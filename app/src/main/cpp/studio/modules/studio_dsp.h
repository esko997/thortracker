/* Original Thor Tracker Studio DSP. SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef THORTRACKER_STUDIO_DSP_H_
#define THORTRACKER_STUDIO_DSP_H_

#include "studio_modules.h"
#include <math.h>

#define DSP_TAU 6.28318530717958647692f

static inline float dsp_min(float a, float b) { return a < b ? a : b; }
static inline float dsp_max(float a, float b) { return a > b ? a : b; }
static inline float dsp_clamp(float x, float lo, float hi) { return dsp_min(dsp_max(lo, x), hi); }
static inline float dsp_unit(float x) { return dsp_clamp(x, 0.f, 1.f); }
static inline float dsp_param(float x, float lo, float hi, float fallback) {
    return isfinite(x) ? dsp_clamp(x, lo, hi) : fallback;
}
static inline float dsp_audio(float x, float bound) {
    return isfinite(x) ? dsp_clamp(x, -bound, bound) : 0.f;
}
static inline bool dsp_valid_rate(uint32_t rate) {
    return rate >= STUDIO_DSP_MIN_RATE && rate <= STUDIO_DSP_MAX_RATE;
}
static inline float dsp_lerp(float a, float b, float t) { return a + (b - a) * t; }
static inline float dsp_approach(float x, float target, float step) {
    return x < target ? dsp_min(x + step, target) : dsp_max(x - step, target);
}
static inline float dsp_zap_tiny(float x) { return fabsf(x) < 1e-15f ? 0.f : x; }
static inline float dsp_slew(float x, float target, float amount) {
    float next = dsp_lerp(x, target, amount);
    return next == x ? target : next;
}
/* Coefficients are computed on parameter events, never in the sample loop. */
static inline float dsp_lowpass_amount(float hz, float inverse_rate) {
    return 1.f - expf(-DSP_TAU * dsp_min(hz * inverse_rate, .49f));
}
static inline float dsp_filter(float state, float input, float amount) {
    return dsp_zap_tiny(dsp_lerp(state, input, amount));
}
static inline float dsp_db_gain(float db) { return exp2f(db / 6.020599913f); }

#endif
