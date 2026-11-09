/* Famicom Disk System audio (see nes_fds.h for scope and sources). Event-driven like nes_n163.c: run() jumps to the
 * next event (a unit tick every NES_FDS_UNIT_CYCLES CPU cycles, an envelope step), computes it exactly and, with
 * output attached, steps the low-pass filter once per unit tick and sends its level whenever the amplitude changes. */
#include "nes_fds.h"

#include <math.h>
#include <string.h>

#include "nes_apu.h"

#define WAVE_HALT 0x80 /* $4083 bit 7 */
#define ENV_OFF 0x40   /* $4083 bit 6 */
#define MOD_HALT 0x80  /* $4087 bit 7 */
#define MOD_CARRY 0x40 /* $4087 bit 6 */
#define WRITE_ENABLE 0x80
#define ENV_DISABLED 0x80 /* $4080 / $4084 bit 7 */
#define ENV_UP 0x40

/* FamiStudio 4.5.3 Nes_Fds.cpp `dac_lookup` (plgDavid's measurements, cleaned up by Persune): the DAC output of each
 * 6-bit sample, in units of 1/32 of a step of an ideal DAC (63 << 5 = 2016 at the top). */
static const uint16_t kDac[64] = {
    0,    24,   48,   76,   95,   124,  150,  185,  190,  222,  248,  287,  300,  340,  370,  418,
    379,  419,  445,  492,  496,  544,  574,  631,  601,  652,  682,  743,  742,  803,  840,  914,
    762,  818,  844,  910,  896,  960,  993,  1070, 1002, 1068, 1099, 1179, 1161, 1239, 1279, 1374,
    1222, 1295, 1326, 1416, 1388, 1474, 1514, 1619, 1517, 1605, 1644, 1753, 1724, 1829, 1884, 2016,
};

/* Mod table entry -> counter increment (entry 4 resets the counter instead). */
static const int8_t kModStep[8] = {0, 1, 2, 4, 0, -4, -2, -1};

double nes_fds_dac(int sample) { return kDac[sample & 63] / 2016.0; }

int nes_fds_position(const NesFds* f) { return (int)(f->wave_acc >> 18) & 63; }

uint32_t nes_fds_frequency(const NesFds* f) { return (uint32_t)f->freq_lo | (uint32_t)(f->freq_hi & 15) << 8; }

uint32_t nes_fds_mod_frequency(const NesFds* f) { return (uint32_t)f->mod_lo | (uint32_t)(f->mod_hi & 15) << 8; }

/* NESdev "Modulation unit": counter x gain, rounded up on positive results (bit 11 clear) with a remainder, + $400,
 * bits 4-11 times the frequency, 20 bits. Unsigned arithmetic: the bits taken are those of the two's complement. */
uint32_t nes_fds_pitch(const NesFds* f) {
    uint32_t temp = (uint32_t)((int32_t)f->counter * (int32_t)f->mod_gain);
    if ((temp & 0x0F) && !(temp & 0x800)) temp += 0x20;
    temp += 0x400;
    temp = (temp >> 4) & 0xFF;
    return (nes_fds_frequency(f) * temp) & 0xFFFFF;
}

float nes_fds_mix_level(const NesFds* f) {
    if (f->mute) return 0.0f;
    const int gain = f->gain < 32 ? f->gain : 32;
    return (float)(NES_FDS_FULL_LEVEL * nes_fds_dac(f->sample) * gain / 32.0 * 2.0 / (2.0 + (f->ctrl & 3)));
}

static int32_t amplitude(const NesFds* f) { return (int32_t)lrintf(f->lp * (float)NES_APU_AMP_SCALE); }

void nes_fds_set_output(NesFds* f, blip_t* out) {
    f->out = out;
    f->lp = nes_fds_mix_level(f);
    f->lp_k = (float)(1.0 - exp(-2.0 * 3.14159265358979 * NES_FDS_LOWPASS_HZ * NES_FDS_UNIT_CYCLES / 1789773.0));
    f->amp = amplitude(f);
}

void nes_fds_set_mute(NesFds* f, int muted) { f->mute = (uint8_t)(muted != 0); }

static uint32_t env_period(const NesFds* f, uint8_t ctrl) { return 8u * ((ctrl & 0x3Fu) + 1u) * (f->env_speed + 1u); }

static int env_enabled(const NesFds* f, uint8_t ctrl) {
    return !(ctrl & ENV_DISABLED) && f->env_speed && !(f->freq_hi & (WAVE_HALT | ENV_OFF));
}

