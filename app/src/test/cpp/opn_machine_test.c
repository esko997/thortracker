/* Host regression test for the OPN and OPNA systems ("ym2203-opn", "ym2608-opna"): Thor Tracker's own player
 * (opn/opn_player.c) on ymfm's YM2203 / YM2608 (opn/opn_ymfm.cpp) through the machine (opn/opn_machine.c), the VGM
 * renderer (opn/opn_vgm.c) and the hosts' entry points (chip_engine_ops, opn_export_vgm). Every image is built here, in
 * C, by a small writer that follows opn_image.h (no generated fixtures; the ADPCM-B samples are a sine and a chirp
 * encoded here with the YM2608's own ADPCM-B decoder model; never any ROM bytes).
 *
 * Checks, on both chips unless named:
 *  - loader: a minimal and a full image accepted (info derived: channels, tick length, rows, start speeds, rhythm use);
 *    single-field corruptions of the header, tables, instruments, envelopes, samples and pattern rows each rejected
 *    with its reason (the loader's problem string);
 *  - pitch from rendered audio through the hosts' ops table: FM A-4 = 440 Hz, C-2 and C-6 (block/F-number from the
 *    registers and from the 12-TET formula), SSG A-4 and B-0 (the lowest note) within the SSG's period rounding,
 *    ADPCM-B: a 500 Hz sine recorded at 16 kHz played at its root and +12;
 *  - FM 3 special mode: the four operators' frequency pairs (note + per-operator offsets, high byte first), operator 4
 *    alone heard an octave up; CSM: timer A expiries every (1024 - NA) native samples while an FM 3 note sounds, none
 *    after its OFF, and never a $28 key-on of FM 3;
 *  - SSG: the mixer composed per channel, the noise period from the highest channel with a noise envelope (else Nxx),
 *    the hardware envelope period from the highest channel with a hardware envelope (auto and manual), $0D at key-on,
 *    the level and Cxx;
 *  - YM2608 rhythm: $18+d = pan | level and $10 = 1 << d per drum, the dump ($10 = $80 | 1 << d) in the key-off phase,
 *    Rxx; without the ROM silence and opn_chip_rhythm_missing; with the user's ROM (THOR_RHYTHM_ROM, SHA-256
 *    checked, never committed) audible drums, drum audition and no ROM bytes in the VGM;
 *  - YM2608 ADPCM-B: the key-on register sequence, a non-looping sample ending at its length in silence, a looping one
 *    still playing, OFF, level and pan;
 *  - positions, speeds (Fxx), jumps (Bxx, Dxx), the order fallback and the budget fallback of a seek (its writes = a
 *    player placed at the order); seeks are fully clocked replays: from the target on, positions, writes and output
 *    samples identical to straight play from (0, 0) - targets with an ADPCM-B chirp keyed long before, an FM release
 *    tail, the SSG hardware envelope and CSM running;
 *  - live relayout: apply_image before every tick (alternately a copy whose data sits elsewhere) = straight play's
 *    writes, positions and samples; mute continuity at the chip: a part muted then unmuted gives every OpnOutput
 *    field of a never-muted chip from the unmute on (0 for it and the others identical while muted); in the machine
 *    mute leaves the writes alone, equals the song without the channel while muted and converges after the unmute;
 *  - audition and release until silent per part, fine pitch, argument limits; deterministic, non-silent output;
 *  - VGM: header, clocks, data block $81 with the ADPCM-B memory on the YM2608 with samples (none on the YM2203 or
 *    without samples), loop offset and samples, the end command, sample totals = ticks x tick length, the GD3 tag of
 *    opn_export_vgm; the ops tables as the hosts drive them; cost per tick;
 *  - VGM loops: each file expanded (its loop body played 3 times) = exactly the writes of a fresh player ticked
 *    straight that long (machine reset, init, every tick's batch), the loop between two arrivals at the loop point -
 *    notes held across the wrap (SSG hardware envelope, CSM FM 3, looping ADPCM-B) loop from the second arrival, a
 *    volume slide clamping after several passes loops later, an ever-rising pitch slide gives no loop.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir]
 * Writes to the evidence directory: <tone>.wav + <tone>.vgm for every measured tone (opn_fm_a4, opn_fm_c2,
 * opn_fm_c6, opn_ssg_a4, opn_ssg_b0, opn_fm3_special and the opna_ ones plus opna_adpcm_root / opna_adpcm_up12),
 * opn_csm.wav / opna_csm.wav, opn_song.wav / .vgm / .bin and opna_song.wav / .vgm / .bin (the test songs and images),
 * <loop song>.vgm + <loop song>_trace.txt (opn_loop_wrap, opn_loop_volslide, opn_loop_none, opn_song and the opna_
 * ones), with the ROM opna_rhythm.wav, and opn_evidence.txt (what each file holds), the inputs of
 * tools/audio/opn_reference_check.py. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "chip_engine.h"
#include "opn_export.h"
#include "opn_image.h"
#include "opn_machine.h"
#include "opn_player.h"
#include "opn_vgm.h"
#include "opn_ymfm.h"
#include "ym2608_rhythm_rom.h"

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
#define NATIVE_HZ (3993600.0 / 72.0)
#define SSG_TONE_CLOCK 124800.0 /* 1996800 / 16: tone = 124800 / TP on both chips */
#define TICK_SAMPLES 928        /* 16 x (256 - 198) native samples at the default timer B */
#define SEEK_BUDGET 12000
#define NOENV OPN_NO_ENVELOPE
#define NONE (-1)
#define OFF OPN_NOTE_OFF
#define FX(code, param) ((code) << 8 | (param))
#define TONE_TICKS 90

/* ADPCM-B test samples: a 500 Hz sine and a 100 -> 250 Hz chirp, both recorded at 16 kHz. */
#define SINE_RATE 16000
#define SINE_HZ 500.0
#define SINE_SAMPLES 4096
#define SINE_BYTES (SINE_SAMPLES / 2)
#define CHIRP_SAMPLES 160000
#define CHIRP_BYTES (CHIRP_SAMPLES / 2)

/* The test song: 4 orders x 32 rows at speed 3 (384 ticks, 6.4 s), looping to order 1. */
#define SONG_ORDERS 4
#define SONG_ROWS 32
#define SONG_SPEED 3
#define SONG_TICKS (SONG_ORDERS * SONG_ROWS * SONG_SPEED)
#define VGM_PASSES 3 /* loop passes a VGM is expanded to when compared with straight play's writes */

static const char* ROM_SHA256 = "53afd0fa9c62eda3e2be939e23f3adf48a2af8ad37bb1640261726c5d5adeba8";

static float g_out[OPNM_MAX_TICK_FRAMES * 2];
static OpnImage g_img, g_bad, g_song[2], g_moved[2], g_drop, g_pos, g_rhythm;
static int16_t g_sine_pcm[SINE_SAMPLES], g_chirp_pcm[CHIRP_SAMPLES];
static uint8_t g_sine_adpcm[SINE_BYTES], g_chirp_adpcm[CHIRP_BYTES];
static uint8_t g_vgm[4 << 20];
static OpnPlayer g_vplayer[2]; /* opn_vgm_render's scratch: two players */
static FILE* g_manifest;

/* ---- helpers -------------------------------------------------------------------------------------------------- */

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

static void put16(uint8_t* p, int v) {
    p[0] = (uint8_t)(v & 0xFF);
    p[1] = (uint8_t)((v >> 8) & 0xFF);
}

