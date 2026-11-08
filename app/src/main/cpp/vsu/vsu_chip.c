/* Thor Tracker - the Virtual Boy VSU core (vsu_chip.h).
 * Copyright (C) 2026 Thor Tracker contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version. This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public
 * License for more details.
 *
 * Derived from Mednafen 1.32.1 src/vb/vsu.cpp, Copyright (C) 2010-2016 Mednafen Team, GPL-2.0-or-later
 * (mednafen-1.32.1.tar.xz): its register map, per-channel clock dividers (effects 4800 clocks, length 4 effects
 * ticks, envelope 4 length units, sweep/modulation 1 or 8 effects ticks), wave and noise stepping (noise every
 * 10 (2048 - F) clocks, the 15-bit register with taps 14, 10, 13, 4, 8, 6, 9, 11), the SxINT restarts and the
 * per-channel amplitude. C11 port: Blip_Buffer and the output latchers removed (the machine samples the mixer),
 * the clock-by-chunk loop replaced by whole events (wave steps counted by division).
 *
 * Amended from the Virtual Boy Sacred Tech Scroll by Guy Perfect (VSU chapter, revision 2024-10-20), each a
 * deliberate choice (docs/AUDIO_ENGINE.md, "Virtual Boy VSU: Sources and the core"):
 *  1. Wave RAM writes are ignored while any channel is active; modulation RAM writes while channel 5 is active.
 *  2. The envelope's repeat reloads the last written Value after one frame at the limit (Mednafen wraps 0 <-> 15).
 *  3. S5INT does not reset the current frequency (Mednafen copies the written frequency); writing SxFQL/SxFQH
 *     sets both the written and the current value.
 *  4. The noise shift register resets to 0 (Mednafen: 1) on S6INT and S6EV1 writes.
 *  5. Sweep overflow: with the sweep function selected (S5EV1 bit 4 = 0) the next value cur +- (cur >> shift) is
 *     computed at every effects tick; above 2047 channel 5 stops at once, also when sweeping is disabled (the
 *     Tech Scroll's hardware bug, as libvgm); a running sweep therefore stops before playing a value > 2047.
 *  6. Modulation: at each frame end the current frequency = (last written frequency + signed table value) & 0x7FF;
 *     after the 32nd value it wraps when repeat is set, else stays at the last value.
 *  7. A sweep/modulation interval written mid-frame takes effect at once unless the frame has already run as long
 *     as the new interval, in which case that frame ends at the old interval.
 *  8. Output is the digital mixer exactly: per channel amplitude = (stereo level x envelope) >> 3, + 1 when the
 *     product is not 0; channel value = 6-bit sample x amplitude (0 for an inactive channel or a wave index > 4);
 *     the sum of the six >> 4 is the 10-bit output (<= 685). (Sampling every 120 clocks and the RC high-pass are
 *     the machine's: vsu_machine.c.)
 * Also from the Tech Scroll: the envelope frame counts while the envelope is disabled (Mednafen counts only while
 * enabled), the sweep/modulation frame counts while modification is disabled (with a non-zero interval), channel 6
 * has no SxRAM, and the unmapped 0x300-0x3FF range is no mirror of modulation RAM. Not modelled: the "5-10 ms of
 * zero samples" after enabling a channel with the envelope on; libvgm's modulation lock on S5FQ writes. */
#include "vsu_chip.h"

#include <string.h>

/* The noise feedback bit (Tech Scroll: tap 0..7 = bit 14, 10, 13, 4, 8, 6, 9, 11). */
static const uint8_t TAP_BIT[8] = {14, 10, 13, 4, 8, 6, 9, 11};

static int32_t period(int c, int cur) { return c == 5 ? 10 * (2048 - cur) : 2048 - cur; }

/* Channel 5's frequency-modification frame in effects ticks: interval x (1 or 8). */
static uint16_t frame_len(uint8_t swp) { return (uint16_t)(((swp >> 4) & 7) * ((swp & 0x80) ? 8 : 1)); }

static int sweep_next(int cur, uint8_t swp) {
    const int d = cur >> (swp & 7);
    return (swp & 8) ? cur + d : cur - d;
}

void vsu_reset(VsuChip* chip) { memset(chip, 0, sizeof *chip); }

static void noise_step(VsuChip* chip) {
    const unsigned r = chip->lfsr, tap = TAP_BIT[(chip->ch[5].ev1 >> 4) & 7];
    const unsigned bit = ((r >> 7) ^ (r >> tap) ^ 1u) & 1u;
    chip->lfsr = (uint16_t)(((r << 1) & 0x7FFFu) | bit);
}

