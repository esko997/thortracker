/* Host regression test for the POKEY system: Thor Tracker's own player (pokey/pokey_player.c) on the own cycle-exact
 * POKEY model (pokey/pokey_chip.c), playing the test song built in C (pokey_test_song.h).
 *
 * Checks: the chip against documented hardware numbers (Altirra Hardware Reference Manual: the 4- and 5-bit
 * generators' patterns, 9/17-bit maximal sequences, periods N + 4, N + 7, (N + 1) x 28 / x 114, 64 kHz AUDF 72 =
 * 437.8 Hz, channels 1 and 3 cancelling while 1 and 2 add, STIMER's output states, the two-tone resync letting timer 1
 * fire one cycle after timer 2 but not two) and pk_run = pk_run_reference (identical edges and interrupts) on every
 * chip case and on the song; image validation (single-byte corruptions each rejected with its reason); the song's
 * coverage (every kind, distortion, global, samples on timers 1, 2 and 4) and deterministic non-silent output; loop
 * passes; seeks sample-identical to straight play and the order fallback; live edits (same image, re-laid-out image,
 * note edit, tempo edit ahead of and behind the playhead, song cut, multispeed and machine changes); audition pitch
 * measured from the chip's output edges (A-4 at 64 kHz = 437.8 Hz, fast 16-bit, fast 8-bit, a distortion C note at its
 * 15-step period, the difference "saw" at its beat, fine pitch), samples at their rate stopping or looping, release;
 * mute; sticky portamento over samples (a one-shot sample that ended keys on afresh: AUDC, IRQEN, its interrupts and
 * audible; a playing or looping sample and a tone whose envelope ended glide); multispeed; the VGM parsed back against
 * the machine's writes; the hosts' entry point; the ops table; costs.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir]
 * Writes <dir>/pokey_song.bin and pokey_song_pal.bin (the test images), pokey_trace.txt and pokey_trace_pal.txt (1500
 * ticks: `T tick order row tickInRow cycle:` and `I cycle timers:` lines with ` R=VV` writes), pokey_porta.bin and
 * pokey_trace_porta.txt (the portamento song, 144 ticks), pokey_song.vgm (no GD3), pokey_song.wav (30 s),
 * pokey_tone.wav (A-4 + release) and pokey_chip_cases.txt (chip scenarios: `CASE`, `W`, `E`, `END`), the inputs of
 * tools/audio/pokey_reference_check.py. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "chip_engine.h"
#include "pokey_chip.h"
#include "pokey_export.h"
#include "pokey_machine.h"
#include "pokey_test_song.h"
#include "pokey_vgm.h"

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
#define SEEK_BUDGET 12000
#define TRACE_TICKS 1500
#define A4 57
#define FNV_BASIS 0xCBF29CE484222325ull

static float g_out[PKM_MAX_TICK_FRAMES * 2];
static PokeyImage g_song, g_pal, g_moved, g_edited, g_tempo, g_short, g_x2, g_bad, g_porta;

static double clock_us(clockid_t id) {
    struct timespec t;
    clock_gettime(id, &t);
    return t.tv_sec * 1e6 + t.tv_nsec / 1e3;
}
static double now_us(void) { return clock_us(CLOCK_MONOTONIC); }
static double cpu_us(void) { return clock_us(CLOCK_THREAD_CPUTIME_ID); }

static uint64_t fnv(uint64_t h, const void* data, size_t n) {
    const uint8_t* p = (const uint8_t*)data;
    for (size_t i = 0; i < n; ++i) h = (h ^ p[i]) * 0x100000001B3ull;
    return h;
}

static void write_wav(const char* path, const float* data, long frames) {
    FILE* f = fopen(path, "wb");
    if (!f) return;
    const int bytes = (int)(frames * 4);
    const int hdr[] = {0x46464952, 36 + bytes, 0x45564157, 0x20746d66, 16, 0x00020001, RATE, RATE * 4, 0x00100004,
                       0x61746164, bytes};
    fwrite(hdr, sizeof hdr, 1, f);
    for (long i = 0; i < frames * 2; ++i) {
        const float v = data[i] < -1 ? -1 : data[i] > 1 ? 1 : data[i];
        const short s = (short)lrintf(v * 32767);
        fwrite(&s, 2, 1, f);
    }
    fclose(f);
}

static void write_file(const char* dir, const char* name, const void* data, size_t n) {
    char path[512];
    snprintf(path, sizeof path, "%s/%s", dir, name);
    FILE* f = fopen(path, "wb");
    if (!f) return;
    fwrite(data, 1, n, f);
    fclose(f);
}

static double rms(const float* x, long frames) {
    double s = 0;
    for (long i = 0; i < frames * 2; ++i) s += (double)x[i] * x[i];
    return frames ? sqrt(s / (frames * 2.0)) : 0;
}

static double peak(const float* x, long frames) {
    double p = 0;
    for (long i = 0; i < frames * 2; ++i)
        if (fabs(x[i]) > p) p = fabs(x[i]);
    return p;
}

/* `ticks` ticks of m into a new buffer; returns the frame count. */
static long render(PokeyMachine* m, int ticks, float** buf) {
    float* out = (float*)malloc((size_t)(ticks + 1) * PKM_MAX_TICK_FRAMES * 2 * sizeof(float));
    long frames = 0;
    for (int t = 0; t < ticks; ++t) {
        const int n = pkm_tick(m, out + 2 * frames);
        if (n <= 0) break;
        frames += n;
    }
    *buf = out;
    return frames;
}

static void build(PokeyImage* image, PkSongOptions opt) {
    pk_build_test_song(image->bytes, &opt);
    const int rc = pk_load_image(image);
    EXPECT(rc == 0, "test image loads: %s at %d", image->info.problem ? image->info.problem : "", image->info.problem_offset);
}

/* ---- chip replays: the machine's writes fed to a second chip whose output edges and interrupts are recorded ---- */

typedef struct Edge {
    uint64_t cycle;
    uint16_t levels;
} Edge;

typedef struct Replay {
    PokeyChip chip;
    int reference; /* pk_run_reference instead of pk_run */
    int keep;      /* keep edges and interrupts, not only their hashes */
    uint64_t hash, irq_hash;
    long count, irqs;
    Edge* edges;
    long n, cap;
    uint64_t* irq_cycle;
    uint8_t* irq_timers;
    long nirq, irq_cap;
} Replay;

static void rep_out(void* ctx, uint64_t cycle, uint16_t levels) {
    Replay* r = (Replay*)ctx;
    r->hash = fnv(fnv(r->hash, &cycle, sizeof cycle), &levels, sizeof levels);
    ++r->count;
    if (!r->keep) return;
    if (r->n == r->cap) {
        r->cap = r->cap ? 2 * r->cap : 4096;
        r->edges = (Edge*)realloc(r->edges, sizeof(Edge) * (size_t)r->cap);
    }
    r->edges[r->n++] = (Edge){cycle, levels};
}

static void rep_clear(Replay* r) {
    pk_reset(&r->chip);
    r->hash = r->irq_hash = FNV_BASIS;
    r->count = r->irqs = r->n = r->nirq = 0;
}

static Replay* rep_new(int reference, int keep) {
    Replay* r = (Replay*)calloc(1, sizeof *r);
    pk_init(&r->chip, rep_out, r);
    r->reference = reference;
    r->keep = keep;
    rep_clear(r);
    return r;
}

static void rep_free(Replay* r) {
    free(r->edges);
    free(r->irq_cycle);
    free(r->irq_timers);
    free(r);
}

static void rep_run(Replay* r, uint64_t until) {
    while (r->reference ? pk_run_reference(&r->chip, until) : pk_run(&r->chip, until)) {
        const uint64_t c = r->chip.now;
        const uint8_t t = pk_take_irq(&r->chip);
        r->irq_hash = fnv(fnv(r->irq_hash, &c, sizeof c), &t, 1);
        ++r->irqs;
        if (!r->keep) continue;
        if (r->nirq == r->irq_cap) {
            r->irq_cap = r->irq_cap ? 2 * r->irq_cap : 1024;
            r->irq_cycle = (uint64_t*)realloc(r->irq_cycle, sizeof(uint64_t) * (size_t)r->irq_cap);
            r->irq_timers = (uint8_t*)realloc(r->irq_timers, (size_t)r->irq_cap);
        }
        r->irq_cycle[r->nirq] = c;
        r->irq_timers[r->nirq++] = t;
    }
}

/* The player's reset batch (what start/audition write at cycle 0) into a replay. */
static void rep_reset_batch(Replay* r, const PokeyImage* image) {
    static PkPlayer p;
    pkp_reset(&p, image);
    for (int i = 0; i < p.writes; ++i) pk_write(&r->chip, p.write[i].reg, p.write[i].val);
}

/* ---- trace sinks ---- */

typedef struct Log {
    uint64_t* cycle;
    uint8_t *reg, *val;
    long n, cap;
} Log;

static void log_add(Log* l, uint64_t cycle, int reg, int val) {
    if (l->n == l->cap) {
        l->cap = l->cap ? 2 * l->cap : 4096;
        l->cycle = (uint64_t*)realloc(l->cycle, sizeof(uint64_t) * (size_t)l->cap);
        l->reg = (uint8_t*)realloc(l->reg, (size_t)l->cap);
        l->val = (uint8_t*)realloc(l->val, (size_t)l->cap);
    }
    l->cycle[l->n] = cycle;
    l->reg[l->n] = (uint8_t)reg;
    l->val[l->n++] = (uint8_t)val;
}

static void log_free(Log* l) {
    free(l->cycle);
    free(l->reg);
    free(l->val);
    memset(l, 0, sizeof *l);
}

typedef struct Sink {
    FILE* f;
    int ticks;          /* tick batches seen */
    uint64_t hash;      /* every batch */
    uint64_t* per_tick; /* hash of each tick's batches (optional, TRACE_TICKS + 1 entries) */
    Replay* rep[2];     /* replays fed with every batch (optional) */
    Log* log;           /* writes as the VGM logs them: IRQEN left out (optional) */
    long irq_events;
    int timers_seen;
    int audctl_bits, skctl_seen[256], stimer, restart, audc_bits;
} Sink;

static void sink_event(void* ctx, const PkmEvent* e) {
    Sink* s = (Sink*)ctx;
    for (int i = 0; i < 2; ++i) {
        if (!s->rep[i]) continue;
        rep_run(s->rep[i], e->cycle);
        for (int k = 0; k < e->count; ++k) pk_write(&s->rep[i]->chip, e->writes[k].reg, e->writes[k].val);
    }
    uint64_t h = fnv(FNV_BASIS, &e->irq, sizeof e->irq);
    h = fnv(h, &e->cycle, sizeof e->cycle);
    if (e->irq) {
        h = fnv(h, &e->timers, sizeof e->timers);
        ++s->irq_events;
        s->timers_seen |= e->timers;
        if (s->f) fprintf(s->f, "I %llu %X:", (unsigned long long)e->cycle, e->timers);
    } else {
        const int pos[3] = {e->order, e->row, e->tick};
        h = fnv(h, pos, sizeof pos);
        if (s->f) fprintf(s->f, "T %d %d %d %d %llu:", s->ticks, e->order, e->row, e->tick, (unsigned long long)e->cycle);
        ++s->ticks;
    }
    int skctl_writes = 0;
    for (int k = 0; k < e->count; ++k) {
        const int reg = e->writes[k].reg, val = e->writes[k].val;
        h = fnv(fnv(h, &e->writes[k].reg, 1), &e->writes[k].val, 1);
        if (s->f) fprintf(s->f, " %X=%02X", reg, val);
        if (s->log && reg != PK_IRQEN) log_add(s->log, e->cycle, reg, val);
        if (reg == PK_AUDCTL) s->audctl_bits |= val;
        if (reg == PK_SKCTL) s->skctl_seen[val] = 1, ++skctl_writes;
        if (reg == PK_STIMER) s->stimer = 1;
        if (reg == PK_AUDC1 || reg == PK_AUDC2 || reg == PK_AUDC3 || reg == PK_AUDC4) {
            if (val & PK_AUDC_VOLUME_ONLY) s->audc_bits |= 1 << 8;
            else if (val & 15) s->audc_bits |= 1 << (val >> 5);
        }
    }
    if (skctl_writes == 2) s->restart = 1;
    if (s->f) fputc('\n', s->f);
    s->hash = fnv(s->hash, &h, sizeof h);
    if (s->per_tick && s->ticks > 0 && s->ticks <= TRACE_TICKS) s->per_tick[s->ticks - 1] = fnv(s->per_tick[s->ticks - 1], &h, sizeof h);
}

static void sink_init(Sink* s) {
    memset(s, 0, sizeof *s);
    s->hash = FNV_BASIS;
}

