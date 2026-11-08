/* Host regression test for the OPL3 system: Thor Tracker's own player (opl3/opl3_player.c) on Nuked-OPL3,
 * playing the hand-built test song tools/audio/opl3/opl3_song.py (opl3_test_song.h, from
 * tools/audio/prepare_opl3.py).
 *
 * Checks: the pitch table (A-4 = 440 Hz, every note within 3 cents of 12-TET); image validation (single-byte
 * corruptions each rejected with its reason); register writes identical to the independent Python reference
 * player (tools/audio/opl3/opl3_ref.py) in every tick, with 2-op and 4-op instruments of all algorithms,
 * chords exhausting and stealing the voice pool, 4-op pairs taking their secondary back, drum sweeps and every
 * effect; positions; deterministic, non-silent output; seeks landing on exact rows with the reference's writes
 * afterwards, and the order fallback; live re-layout before every tick, a note edit, tempo edits ahead of and
 * behind the playhead and a song cut under the playhead; audition (440 Hz measured, fine pitch, 4-op, sweep,
 * release until silent, argument limits); mute; multispeed; VGM bytes identical to the reference's; the
 * "opl3-fm" ops table as the hosts drive it; costs.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir]
 * Writes <dir>/opl3_song.wav (48 kHz stereo, 30 s), opl3_tone.wav (the A-4 audition, 2 s + release),
 * opl3_trace.txt (per-tick player writes), opl3_song.vgm (VGM 1.71, YMF262) and opl3_song.bin (the image), the
 * inputs of tools/audio/opl3_reference_check.py. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "chip_engine.h"
#include "generated/opl3_tables.h"
#include "opl3_export.h"
#include "opl3_machine.h"
#include "opl3_test_song.h"
#include "opl3_vgm.h"

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

#define RATE 48000
#define SECONDS 30
#define A4 57
#define SINE_SLOT 9
#define SEEK_BUDGET 12000

static float g_out[O3M_MAX_TICK_FRAMES * 2];
static Opl3Image g_song, g_moved, g_edited, g_tempo, g_short, g_x2, g_bad;

typedef struct Ref {
    const int* offsets;
    const unsigned short* regs;
    const unsigned char* vals;
    const short* pos;
    const unsigned char* offs;
} Ref;

static const Ref REF_SONG = {opl3_ref_song_offsets, opl3_ref_song_regs, opl3_ref_song_vals, opl3_ref_song_pos,
                             opl3_ref_song_offs};
static const Ref REF_EDITED = {opl3_ref_edited_offsets, opl3_ref_edited_regs, opl3_ref_edited_vals, opl3_ref_edited_pos,
                               opl3_ref_edited_offs};
static const Ref REF_TEMPO = {opl3_ref_tempo_offsets, opl3_ref_tempo_regs, opl3_ref_tempo_vals, opl3_ref_tempo_pos,
                              opl3_ref_tempo_offs};

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

/* Frequency from rising zero crossings of the left channel of interleaved stereo (mean removed). */
static double zero_cross_hz(const float* x, int n) {
    double mean = 0;
    for (int i = 0; i < n; ++i) mean += x[2 * i];
    mean /= n;
    int count = 0;
    double first_t = -1, last_t = 0;
    for (int i = 1; i < n; ++i) {
        double a = x[2 * (i - 1)] - mean, b = x[2 * i] - mean;
        if (a < 0 && b >= 0) {
            double t = (i - 1) + a / (a - b);
            if (first_t < 0) first_t = t;
            else {
                last_t = t;
                ++count;
            }
        }
    }
    return count ? count * (double)RATE / (last_t - first_t) : 0;
}

static double rms(const float* x, int frames) {
    double s = 0;
    for (int i = 0; i < frames * 2; ++i) s += (double)x[i] * x[i];
    return frames ? sqrt(s / (frames * 2.0)) : 0;
}

static double peak(const float* x, int frames) {
    double p = 0;
    for (int i = 0; i < frames * 2; ++i)
        if (fabs(x[i]) > p) p = fabs(x[i]);
    return p;
}

static int render(Opl3Machine* m, int ticks, float** buf) {
    float* out = (float*)malloc((size_t)ticks * O3M_MAX_TICK_FRAMES * 2 * sizeof(float));
    int frames = 0;
    for (int t = 0; t < ticks; ++t) {
        int n = o3m_tick(m, out + 2 * frames);
        if (n <= 0) break;
        frames += n;
    }
    *buf = out;
    return frames;
}

static int load(Opl3Image* image, const uint8_t* bytes, size_t used) {
    memset(image->bytes, 0, sizeof image->bytes);
    memcpy(image->bytes, bytes, used);
    return o3_load_image(image);
}

static int ref_count(const Ref* r, int k) { return r->offsets[k + 1] - r->offsets[k]; }

/* Whether the player's last batch equals reference batch k (0 = reset, k = tick k - 1). */
static int same(const O3Player* p, const Ref* r, int k) {
    if (p->writes != ref_count(r, k) || p->offs != r->offs[k]) return 0;
    for (int i = 0; i < p->writes; ++i) {
        const int j = r->offsets[k] + i;
        if (p->write[i].reg != r->regs[j] || p->write[i].val != r->vals[j]) return 0;
    }
    return 1;
}

/* Reference tick index (batch k) whose position is (order, row, 0), first occurrence, or -1. */
static int ref_row_start(const Ref* r, int order, int row) {
    for (int k = 1; k <= OPL3_REF_TICKS; ++k)
        if (r->pos[3 * k] == order && r->pos[3 * k + 1] == row && r->pos[3 * k + 2] == 0) return k;
    return -1;
}

static int pos_is(const Opl3Machine* m, const Ref* r, int k) {
    int o, w, t;
    o3m_position(m, &o, &w, &t);
    return o == r->pos[3 * k] && w == r->pos[3 * k + 1] && t == r->pos[3 * k + 2];
}

static double voice_hz(const O3Player* p, int v) {
    const int bank = v >= 9 ? 0x100 : 0, c = v % 9;
    const int b0 = p->shadow[bank + 0xB0 + c], fnum = p->shadow[bank + 0xA0 + c] | (b0 & 3) << 8, block = b0 >> 2 & 7;
    return fnum * ((double)O3M_CLOCK / O3M_NATIVE_DIVIDER) / (double)(1 << (20 - block));
}

/* ---- tests ---------------------------------------------------------------------------------- */

