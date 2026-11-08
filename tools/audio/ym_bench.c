/* YM2612 engine benchmark: what the "ym2612-fm" machine (Thor Tracker's player -> XGC frames -> SGDK's XGM driver on
 * z80.h -> ymfm YM2612 + PSG -> blip_buf) costs the hosts, playing the host-test song. Developer tool for measuring
 * on devices; built by tools/audio/build_ym_bench.sh for the host and for Android (NDK, arm64) as a static executable.
 *
 * Usage: ym_bench [seconds=30] [rate=48000] [paced_seconds=10]
 * Audio thread (per tick = one NTSC vertical interrupt, 59.92 Hz):
 * - cold: the first 60 ticks of the process, before any warm-up (CPU frequency ramp, migration to a big core,
 *   first-touch page faults): what the first ticks of a run measure, not the engine;
 * - warm, back to back: ymm_tick after 2 s of warm-up, each tick followed by the YM2612 core alone (a second ymfm fed
 *   the driver's writes of that tick, same sample count) so both see the same CPU state; the rest (tick - core) is
 *   the Z80 running the driver, the PSG, the player, the XGC encoder and the resampler. Wall and thread CPU time,
 *   the slowest ticks by index, and the count above 3x the mean;
 * - paced: one tick per 1/59.92 s as an audio callback runs (the CPU idles between ticks, so its clock may drop);
 * - the player and the XGC encoder alone, and ymm_apply_image of a relocating live edit before every tick.
 * Control thread: machine creation, audition setup, seeks (12000-tick budget, 60-tick pre-roll through the driver).
 * "core" = share of one CPU core at realtime. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "ym_machine.h"
#include "ym_test_song.h"
#include "ym_xgm.h"
#include "ym_ymfm.h"

#define TICK_US (1e6 * 3420.0 * 262.0 / 53693175.0)

static YmImage g_image, g_moved;
static float g_out[YMM_MAX_TICK_FRAMES * 2];

static double clock_us(clockid_t id) {
    struct timespec t;
    clock_gettime(id, &t);
    return t.tv_sec * 1e6 + t.tv_nsec / 1e3;
}
static double now_us(void) { return clock_us(CLOCK_MONOTONIC); }
static double cpu_us(void) { return clock_us(CLOCK_THREAD_CPUTIME_ID); }

static int load(YmImage* image, const uint8_t* bytes, size_t used) {
    memset(image->bytes, 0, sizeof image->bytes);
    memcpy(image->bytes, bytes, used);
    if (ym_load_image(image)) {
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
    printf("  %-38s mean %7.1f us  p99 %7.1f  p99.9 %7.1f  worst %7.1f  (%.2fx; %d > 3x mean; %.1f%% core)\n", what, mean,
           s[n * 99 / 100], s[n * 999 / 1000], s[n - 1], s[n - 1] / mean, over, mean / TICK_US * 100);
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
    if (load(&g_image, ym_song_image, sizeof ym_song_image) || load(&g_moved, ym_moved_image, sizeof ym_moved_image)) return 1;
    printf("ym2612-fm bench: %d ticks (%.1f s of music: 6 FM + 3 PSG + noise through the XGM driver) at %u Hz\n", ticks,
           ticks * TICK_US / 1e6, rate);

    double t0 = now_us();
    YmMachine* m = ymm_create(rate);
    printf("  create: %.2f ms\n", (now_us() - t0) / 1000);
    int mode;
    const int n_max = ticks > paced ? ticks : paced;
    double* wall = (double*)malloc(sizeof(double) * (size_t)n_max);
    double* cpu = (double*)malloc(sizeof(double) * (size_t)n_max);
    double* core_wall = (double*)malloc(sizeof(double) * (size_t)n_max);
    double* rest = (double*)malloc(sizeof(double) * (size_t)n_max);

    /* Cold: the first ticks of the process. */
    ymm_start(m, &g_image, 0, 0, 12000, &mode);
    for (int t = 0; t < cold; ++t) {
        const double w = now_us(), c = cpu_us();
        ymm_tick(m, g_out);
        cpu[t] = cpu_us() - c;
        wall[t] = now_us() - w;
    }
    report("cold: first 60 ticks (wall)", wall, cold);
    slowest("cold ticks", wall, cpu, "cpu", cold, 5);

    /* Warm-up, then back to back with the YM2612 core alone interleaved (fed the driver's writes). */
    ymm_start(m, &g_image, 0, 0, 12000, &mode);
    for (int t = 0; t < 120; ++t) ymm_tick(m, g_out);
    ymm_start(m, &g_image, 0, 0, 12000, &mode);
    ymm_set_log(m, 1);
    YmOpn2* chip = ymo_create();
    uint32_t acc = 0;
    for (int t = 0; t < ticks; ++t) {
        double w = now_us(), c = cpu_us();
        ymm_tick(m, g_out);
        cpu[t] = cpu_us() - c;
        wall[t] = now_us() - w;
        int nlog;
        const YmmLogEntry* log = ymm_log(m, &nlog);
        const uint32_t cycles = ymm_z80_cycles_per_tick(m);
        acc += cycles * 15;
        const int samples = (int)(acc / 1008);
        acc -= (uint32_t)samples * 1008;
        w = now_us();
        int at = 0;
        for (int s = 0; s < samples; ++s) {
            const uint32_t until = (uint32_t)((uint64_t)(s + 1) * cycles / (uint32_t)samples);
            for (; at < nlog && log[at].cycle < until; ++at) {
                if (log[at].kind == YM_W_PSG) continue;
                ymo_write(chip, 0, log[at].kind * 2, log[at].reg);
                ymo_write(chip, 0, log[at].kind * 2 + 1, log[at].val);
            }
            int32_t l, r;
            ymo_generate(chip, 0, &l, &r);
        }
        core_wall[t] = now_us() - w;
        rest[t] = wall[t] - core_wall[t];
    }
    ymm_set_log(m, 0);
    report("tick (whole machine)", wall, ticks);
    report("tick, thread CPU time", cpu, ticks);
    report("YM2612 core alone (ymfm, 889 smp)", core_wall, ticks);
    report("rest: Z80 + driver, PSG, player, XGC", rest, ticks);
    slowest("ticks", wall, core_wall, "core", ticks, 5);
    ymo_destroy(chip);

    /* Paced like an audio callback. */
    ymm_start(m, &g_image, 0, 0, 12000, &mode);
    struct timespec next;
    clock_gettime(CLOCK_MONOTONIC, &next);
    for (int t = 0; t < paced; ++t) {
        next.tv_nsec += (long)(TICK_US * 1000);
        if (next.tv_nsec >= 1000000000) {
            next.tv_nsec -= 1000000000;
            ++next.tv_sec;
        }
        clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &next, NULL);
        const double w = now_us(), c = cpu_us();
        ymm_tick(m, g_out);
        cpu[t] = cpu_us() - c;
        wall[t] = now_us() - w;
    }
    report("paced, one tick per 1/59.92 s (wall)", wall, paced);
    report("paced, thread CPU time", cpu, paced);
    slowest("paced ticks", wall, cpu, "cpu", paced, 5);

    /* The player and the XGC encoder alone. */
    YmPlayer* solo = (YmPlayer*)malloc(sizeof *solo);
    YmXgmEncoder* enc = (YmXgmEncoder*)malloc(sizeof *enc);
    static uint8_t xgc[YMX_MAX_FRAME_BYTES];
    ymp_reset(solo, &g_image);
    ymx_reset(enc);
    for (int t = 0; t < ticks; ++t) {
        t0 = now_us();
        ymp_tick(solo, &g_image);
        size_t n;
        ymx_frame(enc, solo->write, solo->writes, -1, NULL, xgc, sizeof xgc, &n);
        wall[t] = now_us() - t0;
    }
    report("player + XGC encoder", wall, ticks);

    /* Live edits: a relocating apply before every tick. */
    ymm_start(m, &g_image, 0, 0, 12000, &mode);
    for (int t = 0; t < ticks; ++t) {
        t0 = now_us();
        ymm_apply_image(m, (t & 1) ? &g_moved : &g_image);
        wall[t] = now_us() - t0;
        ymm_tick(m, g_out);
    }
    report("apply_image (relocating, 64 KB copy)", wall, ticks);

    /* Control thread. */
    t0 = now_us();
    for (int i = 0; i < 20; ++i) ymm_audition(m, &g_image, 0, 57, 1, 0);
    printf("  audition setup (driver boot + first frame): %.2f ms\n", (now_us() - t0) / 20 / 1000);
    t0 = now_us();
    ymm_start(m, &g_image, 0, 5, 12000, &mode);
    printf("  start 0/5 (30 ticks, all through the driver): %.1f ms\n", (now_us() - t0) / 1000);
    t0 = now_us();
    ymm_start(m, &g_image, 3, 20, 12000, &mode);
    printf("  start 3/20 (443 ticks: 383 player alone + restore + 60 through the driver): %.1f ms\n", (now_us() - t0) / 1000);
    ymp_reset(solo, &g_image);
    ymx_reset(enc);
    t0 = now_us();
    for (int t = 0; t < 12000; ++t) {
        ymp_tick(solo, &g_image);
        ymx_absorb(enc, solo->write, solo->writes);
    }
    printf("  12000-tick fast-forward (seekTicks, player + encoder state): %.1f ms\n", (now_us() - t0) / 1000);
    free(solo);
    free(enc);
    free(wall);
    free(cpu);
    free(core_wall);
    free(rest);
    ymm_destroy(m);
    return 0;
}
