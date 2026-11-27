/* Original Thor Tracker Studio DSP. SPDX-License-Identifier: GPL-3.0-or-later */
#include "studio_dsp.h"
#include <stdlib.h>

enum VoiceKind { NOTE_IDLE, NOTE_SAMPLE, NOTE_FM, NOTE_DRUM, NOTE_WAVETABLE, NOTE_PLUCK, NOTE_RESIDUAL };
enum EnvelopeStage { ATTACK, HELD, RELEASE };

enum { WAVE_BANDS = 6, WAVE_PERIOD = 256 };
struct StudioWavetable {
    float bands[WAVE_BANDS][STUDIO_WAVETABLE_WAVES][WAVE_PERIOD + 1];
};

typedef struct WaveNote {
    const StudioWavetable *table;
    uint8_t slot;
    bool correcting;
    uint32_t correction_remaining;
    float correction;
    float phase, step, goal_step;
    float morph, goal_morph, scan_phase, scan_step, scan_depth, goal_scan_step, goal_scan_depth;
} WaveNote;

enum { PLUCK_MODES = 3 };
typedef struct PluckNote {
    float inharmonicity, damping, rt60, floor;
    float phase[PLUCK_MODES], step[PLUCK_MODES], goal_step[PLUCK_MODES];
    float amplitude[PLUCK_MODES], pole[PLUCK_MODES];
} PluckNote;

typedef struct SampleNote {
    const float *pcm;
    uint32_t start, end, loop_start, loop_end, channels;
    bool reverse, reverse_wrapped;
    float step, goal_step;
    double pitch_ratio, position;
    float cutoff, goal_cutoff, filter[2];
} SampleNote;

typedef struct FmNote {
    float phase[2], step[2], goal_step[2], ratio[2];
    float index, goal_index, index_cycles, feedback, goal_feedback, feedback_cycles;
    float mod_history;
    float decay_amount, sustain, body, mod_envelope;
} FmNote;

typedef struct DrumNote {
    StudioDrumKind kind;
    uint32_t rng;
    float phase[3], step[3], goal_step[3];
    float body, goal_body, snap, goal_snap, tone, goal_tone;
    float pitch_envelope, click, natural, decay_pole;
    float lp_low, lp_high, tone_amount, goal_tone_amount;
} DrumNote;

typedef struct Note {
    enum VoiceKind kind;
    enum EnvelopeStage stage;
    float hz, velocity, goal_velocity, level, goal_level;
    float env, attack_step, release_seconds, release_step;
    float last_l, last_r;
    union { SampleNote sample; FmNote fm; DrumNote drum; WaveNote wave; PluckNote pluck; } synth;
} Note;

struct StudioVoice {
    const StudioDspTables *tables;
    uint32_t rate;
    float inverse_rate, slew, inverse_fade, drum_pitch_pole, drum_click_pole, drum_lowpass;
    uint32_t fade_frames, fade_remaining;
    unsigned front;
    float residual_l, residual_r;
    Note notes[2];
};

void studio_dsp_prepare_tables(StudioDspTables *tables) {
    for (unsigned i = 0; i < STUDIO_DSP_SINE_SIZE; ++i) {
        tables->sine[i] = sinf(DSP_TAU * ((float)i / STUDIO_DSP_SINE_SIZE));
    }
    tables->sine[STUDIO_DSP_SINE_SIZE] = tables->sine[0];
}

