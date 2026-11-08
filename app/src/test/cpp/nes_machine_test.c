/* Host regression test for the NES machine: the FamiStudio sound engine 4.5.3 player (FamiTracker
 * tempo) on m6502 + nes_apu, playing FamiStudio's own export of the test songs ft_test.txt and
 * dpcm_test.txt and the same rows in the Thor Tracker image layout (nes_ft_song.h, nes_dpcm_song.h):
 * row timing, delays/cuts/releases, DPCM samples, seek, live relocation, audition; plus APU unit
 * checks (DMC included) against the NESdev-documented behaviour.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir]
 * Writes <dir>/nes_ft.wav and nes_ft_tonal.wav (48 kHz stereo, 30 s), <dir>/nes_trace.txt
 * (per-frame APU register writes) and <dir>/nes_ft.nsf (the player + FamiStudio's export as an NSF,
 * for reference players; see tools/audio/nes_reference_check.py); for the DPCM song nes_dpcm.wav,
 * nes_dpcm_trace.txt and nes_dpcm.nsf. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "blip_buf.h"
#include "chip_engine.h"
#include "nes_apu.h"
#include "nes_ft_song.h"
#include "nes_dpcm_song.h"
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

static NesImage g_raw, g_thor, g_moved, g_edited, g_short;

/* FamiStudio note numbers of ft_test.txt (engine note = FamiStudio C0 = 1). */
#define NOTE_C2 25
#define NOTE_C3 37
#define NOTE_A3 46 /* 440 Hz on a pulse */
#define NOTE_E4 53
#define NOTE_B4 60
#define NOTE_C5 61
#define NOTE_D5 63

#define FIRST_PASS_ROWS (NES_FT_ORDERS * NES_FT_ROWS)
#define LOOP_ROWS ((NES_FT_ORDERS - NES_FT_LOOP) * NES_FT_ROWS)

static int load_fixture(NesImage* image, const uint8_t* bytes, size_t size, const char* name) {
    memset(image, 0, sizeof *image);
    memcpy(image->bytes, bytes, size);
    int r = nm_load_image(image);
    EXPECT(r == 0, "%s loads (%s at %u)", name, image->info.problem ? image->info.problem : "-", image->info.problem_at);
    return r;
}

static int note_table_period(NesMachine* m, int index) {
    return nm_peek(m, (uint16_t)(NES_FAMISTUDIO_NOTE_TABLE_LSB + index)) |
           nm_peek(m, (uint16_t)(NES_FAMISTUDIO_NOTE_TABLE_MSB + index)) << 8;
}

/* Straight play of ft_test: (order, row, frames) of row k (k counts rows from the song start
 * through the loop), at tempo 150 a row lasts its speed in frames. */
static void song_row(int k, int* order, int* row, int* frames) {
    if (k >= FIRST_PASS_ROWS) k = NES_FT_LOOP * NES_FT_ROWS + (k - FIRST_PASS_ROWS) % LOOP_ROWS;
    *order = k / NES_FT_ROWS;
    *row = k % NES_FT_ROWS;
    *frames = nes_ft_speed[*order][*row];
}

static int song_frames(int rows) {
    int total = 0, o, r, f;
    for (int k = 0; k < rows; ++k) {
        song_row(k, &o, &r, &f);
        total += f;
    }
    return total;
}

/* Frame at which row (order, row) of the first pass starts. */
static int row_frame(int order, int row) { return song_frames(order * NES_FT_ROWS + row); }

static void test_player_image(NesMachine* m) {
    printf("player image: entry table, FamiTracker tempo, FamiStudio note table\n");
    int mode;
    EXPECT(nm_start(m, &g_thor, 0, 0, 0, &mode) == NM_OK, "test song starts");
    EXPECT(nm_peek(m, NES_PLAYER_INIT) == 0x4C && nm_peek(m, NES_PLAYER_PLAY) == 0x4C, "JMP entry table at $8000");
    EXPECT((nm_peek(m, NES_PLAYER_PLAY + 1) | nm_peek(m, NES_PLAYER_PLAY + 2) << 8) == NES_FAMISTUDIO_UPDATE,
           "play entry jumps to famistudio_update");
    EXPECT(nm_peek(m, NES_FAMISTUDIO_SONG_SPEED) == 6 && nm_peek(m, NES_FAMISTUDIO_TEMPO_ACC_HI) == 6,
           "famistudio_music_play: FamiTracker speed 6, accumulator ready for row 0");
    EXPECT((nm_peek(m, NES_FAMISTUDIO_TEMPO_STEP_LO) | nm_peek(m, NES_FAMISTUDIO_TEMPO_STEP_HI) << 8) == NES_FT_TEMPO_NTSC,
           "NTSC tempo step from the song header (tempo 150 = 256)");
    /* Index i is A440 at 46 (FamiStudio names it A3; Thor Tracker's absolute note is i + 11, A-4):
     * period = clock / (16 f) - 1 rounded. */
    int worst = 0;
    for (int i = 1; i <= 96; ++i) {
        double f = 440.0 * pow(2.0, (i - NOTE_A3) / 12.0);
        double exact = CPU_HZ / (16.0 * f) - 1.0;
        int err = (int)lround(fabs(note_table_period(m, i) - exact) * 100);
        if (err > worst) worst = err;
    }
    EXPECT(worst <= 100, "note table within 1 period unit of 12-TET A440 (worst %.2f)", worst / 100.0);
    EXPECT(note_table_period(m, NOTE_A3) == 0xFD, "index 46 (A440) = $0FD, got $%03X", note_table_period(m, NOTE_A3));
}

static void test_images(void) {
    printf("images: Thor layout derived timing and validation\n");
    const NesImageInfo* t = &g_thor.info;
    EXPECT(t->layout == NM_LAYOUT_THOR && t->orders == NES_FT_ORDERS && t->loop == NES_FT_LOOP &&
               t->instruments == NES_FT_INSTRUMENTS,
           "orders %d loop %d instruments %d", t->orders, t->loop, t->instruments);
    int rows_ok = 1, speed_ok = 1;
    for (int o = 0; o < NES_FT_ORDERS; ++o) {
        rows_ok &= t->rows[o] == NES_FT_ROWS && g_raw.info.rows[o] == NES_FT_ROWS;
        /* The speed an order starts with is the last speed of the order before (the text's table). */
        speed_ok &= t->speed[o] == (o ? nes_ft_speed[o - 1][NES_FT_ROWS - 1] : 6);
    }
    EXPECT(rows_ok, "rows per order derived from the streams (raw: from the header)");
    EXPECT(speed_ok, "speed at each order start derived from the $46 opcodes = ft_test.txt");
    EXPECT(g_short.info.orders == 2 && g_short.info.loop == 1, "short variant: 2 orders, loop 1");
    EXPECT(g_moved.info.used_count == t->used_count && t->used_count == 33, "33 envelopes in the directory");

    /* Rejections: each corrupts one thing in nes_ft_thor. */
    static NesImage bad;
    const uint32_t region = NM_REGION_OFFSET;
    const uint8_t* s = nm_region(&g_thor);
    const uint16_t ch0 = nm_rd16(s + 5), ch3 = nm_rd16(s + 11), ch4 = nm_rd16(s + 13);
    const uint16_t pat0 = nm_rd16(s + (ch0 + 2 - NES_SONG_BASE)), pat4 = nm_rd16(s + (ch4 + 2 - NES_SONG_BASE));
    const uint16_t pat3 = nm_rd16(s + (ch3 + 2 - NES_SONG_BASE));
    const uint8_t* dir = g_thor.bytes + NES_IMAGE_HEADER_SIZE;
    const uint16_t lead_vol = nm_rd16(dir + 4 * (4 * NES_FT_INSTRUMENT_LEAD));
    const uint16_t lead_len = dir[4 * (4 * NES_FT_INSTRUMENT_LEAD) + 2];
    struct {
        uint32_t at;
        uint8_t value;
        const char* what;
    } cases[] = {
        {0, 'X', "bad magic"},
        {4, 0, "other player build"},
        {13, 2, "unknown layout"},
        {14, 0, "no instruments"},
        {14, NES_FT_INSTRUMENTS + 1, "instrument table past the data"},
        {17, 0x03, "envelope count above the directory"},
        {NES_IMAGE_HEADER_SIZE + 4 * 300, 1, "directory entry beyond the count"},
        {NES_IMAGE_HEADER_SIZE + 3, 4, "envelope kind 4"},
        {NES_IMAGE_HEADER_SIZE + 3, NM_KIND_PITCH, "instrument envelope of the wrong kind"},
        {NES_IMAGE_HEADER_SIZE + 4, dir[0], "overlapping / unordered envelopes"},
        {region + lead_vol - NES_SONG_BASE + 1, 0xD0, "volume value 16"},
        {region + lead_vol - NES_SONG_BASE + lead_len - 2, 0xC0, "envelope without a final loop"},
        {region + lead_vol - NES_SONG_BASE, 5, "release index on a loop code"},
        {region + 0, 2, "two songs"},
        {region + ch0 - NES_SONG_BASE, 0x40, "order stream entry is not $41"},
        {region + ch0 - NES_SONG_BASE + 4 * NES_FT_ORDERS + 1, (uint8_t)(ch0 + 4 * NES_FT_LOOP + 1), "loop between entries"},
        {region + ch0 - NES_SONG_BASE + 1, 0, "pattern with no entries"},
        {region + ch4 - NES_SONG_BASE + 1, 2, "entry count past the pattern"},
        {region + pat4 - NES_SONG_BASE, 0x9D, "DPCM pattern one row short"},
        {region + pat4 - NES_SONG_BASE, 0x25, "a note on the DPCM channel"},
        {region + pat0 - NES_SONG_BASE + 1, 0, "speed 0"},
        {region + pat3 - NES_SONG_BASE, 0x4E, "fine pitch on the noise channel"},
        {region + pat0 - NES_SONG_BASE + 4, 0xFC, "instrument beyond the table"},
        {region + pat0 - NES_SONG_BASE + 6, 0x55, "VRC6 opcode"},
        {region + g_thor.info.size + 3, 1, "bytes after the data size"},
    };
    for (size_t i = 0; i < sizeof cases / sizeof cases[0]; ++i) {
        bad = g_thor;
        bad.bytes[cases[i].at] = cases[i].value;
        int r = nm_load_image(&bad);
        EXPECT(r != 0, "rejected: %s", cases[i].what);
        if (r) printf("  rejected %-40s -> %s (byte %u)\n", cases[i].what, bad.info.problem, bad.info.problem_at);
    }
    bad = g_thor;
    EXPECT(nm_load_image(&bad) == 0, "the unmodified copy still loads");
}

typedef struct {
    uint16_t period[4]; /* pulse 1/2, triangle timer, noise mode/period */
    uint8_t vol[4];     /* audible level indicator per channel */
} FrameState;

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

/* Per-frame APU writes of a straight play (the reference other runs are compared with). */
typedef struct {
    int frames;
    uint8_t (*w)[512];
    int* n;
} Trace;

static void trace_alloc(Trace* t, int frames) {
    t->frames = frames;
    t->w = calloc((size_t)frames, 512);
    t->n = (int*)calloc((size_t)frames, sizeof(int));
}

static void trace_free(Trace* t) {
    free(t->w);
    free(t->n);
}

static void record(NesMachine* m, Trace* t, int f) { t->n[f] = nm_apu_log(m, t->w[f], 256); }

static int same_frame(const Trace* a, const Trace* b, int f) {
    return a->n[f] == b->n[f] && !memcmp(a->w[f], b->w[f], (size_t)a->n[f] * 2);
}

/* First frame in [from, to) where traces differ, or -1. */
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