static void test_tables(void) {
    printf("pitch table\n");
    const int a4 = A4 * 64, frac = a4 % 768;
    EXPECT(frac >= O3_FNUM_SPLIT && O3_FNUM[frac] == 580, "A-4 = F-number 580 at block 4 (got %d)", O3_FNUM[frac]);
    const double hz = 580 * ((double)O3M_CLOCK / O3M_NATIVE_DIVIDER) / 65536.0;
    EXPECT(fabs(hz - 440.0) < 0.05, "A-4 register frequency %.3f Hz", hz);
    double worst = 0;
    for (int n = 0; n < O3_NOTES; ++n) {
        const int u = n * 64, oct = u / 768, f = u % 768;
        int fnum = O3_FNUM[f], block = oct - (f < O3_FNUM_SPLIT ? 1 : 0);
        if (block < 0) block = 0, fnum >>= 1;
        const double got = fnum * ((double)O3M_CLOCK / O3M_NATIVE_DIVIDER) / (double)(1 << (20 - block));
        const double want = 440.0 * pow(2.0, (n - A4) / 12.0);
        const double cents = fabs(1200.0 * log2(got / want));
        if (cents > worst) worst = cents;
        EXPECT(fnum < 1024 && block <= 7, "note %d registers", n);
    }
    EXPECT(worst < 3.0, "every note C-0..B-7 within 3 cents of 12-TET (worst %.2f)", worst);
    printf("  A-4 = block 4 F-number 580 = %.3f Hz; notes within %.2f cents of 12-TET\n", hz, worst);
}

static void corrupt(int offset, int value, const char* want) {
    memcpy(&g_bad, &g_song, sizeof g_bad);
    g_bad.bytes[offset] = (uint8_t)value;
    const int rc = o3_load_image(&g_bad);
    EXPECT(rc != 0 && g_bad.info.problem && strstr(g_bad.info.problem, want), "byte %d = %d rejected for \"%s\" (got %s)",
           offset, value, want, rc ? g_bad.info.problem : "accepted");
    if (rc) printf("  rejected @%d: %s\n", g_bad.info.problem_offset, g_bad.info.problem);
}

static void test_images(void) {
    printf("images\n");
    EXPECT(g_song.info.loaded == O3_LOADED && g_song.info.orders == 4 && g_song.info.loop == 1, "song info");
    EXPECT(g_song.info.rows[0] == 16 && g_song.info.rows[1] == 32 && g_song.info.rows[2] == 16 && g_song.info.rows[3] == 24,
           "rows per order");
    EXPECT(g_song.info.start_speed[0] == 6 && g_song.info.start_speed[2] == 6 && g_song.info.start_speed[3] == 5,
           "start speeds 6/6/6/5 (got %d %d %d %d)", g_song.info.start_speed[0], g_song.info.start_speed[1],
           g_song.info.start_speed[2], g_song.info.start_speed[3]);
    corrupt(0, 'X', "magic");
    corrupt(O3_H_LAYOUT, 2, "layout");
    corrupt(O3_H_MULTISPEED, 0, "multispeed");
    corrupt(O3_H_MULTISPEED, 9, "multispeed");
    corrupt(O3_H_SPEED, 0, "speed");
    corrupt(O3_H_DEPTH, 0x01, "depth");
    corrupt(O3_H_ORDERS, 0, "order count");
    corrupt(O3_H_LOOP, 4, "loop");
    corrupt(O3_H_INSTRUMENTS, 65, "instrument count");
    corrupt(O3_H_INSTRUMENTS, 11, "past the instrument count");
    corrupt(O3_H_SHAPES, 65, "shape count");
    corrupt(O3_H_SHAPES, 3, "past the shape count");
    corrupt(O3_H_DATA_SIZE + 1, 0xFF, "data size");
    corrupt(20, 1, "reserved");
    corrupt(O3_OFF_ROWS + 9, 4, "past the order count");
    corrupt(O3_OFF_ORDERS + 1, 0xF0, "pattern offset");
    corrupt(O3_OFF_INSTRUMENTS + O3_I_FLAGS, 0x83, "flags");
    corrupt(O3_OFF_INSTRUMENTS + 32 + O3_I_ALG, 2, "algorithm");
    corrupt(O3_OFF_INSTRUMENTS + 32 + O3_I_FEEDBACK, 8, "feedback");
    corrupt(O3_OFF_INSTRUMENTS + 32 + O3_I_OPS + 4, 8, "waveform");
    corrupt(O3_OFF_INSTRUMENTS + 32 + O3_I_OPS + 10, 1, "operator 3/4");
    corrupt(O3_OFF_INSTRUMENTS + 32 * 5 + O3_I_SWEEP_START, 97, "sweep");
    corrupt(O3_OFF_INSTRUMENTS + 32 * 5 + O3_I_FLAGS, 0x81, "four-operator instrument on channel 7-9");
    corrupt(O3_OFF_INSTRUMENTS + 32 * 11 + O3_I_FLAGS, 0, "flags");
    corrupt(O3_OFF_INSTRUMENTS + 32 + 29, 1, "reserved");
    corrupt(O3_OFF_SHAPES, 49, "interval");
    corrupt(O3_OFF_SHAPES + 1, 0, "after a 0");
    corrupt(O3_OFF_SHAPES + 12, 0, "empty chord shape");
    corrupt(OPL3_OFFSET_MASK, 0x20, "mask");
    corrupt(OPL3_OFFSET_MASK, 0x17, "mask");
    corrupt(OPL3_OFFSET_NOTE, 96, "note");
    corrupt(OPL3_OFFSET_INST, 13, "instrument");
    corrupt(OPL3_OFFSET_EFFECT, 36, "effect code");
    corrupt(OPL3_OFFSET_EFFECT, 5, "unknown effect");
    corrupt(OPL3_OFFSET_PARAM_B, 4, "Bxx");
    corrupt(OPL3_OFFSET_PARAM_C, 0x40, "Cxx");
    corrupt(OPL3_OFFSET_PARAM_E, 8, "Exx");
    corrupt(OPL3_OFFSET_PARAM_F, 0, "F00");
    corrupt(OPL3_OFFSET_PARAM_I, 0x40, "Ixx");
    corrupt(OPL3_OFFSET_PARAM_K, 5, "Kxx");
    corrupt(OPL3_OFFSET_PARAM_M, 0x53, "Mxy");
    corrupt(OPL3_OFFSET_PARAM_W, 0x28, "Wxy");
    Opl3Image* fresh = &g_bad;
    memcpy(fresh, &g_song, sizeof *fresh);
    EXPECT(o3_load_image(fresh) == 0 && !memcmp(&fresh->info, &g_song.info, sizeof fresh->info), "loading is a pure function");
}

