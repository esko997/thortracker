/* Atari 2600 music machine (tia_machine.h): player + Stella 7.0's TIA audio channels (tia_stella.h) on the colour-clock
 * timeline + blip_buf. The event positions and the mono mixing formula are Stella 7.0's (Audio.cxx / Audio.hxx:
 * Audio::tick and mixingTableEntry, by the Stella Team, GPL-2.0-or-later), reimplemented here, not vendored. */
#include "tia_machine.h"

#include <stdlib.h>
#include <string.h>

#include "blip_buf.h"
#include "tia_stella.h"

/* floor(0x7fff * s / 30.0 * 60.0 / (30.0 + s)), s = 0..30 (Stella's mixingTableEntry(s, 0x1e)). */
const int16_t TIM_MIX[31] = {
    0,     2114,  4095,  5957,  7709,  9362,  10922, 12398, 13796, 15123, 16383, 17582, 18724, 19812, 20851, 21844,
    22794, 23703, 24575, 25411, 26213, 26984, 27725, 28439, 29126, 29788, 30426, 31042, 31637, 32211, 32767,
};

struct TiaMachine {
    uint32_t rate;
    int region;
    int lines;                 /* scanlines per tick */
    int64_t frame_clocks;      /* colour clocks per tick: lines x 228 */
    int release_limit;         /* ticks in TIM_RELEASE_SECONDS, rounded up */
    TiaStella stella;
    blip_t* blip;
    TiPlayer player;
    TiaImage image;
    int loaded;
    int64_t tick_index;        /* ticks since the reset (seek included): tick k starts at clock k frame_clocks */
    uint32_t ticks;            /* ticks since start/audition, seek excluded */
    int frames;                /* host frames of the last tick */
    int level;                 /* mono level as last fed to blip */
    unsigned mute;             /* bit c: channel c counts as 0 in the mix */
    int audition, audition_channel, released, release_ticks, ended;
    TimTraceFn trace;
    void* trace_ctx;
    TimLevelFn probe;
    void* probe_ctx;
    short pcm[TIM_MAX_TICK_FRAMES + 16];
};

/* Sends the player's current batch to the chip (traced first). */
static void send(TiaMachine* m, int64_t clock, int kind) {
    const TiPlayer* p = &m->player;
    if (m->trace) m->trace(m->trace_ctx, clock, kind, p->write, p->writes);
    for (int i = 0; i < p->writes; ++i) {
        const int reg = p->write[i].reg;
        const uint8_t val = p->write[i].val;
        switch (reg) {
        case TI_REG_AUDC0: case TI_REG_AUDC1: tis_audc(&m->stella, reg - TI_REG_AUDC0, val); break;
        case TI_REG_AUDF0: case TI_REG_AUDF1: tis_audf(&m->stella, reg - TI_REG_AUDF0, val); break;
        case TI_REG_AUDV0: case TI_REG_AUDV1: tis_audv(&m->stella, reg - TI_REG_AUDV0, val); break;
        default: break;
        }
    }
}

/* The mono level from the channels' present output (muted channels 0), as a blip delta at `t` clocks into the tick. */
static void mix(TiaMachine* m, unsigned t) {
    const int v0 = (m->mute & 1) ? 0 : tis_actual_volume(&m->stella, 0);
    const int v1 = (m->mute & 2) ? 0 : tis_actual_volume(&m->stella, 1);
    const int level = TIM_MIX[v0 + v1];
    if (level != m->level) {
        blip_add_delta(m->blip, t, level - m->level);
        m->level = level;
    }
}

/* After a phase 1 at line clock `t` of the tick starting at `base`: the probe, then the new level from clock t + 1. */
static void after_phase1(TiaMachine* m, int64_t base, unsigned t) {
    if (m->probe) m->probe(m->probe_ctx, base + t, tis_actual_volume(&m->stella, 0), tis_actual_volume(&m->stella, 1));
    mix(m, t + 1);
}

/* Reset: chip, resampler, level, mutes and time, then the player's reset batch at clock 0 (call after the player's
 * reset). */
static void machine_reset(TiaMachine* m) {
    tis_reset(&m->stella);
    blip_clear(m->blip);
    m->level = 0;
    m->mute = 0;
    m->tick_index = 0;
    m->ticks = 0;
    m->frames = 0;
    m->audition = m->released = m->release_ticks = m->ended = 0;
    send(m, 0, TIM_TRACE_TICK);
    mix(m, 0);
}

