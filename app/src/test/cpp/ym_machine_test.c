/* Host regression test for the YM2612 (Mega Drive) system: Thor Tracker's own player (ym/ym_player.c) streamed as
 * XGC frames (ym/ym_xgm.c) through SGDK's XGM Z80 driver on z80.h with ymfm's YM2612 and the PSG (ym/ym_psg.c),
 * playing the hand-built test song tools/audio/ym/ym_song.py (ym_test_song.h, from tools/audio/prepare_ym.py).
 *
 * Checks: the pitch tables (A-4 = 440 Hz, every note within 2 cents of 12-TET); image validation (single-byte
 * corruptions each rejected with its reason); register writes identical to the independent Python reference player
 * (tools/audio/ym/ym_ref.py) in every tick, NTSC and PAL, with all 8 algorithms, LFO, SSG-EG, FM 6, retriggers, drum
 * sweeps, PSG envelopes, fixed-rate and pitched noise and every effect; the XGM and XGC exports byte-identical to
 * SGDK xgmtool's conversion of the reference VGM; the stream the engine feeds the driver identical to xgmtool's XGC;
 * the driver's chip writes identical to the XGC frames it executes (across a ring wrap); positions; deterministic,
 * non-silent output; seeks landing on exact rows with the reference's writes afterwards, and the order fallback; live
 * re-layout before every tick, a note edit, tempo edits ahead of and behind the playhead and a song cut under the
 * playhead; audition (A-4 measured on FM and PSG, fine pitch, the kick sweep, release until silent, argument limits);
 * mute; VGM bytes identical to the reference's; the hosts' export entry point; the "ym2612-fm" ops table; costs.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir]
 * Writes <dir>/ym_song.wav (48 kHz stereo, 30 s), ym_song_pal.wav, ym_tone_fm.wav and ym_tone_psg.wav (A-4
 * auditions), ym_trace.txt (per-tick player writes), ym_song.vgm, ym_song.xgm, ym_song.bin (the XGC) and
 * ym_image.bin (the image). */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "chip_engine.h"
#include "generated/ym_tables.h"
#include "ym_export.h"
#include "ym_machine.h"
#include "ym_test_song.h"
#include "ym_vgm.h"
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
#define NTSC_TICK_HZ (53693175.0 / (3420.0 * 262.0))
#define PAL_TICK_HZ (53203424.0 / (3420.0 * 313.0))

static float g_out[YMM_MAX_TICK_FRAMES * 2];
static YmImage g_song, g_moved, g_edited, g_tempo, g_short, g_pal, g_bad;

typedef struct Ref {
    const int* offsets;
    const unsigned char* kinds;
    const unsigned char* regs;
    const unsigned char* vals;
    const short* pos;
    const unsigned char* offs;
} Ref;

#define REF(name) {ym_ref_##name##_offsets, ym_ref_##name##_kinds, ym_ref_##name##_regs, ym_ref_##name##_vals, \
                   ym_ref_##name##_pos, ym_ref_##name##_offs}
static const Ref REF_SONG = REF(song);
static const Ref REF_EDITED = REF(edited);
static const Ref REF_TEMPO = REF(tempo);
static const Ref REF_PAL = REF(pal);

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

/* Frequency from rising zero crossings of the left channel of interleaved stereo (mean removed). */
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

static int load(YmImage* image, const uint8_t* bytes, size_t used) {
    memset(image->bytes, 0, sizeof image->bytes);
    memcpy(image->bytes, bytes, used);
    return ym_load_image(image);
}

static int ref_count(const Ref* r, int k) { return r->offsets[k + 1] - r->offsets[k]; }

/* Whether a player batch equals reference batch k (0 = reset, k = tick k - 1). */
static int same(const YmPlayer* p, const Ref* r, int k) {
    if (p->writes != ref_count(r, k) || p->offs != r->offs[k]) return 0;
    for (int i = 0; i < p->writes; ++i) {
        const int j = r->offsets[k] + i;
        if (p->write[i].kind != r->kinds[j] || p->write[i].reg != r->regs[j] || p->write[i].val != r->vals[j]) return 0;
    }
    return 1;
}

/* Reference tick index (batch k) whose position is (order, row, 0), first occurrence, or -1. */
static int ref_row_start(const Ref* r, int order, int row) {
    for (int k = 1; k <= YM_REF_TICKS; ++k)
        if (r->pos[3 * k] == order && r->pos[3 * k + 1] == row && r->pos[3 * k + 2] == 0) return k;
    return -1;
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

/* ---- tests ---------------------------------------------------------------------------------- */

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

static void corrupt(int offset, int value, const char* want) {
    memcpy(&g_bad, &g_song, sizeof g_bad);
    g_bad.bytes[offset] = (uint8_t)value;
    const int rc = ym_load_image(&g_bad);
    EXPECT(rc != 0 && g_bad.info.problem && strstr(g_bad.info.problem, want), "byte %d = %d rejected for \"%s\" (got %s)",
           offset, value, want, rc ? g_bad.info.problem : "accepted");
}

#define INST(slot) (YM_OFF_INSTRUMENTS + YM_INSTRUMENT_SIZE * ((slot) - 1))

/* Image offset of envelope k (0 volume, 1 arpeggio, 2 pitch) of a PSG slot in the test song. */
static int env_at(int slot, int k) { return YM_OFF_DATA + ym_u16(g_song.bytes + INST(slot) + YM_I_ENV_VOLUME + 2 * k); }

static void test_images(void) {
    printf("images\n");
    EXPECT(g_song.info.loaded == YM_LOADED && g_song.info.orders == 4 && g_song.info.loop == 1, "song info");
    EXPECT(g_song.info.rows[0] == 16 && g_song.info.rows[1] == 32 && g_song.info.rows[2] == 16 && g_song.info.rows[3] == 24,
           "rows per order");
    EXPECT(g_song.info.region == YM_NTSC && g_pal.info.region == YM_PAL && g_song.info.lfo == 0x0B, "region, LFO");
    corrupt(0, 'X', "magic");
    corrupt(YM_H_LAYOUT, 2, "layout");
    corrupt(YM_H_REGION, 2, "region");
    corrupt(YM_H_SPEED, 0, "speed");
    corrupt(YM_H_LFO, 0x07, "LFO");
    corrupt(YM_H_LFO, 0x10, "LFO");
    corrupt(YM_H_ORDERS, 0, "order count");
    corrupt(YM_H_LOOP, 4, "loop");
    corrupt(YM_H_INSTRUMENTS, 65, "instrument count");
    corrupt(YM_H_INSTRUMENTS, 18, "past the instrument count");
    corrupt(13, 1, "reserved");
    corrupt(YM_H_DATA_SIZE + 1, 0xFF, "data size");
    corrupt(20, 1, "reserved");
    corrupt(YM_OFF_ROWS + 9, 4, "past the order count");
    corrupt(YM_OFF_ORDERS + 1, 0xF0, "pattern offset");
    corrupt(INST(3) + YM_I_FLAGS, 0x82, "flags");
    corrupt(INST(3) + YM_I_FLAGS, 0x01, "flags");
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
    YmImage* fresh = &g_bad;
    memcpy(fresh, &g_song, sizeof *fresh);
    EXPECT(ym_load_image(fresh) == 0 && !memcmp(&fresh->info, &g_song.info, sizeof fresh->info), "loading is a pure function");
}

/* The player alone against the reference (reset batch included), NTSC and PAL. */
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
    EXPECT(bad == 0, "%s: writes and positions = reference player in all %d ticks (%d differ, first at tick %d)", name,
           YM_REF_TICKS, bad, first - 1);
    if (first > 0) {
        ymp_reset(&p, image);
        for (int k = 1; k <= first; ++k) ymp_tick(&p, image);
        printf("  tick %d: player", first - 1);
        for (int i = 0; i < p.writes && i < 16; ++i) printf(" %d:%02X=%02X", p.write[i].kind, p.write[i].reg, p.write[i].val);
        printf("\n  reference");
        for (int i = 0; i < ref_count(ref, first) && i < 16; ++i) {
            const int j = ref->offsets[first] + i;
            printf(" %d:%02X=%02X", ref->kinds[j], ref->regs[j], ref->vals[j]);
        }
        printf("\n");
    }
}

