/* POKEY sound section (pokey_chip.h): one per-cycle step, run only in cycles where something happens. */
#include "pokey_chip.h"

#include <math.h>
#include <string.h>

enum { CLK_NONE, CLK_FAST, CLK_BASE };

static uint64_t base_period(const PokeyChip* c) { return (c->audctl & PK_AUDCTL_BASE15) ? 114u : 28u; }

static uint64_t base_first(const PokeyChip* c) {
    return c->exit + ((c->audctl & PK_AUDCTL_BASE15) ? PK_BASE15_FIRST : PK_BASE64_FIRST);
}

/* Both prescalers run from the end of initialization mode; AUDCTL bit 0 picks the one every base-clocked
 * counter counts, so switching it never shifts either clock's phase. */
static int pulse_at(const PokeyChip* c, uint64_t t) {
    if (c->init) return 0;
    const uint64_t first = base_first(c);
    return t >= first && (t - first) % base_period(c) == 0;
}

/* The first base pulse after cycle t. */
static uint64_t next_pulse(const PokeyChip* c, uint64_t t) {
    if (c->init) return PK_NEVER;
    const uint64_t first = base_first(c), period = base_period(c);
    if (t < first) return first;
    return first + ((t - first) / period + 1) * period;
}

/* Base pulses in cycles (a, b], a <= b. */
static uint64_t pulses_between(const PokeyChip* c, uint64_t a, uint64_t b) {
    if (c->init) return 0;
    const uint64_t first = base_first(c), period = base_period(c);
    if (b < first) return 0;
    const uint64_t hi = (b - first) / period + 1;
    const uint64_t lo = a < first ? 0 : (a - first) / period + 1;
    return hi - lo;
}

/* What counter `ch` counts: the machine clock, base pulses, or (linked high counters) the low counter's fires. */
static int clock_of(const PokeyChip* c, int ch) {
    switch (ch) {
    case 0: return (c->audctl & PK_AUDCTL_CH1_FAST) ? CLK_FAST : CLK_BASE;
    case 1: return (c->audctl & PK_AUDCTL_JOIN12) ? CLK_NONE : CLK_BASE;
    case 2: return (c->audctl & PK_AUDCTL_CH3_FAST) ? CLK_FAST : CLK_BASE;
    default: return (c->audctl & PK_AUDCTL_JOIN34) ? CLK_NONE : CLK_BASE;
    }
}

int pk_poly_bit(const PokeyChip* c, int which, int ch, uint64_t t) {
    /* In initialization mode (and before a channel's delayed copy has left it) the audio circuits see the
     * 5-bit generator as a constant 1 and the others as constant 0 (Altirra manual 5.5). */
    if (c->init || t < c->exit + (uint64_t)ch + 1) return which == 5;
    const uint64_t k = t - c->exit - (uint64_t)ch - 1; /* POKEY channel ch + 1 sees the bit ch + 1 cycles late */
    switch (which) {
    case 4: return c->poly4[k % PK_POLY4_LENGTH];
    case 5: return c->poly5[k % PK_POLY5_LENGTH];
    case 9: return c->poly9[k % PK_POLY9_LENGTH];
    default: {
        const uint64_t i = k % PK_POLY17_LENGTH;
        return (c->poly17[i >> 3] >> (i & 7)) & 1;
    }
    }
}

/* AUDF + 1 counts left. A pending fire stays pending (only STIMER and two-tone resyncs drop it). */
static void reload(PokeyChip* c, int ch) { c->u[ch] = (uint16_t)(c->audf[ch] + 1); }

/* One count. On underflow the counter wraps (linked low counters keep counting from there) and fires
 * PK_FIRE_DELAY cycles later unless a fire is already pending. */
static void count(PokeyChip* c, int ch, uint64_t t) {
    if (--c->u[ch]) return;
    c->u[ch] = 256;
    if (c->fire_at[ch] == PK_NEVER) c->fire_at[ch] = t + PK_FIRE_DELAY;
}

