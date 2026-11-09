/* YM2612 engine benchmark: what the "ym2612-fm" machine (Thor Tracker's player -> XGM2 FM/PSG frames -> SGDK's XGM2
 * driver on z80.h, mixing PCM into the DAC -> ymfm YM2612 + PSG -> blip_buf) costs the hosts, playing the host test's
 * songs (ym_test_song.h). Developer tool for measuring on devices; built by tools/audio/build_ym_bench.sh for the host
 * and for Android (NDK, arm64) as a static executable.
 *
 * Usage: ym_bench [seconds=30] [rate=48000] [paced_seconds=10]
 * Audio thread (per tick = one vertical interrupt), for the tonal test song and the PCM song (FM 3 special, three PCM
 * channels through the driver's software mix):
 * - cold: the first 60 ticks of the process, before any warm-up (CPU frequency ramp, migration to a big core,
 *   first-touch page faults): what the first ticks of a run measure, not the engine;
 * - warm, back to back: ymm_tick after 2 s of warm-up, each tick followed by the YM2612 core alone (a second ymfm fed
 *   the driver's writes of that tick, same sample count) so both see the same CPU state; the rest (tick - core) is
 *   the Z80 running the driver (its PCM mix included), the PSG, the player, the XGM2 encoder/packer and the
 *   resampler. Wall and thread CPU time, the slowest ticks by index, and the count above 3x the mean;
 * - paced: one tick per tick period as an audio callback runs (the CPU idles between ticks, so its clock may drop);
 * - the player and the XGM2 encoder + packer alone;
 * - ymm_apply_image before every tick: an unchanged PCM bank (the same image) and a changed one (the PCM song and its
 *   re-laid-out variant alternately: a bank swap at the driver's next fill boundary).
 * Control thread: machine creation, audition setup, seeks (ymm_start to a far row: the whole machine runs from the
 * song start, fully clocked), the VGM and XGC exports. "core" = share of one CPU core at realtime. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "ym_machine.h"
#include "ym_test_song.h"
#include "ym_vgm.h"
#include "ym_xgm.h"
#include "ym_ymfm.h"

static YmImage g_song, g_pcm, g_pcm_moved;
static float g_out[YMM_MAX_TICK_FRAMES * 2];
static double g_tick_us; /* the song being measured: one tick's period */

static double clock_us(clockid_t id) {
    struct timespec t;
    clock_gettime(id, &t);
    return t.tv_sec * 1e6 + t.tv_nsec / 1e3;
}
static double now_us(void) { return clock_us(CLOCK_MONOTONIC); }
static double cpu_us(void) { return clock_us(CLOCK_THREAD_CPUTIME_ID); }

