/* Host-only audio behavior checks for the Studio mono synth. SPDX-License-Identifier: GPL-3.0-or-later */
#include <float.h>
#include <math.h>
#include <stdio.h>

#include "mono_synth.h"

#define RATE 48000
#define LONG_WINDOW 4800

static int failures;
#define EXPECT(condition, ...)                         \
    do {                                               \
        if (!(condition)) {                            \
            ++failures;                                \
            fprintf(stderr, "line %d: ", __LINE__);     \
            fprintf(stderr, __VA_ARGS__);               \
            fputc('\n', stderr);                        \
        }                                              \
    } while (0)

static const MonoSynthPatch flat_patch = {
    .waveform = MONO_SYNTH_SAW,
    .cutoff_hz = 8000.0f,
    .decay_seconds = 0.1f,
    .level = 0.7f,
};

static void start(MonoSynth* voice, const MonoSynthPatch* patch, float hz) {
    mono_synth_init(voice, RATE);
    mono_synth_apply_patch(voice, patch);
    mono_synth_note_on(voice, hz, 1.0f, 0.0f, false);
}

static void render(MonoSynth* voice, float* out, int frames) {
    for (int i = 0; i < frames; ++i) {
        const float sample = mono_synth_render(voice);
        if (out) out[i] = sample;
    }
}

static double energy(const float* samples, int frames) {
    double sum = 0.0;
    for (int i = 0; i < frames; ++i) sum += (double)samples[i] * samples[i];
    return sum / frames;
}

static double magnitude(const float* samples, int frames, double hz) {
    double real = 0.0, imag = 0.0;
    const double step = 6.28318530717958647693 * hz / RATE;
    for (int i = 0; i < frames; ++i) {
        real += samples[i] * cos(step * i);
        imag += samples[i] * sin(step * i);
    }
    return hypot(real, imag) / frames;
}

static double pitch(const float* samples, int frames) {
    double first = 0.0, last = 0.0;
    int crossings = 0;
    for (int i = 1; i < frames; ++i) {
        if (samples[i - 1] <= 0.0f && samples[i] > 0.0f) {
            last = i - 1 - (double)samples[i - 1] / (samples[i] - samples[i - 1]);
            if (crossings++ == 0) first = last;
        }
    }
    return crossings >= 2 ? (crossings - 1) * RATE / (last - first) : 0.0;
}

static bool same_output(MonoSynth* a, MonoSynth* b, int frames) {
    for (int i = 0; i < frames; ++i) {
        if (mono_synth_render(a) != mono_synth_render(b)) return false;
    }
    return true;
}

static bool silent(MonoSynth* voice, int frames) {
    for (int i = 0; i < frames; ++i) {
        if (mono_synth_render(voice) != 0.0f) return false;
    }
    return true;
}

static void test_oscillators(void) {
    MonoSynth voice;
    float audio[LONG_WINDOW];
    for (int waveform = MONO_SYNTH_SAW; waveform <= MONO_SYNTH_SQUARE; ++waveform) {
        MonoSynthPatch patch = flat_patch;
        patch.waveform = waveform;
        start(&voice, &patch, 440.0f);
        render(&voice, NULL, 960);
        render(&voice, audio, LONG_WINDOW);
        const double measured = pitch(audio, LONG_WINDOW);
        EXPECT(fabs(measured - 440.0) < 0.3, "waveform %d pitch %.3f Hz", waveform, measured);
        const double fundamental = magnitude(audio, LONG_WINDOW, 440.0);
        const double second = magnitude(audio, LONG_WINDOW, 880.0);
        EXPECT(fundamental > 0.06 && (waveform == MONO_SYNTH_SAW ? second > 0.30 * fundamental
                                                                 : second < 0.02 * fundamental),
               "waveform %d fundamental %.4f, second harmonic %.4f", waveform, fundamental, second);

        /* 9500 Hz's fifth harmonic folds to 500 Hz at 48 kHz. A high cutoff passes that alias;
         * a low output level keeps the soft limiter from generating comparable harmonics. */
        patch.cutoff_hz = 16000.0f;
        patch.level = 0.1f;
        start(&voice, &patch, 9500.0f);
        render(&voice, NULL, 960);
        render(&voice, audio, LONG_WINDOW);
        const double tone = magnitude(audio, LONG_WINDOW, 9500.0);
        const double alias = magnitude(audio, LONG_WINDOW, 500.0);
        EXPECT(tone > 0.008 && alias < 0.09 * tone,
               "waveform %d at high pitch: fundamental %.5f, folded fifth %.5f", waveform, tone, alias);
    }
}

