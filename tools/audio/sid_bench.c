/* SID engine benchmark: what the "c64-sid" machine (GoatTracker 2 playroutine on m6502 + reSID 1.0)
 * costs the hosts, playing the host-test song. Developer tool for measuring on devices; built by
 * tools/audio/build_sid_bench.sh for the host and for Android (NDK, arm64) as a plain executable.
 *
 * Usage: sid_bench [seconds=30] [rate=48000]
 * Audio thread (per tick; one tick = one 50.1245 Hz player call = 19656 C64 cycles): sm_tick for both chip
 * models and reSID sampling methods (mean and worst), and sm_apply_image of a relocating live edit (the
 * re-laid-out test image alternating with the original before every tick). Control thread (never the
 * audio callback): machine creation (the first builds reSID's tables), audition setup, seeks.
 * "core" = share of one CPU core at realtime. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "sid_gt_song.h"
#include "sid_machine.h"

static SidImage g_image, g_moved;
static float g_out[SM_MAX_TICK_FRAMES * 2];

static double now_us(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e6 + t.tv_nsec / 1e3;
}

static int load(SidImage* image, const uint8_t* bytes, size_t used) {
    memset(image->bytes, 0, sizeof image->bytes);
    memcpy(image->bytes, bytes, used);
    if (sm_load_image(image)) {
        fprintf(stderr, "image: %s\n", image->info.problem);
        return 1;
    }
    return 0;
}

static int cmp_double(const void* a, const void* b) {
    const double x = *(const double*)a, y = *(const double*)b;
    return x < y ? -1 : x > y;
}

int main(int argc, char** argv) {
    const double seconds = argc > 1 ? atof(argv[1]) : 30.0;
    const uint32_t rate = argc > 2 ? (uint32_t)atoi(argv[2]) : 48000u;
    const double tick_hz = (double)SM_CPU_HZ / SID_PAL_FRAME_CYCLES;
    const int ticks = (int)(seconds * tick_hz);
    if (load(&g_image, sid_gt_image, SID_GT_IMAGE_USED) || load(&g_moved, sid_gt_moved, SID_GT_MOVED_USED)) return 1;
    printf("c64-sid bench: %d ticks (%.1f s of music) at %u Hz\n", ticks, ticks / tick_hz, rate);

    /* Control thread: creation. The first machine in the process builds reSID's filter tables. */
    double t0 = now_us();
    SidMachine* first = sm_create(rate);
    const double create_first = now_us() - t0;
    t0 = now_us();
    SidMachine* second = sm_create(rate);
    const double create_next = now_us() - t0;
    printf("  create: first machine %.1f ms (reSID tables), next %.2f ms\n", create_first / 1000, create_next / 1000);
    sm_destroy(first);
    sm_destroy(second);

    /* Audio thread: ticks. */
    const char* models[2] = {"6581", "8580"};
    const char* methods[2] = {"resample", "interpolate"};
    double worst_tick = 0;
    double* dur = (double*)malloc(sizeof(double) * (size_t)ticks);
    for (int method = 0; method < 2; ++method) {
        for (int model = 0; model < 2; ++model) {
            g_image.bytes[13] = (uint8_t)model;
            sm_load_image(&g_image);
            SidMachine* m = sm_create_sampling(rate, method);
            if (!m || !dur) {
                fprintf(stderr, "sm_create failed\n");
                return 1;
            }
            int mode;
            sm_start(m, &g_image, 0, 0, 100, &mode);
            double worst = 0;
            int worst_at = 0;
            const double s0 = now_us();
            for (int t = 0; t < ticks; ++t) {
                const double a = now_us();
                if (sm_tick(m, g_out) <= 0) {
                    fprintf(stderr, "tick failed\n");
                    return 1;
                }
                dur[t] = now_us() - a;
                if (dur[t] > worst) {
                    worst = dur[t];
                    worst_at = t;
                }
            }
            const double us = (now_us() - s0) / ticks;
            qsort(dur, (size_t)ticks, sizeof *dur, cmp_double);
            if (method == 0 && worst > worst_tick) worst_tick = worst;
            printf("  tick %-11s %s: %7.1f us mean, p99 %7.1f, p99.9 %7.1f, worst %7.1f (tick %d); %5.1fx realtime, %5.2f%% of a core\n",
                   methods[method], models[model], us, dur[ticks * 99 / 100], dur[ticks * 999 / 1000], worst, worst_at,
                   1e6 / tick_hz / us, us * tick_hz / 1e4);
            sm_destroy(m);
        }
    }
    free(dur);
    g_image.bytes[13] = 1;
    sm_load_image(&g_image);

    /* Audio thread: relocating live edits (moved layout <-> original before every tick). */
    SidMachine* m = sm_create(rate);
    int mode;
    sm_start(m, &g_image, 0, 0, 100, &mode);
    double apply_sum = 0, apply_worst = 0;
    const int applies = ticks < 1500 ? ticks : 1500;
    for (int t = 0; t < applies; ++t) {
        const double a = now_us();
        sm_apply_image(m, t & 1 ? &g_image : &g_moved);
        const double d = now_us() - a;
        apply_sum += d;
        if (d > apply_worst) apply_worst = d;
        sm_tick(m, g_out);
    }
    printf("  apply_image (relocating, every tick): %.1f us mean, %.1f us worst\n", apply_sum / applies, apply_worst);
    printf("  audio-thread stall per tick (worst resample tick + worst apply): %.2f ms\n", (worst_tick + apply_worst) / 1000);

    /* Control thread: audition setup (power-on, init, the note's start-up ticks). */
    const int tone = g_image.bytes[SM_SLOT_MAP + SID_GT_TONE_SLOT];
    double aud_sum = 0, aud_worst = 0;
    for (int i = 0; i < 50; ++i) {
        const double a = now_us();
        sm_audition(m, &g_image, i % 3, 36 + i % 40, tone, 0);
        const double d = now_us() - a;
        aud_sum += d;
        if (d > aud_worst) aud_worst = d;
    }
    printf("  audition setup: %.2f ms mean, %.2f ms worst\n", aud_sum / 50000, aud_worst / 1000);

    /* Control thread: seeks. The player runs every tick; the SID is clocked only for the last ~3 s
     * (settle window, 150 ticks); older ticks reach it as bare writes. */
    t0 = now_us();
    sm_start(m, &g_image, 0, 1, 12000, &mode);
    const double near_ms = (now_us() - t0) / 1000;
    const uint32_t near_ticks = sm_ticks(m);
    t0 = now_us();
    sm_start(m, &g_image, 3, 23, 12000, &mode);
    const double far_ms = (now_us() - t0) / 1000;
    const uint32_t far_ticks = sm_ticks(m);
    sm_start(m, &g_image, 0, 0, 100, &mode);
    t0 = now_us();
    for (int t = 0; t < 1000; ++t) sm_tick_silent(m);
    const double clocked_us = (now_us() - t0) / 1000;
    const double per_tick = (far_ms - 150 * clocked_us / 1000) * 1000 / (far_ticks - 150);
    printf("  seek 0/1 (%u ticks): %.2f ms; seek 3/23 (%u ticks): %.2f ms; clocked silent tick %.1f us\n", near_ticks, near_ms,
           far_ticks, far_ms, clocked_us);
    printf("  seek cost ~%.1f us per tick beyond the 150-tick settle window: 12000 ticks (~4 min) ~%.0f ms "
           "(control thread; the playing machine keeps sounding)\n",
           per_tick, (150 * clocked_us + 11850 * per_tick) / 1000);
    sm_destroy(m);
    return 0;
}