/* Decodes XGC frames into the chip writes the driver performs (state, frame-skip and loop commands write none). */
static int xgc_writes(const uint8_t* x, int size, YmmLogEntry* out, int max) {
    int n = 0, at = 0;
    while (at < size) {
        const int frame_end = at + x[at];
        ++at;
        while (at < frame_end) {
            const int c = x[at++] >> 1, hi = c & 0xF0, k = (c & 15) + 1;
            if (hi == 0x10) { /* PSG tone ($10-$17: 1..8) or envelope ($18-$1B: 1..4) */
                const int count = (c & 8) ? (c & 3) + 1 : (c & 7) + 1;
                for (int i = 0; i < count && n < max; ++i) out[n++] = (YmmLogEntry){0, YM_W_PSG, 0, x[at++]};
            } else if (hi == 0x20 || hi == 0x30) {
                for (int i = 0; i < k && n < max; ++i, at += 2) out[n++] = (YmmLogEntry){0, (uint8_t)(hi == 0x30), x[at], x[at + 1]};
            } else if (hi == 0x40) {
                for (int i = 0; i < k && n < max; ++i) out[n++] = (YmmLogEntry){0, 0, 0x28, x[at++]};
            } else if (hi == 0x60) {
                at += 2 * k;
            } else if (c == 0x7E) {
                at += 3;
            }
        }
    }
    return n;
}

/* The driver's chip writes over a run, compared as one stream with the writes the XGC frames streamed so far decode
 * to: the driver must perform exactly those, in order (it may lag behind heavy frames: a prefix). */
typedef struct DriverCheck {
    YmmLogEntry* want;
    int nwant, ngot, bad, first_bad_tick, dac_bad, cap;
} DriverCheck;

static void driver_frames(DriverCheck* d, const uint8_t* frames, int size) {
    d->nwant += xgc_writes(frames, size, d->want + d->nwant, d->cap - d->nwant);
}

static void driver_log(DriverCheck* d, const YmMachine* m, int tick) {
    int nlog;
    const YmmLogEntry* log = ymm_log(m, &nlog);
    for (int i = 0; i < nlog; ++i) {
        if (log[i].kind == 0 && (log[i].reg == 0x2A || log[i].reg == 0x2B)) {
            if (log[i].reg == 0x2B && log[i].val != 0) ++d->dac_bad; /* no PCM: the DAC stays off */
            continue;
        }
        const YmmLogEntry* w = d->ngot < d->nwant ? &d->want[d->ngot] : NULL;
        if (!w || log[i].kind != w->kind || log[i].reg != w->reg || log[i].val != w->val) {
            if (!d->bad) d->first_bad_tick = tick;
            ++d->bad;
        }
        ++d->ngot;
    }
}

/* One trace line (tools/audio/ym/ym_ref.py's format_tick): the position of reference batch k, the player's writes. */
static void trace_line(FILE* f, const Ref* r, int k, const YmPlayer* p) {
    fprintf(f, "%d %d %d:", r->pos[3 * k], r->pos[3 * k + 1], r->pos[3 * k + 2]);
    for (int i = 0; i < p->writes; ++i) {
        if (p->write[i].kind == YM_W_PSG) fprintf(f, " PSG=%02X", p->write[i].val);
        else fprintf(f, " %d%02X=%02X", p->write[i].kind, p->write[i].reg, p->write[i].val);
        if (i + 1 == p->offs) fputs(" |", f);
    }
    fputc('\n', f);
}

/* The machine against the reference: the player's batches (one tick ahead) and positions, the heard positions (lagging
 * behind heavy frames), and the driver's chip writes = the stream. Also writes the trace evidence. */
