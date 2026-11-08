/* OPM music machine (opm_machine.h): player + ymfm's YM2151 (opm_ymfm.h) + blip_buf. */
#include "opm_machine.h"

#include <stdlib.h>
#include <string.h>

#include "blip_buf.h"
#include "opm_ymfm.h"

#define QUIET 0x380 /* ymfm's EG_QUIET: an operator at or above this attenuation is silent */

struct OpmMachine {
    uint32_t rate;
    YmOpm* chip;
    blip_t* blip[2];
    int last[2];
    OmPlayer player;
    OpmImage image;
    int loaded;
    uint32_t native_acc;
    int native_samples;
    uint32_t ticks;
    unsigned mute; /* bit c: channel c left out of the output sum */
    int audition, audition_channel, released, release_ticks;
    short pcm[2 * OMM_MAX_TICK_FRAMES + 16];
};

/* Writes write[from .. to - 1]. */
static void send_range(OpmMachine* m, int from, int to) {
    const OmPlayer* p = &m->player;
    for (int i = from; i < to; ++i) omc_write(m->chip, p->write[i].reg, p->write[i].val);
}

static void send_writes(OpmMachine* m) { send_range(m, 0, m->player.writes); }

static void generate(OpmMachine* m, int from, int to) {
    for (int i = from; i < to; ++i) {
        int32_t s[2];
        omc_generate(m->chip, m->mute, &s[0], &s[1]);
        for (int k = 0; k < 2; ++k) {
            if (s[k] != m->last[k]) {
                blip_add_delta(m->blip[k], (unsigned)i, s[k] - m->last[k]);
                m->last[k] = s[k];
            }
        }
    }
}

static void chip_reset(OpmMachine* m) {
    omc_reset(m->chip);
    for (int i = 0; i < 2; ++i) {
        blip_clear(m->blip[i]);
        m->last[i] = 0;
    }
    m->native_acc = 0;
    m->ticks = 0;
    m->audition = m->released = m->release_ticks = 0;
    m->mute = 0;
}

OpmMachine* omm_create(uint32_t sample_rate) {
    if (sample_rate < 8000 || sample_rate > OMM_MAX_SAMPLE_RATE) return NULL;
    OpmMachine* m = (OpmMachine*)calloc(1, sizeof *m);
    if (!m) return NULL;
    m->rate = sample_rate;
    m->chip = omc_create();
    if (!m->chip) {
        omm_destroy(m);
        return NULL;
    }
    for (int i = 0; i < 2; ++i) {
        m->blip[i] = blip_new(OMM_MAX_TICK_FRAMES + 64);
        if (!m->blip[i]) {
            omm_destroy(m);
            return NULL;
        }
        blip_set_rates(m->blip[i], (double)OMM_CLOCK / OMM_NATIVE_DIVIDER, sample_rate);
    }
    chip_reset(m);
    return m;
}

void omm_destroy(OpmMachine* m) {
    if (!m) return;
    for (int i = 0; i < 2; ++i)
        if (m->blip[i]) blip_delete(m->blip[i]);
    if (m->chip) omc_destroy(m->chip);
    free(m);
}

/* The player's register state onto a freshly reset chip: the shared registers, the channels' output/connection
 * ($20-$27, always: the chip's reset value differs between chips), the rest of the channel and operator registers,
 * then the key-ons of keyed channels. Everything except $20-$27 only where non-zero (the reset chip holds 0). */
static void restore(OpmMachine* m) {
    const OmPlayer* p = &m->player;
    const uint8_t* s = p->shadow;
    static const int shared[] = {0x0F, 0x10, 0x11, 0x18, 0x19};
    for (int i = 0; i < 5; ++i)
        if (s[shared[i]]) omc_write(m->chip, (uint8_t)shared[i], s[shared[i]]);
    if (s[OMP_SHADOW_PMD]) omc_write(m->chip, 0x19, (uint8_t)(0x80 | s[OMP_SHADOW_PMD]));
    if (s[0x1B]) omc_write(m->chip, 0x1B, s[0x1B]);
    if (s[0x14]) omc_write(m->chip, 0x14, s[0x14]);
    for (int r = 0x20; r < 0x28; ++r) omc_write(m->chip, (uint8_t)r, s[r]);
    for (int r = 0x28; r < 0x100; ++r)
        if (s[r]) omc_write(m->chip, (uint8_t)r, s[r]);
    for (int c = 0; c < OM_CHANNELS; ++c)
        if (p->ch[c].keyed) omc_write(m->chip, 0x08, (uint8_t)(OMP_KEY_ALL | c));
}

