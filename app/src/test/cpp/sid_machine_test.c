/* Host regression test for the SID machine: the GoatTracker 2.77 playroutine on m6502 + reSID 1.0,
 * playing the test song tools/audio/sid/gt_test_song.py in the Thor Tracker image layout and as
 * GoatTracker's own packed PSID (sid_gt_song.h, from tools/audio/prepare_sid_player.py).
 *
 * Checks: SID register writes identical to the reference (py65, an independent 6502 emulator, running
 * GoatTracker's packed PSID) for every frame; row starts on the frames the packed player's own counter
 * gives; image validation; seek; mute; audition (440 Hz) and release; deterministic, non-silent output
 * on both chip models; faults; the "c64-sid" ops table as the hosts drive it; multispeed tick length.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir]
 * Writes <dir>/sid_gt_8580.wav and sid_gt_6581.wav (48 kHz stereo, 30 s), sid_tone_8580.wav (the A-4
 * audition, 2 s), sid_trace.txt (per-tick SID register writes) and sid_gt.sid (GoatTracker's PSID). */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "chip_engine.h"
#include "sid_gt_song.h"
#include "sid_machine.h"

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
#define TONE_NOTE 57 /* A-4 */

static float g_out[SM_MAX_TICK_FRAMES * 2];
static SidImage g_image, g_6581, g_x2, g_bad, g_moved, g_edited, g_tempo, g_short;

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
            if (first_t < 0) {
                first_t = t;
            } else {
                last_t = t;
                ++count;
            }
        }
    }
    return count ? count * (double)RATE / (last_t - first_t) : 0;
}

static void stats(const float* x, int frames, double* rms, double* peak) {
    double s = 0, p = 0;
    for (int i = 0; i < frames * 2; ++i) {
        s += (double)x[i] * x[i];
        if (fabs(x[i]) > p) p = fabs(x[i]);
    }
    *rms = sqrt(s / (frames * 2.0));
    *peak = p;
}

/* Renders `ticks` ticks into a new buffer; returns frames (or -1 on a fault). */
static int render(SidMachine* m, int ticks, float** buf) {
    size_t cap = (size_t)ticks * SM_MAX_TICK_FRAMES * 2;
    float* out = (float*)malloc(cap * sizeof(float));
    int frames = 0;
    for (int t = 0; t < ticks; ++t) {
        int n = sm_tick(m, out + 2 * frames);
        if (n < 0) {
            free(out);
            *buf = NULL;
            return -1;
        }
        frames += n;
    }
    *buf = out;
    return frames;
}

static int load(SidImage* image, const uint8_t* bytes, size_t used) {
    memset(image->bytes, 0, sizeof image->bytes);
    memcpy(image->bytes, bytes, used);
    return sm_load_image(image);
}

static int ref_count(int f) { return (int)(sid_gt_ref_index[f + 1] - sid_gt_ref_index[f]); }
static const uint8_t* ref_writes(int f) { return sid_gt_ref_writes + 2 * sid_gt_ref_index[f]; }

/* Whether the machine's writes of its last tick equal reference frame f. */
static int same_as_ref(SidMachine* m, int f, int* n_out) {
    uint8_t got[2 * 256];
    int n = sm_log(m, got, 256);
    if (n_out) *n_out = n;
    return n == ref_count(f) && !memcmp(got, ref_writes(f), (size_t)n * 2);
}

/* (order, row) of the k-th row start of straight play. */
static void kth_row(int k, int* order, int* row) {
    int o = 0, r = 0;
    for (int i = 0; i < k; ++i) {
        if (++r >= sid_gt_rows[o]) {
            r = 0;
            if (++o >= SID_GT_ORDERS) o = SID_GT_LOOP;
        }
    }
    *order = o;
    *row = r;
}

static void test_player_image(void) {
    printf("player image\n");
    EXPECT(sid_player_code[0] == 0x4C && sid_player_code[3] == 0x4C, "jmp mt_init, jmp mt_play at $1000");
    const uint16_t patches[] = {SID_PATCH_FIRSTNOHRINSTR, SID_PATCH_FIRSTLEGATOINSTR, SID_PATCH_ADPARAM,
                                SID_PATCH_SRPARAM, SID_PATCH_DEFAULTTEMPO};
    for (int i = 0; i < 5; ++i) {
        EXPECT(patches[i] > SID_PLAYER_BASE && patches[i] < SID_PLAYER_BASE + SID_PLAYER_SIZE, "patch %d inside", i);
    }
    /* GoatTracker's frequency table (in the image): A-4 = note 57 and 12-TET within 1.5 cents above C-1. */
    const uint8_t* region = sm_region(&g_image);
    double worst = 0;
    for (int n = 12; n < 95; ++n) {
        const int v = region[SID_MT_FREQTBLLO - SID_REGION_BASE + n] | region[SID_MT_FREQTBLHI - SID_REGION_BASE + n] << 8;
        const double hz = v * (double)SM_CPU_HZ / 16777216.0;
        const double cents = 1200.0 * log2(hz / (440.0 * pow(2.0, (n - 57) / 12.0)));
        if (fabs(cents) > worst) worst = fabs(cents);
    }
    const int a4 = region[SID_MT_FREQTBLLO - SID_REGION_BASE + 57] | region[SID_MT_FREQTBLHI - SID_REGION_BASE + 57] << 8;
    EXPECT(fabs(a4 * (double)SM_CPU_HZ / 16777216.0 - 440.0) < 0.5, "A-4 register $%04X", a4);
    EXPECT(worst < 3.0, "frequency table off 12-TET by %.2f cents", worst);
    printf("  player $%04X-$%04X, A-4 = $%04X (%.2f Hz), table within %.2f cents of 12-TET\n", SID_PLAYER_BASE,
           SID_PLAYER_BASE + SID_PLAYER_SIZE - 1, a4, a4 * (double)SM_CPU_HZ / 16777216.0, worst);
}