/* Per-tick hashes of straight play of `image` from the start for TRACE_TICKS ticks, optionally applying alt0/alt1
 * (alternately, before tick t for every t >= from when `every`, else once before tick `from`). */
static uint64_t* tick_hashes(const PokeyImage* image, const PokeyImage* alt0, const PokeyImage* alt1, int from, int every) {
    uint64_t* h = (uint64_t*)malloc(sizeof(uint64_t) * TRACE_TICKS);
    for (int i = 0; i < TRACE_TICKS; ++i) h[i] = FNV_BASIS;
    Sink s;
    sink_init(&s);
    s.per_tick = h;
    PokeyMachine* m = pkm_create(RATE);
    int mode;
    pkm_start(m, image, 0, 0, SEEK_BUDGET, &mode);
    pkm_set_trace(m, sink_event, &s);
    for (int t = 0; t < TRACE_TICKS; ++t) {
        if (alt0 && t >= from && (every || t == from)) pkm_apply_image(m, (t & 1) ? alt0 : alt1);
        pkm_tick(m, g_out);
    }
    pkm_destroy(m);
    return h;
}

static int count_differences(const uint64_t* a, const uint64_t* b, int* first) {
    int bad = 0;
    *first = -1;
    for (int i = 0; i < TRACE_TICKS; ++i)
        if (a[i] != b[i]) {
            if (*first < 0) *first = i;
            ++bad;
        }
    return bad;
}

/* ---- the chip ----------------------------------------------------------------------------------------------- */

typedef struct CW {
    uint32_t at;
    uint8_t reg, val;
} CW;

typedef struct ChipCase {
    const char* name;
    int machine;
    uint32_t cycles;
    const CW* w;
    int n;
} ChipCase;

static const CW C_FAST[] = {{0, PK_SKCTL, 3}, {0, PK_AUDCTL, 0x40}, {0, PK_AUDF1, 10}, {0, PK_AUDC1, 0xAF}};
static const CW C_BASE64[] = {{0, PK_SKCTL, 3}, {0, PK_AUDF1, 72}, {0, PK_AUDC1, 0xA8}};
static const CW C_BASE15[] = {{0, PK_SKCTL, 3}, {0, PK_AUDCTL, 0x01}, {0, PK_AUDF2, 5}, {0, PK_AUDC2, 0xA6}};
static const CW C_LINKED[] = {{0, PK_SKCTL, 3}, {0, PK_AUDCTL, 0x50}, {0, PK_AUDF1, 0x34}, {0, PK_AUDF2, 0x01}, {0, PK_AUDC2, 0xAF}};
static const CW C_HIGHPASS[] = {{0, PK_SKCTL, 3}, {0, PK_AUDCTL, 0x04}, {0, PK_AUDF1, 40}, {0, PK_AUDF3, 41}, {0, PK_AUDC1, 0xAA},
                                {0, PK_AUDC3, 0x00}};
static const CW C_STIMER[] = {{0, PK_SKCTL, 3},       {0, PK_AUDF1, 30},     {0, PK_AUDF2, 47},       {0, PK_AUDC1, 0xA5},
                              {0, PK_AUDC2, 0xA5},    {0, PK_AUDF3, 30},     {0, PK_AUDC3, 0xA3},     {5000, PK_STIMER, 0},
                              {9001, PK_STIMER, 0}};
static const CW C_INIT[] = {{0, PK_SKCTL, 3},      {0, PK_AUDCTL, 0x40}, {0, PK_AUDF1, 100},   {0, PK_AUDC1, 0xA4}, {0, PK_AUDF3, 20},
                            {0, PK_AUDC3, 0xA5},   {0, PK_AUDF4, 3},     {0, PK_AUDC4, 0x8F},  {6000, PK_SKCTL, 0}, {9000, PK_SKCTL, 3}};
static const CW C_TWOTONE[] = {{0, PK_SKCTL, 0x0B}, {0, PK_AUDF1, 3}, {0, PK_AUDF2, 5}, {0, PK_AUDC1, 0xA8}, {0, PK_AUDC2, 0xA8}};
static const CW C_TWOTONE_BREAK[] = {{0, PK_SKCTL, 0x8B}, {0, PK_AUDCTL, 0x40}, {0, PK_AUDF1, 81},   {0, PK_AUDF2, 2},
                                     {0, PK_AUDC1, 0xA8}, {0, PK_AUDC2, 0xA8},  {0, PK_IRQEN, 0x01}, {106, PK_STIMER, 0}};
static const CW C_VOLONLY[] = {{0, PK_SKCTL, 3},       {0, PK_AUDC1, 0x18},    {0, PK_AUDF2, 10},      {0, PK_AUDC2, 0xA4},
                               {1000, PK_AUDC1, 0x1F}, {2000, PK_AUDC1, 0x10}, {2500, PK_AUDC2, 0x14}};
static const CW C_IRQ[] = {{0, PK_SKCTL, 3},       {0, PK_IRQEN, 0x07},   {0, PK_AUDF1, 20},      {0, PK_AUDC1, 0xA4},
                           {0, PK_AUDF2, 50},      {0, PK_AUDF4, 100},    {0, PK_AUDC4, 0xA2},    {8000, PK_IRQEN, 0x05},
                           {15000, PK_IRQEN, 0}};
static const CW C_POLY[] = {{0, PK_SKCTL, 3}, {0, PK_AUDCTL, 0x80}, {0, PK_AUDF1, 0}, {0, PK_AUDC1, 0x8F}, {10000, PK_AUDCTL, 0x00}};
static const CW C_PAL[] = {{0, PK_SKCTL, 3},     {0, PK_AUDF1, 5},     {0, PK_AUDC1, 0x0F}, {0, PK_AUDF2, 7},
                           {0, PK_AUDC2, 0x4A},  {0, PK_AUDF3, 9},     {0, PK_AUDC3, 0x2C}, {0, PK_AUDF4, 2},
                           {0, PK_AUDC4, 0xC6}};

#define CASE(name, machine, cycles, w) {name, machine, cycles, w, (int)(sizeof w / sizeof w[0])}
static const ChipCase CASES[] = {
    CASE("fast", PK_MACHINE_NTSC, 4000, C_FAST),
    CASE("base64", PK_MACHINE_NTSC, 20000, C_BASE64),
    CASE("base15", PK_MACHINE_NTSC, 20000, C_BASE15),
    CASE("linked", PK_MACHINE_NTSC, 12000, C_LINKED),
    CASE("highpass", PK_MACHINE_NTSC, 30000, C_HIGHPASS),
    CASE("stimer", PK_MACHINE_NTSC, 15000, C_STIMER),
    CASE("initmode", PK_MACHINE_NTSC, 15000, C_INIT),
    CASE("twotone", PK_MACHINE_NTSC, 10000, C_TWOTONE),
    CASE("twotone_break", PK_MACHINE_NTSC, 3000, C_TWOTONE_BREAK),
    CASE("volume_only", PK_MACHINE_NTSC, 4000, C_VOLONLY),
    CASE("irq", PK_MACHINE_NTSC, 20000, C_IRQ),
    CASE("poly9_poly17", PK_MACHINE_NTSC, 20000, C_POLY),
    CASE("pal_distortions", PK_MACHINE_PAL, 20000, C_PAL),
};

/* Runs a list of writes (ascending cycles; a write acts after that cycle's step) to `cycles` on a cleared replay. */
static void run_writes(Replay* r, const CW* w, int n, uint32_t cycles) {
    rep_clear(r);
    for (int i = 0; i < n; ++i) {
        rep_run(r, w[i].at);
        pk_write(&r->chip, w[i].reg, w[i].val);
    }
    rep_run(r, cycles);
}

/* Cycles between successive rising edges (0 -> on) of channel ch after `from`: all equal? Returns the interval or -1. */
static long rising_interval(const Replay* r, int ch, uint64_t from) {
    long interval = 0;
    uint64_t last = 0;
    int prev = 0, have = 0, bad = 0;
    for (long i = 0; i < r->n; ++i) {
        const int nib = (r->edges[i].levels >> (4 * ch)) & 15;
        if (!prev && nib && r->edges[i].cycle >= from) {
            if (have) {
                const long d = (long)(r->edges[i].cycle - last);
                if (!interval) interval = d;
                else if (d != interval) bad = 1;
            }
            last = r->edges[i].cycle;
            have = 1;
        }
        prev = nib;
    }
    return bad || !interval ? -1 : interval;
}

static int pattern_bit(const char* s, int k) { return s[k] - '0'; }

