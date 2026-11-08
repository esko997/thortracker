/* Host regression test for the NES + Sunsoft 5B build ("nes-famistudio-s5b"): the 5B audio unit (nes_s5b.c) against
 * the NESdev "Sunsoft 5B audio" description, and the FamiStudio sound engine 4.5.3 assembled with
 * FAMISTUDIO_EXP_S5B = 1 (plus Thor Tracker's `$5F` extension) playing FamiStudio's own export of
 * tools/audio/nes/s5b_test.txt and the same rows in the Thor Tracker image layout (nes_s5b_song.h): identical register
 * writes (APU and 5B) through two passes, row timing, the squares' periods and pitch, the mixer and the hardware
 * envelope's auto period as the engine computes them, seek (the 5B's phases, LFSR and envelope run through it), live
 * relocation and edits (the hardware envelope settings follow edited instruments at once), audition, mute, the image
 * rules of the 5B build (with `$5F`) and the chip engine interface; and the Kotlin lowering's identical manual-period
 * copies (s5b-manual-copies.nesimg): the actual slot held, the shared period kept. The order fallback's held
 * instruments: nes_s5b_fallback_test.c.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir] (after `tools/build.sh
 * exportSamples`; the Kotlin image is read from app/build/samples, or the directory THOR_SAMPLES names).
 * Writes <dir>/nes_s5b.wav (48 kHz stereo, 30 s), <dir>/nes_s5b_trace.txt (per-frame APU + 5B writes) and
 * <dir>/nes_s5b.nsf (the 5B player + FamiStudio's export, NSF expansion byte $7B = $20). */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "blip_buf.h"
#include "chip_engine.h"
#include "nes_apu.h"
#include "nes_ft_song.h"
#include "nes_machine.h"
#include "nes_s5b.h"
#include "nes_s5b_song.h"
#include "nes_vrc6_song.h"

static int failures = 0;
static int checks = 0;
#define EXPECT(cond, ...)                                              \
    do {                                                               \
        ++checks;                                                      \
        if (!(cond)) {                                                 \
            ++failures;                                                \
            printf("  FAIL %s:%d: ", __FILE__, __LINE__);              \
            printf(__VA_ARGS__);                                       \
            printf("\n");                                              \
        }                                                              \
    } while (0)

#define CPU_HZ 1789773.0
#define RATE 48000

/* Engine channels of the 5B build. */
#define CH_SQ1 5
#define CH_SQ2 6
#define CH_SQ3 7

#define FIRST_PASS_ROWS (S5B_TEST_ORDERS * S5B_TEST_ROWS)
#define LOOP_ROWS ((S5B_TEST_ORDERS - S5B_TEST_LOOP) * S5B_TEST_ROWS)

static float g_out[NM_MAX_TICK_FRAMES * 2];
static NesImage g_raw, g_thor, g_moved, g_edited;

static double now_us(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e6 + t.tv_nsec / 1e3;
}

static void write_wav(const char* path, const float* data, int frames) {
    FILE* f = fopen(path, "wb");
    if (!f) return;
    int bytes = frames * 4;
    int hdr[] = {0x46464952, 36 + bytes, 0x45564157, 0x20746d66, 16, 0x00020001, RATE, RATE * 4, 0x00100004,
                 0x61746164, bytes};
    fwrite(hdr, sizeof hdr, 1, f);
    for (int i = 0; i < frames * 2; ++i) {
        float v = data[i] < -1 ? -1 : data[i] > 1 ? 1 : data[i];
        short s = (short)lrintf(v * 32767);
        fwrite(&s, 2, 1, f);
    }
    fclose(f);
}

/* Frequency from rising mean crossings of a mono signal. A crossing counts only after the signal has been below the
 * mean by a quarter of its half swing, so the band-limited steps' ringing near the mean (a square's plateau sits there
 * while the output's DC settles) adds no crossings. */
static double zero_cross_hz(const float* x, int n, int rate) {
    double mean = 0, lo = x[0], hi = x[0];
    for (int i = 0; i < n; ++i) {
        mean += x[i];
        if (x[i] < lo) lo = x[i];
        if (x[i] > hi) hi = x[i];
    }
    mean /= n;
    const double arm = 0.25 * (hi - lo) / 2;
    int first = -1, count = 0, armed = 0;
    double first_t = 0, last_t = 0;
    for (int i = 1; i < n; ++i) {
        double a = x[i - 1] - mean, b = x[i] - mean;
        if (b < -arm) armed = 1;
        if (armed && a < 0 && b >= 0) {
            armed = 0;
            double t = (i - 1) + a / (a - b);
            if (first < 0) {
                first = i;
                first_t = t;
            } else {
                last_t = t;
                ++count;
            }
        }
    }
    if (count < 2) return 0;
    return count * (double)rate / (last_t - first_t);
}

/* ---- 5B unit checks (NESdev "Sunsoft 5B audio") -------------------------------------------- */

/* Register r = v through the bus at the unit's current time ($C000 select, $E000 data). */
static void reg_write(NesS5b* s, uint8_t r, uint8_t v) {
    nes_s5b_write(s, s->time, 0xC000, r);
    nes_s5b_write(s, s->time, 0xE000, v);
}

/* `n` generator clocks, one at a time (from a reset unit at time 0 each 16-cycle step holds exactly one clock); the
 * frame rolls over before the frame-relative time grows large. */
static void clocks(NesS5b* s, uint32_t n) {
    while (n--) {
        nes_s5b_run(s, s->time + NES_S5B_CLOCK_DIVIDER);
        if (s->time >= 29000) nes_s5b_end_frame(s, s->time);
    }
}

/* Square output flips of channel c over n clocks. */
static int count_flips(NesS5b* s, int c, uint32_t n) {
    int flips = 0;
    uint8_t last = s->square[c];
    for (uint32_t i = 0; i < n; ++i) {
        clocks(s, 1);
        if (s->square[c] != last) {
            ++flips;
            last = s->square[c];
        }
    }
    return flips;
}

static void set_tone(NesS5b* s, int c, int period) {
    reg_write(s, (uint8_t)(2 * c + 1), (uint8_t)(period >> 8));
    reg_write(s, (uint8_t)(2 * c), (uint8_t)period);
}

/* The 17-bit LFSR step as NESdev states it: taps at bits 16 and 13 counted from the output end, i.e. the new top
 * bit is bit 0 ^ bit 3 of the register shifted right. */
static uint32_t ref_lfsr(uint32_t r) { return (r >> 1) | (((r & 1u) ^ ((r >> 3) & 1u)) << 16); }

/* Envelope level after `k` steps from a $0D write of `shape` (NESdev's shape table), and whether it holds. */
static int ref_envelope(int shape, int k, int* held) {
    const int attack = (shape >> 2) & 1;
    *held = 0;
    if (k < 32) return attack ? k : 31 - k;
    const int phase = k % 32, phase64 = k % 64;
    if (!(shape & 8)) {
        *held = 1;
        return 0;
    }
    switch (shape) {
    case 0x08: return 31 - phase;
    case 0x09: *held = 1; return 0;
    case 0x0A: return phase64 < 32 ? 31 - phase64 : phase64 - 32; /* \/\/ (k = 32..63 rises) */
    case 0x0B: *held = 1; return 31;
    case 0x0C: return phase;
    case 0x0D: *held = 1; return 31;
    case 0x0E: return phase64 < 32 ? phase64 : 63 - phase64; /* /\/\ (k = 32..63 falls) */
    default: *held = 1; return 0; /* $0F */
    }
}

static int same_unit(const NesS5b* a, const NesS5b* b) {
    return !memcmp(a->reg, b->reg, sizeof a->reg) && a->select == b->select && a->prescale == b->prescale &&
           !memcmp(a->square, b->square, sizeof a->square) && !memcmp(a->tone_count, b->tone_count, sizeof a->tone_count) &&
           a->noise_count == b->noise_count && a->noise_half == b->noise_half && a->lfsr == b->lfsr &&
           a->env_count == b->env_count && a->env_level == b->env_level && a->env_up == b->env_up &&
           a->env_held == b->env_held && a->time == b->time;
}

