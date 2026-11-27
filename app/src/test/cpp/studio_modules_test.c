/* Behavioral tests for the standalone portable Studio DSP. GPL-3.0-or-later. */
#include "studio/modules/studio_modules.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>

#define RATE 8000u

typedef struct Stereo { float l, r; } Stereo;

static StudioDspTables tables;

static StudioFx *effect(StudioFxKind kind, StudioFxParams params) {
    StudioFx *fx = studio_fx_create(kind, RATE);
    assert(fx);
    studio_fx_set(fx, &params);
    return fx;
}

static Stereo fx_frame(StudioFx *fx, float left, float right) {
    Stereo x = {left, right};
    studio_fx_frame(fx, &x.l, &x.r);
    assert(isfinite(x.l) && fabsf(x.l) <= 4.f && isfinite(x.r) && fabsf(x.r) <= 4.f);
    return x;
}

static StudioVoice *new_voice(void) {
    StudioVoice *voice = studio_voice_create(RATE, &tables);
    assert(voice);
    return voice;
}

static Stereo voice_frame(StudioVoice *voice) {
    Stereo x;
    studio_voice_frame(voice, &x.l, &x.r);
    assert(isfinite(x.l) && fabsf(x.l) <= 1.f && isfinite(x.r) && fabsf(x.r) <= 1.f);
    return x;
}

static void near(float actual, float expected) { assert(fabsf(actual - expected) < 2e-5f); }

static void test_delay(void) {
    StudioFxParams p = {.delay = {2.0625f, 0.f, 20000.f, 1.f}}; /* 16.5 frames */
    StudioFx *fx = effect(STUDIO_FX_DELAY, p);
    for (unsigned i = 0; i < 120; ++i) {
        Stereo frame = fx_frame(fx, i == 0 ? .8f : 0.f, i == 0 ? -.4f : 0.f);
        bool echo = i == 16 || i == 17;
        near(frame.l, echo ? .4f : 0.f);
        near(frame.r, echo ? -.2f : 0.f);
    }
    studio_fx_clear(fx);
    p.delay.time_ms = 3.f;
    p.delay.feedback = .95f;
    studio_fx_set(fx, &p);
    float first = 0.f, second = 0.f, late = 0.f;
    for (unsigned i = 0; i < 3 * RATE; ++i) {
        Stereo frame = fx_frame(fx, i == 0 ? .8f : 0.f, 0.f);
        assert(frame.r == 0.f);
        if (i == 24) first = frame.l;
        if (i == 48) second = frame.l;
        if (i > 2 * RATE) late = fmaxf(late, fabsf(frame.l));
    }
    near(first, .8f);
    assert(second > .6f && second < first && late < .001f);
    studio_fx_clear(fx);
    fx_frame(fx, 1.f, -.4f);
    studio_fx_clear(fx);
    for (unsigned i = 0; i < 400; ++i) {
        Stereo frame = fx_frame(fx, 0.f, 0.f);
        assert(frame.l == 0.f && frame.r == 0.f);
    }
    studio_fx_destroy(fx);

    /* An independent one-shot at the upper allocation bound catches ring aliasing. */
    fx = studio_fx_create(STUDIO_FX_DELAY, STUDIO_DSP_MAX_RATE);
    assert(fx);
    p.delay.time_ms = STUDIO_DELAY_MAX_MS;
    p.delay.feedback = 0.f;
    studio_fx_set(fx, &p);
    for (unsigned i = 0; i <= 2 * STUDIO_DSP_MAX_RATE + 2; ++i) {
        Stereo frame = fx_frame(fx, i == 0 ? 1.f : 0.f, 0.f);
        near(frame.l, i == 2 * STUDIO_DSP_MAX_RATE ? 1.f : 0.f);
        assert(frame.r == 0.f);
    }
    studio_fx_destroy(fx);
}