static void test_chip(const char* dir) {
    printf("chip\n");
    Replay* a = rep_new(0, 1);
    Replay* b = rep_new(1, 1);
    /* Generators (Altirra manual 5.5: the 4- and 5-bit patterns from the reset state; maximal-length sequences). */
    rep_clear(a);
    pk_write(&a->chip, PK_SKCTL, 3);
    static const char* P4 = "000011101100101";
    static const char* P5 = "0000011100100010101111011010011";
    int ok4 = 1, ok5 = 1;
    for (int ch = 0; ch < 4; ++ch) {
        for (int k = 0; k < 15; ++k) ok4 &= pk_poly_bit(&a->chip, 4, ch, (uint64_t)(ch + 1 + k)) == pattern_bit(P4, k);
        for (int k = 0; k < 31; ++k) ok5 &= pk_poly_bit(&a->chip, 5, ch, (uint64_t)(ch + 1 + k)) == pattern_bit(P5, k);
    }
    EXPECT(ok4, "4-bit generator: %s, channel c c cycles late", P4);
    EXPECT(ok5, "5-bit generator: %s", P5);
    static const int lengths[4] = {PK_POLY4_LENGTH, PK_POLY5_LENGTH, PK_POLY9_LENGTH, PK_POLY17_LENGTH};
    static const int which[4] = {4, 5, 9, 17};
    for (int g = 0; g < 4; ++g) {
        long ones = 0;
        for (int k = 0; k < lengths[g]; ++k) ones += pk_poly_bit(&a->chip, which[g], 0, (uint64_t)(1 + k));
        EXPECT(ones == lengths[g] / 2, "%d-bit generator: %ld ones in its period %d (maximal length: %d)", which[g], ones,
               lengths[g], lengths[g] / 2);
    }
    /* 511 = 7 x 73: the 9-bit sequence repeats with neither factor (its period is the full 511) */
    for (int f = 7; f <= 73; f += 66) {
        int periodic = 1;
        for (int k = 0; k < 511 && periodic; ++k)
            periodic = pk_poly_bit(&a->chip, 9, 0, (uint64_t)(1 + k)) == pk_poly_bit(&a->chip, 9, 0, (uint64_t)(1 + k + f));
        EXPECT(!periodic, "9-bit generator does not repeat after %d", f);
    }
    pk_write(&a->chip, PK_SKCTL, 0);
    EXPECT(pk_poly_bit(&a->chip, 5, 0, 10) == 1 && pk_poly_bit(&a->chip, 4, 0, 10) == 0 && pk_poly_bit(&a->chip, 17, 0, 10) == 0,
           "initialization mode: 5-bit reads 1, the others 0");

    /* Periods from the output edges. */
    run_writes(a, C_FAST, 4, 4000);
    EXPECT(rising_interval(a, 0, 100) == 2 * (10 + 4), "fast 8-bit: N + 4 cycles per fire (%ld per rising edge)", rising_interval(a, 0, 100));
    run_writes(a, C_BASE64, 3, 20000);
    const long p64 = rising_interval(a, 0, 100);
    const double hz64 = PK_CLOCK_NTSC / (double)p64;
    EXPECT(p64 == 2 * 28 * 73 && fabs(hz64 - 437.8) < 0.05, "64 kHz AUDF 72 = (N + 1) x 28: %ld cycles = %.2f Hz (437.8)", p64, hz64);
    run_writes(a, C_BASE15, 4, 20000);
    EXPECT(rising_interval(a, 1, 200) == 2 * 114 * 6, "15 kHz: (N + 1) x 114 (%ld)", rising_interval(a, 1, 200));
    run_writes(a, C_LINKED, 5, 12000);
    EXPECT(rising_interval(a, 1, 1000) == 2 * (0x134 + 7), "linked fast 16-bit: N + 7 (%ld)", rising_interval(a, 1, 1000));

    /* Channels 1 and 2 are inverted against 3 and 4: equal tones on 1 and 3 cancel, on 1 and 2 they add. */
    static const CW cancel[] = {{0, PK_SKCTL, 3}, {0, PK_AUDF1, 40}, {0, PK_AUDF3, 40}, {0, PK_AUDC1, 0xA8}, {0, PK_AUDC3, 0xA8},
                                {100, PK_STIMER, 0}};
    run_writes(a, cancel, 6, 20000);
    int constant = 1;
    for (long i = 0; i < a->n; ++i)
        if (a->edges[i].cycle >= 100) constant &= pk_mix_index(a->edges[i].levels) == pk_mix_index(0x0008);
    EXPECT(constant && a->n > 10, "channels 1 + 3, same tone: the mix stays constant (cancel) over %ld edges", a->n);
    static const CW add[] = {{0, PK_SKCTL, 3}, {0, PK_AUDF1, 40}, {0, PK_AUDF2, 40}, {0, PK_AUDC1, 0xA8}, {0, PK_AUDC2, 0xA8},
                             {100, PK_STIMER, 0}};
    run_writes(a, add, 6, 20000);
    int alternate = a->n > 10;
    for (long i = 0; i < a->n; ++i)
        if (a->edges[i].cycle > 100) alternate &= a->edges[i].levels == 0x0088 || a->edges[i].levels == 0x0000;
    EXPECT(alternate, "channels 1 + 2, same tone: in phase, the mix swings 0 <-> both (add)");
    /* STIMER: every output flip-flop 1, so channels 3 and 4 are on and 1 and 2 (inverted) off. */
    for (int hp = 0; hp < 2; ++hp) {
        const CW st[] = {{0, PK_SKCTL, 3},      {0, PK_AUDCTL, (uint8_t)(hp ? 0x06 : 0)}, {0, PK_AUDF1, 50}, {0, PK_AUDF2, 50},
                         {0, PK_AUDF3, 50},     {0, PK_AUDF4, 50},                       {0, PK_AUDC1, 0xA5}, {0, PK_AUDC2, 0xA5},
                         {0, PK_AUDC3, 0xA5},   {0, PK_AUDC4, 0xA5},                     {200, PK_STIMER, 0}};
        run_writes(a, st, 11, 200);
        EXPECT(a->chip.levels == 0x5500, "STIMER%s: outputs 1, channels 3 and 4 on (levels %04X)", hp ? " with high-pass" : "",
               a->chip.levels);
    }
    /* Two-tone: timer 2 every 84 cycles (64 kHz, AUDF 2) resyncs 2 cycles after its fire; timer 1 (1.79 MHz) set to 85
     * cycles still fires once after STIMER, at 86 it is pre-empted (Altirra manual, two-tone resync timing). */
    for (int n = 81; n <= 82; ++n) {
        CW w[8];
        memcpy(w, C_TWOTONE_BREAK, sizeof w);
        w[2].val = (uint8_t)n;
        run_writes(a, w, 8, 3000);
        int fired = 0;
        uint64_t at = 0;
        for (long i = 0; i < a->nirq; ++i)
            if (a->irq_cycle[i] > 106 && (a->irq_timers[i] & PK_IRQ_TIMER1)) ++fired, at = a->irq_cycle[i];
        if (n == 81) EXPECT(fired == 1 && at == 106 + 85, "two-tone: timer 1 at 85 cycles fires once (%d, at %llu)", fired, (unsigned long long)at);
        else EXPECT(fired == 0, "two-tone: timer 1 at 86 cycles is pre-empted (%d fires)", fired);
    }

    /* Every case: the event-driven pk_run = pk_run_reference stepping every cycle; the cases file for the reference. */
    char path[512];
    snprintf(path, sizeof path, "%s/pokey_chip_cases.txt", dir);
    FILE* f = fopen(path, "w");
    for (size_t c = 0; c < sizeof CASES / sizeof CASES[0]; ++c) {
        const ChipCase* k = &CASES[c];
        run_writes(a, k->w, k->n, k->cycles);
        run_writes(b, k->w, k->n, k->cycles);
        EXPECT(a->count == b->count && a->hash == b->hash && a->irqs == b->irqs && a->irq_hash == b->irq_hash,
               "%s: pk_run = pk_run_reference (%ld / %ld edges, %ld / %ld interrupts)", k->name, a->count, b->count, a->irqs, b->irqs);
        EXPECT(a->count > 2, "%s: output changes (%ld)", k->name, a->count);
        if (!f) continue;
        fprintf(f, "CASE %s %s %u\n", k->name, k->machine == PK_MACHINE_PAL ? "PAL" : "NTSC", k->cycles);
        for (int i = 0; i < k->n; ++i) fprintf(f, "W %u %X %02X\n", k->w[i].at, k->w[i].reg, k->w[i].val);
        for (long i = 0; i < a->n; ++i) fprintf(f, "E %llu %04X\n", (unsigned long long)a->edges[i].cycle, a->edges[i].levels);
        fprintf(f, "END\n");
    }
    if (f) fclose(f);
    printf("  64 kHz AUDF 72: %.2f Hz; %zu chip cases written\n", hz64, sizeof CASES / sizeof CASES[0]);
    rep_free(a);
    rep_free(b);
}

/* ---- images ------------------------------------------------------------------------------------------------- */

/* Data-region offsets of the first mask, note and instrument bytes and of the first code byte of every effect. */
typedef struct Offsets {
    int mask, note, inst, code[36];
} Offsets;

static Offsets find_offsets(const PokeyImage* image) {
    Offsets o;
    memset(&o, -1, sizeof o);
    const uint8_t* b = image->bytes;
    for (int order = 0; order < image->info.orders; ++order)
        for (int c = 0; c < PK_CHANNELS; ++c) {
            int at = pk_u16(b + PK_OFF_ORDERS + 2 * (PK_CHANNELS * order + c)), run = 0;
            for (int r = 0; r < image->info.rows[order]; ++r) {
                if (run) { --run; continue; }
                const int m = b[PK_OFF_DATA + at];
                if (m & 0x80) { run = m & 0x7F; ++at; continue; }
                if (o.mask < 0) o.mask = PK_OFF_DATA + at;
                ++at;
                if (m & 1) { if (o.note < 0) o.note = PK_OFF_DATA + at; ++at; }
                if (m & 2) { if (o.inst < 0) o.inst = PK_OFF_DATA + at; ++at; }
                for (int e = 0; e < ((m >> 2) & 7); ++e, at += 2)
                    if (o.code[b[PK_OFF_DATA + at]] < 0) o.code[b[PK_OFF_DATA + at]] = PK_OFF_DATA + at;
            }
        }
    return o;
}

static void corrupt(int offset, int value, const char* want) {
    memcpy(&g_bad, &g_song, sizeof g_bad);
    g_bad.bytes[offset] = (uint8_t)value;
    const int rc = pk_load_image(&g_bad);
    EXPECT(rc != 0 && g_bad.info.problem && strstr(g_bad.info.problem, want), "byte %d = %d rejected for \"%s\" (got %s)", offset,
           value, want, rc ? g_bad.info.problem : "accepted");
}

static int inst_at(int slot, int field) { return PK_OFF_INSTRUMENTS + PK_INSTRUMENT_SIZE * (slot - 1) + field; }

static void test_images(void) {
    printf("images\n");
    const PkInfo* in = &g_song.info;
    EXPECT(in->loaded == PK_LOADED && in->orders == 4 && in->loop == 1 && in->machine == PK_MACHINE_NTSC && in->samples == 2,
           "song info");
    EXPECT(in->rows[0] == 16 && in->rows[1] == 32 && in->rows[2] == 16 && in->rows[3] == 24, "rows per order");
    EXPECT(in->start_speed[0] == 6 && in->start_speed[1] == 6 && in->start_speed[2] == 6 && in->start_speed[3] == 6,
           "start speeds 6 (order 3's F05 is on its row 2)");
    EXPECT(in->start_audctl[0] == 0 && in->start_audctl[1] == 0x80 && in->start_audctl[2] == 0x80 && in->start_audctl[3] == 0x80,
           "start AUDCTL bits: N01 in order 0, K01/K00 in order 1 (%02X %02X %02X %02X)", in->start_audctl[0], in->start_audctl[1],
           in->start_audctl[2], in->start_audctl[3]);
    EXPECT(in->start_two_tone[1] == 0 && in->start_two_tone[2] == 0 && in->start_two_tone[3] == 2, "start two-tone (W02 in order 2)");
    EXPECT(g_pal.info.machine == PK_MACHINE_PAL, "PAL copy");
    const Offsets o = find_offsets(&g_song);
    const int env0 = PK_OFF_DATA; /* the lead's volume envelope comes first in the data region */
    corrupt(0, 'X', "magic");
    corrupt(PK_H_LAYOUT, 2, "layout");
    corrupt(PK_H_MACHINE, 2, "machine");
    corrupt(PK_H_MULTISPEED, 0, "multispeed");
    corrupt(PK_H_MULTISPEED, 5, "multispeed");
    corrupt(PK_H_SPEED, 0, "speed");
    corrupt(PK_H_ORDERS, 0, "order count");
    corrupt(PK_H_LOOP, 4, "loop");
    corrupt(PK_H_INSTRUMENTS, 65, "instrument count");
    corrupt(PK_H_INSTRUMENTS, 12, "past the instrument count");
    corrupt(PK_H_AUDCTL, 0x02, "shared AUDCTL");
    corrupt(PK_H_DATA_SIZE + 1, 0xFF, "data size");
    corrupt(PK_H_SAMPLE_SIZE + 1, 0xFF, "sample bytes");
    corrupt(PK_H_SAMPLES, 33, "sample count");
    corrupt(PK_H_SAMPLES, 3, "sample length");
    corrupt(20, 1, "reserved");
    corrupt(PK_OFF_ROWS + 5, 4, "past the order count");
    corrupt(PK_OFF_ORDERS + 1, 0xF0, "pattern offset");
    corrupt(PK_OFF_SAMPLES + 3, 0x90, "sample length");
    corrupt(PK_OFF_SAMPLES + PK_SAMPLE_ENTRY_SIZE + 1, 0x40, "sample runs past");
    corrupt(inst_at(PKT_LEAD, PK_I_FLAGS), 0xC0, "instrument flags");
    corrupt(inst_at(PKT_LEAD, PK_I_FLAGS), 0x00, "instrument flags");
    corrupt(inst_at(PKT_KICK, PK_I_FLAGS), 0x85, "samples cannot be joined");
    corrupt(inst_at(PKT_LEAD, PK_I_FLAGS), 0xA0, "difference without filtered");
    corrupt(inst_at(PKT_SAW, PK_I_FILTER_OFFSET), 0, "difference filter offset");
    corrupt(inst_at(PKT_LEAD, PK_I_FILTER_OFFSET), 1, "filter offset without filtered");
    corrupt(inst_at(PKT_LEAD, PK_I_PARTNER_AUDC), 1, "partner AUDC");
    corrupt(inst_at(PKT_LEAD, 5), 1, "reserved byte 5");
    corrupt(inst_at(PKT_KICK, PK_I_SAMPLE), 2, "sample the image does not have");
    corrupt(inst_at(PKT_KICK, PK_I_ROOT), 108, "root");
    corrupt(inst_at(PKT_KICK, PK_I_RATE + 1), 0x03, "sample rate");
    corrupt(inst_at(PKT_LOOPS, PK_I_LOOP), 64, "sample loop past");
    corrupt(inst_at(PKT_KICK, PK_I_ENV_VOLUME), 0, "no volume or distortion envelope");
    corrupt(inst_at(PKT_LEAD, PK_I_SAMPLE), 1, "sample index without samples");
    corrupt(inst_at(PKT_LEAD, PK_I_RATE), 1, "sample rate without samples");
    corrupt(inst_at(PKT_LEAD, PK_I_LOOP), 0, "sample loop without samples");
    corrupt(inst_at(PKT_LEAD, PK_I_ENV_VOLUME + 1), 0xF0, "envelope offset");
    corrupt(env0, 0, "envelope length");
    corrupt(env0 + 2, 10, "envelope loop");
    corrupt(env0 + 4, 4, "envelope release");
    corrupt(env0 + 6, 16, "envelope value");
    corrupt(inst_at(PKT_LEAD, 20), 1, "reserved bytes 18..31");
    corrupt(o.mask, 0x20, "mask");
    corrupt(o.mask, 0x17, "mask");
    corrupt(o.note, 108, "note");
    corrupt(o.inst, 14, "instrument the image does not define");
    corrupt(o.code[PK_FX_ARPEGGIO], 36, "effect code above Z");
    corrupt(o.code[PK_FX_ARPEGGIO], 5, "unknown effect");
    corrupt(o.code[PK_FX_JUMP] + 1, 4, "Bxx");
    corrupt(o.code[PK_FX_VOLUME] + 1, 0x10, "Cxx");
    corrupt(o.code[PK_FX_SPEED] + 1, 0, "F00");
    corrupt(o.code[PK_FX_CLOCK] + 1, 2, "Kxx");
    corrupt(o.code[PK_FX_POLY] + 1, 2, "Nxx");
    corrupt(o.code[PK_FX_RESTART] + 1, 1, "R00");
    corrupt(o.code[PK_FX_TIMERS] + 1, 1, "T00");
    corrupt(o.code[PK_FX_DISTORTION] + 1, 9, "Vxx");
    corrupt(o.code[PK_FX_TWO_TONE] + 1, 3, "Wxx");
    memcpy(&g_bad, &g_song, sizeof g_bad);
    EXPECT(pk_load_image(&g_bad) == 0 && !memcmp(&g_bad.info, &g_song.info, sizeof g_bad.info), "loading is a pure function");
}

