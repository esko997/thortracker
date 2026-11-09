/* SAA1099 music machine (saa_machine.h): player + one or two SAA1099 cores + two blip_buf buffers. Behaviour:
 * docs/AUDIO_ENGINE.md, "SAA1099" ("What runs", "Position, seek and live edits", "Audition", "Mute and ops
 * behaviour"). */
#include "saa_machine.h"

#include <stdlib.h>
#include <string.h>

#include "blip_buf.h"

/* emit's context: which chip's channels the events are */
typedef struct EmitCtx {
    struct SaaMachine* m;
    int chip;
} EmitCtx;

struct SaaMachine {
    uint32_t rate;
    SacCore core[2];
    blip_t* blip[2];                  /* left, right */
    int out_l[SA_MAX_CHANNELS];       /* each tracker channel's output in the resampler input (0 while muted) */
    int out_r[SA_MAX_CHANNELS];
    int muted[SA_MAX_CHANNELS];
    EmitCtx ctx[2];
    SaPlayer player;
    SaaImage image;
    int loaded;
    int chips, channels, scale, tick_rate; /* of the variant the machine was reset for */
    uint32_t clock;
    uint32_t clock_acc;
    uint32_t clocks;
    uint32_t ticks;
    int audition, audition_channel, released, release_ticks;
    short pcm[2 * (SAAM_MAX_TICK_FRAMES + 16)];
};

static void machine_reset(SaaMachine* m, int variant) {
    const int cms = variant == SA_VARIANT_CMS;
    m->chips = cms ? 2 : 1;
    m->channels = cms ? SA_CHANNELS_CMS : SA_CHANNELS_SAM;
    m->scale = cms ? SAAM_LEVEL_SCALE_CMS : SAAM_LEVEL_SCALE_SAM;
    m->tick_rate = cms ? SAAM_RATE_CMS : SAAM_RATE_SAM;
    m->clock = cms ? SAC_CLOCK_CMS : SAC_CLOCK_SAM;
    for (int chip = 0; chip < 2; ++chip) {
        sac_reset(&m->core[chip]);
        blip_set_rates(m->blip[chip], (double)m->clock, m->rate);
        blip_clear(m->blip[chip]);
    }
    memset(m->out_l, 0, sizeof m->out_l);
    memset(m->out_r, 0, sizeof m->out_r);
    memset(m->muted, 0, sizeof m->muted);
    m->clock_acc = 0;
    m->clocks = 0;
    m->ticks = 0;
    m->audition = m->released = m->release_ticks = 0;
    m->audition_channel = 0;
}

SaaMachine* saam_create(uint32_t sample_rate) {
    if (sample_rate < 8000 || sample_rate > SAAM_MAX_SAMPLE_RATE) return NULL;
    SaaMachine* m = (SaaMachine*)calloc(1, sizeof *m);
    if (!m) return NULL;
    m->rate = sample_rate;
    for (int side = 0; side < 2; ++side) {
        m->blip[side] = blip_new((int)SAAM_MAX_TICK_FRAMES + 64);
        if (!m->blip[side]) {
            saam_destroy(m);
            return NULL;
        }
    }
    for (int chip = 0; chip < 2; ++chip) {
        m->ctx[chip].m = m;
        m->ctx[chip].chip = chip;
    }
    machine_reset(m, SA_VARIANT_SAM);
    return m;
}

void saam_destroy(SaaMachine* m) {
    if (!m) return;
    for (int side = 0; side < 2; ++side)
        if (m->blip[side]) blip_delete(m->blip[side]);
    free(m);
}

/* The player's last batch onto the cores, at one instant. */
static void send_writes(SaaMachine* m) {
    const SaPlayer* p = &m->player;
    for (int i = 0; i < p->writes; ++i) sac_write(&m->core[p->write[i].chip & 1], p->write[i].reg, p->write[i].val);
}

/* Chip clocks of the next tick: clock / (rate N) by exact accumulation. */
static uint32_t next_clocks(SaaMachine* m) {
    const uint32_t period = (uint32_t)m->tick_rate * (uint32_t)m->image.info.multispeed;
    m->clock_acc += m->clock;
    const uint32_t n = m->clock_acc / period;
    m->clock_acc -= n * period;
    m->clocks = n;
    return n;
}

