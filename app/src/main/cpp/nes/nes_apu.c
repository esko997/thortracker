/* NTSC 2A03 APU (see nes_apu.h for scope). Event-driven: run() jumps from one timer, DMC or frame-
 * counter event to the next instead of stepping every CPU cycle, and sends the mixer output to
 * the band-limited buffer whenever it changes. */
#include "nes_apu.h"

#include <math.h>
#include <string.h>

const uint8_t nes_length_table[32] = {10, 254, 20, 2,  40, 4,  80, 6,  160, 8,  60, 10, 14, 12, 26, 14,
                                      12, 16,  24, 18, 48, 20, 96, 22, 192, 24, 72, 26, 16, 28, 32, 30};
const uint8_t nes_duty_table[4][8] = {
    {0, 1, 0, 0, 0, 0, 0, 0}, {0, 1, 1, 0, 0, 0, 0, 0}, {0, 1, 1, 1, 1, 0, 0, 0}, {1, 0, 0, 1, 1, 1, 1, 1}};
static const uint8_t kTriangle[32] = {15, 14, 13, 12, 11, 10, 9,  8,  7,  6,  5,  4,  3,  2,  1,  0,
                                      0,  1,  2,  3,  4,  5,  6,  7,  8,  9,  10, 11, 12, 13, 14, 15};
/* Noise timer periods in CPU cycles (NTSC). */
static const uint16_t kNoisePeriod[16] = {4, 8, 16, 32, 64, 96, 128, 160, 202, 254, 380, 508, 762, 1016, 2034, 4068};
const uint16_t nes_dmc_rate[16] = {428, 380, 340, 320, 286, 254, 226, 214, 190, 160, 142, 128, 106, 84, 72, 54};

/* Frame counter steps in CPU cycles after the sequence (re)start. The last entry restarts it. */
#define FC_Q 1
#define FC_H 2
#define FC_IRQ 4
#define FC_WRAP 8
static const uint32_t kFcTime4[5] = {7457, 14913, 22371, 29829, 29830};
static const uint8_t kFcWhat4[5] = {FC_Q, FC_Q | FC_H, FC_Q, FC_Q | FC_H | FC_IRQ, FC_WRAP};
static const uint32_t kFcTime5[6] = {7457, 14913, 22371, 29829, 37281, 37282};
static const uint8_t kFcWhat5[6] = {FC_Q, FC_Q | FC_H, FC_Q, 0, FC_Q | FC_H, FC_WRAP};

#define NEVER 0xFFFFFFFFu

void nes_apu_reset(NesApu* a) {
    blip_t* out = a->out;
    uint8_t mute = a->mute;
    const uint8_t* prg = a->prg;
    memset(a, 0, sizeof *a);
    a->out = out;
    a->mute = mute;
    a->prg = prg;
    a->noise.lfsr = 1;
    a->fc_reset_at = -1;
    a->pulse[0].timer = a->pulse[1].timer = 2;
    a->tri.timer = 1;
    a->noise.timer = kNoisePeriod[0];
    a->dmc.timer = nes_dmc_rate[0];
    a->dmc.bits = 8;
    a->dmc.silence = 1;
}

void nes_apu_set_memory(NesApu* a, const uint8_t* prg) { a->prg = prg; }

/* ---- channel outputs ---------------------------------------------------------------------- */

static int sweep_target(const NesPulse* p, int index) {
    int change = p->period >> (p->sweep & 7);
    if (p->sweep & 0x08) return p->period - change - (index == 0 ? 1 : 0);
    return p->period + change;
}

static int pulse_muted(const NesPulse* p, int index) {
    return p->period < 8 || sweep_target(p, index) > 0x7FF;
}

static int envelope_volume(uint8_t ctrl, const NesEnvelope* e) { return (ctrl & 0x10) ? (ctrl & 15) : e->decay; }

static int pulse_out(const NesPulse* p, int index) {
    if (!p->length || pulse_muted(p, index) || !nes_duty_table[p->ctrl >> 6][p->seq]) return 0;
    return envelope_volume(p->ctrl, &p->env);
}

static int noise_out(const NesNoise* n) {
    if (!n->length || (n->lfsr & 1)) return 0;
    return envelope_volume(n->ctrl, &n->env);
}

static float mix(const NesApu* a) {
    int p1 = (a->mute & (1 << NES_APU_PULSE1)) ? 0 : pulse_out(&a->pulse[0], 0);
    int p2 = (a->mute & (1 << NES_APU_PULSE2)) ? 0 : pulse_out(&a->pulse[1], 1);
    int t = (a->mute & (1 << NES_APU_TRIANGLE)) ? 0 : kTriangle[a->tri.seq];
    int n = (a->mute & (1 << NES_APU_NOISE)) ? 0 : noise_out(&a->noise);
    int d = (a->mute & (1 << NES_APU_DMC)) ? 0 : a->dmc.level;
    float out = 0.0f;
    if (p1 + p2) out += 95.88f / (8128.0f / (float)(p1 + p2) + 100.0f);
    float tnd = (float)t / 8227.0f + (float)n / 12241.0f + (float)d / 22638.0f;
    if (tnd > 0.0f) out += 159.79f / (1.0f / tnd + 100.0f);
    return out;
}

