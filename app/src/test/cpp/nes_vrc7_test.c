/* Host regression test for the NES + Konami VRC7 build ("nes-famistudio-vrc7"): the VRC7 audio unit (nes_vrc7.c on
 * emu2413) against the NESdev "VRC7 audio" description and the db_vrc7 hardware measurement, and the FamiStudio sound
 * engine 4.5.3 assembled with FAMISTUDIO_EXP_VRC7 = 1 playing FamiStudio's own export of tools/audio/nes/vrc7_test.txt
 * and the same rows in the Thor Tracker image layout (nes_vrc7_song.h): identical register writes (APU and VRC7), row
 * timing, FM pitch, the chip's one custom patch shared by the FM channels, key-off/stop, seek (the chip runs through
 * it), live relocation, edits and patch edits, audition and release, mute, the image rules of the VRC7 build, the
 * chip engine interface, and the WAV export of the short-looping VRC6 and VRC7 test songs.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir]
 * Writes <dir>/nes_vrc7.wav (48 kHz stereo, 30 s), <dir>/nes_vrc7_trace.txt (per-frame APU + VRC7 writes) and
 * <dir>/nes_vrc7.nsf (the VRC7 player + FamiStudio's export, NSF expansion byte $7B = $02). */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "blip_buf.h"
#include "chip_engine.h"
#include "chip_wav.h"
#include "nes_apu.h"
#include "nes_ft_song.h"
#include "nes_machine.h"
#include "nes_vrc6_song.h"
#include "nes_vrc7.h"
#include "nes_vrc7_song.h"

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
#define FRAME_CYCLES 29780u

/* Engine channels of the VRC7 build. */
#define CH_FM1 5
#define CH_FM2 6
#define CH_FM3 7
#define CH_FM4 8

