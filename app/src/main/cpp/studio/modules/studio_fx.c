/* Original Thor Tracker Studio DSP. SPDX-License-Identifier: GPL-3.0-or-later */
#include "studio_dsp.h"
#include <stdlib.h>

/* Angled stereo modulation and an orthogonal four-line feedback reverb. Only delayed
 * instances reserve history; valid counts make clearing constant-time without stale reads. */
static const float reverb_ms[4] = {31.7f, 43.1f, 57.7f, 67.1f};
static const StudioFxParams fx_defaults[] = {
    [STUDIO_FX_DELAY]   = {.delay   = {250.f, .35f, 5000.f, .35f}},
    [STUDIO_FX_DRIVE]   = {.drive   = {12.f, 5000.f, -6.f}},
    [STUDIO_FX_CRUSHER] = {.crusher = {8.f, 8000.f, 1.f, 0.f}},
    [STUDIO_FX_REVERB]  = {.reverb  = {.5f, 2.f, 4500.f, 18.f, .3f}},
    [STUDIO_FX_CHORUS]  = {.chorus  = {.65f, 3.f, .35f}},
};

typedef struct Ring { float *data; uint32_t capacity, write, valid; } Ring;
typedef struct Tap { float from, to, goal, blend; } Tap;
/* Internal control units: sample distances, coefficients and linear gains. */
typedef union Controls {
    struct { float feedback, tone, mix; } delay;
    struct { float gain, tone, trim; } drive;
    struct { float levels, inv_levels, step, mix, trim; } crusher;
    struct { float gain[4], damping, mix; } reverb;
    struct { float step, depth, mix; } chorus;
} Controls;

struct StudioFx {
    StudioFxKind kind;
    uint32_t rate;
    float inverse_rate, slew, bypass_step, tap_step;
    float enabled, enabled_goal;
    bool primed;
    Controls current, goal;
    union {
        struct { Ring history; Tap tap; float tone[2]; } delay;
        struct { float tone[2]; } drive;
        struct { float phase, held[2]; } crusher;
        struct {
            Ring predelay, lines[4];
            Tap pre_tap, size_tap;
            float lengths[4], damped[4];
        } reverb;
        struct { Ring history; float phase, base; } chorus;
    } state;
    float history[];
};

static float milliseconds_to_frames(float ms, uint32_t rate) {
    /* Multiply in double so integral sample distances remain integral (e.g. 2 s at 96 kHz). */
    return (float)((double)ms * (double)rate / 1000.);
}

static uint32_t ring_capacity(float ms, uint32_t rate) {
    return (uint32_t)milliseconds_to_frames(ms, rate) + 2u;
}

static inline void ring_clear(Ring *ring) { ring->write = ring->valid = 0; }

static inline uint32_t ring_back(const Ring *ring, uint32_t age) {
    return ring->write >= age ? ring->write - age : ring->capacity + ring->write - age;
}

/* Distances >=1. For a fraction across the valid-history boundary, only its old
 * endpoint should become zero. An invalid history cell is never read. */
static inline float ring_read(const Ring *ring, float distance, unsigned stride, unsigned channel) {
    uint32_t whole = (uint32_t)distance;
    if (whole > ring->valid) return 0.f;
    uint32_t index = ring_back(ring, whole);
    float a = ring->data[stride * index + channel];
    float fraction = distance - (float)whole;
    if (fraction == 0.f) return a;
    if (whole == ring->valid) return a * (1.f - fraction);
    uint32_t older = index ? index - 1 : ring->capacity - 1;
    return dsp_lerp(a, ring->data[stride * older + channel], fraction);
}

static inline float ring_stereo_tap(const Ring *ring, float distance, unsigned channel, float input) {
    if (distance >= 1.f) return ring_read(ring, distance, 2, channel);
    if (distance == 0.f) return input;
    return dsp_lerp(input, ring_read(ring, 1.f, 2, channel), distance);
}

static inline void ring_advance(Ring *ring) {
    if (++ring->write == ring->capacity) ring->write = 0;
    if (ring->valid < ring->capacity) ++ring->valid;
}

static inline void ring_write_mono(Ring *ring, float x) {
    ring->data[ring->write] = x;
    ring_advance(ring);
}

