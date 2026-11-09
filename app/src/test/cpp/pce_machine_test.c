/* Host regression test for the PC Engine system: Thor Tracker's own player (pce/pce_player.c) on Mednafen's HuC6280
 * PSG, its DDA samples streamed from the HuC6280 timer interrupt, playing the hand-built test song
 * tools/audio/pce/pce_song.py (pce_test_song.h, from tools/audio/prepare_pce.py), plus small images built here for
 * measurements.
 *
 * Checks: the period table against its formula, A-4 = period 254, every note A-0..B-7 within 19.2 cents of 12-TET
 * (7.4 up to C-6), the clamps, the sample step's octaves; image validation (loader reasons by single-byte corruption
 * of the song or by small constructed images); register writes and positions identical to the independent Python
 * reference player (tools/audio/pce/pce_ref.py) in each of 1500 ticks, tick batch and every timer interrupt batch
 * with its CPU cycle, and its reset batch; deterministic, non-silent output; loop passes stepping through the same
 * positions (= the VGM loop); seeks landing on exact rows with the reference's writes and cycles afterwards and
 * bit-identical sound from the target, and the order fallback; live edits (a re-laid image alternating with the
 * original before every tick, a note edit, a tempo edit ahead of and behind the playhead, a cut song, multispeed x2, a
 * header shared-state edit taking effect in the next tick's writes and at the next timer reload); audition (A-4
 * measured on a sine wave, fine pitch, release, noise only on PSG 5-6, a sample's root note playing one frame per
 * timer interrupt, looping and one-shot samples, argument limits); mute (silence, unchanged writes); the LFO's pitch
 * modulation (present with M01, absent with M00 and with the halt bit); noise against a sine (zero crossings, spectral
 * roughness, the LFSR running); master and channel balance (left/right level ratios of 2^(steps/4)); timer
 * interrupts per tick; VGM bytes identical to the reference's; the hosts' VGM entry point and its GD3 tag; the
 * "pce-huc6280" ops table as the hosts drive it; costs.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir]
 * Writes <dir>/pce_song.wav (48 kHz stereo, 30 s), pce_tone.wav (an A-4 audition, 2 s + release), pce_trace.txt
 * (per-tick player writes with the tick's first CPU cycle, and every timer interrupt that wrote, with its cycle),
 * pce_song.vgm (VGM 1.71, HuC6280) and pce_song.bin (the image), the inputs of
 * tools/audio/pce/pce_reference_check.py. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "chip_engine.h"
#include "pce_export.h"
#include "pce_machine.h"
#include "pce_test_song.h"
#include "pce_vgm.h"

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
#define SECONDS 30
#define A4 57
#define PSG_HZ (39375000.0 / 11.0)           /* the waveform counters' clock */
#define CPU_HZ (78750000.0 / 11.0)           /* the CPU and its timer */
#define TIMER_HZ (CPU_HZ / PCM_TIMER_DIVIDER) /* 6991.3 Hz: timer reload 0 */
#define SEEK_BUDGET PCM_SEEK_TICKS

/* Slots of the measurement image (build_tone). */
#define T_SINE 1
#define T_NOISE 2
#define T_RELEASE 3
#define T_LOOP 4
#define T_ONESHOT 5
#define T_UNDEFINED 6

static float g_out[PCM_MAX_TICK_FRAMES * 2];
static PceImage g_song, g_moved, g_edited, g_tempo, g_short, g_x2, g_shared, g_bad, g_tone;
static PceImage g_lfo[3], g_pan[3];

typedef struct Ref {
    const int* offsets;
    const unsigned char* regs;
    const unsigned char* vals;
    const short* pos;
    const short* irqs;
    const int* ifirst;
    const int* iwfirst;
    const int* idelay;
    const unsigned char* icount;
    const unsigned char* iregs;
    const unsigned char* ivals;
    int multispeed;
} Ref;

#define REF(name, n)                                                                                                      \
    {pce_ref_##name##_offsets, pce_ref_##name##_regs, pce_ref_##name##_vals, pce_ref_##name##_pos, pce_ref_##name##_irqs, \
     pce_ref_##name##_ifirst,  pce_ref_##name##_iwfirst, pce_ref_##name##_idelay, pce_ref_##name##_icount,               \
     pce_ref_##name##_iregs,                                                                                               \
     pce_ref_##name##_ivals,   n}
static const Ref REF_SONG = REF(song, 1);
static const Ref REF_EDITED = REF(edited, 1);
static const Ref REF_TEMPO = REF(tempo, 1);
static const Ref REF_X2 = REF(x2, 2);

static double now_us(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e6 + t.tv_nsec / 1e3;
}

static double cpu_us(void) {
    struct timespec t;
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &t);
    return t.tv_sec * 1e6 + t.tv_nsec / 1e3;
}

static uint64_t fnv(uint64_t h, const void* data, size_t n) {
    const uint8_t* p = (const uint8_t*)data;
    for (size_t i = 0; i < n; ++i) h = (h ^ p[i]) * 0x100000001B3ull;
    return h;
}

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

/* Frequency from rising zero crossings of channel `side` (0 left, 1 right) of interleaved stereo (mean removed). */
static double zero_cross_hz(const float* x, int n) {
    double mean = 0;
    for (int i = 0; i < n; ++i) mean += x[2 * i];
    mean /= n;
    int count = 0;
    double first_t = -1, last_t = 0;
    for (int i = 1; i < n; ++i) {
        double a = x[2 * (i - 1)] - mean, b = x[2 * i] - mean;
        if (a < 0 && b >= 0) {
            double t = (i - 1) + a / (a - b);
            if (first_t < 0) first_t = t;
            else {
                last_t = t;
                ++count;
            }
        }
    }
    return count ? count * (double)RATE / (last_t - first_t) : 0;
}

/* Rising zero crossings per second of the left channel (mean removed). */
static double crossing_rate(const float* x, int n) {
    double mean = 0;
    for (int i = 0; i < n; ++i) mean += x[2 * i];
    mean /= n;
    int count = 0;
    for (int i = 1; i < n; ++i)
        if (x[2 * (i - 1)] - mean < 0 && x[2 * i] - mean >= 0) ++count;
    return count * (double)RATE / n;
}

/* rms of the left channel's first difference over its rms (mean removed): ~2 pi f / RATE for a tone, near sqrt(2)
 * for white noise. */
static double roughness(const float* x, int n) {
    double mean = 0;
    for (int i = 0; i < n; ++i) mean += x[2 * i];
    mean /= n;
    double s = 0, d = 0;
    for (int i = 1; i < n; ++i) {
        s += (x[2 * i] - mean) * (x[2 * i] - mean);
        const double e = x[2 * i] - x[2 * (i - 1)];
        d += e * e;
    }
    return s > 0 ? sqrt(d / s) : 0;
}

static double rms(const float* x, int frames) {
    double s = 0;
    for (int i = 0; i < frames * 2; ++i) s += (double)x[i] * x[i];
    return frames ? sqrt(s / (frames * 2.0)) : 0;
}

/* rms of one side (0 left, 1 right) with its mean removed. */
static double side_rms(const float* x, int frames, int side) {
    double mean = 0, s = 0;
    for (int i = 0; i < frames; ++i) mean += x[2 * i + side];
    mean /= frames ? frames : 1;
    for (int i = 0; i < frames; ++i) s += (x[2 * i + side] - mean) * (x[2 * i + side] - mean);
    return frames ? sqrt(s / frames) : 0;
}

static double peak(const float* x, int frames) {
    double p = 0;
    for (int i = 0; i < frames * 2; ++i)
        if (fabs(x[i]) > p) p = fabs(x[i]);
    return p;
}

static int render(PceMachine* m, int ticks, float** buf) {
    float* out = (float*)malloc((size_t)ticks * PCM_MAX_TICK_FRAMES * 2 * sizeof(float));
    int frames = 0;
    for (int t = 0; t < ticks; ++t) {
        int n = pcm_tick(m, out + 2 * frames);
        if (n <= 0) break;
        frames += n;
    }
    *buf = out;
    return frames;
}

static int load(PceImage* image, const uint8_t* bytes, size_t used) {
    memset(image->bytes, 0, sizeof image->bytes);
    memcpy(image->bytes, bytes, used);
    return pc_load_image(image);
}

/* ---- the trace hook: every batch the machine sends ------------------------------------------------------------ */

#define MAX_BATCHES 1024
#define MAX_TRACE_WRITES 32768

typedef struct Batch {
    int64_t cycle;
    int kind, first, n;
} Batch;

typedef struct Collect {
    int nb, nw, overflow;
    Batch b[MAX_BATCHES];
    PcWrite w[MAX_TRACE_WRITES];
} Collect;

static Collect g_col;

static void collect(void* ctx, int64_t cycle, int kind, const PcWrite* w, int n) {
    Collect* c = (Collect*)ctx;
    if (c->nb >= MAX_BATCHES || c->nw + n > MAX_TRACE_WRITES) {
        c->overflow = 1;
        return;
    }
    Batch* b = &c->b[c->nb++];
    b->cycle = cycle;
    b->kind = kind;
    b->first = c->nw;
    b->n = n;
    memcpy(c->w + c->nw, w, sizeof *w * (size_t)n);
    c->nw += n;
}

static void clear(Collect* c) { c->nb = c->nw = c->overflow = 0; }

/* One tick through the machine, collecting its batches. */
static int traced_tick(PceMachine* m) {
    clear(&g_col);
    return pcm_tick(m, g_out);
}

static int64_t ref_tick_cycle(int t, int multispeed) { return (int64_t)t * PCM_CYCLES_NUM / (PCM_CPU_HZ_DEN * (int64_t)multispeed); }

static int ref_count(const Ref* r, int t) { return r->offsets[t + 1] - r->offsets[t]; }

/* Whether the collected batches of the last tick equal reference tick t: the tick's batch at its first cycle, then
 * irqs[t] timer interrupts, those that wrote anything equal to the reference's events with their cycles. */
static int same_tick(const Collect* c, const Ref* r, int t) {
    if (c->overflow || c->nb != 1 + r->irqs[t]) return 0;
    const Batch* b = &c->b[0];
    if (b->kind != PCM_TRACE_TICK || b->cycle != ref_tick_cycle(t, r->multispeed) || b->n != ref_count(r, t)) return 0;
    for (int i = 0; i < b->n; ++i) {
        const int j = r->offsets[t] + i;
        if (c->w[b->first + i].reg != r->regs[j] || c->w[b->first + i].val != r->vals[j]) return 0;
    }
    const int64_t start = ref_tick_cycle(t, r->multispeed);
    int e = r->ifirst[t], j = r->iwfirst[t];
    for (int k = 1; k < c->nb; ++k) {
        b = &c->b[k];
        if (b->kind != PCM_TRACE_TIMER) return 0;
        if (!b->n) continue;
        if (e >= r->ifirst[t + 1] || b->cycle != start + r->idelay[e] || b->n != r->icount[e]) return 0;
        for (int i = 0; i < b->n; ++i, ++j)
            if (c->w[b->first + i].reg != r->iregs[j] || c->w[b->first + i].val != r->ivals[j]) return 0;
        ++e;
    }
    return e == r->ifirst[t + 1];
}

static int same_reset(const PcPlayer* p) {
    if (p->writes != PCE_REF_RESET_WRITES) return 0;
    for (int i = 0; i < p->writes; ++i)
        if (p->write[i].reg != pce_ref_reset_regs[i] || p->write[i].val != pce_ref_reset_vals[i]) return 0;
    return 1;
}

/* Whether reference ticks t of a and b have the same writes, interrupts and positions. */
static int ref_tick_equal(const Ref* a, const Ref* b, int t) {
    if (ref_count(a, t) != ref_count(b, t) || a->irqs[t] != b->irqs[t]) return 0;
    for (int k = 0; k < 3; ++k)
        if (a->pos[3 * t + k] != b->pos[3 * t + k]) return 0;
    for (int i = 0; i < ref_count(a, t); ++i)
        if (a->regs[a->offsets[t] + i] != b->regs[b->offsets[t] + i] || a->vals[a->offsets[t] + i] != b->vals[b->offsets[t] + i])
            return 0;
    const int ne = a->ifirst[t + 1] - a->ifirst[t];
    if (ne != b->ifirst[t + 1] - b->ifirst[t]) return 0;
    int ja = a->iwfirst[t], jb = b->iwfirst[t];
    for (int e = 0; e < ne; ++e) {
        const int ea = a->ifirst[t] + e, eb = b->ifirst[t] + e;
        const int n = a->icount[ea];
        if (a->idelay[ea] != b->idelay[eb] || n != b->icount[eb]) return 0;
        for (int i = 0; i < n; ++i, ++ja, ++jb)
            if (a->iregs[ja] != b->iregs[jb] || a->ivals[ja] != b->ivals[jb]) return 0;
    }
    return 1;
}