static void corrupt(int offset, uint8_t value, const char* what) {
    memcpy(&g_bad, &g_image, sizeof g_bad);
    g_bad.bytes[offset] = value;
    const int r = sm_load_image(&g_bad);
    EXPECT(r != 0, "%s accepted", what);
    if (r) printf("  rejected %-44s: %s\n", what, g_bad.info.problem);
}

static void test_images(void) {
    printf("images\n");
    const SidImageInfo* i = &g_image.info;
    EXPECT(i->orders == SID_GT_ORDERS && i->loop == SID_GT_LOOP, "orders %d loop %d", i->orders, i->loop);
    int same = 1;
    for (int o = 0; o < SID_GT_ORDERS; ++o) same &= i->rows[o] == sid_gt_rows[o];
    EXPECT(same, "rows per order");
    EXPECT(i->instruments == 7 && i->multiplier == 1 && i->default_tempo == 5 && i->model == 1, "header fields");
    EXPECT(i->tempo[0] == 5 && i->tempo[1] == 5 && i->tempo[2] == 5 && i->tempo[3] == 4 && i->funk[2] == 6,
           "order-start tempos %d %d %d %d (funk %d)", i->tempo[0], i->tempo[1], i->tempo[2], i->tempo[3], i->funk[2]);
    const int H = SID_IMAGE_HEADER_SIZE;
    const int ol0 = i->orderlist[0] - SID_REGION_BASE + H, ol1 = i->orderlist[1] - SID_REGION_BASE + H;
    const int p0 = (g_image.bytes[H + SID_MT_PATTTBLLO - SID_REGION_BASE] | g_image.bytes[H + SID_MT_PATTTBLHI - SID_REGION_BASE] << 8) -
                   SID_REGION_BASE + H;
    corrupt(0, 'X', "magic");
    corrupt(4, g_image.bytes[4] ^ 1, "player id");
    corrupt(12, 0, "layout");
    corrupt(13, 2, "chip model");
    corrupt(14, 0, "speed multiplier 0 (25 Hz mode)");
    corrupt(14, 17, "speed multiplier 17");
    corrupt(15, 1, "default tempo 1");
    corrupt(20, 0, "instrument count 0");
    corrupt(SM_SLOT_MAP + 2, g_image.bytes[SM_SLOT_MAP + 1], "slot map duplicate");
    corrupt(SM_SLOT_MAP + 9, 9, "slot map past the count");
    corrupt(H + SID_MT_SONGTBLHI - SID_REGION_BASE, 0x10, "orderlist in the player");
    corrupt(ol0 + 1, 0xD1, "orderlist repeat command");
    corrupt(ol1 + 3, 0xFF, "orderlists of different length");
    corrupt(ol0 + 5, 4, "restart past the end");
    corrupt(ol0, 207, "pattern without data");
    corrupt(H + SID_MT_PATTTBLHI - SID_REGION_BASE, 0xD0, "pattern past the region");
    corrupt(p0, 0x00, "instrument 0");
    corrupt(p0, 0x09, "instrument above the count");
    corrupt(p0 + 1, 0xC1, "packed rest after an instrument");
    corrupt(p0 + 4, 0x00, "pattern shorter than its order");
    corrupt(10, 0x26, "data end below the patterns");
    corrupt(11, 0xD1, "data end past the region");
}

/* Straight play from power-on: writes equal the reference in every frame; row starts on its frames. */
static void test_reference(const char* dir) {
    printf("reference (py65 running GoatTracker's PSID)\n");
    SidMachine* m = sm_create(RATE);
    EXPECT(sm_power_on(m, &g_image) == SM_OK, "power on");
    sm_set_logging(m, 1);
    int first_bad = -1, starts = 0, start_bad = -1, row_bad = -1, writes = 0;
    char path[1024];
    snprintf(path, sizeof path, "%s/sid_trace.txt", dir);
    FILE* ft = fopen(path, "w");
    if (ft) fprintf(ft, "# tick: SID writes as reg=value (hex), GoatTracker 2.77 player, gt_test.sng (Thor layout)\n");
    for (int f = 0; f < SID_GT_REF_FRAMES; ++f) {
        int o, r, t;
        sm_position(m, &o, &r, &t);
        int n = 0;
        if (sm_tick_silent(m) != SM_OK) {
            EXPECT(0, "fault at tick %d", f);
            break;
        }
        uint8_t got[2 * 256];
        n = sm_log(m, got, 256);
        const int ok = n == ref_count(f) && !memcmp(got, ref_writes(f), (size_t)n * 2);
        writes += n;
        if (!ok && first_bad < 0) first_bad = f;
        if (ft) {
            fprintf(ft, "%d:", f);
            for (int k = 0; k < n; ++k) fprintf(ft, " %02X=%02X", got[2 * k], got[2 * k + 1]);
            fprintf(ft, "\n");
        }
        int o2, r2, t2;
        sm_position(m, &o2, &r2, &t2);
        if (t2 == 1) { /* this tick started a row */
            int eo, er;
            kth_row(starts, &eo, &er);
            if (starts >= SID_GT_REF_STARTS || sid_gt_ref_starts[starts] != f) {
                if (start_bad < 0) start_bad = f;
            }
            if ((o != eo || r != er || t != 0 || o2 != eo || r2 != er) && row_bad < 0) row_bad = f;
            ++starts;
        }
    }
    if (ft) fclose(ft);
    EXPECT(first_bad < 0, "Thor image writes differ from the reference first at frame %d", first_bad);
    EXPECT(starts == SID_GT_REF_STARTS && start_bad < 0, "row starts %d vs %d, first mismatch at frame %d", starts,
           SID_GT_REF_STARTS, start_bad);
    EXPECT(row_bad < 0, "position (order, row) wrong at frame %d", row_bad);
    printf("  Thor image: %d SID writes in %d frames identical, %d row starts on the reference frames, worst call %u cycles\n",
           writes, SID_GT_REF_FRAMES, starts, sm_max_call_cycles(m));
    /* GoatTracker's own PSID on the same machine. */
    EXPECT(sm_load_psid(m, sid_gt_psid, sizeof sid_gt_psid, 1) == SM_OK, "load PSID");
    sm_set_logging(m, 1);
    first_bad = -1;
    for (int f = 0; f < SID_GT_REF_FRAMES && sm_tick_silent(m) == SM_OK; ++f) {
        if (!same_as_ref(m, f, NULL) && first_bad < 0) first_bad = f;
    }
    EXPECT(first_bad < 0, "GoatTracker's PSID on m6502 differs from py65 first at frame %d", first_bad);
    printf("  GoatTracker's PSID on this machine: identical to py65 in all %d frames\n", SID_GT_REF_FRAMES);
    snprintf(path, sizeof path, "%s/sid_gt.sid", dir);
    FILE* fs = fopen(path, "wb");
    if (fs) {
        fwrite(sid_gt_psid, 1, sizeof sid_gt_psid, fs);
        fclose(fs);
    }
    sm_destroy(m);
}