float nes_apu_mix_level(const NesApu* a) { return mix(a); }

static void update_output(NesApu* a) {
    int32_t amp = (int32_t)lrintf(mix(a) * (float)NES_APU_AMP_SCALE);
    if (amp != a->amp) {
        blip_add_delta(a->out, a->time, amp - a->amp);
        a->amp = amp;
    }
}

void nes_apu_set_output(NesApu* a, blip_t* out) {
    a->out = out;
    /* Start from the current level without a step: the buffer's output is DC-free anyway. */
    a->amp = (int32_t)lrintf(mix(a) * (float)NES_APU_AMP_SCALE);
}

void nes_apu_set_mute(NesApu* a, uint8_t mask) {
    a->mute = mask;
    if (a->out) update_output(a);
}

/* ---- frame counter clocks ----------------------------------------------------------------- */

static void clock_envelope(uint8_t ctrl, NesEnvelope* e) {
    if (e->start) {
        e->start = 0;
        e->decay = 15;
        e->divider = ctrl & 15;
    } else if (e->divider == 0) {
        e->divider = ctrl & 15;
        if (e->decay) {
            --e->decay;
        } else if (ctrl & 0x20) {
            e->decay = 15;
        }
    } else {
        --e->divider;
    }
}

static void clock_quarter(NesApu* a) {
    clock_envelope(a->pulse[0].ctrl, &a->pulse[0].env);
    clock_envelope(a->pulse[1].ctrl, &a->pulse[1].env);
    clock_envelope(a->noise.ctrl, &a->noise.env);
    NesTriangle* t = &a->tri;
    if (t->linear_reload) {
        t->linear = t->ctrl & 0x7F;
    } else if (t->linear) {
        --t->linear;
    }
    if (!(t->ctrl & 0x80)) t->linear_reload = 0;
}

static void clock_half(NesApu* a) {
    for (int i = 0; i < 2; ++i) {
        NesPulse* p = &a->pulse[i];
        if (!(p->ctrl & 0x20) && p->length) --p->length;
        if (p->sweep_divider == 0 && (p->sweep & 0x80) && (p->sweep & 7) && !pulse_muted(p, i)) {
            p->period = (uint16_t)sweep_target(p, i);
        }
        if (p->sweep_divider == 0 || p->sweep_reload) {
            p->sweep_divider = (p->sweep >> 4) & 7;
            p->sweep_reload = 0;
        } else {
            --p->sweep_divider;
        }
    }
    if (!(a->tri.ctrl & 0x80) && a->tri.length) --a->tri.length;
    if (!(a->noise.ctrl & 0x20) && a->noise.length) --a->noise.length;
}

static uint32_t fc_until_next(const NesApu* a) {
    const uint32_t* times = a->mode5 ? kFcTime5 : kFcTime4;
    int n = a->mode5 ? 6 : 5;
    for (int i = 0; i < n; ++i) {
        if (times[i] > a->fc_cycle) return times[i] - a->fc_cycle;
    }
    return 1; /* unreachable: the last entry wraps */
}

static void fc_event(NesApu* a) {
    const uint32_t* times = a->mode5 ? kFcTime5 : kFcTime4;
    const uint8_t* what = a->mode5 ? kFcWhat5 : kFcWhat4;
    int n = a->mode5 ? 6 : 5;
    for (int i = 0; i < n; ++i) {
        if (times[i] != a->fc_cycle) continue;
        if (what[i] & FC_Q) clock_quarter(a);
        if (what[i] & FC_H) clock_half(a);
        if ((what[i] & FC_IRQ) && !a->irq_inhibit) a->frame_irq = 1;
        if (what[i] & FC_WRAP) a->fc_cycle = 0;
        return;
    }
}
/* ---- DMC ---------------------------------------------------------------------------------- */

static void dmc_restart(NesDmc* d) {
    d->addr = (uint16_t)(0xC000 + 64u * d->start);
    d->remaining = (uint16_t)(16u * d->length + 1u);
}

