/* SID engine benchmark: cost per tick and realtime factor of the "c64-sid" machine (GoatTracker 2
 * playroutine on m6502 + reSID 1.0) playing the host-test song, for both chip models and both reSID
 * sampling methods, plus the silent (seek) cost. Developer tool for measuring on devices; built by
 * tools/audio/build_sid_bench.sh for the host and for Android (NDK, arm64) as a plain executable.
 *
 * Usage: sid_bench [seconds=30] [rate=48000]
 * One tick = one 50.1245 Hz player call (19656 C64 cycles); "core" = share of one CPU core at realtime. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "sid_gt_song.h"
#include "sid_machine.h"

static SidImage g_image;
static float g_out[SM_MAX_TICK_FRAMES * 2];

static double now_us(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e6 + t.tv_nsec / 1e3;
}

int main(int argc, char** argv) {
    const double seconds = argc > 1 ? atof(argv[1]) : 30.0;
    const uint32_t rate = argc > 2 ? (uint32_t)atoi(argv[2]) : 48000u;
    const double tick_hz = (double)SM_CPU_HZ / SID_PAL_FRAME_CYCLES;
    const int ticks = (int)(seconds * tick_hz);
    memcpy(g_image.bytes, sid_gt_image, SID_GT_IMAGE_USED);
    if (sm_load_image(&g_image)) {
        fprintf(stderr, "image: %s\n", g_image.info.problem);
        return 1;
    }
    printf("c64-sid bench: %d ticks (%.1f s of music) at %u Hz\n", ticks, ticks / tick_hz, rate);
    const char* models[2] = {"6581", "8580"};
    const char* methods[2] = {"resample", "interpolate"};
    for (int method = 0; method < 2; ++method) {
        for (int model = 0; model < 2; ++model) {
            g_image.bytes[13] = (uint8_t)model;
            sm_load_image(&g_image);
            const double c0 = now_us();
            SidMachine* m = sm_create_sampling(rate, method);
            const double create_us = now_us() - c0;
            if (!m) {
                fprintf(stderr, "sm_create failed\n");
                return 1;
            }
            int mode;
            sm_start(m, &g_image, 0, 0, 100, &mode);
            double worst = 0;
            const double t0 = now_us();
            for (int t = 0; t < ticks; ++t) {
                const double a = now_us();
                if (sm_tick(m, g_out) <= 0) {
                    fprintf(stderr, "tick failed\n");
                    return 1;
                }
                const double d = now_us() - a;
                if (d > worst) worst = d;
            }
            const double us = (now_us() - t0) / ticks;
            printf("  %-11s %s: %7.1f us per tick (worst %7.1f), %6.1fx realtime, %5.2f%% of a core, create %.0f us\n",
                   methods[method], models[model], us, worst, 1e6 / tick_hz / us, us * tick_hz / 1e4, create_us);
            sm_destroy(m);
        }
    }
    /* Seeks: the player runs every tick; the SID is clocked only for the last ~3 s (settle window). */
    SidMachine* m = sm_create(rate);
    int mode;
    double t0 = now_us();
    sm_start(m, &g_image, 0, 1, 12000, &mode);
    const double near_ms = (now_us() - t0) / 1000;
    const uint32_t near_ticks = sm_ticks(m);
    t0 = now_us();
    sm_start(m, &g_image, 3, 23, 12000, &mode);
    const double far_ms = (now_us() - t0) / 1000;
    const uint32_t far_ticks = sm_ticks(m);
    double clocked_us = 0;
    sm_start(m, &g_image, 0, 0, 100, &mode);
    t0 = now_us();
    for (int t = 0; t < 1000; ++t) sm_tick_silent(m);
    clocked_us = (now_us() - t0) / 1000;
    const double per_tick = (far_ms - 150 * clocked_us / 1000) * 1000 / (far_ticks - 150);
    printf("  seek 0/1 (%u ticks): %.2f ms; seek 3/23 (%u ticks): %.2f ms; clocked silent tick %.1f us\n", near_ticks, near_ms,
           far_ticks, far_ms, clocked_us);
    printf("  seek cost ~%.1f us per tick beyond the 150-tick settle window: 12000 ticks (~4 min) ~%.0f ms\n", per_tick,
           (150 * clocked_us + 11850 * per_tick) / 1000);
    sm_destroy(m);
    return 0;
}
