/* SCC music machine (scc_machine.h): player + K051649 / K052539 core + blip_buf. Behaviour: docs/AUDIO_ENGINE.md,
 * "SCC and SCC+" ("What runs", "Position, seek and live edits", "Audition", "Mute and ops behaviour"). */
#include "scc_machine.h"

#include <stdlib.h>
#include <string.h>

#include "blip_buf.h"

struct SccMachine {
    uint32_t rate;
    SccCore core;
    blip_t* blip;
    int out_level[SCK_CHANNELS]; /* each channel's level in the resampler input (0 while muted) */
    int muted[SCK_CHANNELS];
    ScPlayer player;
    SccImage image;
    int loaded;
    uint32_t clock_acc;
    uint32_t clocks;
    uint32_t ticks;
    int audition, audition_channel, released, release_ticks;
    short pcm[SCM_MAX_TICK_FRAMES + 16];
};

static void machine_reset(SccMachine* m, int variant) {
    sck_reset(&m->core, variant == SC_VARIANT_PLUS);
    blip_clear(m->blip);
    memset(m->out_level, 0, sizeof m->out_level);
    memset(m->muted, 0, sizeof m->muted);
    m->clock_acc = 0;
    m->clocks = 0;
    m->ticks = 0;
    m->audition = m->released = m->release_ticks = 0;
    m->audition_channel = 0;
}

SccMachine* scm_create(uint32_t sample_rate) {
    if (sample_rate < 8000 || sample_rate > SCM_MAX_SAMPLE_RATE) return NULL;
    SccMachine* m = (SccMachine*)calloc(1, sizeof *m);
    if (!m) return NULL;
    m->rate = sample_rate;
    m->blip = blip_new((int)SCM_MAX_TICK_FRAMES + 64);
    if (!m->blip) {
        scm_destroy(m);
        return NULL;
    }
    blip_set_rates(m->blip, (double)SCM_CLOCK, sample_rate);
    machine_reset(m, SC_VARIANT_SCC);
    return m;
}

void scm_destroy(SccMachine* m) {
    if (!m) return;
    if (m->blip) blip_delete(m->blip);
    free(m);
}

/* The player's last batch onto the core, at one instant. */
static void send_writes(SccMachine* m) {
    const ScPlayer* p = &m->player;
    for (int i = 0; i < p->writes; ++i) sck_write(&m->core, p->write[i].port, p->write[i].reg, p->write[i].val);
}

/* Chip clocks of the next tick: 3579545 / (60 N) by exact accumulation. */
static uint32_t next_clocks(SccMachine* m) {
    const uint32_t period = 60u * (uint32_t)m->image.info.multispeed;
    m->clock_acc += SCM_CLOCK;
    const uint32_t n = m->clock_acc / period;
    m->clock_acc -= n * period;
    m->clocks = n;
    return n;
}

static void on_level(void* ctx, uint32_t clock, int channel, int level) {
    SccMachine* m = (SccMachine*)ctx;
    if (m->muted[channel]) return;
    blip_add_delta(m->blip, clock, (level - m->out_level[channel]) * SCM_LEVEL_SCALE);
    m->out_level[channel] = level;
}

/* Mutes take effect at a tick's first clock: a muted channel steps to 0, an unmuted one back to its level. */
static void sync_mutes(SccMachine* m) {
    for (int c = 0; c < SCK_CHANNELS; ++c) {
        const int want = m->muted[c] ? 0 : sck_level(&m->core, c);
        if (want != m->out_level[c]) {
            blip_add_delta(m->blip, 0, (want - m->out_level[c]) * SCM_LEVEL_SCALE);
            m->out_level[c] = want;
        }
    }
}

/* One player tick: its writes at the first clock, then its clocks rendered into the resampler. */
static void run_tick(SccMachine* m) {
    scp_tick(&m->player, &m->image);
    send_writes(m);
    const uint32_t n = next_clocks(m);
    sync_mutes(m);
    sck_render(&m->core, n, on_level, m);
    blip_end_frame(m->blip, n);
}

/* One player tick with the core advanced silently (seek). */
static void run_silent_tick(SccMachine* m) {
    scp_tick(&m->player, &m->image);
    send_writes(m);
    sck_advance(&m->core, next_clocks(m));
}

static int at(const ScPlayer* p, int order, int row) { return p->order == order && p->row == row && p->tick == 0; }

/* Puts the player at the song start (or at the order's row 0 with its start speed: from_order) and runs it alone
 * until the next tick starts (order, row) (at most `bound` ticks, never leaving the order from_order). Returns the
 * ticks run, or -1 if (order, row) was not reached. */
static int count_ticks(ScPlayer* p, const SccImage* image, int from_order, int order, int row, int bound) {
    if (from_order) scp_place(p, image, order);
    else scp_reset(p, image);
    int n = 0;
    for (; !at(p, order, row) && n < bound && (!from_order || p->order == order); ++n) scp_tick(p, image);
    return at(p, order, row) ? n : -1;
}