/* First tick where the two reference traces differ, or -1. */
static int first_difference(const Ref* a, const Ref* b) {
    for (int t = 0; t < PCE_REF_TICKS; ++t)
        if (!ref_tick_equal(a, b, t)) return t;
    return -1;
}

/* The tick that starts the row tick t belongs to (in trace r). */
static int row_start_of(const Ref* r, int t) { return t - r->pos[3 * t + 2]; }

/* First reference tick whose position is (order, row, 0), or -1. */
static int ref_row_start(const Ref* r, int order, int row) {
    for (int t = 0; t < PCE_REF_TICKS; ++t)
        if (r->pos[3 * t] == order && r->pos[3 * t + 1] == row && r->pos[3 * t + 2] == 0) return t;
    return -1;
}

/* Ticks of the row starting at reference tick t. */
static int ref_row_length(const Ref* r, int t) {
    int n = 1;
    while (t + n < PCE_REF_TICKS && r->pos[3 * (t + n) + 2] != 0) ++n;
    return n;
}

static int pos_is(const PceMachine* m, const Ref* r, int t) {
    int o, w, k;
    pcm_position(m, &o, &w, &k);
    return o == r->pos[3 * t] && w == r->pos[3 * t + 1] && k == r->pos[3 * t + 2];
}

/* Whether tick t of the trace writes any of the shared registers R1, R8, R9. */
static int ref_writes_globals(const Ref* r, int t) {
    for (int i = r->offsets[t]; i < r->offsets[t + 1]; ++i) {
        const int reg = r->regs[i];
        if (reg == 1 || reg == 8 || reg == 9) return 1;
    }
    return 0;
}

/* Tick t of the trace runs inside a row (no row start) and writes no shared register. */
static int quiet_tick(const Ref* r, int t) { return r->pos[3 * t + 2] != 0 && !ref_writes_globals(r, t); }

/* ---- images built here ----------------------------------------------------------------------- */

static void put16(uint8_t* p, int v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
}

static void put32(uint8_t* p, uint32_t v) {
    for (int i = 0; i < 4; ++i) p[i] = (uint8_t)(v >> (8 * i));
}

/* One order of `rows` rows at `speed`, every channel's pattern at data offset 0, data[0 .. n); master balance $FF,
 * two waves (0 sine, 1 square), timer reload 0. */
static void base_image(PceImage* im, int rows, int speed, const uint8_t* data, int n) {
    memset(im, 0, sizeof *im);
    uint8_t* b = im->bytes;
    memcpy(b, "TTPC", 4);
    b[PC_H_LAYOUT] = PC_LAYOUT;
    b[PC_H_MULTISPEED] = 1;
    b[PC_H_SPEED] = (uint8_t)speed;
    put16(b + PC_H_ORDERS, 1);
    b[PC_H_MASTER] = 0xFF;
    b[PC_OFF_ROWS] = (uint8_t)rows;
    b[PC_H_WAVES] = 2;
    for (int i = 0; i < PC_WAVE_SIZE; ++i) {
        b[PC_OFF_WAVES + i] = (uint8_t)((PCP_SINE[2 * i] + 127) * 31 / 254); /* pce_song.test_waves()[0] */
        b[PC_OFF_WAVES + PC_WAVE_SIZE + i] = i < 16 ? 31 : 0;
    }
    memcpy(b + PC_OFF_DATA, data, (size_t)n);
    put32(b + PC_H_DATA_SIZE, (uint32_t)n);
}

static void set_pattern(PceImage* im, int channel, int offset) { put16(im->bytes + PC_OFF_ORDERS + 2 * channel, offset); }

/* An instrument without envelopes (every envelope: length 0, loop and release $FF). */
static void put_instrument(PceImage* im, int slot, int kind, int wave, int sample, int root, int loop, int loop_start) {
    uint8_t* r = im->bytes + PC_OFF_INSTRUMENTS + PC_INSTRUMENT_SIZE * (slot - 1);
    memset(r, 0, PC_INSTRUMENT_SIZE);
    r[PC_I_FLAGS] = (uint8_t)(PC_FLAG_DEFINED | kind);
    r[PC_I_WAVE] = (uint8_t)wave;
    r[PC_I_SAMPLE] = (uint8_t)sample;
    r[PC_I_ROOT] = (uint8_t)root;
    r[PC_I_LOOP] = (uint8_t)loop;
    put16(r + PC_I_LOOP_START, loop_start);
    for (int k = 0; k < PC_ENVELOPE_COUNT; ++k) {
        r[PC_I_ENVELOPES + PC_ENVELOPE_BYTES * k + 3] = PC_ENV_NONE;
        r[PC_I_ENVELOPES + PC_ENVELOPE_BYTES * k + 4] = PC_ENV_NONE;
    }
    if (im->bytes[PC_H_INSTRUMENTS] < slot) im->bytes[PC_H_INSTRUMENTS] = (uint8_t)slot;
}

static void put_envelope(PceImage* im, int slot, int k, int offset, int length, int loop, int release) {
    uint8_t* e = im->bytes + PC_OFF_INSTRUMENTS + PC_INSTRUMENT_SIZE * (slot - 1) + PC_I_ENVELOPES + PC_ENVELOPE_BYTES * k;
    put16(e, offset);
    e[2] = (uint8_t)length;
    e[3] = (uint8_t)loop;
    e[4] = (uint8_t)release;
}

static const uint8_t EMPTY_ROW[] = {0x80};

/* Auditions: data [0] the empty row, [1..5] a volume envelope held at 31 and released to 0 (31 31 20 10 0, loop 1,
 * release 2), [6..69] sample 0: 64 frames of a square with a 16-frame period (8 x 31, 8 x 0). Timer reload 0: one
 * interrupt every 1024 cycles (6991.3 Hz). */
#define TONE_SAMPLE_AT 6
#define TONE_SAMPLE_FRAMES 64
#define TONE_SAMPLE_PERIOD 16
static void build_tone(void) {
    uint8_t data[TONE_SAMPLE_AT + TONE_SAMPLE_FRAMES] = {0x80, 31, 31, 20, 10, 0};
    for (int i = 0; i < TONE_SAMPLE_FRAMES; ++i) data[TONE_SAMPLE_AT + i] = (i % TONE_SAMPLE_PERIOD) < TONE_SAMPLE_PERIOD / 2 ? 31 : 0;
    PceImage* im = &g_tone;
    base_image(im, 1, 6, data, (int)sizeof data);
    uint8_t* b = im->bytes;
    b[PC_H_SAMPLES] = 1;
    put32(b + PC_OFF_SAMPLES, TONE_SAMPLE_AT);
    put32(b + PC_OFF_SAMPLES + 4, TONE_SAMPLE_FRAMES);
    b[PC_H_LFO_FREQ] = 0x33; /* kept by auditions */
    put_instrument(im, T_SINE, PC_KIND_TONE, 0, 0, 0, 0, 0);
    put_instrument(im, T_NOISE, PC_KIND_NOISE, 0, 0, 0, 0, 0);
    put_instrument(im, T_RELEASE, PC_KIND_TONE, 0, 0, 0, 0, 0);
    put_envelope(im, T_RELEASE, PC_ENV_VOLUME, 1, 5, 1, 2);
    put_instrument(im, T_LOOP, PC_KIND_SAMPLE, 0, 0, A4, 1, 0);
    put_instrument(im, T_ONESHOT, PC_KIND_SAMPLE, 0, 0, A4, 0, 0);
    EXPECT(pc_load_image(im) == 0, "tone image loads: %s", im->info.problem);
}

/* The LFO: PSG 1 holds A-4 on the sine; PSG 2 holds A-0 on the sine at volume 0 (C00), the modulator, at LFO frequency 5:
 * 3579545 / (32 x 4068 x 5) = 5.5 Hz. Variants: LFO control 0 (off), 1 (shift 0: +-16 on period 254), $81 (halted). */
static void build_lfo(PceImage* im, int ctrl) {
    const uint8_t data[] = {0x80, 0x03, A4, T_SINE, 0x07, PC_NOTE_MIN, T_SINE, PC_FX_VOLUME, 0x00};
    base_image(im, 1, 255, data, (int)sizeof data);
    set_pattern(im, 0, 1);
    set_pattern(im, 1, 4);
    im->bytes[PC_H_LFO_FREQ] = 5;
    im->bytes[PC_H_LFO_CTRL] = (uint8_t)ctrl;
    put_instrument(im, T_SINE, PC_KIND_TONE, 0, 0, 0, 0, 0);
    EXPECT(pc_load_image(im) == 0, "LFO image loads: %s", im->info.problem);
}

/* Balance: PSG 1 holds A-4 on the sine with pan `pan` (8xy) at master balance `master`. */
static void build_pan(PceImage* im, int pan, int master) {
    const uint8_t data[] = {0x80, 0x07, A4, T_SINE, PC_FX_PAN, (uint8_t)pan};
    base_image(im, 1, 255, data, (int)sizeof data);
    set_pattern(im, 0, 1);
    im->bytes[PC_H_MASTER] = (uint8_t)master;
    put_instrument(im, T_SINE, PC_KIND_TONE, 0, 0, 0, 0, 0);
    EXPECT(pc_load_image(im) == 0, "balance image loads: %s", im->info.problem);
}

/* ---- tests ---------------------------------------------------------------------------------- */

static void test_tables(void) {
    printf("periods\n");
    int table_ok = 1;
    for (int i = 0; i < 768; ++i) {
        const double v = PSG_HZ / (32 * 440.0 * pow(2.0, (i / 64.0 - 57) / 12.0)) * 65536;
        if ((uint32_t)llround(v) != PCP_PERIOD_Q16[i]) table_ok = 0;
    }
    EXPECT(table_ok, "PCP_PERIOD_Q16 = round(39375000 / 11 / (32 x 440 x 2^((i / 64 - 57) / 12)) x 65536)");
    int sine_ok = 1;
    for (int i = 0; i < 64; ++i)
        if (PCP_SINE[i] != (int)floor(127 * sin(2 * M_PI * i / 64) + 0.5)) sine_ok = 0;
    EXPECT(sine_ok, "PCP_SINE = floor(127 sin(2 pi i / 64) + 0.5)");
    double worst = 0, worst_low = 0;
    int nearest = 1;
    for (int n = PC_NOTE_MIN; n <= PC_NOTE_MAX; ++n) {
        const int period = pcp_period(n * 64);
        const double target = 440.0 * pow(2.0, (n - A4) / 12.0);
        const double ideal = PSG_HZ / (32 * target);
        if (fabs(period - ideal) > 0.5 + 1e-6) nearest = 0;
        const double cents = fabs(1200 * log2(PSG_HZ / (32.0 * period) / target));
        if (cents > worst) worst = cents;
        if (n <= 72 && cents > worst_low) worst_low = cents;
    }
    EXPECT(pcp_period(A4 * 64) == 254, "A-4 = period 254 (got %d): %.2f Hz", pcp_period(A4 * 64), PSG_HZ / (32 * 254.0));
    EXPECT(pcp_period(PC_NOTE_MIN * 64) == 4068 && pcp_period(PC_NOTE_MAX * 64) == 28, "A-0 = 4068, B-7 = 28 (%d, %d)",
           pcp_period(PC_NOTE_MIN * 64), pcp_period(PC_NOTE_MAX * 64));
    EXPECT(nearest, "every note's period is the nearest integer to clock / (32 f)");
    EXPECT(worst < 19.25, "every note A-0..B-7 within 19.2 cents of 12-TET (worst %.2f)", worst);
    EXPECT(worst_low < 7.45, "A-0..C-6 within 7.4 cents (worst %.2f)", worst_low);
    EXPECT(pcp_period(PCP_PITCH_MIN) == 4093 && pcp_period(0) == 4093 && pcp_period(-5000) == 4093,
           "pitches below 569 clamp to period 4093");
    EXPECT(pcp_period(PCP_PITCH_MAX) == pcp_period(100000) && pcp_period(PCP_PITCH_MAX) >= 1, "the top clamps to B-7 + 63/64");
    EXPECT(PSG_HZ / (32 * 440.0 * pow(2.0, ((PCP_PITCH_MIN - 1) / 64.0 - A4) / 12.0)) > 4095.5,
           "pitch 568 would need a period above 12 bits");
    int steps_ok = 1;
    for (int root = PC_NOTE_MIN; root <= PC_NOTE_MAX; ++root) {
        if (pcp_sample_step(root * 64, root) != 65536) steps_ok = 0;
        if (root * 64 + 768 <= PCP_PITCH_MAX && pcp_sample_step(root * 64 + 768, root) != 131072) steps_ok = 0;
        if (root * 64 - 768 >= PCP_PITCH_MIN && pcp_sample_step(root * 64 - 768, root) != 32768) steps_ok = 0;
    }
    EXPECT(steps_ok, "sample step: the root plays 65536 (one frame per interrupt), an octave up 131072, down 32768");
    const uint32_t fifth = pcp_sample_step((A4 + 7) * 64, A4);
    EXPECT(fabs(fifth / 65536.0 - pow(2.0, 7.0 / 12.0)) < 0.003, "a fifth up steps %.4f frames (1.4983)", fifth / 65536.0);
    printf("  A-4 %.3f Hz; worst %.2f cents (%.2f up to C-6)\n", PSG_HZ / (32 * 254.0), worst, worst_low);
}