static void put32(uint8_t* p, uint32_t v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

static const char* engine_id(int chip) { return chip == OPN_CHIP_YM2608 ? "ym2608-opna" : "ym2203-opn"; }
static const char* chip_name(int chip) { return chip == OPN_CHIP_YM2608 ? "YM2608" : "YM2203"; }
static const char* prefix(int chip) { return chip == OPN_CHIP_YM2608 ? "opna" : "opn"; }

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

static void save(const char* dir, const char* name, const void* data, size_t n) {
    char path[512];
    snprintf(path, sizeof path, "%s/%s", dir, name);
    FILE* f = fopen(path, "wb");
    if (!f) return;
    fwrite(data, 1, n, f);
    fclose(f);
}

static void save_wav(const char* dir, const char* name, const float* data, int frames) {
    char path[512];
    snprintf(path, sizeof path, "%s/%s.wav", dir, name);
    write_wav(path, data, frames);
}

/* Frequency from rising zero crossings of channel `ch` (0 left, 1 right) of interleaved stereo (mean removed). */
static double zero_cross_hz(const float* x, int frames, int ch) {
    if (frames < 2) return 0;
    double mean = 0;
    for (int i = 0; i < frames; ++i) mean += x[2 * i + ch];
    mean /= frames;
    int count = 0;
    double first_t = -1, last_t = 0;
    for (int i = 1; i < frames; ++i) {
        const double a = x[2 * (i - 1) + ch] - mean, b = x[2 * i + ch] - mean;
        if (a < 0 && b >= 0) {
            const double t = (i - 1) + a / (a - b);
            if (first_t < 0) first_t = t;
            else {
                last_t = t;
                ++count;
            }
        }
    }
    return count ? count * (double)RATE / (last_t - first_t) : 0;
}

static double rms(const float* x, int frames) {
    double s = 0;
    for (int i = 0; i < frames * 2; ++i) s += (double)x[i] * x[i];
    return frames > 0 ? sqrt(s / (frames * 2.0)) : 0;
}

static double rms_ch(const float* x, int frames, int ch) {
    double s = 0;
    for (int i = 0; i < frames; ++i) s += (double)x[2 * i + ch] * x[2 * i + ch];
    return frames > 0 ? sqrt(s / frames) : 0;
}

static double peak(const float* x, int frames) {
    double p = 0;
    for (int i = 0; i < frames * 2; ++i)
        if (fabs(x[i]) > p) p = fabs(x[i]);
    return p;
}

static int render(OpnMachine* m, int ticks, float** buf) {
    float* out = (float*)malloc((size_t)ticks * OPNM_MAX_TICK_FRAMES * 2 * sizeof(float));
    int frames = 0;
    for (int t = 0; t < ticks; ++t) {
        const int n = opnm_tick(m, out + 2 * frames);
        if (n <= 0) break;
        frames += n;
    }
    *buf = out;
    return frames;
}

/* Ticks until the next tick starts (order, row) and runs that tick: 1, or 0 if it never came within 4000 ticks. */
static int tick_row(OpnMachine* m, int order, int row) {
    for (int i = 0; i < 4000; ++i) {
        int o, r, t;
        opnm_position(m, &o, &r, &t);
        const int at = o == order && r == row && t == 0;
        opnm_tick(m, g_out);
        if (at) return 1;
    }
    return 0;
}

/* Index of a write (port, reg, val; val -1 = any) in write[from .. to - 1] (the last one), or -1. */
static int find_write(const OpnPlayer* p, int from, int to, int port, int reg, int val) {
    int found = -1;
    for (int i = from; i < to; ++i)
        if (p->write[i].port == port && p->write[i].reg == reg && (val < 0 || p->write[i].val == val)) found = i;
    return found;
}

/* ---- pitch math (12-TET, the datasheet formulas) ------------------------------------------------------------------ */

static double et_hz(double note) { return 440.0 * pow(2.0, (note - 57.0) / 12.0); }

/* The block and F-number of pitch `units` (1/64 semitone from C-0): block = units / 768, F-number of C at block b
 * = 16.3516 Hz x 2^21 / native rate (618), times 2^(fraction / 768). */
static void fm_expect(int units, int* block, int* fnum) {
    *block = units / 768;
    *fnum = (int)lround(16.351597831 * 2097152.0 / NATIVE_HZ * pow(2.0, (units % 768) / 768.0));
}

/* f = F-number x 2^(block - 1) x native rate / 2^20 (= fM / (72 x 2^20) on the YM2203, fM / (144 x 2^20) on the
 * YM2608). */
static double fm_hz(int block, int fnum) { return fnum * ldexp(1.0, block) / 2.0 * NATIVE_HZ / 1048576.0; }

static void fm_regs(const OpnPlayer* p, int ch, int* block, int* fnum) {
    const int port = ch / 3, i = ch % 3, hi = p->reg[port][0xA4 + i];
    *fnum = (hi & 7) << 8 | p->reg[port][0xA0 + i];
    *block = hi >> 3 & 7;
}

static int ssg_tp(const OpnPlayer* p, int i) { return p->reg[0][2 * i] | (p->reg[0][2 * i + 1] & 0x0F) << 8; }
static int ssg_env_period(const OpnPlayer* p) { return p->reg[0][0x0B] | p->reg[0][0x0C] << 8; }
static int adpcm_dn(const OpnPlayer* p) { return p->reg[1][0x09] | p->reg[1][0x0A] << 8; }
static int expect_dn(int rate, int note, int root) {
    return (int)lround(rate * 65536.0 / NATIVE_HZ * pow(2.0, (note - root) / 12.0));
}

/* ---- ADPCM-B encoder ------------------------------------------------------------------------------------------- */

/* Greedy ADPCM-B encoder against the YM2608's decoder (ymfm adpcm_b_channel::clock): per sample the nibble whose
 * decoded value lands nearest, high nibble first, step 127..24576 scaled by 57/57/57/57/77/102/128/153 / 64; padded
 * to a multiple of 64 samples (32 bytes) toward 0. Returns the byte count. */
static uint32_t adpcm_encode(const int16_t* pcm, int n, uint8_t* out) {
    static const int scale[8] = {57, 57, 57, 57, 77, 102, 128, 153};
    const int nibbles = (n + 63) / 64 * 64;
    int acc = 0, step = 127;
    for (int i = 0; i < nibbles; ++i) {
        const int target = i < n ? pcm[i] : 0;
        int best = 0, best_err = 1 << 30, best_acc = acc;
        for (int c = 0; c < 16; ++c) {
            int d = (2 * (c & 7) + 1) * step / 8;
            if (c & 8) d = -d;
            int a = acc + d;
            a = a < -32768 ? -32768 : a > 32767 ? 32767 : a;
            const int e = abs(a - target);
            if (e < best_err) best = c, best_err = e, best_acc = a;
        }
        acc = best_acc;
        step = step * scale[best & 7] / 64;
        step = step < 127 ? 127 : step > 24576 ? 24576 : step;
        if (i & 1) out[i / 2] |= (uint8_t)best;
        else out[i / 2] = (uint8_t)(best << 4);
    }
    return (uint32_t)(nibbles / 2);
}

static void make_samples(void) {
    for (int i = 0; i < SINE_SAMPLES; ++i) g_sine_pcm[i] = (int16_t)lrint(16000.0 * sin(2.0 * M_PI * SINE_HZ * i / SINE_RATE));
    double phase = 0;
    for (int i = 0; i < CHIRP_SAMPLES; ++i) {
        g_chirp_pcm[i] = (int16_t)lrint(16000.0 * sin(phase));
        phase += 2.0 * M_PI * (100.0 + 150.0 * i / CHIRP_SAMPLES) / SINE_RATE;
    }
    const uint32_t a = adpcm_encode(g_sine_pcm, SINE_SAMPLES, g_sine_adpcm);
    const uint32_t b = adpcm_encode(g_chirp_pcm, CHIRP_SAMPLES, g_chirp_adpcm);
    EXPECT(a == SINE_BYTES && b == CHIRP_BYTES, "ADPCM-B samples encoded (%u, %u bytes)", a, b);
}

/* ---- the image writer (opn_image.h) ------------------------------------------------------------------------------ */

typedef struct Ev {
    int order, row, ch, note, ins, nfx;
    int fx[OPN_MAX_EFFECTS]; /* FX(code, param) */
} Ev;

typedef struct B {
    OpnImage* im;
    uint8_t* b;
    int chip, orders, data, ok;
} B;

static void b_init(B* s, OpnImage* im, int chip, int orders, int rows, int speed) {
    memset(im, 0, sizeof *im);
    s->im = im;
    s->b = im->bytes;
    s->chip = chip;
    s->orders = orders;
    s->data = 0;
    s->ok = 1;
    uint8_t* b = s->b;
    memcpy(b + OPN_H_MAGIC, "TTOP", 4);
    b[OPN_H_LAYOUT] = OPN_LAYOUT;
    b[OPN_H_CHIP] = (uint8_t)chip;
    b[OPN_H_SPEED] = (uint8_t)speed;
    put16(b + OPN_H_ORDERS, orders);
    b[OPN_H_TIMER_B] = OPN_TIMER_B_DEFAULT;
    b[OPN_H_RHYTHM_LEVEL] = chip == OPN_CHIP_YM2608 ? 0x3F : 0;
    for (int o = 0; o < orders; ++o) b[OPN_OFF_ROWS + o] = (uint8_t)(rows & 0xFF); /* 256 -> 0 */
}

static int b_bytes(B* s, const uint8_t* p, int n) {
    if (s->data + n > (int)OPN_DATA_CAPACITY) {
        s->ok = 0;
        return 0;
    }
    memcpy(s->b + OPN_OFF_DATA + s->data, p, (size_t)n);
    const int at = s->data;
    s->data += n;
    return at;
}

/* Unreferenced bytes at the current end of the data region: moves everything written after them. */
static void b_pad(B* s, int n) {
    static uint8_t junk[1024];
    memset(junk, 0xA5, sizeof junk);
    b_bytes(s, junk, n < 1024 ? n : 1024);
}

static int b_env(B* s, const int8_t* v, int len, int loop, int release) {
    uint8_t h[OPN_ENV_HEADER];
    put16(h, len);
    put16(h + 2, loop < 0 ? OPN_NO_ENVELOPE : loop);
    put16(h + 4, release < 0 ? OPN_NO_ENVELOPE : release);
    const int at = b_bytes(s, h, OPN_ENV_HEADER);
    b_bytes(s, (const uint8_t*)v, len);
    return at;
}

static uint8_t* b_ins(B* s, int slot, int kind) {
    uint8_t* r = s->b + OPN_OFF_INSTRUMENTS + OPN_INSTRUMENT_SIZE * (slot - 1);
    memset(r, 0, OPN_INSTRUMENT_SIZE);
    r[OPN_I_FLAGS] = (uint8_t)(OPN_FLAG_DEFINED | kind);
    if (slot > s->b[OPN_H_INSTRUMENTS]) s->b[OPN_H_INSTRUMENTS] = (uint8_t)slot;
    return r;
}

static void fm_op(uint8_t* r, int k, int dtmul, int tl, int ar, int dr, int sr, int slrr) {
    uint8_t* o = r + OPN_I_OPS + OPN_OP_BYTES * k;
    o[0] = (uint8_t)dtmul, o[1] = (uint8_t)tl, o[2] = (uint8_t)ar, o[3] = (uint8_t)dr, o[4] = (uint8_t)sr;
    o[5] = (uint8_t)slrr, o[6] = 0;
}

/* Algorithm 7 with only operator `op` (0..3) audible: MUL 1, TL 0, AR 31, no decay, RR 15: a sine at the note. */
static uint8_t* ins_fm_sine(B* s, int slot, int op) {
    uint8_t* r = b_ins(s, slot, OPN_KIND_FM);
    r[OPN_I_ALG] = 7;
    for (int k = 0; k < 4; ++k) fm_op(r, k, 0x01, k == op ? 0x00 : 0x7F, 0x1F, 0, 0, 0x0F);
    return r;
}

/* Algorithm 4 (two 2-op pairs), feedback 5: a brassy bass. */
static void ins_fm_brass(B* s, int slot) {
    uint8_t* r = b_ins(s, slot, OPN_KIND_FM);
    r[OPN_I_ALG] = 4;
    r[OPN_I_FEEDBACK] = 5;
    fm_op(r, 0, 0x01, 0x1E, 0x1F, 0x05, 0x02, 0x27);
    fm_op(r, 1, 0x01, 0x00, 0x1F, 0x08, 0x02, 0x37);
    fm_op(r, 2, 0x02, 0x24, 0x1F, 0x05, 0x02, 0x27);
    fm_op(r, 3, 0x01, 0x04, 0x1F, 0x08, 0x02, 0x37);
}

static void ins_ssg(B* s, int slot, int shape, int autop, int octave, int period, int vol, int mixer, int noise) {
    uint8_t* r = b_ins(s, slot, OPN_KIND_SSG);
    r[OPN_I_SSG_SHAPE] = (uint8_t)shape;
    r[OPN_I_SSG_AUTO] = (uint8_t)autop;
    r[OPN_I_SSG_OCTAVE] = (uint8_t)(int8_t)octave;
    put16(r + OPN_I_SSG_PERIOD, period);
    put16(r + OPN_I_ENV_VOLUME, vol);
    put16(r + OPN_I_ENV_ARPEGGIO, NOENV);
    put16(r + OPN_I_ENV_PITCH, NOENV);
    put16(r + OPN_I_ENV_MIXER, mixer);
    put16(r + OPN_I_ENV_NOISE, noise);
}

static void ins_rhythm(B* s, int slot, int level, int pan) {
    uint8_t* r = b_ins(s, slot, OPN_KIND_RHYTHM);
    r[OPN_I_RHYTHM_LEVEL] = (uint8_t)level;
    r[OPN_I_PAN] = (uint8_t)pan;
}

static void ins_adpcm(B* s, int slot, int level, int sample, int root, int flags, int pan) {
    uint8_t* r = b_ins(s, slot, OPN_KIND_ADPCM);
    r[OPN_I_PAN] = (uint8_t)pan;
    r[OPN_I_ADPCM_LEVEL] = (uint8_t)level;
    r[OPN_I_ADPCM_SAMPLE] = (uint8_t)sample;
    r[OPN_I_ADPCM_ROOT] = (uint8_t)root;
    r[OPN_I_ADPCM_FLAGS] = (uint8_t)flags;
}

static void b_sample(B* s, int k, const uint8_t* adpcm, uint32_t bytes, uint32_t at, uint32_t rate) {
    memcpy(s->b + OPN_OFF_ADPCM + at, adpcm, bytes);
    uint8_t* e = s->b + OPN_OFF_SAMPLES + OPN_SAMPLE_SIZE * k;
    put32(e + OPN_S_START, at);
    put32(e + OPN_S_END, at + bytes);
    put32(e + OPN_S_RATE, rate);
    if (k + 1 > s->b[OPN_H_SAMPLES]) s->b[OPN_H_SAMPLES] = (uint8_t)(k + 1);
    if (at + bytes > opn_u32(s->b + OPN_H_ADPCM_SIZE)) put32(s->b + OPN_H_ADPCM_SIZE, at + bytes);
}

/* Encodes every (order, channel) pattern from the events (empty patterns of one length shared), writes the data size
 * and loads the image. Returns opn_load_image's result. */
static int b_finish(B* s, const Ev* ev, int n) {
    static Ev grid[256];
    static uint8_t tmp[256 * (3 + 2 * OPN_MAX_EFFECTS) + 8];
    int empty_at[257];
    for (int i = 0; i <= 256; ++i) empty_at[i] = -1;
    const int channels = opn_channels(s->chip);
    for (int o = 0; o < s->orders; ++o) {
        const int rows = s->b[OPN_OFF_ROWS + o] ? s->b[OPN_OFF_ROWS + o] : 256;
        for (int c = 0; c < channels; ++c) {
            int used = 0;
            for (int r = 0; r < rows; ++r) grid[r].note = NONE, grid[r].ins = 0, grid[r].nfx = 0;
            for (int i = 0; i < n; ++i)
                if (ev[i].order == o && ev[i].ch == c && ev[i].row < rows) grid[ev[i].row] = ev[i], used = 1;
            int at;
            if (!used && empty_at[rows] >= 0) at = empty_at[rows];
            else {
                int len = 0, r = 0;
                while (r < rows) {
                    const Ev* e = &grid[r];
                    if (e->note < 0 && !e->ins && !e->nfx) {
                        int k = 0;
                        while (r + k < rows && k < 128 && grid[r + k].note < 0 && !grid[r + k].ins && !grid[r + k].nfx) ++k;
                        tmp[len++] = (uint8_t)(0x80 | (k - 1));
                        r += k;
                        continue;
                    }
                    tmp[len++] = (uint8_t)((e->note >= 0 ? 1 : 0) | (e->ins ? 2 : 0) | e->nfx << 2);
                    if (e->note >= 0) tmp[len++] = (uint8_t)e->note;
                    if (e->ins) tmp[len++] = (uint8_t)e->ins;
                    for (int f = 0; f < e->nfx; ++f) {
                        tmp[len++] = (uint8_t)(e->fx[f] >> 8);
                        tmp[len++] = (uint8_t)(e->fx[f] & 0xFF);
                    }
                    ++r;
                }
                at = b_bytes(s, tmp, len);
                if (!used) empty_at[rows] = at;
            }
            put16(s->b + OPN_OFF_ORDERS + 2 * (16 * o + c), at);
        }
    }
    put16(s->b + OPN_H_DATA_SIZE, s->data);
    if (!s->ok) {
        printf("  image writer: the data region is full\n");
        return -1;
    }
    return opn_load_image(s->im, s->chip);
}

/* ---- the images -------------------------------------------------------------------------------------------------- */

static int build_min(OpnImage* im, int chip) {
    B s;
    b_init(&s, im, chip, 1, 1, 6);
    return b_finish(&s, NULL, 0);
}

static int g_full_vol_env; /* data offset of build_full's SSG volume envelope */

/* Two orders of 32 rows at speed 3 with one sound of each part, a few effects of every kind the chip takes and
 * `extra` events on top. */
static int build_full(OpnImage* im, int chip, const Ev* extra, int n_extra) {
    B s;
    b_init(&s, im, chip, 2, 32, 3);
    ins_fm_sine(&s, 1, 0);
    static const int8_t vol[4] = {15, 13, 11, 9}, mix[1] = {OPN_MIX_TONE};
    g_full_vol_env = b_env(&s, vol, 4, NONE, NONE);
    const int me = b_env(&s, mix, 1, NONE, NONE);
    ins_ssg(&s, 2, 0, 0, 0, 0, g_full_vol_env, me, NOENV);
    const int ssg = opn_ssg_first(chip);
    Ev ev[32];
    int n = 0;
    const Ev base[] = {
        {0, 0, 0, 57, 1, 1, {FX(OPN_FX_VOLUME, 0x70)}},
        {0, 0, ssg, 57, 2, 0, {0}},
        {0, 4, 1, 60, 1, 1, {FX(OPN_FX_VIBRATO, 0x46)}},
        {0, 8, 0, OFF, 0, 0, {0}},
        {0, 8, ssg + 1, 64, 2, 2, {FX(OPN_FX_NOISE, 0x05), FX(OPN_FX_ENV_SHAPE, 0x0E)}},
        {1, 0, 2, 48, 1, 1, {FX(OPN_FX_SPEED, 4)}},
        {1, 31, 0, NONE, 0, 1, {FX(OPN_FX_JUMP, 0)}},
    };
    for (size_t i = 0; i < sizeof base / sizeof base[0]; ++i) ev[n++] = base[i];
    if (chip == OPN_CHIP_YM2608) {
        ins_rhythm(&s, 3, 31, 0xC0);
        b_sample(&s, 0, g_sine_adpcm, SINE_BYTES, 0, SINE_RATE);
        ins_adpcm(&s, 4, 255, 0, 57, 0, 0xC0);
        const Ev opna[] = {
            {0, 0, OPNA_CH_ADPCM, 57, 4, 1, {FX(OPN_FX_PAN, 0x11)}},
            {0, 4, OPNA_CH_RHYTHM, 57, 3, 0, {0}},
            {0, 12, 5, NONE, 0, 1, {FX(OPN_FX_LFO, 0x08)}},
            {1, 0, OPNA_CH_RHYTHM, NONE, 0, 1, {FX(OPN_FX_RHYTHM_LEVEL, 0x20)}},
            {1, 4, OPNA_CH_ADPCM, OFF, 0, 0, {0}},
        };
        for (size_t i = 0; i < sizeof opna / sizeof opna[0]; ++i) ev[n++] = opna[i];
    }
    for (int i = 0; i < n_extra; ++i) ev[n++] = extra[i];
    return b_finish(&s, ev, n);
}

/* One held note from (0, 0) on `ch`: one order of 256 rows at speed 255 (no row change within a test). */
static int build_tone(OpnImage* im, int chip, const char* part, int ch, int note) {
    B s;
    b_init(&s, im, chip, 1, 0, 255);
    ins_fm_sine(&s, 1, 0);
    ins_ssg(&s, 2, 0, 0, 0, 0, NOENV, NOENV, NOENV);
    if (chip == OPN_CHIP_YM2608) {
        b_sample(&s, 0, g_sine_adpcm, SINE_BYTES, 0, SINE_RATE);
        ins_adpcm(&s, 3, 255, 0, 57, OPN_ADPCM_LOOP, 0xC0);
    }
    const int slot = !strcmp(part, "ssg") ? 2 : !strcmp(part, "adpcm") ? 3 : 1;
    const Ev e = {0, 0, ch, note, slot, 0, {0}};
    return b_finish(&s, &e, 1);
}

/* The test song (SONG_*): FM brass bass and sine melody (vibrato, slide), SSG lead with a volume envelope and Cxx, an
 * SSG noise drum (mixer, noise and volume envelopes), an SSG hardware-envelope bass; on the YM2608 also FM 4 (port 1)
 * with 8xy pans, the LFO and an ADPCM-B chirp from the start (10 s, longer than the song) then a looping sine. `pad`
 * unreferenced bytes first (the moved copy), events of channel `drop` left out (-1: none). */
static int build_song(OpnImage* im, int chip, int pad, int drop) {
    B s;
    b_init(&s, im, chip, SONG_ORDERS, SONG_ROWS, SONG_SPEED);
    put16(s.b + OPN_H_LOOP, 1);
    if (chip == OPN_CHIP_YM2608) s.b[OPN_H_LFO] = 0x0A;
    if (pad) b_pad(&s, pad);
    ins_fm_sine(&s, 1, 0);
    ins_fm_brass(&s, 2);
    static const int8_t lead[8] = {15, 14, 13, 12, 11, 10, 9, 8}, dvol[5] = {15, 10, 6, 3, 0}, dmix[1] = {OPN_MIX_NOISE},
                        dnoise[4] = {2, 4, 8, 16};
    ins_ssg(&s, 3, 0, 0, 0, 0, b_env(&s, lead, 8, NONE, NONE), NOENV, NOENV);
    const int v = b_env(&s, dvol, 5, NONE, NONE), mx = b_env(&s, dmix, 1, NONE, NONE), nz = b_env(&s, dnoise, 4, NONE, NONE);
    ins_ssg(&s, 4, 0, 0, 0, 0, v, mx, nz);
    ins_ssg(&s, 7, 0x08, 1, 4, 0, NOENV, NOENV, NOENV);
    if (chip == OPN_CHIP_YM2608) {
        b_sample(&s, 0, g_sine_adpcm, SINE_BYTES, 0, SINE_RATE);
        b_sample(&s, 1, g_chirp_adpcm, CHIRP_BYTES, SINE_BYTES, SINE_RATE);
        ins_adpcm(&s, 5, 255, 1, 57, 0, 0xC0);
        ins_adpcm(&s, 6, 200, 0, 57, OPN_ADPCM_LOOP, 0xC0);
    }
    static Ev ev[512];
    int n = 0;
    static const int bass[8] = {0, 0, 7, 5, 3, 3, 10, 7};
    const int ssg = opn_ssg_first(chip);
    for (int o = 0; o < SONG_ORDERS; ++o)
        for (int r = 0; r < SONG_ROWS; ++r) {
            Ev e[6];
            int k = 0;
            if (r % 4 == 0) e[k++] = (Ev){o, r, 0, 33 + bass[(o * 8 + r / 4) % 8], 2, 0, {0}};
            if (r == 14) e[k++] = (Ev){o, r, 0, OFF, 0, 0, {0}}; /* FM 1's release tail over rows 14-15 */
            if (r % 8 == 0) {
                e[k] = (Ev){o, r, 1, 57 + (o + r / 8) * 2 % 12, 1, 0, {0}};
                if (r == 16) e[k].nfx = 1, e[k].fx[0] = FX(OPN_FX_VIBRATO, 0x46);
                if (o == 1 && r == 8) e[k].nfx = 1, e[k].fx[0] = FX(OPN_FX_SLIDE_UP, 0x08);
                ++k;
            }
            if (r % 2 == 0) {
                e[k] = (Ev){o, r, ssg, 69 - (r / 2) % 5 * 2, 3, 0, {0}};
                if (r % 8 == 4) e[k].nfx = 1, e[k].fx[0] = FX(OPN_FX_VOLUME, 0x0A);
                ++k;
            }
            if (r % 8 == 4) e[k++] = (Ev){o, r, ssg + 1, 40, 4, 0, {0}};
            if (r == 24) e[k++] = (Ev){o, r, ssg + 2, 33, 7, 0, {0}};
            if (r == 28) e[k++] = (Ev){o, r, ssg + 2, OFF, 0, 0, {0}};
            for (int i = 0; i < k; ++i)
                if (e[i].ch != drop) ev[n++] = e[i];
            if (chip != OPN_CHIP_YM2608) continue;
            Ev x[3];
            int j = 0;
            if (r % 8 == 2) x[j++] = (Ev){o, r, 3, 64 + o, 1, 1, {FX(OPN_FX_PAN, r % 16 == 2 ? 0x10 : 0x01)}};
            if (o == 0 && r == 0) x[j++] = (Ev){o, r, OPNA_CH_ADPCM, 57, 5, 0, {0}};
            if (o == 2 && r == 0) x[j++] = (Ev){o, r, OPNA_CH_ADPCM, 64, 6, 1, {FX(OPN_FX_VOLUME, 0xC0)}};
            if (o == 3 && r == 16) x[j++] = (Ev){o, r, OPNA_CH_ADPCM, OFF, 0, 0, {0}};
            for (int i = 0; i < j; ++i)
                if (x[i].ch != drop) ev[n++] = x[i];
        }
    return b_finish(&s, ev, n);
}

/* ---- straight-play reference --------------------------------------------------------------------------------------- */

typedef struct RefTick {
    int o, r, t;         /* position before the tick */
    int writes, offs, frames;
    uint64_t hash;       /* of the tick's float frames */
    OpnWrite w[OPNP_MAX_WRITES];
} RefTick;

static RefTick* record(int chip, const OpnImage* im, int ticks, double* pk) {
    RefTick* ref = (RefTick*)calloc((size_t)ticks, sizeof(RefTick));
    OpnMachine* m = opnm_create(chip, RATE);
    int mode;
    *pk = 0;
    if (!ref || !m || opnm_start(m, im, 0, 0, SEEK_BUDGET, &mode) != OPNM_OK) {
        if (m) opnm_destroy(m);
        return ref;
    }
    const OpnPlayer* p = opnm_player(m);
    for (int k = 0; k < ticks; ++k) {
        RefTick* r = &ref[k];
        opnm_position(m, &r->o, &r->r, &r->t);
        r->frames = opnm_tick(m, g_out);
        r->writes = p->writes;
        r->offs = p->offs;
        memcpy(r->w, p->write, sizeof(OpnWrite) * (size_t)p->writes);
        r->hash = fnv(0xCBF29CE484222325ull, g_out, sizeof(float) * 2 * (size_t)(r->frames > 0 ? r->frames : 0));
        const double q = peak(g_out, r->frames);
        if (q > *pk) *pk = q;
    }
    opnm_destroy(m);
    return ref;
}

static int same_tick(const OpnPlayer* p, const RefTick* r) {
    return p->writes == r->writes && p->offs == r->offs && !memcmp(p->write, r->w, sizeof(OpnWrite) * (size_t)r->writes);
}

static int pos_is(const OpnMachine* m, const RefTick* r) {
    int o, w, t;
    opnm_position(m, &o, &w, &t);
    return o == r->o && w == r->r && t == r->t;
}

static int find_row(const RefTick* ref, int ticks, int order, int row) {
    for (int k = 0; k < ticks; ++k)
        if (ref[k].o == order && ref[k].r == row && ref[k].t == 0) return k;
    return -1;
}

/* ---- loader ---------------------------------------------------------------------------------------------------- */

static void expect_rejected(int chip, const char* what, const char* want) {
    const int r = opn_load_image(&g_bad, chip);
    const char* problem = g_bad.info.problem;
    EXPECT(r == -1 && g_bad.info.loaded != OPN_LOADED && problem && strstr(problem, want) &&
               g_bad.info.problem_offset >= -1 && g_bad.info.problem_offset < (int)opn_image_size(chip),
           "%s %s: rejected with \"%s\" (got %s \"%s\" at %d)", chip_name(chip), what, want, r ? "rejected" : "ACCEPTED",
           problem ? problem : "", g_bad.info.problem_offset);
}

static void corrupt8(int chip, int offset, int value, const char* what, const char* want) {
    memcpy(&g_bad, &g_img, sizeof g_bad);
    g_bad.bytes[offset] = (uint8_t)value;
    expect_rejected(chip, what, want);
}

static void corrupt16(int chip, int offset, int value, const char* what, const char* want) {
    memcpy(&g_bad, &g_img, sizeof g_bad);
    put16(g_bad.bytes + offset, value);
    expect_rejected(chip, what, want);
}

static void corrupt32(int chip, int offset, uint32_t value, const char* what, const char* want) {
    memcpy(&g_bad, &g_img, sizeof g_bad);
    put32(g_bad.bytes + offset, value);
    expect_rejected(chip, what, want);
}

/* Order 0 / channel 0 replaced by a pattern of `n` raw bytes appended to the data region (an accepted control when
 * `want` is NULL). */
static void corrupt_pattern(int chip, const uint8_t* bytes, int n, const char* what, const char* want) {
    memcpy(&g_bad, &g_img, sizeof g_bad);
    const int size = opn_u16(g_bad.bytes + OPN_H_DATA_SIZE);
    memcpy(g_bad.bytes + OPN_OFF_DATA + size, bytes, (size_t)n);
    put16(g_bad.bytes + OPN_H_DATA_SIZE, size + n);
    put16(g_bad.bytes + OPN_OFF_ORDERS, size);
    if (want) expect_rejected(chip, what, want);
    else EXPECT(opn_load_image(&g_bad, chip) == 0, "%s %s: accepted (%s)", chip_name(chip), what, g_bad.info.problem);
}

/* One event on top of the full image: rejected with `want`. */
static void corrupt_event(int chip, Ev e, const char* what, const char* want) {
    const int r = build_full(&g_bad, chip, &e, 1);
    const char* problem = g_bad.info.problem;
    EXPECT(r == -1 && problem && strstr(problem, want), "%s %s: rejected with \"%s\" (got \"%s\")", chip_name(chip), what,
           want, problem ? problem : "accepted");
}

static void test_images(void) {
    printf("image loader\n");
    for (int chip = 0; chip < 2; ++chip) {
        const int opna = chip == OPN_CHIP_YM2608;
        EXPECT(build_min(&g_img, chip) == 0, "%s minimal image loads (%s)", chip_name(chip), g_img.info.problem);
        const OpnInfo* in = &g_img.info;
        EXPECT(in->loaded == OPN_LOADED && in->chip == chip && in->channels == opn_channels(chip) && in->orders == 1 &&
                   in->loop == 0 && in->speed == 6 && in->timer_b == OPN_TIMER_B_DEFAULT && in->tick_samples == TICK_SAMPLES &&
                   in->rows[0] == 1 && in->samples == 0 && in->adpcm_size == 0 && in->fm3_mode == OPN_FM3_NORMAL &&
                   in->rhythm_level == (opna ? 0x3F : 0) && !in->uses_rhythm,
               "%s minimal image info (channels %d, tick %u samples, rows %d)", chip_name(chip), in->channels,
               in->tick_samples, in->rows[0]);
        EXPECT(build_full(&g_img, chip, NULL, 0) == 0, "%s full image loads (%s)", chip_name(chip), g_img.info.problem);
        EXPECT(in->instruments == (opna ? 4 : 2) && in->samples == (opna ? 1 : 0) && in->adpcm_size == (opna ? SINE_BYTES : 0) &&
                   in->rows[0] == 32 && in->rows[1] == 32 && in->start_speed[0] == 3 && in->uses_rhythm == opna,
               "%s full image info (instruments %d, samples %d, ADPCM %u bytes, uses rhythm %d)", chip_name(chip),
               in->instruments, in->samples, in->adpcm_size, in->uses_rhythm);
        const int ssg = OPN_OFF_INSTRUMENTS + OPN_INSTRUMENT_SIZE, fm = OPN_OFF_INSTRUMENTS, env = OPN_OFF_DATA + g_full_vol_env;
        const int data_size = opn_u16(g_img.bytes + OPN_H_DATA_SIZE);

        /* header */
        corrupt8(chip, OPN_H_MAGIC, 'X', "magic", "magic");
        corrupt8(chip, OPN_H_LAYOUT, OPN_LAYOUT + 1, "layout", "unknown layout");
        corrupt8(chip, OPN_H_CHIP, 7, "chip 7", "unknown chip");
        memcpy(&g_bad, &g_img, sizeof g_bad);
        expect_rejected(chip ^ 1, "image loaded for the other chip", "chip byte does not match the engine");
        corrupt8(chip, OPN_H_SPEED, 0, "speed 0", "speed must be 1..255");
        corrupt16(chip, OPN_H_ORDERS, 0, "0 orders", "order count must be 1..256");
        corrupt16(chip, OPN_H_ORDERS, 257, "257 orders", "order count must be 1..256");
        corrupt16(chip, OPN_H_LOOP, 2, "loop order 2 of 2", "loop order past the last order");
        corrupt8(chip, OPN_H_INSTRUMENTS, 65, "65 instruments", "instrument count above 64");
        corrupt8(chip, OPN_H_FM3_MODE, 3, "FM 3 mode 3", "FM 3 mode must be");
        corrupt16(chip, OPN_H_DATA_SIZE, OPN_DATA_CAPACITY + 1, "data size past the region", "data size above the region");
        corrupt16(chip, OPN_H_TIMER_A, 1024, "timer A 1024", "timer A above 1023");
        corrupt8(chip, OPN_H_TIMER_B, OPN_TIMER_B_MIN - 1, "timer B 182", "timer B outside 183..250");
        corrupt8(chip, OPN_H_TIMER_B, OPN_TIMER_B_MAX + 1, "timer B 251", "timer B outside 183..250");
        corrupt8(chip, 21, 1, "reserved header byte 21", "header reserved bytes 21..23");
        corrupt8(chip, 40, 1, "reserved header byte 40", "header reserved bytes 28..63");
        if (opna) {
            corrupt8(chip, OPN_H_LFO, 0x07, "LFO 07", "LFO byte must be 0 or 8..15");
            corrupt8(chip, OPN_H_LFO, 0x10, "LFO 10", "LFO byte must be 0 or 8..15");
            corrupt8(chip, OPN_H_RHYTHM_LEVEL, 64, "rhythm level 64", "rhythm level above 63");
            corrupt8(chip, OPN_H_SAMPLES, 65, "65 samples", "sample count above 64");
            corrupt32(chip, OPN_H_ADPCM_SIZE, SINE_BYTES - 1, "ADPCM-B size 2047", "ADPCM-B size not a multiple of 32");
            corrupt32(chip, OPN_H_ADPCM_SIZE, OPN_ADPCM_MEMORY + 32, "ADPCM-B size past 256 KiB", "ADPCM-B size above 256 KiB");
            memcpy(&g_bad, &g_img, sizeof g_bad);
            g_bad.bytes[OPN_H_LFO] = 0x0F;
            EXPECT(opn_load_image(&g_bad, chip) == 0, "YM2608 LFO 0F accepted (%s)", g_bad.info.problem);
        } else {
            corrupt8(chip, OPN_H_LFO, 0x08, "LFO 08", "LFO byte must be 0 on the YM2203");
            corrupt8(chip, OPN_H_RHYTHM_LEVEL, 1, "rhythm level 1", "rhythm level must be 0 on the YM2203");
            corrupt8(chip, OPN_H_SAMPLES, 1, "1 sample", "sample count must be 0 on the YM2203");
            corrupt32(chip, OPN_H_ADPCM_SIZE, 32, "ADPCM-B size 32", "ADPCM-B size must be 0 on the YM2203");
        }

        /* instruments and envelopes */
        corrupt8(chip, fm + OPN_I_ALG, 8, "FM algorithm 8", "FM algorithm above 7");
        corrupt8(chip, fm + OPN_I_FEEDBACK, 8, "FM feedback 8", "FM feedback above 7");
        corrupt16(chip, fm + OPN_I_CH3, 3073, "FM 3 operator offset 3073", "FM 3 operator pitch offset outside -3072..3072");
        corrupt8(chip, fm + OPN_I_OPS + 1, 0x80, "FM TL bit 7", "FM operator register with reserved bits set");
        corrupt8(chip, ssg + OPN_I_SSG_SHAPE, 0x07, "SSG envelope shape 07", "SSG envelope shape must be 0 or 8..15");
        corrupt8(chip, ssg + OPN_I_SSG_OCTAVE, 9, "SSG envelope octave 9", "SSG envelope octave outside -8..8");
        corrupt8(chip, env + OPN_ENV_HEADER + 1, 16, "volume envelope value 16", "volume envelope value outside 0..15");
        corrupt16(chip, env, 0, "envelope length 0", "envelope length outside 1..256");
        corrupt16(chip, env + 2, 4, "envelope loop 4 of 4", "envelope loop past its end");
        corrupt16(chip, ssg + OPN_I_ENV_VOLUME, data_size, "envelope at the data size", "envelope outside the data size");
        corrupt8(chip, OPN_OFF_INSTRUMENTS + OPN_INSTRUMENT_SIZE * 63, OPN_FLAG_DEFINED, "slot 64 past the count",
                 "instrument record past the instrument count");
        corrupt8(chip, fm + OPN_I_SWEEP_START, 97, "sweep start +97", "sweep start/end");
        if (opna) {
            const int rh = OPN_OFF_INSTRUMENTS + OPN_INSTRUMENT_SIZE * 2, ad = OPN_OFF_INSTRUMENTS + OPN_INSTRUMENT_SIZE * 3;
            corrupt8(chip, rh + OPN_I_RHYTHM_LEVEL, 32, "rhythm level 32", "rhythm level above 31");
            corrupt8(chip, rh + OPN_I_PAN, 0, "rhythm pan 0", "pan byte");
            corrupt8(chip, ad + OPN_I_ADPCM_SAMPLE, 1, "ADPCM sound of sample 1 of 1", "ADPCM sound names a sample past the sample table");
            corrupt8(chip, ad + OPN_I_ADPCM_ROOT, 96, "ADPCM root 96", "ADPCM root note above 95");
            corrupt8(chip, ad + OPN_I_ADPCM_FLAGS, 0x02, "ADPCM flags 02", "ADPCM flags: only bit 0");
            /* samples */
            corrupt32(chip, OPN_OFF_SAMPLES + OPN_S_START, 16, "sample start 16", "sample start not a multiple of 32");
            corrupt32(chip, OPN_OFF_SAMPLES + OPN_S_END, 2 * SINE_BYTES, "sample end past the size", "sample end past the ADPCM-B size");
            corrupt32(chip, OPN_OFF_SAMPLES + OPN_S_END, 0, "sample end 0", "sample end not after its start");
            corrupt32(chip, OPN_OFF_SAMPLES + OPN_S_RATE, 999, "sample rate 999", "sample rate outside 1000..55466 Hz");
            corrupt32(chip, OPN_OFF_SAMPLES + OPN_S_RATE, 55467, "sample rate 55467", "sample rate outside 1000..55466 Hz");
            corrupt8(chip, OPN_OFF_ADPCM + SINE_BYTES + 5, 1, "ADPCM-B memory past the used size", "ADPCM-B memory past the used size is not 0");
        } else {
            corrupt8(chip, fm + OPN_I_AMS_FMS, 0x11, "FM AMS/FMS", "FM AMS/FMS on the YM2203");
            /* a rhythm sound in slot 3 (counted by the header): the YM2203 has no rhythm part */
            memcpy(&g_bad, &g_img, sizeof g_bad);
            g_bad.bytes[OPN_OFF_INSTRUMENTS + OPN_INSTRUMENT_SIZE * 2] = OPN_FLAG_DEFINED | OPN_KIND_RHYTHM;
            g_bad.bytes[OPN_OFF_INSTRUMENTS + OPN_INSTRUMENT_SIZE * 2 + OPN_I_RHYTHM_LEVEL] = 31;
            g_bad.bytes[OPN_OFF_INSTRUMENTS + OPN_INSTRUMENT_SIZE * 2 + OPN_I_PAN] = 0xC0;
            g_bad.bytes[OPN_H_INSTRUMENTS] = 3;
            expect_rejected(chip, "a rhythm sound", "rhythm or ADPCM sound on the YM2203");
        }

        /* pattern rows */
        const uint8_t control[] = {0x01, 57, 0x80 | 30};
        corrupt_pattern(chip, control, sizeof control, "patched pattern control", NULL);
        const uint8_t run[] = {0x80 | 32};
        corrupt_pattern(chip, run, sizeof run, "33-row run in a 32-row pattern", "empty-row run past the pattern's last row");
        const uint8_t mask0[] = {0x00, 0x80 | 30}, mask14[] = {0x14, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x80 | 30};
        corrupt_pattern(chip, mask0, sizeof mask0, "mask 00", "row mask byte");
        corrupt_pattern(chip, mask14, sizeof mask14, "mask 14 (5 effects)", "row mask byte");
        const uint8_t note[] = {0x01, 0xFD, 0x80 | 30};
        corrupt_pattern(chip, note, sizeof note, "note FD", "note byte");
        corrupt16(chip, OPN_OFF_ORDERS + 2, data_size, "pattern at the data size", "pattern offset past the data size");
        corrupt_event(chip, (Ev){0, 12, 0, 96, 1, 0, {0}}, "note 96", "note byte");
        corrupt_event(chip, (Ev){0, 12, 0, 57, 2, 0, {0}}, "SSG sound on FM 1", "row names a sound of another part than the channel's");
        corrupt_event(chip, (Ev){0, 12, 0, 57, 9, 0, {0}}, "undefined slot 9", "row names an instrument the image does not define");
        corrupt_event(chip, (Ev){0, 12, opn_ssg_first(chip), 10, 2, 0, {0}}, "SSG A#0", "SSG note below B-0");
        corrupt_event(chip, (Ev){0, 12, 0, NONE, 0, 1, {FX(36, 0)}}, "effect code 36", "effect code above Z");
        corrupt_event(chip, (Ev){0, 12, 0, NONE, 0, 1, {FX(OPN_FX_ENV_SHAPE, 0x08)}}, "H08 on FM", "Hxx envelope shape on a non-SSG channel");
        corrupt_event(chip, (Ev){0, 12, 0, NONE, 0, 1, {FX(OPN_FX_VOLUME, 0x80)}}, "C80 on FM", "Cxx volume above 7F on an FM channel");
        corrupt_event(chip, (Ev){0, 12, opn_ssg_first(chip), NONE, 0, 1, {FX(OPN_FX_VOLUME, 0x10)}}, "C10 on SSG",
                      "Cxx volume above 0F on an SSG channel");
        corrupt_event(chip, (Ev){0, 12, 0, NONE, 0, 1, {FX(OPN_FX_JUMP, 2)}}, "B02 of 2 orders", "Bxx jumps past the last order");
        corrupt_event(chip, (Ev){0, 12, opn_ssg_first(chip), NONE, 0, 1, {FX(OPN_FX_NOISE, 0x20)}}, "N20", "Nxx noise period above 1F");
        if (opna) {
            corrupt_event(chip, (Ev){0, 12, OPNA_CH_RHYTHM, NONE, 0, 1, {FX(OPN_FX_FINE, 0x90)}}, "P90 on a drum", "pitch effect");
            corrupt_event(chip, (Ev){0, 12, 0, NONE, 0, 1, {FX(OPN_FX_LFO, 0x07)}}, "L07", "Lxx LFO must be 00 or 08..0F");
            corrupt_event(chip, (Ev){0, 12, 0, NONE, 0, 1, {FX(OPN_FX_RHYTHM_LEVEL, 0x40)}}, "R40", "Rxx rhythm level above 3F");
        } else {
            corrupt_event(chip, (Ev){0, 12, 0, NONE, 0, 1, {FX(OPN_FX_PAN, 0x11)}}, "811 on the YM2203", "8xy pan on the YM2203");
            corrupt_event(chip, (Ev){0, 12, 0, NONE, 0, 1, {FX(OPN_FX_LFO, 0x08)}}, "L08 on the YM2203", "Lxx LFO on the YM2203");
            corrupt_event(chip, (Ev){0, 12, 0, NONE, 0, 1, {FX(OPN_FX_RHYTHM_LEVEL, 0x20)}}, "R20 on the YM2203",
                          "Rxx rhythm level on the YM2203");
        }
        EXPECT(opn_load_image(&g_img, chip) == 0, "%s the untouched full image still loads", chip_name(chip));
    }
}

/* ---- pitch from rendered audio ---------------------------------------------------------------------------------- */

/* Renders `im` (a held tone from (0, 0)) through the hosts' ops table for TONE_TICKS ticks and writes <name>.wav,
 * <name>.vgm (the same image's VGM, TONE_TICKS ticks) and a manifest line. Returns the frames (caller frees). */
static int tone_evidence(const char* dir, const char* name, int chip, const char* part, int ch, int note,
                         const OpnImage* im, float** out) {
    const ChipEngineOps* ops = chip_engine_ops(engine_id(chip));
    float* buf = (float*)malloc(sizeof(float) * 2 * OPNM_MAX_TICK_FRAMES * TONE_TICKS);
    int frames = 0;
    *out = buf;
    EXPECT(ops != NULL, "%s: engine %s registered", name, engine_id(chip));
    if (!ops) return 0;
    ChipImage* ci = (ChipImage*)calloc(1, ops->image_storage);
    if (ops->load_image(ci, im->bytes, ops->image_size) == CHIP_OK) {
        ChipMachine* m = ops->create(RATE);
        int mode;
        if (m && ops->start(m, ci, 0, 0, SEEK_BUDGET, &mode) == CHIP_OK)
            for (int t = 0; t < TONE_TICKS; ++t) {
                const int n = ops->tick(m, buf + 2 * frames);
                if (n <= 0) break;
                frames += n;
            }
        if (m) ops->destroy(m);
    }
    free(ci);
    save_wav(dir, name, buf, frames);
    OpnVgmInfo info;
    const size_t size = opn_vgm_render(im, TONE_TICKS, g_vplayer, g_vgm, sizeof g_vgm, &info);
    EXPECT(size > 0, "%s: VGM renders", name);
    char file[256];
    snprintf(file, sizeof file, "%s.vgm", name);
    save(dir, file, g_vgm, size);
    if (g_manifest) fprintf(g_manifest, "tone %s %s %s %d %d\n", name, chip_name(chip), part, ch, note);
    return frames;
}

/* A machine started at (0, 0) after its first tick: the player's registers of the tone. */
static OpnMachine* first_tick(int chip, const OpnImage* im) {
    OpnMachine* m = opnm_create(chip, RATE);
    int mode;
    if (!m) return NULL;
    opnm_start(m, im, 0, 0, SEEK_BUDGET, &mode);
    opnm_tick(m, g_out);
    return m;
}

typedef struct Tone {
    int chip;
    const char* name;
    const char* part;
    int ch, note;
} Tone;

static void test_pitch(const char* dir) {
    printf("pitch (ops table, measured from the rendered audio)\n");
    static const Tone tones[] = {
        {OPN_CHIP_YM2203, "opn_fm_a4", "fm", 0, 57},      {OPN_CHIP_YM2203, "opn_fm_c2", "fm", 0, 24},
        {OPN_CHIP_YM2203, "opn_fm_c6", "fm", 1, 72},      {OPN_CHIP_YM2203, "opn_ssg_a4", "ssg", 3, 57},
        {OPN_CHIP_YM2203, "opn_ssg_b0", "ssg", 5, 11},    {OPN_CHIP_YM2608, "opna_fm_a4", "fm", 3, 57},
        {OPN_CHIP_YM2608, "opna_fm_c2", "fm", 0, 24},     {OPN_CHIP_YM2608, "opna_fm_c6", "fm", 5, 72},
        {OPN_CHIP_YM2608, "opna_ssg_a4", "ssg", 6, 57},   {OPN_CHIP_YM2608, "opna_ssg_b0", "ssg", 8, 11},
        {OPN_CHIP_YM2608, "opna_adpcm_root", "adpcm", 15, 57}, {OPN_CHIP_YM2608, "opna_adpcm_up12", "adpcm", 15, 69},
    };
    for (size_t i = 0; i < sizeof tones / sizeof tones[0]; ++i) {
        const Tone* t = &tones[i];
        EXPECT(build_tone(&g_img, t->chip, t->part, t->ch, t->note) == 0, "%s image (%s)", t->name, g_img.info.problem);
        float* buf;
        const int frames = tone_evidence(dir, t->name, t->chip, t->part, t->ch, t->note, &g_img, &buf);
        const double hz = zero_cross_hz(buf + 2 * (RATE / 10), frames - RATE / 10, 0);
        OpnMachine* m = first_tick(t->chip, &g_img);
        if (!m) {
            free(buf);
            continue;
        }
        const OpnPlayer* p = opnm_player(m);
        double expect = 0, tol = 0;
        if (!strcmp(t->part, "fm")) {
            int block, fnum, eb, ef;
            fm_regs(p, t->ch, &block, &fnum);
            fm_expect(t->note * 64, &eb, &ef);
            expect = fm_hz(block, fnum);
            tol = 0.001;
            EXPECT(block == eb && abs(fnum - ef) <= 1, "%s: block %d F-number %d (expected %d / %d)", t->name, block, fnum, eb, ef);
            const double cents = 1200.0 * log2(expect / et_hz(t->note));
            EXPECT(fabs(cents) < 3.0, "%s: the registers' pitch %.3f Hz is %+.2f cents from 12-TET", t->name, expect, cents);
        } else if (!strcmp(t->part, "ssg")) {
            const int tp = ssg_tp(p, t->ch - opn_ssg_first(t->chip)), want = (int)lround(SSG_TONE_CLOCK / et_hz(t->note));
            expect = SSG_TONE_CLOCK / tp;
            tol = 0.0015;
            EXPECT(tp == want, "%s: tone period %d (12-TET rounding %d)", t->name, tp, want);
            EXPECT((p->reg[0][7] >> (t->ch - opn_ssg_first(t->chip)) & 1) == 0, "%s: tone enabled in $07 (%02X)", t->name, p->reg[0][7]);
        } else {
            const int dn = adpcm_dn(p), want = expect_dn(SINE_RATE, t->note, 57);
            expect = SINE_HZ * dn * NATIVE_HZ / 65536.0 / SINE_RATE;
            tol = 0.003;
            EXPECT(abs(dn - want) <= 1, "%s: delta-N %d (expected %d)", t->name, dn, want);
        }
        EXPECT(expect > 0 && fabs(hz / expect - 1.0) < tol, "%s: measured %.3f Hz, the registers say %.3f Hz (tolerance %.2f%%)",
               t->name, hz, expect, tol * 100);
        printf("  %-16s %9.3f Hz measured, %9.3f Hz from the registers, 12-TET %9.3f Hz\n", t->name, hz, expect,
               !strcmp(t->part, "adpcm") ? SINE_HZ * pow(2.0, (t->note - 57) / 12.0) : et_hz(t->note));
        opnm_destroy(m);
        free(buf);
    }
}

/* ---- FM 3 special and CSM modes --------------------------------------------------------------------------------- */

static void test_fm3(const char* dir) {
    printf("FM 3 special and CSM modes\n");
    static const int off[4] = {64, 768, -768, 768};
    static const uint8_t lo[4] = {0xA9, 0xAA, 0xA8, 0xA2}, hi[4] = {0xAD, 0xAE, 0xAC, 0xA6};
    for (int chip = 0; chip < 2; ++chip) {
        /* special: operator k plays A-4 + off[k]; only operator 4 (+1 octave) is audible */
        B s;
        b_init(&s, &g_img, chip, 1, 0, 255);
        s.b[OPN_H_FM3_MODE] = OPN_FM3_SPECIAL;
        uint8_t* r = ins_fm_sine(&s, 1, 3);
        for (int k = 0; k < 4; ++k) put16(r + OPN_I_CH3 + 2 * k, (uint16_t)(int16_t)off[k]);
        const Ev e = {0, 0, OPN_CH_FM3, A4, 1, 0, {0}};
        EXPECT(b_finish(&s, &e, 1) == 0, "%s special-mode image (%s)", chip_name(chip), g_img.info.problem);
        OpnMachine* m = first_tick(chip, &g_img);
        if (!m) continue;
        const OpnPlayer* p = opnm_player(m);
        EXPECT((p->reg[0][0x27] & 0xC0) == 0x40, "%s special mode: $27 = %02X", chip_name(chip), p->reg[0][0x27]);
        for (int k = 0; k < 4; ++k) {
            int eb, ef;
            fm_expect(A4 * 64 + off[k], &eb, &ef);
            const int h = p->reg[0][hi[k]], fnum = (h & 7) << 8 | p->reg[0][lo[k]], block = h >> 3 & 7;
            const int ih = find_write(p, 0, p->writes, 0, hi[k], -1), il = find_write(p, 0, p->writes, 0, lo[k], -1);
            EXPECT(block == eb && abs(fnum - ef) <= 1, "%s operator %d: block %d F-number %d (expected %d / %d)", chip_name(chip),
                   k + 1, block, fnum, eb, ef);
            EXPECT(ih >= 0 && il > ih, "%s operator %d: $%02X written before $%02X (%d, %d)", chip_name(chip), k + 1, hi[k], lo[k], ih, il);
        }
        int b4, f4;
        b4 = p->reg[0][0xA6] >> 3 & 7;
        f4 = (p->reg[0][0xA6] & 7) << 8 | p->reg[0][0xA2];
        opnm_destroy(m);
        char name[64];
        snprintf(name, sizeof name, "%s_fm3_special", prefix(chip));
        float* buf;
        const int frames = tone_evidence(dir, name, chip, "fm3op4", OPN_CH_FM3, A4, &g_img, &buf);
        const double hz = zero_cross_hz(buf + 2 * (RATE / 10), frames - RATE / 10, 0), want = fm_hz(b4, f4);
        EXPECT(fabs(hz / want - 1.0) < 0.001 && fabs(want / 880.0 - 1.0) < 0.002,
               "%s special mode: operator 4 alone heard at %.3f Hz (its registers %.3f Hz, A-5 880)", chip_name(chip), hz, want);
        printf("  %s special mode: operator 4 at %.3f Hz\n", chip_name(chip), hz);
        free(buf);

        /* CSM: NA = 624, timer A every 400 native samples while the FM 3 note sounds */
        b_init(&s, &g_img, chip, 1, 32, 6);
        s.b[OPN_H_FM3_MODE] = OPN_FM3_CSM;
        put16(s.b + OPN_H_TIMER_A, 624);
        ins_fm_sine(&s, 1, 0);
        const Ev csm[] = {{0, 0, OPN_CH_FM3, A4, 1, 0, {0}}, {0, 8, OPN_CH_FM3, OFF, 0, 0, {0}}};
        EXPECT(b_finish(&s, csm, 2) == 0, "%s CSM image (%s)", chip_name(chip), g_img.info.problem);
        m = opnm_create(chip, RATE);
        int mode;
        opnm_start(m, &g_img, 0, 0, SEEK_BUDGET, &mode);
        p = opnm_player(m);
        const OpnChip* c = opnm_chip(m);
        EXPECT((p->reg[0][0x27] & 0xC0) == 0x40 && p->reg[0][0x24] == 624 >> 2 && p->reg[0][0x25] == (624 & 3),
               "%s CSM before the note: $27 = %02X, timer A $24/$25 = %02X/%02X", chip_name(chip), p->reg[0][0x27],
               p->reg[0][0x24], p->reg[0][0x25]);
        const uint32_t e0 = opn_chip_timer_a_expiries(c);
        float* note = (float*)malloc(sizeof(float) * 2 * OPNM_MAX_TICK_FRAMES * 48);
        int frames_note = 0, native = 0, keyons = 0;
        for (int t = 0; t < 48; ++t) {
            frames_note += opnm_tick(m, note + 2 * frames_note);
            native += opnm_native_samples(m);
            for (int i = 0; i < p->writes; ++i)
                if (p->write[i].port == 0 && p->write[i].reg == 0x28 && (p->write[i].val & 7) == 2 && (p->write[i].val & 0xF0)) ++keyons;
            if (t == 0) EXPECT(p->reg[0][0x27] == 0x81, "%s CSM note: $27 = %02X", chip_name(chip), p->reg[0][0x27]);
        }
        const uint32_t e1 = opn_chip_timer_a_expiries(c);
        EXPECT(e0 == 0 && fabs((double)(e1 - e0) - native / 400.0) <= 2.0,
               "%s CSM: %u timer A expiries in %d native samples (expected %.1f, none before the note: %u)", chip_name(chip),
               e1 - e0, native, native / 400.0, e0);
        EXPECT(keyons == 0, "%s CSM: no $28 key-on of FM 3 (%d)", chip_name(chip), keyons);
        EXPECT(rms(note + 2 * (RATE / 20), frames_note - RATE / 20) > 0.002, "%s CSM note audible (rms %.4f)", chip_name(chip),
               rms(note + 2 * (RATE / 20), frames_note - RATE / 20));
        snprintf(name, sizeof name, "%s_csm", prefix(chip));
        save_wav(dir, name, note, frames_note);
        free(note);
        opnm_tick(m, g_out); /* the OFF row */
        EXPECT(p->reg[0][0x27] == 0x40, "%s CSM after OFF: $27 = %02X", chip_name(chip), p->reg[0][0x27]);
        const uint32_t e2 = opn_chip_timer_a_expiries(c);
        for (int t = 0; t < 20; ++t) opnm_tick(m, g_out);
        EXPECT(opn_chip_timer_a_expiries(c) == e2, "%s CSM: no expiries after OFF (%u -> %u)", chip_name(chip), e2,
               opn_chip_timer_a_expiries(c));
        printf("  %s CSM: %u expiries in %d samples (%.2f per 400)\n", chip_name(chip), e1 - e0, native, (e1 - e0) * 400.0 / native);
        opnm_destroy(m);
    }
}

/* ---- SSG shared registers ------------------------------------------------------------------------------------------ */

static void test_ssg(void) {
    printf("SSG mixer, noise and hardware envelope\n");
    for (int chip = 0; chip < 2; ++chip) {
        const int f = opn_ssg_first(chip);
        B s;
        b_init(&s, &g_img, chip, 1, 32, 2);
        static const int8_t mixn[1] = {OPN_MIX_NOISE}, mixb[1] = {OPN_MIX_BOTH}, n5[1] = {5}, n9[1] = {9};
        const int en = b_env(&s, mixn, 1, NONE, NONE), eb = b_env(&s, mixb, 1, NONE, NONE);
        const int e5 = b_env(&s, n5, 1, NONE, NONE), e9 = b_env(&s, n9, 1, NONE, NONE);
        ins_ssg(&s, 1, 0, 0, 0, 0, NOENV, NOENV, NOENV);          /* tone */
        ins_ssg(&s, 2, 0, 0, 0, 0, NOENV, en, e5);                /* noise only, period 5 */
        ins_ssg(&s, 3, 0x0E, 1, 0, 0, NOENV, NOENV, NOENV);       /* hardware envelope, period = tone period */
        ins_ssg(&s, 4, 0x08, 0, 0, 0x1234, NOENV, NOENV, NOENV);  /* hardware envelope, manual period */
        ins_ssg(&s, 5, 0, 0, 0, 0, NOENV, eb, e9);                /* tone + noise, period 9 */
        const Ev ev[] = {
            {0, 0, f, 57, 1, 0, {0}},      {0, 0, f + 1, 57, 2, 0, {0}}, {0, 0, f + 2, 45, 3, 0, {0}},
            {0, 4, f + 2, 45, 5, 0, {0}},
            {0, 8, f, 57, 4, 0, {0}},      {0, 8, f + 2, 45, 3, 0, {0}},
            {0, 12, f + 2, 45, 1, 0, {0}},
            {0, 16, f, 57, 1, 1, {FX(OPN_FX_VOLUME, 0x08)}},
            {0, 20, f + 1, 57, 1, 1, {FX(OPN_FX_NOISE, 0x11)}},
        };
        EXPECT(b_finish(&s, ev, (int)(sizeof ev / sizeof ev[0])) == 0, "%s SSG image (%s)", chip_name(chip), g_img.info.problem);
        OpnMachine* m = opnm_create(chip, RATE);
        int mode;
        opnm_start(m, &g_img, 0, 0, SEEK_BUDGET, &mode);
        const OpnPlayer* p = opnm_player(m);
        const char* n = chip_name(chip);
        const uint8_t* R = p->reg[0];
        tick_row(m, 0, 0);
        EXPECT(R[7] == 0x2A, "%s row 0: mixer $07 = %02X (SSG 1 tone, SSG 2 noise, SSG 3 tone: 2A)", n, R[7]);
        EXPECT(R[6] == 5, "%s row 0: noise period %d from SSG 2's envelope (5)", n, R[6]);
        EXPECT(R[8] == 15 && R[9] == 15 && R[10] == 0x10, "%s row 0: levels %02X %02X %02X (0F 0F 10)", n, R[8], R[9], R[10]);
        EXPECT(find_write(p, 0, p->writes, 0, 0x0D, 0x0E) >= 0, "%s row 0: the key-on writes the shape $0D = 0E", n);
        EXPECT(ssg_tp(p, 2) == (int)lround(SSG_TONE_CLOCK / et_hz(45)) && ssg_env_period(p) == ssg_tp(p, 2),
               "%s row 0: auto envelope period %d = SSG 3's tone period %d", n, ssg_env_period(p), ssg_tp(p, 2));
        tick_row(m, 0, 4);
        EXPECT(R[6] == 9, "%s row 4: noise period %d from SSG 3, the highest channel with a noise envelope (9)", n, R[6]);
        EXPECT(R[7] == 0x0A, "%s row 4: mixer $07 = %02X (SSG 3 tone + noise: 0A)", n, R[7]);
        EXPECT(R[10] == 15, "%s row 4: SSG 3 level %02X without a hardware envelope", n, R[10]);
        tick_row(m, 0, 8);
        EXPECT(R[8] == 0x10 && R[10] == 0x10, "%s row 8: SSG 1 and 3 on the hardware envelope (%02X %02X)", n, R[8], R[10]);
        EXPECT(ssg_env_period(p) == ssg_tp(p, 2), "%s row 8: SSG 3 (auto, highest) owns the period: %04X", n, ssg_env_period(p));
        EXPECT(find_write(p, 0, p->writes, 0, 0x0D, -1) >= 0, "%s row 8: shape written at the key-ons", n);
        tick_row(m, 0, 12);
        EXPECT(ssg_env_period(p) == 0x1234, "%s row 12: SSG 1's manual period %04X once SSG 3 has none", n, ssg_env_period(p));
        EXPECT(R[6] == 5 && R[10] == 15, "%s row 12: noise %d (SSG 2 again), SSG 3 level %02X", n, R[6], R[10]);
        tick_row(m, 0, 16);
        EXPECT(R[8] == 8, "%s row 16: C08 -> level %d ((15 x 8 + 7) / 15 = 8)", n, R[8]);
        tick_row(m, 0, 20);
        EXPECT(R[6] == 0x11 && R[7] == 0x38, "%s row 20: N11 without noise envelopes: $06 = %02X, all tone $07 = %02X", n, R[6], R[7]);
        opnm_destroy(m);
    }
}

/* ---- YM2608 rhythm ------------------------------------------------------------------------------------------------ */

static int build_rhythm(OpnImage* im) {
    B s;
    b_init(&s, im, OPN_CHIP_YM2608, 1, 32, 3);
    ins_rhythm(&s, 1, 31, 0xC0);
    const int d = OPNA_CH_RHYTHM;
    const Ev ev[] = {
        {0, 0, d, 57, 1, 0, {0}},         {0, 0, d + 1, 57, 1, 1, {FX(OPN_FX_VOLUME, 0x10)}},
        {0, 8, d, OFF, 0, 0, {0}},        {0, 16, d + 2, 57, 1, 1, {FX(OPN_FX_RHYTHM_LEVEL, 0x20)}},
        {0, 16, d + 3, 57, 1, 0, {0}},    {0, 16, d + 4, 57, 1, 0, {0}},
        {0, 16, d + 5, 57, 1, 0, {0}},
    };
    return b_finish(&s, ev, (int)(sizeof ev / sizeof ev[0]));
}

static void test_rhythm_without_rom(void) {
    printf("YM2608 rhythm: writes, and silence without the ROM\n");
    EXPECT(!ym2608_rhythm_rom_installed(), "no rhythm ROM installed yet");
    EXPECT(build_rhythm(&g_rhythm) == 0, "rhythm image (%s)", g_rhythm.info.problem);
    EXPECT(g_rhythm.info.uses_rhythm == 1, "the loader sees the rhythm notes");
    OpnMachine* m = opnm_create(OPN_CHIP_YM2608, RATE);
    int mode;
    opnm_start(m, &g_rhythm, 0, 0, SEEK_BUDGET, &mode);
    const OpnPlayer* p = opnm_player(m);
    EXPECT(p->reg[0][0x11] == 0x3F, "rhythm total level $11 = %02X from the header", p->reg[0][0x11]);
    float* buf = (float*)malloc(sizeof(float) * 2 * OPNM_MAX_TICK_FRAMES * 64);
    int frames = opnm_tick(m, buf);
    const int i18 = find_write(p, p->offs, p->writes, 0, 0x18, 0xDF), k0 = find_write(p, p->offs, p->writes, 0, 0x10, 0x01);
    const int i19 = find_write(p, p->offs, p->writes, 0, 0x19, 0xC0 | 16), k1 = find_write(p, p->offs, p->writes, 0, 0x10, 0x02);
    EXPECT(i18 >= 0 && k0 > i18, "bass drum: $18 = DF then its own $10 = 01 (%d, %d)", i18, k0);
    EXPECT(i19 >= 0 && k1 > i19 && k1 != k0, "snare C10: $19 = C0 | 16 then $10 = 02 (%d, %d)", i19, k1);
    for (int t = 1; t < 24; ++t) frames += opnm_tick(m, buf + 2 * frames);
    frames += opnm_tick(m, buf + 2 * frames); /* row 8 */
    EXPECT(find_write(p, 0, p->offs, 0, 0x10, 0x81) >= 0, "OFF: the dump $10 = 81 in the key-off phase");
    for (int t = 25; t < 48; ++t) frames += opnm_tick(m, buf + 2 * frames);
    frames += opnm_tick(m, buf + 2 * frames); /* row 16 */
    EXPECT(p->reg[0][0x11] == 0x20, "R20: $11 = %02X", p->reg[0][0x11]);
    int keys = 0;
    for (int d = 2; d < 6; ++d) keys += find_write(p, p->offs, p->writes, 0, 0x10, 1 << d) >= 0;
    EXPECT(keys == 4, "four drums keyed in one tick: four $10 writes (%d)", keys);
    for (int t = 49; t < 64; ++t) frames += opnm_tick(m, buf + 2 * frames);
    EXPECT(peak(buf, frames) < 1e-4, "no ROM: silence (peak %.6f)", peak(buf, frames));
    EXPECT(opn_chip_rhythm_missing(opnm_chip(m)) == 1, "no ROM: the chip flags the missing ROM");
    free(buf);
    opnm_destroy(m);
}

/* ---- SHA-256 (FIPS 180-4), for the rhythm ROM check ------------------------------------------------------------- */

static uint32_t ror(uint32_t x, int n) { return x >> n | x << (32 - n); }

static void sha256_hex(const uint8_t* data, size_t len, char out[65]) {
    static const uint32_t k[64] = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01,
        0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
        0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08,
        0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};
    uint32_t h[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    const size_t total = ((len + 9 + 63) / 64) * 64;
    for (size_t off = 0; off < total; off += 64) {
        uint8_t block[64];
        for (int i = 0; i < 64; ++i) {
            const size_t q = off + (size_t)i;
            block[i] = q < len ? data[q] : q == len ? 0x80 : 0;
        }
        if (off + 64 == total) {
            const uint64_t bits = (uint64_t)len * 8;
            for (int i = 0; i < 8; ++i) block[56 + i] = (uint8_t)(bits >> (56 - 8 * i));
        }
        uint32_t w[64];
        for (int i = 0; i < 16; ++i) w[i] = (uint32_t)block[4 * i] << 24 | block[4 * i + 1] << 16 | block[4 * i + 2] << 8 | block[4 * i + 3];
        for (int i = 16; i < 64; ++i) {
            const uint32_t s0 = ror(w[i - 15], 7) ^ ror(w[i - 15], 18) ^ (w[i - 15] >> 3);
            const uint32_t s1 = ror(w[i - 2], 17) ^ ror(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
        for (int i = 0; i < 64; ++i) {
            const uint32_t t1 = hh + (ror(e, 6) ^ ror(e, 11) ^ ror(e, 25)) + ((e & f) ^ (~e & g)) + k[i] + w[i];
            const uint32_t t2 = (ror(a, 2) ^ ror(a, 13) ^ ror(a, 22)) + ((a & b) ^ (a & c) ^ (b & c));
            hh = g, g = f, f = e, e = d + t1, d = c, c = b, b = a, a = t1 + t2;
        }
        h[0] += a, h[1] += b, h[2] += c, h[3] += d, h[4] += e, h[5] += f, h[6] += g, h[7] += hh;
    }
    for (int i = 0; i < 8; ++i) snprintf(out + 8 * i, 9, "%08x", h[i]);
}

static int contains(const uint8_t* hay, size_t n, const uint8_t* needle, size_t m) {
    for (size_t i = 0; i + m <= n; ++i)
        if (hay[i] == needle[0] && !memcmp(hay + i, needle, m)) return 1;
    return 0;
}

/* The user's ROM from THOR_RHYTHM_ROM (never part of the repository): drums with sound, audition, no ROM in the VGM.
 * Runs last: the install is for the whole process. */
static void test_rhythm_with_rom(const char* dir) {
    const char* path = getenv("THOR_RHYTHM_ROM");
    if (!path || !path[0]) {
        printf("OPNA rhythm ROM checks skipped (THOR_RHYTHM_ROM unset; the ROM is not part of Thor Tracker)\n");
        return;
    }
    printf("YM2608 rhythm with the ROM from THOR_RHYTHM_ROM\n");
    static uint8_t rom[YM2608_RHYTHM_ROM_SIZE + 1];
    FILE* f = fopen(path, "rb");
    EXPECT(f != NULL, "%s opens", path);
    if (!f) return;
    const size_t size = fread(rom, 1, sizeof rom, f);
    fclose(f);
    char hex[65];
    sha256_hex(rom, size, hex);
    EXPECT(size == YM2608_RHYTHM_ROM_SIZE && !strcmp(hex, ROM_SHA256), "the YM2608 rhythm ROM: %zu bytes, SHA-256 %s", size, hex);
    if (size != YM2608_RHYTHM_ROM_SIZE || strcmp(hex, ROM_SHA256)) return;
    EXPECT(ym2608_rhythm_rom_install(rom, size) == 0 && ym2608_rhythm_rom_installed(), "installed");
    OpnMachine* m = opnm_create(OPN_CHIP_YM2608, RATE);
    int mode;
    opnm_start(m, &g_rhythm, 0, 0, SEEK_BUDGET, &mode);
    float* buf;
    const int frames = render(m, 64, &buf);
    EXPECT(peak(buf, frames) > 0.01, "drums audible (peak %.4f)", peak(buf, frames));
    EXPECT(!opn_chip_rhythm_missing(opnm_chip(m)), "nothing flagged missing");
    save_wav(dir, "opna_rhythm", buf, frames);
    free(buf);
    opnm_start(m, &g_rhythm, 0, 0, SEEK_BUDGET, &mode);
    opnm_tick(m, g_out);
    EXPECT(opn_chip_rhythm_playing(opnm_chip(m)) & 1, "the bass drum plays after its key-on");
    EXPECT(opnm_audition(m, &g_rhythm, OPNA_CH_RHYTHM + 1, A4, 1, 0) == OPNM_OK, "snare audition");
    opnm_tick(m, g_out);
    EXPECT(opnm_release(m) == 1, "drum release phase");
    int ticks = 0;
    while (opnm_tick(m, g_out) > 0 && ticks < 600) ++ticks;
    EXPECT(ticks < 180, "the released drum ends with its sample (%d ticks)", ticks);
    opnm_destroy(m);
    OpnVgmInfo info;
    const size_t n = opn_vgm_render(&g_rhythm, 600, g_vplayer, g_vgm, sizeof g_vgm, &info);
    int found = 0;
    for (size_t at = 0; at + 32 <= YM2608_RHYTHM_ROM_SIZE; at += 256) found += contains(g_vgm, n, rom + at, 32);
    EXPECT(n > 0 && !found, "no ROM bytes in the VGM (%d of 32 windows found)", found);
}

/* ---- YM2608 ADPCM-B ----------------------------------------------------------------------------------------------- */

static int build_adpcm(OpnImage* im, const Ev* ev, int n) {
    B s;
    b_init(&s, im, OPN_CHIP_YM2608, 1, 64, 4);
    b_sample(&s, 0, g_sine_adpcm, SINE_BYTES, 2048, SINE_RATE);
    ins_adpcm(&s, 1, 255, 0, A4, 0, 0xC0);
    ins_adpcm(&s, 2, 255, 0, A4, OPN_ADPCM_LOOP, 0xC0);
    ins_adpcm(&s, 3, 128, 0, A4, OPN_ADPCM_LOOP, 0x80);
    return b_finish(&s, ev, n);
}

static void test_adpcm(void) {
    printf("YM2608 ADPCM-B\n");
    const int ch = OPNA_CH_ADPCM;
    const Ev ev[] = {{0, 0, ch, A4, 1, 0, {0}}, {0, 32, ch, A4, 2, 0, {0}}, {0, 48, ch, OFF, 0, 0, {0}}};
    EXPECT(build_adpcm(&g_img, ev, 3) == 0, "ADPCM-B image (%s)", g_img.info.problem);
    OpnMachine* m = opnm_create(OPN_CHIP_YM2608, RATE);
    int mode;
    opnm_start(m, &g_img, 0, 0, SEEK_BUDGET, &mode);
    const OpnPlayer* p = opnm_player(m);
    const OpnChip* c = opnm_chip(m);
    opnm_tick(m, g_out);
    const int reset = find_write(p, 0, p->offs, 1, 0x00, 0x01), start = find_write(p, p->offs, p->writes, 1, 0x00, 0xA0);
    int last = -1;
    for (int i = p->offs; i < p->writes; ++i)
        if (p->write[i].port == 1 && p->write[i].reg >= 0x01 && p->write[i].reg <= 0x0B) last = i;
    EXPECT(reset >= 0 && start >= 0 && start > last, "key-on: $100 = 01 in the key-off phase (%d), $100 = A0 after the other "
           "ADPCM-B writes (%d > %d)", reset, start, last);
    const uint8_t* R = p->reg[1];
    const int dn = expect_dn(SINE_RATE, A4, A4);
    EXPECT(R[0x01] == 0xC2 && R[0x02] == 64 && R[0x03] == 0 && R[0x04] == 127 && R[0x05] == 0 && R[0x0B] == 255 &&
               abs(adpcm_dn(p) - dn) <= 1,
           "registers: $101 %02X (C2), start %02X%02X (0040), end %02X%02X (007F), delta-N %d (%d), level %d (255)", R[0x01],
           R[0x03], R[0x02], R[0x05], R[0x04], adpcm_dn(p), dn, R[0x0B]);
    EXPECT(opn_chip_adpcm_playing(c), "playing after the key-on");
    /* 4096 samples at 16000.4 Hz: 0.256 s = 15.3 ticks */
    int end = -1;
    for (int t = 2; t <= 40; ++t) {
        opnm_tick(m, g_out);
        if (end < 0 && !opn_chip_adpcm_playing(c)) end = t;
    }
    const double len = SINE_SAMPLES / (adpcm_dn(p) * NATIVE_HZ / 65536.0) * NATIVE_HZ / TICK_SAMPLES;
    EXPECT(end >= (int)floor(len) && end <= (int)ceil(len) + 1, "a non-looping sample ends after %d ticks (its length %.1f)", end, len);
    float* buf;
    int frames = render(m, 10, &buf);
    EXPECT(rms(buf, frames) < 1e-5, "and is silent after its end (rms %.7f)", rms(buf, frames));
    free(buf);
    EXPECT(tick_row(m, 0, 32), "row 32");
    EXPECT(find_write(p, p->offs, p->writes, 1, 0x00, 0xB0) >= 0, "the looping sound keys on with $100 = B0 (repeat)");
    for (int t = 0; t < 50; ++t) opnm_tick(m, g_out);
    frames = render(m, 10, &buf);
    EXPECT(opn_chip_adpcm_playing(c) && rms(buf, frames) > 0.005, "a looping sample still plays after 1 s (rms %.4f)", rms(buf, frames));
    free(buf);
    EXPECT(tick_row(m, 0, 48), "row 48");
    EXPECT(find_write(p, 0, p->offs, 1, 0x00, 0x01) >= 0, "OFF: $100 = 01 in the key-off phase");
    for (int t = 0; t < 3; ++t) opnm_tick(m, g_out);
    EXPECT(!opn_chip_adpcm_playing(c), "OFF stops the sample");
    opnm_destroy(m);

    /* level and pan: slot 3 (level 128, left) against slot 2 (level 255, both) */
    double left[2], right[2];
    for (int k = 0; k < 2; ++k) {
        const Ev e = {0, 0, ch, A4, k ? 2 : 3, 0, {0}};
        build_adpcm(&g_img, &e, 1);
        m = opnm_create(OPN_CHIP_YM2608, RATE);
        opnm_start(m, &g_img, 0, 0, SEEK_BUDGET, &mode);
        frames = render(m, 40, &buf);
        if (k == 0)
            EXPECT(opnm_player(m)->reg[1][0x0B] == 128 && opnm_player(m)->reg[1][0x01] == 0x82, "level 128, left: $10B %d, $101 %02X",
                   opnm_player(m)->reg[1][0x0B], opnm_player(m)->reg[1][0x01]);
        left[k] = rms_ch(buf + 2 * (RATE / 20), frames - RATE / 20, 0);
        right[k] = rms_ch(buf + 2 * (RATE / 20), frames - RATE / 20, 1);
        free(buf);
        opnm_destroy(m);
    }
    EXPECT(right[0] < 0.01 * left[0] && left[0] > 0.005, "pan left: right rms %.6f, left %.4f", right[0], left[0]);
    EXPECT(left[0] / left[1] > 0.45 && left[0] / left[1] < 0.55, "level 128 / 255: loudness ratio %.3f (0.502)", left[0] / left[1]);
    EXPECT(fabs(right[1] / left[1] - 1.0) < 0.01, "pan both: equal sides (%.4f / %.4f)", right[1], left[1]);
}

/* ---- positions, speeds, jumps ------------------------------------------------------------------------------------- */

static void test_positions(void) {
    printf("positions, speeds and jumps\n");
    B s;
    b_init(&s, &g_pos, OPN_CHIP_YM2203, 2, 8, 4);
    s.b[OPN_OFF_ROWS + 1] = 4;
    ins_fm_sine(&s, 1, 0);
    const Ev ev[] = {
        {0, 0, 0, A4, 1, 1, {FX(OPN_FX_SPEED, 4)}},
        {0, 2, 0, NONE, 0, 1, {FX(OPN_FX_SPEED, 3)}},
        {0, 5, 1, NONE, 0, 1, {FX(OPN_FX_BREAK, 2)}},
        {1, 3, 0, NONE, 0, 1, {FX(OPN_FX_JUMP, 0)}},
    };
    EXPECT(b_finish(&s, ev, 4) == 0, "positions image (%s)", g_pos.info.problem);
    EXPECT(g_pos.info.rows[0] == 8 && g_pos.info.rows[1] == 4 && g_pos.info.start_speed[0] == 4 && g_pos.info.start_speed[1] == 3,
           "rows %d/%d, start speeds %d/%d (4/3)", g_pos.info.rows[0], g_pos.info.rows[1], g_pos.info.start_speed[0],
           g_pos.info.start_speed[1]);
    static const int seq[][3] = {{0, 0, 4}, {0, 1, 4}, {0, 2, 3}, {0, 3, 3}, {0, 4, 3}, {0, 5, 3},
                                 {1, 2, 3}, {1, 3, 3}, {0, 0, 4}, {0, 1, 4}};
    OpnMachine* m = opnm_create(OPN_CHIP_YM2203, RATE);
    int mode, bad = 0, o, r, t, frames = 0, native = 0;
    opnm_start(m, &g_pos, 0, 0, SEEK_BUDGET, &mode);
    for (size_t i = 0; i < sizeof seq / sizeof seq[0]; ++i)
        for (int k = 0; k < seq[i][2]; ++k) {
            opnm_position(m, &o, &r, &t);
            if (o != seq[i][0] || r != seq[i][1] || t != k) {
                if (!bad) printf("  at %d/%d/%d expected %d/%d/%d\n", o, r, t, seq[i][0], seq[i][1], k);
                ++bad;
            }
            frames += opnm_tick(m, g_out);
            native += opnm_native_samples(m);
        }
    EXPECT(bad == 0, "F03 from row 2, D02 to order 1 row 2, B00 back to order 0 (its F04 again): %d positions differ", bad);
    const double want = native * RATE / NATIVE_HZ;
    EXPECT(native == 34 * TICK_SAMPLES && fabs(frames - want) <= 2, "34 ticks = %d native samples, %d frames (%.1f)", native, frames, want);
    /* seeks into it: order 1 rows 0-1 are only reached by the order fallback */
    EXPECT(opnm_start(m, &g_pos, 1, 0, SEEK_BUDGET, &mode) == OPNM_OK && mode == OPNM_SEEK_FROM_ORDER, "1/0: order fallback (%d)", mode);
    opnm_position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 0 && t == 0, "fallback lands on 1/0/0 (%d/%d/%d)", o, r, t);
    for (int k = 0; k < 6; ++k) opnm_tick(m, g_out);
    opnm_position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 2 && t == 0, "the fallback plays order 1 at its start speed 3 (%d/%d/%d after 6 ticks)", o, r, t);
    EXPECT(opnm_start(m, &g_pos, 1, 2, SEEK_BUDGET, &mode) == OPNM_OK && mode == OPNM_SEEK_FROM_START, "1/2 reached from the start");
    EXPECT(opnm_start(m, &g_pos, 2, 0, SEEK_BUDGET, &mode) == OPNM_ERR_IMAGE, "order past the song rejected");
    EXPECT(opnm_start(m, &g_pos, 0, 8, SEEK_BUDGET, &mode) == OPNM_ERR_IMAGE, "row past the order rejected");
    opnm_destroy(m);
}

/* ---- seek, live edits, mute, determinism -------------------------------------------------------------------------- */

/* Seeks to `targets` must replay the song fully clocked: from the target on, every tick's position, player writes and
 * output samples (hash of the exact float frames) equal straight play's from (0, 0) (`ref`, `ticks` long), for up to
 * 100 ticks. */
static void seek_exact(int chip, const OpnImage* song, const RefTick* ref, int ticks, const int (*targets)[2], int n,
                       const char* what) {
    OpnMachine* m = opnm_create(chip, RATE);
    for (int i = 0; i < n; ++i) {
        const int o = targets[i][0], r = targets[i][1], k = find_row(ref, ticks, o, r);
        EXPECT(k >= 0, "%s target %d/%d is in straight play", what, o, r);
        if (k < 0) continue;
        int mode = -1;
        const double t0 = now_us();
        EXPECT(opnm_start(m, song, o, r, SEEK_BUDGET, &mode) == OPNM_OK && mode == OPNM_SEEK_FROM_START, "%s seek %d/%d", what, o, r);
        const double us = now_us() - t0;
        EXPECT(pos_is(m, &ref[k]), "%s: lands on %d/%d tick 0", what, o, r);
        int bad_writes = 0, bad_sound = 0, first = -1;
        const int count = ticks - k < 100 ? ticks - k : 100;
        for (int j = 0; j < count; ++j) {
            if (!pos_is(m, &ref[k + j])) ++bad_writes;
            const int frames = opnm_tick(m, g_out);
            if (!same_tick(opnm_player(m), &ref[k + j])) ++bad_writes;
            const uint64_t h = fnv(0xCBF29CE484222325ull, g_out, sizeof(float) * 2 * (size_t)(frames > 0 ? frames : 0));
            if (frames != ref[k + j].frames || h != ref[k + j].hash) {
                if (first < 0) first = j;
                ++bad_sound;
            }
        }
        EXPECT(bad_writes == 0, "%s seek %d/%d: %d ticks of positions and writes = straight play (%d differ)", what, o, r, count,
               bad_writes);
        EXPECT(bad_sound == 0, "%s seek %d/%d: %d ticks of samples identical to straight play (%d differ, first at +%d)", what,
               o, r, count, bad_sound, first);
        printf("  %s %d/%d: %d ticks replayed in %.0f us, %s\n", what, o, r, k, us, bad_sound ? "sound differs" : "sample-identical");
    }
    opnm_destroy(m);
}

/* The CSM song: FM 3 in CSM mode (NA 624) keyed at 0/0 and released at 1/16, FM 1 notes around it; 2 orders x 32 rows
 * at speed 3. */
#define CSM_TICKS (2 * 32 * 3)
static int build_csm_song(OpnImage* im, int chip) {
    B s;
    b_init(&s, im, chip, 2, 32, 3);
    s.b[OPN_H_FM3_MODE] = OPN_FM3_CSM;
    put16(s.b + OPN_H_TIMER_A, 624);
    ins_fm_sine(&s, 1, 0);
    ins_fm_brass(&s, 2);
    Ev ev[40];
    int n = 0;
    ev[n++] = (Ev){0, 0, OPN_CH_FM3, A4, 1, 0, {0}};
    ev[n++] = (Ev){0, 24, OPN_CH_FM3, NONE, 0, 1, {FX(OPN_FX_TIMER_A, 0xA0)}}; /* Txx: NA = 640 from here */
    ev[n++] = (Ev){1, 16, OPN_CH_FM3, OFF, 0, 0, {0}};
    for (int o = 0; o < 2; ++o)
        for (int r = 0; r < 32; r += 8) ev[n++] = (Ev){o, r, 0, 36 + r / 4, 2, 0, {0}};
    return b_finish(&s, ev, n);
}

static void test_seek(int chip, const RefTick* ref) {
    printf("%s seek (fully clocked replay)\n", chip_name(chip));
    const OpnImage* song = &g_song[chip];
    /* 0/5 early; 0/15 and 1/15 in FM 1's release tail (OFF at row 14); 1/16 with the ADPCM-B chirp keyed 144 ticks
     * earlier still playing (YM2608); 1/26 and 2/26 with the SSG hardware-envelope bass sounding; 2/29 in its
     * release; 3/31 the last row. */
    static const int targets[][2] = {{0, 5}, {0, 15}, {1, 15}, {1, 16}, {1, 26}, {2, 26}, {2, 29}, {3, 31}};
    seek_exact(chip, song, ref, SONG_TICKS, targets, (int)(sizeof targets / sizeof targets[0]), "song");
    OpnMachine* m = opnm_create(chip, RATE);
    int mode = -1, o, r, t;
    if (chip == OPN_CHIP_YM2608) {
        EXPECT(opnm_start(m, song, 1, 16, SEEK_BUDGET, &mode) == OPNM_OK && opn_chip_adpcm_playing(opnm_chip(m)),
               "after the seek to 1/16 the chirp keyed at 0/0 is still playing");
    }
    EXPECT(opnm_start(m, song, 1, 26, SEEK_BUDGET, &mode) == OPNM_OK && (opnm_player(m)->reg[0][10] & 0x10),
           "after the seek to 1/26 SSG 3 is on the hardware envelope ($0A = %02X)", opnm_player(m)->reg[0][10]);
    /* CSM running across the target */
    EXPECT(build_csm_song(&g_img, chip) == 0, "%s CSM song (%s)", chip_name(chip), g_img.info.problem);
    double pk;
    RefTick* csm = record(chip, &g_img, CSM_TICKS, &pk);
    if (csm) {
        static const int csm_targets[][2] = {{0, 10}, {0, 30}, {1, 8}, {1, 20}};
        seek_exact(chip, &g_img, csm, CSM_TICKS, csm_targets, 4, "CSM");
        EXPECT(opnm_start(m, &g_img, 1, 8, SEEK_BUDGET, &mode) == OPNM_OK && opnm_player(m)->reg[0][0x27] == 0x81,
               "after the seek to 1/8 CSM runs ($27 = %02X)", opnm_player(m)->reg[0][0x27]);
        const uint32_t e0 = opn_chip_timer_a_expiries(opnm_chip(m));
        for (int j = 0; j < 10; ++j) opnm_tick(m, g_out);
        EXPECT(opn_chip_timer_a_expiries(opnm_chip(m)) > e0, "and timer A keeps firing after it");
        free(csm);
    }
    EXPECT(opnm_start(m, song, 3, 4, 10, &mode) == OPNM_OK && mode == OPNM_SEEK_FROM_ORDER, "small budget: order fallback");
    opnm_position(m, &o, &r, &t);
    EXPECT(o == 3 && r == 4 && t == 0, "budget fallback lands on 3/4 (%d/%d/%d)", o, r, t);
    /* from there its writes are those of a player placed at order 3 (opnp_place) and ticked to row 4 */
    static OpnPlayer placed;
    opnp_reset(&placed, song);
    opnp_place(&placed, song, 3);
    for (int j = 0; j < 4000 && !(placed.order == 3 && placed.row == 4 && placed.tick == 0); ++j) opnp_tick(&placed, song);
    EXPECT(placed.order == 3 && placed.row == 4 && placed.tick == 0, "the placed player reaches 3/4");
    int bad = 0;
    for (int j = 0; j < 60; ++j) {
        opnm_tick(m, g_out);
        opnp_tick(&placed, song);
        const OpnPlayer* p = opnm_player(m);
        if (p->writes != placed.writes || p->offs != placed.offs ||
            memcmp(p->write, placed.write, sizeof(OpnWrite) * (size_t)placed.writes))
            ++bad;
    }
    opnm_position(m, &o, &r, &t);
    EXPECT(bad == 0 && o == 3 && r == 24 && t == 0, "fallback: 60 ticks of writes = the placed player's (%d differ), at %d/%d/%d",
           bad, o, r, t);
    EXPECT(opnm_start(m, song, SONG_ORDERS, 0, SEEK_BUDGET, &mode) == OPNM_ERR_IMAGE, "order past the song rejected");
    EXPECT(opnm_start(m, song, 0, SONG_ROWS, SEEK_BUDGET, &mode) == OPNM_ERR_IMAGE, "row past the order rejected");
    EXPECT(opnm_start(m, &g_song[chip ^ 1], 0, 0, SEEK_BUDGET, &mode) == OPNM_ERR_IMAGE, "the other chip's image refused");
    opnm_destroy(m);
}

static void test_live(int chip, const RefTick* ref) {
    printf("%s live relayout\n", chip_name(chip));
    OpnMachine* m = opnm_create(chip, RATE);
    int mode, bad = 0, first = -1;
    opnm_start(m, &g_song[chip], 0, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < SONG_TICKS; ++k) {
        opnm_apply_image(m, (k & 1) ? &g_moved[chip] : &g_song[chip]);
        int b = !pos_is(m, &ref[k]);
        const int n = opnm_tick(m, g_out);
        b += !same_tick(opnm_player(m), &ref[k]);
        b += n != ref[k].frames || fnv(0xCBF29CE484222325ull, g_out, sizeof(float) * 2 * (size_t)(n > 0 ? n : 0)) != ref[k].hash;
        if (b && first < 0) first = k;
        bad += b;
    }
    EXPECT(bad == 0, "apply_image before every tick (alternately the moved copy): positions, writes and samples = straight play "
           "(%d differ, first at tick %d)", bad, first);
    opnm_destroy(m);
}

/* Mute continuity at the chip (opn_ymfm.h): two chips fed identical writes (FM 1 with feedback 7 retuned mid-way, SSG
 * A, on the YM2608 also a looping ADPCM-B sample), one with part `p` muted for the first MUTE_SAMPLES native samples.
 * While muted that part's fields are 0 and every other field equals the never-muted chip's; from the unmute on every
 * field is identical (the muted part kept computing: FM feedback history, SSG counters, ADPCM-B position). */
#define MUTE_SAMPLES 20000
#define MUTE_RUN 50000
static void chip_setup(OpnChip* c, int chip) {
    static const uint8_t ops[4] = {0x00, 0x08, 0x04, 0x0C}; /* operators 1-4 */
    opn_chip_write(c, 0, 0xB0, 0x3F);                       /* feedback 7, algorithm 7 */
    if (chip == OPN_CHIP_YM2608) opn_chip_write(c, 0, 0xB4, 0xC0);
    for (int k = 0; k < 4; ++k) {
        opn_chip_write(c, 0, (uint8_t)(0x30 + ops[k]), 0x01);
        opn_chip_write(c, 0, (uint8_t)(0x40 + ops[k]), k == 0 ? 0x00 : 0x7F);
        opn_chip_write(c, 0, (uint8_t)(0x50 + ops[k]), 0x1F);
        opn_chip_write(c, 0, (uint8_t)(0x60 + ops[k]), 0x00);
        opn_chip_write(c, 0, (uint8_t)(0x70 + ops[k]), 0x00);
        opn_chip_write(c, 0, (uint8_t)(0x80 + ops[k]), 0x0F);
        opn_chip_write(c, 0, (uint8_t)(0x90 + ops[k]), 0x00);
    }
    opn_chip_write(c, 0, 0xA4, 0x22); /* block 4, F-number $210 */
    opn_chip_write(c, 0, 0xA0, 0x10);
    opn_chip_write(c, 0, 0x28, 0xF0); /* FM 1 key on */
    opn_chip_write(c, 0, 0x00, 0x1C); /* SSG A: TP 284 */
    opn_chip_write(c, 0, 0x01, 0x01);
    opn_chip_write(c, 0, 0x07, 0x3E); /* tone A only */
    opn_chip_write(c, 0, 0x08, 0x0F);
    if (chip == OPN_CHIP_YM2608) {
        const int dn = expect_dn(SINE_RATE, A4, A4), end = SINE_BYTES / 32 - 1;
        opn_chip_write(c, 1, 0x00, 0x01); /* reset */
        opn_chip_write(c, 1, 0x01, 0xC2); /* both sides, 8-bit DRAM */
        opn_chip_write(c, 1, 0x02, 0x00);
        opn_chip_write(c, 1, 0x03, 0x00);
        opn_chip_write(c, 1, 0x04, (uint8_t)(end & 0xFF));
        opn_chip_write(c, 1, 0x05, (uint8_t)(end >> 8));
        opn_chip_write(c, 1, 0x0C, 0xFF);
        opn_chip_write(c, 1, 0x0D, 0xFF);
        opn_chip_write(c, 1, 0x09, (uint8_t)(dn & 0xFF));
        opn_chip_write(c, 1, 0x0A, (uint8_t)(dn >> 8));
        opn_chip_write(c, 1, 0x0B, 0xFF);
        opn_chip_write(c, 1, 0x00, 0xB0); /* start, external memory, repeat */
    }
}

static void test_chip_mute(int chip) {
    printf("%s chip mute continuity\n", chip_name(chip));
    const int parts = chip == OPN_CHIP_YM2608 ? 3 : 2;
    static const char* names[3] = {"FM 1", "SSG A", "ADPCM-B"};
    for (int part = 0; part < parts; ++part) {
        const uint32_t mute = part == 0 ? OPN_MUTE_FM(0) : part == 1 ? OPN_MUTE_SSG(0) : OPN_MUTE_ADPCM;
        OpnChip* a = opn_chip_create(chip);
        OpnChip* b = opn_chip_create(chip);
        EXPECT(a && b, "%s chips created", chip_name(chip));
        if (!a || !b) {
            if (a) opn_chip_destroy(a);
            if (b) opn_chip_destroy(b);
            return;
        }
        if (chip == OPN_CHIP_YM2608) {
            opn_chip_set_adpcm(a, g_sine_adpcm, SINE_BYTES);
            opn_chip_set_adpcm(b, g_sine_adpcm, SINE_BYTES);
        }
        chip_setup(a, chip);
        chip_setup(b, chip);
        int muted_part_zero = 1, others_same = 1, after_same = 1, first_after = -1;
        int32_t heard = 0;
        for (int i = 0; i < MUTE_RUN; ++i) {
            if (i == MUTE_SAMPLES / 2) /* retune FM 1 while muted: block 5 */
                for (OpnChip* c = a; c; c = c == a ? b : NULL) opn_chip_write(c, 0, 0xA4, 0x2A), opn_chip_write(c, 0, 0xA0, 0x10);
            OpnOutput x, y; /* x: muted until MUTE_SAMPLES, y: never muted */
            opn_chip_generate(a, i < MUTE_SAMPLES ? mute : 0, &x);
            opn_chip_generate(b, 0, &y);
            const int32_t* own_x = part == 0 ? x.fm : part == 1 ? x.ssg : x.adpcm;
            const int32_t* own_y = part == 0 ? y.fm : part == 1 ? y.ssg : y.adpcm;
            const int n = part == 1 ? 1 : 2;
            for (int k = 0; k < n; ++k)
                if (abs(own_y[k]) > heard) heard = abs(own_y[k]);
            if (i < MUTE_SAMPLES) {
                for (int k = 0; k < n; ++k) muted_part_zero &= own_x[k] == 0;
                if (part != 0) others_same &= x.fm[0] == y.fm[0] && x.fm[1] == y.fm[1];
                if (part != 1) others_same &= x.ssg[0] == y.ssg[0] && x.ssg[1] == y.ssg[1] && x.ssg[2] == y.ssg[2];
                if (part != 2) others_same &= x.adpcm[0] == y.adpcm[0] && x.adpcm[1] == y.adpcm[1];
                others_same &= x.rhythm[0] == y.rhythm[0] && x.rhythm[1] == y.rhythm[1];
            } else if (memcmp(&x, &y, sizeof x)) {
                if (first_after < 0) first_after = i - MUTE_SAMPLES;
                after_same = 0;
            }
        }
        EXPECT(heard > 0, "%s %s sounds in the never-muted chip (peak %d)", chip_name(chip), names[part], heard);
        EXPECT(muted_part_zero, "%s %s muted: its output fields are 0", chip_name(chip), names[part]);
        EXPECT(others_same, "%s %s muted: every other part's fields = the never-muted chip's", chip_name(chip), names[part]);
        EXPECT(after_same, "%s %s unmuted after %d native samples: every field = never muted (first difference +%d)",
               chip_name(chip), names[part], MUTE_SAMPLES, first_after);
        opn_chip_destroy(a);
        opn_chip_destroy(b);
    }
}

/* Mute in the machine: channel c muted for MUTE_TICKS ticks from the start, then unmuted. The player's writes are
 * straight play's throughout; while muted the output is the song without c's events exactly (the same input to the
 * host resampler); after the unmute it converges to the never-muted render (the resampler's high-pass remembers the
 * muted stretch for a while): from 0.5 s after the unmute every sample within 1e-4. */
#define MUTE_TICKS 150
static void test_mute(int chip, const RefTick* ref) {
    printf("%s mute\n", chip_name(chip));
    test_chip_mute(chip);
    static const int channels[2][4] = {{0, 1, 3, -1}, {0, 3, OPNA_CH_SSG, OPNA_CH_ADPCM}};
    OpnMachine* never = opnm_create(chip, RATE);
    int mode;
    opnm_start(never, &g_song[chip], 0, 0, SEEK_BUDGET, &mode);
    float* clean;
    const int clean_frames = render(never, SONG_TICKS, &clean);
    opnm_destroy(never);
    for (int d = 0; d < 4; ++d) {
        const int ch = channels[chip][d];
        if (ch < 0) continue;
        EXPECT(build_song(&g_drop, chip, 0, ch) == 0, "song without channel %d (%s)", ch, g_drop.info.problem);
        double pk;
        RefTick* dropped = record(chip, &g_drop, MUTE_TICKS, &pk);
        if (!dropped) continue;
        OpnMachine* m = opnm_create(chip, RATE);
        int bad_writes = 0, bad_muted = 0, first_muted = -1, frames = 0, unmute_frame = 0;
        float* out = (float*)malloc(sizeof(float) * 2 * OPNM_MAX_TICK_FRAMES * SONG_TICKS);
        opnm_start(m, &g_song[chip], 0, 0, SEEK_BUDGET, &mode);
        EXPECT(opnm_mute(m, ch, 1) == OPNM_OK, "mute channel %d", ch);
        for (int k = 0; k < SONG_TICKS; ++k) {
            if (k == MUTE_TICKS) opnm_mute(m, ch, 0), unmute_frame = frames;
            const int n = opnm_tick(m, out + 2 * frames);
            bad_writes += !same_tick(opnm_player(m), &ref[k]);
            const uint64_t h = fnv(0xCBF29CE484222325ull, out + 2 * frames, sizeof(float) * 2 * (size_t)(n > 0 ? n : 0));
            if (k < MUTE_TICKS && (n != dropped[k].frames || h != dropped[k].hash)) {
                if (first_muted < 0) first_muted = k;
                ++bad_muted;
            }
            frames += n > 0 ? n : 0;
        }
        double worst = 0;
        const int from = unmute_frame + RATE / 2, to = frames < clean_frames ? frames : clean_frames;
        for (int i = 2 * from; i < 2 * to; ++i)
            if (fabs((double)out[i] - clean[i]) > worst) worst = fabs((double)out[i] - clean[i]);
        EXPECT(bad_writes == 0, "channel %d muted: the player's writes unchanged (%d differ)", ch, bad_writes);
        EXPECT(bad_muted == 0, "channel %d muted: samples = the song without it (%d of %d ticks differ, first %d)", ch, bad_muted,
               MUTE_TICKS, first_muted);
        EXPECT(frames == clean_frames && to > from && worst < 1e-4,
               "channel %d unmuted: from 0.5 s after the unmute within %.2e of never muted (bound 1e-4)", ch, worst);
        printf("  channel %d: muted = without it %s; 0.5 s after the unmute max difference %.2e\n", ch,
               bad_muted ? "NO" : "yes", worst);
        free(out);
        free(dropped);
        opnm_destroy(m);
    }
    free(clean);
    OpnMachine* m = opnm_create(chip, RATE);
    /* all channels muted, then all unmuted */
    opnm_start(m, &g_song[chip], 1, 0, SEEK_BUDGET, &mode);
    for (int c = 0; c < opn_channels(chip); ++c) opnm_mute(m, c, 1);
    float* buf;
    int frames = render(m, 30, &buf);
    EXPECT(rms(buf + 2 * (RATE / 10), frames - RATE / 10) < 1e-4, "all channels muted: rms %.6f after 100 ms",
           rms(buf + 2 * (RATE / 10), frames - RATE / 10));
    free(buf);
    for (int c = 0; c < opn_channels(chip); ++c) opnm_mute(m, c, 0);
    frames = render(m, 30, &buf);
    EXPECT(rms(buf, frames) > 0.001, "unmuted: sound again (rms %.4f)", rms(buf, frames));
    free(buf);
    EXPECT(opnm_mute(m, opn_channels(chip), 1) != OPNM_OK, "channel %d rejected", opn_channels(chip));
    opnm_destroy(m);
}

/* ---- audition ----------------------------------------------------------------------------------------------------- */

static int release_ticks(OpnMachine* m, int limit) {
    int ticks = 0;
    while (opnm_tick(m, g_out) > 0 && ticks < limit) ++ticks;
    return ticks;
}

static void test_audition(void) {
    printf("audition\n");
    for (int chip = 0; chip < 2; ++chip) {
        const char* n = chip_name(chip);
        EXPECT(build_full(&g_img, chip, NULL, 0) == 0, "%s audition image", n);
        OpnMachine* m = opnm_create(chip, RATE);
        const OpnPlayer* p = opnm_player(m);
        const int fms[2] = {0, opn_fm_channels(chip) - 1};
        for (int i = 0; i < 2; ++i) {
            EXPECT(opnm_audition(m, &g_img, fms[i], A4, 1, 0) == OPNM_OK, "%s FM %d A-4", n, fms[i] + 1);
            float* buf;
            const int frames = render(m, 60, &buf);
            int b, f;
            fm_regs(p, fms[i], &b, &f);
            const double hz = zero_cross_hz(buf + 2 * (RATE / 10), frames - RATE / 10, 0);
            EXPECT(fabs(hz / fm_hz(b, f) - 1.0) < 0.001 && fabs(hz / 440.0 - 1.0) < 0.002, "%s FM %d audition %.3f Hz", n, fms[i] + 1, hz);
            free(buf);
            EXPECT(opnm_release(m) == 1, "%s FM release phase", n);
            const int t = release_ticks(m, 700);
            EXPECT(t < 60, "%s FM release ends when the carrier is quiet (%d ticks)", n, t);
        }
        EXPECT(opnm_audition(m, &g_img, 0, A4, 1, 64) == OPNM_OK, "%s fine +64", n);
        float* buf;
        int frames = render(m, 60, &buf);
        const double up = zero_cross_hz(buf + 2 * (RATE / 10), frames - RATE / 10, 0);
        EXPECT(fabs(up / et_hz(58) - 1.0) < 0.0015, "%s fine +64 = A#4: %.3f Hz", n, up);
        free(buf);
        const int ssg = opn_ssg_first(chip);
        EXPECT(opnm_audition(m, &g_img, ssg, A4, 2, 0) == OPNM_OK, "%s SSG A-4", n);
        frames = render(m, 30, &buf);
        EXPECT(rms(buf, frames) > 0.002, "%s SSG audition audible", n);
        free(buf);
        const int r = opnm_release(m);
        const int t = r ? release_ticks(m, 700) : 0;
        EXPECT(r == 0 || t < 60, "%s SSG release ends (%d, %d ticks)", n, r, t);
        if (chip == OPN_CHIP_YM2608) {
            EXPECT(opnm_audition(m, &g_img, OPNA_CH_ADPCM, A4, 4, 0) == OPNM_OK, "ADPCM-B audition");
            opnm_tick(m, g_out);
            opnm_tick(m, g_out);
            EXPECT(opn_chip_adpcm_playing(opnm_chip(m)), "ADPCM-B audition plays");
            EXPECT(opnm_release(m) == 1, "ADPCM-B release phase");
            const int ta = release_ticks(m, 700);
            EXPECT(ta < 20, "ADPCM-B release ends (%d ticks)", ta);
        }
        EXPECT(opnm_audition(m, &g_img, 0, A4, 1, 0) == OPNM_OK && opnm_release(m) == 0, "%s release before any tick: silent now", n);
        EXPECT(opnm_audition(m, &g_img, 0, A4, 1, 127) == OPNM_OK && opnm_audition(m, &g_img, 0, A4, 1, -128) == OPNM_OK,
               "%s fine -128 and 127 accepted", n);
        EXPECT(opnm_audition(m, &g_img, opn_channels(chip), A4, 1, 0) != OPNM_OK, "%s channel %d rejected", n, opn_channels(chip));
        EXPECT(opnm_audition(m, &g_img, -1, A4, 1, 0) != OPNM_OK, "%s channel -1 rejected", n);
        EXPECT(opnm_audition(m, &g_img, 0, 96, 1, 0) != OPNM_OK, "%s note 96 rejected", n);
        EXPECT(opnm_audition(m, &g_img, 0, -1, 1, 0) != OPNM_OK, "%s note -1 rejected", n);
        EXPECT(opnm_audition(m, &g_img, 0, A4, 0, 0) != OPNM_OK, "%s slot 0 rejected", n);
        EXPECT(opnm_audition(m, &g_img, 0, A4, 65, 0) != OPNM_OK, "%s slot 65 rejected", n);
        EXPECT(opnm_audition(m, &g_img, 0, A4, 9, 0) != OPNM_OK, "%s undefined slot rejected", n);
        EXPECT(opnm_audition(m, &g_img, 0, A4, 2, 0) != OPNM_OK, "%s SSG sound on FM rejected", n);
        EXPECT(opnm_audition(m, &g_img, ssg, A4, 1, 0) != OPNM_OK, "%s FM sound on SSG rejected", n);
        EXPECT(opnm_audition(m, &g_img, 0, A4, 1, 128) != OPNM_OK, "%s fine 128 rejected", n);
        EXPECT(opnm_audition(m, &g_img, 0, A4, 1, -129) != OPNM_OK, "%s fine -129 rejected", n);
        opnm_destroy(m);
    }
}

/* ---- VGM ---------------------------------------------------------------------------------------------------------- */

typedef struct VgmScan {
    int ok, ended, loop_seen, blocks, block_type;
    const char* why;
    uint32_t samples, loop_samples, block_size;
    size_t block_at, first_cmd;
    int writes[3]; /* $55, $56, $57 */
} VgmScan;

static void vgm_scan(const uint8_t* v, size_t size, VgmScan* s) {
    memset(s, 0, sizeof *s);
    s->ok = 1;
    if (size < 0x100) {
        s->ok = 0, s->why = "short";
        return;
    }
    const size_t start = 0x34 + opn_u32(v + 0x34), loop = opn_u32(v + 0x1C) ? 0x1C + opn_u32(v + 0x1C) : 0;
    s->first_cmd = start;
    size_t i = start;
    while (i < size) {
        const int after = loop && i >= loop;
        if (loop && i == loop) s->loop_seen = 1;
        const uint8_t c = v[i];
        uint32_t wait = 0;
        if (c == 0x55 || c == 0x56 || c == 0x57) {
            s->writes[c - 0x55]++;
            i += 3;
        } else if (c == 0x61) {
            wait = v[i + 1] | v[i + 2] << 8;
            i += 3;
        } else if (c == 0x62) {
            wait = 735, ++i;
        } else if (c == 0x63) {
            wait = 882, ++i;
        } else if (c >= 0x70 && c <= 0x7F) {
            wait = (uint32_t)(c & 15) + 1, ++i;
        } else if (c == 0x67) {
            if (i + 7 > size || v[i + 1] != 0x66) {
                s->ok = 0, s->why = "data block header";
                return;
            }
            const uint32_t len = opn_u32(v + i + 3);
            if (!s->blocks) s->block_type = v[i + 2], s->block_size = len, s->block_at = i + 7;
            ++s->blocks;
            i += 7 + len;
        } else if (c == 0x66) {
            s->ended = 1;
            if (i + 1 != size) s->ok = 0, s->why = "bytes after the end command";
            return;
        } else {
            s->ok = 0, s->why = "unknown command";
            return;
        }
        s->samples += wait;
        if (after) s->loop_samples += wait;
    }
    s->ok = 0, s->why = "no end command";
}

static void test_vgm(const char* dir, int chip) {
    printf("%s VGM\n", chip_name(chip));
    const OpnImage* song = &g_song[chip];
    OpnVgmInfo info;
    const double t0 = now_us();
    const size_t size = opn_vgm_render(song, 100000, g_vplayer, g_vgm, sizeof g_vgm, &info);
    const double us = now_us() - t0;
    EXPECT(size > 0 && size == info.size, "renders (%zu bytes, info %zu)", size, info.size);
    if (!size) return;
    const uint8_t* v = g_vgm;
    VgmScan s;
    vgm_scan(v, size, &s);
    EXPECT(!memcmp(v, "Vgm ", 4) && opn_u32(v + 8) == 0x171 && opn_u32(v + 4) + 4 == size, "header: ident, version 1.71, EOF offset");
    EXPECT(opn_u32(v + 0x14) == 0, "no GD3 from the renderer");
    const int opna = chip == OPN_CHIP_YM2608;
    EXPECT(opn_u32(v + 0x44) == (opna ? 0 : OPN_CLOCK_YM2203) && opn_u32(v + 0x48) == (opna ? OPN_CLOCK_YM2608 : 0),
           "clocks: $44 = %u, $48 = %u", opn_u32(v + 0x44), opn_u32(v + 0x48));
    EXPECT(s.ok && s.ended, "commands well formed, ends with $66 (%s)", s.why ? s.why : "ok");
    EXPECT(opna ? s.writes[0] == 0 && s.writes[1] > 0 && s.writes[2] > 0 : s.writes[0] > 0 && !s.writes[1] && !s.writes[2],
           "write commands $55/$56/$57: %d/%d/%d", s.writes[0], s.writes[1], s.writes[2]);
    EXPECT(info.loop_tick > 0 && info.loop_tick < info.ticks && info.ticks <= 100000,
           "a loop found: ticks %u to %u", info.loop_tick, info.ticks);
    const double want = info.ticks * (double)TICK_SAMPLES * 44100.0 / NATIVE_HZ;
    EXPECT(s.samples == info.samples && opn_u32(v + 0x18) == s.samples && fabs(s.samples - want) <= 1.0,
           "total samples %u = header %u = %.1f (ticks x tick length)", s.samples, opn_u32(v + 0x18), want);
    const double want_loop = (info.ticks - info.loop_tick) * (double)TICK_SAMPLES * 44100.0 / NATIVE_HZ;
    EXPECT(info.loop_offset && 0x1C + opn_u32(v + 0x1C) == info.loop_offset && s.loop_seen && s.loop_samples == info.loop_samples &&
               opn_u32(v + 0x20) == s.loop_samples && fabs(s.loop_samples - want_loop) <= 2.0,
           "loop at %u (a command), %u samples (%.1f)", info.loop_offset, s.loop_samples, want_loop);
    if (opna) {
        const uint32_t used = song->info.adpcm_size;
        EXPECT(s.blocks == 1 && s.block_type == 0x81 && s.block_size == 8 + used && s.block_at == s.first_cmd + 7 &&
                   opn_u32(v + s.block_at) == OPN_ADPCM_MEMORY && opn_u32(v + s.block_at + 4) == 0 &&
                   !memcmp(v + s.block_at + 8, song->bytes + OPN_OFF_ADPCM, used),
               "first command: data block $81 (%d blocks, type %02X, %u bytes) = memory size, start 0 and the ADPCM-B memory",
               s.blocks, s.block_type, s.block_size);
        /* without samples: no data block */
        B b;
        b_init(&b, &g_img, chip, 1, 4, 3);
        ins_fm_sine(&b, 1, 0);
        const Ev e = {0, 0, 0, A4, 1, 0, {0}};
        b_finish(&b, &e, 1);
        const size_t n = opn_vgm_render(&g_img, 100, g_vplayer, g_vgm, sizeof g_vgm, &info);
        VgmScan t;
        vgm_scan(g_vgm, n, &t);
        EXPECT(n > 0 && t.ok && t.blocks == 0, "YM2608 song without samples: no data block (%d)", t.blocks);
        /* g_vgm was reused; the song's file is rendered again below for the evidence */
    } else {
        EXPECT(s.blocks == 0, "YM2203: no data block (%d)", s.blocks);
    }
    EXPECT(opn_vgm_render(song, 100000, g_vplayer, g_vgm, 1000, &info) == 0 && info.size == size, "too small a buffer: 0, size %zu", info.size);
    opn_vgm_render(song, 100000, g_vplayer, g_vgm, sizeof g_vgm, &info);
    char name[64];
    snprintf(name, sizeof name, "%s_song.vgm", prefix(chip));
    save(dir, name, g_vgm, size);
    snprintf(name, sizeof name, "%s_song.bin", prefix(chip));
    save(dir, name, song->bytes, opn_image_size(chip));
    if (g_manifest) fprintf(g_manifest, "song %s_song %s %u %u\n", prefix(chip), chip_name(chip), info.ticks, info.loop_tick);
    printf("  %zu bytes, loop ticks %u..%u, loop %u samples, rendered in %.0f us\n", size, info.loop_tick, info.ticks,
           info.loop_samples, us);
}

/* ---- VGM loops: the file, its loop expanded, = straight play's writes --------------------------------------------- */

typedef struct WriteList {
    OpnWrite* w;
    int n, cap;
} WriteList;

static void wl_add(WriteList* l, const OpnWrite* w, int n) {
    if (n <= 0) return;
    if (l->n + n > l->cap) {
        l->cap = (l->n + n) * 2 + 1024;
        l->w = (OpnWrite*)realloc(l->w, sizeof(OpnWrite) * (size_t)l->cap);
    }
    memcpy(l->w + l->n, w, sizeof(OpnWrite) * (size_t)n);
    l->n += n;
}

/* The register writes of a VGM in file order ($55 / $56 port 0, $57 port 1); *loop_index = the index of the first
 * write at or after the loop offset (-1: no loop offset, or not on a command). */
static void vgm_writes(const uint8_t* v, size_t size, WriteList* out, int* loop_index) {
    const size_t start = 0x34 + opn_u32(v + 0x34), loop = opn_u32(v + 0x1C) ? 0x1C + opn_u32(v + 0x1C) : 0;
    *loop_index = -1;
    size_t i = start;
    while (i < size) {
        if (loop && i == loop) *loop_index = out->n;
        const uint8_t c = v[i];
        if (c >= 0x55 && c <= 0x57) {
            const OpnWrite w = {(uint8_t)(c == 0x57), v[i + 1], v[i + 2]};
            wl_add(out, &w, 1);
            i += 3;
        } else if (c == 0x61) {
            i += 3;
        } else if (c == 0x62 || c == 0x63 || (c >= 0x70 && c <= 0x7F)) {
            ++i;
        } else if (c == 0x67) {
            i += 7 + opn_u32(v + i + 3);
        } else {
            break; /* $66, or a command vgm_scan reports */
        }
    }
}

/* Renders `im`'s VGM (at most max_ticks ticks), expands it - the whole file, then its loop body VGM_PASSES - 1 more
 * times - and requires exactly the writes of a fresh player: opnp_machine_reset's, opnp_reset's batch, then every
 * tick's batch (key-off phase first, as the player orders them) of straight ticking for loop_tick + VGM_PASSES x
 * (ticks - loop_tick) ticks (`ticks` without a loop). The loop must begin and end at arrivals of straight play at
 * (loop order, row 0, tick 0). Without a loop (want_loop 0) the header's loop fields are 0 and the file holds
 * max_ticks ticks. Writes <name>.vgm and <name>_trace.txt (straight play's writes, "port reg val" in hex) for
 * opn_reference_check.py. */
static OpnVgmInfo check_loop(const char* dir, const char* name, const OpnImage* im, uint32_t max_ticks, int want_loop) {
    OpnVgmInfo info;
    memset(&info, 0, sizeof info);
    const size_t size = opn_vgm_render(im, max_ticks, g_vplayer, g_vgm, sizeof g_vgm, &info);
    EXPECT(size > 0, "%s: VGM renders", name);
    if (!size) return info;
    VgmScan s;
    vgm_scan(g_vgm, size, &s);
    EXPECT(s.ok && s.ended, "%s: well formed (%s)", name, s.why ? s.why : "ok");
    const int looped = info.loop_offset != 0;
    if (want_loop)
        EXPECT(looped && info.loop_tick > 0 && info.loop_tick < info.ticks && opn_u32(g_vgm + 0x1C) != 0,
               "%s: a loop from tick %u to %u", name, info.loop_tick, info.ticks);
    else
        EXPECT(!looped && info.loop_tick == 0 && opn_u32(g_vgm + 0x1C) == 0 && opn_u32(g_vgm + 0x20) == 0 && info.ticks == max_ticks,
               "%s: no loop within %u ticks (loop %u..%u, header %u/%u)", name, max_ticks, info.loop_tick, info.ticks,
               opn_u32(g_vgm + 0x1C), opn_u32(g_vgm + 0x20));
    const double want = info.ticks * (double)opn_tick_samples(im->info.timer_b) * 44100.0 / NATIVE_HZ;
    EXPECT(fabs(s.samples - want) <= 1.0, "%s: %u samples = %u ticks (%.1f)", name, s.samples, info.ticks, want);

    WriteList file = {0}, expanded = {0}, straight = {0};
    int loop_index = -1;
    vgm_writes(g_vgm, size, &file, &loop_index);
    wl_add(&expanded, file.w, file.n);
    if (looped) {
        EXPECT(loop_index >= 0 && loop_index < file.n, "%s: the loop offset is on a command before writes (%d of %d)", name,
               loop_index, file.n);
        if (loop_index >= 0)
            for (int k = 1; k < VGM_PASSES; ++k) wl_add(&expanded, file.w + loop_index, file.n - loop_index);
    }
    const uint32_t total = looped ? info.loop_tick + VGM_PASSES * (info.ticks - info.loop_tick) : info.ticks;
    static OpnPlayer p;
    static OpnWrite reset[OPNP_MAX_RESET_WRITES];
    wl_add(&straight, reset, opnp_machine_reset(im->info.chip, reset));
    opnp_reset(&p, im);
    wl_add(&straight, p.write, p.writes);
    int arrival_b = !looped, arrival_a = !looped, arrivals = 0;
    for (uint32_t t = 0; t < total; ++t) {
        if (p.order == im->info.loop && p.row == 0 && p.tick == 0) {
            ++arrivals;
            if (t == info.loop_tick) arrival_b = 1;
            if (t == info.ticks) arrival_a = 1;
        }
        opnp_tick(&p, im);
        wl_add(&straight, p.write, p.writes);
    }
    EXPECT(arrival_b && arrival_a, "%s: loop ticks %u and %u are arrivals at %d/0/0 in straight play (%d arrivals)", name,
           info.loop_tick, info.ticks, im->info.loop, arrivals);
    int first = -1;
    const int n = expanded.n < straight.n ? expanded.n : straight.n;
    for (int i = 0; i < n && first < 0; ++i)
        if (memcmp(&expanded.w[i], &straight.w[i], sizeof(OpnWrite))) first = i;
    EXPECT(expanded.n == straight.n && first < 0,
           "%s: the VGM with its loop played %d times = %u ticks of straight play's writes (%d vs %d writes, first difference "
           "at %d)", name, VGM_PASSES, total, expanded.n, straight.n, first);

    char file_name[256], path[512];
    snprintf(file_name, sizeof file_name, "%s.vgm", name);
    save(dir, file_name, g_vgm, size);
    snprintf(path, sizeof path, "%s/%s_trace.txt", dir, name);
    FILE* f = fopen(path, "w");
    if (f) {
        fprintf(f, "# %s: straight play's writes (reset, init, %u ticks); port reg val in hex\n", name, total);
        for (int i = 0; i < straight.n; ++i) fprintf(f, "%d %02X %02X\n", straight.w[i].port, straight.w[i].reg, straight.w[i].val);
        fclose(f);
    }
    if (g_manifest)
        fprintf(g_manifest, "loop %s %s %u %u %u %d\n", name, chip_name(im->info.chip), total, info.loop_tick,
                looped ? info.ticks : 0, VGM_PASSES);
    printf("  %-22s loop %u..%u (%d arrivals checked), %d writes expanded = straight play\n", name, info.loop_tick, info.ticks,
           arrivals, expanded.n);
    free(file.w);
    free(expanded.w);
    free(straight.w);
    return info;
}

/* Loop order 0 of two 4-row orders at speed 3; in the last row an SSG hardware-envelope note, a CSM FM 3 note and (YM2608)
 * a looping ADPCM-B sample are keyed and held across the wrap; FM 1 keys at 0/0. Arrivals at ticks 0, 24, 48. */
static int build_wrap(OpnImage* im, int chip) {
    B s;
    b_init(&s, im, chip, 2, 4, 3);
    s.b[OPN_H_FM3_MODE] = OPN_FM3_CSM;
    put16(s.b + OPN_H_TIMER_A, 624);
    ins_fm_sine(&s, 1, 0);
    ins_ssg(&s, 2, 0x0E, 1, 0, 0, NOENV, NOENV, NOENV);
    Ev ev[4];
    int n = 0;
    ev[n++] = (Ev){0, 0, 0, A4, 1, 0, {0}};
    ev[n++] = (Ev){1, 3, opn_ssg_first(chip), 45, 2, 0, {0}};
    ev[n++] = (Ev){1, 3, OPN_CH_FM3, 64, 1, 0, {0}};
    if (chip == OPN_CHIP_YM2608) {
        b_sample(&s, 0, g_sine_adpcm, SINE_BYTES, 0, SINE_RATE);
        ins_adpcm(&s, 3, 255, 0, A4, OPN_ADPCM_LOOP, 0xC0);
        ev[n++] = (Ev){1, 3, OPNA_CH_ADPCM, A4, 3, 0, {0}};
    }
    return b_finish(&s, ev, n);
}

/* Order 0 (one row) keys FM 1 at C7F; loop order 1 (8 rows at speed 3) slides it down with A02 on every row, so the
 * volume reaches 0 (and the state repeats) only after several passes. Arrivals at ticks 3, 27, 51, ... */
static int build_volslide(OpnImage* im, int chip) {
    B s;
    b_init(&s, im, chip, 2, 8, 3);
    s.b[OPN_OFF_ROWS] = 1;
    put16(s.b + OPN_H_LOOP, 1);
    ins_fm_sine(&s, 1, 0);
    Ev ev[9];
    ev[0] = (Ev){0, 0, 0, A4, 1, 1, {FX(OPN_FX_VOLUME, 0x7F)}};
    for (int r = 0; r < 8; ++r) ev[1 + r] = (Ev){1, r, 0, NONE, 0, 1, {FX(OPN_FX_VOLUME_SLIDE, 0x02)}};
    return b_finish(&s, ev, 9);
}

/* As build_volslide with 101 (slide up 1/64 semitone per tick) from C-0 instead: the pitch keeps rising for 6143
 * ticks, so no two arrivals within 1200 ticks share a state. */
static int build_slide(OpnImage* im, int chip) {
    B s;
    b_init(&s, im, chip, 2, 8, 3);
    s.b[OPN_OFF_ROWS] = 1;
    put16(s.b + OPN_H_LOOP, 1);
    ins_fm_sine(&s, 1, 0);
    Ev ev[9];
    ev[0] = (Ev){0, 0, 0, 0, 1, 0, {0}};
    for (int r = 0; r < 8; ++r) ev[1 + r] = (Ev){1, r, 0, NONE, 0, 1, {FX(OPN_FX_SLIDE_UP, 0x01)}};
    return b_finish(&s, ev, 9);
}

static void test_vgm_loops(const char* dir) {
    printf("VGM loops: the file with its loop expanded = straight play's writes\n");
    char name[64];
    for (int chip = 0; chip < 2; ++chip) {
        const char* p = prefix(chip);
        EXPECT(build_wrap(&g_img, chip) == 0, "%s wrap song (%s)", chip_name(chip), g_img.info.problem);
        snprintf(name, sizeof name, "%s_loop_wrap", p);
        OpnVgmInfo info = check_loop(dir, name, &g_img, 20000, 1);
        EXPECT(info.loop_tick == 24 && info.ticks == 48,
               "%s: notes held across the wrap repeat from the second arrival: loop %u..%u (24..48)", name, info.loop_tick,
               info.ticks);
        EXPECT(build_volslide(&g_img, chip) == 0, "%s volume-slide song (%s)", chip_name(chip), g_img.info.problem);
        snprintf(name, sizeof name, "%s_loop_volslide", p);
        info = check_loop(dir, name, &g_img, 20000, 1);
        EXPECT(info.loop_tick > 27, "%s: the loop starts only once the slide has clamped (at tick %u, after the second "
               "arrival at 27)", name, info.loop_tick);
        EXPECT(build_slide(&g_img, chip) == 0, "%s pitch-slide song (%s)", chip_name(chip), g_img.info.problem);
        snprintf(name, sizeof name, "%s_loop_none", p);
        check_loop(dir, name, &g_img, 1200, 0);
        snprintf(name, sizeof name, "%s_song", p);
        check_loop(dir, name, &g_song[chip], 100000, 1);
    }
}

/* The hosts' entry point (opn_export.h): the renderer's VGM plus a GD3 tag. */
static void test_export(int chip) {
    printf("%s VGM export entry point\n", chip_name(chip));
    const OpnImage* song = &g_song[chip];
    size_t size = 0;
    const char* problem = NULL;
    const char* title = "Thor \xC3\x96pening \xF0\x9F\x8E\xB9"; /* 2-byte and 4-byte UTF-8 */
    uint8_t* vgm = opn_export_vgm(song->bytes, opn_image_size(chip), 100000, title, "Esko", "2026/11/08", &size, &problem);
    EXPECT(vgm && !problem, "renders (%s)", problem ? problem : "ok");
    if (!vgm) return;
    const uint32_t gd3_at = 0x14 + opn_u32(vgm + 0x14), eof = 4 + opn_u32(vgm + 4);
    EXPECT(gd3_at > 0x14 && gd3_at < size && eof == size && vgm[gd3_at - 1] == 0x66, "GD3 after the end command (at %u), EOF = size %zu",
           gd3_at, size);
    const uint8_t* g = vgm + gd3_at;
    const uint32_t len = opn_u32(g + 8);
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
                c = 0x10000 + ((c - 0xD800) << 10) + ((uint32_t)(s[0] | s[1] << 8) - 0xDC00);
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
    const char* system = chip == OPN_CHIP_YM2608 ? "PC-88/98 OPNA (Thor Tracker)" : "PC-88/98 OPN (Thor Tracker)";
    EXPECT(s == vgm + size, "11 strings fill the tag");
    EXPECT(!strcmp(fields[0], title) && !strcmp(fields[4], system) && !strcmp(fields[6], "Esko") && !strcmp(fields[8], "2026/11/08") &&
               !strcmp(fields[9], "Thor Tracker") && !fields[2][0] && !fields[10][0],
           "GD3 fields: '%s', '%s', '%s', '%s', '%s'", fields[0], fields[4], fields[6], fields[8], fields[9]);
    /* the data before the tag = the renderer's VGM */
    OpnVgmInfo info;
    const size_t plain = opn_vgm_render(song, 100000, g_vplayer, g_vgm, sizeof g_vgm, &info);
    EXPECT(plain == gd3_at && !memcmp(vgm + 0x18, g_vgm + 0x18, plain - 0x18) && !memcmp(vgm, g_vgm, 4),
           "the commands = opn_vgm_render's (%zu vs %u bytes)", plain, gd3_at);
    opn_export_free(vgm);
    memcpy(&g_bad, song, sizeof g_bad);
    g_bad.bytes[OPN_H_SPEED] = 0;
    vgm = opn_export_vgm(g_bad.bytes, opn_image_size(chip), 1000, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && problem && strstr(problem, "speed"), "bad image: NULL, '%s'", problem ? problem : "");
    vgm = opn_export_vgm(song->bytes, opn_image_size(chip) - 1, 1000, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && problem && !strcmp(problem, "image size"), "wrong size: '%s'", problem ? problem : "");
    vgm = opn_export_vgm(song->bytes, opn_image_size(chip), 100, NULL, NULL, NULL, &size, &problem);
    EXPECT(vgm && opn_u32(vgm + 0x1C) == 0 && opn_u32(vgm + 0x20) == 0, "tick limit before the loop: no loop");
    if (vgm) {
        const uint8_t* d = vgm + 0x14 + opn_u32(vgm + 0x14) + 12;
        for (int f = 0; f < 8; ++f, d += 2)
            while (d[0] | d[1]) d += 2;
        char date[16] = {0};
        for (int i = 0; i < 15 && (d[2 * i] | d[2 * i + 1]); ++i) date[i] = (char)d[2 * i];
        EXPECT(strlen(date) == 10 && date[4] == '/' && date[7] == '/' && !strncmp(date, "20", 2), "default date today: %s", date);
    }
    opn_export_free(vgm);
}

/* ---- the hosts' ops table ----------------------------------------------------------------------------------------- */

static void test_ops(int chip) {
    printf("%s ops table\n", chip_name(chip));
    const ChipEngineOps* ops = chip_engine_ops(engine_id(chip));
    EXPECT(ops && !strcmp(ops->system_id, engine_id(chip)), "registry: %s", engine_id(chip));
    if (!ops) return;
    EXPECT(ops->image_size == opn_image_size(chip) && ops->image_storage >= ops->image_size, "sizes (%zu, %zu)", ops->image_size,
           ops->image_storage);
    EXPECT(ops->create(CHIP_MAX_SAMPLE_RATE + 1) == NULL, "rate above the maximum rejected");
    ChipImage* image = (ChipImage*)calloc(1, ops->image_storage);
    const OpnImage* song = &g_song[chip];
    EXPECT(ops->load_image(image, song->bytes, ops->image_size - 1) == CHIP_ERR_IMAGE, "wrong size rejected");
    memcpy(&g_bad, song, sizeof g_bad);
    g_bad.bytes[0] = 'X';
    EXPECT(ops->load_image(image, g_bad.bytes, ops->image_size) == CHIP_ERR_IMAGE, "invalid rejected");
    memset(&g_bad, 0, sizeof g_bad);
    memcpy(g_bad.bytes, g_song[chip ^ 1].bytes, opn_image_size(chip ^ 1) < ops->image_size ? opn_image_size(chip ^ 1) : ops->image_size);
    EXPECT(ops->load_image(image, g_bad.bytes, ops->image_size) == CHIP_ERR_IMAGE, "the other chip's image rejected");
    EXPECT(ops->load_image(image, song->bytes, ops->image_size) == CHIP_OK, "song loads");
    ChipMachine* m = ops->create(RATE);
    int mode = -1, o, r, t;
    EXPECT(ops->start(m, image, 1, 4, SEEK_BUDGET, &mode) == CHIP_OK && mode == CHIP_SEEK_FROM_START, "start 1/4");
    ops->position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 4 && t == 0, "position 1/4/0");
    float out[CHIP_MAX_TICK_FRAMES * 2];
    int frames = 0, n = 0;
    for (int k = 0; k < 60; ++k) frames += (n = ops->tick(m, out));
    const double want = 60.0 * TICK_SAMPLES * RATE / NATIVE_HZ;
    EXPECT(fabs(frames - want) <= 2 && n >= 802 && n <= 804, "60 ticks = %d frames (%.1f)", frames, want);
    ops->position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 24 && t == 0, "20 rows later (%d/%d/%d)", o, r, t);
    ops->apply_image(m, image);
    EXPECT(ops->mute(m, 0, 1) == CHIP_OK && ops->mute(m, 0, 0) == CHIP_OK, "mute");
    EXPECT(ops->audition(m, image, 0, A4, 1, 0) == CHIP_OK, "audition");
    ops->tick(m, out);
    EXPECT(ops->release(m) == 1, "release phase");
    int ticks = 0;
    while (ops->tick(m, out) > 0 && ticks < 1000) ++ticks;
    EXPECT(ticks < 1000, "released note ends (%d ticks)", ticks);
    EXPECT(ops->audition(m, image, opn_channels(chip), A4, 1, 0) == CHIP_ERR_IMAGE, "audition channel past the chip rejected");
    ops->destroy(m);
    free(image);
}