static void test_s5b_tone_noise(void) {
    printf("5B unit: bus latch, tone counters, noise LFSR\n");
    static NesS5b s;
    memset(&s, 0, sizeof s);
    nes_s5b_reset(&s);
    EXPECT(nes_s5b_register(0xC000) && nes_s5b_register(0xDFFF) && nes_s5b_register(0xE000) && nes_s5b_register(0xFFFF) &&
               !nes_s5b_register(0xBFFF),
           "5B audio addresses: $C000-$FFFF");
    /* Latch: a select with bits 4-7 set disables data writes until a clean select. */
    reg_write(&s, 2, 0x34);
    nes_s5b_write(&s, 0, 0xC000, 0x12);
    nes_s5b_write(&s, 0, 0xE000, 0x56);
    const int ignored = s.reg[2] == 0x34;
    nes_s5b_write(&s, 0, 0xDFFF, 0x02); /* any address of the range selects */
    nes_s5b_write(&s, 0, 0xFFFF, 0x56);
    EXPECT(ignored && s.reg[2] == 0x56, "select $12 ignores the data write; select $02 (at $DFFF) then $FFFF writes ($%02X)", s.reg[2]);
    reg_write(&s, 1, 0xFF);
    reg_write(&s, 6, 0xFF);
    reg_write(&s, 7, 0xFF);
    reg_write(&s, 8, 0xFF);
    reg_write(&s, 13, 0xFF);
    EXPECT(s.reg[1] == 0x0F && s.reg[6] == 0x1F && s.reg[7] == 0x3F && s.reg[8] == 0x1F && s.reg[13] == 0x0F,
           "register widths: 4-bit tone high, 5-bit noise, 6-bit mixer, 5-bit volume, 4-bit shape");

    /* Tone: an up-counter clocked every 16 CPU cycles; a reach of the period (0 as 1) flips: f = CPU / (32 P). */
    const int periods[] = {0, 1, 2, 5, 100, 4095};
    for (size_t i = 0; i < sizeof periods / sizeof periods[0]; ++i) {
        nes_s5b_reset(&s);
        set_tone(&s, 1, periods[i]);
        const int p = periods[i] ? periods[i] : 1;
        const uint32_t n = (uint32_t)p * 40u;
        const int flips = count_flips(&s, 1, n);
        EXPECT(flips == 40, "tone period %d: %d flips in %u clocks (one per %d clocks: %.2f Hz)", periods[i], flips, n, p,
               CPU_HZ / (32.0 * p));
    }
    /* Shortening the period below the count flips at the next clock, then counts the new period from 0. */
    nes_s5b_reset(&s);
    set_tone(&s, 0, 1000);
    const int before = count_flips(&s, 0, 500);
    const uint8_t sq = s.square[0];
    set_tone(&s, 0, 10);
    clocks(&s, 1);
    const int immediate = s.square[0] != sq;
    const int after = count_flips(&s, 0, 100);
    EXPECT(before == 0 && immediate && after == 10, "period 1000 -> 10 at count 500: flip at the next clock, then every 10 (%d, %d)",
           immediate, after);

    /* Noise: each reach of the 5-bit period toggles a /2 prescaler; its rising toggle steps the LFSR (seed 1). */
    uint32_t r = 1, period = 0;
    do {
        r = ref_lfsr(r);
        ++period;
    } while (r != 1 && period < 200000);
    EXPECT(period == 131071, "the 17-bit LFSR (bit 0 ^ bit 3 into bit 16) has the maximal period 2^17 - 1 (%u)", period);
    nes_s5b_reset(&s);
    EXPECT(nes_s5b_lfsr(&s) == 1 && nes_s5b_noise(&s) == 0, "power-on: LFSR 1, noise output !bit 0 = 0");
    reg_write(&s, 6, 0); /* period 0 acts as 1: a step every 2 clocks */
    clocks(&s, 1);
    const uint32_t first = nes_s5b_lfsr(&s);
    clocks(&s, 1);
    const uint32_t second = nes_s5b_lfsr(&s);
    EXPECT(first == ref_lfsr(1) && second == first, "first step on the first reach (rising toggle), none on the second ($%05X)", first);
    int seq_ok = 1;
    r = first;
    for (int k = 0; k < 64; ++k) {
        clocks(&s, 2);
        r = ref_lfsr(r);
        seq_ok &= nes_s5b_lfsr(&s) == r && nes_s5b_noise(&s) == (int)(~r & 1u);
    }
    EXPECT(seq_ok, "65 steps: the reference sequence and its output bits");
    nes_s5b_reset(&s);
    reg_write(&s, 6, 7);
    r = 1;
    for (int k = 0; k < 1000; ++k) r = ref_lfsr(r);
    clocks(&s, 2 * 7 * 1000);
    EXPECT(nes_s5b_lfsr(&s) == r, "noise period 7: 1000 steps in 14000 clocks");
    nes_s5b_reset(&s);
    clocks(&s, 2u * 131071u);
    EXPECT(nes_s5b_lfsr(&s) == 1, "period 0: back to the seed after 2 x 131071 clocks");
}

static void test_s5b_envelope(void) {
    printf("5B unit: envelope shapes, hold, $0D reset\n");
    static NesS5b s;
    memset(&s, 0, sizeof s);
    for (int step_clocks = 1; step_clocks <= 3; step_clocks += 2) {
        for (int shape = 0; shape < 16; ++shape) {
            nes_s5b_reset(&s);
            reg_write(&s, 11, (uint8_t)step_clocks);
            reg_write(&s, 12, 0);
            reg_write(&s, 13, (uint8_t)shape);
            int ok = 1, held_ok = 1, first_bad = -1;
            for (int k = 0; k < 200; ++k) {
                int held;
                const int want = ref_envelope(shape, k, &held);
                if (nes_s5b_envelope_level(&s) != want && first_bad < 0) first_bad = k;
                ok &= nes_s5b_envelope_level(&s) == want;
                if (k >= 32) held_ok &= nes_s5b_envelope_holding(&s) == held;
                clocks(&s, (uint32_t)step_clocks);
            }
            EXPECT(ok && held_ok, "shape $%X, a step every %d clocks: NESdev's 200-step level sequence (first difference at step %d)",
                   shape, step_clocks, first_bad);
        }
    }
    /* Period 0 acts as 1. */
    nes_s5b_reset(&s);
    reg_write(&s, 13, 0x08);
    clocks(&s, 5);
    EXPECT(nes_s5b_envelope_level(&s) == 26, "envelope period 0 = 1: 5 steps in 5 clocks (%d)", nes_s5b_envelope_level(&s));
    /* A $0D write restarts the envelope at once: counter 0, level by the attack bit. */
    nes_s5b_reset(&s);
    reg_write(&s, 11, 10);
    reg_write(&s, 13, 0x0C);
    clocks(&s, 75); /* 7 steps and 5 clocks into the next */
    const int mid = nes_s5b_envelope_level(&s);
    reg_write(&s, 13, 0x0A);
    const int reset_level = nes_s5b_envelope_level(&s);
    clocks(&s, 9);
    const int still = nes_s5b_envelope_level(&s);
    clocks(&s, 1);
    EXPECT(mid == 7 && reset_level == 31 && still == 31 && nes_s5b_envelope_level(&s) == 30,
           "rewriting $0D mid-ramp: level 31 at once, the next step a full period later (%d %d %d %d)", mid, reset_level, still,
           nes_s5b_envelope_level(&s));
    /* A 16-bit period. */
    nes_s5b_reset(&s);
    reg_write(&s, 11, 0x00);
    reg_write(&s, 12, 0x01);
    reg_write(&s, 13, 0x08);
    clocks(&s, 255);
    const int l255 = nes_s5b_envelope_level(&s);
    clocks(&s, 1);
    EXPECT(l255 == 31 && nes_s5b_envelope_level(&s) == 30, "envelope period $0100: the first step at clock 256");
}

static void test_s5b_mixer_dac(void) {
    printf("5B unit: volume, envelope bit, mixer AND and DC, DAC, mix level, mute\n");
    static NesS5b s;
    memset(&s, 0, sizeof s);
    nes_s5b_reset(&s);
    reg_write(&s, 7, 0x3F); /* tone and noise off everywhere: constant levels */
    reg_write(&s, 8, 0x0F);
    reg_write(&s, 9, 0x05);
    reg_write(&s, 10, 0x00);
    EXPECT(nes_s5b_channel_level(&s, 0) == 31 && nes_s5b_channel_level(&s, 1) == 11 && nes_s5b_channel_level(&s, 2) == 1,
           "fixed volume V: 5-bit level 2V + 1 (15 -> 31, 5 -> 11, 0 -> 1)");
    EXPECT(nes_s5b_dac(1) == 0.0 && nes_s5b_dac(0) == 0.0 && nes_s5b_dac(31) == 1.0, "DAC: levels 0 and 1 silent, 31 full");
    int mono = 1, steps_ok = 1;
    for (int l = 2; l < 32; ++l) {
        mono &= nes_s5b_dac(l) > nes_s5b_dac(l - 1);
        steps_ok &= fabs(20.0 * log10(nes_s5b_dac(l) / nes_s5b_dac(l == 2 ? 31 : l - 1)) - (l == 2 ? -43.5 : 1.5)) < 1e-4;
    }
    EXPECT(mono && steps_ok, "DAC: monotonic, 1.5 dB per step from level 2 (-43.5 dB) to 31");
    reg_write(&s, 8, 0x10);
    reg_write(&s, 11, 1);
    reg_write(&s, 13, 0x0E);
    int env_ok = 1;
    for (int k = 0; k < 40; ++k) {
        env_ok &= nes_s5b_channel_level(&s, 0) == nes_s5b_envelope_level(&s);
        clocks(&s, 1);
    }
    EXPECT(env_ok, "volume bit 4 (E): the channel's level is the envelope's");
    /* Mixer: on = (tone off | square) & (noise off | noise). */
    reg_write(&s, 8, 0x0F);
    set_tone(&s, 0, 3);
    reg_write(&s, 6, 1);
    const uint8_t mixers[4] = {0x36, 0x3E, 0x37, 0x3F}; /* channel A: tone+noise, tone, noise, neither */
    for (int i = 0; i < 4; ++i) {
        reg_write(&s, 7, mixers[i]);
        int ok = 1, on = 0, off = 0;
        for (int k = 0; k < 3000; ++k) {
            clocks(&s, 1);
            const int tone = (mixers[i] & 1) || s.square[0];
            const int noise = (mixers[i] & 8) || nes_s5b_noise(&s);
            const int level = nes_s5b_channel_level(&s, 0);
            ok &= level == (tone && noise ? 31 : 0);
            on += level != 0;
            off += level == 0;
        }
        EXPECT(ok && on > 0 && (i == 3 ? off == 0 : off > 0), "mixer $%02X: channel A = (tone off | square) & (noise off | noise) (%d on, %d off)",
               mixers[i], on, off);
    }
    /* Mix level: one channel at full = a 2A03 pulse at 15 x FamiStudio's ratio; mutes leave it out. */
    reg_write(&s, 7, 0x3F);
    reg_write(&s, 8, 0x0F);
    reg_write(&s, 9, 0x00);
    reg_write(&s, 10, 0x00);
    const double pulse15 = 95.88 / (8128.0 / 15.0 + 100.0);
    EXPECT(fabs(nes_s5b_mix_level(&s) - NES_S5B_LEVEL) < 1e-6 && fabs(NES_S5B_LEVEL / pulse15 - 1.56563) < 1e-6 &&
               fabs(0.793774 * 4080.0 / 12240.0 / 0.169 - NES_S5B_RATIO) < 1e-4,
           "one channel at full: %.5f = 2A03 pulse at 15 x 1.56563", nes_s5b_mix_level(&s));
    nes_s5b_set_mute(&s, 1);
    EXPECT(nes_s5b_mix_level(&s) == 0.0f && nes_s5b_channel_level(&s, 0) == 31, "mute leaves the channel out of the mix, running");
    nes_s5b_set_mute(&s, 0);
}

/* The same register writes on a silent unit and on one with output: the same state after every frame. Then the
 * synthesized pitch of a held tone. */