static void corrupt(int offset, int value, const char* want) {
    memcpy(&g_bad, &g_song, sizeof g_bad);
    g_bad.bytes[offset] = (uint8_t)value;
    const int rc = pc_load_image(&g_bad);
    EXPECT(rc != 0, "byte %d = %d (%s) must be rejected (got %s)",
           offset, value, want, rc ? g_bad.info.problem : "accepted");
    if (rc) printf("  rejected @%d: %s\n", g_bad.info.problem_offset, g_bad.info.problem);
}

static void accepted(int offset, int value, const char* what) {
    memcpy(&g_bad, &g_song, sizeof g_bad);
    g_bad.bytes[offset] = (uint8_t)value;
    const int rc = pc_load_image(&g_bad);
    EXPECT(rc == 0, "%s: byte %d = %d accepted (got %s)", what, offset, value, rc ? g_bad.info.problem : "ok");
}

/* A constructed image (g_bad, already filled), with rejection context `want`, or accepted when want is NULL. */
static void constructed(const char* what, const char* want) {
    const int rc = pc_load_image(&g_bad);
    if (want)
        EXPECT(rc != 0, "%s (%s) must be rejected (got %s)", what, want,
               rc ? g_bad.info.problem : "accepted");
    else
        EXPECT(rc == 0, "%s accepted (got %s)", what, rc ? g_bad.info.problem : "ok");
    if (rc) printf("  %s: rejected @%d: %s\n", what, g_bad.info.problem_offset, g_bad.info.problem);
}

/* Image offset of slot's record byte `field`, and of the first value of its envelope k. */
static int ins_at(int slot, int field) { return PC_OFF_INSTRUMENTS + PC_INSTRUMENT_SIZE * (slot - 1) + field; }
static int env_at(int slot, int k) { return ins_at(slot, PC_I_ENVELOPES + PC_ENVELOPE_BYTES * k); }
static int env_data(int slot, int k) { return PC_OFF_DATA + pc_u16(g_song.bytes + env_at(slot, k)); }

static void test_images(void) {
    printf("images\n");
    const PcInfo* info = &g_song.info;
    EXPECT(info->loaded == PC_LOADED && info->orders == 4 && info->loop == 1 && info->multispeed == 1 && info->speed == 6,
           "song info");
    EXPECT(info->rows[0] == 16 && info->rows[1] == 32 && info->rows[2] == 16 && info->rows[3] == 24, "rows per order");
    EXPECT(info->start_speed[0] == 6 && info->start_speed[1] == 6 && info->start_speed[2] == 6 && info->start_speed[3] == 5,
           "start speeds %d %d %d %d (6 6 6 5)", info->start_speed[0], info->start_speed[1], info->start_speed[2],
           info->start_speed[3]);
    EXPECT(info->instruments == 9 && info->waves == 5 && info->samples == 2, "9 instruments, 5 waves, 2 samples (%d, %d, %d)",
           info->instruments, info->waves, info->samples);
    /* header */
    corrupt(0, 'X', "magic");
    corrupt(PC_H_LAYOUT, 2, "layout");
    corrupt(PC_H_MULTISPEED, 0, "multispeed");
    corrupt(PC_H_MULTISPEED, 9, "multispeed");
    corrupt(PC_H_SPEED, 0, "speed must be 1..255");
    corrupt(PC_H_INSTRUMENTS, 65, "instrument count above 64");
    corrupt(PC_H_INSTRUMENTS, info->instruments - 1, "instrument record past the instrument count");
    corrupt(PC_H_ORDERS, 0, "order count");
    corrupt(PC_H_LOOP, 4, "loop order");
    corrupt(PC_H_WAVES, 65, "wave count above 64");
    corrupt(PC_H_WAVES, 4, "wave past the wave count");
    corrupt(PC_H_SAMPLES, 65, "sample count above 64");
    corrupt(PC_H_SAMPLES, 1, "sample entry past the sample count");
    corrupt(PC_H_LFO_CTRL, 0x04, "LFO control");
    corrupt(PC_H_LFO_CTRL, 0x40, "LFO control");
    corrupt(PC_H_TIMER, 128, "timer reload above 127");
    corrupt(18, 1, "reserved bytes 18..19");
    corrupt(PC_H_DATA_SIZE + 2, 2, "data size above the region");
    corrupt(40, 1, "reserved bytes 24..63");
    accepted(PC_H_MASTER, 0x00, "any master balance");
    accepted(PC_H_LFO_FREQ, 0x00, "LFO frequency 0 (= 256)");
    accepted(PC_H_LFO_CTRL, 0x83, "LFO control $83");
    accepted(PC_H_TIMER, 127, "timer reload 127");
    /* tables */
    corrupt(PC_OFF_ROWS + 9, 4, "rows of an order past the order count");
    corrupt(PC_OFF_ORDERS + 2 * PC_CHANNELS * 9, 1, "patterns of an order past the order count");
    corrupt(PC_OFF_ORDERS + 1, 0xF0, "pattern offset past the data size");
    corrupt(PC_OFF_WAVES + 3, 32, "wave sample above 31");
    corrupt(PC_OFF_SAMPLES + 6, 1, "sample longer than 65535 frames");
    corrupt(PC_OFF_SAMPLES + 2, 1, "sample runs past the data size");
    corrupt(PC_OFF_DATA + pc_u32(g_song.bytes + PC_OFF_SAMPLES), 32, "sample frame above 31");
    /* instruments (slots of pce_song.test_instruments: 1 sine, 2 lead, 3 pad, 4 wave envelope, 5 relative pitch,
     * 6 noise hat, 7 noise snare, 8 one-shot sample, 9 looping sample) */
    corrupt(ins_at(1, PC_I_FLAGS), 0x83, "instrument kind 3");
    corrupt(ins_at(1, PC_I_FLAGS), 0x00, "instrument flags");
    corrupt(ins_at(1, PC_I_FLAGS), 0x88, "instrument flags");
    corrupt(ins_at(1, PC_I_WAVE), 5, "tone instrument names a wave the image does not have");
    corrupt(ins_at(6, PC_I_WAVE), 1, "wave byte of a noise or sample instrument");
    corrupt(ins_at(8, PC_I_SAMPLE), 2, "sample instrument names a sample the image does not have");
    corrupt(ins_at(8, PC_I_ROOT), PC_NOTE_MIN - 1, "sample root note outside 9..95");
    corrupt(ins_at(8, PC_I_ROOT), PC_NOTE_MAX + 1, "sample root note outside 9..95");
    corrupt(ins_at(8, PC_I_LOOP), 2, "sample loop flag must be 0 or 1");
    corrupt(ins_at(8, PC_I_LOOP_START), 1, "sample loop start");
    corrupt(ins_at(9, PC_I_LOOP_START + 1), 1, "sample loop start");
    accepted(ins_at(9, PC_I_LOOP_START), 255, "loop start 255 of a 256-frame sample");
    corrupt(ins_at(1, PC_I_SAMPLE), 1, "sample fields of a tone or noise instrument");
    corrupt(ins_at(6, PC_I_ROOT), 48, "sample fields of a tone or noise instrument");
    corrupt(ins_at(1, 5), 1, "instrument reserved byte 5");
    corrupt(env_at(1, PC_ENV_VOLUME) + 5, 1, "envelope reserved byte");
    corrupt(env_at(1, PC_ENV_ARPEGGIO) + 3, 0, "empty envelope");
    corrupt(env_at(1, PC_ENV_ARPEGGIO), 1, "empty envelope");
    corrupt(env_at(6, PC_ENV_WAVE) + 2, 1, "wave envelope on a noise or sample instrument");
    corrupt(env_at(2, PC_ENV_VOLUME) + 3, 6, "envelope loop past its length");
    corrupt(env_at(2, PC_ENV_VOLUME) + 4, 6, "envelope release must be 1..length - 1");
    corrupt(env_at(2, PC_ENV_VOLUME) + 4, 0, "envelope release must be 1..length - 1");
    corrupt(env_at(3, PC_ENV_VOLUME) + 4, 3, "envelope release must follow its loop");
    corrupt(env_at(2, PC_ENV_VOLUME) + 1, 0xFF, "envelope runs past the data size");
    corrupt(env_data(2, PC_ENV_VOLUME), 32, "volume envelope value above 31");
    corrupt(env_data(3, PC_ENV_ARPEGGIO), 64, "arpeggio envelope value outside -64..63");
    corrupt(env_data(3, PC_ENV_ARPEGGIO), 0xBF, "arpeggio envelope value outside -64..63");
    accepted(env_data(3, PC_ENV_ARPEGGIO), 0xC0, "arpeggio -64");
    corrupt(env_data(4, PC_ENV_WAVE), 5, "wave envelope names a wave the image does not have");
    accepted(env_data(5, PC_ENV_PITCH), 0x80, "pitch envelope value -128");
    /* patterns */
    corrupt(PCE_OFFSET_MASK, 0x20, "row mask byte");
    corrupt(PCE_OFFSET_MASK, 0x17, "row mask byte");
    corrupt(PCE_OFFSET_NOTE, PC_NOTE_MIN - 1, "note byte");
    corrupt(PCE_OFFSET_NOTE, PC_NOTE_MAX + 1, "note byte");
    accepted(PCE_OFFSET_NOTE, PC_NOTE_OFF, "OFF");
    corrupt(PCE_OFFSET_INST, 65, "row names an instrument the image does not define");
    corrupt(PCE_OFFSET_INST, 0, "row names an instrument the image does not define");
    corrupt(PCE_OFFSET_INST, 10, "row names an instrument the image does not define");
    corrupt(PCE_OFFSET_EFFECT, 36, "effect code above Z");
    static const int unknown[] = {5, 6, 7, 9, 14, 17, 18, 19, 20, 23, 24, 26, 27, 30, 31, 34, 35};
    for (size_t i = 0; i < sizeof unknown / sizeof unknown[0]; ++i) corrupt(PCE_OFFSET_EFFECT, unknown[i], "unknown effect code");
    corrupt(PCE_OFFSET_PARAM_B, 4, "Bxx");
    corrupt(PCE_OFFSET_PARAM_C, 0x20, "Cxx");
    corrupt(PCE_OFFSET_PARAM_F, 0, "F00");
    corrupt(PCE_OFFSET_PARAM_M, 0x04, "Mxx");
    corrupt(PCE_OFFSET_PARAM_M, 0x84, "Mxx");
    corrupt(PCE_OFFSET_PARAM_T, 0x80, "Txx");
    corrupt(PCE_OFFSET_PARAM_W, 5, "Wxx");
    accepted(PCE_OFFSET_PARAM_C, 0x1F, "C1F");
    accepted(PCE_OFFSET_PARAM_M, 0x83, "M83");
    accepted(PCE_OFFSET_PARAM_T, 0x7F, "T7F");
    accepted(PCE_OFFSET_PARAM_W, 4, "W04");
    /* The noise slot named on PSG 5/6 is a noise instrument; any defined instrument may play there. */
    const int noise_slot = g_song.bytes[PCE_OFFSET_NOISE_INST];
    EXPECT(noise_slot >= 1 && noise_slot <= info->instruments &&
               (g_song.bytes[ins_at(noise_slot, PC_I_FLAGS)] & PC_KIND_MASK) == PC_KIND_NOISE,
           "the noise row on PSG 5/6 names a noise instrument (slot %d)", noise_slot);
    accepted(PCE_OFFSET_NOISE_INST, 1, "a tone instrument on PSG 5/6");
    accepted(PCE_OFFSET_NOISE_INST, 8, "a sample instrument on PSG 5/6");

    /* Constructed images for the reasons a single byte of the song cannot reach. */
    base_image(&g_bad, 2, 6, EMPTY_ROW, 1);
    constructed("a pattern decoding past the data", "pattern runs past the data size");
    {
        static const uint8_t data[] = {0x01};
        base_image(&g_bad, 1, 6, data, 1);
        constructed("a row whose note byte is past the data", "row runs past the data size");
    }
    {
        static const uint8_t data[] = {0x02, 1};
        base_image(&g_bad, 1, 6, data, 2);
        put_instrument(&g_bad, 1, PC_KIND_NOISE, 0, 0, 0, 0, 0);
        constructed("a noise instrument on PSG 1", "noise instrument off PSG 5-6");
        static const uint8_t psg4[] = {0x80, 0x02, 1};
        base_image(&g_bad, 1, 6, psg4, 3);
        set_pattern(&g_bad, 3, 1);
        put_instrument(&g_bad, 1, PC_KIND_NOISE, 0, 0, 0, 0, 0);
        constructed("a noise instrument on PSG 4", "noise instrument off PSG 5-6");
        set_pattern(&g_bad, 3, 0);
        set_pattern(&g_bad, 4, 1);
        set_pattern(&g_bad, 5, 1);
        constructed("a noise instrument on PSG 5 and PSG 6", NULL);
    }
    base_image(&g_bad, 1, 6, EMPTY_ROW, 1);
    g_bad.bytes[PC_H_SAMPLES] = 1;
    put32(g_bad.bytes + PC_OFF_SAMPLES, 0);
    constructed("a sample of 0 frames", "sample without frames");
    base_image(&g_bad, 1, 6, EMPTY_ROW, 1);
    put32(g_bad.bytes + PC_H_DATA_SIZE, PC_DATA_CAPACITY + 1);
    constructed("data size 123073", "data size above the region");
    base_image(&g_bad, 1, 6, EMPTY_ROW, 1);
    put32(g_bad.bytes + PC_H_DATA_SIZE, PC_DATA_CAPACITY);
    constructed("data size 123072", NULL);
    base_image(&g_bad, 1, 255, EMPTY_ROW, 1);
    g_bad.bytes[PC_H_MULTISPEED] = PC_MAX_MULTISPEED;
    g_bad.bytes[PC_H_MASTER] = 0xFF;
    g_bad.bytes[PC_H_LFO_FREQ] = 0xFF;
    g_bad.bytes[PC_H_LFO_CTRL] = 0x83;
    g_bad.bytes[PC_H_TIMER] = PC_MAX_TIMER;
    constructed("the largest header values", NULL);
    {
        /* a 65535-frame sample, the largest */
        base_image(&g_bad, 1, 6, EMPTY_ROW, 1);
        g_bad.bytes[PC_H_SAMPLES] = 1;
        put32(g_bad.bytes + PC_OFF_SAMPLES, 1);
        put32(g_bad.bytes + PC_OFF_SAMPLES + 4, PC_MAX_SAMPLE_FRAMES);
        put32(g_bad.bytes + PC_H_DATA_SIZE, 1 + PC_MAX_SAMPLE_FRAMES);
        constructed("a 65535-frame sample", NULL);
    }

    PceImage* fresh = &g_bad;
    memcpy(fresh, &g_song, sizeof *fresh);
    EXPECT(pc_load_image(fresh) == 0 && !memcmp(&fresh->info, &g_song.info, sizeof fresh->info), "loading is a pure function");
}

