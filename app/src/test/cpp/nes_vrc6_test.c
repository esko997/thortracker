/* Host regression test for the NES + Konami VRC6 build ("nes-famistudio-vrc6"): the VRC6 audio unit
 * (nes_vrc6.c) against the NESdev "VRC6 audio" description, and the FamiStudio sound engine 4.5.3 assembled
 * with FAMISTUDIO_EXP_VRC6 = 1 playing FamiStudio's own export of tools/audio/nes/vrc6_test.txt and the same
 * rows in the Thor Tracker image layout (nes_vrc6_song.h): identical register writes (APU and VRC6), row
 * timing, pitch of the VRC6 pulses and saw, duty, the saw master volumes, seek, live relocation and edits,
 * audition, mute, the image rules of the VRC6 build and the chip engine interface. The 2A03 build keeps its
 * own test (nes_machine_test.c); here it only proves the two builds refuse each other's images.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir]
 * Writes <dir>/nes_vrc6.wav (48 kHz stereo, 30 s), <dir>/nes_vrc6_trace.txt (per-frame APU + VRC6 writes) and
 * <dir>/nes_vrc6.nsf (the VRC6 player + FamiStudio's export, NSF expansion byte $7B = $01). */
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
#include "nes_vrc6.h"
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

/* Engine channels of the VRC6 build. */
#define CH_VPULSE1 5
#define CH_VPULSE2 6
#define CH_SAW 7

#define FIRST_PASS_ROWS (VRC6_TEST_ORDERS * VRC6_TEST_ROWS)
#define LOOP_ROWS ((VRC6_TEST_ORDERS - VRC6_TEST_LOOP) * VRC6_TEST_ROWS)

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