/* 30 s on each model: non-silent, no clipping, deterministic; WAVs; cost per tick. */
static void test_audio(const char* dir) {
    printf("audio\n");
    const int ticks = (int)(SECONDS * 50.1245);
    const SidImage* images[2] = {&g_image, &g_6581};
    const char* names[2] = {"8580", "6581"};
    for (int k = 0; k < 2; ++k) {
        SidMachine* m = sm_create(RATE);
        int mode;
        EXPECT(sm_start(m, images[k], 0, 0, 100, &mode) == SM_OK, "start");
        float* a;
        const double t0 = now_us();
        const int frames = render(m, ticks, &a);
        const double us = now_us() - t0;
        EXPECT(frames > 0, "rendered");
        if (frames <= 0) {
            sm_destroy(m);
            continue;
        }
        double rms, peak;
        stats(a, frames, &rms, &peak);
        int clipped = 0;
        for (int i = 0; i < frames; ++i) clipped += fabsf(a[2 * i]) >= 32767.0f / 32768.0f;
        EXPECT(rms > 0.02 && clipped * 10000 < frames, "%s rms %.4f peak %.4f, %d samples at full scale", names[k], rms,
               peak, clipped);
        EXPECT(frames >= SECONDS * RATE - RATE / 10 && frames <= SECONDS * RATE + RATE / 10, "%d frames", frames);
        /* again: identical samples */
        sm_start(m, images[k], 0, 0, 100, &mode);
        float* b;
        const int frames_b = render(m, ticks, &b);
        EXPECT(frames_b == frames && !memcmp(a, b, (size_t)frames * 8), "%s deterministic", names[k]);
        char path[1024];
        snprintf(path, sizeof path, "%s/sid_gt_%s.wav", dir, names[k]);
        write_wav(path, a, frames);
        printf("  %s: rms %.4f peak %.4f, hash %016llx, %.1f us per tick (%.0fx realtime), wrote %s\n", names[k], rms, peak,
               (unsigned long long)fnv(0xcbf29ce484222325ull, a, (size_t)frames * 8), us / ticks,
               (ticks / 50.1245) * 1e6 / us, path);
        free(a);
        free(b);
        sm_destroy(m);
    }
    /* Sampling methods and models: cost per tick (dev PC). */
    for (int method = 0; method < 2; ++method) {
        for (int k = 0; k < 2; ++k) {
            SidMachine* m = sm_create_sampling(RATE, method);
            int mode;
            sm_start(m, images[k], 0, 0, 100, &mode);
            float* a;
            const double t0 = now_us();
            const int frames = render(m, 1000, &a);
            const double us = now_us() - t0;
            free(a);
            (void)frames;
            printf("  cost %s %s: %.1f us per tick, %.0fx realtime\n", method ? "interpolate" : "resample  ", names[k],
                   us / 1000, 1000 / 50.1245 * 1e6 / us);
            sm_destroy(m);
        }
    }
    const double c0 = now_us();
    SidMachine* m = sm_create(RATE);
    printf("  sm_create: %.0f us\n", now_us() - c0);
    sm_destroy(m);
}

/* 2 s of audio after seeking to (order, row) (row start at reference frame f0) against the same 2 s of
 * straight play, skipping the first 20 ms. Seeking clocks the SID in bulk (reSID's multi-cycle filter
 * steps, a noise generator that does not repeat the per-cycle sequence), so samples differ even where
 * every tick was clocked; what is compared is the loudness: mean |difference| of the 20 ms RMS envelopes
 * relative to straight play's mean. *sample_diff gets the normalized RMS difference of the samples. */
static double seek_envelope_diff(int order, int row, int f0, double* sample_diff) {
    SidMachine* s = sm_create(RATE);
    SidMachine* k = sm_create(RATE);
    int mode;
    sm_start(s, &g_image, 0, 0, 100, &mode);
    for (uint32_t t = sm_ticks(s); t < (uint32_t)f0; ++t) sm_tick(s, g_out);
    float *a, *b;
    const int na = render(s, 100, &a);
    sm_start(k, &g_image, order, row, 12000, &mode);
    const int nb = render(k, 100, &b);
    const int n = na < nb ? na : nb, win = RATE / 50;
    double d = 0, e = 0, env_d = 0, env_a = 0;
    for (int w = 1; (w + 1) * win <= n; ++w) {
        double ea = 0, eb = 0;
        for (int i = w * win; i < (w + 1) * win; ++i) {
            ea += (double)a[2 * i] * a[2 * i];
            eb += (double)b[2 * i] * b[2 * i];
            d += (double)(a[2 * i] - b[2 * i]) * (a[2 * i] - b[2 * i]);
        }
        e += ea;
        env_a += sqrt(ea / win);
        env_d += fabs(sqrt(ea / win) - sqrt(eb / win));
    }
    free(a);
    free(b);
    sm_destroy(s);
    sm_destroy(k);
    *sample_diff = e > 0 ? sqrt(d / e) : 1;
    return env_a > 0 ? env_d / env_a : 1;
}