static void test_machine(const char* dir) {
    printf("machine: reference player, driver, positions\n");
    YmMachine* m = ymm_create(RATE);
    ymm_set_log(m, 1);
    int mode = -1;
    EXPECT(ymm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode) == YMM_OK && mode == YMM_SEEK_FROM_START, "start");
    const YmPlayer* p = ymm_player(m);
    EXPECT(same(p, &REF_SONG, 1), "the player has run tick 0 (frames one tick ahead)");
    EXPECT(heard_is(m, &REF_SONG, 1), "heard position 0/0/0");
    char path[512];
    snprintf(path, sizeof path, "%s/ym_trace.txt", dir);
    FILE* trace = fopen(path, "w");
    DriverCheck d = {(YmmLogEntry*)malloc(sizeof(YmmLogEntry) * 400000), 0, 0, 0, -1, 0, 400000};
    int qsize;
    const uint8_t* q = ymm_last_frames(m, &qsize);
    driver_frames(&d, q, qsize);
    if (trace) trace_line(trace, &REF_SONG, 1, p);
    int mismatches = 0, first = -1, position_errors = 0, heard_errors = 0, max_writes = 0, writes = 0;
    int fm6_keyed = 0, max_lag = 0, lag_ticks = 0;
    int heard = 1; /* reference batch the heard position must be at (it may stall, never skip back) */
    double kick[12] = {0};
    const int kick_start = ref_row_start(&REF_SONG, 1, 0);
    for (int k = 1; k < YM_REF_TICKS; ++k) {
        if (!pos_is(m, &REF_SONG, k)) ++position_errors;
        /* heard: the reference position of the next unexecuted tick: k minus the lag */
        const int lag = ymm_lag(m);
        if (!heard_is(m, &REF_SONG, k - lag)) ++heard_errors;
        if (lag > max_lag) max_lag = lag;
        if (lag) ++lag_ticks;
        if (k - lag < heard) ++heard_errors;
        heard = k - lag;
        ymm_tick(m, g_out);
        driver_log(&d, m, k - 1);
        if (!same(p, &REF_SONG, k + 1)) {
            if (first < 0) first = k;
            ++mismatches;
        }
        q = ymm_last_frames(m, &qsize);
        driver_frames(&d, q, qsize);
        writes += p->writes;
        if (p->writes > max_writes) max_writes = p->writes;
        if (p->ym[1][0xB6] && (p->ym[1][0xA6] || p->ym[1][0xA2])) fm6_keyed = 1;
        if (trace) trace_line(trace, &REF_SONG, k + 1, p);
        if (k + 1 >= kick_start && k + 1 < kick_start + 12) kick[k + 1 - kick_start] = fm_hz(0, p->ym[0][0xA4], p->ym[0][0xA0]);
    }
    if (trace) fclose(trace);
    EXPECT(mismatches == 0, "player writes = reference in all %d ticks (%d differ, first at tick %d)", YM_REF_TICKS, mismatches,
           first);
    EXPECT(position_errors == 0, "player positions = reference in every tick (%d differ)", position_errors);
    EXPECT(heard_errors == 0, "heard positions = the reference's, lagging behind heavy frames (%d wrong)", heard_errors);
    EXPECT(d.bad == 0 && d.ngot > 1000, "the driver's chip writes = the streamed frames, in order (%d writes, %d differ from tick %d)",
           d.ngot, d.bad, d.first_bad_tick);
    EXPECT(d.nwant - d.ngot < 400, "the driver keeps up (%d writes queued at the end)", d.nwant - d.ngot);
    EXPECT(d.dac_bad == 0, "the driver keeps the DAC off ($2B = 0) without PCM (%d other writes)", d.dac_bad);
    EXPECT(max_lag <= 6, "lag behind heavy frames at most 6 ticks (%d)", max_lag);
    EXPECT(fm6_keyed, "FM 6 plays notes");
    EXPECT(kick[0] > 500 && kick[0] > kick[3] && kick[3] > kick[6] && fabs(kick[9] - 65.41) < 1.0,
           "kick sweeps C-5 -> C-2 (%.1f %.1f %.1f %.1f Hz)", kick[0], kick[3], kick[6], kick[9]);
    printf("  %d ticks, %d player writes (max %d per tick), %d driver writes; driver lag: max %d ticks, %d ticks lagging; kick %.0f -> %.0f Hz\n",
           YM_REF_TICKS, writes, max_writes, d.ngot, max_lag, lag_ticks, kick[0], kick[9]);
    free(d.want);
    ymm_destroy(m);
}

/* The XGC stream the engine feeds the driver = xgmtool's XGC of the reference VGM, every frame of the first pass (the
 * loop frame included); xgmtool's file then ends with frame skip + loop, where the engine plays on. */
static void test_stream_vs_xgmtool(void) {
    printf("stream = xgmtool XGC\n");
    const uint8_t* x = ym_xgmtool_song_xgc;
    const int music = 0x104, size = (int)(x[0x100] | x[0x101] << 8 | x[0x102] << 16);
    const uint8_t* tail = x + music + size - 7;
    EXPECT(tail[0] == 7 && tail[1] == 0xFA && tail[2] == 0xFC && tail[6] == 0xFE, "xgmtool's XGC ends with frame skip + loop");
    EXPECT(x[music] == 1 && x[music + 1] == 1 && x[music + 2] == 1, "xgmtool's 3 empty frames");
    const int want = size - 7 - 3;
    uint8_t* engine = (uint8_t*)malloc((size_t)size + 8192);
    YmMachine* m = ymm_create(RATE);
    int mode, qsize;
    ymm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    const uint8_t* q = ymm_last_frames(m, &qsize);
    memcpy(engine, q, (size_t)qsize);
    int n = qsize;
    for (int k = 1; k < YM_REF_SONG_VGM_TICKS; ++k) {
        ymm_tick(m, g_out);
        q = ymm_last_frames(m, &qsize);
        memcpy(engine + n, q, (size_t)qsize);
        n += qsize;
    }
    int diff = -1;
    for (int i = 0; i < n && i < want; ++i)
        if (engine[i] != x[music + 3 + i]) {
            diff = i;
            break;
        }
    EXPECT(n == want && diff < 0, "the engine's %u ticks of frames = xgmtool's XGC of the reference VGM (%d bytes vs %d, first difference at %d)",
           YM_REF_SONG_VGM_TICKS, n, want, diff);
    printf("  %d bytes of XGC frames identical (%u ticks, loop frame included)\n", n, YM_REF_SONG_VGM_TICKS);
    free(engine);
    ymm_destroy(m);
}

/* Long run across a ring wrap: the driver's writes keep matching the stream, it keeps playing. */
static void test_ring(void) {
    printf("ring wrap\n");
    YmMachine* m = ymm_create(RATE);
    ymm_set_log(m, 1);
    int mode, qsize;
    ymm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    DriverCheck d = {(YmmLogEntry*)malloc(sizeof(YmmLogEntry) * 2000000), 0, 0, 0, -1, 0, 2000000};
    const uint8_t* q = ymm_last_frames(m, &qsize);
    driver_frames(&d, q, qsize);
    int wraps = 0, ticks = 0, after = 0, stopped = 0;
    for (; ticks < 40000 && after < 600; ++ticks) {
        if (ymm_tick(m, g_out) <= 0) {
            stopped = 1;
            break;
        }
        driver_log(&d, m, ticks);
        const uint8_t* prev = q;
        q = ymm_last_frames(m, &qsize);
        driver_frames(&d, q, qsize);
        if (q < prev) ++wraps;
        if (wraps) ++after;
    }
    EXPECT(wraps >= 1 && !stopped && d.bad == 0, "%d ticks, %d ring wraps: the driver's writes = the stream throughout (%d differ)",
           ticks, wraps, d.bad);
    EXPECT(ymm_z80_ram(m)[YMM_STATUS] & 0x40, "still playing after the wrap");
    printf("  %d ticks, %d wraps, %d driver writes\n", ticks, wraps, d.ngot);
    free(d.want);
    ymm_destroy(m);
}

