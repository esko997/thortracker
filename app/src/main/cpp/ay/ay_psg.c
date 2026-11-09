/* AY-3-8910/8912 and YM2149 core (ay_psg.h; behaviour in docs/AUDIO_ENGINE.md "AY-3-8910 / YM2149: chip core").
 *
 * The DAC tables below are data from ayumi (https://github.com/true-grue/ayumi, commit 07c08b4874c359169e4a028edf73f046d8b763e2,
 * ayumi.c: AY_dac_table, whose 32 entries are the AY's 16 levels each listed twice, and YM_dac_table), under this
 * licence:
 *
 *   The MIT License
 *
 *   Copyright (c) Peter Sovietov, http://sovietov.com
 *
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *
 *   The above copyright notice and this permission notice shall be included in
 *   all copies or substantial portions of the Software.
 *
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *   THE SOFTWARE.
 *
 * Everything else here was written for Thor Tracker. */
#include "ay_psg.h"

#include <string.h>

const double AY_PSG_AY_DAC[16] = {
    0.0,            0.00999465934234, 0.0144502937362, 0.0210574502174, 0.0307011520562, 0.0455481803616,
    0.0644998855573, 0.107362478065,  0.126588845655,  0.20498970016,   0.292210269322,  0.372838941024,
    0.492530708782, 0.635324635691,   0.805584802014,  1.0,
};

const double AY_PSG_YM_DAC[32] = {
    0.0,             0.0,             0.00465400167849, 0.00772106507973, 0.0109559777218, 0.0139620050355,
    0.0169985503929, 0.0200198367285, 0.024368657969,   0.029694056611,   0.0350652323186, 0.0403906309606,
    0.0485389486534, 0.0583352407111, 0.0680552376593,  0.0777752346075,  0.0925154497597, 0.111085679408,
    0.129747463188,  0.148485542077,  0.17666895552,    0.211551079576,   0.246387426566,  0.281101701381,
    0.333730067903,  0.400427252613,  0.467383840696,   0.53443198291,    0.635172045472,  0.75800717174,
    0.879926756695,  1.0,
};

int ay_psg_amplitude(int ym, int level) {
    const double v = ym ? AY_PSG_YM_DAC[level & 31] : AY_PSG_AY_DAC[level & 15];
    return (int)(v * AY_AMP + 0.5);
}

/* ---- registers ------------------------------------------------------------------------------- */

static void derive(AyPsg* c, int reg) {
    if (reg < 6) {
        const int k = reg >> 1;
        const uint32_t p = c->reg[2 * k] | (uint32_t)(c->reg[2 * k + 1] & 15) << 8;
        c->tone_period[k] = p ? p : 1;
    } else if (reg == 6) {
        const uint32_t p = c->reg[6] & 31;
        c->noise_period = 2 * (p ? p : 1);
    } else if (reg == 11 || reg == 12) {
        const uint32_t p = c->reg[11] | (uint32_t)c->reg[12] << 8;
        c->env_period = p ? p * (c->ym ? 1u : 2u) : 1u;
    }
}

/* R13: the shape (continue = 0 behaves as hold with alternate = attack) and a restart at the ramp's start. */
static void env_restart(AyPsg* c) {
    const int shape = c->reg[13] & 15, attack = (shape & 4) != 0;
    if (shape & 8) {
        c->env_hold = (uint8_t)(shape & 1);
        c->env_alt = (uint8_t)((shape >> 1) & 1);
    } else {
        c->env_hold = 1;
        c->env_alt = (uint8_t)attack;
    }
    c->env_att = attack ? c->env_mask : 0;
    c->env_cnt = c->env_mask;
    c->env_holding = 0;
    c->env_count = 0;
}

void ay_psg_reset(AyPsg* c, int ym, int stereo) {
    memset(c, 0, sizeof *c);
    c->ym = ym ? 1 : 0;
    c->stereo = stereo ? 1 : 0;
    c->env_mask = ym ? 31 : 15;
    for (int l = 0; l <= c->env_mask; ++l) c->amp[l] = ay_psg_amplitude(c->ym, l);
    for (int r = 0; r < 13; ++r) derive(c, r);
    c->lfsr = 1;
    /* holding at level 0 until the first R13 write */
    c->env_cnt = 0;
    c->env_att = 0;
    c->env_holding = 1;
    c->last = c->last_r = 0;
}