/* ---- the song ----------------------------------------------------------------------------------------------- */

/* Straight play of `image` for TRACE_TICKS ticks into the trace file, fed to both chip replays. */
static void test_trace(const char* dir, const PokeyImage* image, const char* file) {
    printf("trace %s\n", file);
    char path[512];
    snprintf(path, sizeof path, "%s/%s", dir, file);
    Sink s;
    sink_init(&s);
    s.f = fopen(path, "w");
    s.rep[0] = rep_new(0, 0);
    s.rep[1] = rep_new(1, 0);
    rep_reset_batch(s.rep[0], image);
    rep_reset_batch(s.rep[1], image);
    PokeyMachine* m = pkm_create(RATE);
    int mode, kinds = 0, sample_lanes = 0;
    EXPECT(pkm_start(m, image, 0, 0, SEEK_BUDGET, &mode) == PKM_OK && mode == PKM_SEEK_FROM_START, "start");
    pkm_set_trace(m, sink_event, &s);
    const uint32_t frame = image->info.machine == PK_MACHINE_PAL ? PK_FRAME_CYCLES_PAL : PK_FRAME_CYCLES_NTSC;
    int cycles_ok = 1;
    for (int t = 0; t < TRACE_TICKS; ++t) {
        pkm_tick(m, g_out);
        cycles_ok &= pkm_tick_cycles(m) == frame;
        const PkPlayer* p = pkm_player(m);
        for (int l = 0; l < PK_CHANNELS; ++l) {
            const PkVoice* v = &p->lane[l].voice;
            if (!v->active) continue;
            kinds |= 1 << v->kind;
            if (v->kind == PKP_SAMPLE && v->playing) sample_lanes |= 1 << l;
        }
    }
    for (int i = 0; i < 2; ++i) rep_run(s.rep[i], pkm_chip(m)->now);
    if (s.f) fclose(s.f);
    EXPECT(cycles_ok, "every tick = one frame (%u cycles)", frame);
    EXPECT(kinds == 0x1F, "every kind sounded (mask %02X)", kinds);
    EXPECT(sample_lanes == 0x0B && (s.timers_seen & 7) == 7, "samples on channels 1, 2 and 4 (lanes %X, timers %X)", sample_lanes,
           s.timers_seen);
    EXPECT(s.audc_bits == 0x1FF, "every distortion and volume only written (mask %03X)", s.audc_bits);
    EXPECT((s.audctl_bits & 0xFF) == 0xFF, "every AUDCTL bit used (%02X)", s.audctl_bits);
    EXPECT(s.skctl_seen[0x0B] && s.skctl_seen[0x8B] && s.skctl_seen[0x03] && s.restart && s.stimer,
           "two-tone, force break, R pair and STIMER written");
    EXPECT(s.rep[0]->count == s.rep[1]->count && s.rep[0]->hash == s.rep[1]->hash && s.rep[0]->irq_hash == s.rep[1]->irq_hash,
           "%d ticks of the song: pk_run = pk_run_reference (%ld edges, %ld interrupts)", TRACE_TICKS, s.rep[0]->count, s.rep[0]->irqs);
    EXPECT(s.rep[0]->irqs == s.irq_events, "every interrupt reached the player (%ld vs %ld)", s.rep[0]->irqs, s.irq_events);
    printf("  %d ticks, %ld interrupts, %ld output changes\n", TRACE_TICKS, s.irq_events, s.rep[0]->count);
    rep_free(s.rep[0]);
    rep_free(s.rep[1]);
    pkm_destroy(m);
}

static void test_audio(const char* dir) {
    printf("audio\n");
    const int ticks = 1800;
    uint64_t hash[2] = {0, 0};
    for (int pass = 0; pass < 2; ++pass) {
        PokeyMachine* m = pkm_create(RATE);
        int mode;
        pkm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
        float* buf;
        const double t0 = now_us();
        const long frames = render(m, ticks, &buf);
        const double us = (now_us() - t0) / ticks;
        hash[pass] = fnv(FNV_BASIS, buf, (size_t)frames * 2 * sizeof(float));
        if (pass == 0) {
            const double want = ticks * (double)PK_FRAME_CYCLES_NTSC * RATE / PK_CLOCK_NTSC;
            const double r = rms(buf, frames), pk = peak(buf, frames);
            EXPECT(fabs(frames - want) <= 2, "1800 ticks = %.1f frames (got %ld)", want, frames);
            EXPECT(r > 0.01, "non-silent (rms %.4f)", r);
            EXPECT(pk <= 0.65f, "within the amplifier clamp (peak %.4f)", pk);
            int quiet = 0;
            for (long s = 0; s + RATE <= frames; s += RATE)
                if (rms(buf + 2 * s, RATE) < 0.002) ++quiet;
            EXPECT(quiet == 0, "every second non-silent (%d quiet)", quiet);
            int stereo = 1;
            for (long i = 0; i < frames; ++i) stereo &= buf[2 * i] == buf[2 * i + 1];
            EXPECT(stereo, "mono on both host channels");
            char path[512];
            snprintf(path, sizeof path, "%s/pokey_song.wav", dir);
            write_wav(path, buf, frames);
            printf("  30 s: rms %.4f, peak %.4f, %.1f us per tick (%.0fx realtime)\n", r, pk, us, 16683.0 / us);
        }
        free(buf);
        pkm_destroy(m);
    }
    EXPECT(hash[0] == hash[1], "bit-identical on a second start");
}

/* Ticks from the start to the first arrival at (loop, 0, 0), and the pass length. */
static void test_loop(void) {
    printf("loop passes\n");
    enum { MAX_PASS = 1024 };
    static int pos[4][MAX_PASS];
    int len[4] = {0}, first = -1;
    PokeyMachine* m = pkm_create(RATE);
    int mode, pass = -1;
    pkm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    const PkPlayer* p = pkm_player(m);
    uint8_t audctl[4] = {0}, two_tone[4] = {0}, speed[4] = {0};
    for (int k = 0; k < 4 * MAX_PASS && pass < 4; ++k) {
        if (p->order == g_song.info.loop && p->row == 0 && p->tick == 0) {
            ++pass;
            if (first < 0) first = k;
            if (pass < 4) audctl[pass] = (uint8_t)p->shared, two_tone[pass] = (uint8_t)p->two_tone, speed[pass] = (uint8_t)p->speed;
        }
        const int here = p->order << 16 | p->row << 8 | p->tick;
        pkm_tick(m, g_out);
        if (pass < 0 || pass >= 4 || len[pass] >= MAX_PASS) continue;
        pos[pass][len[pass]++] = here;
    }
    EXPECT(pass == 4 && len[0] == len[1] && len[1] == len[2] && len[0] < MAX_PASS, "passes of %d, %d, %d ticks", len[0], len[1], len[2]);
    EXPECT(!memcmp(pos[0], pos[1], sizeof(int) * (size_t)len[0]) && !memcmp(pos[1], pos[2], sizeof(int) * (size_t)len[0]),
           "every pass steps through the same (order, row, tick) positions");
    EXPECT(audctl[1] == 0x80 && audctl[2] == 0x80 && two_tone[1] == 0 && speed[1] == 6 && speed[2] == 6,
           "every arrival at the loop starts with the first pass's speed, shared AUDCTL and two-tone (order 3 ends at F05, N00)");
    static PkVgmScratch scratch;
    pk_init(&scratch.chip, NULL, NULL);
    PkVgmInfo info;
    pk_vgm_render(&g_song, 36000, &scratch, NULL, 0, &info);
    const uint64_t want = pk_vgm_samples(PK_MACHINE_NTSC, (uint64_t)(first + len[0]) * PK_FRAME_CYCLES_NTSC) -
                          pk_vgm_samples(PK_MACHINE_NTSC, (uint64_t)first * PK_FRAME_CYCLES_NTSC);
    EXPECT(info.loop_samples == want && info.ticks == (uint32_t)(first + len[0]), "= the VGM loop (%u samples, %u ticks)",
           info.loop_samples, info.ticks);
    printf("  first arrival after %d ticks, %d ticks per pass\n", first, len[0]);
    pkm_destroy(m);
}

static int positions_until(const PokeyImage* image, int order, int row) {
    PokeyMachine* m = pkm_create(RATE);
    int mode, o, r, t, k = -1;
    pkm_start(m, image, 0, 0, SEEK_BUDGET, &mode);
    for (int i = 0; i < TRACE_TICKS; ++i) {
        pkm_position(m, &o, &r, &t);
        if (o == order && r == row && t == 0) {
            k = i;
            break;
        }
        pkm_tick(m, g_out);
    }
    pkm_destroy(m);
    return k;
}

static void test_seek(void) {
    printf("seek\n");
    /* straight play's frames per tick for the first 1100 ticks */
    enum { N = 1100 };
    PokeyMachine* m = pkm_create(RATE);
    int mode;
    pkm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    float* all = (float*)malloc(sizeof(float) * 2 * PKM_MAX_TICK_FRAMES * (N + 1));
    static long at[N + 1];
    at[0] = 0;
    for (int t = 0; t < N; ++t) at[t + 1] = at[t] + pkm_tick(m, all + 2 * at[t]);
    static const int targets[][2] = {{0, 5}, {0, 15}, {1, 0}, {1, 17}, {2, 3}, {2, 12}, {3, 2}, {3, 20}};
    for (size_t i = 0; i < sizeof targets / sizeof targets[0]; ++i) {
        const int o = targets[i][0], r = targets[i][1], k = positions_until(&g_song, o, r);
        EXPECT(k >= 0 && k + 60 <= N, "target %d/%d reached by straight play (tick %d)", o, r, k);
        if (k < 0 || k + 60 > N) continue;
        const double t0 = now_us();
        EXPECT(pkm_start(m, &g_song, o, r, SEEK_BUDGET, &mode) == PKM_OK && mode == PKM_SEEK_FROM_START, "seek %d/%d", o, r);
        const double us = now_us() - t0;
        int po, pr, pt;
        pkm_position(m, &po, &pr, &pt);
        EXPECT(po == o && pr == r && pt == 0, "lands on %d/%d tick 0", o, r);
        float* y;
        const long fy = render(m, 60, &y);
        const long fx = at[k + 60] - at[k];
        EXPECT(fx == fy && !memcmp(all + 2 * at[k], y, sizeof(float) * 2 * (size_t)fx),
               "seek %d/%d: 60 ticks sample-identical to straight play", o, r);
        free(y);
        printf("  %d/%d: %d ticks clocked in %.0f us\n", o, r, k, us);
    }
    /* Order 3 rows 0-1 are only reached by the order fallback (order 2 breaks to row 2). */
    EXPECT(pkm_start(m, &g_song, 3, 0, SEEK_BUDGET, &mode) == PKM_OK && mode == PKM_SEEK_FROM_ORDER, "3/0: order fallback");
    int o, r, t;
    pkm_position(m, &o, &r, &t);
    EXPECT(o == 3 && r == 0 && t == 0, "fallback lands on 3/0 (%d/%d/%d)", o, r, t);
    for (int j = 0; j < 12; ++j) pkm_tick(m, g_out);
    pkm_position(m, &o, &r, &t);
    EXPECT(o == 3 && r == 2 && t == 0, "rows 0-1 at the order's start speed 6 (%d/%d/%d)", o, r, t);
    for (int j = 0; j < 5; ++j) pkm_tick(m, g_out);
    pkm_position(m, &o, &r, &t);
    EXPECT(o == 3 && r == 3 && t == 0, "then F05 (%d/%d/%d)", o, r, t);
    EXPECT(pkm_start(m, &g_song, 1, 17, 10, &mode) == PKM_OK && mode == PKM_SEEK_FROM_ORDER, "small budget: fallback");
    pkm_position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 17 && t == 0, "budget fallback lands on 1/17");
    EXPECT(pkm_start(m, &g_song, 4, 0, SEEK_BUDGET, &mode) == PKM_ERR_IMAGE, "order past the song rejected");
    EXPECT(pkm_start(m, &g_song, 0, 16, SEEK_BUDGET, &mode) == PKM_ERR_IMAGE, "row past the order rejected");
    free(all);
    pkm_destroy(m);
}

