/* Nintendo MMC5 audio (see nes_mmc5.h for scope, sources and limitations). Event-driven like nes_vrc6.c: run()
 * jumps from one timer clock of an audible pulse or envelope/length clock to the next and sends the level to the
 * band-limited buffer whenever it changes; without output every timer advances arithmetically. */
#include "nes_mmc5.h"

#include <math.h>
#include <string.h>

void nes_mmc5_reset(NesMmc5* v) {
    blip_t* out = v->out;
    const uint8_t mute = v->mute;
    memset(v, 0, sizeof *v);
    v->out = out;
    v->mute = mute;
    v->pulse[0].timer = v->pulse[1].timer = 2; /* period 0: one sequencer step every 2 CPU cycles */
    v->clock_timer = NES_MMC5_CLOCK_PERIOD;
}

/* ---- voices ------------------------------------------------------------------------------- */

static int envelope_volume(const NesMmc5Pulse* p) { return (p->ctrl & 0x10) ? (p->ctrl & 15) : p->env.decay; }

/* No sweep and no period-below-8 silencing: only the length counter and the duty step gate the volume. */
static int pulse_out(const NesMmc5Pulse* p) {
    if (!p->length || !nes_duty_table[p->ctrl >> 6][p->seq]) return 0;
    return envelope_volume(p);
}

int nes_mmc5_channel_out(const NesMmc5* v, int channel) {
    return channel == NES_MMC5_PCM ? v->dac : pulse_out(&v->pulse[channel & 1]);
}

/* The 2A03 mixer's formulas on the MMC5's own outputs: pulses as the APU pulses, the DAC as a DMC level of the
 * same value (up to 255: twice the DMC's range), negated (inverted polarity). */
static double level(const NesMmc5* v) {
    const int p1 = (v->mute & (1 << NES_MMC5_PULSE1)) ? 0 : pulse_out(&v->pulse[0]);
    const int p2 = (v->mute & (1 << NES_MMC5_PULSE2)) ? 0 : pulse_out(&v->pulse[1]);
    const int d = (v->mute & (1 << NES_MMC5_PCM)) ? 0 : v->dac;
    double out = 0.0;
    if (p1 + p2) out += 95.88 / (8128.0 / (double)(p1 + p2) + 100.0);
    if (d) out += 159.79 / (22638.0 / (double)d + 100.0);
    return -out;
}

float nes_mmc5_mix_level(const NesMmc5* v) { return (float)level(v); }

static int32_t amplitude(const NesMmc5* v) { return (int32_t)lrint(level(v) * NES_APU_AMP_SCALE); }

static void update_output(NesMmc5* v) {
    const int32_t amp = amplitude(v);
    if (amp != v->amp) {
        blip_add_delta(v->out, v->time, amp - v->amp);
        v->amp = amp;
    }
}

void nes_mmc5_set_output(NesMmc5* v, blip_t* out) {
    v->out = out;
    v->amp = amplitude(v);
}

void nes_mmc5_set_mute(NesMmc5* v, uint8_t mask) {
    v->mute = mask;
    if (v->out) update_output(v);
}

/* ---- timers and the envelope/length clock ------------------------------------------------- */

/* Runs a pulse timer for `cycles`; returns the sequencer steps it gave (it reloads 2 (period + 1) at zero). */
static uint32_t timer_advance(NesMmc5Pulse* p, uint32_t cycles) {
    const uint32_t period = 2u * (p->period + 1u);
    if (cycles < p->timer) {
        p->timer -= cycles;
        return 0;
    }
    cycles -= p->timer;
    p->timer = period - cycles % period;
    return 1 + cycles / period;
}

/* As the APU envelope (nes_apu.c): start flag, divider of the volume bits, decay 15..0, loop with bit 5. */
static void clock_envelope(NesMmc5Pulse* p) {
    NesEnvelope* e = &p->env;
    if (e->start) {
        e->start = 0;
        e->decay = 15;
        e->divider = p->ctrl & 15;
    } else if (e->divider == 0) {
        e->divider = p->ctrl & 15;
        if (e->decay) {
            --e->decay;
        } else if (p->ctrl & 0x20) {
            e->decay = 15;
        }
    } else {
        --e->divider;
    }
}

