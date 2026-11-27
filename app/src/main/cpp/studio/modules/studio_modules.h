/* Original Thor Tracker Studio DSP. SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef THORTRACKER_STUDIO_MODULES_H_
#define THORTRACKER_STUDIO_MODULES_H_

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define STUDIO_DSP_MIN_RATE 8000u
#define STUDIO_DSP_MAX_RATE 96000u
#define STUDIO_DELAY_MAX_MS 2000.f
#define STUDIO_REVERB_MAX_PREDELAY_MS 120.f
#define STUDIO_DSP_SINE_SIZE 1024u

/* One owner at a time per instance. Only create/destroy and table preparation belong off
 * the audio thread. Other calls allocate nothing, retain no config pointers and are safe at
 * render boundaries (not concurrently with rendering). IDs here are not project wire IDs. */
typedef enum StudioFxKind {
    STUDIO_FX_DELAY = 1, STUDIO_FX_DRIVE, STUDIO_FX_CRUSHER, STUDIO_FX_REVERB, STUDIO_FX_CHORUS
} StudioFxKind;

typedef union StudioFxParams {
    /* Resolve tempo/division to milliseconds before passing delay parameters. */
    struct { float time_ms, feedback, tone_hz, mix; } delay;
    struct { float drive_db, tone_hz, trim_db; } drive;
    struct { float bits, rate_hz, mix, trim_db; } crusher;
    struct { float size, decay_seconds, damping_hz, predelay_ms, mix; } reverb;
    struct { float rate_hz, depth_ms, mix; } chorus;
} StudioFxParams;

typedef struct StudioFx StudioFx;

/* NULL for invalid kind/rate or allocation failure. Up to 16 bytes of interpolation guard
 * plus 8 * ceil(rate * 2) bytes of delay history per delay instance. No heap for drive/crusher
 * history. A stereo effect may be moved/reordered by moving its pointer, never its contents. */
StudioFx *studio_fx_create(StudioFxKind kind, uint32_t sample_rate);
void studio_fx_destroy(StudioFx *fx);
/* Controls clamp to their listed ranges; nonfinite values use the default in parentheses.
 * Mix and size are linear in 0..1. Delay: time 1..2000 ms (250), feedback 0..0.95 (0.35),
 * tone 30..20000 Hz (5000), mix (0.35). Drive: gain 0..36 dB (12), tone (5000), trim
 * -24..6 dB (-6). Crusher: 2..16 bits (8, nearest integer), rate 100 Hz..render rate
 * (8000), mix (1), trim -24..6 dB (0). Reverb: size (0.5), decay 0.2..8 s (2), damping
 * 30..20000 Hz (4500), pre-delay 0..120 ms (18), mix (0.3). Chorus: rate 0.05..8 Hz
 * (0.65), depth 0..10 ms (3), mix (0.35). Filter frequencies additionally limit to Nyquist.
 * Before the first processed frame (also after clear) changes are immediate. The active union
 * member must match the instance's kind. */
void studio_fx_set(StudioFx *fx, const StudioFxParams *params);
/* A running effect fades to/from dry (its history continues while bypassed). */
void studio_fx_set_bypass(StudioFx *fx, bool bypass);
/* O(1): drop all history/state, retain the most recent controls and bypass destination. */
void studio_fx_clear(StudioFx *fx);
/* Stereo frame, in place; left/right are distinct. Finite nominal [-1,1] input. Drive and
 * crusher trim may exceed unity; general effect output is bounded to [-4,4]. */
void studio_fx_frame(StudioFx *fx, float *left, float *right);
void studio_fx_process(StudioFx *fx, float *interleaved_stereo, uint32_t frames);

/* Prepare once, share immutably between voices; the table must outlive them. */
typedef struct StudioDspTables { float sine[STUDIO_DSP_SINE_SIZE + 1]; } StudioDspTables;
void studio_dsp_prepare_tables(StudioDspTables *tables);

#define STUDIO_WAVETABLE_SLOTS 32u
#define STUDIO_WAVETABLE_WAVES 4u
#define STUDIO_WAVETABLE_POINTS 64u
typedef struct StudioWavetable StudioWavetable;
typedef struct StudioWavetableSet {
    const StudioWavetable *slot[STUDIO_WAVETABLE_SLOTS];
} StudioWavetableSet;

/* Immutable output, independent of the input points/basis lifetime. NULL on failure.
 * Prepare/destroy off the audio thread. Points are four consecutive signed 64-point cycles. */
StudioWavetable *studio_wavetable_prepare(const StudioDspTables *basis, const int8_t points[256]);
void studio_wavetable_destroy(StudioWavetable *table);

/* Borrowed host-endian interleaved binary32 PCM, normalized to [-1,1], 1 or 2 channels,
 * source rate 8..192 kHz. Sample data must outlive all voices/short retrigger fades
 * referencing it; resetting the lane synchronously releases every reference. */
typedef struct StudioPcm {
    const float *samples;
    uint32_t frames, sample_rate, channels;
} StudioPcm;