#define FIRST_PASS_ROWS (VRC7_TEST_ORDERS * VRC7_TEST_ROWS)
#define LOOP_ROWS ((VRC7_TEST_ORDERS - VRC7_TEST_LOOP) * VRC7_TEST_ROWS)

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
static double zero_cross_hz(const short* x, int n, int rate) {
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

/* AC RMS of samples[skip..n). */
static double ac_rms(const short* x, int n, int skip) {
    double mean = 0, sum = 0;
    for (int i = skip; i < n; ++i) mean += x[i];
    mean /= (n - skip);
    for (int i = skip; i < n; ++i) sum += (x[i] - mean) * (x[i] - mean);
    return sqrt(sum / (n - skip));
}

/* ---- VRC7 unit checks (NESdev "VRC7 audio", nes-audio-tests db_vrc7) ----------------------- */

static void reg(NesVrc7* v, uint32_t t, uint8_t r, uint8_t value) {
    nes_vrc7_write(v, t, 0x9010, r);
    nes_vrc7_write(v, t, 0x9030, value);
}

/* Custom patch ($00-$07) on channel 0 at full volume, fnum 290, octave 4, keyed on. */
static void tone(NesVrc7* v, const uint8_t patch[8]) {
    for (int r = 0; r < 8; ++r) reg(v, 0, (uint8_t)r, patch[r]);
    reg(v, 0, 0x30, 0x00);
    reg(v, 0, 0x10, 290 & 0xFF);
    reg(v, 0, 0x20, (290 >> 8) | (4 << 1) | 0x10);
}

/* `seconds` of the unit (and/or a 2A03 unit) into a blip buffer at RATE; returns the samples read. */
static int render_units(NesVrc7* v, NesApu* a, double seconds, short* out, int cap) {
    blip_t* b = blip_new(RATE / 10);
    blip_set_rates(b, CPU_HZ, RATE);
    if (v) nes_vrc7_set_output(v, b);
    if (a) nes_apu_set_output(a, b);
    int got = 0;
    for (int f = 0; f < (int)(seconds * 60); ++f) {
        if (v) nes_vrc7_end_frame(v, FRAME_CYCLES);
        if (a) nes_apu_end_frame(a, FRAME_CYCLES);
        blip_end_frame(b, FRAME_CYCLES);
        got += blip_read_samples(b, out + got, cap - got < blip_samples_avail(b) ? cap - got : blip_samples_avail(b), 0);
    }
    if (v) nes_vrc7_set_output(v, NULL);
    if (a) nes_apu_set_output(a, NULL);
    blip_delete(b);
    return got;
}

/* The chips' synthesis state (registers, LFOs, noise, slot phases and envelopes, outputs). */
static int same_chip(const NesVrc7* a, const NesVrc7* b) {
    const OPLL *x = a->opll, *y = b->opll;
    if (x->eg_counter != y->eg_counter || x->pm_phase != y->pm_phase || x->am_phase != y->am_phase || x->noise != y->noise ||
        memcmp(x->reg, y->reg, sizeof x->reg) || memcmp(x->ch_out, y->ch_out, sizeof x->ch_out) || a->wait != b->wait) {
        return 0;
    }
    for (int i = 0; i < 12; ++i) {
        const OPLL_SLOT *s = &x->slot[i], *t = &y->slot[i];
        if (s->pg_phase != t->pg_phase || s->eg_out != t->eg_out || s->eg_state != t->eg_state || s->key_flag != t->key_flag ||
            s->output[0] != t->output[0] || s->output[1] != t->output[1]) {
            return 0;
        }
    }
    return 1;
}

static void test_vrc7_unit(void) {
    printf("VRC7 unit: registers, clock, balance, reset latch, silent run, mute, release end\n");
    static short pcm[RATE * 4], pcm2[RATE * 4];
    NesVrc7 v, w;
    EXPECT(nes_vrc7_init(&v) == 0 && nes_vrc7_init(&w) == 0, "two emu2413 instances");
    EXPECT(nes_vrc7_register(0x9010) && nes_vrc7_register(0x9030) && nes_vrc7_register(0xE000) && !nes_vrc7_register(0x9000) &&
               !nes_vrc7_register(0x9011) && !nes_vrc7_register(0xA000),
           "VRC7 audio addresses: $9010, $9030, $E000");

    /* Only the VRC7's registers reach the chip: no rhythm ($0E), no channels 7-9, no YM2413 mirrors. */
    reg(&v, 0, 0x0E, 0x20);
    reg(&v, 0, 0x16, 0x55);
    reg(&v, 0, 0x19, 0x66); /* a YM2413 mirror of $10 */
    reg(&v, 0, 0x15, 0x77);
    EXPECT(v.opll->reg[0x0E] == 0 && v.opll->reg[0x16] == 0 && v.opll->reg[0x10] == 0 && v.reg[0x15] == 0x77 &&
               v.opll->reg[0x15] == 0x77,
           "$0E, $16 and $19 ignored, $15 written");
    nes_vrc7_reset(&v);

    /* Clock: one sample per 36 CPU cycles, F = 49716 Hz x fnum / 2^(19 - octave) (NESdev). A near-pure sine: the
     * modulator at TL 63, no feedback. */
    const uint8_t sine[8] = {0x21, 0x21, 0x3F, 0x00, 0xF0, 0xF0, 0x0F, 0x0F};
    tone(&v, sine);
    int n = render_units(&v, NULL, 1.0, pcm, RATE * 2);
    const double hz = zero_cross_hz(pcm + RATE / 5, n - RATE / 5, RATE), want = CPU_HZ / 36.0 * 290 / 32768.0;
    EXPECT(fabs(hz / want - 1) < 0.002, "fnum 290 octave 4: %.2f Hz, want %.2f (36 CPU cycles per sample)", hz, want);

    /* Balance: db_vrc7's tone (rainwarrior's patch) over a full-volume 440 Hz 50% APU square: measured +11.3 and
     * +10.88 dB RMS on Lagrange Point carts. */
    nes_vrc7_reset(&v);
    const uint8_t db_patch[8] = {0x22, 0x21, 0x20, 0x07, 0xF0, 0xF0, 0x0F, 0x0F};
    tone(&v, db_patch);
    n = render_units(&v, NULL, 2.0, pcm, RATE * 4);
    const double fm_rms = ac_rms(pcm, n, RATE / 5);
    static NesApu apu;
    memset(&apu, 0, sizeof apu);
    nes_apu_reset(&apu);
    nes_apu_write(&apu, 0, 0x4015, 0x01);
    nes_apu_write(&apu, 0, 0x4000, 0xBF);
    nes_apu_write(&apu, 0, 0x4002, 253);
    nes_apu_write(&apu, 0, 0x4003, 0xF0);
    const int n2 = render_units(NULL, &apu, 2.0, pcm2, RATE * 4);
    const double sq_rms = ac_rms(pcm2, n2, RATE / 5), db = 20 * log10(fm_rms / sq_rms);
    EXPECT(fabs(db - NES_VRC7_DB) < 0.3, "db_vrc7 tone %.2f dB over the APU square (hardware: 10.88..11.3)", db);
    printf("  db_vrc7: VRC7 tone rms %.1f, APU square rms %.1f: %+.2f dB\n", fm_rms, sq_rms, db);

    /* $E000 bit 6: silent, registers and the tremolo LFO cleared, the vibrato phase kept, writes ignored. */
    nes_vrc7_end_frame(&v, FRAME_CYCLES);
    const uint32_t vibrato = v.opll->pm_phase;
    EXPECT(v.reg[0x20] == 0x19 && v.opll->slot[1].eg_out < 100, "keyed on and sounding before the reset");
    nes_vrc7_write(&v, 0, 0xE000, 0x40);
    int cleared = 1;
    for (int r = 0; r < 0x40; ++r) cleared &= v.reg[r] == 0 && v.opll->reg[r] == 0;
    EXPECT(cleared && v.opll->pm_phase == vibrato && v.opll->am_phase == 0 && nes_vrc7_mix_level(&v) == 0.0f,
           "$E000 = $40: registers and tremolo cleared, vibrato phase %u kept, silent", vibrato);
    reg(&v, 0, 0x30, 0x10);
    nes_vrc7_end_frame(&v, FRAME_CYCLES);
    EXPECT(v.reg[0x30] == 0 && v.opll->reg[0x30] == 0 && v.opll->pm_phase == vibrato && nes_vrc7_mix_level(&v) == 0.0f,
           "held: writes disregarded, not clocked, silent");
    nes_vrc7_write(&v, 0, 0xE000, 0x00);
    reg(&v, 0, 0x30, 0x10);
    EXPECT(v.reg[0x30] == 0x10, "$E000 = 0: writes accepted again");

    /* A silent run (no output: seeking) advances the chip exactly as an audible one; a mute leaves the chip alone. */
    nes_vrc7_reset(&v);
    nes_vrc7_reset(&w);
    tone(&v, db_patch);
    tone(&w, db_patch);
    nes_vrc7_set_mute(&w, 1);
    render_units(&v, NULL, 0.5, pcm, RATE);
    for (int f = 0; f < 30; ++f) nes_vrc7_end_frame(&w, FRAME_CYCLES);
    EXPECT(same_chip(&v, &w), "0.5 s with output and 0.5 s silent and muted: the same chip state");
    EXPECT(nes_vrc7_channel_out(&w, 0) == nes_vrc7_channel_out(&v, 0) && nes_vrc7_mix_level(&w) == 0.0f &&
               (nes_vrc7_mix_level(&v) != 0.0f) == (nes_vrc7_channel_out(&v, 0) != 0),
           "muted: the channel keeps its output, the mix leaves it out");

    /* Release end: a sustained patch keyed off decays to silence; with release rate 0 it holds for ever. */
    nes_vrc7_reset(&v);
    tone(&v, db_patch); /* carrier RR 15 */
    nes_vrc7_end_frame(&v, FRAME_CYCLES);
    EXPECT(!nes_vrc7_release_over(&v, 0), "keyed on: not over");
    reg(&v, 0, 0x20, (290 >> 8) | (4 << 1));
    int frames = 0;
    while (!nes_vrc7_release_over(&v, 0) && frames < 600) {
        nes_vrc7_end_frame(&v, FRAME_CYCLES);
        ++frames;
    }
    nes_vrc7_end_frame(&v, FRAME_CYCLES);
    EXPECT(frames < 60 && nes_vrc7_channel_out(&v, 0) == 0, "RR 15: silent and over %d frames after key-off", frames);
    nes_vrc7_reset(&v);
    const uint8_t held[8] = {0x22, 0x21, 0x20, 0x07, 0xF0, 0xF0, 0x0F, 0x00}; /* carrier EG = 1, RR 0 */
    tone(&v, held);
    nes_vrc7_end_frame(&v, FRAME_CYCLES);
    reg(&v, 0, 0x20, (290 >> 8) | (4 << 1));
    nes_vrc7_end_frame(&v, FRAME_CYCLES);
    int loud = 0;
    for (int f = 0; f < 30; ++f) {
        nes_vrc7_end_frame(&v, FRAME_CYCLES);
        loud |= nes_vrc7_channel_out(&v, 0) != 0;
    }
    EXPECT(nes_vrc7_release_over(&v, 0) && loud, "RR 0: keyed off but holding its level: over (can never change)");
    nes_vrc7_free(&v);
    nes_vrc7_free(&w);
}

/* ---- the VRC7 build playing the test song -------------------------------------------------- */

static int load_fixture(NesImage* image, const uint8_t* bytes, size_t size, const char* name) {
    memset(image, 0, sizeof *image);
    memcpy(image->bytes, bytes, size);
    int r = nm_load_image(image, &nes_player_vrc7);
    EXPECT(r == 0, "%s loads (%s at %u)", name, image->info.problem ? image->info.problem : "-", image->info.problem_at);
    return r;
}

/* Frames of the first `rows` rows of a test song (its speed table, then the loop). */
static int frames_of(const uint8_t* speed, int rows_per_order, int orders, int loop, int rows) {
    int total = 0;
    const int first = orders * rows_per_order, loop_rows = (orders - loop) * rows_per_order;
    for (int k = 0; k < rows; ++k) {
        const int j = k < first ? k : loop * rows_per_order + (k - first) % loop_rows;
        total += speed[j];
    }
    return total;
}

static int song_frames(int rows) { return frames_of(&nes_vrc7_speed[0][0], VRC7_TEST_ROWS, VRC7_TEST_ORDERS, VRC7_TEST_LOOP, rows); }
static int row_frame(int order, int row) { return song_frames(order * VRC7_TEST_ROWS + row); }

static void song_row(int k, int* order, int* row, int* frames) {
    if (k >= FIRST_PASS_ROWS) k = VRC7_TEST_LOOP * VRC7_TEST_ROWS + (k - FIRST_PASS_ROWS) % LOOP_ROWS;
    *order = k / VRC7_TEST_ROWS;
    *row = k % VRC7_TEST_ROWS;
    *frames = nes_vrc7_speed[*order][*row];
}

/* An FM channel's frequency from the chip registers: 49716 Hz x fnum / 2^(19 - block). */
static double fm_hz(const NesVrc7* v, int fm) {
    const int fnum = v->reg[0x10 + fm] | (v->reg[0x20 + fm] & 1) << 8, block = (v->reg[0x20 + fm] >> 1) & 7;
    return CPU_HZ / 36.0 * fnum / (double)(1 << (19 - block));
}

/* Expansion instrument entry `j` of an image (Thor layout). */
static uint8_t* exp_entry(NesImage* image, int j) {
    return image->bytes + NM_REGION_OFFSET + (image->info.exp_instruments - NES_VRC7_SONG_BASE) + NM_EXP_INSTRUMENT_SIZE * j;
}

/* Per-frame sound register writes (APU and VRC7) as (address low, address high, value). */
typedef struct {
    int frames;
    uint8_t (*w)[1536];
    int* n;
} Trace;

static void trace_alloc(Trace* t, int frames) {
    t->frames = frames;
    t->w = calloc((size_t)frames, 1536);
    t->n = (int*)calloc((size_t)frames, sizeof(int));
}

static void trace_free(Trace* t) {
    free(t->w);
    free(t->n);
}

static void record(NesMachine* m, Trace* t, int f) { t->n[f] = nm_write_log(m, t->w[f], 512); }

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

/* The start of the order-0 pattern of channel `c` (Thor layout) and its entry count. */
static uint8_t* pattern0(NesImage* image, int c, int* entries) {
    uint8_t* e = image->bytes + NM_REGION_OFFSET + (image->info.streams[c] - NES_VRC7_SONG_BASE);
    *entries = e[1];
    return image->bytes + NM_REGION_OFFSET + (nm_rd16(e + 2) - NES_VRC7_SONG_BASE);
}

static void expect_rejected(NesImage* bad, const char* why, const char* what) {
    const int r = nm_load_image(bad, &nes_player_vrc7);
    EXPECT(r != 0 && bad->info.problem && !strcmp(bad->info.problem, why), "%s: rejected with \"%s\" (%s)", what, why,
           r ? bad->info.problem : "loaded");
}

static void test_images(void) {
    printf("VRC7 images: build identity, 11 streams, expansion instruments, VRC7 opcode rules\n");
    nm_build_raw_image(&g_raw, &nes_player_vrc7, nes_vrc7_raw, NES_VRC7_RAW_SIZE, NULL, 0, 0, VRC7_TEST_ROWS, VRC7_TEST_ORDERS,
                       VRC7_TEST_LOOP);
    EXPECT(nm_load_image(&g_raw, &nes_player_vrc7) == 0, "FamiStudio's raw VRC7 export loads (%s)",
           g_raw.info.problem ? g_raw.info.problem : "-");
    EXPECT(g_raw.info.tempo_ntsc == VRC7_TEST_TEMPO_NTSC, "raw: NTSC tempo word after 7 header bytes and 11 channel pointers (%u)",
           g_raw.info.tempo_ntsc);
    load_fixture(&g_thor, nes_vrc7_thor, NES_VRC7_THOR_SIZE, "Thor layout");
    load_fixture(&g_moved, nes_vrc7_moved, NES_VRC7_MOVED_SIZE, "moved");
    load_fixture(&g_edited, nes_vrc7_edited, NES_VRC7_EDITED_SIZE, "edited");
    const NesImageInfo* i = &g_thor.info;
    EXPECT(i->orders == VRC7_TEST_ORDERS && i->loop == VRC7_TEST_LOOP && i->instruments == VRC7_TEST_INSTRUMENTS &&
               i->tempo_ntsc == VRC7_TEST_TEMPO_NTSC && i->exp_instruments >= NES_VRC7_SONG_BASE,
           "Thor layout: %u orders, loop %u, %u instruments per table, tempo step %u, expansion table at $%04X", i->orders,
           i->loop, i->instruments, i->tempo_ntsc, i->exp_instruments);
    int streams_ok = 1;
    for (int c = 0; c < NES_VRC7_CHANNELS; ++c) streams_ok &= i->streams[c] >= NES_VRC7_SONG_BASE;
    EXPECT(streams_ok, "an order stream for each of the 11 channels");
    for (int o = 0; o < VRC7_TEST_ORDERS; ++o) {
        EXPECT(i->speed[o] == nes_vrc7_speed[o][0], "order %d starts at speed %d (%d)", o, nes_vrc7_speed[o][0], i->speed[o]);
    }
    EXPECT(!memcmp(exp_entry(&g_thor, VRC7_TEST_EXP_FCUSTOMA) + 6, "\x00\x00\x31\x61\x14\x05\xA8\x64\x61\x27", 10) &&
               exp_entry(&g_thor, VRC7_TEST_EXP_FBELL)[7] == 0x10 && exp_entry(&g_thor, VRC7_TEST_EXP_FGUITAR)[6] == 0x20 &&
               exp_entry(&g_thor, VRC7_TEST_EXP_FBASS)[6] == 0x80,
           "expansion entries: FCustomA's registers, FBell patch 1, FGuitar release override, FBass stop override");

    /* The builds refuse each other's images. */
    static NesImage other, bad;
    memset(&other, 0, sizeof other);
    memcpy(other.bytes, nes_ft_thor, NES_FT_THOR_SIZE);
    EXPECT(nm_load_image(&other, &nes_player_vrc7) != 0, "a 2A03 image on the VRC7 build: rejected (%s)", other.info.problem);
    memset(&other, 0, sizeof other);
    memcpy(other.bytes, nes_vrc6_thor, NES_VRC6_THOR_SIZE);
    EXPECT(nm_load_image(&other, &nes_player_vrc7) != 0, "a VRC6 image on the VRC7 build: rejected (%s)", other.info.problem);
    other = g_thor;
    EXPECT(nm_load_image(&other, &nes_player_2a03) != 0 && (other = g_thor, nm_load_image(&other, &nes_player_vrc6) != 0),
           "a VRC7 image on the 2A03 and VRC6 builds: rejected");

    /* Expansion instruments: envelope kinds, override and patch bytes, the table inside the data. */
    bad = g_thor;
    exp_entry(&bad, VRC7_TEST_EXP_FBELL)[6] = 0x40;
    expect_rejected(&bad, "VRC7 override byte not a combination of $20 and $80", "override $40");
    bad = g_thor;
    exp_entry(&bad, VRC7_TEST_EXP_FBELL)[6] = 0xA0;
    EXPECT(nm_load_image(&bad, &nes_player_vrc7) == 0, "override $A0 (release and stop) loads");
    bad = g_thor;
    exp_entry(&bad, VRC7_TEST_EXP_FBELL)[7] = 0x11;
    expect_rejected(&bad, "VRC7 patch byte not patch << 4", "patch byte $11");
    bad = g_thor;
    memcpy(exp_entry(&bad, VRC7_TEST_EXP_FBELL), exp_entry(&g_thor, VRC7_TEST_EXP_FBELL) + 2, 2); /* volume <- arpeggio */
    expect_rejected(&bad, "expansion instrument envelope of the wrong kind", "an arpeggio as the volume envelope");
    bad = g_thor;
    bad.bytes[NM_REGION_OFFSET + 3] = 0xF0, bad.bytes[NM_REGION_OFFSET + 4] = 0xFF;
    expect_rejected(&bad, "expansion instrument table outside the data", "expansion pointer past the data");

    /* Rows: `$56` only on the FM channels, no duty or phase reset there. FM 1's order-0 pattern holds a `$56`
     * (G4 Release at row 10) and a volume `$7A` (E4 Volume 10). */
    int entries = 0;
    uint8_t* fm1 = pattern0(&g_thor, CH_FM1, &entries);
    int vol_at = -1, rel_at = -1;
    for (int k = 0; k < 64; ++k) {
        if (vol_at < 0 && fm1[k] == 0x7A) vol_at = k;
        if (rel_at < 0 && fm1[k] == 0x56) rel_at = k;
    }
    EXPECT(vol_at >= 0 && rel_at >= 0, "FM 1's first pattern holds a volume $7A and a VRC7 release $56");
    if (vol_at >= 0) {
        const uint32_t at = (uint32_t)(fm1 - g_thor.bytes) + (uint32_t)vol_at;
        bad = g_thor;
        bad.bytes[at] = 0x4F;
        expect_rejected(&bad, "duty effect on a VRC7 channel", "duty on FM 1");
        bad = g_thor;
        bad.bytes[at] = 0x53;
        expect_rejected(&bad, "phase reset only on the pulses", "phase reset on FM 1");
    }
    /* Pulse 1 playing FM 1's order-0 pattern (same rows): its `$56` is refused there. */
    bad = g_thor;
    uint8_t* e0 = bad.bytes + NM_REGION_OFFSET + (bad.info.streams[0] - NES_VRC7_SONG_BASE);
    const uint8_t* e5 = bad.bytes + NM_REGION_OFFSET + (bad.info.streams[CH_FM1] - NES_VRC7_SONG_BASE);
    memcpy(e0 + 1, e5 + 1, 3);
    expect_rejected(&bad, "VRC7 release off the FM channels", "$56 on pulse 1");
    /* Header problems. */
    bad = g_thor;
    bad.bytes[8] = (uint8_t)NES_SONG_BASE, bad.bytes[9] = (uint8_t)(NES_SONG_BASE >> 8);
    EXPECT(nm_load_image(&bad, &nes_player_vrc7) != 0, "the 2A03 song base on the VRC7 build: rejected (%s)", bad.info.problem);
    bad = g_thor;
    bad.bytes[10] = (uint8_t)(NES_VRC7_IMAGE_REGION_SIZE + 1), bad.bytes[11] = (uint8_t)((NES_VRC7_IMAGE_REGION_SIZE + 1) >> 8);
    EXPECT(nm_load_image(&bad, &nes_player_vrc7) != 0, "data larger than the VRC7 region: rejected (%s)", bad.info.problem);
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
    snprintf(path, sizeof path, "%s/nes_vrc7.wav", dir);
    write_wav(path, wav, frames);
    printf("  wrote %s (%.1f s)\n", path, frames / (double)RATE);
    snprintf(path, sizeof path, "%s/nes_vrc7_trace.txt", dir);
    FILE* ft = fopen(path, "w");
    if (ft) {
        fprintf(ft, "# frame: APU and VRC7 writes as address=value (hex), FamiStudio 4.5.3 VRC7 player (FamiTracker tempo), vrc7_test\n");
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
    snprintf(path, sizeof path, "%s/nes_vrc7.nsf", dir);
    FILE* fn = fopen(path, "wb");
    if (fn) {
        uint8_t h[128];
        memset(h, 0, sizeof h);
        memcpy(h, "NESM\x1A", 5);
        h[5] = 1;
        h[6] = 1;
        h[7] = 1;
        h[8] = (uint8_t)NES_VRC7_PLAYER_BASE, h[9] = (uint8_t)(NES_VRC7_PLAYER_BASE >> 8);
        h[10] = (uint8_t)NES_VRC7_PLAYER_INIT, h[11] = (uint8_t)(NES_VRC7_PLAYER_INIT >> 8);
        h[12] = (uint8_t)NES_VRC7_PLAYER_PLAY, h[13] = (uint8_t)(NES_VRC7_PLAYER_PLAY >> 8);
        strcpy((char*)h + 0x0E, "Thor Tracker VRC7 test");
        strcpy((char*)h + 0x2E, "Thor Tracker");
        strcpy((char*)h + 0x4E, "FamiStudio 4.5.3 engine");
        h[0x6E] = 0xFF, h[0x6F] = 0x40;
        h[0x78] = 0x20, h[0x79] = 0x4E;
        h[0x7B] = 0x02; /* expansion: VRC7 */
        fwrite(h, 1, sizeof h, fn);
        static uint8_t body[NES_VRC7_SONG_BASE - 0x8000 + NES_VRC7_RAW_SIZE];
        memset(body, 0, sizeof body);
        memcpy(body, nes_vrc7_player_code, NES_VRC7_PLAYER_SIZE);
        memcpy(body + (NES_VRC7_SONG_BASE - 0x8000), nes_vrc7_raw, NES_VRC7_RAW_SIZE);
        fwrite(body, 1, sizeof body, fn);
        fclose(fn);
    }
}

/* Plays up to (order, row) from the start and through that row's first frame. */
static void at_row(NesMachine* m, int order, int row) {
    int mode;
    nm_start(m, &g_thor, order, row, 12000, &mode);
    nm_tick_silent(m);
}

static void test_playback(NesMachine* m, const char* dir) {
    const int pass_rows = FIRST_PASS_ROWS + 2 * LOOP_ROWS;
    const int frames = song_frames(pass_rows);
    printf("FamiStudio's VRC7 export of vrc7_test.txt: %d rows (first pass + 2 loops), %d frames\n", pass_rows, frames);
    Trace raw, thor;
    trace_alloc(&raw, frames);
    trace_alloc(&thor, frames);
    play_trace(m, &g_raw, &raw);
    int mode, faults = 0, nans = 0, vrc7_writes = 0, apu_writes = 0, bad_rows = 0, k = 0, row_start = 0, custom_mismatch = 0;
    int custom_frames = 0;
    uint32_t worst_call = 0;
    double sum2 = 0, peak = 0, t0 = now_us();
    const NesVrc7* v = nm_vrc7(m);
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
            else ++vrc7_writes;
        }
        /* The chip's one custom patch holds the registers of the entry the machine says it came from. */
        const int ci = nm_vrc7_custom_instrument(m);
        if (ci >= 0) {
            ++custom_frames;
            if (memcmp(v->reg, exp_entry(&g_thor, ci) + 8, 8)) ++custom_mismatch;
        }
        if (nm_last_call_cycles(m) > worst_call) worst_call = nm_last_call_cycles(m);
        for (int i = 0; i < 2 * n; ++i) {
            if (g_out[i] != g_out[i]) ++nans;
            double a = fabs(g_out[i]);
            if (a > peak) peak = a;
            sum2 += g_out[i] * g_out[i];
        }
    }
    const double per_tick = (now_us() - t0) / frames, rms = sqrt(sum2 / (2.0 * frames * 798.7));
    EXPECT(faults == 0 && nans == 0, "no driver faults or NaNs over %d frames", frames);
    EXPECT(rms > 0.01 && peak < 1.0, "non-silent, unclipped: rms %.4f peak %.4f", rms, peak);
    EXPECT(worst_call < 29780 / 2, "engine frame within half a frame (worst %u cycles)", worst_call);
    EXPECT(vrc7_writes > frames * 6 && apu_writes > frames * 3, "the engine writes the VRC7 (%d) and the APU (%d) every frame",
           vrc7_writes, apu_writes);
    EXPECT(custom_frames > 0 && custom_mismatch == 0, "custom patch registers = the tracked entry's in all %d frames (%d differ)",
           custom_frames, custom_mismatch);
    printf("  rms %.4f, peak %.4f, worst engine frame %u CPU cycles, %.1f us per tick (%.0fx realtime)\n", rms, peak, worst_call,
           per_tick, 16639.0 / per_tick);
    int d = first_difference(&raw, &thor, 0, frames);
    EXPECT(d < 0, "Thor layout writes the same APU and VRC7 registers as FamiStudio's export, all %d frames (first difference %d)",
           frames, d);
    EXPECT(bad_rows == 0 && k == pass_rows, "%d row starts at the frames the speeds give, loop to order %d (%d wrong)", k,
           VRC7_TEST_LOOP, bad_rows);
    /* $E000 = 0 once, in famistudio_init. */
    int resets = 0;
    for (int f = 0; f < raw.frames; ++f) {
        for (int i = 0; i < raw.n[f]; ++i) resets += raw.w[f][3 * i + 1] == 0xE0;
    }
    EXPECT(resets == 0, "no $E000 writes while playing (%d)", resets);

    /* FM 1 at order 0 row 0: FBell (patch 1) C4 from the VRC7 note table, keyed on. */
    at_row(m, 0, 0);
    const int note = nm_peek(m, NES_VRC7_FAMISTUDIO_CHN_NOTE + CH_FM1);
    const double want = 440.0 * pow(2.0, (note - 46) / 12.0);
    EXPECT(v->reg[0x30] >> 4 == 1 && (v->reg[0x20] & 0x10) && fabs(fm_hz(v, 0) / want - 1) < 0.005 &&
               nm_vrc7_instrument(m, CH_FM1) == VRC7_TEST_EXP_FBELL,
           "FM 1 FBell: patch 1, keyed on, note %d at %.2f Hz (12-TET %.2f)", note, fm_hz(v, 0), want);
    /* Order 1 row 0: FM 1 and FM 4 load FCustomA, the chip's custom patch; FM 3's FCustomB at row 4 replaces it for both
     * (the VRC7 has one custom patch). */
    at_row(m, 1, 0);
    EXPECT(nm_vrc7_custom_instrument(m) == VRC7_TEST_EXP_FCUSTOMA && !memcmp(v->reg, "\x31\x61\x14\x05\xA8\x64\x61\x27", 8) &&
               (v->reg[0x30] >> 4) == 0 && nm_vrc7_instrument(m, CH_FM4) == VRC7_TEST_EXP_FCUSTOMA,
           "order 1 row 0: FCustomA's registers in $00-$07, FM 1 and FM 4 on patch 0");
    at_row(m, 1, 4);
    EXPECT(nm_vrc7_custom_instrument(m) == VRC7_TEST_EXP_FCUSTOMB && !memcmp(v->reg, "\x02\x21\x1E\x07\xF2\xD5\x24\x16", 8) &&
               (v->reg[0x30] >> 4) == 0 && (v->reg[0x32] >> 4) == 0 && nm_vrc7_instrument(m, CH_FM1) == VRC7_TEST_EXP_FCUSTOMA,
           "order 1 row 4: FCustomB's registers; FM 1 (still FCustomA) now sounds through them");
    /* Key-off: FBell's release at order 0 row 10 ($56); FBass's Stop at row 6 (the engine's cut). */
    at_row(m, 0, 9);
    const int keyed = v->reg[0x20] & 0x10;
    at_row(m, 0, 10);
    EXPECT(keyed && !(v->reg[0x20] & 0x10), "order 0 row 10: FM 1 keyed off by its release");
    at_row(m, 0, 5);
    const int fm2_keyed = v->reg[0x21] & 0x10;
    at_row(m, 0, 6);
    EXPECT(fm2_keyed && !(v->reg[0x21] & 0x10), "order 0 row 6: FM 2 keyed off by its Stop ($21 = $%02X)", v->reg[0x21]);

    write_evidence(m, dir, &raw);
    trace_free(&raw);
    trace_free(&thor);
}

