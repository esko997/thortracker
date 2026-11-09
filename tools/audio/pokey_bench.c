/* POKEY engine benchmark: what the "atari-pokey" machine (Thor Tracker's player on the own cycle-exact POKEY model,
 * Altirra's mix and output stage, blip_buf resampling) costs the hosts, playing the host-test song
 * (app/src/test/cpp/pokey_test_song.h). Developer tool for measuring on devices; built by
 * tools/audio/build_pokey_bench.sh for the host and for Android (NDK, arm64) as a plain executable.
 *
 * Usage: pokey_bench [seconds=30] [rate=48000] [paced_seconds=10]
 * Audio thread (per tick = one NTSC frame, 16.68 ms):
 * - cold: the first 60 ticks of the process, before any warm-up;
 * - warm, back to back: pkm_tick after 2 s of warm-up, each tick followed by the chip alone (a second POKEY model fed
 *   the same writes at the same cycles, interrupts included) so both see the same CPU state; the overhead (tick -
 *   chip) is the player, the mix, blip_buf and the output filter. Wall and thread CPU time, the slowest ticks by
 *   index, and the count above 3x the mean;
 * - paced: one tick per frame as an audio callback runs;
 * - the player alone, and pkm_apply_image of a relocating live edit before every tick.
 * Control thread: machine creation, audition setup, seeks (every tick fully clocked), 12000 clocked ticks (the
 * suggested seekTicks budget). "core" = share of one CPU core at realtime. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "pokey_machine.h"
#include "pokey_test_song.h"

#define FRAME_US (1e6 * PK_FRAME_CYCLES_NTSC / PK_CLOCK_NTSC)

static PokeyImage g_image, g_moved;
static float g_out[PKM_MAX_TICK_FRAMES * 2];

static double clock_us(clockid_t id) {
    struct timespec t;
    clock_gettime(id, &t);
    return t.tv_sec * 1e6 + t.tv_nsec / 1e3;
}
static double now_us(void) { return clock_us(CLOCK_MONOTONIC); }
static double cpu_us(void) { return clock_us(CLOCK_THREAD_CPUTIME_ID); }

static int load(PokeyImage* image, int relayout) {
    const PkSongOptions opt = {PK_MACHINE_NTSC, 1, relayout, 0, 0, 0};
    pk_build_test_song(image->bytes, &opt);
    if (pk_load_image(image)) {
        fprintf(stderr, "image: %s\n", image->info.problem);
        return 1;
    }
    return 0;
}

static int cmp_double(const void* a, const void* b) {
    const double x = *(const double*)a, y = *(const double*)b;
    return x < y ? -1 : x > y;
}

static double report(const char* what, const double* v, int n) {
    double* s = (double*)malloc(sizeof(double) * (size_t)n);
    double sum = 0;
    for (int i = 0; i < n; ++i) sum += s[i] = v[i];
    qsort(s, (size_t)n, sizeof *s, cmp_double);
    const double mean = sum / n;
    int over = 0;
    for (int i = 0; i < n; ++i) over += v[i] > 3 * mean;
    printf("  %-34s mean %7.1f us  p99 %7.1f  p99.9 %7.1f  worst %7.1f  (%.2fx; %d > 3x mean; %.1f%% core)\n", what, mean,
           s[n * 99 / 100], s[n * 999 / 1000], s[n - 1], s[n - 1] / mean, over, mean / FRAME_US * 100);
    free(s);
    return mean;
}

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

/* The chip alone: a second POKEY fed the machine's batches at their cycles (through the trace hook); a tick's batches
 * are queued and replayed after the tick so that only the chip's work is timed. */
typedef struct Batch {
    uint64_t cycle;
    int count;
    PkWrite writes[PKP_MAX_WRITES];
} Batch;

typedef struct Alone {
    PokeyChip chip;
    Batch* queue;
    int n, cap;
} Alone;

static void queue_batch(void* ctx, const PkmEvent* e) {
    Alone* a = (Alone*)ctx;
    if (a->n == a->cap) {
        a->cap = a->cap ? 2 * a->cap : 1024;
        a->queue = (Batch*)realloc(a->queue, sizeof(Batch) * (size_t)a->cap);
    }
    Batch* b = &a->queue[a->n++];
    b->cycle = e->cycle;
    b->count = e->count;
    memcpy(b->writes, e->writes, sizeof(PkWrite) * (size_t)e->count);
}

static void run_alone(Alone* a, uint64_t until) {
    for (int i = 0; i < a->n; ++i) {
        while (pk_run(&a->chip, a->queue[i].cycle)) pk_take_irq(&a->chip);
        for (int k = 0; k < a->queue[i].count; ++k) pk_write(&a->chip, a->queue[i].writes[k].reg, a->queue[i].writes[k].val);
    }
    while (pk_run(&a->chip, until)) pk_take_irq(&a->chip);
    a->n = 0;
}

