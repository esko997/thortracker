/* Philips SAA1099 sound core (saa_core.h).
 *
 * license:BSD-3-Clause
 * Derived from SAASound (github.com/stripwax/SAASound, commit 3d92322030d1e5627cbf8295bed1efc872bad6eb):
 *   Copyright (c) 1998-2004, Dave Hooper <dave@beermex.com>
 *   Copyright (c) 2004-2025, Dave Hooper <dave@beermex.com> + Simon Owen <simon@simonowen.com>
 *   All rights reserved.
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * conditions of the BSD-3-Clause licence are met (full text in app/src/main/assets/NOTICE.txt).
 *
 * SAASound oversamples at a host rate; here everything runs on chip clocks. Between register writes nothing feeds
 * back into the tone generators, so each part advances independently: a tone generator's edges are counted by
 * division (sac_advance) and the LFSRs and envelopes take one step per shift or tick. sac_render walks the same clocks
 * event by event (the next tone edge or prescaler shift), applying each event clock with the per-clock rule and
 * emitting the channels whose output changed. Both end in the same state. */
#include "saa_core.h"

#include <string.h>

/* ---- SAASound's tables ---------------------------------------------------------------------------------------- */

/* CSAAEnv::cs_EnvData: phases, looping and levels[resolution: 0 = 4-bit, 1 = 3-bit][phase][position] per shape. */
static const uint8_t ENV_PHASES[8] = {1, 1, 1, 1, 2, 2, 1, 1};
static const uint8_t ENV_LOOPING[8] = {0, 1, 0, 1, 0, 1, 0, 1};
static const uint8_t ENV_LEVELS[8][2][2][16] = {
    {{{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}},
     {{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}}},
    {{{15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15}, {15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15}},
     {{14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14}, {14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14}}},
    {{{15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0}, {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}},
     {{14, 14, 12, 12, 10, 10, 8, 8, 6, 6, 4, 4, 2, 2, 0, 0}, {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}}},
    {{{15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0}, {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}},
     {{14, 14, 12, 12, 10, 10, 8, 8, 6, 6, 4, 4, 2, 2, 0, 0}, {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}}},
    {{{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}, {15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0}},
     {{0, 0, 2, 2, 4, 4, 6, 6, 8, 8, 10, 10, 12, 12, 14, 14}, {14, 14, 12, 12, 10, 10, 8, 8, 6, 6, 4, 4, 2, 2, 0, 0}}},
    {{{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}, {15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0}},
     {{0, 0, 2, 2, 4, 4, 6, 6, 8, 8, 10, 10, 12, 12, 14, 14}, {14, 14, 12, 12, 10, 10, 8, 8, 6, 6, 4, 4, 2, 2, 0, 0}}},
    {{{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}, {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}},
     {{0, 0, 2, 2, 4, 4, 6, 6, 8, 8, 10, 10, 12, 12, 14, 14}, {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}}},
    {{{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}, {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}},
     {{0, 0, 2, 2, 4, 4, 6, 6, 8, 8, 10, 10, 12, 12, 14, 14}, {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}}},
};