static void test_delay_automation(void) {
    StudioFxParams p = {.delay = {10.f, 0.f, 20000.f, 1.f}};
    StudioFx *fx = effect(STUDIO_FX_DELAY, p);
    p.delay.time_ms = 20.f;
    StudioFx *target = effect(STUDIO_FX_DELAY, p);
    for (unsigned i = 0; i < 850; ++i) {
        float input = ((int)(i % 257) - 128) / 160.f;
        if (i == 400) studio_fx_set(fx, &p);
        if (i == 650) studio_fx_set_bypass(fx, true);
        Stereo a = fx_frame(fx, input, -input);
        Stereo b = fx_frame(target, input, -input);
        if (i == 400) near(a.l, (int)((400 - 80) % 257 - 128) / 160.f);
        if (i == 549) near(a.l, b.l);
        if (i == 650) assert(fabsf(a.l - b.l) < .05f);
        if (i >= 700) assert(a.l == input && a.r == -input);
    }
    studio_fx_set_bypass(fx, false);
    for (unsigned i = 850; i < 910; ++i) {
        Stereo a = fx_frame(fx, .05f, -.2f);
        Stereo b = fx_frame(target, .05f, -.2f);
        if (i >= 898) { near(a.l, b.l); near(a.r, b.r); }
    }
    studio_fx_destroy(fx);
    studio_fx_destroy(target);
}

static void test_drive_and_crusher(void) {
    StudioFxParams p = {.drive = {0.f, 20000.f, 0.f}};
    StudioFx *drive = effect(STUDIO_FX_DRIVE, p);
    Stereo low = {0};
    for (unsigned i = 0; i < 100; ++i) low = fx_frame(drive, .2f, 0.f);
    studio_fx_clear(drive);
    p.drive.drive_db = 36.f;
    studio_fx_set(drive, &p);
    Stereo strong = {0};
    for (unsigned i = 0; i < 100; ++i) strong = fx_frame(drive, .2f, 0.f);
    assert(strong.l > 3.f * low.l && strong.l <= 1.f && strong.r == 0.f);
    p.drive.trim_db = -6.f;
    studio_fx_set(drive, &p);
    Stereo changed = fx_frame(drive, .2f, 0.f);
    assert(changed.l < strong.l && changed.l > strong.l - .03f);
    for (unsigned i = 0; i < 800; ++i) changed = fx_frame(drive, .2f, 0.f);
    near(changed.l, .5011872f * strong.l);

    StudioFx *crusher = effect(STUDIO_FX_CRUSHER, (StudioFxParams){.crusher = {2, 2000, 1, 0}});
    Stereo q = fx_frame(crusher, .4f, -.9f);
    assert(q.l == .5f && q.r == -1.f);
    for (unsigned i = 1; i < 4; ++i) {
        q = fx_frame(crusher, -.15f, .07f);
        assert(q.l == .5f && q.r == -1.f);
    }
    q = fx_frame(crusher, -.15f, .07f);
    assert(q.l == 0.f && q.r == 0.f);
    studio_fx_destroy(crusher);

    /* Reordering nonlinear Drive around a mixed delay is audibly noncommutative. */
    StudioFxParams d = {.delay = {1.f, .7f, 20000.f, .5f}};
    StudioFx *delay_a = effect(STUDIO_FX_DELAY, d), *delay_b = effect(STUDIO_FX_DELAY, d);
    StudioFx *drive_b = effect(STUDIO_FX_DRIVE, p);
    studio_fx_clear(drive);
    float difference = 0.f;
    for (unsigned i = 0; i < 512; ++i) {
        float x = i % 17 < 4 ? .2f : -.05f;
        Stereo a = fx_frame(drive, x, 0.f);
        a = fx_frame(delay_a, a.l, a.r);
        Stereo b = fx_frame(delay_b, x, 0.f);
        b = fx_frame(drive_b, b.l, b.r);
        difference += fabsf(a.l - b.l);
    }
    assert(difference > 1.f);
    studio_fx_destroy(drive);
    studio_fx_destroy(drive_b);
    studio_fx_destroy(delay_a);
    studio_fx_destroy(delay_b);
}