static void fprint_writes(FILE* f, const PcWrite* w, int n) {
    for (int i = 0; i < n; ++i) fprintf(f, " %02X=%02X", w[i].reg, w[i].val);
}

static void test_reference(const char* dir) {
    printf("reference player\n");
    PceMachine* m = pcm_create(RATE);
    int mode = -1;
    EXPECT(pcm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode) == PCM_OK && mode == PCM_SEEK_FROM_START, "start");
    const PcPlayer* p = pcm_player(m);
    EXPECT(same_reset(p), "reset batch = reference (%d writes, reference %d)", p->writes, PCE_REF_RESET_WRITES);
    EXPECT(p->select == PC_CHANNELS - 1, "select is 5 after the reset batch (%d)", p->select);
    pcm_set_trace(m, collect, &g_col);
    char path[512];
    snprintf(path, sizeof path, "%s/pce_trace.txt", dir);
    FILE* trace = fopen(path, "w");
    int mismatches = 0, first = -1, position_errors = 0, irq_errors = 0, writes = 0, max_writes = 0, interrupts = 0;
    int saw_dda = 0, saw_noise = 0, wave_loads = 0, lfo_modes = 0, dda_channels = 0, released = 0, masters = 0;
    int last_master = -1;
    for (int t = 0; t < PCE_REF_TICKS; ++t) {
        if (!pos_is(m, &REF_SONG, t)) ++position_errors;
        int o, r, k;
        pcm_position(m, &o, &r, &k);
        traced_tick(m);
        if (!same_tick(&g_col, &REF_SONG, t)) {
            if (first < 0) first = t;
            ++mismatches;
        }
        if (pcm_timer_interrupts(m) != REF_SONG.irqs[t]) ++irq_errors;
        interrupts += pcm_timer_interrupts(m);
        for (int b = 0; b < g_col.nb; ++b) {
            const Batch* x = &g_col.b[b];
            writes += x->n;
            if (x->n > max_writes) max_writes = x->n;
            for (int i = 0; i + 1 < x->n; ++i)
                if (g_col.w[x->first + i].reg == 4 && g_col.w[x->first + i].val == 0x40 && g_col.w[x->first + i + 1].reg == 4 &&
                    g_col.w[x->first + i + 1].val == 0x00)
                    ++wave_loads;
        }
        if (trace && g_col.nb) {
            fprintf(trace, "%d %d %d @%lld:", o, r, k, (long long)g_col.b[0].cycle);
            fprint_writes(trace, g_col.w + g_col.b[0].first, g_col.b[0].n);
            for (int b = 1; b < g_col.nb; ++b) {
                if (!g_col.b[b].n) continue;
                fprintf(trace, " | @%lld:", (long long)g_col.b[b].cycle);
                fprint_writes(trace, g_col.w + g_col.b[b].first, g_col.b[b].n);
            }
            fputc('\n', trace);
        }
        for (int c = 0; c < PC_CHANNELS; ++c) {
            if (p->ch[c].dda) {
                saw_dda = 1;
                dda_channels |= 1 << c;
            }
            if (p->ch[c].released) released = 1;
        }
        if (p->ch[4].r7 & 0x80 || p->ch[5].r7 & 0x80) saw_noise = 1;
        lfo_modes |= 1 << (p->lfo_ctrl & 3);
        if (p->lfo_ctrl & 0x80) lfo_modes |= 0x10;
        if (p->master != last_master) ++masters, last_master = p->master;
    }
    pcm_set_trace(m, NULL, NULL);
    if (trace) fclose(trace);
    EXPECT(mismatches == 0, "tick and timer-interrupt writes and cycles = reference player in all %d ticks (%d differ, first at "
                            "tick %d)",
           PCE_REF_TICKS, mismatches, first);
    if (first >= 0) {
        printf("  first difference at tick %d; reference:", first);
        for (int j = REF_SONG.offsets[first]; j < REF_SONG.offsets[first + 1] && j < REF_SONG.offsets[first] + 24; ++j)
            printf(" %02X=%02X", REF_SONG.regs[j], REF_SONG.vals[j]);
        printf(" (%d interrupts, %d writing)\n", REF_SONG.irqs[first], REF_SONG.ifirst[first + 1] - REF_SONG.ifirst[first]);
    }
    EXPECT(position_errors == 0, "positions = reference in every tick (%d differ)", position_errors);
    EXPECT(irq_errors == 0, "timer interrupts per tick = reference (%d differ)", irq_errors);
    EXPECT(saw_dda && (dda_channels & 0x30) == 0x30, "DDA samples stream on PSG 5 and PSG 6 (channels %X)", dda_channels);
    EXPECT(saw_noise, "the noise generator is switched on (PSG 5/6)");
    EXPECT(wave_loads >= 6, "wave loads occur (%d)", wave_loads);
    EXPECT((lfo_modes & 0x1F) == 0x1F, "LFO control 0, 1, 2, 3 and the halt bit are all used (mask %X)", lfo_modes);
    EXPECT(released, "a release point is reached");
    EXPECT(masters >= 3, "the master balance changes (%d values)", masters);
    printf("  %d ticks, %d writes (max %d per batch), %d timer interrupts, %d wave loads\n", PCE_REF_TICKS, writes, max_writes,
           interrupts, wave_loads);
    pcm_destroy(m);
}

static void test_audio(const char* dir) {
    printf("audio\n");
    const int ticks = SECONDS * 60;
    uint64_t hash[2] = {0, 0};
    for (int pass = 0; pass < 2; ++pass) {
        PceMachine* m = pcm_create(RATE);
        int mode;
        pcm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
        float* buf;
        const double t0 = now_us();
        const int frames = render(m, ticks, &buf);
        const double us = (now_us() - t0) / ticks;
        hash[pass] = fnv(0xCBF29CE484222325ull, buf, (size_t)frames * 2 * sizeof(float));
        if (pass == 0) {
            const double r = rms(buf, frames), pk = peak(buf, frames);
            EXPECT(frames >= SECONDS * RATE - 2 && frames <= SECONDS * RATE + 2, "30 s = %d frames (got %d)", SECONDS * RATE, frames);
            EXPECT(r > 0.01, "non-silent (rms %.4f)", r);
            EXPECT(pk < 0.999, "no clipping (peak %.4f)", pk);
            char path[512];
            snprintf(path, sizeof path, "%s/pce_song.wav", dir);
            write_wav(path, buf, frames);
            printf("  30 s: rms %.4f, peak %.4f, %.1f us per tick (%.0fx realtime)\n", r, pk, us, 16666.7 / us);
        }
        free(buf);
        pcm_destroy(m);
    }
    EXPECT(hash[0] == hash[1], "bit-identical on a second start");
}

/* Every pass of the loop plays like the first: the loop order is entered from order 0 and later by B01 from order 3;
 * the player re-derives the speed at the loop point. */
static void test_loop(void) {
    printf("loop passes\n");
    enum { MAX_PASS = 4096 };
    static int pos[4][MAX_PASS];
    int len[4] = {0};
    PceMachine* m = pcm_create(RATE);
    int mode, o, r, t, pass = -1, first_arrival = -1;
    pcm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 4 * MAX_PASS && pass < 4; ++k) {
        pcm_position(m, &o, &r, &t);
        if (o == g_song.info.loop && r == 0 && t == 0) {
            if (pass < 0) first_arrival = k;
            ++pass;
        }
        pcm_tick(m, g_out);
        if (pass < 0 || pass >= 4 || len[pass] >= MAX_PASS) continue;
        pos[pass][len[pass]++] = o << 16 | r << 8 | t;
    }
    EXPECT(pass == 4 && len[0] == len[1] && len[1] == len[2] && len[0] < MAX_PASS, "passes of %d, %d, %d ticks", len[0], len[1],
           len[2]);
    EXPECT(!memcmp(pos[0], pos[1], sizeof(int) * (size_t)len[0]) && !memcmp(pos[1], pos[2], sizeof(int) * (size_t)len[0]),
           "every pass steps through the same (order, row, tick) positions");
    const int64_t a = ref_tick_cycle(first_arrival, 1), b = ref_tick_cycle(first_arrival + len[0], 1);
    const uint32_t samples = (uint32_t)(b * 4851 / 787500 - a * 4851 / 787500);
    EXPECT(samples == PCE_REF_VGM_LOOP_SAMPLES, "= the VGM loop (%u samples, reference %u)", samples,
           (unsigned)PCE_REF_VGM_LOOP_SAMPLES);
    printf("  %d ticks per pass from tick %d; speed at the loop point %d\n", len[0], first_arrival,
           g_song.info.start_speed[g_song.info.loop]);
    pcm_destroy(m);
}

/* Informational thread-CPU benchmark after 1 s of warm-up. CPU frequency, cache/page state and host contention
 * affect even thread CPU time; their worst/mean ratio is not a deterministic engine correctness assertion. */
static void measure_tick_cost(void) {
    printf("tick cost outliers\n");
    const int ticks = SECONDS * 60;
    PceMachine* m = pcm_create(RATE);
    int mode;
    pcm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 60; ++k) pcm_tick(m, g_out);
    double sum = 0, worst = 0;
    int worst_at = 0;
    for (int k = 0; k < ticks; ++k) {
        const double c0 = cpu_us();
        pcm_tick(m, g_out);
        const double cpu = cpu_us() - c0;
        sum += cpu;
        if (cpu > worst) worst = cpu, worst_at = k;
    }
    const double mean = sum / ticks;
    printf("  thread CPU per tick: mean %.1f us, worst %.1f us (%.2fx, tick %d)\n", mean, worst, worst / mean, worst_at);
    pcm_destroy(m);
}

