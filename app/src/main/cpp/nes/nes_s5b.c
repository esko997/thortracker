/* Sunsoft 5B audio (see nes_s5b.h for scope and sources). With output attached run() steps one generator clock
 * (16 CPU cycles) at a time and sends the mixed level to the band-limited buffer whenever it changes; without output
 * the counters, the LFSR and the envelope advance by whole clock counts to the same state. */
#include "nes_s5b.h"

#include <math.h>
#include <string.h>

#include "nes_apu.h"

#define AMP_FULL (NES_S5B_LEVEL * NES_APU_AMP_SCALE) /* blip amplitude of one channel at DAC level 31 */

/* Unused register bits (NESdev's bit fields) read back as 0. */
static const uint8_t kRegisterMask[16] = {0xFF, 0x0F, 0xFF, 0x0F, 0xFF, 0x0F, 0x1F, 0x3F,
                                           0x1F, 0x1F, 0x1F, 0xFF, 0xFF, 0x0F, 0xFF, 0xFF};

/* 10^(-1.5 (31 - L) / 20) for L = 2..31 (1.5 dB per step, 31 = 1.0); levels 0 and 1 are silent (NESdev). */
static const double kDac[32] = {
    0.000000000, 0.000000000, 0.006683439, 0.007943282, 0.009440609, 0.011220185, 0.013335214, 0.015848932,
    0.018836491, 0.022387211, 0.026607251, 0.031622777, 0.037583740, 0.044668359, 0.053088444, 0.063095734,
    0.074989421, 0.089125094, 0.105925373, 0.125892541, 0.149623566, 0.177827941, 0.211348904, 0.251188643,
    0.298538262, 0.354813389, 0.421696503, 0.501187234, 0.595662144, 0.707945784, 0.841395142, 1.000000000,
};

void nes_s5b_reset(NesS5b* s) {
    blip_t* out = s->out;
    const uint8_t mute = s->mute;
    memset(s, 0, sizeof *s);
    s->out = out;
    s->mute = mute;
    s->prescale = NES_S5B_CLOCK_DIVIDER;
    s->lfsr = 1;
    s->env_held = 1; /* level 0 until a shape is written */
}

/* ---- generators --------------------------------------------------------------------------- */

static uint32_t tone_period(const NesS5b* s, int c) {
    const uint32_t p = s->reg[2 * c] | (uint32_t)(s->reg[2 * c + 1] & 15) << 8;
    return p ? p : 1;
}

static uint32_t noise_period(const NesS5b* s) { return s->reg[6] ? s->reg[6] : 1u; }

static uint32_t envelope_period(const NesS5b* s) {
    const uint32_t p = s->reg[11] | (uint32_t)s->reg[12] << 8;
    return p ? p : 1;
}

/* An up-counter run for `clocks` generator clocks: each clock it counts, and on reaching `period` (count + 1 >=
 * period, which a shortened period gives at the next clock) it resets to 0. Returns the reaches. */
static uint32_t counter_run(uint16_t* count, uint32_t period, uint32_t clocks) {
    const uint32_t first = *count + 1u >= period ? 1u : period - *count; /* clocks to the next reach */
    if (clocks < first) {
        *count = (uint16_t)(*count + clocks);
        return 0;
    }
    clocks -= first;
    *count = (uint16_t)(clocks % period);
    return 1 + clocks / period;
}

/* The envelope's step past the end of a ramp (shape bits C A a H in $0D). */
static void envelope_ramp_end(NesS5b* s) {
    const uint8_t shape = s->reg[13];
    if (!(shape & 8)) { /* no continue: to 0, held */
        s->env_level = 0;
        s->env_held = 1;
    } else if (shape & 1) { /* hold: the attack's end level, flipped by alternate */
        s->env_level = (((shape >> 2) ^ (shape >> 1)) & 1) ? 31 : 0;
        s->env_held = 1;
    } else if (shape & 2) { /* alternate: turn around, the edge level once more */
        s->env_up ^= 1;
    } else { /* repeat the ramp */
        s->env_level = s->env_up ? 0 : 31;
    }
}

/* `steps` envelope steps. Once past a ramp end a running envelope repeats every 64 steps (a ramp is 32: 31 moves and
 * the end step; alternating shapes take two ramps to return), so long runs skip whole cycles. */
static void envelope_steps(NesS5b* s, uint32_t steps) {
    while (steps && !s->env_held) {
        const uint32_t room = s->env_up ? 31u - s->env_level : s->env_level; /* moves left in this ramp */
        if (steps <= room) {
            s->env_level = (uint8_t)(s->env_up ? s->env_level + steps : s->env_level - steps);
            return;
        }
        steps -= room + 1;
        s->env_level = s->env_up ? 31 : 0;
        envelope_ramp_end(s);
        steps %= 64;
    }
}

