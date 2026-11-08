/* Host regression test for the WonderSwan systems ("ws-wonderswan", "ws-wonderswan-color"): Thor Tracker's own player
 * (ws/ws_player.c) on the sound unit (ws/ws_apu.c, a port of ares's APU), CPU-fed samples streamed from the voice
 * timer, Sound DMA and Hyper Voice on the Color, playing the reference songs of tools/audio/ws/ws_song.py
 * (ws_test_song.h, from tools/audio/prepare_ws.py), plus small images built here for measurements.
 *
 * Checks: the sound unit's closed-form stepping against the specification's per-cycle loop for random register
 * programs on both models; a channel steps every 2048 - d cycles; the eight LFSR sequence lengths; the sweep's
 * 8192-cycle prescaler, a step every time + 1 prescaler ticks, the $8D reset, no sweep without channel 3 enabled; the
 * $94 bit order; Hyper Voice conversion, update divisors, the stereo flip-flop, its $6B reset and $69 ignoring the
 * channel mode; Sound DMA rates (6, 4, 2, 1 blocks), hold, loop, one-shot end, the failing enable with length 0,
 * backwards; the headphone adder's 16-bit wrap and the speaker's 8-bit wrap and shift; mute changing no state and
 * unmute continuing sample-exactly. The period table against its formula, A-4 = N 218 (d 1830), the clamps, the
 * sample step's octaves. Image validation (every reference corruption rejected). Register writes, positions and timer
 * interrupts identical to the independent Python reference player (tools/audio/ws/ws_ref.py) in each of
 * WS_TEST_TICKS ticks of every reference song: the reset batch, tick batches and every timer interrupt batch with its
 * cycle. Deterministic, non-silent output; A-4 measured from the rendered audio. Seeks landing on exact rows with the
 * reference's writes afterwards and bit-identical sound from the target (a looping DMA sample and a CPU-fed sample
 * keyed long before the target included), and the order fallback. Live edits before every tick (the same image; a
 * re-laid image alternating with the original: DMA rebased onto the moved identical bytes), a DMA sample whose bytes
 * changed stopping at the next tick, a changed loaded wave reloading, header shared state. Audition (pitch, fine,
 * release, a one-shot CPU-fed sample's end, a one-shot DMA sample's end, lane rules, argument limits). Mute through
 * the machine. VGM bytes identical to the reference's, the Hyper Voice refusal, the hosts' entry point and its GD3
 * tag. The Kotlin-generated images (ws-demo.wsimg, wsc-demo.wsimg, ws-shortest.wsimg, wsc-shortest.wsimg): load, play,
 * seek determinism, VGM. The two ops tables as the hosts drive them.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir] (the Kotlin images are read
 * from argv[2], default app/build/samples: run `tools/build.sh exportSamples` first). Writes into the evidence dir:
 * ws_song.wav, wsc_song.wav, ws_tone.wav (48 kHz stereo, 10 s), ws_trace.txt and wsc_trace.txt (the reset batch, per
 * tick its writes, timer interrupts and DMA transfers with cycles), ws_song.vgm, wsc_vgm.vgm, ws_tone.vgm (no GD3) and
 * ws_song.bin, wsc_song.bin, wsc_vgm.bin, ws_tone.bin (the images): the inputs of
 * tools/audio/ws/ws_reference_check.py. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "chip_engine.h"
#include "ws_apu.h"
#include "ws_export.h"
#include "ws_machine.h"
#include "ws_test_song.h"
#include "ws_vgm.h"

#ifndef WS_TEST_VGM_MAX_TICKS
#define WS_TEST_VGM_MAX_TICKS 45283
#endif

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
#define A4 57
#define SEEK_BUDGET WSM_SEEK_TICKS
#define TICKS_PER_SECOND ((double)WS_CPU_HZ / WS_TICK_CYCLES)
#define EVIDENCE_TICKS 755 /* 10 s */

static float g_out[WSM_MAX_TICK_FRAMES * 2];

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

/* ---- the reference traces (ws_test_song.h), read through accessors whatever the generated element types ---------- */

typedef int (*Get)(int i);

typedef struct Ref {
    const char* name;
    int model;
    const unsigned char* image;
    size_t image_size;
    Get offsets, space, addr, vals, pos, irqs, ifirst, iwfirst, idelay, icount, ispace, iaddr, ivals;
    int reset_n;
    Get reset_space, reset_addr, reset_vals;
    int corrupt_n;
    Get corrupt_off, corrupt_val;
} Ref;

/* The generated sizes and counts may be const variables rather than macros: the structs are filled at run time. */
#define ACC(N, f) \
    static int N##_get_##f(int i) { return (int)N##_##f[i]; }
#define DEFINE_REF(N, model)                                                                                            \
    ACC(N, offsets) ACC(N, space) ACC(N, addr) ACC(N, vals) ACC(N, pos) ACC(N, irqs) ACC(N, ifirst) ACC(N, iwfirst)      \
    ACC(N, idelay) ACC(N, icount) ACC(N, ispace) ACC(N, iaddr) ACC(N, ivals) ACC(N, reset_space) ACC(N, reset_addr)      \
    ACC(N, reset_vals) ACC(N, corrupt_off) ACC(N, corrupt_val)                                                           \
    static Ref REF_##N;                                                                                                  \
    static void init_##N(void) {                                                                                         \
        const Ref r = {#N, model, (const unsigned char*)N##_image, (size_t)N##_image_size, N##_get_offsets,              \
                       N##_get_space, N##_get_addr, N##_get_vals, N##_get_pos, N##_get_irqs, N##_get_ifirst,             \
                       N##_get_iwfirst, N##_get_idelay, N##_get_icount, N##_get_ispace, N##_get_iaddr,                   \
                       N##_get_ivals, (int)(N##_reset_n), N##_get_reset_space, N##_get_reset_addr,                       \
                       N##_get_reset_vals, (int)(N##_corrupt_n), N##_get_corrupt_off, N##_get_corrupt_val};             \
        REF_##N = r;                                                                                                     \
    }

DEFINE_REF(ws_song, WS_MODEL_MONO)
DEFINE_REF(wsc_song, WS_MODEL_COLOR)
DEFINE_REF(wsc_vgm, WS_MODEL_COLOR)
DEFINE_REF(ws_tone, WS_MODEL_MONO)

static const Ref* const REFS[] = {&REF_ws_song, &REF_wsc_song, &REF_wsc_vgm, &REF_ws_tone};
#define NREFS 4

typedef struct VgmRef {
    const Ref* ref;
    uint64_t size, fnv64, loop_offset, total_samples, loop_samples, ticks;
} VgmRef;