static void test_s5b_silent_and_output(void) {
    printf("5B unit: silent (arithmetic) and audible (clocked) runs agree; synthesized pitch\n");
    static NesS5b quiet, loud;
    memset(&quiet, 0, sizeof quiet);
    memset(&loud, 0, sizeof loud);
    blip_t* blip = blip_new(RATE / 10);
    blip_set_rates(blip, CPU_HZ, RATE);
    nes_s5b_reset(&quiet);
    nes_s5b_reset(&loud);
    nes_s5b_set_output(&loud, blip);
    static short pcm[RATE / 10];
    uint32_t seed = 12345;
#define RND() (seed = seed * 1103515245u + 12345u, (seed >> 16) & 0x7FFF)
    int same = 1, frames_same = 0;
    for (int f = 0; f < 600; ++f) {
        const uint32_t frame = (f & 1) ? 29781u : 29780u;
        uint32_t t = 0;
        const int writes = (int)(RND() % 12);
        for (int w = 0; w < writes; ++w) {
            t += RND() % (frame / 13);
            uint8_t r = (uint8_t)(RND() % 14), v = (uint8_t)RND();
            if (r == 11) v = (uint8_t)(v & 0x3F); /* short envelope periods: many steps */
            if (r == 12) v = (uint8_t)(v & 0x01);
            if (r == 1 || r == 3 || r == 5) v = (uint8_t)(v & 0x01);
            const uint8_t select = (RND() % 50) ? r : (uint8_t)(0x10 | r); /* sometimes a disabling select */
            nes_s5b_write(&quiet, t, 0xC000, select);
            nes_s5b_write(&loud, t, 0xC000, select);
            nes_s5b_write(&quiet, t, 0xE000, v);
            nes_s5b_write(&loud, t, 0xE000, v);
        }
        nes_s5b_end_frame(&quiet, frame);
        nes_s5b_end_frame(&loud, frame);
        blip_end_frame(blip, frame);
        blip_read_samples(blip, pcm, blip_samples_avail(blip), 0);
        const int ok = same_unit(&quiet, &loud);
        same &= ok;
        frames_same += ok;
    }
#undef RND
    EXPECT(same, "600 frames of random writes: identical counters, LFSR and envelope (%d frames equal)", frames_same);

    /* Channel C at period 254, tone only, full volume: CPU / (32 x 254) = 220.2 Hz through the band-limited buffer. */
    nes_s5b_reset(&loud);
    nes_s5b_set_output(&loud, blip);
    blip_clear(blip);
    set_tone(&loud, 2, 254);
    reg_write(&loud, 7, 0x3B);
    reg_write(&loud, 10, 0x0F);
    static float mono[RATE];
    int got = 0;
    for (int f = 0; f < 60; ++f) {
        const uint32_t frame = (f & 1) ? 29781u : 29780u;
        nes_s5b_end_frame(&loud, frame);
        blip_end_frame(blip, frame);
        const int n = blip_read_samples(blip, pcm, blip_samples_avail(blip), 0);
        for (int i = 0; i < n && got < RATE; ++i) mono[got++] = pcm[i] / 32768.0f;
    }
    /* The second half: the output's DC step at the start has settled through the buffer's high-pass. */
    const double hz = zero_cross_hz(mono + got / 2, got - got / 2, RATE), want = CPU_HZ / (32.0 * 254.0);
    float peak = 0;
    for (int i = got / 2; i < got; ++i) peak = fabsf(mono[i]) > peak ? fabsf(mono[i]) : peak;
    EXPECT(fabs(hz / want - 1) < 0.005 && peak > 0.05f, "synthesized tone %.2f Hz, want %.2f (peak %.3f)", hz, want, peak);
    blip_delete(blip);
}

/* ---- the 5B build playing the test song ----------------------------------------------------- */

static int load_fixture(NesImage* image, const uint8_t* bytes, size_t size, const char* name) {
    memset(image, 0, sizeof *image);
    memcpy(image->bytes, bytes, size);
    int r = nm_load_image(image, &nes_player_s5b);
    EXPECT(r == 0, "%s loads (%s at %u)", name, image->info.problem ? image->info.problem : "-", image->info.problem_at);
    return r;
}

static void song_row(int k, int* order, int* row, int* frames) {
    if (k >= FIRST_PASS_ROWS) k = S5B_TEST_LOOP * S5B_TEST_ROWS + (k - FIRST_PASS_ROWS) % LOOP_ROWS;
    *order = k / S5B_TEST_ROWS;
    *row = k % S5B_TEST_ROWS;
    *frames = nes_s5b_speed[*order][*row];
}

static int song_frames(int rows) {
    int total = 0, o, r, f;
    for (int k = 0; k < rows; ++k) {
        song_row(k, &o, &r, &f);
        total += f;
    }
    return total;
}

static int row_frame(int order, int row) { return song_frames(order * S5B_TEST_ROWS + row); }

static int note_table(NesMachine* m, int note) {
    return nm_peek(m, (uint16_t)(NES_S5B_FAMISTUDIO_NOTE_TABLE_LSB + note)) |
           nm_peek(m, (uint16_t)(NES_S5B_FAMISTUDIO_NOTE_TABLE_MSB + note)) << 8;
}

static int tone_period(const NesS5b* s, int k) { return s->reg[2 * k] | (s->reg[2 * k + 1] & 15) << 8; }
static int env_period_reg(const NesS5b* s) { return s->reg[11] | s->reg[12] << 8; }
static int env_period_ram(NesMachine* m) {
    return nm_peek(m, NES_S5B_FAMISTUDIO_S5B_ENV_PERIOD_LO) | nm_peek(m, NES_S5B_FAMISTUDIO_S5B_ENV_PERIOD_HI) << 8;
}
static int shape_ram(NesMachine* m, int k) { return nm_peek(m, (uint16_t)(NES_S5B_FAMISTUDIO_S5B_CHN_ENV_SHAPE + k)); }
static int octave_ram(NesMachine* m, int k) { return nm_peek(m, (uint16_t)(NES_S5B_FAMISTUDIO_S5B_CHN_ENV_OCTAVE + k)); }

/* Expansion instrument entry `j` of an image (Thor layout). */
static uint8_t* exp_entry(NesImage* image, int j) {
    return image->bytes + NM_REGION_OFFSET + (image->info.exp_instruments - NES_S5B_SONG_BASE) + NM_EXP_INSTRUMENT_SIZE * j;
}

/* famistudio_update_s5b_channel_sound's envelope period for the frame just run, from engine RAM and the squares'
 * periods: the last 5B square (in channel order) with a note and a hardware envelope sets it, its manual period
 * (octave $80) or its tone period shifted by the octave (left for negative octaves; right with the last shift
 * rounding an odd value up). -1: no square has an envelope (the registers are not written). */
static int expected_env_period(NesMachine* m, const NesS5b* s) {
    for (int k = NES_S5B_VOICES - 1; k >= 0; --k) {
        if (!nm_peek(m, (uint16_t)(NES_S5B_FAMISTUDIO_CHN_NOTE + CH_SQ1 + k)) || !(shape_ram(m, k) & 0x0F)) continue;
        if (octave_ram(m, k) == 0x80) return env_period_ram(m);
        const int octave = (int8_t)octave_ram(m, k);
        uint32_t p = (uint32_t)tone_period(s, k);
        for (int i = octave; i < 0; ++i) p = (p << 1) & 0xFFFFu;
        for (int i = octave; i > 0; --i) {
            if (i == 1 && (p & 1)) p = (p + 1) & 0xFFFFu;
            p >>= 1;
        }
        return (int)p;
    }
    return -1;
}

/* Per-frame sound register writes (APU and 5B) as (address low, address high, value). */
typedef struct {
    int frames;
    uint8_t (*w)[768];
    int* n;
} Trace;

static void trace_alloc(Trace* t, int frames) {
    t->frames = frames;
    t->w = calloc((size_t)frames, 768);
    t->n = (int*)calloc((size_t)frames, sizeof(int));
}

static void trace_free(Trace* t) {
    free(t->w);
    free(t->n);
}

static void record(NesMachine* m, Trace* t, int f) { t->n[f] = nm_write_log(m, t->w[f], 256); }

static int same_frame(const Trace* a, const Trace* b, int f) {
    return a->n[f] == b->n[f] && !memcmp(a->w[f], b->w[f], (size_t)a->n[f] * 3);
}

static int first_difference(const Trace* a, const Trace* b, int from, int to) {
    for (int f = from; f < to; ++f) {
        if (!same_frame(a, b, f)) return f;
    }
    return -1;
}

static void play_trace(NesMachine* m, const NesImage* image, Trace* t) {
    int mode;
    nm_start(m, image, 0, 0, 0, &mode);
    nm_set_apu_logging(m, 1);
    for (int f = 0; f < t->frames; ++f) {
        nm_tick_silent(m);
        record(m, t, f);
    }
}

/* The address of the first `$5E` prefix in channel c's order-0 pattern (Thor layout), or 0. */
static uint32_t find_5e(NesImage* image, int c) {
    static const int8_t operand[0x60] = {
        [0x43] = 0, [0x46] = 1, [0x47] = 1, [0x48] = 1, [0x49] = 2, [0x4A] = 0, [0x4B] = 2, [0x4C] = 0, [0x4D] = 0,
        [0x4E] = 1, [0x4F] = 1, [0x51] = 2, [0x52] = 1, [0x53] = 0, [0x54] = 1, [0x5E] = 2,
    };
    const NmView view = nm_view(image);
    const uint8_t* s = nm_region(image);
    const uint8_t* e = s + (image->info.streams[c] - NES_S5B_SONG_BASE);
    uint16_t a = nm_rd16(e + 2);
    for (int k = 0; k < e[1]; ++k) {
        NmEntry entry;
        if (nm_walk_entry(&view, a, c, &entry)) return 0;
        uint32_t p = a;
        while (p < entry.end) {
            const uint8_t b = s[p - NES_S5B_SONG_BASE];
            if (b == 0x5E) return NM_REGION_OFFSET + (p - NES_S5B_SONG_BASE);
            if ((b >= 0x80 && !(b & 1)) || (b >= 0x70 && b < 0x80)) {
                p += 1; /* instrument, volume */
            } else if (b >= 0x43 && b < 0x60 && b != 0x45 && b != 0x50) {
                p += 1u + (uint32_t)operand[b];
            } else {
                break; /* the terminator */
            }
        }
        a = entry.end;
    }
    return 0;
}