static void test_reference(const char* dir) {
    printf("reference player\n");
    Opl3Machine* m = o3m_create(RATE);
    int mode = -1;
    EXPECT(o3m_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode) == O3M_OK && mode == O3M_SEEK_FROM_START, "start");
    const O3Player* p = o3m_player(m);
    EXPECT(same(p, &REF_SONG, 0), "init writes = reference (%d writes)", p->writes);
    char path[512];
    snprintf(path, sizeof path, "%s/opl3_trace.txt", dir);
    FILE* trace = fopen(path, "w");
    int mismatches = 0, first = -1, position_errors = 0, writes = 0;
    int max_pool_keyed = 0, saw_4op = 0, saw_2op_after_4op = 0, max_writes = 0;
    static const int pool[9] = {15, 16, 17, 3, 4, 5, 12, 13, 14};
    double kick[6] = {0};
    for (int k = 1; k <= OPL3_REF_TICKS; ++k) {
        if (!pos_is(m, &REF_SONG, k)) ++position_errors;
        int o, r, t;
        o3m_position(m, &o, &r, &t);
        o3m_tick(m, g_out);
        if (!same(p, &REF_SONG, k)) {
            if (first < 0) first = k;
            ++mismatches;
        }
        writes += p->writes;
        if (p->writes > max_writes) max_writes = p->writes;
        if (trace) {
            fprintf(trace, "%d %d %d:", o, r, t);
            for (int i = 0; i < p->writes; ++i) {
                fprintf(trace, " %03X=%02X", p->write[i].reg, p->write[i].val);
                if (i + 1 == p->offs) fputs(" |", trace);
            }
            fputc('\n', trace);
        }
        int keyed = 0;
        for (int i = 0; i < 9; ++i) {
            const int v = pool[i];
            if (p->shadow[(v >= 9 ? 0x100 : 0) + 0xB0 + v % 9] & 0x20) {
                /* a pool voice sounds a chord unless its pair is in 4-op mode */
                if (i < 3 || !(p->shadow[0x104] >> (i - 3) & 1)) ++keyed;
            }
        }
        if (keyed > max_pool_keyed) max_pool_keyed = keyed;
        if (p->shadow[0x104]) saw_4op = 1;
        else if (saw_4op) saw_2op_after_4op = 1;
        if (k <= 6) kick[k - 1] = voice_hz(p, 6);
    }
    if (trace) fclose(trace);
    EXPECT(mismatches == 0, "writes = reference player in all %d ticks (%d differ, first at tick %d)", OPL3_REF_TICKS,
           mismatches, first - 1);
    if (first > 0) {
        printf("  tick %d: machine", first - 1);
        for (int i = 0; i < p->writes && i < 12; ++i) printf(" %03X=%02X", p->write[i].reg, p->write[i].val);
        printf("\n");
    }
    EXPECT(position_errors == 0, "positions = reference in every tick (%d differ)", position_errors);
    EXPECT(max_pool_keyed == 9, "the chord pool is exhausted (max %d of 9 pool voices keyed)", max_pool_keyed);
    EXPECT(saw_4op && saw_2op_after_4op, "4-op pairs switch on and back off");
    EXPECT(kick[0] > kick[1] && kick[1] > kick[2] && kick[2] > kick[3], "kick sweeps down (%.1f %.1f %.1f %.1f Hz)", kick[0],
           kick[1], kick[2], kick[3]);
    printf("  %d ticks, %d writes (max %d per tick), pool max %d voices; kick %.0f -> %.0f Hz over 5 ticks\n",
           OPL3_REF_TICKS, writes, max_writes, max_pool_keyed, kick[0], kick[5]);
    o3m_destroy(m);
}

static void test_audio(const char* dir) {
    printf("audio\n");
    const int ticks = SECONDS * 60;
    uint64_t hash[2] = {0, 0};
    for (int pass = 0; pass < 2; ++pass) {
        Opl3Machine* m = o3m_create(RATE);
        int mode;
        o3m_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
        float* buf;
        const double t0 = now_us();
        const int frames = render(m, ticks, &buf);
        const double us = (now_us() - t0) / ticks;
        hash[pass] = fnv(0xCBF29CE484222325ull, buf, (size_t)frames * 2 * sizeof(float));
        if (pass == 0) {
            const double r = rms(buf, frames), pk = peak(buf, frames);
            EXPECT(frames == SECONDS * RATE, "30 s = %d frames (got %d)", SECONDS * RATE, frames);
            EXPECT(r > 0.02, "non-silent (rms %.4f)", r);
            EXPECT(pk < 0.999, "no clipping (peak %.4f)", pk);
            int quiet = 0;
            for (int s = 0; s < SECONDS; ++s)
                if (rms(buf + 2 * s * RATE, RATE) < 0.005) ++quiet;
            EXPECT(quiet == 0, "every second non-silent (%d quiet)", quiet);
            char path[512];
            snprintf(path, sizeof path, "%s/opl3_song.wav", dir);
            write_wav(path, buf, frames);
            printf("  30 s: rms %.4f, peak %.4f, %.1f us per tick (%.0fx realtime)\n", r, pk, us, 16666.7 / us);
        }
        free(buf);
        o3m_destroy(m);
    }
    EXPECT(hash[0] == hash[1], "bit-identical on a second start");
}

/* Every pass of the loop plays like the first: the test song enters the loop order (1) first from order 0 at
 * speed 6 and later by B01 from order 3 at speed 5, without an Fxx there; the player re-derives the speed. (The
 * registers are not pass-identical: which pool voice takes a chord note follows the allocation history.) */
static void test_loop(void) {
    printf("loop passes\n");
    enum { MAX_PASS = 1024 };
    static int pos[4][MAX_PASS];
    int len[4] = {0};
    Opl3Machine* m = o3m_create(RATE);
    int mode, o, r, t, pass = -1;
    o3m_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 4 * MAX_PASS && pass < 4; ++k) {
        o3m_position(m, &o, &r, &t);
        if (o == g_song.info.loop && r == 0 && t == 0) ++pass;
        o3m_tick(m, g_out);
        if (pass < 0 || pass >= 4 || len[pass] >= MAX_PASS) continue;
        pos[pass][len[pass]++] = o << 16 | r << 8 | t;
    }
    EXPECT(pass == 4 && len[0] == len[1] && len[1] == len[2] && len[0] < MAX_PASS, "passes of %d, %d, %d ticks", len[0], len[1], len[2]);
    EXPECT(!memcmp(pos[0], pos[1], sizeof(int) * (size_t)len[0]) && !memcmp(pos[1], pos[2], sizeof(int) * (size_t)len[0]),
           "every pass steps through the same (order, row, tick) positions");
    EXPECT((uint32_t)len[0] * 735 == OPL3_REF_VGM_LOOP_SAMPLES, "= the VGM loop (%u samples)", OPL3_REF_VGM_LOOP_SAMPLES);
    printf("  %d ticks per pass; speed at the loop point %d (order 3 ends at 5)\n", len[0], g_song.info.start_speed[1]);
    o3m_destroy(m);
}