static inline void ring_write_stereo(Ring *ring, float left, float right) {
    ring->data[2 * ring->write] = left;
    ring->data[2 * ring->write + 1] = right;
    ring_advance(ring);
}

static inline void tap_clear(Tap *tap) {
    *tap = (Tap){tap->goal, tap->goal, tap->goal, 1.f};
}

/* New changes during a fade replace only the pending target, not either current
 * tap. Sweeping a single read head would turn time automation into pitch glides. */
static inline void tap_advance(Tap *tap, float step) {
    if (tap->blend == 1.f && tap->to != tap->goal) {
        tap->from = tap->to;
        tap->to = tap->goal;
        tap->blend = 0.f;
    } else {
        tap->blend = dsp_min(1.f, tap->blend + step);
    }
}

static inline float tap_stereo(const Ring *ring, const Tap *tap, unsigned channel, float x) {
    float to = ring_stereo_tap(ring, tap->to, channel, x);
    if (tap->blend == 1.f) return to;
    return dsp_lerp(ring_stereo_tap(ring, tap->from, channel, x), to, tap->blend);
}

StudioFx *studio_fx_create(StudioFxKind kind, uint32_t sample_rate) {
    if (!dsp_valid_rate(sample_rate)) return NULL;
    uint32_t stereo = 0, lines[4] = {0}, cells = 0;
    switch (kind) {
    case STUDIO_FX_DELAY:
        stereo = ring_capacity(STUDIO_DELAY_MAX_MS, sample_rate);
        break;
    case STUDIO_FX_REVERB:
        stereo = ring_capacity(STUDIO_REVERB_MAX_PREDELAY_MS, sample_rate);
        for (unsigned i = 0; i < 4; ++i) {
            lines[i] = ring_capacity(1.4f * reverb_ms[i], sample_rate);
            cells += lines[i];
        }
        break;
    case STUDIO_FX_CHORUS:
        stereo = ring_capacity(22.f, sample_rate);
        break;
    case STUDIO_FX_DRIVE:
    case STUDIO_FX_CRUSHER:
        break;
    default:
        return NULL;
    }
    StudioFx *fx = calloc(1, sizeof(*fx) + sizeof(float) * (cells + 2u * stereo));
    if (!fx) return NULL;
    fx->kind = kind;
    fx->rate = sample_rate;
    fx->inverse_rate = 1.f / (float)sample_rate;
    fx->slew = 1.f - expf(-fx->inverse_rate / .004f);
    fx->bypass_step = fx->inverse_rate / .005f;
    fx->tap_step = fx->inverse_rate / .015f;
    fx->enabled = fx->enabled_goal = 1.f;
    if (kind == STUDIO_FX_DELAY) {
        fx->state.delay.history = (Ring){.data = fx->history, .capacity = stereo};
    } else if (kind == STUDIO_FX_REVERB) {
        fx->state.reverb.predelay = (Ring){.data = fx->history, .capacity = stereo};
        float *data = fx->history + 2u * stereo;
        for (unsigned i = 0; i < 4; ++i) {
            fx->state.reverb.lines[i] = (Ring){.data = data, .capacity = lines[i]};
            fx->state.reverb.lengths[i] = milliseconds_to_frames(reverb_ms[i], sample_rate);
            data += lines[i];
        }
    } else if (kind == STUDIO_FX_CHORUS) {
        fx->state.chorus.history = (Ring){.data = fx->history, .capacity = stereo};
        fx->state.chorus.base = milliseconds_to_frames(12.f, sample_rate);
    }
    studio_fx_set(fx, &fx_defaults[kind]);
    studio_fx_clear(fx);
    return fx;
}

void studio_fx_destroy(StudioFx *fx) { free(fx); }