static void expect_rejected(NesImage* bad, const char* why, const char* what) {
    const int r = nm_load_image(bad, &nes_player_s5b);
    EXPECT(r != 0 && bad->info.problem && !strcmp(bad->info.problem, why), "%s: rejected with \"%s\" (%s)", what, why,
           r ? bad->info.problem : "loaded");
}

/* The first used directory entry of `kind` and the image offset of its first value byte (>= $80), or -1. */
static int first_value_of_kind(const NesImage* image, int kind, uint32_t* at) {
    const uint8_t* dir = image->bytes + NES_IMAGE_HEADER_SIZE;
    for (int u = 0; u < image->info.used_count; ++u) {
        const int i = image->info.used[u];
        if (dir[4 * i + 3] != kind) continue;
        const uint16_t addr = nm_rd16(dir + 4 * i);
        for (int k = nm_envelope_start(kind); k < dir[4 * i + 2]; ++k) {
            const uint32_t off = NM_REGION_OFFSET + (addr - NES_S5B_SONG_BASE) + (uint32_t)k;
            if (image->bytes[off] >= 0x80) {
                *at = off;
                return i;
            }
        }
    }
    return -1;
}

static void test_images(void) {
    printf("5B images: build identity, 8 streams, expansion instruments, mixer/noise envelopes, 5B opcode rules\n");
    nm_build_raw_image(&g_raw, &nes_player_s5b, nes_s5b_raw, NES_S5B_RAW_SIZE, NULL, 0, 0, S5B_TEST_ROWS, S5B_TEST_ORDERS,
                       S5B_TEST_LOOP);
    EXPECT(nm_load_image(&g_raw, &nes_player_s5b) == 0, "FamiStudio's raw 5B export loads (%s)", g_raw.info.problem ? g_raw.info.problem : "-");
    EXPECT(g_raw.info.tempo_ntsc == S5B_TEST_TEMPO_NTSC, "raw: NTSC tempo word after 7 header bytes and 8 channel pointers (%u)",
           g_raw.info.tempo_ntsc);
    load_fixture(&g_thor, nes_s5b_thor, NES_S5B_THOR_SIZE, "Thor layout");
    load_fixture(&g_moved, nes_s5b_moved, NES_S5B_MOVED_SIZE, "moved");
    load_fixture(&g_edited, nes_s5b_edited, NES_S5B_EDITED_SIZE, "edited");
    const NesImageInfo* i = &g_thor.info;
    EXPECT(i->orders == S5B_TEST_ORDERS && i->loop == S5B_TEST_LOOP && i->instruments == S5B_TEST_INSTRUMENTS &&
               i->tempo_ntsc == S5B_TEST_TEMPO_NTSC && i->exp_instruments >= NES_S5B_SONG_BASE,
           "Thor layout: %u orders, loop %u, %u instruments per table, tempo step %u, expansion table at $%04X", i->orders, i->loop,
           i->instruments, i->tempo_ntsc, i->exp_instruments);
    EXPECT(g_moved.info.exp_instruments != i->exp_instruments, "moved: the expansion table at another address ($%04X)",
           g_moved.info.exp_instruments);
    int streams_ok = 1;
    for (int c = 0; c < NES_S5B_CHANNELS; ++c) streams_ok &= i->streams[c] >= NES_S5B_SONG_BASE;
    EXPECT(streams_ok, "an order stream for each of the 8 channels");
    const uint8_t* manual = exp_entry(&g_thor, S5B_TEST_INSTRUMENT_SENVMANUAL);
    EXPECT(exp_entry(&g_thor, S5B_TEST_INSTRUMENT_SLEAD)[10] == 0 && exp_entry(&g_thor, S5B_TEST_INSTRUMENT_SENVAUTO)[10] == 0x08 &&
               exp_entry(&g_thor, S5B_TEST_INSTRUMENT_SENVAUTO)[11] == 3 && exp_entry(&g_thor, S5B_TEST_INSTRUMENT_SENVNEG)[11] == 0xFF &&
               manual[10] == 0x0E && manual[11] == 0x80 && nm_rd16(manual + 12) == 1000,
           "expansion entries: SLead no envelope, SEnvAuto $08 octave +3, SEnvNeg octave -1, SEnvManual $0E manual period 1000");
    EXPECT(nm_rd16(exp_entry(&g_edited, S5B_TEST_INSTRUMENT_SENVMANUAL) + 12) == S5B_TEST_EDITED_PERIOD &&
               exp_entry(&g_edited, S5B_TEST_INSTRUMENT_SENVAUTO)[10] == S5B_TEST_EDITED_SHAPE,
           "edited: SEnvManual's period %d, SEnvAuto's shape $%02X", S5B_TEST_EDITED_PERIOD, S5B_TEST_EDITED_SHAPE);

    /* The builds refuse each other's images. */
    static NesImage other, bad;
    memset(&other, 0, sizeof other);
    memcpy(other.bytes, nes_ft_thor, NES_FT_THOR_SIZE);
    EXPECT(nm_load_image(&other, &nes_player_s5b) != 0, "a 2A03 image on the 5B build: rejected (%s)", other.info.problem);
    memset(&other, 0, sizeof other);
    memcpy(other.bytes, nes_vrc6_thor, NES_VRC6_THOR_SIZE);
    EXPECT(nm_load_image(&other, &nes_player_s5b) != 0, "a VRC6 image on the 5B build: rejected (%s)", other.info.problem);
    other = g_thor;
    EXPECT(nm_load_image(&other, &nes_player_2a03) != 0 && (other = g_thor, nm_load_image(&other, &nes_player_vrc6) != 0),
           "a 5B image on the 2A03 and VRC6 builds: rejected");
    /* Mixer and noise kinds exist only in 5B images. */
    uint32_t at = 0;
    const int mixer_idx = first_value_of_kind(&g_thor, NM_KIND_MIXER, &at);
    EXPECT(mixer_idx >= 0, "the Thor layout has a mixer envelope (directory index %d)", mixer_idx);
    memset(&other, 0, sizeof other);
    memcpy(other.bytes, nes_vrc6_thor, NES_VRC6_THOR_SIZE);
    if (nm_load_image(&other, &nes_player_vrc6) == 0) {
        other.bytes[NES_IMAGE_HEADER_SIZE + 4 * other.info.used[0] + 3] = NM_KIND_MIXER;
        EXPECT(nm_load_image(&other, &nes_player_vrc6) != 0 && !strcmp(other.info.problem, "envelope kind outside 0..3"),
               "a mixer kind in a VRC6 image: rejected (%s)", other.info.problem);
    }

    /* Envelope values: mixer $C0/$C1/$C8/$C9, noise $C0-$DF; kinds 0..3, MIXER (5) and NOISE (6), not N163's WAVE (4). */
    if (mixer_idx >= 0) {
        bad = g_thor;
        bad.bytes[at] = 0xC2;
        expect_rejected(&bad, "mixer envelope value not $C0, $C1, $C8 or $C9", "mixer value $C2");
        bad = g_thor;
        bad.bytes[at] = 0xC9;
        EXPECT(nm_load_image(&bad, &nes_player_s5b) == 0, "mixer value $C9 (neither tone nor noise) loads");
        bad = g_thor;
        bad.bytes[NES_IMAGE_HEADER_SIZE + 4 * mixer_idx + 3] = NM_KIND_WAVE;
        expect_rejected(&bad, "envelope kind not 0..3, 5 or 6", "an N163 wave-index kind (4) in a 5B image");
        bad = g_thor;
        bad.bytes[NES_IMAGE_HEADER_SIZE + 4 * mixer_idx + 3] = NM_KIND_NOISE + 1;
        expect_rejected(&bad, "envelope kind not 0..3, 5 or 6", "directory kind 7");
    }
    const int noise_idx = first_value_of_kind(&g_thor, NM_KIND_NOISE, &at);
    EXPECT(noise_idx >= 0, "the Thor layout has a noise envelope (directory index %d)", noise_idx);
    if (noise_idx >= 0) {
        bad = g_thor;
        bad.bytes[at] = 0xE0;
        expect_rejected(&bad, "noise envelope value outside 0..31", "noise value 32");
        bad = g_thor;
        bad.bytes[at] = 0xDF;
        EXPECT(nm_load_image(&bad, &nes_player_s5b) == 0, "noise value 31 loads");
    }

    /* Expansion entries: envelope kinds, shape, octave, zero bytes, the table inside the data. */
    const int j = S5B_TEST_INSTRUMENT_SENVAUTO;
    bad = g_thor;
    memcpy(exp_entry(&bad, j) + 6, exp_entry(&g_thor, j) + 8, 2); /* mixer <- noise */
    expect_rejected(&bad, "expansion instrument envelope of the wrong kind", "a noise envelope as the mixer envelope");
    bad = g_thor;
    exp_entry(&bad, j)[10] = 0x07;
    expect_rejected(&bad, "5B envelope shape not 0 or $08-$0F", "shape $07");
    bad = g_thor;
    exp_entry(&bad, j)[10] = 0x10;
    expect_rejected(&bad, "5B envelope shape not 0 or $08-$0F", "shape $10");
    bad = g_thor;
    exp_entry(&bad, j)[10] = 0x00;
    EXPECT(nm_load_image(&bad, &nes_player_s5b) == 0, "shape 0 (no hardware envelope) loads");
    bad = g_thor;
    exp_entry(&bad, j)[11] = 0x09;
    expect_rejected(&bad, "5B auto-period octave outside -8..8 and not $80", "octave +9");
    bad = g_thor;
    exp_entry(&bad, j)[11] = 0xF7;
    expect_rejected(&bad, "5B auto-period octave outside -8..8 and not $80", "octave -9");
    bad = g_thor;
    exp_entry(&bad, j)[11] = 0xF8;
    EXPECT(nm_load_image(&bad, &nes_player_s5b) == 0, "octave -8 loads");
    bad = g_thor;
    exp_entry(&bad, j)[11] = 0x80;
    exp_entry(&bad, j)[12] = 0xFF, exp_entry(&bad, j)[13] = 0xFF;
    EXPECT(nm_load_image(&bad, &nes_player_s5b) == 0, "manual ($80) with period $FFFF loads");
    bad = g_thor;
    exp_entry(&bad, j)[15] = 1;
    expect_rejected(&bad, "5B expansion instrument bytes 14-15 not zero", "byte 15 set");
    bad = g_thor;
    bad.bytes[NM_REGION_OFFSET + 3] = 0xF0, bad.bytes[NM_REGION_OFFSET + 4] = 0xFF;
    expect_rejected(&bad, "expansion instrument table outside the data", "expansion pointer past the data");
    bad = g_raw;
    bad.bytes[NM_REGION_OFFSET + 3] = 0x00, bad.bytes[NM_REGION_OFFSET + 4] = 0x80;
    EXPECT(nm_load_image(&bad, &nes_player_s5b) != 0 && !strcmp(bad.info.problem, "expansion instrument pointer outside the data"),
           "raw: an expansion pointer below the song base rejected (%s)", bad.info.problem);

    /* Rows: `$5E` only on the 5B squares; no duty or phase reset there. */
    const uint32_t e5 = find_5e(&g_thor, CH_SQ1);
    EXPECT(e5 > 0, "5B Square 1's first pattern holds a `$5E lo hi`");
    if (e5) {
        bad = g_thor;
        bad.bytes[e5] = 0x4F;
        expect_rejected(&bad, "duty effect on a 5B channel", "duty on 5B Square 1");
        bad = g_thor;
        bad.bytes[e5] = 0x53;
        expect_rejected(&bad, "phase reset only on the pulses", "phase reset on 5B Square 1");
        /* Pulse 1 playing 5B Square 1's order-0 pattern: its `$5E` is refused there. */
        bad = g_thor;
        uint8_t* p0 = bad.bytes + NM_REGION_OFFSET + (bad.info.streams[0] - NES_S5B_SONG_BASE);
        const uint8_t* p5 = bad.bytes + NM_REGION_OFFSET + (bad.info.streams[CH_SQ1] - NES_S5B_SONG_BASE);
        memcpy(p0 + 1, p5 + 1, 3);
        expect_rejected(&bad, "5B envelope period off the 5B channels", "$5E on pulse 1");
        /* Thor Tracker's `$5F i` (instrument i, the shared period kept): 5B squares only, i inside the table. The
         * `$5E lo hi` becomes `$5F i $43` (the same three bytes; `$43` only disables the row's attack). */
        bad = g_thor;
        bad.bytes[e5] = 0x5F, bad.bytes[e5 + 1] = 0, bad.bytes[e5 + 2] = 0x43;
        EXPECT(nm_load_image(&bad, &nes_player_s5b) == 0, "`$5F 0` on 5B Square 1 loads (%s)", bad.info.problem ? bad.info.problem : "-");
        bad.bytes[e5 + 1] = (uint8_t)(S5B_TEST_INSTRUMENTS - 1);
        EXPECT(nm_load_image(&bad, &nes_player_s5b) == 0, "`$5F` with the last instrument loads");
        bad.bytes[e5 + 1] = (uint8_t)S5B_TEST_INSTRUMENTS;
        expect_rejected(&bad, "instrument beyond the table", "`$5F` past the instrument table");
        bad.bytes[e5 + 1] = 0;
        p0 = bad.bytes + NM_REGION_OFFSET + (bad.info.streams[0] - NES_S5B_SONG_BASE);
        memcpy(p0 + 1, p5 + 1, 3);
        expect_rejected(&bad, "5B period-keeping instrument off the 5B channels", "$5F on pulse 1");
    }
    /* Header problems. */
    bad = g_thor;
    bad.bytes[8] = (uint8_t)NES_SONG_BASE, bad.bytes[9] = (uint8_t)(NES_SONG_BASE >> 8);
    EXPECT(nm_load_image(&bad, &nes_player_s5b) != 0, "the 2A03 song base on the 5B build: rejected (%s)", bad.info.problem);
    bad = g_thor;
    bad.bytes[10] = (uint8_t)(NES_S5B_IMAGE_REGION_SIZE + 1), bad.bytes[11] = (uint8_t)((NES_S5B_IMAGE_REGION_SIZE + 1) >> 8);
    EXPECT(nm_load_image(&bad, &nes_player_s5b) != 0, "data larger than the 5B region: rejected (%s)", bad.info.problem);
}