/* Every NES_MMC5_CLOCK_PERIOD cycles: both envelopes and both length counters (bit 5 halts the counter). */
static void clock_units(NesMmc5* v) {
    for (int i = 0; i < 2; ++i) {
        NesMmc5Pulse* p = &v->pulse[i];
        clock_envelope(p);
        if (!(p->ctrl & 0x20) && p->length) --p->length;
    }
}

/* Whether a timer clock of pulse `i` can change the output (sounding and in the mix). */
static int audible(const NesMmc5* v, int i) {
    const NesMmc5Pulse* p = &v->pulse[i];
    return p->length && envelope_volume(p) && !(v->mute & (1 << i));
}

/* `cycles` <= the time to the next envelope/length clock. */
static void advance(NesMmc5* v, uint32_t cycles) {
    for (int i = 0; i < 2; ++i) {
        NesMmc5Pulse* p = &v->pulse[i];
        p->seq = (uint8_t)((p->seq + 8u - timer_advance(p, cycles) % 8u) & 7u);
    }
    v->clock_timer -= cycles;
    if (v->clock_timer == 0) {
        clock_units(v);
        v->clock_timer = NES_MMC5_CLOCK_PERIOD;
    }
}

void nes_mmc5_run(NesMmc5* v, uint32_t t) {
    while (v->time < t) {
        uint32_t step = t - v->time;
        if (v->clock_timer < step) step = v->clock_timer;
        if (v->out) { /* to the next step of an audible pulse: its output may change there */
            for (int i = 0; i < 2; ++i) {
                if (audible(v, i) && v->pulse[i].timer < step) step = v->pulse[i].timer;
            }
        }
        advance(v, step);
        v->time += step;
        if (v->out) update_output(v);
    }
}

/* ---- registers ---------------------------------------------------------------------------- */

int nes_mmc5_register(uint16_t addr) { return (addr >= 0x5000 && addr <= 0x5007) || addr == 0x5010 || addr == 0x5011 || addr == 0x5015; }

void nes_mmc5_write(NesMmc5* v, uint32_t t, uint16_t addr, uint8_t value) {
    nes_mmc5_run(v, t);
    NesMmc5Pulse* p = &v->pulse[(addr >> 2) & 1];
    switch (addr) {
    case 0x5000:
    case 0x5004:
        p->ctrl = value;
        break;
    case 0x5001:
    case 0x5005: /* no sweep unit */
        return;
    case 0x5002:
    case 0x5006:
        p->period = (uint16_t)((p->period & 0x700) | value);
        break;
    case 0x5003:
    case 0x5007:
        p->period = (uint16_t)((p->period & 0xFF) | (value & 7) << 8);
        if (v->enabled & (1 << ((addr >> 2) & 1))) p->length = nes_length_table[value >> 3];
        p->seq = 0;
        p->env.start = 1;
        break;
    case 0x5010:
        v->pcm_mode = value & 1;
        v->irq_enable = (value & 0x80) != 0;
        break;
    case 0x5011:
        if (v->pcm_mode) return; /* read mode: direct writes are ignored */
        if (value) {
            v->dac = value;
            v->irq_trip = 0;
        } else { /* $00: the DAC keeps its value, the IRQ trips */
            v->irq_trip = 1;
        }
        break;
    case 0x5015:
        v->enabled = value & 3;
        if (!(value & 1)) v->pulse[0].length = 0;
        if (!(value & 2)) v->pulse[1].length = 0;
        break;
    default:
        return;
    }
    if (v->out) update_output(v);
}

uint8_t nes_mmc5_read(NesMmc5* v, uint32_t t, uint16_t addr) {
    nes_mmc5_run(v, t);
    if (addr == 0x5015) return (uint8_t)((v->pulse[0].length ? 1 : 0) | (v->pulse[1].length ? 2 : 0));
    if (addr == 0x5010) {
        const uint8_t r = (uint8_t)((v->irq_trip && v->irq_enable ? 0x80 : 0) | 0x01); /* bit 0: MMC5A */
        v->irq_trip = 0;
        return r;
    }
    return 0;
}

void nes_mmc5_end_frame(NesMmc5* v, uint32_t frame_cycles) {
    nes_mmc5_run(v, frame_cycles);
    v->time = 0;
}