static void schedule_resync(PokeyChip* c, uint64_t t) {
    int i = 0;
    while (i < c->resyncs && c->resync[i] < t) ++i;
    if (i < c->resyncs && c->resync[i] == t) return; /* both timers firing together resync once */
    if (c->resyncs == 4) return;                       /* cannot happen: resyncs pre-empt the fires behind them */
    memmove(&c->resync[i + 1], &c->resync[i], (size_t)(c->resyncs - i) * sizeof c->resync[0]);
    c->resync[i] = t;
    ++c->resyncs;
}

/* The audio circuit of channel `ch` is clocked: unless the 5-bit generator masks the pulse, a pure tone toggles
 * and noise samples its generator. */
static void process(PokeyChip* c, int ch, uint64_t t) {
    const uint8_t audc = c->audc[ch];
    if (!(audc & PK_AUDC_NO_POLY5) && !pk_poly_bit(c, 5, ch, t)) return;
    if (audc & PK_AUDC_PURE) c->out[ch] ^= 1;
    else if (audc & PK_AUDC_POLY4) c->out[ch] = (uint8_t)pk_poly_bit(c, 4, ch, t);
    else c->out[ch] = (uint8_t)pk_poly_bit(c, (c->audctl & PK_AUDCTL_POLY9) ? 9 : 17, ch, t);
}

static void irq(PokeyChip* c, uint8_t bit) {
    if (c->irqen & bit) {
        c->irq |= bit;
        c->irq_new = 1;
    }
}

static void fire(PokeyChip* c, int ch, uint64_t t) {
    const uint8_t ctl = c->audctl;
    c->fire_at[ch] = PK_NEVER;
    switch (ch) {
    case 2:
        if (ctl & PK_AUDCTL_JOIN34) count(c, 3, t);
        else reload(c, 2);
        process(c, 2, t);
        c->hp[0] = (ctl & PK_AUDCTL_HP13) ? c->out[0] : 1;
        break;
    case 3:
        if (ctl & PK_AUDCTL_JOIN34) reload(c, 2);
        reload(c, 3);
        process(c, 3, t);
        c->hp[1] = (ctl & PK_AUDCTL_HP24) ? c->out[1] : 1;
        irq(c, PK_IRQ_TIMER4);
        break;
    case 0:
        if (ctl & PK_AUDCTL_JOIN12) {
            count(c, 1, t);
        } else {
            reload(c, 0);
            /* Two-tone: timer 1 resyncs only while the serial output bit is 1 (the idle line; force break
             * makes it 0). */
            if ((c->skctl & PK_SKCTL_TWO_TONE) && !(c->skctl & PK_SKCTL_BREAK)) schedule_resync(c, t + PK_RESYNC_DELAY);
        }
        process(c, 0, t);
        irq(c, PK_IRQ_TIMER1);
        break;
    default:
        if (ctl & PK_AUDCTL_JOIN12) reload(c, 0);
        reload(c, 1);
        process(c, 1, t);
        if (c->skctl & PK_SKCTL_TWO_TONE) schedule_resync(c, t + PK_RESYNC_DELAY);
        irq(c, PK_IRQ_TIMER2);
        break;
    }
}

static uint16_t levels_of(const PokeyChip* c) {
    uint16_t levels = 0;
    for (int ch = 0; ch < 4; ++ch) {
        const uint8_t audc = c->audc[ch];
        /* Volume only overrides after the high-pass XOR, so volume-only channels always add. */
        const int on = (audc & PK_AUDC_VOLUME_ONLY) || (ch < 2 ? c->out[ch] ^ c->hp[ch] : c->out[ch]);
        if (on) levels |= (uint16_t)((audc & 15) << (4 * ch));
    }
    return levels;
}

static void report(PokeyChip* c, uint64_t t) {
    const uint16_t levels = levels_of(c);
    if (levels == c->levels) return;
    c->levels = levels;
    if (c->output) c->output(c->ctx, t, levels);
}

/* Everything of cycle t, in MAME's order: counting, then fires of channels 3 and 4, two-tone resyncs, fires of
 * channels 1 and 2 (a resync pre-empts a fire of the same cycle, as Altirra measures). */