static void write_evidence(NesMachine* m, const char* dir, const Trace* raw) {
    char path[512];
    static float wav[NM_MAX_TICK_FRAMES * 2 * 60 * 30];
    int mode, frames = 0;
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    for (int f = 0; f < 60 * 30; ++f) {
        int n = nm_tick(m, wav + 2 * frames);
        frames += n > 0 ? n : 0;
    }
    snprintf(path, sizeof path, "%s/nes_s5b.wav", dir);
    write_wav(path, wav, frames);
    printf("  wrote %s (%.1f s)\n", path, frames / (double)RATE);
    snprintf(path, sizeof path, "%s/nes_s5b_trace.txt", dir);
    FILE* ft = fopen(path, "w");
    if (ft) {
        fprintf(ft, "# frame: APU and 5B writes as address=value (hex), FamiStudio 4.5.3 5B player (FamiTracker tempo), s5b_test\n");
        for (int f = 0; f < raw->frames; ++f) {
            fprintf(ft, "%d:", f);
            for (int k = 0; k < raw->n[f]; ++k) {
                const uint8_t* e = raw->w[f] + 3 * k;
                fprintf(ft, " %02X%02X=%02X", e[1], e[0], e[2]);
            }
            fprintf(ft, "\n");
        }
        fclose(ft);
    }
    snprintf(path, sizeof path, "%s/nes_s5b.nsf", dir);
    FILE* fn = fopen(path, "wb");
    if (fn) {
        uint8_t h[128];
        memset(h, 0, sizeof h);
        memcpy(h, "NESM\x1A", 5);
        h[5] = 1;
        h[6] = 1;
        h[7] = 1;
        h[8] = (uint8_t)NES_S5B_PLAYER_BASE, h[9] = (uint8_t)(NES_S5B_PLAYER_BASE >> 8);
        h[10] = (uint8_t)NES_S5B_PLAYER_INIT, h[11] = (uint8_t)(NES_S5B_PLAYER_INIT >> 8);
        h[12] = (uint8_t)NES_S5B_PLAYER_PLAY, h[13] = (uint8_t)(NES_S5B_PLAYER_PLAY >> 8);
        strcpy((char*)h + 0x0E, "Thor Tracker 5B test");
        strcpy((char*)h + 0x2E, "Thor Tracker");
        strcpy((char*)h + 0x4E, "FamiStudio 4.5.3 engine");
        h[0x6E] = 0xFF, h[0x6F] = 0x40;
        h[0x78] = 0x20, h[0x79] = 0x4E;
        h[0x7B] = 0x20; /* expansion: Sunsoft 5B */
        fwrite(h, 1, sizeof h, fn);
        static uint8_t body[NES_S5B_SONG_BASE - 0x8000 + NES_S5B_RAW_SIZE];
        memset(body, 0, sizeof body);
        memcpy(body, nes_s5b_player_code, NES_S5B_PLAYER_SIZE);
        memcpy(body + (NES_S5B_SONG_BASE - 0x8000), nes_s5b_raw, NES_S5B_RAW_SIZE);
        fwrite(body, 1, sizeof body, fn);
        fclose(fn);
    }
}

/* Renders `ticks` frames from (order, row) with only `channel` audible; returns the mono samples. */
static int solo(NesMachine* m, int order, int row, int skip, int ticks, int channel, float* mono, int cap) {
    int mode, got = 0;
    nm_start(m, &g_thor, order, row, 12000, &mode);
    for (int c = 0; c < NES_S5B_CHANNELS; ++c) nm_mute_channel(m, c, c != channel);
    for (int f = 0; f < skip + ticks; ++f) {
        int n = nm_tick(m, g_out);
        if (f < skip || n <= 0) continue;
        for (int i = 0; i < n && got < cap; ++i) mono[got++] = g_out[2 * i];
    }
    return got;
}