typedef struct StudioSamplerPatch {
    StudioPcm pcm;
    /* Absolute source frames. Attack region [start,end) already intersects the selected slice.
     * Optional [loop_start,loop_end) must lie inside it; equal disables looping. */
    uint32_t start, end, loop_start, loop_end;
    bool reverse;
    float root_hz, fine_cents; /* 1..20000 Hz (440), -1200..1200 cents (0) */
    float attack_seconds, release_seconds; /* 0..2 (0.002), 0..4 (0.08) */
    float cutoff_hz, level; /* 30..20000 Hz (20000), 0..1 (0.8) */
} StudioSamplerPatch;

typedef struct StudioFmPatch {
    float carrier_ratio, modulator_ratio; /* 0.25..16 (1,2) */
    float index, feedback; /* phase-modulation index 0..12 radians (2), 0..1 (0) */
    float attack_seconds, decay_seconds, sustain, release_seconds; /* 0..2 (0.005), 0.01..8 (0.45), 0..1 (0.5), 0..4 (0.12) */
    float level; /* 0..1 (0.75) */
} StudioFmPatch;

typedef enum StudioDrumKind { STUDIO_DRUM_KICK, STUDIO_DRUM_SNARE, STUDIO_DRUM_HAT } StudioDrumKind;

typedef struct StudioDrumPatch {
    StudioDrumKind kind; /* trigger only */
    float body, snap, tone; /* 0..1 (0.65,0.45,0.5) */
    float decay_seconds, level; /* 0.01..4 (0.35), 0..1 (0.8) */
} StudioDrumPatch;

typedef struct StudioWavetablePatch {
    float morph, scan_hz, scan_depth; /* fraction 0..1 (0), Hz 0..8 (0), fraction 0..1 (0) */
    float attack_seconds, release_seconds, level; /* 0..2 (.005), 0..4 (.12), 0..1 (.70) */
} StudioWavetablePatch;

typedef struct StudioPluckPatch {
    float inharmonicity, strike, damping; /* 0..1 (.15, .45, .45); strike is trigger-only */
    float decay_seconds, release_seconds, level; /* .04..8 (.75) RT60, 0..4 (.10), 0..1 (.75) */
} StudioPluckPatch;

typedef struct StudioVoice StudioVoice;

StudioVoice *studio_voice_create(uint32_t sample_rate, const StudioDspTables *tables);
void studio_voice_destroy(StudioVoice *voice);
void studio_voice_clear(StudioVoice *voice);
/* Frequency in Hz, including project tuning and transpose; velocity/accent optionally folded
 * together by the caller into 0..1 velocity. Sampler returns false without changing the
 * current voice for invalid regions, PCM shape or frequency. Invalid FM/drum triggers are
 * ignored. A new attack replaces the current voice with at most one 4 ms retiring voice. */
bool studio_voice_start_sampler(StudioVoice *voice, const StudioSamplerPatch *patch, float hz, float velocity);
void studio_voice_start_fm(StudioVoice *voice, const StudioFmPatch *patch, float hz, float velocity, bool legato);
void studio_voice_start_drum(StudioVoice *voice, const StudioDrumPatch *patch, float hz, float velocity);
/* Table is borrowed; slot is the zero-based originating patch slot. Legato preserves a
 * matching, open current note only. Pluck's caller must also require the same patch slot. */
bool studio_voice_start_wavetable(StudioVoice *voice, const StudioWavetablePatch *patch,
    const StudioWavetable *table, uint8_t slot, float hz, float velocity, bool legato);
bool studio_voice_start_pluck(StudioVoice *voice, const StudioPluckPatch *patch,
    float hz, float velocity, bool legato);
/* Live updates affect the matching active voice only, never a retired overlap. Sampler
 * asset/region/reverse and root/fine are trigger-time; drive those via the next attack.
 * Drum kind is trigger-time; FM legato preserves oscillator and envelope state. */
void studio_voice_update_sampler(StudioVoice *voice, const StudioSamplerPatch *patch);
void studio_voice_update_fm(StudioVoice *voice, const StudioFmPatch *patch);
void studio_voice_update_drum(StudioVoice *voice, const StudioDrumPatch *patch);
void studio_voice_update_wavetable(StudioVoice *voice, const StudioWavetablePatch *patch);
void studio_voice_update_pluck(StudioVoice *voice, const StudioPluckPatch *patch);
/* Resolve both note slots before retiring the old bank. The set itself is never retained;
 * NULL is an empty set. Keep replacement tables alive until rebind/clear/destroy. */
void studio_voice_rebind_wavetables(StudioVoice *voice, const StudioWavetableSet *next);
/* Update held pitch without an envelope restart. Release with the voice envelope; cut fades
 * out over at most 4 ms and is independent of patch release. Clear gives immediate silence. */
void studio_voice_pitch(StudioVoice *voice, float hz);
void studio_voice_release(StudioVoice *voice);
void studio_voice_cut(StudioVoice *voice);
bool studio_voice_active(const StudioVoice *voice);
/* Overwrite one stereo frame; finite and bounded to [-1,1], exact silence when idle. */
void studio_voice_frame(StudioVoice *voice, float *left, float *right);

#ifdef __cplusplus
}
#endif
#endif /* THORTRACKER_STUDIO_MODULES_H_ */