/* Ticks between the next two row starts. */
static int row_length(PokeyMachine* m) {
    int o, r, t, n = 0;
    do {
        pkm_tick(m, g_out);
        pkm_position(m, &o, &r, &t);
    } while (t != 0);
    do {
        pkm_tick(m, g_out);
        ++n;
        pkm_position(m, &o, &r, &t);
    } while (t != 0);
    return n;
}

static void test_live_edits(void) {
    printf("live edits\n");
    uint64_t* song = tick_hashes(&g_song, NULL, NULL, 0, 0);
    int first;
    uint64_t* h = tick_hashes(&g_song, &g_song, &g_song, 0, 1);
    int bad = count_differences(song, h, &first);
    EXPECT(bad == 0, "same image applied before every tick: = straight play (%d differ, first %d)", bad, first);
    free(h);
    h = tick_hashes(&g_song, &g_moved, &g_song, 0, 1);
    bad = count_differences(song, h, &first);
    EXPECT(bad == 0, "re-laid-out image and the original alternately before every tick: = straight play (%d differ, first %d)", bad,
           first);
    free(h);
    {
        PokeyMachine* m = pkm_create(RATE);
        int mode;
        pkm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
        double worst = 0, total = 0;
        for (int k = 0; k < TRACE_TICKS; ++k) {
            const double t0 = now_us();
            pkm_apply_image(m, (k & 1) ? &g_moved : &g_song);
            const double us = now_us() - t0;
            total += us;
            if (us > worst) worst = us;
            pkm_tick(m, g_out);
        }
        printf("  apply_image: %.2f us mean, worst %.1f us\n", total / TRACE_TICKS, worst);
        pkm_destroy(m);
    }
    /* A note edit in order 1 row 16 (lane 3), applied at ticks before that row: straight play of the edited song. */
    uint64_t* edited = tick_hashes(&g_edited, NULL, NULL, 0, 0);
    const int k_edit = positions_until(&g_song, 1, 16);
    const int when[] = {0, 40, k_edit - 30, k_edit - 2, k_edit};
    for (size_t i = 0; i < sizeof when / sizeof when[0]; ++i) {
        h = tick_hashes(&g_song, &g_edited, &g_edited, when[i], 0);
        bad = count_differences(edited, h, &first);
        EXPECT(bad == 0, "note edit applied before tick %d: = edited straight play (%d differ, first %d)", when[i], bad, first);
        free(h);
    }
    EXPECT(count_differences(song, edited, &first) > 0, "the note edit changes the writes");
    free(edited);
    /* Tempo: F09 on order 1 row 4. Applied ahead of it: straight play of the tempo song. */
    uint64_t* tempo = tick_hashes(&g_tempo, NULL, NULL, 0, 0);
    const int k_tempo = positions_until(&g_song, 1, 4);
    h = tick_hashes(&g_song, &g_tempo, &g_tempo, k_tempo - 3, 0);
    bad = count_differences(tempo, h, &first);
    EXPECT(bad == 0, "tempo edit ahead of the playhead: = tempo song straight play (%d differ, first %d)", bad, first);
    free(h);
    free(tempo);
    free(song);
    PokeyMachine* m = pkm_create(RATE);
    int mode, o, r, t;
    pkm_start(m, &g_song, 1, 8, SEEK_BUDGET, &mode);
    pkm_apply_image(m, &g_tempo);
    const int a = row_length(m);
    pkm_apply_image(m, &g_song);
    const int b = row_length(m);
    EXPECT(a == 9 && b == 6, "tempo edit behind the playhead: rows of %d ticks, undo %d", a, b);
    /* Cut the song to two orders while order 3 plays: clamped, then order 1 in order. */
    pkm_start(m, &g_song, 3, 5, SEEK_BUDGET, &mode);
    pkm_tick(m, g_out);
    pkm_apply_image(m, &g_short);
    pkm_position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 31, "clamped to the last row of the last order (%d/%d)", o, r);
    int ok = 1, rows_seen = 0, last_row = r;
    for (int k = 0; k < 6 * 10; ++k) {
        pkm_tick(m, g_out);
        pkm_position(m, &o, &r, &t);
        if (t == 0 && r != last_row) {
            if (o != 1 || r != (last_row + 1) % 32) ok = 0;
            last_row = r;
            ++rows_seen;
        }
    }
    EXPECT(ok && rows_seen >= 8, "then the loop order plays in order (%d rows)", rows_seen);
    /* Multispeed and machine applied live: the next tick's length. */
    pkm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    pkm_tick(m, g_out);
    const uint32_t c1 = pkm_tick_cycles(m);
    pkm_apply_image(m, &g_x2);
    const int f2 = pkm_tick(m, g_out);
    const uint32_t c2 = pkm_tick_cycles(m);
    pkm_apply_image(m, &g_pal);
    pkm_tick(m, g_out);
    const uint32_t c3 = pkm_tick_cycles(m);
    EXPECT(c1 == PK_FRAME_CYCLES_NTSC && c2 == PK_FRAME_CYCLES_NTSC / 2 && c3 == PK_FRAME_CYCLES_PAL && f2 >= 400 && f2 <= 401,
           "x2 then PAL live: %u -> %u -> %u cycles per tick", c1, c2, c3);
    pkm_destroy(m);
}

/* ---- audition and measured pitch ---------------------------------------------------------------------------- */

/* Auditions `slot` on `lane` and replays `ticks` ticks of its writes into r (edges and interrupts kept). */
static int audition_into(PokeyMachine* m, Replay* r, int lane, int note, int slot, int fine, int ticks, float* frames, long* nframes) {
    rep_clear(r);
    if (pkm_audition(m, &g_song, lane, note, slot, fine) != PKM_OK) return -1;
    rep_reset_batch(r, &g_song);
    Sink s;
    sink_init(&s);
    s.rep[0] = r;
    pkm_set_trace(m, sink_event, &s);
    long n = 0;
    for (int t = 0; t < ticks; ++t) {
        const int k = pkm_tick(m, frames ? frames + 2 * n : g_out);
        if (k <= 0) break;
        n += k;
    }
    rep_run(r, pkm_chip(m)->now);
    pkm_set_trace(m, NULL, NULL);
    if (nframes) *nframes = n;
    return 0;
}

/* Frequency of a pure tone on channel ch from its rising edges after `from`. */
static double tone_hz(const Replay* r, int ch, uint64_t from, double clock) {
    uint64_t first = 0, last = 0;
    long count = 0;
    int prev = 0;
    for (long i = 0; i < r->n; ++i) {
        const int nib = (r->edges[i].levels >> (4 * ch)) & 15;
        if (!prev && nib && r->edges[i].cycle >= from) {
            if (!count) first = r->edges[i].cycle;
            last = r->edges[i].cycle;
            ++count;
        }
        prev = nib;
    }
    return count > 1 ? (count - 1) * clock / (double)(last - first) : 0;
}

/* The smallest shift T mapping channel ch's edges after `from` onto themselves (a periodic pattern's period). */
static long pattern_period(const Replay* r, int ch, uint64_t from, uint64_t to) {
    long n = 0;
    uint64_t* t = (uint64_t*)malloc(sizeof(uint64_t) * (size_t)r->n);
    int* v = (int*)malloc(sizeof(int) * (size_t)r->n);
    int prev = -1;
    for (long i = 0; i < r->n; ++i) {
        const int nib = (r->edges[i].levels >> (4 * ch)) & 15;
        if (nib != prev && r->edges[i].cycle >= from && r->edges[i].cycle <= to) t[n] = r->edges[i].cycle, v[n++] = nib;
        prev = nib;
    }
    long period = -1;
    for (long j = 1; j < n && period < 0; ++j) {
        const uint64_t T = t[j] - t[0];
        if (t[0] + 3 * T > to) break;
        int ok = 1;
        long k = j;
        for (long i = 0; i < n && ok && t[i] + T <= t[n - 1]; ++i) {
            while (k < n && t[k] < t[i] + T) ++k;
            ok = k < n && t[k] == t[i] + T && v[k] == v[i];
        }
        if (ok) period = (long)T;
    }
    free(t);
    free(v);
    return period;
}

/* The difference sound's beat: its on-pulses' widths ramp and wrap once per beat. */
static double beat_hz(const Replay* r, int ch, uint64_t from, double clock) {
    uint64_t rise = 0, first = 0, last = 0;
    long wraps = 0, width = -1, max = 0;
    int prev = 0;
    for (long i = 0; i < r->n; ++i) {
        const int nib = (r->edges[i].levels >> (4 * ch)) & 15;
        if (r->edges[i].cycle >= from) {
            if (!prev && nib) rise = r->edges[i].cycle;
            if (prev && !nib && rise) {
                const long w = (long)(r->edges[i].cycle - rise);
                if (w > max) max = w;
                if (width >= 0 && w < width - max / 2) {
                    if (!wraps) first = r->edges[i].cycle;
                    last = r->edges[i].cycle;
                    ++wraps;
                }
                width = w;
            }
        }
        prev = nib;
    }
    return wraps > 1 ? (wraps - 1) * clock / (double)(last - first) : 0;
}