void nes_fds_reset(NesFds* f) {
    blip_t* out = f->out;
    const uint8_t mute = f->mute;
    memset(f, 0, sizeof *f);
    f->out = out;
    f->mute = mute;
    f->vol_ctrl = ENV_DISABLED; /* the BIOS writes $4080 = $80 */
    f->env_speed = 0xE8;        /* and $408A = $E8 */
    f->unit_timer = NES_FDS_UNIT_CYCLES;
    f->vol_timer = env_period(f, f->vol_ctrl);
    f->mod_timer = env_period(f, f->mod_ctrl);
    if (out) nes_fds_set_output(f, out);
}

/* The DAC input: the sample at the wave position unless write-enabled (held). */
static void update_sample(NesFds* f) {
    if (!(f->ctrl & WRITE_ENABLE)) f->sample = f->wave[nes_fds_position(f)];
}

/* While the wave position is 0 the output gain follows the volume gain. */
static void follow_gain(NesFds* f) {
    if (nes_fds_position(f) == 0) f->gain = f->vol_gain;
}

static void env_step(uint8_t ctrl, uint8_t* gain) {
    if (ctrl & ENV_UP) {
        if (*gain < 32) ++*gain;
    } else if (*gain > 0) {
        --*gain;
    }
}

/* One unit tick: the mod unit, then the wave unit with the counter it left. */
static void unit_tick(NesFds* f) {
    if (!(f->mod_hi & MOD_HALT)) {
        const uint32_t low = (f->mod_acc & 0xFFF) + nes_fds_mod_frequency(f);
        uint32_t high = f->mod_acc & ~0xFFFu;
        if (low > 0xFFF || (f->mod_hi & MOD_CARRY)) {
            const uint8_t e = f->mod[(f->mod_acc >> 13) & 31];
            f->counter = e == 4 ? 0 : (int8_t)((((f->counter + kModStep[e]) & 0x7F) ^ 0x40) - 0x40);
            high = (high + 0x1000) & 0x3F000;
        }
        f->mod_acc = high | (low & 0xFFF);
    }
    if (!(f->freq_hi & WAVE_HALT)) {
        const uint32_t acc = f->wave_acc + nes_fds_pitch(f);
        f->wave_acc = acc & 0xFFFFFF;
        if (nes_fds_position(f) == 0 || acc > 0xFFFFFF) f->gain = f->vol_gain; /* at or past position 0 */
        update_sample(f);
    }
}

/* Output: one filter step per unit tick toward the current level; the amplitude goes out when it changes. */
static void filter_tick(NesFds* f) {
    f->lp += f->lp_k * (nes_fds_mix_level(f) - f->lp);
    const int32_t amp = amplitude(f);
    if (amp != f->amp) {
        blip_add_delta_fast(f->out, f->time, amp - f->amp);
        f->amp = amp;
    }
}

void nes_fds_run(NesFds* f, uint32_t t) {
    while (f->time < t) {
        const int vol_on = env_enabled(f, f->vol_ctrl), mod_on = env_enabled(f, f->mod_ctrl);
        uint32_t next = f->unit_timer;
        if (vol_on && f->vol_timer < next) next = f->vol_timer;
        if (mod_on && f->mod_timer < next) next = f->mod_timer;
        const uint32_t step = t - f->time < next ? t - f->time : next;
        f->time += step;
        f->unit_timer -= step;
        if (vol_on) f->vol_timer -= step;
        if (mod_on) f->mod_timer -= step;
        if (vol_on && !f->vol_timer) {
            f->vol_timer = env_period(f, f->vol_ctrl);
            env_step(f->vol_ctrl, &f->vol_gain);
            follow_gain(f);
        }
        if (mod_on && !f->mod_timer) {
            f->mod_timer = env_period(f, f->mod_ctrl);
            env_step(f->mod_ctrl, &f->mod_gain);
        }
        if (!f->unit_timer) {
            f->unit_timer = NES_FDS_UNIT_CYCLES;
            unit_tick(f);
            if (f->out) filter_tick(f);
        }
    }
}

/* ---- registers ---------------------------------------------------------------------------- */

int nes_fds_register(uint16_t addr) { return addr >= 0x4040 && addr <= 0x408A; }

int nes_fds_readable(uint16_t addr) { return (addr >= 0x4040 && addr <= 0x407F) || (addr >= 0x4090 && addr <= 0x4097); }