void studio_fx_set(StudioFx *fx, const StudioFxParams *p) {
    const StudioFxParams *d = &fx_defaults[fx->kind];
    Controls *c = &fx->goal;
    switch (fx->kind) {
    case STUDIO_FX_DELAY: {
        float ms = dsp_param(p->delay.time_ms, 1.f, STUDIO_DELAY_MAX_MS, d->delay.time_ms);
        fx->state.delay.tap.goal = milliseconds_to_frames(ms, fx->rate);
        c->delay.feedback = dsp_param(p->delay.feedback, 0.f, .95f, d->delay.feedback);
        c->delay.tone = dsp_lowpass_amount(dsp_param(p->delay.tone_hz, 30.f, 20000.f, d->delay.tone_hz), fx->inverse_rate);
        c->delay.mix = dsp_param(p->delay.mix, 0.f, 1.f, d->delay.mix);
        if (!fx->primed) tap_clear(&fx->state.delay.tap);
        break;
    }
    case STUDIO_FX_DRIVE:
        c->drive.gain = dsp_db_gain(dsp_param(p->drive.drive_db, 0.f, 36.f, d->drive.drive_db));
        c->drive.tone = dsp_lowpass_amount(dsp_param(p->drive.tone_hz, 30.f, 20000.f, d->drive.tone_hz), fx->inverse_rate);
        c->drive.trim = dsp_db_gain(dsp_param(p->drive.trim_db, -24.f, 6.f, d->drive.trim_db));
        break;
    case STUDIO_FX_CRUSHER: {
        unsigned bits = (unsigned)(.5f + dsp_param(p->crusher.bits, 2.f, 16.f, d->crusher.bits));
        c->crusher.levels = (float)(1u << (bits - 1));
        c->crusher.inv_levels = 1.f / c->crusher.levels;
        c->crusher.step = dsp_param(p->crusher.rate_hz, 100.f, (float)fx->rate, d->crusher.rate_hz) / (float)fx->rate;
        c->crusher.mix = dsp_param(p->crusher.mix, 0.f, 1.f, d->crusher.mix);
        c->crusher.trim = dsp_db_gain(dsp_param(p->crusher.trim_db, -24.f, 6.f, d->crusher.trim_db));
        break;
    }
    case STUDIO_FX_REVERB: {
        float size = dsp_lerp(.5f, 1.4f, dsp_param(p->reverb.size, 0.f, 1.f, d->reverb.size));
        float decay = dsp_param(p->reverb.decay_seconds, .2f, 8.f, d->reverb.decay_seconds);
        fx->state.reverb.size_tap.goal = size;
        float pre = dsp_param(p->reverb.predelay_ms, 0.f, STUDIO_REVERB_MAX_PREDELAY_MS, d->reverb.predelay_ms);
        fx->state.reverb.pre_tap.goal = milliseconds_to_frames(pre, fx->rate);
        for (unsigned i = 0; i < 4; ++i) {
            /* Attenuate each line to -60 dB over the requested RT60, even at short sizes. */
            c->reverb.gain[i] = expf(-6.907755279f * (.001f * reverb_ms[i]) * size / decay);
        }
        c->reverb.damping = dsp_lowpass_amount(dsp_param(p->reverb.damping_hz, 30.f, 20000.f, d->reverb.damping_hz), fx->inverse_rate);
        c->reverb.mix = dsp_param(p->reverb.mix, 0.f, 1.f, d->reverb.mix);
        if (!fx->primed) {
            tap_clear(&fx->state.reverb.pre_tap);
            tap_clear(&fx->state.reverb.size_tap);
        }
        break;
    }
    case STUDIO_FX_CHORUS:
        c->chorus.step = fx->inverse_rate * dsp_param(p->chorus.rate_hz, .05f, 8.f, d->chorus.rate_hz);
        c->chorus.depth = milliseconds_to_frames(dsp_param(p->chorus.depth_ms, 0.f, 10.f, d->chorus.depth_ms), fx->rate);
        c->chorus.mix = dsp_param(p->chorus.mix, 0.f, 1.f, d->chorus.mix);
        break;
    }
    if (!fx->primed) fx->current = fx->goal;
}

void studio_fx_set_bypass(StudioFx *fx, bool bypass) {
    fx->enabled_goal = bypass ? 0.f : 1.f;
    if (!fx->primed) fx->enabled = fx->enabled_goal;
}