static void write_evidence(NesMachine* m, const char* dir, const Trace* raw) {
    char path[512];
    static float wav[NM_MAX_TICK_FRAMES * 2 * 60 * 30];
    int mode;
    for (int tonal = 0; tonal < 2; ++tonal) {
        nm_start(m, &g_raw, 0, 0, 0, &mode);
        if (tonal) nm_mute_channel(m, 3, 1);
        int frames = 0;
        for (int f = 0; f < 60 * 30; ++f) {
            int n = nm_tick(m, wav + 2 * frames);
            frames += n > 0 ? n : 0;
        }
        snprintf(path, sizeof path, "%s/%s", dir, tonal ? "nes_ft_tonal.wav" : "nes_ft.wav");
        write_wav(path, wav, frames);
        if (!tonal) printf("  wrote %s (%.1f s)\n", path, frames / (double)RATE);
    }
    snprintf(path, sizeof path, "%s/nes_trace.txt", dir);
    FILE* ft = fopen(path, "w");
    if (ft) {
        fprintf(ft, "# frame: APU writes as reg=value (hex), FamiStudio %s player (FamiTracker tempo), ft_test\n", "4.5.3");
        for (int f = 0; f < raw->frames; ++f) {
            fprintf(ft, "%d:", f);
            for (int i = 0; i < raw->n[f]; ++i) fprintf(ft, " 40%02X=%02X", raw->w[f][2 * i], raw->w[f][2 * i + 1]);
            fprintf(ft, "\n");
        }
        fclose(ft);
    }
    snprintf(path, sizeof path, "%s/nes_ft.nsf", dir);
    FILE* fn = fopen(path, "wb");
    if (fn) {
        uint8_t h[128];
        memset(h, 0, sizeof h);
        memcpy(h, "NESM\x1A", 5);
        h[5] = 1;                 /* version */
        h[6] = 1;                 /* songs */
        h[7] = 1;                 /* starting song */
        h[8] = 0x00, h[9] = 0x80; /* load */
        h[10] = (uint8_t)NES_PLAYER_INIT, h[11] = (uint8_t)(NES_PLAYER_INIT >> 8);
        h[12] = (uint8_t)NES_PLAYER_PLAY, h[13] = (uint8_t)(NES_PLAYER_PLAY >> 8);
        strcpy((char*)h + 0x0E, "Thor Tracker NES test");
        strcpy((char*)h + 0x2E, "Thor Tracker");
        strcpy((char*)h + 0x4E, "FamiStudio 4.5.3 engine");
        h[0x6E] = 0xFF, h[0x6F] = 0x40; /* 16639 us: the NTSC frame rate, as FamiStudio's NSF export */
        h[0x78] = 0x20, h[0x79] = 0x4E; /* PAL speed (unused) */
        fwrite(h, 1, sizeof h, fn);
        static uint8_t body[NES_SONG_BASE - 0x8000 + NES_FT_RAW_SIZE];
        memset(body, 0, sizeof body);
        memcpy(body, nes_player_code, NES_PLAYER_SIZE);
        memcpy(body + (NES_SONG_BASE - 0x8000), nes_ft_raw, NES_FT_RAW_SIZE);
        fwrite(body, 1, sizeof body, fn);
        fclose(fn);
    }
}

static void test_playback(NesMachine* m, const char* dir) {
    const int pass_rows = FIRST_PASS_ROWS + 2 * LOOP_ROWS;
    const int frames = song_frames(pass_rows);
    printf("FamiStudio's FamiTracker-tempo export of ft_test.txt: %d rows (first pass + 2 loops), %d frames\n", pass_rows,
           frames);
    Trace raw, thor;
    trace_alloc(&raw, frames);
    trace_alloc(&thor, frames);
    play_trace(m, &g_raw, &raw);
    int mode;
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_set_apu_logging(m, 1);
    static FrameState states[8192];
    static int pos[8192][3];
    static uint8_t note0[8192], note2[8192], env0[8192];
    int faults = 0, min_n = 1 << 30, max_n = 0, nans = 0, writes = 0;
    uint32_t worst_call = 0;
    double sum2 = 0, peak = 0, t0 = now_us();
    uint64_t hash = 0xCBF29CE484222325ull;
    for (int f = 0; f < frames; ++f) {
        nm_position(m, &pos[f][0], &pos[f][1], &pos[f][2]);
        int n = nm_tick(m, g_out);
        if (n <= 0) {
            ++faults;
            break;
        }
        record(m, &thor, f);
        writes += thor.n[f];
        capture_state(nm_apu(m), &states[f]);
        note0[f] = nm_peek(m, NES_FAMISTUDIO_CHN_NOTE + 0);
        note2[f] = nm_peek(m, NES_FAMISTUDIO_CHN_NOTE + 2);
        env0[f] = nm_peek(m, NES_FAMISTUDIO_ENV_VALUE + 0);
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
    }
    double per_tick = (now_us() - t0) / frames;
    EXPECT(faults == 0 && nans == 0, "no driver faults or NaNs over %d frames", frames);
    EXPECT(min_n >= 798 && max_n <= 800, "48 kHz frames per 60.1 Hz tick in [798, 800] (got %d..%d)", min_n, max_n);
    double rms = sqrt(sum2 / (2.0 * frames * 798.7));
    EXPECT(rms > 0.03 && peak < 1.0, "non-silent, unclipped: rms %.4f peak %.4f", rms, peak);
    EXPECT(worst_call < 29780 / 4, "engine frame within a quarter frame (worst %u cycles)", worst_call);
    EXPECT(writes > frames * 4, "engine writes the APU every frame (%d writes)", writes);
    printf("  rms %.4f, peak %.4f, worst engine frame %u CPU cycles, %.1f us per tick (%.0fx realtime)\n", rms, peak,
           worst_call, per_tick, 16639.0 / per_tick);

    /* The layout plays FamiStudio's own rows: the same register writes as FamiStudio's export. */
    int d = first_difference(&raw, &thor, 0, frames);
    EXPECT(d < 0, "Thor layout writes the same APU registers as FamiStudio's export, all %d frames (first difference %d)",
           frames, d);

    /* Rows: every row starts on the frame FamiTracker tempo gives it (speed per row from the text), in
     * play order through the loop; position() predicts the row with tick 0 the frame before. */
    int k = 0, bad_rows = 0, row_start = 0;
    for (int f = 0; f < frames; ++f) {
        if (pos[f][2] == 0) {
            int o, r, len;
            song_row(k, &o, &r, &len);
            if (pos[f][0] != o || pos[f][1] != r || f != row_start) {
                if (bad_rows++ < 3) printf("  row %d: frame %d at %d/%d, expected %d/%d at frame %d\n", k, f, pos[f][0], pos[f][1], o, r, row_start);
            }
            row_start += len;
            ++k;
        } else if (f > 0 && (pos[f][0] != pos[f - 1][0] || pos[f][1] != pos[f - 1][1] || pos[f][2] != pos[f - 1][2] + 1)) {
            ++bad_rows;
        }
    }
    EXPECT(bad_rows == 0 && k == pass_rows, "%d row starts at the frames speed (Fxx 6/7/4/8) gives, loop to order %d (%d wrong)",
           k, NES_FT_LOOP, bad_rows);

    /* Note delay / cut / release at the frames the text asks for. */
    int f8 = row_frame(0, 12);
    EXPECT(note0[f8] == NOTE_C5 && note0[f8 + 3] == NOTE_C5 && note0[f8 + 4] == 0,
           "order 0 row 12 C5 CutDelay=4: sounds frames 0-3 of the row, cut at frame 4 (%d %d %d)", note0[f8], note0[f8 + 3],
           note0[f8 + 4]);
    int f2 = row_frame(1, 2);
    EXPECT(note2[f2 + 1] != NOTE_C3 && note2[f2 + 2] == NOTE_C3,
           "order 1 row 2 triangle C3 NoteDelay=2: starts at frame 2 of the row (%d %d)", note2[f2 + 1], note2[f2 + 2]);
    EXPECT(states[f2 + 2].period[2] == note_table_period(m, NOTE_C3), "  ... with the C3 timer period %d", states[f2 + 2].period[2]);
    int f3 = row_frame(3, 8);
    EXPECT(note0[f3 + 4] != NOTE_E4 && note0[f3 + 5] == NOTE_E4,
           "order 3 row 8 E4 NoteDelay=5 at speed 8: starts at frame 5 (%d %d)", note0[f3 + 4], note0[f3 + 5]);
    int f10 = row_frame(0, 10);
    EXPECT(env0[f10 - 1] == 12 && env0[f10] == 10 && env0[f10 + 1] == 7 && env0[f10 + 2] == 4 && env0[f10 + 3] == 0,
           "order 0 row 10 release: Lead volume leaves its sustain 12 for the tail 10 7 4 0 (%d|%d %d %d %d)", env0[f10 - 1],
           env0[f10], env0[f10 + 1], env0[f10 + 2], env0[f10 + 3]);

    /* Determinism: the same render twice. */
    for (int pass = 0; pass < 2; ++pass) {
        nm_start(m, &g_thor, 0, 0, 0, &mode);
        uint64_t h2 = 0xCBF29CE484222325ull;
        for (int f = 0; f < frames; ++f) {
            int n = nm_tick(m, g_out);
            if (n <= 0) break;
            h2 = fnv(h2, g_out, sizeof(float) * 2 * (size_t)n);
        }
        EXPECT(h2 == hash, "render %d hash %016llx equals the first %016llx", pass + 2, (unsigned long long)h2,
               (unsigned long long)hash);
    }

    /* Pitch of a held note rendered alone: pulse 1 and triangle. */
    for (int ch = 0; ch <= 2; ch += 2) {
        int best = 0, best_len = 0, s = 0;
        for (int f = 1; f <= frames; ++f) {
            int same = f < frames && states[f].vol[ch] > 0 && states[s].vol[ch] > 0 && states[f].period[ch] == states[s].period[ch];
            if (!same) {
                if (f - s > best_len) best_len = f - s, best = s;
                s = f;
            }
        }
        best += 3;
        best_len -= 3;
        EXPECT(best_len >= 8, "channel %d holds a note (%d frames)", ch, best_len);
        if (best_len < 8) continue;
        nm_start(m, &g_thor, 0, 0, 0, &mode);
        for (int c = 0; c < NM_CHANNELS; ++c) nm_mute_channel(m, c, c != ch);
        static float mono[NM_MAX_TICK_FRAMES * 600];
        int got = 0;
        for (int f = 0; f < best + best_len; ++f) {
            int n = nm_tick(m, g_out);
            if (f < best || n <= 0) continue;
            for (int i = 0; i < n && got < (int)(sizeof mono / sizeof mono[0]); ++i) mono[got++] = g_out[2 * i];
        }
        int p = states[best].period[ch];
        double want = CPU_HZ / ((ch == 2 ? 32.0 : 16.0) * (p + 1)), hz = zero_cross_hz(mono, got, RATE);
        EXPECT(fabs(hz / want - 1) < 0.01, "%s note at frame %d (period %d): %.2f Hz, want %.2f",
               ch == 2 ? "triangle" : "pulse 1", best, p, hz, want);
        printf("  %s: frames %d-%d period %d -> %.2f Hz measured, %.2f Hz from the timer\n", ch == 2 ? "triangle" : "pulse 1",
               best, best + best_len - 1, p, hz, want);
    }

    /* Another tempo: the row starts follow FamiTracker's accumulator (step = 256 * tempo / 150 per
     * frame; a row starts when the whole part reaches the speed, which it then pays). */
    static NesImage slow;
    slow = g_thor;
    uint8_t* hdr = slow.bytes + NM_REGION_OFFSET + 15;
    const int step = 256 * 125 / 150; /* FamiStudio's integer formula for tempo 125 */
    hdr[2] = (uint8_t)step;
    hdr[3] = (uint8_t)(step >> 8);
    EXPECT(nm_load_image(&slow) == 0 && slow.info.tempo_ntsc == step, "tempo 125 image (step %d)", step);
    nm_start(m, &slow, 0, 0, 0, &mode);
    int acc = 6 << 8, speed = 6, rows = 0, mismatched = 0, model_frames = 0;
    for (int f = 0; rows < FIRST_PASS_ROWS + 4; ++f) {
        int starts_model = (acc >> 8) >= speed, o, r, t;
        nm_position(m, &o, &r, &t);
        if (starts_model != (t == 0)) ++mismatched;
        if (starts_model) {
            acc -= speed << 8;
            int len;
            song_row(rows++, &o, &r, &len);
            speed = len;
        }
        acc += step;
        nm_tick_silent(m);
        model_frames = f + 1;
    }
    EXPECT(mismatched == 0, "tempo 125: %d rows start on the accumulator's frames (%d mismatches)", rows, mismatched);
    printf("  tempo 125: %d rows in %d frames (tempo 150: %d)\n", rows, model_frames, song_frames(rows));

    write_evidence(m, dir, &raw);
    trace_free(&raw);
    trace_free(&thor);
}