/* ---- song audio, determinism, costs ------------------------------------------------------------------------------ */

static void test_song_audio(const char* dir, int chip, const RefTick* ref, double pk) {
    printf("%s song audio\n", chip_name(chip));
    double pk2;
    RefTick* again = record(chip, &g_song[chip], SONG_TICKS, &pk2);
    int same = again != NULL;
    for (int k = 0; same && k < SONG_TICKS; ++k) same = again[k].hash == ref[k].hash && again[k].frames == ref[k].frames;
    EXPECT(same, "bit-identical on a second start");
    free(again);
    EXPECT(pk > 0.01 && pk < 1.0, "non-silent, unclipped (peak %.4f)", pk);
    OpnMachine* m = opnm_create(chip, RATE);
    int mode;
    opnm_start(m, &g_song[chip], 0, 0, SEEK_BUDGET, &mode);
    float* buf;
    const int frames = render(m, SONG_TICKS, &buf);
    int quiet = 0;
    for (int s = 0; s + RATE / 2 <= frames; s += RATE / 2)
        if (rms(buf + 2 * s, RATE / 2) < 0.002) ++quiet;
    EXPECT(quiet == 0, "every half second non-silent (%d quiet)", quiet);
    char name[64];
    snprintf(name, sizeof name, "%s_song", prefix(chip));
    save_wav(dir, name, buf, frames);
    printf("  %.2f s, rms %.4f, peak %.4f\n", (double)frames / RATE, rms(buf, frames), pk);
    free(buf);
    opnm_destroy(m);
}