/* d clocks of channel c without an effects tick inside them (one may end at the last clock). */
static void advance(VsuChip* chip, int c, int32_t d) {
    VsuChannel* ch = &chip->ch[c];
    if (d < ch->step_count) {
        ch->step_count -= d;
        return;
    }
    d -= ch->step_count; /* the first step is at that clock; the current frequency holds until the span's end */
    const int32_t p = period(c, ch->cur);
    const int32_t more = d / p;
    ch->step_count = p - d % p;
    if (c == 5) {
        for (int32_t k = 0; k <= more; ++k) noise_step(chip);
    } else {
        ch->wave_pos = (uint8_t)((ch->wave_pos + 1 + more) & 31);
    }
}

static void envelope_step(VsuChannel* ch) {
    const int grow = ch->ev0 & 8;
    if (!grow && ch->env > 0) --ch->env;
    else if (grow && ch->env < 15) ++ch->env;
    else if (ch->ev1 & 2) ch->env = ch->ev0 >> 4; /* one frame at the limit, then the written value */
}

static void effects_tick(VsuChip* chip, int c) {
    VsuChannel* ch = &chip->ch[c];
    if (--ch->len_div == 0) {
        ch->len_div = 4;
        if ((ch->intv & 0x20) && --ch->length == 0) {
            ch->active = 0;
            return;
        }
        if (--ch->env_div == 0) {
            ch->env_div = 4;
            if (--ch->env_count == 0) {
                ch->env_count = (uint8_t)((ch->ev0 & 7) + 1);
                if (ch->ev1 & 1) envelope_step(ch);
            }
        }
    }
    if (c != 4) return;
    const int sweep = !(ch->ev1 & 0x10);
    if (sweep && sweep_next(ch->cur, chip->swp) > 2047) {
        ch->active = 0;
        return;
    }
    if (!chip->mod_len) return;
    if (++chip->mod_ticks < chip->mod_len) return;
    chip->mod_ticks = 0;
    if ((ch->ev1 & 0x40) && (chip->swp & 0x70)) {
        if (sweep) {
            const int n = sweep_next(ch->cur, chip->swp);
            ch->cur = (uint16_t)(n < 0 ? 0 : n);
        } else {
            if (chip->mod_pos >= VSU_MOD_VALUES && (ch->ev1 & 0x20)) chip->mod_pos = 0;
            if (chip->mod_pos < VSU_MOD_VALUES) {
                ch->cur = (uint16_t)((unsigned)(ch->freq + (int8_t)chip->mod[chip->mod_pos]) & 0x7FFu);
                ++chip->mod_pos;
            }
        }
    }
    chip->mod_len = frame_len(chip->swp);
    /* The next frame begins: its value is computed now, so an overflow stops the channel before it plays (5). */
    if (sweep && sweep_next(ch->cur, chip->swp) > 2047) ch->active = 0;
}

void vsu_run(VsuChip* chip, uint32_t clocks) {
    chip->clocks += clocks;
    for (int c = 0; c < VSU_CHANNELS; ++c) {
        VsuChannel* ch = &chip->ch[c];
        uint32_t left = clocks;
        while (left && ch->active) {
            const uint32_t d = left < (uint32_t)ch->fx_count ? left : (uint32_t)ch->fx_count;
            advance(chip, c, (int32_t)d);
            ch->fx_count -= (int32_t)d;
            left -= d;
            if (!ch->fx_count) {
                ch->fx_count = (int32_t)VSU_EFFECT_CLOCKS;
                effects_tick(chip, c);
            }
        }
    }
}

static void start_channel(VsuChip* chip, int c) {
    VsuChannel* ch = &chip->ch[c];
    ch->active = 1;
    ch->wave_pos = 0;
    ch->step_count = period(c, ch->cur);
    ch->fx_count = (int32_t)VSU_EFFECT_CLOCKS;
    ch->len_div = 4;
    ch->env_div = 4;
    ch->length = (uint8_t)((ch->intv & 0x1F) + 1);
    ch->env_count = (uint8_t)((ch->ev0 & 7) + 1);
    if (c == 4) {
        chip->mod_pos = 0;
        chip->mod_ticks = 0;
        chip->mod_len = frame_len(chip->swp);
        /* S5INT starts a frame: an overflowing next sweep value stops the channel at once (amendment 5). */
        if (!(ch->ev1 & 0x10) && sweep_next(ch->cur, chip->swp) > 2047) ch->active = 0;
    }
    if (c == 5) chip->lfsr = 0;
}