static void test_seek(NesMachine* m) {
    printf("seek: exact rows from engine state\n");
    const int frames = song_frames(FIRST_PASS_ROWS + LOOP_ROWS);
    Trace ref, t;
    trace_alloc(&ref, frames);
    trace_alloc(&t, frames);
    play_trace(m, &g_thor, &ref);
    const int targets[][2] = {{0, 1}, {1, 0}, {2, 8}, {2, 9}, {3, 15}, {0, 12}};
    for (size_t i = 0; i < sizeof targets / sizeof targets[0]; ++i) {
        const int o = targets[i][0], r = targets[i][1], f0 = row_frame(o, r);
        int mode = -1, po, pr, pt;
        double s0 = now_us();
        int res = nm_start(m, &g_thor, o, r, 12000, &mode);
        double us = now_us() - s0;
        nm_position(m, &po, &pr, &pt);
        EXPECT(res == NM_OK && mode == NM_SEEK_FROM_START && po == o && pr == r && pt == 0 && nm_frames(m) == (uint32_t)f0,
               "seek %d/%d: from start, %u frames (row starts at %d), position %d/%d/%d", o, r, nm_frames(m), f0, po, pr, pt);
        nm_set_apu_logging(m, 1);
        int same = 1;
        for (int f = f0; f < f0 + 200 && f < frames; ++f) {
            nm_tick_silent(m);
            record(m, &t, f);
            same &= same_frame(&ref, &t, f);
        }
        EXPECT(same, "seek %d/%d writes the same registers as straight play for 200 frames (%.2f ms)", o, r, us / 1000.0);
    }
    int mode = -1, o, r, tk;
    EXPECT(nm_start(m, &g_raw, 2, 8, 12000, &mode) == NM_OK && mode == NM_SEEK_FROM_START, "raw layout seeks from the start");
    /* Budget too small for the song start: placed at order 2's start (speed 7 from order 1) and its 8
     * rows replayed: 56 frames. */
    double s0 = now_us();
    EXPECT(nm_start(m, &g_thor, 2, 8, 60, &mode) == NM_OK && mode == NM_SEEK_FROM_ORDER, "budget 60: seek from order 2's start");
    printf("  fallback seek 2/8: %.3f ms, %u frames\n", (now_us() - s0) / 1000.0, nm_frames(m));
    nm_position(m, &o, &r, &tk);
    EXPECT(o == 2 && r == 8 && tk == 0 && nm_frames(m) == 56, "fallback lands on 2/8 after 56 frames (%d/%d/%d, %u)", o, r, tk,
           nm_frames(m));
    int rows_ok = 1, f = row_frame(2, 8);
    for (int i = 0; i < 300; ++i, ++f) {
        int a, b, c, x, y, z;
        nm_position(m, &a, &b, &c);
        /* compare with straight play's positions */
        int k = 0, start = 0, len = 0;
        for (;; ++k) {
            song_row(k, &x, &y, &len);
            if (start + len > f) break;
            start += len;
        }
        z = f - start;
        rows_ok &= a == x && b == y && c == z;
        nm_tick_silent(m);
    }
    EXPECT(rows_ok, "after the fallback the rows run in step with straight play (300 frames)");
    EXPECT(nm_start(m, &g_thor, 2, 8, 50, &mode) == NM_ERR_IMAGE, "budget 50: not even the order replay fits");
    EXPECT(nm_start(m, &g_raw, 2, 8, 60, &mode) == NM_ERR_IMAGE, "raw layout: no order fallback");
    EXPECT(nm_start(m, &g_thor, NES_FT_ORDERS, 0, 12000, &mode) == NM_ERR_IMAGE, "order past the song rejected");
    EXPECT(nm_start(m, &g_thor, 0, NES_FT_ROWS, 12000, &mode) == NM_ERR_IMAGE, "row past the order rejected");
    s0 = now_us();
    EXPECT(nm_start(m, &g_thor, 3, 15, 12000, &mode) == NM_OK, "seek to the last row");
    printf("  seek to 3/15 (%u frames): %.3f ms\n", nm_frames(m), (now_us() - s0) / 1000.0);
    trace_free(&ref);
    trace_free(&t);
}

static void test_live_apply(NesMachine* m) {
    printf("live apply: relocation at tick boundaries\n");
    const int frames = song_frames(FIRST_PASS_ROWS + 2 * LOOP_ROWS);
    Trace ref, t;
    trace_alloc(&ref, frames);
    trace_alloc(&t, frames);
    play_trace(m, &g_thor, &ref);
    int mode;

    /* Every block moved, applied before every single frame (alternating images): nothing audible changes. */
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_set_apu_logging(m, 1);
    double worst_us = 0;
    for (int f = 0; f < frames; ++f) {
        double a0 = now_us();
        nm_apply_image(m, (f & 1) ? &g_moved : &g_thor);
        double us = now_us() - a0;
        if (us > worst_us) worst_us = us;
        nm_tick_silent(m);
        record(m, &t, f);
    }
    int d = first_difference(&ref, &t, 0, frames);
    EXPECT(d < 0, "moved data applied before each of %d frames: same register writes as straight play (first difference %d)",
           frames, d);
    printf("  worst apply_image: %.1f us\n", worst_us);

    /* The moved image really moves the engine's pointers (relocation, not luck). */
    nm_start(m, &g_thor, 1, 3, 12000, &mode);
    nm_tick_silent(m);
    const uint16_t p_before = nm_peek(m, NES_FAMISTUDIO_CHN_PTR_LO) | nm_peek(m, NES_FAMISTUDIO_CHN_PTR_HI) << 8;
    const uint16_t e_before = nm_peek(m, NES_FAMISTUDIO_ENV_ADDR_LO) | nm_peek(m, NES_FAMISTUDIO_ENV_ADDR_HI) << 8;
    nm_apply_image(m, &g_moved);
    const uint16_t p_after = nm_peek(m, NES_FAMISTUDIO_CHN_PTR_LO) | nm_peek(m, NES_FAMISTUDIO_CHN_PTR_HI) << 8;
    const uint16_t e_after = nm_peek(m, NES_FAMISTUDIO_ENV_ADDR_LO) | nm_peek(m, NES_FAMISTUDIO_ENV_ADDR_HI) << 8;
    EXPECT(p_before != p_after && e_before != e_after && p_after >= NES_SONG_BASE && p_after < NES_SONG_BASE + g_moved.info.size,
           "apply re-points stream ($%04X -> $%04X) and envelope ($%04X -> $%04X)", p_before, p_after, e_before, e_after);

    /* Speed edits: order 2 row 8's Fxx 4 -> 5. Applied when straight play's speed at the playhead
     * changes; an Fxx the current order already ran (order 3 row 0: 8) is kept. */
    static NesImage fast;
    fast = g_thor;
    uint8_t* fr = fast.bytes + NM_REGION_OFFSET;
    int edits = 0;
    for (uint32_t i = 0; i + 2 < fast.info.size; ++i) {
        if (fr[i] == 0x46 && fr[i + 1] == 0x04 && fr[i + 2] == 0x53) fr[i + 1] = 5, ++edits;
    }
    EXPECT(edits == 1 && nm_load_image(&fast) == 0 && fast.info.speed[3] == 5, "speed edit image (order 3 starts at 5)");
    const struct {
        int order, row, before, after;
    } speed_cases[] = {{2, 10, 4, 5}, {3, 4, 8, 8}, {2, 6, 7, 7}, {1, 3, 7, 7}};
    for (size_t i = 0; i < sizeof speed_cases / sizeof speed_cases[0]; ++i) {
        nm_start(m, &g_thor, speed_cases[i].order, speed_cases[i].row, 12000, &mode);
        nm_tick_silent(m);
        const int b = nm_peek(m, NES_FAMISTUDIO_SONG_SPEED);
        nm_apply_image(m, &fast);
        const int a = nm_peek(m, NES_FAMISTUDIO_SONG_SPEED);
        EXPECT(b == speed_cases[i].before && a == speed_cases[i].after, "speed edit at %d/%d: %d -> %d (want %d -> %d)",
               speed_cases[i].order, speed_cases[i].row, b, a, speed_cases[i].before, speed_cases[i].after);
    }
    nm_start(m, &g_thor, 2, 6, 12000, &mode);
    nm_apply_image(m, &fast);
    while (nm_frames(m) < (uint32_t)row_frame(2, 8) + 1) nm_tick_silent(m);
    EXPECT(nm_peek(m, NES_FAMISTUDIO_SONG_SPEED) == 5, "the edited Fxx runs when its row is reached");

    /* Identical image: same render. */
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    uint64_t h1 = 0xCBF29CE484222325ull, h2 = h1;
    for (int f = 0; f < 600; ++f) {
        if (f == 300) nm_apply_image(m, &g_thor);
        int n = nm_tick(m, g_out);
        h1 = fnv(h1, g_out, sizeof(float) * 2 * (size_t)(n > 0 ? n : 0));
    }
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    for (int f = 0; f < 600; ++f) {
        int n = nm_tick(m, g_out);
        h2 = fnv(h2, g_out, sizeof(float) * 2 * (size_t)(n > 0 ? n : 0));
    }
    EXPECT(h1 == h2, "applying the playing image changes nothing");

    /* Edits: the Lead sustain 12 -> 8 heard on the note already sustaining (order 0 row 9), the note
     * edit at order 2 row 8 heard when reached, rows unchanged. */
    nm_start(m, &g_thor, 0, 9, 12000, &mode);
    nm_tick_silent(m);
    nm_tick_silent(m);
    const uint8_t before = nm_peek(m, NES_FAMISTUDIO_ENV_VALUE + 0);
    nm_apply_image(m, &g_edited);
    nm_tick_silent(m);
    const uint8_t after = nm_peek(m, NES_FAMISTUDIO_ENV_VALUE + 0);
    EXPECT(before == 12 && after == 8, "sustaining Lead note follows its edited volume envelope (%d -> %d)", before, after);
    int o, r, tk, f = row_frame(0, 9) + 3, bad_rows = 0, note_at = -1;
    for (; f < row_frame(2, 9); ++f) {
        nm_position(m, &o, &r, &tk);
        int a, b, len, k = 0, start = 0;
        for (;; ++k) {
            song_row(k, &a, &b, &len);
            if (start + len > f) break;
            start += len;
        }
        bad_rows += o != a || r != b || tk != f - start;
        nm_tick_silent(m);
        if (o == 2 && r == 8 && note_at < 0 && nm_peek(m, NES_FAMISTUDIO_CHN_NOTE) == NOTE_D5) note_at = tk;
    }
    EXPECT(bad_rows == 0, "rows continue unchanged after the edit (%d wrong)", bad_rows);
    EXPECT(note_at == 0, "edited note D5 plays at order 2 row 8 (straight play: B4)");

    /* Shrunk song while in order 3: clamps to the last row of order 1, then loops to order 1. */
    nm_start(m, &g_thor, 3, 4, 12000, &mode);
    nm_tick_silent(m);
    nm_apply_image(m, &g_short);
    int seen_loop = 0, faults = 0;
    for (int i = 0; i < 400; ++i) {
        nm_position(m, &o, &r, &tk);
        if (i < 9 && tk == 0 && o == 1 && r == 0) seen_loop = 1;
        if (o >= 2) ++faults;
        if (nm_tick_silent(m) != NM_OK) ++faults;
    }
    EXPECT(seen_loop && faults == 0, "orders removed under the playhead: next row is the loop order, no fault");

    /* Raw layout: byte copy. */
    nm_start(m, &g_raw, 0, 0, 0, &mode);
    for (int i = 0; i < 50; ++i) nm_tick_silent(m);
    nm_apply_image(m, &g_raw);
    nm_position(m, &o, &r, &tk);
    EXPECT(nm_tick_silent(m) == NM_OK && o == 0 && r == 8, "raw apply keeps the transport (%d/%d)", o, r);
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
    printf("audition: one held note through the engine, release\n");
    EXPECT(nm_audition(m, &g_thor, 0, NOTE_A3, NES_FT_INSTRUMENT_LEAD, 0) == NM_OK, "audition pulse 1 A440 Lead");
    EXPECT(nm_release(m) == 0, "release before the first tick: nothing to release");
    nm_audition(m, &g_thor, 0, NOTE_A3, NES_FT_INSTRUMENT_LEAD, 0);
    int n = nm_tick(m, g_out);
    const NesApu* a = nm_apu(m);
    EXPECT(n > 0 && a->pulse[0].period == note_table_period(m, NOTE_A3), "first tick sounds A440 (period %d)", a->pulse[0].period);
    double e = energy(m, 120);
    a = nm_apu(m);
    EXPECT(e > 1.0 && (a->pulse[0].ctrl & 15) == 12 && !(a->pulse[1].ctrl & 15) && !(a->noise.ctrl & 15),
           "held 2 s at the sustain level 12, other channels silent (energy %.1f)", e);
    int ticks = 0, r;
    EXPECT(nm_release(m) == 1, "Lead has a release point: release phase");
    while ((r = nm_tick(m, g_out)) > 0 && ticks < 100) ++ticks;
    EXPECT(r == 0 && ticks == 4, "release tail 10 7 4 0 plays 4 ticks, then tick returns 0 (%d ticks)", ticks);
    EXPECT(nm_tick(m, g_out) == 0, "stays ended");

    nm_audition(m, &g_thor, 0, NOTE_A3, NES_FT_INSTRUMENT_LEAD, 10);
    nm_tick(m, g_out);
    EXPECT(nm_apu(m)->pulse[0].period == note_table_period(m, NOTE_A3) + 10, "fine +10 adds 10 to the period (%d)",
           nm_apu(m)->pulse[0].period);
    nm_audition(m, &g_thor, 1, NOTE_C3, NES_FT_INSTRUMENT_ARPY, 0);
    int arp_ok = 1;
    for (int i = 0; i < 6; ++i) {
        nm_tick(m, g_out);
        static const int steps[3] = {0, 4, 7};
        arp_ok &= nm_apu(m)->pulse[1].period == note_table_period(m, NOTE_C3 + steps[i % 3]);
    }
    EXPECT(arp_ok, "pulse 2 Arpy: the instrument's arpeggio 0 4 7 runs");
    nm_audition(m, &g_thor, 2, NOTE_C2, NES_FT_INSTRUMENT_BASS, -5);
    nm_tick(m, g_out);
    EXPECT(nm_apu(m)->tri.period == note_table_period(m, NOTE_C2) - 5 && energy(m, 30) > 1.0, "triangle C2 fine -5 (period %d)",
           nm_apu(m)->tri.period);
    EXPECT(nm_release(m) == 0, "Bass has no release point: cut");
    nm_audition(m, &g_thor, 3, 32, NES_FT_INSTRUMENT_HAT, 99);
    nm_tick(m, g_out);
    EXPECT((nm_apu(m)->noise.mode_period & 0x8F) == (0x80 | ((32 & 15) ^ 15)), "noise Hat: period index 15, short mode ($%02X)",
           nm_apu(m)->noise.mode_period);
    EXPECT(nm_audition(m, &g_thor, 4, 1, 1, 0) == NM_ERR_IMAGE, "no DPCM audition without a DPCM list");
    EXPECT(nm_audition(m, &g_thor, 0, 0, 0, 0) == NM_ERR_IMAGE && nm_audition(m, &g_thor, 0, 97, 0, 0) == NM_ERR_IMAGE,
           "notes outside 1..96 rejected");
    EXPECT(nm_audition(m, &g_thor, 0, 40, NES_FT_INSTRUMENTS, 0) == NM_ERR_IMAGE, "instrument past the table rejected");
    EXPECT(nm_audition(m, &g_thor, 0, 40, 0, 128) == NM_ERR_IMAGE, "fine outside -128..127 rejected");
    EXPECT(nm_audition(m, &g_raw, 0, 40, 0, 0) == NM_ERR_IMAGE, "raw layout cannot audition");
}

