/* OPL3 engine benchmark: what the "opl3-fm" machine (Thor Tracker's player on Nuked-OPL3 1.8, blip_buf
 * resampling) costs the hosts, playing the host-test song. Developer tool for measuring on devices; built by
 * tools/audio/build_opl3_bench.sh for the host and for Android (NDK, arm64) as a plain executable.
 *
 * Usage: opl3_bench [seconds=30] [rate=48000] [paced_seconds=10]
 * Audio thread (per tick = 1/60 s):
 * - cold: the first 60 ticks of the process, before any warm-up (CPU frequency ramp, migration to a big core,
 *   first-touch page faults): what the first ticks of a run measure, not the engine;
 * - warm, back to back: o3m_tick after 2 s of warm-up, each tick followed by the chip alone (a second Nuked-OPL3
 *   fed the same writes, same sample count) so both see the same CPU state; the overhead (tick - chip) is the
 *   player, the key-off split, the mute routing and the resampler. Wall and thread CPU time (CPU time excludes
 *   preemption by other processes), the slowest ticks by index, and the count above 3x the mean;
 * - paced: one tick per 1/60 s as an audio callback runs (the CPU idles between ticks, so its clock may drop);
 * - the player alone, and o3m_apply_image of a relocating live edit before every tick.
 * Control thread: machine creation, audition setup, seeks (12000-tick budget, 1 s clocked pre-roll).
 * "core" = share of one CPU core at realtime. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "opl3.h"
#include "opl3_machine.h"
#include "opl3_test_song.h"

static Opl3Image g_image, g_moved;
static float g_out[O3M_MAX_TICK_FRAMES * 2];

static double clock_us(clockid_t id) {
    struct timespec t;
    clock_gettime(id, &t);
    return t.tv_sec * 1e6 + t.tv_nsec / 1e3;
}
static double now_us(void) { return clock_us(CLOCK_MONOTONIC); }
static double cpu_us(void) { return clock_us(CLOCK_THREAD_CPUTIME_ID); }

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

/* Stats of v[0..n) (v is left unchanged). Returns the mean. */
static double report(const char* what, const double* v, int n) {
    double* s = (double*)malloc(sizeof(double) * (size_t)n);
    double sum = 0;
    for (int i = 0; i < n; ++i) sum += s[i] = v[i];
    qsort(s, (size_t)n, sizeof *s, cmp_double);
    const double mean = sum / n;
    int over = 0;
    for (int i = 0; i < n; ++i) over += v[i] > 3 * mean;
    printf("  %-34s mean %7.1f us  p99 %7.1f  p99.9 %7.1f  worst %7.1f  (%.2fx; %d > 3x mean; %.1f%% core)\n", what, mean,
           s[n * 99 / 100], s[n * 999 / 1000], s[n - 1], s[n - 1] / mean, over, mean / 16666.7 * 100);
    free(s);
    return mean;
}

/* The k slowest entries of v by index, with the companion column c at the same ticks. */
static void slowest(const char* what, const double* v, const double* c, const char* c_name, int n, int k) {
    printf("    slowest %s:", what);
    char* taken = (char*)calloc((size_t)n, 1);
    for (int j = 0; j < k && j < n; ++j) {
        int best = -1;
        for (int i = 0; i < n; ++i)
            if (!taken[i] && (best < 0 || v[i] > v[best])) best = i;
        taken[best] = 1;
        printf(" #%d %.0f", best, v[best]);
        if (c) printf(" (%s %.0f)", c_name, c[best]);
    }
    printf(" us\n");
    free(taken);
}