/* Frequency from rising zero crossings of a mono signal (mean removed). */
static double zero_cross_hz(const float* x, int n, int rate) {
    double mean = 0;
    for (int i = 0; i < n; ++i) mean += x[i];
    mean /= n;
    int first = -1, count = 0;
    double first_t = 0, last_t = 0;
    for (int i = 1; i < n; ++i) {
        double a = x[i - 1] - mean, b = x[i] - mean;
        if (a < 0 && b >= 0) {
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

/* ---- VRC6 unit checks (NESdev "VRC6 audio") ----------------------------------------------- */

/* Pulse output sampled once per divider clock over 16 clocks: duty D gives D + 1 high steps of 16, the mode
 * bit 16; the period is (t + 1) CPU cycles per step. */
static void test_vrc6_pulse(void) {
    printf("VRC6 unit: pulses\n");
    NesVrc6 v;
    memset(&v, 0, sizeof v);
    nes_vrc6_reset(&v);
    EXPECT(nes_vrc6_channel_out(&v, NES_VRC6_PULSE1) == 0 && nes_vrc6_channel_out(&v, NES_VRC6_SAW) == 0,
           "power-on: channels disabled, silent");
    const int period = 99; /* 100 CPU cycles per duty step */
    for (int duty = 0; duty < 8; ++duty) {
        nes_vrc6_reset(&v);
        nes_vrc6_write(&v, 0, 0x9000, (uint8_t)(duty << 4 | 10));
        nes_vrc6_write(&v, 0, 0x9001, (uint8_t)period);
        nes_vrc6_write(&v, 0, 0x9002, 0x80);
        int high = 0, wrong_level = 0;
        uint32_t t = 0;
        for (int step = 0; step < 16; ++step) {
            t += period + 1;
            nes_vrc6_run(&v, t);
            const int out = nes_vrc6_channel_out(&v, NES_VRC6_PULSE1);
            if (out) ++high;
            if (out && out != 10) ++wrong_level;
        }
        EXPECT(high == duty + 1 && !wrong_level, "duty %d: %d of 16 steps at volume 10 (want %d)", duty, high, duty + 1);
    }
    nes_vrc6_reset(&v);
    nes_vrc6_write(&v, 0, 0xA000, 0x8F); /* mode bit: always the volume */
    nes_vrc6_write(&v, 0, 0xA001, 0x10);
    nes_vrc6_write(&v, 0, 0xA002, 0x80);
    int always = 1;
    for (uint32_t t = 1; t < 2000; t += 37) {
        nes_vrc6_run(&v, t);
        always &= nes_vrc6_channel_out(&v, NES_VRC6_PULSE2) == 15;
    }
    EXPECT(always, "pulse 2 with the mode bit outputs its volume at every step");
    /* 12-bit period: the divider clocks every t + 1 cycles (the first clock one cycle after power-on). */
    nes_vrc6_reset(&v);
    nes_vrc6_write(&v, 0, 0x9000, 0x7F); /* duty 7: steps 7..0 high, 15..8 low */
    nes_vrc6_write(&v, 0, 0x9001, 0xFF);
    nes_vrc6_write(&v, 0, 0x9002, 0x8F); /* period $FFF */
    const uint32_t step7 = 1 + 4096u * 7; /* the 8th clock: step 15 - 8 = 7 */
    nes_vrc6_run(&v, step7 - 1);
    const int before = nes_vrc6_channel_out(&v, NES_VRC6_PULSE1);
    nes_vrc6_run(&v, step7);
    EXPECT(before == 0 && nes_vrc6_channel_out(&v, NES_VRC6_PULSE1) == 15,
           "period $FFF: 4096 cycles per step, step 7 (the first high one at duty 7) reached at cycle %u, not before", step7);
    /* Disable: silent and back to step 15. */
    nes_vrc6_write(&v, step7, 0x9002, 0x0F);
    EXPECT(nes_vrc6_channel_out(&v, NES_VRC6_PULSE1) == 0 && v.pulse[0].step == 15, "clearing E silences and resets to step 15");
    nes_vrc6_run(&v, 40000);
    EXPECT(v.pulse[0].step == 15, "held at step 15 while disabled");
}

static void test_vrc6_saw(void) {
    printf("VRC6 unit: saw\n");
    NesVrc6 v;
    memset(&v, 0, sizeof v);
    nes_vrc6_reset(&v);
    nes_vrc6_write(&v, 0, 0xB000, 42);
    nes_vrc6_write(&v, 0, 0xB001, 0); /* one CPU cycle per clock */
    nes_vrc6_write(&v, 0, 0xB002, 0x80);
    /* Rate added on clocks 2, 4 .. 12, reset on the 14th: outputs acc >> 3. */
    static const int want[14] = {0, 5, 5, 10, 10, 15, 15, 21, 21, 26, 26, 31, 31, 0};
    int ok = 1;
    for (int c = 1; c <= 28; ++c) {
        nes_vrc6_run(&v, (uint32_t)c);
        ok &= nes_vrc6_channel_out(&v, NES_VRC6_SAW) == want[(c - 1) % 14];
    }
    EXPECT(ok, "rate 42: 0 5 5 10 10 15 15 21 21 26 26 31 31 0, repeating every 14 clocks");
    nes_vrc6_write(&v, 28, 0xB000, 50);
    for (int c = 29; c <= 41; ++c) nes_vrc6_run(&v, (uint32_t)c);
    EXPECT(nes_vrc6_channel_out(&v, NES_VRC6_SAW) == (uint8_t)(6 * 50) >> 3, "rate 50 wraps the 8-bit accumulator (%d)",
           nes_vrc6_channel_out(&v, NES_VRC6_SAW));
    nes_vrc6_write(&v, 41, 0xB002, 0x00);
    EXPECT(nes_vrc6_channel_out(&v, NES_VRC6_SAW) == 0 && v.saw.acc == 0, "clearing E forces the accumulator to 0");
    /* $9003: H halts every oscillator, B shifts periods right by 4, A by 8. */
    nes_vrc6_reset(&v);
    nes_vrc6_write(&v, 0, 0x9000, 0x3F);
    nes_vrc6_write(&v, 0, 0x9001, 0x00);
    nes_vrc6_write(&v, 0, 0x9002, 0x81); /* period $100 */
    nes_vrc6_write(&v, 0, 0x9003, 0x02); /* B: $100 >> 4 = 16, 17 cycles per step */
    nes_vrc6_run(&v, 17 * 12);
    EXPECT(v.pulse[0].step == 3, "B: 12 steps in 12 x 17 cycles (step %d)", v.pulse[0].step);
    nes_vrc6_write(&v, 17 * 12, 0x9003, 0x06); /* A overrides B: $100 >> 8 = 1, 2 cycles per step */
    nes_vrc6_run(&v, 17 * 12 + 2 * 3);
    EXPECT(v.pulse[0].step == 0, "A: 3 steps in 6 cycles (step %d)", v.pulse[0].step);
    nes_vrc6_write(&v, 17 * 12 + 6, 0x9003, 0x01);
    nes_vrc6_run(&v, 20000);
    EXPECT(v.pulse[0].step == 0, "H halts the oscillators");
    /* Mix: a full pulse equals the 2A03 mixer's full pulse; mutes apply to the mix only. */
    nes_vrc6_reset(&v);
    nes_vrc6_write(&v, 0, 0x9000, 0x8F);
    nes_vrc6_write(&v, 0, 0x9002, 0x80);
    const double full_2a03 = 95.88 / (8128.0 / 15.0 + 100.0);
    EXPECT(fabs(nes_vrc6_mix_level(&v) - full_2a03) < 1e-4, "VRC6 pulse at 15 = 2A03 pulse at 15 (%.4f vs %.4f)",
           nes_vrc6_mix_level(&v), full_2a03);
    nes_vrc6_set_mute(&v, 1 << NES_VRC6_PULSE1);
    EXPECT(nes_vrc6_mix_level(&v) == 0.0f && nes_vrc6_channel_out(&v, NES_VRC6_PULSE1) == 15, "muted in the mix, still running");
    EXPECT(nes_vrc6_register(0x9003) && nes_vrc6_register(0xB002) && !nes_vrc6_register(0xA003) && !nes_vrc6_register(0xB003) &&
               !nes_vrc6_register(0x8FFF),
           "VRC6 audio registers: $9000-$9003, $A000-$A002, $B000-$B002");
}

/* ---- the VRC6 build playing the test song -------------------------------------------------- */

static int load_fixture(NesImage* image, const uint8_t* bytes, size_t size, const char* name) {
    memset(image, 0, sizeof *image);
    memcpy(image->bytes, bytes, size);
    int r = nm_load_image(image, &nes_player_vrc6);
    EXPECT(r == 0, "%s loads (%s at %u)", name, image->info.problem ? image->info.problem : "-", image->info.problem_at);
    return r;
}

static void song_row(int k, int* order, int* row, int* frames) {
    if (k >= FIRST_PASS_ROWS) k = VRC6_TEST_LOOP * VRC6_TEST_ROWS + (k - FIRST_PASS_ROWS) % LOOP_ROWS;
    *order = k / VRC6_TEST_ROWS;
    *row = k % VRC6_TEST_ROWS;
    *frames = nes_vrc6_speed[*order][*row];
}

static int song_frames(int rows) {
    int total = 0, o, r, f;
    for (int k = 0; k < rows; ++k) {
        song_row(k, &o, &r, &f);
        total += f;
    }
    return total;
}

static int row_frame(int order, int row) { return song_frames(order * VRC6_TEST_ROWS + row); }

static int table16(NesMachine* m, uint16_t lsb, uint16_t msb, int index) {
    return nm_peek(m, (uint16_t)(lsb + index)) | nm_peek(m, (uint16_t)(msb + index)) << 8;
}

static int pulse_table(NesMachine* m, int note) {
    return table16(m, NES_VRC6_FAMISTUDIO_NOTE_TABLE_LSB, NES_VRC6_FAMISTUDIO_NOTE_TABLE_MSB, note);
}

static int saw_table(NesMachine* m, int note) {
    return table16(m, NES_VRC6_FAMISTUDIO_SAW_NOTE_TABLE_LSB, NES_VRC6_FAMISTUDIO_SAW_NOTE_TABLE_MSB, note);
}

static int vpulse_period(const NesVrc6* v, int i) { return v->pulse[i].lo | (v->pulse[i].hi & 15) << 8; }
static int saw_period(const NesVrc6* v) { return v->saw.lo | (v->saw.hi & 15) << 8; }

/* Per-frame sound register writes (APU and VRC6) as (address low, address high, value). */
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

static void test_images(void) {
    printf("VRC6 images: build identity, 8 streams, VRC6 operand rules\n");
    nm_build_raw_image(&g_raw, &nes_player_vrc6, nes_vrc6_raw, NES_VRC6_RAW_SIZE, NULL, 0, 0, VRC6_TEST_ROWS, VRC6_TEST_ORDERS,
                       VRC6_TEST_LOOP);
    EXPECT(nm_load_image(&g_raw, &nes_player_vrc6) == 0, "FamiStudio's raw VRC6 export loads (%s)",
           g_raw.info.problem ? g_raw.info.problem : "-");
    EXPECT(g_raw.info.tempo_ntsc == VRC6_TEST_TEMPO_NTSC, "raw: NTSC tempo word after 8 channel pointers (%u)", g_raw.info.tempo_ntsc);
    load_fixture(&g_thor, nes_vrc6_thor, NES_VRC6_THOR_SIZE, "Thor layout");
    load_fixture(&g_moved, nes_vrc6_moved, NES_VRC6_MOVED_SIZE, "moved");
    load_fixture(&g_edited, nes_vrc6_edited, NES_VRC6_EDITED_SIZE, "edited");
    const NesImageInfo* i = &g_thor.info;
    EXPECT(i->orders == VRC6_TEST_ORDERS && i->loop == VRC6_TEST_LOOP && i->instruments == VRC6_TEST_INSTRUMENTS &&
               i->tempo_ntsc == VRC6_TEST_TEMPO_NTSC,
           "Thor layout: %u orders, loop %u, %u instruments, tempo step %u", i->orders, i->loop, i->instruments, i->tempo_ntsc);
    int streams_ok = 1;
    for (int c = 0; c < NES_VRC6_CHANNELS; ++c) streams_ok &= i->streams[c] >= NES_VRC6_SONG_BASE;
    EXPECT(streams_ok, "an order stream for each of the 8 channels");

    /* The builds refuse each other's images. */
    static NesImage other;
    memset(&other, 0, sizeof other);
    memcpy(other.bytes, nes_ft_thor, NES_FT_THOR_SIZE);
    EXPECT(nm_load_image(&other, &nes_player_vrc6) != 0, "a 2A03 image on the VRC6 build: rejected (%s)", other.info.problem);
    EXPECT(nm_load_image(&other, &nes_player_2a03) == 0, "... and loads on its own build");
    other = g_thor;
    EXPECT(nm_load_image(&other, &nes_player_2a03) != 0, "a VRC6 image on the 2A03 build: rejected (%s)", other.info.problem);

    /* VRC6 operands: duty 0..7 on the VRC6 pulses, $55 only $00/$01/$FF. */
    const uint8_t* s = nm_region(&g_thor);
    int duty_at = -1, saw_at = -1;
    for (uint32_t a = 0; a + 1 < i->size; ++a) {
        if (duty_at < 0 && s[a] == 0x4F && s[a + 1] == 0x07) duty_at = (int)a;
        if (saw_at < 0 && s[a] == 0x55 && s[a + 1] == 0x01) saw_at = (int)a;
    }
    EXPECT(duty_at >= 0 && saw_at >= 0, "the Thor layout holds a VRC6 duty 7 ($4F $07) and a Full saw volume ($55 $01)");
    static NesImage bad;
    if (duty_at >= 0) {
        bad = g_thor;
        bad.bytes[NM_REGION_OFFSET + duty_at + 1] = 0x08;
        EXPECT(nm_load_image(&bad, &nes_player_vrc6) != 0, "VRC6 duty 8: rejected (%s)", bad.info.problem);
    }
    if (saw_at >= 0) {
        bad = g_thor;
        bad.bytes[NM_REGION_OFFSET + saw_at + 1] = 0x02;
        EXPECT(nm_load_image(&bad, &nes_player_vrc6) != 0, "saw master volume $02: rejected (%s)", bad.info.problem);
        bad = g_thor;
        bad.bytes[NM_REGION_OFFSET + saw_at + 1] = 0xFF;
        EXPECT(nm_load_image(&bad, &nes_player_vrc6) == 0, "saw master volume $FF (Quarter) loads");
    }
    /* Header problems. */
    bad = g_thor;
    bad.bytes[8] = (uint8_t)NES_SONG_BASE, bad.bytes[9] = (uint8_t)(NES_SONG_BASE >> 8);
    EXPECT(nm_load_image(&bad, &nes_player_vrc6) != 0, "the 2A03 song base on the VRC6 build: rejected (%s)", bad.info.problem);
    bad = g_thor;
    bad.bytes[10] = (uint8_t)(NES_VRC6_IMAGE_REGION_SIZE + 1), bad.bytes[11] = (uint8_t)((NES_VRC6_IMAGE_REGION_SIZE + 1) >> 8);
    EXPECT(nm_load_image(&bad, &nes_player_vrc6) != 0, "data larger than the VRC6 region: rejected (%s)", bad.info.problem);
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
    snprintf(path, sizeof path, "%s/nes_vrc6.wav", dir);
    write_wav(path, wav, frames);
    printf("  wrote %s (%.1f s)\n", path, frames / (double)RATE);
    snprintf(path, sizeof path, "%s/nes_vrc6_trace.txt", dir);
    FILE* ft = fopen(path, "w");
    if (ft) {
        fprintf(ft, "# frame: APU and VRC6 writes as address=value (hex), FamiStudio 4.5.3 VRC6 player (FamiTracker tempo), vrc6_test\n");
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
    snprintf(path, sizeof path, "%s/nes_vrc6.nsf", dir);
    FILE* fn = fopen(path, "wb");
    if (fn) {
        uint8_t h[128];
        memset(h, 0, sizeof h);
        memcpy(h, "NESM\x1A", 5);
        h[5] = 1;
        h[6] = 1;
        h[7] = 1;
        h[8] = (uint8_t)NES_VRC6_PLAYER_BASE, h[9] = (uint8_t)(NES_VRC6_PLAYER_BASE >> 8);
        h[10] = (uint8_t)NES_VRC6_PLAYER_INIT, h[11] = (uint8_t)(NES_VRC6_PLAYER_INIT >> 8);
        h[12] = (uint8_t)NES_VRC6_PLAYER_PLAY, h[13] = (uint8_t)(NES_VRC6_PLAYER_PLAY >> 8);
        strcpy((char*)h + 0x0E, "Thor Tracker VRC6 test");
        strcpy((char*)h + 0x2E, "Thor Tracker");
        strcpy((char*)h + 0x4E, "FamiStudio 4.5.3 engine");
        h[0x6E] = 0xFF, h[0x6F] = 0x40;
        h[0x78] = 0x20, h[0x79] = 0x4E;
        h[0x7B] = 0x01; /* expansion: VRC6 */
        fwrite(h, 1, sizeof h, fn);
        static uint8_t body[NES_VRC6_SONG_BASE - 0x8000 + NES_VRC6_RAW_SIZE];
        memset(body, 0, sizeof body);
        memcpy(body, nes_vrc6_player_code, NES_VRC6_PLAYER_SIZE);
        memcpy(body + (NES_VRC6_SONG_BASE - 0x8000), nes_vrc6_raw, NES_VRC6_RAW_SIZE);
        fwrite(body, 1, sizeof body, fn);
        fclose(fn);
    }
}

/* Renders `ticks` frames from (order, row) with only `channel` audible; returns the mono samples. */
static int solo(NesMachine* m, int order, int row, int skip, int ticks, int channel, float* mono, int cap) {
    int mode, got = 0;
    nm_start(m, &g_thor, order, row, 12000, &mode);
    for (int c = 0; c < NES_VRC6_CHANNELS; ++c) nm_mute_channel(m, c, c != channel);
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
    printf("FamiStudio's VRC6 export of vrc6_test.txt: %d rows (first pass + 2 loops), %d frames\n", pass_rows, frames);
    Trace raw, thor;
    trace_alloc(&raw, frames);
    trace_alloc(&thor, frames);
    play_trace(m, &g_raw, &raw);
    int mode, faults = 0, nans = 0, vrc6_writes = 0, apu_writes = 0, bad_rows = 0, k = 0, row_start = 0;
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
        for (int i = 0; i < thor.n[f]; ++i) {
            if (thor.w[f][3 * i + 1] == 0x40) ++apu_writes;
            else ++vrc6_writes;
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
    EXPECT(worst_call < 29780 / 4, "engine frame within a quarter frame (worst %u cycles)", worst_call);
    EXPECT(vrc6_writes > frames * 3 && apu_writes > frames * 3, "the engine writes the VRC6 (%d) and the APU (%d) every frame",
           vrc6_writes, apu_writes);
    printf("  rms %.4f, peak %.4f, worst engine frame %u CPU cycles, %.1f us per tick (%.0fx realtime)\n", rms, peak, worst_call,
           per_tick, 16639.0 / per_tick);
    int d = first_difference(&raw, &thor, 0, frames);
    EXPECT(d < 0, "Thor layout writes the same APU and VRC6 registers as FamiStudio's export, all %d frames (first difference %d)",
           frames, d);
    EXPECT(bad_rows == 0 && k == pass_rows, "%d row starts at the frames the speeds give, loop to order %d (%d wrong)", k,
           VRC6_TEST_LOOP, bad_rows);

    /* nm_apu_log keeps reporting only the APU. */
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_set_apu_logging(m, 1);
    nm_tick_silent(m);
    uint8_t pairs[512];
    const int n_apu = nm_apu_log(m, pairs, 256);
    nm_tick_silent(m);
    uint8_t triples[768];
    const int n_all = nm_write_log(m, triples, 256);
    int vrc6_in_all = 0;
    for (int i = 0; i < n_all; ++i) vrc6_in_all += triples[3 * i + 1] != 0x40;
    EXPECT(n_apu > 0 && vrc6_in_all > 0, "apu_log: %d APU pairs; write_log: %d writes, %d of them VRC6", n_apu, n_all, vrc6_in_all);

    /* Registers at known rows: duty, period from the note tables, the saw master volume. */
    const NesVrc6* v = nm_vrc6(m);
    nm_start(m, &g_thor, 0, 0, 12000, &mode);
    nm_tick_silent(m);
    nm_tick_silent(m);
    const int note5 = nm_peek(m, NES_VRC6_FAMISTUDIO_CHN_NOTE + CH_VPULSE1);
    EXPECT((v->pulse[0].hi & 0x80) && ((v->pulse[0].ctrl >> 4) & 7) == 7 && vpulse_period(v, 0) == pulse_table(m, note5),
           "order 0 row 0, VRC6 pulse 1 VPulse: enabled, duty 7 (8/16), period %d = note table[%d] %d", vpulse_period(v, 0), note5,
           pulse_table(m, note5));
    EXPECT((v->saw.rate & 1) == 0 && v->saw.rate <= 30 && v->saw.rate > 0, "order 0 row 0, saw SawHalf: rate %d = volume x 2",
           v->saw.rate);
    nm_start(m, &g_thor, 1, 0, 12000, &mode);
    int full_ok = 1;
    for (int f = 0; f < 4; ++f) {
        nm_tick_silent(m);
        full_ok &= v->saw.rate == 40;
    }
    EXPECT(full_ok, "order 1 row 0, saw SawFull (volume 10): rate 40 = volume x 4");
    nm_start(m, &g_thor, 1, 8, 12000, &mode);
    nm_tick_silent(m);
    nm_tick_silent(m);
    EXPECT(v->saw.rate == 15, "order 1 row 8, saw SawQuarter (volume 15): rate 15 = volume x 1 (%d)", v->saw.rate);
    EXPECT(nm_peek(m, NES_VRC6_FAMISTUDIO_VRC6_SAW_VOLUME) == 0xFF, "engine saw volume -1 (Quarter)");
    nm_start(m, &g_thor, 2, 0, 12000, &mode);
    int duties[3];
    for (int f = 0; f < 3; ++f) {
        nm_tick_silent(m);
        duties[f] = (v->pulse[0].ctrl >> 4) & 7;
    }
    EXPECT(duties[0] == 1 && duties[1] == 3 && duties[2] == 5, "order 2 row 0, VThin duty envelope 1 3 5 (%d %d %d)", duties[0],
           duties[1], duties[2]);
    const int note7 = nm_peek(m, NES_VRC6_FAMISTUDIO_CHN_NOTE + CH_SAW);
    EXPECT(saw_period(v) == saw_table(m, note7) && (v->saw.hi & 0x80) && v->saw.rate % 4 == 0 && v->saw.rate < 40,
           "order 2 row 0, saw SawFull Volume=12: period %d = saw table[%d] %d, rate %d", saw_period(v), note7, saw_table(m, note7),
           v->saw.rate);
    /* Saw table = clock x 16 / (14 f) - 0.5 for 12-TET A440 (FamiStudio note 46 = A440). */
    int worst = 0;
    for (int i = 1; i <= 96; ++i) {
        const double f = 440.0 * pow(2.0, (i - 46) / 12.0), exact = CPU_HZ / (14.0 * f) - 1.0;
        const int err = (int)lround(fabs(saw_table(m, i) - exact) * 100);
        if (saw_table(m, i) < 4096 && err > worst) worst = err;
    }
    EXPECT(worst <= 100, "saw note table within 1 period unit of 12-TET (worst %.2f)", worst / 100.0);

    /* Pitch heard: VRC6 pulse 1 held at order 0 row 0 (4 rows), the saw at order 2 row 0 (8 rows). */
    static float mono[NM_MAX_TICK_FRAMES * 120];
    const int cap = (int)(sizeof mono / sizeof mono[0]);
    nm_start(m, &g_thor, 0, 0, 12000, &mode);
    nm_tick_silent(m);
    nm_tick_silent(m);
    int p = vpulse_period(v, 0);
    int got = solo(m, 0, 0, 2, 20, CH_VPULSE1, mono, cap);
    double hz = zero_cross_hz(mono, got, RATE), want = CPU_HZ / (16.0 * (p + 1));
    EXPECT(fabs(hz / want - 1) < 0.01, "VRC6 pulse 1 period %d: %.2f Hz, want %.2f", p, hz, want);
    nm_start(m, &g_thor, 2, 0, 12000, &mode);
    nm_tick_silent(m);
    nm_tick_silent(m);
    p = saw_period(v);
    got = solo(m, 2, 0, 2, 50, CH_SAW, mono, cap);
    hz = zero_cross_hz(mono, got, RATE), want = CPU_HZ / (14.0 * (p + 1));
    EXPECT(fabs(hz / want - 1) < 0.01, "VRC6 saw period %d: %.2f Hz, want %.2f", p, hz, want);
    printf("  saw: period %d -> %.2f Hz measured, %.2f Hz from the divider\n", p, hz, want);

    write_evidence(m, dir, &raw);
    trace_free(&raw);
    trace_free(&thor);
}

static void test_seek(NesMachine* m) {
    printf("VRC6 seek: exact rows from engine state\n");
    const int frames = song_frames(FIRST_PASS_ROWS + LOOP_ROWS);
    Trace ref, t;
    trace_alloc(&ref, frames);
    trace_alloc(&t, frames);
    play_trace(m, &g_thor, &ref);
    const int targets[][2] = {{0, 4}, {1, 0}, {1, 8}, {2, 8}, {2, 12}, {3, 15}};
    for (size_t i = 0; i < sizeof targets / sizeof targets[0]; ++i) {
        const int o = targets[i][0], r = targets[i][1], f0 = row_frame(o, r);
        int mode = -1, po, pr, pt;
        int res = nm_start(m, &g_thor, o, r, 12000, &mode);
        nm_position(m, &po, &pr, &pt);
        EXPECT(res == NM_OK && mode == NM_SEEK_FROM_START && po == o && pr == r && pt == 0 && nm_frames(m) == (uint32_t)f0,
               "seek %d/%d: from start, %u frames (row starts at %d)", o, r, nm_frames(m), f0);
        nm_set_apu_logging(m, 1);
        int same = 1;
        for (int f = f0; f < f0 + 200 && f < frames; ++f) {
            nm_tick_silent(m);
            record(m, &t, f);
            same &= same_frame(&ref, &t, f);
        }
        EXPECT(same, "seek %d/%d writes the same APU and VRC6 registers as straight play for 200 frames", o, r);
    }
    /* Order fallback: the streams are placed at the order start with the engine state of power-on, so the saw's
     * master volume at 2/8 (Full, from SawFull at 2/0; power-on is Half) comes from the order's own $55: each
     * order re-sends it with its first instrument. */
    int mode = -1, o, r, tk;
    nm_start(m, &g_thor, 2, 8, 12000, &mode);
    const int straight_volume = nm_peek(m, NES_VRC6_FAMISTUDIO_VRC6_SAW_VOLUME);
    EXPECT(nm_start(m, &g_thor, 2, 8, 70, &mode) == NM_OK && mode == NM_SEEK_FROM_ORDER, "small budget: seek from order 2's start");
    nm_position(m, &o, &r, &tk);
    EXPECT(o == 2 && r == 8 && tk == 0, "fallback lands on 2/8 (%d/%d/%d)", o, r, tk);
    EXPECT(straight_volume == 0x01 && nm_peek(m, NES_VRC6_FAMISTUDIO_VRC6_SAW_VOLUME) == straight_volume,
           "after the order fallback the engine holds straight play's saw volume ($%02X, Full)", straight_volume);
    EXPECT(nm_start(m, &g_thor, VRC6_TEST_ORDERS, 0, 12000, &mode) == NM_ERR_IMAGE, "order past the song rejected");
    static NesImage other;
    memset(&other, 0, sizeof other);
    memcpy(other.bytes, nes_ft_thor, NES_FT_THOR_SIZE);
    nm_load_image(&other, &nes_player_2a03);
    EXPECT(nm_start(m, &other, 0, 0, 0, &mode) == NM_ERR_IMAGE, "a VRC6 machine refuses an image loaded for the 2A03 build");
    trace_free(&ref);
    trace_free(&t);
}

static void test_live_apply(NesMachine* m) {
    printf("VRC6 live apply: relocation and edits at tick boundaries\n");
    const int frames = song_frames(FIRST_PASS_ROWS + 2 * LOOP_ROWS);
    Trace ref, t;
    trace_alloc(&ref, frames);
    trace_alloc(&t, frames);
    play_trace(m, &g_thor, &ref);
    int mode;
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
    EXPECT(d < 0, "moved data applied before each of %d frames: same writes as straight play (first difference %d)", frames, d);
    printf("  worst apply_image: %.1f us\n", worst_us);

    /* Identical Lead and VPulse envelopes keep separate identities: only VPulse's sustain is edited. */
    const NesVrc6* v = nm_vrc6(m);
    nm_start(m, &g_thor, 0, 0, 12000, &mode);
    for (int i = 0; i < 4; ++i) nm_tick_silent(m);
    const int before = v->pulse[0].ctrl & 15;
    nm_apply_image(m, &g_edited);
    nm_tick_silent(m);
    const int after = v->pulse[0].ctrl & 15;
    EXPECT(before == 12 && after == 8 && (nm_apu(m)->pulse[0].ctrl & 15) == 12,
           "VPulse sustain follows its edit (%d -> %d); identical unedited Lead stays at 12", before, after);
    nm_start(m, &g_edited, 2, 8, 12000, &mode);
    nm_tick_silent(m);
    const int edited_note = nm_peek(m, NES_VRC6_FAMISTUDIO_CHN_NOTE + CH_VPULSE1);
    nm_start(m, &g_thor, 2, 8, 12000, &mode);
    nm_tick_silent(m);
    const int original_note = nm_peek(m, NES_VRC6_FAMISTUDIO_CHN_NOTE + CH_VPULSE1);
    nm_start(m, &g_thor, 1, 4, 12000, &mode);
    nm_tick_silent(m);
    nm_apply_image(m, &g_edited);
    int o, r, tk, heard = -1;
    for (int i = 0; i < 400 && heard < 0; ++i) {
        nm_position(m, &o, &r, &tk);
        nm_tick_silent(m);
        if (o == 2 && r == 8 && tk == 0) heard = nm_peek(m, NES_VRC6_FAMISTUDIO_CHN_NOTE + CH_VPULSE1);
    }
    EXPECT(edited_note != original_note && heard == edited_note, "edited VRC6 note at order 2 row 8: %d (was %d, heard %d)",
           edited_note, original_note, heard);
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
    printf("VRC6 audition: held notes on the VRC6 channels, release\n");
    const NesVrc6* v = nm_vrc6(m);
    const int note = 46; /* A440 */
    EXPECT(nm_audition(m, &g_thor, CH_VPULSE1, note, VRC6_TEST_INSTRUMENT_VPULSE, 0) == NM_OK, "audition VRC6 pulse 1 VPulse");
    nm_tick(m, g_out);
    EXPECT(vpulse_period(v, 0) == pulse_table(m, note) && (v->pulse[0].hi & 0x80), "first tick sounds A440 (period %d)",
           vpulse_period(v, 0));
    double e = energy(m, 120);
    EXPECT(e > 1.0 && (v->pulse[0].ctrl & 15) == 12 && !(v->pulse[1].ctrl & 15) && !(nm_apu(m)->pulse[0].ctrl & 15),
           "held 2 s at the sustain level 12, other channels silent (energy %.1f)", e);
    EXPECT(nm_release(m) == 1, "VPulse has a release point: release phase");
    int ticks = 0, res;
    while ((res = nm_tick(m, g_out)) > 0 && ticks < 100) ++ticks;
    EXPECT(res == 0 && ticks == 4, "release tail 10 7 4 0 plays 4 ticks (%d)", ticks);
    EXPECT(nm_audition(m, &g_thor, CH_VPULSE2, note, VRC6_TEST_INSTRUMENT_VPULSE, 10) == NM_OK, "audition VRC6 pulse 2, fine +10");
    nm_tick(m, g_out);
    EXPECT(vpulse_period(v, 1) == pulse_table(m, note) + 10, "fine +10 adds 10 to the period (%d)", vpulse_period(v, 1));
    EXPECT(nm_audition(m, &g_thor, CH_SAW, note, VRC6_TEST_INSTRUMENT_SAWFULL, -3) == NM_OK, "audition the saw SawFull, fine -3");
    nm_tick(m, g_out);
    EXPECT(saw_period(v) == saw_table(m, note) - 3 && energy(m, 30) > 1.0, "saw A440 from the saw table, fine -3 (period %d)",
           saw_period(v));
    EXPECT(nm_release(m) == 0, "SawFull has no release point: cut");
    EXPECT(nm_audition(m, &g_thor, NES_VRC6_CHANNELS, note, 0, 0) == NM_ERR_IMAGE, "channel 8 does not exist");
    EXPECT(nm_audition(m, &g_thor, CH_SAW, note, VRC6_TEST_INSTRUMENTS, 0) == NM_ERR_IMAGE, "instrument past the table rejected");
    EXPECT(nm_audition(m, &g_raw, CH_SAW, note, 0, 0) == NM_ERR_IMAGE, "raw layout cannot audition");
}

static void test_mute(NesMachine* m) {
    printf("VRC6 mute: the three VRC6 channels in the mix\n");
    int mode;
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    double e_all = 0, e_muted = 0, e_back = 0;
    for (int f = 0; f < 600; ++f) {
        if (f == 200 || f == 400) {
            for (int c = 0; c < NES_VRC6_CHANNELS; ++c) nm_mute_channel(m, c, f == 200);
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
    /* Only the VRC6: the 2A03 part remains. */
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    for (int c = CH_VPULSE1; c <= CH_SAW; ++c) nm_mute_channel(m, c, 1);
    EXPECT(nm_vrc6(m)->mute == 7 && nm_apu(m)->mute == 0, "channels 5-7 mute the VRC6 unit's channels");
    EXPECT(energy(m, 200) > 0.1 && nes_vrc6_mix_level(nm_vrc6(m)) == 0.0f, "2A03 still heard, VRC6 silent in the mix");
    EXPECT(nm_mute_channel(m, NES_VRC6_CHANNELS, 1) == NM_ERR_IMAGE, "channel 8 does not exist");
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    EXPECT(nm_vrc6(m)->mute == 0, "start clears mutes (power-on)");
}

static void test_chip_engine_ops(const char* dir) {
    printf("chip engine interface: \"nes-famistudio-vrc6\"\n");
    const ChipEngineOps* ops = chip_engine_ops("nes-famistudio-vrc6");
    const ChipEngineOps* plain = chip_engine_ops("nes-famistudio");
    EXPECT(ops != NULL && plain != NULL && ops != plain, "registered beside the 2A03 engine");
    if (!ops) return;
    EXPECT(ops->image_size == NES_VRC6_IMAGE_SIZE && plain->image_size == NES_IMAGE_SIZE && ops->image_storage >= ops->image_size,
           "image sizes %zu (2A03: %zu)", ops->image_size, plain->image_size);
    ChipImage* img = (ChipImage*)malloc(ops->image_storage);
    static uint8_t bytes[NES_IMAGE_SIZE];
    memset(bytes, 0, sizeof bytes);
    memcpy(bytes, nes_vrc6_thor, NES_VRC6_THOR_SIZE);
    EXPECT(ops->load_image(img, bytes, ops->image_size) == CHIP_OK, "VRC6 image loads");
    EXPECT(ops->load_image(img, bytes, plain->image_size) == CHIP_ERR_IMAGE, "the 2A03 image size rejected");
    memset(bytes, 0, sizeof bytes);
    memcpy(bytes, nes_ft_thor, NES_FT_THOR_SIZE);
    EXPECT(ops->load_image(img, bytes, ops->image_size) == CHIP_ERR_IMAGE, "a 2A03 image cut to the VRC6 size rejected");
    memset(bytes, 0, sizeof bytes);
    memcpy(bytes, nes_vrc6_thor, NES_VRC6_THOR_SIZE);
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
    EXPECT(ops->audition(au, img, CH_SAW, 46, VRC6_TEST_INSTRUMENT_SAWHALF + 1, 0) == CHIP_OK, "saw audition through the ops");
    EXPECT(ops->mute(cm, CH_SAW, 1) == CHIP_OK && ops->mute(cm, NES_VRC6_CHANNELS, 1) == CHIP_ERR_IMAGE, "mute the song's saw; channel 8 rejected");
    EXPECT(ops->tick(cm, g_out) > 0 && ops->tick(au, g_out) > 0, "both machines tick");
    EXPECT(ops->release(au) == 1, "SawHalf has a release point");
    ops->destroy(cm);
    ops->destroy(au);
    free(img);
    (void)dir;
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    test_vrc6_pulse();
    test_vrc6_saw();
    test_images();
    NesMachine* m = nm_create(RATE, &nes_player_vrc6);
    if (!m) {
        printf("nm_create failed\n");
        return 2;
    }
    EXPECT(nm_player(m) == &nes_player_vrc6 && nes_player_vrc6.channels == 8 && nes_player_vrc6.song_base == NES_VRC6_SONG_BASE,
           "machine for the VRC6 build: 8 channels, song base $%04X", NES_VRC6_SONG_BASE);
    test_playback(m, dir);
    test_seek(m);
    test_live_apply(m);
    test_audition(m);
    test_mute(m);
    test_chip_engine_ops(dir);
    nm_destroy(m);
    printf("nes_vrc6_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