void nes_fds_write(NesFds* f, uint32_t t, uint16_t addr, uint8_t value) {
    nes_fds_run(f, t);
    if (addr >= 0x4040 && addr <= 0x407F) {
        if (f->ctrl & WRITE_ENABLE) f->wave[addr - 0x4040] = value & 0x3F;
        return;
    }
    switch (addr) {
    case 0x4080:
        f->vol_ctrl = value;
        f->vol_timer = env_period(f, value);
        if (value & ENV_DISABLED) {
            f->vol_gain = value & 0x3F;
            if (!f->vol_gain) f->gain = 0; /* writing gain 0 mutes at once */
            follow_gain(f);
        }
        break;
    case 0x4082: f->freq_lo = value; break;
    case 0x4083:
        f->freq_hi = value;
        if (value & ENV_OFF) {
            f->vol_timer = env_period(f, f->vol_ctrl);
            f->mod_timer = env_period(f, f->mod_ctrl);
        }
        if (value & WAVE_HALT) {
            f->wave_acc = 0;
            follow_gain(f);
            update_sample(f);
        }
        break;
    case 0x4084:
        f->mod_ctrl = value;
        f->mod_timer = env_period(f, value);
        if (value & ENV_DISABLED) f->mod_gain = value & 0x3F;
        break;
    case 0x4085: f->counter = (int8_t)(((value & 0x7F) ^ 0x40) - 0x40); break;
    case 0x4086: f->mod_lo = value; break;
    case 0x4087:
        f->mod_hi = value;
        if (value & MOD_HALT) f->mod_acc &= ~0x1FFFu;
        break;
    case 0x4088:
        if (f->mod_hi & MOD_HALT) {
            f->mod[(f->mod_acc >> 13) & 31] = value & 7;
            f->mod_acc = (f->mod_acc + 0x2000) & 0x3FFFF;
        }
        break;
    case 0x4089:
        f->ctrl = value & (WRITE_ENABLE | 3);
        update_sample(f);
        break;
    case 0x408A: f->env_speed = value; break;
    default: break; /* $4081: no register */
    }
}

uint8_t nes_fds_read(NesFds* f, uint32_t t, uint16_t addr) {
    nes_fds_run(f, t);
    if (addr >= 0x4040 && addr <= 0x407F) {
        return (uint8_t)(0x40 | (f->ctrl & WRITE_ENABLE ? f->wave[addr - 0x4040] : f->wave[nes_fds_position(f)]));
    }
    switch (addr) {
    case 0x4090: return (uint8_t)(0x40 | f->vol_gain);
    case 0x4091: return (uint8_t)(f->wave_acc >> 12);
    case 0x4092: return (uint8_t)(0x40 | f->mod_gain);
    case 0x4093: return (uint8_t)((f->mod_acc >> 5) & 0x7F);
    case 0x4094: return (uint8_t)((uint32_t)((int32_t)f->counter * (int32_t)f->mod_gain) >> 4);
    case 0x4095: {
        static const uint8_t kIncrement[8] = {0x0, 0x1, 0x2, 0x4, 0xC, 0xC, 0xE, 0xF};
        return kIncrement[f->mod[(f->mod_acc >> 13) & 31]];
    }
    case 0x4096: return (uint8_t)(0x40 | f->sample);
    case 0x4097: return (uint8_t)(f->counter & 0x7F);
    default: return 0;
    }
}

void nes_fds_end_frame(NesFds* f, uint32_t frame_cycles) {
    nes_fds_run(f, frame_cycles);
    f->time = 0;
}

int nes_fds_same_state(const NesFds* a, const NesFds* b) {
    return !memcmp(a->wave, b->wave, sizeof a->wave) && !memcmp(a->mod, b->mod, sizeof a->mod) && a->vol_ctrl == b->vol_ctrl &&
           a->mod_ctrl == b->mod_ctrl && a->vol_gain == b->vol_gain && a->mod_gain == b->mod_gain && a->gain == b->gain &&
           a->freq_hi == b->freq_hi && a->mod_hi == b->mod_hi && a->freq_lo == b->freq_lo && a->mod_lo == b->mod_lo &&
           a->counter == b->counter && a->ctrl == b->ctrl && a->env_speed == b->env_speed && a->sample == b->sample &&
           a->wave_acc == b->wave_acc && a->mod_acc == b->mod_acc && a->unit_timer == b->unit_timer &&
           a->vol_timer == b->vol_timer && a->mod_timer == b->mod_timer && a->time == b->time;
}