static double cpu_us(void) {
    struct timespec t;
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &t);
    return t.tv_sec * 1e6 + t.tv_nsec / 1e3;
}

/* Tick cost must be flat (device benches once showed rare ticks at 10x the mean): after 1 s of warm-up, 30 s of the
 * test song with no tick above 3x the mean. Thread CPU time, so preemption by other processes does not count. */
static void test_outliers(void) {
    printf("tick cost outliers\n");
    const int ticks = SECONDS * 60;
    double* cpu = (double*)malloc(sizeof(double) * (size_t)ticks);
    double* wall = (double*)malloc(sizeof(double) * (size_t)ticks);
    Opl3Machine* m = o3m_create(RATE);
    int mode;
    o3m_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 60; ++k) o3m_tick(m, g_out);
    double sum = 0, worst = 0, worst_wall = 0;
    int worst_at = 0;
    for (int k = 0; k < ticks; ++k) {
        const double w0 = now_us(), c0 = cpu_us();
        o3m_tick(m, g_out);
        cpu[k] = cpu_us() - c0;
        wall[k] = now_us() - w0;
        sum += cpu[k];
        if (cpu[k] > worst) worst = cpu[k], worst_at = k;
        if (wall[k] > worst_wall) worst_wall = wall[k];
    }
    const double mean = sum / ticks;
    EXPECT(worst < 3 * mean, "no tick above 3x the mean: worst %.1f us (tick %d) vs mean %.1f us", worst, worst_at, mean);
    printf("  thread CPU per tick: mean %.1f us, worst %.1f us (%.2fx, tick %d); wall worst %.1f us\n", mean, worst,
           worst / mean, worst_at, worst_wall);
    o3m_destroy(m);
    free(cpu);
    free(wall);
}

static void test_seek(void) {
    printf("seek\n");
    static const int targets[][2] = {{0, 5}, {0, 15}, {1, 0}, {1, 17}, {2, 3}, {2, 12}, {3, 2}, {3, 20}};
    Opl3Machine* m = o3m_create(RATE);
    for (size_t i = 0; i < sizeof targets / sizeof targets[0]; ++i) {
        const int o = targets[i][0], r = targets[i][1], k = ref_row_start(&REF_SONG, o, r);
        EXPECT(k > 0, "target %d/%d is in the reference", o, r);
        if (k <= 0) continue;
        int mode = -1;
        const double t0 = now_us();
        EXPECT(o3m_start(m, &g_song, o, r, SEEK_BUDGET, &mode) == O3M_OK && mode == O3M_SEEK_FROM_START, "seek %d/%d", o, r);
        const double us = now_us() - t0;
        EXPECT(pos_is(m, &REF_SONG, k), "lands on %d/%d tick 0", o, r);
        int bad = 0;
        const int n = OPL3_REF_TICKS - k + 1 < 200 ? OPL3_REF_TICKS - k + 1 : 200;
        for (int j = 0; j < n; ++j) {
            if (!pos_is(m, &REF_SONG, k + j)) ++bad;
            o3m_tick(m, g_out);
            if (!same(o3m_player(m), &REF_SONG, k + j)) ++bad;
        }
        EXPECT(bad == 0, "seek %d/%d: %d ticks of writes = straight play (%d differ)", o, r, n, bad);
        printf("  %d/%d: %d ticks fast-forwarded in %.0f us\n", o, r, k - 1, us);
    }
    /* Sound after a seek against straight play from the same tick, 1 s: inside the pre-roll window (<= 60 ticks from
     * the start) bit-identical; beyond it the chip ran the last second, so held notes and release tails are under
     * way: the 20 ms loudness envelope over the first 0.5 s stays close (rms deviation relative to the loudness). */
    static const int compare[][2] = {{0, 5}, {1, 0}, {1, 17}, {2, 12}, {3, 20}};
    for (size_t i = 0; i < sizeof compare / sizeof compare[0]; ++i) {
        const int o = compare[i][0], r = compare[i][1], k = ref_row_start(&REF_SONG, o, r);
        Opl3Machine* a = o3m_create(RATE);
        int mode;
        o3m_start(a, &g_song, 0, 0, SEEK_BUDGET, &mode);
        for (int j = 1; j < k; ++j) o3m_tick(a, g_out);
        float *x, *y;
        const int fx = render(a, 60, &x);
        o3m_start(m, &g_song, o, r, SEEK_BUDGET, &mode);
        const int fy = render(m, 60, &y);
        const int win = RATE / 50, wins = RATE / 2 / win;
        double dev = 0, level = 0;
        for (int w = 0; w < wins; ++w) {
            const double rx = rms(x + 2 * w * win, win), ry = rms(y + 2 * w * win, win);
            dev += (ry - rx) * (ry - rx);
            level += rx * rx;
        }
        const double env = sqrt(dev / level);
        const int identical = fx == fy && !memcmp(x, y, sizeof(float) * 2 * (size_t)fx);
        if (k - 1 <= O3M_PREROLL_TICKS)
            EXPECT(identical, "seek %d/%d (%d ticks, inside the pre-roll): sound = straight play", o, r, k - 1);
        else
            EXPECT(env < 0.08, "seek %d/%d (%d ticks): loudness envelope within %.3f of straight play", o, r, k - 1, env);
        const double rx = rms(x + 2 * RATE / 2, fx - RATE / 2), ry = rms(y + 2 * RATE / 2, fy - RATE / 2);
        EXPECT(ry > 0.8 * rx && ry < 1.25 * rx, "seek %d/%d: loudness 0.5-1 s after %.4f vs straight %.4f", o, r, ry, rx);
        printf("  %d/%d vs straight play: %s, envelope deviation %.3f\n", o, r, identical ? "identical" : "differs", env);
        free(x);
        free(y);
        o3m_destroy(a);
    }
    /* Order 3 rows 0-1 are only reached by the order fallback (order 2 breaks to row 2). */
    int mode = -1;
    EXPECT(o3m_start(m, &g_song, 3, 0, SEEK_BUDGET, &mode) == O3M_OK && mode == O3M_SEEK_FROM_ORDER, "3/0: order fallback");
    int o, r, t, ok = 1;
    o3m_position(m, &o, &r, &t);
    EXPECT(o == 3 && r == 0 && t == 0, "fallback lands on 3/0 (%d/%d/%d)", o, r, t);
    for (int j = 0; j < 5 * 24; ++j) {
        o3m_tick(m, g_out);
        o3m_position(m, &o, &r, &t);
        const int want = j + 1; /* order 3 starts at speed 5 */
        if (o != 3 || r != want / 5 || t != want % 5) ok = 0;
        if (want >= 5 * 24 - 1) break;
    }
    EXPECT(ok, "fallback keeps row step (speed 5)");
    EXPECT(o3m_start(m, &g_song, 1, 17, 10, &mode) == O3M_OK && mode == O3M_SEEK_FROM_ORDER, "small budget: fallback");
    o3m_position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 17 && t == 0, "budget fallback lands on 1/17");
    EXPECT(o3m_start(m, &g_song, 4, 0, SEEK_BUDGET, &mode) == O3M_ERR_IMAGE, "order past the song rejected");
    EXPECT(o3m_start(m, &g_song, 0, 16, SEEK_BUDGET, &mode) == O3M_ERR_IMAGE, "row past the order rejected");
    o3m_destroy(m);
}