static void test_costs(int chip) {
    printf("%s costs\n", chip_name(chip));
    OpnMachine* m = opnm_create(chip, RATE);
    int mode;
    double t0 = now_us();
    opnm_start(m, &g_song[chip], 3, 20, SEEK_BUDGET, &mode);
    const double seek = now_us() - t0;
    opnm_start(m, &g_song[chip], 0, 0, SEEK_BUDGET, &mode);
    double sum = 0, worst = 0;
    for (int k = 0; k < 2 * SONG_TICKS; ++k) {
        t0 = now_us();
        opnm_tick(m, g_out);
        const double d = now_us() - t0;
        sum += d;
        if (d > worst) worst = d;
    }
    const double mean = sum / (2 * SONG_TICKS), budget = TICK_SAMPLES / NATIVE_HZ * 1e6;
    static OpnPlayer pl;
    opnp_reset(&pl, &g_song[chip]);
    t0 = now_us();
    for (int k = 0; k < 12000; ++k) opnp_tick(&pl, &g_song[chip]);
    const double per = (now_us() - t0) / 12000;
    printf("  seek to 3/20: %.0f us; tick at 48 kHz: mean %.1f us, max %.1f us (%.2f%% / %.2f%% of a %.0f us tick); "
           "player alone %.2f us per tick\n", seek, mean, worst, 100 * mean / budget, 100 * worst / budget, budget, per);
    opnm_destroy(m);
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    char path[512];
    snprintf(path, sizeof path, "%s/opn_evidence.txt", dir);
    g_manifest = fopen(path, "w");
    if (g_manifest) fprintf(g_manifest, "# kind name chip part channel note / song name chip ticks (opn_reference_check.py)\n");
    make_samples();
    test_images();
    test_pitch(dir);
    test_fm3(dir);
    test_ssg();
    test_rhythm_without_rom();
    test_adpcm();
    test_positions();
    test_audition();
    for (int chip = 0; chip < 2; ++chip) {
        EXPECT(build_song(&g_song[chip], chip, 0, -1) == 0, "%s song loads (%s)", chip_name(chip), g_song[chip].info.problem);
        EXPECT(build_song(&g_moved[chip], chip, 333, -1) == 0, "%s moved song loads (%s)", chip_name(chip), g_moved[chip].info.problem);
    }
    for (int chip = 0; chip < 2; ++chip) {
        double pk = 0;
        RefTick* ref = record(chip, &g_song[chip], SONG_TICKS, &pk);
        if (!ref) continue;
        test_song_audio(dir, chip, ref, pk);
        test_seek(chip, ref);
        test_live(chip, ref);
        test_mute(chip, ref);
        free(ref);
        test_vgm(dir, chip);
        test_export(chip);
        test_ops(chip);
        test_costs(chip);
    }
    test_vgm_loops(dir);
    test_rhythm_with_rom(dir);
    if (g_manifest) fclose(g_manifest);
    printf("OPN / OPNA: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