static void test_reverb_chorus(void) {
    StudioFxParams p = {.reverb = {0, .2f, 20000, 40, 1}};
    StudioFx *short_tail = effect(STUDIO_FX_REVERB, p);
    p.reverb.decay_seconds = 3.f;
    StudioFx *long_tail = effect(STUDIO_FX_REVERB, p);
    unsigned first = 0;
    double short_energy = 0., long_energy = 0.;
    for (unsigned i = 0; i < 12000; ++i) {
        Stereo a = fx_frame(short_tail, i == 0 ? 1.f : 0.f, 0.f);
        Stereo b = fx_frame(long_tail, i == 0 ? 1.f : 0.f, 0.f);
        if (!first && fabsf(b.l) > 1e-6f) {
            first = i;
            assert(b.l > b.r && b.r > 0.f); /* not a phase-inverted mono return */
        }
        if (i >= 6000) {
            short_energy += a.l * a.l + a.r * a.r;
            long_energy += b.l * b.l + b.r * b.r;
        }
    }
    assert(first > 400 && first < 480);
    assert(long_energy > 1e-6 && long_energy > 20. * short_energy);
    studio_fx_clear(long_tail);
    for (unsigned i = 0; i < 800; ++i) {
        Stereo dry = fx_frame(long_tail, 0.f, 0.f);
        assert(dry.l == 0.f && dry.r == 0.f);
    }
    studio_fx_destroy(short_tail);
    studio_fx_destroy(long_tail);

    StudioFx *chorus = effect(STUDIO_FX_CHORUS, (StudioFxParams){.chorus = {1, 6, .75f}});
    float wide = 0.f;
    for (unsigned i = 0; i < 2 * RATE; ++i) {
        float x = .5f * sinf((float)i * (6.28318530718f * 310.f / RATE));
        Stereo wet = fx_frame(chorus, x, x);
        if (i > 200) wide += fabsf(wet.l - wet.r);
    }
    assert(wide > 500.f);
    studio_fx_clear(chorus);
    for (unsigned i = 0; i < 400; ++i) {
        Stereo dry = fx_frame(chorus, 0.f, 0.f);
        assert(dry.l == 0.f && dry.r == 0.f);
    }
    studio_fx_destroy(chorus);
}

static void test_sampler(void) {
    enum { PCM_FRAMES = 512 };
    float pcm[2 * PCM_FRAMES];
    for (unsigned i = 0; i < PCM_FRAMES; ++i) {
        pcm[2 * i] = .1f + (float)i / 1000.f;
        pcm[2 * i + 1] = -.75f + (float)i / 1024.f;
    }
    StudioSamplerPatch p = {.pcm = {pcm, PCM_FRAMES, RATE, 2}, .start = 7, .end = 407,
                            .root_hz = 440, .cutoff_hz = 20000, .level = 1};
    StudioVoice *v = new_voice();
    for (unsigned scenario = 0; scenario < 4; ++scenario) {
        p.reverse = scenario == 1;
        p.fine_cents = scenario == 2 ? 1200.f : 0.f;
        p.pcm.sample_rate = scenario == 3 ? 16000u : RATE;
        studio_voice_clear(v);
        assert(studio_voice_start_sampler(v, &p, 440.f, 1.f));
        Stereo value = {0};
        for (unsigned i = 0; i <= 31; ++i) value = voice_frame(v);
        unsigned expected = scenario == 0 ? 38 : scenario == 1 ? 375 : 69;
        near(value.l, pcm[2 * expected]);
        near(value.r, pcm[2 * expected + 1]);
    }
    p.pcm.sample_rate = RATE;
    p.reverse = false;
    p.end = 60;
    p.loop_start = 10;
    p.loop_end = 15;
    studio_voice_clear(v);
    assert(studio_voice_start_sampler(v, &p, 440.f, 1.f));
    for (unsigned i = 0; i < 160; ++i) {
        Stereo frame = voice_frame(v);
        if (i >= 10) {
            unsigned pos = 10 + (i - 3) % 5;
            near(frame.l, pcm[pos * 2]);
            near(frame.r, pcm[pos * 2 + 1]);
        }
    }
    /* A fractional reverse pass enters the loop from its real neighboring frame;
     * after wrapping, interpolation uses only the two loop endpoints. */
    p.reverse = true;
    studio_voice_clear(v);
    assert(studio_voice_start_sampler(v, &p, 220.f, 1.f));
    for (unsigned i = 0; i <= 105; ++i) {
        Stereo frame = voice_frame(v);
        if (i == 89) near(frame.l, (pcm[2 * 14] + pcm[2 * 15]) * .5f);
        if (i == 99) near(frame.l, (pcm[2 * 14] + pcm[2 * 10]) * .5f);
    }
    /* A damaged trigger must not stop a sounding slice. A cut ends it despite the loop. */
    p.end = PCM_FRAMES + 1;
    assert(!studio_voice_start_sampler(v, &p, 440.f, 1.f));
    assert(voice_frame(v).l > .1f);
    studio_voice_cut(v);
    for (unsigned i = 0; i < RATE * 4u / 1000u; ++i) voice_frame(v);
    assert(!studio_voice_active(v));
    Stereo zero = voice_frame(v);
    assert(zero.l == 0.f && zero.r == 0.f);

    /* A one-shot that finishes naturally must not leave the next note's envelope open. */
    p.reverse = false;
    p.end = 80;
    p.loop_end = p.loop_start = 0;
    assert(studio_voice_start_sampler(v, &p, 440.f, 1.f));
    Stereo first = voice_frame(v);
    assert(first.l > 0.f && first.l < .02f);
    for (unsigned i = 0; i < 80; ++i) voice_frame(v);
    assert(!studio_voice_active(v));
    assert(studio_voice_start_sampler(v, &p, 440.f, 1.f));
    Stereo repeat = voice_frame(v);
    near(repeat.l, first.l);
    near(repeat.r, first.r);
    studio_voice_destroy(v);
}