int main(int argc, char** argv) {
    const double seconds = argc > 1 ? atof(argv[1]) : 30.0;
    const uint32_t rate = argc > 2 ? (uint32_t)atoi(argv[2]) : 48000u;
    const double paced_seconds = argc > 3 ? atof(argv[3]) : 10.0;
    const int ticks = (int)(seconds * 1e6 / FRAME_US), paced = (int)(paced_seconds * 1e6 / FRAME_US), cold = 60;
    if (load(&g_image, 0) || load(&g_moved, 1)) return 1;
    printf("atari-pokey bench: %d ticks (%.1f s of music, every sound kind, samples) at %u Hz\n", ticks, seconds, rate);

    double t0 = now_us();
    PokeyMachine* m = pkm_create(rate);
    printf("  create: %.2f ms (machine %zu KB)\n", (now_us() - t0) / 1000, (sizeof(PokeyChip) + sizeof(PokeyImage)) / 1024);
    int mode;
    const int n_max = ticks > paced ? ticks : paced;
    double* wall = (double*)malloc(sizeof(double) * (size_t)n_max);
    double* cpu = (double*)malloc(sizeof(double) * (size_t)n_max);
    double* chip_wall = (double*)malloc(sizeof(double) * (size_t)n_max);
    double* overhead = (double*)malloc(sizeof(double) * (size_t)n_max);
    static Alone alone;
    pk_init(&alone.chip, NULL, NULL);

    /* Cold: the first ticks of the process. */
    pkm_start(m, &g_image, 0, 0, 12000, &mode);
    for (int t = 0; t < cold; ++t) {
        const double w = now_us(), c = cpu_us();
        pkm_tick(m, g_out);
        cpu[t] = cpu_us() - c;
        wall[t] = now_us() - w;
    }
    report("cold: first 60 ticks (wall)", wall, cold);
    slowest("cold ticks", wall, cpu, "cpu", cold, 5);

    /* Warm-up, then back to back with the chip alone interleaved. */
    pkm_start(m, &g_image, 0, 0, 12000, &mode);
    for (int t = 0; t < 120; ++t) pkm_tick(m, g_out);
    pkm_start(m, &g_image, 0, 0, 12000, &mode);
    pk_reset(&alone.chip);
    {
        static PkPlayer reset;
        pkp_reset(&reset, &g_image);
        for (int i = 0; i < reset.writes; ++i) pk_write(&alone.chip, reset.write[i].reg, reset.write[i].val);
    }
    pkm_set_trace(m, queue_batch, &alone);
    for (int t = 0; t < ticks; ++t) {
        double w = now_us(), c = cpu_us();
        pkm_tick(m, g_out);
        cpu[t] = cpu_us() - c;
        wall[t] = now_us() - w;
        w = now_us();
        run_alone(&alone, pkm_chip(m)->now);
        chip_wall[t] = now_us() - w;
        overhead[t] = wall[t] - chip_wall[t];
    }
    pkm_set_trace(m, NULL, NULL);
    report("tick (player + chip + mix + resample)", wall, ticks);
    report("tick, thread CPU time", cpu, ticks);
    report("chip alone, same writes", chip_wall, ticks);
    report("overhead (tick - chip alone)", overhead, ticks);
    slowest("ticks", wall, chip_wall, "chip", ticks, 5);

    /* Paced like an audio callback. */
    pkm_start(m, &g_image, 0, 0, 12000, &mode);
    struct timespec next;
    clock_gettime(CLOCK_MONOTONIC, &next);
    const long step_ns = (long)(FRAME_US * 1000);
    for (int t = 0; t < paced; ++t) {
        next.tv_nsec += step_ns;
        while (next.tv_nsec >= 1000000000) {
            next.tv_nsec -= 1000000000;
            ++next.tv_sec;
        }
        clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &next, NULL);
        const double w = now_us(), c = cpu_us();
        pkm_tick(m, g_out);
        cpu[t] = cpu_us() - c;
        wall[t] = now_us() - w;
    }
    report("paced, one tick per frame (wall)", wall, paced);
    report("paced, thread CPU time", cpu, paced);
    slowest("paced ticks", wall, cpu, "cpu", paced, 5);

    /* The player alone. */
    PkPlayer* solo = (PkPlayer*)malloc(sizeof *solo);
    pkp_reset(solo, &g_image);
    for (int t = 0; t < ticks; ++t) {
        t0 = now_us();
        pkp_tick(solo, &g_image);
        wall[t] = now_us() - t0;
    }
    report("player alone", wall, ticks);

    /* Live edits: a relocating apply before every tick. */
    pkm_start(m, &g_image, 0, 0, 12000, &mode);
    for (int t = 0; t < ticks; ++t) {
        t0 = now_us();
        pkm_apply_image(m, (t & 1) ? &g_moved : &g_image);
        wall[t] = now_us() - t0;
        pkm_tick(m, g_out);
    }
    report("apply_image (relocating, 64 KB copy)", wall, ticks);

    /* Control thread. */
    t0 = now_us();
    for (int i = 0; i < 100; ++i) pkm_audition(m, &g_image, 0, 57, PKT_LEAD, 0);
    printf("  audition setup: %.1f us\n", (now_us() - t0) / 100);
    t0 = now_us();
    pkm_start(m, &g_image, 0, 5, 12000, &mode);
    printf("  start 0/5 (30 ticks, all clocked): %.1f ms\n", (now_us() - t0) / 1000);
    t0 = now_us();
    pkm_start(m, &g_image, 3, 20, 12000, &mode);
    printf("  start 3/20 (474 ticks, all clocked): %.1f ms\n", (now_us() - t0) / 1000);
    pkm_start(m, &g_image, 0, 0, 12000, &mode);
    t0 = now_us();
    for (int t = 0; t < 12000; ++t) pkm_tick(m, g_out);
    printf("  12000 clocked ticks (seekTicks): %.1f ms\n", (now_us() - t0) / 1000);
    pkp_reset(solo, &g_image);
    t0 = now_us();
    for (int t = 0; t < 12000; ++t) pkp_tick(solo, &g_image);
    printf("  12000 player ticks (the seek's tick count): %.1f ms\n", (now_us() - t0) / 1000);
    free(solo);
    free(wall);
    free(cpu);
    free(chip_wall);
    free(overhead);
    free(alone.queue);
    pkm_destroy(m);
    return 0;
}