static void test_mute(NesMachine* m) {
    printf("mute: mixer-level channel borrowing\n");
    int mode;
    nm_start(m, &g_thor, 0, 0, 0, &mode);
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
    EXPECT(e_muted < e_all * 1e-6 && e_back > e_all * 0.2 && nm_frames(m) == 900,
           "mute silences, unmute restores, transport runs (energy %.1f, %.2g, %.1f)", e_all, e_muted, e_back);
    EXPECT(nm_mute_channel(m, 5, 1) == NM_ERR_IMAGE, "channel 5 does not exist");
    nm_mute_channel(m, 0, 1);
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    EXPECT(nm_apu(m)->mute == 0, "start clears mutes (power-on)");
}

static void test_faults(NesMachine* m) {
    printf("faults: invalid opcodes, BRK, runaway code, invalid stream data\n");
    int mode;
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_poke(m, 0x0300, 0x02); /* JAM */
    EXPECT(nm_call(m, 0x0300, 0) == NM_ERR_FAULT, "JAM opcode faults");
    EXPECT(nm_tick(m, g_out) == NM_ERR_FAULT, "a faulted machine stays silent");
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_poke(m, 0x0300, 0x00);
    EXPECT(nm_call(m, 0x0300, 0) == NM_ERR_FAULT, "BRK faults");
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_poke(m, 0x0300, 0x4C);
    nm_poke(m, 0x0301, 0x00);
    nm_poke(m, 0x0302, 0x03); /* JMP $0300 */
    EXPECT(nm_call(m, 0x0300, 0) == NM_ERR_FAULT, "a call longer than one frame faults");
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_poke(m, 0x0300, 0xA9);
    nm_poke(m, 0x0301, 0x42);
    nm_poke(m, 0x0302, 0x60); /* LDA #$42; RTS */
    EXPECT(nm_call(m, 0x0300, 0) == NM_OK && nm_last_call_cycles(m) == 8, "a valid call returns (%u cycles)",
           nm_last_call_cycles(m));

    /* Raw layout streams are not walked: an opcode the build does not know ($55: VRC6 saw volume)
     * reaches the engine's BRK trap on the first frame. */
    static NesImage bad;
    bad = g_raw;
    uint8_t* song = bad.bytes + NM_REGION_OFFSET;
    int ch0 = (song[5] | song[6] << 8) - NES_SONG_BASE;
    song[ch0] = 0x55;
    EXPECT(nm_load_image(&bad) == 0, "the raw image passes header validation");
    const ChipEngineOps* ops = chip_engine_ops("nes-famistudio");
    ChipMachine* cm = ops->create(RATE);
    EXPECT(ops->start(cm, (const ChipImage*)&bad, 0, 0, 0, &mode) == CHIP_OK, "starts");
    EXPECT(ops->tick(cm, g_out) == CHIP_ERR_FAULT, "unknown stream opcode -> engine BRK -> CHIP_ERR_FAULT");
    ops->destroy(cm);
}

static void test_chip_engine_ops(void) {
    printf("chip engine interface: \"nes-famistudio\"\n");
    const ChipEngineOps* ops = chip_engine_ops("nes-famistudio");
    EXPECT(ops != NULL, "registered");
    if (!ops) return;
    EXPECT(chip_engine_ops("gb-huge") != NULL && chip_engine_ops("gb-huge") != ops, "Game Boy still registered");
    EXPECT(ops->image_size == NES_IMAGE_SIZE && ops->image_storage >= ops->image_size, "image sizes (%zu)", ops->image_size);
    ChipImage* img = (ChipImage*)malloc(ops->image_storage);
    ChipImage* edited = (ChipImage*)malloc(ops->image_storage);
    EXPECT(ops->load_image(img, g_thor.bytes, ops->image_size) == CHIP_OK, "test image loads");
    EXPECT(ops->load_image(edited, g_edited.bytes, ops->image_size) == CHIP_OK, "edited image loads");
    EXPECT(ops->load_image(img, g_thor.bytes, ops->image_size - 1) == CHIP_ERR_IMAGE, "wrong size rejected");
    static NesImage bad;
    bad = g_thor;
    bad.bytes[4] ^= 1;
    EXPECT(ops->load_image(img, bad.bytes, ops->image_size) == CHIP_ERR_IMAGE, "other player build rejected");
    ops->load_image(img, g_thor.bytes, ops->image_size);

    ChipMachine* cm = ops->create(RATE);
    ChipMachine* au = ops->create(RATE);
    EXPECT(cm != NULL && au != NULL, "create(48000)");
    EXPECT(ops->create(CHIP_MAX_SAMPLE_RATE + 1) == NULL, "unsupported rate");
    int mode = -1, order, row, tick;
    EXPECT(ops->start(cm, img, 2, 0, 12000, &mode) == CHIP_OK && mode == CHIP_SEEK_FROM_START, "start at order 2");
    ops->position(cm, &order, &row, &tick);
    EXPECT(order == 2 && row == 0 && tick == 0, "position %d/%d/%d", order, row, tick);
    const int ticks = row_frame(2, 10) - row_frame(2, 0);
    double e = 0;
    for (int i = 0; i < ticks; ++i) {
        int n = ops->tick(cm, g_out);
        EXPECT(n > 0 && n <= (int)CHIP_MAX_TICK_FRAMES, "tick frames %d", n);
        for (int k = 0; k < 2 * n; ++k) e += g_out[k] * g_out[k];
    }
    EXPECT(e > 1.0, "non-silent through the ops (energy %.2f)", e);
    ops->position(cm, &order, &row, &tick);
    EXPECT(order == 2 && row == 10 && tick == 0, "%d ticks (8 rows at speed 7, 2 at speed 4) = 10 rows (%d/%d/%d)", ticks, order,
           row, tick);
    ops->apply_image(cm, edited);
    ops->position(cm, &order, &row, &tick);
    EXPECT(order == 2 && row == 10 && tick == 0, "apply keeps the position");
    /* Audition borrowing pulse 1, as the hosts do it. */
    EXPECT(ops->audition(au, img, 0, NOTE_A3, 0, 0) == CHIP_ERR_IMAGE, "ops audition: slot 0 (no instrument) rejected");
    EXPECT(ops->audition(au, img, 0, NOTE_A3, NES_FT_INSTRUMENTS + 1, 0) == CHIP_ERR_IMAGE, "ops audition: slot past the table rejected");
    EXPECT(ops->audition(au, img, 0, NOTE_A3, NES_FT_INSTRUMENTS, 0) == CHIP_OK, "ops audition: last slot = last table index");
    /* The hosts pass the cell's 1-based slot: Lead is table index 2, slot 3. */
    EXPECT(ops->audition(au, img, 0, NOTE_A3, NES_FT_INSTRUMENT_LEAD + 1, 0) == CHIP_OK, "audition through the ops");
    EXPECT(ops->mute(cm, 0, 1) == CHIP_OK, "song's pulse 1 muted while auditioning");
    int n1 = ops->tick(cm, g_out), n2 = ops->tick(au, g_out);
    EXPECT(n1 > 0 && n2 > 0, "both machines tick");
    EXPECT(ops->release(au) == 1, "release phase");
    int left = 0;
    while (ops->tick(au, g_out) > 0 && left < 100) ++left;
    EXPECT(left == 4, "released audition ends after its tail (%d ticks)", left);
    EXPECT(ops->mute(cm, 0, 0) == CHIP_OK, "unmute");
    ops->destroy(cm);
    ops->destroy(au);
    free(img);
    free(edited);
}

/* ---- DPCM: the DMC and FamiStudio's DPCM channel ------------------------------------------- */

/* NESdev "APU DMC" NTSC rate table, CPU cycles per output bit (written here independently of nes_apu.c). */
static const int kNesdevDmcRates[16] = {428, 380, 340, 320, 286, 254, 226, 214, 190, 160, 142, 128, 106, 84, 72, 54};

/* The level a DMC output unit reaches playing `n` bytes (LSB first) from `level`: +2 for a 1 bit while the
 * level is at most 125, -2 for a 0 bit while it is at least 2 (NESdev; independent of nes_apu.c). */
static int dmc_model(int level, const uint8_t* bytes, int n) {
    for (int i = 0; i < n; ++i) {
        for (int b = 0; b < 8; ++b) {
            if (bytes[i] >> b & 1) {
                if (level <= 125) level += 2;
            } else if (level >= 2) {
                level -= 2;
            }
        }
    }
    return level;
}

static uint8_t g_prg[0x8000]; /* cartridge space $8000-$FFFF for the DMC unit checks */

/* An APU reading g_prg with the DMC started at cycle 0: $4010 ctrl, $4011 level, $4012 start, $4013 length. */
static void dmc_rig(NesApu* a, uint8_t ctrl, uint8_t level, uint8_t start, uint8_t length) {
    memset(a, 0, sizeof *a);
    nes_apu_set_memory(a, g_prg);
    nes_apu_reset(a);
    nes_apu_write(a, 0, 0x4010, ctrl);
    nes_apu_write(a, 0, 0x4011, level);
    nes_apu_write(a, 0, 0x4012, start);
    nes_apu_write(a, 0, 0x4013, length);
    nes_apu_write(a, 0, 0x4015, 0x10);
}

/* The levels after each change, stepping one CPU cycle at a time from cycle 1 (and when they happened). */
static int dmc_levels(NesApu* a, int* out, uint32_t* times, int count, uint32_t limit) {
    int n = 0, prev = a->dmc.level;
    for (uint32_t t = 1; n < count && t < limit; ++t) {
        nes_apu_run(a, t);
        if (a->dmc.level != prev) {
            prev = a->dmc.level;
            times[n] = t;
            out[n++] = prev;
        }
    }
    return n;
}

static int dmc_equal(const NesDmc* a, const NesDmc* b) {
    return a->ctrl == b->ctrl && a->level == b->level && a->start == b->start && a->length == b->length && a->addr == b->addr &&
           a->remaining == b->remaining && a->buffer == b->buffer && a->buffer_full == b->buffer_full && a->shift == b->shift &&
           a->bits == b->bits && a->silence == b->silence && a->irq == b->irq && a->timer == b->timer && a->fetches == b->fetches;
}