static void test_seek(NesMachine* m) {
    printf("VRC7 seek: exact rows, the FM chip runs through the fast-forward\n");
    const int frames = song_frames(FIRST_PASS_ROWS + LOOP_ROWS);
    Trace ref, t;
    trace_alloc(&ref, frames);
    trace_alloc(&t, frames);
    play_trace(m, &g_thor, &ref);
    NesMachine* straight = nm_create(RATE, &nes_player_vrc7);
    const int targets[][2] = {{0, 4}, {1, 0}, {1, 8}, {2, 8}, {2, 12}, {3, 15}};
    for (size_t i = 0; i < sizeof targets / sizeof targets[0]; ++i) {
        const int o = targets[i][0], r = targets[i][1], f0 = row_frame(o, r);
        int mode = -1, po, pr, pt;
        int res = nm_start(m, &g_thor, o, r, 12000, &mode);
        nm_position(m, &po, &pr, &pt);
        EXPECT(res == NM_OK && mode == NM_SEEK_FROM_START && po == o && pr == r && pt == 0 && nm_frames(m) == (uint32_t)f0,
               "seek %d/%d: from start, %u frames (row starts at %d)", o, r, nm_frames(m), f0);
        /* Straight audible play to the same frame: the chip in the same state (seeking does not freeze FM). */
        nm_start(straight, &g_thor, 0, 0, 0, &mode);
        for (int f = 0; f < f0; ++f) nm_tick(straight, g_out);
        EXPECT(same_chip(nm_vrc7(m), nm_vrc7(straight)), "seek %d/%d: FM envelopes, phases and LFOs as after straight play", o, r);
        nm_set_apu_logging(m, 1);
        int same = 1;
        for (int f = f0; f < f0 + 200 && f < frames; ++f) {
            nm_tick_silent(m);
            record(m, &t, f);
            same &= same_frame(&ref, &t, f);
        }
        EXPECT(same, "seek %d/%d writes the same APU and VRC7 registers as straight play for 200 frames", o, r);
    }
    nm_destroy(straight);
    int mode = -1, o, r, tk;
    EXPECT(nm_start(m, &g_thor, 2, 8, 70, &mode) == NM_OK && mode == NM_SEEK_FROM_ORDER, "small budget: seek from order 2's start");
    nm_position(m, &o, &r, &tk);
    EXPECT(o == 2 && r == 8 && tk == 0, "fallback lands on 2/8 (%d/%d/%d)", o, r, tk);
    EXPECT(nm_start(m, &g_thor, VRC7_TEST_ORDERS, 0, 12000, &mode) == NM_ERR_IMAGE, "order past the song rejected");
    static NesImage other;
    memset(&other, 0, sizeof other);
    memcpy(other.bytes, nes_ft_thor, NES_FT_THOR_SIZE);
    nm_load_image(&other, &nes_player_2a03);
    EXPECT(nm_start(m, &other, 0, 0, 0, &mode) == NM_ERR_IMAGE, "a VRC7 machine refuses an image loaded for the 2A03 build");
    trace_free(&ref);
    trace_free(&t);
}