/* Seek: from the start (positions and writes in step with the reference), the order fallback, limits. */
static void test_seek(void) {
    printf("seek\n");
    SidMachine* m = sm_create(RATE);
    const int targets[][2] = {{0, 0}, {0, 5}, {1, 0}, {1, 17}, {2, 8}, {3, 23}};
    for (size_t k = 0; k < sizeof targets / sizeof targets[0]; ++k) {
        const int o = targets[k][0], r = targets[k][1];
        int idx = 0;
        for (int oo = 0; oo < o; ++oo) idx += sid_gt_rows[oo];
        idx += r;
        const int f0 = sid_gt_ref_starts[idx];
        int mode = -1;
        const double t0 = now_us();
        EXPECT(sm_start(m, &g_image, o, r, 12000, &mode) == SM_OK && mode == SM_SEEK_FROM_START, "seek %d/%d", o, r);
        const double us = now_us() - t0;
        int po, pr, pt;
        sm_position(m, &po, &pr, &pt);
        EXPECT(po == o && pr == r && pt == 0, "seek %d/%d lands at %d/%d/%d", o, r, po, pr, pt);
        EXPECT(sm_ticks(m) == (uint32_t)f0, "seek %d/%d after %u ticks, reference row start at frame %d", o, r, sm_ticks(m), f0);
        sm_set_logging(m, 1);
        int bad = -1;
        for (int i = 0; i < 200 && f0 + i < SID_GT_REF_FRAMES; ++i) {
            sm_tick_silent(m);
            if (!same_as_ref(m, f0 + i, NULL) && bad < 0) bad = i;
        }
        EXPECT(bad < 0, "after seek %d/%d writes differ at tick %d", o, r, bad);
        printf("  %d/%d: %u silent ticks in %.2f ms (%.1f us per tick), writes in step for 200 ticks\n", o, r, f0, us / 1000,
               f0 ? us / f0 : 0.0);
    }
    /* Order fallback: budget too small; lands on the row and the rows keep the reference's timing. */
    int mode = -1;
    EXPECT(sm_start(m, &g_image, 3, 6, 40, &mode) == SM_OK && mode == SM_SEEK_FROM_ORDER, "fallback 3/6 (mode %d)", mode);
    int po, pr, pt;
    sm_position(m, &po, &pr, &pt);
    EXPECT(po == 3 && pr == 6 && pt == 0, "fallback lands at %d/%d/%d", po, pr, pt);
    const uint32_t landed = sm_ticks(m);
    int idx = 16 + 32 + 16 + 6;
    int bad = -1, rows = 0;
    for (int i = 0; i < 200; ++i) {
        sm_tick_silent(m);
        sm_position(m, &po, &pr, &pt);
        if (pt == 1) {
            if (sid_gt_ref_starts[idx + rows] - sid_gt_ref_starts[idx] != i && bad < 0) bad = i;
            ++rows;
        }
    }
    EXPECT(bad < 0 && rows > 20, "fallback rows out of step at tick %d (%d rows)", bad, rows);
    printf("  fallback 3/6 (budget 40): landed after %u ticks, %d rows in step\n", landed, rows);
    /* Audio after a seek: inside the settle window every tick was clocked; farther away the SID's state
     * from before the window (oscillator phases, old envelopes) is approximate. Compared by loudness. */
    double near_s, far_s;
    const double near = seek_envelope_diff(0, 5, sid_gt_ref_starts[5], &near_s);
    const double far = seek_envelope_diff(3, 8, sid_gt_ref_starts[16 + 32 + 16 + 8], &far_s);
    EXPECT(near < 0.05, "loudness after seek 0/5 differs from straight play by %.3f", near);
    EXPECT(far < 0.10, "loudness after seek 3/8 differs from straight play by %.3f", far);
    printf("  after seek vs straight play, 2 s: loudness envelope off by %.3f (0/5) and %.3f (3/8, past the 3 s window); "
           "samples %.3f / %.3f (bulk clocking)\n", near, far, near_s, far_s);
    EXPECT(sm_start(m, &g_image, 4, 0, 100, &mode) == SM_ERR_IMAGE, "order past the song");
    EXPECT(sm_start(m, &g_image, 0, 16, 100, &mode) == SM_ERR_IMAGE, "row past the order");
    sm_destroy(m);
}

