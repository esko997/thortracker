/* Host regression test for the NES machine: the FamiStudio sound engine 4.5.3 player on m6502 +
 * nes_apu, playing FamiStudio's own export of a demo song (nes_demo_song.h), plus APU unit checks
 * against the NESdev-documented behaviour.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir]
 * Writes <dir>/nes_demo.wav (48 kHz stereo), <dir>/nes_trace.txt (per-frame APU register writes)
 * and <dir>/nes_demo.nsf (the same player + song bytes as an NSF, for reference players; see
 * tools/audio/nes_reference_check.py). */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "blip_buf.h"
#include "chip_engine.h"
#include "nes_apu.h"
#include "nes_demo_song.h"
#include "nes_machine.h"

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
#define SONG_FRAMES (NES_DEMO_ORDER_COUNT * NES_DEMO_ROWS_PER_ORDER * NES_DEMO_FRAMES_PER_ROW)

static float g_out[NM_MAX_TICK_FRAMES * 2];

static double now_us(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e6 + t.tv_nsec / 1e3;
}

static uint64_t fnv(uint64_t h, const void* data, size_t n) {
    const uint8_t* p = (const uint8_t*)data;
    for (size_t i = 0; i < n; ++i) h = (h ^ p[i]) * 0x100000001B3ull;
    return h;
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

/* Frequency from rising zero crossings of a mono signal (mean removed). */
static double zero_cross_hz(const float* x, int n, int rate) {
    double mean = 0;
    for (int i = 0; i < n; ++i) mean += x[i];
    mean /= n;
    int first = -1, last = -1, count = 0;
    double first_t = 0, last_t = 0;
    for (int i = 1; i < n; ++i) {
        double a = x[i - 1] - mean, b = x[i] - mean;
        if (a < 0 && b >= 0) {
            double t = (i - 1) + a / (a - b);
            if (first < 0) {
                first = i;
                first_t = t;
            } else {
                last = i;
                last_t = t;
                ++count;
            }
        }
    }
    if (count < 2 || last < 0) return 0;
    return count * (double)rate / (last_t - first_t);
}

/* ---- APU unit checks ---------------------------------------------------------------------- */

typedef struct {
    NesApu apu;
    blip_t* blip;
    uint32_t frame;
} ApuRig;

static void rig_init(ApuRig* r) {
    memset(&r->apu, 0, sizeof r->apu);
    r->blip = blip_new(RATE / 10);
    blip_set_rates(r->blip, CPU_HZ, RATE);
    nes_apu_reset(&r->apu);
    nes_apu_set_output(&r->apu, r->blip);
    r->frame = 0;
}

static void rig_free(ApuRig* r) { blip_delete(r->blip); }

/* Renders `frames` NTSC frames of the current register state into mono samples. */
static int rig_render(ApuRig* r, int frames, float* out, int cap) {
    int n = 0;
    short buf[4096];
    for (int f = 0; f < frames; ++f) {
        uint32_t cycles = (r->frame++ & 1) ? 29781 : 29780;
        nes_apu_end_frame(&r->apu, cycles);
        blip_end_frame(r->blip, cycles);
        int got = blip_read_samples(r->blip, buf, blip_samples_avail(r->blip), 0);
        for (int i = 0; i < got && n < cap; ++i) out[n++] = buf[i] / 32768.0f;
    }
    return n;
}

static void test_apu_pitch(void) {
    printf("APU: pulse and triangle pitch from timer periods\n");
    static float buf[RATE];
    ApuRig r;
    rig_init(&r);
    nes_apu_write(&r.apu, 0, 0x4015, 0x0F);
    nes_apu_write(&r.apu, 0, 0x4000, 0xBF); /* duty 50%, halt, constant volume 15 */
    nes_apu_write(&r.apu, 0, 0x4001, 0x08); /* sweep off, negate (no muting) */
    nes_apu_write(&r.apu, 0, 0x4002, 0xFD);
    nes_apu_write(&r.apu, 0, 0x4003, 0x00); /* period 253: A-4 */
    int n = rig_render(&r, 30, buf, RATE);
    double want = CPU_HZ / (16.0 * 254), got = zero_cross_hz(buf + 2000, n - 2000, RATE);
    EXPECT(fabs(got / want - 1) < 0.002, "pulse period 253: %.2f Hz, want %.2f", got, want);
    printf("  pulse 1 period 253: %.2f Hz (expected %.2f)\n", got, want);
    nes_apu_write(&r.apu, 0, 0x4015, 0x00);
    rig_render(&r, 2, buf, RATE);
    EXPECT(r.apu.pulse[0].length == 0, "$4015 disable clears the length counter");
    nes_apu_write(&r.apu, 0, 0x4015, 0x04);
    nes_apu_write(&r.apu, 0, 0x4008, 0xFF); /* control + linear 127 */
    nes_apu_write(&r.apu, 0, 0x400A, 0xFD);
    nes_apu_write(&r.apu, 0, 0x400B, 0x00);
    n = rig_render(&r, 30, buf, RATE);
    want = CPU_HZ / (32.0 * 254);
    got = zero_cross_hz(buf + 2000, n - 2000, RATE);
    EXPECT(fabs(got / want - 1) < 0.002, "triangle period 253: %.2f Hz, want %.2f", got, want);
    printf("  triangle period 253: %.2f Hz (expected %.2f)\n", got, want);
    rig_free(&r);
}

static void test_apu_counters(void) {
    printf("APU: length counter, envelope, linear counter, sweep, frame counter\n");
    /* Length: index 0 = 10 half-frame clocks; 4-step half clocks at 14913 and 29829, sequence
     * period 29830. One long frame-relative timeline (no output attached). */
    const uint32_t tenth = 4 * 29830 + 29829;
    NesApu b;
    memset(&b, 0, sizeof b);
    nes_apu_reset(&b);
    nes_apu_write(&b, 0, 0x4015, 0x0F);
    nes_apu_write(&b, 0, 0x4000, 0x9F); /* no halt */
    nes_apu_write(&b, 0, 0x4003, 0x01); /* length index 0, period $100 */
    EXPECT(b.pulse[0].length == 10, "length load 10, got %d", b.pulse[0].length);
    nes_apu_run(&b, tenth - 1);
    EXPECT(b.pulse[0].length == 1, "one clock left at cycle %u (got %d)", tenth - 1, b.pulse[0].length);
    EXPECT(nes_apu_read_status(&b, tenth - 1) & 1, "$4015 bit 0 set while length > 0");
    nes_apu_run(&b, tenth);
    EXPECT(b.pulse[0].length == 0, "10th half-frame clock at cycle %u empties it", tenth);
    EXPECT(!(nes_apu_read_status(&b, tenth) & 1), "$4015 bit 0 clear at length 0");
    EXPECT(b.frame_irq == 0, "frame IRQ flag cleared by the $4015 read");

    /* Frame IRQ flag at the end of the 4-step sequence unless inhibited. */
    memset(&b, 0, sizeof b);
    nes_apu_reset(&b);
    nes_apu_run(&b, 29828);
    EXPECT(!b.frame_irq, "no frame IRQ before cycle 29829");
    nes_apu_run(&b, 29830);
    EXPECT(b.frame_irq, "frame IRQ flag at cycle 29829");
    nes_apu_write(&b, 29830, 0x4017, 0x40);
    EXPECT(!b.frame_irq, "$4017 bit 6 clears the flag");

    /* Envelope: start -> 15, then one step per quarter frame with divider period 0, no loop. */
    memset(&b, 0, sizeof b);
    nes_apu_reset(&b);
    nes_apu_write(&b, 0, 0x4015, 0x08);
    nes_apu_write(&b, 0, 0x400C, 0x00); /* envelope period 0, no loop, no halt */
    nes_apu_write(&b, 0, 0x400F, 0xF8); /* length index 31 (30), envelope start */
    nes_apu_run(&b, 7457);
    EXPECT(b.noise.env.decay == 15, "envelope starts at 15 (got %d)", b.noise.env.decay);
    nes_apu_run(&b, 14913);
    EXPECT(b.noise.env.decay == 14, "decays once per quarter frame (got %d)", b.noise.env.decay);

    /* Triangle linear counter: reload 5, control clear -> counts down per quarter frame. */
    memset(&b, 0, sizeof b);
    nes_apu_reset(&b);
    nes_apu_write(&b, 0, 0x4015, 0x04);
    nes_apu_write(&b, 0, 0x4008, 0x05);
    nes_apu_write(&b, 0, 0x400B, 0x08);
    nes_apu_run(&b, 7457);
    EXPECT(b.tri.linear == 5 && !b.tri.linear_reload, "linear counter reloaded to 5 and flag cleared");
    nes_apu_run(&b, 22371);
    EXPECT(b.tri.linear == 3, "linear counter 3 after two more quarter frames (got %d)", b.tri.linear);

    /* Sweep: shift 1, divider period 0. Pulse 1 negates in ones' complement, pulse 2 in two's. */
    memset(&b, 0, sizeof b);
    nes_apu_reset(&b);
    nes_apu_write(&b, 0, 0x4015, 0x03);
    nes_apu_write(&b, 0, 0x4000, 0xBF);
    nes_apu_write(&b, 0, 0x4004, 0xBF);
    nes_apu_write(&b, 0, 0x4001, 0x89); /* enabled, period 0, negate, shift 1 */
    nes_apu_write(&b, 0, 0x4005, 0x89);
    nes_apu_write(&b, 0, 0x4002, 0x00);
    nes_apu_write(&b, 0, 0x4003, 0x01);
    nes_apu_write(&b, 0, 0x4006, 0x00);
    nes_apu_write(&b, 0, 0x4007, 0x01);
    nes_apu_run(&b, 14913); /* first half frame: reload only (reload flag) - divider 0 -> update */
    EXPECT(b.pulse[0].period == 0x7F && b.pulse[1].period == 0x80, "negated sweep: pulse 1 $%03X (want $07F), pulse 2 $%03X (want $080)",
           b.pulse[0].period, b.pulse[1].period);
    nes_apu_write(&b, 14913, 0x4001, 0x00); /* sweep off, no negate, shift 0 */
    nes_apu_write(&b, 14913, 0x4002, 0x00);
    nes_apu_write(&b, 14913, 0x4003, 0x05); /* period $500: target $A00 > $7FF mutes */
    nes_apu_write(&b, 14913, 0x4015, 0x01);
    nes_apu_set_mute(&b, 1 << NES_APU_TRIANGLE); /* the idle triangle holds level 15 */
    EXPECT(nes_apu_mix_level(&b) == 0.0f, "target period above $7FF mutes the pulse");
    nes_apu_write(&b, 14913, 0x4001, 0x08);
    nes_apu_write(&b, 14913, 0x4000, 0x3F); /* duty 12.5%: step 0 low; force a high step */
    b.pulse[0].seq = 1;
    EXPECT(nes_apu_mix_level(&b) > 0.0f, "negate flag lifts the sweep muting");

    /* 5-step mode: the $4017 write clocks quarter + half frame after 3-4 CPU cycles. */
    memset(&b, 0, sizeof b);
    nes_apu_reset(&b);
    nes_apu_write(&b, 0, 0x4015, 0x01);
    nes_apu_write(&b, 0, 0x4000, 0x1F);
    nes_apu_write(&b, 0, 0x4003, 0x08); /* length index 1 = 254 */
    nes_apu_write(&b, 100, 0x4017, 0x80);
    nes_apu_run(&b, 102);
    EXPECT(b.pulse[0].length == 254, "no clock before the $4017 delay");
    nes_apu_run(&b, 105);
    EXPECT(b.pulse[0].length == 253 && b.mode5, "5-step write clocks the length counter immediately");

    /* Noise LFSR from seed 1: 32767 steps in mode 0, 93 in mode 1. */
    for (int mode = 0; mode < 2; ++mode) {
        ApuRig n;
        rig_init(&n);
        nes_apu_write(&n.apu, 0, 0x4015, 0x08);
        nes_apu_write(&n.apu, 0, 0x400C, 0x30);
        nes_apu_write(&n.apu, 0, 0x400E, mode ? 0x80 : 0x00); /* period 4 cycles */
        nes_apu_write(&n.apu, 0, 0x400F, 0x08);
        uint32_t c = n.apu.noise.timer, steps = 0, period = 0;
        for (; c < 140000; c += 4) {
            nes_apu_run(&n.apu, c);
            ++steps;
            if (n.apu.noise.lfsr == 1) {
                period = steps;
                break;
            }
        }
        EXPECT(period == (mode ? 93u : 32767u), "mode %d LFSR period %u", mode, period);
        rig_free(&n);
    }

    /* Nonlinear mixer: exact NESdev formulas. An idle triangle outputs its current step (15 at
     * power-on), as on hardware. */
    memset(&b, 0, sizeof b);
    nes_apu_reset(&b);
    double tri15 = 159.79 / (1.0 / (15.0 / 8227.0) + 100.0);
    EXPECT(fabs(nes_apu_mix_level(&b) - tri15) < 1e-5, "power-on level = triangle step 15 (%.5f, got %.5f)", tri15,
           nes_apu_mix_level(&b));
    nes_apu_set_mute(&b, 1 << NES_APU_TRIANGLE);
    nes_apu_write(&b, 0, 0x4011, 0x7F);
    double dmc = 159.79 / (1.0 / (127.0 / 22638.0) + 100.0);
    EXPECT(fabs(nes_apu_mix_level(&b) - dmc) < 1e-5, "DMC level 127 mixes to %.5f (got %.5f)", dmc, nes_apu_mix_level(&b));
    nes_apu_write(&b, 0, 0x4011, 0x00);
    nes_apu_write(&b, 0, 0x4015, 0x03);
    nes_apu_write(&b, 0, 0x4000, 0xFF);
    nes_apu_write(&b, 0, 0x4004, 0xFF);
    nes_apu_write(&b, 0, 0x4001, 0x08);
    nes_apu_write(&b, 0, 0x4005, 0x08);
    nes_apu_write(&b, 0, 0x4003, 0x01);
    nes_apu_write(&b, 0, 0x4007, 0x01); /* duty 75%, step 0 high */
    double one = 95.88 / (8128.0 / 15 + 100), two = 95.88 / (8128.0 / 30 + 100);
    EXPECT(fabs(nes_apu_mix_level(&b) - two) < 1e-5, "two pulses at 15 mix to %.5f (got %.5f)", two, nes_apu_mix_level(&b));
    EXPECT(two < 2 * one * 0.9, "pulse mixing is nonlinear (%.4f vs 2 x %.4f)", two, one);
}

/* ---- the player on the machine ------------------------------------------------------------ */

static NesImage g_demo;

static void build_demo(NesImage* img, int timing) {
    if (timing) {
        nm_build_image(img, nes_demo_song, NES_DEMO_SONG_SIZE, 0, NES_DEMO_FRAMES_PER_ROW, NES_DEMO_ROWS_PER_ORDER,
                       NES_DEMO_ORDER_COUNT, NES_DEMO_LOOP_ORDER);
    } else {
        nm_build_image(img, nes_demo_song, NES_DEMO_SONG_SIZE, 0, 0, 0, 0, 0);
    }
}

static int note_table_period(NesMachine* m, int index) {
    return nm_peek(m, (uint16_t)(NES_FAMISTUDIO_NOTE_TABLE_LSB + index)) |
           nm_peek(m, (uint16_t)(NES_FAMISTUDIO_NOTE_TABLE_MSB + index)) << 8;
}

static void test_player_image(NesMachine* m) {
    printf("player image: entry table and FamiStudio note table\n");
    EXPECT(nm_start(m, &g_demo, 0, 0, 0) == NM_OK, "demo starts");
    EXPECT(nm_peek(m, NES_PLAYER_INIT) == 0x4C && nm_peek(m, NES_PLAYER_PLAY) == 0x4C, "JMP entry table at $8000");
    EXPECT((nm_peek(m, NES_PLAYER_PLAY + 1) | nm_peek(m, NES_PLAYER_PLAY + 2) << 8) == NES_FAMISTUDIO_UPDATE,
           "play entry jumps to famistudio_update");
    EXPECT(nm_peek(m, NES_FAMISTUDIO_SONG_SPEED) != 0, "famistudio_music_play started the song");
    /* Index i is A-4 = 440 Hz at 46 (FamiTracker octave naming; note byte n = C-1 + n - 1):
     * period = clock / (16 f) - 1 rounded. */
    int worst = 0;
    for (int i = 1; i <= 96; ++i) {
        double f = 440.0 * pow(2.0, (i - 46) / 12.0);
        double exact = CPU_HZ / (16.0 * f) - 1.0;
        int err = (int)lround(fabs(note_table_period(m, i) - exact) * 100);
        if (err > worst) worst = err;
    }
    EXPECT(worst <= 100, "note table within 1 period unit of 12-TET A440 (worst %.2f)", worst / 100.0);
    EXPECT(note_table_period(m, 46) == 0xFD, "index 46 (A-4) = $0FD, got $%03X", note_table_period(m, 46));
}

typedef struct {
    uint16_t period[4]; /* pulse 1/2, triangle timer, noise mode/period */
    uint8_t vol[4];     /* audible level indicator per channel */
} FrameState;

static FrameState* g_states;

static void capture_state(const NesApu* a, FrameState* s) {
    for (int i = 0; i < 2; ++i) {
        const NesPulse* p = &a->pulse[i];
        s->period[i] = p->period;
        int v = (p->ctrl & 0x10) ? (p->ctrl & 15) : p->env.decay;
        s->vol[i] = (uint8_t)((p->length && p->period >= 8) ? v : 0);
    }
    s->period[2] = a->tri.period;
    s->vol[2] = (uint8_t)((a->tri.length && a->tri.linear && a->tri.period >= 2) ? 15 : 0);
    s->period[3] = a->noise.mode_period;
    s->vol[3] = (uint8_t)(a->noise.length ? ((a->noise.ctrl & 0x10) ? (a->noise.ctrl & 15) : a->noise.env.decay) : 0);
}

/* Longest run of frames where channel `ch` keeps one period audibly (skipping the first 3 frames
 * of the run for attack envelopes). */
static void longest_run(int ch, int frames, int* start, int* len) {
    *start = 0;
    *len = 0;
    int s = 0;
    for (int f = 1; f <= frames; ++f) {
        int same = f < frames && g_states[f].vol[ch] > 0 && g_states[f].period[ch] == g_states[s].period[ch] &&
                   g_states[s].vol[ch] > 0;
        if (!same) {
            if (f - s > *len) {
                *len = f - s;
                *start = s;
            }
            s = f;
        }
    }
}

static void test_demo_playback(NesMachine* m, const char* dir) {
    printf("demo song: FamiStudio's export of Shatterhand \"Final Area\" (%d bytes)\n", NES_DEMO_SONG_SIZE);
    const int frames = SONG_FRAMES + 600; /* one pass, the loop, and 10 s more */
    g_states = (FrameState*)calloc((size_t)frames + 1, sizeof *g_states);
    uint8_t (*trace)[512] = calloc((size_t)frames, 512);
    int* trace_len = (int*)calloc((size_t)frames, sizeof(int));
    float* wav = (float*)malloc(sizeof(float) * 2 * NM_MAX_TICK_FRAMES * 60 * 30);
    EXPECT(nm_start(m, &g_demo, 0, 0, 0) == NM_OK, "start");
    nm_set_apu_logging(m, 1);
    int faults = 0, min_n = 1 << 30, max_n = 0, wav_frames = 0, nans = 0;
    uint32_t worst_call = 0;
    double sum2 = 0, peak = 0, t0 = now_us();
    uint64_t hash = 0xCBF29CE484222325ull;
    for (int f = 0; f < frames; ++f) {
        int order, row, tick;
        nm_position(m, &order, &row, &tick);
        int expect = f % SONG_FRAMES;
        if (f == 0 || f == 477 || f == SONG_FRAMES + 5) {
            EXPECT(order == expect / 144 && row == (expect % 144) / 9 && tick == expect % 9,
                   "position at frame %d: %d/%d/%d", f, order, row, tick);
        }
        capture_state(nm_apu(m), &g_states[f]);
        int n = nm_tick(m, g_out);
        if (n <= 0) {
            ++faults;
            break;
        }
        trace_len[f] = nm_apu_log(m, trace[f], 256);
        if (nm_last_call_cycles(m) > worst_call) worst_call = nm_last_call_cycles(m);
        if (n < min_n) min_n = n;
        if (n > max_n) max_n = n;
        hash = fnv(hash, g_out, sizeof(float) * 2 * (size_t)n);
        for (int i = 0; i < 2 * n; ++i) {
            if (g_out[i] != g_out[i]) ++nans;
            double v = fabs(g_out[i]);
            if (v > peak) peak = v;
            sum2 += g_out[i] * g_out[i];
        }
        if (f < 60 * 30) {
            memcpy(wav + 2 * wav_frames, g_out, sizeof(float) * 2 * (size_t)n);
            wav_frames += n;
        }
    }
    double per_tick = (now_us() - t0) / frames;
    EXPECT(faults == 0, "no driver faults over %d frames", frames);
    EXPECT(nans == 0, "no NaN samples");
    EXPECT(min_n >= 798 && max_n <= 800, "48 kHz frames per 60.1 Hz tick in [798, 800] (got %d..%d)", min_n, max_n);
    double rms = sqrt(sum2 / (2.0 * frames * 798.7));
    EXPECT(rms > 0.05 && peak < 1.0, "non-silent, unclipped: rms %.4f peak %.4f", rms, peak);
    EXPECT(worst_call < 29780 / 4, "engine frame within a quarter frame (worst %u cycles)", worst_call);
    printf("  rms %.4f, peak %.4f, worst engine frame %u CPU cycles, %.1f us per tick (%.0fx realtime)\n", rms, peak,
           worst_call, per_tick, 16639.0 / per_tick);

    /* The song loops after order_count * rows * frames_per_row frames: same writes as the first pass. */
    int loop_ok = 1;
    for (int f = 200; f < 600; ++f) {
        if (trace_len[f] != trace_len[f + SONG_FRAMES] ||
            memcmp(trace[f], trace[f + SONG_FRAMES], (size_t)trace_len[f] * 2) != 0) {
            loop_ok = 0;
            printf("  loop mismatch at frame %d\n", f);
            break;
        }
    }
    EXPECT(loop_ok, "register writes repeat with the %d-frame song period (timing metadata matches the engine)", SONG_FRAMES);

    /* Note pitches written by the engine: most sounding pulse frames sit exactly on note-table periods. */
    int on_table = 0, sounding = 0;
    for (int f = 0; f < SONG_FRAMES; ++f) {
        for (int ch = 0; ch < 2; ++ch) {
            if (!g_states[f].vol[ch]) continue;
            ++sounding;
            for (int i = 1; i <= 96; ++i) {
                if (note_table_period(m, i) == g_states[f].period[ch]) {
                    ++on_table;
                    break;
                }
            }
        }
    }
    EXPECT(sounding > SONG_FRAMES / 2 && on_table * 10 >= sounding * 5,
           "pulse periods on the note table in %d of %d sounding frames", on_table, sounding);
    printf("  pulse periods on the FamiStudio note table: %d / %d sounding frames\n", on_table, sounding);

    int writes = 0, w4003 = 0;
    for (int f = 0; f < frames; ++f) {
        writes += trace_len[f];
        for (int i = 0; i < trace_len[f]; ++i) w4003 += trace[f][2 * i] == 0x03;
    }
    EXPECT(writes > frames * 4, "engine writes the APU every frame (%d writes)", writes);
    printf("  %d APU writes, %d to $4003 (FamiStudio avoids phase resets)\n", writes, w4003);

    /* Write the evidence: WAVs (full mix, and pulse + triangle only for waveform comparisons
     * without the random noise channel), trace, NSF. */
    char path[512];
    snprintf(path, sizeof path, "%s/nes_demo.wav", dir);
    write_wav(path, wav, wav_frames);
    printf("  wrote %s (%.1f s)\n", path, wav_frames / (double)RATE);
    nm_start(m, &g_demo, 0, 0, 0);
    nm_mute_channel(m, 3, 1);
    nm_mute_channel(m, 4, 1);
    wav_frames = 0;
    for (int f = 0; f < 60 * 30; ++f) {
        int n = nm_tick(m, wav + 2 * wav_frames);
        wav_frames += n > 0 ? n : 0;
    }
    nm_mute_channel(m, 3, 0);
    nm_mute_channel(m, 4, 0);
    snprintf(path, sizeof path, "%s/nes_demo_tonal.wav", dir);
    write_wav(path, wav, wav_frames);
    snprintf(path, sizeof path, "%s/nes_trace.txt", dir);
    FILE* ft = fopen(path, "w");
    if (ft) {
        fprintf(ft, "# frame: APU writes as reg=value (hex), FamiStudio %s player, demo song\n", "4.5.3");
        for (int f = 0; f < frames; ++f) {
            fprintf(ft, "%d:", f);
            for (int i = 0; i < trace_len[f]; ++i) fprintf(ft, " 40%02X=%02X", trace[f][2 * i], trace[f][2 * i + 1]);
            fprintf(ft, "\n");
        }
        fclose(ft);
    }
    snprintf(path, sizeof path, "%s/nes_demo.nsf", dir);
    FILE* fn = fopen(path, "wb");
    if (fn) {
        uint8_t h[128];
        memset(h, 0, sizeof h);
        memcpy(h, "NESM\x1A", 5);
        h[5] = 1;                /* version */
        h[6] = 1;                /* songs */
        h[7] = 1;                /* starting song */
        h[8] = 0x00, h[9] = 0x80; /* load */
        h[10] = (uint8_t)NES_PLAYER_INIT, h[11] = (uint8_t)(NES_PLAYER_INIT >> 8);
        h[12] = (uint8_t)NES_PLAYER_PLAY, h[13] = (uint8_t)(NES_PLAYER_PLAY >> 8);
        strcpy((char*)h + 0x0E, "Final Area");
        strcpy((char*)h + 0x2E, "Shatterhand (FamiStudio demo)");
        strcpy((char*)h + 0x4E, "Thor Tracker NES spike");
        h[0x6E] = 0xFF, h[0x6F] = 0x40; /* 16639 us: the NTSC frame rate, as FamiStudio's NSF export */
        h[0x78] = 0x20, h[0x79] = 0x4E; /* PAL speed (unused) */
        fwrite(h, 1, sizeof h, fn);
        static uint8_t body[NES_SONG_BASE - 0x8000 + NES_DEMO_SONG_SIZE];
        memset(body, 0, sizeof body);
        memcpy(body, nes_player_code, NES_PLAYER_SIZE);
        memcpy(body + (NES_SONG_BASE - 0x8000), nes_demo_song, NES_DEMO_SONG_SIZE);
        fwrite(body, 1, sizeof body, fn);
        fclose(fn);
    }

    /* Pitch of known notes: the longest held pulse 1 and triangle notes, rendered alone. */
    for (int ch = 0; ch <= 2; ch += 2) {
        int start, len;
        longest_run(ch, SONG_FRAMES, &start, &len);
        start += 3;
        len -= 3;
        EXPECT(len >= 8, "channel %d has a held note (%d frames)", ch, len);
        if (len < 8) continue;
        nm_start(m, &g_demo, 0, 0, 0);
        for (int c = 0; c < NM_CHANNELS; ++c) nm_mute_channel(m, c, c != ch);
        static float mono[NM_MAX_TICK_FRAMES * 600];
        int got = 0;
        for (int f = 0; f < start + len; ++f) {
            int n = nm_tick(m, g_out);
            if (f < start || n <= 0) continue;
            for (int i = 0; i < n && got < (int)(sizeof mono / sizeof mono[0]); ++i) mono[got++] = g_out[2 * i];
        }
        for (int c = 0; c < NM_CHANNELS; ++c) nm_mute_channel(m, c, 0);
        int p = g_states[start].period[ch];
        double want = CPU_HZ / ((ch == 2 ? 32.0 : 16.0) * (p + 1)), hz = zero_cross_hz(mono, got, RATE);
        EXPECT(fabs(hz / want - 1) < 0.01, "%s note at frame %d (period %d): %.2f Hz, want %.2f",
               ch == 2 ? "triangle" : "pulse 1", start, p, hz, want);
        printf("  %s: frames %d-%d period %d -> %.2f Hz measured, %.2f Hz from the timer\n", ch == 2 ? "triangle" : "pulse 1",
               start, start + len - 1, p, hz, want);
    }

    /* Determinism: the same render twice. */
    for (int pass = 0; pass < 2; ++pass) {
        nm_start(m, &g_demo, 0, 0, 0);
        uint64_t h2 = 0xCBF29CE484222325ull;
        for (int f = 0; f < frames; ++f) {
            int n = nm_tick(m, g_out);
            if (n <= 0) break;
            h2 = fnv(h2, g_out, sizeof(float) * 2 * (size_t)n);
        }
        EXPECT(h2 == hash, "render %d hash %016llx equals the first %016llx", pass + 2, (unsigned long long)h2,
               (unsigned long long)hash);
    }
    printf("  render hash %016llx\n", (unsigned long long)hash);

    /* Seek: (order 3, row 5) is frame 477; the next frames match straight play exactly. */
    double s0 = now_us();
    EXPECT(nm_start(m, &g_demo, 3, 5, 12000) == NM_OK, "seek to order 3 row 5");
    double seek_us = now_us() - s0;
    EXPECT(nm_frames(m) == 477, "seek fast-forwards 477 frames (got %u)", nm_frames(m));
    int order, row, tick;
    nm_position(m, &order, &row, &tick);
    EXPECT(order == 3 && row == 5 && tick == 0, "position after seek %d/%d/%d", order, row, tick);
    nm_set_apu_logging(m, 1);
    int seek_ok = 1;
    uint8_t pairs[512];
    for (int f = 477; f < 677; ++f) {
        nm_tick(m, g_out);
        int n = nm_apu_log(m, pairs, 256);
        if (n != trace_len[f] || memcmp(pairs, trace[f], (size_t)n * 2) != 0) {
            seek_ok = 0;
            printf("  seek mismatch at frame %d\n", f);
            break;
        }
    }
    EXPECT(seek_ok, "seeked machine writes the same registers as straight play");
    s0 = now_us();
    EXPECT(nm_start(m, &g_demo, 24, 15, 12000) == NM_OK, "seek to the last row");
    printf("  seek to order 3 row 5: %.2f ms; to order 24 row 15 (%d frames): %.2f ms\n", seek_us / 1000.0,
           SONG_FRAMES - 9, (now_us() - s0) / 1000.0);
    EXPECT(nm_start(m, &g_demo, 25, 0, 12000) == NM_ERR_IMAGE, "order past the song is rejected");
    EXPECT(nm_start(m, &g_demo, 0, 16, 12000) == NM_ERR_IMAGE, "row past the pattern is rejected");
    EXPECT(nm_start(m, &g_demo, 10, 0, 100) == NM_ERR_IMAGE, "target beyond the tick budget is rejected");
    NesImage untimed;
    build_demo(&untimed, 0);
    EXPECT(nm_start(m, &untimed, 1, 0, 12000) == NM_ERR_IMAGE, "no timing: only (0, 0) can start");
    EXPECT(nm_start(m, &untimed, 0, 0, 12000) == NM_OK, "no timing: start at (0, 0)");
    for (int f = 0; f < 50; ++f) nm_tick(m, g_out);
    nm_position(m, &order, &row, &tick);
    EXPECT(order == 0 && row == 0 && tick == 50, "no timing: position reports frames (%d/%d/%d)", order, row, tick);

    /* Live apply of an identical image keeps transport and output. */
    nm_start(m, &g_demo, 0, 0, 0);
    uint64_t h3 = 0xCBF29CE484222325ull;
    for (int f = 0; f < 600; ++f) {
        if (f == 300) nm_apply_image(m, &g_demo);
        int n = nm_tick(m, g_out);
        h3 = fnv(h3, g_out, sizeof(float) * 2 * (size_t)(n > 0 ? n : 0));
    }
    nm_start(m, &g_demo, 0, 0, 0);
    uint64_t h4 = 0xCBF29CE484222325ull;
    for (int f = 0; f < 600; ++f) {
        int n = nm_tick(m, g_out);
        h4 = fnv(h4, g_out, sizeof(float) * 2 * (size_t)(n > 0 ? n : 0));
    }
    EXPECT(h3 == h4 && nm_frames(m) == 600, "apply_image keeps the transport and the sound");

    /* Mute: all five channels muted -> silence (after the output filter settles) while the song
     * keeps running; unmuted -> sound again. Per-channel isolation is the pitch check above. */
    nm_start(m, &g_demo, 0, 0, 0);
    double e_all = 0, e_muted = 0, e_back = 0;
    for (int f = 0; f < 900; ++f) {
        if (f == 300 || f == 600) {
            for (int c = 0; c < NM_CHANNELS; ++c) nm_mute_channel(m, c, f == 300);
        }
        int n = nm_tick(m, g_out);
        double e = 0;
        for (int i = 0; i < 2 * n; ++i) e += g_out[i] * g_out[i];
        if (f < 300) e_all += e;
        else if (f >= 310 && f < 600) e_muted += e;
        else if (f >= 600) e_back += e;
    }
    EXPECT(e_muted < e_all * 1e-6 && e_back > e_all * 0.5 && nm_frames(m) == 900,
           "mute silences, unmute restores, transport runs (energy %.1f, %.2g, %.1f)", e_all, e_muted, e_back);
    EXPECT(nm_mute_channel(m, 5, 1) == NM_ERR_IMAGE, "channel 5 does not exist");

    free(g_states);
    free(trace);
    free(trace_len);
    free(wav);
}

static void test_faults(NesMachine* m) {
    printf("faults: invalid opcodes, BRK, runaway code, invalid stream data\n");
    nm_start(m, &g_demo, 0, 0, 0);
    nm_poke(m, 0x0300, 0x02); /* JAM */
    EXPECT(nm_call(m, 0x0300, 0) == NM_ERR_FAULT, "JAM opcode faults");
    EXPECT(nm_tick(m, g_out) == NM_ERR_FAULT, "a faulted machine stays silent");
    nm_start(m, &g_demo, 0, 0, 0);
    nm_poke(m, 0x0300, 0x00);
    EXPECT(nm_call(m, 0x0300, 0) == NM_ERR_FAULT, "BRK faults");
    nm_start(m, &g_demo, 0, 0, 0);
    nm_poke(m, 0x0300, 0x4C);
    nm_poke(m, 0x0301, 0x00);
    nm_poke(m, 0x0302, 0x03); /* JMP $0300 */
    EXPECT(nm_call(m, 0x0300, 0) == NM_ERR_FAULT, "a call longer than one frame faults");
    nm_start(m, &g_demo, 0, 0, 0);
    nm_poke(m, 0x0300, 0xA9);
    nm_poke(m, 0x0301, 0x42);
    nm_poke(m, 0x0302, 0x60); /* LDA #$42; RTS */
    EXPECT(nm_call(m, 0x0300, 0) == NM_OK && nm_last_call_cycles(m) == 8, "a valid call returns (%u cycles)",
           nm_last_call_cycles(m));

    /* An opcode byte the engine does not know ($55: VRC6 saw volume, no VRC6 here) at the start
     * of the pulse 1 stream: the engine executes its BRK trap on the first frame. */
    NesImage bad = g_demo;
    uint8_t* song = bad.bytes + NM_HEADER_SIZE;
    int ch0 = (song[5] | song[6] << 8) - NES_SONG_BASE;
    song[ch0] = 0x55;
    const ChipEngineOps* ops = chip_engine_ops("nes-famistudio");
    ChipMachine* cm = ops->create(RATE);
    int mode = -1;
    EXPECT(ops->start(cm, (const ChipImage*)&bad, 0, 0, 0, &mode) == CHIP_OK, "the bad image passes header validation");
    EXPECT(ops->tick(cm, g_out) == CHIP_ERR_FAULT, "unknown stream opcode -> engine BRK -> CHIP_ERR_FAULT");
    ops->destroy(cm);
}

static void test_chip_engine_ops(void) {
    printf("chip engine interface: \"nes-famistudio\"\n");
    const ChipEngineOps* ops = chip_engine_ops("nes-famistudio");
    EXPECT(ops != NULL, "registered");
    if (!ops) return;
    EXPECT(chip_engine_ops("gb-huge") != NULL && chip_engine_ops("gb-huge") != ops, "Game Boy still registered");
    EXPECT(ops->image_size == NM_HEADER_SIZE + NM_SONG_SIZE && ops->image_storage >= ops->image_size, "image sizes");
    ChipImage* img = (ChipImage*)malloc(ops->image_storage);
    EXPECT(ops->load_image(img, g_demo.bytes, ops->image_size) == CHIP_OK, "demo image loads");
    EXPECT(ops->load_image(img, g_demo.bytes, ops->image_size - 1) == CHIP_ERR_IMAGE, "wrong size rejected");
    NesImage bad = g_demo;
    bad.bytes[0] ^= 1;
    EXPECT(ops->load_image(img, bad.bytes, ops->image_size) == CHIP_ERR_IMAGE, "bad magic rejected");
    bad = g_demo;
    bad.bytes[4] ^= 1;
    EXPECT(ops->load_image(img, bad.bytes, ops->image_size) == CHIP_ERR_IMAGE, "other player build rejected");
    bad = g_demo;
    bad.bytes[NM_HEADER_SIZE + 6] = 0x60; /* channel 0 pointer -> $60xx */
    EXPECT(ops->load_image(img, bad.bytes, ops->image_size) == CHIP_ERR_IMAGE, "stream pointer outside the song rejected");
    bad = g_demo;
    bad.bytes[NM_HEADER_SIZE + NES_DEMO_SONG_SIZE + 10] = 1;
    EXPECT(ops->load_image(img, bad.bytes, ops->image_size) == CHIP_ERR_IMAGE, "bytes past the data size rejected");
    bad = g_demo;
    bad.bytes[12] = 1;
    EXPECT(ops->load_image(img, bad.bytes, ops->image_size) == CHIP_ERR_IMAGE, "song index past the song count rejected");
    bad = g_demo;
    bad.bytes[17] = NES_DEMO_ORDER_COUNT;
    EXPECT(ops->load_image(img, bad.bytes, ops->image_size) == CHIP_ERR_IMAGE, "loop order past the orders rejected");
    ops->load_image(img, g_demo.bytes, ops->image_size);

    ChipMachine* cm = ops->create(RATE);
    EXPECT(cm != NULL, "create(48000)");
    EXPECT(ops->create(CHIP_MAX_SAMPLE_RATE + 1) == NULL, "unsupported rate");
    int mode = -1, order, row, tick;
    EXPECT(ops->start(cm, img, 2, 0, 12000, &mode) == CHIP_OK && mode == CHIP_SEEK_FROM_START, "start at order 2");
    ops->position(cm, &order, &row, &tick);
    EXPECT(order == 2 && row == 0 && tick == 0, "position %d/%d/%d", order, row, tick);
    double e = 0;
    for (int i = 0; i < 90; ++i) {
        int n = ops->tick(cm, g_out);
        EXPECT(n > 0 && n <= (int)CHIP_MAX_TICK_FRAMES, "tick frames %d", n);
        for (int k = 0; k < 2 * n; ++k) e += g_out[k] * g_out[k];
    }
    EXPECT(e > 1.0, "non-silent through the ops (energy %.2f)", e);
    ops->position(cm, &order, &row, &tick);
    EXPECT(order == 2 && row == 10 && tick == 0, "90 ticks = 10 rows (%d/%d/%d)", order, row, tick);
    ops->apply_image(cm, img);
    EXPECT(ops->mute(cm, 2, 1) == CHIP_OK && ops->mute(cm, 2, 0) == CHIP_OK, "mute/unmute triangle");
    EXPECT(ops->audition(cm, img, 0, 57, 1, 0) == CHIP_ERR_IMAGE, "audition not supported yet");
    EXPECT(ops->release(cm) == 0, "release: no release phase");
    ops->destroy(cm);
    free(img);
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    build_demo(&g_demo, 1);
    NesMachine* m = nm_create(RATE);
    if (!m) {
        printf("nm_create failed\n");
        return 2;
    }
    EXPECT(nm_validate(&g_demo) == NM_OK, "demo image validates");
    test_apu_pitch();
    test_apu_counters();
    test_player_image(m);
    test_demo_playback(m, dir);
    test_faults(m);
    test_chip_engine_ops();
    nm_destroy(m);
    printf("NES: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