static void test_live_apply(NesMachine* m) {
    printf("VRC7 live apply: relocation, edits and patch edits at tick boundaries\n");
    const int frames = song_frames(FIRST_PASS_ROWS + 2 * LOOP_ROWS);
    Trace ref, t;
    trace_alloc(&ref, frames);
    trace_alloc(&t, frames);
    play_trace(m, &g_thor, &ref);
    const NesVrc7* v = nm_vrc7(m);
    int mode;
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_set_apu_logging(m, 1);
    double worst_us = 0;
    int chip_kept = 1;
    for (int f = 0; f < frames; ++f) {
        uint8_t before[8];
        memcpy(before, v->reg, 8);
        double a0 = now_us();
        nm_apply_image(m, (f & 1) ? &g_moved : &g_thor);
        double us = now_us() - a0;
        if (us > worst_us) worst_us = us;
        chip_kept &= !memcmp(before, v->reg, 8);
        nm_tick_silent(m);
        record(m, &t, f);
    }
    int d = first_difference(&ref, &t, 0, frames);
    EXPECT(d < 0 && chip_kept, "moved data applied before each of %d frames: same writes as straight play, no patch rewrites (first difference %d)",
           frames, d);
    printf("  worst apply_image: %.1f us\n", worst_us);

    /* The FBell volume sustain 12 -> 8 heard on the sustaining FM 1 note ($3x volume inverted: 3 -> 7). */
    nm_start(m, &g_thor, 0, 0, 12000, &mode);
    for (int i = 0; i < 4; ++i) nm_tick_silent(m);
    const int before = v->reg[0x30] & 15;
    nm_apply_image(m, &g_edited);
    nm_tick_silent(m);
    const int after = v->reg[0x30] & 15;
    EXPECT(before == 3 && after == 7, "sustaining FBell note follows its edited volume envelope ($30 volume %d -> %d)", before, after);
    nm_start(m, &g_edited, 2, 8, 12000, &mode);
    nm_tick_silent(m);
    const int edited_note = nm_peek(m, NES_VRC7_FAMISTUDIO_CHN_NOTE + CH_FM1);
    nm_start(m, &g_thor, 2, 8, 12000, &mode);
    nm_tick_silent(m);
    const int original_note = nm_peek(m, NES_VRC7_FAMISTUDIO_CHN_NOTE + CH_FM1);
    EXPECT(edited_note != original_note, "edited FM 1 note at order 2 row 8: %d (was %d)", edited_note, original_note);

    /* Patch edits while notes hold: FBell's preset 1 -> 3 is heard on FM 1 at once; FCustomA's carrier release byte
     * rewrites the chip's custom patch at once (no instrument load in between). */
    static NesImage patch_edit;
    patch_edit = g_thor;
    exp_entry(&patch_edit, VRC7_TEST_EXP_FBELL)[7] = 0x30;
    exp_entry(&patch_edit, VRC7_TEST_EXP_FCUSTOMA)[15] = 0x2F;
    EXPECT(nm_load_image(&patch_edit, &nes_player_vrc7) == 0, "patch-edited image loads");
    nm_start(m, &g_thor, 0, 0, 12000, &mode);
    for (int i = 0; i < 3; ++i) nm_tick_silent(m);
    nm_apply_image(m, &patch_edit);
    nm_tick_silent(m);
    EXPECT(v->reg[0x30] >> 4 == 3 && nm_vrc7_instrument(m, CH_FM1) == VRC7_TEST_EXP_FBELL, "FBell edited to patch 3: FM 1's $30 = $%02X",
           v->reg[0x30]);
    nm_start(m, &g_thor, 1, 0, 12000, &mode);
    nm_tick_silent(m);
    const int custom_before = v->reg[7];
    nm_apply_image(m, &patch_edit);
    EXPECT(custom_before == 0x27 && v->reg[7] == 0x2F && v->opll->patch[1].RR == 15,
           "FCustomA's $07 edited: the chip's custom patch rewritten at the apply ($%02X -> $%02X)", custom_before, v->reg[7]);
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
    printf("VRC7 audition: held FM notes, key-off release\n");
    const NesVrc7* v = nm_vrc7(m);
    const int note = 46; /* A440 */
    EXPECT(nm_audition(m, &g_thor, CH_FM1, note, VRC7_TEST_EXP_FBELL, 0) == NM_OK, "audition FM 1 FBell");
    nm_tick(m, g_out);
    EXPECT((v->reg[0x20] & 0x10) && v->reg[0x30] >> 4 == 1 && fabs(fm_hz(v, 0) / 440.0 - 1) < 0.005,
           "first tick: keyed on, patch 1, %.2f Hz", fm_hz(v, 0));
    double e = energy(m, 120);
    EXPECT(e > 1.0 && (v->reg[0x30] & 15) == 3 && !(v->reg[0x21] & 0x10) && !(nm_apu(m)->pulse[0].ctrl & 15),
           "held 2 s at the sustain level 12, other channels silent (energy %.1f)", e);
    EXPECT(nm_release(m) == 1, "FM: a release phase");
    int ticks = 0, res;
    while ((res = nm_tick(m, g_out)) > 0 && ticks < 600) ++ticks;
    EXPECT(res == 0 && ticks > 4 && !(v->reg[0x20] & 0x10) && nes_vrc7_release_over(v, 0),
           "key-off, envelope release tail and the FM decay: ended after %d ticks", ticks);
    /* FFlute: no volume release point, but the key-off decay still plays. */
    EXPECT(nm_audition(m, &g_thor, CH_FM2, note, VRC7_TEST_EXP_FFLUTE, -3) == NM_OK, "audition FM 2 FFlute, fine -3");
    energy(m, 30);
    EXPECT(nm_release(m) == 1, "FFlute (no release point) still releases on FM");
    ticks = 0;
    while ((res = nm_tick(m, g_out)) > 0 && ticks < 600) ++ticks;
    EXPECT(res == 0 && ticks >= 1 && nes_vrc7_channel_out(v, 1) == 0, "FFlute decayed to silence after %d ticks", ticks);
    /* A custom patch holding its level after key-off (carrier EG 1, RR 0): ends at once. */
    static NesImage frozen;
    frozen = g_thor;
    exp_entry(&frozen, VRC7_TEST_EXP_FCUSTOMA)[15] = 0x20;
    nm_load_image(&frozen, &nes_player_vrc7);
    EXPECT(nm_audition(m, &frozen, CH_FM3, note, VRC7_TEST_EXP_FCUSTOMA, 0) == NM_OK && energy(m, 20) > 0.1 && nm_release(m) == 1,
           "FCustomA with RR 0 held, released");
    ticks = 0;
    while ((res = nm_tick(m, g_out)) > 0 && ticks < 600) ++ticks;
    EXPECT(res == 0 && ticks <= 2, "a release that can never decay ends with the key-off (%d ticks)", ticks);
    EXPECT(nm_audition(m, &g_thor, NES_VRC7_CHANNELS, note, 0, 0) == NM_ERR_IMAGE, "channel 11 does not exist");
    EXPECT(nm_audition(m, &g_thor, CH_FM1, note, VRC7_TEST_INSTRUMENTS, 0) == NM_ERR_IMAGE, "instrument past the table rejected");
    EXPECT(nm_audition(m, &g_raw, CH_FM1, note, 0, 0) == NM_ERR_IMAGE, "raw layout cannot audition");
    EXPECT(nm_audition(m, &g_thor, 0, note, VRC7_TEST_INSTRUMENT_LEAD, 0) == NM_OK && energy(m, 10) > 0.01 && nm_release(m) == 1,
           "2A03 pulse 1 Lead on the VRC7 build: audition and release point as on the 2A03 build");
}