StudioWavetable *studio_wavetable_prepare(const StudioDspTables *basis, const int8_t points[256]) {
    if (!basis || !points) return NULL;
    StudioWavetable *table = malloc(sizeof(*table));
    if (!table) return NULL;
    enum { SINE_MASK = STUDIO_DSP_SINE_SIZE - 1, QUARTER = STUDIO_DSP_SINE_SIZE / 4,
           SOURCE_STEP = STUDIO_DSP_SINE_SIZE / STUDIO_WAVETABLE_POINTS,
           TABLE_STEP = STUDIO_DSP_SINE_SIZE / WAVE_PERIOD };
    const float *sine = basis->sine;
    float peak = 1.f;
    for (unsigned w = 0; w < STUDIO_WAVETABLE_WAVES; ++w) {
        const int8_t *wave = points + w * STUDIO_WAVETABLE_POINTS;
        int sum = 0;
        for (unsigned i = 0; i < STUDIO_WAVETABLE_POINTS; ++i) sum += wave[i];
        float ac[STUDIO_WAVETABLE_POINTS];
        for (unsigned i = 0; i < STUDIO_WAVETABLE_POINTS; ++i) {
            ac[i] = ((int)wave[i] * (int)STUDIO_WAVETABLE_POINTS - sum)
                    * (1.f / (128u * STUDIO_WAVETABLE_POINTS));
        }
        float cycle[WAVE_PERIOD] = {0};
        for (unsigned k = 1; k <= STUDIO_WAVETABLE_POINTS / 2; ++k) {
            float c = 0.f, s = 0.f;
            if (k == STUDIO_WAVETABLE_POINTS / 2) {
                /* Nyquist's positive and negative bins coincide at the authored grid. */
                for (unsigned i = 0; i < STUDIO_WAVETABLE_POINTS; i += 2) c += ac[i] - ac[i + 1];
                c *= 1.f / STUDIO_WAVETABLE_POINTS;
            } else {
                for (unsigned i = 0; i < STUDIO_WAVETABLE_POINTS; ++i) {
                    unsigned phase = i * k * SOURCE_STEP;
                    c += ac[i] * sine[(phase + QUARTER) & SINE_MASK];
                    s += ac[i] * sine[phase & SINE_MASK];
                }
                c *= 2.f / STUDIO_WAVETABLE_POINTS;
                s *= 2.f / STUDIO_WAVETABLE_POINTS;
            }
            for (unsigned i = 0; i < WAVE_PERIOD; ++i) {
                unsigned phase = i * k * TABLE_STEP;
                cycle[i] += c * sine[(phase + QUARTER) & SINE_MASK] + s * sine[phase & SINE_MASK];
            }
            if ((k & (k - 1u)) == 0) {
                unsigned band = 0;
                for (unsigned n = k; n > 1; n >>= 1) ++band;
                float *dest = table->bands[band][w];
                for (unsigned i = 0; i < WAVE_PERIOD; ++i) {
                    dest[i] = cycle[i];
                    peak = dsp_max(peak, fabsf(cycle[i]));
                }
                dest[WAVE_PERIOD] = dest[0];
            }
        }
    }
    if (peak > 1.f) {
        float scale = 1.f / peak;
        for (unsigned b = 0; b < WAVE_BANDS; ++b) {
            for (unsigned w = 0; w < STUDIO_WAVETABLE_WAVES; ++w) {
                for (unsigned i = 0; i <= WAVE_PERIOD; ++i) table->bands[b][w][i] *= scale;
            }
        }
    }
    return table;
}

void studio_wavetable_destroy(StudioWavetable *table) { free(table); }

static inline float sinusoid(const StudioDspTables *tables, float cycles) {
    float u = cycles - (float)(int)cycles;
    if (u < 0.f) u += 1.f;
    /* Negative sub-epsilon phases can round to exactly one. */
    if (u >= 1.f) return tables->sine[0];
    u *= STUDIO_DSP_SINE_SIZE;
    unsigned i = (unsigned)u;
    return dsp_lerp(tables->sine[i], tables->sine[i + 1], u - (float)i);
}

static inline float advance_phase(float phase, float step) {
    phase += step;
    return phase >= 1.f ? phase - 1.f : phase;
}

StudioVoice *studio_voice_create(uint32_t sample_rate, const StudioDspTables *tables) {
    if (!dsp_valid_rate(sample_rate) || !tables) return NULL;
    StudioVoice *voice = calloc(1, sizeof(*voice));
    if (!voice) return NULL;
    voice->tables = tables;
    voice->rate = sample_rate;
    voice->inverse_rate = 1.f / (float)sample_rate;
    voice->slew = 1.f - expf(-voice->inverse_rate / .004f);
    voice->fade_frames = (sample_rate * 4u + 999u) / 1000u;
    voice->inverse_fade = 1.f / (float)voice->fade_frames;
    voice->drum_pitch_pole = expf(-voice->inverse_rate / .018f);
    voice->drum_click_pole = expf(-voice->inverse_rate / .009f);
    voice->drum_lowpass = dsp_lowpass_amount(320.f, voice->inverse_rate);
    return voice;
}

void studio_voice_destroy(StudioVoice *voice) { free(voice); }

void studio_voice_clear(StudioVoice *voice) {
    voice->notes[0] = (Note){0};
    voice->notes[1] = (Note){0};
    voice->front = voice->fade_remaining = 0;
    voice->residual_l = voice->residual_r = 0.f;
}

static inline Note *current(StudioVoice *voice) { return &voice->notes[voice->front]; }

/* Keep at most one outgoing waveform. At a retrigger during the overlap its last
 * contribution (not its PCM/oscillator) joins the new outgoing ramp. */
static void retire(StudioVoice *voice) {
    if (current(voice)->kind == NOTE_IDLE) return;
    Note *old = &voice->notes[voice->front ^ 1u];
    float fade = (float)voice->fade_remaining * voice->inverse_fade;
    voice->residual_l = (old->last_l + voice->residual_l) * fade;
    voice->residual_r = (old->last_r + voice->residual_r) * fade;
    voice->front ^= 1u;
    voice->fade_remaining = voice->fade_frames;
}

static Note *begin(StudioVoice *voice, enum VoiceKind kind, float hz, float velocity) {
    retire(voice);
    Note *note = current(voice);
    float volume = dsp_param(velocity, 0.f, 1.f, 0.f);
    *note = (Note){.kind = kind, .stage = ATTACK, .hz = hz, .velocity = volume, .goal_velocity = volume};
    return note;
}

static inline bool valid_frequency(float hz) { return isfinite(hz) && hz > 0.f; }