static void test_release_and_reset(void) {
    MonoSynthPatch patch = flat_patch;
    patch.waveform = MONO_SYNTH_SQUARE;
    patch.resonance = 0.7f;
    patch.envelope_octaves = 2.0f;
    MonoSynth voice, fresh;
    start(&voice, &patch, 250.0f);
    render(&voice, NULL, 973);
    mono_synth_note_off(&voice);
    float tail[96];
    render(&voice, tail, 96);
    EXPECT(energy(tail, 96) > 0.001, "note-off hard-cut a sounding note");
    render(&voice, NULL, 384);
    EXPECT(silent(&voice, 512), "release was not silent by 10 ms");

    mono_synth_note_on(&voice, 880.0f, 0.3f, 1.0f, false);
    render(&voice, NULL, 737);
    mono_synth_reset(&voice);
    EXPECT(silent(&voice, 512), "reset did not cut immediately");
    mono_synth_note_on(&voice, 250.0f, 1.0f, 0.0f, false);
    start(&fresh, &patch, 250.0f);
    EXPECT(same_output(&voice, &fresh, 2048), "reset left voice history or lost the current patch");

    mono_synth_reset(&voice);
    mono_synth_note_on(&voice, 250.0f, 1.0f, 0.0f, false);
    mono_synth_note_off(&voice);
    EXPECT(silent(&voice, 512), "note-off before rendering failed to cancel the onset");
}

static void test_articulation(void) {
    MonoSynthPatch patch = flat_patch;
    patch.cutoff_hz = 300.0f;
    patch.resonance = 0.2f;
    patch.envelope_octaves = 4.0f;
    patch.decay_seconds = 0.015f;
    MonoSynth held;
    start(&held, &patch, 375.0f);
    render(&held, NULL, 12000);
    MonoSynth tied = held, struck = held;
    mono_synth_note_on(&tied, 375.0f, 1.0f, 0.0f, true);
    mono_synth_note_on(&struck, 375.0f, 1.0f, 0.0f, false);
    float sustain[960], attack[960], tie[960];
    render(&held, sustain, 960);
    render(&struck, attack, 960);
    render(&tied, tie, 960);
    bool unchanged = true;
    for (int i = 0; i < 960; ++i) unchanged = unchanged && tie[i] == sustain[i];
    EXPECT(unchanged, "same-pitch legato changed the envelope or reset phase");
    EXPECT(energy(attack + 40, 16) < 0.23 * energy(sustain + 40, 16), "retrigger did not dip the VCA");
    EXPECT(energy(attack + 480, 480) > 1.8 * energy(sustain + 480, 480), "retrigger did not reopen the filter");

    patch.cutoff_hz = 220.0f;
    patch.envelope_octaves = 1.0f;
    patch.decay_seconds = 0.05f;
    patch.accent = 1.0f;
    start(&held, &patch, 440.0f);
    mono_synth_reset(&tied);
    mono_synth_apply_patch(&tied, &patch);
    mono_synth_note_on(&tied, 440.0f, 1.0f, 1.0f, false);
    render(&held, sustain, 960);
    render(&tied, tie, 960);
    EXPECT(energy(tie + 240, 720) > 1.8 * energy(sustain + 240, 720), "per-note accent did not add punch");
}

static void test_glide_and_automation(void) {
    MonoSynthPatch patch = flat_patch;
    patch.glide_seconds = 0.1f;
    MonoSynth sliding;
    float audio[LONG_WINDOW];
    start(&sliding, &patch, 220.0f);
    render(&sliding, NULL, 4800);
    mono_synth_note_on(&sliding, 880.0f, 1.0f, 0.0f, true);
    render(&sliding, audio, 960);
    const double early = pitch(audio, 960);
    EXPECT(early > 240.0 && early < 360.0, "early glide pitch %.2f Hz", early);
    render(&sliding, NULL, 3840);
    render(&sliding, audio, LONG_WINDOW);
    const double settled = pitch(audio, LONG_WINDOW);
    EXPECT(fabs(settled - 880.0) < 0.3, "glide settled at %.2f Hz", settled);

    mono_synth_note_on(&sliding, 220.0f, 1.0f, 0.0f, true);
    render(&sliding, NULL, 480);
    mono_synth_set_frequency(&sliding, 660.0f);
    render(&sliding, audio, LONG_WINDOW);
    const double automated = pitch(audio, LONG_WINDOW);
    EXPECT(fabs(automated - 660.0) < 2.0, "pitch automation did not supersede glide: %.2f Hz", automated);
}