static int z80_same(const z80_t* a, const z80_t* b) {
    return a->step == b->step && a->addr == b->addr && a->dlatch == b->dlatch && a->opcode == b->opcode &&
           a->hlx_idx == b->hlx_idx && a->prefix_active == b->prefix_active && a->pins == b->pins &&
           a->int_bits == b->int_bits && a->pc == b->pc && a->af == b->af && a->bc == b->bc && a->de == b->de &&
           a->hl == b->hl && a->ix == b->ix && a->iy == b->iy && a->wz == b->wz && a->sp == b->sp && a->ir == b->ir &&
           a->af2 == b->af2 && a->bc2 == b->bc2 && a->de2 == b->de2 && a->hl2 == b->hl2 && a->im == b->im &&
           a->iff1 == b->iff1 && a->iff2 == b->iff2;
}

/* Lockstep: `fast` (pass replay) against `full` (every Z80 cycle stepped) for `ticks` ticks: identical samples, chip
 * writes (with their cycles), lag and positions every tick; identical Z80 state and RAM whenever `fast` is between
 * passes. Returns the ticks compared without a difference. */
static int lockstep(YmMachine* fast, YmMachine* full, int ticks, const char* what, int* states) {
    static float a[YMM_MAX_TICK_FRAMES * 2], b[YMM_MAX_TICK_FRAMES * 2];
    int same_ticks = 0, first_bad = -1;
    for (int k = 0; k < ticks; ++k) {
        const int na = ymm_tick(fast, a), nb = ymm_tick(full, b);
        int nla, nlb, oa, ra, ta, ob, rb, tb;
        const YmmLogEntry* la = ymm_log(fast, &nla);
        const YmmLogEntry* lb = ymm_log(full, &nlb);
        ymm_position(fast, &oa, &ra, &ta);
        ymm_position(full, &ob, &rb, &tb);
        int ok = na == nb && (na <= 0 || !memcmp(a, b, sizeof(float) * 2 * (size_t)na)) && nla == nlb &&
                 !memcmp(la, lb, sizeof(YmmLogEntry) * (size_t)nla) && ymm_lag(fast) == ymm_lag(full) && oa == ob &&
                 ra == rb && ta == tb;
        if (ok && !ymm_replaying(fast)) {
            ok = z80_same((const z80_t*)ymm_z80(fast), (const z80_t*)ymm_z80(full)) &&
                 !memcmp(ymm_z80_ram(fast), ymm_z80_ram(full), 0x2000);
            ++*states;
        }
        if (ok) ++same_ticks;
        else if (first_bad < 0) first_bad = k;
        if (na <= 0 || nb <= 0) break;
    }
    EXPECT(first_bad < 0, "%s: replayed = fully stepped in every tick (first difference at tick %d)", what, first_bad);
    return same_ticks;
}

/* Pass replay is exact: the song (start, ring wrap, loops), PAL, a seek, auditions with release, mutes. */
static void test_replay(void) {
    printf("pass replay = every cycle stepped\n");
    YmMachine* fast = ymm_create(RATE);
    YmMachine* full = ymm_create(RATE);
    ymm_set_replay(full, 0);
    ymm_set_log(fast, 1);
    ymm_set_log(full, 1);
    int mode, states = 0, ticks = 0;
    ymm_start(fast, &g_song, 0, 0, SEEK_BUDGET, &mode);
    ymm_start(full, &g_song, 0, 0, SEEK_BUDGET, &mode);
    ticks += lockstep(fast, full, 3400, "NTSC song across a ring wrap", &states);
    ymm_start(fast, &g_pal, 0, 0, SEEK_BUDGET, &mode);
    ymm_start(full, &g_pal, 0, 0, SEEK_BUDGET, &mode);
    ticks += lockstep(fast, full, 600, "PAL song", &states);
    ymm_start(fast, &g_song, 3, 20, SEEK_BUDGET, &mode);
    ymm_start(full, &g_song, 3, 20, SEEK_BUDGET, &mode);
    ymm_mute(fast, 2, 1);
    ymm_mute(full, 2, 1);
    ymm_mute(fast, 9, 1);
    ymm_mute(full, 9, 1);
    ticks += lockstep(fast, full, 400, "seek 3/20 with FM 3 and Noise muted", &states);
    static const int aud[][3] = {{0, A4, SINE_SLOT}, {6, A4, SQUARE_SLOT}, {9, 48, TOM_SLOT}};
    for (int i = 0; i < 3; ++i) {
        ymm_audition(fast, &g_song, aud[i][0], aud[i][1], aud[i][2], 0);
        ymm_audition(full, &g_song, aud[i][0], aud[i][1], aud[i][2], 0);
        ticks += lockstep(fast, full, 90, "audition", &states);
        EXPECT(ymm_release(fast) == ymm_release(full), "release alike");
        ticks += lockstep(fast, full, 800, "audition release", &states);
    }
    YmmReplayStats s;
    ymm_replay_stats(fast, &s);
    const double share = (double)s.cycles / ((double)ticks * 59736.0);
    EXPECT(states > ticks / 2, "Z80 state and RAM compared at %d of %d tick ends", states, ticks);
    EXPECT(share > 0.8, "most Z80 cycles replayed (%.1f %%)", 100 * share);
    EXPECT(s.entries < 64 && s.arena_bytes < 640u * 1024u, "memo bounded: %d passes, %u bytes", s.entries, s.arena_bytes);
    printf("  %d ticks identical (Z80 state and RAM at %d); %d passes kept (%u KB), %llu replays, %.1f %% of Z80 cycles\n",
           ticks, states, s.entries, s.arena_bytes / 1024, (unsigned long long)s.hits, 100 * share);
    ymm_destroy(fast);
    ymm_destroy(full);
}