static void test_sampler_track_local(void) {
    float pcm[] = {.375f, -.25f};
    StudioSamplerPatch p = {.pcm = {pcm, 1, RATE, 2}, .end = 1, .loop_end = 1,
                            .root_hz = 440, .release_seconds = .05f, .cutoff_hz = 20000, .level = 1};
    StudioVoice *a = new_voice(), *b = new_voice();
    assert(studio_voice_start_sampler(a, &p, 440.f, 1.f));
    assert(studio_voice_start_sampler(b, &p, 440.f, 1.f));
    for (unsigned i = 0; i < 60; ++i) { voice_frame(a); voice_frame(b); }
    p.level = 0.f;
    studio_voice_update_sampler(a, &p);
    Stereo changed = voice_frame(a);
    assert(changed.l < .375f && changed.l > .35f);
    for (unsigned i = 0; i < 600; ++i) changed = voice_frame(a);
    near(changed.l, 0.f);
    Stereo shared = voice_frame(b);
    near(shared.l, .375f);
    near(shared.r, -.25f);
    studio_voice_release(b);
    assert(voice_frame(b).l > .35f);
    for (unsigned i = 0; i < 450; ++i) voice_frame(b);
    assert(!studio_voice_active(b) && studio_voice_active(a));
    studio_voice_clear(a);
    assert(!studio_voice_active(a));
    shared = voice_frame(a);
    assert(shared.l == 0.f && shared.r == 0.f);
    studio_voice_destroy(a);
    studio_voice_destroy(b);
}

static unsigned crossings(StudioVoice *voice, unsigned frames) {
    unsigned count = 0;
    float previous = voice_frame(voice).l;
    for (unsigned i = 1; i < frames; ++i) {
        Stereo x = voice_frame(voice);
        assert(x.l == x.r);
        if (previous <= 0.f && x.l > 0.f) ++count;
        previous = x.l;
    }
    return count;
}

static void test_fm(void) {
    StudioFmPatch p = {.carrier_ratio = 1, .modulator_ratio = 2, .index = 0,
                       .attack_seconds = .001f, .decay_seconds = .2f, .sustain = 1,
                       .release_seconds = .05f, .level = .6f};
    StudioVoice *a = new_voice(), *b = new_voice();
    studio_voice_start_fm(a, &p, 200.f, 1.f, false);
    unsigned fundamental = crossings(a, RATE / 10u);
    assert(fundamental >= 19 && fundamental <= 21);
    studio_voice_clear(a);
    studio_voice_start_fm(a, &p, 200.f, 1.f, false);
    studio_voice_start_fm(b, &p, 200.f, 1.f, false);
    for (unsigned i = 0; i < 123; ++i) { voice_frame(a); voice_frame(b); }
    studio_voice_start_fm(a, &p, 400.f, 1.f, true);
    near(voice_frame(a).l, voice_frame(b).l); /* legato does not reset either phase */
    for (unsigned i = 0; i < 500; ++i) voice_frame(a);
    unsigned octave = crossings(a, RATE / 10u);
    assert(octave >= 39 && octave <= 41);
    studio_voice_pitch(a, 200.f);
    for (unsigned i = 0; i < 500; ++i) voice_frame(a);
    fundamental = crossings(a, RATE / 10u);
    assert(fundamental >= 19 && fundamental <= 21);

    studio_voice_clear(a);
    studio_voice_clear(b);
    studio_voice_start_fm(a, &p, 100.f, 1.f, false);
    studio_voice_start_fm(b, &p, 100.f, 1.f, false);
    for (unsigned i = 0; i < 200; ++i) { voice_frame(a); voice_frame(b); }
    p.index = 12;
    p.feedback = 1;
    studio_voice_update_fm(a, &p);
    float sum = 0.f;
    for (unsigned i = 0; i < 1000; ++i) sum += fabsf(voice_frame(a).l - voice_frame(b).l);
    assert(sum > 50.f);
    studio_voice_release(a);
    for (unsigned i = 0; i < 450; ++i) voice_frame(a);
    assert(!studio_voice_active(a));
    studio_voice_destroy(a);
    studio_voice_destroy(b);
}