static void test_audition(const char* dir) {
    printf("audition\n");
    SidMachine* m = sm_create(RATE);
    const int tone = g_image.bytes[SM_SLOT_MAP + SID_GT_TONE_SLOT];
    EXPECT(sm_audition(m, &g_image, 0, TONE_NOTE, tone, 0) == SM_OK, "audition A-4");
    float* a;
    const int frames = render(m, 100, &a); /* 2 s */
    const double hz = zero_cross_hz(a + 2 * (RATE / 10), RATE);
    const uint8_t* region = sm_region(&g_image);
    const int reg = region[SID_MT_FREQTBLLO - SID_REGION_BASE + TONE_NOTE] | region[SID_MT_FREQTBLHI - SID_REGION_BASE + TONE_NOTE] << 8;
    const double expect = reg * (double)SM_CPU_HZ / 16777216.0;
    EXPECT(fabs(hz - expect) < 0.2 && fabs(hz - 440.0) < 0.5, "A-4 sounds %.3f Hz (register %.3f Hz)", hz, expect);
    double rms, peak;
    stats(a + 2 * (RATE / 10), RATE, &rms, &peak);
    EXPECT(rms > 0.05, "audition audible (rms %.4f)", rms);
    double rms_end, peak_end;
    stats(a + 2 * (frames - RATE / 4), RATE / 4, &rms_end, &peak_end);
    EXPECT(rms_end > 0.5 * rms, "sustains for 2 s (rms %.4f -> %.4f)", rms, rms_end);
    EXPECT(sm_envelope(m, 1) == 0 && sm_envelope(m, 2) == 0, "other voices silent");
    char path[1024];
    snprintf(path, sizeof path, "%s/sid_tone_8580.wav", dir);
    write_wav(path, a, frames);
    printf("  A-4 on \"Tone\": %.3f Hz (register $%04X = %.3f Hz), rms %.3f, wrote %s\n", hz, reg, expect, rms, path);
    free(a);
    /* Release: the envelope falls to 0 (Tone: release 0), then tick returns 0. */
    EXPECT(sm_release(m) == 1, "release");
    int n = 0, ended = -1;
    for (int t = 0; t < 200; ++t) {
        if (sm_tick(m, g_out) == 0) {
            ended = t;
            break;
        }
        ++n;
    }
    EXPECT(ended >= 1 && ended < 10, "release tail %d ticks", ended);
    /* Lead (release 9): a longer tail through the player's key-off. */
    const int lead = g_image.bytes[SM_SLOT_MAP + 1];
    EXPECT(sm_audition(m, &g_image, 1, 48, lead, 0) == SM_OK, "audition C-4 on channel 2");
    for (int t = 0; t < 25; ++t) sm_tick(m, g_out);
    EXPECT(sm_envelope(m, 1) > 0 && sm_envelope(m, 0) == 0, "voice 2 sounds (env %d)", sm_envelope(m, 1));
    EXPECT(sm_release(m) == 1, "release lead");
    ended = -1;
    for (int t = 0; t < 2000; ++t) {
        if (sm_tick(m, g_out) == 0) {
            ended = t;
            break;
        }
    }
    EXPECT(ended > 10, "lead release tail %d ticks", ended);
    printf("  release tails: Tone %d ticks, Lead %d ticks\n", n, ended);
    EXPECT(sm_audition(m, &g_image, 0, TONE_NOTE, tone, 0) == SM_OK, "audition again");
    EXPECT(sm_release(m) == 0, "release before any tick cuts");
    /* An instrument that has decayed to silence (sustain 0): release cuts (0) instead of a tail. */
    static SidImage decay;
    memcpy(&decay, &g_image, sizeof decay);
    decay.bytes[SID_IMAGE_HEADER_SIZE + SID_MT_INSSR - SID_REGION_BASE + tone - 1] = 0x00;
    EXPECT(sm_load_image(&decay) == 0, "decay variant");
    EXPECT(sm_audition(m, &decay, 0, TONE_NOTE, tone, 0) == SM_OK, "audition sustain 0");
    for (int t = 0; t < 10; ++t) sm_tick(m, g_out);
    EXPECT(sm_envelope(m, 0) == 0 && sm_release(m) == 0 && sm_tick(m, g_out) == 0, "decayed note: release cuts");
    EXPECT(sm_audition(m, &g_image, 3, 57, tone, 0) == SM_ERR_IMAGE, "channel 3");
    EXPECT(sm_audition(m, &g_image, 0, 93, tone, 0) == SM_ERR_IMAGE, "note 93 (above G#7)");
    EXPECT(sm_audition(m, &g_image, 0, 57, 0, 0) == SM_ERR_IMAGE, "instrument 0");
    EXPECT(sm_audition(m, &g_image, 0, 57, 8, 0) == SM_ERR_IMAGE, "instrument past the count");
    EXPECT(sm_audition(m, &g_image, 0, 57, tone, 3) == SM_ERR_IMAGE, "fine pitch");
    sm_destroy(m);
}

/* The A-4 held in the song (order 1, channel 1) with the other voices muted; mute leaves writes alone. */
static void test_mute(void) {
    printf("mute\n");
    SidMachine* m = sm_create(RATE);
    int mode;
    EXPECT(sm_start(m, &g_image, 1, 0, 12000, &mode) == SM_OK, "seek 1/0");
    sm_mute_channel(m, 1, 1);
    sm_mute_channel(m, 2, 1);
    float* a;
    const int frames = render(m, 25, &a); /* within the 16-row note */
    const double hz = zero_cross_hz(a + 2 * (RATE / 10), frames - RATE / 10);
    EXPECT(fabs(hz - 440.09) < 0.5, "held A-4 in the song: %.3f Hz", hz);
    free(a);
    sm_mute_channel(m, 0, 1);
    const int f2 = render(m, 10, &a); /* 200 ms: the external filter's step from the removed voices decays */
    double rms, peak;
    stats(a + 2 * (f2 - RATE / 10), RATE / 10, &rms, &peak);
    EXPECT(peak < 0.002, "all voices muted: peak %.5f in the last 100 ms", peak);
    free(a);
    EXPECT(sm_mute_channel(m, 3, 1) == SM_ERR_IMAGE, "channel 3");
    /* writes with and without mute are the same */
    SidMachine* u = sm_create(RATE);
    sm_start(m, &g_image, 0, 0, 100, &mode);
    sm_start(u, &g_image, 0, 0, 100, &mode);
    sm_mute_channel(m, 0, 1);
    sm_set_logging(m, 1);
    sm_set_logging(u, 1);
    int same = 1;
    for (int t = 0; t < 300; ++t) {
        uint8_t x[512], y[512];
        sm_tick(m, g_out);
        sm_tick(u, g_out);
        const int nx = sm_log(m, x, 256), ny = sm_log(u, y, 256);
        same &= nx == ny && !memcmp(x, y, (size_t)nx * 2);
    }
    EXPECT(same, "muted machine writes like an unmuted one");
    sm_mute_channel(m, 0, 0);
    EXPECT(sm_tick(m, g_out) > 0, "unmuted");
    printf("  held A-4 %.3f Hz alone; all muted peak < 0.002; unmuted again\n", hz);
    sm_destroy(u);
    sm_destroy(m);
}