static void test_mute(NesMachine* m) {
    printf("VRC7 mute: the six FM channels in the mix\n");
    int mode;
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    double e_all = 0, e_muted = 0, e_back = 0;
    for (int f = 0; f < 600; ++f) {
        if (f == 200 || f == 400) {
            for (int c = 0; c < NES_VRC7_CHANNELS; ++c) nm_mute_channel(m, c, f == 200);
        }
        int n = nm_tick(m, g_out);
        double e = 0;
        for (int i = 0; i < 2 * n; ++i) e += g_out[i] * g_out[i];
        if (f < 200) e_all += e;
        else if (f >= 210 && f < 400) e_muted += e;
        else if (f >= 400) e_back += e;
    }
    EXPECT(e_muted < e_all * 1e-6 && e_back > e_all * 0.2, "mute silences all 11, unmute restores (energy %.1f, %.2g, %.1f)", e_all,
           e_muted, e_back);
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    for (int c = CH_FM1; c < NES_VRC7_CHANNELS; ++c) nm_mute_channel(m, c, 1);
    EXPECT(nm_vrc7(m)->mute == 0x3F && nm_apu(m)->mute == 0, "channels 5-10 mute the VRC7 unit's channels");
    const double e = energy(m, 30);
    EXPECT(e > 0.01 && nes_vrc7_mix_level(nm_vrc7(m)) == 0.0f && nm_vrc7(m)->opll->slot[1].eg_out < 100,
           "2A03 still heard; FM 1's note still running in the chip, silent in the mix");
    EXPECT(nm_mute_channel(m, NES_VRC7_CHANNELS, 1) == NM_ERR_IMAGE, "channel 11 does not exist");
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    EXPECT(nm_vrc7(m)->mute == 0, "start clears mutes (power-on)");
}