static void lfsr_steps(NesS5b* s, uint32_t steps) {
    uint32_t r = s->lfsr;
    while (steps--) r = (r >> 1) | ((r ^ (r >> 3)) & 1u) << 16;
    s->lfsr = r;
}

/* Every generator for `clocks` generator clocks. */
static void advance(NesS5b* s, uint32_t clocks) {
    for (int c = 0; c < NES_S5B_VOICES; ++c) {
        s->square[c] ^= (uint8_t)(counter_run(&s->tone_count[c], tone_period(s, c), clocks) & 1u);
    }
    const uint32_t toggles = counter_run(&s->noise_count, noise_period(s), clocks);
    lfsr_steps(s, s->noise_half ? toggles / 2 : (toggles + 1) / 2); /* a step on each 0 -> 1 of the /2 prescaler */
    s->noise_half ^= (uint8_t)(toggles & 1u);
    envelope_steps(s, counter_run(&s->env_count, envelope_period(s), clocks));
}

/* ---- output ------------------------------------------------------------------------------- */

int nes_s5b_channel_level(const NesS5b* s, int channel) {
    const uint8_t mixer = s->reg[7];
    const int tone = (mixer >> channel & 1) | s->square[channel];
    const int noise = (mixer >> (channel + 3) & 1) | nes_s5b_noise(s);
    if (!(tone && noise)) return 0;
    const uint8_t volume = s->reg[8 + channel];
    return volume & 0x10 ? s->env_level : 2 * (volume & 15) + 1;
}

double nes_s5b_dac(int level) { return level >= 0 && level < 32 ? kDac[level] : 0.0; }

static double dac_sum(const NesS5b* s) {
    double sum = 0;
    for (int c = 0; c < NES_S5B_VOICES; ++c) {
        if (!(s->mute & (1 << c))) sum += kDac[nes_s5b_channel_level(s, c)];
    }
    return sum;
}

float nes_s5b_mix_level(const NesS5b* s) { return (float)(dac_sum(s) * NES_S5B_LEVEL); }

static int32_t amplitude(const NesS5b* s) { return (int32_t)lrint(dac_sum(s) * AMP_FULL); }

static void update_output(NesS5b* s) {
    const int32_t amp = amplitude(s);
    if (amp != s->amp) {
        blip_add_delta(s->out, s->time, amp - s->amp);
        s->amp = amp;
    }
}

void nes_s5b_set_output(NesS5b* s, blip_t* out) {
    s->out = out;
    s->amp = amplitude(s);
}

void nes_s5b_set_mute(NesS5b* s, uint8_t mask) {
    s->mute = mask;
    if (s->out) update_output(s);
}

void nes_s5b_run(NesS5b* s, uint32_t t) {
    if (t <= s->time) return;
    uint32_t cycles = t - s->time;
    if (!s->out) { /* whole clocks at once: the state is the same as clock by clock */
        if (cycles < s->prescale) {
            s->prescale = (uint8_t)(s->prescale - cycles);
        } else {
            cycles -= s->prescale;
            s->prescale = (uint8_t)(NES_S5B_CLOCK_DIVIDER - cycles % NES_S5B_CLOCK_DIVIDER);
            advance(s, 1 + cycles / NES_S5B_CLOCK_DIVIDER);
        }
        s->time = t;
        return;
    }
    while (cycles >= s->prescale) { /* any clock may change a channel's level */
        cycles -= s->prescale;
        s->time += s->prescale;
        s->prescale = NES_S5B_CLOCK_DIVIDER;
        advance(s, 1);
        update_output(s);
    }
    s->prescale = (uint8_t)(s->prescale - cycles);
    s->time = t;
}

/* ---- registers ---------------------------------------------------------------------------- */

int nes_s5b_register(uint16_t addr) { return addr >= 0xC000; }

void nes_s5b_write(NesS5b* s, uint32_t t, uint16_t addr, uint8_t value) {
    nes_s5b_run(s, t);
    if (addr < 0xC000) return;
    if (addr < 0xE000) {
        s->select = value;
        return;
    }
    if (s->select & 0xF0) return; /* a select with bits 4-7 set disables data writes */
    const int r = s->select & 15;
    s->reg[r] = (uint8_t)(value & kRegisterMask[r]);
    if (r == 13) { /* new shape: the envelope restarts at once */
        s->env_count = 0;
        s->env_up = (uint8_t)((value >> 2) & 1);
        s->env_level = s->env_up ? 0 : 31;
        s->env_held = 0;
    }
    if (s->out) update_output(s);
}

void nes_s5b_end_frame(NesS5b* s, uint32_t frame_cycles) {
    nes_s5b_run(s, frame_cycles);
    s->time = 0;
}