static void test_seek(void) {
    printf("seek\n");
    /* Targets: the rows straight play is in at these ticks (each sought at its first arrival). */
    static const int at_ticks[] = {5, 40, 120, 250, 400, 600, 900, 1200};
    PceMachine* m = pcm_create(RATE);
    for (size_t i = 0; i < sizeof at_ticks / sizeof at_ticks[0]; ++i) {
        const int o = REF_SONG.pos[3 * at_ticks[i]], r = REF_SONG.pos[3 * at_ticks[i] + 1], k = ref_row_start(&REF_SONG, o, r);
        EXPECT(k >= 0, "target %d/%d is in the reference", o, r);
        if (k < 0) continue;
        int mode = -1;
        const double t0 = now_us();
        EXPECT(pcm_start(m, &g_song, o, r, SEEK_BUDGET, &mode) == PCM_OK && mode == PCM_SEEK_FROM_START, "seek %d/%d", o, r);
        const double us = now_us() - t0;
        EXPECT(pos_is(m, &REF_SONG, k), "lands on %d/%d tick 0", o, r);
        pcm_set_trace(m, collect, &g_col);
        int bad = 0;
        const int n = PCE_REF_TICKS - k < 200 ? PCE_REF_TICKS - k : 200;
        for (int j = 0; j < n; ++j) {
            if (!pos_is(m, &REF_SONG, k + j)) ++bad;
            traced_tick(m);
            if (!same_tick(&g_col, &REF_SONG, k + j)) ++bad;
        }
        pcm_set_trace(m, NULL, NULL);
        EXPECT(bad == 0, "seek %d/%d: %d ticks of writes, interrupts and cycles = straight play (%d differ)", o, r, n, bad);
        /* Sound from the target = straight play from the same tick, bit for bit (every phase, LFSR, latch and filter
         * state was clocked exactly as audible playback). */
        PceMachine* a = pcm_create(RATE);
        pcm_start(a, &g_song, 0, 0, SEEK_BUDGET, &mode);
        for (int j = 0; j < k; ++j) pcm_tick(a, g_out);
        float *x, *y;
        const int fx = render(a, 60, &x);
        pcm_start(m, &g_song, o, r, SEEK_BUDGET, &mode);
        const int fy = render(m, 60, &y);
        EXPECT(fx == fy && !memcmp(x, y, sizeof(float) * 2 * (size_t)fx), "seek %d/%d (%d ticks): sound = straight play", o, r, k);
        printf("  %d/%d: %d ticks fast-forwarded in %.0f us\n", o, r, k, us);
        free(x);
        free(y);
        pcm_destroy(a);
    }
    /* Order 3 rows 0-1 are only reached by the order fallback (order 2 breaks to row 2). */
    EXPECT(ref_row_start(&REF_SONG, 3, 0) < 0, "3/0 is not reached by straight play");
    int mode = -1;
    EXPECT(pcm_start(m, &g_song, 3, 0, SEEK_BUDGET, &mode) == PCM_OK && mode == PCM_SEEK_FROM_ORDER, "3/0: order fallback");
    int o, r, t, ok = 1, rows_seen = 0, last_row = 0;
    pcm_position(m, &o, &r, &t);
    EXPECT(o == 3 && r == 0 && t == 0, "fallback lands on 3/0 (%d/%d/%d)", o, r, t);
    for (int j = 0; j < 255 * 4 && rows_seen < 4; ++j) {
        pcm_tick(m, g_out);
        pcm_position(m, &o, &r, &t);
        if (t == 0) {
            if (o != 3 || r != last_row + 1) ok = 0;
            last_row = r;
            ++rows_seen;
        }
    }
    EXPECT(ok && rows_seen == 4, "fallback then plays order 3 row by row (%d rows)", rows_seen);
    EXPECT(pcm_start(m, &g_song, 1, 17, 10, &mode) == PCM_OK && mode == PCM_SEEK_FROM_ORDER, "small budget: fallback");
    pcm_position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 17 && t == 0, "budget fallback lands on 1/17");
    EXPECT(pcm_start(m, &g_song, 4, 0, SEEK_BUDGET, &mode) == PCM_ERR_IMAGE, "order past the song rejected");
    EXPECT(pcm_start(m, &g_song, 0, 16, SEEK_BUDGET, &mode) == PCM_ERR_IMAGE, "row past the order rejected");
    pcm_destroy(m);
}

/* Plays `image` from the start, applying `alt0`/`alt1` (odd/even ticks) before tick t (from tick `from`; every tick or
 * only then), and compares with `ref`. */
static int play_applying(const PceImage* image, const PceImage* alt0, const PceImage* alt1, int from, int every,
                         const Ref* ref, int* first) {
    PceMachine* m = pcm_create(RATE);
    int mode, bad = 0;
    pcm_start(m, image, 0, 0, SEEK_BUDGET, &mode);
    pcm_set_trace(m, collect, &g_col);
    *first = -1;
    for (int t = 0; t < PCE_REF_TICKS; ++t) {
        if (t >= from && (every || t == from)) pcm_apply_image(m, (t % 2) ? alt0 : alt1);
        if (!pos_is(m, ref, t)) ++bad;
        traced_tick(m);
        if (!same_tick(&g_col, ref, t)) {
            if (*first < 0) *first = t;
            ++bad;
        }
    }
    pcm_destroy(m);
    return bad;
}

/* Ticks the current row lasts, the tick just run included (the machine has run one tick of it). */
static int rest_of_row(PceMachine* m) {
    int o, r, t, n = 1;
    for (;;) {
        pcm_position(m, &o, &r, &t);
        if (t == 0 || n > 600) return n;
        pcm_tick(m, g_out);
        ++n;
    }
}

static void test_live_edits(void) {
    printf("live edits\n");
    int first;
    int bad = play_applying(&g_song, &g_song, &g_song, 0, 1, &REF_SONG, &first);
    EXPECT(bad == 0, "same image applied before every tick: writes = reference (%d differ, first %d)", bad, first);
    bad = play_applying(&g_song, &g_moved, &g_song, 0, 1, &REF_SONG, &first);
    EXPECT(bad == 0, "re-laid-out image and the original alternately before every tick: writes = reference (%d differ, first %d)",
           bad, first);
    {
        PceMachine* m = pcm_create(RATE);
        int mode;
        pcm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
        double worst = 0, total = 0;
        for (int k = 0; k < 1500; ++k) {
            const double t0 = now_us();
            pcm_apply_image(m, (k & 1) ? &g_moved : &g_song);
            const double us = now_us() - t0;
            total += us;
            if (us > worst) worst = us;
            pcm_tick(m, g_out);
        }
        printf("  apply_image: %.2f us mean, worst %.1f us\n", total / 1500, worst);
        pcm_destroy(m);
    }
    /* A note edit in order 2, applied at ticks before its row starts: straight play of the edited song. */
    const int d_edit = first_difference(&REF_SONG, &REF_EDITED);
    EXPECT(d_edit > 0, "the note edit changes the reference (first at tick %d)", d_edit);
    if (d_edit > 0) {
        const int k_edit = row_start_of(&REF_SONG, d_edit);
        const int at[] = {0, 40, k_edit - 30, k_edit - 2, k_edit - 1, k_edit};
        for (size_t i = 0; i < sizeof at / sizeof at[0]; ++i) {
            if (at[i] < 0) continue;
            bad = play_applying(&g_song, &g_edited, &g_edited, at[i], 0, &REF_EDITED, &first);
            EXPECT(bad == 0, "note edit applied before tick %d: = edited straight play (%d differ, first %d)", at[i], bad, first);
        }
    }
    /* Tempo: one Fxx added ahead in the song. Applied ahead of its row: straight play of the tempo song. */
    const int d_tempo = first_difference(&REF_SONG, &REF_TEMPO);
    EXPECT(d_tempo > 0, "the tempo edit changes the reference (first at tick %d)", d_tempo);
    if (d_tempo > 0) {
        /* The row holding the new Fxx (it starts identically in both traces; the first difference is later inside it,
         * when the old speed would have moved on). */
        const int k_tempo = row_start_of(&REF_TEMPO, d_tempo);
        const int from = k_tempo >= 3 ? k_tempo - 3 : 0;
        bad = play_applying(&g_song, &g_tempo, &g_tempo, from, 0, &REF_TEMPO, &first);
        EXPECT(bad == 0, "tempo edit ahead of the playhead: = tempo song straight play (%d differ, first %d)", bad, first);
        /* Behind the playhead: the row has started at the old speed; the edit gives it the new one, the undo the old. */
        const int o = REF_SONG.pos[3 * k_tempo], r = REF_SONG.pos[3 * k_tempo + 1];
        const int old_len = ref_row_length(&REF_SONG, k_tempo), new_len = ref_row_length(&REF_TEMPO, k_tempo);
        PceMachine* m = pcm_create(RATE);
        int mode;
        pcm_start(m, &g_song, o, r, SEEK_BUDGET, &mode);
        pcm_tick(m, g_out);
        pcm_apply_image(m, &g_tempo);
        const int a = rest_of_row(m);
        pcm_start(m, &g_song, o, r, SEEK_BUDGET, &mode);
        pcm_tick(m, g_out);
        pcm_apply_image(m, &g_tempo);
        pcm_apply_image(m, &g_song);
        const int b = rest_of_row(m);
        EXPECT(a == new_len && b == old_len, "tempo edit behind the playhead (%d/%d): row of %d ticks (want %d), undo %d (want %d)",
               o, r, a, new_len, b, old_len);
        pcm_destroy(m);
    }
    {
        PceMachine* m = pcm_create(RATE);
        int mode, o, r, t;
        /* Cut the song to two orders (loop 0) while order 3 plays: clamped, then order 0 from its first row. */
        pcm_start(m, &g_song, 3, 5, SEEK_BUDGET, &mode);
        pcm_tick(m, g_out);
        pcm_apply_image(m, &g_short);
        pcm_position(m, &o, &r, &t);
        EXPECT(o == 1 && r == 31, "clamped to the last row of the last order (%d/%d)", o, r);
        int ok = 1, rows_seen = 0, last_order = o, last_row = r;
        for (int k = 0; k < 255 * 8 && rows_seen < 6; ++k) {
            pcm_tick(m, g_out);
            pcm_position(m, &o, &r, &t);
            if (t == 0) {
                const int want_order = last_order == 1 && last_row == 31 ? 0 : last_order;
                const int want_row = last_order == 1 && last_row == 31 ? 0 : last_row + 1;
                if (o != want_order || r != want_row) ok = 0;
                last_order = o;
                last_row = r;
                ++rows_seen;
            }
        }
        EXPECT(ok && rows_seen == 6, "then the loop order (0) plays in order (%d rows)", rows_seen);
        /* Multispeed applied live halves the tick. */
        pcm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
        pcm_tick(m, g_out);
        const int n1 = pcm_native_samples(m);
        pcm_apply_image(m, &g_x2);
        pcm_tick(m, g_out);
        const int n2 = pcm_native_samples(m);
        EXPECT(n1 >= 29828 && n1 <= 29831 && n2 >= 14913 && n2 <= 14916, "x2 live: %d -> %d native samples per tick (29829.5 -> "
                                                                           "14914.8)",
               n1, n2);
        pcm_destroy(m);
    }
    /* Header shared-state edit: the next tick's writes carry the new song-start values; the new timer reload applies
     * from the next reload. */
    {
        /* Two consecutive ticks inside rows (no row effects run) without shared writes in straight play. */
        int t = 1;
        while (t + 1 < PCE_REF_TICKS && !(quiet_tick(&REF_SONG, t) && quiet_tick(&REF_SONG, t + 1))) ++t;
        EXPECT(t + 1 < PCE_REF_TICKS, "two ticks without row starts or shared writes (%d)", t);
        const uint8_t* h = g_shared.bytes;
        const uint8_t* s = g_song.bytes;
        EXPECT(h[PC_H_MASTER] != s[PC_H_MASTER] && h[PC_H_LFO_FREQ] != s[PC_H_LFO_FREQ] && h[PC_H_LFO_CTRL] != s[PC_H_LFO_CTRL] &&
                   h[PC_H_TIMER] != s[PC_H_TIMER],
               "the shared image edits every shared header field");
        PceMachine* m = pcm_create(RATE);
        int mode;
        pcm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
        for (int k = 0; k < t; ++k) pcm_tick(m, g_out);
        const PcPlayer* p = pcm_player(m);
        const int old_timer = p->timer;
        pcm_apply_image(m, &g_shared);
        pcm_set_trace(m, collect, &g_col);
        traced_tick(m);
        EXPECT(p->master == h[PC_H_MASTER] && p->lfo_freq == h[PC_H_LFO_FREQ] && p->lfo_ctrl == h[PC_H_LFO_CTRL] &&
                   p->timer == h[PC_H_TIMER] && p->shadow_r1 == h[PC_H_MASTER] && p->shadow_r8 == h[PC_H_LFO_FREQ] &&
                   p->shadow_r9 == h[PC_H_LFO_CTRL],
               "after the edit: master %02X LFO %02X/%02X timer %d", p->master, p->lfo_freq, p->lfo_ctrl, p->timer);
        const Batch* b0 = &g_col.b[0];
        const PcWrite* w = g_col.w + b0->first;
        EXPECT(g_col.nb >= 1 && b0->n >= 3 && w[0].reg == 1 && w[0].val == h[PC_H_MASTER] && w[1].reg == 8 &&
                   w[1].val == h[PC_H_LFO_FREQ] && w[2].reg == 9 && w[2].val == h[PC_H_LFO_CTRL],
               "R1, R8, R9 open the tick's batch");
        /* interrupt spacing: the first interrupt keeps the old schedule, every later one 1024 (R + 1) apart */
        int64_t prev = -1;
        int spaced = 1, gaps = 0;
        for (int k = 0; k < 2; ++k) {
            for (int j = 1; j < g_col.nb; ++j) {
                const int64_t c = g_col.b[j].cycle;
                if (prev >= 0) {
                    if (c - prev != (int64_t)PCM_TIMER_DIVIDER * (h[PC_H_TIMER] + 1)) spaced = 0;
                    ++gaps;
                }
                prev = c;
            }
            if (k == 0) traced_tick(m);
        }
        EXPECT(spaced && gaps >= 4, "the new timer reload spaces later interrupts %d cycles apart (%d gaps; old reload %d)",
               PCM_TIMER_DIVIDER * (h[PC_H_TIMER] + 1), gaps, old_timer);
        pcm_set_trace(m, NULL, NULL);
        pcm_apply_image(m, &g_song);
        pcm_tick(m, g_out);
        EXPECT(p->master == s[PC_H_MASTER] && p->lfo_freq == s[PC_H_LFO_FREQ] && p->lfo_ctrl == s[PC_H_LFO_CTRL] &&
                   p->timer == s[PC_H_TIMER] && p->shadow_r1 == s[PC_H_MASTER],
               "undo restores the song's start values");
        pcm_destroy(m);
    }
}