int main(int argc, char** argv) {
    const double seconds = argc > 1 ? atof(argv[1]) : 30.0;
    const uint32_t rate = argc > 2 ? (uint32_t)atoi(argv[2]) : 48000u;
    const double paced_seconds = argc > 3 ? atof(argv[3]) : 10.0;
    const int ticks = (int)(seconds * 60), paced = (int)(paced_seconds * 60), cold = 60;
    if (load(&g_image, opl3_song_image, sizeof opl3_song_image) || load(&g_moved, opl3_moved_image, sizeof opl3_moved_image))
        return 1;
    printf("opl3-fm bench: %d ticks (%.1f s of music, 9 channels, chords, 4-op) at %u Hz\n", ticks, ticks / 60.0, rate);

    double t0 = now_us();
    Opl3Machine* m = o3m_create(rate);
    printf("  create: %.2f ms (machine %zu KB)\n", (now_us() - t0) / 1000, sizeof(opl3_chip) / 1024 + sizeof(Opl3Image) / 1024);
    int mode;
    const int n_max = ticks > paced ? ticks : paced;
    double* wall = (double*)malloc(sizeof(double) * (size_t)n_max);
    double* cpu = (double*)malloc(sizeof(double) * (size_t)n_max);
    double* chip_wall = (double*)malloc(sizeof(double) * (size_t)n_max);
    double* overhead = (double*)malloc(sizeof(double) * (size_t)n_max);
    static opl3_chip chip;

    /* Cold: the first ticks of the process. */
    o3m_start(m, &g_image, 0, 0, 12000, &mode);
    for (int t = 0; t < cold; ++t) {
        const double w = now_us(), c = cpu_us();
        o3m_tick(m, g_out);
        cpu[t] = cpu_us() - c;
        wall[t] = now_us() - w;
    }
    report("cold: first 60 ticks (wall)", wall, cold);
    slowest("cold ticks", wall, cpu, "cpu", cold, 5);

    /* Warm-up, then back to back with the chip alone interleaved. */
    o3m_start(m, &g_image, 0, 0, 12000, &mode);
    for (int t = 0; t < 120; ++t) o3m_tick(m, g_out);
    o3m_start(m, &g_image, 0, 0, 12000, &mode);
    OPL3_Reset(&chip, 49716);
    const O3Player* p = o3m_player(m);
    for (int i = 0; i < p->writes; ++i) OPL3_WriteReg(&chip, p->write[i].reg, p->write[i].val);
    for (int t = 0; t < ticks; ++t) {
        double w = now_us(), c = cpu_us();
        o3m_tick(m, g_out);
        cpu[t] = cpu_us() - c;
        wall[t] = now_us() - w;
        for (int i = 0; i < p->writes; ++i) OPL3_WriteReg(&chip, p->write[i].reg, p->write[i].val);
        const int n = o3m_native_samples(m);
        w = now_us();
        int16_t s[2];
        for (int i = 0; i < n; ++i) OPL3_Generate(&chip, s);
        chip_wall[t] = now_us() - w;
        overhead[t] = wall[t] - chip_wall[t];
    }
    report("tick (player + chip + resample)", wall, ticks);
    report("tick, thread CPU time", cpu, ticks);
    report("chip alone, same writes (829 smp)", chip_wall, ticks);
    report("overhead (tick - chip alone)", overhead, ticks);
    slowest("ticks", wall, chip_wall, "chip", ticks, 5);

    /* Paced like an audio callback. */
    o3m_start(m, &g_image, 0, 0, 12000, &mode);
    struct timespec next;
    clock_gettime(CLOCK_MONOTONIC, &next);
    for (int t = 0; t < paced; ++t) {
        next.tv_nsec += 16666667;
        if (next.tv_nsec >= 1000000000) {
            next.tv_nsec -= 1000000000;
            ++next.tv_sec;
        }
        clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &next, NULL);
        const double w = now_us(), c = cpu_us();
        o3m_tick(m, g_out);
        cpu[t] = cpu_us() - c;
        wall[t] = now_us() - w;
    }
    report("paced, one tick per 1/60 s (wall)", wall, paced);
    report("paced, thread CPU time", cpu, paced);
    slowest("paced ticks", wall, cpu, "cpu", paced, 5);

    /* The player alone. */
    O3Player* solo = (O3Player*)malloc(sizeof *solo);
    o3p_reset(solo, &g_image);
    for (int t = 0; t < ticks; ++t) {
        t0 = now_us();
        o3p_tick(solo, &g_image);
        wall[t] = now_us() - t0;
    }
    report("player alone", wall, ticks);

    /* Live edits: a relocating apply before every tick. */
    o3m_start(m, &g_image, 0, 0, 12000, &mode);
    for (int t = 0; t < ticks; ++t) {
        t0 = now_us();
        o3m_apply_image(m, (t & 1) ? &g_moved : &g_image);
        wall[t] = now_us() - t0;
        o3m_tick(m, g_out);
    }
    report("apply_image (relocating, 64 KB copy)", wall, ticks);

    /* Control thread. */
    t0 = now_us();
    for (int i = 0; i < 100; ++i) o3m_audition(m, &g_image, 0, 57, 9, 0);
    printf("  audition setup: %.1f us\n", (now_us() - t0) / 100);
    t0 = now_us();
    o3m_start(m, &g_image, 0, 5, 12000, &mode);
    printf("  start 0/5 (30 ticks, all clocked): %.1f ms\n", (now_us() - t0) / 1000);
    t0 = now_us();
    o3m_start(m, &g_image, 3, 20, 12000, &mode);
    printf("  start 3/20 (435 ticks: 375 player alone + restore + 60 clocked): %.1f ms\n", (now_us() - t0) / 1000);
    o3p_reset(solo, &g_image);
    t0 = now_us();
    for (int t = 0; t < 12000; ++t) o3p_tick(solo, &g_image);
    printf("  12000-tick fast-forward (seekTicks, player alone): %.1f ms\n", (now_us() - t0) / 1000);
    free(solo);
    free(wall);
    free(cpu);
    free(chip_wall);
    free(overhead);
    o3m_destroy(m);
    return 0;
}