static float attack_step(const StudioVoice *voice, float seconds, float fallback) {
    /* A minimum 1 ms de-click ramp, even at an instantaneous nominal attack. */
    return voice->inverse_rate / dsp_max(.001f, dsp_param(seconds, 0.f, 2.f, fallback));
}

static void set_release(const StudioVoice *voice, Note *note, float seconds) {
    if (note->release_seconds == seconds) return;
    note->release_seconds = seconds;
    if (note->stage == RELEASE) note->release_step = note->env * voice->inverse_rate / seconds;
}

static inline float bounded_hz(float hz) { return dsp_clamp(hz, 1.f, 20000.f); }

static void set_sample_pitch(Note *note) {
    SampleNote *s = &note->synth.sample;
    s->goal_step = dsp_clamp((float)(s->pitch_ratio * note->hz), 1e-5f, 256.f);
}

static void set_sample_controls(const StudioVoice *voice, Note *note, const StudioSamplerPatch *patch) {
    note->attack_step = attack_step(voice, patch->attack_seconds, .002f);
    set_release(voice, note, dsp_max(.004f, dsp_param(patch->release_seconds, 0.f, 4.f, .08f)));
    note->goal_level = dsp_param(patch->level, 0.f, 1.f, .8f);
    float cutoff = dsp_param(patch->cutoff_hz, 30.f, 20000.f, 20000.f);
    note->synth.sample.goal_cutoff = cutoff == 20000.f ? 1.f : dsp_lowpass_amount(cutoff, voice->inverse_rate);
}

bool studio_voice_start_sampler(StudioVoice *voice, const StudioSamplerPatch *patch, float hz, float velocity) {
    const StudioPcm *pcm = &patch->pcm;
    bool loop = patch->loop_start != patch->loop_end;
    if (!valid_frequency(hz) || !pcm->samples || (pcm->channels != 1 && pcm->channels != 2) ||
        pcm->sample_rate < 8000u || pcm->sample_rate > 192000u ||
        (uint64_t)pcm->frames * pcm->channels > SIZE_MAX / sizeof(float) ||
        patch->start >= patch->end || patch->end > pcm->frames ||
        (loop && (patch->loop_start < patch->start || patch->loop_end > patch->end ||
                  patch->loop_start >= patch->loop_end))) return false;
    Note *note = begin(voice, NOTE_SAMPLE, bounded_hz(hz), velocity);
    SampleNote *s = &note->synth.sample;
    *s = (SampleNote){.pcm = pcm->samples, .start = patch->start, .end = patch->end,
                      .loop_start = loop ? patch->loop_start : 0, .loop_end = loop ? patch->loop_end : 0,
                      .channels = pcm->channels, .reverse = patch->reverse,
                      .position = (double)(patch->reverse ? patch->end - 1u : patch->start)};
    float root = dsp_param(patch->root_hz, 1.f, 20000.f, 440.f);
    float fine = dsp_param(patch->fine_cents, -1200.f, 1200.f, 0.f);
    s->pitch_ratio = (double)pcm->sample_rate / ((double)root * voice->rate) * exp2f(fine / 1200.f);
    set_sample_pitch(note);
    set_sample_controls(voice, note, patch);
    s->step = s->goal_step;
    s->cutoff = s->goal_cutoff;
    note->level = note->goal_level;
    return true;
}

void studio_voice_update_sampler(StudioVoice *voice, const StudioSamplerPatch *patch) {
    Note *note = current(voice);
    if (note->kind == NOTE_SAMPLE) set_sample_controls(voice, note, patch);
}

static void set_fm_pitch(const StudioVoice *voice, Note *note) {
    FmNote *fm = &note->synth.fm;
    float pitch = note->hz * voice->inverse_rate;
    fm->goal_step[0] = dsp_min(.45f, pitch * fm->ratio[0]);
    fm->goal_step[1] = dsp_min(.45f, pitch * fm->ratio[1]);
    /* Reduce phase modulation as upper sidebands approach Nyquist, instead of
     * turning high notes/ratios into a field of foldback tones. */
    float index_limit = dsp_max(0.f, (.48f - fm->goal_step[0]) / fm->goal_step[1] - 1.f);
    fm->goal_index = dsp_min(fm->index_cycles, index_limit / DSP_TAU);
    fm->goal_feedback = fm->feedback_cycles * dsp_unit((.48f / fm->goal_step[1] - 1.f) / 3.f);
}

static void set_fm_controls(const StudioVoice *voice, Note *note, const StudioFmPatch *patch) {
    FmNote *fm = &note->synth.fm;
    note->attack_step = attack_step(voice, patch->attack_seconds, .005f);
    set_release(voice, note, dsp_max(.004f, dsp_param(patch->release_seconds, 0.f, 4.f, .12f)));
    note->goal_level = dsp_param(patch->level, 0.f, 1.f, .75f);
    fm->ratio[0] = dsp_param(patch->carrier_ratio, .25f, 16.f, 1.f);
    fm->ratio[1] = dsp_param(patch->modulator_ratio, .25f, 16.f, 2.f);
    fm->index_cycles = dsp_param(patch->index, 0.f, 12.f, 2.f) / DSP_TAU;
    fm->feedback_cycles = .22f * dsp_param(patch->feedback, 0.f, 1.f, 0.f);
    fm->sustain = dsp_param(patch->sustain, 0.f, 1.f, .5f);
    float decay = dsp_param(patch->decay_seconds, .01f, 8.f, .45f);
    fm->decay_amount = 1.f - expf(-6.907755279f * voice->inverse_rate / decay);
    set_fm_pitch(voice, note);
}