static void test_audition(const char* dir) {
    printf("audition\n");
    PokeyMachine* m = pkm_create(RATE);
    Replay* r = rep_new(0, 1);
    const PkPlayer* p = pkm_player(m);
    const double C = PK_CLOCK_NTSC;
    float* buf = (float*)malloc(sizeof(float) * 2 * PKM_MAX_TICK_FRAMES * 900);
    long frames = 0;
    /* A-4 on the 8-bit 64 kHz clock: AUDF 72, 437.8 Hz (the Altirra manual's number). */
    const double t0 = now_us();
    EXPECT(audition_into(m, r, 0, A4, PKT_LEAD, 0, 120, buf, &frames) == 0, "A-4 on the lead");
    const double setup = now_us() - t0;
    const double a4 = tone_hz(r, 0, 3000, C);
    EXPECT(p->lane[0].voice.counter == 72 && fabs(a4 - 437.81) < 0.05, "A-4 at 64 kHz: AUDF %d, %.3f Hz measured (437.8)",
           p->lane[0].voice.counter, a4);
    EXPECT(rms(buf + 2 * (frames - RATE / 2), RATE / 2) > 0.5 * rms(buf + 2 * (RATE / 2), RATE / 2), "held");
    EXPECT(pkm_release(m) == 1, "release phase");
    long tail = 0;
    int ticks = 0, n;
    while ((n = pkm_tick(m, buf + 2 * (frames + tail))) > 0 && ticks < 700) {
        tail += n;
        ++ticks;
    }
    EXPECT(n == 0 && ticks >= 4 && ticks <= 10, "release ends with the volume envelope's tail (%d ticks)", ticks);
    char path[512];
    snprintf(path, sizeof path, "%s/pokey_tone.wav", dir);
    write_wav(path, buf, frames + tail);
    printf("  A-4 %.3f Hz, release %d ticks, setup %.0f us\n", a4, ticks, setup);
    /* fine +64 = A#4 */
    audition_into(m, r, 0, A4, PKT_LEAD, 64, 60, NULL, NULL);
    const double up = tone_hz(r, 0, 3000, C), want_up = C / (2.0 * 28 * (p->lane[0].voice.counter + 1));
    EXPECT(p->lane[0].voice.counter == 68 && fabs(up - want_up) < 0.05, "fine +64: AUDF %d, %.3f Hz (A#4 466.16 nearest)",
           p->lane[0].voice.counter, up);
    /* A-4 on the fast 16-bit counter: within its resolution (one cycle in 2034) */
    audition_into(m, r, 1, A4, PKT_BASS16F, 0, 60, NULL, NULL);
    const double a16 = tone_hz(r, 1, 3000, C);
    EXPECT(fabs(a16 - 440.0) < 440.0 / 2034 && p->lane[1].voice.counter == 2027, "A-4 fast 16-bit: N %d, %.3f Hz",
           p->lane[1].voice.counter, a16);
    /* A-7 on the fast 8-bit counter */
    audition_into(m, r, 0, PKT_N(A, 7), PKT_FAST, 0, 30, NULL, NULL);
    const double a7 = tone_hz(r, 0, 3000, C), want7 = C / (2.0 * (p->lane[0].voice.counter + 4));
    EXPECT(fabs(a7 - want7) < 0.5 && fabs(a7 - 3520.0) < 3520.0 / 254, "A-7 fast 8-bit: N %d, %.1f Hz", p->lane[0].voice.counter, a7);
    /* Distortion C: the 15-step poly4 pattern, its period 15 fires */
    audition_into(m, r, 1, PKT_N(C, 4), PKT_BUZZ, 0, 60, NULL, NULL);
    const long period = 28L * (p->lane[1].voice.counter + 1);
    const long T = pattern_period(r, 1, 3000, 3000 + 40 * (uint64_t)period * 15);
    const double cents = 1200.0 * log2(C / (double)T / 261.626);
    EXPECT(T == 15 * period && fabs(cents) < 100, "distortion C on C-4: pattern period %ld cycles = 15 x %ld (%.0f cents)", T, period,
           cents);
    /* The difference "saw": the beat of the two counters' rates */
    audition_into(m, r, 1, PKT_N(C, 3), PKT_SAW, 0, 120, NULL, NULL);
    const int ns = p->lane[1].voice.counter, nc = p->lane[1].voice.clock_counter;
    const double want_saw = C / (28.0 * (ns + 1)) - C / (28.0 * (nc + 1));
    const double saw = beat_hz(r, 1, 3000, C);
    EXPECT(nc == ns + 3 && fabs(saw - want_saw) < 0.01 * want_saw && fabs(want_saw - 130.81) < 0.06 * 130.81,
           "difference saw C-3: counters %d/%d, beat %.2f Hz measured, %.2f expected", ns, nc, saw, want_saw);
    /* Samples: a once sample stops after its 600 levels, a looping one keeps going, each level one timer period. */
    audition_into(m, r, 3, PKT_N(C, 4), PKT_KICK, 0, 30, NULL, NULL);
    long fired = 0, bad_interval = 0;
    const long kp = 28L * (p->lane[3].voice.counter + 1);
    for (long i = 0; i < r->nirq; ++i) {
        if (r->irq_timers[i] & PK_IRQ_TIMER4) ++fired;
        if (i > 0 && i + 1 < r->nirq && (long)(r->irq_cycle[i] - r->irq_cycle[i - 1]) != kp) ++bad_interval;
    }
    EXPECT(fired == PKT_KICK_LEVELS && !p->lane[3].voice.playing && r->chip.audc[3] == 0 && !(r->chip.irqen & PK_IRQ_TIMER4),
           "once: %ld timer 4 interrupts for %d levels, then stopped (AUDC4 %02X)", fired, PKT_KICK_LEVELS, r->chip.audc[3]);
    EXPECT(bad_interval == 0 && fabs(C / kp - 7990) < 7990 * 0.07, "one level per %ld cycles = %.0f Hz (7990 within the 64 kHz steps)",
           kp, C / kp);
    EXPECT(pkm_release(m) == 0, "a stopped sample has nothing to release");
    audition_into(m, r, 0, PKT_N(C, 4), PKT_LOOPS, 0, 30, NULL, NULL);
    long loop_irqs = 0;
    bad_interval = 0;
    for (long i = 0; i < r->nirq; ++i) {
        if (r->irq_timers[i] & PK_IRQ_TIMER1) ++loop_irqs;
        if (i > 0 && (long)(r->irq_cycle[i] - r->irq_cycle[i - 1]) != PK_PCM_MIN_PERIOD) ++bad_interval;
    }
    EXPECT(p->lane[0].voice.playing && loop_irqs > 10 * PKT_LOOP_LEVELS && bad_interval == 0,
           "loop: still playing after %ld interrupts, one level per %d cycles (15980 Hz limited to a scan line)", loop_irqs,
           PK_PCM_MIN_PERIOD);
    /* OFF of a tone without a volume release point: silent at once. */
    audition_into(m, r, 0, PKT_N(A, 7), PKT_FAST, 0, 10, NULL, NULL);
    EXPECT(pkm_release(m) == 1 && pkm_tick(m, g_out) == 0, "no release point: silent at the next tick");
    EXPECT(pkm_audition(m, &g_song, 0, A4, PKT_LEAD, 0) == PKM_OK && pkm_release(m) == 0, "release before any tick: cut");
    EXPECT(pkm_audition(m, &g_song, 0, 48, PKT_BASS16, 0) == PKM_ERR_IMAGE, "16-bit on channel 1 rejected");
    EXPECT(pkm_audition(m, &g_song, 1, 48, PKT_FAST, 0) == PKM_ERR_IMAGE, "fast 8-bit on channel 2 rejected");
    EXPECT(pkm_audition(m, &g_song, 2, 48, PKT_KICK, 0) == PKM_ERR_IMAGE, "samples on channel 3 rejected");
    EXPECT(pkm_audition(m, &g_song, 1, 48, PKT_LOOPS, 0) == PKM_ERR_IMAGE, "fast samples on channel 2 rejected");
    EXPECT(pkm_audition(m, &g_song, 0, 48, PKT_HP16, 0) == PKM_ERR_IMAGE, "16-bit high-pass on channel 1 rejected");
    EXPECT(pkm_audition(m, &g_song, 0, 48, 14, 0) == PKM_ERR_IMAGE, "undefined slot rejected");
    EXPECT(pkm_audition(m, &g_song, 0, 108, PKT_LEAD, 0) == PKM_ERR_IMAGE, "note 108 rejected");
    EXPECT(pkm_audition(m, &g_song, 4, 48, PKT_LEAD, 0) == PKM_ERR_IMAGE, "channel 4 (lane 5) rejected");
    EXPECT(pkm_audition(m, &g_song, 0, 48, PKT_LEAD, 200) == PKM_ERR_IMAGE, "fine 200 rejected");
    free(buf);
    rep_free(r);
    pkm_destroy(m);
}

static void test_mute(void) {
    printf("mute\n");
    PokeyMachine* m = pkm_create(RATE);
    int mode;
    Sink plain, muted;
    sink_init(&plain);
    sink_init(&muted);
    for (int pass = 0; pass < 2; ++pass) {
        pkm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
        if (pass) pkm_mute(m, 1, 1), pkm_mute(m, 3, 1);
        pkm_set_trace(m, sink_event, pass ? &muted : &plain);
        for (int k = 0; k < TRACE_TICKS; ++k) pkm_tick(m, g_out);
        pkm_set_trace(m, NULL, NULL);
    }
    EXPECT(plain.hash == muted.hash && plain.ticks == muted.ticks, "muted lanes leave the player's writes unchanged");
    /* Order 2 rows 0-7: lane 1's 16-bit high-pass (POKEY 2) holds all four channels, the other lanes none. */
    float *x, *y, *z;
    pkm_start(m, &g_song, 2, 1, SEEK_BUDGET, &mode);
    const long fx = render(m, 30, &x);
    pkm_start(m, &g_song, 2, 1, SEEK_BUDGET, &mode);
    pkm_mute(m, 0, 1);
    pkm_mute(m, 2, 1);
    pkm_mute(m, 3, 1);
    const long fy = render(m, 30, &y);
    EXPECT(fx == fy && !memcmp(x, y, sizeof(float) * 2 * (size_t)fx) && rms(x, fx) > 0.005,
           "lanes holding no channel muted: output unchanged (rms %.4f)", rms(x, fx));
    pkm_start(m, &g_song, 2, 1, SEEK_BUDGET, &mode);
    pkm_mute(m, 1, 1);
    const long fz = render(m, 30, &z);
    EXPECT(rms(z + 2 * (RATE / 10), fz - RATE / 10) < 0.0005, "the lane holding them muted: silent after 100 ms (rms %.6f)",
           rms(z + 2 * (RATE / 10), fz - RATE / 10));
    free(x);
    free(y);
    free(z);
    pkm_start(m, &g_song, 1, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 30; ++k) pkm_tick(m, g_out);
    for (int c = 0; c < 4; ++c) pkm_mute(m, c, 1);
    float* buf;
    long frames = render(m, 30, &buf);
    EXPECT(rms(buf + 2 * (RATE / 10), frames - RATE / 10) < 0.0005, "all lanes muted: rms %.6f after 100 ms",
           rms(buf + 2 * (RATE / 10), frames - RATE / 10));
    free(buf);
    for (int c = 0; c < 4; ++c) pkm_mute(m, c, 0);
    frames = render(m, 30, &buf);
    EXPECT(rms(buf, frames) > 0.005, "unmuted: resumes mid-note (rms %.4f)", rms(buf, frames));
    free(buf);
    /* A mute applied between start and the first tick holds. */
    pkm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    for (int c = 0; c < 4; ++c) pkm_mute(m, c, 1);
    frames = render(m, 60, &buf);
    EXPECT(rms(buf, frames) < 0.0005, "muted before the first tick: rms %.6f", rms(buf, frames));
    free(buf);
    EXPECT(pkm_mute(m, 4, 1) == PKM_ERR_IMAGE, "lane 4 rejected");
    pkm_destroy(m);
}

/* ---- sticky portamento over samples --------------------------------------------------------------------------- */

/* One order of 24 rows at speed 6; every lane plays C-4 with 310 on row 0, then E-4 on row 8 and G-4 on row 16
 * without a sound number. POKEY 1: a 200-level one-shot (~25 ms at 7990 Hz, over long before row 8); POKEY 2: an
 * 8000-level one-shot (~1 s: still playing at row 8, over by row 16); POKEY 3: a tone whose one-shot volume envelope
 * ends at 0 (still held); POKEY 4: the 200 levels looping. Levels alternate 3 and 12, so every interrupt writes AUDC. */
#define PORTA_ROWS 24
#define PORTA_SPEED 6
#define PORTA_SHORT 200
#define PORTA_LONG 8000
enum { PORTA_ONCE = 1, PORTA_LONG_ONCE, PORTA_LOOPED, PORTA_TONE };

static void porta_instrument(uint8_t* b, int slot, int flags, int sample, int loop, int volume) {
    uint8_t* p = b + PK_OFF_INSTRUMENTS + PK_INSTRUMENT_SIZE * (slot - 1);
    p[PK_I_FLAGS] = (uint8_t)(PK_FLAG_DEFINED | flags);
    if (flags & PK_FLAG_SAMPLE) {
        p[PK_I_SAMPLE] = (uint8_t)sample;
        p[PK_I_ROOT] = PKT_N(C, 4);
        pkt_u16(p + PK_I_RATE, 7990);
    }
    pkt_u16(p + PK_I_LOOP, loop);
    pkt_u16(p + PK_I_ENV_VOLUME, volume);
    for (int k = 1; k < PKP_ENVS; ++k) pkt_u16(p + PK_I_ENV_VOLUME + 2 * k, PK_ENV_NONE);
}

static void build_porta(PokeyImage* image) {
    static const int fade[] = {15, 8, 0};
    static const int slot[PK_CHANNELS] = {PORTA_ONCE, PORTA_LONG_ONCE, PORTA_TONE, PORTA_LOOPED};
    static const int notes[3] = {PKT_N(C, 4), PKT_N(E, 4), PKT_N(G, 4)};
    uint8_t* b = image->bytes;
    memset(b, 0, PK_IMAGE_SIZE);
    memcpy(b, "TTPK", 4);
    b[PK_H_LAYOUT] = PK_LAYOUT;
    b[PK_H_MACHINE] = PK_MACHINE_NTSC;
    b[PK_H_MULTISPEED] = 1;
    b[PK_H_SPEED] = PORTA_SPEED;
    pkt_u16(b + PK_H_ORDERS, 1);
    b[PK_H_INSTRUMENTS] = PORTA_TONE;
    b[PK_OFF_ROWS] = PORTA_ROWS;
    PktOut o = {b, 0};
    const int env = pkt_env(&o, fade, 3, PK_ENV_NONE, PK_ENV_NONE);
    for (int lane = 0; lane < PK_CHANNELS; ++lane) {
        pkt_u16(b + PK_OFF_ORDERS + 2 * lane, o.at);
        for (int k = 0; k < 3; ++k) {
            pkt_put(&o, k ? 0x01 : 0x01 | 0x02 | (1 << 2)); /* the note; on row 0 also the sound and 310 */
            pkt_put(&o, notes[k]);
            if (!k) pkt_put(&o, slot[lane]), pkt_put(&o, PK_FX_PORTAMENTO), pkt_put(&o, 0x10);
            pkt_put(&o, 0x80 | 6); /* 7 empty rows */
        }
    }
    pkt_u16(b + PK_H_DATA_SIZE, o.at);
    const int lengths[2] = {PORTA_SHORT, PORTA_LONG};
    int bytes = 0;
    for (int k = 0; k < 2; ++k) {
        uint8_t* e = b + PK_OFF_SAMPLES + PK_SAMPLE_ENTRY_SIZE * k;
        pkt_u16(e, bytes);
        pkt_u16(e + 2, lengths[k]);
        memset(b + PK_OFF_SAMPLE_DATA + bytes, 0xC3, (size_t)lengths[k] / 2); /* 3, 12, 3, ... (low nibble first) */
        bytes += lengths[k] / 2;
    }
    pkt_u16(b + PK_H_SAMPLE_SIZE, bytes);
    b[PK_H_SAMPLES] = 2;
    porta_instrument(b, PORTA_ONCE, PK_FLAG_SAMPLE, 0, 0xFFFF, PK_ENV_NONE);
    porta_instrument(b, PORTA_LONG_ONCE, PK_FLAG_SAMPLE, 1, 0xFFFF, PK_ENV_NONE);
    porta_instrument(b, PORTA_LOOPED, PK_FLAG_SAMPLE, 0, 0, PK_ENV_NONE);
    porta_instrument(b, PORTA_TONE, 0, 0, 0xFFFF, env);
    const int rc = pk_load_image(image);
    EXPECT(rc == 0, "portamento image loads: %s at %d", image->info.problem ? image->info.problem : "", image->info.problem_offset);
}