static void test_audio(const char* dir, const YmImage* image, const char* wav, double tick_hz) {
    printf("audio (%s)\n", wav);
    const int ticks = (int)(SECONDS * tick_hz + 0.5);
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
            const int want = (int)(ticks * RATE / tick_hz + 0.5);
            EXPECT(abs(frames - want) <= 2, "%d ticks at %.3f Hz = %d frames (got %d)", ticks, tick_hz, want, frames);
            EXPECT(r > 0.02, "non-silent (rms %.4f)", r);
            EXPECT(pk < 0.999, "no clipping (peak %.4f)", pk);
            int quiet = 0;
            for (int s = 0; s < SECONDS; ++s)
                if (rms(buf + 2 * s * RATE, RATE) < 0.005) ++quiet;
            EXPECT(quiet == 0, "every second non-silent (%d quiet)", quiet);
            write_wav(dir, wav, buf, frames);
            printf("  30 s: rms %.4f, peak %.4f, %.1f us per tick (%.0fx realtime)\n", r, pk, us, 1e6 / tick_hz / us);
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
    EXPECT((uint32_t)len[0] * 735 == YM_REF_SONG_VGM_LOOP_SAMPLES, "= the VGM loop (%u samples)", YM_REF_SONG_VGM_LOOP_SAMPLES);
    printf("  %d ticks per pass\n", len[0]);
    ymm_destroy(m);
}

/* Tick cost must be flat: after 1 s of warm-up, 30 s with no tick above 3x the mean (thread CPU time). */
static void test_outliers(void) {
    printf("tick cost outliers\n");
    const int ticks = SECONDS * 60;
    double* cpu = (double*)malloc(sizeof(double) * (size_t)ticks);
    YmMachine* m = ymm_create(RATE);
    int mode;
    ymm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
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
    EXPECT(worst < 3 * mean, "no tick above 3x the mean: worst %.1f us (tick %d) vs mean %.1f us", worst, worst_at, mean);
    printf("  thread CPU per tick: mean %.1f us, worst %.1f us (%.2fx, tick %d)\n", mean, worst, worst / mean, worst_at);
    ymm_destroy(m);
    free(cpu);
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
        const double t0 = now_us();
        EXPECT(ymm_start(m, &g_song, o, r, SEEK_BUDGET, &mode) == YMM_OK && mode == YMM_SEEK_FROM_START, "seek %d/%d", o, r);
        const double us = now_us() - t0;
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
        printf("  %d/%d: %d ticks fast-forwarded in %.0f us\n", o, r, k - 1, us);
    }
    /* Sound after a seek against straight play from the same tick, 1 s: inside the pre-roll window bit-identical;
     * beyond it the last 60 ticks streamed through the driver. Each channel's 20 ms loudness envelope over 0.5 s (those
     * carrying 2 % of the energy or more) stays within 0.1 of straight play (FM release tails and SSG-EG phases of
     * notes keyed before the pre-roll are longer than the OPL3's 0.08); the mix within 0.15 (it also depends on the
     * channels' relative phases: PSG counters and FM phases restart at the restore, so near-unison channels beat
     * differently). */
    static const int compare[][2] = {{0, 5}, {1, 0}, {1, 17}, {2, 12}, {3, 20}};
    for (size_t i = 0; i < sizeof compare / sizeof compare[0]; ++i) {
        const int o = compare[i][0], r = compare[i][1], k = ref_row_start(&REF_SONG, o, r);
        YmMachine* a = ymm_create(RATE);
        double worst = 0, env = 0, mix_level = 0;
        int worst_ch = -1;
        for (int solo = -1; solo < YM_CHANNELS; ++solo) {
            int mode;
            ymm_start(a, &g_song, 0, 0, SEEK_BUDGET, &mode);
            for (int j = 1; j < k; ++j) ymm_tick(a, g_out);
            ymm_start(m, &g_song, o, r, SEEK_BUDGET, &mode);
            for (int c = 0; c < YM_CHANNELS; ++c) {
                ymm_mute(a, c, solo >= 0 && c != solo);
                ymm_mute(m, c, solo >= 0 && c != solo);
            }
            float *x, *y;
            const int fx = render(a, 60, &x), fy = render(m, 60, &y);
            const int win = RATE / 50, wins = RATE / 2 / win;
            double dev = 0, level = 0;
            for (int w = 0; w < wins; ++w) {
                const double rx = rms(x + 2 * w * win, win), ry = rms(y + 2 * w * win, win);
                dev += (ry - rx) * (ry - rx);
                level += rx * rx;
            }
            const double e = level > 0 ? sqrt(dev / level) : 0;
            if (solo < 0) {
                env = e;
                mix_level = level;
                const int identical = fx == fy && !memcmp(x, y, sizeof(float) * 2 * (size_t)fx);
                if (k - 1 <= YMM_PREROLL_TICKS)
                    EXPECT(identical, "seek %d/%d (%d ticks, inside the pre-roll): sound = straight play", o, r, k - 1);
                else
                    EXPECT(e < 0.15, "seek %d/%d (%d ticks): mixed loudness envelope within %.3f of straight play", o, r, k - 1, e);
                const double rx = rms(x + 2 * RATE / 2, fx - RATE / 2), ry = rms(y + 2 * RATE / 2, fy - RATE / 2);
                EXPECT(ry > 0.8 * rx && ry < 1.25 * rx, "seek %d/%d: loudness 0.5-1 s after %.4f vs straight %.4f", o, r, ry, rx);
            } else if (level > 0.02 * mix_level && e > worst) {
                worst = e;
                worst_ch = solo;
            }
            free(x);
            free(y);
        }
        EXPECT(worst < 0.1, "seek %d/%d: every channel's loudness envelope within %.3f of straight play (channel %d)", o, r,
               worst, worst_ch);
        printf("  %d/%d vs straight play: envelope deviation %.3f mixed, %.3f worst channel (%d)\n", o, r, env, worst, worst_ch);
        ymm_destroy(a);
    }
    /* Order 3 rows 0-1 are only reached by the order fallback (order 2 breaks to row 2). */
    int mode = -1;
    EXPECT(ymm_start(m, &g_song, 3, 0, SEEK_BUDGET, &mode) == YMM_OK && mode == YMM_SEEK_FROM_ORDER, "3/0: order fallback");
    int o, r, t;
    ymm_position(m, &o, &r, &t);
    EXPECT(o == 3 && r == 0 && t == 0, "fallback lands on 3/0 (%d/%d/%d)", o, r, t);
    EXPECT(ymm_start(m, &g_song, 1, 17, 10, &mode) == YMM_OK && mode == YMM_SEEK_FROM_ORDER, "small budget: fallback");
    ymm_position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 17 && t == 0, "budget fallback lands on 1/17");
    EXPECT(ymm_start(m, &g_song, 4, 0, SEEK_BUDGET, &mode) == YMM_ERR_IMAGE, "order past the song rejected");
    EXPECT(ymm_start(m, &g_song, 0, 16, SEEK_BUDGET, &mode) == YMM_ERR_IMAGE, "row past the order rejected");
    ymm_destroy(m);
}