/* Plays `image` from the start, applying `alt[t % 2]` before tick t (from tick `from`), and compares with `ref`. */
static int play_applying(const Opl3Image* image, const Opl3Image* alt0, const Opl3Image* alt1, int from, int every,
                         const Ref* ref, int* first) {
    Opl3Machine* m = o3m_create(RATE);
    int mode, bad = 0;
    o3m_start(m, image, 0, 0, SEEK_BUDGET, &mode);
    *first = -1;
    for (int k = 1; k <= OPL3_REF_TICKS; ++k) {
        if (k - 1 >= from && (every || k - 1 == from)) o3m_apply_image(m, (k % 2) ? alt0 : alt1);
        if (!pos_is(m, ref, k)) ++bad;
        o3m_tick(m, g_out);
        if (!same(o3m_player(m), ref, k)) {
            if (*first < 0) *first = k - 1;
            ++bad;
        }
    }
    o3m_destroy(m);
    return bad;
}

/* Ticks between the next two row starts. */
static int row_length(Opl3Machine* m) {
    int o, r, t, n = 0;
    do {
        o3m_tick(m, g_out);
        o3m_position(m, &o, &r, &t);
    } while (t != 0);
    do {
        o3m_tick(m, g_out);
        ++n;
        o3m_position(m, &o, &r, &t);
    } while (t != 0);
    return n;
}

static void test_live_edits(void) {
    printf("live edits\n");
    int first;
    int bad = play_applying(&g_song, &g_song, &g_song, 0, 1, &REF_SONG, &first);
    EXPECT(bad == 0, "same image applied before every tick: writes = reference (%d differ, first %d)", bad, first);
    bad = play_applying(&g_song, &g_moved, &g_song, 0, 1, &REF_SONG, &first);
    EXPECT(bad == 0, "re-laid-out image and the original alternately before every tick: writes = reference (%d differ, first %d)",
           bad, first);
    {
        Opl3Machine* m = o3m_create(RATE);
        int mode;
        o3m_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
        double worst = 0, total = 0;
        for (int k = 0; k < 1500; ++k) {
            const double t0 = now_us();
            o3m_apply_image(m, (k & 1) ? &g_moved : &g_song);
            const double us = now_us() - t0;
            total += us;
            if (us > worst) worst = us;
            o3m_tick(m, g_out);
        }
        printf("  apply_image: %.2f us mean, worst %.1f us\n", total / 1500, worst);
        o3m_destroy(m);
    }
    /* A note edit in order 1 row 16, applied at ticks before that row: straight play of the edited song. */
    const int k_edit = ref_row_start(&REF_SONG, 1, 16);
    const int at[] = {0, 40, k_edit - 30, k_edit - 2, k_edit - 1};
    for (size_t i = 0; i < sizeof at / sizeof at[0]; ++i) {
        bad = play_applying(&g_song, &g_edited, &g_edited, at[i], 0, &REF_EDITED, &first);
        EXPECT(bad == 0, "note edit applied before tick %d: = edited straight play (%d differ, first %d)", at[i], bad, first);
    }
    /* Tempo: F09 on order 1 row 4. Applied ahead of it: straight play of the tempo song. */
    const int k_tempo = ref_row_start(&REF_SONG, 1, 4);
    bad = play_applying(&g_song, &g_tempo, &g_tempo, k_tempo - 3, 0, &REF_TEMPO, &first);
    EXPECT(bad == 0, "tempo edit ahead of the playhead: = tempo song straight play (%d differ, first %d)", bad, first);
    {
        Opl3Machine* m = o3m_create(RATE);
        int mode, o, r, t;
        o3m_start(m, &g_song, 1, 8, SEEK_BUDGET, &mode);
        o3m_apply_image(m, &g_tempo);
        const int a = row_length(m);
        o3m_apply_image(m, &g_song);
        const int b = row_length(m);
        EXPECT(a == 9 && b == 6, "tempo edit behind the playhead: rows of %d ticks, undo %d", a, b);
        /* Cut the song to two orders while order 3 plays: clamped, then order 1 in order. */
        o3m_start(m, &g_song, 3, 5, SEEK_BUDGET, &mode);
        o3m_tick(m, g_out);
        o3m_apply_image(m, &g_short);
        o3m_position(m, &o, &r, &t);
        EXPECT(o == 1 && r == 31, "clamped to the last row of the last order (%d/%d)", o, r);
        int ok = 1, rows_seen = 0, last_row = r;
        for (int k = 0; k < 6 * 10; ++k) {
            o3m_tick(m, g_out);
            o3m_position(m, &o, &r, &t);
            if (t == 0 && r != last_row) {
                if (o != 1 || r != (last_row + 1) % 32) ok = 0;
                last_row = r;
                ++rows_seen;
            }
        }
        EXPECT(ok && rows_seen >= 8, "then the loop order plays in order (%d rows)", rows_seen);
        /* Multispeed applied live halves the tick. */
        o3m_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
        o3m_tick(m, g_out);
        const int n1 = o3m_native_samples(m);
        o3m_apply_image(m, &g_x2);
        o3m_tick(m, g_out);
        const int n2 = o3m_native_samples(m);
        EXPECT((n1 == 828 || n1 == 829) && (n2 == 414 || n2 == 415), "x2 live: %d -> %d native samples per tick", n1, n2);
        o3m_destroy(m);
    }
}