static void on_output(void* ctx, uint32_t clock, int channel, int left, int right) {
    EmitCtx* e = (EmitCtx*)ctx;
    SaaMachine* m = e->m;
    const int c = e->chip * SA_CHIP_CHANNELS + channel;
    if (m->muted[c]) return;
    if (left != m->out_l[c]) blip_add_delta(m->blip[0], clock, (left - m->out_l[c]) * m->scale);
    if (right != m->out_r[c]) blip_add_delta(m->blip[1], clock, (right - m->out_r[c]) * m->scale);
    m->out_l[c] = left;
    m->out_r[c] = right;
}

/* Mutes take effect at a tick's first clock: a muted channel steps to 0, an unmuted one back to the core's output as
 * last rendered (the render then emits what this tick's writes changed). */
static void sync_mutes(SaaMachine* m) {
    for (int c = 0; c < m->channels; ++c) {
        const SacCore* core = &m->core[c / SA_CHIP_CHANNELS];
        const int l = c % SA_CHIP_CHANNELS;
        const int want_l = m->muted[c] ? 0 : core->out_l[l], want_r = m->muted[c] ? 0 : core->out_r[l];
        if (want_l != m->out_l[c]) blip_add_delta(m->blip[0], 0, (want_l - m->out_l[c]) * m->scale);
        if (want_r != m->out_r[c]) blip_add_delta(m->blip[1], 0, (want_r - m->out_r[c]) * m->scale);
        m->out_l[c] = want_l;
        m->out_r[c] = want_r;
    }
}

/* One player tick: its writes at the first clock, then its clocks rendered into the resampler. */
static void run_tick(SaaMachine* m) {
    sap_tick(&m->player, &m->image);
    send_writes(m);
    const uint32_t n = next_clocks(m);
    sync_mutes(m);
    for (int chip = 0; chip < m->chips; ++chip) sac_render(&m->core[chip], n, on_output, &m->ctx[chip]);
    blip_end_frame(m->blip[0], n);
    blip_end_frame(m->blip[1], n);
}

/* One player tick with exact chip and fractional resampler time, but no signal history (seek). */
static void run_silent_tick(SaaMachine* m) {
    sap_tick(&m->player, &m->image);
    send_writes(m);
    const uint32_t n = next_clocks(m);
    for (int chip = 0; chip < m->chips; ++chip) sac_advance(&m->core[chip], n);
    /* Pre-roll settles the filter, not a fractional-sample timing error. */
    blip_skip_silence(m->blip[0], n);
    blip_skip_silence(m->blip[1], n);
}

/* The resampled frames of the last tick, interleaved into pcm; returns the frame count. */
static int read_frames(SaaMachine* m) {
    const int n = blip_read_samples(m->blip[0], m->pcm, (int)SAAM_MAX_TICK_FRAMES, 1);
    blip_read_samples(m->blip[1], m->pcm + 1, (int)SAAM_MAX_TICK_FRAMES, 1);
    return n;
}

static int at(const SaPlayer* p, int order, int row) { return p->order == order && p->row == row && p->tick == 0; }

/* Puts the player at the song start (or at the order's row 0 with its start speed: from_order) and runs it alone
 * until the next tick starts (order, row) (at most `bound` ticks, never leaving the order from_order). Returns the
 * ticks run, or -1 if (order, row) was not reached. */
static int count_ticks(SaPlayer* p, const SaaImage* image, int from_order, int order, int row, int bound) {
    if (from_order) sap_place(p, image, order);
    else sap_reset(p, image);
    int n = 0;
    for (; !at(p, order, row) && n < bound && (!from_order || p->order == order); ++n) sap_tick(p, image);
    return at(p, order, row) ? n : -1;
}

int saam_start(SaaMachine* m, const SaaImage* image, int order, int row, int max_ticks, int* seek_mode) {
    if (seek_mode) *seek_mode = SAAM_SEEK_FROM_START;
    if (image->info.loaded != SA_LOADED || order < 0 || order >= image->info.orders || row < 0 ||
        row >= image->info.rows[order])
        return SAAM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    m->loaded = 1;
    SaPlayer* p = &m->player;
    int from_order = 0;
    int ticks = count_ticks(p, &m->image, 0, order, row, max_ticks);
    if (ticks < 0) {
        /* Replaying the order's earlier rows takes < 256 rows x 255 ticks, so it has its own bound instead of
         * max_ticks. A row behind a Dxx/Bxx of the same order is unreachable. */
        from_order = 1;
        if (seek_mode) *seek_mode = SAAM_SEEK_FROM_ORDER;
        ticks = count_ticks(p, &m->image, 1, order, row, 256 * 256);
        if (ticks < 0) {
            m->loaded = 0;
            return SAAM_ERR_IMAGE;
        }
    }
    /* From the reset again: the cores follow the player exactly, advanced silently until the last second, which is
     * rendered (output discarded) so the resampler's history settles. */
    machine_reset(m, m->image.info.variant);
    if (from_order) sap_place(p, &m->image, order);
    else sap_reset(p, &m->image);
    send_writes(m);
    if (ticks == 0) return SAAM_OK; /* write[] keeps the reset batch, as straight play's start */
    const int preroll = SAAM_PREROLL_SECONDS * m->tick_rate * m->image.info.multispeed;
    const int silent = ticks > preroll ? ticks - preroll : 0;
    for (int t = 0; t < silent; ++t) run_silent_tick(m);
    for (int t = silent; t < ticks; ++t) {
        run_tick(m);
        read_frames(m);
    }
    p->writes = 0;
    m->ticks = 0;
    return SAAM_OK;
}