static void test_apu_dmc(void) {
    printf("APU DMC: rate table, delta steps, clamping, length, wrap, loop, IRQ, $4015\n");
    static NesApu a, b;
    int levels[64];
    uint32_t times[64];
    memset(g_prg, 0, sizeof g_prg);
    memset(g_prg + 0x4000, 0xFF, 64); /* $C000: 1-bits */
    int rates_ok = 1;
    for (int r = 0; r < 16; ++r) {
        dmc_rig(&a, (uint8_t)r, 0, 0, 3);
        int n = dmc_levels(&a, levels, times, 20, 200000);
        int ok = n == 20 && levels[0] == 2 && levels[19] == 40;
        for (int i = 1; i < n; ++i) ok &= times[i] - times[i - 1] == (uint32_t)kNesdevDmcRates[r];
        if (!ok) printf("  rate %d: %d changes, first interval %u\n", r, n, n > 1 ? times[1] - times[0] : 0);
        rates_ok &= ok;
    }
    EXPECT(rates_ok, "1-bits raise the level by 2 once per NESdev rate period, all 16 NTSC rates (428..54 cycles)");

    g_prg[0x4000] = 0x0F;
    g_prg[0x4001] = 0xAA;
    memset(g_prg + 0x4002, 0x00, 62);
    dmc_rig(&a, 0x0F, 64, 0, 1);
    static const int want[24] = {66, 68, 70, 72, 70, 68, 66, 64, 62, 64, 62, 64, 62, 64, 62, 64, 62, 60, 58, 56, 54, 52, 50, 48};
    int n = dmc_levels(&a, levels, times, 24, 100000), pattern_ok = n == 24;
    for (int i = 0; i < n && pattern_ok; ++i) pattern_ok = levels[i] == want[i];
    EXPECT(pattern_ok, "bytes $0F $AA $00 from level 64, LSB first: 66 68 70 72 70 68 66 64 | 62 64 62 64 62 64 62 64 | 62 60 .. 48 "
                       "(%d levels, last %d)", n, n ? levels[n - 1] : -1);
    EXPECT(times[1] - times[0] == 54 && times[23] - times[0] == 23 * 54, "at rate $F every 54 CPU cycles (33.14 kHz)");

    memset(g_prg + 0x4000, 0xFF, 64);
    int high[2];
    for (int i = 0; i < 2; ++i) {
        dmc_rig(&a, 0x0F, (uint8_t)(124 + i), 0, 1);
        nes_apu_run(&a, 40000);
        high[i] = a.dmc.level;
    }
    memset(g_prg + 0x4000, 0x00, 64);
    dmc_rig(&a, 0x0F, 3, 0, 1);
    nes_apu_run(&a, 40000);
    EXPECT(high[0] == 126 && high[1] == 127 && a.dmc.level == 1,
           "clamped: 1-bits stop at 126 from 124 and at 127 from 125, 0-bits stop at 1 from 3 (%d %d %d)", high[0], high[1],
           a.dmc.level);

    dmc_rig(&a, 0x0F, 64, 0, 1);
    const uint8_t s0 = nes_apu_read_status(&a, 1);
    nes_apu_run(&a, 60000);
    const uint8_t s1 = nes_apu_read_status(&a, 60000);
    EXPECT((s0 & 0x10) && !(s1 & 0x10) && a.dmc.fetches == 17 && a.dmc.addr == 0xC011,
           "$4013 = 1: 17 bytes $C000-$C010 by DMA, $4015 bit 4 while bytes remain (%02X -> %02X, %u fetches, next $%04X)", s0, s1,
           a.dmc.fetches, a.dmc.addr);
    dmc_rig(&a, 0x0F, 64, 0xFF, 4);
    nes_apu_run(&a, 200000);
    EXPECT(a.dmc.fetches == 65 && a.dmc.addr == 0x8001, "$4012 = $FF, $4013 = 4: 65 bytes from $FFC0 wrap to $8000 (%u, next $%04X)",
           a.dmc.fetches, a.dmc.addr);

    dmc_rig(&a, 0x4F, 64, 0, 1);
    nes_apu_run(&a, 300000);
    EXPECT((nes_apu_read_status(&a, 300000) & 0x10) && a.dmc.fetches > 17 * 30 && a.dmc.addr >= 0xC000 && a.dmc.addr <= 0xC011 &&
               !a.dmc.irq,
           "loop: the 17 bytes restart at $C000 without end or IRQ (%u fetches)", a.dmc.fetches);

    dmc_rig(&a, 0x8F, 64, 0, 1);
    nes_apu_run(&a, 60000);
    const uint8_t i1 = nes_apu_read_status(&a, 60000), i2 = nes_apu_read_status(&a, 60001);
    nes_apu_write(&a, 60002, 0x4010, 0x0F);
    const uint8_t i3 = nes_apu_read_status(&a, 60003);
    dmc_rig(&a, 0x8F, 64, 0, 1);
    nes_apu_run(&a, 60000);
    nes_apu_write(&a, 60001, 0x4015, 0x00);
    const uint8_t i4 = nes_apu_read_status(&a, 60002);
    EXPECT((i1 & 0x80) && (i2 & 0x80) && !(i3 & 0x80) && !(i4 & 0x80),
           "IRQ flag at the end of a non-looping sample with $4010 bit 7; reads keep it, $4010/$4015 writes clear it");

    memset(g_prg + 0x4000, 0xFF, 64);
    dmc_rig(&a, 0x0F, 0, 0, 2);
    const uint32_t stop_at = 428 + 7 * 54 + 2 * 8 * 54 + 20; /* the silent output cycle, then 2 bytes: level 32 */
    nes_apu_run(&a, stop_at);
    const uint32_t fetched = a.dmc.fetches;
    const int at_stop = a.dmc.level;
    nes_apu_write(&a, stop_at, 0x4015, 0x00);
    const uint8_t s2 = nes_apu_read_status(&a, stop_at + 1);
    nes_apu_run(&a, stop_at + 30000);
    const int played_out = a.dmc.level;
    nes_apu_run(&a, stop_at + 60000);
    EXPECT(!(s2 & 0x10) && a.dmc.fetches == fetched && played_out > at_stop && played_out <= at_stop + 32 && a.dmc.level == played_out,
           "$4015 bit 4 clear mid-sample: no more fetches, the bits already fetched play out (%d -> %d), then the level holds",
           at_stop, played_out);
    dmc_rig(&a, 0x0F, 0, 0, 2);
    nes_apu_run(&a, 5000);
    nes_apu_write(&a, 5000, 0x4011, 100);
    const int direct = a.dmc.level;
    nes_apu_run(&a, 5000 + 5 * 54);
    EXPECT(direct == 100 && a.dmc.level > 100 && a.dmc.level <= 110, "$4011 during playback sets the level, the deltas continue from it (%d)",
           a.dmc.level);

    /* The DMC runs the same with and without synthesis (seeking keeps sample positions and levels). */
    blip_t* blip = blip_new(RATE / 10);
    blip_set_rates(blip, CPU_HZ, RATE);
    dmc_rig(&a, 0x4D, 30, 0, 2);
    dmc_rig(&b, 0x4D, 30, 0, 2);
    nes_apu_set_output(&a, blip);
    int same = 1;
    short buf[4096];
    for (int f = 0; f < 20; ++f) {
        const uint32_t cycles = (f & 1) ? 29781 : 29780;
        if (f == 7) {
            nes_apu_write(&a, 1234, 0x4010, 0x02);
            nes_apu_write(&b, 1234, 0x4010, 0x02);
        }
        nes_apu_end_frame(&a, cycles);
        nes_apu_end_frame(&b, cycles);
        blip_end_frame(blip, cycles);
        blip_read_samples(blip, buf, blip_samples_avail(blip), 0);
        same &= dmc_equal(&a.dmc, &b.dmc);
    }
    EXPECT(same && a.dmc.fetches > 100, "with and without output the DMC state is identical every frame (%u fetches)", a.dmc.fetches);
    blip_delete(blip);
}

static NesImage g_draw, g_dthor, g_dmoved, g_dedited, g_dboundary, g_big;

#define D_FIRST_PASS_ROWS (NES_DPCM_ORDERS * NES_DPCM_ROWS)
#define D_LOOP_ROWS ((NES_DPCM_ORDERS - NES_DPCM_LOOP) * NES_DPCM_ROWS)

/* Straight play of dpcm_test, as song_row for ft_test. */
static void dpcm_song_row(int k, int* order, int* row, int* frames) {
    if (k >= D_FIRST_PASS_ROWS) k = NES_DPCM_LOOP * NES_DPCM_ROWS + (k - D_FIRST_PASS_ROWS) % D_LOOP_ROWS;
    *order = k / NES_DPCM_ROWS;
    *row = k % NES_DPCM_ROWS;
    *frames = nes_dpcm_speed[*order][*row];
}

static int dpcm_frames(int rows) {
    int total = 0, o, r, f;
    for (int k = 0; k < rows; ++k) {
        dpcm_song_row(k, &o, &r, &f);
        total += f;
    }
    return total;
}

static int dpcm_row_frame(int order, int row) { return dpcm_frames(order * NES_DPCM_ROWS + row); }

/* The text's DMC event at (order, row) of a kind, or NULL. */
static const int16_t* dpcm_event(int order, int row, int kind) {
    for (int i = 0; i < NES_DPCM_EVENTS; ++i) {
        const int16_t* e = nes_dpcm_events[i];
        if (e[0] == order && e[1] == row && e[3] == kind) return e;
    }
    return NULL;
}

static int load_split(NesImage* image, const uint8_t* head, size_t head_size, uint16_t sample_start, const uint8_t* samples,
                      size_t sample_size, const char* name) {
    memset(image, 0, sizeof *image);
    memcpy(image->bytes, head, head_size);
    memcpy(image->bytes + NM_REGION_OFFSET + (sample_start - NES_SONG_BASE), samples, sample_size);
    int r = nm_load_image(image);
    EXPECT(r == 0, "%s loads (%s at %u)", name, image->info.problem ? image->info.problem : "-", image->info.problem_at);
    return r;
}

/* Whether frame f of `t` has the `n` (register, value) pairs as consecutive writes. */
static int has_writes(const Trace* t, int f, const uint8_t* pairs, int n) {
    for (int i = 0; i + n <= t->n[f]; ++i) {
        if (!memcmp(t->w[f] + 2 * i, pairs, (size_t)n * 2)) return 1;
    }
    return 0;
}

static int count_writes(const Trace* t, int from, int to, uint8_t reg, int value) {
    int count = 0;
    for (int f = from; f < to; ++f) {
        for (int i = 0; i < t->n[f]; ++i) count += t->w[f][2 * i] == reg && (value < 0 || t->w[f][2 * i + 1] == value);
    }
    return count;
}

static int is_dmc_reg(uint8_t reg) { return (reg >= 0x10 && reg <= 0x13) || reg == 0x15; }

/* The DMC register writes ($4010-$4013, $4015) of frame fa of a and fb of b are the same. */
static int same_dmc_writes(const Trace* a, int fa, const Trace* b, int fb) {
    uint8_t x[512], y[512];
    int nx = 0, ny = 0;
    for (int i = 0; i < a->n[fa]; ++i) {
        if (is_dmc_reg(a->w[fa][2 * i])) memcpy(x + 2 * nx++, a->w[fa] + 2 * i, 2);
    }
    for (int i = 0; i < b->n[fb]; ++i) {
        if (is_dmc_reg(b->w[fb][2 * i])) memcpy(y + 2 * ny++, b->w[fb] + 2 * i, 2);
    }
    return nx == ny && !memcmp(x, y, (size_t)nx * 2);
}

static const uint8_t* dpcm_list(const NesImage* image) { return nm_region(image) + (image->info.dpcm_list - NES_SONG_BASE); }