void studio_voice_start_fm(StudioVoice *voice, const StudioFmPatch *patch, float hz, float velocity, bool legato) {
    if (!valid_frequency(hz)) return;
    hz = bounded_hz(hz);
    Note *note = current(voice);
    if (legato && note->kind == NOTE_FM && note->stage != RELEASE) {
        note->hz = hz;
        note->goal_velocity = dsp_param(velocity, 0.f, 1.f, 0.f);
        set_fm_controls(voice, note, patch);
        return;
    }
    note = begin(voice, NOTE_FM, hz, velocity);
    FmNote *fm = &note->synth.fm;
    *fm = (FmNote){.body = 1.f, .mod_envelope = 1.f};
    set_fm_controls(voice, note, patch);
    fm->step[0] = fm->goal_step[0];
    fm->step[1] = fm->goal_step[1];
    fm->index = fm->goal_index;
    fm->feedback = fm->goal_feedback;
    note->level = note->goal_level;
}

void studio_voice_update_fm(StudioVoice *voice, const StudioFmPatch *patch) {
    Note *note = current(voice);
    if (note->kind == NOTE_FM) set_fm_controls(voice, note, patch);
}

static void set_drum_pitch(const StudioVoice *voice, Note *note) {
    DrumNote *d = &note->synth.drum;
    float pitch = note->hz * voice->inverse_rate;
    if (d->kind == STUDIO_DRUM_HAT) {
        float rate = dsp_min(pitch, .44f / 6.91f);
        d->goal_step[0] = rate * 2.76f;
        d->goal_step[1] = rate * 4.33f;
        d->goal_step[2] = rate * 6.91f;
    } else {
        d->goal_step[0] = dsp_min(pitch, .44f);
        d->goal_step[1] = dsp_min(pitch * 1.57f, .44f);
    }
}

static void set_drum_controls(const StudioVoice *voice, Note *note, const StudioDrumPatch *patch) {
    DrumNote *d = &note->synth.drum;
    d->goal_body = dsp_param(patch->body, 0.f, 1.f, .65f);
    d->goal_snap = dsp_param(patch->snap, 0.f, 1.f, .45f);
    d->goal_tone = dsp_param(patch->tone, 0.f, 1.f, .5f);
    d->goal_tone_amount = dsp_lowpass_amount(600.f + 10000.f * d->goal_tone * d->goal_tone, voice->inverse_rate);
    note->goal_level = dsp_param(patch->level, 0.f, 1.f, .8f);
    float decay = dsp_param(patch->decay_seconds, .01f, 4.f, .35f);
    d->decay_pole = expf(-9.210340372f * voice->inverse_rate / decay);
}

void studio_voice_start_drum(StudioVoice *voice, const StudioDrumPatch *patch, float hz, float velocity) {
    if (!valid_frequency(hz) || (unsigned)patch->kind > STUDIO_DRUM_HAT) return;
    Note *note = begin(voice, NOTE_DRUM, bounded_hz(hz), velocity);
    note->attack_step = voice->inverse_rate / .001f;
    note->release_seconds = .008f;
    DrumNote *d = &note->synth.drum;
    *d = (DrumNote){.kind = patch->kind, .pitch_envelope = 1.f, .click = 1.f, .natural = 1.f,
                    .rng = UINT32_C(0x93c5074d) + (unsigned)patch->kind * UINT32_C(0x1ab3314f)};
    set_drum_controls(voice, note, patch);
    set_drum_pitch(voice, note);
    for (unsigned i = 0; i < 3; ++i) d->step[i] = d->goal_step[i];
    d->body = d->goal_body;
    d->snap = d->goal_snap;
    d->tone = d->goal_tone;
    d->tone_amount = d->goal_tone_amount;
    note->level = note->goal_level;
}

void studio_voice_update_drum(StudioVoice *voice, const StudioDrumPatch *patch) {
    Note *note = current(voice);
    if (note->kind == NOTE_DRUM) set_drum_controls(voice, note, patch);
}

static void set_wave_pitch(const StudioVoice *voice, Note *note) {
    note->synth.wave.goal_step = dsp_min(.45f, note->hz * voice->inverse_rate);
}