void saam_apply_image(SaaMachine* m, const SaaImage* image) {
    if (!m->loaded || m->audition || image->info.loaded != SA_LOADED) return;
    if (image->info.variant != m->image.info.variant) return;
    sap_relocate(&m->player, &m->image, image);
    memcpy(&m->image, image, sizeof m->image);
}

int saam_audition(SaaMachine* m, const SaaImage* image, int channel, int note, int slot, int fine) {
    if (image->info.loaded != SA_LOADED) return SAAM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    machine_reset(m, m->image.info.variant);
    if (sap_audition(&m->player, &m->image, channel, note, slot, fine)) {
        m->loaded = 0;
        return SAAM_ERR_IMAGE;
    }
    m->loaded = 1;
    m->audition = 1;
    m->audition_channel = channel;
    send_writes(m);
    return SAAM_OK;
}

/* The audition note cut at once: the player's channel is no longer held and its amplitude is zeroed on the chip (the
 * host stops ticking the machine). */
static void cut_now(SaaMachine* m, int c) {
    SaPlayer* p = &m->player;
    const int chip = c / SA_CHIP_CHANNELS, l = c % SA_CHIP_CHANNELS;
    p->ch[c].keyed = 0;
    p->sh[chip][SAC_REG_AMP + l] = 0;
    sac_write(&m->core[chip], SAC_REG_AMP + l, 0);
    m->loaded = 0;
}

int saam_release(SaaMachine* m) {
    if (!m->loaded || !m->audition) return 0;
    if (m->released) return 1;
    const int c = m->audition_channel;
    if (!m->ticks || sap_silent(&m->player, &m->image, c)) {
        cut_now(m, c); /* before any tick: cut; or already silent */
        return 0;
    }
    SaEnvelope volume;
    sa_envelope(&m->image, m->player.ch[c].slot, SA_I_VOLUME, &volume);
    if (!volume.length || volume.release < 0) {
        cut_now(m, c); /* no release point: the key-off zeroes the amplitude, silent now */
        return 0;
    }
    sap_key_off(&m->player, c);
    send_writes(m);
    m->released = 1;
    m->release_ticks = 0;
    return 1;
}

int saam_mute(SaaMachine* m, int channel, int muted) {
    if (channel < 0 || channel >= m->channels) return SAAM_ERR_IMAGE;
    m->muted[channel] = muted != 0;
    return SAAM_OK;
}

void saam_position(const SaaMachine* m, int* order, int* row, int* tick) {
    *order = m->player.order;
    *row = m->player.row;
    *tick = m->player.tick;
}

int saam_tick(SaaMachine* m, float* out) {
    if (!m->loaded) return 0;
    if (m->released) {
        const int limit = SAAM_RELEASE_SECONDS * m->tick_rate * m->image.info.multispeed;
        if (sap_silent(&m->player, &m->image, m->audition_channel) || m->release_ticks >= limit) {
            m->loaded = 0;
            return 0;
        }
        ++m->release_ticks;
    }
    run_tick(m);
    const int frames = read_frames(m);
    for (int i = 0; i < 2 * frames; ++i) out[i] = m->pcm[i] * (1.0f / 32768.0f);
    ++m->ticks;
    return frames;
}

const SaPlayer* saam_player(const SaaMachine* m) { return &m->player; }
const SacCore* saam_core(const SaaMachine* m, int chip) { return &m->core[chip & 1]; }
uint32_t saam_ticks(const SaaMachine* m) { return m->ticks; }
uint32_t saam_clocks(const SaaMachine* m) { return m->clocks; }
uint32_t saam_clock(const SaaMachine* m) { return m->clock; }