static void test_dpcm_images(void) {
    printf("DPCM images: list, samples and DPCM channel validation\n");
    const NesImageInfo* t = &g_dthor.info;
    EXPECT(t->mappings == NES_DPCM_MAPPINGS && t->samples == NES_DPCM_DMC_SIZE && g_dmoved.info.mappings == NES_DPCM_MAPPINGS &&
               g_dmoved.info.samples == NES_DPCM_MOVED_SAMPLES_SIZE && g_draw.info.samples == NES_DPCM_DMC_SIZE,
           "%d mappings, %u sample bytes (moved %u, raw %u)", t->mappings, t->samples, g_dmoved.info.samples, g_draw.info.samples);
    EXPECT(t->orders == NES_DPCM_ORDERS && t->loop == NES_DPCM_LOOP && t->rows[2] == NES_DPCM_ROWS, "orders %d loop %d", t->orders,
           t->loop);
    const uint8_t* l = dpcm_list(&g_dthor);
    const uint8_t* lm = dpcm_list(&g_dmoved);
    int moved = 0;
    for (int k = 0; k < NES_DPCM_MAPPINGS; ++k) moved += l[4 * k] != lm[4 * k] && !memcmp(l + 4 * k + 1, lm + 4 * k + 1, 3);
    EXPECT(moved == NES_DPCM_MAPPINGS, "moved variant: every mapping's sample at another address, same length/rate/level");
    static NesImage bad;
    const uint8_t* s = nm_region(&g_dthor);
    const uint32_t list = NM_REGION_OFFSET + t->dpcm_list - NES_SONG_BASE;
    const uint16_t ch0 = nm_rd16(s + 5), ch4 = nm_rd16(s + 13);
    const uint32_t p0 = NM_REGION_OFFSET + nm_rd16(s + (ch0 + 2 - NES_SONG_BASE)) - NES_SONG_BASE;
    const uint32_t p4 = NM_REGION_OFFSET + nm_rd16(s + (ch4 + 2 - NES_SONG_BASE)) - NES_SONG_BASE;
    const uint32_t lng = list + 4 * (NES_DPCM_MAP_LONG_15 - 1);
    struct {
        uint32_t at;
        uint8_t value;
        const char* what;
    } cases[] = {
        {NES_IMAGE_HEADER_DPCM_COUNT, 64, "64 DPCM list entries"},
        {NES_IMAGE_HEADER_DPCM_COUNT, 0, "DPCM notes without a DPCM list"},
        {NES_IMAGE_HEADER_DPCM_COUNT, NES_DPCM_MAPPINGS - 1, "a DPCM note past the list (mapping 8 used)"},
        {list + 2, 0x8F, "an entry enabling the DMC IRQ"},
        {list + 2, 0x1F, "an entry with an unused $4010 bit"},
        {list + 3, 128, "initial level 128"},
        {lng + 1, 0x50, "the Long sample running past the sample bytes"},
        {list, 0x1E, "a sample starting past the sample bytes"},
        {NES_IMAGE_HEADER_SAMPLE_SIZE, (uint8_t)(NES_DPCM_DMC_SIZE - 1), "sample bytes after the sample block"},
        {NES_IMAGE_HEADER_SAMPLE_SIZE + 1, 0x40, "a sample block past the vectors"},
        {NES_IMAGE_HEADER_SAMPLE_START, 0x01, "a sample block not 64-aligned"},
        {NES_IMAGE_HEADER_SAMPLE_START + 1, 0xBF, "a sample block below $C000"},
        {NES_IMAGE_HEADER_SAMPLE_START + 1, 0x00, "a sample block size without a start"},
        {NES_IMAGE_HEADER_SAMPLE_START, 0x40, "a sample block start above the samples ($C040)"},
        {NM_REGION_OFFSET + 4, 0xC0, "the DPCM list outside the data"},
        {p4, 0x45, "a release on the DPCM channel"},
        {p4, 0x80, "an instrument on the DPCM channel"},
        {p4, 0x4E, "fine pitch on the DPCM channel"},
        {p4, 0x70, "a volume on the DPCM channel"},
        {p4, 0x40, "an extended DPCM note past the list"},
        {p0, 0x52, "the delta counter on pulse 1"},
    };
    for (size_t i = 0; i < sizeof cases / sizeof cases[0]; ++i) {
        bad = g_dthor;
        bad.bytes[cases[i].at] = cases[i].value;
        int r = nm_load_image(&bad);
        EXPECT(r != 0, "rejected: %s", cases[i].what);
        if (r) printf("    %s: %s\n", cases[i].what, bad.info.problem);
    }
    bad = g_dthor;
    EXPECT(nm_load_image(&bad) == 0, "the unmodified copy still loads");
}

static void write_dpcm_evidence(NesMachine* m, const char* dir, const Trace* thor) {
    char path[512];
    static float wav[NM_MAX_TICK_FRAMES * 2 * 60 * 30];
    int mode;
    for (int dmc_only = 0; dmc_only < 2; ++dmc_only) {
        int frames = 0;
        nm_start(m, &g_dthor, 0, 0, 0, &mode);
        for (int c = 0; c < 4 && dmc_only; ++c) nm_mute_channel(m, c, 1);
        for (int f = 0; f < 60 * 30; ++f) {
            int n = nm_tick(m, wav + 2 * frames);
            frames += n > 0 ? n : 0;
        }
        snprintf(path, sizeof path, "%s/%s", dir, dmc_only ? "nes_dpcm_dmc.wav" : "nes_dpcm.wav");
        write_wav(path, wav, frames);
        printf("  wrote %s (%.1f s)\n", path, frames / (double)RATE);
    }
    snprintf(path, sizeof path, "%s/nes_dpcm_trace.txt", dir);
    FILE* ft = fopen(path, "w");
    if (ft) {
        fprintf(ft, "# frame: APU writes as reg=value (hex), FamiStudio 4.5.3 player (FamiTracker tempo), dpcm_test\n");
        for (int f = 0; f < thor->frames; ++f) {
            fprintf(ft, "%d:", f);
            for (int i = 0; i < thor->n[f]; ++i) fprintf(ft, " 40%02X=%02X", thor->w[f][2 * i], thor->w[f][2 * i + 1]);
            fprintf(ft, "\n");
        }
        fclose(ft);
    }
    snprintf(path, sizeof path, "%s/nes_dpcm.nsf", dir);
    FILE* fn = fopen(path, "wb");
    if (fn) {
        uint8_t h[128];
        memset(h, 0, sizeof h);
        memcpy(h, "NESM\x1A", 5);
        h[5] = 1, h[6] = 1, h[7] = 1;
        h[8] = 0x00, h[9] = 0x80;
        h[10] = (uint8_t)NES_PLAYER_INIT, h[11] = (uint8_t)(NES_PLAYER_INIT >> 8);
        h[12] = (uint8_t)NES_PLAYER_PLAY, h[13] = (uint8_t)(NES_PLAYER_PLAY >> 8);
        strcpy((char*)h + 0x0E, "Thor Tracker DPCM test");
        strcpy((char*)h + 0x2E, "Thor Tracker");
        strcpy((char*)h + 0x4E, "FamiStudio 4.5.3 engine");
        h[0x6E] = 0xFF, h[0x6F] = 0x40;
        h[0x78] = 0x20, h[0x79] = 0x4E;
        fwrite(h, 1, sizeof h, fn);
        static uint8_t body[NES_SAMPLE_BASE - 0x8000 + NES_DPCM_DMC_SIZE];
        memset(body, 0, sizeof body);
        memcpy(body, nes_player_code, NES_PLAYER_SIZE);
        memcpy(body + (NES_SONG_BASE - 0x8000), nes_dpcm_raw, NES_DPCM_RAW_SIZE);
        memcpy(body + (NES_SAMPLE_BASE - 0x8000), nes_dpcm_dmc, NES_DPCM_DMC_SIZE);
        fwrite(body, 1, sizeof body, fn);
        fclose(fn);
    }
}

static void test_dpcm_playback(NesMachine* m, const char* dir) {
    const int pass_rows = D_FIRST_PASS_ROWS + 2 * D_LOOP_ROWS;
    const int frames = dpcm_frames(pass_rows), first = dpcm_frames(D_FIRST_PASS_ROWS);
    printf("FamiStudio's export of dpcm_test.txt with its .dmc samples: %d rows (first pass + 2 loops), %d frames\n", pass_rows,
           frames);
    Trace raw, thor;
    trace_alloc(&raw, frames);
    trace_alloc(&thor, frames);
    play_trace(m, &g_draw, &raw);
    static NesDmc dmc[8192];
    static int pos[8192][3];
    int mode, faults = 0;
    double sum2 = 0, peak = 0, t0 = now_us();
    nm_start(m, &g_dthor, 0, 0, 0, &mode);
    nm_set_apu_logging(m, 1);
    for (int f = 0; f < frames; ++f) {
        nm_position(m, &pos[f][0], &pos[f][1], &pos[f][2]);
        int n = nm_tick(m, g_out);
        if (n <= 0) {
            ++faults;
            break;
        }
        record(m, &thor, f);
        dmc[f] = nm_apu(m)->dmc;
        for (int i = 0; i < 2 * n; ++i) {
            double v = fabs(g_out[i]);
            if (v > peak) peak = v;
            sum2 += g_out[i] * g_out[i];
        }
    }
    const double rms = sqrt(sum2 / (2.0 * frames * 798.7)), per_tick = (now_us() - t0) / frames;
    printf("  rms %.4f, peak %.4f, %.1f us per tick (%.0fx realtime)\n", rms, peak, per_tick, 16639.0 / per_tick);
    EXPECT(faults == 0 && rms > 0.03 && peak < 1.0, "plays without faults, non-silent, unclipped (rms %.4f peak %.4f)", rms, peak);
    int d = first_difference(&raw, &thor, 0, frames);
    int dmc_writes = 0;
    for (int reg = 0x10; reg <= 0x15; ++reg) dmc_writes += reg == 0x14 ? 0 : count_writes(&thor, 0, frames, (uint8_t)reg, -1);
    EXPECT(d < 0 && dmc_writes > 150,
           "Thor layout + samples writes the same APU registers as FamiStudio's export + .dmc, all %d frames, %d of them "
           "$4010-$4013/$4015 (first difference %d)",
           frames, dmc_writes, d);

    int k = 0, bad_rows = 0, row_start = 0;
    for (int f = 0; f < frames; ++f) {
        if (pos[f][2] != 0) continue;
        int o, r, len;
        dpcm_song_row(k++, &o, &r, &len);
        if (pos[f][0] != o || pos[f][1] != r || f != row_start) ++bad_rows;
        row_start += len;
    }
    EXPECT(bad_rows == 0 && k == pass_rows, "%d row starts at the frames speed (Fxx 6/5/3/7) gives (%d wrong)", k, bad_rows);

    /* The DMC writes the text asks for, at their rows and frames (delay, cut, stop, delta counter, no attack). */
    int starts = 0, bad = 0;
    for (int i = 0; i < NES_DPCM_EVENTS; ++i) {
        const int16_t* e = nes_dpcm_events[i];
        const int f = dpcm_row_frame(e[0], e[1]) + e[2];
        uint8_t w[12];
        int n = 0;
        if (e[3] == 1) {
            const uint8_t play[] = {0x15, 0x0F, 0x12, (uint8_t)e[5], 0x13, (uint8_t)e[6], 0x10, (uint8_t)e[4]};
            memcpy(w, play, sizeof play);
            n = sizeof play;
            if (e[7] >= 0) w[n++] = 0x11, w[n++] = (uint8_t)e[7];
            w[n++] = 0x15, w[n++] = 0x1F;
            ++starts;
        } else if (e[3] == 2) {
            w[n++] = 0x15, w[n++] = 0x0F;
        } else {
            w[n++] = 0x11, w[n++] = (uint8_t)e[7];
        }
        if (!has_writes(&thor, f, w, n / 2) && bad++ < 3) printf("  event %d (%d/%d +%d kind %d) missing at frame %d\n", i, e[0], e[1], e[2], e[3], f);
    }
    EXPECT(bad == 0, "all %d DMC events of dpcm_test.txt at their rows and frames, register for register", NES_DPCM_EVENTS);
    EXPECT(count_writes(&thor, 0, first, 0x15, 0x1F) == starts, "%d sample starts in the first pass and no others", starts);

    /* Samples play bit for bit: after a whole sample the level equals the bit model from the start level. */
    const int16_t* kick = dpcm_event(0, 10, 1);
    const int16_t* lng = dpcm_event(3, 0, 1);
    const int kick_end = dpcm_row_frame(0, 12) - 1, long_end = dpcm_row_frame(3, 8) - 1;
    const int want_kick = dmc_model(kick[7], nes_dpcm_dmc + 64 * kick[5], 16 * kick[6] + 1);
    const int want_long = dmc_model(lng[7], nes_dpcm_dmc + 64 * lng[5], 16 * lng[6] + 1);
    EXPECT(dmc[kick_end].remaining == 0 && dmc[kick_end].level == want_kick,
           "Kick at rate $C (order 0 row 10): after its %d bytes the level is %d, the bit model gives %d", 16 * kick[6] + 1,
           dmc[kick_end].level, want_kick);
    EXPECT(dmc[long_end].remaining == 0 && dmc[long_end].level == want_long,
           "Long at rate $F from level 0 (order 3 row 0): after its %d bytes the level is %d, the bit model gives %d",
           16 * lng[6] + 1, dmc[long_end].level, want_long);
    const int f_long = dpcm_row_frame(3, 0);
    int long_frames = 0;
    while (f_long + long_frames < frames && dmc[f_long + long_frames].remaining) ++long_frames;
    EXPECT(long_frames >= 14 && long_frames <= 15, "its 1025 bytes are fetched in %d frames (1025 x 8 x 54 cycles = 14.9 frames)",
           long_frames);

    /* Looped samples run until the Stop (order 1 rows 0-3; order 3 rows 8-14). */
    const int loops[2][3] = {{1, 0, 4}, {3, 8, 15}};
    for (int i = 0; i < 2; ++i) {
        const int l0 = dpcm_row_frame(loops[i][0], loops[i][1]), l1 = dpcm_row_frame(loops[i][0], loops[i][2]);
        int looping = 1;
        for (int f = l0; f < l1; ++f) looping &= dmc[f].remaining > 0 && (dmc[f].ctrl & 0x40);
        const uint32_t fetched = dmc[l1 - 1].fetches - dmc[l0 - 1].fetches;
        EXPECT(looping && fetched > 3 * 33 && dmc[l1].remaining == 0,
               "looped 33-byte sample at order %d rows %d-%d: %u bytes fetched (%.1f passes), stopped at row %d", loops[i][0],
               loops[i][1], loops[i][2] - 1, fetched, fetched / 33.0, loops[i][2]);
    }

    /* The DMC alone (others muted) is audible, and the render is deterministic. */
    uint64_t h[2];
    double e_dmc = 0;
    for (int pass = 0; pass < 2; ++pass) {
        nm_start(m, &g_dthor, 0, 0, 0, &mode);
        for (int c = 0; c < 4; ++c) nm_mute_channel(m, c, 1);
        h[pass] = 0xCBF29CE484222325ull;
        for (int f = 0; f < first; ++f) {
            int n = nm_tick(m, g_out);
            h[pass] = fnv(h[pass], g_out, sizeof(float) * 2 * (size_t)(n > 0 ? n : 0));
            if (!pass) {
                for (int i = 0; i < 2 * n; ++i) e_dmc += g_out[i] * g_out[i];
            }
        }
    }
    EXPECT(h[0] == h[1] && e_dmc > 10.0, "DMC alone: audible (energy %.1f over the first pass), deterministic", e_dmc);
    write_dpcm_evidence(m, dir, &thor);
    trace_free(&raw);
    trace_free(&thor);
}