static void set_wave_controls(const StudioVoice *voice, Note *note, const StudioWavetablePatch *patch) {
    WaveNote *w = &note->synth.wave;
    note->attack_step = attack_step(voice, patch->attack_seconds, .005f);
    set_release(voice, note, dsp_max(.004f, dsp_param(patch->release_seconds, 0.f, 4.f, .12f)));
    note->goal_level = dsp_param(patch->level, 0.f, 1.f, .70f);
    w->goal_morph = dsp_param(patch->morph, 0.f, 1.f, 0.f);
    w->goal_scan_step = dsp_param(patch->scan_hz, 0.f, 8.f, 0.f) * voice->inverse_rate;
    w->goal_scan_depth = w->goal_scan_step > 0.f ? dsp_param(patch->scan_depth, 0.f, 1.f, 0.f) : 0.f;
}

bool studio_voice_start_wavetable(StudioVoice *voice, const StudioWavetablePatch *patch,
    const StudioWavetable *table, uint8_t slot, float hz, float velocity, bool legato) {
    if (!table || slot >= STUDIO_WAVETABLE_SLOTS || !valid_frequency(hz)) return false;
    hz = bounded_hz(hz);
    Note *note = current(voice);
    WaveNote *w = &note->synth.wave;
    if (legato && note->kind == NOTE_WAVETABLE && note->stage != RELEASE &&
        w->slot == slot && w->table == table) {
        note->hz = hz;
        note->goal_velocity = dsp_param(velocity, 0.f, 1.f, 0.f);
        set_wave_pitch(voice, note);
        set_wave_controls(voice, note, patch);
        return true;
    }
    note = begin(voice, NOTE_WAVETABLE, hz, velocity);
    w = &note->synth.wave;
    *w = (WaveNote){.table = table, .slot = slot, .scan_phase = .25f};
    set_wave_pitch(voice, note);
    set_wave_controls(voice, note, patch);
    w->step = w->goal_step;
    w->morph = w->goal_morph;
    w->scan_step = w->goal_scan_step;
    w->scan_depth = w->goal_scan_depth;
    note->level = note->goal_level;
    return true;
}

void studio_voice_update_wavetable(StudioVoice *voice, const StudioWavetablePatch *patch) {
    Note *note = current(voice);
    if (note->kind == NOTE_WAVETABLE) set_wave_controls(voice, note, patch);
}

static void wave_residual(Note *note) {
    note->synth.wave.table = NULL;
    note->kind = NOTE_RESIDUAL;
}

void studio_voice_rebind_wavetables(StudioVoice *voice, const StudioWavetableSet *next) {
    Note *old = &voice->notes[voice->front ^ 1u];
    if (old->kind == NOTE_WAVETABLE) {
        const WaveNote *w = &old->synth.wave;
        if (!next || next->slot[w->slot] != w->table) wave_residual(old);
    }
    Note *note = current(voice);
    if (note->kind != NOTE_WAVETABLE) return;
    WaveNote *w = &note->synth.wave;
    const StudioWavetable *table = next ? next->slot[w->slot] : NULL;
    if (table == w->table) return;
    if (!table) {
        retire(voice);
        wave_residual(note);
        *current(voice) = (Note){0};
    } else {
        w->table = table;
        w->correcting = true;
    }
}

static void set_pluck_pitch(const StudioVoice *voice, Note *note) {
    PluckNote *p = &note->synth.pluck;
    float root = dsp_min(.45f, note->hz * voice->inverse_rate);
    p->goal_step[0] = root;
    /* Keep unwrapped steps for the upper-mode taper, clamp only when advancing phase. */
    p->goal_step[1] = root * (2.f + .76f * p->inharmonicity);
    p->goal_step[2] = root * (3.f + 2.37f * p->inharmonicity);
}

static void set_pluck_controls(const StudioVoice *voice, Note *note, const StudioPluckPatch *patch) {
    PluckNote *p = &note->synth.pluck;
    p->inharmonicity = dsp_param(patch->inharmonicity, 0.f, 1.f, .15f);
    note->goal_level = dsp_param(patch->level, 0.f, 1.f, .75f);
    set_release(voice, note, dsp_max(.004f, dsp_param(patch->release_seconds, 0.f, 4.f, .10f)));
    float rt60 = dsp_param(patch->decay_seconds, .04f, 8.f, .75f);
    float damping = dsp_param(patch->damping, 0.f, 1.f, .45f);
    if (rt60 != p->rt60 || damping != p->damping) {
        p->rt60 = rt60;
        p->damping = damping;
        float exponent = -6.907755279f * voice->inverse_rate / rt60;
        /* Update the decay rate, not the energy already in the resonator. */
        p->pole[0] = expf(exponent);
        p->pole[1] = expf(exponent / dsp_lerp(.9f, .25f, damping));
        p->pole[2] = expf(exponent / dsp_lerp(.75f, .12f, damping));
    }
    set_pluck_pitch(voice, note);
}