void studio_fx_clear(StudioFx *fx) {
    fx->primed = false;
    fx->enabled = fx->enabled_goal;
    fx->current = fx->goal;
    switch (fx->kind) {
    case STUDIO_FX_DELAY:
        ring_clear(&fx->state.delay.history);
        tap_clear(&fx->state.delay.tap);
        fx->state.delay.tone[0] = fx->state.delay.tone[1] = 0.f;
        break;
    case STUDIO_FX_DRIVE:
        fx->state.drive.tone[0] = fx->state.drive.tone[1] = 0.f;
        break;
    case STUDIO_FX_CRUSHER:
        fx->state.crusher.phase = 1.f;
        fx->state.crusher.held[0] = fx->state.crusher.held[1] = 0.f;
        break;
    case STUDIO_FX_REVERB:
        ring_clear(&fx->state.reverb.predelay);
        tap_clear(&fx->state.reverb.pre_tap);
        tap_clear(&fx->state.reverb.size_tap);
        for (unsigned i = 0; i < 4; ++i) {
            ring_clear(&fx->state.reverb.lines[i]);
            fx->state.reverb.damped[i] = 0.f;
        }
        break;
    case STUDIO_FX_CHORUS:
        ring_clear(&fx->state.chorus.history);
        fx->state.chorus.phase = 0.f;
        break;
    }
}

#define SLEW(field) (fx->current.field = dsp_slew(fx->current.field, fx->goal.field, fx->slew))

static inline void delay_frame(StudioFx *fx, float *left, float *right) {
    float feedback = SLEW(delay.feedback), tone = SLEW(delay.tone), mix = SLEW(delay.mix);
    Ring *ring = &fx->state.delay.history;
    Tap *tap = &fx->state.delay.tap;
    tap_advance(tap, fx->tap_step);
    float a = tap_stereo(ring, tap, 0, *left), b = tap_stereo(ring, tap, 1, *right);
    float *lp = fx->state.delay.tone;
    lp[0] = dsp_filter(lp[0], a, tone);
    lp[1] = dsp_filter(lp[1], b, tone);
    ring_write_stereo(ring, dsp_clamp(*left + lp[0] * feedback, -4.f, 4.f),
                           dsp_clamp(*right + lp[1] * feedback, -4.f, 4.f));
    *left = dsp_lerp(*left, a, mix);
    *right = dsp_lerp(*right, b, mix);
}

/* Only use contiguous tap reads when both interpolation endpoints exist. Clear,
 * tap transitions and control/bypass slews keep the scalar path. The delay's
 * minimum distance is 1 ms (>=8 frames at every supported sample rate). */
static bool delay_settled(const StudioFx *fx) {
    const Tap *tap = &fx->state.delay.tap;
    return fx->enabled == 1.f && fx->enabled_goal == 1.f &&
           tap->blend == 1.f && tap->to == tap->goal &&
           fx->state.delay.history.valid > (uint32_t)tap->to &&
           fx->current.delay.feedback == fx->goal.delay.feedback &&
           fx->current.delay.tone == fx->goal.delay.tone &&
           fx->current.delay.mix == fx->goal.delay.mix;
}

static void delay_settled_block(StudioFx *fx, float *stereo, uint32_t frames) {
    Ring *ring = &fx->state.delay.history;
    const uint32_t capacity = ring->capacity;
    const float distance = fx->state.delay.tap.to;
    const uint32_t whole = (uint32_t)distance;
    const float fraction = distance - (float)whole;
    const float feedback = fx->current.delay.feedback, tone = fx->current.delay.tone, mix = fx->current.delay.mix;
    float lp[2] = {fx->state.delay.tone[0], fx->state.delay.tone[1]};
    uint32_t available = capacity - ring->valid;
    ring->valid += frames < available ? frames : available;
    while (frames) {
        uint32_t write = ring->write, read = ring_back(ring, whole);
        uint32_t older = fraction == 0.f ? read : (read ? read - 1 : capacity - 1);
        uint32_t run = frames;
        if (run > capacity - write) run = capacity - write;
        if (run > capacity - read) run = capacity - read;
        if (run > capacity - older) run = capacity - older;
        float *dst = ring->data + 2 * write;
        const float *tap = ring->data + 2 * read, *prev = ring->data + 2 * older;
        /* Sequential even when tap and write ranges overlap: short feedback delays
         * must read the samples written earlier in this same run, not a snapshot. */
        for (uint32_t i = 0; i < run; ++i, stereo += 2, tap += 2, prev += 2, dst += 2) {
            for (unsigned c = 0; c < 2; ++c) {
                float dry = dsp_audio(stereo[c], 4.f);
                float wet = fraction == 0.f ? tap[c] : dsp_lerp(tap[c], prev[c], fraction);
                lp[c] = dsp_filter(lp[c], wet, tone);
                dst[c] = dsp_clamp(dry + lp[c] * feedback, -4.f, 4.f);
                stereo[c] = dsp_clamp(dsp_lerp(dry, wet, mix), -4.f, 4.f);
            }
        }
        write += run;
        ring->write = write == capacity ? 0 : write;
        frames -= run;
    }
    fx->state.delay.tone[0] = lp[0];
    fx->state.delay.tone[1] = lp[1];
}