#define VGM_REF(N)                                                                                                     \
    {&REF_##N, (uint64_t)(N##_vgm_size), (uint64_t)(N##_vgm_fnv64), (uint64_t)(N##_vgm_loop_offset),                 \
     (uint64_t)(N##_vgm_total_samples), (uint64_t)(N##_vgm_loop_samples), (uint64_t)(N##_vgm_ticks)}
#define NVGM 3
static VgmRef VGM_REFS[NVGM];

static void init_refs(void) {
    init_ws_song();
    init_wsc_song();
    init_wsc_vgm();
    init_ws_tone();
    const VgmRef v[NVGM] = {VGM_REF(ws_song), VGM_REF(wsc_vgm), VGM_REF(ws_tone)};
    memcpy(VGM_REFS, v, sizeof v);
}

static WsImage g_img[NREFS];
static WsImage g_tmp, g_tmp2, g_tone_mono, g_tone_color;

static const WsImage* image_of(const Ref* r) {
    for (int i = 0; i < NREFS; ++i)
        if (REFS[i] == r) return &g_img[i];
    return NULL;
}

static int load(WsImage* image, const unsigned char* bytes, size_t used) {
    memset(image->bytes, 0, sizeof image->bytes);
    memcpy(image->bytes, bytes, used);
    return ws_load_image(image);
}

static int ref_count(const Ref* r, int t) { return r->offsets(t + 1) - r->offsets(t); }

static int ref_row_start(const Ref* r, int order, int row) {
    for (int t = 0; t < WS_TEST_TICKS; ++t)
        if (r->pos(3 * t) == order && r->pos(3 * t + 1) == row && r->pos(3 * t + 2) == 0) return t;
    return -1;
}

/* ---- the trace hook: every batch and DMA transfer the machine sends --------------------------------------------- */

#define MAX_EVENTS 2048
#define MAX_TRACE_WRITES 32768
enum { EV_BATCH = 0, EV_DMA = 1 };

typedef struct Event {
    int type, kind;
    int64_t cycle;
    int first, n;      /* batches: their writes */
    int target, data;  /* DMA transfers */
} Event;

typedef struct Collect {
    int ne, nw, overflow;
    Event e[MAX_EVENTS];
    WsWrite w[MAX_TRACE_WRITES];
} Collect;

static Collect g_col;

static void collect(void* ctx, int64_t cycle, int kind, const WsWrite* w, int n) {
    Collect* c = (Collect*)ctx;
    if (c->ne >= MAX_EVENTS || c->nw + n > MAX_TRACE_WRITES) {
        c->overflow = 1;
        return;
    }
    Event* e = &c->e[c->ne++];
    e->type = EV_BATCH;
    e->kind = kind;
    e->cycle = cycle;
    e->first = c->nw;
    e->n = n;
    memcpy(c->w + c->nw, w, sizeof *w * (size_t)n);
    c->nw += n;
}

static void collect_dma(void* ctx, int64_t cycle, int target, uint8_t data) {
    Collect* c = (Collect*)ctx;
    if (c->ne >= MAX_EVENTS) {
        c->overflow = 1;
        return;
    }
    Event* e = &c->e[c->ne++];
    e->type = EV_DMA;
    e->kind = 0;
    e->cycle = cycle;
    e->first = e->n = 0;
    e->target = target;
    e->data = data;
}

static void clear(Collect* c) { c->ne = c->nw = c->overflow = 0; }

static void trace_on(WsMachine* m) {
    wsm_set_trace(m, collect, &g_col);
    wsm_set_dma_trace(m, collect_dma, &g_col);
}

static void trace_off(WsMachine* m) {
    wsm_set_trace(m, NULL, NULL);
    wsm_set_dma_trace(m, NULL, NULL);
}

static int traced_tick(WsMachine* m) {
    clear(&g_col);
    return wsm_tick(m, g_out);
}

static int same_write(const WsWrite* w, int space, int addr, int val) {
    return w->space == space && w->addr == addr && w->val == val;
}

/* Whether the collected batches of the last tick equal reference tick t: the tick's batch at its first cycle, then
 * irqs[t] timer interrupts, those that wrote anything equal to the reference's with their cycles (DMA transfers aside). */
static int same_tick(const Collect* c, const Ref* r, int t) {
    if (c->overflow) return 0;
    const int64_t start = (int64_t)t * WS_TICK_CYCLES;
    int batches = 0, e = r->ifirst(t), j = r->iwfirst(t);
    for (int k = 0; k < c->ne; ++k) {
        const Event* x = &c->e[k];
        if (x->type != EV_BATCH) continue;
        if (batches++ == 0) {
            if (x->kind != WSM_TRACE_TICK || x->cycle != start || x->n != ref_count(r, t)) return 0;
            for (int i = 0; i < x->n; ++i) {
                const int q = r->offsets(t) + i;
                if (!same_write(&c->w[x->first + i], r->space(q), r->addr(q), r->vals(q))) return 0;
            }
            continue;
        }
        if (x->kind != WSM_TRACE_TIMER) return 0;
        if (!x->n) continue;
        if (e >= r->ifirst(t + 1) || x->cycle != start + r->idelay(e) || x->n != r->icount(e)) return 0;
        for (int i = 0; i < x->n; ++i, ++j)
            if (!same_write(&c->w[x->first + i], r->ispace(j), r->iaddr(j), r->ivals(j))) return 0;
        ++e;
    }
    return batches == 1 + r->irqs(t) && e == r->ifirst(t + 1);
}

static int same_reset(const Collect* c, const Ref* r) {
    if (c->overflow || c->ne < 1 || c->e[0].type != EV_BATCH || c->e[0].kind != WSM_TRACE_RESET || c->e[0].cycle != 0)
        return 0;
    const Event* x = &c->e[0];
    if (x->n != r->reset_n) return 0;
    for (int i = 0; i < x->n; ++i)
        if (!same_write(&c->w[x->first + i], r->reset_space(i), r->reset_addr(i), r->reset_vals(i))) return 0;
    return 1;
}

static int pos_is(const WsMachine* m, const Ref* r, int t) {
    int o, w, k;
    wsm_position(m, &o, &w, &k);
    return o == r->pos(3 * t) && w == r->pos(3 * t + 1) && k == r->pos(3 * t + 2);
}

/* The DMA transfers of the collected tick folded into a digest. */
static uint64_t dma_digest(uint64_t h, const Collect* c) {
    for (int k = 0; k < c->ne; ++k) {
        const Event* x = &c->e[k];
        if (x->type != EV_DMA) continue;
        const int64_t v[3] = {x->cycle, x->target, x->data};
        h = fnv(h, v, sizeof v);
    }
    return h;
}

static void print_writes(FILE* f, const WsWrite* w, int n) {
    for (int i = 0; i < n; ++i) fprintf(f, "W %d %04X %02X\n", w[i].space, w[i].addr, w[i].val);
}

/* ---- audio helpers --------------------------------------------------------------------------------------------- */

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

static void write_bytes(const char* dir, const char* name, const void* data, size_t n) {
    char path[512];
    snprintf(path, sizeof path, "%s/%s", dir, name);
    FILE* f = fopen(path, "wb");
    if (!f) return;
    fwrite(data, 1, n, f);
    fclose(f);
}

static double rms(const float* x, int frames) {
    double s = 0;
    for (int i = 0; i < frames * 2; ++i) s += (double)x[i] * x[i];
    return frames ? sqrt(s / (frames * 2.0)) : 0;
}

static double peak(const float* x, int frames) {
    double p = 0;
    for (int i = 0; i < frames * 2; ++i)
        if (fabs(x[i]) > p) p = fabs(x[i]);
    return p;
}

/* The strongest frequency in [lo, hi] Hz of the mono mix (Hann window, Goertzel power per `step` Hz, parabolic
 * refinement). */
static double peak_hz(const float* x, int frames, double lo, double hi, double step) {
    double best = -1, best_f = lo, prev = 0, next = 0, before = 0;
    const int n = (int)((hi - lo) / step) + 1;
    double* power = (double*)malloc(sizeof(double) * (size_t)n);
    double* sig = (double*)malloc(sizeof(double) * (size_t)(frames > 0 ? frames : 1));
    double mean = 0;
    for (int i = 0; i < frames; ++i) mean += x[2 * i] + x[2 * i + 1];
    mean /= 2.0 * (frames ? frames : 1);
    for (int i = 0; i < frames; ++i)
        sig[i] = (0.5 - 0.5 * cos(2 * M_PI * i / (frames - 1))) * ((x[2 * i] + x[2 * i + 1]) * 0.5 - mean);
    for (int k = 0; k < n; ++k) {
        const double f = lo + k * step, w = 2 * M_PI * f / RATE, c = 2 * cos(w);
        double s1 = 0, s2 = 0;
        for (int i = 0; i < frames; ++i) {
            const double s0 = sig[i] + c * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        power[k] = s1 * s1 + s2 * s2 - c * s1 * s2;
        if (power[k] > best) {
            best = power[k];
            best_f = f;
        }
    }
    const int kb = (int)lrint((best_f - lo) / step);
    if (kb > 0 && kb + 1 < n) {
        before = power[kb - 1];
        prev = power[kb];
        next = power[kb + 1];
        const double d = before - 2 * prev + next;
        if (d < 0) best_f += step * 0.5 * (before - next) / d;
    }
    free(power);
    free(sig);
    return best_f;
}

static int render(WsMachine* m, int ticks, float** buf) {
    float* out = (float*)malloc((size_t)ticks * WSM_MAX_TICK_FRAMES * 2 * sizeof(float));
    int frames = 0;
    for (int t = 0; t < ticks; ++t) {
        const int n = wsm_tick(m, out + 2 * frames);
        if (n <= 0) break;
        frames += n;
    }
    *buf = out;
    return frames;
}

/* ---- images built here ------------------------------------------------------------------------------------------ */

static void put16(uint8_t* p, int v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
}

static void put32(uint8_t* p, uint32_t v) {
    for (int i = 0; i < 4; ++i) p[i] = (uint8_t)(v >> (8 * i));
}

/* Slots of the measurement images (build_tone). */
#define T_SINE 1
#define T_RELEASE 2
#define T_NOISE 3
#define T_LOOP 4
#define T_ONESHOT 5
#define T_STEREO 6
#define T_DMA_ONESHOT 7 /* Color only */
#define T_DMA_LOOP 8    /* Color only */
#define T_UNDEFINED 9

#define TONE_SAMPLE_AT 6
#define TONE_FRAMES 64
#define TONE_PERIOD 16

static void put_instrument(WsImage* im, int slot, int flags, int source, int root, int loop) {
    uint8_t* r = im->bytes + WS_OFF_INSTRUMENTS + WS_INSTRUMENT_SIZE * (slot - 1);
    memset(r, 0, WS_INSTRUMENT_SIZE);
    r[WS_I_FLAGS] = (uint8_t)(WS_FLAG_DEFINED | flags);
    r[WS_I_SOURCE] = (uint8_t)source;
    r[WS_I_ROOT] = (uint8_t)root;
    r[WS_I_LOOP] = (uint8_t)loop;
    for (int k = 0; k < WS_ENVELOPE_COUNT; ++k) {
        r[WS_I_ENVELOPES + WS_ENVELOPE_BYTES * k + 3] = WS_ENV_NONE;
        r[WS_I_ENVELOPES + WS_ENVELOPE_BYTES * k + 4] = WS_ENV_NONE;
    }
    if (im->bytes[WS_H_INSTRUMENTS] < slot) im->bytes[WS_H_INSTRUMENTS] = (uint8_t)slot;
}

/* data: [0] the empty row; [1..5] a volume envelope held at 15 and released to 0 (15 15 10 5 0, loop 1, release 2);
 * [6..69] sample 0, 64 frames of a 16-frame square; [70..133] its signed copy; [134..261] sample 1, stereo (left the
 * square, right its inverse); [262..389] its signed copy. Two waves: 0 a sine, 1 a square. Timer reload 1 (12 kHz). */
static void build_tone(WsImage* im, int model) {
    enum { SIZE = 390 };
    uint8_t data[SIZE] = {0x80, 15, 15, 10, 5, 0};
    for (int i = 0; i < TONE_FRAMES; ++i) {
        const uint8_t v = (i % TONE_PERIOD) < TONE_PERIOD / 2 ? 0xC0 : 0x40;
        data[TONE_SAMPLE_AT + i] = v;
        data[70 + i] = v ^ 0x80;
        data[134 + 2 * i] = v;
        data[134 + 2 * i + 1] = (uint8_t)(0x100 - v);
        data[262 + 2 * i] = v ^ 0x80;
        data[262 + 2 * i + 1] = (uint8_t)(0x100 - v) ^ 0x80;
    }
    memset(im, 0, sizeof *im);
    uint8_t* b = im->bytes;
    memcpy(b, "TTWS", 4);
    b[WS_H_LAYOUT] = WS_LAYOUT;
    b[WS_H_MODEL] = (uint8_t)model;
    b[WS_H_SPEED] = 6;
    put16(b + WS_H_ORDERS, 1);
    b[WS_OFF_ROWS] = 1;
    b[WS_H_WAVES] = 2;
    b[WS_H_SAMPLES] = 2;
    b[WS_H_TIMER] = 1;
    for (int i = 0; i < WS_WAVE_SIZE; ++i) {
        b[WS_OFF_WAVES + i] = (uint8_t)((WSP_SINE[2 * i] + 127) * 15 / 254);
        b[WS_OFF_WAVES + WS_WAVE_SIZE + i] = i < 16 ? 15 : 0;
    }
    put32(b + WS_OFF_SAMPLES + WS_S_OFFSET, TONE_SAMPLE_AT);
    put32(b + WS_OFF_SAMPLES + WS_S_FRAMES, TONE_FRAMES);
    put32(b + WS_OFF_SAMPLES + WS_S_SIGNED_OFFSET, 70);
    b[WS_OFF_SAMPLES + WS_S_FLAGS] = WS_SAMPLE_SIGNED;
    put32(b + WS_OFF_SAMPLES + WS_SAMPLE_ENTRY_SIZE + WS_S_OFFSET, 134);
    put32(b + WS_OFF_SAMPLES + WS_SAMPLE_ENTRY_SIZE + WS_S_FRAMES, TONE_FRAMES);
    put32(b + WS_OFF_SAMPLES + WS_SAMPLE_ENTRY_SIZE + WS_S_SIGNED_OFFSET, 262);
    b[WS_OFF_SAMPLES + WS_SAMPLE_ENTRY_SIZE + WS_S_FLAGS] = WS_SAMPLE_STEREO | WS_SAMPLE_SIGNED;
    memcpy(b + WS_OFF_DATA, data, SIZE);
    put32(b + WS_H_DATA_SIZE, SIZE);
    put_instrument(im, T_SINE, WS_KIND_TONE, 0, 0, 0);
    put_instrument(im, T_RELEASE, WS_KIND_TONE, 0, 0, 0);
    uint8_t* env = b + WS_OFF_INSTRUMENTS + WS_INSTRUMENT_SIZE * (T_RELEASE - 1) + WS_I_ENVELOPES;
    put16(env, 1);
    env[2] = 5;
    env[3] = 1;
    env[4] = 2;
    put_instrument(im, T_NOISE, WS_KIND_NOISE, 0, 0, 0);
    put_instrument(im, T_LOOP, WS_KIND_SAMPLE, 0, A4, 1);
    put_instrument(im, T_ONESHOT, WS_KIND_SAMPLE, 0, A4, 0);
    put_instrument(im, T_STEREO, WS_KIND_SAMPLE, 1, A4, 1);
    if (model == WS_MODEL_COLOR) {
        put_instrument(im, T_DMA_ONESHOT, WS_KIND_SAMPLE | WS_FLAG_DMA, 0, 3, 0);
        put_instrument(im, T_DMA_LOOP, WS_KIND_SAMPLE | WS_FLAG_DMA, 0, 0, 1);
    }
    EXPECT(ws_load_image(im) == 0, "tone image (model %d) loads: %s", model, im->info.problem);
}

/* `src` with its data region moved up by k bytes (every offset + k): the same song, re-laid out. 0 if it cannot be. */
static int relayout(WsImage* dst, const WsImage* src, int k) {
    const WsInfo* info = &src->info;
    if (info->data_size + (uint32_t)k > WS_DATA_CAPACITY) return 0;
    memcpy(dst, src, sizeof *dst);
    uint8_t* b = dst->bytes;
    memset(b + WS_OFF_DATA, 0, WS_DATA_CAPACITY);
    memcpy(b + WS_OFF_DATA + k, src->bytes + WS_OFF_DATA, info->data_size);
    put32(b + WS_H_DATA_SIZE, info->data_size + (uint32_t)k);
    for (int o = 0; o < info->orders; ++o)
        for (int c = 0; c < info->lanes; ++c) {
            uint8_t* p = b + WS_OFF_ORDERS + 2 * (WS_CHANNELS * o + c);
            if (ws_u16(p) + k > 65535) return 0;
            put16(p, ws_u16(p) + k);
        }
    for (int n = 1; n <= WS_MAX_INSTRUMENTS; ++n) {
        uint8_t* r = b + WS_OFF_INSTRUMENTS + WS_INSTRUMENT_SIZE * (n - 1);
        for (int e = 0; e < WS_ENVELOPE_COUNT; ++e) {
            uint8_t* p = r + WS_I_ENVELOPES + WS_ENVELOPE_BYTES * e;
            if (!p[2]) continue;
            if (ws_u16(p) + k > 65535) return 0;
            put16(p, ws_u16(p) + k);
        }
    }
    for (int s = 0; s < info->samples; ++s) {
        uint8_t* e = b + WS_OFF_SAMPLES + WS_SAMPLE_ENTRY_SIZE * s;
        put32(e + WS_S_OFFSET, ws_u32(e + WS_S_OFFSET) + (uint32_t)k);
        if (e[WS_S_FLAGS] & WS_SAMPLE_SIGNED) put32(e + WS_S_SIGNED_OFFSET, ws_u32(e + WS_S_SIGNED_OFFSET) + (uint32_t)k);
    }
    return ws_load_image(dst) == 0;
}

/* ---- the sound unit ----------------------------------------------------------------------------------------------- */

static uint8_t g_rom[4096];
static WsApu *g_a, *g_b;

typedef struct Transfers {
    int n;
    uint64_t cycle[512];
    int target[512];
    uint8_t data[512];
    uint64_t digest;
} Transfers;

static Transfers g_ta, g_tb;

static void record(void* ctx, uint64_t cycle, int target, uint8_t data) {
    Transfers* t = (Transfers*)ctx;
    const uint64_t v[3] = {cycle, (uint64_t)target, data};
    t->digest = fnv(t->digest, v, sizeof v);
    if (t->n < 512) {
        t->cycle[t->n] = cycle;
        t->target[t->n] = target;
        t->data[t->n] = data;
        ++t->n;
    }
}

static void apu_fresh(WsApu* a, int model, Transfers* t) {
    memset(a, 0, sizeof *a);
    ws_apu_reset(a, model);
    ws_apu_map_rom(a, WS_DMA_BASE, g_rom, sizeof g_rom);
    if (t) {
        memset(t, 0, sizeof *t);
        ws_apu_set_hook(a, record, t);
    }
}

static void port(WsApu* a, int p, int v) { ws_apu_write_port(a, p, (uint8_t)v); }

static uint32_t g_seed = 1;
static uint32_t rnd(void) {
    g_seed = g_seed * 1103515245u + 12345u;
    return g_seed >> 8;
}

static const int PORTS[] = {0x4A, 0x4B, 0x4C, 0x4E, 0x4F, 0x50, 0x52, 0x64, 0x65, 0x66, 0x67, 0x69, 0x6A, 0x6B,
                            0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8A, 0x8B, 0x8C, 0x8D,
                            0x8E, 0x8F, 0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x9E};

/* A random write that keeps the program lively: high pitches, short DMA lengths, the wave base at 0 or $FF. */
static void random_write(int* p, int* v) {
    *p = PORTS[rnd() % (sizeof PORTS / sizeof PORTS[0])];
    *v = (int)(rnd() & 0xFF);
    if (*p >= 0x80 && *p <= 0x87 && (*p & 1)) *v |= rnd() % 4 ? 7 : 0;
    if (*p == 0x4F || *p == 0x50 || *p == 0x4C) *v = rnd() % 4 ? 0 : *v & 1;
    if (*p == 0x4B) *v = rnd() % 2 ? 0x00 : *v;
    if (*p == 0x4C && rnd() % 2) *v = 0x04; /* the ROM's bank */
    if (*p == 0x8F) *v = rnd() % 2 ? 0xFF : 0;
}

static void test_apu_closed_form(void) {
    printf("sound unit: closed form = per-cycle loop\n");
    int bad_state = 0, bad_frame = 0, blocks = 0, transfers = 0;
    for (int model = 0; model < 2; ++model) {
        for (int seed = 1; seed <= 12; ++seed) {
            g_seed = (uint32_t)(seed * 7919 + model);
            for (size_t i = 0; i < sizeof g_rom; ++i) g_rom[i] = (uint8_t)rnd();
            apu_fresh(g_a, model, &g_ta);
            apu_fresh(g_b, model, &g_tb);
            const int speaker = seed % 3 == 0;
            ws_apu_set_output(g_a, speaker);
            ws_apu_set_output(g_b, speaker);
            for (int i = 0; i < 4096; ++i) {
                const uint16_t addr = (uint16_t)(rnd() & 0xFFFF);
                const uint8_t v = (uint8_t)rnd();
                ws_apu_write_ram(g_a, addr, v);
                ws_apu_write_ram(g_b, addr, v);
            }
            for (int k = 0; k < 6000; ++k) {
                if (rnd() % 8 == 0) {
                    const int n = 1 + (int)(rnd() % 4);
                    for (int j = 0; j < n; ++j) {
                        int p, v;
                        random_write(&p, &v);
                        port(g_a, p, v);
                        port(g_b, p, v);
                    }
                }
                if (rnd() % 500 == 0) {
                    const unsigned mute = rnd() & 0x1F;
                    ws_apu_set_mute(g_a, mute);
                    ws_apu_set_mute(g_b, mute);
                }
                int16_t fa[2], fb[2];
                ws_apu_block(g_a, fa);
                ws_apu_block_reference(g_b, fb);
                ++blocks;
                if (fa[0] != fb[0] || fa[1] != fb[1]) ++bad_frame;
                if (!ws_apu_same_state(g_a, g_b)) {
                    ++bad_state;
                    memcpy(g_a, g_b, sizeof *g_a); /* continue from the reference */
                    ws_apu_set_hook(g_a, record, &g_ta);
                }
            }
            if (g_ta.digest != g_tb.digest) ++bad_state;
            transfers += g_tb.n;
        }
    }
    EXPECT(bad_state == 0 && bad_frame == 0, "%d random blocks: closed form = per-cycle loop (%d states, %d frames differ)",
           blocks, bad_state, bad_frame);
    EXPECT(transfers > 50, "the random programs exercise Sound DMA (%d transfers)", transfers);
}

/* Channel 1 at register d from the reset: after n cycles it has stepped n / (2048 - d) times (2048 at d = 0). */
static void test_apu_period(void) {
    printf("sound unit: periods\n");
    static const int regs[] = {0, 1, 1000, 1830, 1920, 2000, 2046, 2047};
    int ok = 1;
    for (size_t i = 0; i < sizeof regs / sizeof regs[0]; ++i) {
        const int d = regs[i], n_period = 2048 - d;
        for (int closed = 0; closed < 2; ++closed) {
            WsApu* a = closed ? g_a : g_b;
            apu_fresh(a, WS_MODEL_MONO, NULL);
            port(a, 0x80, d & 0xFF);
            port(a, 0x81, d >> 8);
            port(a, 0x90, 0x01);
            for (int blocks = 1; blocks <= 64; ++blocks) {
                int16_t f[2];
                if (closed) ws_apu_block(a, f);
                else ws_apu_block_reference(a, f);
                const int n = WS_BLOCK_CYCLES * blocks;
                if (a->ch[0].offset != ((n / n_period) & 31) || a->ch[0].counter != ((-(n % n_period)) & 2047)) ok = 0;
            }
        }
    }
    EXPECT(ok, "a channel steps every 2048 - d cycles (d 0..2047, both steppings)");
}

static void test_apu_lfsr(void) {
    printf("sound unit: noise\n");
    static const int want[8] = {32767, 1953, 254, 217, 73, 63, 42, 28};
    static int first[32768];
    for (int t = 0; t < 8; ++t) {
        apu_fresh(g_a, WS_MODEL_MONO, NULL);
        port(g_a, 0x86, 1920 & 0xFF); /* 2048 - 1920 = 128 cycles: one LFSR step per block */
        port(g_a, 0x87, 1920 >> 8);
        port(g_a, 0x8E, 0x10 | t);
        port(g_a, 0x8B, 0xFF);
        port(g_a, 0x90, 0x88);
        for (int i = 0; i < 32768; ++i) first[i] = -1;
        first[g_a->lfsr] = 0;
        int length = -1;
        for (int s = 1; s <= 70000 && length < 0; ++s) {
            int16_t f[2];
            ws_apu_block(g_a, f);
            if (first[g_a->lfsr] >= 0) length = s - first[g_a->lfsr];
            else first[g_a->lfsr] = s;
        }
        EXPECT(length == want[t], "tap mode %d: sequence length %d (want %d)", t, length, want[t]);
    }
    /* $8E bit 3 resets the LFSR; $92/$93 load it */
    port(g_a, 0x92, 0x34);
    port(g_a, 0x93, 0x92);
    EXPECT(g_a->lfsr == 0x1234, "$92/$93 load 15 bits (%04X)", g_a->lfsr);
    port(g_a, 0x8E, 0x18);
    EXPECT(g_a->lfsr == 0 && g_a->noise_update == 1, "$8E bit 3 resets the LFSR");
}

static void run_blocks(WsApu* a, int n) {
    int16_t f[2];
    for (int i = 0; i < n; ++i) ws_apu_block(a, f);
}

static void test_apu_sweep(void) {
    printf("sound unit: sweep\n");
    apu_fresh(g_a, WS_MODEL_MONO, NULL);
    port(g_a, 0x84, 100);
    port(g_a, 0x8C, 3);
    port(g_a, 0x8D, 0);
    port(g_a, 0x90, 0x44);
    run_blocks(g_a, 63);
    const int before = g_a->ch[2].pitch;
    run_blocks(g_a, 1);
    const int after = g_a->ch[2].pitch;
    run_blocks(g_a, 64);
    EXPECT(before == 100 && after == 103 && g_a->ch[2].pitch == 106,
           "time 0: a step at the prescaler's 8192nd cycle, then every 8192 (%d %d %d)", before, after, g_a->ch[2].pitch);
    /* time 2: a step every 3 prescaler ticks, the first 3 ticks after the $8D write */
    apu_fresh(g_a, WS_MODEL_MONO, NULL);
    port(g_a, 0x84, 100);
    port(g_a, 0x8C, (uint8_t)-5);
    port(g_a, 0x8D, 2);
    port(g_a, 0x90, 0x44);
    run_blocks(g_a, 3 * 64 - 1);
    const int p0 = g_a->ch[2].pitch;
    run_blocks(g_a, 1);
    const int p1 = g_a->ch[2].pitch;
    EXPECT(p0 == 100 && p1 == 95, "time 2: the first step after 3 prescaler ticks (%d -> %d)", p0, p1);
    /* $8D resets the step counter: two ticks into the next step, rewriting time 2 delays it by 3 ticks from here */
    run_blocks(g_a, 2 * 64);
    port(g_a, 0x8D, 2);
    run_blocks(g_a, 3 * 64 - 1);
    const int p2 = g_a->ch[2].pitch;
    run_blocks(g_a, 1);
    EXPECT(p2 == 95 && g_a->ch[2].pitch == 90, "$8D resets the step counter (%d -> %d)", p2, g_a->ch[2].pitch);
    /* the 11-bit register wraps */
    apu_fresh(g_a, WS_MODEL_MONO, NULL);
    port(g_a, 0x84, 2);
    port(g_a, 0x8C, (uint8_t)-5);
    port(g_a, 0x90, 0x44);
    run_blocks(g_a, 64);
    EXPECT(g_a->ch[2].pitch == 2045, "the sweep wraps the 11-bit register (%d)", g_a->ch[2].pitch);
    /* no sweep while channel 3 is disabled */
    apu_fresh(g_a, WS_MODEL_MONO, NULL);
    port(g_a, 0x84, 100);
    port(g_a, 0x8C, 3);
    port(g_a, 0x90, 0x40);
    run_blocks(g_a, 64 * 8);
    EXPECT(g_a->ch[2].pitch == 100 && g_a->sweep_counter == 0, "no sweep without channel 3 enabled (%d)", g_a->ch[2].pitch);
    EXPECT(g_a->sweep_clock == (64 * 8 * WS_BLOCK_CYCLES) % 8192, "the prescaler runs regardless");
}

/* Headphone frame of one block. */
static void frame_of(WsApu* a, int16_t f[2]) { ws_apu_block(a, f); }

static void test_apu_voice(void) {
    printf("sound unit: voice volume bits\n");
    static const int want_l[4] = {0, 0, 0x80 << 5, 0x40 << 5}, want_r[4] = {0x80 << 5, 0x40 << 5, 0, 0};
    for (int bit = 0; bit < 4; ++bit) {
        apu_fresh(g_a, WS_MODEL_MONO, NULL);
        port(g_a, 0x91, 0x08);
        port(g_a, 0x89, 0x80);
        port(g_a, 0x90, 0x20);
        port(g_a, 0x94, 1 << bit);
        int16_t f[2];
        frame_of(g_a, f);
        EXPECT(f[0] == want_l[bit] && f[1] == want_r[bit], "$94 bit %d: left %d right %d (want %d %d)", bit, f[0], f[1],
               want_l[bit], want_r[bit]);
    }
}

/* The wiki's Hyper Voice table, written out independently. */
static int16_t hv_expected(int scaling, int shift, uint8_t v) {
    long x;
    switch (scaling) {
    case 0: x = (long)v * 256 / (1L << shift); break;                       /* unsigned */
    case 1: x = -(long)((256 - v) * 256) / (1L << shift); break;            /* unsigned, negated: (v - 256) << 8 >> s */
    case 2: x = (long)(int8_t)v * 256; x = x >= 0 ? x >> shift : -((-x + (1L << shift) - 1) >> shift); break;
    default: x = (long)v * 256; break;
    }
    return (int16_t)(uint16_t)(x & 0xFFFF);
}

static void test_apu_hyper_voice(void) {
    printf("sound unit: Hyper Voice\n");
    static const uint8_t values[] = {0x00, 0x01, 0x40, 0x7F, 0x80, 0x81, 0xC0, 0xFF};
    int ok = 1;
    for (int scaling = 0; scaling < 4; ++scaling)
        for (int shift = 0; shift < 4; ++shift)
            for (size_t i = 0; i < sizeof values; ++i) {
                apu_fresh(g_a, WS_MODEL_COLOR, NULL);
                port(g_a, 0x6A, 0x80 | scaling << 2 | shift);
                port(g_a, 0x69, values[i]);
                port(g_a, 0x69, values[(i + 3) % sizeof values]);
                run_blocks(g_a, 1);
                if (g_a->hv.out_l != hv_expected(scaling, shift, values[i]) ||
                    g_a->hv.out_r != hv_expected(scaling, shift, values[(i + 3) % sizeof values]))
                    ok = 0;
            }
    EXPECT(ok, "conversion: the wiki's table for every scaling and shift");
    static const int divisor[8] = {1, 2, 3, 4, 5, 6, 8, 12};
    for (int speed = 0; speed < 8; ++speed) {
        apu_fresh(g_a, WS_MODEL_COLOR, NULL);
        port(g_a, 0x69, 0x40);
        port(g_a, 0x6A, 0x80 | speed << 4 | 3 << 2);
        int at = 0;
        for (int b = 1; b <= 20 && !at; ++b) {
            run_blocks(g_a, 1);
            if (g_a->hv.out_l == 0x4000) at = b;
        }
        EXPECT(at == divisor[speed], "speed %d: the first update after %d blocks (want %d)", speed, at, divisor[speed]);
    }
    apu_fresh(g_a, WS_MODEL_COLOR, NULL);
    port(g_a, 0x6B, 0x20); /* channel mode 1: manual writes ignore it */
    port(g_a, 0x69, 0x11);
    port(g_a, 0x69, 0x22);
    port(g_a, 0x69, 0x33);
    EXPECT(g_a->hv.latch_l == 0x33 && g_a->hv.latch_r == 0x22 && g_a->hv.flip == 1, "$69 alternates left, right, left");
    port(g_a, 0x6B, 0x10);
    port(g_a, 0x69, 0x44);
    EXPECT(g_a->hv.latch_l == 0x44 && g_a->hv.latch_r == 0x22, "$6B bit 4 resets the flip-flop: the next byte goes left");
    /* direct output ports and the headphone adder's 16-bit wrap */
    apu_fresh(g_a, WS_MODEL_COLOR, NULL);
    for (int i = 0; i < 16; ++i) ws_apu_write_ram(g_a, (uint16_t)i, 0xFF);
    port(g_a, 0x8F, 0x00);
    port(g_a, 0x88, 0xFF);
    port(g_a, 0x90, 0x01);
    port(g_a, 0x91, 0x08);
    port(g_a, 0x64, 0xFF);
    port(g_a, 0x65, 0x7F);
    port(g_a, 0x66, 0x00);
    port(g_a, 0x67, 0x80);
    port(g_a, 0x6A, 0x80 | 7 << 4); /* divisor 12: the direct values stay for this block */
    int16_t f[2];
    frame_of(g_a, f);
    EXPECT(f[0] == (int16_t)(uint16_t)((225 << 5) + 0x7FFF) && f[1] == (int16_t)(uint16_t)((225 << 5) - 0x8000),
           "headphones wrap to 16 bits: %d %d", f[0], f[1]);
    ws_apu_set_mute(g_a, 0x10);
    frame_of(g_a, f);
    EXPECT(f[0] == 225 << 5 && f[1] == 225 << 5, "muted Hyper Voice is not added (%d %d)", f[0], f[1]);
    /* the original model ignores the Color's ports */
    apu_fresh(g_a, WS_MODEL_MONO, NULL);
    port(g_a, 0x6A, 0x80);
    port(g_a, 0x4E, 5);
    port(g_a, 0x52, 0x80);
    EXPECT(!g_a->hv.enable && !g_a->dma.enable && !g_a->dma.length, "the original model ignores $4A-$6B");
}

static void test_apu_speaker(void) {
    printf("sound unit: speaker\n");
    for (int shift = 0; shift < 4; ++shift) {
        apu_fresh(g_a, WS_MODEL_MONO, NULL);
        ws_apu_set_output(g_a, 1);
        for (int i = 0; i < 64; ++i) ws_apu_write_ram(g_a, (uint16_t)i, 0xFF);
        port(g_a, 0x8F, 0x00);
        for (int c = 0; c < 4; ++c) port(g_a, 0x88 + c, 0xFF);
        port(g_a, 0x90, 0x0F);
        port(g_a, 0x91, 0x01 | shift << 1);
        int16_t f[2];
        frame_of(g_a, f);
        const int want = ((1800 >> shift) & 0xFF) << 7;
        EXPECT(f[0] == want && f[1] == want, "speaker shift %d: ((900 + 900) >> %d) & $FF << 7 = %d (got %d %d)", shift, shift,
               want, f[0], f[1]);
        port(g_a, 0x91, 0x08 | shift << 1);
        frame_of(g_a, f);
        EXPECT(f[0] == 0 && f[1] == 0, "speaker disabled: silent on the speaker path");
    }
}

static void test_apu_dma(void) {
    printf("sound unit: Sound DMA\n");
    for (size_t i = 0; i < sizeof g_rom; ++i) g_rom[i] = (uint8_t)(i * 7 + 3);
    static const int divisor[4] = {6, 4, 2, 1};
    for (int rate = 0; rate < 4; ++rate) {
        apu_fresh(g_a, WS_MODEL_COLOR, &g_ta);
        port(g_a, 0x4A, 0x00);
        port(g_a, 0x4B, 0x00);
        port(g_a, 0x4C, 0x04);
        port(g_a, 0x4E, 200);
        port(g_a, 0x52, 0x80 | rate);
        run_blocks(g_a, 24);
        int spaced = g_ta.n == 24 / divisor[rate];
        for (int i = 0; spaced && i < g_ta.n; ++i)
            spaced = g_ta.cycle[i] == (uint64_t)WS_BLOCK_CYCLES * (divisor[rate] * (i + 1) - 1) + WS_DMA_TRANSFER_CYCLE &&
                     g_ta.data[i] == g_rom[i] && g_ta.target[i] == 0;
        EXPECT(spaced, "rate %d: a transfer every %d blocks at cycle 122 (%d in 24 blocks)", rate, divisor[rate], g_ta.n);
        EXPECT(g_a->ch[1].vol_l == g_rom[g_ta.n - 1] >> 4 && g_a->ch[1].vol_r == (g_rom[g_ta.n - 1] & 15),
               "target 0: CH2's volume byte ($89) = the last byte");
    }
    /* hold: zeros, nothing advances */
    apu_fresh(g_a, WS_MODEL_COLOR, &g_ta);
    port(g_a, 0x4C, 0x04);
    port(g_a, 0x4E, 5);
    port(g_a, 0x52, 0x80 | 0x04 | 3);
    run_blocks(g_a, 10);
    int zeros = g_ta.n == 10;
    for (int i = 0; i < g_ta.n; ++i) zeros = zeros && g_ta.data[i] == 0;
    EXPECT(zeros && g_a->dma.source == WS_DMA_BASE && g_a->dma.length == 5 && g_a->dma.enable,
           "hold: transfers 0 without advancing (%d transfers)", g_ta.n);
    /* loop: the shadow source and length come back */
    apu_fresh(g_a, WS_MODEL_COLOR, &g_ta);
    port(g_a, 0x4A, 0x10);
    port(g_a, 0x4C, 0x04);
    port(g_a, 0x4E, 5);
    port(g_a, 0x52, 0x80 | 0x08 | 3);
    run_blocks(g_a, 12);
    int looped = g_ta.n == 12;
    for (int i = 0; looped && i < 12; ++i) looped = g_ta.data[i] == g_rom[0x10 + i % 5];
    EXPECT(looped && g_a->dma.enable, "loop: 5 bytes repeating");
    /* one-shot: the enable clears after the last byte */
    apu_fresh(g_a, WS_MODEL_COLOR, &g_ta);
    port(g_a, 0x4C, 0x04);
    port(g_a, 0x4E, 5);
    port(g_a, 0x52, 0x80 | 3);
    run_blocks(g_a, 12);
    EXPECT(g_ta.n == 5 && !g_a->dma.enable && g_a->dma.length == 0, "one-shot: 5 transfers, then the enable clears (%d)", g_ta.n);
    port(g_a, 0x52, 0x80 | 3);
    EXPECT(!g_a->dma.enable, "an enable with the length at 0 fails");
    /* backwards */
    apu_fresh(g_a, WS_MODEL_COLOR, &g_ta);
    port(g_a, 0x4A, 0x09);
    port(g_a, 0x4C, 0x04);
    port(g_a, 0x4E, 10);
    port(g_a, 0x52, 0x80 | 0x40 | 3);
    run_blocks(g_a, 10);
    int back = g_ta.n == 10;
    for (int i = 0; back && i < 10; ++i) back = g_ta.data[i] == g_rom[9 - i];
    EXPECT(back, "backwards: bytes 9, 8, ... 0");
    /* outside the ROM: 0 */
    apu_fresh(g_a, WS_MODEL_COLOR, &g_ta);
    port(g_a, 0x4C, 0x02);
    port(g_a, 0x4E, 3);
    port(g_a, 0x52, 0x80 | 3);
    run_blocks(g_a, 3);
    EXPECT(g_ta.n == 3 && !g_ta.data[0] && !g_ta.data[1] && !g_ta.data[2], "addresses outside the mapped data read 0");
    /* Hyper Voice target: channel modes */
    static const int mode_l[4] = {1, 1, 0, 1}, mode_r[4] = {0, 0, 1, 1};
    for (int mode = 0; mode < 4; ++mode) {
        apu_fresh(g_a, WS_MODEL_COLOR, &g_ta);
        port(g_a, 0x6B, 0x10 | mode << 5);
        port(g_a, 0x4C, 0x04);
        port(g_a, 0x4E, 1);
        port(g_a, 0x52, 0x80 | 0x10 | 3);
        run_blocks(g_a, 1);
        EXPECT(g_ta.n == 1 && g_ta.target[0] == 1 && (g_a->hv.latch_l == g_rom[0]) == mode_l[mode] &&
                   (g_a->hv.latch_r == g_rom[0]) == mode_r[mode],
               "Hyper Voice DMA, channel mode %d: left %d right %d", mode, g_a->hv.latch_l == g_rom[0], g_a->hv.latch_r == g_rom[0]);
    }
    /* rebase */
    apu_fresh(g_a, WS_MODEL_COLOR, NULL);
    port(g_a, 0x4A, 0x20);
    port(g_a, 0x4C, 0x04);
    ws_apu_dma_rebase(g_a, -0x10);
    EXPECT(g_a->dma.source == 0x40010 && g_a->dma.shadow_source == 0x40010, "rebase moves source and shadow");
}

/* Mute: the muted APU's state equals the unmuted one's block for block; after unmuting the frames are identical. */
static void test_apu_mute(void) {
    printf("sound unit: mute\n");
    int bad_state = 0, bad_after = 0, differ_while = 0;
    for (int model = 0; model < 2; ++model) {
        g_seed = 4242u + (uint32_t)model;
        for (size_t i = 0; i < sizeof g_rom; ++i) g_rom[i] = (uint8_t)rnd();
        apu_fresh(g_a, model, &g_ta);
        apu_fresh(g_b, model, &g_tb);
        for (int i = 0; i < 4096; ++i) {
            const uint16_t addr = (uint16_t)(rnd() & 0xFFFF);
            const uint8_t v = (uint8_t)rnd();
            ws_apu_write_ram(g_a, addr, v);
            ws_apu_write_ram(g_b, addr, v);
        }
        for (int k = 0; k < 3000; ++k) {
            if (rnd() % 6 == 0) {
                int p, v;
                random_write(&p, &v);
                port(g_a, p, v);
                port(g_b, p, v);
            }
            if (k == 1000) ws_apu_set_mute(g_b, 0x1F);
            if (k == 2000) ws_apu_set_mute(g_b, 0);
            int16_t fa[2], fb[2];
            ws_apu_block(g_a, fa);
            ws_apu_block(g_b, fb);
            if (!ws_apu_same_state(g_a, g_b)) ++bad_state;
            if (k >= 2000 && (fa[0] != fb[0] || fa[1] != fb[1])) ++bad_after;
            if (k >= 1000 && k < 2000 && (fa[0] != fb[0] || fa[1] != fb[1])) ++differ_while;
        }
        if (g_ta.digest != g_tb.digest) ++bad_state;
    }
    EXPECT(bad_state == 0, "mute changes no state: phases, LFSR, sweep, DMA and Hyper Voice continue (%d blocks differ)",
           bad_state);
    EXPECT(bad_after == 0, "unmuted: frames identical to never muted (%d differ)", bad_after);
    EXPECT(differ_while > 0, "muted frames differ while muted (%d)", differ_while);
}

/* ---- player tables ------------------------------------------------------------------------------------------------ */

static void test_tables(void) {
    printf("periods\n");
    int table_ok = 1;
    for (int i = 0; i < 768; ++i) {
        const double v = 96000.0 / (440.0 * pow(2.0, (i / 64.0 - 57) / 12.0)) * 65536;
        if ((uint32_t)llround(v) != WSP_PERIOD_Q16[i]) table_ok = 0;
    }
    EXPECT(table_ok, "WSP_PERIOD_Q16 = round(96000 / (440 x 2^((i / 64 - 57) / 12)) x 65536)");
    int sine_ok = 1;
    for (int i = 0; i < 64; ++i)
        if (WSP_SINE[i] != (int)floor(127 * sin(2 * M_PI * i / 64) + 0.5)) sine_ok = 0;
    EXPECT(sine_ok, "WSP_SINE = floor(127 sin(2 pi i / 64) + 0.5)");
    EXPECT(wsp_period(A4 * 64) == 218, "A-4 = N 218, d 1830 (N %d): %.3f Hz", wsp_period(A4 * 64), 96000.0 / 218);
    EXPECT(wsp_period(WS_NOTE_MIN * 64) <= 2048 && wsp_period(WSP_PITCH_MIN) == 2048 && wsp_period(0) == 2048,
           "pitches below 1167 clamp to N 2048 (G-1 = %d)", wsp_period(WS_NOTE_MIN * 64));
    EXPECT(96000.0 / (440.0 * pow(2.0, ((WSP_PITCH_MIN - 1) / 64.0 - A4) / 12.0)) > 2048.5, "pitch 1166 would need N above 2048");
    EXPECT(wsp_period(WSP_PITCH_MAX) == wsp_period(100000) && wsp_period(WSP_PITCH_MAX) >= 1, "the top clamps to B-7 + 63/64");
    double worst = 0;
    int nearest = 1;
    for (int n = WS_NOTE_MIN; n <= WS_NOTE_MAX; ++n) {
        const double target = 440.0 * pow(2.0, (n - A4) / 12.0), ideal = 96000.0 / target;
        const int N = wsp_period(n * 64);
        if (fabs(N - ideal) > 0.5 + 1e-6) nearest = 0;
        const double cents = fabs(1200 * log2(96000.0 / N / target));
        if (cents > worst) worst = cents;
    }
    EXPECT(nearest, "every note's N is the nearest integer to 96000 / f");
    EXPECT(worst < 23.35, "every note G-1..B-7 within 23.3 cents (worst %.2f)", worst);
    int steps_ok = 1;
    for (int root = WS_NOTE_MIN; root <= WS_NOTE_MAX; ++root) {
        if (wsp_sample_step(root * 64, root) != 65536) steps_ok = 0;
        if (root * 64 + 768 <= WSP_PITCH_MAX && wsp_sample_step(root * 64 + 768, root) != 131072) steps_ok = 0;
        if (root * 64 - 768 >= WSP_PITCH_MIN && wsp_sample_step(root * 64 - 768, root) != 32768) steps_ok = 0;
    }
    EXPECT(steps_ok, "sample step: the root plays 65536, an octave up 131072, down 32768");
    printf("  A-4 %.3f Hz (+%.2f cents); worst %.2f cents\n", 96000.0 / 218, 1200 * log2(96000.0 / 218 / 440), worst);
}

/* ---- images -------------------------------------------------------------------------------------------------------- */

static void test_images(void) {
    printf("images\n");
    for (int i = 0; i < NREFS; ++i) {
        const Ref* r = REFS[i];
        const WsInfo* info = &g_img[i].info;
        EXPECT(info->loaded == WS_LOADED && info->model == r->model && info->lanes == (r->model ? 5 : 4),
               "%s loads as model %d", r->name, r->model);
        int rejected = 0;
        for (int k = 0; k < r->corrupt_n; ++k) {
            memcpy(&g_tmp, &g_img[i], sizeof g_tmp);
            g_tmp.bytes[r->corrupt_off(k)] = (uint8_t)r->corrupt_val(k);
            if (ws_load_image(&g_tmp) != 0 && g_tmp.info.problem) ++rejected;
            else printf("  %s: byte %d = %d accepted\n", r->name, r->corrupt_off(k), r->corrupt_val(k));
        }
        EXPECT(rejected == r->corrupt_n, "%s: every corruption rejected (%d of %d)", r->name, rejected, r->corrupt_n);
        memcpy(&g_tmp, &g_img[i], sizeof g_tmp);
        EXPECT(ws_load_image(&g_tmp) == 0 && !memcmp(&g_tmp.info, info, sizeof g_tmp.info), "%s: loading is a pure function",
               r->name);
    }
    EXPECT(relayout(&g_tmp, &g_img[0], 64), "a re-laid copy of ws_song loads: %s", g_tmp.info.problem ? g_tmp.info.problem : "");
}

/* ---- the reference player ------------------------------------------------------------------------------------------ */

/* Plays reference r from the start through a machine of its model, comparing every tick; writes the trace when
 * `trace_path` is set. */
static void play_reference(const Ref* r, const char* trace_path) {
    const WsImage* image = image_of(r);
    WsMachine* m = wsm_create(RATE, r->model);
    trace_on(m);
    clear(&g_col);
    int mode = -1;
    EXPECT(wsm_start(m, image, 0, 0, SEEK_BUDGET, &mode) == WSM_OK && mode == WSM_SEEK_FROM_START, "%s: start", r->name);
    EXPECT(same_reset(&g_col, r), "%s: reset batch = reference (%d writes, reference %d)", r->name,
           g_col.ne ? g_col.e[0].n : -1, r->reset_n);
    FILE* f = trace_path ? fopen(trace_path, "w") : NULL;
    if (f) {
        fprintf(f, "R\n");
        if (g_col.ne) print_writes(f, g_col.w + g_col.e[0].first, g_col.e[0].n);
    }
    int mismatches = 0, first = -1, position_errors = 0, irq_errors = 0, transfers = 0, max_writes = 0;
    for (int t = 0; t < WS_TEST_TICKS; ++t) {
        if (!pos_is(m, r, t)) ++position_errors;
        int o, w, k;
        wsm_position(m, &o, &w, &k);
        traced_tick(m);
        if (!same_tick(&g_col, r, t)) {
            if (first < 0) first = t;
            ++mismatches;
        }
        if (wsm_timer_interrupts(m) != r->irqs(t)) ++irq_errors;
        for (int e = 0; e < g_col.ne; ++e) {
            const Event* x = &g_col.e[e];
            if (x->type == EV_DMA) ++transfers;
            else if (x->n > max_writes) max_writes = x->n;
            if (!f) continue;
            if (x->type == EV_DMA) {
                fprintf(f, "D %lld %d %02X\n", (long long)x->cycle, x->target, x->data);
            } else {
                if (x->kind == WSM_TRACE_TICK) fprintf(f, "T %d %d %d %d %lld\n", t, o, w, k, (long long)x->cycle);
                else fprintf(f, "I %lld\n", (long long)x->cycle);
                print_writes(f, g_col.w + x->first, x->n);
            }
        }
    }
    if (f) fclose(f);
    trace_off(m);
    EXPECT(mismatches == 0, "%s: tick and timer-interrupt writes and cycles = reference player in all %d ticks (%d differ, "
                            "first at tick %d)",
           r->name, WS_TEST_TICKS, mismatches, first);
    if (first >= 0) {
        printf("  %s: first difference at tick %d; reference:", r->name, first);
        for (int j = r->offsets(first); j < r->offsets(first + 1) && j < r->offsets(first) + 24; ++j)
            printf(" %d:%04X=%02X", r->space(j), r->addr(j), r->vals(j));
        printf("\n");
    }
    EXPECT(position_errors == 0, "%s: positions = reference in every tick (%d differ)", r->name, position_errors);
    EXPECT(irq_errors == 0, "%s: timer interrupts per tick = reference (%d differ)", r->name, irq_errors);
    printf("  %s: %d ticks, max %d writes per batch, %d DMA transfers\n", r->name, WS_TEST_TICKS, max_writes, transfers);
    if (r->model == WS_MODEL_COLOR && r == &REF_wsc_song) EXPECT(transfers > 0, "%s: Sound DMA transfers happen", r->name);
    wsm_destroy(m);
}

static void test_reference(const char* dir) {
    printf("reference player\n");
    char path[512];
    snprintf(path, sizeof path, "%s/ws_trace.txt", dir);
    play_reference(&REF_ws_song, path);
    snprintf(path, sizeof path, "%s/wsc_trace.txt", dir);
    play_reference(&REF_wsc_song, path);
    play_reference(&REF_wsc_vgm, NULL);
    play_reference(&REF_ws_tone, NULL);
    for (int i = 0; i < NREFS; ++i) {
        char name[64];
        snprintf(name, sizeof name, "%s.bin", REFS[i]->name);
        write_bytes(dir, name, g_img[i].bytes, sizeof g_img[i].bytes);
    }
}

/* ---- audio ----------------------------------------------------------------------------------------------------------- */

static void test_audio(const char* dir) {
    printf("audio\n");
    static const Ref* const songs[] = {&REF_ws_song, &REF_wsc_song, &REF_ws_tone};
    static const char* const names[] = {"ws_song.wav", "wsc_song.wav", "ws_tone.wav"};
    for (int s = 0; s < 3; ++s) {
        uint64_t hash[2] = {0, 0};
        for (int pass = 0; pass < 2; ++pass) {
            WsMachine* m = wsm_create(RATE, songs[s]->model);
            int mode;
            wsm_start(m, image_of(songs[s]), 0, 0, SEEK_BUDGET, &mode);
            float* buf;
            const double t0 = now_us();
            const int frames = render(m, EVIDENCE_TICKS, &buf);
            const double us = (now_us() - t0) / EVIDENCE_TICKS;
            hash[pass] = fnv(0xCBF29CE484222325ull, buf, (size_t)frames * 2 * sizeof(float));
            if (pass == 0) {
                const double r = rms(buf, frames), pk = peak(buf, frames);
                const int want = (int)((int64_t)EVIDENCE_TICKS * WS_TICK_CYCLES * RATE / WS_CPU_HZ);
                EXPECT(abs(frames - want) <= 2, "%s: %d ticks = %d frames (got %d)", songs[s]->name, EVIDENCE_TICKS, want, frames);
                EXPECT(r > 0.005, "%s: non-silent (rms %.4f)", songs[s]->name, r);
                char path[512];
                snprintf(path, sizeof path, "%s/%s", dir, names[s]);
                write_wav(path, buf, frames);
                printf("  %s: rms %.4f, peak %.4f, %.1f us per tick (%.0fx realtime)\n", songs[s]->name, r, pk, us,
                       1e6 / TICKS_PER_SECOND / us);
            }
            free(buf);
            wsm_destroy(m);
        }
        EXPECT(hash[0] == hash[1], "%s: bit-identical on a second start", songs[s]->name);
    }
}

/* ws_tone: A-4 on CH1 sounds 96000 / 218 = 440.367 Hz (measured from the audio; tolerance unverified). */
static void test_tone_pitch(void) {
    printf("A-4 from the audio\n");
    WsMachine* m = wsm_create(RATE, WS_MODEL_MONO);
    int mode;
    wsm_start(m, image_of(&REF_ws_tone), 0, 0, SEEK_BUDGET, &mode);
    float* buf;
    const int frames = render(m, 240, &buf);
    const double hz = frames > RATE * 2 ? peak_hz(buf + 2 * (RATE / 2), RATE * 2, 430, 450, 0.02) : 0;
    EXPECT(fabs(hz - 96000.0 / 218) < 0.25, "A-4 measured %.3f Hz (%.3f expected: N 218)", hz, 96000.0 / 218);
    EXPECT(wsp_shadow(wsm_player(m), 0x80) == (1830 & 0xFF) && wsp_shadow(wsm_player(m), 0x81) == (1830 >> 8),
           "CH1's frequency register holds d = 1830");
    printf("  %.3f Hz\n", hz);
    free(buf);
    wsm_destroy(m);
}

/* ---- seek ------------------------------------------------------------------------------------------------------------- */

/* Seeks to the row starting at reference tick k: lands, then 200 ticks equal the reference, and 60 ticks of sound
 * equal straight play bit for bit. */
static void seek_check(const Ref* r, int k, const char* why) {
    const int o = r->pos(3 * k), row = r->pos(3 * k + 1);
    const WsImage* image = image_of(r);
    WsMachine* m = wsm_create(RATE, r->model);
    int mode = -1;
    const double t0 = now_us();
    EXPECT(wsm_start(m, image, o, row, SEEK_BUDGET, &mode) == WSM_OK && mode == WSM_SEEK_FROM_START, "%s: seek %d/%d (%s)",
           r->name, o, row, why);
    const double us = now_us() - t0;
    const int kk = ref_row_start(r, o, row);
    EXPECT(kk >= 0 && pos_is(m, r, kk), "%s: lands on %d/%d tick 0", r->name, o, row);
    if (kk < 0) {
        wsm_destroy(m);
        return;
    }
    trace_on(m);
    int bad = 0;
    const int n = WS_TEST_TICKS - kk < 200 ? WS_TEST_TICKS - kk : 200;
    for (int j = 0; j < n; ++j) {
        if (!pos_is(m, r, kk + j)) ++bad;
        traced_tick(m);
        if (!same_tick(&g_col, r, kk + j)) ++bad;
    }
    trace_off(m);
    EXPECT(bad == 0, "%s: seek %d/%d: %d ticks of writes, interrupts and cycles = straight play (%d differ)", r->name, o, row,
           n, bad);
    WsMachine* a = wsm_create(RATE, r->model);
    wsm_start(a, image, 0, 0, SEEK_BUDGET, &mode);
    for (int j = 0; j < kk; ++j) wsm_tick(a, g_out);
    float *x, *y;
    const int fx = render(a, 60, &x);
    wsm_start(m, image, o, row, SEEK_BUDGET, &mode);
    const int fy = render(m, 60, &y);
    EXPECT(fx == fy && !memcmp(x, y, sizeof(float) * 2 * (size_t)fx), "%s: seek %d/%d (%d ticks, %s): sound = straight play",
           r->name, o, row, kk, why);
    printf("  %s %d/%d (%s): %d ticks fast-forwarded in %.0f us\n", r->name, o, row, why, kk, us);
    free(x);
    free(y);
    wsm_destroy(a);
    wsm_destroy(m);
}

static void test_seek(void) {
    printf("seek\n");
    static const int at_ticks[] = {5, 40, 120, 250, 400, 600, 900, 1200};
    for (int s = 0; s < 2; ++s) {
        const Ref* r = s ? &REF_wsc_song : &REF_ws_song;
        for (size_t i = 0; i < sizeof at_ticks / sizeof at_ticks[0]; ++i) {
            const int k = ref_row_start(r, r->pos(3 * at_ticks[i]), r->pos(3 * at_ticks[i] + 1));
            if (k >= 0) seek_check(r, k, "spread");
        }
    }
    /* Row starts of wsc_song where a looping DMA sample sounds, and where a CPU-fed sample has been streaming for at
     * least 100 ticks: found by straight play. */
    int dma_at = -1, fed_at = -1, fed_since[WS_CHANNELS];
    for (int c = 0; c < WS_CHANNELS; ++c) fed_since[c] = -1;
    WsMachine* m = wsm_create(RATE, WS_MODEL_COLOR);
    int mode;
    wsm_start(m, image_of(&REF_wsc_song), 0, 0, SEEK_BUDGET, &mode);
    for (int t = 0; t < WS_TEST_TICKS && (dma_at < 0 || fed_at < 0); ++t) {
        const WsPlayer* p = wsm_player(m);
        const WsApu* apu = wsm_apu(m);
        if (p->tick == 0 && t > 0 && ref_row_start(&REF_wsc_song, p->order, p->row) == t) {
            if (dma_at < 0 && p->owner >= 0 && apu->dma.enable && apu->dma.loop && t > 50) dma_at = t;
            for (int c = 0; c < WS_CHANNELS; ++c)
                if (fed_at < 0 && fed_since[c] >= 0 && t - fed_since[c] >= 100) fed_at = t;
        }
        wsm_tick(m, g_out);
        for (int c = 0; c < WS_CHANNELS; ++c) {
            if (!p->lane[c].feeding) fed_since[c] = -1;
            else if (fed_since[c] < 0) fed_since[c] = t;
        }
    }
    wsm_destroy(m);
    EXPECT(dma_at > 0, "wsc_song has a row start with a looping DMA sample sounding");
    EXPECT(fed_at > 0, "wsc_song has a row start with a CPU-fed sample streaming for 100 ticks");
    if (dma_at > 0) seek_check(&REF_wsc_song, dma_at, "a looping DMA sample");
    if (fed_at > 0) seek_check(&REF_wsc_song, fed_at, "a CPU-fed sample keyed long before");
    /* the order fallback */
    m = wsm_create(RATE, WS_MODEL_MONO);
    const Ref* r = &REF_ws_song;
    int target = -1;
    for (int t = 100; t < WS_TEST_TICKS && target < 0; ++t)
        if (r->pos(3 * t + 2) == 0 && r->pos(3 * t + 1) > 0 && ref_row_start(r, r->pos(3 * t), r->pos(3 * t + 1)) == t) target = t;
    EXPECT(target > 0, "ws_song: a row start past tick 100 with row > 0");
    if (target > 0) {
        const int o = r->pos(3 * target), row = r->pos(3 * target + 1);
        EXPECT(wsm_start(m, image_of(r), o, row, 1, &mode) == WSM_OK && mode == WSM_SEEK_FROM_ORDER,
               "small budget: the order fallback to %d/%d", o, row);
        int po, pr, pt;
        wsm_position(m, &po, &pr, &pt);
        EXPECT(po == o && pr == row && pt == 0, "the fallback lands on %d/%d (%d/%d/%d)", o, row, po, pr, pt);
        EXPECT(wsm_tick(m, g_out) > 0, "and plays");
    }
    const WsInfo* info = &image_of(r)->info;
    EXPECT(wsm_start(m, image_of(r), info->orders, 0, SEEK_BUDGET, &mode) == WSM_ERR_IMAGE, "order past the song rejected");
    EXPECT(wsm_start(m, image_of(r), 0, info->rows[0], SEEK_BUDGET, &mode) == WSM_ERR_IMAGE, "row past the order rejected");
    EXPECT(wsm_start(m, image_of(&REF_wsc_song), 0, 0, SEEK_BUDGET, &mode) == WSM_ERR_IMAGE,
           "a Color image on the original model's machine rejected");
    wsm_destroy(m);
}

/* ---- live edits ------------------------------------------------------------------------------------------------------- */

/* Plays r from the start, applying `alt0`/`alt1` (odd/even ticks) before every tick; compares positions, the DMA
 * transfers and the sound with plain straight play, and (when `writes`) every batch with the reference. */
static int play_applying(const Ref* r, const WsImage* alt0, const WsImage* alt1, int writes, int* first) {
    const WsImage* image = image_of(r);
    WsMachine* m = wsm_create(RATE, r->model);
    WsMachine* s = wsm_create(RATE, r->model);
    int mode, bad = 0;
    wsm_start(m, image, 0, 0, SEEK_BUDGET, &mode);
    wsm_start(s, image, 0, 0, SEEK_BUDGET, &mode);
    static float out_s[WSM_MAX_TICK_FRAMES * 2];
    *first = -1;
    for (int t = 0; t < WS_TEST_TICKS; ++t) {
        wsm_apply_image(m, (t % 2) ? alt0 : alt1);
        if (!pos_is(m, r, t)) ++bad;
        trace_on(m);
        const int n = traced_tick(m);
        trace_off(m);
        const uint64_t dm = dma_digest(0, &g_col);
        int ok = !writes || same_tick(&g_col, r, t);
        trace_on(s);
        clear(&g_col);
        const int ns = wsm_tick(s, out_s);
        trace_off(s);
        ok = ok && dm == dma_digest(0, &g_col) && n == ns && !memcmp(g_out, out_s, sizeof(float) * 2 * (size_t)n);
        if (!ok) {
            if (*first < 0) *first = t;
            ++bad;
        }
    }
    wsm_destroy(m);
    wsm_destroy(s);
    return bad;
}

/* Runs straight play of r until `pred` holds at a tick boundary (at most WS_TEST_TICKS ticks); returns the machine
 * there (the caller destroys it) or NULL. */
typedef int (*Pred)(const WsMachine* m, int* lane);

static WsMachine* play_until(const Ref* r, Pred pred, int* lane, int* at) {
    WsMachine* m = wsm_create(RATE, r->model);
    int mode;
    wsm_start(m, image_of(r), 0, 0, SEEK_BUDGET, &mode);
    for (int t = 0; t < WS_TEST_TICKS; ++t) {
        if (t > 0 && pred(m, lane)) {
            *at = t;
            return m;
        }
        wsm_tick(m, g_out);
    }
    wsm_destroy(m);
    return NULL;
}

static int dma_sounding(const WsMachine* m, int* lane) {
    const WsPlayer* p = wsm_player(m);
    *lane = p->owner;
    return p->owner >= 0 && wsm_apu(m)->dma.enable;
}

static int wave_loaded(const WsMachine* m, int* lane) {
    const WsPlayer* p = wsm_player(m);
    for (int c = 0; c < 4; ++c) {
        if (p->lane[c].sounding && p->lane[c].kind == WS_KIND_TONE && p->lane[c].loaded >= 0) {
            *lane = c;
            return 1;
        }
    }
    return 0;
}

static void test_live_edits(void) {
    printf("live edits\n");
    int first;
    for (int s = 0; s < 2; ++s) {
        const Ref* r = s ? &REF_wsc_song : &REF_ws_song;
        const WsImage* image = image_of(r);
        int bad = play_applying(r, image, image, 1, &first);
        EXPECT(bad == 0, "%s: the same image before every tick: writes, DMA and sound = reference/straight (%d differ, first %d)",
               r->name, bad, first);
        const int moved = relayout(&g_tmp2, image, 64);
        EXPECT(moved, "%s: a re-laid copy (data moved by 64 bytes) loads", r->name);
        if (moved) {
            /* Without DMA the writes stay the reference's; with DMA, programs keyed under the moved image name other
             * addresses, so the DMA transfers and the sound are compared instead (the rebase keeps them). */
            bad = play_applying(r, &g_tmp2, image, r->model == WS_MODEL_MONO, &first);
            EXPECT(bad == 0, "%s: re-laid image and the original alternately before every tick: = straight play (%d differ, "
                             "first %d)",
                   r->name, bad, first);
        }
    }
    /* A sounding DMA sample: moved identical bytes rebase the chip's source; changed bytes stop it at the next tick. */
    int lane = -1, at = -1;
    WsMachine* m = play_until(&REF_wsc_song, dma_sounding, &lane, &at);
    EXPECT(m != NULL, "wsc_song sounds a DMA sample");
    if (m && relayout(&g_tmp2, image_of(&REF_wsc_song), 64)) {
        const uint32_t src = wsm_apu(m)->dma.source, shadow = wsm_apu(m)->dma.shadow_source;
        wsm_apply_image(m, &g_tmp2);
        EXPECT(wsm_apu(m)->dma.source == ((src + 64) & 0xFFFFF) && wsm_apu(m)->dma.shadow_source == ((shadow + 64) & 0xFFFFF) &&
                   wsm_player(m)->owner == lane && !wsm_player(m)->dma_kill,
               "moved identical bytes: DMA source and shadow rebased by +64, the note keeps playing");
        /* the DMA copy for its lane changed in the moved image: stop at the next tick's start */
        const WsPlayer* p = wsm_player(m);
        const uint8_t* rec = g_tmp2.bytes + WS_OFF_INSTRUMENTS + WS_INSTRUMENT_SIZE * (p->lane[lane].slot - 1);
        const uint8_t* e = g_tmp2.bytes + WS_OFF_SAMPLES + WS_SAMPLE_ENTRY_SIZE * (rec[WS_I_SOURCE] & 63);
        const uint32_t off = ws_u32(e + (lane == WS_VOICE_CHANNEL ? WS_S_OFFSET : WS_S_SIGNED_OFFSET));
        memcpy(&g_tmp, &g_tmp2, sizeof g_tmp);
        g_tmp.bytes[WS_OFF_DATA + off] ^= 0x01;
        EXPECT(ws_load_image(&g_tmp) == 0, "the changed copy loads");
        wsm_apply_image(m, &g_tmp);
        EXPECT(p->dma_kill && p->owner == lane, "changed bytes: dma_kill set, the note sounds until the next tick");
        trace_on(m);
        traced_tick(m);
        trace_off(m);
        const Event* b0 = g_col.ne ? &g_col.e[0] : NULL;
        EXPECT(b0 && b0->type == EV_BATCH && b0->n >= 1 && same_write(&g_col.w[b0->first], WS_SPACE_PORT, 0x52, 0) && !p->dma_kill,
               "the next tick opens with $52 = 0 (the DMA note stopped)");
    }
    if (m) wsm_destroy(m);
    /* A loaded wave whose 32 values changed reloads at the lane's next update. */
    m = play_until(&REF_ws_song, wave_loaded, &lane, &at);
    EXPECT(m != NULL, "ws_song sounds a wavetable tone");
    if (m) {
        const int w = wsm_player(m)->lane[lane].loaded;
        memcpy(&g_tmp, image_of(&REF_ws_song), sizeof g_tmp);
        uint8_t* wave = g_tmp.bytes + WS_OFF_WAVES + WS_WAVE_SIZE * w;
        wave[0] ^= 1;
        EXPECT(ws_load_image(&g_tmp) == 0, "the edited wave loads");
        wsm_apply_image(m, &g_tmp);
        EXPECT(wsm_player(m)->lane[lane].loaded == -1, "lane %d: the changed wave %d is marked for reloading", lane, w);
        trace_on(m);
        traced_tick(m);
        trace_off(m);
        int reload = 0;
        if (g_col.ne && g_col.e[0].type == EV_BATCH)
            for (int i = 0; i < g_col.e[0].n; ++i) {
                const WsWrite* x = &g_col.w[g_col.e[0].first + i];
                if (x->space == WS_SPACE_RAM && x->addr >= WS_WAVE_RAM + 16 * lane && x->addr < WS_WAVE_RAM + 16 * lane + 16) reload = 1;
            }
        EXPECT(reload || !wsm_player(m)->lane[lane].sounding, "lane %d reloads its wave at the next update", lane);
        wsm_destroy(m);
    }
    /* Header shared state: a new timer reload and speaker shift take effect. */
    m = wsm_create(RATE, WS_MODEL_MONO);
    int mode;
    wsm_start(m, image_of(&REF_ws_song), 0, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 7; ++k) wsm_tick(m, g_out);
    memcpy(&g_tmp, image_of(&REF_ws_song), sizeof g_tmp);
    g_tmp.bytes[WS_H_TIMER] = (uint8_t)(g_tmp.bytes[WS_H_TIMER] == 7 ? 9 : 7);
    g_tmp.bytes[WS_H_SHIFT] = (uint8_t)((g_tmp.bytes[WS_H_SHIFT] + 1) & 3);
    EXPECT(ws_load_image(&g_tmp) == 0, "the header edit loads");
    wsm_apply_image(m, &g_tmp);
    EXPECT(wsm_player(m)->timer == g_tmp.bytes[WS_H_TIMER] && wsm_player(m)->shift == g_tmp.bytes[WS_H_SHIFT],
           "a header edit: timer reload %d, speaker shift %d", wsm_player(m)->timer, wsm_player(m)->shift);
    wsm_tick(m, g_out);
    EXPECT(wsp_shadow(wsm_player(m), 0x91) == (0x09 | wsm_player(m)->shift << 1), "the next tick writes $91 = $%02X",
           wsp_shadow(wsm_player(m), 0x91));
    wsm_destroy(m);
    /* A cut song: the position clamps to the last row of the last order and plays on from the loop order. */
    const WsInfo* info = &image_of(&REF_ws_song)->info;
    if (info->orders >= 2) {
        memcpy(&g_tmp, image_of(&REF_ws_song), sizeof g_tmp);
        uint8_t* b = g_tmp.bytes;
        put16(b + WS_H_ORDERS, 1);
        put16(b + WS_H_LOOP, 0);
        for (int o = 1; o < WS_MAX_ORDERS; ++o) {
            b[WS_OFF_ROWS + o] = 0;
            memset(b + WS_OFF_ORDERS + 2 * WS_CHANNELS * o, 0, 2 * WS_CHANNELS);
        }
        /* jumps past order 0 would be rejected: drop the song to its first order only when it still loads */
        if (ws_load_image(&g_tmp) == 0) {
            m = wsm_create(RATE, WS_MODEL_MONO);
            if (wsm_start(m, image_of(&REF_ws_song), 1, 0, SEEK_BUDGET, &mode) == WSM_OK) {
                wsm_tick(m, g_out);
                int o, r, t;
                wsm_position(m, &o, &r, &t);
                const int started = o == 1 && t > 0; /* a speed-1 row has already moved on */
                wsm_apply_image(m, &g_tmp);
                wsm_position(m, &o, &r, &t);
                EXPECT(o == 0 && (started ? r == info->rows[0] - 1 : r <= info->rows[0] - 1),
                       "the cut song: clamped into order 0 (%d/%d/%d)", o, r, t);
                int reached = o == 0 && r == 0 && t == 0;
                for (int k = 0; k < 256 * 8 && !reached; ++k) {
                    wsm_tick(m, g_out);
                    wsm_position(m, &o, &r, &t);
                    reached = o == 0 && r == 0 && t == 0;
                }
                EXPECT(reached, "then the loop order plays from its first row");
            }
            wsm_destroy(m);
        } else {
            printf("  (cut song not built: %s)\n", g_tmp.info.problem);
        }
    }
}

/* ---- audition ---------------------------------------------------------------------------------------------------------- */

static int ticks_until_end(WsMachine* m, int limit) {
    int n = 0;
    while (n < limit && wsm_tick(m, g_out) > 0) ++n;
    return n;
}

static void test_audition(void) {
    printf("audition\n");
    WsMachine* m = wsm_create(RATE, WS_MODEL_MONO);
    const WsPlayer* p = wsm_player(m);
    EXPECT(wsm_audition(m, &g_tone_mono, 0, A4, T_SINE, 0) == WSM_OK, "A-4 on the sine wave");
    float* buf;
    int frames = render(m, 180, &buf);
    const double hz = peak_hz(buf + 2 * (RATE / 4), RATE * 2, 430, 450, 0.02);
    EXPECT(fabs(hz - 96000.0 / 218) < 0.25, "A-4 measured %.3f Hz (%.3f: N 218)", hz, 96000.0 / 218);
    EXPECT(rms(buf + 2 * (frames - RATE / 2), RATE / 2) > 0.5 * rms(buf + 2 * (RATE / 2), RATE / 2), "held");
    free(buf);
    EXPECT(p->timer == g_tone_mono.bytes[WS_H_TIMER], "header shared state kept");
    /* fine +64 = one semitone up */
    wsm_audition(m, &g_tone_mono, 0, A4, T_SINE, 64);
    frames = render(m, 120, &buf);
    const double up = peak_hz(buf + 2 * (RATE / 4), RATE, 455, 480, 0.02);
    EXPECT(fabs(up - 96000.0 / wsp_period((A4 + 1) * 64)) < 0.4, "fine +64 = A#4 %.3f Hz (%.3f)", up,
           96000.0 / wsp_period((A4 + 1) * 64));
    free(buf);
    /* release with a release point, then the volume reaches 0 */
    EXPECT(wsm_audition(m, &g_tone_mono, 0, A4, T_RELEASE, 0) == WSM_OK, "release instrument");
    for (int k = 0; k < 30; ++k) wsm_tick(m, g_out);
    EXPECT(wsm_release(m) == 1, "release phase");
    int ticks = ticks_until_end(m, 1000);
    EXPECT(ticks >= 1 && ticks <= 10, "release ends when the written volume reaches 0 (%d ticks)", ticks);
    /* no release point */
    wsm_audition(m, &g_tone_mono, 0, A4, T_SINE, 0);
    wsm_tick(m, g_out);
    EXPECT(wsm_release(m) == 0 && wsp_shadow(p, 0x90) == 0 && wsm_tick(m, g_out) == 0, "release without a release point: stopped");
    EXPECT(wsm_audition(m, &g_tone_mono, 0, A4, T_SINE, 0) == WSM_OK && wsm_release(m) == 0, "release before any tick: over");
    /* noise only on CH4 */
    for (int c = 0; c < 3; ++c) EXPECT(wsm_audition(m, &g_tone_mono, c, A4, T_NOISE, 0) == WSM_ERR_IMAGE, "noise on CH%d rejected", c + 1);
    EXPECT(wsm_audition(m, &g_tone_mono, 3, A4, T_NOISE, 0) == WSM_OK, "noise on CH4");
    wsm_tick(m, g_out);
    EXPECT(wsp_shadow(p, 0x90) == 0x88 && wsm_apu(m)->noise, "CH4 noise: $90 = $88");
    /* CPU-fed samples: CH2 only (mono), the root plays one frame per interrupt */
    EXPECT(wsm_audition(m, &g_tone_mono, 0, A4, T_LOOP, 0) == WSM_ERR_IMAGE, "a sample on CH1 rejected");
    EXPECT(wsm_audition(m, &g_tone_mono, 1, A4, T_STEREO, 0) == WSM_ERR_IMAGE, "a stereo sample on CH2 rejected");
    for (int octave = 0; octave < 2; ++octave) {
        EXPECT(wsm_audition(m, &g_tone_mono, 1, A4 + 12 * octave, T_LOOP, 0) == WSM_OK, "looping sample on CH2");
        for (int k = 0; k < 4; ++k) wsm_tick(m, g_out);
        EXPECT(p->lane[1].step == (uint32_t)(65536 << octave) && p->lane[1].feeding && wsm_timer_interrupts(m) == 159,
               "root%s: step %u, 159 interrupts per tick at reload 1", octave ? " + 12" : "", p->lane[1].step);
        EXPECT(wsm_apu(m)->voice && wsp_shadow(p, 0x90) == 0x20, "voice mode on ($90 = $20)");
    }
    EXPECT(wsm_audition(m, &g_tone_mono, 1, A4, T_ONESHOT, 0) == WSM_OK, "one-shot sample on CH2");
    ticks = ticks_until_end(m, 10);
    EXPECT(ticks >= 1 && ticks <= 2 && !p->lane[1].sounding, "a 64-frame one-shot (5.3 ms) ends the audition (%d ticks)", ticks);
    /* arguments */
    EXPECT(wsm_audition(m, &g_tone_mono, 0, A4, T_UNDEFINED, 0) == WSM_ERR_IMAGE, "undefined slot rejected");
    EXPECT(wsm_audition(m, &g_tone_mono, 0, A4, 0, 0) == WSM_ERR_IMAGE && wsm_audition(m, &g_tone_mono, 0, A4, 65, 0) == WSM_ERR_IMAGE,
           "slots 0 and 65 rejected");
    EXPECT(wsm_audition(m, &g_tone_mono, 0, WS_NOTE_MIN - 1, T_SINE, 0) == WSM_ERR_IMAGE &&
               wsm_audition(m, &g_tone_mono, 0, WS_NOTE_MAX + 1, T_SINE, 0) == WSM_ERR_IMAGE,
           "notes 18 and 96 rejected");
    EXPECT(wsm_audition(m, &g_tone_mono, 0, WS_NOTE_MIN, T_SINE, 0) == WSM_OK &&
               wsm_audition(m, &g_tone_mono, 0, WS_NOTE_MAX, T_SINE, 0) == WSM_OK,
           "notes 19 and 95 accepted");
    EXPECT(wsm_audition(m, &g_tone_mono, 4, A4, T_LOOP, 0) == WSM_ERR_IMAGE && wsm_audition(m, &g_tone_mono, -1, A4, T_SINE, 0) == WSM_ERR_IMAGE,
           "lanes 4 (no Hyper Voice) and -1 rejected on the original model");
    EXPECT(wsm_audition(m, &g_tone_mono, 0, A4, T_SINE, 128) == WSM_ERR_IMAGE &&
               wsm_audition(m, &g_tone_mono, 0, A4, T_SINE, -129) == WSM_ERR_IMAGE,
           "fine 128 and -129 rejected");
    EXPECT(wsm_audition(m, &g_tone_color, 0, A4, T_SINE, 0) == WSM_ERR_IMAGE, "a Color image on the original model rejected");
    wsm_destroy(m);

    m = wsm_create(RATE, WS_MODEL_COLOR);
    p = wsm_player(m);
    /* Hyper Voice: CPU-fed stereo sample, then DMA samples */
    EXPECT(wsm_audition(m, &g_tone_color, 4, A4, T_SINE, 0) == WSM_ERR_IMAGE, "a tone on Hyper Voice rejected");
    EXPECT(wsm_audition(m, &g_tone_color, 4, A4, T_STEREO, 0) == WSM_OK, "a stereo sample on Hyper Voice");
    frames = render(m, 30, &buf);
    EXPECT(rms(buf, frames) > 0.01 && wsm_apu(m)->hv.enable, "Hyper Voice sounds (rms %.4f)", rms(buf, frames));
    free(buf);
    EXPECT(wsm_audition(m, &g_tone_color, 0, A4, T_DMA_ONESHOT, 0) == WSM_ERR_IMAGE, "a DMA sample on CH1 rejected");
    for (int lane = 1; lane <= 4; lane += 3) {
        EXPECT(wsm_audition(m, &g_tone_color, lane, A4, T_DMA_ONESHOT, 0) == WSM_OK, "a one-shot DMA sample on lane %d", lane);
        wsm_tick(m, g_out);
        EXPECT(p->owner == lane && p->lane[lane].sounding, "lane %d owns the DMA", lane);
        ticks = ticks_until_end(m, 20);
        EXPECT(ticks <= 2 && !wsm_apu(m)->dma.enable && p->lane[lane].sounding,
               "lane %d: the 64-byte one-shot at 24000 bytes/s ends the audition while the note still counts as sounding "
               "(%d ticks)",
               lane, ticks);
        EXPECT(wsm_audition(m, &g_tone_color, lane, A4, T_DMA_LOOP, 0) == WSM_OK, "a looping DMA sample on lane %d", lane);
        for (int k = 0; k < 60; ++k) wsm_tick(m, g_out);
        EXPECT(p->owner == lane && wsm_apu(m)->dma.enable && wsm_apu(m)->dma.rate == 0, "lane %d: the loop keeps streaming (4000 bytes/s)",
               lane);
        EXPECT(wsm_release(m) == 0 && p->owner < 0 && !wsm_apu(m)->dma.enable, "lane %d: release stops the DMA ($52 = 0)", lane);
    }
    wsm_destroy(m);
}

/* ---- mute through the machine --------------------------------------------------------------------------------------- */

static void test_mute(void) {
    printf("mute\n");
    for (int s = 0; s < 2; ++s) {
        const Ref* r = s ? &REF_wsc_song : &REF_ws_song;
        WsMachine* m = wsm_create(RATE, r->model);
        WsMachine* plain = wsm_create(RATE, r->model);
        int mode, bad = 0, bad_state = 0;
        wsm_start(m, image_of(r), 0, 0, SEEK_BUDGET, &mode);
        wsm_start(plain, image_of(r), 0, 0, SEEK_BUDGET, &mode);
        wsm_mute(m, 1, 1);
        wsm_mute(m, 3, 1);
        if (r->model == WS_MODEL_COLOR) wsm_mute(m, 4, 1);
        trace_on(m);
        for (int t = 0; t < WS_TEST_TICKS; ++t) {
            if (t == 600) {
                for (int c = 0; c < WS_CHANNELS; ++c) wsm_mute(m, c, 0);
            }
            traced_tick(m);
            wsm_tick(plain, g_out);
            if (!same_tick(&g_col, r, t)) ++bad;
            if (!ws_apu_same_state(wsm_apu(m), wsm_apu(plain))) ++bad_state;
        }
        trace_off(m);
        EXPECT(bad == 0, "%s: muted lanes leave the player's writes unchanged (%d ticks differ)", r->name, bad);
        EXPECT(bad_state == 0, "%s: muted lanes leave the sound unit's state unchanged (%d ticks differ)", r->name, bad_state);
        wsm_start(m, image_of(r), 0, 0, SEEK_BUDGET, &mode);
        for (int k = 0; k < 120; ++k) wsm_tick(m, g_out);
        for (int c = 0; c < (r->model ? 5 : 4); ++c) wsm_mute(m, c, 1);
        float* buf;
        int frames = render(m, 30, &buf);
        const double quiet = rms(buf + 2 * (RATE / 5), frames - RATE / 5);
        EXPECT(quiet < 0.0005, "%s: all lanes muted: rms %.6f after 200 ms", r->name, quiet);
        free(buf);
        for (int c = 0; c < (r->model ? 5 : 4); ++c) wsm_mute(m, c, 0);
        frames = render(m, 60, &buf);
        EXPECT(rms(buf, frames) > 0.002, "%s: unmuted: resumes (rms %.4f)", r->name, rms(buf, frames));
        free(buf);
        EXPECT(wsm_mute(m, r->model ? 5 : 4, 1) == WSM_ERR_IMAGE && wsm_mute(m, -1, 1) == WSM_ERR_IMAGE, "%s: lanes out of range rejected",
               r->name);
        wsm_destroy(m);
        wsm_destroy(plain);
    }
}

/* ---- VGM ----------------------------------------------------------------------------------------------------------------- */

static void test_vgm(const char* dir) {
    printf("VGM export\n");
    for (size_t i = 0; i < sizeof VGM_REFS / sizeof VGM_REFS[0]; ++i) {
        const VgmRef* v = &VGM_REFS[i];
        const WsImage* image = image_of(v->ref);
        WsVgmInfo info;
        const char* problem = NULL;
        EXPECT(ws_vgm_render(image, WS_TEST_VGM_MAX_TICKS, NULL, 0, &info, &problem) == 0 && info.size == v->size,
               "%s: measuring: %zu bytes (reference %llu)", v->ref->name, info.size, (unsigned long long)v->size);
        const size_t cap = (size_t)v->size + 4096;
        uint8_t* buf = (uint8_t*)malloc(cap);
        const double t0 = now_us();
        const int rc = ws_vgm_render(image, WS_TEST_VGM_MAX_TICKS, buf, cap, &info, &problem);
        const double us = now_us() - t0;
        EXPECT(rc == 0 && info.size == v->size && fnv(0xCBF29CE484222325ull, buf, info.size) == v->fnv64,
               "%s: VGM bytes = the reference player's (%zu vs %llu bytes)", v->ref->name, info.size, (unsigned long long)v->size);
        EXPECT(info.samples == v->total_samples && info.loop_offset == v->loop_offset && info.loop_samples == v->loop_samples &&
                   info.ticks == v->ticks,
               "%s: VGM length and loop (%u ticks, %u samples, loop at %u, %u samples)", v->ref->name, info.ticks, info.samples,
               info.loop_offset, info.loop_samples);
        const uint32_t clock = buf[0xC0] | buf[0xC1] << 8 | buf[0xC2] << 16 | (uint32_t)buf[0xC3] << 24;
        EXPECT(!memcmp(buf, "Vgm ", 4) && buf[8] == 0x71 && buf[9] == 0x01 && clock == WS_VGM_CLOCK && buf[0x34] == 0xCC,
               "%s: header: VGM 1.71, WonderSwan at %u Hz, data at 0x100", v->ref->name, clock);
        EXPECT(ws_vgm_render(image, WS_TEST_VGM_MAX_TICKS, buf, 1000, &info, &problem) == -1 && info.size == v->size,
               "%s: too small a buffer: -1 and the size needed", v->ref->name);
        char name[64];
        snprintf(name, sizeof name, "%s.vgm", v->ref->name);
        write_bytes(dir, name, buf, (size_t)v->size);
        printf("  %s: %llu bytes, %llu ticks, loop %llu samples, rendered in %.0f us\n", v->ref->name, (unsigned long long)v->size,
               (unsigned long long)v->ticks, (unsigned long long)v->loop_samples, us);
        free(buf);
    }
    /* wsc_song plays Hyper Voice notes when it has any: refused */
    WsVgmInfo info;
    const char* problem = NULL;
    const WsImage* wsc = image_of(&REF_wsc_song);
    const int hv = ws_vgm_has_hyper_voice(wsc);
    const int rc = ws_vgm_render(wsc, 2000, NULL, 0, &info, &problem);
    EXPECT(hv ? (rc == -2 && problem != NULL) : rc == 0, "wsc_song: %s", hv ? "refused (Hyper Voice notes)" : "renders");
    EXPECT(!ws_vgm_has_hyper_voice(image_of(&REF_wsc_vgm)) && !ws_vgm_has_hyper_voice(image_of(&REF_ws_song)),
           "wsc_vgm and ws_song have no Hyper Voice notes");
}

static void gd3_fields(const uint8_t* vgm, size_t size, char fields[11][64]) {
    const uint32_t gd3_at = 0x14 + (vgm[0x14] | vgm[0x15] << 8 | vgm[0x16] << 16 | (uint32_t)vgm[0x17] << 24);
    const uint8_t* s = vgm + gd3_at + 12;
    for (int f = 0; f < 11; ++f) {
        int n = 0;
        while (s + 1 < vgm + size) {
            uint32_t c = s[0] | s[1] << 8;
            s += 2;
            if (!c) break;
            if (n > 58) continue;
            if (c < 0x80) fields[f][n++] = (char)c;
            else fields[f][n++] = '?';
        }
        fields[f][n] = 0;
    }
}

static void test_export(void) {
    printf("VGM export entry point\n");
    const VgmRef* v = &VGM_REFS[0];
    const WsImage* image = image_of(v->ref);
    size_t size = 0;
    const char* problem = NULL;
    uint8_t* vgm = ws_export_vgm(image->bytes, sizeof image->bytes, WS_TEST_VGM_MAX_TICKS, "Thor Swan", "Esko", "2026/11/08",
                                 &size, &problem);
    EXPECT(vgm && !problem, "renders (%s)", problem ? problem : "ok");
    if (vgm) {
        const uint32_t gd3_at = 0x14 + (vgm[0x14] | vgm[0x15] << 8 | vgm[0x16] << 16 | (uint32_t)vgm[0x17] << 24);
        const uint32_t eof = 4 + (vgm[4] | vgm[5] << 8 | vgm[6] << 16 | (uint32_t)vgm[7] << 24);
        EXPECT(gd3_at == v->size && eof == size && !memcmp(vgm + gd3_at, "Gd3 ", 4), "GD3 after the reference data (at %u)", gd3_at);
        uint8_t* body = (uint8_t*)malloc((size_t)v->size);
        memcpy(body, vgm, (size_t)v->size);
        const uint32_t ref_eof = (uint32_t)v->size - 4;
        for (int i = 0; i < 4; ++i) body[4 + i] = (uint8_t)(ref_eof >> (8 * i));
        memset(body + 0x14, 0, 4);
        EXPECT(fnv(0xCBF29CE484222325ull, body, (size_t)v->size) == v->fnv64, "the data = the reference VGM (0x04/0x14 aside)");
        free(body);
        char fields[11][64];
        gd3_fields(vgm, size, fields);
        EXPECT(!strcmp(fields[0], "Thor Swan") && !strcmp(fields[4], "WonderSwan (Thor Tracker)") && !strcmp(fields[6], "Esko") &&
                   !strcmp(fields[8], "2026/11/08") && !strcmp(fields[9], "Thor Tracker"),
               "GD3 fields: '%s', '%s', '%s', '%s', '%s'", fields[0], fields[4], fields[6], fields[8], fields[9]);
        ws_export_free(vgm);
    }
    const WsImage* color = image_of(&REF_wsc_vgm);
    vgm = ws_export_vgm(color->bytes, sizeof color->bytes, 600, NULL, NULL, "2026/11/08", &size, &problem);
    EXPECT(vgm != NULL, "a Color song without Hyper Voice exports (%s)", problem ? problem : "ok");
    if (vgm) {
        char fields[11][64];
        gd3_fields(vgm, size, fields);
        EXPECT(!strcmp(fields[4], "WonderSwan Color (Thor Tracker)"), "Color GD3 system '%s'", fields[4]);
        ws_export_free(vgm);
    }
    memcpy(&g_tmp, image_of(&REF_ws_song), sizeof g_tmp);
    g_tmp.bytes[0] = 'X';
    vgm = ws_export_vgm(g_tmp.bytes, sizeof g_tmp.bytes, 600, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && problem, "bad image: NULL with the loader's reason");
    vgm = ws_export_vgm(image->bytes, 100, 600, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && problem && !strcmp(problem, "image size"), "short image refused");
    vgm = ws_export_vgm(image->bytes, sizeof image->bytes, 3, NULL, NULL, NULL, &size, &problem);
    EXPECT(vgm && (vgm[0x1C] | vgm[0x1D] | vgm[0x20] | vgm[0x21]) == 0 && size > 0x100, "a tick limit before the loop: no loop");
    ws_export_free(vgm);
}

/* ---- the Kotlin-generated images --------------------------------------------------------------------------------------- */

static uint8_t* read_file(const char* path, size_t* size) {
    FILE* f = fopen(path, "rb");
    *size = 0;
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    const long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t* p = (uint8_t*)malloc(n > 0 ? (size_t)n : 1);
    *size = fread(p, 1, n > 0 ? (size_t)n : 0, f);
    fclose(f);
    return p;
}

static void test_samples(const char* samples) {
    printf("Kotlin-generated images (%s)\n", samples);
    static const struct {
        const char* file;
        const char* system;
        int model;
        int demo;
    } kImages[] = {
        {"ws-demo.wsimg", "ws-wonderswan", WS_MODEL_MONO, 1},
        {"wsc-demo.wsimg", "ws-wonderswan-color", WS_MODEL_COLOR, 1},
        {"ws-shortest.wsimg", "ws-wonderswan", WS_MODEL_MONO, 0},
        {"wsc-shortest.wsimg", "ws-wonderswan-color", WS_MODEL_COLOR, 0},
    };
    for (size_t i = 0; i < sizeof kImages / sizeof kImages[0]; ++i) {
        char path[512];
        snprintf(path, sizeof path, "%s/%s", samples, kImages[i].file);
        size_t size = 0;
        uint8_t* bytes = read_file(path, &size);
        EXPECT(bytes && size == WS_IMAGE_SIZE, "%s: %zu bytes (run `tools/build.sh exportSamples` first or set THOR_SAMPLES)", path,
               size);
        if (!bytes || size != WS_IMAGE_SIZE) {
            free(bytes);
            continue;
        }
        WsImage* image = &g_tmp;
        memcpy(image->bytes, bytes, WS_IMAGE_SIZE);
        free(bytes);
        EXPECT(ws_load_image(image) == 0 && image->info.model == kImages[i].model, "%s loads as model %d (%s)", kImages[i].file,
               kImages[i].model, image->info.problem ? image->info.problem : "ok");
        if (image->info.loaded != WS_LOADED) continue;
        const ChipEngineOps* ops = chip_engine_ops(kImages[i].system);
        const ChipEngineOps* other = chip_engine_ops(kImages[i].model ? "ws-wonderswan" : "ws-wonderswan-color");
        ChipImage* storage = (ChipImage*)calloc(1, ops->image_storage);
        EXPECT(ops->load_image(storage, image->bytes, WS_IMAGE_SIZE) == CHIP_OK &&
                   other->load_image(storage, image->bytes, WS_IMAGE_SIZE) == CHIP_ERR_IMAGE,
               "%s: its system's ops load it, the other model's refuse it", kImages[i].file);
        free(storage);
        WsMachine* m = wsm_create(RATE, kImages[i].model);
        int mode;
        EXPECT(wsm_start(m, image, 0, 0, SEEK_BUDGET, &mode) == WSM_OK, "%s: start", kImages[i].file);
        float* buf;
        const int ticks = kImages[i].demo ? 600 : 60;
        int frames = render(m, ticks, &buf);
        EXPECT(frames > 0 && rms(buf, frames) > 0.002, "%s: plays (rms %.4f)", kImages[i].file, rms(buf, frames));
        free(buf);
        /* seek determinism: the row straight play starts after ~200 ticks, sought, sounds as straight play */
        wsm_start(m, image, 0, 0, SEEK_BUDGET, &mode);
        int k = 0, o = 0, r = 0, t = 0;
        for (; k < 2000; ++k) {
            wsm_position(m, &o, &r, &t);
            if (k >= 200 && t == 0) break;
            wsm_tick(m, g_out);
        }
        float *x, *y;
        const int fx = render(m, 60, &x);
        WsMachine* n = wsm_create(RATE, kImages[i].model);
        const int rc = wsm_start(n, image, o, r, SEEK_BUDGET, &mode);
        const int fy = rc == WSM_OK ? render(n, 60, &y) : 0;
        if (rc != WSM_OK) y = NULL;
        /* a row re-entered by a jump may be found earlier by the seek: only the first arrival is comparable */
        int first_arrival = 1;
        {
            WsPlayer* q = (WsPlayer*)malloc(sizeof *q);
            wsp_reset(q, image);
            for (int j = 0; j < k; ++j) {
                if (q->order == o && q->row == r && q->tick == 0) first_arrival = 0;
                wsp_tick(q, image);
            }
            free(q);
        }
        if (first_arrival)
            EXPECT(rc == WSM_OK && fx == fy && !memcmp(x, y, sizeof(float) * 2 * (size_t)fx), "%s: seek to %d/%d = straight play",
                   kImages[i].file, o, r);
        free(x);
        free(y);
        wsm_destroy(n);
        wsm_destroy(m);
        /* VGM: the original model's demo exports; a Color song with Hyper Voice notes is refused */
        WsVgmInfo info;
        const char* problem = NULL;
        const int hv = ws_vgm_has_hyper_voice(image);
        const int vrc = ws_vgm_render(image, 45283, NULL, 0, &info, &problem);
        if (kImages[i].model == WS_MODEL_MONO) EXPECT(vrc == 0 && info.size > 0x100, "%s: VGM renders (%zu bytes)", kImages[i].file, info.size);
        else EXPECT(hv ? vrc == -2 && problem : vrc == 0, "%s: VGM %s", kImages[i].file, hv ? "refused (Hyper Voice notes)" : "renders");
    }
}

/* ---- the ops tables ----------------------------------------------------------------------------------------------------- */

static void test_ops(void) {
    printf("ops tables\n");
    for (int model = 0; model < 2; ++model) {
        const char* id = model ? "ws-wonderswan-color" : "ws-wonderswan";
        const ChipEngineOps* ops = chip_engine_ops(id);
        EXPECT(ops && !strcmp(ops->system_id, id), "registry: %s", id);
        if (!ops) continue;
        EXPECT(ops->image_size == WS_IMAGE_SIZE && ops->image_storage == sizeof(WsImage), "%s: sizes", id);
        EXPECT(ops->create(CHIP_MAX_SAMPLE_RATE + 1) == NULL, "%s: rate above the maximum rejected", id);
        const Ref* r = model ? &REF_wsc_song : &REF_ws_song;
        const Ref* wrong = model ? &REF_ws_song : &REF_wsc_song;
        ChipImage* image = (ChipImage*)calloc(1, ops->image_storage);
        ChipImage* tone = (ChipImage*)calloc(1, ops->image_storage);
        EXPECT(ops->load_image(image, image_of(r)->bytes, ops->image_size - 1) == CHIP_ERR_IMAGE, "%s: wrong size rejected", id);
        EXPECT(ops->load_image(image, image_of(wrong)->bytes, ops->image_size) == CHIP_ERR_IMAGE, "%s: the other model refused", id);
        EXPECT(ops->load_image(image, image_of(r)->bytes, ops->image_size) == CHIP_OK, "%s: song loads", id);
        EXPECT(ops->load_image(tone, (model ? &g_tone_color : &g_tone_mono)->bytes, ops->image_size) == CHIP_OK, "%s: tone loads", id);
        ChipMachine* m = ops->create(RATE);
        int mode = -1, o, row, t;
        const int k = ref_row_start(r, r->pos(3 * 300), r->pos(3 * 300 + 1));
        EXPECT(k >= 0 && k + 76 < WS_TEST_TICKS, "%s: a row near tick 300", id);
        if (k >= 0 && k + 76 < WS_TEST_TICKS) {
            const int so = r->pos(3 * k), sr = r->pos(3 * k + 1);
            EXPECT(ops->start(m, image, so, sr, SEEK_BUDGET, &mode) == CHIP_OK && mode == CHIP_SEEK_FROM_START, "%s: start %d/%d",
                   id, so, sr);
            static float out[CHIP_MAX_TICK_FRAMES * 2];
            int frames = 0, n = 0;
            for (int j = 0; j < 75; ++j) frames += (n = ops->tick(m, out));
            const int want = (int)((int64_t)75 * WS_TICK_CYCLES * RATE / WS_CPU_HZ);
            EXPECT(abs(frames - want) <= 2 && n >= 636 && n <= 637, "%s: 75 ticks = %d frames (got %d)", id, want, frames);
            ops->position(m, &o, &row, &t);
            EXPECT(o == r->pos(3 * (k + 75)) && row == r->pos(3 * (k + 75) + 1) && t == r->pos(3 * (k + 75) + 2),
                   "%s: 75 ticks later = straight play (%d/%d/%d)", id, o, row, t);
            ops->apply_image(m, image);
            EXPECT(ops->mute(m, 0, 1) == CHIP_OK && ops->mute(m, 0, 0) == CHIP_OK, "%s: mute", id);
        }
        EXPECT(ops->audition(m, tone, 0, A4, T_RELEASE, 0) == CHIP_OK, "%s: audition", id);
        static float out2[CHIP_MAX_TICK_FRAMES * 2];
        for (int j = 0; j < 5; ++j) ops->tick(m, out2);
        EXPECT(ops->release(m) == 1, "%s: release phase", id);
        int ticks = 0;
        while (ops->tick(m, out2) > 0 && ticks < 1000) ++ticks;
        EXPECT(ticks < 1000, "%s: released note ends (%d ticks)", id, ticks);
        ops->destroy(m);
        free(image);
        free(tone);
    }
}

static void test_costs(void) {
    printf("costs\n");
    WsMachine* m = wsm_create(RATE, WS_MODEL_COLOR);
    int mode;
    const Ref* r = &REF_wsc_song;
    const int k = ref_row_start(r, r->pos(3 * 1400), r->pos(3 * 1400 + 1));
    double t0 = now_us();
    if (k >= 0) wsm_start(m, image_of(r), r->pos(3 * k), r->pos(3 * k + 1), SEEK_BUDGET, &mode);
    const double seek = now_us() - t0;
    WsPlayer* p = (WsPlayer*)malloc(sizeof *p);
    t0 = now_us();
    wsp_reset(p, image_of(r));
    for (int j = 0; j < WSM_SEEK_TICKS; ++j) wsp_tick(p, image_of(r));
    const double per = (now_us() - t0) / WSM_SEEK_TICKS;
    free(p);
    t0 = now_us();
    for (int j = 0; j < 75; ++j) wsm_tick(m, g_out);
    const double tick = (now_us() - t0) / 75;
    printf("  seek to tick %d: %.0f us; player alone %.2f us per tick; machine %.1f us per tick (budget %.0f us)\n", k, seek, per,
           tick, 1e6 / TICKS_PER_SECOND);
    wsm_destroy(m);
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    const char* samples = argc > 2 ? argv[2] : "app/build/samples";
    init_refs();
    g_a = (WsApu*)malloc(sizeof *g_a);
    g_b = (WsApu*)malloc(sizeof *g_b);
    for (int i = 0; i < NREFS; ++i)
        EXPECT(load(&g_img[i], REFS[i]->image, REFS[i]->image_size) == 0, "%s loads: %s", REFS[i]->name,
               g_img[i].info.problem ? g_img[i].info.problem : "ok");
    build_tone(&g_tone_mono, WS_MODEL_MONO);
    build_tone(&g_tone_color, WS_MODEL_COLOR);
    test_apu_closed_form();
    test_apu_period();
    test_apu_lfsr();
    test_apu_sweep();
    test_apu_voice();
    test_apu_hyper_voice();
    test_apu_speaker();
    test_apu_dma();
    test_apu_mute();
    test_tables();
    test_images();
    test_reference(dir);
    test_audio(dir);
    test_tone_pitch();
    test_seek();
    test_live_edits();
    test_audition();
    test_mute();
    test_vgm(dir);
    test_export();
    test_samples(samples);
    test_ops();
    test_costs();
    free(g_a);
    free(g_b);
    printf("WonderSwan: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