static void test_audition(const char* dir) {
    printf("audition\n");
    Opl3Machine* m = o3m_create(RATE);
    const double t0 = now_us();
    EXPECT(o3m_audition(m, &g_song, 0, A4, SINE_SLOT, 0) == O3M_OK, "A-4 on the sine instrument");
    const double setup = now_us() - t0;
    const O3Player* p = o3m_player(m);
    float* buf;
    const int frames = render(m, 120, &buf);
    const double hz = zero_cross_hz(buf + 2 * (RATE / 10), frames - RATE / 10);
    EXPECT(fabs(hz - 440.0) < 0.3, "A-4 measured %.3f Hz", hz);
    EXPECT(rms(buf + 2 * (frames - RATE / 2), RATE / 2) > 0.5 * rms(buf + 2 * (RATE / 2), RATE / 2), "held");
    int keyed = 0;
    for (int v = 0; v < 18; ++v)
        if (p->shadow[(v >= 9 ? 0x100 : 0) + 0xB0 + v % 9] & 0x20) ++keyed;
    EXPECT(keyed == 1 && (p->shadow[0xB0] & 0x20), "only voice 0 keyed (%d)", keyed);
    EXPECT(o3m_release(m) == 1, "release phase");
    float* tail = (float*)malloc(sizeof(float) * 2 * O3M_MAX_TICK_FRAMES * 700);
    int tail_frames = 0, ticks = 0, n;
    while ((n = o3m_tick(m, tail + 2 * tail_frames)) > 0 && ticks < 700) {
        tail_frames += n;
        ++ticks;
    }
    EXPECT(n == 0 && ticks > 2 && ticks < 120, "release ends when the carrier is silent (%d ticks)", ticks);
    char path[512];
    snprintf(path, sizeof path, "%s/opl3_tone.wav", dir);
    float* both = (float*)malloc(sizeof(float) * 2 * (size_t)(frames + tail_frames));
    memcpy(both, buf, sizeof(float) * 2 * (size_t)frames);
    memcpy(both + 2 * frames, tail, sizeof(float) * 2 * (size_t)tail_frames);
    write_wav(path, both, frames + tail_frames);
    printf("  A-4 %.3f Hz, release %d ticks, setup %.0f us\n", hz, ticks, setup);
    free(both);
    free(tail);
    free(buf);
    /* fine pitch: +64 units = +1 semitone */
    o3m_audition(m, &g_song, 4, A4, SINE_SLOT, 64);
    const int f2 = render(m, 60, &buf);
    const double up = zero_cross_hz(buf + 2 * (RATE / 10), f2 - RATE / 10);
    EXPECT(fabs(up - 466.164) < 0.4, "fine +64 = A#4 %.3f Hz", up);
    free(buf);
    /* 4-op and sweep */
    EXPECT(o3m_audition(m, &g_song, 3, 48, 11, 0) == O3M_OK, "4-op bell on channel 4");
    const int f3 = render(m, 30, &buf);
    EXPECT(rms(buf, f3) > 0.01 && (p->shadow[0x104] & 0x08), "4-op sounds, pair mode on (0x104 = %02X)", p->shadow[0x104]);
    free(buf);
    EXPECT(o3m_audition(m, &g_song, 6, 36, 6, 0) == O3M_OK, "kick");
    o3m_tick(m, g_out);
    const double h0 = voice_hz(p, 6);
    for (int i = 0; i < 10; ++i) o3m_tick(m, g_out);
    const double h1 = voice_hz(p, 6);
    /* C-3 + 24 semitones (C-5, 523.25 Hz) down to C-3 (130.81 Hz) at 2.5 semitones per tick */
    EXPECT(fabs(h0 - 523.25) < 1.5 && fabs(h1 - 130.81) < 0.4, "kick sweep %.2f -> %.2f Hz", h0, h1);
    EXPECT(o3m_audition(m, &g_song, 0, A4, SINE_SLOT, 0) == O3M_OK && o3m_release(m) == 0, "release before any tick: cut");
    EXPECT(o3m_audition(m, &g_song, 7, 48, 1, 0) == O3M_ERR_IMAGE, "4-op on channel 8 rejected");
    EXPECT(o3m_audition(m, &g_song, 0, 48, 13, 0) == O3M_ERR_IMAGE, "undefined slot rejected");
    EXPECT(o3m_audition(m, &g_song, 0, 96, 1, 0) == O3M_ERR_IMAGE, "note 96 rejected");
    EXPECT(o3m_audition(m, &g_song, 9, 48, 1, 0) == O3M_ERR_IMAGE, "channel 9 rejected");
    EXPECT(o3m_audition(m, &g_song, 0, 48, 1, 200) == O3M_ERR_IMAGE, "fine 200 rejected");
    o3m_destroy(m);
}

static void test_mute(void) {
    printf("mute\n");
    Opl3Machine* m = o3m_create(RATE);
    int mode, bad = 0;
    o3m_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    o3m_mute(m, 3, 1);
    o3m_mute(m, 2, 1);
    for (int k = 1; k <= OPL3_REF_TICKS; ++k) {
        o3m_tick(m, g_out);
        if (!same(o3m_player(m), &REF_SONG, k)) ++bad;
    }
    EXPECT(bad == 0, "muted channels leave the player's writes unchanged (%d differ)", bad);
    o3m_start(m, &g_song, 1, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 30; ++k) o3m_tick(m, g_out);
    for (int c = 0; c < 9; ++c) o3m_mute(m, c, 1);
    float* buf;
    int frames = render(m, 30, &buf);
    const double quiet = rms(buf + 2 * (RATE / 10), frames - RATE / 10);
    EXPECT(quiet < 0.0005, "all channels muted: rms %.6f after 100 ms", quiet);
    free(buf);
    for (int c = 0; c < 9; ++c) o3m_mute(m, c, 0);
    frames = render(m, 30, &buf);
    EXPECT(rms(buf, frames) > 0.01, "unmuted: resumes mid-note (rms %.4f)", rms(buf, frames));
    free(buf);
    EXPECT(o3m_mute(m, 9, 1) == O3M_ERR_IMAGE, "channel 9 rejected");
    o3m_destroy(m);
}