/* Monotonic rational saturator, exactly ±1 at ±3 with zero slope at the join. */
static inline float saturate(float x) {
    x = dsp_clamp(x, -3.f, 3.f);
    float xx = x * x;
    return x * (27.f + xx) / (27.f + 9.f * xx);
}

static inline void drive_frame(StudioFx *fx, float *left, float *right) {
    float gain = SLEW(drive.gain), tone = SLEW(drive.tone), trim = SLEW(drive.trim);
    float *lp = fx->state.drive.tone;
    lp[0] = dsp_filter(lp[0], saturate(gain * *left), tone);
    lp[1] = dsp_filter(lp[1], saturate(gain * *right), tone);
    *left = trim * lp[0];
    *right = trim * lp[1];
}

static inline float quantize(float x, float scale, float inverse_scale) {
    float steps = dsp_clamp(x, -1.f, 1.f) * scale;
    int nearest = (int)(steps + (steps < 0.f ? -.5f : .5f));
    return dsp_min((float)nearest, scale - 1.f) * inverse_scale;
}

static inline void crusher_frame(StudioFx *fx, float *left, float *right) {
    float step = SLEW(crusher.step), mix = SLEW(crusher.mix), trim = SLEW(crusher.trim);
    float *held = fx->state.crusher.held;
    float phase = fx->state.crusher.phase;
    if (phase >= 1.f) {
        phase -= 1.f;
        float levels = fx->goal.crusher.levels, inv_levels = fx->goal.crusher.inv_levels;
        held[0] = quantize(*left, levels, inv_levels);
        held[1] = quantize(*right, levels, inv_levels);
    }
    fx->state.crusher.phase = phase + step;
    /* At full wet, forward the held codes exactly; interpolation at t=1 can
     * otherwise introduce dry-input-dependent rounding between captures. */
    *left = trim * (mix == 1.f ? held[0] : dsp_lerp(*left, held[0], mix));
    *right = trim * (mix == 1.f ? held[1] : dsp_lerp(*right, held[1], mix));
}

static inline void reverb_frame(StudioFx *fx, float *left, float *right) {
    float damping = SLEW(reverb.damping), mix = SLEW(reverb.mix);
    Tap *pre = &fx->state.reverb.pre_tap, *size = &fx->state.reverb.size_tap;
    Ring *preline = &fx->state.reverb.predelay;
    tap_advance(pre, fx->tap_step);
    tap_advance(size, fx->tap_step);
    float in_l = tap_stereo(preline, pre, 0, *left), in_r = tap_stereo(preline, pre, 1, *right);
    ring_write_stereo(preline, *left, *right);
    float wet[4], feedback[4];
    for (unsigned i = 0; i < 4; ++i) {
        Ring *line = &fx->state.reverb.lines[i];
        float length = fx->state.reverb.lengths[i];
        float read = ring_read(line, length * size->to, 1, 0);
        if (size->blend != 1.f) {
            read = dsp_lerp(ring_read(line, length * size->from, 1, 0), read, size->blend);
        }
        wet[i] = read;
        feedback[i] = fx->state.reverb.damped[i] = dsp_filter(fx->state.reverb.damped[i], read, damping);
    }
    /* H4/2 preserves energy. Each line's loss and damping keep the tank below unity. */
    float a = feedback[0] + feedback[1], b = feedback[0] - feedback[1];
    float c = feedback[2] + feedback[3], d = feedback[2] - feedback[3];
    float scatter[4] = {a + c, b + d, a - c, b - d};
    float excite[4] = {in_l, in_r, in_l, in_r};
    for (unsigned i = 0; i < 4; ++i) {
        float gain = SLEW(reverb.gain[i]);
        ring_write_mono(&fx->state.reverb.lines[i],
                        dsp_clamp(.45f * excite[i] + .5f * gain * scatter[i], -2.f, 2.f));
    }
    float l = wet[0] + wet[2], r = wet[1] + wet[3];
    *left = dsp_lerp(*left, .65f * l + .27f * r, mix);
    *right = dsp_lerp(*right, .65f * r + .27f * l, mix);
}

