/* Original Thor Tracker Studio DSP. SPDX-License-Identifier: GPL-3.0-or-later */
#include "mono_synth.h"

#include <math.h>

static const MonoSynthPatch init_patch = {
    .waveform = MONO_SYNTH_SAW,
    .cutoff_hz = 1300.0f,
    .resonance = 0.15f,
    .envelope_octaves = 2.0f,
    .decay_seconds = 0.18f,
    .accent = 0.5f,
    .glide_seconds = 0.04f,
    .level = 0.75f,
};

static float clamp(float value, float low, float high) {
    return value < low ? low : value > high ? high : value;
}

static float control(float value, float low, float high, float fallback) {
    return isfinite(value) ? clamp(value, low, high) : fallback;
}

static float limit_cutoff(const MonoSynth* voice, float hz) {
    return hz < voice->cutoff_limit ? hz : voice->cutoff_limit;
}

static void snap_controls(MonoSynth* voice) {
    voice->cutoff_now = limit_cutoff(voice, voice->cutoff_hz);
    voice->damping_now = voice->damping;
    voice->square_mix_now = voice->square_mix;
}

void mono_synth_init(MonoSynth* voice, float sample_rate) {
    const float rate = control(sample_rate, 8000.0f, 192000.0f, 48000.0f);
    const float inverse = 1.0f / rate;
    *voice = (MonoSynth){
        .inverse_sample_rate = inverse,
        .control_step = -expm1f(-inverse / 0.003f),
        .attack_step = inverse / 0.003f,
        .release_step = inverse / 0.008f,
        .retrigger_step = inverse / 0.001f,
        .cutoff_limit = 0.40f * rate,
    };
    mono_synth_apply_patch(voice, &init_patch);
}

void mono_synth_reset(MonoSynth* voice) {
    voice->phase = 0.0;
    voice->phase_step = voice->target_phase_step = voice->glide_delta = 0.0f;
    voice->glide_remaining = 0;
    voice->amplitude = voice->envelope = voice->velocity = voice->accent = voice->gain_now = 0.0f;
    voice->ic1 = voice->ic2 = 0.0f;
    voice->gate = voice->retriggering = false;
    snap_controls(voice);
}

void mono_synth_apply_patch(MonoSynth* voice, const MonoSynthPatch* patch) {
    voice->square_mix = patch->waveform == MONO_SYNTH_SQUARE ? 1.0f : 0.0f;
    voice->cutoff_hz = control(patch->cutoff_hz, 30.0f, 16000.0f, init_patch.cutoff_hz);
    const float resonance = control(patch->resonance, 0.0f, 1.0f, init_patch.resonance);
    voice->damping = 2.0f - 1.7f * resonance; /* 1/Q; never self-oscillates. */
    voice->envelope_ratio = exp2f(control(patch->envelope_octaves, 0.0f, 6.0f, init_patch.envelope_octaves));
    const float decay = control(patch->decay_seconds, 0.01f, 4.0f, init_patch.decay_seconds);
    voice->decay_pole = expf(-voice->inverse_sample_rate / decay);
    voice->accent_depth = control(patch->accent, 0.0f, 1.0f, init_patch.accent);
    const float glide = control(patch->glide_seconds, 0.0f, 2.0f, init_patch.glide_seconds);
    voice->glide_samples = (uint32_t)(glide / voice->inverse_sample_rate + 0.5f);
    voice->level = control(patch->level, 0.0f, 1.0f, init_patch.level);
    if (!voice->gate && voice->amplitude == 0.0f) snap_controls(voice);
}

static float pitch_step(const MonoSynth* voice, float frequency_hz) {
    return clamp(frequency_hz * voice->inverse_sample_rate, voice->inverse_sample_rate, 0.45f);
}

static void set_pitch(MonoSynth* voice, float step) {
    voice->phase_step = voice->target_phase_step = step;
    voice->glide_remaining = 0;
}

static float note_gain(const MonoSynth* voice, float emphasis) {
    return voice->velocity * voice->level * (1.0f + 0.5f * emphasis);
}

void mono_synth_note_on(MonoSynth* voice, float frequency_hz, float velocity, float accent, bool legato) {
    if (!isfinite(frequency_hz)) return;
    const float step = pitch_step(voice, frequency_hz);
    const bool tied = legato && voice->gate;
    voice->gate = true;
    voice->velocity = control(velocity, 0.0f, 1.0f, 0.0f);
    voice->accent = control(accent, 0.0f, 1.0f, 0.0f);

    if (tied) {
        if (voice->target_phase_step == step) return;
        if (voice->glide_samples != 0 && voice->phase_step != step) {
            voice->target_phase_step = step;
            voice->glide_remaining = voice->glide_samples;
            voice->glide_delta = (voice->phase_step - step) / (float)voice->glide_remaining;
        } else {
            set_pitch(voice, step);
        }
        return;
    }

    set_pitch(voice, step);
    voice->retriggering = voice->amplitude > 0.0f;
    if (!voice->retriggering) {
        voice->envelope = 1.0f;
        voice->gain_now = note_gain(voice, voice->accent * voice->accent_depth);
    }
}