static void test_multispeed(void) {
    printf("multispeed\n");
    Opl3Machine* m = o3m_create(RATE);
    int mode, o, r, t, total = 0, frames = 0;
    o3m_start(m, &g_x2, 0, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 120; ++k) {
        frames += o3m_tick(m, g_out);
        total += o3m_native_samples(m);
    }
    o3m_position(m, &o, &r, &t);
    EXPECT(total == 49716 || total == 49715, "120 ticks at x2 = 1 s of chip samples (%d)", total);
    EXPECT(frames >= RATE - 2 && frames <= RATE + 2, "= 1 s of host frames (%d)", frames);
    EXPECT(o == 1 && r == 4 && t == 0, "x2: rows of 6 fast ticks: 20 rows in 120 ticks (%d/%d/%d)", o, r, t);
    o3m_destroy(m);
}

static void test_vgm(const char* dir) {
    printf("VGM export\n");
    size_t cap = 1 << 20;
    uint8_t* buf = (uint8_t*)malloc(cap);
    static O3Player scratch;
    O3VgmInfo info;
    const double t0 = now_us();
    const size_t size = o3_vgm_render(&g_song, 36000, &scratch, buf, cap, &info);
    const double us = now_us() - t0;
    EXPECT(size == OPL3_REF_VGM_SIZE && fnv(0xCBF29CE484222325ull, buf, size) == OPL3_REF_VGM_FNV,
           "VGM bytes = the reference player's (%zu vs %u bytes)", size, OPL3_REF_VGM_SIZE);
    EXPECT(info.ticks == OPL3_REF_VGM_TICKS && info.samples == OPL3_REF_VGM_SAMPLES &&
               info.loop_offset == OPL3_REF_VGM_LOOP_OFFSET && info.loop_samples == OPL3_REF_VGM_LOOP_SAMPLES,
           "VGM length and loop (%u ticks, loop at %u, %u samples)", info.ticks, info.loop_offset, info.loop_samples);
    EXPECT(!memcmp(buf, "Vgm ", 4) && buf[8] == 0x71 && buf[9] == 0x01 && buf[0x5C] == (14318180 & 0xFF), "header");
    EXPECT(o3_vgm_render(&g_song, 36000, &scratch, buf, 1000, &info) == 0, "too small a buffer reports 0");
    char path[512];
    snprintf(path, sizeof path, "%s/opl3_song.vgm", dir);
    FILE* f = fopen(path, "wb");
    if (f) {
        fwrite(buf, 1, size, f);
        fclose(f);
    }
    snprintf(path, sizeof path, "%s/opl3_song.bin", dir); /* the image, for tools/audio/opl3_reference_check.py */
    if ((f = fopen(path, "wb"))) {
        fwrite(g_song.bytes, 1, sizeof g_song.bytes, f);
        fclose(f);
    }
    printf("  %zu bytes, %u ticks, loop %u samples (%.2f s), rendered in %.0f us\n", size, OPL3_REF_VGM_TICKS,
           OPL3_REF_VGM_LOOP_SAMPLES, OPL3_REF_VGM_LOOP_SAMPLES / 44100.0, us);
    free(buf);
}

/* The hosts' entry point (opl3_export.h): the reference VGM plus a GD3 tag. */
static void test_export(void) {
    printf("VGM export entry point\n");
    size_t size = 0;
    const char* problem = NULL;
    const char* title = "Thor \xC3\x96pening \xF0\x9F\x8E\xB9"; /* "Thor Öpening 🎹": 2-byte and 4-byte UTF-8 */
    uint8_t* vgm = o3_export_vgm(g_song.bytes, sizeof g_song.bytes, 36000, title, "Esko", "2026/11/08", &size, &problem);
    EXPECT(vgm && !problem, "renders (%s)", problem ? problem : "ok");
    if (!vgm) return;
    const uint32_t gd3_at = 0x14 + (vgm[0x14] | vgm[0x15] << 8 | vgm[0x16] << 16 | (uint32_t)vgm[0x17] << 24);
    const uint32_t eof = 4 + (vgm[4] | vgm[5] << 8 | vgm[6] << 16 | (uint32_t)vgm[7] << 24);
    EXPECT(gd3_at == OPL3_REF_VGM_SIZE && eof == size, "GD3 after the reference data (at %u), EOF offset = size %zu", gd3_at, size);
    uint8_t* body = (uint8_t*)malloc(OPL3_REF_VGM_SIZE);
    memcpy(body, vgm, OPL3_REF_VGM_SIZE);
    const uint32_t ref_eof = OPL3_REF_VGM_SIZE - 4;
    memcpy(body + 4, &ref_eof, 4);
    memset(body + 0x14, 0, 4);
    EXPECT(fnv(0xCBF29CE484222325ull, body, OPL3_REF_VGM_SIZE) == OPL3_REF_VGM_FNV,
           "the data = the reference player's VGM (header 0x04/0x14 aside)");
    free(body);
    const uint8_t* g = vgm + gd3_at;
    const uint32_t len = g[8] | g[9] << 8 | g[10] << 16 | (uint32_t)g[11] << 24;
    EXPECT(!memcmp(g, "Gd3 ", 4) && g[4] == 0 && g[5] == 1 && gd3_at + 12 + len == size, "GD3 1.00 header, length %u", len);
    /* the 11 strings, decoded back to UTF-8 (BMP + surrogate pairs) */
    char fields[11][64];
    const uint8_t* s = g + 12;
    for (int f = 0; f < 11; ++f) {
        int n = 0;
        for (;;) {
            uint32_t c = s[0] | s[1] << 8;
            s += 2;
            if (!c) break;
            if (c >= 0xD800 && c < 0xDC00) {
                c = 0x10000 + ((c - 0xD800) << 10) + ((s[0] | s[1] << 8) - 0xDC00);
                s += 2;
            }
            if (n > 58) continue;
            if (c < 0x80) fields[f][n++] = (char)c;
            else if (c < 0x800) fields[f][n++] = (char)(0xC0 | c >> 6), fields[f][n++] = (char)(0x80 | (c & 0x3F));
            else if (c < 0x10000)
                fields[f][n++] = (char)(0xE0 | c >> 12), fields[f][n++] = (char)(0x80 | (c >> 6 & 0x3F)),
                fields[f][n++] = (char)(0x80 | (c & 0x3F));
            else
                fields[f][n++] = (char)(0xF0 | c >> 18), fields[f][n++] = (char)(0x80 | (c >> 12 & 0x3F)),
                fields[f][n++] = (char)(0x80 | (c >> 6 & 0x3F)), fields[f][n++] = (char)(0x80 | (c & 0x3F));
        }
        fields[f][n] = 0;
    }
    EXPECT(s == vgm + size, "11 strings fill the tag");
    EXPECT(!strcmp(fields[0], title) && !strcmp(fields[4], "OPL3 (Thor Tracker)") && !strcmp(fields[6], "Esko") &&
               !strcmp(fields[8], "2026/11/08") && !strcmp(fields[9], "Thor Tracker") && !fields[2][0] && !fields[10][0],
           "GD3 fields: '%s', '%s', '%s', '%s', '%s'", fields[0], fields[4], fields[6], fields[8], fields[9]);
    o3_export_free(vgm);
    /* rejected images report the loader's reason */
    memcpy(g_bad.bytes, g_song.bytes, sizeof g_bad.bytes);
    g_bad.bytes[O3_H_MULTISPEED] = 9;
    vgm = o3_export_vgm(g_bad.bytes, sizeof g_bad.bytes, 36000, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && size == 0 && problem && strstr(problem, "multispeed"), "bad image: NULL, '%s'", problem ? problem : "");
    vgm = o3_export_vgm(g_song.bytes, 100, 36000, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && problem && !strcmp(problem, "image size"), "short image: '%s'", problem ? problem : "");
    vgm = o3_export_vgm(g_song.bytes, sizeof g_song.bytes, 100, NULL, NULL, NULL, &size, &problem);
    EXPECT(vgm && (vgm[0x1C] | vgm[0x20]) == 0 && size > 0x100, "tick limit before the loop closes: no loop");
    if (vgm) {
        /* no date given: today's, "yyyy/mm/dd" (ASCII in UTF-16LE: every other byte) */
        const uint8_t* d = vgm + 0x14 + (vgm[0x14] | vgm[0x15] << 8) + 12;
        for (int f = 0; f < 8; ++f, d += 2)
            while (d[0] | d[1]) d += 2;
        char date[16] = {0};
        for (int i = 0; i < 15 && (d[2 * i] | d[2 * i + 1]); ++i) date[i] = (char)d[2 * i];
        EXPECT(strlen(date) == 10 && date[4] == '/' && date[7] == '/' && !strncmp(date, "20", 2), "default date today: %s", date);
    }
    o3_export_free(vgm);
}