typedef struct PortaLog {
    Sink trace;                /* pokey_trace_porta.txt, for tools/audio/pokey_reference_check.py */
    int key_audc1[PORTA_ROWS]; /* AUDC1 the row's tick-0 batch wrote, -1 none */
    int key_irqen[PORTA_ROWS]; /* IRQEN the row's tick-0 batch wrote, -1 none */
    int levels[PORTA_ROWS];    /* non-zero AUDC1 writes of the row's interrupts */
    int stops[PORTA_ROWS];     /* AUDC1 = 0 writes of the row's interrupts */
} PortaLog;

static void porta_event(void* ctx, const PkmEvent* e) {
    PortaLog* l = (PortaLog*)ctx;
    sink_event(&l->trace, e);
    if (e->order != 0 || e->row < 0 || e->row >= PORTA_ROWS) return;
    for (int k = 0; k < e->count; ++k) {
        const int reg = e->writes[k].reg, val = e->writes[k].val;
        if (e->irq) {
            if (reg == PK_AUDC1) ++*(val ? &l->levels[e->row] : &l->stops[e->row]);
        } else if (e->tick == 0) {
            if (reg == PK_AUDC1) l->key_audc1[e->row] = val;
            if (reg == PK_IRQEN) l->key_irqen[e->row] = val;
        }
    }
}

/* A one-shot sample the interrupt routine ran to its end is no longer held: the next note keys on afresh even under a
 * sticky 3xx. A still-playing or looping sample and a synthesized voice whose envelope has ended only glide. */
static void test_porta_samples(const char* dir) {
    printf("sticky portamento over samples\n");
    static PortaLog pl;
    memset(&pl, 0, sizeof pl);
    sink_init(&pl.trace);
    for (int r = 0; r < PORTA_ROWS; ++r) pl.key_audc1[r] = pl.key_irqen[r] = -1;
    char path[512];
    snprintf(path, sizeof path, "%s/pokey_trace_porta.txt", dir);
    pl.trace.f = fopen(path, "w");
    write_file(dir, "pokey_porta.bin", g_porta.bytes, sizeof g_porta.bytes);
    PokeyMachine* m = pkm_create(RATE);
    int mode;
    EXPECT(pkm_start(m, &g_porta, 0, 0, SEEK_BUDGET, &mode) == PKM_OK, "start");
    pkm_set_trace(m, porta_event, &pl);
    const PkPlayer* p = pkm_player(m);
    PkLane at8[PK_CHANNELS], at16[PK_CHANNELS];
    for (int t = 0; t < PORTA_ROWS * PORTA_SPEED; ++t) {
        pkm_tick(m, g_out);
        if (t == 8 * PORTA_SPEED) memcpy(at8, p->lane, sizeof at8);
        if (t == 16 * PORTA_SPEED) memcpy(at16, p->lane, sizeof at16);
    }
    pkm_set_trace(m, NULL, NULL);
    if (pl.trace.f) fclose(pl.trace.f);
    /* POKEY 1: each note restarts the sample: AUDC1 at its first level and timer 1 enabled in the row's tick-0 batch,
     * then every further level through timer 1 interrupts and the stop. */
    for (int r = 0; r < PORTA_ROWS; r += 8) {
        EXPECT(pl.key_audc1[r] == (PK_AUDC_VOLUME_ONLY | 3) && pl.key_irqen[r] >= 0 && (pl.key_irqen[r] & PK_IRQ_TIMER1),
               "row %d: POKEY 1 keys its one-shot on (tick 0 writes AUDC1 %02X, IRQEN %02X; -1 = none)", r, pl.key_audc1[r],
               pl.key_irqen[r]);
        EXPECT(pl.levels[r] == PORTA_SHORT - 1 && pl.stops[r] == 1,
               "row %d: %d interrupt levels and %d stop (want %d and 1)", r, pl.levels[r], pl.stops[r], PORTA_SHORT - 1);
    }
    int stray = 0;
    for (int r = 0; r < PORTA_ROWS; ++r)
        if (r % 8) stray += pl.levels[r] + pl.stops[r] + (pl.key_audc1[r] >= 0);
    EXPECT(stray == 0, "POKEY 1 writes nothing between its notes (%d AUDC1 writes)", stray);
    const int e4 = 64 * PKT_N(E, 4), g4 = 64 * PKT_N(G, 4);
    EXPECT(at8[0].base == e4 && at8[0].target == e4 && at16[0].base == g4 && at16[0].target == g4,
           "POKEY 1 keyed on at E-4 and G-4, not gliding (row 8 base %d target %d, row 16 base %d target %d)", at8[0].base,
           at8[0].target, at16[0].base, at16[0].target);
    /* POKEY 2: still playing at row 8 (the note glides on), over by row 16 (keyed on afresh). */
    EXPECT(at8[1].voice.playing && at8[1].voice.index > PORTA_LONG / 2 && at8[1].target == e4 && at8[1].base < e4,
           "row 8: the playing one-shot glides on (index %d, base %d, target %d)", at8[1].voice.index, at8[1].base, at8[1].target);
    EXPECT(at16[1].voice.playing && at16[1].voice.index < PORTA_LONG / 2 && at16[1].base == g4 && at16[1].target == g4,
           "row 16: the ended one-shot restarts (index %d, base %d, target %d)", at16[1].voice.index, at16[1].base, at16[1].target);
    /* POKEY 3 (the tone, its envelope at 0) and POKEY 4 (looping) stay held: both later notes glide. */
    for (int l = 2; l < PK_CHANNELS; ++l) {
        const int sample = l == 3;
        EXPECT(at8[l].voice.active && !at8[l].voice.released && at8[l].target == e4 && at8[l].base < e4 && at16[l].target == g4 &&
                   at16[l].base < g4 && (sample ? at8[l].voice.playing && at16[l].voice.playing : at8[l].voice.level == 0),
               "POKEY %d held: notes glide (row 8 base %d target %d, row 16 base %d target %d)", l + 1, at8[l].base, at8[l].target,
               at16[l].base, at16[l].target);
    }
    /* Audible: POKEY 1 alone (the other lanes muted) sounds on rows 0, 8 and 16 and is silent before 8 and 16. */
    pkm_start(m, &g_porta, 0, 0, SEEK_BUDGET, &mode);
    for (int c = 1; c < PK_CHANNELS; ++c) pkm_mute(m, c, 1);
    double row_rms[PORTA_ROWS];
    for (int r = 0; r < PORTA_ROWS; ++r) {
        float* buf;
        const long n = render(m, PORTA_SPEED, &buf);
        row_rms[r] = rms(buf, n);
        free(buf);
    }
    EXPECT(row_rms[0] > 0.002 && row_rms[8] > 0.002 && row_rms[16] > 0.002, "POKEY 1 audible on rows 0, 8, 16 (rms %.4f %.4f %.4f)",
           row_rms[0], row_rms[8], row_rms[16]);
    EXPECT(row_rms[7] < 0.0005 && row_rms[15] < 0.0005, "and silent on rows 7 and 15 (rms %.6f %.6f)", row_rms[7], row_rms[15]);
    pkm_destroy(m);
}

static void test_multispeed(void) {
    printf("multispeed\n");
    PokeyMachine* m = pkm_create(RATE);
    int mode, o, r, t, frames = 0;
    uint64_t cycles = 0;
    pkm_start(m, &g_x2, 0, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 120; ++k) {
        frames += pkm_tick(m, g_out);
        cycles += pkm_tick_cycles(m);
    }
    pkm_position(m, &o, &r, &t);
    const double want = 60.0 * PK_FRAME_CYCLES_NTSC * RATE / PK_CLOCK_NTSC;
    EXPECT(cycles == 60ull * PK_FRAME_CYCLES_NTSC, "120 ticks at x2 = 60 frames of cycles (%llu)", (unsigned long long)cycles);
    EXPECT(fabs(frames - want) <= 2, "= %.1f host frames (%d)", want, frames);
    EXPECT(o == 1 && r == 4 && t == 0, "x2: rows of 6 fast ticks: 20 rows in 120 ticks (%d/%d/%d)", o, r, t);
    pkm_destroy(m);
}

/* ---- VGM ------------------------------------------------------------------------------------------------------ */

static uint32_t u32(const uint8_t* p) { return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24; }

static void test_vgm(const char* dir) {
    printf("VGM export\n");
    static PkVgmScratch scratch;
    pk_init(&scratch.chip, NULL, NULL);
    PkVgmInfo info;
    const double t0 = now_us();
    pk_vgm_render(&g_song, 36000, &scratch, NULL, 0, &info);
    const size_t cap = info.size;
    uint8_t* buf = (uint8_t*)malloc(cap);
    const size_t size = pk_vgm_render(&g_song, 36000, &scratch, buf, cap, &info);
    const double us = now_us() - t0;
    EXPECT(size == cap && size > 0x100, "renders (%zu bytes)", size);
    EXPECT(pk_vgm_render(&g_song, 36000, &scratch, buf, 1000, &info) == 0, "too small a buffer reports 0");
    pk_vgm_render(&g_song, 36000, &scratch, buf, cap, &info);
    EXPECT(!memcmp(buf, "Vgm ", 4) && u32(buf + 4) == size - 4 && u32(buf + 8) == 0x171 && u32(buf + 0x24) == 60 &&
               u32(buf + 0x34) == 0xCC && u32(buf + 0xB0) == PK_VGM_CLOCK_NTSC && u32(buf + 0x14) == 0,
           "header: version 1.71, 60 Hz, data at 0x100, POKEY clock %u", u32(buf + 0xB0));
    /* The machine's writes over the same ticks, as the VGM logs them: the reset batch, the loop dump, every batch. */
    Log want = {0};
    static PkPlayer reset;
    pkp_reset(&reset, &g_song);
    for (int i = 0; i < reset.writes; ++i)
        if (reset.write[i].reg != PK_IRQEN) log_add(&want, 0, reset.write[i].reg, reset.write[i].val);
    Sink s;
    sink_init(&s);
    s.log = &want;
    PokeyMachine* m = pkm_create(RATE);
    int mode;
    pkm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    pkm_set_trace(m, sink_event, &s);
    const PkPlayer* p = pkm_player(m);
    long dump_at = -1;
    for (uint32_t t = 0; t < info.ticks; ++t) {
        if (dump_at < 0 && p->order == g_song.info.loop && p->row == 0 && p->tick == 0) {
            dump_at = want.n;
            const uint64_t c = pkm_chip(m)->now;
            log_add(&want, c, PK_SKCTL, p->shadow[PK_SKCTL]);
            log_add(&want, c, PK_AUDCTL, p->shadow[PK_AUDCTL]);
            for (int reg = PK_AUDF1; reg <= PK_AUDC4; ++reg) log_add(&want, c, reg, p->shadow[reg]);
        }
        pkm_tick(m, g_out);
    }
    const uint64_t end = pkm_chip(m)->now;
    pkm_destroy(m);
    /* Parse the file from the format alone. */
    uint64_t samples = 0;
    long k = 0, mismatches = 0, first = -1, loop_entry = -1;
    size_t at = 0x100;
    int ended = 0, bad_command = 0;
    const size_t loop_offset = 0x1C + u32(buf + 0x1C);
    while (at < size && !ended && !bad_command) {
        if (at == loop_offset) loop_entry = k;
        const int cmd = buf[at];
        if (cmd == 0xBB) {
            const int reg = buf[at + 1], val = buf[at + 2];
            if (k >= want.n || reg != want.reg[k] || val != want.val[k] || samples != pk_vgm_samples(PK_MACHINE_NTSC, want.cycle[k])) {
                if (first < 0) first = k;
                ++mismatches;
            }
            ++k;
            at += 3;
        } else if (cmd == 0x61) {
            samples += buf[at + 1] | buf[at + 2] << 8;
            at += 3;
        } else if (cmd == 0x62) {
            samples += 735, ++at;
        } else if (cmd == 0x63) {
            samples += 882, ++at;
        } else if (cmd >= 0x70 && cmd <= 0x7F) {
            samples += (uint64_t)(cmd & 15) + 1, ++at;
        } else if (cmd == 0x66) {
            ended = 1, ++at;
        } else {
            bad_command = 1;
        }
    }
    EXPECT(ended && !bad_command && at == size, "commands parse to 0x66 at the end of the file");
    EXPECT(samples == u32(buf + 0x18) && samples == pk_vgm_samples(PK_MACHINE_NTSC, end), "waits sum to the header's %u samples",
           u32(buf + 0x18));
    EXPECT(k == want.n && mismatches == 0, "the VGM's %ld writes = the machine's (%ld differ, first %ld; %ld expected)", k, mismatches,
           first, want.n);
    EXPECT(loop_entry == dump_at && info.loop_offset == loop_offset && u32(buf + 0x20) == info.loop_samples && info.loop_samples > 0,
           "the loop point is the register dump before the loop order");
    write_file(dir, "pokey_song.vgm", buf, size);
    write_file(dir, "pokey_song.bin", g_song.bytes, sizeof g_song.bytes);
    write_file(dir, "pokey_song_pal.bin", g_pal.bytes, sizeof g_pal.bytes);
    printf("  %zu bytes, %u ticks, %ld writes, loop %u samples (%.2f s), rendered in %.0f us\n", size, info.ticks, k,
           info.loop_samples, info.loop_samples / 44100.0, us);
    /* PAL: 50 Hz, its clock and its sample scale */
    pk_vgm_render(&g_pal, 36000, &scratch, NULL, 0, &info);
    uint8_t* pal = (uint8_t*)malloc(info.size);
    pk_vgm_render(&g_pal, 36000, &scratch, pal, info.size, &info);
    EXPECT(u32(pal + 0x24) == 50 && u32(pal + 0xB0) == PK_VGM_CLOCK_PAL &&
               u32(pal + 0x18) == pk_vgm_samples(PK_MACHINE_PAL, (uint64_t)info.ticks * PK_FRAME_CYCLES_PAL),
           "PAL: 50 Hz, clock %u, %u samples for %u ticks", u32(pal + 0xB0), u32(pal + 0x18), info.ticks);
    free(pal);
    log_free(&want);
    free(buf);
}