int scm_start(SccMachine* m, const SccImage* image, int order, int row, int max_ticks, int* seek_mode) {
    if (seek_mode) *seek_mode = SCM_SEEK_FROM_START;
    if (image->info.loaded != SC_LOADED || order < 0 || order >= image->info.orders || row < 0 ||
        row >= image->info.rows[order])
        return SCM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    m->loaded = 1;
    ScPlayer* p = &m->player;
    int from_order = 0;
    int ticks = count_ticks(p, &m->image, 0, order, row, max_ticks);
    if (ticks < 0) {
        /* Replaying the order's earlier rows takes < 256 rows x 255 ticks, so it has its own bound instead of
         * max_ticks. A row behind a Dxx/Bxx of the same order is unreachable. */
        from_order = 1;
        if (seek_mode) *seek_mode = SCM_SEEK_FROM_ORDER;
        ticks = count_ticks(p, &m->image, 1, order, row, 256 * 256);
        if (ticks < 0) {
            m->loaded = 0;
            return SCM_ERR_IMAGE;
        }
    }
    /* From the reset again: the core follows the player exactly, advanced silently until the last
     * SCM_PREROLL_TICKS x N ticks, which are rendered (output discarded) so the resampler's history settles. */
    machine_reset(m, m->image.info.variant);
    if (from_order) scp_place(p, &m->image, order);
    else scp_reset(p, &m->image);
    send_writes(m);
    if (ticks == 0) return SCM_OK; /* write[] keeps the reset batch, as straight play's start */
    const int preroll = SCM_PREROLL_TICKS * m->image.info.multispeed;
    const int silent = ticks > preroll ? ticks - preroll : 0;
    for (int t = 0; t < silent; ++t) run_silent_tick(m);
    for (int t = silent; t < ticks; ++t) {
        run_tick(m);
        blip_read_samples(m->blip, m->pcm, (int)SCM_MAX_TICK_FRAMES, 0);
    }
    p->writes = 0;
    m->ticks = 0;
    return SCM_OK;
}

void scm_apply_image(SccMachine* m, const SccImage* image) {
    if (!m->loaded || m->audition || image->info.loaded != SC_LOADED) return;
    if (image->info.variant != m->image.info.variant) return;
    scp_relocate(&m->player, &m->image, image);
    memcpy(&m->image, image, sizeof m->image);
}

int scm_audition(SccMachine* m, const SccImage* image, int channel, int note, int slot, int fine) {
    if (image->info.loaded != SC_LOADED) return SCM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    machine_reset(m, m->image.info.variant);
    if (scp_audition(&m->player, &m->image, channel, note, slot, fine)) {
        m->loaded = 0;
        return SCM_ERR_IMAGE;
    }
    m->loaded = 1;
    m->audition = 1;
    m->audition_channel = channel;
    send_writes(m);
    return SCM_OK;
}

/* The audition note cut at once: the player's channel is no longer keyed and its key bit clears on the chip (the
 * host stops ticking the machine). */
static void cut_now(SccMachine* m, int c) {
    ScPlayer* p = &m->player;
    p->ch[c].keyed = 0;
    p->sh_key = (uint8_t)(p->sh_key & ~(1u << c));
    sck_write(&m->core, SCK_PORT_KEY, 0, p->sh_key);
    m->loaded = 0;
}

int scm_release(SccMachine* m) {
    if (!m->loaded || !m->audition) return 0;
    if (m->released) return 1;
    const int c = m->audition_channel;
    if (!m->ticks || scp_silent(&m->player, &m->image, c)) {
        cut_now(m, c); /* before any tick: cut; or already silent */
        return 0;
    }
    ScEnvelope volume;
    sc_envelope(&m->image, m->player.ch[c].slot, SC_I_VOLUME, &volume);
    if (!volume.length || volume.release < 0) {
        cut_now(m, c); /* no release point: the key-off clears the key bit, silent now */
        return 0;
    }
    scp_key_off(&m->player, c);
    send_writes(m);
    m->released = 1;
    m->release_ticks = 0;
    return 1;
}

int scm_mute(SccMachine* m, int channel, int muted) {
    if (channel < 0 || channel >= SCK_CHANNELS) return SCM_ERR_IMAGE;
    m->muted[channel] = muted != 0;
    return SCM_OK;
}

void scm_position(const SccMachine* m, int* order, int* row, int* tick) {
    *order = m->player.order;
    *row = m->player.row;
    *tick = m->player.tick;
}

int scm_tick(SccMachine* m, float* out) {
    if (!m->loaded) return 0;
    if (m->released) {
        const int limit = SCM_RELEASE_SECONDS * 60 * m->image.info.multispeed;
        if (scp_silent(&m->player, &m->image, m->audition_channel) || m->release_ticks >= limit) {
            m->loaded = 0;
            return 0;
        }
        ++m->release_ticks;
    }
    run_tick(m);
    const int frames = blip_read_samples(m->blip, m->pcm, (int)SCM_MAX_TICK_FRAMES, 0);
    for (int i = 0; i < frames; ++i) out[2 * i] = out[2 * i + 1] = m->pcm[i] * (1.0f / 32768.0f);
    ++m->ticks;
    return frames;
}

const ScPlayer* scm_player(const SccMachine* m) { return &m->player; }
const SccCore* scm_core(const SccMachine* m) { return &m->core; }
uint32_t scm_ticks(const SccMachine* m) { return m->ticks; }
uint32_t scm_clocks(const SccMachine* m) { return m->clocks; }
