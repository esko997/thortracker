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

/* The one-frame public API is the oracle for block scheduling. Use uneven spans
 * across tap/read/write wraps, including independent invalid samples on either
 * channel; compare every output, not just aggregate power. */
static void compare_fx_blocks(StudioFx *block, StudioFx *reference, unsigned *clock, unsigned frames) {
    const unsigned widths[] = {1, 128, 37, 257, 2, 89};
    unsigned chunk = 0;
    while (frames) {
        float storage[2 * 257 + 2], *audio = storage + 1;
        Stereo expected[257];
        unsigned n = widths[chunk++ % (sizeof widths / sizeof widths[0])];
        if (n > frames) n = frames;
        storage[0] = 17.f;
        audio[2*n] = -19.f;
        for (unsigned j = 0; j < n; ++j, ++*clock) {
            unsigned t = *clock;
            float l = ((int)((t * 353u + 19u) % 1021u) - 510) / 1024.f;
            float r = ((int)((t * 127u + 83u) % 991u) - 495) / 512.f;
            if (t % 211 == 36) l = NAN;
            if (t % 199 == 14) r = -INFINITY;
            if (t % 251 == 9) r = 9.f;
            audio[2*j] = l; audio[2*j+1] = r;
            expected[j] = fx_frame(reference, l, r);
        }
        studio_fx_process(block, audio, n);
        assert(storage[0] == 17.f && audio[2*n] == -19.f);
        for (unsigned j = 0; j < n; ++j) {
            near(audio[2*j], expected[j].l);
            near(audio[2*j+1], expected[j].r);
        }
        frames -= n;
    }
}

static void test_delay_block_continuation(void) {
    StudioFxParams p = {.delay = {1.f, .25f, 1800.f, .1f}};
    StudioFx *block = effect(STUDIO_FX_DELAY, p), *ref = effect(STUDIO_FX_DELAY, p);
    unsigned clock = 0;
    studio_fx_process(block, NULL, 0); /* Must not prime controls on an empty block. */
    p = (StudioFxParams){.delay = {2.0625f, .82f, 3500.f, .875f}};
    studio_fx_set(block, &p); studio_fx_set(ref, &p);
    compare_fx_blocks(block, ref, &clock, 16);
    compare_fx_blocks(block, ref, &clock, 1); /* tap at age 16.5: only newer endpoint exists */
    compare_fx_blocks(block, ref, &clock, 2 * RATE + 179);

    p.delay.time_ms = 1900.125f;
    studio_fx_set(block, &p); studio_fx_set(ref, &p);
    compare_fx_blocks(block, ref, &clock, 31);
    p.delay.time_ms = 1999.9375f; /* replace pending tap during crossfade */
    p.delay.mix = .35f; p.delay.feedback = .95f; p.delay.tone_hz = 370.f;
    studio_fx_set(block, &p); studio_fx_set(ref, &p);
    compare_fx_blocks(block, ref, &clock, 3 * RATE);
    studio_fx_set_bypass(block, true); studio_fx_set_bypass(ref, true);
    compare_fx_blocks(block, ref, &clock, 201);
    p.delay.time_ms = 4.f; /* exact 32-frame tap; bypass must not freeze history */
    studio_fx_set(block, &p); studio_fx_set(ref, &p);
    studio_fx_set_bypass(block, false); studio_fx_set_bypass(ref, false);
    compare_fx_blocks(block, ref, &clock, 2 * RATE + 83);

    /* Continue the block-built feedback history using only the scalar API. */
    for (unsigned j = 0; j < 500; ++j) {
        Stereo a = fx_frame(block, 0.f, 0.f), b = fx_frame(ref, 0.f, 0.f);
        near(a.l, b.l); near(a.r, b.r);
    }
    /* Previously valid nonzero cells must not leak after O(1) clear. An isolated
     * quiet impulse stays audible at the exact tap, including on the settled path. */
    studio_fx_clear(block);
    p = (StudioFxParams){.delay = {1.f, 0.f, 3000.f, 1.f}};
    studio_fx_set(block, &p);
    float zeros[64] = {0};
    studio_fx_process(block, zeros, 32);
    for (unsigned i = 0; i < 64; ++i) assert(zeros[i] == 0.f);
    zeros[0] = 1e-20f; zeros[1] = -1e-20f;
    studio_fx_process(block, zeros, 32);
    for (unsigned i = 0; i < 32; ++i) {
        assert(zeros[2*i] == (i == 8 ? 1e-20f : 0.f));
        assert(zeros[2*i+1] == (i == 8 ? -1e-20f : 0.f));
    }
    studio_fx_destroy(block); studio_fx_destroy(ref);
}