static int at(const OmPlayer* p, int order, int row) { return p->order == order && p->row == row && p->tick == 0; }

/* Puts the player at the song start (or at the order's row 0 with its start speed: from_order) and runs it alone
 * for `ticks` ticks, or until the next tick starts (order, row) when ticks < 0 (at most `bound` ticks, never
 * leaving the order from_order). Returns the ticks run, or -1 if (order, row) was not reached. */
static int run_player(OmPlayer* p, const OpmImage* image, int from_order, int order, int row, int ticks, int bound) {
    if (from_order) omp_place(p, image, order);
    else omp_reset(p, image);
    int n = 0;
    for (; ticks < 0 ? !at(p, order, row) && n < bound && (!from_order || p->order == order) : n < ticks; ++n)
        omp_tick(p, image);
    return ticks >= 0 || at(p, order, row) ? n : -1;
}

static int run_tick(OpmMachine* m);

int omm_start(OpmMachine* m, const OpmImage* image, int order, int row, int max_ticks, int* seek_mode) {
    if (seek_mode) *seek_mode = OMM_SEEK_FROM_START;
    if (image->info.loaded != OM_LOADED || order < 0 || order >= image->info.orders || row < 0 ||
        row >= image->info.rows[order])
        return OMM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    m->loaded = 1;
    chip_reset(m);
    OmPlayer* p = &m->player;
    int from_order = 0;
    int ticks = run_player(p, &m->image, 0, order, row, -1, max_ticks);
    if (ticks < 0) {
        /* Replaying the order's earlier rows takes < 256 rows x 255 ticks, so it has its own bound instead of
         * max_ticks. A row behind a Dxx/Bxx of the same order is unreachable. */
        from_order = 1;
        if (seek_mode) *seek_mode = OMM_SEEK_FROM_ORDER;
        ticks = run_player(p, &m->image, 1, order, row, -1, 256 * 256);
        if (ticks < 0) {
            m->loaded = 0;
            return OMM_ERR_IMAGE;
        }
    }
    if (ticks == 0 && !from_order) {
        send_writes(m); /* plain start at 0/0: the init writes as straight play sends them */
        return OMM_OK;
    }
    /* The chip is clocked through the last OMM_PREROLL_TICKS x multispeed ticks before the target (the player's own
     * writes, output discarded), so held notes and release tails arrive with their envelopes under way. Earlier
     * ticks run the player alone, then the chip receives its register state. */
    const int preroll = OMM_PREROLL_TICKS * m->image.info.multispeed;
    const int silent = ticks > preroll ? ticks - preroll : 0;
    run_player(p, &m->image, from_order, order, row, silent, 0);
    if (silent) restore(m);
    else send_writes(m); /* from the song start (or the placement): the chip's state is straight play's */
    for (int t = silent; t < ticks; ++t) {
        run_tick(m);
        for (int k = 0; k < 2; ++k) blip_read_samples(m->blip[k], m->pcm, (int)OMM_MAX_TICK_FRAMES, 0);
    }
    p->writes = p->offs = 0;
    m->ticks = 0;
    return OMM_OK;
}

void omm_apply_image(OpmMachine* m, const OpmImage* image) {
    if (!m->loaded || m->audition || image->info.loaded != OM_LOADED) return;
    omp_relocate(&m->player, &m->image, image);
    memcpy(&m->image, image, sizeof m->image);
}