/* Memory reader: refills an empty sample buffer by DMA while bytes remain. */
static void dmc_fetch(NesApu* a) {
    NesDmc* d = &a->dmc;
    if (d->buffer_full || !d->remaining) return;
    d->buffer = a->prg ? a->prg[d->addr - 0x8000u] : 0;
    d->buffer_full = 1;
    ++d->fetches;
    d->addr = d->addr == 0xFFFF ? 0x8000 : (uint16_t)(d->addr + 1);
    if (--d->remaining == 0) {
        if (d->ctrl & 0x40) {
            dmc_restart(d);
        } else if (d->ctrl & 0x80) {
            d->irq = 1;
        }
    }
}

/* Nothing observable happens until $4015 starts a sample: silent, nothing buffered or to fetch. */
static int dmc_idle(const NesDmc* d) { return d->silence && !d->buffer_full && !d->remaining; }

/* Output unit, once per rate period: a level step from bit 0, then the next bit or output cycle. */
static void dmc_clock(NesApu* a) {
    NesDmc* d = &a->dmc;
    if (!d->silence) {
        if (d->shift & 1) {
            if (d->level <= 125) d->level += 2;
        } else if (d->level >= 2) {
            d->level -= 2;
        }
    }
    d->shift >>= 1;
    if (--d->bits == 0) {
        d->bits = 8;
        if (d->buffer_full) {
            d->silence = 0;
            d->shift = d->buffer;
            d->buffer_full = 0;
            dmc_fetch(a);
        } else {
            d->silence = 1;
        }
    }
}

/* Advances the DMC timer by `cycles` (<= the timer unless idle, when its phase and bit counter
 * advance arithmetically: the timer runs on hardware whether or not a sample plays). */
static void dmc_advance(NesApu* a, uint32_t cycles) {
    NesDmc* d = &a->dmc;
    if (cycles < d->timer) {
        d->timer -= cycles;
        return;
    }
    const uint32_t period = nes_dmc_rate[d->ctrl & 15];
    if (!dmc_idle(d)) {
        dmc_clock(a);
        d->timer = period;
        return;
    }
    cycles -= d->timer;
    const uint32_t clocks = 1 + cycles / period;
    d->timer = period - cycles % period;
    d->bits = (uint8_t)((d->bits - 1u + 8u - clocks % 8u) % 8u + 1u);
    d->shift = 0;
}

void nes_apu_dmc_move(NesApu* a, uint8_t start) {
    NesDmc* d = &a->dmc;
    if (d->remaining) d->addr = (uint16_t)(d->addr + 64 * ((int)start - (int)d->start));
    d->start = start;
}

void nes_apu_dmc_stop(NesApu* a) { a->dmc.remaining = 0; }

/* ---- timers ------------------------------------------------------------------------------- */

/* A silent channel's waveform phase is unobservable until a write resets it ($4003/$4007 reset
 * the duty step; length reloads need those writes), so only sounding timers are stepped. */
static int pulse_running(const NesPulse* p) { return p->length && p->period >= 8; }
static int triangle_running(const NesTriangle* t) { return t->length && t->linear && t->period >= 2; }

static uint32_t timers_until_next(const NesApu* a) {
    uint32_t next = NEVER;
    for (int i = 0; i < 2; ++i) {
        if (pulse_running(&a->pulse[i]) && a->pulse[i].timer < next) next = a->pulse[i].timer;
    }
    if (triangle_running(&a->tri) && a->tri.timer < next) next = a->tri.timer;
    if (a->noise.length && a->noise.timer < next) next = a->noise.timer;
    return next;
}

static void step_timers(NesApu* a, uint32_t cycles) {
    for (int i = 0; i < 2; ++i) {
        NesPulse* p = &a->pulse[i];
        if (!pulse_running(p)) continue;
        p->timer -= cycles;
        if (p->timer == 0) {
            p->timer = 2u * (p->period + 1u);
            p->seq = (uint8_t)((p->seq + 7) & 7);
        }
    }
    NesTriangle* t = &a->tri;
    if (triangle_running(t)) {
        t->timer -= cycles;
        if (t->timer == 0) {
            t->timer = t->period + 1u;
            t->seq = (uint8_t)((t->seq + 1) & 31);
        }
    }
    NesNoise* n = &a->noise;
    if (n->length) {
        n->timer -= cycles;
        if (n->timer == 0) {
            n->timer = kNoisePeriod[n->mode_period & 15];
            uint16_t bit = (uint16_t)((n->lfsr ^ (n->lfsr >> ((n->mode_period & 0x80) ? 6 : 1))) & 1);
            n->lfsr = (uint16_t)((n->lfsr >> 1) | (bit << 14));
        }
    }
}