static void test_other_fx_blocks(void) {
    const StudioFxParams params[] = {
        [STUDIO_FX_DRIVE] = {.drive = {14, 1000, -3}},
        [STUDIO_FX_CRUSHER] = {.crusher = {7, 1303, .8f, -2}},
        [STUDIO_FX_REVERB] = {.reverb = {.3f, .5f, 1200, 3, .7f}},
        [STUDIO_FX_CHORUS] = {.chorus = {.8f, 4, .75f}},
    };
    for (int i = STUDIO_FX_DRIVE; i <= STUDIO_FX_CHORUS; ++i) {
        StudioFx *block = effect((StudioFxKind)i, params[i]), *ref = effect((StudioFxKind)i, params[i]);
        unsigned clock = 0;
        compare_fx_blocks(block, ref, &clock, RATE);
        studio_fx_set_bypass(block, true); studio_fx_set_bypass(ref, true);
        compare_fx_blocks(block, ref, &clock, 15);
        studio_fx_set_bypass(block, false); studio_fx_set_bypass(ref, false);
        compare_fx_blocks(block, ref, &clock, 257);
        studio_fx_destroy(block); studio_fx_destroy(ref);
    }
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

#define TEST_TAU 6.283185307179586

static StudioWavetable *prepare_test_wave(int dc, const int amplitude[4], int harmonic) {
    int8_t points[4 * 64];
    for (unsigned wave = 0; wave < 4; ++wave) {
        for (unsigned i = 0; i < 64; ++i) {
            double phase = TEST_TAU * i / 64.;
            long value = dc + lround(amplitude[wave] * sin(phase) + harmonic * sin(8. * phase));
            assert(value >= -128 && value <= 127);
            points[wave * 64 + i] = (int8_t)value;
        }
    }
    StudioWavetable *table = studio_wavetable_prepare(&tables, points);
    assert(table);
    return table;
}

static void test_wavetable_morph_scan(void) {
    const int amplitude[4] = {32, 64, -32, -64};
    StudioWavetable *table = prepare_test_wave(17, amplitude, 0);
    StudioVoice *v = new_voice();
    StudioWavetablePatch p = {.level = 1.f};
    for (unsigned source = 0; source < 4; ++source) {
        studio_voice_clear(v);
        p.morph = (float)(100 * source) / 300.f;
        assert(studio_voice_start_wavetable(v, &p, table, 4, 125.f, 1.f, false));
        for (unsigned i = 0; i < 192; ++i) {
            Stereo x = voice_frame(v);
            assert(x.l == x.r);
            if (i >= 8) {
                double expected = amplitude[source] * sin(TEST_TAU * i / 64.) / 128.;
                assert(fabs((double)x.l - expected) < .006);
            }
        }
    }

    studio_voice_clear(v);
    p.morph = .5f;
    p.scan_depth = .75f;
    p.scan_hz = 2.f;
    assert(studio_voice_start_wavetable(v, &p, table, 4, 125.f, 1.f, false));
    for (unsigned i = 0; i < 9000; ++i) {
        if (i == 461) assert(studio_voice_start_wavetable(v, &p, table, 4, 125.f, 1.f, true));
        Stereo x = voice_frame(v);
        if (i >= 8) {
            /* Triangle advances one 4000-frame cycle, starting at zero on the upstroke. */
            double t = (i % 4000u) / 4000.;
            double lfo = t <= .25 ? 4. * t : t <= .75 ? 2. - 4. * t : 4. * t - 4.;
            double position = 3. * fmin(1., fmax(0., .5 + .75 * lfo));
            int a = (int)position;
            double amp = amplitude[a];
            if (a < 3) amp += (position - a) * (amplitude[a + 1] - amplitude[a]);
            double expected = amp * sin(TEST_TAU * i / 64.) / 128.;
            assert(fabs((double)x.l - expected) < .012);
        }
    }
    p.scan_hz = 0.f; /* nonzero depth alone must not leave the sweep stuck on an offset */
    p.morph = 1.f;
    studio_voice_update_wavetable(v, &p);
    for (unsigned i = 9000; i < 9700; ++i) {
        Stereo x = voice_frame(v);
        if (i >= 9600) assert(fabs(x.l + .5 * sin(TEST_TAU * i / 64.)) < .006);
    }
    studio_voice_clear(v);
    studio_wavetable_destroy(table);

    const int silent[4] = {0};
    table = prepare_test_wave(93, silent, 0);
    assert(studio_voice_start_wavetable(v, &p, table, 4, 125.f, 1.f, false));
    for (unsigned i = 0; i < 100; ++i) assert(voice_frame(v).l == 0.f);
    studio_voice_destroy(v);
    studio_wavetable_destroy(table);
}

typedef struct Spectrum {
    double re, im;
} Spectrum;

static void project_bin(Spectrum *bin, float sample, double hz, unsigned n) {
    double phase = (TEST_TAU / RATE) * hz * n;
    bin->re += sample * cos(phase);
    bin->im += sample * sin(phase);
}

static double bin_amplitude(Spectrum bin, unsigned frames) {
    return 2. * hypot(bin.re, bin.im) / frames;
}

static void test_wavetable_mip(void) {
    const int amplitude[4] = {32, 32, 32, 32};
    StudioWavetable *table = prepare_test_wave(0, amplitude, 64);
    StudioVoice *v = new_voice();
    const StudioWavetablePatch p = {.level = 1.f};
    const double notes[] = {312.5, 375., 437.5, 562.5};
    const double upper_peak[] = {.5, .4167, .0694, 0.};
    enum { FFT_FRAMES = 2048 };
    for (unsigned test = 0; test < 4; ++test) {
        double hz = notes[test];
        /* The last step is a held-note retune across the mip boundary, not a restart. */
        if (test == 3) studio_voice_pitch(v, (float)hz);
        else {
            studio_voice_clear(v);
            assert(studio_voice_start_wavetable(v, &p, table, 0, (float)hz, 1.f, false));
        }
        for (unsigned i = 0; i < 768; ++i) voice_frame(v);
        double upper = test == 3 ? RATE - 8. * hz : 8. * hz;
        Spectrum fundamental = {0}, harmonic = {0};
        for (unsigned i = 0; i < FFT_FRAMES; ++i) {
            float x = voice_frame(v).l;
            project_bin(&fundamental, x, hz, i);
            project_bin(&harmonic, x, upper, i);
        }
        assert(fabs(bin_amplitude(fundamental, FFT_FRAMES) - .25) < .01);
        assert(fabs(bin_amplitude(harmonic, FFT_FRAMES) - upper_peak[test]) < .015);
    }
    studio_voice_clear(v);
    studio_wavetable_destroy(table);

    int8_t points[4 * 64];
    for (unsigned i = 0; i < sizeof(points); ++i) points[i] = i % 2 ? -64 : 64;
    table = studio_wavetable_prepare(&tables, points);
    assert(table && studio_voice_start_wavetable(v, &p, table, 0, 62.5f, 1.f, false));
    /* Authored Nyquist cosine is one bin, not doubled or dropped. */
    for (unsigned i = 0; i < 64; ++i) {
        float x = voice_frame(v).l;
        if (i >= 8) near(x, i % 2 ? 0.f : i % 4 ? -.5f : .5f);
    }
    studio_voice_destroy(v);
    studio_wavetable_destroy(table);
}

static void test_wavetable_rebind(void) {
    const int sine[4] = {64, 64, 64, 64}, inverted[4] = {-64, -64, -64, -64};
    const int dc[4] = {0};
    StudioWavetable *old = prepare_test_wave(0, sine, 0);
    StudioWavetable *final = prepare_test_wave(0, inverted, 0);
    StudioWavetable *silent = prepare_test_wave(83, dc, 0);
    StudioVoice *v = new_voice(), *ref = new_voice();
    const StudioWavetablePatch p = {.morph = .38f, .scan_hz = 3.f, .scan_depth = .5f, .level = 1.f};
    assert(studio_voice_start_wavetable(v, &p, old, 7, 125.f, 1.f, false));
    assert(studio_voice_start_wavetable(ref, &p, final, 7, 125.f, 1.f, false));
    Stereo before = {0};
    for (unsigned i = 0; i < 81; ++i) { before = voice_frame(v); voice_frame(ref); }
    StudioWavetableSet next = {0};
    for (unsigned edit = 0; edit < 7; ++edit) {
        /* Alternate unrelated allocations before each earlier correction could finish. */
        StudioWavetable *replacement = edit == 6 ? final : prepare_test_wave(0, edit % 2 ? sine : inverted, 0);
        next.slot[7] = replacement;
        studio_voice_rebind_wavetables(v, &next);
        studio_wavetable_destroy(old); /* next render under ASan must not touch it */
        old = replacement;
        for (unsigned frame = 0; frame < 5; ++frame) {
            Stereo x = voice_frame(v);
            voice_frame(ref);
            if (frame == 0) assert(x.l == before.l && x.r == before.r);
            before = x;
        }
    }
    for (unsigned i = 0; i < 80; ++i) {
        Stereo x = voice_frame(v), y = voice_frame(ref);
        if (i >= 32) near(x.l, y.l); /* no phase/envelope restart or accumulated corrections */
    }
    studio_voice_destroy(ref);
    studio_voice_clear(v);

    /* An outgoing slot differs from the current one. Remove it while it contributes. */
    assert(studio_voice_start_wavetable(v, &p, final, 7, 125.f, 1.f, false));
    for (unsigned i = 0; i < 80; ++i) voice_frame(v);
    assert(studio_voice_start_wavetable(v, &p, silent, 3, 125.f, 1.f, false));
    before = voice_frame(v);
    assert(fabsf(before.l) > .45f);
    next.slot[3] = silent;
    next.slot[7] = NULL;
    studio_voice_rebind_wavetables(v, &next);
    studio_wavetable_destroy(final);
    for (unsigned i = 0; i < 35; ++i) {
        Stereo x = voice_frame(v);
        near(x.l, before.l * fmaxf(0.f, (31.f - i) / 32.f));
    }
    studio_voice_rebind_wavetables(v, NULL);
    studio_wavetable_destroy(silent);
    for (unsigned i = 0; i < 34; ++i) assert(voice_frame(v).l == 0.f);
    assert(!studio_voice_active(v));
    studio_voice_destroy(v);
}

static void test_pluck_resonator(void) {
    StudioVoice *v = new_voice(), *ref = new_voice();
    StudioPluckPatch p = {.inharmonicity = 1, .strike = 1, .damping = .5f,
                         .decay_seconds = .75f, .release_seconds = .05f, .level = 1};
    assert(studio_voice_start_pluck(v, &p, 125.f, 1.f, false));
    assert(studio_voice_start_pluck(ref, &p, 125.f, 1.f, false));
    const double ratios[] = {1., 2.76, 5.37}, weights[] = {1., .78, .74};
    const double rt60[] = {.75, .75 * .575, .75 * .435};
    for (unsigned i = 0; i < 640; ++i) {
        if (i == 123) {
            p.strike = 0;
            studio_voice_update_pluck(v, &p);
            assert(studio_voice_start_pluck(v, &p, 125.f, 1.f, true));
        }
        Stereo a = voice_frame(v), b = voice_frame(ref);
        assert(a.l == a.r && a.l == b.l);
        if (i >= 8) {
            double expected = 0.;
            for (unsigned mode = 0; mode < 3; ++mode) {
                double time = (double)i / RATE;
                expected += weights[mode] * pow(.001, time / rt60[mode]) *
                            sin(TEST_TAU * 125. * ratios[mode] * time);
            }
            assert(fabs(a.l - expected * (.9 / 2.52)) < .0003);
        }
    }
    assert(studio_voice_start_pluck(v, &p, 1700.f, 1.f, true));
    near(voice_frame(v).l, voice_frame(ref).l); /* tied retune starts without a phase reset */
    for (unsigned i = 0; i < 540; ++i) voice_frame(v);
    unsigned high = crossings(v, RATE / 10);
    assert(high >= 168 && high <= 172); /* upper resonators are muted rather than folded */
    studio_voice_release(v);
    for (unsigned i = 0; i < 420; ++i) voice_frame(v);
    assert(!studio_voice_active(v));
    studio_voice_clear(v);
    studio_voice_clear(ref);

    /* At 2 kHz the upper modes are outside the passband; directly check the root RT60. */
    p.decay_seconds = .2f;
    assert(studio_voice_start_pluck(v, &p, 2000.f, 1.f, false));
    float earlier = 0.f;
    for (unsigned i = 0; i <= 1609; ++i) {
        Stereo x = voice_frame(v);
        if (i == 9) earlier = x.l;
        if (i == 1609) near(x.l, earlier * .001f);
    }
    assert(earlier > .5f && studio_voice_active(v));
    for (unsigned i = 0; i < 560; ++i) voice_frame(v);
    assert(!studio_voice_active(v));

    assert(studio_voice_start_pluck(v, &p, 125.f, 1.f, false));
    for (unsigned i = 0; i < 51; ++i) voice_frame(v);
    studio_voice_cut(v);
    assert(studio_voice_active(v)); /* only a retiring note, not a valid tie target */
    assert(studio_voice_start_pluck(v, &p, 200.f, 1.f, true));
    assert(studio_voice_start_pluck(ref, &p, 200.f, 1.f, false));
    for (unsigned i = 0; i < 75; ++i) {
        Stereo a = voice_frame(v), b = voice_frame(ref);
        if (i >= 32) near(a.l, b.l);
    }
    studio_voice_cut(v);
    for (unsigned i = 0; i < 32; ++i) voice_frame(v);
    assert(!studio_voice_active(v));
    studio_voice_destroy(v);
    studio_voice_destroy(ref);
}

int main(void) {
    studio_dsp_prepare_tables(&tables);
    test_delay();
    test_delay_automation();
    test_delay_block_continuation();
    test_other_fx_blocks();
    test_drive_and_crusher();
    test_reverb_chorus();
    test_sampler();
    test_sampler_track_local();
    test_fm();
    test_drums();
    test_finite_extremes();
    test_wavetable_morph_scan();
    test_wavetable_mip();
    test_wavetable_rebind();
    test_pluck_resonator();
    puts("Studio DSP: echo timing/automation/clear, drive/order, quantize/hold, stereo tails/chorus,"
         " pitched/reverse/looping PCM, FM legato, deterministic drums, wavetable morph/scan/mips/rebind,"
         " modal pluck pitch/RT60/ties and finite extremes OK");
    return 0;
}