/* Images: zero-fill, copy the fixture's non-zero runs, load. */
static int load(YmImage* image, const unsigned* runs, int n, const unsigned char* bytes, const char* name) {
    memset(image->bytes, 0, sizeof image->bytes);
    size_t at = 0;
    for (int i = 0; i < n; ++i) {
        memcpy(image->bytes + runs[2 * i], bytes + at, runs[2 * i + 1]);
        at += runs[2 * i + 1];
    }
    if (ym_load_image(image)) {
        fprintf(stderr, "%s: %s\n", name, image->info.problem);
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
    printf("  %-40s mean %7.1f us  p99 %7.1f  p99.9 %7.1f  worst %7.1f  (%.2fx; %d > 3x mean; %.1f%% core)\n", what, mean,
           s[n * 99 / 100], s[n * 999 / 1000], s[n - 1], s[n - 1] / mean, over, mean / g_tick_us * 100);
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

/* The audio thread's costs for one song. */
static void bench_song(YmMachine* m, const YmImage* image, const char* name, int ticks, int paced, double* wall, double* cpu,
                       double* core_wall, double* rest) {
    const int cold = 60;
    const uint32_t mclk_per_tick = YMM_MCLK_PER_LINE * (image->info.region == YM_PAL ? YMM_LINES_PAL : YMM_LINES_NTSC);
    g_tick_us = 1e6 * mclk_per_tick / (image->info.region == YM_PAL ? YMM_MCLK_PAL : YMM_MCLK_NTSC);
    printf("%s: %d ticks (%.1f s of music) at %.2f Hz\n", name, ticks, ticks * g_tick_us / 1e6, 1e6 / g_tick_us);
    int mode;

    /* Cold: the first ticks of this song's run. */
    ymm_start(m, image, 0, 0, 12000, &mode);
    for (int t = 0; t < cold; ++t) {
        const double w = now_us(), c = cpu_us();
        ymm_tick(m, g_out);
        cpu[t] = cpu_us() - c;
        wall[t] = now_us() - w;
    }
    report("cold: first 60 ticks (wall)", wall, cold);
    slowest("cold ticks", wall, cpu, "cpu", cold, 5);

    /* Warm-up, then back to back with the YM2612 core alone interleaved (fed the driver's writes). */
    ymm_start(m, image, 0, 0, 12000, &mode);
    for (int t = 0; t < 120; ++t) ymm_tick(m, g_out);
    ymm_start(m, image, 0, 0, 12000, &mode);
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
        acc += cycles * YMM_MCLK_PER_Z80;
        const int samples = (int)(acc / YMM_MCLK_PER_YM_SAMPLE);
        acc -= (uint32_t)samples * YMM_MCLK_PER_YM_SAMPLE;
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
    report("YM2612 core alone (ymfm)", core_wall, ticks);
    report("rest: Z80 + driver/PCM mix, PSG, player", rest, ticks);
    slowest("ticks", wall, core_wall, "core", ticks, 5);
    ymo_destroy(chip);

    /* Paced like an audio callback. */
    ymm_start(m, image, 0, 0, 12000, &mode);
    struct timespec next;
    clock_gettime(CLOCK_MONOTONIC, &next);
    for (int t = 0; t < paced; ++t) {
        next.tv_nsec += (long)(g_tick_us * 1000);
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
    report("paced, one tick per period (wall)", wall, paced);
    report("paced, thread CPU time", cpu, paced);
    slowest("paced ticks", wall, cpu, "cpu", paced, 5);

    /* The player and the XGM2 encoder + packer alone (as the machine encodes each tick). */
    YmPlayer* solo = (YmPlayer*)malloc(sizeof *solo);
    static YmxFrame frame;
    static uint8_t cmds[YMX_FRAME_MAX], packed[YMX_PACKED_MAX];
    ymp_reset(solo, image);
    uint32_t pos = 0;
    for (int t = 0; t < ticks; ++t) {
        const double t0 = now_us();
        ymp_tick(solo, image);
        ymx_encode(image, solo, solo->write, solo->writes, solo->pcm, t == 0, &frame);
        memcpy(cmds, frame.fm, (size_t)frame.fm_size);
        int n = frame.fm_size + ymx_wait(cmds + frame.fm_size, 0, 1);
        pos = (pos + (uint32_t)ymx_pack(cmds, n, pos, packed)) & 0x7FFF;
        memcpy(cmds, frame.psg, (size_t)frame.psg_size);
        n = frame.psg_size + ymx_wait(cmds + frame.psg_size, 1, 1);
        ymx_pack(cmds, n, pos, packed);
        wall[t] = now_us() - t0;
    }
    report("player + XGM2 encoder + packer", wall, ticks);
    free(solo);
}

int main(int argc, char** argv) {
    const double seconds = argc > 1 ? atof(argv[1]) : 30.0;
    const uint32_t rate = argc > 2 ? (uint32_t)atoi(argv[2]) : 48000u;
    const double paced_seconds = argc > 3 ? atof(argv[3]) : 10.0;
    const int ticks = (int)(seconds * 60), paced = (int)(paced_seconds * 60);
    if (load(&g_song, ym_song_runs, YM_SONG_RUNS, ym_song_bytes, "song") ||
        load(&g_pcm, ym_pcm_runs, YM_PCM_RUNS, ym_pcm_bytes, "pcm") ||
        load(&g_pcm_moved, ym_pcm_moved_runs, YM_PCM_MOVED_RUNS, ym_pcm_moved_bytes, "pcm_moved"))
        return 1;
    printf("ym2612-fm bench at %u Hz: the XGM2 driver on z80.h, ymfm, SN76489, blip_buf\n", rate);

    double t0 = now_us();
    YmMachine* m = ymm_create(rate);
    printf("  create: %.2f ms\n", (now_us() - t0) / 1000);
    const int n_max = ticks > paced ? ticks : paced;
    double* wall = (double*)malloc(sizeof(double) * (size_t)n_max);
    double* cpu = (double*)malloc(sizeof(double) * (size_t)n_max);
    double* core_wall = (double*)malloc(sizeof(double) * (size_t)n_max);
    double* rest = (double*)malloc(sizeof(double) * (size_t)n_max);

    bench_song(m, &g_song, "tonal test song (6 FM + 3 PSG + noise)", ticks, paced, wall, cpu, core_wall, rest);
    bench_song(m, &g_pcm, "PCM song (FM 3 special + 3 PCM through the driver)", ticks, paced, wall, cpu, core_wall, rest);

    /* Live edits before every tick: the same bank (no swap) and a re-laid-out bank (a swap every tick). */
    int mode;
    ymm_start(m, &g_pcm, 0, 0, 12000, &mode);
    for (int t = 0; t < ticks; ++t) {
        t0 = now_us();
        ymm_apply_image(m, &g_pcm);
        wall[t] = now_us() - t0;
        ymm_tick(m, g_out);
    }
    report("apply_image, unchanged PCM bank", wall, ticks);
    ymm_start(m, &g_pcm, 0, 0, 12000, &mode);
    for (int t = 0; t < ticks; ++t) {
        t0 = now_us();
        ymm_apply_image(m, (t & 1) ? &g_pcm_moved : &g_pcm);
        wall[t] = now_us() - t0;
        ymm_tick(m, g_out);
    }
    report("apply_image, changed PCM bank (swap)", wall, ticks);

    /* Control thread. */
    t0 = now_us();
    for (int i = 0; i < 20; ++i) ymm_audition(m, &g_song, 0, 57, 1, 0);
    printf("  audition setup (driver boot + first frame): %.2f ms\n", (now_us() - t0) / 20 / 1000);
    for (int s = 0; s < 2; ++s) {
        const YmImage* image = s ? &g_pcm : &g_song;
        /* the far row: the last order's last row (the whole machine runs from the song start up to it) */
        const int order = image->info.orders - 1, row = image->info.rows[order] - 1;
        t0 = now_us();
        const int rc = ymm_start(m, image, order, row, 12000, &mode);
        const double us = now_us() - t0;
        printf("  %s: start %d/%d (%s): %.1f ms\n", s ? "PCM song" : "song", order, row,
               rc != YMM_OK ? "failed" : mode == YMM_SEEK_FROM_START ? "fully clocked from the song start" : "order fallback",
               us / 1000);
    }
    static const char* format_name[3] = {"VGM", "XGM", "XGC"};
    for (int s = 0; s < 2; ++s)
        for (int format = YM_FORMAT_VGM; format <= YM_FORMAT_XGC; format += 2) {
            const YmImage* image = s ? &g_pcm : &g_song;
            YmVgmInfo info;
            const char* problem = NULL;
            t0 = now_us();
            uint8_t* file = ym_render(image, format, 36000, &info, &problem);
            const double us = now_us() - t0;
            printf("  %s %s export: %.1f ms (%zu bytes, %u ticks%s)\n", s ? "PCM song" : "song", format_name[format], us / 1000,
                   file ? info.size : 0, info.ticks, file ? "" : ", failed");
            free(file);
        }
    free(wall);
    free(cpu);
    free(core_wall);
    free(rest);
    ymm_destroy(m);
    return 0;
}