int omm_audition(OpmMachine* m, const OpmImage* image, int channel, int note, int slot, int fine) {
    if (image->info.loaded != OM_LOADED) return OMM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    chip_reset(m);
    if (omp_audition(&m->player, &m->image, channel, note, slot, fine)) {
        m->loaded = 0;
        return OMM_ERR_IMAGE;
    }
    m->loaded = 1;
    m->audition = 1;
    m->audition_channel = channel;
    send_writes(m);
    return OMM_OK;
}

/* Every carrier of the audition channel at or above ymfm's quiet attenuation. */
static int audition_silent(const OpmMachine* m) {
    const OmChannel* ch = &m->player.ch[m->audition_channel];
    for (int k = 0; k < 4; ++k)
        if ((ch->carriers >> k & 1) && omc_envelope(m->chip, m->audition_channel, k) < QUIET) return 0;
    return 1;
}

int omm_release(OpmMachine* m) {
    if (!m->loaded || !m->audition) return 0;
    if (!m->ticks || audition_silent(m)) {
        m->loaded = 0;
        return 0;
    }
    if (!m->released) {
        omp_key_off(&m->player, m->audition_channel);
        send_writes(m);
        m->released = 1;
        m->release_ticks = 0;
    }
    return 1;
}

int omm_mute(OpmMachine* m, int channel, int muted) {
    if (channel < 0 || channel >= OM_CHANNELS) return OMM_ERR_IMAGE;
    if (muted) m->mute |= 1u << channel;
    else m->mute &= ~(1u << channel);
    return OMM_OK;
}

void omm_position(const OpmMachine* m, int* order, int* row, int* tick) {
    *order = m->player.order;
    *row = m->player.row;
    *tick = m->player.tick;
}

/* Sends the player's batch (the key-off phase one chip sample before the rest, so a key-off + key-on retriggers:
 * opm_player.h) and renders n native samples into the resampler. */
static void play_batch(OpmMachine* m, int n) {
    const int offs = m->player.offs;
    if (offs) {
        send_range(m, 0, offs);
        generate(m, 0, 1);
    }
    send_range(m, offs, m->player.writes);
    generate(m, offs ? 1 : 0, n);
    for (int k = 0; k < 2; ++k) blip_end_frame(m->blip[k], (unsigned)n);
}

/* Native samples of the next tick: 4000000 / (64 x 60 x multispeed) by exact accumulation. */
static int tick_samples(OpmMachine* m) {
    const uint32_t period = 60u * OMM_NATIVE_DIVIDER * (uint32_t)m->image.info.multispeed;
    m->native_acc += OMM_CLOCK;
    const int n = (int)(m->native_acc / period);
    m->native_acc -= (uint32_t)n * period;
    m->native_samples = n;
    return n;
}

/* One player tick and its chip samples into the resampler (frames then wait in the blip buffers). */
static int run_tick(OpmMachine* m) {
    omp_tick(&m->player, &m->image);
    const int n = tick_samples(m);
    play_batch(m, n);
    return n;
}

int omm_tick(OpmMachine* m, float* out) {
    if (!m->loaded) return 0;
    if (m->released) {
        const int limit = OMM_RELEASE_SECONDS * 60 * m->image.info.multispeed;
        if (audition_silent(m) || m->release_ticks >= limit) {
            m->loaded = 0;
            return 0;
        }
        ++m->release_ticks;
    }
    run_tick(m);
    int frames = 0;
    for (int k = 0; k < 2; ++k) frames = blip_read_samples(m->blip[k], m->pcm + k, (int)OMM_MAX_TICK_FRAMES, 1);
    for (int i = 0; i < 2 * frames; ++i) out[i] = m->pcm[i] * (1.0f / 32768.0f);
    ++m->ticks;
    return frames;
}

const OmPlayer* omm_player(const OpmMachine* m) { return &m->player; }
uint32_t omm_ticks(const OpmMachine* m) { return m->ticks; }
int omm_native_samples(const OpmMachine* m) { return m->native_samples; }
int omm_envelope(const OpmMachine* m, int channel, int op) { return omc_envelope(m->chip, channel, op); }