TiaMachine* tim_create(uint32_t sample_rate, int region) {
    if (sample_rate < 8000 || sample_rate > TIM_MAX_SAMPLE_RATE) return NULL;
    if (region != TI_REGION_NTSC && region != TI_REGION_PAL) return NULL;
    TiaMachine* m = (TiaMachine*)calloc(1, sizeof *m);
    if (!m) return NULL;
    m->rate = sample_rate;
    m->region = region;
    const int pal = region == TI_REGION_PAL;
    m->lines = pal ? TIM_LINES_PAL : TIM_LINES_NTSC;
    m->frame_clocks = (int64_t)m->lines * TIM_CLOCKS_PER_LINE;
    const int64_t num = pal ? TIM_CLOCK_PAL_NUM : TIM_CLOCK_NTSC_NUM, den = pal ? TIM_CLOCK_PAL_DEN : TIM_CLOCK_NTSC_DEN;
    const int64_t limit_den = den * m->frame_clocks;
    m->release_limit = (int)((TIM_RELEASE_SECONDS * num + limit_den - 1) / limit_den);
    tis_init(&m->stella);
    m->blip = blip_new((int)TIM_MAX_TICK_FRAMES + 64);
    if (!m->blip) {
        tim_destroy(m);
        return NULL;
    }
    blip_set_rates(m->blip, (double)num / (double)den, sample_rate);
    blip_clear(m->blip);
    return m;
}

void tim_destroy(TiaMachine* m) {
    if (!m) return;
    if (m->blip) blip_delete(m->blip);
    tis_destroy(&m->stella);
    free(m);
}

/* One player tick and its frame of audio events, clocked through the chip into the resampler (frames then wait in
 * the blip buffer). */
static void run_tick(TiaMachine* m) {
    const int64_t base = m->tick_index * m->frame_clocks;
    tip_tick(&m->player, &m->image);
    send(m, base, TIM_TRACE_TICK);
    mix(m, 0);
    for (int l = 0; l < m->lines; ++l) {
        const unsigned line = (unsigned)(l * TIM_CLOCKS_PER_LINE);
        tis_phase0(&m->stella); /* TIM_PHASE0_A */
        tis_phase1(&m->stella); /* TIM_PHASE1_A */
        after_phase1(m, base, line + TIM_PHASE1_A);
        tis_phase0(&m->stella); /* TIM_PHASE0_B */
        tis_phase1(&m->stella); /* TIM_PHASE1_B */
        after_phase1(m, base, line + TIM_PHASE1_B);
    }
    blip_end_frame(m->blip, (unsigned)m->frame_clocks);
    ++m->tick_index;
}

static int at(const TiPlayer* p, int order, int row) { return p->order == order && p->row == row && p->tick == 0; }

/* Puts the player at the song start (or at the order's row 0 with its start speed: from_order) and runs it alone
 * until the next tick starts (order, row) - at most `bound` ticks, never leaving the order with from_order. Returns
 * the ticks run, or -1 if (order, row) was not reached. */
static int find_ticks(TiPlayer* p, const TiaImage* image, int from_order, int order, int row, int bound) {
    if (from_order) tip_place(p, image, order);
    else tip_reset(p, image);
    int n = 0;
    while (!at(p, order, row) && n < bound && (!from_order || p->order == order)) {
        tip_tick(p, image);
        ++n;
    }
    return at(p, order, row) ? n : -1;
}

int tim_start(TiaMachine* m, const TiaImage* image, int order, int row, int max_ticks, int* seek_mode) {
    if (seek_mode) *seek_mode = TIM_SEEK_FROM_START;
    if (image->info.loaded != TI_LOADED || image->info.region != m->region || order < 0 || order >= image->info.orders ||
        row < 0 || row >= image->info.rows[order])
        return TIM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    m->loaded = 0;
    TiPlayer* p = &m->player;
    int from_order = 0;
    int ticks = find_ticks(p, &m->image, 0, order, row, max_ticks < 0 ? 0 : max_ticks);
    if (ticks < 0) {
        /* Replaying the order's earlier rows takes < 256 rows x 255 ticks, so it has its own bound instead of
         * max_ticks. A row behind a Dxx/Bxx of the same order is unreachable. */
        from_order = 1;
        if (seek_mode) *seek_mode = TIM_SEEK_FROM_ORDER;
        ticks = find_ticks(p, &m->image, 1, order, row, 256 * 256);
        if (ticks < 0) return TIM_ERR_IMAGE;
    }
    /* The same ticks again, clocked as audible playback (chip, resampler), the output discarded. */
    if (from_order) tip_place(p, &m->image, order);
    else tip_reset(p, &m->image);
    machine_reset(m);
    for (int t = 0; t < ticks; ++t) {
        run_tick(m);
        blip_read_samples(m->blip, m->pcm, (int)TIM_MAX_TICK_FRAMES, 0);
    }
    m->ticks = 0;
    m->frames = 0;
    m->loaded = 1;
    return TIM_OK;
}