bool studio_voice_start_pluck(StudioVoice *voice, const StudioPluckPatch *patch,
    float hz, float velocity, bool legato) {
    if (!valid_frequency(hz)) return false;
    hz = bounded_hz(hz);
    Note *note = current(voice);
    if (legato && note->kind == NOTE_PLUCK && note->stage != RELEASE) {
        note->hz = hz;
        note->goal_velocity = dsp_param(velocity, 0.f, 1.f, 0.f);
        set_pluck_controls(voice, note, patch);
        return true;
    }
    note = begin(voice, NOTE_PLUCK, hz, velocity);
    note->attack_step = voice->inverse_rate / .001f;
    set_pluck_controls(voice, note, patch);
    note->level = note->goal_level;
    PluckNote *p = &note->synth.pluck;
    float strike = dsp_param(patch->strike, 0.f, 1.f, .45f);
    float upper1 = .12f + .66f * strike, upper2 = .06f + .68f * strike;
    float root = .9f / (1.f + upper1 + upper2);
    p->amplitude[0] = root;
    p->amplitude[1] = root * upper1;
    p->amplitude[2] = root * upper2;
    p->floor = root * .0001f;
    for (unsigned i = 0; i < PLUCK_MODES; ++i) p->step[i] = p->goal_step[i];
    return true;
}

void studio_voice_update_pluck(StudioVoice *voice, const StudioPluckPatch *patch) {
    Note *note = current(voice);
    if (note->kind == NOTE_PLUCK) set_pluck_controls(voice, note, patch);
}

void studio_voice_pitch(StudioVoice *voice, float hz) {
    if (!valid_frequency(hz)) return;
    Note *note = current(voice);
    note->hz = bounded_hz(hz);
    switch (note->kind) {
    case NOTE_IDLE: break;
    case NOTE_SAMPLE: set_sample_pitch(note); break;
    case NOTE_FM: set_fm_pitch(voice, note); break;
    case NOTE_DRUM: set_drum_pitch(voice, note); break;
    case NOTE_WAVETABLE: set_wave_pitch(voice, note); break;
    case NOTE_PLUCK: set_pluck_pitch(voice, note); break;
    case NOTE_RESIDUAL: break; /* only an outgoing slot can contain a residual */
    }
}

void studio_voice_release(StudioVoice *voice) {
    Note *note = current(voice);
    if (note->kind == NOTE_IDLE || note->stage == RELEASE) return;
    note->stage = RELEASE;
    note->release_step = note->env * voice->inverse_rate / note->release_seconds;
}

void studio_voice_cut(StudioVoice *voice) {
    retire(voice);
    *current(voice) = (Note){0};
}

bool studio_voice_active(const StudioVoice *voice) {
    return voice->notes[voice->front].kind != NOTE_IDLE || voice->fade_remaining != 0;
}

static inline void finish(Note *note) {
    if (note->kind == NOTE_SAMPLE) note->synth.sample.pcm = NULL;
    if (note->kind == NOTE_WAVETABLE) note->synth.wave.table = NULL;
    note->kind = NOTE_IDLE;
}

static inline void advance_sample(SampleNote *s) {
    double next = s->position + (s->reverse ? -s->step : s->step);
    if (s->loop_end) {
        double length = (double)(s->loop_end - s->loop_start);
        if (s->reverse && next < s->loop_start) {
            double crossing = s->loop_start - next;
            if (crossing >= length) crossing -= (uint32_t)(crossing / length) * length;
            next = crossing > 0. ? s->loop_end - crossing : (double)s->loop_start;
            s->reverse_wrapped = true;
        } else if (!s->reverse && next >= s->loop_end) {
            double crossing = next - s->loop_end;
            if (crossing >= length) crossing -= (uint32_t)(crossing / length) * length;
            next = s->loop_start + crossing;
        }
    }
    s->position = next;
}

static void sample_frame(const StudioVoice *voice, Note *note, float gain, float *l, float *r) {
    SampleNote *s = &note->synth.sample;
    uint32_t i = (uint32_t)s->position, j = i + 1;
    if (s->loop_end && j == s->loop_end && (!s->reverse || s->reverse_wrapped)) j = s->loop_start;
    else if (j == s->end) j = i;
    float f = (float)(s->position - (double)i);
    const float *a = s->pcm + (size_t)i * s->channels, *b = s->pcm + (size_t)j * s->channels;
    float x = dsp_audio(dsp_lerp(a[0], b[0], f), 1.f);
    s->cutoff = dsp_slew(s->cutoff, s->goal_cutoff, voice->slew);
    s->filter[0] = dsp_filter(s->filter[0], x, s->cutoff);
    if (s->channels == 2) {
        float y = dsp_audio(dsp_lerp(a[1], b[1], f), 1.f);
        s->filter[1] = dsp_filter(s->filter[1], y, s->cutoff);
    } else {
        s->filter[1] = s->filter[0];
    }
    s->step = dsp_slew(s->step, s->goal_step, voice->slew);
    if (!s->loop_end) {
        /* Taper the final two milliseconds of a one-shot without reading past the
         * slice. A one-frame sample still emits a proportionate impulse. */
        double remaining = s->reverse ? s->position - s->start + 1. : s->end - s->position;
        gain *= dsp_min(1.f, (float)remaining * (voice->inverse_rate / (.002f * s->step)));
    }
    *l = gain * s->filter[0];
    *r = gain * s->filter[1];
    advance_sample(s);
    if (s->position < s->start || s->position >= s->end) finish(note);
}