/* CSAAAmp::EffectiveAmplitude's pdm table (the result is multiplied by 4 there and here). */
const uint8_t SAC_PDM[8][16] = {
    {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
    {0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8},
    {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
    {0, 2, 3, 5, 6, 8, 9, 11, 12, 14, 15, 17, 18, 20, 21, 23},
    {0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30},
    {0, 3, 5, 8, 10, 13, 15, 18, 20, 23, 25, 28, 30, 33, 35, 38},
    {0, 3, 6, 9, 12, 15, 18, 21, 24, 27, 30, 33, 36, 39, 42, 45},
    {0, 4, 7, 11, 14, 18, 21, 25, 28, 32, 35, 39, 42, 46, 49, 53},
};

/* ---- envelope controller: SAASound CSAAEnv, ported verbatim ---------------------------------------------------- */

/* CSAAEnv::SetLevels */
static void env_set_levels(SacEnv* e) {
    const int r = e->resolution == 2 ? 1 : 0;
    /* every non-looping shape sustains at zero once ended */
    if (e->ended && !e->looping) e->left = 0;
    else e->left = ENV_LEVELS[e->shape][r][e->phase][e->pos];
    if (e->invert) e->right = (r ? 14 : 15) - e->left;
    else e->right = e->left;
}

/* CSAAEnv::SetNewEnvData */
static void env_set_new_data(SacEnv* e, int data) {
    e->phase = 0;
    e->pos = 0;
    e->shape = (data >> 1) & 0x07;
    e->invert = (data & 0x01) != 0;
    e->external = (data & 0x20) != 0;
    e->phases = ENV_PHASES[e->shape];
    e->looping = ENV_LOOPING[e->shape];
    e->resolution = (data & 0x10) ? 2 : 1;
    e->enabled = (data & 0x80) != 0;
    if (e->enabled) {
        e->ended = 0;
    } else {
        e->ended = 1;
        e->phase = 0;
        e->pos = 0;
    }
    env_set_levels(e);
}

/* CSAAEnv::Tick */
static void env_tick(SacEnv* e) {
    if (!e->enabled) {
        e->ended = 1;
        e->phase = 0;
        e->pos = 0;
        return;
    }
    if (e->ended) return;
    e->pos += e->resolution;
    int process = 0;
    if (e->pos >= 16) {
        e->phase++;
        if (e->phase == e->phases) {
            if (!e->looping) {
                /* position (3): the end of a one-shot shape */
                e->ended = 1;
                process = 1;
            } else {
                /* position (4): a looping shape wraps; buffered data is acted upon only here */
                e->ended = 0;
                e->phase = 0;
                e->pos -= 16;
                process = 1;
            }
        } else {
            e->ended = 0;
            e->pos -= 16;
        }
    } else {
        e->ended = 0;
    }
    if (e->new_data && process) {
        e->new_data = 0;
        env_set_new_data(e, e->next_data);
    } else {
        env_set_levels(e);
    }
}

/* CSAAEnv::SetEnvControl */
static void env_control(SacEnv* e, int data) {
    const int enabled = (data & 0x80) != 0;
    if (!enabled && !e->enabled) return;
    e->enabled = enabled;
    if (!enabled) {
        /* disabled: any later control write applies at once */
        e->ended = 1;
        return;
    }
    /* the resolution applies at once (test case envext_34b): 4 -> 3 bits clears the position's LSB, 3 -> 4 sets it */
    const int resolution = (data & 0x10) ? 2 : 1;
    if (e->resolution == 1 && resolution == 2) e->pos &= 0x0E;
    else if (e->resolution == 2 && resolution == 1) e->pos |= 0x01;
    e->resolution = resolution;
    if (e->ended) {
        env_set_new_data(e, data);
        e->new_data = 0;
    } else {
        env_set_levels(e);
        e->new_data = 1;
        e->next_data = data;
    }
}

/* CSAAEnv::InternalClock / ExternalClock */
static void env_internal_clock(SacEnv* e) {
    if (e->enabled && !e->external) env_tick(e);
}

static void env_external_clock(SacEnv* e) {
    if (e->external && e->enabled) env_tick(e);
}

/* ---- tone and noise generators -------------------------------------------------------------------------------- */

/* Pending offset/octave data latched (at an edge, at once while sync is set, at the sync's rising edge). */
static void tone_latch(SacTone* t) {
    t->oct = t->next_oct;
    t->off = t->next_off;
    t->pending = 0;
    t->half = sac_half(t->oct, t->off);
}

/* SAASound CSAANoise::ChangeLevel: Jepael's 18-bit Galois LFSR. */
static void lfsr_shift(SacNoise* n) { n->lfsr = (n->lfsr & 1) ? (n->lfsr >> 1) ^ SAC_LFSR_TAPS : n->lfsr >> 1; }

/* The side effects of an edge of tone generator i other than its own level: noise 0 / 1 in mode 3 on tones 0 / 3,
 * envelope 0 / 1 (internal clock) on tones 1 / 4. */
static void edge_effects(SacCore* c, int i) {
    if ((i == 0 || i == 3) && c->noise[i / 3].mode == 3) lfsr_shift(&c->noise[i / 3]);
    if (i == 1 || i == 4) env_internal_clock(&c->env[i / 3]);
}

/* One edge of tone generator i (its count has reached 0). */
static void tone_edge(SacCore* c, int i) {
    SacTone* t = &c->tone[i];
    t->level ^= 1;
    edge_effects(c, i);
    if (t->pending) tone_latch(t);
    t->count = t->half;
}

/* One chip clock, as the specification's "One clock" (sync is clear). */
static void one_clock(SacCore* c) {
    c->prescale = (c->prescale + 1) & (SAC_PRESCALE - 1);
    for (int i = 0; i < SAC_CHANNELS; ++i)
        if (--c->tone[i].count == 0) tone_edge(c, i);
    for (int g = 0; g < 2; ++g) {
        const int mode = c->noise[g].mode;
        if (mode < 3 && (c->prescale & ((256 << mode) - 1)) == 0) lfsr_shift(&c->noise[g]);
    }
}

/* ---- registers ------------------------------------------------------------------------------------------------ */

void sac_reset(SacCore* core) {
    memset(core, 0, sizeof *core);
    for (int i = 0; i < SAC_CHANNELS; ++i) {
        SacTone* t = &core->tone[i];
        t->level = 1;
        t->half = sac_half(0, 0);
        t->count = t->half;
    }
    for (int g = 0; g < 2; ++g) {
        core->noise[g].lfsr = SAC_LFSR_SEED;
        SacEnv* e = &core->env[g];
        e->ended = 1;
        e->resolution = 1;
        e->phases = ENV_PHASES[0];
        e->looping = ENV_LOOPING[0];
    }
}

static void set_offset(SacCore* c, int ch, int val) {
    SacTone* t = &c->tone[ch];
    t->next_off = val;
    if (c->sync) tone_latch(t);
    else t->pending = 1;
}

static void set_octave(SacCore* c, int ch, int val) {
    SacTone* t = &c->tone[ch];
    t->next_oct = val & 7;
    if (c->sync) tone_latch(t);
    else t->pending = 1;
}

static void set_control(SacCore* c, int val) {
    const int sync = (val >> 1) & 1;
    c->enable = val & 1;
    if (sync && !c->sync) {
        /* sync & reset: every generator held at its reset phase, pending data applied */
        for (int i = 0; i < SAC_CHANNELS; ++i) {
            c->tone[i].level = 1;
            tone_latch(&c->tone[i]);
        }
        c->prescale = 0;
    } else if (!sync && c->sync) {
        /* released: the six tone generators restart together */
        for (int i = 0; i < SAC_CHANNELS; ++i) c->tone[i].count = c->tone[i].half;
    }
    c->sync = sync;
}

void sac_write(SacCore* core, int reg, int val) {
    reg &= 0x1F;
    val &= 0xFF;
    /* the address write: an external envelope clock (per address, as SAASound) */
    if (reg == SAC_REG_ENV0) env_external_clock(&core->env[0]);
    if (reg == SAC_REG_ENV1) env_external_clock(&core->env[1]);
    if (reg < SAC_CHANNELS) {
        core->ch[reg].amp_l = val & 0x0F;
        core->ch[reg].amp_r = val >> 4;
    } else if (reg >= SAC_REG_OFFSET && reg < SAC_REG_OFFSET + SAC_CHANNELS) {
        set_offset(core, reg - SAC_REG_OFFSET, val);
    } else if (reg >= SAC_REG_OCTAVE && reg < SAC_REG_OCTAVE + 3) {
        const int k = reg - SAC_REG_OCTAVE;
        set_octave(core, 2 * k, val);
        set_octave(core, 2 * k + 1, val >> 4);
    } else {
        switch (reg) {
        case SAC_REG_TONE:
            for (int c = 0; c < SAC_CHANNELS; ++c) core->ch[c].tone = (val >> c) & 1;
            break;
        case SAC_REG_NOISE:
            for (int c = 0; c < SAC_CHANNELS; ++c) core->ch[c].noise = (val >> c) & 1;
            break;
        case SAC_REG_NOISE_MODE:
            core->noise[0].mode = val & 3;
            core->noise[1].mode = (val >> 4) & 3;
            break;
        case SAC_REG_ENV0:
            env_control(&core->env[0], val);
            break;
        case SAC_REG_ENV1:
            env_control(&core->env[1], val);
            break;
        case SAC_REG_CONTROL:
            set_control(core, val);
            break;
        default:
            break; /* 0x06, 0x07, 0x0E, 0x0F, 0x13, 0x17, 0x1A, 0x1B, 0x1D-0x1F: nothing */
        }
    }
}

/* ---- output --------------------------------------------------------------------------------------------------- */

void sac_output(const SacCore* core, int channel, int* left, int* right) {
    if (core->sync || !core->enable) {
        *left = *right = 0;
        return;
    }
    const SacChannel* ch = &core->ch[channel];
    const int t = core->tone[channel].level, n = (int)(core->noise[channel / 3].lfsr & 1);
    int x; /* mixer level in half-steps, 0..2 (SAASound CSAAAmp::Tick) */
    if (ch->tone && ch->noise) x = t * (2 - n);
    else if (ch->tone) x = 2 * t;
    else if (ch->noise) x = 2 * n;
    else x = 0;
    const SacEnv* e = &core->env[channel / 3];
    if ((channel == 2 || channel == 5) && e->enabled) {
        *left = SAC_PDM[ch->amp_l >> 1][e->left] * 4 * (2 - x);
        *right = SAC_PDM[ch->amp_r >> 1][e->right] * 4 * (2 - x);
    } else {
        *left = ch->amp_l * x * 16;
        *right = ch->amp_r * x * 16;
    }
}

/* Stores every channel's output; emits the ones that changed (clock offset `at`). */
static void update_outputs(SacCore* c, uint32_t at, SacEmit emit, void* ctx) {
    for (int ch = 0; ch < SAC_CHANNELS; ++ch) {
        int l, r;
        sac_output(c, ch, &l, &r);
        if (l == c->out_l[ch] && r == c->out_r[ch]) continue;
        c->out_l[ch] = l;
        c->out_r[ch] = r;
        if (emit) emit(ctx, at, ch, l, r);
    }
}

/* ---- advance and render --------------------------------------------------------------------------------------- */

/* n clocks of tone generator t: returns its edges, its level, count and latched data as after them. Only the first
 * edge can latch pending data; after it the half-period is constant. */
static uint32_t tone_advance(SacTone* t, uint32_t n) {
    if (n < (uint32_t)t->count) {
        t->count -= (int32_t)n;
        return 0;
    }
    const uint32_t rest = n - (uint32_t)t->count;
    t->level ^= 1;
    if (t->pending) tone_latch(t);
    const uint32_t h = (uint32_t)t->half, k = rest / h;
    t->level ^= (int32_t)(k & 1);
    t->count = (int32_t)(h - (rest - k * h));
    return 1 + k;
}

void sac_advance(SacCore* core, uint32_t clocks) {
    if (!core->sync && clocks) {
        for (int g = 0; g < 2; ++g) {
            SacNoise* n = &core->noise[g];
            if (n->mode == 3) continue;
            const uint32_t period = 256u << n->mode;
            const uint64_t shifts = ((uint64_t)((uint32_t)core->prescale & (period - 1)) + clocks) / period;
            for (uint64_t s = 0; s < shifts; ++s) lfsr_shift(n);
        }
        core->prescale = (int32_t)(((uint32_t)core->prescale + clocks) & (SAC_PRESCALE - 1));
        for (int i = 0; i < SAC_CHANNELS; ++i) {
            const uint32_t edges = tone_advance(&core->tone[i], clocks);
            if (!edges) continue;
            if ((i == 0 || i == 3) && core->noise[i / 3].mode == 3) {
                for (uint32_t s = 0; s < edges; ++s) lfsr_shift(&core->noise[i / 3]);
            } else if (i == 1 || i == 4) {
                SacEnv* e = &core->env[i / 3];
                /* each edge an internal clock; a buffered load may switch the envelope to external, an ended one
                 * stays as it is */
                for (uint32_t s = 0; s < edges && e->enabled && !e->external && !e->ended; ++s) env_tick(e);
            }
        }
    }
    update_outputs(core, 0, NULL, NULL);
}

/* Clocks to the next event (1-based): the nearest tone edge or prescaler shift. */
static uint32_t next_event(const SacCore* c) {
    uint32_t d = (uint32_t)c->tone[0].count;
    for (int i = 1; i < SAC_CHANNELS; ++i)
        if ((uint32_t)c->tone[i].count < d) d = (uint32_t)c->tone[i].count;
    for (int g = 0; g < 2; ++g) {
        if (c->noise[g].mode == 3) continue;
        const uint32_t period = 256u << c->noise[g].mode;
        const uint32_t to = period - ((uint32_t)c->prescale & (period - 1));
        if (to < d) d = to;
    }
    return d;
}

/* n clocks without any event (n is below every distance next_event considers). */
static void skip(SacCore* c, uint32_t n) {
    c->prescale = (int32_t)(((uint32_t)c->prescale + n) & (SAC_PRESCALE - 1));
    for (int i = 0; i < SAC_CHANNELS; ++i) c->tone[i].count -= (int32_t)n;
}

void sac_render(SacCore* core, uint32_t clocks, SacEmit emit, void* ctx) {
    update_outputs(core, 0, emit, ctx); /* what writes changed since the last render */
    if (core->sync) return;             /* held: nothing moves, every output is 0 */
    uint32_t done = 0;
    while (done < clocks) {
        const uint32_t d = next_event(core), left = clocks - done;
        if (d > left) {
            skip(core, left);
            break;
        }
        skip(core, d - 1);
        one_clock(core);
        done += d;
        update_outputs(core, done - 1, emit, ctx);
    }
}