void tim_apply_image(TiaMachine* m, const TiaImage* image) {
    if (!m->loaded || m->audition || image->info.loaded != TI_LOADED || image->info.region != m->region) return;
    tip_relocate(&m->player, &m->image, image);
    memcpy(&m->image, image, sizeof m->image);
}

int tim_audition(TiaMachine* m, const TiaImage* image, int channel, int note, int slot, int fine) {
    if (image->info.loaded != TI_LOADED || image->info.region != m->region) return TIM_ERR_IMAGE;
    TiPlayer* p = &m->player;
    /* Arguments first (tip_audition checks them against the image before touching the player). */
    if (tip_audition(p, image, channel, note, slot, fine)) return TIM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    m->loaded = 0;
    tip_audition(p, &m->image, channel, note, slot, fine); /* the same state, from the machine's copy */
    machine_reset(m);
    m->loaded = 1;
    m->audition = 1;
    m->audition_channel = channel;
    return TIM_OK;
}

int tim_release(TiaMachine* m) {
    if (!m->loaded || !m->audition) return 0;
    if (!m->ticks) {
        m->loaded = 0;
        return 0;
    }
    if (m->released) return m->ended ? 0 : 1;
    TiPlayer* p = &m->player;
    tip_off(p, &m->image, m->audition_channel);
    send(m, m->tick_index * m->frame_clocks, TIM_TRACE_OFF);
    m->released = 1;
    m->release_ticks = 0;
    if (!p->ch[m->audition_channel].sounding) {
        m->ended = 1;
        return 0;
    }
    return 1;
}

int tim_mute(TiaMachine* m, int channel, int muted) {
    if (channel < 0 || channel >= TI_CHANNELS) return TIM_ERR_IMAGE;
    if (muted) m->mute |= 1u << channel;
    else m->mute &= ~(1u << channel);
    return TIM_OK;
}

void tim_position(const TiaMachine* m, int* order, int* row, int* tick) {
    *order = m->player.order;
    *row = m->player.row;
    *tick = m->player.tick;
}

/* The audition note has ended: its channel stopped, or, released, its written AUDV is 0 with the volume envelope at
 * its last step. */
static int audition_done(const TiaMachine* m) {
    const TiChannel* ch = &m->player.ch[m->audition_channel];
    if (!ch->sounding) return 1;
    if (!m->released || !ch->released || ch->audv) return 0;
    const uint8_t* env = m->image.bytes + TI_OFF_INSTRUMENTS + TI_INSTRUMENT_SIZE * (ch->slot - 1) + TI_I_ENVELOPES +
                         TI_ENVELOPE_BYTES * TI_ENV_VOLUME;
    const int length = env[2];
    return length && ch->env[TI_ENV_VOLUME] >= length - 1;
}

int tim_tick(TiaMachine* m, float* out) {
    if (!m->loaded) return 0;
    if (m->audition && m->ticks) {
        if (m->ended || audition_done(m) || (m->released && m->release_ticks >= m->release_limit)) {
            m->ended = 1;
            m->loaded = 0;
            return 0;
        }
        if (m->released) ++m->release_ticks;
    }
    run_tick(m);
    const int frames = blip_read_samples(m->blip, m->pcm, (int)TIM_MAX_TICK_FRAMES, 0);
    for (int i = 0; i < frames; ++i) out[2 * i] = out[2 * i + 1] = m->pcm[i] * (1.0f / 32768.0f);
    m->frames = frames;
    ++m->ticks;
    return frames;
}

const TiPlayer* tim_player(const TiaMachine* m) { return &m->player; }
uint32_t tim_ticks(const TiaMachine* m) { return m->ticks; }
int tim_frames(const TiaMachine* m) { return m->frames; }
int64_t tim_clock(const TiaMachine* m) { return m->tick_index * m->frame_clocks; }
int tim_level(const TiaMachine* m) { return m->level; }
void tim_set_trace(TiaMachine* m, TimTraceFn fn, void* ctx) {
    m->trace = fn;
    m->trace_ctx = ctx;
}
void tim_set_level_probe(TiaMachine* m, TimLevelFn fn, void* ctx) {
    m->probe = fn;
    m->probe_ctx = ctx;
}
void tim_channel_state(const TiaMachine* m, int c, uint8_t out[10]) { tis_state(&m->stella, c, out); }
