/* Original Thor Tracker Studio DSP. SPDX-License-Identifier: GPL-3.0-or-later */
#include "studio_dsp.h"
#include <stdlib.h>

enum VoiceKind { NOTE_IDLE, NOTE_SAMPLE, NOTE_FM, NOTE_DRUM };
enum EnvelopeStage { ATTACK, HELD, RELEASE };

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
    union { SampleNote sample; FmNote fm; DrumNote drum; } synth;
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

void studio_voice_pitch(StudioVoice *voice, float hz) {
    if (!valid_frequency(hz)) return;
    Note *note = current(voice);
    note->hz = bounded_hz(hz);
    switch (note->kind) {
    case NOTE_IDLE: break;
    case NOTE_SAMPLE: set_sample_pitch(note); break;
    case NOTE_FM: set_fm_pitch(voice, note); break;
    case NOTE_DRUM: set_drum_pitch(voice, note); break;
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

static void render_note(const StudioVoice *voice, Note *note, float *l, float *r) {
    *l = *r = 0.f;
    if (note->kind == NOTE_IDLE) return;
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