static void test_faults_and_speed(void) {
    printf("faults, multispeed, live apply\n");
    SidMachine* m = sm_create(RATE);
    int mode;
    sm_start(m, &g_image, 0, 0, 100, &mode);
    sm_poke(m, SID_PLAYER_PLAY, 0x00); /* BRK */
    EXPECT(sm_tick(m, g_out) == SM_ERR_FAULT && sm_tick(m, g_out) == SM_ERR_FAULT, "BRK faults and stays faulted");
    sm_start(m, &g_image, 0, 0, 100, &mode);
    sm_poke(m, SID_PLAYER_PLAY, 0x02); /* JAM */
    EXPECT(sm_tick(m, g_out) == SM_ERR_FAULT, "JAM faults");
    sm_start(m, &g_image, 0, 0, 100, &mode);
    sm_poke(m, SID_PLAYER_PLAY + 1, (uint8_t)SID_PLAYER_PLAY); /* jmp to itself */
    sm_poke(m, SID_PLAYER_PLAY + 2, (uint8_t)(SID_PLAYER_PLAY >> 8));
    EXPECT(sm_tick(m, g_out) == SM_ERR_FAULT, "runaway call faults");
    EXPECT(sm_start(m, &g_image, 0, 0, 100, &mode) == SM_OK && sm_tick(m, g_out) > 0, "a new start recovers");
    /* Multispeed: x2 ticks are CIA periods of $4CC7 / 2 + 1 cycles. */
    EXPECT(sm_start(m, &g_x2, 0, 0, 200, &mode) == SM_OK, "start x2");
    const int n = sm_tick(m, g_out);
    EXPECT(sm_tick_cycles(m) == 0x4CC7 / 2 + 1 && n >= 477 && n <= 480, "x2 tick %u cycles, %d frames", sm_tick_cycles(m), n);
    /* Live apply of the same image mid-song keeps position and writes. */
    SidMachine* u = sm_create(RATE);
    sm_start(m, &g_image, 1, 3, 12000, &mode);
    sm_start(u, &g_image, 1, 3, 12000, &mode);
    sm_set_logging(m, 1);
    sm_set_logging(u, 1);
    int same = 1;
    for (int t = 0; t < 300; ++t) {
        if (t % 7 == 0) sm_apply_image(m, &g_image);
        uint8_t x[512], y[512];
        sm_tick_silent(m);
        sm_tick_silent(u);
        const int nx = sm_log(m, x, 256), ny = sm_log(u, y, 256);
        same &= nx == ny && !memcmp(x, y, (size_t)nx * 2);
    }
    int o, r, t, uo, ur, ut;
    sm_position(m, &o, &r, &t);
    sm_position(u, &uo, &ur, &ut);
    EXPECT(same && o == uo && r == ur && t == ut, "apply_image of the same image changes nothing");
    printf("  BRK/JAM/runaway fault; x2 tick %u cycles; same-image apply in step for 300 ticks\n", 0x4CC7 / 2 + 1);
    sm_destroy(u);
    sm_destroy(m);
}

#define CHN_PEEK(m, sym, c) sm_peek((m), (uint16_t)(SID_MT_##sym + 7 * (c)))

/* Ticks m and u once (silently) and tells whether they wrote the same SID registers. */
static int tick_same(SidMachine* m, SidMachine* u) {
    uint8_t x[512], y[512];
    sm_tick_silent(m);
    sm_tick_silent(u);
    const int nx = sm_log(m, x, 256), ny = sm_log(u, y, 256);
    return nx == ny && !memcmp(x, y, (size_t)nx * 2);
}

/* Index of the row start (order, row) in the first pass of straight play. */
static int row_index(int order, int row) {
    int k = row;
    for (int o = 0; o < order; ++o) k += sid_gt_rows[o];
    return k;
}

/* Live edits relocate: a re-laid-out image before every tick plays like straight play; edits ahead are
 * heard as straight play of the edited song; tempo edits behind the playhead, a shortened song, chip
 * model and speed changes. */