void ay_psg_write(AyPsg* c, int reg, int value) {
    if (reg < 0 || reg > 13) return;
    c->reg[reg] = (uint8_t)value;
    if (reg == 13) env_restart(c);
    else derive(c, reg);
}

void ay_psg_set_mute(AyPsg* c, int mask) { c->mute = (uint8_t)(mask & 7); }

/* ---- output ---------------------------------------------------------------------------------- */

int ay_psg_env_level(const AyPsg* c) { return c->env_cnt ^ c->env_att; }

int ay_psg_channel_level(const AyPsg* c, int k) {
    const int r7 = c->reg[7];
    const int tone = c->tone_out[k] | (r7 >> k & 1);
    const int noise = (int)(c->lfsr & 1) | (r7 >> (k + 3) & 1);
    if (!(tone & noise)) return 0;
    const int amp = c->reg[8 + k];
    if (amp & 0x10) return ay_psg_env_level(c);
    const int v = amp & 15;
    return c->ym ? (v ? 2 * v + 1 : 0) : v;
}

/* Channels in a side's sum (bit c = channel c): mono A + B + C on both; the CPC's socket A + B left, B + C right. */
static int side_channels(const AyPsg* c, int side) { return c->stereo ? (side ? 6 : 3) : 7; }

int ay_psg_mix(const AyPsg* c, int side) {
    const int use = side_channels(c, side) & ~c->mute;
    int sum = 0;
    for (int k = 0; k < 3; ++k)
        if (use >> k & 1) sum += c->amp[ay_psg_channel_level(c, k)];
    return sum;
}

static void update_last(AyPsg* c) {
    c->last = ay_psg_mix(c, 0);
    c->last_r = c->stereo ? ay_psg_mix(c, 1) : c->last;
}

/* ---- one step -------------------------------------------------------------------------------- */

static uint32_t lfsr_step(uint32_t x) { return x >> 1 | ((x ^ x >> 3) & 1) << 16; }

/* One envelope step: down the ramp; at its end hold (alternate flipping once) or start the next ramp. */
static void env_step(AyPsg* c) {
    if (--c->env_cnt >= 0) return;
    if (c->env_alt) c->env_att ^= c->env_mask;
    if (c->env_hold) {
        c->env_holding = 1;
        c->env_cnt = 0;
    } else {
        c->env_cnt = c->env_mask;
    }
}

/* One step of every generator; 1 if any of them changed its output. */
static int step(AyPsg* c) {
    int changed = 0;
    for (int k = 0; k < 3; ++k) {
        if (++c->tone_count[k] >= c->tone_period[k]) {
            c->tone_count[k] = 0;
            c->tone_out[k] ^= 1;
            changed = 1;
        }
    }
    if (++c->noise_count >= c->noise_period) {
        c->noise_count = 0;
        c->lfsr = lfsr_step(c->lfsr);
        changed = 1;
    }
    if (!c->env_holding && ++c->env_count >= c->env_period) {
        c->env_count = 0;
        env_step(c);
        changed = 1;
    }
    return changed;
}

void ay_psg_render_mix(AyPsg* c, uint32_t steps, int32_t* left, int32_t* right) {
    for (uint32_t i = 0; i < steps; ++i) {
        step(c);
        update_last(c);
        if (left) left[i] = c->last;
        if (right) right[i] = c->last_r;
    }
}

/* ---- silent advance -------------------------------------------------------------------------- */

/* Counter events in n steps of a counter that fires when count + 1 >= period (then 0): the first after
 * max(1, period - count) steps, then every period. Updates the counter; returns the event count. */
static uint32_t events(uint32_t* count, uint32_t period, uint32_t n) {
    const uint32_t first = *count >= period ? 1 : period - *count;
    if (n < first) {
        *count += n;
        return 0;
    }
    const uint32_t rest = n - first;
    *count = rest % period;
    return 1 + rest / period;
}