static void test_ops(void) {
    printf("ops table\n");
    const ChipEngineOps* ops = chip_engine_ops("opl3-fm");
    EXPECT(ops && !strcmp(ops->system_id, "opl3-fm"), "registry");
    if (!ops) return;
    EXPECT(ops->image_size == O3_IMAGE_SIZE && ops->image_storage >= ops->image_size, "sizes");
    EXPECT(ops->create(CHIP_MAX_SAMPLE_RATE + 1) == NULL, "rate above the maximum rejected");
    ChipImage* image = (ChipImage*)calloc(1, ops->image_storage);
    EXPECT(ops->load_image(image, g_song.bytes, ops->image_size - 1) == CHIP_ERR_IMAGE, "wrong size rejected");
    memcpy(g_bad.bytes, g_song.bytes, sizeof g_bad.bytes);
    g_bad.bytes[0] = 'X';
    EXPECT(ops->load_image(image, g_bad.bytes, ops->image_size) == CHIP_ERR_IMAGE, "invalid rejected");
    EXPECT(ops->load_image(image, g_song.bytes, ops->image_size) == CHIP_OK, "song loads");
    ChipMachine* m = ops->create(RATE);
    int mode = -1, o, r, t;
    EXPECT(ops->start(m, image, 1, 4, SEEK_BUDGET, &mode) == CHIP_OK && mode == CHIP_SEEK_FROM_START, "start 1/4");
    ops->position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 4 && t == 0, "position 1/4/0");
    float out[CHIP_MAX_TICK_FRAMES * 2];
    int frames = 0, n = 0;
    for (int k = 0; k < 60; ++k) frames += (n = ops->tick(m, out));
    EXPECT(frames >= 48000 - 2 && frames <= 48000 + 2 && n >= 799 && n <= 801, "60 ticks = 1 s (%d frames)", frames);
    ops->position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 14 && t == 0, "10 rows later (%d/%d/%d)", o, r, t);
    ops->apply_image(m, image);
    EXPECT(ops->mute(m, 0, 1) == CHIP_OK && ops->mute(m, 0, 0) == CHIP_OK, "mute");
    EXPECT(ops->audition(m, image, 0, A4, SINE_SLOT, 0) == CHIP_OK, "audition");
    ops->tick(m, out);
    EXPECT(ops->release(m) == 1, "release phase");
    int ticks = 0;
    while (ops->tick(m, out) > 0 && ticks < 1000) ++ticks;
    EXPECT(ticks < 1000, "released note ends (%d ticks)", ticks);
    ops->destroy(m);
    free(image);
}

static void test_costs(void) {
    printf("costs\n");
    Opl3Machine* m = o3m_create(RATE);
    int mode;
    double t0 = now_us();
    o3m_start(m, &g_song, 3, 20, SEEK_BUDGET, &mode);
    const double seek = now_us() - t0;
    /* a 12000-tick seek: the loop (359 ticks) replayed */
    Opl3Image* big = &g_bad;
    memcpy(big, &g_song, sizeof *big);
    t0 = now_us();
    O3Player* p = (O3Player*)malloc(sizeof *p);
    o3p_reset(p, big);
    for (int k = 0; k < 12000; ++k) o3p_tick(p, big);
    const double per = (now_us() - t0) / 12000;
    free(p);
    printf("  seek to 3/20: %.0f us; player alone %.2f us per tick (12000 ticks: %.1f ms)\n", seek, per, per * 12);
    o3m_destroy(m);
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    EXPECT(load(&g_song, opl3_song_image, sizeof opl3_song_image) == 0, "song loads: %s", g_song.info.problem);
    EXPECT(load(&g_moved, opl3_moved_image, sizeof opl3_moved_image) == 0, "moved loads: %s", g_moved.info.problem);
    EXPECT(load(&g_edited, opl3_edited_image, sizeof opl3_edited_image) == 0, "edited loads: %s", g_edited.info.problem);
    EXPECT(load(&g_tempo, opl3_tempo_image, sizeof opl3_tempo_image) == 0, "tempo loads: %s", g_tempo.info.problem);
    EXPECT(load(&g_short, opl3_short_image, sizeof opl3_short_image) == 0, "short loads: %s", g_short.info.problem);
    EXPECT(load(&g_x2, opl3_x2_image, sizeof opl3_x2_image) == 0, "x2 loads: %s", g_x2.info.problem);
    test_tables();
    test_images();
    test_reference(dir);
    test_audio(dir);
    test_outliers();
    test_loop();
    test_seek();
    test_live_edits();
    test_audition(dir);
    test_mute();
    test_multispeed();
    test_vgm(dir);
    test_export();
    test_ops();
    test_costs();
    printf("OPL3: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
