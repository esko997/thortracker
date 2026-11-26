/* Original Thor Tracker Studio DSP. SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef THORTRACKER_STUDIO_MONO_SYNTH_H_
#define THORTRACKER_STUDIO_MONO_SYNTH_H_

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum { MONO_SYNTH_SAW = 0, MONO_SYNTH_SQUARE = 1 };

typedef struct MonoSynthPatch {
    int waveform;            /* MONO_SYNTH_*; other values select saw */
    float cutoff_hz;         /* 30..16000 Hz, limited again by sample rate */
    float resonance;         /* 0..1; non-oscillating low-pass */
    float envelope_octaves;  /* 0..6 octaves of peak filter lift before accent */
    float decay_seconds;     /* 0.01..4 seconds, filter envelope time constant */
    float accent;            /* 0..1, sensitivity to per-note accent */
    float glide_seconds;     /* 0..2 seconds, legato pitch transition */
    float level;             /* 0..1 */
} MonoSynthPatch;

/* Fixed-size voice storage. Initialize before use; only the DSP functions write these members.
 * Calls for one voice must be serialized by the caller; distinct voices are independent. */
typedef struct MonoSynth {
    float inverse_sample_rate;
    float control_step, attack_step, release_step, retrigger_step;
    float cutoff_hz, cutoff_limit, damping, square_mix, level, envelope_ratio;
    float decay_pole, accent_depth;
    uint32_t glide_samples, glide_remaining;
    double phase;
    float phase_step, target_phase_step, glide_delta;
    float amplitude, envelope, velocity, accent;
    float cutoff_now, damping_now, square_mix_now, gain_now;
    float ic1, ic2;
    bool gate, retriggering;
} MonoSynth;

/* Idle; sample rate is clamped to 8000..192000 Hz (nonfinite: 48000 Hz).
 * Init patch: saw, 1300 Hz, .15 resonance, 2 octaves, .18 s decay, .5 accent, .04 s glide, .75 level. */
void mono_synth_init(MonoSynth* voice, float sample_rate);
/* Immediate deterministic silence (transport cut); retains the current patch and sample rate. */
void mono_synth_reset(MonoSynth* voice);
/* Apply a complete patch, clamping finite controls and substituting documented init defaults for nonfinite values.
 * Audible waveform, cutoff, resonance and gain changes are smoothed; decay changes the current envelope's rate. */
void mono_synth_apply_patch(MonoSynth* voice, const MonoSynthPatch* patch);
/* Hz is clamped to 1 Hz..0.45 × sample rate; a note-on with nonfinite Hz is ignored.
 * Velocity and accent are clamped to 0..1 (nonfinite: zero). Accent boosts volume and the filter envelope,
 * scaled by patch accent.
 * Legato only with an open gate: change pitch using patch glide, keep phase and both envelopes, and smooth
 * velocity/accent to the new note. A fresh note attacks immediately; a non-legato retrigger while audible
 * dips the VCA before restarting the attack. Ordinary note-ons do not glide. */
void mono_synth_note_on(MonoSynth* voice, float frequency_hz, float velocity, float accent, bool legato);
/* Begin click-safe release (about 8 ms from full level). */
void mono_synth_note_off(MonoSynth* voice);
/* Immediate pitch automation (no envelope restart, phase preserved); supersedes any legato glide. */
void mono_synth_set_frequency(MonoSynth* voice, float frequency_hz);
/* Advance one sample. Mono, finite and bounded within [-1, 1]; exact zero when idle. */
float mono_synth_render(MonoSynth* voice);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_STUDIO_MONO_SYNTH_H_ */
