/* Host regression test for the YM2612 (Mega Drive) system: Thor Tracker's own player (ym/ym_player.c) encoded as XGM2
 * FM and PSG frames (ym/ym_xgm.c), packed into two stream rings and executed by SGDK's real XGM2 Z80 driver on z80.h
 * with ymfm's YM2612 (the driver mixing three PCM channels into its DAC) and the PSG (ym/ym_psg.c), playing the
 * hand-built songs of tools/audio/ym/ym_song.py (ym_test_song.h, from tools/audio/prepare_ym.py): the tonal test song
 * (13 lanes, PCM lanes empty, FM 3 normal) and the PCM song (FM 3 special mode with operator offsets, two PCM kits on
 * three lanes, FM 6 under the DAC), each with its live-edit variants.
 *
 * Checks, by evidence item of the integration contract:
 * - old notes/effects/tuning/limits: the pitch tables; image validation (single-byte corruptions of the tonal prefix
 *   and every layout-2 extension, each rejected with its reason); player writes and PCM commands identical to the
 *   independent Python reference player (tools/audio/ym/ym_ref.py) in every tick for the song, PAL, the note edit,
 *   the tempo edit and the PCM song, alone and inside the machine; positions, heard positions and row_serial;
 *   deterministic, non-silent output; loop passes; seeks (exact rows, the order fallback, unreachable rows); live
 *   edits (re-layout before every tick, a note edit, tempo edits ahead of and behind the playhead, a song cut);
 *   audition (A-4 on FM and PSG, fine pitch, the kick sweep, release until silent, argument limits); mute; PAL.
 * - FM 3 per-operator pitch: ymfm sounds operator k at the register pair the player writes for it ($AD/$A9, $AE/$AA,
 *   $AC/$A8, $A6/$A2); in special mode each pair holds fm_frequency(root + offset k), the root being normal-mode
 *   play's; the driver's chip writes carry them; normal mode ignores the offsets; live edits switch special <-> normal.
 * - PCM: the driver's STATUS bits follow every trigger, stop, retrigger and sample end on all three channels (all
 *   three overlapping); delays, cuts, G == S, OFF and unmapped keys in the song; the pitch of a mapped variant
 *   measured against its own bytes at the driver's rate; the DAC bytes = the variant's signed bytes and its signed
 *   zero padding, then signed zero (silence); a PCM mute stops through the driver and suppresses triggers; FM 6 is
 *   inaudible while the DAC is enabled and audible after; live edits: re-laid-out variants continue sample-exactly
 *   (rebased, no retrigger), a changed variant stops, an identical image changes nothing.
 * - dual streams: every packed block the machine appends is decoded by an independent literal-LZ unpacker (page and
 *   bank rules, block and frame limits, frame ends, equal FM/PSG frame counts, both rings wrapping); a full six-patch
 *   frame with PCM, FM 3 special frequencies and the PSG stays within the limits; after every executed frame the
 *   chips hold exactly the player's register shadow.
 * - deterministic seek/restart/audition (two machines; seek = straight play, driver state and writes exact); no
 *   allocation by the realtime calls (glibc malloc interposition).
 * - exports: VGM (header, PCM data block, 0x8n count, loop at its 0xE0), XGM2 .xgm/.xgc (headers, sample table, sample
 *   data, both streams unpacked and compared frame by frame, loops on the loop frame, equal FM/PSG durations); the
 *   hosts' entry point with GD3; the "ym2612-fm" ops table; costs.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir]
 * Writes into <dir> for tools/audio/ym_reference_check.py: ym_image.bin, ym_trace.txt (per-tick player writes and PCM
 * commands in ym_ref.format_tick's format), ym_song.wav (48 kHz stereo, 30 s), ym_song.vgm, ym_song.xgm, ym_song.xgc
 * and the same for the PCM song (ym_pcm_image.bin, ym_pcm_trace.txt, ym_pcm_song.wav/.vgm/.xgm/.xgc); also
 * ym_song_pal.wav and the auditions ym_tone_fm.wav, ym_tone_psg.wav and ym_tone_pcm.wav. */
#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* ---- allocation counting -------------------------------------------------------------------------------------------
 * glibc: the test defines the allocator entry points (libstdc++'s operator new, ymfm's and blip_buf's allocations
 * all arrive here) and forwards them to glibc's own; while g_counting is set every call is counted. Single-threaded. */
#if defined(__GLIBC__)
extern void* __libc_malloc(size_t n);
extern void* __libc_calloc(size_t n, size_t size);
extern void* __libc_realloc(void* p, size_t n);
extern void __libc_free(void* p);
extern void* __libc_memalign(size_t align, size_t n);
static int g_counting;
static long g_allocs, g_frees;
void* malloc(size_t n) {
    if (g_counting) ++g_allocs;
    return __libc_malloc(n);
}
void* calloc(size_t n, size_t size) {
    if (g_counting) ++g_allocs;
    return __libc_calloc(n, size);
}
void* realloc(void* p, size_t n) {
    if (g_counting) ++g_allocs;
    return __libc_realloc(p, n);
}
void free(void* p) {
    if (g_counting && p) ++g_frees;
    __libc_free(p);
}
void* aligned_alloc(size_t align, size_t n) {
    if (g_counting) ++g_allocs;
    return __libc_memalign(align, n);
}
void* memalign(size_t align, size_t n) {
    if (g_counting) ++g_allocs;
    return __libc_memalign(align, n);
}
int posix_memalign(void** out, size_t align, size_t n) {
    if (g_counting) ++g_allocs;
    void* p = __libc_memalign(align, n);
    if (!p) return 12; /* ENOMEM */
    *out = p;
    return 0;
}
#define ALLOC_COUNTED 1
#else
#define ALLOC_COUNTED 0
#endif

#include "chip_engine.h"
#include "generated/ym_tables.h"
#include "ym_export.h"
#include "ym_machine.h"
#include "ym_test_song.h"
#include "ym_vgm.h"
#include "ym_xgm.h"
#include "ym_ymfm.h"
#include "z80.h"

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
#define SINE_SLOT 1
#define SQUARE_SLOT 2
#define KICK_SLOT 11
#define PLUCK_SLOT 14
#define TOM_SLOT 18
#define SEEK_BUDGET 12000
#define EXPORT_TICKS 36000
#define NTSC_TICK_HZ (53693175.0 / (3420.0 * 262.0))
#define PAL_TICK_HZ (53203424.0 / (3420.0 * 313.0))
#define MAX_IDS (YM_PCM_MAX_VARIANTS + 1)

static float g_out[YMM_MAX_TICK_FRAMES * 2];
static YmImage g_song, g_moved, g_edited, g_tempo, g_short, g_pal, g_pcm, g_pcm_moved, g_pcm_edited, g_bad;
static uint32_t g_pass_ticks; /* ticks of one loop pass of the song (test_loop) */

typedef struct Ref {
    const int* offsets;
    const unsigned char* kinds;
    const unsigned char* regs;
    const unsigned char* vals;
    const short* pos;
    const unsigned char* offs;
    const short* pcm;
} Ref;

#define REF(name) {ym_ref_##name##_offsets, ym_ref_##name##_kinds, ym_ref_##name##_regs, ym_ref_##name##_vals, \
                   ym_ref_##name##_pos, ym_ref_##name##_offs, ym_ref_##name##_pcm}
static const Ref REF_SONG = REF(song);
static const Ref REF_EDITED = REF(edited);
static const Ref REF_TEMPO = REF(tempo);
static const Ref REF_PAL = REF(pal);
static const Ref REF_PCM = REF(pcm);

/* ---- helpers ---------------------------------------------------------------------------------------------------- */

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

static uint32_t u16le(const uint8_t* p) { return (uint32_t)p[0] | (uint32_t)p[1] << 8; }
static uint32_t u24le(const uint8_t* p) { return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16; }
static uint32_t u32le(const uint8_t* p) { return u24le(p) | (uint32_t)p[3] << 24; }

static void write_file(const char* dir, const char* name, const void* data, size_t n) {
    char path[512];
    snprintf(path, sizeof path, "%s/%s", dir, name);
    FILE* f = fopen(path, "wb");
    if (!f) return;
    fwrite(data, 1, n, f);
    fclose(f);
}