static void step(PokeyChip* c, uint64_t t) {
    c->now = t;
    const int pulse = pulse_at(c, t);
    for (int ch = 0; ch < 4; ++ch) {
        const int k = clock_of(c, ch);
        if (k == CLK_FAST || (k == CLK_BASE && pulse)) count(c, ch, t);
    }
    if (c->fire_at[2] == t) fire(c, 2, t);
    if (c->fire_at[3] == t) fire(c, 3, t);
    while (c->resyncs && c->resync[0] == t) {
        --c->resyncs;
        memmove(&c->resync[0], &c->resync[1], (size_t)c->resyncs * sizeof c->resync[0]);
        reload(c, 0);
        reload(c, 1);
        c->fire_at[0] = c->fire_at[1] = PK_NEVER; /* pending fires are pre-empted */
    }
    if (c->fire_at[0] == t) fire(c, 0, t);
    if (c->fire_at[1] == t) fire(c, 1, t);
    report(c, t);
}

/* The cycle counter `ch` next underflows in, if nothing is written before. */
static uint64_t underflow_cycle(const PokeyChip* c, int ch) {
    switch (clock_of(c, ch)) {
    case CLK_FAST: return c->now + c->u[ch];
    case CLK_BASE: {
        const uint64_t p = next_pulse(c, c->now);
        return p == PK_NEVER ? PK_NEVER : p + (uint64_t)(c->u[ch] - 1) * base_period(c);
    }
    default: return PK_NEVER;
    }
}

static uint64_t next_event(const PokeyChip* c) {
    uint64_t t = c->resyncs ? c->resync[0] : PK_NEVER;
    for (int ch = 0; ch < 4; ++ch) {
        if (c->fire_at[ch] < t) t = c->fire_at[ch];
        const uint64_t u = underflow_cycle(c, ch);
        if (u < t) t = u;
    }
    return t;
}

/* Counts through cycles (now, t] in which no counter underflows and nothing fires. */
static void skip_to(PokeyChip* c, uint64_t t) {
    if (t <= c->now) return;
    const uint64_t pulses = pulses_between(c, c->now, t);
    for (int ch = 0; ch < 4; ++ch) {
        const int k = clock_of(c, ch);
        if (k == CLK_FAST) c->u[ch] = (uint16_t)(c->u[ch] - (t - c->now));
        else if (k == CLK_BASE) c->u[ch] = (uint16_t)(c->u[ch] - pulses);
    }
    c->now = t;
}

int pk_run(PokeyChip* c, uint64_t until) {
    while (c->now < until) {
        const uint64_t t = next_event(c);
        if (t > until) {
            skip_to(c, until);
            return 0;
        }
        skip_to(c, t - 1);
        c->irq_new = 0;
        step(c, t);
        if (c->irq_new) return 1;
    }
    return 0;
}

int pk_run_reference(PokeyChip* c, uint64_t until) {
    while (c->now < until) {
        c->irq_new = 0;
        step(c, c->now + 1);
        if (c->irq_new) return 1;
    }
    return 0;
}

uint8_t pk_take_irq(PokeyChip* c) {
    const uint8_t irq = c->irq;
    c->irq = 0;
    return irq;
}

void pk_write(PokeyChip* c, int reg, uint8_t value) {
    switch (reg & 15) {
    case PK_AUDF1: case PK_AUDF2: case PK_AUDF3: case PK_AUDF4:
        c->audf[(reg & 15) >> 1] = value; /* takes effect at the counter's next reload */
        break;
    case PK_AUDC1: case PK_AUDC2: case PK_AUDC3: case PK_AUDC4:
        c->audc[(reg & 15) >> 1] = value;
        break;
    case PK_AUDCTL:
        c->audctl = value;
        if (!(value & PK_AUDCTL_HP13)) c->hp[0] = 1; /* a disabled high-pass flip-flop is forced to 1 */
        if (!(value & PK_AUDCTL_HP24)) c->hp[1] = 1;
        break;
    case PK_STIMER:
        for (int ch = 0; ch < 4; ++ch) {
            reload(c, ch);
            c->fire_at[ch] = PK_NEVER;
            c->out[ch] = 1;
        }
        c->hp[0] = c->hp[1] = 1;
        c->resyncs = 0;
        break;
    case PK_IRQEN:
        c->irqen = value;
        c->irq &= value; /* a cleared enable bit also clears that interrupt's status */
        break;
    case PK_SKCTL: {
        const int init = !(value & PK_SKCTL_INIT_MASK);
        if (init) {
            c->init = 1; /* generators and base clocks reset and held */
        } else if (c->init) {
            c->init = 0;
            c->exit = c->now;
        }
        c->skctl = value;
        break;
    }
    default:
        break; /* SEROUT, POTGO, SKREST and the read-only side: no sound */
    }
    report(c, c->now);
}