static float fm_frame(const StudioVoice *voice, Note *note) {
    FmNote *fm = &note->synth.fm;
    if (note->stage != ATTACK) {
        fm->body = dsp_slew(fm->body, fm->sustain, fm->decay_amount);
        fm->mod_envelope = dsp_slew(fm->mod_envelope, .3f, fm->decay_amount);
    }
    fm->index = dsp_slew(fm->index, fm->goal_index, voice->slew);
    fm->feedback = dsp_slew(fm->feedback, fm->goal_feedback, voice->slew);
    float mod = sinusoid(voice->tables, fm->phase[1] + fm->mod_history * fm->feedback);
    fm->mod_history = .5f * (mod + fm->mod_history);
    float wave = sinusoid(voice->tables, fm->phase[0] + mod * fm->index * fm->mod_envelope);
    for (unsigned i = 0; i < 2; ++i) {
        fm->step[i] = dsp_slew(fm->step[i], fm->goal_step[i], voice->slew);
        fm->phase[i] = advance_phase(fm->phase[i], fm->step[i]);
    }
    return wave * fm->body;
}

static inline float drum_noise(uint32_t *seed) {
    uint32_t x = *seed;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    *seed = x;
    return (float)(x >> 8) * (1.f / 8388608.f) - 1.f;
}

static inline float drum_sine(const StudioVoice *voice, DrumNote *d, unsigned op, float bend) {
    d->step[op] = dsp_slew(d->step[op], d->goal_step[op], voice->slew);
    float wave = sinusoid(voice->tables, d->phase[op]);
    d->phase[op] = advance_phase(d->phase[op], dsp_min(.44f, d->step[op] * bend));
    return wave;
}

static float drum_frame(const StudioVoice *voice, Note *note) {
    DrumNote *d = &note->synth.drum;
    d->body = dsp_slew(d->body, d->goal_body, voice->slew);
    d->snap = dsp_slew(d->snap, d->goal_snap, voice->slew);
    d->tone = dsp_slew(d->tone, d->goal_tone, voice->slew);
    d->tone_amount = dsp_slew(d->tone_amount, d->goal_tone_amount, voice->slew);
    float raw = drum_noise(&d->rng);
    d->lp_low = dsp_filter(d->lp_low, raw, voice->drum_lowpass);
    d->lp_high = dsp_filter(d->lp_high, raw, d->tone_amount);
    float noise = .8f * (d->lp_high - d->lp_low), result;
    switch (d->kind) {
    case STUDIO_DRUM_KICK: {
        float wave = drum_sine(voice, d, 0, 1.f + 2.8f * d->snap * d->pitch_envelope);
        /* The odd power shaper adds harmonics without another oscillator. */
        float tone = wave * fabsf(wave);
        result = (.2f + .74f * d->body) * dsp_lerp(wave, tone, .5f * d->tone) +
                 .32f * d->snap * d->click * noise;
        break;
    }
    case STUDIO_DRUM_SNARE: {
        float a = drum_sine(voice, d, 0, 1.f), b = drum_sine(voice, d, 1, 1.f);
        result = d->body * (.45f * a + .18f * b) * (.35f + .65f * d->pitch_envelope) +
                 (.38f + .42f * d->snap) * noise * (.6f + .4f * d->click);
        break;
    }
    case STUDIO_DRUM_HAT: {
        float a = drum_sine(voice, d, 0, 1.f), b = drum_sine(voice, d, 1, 1.f), c = drum_sine(voice, d, 2, 1.f);
        float metal = .33333333f * (a * b + b * c - a * c);
        result = (.12f + .55f * d->body) * metal + (.18f + .49f * d->snap) * noise;
        break;
    }
    default: return 0.f;
    }
    result *= d->natural;
    d->natural *= d->decay_pole;
    d->pitch_envelope = dsp_zap_tiny(d->pitch_envelope * voice->drum_pitch_pole);
    d->click = dsp_zap_tiny(d->click * voice->drum_click_pole);
    if (d->natural <= .0001f) finish(note);
    return result;
}

static inline float wave_lookup(const StudioWavetable *table, unsigned band,
    unsigned wave, float morph, unsigned index, float fraction) {
    const float *a = table->bands[band][wave];
    float x = dsp_lerp(a[index], a[index + 1], fraction);
    if (morph == 0.f) return x; /* also excludes reading beyond wave D */
    const float *b = table->bands[band][wave + 1];
    return dsp_lerp(x, dsp_lerp(b[index], b[index + 1], fraction), morph);
}