/* The hosts' entry point (pokey_export.h): the VGM plus a GD3 tag. */
static void test_export(void) {
    printf("VGM export entry point\n");
    static PkVgmScratch scratch;
    pk_init(&scratch.chip, NULL, NULL);
    PkVgmInfo info;
    pk_vgm_render(&g_song, 36000, &scratch, NULL, 0, &info);
    const size_t body_size = info.size;
    uint8_t* body = (uint8_t*)malloc(body_size);
    pk_vgm_render(&g_song, 36000, &scratch, body, body_size, &info);
    const uint64_t body_hash = fnv(FNV_BASIS, body + 8, body_size - 8);
    size_t size = 0;
    const char* problem = NULL;
    const char* title = "Scanline \xC3\x96pening \xF0\x9F\x8E\xB9"; /* 2-byte and 4-byte UTF-8 */
    uint8_t* vgm = pk_export_vgm(g_song.bytes, sizeof g_song.bytes, 36000, title, "Esko", "2026/11/08", &size, &problem);
    EXPECT(vgm && !problem, "renders (%s)", problem ? problem : "ok");
    if (!vgm) {
        free(body);
        return;
    }
    const uint32_t gd3_at = 0x14 + u32(vgm + 0x14);
    EXPECT(gd3_at == body_size && 4 + u32(vgm + 4) == size, "GD3 after the data (at %u), EOF offset = size %zu", gd3_at, size);
    memset(vgm + 0x14, 0, 4);
    EXPECT(fnv(FNV_BASIS, vgm + 8, body_size - 8) == body_hash, "the data = pk_vgm_render's (header 0x04/0x14 aside)");
    const uint8_t* g = vgm + gd3_at;
    const uint32_t len = u32(g + 8);
    EXPECT(!memcmp(g, "Gd3 ", 4) && g[4] == 0 && g[5] == 1 && gd3_at + 12 + len == size, "GD3 1.00 header, length %u", len);
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
    EXPECT(!strcmp(fields[0], title) && !strcmp(fields[4], "Atari 8-bit POKEY (Thor Tracker)") && !strcmp(fields[6], "Esko") &&
               !strcmp(fields[8], "2026/11/08") && !strcmp(fields[9], "Thor Tracker") && !fields[2][0] && !fields[10][0],
           "GD3 fields: '%s', '%s', '%s', '%s', '%s'", fields[0], fields[4], fields[6], fields[8], fields[9]);
    pk_export_free(vgm);
    free(body);
    memcpy(g_bad.bytes, g_song.bytes, sizeof g_bad.bytes);
    g_bad.bytes[PK_H_MULTISPEED] = 9;
    vgm = pk_export_vgm(g_bad.bytes, sizeof g_bad.bytes, 36000, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && size == 0 && problem && strstr(problem, "multispeed"), "bad image: NULL, '%s'", problem ? problem : "");
    vgm = pk_export_vgm(g_song.bytes, 100, 36000, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && problem && !strcmp(problem, "image size"), "short image: '%s'", problem ? problem : "");
    vgm = pk_export_vgm(g_song.bytes, sizeof g_song.bytes, 100, NULL, NULL, NULL, &size, &problem);
    EXPECT(vgm && u32(vgm + 0x1C) == 0 && u32(vgm + 0x20) == 0 && size > 0x100, "tick limit before the loop closes: no loop");
    if (vgm) {
        const uint8_t* d = vgm + 0x14 + u32(vgm + 0x14) + 12;
        for (int f = 0; f < 8; ++f, d += 2)
            while (d[0] | d[1]) d += 2;
        char date[16] = {0};
        for (int i = 0; i < 15 && (d[2 * i] | d[2 * i + 1]); ++i) date[i] = (char)d[2 * i];
        EXPECT(strlen(date) == 10 && date[4] == '/' && date[7] == '/' && !strncmp(date, "20", 2), "default date today: %s", date);
    }
    pk_export_free(vgm);
}

static void test_ops(void) {
    printf("ops table\n");
    const ChipEngineOps* ops = chip_engine_ops("atari-pokey");
    EXPECT(ops && !strcmp(ops->system_id, "atari-pokey"), "registry");
    if (!ops) return;
    EXPECT(ops->image_size == PK_IMAGE_SIZE && ops->image_storage >= ops->image_size, "sizes");
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
    const double want = 60.0 * PK_FRAME_CYCLES_NTSC * RATE / PK_CLOCK_NTSC;
    EXPECT(fabs(frames - want) <= 2 && n >= 801 && n <= 802, "60 ticks = %.1f frames (%d)", want, frames);
    ops->position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 14 && t == 0, "10 rows later (%d/%d/%d)", o, r, t);
    ops->apply_image(m, image);
    EXPECT(ops->mute(m, 0, 1) == CHIP_OK && ops->mute(m, 0, 0) == CHIP_OK, "mute");
    EXPECT(ops->audition(m, image, 0, A4, PKT_LEAD, 0) == CHIP_OK, "audition");
    ops->tick(m, out);
    EXPECT(ops->release(m) == 1, "release phase");
    int ticks = 0;
    while (ops->tick(m, out) > 0 && ticks < 1000) ++ticks;
    EXPECT(ticks < 1000, "released note ends (%d ticks)", ticks);
    EXPECT(ops->audition(m, image, 2, A4, PKT_KICK, 0) == CHIP_ERR_IMAGE, "samples on channel 3: CHIP_ERR_IMAGE");
    ops->destroy(m);
    free(image);
}

/* Interrupts delivered in the current tick (test_costs' trace hook). */
static void count_interrupts(void* ctx, const PkmEvent* event) {
    if (event->irq) ++*(int*)ctx;
}

static int compare_double(const void* a, const void* b) {
    const double x = *(const double*)a, y = *(const double*)b;
    return x < y ? -1 : x > y;
}

/* Tick cost has no slow path. POKEY ticks are not flat by design (the event-driven chip's work follows the counters'
 * fire rates, and a sample at the 114-cycle minimum adds 262 interrupt routines to a tick), so after 1 s of warm-up,
 * over 30 s of the song (thread CPU time): no tick above 3x the 99th percentile (an isolated slow path) and no tick
 * above a quarter of the tick's real time (16.7 ms NTSC). */
static void test_costs(void) {
    printf("costs\n");
    const int ticks = 1800;
    double* cpu = (double*)malloc(sizeof(double) * (size_t)ticks);
    int* irqs = (int*)malloc(sizeof(int) * (size_t)ticks);
    PokeyMachine* m = pkm_create(RATE);
    int mode, irq_count = 0;
    pkm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    pkm_set_trace(m, count_interrupts, &irq_count);
    for (int k = 0; k < 60; ++k) pkm_tick(m, g_out);
    for (int k = 0; k < ticks; ++k) {
        irq_count = 0;
        const double c0 = cpu_us();
        pkm_tick(m, g_out);
        cpu[k] = cpu_us() - c0;
        irqs[k] = irq_count;
    }
    pkm_set_trace(m, NULL, NULL);
    double* sorted = (double*)malloc(sizeof(double) * (size_t)ticks);
    memcpy(sorted, cpu, sizeof(double) * (size_t)ticks);
    qsort(sorted, (size_t)ticks, sizeof(double), compare_double);
    const double p99 = sorted[ticks - 1 - ticks / 100], worst = sorted[ticks - 1];
    double sum = 0;
    long busy = 0, all_irqs = 0;
    int worst_at = 0;
    for (int k = 0; k < ticks; ++k) {
        sum += cpu[k];
        if (irqs[k]) ++busy, all_irqs += irqs[k];
        if (cpu[k] == worst) worst_at = k;
    }
    const double tick_us = 1e6 * PK_FRAME_CYCLES_NTSC / PK_CLOCK_NTSC;
    EXPECT(worst < 3 * p99, "no isolated slow tick: worst %.1f us (tick %d, %d interrupts) vs p99 %.1f us", worst, worst_at,
           irqs[worst_at], p99);
    EXPECT(worst < tick_us / 4, "every tick within a quarter of its %.0f us: worst %.1f us", tick_us, worst);
    printf("  thread CPU per tick: mean %.1f us, p99 %.1f us, worst %.1f us (tick %d); %ld interrupts in %ld ticks\n",
           sum / ticks, p99, worst, worst_at, all_irqs, busy);
    free(sorted);
    free(irqs);
    double t0 = now_us();
    pkm_start(m, &g_song, 3, 20, SEEK_BUDGET, &mode);
    const double seek = now_us() - t0;
    PkPlayer* p = (PkPlayer*)malloc(sizeof *p);
    pkp_reset(p, &g_song);
    t0 = now_us();
    for (int k = 0; k < 12000; ++k) pkp_tick(p, &g_song);
    const double alone = now_us() - t0;
    printf("  seek to 3/20: %.0f us; player alone %.2f us per tick; 12000 player ticks %.1f ms\n", seek, alone / 12000, alone / 1000);
    free(p);
    free(cpu);
    pkm_destroy(m);
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    build(&g_song, (PkSongOptions){PK_MACHINE_NTSC, 1, 0, 0, 0, 0});
    build(&g_pal, (PkSongOptions){PK_MACHINE_PAL, 1, 0, 0, 0, 0});
    build(&g_moved, (PkSongOptions){PK_MACHINE_NTSC, 1, 1, 0, 0, 0});
    build(&g_edited, (PkSongOptions){PK_MACHINE_NTSC, 1, 0, 1, 0, 0});
    build(&g_tempo, (PkSongOptions){PK_MACHINE_NTSC, 1, 0, 0, 1, 0});
    build(&g_short, (PkSongOptions){PK_MACHINE_NTSC, 1, 0, 0, 0, 1});
    build(&g_x2, (PkSongOptions){PK_MACHINE_NTSC, 2, 0, 0, 0, 0});
    build_porta(&g_porta);
    EXPECT(memcmp(g_song.bytes, g_moved.bytes, PK_IMAGE_SIZE) != 0, "the re-laid-out image differs in its bytes");
    test_chip(dir);
    test_images();
    test_trace(dir, &g_song, "pokey_trace.txt");
    test_trace(dir, &g_pal, "pokey_trace_pal.txt");
    test_audio(dir);
    test_loop();
    test_seek();
    test_live_edits();
    test_audition(dir);
    test_mute();
    test_porta_samples(dir);
    test_multispeed();
    test_vgm(dir);
    test_export();
    test_ops();
    test_costs();
    printf("POKEY: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