void nes_apu_run(NesApu* a, uint32_t t) {
    while (a->time < t) {
        uint32_t step = t - a->time;
        uint32_t fc = fc_until_next(a);
        if (fc < step) step = fc;
        if (a->fc_reset_at >= 0 && (uint32_t)a->fc_reset_at - a->time < step) step = (uint32_t)a->fc_reset_at - a->time;
        if (!dmc_idle(&a->dmc) && a->dmc.timer < step) step = a->dmc.timer;
        if (a->out) {
            uint32_t timers = timers_until_next(a);
            if (timers < step) step = timers;
            step_timers(a, step);
        }
        dmc_advance(a, step);
        a->time += step;
        if (a->fc_reset_at >= 0 && (uint32_t)a->fc_reset_at == a->time) {
            a->fc_reset_at = -1;
            a->fc_cycle = 0;
            a->mode5 = a->fc_reset_mode5;
            if (a->mode5) {
                clock_quarter(a);
                clock_half(a);
            }
        } else {
            a->fc_cycle += step;
            fc_event(a);
        }
        if (a->out) update_output(a);
    }
}

void nes_apu_write(NesApu* a, uint32_t t, uint16_t addr, uint8_t v) {
    nes_apu_run(a, t);
    switch (addr) {
    case 0x4000:
    case 0x4004:
        a->pulse[(addr >> 2) & 1].ctrl = v;
        break;
    case 0x4001:
    case 0x4005: {
        NesPulse* p = &a->pulse[(addr >> 2) & 1];
        p->sweep = v;
        p->sweep_reload = 1;
        break;
    }
    case 0x4002:
    case 0x4006: {
        NesPulse* p = &a->pulse[(addr >> 2) & 1];
        p->period = (uint16_t)((p->period & 0x700) | v);
        break;
    }
    case 0x4003:
    case 0x4007: {
        int i = (addr >> 2) & 1;
        NesPulse* p = &a->pulse[i];
        p->period = (uint16_t)((p->period & 0xFF) | ((v & 7) << 8));
        if (a->enabled & (1 << i)) p->length = nes_length_table[v >> 3];
        p->seq = 0;
        p->env.start = 1;
        break;
    }
    case 0x4008:
        a->tri.ctrl = v;
        break;
    case 0x400A:
        a->tri.period = (uint16_t)((a->tri.period & 0x700) | v);
        break;
    case 0x400B:
        a->tri.period = (uint16_t)((a->tri.period & 0xFF) | ((v & 7) << 8));
        if (a->enabled & 4) a->tri.length = nes_length_table[v >> 3];
        a->tri.linear_reload = 1;
        break;
    case 0x400C:
        a->noise.ctrl = v;
        break;
    case 0x400E:
        a->noise.mode_period = v;
        break;
    case 0x400F:
        if (a->enabled & 8) a->noise.length = nes_length_table[v >> 3];
        a->noise.env.start = 1;
        break;
    case 0x4010:
        a->dmc.ctrl = v;
        if (!(v & 0x80)) a->dmc.irq = 0;
        break;
    case 0x4011:
        a->dmc.level = v & 0x7F;
        break;
    case 0x4012:
        a->dmc.start = v;
        break;
    case 0x4013:
        a->dmc.length = v;
        break;
    case 0x4015:
        a->enabled = v & 0x0F;
        if (!(v & 1)) a->pulse[0].length = 0;
        if (!(v & 2)) a->pulse[1].length = 0;
        if (!(v & 4)) a->tri.length = 0;
        if (!(v & 8)) a->noise.length = 0;
        a->dmc.irq = 0;
        if (!(v & 0x10)) {
            a->dmc.remaining = 0;
        } else if (!a->dmc.remaining) {
            dmc_restart(&a->dmc);
            dmc_fetch(a);
        }
        break;
    case 0x4017:
        a->irq_inhibit = (v & 0x40) != 0;
        if (a->irq_inhibit) a->frame_irq = 0;
        a->fc_reset_mode5 = (v & 0x80) != 0;
        /* 3 CPU cycles after a write on an APU cycle, 4 between APU cycles. */
        a->fc_reset_at = (int32_t)(t + (((a->abs_cycle + t) & 1) ? 4 : 3));
        break;
    default:
        break;
    }
    if (a->out) update_output(a);
}

uint8_t nes_apu_read_status(NesApu* a, uint32_t t) {
    nes_apu_run(a, t);
    uint8_t s = (uint8_t)((a->pulse[0].length ? 1 : 0) | (a->pulse[1].length ? 2 : 0) | (a->tri.length ? 4 : 0) |
                          (a->noise.length ? 8 : 0) | (a->dmc.remaining ? 0x10 : 0) | (a->frame_irq ? 0x40 : 0) |
                          (a->dmc.irq ? 0x80 : 0));
    a->frame_irq = 0;
    return s;
}

void nes_apu_end_frame(NesApu* a, uint32_t frame_cycles) {
    nes_apu_run(a, frame_cycles);
    a->time = 0;
    a->abs_cycle += frame_cycles;
    if (a->fc_reset_at >= 0) a->fc_reset_at -= (int32_t)frame_cycles;
}