/* Plays `image` from the start, applying alt[t % 2] before machine tick t (from tick `from`), and compares the player's
 * batches (one tick ahead) with `ref`. */
static int play_applying(const YmImage* image, const YmImage* alt0, const YmImage* alt1, int from, int every,
                         const Ref* ref, int* first) {
    YmMachine* m = ymm_create(RATE);
    int mode, bad = 0;
    ymm_start(m, image, 0, 0, SEEK_BUDGET, &mode);
    *first = -1;
    for (int k = 1; k < YM_REF_TICKS; ++k) {
        if (k - 1 >= from && (every || k - 1 == from)) ymm_apply_image(m, (k % 2) ? alt0 : alt1);
        if (!pos_is(m, ref, k)) ++bad;
        ymm_tick(m, g_out);
        if (!same(ymm_player(m), ref, k + 1)) {
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
    int bad = play_applying(&g_song, &g_song, &g_song, 0, 1, &REF_SONG, &first);
    EXPECT(bad == 0, "same image applied before every tick: writes = reference (%d differ, first %d)", bad, first);
    bad = play_applying(&g_song, &g_moved, &g_song, 0, 1, &REF_SONG, &first);
    EXPECT(bad == 0, "re-laid-out image and the original alternately before every tick: writes = reference (%d differ, first %d)",
           bad, first);
    {
        YmMachine* m = ymm_create(RATE);
        int mode;
        ymm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
        double worst = 0, total = 0;
        for (int k = 0; k < 1500; ++k) {
            const double t0 = now_us();
            ymm_apply_image(m, (k & 1) ? &g_moved : &g_song);
            const double us = now_us() - t0;
            total += us;
            if (us > worst) worst = us;
            ymm_tick(m, g_out);
        }
        printf("  apply_image: %.2f us mean, worst %.1f us\n", total / 1500, worst);
        ymm_destroy(m);
    }
    /* A note edit in order 1 row 16, applied at ticks before the player reaches that row: straight play of the
     * edited song. (The player runs one tick ahead, so the last chance is two ticks before the row is heard.) */
    const int k_edit = ref_row_start(&REF_SONG, 1, 16);
    const int at[] = {0, 40, k_edit - 30, k_edit - 3, k_edit - 2};
    for (size_t i = 0; i < sizeof at / sizeof at[0]; ++i) {
        bad = play_applying(&g_song, &g_edited, &g_edited, at[i], 0, &REF_EDITED, &first);
        EXPECT(bad == 0, "note edit applied before tick %d: = edited straight play (%d differ, first %d)", at[i], bad, first);
    }
    /* Tempo: F09 on order 1 row 4. Applied ahead of it: straight play of the tempo song. */
    const int k_tempo = ref_row_start(&REF_SONG, 1, 4);
    bad = play_applying(&g_song, &g_tempo, &g_tempo, k_tempo - 4, 0, &REF_TEMPO, &first);
    EXPECT(bad == 0, "tempo edit ahead of the playhead: = tempo song straight play (%d differ, first %d)", bad, first);
    {
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
    EXPECT(ymm_audition(m, &g_song, 10, 48, 2, 0) == YMM_ERR_IMAGE, "channel 10 rejected");
    EXPECT(ymm_audition(m, &g_song, 0, 48, 1, 200) == YMM_ERR_IMAGE, "fine 200 rejected");
    ymm_destroy(m);
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
    ymm_start(m, &g_song, 1, 17, SEEK_BUDGET, &mode); /* after a seek's register restore */
    for (int c = 0; c < YM_CHANNELS; ++c) ymm_mute(m, c, 1);
    frames = render(m, 60, &buf);
    EXPECT(rms(buf + 2 * (RATE / 10), frames - RATE / 10) < 0.0005, "muted right after a seek: rms %.6f after 100 ms",
           rms(buf + 2 * (RATE / 10), frames - RATE / 10));
    free(buf);
    EXPECT(ymm_mute(m, 10, 1) == YMM_ERR_IMAGE, "channel 10 rejected");
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

static void test_exports(const char* dir) {
    printf("exports\n");
    size_t cap = 1 << 20;
    uint8_t* buf = (uint8_t*)malloc(cap);
    static YmPlayer scratch;
    static YmXgmEncoder enc;
    YmVgmInfo info;
    double t0 = now_us();
    size_t size = ym_render(&g_song, YM_FORMAT_VGM, 36000, &scratch, NULL, buf, cap, &info);
    const double us = now_us() - t0;
    EXPECT(size == YM_REF_SONG_VGM_SIZE && fnv(0xCBF29CE484222325ull, buf, size) == YM_REF_SONG_VGM_FNV,
           "VGM bytes = the reference player's (%zu vs %u bytes)", size, YM_REF_SONG_VGM_SIZE);
    EXPECT(info.ticks == YM_REF_SONG_VGM_TICKS && info.samples == YM_REF_SONG_VGM_SAMPLES &&
               info.loop_offset == YM_REF_SONG_VGM_LOOP_OFFSET && info.loop_samples == YM_REF_SONG_VGM_LOOP_SAMPLES,
           "VGM length and loop (%u ticks, loop at %u, %u samples)", info.ticks, info.loop_offset, info.loop_samples);
    EXPECT(!memcmp(buf, "Vgm ", 4) && buf[8] == 0x71 && buf[9] == 0x01 && buf[0x28] == 9 && buf[0x2A] == 16 &&
               (buf[0x2C] | buf[0x2D] << 8 | buf[0x2E] << 16) == 7670453 && (buf[0x0C] | buf[0x0D] << 8 | buf[0x0E] << 16) == 3579545,
           "VGM header: YM2612 7670453 Hz, SN76489 3579545 Hz, feedback 9, width 16");
    EXPECT(ym_render(&g_song, YM_FORMAT_VGM, 36000, &scratch, NULL, buf, 1000, &info) == 0, "too small a buffer reports 0");
    write_file(dir, "ym_song.vgm", buf, size);
    write_file(dir, "ym_image.bin", g_song.bytes, sizeof g_song.bytes);
    printf("  VGM %zu bytes, %u ticks, loop %u samples (%.2f s), rendered in %.0f us\n", size, info.ticks,
           info.loop_samples, info.loop_samples / 44100.0, us);
    size = ym_render(&g_pal, YM_FORMAT_VGM, 36000, &scratch, NULL, buf, cap, &info);
    EXPECT(size == YM_REF_PAL_VGM_SIZE && fnv(0xCBF29CE484222325ull, buf, size) == YM_REF_PAL_VGM_FNV &&
               info.loop_samples == YM_REF_PAL_VGM_LOOP_SAMPLES && buf[0x24] == 50,
           "PAL VGM bytes = the reference player's (%zu bytes)", size);
    /* XGM and XGC = xgmtool's conversion of the reference VGM */
    t0 = now_us();
    size = ym_render(&g_song, YM_FORMAT_XGM, 36000, &scratch, &enc, buf, cap, &info);
    const double xus = now_us() - t0;
    EXPECT(size == YM_XGMTOOL_SONG_XGM_SIZE && fnv(0xCBF29CE484222325ull, buf, size) == YM_XGMTOOL_SONG_XGM_FNV,
           "XGM bytes = xgmtool's (%zu vs %u bytes)", size, YM_XGMTOOL_SONG_XGM_SIZE);
    write_file(dir, "ym_song.xgm", buf, size);
    size = ym_render(&g_song, YM_FORMAT_XGC, 36000, &scratch, &enc, buf, cap, &info);
    int diff = -1;
    for (size_t i = 0; i < size && i < YM_XGMTOOL_SONG_XGC_SIZE; ++i)
        if (buf[i] != ym_xgmtool_song_xgc[i]) {
            diff = (int)i;
            break;
        }
    EXPECT(size == YM_XGMTOOL_SONG_XGC_SIZE && diff < 0, "XGC bytes = xgmtool's (%zu vs %u bytes, first difference at %d)",
           size, YM_XGMTOOL_SONG_XGC_SIZE, diff);
    write_file(dir, "ym_song.bin", buf, size);
    size = ym_render(&g_pal, YM_FORMAT_XGM, 36000, &scratch, &enc, buf, cap, &info);
    EXPECT(size == YM_XGMTOOL_PAL_XGM_SIZE && fnv(0xCBF29CE484222325ull, buf, size) == YM_XGMTOOL_PAL_XGM_FNV,
           "PAL XGM bytes = xgmtool's (%zu bytes)", size);
    size = ym_render(&g_pal, YM_FORMAT_XGC, 36000, &scratch, &enc, buf, cap, &info);
    EXPECT(size == YM_XGMTOOL_PAL_XGC_SIZE && fnv(0xCBF29CE484222325ull, buf, size) == YM_XGMTOOL_PAL_XGC_FNV,
           "PAL XGC bytes = xgmtool's (%zu bytes)", size);
    printf("  XGM %u bytes, XGC %u bytes = xgmtool's; XGM rendered in %.0f us\n", YM_XGMTOOL_SONG_XGM_SIZE,
           YM_XGMTOOL_SONG_XGC_SIZE, xus);
    free(buf);
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

/* The hosts' entry point (ym_export.h): reference VGM / xgmtool XGM plus a GD3 tag, xgmtool XGC. */
static void test_export_entry(void) {
    printf("export entry point\n");
    size_t size = 0;
    const char* problem = NULL;
    const char* title = "Thor \xC3\x96pening \xF0\x9F\x8E\xB9"; /* "Thor Öpening 🎹": 2-byte and 4-byte UTF-8 */
    uint8_t* vgm = ym_export(g_song.bytes, sizeof g_song.bytes, YM_EXPORT_VGM, 36000, title, "Esko", "2026/11/08", &size,
                             &problem);
    EXPECT(vgm && !problem, "VGM renders (%s)", problem ? problem : "ok");
    if (vgm) {
        const uint32_t gd3_at = 0x14 + (vgm[0x14] | vgm[0x15] << 8 | vgm[0x16] << 16 | (uint32_t)vgm[0x17] << 24);
        const uint32_t eof = 4 + (vgm[4] | vgm[5] << 8 | vgm[6] << 16 | (uint32_t)vgm[7] << 24);
        EXPECT(gd3_at == YM_REF_SONG_VGM_SIZE && eof == size, "GD3 after the reference data, EOF offset = size");
        uint8_t* body = (uint8_t*)malloc(YM_REF_SONG_VGM_SIZE);
        memcpy(body, vgm, YM_REF_SONG_VGM_SIZE);
        const uint32_t ref_eof = YM_REF_SONG_VGM_SIZE - 4;
        memcpy(body + 4, &ref_eof, 4);
        memset(body + 0x14, 0, 4);
        EXPECT(fnv(0xCBF29CE484222325ull, body, YM_REF_SONG_VGM_SIZE) == YM_REF_SONG_VGM_FNV,
               "the data = the reference player's VGM (header 0x04/0x14 aside)");
        free(body);
        char fields[11][64];
        const uint8_t* end = gd3_fields(vgm + gd3_at, fields);
        EXPECT(!memcmp(vgm + gd3_at, "Gd3 ", 4) && end == vgm + size, "GD3 1.00, 11 strings fill the tag");
        EXPECT(!strcmp(fields[0], title) && !strcmp(fields[4], "Mega Drive (Thor Tracker)") && !strcmp(fields[6], "Esko") &&
                   !strcmp(fields[8], "2026/11/08") && !strcmp(fields[9], "Thor Tracker") && !fields[2][0] && !fields[10][0],
               "GD3 fields: '%s', '%s', '%s', '%s', '%s'", fields[0], fields[4], fields[6], fields[8], fields[9]);
        ym_export_free(vgm);
    }
    uint8_t* xgm = ym_export(g_song.bytes, sizeof g_song.bytes, YM_EXPORT_XGM, 36000, title, "Esko", "2026/11/08", &size,
                             &problem);
    EXPECT(xgm && size > YM_XGMTOOL_SONG_XGM_SIZE, "XGM renders");
    if (xgm) {
        EXPECT(xgm[0x103] == 0x02, "XGM flags: NTSC, GD3 tag present");
        xgm[0x103] = 0;
        EXPECT(fnv(0xCBF29CE484222325ull, xgm, YM_XGMTOOL_SONG_XGM_SIZE) == YM_XGMTOOL_SONG_XGM_FNV,
               "the XGM data = xgmtool's (the GD3 flag aside)");
        char fields[11][64];
        const uint8_t* end = gd3_fields(xgm + YM_XGMTOOL_SONG_XGM_SIZE, fields);
        EXPECT(!memcmp(xgm + YM_XGMTOOL_SONG_XGM_SIZE, "Gd3 ", 4) && end == xgm + size && !strcmp(fields[0], title),
               "XGM GD3 tag after the music data");
        ym_export_free(xgm);
    }
    uint8_t* xgc = ym_export(g_song.bytes, sizeof g_song.bytes, YM_EXPORT_XGC, 36000, title, NULL, NULL, &size, &problem);
    EXPECT(xgc && size == YM_XGMTOOL_SONG_XGC_SIZE && !memcmp(xgc, ym_xgmtool_song_xgc, size), "XGC = xgmtool's, no tag");
    ym_export_free(xgc);
    memcpy(g_bad.bytes, g_song.bytes, sizeof g_bad.bytes);
    g_bad.bytes[YM_H_REGION] = 2;
    uint8_t* f = ym_export(g_bad.bytes, sizeof g_bad.bytes, YM_EXPORT_VGM, 36000, NULL, NULL, NULL, &size, &problem);
    EXPECT(!f && size == 0 && problem && strstr(problem, "region"), "bad image: NULL, '%s'", problem ? problem : "");
    f = ym_export(g_song.bytes, 100, YM_EXPORT_VGM, 36000, NULL, NULL, NULL, &size, &problem);
    EXPECT(!f && problem && !strcmp(problem, "image size"), "short image: '%s'", problem ? problem : "");
    f = ym_export(g_song.bytes, sizeof g_song.bytes, 3, 36000, NULL, NULL, NULL, &size, &problem);
    EXPECT(!f && problem && !strcmp(problem, "format"), "unknown format: '%s'", problem ? problem : "");
    f = ym_export(g_song.bytes, sizeof g_song.bytes, YM_EXPORT_VGM, 100, NULL, NULL, NULL, &size, &problem);
    EXPECT(f && (f[0x1C] | f[0x20]) == 0 && size > 0x100, "tick limit before the loop closes: no loop");
    if (f) {
        const uint8_t* d = f + 0x14 + (f[0x14] | f[0x15] << 8) + 12;
        for (int i = 0; i < 8; ++i, d += 2)
            while (d[0] | d[1]) d += 2;
        char date[16] = {0};
        for (int i = 0; i < 15 && (d[2 * i] | d[2 * i + 1]); ++i) date[i] = (char)d[2 * i];
        EXPECT(strlen(date) == 10 && date[4] == '/' && date[7] == '/' && !strncmp(date, "20", 2), "default date today: %s", date);
    }
    ym_export_free(f);
    f = ym_export(g_song.bytes, sizeof g_song.bytes, YM_EXPORT_XGC, 100, NULL, NULL, NULL, &size, &problem);
    EXPECT(f && size > 0x104 + 3 && f[size - 2] == 2 && f[size - 1] == 0xFE, "XGC tick limit: ends with an end frame");
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
    const int want = (int)(60 * RATE / NTSC_TICK_HZ + 0.5);
    EXPECT(abs(frames - want) <= 2 && n >= 800 && n <= 803, "60 ticks = %d frames at 59.92 Hz (%d)", want, frames);
    ops->position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 14 && t == 0, "10 rows later (%d/%d/%d)", o, r, t);
    ops->apply_image(m, image);
    EXPECT(ops->mute(m, 0, 1) == CHIP_OK && ops->mute(m, 0, 0) == CHIP_OK, "mute");
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
    double t0 = now_us();
    ymm_start(m, &g_song, 3, 20, SEEK_BUDGET, &mode);
    const double seek = now_us() - t0;
    t0 = now_us();
    ymm_audition(m, &g_song, 0, A4, SINE_SLOT, 0);
    const double audition = now_us() - t0;
    t0 = now_us();
    YmMachine* fresh = ymm_create(RATE);
    const double create = now_us() - t0;
    static YmPlayer p;
    static YmXgmEncoder enc;
    ymp_reset(&p, &g_song);
    ymx_reset(&enc);
    t0 = now_us();
    for (int k = 0; k < 12000; ++k) {
        ymp_tick(&p, &g_song);
        ymx_absorb(&enc, p.write, p.writes);
    }
    const double per = (now_us() - t0) / 12000;
    ymp_reset(&p, &g_song);
    ymx_reset(&enc);
    uint8_t xgc[YMX_MAX_FRAME_BYTES];
    size_t xs;
    t0 = now_us();
    for (int k = 0; k < 3000; ++k) {
        ymp_tick(&p, &g_song);
        ymx_frame(&enc, p.write, p.writes, -1, NULL, xgc, sizeof xgc, &xs);
    }
    const double encode = (now_us() - t0) / 3000;
    printf("  seek to 3/20: %.0f us; audition setup %.0f us; machine creation %.0f us (%zu KB)\n", seek, audition, create,
           (size_t)0);
    printf("  player + encoder fast-forward %.2f us per tick (12000 ticks: %.1f ms); player + XGC encoding %.2f us per tick\n",
           per, per * 12, encode);
    ymm_destroy(fresh);
    ymm_destroy(m);
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    EXPECT(load(&g_song, ym_song_image, sizeof ym_song_image) == 0, "song loads: %s", g_song.info.problem);
    EXPECT(load(&g_moved, ym_moved_image, sizeof ym_moved_image) == 0, "moved loads: %s", g_moved.info.problem);
    EXPECT(load(&g_edited, ym_edited_image, sizeof ym_edited_image) == 0, "edited loads: %s", g_edited.info.problem);
    EXPECT(load(&g_tempo, ym_tempo_image, sizeof ym_tempo_image) == 0, "tempo loads: %s", g_tempo.info.problem);
    EXPECT(load(&g_short, ym_short_image, sizeof ym_short_image) == 0, "short loads: %s", g_short.info.problem);
    EXPECT(load(&g_pal, ym_pal_image, sizeof ym_pal_image) == 0, "pal loads: %s", g_pal.info.problem);
    test_tables();
    test_images();
    printf("player vs reference\n");
    test_player(&REF_SONG, &g_song, "NTSC");
    test_player(&REF_PAL, &g_pal, "PAL");
    test_machine(dir);
    test_stream_vs_xgmtool();
    test_ring();
    test_replay();
    test_audio(dir, &g_song, "ym_song.wav", NTSC_TICK_HZ);
    test_audio(dir, &g_pal, "ym_song_pal.wav", PAL_TICK_HZ);
    test_outliers();
    test_loop();
    test_seek();
    test_live_edits();
    test_audition(dir);
    test_mute();
    test_pal();
    test_exports(dir);
    test_export_entry();
    test_ops();
    test_costs();
    printf("YM2612: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