static void test_dpcm_seek(NesMachine* m) {
    printf("DPCM seek: the DMC carries over a seek\n");
    const int frames = dpcm_frames(D_FIRST_PASS_ROWS + D_LOOP_ROWS);
    Trace ref, t;
    trace_alloc(&ref, frames);
    trace_alloc(&t, frames);
    static NesDmc ref_dmc[8192];
    int mode;
    nm_start(m, &g_dthor, 0, 0, 0, &mode);
    nm_set_apu_logging(m, 1);
    for (int f = 0; f < frames; ++f) {
        nm_tick_silent(m);
        record(m, &ref, f);
        ref_dmc[f] = nm_apu(m)->dmc;
    }
    /* In the sample-heavy order 2 (a note every row), mid-loop (order 1 row 2), mid-Long (order 3 row 4). */
    const int targets[][2] = {{2, 5}, {2, 9}, {2, 13}, {1, 2}, {3, 4}};
    for (size_t i = 0; i < sizeof targets / sizeof targets[0]; ++i) {
        const int o = targets[i][0], r = targets[i][1], f0 = dpcm_row_frame(o, r);
        const double s0 = now_us();
        const int res = nm_start(m, &g_dthor, o, r, 12000, &mode);
        printf("  seek to %d/%d (%d frames): %.3f ms\n", o, r, f0, (now_us() - s0) / 1000.0);
        const NesDmc landed = nm_apu(m)->dmc;
        const int same_state = res == NM_OK && mode == NM_SEEK_FROM_START && nm_frames(m) == (uint32_t)f0 && dmc_equal(&landed, &ref_dmc[f0 - 1]);
        nm_set_apu_logging(m, 1);
        int diff = -1;
        for (int k = 0; k < 200 && f0 + k < frames; ++k) {
            nm_tick_silent(m);
            record(m, &t, f0 + k);
            if (!same_frame(&ref, &t, f0 + k) || !dmc_equal(&nm_apu(m)->dmc, &ref_dmc[f0 + k])) {
                diff = k;
                break;
            }
        }
        EXPECT(same_state && diff < 0,
               "seek to %d/%d: DMC (level %d, %u bytes left) = straight play's at frame %d; writes and DMC in step for 200 frames "
               "(first difference %d)",
               o, r, landed.level, landed.remaining, f0, diff);
    }
    /* What is heard: the DMC alone after a seek into order 2 equals straight play from that frame. */
    NesMachine* straight = nm_create(RATE);
    const int f0 = dpcm_row_frame(2, 5);
    nm_start(m, &g_dthor, 2, 5, 12000, &mode);
    nm_start(straight, &g_dthor, 0, 0, 0, &mode);
    for (int f = 0; f < f0; ++f) nm_tick_silent(straight);
    uint64_t h1 = 0xCBF29CE484222325ull, h2 = h1;
    for (int c = 0; c < 4; ++c) {
        nm_mute_channel(m, c, 1);
        nm_mute_channel(straight, c, 1);
    }
    for (int f = 0; f < 120; ++f) {
        int n = nm_tick(m, g_out);
        h1 = fnv(h1, g_out, sizeof(float) * 2 * (size_t)(n > 0 ? n : 0));
        n = nm_tick(straight, g_out);
        h2 = fnv(h2, g_out, sizeof(float) * 2 * (size_t)(n > 0 ? n : 0));
    }
    EXPECT(h1 == h2, "DMC heard after the seek to 2/5 = straight play's (120 frames, same samples)");
    nm_destroy(straight);
    /* Order fallback: the streams start at order 2; its DMC writes run in step with straight play. */
    EXPECT(nm_start(m, &g_dthor, 2, 5, 40, &mode) == NM_OK && mode == NM_SEEK_FROM_ORDER, "budget 40: seek from order 2's start");
    nm_set_apu_logging(m, 1);
    int in_step = 1;
    for (int k = 0; k < 120; ++k) {
        nm_tick_silent(m);
        record(m, &t, f0 + k);
        in_step &= same_dmc_writes(&ref, f0 + k, &t, f0 + k);
    }
    EXPECT(in_step, "after the fallback the DMC writes run in step with straight play (120 frames)");
    trace_free(&ref);
    trace_free(&t);
}

static void test_dpcm_apply(NesMachine* m) {
    printf("DPCM live edits: samples moved, edited, unchanged\n");
    const int frames = dpcm_frames(D_FIRST_PASS_ROWS + D_LOOP_ROWS);
    Trace ref, t;
    trace_alloc(&ref, frames);
    trace_alloc(&t, frames);
    play_trace(m, &g_dthor, &ref);
    uint8_t to_plain[256];
    const uint8_t* l = dpcm_list(&g_dthor);
    const uint8_t* lm = dpcm_list(&g_dmoved);
    for (int k = 0; k < NES_DPCM_MAPPINGS; ++k) to_plain[lm[4 * k]] = l[4 * k];
    /* Every block and every sample moved, applied before every frame (alternating images). */
    int mode, relocated = 0, diff = -1;
    double worst_us = 0;
    nm_start(m, &g_dthor, 0, 0, 0, &mode);
    nm_set_apu_logging(m, 1);
    for (int f = 0; f < frames; ++f) {
        const NesDmc before = nm_apu(m)->dmc;
        const double a0 = now_us();
        nm_apply_image(m, (f & 1) ? &g_dmoved : &g_dthor);
        if (now_us() - a0 > worst_us) worst_us = now_us() - a0;
        relocated += before.remaining && nm_apu(m)->dmc.start != before.start && nm_apu(m)->dmc.addr != before.addr;
        nm_tick_silent(m);
        record(m, &t, f);
        for (int i = 0; i < t.n[f]; ++i) {
            if ((f & 1) && t.w[f][2 * i] == 0x12) t.w[f][2 * i + 1] = to_plain[t.w[f][2 * i + 1]];
        }
        if (diff < 0 && !same_frame(&ref, &t, f)) diff = f;
    }
    EXPECT(diff < 0 && relocated > 100,
           "moved samples applied before every frame: the same writes ($4012 = the moved address of the same sample) for %d frames, "
           "%d playing samples relocated (first difference %d)",
           frames, relocated, diff);
    printf("  worst apply_image: %.1f us\n", worst_us);
    uint64_t h[2];
    for (int pass = 0; pass < 2; ++pass) {
        nm_start(m, &g_dthor, 0, 0, 0, &mode);
        for (int c = 0; c < 4; ++c) nm_mute_channel(m, c, 1);
        h[pass] = 0xCBF29CE484222325ull;
        for (int f = 0; f < frames; ++f) {
            if (pass) nm_apply_image(m, (f & 1) ? &g_dmoved : &g_dthor);
            int n = nm_tick(m, g_out);
            h[pass] = fnv(h[pass], g_out, sizeof(float) * 2 * (size_t)(n > 0 ? n : 0));
        }
    }
    EXPECT(h[0] == h[1], "the DMC sounds identical with the samples moving under it every frame");

    /* An edited sample stops while it plays; an unchanged one continues. */
    nm_start(m, &g_dthor, 0, 0, 0, &mode);
    nm_tick_silent(m);
    nm_tick_silent(m);
    const NesDmc kick = nm_apu(m)->dmc;
    nm_apply_image(m, &g_dedited);
    const NesDmc stopped = nm_apu(m)->dmc;
    nm_tick_silent(m);
    EXPECT(kick.remaining > 0 && kick.start == 0 && stopped.remaining == 0 && nm_apu(m)->dmc.fetches == stopped.fetches,
           "the playing Kick stops when its bytes change (%u bytes were left)", kick.remaining);
    nm_start(m, &g_dthor, 0, 4, 12000, &mode);
    nm_tick_silent(m);
    const NesDmc snare = nm_apu(m)->dmc;
    nm_apply_image(m, &g_dedited);
    const NesDmc kept = nm_apu(m)->dmc;
    nm_tick_silent(m);
    EXPECT(snare.remaining > 0 && snare.start != 0 && dmc_equal(&kept, &snare) && nm_apu(m)->dmc.fetches > kept.fetches,
           "the playing Snare continues: its bytes did not change");
    const int16_t* e = dpcm_event(0, 8, 1);
    const int n = 16 * e[6] + 1;
    const int want_edit = dmc_model(e[7], nes_dpcm_edited_samples + 64 * e[5], n);
    const int want_old = dmc_model(e[7], nes_dpcm_dmc + 64 * e[5], n);
    nm_start(m, &g_dthor, 0, 7, 12000, &mode);
    nm_apply_image(m, &g_dedited);
    while (nm_frames(m) < (uint32_t)dpcm_row_frame(0, 10)) nm_tick_silent(m);
    EXPECT(want_edit != want_old && nm_apu(m)->dmc.level == want_edit,
           "the next Kick plays the edited bytes (level %d; model %d, unedited %d)", nm_apu(m)->dmc.level, want_edit, want_old);

    /* The same image mid-sample: nothing changes. */
    uint64_t h1 = 0xCBF29CE484222325ull, h2 = h1;
    for (int pass = 0; pass < 2; ++pass) {
        nm_start(m, &g_dthor, 0, 0, 0, &mode);
        for (int f = 0; f < 300; ++f) {
            if (pass && (f == 2 || f == 100)) nm_apply_image(m, &g_dthor);
            int k = nm_tick(m, g_out);
            if (pass) h2 = fnv(h2, g_out, sizeof(float) * 2 * (size_t)(k > 0 ? k : 0));
            else h1 = fnv(h1, g_out, sizeof(float) * 2 * (size_t)(k > 0 ? k : 0));
        }
    }
    EXPECT(h1 == h2, "applying the same image mid-sample changes nothing heard");
    trace_free(&ref);
    trace_free(&t);
}

static void test_dpcm_audition(NesMachine* m) {
    printf("DPCM audition: a mapping's sample through the engine; release = stop\n");
    EXPECT(nm_audition(m, &g_dthor, 4, NES_DPCM_MAP_KICK_15, 0, 0) == NM_OK, "audition DPCM mapping Kick (rate $F)");
    nm_set_apu_logging(m, 1);
    const int n = nm_tick(m, g_out);
    Trace t;
    trace_alloc(&t, 1);
    record(m, &t, 0);
    const uint8_t* e = dpcm_list(&g_dthor) + 4 * (NES_DPCM_MAP_KICK_15 - 1);
    const uint8_t want[] = {0x15, 0x0F, 0x12, e[0], 0x13, e[1], 0x10, e[2], 0x11, e[3], 0x15, 0x1F};
    const NesApu* a = nm_apu(m);
    EXPECT(n > 0 && has_writes(&t, 0, want, 6) && a->dmc.remaining > 0,
           "the first tick starts the Kick: $4012=%02X $4013=%02X $4010=%02X $4011=%02X, $4015=$1F", e[0], e[1], e[2], e[3]);
    FrameState st;
    capture_state(a, &st);
    EXPECT(!st.vol[0] && !st.vol[1] && !st.vol[3] && count_writes(&t, 0, 1, 0x15, 0x1F) == 1, "only the DMC sounds");
    EXPECT(energy(m, 4) > 0.05, "audible");
    EXPECT(nm_release(m) == 0, "release on DPCM: 0 (stop now; the host drops the machine)");
    nm_audition(m, &g_dthor, 4, NES_DPCM_MAP_LOOP_13, 0, 0);
    for (int i = 0; i < 120; ++i) nm_tick(m, g_out);
    EXPECT(nm_apu(m)->dmc.remaining > 0 && nm_apu(m)->dmc.fetches > 120 * 29780 / (8 * 84) * 9 / 10,
           "a looped mapping sustains for 2 s (%u bytes fetched)", nm_apu(m)->dmc.fetches);
    EXPECT(nm_audition(m, &g_dthor, 4, 0, 0, 0) == NM_ERR_IMAGE && nm_audition(m, &g_dthor, 4, NES_DPCM_MAPPINGS + 1, 0, 0) == NM_ERR_IMAGE,
           "list entries outside 1..%d rejected", NES_DPCM_MAPPINGS);
    EXPECT(nm_audition(m, &g_draw, 4, 1, 0, 0) == NM_ERR_IMAGE, "raw layout cannot audition");
    trace_free(&t);
}