static void test_live_edits(void) {
    printf("live edits (relocating apply_image)\n");
    SidMachine* m = sm_create(RATE);
    SidMachine* u = sm_create(RATE);
    /* 1. From power-on, the moved layout and the original alternately before every tick: the reference. */
    EXPECT(sm_power_on(m, &g_image) == SM_OK, "power on");
    sm_set_logging(m, 1);
    int bad = -1, starts = 0, start_bad = -1, mapped = 0;
    double worst_us = 0, sum_us = 0;
    for (int f = 0; f < SID_GT_REF_FRAMES; ++f) {
        const SidImage* next = f & 1 ? &g_image : &g_moved;
        const double t0 = now_us();
        sm_apply_image(m, next);
        const double us = now_us() - t0;
        sum_us += us;
        if (us > worst_us) worst_us = us;
        if (next == &g_moved) {
            for (int c = 0; c < SM_CHANNELS; ++c) mapped += CHN_PEEK(m, CHNWAVEPTR, c) > SID_GT_PROGRAM_SHIFT;
        }
        if (sm_tick_silent(m) != SM_OK) {
            EXPECT(0, "fault at tick %d", f);
            break;
        }
        if (!same_as_ref(m, f, NULL) && bad < 0) bad = f;
        int o, r, t;
        sm_position(m, &o, &r, &t);
        if (t == 1) {
            if ((starts >= SID_GT_REF_STARTS || sid_gt_ref_starts[starts] != f) && start_bad < 0) start_bad = f;
            ++starts;
        }
    }
    EXPECT(bad < 0, "re-laid out before every tick: writes differ from the reference first at frame %d", bad);
    EXPECT(starts == SID_GT_REF_STARTS && start_bad < 0, "row starts %d, first off at frame %d", starts, start_bad);
    EXPECT(mapped > 300, "moved wave pointers in use: %d", mapped);
    printf("  moved/original layout before each of %d ticks: writes = reference, %d row starts on its frames; "
           "%d channel-ticks in moved wave programs; apply %.1f us mean, %.1f us worst\n",
           SID_GT_REF_FRAMES, starts, mapped, sum_us / SID_GT_REF_FRAMES, worst_us);
    /* ...and after seeks (from the start, and the order fallback) against the unmoved machine. */
    const int seeks[][3] = {{0, 5, 12000}, {1, 17, 12000}, {2, 3, 12000}, {3, 6, 40}};
    for (size_t k = 0; k < sizeof seeks / sizeof seeks[0]; ++k) {
        int mode, mode_u;
        sm_start(m, &g_image, seeks[k][0], seeks[k][1], seeks[k][2], &mode);
        sm_start(u, &g_image, seeks[k][0], seeks[k][1], seeks[k][2], &mode_u);
        sm_set_logging(m, 1);
        sm_set_logging(u, 1);
        int same = 1;
        for (int t = 0; t < 600; ++t) {
            sm_apply_image(m, t & 1 ? &g_image : &g_moved);
            same &= tick_same(m, u);
        }
        EXPECT(same && mode == mode_u, "after seek %d/%d (mode %d) the relocating machine left straight play", seeks[k][0],
               seeks[k][1], mode);
    }
    printf("  after seeks 0/5, 1/17, 2/3 and the order fallback 3/6: in step for 600 ticks each\n");

    /* 2. A note edit ahead of the playhead, applied at ticks up to its row's start (incl. between its
     *    fetch and start): from then on the writes are straight play's of the edited song. */
    const int edit_start = sid_gt_ref_starts[row_index(SID_GT_EDIT_ORDER, SID_GT_EDIT_ROW)];
    int edit_bad = -1, edit_differs = 0;
    for (int at = edit_start - 12; at <= edit_start + 1; ++at) {
        const int when = at == edit_start + 1 ? 300 : at; /* also once well before, during order 1 */
        sm_power_on(m, &g_image);
        sm_power_on(u, &g_edited);
        sm_set_logging(m, 1);
        sm_set_logging(u, 1);
        int same = 1;
        for (int t = 0; t < SID_GT_REF_FRAMES; ++t) {
            if (t == when) sm_apply_image(m, &g_edited);
            same &= tick_same(m, u);
        }
        if (!same && edit_bad < 0) edit_bad = when;
    }
    /* the edit is audible: the original differs from the edited song */
    sm_power_on(m, &g_image);
    sm_power_on(u, &g_edited);
    sm_set_logging(m, 1);
    sm_set_logging(u, 1);
    for (int t = 0; t < edit_start + 10; ++t) edit_differs |= !tick_same(m, u);
    EXPECT(edit_bad < 0 && edit_differs, "note edit applied at tick %d is not straight play of the edit", edit_bad);
    printf("  note edit (order %d row %d) applied at each of the 13 ticks before its row starts and during order 1: "
           "writes = straight play of the edited song\n", SID_GT_EDIT_ORDER, SID_GT_EDIT_ROW);

    /* 3. Tempo edit behind the playhead (order 0 row 0 F06 -> F09) at 0/5: rows of 9 ticks from there;
     *    undone at 0/8: 6 again. A funktempo speedtable edit while funktempo plays: the new pair. */
    int mode;
    sm_start(m, &g_image, 0, 5, 12000, &mode);
    sm_apply_image(m, &g_tempo);
    int tempo_ok = 1;
    for (int c = 0; c < SM_CHANNELS; ++c) tempo_ok &= CHN_PEEK(m, CHNTEMPO, c) == SID_GT_TEMPO_EDIT;
    int lengths[6] = {0}, n = 0, since = 0;
    for (int t = 0; t < 200 && n < 6; ++t) {
        sm_tick_silent(m);
        int o, r, k;
        sm_position(m, &o, &r, &k);
        if (k == 1 && t) {
            lengths[n++] = since;
            since = 0;
            if (o == 0 && r == 8) sm_apply_image(m, &g_image); /* undo once row 8 has started */
        }
        ++since;
    }
    EXPECT(tempo_ok && lengths[0] == SID_GT_TEMPO_EDIT + 1 && lengths[1] == SID_GT_TEMPO_EDIT + 1 &&
               lengths[3] == 6 && lengths[4] == 6,
           "tempo edit: chntempo ok %d, row lengths %d %d %d %d %d", tempo_ok, lengths[0], lengths[1], lengths[2],
           lengths[3], lengths[4]);
    static SidImage funk;
    memcpy(&funk, &g_image, sizeof funk);
    uint8_t* left = funk.bytes + SID_IMAGE_HEADER_SIZE + SID_MT_SPEEDLEFTTBL - SID_REGION_BASE;
    uint8_t* right = funk.bytes + SID_IMAGE_HEADER_SIZE + SID_MT_SPEEDRIGHTTBL - SID_REGION_BASE;
    const int fi = g_image.info.funk[2];
    left[fi - 1] = 5;
    right[fi - 1] = 3;
    EXPECT(sm_load_image(&funk) == 0, "funk variant");
    sm_start(m, &g_image, 1, 4, 12000, &mode);
    const int in_funk = CHN_PEEK(m, CHNTEMPO, 0) < 2;
    sm_apply_image(m, &funk);
    EXPECT(in_funk && sm_peek(m, SID_MT_FUNKTEMPOTBL) == 5 && sm_peek(m, SID_MT_FUNKTEMPOTBL + 1) == 3 &&
               CHN_PEEK(m, CHNTEMPO, 0) < 2,
           "funktempo pair edit: %d/%d", sm_peek(m, SID_MT_FUNKTEMPOTBL), sm_peek(m, SID_MT_FUNKTEMPOTBL + 1));
    printf("  tempo edit behind the playhead: rows of %d, %d ticks, undone: %d, %d; funktempo pair edit 6/4 -> 5/3 taken\n",
           lengths[0], lengths[1], lengths[3], lengths[4]);

    /* 4. The song shortened under the playhead (order 3 -> orders 0-1): clamped to the last row of order 1,
     *    then plays order 1 (the restart) in step with straight play of the short song there. */
    sm_start(m, &g_image, 3, 5, 12000, &mode);
    sm_apply_image(m, &g_short);
    int o, r, k;
    sm_position(m, &o, &r, &k);
    const int clamped = o == 1 && r == 0 && k == 0; /* clamped to its last row: the restart row is next */
    int short_ok = 1, rows = 0;
    for (int t = 0; t < 600; ++t) {
        short_ok &= sm_tick_silent(m) == SM_OK;
        sm_position(m, &o, &r, &k);
        if (k == 1) {
            short_ok &= o == 1 && r == rows % sid_gt_rows[1];
            ++rows;
        }
    }
    EXPECT(clamped && short_ok && rows > 50, "shortened song: clamped %d, %d rows in order 1, ok %d", clamped, rows, short_ok);
    printf("  song cut to orders 0-1 under order 3: clamped to 1/%d, then %d rows of order 1 in order\n", sid_gt_rows[1] - 1,
           rows);

    /* 5. Chip model and speed from the header, live: 6581 writes the same registers; x2 halves the tick. */
    sm_start(m, &g_image, 1, 0, 12000, &mode);
    sm_start(u, &g_image, 1, 0, 12000, &mode);
    sm_set_logging(m, 1);
    sm_set_logging(u, 1);
    sm_apply_image(m, &g_6581);
    int same = 1;
    for (int t = 0; t < 100; ++t) same &= tick_same(m, u);
    const int frames = sm_tick(m, g_out);
    double rms, peak;
    stats(g_out, frames, &rms, &peak);
    sm_apply_image(m, &g_x2);
    EXPECT(same && rms > 0.01 && sm_tick_cycles(m) == 0x4CC7 / 2 + 1, "model/speed live: same %d rms %.3f cycles %u", same,
           rms, sm_tick_cycles(m));
    printf("  6581 applied live: writes unchanged, rms %.3f; x2 applied live: %u cycles per tick\n", rms, sm_tick_cycles(m));
    sm_destroy(u);
    sm_destroy(m);
}