static void test_playback(NesMachine* m, const char* dir) {
    const int pass_rows = FIRST_PASS_ROWS + 2 * LOOP_ROWS;
    const int frames = song_frames(pass_rows);
    printf("FamiStudio's 5B export of s5b_test.txt: %d rows (first pass + 2 loops), %d frames\n", pass_rows, frames);
    Trace raw, thor;
    trace_alloc(&raw, frames);
    trace_alloc(&thor, frames);
    play_trace(m, &g_raw, &raw);
    const NesS5b* s = nm_s5b(m);
    /* The mixer env value slot of each square (famistudio_channel_env + FAMISTUDIO_ENV_MIXER_IDX_OFF). */
    int mixer_slot[NES_S5B_VOICES];
    for (int k = 0; k < NES_S5B_VOICES; ++k) {
        mixer_slot[k] = nm_peek(m, (uint16_t)(NES_S5B_FAMISTUDIO_CHANNEL_ENV + CH_SQ1 + k)) + 2;
    }
    int mode, faults = 0, nans = 0, s5b_writes = 0, apu_writes = 0, bad_rows = 0, k = 0, row_start = 0;
    int shape_writes = 0, mixer_bad = 0, env_bad = 0, env_frames = 0, env_running = 0, first_env_bad = -1;
    int mixer_seen[NES_S5B_VOICES] = {0}; /* bit per combination 0, 1, 8, 9 */
    uint32_t worst_call = 0;
    double sum2 = 0, peak = 0, t0 = now_us();
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_set_apu_logging(m, 1);
    for (int f = 0; f < frames; ++f) {
        int po, pr, pt;
        nm_position(m, &po, &pr, &pt);
        if (pt == 0) {
            int o, r, len;
            song_row(k++, &o, &r, &len);
            if (po != o || pr != r || f != row_start) ++bad_rows;
            row_start += len;
        }
        int n = nm_tick(m, g_out);
        if (n <= 0) {
            ++faults;
            break;
        }
        record(m, &thor, f);
        int env_written = 0;
        for (int i = 0; i < thor.n[f]; ++i) {
            const uint8_t* e = thor.w[f] + 3 * i;
            if (e[1] == 0x40) {
                ++apu_writes;
            } else {
                ++s5b_writes;
                if (e[1] == 0xC0 && e[2] == 13) ++shape_writes;
                if (e[1] == 0xC0 && e[2] == 11) env_written = 1;
            }
        }
        /* Register $07 = the three squares' mixer envelope values (tone off bit 0, noise off bit 3) shifted by channel. */
        int mixer = 0;
        for (int q = NES_S5B_VOICES - 1; q >= 0; --q) {
            const int v = nm_peek(m, (uint16_t)(NES_S5B_FAMISTUDIO_ENV_VALUE + mixer_slot[q]));
            mixer = (mixer << 1) | v;
            mixer_seen[q] |= 1 << ((v & 1) | ((v >> 2) & 2));
        }
        mixer_bad += s->reg[7] != (mixer & 0x3F);
        const int want = expected_env_period(m, s);
        if (want >= 0) {
            ++env_frames;
            if (!env_written || env_period_reg(s) != want) {
                ++env_bad;
                if (first_env_bad < 0) first_env_bad = f;
            }
            env_running += !nes_s5b_envelope_holding(s);
        }
        if (nm_last_call_cycles(m) > worst_call) worst_call = nm_last_call_cycles(m);
        for (int i = 0; i < 2 * n; ++i) {
            if (g_out[i] != g_out[i]) ++nans;
            double v = fabs(g_out[i]);
            if (v > peak) peak = v;
            sum2 += g_out[i] * g_out[i];
        }
    }
    const double per_tick = (now_us() - t0) / frames, rms = sqrt(sum2 / (2.0 * frames * 798.7));
    EXPECT(faults == 0 && nans == 0, "no driver faults or NaNs over %d frames", frames);
    EXPECT(rms > 0.03 && peak < 1.0, "non-silent, unclipped: rms %.4f peak %.4f", rms, peak);
    EXPECT(s5b_writes > frames * 10 && apu_writes > frames * 3, "the engine writes the 5B (%d) and the APU (%d) every frame",
           s5b_writes, apu_writes);
    printf("  rms %.4f, peak %.4f, worst engine frame %u CPU cycles, %.1f us per tick (%.0fx realtime)\n", rms, peak, worst_call,
           per_tick, 16639.0 / per_tick);
    int d = first_difference(&raw, &thor, 0, frames);
    EXPECT(d < 0, "Thor layout writes the same APU and 5B registers as FamiStudio's export, all %d frames (first difference %d)",
           frames, d);
    EXPECT(bad_rows == 0 && k == pass_rows, "%d row starts at the frames the speeds give, loop to order %d (%d wrong)", k,
           S5B_TEST_LOOP, bad_rows);
    EXPECT(mixer_bad == 0 && mixer_seen[CH_SQ3 - CH_SQ1] == 15,
           "$07 = the squares' mixer envelope values every frame (%d wrong); 5B Square 3 plays tone+noise, noise, tone and neither",
           mixer_bad);
    EXPECT(env_frames > 0 && env_bad == 0 && env_running > 0 && shape_writes > 0,
           "hardware envelope: $0B/$0C = the auto (octave-shifted tone) or manual period in all %d frames with one (%d wrong, "
           "first at frame %d); %d shape writes; running in %d frames",
           env_frames, env_bad, first_env_bad, shape_writes, env_running);

    /* nm_apu_log keeps reporting only the APU. */
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_set_apu_logging(m, 1);
    nm_tick_silent(m);
    uint8_t pairs[512];
    const int n_apu = nm_apu_log(m, pairs, 256);
    nm_tick_silent(m);
    uint8_t triples[768];
    const int n_all = nm_write_log(m, triples, 256);
    int s5b_in_all = 0;
    for (int q = 0; q < n_all; ++q) s5b_in_all += triples[3 * q + 1] == 0xC0 || triples[3 * q + 1] == 0xE0;
    EXPECT(n_apu > 0 && s5b_in_all > 0, "apu_log: %d APU pairs; write_log: %d writes, %d of them 5B", n_apu, n_all, s5b_in_all);

    /* Square 1 at order 0 row 0: tone = note table + 1 (FamiStudio: the 5B is off by one against the 2A03 table). */
    nm_start(m, &g_thor, 0, 0, 12000, &mode);
    nm_tick_silent(m);
    nm_tick_silent(m);
    const int note5 = nm_peek(m, NES_S5B_FAMISTUDIO_CHN_NOTE + CH_SQ1);
    const int p = tone_period(s, 0);
    EXPECT(note5 > 0 && p == note_table(m, note5) + 1 && nm_s5b_instrument(m, CH_SQ1) == S5B_TEST_INSTRUMENT_SLEAD,
           "order 0 row 0, 5B Square 1 SLead: period %d = note table[%d] + 1 (%d)", p, note5, note_table(m, note5) + 1);
    /* The note table is the 2A03's: 12-TET A440 at note 46. */
    const double a440 = CPU_HZ / (16.0 * 440.0) - 1.0;
    EXPECT(fabs(note_table(m, 46) - a440) < 1.0, "note 46 = A440 (%d, exact %.2f)", note_table(m, 46), a440);
    /* Pitch heard: f = CPU / (32 period). */
    static float mono[NM_MAX_TICK_FRAMES * 120];
    const int cap = (int)(sizeof mono / sizeof mono[0]);
    const int got = solo(m, 0, 0, 2, 12, CH_SQ1, mono, cap);
    const double hz = zero_cross_hz(mono, got, RATE), want = CPU_HZ / (32.0 * p);
    EXPECT(fabs(hz / want - 1) < 0.01, "5B Square 1 period %d: %.2f Hz, want %.2f", p, hz, want);
    /* Order 3 row 0: the `$5E` (800) and SEnvManual's attack on the same frame: the effect keeps its period. */
    nm_start(m, &g_thor, 3, 0, 12000, &mode);
    nm_tick_silent(m);
    EXPECT(env_period_ram(m) == 800 && nm_s5b_period_instrument(m) == -1,
           "order 3 row 0: the manual period is the `$5E` value 800 (%d), from no instrument (%d)", env_period_ram(m),
           nm_s5b_period_instrument(m));

    write_evidence(m, dir, &raw);
    trace_free(&raw);
    trace_free(&thor);
}

static void test_seek(NesMachine* m) {
    printf("5B seek: exact rows; the 5B's phases, LFSR and envelope run through the fast-forward\n");
    const int frames = song_frames(FIRST_PASS_ROWS + LOOP_ROWS);
    Trace ref, t;
    trace_alloc(&ref, frames);
    trace_alloc(&t, frames);
    play_trace(m, &g_thor, &ref);
    NesMachine* straight = nm_create(RATE, &nes_player_s5b);
    const int targets[][2] = {{0, 4}, {1, 0}, {1, 8}, {2, 8}, {2, 12}, {3, 15}};
    for (size_t i = 0; i < sizeof targets / sizeof targets[0]; ++i) {
        const int o = targets[i][0], r = targets[i][1], f0 = row_frame(o, r);
        int mode = -1, po, pr, pt;
        int res = nm_start(m, &g_thor, o, r, 12000, &mode);
        nm_position(m, &po, &pr, &pt);
        EXPECT(res == NM_OK && mode == NM_SEEK_FROM_START && po == o && pr == r && pt == 0 && nm_frames(m) == (uint32_t)f0,
               "seek %d/%d: from start, %u frames (row starts at %d)", o, r, nm_frames(m), f0);
        /* Straight audible play to the same frame: the 5B unit in the same state (seeking is silent, arithmetic). */
        nm_start(straight, &g_thor, 0, 0, 0, &mode);
        for (int f = 0; f < f0; ++f) nm_tick(straight, g_out);
        EXPECT(same_unit(nm_s5b(m), nm_s5b(straight)) && nm_s5b_period_instrument(m) == nm_s5b_period_instrument(straight),
               "seek %d/%d: tone counters, LFSR, envelope and registers as after straight play", o, r);
        nm_set_apu_logging(m, 1);
        int same = 1;
        for (int f = f0; f < f0 + 200 && f < frames; ++f) {
            nm_tick_silent(m);
            record(m, &t, f);
            same &= same_frame(&ref, &t, f);
        }
        EXPECT(same, "seek %d/%d writes the same APU and 5B registers as straight play for 200 frames", o, r);
    }
    nm_destroy(straight);
    int mode = -1, o, r, tk;
    EXPECT(nm_start(m, &g_thor, 2, 8, 70, &mode) == NM_OK && mode == NM_SEEK_FROM_ORDER, "small budget: seek from order 2's start");
    nm_position(m, &o, &r, &tk);
    EXPECT(o == 2 && r == 8 && tk == 0, "fallback lands on 2/8 (%d/%d/%d)", o, r, tk);
    EXPECT(nm_tick(m, g_out) > 0, "and plays on");
    EXPECT(nm_start(m, &g_thor, S5B_TEST_ORDERS, 0, 12000, &mode) == NM_ERR_IMAGE, "order past the song rejected");
    static NesImage other;
    memset(&other, 0, sizeof other);
    memcpy(other.bytes, nes_ft_thor, NES_FT_THOR_SIZE);
    nm_load_image(&other, &nes_player_2a03);
    EXPECT(nm_start(m, &other, 0, 0, 0, &mode) == NM_ERR_IMAGE, "a 5B machine refuses an image loaded for the 2A03 build");
    trace_free(&ref);
    trace_free(&t);
}

/* The engine's hardware envelope settings: the squares' shape low nibbles and octaves, the manual period. */
typedef struct {
    int shape[NES_S5B_VOICES], octave[NES_S5B_VOICES], period;
} EnvRam;

static EnvRam env_ram(NesMachine* m) {
    EnvRam e;
    for (int k = 0; k < NES_S5B_VOICES; ++k) {
        e.shape[k] = shape_ram(m, k) & 0x0F;
        e.octave[k] = octave_ram(m, k);
    }
    e.period = env_period_ram(m);
    return e;
}

static int same_env_ram(const EnvRam* a, const EnvRam* b) {
    return !memcmp(a->shape, b->shape, sizeof a->shape) && !memcmp(a->octave, b->octave, sizeof a->octave) && a->period == b->period;
}

/* Playing `from` at (order, row) + 2 ticks, `to` applied, one tick: the hardware envelope settings equal those of
 * `to` started at (order, row) and ticked 3 times (the edit is followed as if the instruments had been loaded). */