static void test_dpcm_mute(NesMachine* m) {
    printf("DPCM mute: channel 4 silent in the mixer, the sample keeps running\n");
    int mode;
    nm_start(m, &g_dthor, 2, 0, 12000, &mode);
    for (int c = 0; c < 4; ++c) nm_mute_channel(m, c, 1);
    const double e_on = energy(m, 30);
    nm_mute_channel(m, 4, 1);
    energy(m, 6); /* the output stage settles from the step to level 0 */
    const uint32_t fetched = nm_apu(m)->dmc.fetches;
    const double e_off = energy(m, 24);
    const uint32_t during = nm_apu(m)->dmc.fetches - fetched;
    nm_mute_channel(m, 4, 0);
    const double e_back = energy(m, 30);
    EXPECT(e_on > 0.05 && e_off < e_on * 0.01 && e_back > 0.05 && during > 100,
           "DMC energy %.3f, muted %.5f, unmuted %.3f; %u bytes fetched while muted", e_on, e_off, e_back, during);
}

static void test_dpcm_chip_ops(void) {
    printf("chip engine interface: DPCM image, audition borrowing channel 4\n");
    const ChipEngineOps* ops = chip_engine_ops("nes-famistudio");
    ChipImage* img = (ChipImage*)malloc(ops->image_storage);
    EXPECT(ops->load_image(img, g_dthor.bytes, ops->image_size) == CHIP_OK, "DPCM image loads through the ops");
    ChipMachine* cm = ops->create(RATE);
    ChipMachine* au = ops->create(RATE);
    int mode, order, row, tick;
    EXPECT(ops->start(cm, img, 2, 0, 12000, &mode) == CHIP_OK, "start at order 2");
    EXPECT(ops->audition(au, img, 4, NES_DPCM_MAP_SNARE_15, 1, 0) == CHIP_OK, "audition DPCM mapping Snare with slot 1");
    EXPECT(ops->audition(au, img, 4, NES_DPCM_MAP_SNARE_15, 0, 0) == CHIP_ERR_IMAGE, "slot 0 still rejected");
    ops->audition(au, img, 4, NES_DPCM_MAP_SNARE_15, 1, 0);
    EXPECT(ops->mute(cm, 4, 1) == CHIP_OK, "song's DPCM muted while auditioning");
    int n1 = ops->tick(cm, g_out), n2 = ops->tick(au, g_out);
    EXPECT(n1 > 0 && n2 > 0 && nm_apu((const NesMachine*)au)->dmc.remaining > 0, "both tick, the sample plays");
    EXPECT(ops->release(au) == 0, "release = stop");
    EXPECT(ops->mute(cm, 4, 0) == CHIP_OK, "unmute");
    ops->position(cm, &order, &row, &tick);
    EXPECT(order == 2 && row == 0 && tick == 1, "song position kept (%d/%d/%d)", order, row, tick);
    ops->destroy(cm);
    ops->destroy(au);
    free(img);
}

/* $4012 values of frame f written while `image` was applied, mapped to g_dthor's addresses of the same mappings. */
static void map_sample_starts(Trace* t, int f, const NesImage* image) {
    const uint8_t* from = dpcm_list(image);
    const uint8_t* to = dpcm_list(&g_dthor);
    for (int i = 0; i < t->n[f]; ++i) {
        if (t->w[f][2 * i] != 0x12) continue;
        for (int k = 0; k < NES_DPCM_MAPPINGS; ++k) {
            if (from[4 * k] == t->w[f][2 * i + 1]) {
                t->w[f][2 * i + 1] = to[4 * k];
                break;
            }
        }
    }
}

/* DMC-only render (other channels muted), applying `alternate` before every odd frame if given. */
static uint64_t dmc_hash(NesMachine* m, const NesImage* image, const NesImage* alternate, int frames) {
    int mode;
    nm_start(m, image, 0, 0, 0, &mode);
    for (int c = 0; c < 4; ++c) nm_mute_channel(m, c, 1);
    uint64_t h = 0xCBF29CE484222325ull;
    for (int f = 0; f < frames; ++f) {
        if (alternate) nm_apply_image(m, (f & 1) ? alternate : image);
        int n = nm_tick(m, g_out);
        h = fnv(h, g_out, sizeof(float) * 2 * (size_t)(n > 0 ? n : 0));
    }
    return h;
}

static void test_shared_region(NesMachine* m) {
    printf("one region for song data and samples: a big sample-less song, a song ending at its samples\n");
    /* ft_test with its patterns at the top: the song data fills $8C00-$FFF9. */
    const NesImageInfo* b = &g_big.info;
    const uint8_t* s = nm_region(&g_big);
    const uint16_t ch0 = nm_rd16(s + 5), pat0 = nm_rd16(s + (ch0 + 2 - NES_SONG_BASE));
    EXPECT(b->size == NM_REGION_SIZE && b->size > 24570 && !b->samples && !b->sample_start && pat0 >= 0xC000,
           "sample-less image with %u bytes of song data (0.9.0's limit: 24570), patterns at $%04X", b->size, pat0);
    const int frames = song_frames(FIRST_PASS_ROWS + 2 * LOOP_ROWS);
    Trace ref, t;
    trace_alloc(&ref, frames);
    trace_alloc(&t, frames);
    play_trace(m, &g_thor, &ref);
    play_trace(m, &g_big, &t);
    int d = first_difference(&ref, &t, 0, frames);
    EXPECT(d < 0, "it plays: the same APU writes as the compact layout for %d frames (first difference %d)", frames, d);
    int mode;
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_set_apu_logging(m, 1);
    for (int f = 0; f < frames; ++f) {
        nm_apply_image(m, (f & 1) ? &g_big : &g_thor);
        nm_tick_silent(m);
        record(m, &t, f);
    }
    d = first_difference(&ref, &t, 0, frames);
    EXPECT(d < 0, "live apply between the compact and the full-region layout before every frame: same writes (first difference %d)", d);
    const int f0 = row_frame(2, 8);
    EXPECT(nm_start(m, &g_big, 2, 8, 12000, &mode) == NM_OK && nm_frames(m) == (uint32_t)f0, "seek to 2/8 in it");
    nm_set_apu_logging(m, 1);
    int in_step = 1;
    for (int k = 0; k < 200; ++k) {
        nm_tick_silent(m);
        record(m, &t, f0 + k);
        in_step &= same_frame(&ref, &t, f0 + k);
    }
    EXPECT(in_step, "  ... in step with straight play for 200 frames");
    trace_free(&ref);
    trace_free(&t);

    /* dpcm_test with its samples packed at the top and the song data ending exactly where they start. */
    const NesImageInfo* x = &g_dboundary.info;
    EXPECT(x->sample_start == NES_DPCM_BOUNDARY_SAMPLE_START && NES_SONG_BASE + x->size == x->sample_start &&
               x->samples == NES_DPCM_BOUNDARY_SAMPLES_SIZE && x->sample_start + x->samples <= NES_REGION_END && x->size > 13312,
           "samples at $%04X-$%04X, %u bytes of song data ending exactly at them", x->sample_start, x->sample_start + x->samples - 1,
           x->size);
    const int dframes = dpcm_frames(D_FIRST_PASS_ROWS + 2 * D_LOOP_ROWS);
    trace_alloc(&ref, dframes);
    trace_alloc(&t, dframes);
    play_trace(m, &g_dthor, &ref);
    play_trace(m, &g_dboundary, &t);
    for (int f = 0; f < dframes; ++f) map_sample_starts(&t, f, &g_dboundary);
    d = first_difference(&ref, &t, 0, dframes);
    EXPECT(d < 0, "it plays: the same APU writes as the $C000 layout ($4012 = the same samples) for %d frames (first difference %d)",
           dframes, d);
    const uint64_t h_plain = dmc_hash(m, &g_dthor, NULL, dframes);
    EXPECT(dmc_hash(m, &g_dboundary, NULL, dframes) == h_plain, "the DMC sounds sample-identical");
    EXPECT(dmc_hash(m, &g_dthor, &g_dboundary, dframes) == h_plain,
           "live apply between the two before every frame (samples move $C000 <-> $%04X): the DMC sounds identical", x->sample_start);
    static NesImage bad;
    bad = g_dboundary;
    bad.bytes[10] = (uint8_t)(x->size + 1);
    bad.bytes[11] = (uint8_t)((x->size + 1) >> 8);
    EXPECT(nm_load_image(&bad) != 0, "one more byte of song data overlaps the samples: rejected (%s)", bad.info.problem);
    bad = g_dboundary;
    bad.bytes[NES_IMAGE_HEADER_SAMPLE_START] = (uint8_t)(x->sample_start - 64);
    bad.bytes[NES_IMAGE_HEADER_SAMPLE_START + 1] = (uint8_t)((x->sample_start - 64) >> 8);
    EXPECT(nm_load_image(&bad) != 0, "a sample block starting 64 bytes into the song data: rejected (%s)", bad.info.problem);
    trace_free(&ref);
    trace_free(&t);
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    nm_build_raw_image(&g_raw, nes_ft_raw, NES_FT_RAW_SIZE, NULL, 0, 0, NES_FT_ROWS, NES_FT_ORDERS, NES_FT_LOOP);
    EXPECT(nm_load_image(&g_raw) == 0, "raw FamiStudio export loads (%s)", g_raw.info.problem ? g_raw.info.problem : "-");
    load_fixture(&g_thor, nes_ft_thor, NES_FT_THOR_SIZE, "Thor layout");
    load_fixture(&g_moved, nes_ft_moved, NES_FT_MOVED_SIZE, "moved");
    load_fixture(&g_edited, nes_ft_edited, NES_FT_EDITED_SIZE, "edited");
    load_fixture(&g_short, nes_ft_short, NES_FT_SHORT_SIZE, "short");
    nm_build_raw_image(&g_draw, nes_dpcm_raw, NES_DPCM_RAW_SIZE, nes_dpcm_dmc, NES_DPCM_DMC_SIZE, 0, NES_DPCM_ROWS, NES_DPCM_ORDERS,
                       NES_DPCM_LOOP);
    EXPECT(nm_load_image(&g_draw) == 0, "raw DPCM export + .dmc loads (%s)", g_draw.info.problem ? g_draw.info.problem : "-");
    load_split(&g_dthor, nes_dpcm_thor, NES_DPCM_THOR_SIZE, NES_DPCM_THOR_SAMPLE_START, nes_dpcm_thor_samples,
               NES_DPCM_THOR_SAMPLES_SIZE, "DPCM Thor layout");
    load_split(&g_dmoved, nes_dpcm_moved, NES_DPCM_MOVED_SIZE, NES_DPCM_MOVED_SAMPLE_START, nes_dpcm_moved_samples,
               NES_DPCM_MOVED_SAMPLES_SIZE, "DPCM moved");
    load_split(&g_dedited, nes_dpcm_edited, NES_DPCM_EDITED_SIZE, NES_DPCM_EDITED_SAMPLE_START, nes_dpcm_edited_samples,
               NES_DPCM_EDITED_SAMPLES_SIZE, "DPCM edited");
    load_split(&g_dboundary, nes_dpcm_boundary, NES_DPCM_BOUNDARY_SIZE, NES_DPCM_BOUNDARY_SAMPLE_START, nes_dpcm_boundary_samples,
               NES_DPCM_BOUNDARY_SAMPLES_SIZE, "DPCM samples at the top, song data up to them");
    load_fixture(&g_big, nes_ft_big, NES_FT_BIG_SIZE, "song data filling the whole region");
    NesMachine* m = nm_create(RATE);
    if (!m) {
        printf("nm_create failed\n");
        return 2;
    }
    test_apu_pitch();
    test_apu_counters();
    test_apu_dmc();
    test_player_image(m);
    test_images();
    test_playback(m, dir);
    test_seek(m);
    test_live_apply(m);
    test_audition(m);
    test_mute(m);
    test_faults(m);
    test_chip_engine_ops();
    test_dpcm_images();
    test_dpcm_playback(m, dir);
    test_dpcm_seek(m);
    test_dpcm_apply(m);
    test_dpcm_audition(m);
    test_dpcm_mute(m);
    test_dpcm_chip_ops();
    test_shared_region(m);
    nm_destroy(m);
    printf("NES: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