static void test_drums(void) {
    StudioVoice *a = new_voice(), *b = new_voice();
    StudioDrumPatch p = {.kind = STUDIO_DRUM_KICK, .body = 1.f, .snap = 0.f,
                         .tone = .3f, .decay_seconds = .2f, .level = 1.f};
    studio_voice_start_drum(a, &p, 100.f, 1.f);
    studio_voice_start_drum(b, &p, 200.f, 1.f);
    unsigned low = crossings(a, RATE / 10u), high = crossings(b, RATE / 10u);
    assert(low >= 9 && low <= 11 && high >= 19 && high <= 21);
    for (unsigned k = STUDIO_DRUM_KICK; k <= STUDIO_DRUM_HAT; ++k) {
        studio_voice_clear(a);
        studio_voice_clear(b);
        p.kind = (StudioDrumKind)k;
        p.snap = .7f;
        studio_voice_start_drum(a, &p, 160.f, 1.f);
        studio_voice_start_drum(b, &p, 160.f, 1.f);
        float peak = 0.f;
        for (unsigned i = 0; i < 1800; ++i) {
            Stereo x = voice_frame(a), y = voice_frame(b);
            assert(x.l == y.l && x.r == y.r);
            peak = fmaxf(peak, fabsf(x.l));
        }
        assert(peak > .04f && !studio_voice_active(a) && !studio_voice_active(b));
    }
    p.kind = STUDIO_DRUM_SNARE;
    studio_voice_start_drum(a, &p, 200.f, 1.f);
    p.kind = STUDIO_DRUM_HAT;
    studio_voice_start_drum(b, &p, 200.f, 1.f);
    float difference = 0.f;
    for (unsigned i = 0; i < 300; ++i) difference += fabsf(voice_frame(a).l - voice_frame(b).l);
    assert(difference > 4.f);
    /* This retrigger replaces an older overlap before it retires. Cut drains all state. */
    studio_voice_start_drum(a, &p, 160.f, 1.f);
    for (unsigned i = 0; i < 16; ++i) voice_frame(a);
    studio_voice_start_drum(a, &p, 320.f, 1.f);
    for (unsigned i = 0; i < 16; ++i) voice_frame(a);
    studio_voice_cut(a);
    for (unsigned i = 0; i < RATE * 4u / 1000u; ++i) voice_frame(a);
    assert(!studio_voice_active(a));
    studio_voice_destroy(a);
    studio_voice_destroy(b);
}

static void test_finite_extremes(void) {
    const StudioFxParams extreme[] = {
        [STUDIO_FX_DELAY] = {.delay = {1, 100, 2, 2}},
        [STUDIO_FX_DRIVE] = {.drive = {1000, 0, 100}},
        [STUDIO_FX_CRUSHER] = {.crusher = {NAN, INFINITY, 20, 100}},
        [STUDIO_FX_REVERB] = {.reverb = {100, 100, 1, 0, 40}},
        [STUDIO_FX_CHORUS] = {.chorus = {100, 100, 4}},
    };
    StudioFx *fx[STUDIO_FX_CHORUS];
    for (unsigned i = STUDIO_FX_DELAY; i <= STUDIO_FX_CHORUS; ++i) {
        fx[i - 1] = effect((StudioFxKind)i, extreme[i]);
    }
    for (unsigned i = 0; i < 6 * RATE; ++i) {
        float frame[2] = {i % 29 ? -.95f : NAN, i % 17 ? .99f : INFINITY};
        for (unsigned j = 0; j < STUDIO_FX_CHORUS; ++j) studio_fx_process(fx[j], frame, 1);
        assert(isfinite(frame[0]) && fabsf(frame[0]) <= 4.f);
        assert(isfinite(frame[1]) && fabsf(frame[1]) <= 4.f);
    }
    for (unsigned i = 0; i < STUDIO_FX_CHORUS; ++i) studio_fx_destroy(fx[i]);
}

int main(void) {
    studio_dsp_prepare_tables(&tables);
    test_delay();
    test_delay_automation();
    test_drive_and_crusher();
    test_reverb_chorus();
    test_sampler();
    test_sampler_track_local();
    test_fm();
    test_drums();
    test_finite_extremes();
    puts("Studio DSP: echo timing/automation/clear, drive/order, quantize/hold, stereo tails/chorus,"
         " pitched/reverse/looping PCM, FM legato, deterministic drums and finite extremes OK");
    return 0;
}