static float wavetable_frame(const StudioVoice *voice, Note *note, float gain) {
    WaveNote *w = &note->synth.wave;
    float slew = voice->slew;
    w->morph = dsp_slew(w->morph, w->goal_morph, slew);
    float position = w->morph;
    if (w->scan_step != 0.f || w->goal_scan_step != 0.f || w->scan_depth != 0.f) {
        w->scan_step = dsp_slew(w->scan_step, w->goal_scan_step, slew);
        w->scan_depth = dsp_slew(w->scan_depth, w->goal_scan_depth, slew);
        float lfo = 1.f - 4.f * fabsf(w->scan_phase - .5f);
        position = dsp_unit(position + w->scan_depth * lfo);
        w->scan_phase = advance_phase(w->scan_phase, w->scan_step);
    }
    float morph = 3.f * position;
    unsigned wave = (unsigned)morph;
    morph -= (float)wave;

    /* Select using the step that entered this frame, including pitch glide. */
    float step = w->step;
    unsigned band = step <= .45f / 32.f ? 5 : step <= .45f / 16.f ? 4 :
                    step <= .45f / 8.f ? 3 : step <= .45f / 4.f ? 2 :
                    step <= .45f / 2.f ? 1 : 0;
    float phase = w->phase * WAVE_PERIOD;
    unsigned index = (unsigned)phase;
    float fraction = phase - (float)index;
    float x = wave_lookup(w->table, band, wave, morph, index, fraction);
    float top = (float)(1u << band) * step;
    if (band && top > .36f) {
        float lighter = wave_lookup(w->table, band - 1u, wave, morph, index, fraction);
        float mix = dsp_unit((top - .36f) * (1.f / (.45f - .36f)));
        x = dsp_lerp(x, lighter, mix);
    }
    w->step = dsp_slew(step, w->goal_step, slew);
    w->phase = advance_phase(w->phase, step);
    x *= gain;
    if (w->correcting) {
        /* New edit supersedes the last correction, never stacks one on top. */
        w->correcting = false;
        w->correction = note->last_l - x;
        w->correction_remaining = voice->fade_frames - 1u;
        return note->last_l;
    }
    if (w->correction_remaining) {
        float fade = (float)w->correction_remaining-- * voice->inverse_fade;
        return dsp_clamp(x + w->correction * fade, -1.f, 1.f);
    }
    return x;
}

static float pluck_frame(const StudioVoice *voice, Note *note) {
    PluckNote *p = &note->synth.pluck;
    float wave = 0.f;
    for (unsigned i = 0; i < PLUCK_MODES; ++i) {
        float step = p->step[i], phase = p->phase[i], amp = p->amplitude[i];
        if (i == 0 || (step < .45f && amp != 0.f)) {
            float taper = i == 0 ? 1.f : dsp_unit((.45f - step) * (1.f / (.45f - .38f)));
            wave += amp * taper * sinusoid(voice->tables, phase);
        }
        p->phase[i] = advance_phase(phase, dsp_min(.45f, step));
        p->step[i] = dsp_slew(step, p->goal_step[i], voice->slew);
        p->amplitude[i] = dsp_zap_tiny(amp * p->pole[i]);
    }
    if (p->amplitude[0] <= p->floor) finish(note);
    return wave;
}

static void render_note(const StudioVoice *voice, Note *note, float *l, float *r) {
    *l = *r = 0.f;
    if (note->kind == NOTE_IDLE) return;
    if (note->kind == NOTE_RESIDUAL) {
        *l = note->last_l;
        *r = note->last_r;
        return;
    }
    if (note->stage == ATTACK) {
        note->env = dsp_min(1.f, note->env + note->attack_step);
        if (note->env == 1.f) note->stage = HELD;
    } else if (note->stage == RELEASE) {
        note->env -= note->release_step;
        if (note->env <= 1e-7f) {
            finish(note);
            return;
        }
    }
    note->velocity = dsp_slew(note->velocity, note->goal_velocity, voice->slew);
    note->level = dsp_slew(note->level, note->goal_level, voice->slew);
    float gain = note->env * note->velocity * note->level;
    switch (note->kind) {
    case NOTE_IDLE: break;
    case NOTE_SAMPLE: sample_frame(voice, note, gain, l, r); break;
    case NOTE_FM: *l = *r = gain * fm_frame(voice, note); break;
    case NOTE_DRUM: *l = *r = gain * drum_frame(voice, note); break;
    case NOTE_WAVETABLE: *l = *r = wavetable_frame(voice, note, gain); break;
    case NOTE_PLUCK: *l = *r = gain * pluck_frame(voice, note); break;
    case NOTE_RESIDUAL: break; /* handled above, without ticking the envelope */
    }
}

void studio_voice_frame(StudioVoice *voice, float *left, float *right) {
    Note *note = current(voice);
    render_note(voice, note, left, right);
    note->last_l = *left;
    note->last_r = *right;
    if (voice->fade_remaining) {
        Note *old = &voice->notes[voice->front ^ 1u];
        float l, r;
        render_note(voice, old, &l, &r);
        old->last_l = l;
        old->last_r = r;
        float fade = (float)voice->fade_remaining * voice->inverse_fade;
        *left += fade * (l + voice->residual_l);
        *right += fade * (r + voice->residual_r);
        if (--voice->fade_remaining == 0) *old = (Note){0};
    }
    *left = dsp_clamp(*left, -1.f, 1.f);
    *right = dsp_clamp(*right, -1.f, 1.f);
}
