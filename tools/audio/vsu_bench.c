/* Virtual Boy VSU engine benchmark: what the "vb-vsu" machine (Thor Tracker's player on the VSU core, the RC
 * high-pass and blip_buf resampling) costs the hosts, playing the host-test song (app/src/test/cpp/vsu_test_song.h).
 * Developer tool for measuring on devices; built by tools/audio/build_vsu_bench.sh for the host and for Android (NDK,
 * arm64) as a plain executable.
 *
 * Usage: vsu_bench [seconds=30] [rate=48000] [paced_seconds=10]
 * Audio thread (per tick = one 50 Hz frame, 20 ms): cold (the first 50 ticks), warm back to back (wall and thread CPU
 * time, the slowest ticks), paced (one tick per 20 ms as an audio callback runs), the chip alone for a tick (100000
 * clocks without sampling: what a seek pays per tick), and vbm_apply_image of a relocating live edit before every tick.
 * Control thread: machine creation, audition setup, seeks (every tick fully clocked), 12000 clocked ticks (seekTicks).
 * "core" = share of one CPU core at realtime. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "vsu_machine.h"
#include "vsu_test_song.h"

#define FRAME_US 20000.0

static VsuImage g_image, g_moved;
static float g_out[VBM_MAX_TICK_FRAMES * 2];

static double clock_us(clockid_t id) {
    struct timespec t;
    clock_gettime(id, &t);
    return t.tv_sec * 1e6 + t.tv_nsec / 1e3;
}
static double now_us(void) { return clock_us(CLOCK_MONOTONIC); }
static double cpu_us(void) { return clock_us(CLOCK_THREAD_CPUTIME_ID); }

static int load(VsuImage* image, int relayout) {
    const VbSongOptions opt = {.relayout = relayout};
    vb_build_test_song(image->bytes, &opt);
    if (vb_load_image(image)) {
        fprintf(stderr, "image: %s\n", image->info.problem);
        return 1;
    }
    return 0;
}

static int cmp_double(const void* a, const void* b) {
    const double x = *(const double*)a, y = *(const double*)b;
    return x < y ? -1 : x > y;
}

static void report(const char* what, const double* v, int n) {
    double* s = (double*)malloc(sizeof(double) * (size_t)n);
    double sum = 0;
    for (int i = 0; i < n; ++i) sum += s[i] = v[i];
    qsort(s, (size_t)n, sizeof *s, cmp_double);
    const double mean = sum / n;
    int over = 0;
    for (int i = 0; i < n; ++i) over += v[i] > 3 * mean;
    printf("  %-34s mean %7.1f us  p99 %7.1f  worst %7.1f  (%d > 3x mean; %.2f%% core)\n", what, mean, s[n * 99 / 100],
           s[n - 1], over, mean / FRAME_US * 100);
    free(s);
}

static void sleep_until(double t_us) {
    for (;;) {
        const double left = t_us - now_us();
        if (left <= 0) return;
        struct timespec ts = {(time_t)(left / 1e6), (long)((left - (time_t)(left / 1e6) * 1e6) * 1e3)};
        nanosleep(&ts, NULL);
    }
}

int main(int argc, char** argv) {
    const double seconds = argc > 1 ? atof(argv[1]) : 30;
    const int rate = argc > 2 ? atoi(argv[2]) : 48000;
    const double paced = argc > 3 ? atof(argv[3]) : 10;
    if (load(&g_image, 0) || load(&g_moved, 1)) return 1;
    const int ticks = (int)(seconds * 50);
    double* wall = (double*)malloc(sizeof(double) * (size_t)(ticks + 100));
    double* cpu = (double*)malloc(sizeof(double) * (size_t)(ticks + 100));
    printf("vb-vsu bench: %.0f s of the test song at %d Hz\n", seconds, rate);

    double t0 = now_us();
    VsuMachine* m = vbm_create((uint32_t)rate);
    printf("  machine creation                   %.1f us (%zu bytes of image + player + chip)\n", now_us() - t0,
           sizeof(VsuImage) + sizeof(VbPlayer) + sizeof(VsuChip));
    int mode;
    vbm_start(m, &g_image, 0, 0, 12000, &mode);
    for (int k = 0; k < 50; ++k) {
        const double w = now_us();
        vbm_tick(m, g_out);
        wall[k] = now_us() - w;
    }
    report("cold (first 50 ticks)", wall, 50);
    for (int k = 0; k < 100; ++k) vbm_tick(m, g_out);
    for (int k = 0; k < ticks; ++k) {
        const double w = now_us(), c = cpu_us();
        vbm_tick(m, g_out);
        cpu[k] = cpu_us() - c;
        wall[k] = now_us() - w;
    }
    report("warm tick (wall)", wall, ticks);
    report("warm tick (thread CPU)", cpu, ticks);

    {
        static VsuChip chip;
        chip = *vbm_chip(m);
        for (int k = 0; k < ticks; ++k) {
            const double c = cpu_us();
            vsu_run(&chip, VBP_TICK_CLOCKS);
            cpu[k] = cpu_us() - c;
        }
        report("chip alone, 100000 clocks", cpu, ticks);
    }

    vbm_start(m, &g_image, 0, 0, 12000, &mode);
    for (int k = 0; k < ticks; ++k) {
        const double c = cpu_us();
        vbm_apply_image(m, (k & 1) ? &g_moved : &g_image);
        cpu[k] = cpu_us() - c;
        vbm_tick(m, g_out);
    }
    report("apply_image (relocating)", cpu, ticks);

    const int pticks = (int)(paced * 50);
    vbm_start(m, &g_image, 0, 0, 12000, &mode);
    double next = now_us();
    for (int k = 0; k < pticks; ++k) {
        sleep_until(next);
        next += FRAME_US;
        const double w = now_us();
        vbm_tick(m, g_out);
        wall[k] = now_us() - w;
    }
    report("paced tick (wall)", wall, pticks);

    t0 = now_us();
    for (int k = 0; k < 100; ++k) vbm_audition(m, &g_image, 0, 57, VBT_LEAD, 0);
    printf("  audition setup                     %.1f us\n", (now_us() - t0) / 100);
    static const int targets[][2] = {{1, 0}, {3, 20}};
    for (int i = 0; i < 2; ++i) {
        t0 = now_us();
        vbm_start(m, &g_image, targets[i][0], targets[i][1], 12000, &mode);
        printf("  seek to %d/%-2d                       %.0f us\n", targets[i][0], targets[i][1], now_us() - t0);
    }
    {
        static VbPlayer p;
        static VsuChip chip;
        vbp_reset(&p, &g_image);
        vsu_reset(&chip);
        t0 = now_us();
        for (int k = 0; k < 12000; ++k) {
            vbp_tick(&p, &g_image);
            for (int i = 0; i < p.writes; ++i) vsu_write(&chip, p.write[i].reg, p.write[i].val);
            vsu_run(&chip, VBP_TICK_CLOCKS);
        }
        printf("  12000 fully clocked ticks          %.1f ms (%.2f us per tick)\n", (now_us() - t0) / 1000,
               (now_us() - t0) / 12000);
    }
    vbm_destroy(m);
    free(wall);
    free(cpu);
    return 0;
}