static void test_chip_engine_ops(void) {
    printf("chip engine interface: \"nes-famistudio-vrc7\"\n");
    const ChipEngineOps* ops = chip_engine_ops("nes-famistudio-vrc7");
    const ChipEngineOps* plain = chip_engine_ops("nes-famistudio");
    EXPECT(ops != NULL && plain != NULL && ops != plain && ops != chip_engine_ops("nes-famistudio-vrc6"),
           "registered beside the 2A03 and VRC6 engines");
    if (!ops) return;
    EXPECT(ops->image_size == NES_VRC7_IMAGE_SIZE && ops->image_storage >= ops->image_size, "image size %zu", ops->image_size);
    ChipImage* img = (ChipImage*)malloc(ops->image_storage);
    static uint8_t bytes[NES_IMAGE_SIZE];
    memset(bytes, 0, sizeof bytes);
    memcpy(bytes, nes_vrc7_thor, NES_VRC7_THOR_SIZE);
    EXPECT(ops->load_image(img, bytes, ops->image_size) == CHIP_OK, "VRC7 image loads");
    EXPECT(ops->load_image(img, bytes, plain->image_size) == CHIP_ERR_IMAGE, "the 2A03 image size rejected");
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
    /* The hosts pass 1-based slots; on an FM channel the slot addresses the expansion table. */
    EXPECT(ops->audition(au, img, CH_FM1, 46, VRC7_TEST_EXP_FBELL + 1, 0) == CHIP_OK, "FM audition through the ops");
    EXPECT(ops->mute(cm, CH_FM1, 1) == CHIP_OK && ops->mute(cm, NES_VRC7_CHANNELS, 1) == CHIP_ERR_IMAGE, "mute the song's FM 1; channel 11 rejected");
    EXPECT(ops->tick(cm, g_out) > 0 && ops->tick(au, g_out) > 0, "both machines tick");
    EXPECT(ops->release(au) == 1, "FM release phase through the ops");
    ops->destroy(cm);
    ops->destroy(au);
    free(img);
}