void vsu_write(VsuChip* chip, uint16_t reg, uint8_t val) {
    if (reg < 0xA0) {
        if (!vsu_active_mask(chip)) chip->wave[reg >> 5][reg & 31] = val & 0x3F;
        return;
    }
    if (reg < 0xC0) {
        if (!chip->ch[4].active) chip->mod[reg - 0xA0] = val;
        return;
    }
    if (reg == VSU_SSTOP) {
        if (val & 1)
            for (int c = 0; c < VSU_CHANNELS; ++c) chip->ch[c].active = 0;
        return;
    }
    if (reg < 0x100 || reg > 0x15F) return;
    const int c = (reg - 0x100) >> 4;
    VsuChannel* ch = &chip->ch[c];
    switch (reg & 15) {
    case VSU_INT:
        ch->intv = val & 0xBF;
        if (val & 0x80) start_channel(chip, c);
        else ch->active = 0;
        break;
    case VSU_LRV: ch->lrv = val; break;
    case VSU_FQL:
        ch->freq = (uint16_t)((ch->freq & 0x700) | val);
        ch->cur = (uint16_t)((ch->cur & 0x700) | val);
        break;
    case VSU_FQH:
        ch->freq = (uint16_t)((ch->freq & 0xFF) | (val & 7) << 8);
        ch->cur = (uint16_t)((ch->cur & 0xFF) | (val & 7) << 8);
        break;
    case VSU_EV0:
        ch->ev0 = val;
        ch->env = val >> 4;
        break;
    case VSU_EV1:
        ch->ev1 = val & (c >= 4 ? 0x73 : 0x03);
        if (c == 5) chip->lfsr = 0;
        break;
    case VSU_RAM:
        if (c < 5) ch->ram = val & 0x0F;
        break;
    case VSU_SWP:
        if (c == 4) {
            chip->swp = val;
            const uint16_t len = frame_len(val);
            if (len > chip->mod_ticks) chip->mod_len = len; /* else the running frame ends at its old length */
        }
        break;
    default: break;
    }
}

void vsu_sample(const VsuChip* chip, unsigned mute_mask, int* left, int* right) {
    int l = 0, r = 0;
    for (int c = 0; c < VSU_CHANNELS; ++c) {
        const VsuChannel* ch = &chip->ch[c];
        if (!ch->active || (mute_mask >> c & 1u)) continue;
        int s;
        if (c == 5) s = (chip->lfsr & 1) ? 63 : 0;
        else s = ch->ram > 4 ? 0 : chip->wave[ch->ram][ch->wave_pos];
        int al = (ch->lrv >> 4) * ch->env, ar = (ch->lrv & 15) * ch->env;
        if (al) al = (al >> 3) + 1;
        if (ar) ar = (ar >> 3) + 1;
        l += s * al;
        r += s * ar;
    }
    *left = l >> 4;
    *right = r >> 4;
}

void vsu_poke_wave(VsuChip* chip, int wave, int index, uint8_t value) {
    if (wave >= 0 && wave < VSU_WAVES && index >= 0 && index < VSU_WAVE_SAMPLES) chip->wave[wave][index] = value & 0x3F;
}

void vsu_poke_mod(VsuChip* chip, int index, uint8_t value) {
    if (index >= 0 && index < VSU_MOD_VALUES) chip->mod[index] = value;
}

uint8_t vsu_register(const VsuChip* chip, uint16_t reg) {
    if (reg < 0xA0) return chip->wave[reg >> 5][reg & 31];
    if (reg < 0xC0) return chip->mod[reg - 0xA0];
    if (reg < 0x100 || reg > 0x15F) return 0;
    const int c = (reg - 0x100) >> 4;
    const VsuChannel* ch = &chip->ch[c];
    switch (reg & 15) {
    case VSU_INT: return ch->intv;
    case VSU_LRV: return ch->lrv;
    case VSU_FQL: return (uint8_t)(ch->freq & 0xFF);
    case VSU_FQH: return (uint8_t)(ch->freq >> 8);
    case VSU_EV0: return ch->ev0;
    case VSU_EV1: return ch->ev1;
    case VSU_RAM: return ch->ram;
    case VSU_SWP: return c == 4 ? chip->swp : 0;
    default: return 0;
    }
}

static int channel_held_equal(const VsuChannel* a, const VsuChannel* b) {
    return a->intv == b->intv && a->lrv == b->lrv && a->ev0 == b->ev0 && a->ev1 == b->ev1 && a->ram == b->ram &&
           a->freq == b->freq && a->active == b->active && a->env == b->env && a->env_count == b->env_count &&
           a->length == b->length && a->cur == b->cur;
}

int vsu_held_equal(const VsuChip* a, const VsuChip* b) {
    for (int c = 0; c < VSU_CHANNELS; ++c)
        if (!channel_held_equal(&a->ch[c], &b->ch[c])) return 0;
    return !memcmp(a->wave, b->wave, sizeof a->wave) && !memcmp(a->mod, b->mod, sizeof a->mod) && a->swp == b->swp &&
           a->mod_pos == b->mod_pos && a->mod_ticks == b->mod_ticks && a->mod_len == b->mod_len;
}

int vsu_equal(const VsuChip* a, const VsuChip* b) {
    if (!vsu_held_equal(a, b) || a->lfsr != b->lfsr || a->clocks != b->clocks) return 0;
    for (int c = 0; c < VSU_CHANNELS; ++c) {
        const VsuChannel *x = &a->ch[c], *y = &b->ch[c];
        if (x->wave_pos != y->wave_pos || x->len_div != y->len_div || x->env_div != y->env_div ||
            x->step_count != y->step_count || x->fx_count != y->fx_count)
            return 0;
    }
    return 1;
}