static void test_ops(void) {
    printf("ops table\n");
    const ChipEngineOps* ops = chip_engine_ops("c64-sid");
    EXPECT(ops && !strcmp(ops->system_id, "c64-sid"), "registry");
    if (!ops) return;
    EXPECT(ops->image_size == SID_IMAGE_SIZE && ops->image_storage >= ops->image_size, "sizes");
    ChipImage* storage = (ChipImage*)malloc(ops->image_storage);
    EXPECT(ops->load_image(storage, g_image.bytes, ops->image_size - 1) == CHIP_ERR_IMAGE, "short image rejected");
    EXPECT(ops->load_image(storage, g_image.bytes, ops->image_size) == CHIP_OK, "load");
    EXPECT(ops->create(4000) == NULL && ops->create(200000) == NULL, "rates outside 8-96 kHz");
    ChipMachine* m = ops->create(RATE);
    int mode;
    EXPECT(ops->start(m, storage, 0, 0, 12000, &mode) == CHIP_OK && mode == CHIP_SEEK_FROM_START, "start");
    int o, r, t, frames = 0, max = 0;
    for (int i = 0; i < 60; ++i) {
        const int n = ops->tick(m, g_out);
        frames += n;
        if (n > max) max = n;
    }
    ops->position(m, &o, &r, &t);
    EXPECT(o == 0 && r >= 8, "position advanced to %d/%d/%d", o, r, t);
    EXPECT(max <= (int)CHIP_MAX_TICK_FRAMES && frames > 55000, "%d frames, max %d per tick", frames, max);
    ops->apply_image(m, storage);
    int o2, r2, t2;
    ops->position(m, &o2, &r2, &t2);
    EXPECT(o2 == o && r2 == r && t2 == t, "apply keeps the position");
    EXPECT(ops->mute(m, 0, 1) == CHIP_OK && ops->mute(m, 0, 0) == CHIP_OK, "mute");
    ChipMachine* a = ops->create(RATE);
    EXPECT(ops->audition(a, storage, 2, TONE_NOTE, SID_GT_TONE_SLOT, 0) == CHIP_OK, "audition slot %d", SID_GT_TONE_SLOT);
    EXPECT(ops->tick(a, g_out) > 0 && ops->release(a) == 1, "audition sounds, release has a tail");
    int ended = 0;
    for (int i = 0; i < 100 && !ended; ++i) ended = ops->tick(a, g_out) == 0;
    EXPECT(ended, "released audition ends");
    EXPECT(ops->audition(a, storage, 0, TONE_NOTE, 0, 0) == CHIP_ERR_IMAGE, "slot 0");
    EXPECT(ops->audition(a, storage, 0, TONE_NOTE, 12, 0) == CHIP_ERR_IMAGE, "empty slot");
    ops->destroy(a);
    ops->destroy(m);
    free(storage);
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    EXPECT(load(&g_image, sid_gt_image, SID_GT_IMAGE_USED) == 0, "test image loads: %s", g_image.info.problem);
    memcpy(&g_6581, &g_image, sizeof g_image);
    g_6581.bytes[13] = 0;
    EXPECT(sm_load_image(&g_6581) == 0, "6581 variant");
    memcpy(&g_x2, &g_image, sizeof g_image);
    g_x2.bytes[14] = 2;
    g_x2.bytes[15] = 11;
    EXPECT(sm_load_image(&g_x2) == 0, "x2 variant");
    EXPECT(load(&g_moved, sid_gt_moved, SID_GT_MOVED_USED) == 0, "moved fixture: %s", g_moved.info.problem);
    EXPECT(load(&g_edited, sid_gt_edited, SID_GT_EDITED_USED) == 0, "edited fixture: %s", g_edited.info.problem);
    EXPECT(load(&g_tempo, sid_gt_tempo, SID_GT_TEMPO_USED) == 0, "tempo fixture: %s", g_tempo.info.problem);
    EXPECT(load(&g_short, sid_gt_short, SID_GT_SHORT_USED) == 0, "short fixture: %s", g_short.info.problem);
    test_player_image();
    test_images();
    test_reference(dir);
    test_seek();
    test_audition(dir);
    test_mute();
    test_faults_and_speed();
    test_live_edits();
    test_ops();
    test_audio(dir);
    printf("SID: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