void pk_reset(PokeyChip* c) {
    memset(c->audf, 0, sizeof c->audf);
    memset(c->audc, 0, sizeof c->audc);
    c->audctl = c->skctl = c->irqen = 0;
    c->now = 0;
    c->init = 1;
    c->exit = 0;
    for (int ch = 0; ch < 4; ++ch) {
        c->u[ch] = 1;
        c->fire_at[ch] = PK_NEVER;
        c->out[ch] = 0;
    }
    c->hp[0] = c->hp[1] = 1;
    c->resyncs = 0;
    c->levels = 0;
    c->irq = 0;
    c->irq_new = 0;
}

/* Altirra's generators (pokeytables.cpp): XNOR shift registers from the all-zero reset state, tapped at the far
 * end; entry k = the bit the audio circuits see k shifts after initialization mode ends. The 17-bit generator is
 * the 9-bit one with eight more stages, tapped after bit 8. */
void pk_init(PokeyChip* c, PkOutputFn output, void* ctx) {
    uint32_t s = 0;
    for (int k = 0; k < PK_POLY4_LENGTH; ++k) {
        c->poly4[k] = (uint8_t)(s & 1);
        s = (s >> 1) + (~((s << 2) ^ (s << 3)) & 8);
    }
    s = 0;
    for (int k = 0; k < PK_POLY5_LENGTH; ++k) {
        c->poly5[k] = (uint8_t)(s & 1);
        s = (s >> 1) + (~((s << 2) ^ (s << 4)) & 16);
    }
    s = 0;
    for (int k = 0; k < PK_POLY9_LENGTH; ++k) {
        c->poly9[k] = (uint8_t)(s & 1);
        s = (s >> 1) + (~((s << 8) ^ (s << 3)) & 0x100);
    }
    s = 0;
    memset(c->poly17, 0, sizeof c->poly17);
    for (int k = 0; k < PK_POLY17_LENGTH; ++k) {
        c->poly17[k >> 3] |= (uint8_t)(((s >> 8) & 1) << (k & 7));
        s = (s >> 1) + (~((s << 16) ^ (s << 11)) & 0x10000);
    }
    c->output = output;
    c->ctx = ctx;
    pk_reset(c);
}

void pk_mix_init(float table[PK_MIX_ENTRIES]) {
    /* Altirra 4.40 pokey.cpp, UpdateMixTable (non-linear mixing): the measured voltage drop of each volume bit,
     * normalised so four channels at 15 sum to 1, through a linear-then-exponential saturation fitted to the
     * output pin. Index k + 5 j + 25 i: k channels on with volume bit 0, j with bit 1, i = bit 2 + 2 x bit 3. */
    static const float bits[4] = {0.12f / 8.24f, 0.26f / 8.24f, 0.56f / 8.24f, 1.12f / 8.24f};
    const float threshold = 0.14f, slope = 2.1f, curve = 2.85f;
    float* d = table;
    float v23 = 0;
    for (int i = 0; i < 13; ++i) {
        float v123 = v23;
        for (int j = 0; j < 5; ++j) {
            float x = v123;
            for (int k = 0; k < 5; ++k) {
                const float y = x < threshold ? x : threshold + (1.0f - expf(-curve * (x - threshold))) / curve;
                *d++ = y * -slope;
                x += bits[0];
            }
            v123 += bits[1];
        }
        v23 += bits[2];
    }
}

int pk_mix_index(uint16_t levels) {
    static const uint8_t lookup[16] = {0, 1, 5, 6, 25, 26, 30, 31, 50, 51, 55, 56, 75, 76, 80, 81};
    return lookup[levels & 15] + lookup[(levels >> 4) & 15] + lookup[(levels >> 8) & 15] + lookup[levels >> 12];
}
