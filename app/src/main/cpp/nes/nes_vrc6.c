/* Konami VRC6 audio (see nes_vrc6.h for scope and sources). Event-driven like nes_apu.c: run() jumps from
 * one oscillator clock to the next and sends the DAC level to the band-limited buffer whenever it changes;
 * without output every oscillator advances arithmetically. */
#include "nes_vrc6.h"

#include <math.h>
#include <string.h>

#include "nes_apu.h"

#define AMP_PER_STEP (NES_VRC6_STEP * NES_APU_AMP_SCALE)

void nes_vrc6_reset(NesVrc6* v) {
    blip_t* out = v->out;
    const uint8_t mute = v->mute;
    memset(v, 0, sizeof *v);
    v->out = out;
    v->mute = mute;
    v->pulse[0].step = v->pulse[1].step = 15;
    v->pulse[0].timer = v->pulse[1].timer = v->saw.timer = 1; /* period 0: the divider clocks every cycle */
}

/* ---- oscillators -------------------------------------------------------------------------- */

static int enabled(uint8_t hi) { return hi & 0x80; }

/* CPU cycles per divider clock: the 12-bit period, shifted right by $9003's B (4) or A (8), plus one. */
static uint32_t divider_period(const NesVrc6* v, uint8_t lo, uint8_t hi) {
    uint32_t p = (uint32_t)lo | (uint32_t)(hi & 15) << 8;
    if (v->freq & 4) {
        p >>= 8;
    } else if (v->freq & 2) {
        p >>= 4;
    }
    return p + 1;
}

/* Runs a divider for `cycles`; returns the clocks it gave (it reloads with `period` on reaching zero). */
static uint32_t divider_advance(uint32_t* timer, uint32_t period, uint32_t cycles) {
    if (cycles < *timer) {
        *timer -= cycles;
        return 0;
    }
    cycles -= *timer;
    *timer = period - cycles % period;
    return 1 + cycles / period;
}

/* `n` duty generator clocks: the step counts down 15..0 and wraps; held at 15 while disabled. */
static void pulse_clocks(NesVrc6Pulse* p, uint32_t n) {
    if (enabled(p->hi)) p->step = (uint8_t)((p->step + 16u - n % 16u) & 15u);
}

/* `n` saw sequencer clocks: step 0..13; even steps 2..12 add the rate, the 14th clock resets to step 0 with
 * the accumulator 0. Held at step 0, accumulator 0 while disabled. */
static void saw_clocks(NesVrc6Saw* s, uint32_t n) {
    if (!enabled(s->hi) || !n) return;
    const uint32_t total = s->step + n;
    if (total < 14) {
        s->acc = (uint8_t)(s->acc + s->rate * (total / 2 - s->step / 2));
        s->step = (uint8_t)total;
    } else { /* reset at least once: the accumulator holds the adds since the last step 0 */
        s->step = (uint8_t)(total % 14);
        s->acc = (uint8_t)(s->rate * (s->step / 2u));
    }
}

static int pulse_out(const NesVrc6Pulse* p) {
    if (!enabled(p->hi)) return 0;
    const int volume = p->ctrl & 15;
    if (p->ctrl & 0x80) return volume; /* mode bit: duty ignored */
    return p->step <= ((p->ctrl >> 4) & 7) ? volume : 0;
}

static int saw_out(const NesVrc6Saw* s) { return enabled(s->hi) ? s->acc >> 3 : 0; }

int nes_vrc6_channel_out(const NesVrc6* v, int channel) {
    return channel == NES_VRC6_SAW ? saw_out(&v->saw) : pulse_out(&v->pulse[channel & 1]);
}

static int dac_input(const NesVrc6* v) {
    int sum = 0;
    for (int c = 0; c < NES_VRC6_CHANNELS; ++c) {
        if (!(v->mute & (1 << c))) sum += nes_vrc6_channel_out(v, c);
    }
    return sum;
}

float nes_vrc6_mix_level(const NesVrc6* v) { return (float)(dac_input(v) * NES_VRC6_STEP); }

static int32_t amplitude(const NesVrc6* v) { return (int32_t)lrint(dac_input(v) * AMP_PER_STEP); }

static void update_output(NesVrc6* v) {
    const int32_t amp = amplitude(v);
    if (amp != v->amp) {
        blip_add_delta(v->out, v->time, amp - v->amp);
        v->amp = amp;
    }
}

void nes_vrc6_set_output(NesVrc6* v, blip_t* out) {
    v->out = out;
    v->amp = amplitude(v);
}

void nes_vrc6_set_mute(NesVrc6* v, uint8_t mask) {
    v->mute = mask;
    if (v->out) update_output(v);
}

/* All three dividers (and their sequencers) for `cycles`; the caller keeps that within one clock of every
 * sounding oscillator when output is attached. */
static void advance(NesVrc6* v, uint32_t cycles) {
    for (int i = 0; i < 2; ++i) {
        NesVrc6Pulse* p = &v->pulse[i];
        pulse_clocks(p, divider_advance(&p->timer, divider_period(v, p->lo, p->hi), cycles));
    }
    NesVrc6Saw* s = &v->saw;
    saw_clocks(s, divider_advance(&s->timer, divider_period(v, s->lo, s->hi), cycles));
}

void nes_vrc6_run(NesVrc6* v, uint32_t t) {
    if (v->freq & 1) { /* H: every oscillator stopped where it is */
        if (t > v->time) v->time = t;
        return;
    }
    while (v->time < t) {
        uint32_t step = t - v->time;
        if (v->out) { /* to the next clock of an enabled oscillator: its output may change there */
            for (int i = 0; i < 2; ++i) {
                if (enabled(v->pulse[i].hi) && v->pulse[i].timer < step) step = v->pulse[i].timer;
            }
            if (enabled(v->saw.hi) && v->saw.timer < step) step = v->saw.timer;
        }
        advance(v, step);
        v->time += step;
        if (v->out) update_output(v);
    }
}

/* ---- registers ---------------------------------------------------------------------------- */

int nes_vrc6_register(uint16_t addr) {
    return (addr >= 0x9000 && addr <= 0x9003) || (addr >= 0xA000 && addr <= 0xA002) || (addr >= 0xB000 && addr <= 0xB002);
}

void nes_vrc6_write(NesVrc6* v, uint32_t t, uint16_t addr, uint8_t value) {
    nes_vrc6_run(v, t);
    NesVrc6Pulse* p = &v->pulse[addr >= 0xA000];
    switch (addr) {
    case 0x9000:
    case 0xA000:
        p->ctrl = value;
        break;
    case 0x9001:
    case 0xA001:
        p->lo = value;
        break;
    case 0x9002:
    case 0xA002:
        p->hi = value;
        if (!enabled(value)) p->step = 15; /* reset and halted until E is set again */
        break;
    case 0x9003:
        v->freq = value & 7;
        break;
    case 0xB000:
        v->saw.rate = value & 0x3F;
        break;
    case 0xB001:
        v->saw.lo = value;
        break;
    case 0xB002:
        v->saw.hi = value;
        if (!enabled(value)) { /* accumulator forced to 0; the divider keeps running */
            v->saw.step = 0;
            v->saw.acc = 0;
        }
        break;
    default:
        return;
    }
    if (v->out) update_output(v);
}

void nes_vrc6_end_frame(NesVrc6* v, uint32_t frame_cycles) {
    nes_vrc6_run(v, frame_cycles);
    v->time = 0;
}