static void test_audition(const char* dir) {
    printf("audition\n");
    PceMachine* m = pcm_create(RATE);
    const double t0 = now_us();
    EXPECT(pcm_audition(m, &g_tone, 0, A4, T_RELEASE, 0) == PCM_OK, "A-4 on the sine wave");
    const double setup = now_us() - t0;
    const PcPlayer* p = pcm_player(m);
    float* buf;
    const int frames = render(m, 120, &buf);
    const double want = PSG_HZ / (32.0 * 254);
    const double hz = zero_cross_hz(buf + 2 * (RATE / 10), frames - RATE / 10);
    EXPECT(fabs(hz - want) < 0.3, "A-4 measured %.3f Hz (%.3f expected: period 254)", hz, want);
    EXPECT(rms(buf + 2 * (frames - RATE / 2), RATE / 2) > 0.5 * rms(buf + 2 * (RATE / 2), RATE / 2), "held");
    int sounding = 0;
    for (int c = 0; c < PC_CHANNELS; ++c) sounding += p->ch[c].sounding;
    EXPECT(sounding == 1 && p->ch[0].sounding, "only PSG 1 sounds (%d)", sounding);
    EXPECT(p->lfo_freq == 0x33 && p->shadow_r8 == 0x33 && p->master == 0xFF, "header shared state kept");
    EXPECT(pcm_release(m) == 1, "release phase");
    float* tail = (float*)malloc(sizeof(float) * 2 * PCM_MAX_TICK_FRAMES * 700);
    int tail_frames = 0, ticks = 0, n;
    while ((n = pcm_tick(m, tail + 2 * tail_frames)) > 0 && ticks < 700) {
        tail_frames += n;
        ++ticks;
    }
    EXPECT(n == 0 && ticks >= 1 && ticks <= 10, "release ends when the written volume reaches 0 (%d ticks)", ticks);
    char path[512];
    snprintf(path, sizeof path, "%s/pce_tone.wav", dir);
    float* both = (float*)malloc(sizeof(float) * 2 * (size_t)(frames + tail_frames));
    memcpy(both, buf, sizeof(float) * 2 * (size_t)frames);
    memcpy(both + 2 * frames, tail, sizeof(float) * 2 * (size_t)tail_frames);
    write_wav(path, both, frames + tail_frames);
    printf("  A-4 %.3f Hz, release %d ticks, setup %.0f us\n", hz, ticks, setup);
    free(both);
    free(tail);
    free(buf);
    /* no release point: OFF stops at once */
    EXPECT(pcm_audition(m, &g_tone, 0, A4, T_SINE, 0) == PCM_OK, "sine");
    pcm_tick(m, g_out);
    EXPECT(pcm_release(m) == 0 && p->ch[0].r4 == 0x00 && pcm_tick(m, g_out) == 0, "release without a release point: stopped");
    EXPECT(pcm_audition(m, &g_tone, 0, A4, T_SINE, 0) == PCM_OK && pcm_release(m) == 0, "release before any tick: silent");
    /* fine pitch: +64 units = +1 semitone */
    pcm_audition(m, &g_tone, 1, A4, T_SINE, 64);
    int f2 = render(m, 60, &buf);
    const double up = zero_cross_hz(buf + 2 * (RATE / 10), f2 - RATE / 10);
    const double want_up = PSG_HZ / (32.0 * pcp_period((A4 + 1) * 64));
    EXPECT(fabs(up - want_up) < 0.4 && fabs(1200 * log2(up / (440.0 * pow(2.0, 1.0 / 12.0)))) < 8,
           "fine +64 = A#4 %.3f Hz (%.3f expected)", up, want_up);
    free(buf);
    pcm_audition(m, &g_tone, 1, A4, T_SINE, -32);
    f2 = render(m, 60, &buf);
    const double down = zero_cross_hz(buf + 2 * (RATE / 10), f2 - RATE / 10);
    const double want_down = PSG_HZ / (32.0 * pcp_period(A4 * 64 - 32));
    EXPECT(fabs(down - want_down) < 0.4, "fine -32 = %.3f Hz (%.3f expected)", down, want_down);
    free(buf);
    /* noise only on PSG 5 and PSG 6 */
    for (int c = 0; c < PC_NOISE_FIRST; ++c)
        EXPECT(pcm_audition(m, &g_tone, c, A4, T_NOISE, 0) == PCM_ERR_IMAGE, "noise instrument on PSG %d rejected", c + 1);
    for (int c = PC_NOISE_FIRST; c < PC_CHANNELS; ++c) {
        EXPECT(pcm_audition(m, &g_tone, c, 63, T_NOISE, 0) == PCM_OK, "noise instrument on PSG %d", c + 1);
        pcm_tick(m, g_out);
        EXPECT(p->ch[c].r7 == (0x80 | (63 & 31)), "PSG %d: R7 = $%02X (noise on, NF 31)", c + 1, p->ch[c].r7);
    }
    EXPECT(pcm_audition(m, &g_tone, 4, A4, T_SINE, 0) == PCM_OK, "a tone on PSG 5");
    pcm_tick(m, g_out);
    EXPECT(p->ch[4].r7 == 0x00, "a tone on PSG 5: noise off");
    /* a sample's root note plays one frame per timer interrupt: the 16-frame square at 6991.3 / 16 Hz */
    for (int octave = 0; octave < 2; ++octave) {
        EXPECT(pcm_audition(m, &g_tone, 2, A4 + 12 * octave, T_LOOP, 0) == PCM_OK, "looping sample on PSG 3");
        const int total = render(m, 120, &buf);
        const double rate = TIMER_HZ * (1 << octave) / TONE_SAMPLE_PERIOD;
        const double got = zero_cross_hz(buf + 2 * (RATE / 10), total - RATE / 10);
        EXPECT(p->ch[2].step == (uint32_t)(65536 << octave), "step %u (%d)", p->ch[2].step, 65536 << octave);
        EXPECT(fabs(got - rate) < 0.005 * rate, "root%s: %.2f Hz (%.2f = 6991.3 x %d / 16)", octave ? " + 12" : "", got, rate,
               1 << octave);
        EXPECT(p->ch[2].dda && p->ch[2].sounding && (p->ch[2].r4 & 0xC0) == 0xC0, "the loop keeps streaming after 2 s");
        EXPECT(rms(buf + 2 * (total - RATE / 2), RATE / 2) > 0.5 * rms(buf + 2 * (RATE / 2), RATE / 2), "loop sustained");
        EXPECT(pcm_timer_interrupts(m) == 116 || pcm_timer_interrupts(m) == 117, "timer reload 0: %d interrupts per tick (116.5)",
               pcm_timer_interrupts(m));
        printf("  sample at %s: %.2f Hz\n", octave ? "root + 12" : "root", got);
        free(buf);
    }
    EXPECT(pcm_audition(m, &g_tone, 3, A4, T_ONESHOT, 0) == PCM_OK, "one-shot sample on PSG 4");
    int ended = 0;
    for (int k = 0; k < 4 && !ended; ++k) ended = pcm_tick(m, g_out) == 0;
    EXPECT(ended && !p->ch[3].dda && !p->ch[3].sounding && p->ch[3].r4 == 0x40,
           "a 64-frame one-shot ends within a tick and the audition with it (R4 $%02X)", p->ch[3].r4);
    /* arguments */
    EXPECT(pcm_audition(m, &g_tone, 0, A4, T_UNDEFINED, 0) == PCM_ERR_IMAGE, "undefined slot rejected");
    EXPECT(pcm_audition(m, &g_tone, 0, A4, 0, 0) == PCM_ERR_IMAGE, "slot 0 rejected");
    EXPECT(pcm_audition(m, &g_tone, 0, A4, 65, 0) == PCM_ERR_IMAGE, "slot 65 rejected");
    EXPECT(pcm_audition(m, &g_tone, 0, PC_NOTE_MIN - 1, T_SINE, 0) == PCM_ERR_IMAGE, "note 8 rejected");
    EXPECT(pcm_audition(m, &g_tone, 0, PC_NOTE_MAX + 1, T_SINE, 0) == PCM_ERR_IMAGE, "note 96 rejected");
    EXPECT(pcm_audition(m, &g_tone, 0, PC_NOTE_MIN, T_SINE, 0) == PCM_OK && pcm_audition(m, &g_tone, 0, PC_NOTE_MAX, T_SINE, 0) == PCM_OK,
           "notes 9 and 95 accepted");
    EXPECT(pcm_audition(m, &g_tone, 6, A4, T_SINE, 0) == PCM_ERR_IMAGE && pcm_audition(m, &g_tone, -1, A4, T_SINE, 0) == PCM_ERR_IMAGE,
           "channels 6 and -1 rejected");
    EXPECT(pcm_audition(m, &g_tone, 0, A4, T_SINE, 128) == PCM_ERR_IMAGE, "fine 128 rejected");
    EXPECT(pcm_audition(m, &g_tone, 0, A4, T_SINE, -129) == PCM_ERR_IMAGE, "fine -129 rejected");
    EXPECT(pcm_audition(m, &g_tone, 0, A4, T_SINE, 127) == PCM_OK && pcm_audition(m, &g_tone, 0, A4, T_SINE, -128) == PCM_OK,
           "fine 127 and -128 accepted");
    pcm_destroy(m);
}