static int follows_like_fresh(NesMachine* m, NesMachine* fresh, const NesImage* from, const NesImage* to, int order, int row) {
    int mode;
    nm_start(m, from, order, row, 12000, &mode);
    nm_tick_silent(m);
    nm_tick_silent(m);
    nm_apply_image(m, to);
    nm_tick_silent(m);
    nm_start(fresh, to, order, row, 12000, &mode);
    for (int i = 0; i < 3; ++i) nm_tick_silent(fresh);
    const EnvRam a = env_ram(m), b = env_ram(fresh);
    int same_instruments = nm_s5b_period_instrument(m) == nm_s5b_period_instrument(fresh);
    for (int c = CH_SQ1; c <= CH_SQ3; ++c) same_instruments &= nm_s5b_instrument(m, c) == nm_s5b_instrument(fresh, c);
    return same_env_ram(&a, &b) && same_instruments;
}

static void test_live_apply(NesMachine* m) {
    printf("5B live apply: relocation, edits and instrument edits at tick boundaries\n");
    const int frames = song_frames(FIRST_PASS_ROWS + 2 * LOOP_ROWS);
    Trace ref, t;
    trace_alloc(&ref, frames);
    trace_alloc(&t, frames);
    play_trace(m, &g_thor, &ref);
    int mode;
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_set_apu_logging(m, 1);
    double worst_us = 0;
    int ram_kept = 1;
    for (int f = 0; f < frames; ++f) {
        const EnvRam before = env_ram(m);
        double a0 = now_us();
        nm_apply_image(m, (f & 1) ? &g_moved : &g_thor);
        double us = now_us() - a0;
        if (us > worst_us) worst_us = us;
        const EnvRam after = env_ram(m);
        ram_kept &= same_env_ram(&before, &after);
        nm_tick_silent(m);
        record(m, &t, f);
    }
    int d = first_difference(&ref, &t, 0, frames);
    EXPECT(d < 0 && ram_kept, "moved data applied before each of %d frames: same writes as straight play, envelope settings kept (first difference %d)",
           frames, d);
    printf("  worst apply_image: %.1f us\n", worst_us);

    /* The SLead volume sustain 12 -> 8 heard on the sustaining 5B Square 1 note; order 2's edited note when reached. */
    const NesS5b* s = nm_s5b(m);
    nm_start(m, &g_thor, 0, 0, 12000, &mode);
    int sustain = 0;
    for (int i = 0; i < 20 && sustain < 2; ++i) {
        nm_tick_silent(m);
        sustain = (s->reg[8] & 0x1F) == 12 ? sustain + 1 : 0;
    }
    nm_apply_image(m, &g_edited);
    nm_tick_silent(m);
    EXPECT(sustain == 2 && (s->reg[8] & 0x1F) == 8, "sustaining SLead note follows its edited volume envelope (12 -> %d)", s->reg[8] & 0x1F);
    nm_start(m, &g_edited, 2, 8, 12000, &mode);
    nm_tick_silent(m);
    const int edited_note = nm_peek(m, NES_S5B_FAMISTUDIO_CHN_NOTE + CH_SQ1);
    nm_start(m, &g_thor, 2, 8, 12000, &mode);
    nm_tick_silent(m);
    const int original_note = nm_peek(m, NES_S5B_FAMISTUDIO_CHN_NOTE + CH_SQ1);
    nm_start(m, &g_thor, 1, 4, 12000, &mode);
    nm_tick_silent(m);
    nm_apply_image(m, &g_edited);
    int o, r, tk, heard = -1;
    for (int i = 0; i < 400 && heard < 0; ++i) {
        nm_position(m, &o, &r, &tk);
        nm_tick_silent(m);
        if (o == 2 && r == 8 && tk == 0) heard = nm_peek(m, NES_S5B_FAMISTUDIO_CHN_NOTE + CH_SQ1);
    }
    EXPECT(edited_note != original_note && heard == edited_note, "edited 5B note at order 2 row 8: %d (was %d, heard %d)", edited_note,
           original_note, heard);

    /* SEnvManual's period 1000 -> 2000 while it owns the manual period: the engine's period follows at once. */
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    int found = 0;
    for (int f = 0; f < 400 && !found; ++f) {
        nm_tick_silent(m);
        found = nm_s5b_period_instrument(m) == S5B_TEST_INSTRUMENT_SENVMANUAL;
    }
    const int period_before = env_period_ram(m);
    nm_apply_image(m, &g_edited);
    EXPECT(found && period_before == 1000 && env_period_ram(m) == S5B_TEST_EDITED_PERIOD,
           "SEnvManual's manual period edited: the engine's period %d -> %d", period_before, env_period_ram(m));
    /* SEnvAuto's shape $08 -> $0C on the square playing it: the shape nibble follows, bit 7 kept. */
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    int square = -1;
    for (int f = 0; f < 2000 && square < 0; ++f) {
        nm_tick_silent(m);
        for (int c = CH_SQ1; c <= CH_SQ3 && square < 0; ++c) {
            if (nm_s5b_instrument(m, c) == S5B_TEST_INSTRUMENT_SENVAUTO) square = c - CH_SQ1;
        }
    }
    EXPECT(square >= 0, "a square plays SEnvAuto (5B Square %d)", square + 1);
    if (square >= 0) {
        const int shape_before = shape_ram(m, square);
        nm_apply_image(m, &g_edited);
        const int shape_after = shape_ram(m, square);
        EXPECT((shape_before & 0x0F) == 0x08 && (shape_after & 0x0F) == (S5B_TEST_EDITED_SHAPE & 0x0F) &&
                   (shape_after & 0x80) == (shape_before & 0x80),
               "SEnvAuto's shape edited: the square's shape $%02X -> $%02X", shape_before, shape_after);
    }
    /* Followed edits equal a fresh start on the edited data, at rows across the song; and an octave edit, and an
     * entry turned manual (it takes over the manual period, as its own load would). */
    NesMachine* fresh = nm_create(RATE, &nes_player_s5b);
    static NesImage octave_edit, manual_edit;
    octave_edit = g_thor;
    exp_entry(&octave_edit, S5B_TEST_INSTRUMENT_SENVAUTO)[11] = 0xFE;
    manual_edit = g_thor;
    exp_entry(&manual_edit, S5B_TEST_INSTRUMENT_SENVAUTO)[11] = 0x80;
    exp_entry(&manual_edit, S5B_TEST_INSTRUMENT_SENVAUTO)[12] = 0xD2, exp_entry(&manual_edit, S5B_TEST_INSTRUMENT_SENVAUTO)[13] = 0x04;
    EXPECT(nm_load_image(&octave_edit, &nes_player_s5b) == 0 && nm_load_image(&manual_edit, &nes_player_s5b) == 0,
           "octave -2 and manual-1234 edits of SEnvAuto load");
    const int rows[][2] = {{0, 2}, {0, 9}, {1, 4}, {2, 2}, {2, 10}, {3, 2}, {3, 9}};
    for (size_t i = 0; i < sizeof rows / sizeof rows[0]; ++i) {
        const int ro = rows[i][0], rr = rows[i][1];
        EXPECT(follows_like_fresh(m, fresh, &g_thor, &g_edited, ro, rr), "%d/%d: the edited image's envelope settings as a fresh start", ro, rr);
        EXPECT(follows_like_fresh(m, fresh, &g_thor, &octave_edit, ro, rr), "%d/%d: SEnvAuto octave -2 as a fresh start", ro, rr);
        EXPECT(follows_like_fresh(m, fresh, &g_thor, &manual_edit, ro, rr), "%d/%d: SEnvAuto turned manual as a fresh start", ro, rr);
    }
    nm_destroy(fresh);
    trace_free(&ref);
    trace_free(&t);
}

static double energy(NesMachine* m, int ticks) {
    double e = 0;
    for (int i = 0; i < ticks; ++i) {
        int n = nm_tick(m, g_out);
        for (int k = 0; k < 2 * n; ++k) e += g_out[k] * g_out[k];
    }
    return e;
}

static void test_audition(NesMachine* m) {
    printf("5B audition: held notes on the 5B squares, hardware envelope, release\n");
    const NesS5b* s = nm_s5b(m);
    const int note = 46; /* A440 */
    EXPECT(nm_audition(m, &g_thor, CH_SQ1, note, S5B_TEST_INSTRUMENT_SLEAD, 0) == NM_OK, "audition 5B Square 1 SLead");
    nm_tick(m, g_out);
    EXPECT(tone_period(s, 0) == note_table(m, note) + 1 && !(s->reg[7] & 1), "first tick sounds A440 (period %d), tone on",
           tone_period(s, 0));
    double e = energy(m, 120);
    EXPECT(e > 1.0 && (s->reg[8] & 15) > 0 && !(s->reg[9] & 15) && !(nm_apu(m)->pulse[0].ctrl & 15),
           "held 2 s, other channels silent (energy %.1f)", e);
    EXPECT(nm_release(m) == 1, "SLead has a release point: release phase");
    int ticks = 0, res;
    while ((res = nm_tick(m, g_out)) > 0 && ticks < 200) ++ticks;
    EXPECT(res == 0 && ticks > 0 && ticks < 200, "the release tail ends (%d ticks)", ticks);
    EXPECT(nm_audition(m, &g_thor, CH_SQ2, note, S5B_TEST_INSTRUMENT_SLEAD, 10) == NM_OK, "audition 5B Square 2, fine +10");
    nm_tick(m, g_out);
    EXPECT(tone_period(s, 1) == note_table(m, note) + 1 + 10, "fine +10 adds 10 to the period (%d)", tone_period(s, 1));
    EXPECT(nm_audition(m, &g_thor, CH_SQ3, note, S5B_TEST_INSTRUMENT_SENVAUTO, 0) == NM_OK, "audition 5B Square 3 SEnvAuto");
    nm_tick(m, g_out);
    nm_tick(m, g_out);
    int moving = 0, level = nes_s5b_envelope_level(s);
    for (int i = 0; i < 30; ++i) {
        nm_tick(m, g_out);
        moving += nes_s5b_envelope_level(s) != level;
        level = nes_s5b_envelope_level(s);
    }
    EXPECT((s->reg[10] & 0x10) && s->reg[13] == 0x08 && !nes_s5b_envelope_holding(s) && moving > 0 &&
               nm_s5b_instrument(m, CH_SQ3) == S5B_TEST_INSTRUMENT_SENVAUTO,
           "SEnvAuto: E bit set, shape $08 written, the envelope running (%d level changes in 30 ticks)", moving);
    EXPECT(nm_audition(m, &g_thor, NES_S5B_CHANNELS, note, 0, 0) == NM_ERR_IMAGE, "channel 8 does not exist");
    EXPECT(nm_audition(m, &g_thor, CH_SQ1, note, S5B_TEST_INSTRUMENTS, 0) == NM_ERR_IMAGE, "instrument past the table rejected");
    EXPECT(nm_audition(m, &g_raw, CH_SQ1, note, 0, 0) == NM_ERR_IMAGE, "raw layout cannot audition");
}