static inline float chorus_lfo(float phase) {
    float tri = 1.f - 4.f * fabsf(phase - .5f);
    return tri * (1.5f - .5f * tri * tri);
}

static inline void chorus_frame(StudioFx *fx, float *left, float *right) {
    float step = SLEW(chorus.step), depth = SLEW(chorus.depth), mix = SLEW(chorus.mix);
    Ring *ring = &fx->state.chorus.history;
    float phase = fx->state.chorus.phase, right_phase = phase + .25f;
    if (right_phase >= 1.f) right_phase -= 1.f;
    float base = fx->state.chorus.base;
    float a = ring_read(ring, base + depth * chorus_lfo(phase), 2, 0);
    float b = ring_read(ring, base + depth * chorus_lfo(right_phase), 2, 1);
    ring_write_stereo(ring, *left, *right);
    phase += step;
    fx->state.chorus.phase = phase >= 1.f ? phase - 1.f : phase;
    *left = dsp_lerp(*left, a, mix);
    *right = dsp_lerp(*right, b, mix);
}

#undef SLEW

typedef void (*FrameKernel)(StudioFx *, float *, float *);

static inline void process_frame(StudioFx *fx, float *left, float *right, FrameKernel kernel) {
    float dry_l = dsp_audio(*left, 4.f), dry_r = dsp_audio(*right, 4.f);
    float l = dry_l, r = dry_r;
    kernel(fx, &l, &r);
    fx->enabled = dsp_approach(fx->enabled, fx->enabled_goal, fx->bypass_step);
    if (fx->enabled != 1.f) {
        l = dsp_lerp(dry_l, l, fx->enabled);
        r = dsp_lerp(dry_r, r, fx->enabled);
    }
    *left = dsp_clamp(l, -4.f, 4.f);
    *right = dsp_clamp(r, -4.f, 4.f);
}

void studio_fx_frame(StudioFx *fx, float *left, float *right) {
    fx->primed = true;
    switch (fx->kind) {
    case STUDIO_FX_DELAY:   process_frame(fx, left, right, delay_frame);   break;
    case STUDIO_FX_DRIVE:   process_frame(fx, left, right, drive_frame);   break;
    case STUDIO_FX_CRUSHER: process_frame(fx, left, right, crusher_frame); break;
    case STUDIO_FX_REVERB:  process_frame(fx, left, right, reverb_frame);  break;
    case STUDIO_FX_CHORUS:  process_frame(fx, left, right, chorus_frame);  break;
    }
}

static inline void process_block(StudioFx *fx, float *stereo, uint32_t frames, FrameKernel kernel) {
    for (uint32_t i = 0; i < frames; ++i, stereo += 2) process_frame(fx, stereo, stereo + 1, kernel);
}

void studio_fx_process(StudioFx *fx, float *stereo, uint32_t frames) {
    if (!frames) return;
    fx->primed = true;
    switch (fx->kind) {
    case STUDIO_FX_DELAY:
        if (delay_settled(fx)) delay_settled_block(fx, stereo, frames);
        else process_block(fx, stereo, frames, delay_frame);
        break;
    case STUDIO_FX_DRIVE:   process_block(fx, stereo, frames, drive_frame);   break;
    case STUDIO_FX_CRUSHER: process_block(fx, stereo, frames, crusher_frame); break;
    case STUDIO_FX_REVERB:  process_block(fx, stereo, frames, reverb_frame);  break;
    case STUDIO_FX_CHORUS:  process_block(fx, stereo, frames, chorus_frame);  break;
    }
}