static void test_filter_and_live_level(void) {
    MonoSynthPatch patch = flat_patch;
    patch.cutoff_hz = 440.0f;
    MonoSynth dry, resonant;
    start(&dry, &patch, 440.0f);
    patch.resonance = 1.0f;
    start(&resonant, &patch, 440.0f);
    render(&dry, NULL, 960);
    render(&resonant, NULL, 960);
    float a[960], b[960];
    render(&dry, a, 960);
    render(&resonant, b, 960);
    EXPECT(energy(b, 960) > 3.0 * energy(a, 960), "max resonance did not boost a tone at cutoff");

    patch.envelope_octaves = 4.0f;
    patch.resonance = 0.0f;
    patch.cutoff_hz = 160.0f;
    patch.decay_seconds = 0.01f;
    start(&dry, &patch, 440.0f);
    patch.decay_seconds = 0.4f;
    start(&resonant, &patch, 440.0f);
    render(&dry, NULL, 3840);
    render(&resonant, NULL, 3840);
    render(&dry, a, 960);
    render(&resonant, b, 960);
    EXPECT(energy(b, 960) > 4.0 * energy(a, 960), "decay did not change held-note brightness");

    patch = flat_patch;
    start(&dry, &patch, 440.0f);
    render(&dry, NULL, 2077);
    resonant = dry;
    patch.level = 0.0f;
    mono_synth_apply_patch(&resonant, &patch);
    render(&dry, a, 96);
    render(&resonant, b, 96);
    EXPECT(energy(b, 96) > 0.25 * energy(a, 96) && energy(b, 96) < 0.95 * energy(a, 96),
           "live level edit did not fade over multiple samples");
    render(&dry, NULL, 2400);
    render(&resonant, NULL, 2400);
    EXPECT(silent(&resonant, 256), "live level at zero still made sound");
    render(&dry, NULL, 256);
    mono_synth_apply_patch(&resonant, &flat_patch);
    render(&dry, NULL, 2400);
    render(&resonant, NULL, 2400);
    EXPECT(same_output(&dry, &resonant, 2048), "live level edit changed held-note phase or gate");
}

static void test_bad_inputs_and_limits(void) {
    MonoSynth a, b;
    const MonoSynthPatch bad = {-1, NAN, INFINITY, -INFINITY, NAN, NAN, INFINITY, NAN};
    mono_synth_init(&a, NAN);
    mono_synth_apply_patch(&a, &bad);
    mono_synth_init(&b, RATE);
    mono_synth_note_on(&a, 250.0f, 0.6f, NAN, false);
    mono_synth_note_on(&b, 250.0f, 0.6f, 0.0f, false);
    EXPECT(same_output(&a, &b, 1973), "nonfinite controls did not fall back to the init sound");
    mono_synth_note_on(&a, NAN, 1.0f, 1.0f, false);
    mono_synth_set_frequency(&a, INFINITY);
    EXPECT(same_output(&a, &b, 1973), "nonfinite pitch changed a sounding note");
    mono_synth_reset(&a);
    mono_synth_note_on(&a, 250.0f, INFINITY, 0.0f, false);
    EXPECT(silent(&a, 1973), "nonfinite velocity produced sound");

    static const float rates[] = {-1.0f, 8000.0f, 44100.0f, 48000.0f, 96000.0f, 192000.0f, FLT_MAX, INFINITY};
    MonoSynthPatch edge = {MONO_SYNTH_SAW, FLT_MAX, FLT_MAX, FLT_MAX, -FLT_MAX, FLT_MAX, FLT_MAX, FLT_MAX};
    for (unsigned i = 0; i < sizeof rates / sizeof rates[0]; ++i) {
        mono_synth_init(&a, rates[i]);
        for (int n = 0; n < 24000; ++n) {
            if (n % 257 == 0) {
                const bool bright = (n / 257) % 2 == 0;
                edge.waveform = bright ? MONO_SYNTH_SAW : MONO_SYNTH_SQUARE;
                edge.cutoff_hz = bright ? FLT_MAX : -FLT_MAX;
                mono_synth_apply_patch(&a, &edge);
                mono_synth_note_on(&a, bright ? FLT_MAX : 31.0f, FLT_MAX, FLT_MAX, n != 0);
                if (bright) mono_synth_set_frequency(&a, 0.45f * FLT_MAX);
            }
            const float sample = mono_synth_render(&a);
            if (!isfinite(sample) || fabsf(sample) > 1.0f) {
                EXPECT(false, "rate %g frame %d gave out-of-range sample %g", rates[i], n, sample);
                break;
            }
        }
        b = a;
        mono_synth_note_off(&b);
        render(&b, NULL, 2000);
        EXPECT(silent(&b, 256), "stress case failed to release at rate %g", rates[i]);

        /* The stress pitch can be ultrasonic and intentionally filtered out. Establish recovery
         * on the same open gate, without a reset that could clear poisoned filter state. */
        mono_synth_apply_patch(&a, &flat_patch);
        mono_synth_note_on(&a, 440.0f, 1.0f, 0.0f, true);
        render(&a, NULL, 10000);
        float recovered[2048];
        render(&a, recovered, 2048);
        const double power = energy(recovered, 2048);
        EXPECT(power > 0.01 && power <= 1.0, "rate %g failed to recover ordinary tone: mean square %g", rates[i], power);
    }
}

int main(void) {
    test_oscillators();
    test_release_and_reset();
    test_articulation();
    test_glide_and_automation();
    test_filter_and_live_level();
    test_bad_inputs_and_limits();
    printf("Studio mono synth: %d failures\n", failures);
    return failures != 0;
}