static void write_wav(const char* dir, const char* name, const float* data, int frames) {
    char path[512];
    snprintf(path, sizeof path, "%s/%s", dir, name);
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

/* Frequency from rising zero crossings of x[0], x[stride], ... (n values at `rate`, mean removed). */
static double zero_cross(const float* x, int n, int stride, double rate) {
    double mean = 0;
    for (int i = 0; i < n; ++i) mean += x[(size_t)stride * i];
    mean /= n > 0 ? n : 1;
    int count = 0;
    double first_t = -1, last_t = 0;
    for (int i = 1; i < n; ++i) {
        const double a = x[(size_t)stride * (i - 1)] - mean, b = x[(size_t)stride * i] - mean;
        if (a < 0 && b >= 0) {
            const double t = (i - 1) + a / (a - b);
            if (first_t < 0) first_t = t;
            else {
                last_t = t;
                ++count;
            }
        }
    }
    return count ? count * rate / (last_t - first_t) : 0;
}

/* The left channel of interleaved stereo at RATE. */
static double zero_cross_hz(const float* x, int frames) { return zero_cross(x, frames, 2, RATE); }

static double rms(const float* x, int frames) {
    double s = 0;
    for (int i = 0; i < frames * 2; ++i) s += (double)x[i] * x[i];
    return frames > 0 ? sqrt(s / (frames * 2.0)) : 0;
}

static double peak(const float* x, int frames) {
    double p = 0;
    for (int i = 0; i < frames * 2; ++i)
        if (fabs(x[i]) > p) p = fabs(x[i]);
    return p;
}

static int render(YmMachine* m, int ticks, float** buf) {
    float* out = (float*)malloc((size_t)ticks * YMM_MAX_TICK_FRAMES * 2 * sizeof(float));
    int frames = 0;
    for (int t = 0; t < ticks; ++t) {
        int n = ymm_tick(m, out + 2 * frames);
        if (n <= 0) break;
        frames += n;
    }
    *buf = out;
    return frames;
}

/* Images: zero-fill, copy the fixture's non-zero runs, load. */
static int load_runs(YmImage* image, const unsigned* runs, int n, const unsigned char* bytes) {
    memset(image->bytes, 0, sizeof image->bytes);
    size_t at = 0;
    for (int i = 0; i < n; ++i) {
        memcpy(image->bytes + runs[2 * i], bytes + at, runs[2 * i + 1]);
        at += runs[2 * i + 1];
    }
    return ym_load_image(image);
}
#define LOAD(image, name, NAME) load_runs(image, ym_##name##_runs, YM_##NAME##_RUNS, ym_##name##_bytes)

/* A modified copy of a loaded image (`dst` = `src`, then the caller edits and reloads). */
static void copy_image(YmImage* dst, const YmImage* src) { memcpy(dst, src, sizeof *dst); }

static double tick_hz(const YmImage* image) { return image->info.region == YM_PAL ? PAL_TICK_HZ : NTSC_TICK_HZ; }
static uint32_t mclk_per_tick(const YmImage* image) {
    return YMM_MCLK_PER_LINE * (image->info.region == YM_PAL ? YMM_LINES_PAL : YMM_LINES_NTSC);
}
static double mclk_hz(const YmImage* image) { return image->info.region == YM_PAL ? YMM_MCLK_PAL : YMM_MCLK_NTSC; }
/* Driver output samples (full rate) per tick: one per YM_PCM_FULL_DIVIDER master clocks. */
static double pcm_per_tick(const YmImage* image) { return (double)mclk_per_tick(image) / YM_PCM_FULL_DIVIDER; }

static int ref_count(const Ref* r, int k) { return r->offsets[k + 1] - r->offsets[k]; }

/* A PCM command of an image whose variant ids map to the reference image's through `map` (NULL: the same ids). */
static int pcm_as_ref(int v, const int* map) { return v > 0 && map ? map[v] : v; }

/* Whether a player batch equals reference batch k (0 = reset, k = tick k - 1): writes, key-off phase, PCM commands. */
static int same_map(const YmPlayer* p, const Ref* r, int k, const int* map) {
    if (p->writes != ref_count(r, k) || p->offs != r->offs[k]) return 0;
    for (int i = 0; i < p->writes; ++i) {
        const int j = r->offsets[k] + i;
        if (p->write[i].kind != r->kinds[j] || p->write[i].reg != r->regs[j] || p->write[i].val != r->vals[j]) return 0;
    }
    for (int c = 0; c < YM_PCM_CHANNELS; ++c)
        if (pcm_as_ref(p->pcm[c], map) != r->pcm[3 * k + c]) return 0;
    return 1;
}
static int same(const YmPlayer* p, const Ref* r, int k) { return same_map(p, r, k, NULL); }

/* Reference tick index (batch k) whose position is (order, row, 0), first occurrence, or -1. */
static int ref_row_start(const Ref* r, int order, int row) {
    for (int k = 1; k <= YM_REF_TICKS; ++k)
        if (r->pos[3 * k] == order && r->pos[3 * k + 1] == row && r->pos[3 * k + 2] == 0) return k;
    return -1;
}

/* A reference batch at tick 0 of a row instance that straight play reaches there for the first time (a seek's target
 * lands on its first occurrence), about `fraction` into the trace (later if possible, else earlier). */
static int ref_far_row(const Ref* r, double fraction) {
    const int from = (int)(YM_REF_TICKS * fraction);
    for (int k = from; k <= YM_REF_TICKS; ++k)
        if (r->pos[3 * k + 2] == 0 && ref_row_start(r, r->pos[3 * k], r->pos[3 * k + 1]) == k) return k;
    for (int k = from; k > 1; --k)
        if (r->pos[3 * k + 2] == 0 && ref_row_start(r, r->pos[3 * k], r->pos[3 * k + 1]) == k) return k;
    return 1;
}

/* The machine keeps the player one tick ahead of the sound: after a start the player has run the target tick
 * (reference batch k), after each machine tick one more. pos_is: the player is about to run reference tick k (its
 * position is pos[k + 1]); heard_is: ymm_position (the tick the driver executes next) is reference batch k's. */
static int pos_is(const YmMachine* m, const Ref* r, int k) {
    const YmPlayer* p = ymm_player(m);
    return p->order == r->pos[3 * (k + 1)] && p->row == r->pos[3 * (k + 1) + 1] && p->tick == r->pos[3 * (k + 1) + 2];
}

static int heard_is(const YmMachine* m, const Ref* r, int k) {
    int o, w, t;
    ymm_position(m, &o, &w, &t);
    return o == r->pos[3 * k] && w == r->pos[3 * k + 1] && t == r->pos[3 * k + 2];
}

static double fm_hz(int region, int a4, int a0) {
    const int fnum = (a4 & 7) << 8 | a0, block = a4 >> 3 & 7;
    const double fs = (region ? YMM_MCLK_PAL : YMM_MCLK_NTSC) / 1008.0;
    return fnum * fs / (double)(1 << (21 - block));
}

static double psg_hz(int region, int n) { return (region ? YMM_MCLK_PAL : YMM_MCLK_NTSC) / 15.0 / (32.0 * n); }

/* fm_frequency of the player's specification: the $A4/$A0 pair (a4 << 8 | a0) of `units` (1/64 semitone from C-0). */
static int fm_pair(int region, int units) {
    const int oct = units / 768, frac = units % 768;
    int fnum = YM_FNUM[region][frac], block = oct - (frac < YM_FNUM_SPLIT[region] ? 1 : 0);
    if (block < 0) {
        block = 0;
        fnum >>= 1;
    }
    return (block << 3 | fnum >> 8) << 8 | (fnum & 0xFF);
}

static int clampi(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

/* ---- PCM variants of an image -------------------------------------------------------------------------------------- */

static const uint8_t* variant_bytes(const YmImage* im, int id, int* len, int* flags) {
    int start, pages;
    ym_pcm_region(im, id, &start, &pages, flags);
    *len = pages * YM_PCM_ALIGN;
    return im->bytes + YM_OFF_PCM_BANK + (size_t)start * YM_PCM_ALIGN;
}

static int variant_start(const YmImage* im, int id) {
    int start, pages, flags;
    ym_pcm_region(im, id, &start, &pages, &flags);
    return start;
}

/* The variant of `b` with the same compiled bytes, length and rate as variant `id` of `a`, 0 = none. */
static int same_variant(const YmImage* a, int id, const YmImage* b) {
    int la, fa;
    const uint8_t* pa = variant_bytes(a, id, &la, &fa);
    for (int i = 1; i <= b->info.pcm_variants; ++i) {
        int lb, fb;
        const uint8_t* pb = variant_bytes(b, i, &lb, &fb);
        if (la == lb && fa == fb && !memcmp(pa, pb, (size_t)la)) return i;
    }
    return 0;
}

/* A PCM slot and note mapped to variant `id`. */
static int find_key(const YmImage* im, int id, int* slot, int* note) {
    for (int s = 1; s <= im->info.instruments; ++s)
        for (int x = 0; x < YM_PCM_KEYS && ym_instrument(im, s, YM_KIND_PCM); ++x)
            if (ym_pcm_variant(im, s, x) == id) {
                *slot = s;
                *note = x;
                return 1;
            }
    return 0;
}

/* A PCM slot with an unmapped note. */
static int find_unmapped(const YmImage* im, int* slot, int* note) {
    for (int s = 1; s <= im->info.instruments; ++s)
        for (int x = 0; x < YM_PCM_KEYS && ym_instrument(im, s, YM_KIND_PCM); ++x)
            if (!ym_pcm_variant(im, s, x)) {
                *slot = s;
                *note = x;
                return 1;
            }
    return 0;
}

static int find_slot(const YmImage* im, int kind) {
    for (int s = 1; s <= im->info.instruments; ++s)
        if (ym_instrument(im, s, kind)) return s;
    return 0;
}

/* The variant PCM channel k plays (0 = none), its byte offset in the variant and the bytes left of it, from the
 * driver's STATUS bit and channel address (68000 address of the next byte) in `im`'s bank. */
static int playing_variant(const YmMachine* m, const YmImage* im, int k, int* offset, int* left) {
    if (!(ymm_pcm_playing(m) >> k & 1)) return 0;
    const uint32_t addr = u24le(ymm_z80_ram(m) + YMM_PCM_VARS + 8 * k);
    if (addr < YMM_SAMPLES) return 0;
    const uint32_t at = addr - YMM_SAMPLES;
    for (int id = 1; id <= im->info.pcm_variants; ++id) {
        int start, pages, flags;
        ym_pcm_region(im, id, &start, &pages, &flags);
        const uint32_t from = (uint32_t)start * YM_PCM_ALIGN, to = from + (uint32_t)pages * YM_PCM_ALIGN;
        if (at >= from && at < to) {
            *offset = (int)(at - from);
            *left = (int)(to - at);
            return id;
        }
    }
    return 0;
}

/* ---- XGM2 streams: an independent decoder (SGDK bin/xgm2.txt) --------------------------------------------------- */

typedef struct FrameInfo {
    int wait;     /* frames the end waits; 0 = a loop/end command */
    int is_loop;
    int32_t loop; /* its offset, -1 = end */
    int end_bytes;
    int load_inst, special, pcm_count, late, offs;
    int pcm_ch[8], pcm_id[8], pcm_half[8];
    int psg_tone, psg_att, psg_noise;
    const uint8_t* load[6]; /* the load-instrument commands' first data byte */
    int load_slot[6];
} FrameInfo;

/* Length of the command at c (first byte only), or -1 (a code the format does not define). */
static int command_length(int b, int psg) {
    const int hi = b >> 4;
    if (psg) {
        if (hi == 0) return b <= 0x0D ? 1 : b == 0x0E ? 2 : 4;
        if (hi <= 3) return 2;
        return 1;
    }
    switch (hi) {
    case 0x0: return b == 0x0F ? 2 : 1;
    case 0x1: return 2;
    case 0x2: return 31;
    case 0x3: case 0x8: return 3;
    case 0x4: case 0x5: case 0x6: case 0x7: return 1;
    case 0x9: case 0xA: case 0xB: case 0xC: case 0xD: return 2;
    case 0xE: return 1 + 2 * ((b & 7) + 1);
    default:
        switch (b) {
        case 0xF0: case 0xFA: case 0xFB: case 0xFC: case 0xFD: return 1;
        case 0xF8: case 0xF9: return 2;
        case 0xFF: return 4;
        }
        return -1;
    }
}

/* Interprets one whole command (len bytes): statistics, and *end when it ends the frame. */
static void command_info(const uint8_t* c, int psg, FrameInfo* fi, int* end) {
    const int b = c[0], hi = b >> 4;
    *end = 0;
    if (psg) {
        if (hi == 0) {
            *end = 1;
            if (b == 0x0F) {
                fi->is_loop = 1;
                fi->loop = u24le(c + 1) == 0xFFFFFFu ? -1 : (int32_t)u24le(c + 1);
                fi->end_bytes = 4;
            } else {
                fi->wait = b == 0x0E ? c[1] + 15 : b + 1;
                fi->end_bytes = b == 0x0E ? 2 : 1;
            }
        } else if (hi == 1) {
            if ((c[1] & 0xF0) == 0xE0) ++fi->psg_noise;
            if (b & 1) *end = 1, fi->wait = 1, fi->end_bytes = 2;
        } else if (hi == 2 || hi == 3) {
            ++fi->psg_tone;
            if (hi == 3) *end = 1, fi->wait = 1, fi->end_bytes = 2;
        } else if (hi >= 8 && hi <= 0xB) {
            ++fi->psg_att;
        } else if (b & 8) {
            *end = 1, fi->wait = 1, fi->end_bytes = 1;
        }
        return;
    }
    switch (hi) {
    case 0x0:
        *end = 1;
        fi->wait = b == 0x0F ? c[1] + 16 : b + 1;
        fi->end_bytes = b == 0x0F ? 2 : 1;
        break;
    case 0x1:
        if (fi->pcm_count < 8) {
            fi->pcm_ch[fi->pcm_count] = b & 3;
            fi->pcm_half[fi->pcm_count] = (b >> 2) & 1;
            fi->pcm_id[fi->pcm_count] = c[1];
        }
        ++fi->pcm_count;
        break;
    case 0x2:
        if (fi->load_inst < 6) {
            fi->load[fi->load_inst] = c + 1;
            fi->load_slot[fi->load_inst] = b & 7;
        }
        ++fi->load_inst;
        break;
    case 0x3:
        if (b & 8) ++fi->special;
        break;
    case 0x4:
        if (!(b & 8)) ++fi->offs;
        break;
    case 0x8: case 0xB: case 0xD:
        *end = 1;
        fi->wait = 1;
        fi->end_bytes = hi == 0x8 ? 3 : 2;
        break;
    case 0xF:
        if (b == 0xF8) ++fi->late;
        if (b == 0xFF) {
            *end = 1;
            fi->is_loop = 1;
            fi->loop = u24le(c + 1) == 0xFFFFFFu ? -1 : (int32_t)u24le(c + 1);
            fi->end_bytes = 4;
        }
        break;
    }
}

/* One unpacked frame: whole commands, exactly one frame end and it last, at most YMX_FRAME_MAX bytes. Returns NULL
 * or the problem. */
static const char* parse_frame(const uint8_t* f, int n, int psg, FrameInfo* fi) {
    memset(fi, 0, sizeof *fi);
    fi->loop = -1;
    if (n < 1 || n > YMX_FRAME_MAX) return "frame size outside 1..255";
    int at = 0;
    while (at < n) {
        const int len = command_length(f[at], psg);
        if (len < 0) return "a command code the format does not define";
        if (at + len > n) return "a command runs past its frame";
        int end;
        command_info(f + at, psg, fi, &end);
        at += len;
        if (end) return at == n ? NULL : "a frame end before the frame's last command";
    }
    return "a frame without an end";
}

/* Length of the unpacked frame at s (commands up to its end), or -1. */
static int frame_length(const uint8_t* s, uint32_t left, int psg) {
    uint32_t at = 0;
    FrameInfo fi;
    memset(&fi, 0, sizeof fi);
    while (at < left) {
        const int len = command_length(s[at], psg);
        if (len < 0 || at + (uint32_t)len > left) return -1;
        int end;
        command_info(s + at, psg, &fi, &end);
        at += (uint32_t)len;
        if (end) return (int)at;
    }
    return -1;
}

/* One packed block at stream offset `at` (the literal-only LZ form: header LLLMMMMM, literal bytes, 0x00 end): the
 * literal chunks must not cross a 256-byte page (nor a 32 KiB bank), a page mark (MMMMM = 1) must end exactly at its
 * page end, and no match (MMMMM >= 2) may appear. `at` = NO_PAGE: the block's ring offset is unknown, the page and
 * bank rules are not checked (test_streams checks them). Returns the packed bytes consumed, or -1 with *why. */
#define NO_PAGE 0xFFFFFFFFu
static int unpack_block(const uint8_t* in, uint32_t avail, uint32_t at, uint8_t* out, int cap, int* n, const char** why) {
    const int paged = at != NO_PAGE;
    uint32_t i = 0;
    *n = 0;
    for (;;) {
        if (i >= avail) {
            *why = "packed block without its end byte";
            return -1;
        }
        const uint32_t pos = at + i;
        const int h = in[i++];
        if (h == 0) return (int)i;
        const int lit = h >> 5, mat = h & 31;
        if (mat >= 2) {
            *why = "a match in a literal-only block";
            return -1;
        }
        if (paged && (pos & 0xFF) + 1 + (uint32_t)lit > 0x100) {
            *why = "a literal chunk crosses a 256-byte page";
            return -1;
        }
        if (paged && pos >> 15 != (pos + (uint32_t)lit) >> 15) {
            *why = "a literal chunk crosses a 32 KiB bank";
            return -1;
        }
        if (paged && mat == 1 && ((pos + 1 + (uint32_t)lit) & 0xFF) != 0) {
            *why = "a page mark not at its page end";
            return -1;
        }
        if (i + (uint32_t)lit > avail) {
            *why = "literal past the block";
            return -1;
        }
        if (*n + lit > cap) {
            *why = "block unpacks above its buffer";
            return -1;
        }
        memcpy(out + *n, in + i, (size_t)lit);
        *n += lit;
        i += (uint32_t)lit;
    }
}

/* The FM frame the machine appended last, decoded; 0 if it does not decode. */
static int last_fm_frame(const YmMachine* m, FrameInfo* fi) {
    int size, n;
    const char* why = NULL;
    uint8_t un[512];
    const uint8_t* b = ymm_last_fm(m, &size);
    return unpack_block(b, (uint32_t)size, NO_PAGE, un, sizeof un, &n, &why) >= 0 && !parse_frame(un, n, 0, fi);
}

/* Commands for PCM channel k in the FM frame the machine appended last: stops (id 0) and triggers. */
static void last_pcm_commands(const YmMachine* m, int k, int* stops, int* triggers) {
    FrameInfo fi;
    *stops = *triggers = 0;
    if (!last_fm_frame(m, &fi)) return;
    for (int i = 0; i < fi.pcm_count && i < 8; ++i)
        if (fi.pcm_ch[i] == k) {
            if (fi.pcm_id[i]) ++*triggers;
            else ++*stops;
        }
}

/* ---- the chips' registers from the driver's writes, against the player's shadow --------------------------------- */

typedef struct Snap {
    uint8_t ym[2][256];
    uint16_t tone[3];
    uint8_t att[4];
    uint8_t noise;
} Snap;

typedef struct ChipState {
    Snap s;
    int latch; /* PSG: channel << 1 | type of the last latch byte */
} ChipState;

/* The player's register shadow (padding zeroed: snapshots compare with memcmp). */
static void snap_player(Snap* s, const YmPlayer* p) {
    memset(s, 0, sizeof *s);
    memcpy(s->ym, p->ym, sizeof s->ym);
    memcpy(s->tone, p->tone, sizeof s->tone);
    memcpy(s->att, p->att, sizeof s->att);
    s->noise = p->noise_ctrl;
}

static void chip_write(ChipState* c, int kind, int reg, int val) {
    if (kind != YM_W_PSG) {
        c->s.ym[kind][reg] = (uint8_t)val;
        return;
    }
    if (val & 0x80) c->latch = (val >> 4) & 7;
    const int ch = c->latch >> 1;
    if (c->latch & 1) c->s.att[ch] = (uint8_t)(val & 15);
    else if (ch == 3) c->s.noise = (uint8_t)(val & 7);
    else if (val & 0x80) c->s.tone[ch] = (uint16_t)((c->s.tone[ch] & 0x3F0) | (val & 15));
    else c->s.tone[ch] = (uint16_t)((c->s.tone[ch] & 15) | (val & 0x3F) << 4);
}

/* The chips after SGDK's resets (the machine's power-on writes). */
static void chip_init(ChipState* c) {
    memset(c, 0, sizeof *c);
    YmWrite w[YMP_MACHINE_RESET_WRITES];
    const int n = ymp_machine_reset(w);
    for (int i = 0; i < n; ++i) chip_write(c, w[i].kind, w[i].reg, w[i].val);
}

static void chip_log(ChipState* c, const YmMachine* m) {
    int n;
    const YmmLogEntry* log = ymm_log(m, &n);
    for (int i = 0; i < n; ++i) chip_write(c, log[i].kind, log[i].reg, log[i].val);
}

/* Registers the chips hold that differ from the player's shadow; *first describes the first. The driver writes a
 * frequency command's high byte with its key flags in bits 7-6 ($A4-$A6, $AC-$AE compared & 0x3F), owns $27's timer
 * bits (only bit 6, FM 3 special, compared) and the DAC ($2A/$2B not compared); TL and SL/RR are its attenuation
 * state's rewrites, equal to the shadow at attenuation 0. */
static int chip_diff(const ChipState* c, const Snap* p, char* first, size_t n) {
    int bad = 0;
#define CMP(chip, player, ...)                                       \
    do {                                                             \
        if ((chip) != (player) && !bad++) snprintf(first, n, __VA_ARGS__); \
    } while (0)
    for (int port = 0; port < 2; ++port)
        for (int ch = 0; ch < 3; ++ch) {
            for (int reg = 0x30 + ch; reg < 0xA0; reg += 4)
                CMP(c->s.ym[port][reg], p->ym[port][reg], "port %d $%02X = %02X, player %02X", port, reg, c->s.ym[port][reg],
                    p->ym[port][reg]);
            CMP(c->s.ym[port][0xA0 + ch], p->ym[port][0xA0 + ch], "port %d $%02X", port, 0xA0 + ch);
            CMP(c->s.ym[port][0xA4 + ch] & 0x3F, p->ym[port][0xA4 + ch], "port %d $%02X = %02X, player %02X", port, 0xA4 + ch,
                c->s.ym[port][0xA4 + ch], p->ym[port][0xA4 + ch]);
            CMP(c->s.ym[port][0xB0 + ch], p->ym[port][0xB0 + ch], "port %d $%02X", port, 0xB0 + ch);
            CMP(c->s.ym[port][0xB4 + ch], p->ym[port][0xB4 + ch], "port %d $%02X = %02X, player %02X", port, 0xB4 + ch,
                c->s.ym[port][0xB4 + ch], p->ym[port][0xB4 + ch]);
        }
    for (int i = 0; i < 3; ++i) {
        CMP(c->s.ym[0][0xA8 + i], p->ym[0][0xA8 + i], "$%02X (FM 3 special)", 0xA8 + i);
        CMP(c->s.ym[0][0xAC + i] & 0x3F, p->ym[0][0xAC + i], "$%02X (FM 3 special)", 0xAC + i);
    }
    CMP(c->s.ym[0][0x22], p->ym[0][0x22], "$22 = %02X, player %02X", c->s.ym[0][0x22], p->ym[0][0x22]);
    CMP(c->s.ym[0][0x27] & 0x40, p->ym[0][0x27] & 0x40, "$27 bit 6 = %d, player %d", c->s.ym[0][0x27] >> 6 & 1,
        p->ym[0][0x27] >> 6 & 1);
    for (int i = 0; i < 3; ++i) CMP(c->s.tone[i], p->tone[i], "PSG tone %d = %d, player %d", i, c->s.tone[i], p->tone[i]);
    for (int i = 0; i < 4; ++i) CMP(c->s.att[i], p->att[i], "PSG attenuation %d = %d, player %d", i, c->s.att[i], p->att[i]);
    CMP(c->s.noise & 7, p->noise & 7, "PSG noise control %d, player %d", c->s.noise & 7, p->noise & 7);
#undef CMP
    return bad;
}

/* Per-tick checks of a machine started at (0, 0): the packed FM and PSG blocks it appended (decoded independently,
 * their ring offsets, frame ends and wraps) and, whenever the driver has no lag, the chips (rebuilt from the driver's
 * logged writes) against the player's shadow after the last executed frame. Needs ymm_set_log(m, 1) before the start. */
typedef struct StreamCheck {
    const uint8_t* base[2]; /* ring starts: the stream start's blocks are at offset 0 */
    uint32_t next[2];
    int wraps[2];
    long frames[2];
    int bad, first_bad_tick;
    const char* why;
    int max_fm, max_psg, max_packed;
    long pcm_cmds;
    ChipState chip;
    Snap snap[256];
    uint32_t encoded;
    int compared, diff_ticks, first_diff_tick;
    char first_diff[128];
    long special_writes, dac_on_writes, dac_writes;
} StreamCheck;

static void stream_tick(StreamCheck* sc, const YmMachine* m, int tick) {
    for (int s = 0; s < 2; ++s) {
        int size;
        const uint8_t* b = s ? ymm_last_psg(m, &size) : ymm_last_fm(m, &size);
        const uint32_t off = (uint32_t)(b - sc->base[s]);
        uint8_t un[512];
        int n = 0;
        const char* why = NULL;
        FrameInfo fi;
        if (off != sc->next[s]) why = "a block not where the previous one ended";
        else if (size < 1 || size > YMX_PACKED_MAX) why = "a packed block above YMX_PACKED_MAX";
        else {
            const int used = unpack_block(b, (uint32_t)size, off, un, sizeof un, &n, &why);
            if (used >= 0 && used != size) why = "the block's end byte is not its last";
        }
        if (!why) why = parse_frame(un, n, s, &fi);
        if (!why) {
            const int wrap = off + 2u * YMX_PACKED_MAX > YMM_STREAM_SIZE;
            if (fi.is_loop) {
                if (fi.loop != 0 || !wrap) why = "a loop command away from the ring end or not to the ring start";
                else ++sc->wraps[s];
            } else if (fi.wait != 1 || wrap) {
                why = "a frame end other than one frame's wait before the ring end";
            }
            if (s == 0 && n - fi.end_bytes > YMX_CMDS_MAX) why = "FM frame commands above YMX_CMDS_MAX";
            ++sc->frames[s];
            if (s == 0 && n > sc->max_fm) sc->max_fm = n;
            if (s == 1 && n > sc->max_psg) sc->max_psg = n;
            if (size > sc->max_packed) sc->max_packed = size;
            if (s == 0) sc->pcm_cmds += fi.pcm_count;
            sc->next[s] = fi.is_loop ? 0 : off + (uint32_t)size;
        } else {
            sc->next[s] = off + (uint32_t)size;
        }
        if (why && !sc->bad++) {
            sc->why = why;
            sc->first_bad_tick = tick;
        }
    }
    int count;
    const YmmLogEntry* log = ymm_log(m, &count);
    for (int i = 0; i < count; ++i) {
        if (log[i].kind == YM_W_PORT0) {
            if (log[i].reg >= 0xA8 && log[i].reg <= 0xAE && log[i].reg != 0xAB && (sc->chip.s.ym[0][0x27] & 0x40)) ++sc->special_writes;
            if (log[i].reg == 0x2B && (log[i].val & 0x80)) ++sc->dac_on_writes;
            if (log[i].reg == 0x2A) ++sc->dac_writes;
        }
        chip_write(&sc->chip, log[i].kind, log[i].reg, log[i].val);
    }
    snap_player(&sc->snap[sc->encoded % 256], ymm_player(m));
    ++sc->encoded;
    if (sc->encoded >= 2 && ymm_lag(m) == 0) {
        char what[128];
        ++sc->compared;
        if (chip_diff(&sc->chip, &sc->snap[(sc->encoded - 2) % 256], what, sizeof what) && !sc->diff_ticks++) {
            sc->first_diff_tick = tick;
            snprintf(sc->first_diff, sizeof sc->first_diff, "%s", what);
        }
    }
}

static void stream_begin(StreamCheck* sc, const YmMachine* m) {
    memset(sc, 0, sizeof *sc);
    int n;
    sc->base[0] = ymm_last_fm(m, &n);
    sc->base[1] = ymm_last_psg(m, &n);
    sc->first_bad_tick = sc->first_diff_tick = -1;
    chip_init(&sc->chip);
    stream_tick(sc, m, 0);
}

/* ---- tests ---------------------------------------------------------------------------------------------------- */

static void test_tables(void) {
    printf("pitch tables\n");
    for (int region = 0; region < 2; ++region) {
        double worst = 0, worst_psg = 0;
        for (int n = 0; n < YM_NOTES; ++n) {
            const int u = n * 64, oct = u / 768, f = u % 768;
            int fnum = YM_FNUM[region][f], block = oct - (f < YM_FNUM_SPLIT[region] ? 1 : 0);
            if (block < 0) block = 0, fnum >>= 1;
            EXPECT(fnum < 2048 && block <= 7, "note %d registers", n);
            const double want = 440.0 * pow(2.0, (n - A4) / 12.0);
            const double cents = fabs(1200.0 * log2(fm_hz(region, block << 3 | fnum >> 8, fnum & 0xFF) / want));
            if (n >= 12 && cents > worst) worst = cents;
            const int nn = (int)((YM_PSG_DIV[region][f] + (128u << oct)) >> (8 + oct));
            if (n >= 45 && n <= 81) { /* A-2..A-6: the 10-bit period's useful range */
                const double c = fabs(1200.0 * log2(psg_hz(region, nn) / want));
                if (c > worst_psg) worst_psg = c;
            }
        }
        const int a4 = A4 * 64 % 768;
        const int fnum = YM_FNUM[region][a4];
        const double hz = fm_hz(region, 4 << 3 | fnum >> 8, fnum & 0xFF);
        const int n = (int)((YM_PSG_DIV[region][a4] + (128u << 4)) >> 12);
        EXPECT(a4 >= YM_FNUM_SPLIT[region] && fnum == (region ? 1093 : 1083), "A-4 F-number %d at block 4", fnum);
        EXPECT(fabs(hz - 440.0) < 0.2, "A-4 FM register frequency %.3f Hz", hz);
        EXPECT(n == (region ? 252 : 254) && fabs(psg_hz(region, n) - 440.0) < 0.5, "A-4 PSG period %d = %.2f Hz", n,
               psg_hz(region, n));
        EXPECT(worst < 1.0, "%s: every FM note from C-1 within 1 cent of 12-TET (worst %.2f)", region ? "PAL" : "NTSC", worst);
        EXPECT(worst_psg < 14.0, "%s: PSG A-2..A-6 within 14 cents (10-bit periods; worst %.2f)", region ? "PAL" : "NTSC", worst_psg);
        printf("  %s: A-4 = block 4 F-number %d = %.3f Hz, PSG %d = %.2f Hz; FM notes within %.2f cents, PSG %.1f\n",
               region ? "PAL" : "NTSC", fnum, hz, n, psg_hz(region, n), worst, worst_psg);
    }
}

static void corrupt_in(const YmImage* src, int offset, int value, const char* want) {
    copy_image(&g_bad, src);
    g_bad.bytes[offset] = (uint8_t)value;
    const int rc = ym_load_image(&g_bad);
    EXPECT(rc != 0 && g_bad.info.problem && strstr(g_bad.info.problem, want), "byte %d = %d rejected for \"%s\" (got %s)",
           offset, value, want, rc ? g_bad.info.problem : "accepted");
}
static void corrupt(int offset, int value, const char* want) { corrupt_in(&g_song, offset, value, want); }

#define INST(slot) (YM_OFF_INSTRUMENTS + YM_INSTRUMENT_SIZE * ((slot) - 1))
#define CH3(slot) (YM_OFF_CH3 + 8 * ((slot) - 1))
#define KEYS(slot) (YM_OFF_PCM_KEYS + YM_PCM_KEYS * ((slot) - 1))
#define VARIANT(id) (YM_OFF_PCM_VARIANTS + YM_PCM_VARIANT_SIZE * ((id) - 1))

/* Image offset of envelope k (0 volume, 1 arpeggio, 2 pitch) of a PSG slot in the test song. */
static int env_at(int slot, int k) { return YM_OFF_DATA + ym_u16(g_song.bytes + INST(slot) + YM_I_ENV_VOLUME + 2 * k); }

/* The PCM song's layout-2 extensions: slots, keys, variants and PCM patterns. */
static void test_pcm_images(void) {
    const YmImage* p = &g_pcm;
    const int pcm_slot = find_slot(p, YM_KIND_PCM), fm_slot = find_slot(p, YM_KIND_FM), v = p->info.pcm_variants;
    EXPECT(pcm_slot && fm_slot && v >= 2 && p->info.pcm_pages > 0 && p->info.pcm_data_size > 0,
           "PCM song: a PCM slot (%d), an FM slot (%d), %d variants in %d pages, %d PCM pattern bytes", pcm_slot, fm_slot, v,
           p->info.pcm_pages, p->info.pcm_data_size);
    EXPECT(p->info.fm3 == YM_FM3_SPECIAL, "PCM song: FM 3 special mode");
    if (!pcm_slot || !fm_slot || v < 2) return;
    int slot, note;
    if (find_key(p, 1, &slot, &note)) corrupt_in(p, KEYS(slot) + note, v + 1, "PCM key maps a variant past the variant count");
    corrupt_in(p, INST(pcm_slot) + 1, 1, "PCM instrument reserved bytes");
    corrupt_in(p, INST(pcm_slot) + YM_I_FLAGS, YM_FLAG_DEFINED, "PCM key map of a slot that is not a PCM instrument");
    corrupt_in(p, CH3(pcm_slot), 1, "FM 3 operator offsets of a slot that is not an FM instrument");
    corrupt_in(p, VARIANT(2), p->bytes[VARIANT(2)] + 1, "PCM variants must follow each other in id order");
    if (p->bytes[VARIANT(1) + 3] == 0) corrupt_in(p, VARIANT(1) + 2, 0, "at least one page");
    corrupt_in(p, VARIANT(1) + 4, 2, "PCM variant flags: only bit 0");
    corrupt_in(p, VARIANT(1) + 5, 1, "PCM variant reserved bytes");
    {
        int start, pages, flags;
        ym_pcm_region(p, 1, &start, &pages, &flags);
        if (!flags) {
            int len, f;
            const uint8_t* b = variant_bytes(p, 1, &len, &f);
            int second_half = 0;
            for (int i = len / 2; i < len; ++i) second_half |= b[i];
            if (pages & 1) corrupt_in(p, VARIANT(1) + 4, YM_PCM_HALF_RATE, "half-rate PCM variant needs an even page count");
            else if (second_half) corrupt_in(p, VARIANT(1) + 4, YM_PCM_HALF_RATE, "half-rate PCM variant needs its zeroed second half");
        }
    }
    corrupt_in(p, YM_H_PCM_VARIANTS, v - 1, "PCM variant entry past the variant count");
    if ((p->info.pcm_pages & 0xFF) != 0xFF && p->info.pcm_pages < YM_PCM_BANK_PAGES)
        corrupt_in(p, YM_H_PCM_BANK_SIZE, (p->info.pcm_pages & 0xFF) + 1, "PCM bank size differs from its variants");
    if (p->info.pcm_pages & 0xFF) corrupt_in(p, YM_H_PCM_BANK_SIZE, (p->info.pcm_pages & 0xFF) - 1, "PCM variant past the bank size");
    if (p->info.pcm_pages < YM_PCM_BANK_PAGES)
        corrupt_in(p, YM_OFF_PCM_BANK + p->info.pcm_pages * YM_PCM_ALIGN, 1, "PCM bank bytes past the bank size");
    if (p->info.pcm_data_size < YM_PCM_DATA_CAPACITY)
        corrupt_in(p, YM_OFF_PCM_DATA + p->info.pcm_data_size, 1, "PCM data bytes past the PCM data size");
#ifdef YM_PCM_OFFSET_NOTE_PCM
    corrupt_in(p, YM_PCM_OFFSET_NOTE_PCM, 96, "note byte");
#else
    EXPECT(0, "the PCM song has a PCM note (YM_PCM_OFFSET_NOTE_PCM)");
#endif
#ifdef YM_PCM_OFFSET_INST_PCM
    corrupt_in(p, YM_PCM_OFFSET_INST_PCM, fm_slot, "FM or PSG instrument on a PCM channel");
#else
    EXPECT(0, "the PCM song names a PCM instrument (YM_PCM_OFFSET_INST_PCM)");
#endif
#ifdef YM_PCM_OFFSET_INST_FM
    corrupt_in(p, YM_PCM_OFFSET_INST_FM, pcm_slot, "PCM instrument on an FM or PSG channel");
#endif
#ifdef YM_PCM_OFFSET_EFFECT_PCM
    corrupt_in(p, YM_PCM_OFFSET_EFFECT_PCM, YM_FX_VOLUME, "effect not available on a PCM channel");
    corrupt_in(p, YM_PCM_OFFSET_EFFECT_PCM, YM_FX_SLIDE_UP, "effect not available on a PCM channel");
#else
    EXPECT(0, "the PCM song has a PCM effect (YM_PCM_OFFSET_EFFECT_PCM)");
#endif
#ifdef YM_PCM_OFFSET_PARAM_F_PCM
    corrupt_in(p, YM_PCM_OFFSET_PARAM_F_PCM, 0, "F00");
#endif
#ifdef YM_PCM_OFFSET_PARAM_L_PCM
    corrupt_in(p, YM_PCM_OFFSET_PARAM_L_PCM, 0x07, "Lxx");
#endif
#ifdef YM_PCM_OFFSET_PARAM_B_PCM
    corrupt_in(p, YM_PCM_OFFSET_PARAM_B_PCM, p->info.orders, "Bxx");
#endif
    copy_image(&g_bad, p);
    EXPECT(ym_load_image(&g_bad) == 0 && !memcmp(&g_bad.info, &p->info, sizeof g_bad.info), "PCM song: loading is a pure function");
}

static void test_images(void) {
    printf("images\n");
    EXPECT(g_song.info.loaded == YM_LOADED && g_song.info.orders == 4 && g_song.info.loop == 1, "song info");
    EXPECT(g_song.info.rows[0] == 16 && g_song.info.rows[1] == 32 && g_song.info.rows[2] == 16 && g_song.info.rows[3] == 24,
           "rows per order");
    EXPECT(g_song.info.region == YM_NTSC && g_pal.info.region == YM_PAL && g_song.info.lfo == 0x0B, "region, LFO");
    EXPECT(g_song.info.fm3 == YM_FM3_NORMAL && g_song.info.pcm_variants == 0 && g_song.info.pcm_pages == 0,
           "the tonal song: FM 3 normal, no PCM");
    EXPECT(g_song.bytes[YM_H_LAYOUT] == YM_LAYOUT, "layout 2");
    corrupt(0, 'X', "magic");
    corrupt(YM_H_LAYOUT, 1, "layout");
    corrupt(YM_H_LAYOUT, 3, "layout");
    corrupt(YM_H_REGION, 2, "region");
    corrupt(YM_H_SPEED, 0, "speed");
    corrupt(YM_H_LFO, 0x07, "LFO");
    corrupt(YM_H_LFO, 0x10, "LFO");
    corrupt(YM_H_ORDERS, 0, "order count");
    corrupt(YM_H_LOOP, 4, "loop");
    corrupt(YM_H_INSTRUMENTS, 65, "instrument count");
    corrupt(YM_H_INSTRUMENTS, 18, "past the instrument count");
    corrupt(13, 1, "reserved byte 13");
    corrupt(YM_H_DATA_SIZE + 1, 0xFF, "data size above the region");
    corrupt(YM_H_FM3, 2, "FM 3 mode");
    corrupt(YM_H_PCM_VARIANTS, YM_PCM_MAX_VARIANTS + 1, "PCM variant count above 123");
    corrupt(YM_H_PCM_BANK_SIZE + 1, 0x11, "PCM bank size above 1 MiB");
    corrupt(YM_H_PCM_DATA_SIZE + 2, 2, "PCM data size above the region");
    corrupt(24, 1, "header reserved bytes 24..63");
    corrupt(63, 1, "header reserved bytes 24..63");
    if (g_song.info.data_size < (int)YM_DATA_CAPACITY) corrupt(YM_OFF_DATA + g_song.info.data_size, 1, "tonal data bytes past the data size");
    corrupt(YM_OFF_ROWS + 9, 4, "past the order count");
    corrupt(YM_OFF_ORDERS + 1, 0xF0, "pattern offset");
    corrupt(YM_OFF_PCM_ORDERS + 1, 0xF0, "pattern offset past the data size");
    corrupt(YM_OFF_PCM_ORDERS + 2 * (YM_PCM_CHANNELS * 5), 1, "patterns of an order past the order count");
    corrupt(INST(3) + YM_I_FLAGS, 0x83, "instrument flags");
    corrupt(INST(3) + YM_I_FLAGS, 0x01, "instrument flags");
    corrupt(INST(3) + YM_I_FLAGS, 0x82, "PCM instrument reserved bytes");
    corrupt(INST(3) + YM_I_ALG, 8, "algorithm");
    corrupt(INST(3) + YM_I_FEEDBACK, 8, "feedback");
    corrupt(INST(3) + YM_I_AMS_FMS, 0x08, "AMS/FMS");
    corrupt(INST(3) + YM_I_OPS + 0, 0x80, "reserved bits");      /* $30 bit 7 */
    corrupt(INST(3) + YM_I_OPS + 1, 0x80, "reserved bits");      /* $40 bit 7 */
    corrupt(INST(3) + YM_I_OPS + 2, 0x20, "reserved bits");      /* $50 bit 5 */
    corrupt(INST(3) + YM_I_OPS + 3, 0x40, "reserved bits");      /* $60 bit 6 */
    corrupt(INST(3) + YM_I_OPS + 4, 0x20, "reserved bits");      /* $70 bit 5 */
    corrupt(INST(3) + YM_I_OPS + 6, 0x10, "reserved bits");      /* $90 bit 4 */
    corrupt(INST(11) + YM_I_SWEEP_START, 97, "sweep");
    corrupt(INST(3) + 36, 1, "reserved bytes 35");
    corrupt(CH3(3) + 1, 0x0D, "FM 3 operator offset outside -3072..3072");   /* +3328 */
    corrupt(CH3(3) + 7, 0xF3, "FM 3 operator offset outside -3072..3072");   /* -3328 */
    corrupt(CH3(12), 1, "FM 3 operator offsets of a slot that is not an FM instrument");
    corrupt(KEYS(1) + A4, 1, "PCM key map of a slot that is not a PCM instrument");
    corrupt(VARIANT(1), 1, "PCM variant entry past the variant count");
    corrupt(YM_OFF_PCM_VARIANTS + YM_PCM_VARIANT_SIZE * YM_PCM_MAX_VARIANTS, 1, "PCM variant table padding");
    if (g_song.info.pcm_data_size < YM_PCM_DATA_CAPACITY)
        corrupt(YM_OFF_PCM_DATA + g_song.info.pcm_data_size, 1, "PCM data bytes past the PCM data size");
    corrupt(YM_OFF_PCM_BANK, 1, "PCM bank bytes past the bank size");
    corrupt(YM_OFF_PCM_BANK + YM_PCM_BANK_SIZE - 1, 1, "PCM bank bytes past the bank size");
    corrupt(INST(12) + YM_I_PSG_NOISE, 8, "noise mode");
    corrupt(INST(12) + YM_I_PSG_FLAGS, 2, "PSG flags");
    corrupt(INST(12) + 3, 1, "reserved byte 3");
    corrupt(INST(12) + 10, 1, "reserved bytes 10");
    corrupt(INST(12) + YM_I_ENV_VOLUME + 1, 0xF0, "envelope outside the data size");
    corrupt(env_at(12, 0), 0, "envelope length");
    corrupt(env_at(12, 0) + 1, 1, "envelope length");                   /* 268 */
    corrupt(env_at(12, 0), 200, "envelope outside the data size");
    corrupt(env_at(12, 0) + 2, 12, "loop past its end");
    corrupt(env_at(12, 0) + 4, 7, "needs a loop before it");            /* release = loop */
    corrupt(env_at(12, 0) + 4, 12, "needs a loop before it");           /* release = length */
    corrupt(env_at(14, 0) + 4, 4, "needs a loop before it");            /* release without a loop */
    corrupt(env_at(13, 1) + 4, 1, "release point on an arpeggio");
    corrupt(env_at(12, 0) + YM_ENV_HEADER, 16, "volume envelope value");
    corrupt(env_at(12, 2) + YM_ENV_HEADER, 64, "pitch envelope value");
    corrupt(env_at(13, 1) + YM_ENV_HEADER, 0xBF, "pitch envelope value"); /* -65 */
    corrupt(INST(3) + YM_I_FLAGS, 0, "flags");
    corrupt(YM_OFFSET_MASK, 0x20, "mask");
    corrupt(YM_OFFSET_MASK, 0x17, "mask");
    corrupt(YM_OFFSET_NOTE, 96, "note");
    corrupt(YM_OFFSET_INST_FM, 21, "does not define");
    corrupt(YM_OFFSET_INST_FM, 12, "PSG instrument on an FM");
    corrupt(YM_OFFSET_INST_PSG, 3, "FM instrument on a PSG");
    corrupt(YM_OFFSET_EFFECT, 36, "effect code");
    corrupt(YM_OFFSET_EFFECT, 5, "unknown effect");
    corrupt(YM_OFFSET_EFFECT, 17, "unknown effect"); /* H: no chords on this system */
    corrupt(YM_OFFSET_PARAM_B_FM, 4, "Bxx");
    corrupt(YM_OFFSET_PARAM_C_FM, 0x80, "Cxx");
    corrupt(YM_OFFSET_PARAM_C_PSG, 0x10, "Cxx");
    corrupt(YM_OFFSET_PARAM_E_FM, 8, "Exx");
    corrupt(YM_OFFSET_PARAM_F_FM, 0, "F00");
    corrupt(YM_OFFSET_PARAM_I_FM, 0x80, "Ixx");
    corrupt(YM_OFFSET_PARAM_L_FM, 0x07, "Lxx");
    corrupt(YM_OFFSET_PARAM_M_FM, 0x53, "Mxy");
    corrupt(YM_OFFSET_PARAM_N_NOISE, 0x23, "Nxy");
    corrupt(YM_OFFSET_PARAM_N_NOISE, 0x04, "Nxy");
    corrupt(YM_OFFSET_PARAM_A_PSG - 1, YM_FX_PAN, "8xy pan on a PSG");
    corrupt(YM_OFFSET_PARAM_A_PSG - 1, YM_FX_FEEDBACK, "Exx feedback on a PSG");
    corrupt(YM_OFFSET_PARAM_A_PSG - 1, YM_FX_NOISE, "Nxy noise mode outside");
    copy_image(&g_bad, &g_song);
    EXPECT(ym_load_image(&g_bad) == 0 && !memcmp(&g_bad.info, &g_song.info, sizeof g_bad.info), "loading is a pure function");
    test_pcm_images();
}

/* The player alone against the reference (reset batch included): writes, positions and PCM commands. */
static void test_player(const Ref* ref, const YmImage* image, const char* name) {
    static YmPlayer p;
    ymp_reset(&p, image);
    EXPECT(same(&p, ref, 0), "%s: reset batch = reference (%d writes)", name, p.writes);
    int bad = 0, first = -1;
    for (int k = 1; k <= YM_REF_TICKS; ++k) {
        if (p.order != ref->pos[3 * k] || p.row != ref->pos[3 * k + 1] || p.tick != ref->pos[3 * k + 2]) ++bad;
        ymp_tick(&p, image);
        if (!same(&p, ref, k)) {
            if (first < 0) first = k;
            ++bad;
        }
    }
    EXPECT(bad == 0, "%s: writes, PCM commands and positions = reference player in all %d ticks (%d differ, first at tick %d)",
           name, YM_REF_TICKS, bad, first - 1);
    if (first > 0) {
        ymp_reset(&p, image);
        for (int k = 1; k <= first; ++k) ymp_tick(&p, image);
        printf("  tick %d: player", first - 1);
        for (int i = 0; i < p.writes && i < 16; ++i) printf(" %d:%02X=%02X", p.write[i].kind, p.write[i].reg, p.write[i].val);
        printf(" P %d %d %d\n  reference", p.pcm[0], p.pcm[1], p.pcm[2]);
        for (int i = 0; i < ref_count(ref, first) && i < 16; ++i) {
            const int j = ref->offsets[first] + i;
            printf(" %d:%02X=%02X", ref->kinds[j], ref->regs[j], ref->vals[j]);
        }
        printf(" P %d %d %d\n", ref->pcm[3 * first], ref->pcm[3 * first + 1], ref->pcm[3 * first + 2]);
    }
}

/* One trace line (tools/audio/ym/ym_ref.py's format_tick): the position the tick ran at, the writes (" |" after a
 * non-empty key-off phase), the PCM commands. */
static void trace_line(FILE* f, int order, int row, int tick, const YmPlayer* p) {
    fprintf(f, "%d %d %d:", order, row, tick);
    for (int i = 0; i < p->writes; ++i) {
        if (p->write[i].kind == YM_W_PSG) fprintf(f, " PSG=%02X", p->write[i].val);
        else fprintf(f, " %d%02X=%02X", p->write[i].kind, p->write[i].reg, p->write[i].val);
        if (i + 1 == p->offs) fputs(" |", f);
    }
    for (int c = 0; c < YM_PCM_CHANNELS; ++c)
        if (p->pcm[c] != YMP_PCM_NONE) fprintf(f, " P%d=%d", c, p->pcm[c]);
    fputc('\n', f);
}

/* The machine against the reference: the player's batches (one tick ahead) and positions, the heard positions (lagging
 * behind heavy frames), row_serial, the DAC. Writes the trace evidence. */
static void test_machine(const char* dir, const Ref* ref, const YmImage* image, const char* name, const char* trace_name,
                         int tonal) {
    printf("machine (%s): reference player, positions\n", name);
    static int row_starts[YM_REF_TICKS + 1];
    row_starts[0] = 0;
    for (int k = 1; k <= YM_REF_TICKS; ++k) row_starts[k] = row_starts[k - 1] + (ref->pos[3 * k + 2] == 0);
    YmMachine* m = ymm_create(RATE);
    ymm_set_log(m, 1);
    int mode = -1;
    EXPECT(ymm_start(m, image, 0, 0, SEEK_BUDGET, &mode) == YMM_OK && mode == YMM_SEEK_FROM_START, "%s: start", name);
    const YmPlayer* p = ymm_player(m);
    EXPECT(same(p, ref, 1), "%s: the player has run tick 0 (frames one tick ahead)", name);
    EXPECT(heard_is(m, ref, 1) && ymm_row_serial(m) == 1, "%s: heard position 0/0/0, row serial 1", name);
    char path[512];
    snprintf(path, sizeof path, "%s/%s", dir, trace_name);
    FILE* trace = fopen(path, "w");
    if (trace) trace_line(trace, 0, 0, 0, p);
    int mismatches = 0, first = -1, position_errors = 0, heard_errors = 0, serial_errors = 0, max_writes = 0, writes = 0;
    int fm6_keyed = 0, max_lag = 0, lag_ticks = 0, dac_on = 0;
    int heard = 1; /* reference batch the heard position must be at (it may stall, never skip back) */
    double kick[12] = {0};
    const int kick_start = ref_row_start(ref, 1, 0);
    for (int k = 1; k < YM_REF_TICKS; ++k) {
        if (!pos_is(m, ref, k)) ++position_errors;
        const int lag = ymm_lag(m);
        if (!heard_is(m, ref, k - lag)) ++heard_errors;
        if (ymm_row_serial(m) != (uint32_t)row_starts[k - lag]) ++serial_errors;
        if (lag > max_lag) max_lag = lag;
        if (lag) ++lag_ticks;
        if (k - lag < heard) ++heard_errors;
        heard = k - lag;
        const int o = p->order, r = p->row, t = p->tick;
        ymm_tick(m, g_out);
        int nlog;
        const YmmLogEntry* log = ymm_log(m, &nlog);
        for (int i = 0; i < nlog; ++i)
            if (log[i].kind == YM_W_PORT0 && log[i].reg == 0x2B && (log[i].val & 0x80)) ++dac_on;
        if (!same(p, ref, k + 1)) {
            if (first < 0) first = k;
            ++mismatches;
        }
        writes += p->writes;
        if (p->writes > max_writes) max_writes = p->writes;
        if (p->ym[1][0xB6] && (p->ym[1][0xA6] || p->ym[1][0xA2])) fm6_keyed = 1;
        if (trace) trace_line(trace, o, r, t, p);
        if (k + 1 >= kick_start && k + 1 < kick_start + 12) kick[k + 1 - kick_start] = fm_hz(0, p->ym[0][0xA4], p->ym[0][0xA0]);
    }
    if (trace) fclose(trace);
    EXPECT(mismatches == 0, "%s: player writes and PCM commands = reference in all %d ticks (%d differ, first at tick %d)", name,
           YM_REF_TICKS, mismatches, first);
    EXPECT(position_errors == 0, "%s: player positions = reference in every tick (%d differ)", name, position_errors);
    EXPECT(heard_errors == 0, "%s: heard positions = the reference's, lagging behind heavy frames (%d wrong)", name, heard_errors);
    EXPECT(serial_errors == 0, "%s: row_serial counts the heard row starts (%d wrong)", name, serial_errors);
    EXPECT(max_lag <= 6, "%s: lag behind heavy frames at most 6 ticks (%d)", name, max_lag);
    if (tonal) {
        EXPECT(dac_on == 0, "%s: the driver keeps the DAC off without PCM (%d enables)", name, dac_on);
        EXPECT(fm6_keyed, "%s: FM 6 plays notes", name);
        EXPECT(kick[0] > 500 && kick[0] > kick[3] && kick[3] > kick[6] && fabs(kick[9] - 65.41) < 1.0,
               "%s: kick sweeps C-5 -> C-2 (%.1f %.1f %.1f %.1f Hz)", name, kick[0], kick[3], kick[6], kick[9]);
    } else {
        EXPECT(dac_on > 0, "%s: the driver enables the DAC for PCM", name);
    }
    printf("  %d ticks, %d player writes (max %d per tick); driver lag: max %d ticks, %d ticks lagging; %d DAC enables\n",
           YM_REF_TICKS, writes, max_writes, max_lag, lag_ticks, dac_on);
    ymm_destroy(m);
}

/* Dual streams and the driver's execution: every block decoded, both rings wrapped `wraps` times, the chips = the
 * player's shadow after every executed frame. */
static void test_streams(const YmImage* image, const char* name, int wraps, int tonal) {
    printf("streams and driver (%s)\n", name);
    YmMachine* m = ymm_create(RATE);
    ymm_set_log(m, 1);
    int mode;
    EXPECT(ymm_start(m, image, 0, 0, SEEK_BUDGET, &mode) == YMM_OK, "%s: start", name);
    static StreamCheck sc;
    stream_begin(&sc, m);
    int ticks = 0, fault = 0;
    for (; ticks < 60000 && (sc.wraps[0] < wraps || sc.wraps[1] < wraps); ++ticks) {
        if (ymm_tick(m, g_out) <= 0) {
            fault = 1;
            break;
        }
        stream_tick(&sc, m, ticks + 1);
    }
    EXPECT(!fault && sc.wraps[0] >= wraps && sc.wraps[1] >= wraps, "%s: %d ticks, FM ring wrapped %d times, PSG ring %d, no fault",
           name, ticks, sc.wraps[0], sc.wraps[1]);
    EXPECT(sc.bad == 0, "%s: every packed block decodes to one whole frame under the page/bank rules (%d bad, first at tick %d: %s)",
           name, sc.bad, sc.first_bad_tick, sc.why ? sc.why : "");
    EXPECT(sc.frames[0] == sc.frames[1] && sc.frames[0] == ticks + 1, "%s: one FM and one PSG frame per tick (%ld, %ld, %d ticks)",
           name, sc.frames[0], sc.frames[1], ticks + 1);
    EXPECT(sc.max_fm <= YMX_FRAME_MAX && sc.max_psg <= YMX_FRAME_MAX && sc.max_packed <= YMX_PACKED_MAX,
           "%s: frames within 255 bytes (FM %d, PSG %d), blocks within %d (%d)", name, sc.max_fm, sc.max_psg, YMX_PACKED_MAX,
           sc.max_packed);
    EXPECT(sc.compared > ticks / 2 && sc.diff_ticks == 0,
           "%s: after every executed frame the chips hold the player's register shadow (%d compared, %d differ, first at tick %d: %s)",
           name, sc.compared, sc.diff_ticks, sc.first_diff_tick, sc.first_diff);
    if (tonal) {
        EXPECT(sc.special_writes == 0 && sc.pcm_cmds == 0 && sc.dac_on_writes == 0, "%s: no FM 3 special, PCM or DAC activity", name);
    } else {
        EXPECT(sc.special_writes > 0, "%s: the driver writes the FM 3 operator frequencies ($A8-$AE) in special mode (%ld)", name,
               sc.special_writes);
        EXPECT(sc.pcm_cmds > 0 && sc.dac_on_writes > 0 && sc.dac_writes > 0, "%s: PCM commands streamed (%ld), DAC enabled, %ld DAC bytes",
               name, sc.pcm_cmds, sc.dac_writes);
    }
    printf("  %d ticks: rings wrapped %d (FM) / %d (PSG) times; largest frames FM %d, PSG %d, block %d bytes; %d chip states compared\n",
           ticks, sc.wraps[0], sc.wraps[1], sc.max_fm, sc.max_psg, sc.max_packed, sc.compared);
    ymm_destroy(m);
}

/* A full six-patch frame: all six FM channels keyed off, reloaded, FM 3's special frequencies, every channel keyed
 * on and late key-offs, the LFO and FM 3 mode, the PSG's tones, noise and attenuations and three PCM triggers in one
 * tick: within the limits, packed at page and bank ends, and the load-instrument data in SGDK's FM_loadInst order. */
static void test_frame_stress(void) {
    printf("full frame stress\n");
    static YmPlayer p;
    ymp_reset(&p, &g_pcm);
    p.fm3 = YM_FM3_SPECIAL;
    static YmWrite w[YMP_MAX_WRITES];
    int n = 0;
    for (int c = 0; c < YM_FM_CHANNELS; ++c) w[n++] = (YmWrite){YM_W_PORT0, 0x28, (uint8_t)((c / 3) * 4 + c % 3)};
    p.ym[0][0x27] = 0x40;
    p.ym[0][0x22] = 0x0B;
    w[n++] = (YmWrite){YM_W_PORT0, 0x27, 0x40};
    w[n++] = (YmWrite){YM_W_PORT0, 0x22, 0x0B};
    for (int c = 0; c < YM_FM_CHANNELS; ++c) {
        const int port = c / 3, ch = c % 3;
        for (int reg = 0x30 + ch; reg < 0xA0; reg += 4) {
            const uint8_t v = (uint8_t)((reg * 7 + c * 13) & 0x7F);
            p.ym[port][reg] = v;
            w[n++] = (YmWrite){(uint8_t)port, (uint8_t)reg, v};
        }
        const uint8_t regs[4] = {(uint8_t)(0xB0 + ch), (uint8_t)(0xB4 + ch), (uint8_t)(0xA4 + ch), (uint8_t)(0xA0 + ch)};
        const uint8_t vals[4] = {(uint8_t)(c & 7), 0xC0, (uint8_t)(0x20 + c), (uint8_t)(0x40 + c)};
        for (int i = 0; i < 4; ++i) {
            p.ym[port][regs[i]] = vals[i];
            w[n++] = (YmWrite){(uint8_t)port, regs[i], vals[i]};
        }
        if (c == YM_CH_FM3)
            for (int i = 0; i < 3; ++i) {
                p.ym[0][0xAC + i] = (uint8_t)(0x18 + i);
                p.ym[0][0xA8 + i] = (uint8_t)(0x90 + i);
                w[n++] = (YmWrite){YM_W_PORT0, (uint8_t)(0xAC + i), (uint8_t)(0x18 + i)};
                w[n++] = (YmWrite){YM_W_PORT0, (uint8_t)(0xA8 + i), (uint8_t)(0x90 + i)};
            }
    }
    for (int c = 0; c < YM_FM_CHANNELS; ++c) w[n++] = (YmWrite){YM_W_PORT0, 0x28, (uint8_t)(0xF0 | ((c / 3) * 4 + c % 3))};
    for (int c = 0; c < YM_FM_CHANNELS; ++c) w[n++] = (YmWrite){YM_W_PORT0, 0x28, (uint8_t)((c / 3) * 4 + c % 3)};
    for (int i = 0; i < 3; ++i) {
        p.tone[i] = (uint16_t)(0x155 + 100 * i);
        p.att[i] = (uint8_t)(3 + i);
        w[n++] = (YmWrite){YM_W_PSG, 0, (uint8_t)(0x80 | i << 5 | (p.tone[i] & 15))};
        w[n++] = (YmWrite){YM_W_PSG, 0, (uint8_t)(p.tone[i] >> 4)};
        w[n++] = (YmWrite){YM_W_PSG, 0, (uint8_t)(0x90 | i << 5 | p.att[i])};
    }
    p.noise_ctrl = 7;
    p.att[3] = 2;
    w[n++] = (YmWrite){YM_W_PSG, 0, 0xE7};
    w[n++] = (YmWrite){YM_W_PSG, 0, 0xF2};
    const int v = g_pcm.info.pcm_variants;
    const int pcm[3] = {v >= 1 ? 1 : YMP_PCM_STOP, v >= 2 ? 2 : YMP_PCM_STOP, v >= 3 ? 3 : YMP_PCM_STOP};
    static YmxFrame f;
    for (int full = 0; full < 2; ++full) {
        EXPECT(ymx_encode(&g_pcm, &p, w, n, pcm, full, &f) == 0, "%s frame encodes", full ? "full" : "touched-everything");
        uint8_t frame[YMX_FRAME_MAX + 8];
        memcpy(frame, f.fm, (size_t)f.fm_size);
        const int size = f.fm_size + ymx_wait(frame + f.fm_size, 0, 1);
        FrameInfo fi;
        const char* why = parse_frame(frame, size, 0, &fi);
        EXPECT(!why && f.fm_size <= YMX_CMDS_MAX, "FM frame of %d command bytes <= %d, well formed (%s)", f.fm_size, YMX_CMDS_MAX,
               why ? why : "ok");
        EXPECT(fi.load_inst == 6 && fi.special == 3 && fi.pcm_count == 3 && fi.offs == 6 && fi.late == 6,
               "six load-instrument commands (%d), three FM 3 special frequencies (%d), three PCM commands (%d), six key-offs "
               "(%d), six late key writes (%d)", fi.load_inst, fi.special, fi.pcm_count, fi.offs, fi.late);
        int load_bad = 0;
        static const uint8_t base[7] = {0x30, 0x40, 0x50, 0x60, 0x70, 0x80, 0x90};
        for (int i = 0; i < fi.load_inst && i < 6; ++i) {
            const int port = fi.load_slot[i] >> 2 & 1, ch = fi.load_slot[i] & 3;
            const uint8_t* d = fi.load[i];
            int j = 0;
            for (int r = 0; r < 7; ++r)
                for (int s = 0; s < 16; s += 4) load_bad += d[j++] != p.ym[port][base[r] + s + ch];
            load_bad += d[j++] != p.ym[port][0xB0 + ch];
            load_bad += d[j] != p.ym[port][0xB4 + ch];
        }
        EXPECT(load_bad == 0, "load-instrument data = the shadow in FM_loadInst order (%d bytes differ)", load_bad);
        static const uint32_t at[] = {0, 0xFE, 0xFF, 0x7FF0, 0x7FFF, 0x80FB, 0xFD00};
        for (size_t i = 0; i < sizeof at / sizeof at[0]; ++i) {
            uint8_t packed[YMX_PACKED_MAX + 16], un[512];
            const size_t ps = ymx_pack(frame, size, at[i], packed);
            int un_n = 0;
            const char* pw = NULL;
            const int used = unpack_block(packed, (uint32_t)ps, at[i], un, sizeof un, &un_n, &pw);
            EXPECT(ps <= YMX_PACKED_MAX && used == (int)ps && un_n == size && !memcmp(un, frame, (size_t)size),
                   "packed at $%05X: %zu bytes, unpacks to the frame (%s)", at[i], ps, pw ? pw : "ok");
        }
        uint8_t psg[YMX_FRAME_MAX + 8];
        memcpy(psg, f.psg, (size_t)f.psg_size);
        const int psize = f.psg_size + ymx_wait(psg + f.psg_size, 1, 1);
        why = parse_frame(psg, psize, 1, &fi);
        EXPECT(!why && fi.psg_tone == 3 && fi.psg_att == 4 && fi.psg_noise == 1, "PSG frame: 3 tones, noise, 4 attenuations (%s)",
               why ? why : "ok");
        printf("  %s: FM frame %d bytes, PSG frame %d bytes\n", full ? "full" : "touched", f.fm_size, f.psg_size);
    }
}

static void test_audio(const char* dir, const YmImage* image, const char* wav, int every_second) {
    printf("audio (%s)\n", wav);
    const double hz = tick_hz(image);
    const int ticks = (int)(SECONDS * hz + 0.5);
    uint64_t hash[2] = {0, 0};
    for (int pass = 0; pass < 2; ++pass) {
        YmMachine* m = ymm_create(RATE);
        int mode;
        ymm_start(m, image, 0, 0, SEEK_BUDGET, &mode);
        float* buf;
        const double t0 = now_us();
        const int frames = render(m, ticks, &buf);
        const double us = (now_us() - t0) / ticks;
        hash[pass] = fnv(0xCBF29CE484222325ull, buf, (size_t)frames * 2 * sizeof(float));
        if (pass == 0) {
            const double r = rms(buf, frames), pk = peak(buf, frames);
            const int want = (int)(ticks * RATE / hz + 0.5);
            EXPECT(abs(frames - want) <= 2, "%d ticks at %.3f Hz = %d frames (got %d)", ticks, hz, want, frames);
            EXPECT(r > 0.02, "non-silent (rms %.4f)", r);
            EXPECT(pk < 0.999, "no clipping (peak %.4f)", pk);
            if (every_second) {
                int quiet = 0;
                for (int s = 0; s < SECONDS; ++s)
                    if (rms(buf + 2 * s * RATE, RATE) < 0.005) ++quiet;
                EXPECT(quiet == 0, "every second non-silent (%d quiet)", quiet);
            }
            write_wav(dir, wav, buf, frames);
            printf("  30 s: rms %.4f, peak %.4f, %.1f us per tick (%.0fx realtime)\n", r, pk, us, 1e6 / hz / us);
        }
        free(buf);
        ymm_destroy(m);
    }
    EXPECT(hash[0] == hash[1], "bit-identical on a second start");
}

/* Every pass of the loop plays like the first (entered from order 0 at speed 6 and by B01 from order 3). */
static void test_loop(void) {
    printf("loop passes\n");
    enum { MAX_PASS = 1024 };
    static int pos[4][MAX_PASS];
    int len[4] = {0};
    YmMachine* m = ymm_create(RATE);
    int mode, o, r, t, pass = -1;
    ymm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    const YmPlayer* p = ymm_player(m);
    for (int k = 0; k < 4 * MAX_PASS && pass < 4; ++k) {
        o = p->order, r = p->row, t = p->tick;
        if (o == g_song.info.loop && r == 0 && t == 0) ++pass;
        ymm_tick(m, g_out);
        if (pass < 0 || pass >= 4 || len[pass] >= MAX_PASS) continue;
        pos[pass][len[pass]++] = o << 16 | r << 8 | t;
    }
    EXPECT(pass == 4 && len[0] == len[1] && len[1] == len[2] && len[0] < MAX_PASS, "passes of %d, %d, %d ticks", len[0],
           len[1], len[2]);
    EXPECT(!memcmp(pos[0], pos[1], sizeof(int) * (size_t)len[0]) && !memcmp(pos[1], pos[2], sizeof(int) * (size_t)len[0]),
           "every pass steps through the same (order, row, tick) positions");
    g_pass_ticks = (uint32_t)len[0];
    printf("  %d ticks per pass\n", len[0]);
    ymm_destroy(m);
}

/* Tick cost must be flat: after 1 s of warm-up, 30 s with no tick above 3x the mean (thread CPU time). */
static void test_outliers(const YmImage* image, const char* name) {
    printf("tick cost outliers (%s)\n", name);
    const int ticks = SECONDS * 60;
    double* cpu = (double*)malloc(sizeof(double) * (size_t)ticks);
    YmMachine* m = ymm_create(RATE);
    int mode;
    ymm_start(m, image, 0, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 60; ++k) ymm_tick(m, g_out);
    double sum = 0, worst = 0;
    int worst_at = 0;
    for (int k = 0; k < ticks; ++k) {
        const double c0 = cpu_us();
        ymm_tick(m, g_out);
        cpu[k] = cpu_us() - c0;
        sum += cpu[k];
        if (cpu[k] > worst) worst = cpu[k], worst_at = k;
    }
    const double mean = sum / ticks;
    EXPECT(worst < 3 * mean, "%s: no tick above 3x the mean: worst %.1f us (tick %d) vs mean %.1f us", name, worst, worst_at, mean);
    printf("  thread CPU per tick: mean %.1f us, worst %.1f us (%.2fx, tick %d)\n", mean, worst, worst / mean, worst_at);
    ymm_destroy(m);
    free(cpu);
}

static int z80_same(const z80_t* a, const z80_t* b) {
    return a->step == b->step && a->addr == b->addr && a->dlatch == b->dlatch && a->opcode == b->opcode &&
           a->hlx_idx == b->hlx_idx && a->prefix_active == b->prefix_active && a->pins == b->pins &&
           a->int_bits == b->int_bits && a->pc == b->pc && a->af == b->af && a->bc == b->bc && a->de == b->de &&
           a->hl == b->hl && a->ix == b->ix && a->iy == b->iy && a->wz == b->wz && a->sp == b->sp && a->ir == b->ir &&
           a->af2 == b->af2 && a->bc2 == b->bc2 && a->de2 == b->de2 && a->hl2 == b->hl2 && a->im == b->im &&
           a->iff1 == b->iff1 && a->iff2 == b->iff2;
}

/* The last tick's chip writes of two machines are the same (cycle, port/PSG, register, value). */
static int logs_equal(const YmMachine* a, const YmMachine* b) {
    int na, nb;
    const YmmLogEntry* la = ymm_log(a, &na);
    const YmmLogEntry* lb = ymm_log(b, &nb);
    if (na != nb) return 0;
    for (int i = 0; i < na; ++i)
        if (la[i].cycle != lb[i].cycle || la[i].kind != lb[i].kind || la[i].reg != lb[i].reg || la[i].val != lb[i].val) return 0;
    return 1;
}

/* A seek runs the whole machine from the song start: at the landing point the Z80 (registers and RAM, so the driver's
 * PCM channels too) is the straight-played machine's, and every chip write after it (with its cycle) is the same. */
static void seek_vs_straight(const YmImage* image, const char* name, int k) {
    YmMachine* a = ymm_create(RATE);
    YmMachine* b = ymm_create(RATE);
    ymm_set_log(a, 1);
    ymm_set_log(b, 1);
    int mode;
    ymm_start(a, image, 0, 0, SEEK_BUDGET, &mode);
    for (int j = 1; j < k; ++j) ymm_tick(a, g_out);
    const YmPlayer* pa = ymm_player(a);
    int to, tr, tt;
    ymm_position(a, &to, &tr, &tt);
    const double t0 = now_us();
    const int rc = ymm_start(b, image, to, tr, SEEK_BUDGET, &mode);
    const double us = now_us() - t0;
    EXPECT(rc == YMM_OK && mode == YMM_SEEK_FROM_START && tt == 0, "%s: seek to %d/%d", name, to, tr);
    EXPECT(z80_same((const z80_t*)ymm_z80(a), (const z80_t*)ymm_z80(b)) && !memcmp(ymm_z80_ram(a), ymm_z80_ram(b), 0x2000) &&
               !memcmp(pa->ym, ymm_player(b)->ym, sizeof pa->ym),
           "%s: seek %d/%d lands with the Z80, its RAM and the player = straight play's", name, to, tr);
    static float x[YMM_MAX_TICK_FRAMES * 2 * 60], y[YMM_MAX_TICK_FRAMES * 2 * 60];
    int bad = 0, fx = 0, fy = 0;
    for (int j = 0; j < 120; ++j) {
        float* xa = j >= 60 ? x + 2 * fx : g_out;
        float* yb = j >= 60 ? y + 2 * fy : g_out;
        const int na = ymm_tick(a, xa), nb = ymm_tick(b, yb);
        if (j >= 60) fx += na, fy += nb;
        if (na != nb || !logs_equal(a, b) || ymm_pcm_playing(a) != ymm_pcm_playing(b)) ++bad;
    }
    double diff = 0;
    for (int i = 0; i < 2 * fx && fx == fy; ++i) diff += (double)(x[i] - y[i]) * (x[i] - y[i]);
    diff = fx ? sqrt(diff / (2.0 * fx)) : 0;
    EXPECT(bad == 0, "%s: 120 ticks after the seek: chip writes (with cycles) and PCM STATUS = straight play (%d differ)", name, bad);
    EXPECT(fx == fy && diff < 1e-4, "%s: sound 1-2 s after the seek = straight play (rms difference %.6f)", name, diff);
    printf("  %s %d/%d: %d ticks fast-forwarded through the whole machine in %.0f us\n", name, to, tr, k - 1, us);
    ymm_destroy(a);
    ymm_destroy(b);
}

static void test_seek(void) {
    printf("seek\n");
    static const int targets[][2] = {{0, 5}, {0, 15}, {1, 0}, {1, 17}, {2, 3}, {2, 12}, {3, 2}, {3, 20}};
    YmMachine* m = ymm_create(RATE);
    for (size_t i = 0; i < sizeof targets / sizeof targets[0]; ++i) {
        const int o = targets[i][0], r = targets[i][1], k = ref_row_start(&REF_SONG, o, r);
        EXPECT(k > 0, "target %d/%d is in the reference", o, r);
        if (k <= 0) continue;
        int mode = -1;
        EXPECT(ymm_start(m, &g_song, o, r, SEEK_BUDGET, &mode) == YMM_OK && mode == YMM_SEEK_FROM_START, "seek %d/%d", o, r);
        EXPECT(heard_is(m, &REF_SONG, k) && pos_is(m, &REF_SONG, k), "lands on %d/%d tick 0", o, r);
        EXPECT(same(ymm_player(m), &REF_SONG, k), "the player has run the target tick");
        int bad = 0;
        const int n = YM_REF_TICKS - k < 200 ? YM_REF_TICKS - k : 200;
        for (int j = 0; j < n; ++j) {
            if (!pos_is(m, &REF_SONG, k + j)) ++bad;
            ymm_tick(m, g_out);
            if (!same(ymm_player(m), &REF_SONG, k + j + 1)) ++bad;
        }
        EXPECT(bad == 0, "seek %d/%d: %d ticks of writes = straight play (%d differ)", o, r, n, bad);
    }
    seek_vs_straight(&g_song, "song", ref_row_start(&REF_SONG, 3, 20));
    seek_vs_straight(&g_pcm, "PCM song", ref_far_row(&REF_PCM, 0.4));
    seek_vs_straight(&g_pcm, "PCM song", ref_far_row(&REF_PCM, 0.8));
    /* Order 3 rows 0-1 are only reached by the order fallback (order 2 breaks to row 2). */
    int mode = -1;
    EXPECT(ymm_start(m, &g_song, 3, 0, SEEK_BUDGET, &mode) == YMM_OK && mode == YMM_SEEK_FROM_ORDER, "3/0: order fallback");
    int o, r, t;
    ymm_position(m, &o, &r, &t);
    EXPECT(o == 3 && r == 0 && t == 0, "fallback lands on 3/0 (%d/%d/%d)", o, r, t);
    int ok = 1;
    for (int j = 0; j < 60; ++j) ok &= ymm_tick(m, g_out) > 0;
    EXPECT(ok, "the fallback plays on");
    EXPECT(ymm_start(m, &g_song, 1, 17, 10, &mode) == YMM_OK && mode == YMM_SEEK_FROM_ORDER, "small budget: fallback");
    ymm_position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 17 && t == 0, "budget fallback lands on 1/17");
    EXPECT(ymm_start(m, &g_song, 4, 0, SEEK_BUDGET, &mode) == YMM_ERR_IMAGE, "order past the song rejected");
    EXPECT(ymm_start(m, &g_song, 0, 16, SEEK_BUDGET, &mode) == YMM_ERR_IMAGE, "row past the order rejected");
    ymm_destroy(m);
}

/* Deterministic seek, restart and audition: two machines (and one machine twice) give identical output. */
static void test_determinism(void) {
    printf("determinism\n");
    const int k = ref_far_row(&REF_PCM, 0.6);
    const int o = REF_PCM.pos[3 * k], r = REF_PCM.pos[3 * k + 1];
    YmMachine* a = ymm_create(RATE);
    YmMachine* b = ymm_create(RATE);
    ymm_set_log(a, 1);
    ymm_set_log(b, 1);
    static float x[YMM_MAX_TICK_FRAMES * 2], y[YMM_MAX_TICK_FRAMES * 2];
    uint64_t run[2] = {0, 0};
    for (int pass = 0; pass < 2; ++pass) {
        int ma, mb, bad = 0;
        ymm_start(a, &g_pcm, o, r, SEEK_BUDGET, &ma);
        ymm_start(b, &g_pcm, o, r, SEEK_BUDGET, &mb);
        uint64_t h = 0xCBF29CE484222325ull;
        for (int j = 0; j < 300; ++j) {
            const int na = ymm_tick(a, x), nb = ymm_tick(b, y);
            if (na != nb || (na > 0 && memcmp(x, y, sizeof(float) * 2 * (size_t)na)) || !logs_equal(a, b)) ++bad;
            if (na > 0) h = fnv(h, x, sizeof(float) * 2 * (size_t)na);
        }
        run[pass] = h;
        EXPECT(ma == mb && bad == 0, "seek %d/%d on two machines: identical output and chip writes for 300 ticks (%d differ)", o, r, bad);
    }
    EXPECT(run[0] == run[1], "a restart of the same machine gives the same output");
    int pslot = 0, pnote = 0;
    const int have_pcm = find_key(&g_pcm, 1, &pslot, &pnote);
    EXPECT(have_pcm, "the PCM song maps variant 1");
    static const int aud[][3] = {{0, A4, SINE_SLOT}, {6, A4, SQUARE_SLOT}, {9, 48, TOM_SLOT}};
    for (int i = 0; i < 4; ++i) {
        if (i == 3 && !have_pcm) continue;
        const YmImage* im = i < 3 ? &g_song : &g_pcm;
        const int ch = i < 3 ? aud[i][0] : YM_CH_PCM, nt = i < 3 ? aud[i][1] : pnote, slot = i < 3 ? aud[i][2] : pslot;
        int mode;
        ymm_start(a, &g_pcm, 0, 0, SEEK_BUDGET, &mode);
        for (int j = 0; j < 100; ++j) ymm_tick(a, x); /* a machine that played a song */
        YmMachine* fresh = ymm_create(RATE);
        ymm_set_log(fresh, 1);
        const int ra = ymm_audition(a, im, ch, nt, slot, 0), rb = ymm_audition(fresh, im, ch, nt, slot, 0);
        int bad = 0;
        for (int j = 0; j < 120; ++j) {
            if (j == 60) bad += ymm_release(a) != ymm_release(fresh);
            const int na = ymm_tick(a, x), nb = ymm_tick(fresh, y);
            if (na != nb || (na > 0 && memcmp(x, y, sizeof(float) * 2 * (size_t)na)) || !logs_equal(a, fresh)) ++bad;
            if (na <= 0) break;
        }
        EXPECT(ra == YMM_OK && rb == YMM_OK && bad == 0, "audition on channel %d after a song = on a fresh machine (%d ticks differ)",
               ch, bad);
        ymm_destroy(fresh);
    }
    ymm_destroy(a);
    ymm_destroy(b);
}

/* Plays `image` from the start, applying alt[t % 2] before machine tick t (from tick `from`), and compares the player's
 * batches (one tick ahead) with `ref`; a PCM command of an applied image translates through map0/map1. */
static int play_applying(const YmImage* image, const YmImage* alt0, const YmImage* alt1, const int* map0, const int* map1,
                         int from, int every, const Ref* ref, int* first) {
    YmMachine* m = ymm_create(RATE);
    int mode, bad = 0;
    const int* map = NULL;
    ymm_start(m, image, 0, 0, SEEK_BUDGET, &mode);
    *first = -1;
    for (int k = 1; k < YM_REF_TICKS; ++k) {
        if (k - 1 >= from && (every || k - 1 == from)) {
            ymm_apply_image(m, (k % 2) ? alt0 : alt1);
            map = (k % 2) ? map0 : map1;
        }
        if (!pos_is(m, ref, k)) ++bad;
        ymm_tick(m, g_out);
        if (!same_map(ymm_player(m), ref, k + 1, map)) {
            if (*first < 0) *first = k;
            ++bad;
        }
    }
    ymm_destroy(m);
    return bad;
}

/* Ticks between the next two row starts (heard positions). */
static int row_length(YmMachine* m) {
    int o, r, t, n = 0;
    do {
        ymm_tick(m, g_out);
        ymm_position(m, &o, &r, &t);
    } while (t != 0);
    do {
        ymm_tick(m, g_out);
        ++n;
        ymm_position(m, &o, &r, &t);
    } while (t != 0);
    return n;
}

static void test_live_edits(void) {
    printf("live edits\n");
    int first;
    int bad = play_applying(&g_song, &g_song, &g_song, NULL, NULL, 0, 1, &REF_SONG, &first);
    EXPECT(bad == 0, "same image applied before every tick: writes = reference (%d differ, first %d)", bad, first);
    bad = play_applying(&g_song, &g_moved, &g_song, NULL, NULL, 0, 1, &REF_SONG, &first);
    EXPECT(bad == 0, "re-laid-out image and the original alternately before every tick: writes = reference (%d differ, first %d)",
           bad, first);
    /* A note edit in order 1 row 16, applied at ticks before the player reaches that row: straight play of the
     * edited song. (The player runs one tick ahead, so the last chance is two ticks before the row is heard.) */
    const int k_edit = ref_row_start(&REF_SONG, 1, 16);
    const int at[] = {0, 40, k_edit - 30, k_edit - 3, k_edit - 2};
    for (size_t i = 0; i < sizeof at / sizeof at[0]; ++i) {
        bad = play_applying(&g_song, &g_edited, &g_edited, NULL, NULL, at[i], 0, &REF_EDITED, &first);
        EXPECT(bad == 0, "note edit applied before tick %d: = edited straight play (%d differ, first %d)", at[i], bad, first);
    }
    /* Tempo: F09 on order 1 row 4. Applied ahead of it: straight play of the tempo song. */
    const int k_tempo = ref_row_start(&REF_SONG, 1, 4);
    bad = play_applying(&g_song, &g_tempo, &g_tempo, NULL, NULL, k_tempo - 4, 0, &REF_TEMPO, &first);
    EXPECT(bad == 0, "tempo edit ahead of the playhead: = tempo song straight play (%d differ, first %d)", bad, first);
    YmMachine* m = ymm_create(RATE);
    int mode, o, r, t;
    ymm_start(m, &g_song, 1, 8, SEEK_BUDGET, &mode);
    ymm_apply_image(m, &g_tempo);
    const int a = row_length(m);
    ymm_apply_image(m, &g_song);
    const int b = row_length(m);
    EXPECT(a == 9 && b == 6, "tempo edit behind the playhead: rows of %d ticks, undo %d", a, b);
    /* Cut the song to two orders while order 3 plays: clamped, then order 1 in order. */
    ymm_start(m, &g_song, 3, 5, SEEK_BUDGET, &mode);
    ymm_tick(m, g_out);
    ymm_apply_image(m, &g_short);
    const YmPlayer* p = ymm_player(m);
    EXPECT(p->order == 1 && p->row == 31, "player clamped to the last row of the last order (%d/%d)", p->order, p->row);
    int ok = 1, rows_seen = 0;
    ymm_tick(m, g_out);
    ymm_position(m, &o, &r, &t);
    int last_row = r;
    for (int k = 0; k < 6 * 10; ++k) {
        ymm_tick(m, g_out);
        ymm_position(m, &o, &r, &t);
        if (t == 0 && r != last_row) {
            if (o != 1 || r != (last_row + 1) % 32) ok = 0;
            last_row = r;
            ++rows_seen;
        }
    }
    EXPECT(ok && rows_seen >= 8, "then the loop order plays in order (%d rows)", rows_seen);
    ymm_destroy(m);
}

/* ---- FM 3 ------------------------------------------------------------------------------------------------------- */

/* ymfm (the chip) sounds operator k of FM 3 in special mode at the register pair the player writes for it: each
 * operator alone (algorithm 7, the others at TL 127) measured against the four pairs' frequencies. */
static void test_fm3_order(void) {
    printf("FM 3 operator order on the chip\n");
    static const uint8_t hi_of_op[4] = {0xAD, 0xAE, 0xAC, 0xA6}; /* the player's map (ym_player.c update_fm) */
    static const int fnum[4] = {300, 420, 560, 740};             /* block 4 */
    const double fs = YMM_MCLK_NTSC / 1008.0;
    YmOpn2* c = ymo_create();
    enum { N = 26634 };
    static float x[N];
    for (int solo = 0; solo < 4; ++solo) {
        ymo_reset(c);
#define W(reg, val) (ymo_write(c, 0, 0, (uint8_t)(reg)), ymo_write(c, 0, 1, (uint8_t)(val)))
        W(0x22, 0);
        W(0x27, 0x40);
        W(0xB2, 0x07);
        W(0xB6, 0xC0);
        for (int k = 0; k < 4; ++k) {
            const int off = YMP_OP_OFFSET[k];
            W(0x32 + off, 0x01);
            W(0x42 + off, k == solo ? 0x00 : 0x7F);
            W(0x52 + off, 0x1F);
            W(0x62 + off, 0x00);
            W(0x72 + off, 0x00);
            W(0x82 + off, 0x0F);
            W(0x92 + off, 0x00);
        }
        for (int k = 0; k < 4; ++k) {
            W(hi_of_op[k], 4 << 3 | fnum[k] >> 8);
            W(hi_of_op[k] - 4, fnum[k] & 0xFF);
        }
        W(0x28, 0xF2);
#undef W
        for (int i = 0; i < N; ++i) {
            int32_t l, r;
            ymo_generate(c, 0, &l, &r);
            x[i] = (float)l;
        }
        const double got = zero_cross(x + 1000, N - 1000, 1, fs), want = fnum[solo] * fs / (double)(1 << 17);
        EXPECT(fabs(1200 * log2(got / want)) < 5, "operator %d alone sounds at $%02X/$%02X's %.2f Hz (measured %.2f)", solo + 1,
               hi_of_op[solo], hi_of_op[solo] - 4, want, got);
    }
    ymo_destroy(c);
}

/* The player in special mode against the same song in normal mode (root pitch) and with offsets zeroed. */
static void test_fm3_player(void) {
    printf("FM 3 per-operator pitch\n");
    static YmImage normal, zeroed;
    copy_image(&normal, &g_pcm);
    normal.bytes[YM_H_FM3] = YM_FM3_NORMAL;
    copy_image(&zeroed, &normal);
    memset(zeroed.bytes + YM_OFF_CH3, 0, 8 * YM_MAX_INSTRUMENTS);
    EXPECT(ym_load_image(&normal) == 0 && ym_load_image(&zeroed) == 0, "the PCM song in normal mode loads (offsets kept, zeroed)");
    static YmPlayer pa, pb, pc;
    ymp_reset(&pa, &g_pcm);
    ymp_reset(&pb, &normal);
    ymp_reset(&pc, &zeroed);
    int has27 = 0;
    for (int i = 0; i < pa.writes; ++i) has27 |= pa.write[i].kind == 0 && pa.write[i].reg == 0x27 && pa.write[i].val == 0x40;
    EXPECT(has27, "special mode: the reset batch writes $27 = $40");
    const int region = g_pcm.info.region;
    static int pair_of[YMP_PITCH_MAX + 1];
    for (int u = 0; u <= YMP_PITCH_MAX; ++u) pair_of[u] = fm_pair(region, u);
    static const uint8_t hi_of_op[4] = {0xAD, 0xAE, 0xAC, 0xA6};
    int off[4] = {0, 0, 0, 0}, keyed = 0, checked = 0, bad = 0, first_bad = -1, distinct = 0, normal_bad = 0, special_regs_normal = 0;
    for (int k = 1; k <= YM_REF_TICKS; ++k) {
        ymp_tick(&pa, &g_pcm);
        ymp_tick(&pb, &normal);
        ymp_tick(&pc, &zeroed);
        /* normal mode ignores the offsets */
        if (pb.writes != pc.writes || memcmp(pb.write, pc.write, sizeof pb.write[0] * (size_t)pb.writes) ||
            memcmp(pb.pcm, pc.pcm, sizeof pb.pcm))
            ++normal_bad;
        for (int i = 0; i < pb.writes; ++i)
            if (pb.write[i].kind == 0 && pb.write[i].reg >= 0xA8 && pb.write[i].reg <= 0xAE) ++special_regs_normal;
        /* the note's offsets: from the image's slot at FM 3's key-on */
        for (int i = 0; i < pa.writes; ++i)
            if (pa.write[i].kind == 0 && pa.write[i].reg == 0x28 && pa.write[i].val == 0xF2) {
                const int slot = pa.ch[YM_CH_FM3].instrument;
                for (int j = 0; j < 4; ++j) off[j] = ym_ch3_offset(&g_pcm, slot, j);
                keyed = 1;
            }
        if (!keyed || !pa.ch[YM_CH_FM3].active) continue;
        const int root = pb.ym[0][0xA6] << 8 | pb.ym[0][0xA2];
        int ok = 1;
        for (int j = 0; j < 4; ++j) {
            const int got = pa.ym[0][hi_of_op[j]] << 8 | pa.ym[0][hi_of_op[j] - 4];
            int found = 0;
            for (int u = 0; u <= YMP_PITCH_MAX && !found; ++u)
                if (pair_of[u] == root && pair_of[clampi(u + off[j], 0, YMP_PITCH_MAX)] == got) found = 1;
            ok &= found;
            if (got != root) ++distinct;
        }
        ++checked;
        if (!ok && !bad++) first_bad = k - 1;
    }
    EXPECT(keyed && checked > 50, "FM 3 plays notes with operator offsets (%d ticks checked)", checked);
    EXPECT(bad == 0, "special mode: $AD/$A9, $AE/$AA, $AC/$A8, $A6/$A2 = fm_frequency(root + offset of operators 1-4), the root "
           "= normal mode's (%d ticks wrong, first %d)", bad, first_bad);
    EXPECT(distinct > 0, "the offsets move operators away from the root (%d)", distinct);
    EXPECT(normal_bad == 0 && special_regs_normal == 0, "normal mode ignores the offsets (%d ticks differ) and writes no special "
           "frequencies (%d)", normal_bad, special_regs_normal);

    /* Live edits: special -> normal and back; the chips follow through the driver. */
    YmMachine* m = ymm_create(RATE);
    ymm_set_log(m, 1);
    int mode;
    ymm_start(m, &g_pcm, 0, 0, SEEK_BUDGET, &mode);
    ChipState chip;
    chip_init(&chip);
    chip_log(&chip, m);
    for (int j = 0; j < 200; ++j) {
        ymm_tick(m, g_out);
        chip_log(&chip, m);
    }
    const int before = chip.s.ym[0][0x27] & 0x40;
    ymp_reset(&pb, &normal);
    for (int j = 0; j <= 200; ++j) ymp_tick(&pb, &normal); /* the machine's player has run ticks 0..200 */
    ymm_apply_image(m, &normal);
    const YmPlayer* p = ymm_player(m);
    int diff = 0;
    for (int j = 0; j < 300; ++j) {
        ymm_tick(m, g_out);
        chip_log(&chip, m);
        ymp_tick(&pb, &normal);
        Snap sa, sb;
        snap_player(&sa, p);
        snap_player(&sb, &pb);
        for (int i = 0; i < 3; ++i) sa.ym[0][0xA8 + i] = sb.ym[0][0xA8 + i] = sa.ym[0][0xAC + i] = sb.ym[0][0xAC + i] = 0;
        diff += memcmp(&sa, &sb, sizeof sa) != 0;
        if (j == 2) EXPECT(before == 0x40 && !(chip.s.ym[0][0x27] & 0x40), "special -> normal: the driver clears $27 bit 6");
    }
    EXPECT(diff == 0, "special -> normal: every register but the idle special frequencies = normal-mode straight play (%d ticks differ)", diff);
    ymp_reset(&pa, &g_pcm);
    for (int j = 0; j <= 500; ++j) ymp_tick(&pa, &g_pcm);
    ymm_apply_image(m, &g_pcm);
    diff = 0;
    for (int j = 0; j < 300; ++j) {
        ymm_tick(m, g_out);
        chip_log(&chip, m);
        ymp_tick(&pa, &g_pcm);
        Snap sa, sb;
        snap_player(&sa, p);
        snap_player(&sb, &pa);
        diff += memcmp(&sa, &sb, sizeof sa) != 0;
        if (j == 2) EXPECT(chip.s.ym[0][0x27] & 0x40, "normal -> special: the driver sets $27 bit 6");
    }
    EXPECT(diff == 0, "normal -> special: every register = special-mode straight play (%d ticks differ)", diff);
    ymm_destroy(m);
}

/* ---- PCM -------------------------------------------------------------------------------------------------------- */

/* The driver's STATUS bits against a model of the PCM commands: frame f (encoded at machine tick f, 0 = the start)
 * executes at the interrupt that begins machine tick f + 1 (tick time f); a trigger plays its variant's bytes at the
 * driver's rate, a stop or retrigger ends what played. Compared at tick ends at least 1.5 ticks from every modelled
 * boundary (the driver's fill buffer runs ahead of its output). */
typedef struct PcmModel {
    double from[3], until[3];
    int triggers[3], stops_sounding, retriggers;
} PcmModel;

static void model_frame(PcmModel* md, const YmImage* im, const int* cmds, int f) {
    const double spt = pcm_per_tick(im);
    for (int k = 0; k < YM_PCM_CHANNELS; ++k) {
        const int v = cmds[k];
        if (v == YMP_PCM_NONE) continue;
        const int sounding = f >= md->from[k] && f < md->until[k];
        if (v == YMP_PCM_STOP) {
            if (sounding) {
                ++md->stops_sounding;
                md->until[k] = f;
            }
            continue;
        }
        if (sounding) ++md->retriggers;
        ++md->triggers[k];
        int start, pages, flags;
        ym_pcm_region(im, v, &start, &pages, &flags);
        md->from[k] = f;
        md->until[k] = f + pages * YM_PCM_ALIGN * (flags & YM_PCM_HALF_RATE ? 2 : 1) / spt;
    }
}

static void test_pcm_status(void) {
    printf("PCM: driver STATUS\n");
    PcmModel md;
    memset(&md, 0, sizeof md);
    for (int k = 0; k < 3; ++k) md.from[k] = md.until[k] = -1e9;
    YmMachine* m = ymm_create(RATE);
    int mode;
    ymm_start(m, &g_pcm, 0, 0, SEEK_BUDGET, &mode);
    const YmPlayer* p = ymm_player(m);
    model_frame(&md, &g_pcm, p->pcm, 0);
    int compared = 0, bad = 0, first_bad = -1, max_pop = 0, all3 = 0;
    for (int j = 1; j <= YM_REF_TICKS; ++j) {
        ymm_tick(m, g_out);
        const unsigned st = ymm_pcm_playing(m);
        const int pop = (int)(st & 1) + (int)(st >> 1 & 1) + (int)(st >> 2 & 1);
        if (pop > max_pop) max_pop = pop;
        all3 += pop == 3;
        if (ymm_lag(m) == 0)
            for (int k = 0; k < 3; ++k) {
                if (fabs(j - md.from[k]) < 1.5 || fabs(j - md.until[k]) < 1.5) continue;
                const int busy = j >= md.from[k] && j < md.until[k];
                ++compared;
                if (busy != (int)(st >> k & 1) && !bad++) first_bad = j;
            }
        model_frame(&md, &g_pcm, p->pcm, j);
    }
    EXPECT(compared > 1000 && bad == 0, "STATUS bits follow triggers, stops, retriggers and sample ends (%d compared, %d differ, "
           "first at tick %d)", compared, bad, first_bad);
    EXPECT(md.triggers[0] && md.triggers[1] && md.triggers[2], "triggers on all three channels (%d %d %d)", md.triggers[0],
           md.triggers[1], md.triggers[2]);
    EXPECT(max_pop == 3 && all3 > 0, "three samples overlap (%d ticks with all three playing)", all3);
    EXPECT(md.stops_sounding > 0 && md.retriggers > 0, "stops end sounding samples (%d), retriggers replace them (%d)",
           md.stops_sounding, md.retriggers);
    printf("  %d triggers, %d stops of sounding samples, %d retriggers, %d ticks with three samples\n",
           md.triggers[0] + md.triggers[1] + md.triggers[2], md.stops_sounding, md.retriggers, all3);
    ymm_destroy(m);
}

/* The PCM rows of the song, followed by a player: G == S with a mapped note nets a stop in the delay tick, an
 * unmapped note is ignored (no command, the sounding sample goes on), OFF and Sxx stop a keyed sample, a delayed
 * mapped note triggers in its delay tick. */
static void test_pcm_rows(void) {
    printf("PCM: row semantics\n");
    static YmPlayer p;
    const YmImage* im = &g_pcm;
    ymp_reset(&p, im);
    int g_eq_s = 0, unmapped = 0, offs = 0, delayed = 0, cuts = 0, bad = 0, first_bad = -1;
    struct {
        int note, g, s, slot, active;
    } row[3];
    memset(row, 0, sizeof row);
#define ROW_EXPECT(cond)                         \
    do {                                         \
        if (!(cond) && !bad++) first_bad = k - 1; \
    } while (0)
    for (int k = 1; k <= YM_REF_TICKS; ++k) {
        const int tick = p.tick;
        int keyed[3], inst[3];
        for (int c = 0; c < 3; ++c) {
            keyed[c] = p.ch[YM_CH_PCM + c].keyed;
            inst[c] = p.ch[YM_CH_PCM + c].instrument;
        }
        ymp_tick(&p, im);
        for (int c = 0; c < 3; ++c) {
            const YmChannel* ch = &p.ch[YM_CH_PCM + c];
            if (tick == 0) {
                const YmRow* r = &ch->row;
                row[c].note = r->note;
                row[c].g = 0;
                row[c].s = -1;
                for (int e = 0; e < r->effects; ++e) {
                    if (r->code[e] == YM_FX_DELAY) row[c].g = r->param[e];
                    if (r->code[e] == YM_FX_CUT) row[c].s = r->param[e];
                }
                row[c].slot = r->instrument ? r->instrument : inst[c];
                row[c].active = 1;
            }
            if (!row[c].active) continue;
            const int v = p.pcm[c], tp = row[c].g;
            if (tick == tp && row[c].note >= 0) {
                if (row[c].note < YM_NOTES) {
                    const int var = ym_pcm_variant(im, row[c].slot, row[c].note);
                    if (var && row[c].s == tp) {
                        g_eq_s += tp > 0;
                        ROW_EXPECT(v == YMP_PCM_STOP);
                    } else if (var) {
                        delayed += tp > 0;
                        ROW_EXPECT(v == var);
                    } else if (row[c].s != tp) {
                        ++unmapped;
                        ROW_EXPECT(v == YMP_PCM_NONE && ch->keyed == keyed[c]);
                    }
                } else if (row[c].note == YM_NOTE_OFF) {
                    ++offs;
                    ROW_EXPECT(v == (keyed[c] ? YMP_PCM_STOP : YMP_PCM_NONE));
                }
            } else if (tick == row[c].s && tick > tp && keyed[c]) {
                ++cuts;
                ROW_EXPECT(v == YMP_PCM_STOP);
            }
        }
    }
#undef ROW_EXPECT
    EXPECT(bad == 0, "PCM rows: commands as the rows say (%d wrong, first at tick %d)", bad, first_bad);
    EXPECT(g_eq_s > 0 && unmapped > 0 && offs > 0 && delayed > 0 && cuts > 0,
           "the song exercises G == S (%d), unmapped keys (%d), OFF (%d), delayed notes (%d) and later cuts (%d)", g_eq_s, unmapped,
           offs, delayed, cuts);
}

/* Rising zero crossings of signed bytes (mean removed): intervals counted, first and last crossing and the spread of
 * the intervals (standard deviation / mean). */
static int byte_crossings(const uint8_t* b, int n, double* first, double* last, double* spread) {
    double mean = 0;
    for (int i = 0; i < n; ++i) mean += (int8_t)b[i];
    mean /= n > 0 ? n : 1;
    int count = -1;
    double prev = -1, sum = 0, sum2 = 0;
    *first = *last = 0;
    for (int i = 1; i < n; ++i) {
        const double a = (int8_t)b[i - 1] - mean, c = (int8_t)b[i] - mean;
        if (a < 0 && c >= 0) {
            const double t = (i - 1) + a / (a - c);
            if (prev >= 0) {
                sum += t - prev;
                sum2 += (t - prev) * (t - prev);
            } else {
                *first = t;
            }
            prev = *last = t;
            ++count;
        }
    }
    if (count < 1) return 0;
    const double m = sum / count;
    *spread = sqrt(fmax(0, sum2 / count - m * m)) / m;
    return count;
}

/* Bytes of a variant before its zero padding. */
static int content_length(const uint8_t* b, int len) {
    while (len > 0 && b[len - 1] == 0) --len;
    return len;
}

/* The most periodic variant (a tone): its id, or 0. */
static int tone_variant(const YmImage* im, double* hz) {
    int best = 0;
    double best_spread = 1e9;
    for (int id = 1; id <= im->info.pcm_variants; ++id) {
        int len, flags;
        const uint8_t* b = variant_bytes(im, id, &len, &flags);
        if (flags & YM_PCM_HALF_RATE) len /= 2;
        const int n = content_length(b, len);
        double first, last, spread;
        const int count = byte_crossings(b, n, &first, &last, &spread);
        if (count >= 20 && spread < best_spread) {
            best_spread = spread;
            best = id;
            const double rate = mclk_hz(im) / (flags & YM_PCM_HALF_RATE ? YM_PCM_HALF_DIVIDER : YM_PCM_FULL_DIVIDER);
            *hz = count * rate / (last - first);
        }
    }
    return best_spread < 0.05 ? best : 0;
}

/* The pitch of a mapped variant: the auditioned output's zero crossings against its own bytes at the driver's rate. */
static void test_pcm_pitch(const char* dir) {
    printf("PCM: pitch\n");
    double want = 0;
    const int id = tone_variant(&g_pcm, &want);
    int slot, note;
    EXPECT(id && find_key(&g_pcm, id, &slot, &note), "the PCM song maps a periodic tone variant (%d)", id);
    if (!id || !find_key(&g_pcm, id, &slot, &note)) return;
    int len, flags;
    const uint8_t* b = variant_bytes(&g_pcm, id, &len, &flags);
    const int n = content_length(b, flags & YM_PCM_HALF_RATE ? len / 2 : len);
    const double ticks = n * (flags & YM_PCM_HALF_RATE ? 2.0 : 1.0) / pcm_per_tick(&g_pcm);
    YmMachine* m = ymm_create(RATE);
    EXPECT(ymm_audition(m, &g_pcm, YM_CH_PCM + 1, note, slot, 0) == YMM_OK, "audition variant %d (slot %d note %d) on PCM 2", id,
           slot, note);
    float* buf;
    const int nt = (int)ticks + 4;
    const int frames = render(m, nt, &buf);
    const int per = (int)(RATE / tick_hz(&g_pcm));
    const int from = 2 * per, to = (int)((ticks - 1) * per);
    const double got = to > from + RATE / 20 ? zero_cross_hz(buf + 2 * from, to - from) : 0;
    EXPECT(got > 0 && fabs(1200 * log2(got / want)) < 8, "variant %d sounds at %.2f Hz, its bytes at the driver's rate say %.2f Hz",
           id, got, want);
    write_wav(dir, "ym_tone_pcm.wav", buf, frames);
    printf("  variant %d: %.2f Hz measured, %.2f Hz from its bytes (%.1f ticks)\n", id, got, want, ticks);
    free(buf);
    ymm_destroy(m);
}

/* The DAC bytes the driver writes for one auditioned variant: the variant's signed bytes (+$80) through its zero
 * padding, then signed zero; the DAC is enabled while it plays and released after. Its release ends with the sample. */
static void test_pcm_dac(void) {
    printf("PCM: DAC data, padding, release\n");
    int id = 0, best_pad = -1;
    for (int i = 1; i <= g_pcm.info.pcm_variants; ++i) {
        int len, flags;
        const uint8_t* b = variant_bytes(&g_pcm, i, &len, &flags);
        int slot, note;
        if (flags & YM_PCM_HALF_RATE || !find_key(&g_pcm, i, &slot, &note)) continue;
        const int pad = len - content_length(b, len);
        if (pad > best_pad && pad < len) best_pad = pad, id = i;
    }
    int slot, note, len, flags;
    EXPECT(id && find_key(&g_pcm, id, &slot, &note), "a full-rate mapped variant");
    if (!id || !find_key(&g_pcm, id, &slot, &note)) return;
    const uint8_t* bytes = variant_bytes(&g_pcm, id, &len, &flags);
    YmMachine* m = ymm_create(RATE);
    ymm_set_log(m, 1);
    EXPECT(ymm_audition(m, &g_pcm, YM_CH_PCM, note, slot, 0) == YMM_OK, "audition variant %d", id);
    const int cap = 700 * 400;
    uint8_t* dac = (uint8_t*)malloc((size_t)cap);
    int ndac = 0, ticks = 0, ended_at = -1, dac_on_seen = 0, dac_off_after = 0;
    for (; ticks < 600; ++ticks) {
        ymm_tick(m, g_out);
        int nlog;
        const YmmLogEntry* log = ymm_log(m, &nlog);
        for (int i = 0; i < nlog; ++i)
            if (log[i].kind == YM_W_PORT0 && log[i].reg == 0x2A && ndac < cap) dac[ndac++] = log[i].val;
        if (ymm_dac_enabled(m)) dac_on_seen = 1;
        if (ended_at < 0 && ticks > 0 && !(ymm_pcm_playing(m) & 1)) ended_at = ticks;
        if (ended_at >= 0 && !ymm_dac_enabled(m)) dac_off_after = 1;
        if (ended_at >= 0 && ticks > ended_at + 20) break;
    }
    int at = -1;
    for (int s = 0; s + len <= ndac && at < 0; ++s) {
        int i = 0;
        while (i < len && dac[s + i] == (uint8_t)(bytes[i] ^ 0x80)) ++i;
        if (i == len) at = s;
    }
    int after_bad = 0;
    for (int i = at + len; at >= 0 && i < ndac; ++i) after_bad += dac[i] != 0x80;
    EXPECT(at >= 0, "the DAC receives variant %d's %d bytes in order, signed ($80 = 0), padding included (%d DAC writes)", id, len, ndac);
    EXPECT(at >= 0 && ndac - (at + len) > 100 && after_bad == 0, "after its end the DAC holds signed zero (%d of %d differ)",
           after_bad, ndac - (at + len));
    EXPECT(dac_on_seen && dac_off_after, "the DAC is enabled while the sample plays and released after it (ended at tick %d)", ended_at);
    printf("  variant %d (%d bytes, %d of them padding) at DAC write %d; ended at tick %d\n", id, len, best_pad, at, ended_at);
    free(dac);
    /* release: the one-shot plays out */
    int start, pages;
    ym_pcm_region(&g_pcm, id, &start, &pages, &flags);
    EXPECT(ymm_audition(m, &g_pcm, YM_CH_PCM + 2, note, slot, 0) == YMM_OK && ymm_release(m) == 0, "PCM release before any tick: cut");
    EXPECT(ymm_audition(m, &g_pcm, YM_CH_PCM + 2, note, slot, 0) == YMM_OK, "PCM audition on PCM 3");
    ymm_tick(m, g_out);
    ymm_tick(m, g_out);
    int off = 0, left = 0;
    const int playing = playing_variant(m, &g_pcm, 2, &off, &left);
    EXPECT(playing == id, "PCM 3 plays variant %d (%d)", id, playing);
    EXPECT(ymm_release(m) == 1, "PCM release phase");
    int n = 0;
    while (ymm_tick(m, g_out) > 0 && n < 1000) ++n;
    const double want = left / pcm_per_tick(&g_pcm);
    EXPECT(fabs(n - want) <= 3, "the released sample plays out: %d ticks for %d bytes left (%.1f ticks)", n, left, want);
    ymm_destroy(m);
}

/* FM 6 and the DAC: with FM 6 muted the output differs from unmuted only while the DAC is off (FM 6's mute is the
 * FM channel's, never the DAC's): while the DAC is on for whole ticks the difference carries no signal (at most the
 * output filter's decay of an earlier difference), and FM 6 is heard once the DAC is released. */
static void test_pcm_fm6(void) {
    printf("PCM: FM 6 under the DAC\n");
    YmMachine* a = ymm_create(RATE);
    YmMachine* b = ymm_create(RATE);
    ymm_set_log(a, 1);
    int mode;
    ymm_start(a, &g_pcm, 0, 0, SEEK_BUDGET, &mode);
    ymm_start(b, &g_pcm, 0, 0, SEEK_BUDGET, &mode);
    ymm_mute(b, YM_CH_FM6, 1);
    static float x[YMM_MAX_TICK_FRAMES * 2], y[YMM_MAX_TICK_FRAMES * 2];
    int dac_ticks = 0, dac_bad = 0, audible_after = 0, was_on = 0, prev_whole = 0;
    double worst = 0;
    for (int j = 0; j < 4 * YM_REF_TICKS; ++j) {
        const int on_before = ymm_dac_enabled(a);
        const int na = ymm_tick(a, x), nb = ymm_tick(b, y);
        if (na <= 0 || na != nb) break;
        int toggles = 0, nlog;
        const YmmLogEntry* log = ymm_log(a, &nlog);
        for (int i = 0; i < nlog; ++i) toggles += log[i].kind == YM_W_PORT0 && log[i].reg == 0x2B;
        const int whole = on_before && ymm_dac_enabled(a) && !toggles;
        double step = 0, step2 = 0;
        for (int i = 1; i < na; ++i) {
            const double d = fabs((double)(x[2 * i] - y[2 * i]) - (x[2 * i - 2] - y[2 * i - 2]));
            if (d > step) step = d;
            step2 += d * d;
        }
        step2 = sqrt(step2 / na);
        if (whole && prev_whole) {
            ++dac_ticks;
            if (step > worst) worst = step;
            if (step > 1e-4) ++dac_bad;
        }
        if (whole) was_on = 1;
        if (was_on && !on_before && !ymm_dac_enabled(a) && step2 > 2e-4) ++audible_after;
        prev_whole = whole;
    }
    EXPECT(dac_ticks > 30 && dac_bad == 0, "FM 6 inaudible while the DAC is on: %d ticks, %d with an FM 6 signal (largest step %.6f)",
           dac_ticks, dac_bad, worst);
    EXPECT(audible_after > 0, "FM 6 audible after the DAC is released (%d ticks)", audible_after);
    ymm_destroy(a);
    ymm_destroy(b);
}

/* Muting a PCM channel stops its sounding sample through the driver (the next frame's stop) and keeps its triggers
 * out of the stream; the player is untouched; unmuting lets the next trigger play. */
static void test_pcm_mute(void) {
    printf("PCM: mute\n");
    YmMachine* m = ymm_create(RATE);
    int mode;
    ymm_start(m, &g_pcm, 0, 0, SEEK_BUDGET, &mode);
    const double spt = pcm_per_tick(&g_pcm);
    int k = -1, j = 1;
    for (; j < YM_REF_TICKS - 400 && k < 0; ++j) {
        ymm_tick(m, g_out);
        for (int c = 0; c < 3 && k < 0; ++c) {
            int off, left;
            if (playing_variant(m, &g_pcm, c, &off, &left) && left > 6 * spt) k = c;
        }
    }
    EXPECT(k >= 0, "a sample sounding for 6 more ticks");
    if (k < 0) {
        ymm_destroy(m);
        return;
    }
    EXPECT(ymm_mute(m, YM_CH_PCM + k, 1) == YMM_OK, "mute PCM %d", k + 1);
    const YmPlayer* p = ymm_player(m);
    ymm_tick(m, g_out);
    ++j;
    int stops, trig;
    last_pcm_commands(m, k, &stops, &trig);
    EXPECT(stops == 1 && trig == 0, "the next frame stops PCM %d (%d stops, %d triggers)", k + 1, stops, trig);
    ymm_tick(m, g_out);
    ++j;
    EXPECT(!(ymm_pcm_playing(m) >> k & 1), "the driver stopped PCM %d", k + 1);
    int triggers = 0, sent = 0, set = 0, bad = 0;
    for (int t = 0; t < 300; ++t, ++j) {
        ymm_tick(m, g_out);
        if (!same(p, &REF_PCM, j + 1)) ++bad;
        triggers += p->pcm[k] > 0;
        last_pcm_commands(m, k, &stops, &trig);
        sent += trig;
        set += ymm_pcm_playing(m) >> k & 1;
    }
    EXPECT(triggers > 0 && sent == 0 && set == 0, "muted: %d triggers of the player, none streamed, PCM %d silent", triggers, k + 1);
    EXPECT(bad == 0, "muted: the player's writes and commands = reference (%d differ)", bad);
    EXPECT(ymm_mute(m, YM_CH_PCM + k, 0) == YMM_OK, "unmute");
    int played = 0;
    for (int t = 0; t < 600 && j < YM_REF_TICKS - 1 && !played; ++t, ++j) {
        ymm_tick(m, g_out);
        played = ymm_pcm_playing(m) >> k & 1;
    }
    EXPECT(played, "unmuted: a later trigger plays on PCM %d", k + 1);
    EXPECT(ymm_mute(m, YM_CHANNELS, 1) == YMM_ERR_IMAGE, "channel 13 rejected");
    ymm_destroy(m);
}

/* Variant id maps between the PCM song and its live-edit variants (by compiled bytes). */
static int g_map_moved[MAX_IDS];  /* pcm_moved id -> pcm id */

/* Live edits of the PCM bank. */
static void test_pcm_edits(void) {
    printf("PCM: live edits\n");
    int unmapped = 0, moved = 0;
    for (int id = 1; id <= g_pcm_moved.info.pcm_variants; ++id) {
        g_map_moved[id] = same_variant(&g_pcm_moved, id, &g_pcm);
        unmapped += !g_map_moved[id];
        moved += g_map_moved[id] && variant_start(&g_pcm_moved, id) != variant_start(&g_pcm, g_map_moved[id]);
    }
    EXPECT(g_pcm_moved.info.pcm_variants == g_pcm.info.pcm_variants && unmapped == 0 && moved > 0,
           "pcm_moved: the same %d variants, %d at other pages", g_pcm.info.pcm_variants, moved);
    int changed = 0, kept = 0;
    for (int id = 1; id <= g_pcm.info.pcm_variants; ++id) {
        if (same_variant(&g_pcm, id, &g_pcm_edited)) ++kept;
        else ++changed;
    }
    EXPECT(changed > 0 && kept > 0, "pcm_edited: %d variants changed, %d kept", changed, kept);

    int first;
    int bad = play_applying(&g_pcm, &g_pcm_moved, &g_pcm, g_map_moved, NULL, 0, 1, &REF_PCM, &first);
    EXPECT(bad == 0, "re-laid-out PCM song and the original alternately before every tick: writes and commands = reference "
           "(%d differ, first %d)", bad, first);

    /* Sample-exact continuity under re-layouts (two edits a round force the mapping) and no-op applies. */
    for (int noop = 0; noop < 2; ++noop) {
        YmMachine* a = ymm_create(RATE);
        YmMachine* b = ymm_create(RATE);
        ymm_set_log(a, 1);
        ymm_set_log(b, 1);
        int mode;
        ymm_start(a, &g_pcm, 0, 0, SEEK_BUDGET, &mode);
        ymm_start(b, &g_pcm, 0, 0, SEEK_BUDGET, &mode);
        static float x[YMM_MAX_TICK_FRAMES * 2], y[YMM_MAX_TICK_FRAMES * 2];
        static uint8_t da[8192], db[8192];
        int dac_bad = 0, status_bad = 0, out_bad = 0, log_bad = 0, pending = 0;
        double worst = 0;
        for (int k = 1; k < YM_REF_TICKS; ++k) {
            ymm_apply_image(b, noop ? &g_pcm : (k % 2) ? &g_pcm_moved : &g_pcm);
            pending += ymm_swap_pending(b);
            const int na = ymm_tick(a, x), nb = ymm_tick(b, y);
            int nla, nlb, ca = 0, cb = 0;
            const YmmLogEntry* la = ymm_log(a, &nla);
            const YmmLogEntry* lb = ymm_log(b, &nlb);
            for (int i = 0; i < nla; ++i)
                if (la[i].kind == 0 && la[i].reg == 0x2A && ca < 8192) da[ca++] = la[i].val;
            for (int i = 0; i < nlb; ++i)
                if (lb[i].kind == 0 && lb[i].reg == 0x2A && cb < 8192) db[cb++] = lb[i].val;
            dac_bad += ca != cb || memcmp(da, db, (size_t)ca);
            status_bad += ymm_pcm_playing(a) != ymm_pcm_playing(b);
            log_bad += !logs_equal(a, b);
            if (na != nb) ++out_bad;
            else
                for (int i = 0; i < 2 * na; ++i)
                    if (fabs(x[i] - y[i]) > worst) worst = fabs(x[i] - y[i]);
        }
        if (noop) {
            EXPECT(pending == 0, "identical image applied before every tick: no bank swap pending (%d)", pending);
            EXPECT(dac_bad + status_bad + out_bad + log_bad == 0 && worst == 0,
                   "identical image applied before every tick: output, chip writes and STATUS unchanged (%d %d %d %d, %.6f)", dac_bad,
                   status_bad, out_bad, log_bad, worst);
        } else {
            EXPECT(pending > 0, "re-laid-out banks wait for the driver's fill boundary (%d ticks pending)", pending);
            EXPECT(dac_bad == 0 && status_bad == 0 && out_bad == 0 && worst < 1e-4,
                   "re-layout before every tick: DAC bytes and STATUS = uninterrupted play (%d, %d ticks differ; output %.6f)",
                   dac_bad, status_bad, worst);
        }
        ymm_destroy(a);
        ymm_destroy(b);
    }

    /* One re-layout under a sounding sample that moves: it continues at the same offset in its new place. */
    const double spt = pcm_per_tick(&g_pcm);
    YmMachine* m = ymm_create(RATE);
    int mode;
    ymm_start(m, &g_pcm, 0, 0, SEEK_BUDGET, &mode);
    const YmPlayer* p = ymm_player(m);
    int k = -1, id = 0, off0 = 0, left0 = 0, j = 1;
    for (; j < YM_REF_TICKS - 8 && k < 0; ++j) {
        ymm_tick(m, g_out);
        for (int c = 0; c < 3 && k < 0; ++c) {
            int quiet = 1;
            for (int e = j + 1; e <= j + 5; ++e) quiet &= REF_PCM.pcm[3 * e + c] == YMP_PCM_NONE;
            const int v = playing_variant(m, &g_pcm, c, &off0, &left0);
            if (!v || !quiet || left0 <= 4 * spt) continue;
            for (int mid = 1; mid <= g_pcm_moved.info.pcm_variants; ++mid)
                if (g_map_moved[mid] == v && variant_start(&g_pcm_moved, mid) != variant_start(&g_pcm, v)) k = c, id = mid;
        }
    }
    EXPECT(k >= 0, "a moving variant sounding for 4 more ticks");
    if (k >= 0) {
        ymm_apply_image(m, &g_pcm_moved);
        EXPECT(ymm_swap_pending(m), "re-layout: the bank swap waits for the driver");
        int t = 0, sent = 0;
        while (ymm_swap_pending(m) && t < 4) {
            ymm_tick(m, g_out);
            ++t;
            int stops, trig;
            last_pcm_commands(m, k, &stops, &trig);
            sent += stops + trig;
            sent += p->pcm[k] != YMP_PCM_NONE;
        }
        int off1, left1;
        const int now = playing_variant(m, &g_pcm_moved, k, &off1, &left1);
        EXPECT(!ymm_swap_pending(m) && t <= 2, "the swap happened within %d ticks", t);
        EXPECT(now == id && off1 >= off0 && off1 - off0 <= (t + 1) * spt + 512 && sent == 0,
               "PCM %d continues variant %d at its new page (offset %d -> %d), not retriggered (%d commands)", k + 1, id, off0, off1,
               sent);
    }
    ymm_destroy(m);

    /* An edit that changes a sounding sample's bytes stops it; unchanged sounding samples continue. */
    m = ymm_create(RATE);
    ymm_start(m, &g_pcm, 0, 0, SEEK_BUDGET, &mode);
    k = -1;
    int keep[3] = {0, 0, 0};
    for (j = 1; j < YM_REF_TICKS - 8 && k < 0; ++j) {
        ymm_tick(m, g_out);
        for (int c = 0; c < 3 && k < 0; ++c) {
            int quiet = 1, off, left;
            for (int e = j + 1; e <= j + 5; ++e) quiet &= REF_PCM.pcm[3 * e + c] == YMP_PCM_NONE;
            const int v = playing_variant(m, &g_pcm, c, &off, &left);
            if (v && quiet && left > 4 * spt && !same_variant(&g_pcm, v, &g_pcm_edited)) k = c;
        }
    }
    EXPECT(k >= 0, "a changed variant sounding for 4 more ticks");
    if (k >= 0) {
        for (int c = 0; c < 3; ++c) {
            int quiet = 1, off, left;
            for (int e = j + 1; e <= j + 5; ++e) quiet &= REF_PCM.pcm[3 * e + c] == YMP_PCM_NONE;
            const int v = playing_variant(m, &g_pcm, c, &off, &left);
            keep[c] = c != k && v && quiet && left > 4 * spt && same_variant(&g_pcm, v, &g_pcm_edited);
        }
        ymm_apply_image(m, &g_pcm_edited);
        int t = 0;
        while (ymm_swap_pending(m) && t < 4) ymm_tick(m, g_out), ++t;
        const unsigned st = ymm_pcm_playing(m);
        int kept_ok = 1;
        for (int c = 0; c < 3; ++c)
            if (keep[c]) kept_ok &= st >> c & 1;
        EXPECT(!ymm_swap_pending(m) && !(st >> k & 1), "the changed sample on PCM %d stops at the swap (%d ticks)", k + 1, t);
        EXPECT(kept_ok, "unchanged sounding samples continue");
    }
    ymm_destroy(m);
}

static double audition_hz(YmMachine* m, const YmImage* image, int channel, int note, int slot, int fine, float** keep,
                          int* keep_frames) {
    EXPECT(ymm_audition(m, image, channel, note, slot, fine) == YMM_OK, "audition %d on channel %d", note, channel);
    float* buf;
    const int frames = render(m, 120, &buf);
    const double hz = zero_cross_hz(buf + 2 * (RATE / 10), frames - RATE / 10);
    if (keep) {
        *keep = buf;
        *keep_frames = frames;
    } else {
        free(buf);
    }
    return hz;
}

static int release_ticks(YmMachine* m, float** tail, int* tail_frames) {
    *tail = (float*)malloc(sizeof(float) * 2 * YMM_MAX_TICK_FRAMES * 700);
    int ticks = 0, n;
    *tail_frames = 0;
    while ((n = ymm_tick(m, *tail + 2 * *tail_frames)) > 0 && ticks < 700) {
        *tail_frames += n;
        ++ticks;
    }
    return n == 0 ? ticks : -1;
}

static void test_audition(const char* dir) {
    printf("audition\n");
    YmMachine* m = ymm_create(RATE);
    double t0 = now_us();
    EXPECT(ymm_audition(m, &g_song, 0, A4, SINE_SLOT, 0) == YMM_OK, "audition");
    const double setup = now_us() - t0;
    float* buf;
    int frames;
    const double hz = audition_hz(m, &g_song, 0, A4, SINE_SLOT, 0, &buf, &frames);
    const double want_fm = fm_hz(0, 4 << 3 | 1083 >> 8, 1083 & 0xFF);
    EXPECT(fabs(hz - want_fm) < 0.3 && fabs(1200 * log2(hz / 440.0)) < 1.5, "FM A-4 measured %.3f Hz (registers %.3f)", hz, want_fm);
    EXPECT(rms(buf + 2 * (frames - RATE / 2), RATE / 2) > 0.5 * rms(buf + 2 * (RATE / 2), RATE / 2), "held");
    EXPECT(ymm_release(m) == 1, "release phase");
    float* tail;
    int tail_frames;
    const int ticks = release_ticks(m, &tail, &tail_frames);
    EXPECT(ticks > 2 && ticks < 120, "release ends when the carrier is silent (%d ticks)", ticks);
    float* both = (float*)malloc(sizeof(float) * 2 * (size_t)(frames + tail_frames));
    memcpy(both, buf, sizeof(float) * 2 * (size_t)frames);
    memcpy(both + 2 * frames, tail, sizeof(float) * 2 * (size_t)tail_frames);
    write_wav(dir, "ym_tone_fm.wav", both, frames + tail_frames);
    printf("  FM A-4 %.3f Hz, release %d ticks, setup %.0f us\n", hz, ticks, setup);
    free(both);
    free(tail);
    free(buf);
    /* PSG square on PSG 1: period 254 */
    const double hz_psg = audition_hz(m, &g_song, 6, A4, SQUARE_SLOT, 0, &buf, &frames);
    EXPECT(fabs(hz_psg - psg_hz(0, 254)) < 0.3 && fabs(1200 * log2(hz_psg / 440.0)) < 2.0, "PSG A-4 measured %.3f Hz (period 254 = %.3f)",
           hz_psg, psg_hz(0, 254));
    EXPECT(ymm_release(m) == 1, "PSG release phase");
    const int psg_ticks = release_ticks(m, &tail, &tail_frames);
    EXPECT(psg_ticks >= 30 && psg_ticks <= 34, "PSG release plays the volume envelope's tail (%d ticks: 30 values from the release point)",
           psg_ticks);
    both = (float*)malloc(sizeof(float) * 2 * (size_t)(frames + tail_frames));
    memcpy(both, buf, sizeof(float) * 2 * (size_t)frames);
    memcpy(both + 2 * frames, tail, sizeof(float) * 2 * (size_t)tail_frames);
    write_wav(dir, "ym_tone_psg.wav", both, frames + tail_frames);
    printf("  PSG A-4 %.3f Hz, release %d ticks\n", hz_psg, psg_ticks);
    free(both);
    free(tail);
    free(buf);
    /* a volume envelope without a release point: key-off is silent at once (the driver executes it next tick) */
    EXPECT(ymm_audition(m, &g_song, 7, 60, PLUCK_SLOT, 0) == YMM_OK, "pluck");
    for (int i = 0; i < 4; ++i) ymm_tick(m, g_out);
    EXPECT(ymm_release(m) == 1, "pluck release");
    const int pluck_ticks = release_ticks(m, &tail, &tail_frames);
    EXPECT(pluck_ticks >= 1 && pluck_ticks <= 3, "pluck (no release point) stops at key-off (%d ticks)", pluck_ticks);
    free(tail);
    /* the Noise channel: pitched white noise (PSG 3's period follows the note and the relative pitch envelope) */
    EXPECT(ymm_audition(m, &g_song, 9, 48, TOM_SLOT, 0) == YMM_OK, "tom on Noise");
    const YmPlayer* np = ymm_player(m);
    ymm_tick(m, g_out);
    const int period0 = np->tone[2];
    for (int i = 0; i < 30; ++i) ymm_tick(m, g_out);
    EXPECT(np->noise_ctrl == (4 | 3) && np->tone[2] > period0 && np->att[3] < 15,
           "pitched noise: mode 7, PSG 3's period falls in pitch (%d -> %d), audible", period0, np->tone[2]);
    float* nb;
    const int nf = render(m, 10, &nb);
    EXPECT(rms(nb, nf) > 0.002, "noise audition sounds (rms %.4f)", rms(nb, nf));
    free(nb);
    EXPECT(ymm_release(m) == 1 && release_ticks(m, &tail, &tail_frames) <= 3, "noise without a release point stops at key-off");
    free(tail);
    /* PAL: A-4 on both chips */
    const double hz_pal = audition_hz(m, &g_pal, 2, A4, SINE_SLOT, 0, NULL, NULL);
    const double hz_pal_psg = audition_hz(m, &g_pal, 7, A4, SQUARE_SLOT, 0, NULL, NULL);
    EXPECT(fabs(1200 * log2(hz_pal / 440.0)) < 1.5 && fabs(1200 * log2(hz_pal_psg / 440.0)) < 2.0,
           "PAL A-4: FM %.3f Hz, PSG %.3f Hz", hz_pal, hz_pal_psg);
    /* fine pitch: +64 units = +1 semitone; FM 6 */
    const double up = audition_hz(m, &g_song, 5, A4, SINE_SLOT, 64, NULL, NULL);
    EXPECT(fabs(1200 * log2(up / 466.164)) < 1.5, "fine +64 on FM 6 = A#4 %.3f Hz", up);
    /* every algorithm sounds (the test song's instruments 3-10 are algorithms 0-7) */
    for (int slot = 3; slot <= 10; ++slot) {
        EXPECT(ymm_audition(m, &g_song, slot % 6, 48, slot, 0) == YMM_OK, "algorithm %d", slot - 3);
        float* b;
        const int f = render(m, 20, &b);
        EXPECT(rms(b, f) > 0.005, "algorithm %d sounds (rms %.4f)", slot - 3, rms(b, f));
        free(b);
    }
    /* the kick: C-2 + 36 semitones (C-5, 523.25 Hz) down to C-2 (65.41 Hz) at 4 semitones per tick */
    EXPECT(ymm_audition(m, &g_song, 0, 24, KICK_SLOT, 0) == YMM_OK, "kick");
    const YmPlayer* p = ymm_player(m);
    const double h0 = fm_hz(0, p->ym[0][0xA4], p->ym[0][0xA0]);
    for (int i = 0; i < 10; ++i) ymm_tick(m, g_out);
    const double h1 = fm_hz(0, p->ym[0][0xA4], p->ym[0][0xA0]);
    EXPECT(fabs(1200 * log2(h0 / 523.25)) < 2 && fabs(1200 * log2(h1 / 65.41)) < 2, "kick sweep %.2f -> %.2f Hz", h0, h1);
    EXPECT(ymm_audition(m, &g_song, 0, A4, SINE_SLOT, 0) == YMM_OK && ymm_release(m) == 0, "release before any tick: cut");
    EXPECT(ymm_audition(m, &g_song, 6, 48, SINE_SLOT, 0) == YMM_ERR_IMAGE, "FM instrument on PSG 1 rejected");
    EXPECT(ymm_audition(m, &g_song, 0, 48, SQUARE_SLOT, 0) == YMM_ERR_IMAGE, "PSG instrument on FM 1 rejected");
    EXPECT(ymm_audition(m, &g_song, 0, 48, 30, 0) == YMM_ERR_IMAGE, "undefined slot rejected");
    EXPECT(ymm_audition(m, &g_song, 0, 96, 1, 0) == YMM_ERR_IMAGE, "note 96 rejected");
    EXPECT(ymm_audition(m, &g_song, YM_CH_PCM, 48, SQUARE_SLOT, 0) == YMM_ERR_IMAGE, "PSG instrument on PCM 1 rejected");
    EXPECT(ymm_audition(m, &g_song, YM_CHANNELS, 48, 1, 0) == YMM_ERR_IMAGE, "channel 13 rejected");
    EXPECT(ymm_audition(m, &g_song, 0, 48, 1, 200) == YMM_ERR_IMAGE, "fine 200 rejected");
    int pslot, pnote, uslot, unote;
    const int fm_slot = find_slot(&g_pcm, YM_KIND_FM);
    if (find_key(&g_pcm, 1, &pslot, &pnote) && find_unmapped(&g_pcm, &uslot, &unote)) {
        EXPECT(ymm_audition(m, &g_pcm, YM_CH_PCM + 2, pnote, pslot, 0) == YMM_OK, "PCM note on PCM 3");
        EXPECT(ymm_audition(m, &g_pcm, YM_CH_PCM, unote, uslot, 0) == YMM_ERR_IMAGE, "unmapped PCM note rejected");
        EXPECT(ymm_audition(m, &g_pcm, 0, pnote, pslot, 0) == YMM_ERR_IMAGE, "PCM instrument on FM 1 rejected");
        EXPECT(ymm_audition(m, &g_pcm, YM_CH_PCM, pnote, fm_slot, 0) == YMM_ERR_IMAGE, "FM instrument on PCM 1 rejected");
    } else {
        EXPECT(0, "the PCM song has a mapped and an unmapped PCM key");
    }
    ymm_destroy(m);
}

/* Muting removes audio, not the feedback history needed by the next audible sample. */
static void test_muted_feedback(void) {
    YmOpn2* continuous = ymo_create();
    YmOpn2* muted = ymo_create();
    const uint8_t registers[][2] = {
        {0x30, 1}, {0x40, 0}, {0x50, 0x1f}, {0x60, 0}, {0x70, 0}, {0x80, 0x0f},
        {0xb0, 0x3f}, {0xb4, 0xc0}, {0xa4, 0x22}, {0xa0, 0x69}, {0x28, 0x10},
    };
    for (unsigned r = 0; r < sizeof registers / sizeof registers[0]; ++r) {
        ymo_write(continuous, 0, 0, registers[r][0]);
        ymo_write(continuous, 0, 1, registers[r][1]);
        ymo_write(muted, 0, 0, registers[r][0]);
        ymo_write(muted, 0, 1, registers[r][1]);
    }
    int bad = 0;
    for (int sample = 0; sample < 4096; ++sample) {
        int32_t left, right, muted_left, muted_right;
        const unsigned mask = sample >= 512 && sample < 2560 ? 1u : 0u;
        ymo_generate(continuous, 0, &left, &right);
        ymo_generate(muted, mask, &muted_left, &muted_right);
        if (!mask && (left != muted_left || right != muted_right)) ++bad;
    }
    EXPECT(bad == 0, "unmute preserves exact FM feedback history (%d audible samples differ)", bad);
    ymo_destroy(continuous);
    ymo_destroy(muted);
}

static void test_mute(void) {
    printf("mute\n");
    YmMachine* m = ymm_create(RATE);
    int mode, bad = 0;
    ymm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    ymm_mute(m, 3, 1);
    ymm_mute(m, 8, 1);
    for (int k = 1; k < YM_REF_TICKS; ++k) {
        ymm_tick(m, g_out);
        if (!same(ymm_player(m), &REF_SONG, k + 1)) ++bad;
    }
    EXPECT(bad == 0, "muted channels leave the player's writes unchanged (%d differ)", bad);
    ymm_start(m, &g_song, 1, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 30; ++k) ymm_tick(m, g_out);
    for (int c = 0; c < YM_CHANNELS; ++c) ymm_mute(m, c, 1);
    float* buf;
    int frames = render(m, 30, &buf);
    const double quiet = rms(buf + 2 * (RATE / 10), frames - RATE / 10);
    EXPECT(quiet < 0.0005, "all channels muted: rms %.6f after 100 ms", quiet);
    free(buf);
    for (int c = 0; c < YM_CHANNELS; ++c) ymm_mute(m, c, 0);
    frames = render(m, 30, &buf);
    EXPECT(rms(buf, frames) > 0.01, "unmuted: resumes mid-note (rms %.4f)", rms(buf, frames));
    free(buf);
    /* FM 6 alone (the driver's DAC is off without PCM) */
    ymm_start(m, &g_song, 1, 0, SEEK_BUDGET, &mode);
    for (int c = 0; c < YM_CHANNELS; ++c) ymm_mute(m, c, c != 5);
    frames = render(m, 30, &buf);
    EXPECT(rms(buf, frames) > 0.005, "FM 6 alone sounds (rms %.4f)", rms(buf, frames));
    free(buf);
    /* Noise alone, PSG alone */
    ymm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    for (int c = 0; c < YM_CHANNELS; ++c) ymm_mute(m, c, c != 9);
    frames = render(m, 60, &buf);
    EXPECT(rms(buf, frames) > 0.005, "Noise alone sounds (rms %.4f)", rms(buf, frames));
    free(buf);
    ymm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    for (int c = 0; c < YM_CHANNELS; ++c) ymm_mute(m, c, c != 7);
    frames = render(m, 60, &buf);
    EXPECT(rms(buf, frames) > 0.002, "PSG 2 alone sounds (rms %.4f)", rms(buf, frames));
    free(buf);
    ymm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    for (int c = 0; c < YM_CHANNELS; ++c) ymm_mute(m, c, 1);
    frames = render(m, 60, &buf);
    EXPECT(rms(buf, frames) < 0.0005, "muted before the first tick: rms %.6f", rms(buf, frames));
    free(buf);
    ymm_start(m, &g_song, 1, 17, SEEK_BUDGET, &mode); /* after a seek */
    for (int c = 0; c < YM_CHANNELS; ++c) ymm_mute(m, c, 1);
    frames = render(m, 60, &buf);
    EXPECT(rms(buf + 2 * (RATE / 10), frames - RATE / 10) < 0.0005, "muted right after a seek: rms %.6f after 100 ms",
           rms(buf + 2 * (RATE / 10), frames - RATE / 10));
    free(buf);
    /* the PCM song with every channel muted (PCM stops through the driver) */
    ymm_start(m, &g_pcm, 0, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 120; ++k) ymm_tick(m, g_out);
    for (int c = 0; c < YM_CHANNELS; ++c) ymm_mute(m, c, 1);
    frames = render(m, 60, &buf);
    EXPECT(rms(buf + 2 * (RATE / 10), frames - RATE / 10) < 0.0005 && !ymm_pcm_playing(m),
           "PCM song, all muted: rms %.6f after 100 ms, no PCM playing", rms(buf + 2 * (RATE / 10), frames - RATE / 10));
    free(buf);
    EXPECT(ymm_mute(m, YM_CHANNELS, 1) == YMM_ERR_IMAGE && ymm_mute(m, -1, 1) == YMM_ERR_IMAGE, "channels 13 and -1 rejected");
    ymm_destroy(m);
}

static void test_pal(void) {
    printf("PAL\n");
    YmMachine* m = ymm_create(RATE);
    int mode, frames = 0, bad = 0;
    ymm_start(m, &g_pal, 0, 0, SEEK_BUDGET, &mode);
    for (int k = 1; k < YM_REF_TICKS; ++k) {
        if (!pos_is(m, &REF_PAL, k)) ++bad;
        const int n = ymm_tick(m, g_out);
        if (k <= 50) frames += n;
        if (!same(ymm_player(m), &REF_PAL, k + 1)) ++bad;
    }
    EXPECT(bad == 0, "PAL: player writes and positions = reference (%d differ)", bad);
    const int want = (int)(50 * RATE / PAL_TICK_HZ + 0.5);
    EXPECT(abs(frames - want) <= 2, "50 PAL ticks = %d host frames (49.70 Hz; got %d)", want, frames);
    EXPECT(ymm_z80_cycles_per_tick(m) == 71364, "PAL tick = 71364 Z80 cycles (%u)", ymm_z80_cycles_per_tick(m));
    ymm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    ymm_tick(m, g_out);
    EXPECT(ymm_z80_cycles_per_tick(m) == 59736, "NTSC tick = 59736 Z80 cycles (%u)", ymm_z80_cycles_per_tick(m));
    ymm_destroy(m);
}

/* No allocation by the calls a host makes on its audio thread (glibc: counted through the interposed allocator). */
static void test_allocations(void) {
    printf("allocations\n");
#if ALLOC_COUNTED
    YmMachine* m = ymm_create(RATE);
    int mode, o, r, t;
    long starts[2], audits[2];
    for (int i = 0; i < 2; ++i) {
        g_allocs = g_frees = 0;
        g_counting = 1;
        ymm_start(m, &g_pcm, 0, 0, SEEK_BUDGET, &mode);
        g_counting = 0;
        starts[i] = g_allocs;
        EXPECT(g_allocs == g_frees, "start: allocations balanced (%ld allocated, %ld freed: the chip reset, ym_ymfm.h)", g_allocs, g_frees);
    }
    EXPECT(starts[0] == starts[1], "start: the same allocations every time (%ld, %ld)", starts[0], starts[1]);
    g_allocs = g_frees = 0;
    g_counting = 1;
    for (int k = 0; k < 900; ++k) {
        ymm_apply_image(m, (k % 3) == 0 ? &g_pcm_moved : (k % 3) == 1 ? &g_pcm_edited : &g_pcm);
        if (k % 100 == 0) ymm_mute(m, YM_CH_PCM + (k / 100) % 3, (k / 100) & 1);
        if (k % 150 == 0) ymm_mute(m, k / 150 % YM_FM_CHANNELS, (k / 150) & 1);
        ymm_tick(m, g_out);
        ymm_position(m, &o, &r, &t);
        (void)ymm_row_serial(m);
    }
    g_counting = 0;
    EXPECT(g_allocs == 0 && g_frees == 0, "900 ticks with live edits, mutes and positions: no allocation (%ld, %ld frees)", g_allocs, g_frees);
    int pslot = 0, pnote = 0;
    find_key(&g_pcm, 1, &pslot, &pnote);
    for (int i = 0; i < 2; ++i) {
        g_allocs = g_frees = 0;
        g_counting = 1;
        ymm_audition(m, i ? &g_pcm : &g_song, i ? YM_CH_PCM : 0, i ? pnote : A4, i ? pslot : SINE_SLOT, 0);
        g_counting = 0;
        audits[i] = g_allocs;
        EXPECT(g_allocs == g_frees, "audition: allocations balanced (%ld, %ld freed: the chip reset)", g_allocs, g_frees);
        g_allocs = g_frees = 0;
        g_counting = 1;
        for (int k = 0; k < 30; ++k) ymm_tick(m, g_out);
        ymm_release(m);
        for (int k = 0; k < 600 && ymm_tick(m, g_out) > 0; ++k) {}
        g_counting = 0;
        EXPECT(g_allocs == 0 && g_frees == 0, "audition ticks and release: no allocation (%ld)", g_allocs);
    }
    printf("  start %ld and audition %ld/%ld allocations (balanced: the YM2612 core's reset); ticks, edits, mutes: none\n",
           starts[0], audits[0], audits[1]);
    ymm_destroy(m);
#else
    printf("  not counted: allocation interposition needs glibc\n");
#endif
}

/* ---- exports ---------------------------------------------------------------------------------------------------- */

/* First arrival at (loop, 0, 0) and the ticks of one pass, by the player alone. */
static void loop_ticks(const YmImage* im, uint32_t* first, uint32_t* pass) {
    static YmPlayer p;
    ymp_reset(&p, im);
    int seen = 0;
    *first = *pass = 0;
    for (uint32_t t = 0; t < 200000; ++t) {
        if (p.order == im->info.loop && p.row == 0 && p.tick == 0) {
            if (seen) {
                *pass = t - *first;
                return;
            }
            seen = 1;
            *first = t;
        }
        ymp_tick(&p, im);
    }
}

typedef struct VgmScan {
    uint64_t samples, at_loop;
    uint32_t dac_cmds, data_size;
    int hit_loop, raw_dac, timer, bad27;
    const char* problem;
} VgmScan;

static void scan_vgm(const uint8_t* f, size_t size, VgmScan* v) {
    memset(v, 0, sizeof *v);
    const uint32_t loop = u32le(f + 0x1C) ? 0x1C + u32le(f + 0x1C) : 0;
    size_t at = 0x34 + u32le(f + 0x34);
    int last27 = -1;
    while (at < size) {
        if (at == loop) {
            v->hit_loop = 1;
            v->at_loop = v->samples;
            if (f[at] != 0xE0) v->problem = "the loop offset is not at a 0xE0";
        }
        const int c = f[at];
        if (c == 0x66) return;
        if (c == 0x67) {
            if (f[at + 1] != 0x66) v->problem = "data block without 0x66";
            if (f[at + 2] == 0x00) v->data_size += u32le(f + at + 3);
            at += 7 + u32le(f + at + 3);
        } else if (c == 0xE0) {
            at += 5;
        } else if (c == 0x50) {
            at += 2;
        } else if (c == 0x52 || c == 0x53) {
            const int reg = f[at + 1], val = f[at + 2];
            if (c == 0x52 && reg == 0x2A) ++v->raw_dac;
            if (c == 0x52 && reg >= 0x24 && reg <= 0x26) ++v->timer;
            if (c == 0x52 && reg == 0x27) {
                if ((val & 0x3F) || val == last27) ++v->bad27;
                last27 = val;
            }
            at += 3;
        } else if (c == 0x61) {
            v->samples += u16le(f + at + 1);
            at += 3;
        } else if (c == 0x62 || c == 0x63) {
            v->samples += c == 0x62 ? 735 : 882;
            ++at;
        } else if (c >= 0x70 && c <= 0x7F) {
            v->samples += (uint64_t)(c & 15) + 1;
            ++at;
        } else if (c >= 0x80 && c <= 0x8F) {
            ++v->dac_cmds;
            v->samples += (uint64_t)(c & 15);
            ++at;
        } else {
            v->problem = "a command the export does not write";
            return;
        }
    }
    v->problem = "no end command";
}

/* An XGM2 stream as frames: unpacked from an .xgm (commands up to each frame end) or a .xgc (one packed block per
 * frame, page/bank rules checked against the stream offset). */
typedef struct StreamFrames {
    int n;
    uint32_t* at;     /* stream offset of frame i */
    uint32_t* tick;   /* tick frame i starts */
    uint32_t* data_at;
    int* len;
    uint8_t* data;    /* unpacked frames back to back */
    uint32_t total;   /* ticks the stream lasts */
    int32_t loop;     /* the loop command's offset, -1 = end */
    const char* problem;
} StreamFrames;

static void stream_frames(const uint8_t* s, uint32_t size, int psg, int packed, StreamFrames* out) {
    memset(out, 0, sizeof *out);
    out->loop = -1;
    out->at = (uint32_t*)malloc(sizeof(uint32_t) * (size + 1));
    out->tick = (uint32_t*)malloc(sizeof(uint32_t) * (size + 1));
    out->data_at = (uint32_t*)malloc(sizeof(uint32_t) * (size + 1));
    out->len = (int*)malloc(sizeof(int) * (size + 1));
    out->data = (uint8_t*)malloc((size_t)size * 2 + 512);
    uint32_t at = 0, tick = 0, used = 0;
    while (at < size) {
        uint8_t buf[512];
        int n;
        const char* why = NULL;
        if (packed) {
            const int k = unpack_block(s + at, size - at, at, buf, sizeof buf, &n, &why);
            if (k < 0) {
                out->problem = why;
                return;
            }
            if (k > YMX_PACKED_MAX) out->problem = "a packed block above YMX_PACKED_MAX";
            out->at[out->n] = at;
            at += (uint32_t)k;
        } else {
            n = frame_length(s + at, size - at, psg);
            if (n < 0 || n > (int)sizeof buf) {
                out->problem = "an unpacked frame without its end";
                return;
            }
            memcpy(buf, s + at, (size_t)n);
            out->at[out->n] = at;
            at += (uint32_t)n;
        }
        FrameInfo fi;
        why = parse_frame(buf, n, psg, &fi);
        if (why) {
            out->problem = why;
            return;
        }
        out->tick[out->n] = tick;
        out->data_at[out->n] = used;
        out->len[out->n] = n;
        memcpy(out->data + used, buf, (size_t)n);
        used += (uint32_t)n;
        ++out->n;
        if (fi.is_loop) {
            out->loop = fi.loop;
            out->total = tick + 1;
            if (at != size) out->problem = "stream data after the loop command";
            return;
        }
        tick += (uint32_t)fi.wait;
    }
    out->problem = "a stream without its loop/end command";
}

static void free_frames(StreamFrames* f) {
    free(f->at);
    free(f->tick);
    free(f->data_at);
    free(f->len);
    free(f->data);
}

static int frame_at_tick(const StreamFrames* f, uint32_t tick) {
    for (int i = 0; i < f->n; ++i)
        if (f->tick[i] == tick) return i;
    return -1;
}

/* The XGM2 file's header, sample table, sample data and padding. Returns the streams' offsets. */
static int check_xgm2(const uint8_t* f, size_t size, int format, const YmImage* im, const YmVgmInfo* info, const char* what,
                      uint32_t* fm_at, uint32_t* psg_at) {
    const int h = format == YM_FORMAT_XGM ? 4 : 0;
    const int want_flags = (im->info.region == YM_PAL ? 1 : 0) | (format == YM_FORMAT_XGM ? 4 : 8);
    const uint32_t slen = u16le(f + h + 2), fmlen = u16le(f + h + 4), psglen = u16le(f + h + 6);
    const uint32_t data = (uint32_t)h + 0x100;
    EXPECT((format == YM_FORMAT_XGC || !memcmp(f, "XGM2", 4)) && f[h] == YM_XGM2_VERSION && f[h + 1] == want_flags,
           "%s: ident, version $10, flags $%02X (got $%02X)", what, want_flags, f[h + 1]);
    EXPECT(data == (format == YM_FORMAT_XGM ? YM_XGM2_HEADER : YM_XGC_HEADER), "%s: header size", what);
    EXPECT(slen == (uint32_t)im->info.pcm_pages && fmlen * 256 == ((info->fm_size + 255) & ~255u) &&
               psglen * 256 == ((info->psg_size + 255) & ~255u) && size == data + (slen + fmlen + psglen) * 256,
           "%s: SLEN %u, FMLEN %u, PSGLEN %u fit the file (%zu bytes)", what, slen, fmlen, psglen, size);
    int sid_bad = 0;
    const int v = im->info.pcm_variants;
    for (int i = 0; i < 124; ++i) {
        const uint32_t e = u16le(f + h + 8 + 2 * i);
        const uint32_t want = i < v ? (uint32_t)variant_start(im, i + 1) : i == v && v ? (uint32_t)im->info.pcm_pages : 0xFFFFu;
        sid_bad += e != want;
    }
    EXPECT(sid_bad == 0, "%s: 124-entry sample table = the variants' start pages, their end, then $FFFF (%d wrong)", what, sid_bad);
    EXPECT(!memcmp(f + data, im->bytes + YM_OFF_PCM_BANK, (size_t)slen * 256), "%s: sample data = the compiled bank", what);
    *fm_at = data + slen * 256;
    *psg_at = *fm_at + fmlen * 256;
    int pad = 0;
    for (uint32_t i = *fm_at + info->fm_size; i < *psg_at; ++i) pad |= f[i];
    for (uint32_t i = *psg_at + info->psg_size; i < (uint32_t)size; ++i) pad |= f[i];
    EXPECT(pad == 0, "%s: stream padding zero", what);
    return 0;
}

static void test_export_image(const char* dir, const YmImage* im, const char* name, const char* prefix) {
    printf("exports (%s)\n", name);
    uint32_t first, pass;
    loop_ticks(im, &first, &pass);
    EXPECT(pass > 0, "%s: the song loops (first arrival tick %u, pass %u)", name, first, pass);
    if (im == &g_song) EXPECT(pass == g_pass_ticks, "%s: one pass = the machine's loop pass (%u ticks)", name, g_pass_ticks);
    const double spt = (double)mclk_per_tick(im) * 44100.0 / mclk_hz(im);
    YmVgmInfo info;
    const char* problem = NULL;
    double t0 = now_us();
    uint8_t* vgm = ym_render(im, YM_FORMAT_VGM, EXPORT_TICKS, &info, &problem);
    const double vgm_us = now_us() - t0;
    EXPECT(vgm && !problem, "%s: VGM renders (%s)", name, problem ? problem : "ok");
    if (vgm) {
        const int pal = im->info.region == YM_PAL;
        EXPECT(!memcmp(vgm, "Vgm ", 4) && u32le(vgm + 8) == 0x171 && u32le(vgm + 0x34) == 0xCC && u32le(vgm + 4) == info.size - 4 &&
                   u32le(vgm + 0x0C) == (pal ? 3546895u : 3579545u) && u32le(vgm + 0x2C) == (pal ? 7600489u : 7670453u) &&
                   u32le(vgm + 0x24) == (pal ? 50u : 60u) && vgm[0x28] == 9 && vgm[0x2A] == 16,
               "%s: VGM 1.71 header (clocks, rate, data at 0x100)", name);
        VgmScan v;
        scan_vgm(vgm, info.size, &v);
        EXPECT(!v.problem, "%s: VGM commands parse (%s)", name, v.problem ? v.problem : "ok");
        EXPECT(v.samples == u32le(vgm + 0x18) && v.samples == info.samples && v.hit_loop &&
                   v.samples - v.at_loop == u32le(vgm + 0x20) && info.looped,
               "%s: VGM total %llu and loop %llu samples = header, loop at its 0xE0", name, (unsigned long long)v.samples,
               (unsigned long long)(v.samples - v.at_loop));
        EXPECT(fabs((double)info.loop_samples - pass * spt) <= 2 && fabs((double)info.samples - (first + pass) * spt) <= 2,
               "%s: VGM loop %u samples = one pass of %u ticks (%.1f), total %u = %u ticks (%.1f)", name, info.loop_samples, pass,
               pass * spt, info.samples, first + pass, (first + pass) * spt);
        EXPECT(v.dac_cmds == info.dac_bytes && v.data_size == info.dac_bytes && v.raw_dac == 0,
               "%s: 0x8n commands (%u) = the PCM data block (%u) = dac_bytes (%u), no raw $2A writes", name, v.dac_cmds, v.data_size,
               info.dac_bytes);
        EXPECT(v.timer == 0 && v.bad27 == 0, "%s: no timer writes, $27 only on FM 3 mode changes", name);
        if (im->info.pcm_variants) EXPECT(info.dac_bytes > 0, "%s: the DAC's bytes are in the VGM (%u)", name, info.dac_bytes);
        else EXPECT(info.dac_bytes == 0, "%s: no DAC data without PCM", name);
        printf("  VGM %zu bytes, %u ticks, loop %u samples, %u DAC bytes, rendered in %.0f us\n", info.size, info.ticks,
               info.loop_samples, info.dac_bytes, vgm_us);
        free(vgm);
    }
    YmVgmInfo xi, ci;
    t0 = now_us();
    uint8_t* xgm = ym_render(im, YM_FORMAT_XGM, EXPORT_TICKS, &xi, &problem);
    const double xgm_us = now_us() - t0;
    t0 = now_us();
    uint8_t* xgc = ym_render(im, YM_FORMAT_XGC, EXPORT_TICKS, &ci, &problem);
    const double xgc_us = now_us() - t0;
    EXPECT(xgm && xgc, "%s: XGM and XGC render", name);
    if (xgm && xgc) {
        uint32_t xfm, xpsg, cfm, cpsg;
        check_xgm2(xgm, xi.size, YM_FORMAT_XGM, im, &xi, "XGM", &xfm, &xpsg);
        check_xgm2(xgc, ci.size, YM_FORMAT_XGC, im, &ci, "XGC", &cfm, &cpsg);
        EXPECT(xi.ticks == first + pass && ci.ticks == xi.ticks && xi.looped && ci.looped, "%s: %u ticks (loop + one pass)", name,
               xi.ticks);
        for (int s = 0; s < 2; ++s) {
            StreamFrames a, b;
            stream_frames(xgm + (s ? xpsg : xfm), s ? xi.psg_size : xi.fm_size, s, 0, &a);
            stream_frames(xgc + (s ? cpsg : cfm), s ? ci.psg_size : ci.fm_size, s, 1, &b);
            const char* sn = s ? "PSG" : "FM";
            EXPECT(!a.problem && !b.problem, "%s %s: .xgm and .xgc streams parse (%s / %s)", name, sn, a.problem ? a.problem : "ok",
                   b.problem ? b.problem : "ok");
            int diff = 0;
            for (int i = 0; i < a.n && a.n == b.n; ++i) {
                const int n = i == a.n - 1 ? a.len[i] - 3 : a.len[i];
                diff += a.tick[i] != b.tick[i] || a.len[i] != b.len[i] || memcmp(a.data + a.data_at[i], b.data + b.data_at[i], (size_t)n);
            }
            EXPECT(a.n == b.n && diff == 0, "%s %s: the .xgc's %d blocks unpack to the .xgm's %d frames (%d differ)", name, sn, b.n,
                   a.n, diff);
            EXPECT(a.total == xi.ticks && b.total == xi.ticks, "%s %s: the streams last %u and %u ticks (= %u)", name, sn, a.total,
                   b.total, xi.ticks);
            const int la = frame_at_tick(&a, first), lb = frame_at_tick(&b, first);
            EXPECT(la >= 0 && lb >= 0 && a.loop == (int32_t)a.at[la] && b.loop == (int32_t)b.at[lb],
                   "%s %s: both loops land on the loop frame (tick %u: .xgm %d, .xgc %d)", name, sn, first, a.loop, b.loop);
            EXPECT(s ? xi.psg_loop_offset == (uint32_t)a.loop && ci.psg_loop_offset == (uint32_t)b.loop
                     : xi.loop_offset == (uint32_t)a.loop && ci.loop_offset == (uint32_t)b.loop,
                   "%s %s: the infos' loop offsets", name, sn);
            if (la >= 0) {
                FrameInfo fi;
                parse_frame(a.data + a.data_at[la], a.len[la], s, &fi);
                EXPECT(s ? fi.psg_tone == 3 && fi.psg_noise == 1 && fi.psg_att == 4 : fi.load_inst == 6,
                       "%s %s: the loop frame is a full frame", name, sn);
            }
            free_frames(&a);
            free_frames(&b);
        }
        printf("  XGM %zu bytes, XGC %zu bytes (FM %u / %u, PSG %u / %u stream bytes); rendered in %.0f / %.0f us\n", xi.size,
               ci.size, xi.fm_size, ci.fm_size, xi.psg_size, ci.psg_size, xgm_us, xgc_us);
    }
    free(xgm);
    free(xgc);
    /* a tick limit before the loop closes: no loop */
    uint8_t* cut = ym_render(im, YM_FORMAT_XGC, 50, &ci, &problem);
    if (cut) {
        StreamFrames b;
        uint32_t cfm, cpsg;
        check_xgm2(cut, ci.size, YM_FORMAT_XGC, im, &ci, "XGC (50 ticks)", &cfm, &cpsg);
        stream_frames(cut + cfm, ci.fm_size, 0, 1, &b);
        EXPECT(!b.problem && b.loop == -1 && b.total == 50 && !ci.looped, "%s: 50-tick XGC ends without a loop (%u ticks)", name, b.total);
        free_frames(&b);
    }
    EXPECT(cut != NULL, "%s: 50-tick XGC renders", name);
    free(cut);
    /* the evidence files through the hosts' entry point */
    static const char* ext[3] = {"vgm", "xgm", "xgc"};
    for (int format = 0; format < 3; ++format) {
        size_t size = 0;
        uint8_t* file = ym_export(im->bytes, sizeof im->bytes, format, EXPORT_TICKS, "Thor Tracker test song", "Thor Tracker",
                                  "2026/11/09", &size, &problem);
        EXPECT(file != NULL, "%s: ym_export %s (%s)", name, ext[format], problem ? problem : "ok");
        if (file) {
            char path[64];
            snprintf(path, sizeof path, "%s_song.%s", prefix, ext[format]);
            write_file(dir, path, file, size);
        }
        ym_export_free(file);
    }
}

/* GD3 strings of a tag at g, decoded back to UTF-8. Returns the end. */
static const uint8_t* gd3_fields(const uint8_t* g, char fields[11][64]) {
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
    return s;
}

/* The hosts' entry point (ym_export.h): ym_render's VGM / XGM plus a GD3 tag, its XGC without one; errors. */
static void test_export_entry(void) {
    printf("export entry point\n");
    size_t size = 0;
    const char* problem = NULL;
    const char* title = "Thor \xC3\x96pening \xF0\x9F\x8E\xB9"; /* "Thor Öpening 🎹": 2-byte and 4-byte UTF-8 */
    YmVgmInfo info;
    uint8_t* ref = ym_render(&g_pcm, YM_FORMAT_VGM, EXPORT_TICKS, &info, &problem);
    uint8_t* vgm = ym_export(g_pcm.bytes, sizeof g_pcm.bytes, YM_EXPORT_VGM, EXPORT_TICKS, title, "Esko", "2026/11/08", &size, &problem);
    EXPECT(vgm && ref && !problem, "VGM renders (%s)", problem ? problem : "ok");
    if (vgm && ref) {
        const uint32_t gd3_at = 0x14 + u32le(vgm + 0x14);
        const uint32_t eof = 4 + u32le(vgm + 4);
        EXPECT(gd3_at == info.size && eof == size, "GD3 after the music data, EOF offset = size");
        memcpy(vgm + 4, ref + 4, 4);
        memset(vgm + 0x14, 0, 4);
        EXPECT(!memcmp(vgm, ref, info.size), "the data = ym_render's VGM (header 0x04/0x14 aside)");
        char fields[11][64];
        const uint8_t* end = gd3_fields(vgm + gd3_at, fields);
        EXPECT(!memcmp(vgm + gd3_at, "Gd3 ", 4) && end == vgm + size, "GD3 1.00, 11 strings fill the tag");
        EXPECT(!strcmp(fields[0], title) && !strcmp(fields[4], "Mega Drive (Thor Tracker)") && !strcmp(fields[6], "Esko") &&
                   !strcmp(fields[8], "2026/11/08") && !strcmp(fields[9], "Thor Tracker") && !fields[2][0] && !fields[10][0],
               "GD3 fields: '%s', '%s', '%s', '%s', '%s'", fields[0], fields[4], fields[6], fields[8], fields[9]);
    }
    free(ref);
    ym_export_free(vgm);
    ref = ym_render(&g_pcm, YM_FORMAT_XGM, EXPORT_TICKS, &info, &problem);
    uint8_t* xgm = ym_export(g_pcm.bytes, sizeof g_pcm.bytes, YM_EXPORT_XGM, EXPORT_TICKS, title, "Esko", "2026/11/08", &size, &problem);
    EXPECT(xgm && ref && size > info.size, "XGM renders");
    if (xgm && ref) {
        EXPECT(xgm[5] & 0x04 && !memcmp(xgm, ref, info.size), "the XGM data = ym_render's, GD3 flag set");
        char fields[11][64];
        const uint8_t* end = gd3_fields(xgm + info.size, fields);
        EXPECT(!memcmp(xgm + info.size, "Gd3 ", 4) && end == xgm + size && !strcmp(fields[0], title), "XGM GD3 tag after the music data");
    }
    free(ref);
    ym_export_free(xgm);
    ref = ym_render(&g_pcm, YM_FORMAT_XGC, EXPORT_TICKS, &info, &problem);
    uint8_t* xgc = ym_export(g_pcm.bytes, sizeof g_pcm.bytes, YM_EXPORT_XGC, EXPORT_TICKS, title, NULL, NULL, &size, &problem);
    EXPECT(xgc && ref && size == info.size && !memcmp(xgc, ref, size) && !(xgc[1] & 0x04), "XGC = ym_render's, no tag");
    free(ref);
    ym_export_free(xgc);
    copy_image(&g_bad, &g_song);
    g_bad.bytes[YM_H_REGION] = 2;
    uint8_t* f = ym_export(g_bad.bytes, sizeof g_bad.bytes, YM_EXPORT_VGM, EXPORT_TICKS, NULL, NULL, NULL, &size, &problem);
    EXPECT(!f && size == 0 && problem && strstr(problem, "region"), "bad image: NULL, '%s'", problem ? problem : "");
    f = ym_export(g_song.bytes, 100, YM_EXPORT_VGM, EXPORT_TICKS, NULL, NULL, NULL, &size, &problem);
    EXPECT(!f && problem && !strcmp(problem, "image size"), "short image: '%s'", problem ? problem : "");
    f = ym_export(g_song.bytes, 65536, YM_EXPORT_VGM, EXPORT_TICKS, NULL, NULL, NULL, &size, &problem);
    EXPECT(!f && problem && !strcmp(problem, "image size"), "a layout-1 sized image: '%s'", problem ? problem : "");
    f = ym_export(g_song.bytes, sizeof g_song.bytes, 3, EXPORT_TICKS, NULL, NULL, NULL, &size, &problem);
    EXPECT(!f && problem && !strcmp(problem, "format"), "unknown format: '%s'", problem ? problem : "");
    f = ym_export(g_song.bytes, sizeof g_song.bytes, YM_EXPORT_VGM, 100, NULL, NULL, NULL, &size, &problem);
    EXPECT(f && (u32le(f + 0x1C) | u32le(f + 0x20)) == 0 && size > 0x100, "tick limit before the loop closes: no loop");
    if (f) {
        const uint8_t* d = f + 0x14 + u32le(f + 0x14) + 12;
        for (int i = 0; i < 8; ++i, d += 2)
            while (d[0] | d[1]) d += 2;
        char date[16] = {0};
        for (int i = 0; i < 15 && (d[2 * i] | d[2 * i + 1]); ++i) date[i] = (char)d[2 * i];
        EXPECT(strlen(date) == 10 && date[4] == '/' && date[7] == '/' && !strncmp(date, "20", 2), "default date today: %s", date);
    }
    ym_export_free(f);
}

static void test_ops(void) {
    printf("ops table\n");
    const ChipEngineOps* ops = chip_engine_ops("ym2612-fm");
    EXPECT(ops && !strcmp(ops->system_id, "ym2612-fm"), "registry");
    if (!ops) return;
    EXPECT(ops->image_size == YM_IMAGE_SIZE && ops->image_storage >= ops->image_size, "sizes");
    EXPECT(ops->create(CHIP_MAX_SAMPLE_RATE + 1) == NULL, "rate above the maximum rejected");
    ChipImage* image = (ChipImage*)calloc(1, ops->image_storage);
    EXPECT(ops->load_image(image, g_song.bytes, ops->image_size - 1) == CHIP_ERR_IMAGE, "wrong size rejected");
    copy_image(&g_bad, &g_song);
    g_bad.bytes[0] = 'X';
    EXPECT(ops->load_image(image, g_bad.bytes, ops->image_size) == CHIP_ERR_IMAGE, "invalid rejected");
    EXPECT(ops->load_image(image, g_song.bytes, ops->image_size) == CHIP_OK, "song loads");
    ChipMachine* m = ops->create(RATE);
    int mode = -1, o, r, t;
    EXPECT(ops->start(m, image, 1, 4, SEEK_BUDGET, &mode) == CHIP_OK && mode == CHIP_SEEK_FROM_START, "start 1/4");
    ops->position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 4 && t == 0, "position 1/4/0");
    const uint32_t serial = ops->row_serial ? ops->row_serial(m) : 0;
    float out[CHIP_MAX_TICK_FRAMES * 2];
    int frames = 0, n = 0;
    for (int k = 0; k < 60; ++k) frames += (n = ops->tick(m, out));
    const int want = (int)(60 * RATE / NTSC_TICK_HZ + 0.5);
    EXPECT(abs(frames - want) <= 2 && n >= 800 && n <= 803, "60 ticks = %d frames at 59.92 Hz (%d)", want, frames);
    ops->position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 14 && t == 0, "10 rows later (%d/%d/%d)", o, r, t);
    EXPECT(ops->row_serial && ops->row_serial(m) == serial + 10, "row_serial counted the 10 row starts");
    ops->apply_image(m, image);
    EXPECT(ops->mute(m, 0, 1) == CHIP_OK && ops->mute(m, 0, 0) == CHIP_OK && ops->mute(m, 12, 1) == CHIP_OK &&
               ops->mute(m, 12, 0) == CHIP_OK && ops->mute(m, 13, 1) == CHIP_ERR_IMAGE,
           "mute channels 0-12");
    EXPECT(ops->audition(m, image, 0, A4, SINE_SLOT, 0) == CHIP_OK, "audition");
    ops->tick(m, out);
    EXPECT(ops->release(m) == 1, "release phase");
    int ticks = 0;
    while (ops->tick(m, out) > 0 && ticks < 1000) ++ticks;
    EXPECT(ticks < 1000, "released note ends (%d ticks)", ticks);
    ops->destroy(m);
    free(image);
}

