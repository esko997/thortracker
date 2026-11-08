/* OPL3 engine benchmark: what the "opl3-fm" machine (Thor Tracker's player on Nuked-OPL3 1.8, blip_buf
 * resampling) costs the hosts, playing the host-test song. Developer tool for measuring on devices; built by
 * tools/audio/build_opl3_bench.sh for the host and for Android (NDK, arm64) as a plain executable.
 *
 * Usage: opl3_bench [seconds=30] [rate=48000]
 * Audio thread (per tick = 1/60 s): o3m_tick (mean, p99, p99.9, worst), split into the player, the chip at
 * 49716 Hz and the resampler; o3m_apply_image of a relocating live edit (the re-laid-out test image alternating
 * with the original before every tick). Control thread: machine creation, audition setup, seeks (12000-tick
 * fast-forward budget). "core" = share of one CPU core at realtime. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "opl3.h"
#include "opl3_machine.h"
#include "opl3_test_song.h"

static Opl3Image g_image, g_moved;
static float g_out[O3M_MAX_TICK_FRAMES * 2];

static double now_us(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e6 + t.tv_nsec / 1e3;
}

static int load(Opl3Image* image, const uint8_t* bytes, size_t used) {
    memset(image->bytes, 0, sizeof image->bytes);
    memcpy(image->bytes, bytes, used);
    if (o3_load_image(image)) {
        fprintf(stderr, "image: %s\n", image->info.problem);
        return 1;
    }
    return 0;
}

static int cmp_double(const void* a, const void* b) {
    const double x = *(const double*)a, y = *(const double*)b;
    return x < y ? -1 : x > y;
}

static void report(const char* what, double* v, int n) {
    double sum = 0;
    for (int i = 0; i < n; ++i) sum += v[i];
    qsort(v, (size_t)n, sizeof *v, cmp_double);
    const double mean = sum / n;
    printf("  %-34s mean %7.1f us  p99 %7.1f  p99.9 %7.1f  worst %7.1f  (%.1f%% core)\n", what, mean, v[n * 99 / 100],
           v[n * 999 / 1000], v[n - 1], mean / 16666.7 * 100);
}

int main(int argc, char** argv) {
    const double seconds = argc > 1 ? atof(argv[1]) : 30.0;
    const uint32_t rate = argc > 2 ? (uint32_t)atoi(argv[2]) : 48000u;
    const int ticks = (int)(seconds * 60);
    if (load(&g_image, opl3_song_image, sizeof opl3_song_image) || load(&g_moved, opl3_moved_image, sizeof opl3_moved_image))
        return 1;
    printf("opl3-fm bench: %d ticks (%.1f s of music, 9 channels, chords, 4-op) at %u Hz\n", ticks, ticks / 60.0, rate);

    double t0 = now_us();
    Opl3Machine* m = o3m_create(rate);
    printf("  create: %.2f ms (machine %zu KB)\n", (now_us() - t0) / 1000, sizeof(opl3_chip) / 1024 + sizeof(Opl3Image) / 1024);
    int mode;
    double* cost = (double*)malloc(sizeof(double) * (size_t)ticks);
    double* apply = (double*)malloc(sizeof(double) * (size_t)ticks);

    /* Audio thread: whole ticks. */
    o3m_start(m, &g_image, 0, 0, 12000, &mode);
    for (int t = 0; t < ticks; ++t) {
        t0 = now_us();
        o3m_tick(m, g_out);
        cost[t] = now_us() - t0;
    }
    report("tick (player + chip + resample)", cost, ticks);

    /* Parts: the chip alone at its native rate, the player alone. */
    static opl3_chip chip;
    OPL3_Reset(&chip, 49716);
    o3m_start(m, &g_image, 0, 0, 12000, &mode);
    const O3Player* p = o3m_player(m);
    O3Player* solo = (O3Player*)malloc(sizeof *solo);
    o3p_reset(solo, &g_image);
    for (int t = 0; t < ticks; ++t) {
        o3p_tick(solo, &g_image);
        for (int i = 0; i < solo->writes; ++i) OPL3_WriteReg(&chip, solo->write[i].reg, solo->write[i].val);
        t0 = now_us();
        int16_t s[2];
        for (int i = 0; i < 829; ++i) OPL3_Generate(&chip, s);
        cost[t] = now_us() - t0;
    }
    report("chip alone (829 samples)", cost, ticks);
    o3p_reset(solo, &g_image);
    for (int t = 0; t < ticks; ++t) {
        t0 = now_us();
        o3p_tick(solo, &g_image);
        cost[t] = now_us() - t0;
    }
    report("player alone", cost, ticks);
    (void)p;

    /* Live edits: a relocating apply before every tick. */
    o3m_start(m, &g_image, 0, 0, 12000, &mode);
    for (int t = 0; t < ticks; ++t) {
        t0 = now_us();
        o3m_apply_image(m, (t & 1) ? &g_moved : &g_image);
        apply[t] = now_us() - t0;
        o3m_tick(m, g_out);
    }
    report("apply_image (relocating, 64 KB copy)", apply, ticks);

    /* Control thread. */
    t0 = now_us();
    for (int i = 0; i < 100; ++i) o3m_audition(m, &g_image, 0, 57, 9, 0);
    printf("  audition setup: %.1f us\n", (now_us() - t0) / 100);
    t0 = now_us();
    o3m_start(m, &g_image, 3, 20, 12000, &mode);
    printf("  start 3/20 (435 ticks fast-forward + restore): %.0f us\n", now_us() - t0);
    o3p_reset(solo, &g_image);
    t0 = now_us();
    for (int t = 0; t < 12000; ++t) o3p_tick(solo, &g_image);
    printf("  12000-tick fast-forward (seekTicks): %.1f ms\n", (now_us() - t0) / 1000);
    free(solo);
    free(cost);
    free(apply);
    o3m_destroy(m);
    return 0;
}