static void test_mute(NesMachine* m) {
    printf("5B mute: the three 5B squares in the mix\n");
    int mode;
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    double e_all = 0, e_muted = 0, e_back = 0;
    for (int f = 0; f < 600; ++f) {
        if (f == 200 || f == 400) {
            for (int c = 0; c < NES_S5B_CHANNELS; ++c) nm_mute_channel(m, c, f == 200);
        }
        int n = nm_tick(m, g_out);
        double e = 0;
        for (int i = 0; i < 2 * n; ++i) e += g_out[i] * g_out[i];
        if (f < 200) e_all += e;
        else if (f >= 210 && f < 400) e_muted += e;
        else if (f >= 400) e_back += e;
    }
    EXPECT(e_muted < e_all * 1e-6 && e_back > e_all * 0.2, "mute silences all 8, unmute restores (energy %.1f, %.2g, %.1f)", e_all,
           e_muted, e_back);
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    for (int c = CH_SQ1; c <= CH_SQ3; ++c) nm_mute_channel(m, c, 1);
    EXPECT(nm_s5b(m)->mute == 7 && nm_apu(m)->mute == 0, "channels 5-7 mute the 5B unit's channels");
    EXPECT(energy(m, 200) > 0.1 && nes_s5b_mix_level(nm_s5b(m)) == 0.0f, "2A03 still heard, 5B silent in the mix");
    EXPECT(nm_mute_channel(m, NES_S5B_CHANNELS, 1) == NM_ERR_IMAGE, "channel 8 does not exist");
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    EXPECT(nm_s5b(m)->mute == 0, "start clears mutes (power-on)");
}

static void test_chip_engine_ops(void) {
    printf("chip engine interface: \"nes-famistudio-s5b\"\n");
    const ChipEngineOps* ops = chip_engine_ops("nes-famistudio-s5b");
    const ChipEngineOps* plain = chip_engine_ops("nes-famistudio");
    const ChipEngineOps* vrc6 = chip_engine_ops("nes-famistudio-vrc6");
    EXPECT(ops != NULL && plain != NULL && ops != plain && ops != vrc6, "registered beside the 2A03 and VRC6 engines");
    if (!ops) return;
    EXPECT(ops->image_size == NES_S5B_IMAGE_SIZE && plain->image_size == NES_IMAGE_SIZE && ops->image_storage >= ops->image_size,
           "image sizes %zu (2A03: %zu)", ops->image_size, plain->image_size);
    ChipImage* img = (ChipImage*)malloc(ops->image_storage);
    static uint8_t bytes[NES_IMAGE_SIZE];
    memset(bytes, 0, sizeof bytes);
    memcpy(bytes, nes_s5b_thor, NES_S5B_THOR_SIZE);
    EXPECT(ops->load_image(img, bytes, ops->image_size) == CHIP_OK, "5B image loads");
    EXPECT(ops->load_image(img, bytes, plain->image_size) == CHIP_ERR_IMAGE, "the 2A03 image size rejected");
    memset(bytes, 0, sizeof bytes);
    memcpy(bytes, nes_ft_thor, NES_FT_THOR_SIZE);
    EXPECT(ops->load_image(img, bytes, ops->image_size) == CHIP_ERR_IMAGE, "a 2A03 image cut to the 5B size rejected");
    memset(bytes, 0, sizeof bytes);
    memcpy(bytes, nes_s5b_thor, NES_S5B_THOR_SIZE);
    ops->load_image(img, bytes, ops->image_size);
    ChipMachine* cm = ops->create(RATE);
    ChipMachine* au = ops->create(RATE);
    int mode = -1, order, row, tick;
    EXPECT(ops->start(cm, img, 1, 0, 12000, &mode) == CHIP_OK && mode == CHIP_SEEK_FROM_START, "start at order 1");
    const int ticks = row_frame(1, 8) - row_frame(1, 0);
    double e = 0;
    for (int i = 0; i < ticks; ++i) {
        int n = ops->tick(cm, g_out);
        for (int k = 0; k < 2 * (n > 0 ? n : 0); ++k) e += g_out[k] * g_out[k];
    }
    ops->position(cm, &order, &row, &tick);
    EXPECT(e > 1.0 && order == 1 && row == 8 && tick == 0, "8 rows through the ops (%d/%d/%d, energy %.1f)", order, row, tick, e);
    /* The hosts pass 1-based slots. */
    EXPECT(ops->audition(au, img, CH_SQ2, 46, S5B_TEST_INSTRUMENT_SLEAD + 1, 0) == CHIP_OK, "5B square audition through the ops");
    EXPECT(ops->mute(cm, CH_SQ3, 1) == CHIP_OK && ops->mute(cm, NES_S5B_CHANNELS, 1) == CHIP_ERR_IMAGE,
           "mute the song's 5B Square 3; channel 8 rejected");
    EXPECT(ops->tick(cm, g_out) > 0 && ops->tick(au, g_out) > 0, "both machines tick");
    EXPECT(ops->release(au) == 1, "SLead has a release point");
    ops->destroy(cm);
    ops->destroy(au);
    free(img);
}

/* s5b-manual-copies.nesimg, lowered by the Kotlin compiler (exportSamples; the golden song s5b_manual_copies): slot 0 and
 * its identical copy, slot 1, are manual-period sounds (900), slot 2 Square 2's manual saw (2000). Square 1 plays slot 0
 * from order 0 row 0, Square 2 slot 2 from row 4 (the shared period 2000), Square 1 switches to the copy at row 8
 * (frame 24, `$5F 1`): FamiStudio's export, which merges the copies, loads nothing there, so the shared period stays
 * Square 2's, while Square 1 holds slot 1 itself (live edits follow the copy, not the original). Ticks 3 per row. */
static void test_manual_copies(NesMachine* m, const char* samples) {
    printf("5B identical manual-period copies: the actual slot, the shared period kept\n");
    char path[1024];
    snprintf(path, sizeof path, "%s/s5b-manual-copies.nesimg", samples);
    static NesImage original, edited;
    FILE* f = fopen(path, "rb");
    if (!f) {
        printf("  FAIL: missing %s; run exportSamples\n", path);
        ++failures;
        return;
    }
    const size_t bytes = fread(original.bytes, 1, nes_player_s5b.image_size, f);
    fclose(f);
    EXPECT(bytes == nes_player_s5b.image_size && nm_load_image(&original, &nes_player_s5b) == 0, "the Kotlin-lowered image loads (%s)",
           original.info.problem ? original.info.problem : "-");
    if (original.info.loaded != NM_LOADED) return;
    int mode;
    nm_start(m, &original, 0, 0, 12000, &mode);
    for (int f2 = 0; f2 < 24; ++f2) nm_tick_silent(m);
    EXPECT(nm_s5b_instrument(m, CH_SQ1) == 0 && nm_s5b_instrument(m, CH_SQ2) == 2 && env_period_ram(m) == 2000 &&
               nm_s5b_period_instrument(m) == 2,
           "before row 8: Square 1 on slot 0, Square 2's saw (slot 2) owns the period 2000 (%d, %d, %d, owner %d)",
           nm_s5b_instrument(m, CH_SQ1), nm_s5b_instrument(m, CH_SQ2), env_period_ram(m), nm_s5b_period_instrument(m));
    nm_tick_silent(m); /* frame 24: row 8 */
    EXPECT(nm_s5b_instrument(m, CH_SQ1) == 1, "row 8: Square 1 holds the copy's own slot 1 (%d)", nm_s5b_instrument(m, CH_SQ1));
    EXPECT(env_period_ram(m) == 2000 && env_period_reg(nm_s5b(m)) == 2000 && nm_s5b_period_instrument(m) == 2,
           "row 8: the shared period stays Square 2's 2000, not the copy's 900 (RAM %d, register %d, owner %d)", env_period_ram(m),
           env_period_reg(nm_s5b(m)), nm_s5b_period_instrument(m));
    /* A live edit of the copy's shape reaches Square 1; the original's slot is not what it plays. */
    edited = original;
    const uint8_t shape = exp_entry(&original, 1)[10] == 0x0C ? 0x08 : 0x0C;
    exp_entry(&edited, 1)[10] = shape;
    EXPECT(nm_load_image(&edited, &nes_player_s5b) == 0, "the copy's shape edited");
    nm_apply_image(m, &edited);
    EXPECT((shape_ram(m, 0) & 0x0F) == shape && env_period_ram(m) == 2000,
           "editing the held copy (slot 1) changes Square 1's shape to $%02X ($%02X), the period stays 2000 (%d)", shape,
           shape_ram(m, 0) & 0x0F, env_period_ram(m));
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    const char* samples = argc > 2 ? argv[2] : "app/build/samples";
    test_s5b_tone_noise();
    test_s5b_envelope();
    test_s5b_mixer_dac();
    test_s5b_silent_and_output();
    test_images();
    NesMachine* m = nm_create(RATE, &nes_player_s5b);
    if (!m) {
        printf("nm_create failed\n");
        return 2;
    }
    EXPECT(nm_player(m) == &nes_player_s5b && nes_player_s5b.channels == 8 && nes_player_s5b.song_base == NES_S5B_SONG_BASE &&
               nes_player_s5b.effect_envelopes == NES_S5B_EFFECT_ENVELOPES && nes_player_head(&nes_player_s5b) == 7,
           "machine for the 5B build: 8 channels, song base $%04X, effect envelopes from %d, 7 header bytes", NES_S5B_SONG_BASE,
           NES_S5B_EFFECT_ENVELOPES);
    test_playback(m, dir);
    test_seek(m);
    test_live_apply(m);
    test_audition(m);
    test_mute(m);
    test_manual_copies(m, samples);
    nm_destroy(m);
    test_chip_engine_ops();
    printf("nes_s5b_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