void mono_synth_note_off(MonoSynth* voice) {
    if (voice->amplitude == 0.0f) {
        /* Also cancel a note-on that has not yet rendered its first sample. */
        mono_synth_reset(voice);
    } else {
        voice->gate = voice->retriggering = false;
    }
}

void mono_synth_set_frequency(MonoSynth* voice, float frequency_hz) {
    if (isfinite(frequency_hz)) set_pitch(voice, pitch_step(voice, frequency_hz));
}

static float smooth(float current, float target, float step) {
    const float delta = target - current;
    const float next = current + step * delta;
    return fabsf(delta) < 1e-5f || next == current ? target : next;
}

/* Quadratic BLEP on the samples immediately before and after a unit-phase wrap. */
static float blep(float phase, float step) {
    if (phase < step) {
        const float edge = 1.0f - phase / step;
        return -edge * edge;
    }
    if (phase > 1.0f - step) {
        const float edge = 1.0f + (phase - 1.0f) / step;
        return edge * edge;
    }
    return 0.0f;
}

static float oscillator(MonoSynth* voice) {
    if (voice->glide_remaining != 0) {
        /* Interpolate from the endpoint rather than accumulating rounding error. */
        --voice->glide_remaining;
        voice->phase_step = voice->target_phase_step + voice->glide_delta * (float)voice->glide_remaining;
    }
    const float phase = (float)voice->phase;
    const float step = voice->phase_step;
    const float wrap = blep(phase, step);
    voice->phase += step;
    if (voice->phase >= 1.0) voice->phase -= 1.0;

    const float mix = voice->square_mix_now;
    if (mix == 0.0f) return 2.0f * phase - 1.0f - wrap;
    const bool first_half = phase < 0.5f;
    const float half_wrap = blep(first_half ? phase + 0.5f : phase - 0.5f, step);
    const float square = (first_half ? 1.0f : -1.0f) + wrap - half_wrap;
    if (mix == 1.0f) return square;
    const float saw = 2.0f * phase - 1.0f - wrap;
    return saw + mix * (square - saw);
}

static float lowpass(MonoSynth* voice, float input) {
    /* Two trapezoid integrators solve band = ic1 + g*(input - damping*band - low), low = ic2 + g*band.
     * Use a short continued fraction for g = tan(pi*fc/rate) (fc <= 0.4*rate). Keep its numerator/denominator
     * separate so mapping fc and solving the filter together need only one division. */
    const float x = (3.14159265358979323846f * voice->inverse_sample_rate) * voice->cutoff_now;
    const float x2 = x * x;
    const float n = x * (105.0f - 10.0f * x2);
    const float d = 105.0f - x2 * (45.0f - x2);
    const float band_over_d = (d * voice->ic1 + n * (input - voice->ic2)) /
                              (d * d + n * (n + voice->damping_now * d));
    const float band = d * band_over_d;
    const float low = voice->ic2 + n * band_over_d;
    voice->ic1 = 2.0f * band - voice->ic1;
    voice->ic2 = 2.0f * low - voice->ic2;
    return low;
}

float mono_synth_render(MonoSynth* voice) {
    if (!voice->gate) {
        if (voice->amplitude == 0.0f) return 0.0f;
        voice->amplitude -= voice->release_step;
        if (voice->amplitude <= 0.0f) {
            mono_synth_reset(voice);
            return 0.0f;
        }
    } else if (voice->retriggering) {
        voice->amplitude -= voice->retrigger_step;
        if (voice->amplitude <= 0.0f) {
            voice->amplitude = 0.0f;
            voice->envelope = 1.0f;
            voice->retriggering = false;
        }
    } else if (voice->amplitude < 1.0f) {
        voice->amplitude = clamp(voice->amplitude + voice->attack_step, 0.0f, 1.0f);
    }

    const float emphasis = voice->accent * voice->accent_depth;
    /* Envelope 1 reaches the patch's octave ratio, times up to two for accent; envelope 0 is base cutoff. */
    const float cutoff = limit_cutoff(voice, voice->cutoff_hz *
        (1.0f + voice->envelope * (voice->envelope_ratio * (1.0f + emphasis) - 1.0f)));
    const float slew = voice->control_step;
    voice->cutoff_now = smooth(voice->cutoff_now, cutoff, slew);
    voice->damping_now = smooth(voice->damping_now, voice->damping, slew);
    voice->square_mix_now = smooth(voice->square_mix_now, voice->square_mix, slew);
    voice->gain_now = smooth(voice->gain_now, note_gain(voice, emphasis), slew);
    if (voice->envelope > 1e-5f) voice->envelope *= voice->decay_pole;
    else voice->envelope = 0.0f;

    const float input = oscillator(voice);
    const float output = 0.65f * voice->amplitude * voice->gain_now * lowpass(voice, input);
    return output / (1.0f + fabsf(output));
}