/* OFF releases a note, not a legato target. Keep every audible tail tick; a final zero stops DDA too. */
static void test_release_portamento(void) {
    for (int kind = PC_KIND_TONE; kind <= PC_KIND_SAMPLE; kind += PC_KIND_SAMPLE - PC_KIND_TONE) {
        for (int ending = 0; ending < 3; ++ending) {
            uint8_t data[] = {
                0x89, 0x07, 48, 1, PC_FX_PORTAMENTO, 0x40,
                0x01, PC_NOTE_OFF, 0x01, 52, 0x01, PC_NOTE_OFF,
                0x83, 0x01, 55, 0x80,
                31, 20, 10, 0, 0, 31, 0, 31
            };
            if (ending == 1) data[19] = 7; /* A nonzero release end remains audible. */
            if (ending == 2) data[17] = 0; /* An intermediate zero must not end the release. */
            PceImage im;
            base_image(&im, 10, 1, data, sizeof data);
            set_pattern(&im, 0, 1);
            im.bytes[PC_H_SAMPLES] = 1;
            put32(im.bytes + PC_OFF_SAMPLES, 20);
            put32(im.bytes + PC_OFF_SAMPLES + 4, 4);
            put_instrument(&im, 1, kind, 0, 0, kind == PC_KIND_SAMPLE ? 48 : 0, kind == PC_KIND_SAMPLE, 0);
            put_envelope(&im, 1, PC_ENV_VOLUME, 16, 4, PC_ENV_NONE, 1);
            EXPECT(pc_load_image(&im) == 0, "release/porta image loads: %s", im.info.problem);
            PcPlayer p;
            pcp_reset(&p, &im);
            for (int tick = 0; tick <= 8; ++tick) {
                pcp_tick(&p, &im);
                if (tick == 1 || tick == 3)
                    EXPECT(p.ch[0].sounding && p.ch[0].released && (p.ch[0].r4 & 31) == data[17],
                           "kind %d ending %d retains first release tick", kind, ending);
                if (tick == 2 || tick == 8) {
                    const int note = tick == 2 ? 52 : 55;
                    EXPECT(p.ch[0].sounding && !p.ch[0].released && p.ch[0].base == note * 64 &&
                           (p.ch[0].r4 & 31) == 31,
                           "kind %d ending %d note %d starts a fresh attack after OFF", kind, ending, note);
                }
                if (tick == 4)
                    EXPECT(p.ch[0].sounding && (p.ch[0].r4 & 31) == 10,
                           "kind %d ending %d preserves penultimate release level", kind, ending);
                if (tick == 5) {
                    EXPECT(p.ch[0].sounding == (ending == 1) && (p.ch[0].r4 & 31) == data[19],
                           "kind %d ending %d finishes only the zero-ended release", kind, ending);
                    if (ending != 1) {
                        EXPECT(!p.ch[0].dda, "finished release stops the sample timer lane");
                        pcp_timer(&p, &im);
                        EXPECT(p.writes == 0, "finished release emits no further sample frames");
                    }
                }
                for (int irq = 0; irq < 8; ++irq) pcp_timer(&p, &im);
            }
        }
    }
    /* A held, silent envelope is still a legato target: only OFF changes that ownership. */
    const uint8_t held[] = {0x83, 0x07, 48, 1, PC_FX_PORTAMENTO, 0x40, 0x80, 0x01, 52, 0x80, 31, 0};
    PceImage im;
    base_image(&im, 4, 1, held, sizeof held);
    set_pattern(&im, 0, 1);
    put_instrument(&im, 1, PC_KIND_TONE, 0, 0, 0, 0, 0);
    put_envelope(&im, 1, PC_ENV_VOLUME, 10, 2, PC_ENV_NONE, PC_ENV_NONE);
    EXPECT(pc_load_image(&im) == 0, "held-silent portamento image loads");
    PcPlayer p;
    pcp_reset(&p, &im);
    for (int tick = 0; tick < 3; ++tick) pcp_tick(&p, &im);
    EXPECT(p.ch[0].sounding && !p.ch[0].released && p.ch[0].base == 49 * 64 &&
           p.ch[0].target == 52 * 64 && (p.ch[0].r4 & 31) == 0,
           "held silent note glides without restarting its envelope");
}

/* The chip's LFO: PSG 2's waveform offsets PSG 1's period (M01: +-16 on 254, about +-1 semitone, at 5.5 Hz), so 50 ms
 * windows over 2 s spread widely; M00 and the halt bit ($81) leave the pitch alone. */
static void test_lfo(void) {
    printf("LFO pitch modulation\n");
    PceMachine* m = pcm_create(RATE);
    double spread[3];
    for (int v = 0; v < 3; ++v) {
        int mode;
        pcm_start(m, &g_lfo[v], 0, 0, SEEK_BUDGET, &mode);
        float* buf;
        const int frames = render(m, 150, &buf);
        const int win = RATE / 20;
        double lo = 1e9, hi = 0;
        for (int at = RATE / 2; at + win <= frames; at += win) {
            const double hz = zero_cross_hz(buf + 2 * at, win);
            if (hz < lo) lo = hz;
            if (hz > hi) hi = hz;
        }
        spread[v] = hi - lo;
        free(buf);
    }
    EXPECT(spread[0] < 1.5, "LFO off: no pitch modulation (spread %.2f Hz)", spread[0]);
    EXPECT(spread[1] > 15.0, "M01 (shift 0): the LFO bends PSG 1's pitch (spread %.1f Hz)", spread[1]);
    EXPECT(spread[2] < 1.5, "M81 (halted): no pitch modulation (spread %.2f Hz)", spread[2]);
    printf("  50 ms pitch spread over 2 s: off %.2f Hz, M01 %.1f Hz, M81 %.2f Hz\n", spread[0], spread[1], spread[2]);
    pcm_destroy(m);
}

/* PSG 5's noise (NF 31) against a sine on the same channel: broadband (many zero crossings, a rough waveform) and the
 * LFSR running. */
static void test_noise(void) {
    printf("noise on PSG 5\n");
    PceMachine* m = pcm_create(RATE);
    double rate[2], rough[2], level[2];
    uint32_t lfsr[2] = {0, 0};
    for (int v = 0; v < 2; ++v) {
        pcm_audition(m, &g_tone, 4, v ? 63 : A4, v ? T_NOISE : T_SINE, 0);
        float* buf;
        const int frames = render(m, 40, &buf);
        lfsr[0] = pcm_psg_register(m, 0x400 + 6);
        pcm_tick(m, g_out);
        lfsr[1] = pcm_psg_register(m, 0x400 + 6);
        const int from = RATE / 10, n = frames - from;
        rate[v] = crossing_rate(buf + 2 * from, n);
        rough[v] = roughness(buf + 2 * from, n);
        level[v] = rms(buf + 2 * from, n);
        free(buf);
    }
    EXPECT(level[1] > 0.01, "noise audible (rms %.4f)", level[1]);
    EXPECT(rate[0] > 420 && rate[0] < 460 && rate[1] > 2000, "zero crossings: sine %.0f/s, noise %.0f/s", rate[0], rate[1]);
    EXPECT(rough[0] < 0.15 && rough[1] > 0.3, "spectral spread (difference/level): sine %.3f, noise %.3f", rough[0], rough[1]);
    EXPECT(lfsr[0] != lfsr[1], "the LFSR runs (%05X -> %05X)", lfsr[0], lfsr[1]);
    printf("  sine %.0f crossings/s (%.3f), noise %.0f crossings/s (%.3f), noise rms %.4f\n", rate[0], rough[0], rate[1], rough[1],
           level[1]);
    pcm_destroy(m);
}

/* Balance: 1.5 dB per attenuation step, a balance step = 2 attenuation steps (scale 0 3 5 7 9 11 13 15 16 19 21 23 25 27
 * 29 31): pan $F7 = right 16 steps quieter (level ratio 2^(16/4) = 16), master $FC = right 6 steps (2^(6/4) = 2.83),
 * pan $0F = left silent. */
static void test_balance(void) {
    printf("balance\n");
    PceMachine* m = pcm_create(RATE);
    double ratio[3], left[3], right[3];
    for (int v = 0; v < 3; ++v) {
        int mode;
        pcm_start(m, &g_pan[v], 0, 0, SEEK_BUDGET, &mode);
        float* buf;
        const int frames = render(m, 60, &buf);
        const int from = RATE * 3 / 10;
        left[v] = side_rms(buf + 2 * from, frames - from, 0);
        right[v] = side_rms(buf + 2 * from, frames - from, 1);
        ratio[v] = right[v] > 0 ? left[v] / right[v] : 0;
        free(buf);
    }
    EXPECT(fabs(ratio[0] / 16.0 - 1) < 0.05, "pan F7: left/right %.3f (16)", ratio[0]);
    EXPECT(fabs(ratio[1] / pow(2.0, 1.5) - 1) < 0.05, "master FC: left/right %.3f (2.828)", ratio[1]);
    EXPECT(left[2] < 1e-4 && right[2] > 0.01, "pan 0F: left silent (%.6f), right %.4f", left[2], right[2]);
    printf("  F7 %.3f, master FC %.3f, 0F left %.6f right %.4f\n", ratio[0], ratio[1], left[2], right[2]);
    pcm_destroy(m);
}

static void test_mute(void) {
    printf("mute\n");
    PceMachine* m = pcm_create(RATE);
    int mode, bad = 0;
    pcm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    pcm_mute(m, 1, 1);
    pcm_mute(m, 5, 1);
    pcm_set_trace(m, collect, &g_col);
    for (int t = 0; t < PCE_REF_TICKS; ++t) {
        traced_tick(m);
        if (!same_tick(&g_col, &REF_SONG, t)) ++bad;
    }
    pcm_set_trace(m, NULL, NULL);
    EXPECT(bad == 0, "muted channels leave the player's writes unchanged (%d ticks differ)", bad);
    pcm_start(m, &g_song, 1, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 30; ++k) pcm_tick(m, g_out);
    for (int c = 0; c < PC_CHANNELS; ++c) pcm_mute(m, c, 1);
    float* buf;
    int frames = render(m, 30, &buf);
    const double quiet = rms(buf + 2 * (RATE / 5), frames - RATE / 5);
    EXPECT(quiet < 0.0005, "all channels muted (DDA included): rms %.6f after 200 ms", quiet);
    free(buf);
    for (int c = 0; c < PC_CHANNELS; ++c) pcm_mute(m, c, 0);
    frames = render(m, 30, &buf);
    EXPECT(rms(buf, frames) > 0.005, "unmuted: resumes mid-note (rms %.4f)", rms(buf, frames));
    free(buf);
    pcm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    for (int c = 0; c < PC_CHANNELS; ++c) pcm_mute(m, c, 1);
    frames = render(m, 60, &buf);
    EXPECT(rms(buf, frames) < 0.0005, "muted before the first tick: rms %.6f", rms(buf, frames));
    free(buf);
    pcm_start(m, &g_song, 1, 17, SEEK_BUDGET, &mode); /* after a seek */
    for (int c = 0; c < PC_CHANNELS; ++c) pcm_mute(m, c, 1);
    frames = render(m, 60, &buf);
    EXPECT(rms(buf + 2 * (RATE / 5), frames - RATE / 5) < 0.0005, "muted right after a seek: rms %.6f after 200 ms",
           rms(buf + 2 * (RATE / 5), frames - RATE / 5));
    free(buf);
    pcm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    pcm_mute(m, 0, 1);
    pcm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    frames = render(m, 120, &buf);
    EXPECT(rms(buf, frames) > 0.005, "start clears mutes (rms %.4f)", rms(buf, frames));
    free(buf);
    EXPECT(pcm_mute(m, 6, 1) == PCM_ERR_IMAGE && pcm_mute(m, -1, 1) == PCM_ERR_IMAGE, "channels 6 and -1 rejected");
    pcm_destroy(m);
}

static void test_multispeed(void) {
    printf("multispeed\n");
    PceMachine* m = pcm_create(RATE);
    int mode, o, r, t, total = 0, frames = 0, bad = 0, first = -1;
    pcm_start(m, &g_x2, 0, 0, SEEK_BUDGET, &mode);
    pcm_set_trace(m, collect, &g_col);
    for (int k = 0; k < PCE_REF_TICKS; ++k) {
        if (!pos_is(m, &REF_X2, k)) ++bad;
        frames += traced_tick(m);
        if (k < 120) total += pcm_native_samples(m);
        if (k == 119) pcm_position(m, &o, &r, &t);
        if (!same_tick(&g_col, &REF_X2, k)) {
            if (first < 0) first = k;
            ++bad;
        }
        if (k == 119) {
            EXPECT(frames >= RATE - 2 && frames <= RATE + 2, "120 ticks at x2 = 1 s of host frames (%d)", frames);
        }
    }
    pcm_set_trace(m, NULL, NULL);
    EXPECT(total >= 1789771 && total <= 1789774, "120 ticks at x2 = 1 s of native samples (%d, 1789772.7)", total);
    EXPECT(o == REF_SONG.pos[3 * 120] && r == REF_SONG.pos[3 * 120 + 1] && t == REF_SONG.pos[3 * 120 + 2],
           "x2: the song's rows in fast ticks: after 120 ticks at %d/%d/%d", o, r, t);
    EXPECT(bad == 0, "x2: writes, interrupts with cycles and positions = reference in %d ticks (%d differ, first %d)",
           PCE_REF_TICKS, bad, first);
    pcm_destroy(m);
}