/* new bit 16 = bit 0 ^ bit 3, shift right. Within 14 steps every feedback reads original bits only (a new bit reaches
 * bit 3 after 14 shifts), so k <= 14 steps are x >> k with the k feedback bits (x ^ x >> 3) & (2^k - 1) on top. */
uint32_t ay_psg_lfsr_advance(uint32_t x, uint32_t n) {
    while (n) {
        const uint32_t k = n < 14 ? n : 14;
        const uint32_t f = (x ^ x >> 3) & ((1u << k) - 1);
        x = x >> k | f << (17 - k);
        n -= k;
    }
    return x;
}

/* k envelope steps at once: ramps walked whole, periodic shapes reduced modulo their cycle, held shapes saturated. */
static void env_advance(AyPsg* c, uint32_t k) {
    while (k && !c->env_holding) {
        if (k <= (uint32_t)c->env_cnt) {
            c->env_cnt -= (int)k;
            return;
        }
        k -= (uint32_t)c->env_cnt + 1; /* to the ramp's end */
        if (c->env_alt) c->env_att ^= c->env_mask;
        if (c->env_hold) {
            c->env_holding = 1;
            c->env_cnt = 0;
            return;
        }
        c->env_cnt = c->env_mask;
        /* at a ramp start of a periodic shape: whole cycles (one ramp, or two when alternating) change nothing */
        k %= (uint32_t)(c->env_mask + 1) * (c->env_alt ? 2u : 1u);
    }
}

static void advance(AyPsg* c, uint32_t n) {
    for (int k = 0; k < 3; ++k)
        if (events(&c->tone_count[k], c->tone_period[k], n) & 1) c->tone_out[k] ^= 1;
    c->lfsr = ay_psg_lfsr_advance(c->lfsr, events(&c->noise_count, c->noise_period, n));
    if (!c->env_holding) {
        env_advance(c, events(&c->env_count, c->env_period, n));
        if (c->env_holding) c->env_count = 0; /* the step that started holding reset it; it stays frozen */
    }
    update_last(c);
}

/* ---- run ------------------------------------------------------------------------------------- */

void ay_psg_run(AyPsg* c, uint32_t steps, blip_t* left, blip_t* right) {
    if (!left) {
        advance(c, steps);
        return;
    }
    const int two = c->stereo && right;
    int last = c->last, last_r = c->last_r;
    for (uint32_t i = 0; i < steps; ++i) {
        /* the first step also picks up register writes and mute changes made since the last one */
        if (step(c) || i == 0) {
            const int mix = ay_psg_mix(c, 0);
            if (mix != last) {
                blip_add_delta(left, i, mix - last);
                last = mix;
            }
            if (two) {
                const int mix_r = ay_psg_mix(c, 1);
                if (mix_r != last_r) {
                    blip_add_delta(right, i, mix_r - last_r);
                    last_r = mix_r;
                }
            }
        }
    }
    c->last = last;
    c->last_r = c->stereo ? (two ? last_r : ay_psg_mix(c, 1)) : last;
}

int ay_psg_same(const AyPsg* a, const AyPsg* b) {
    if (a->ym != b->ym || a->stereo != b->stereo || a->mute != b->mute || memcmp(a->reg, b->reg, sizeof a->reg) ||
        a->noise_period != b->noise_period || a->env_period != b->env_period || a->env_hold != b->env_hold ||
        a->env_alt != b->env_alt || a->noise_count != b->noise_count || a->lfsr != b->lfsr ||
        a->env_count != b->env_count || a->env_cnt != b->env_cnt || a->env_att != b->env_att ||
        a->env_mask != b->env_mask || a->env_holding != b->env_holding || a->last != b->last || a->last_r != b->last_r)
        return 0;
    for (int k = 0; k < 3; ++k)
        if (a->tone_period[k] != b->tone_period[k] || a->tone_count[k] != b->tone_count[k] || a->tone_out[k] != b->tone_out[k])
            return 0;
    return 1;
}