static void test_costs(void) {
    printf("costs\n");
    YmMachine* m = ymm_create(RATE);
    int mode;
    const int far = ref_far_row(&REF_PCM, 0.9);
    const int fo = REF_PCM.pos[3 * far], fr = REF_PCM.pos[3 * far + 1];
    double t0 = now_us();
    ymm_start(m, &g_pcm, fo, fr, SEEK_BUDGET, &mode);
    const double seek = now_us() - t0;
    t0 = now_us();
    ymm_start(m, &g_song, 3, 20, SEEK_BUDGET, &mode);
    const double seek_song = now_us() - t0;
    t0 = now_us();
    ymm_audition(m, &g_song, 0, A4, SINE_SLOT, 0);
    const double audition = now_us() - t0;
    t0 = now_us();
    YmMachine* fresh = ymm_create(RATE);
    const double create = now_us() - t0;
    ymm_start(m, &g_pcm, 0, 0, SEEK_BUDGET, &mode);
    double same_bank = 0, worst_same = 0, other_bank = 0, worst_other = 0, tick = 0;
    for (int k = 0; k < 1200; ++k) {
        const YmImage* im = k < 600 ? ((k & 1) ? &g_pcm_moved : &g_pcm) : ((k & 1) ? &g_pcm_edited : &g_pcm);
        t0 = now_us();
        if (k < 600) ymm_apply_image(m, &g_pcm);
        else ymm_apply_image(m, im);
        const double us = now_us() - t0;
        if (k < 600) {
            same_bank += us;
            if (us > worst_same) worst_same = us;
        } else {
            other_bank += us;
            if (us > worst_other) worst_other = us;
        }
        t0 = now_us();
        ymm_tick(m, g_out);
        tick += now_us() - t0;
    }
    YmVgmInfo info;
    const char* problem;
    t0 = now_us();
    free(ym_render(&g_pcm, YM_FORMAT_VGM, EXPORT_TICKS, &info, &problem));
    const double vgm = now_us() - t0;
    t0 = now_us();
    free(ym_render(&g_pcm, YM_FORMAT_XGC, EXPORT_TICKS, &info, &problem));
    const double xgc = now_us() - t0;
    printf("  seek to PCM %d/%d (%d ticks, whole machine): %.0f us; song 3/20: %.0f us; audition setup %.0f us; creation %.0f us\n",
           fo, fr, far - 1, seek, seek_song, audition, create);
    printf("  apply_image: unchanged bank %.2f us mean (worst %.1f), changed bank %.2f us mean (worst %.1f); tick %.1f us mean\n",
           same_bank / 600, worst_same, other_bank / 600, worst_other, tick / 1200);
    printf("  PCM song exports: VGM %.1f ms, XGC %.1f ms\n", vgm / 1000, xgc / 1000);
    ymm_destroy(fresh);
    ymm_destroy(m);
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    EXPECT(LOAD(&g_song, song, SONG) == 0, "song loads: %s", g_song.info.problem);
    EXPECT(LOAD(&g_moved, moved, MOVED) == 0, "moved loads: %s", g_moved.info.problem);
    EXPECT(LOAD(&g_edited, edited, EDITED) == 0, "edited loads: %s", g_edited.info.problem);
    EXPECT(LOAD(&g_tempo, tempo, TEMPO) == 0, "tempo loads: %s", g_tempo.info.problem);
    EXPECT(LOAD(&g_short, short, SHORT) == 0, "short loads: %s", g_short.info.problem);
    EXPECT(LOAD(&g_pal, pal, PAL) == 0, "pal loads: %s", g_pal.info.problem);
    EXPECT(LOAD(&g_pcm, pcm, PCM) == 0, "pcm loads: %s", g_pcm.info.problem);
    EXPECT(LOAD(&g_pcm_moved, pcm_moved, PCM_MOVED) == 0, "pcm_moved loads: %s", g_pcm_moved.info.problem);
    EXPECT(LOAD(&g_pcm_edited, pcm_edited, PCM_EDITED) == 0, "pcm_edited loads: %s", g_pcm_edited.info.problem);
    if (failures) {
        printf("YM2612: fixture images do not load (%d failures)\n", failures);
        return 1;
    }
    write_file(dir, "ym_image.bin", g_song.bytes, sizeof g_song.bytes);
    write_file(dir, "ym_pcm_image.bin", g_pcm.bytes, sizeof g_pcm.bytes);
    test_tables();
    test_images();
    printf("player vs reference\n");
    test_player(&REF_SONG, &g_song, "NTSC");
    test_player(&REF_PAL, &g_pal, "PAL");
    test_player(&REF_EDITED, &g_edited, "note edit");
    test_player(&REF_TEMPO, &g_tempo, "tempo edit");
    test_player(&REF_PCM, &g_pcm, "PCM song");
    test_machine(dir, &REF_SONG, &g_song, "song", "ym_trace.txt", 1);
    test_machine(dir, &REF_PCM, &g_pcm, "PCM song", "ym_pcm_trace.txt", 0);
    test_streams(&g_song, "song", 2, 1);
    test_streams(&g_pal, "PAL song", 1, 1);
    test_streams(&g_pcm, "PCM song", 2, 0);
    test_frame_stress();
    test_audio(dir, &g_song, "ym_song.wav", 1);
    test_audio(dir, &g_pal, "ym_song_pal.wav", 1);
    test_audio(dir, &g_pcm, "ym_pcm_song.wav", 0);
    test_outliers(&g_song, "song");
    test_outliers(&g_pcm, "PCM song");
    test_loop();
    test_seek();
    test_determinism();
    test_live_edits();
    test_fm3_order();
    test_fm3_player();
    test_pcm_status();
    test_pcm_rows();
    test_pcm_pitch(dir);
    test_pcm_dac();
    test_pcm_fm6();
    test_pcm_mute();
    test_pcm_edits();
    test_audition(dir);
    test_mute();
    test_muted_feedback();
    test_pal();
    test_allocations();
    test_export_image(dir, &g_song, "song", "ym");
    test_export_image(dir, &g_pal, "PAL song", "ym_pal");
    test_export_image(dir, &g_pcm, "PCM song", "ym_pcm");
    test_export_entry();
    test_ops();
    test_costs();
    printf("YM2612: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