static void test_vgm(const char* dir) {
    printf("VGM export\n");
    size_t cap = (size_t)PCE_REF_VGM_SIZE + 4096;
    uint8_t* buf = (uint8_t*)malloc(cap);
    PcVgmInfo info;
    size_t size = 0, measured = 0;
    EXPECT(pc_vgm_render(&g_song, PCE_REF_VGM_MAX_TICKS, NULL, 0, &measured, &info) == 0 && measured == PCE_REF_VGM_SIZE,
           "measuring: %zu bytes (reference %u)", measured, (unsigned)PCE_REF_VGM_SIZE);
    const double t0 = now_us();
    const int rc = pc_vgm_render(&g_song, PCE_REF_VGM_MAX_TICKS, buf, cap, &size, &info);
    const double us = now_us() - t0;
    EXPECT(rc == 0 && size == PCE_REF_VGM_SIZE && fnv(0xCBF29CE484222325ull, buf, size) == PCE_REF_VGM_FNV,
           "VGM bytes = the reference player's (%zu vs %u bytes)", size, (unsigned)PCE_REF_VGM_SIZE);
    EXPECT(info.ticks == PCE_REF_VGM_TICKS && info.samples == PCE_REF_VGM_SAMPLES && info.loop_offset == PCE_REF_VGM_LOOP_OFFSET &&
               info.loop_samples == PCE_REF_VGM_LOOP_SAMPLES && info.size == size,
           "VGM length and loop (%u ticks, loop at %u, %u samples)", info.ticks, info.loop_offset, info.loop_samples);
    const uint32_t clock = buf[0xA4] | buf[0xA5] << 8 | buf[0xA6] << 16 | (uint32_t)buf[0xA7] << 24;
    EXPECT(!memcmp(buf, "Vgm ", 4) && buf[8] == 0x71 && buf[9] == 0x01 && clock == PC_VGM_CLOCK && buf[0x34] == 0xCC,
           "header: VGM 1.71, HuC6280 at %u Hz, data at 0x100", clock);
    size_t small = 0;
    EXPECT(pc_vgm_render(&g_song, PCE_REF_VGM_MAX_TICKS, buf, 1000, &small, &info) == -1 && small == size,
           "too small a buffer: -1 and the size needed");
    char path[512];
    snprintf(path, sizeof path, "%s/pce_song.vgm", dir);
    FILE* f = fopen(path, "wb");
    if (f) {
        fwrite(buf, 1, size, f);
        fclose(f);
    }
    snprintf(path, sizeof path, "%s/pce_song.bin", dir); /* the image, for tools/audio/pce/pce_reference_check.py */
    if ((f = fopen(path, "wb"))) {
        fwrite(g_song.bytes, 1, sizeof g_song.bytes, f);
        fclose(f);
    }
    printf("  %zu bytes, %u ticks, loop %u samples (%.2f s), rendered in %.0f us\n", size, (unsigned)PCE_REF_VGM_TICKS,
           (unsigned)PCE_REF_VGM_LOOP_SAMPLES, PCE_REF_VGM_LOOP_SAMPLES / 44100.0, us);
    free(buf);
}

/* The hosts' entry point (pce_export.h): the reference VGM plus a GD3 tag. */
static void test_export(void) {
    printf("VGM export entry point\n");
    size_t size = 0;
    const char* problem = NULL;
    const char* title = "Thor \xC3\x96pening \xF0\x9F\x8E\xB9"; /* "Thor Öpening 🎹": 2-byte and 4-byte UTF-8 */
    uint8_t* vgm = pc_export_vgm(g_song.bytes, sizeof g_song.bytes, PCE_REF_VGM_MAX_TICKS, title, "Esko", "2026/11/08", &size,
                                 &problem);
    EXPECT(vgm && !problem, "renders (%s)", problem ? problem : "ok");
    if (!vgm) return;
    const uint32_t gd3_at = 0x14 + (vgm[0x14] | vgm[0x15] << 8 | vgm[0x16] << 16 | (uint32_t)vgm[0x17] << 24);
    const uint32_t eof = 4 + (vgm[4] | vgm[5] << 8 | vgm[6] << 16 | (uint32_t)vgm[7] << 24);
    EXPECT(gd3_at == PCE_REF_VGM_SIZE && eof == size, "GD3 after the reference data (at %u), EOF offset = size %zu", gd3_at, size);
    uint8_t* body = (uint8_t*)malloc(PCE_REF_VGM_SIZE);
    memcpy(body, vgm, PCE_REF_VGM_SIZE);
    const uint32_t ref_eof = PCE_REF_VGM_SIZE - 4;
    memcpy(body + 4, &ref_eof, 4);
    memset(body + 0x14, 0, 4);
    EXPECT(fnv(0xCBF29CE484222325ull, body, PCE_REF_VGM_SIZE) == PCE_REF_VGM_FNV,
           "the data = the reference player's VGM (header 0x04/0x14 aside)");
    free(body);
    const uint8_t* g = vgm + gd3_at;
    const uint32_t len = g[8] | g[9] << 8 | g[10] << 16 | (uint32_t)g[11] << 24;
    EXPECT(!memcmp(g, "Gd3 ", 4) && g[4] == 0 && g[5] == 1 && gd3_at + 12 + len == size, "GD3 1.00 header, length %u", len);
    /* the 11 strings, decoded back to UTF-8 (BMP + surrogate pairs) */
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
    EXPECT(!strcmp(fields[0], title) && !strcmp(fields[4], "PC Engine (Thor Tracker)") && !strcmp(fields[6], "Esko") &&
               !strcmp(fields[8], "2026/11/08") && !strcmp(fields[9], "Thor Tracker") && !fields[2][0] && !fields[10][0],
           "GD3 fields: '%s', '%s', '%s', '%s', '%s'", fields[0], fields[4], fields[6], fields[8], fields[9]);
    pc_export_free(vgm);
    /* rejected images report the loader's reason */
    memcpy(g_bad.bytes, g_song.bytes, sizeof g_bad.bytes);
    g_bad.bytes[PC_H_MULTISPEED] = 9;
    vgm = pc_export_vgm(g_bad.bytes, sizeof g_bad.bytes, PCE_REF_VGM_MAX_TICKS, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && problem && strstr(problem, "multispeed"), "bad image: NULL, '%s'", problem ? problem : "");
    vgm = pc_export_vgm(g_song.bytes, 100, PCE_REF_VGM_MAX_TICKS, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && problem && !strcmp(problem, "image size"), "short image: '%s'", problem ? problem : "");
    vgm = pc_export_vgm(g_song.bytes, sizeof g_song.bytes, 100, NULL, NULL, NULL, &size, &problem);
    EXPECT(vgm && (vgm[0x1C] | vgm[0x20]) == 0 && size > 0x100, "tick limit before the loop closes: no loop");
    if (vgm) {
        /* no date given: today's, "yyyy/mm/dd" (ASCII in UTF-16LE: every other byte) */
        const uint8_t* d = vgm + 0x14 + (vgm[0x14] | vgm[0x15] << 8 | vgm[0x16] << 16 | (uint32_t)vgm[0x17] << 24) + 12;
        for (int f = 0; f < 8; ++f, d += 2)
            while (d[0] | d[1]) d += 2;
        char date[16] = {0};
        for (int i = 0; i < 15 && (d[2 * i] | d[2 * i + 1]); ++i) date[i] = (char)d[2 * i];
        EXPECT(strlen(date) == 10 && date[4] == '/' && date[7] == '/' && !strncmp(date, "20", 2), "default date today: %s", date);
    }
    pc_export_free(vgm);
}

static void test_ops(void) {
    printf("ops table\n");
    const ChipEngineOps* ops = chip_engine_ops("pce-huc6280");
    EXPECT(ops && !strcmp(ops->system_id, "pce-huc6280"), "registry");
    if (!ops) return;
    EXPECT(ops->image_size == PC_IMAGE_SIZE && ops->image_storage == sizeof(PceImage), "sizes");
    EXPECT(ops->create(CHIP_MAX_SAMPLE_RATE + 1) == NULL, "rate above the maximum rejected");
    ChipImage* image = (ChipImage*)calloc(1, ops->image_storage);
    ChipImage* tone = (ChipImage*)calloc(1, ops->image_storage);
    EXPECT(ops->load_image(image, g_song.bytes, ops->image_size - 1) == CHIP_ERR_IMAGE, "wrong size rejected");
    memcpy(g_bad.bytes, g_song.bytes, sizeof g_bad.bytes);
    g_bad.bytes[0] = 'X';
    EXPECT(ops->load_image(image, g_bad.bytes, ops->image_size) == CHIP_ERR_IMAGE, "invalid rejected");
    EXPECT(ops->load_image(image, g_song.bytes, ops->image_size) == CHIP_OK, "song loads");
    EXPECT(ops->load_image(tone, g_tone.bytes, ops->image_size) == CHIP_OK, "tone image loads");
    ChipMachine* m = ops->create(RATE);
    int mode = -1, o, r, t;
    const int k = ref_row_start(&REF_SONG, 1, 4);
    EXPECT(k >= 0 && k + 60 < PCE_REF_TICKS, "1/4 is in the reference");
    EXPECT(ops->start(m, image, 1, 4, SEEK_BUDGET, &mode) == CHIP_OK && mode == CHIP_SEEK_FROM_START, "start 1/4");
    ops->position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 4 && t == 0, "position 1/4/0");
    static float out[CHIP_MAX_TICK_FRAMES * 2];
    int frames = 0, n = 0;
    for (int j = 0; j < 60; ++j) frames += (n = ops->tick(m, out));
    EXPECT(frames >= 48000 - 2 && frames <= 48000 + 2 && n >= 799 && n <= 801, "60 ticks = 1 s (%d frames)", frames);
    ops->position(m, &o, &r, &t);
    if (k >= 0 && k + 60 < PCE_REF_TICKS)
        EXPECT(o == REF_SONG.pos[3 * (k + 60)] && r == REF_SONG.pos[3 * (k + 60) + 1] && t == REF_SONG.pos[3 * (k + 60) + 2],
               "60 ticks later = straight play (%d/%d/%d)", o, r, t);
    ops->apply_image(m, image);
    EXPECT(ops->mute(m, 0, 1) == CHIP_OK && ops->mute(m, 0, 0) == CHIP_OK, "mute");
    EXPECT(ops->audition(m, tone, 0, A4, T_RELEASE, 0) == CHIP_OK, "audition");
    ops->tick(m, out);
    EXPECT(ops->release(m) == 1, "release phase");
    int ticks = 0;
    while (ops->tick(m, out) > 0 && ticks < 1000) ++ticks;
    EXPECT(ticks < 1000, "released note ends (%d ticks)", ticks);
    ops->destroy(m);
    free(image);
    free(tone);
}

static void test_costs(void) {
    printf("costs\n");
    PceMachine* m = pcm_create(RATE);
    int mode;
    double t0 = now_us();
    pcm_start(m, &g_song, 3, 20, SEEK_BUDGET, &mode);
    const double seek = now_us() - t0;
    t0 = now_us();
    PcPlayer* p = (PcPlayer*)malloc(sizeof *p);
    pcp_reset(p, &g_song);
    for (int k = 0; k < 3600; ++k) pcp_tick(p, &g_song);
    const double per = (now_us() - t0) / 3600;
    free(p);
    printf("  seek to 3/20: %.0f us; player alone %.2f us per tick (3600 ticks: %.1f ms)\n", seek, per, per * 3.6);
    pcm_destroy(m);
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    EXPECT(load(&g_song, pce_song_image, sizeof pce_song_image) == 0, "song loads: %s", g_song.info.problem);
    EXPECT(load(&g_moved, pce_moved_image, sizeof pce_moved_image) == 0, "moved loads: %s", g_moved.info.problem);
    EXPECT(load(&g_edited, pce_edited_image, sizeof pce_edited_image) == 0, "edited loads: %s", g_edited.info.problem);
    EXPECT(load(&g_tempo, pce_tempo_image, sizeof pce_tempo_image) == 0, "tempo loads: %s", g_tempo.info.problem);
    EXPECT(load(&g_short, pce_short_image, sizeof pce_short_image) == 0, "short loads: %s", g_short.info.problem);
    EXPECT(load(&g_x2, pce_x2_image, sizeof pce_x2_image) == 0, "x2 loads: %s", g_x2.info.problem);
    EXPECT(load(&g_shared, pce_shared_image, sizeof pce_shared_image) == 0, "shared loads: %s", g_shared.info.problem);
    build_tone();
    build_lfo(&g_lfo[0], 0x00);
    build_lfo(&g_lfo[1], 0x01);
    build_lfo(&g_lfo[2], 0x81);
    build_pan(&g_pan[0], 0xF7, 0xFF);
    build_pan(&g_pan[1], 0xFF, 0xFC);
    build_pan(&g_pan[2], 0x0F, 0xFF);
    test_tables();
    test_images();
    test_reference(dir);
    test_audio(dir);
    measure_tick_cost();
    test_loop();
    test_seek();
    test_live_edits();
    test_audition(dir);
    test_release_portamento();
    test_lfo();
    test_noise();
    test_balance();
    test_mute();
    test_multispeed();
    test_vgm(dir);
    test_export();
    test_ops();
    test_costs();
    printf("PC Engine: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