/* The WAV export of a short-looping test song: once through ends where straight ticking enters the loop row again,
 * sample for sample; one more pass adds exactly the loop's ticks. */
static void test_wav_short_loop(const char* dir, const char* system, const uint8_t* fixture, size_t fixture_size, int first_ticks,
                                int loop_ticks) {
    printf("WAV export of the %s test song (loop %d ticks)\n", system, loop_ticks);
    const ChipEngineOps* ops = chip_engine_ops(system);
    if (!ops) return;
    static uint8_t bytes[NES_IMAGE_SIZE];
    memset(bytes, 0, sizeof bytes);
    memcpy(bytes, fixture, fixture_size);
    char spool[512];
    snprintf(spool, sizeof spool, "%s/nes_wav_loop.part", dir);
    /* Straight ticking for the first pass and one loop. */
    static int16_t ref[RATE * 2 * 60];
    ChipImage* img = (ChipImage*)malloc(ops->image_storage);
    ChipMachine* cm = ops->create(RATE);
    int mode, n_ref = 0, n_first = 0;
    ops->load_image(img, bytes, ops->image_size);
    ops->start(cm, img, 0, 0, 0, &mode);
    for (int t = 0; t < first_ticks + loop_ticks; ++t) {
        const int n = ops->tick(cm, g_out);
        for (int i = 0; i < 2 * n && n_ref * 2 + i < (int)(sizeof ref / sizeof ref[0]); ++i) ref[n_ref * 2 + i] = chip_wav_pcm16(g_out[i]);
        n_ref += n;
        if (t == first_ticks - 1) n_first = n_ref;
    }
    ops->destroy(cm);
    free(img);
    for (int loops = 0; loops < 2; ++loops) {
        const char* problem = NULL;
        int capped = -1;
        const int r = chip_render_wav(system, bytes, ops->image_size, loops, 0, 600, spool, &capped, &problem);
        FILE* f = r == 0 ? fopen(spool, "rb") : NULL;
        static int16_t file[RATE * 2 * 60 + 22];
        size_t got = f ? fread(file, 2, sizeof file / 2, f) : 0;
        if (f) fclose(f);
        remove(spool);
        const int frames = got >= 22 ? (int)(got - 22) / 2 : -1, want = loops ? n_ref : n_first;
        EXPECT(r == 0 && capped == 0 && frames == want && !memcmp(file + 22, ref, (size_t)want * 4),
               "loops=%d: %d frames, identical to straight ticking (%d frames)", loops, frames, want);
    }
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    test_vrc7_unit();
    test_images();
    NesMachine* m = nm_create(RATE, &nes_player_vrc7);
    if (!m) {
        printf("nm_create failed\n");
        return 2;
    }
    EXPECT(nm_player(m) == &nes_player_vrc7 && nes_player_vrc7.channels == 11 && nes_player_vrc7.song_base == NES_VRC7_SONG_BASE,
           "machine for the VRC7 build: 11 channels, song base $%04X", NES_VRC7_SONG_BASE);
    test_playback(m, dir);
    test_seek(m);
    test_live_apply(m);
    test_audition(m);
    test_mute(m);
    nm_destroy(m);
    test_chip_engine_ops();
    test_wav_short_loop(dir, "nes-famistudio-vrc7", nes_vrc7_thor, NES_VRC7_THOR_SIZE, song_frames(FIRST_PASS_ROWS),
                        song_frames(FIRST_PASS_ROWS + LOOP_ROWS) - song_frames(FIRST_PASS_ROWS));
    const int vrc6_first = frames_of(&nes_vrc6_speed[0][0], VRC6_TEST_ROWS, VRC6_TEST_ORDERS, VRC6_TEST_LOOP, VRC6_TEST_ORDERS * VRC6_TEST_ROWS);
    const int vrc6_both = frames_of(&nes_vrc6_speed[0][0], VRC6_TEST_ROWS, VRC6_TEST_ORDERS, VRC6_TEST_LOOP,
                                    (2 * VRC6_TEST_ORDERS - VRC6_TEST_LOOP) * VRC6_TEST_ROWS);
    test_wav_short_loop(dir, "nes-famistudio-vrc6", nes_vrc6_thor, NES_VRC6_THOR_SIZE, vrc6_first, vrc6_both - vrc6_first);
    printf("nes_vrc7_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
