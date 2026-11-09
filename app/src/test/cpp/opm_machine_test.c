/* Host regression test for the OPM system: Thor Tracker's own player (opm/opm_player.c) on ymfm's YM2151 at 4 MHz,
 * playing the hand-built test song tools/audio/opm/opm_song.py (opm_test_song.h, from tools/audio/prepare_opm.py),
 * plus small images built here for measurements.
 *
 * Checks: key code and fraction of every note D#0..D-8 against the 4 MHz pitch formula (within 0.2 cents of 12-TET at
 * the ideal 3.579545 MHz key code table), the pitch clamps and operator register offsets; A-4 measured on a sine
 * instrument (439.88 Hz); image validation (every loader reason, by single-byte corruption of the song or by small
 * constructed images); register writes, key-off phases and positions identical to the independent Python reference
 * player (tools/audio/opm/opm_ref.py) in each of 1500 ticks, and its reset batch; deterministic, non-silent output;
 * loop passes stepping through the same positions (= the VGM loop); the exported VGM's loop played twice on ymfm (a
 * note on the loop row attacks again on the second pass); seeks landing on exact rows with the reference's
 * writes afterwards and sound bit-identical to straight play (fully clocked), the order fallback (deterministic); live
 * edits (a re-laid image alternating with the original before every
 * tick, a note edit, a tempo edit ahead of and behind the playhead, a cut song, multispeed x2, a header LFO/CSM edit
 * taking effect in the next tick's shared writes); audition (pitch, fine pitch, release until silent, the noise slot
 * only on FM 8, CSM forced off, argument limits); mute (silence, unchanged writes, unmuting = never muted); the LFO's
 * pitch modulation (present
 * with PMS > 0, absent with PMS 0); the noise generator on FM 8 (broadband against a sine); CSM retriggering at
 * 62500 / (1024 - NA) Hz; VGM bytes identical to the reference's; the hosts' VGM entry point and its GD3 tag; the
 * "ym2151-opm" ops table as the hosts drive it; costs.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir]
 * Writes <dir>/opm_song.wav (48 kHz stereo, 30 s), opm_tone.wav (the A-4 audition, 2 s + release), opm_trace.txt
 * (per-tick player writes), opm_song.vgm (VGM 1.71, YM2151) and opm_song.bin (the image), the inputs of
 * tools/audio/opm/opm_reference_check.py. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "chip_engine.h"
#include "opm_export.h"
#include "opm_machine.h"
#include "opm_test_song.h"
#include "opm_vgm.h"
#include "opm_ymfm.h"

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
#define A4_HZ 439.88 /* key code $48 + fraction 5 at 4 MHz (docs/AUDIO_ENGINE.md, "OPM: Chip and clock") */
#define SEEK_BUDGET OMM_SEEK_TICKS

/* Slots of the measurement image (build_tone). */
#define T_SINE 1
#define T_NOISE 2
#define T_VIBRATO 3
#define T_UNDEFINED 4

static float g_out[OMM_MAX_TICK_FRAMES * 2];
static OpmImage g_song, g_moved, g_edited, g_tempo, g_short, g_x2, g_lfo, g_bad, g_tone, g_csm, g_csm_off, g_loop;

typedef struct Ref {
    const int* offsets;
    const unsigned char* regs;
    const unsigned char* vals;
    const short* pos;
    const unsigned char* offs;
} Ref;

static const Ref REF_SONG = {opm_ref_song_offsets, opm_ref_song_regs, opm_ref_song_vals, opm_ref_song_pos, opm_ref_song_offs};
static const Ref REF_EDITED = {opm_ref_edited_offsets, opm_ref_edited_regs, opm_ref_edited_vals, opm_ref_edited_pos,
                               opm_ref_edited_offs};
static const Ref REF_TEMPO = {opm_ref_tempo_offsets, opm_ref_tempo_regs, opm_ref_tempo_vals, opm_ref_tempo_pos,
                              opm_ref_tempo_offs};

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

/* rms of the left channel's first difference over its rms: ~2 pi f / RATE for a sine, near sqrt(2) for white noise. */
static double roughness(const float* x, int n) {
    double s = 0, d = 0;
    for (int i = 1; i < n; ++i) {
        s += (double)x[2 * i] * x[2 * i];
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

static double peak(const float* x, int frames) {
    double p = 0;
    for (int i = 0; i < frames * 2; ++i)
        if (fabs(x[i]) > p) p = fabs(x[i]);
    return p;
}

static int render(OpmMachine* m, int ticks, float** buf) {
    float* out = (float*)malloc((size_t)ticks * OMM_MAX_TICK_FRAMES * 2 * sizeof(float));
    int frames = 0;
    for (int t = 0; t < ticks; ++t) {
        int n = omm_tick(m, out + 2 * frames);
        if (n <= 0) break;
        frames += n;
    }
    *buf = out;
    return frames;
}

static int load(OpmImage* image, const uint8_t* bytes, size_t used) {
    memset(image->bytes, 0, sizeof image->bytes);
    memcpy(image->bytes, bytes, used);
    return om_load_image(image);
}

static int ref_count(const Ref* r, int t) { return r->offsets[t + 1] - r->offsets[t]; }

/* Whether the player's last batch equals reference tick t (0-based). */
static int same(const OmPlayer* p, const Ref* r, int t) {
    if (p->writes != ref_count(r, t) || p->offs != r->offs[t]) return 0;
    for (int i = 0; i < p->writes; ++i) {
        const int j = r->offsets[t] + i;
        if (p->write[i].reg != r->regs[j] || p->write[i].val != r->vals[j]) return 0;
    }
    return 1;
}

static int same_reset(const OmPlayer* p) {
    if (p->writes != OPM_REF_RESET_WRITES || p->offs != 0) return 0;
    for (int i = 0; i < p->writes; ++i)
        if (p->write[i].reg != opm_ref_reset_regs[i] || p->write[i].val != opm_ref_reset_vals[i]) return 0;
    return 1;
}

/* Whether reference ticks t of a and b have the same writes and positions. */
static int ref_tick_equal(const Ref* a, const Ref* b, int t) {
    if (ref_count(a, t) != ref_count(b, t) || a->offs[t] != b->offs[t]) return 0;
    for (int k = 0; k < 3; ++k)
        if (a->pos[3 * t + k] != b->pos[3 * t + k]) return 0;
    for (int i = 0; i < ref_count(a, t); ++i)
        if (a->regs[a->offsets[t] + i] != b->regs[b->offsets[t] + i] || a->vals[a->offsets[t] + i] != b->vals[b->offsets[t] + i])
            return 0;
    return 1;
}

/* First tick where the two reference traces differ, or -1. */
static int first_difference(const Ref* a, const Ref* b) {
    for (int t = 0; t < OPM_REF_TICKS; ++t)
        if (!ref_tick_equal(a, b, t)) return t;
    return -1;
}

/* The tick that starts the row tick t belongs to (in trace r). */
static int row_start_of(const Ref* r, int t) { return t - r->pos[3 * t + 2]; }

/* First reference tick whose position is (order, row, 0), or -1. */
static int ref_row_start(const Ref* r, int order, int row) {
    for (int t = 0; t < OPM_REF_TICKS; ++t)
        if (r->pos[3 * t] == order && r->pos[3 * t + 1] == row && r->pos[3 * t + 2] == 0) return t;
    return -1;
}

/* Ticks of the row starting at reference tick t. */
static int ref_row_length(const Ref* r, int t) {
    int n = 1;
    while (t + n < OPM_REF_TICKS && r->pos[3 * (t + n) + 2] != 0) ++n;
    return n;
}

static int pos_is(const OpmMachine* m, const Ref* r, int t) {
    int o, w, k;
    omm_position(m, &o, &w, &k);
    return o == r->pos[3 * t] && w == r->pos[3 * t + 1] && k == r->pos[3 * t + 2];
}

/* Whether tick t of the trace writes any of the shared registers. */
static int ref_writes_globals(const Ref* r, int t) {
    for (int i = r->offsets[t]; i < r->offsets[t + 1]; ++i) {
        const int reg = r->regs[i];
        if (reg == 0x0F || reg == 0x10 || reg == 0x11 || reg == 0x14 || reg == 0x18 || reg == 0x19 || reg == 0x1B) return 1;
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

/* One order of `rows` rows at speed 6, every channel's pattern at data offset 0, data[0 .. n). */
static void base_image(OpmImage* im, int rows, const uint8_t* data, int n) {
    memset(im, 0, sizeof *im);
    uint8_t* b = im->bytes;
    memcpy(b, "TTOM", 4);
    b[OM_H_LAYOUT] = OM_LAYOUT;
    b[OM_H_MULTISPEED] = 1;
    b[OM_H_SPEED] = 6;
    put16(b + OM_H_ORDERS, 1);
    b[OM_OFF_ROWS] = (uint8_t)rows;
    memcpy(b + OM_OFF_DATA, data, (size_t)n);
    put16(b + OM_H_DATA_SIZE, n);
}

static void set_pattern(OpmImage* im, int channel, int offset) { put16(im->bytes + OM_OFF_ORDERS + 2 * channel, offset); }

/* Algorithm 7 (every operator a carrier): operators 1-3 silent (TL 127), operator 4 the voice (MUL 1, AR 31). */
static void put_voice(OpmImage* im, int slot, int flags, int sens, int nfrq, int d1r, int rr) {
    uint8_t* r = im->bytes + OM_OFF_INSTRUMENTS + OM_INSTRUMENT_SIZE * (slot - 1);
    memset(r, 0, OM_INSTRUMENT_SIZE);
    r[OM_I_FLAGS] = (uint8_t)(OM_FLAG_DEFINED | flags);
    r[OM_I_CONNECT] = 7;
    r[OM_I_SENS] = (uint8_t)sens;
    r[OM_I_NOISE] = (uint8_t)nfrq;
    for (int k = 0; k < 4; ++k) {
        uint8_t* op = r + OM_I_OPS + OM_OPERATOR_BYTES * k;
        op[0] = 0x01;
        op[1] = k < 3 ? 127 : 0;
        op[2] = 31;
        op[3] = (uint8_t)(k < 3 ? 0 : d1r);
        op[4] = 0;
        op[5] = (uint8_t)(k < 3 ? 15 : rr);
    }
    if (im->bytes[OM_H_INSTRUMENTS] < slot) im->bytes[OM_H_INSTRUMENTS] = (uint8_t)slot;
}

static const uint8_t EMPTY_ROW[] = {0x80};

/* Auditions only: a sine (RR 6), a sustained noise voice (NFRQ 24, FM 8 only), the sine with PMS 6. The header has a
 * 3.8 Hz triangle LFO at full pitch depth and CSM on (timer A 512): auditions must force CSM off. */
static void build_tone(void) {
    OpmImage* im = &g_tone;
    base_image(im, 1, EMPTY_ROW, 1);
    uint8_t* b = im->bytes;
    b[OM_H_LFO_RATE] = 0xC0;
    b[OM_H_LFO_WAVE] = 2;
    b[OM_H_PMD] = 127;
    b[OM_H_CSM] = 1;
    put16(b + OM_H_TIMER_A, 512);
    put_voice(im, T_SINE, 0, 0x00, 0, 0, 6);
    put_voice(im, T_NOISE, OM_FLAG_NOISE, 0x00, 24, 0, 15);
    put_voice(im, T_VIBRATO, 0, 0x60, 0, 0, 6);
    EXPECT(om_load_image(im) == 0, "tone image loads: %s", im->info.problem);
}

/* A song for CSM: FM 1 keys A-4 (AR 31, RR 15) at row 0 and off at tick 1 (S01); the row lasts 255 ticks. Timer A 0:
 * one overflow every 1024 native samples. */
static void build_csm(OpmImage* im, int csm) {
    static const uint8_t data[] = {0x80, 0x07, A4, 1, OM_FX_CUT, 1};
    base_image(im, 1, data, (int)sizeof data);
    set_pattern(im, 0, 1);
    im->bytes[OM_H_SPEED] = 255;
    im->bytes[OM_H_CSM] = (uint8_t)csm;
    put_voice(im, 1, 0, 0x00, 0, 0, 15);
    EXPECT(om_load_image(im) == 0, "CSM image loads: %s", im->info.problem);
}

/* A song for the VGM loop: one order of 16 rows at speed 6 (96 ticks), loop order 0. FM 1 keys A-4 at row 0 and holds
 * it to the song's end: operator 4 AR 31 (an instant attack), D1R 12 toward D1L 15, so the held note is clearly decayed
 * when the loop comes round. */
static void build_loop(void) {
    static const uint8_t data[] = {0x8F, 0x03, A4, 1, 0x8E};
    OpmImage* im = &g_loop;
    base_image(im, 16, data, (int)sizeof data);
    set_pattern(im, 0, 1);
    put_voice(im, 1, 0, 0x00, 0, 12, 15);
    im->bytes[OM_OFF_INSTRUMENTS + OM_I_OPS + 3 * OM_OPERATOR_BYTES + 5] = 0xF0 | 15; /* operator 4: D1L 15, RR 15 */
    EXPECT(om_load_image(im) == 0, "VGM loop image loads: %s", im->info.problem);
}

/* ---- tests ---------------------------------------------------------------------------------- */

static void test_key_codes(void) {
    printf("key codes\n");
    static const int SEMITONE_OF_CODE[16] = {0, 1, 2, -1, 3, 4, 5, -1, 6, 7, 8, -1, 9, 10, 11, -1};
    double worst = 0;
    int bad = 0;
    for (int n = OM_NOTE_MIN; n <= OM_NOTE_MAX; ++n) {
        int kc, kf;
        omp_key_code(n * 64, &kc, &kf);
        const int s = SEMITONE_OF_CODE[kc & 15];
        if (s < 0 || kc > 0x7E || (kf & 3) || kf > 0xFC) {
            ++bad;
            continue;
        }
        /* The 3.579545 MHz table: key code (octave, note C#..C) = semitone 12 octave + s + 1 above C-0, A-4 = 440 Hz;
         * the 4 MHz clock raises everything by 4000000 / 3579545. */
        const double units = (12 * (kc >> 4) + s + 1) * 64.0 + (kf >> 2);
        const double hz = 440.0 * pow(2.0, (units / 64.0 - A4) / 12.0) * (4000000.0 / 3579545.0);
        const double cents = fabs(1200.0 * log2(hz / (440.0 * pow(2.0, (n - A4) / 12.0))));
        if (cents > worst) worst = cents;
    }
    EXPECT(bad == 0, "every note D#0..D-8 has a valid key code and fraction (%d bad)", bad);
    EXPECT(worst < 0.2, "every note within 0.2 cents of 12-TET at the ideal table (worst %.3f)", worst);
    int kc, kf;
    omp_key_code(A4 * 64, &kc, &kf);
    EXPECT(kc == 0x48 && kf == 5 << 2, "A-4 = key code $48 (4/G) + fraction 5 (got $%02X, %d)", kc, kf >> 2);
    int lo_kc, lo_kf, hi_kc, hi_kf;
    omp_key_code(0, &lo_kc, &lo_kf);
    omp_key_code(-5000, &kc, &kf);
    EXPECT(lo_kc == 0x00 && lo_kf == 0 && kc == 0x00 && kf == 0, "pitch below 187 clamps to C#0 fraction 0");
    omp_key_code(OMP_PITCH_MAX, &hi_kc, &hi_kf);
    omp_key_code(100000, &kc, &kf);
    EXPECT(hi_kc == 0x7E && hi_kf == 63 << 2 && kc == 0x7E && kf == 63 << 2, "pitch above 6330 clamps to C-8 fraction 63");
    omp_key_code(OMP_PITCH_MIN, &kc, &kf);
    EXPECT(kc == 0x00 && kf == 0, "pitch 187 = C#0 fraction 0");
    EXPECT(OM_NOTE_MIN * 64 >= OMP_PITCH_MIN && (OM_NOTE_MIN - 1) * 64 < OMP_PITCH_MIN && OM_NOTE_MAX * 64 <= OMP_PITCH_MAX &&
               (OM_NOTE_MAX + 1) * 64 > OMP_PITCH_MAX,
           "D#0..D-8 are exactly the notes inside the clamps");
    int offsets_ok = 1;
    for (int c = 0; c < OM_CHANNELS; ++c) {
        if (omp_operator_offset(c, 0) != c || omp_operator_offset(c, 1) != 16 + c || omp_operator_offset(c, 2) != 8 + c ||
            omp_operator_offset(c, 3) != 24 + c)
            offsets_ok = 0;
    }
    EXPECT(offsets_ok, "operators 1..4 (M1, C1, M2, C2) at register offsets c, 16 + c, 8 + c, 24 + c");
    printf("  notes %d..%d within %.3f cents; A-4 = $%02X/%d\n", OM_NOTE_MIN, OM_NOTE_MAX, worst, 0x48, 5);
}

static void corrupt(int offset, int value, const char* want) {
    memcpy(&g_bad, &g_song, sizeof g_bad);
    g_bad.bytes[offset] = (uint8_t)value;
    const int rc = om_load_image(&g_bad);
    EXPECT(rc != 0 && g_bad.info.problem && strstr(g_bad.info.problem, want), "byte %d = %d rejected for \"%s\" (got %s)",
           offset, value, want, rc ? g_bad.info.problem : "accepted");
    if (rc) printf("  rejected @%d: %s\n", g_bad.info.problem_offset, g_bad.info.problem);
}

static void accepted(int offset, int value, const char* what) {
    memcpy(&g_bad, &g_song, sizeof g_bad);
    g_bad.bytes[offset] = (uint8_t)value;
    const int rc = om_load_image(&g_bad);
    EXPECT(rc == 0, "%s: byte %d = %d accepted (got %s)", what, offset, value, rc ? g_bad.info.problem : "ok");
}

/* A constructed image (g_bad, already filled) rejected for `want` or accepted (want NULL). */
static void constructed(const char* what, const char* want) {
    const int rc = om_load_image(&g_bad);
    if (want)
        EXPECT(rc != 0 && g_bad.info.problem && strstr(g_bad.info.problem, want), "%s rejected for \"%s\" (got %s)", what, want,
               rc ? g_bad.info.problem : "accepted");
    else
        EXPECT(rc == 0, "%s accepted (got %s)", what, rc ? g_bad.info.problem : "ok");
    if (rc) printf("  %s: rejected @%d: %s\n", what, g_bad.info.problem_offset, g_bad.info.problem);
}

static void test_images(void) {
    printf("images\n");
    const OmInfo* info = &g_song.info;
    EXPECT(info->loaded == OM_LOADED && info->orders == 4 && info->loop == 1 && info->multispeed == 1, "song info");
    EXPECT(info->rows[0] == 16 && info->rows[1] == 32 && info->rows[2] == 16 && info->rows[3] == 24, "rows per order");
    printf("  start speeds %d %d %d %d, %d instruments\n", info->start_speed[0], info->start_speed[1], info->start_speed[2],
           info->start_speed[3], info->instruments);
    const int ins = OM_OFF_INSTRUMENTS;
    corrupt(0, 'X', "magic");
    corrupt(OM_H_LAYOUT, 2, "layout");
    corrupt(OM_H_MULTISPEED, 0, "multispeed");
    corrupt(OM_H_MULTISPEED, 9, "multispeed");
    corrupt(OM_H_SPEED, 0, "speed");
    corrupt(OM_H_LFO_WAVE, 4, "LFO waveform");
    corrupt(OM_H_ORDERS, 0, "order count");
    corrupt(OM_H_LOOP, 4, "loop");
    corrupt(OM_H_INSTRUMENTS, 65, "instrument count above 64");
    corrupt(OM_H_INSTRUMENTS, info->instruments - 1, "past the instrument count");
    corrupt(OM_H_CSM, 2, "CSM must be 0 or 1");
    corrupt(OM_H_DATA_SIZE + 1, 0xFF, "data size");
    corrupt(OM_H_AMD, 128, "amplitude depth");
    corrupt(OM_H_PMD, 128, "pitch depth");
    corrupt(19, 1, "reserved byte 19");
    corrupt(OM_H_TIMER_A + 1, 4, "timer A");
    corrupt(40, 1, "reserved bytes 22..63");
    corrupt(OM_OFF_ROWS + 9, 4, "rows of an order past the order count");
    corrupt(OM_OFF_ORDERS + 2 * OM_CHANNELS * 9, 1, "patterns of an order past the order count");
    corrupt(OM_OFF_ORDERS + 1, 0xF0, "pattern offset");
    corrupt(ins + OM_I_FLAGS, 0x83, "flags");
    corrupt(ins + OM_I_FLAGS, 0x01, "flags");
    corrupt(ins + OM_I_CONNECT, 0x47, "connection");
    corrupt(ins + OM_I_SENS, 0x08, "sensitivity");
    corrupt(ins + OM_I_SENS, 0x80, "sensitivity");
    corrupt(ins + 32 + OM_I_NOISE, 32, "noise frequency above 31");
    corrupt(ins + OM_I_NOISE, 5, "without the noise flag");
    corrupt(ins + OM_I_OPS, 0x81, "reserved bits");
    corrupt(ins + OM_I_OPS + 1, 0x80, "reserved bits");
    corrupt(ins + OM_I_OPS + 2, 0x3F, "reserved bits");
    corrupt(ins + OM_I_OPS + 3, 0x20, "reserved bits");
    corrupt(ins + OM_I_OPS + 4, 0x20, "reserved bits");
    corrupt(ins + OM_I_SWEEP_START, 97, "sweep");
    corrupt(ins + OM_I_SWEEP_END, (uint8_t)-97, "sweep");
    corrupt(ins + 31, 1, "reserved byte 31");
    corrupt(OPM_OFFSET_MASK, 0x20, "mask");
    corrupt(OPM_OFFSET_MASK, 0x17, "mask");
    corrupt(OPM_OFFSET_NOTE, OM_NOTE_MIN - 1, "note");
    corrupt(OPM_OFFSET_NOTE, OM_NOTE_MAX + 1, "note");
    corrupt(OPM_OFFSET_INST, 65, "instrument the image does not define");
    corrupt(OPM_OFFSET_INST, 0, "instrument the image does not define");
    corrupt(OPM_OFFSET_EFFECT, 36, "effect code above Z");
    corrupt(OPM_OFFSET_EFFECT, 5, "unknown effect");
    corrupt(OPM_OFFSET_PARAM_B, 4, "Bxx");
    corrupt(OPM_OFFSET_PARAM_C, 0x80, "Cxx");
    corrupt(OPM_OFFSET_PARAM_E, 8, "Exx");
    corrupt(OPM_OFFSET_PARAM_F, 0, "F00");
    corrupt(OPM_OFFSET_PARAM_H, 0x80, "Hxy");
    corrupt(OPM_OFFSET_PARAM_H, 0x04, "Hxy");
    corrupt(OPM_OFFSET_PARAM_I, 0x80, "Ixx");
    corrupt(OPM_OFFSET_PARAM_M, 0x53, "Mxy");
    corrupt(OPM_OFFSET_PARAM_M, 0x03, "Mxy");
    corrupt(OPM_OFFSET_PARAM_N, 0x20, "Nxx noise frequency above 1F");
    corrupt(OPM_OFFSET_PARAM_T, 0x80, "Txx");
    corrupt(OPM_OFFSET_PARAM_V, 0x80, "Vxx");
    corrupt(OPM_OFFSET_PARAM_W, 4, "Wxx");
    corrupt(OPM_OFFSET_PARAM_X, 2, "Xxx");
    accepted(OPM_OFFSET_PARAM_Q, 0xFF, "Qxx takes any parameter");
    accepted(OPM_OFFSET_PARAM_X, 0, "X00");
    /* The noise slot named on FM 8 is a noise instrument; any defined instrument may play there. */
    const int noise_slot = g_song.bytes[OPM_OFFSET_NOISE_INST];
    EXPECT(noise_slot >= 1 && noise_slot <= info->instruments &&
               (g_song.bytes[ins + OM_INSTRUMENT_SIZE * (noise_slot - 1) + OM_I_FLAGS] & OM_FLAG_NOISE),
           "the noise row on FM 8 names a noise instrument (slot %d)", noise_slot);
    accepted(OPM_OFFSET_NOISE_INST, 1, "a non-noise instrument on FM 8");

    /* Constructed images for the reasons a single byte of the song cannot reach. */
    base_image(&g_bad, 2, EMPTY_ROW, 1);
    constructed("a pattern decoding past the data", "pattern runs past the data size");
    {
        static const uint8_t data[] = {0x01};
        base_image(&g_bad, 1, data, 1);
        constructed("a row whose note byte is past the data", "row runs past the data size");
    }
    {
        static const uint8_t data[] = {0x02, 1};
        base_image(&g_bad, 1, data, 2);
        put_voice(&g_bad, 1, OM_FLAG_NOISE, 0, 24, 0, 15);
        constructed("a noise instrument on FM 1", "noise instrument off FM 8");
        static const uint8_t fm8[] = {0x80, 0x02, 1};
        base_image(&g_bad, 1, fm8, 3);
        set_pattern(&g_bad, 7, 1);
        put_voice(&g_bad, 1, OM_FLAG_NOISE, 0, 24, 0, 15);
        constructed("a noise instrument on FM 8", NULL);
    }
    {
        static const uint8_t data[] = {0x04, OM_FX_NOISE, 5};
        base_image(&g_bad, 1, data, 3);
        constructed("N05 on FM 1", "Nxx noise frequency off FM 8");
        static const uint8_t fm8[] = {0x80, 0x04, OM_FX_NOISE, 5};
        base_image(&g_bad, 1, fm8, 4);
        set_pattern(&g_bad, 7, 1);
        constructed("N05 on FM 8", NULL);
    }
    base_image(&g_bad, 1, EMPTY_ROW, 1);
    put16(g_bad.bytes + OM_H_DATA_SIZE, (int)OM_DATA_CAPACITY + 1);
    constructed("data size 59073", "data size above the region");
    base_image(&g_bad, 1, EMPTY_ROW, 1);
    put16(g_bad.bytes + OM_H_TIMER_A, 1023);
    g_bad.bytes[OM_H_LFO_RATE] = 0xFF;
    g_bad.bytes[OM_H_AMD] = 127;
    g_bad.bytes[OM_H_PMD] = 127;
    g_bad.bytes[OM_H_LFO_WAVE] = 3;
    g_bad.bytes[OM_H_CSM] = 1;
    constructed("the largest header values", NULL);

    OpmImage* fresh = &g_bad;
    memcpy(fresh, &g_song, sizeof *fresh);
    EXPECT(om_load_image(fresh) == 0 && !memcmp(&fresh->info, &g_song.info, sizeof fresh->info), "loading is a pure function");
}

static void test_reference(const char* dir) {
    printf("reference player\n");
    OpmMachine* m = omm_create(RATE);
    int mode = -1;
    EXPECT(omm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode) == OMM_OK && mode == OMM_SEEK_FROM_START, "start");
    const OmPlayer* p = omm_player(m);
    EXPECT(same_reset(p), "reset batch = reference (%d writes, reference %d)", p->writes, OPM_REF_RESET_WRITES);
    char path[512];
    snprintf(path, sizeof path, "%s/opm_trace.txt", dir);
    FILE* trace = fopen(path, "w");
    int mismatches = 0, first = -1, position_errors = 0, writes = 0, max_writes = 0, offs_ticks = 0;
    int saw_noise = 0, saw_csm = 0, csm_off_after = 0, waves = 0, keyed_max = 0;
    for (int t = 0; t < OPM_REF_TICKS; ++t) {
        if (!pos_is(m, &REF_SONG, t)) ++position_errors;
        int o, r, k;
        omm_position(m, &o, &r, &k);
        omm_tick(m, g_out);
        if (!same(p, &REF_SONG, t)) {
            if (first < 0) first = t;
            ++mismatches;
        }
        writes += p->writes;
        if (p->writes > max_writes) max_writes = p->writes;
        if (p->offs) ++offs_ticks;
        if (trace) {
            fprintf(trace, "%d %d %d:", o, r, k);
            for (int i = 0; i < p->writes; ++i) {
                fprintf(trace, " %02X=%02X", p->write[i].reg, p->write[i].val);
                if (i + 1 == p->offs) fputs(" |", trace);
            }
            fputc('\n', trace);
        }
        if (p->shadow[0x0F] & 0x80) saw_noise = 1;
        if (p->shadow[0x14] == 0x81) saw_csm = 1;
        else if (saw_csm) csm_off_after = 1;
        waves |= 1 << (p->shadow[0x1B] & 3);
        int keyed = 0;
        for (int c = 0; c < OM_CHANNELS; ++c) keyed += p->ch[c].keyed;
        if (keyed > keyed_max) keyed_max = keyed;
    }
    if (trace) fclose(trace);
    EXPECT(mismatches == 0, "writes = reference player in all %d ticks (%d differ, first at tick %d)", OPM_REF_TICKS, mismatches,
           first);
    if (first >= 0) {
        printf("  first difference at tick %d; reference:", first);
        for (int j = REF_SONG.offsets[first]; j < REF_SONG.offsets[first + 1] && j < REF_SONG.offsets[first] + 16; ++j)
            printf(" %02X=%02X", REF_SONG.regs[j], REF_SONG.vals[j]);
        printf("\n");
    }
    EXPECT(position_errors == 0, "positions = reference in every tick (%d differ)", position_errors);
    EXPECT(offs_ticks > 0, "key-off phases occur (%d ticks)", offs_ticks);
    EXPECT(saw_noise, "the noise generator is switched on (FM 8)");
    EXPECT(saw_csm && csm_off_after, "CSM switches on and back off");
    EXPECT(waves == 0xF, "all four LFO waveforms are used (mask %X)", waves);
    printf("  %d ticks, %d writes (max %d per tick), key-off phases in %d ticks, up to %d channels keyed\n", OPM_REF_TICKS,
           writes, max_writes, offs_ticks, keyed_max);
    omm_destroy(m);
}

static void test_audio(const char* dir) {
    printf("audio\n");
    const int ticks = SECONDS * 60;
    uint64_t hash[2] = {0, 0};
    for (int pass = 0; pass < 2; ++pass) {
        OpmMachine* m = omm_create(RATE);
        int mode;
        omm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
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
            snprintf(path, sizeof path, "%s/opm_song.wav", dir);
            write_wav(path, buf, frames);
            printf("  30 s: rms %.4f, peak %.4f, %.1f us per tick (%.0fx realtime)\n", r, pk, us, 16666.7 / us);
        }
        free(buf);
        omm_destroy(m);
    }
    EXPECT(hash[0] == hash[1], "bit-identical on a second start");
}

static void test_output_rates(void) {
    const uint32_t rates[] = {8000, 44100, 96000};
    for (size_t i = 0; i < sizeof rates / sizeof rates[0]; ++i) {
        OpmMachine* m = omm_create(rates[i]);
        int mode, frames = 0;
        double largest = 0;
        omm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
        for (int t = 0; t < 60; ++t) {
            const int n = omm_tick(m, g_out);
            const double p = peak(g_out, n);
            if (p > largest) largest = p;
            frames += n;
        }
        EXPECT(frames >= (int)rates[i] - 2 && frames <= (int)rates[i] + 2,
               "%u Hz: one second has %d frames", rates[i], frames);
        EXPECT(largest > 0.01 && largest < 0.999,
               "%u Hz: loud eight-channel intro remains audible without resampler clipping (peak %.4f)", rates[i], largest);
        omm_destroy(m);
    }
}

/* Every pass of the loop plays like the first: the loop order is entered from order 0 and later by B01 from order 3;
 * the player re-derives the speed at the loop point. */
static void test_loop(void) {
    printf("loop passes\n");
    enum { MAX_PASS = 4096 };
    static int pos[4][MAX_PASS];
    int len[4] = {0};
    OpmMachine* m = omm_create(RATE);
    int mode, o, r, t, pass = -1;
    omm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 4 * MAX_PASS && pass < 4; ++k) {
        omm_position(m, &o, &r, &t);
        if (o == g_song.info.loop && r == 0 && t == 0) ++pass;
        omm_tick(m, g_out);
        if (pass < 0 || pass >= 4 || len[pass] >= MAX_PASS) continue;
        pos[pass][len[pass]++] = o << 16 | r << 8 | t;
    }
    EXPECT(pass == 4 && len[0] == len[1] && len[1] == len[2] && len[0] < MAX_PASS, "passes of %d, %d, %d ticks", len[0], len[1],
           len[2]);
    EXPECT(!memcmp(pos[0], pos[1], sizeof(int) * (size_t)len[0]) && !memcmp(pos[1], pos[2], sizeof(int) * (size_t)len[0]),
           "every pass steps through the same (order, row, tick) positions");
    EXPECT((uint32_t)len[0] * 735 == OPM_REF_VGM_LOOP_SAMPLES, "= the VGM loop (%u samples)", (unsigned)OPM_REF_VGM_LOOP_SAMPLES);
    printf("  %d ticks per pass; speed at the loop point %d\n", len[0], g_song.info.start_speed[g_song.info.loop]);
    omm_destroy(m);
}

/* Benchmark after 1 s of warm-up. Thread CPU time excludes descheduling, but cache misses, page faults and CPU
 * frequency still vary; their worst/mean ratio is not an engine correctness invariant. */
static void measure_tick_cost(void) {
    printf("tick cost outliers\n");
    const int ticks = SECONDS * 60;
    OpmMachine* m = omm_create(RATE);
    int mode;
    omm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 60; ++k) omm_tick(m, g_out);
    double sum = 0, worst = 0;
    int worst_at = 0;
    for (int k = 0; k < ticks; ++k) {
        const double c0 = cpu_us();
        omm_tick(m, g_out);
        const double elapsed = cpu_us() - c0;
        sum += elapsed;
        if (elapsed > worst) worst = elapsed, worst_at = k;
    }
    const double mean = sum / ticks;
    printf("  thread CPU per tick: mean %.1f us, worst %.1f us (%.2fx, tick %d)\n", mean, worst, worst / mean, worst_at);
    omm_destroy(m);
}

static void test_seek(void) {
    printf("seek\n");
    /* Targets: the rows straight play is in at these ticks (each sought at its first arrival). */
    static const int at_ticks[] = {5, 40, 120, 250, 400, 600, 900, 1200};
    OpmMachine* m = omm_create(RATE);
    int compare_targets[8][2], n_compare = 0;
    for (size_t i = 0; i < sizeof at_ticks / sizeof at_ticks[0]; ++i) {
        const int o = REF_SONG.pos[3 * at_ticks[i]], r = REF_SONG.pos[3 * at_ticks[i] + 1], k = ref_row_start(&REF_SONG, o, r);
        EXPECT(k >= 0, "target %d/%d is in the reference", o, r);
        if (k < 0) continue;
        int mode = -1;
        const double t0 = now_us();
        EXPECT(omm_start(m, &g_song, o, r, SEEK_BUDGET, &mode) == OMM_OK && mode == OMM_SEEK_FROM_START, "seek %d/%d", o, r);
        const double us = now_us() - t0;
        EXPECT(pos_is(m, &REF_SONG, k), "lands on %d/%d tick 0", o, r);
        int bad = 0;
        const int n = OPM_REF_TICKS - k < 200 ? OPM_REF_TICKS - k : 200;
        for (int j = 0; j < n; ++j) {
            if (!pos_is(m, &REF_SONG, k + j)) ++bad;
            omm_tick(m, g_out);
            if (!same(omm_player(m), &REF_SONG, k + j)) ++bad;
        }
        EXPECT(bad == 0, "seek %d/%d: %d ticks of writes = straight play (%d differ)", o, r, n, bad);
        printf("  %d/%d: %d ticks fast-forwarded in %.0f us\n", o, r, k, us);
        if (n_compare < 8) {
            compare_targets[n_compare][0] = o;
            compare_targets[n_compare][1] = r;
            ++n_compare;
        }
    }
    /* Sound after a seek against straight play from the same tick, 1 s. Seeks are fully clocked (every tick of the way
     * runs the chip and the resampler as in play), so the sound must be bit-identical at every target. */
    for (int i = 0; i < n_compare; ++i) {
        const int o = compare_targets[i][0], r = compare_targets[i][1], k = ref_row_start(&REF_SONG, o, r);
        OpmMachine* a = omm_create(RATE);
        int mode;
        omm_start(a, &g_song, 0, 0, SEEK_BUDGET, &mode);
        for (int j = 0; j < k; ++j) omm_tick(a, g_out);
        float *x, *y;
        const int fx = render(a, 60, &x);
        omm_start(m, &g_song, o, r, SEEK_BUDGET, &mode);
        const int fy = render(m, 60, &y);
        int first = -1;
        for (int s = 0; s < 2 * (fx < fy ? fx : fy) && first < 0; ++s)
            if (x[s] != y[s]) first = s / 2;
        const int identical = fx == fy && first < 0;
        EXPECT(identical, "seek %d/%d (%d ticks): sound = straight play (frames %d/%d, first difference at frame %d)", o, r,
               k, fx, fy, first);
        printf("  %d/%d vs straight play: %s\n", o, r, identical ? "identical" : "differs");
        free(x);
        free(y);
        omm_destroy(a);
    }
    /* Order 3 rows 0-1 are only reached by the order fallback (order 2 breaks to row 2). */
    EXPECT(ref_row_start(&REF_SONG, 3, 0) < 0, "3/0 is not reached by straight play");
    int mode = -1;
    EXPECT(omm_start(m, &g_song, 3, 0, SEEK_BUDGET, &mode) == OMM_OK && mode == OMM_SEEK_FROM_ORDER, "3/0: order fallback");
    int o, r, t, ok = 1, rows_seen = 0, last_row = 0;
    omm_position(m, &o, &r, &t);
    EXPECT(o == 3 && r == 0 && t == 0, "fallback lands on 3/0 (%d/%d/%d)", o, r, t);
    for (int j = 0; j < 255 * 4 && rows_seen < 4; ++j) {
        omm_tick(m, g_out);
        omm_position(m, &o, &r, &t);
        if (t == 0) {
            if (o != 3 || r != last_row + 1) ok = 0;
            last_row = r;
            ++rows_seen;
        }
    }
    EXPECT(ok && rows_seen == 4, "fallback then plays order 3 row by row (%d rows)", rows_seen);
    EXPECT(omm_start(m, &g_song, 1, 17, 10, &mode) == OMM_OK && mode == OMM_SEEK_FROM_ORDER, "small budget: fallback");
    omm_position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 17 && t == 0, "budget fallback lands on 1/17");
    /* The fallback is clocked the same way from its placement: deterministic, sample for sample. */
    {
        float *x, *y;
        const int fx = render(m, 60, &x);
        OpmMachine* b = omm_create(RATE);
        int mode_b = -1;
        omm_start(b, &g_song, 1, 17, 10, &mode_b);
        const int fy = render(b, 60, &y);
        EXPECT(mode_b == OMM_SEEK_FROM_ORDER && fx == fy && !memcmp(x, y, sizeof(float) * 2 * (size_t)fx),
               "order fallback: the same sound on a second machine");
        free(x);
        free(y);
        omm_destroy(b);
    }
    EXPECT(omm_start(m, &g_song, 4, 0, SEEK_BUDGET, &mode) == OMM_ERR_IMAGE, "order past the song rejected");
    EXPECT(omm_start(m, &g_song, 0, 16, SEEK_BUDGET, &mode) == OMM_ERR_IMAGE, "row past the order rejected");
    omm_destroy(m);
}

/* Plays `image` from the start, applying `alt0`/`alt1` (odd/even ticks) before tick t (from tick `from`; every tick or
 * only then), and compares with `ref`. */
static int play_applying(const OpmImage* image, const OpmImage* alt0, const OpmImage* alt1, int from, int every,
                         const Ref* ref, int* first) {
    OpmMachine* m = omm_create(RATE);
    int mode, bad = 0;
    omm_start(m, image, 0, 0, SEEK_BUDGET, &mode);
    *first = -1;
    for (int t = 0; t < OPM_REF_TICKS; ++t) {
        if (t >= from && (every || t == from)) omm_apply_image(m, (t % 2) ? alt0 : alt1);
        if (!pos_is(m, ref, t)) ++bad;
        omm_tick(m, g_out);
        if (!same(omm_player(m), ref, t)) {
            if (*first < 0) *first = t;
            ++bad;
        }
    }
    omm_destroy(m);
    return bad;
}

/* Ticks the current row lasts, the tick just run included (the machine has run one tick of it). */
static int rest_of_row(OpmMachine* m) {
    int o, r, t, n = 1;
    for (;;) {
        omm_position(m, &o, &r, &t);
        if (t == 0 || n > 600) return n;
        omm_tick(m, g_out);
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
        OpmMachine* m = omm_create(RATE);
        int mode;
        omm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
        double worst = 0, total = 0;
        for (int k = 0; k < 1500; ++k) {
            const double t0 = now_us();
            omm_apply_image(m, (k & 1) ? &g_moved : &g_song);
            const double us = now_us() - t0;
            total += us;
            if (us > worst) worst = us;
            omm_tick(m, g_out);
        }
        printf("  apply_image: %.2f us mean, worst %.1f us\n", total / 1500, worst);
        omm_destroy(m);
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
    /* Tempo: one Fxx changed ahead in the song. Applied ahead of its row: straight play of the tempo song. */
    const int d_tempo = first_difference(&REF_SONG, &REF_TEMPO);
    EXPECT(d_tempo > 0, "the tempo edit changes the reference (first at tick %d)", d_tempo);
    if (d_tempo > 0) {
        const int k_tempo = row_start_of(&REF_SONG, d_tempo);
        const int from = k_tempo >= 3 ? k_tempo - 3 : 0;
        bad = play_applying(&g_song, &g_tempo, &g_tempo, from, 0, &REF_TEMPO, &first);
        EXPECT(bad == 0, "tempo edit ahead of the playhead: = tempo song straight play (%d differ, first %d)", bad, first);
        /* Behind the playhead: the row has started at the old speed; the edit gives it the new one, the undo the old. */
        const int o = REF_SONG.pos[3 * k_tempo], r = REF_SONG.pos[3 * k_tempo + 1];
        const int edited_start = ref_row_start(&REF_TEMPO, o, r);
        EXPECT(edited_start >= 0, "edited reference reaches the same order/row %d/%d", o, r);
        if (edited_start < 0) return;
        const int old_len = ref_row_length(&REF_SONG, k_tempo), new_len = ref_row_length(&REF_TEMPO, edited_start);
        OpmMachine* m = omm_create(RATE);
        int mode;
        omm_start(m, &g_song, o, r, SEEK_BUDGET, &mode);
        omm_tick(m, g_out);
        omm_apply_image(m, &g_tempo);
        const int a = rest_of_row(m);
        omm_start(m, &g_song, o, r, SEEK_BUDGET, &mode);
        omm_tick(m, g_out);
        omm_apply_image(m, &g_tempo);
        omm_apply_image(m, &g_song);
        const int b = rest_of_row(m);
        EXPECT(a == new_len && b == old_len, "tempo edit behind the playhead (%d/%d): row of %d ticks (want %d), undo %d (want %d)",
               o, r, a, new_len, b, old_len);
        /* Editing Fxx on the row already playing must include that row's own effect, not only earlier rows. */
        EXPECT(omm_start(m, &g_song, 1, 4, SEEK_BUDGET, &mode) == OMM_OK, "start the F09 edit's own row");
        omm_tick(m, g_out);
        omm_apply_image(m, &g_tempo);
        const int current_changed = rest_of_row(m);
        EXPECT(omm_start(m, &g_song, 1, 4, SEEK_BUDGET, &mode) == OMM_OK, "restart the F09 edit's own row");
        omm_tick(m, g_out);
        omm_apply_image(m, &g_tempo);
        omm_apply_image(m, &g_song);
        const int current_undone = rest_of_row(m);
        EXPECT(current_changed == 9 && current_undone == 6,
               "tempo edit on the playing row: %d ticks (want 9), undo %d (want 6)", current_changed, current_undone);
        omm_destroy(m);
    }
    {
        OpmMachine* m = omm_create(RATE);
        int mode, o, r, t;
        /* Cut the song to two orders (loop 0) while order 3 plays: clamped, then order 0 from its first row. */
        omm_start(m, &g_song, 3, 5, SEEK_BUDGET, &mode);
        omm_tick(m, g_out);
        omm_apply_image(m, &g_short);
        omm_position(m, &o, &r, &t);
        EXPECT(o == 1 && r == 31, "clamped to the last row of the last order (%d/%d)", o, r);
        int ok = 1, rows_seen = 0, last_order = o, last_row = r;
        for (int k = 0; k < 255 * 8 && rows_seen < 6; ++k) {
            omm_tick(m, g_out);
            omm_position(m, &o, &r, &t);
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
        omm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
        omm_tick(m, g_out);
        const int n1 = omm_native_samples(m);
        omm_apply_image(m, &g_x2);
        omm_tick(m, g_out);
        const int n2 = omm_native_samples(m);
        EXPECT((n1 == 1041 || n1 == 1042) && (n2 == 520 || n2 == 521), "x2 live: %d -> %d native samples per tick", n1, n2);
        omm_destroy(m);
    }
    /* Header LFO/CSM edit: the next tick's shared writes carry the new song-start values. */
    {
        /* Two consecutive ticks inside rows (no row effects run) without shared writes in straight play. */
        int t = 1;
        while (t + 1 < OPM_REF_TICKS && !(quiet_tick(&REF_SONG, t) && quiet_tick(&REF_SONG, t + 1))) ++t;
        EXPECT(t + 1 < OPM_REF_TICKS, "two ticks without row starts or shared writes (%d)", t);
        const uint8_t* h = g_lfo.bytes;
        const uint8_t* s = g_song.bytes;
        EXPECT(h[OM_H_LFO_RATE] != s[OM_H_LFO_RATE] && h[OM_H_AMD] != s[OM_H_AMD] && h[OM_H_PMD] != s[OM_H_PMD] &&
                   h[OM_H_LFO_WAVE] != s[OM_H_LFO_WAVE] && h[OM_H_CSM] == 1 && om_u16(h + OM_H_TIMER_A) == 900,
               "the LFO image edits every shared header field");
        OpmMachine* m = omm_create(RATE);
        int mode;
        omm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
        for (int k = 0; k < t; ++k) omm_tick(m, g_out);
        omm_apply_image(m, &g_lfo);
        omm_tick(m, g_out);
        const OmPlayer* p = omm_player(m);
        EXPECT(p->shadow[0x18] == h[OM_H_LFO_RATE] && p->shadow[0x1B] == h[OM_H_LFO_WAVE] && p->shadow[0x19] == h[OM_H_AMD] &&
                   p->shadow[OMP_SHADOW_PMD] == h[OM_H_PMD] && p->shadow[0x14] == 0x81 && p->shadow[0x10] == (900 >> 2) &&
                   p->shadow[0x11] == (900 & 3),
               "after the edit: $18=%02X $1B=%02X AMD=%02X PMD=%02X $14=%02X $10=%02X $11=%02X", p->shadow[0x18], p->shadow[0x1B],
               p->shadow[0x19], p->shadow[OMP_SHADOW_PMD], p->shadow[0x14], p->shadow[0x10], p->shadow[0x11]);
        int globals_first = p->writes >= 1 + p->offs && p->write[p->offs].reg == 0x18;
        EXPECT(globals_first, "the shared writes open the tick's batch");
        omm_apply_image(m, &g_song);
        omm_tick(m, g_out);
        EXPECT(p->shadow[0x18] == s[OM_H_LFO_RATE] && p->shadow[0x1B] == s[OM_H_LFO_WAVE] && p->shadow[0x19] == s[OM_H_AMD] &&
                   p->shadow[OMP_SHADOW_PMD] == s[OM_H_PMD] && p->shadow[0x14] == (s[OM_H_CSM] ? 0x81 : 0x00),
               "undo restores the song's start values");
        omm_destroy(m);
    }
}

static void test_audition(const char* dir) {
    printf("audition\n");
    OpmMachine* m = omm_create(RATE);
    const double t0 = now_us();
    EXPECT(omm_audition(m, &g_tone, 0, A4, T_SINE, 0) == OMM_OK, "A-4 on the sine instrument");
    const double setup = now_us() - t0;
    const OmPlayer* p = omm_player(m);
    float* buf;
    const int frames = render(m, 120, &buf);
    const double hz = zero_cross_hz(buf + 2 * (RATE / 10), frames - RATE / 10);
    EXPECT(fabs(hz - A4_HZ) < 0.5, "A-4 measured %.3f Hz (%.2f expected)", hz, A4_HZ);
    EXPECT(rms(buf + 2 * (frames - RATE / 2), RATE / 2) > 0.5 * rms(buf + 2 * (RATE / 2), RATE / 2), "held");
    int keyed = 0;
    for (int c = 0; c < OM_CHANNELS; ++c) keyed += p->ch[c].keyed;
    EXPECT(keyed == 1 && p->ch[0].keyed, "only FM 1 keyed (%d)", keyed);
    EXPECT(p->csm == 0 && p->shadow[0x14] == 0 && p->shadow[0x10] == (512 >> 2), "CSM forced off, timer A kept ($14=%02X)",
           p->shadow[0x14]);
    EXPECT(p->shadow[0x18] == 0xC0 && p->shadow[OMP_SHADOW_PMD] == 127, "header LFO kept");
    EXPECT(omm_release(m) == 1, "release phase");
    float* tail = (float*)malloc(sizeof(float) * 2 * OMM_MAX_TICK_FRAMES * 700);
    int tail_frames = 0, ticks = 0, n;
    while ((n = omm_tick(m, tail + 2 * tail_frames)) > 0 && ticks < 700) {
        tail_frames += n;
        ++ticks;
    }
    EXPECT(n == 0 && ticks > 2 && ticks < 400, "release ends when the carrier is quiet (%d ticks)", ticks);
    char path[512];
    snprintf(path, sizeof path, "%s/opm_tone.wav", dir);
    float* both = (float*)malloc(sizeof(float) * 2 * (size_t)(frames + tail_frames));
    memcpy(both, buf, sizeof(float) * 2 * (size_t)frames);
    memcpy(both + 2 * frames, tail, sizeof(float) * 2 * (size_t)tail_frames);
    write_wav(path, both, frames + tail_frames);
    printf("  A-4 %.3f Hz, release %d ticks, setup %.0f us\n", hz, ticks, setup);
    free(both);
    free(tail);
    free(buf);
    /* fine pitch: +64 units = +1 semitone */
    omm_audition(m, &g_tone, 4, A4, T_SINE, 64);
    const int f2 = render(m, 60, &buf);
    const double up = zero_cross_hz(buf + 2 * (RATE / 10), f2 - RATE / 10);
    const double want_up = A4_HZ * pow(2.0, 1.0 / 12.0);
    EXPECT(fabs(up - want_up) < 0.6, "fine +64 = A#4 %.3f Hz (%.2f expected)", up, want_up);
    free(buf);
    /* the noise slot plays on FM 8 only */
    EXPECT(omm_audition(m, &g_tone, 0, A4, T_NOISE, 0) == OMM_ERR_IMAGE, "noise instrument on FM 1 rejected");
    EXPECT(omm_audition(m, &g_tone, 6, A4, T_NOISE, 0) == OMM_ERR_IMAGE, "noise instrument on FM 7 rejected");
    EXPECT(omm_audition(m, &g_tone, 7, A4, T_NOISE, 0) == OMM_OK && p->shadow[0x0F] == (0x80 | 24),
           "noise instrument on FM 8: $0F = %02X", p->shadow[0x0F]);
    EXPECT(omm_audition(m, &g_tone, 7, A4, T_SINE, 0) == OMM_OK && !(p->shadow[0x0F] & 0x80), "a tonal note on FM 8: noise off");
    EXPECT(omm_audition(m, &g_tone, 0, A4, T_SINE, 0) == OMM_OK && omm_release(m) == 0, "release before any tick: cut");
    EXPECT(omm_audition(m, &g_tone, 0, A4, T_UNDEFINED, 0) == OMM_ERR_IMAGE, "undefined slot rejected");
    EXPECT(omm_audition(m, &g_tone, 0, A4, 0, 0) == OMM_ERR_IMAGE, "slot 0 rejected");
    EXPECT(omm_audition(m, &g_tone, 0, A4, 65, 0) == OMM_ERR_IMAGE, "slot 65 rejected");
    EXPECT(omm_audition(m, &g_tone, 0, OM_NOTE_MIN - 1, T_SINE, 0) == OMM_ERR_IMAGE, "note 2 rejected");
    EXPECT(omm_audition(m, &g_tone, 0, OM_NOTE_MAX + 1, T_SINE, 0) == OMM_ERR_IMAGE, "note 99 rejected");
    EXPECT(omm_audition(m, &g_tone, 0, OM_NOTE_MIN, T_SINE, 0) == OMM_OK && omm_audition(m, &g_tone, 0, OM_NOTE_MAX, T_SINE, 0) == OMM_OK,
           "notes 3 and 98 accepted");
    EXPECT(omm_audition(m, &g_tone, 8, A4, T_SINE, 0) == OMM_ERR_IMAGE, "channel 8 rejected");
    EXPECT(omm_audition(m, &g_tone, 0, A4, T_SINE, 200) == OMM_ERR_IMAGE, "fine 200 rejected");
    EXPECT(omm_audition(m, &g_tone, 0, A4, T_SINE, -129) == OMM_ERR_IMAGE, "fine -129 rejected");
    omm_destroy(m);
}

/* The chip's LFO: PMS 6 at PMD 127 bends the pitch (50 ms windows over 2 s spread widely); PMS 0 does not. */
static void test_lfo(void) {
    printf("LFO pitch modulation\n");
    OpmMachine* m = omm_create(RATE);
    double spread[2];
    for (int v = 0; v < 2; ++v) {
        omm_audition(m, &g_tone, 0, A4, v ? T_VIBRATO : T_SINE, 0);
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
    EXPECT(spread[0] < 1.5, "PMS 0: no pitch modulation (spread %.2f Hz)", spread[0]);
    EXPECT(spread[1] > 30.0, "PMS 6, PMD 127: the LFO bends the pitch (spread %.1f Hz)", spread[1]);
    printf("  50 ms pitch spread over 2 s: PMS 0 %.2f Hz, PMS 6 %.1f Hz\n", spread[0], spread[1]);
    omm_destroy(m);
}

/* FM 8's operator 4 as the noise generator (NFRQ 24): broadband against the sine. */
static void test_noise(void) {
    printf("noise on FM 8\n");
    OpmMachine* m = omm_create(RATE);
    double rate[2], rough[2], level[2];
    for (int v = 0; v < 2; ++v) {
        omm_audition(m, &g_tone, 7, A4, v ? T_NOISE : T_SINE, 0);
        float* buf;
        const int frames = render(m, 40, &buf);
        const int from = RATE / 10, n = frames - from;
        rate[v] = crossing_rate(buf + 2 * from, n);
        rough[v] = roughness(buf + 2 * from, n);
        level[v] = rms(buf + 2 * from, n);
        free(buf);
    }
    EXPECT(level[1] > 0.01, "noise audible (rms %.4f)", level[1]);
    EXPECT(rate[0] > 420 && rate[0] < 460 && rate[1] > 2000, "zero crossings: sine %.0f/s, noise %.0f/s", rate[0], rate[1]);
    EXPECT(rough[0] < 0.15 && rough[1] > 0.3, "spectral spread (difference/level): sine %.3f, noise %.3f", rough[0], rough[1]);
    printf("  sine %.0f crossings/s (%.3f), noise %.0f crossings/s (%.3f), noise rms %.4f\n", rate[0], rough[0], rate[1], rough[1],
           level[1]);
    omm_destroy(m);
}

/* CSM: timer A (NA 0: 1024 native samples) keys every channel on at each overflow; FM 1 holds a released A-4 (AR 31,
 * RR 15), so every overflow is a short burst. Rate measured from the bursts' onsets (1 ms loudness), 0.2-2 s. */
static void test_csm(void) {
    printf("CSM\n");
    OpmMachine* m = omm_create(RATE);
    double found[2] = {0, 0};
    int onsets[2] = {0, 0};
    double tail_level[2] = {0, 0};
    for (int v = 0; v < 2; ++v) {
        int mode;
        omm_start(m, v ? &g_csm : &g_csm_off, 0, 0, SEEK_BUDGET, &mode);
        float* buf;
        const int frames = render(m, 120, &buf);
        const int win = RATE / 1000, from = RATE / 5, wins = (frames - from) / win;
        double* e = (double*)malloc(sizeof(double) * (size_t)wins);
        double top = 0;
        for (int w = 0; w < wins; ++w) {
            e[w] = rms(buf + 2 * (from + w * win), win);
            if (e[w] > top) top = e[w];
        }
        tail_level[v] = rms(buf + 2 * from, frames - from);
        int count = 0, first = -1, last = -1;
        for (int w = 1; w < wins && top > 0; ++w) {
            if (e[w] >= 0.5 * top && e[w - 1] < 0.5 * top) {
                if (first < 0) first = w;
                last = w;
                ++count;
            }
        }
        onsets[v] = count;
        found[v] = count > 1 ? (count - 1) * 1000.0 / (last - first) : 0;
        free(e);
        free(buf);
    }
    const double want = (double)OMM_CLOCK / OMM_NATIVE_DIVIDER / 1024.0;
    EXPECT(tail_level[0] < 0.001, "CSM off: the released note stays silent (rms %.5f)", tail_level[0]);
    EXPECT(onsets[1] > 50 && fabs(found[1] - want) < 0.01 * want, "CSM on: %d bursts at %.2f Hz (62500 / 1024 = %.2f Hz)",
           onsets[1], found[1], want);
    printf("  CSM on: %d bursts, %.2f Hz; off: rms %.5f\n", onsets[1], found[1], tail_level[0]);
    omm_destroy(m);
}

static void test_mute(void) {
    printf("mute\n");
    OpmMachine* m = omm_create(RATE);
    int mode, bad = 0;
    omm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    omm_mute(m, 3, 1);
    omm_mute(m, 7, 1);
    for (int t = 0; t < OPM_REF_TICKS; ++t) {
        omm_tick(m, g_out);
        if (!same(omm_player(m), &REF_SONG, t)) ++bad;
    }
    EXPECT(bad == 0, "muted channels leave the player's writes unchanged (%d differ)", bad);
    omm_start(m, &g_song, 1, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 30; ++k) omm_tick(m, g_out);
    for (int c = 0; c < OM_CHANNELS; ++c) omm_mute(m, c, 1);
    float* buf;
    int frames = render(m, 30, &buf);
    const double quiet = rms(buf + 2 * (RATE / 10), frames - RATE / 10);
    EXPECT(quiet < 0.0005, "all channels muted: rms %.6f after 100 ms", quiet);
    free(buf);
    for (int c = 0; c < OM_CHANNELS; ++c) omm_mute(m, c, 0);
    frames = render(m, 30, &buf);
    EXPECT(rms(buf, frames) > 0.005, "unmuted: resumes mid-note (rms %.4f)", rms(buf, frames));
    free(buf);
    /* Muted channels keep computing (feedback included): after unmuting, the chip sounds as if never muted. Only the
     * resampler's high-pass history differs (it heard silence), which settles within 100 ms to under 1e-3. */
    {
        OpmMachine* a = omm_create(RATE);
        OpmMachine* b = omm_create(RATE);
        int ma, mb;
        omm_start(a, &g_song, 1, 0, SEEK_BUDGET, &ma);
        omm_start(b, &g_song, 1, 0, SEEK_BUDGET, &mb);
        for (int k = 0; k < 30; ++k) omm_tick(a, g_out), omm_tick(b, g_out);
        for (int c = 0; c < OM_CHANNELS; ++c) omm_mute(a, c, 1);
        for (int k = 0; k < 30; ++k) omm_tick(a, g_out), omm_tick(b, g_out);
        for (int c = 0; c < OM_CHANNELS; ++c) omm_mute(a, c, 0);
        int env_bad = 0;
        for (int c = 0; c < OM_CHANNELS; ++c)
            for (int op = 0; op < 4; ++op) env_bad += omm_envelope(a, c, op) != omm_envelope(b, c, op);
        float *x, *y;
        const int fx = render(a, 30, &x), fy = render(b, 30, &y);
        double worst = 0;
        for (int s = 2 * (RATE / 10); s < 2 * (fx < fy ? fx : fy); ++s) {
            const double d = fabs((double)x[s] - y[s]);
            if (d > worst) worst = d;
        }
        EXPECT(env_bad == 0 && fx == fy && worst < 1e-3,
               "unmuted = never muted: %d envelopes differ, largest difference %.6f after 100 ms", env_bad, worst);
        free(x);
        free(y);
        omm_destroy(a);
        omm_destroy(b);
    }
    omm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    for (int c = 0; c < OM_CHANNELS; ++c) omm_mute(m, c, 1);
    frames = render(m, 60, &buf);
    EXPECT(rms(buf, frames) < 0.0005, "muted before the first tick: rms %.6f", rms(buf, frames));
    free(buf);
    omm_start(m, &g_song, 1, 17, SEEK_BUDGET, &mode); /* right after a fully clocked seek */
    for (int c = 0; c < OM_CHANNELS; ++c) omm_mute(m, c, 1);
    frames = render(m, 60, &buf);
    EXPECT(rms(buf + 2 * (RATE / 10), frames - RATE / 10) < 0.0005, "muted right after a seek: rms %.6f after 100 ms",
           rms(buf + 2 * (RATE / 10), frames - RATE / 10));
    free(buf);
    omm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    omm_mute(m, 0, 1);
    omm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    frames = render(m, 120, &buf);
    EXPECT(rms(buf, frames) > 0.005, "start clears mutes (rms %.4f)", rms(buf, frames));
    free(buf);
    EXPECT(omm_mute(m, 8, 1) == OMM_ERR_IMAGE && omm_mute(m, -1, 1) == OMM_ERR_IMAGE, "channel 8 rejected");
    omm_destroy(m);
}

static void test_multispeed(void) {
    printf("multispeed\n");
    OpmMachine* m = omm_create(RATE);
    int mode, o, r, t, total = 0, frames = 0;
    omm_start(m, &g_x2, 0, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 120; ++k) {
        frames += omm_tick(m, g_out);
        total += omm_native_samples(m);
    }
    omm_position(m, &o, &r, &t);
    EXPECT(total == 62500, "120 ticks at x2 = 1 s of chip samples (%d)", total);
    EXPECT(frames >= RATE - 2 && frames <= RATE + 2, "= 1 s of host frames (%d)", frames);
    EXPECT(o == REF_SONG.pos[3 * 120] && r == REF_SONG.pos[3 * 120 + 1] && t == REF_SONG.pos[3 * 120 + 2],
           "x2: the song's rows in fast ticks: after 120 ticks at %d/%d/%d", o, r, t);
    omm_destroy(m);
}

static void test_vgm(const char* dir) {
    printf("VGM export\n");
    size_t cap = 1 << 21;
    uint8_t* buf = (uint8_t*)malloc(cap);
    static OmPlayer scratch;
    OmVgmInfo info;
    const double t0 = now_us();
    const size_t size = om_vgm_render(&g_song, 36000, &scratch, buf, cap, &info);
    const double us = now_us() - t0;
    EXPECT(size == OPM_REF_VGM_SIZE && fnv(0xCBF29CE484222325ull, buf, size) == OPM_REF_VGM_FNV,
           "VGM bytes = the reference player's (%zu vs %u bytes)", size, (unsigned)OPM_REF_VGM_SIZE);
    EXPECT(info.ticks == OPM_REF_VGM_TICKS && info.samples == OPM_REF_VGM_SAMPLES && info.loop_offset == OPM_REF_VGM_LOOP_OFFSET &&
               info.loop_samples == OPM_REF_VGM_LOOP_SAMPLES,
           "VGM length and loop (%u ticks, loop at %u, %u samples)", info.ticks, info.loop_offset, info.loop_samples);
    const uint32_t clock = buf[0x30] | buf[0x31] << 8 | buf[0x32] << 16 | (uint32_t)buf[0x33] << 24;
    EXPECT(!memcmp(buf, "Vgm ", 4) && buf[8] == 0x71 && buf[9] == 0x01 && clock == 4000000 && buf[0x34] == 0xCC,
           "header: VGM 1.71, YM2151 at %u Hz, data at 0x100", clock);
    EXPECT(om_vgm_render(&g_song, 36000, &scratch, buf, 1000, &info) == 0 && info.size == size,
           "too small a buffer reports 0 and the size needed");
    char path[512];
    snprintf(path, sizeof path, "%s/opm_song.vgm", dir);
    FILE* f = fopen(path, "wb");
    if (f) {
        fwrite(buf, 1, size, f);
        fclose(f);
    }
    snprintf(path, sizeof path, "%s/opm_song.bin", dir); /* the image, for tools/audio/opm/opm_reference_check.py */
    if ((f = fopen(path, "wb"))) {
        fwrite(g_song.bytes, 1, sizeof g_song.bytes, f);
        fclose(f);
    }
    printf("  %zu bytes, %u ticks, loop %u samples (%.2f s), rendered in %.0f us\n", size, (unsigned)OPM_REF_VGM_TICKS,
           (unsigned)OPM_REF_VGM_LOOP_SAMPLES, OPM_REF_VGM_LOOP_SAMPLES / 44100.0, us);
    free(buf);
}

static uint32_t le32(const uint8_t* p) { return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24; }

/* The exported VGM of build_loop played as a VGM player does, on ymfm's YM2151: from the data start to 0x66, then from
 * the loop offset to 0x66 again (two passes of the loop), 44.1 kHz waits converted to the chip's 62.5 kHz samples by
 * exact accumulation. FM 1's note is still keyed, decayed, at the song's end; the loop point's dump keys FM 1 off (it
 * was not keyed at the first arrival) and the loop row keys it on. The chip samples the key state once per sample, so
 * the second pass attacks again only if at least one sample separates the two: then its envelope K samples into the
 * loop equals the first pass's. The header keeps 44100 / 60 samples per tick and the waits add up to it. */
static void test_vgm_loop(void) {
    printf("VGM loop passes on the chip\n");
    enum { K = 32, TICKS = 96 };
    const size_t cap = 1 << 16;
    uint8_t* buf = (uint8_t*)malloc(cap);
    static OmPlayer scratch;
    OmVgmInfo info;
    const size_t size = om_vgm_render(&g_loop, 36000, &scratch, buf, cap, &info);
    const uint32_t samples = le32(buf + 0x18), loop_samples = le32(buf + 0x20);
    const size_t data = 0x34 + (size_t)le32(buf + 0x34), loop = 0x1C + (size_t)le32(buf + 0x1C);
    EXPECT(size && info.ticks == TICKS && samples == TICKS * 735u && loop_samples == samples && le32(buf + 0x1C) &&
               data <= loop && loop < size,
           "rendered, %u ticks, %u samples, loop %u samples (want %u each) at %zu", info.ticks, samples, loop_samples,
           TICKS * 735u, loop);
    if (!size || !le32(buf + 0x1C) || loop < data || loop >= size) {
        free(buf);
        return;
    }
    const size_t frames_cap = (size_t)(2ull * samples * 62500 / 44100) + 64;
    int* env = (int*)malloc(frames_cap * sizeof *env);
    YmOpm* chip = omc_create();
    size_t at = data, native = 0, entry[2] = {0, 0};
    uint64_t vgm_time = 0, waited = 0;
    int pass = 0, bad = 0;
    while (pass < 2) {
        if (at == loop && entry[pass] == 0) entry[pass] = native;
        if (at >= size) {
            bad = 1;
            break;
        }
        const int cmd = buf[at];
        uint32_t n;
        if (cmd == 0x54 && at + 2 < size) {
            omc_write(chip, buf[at + 1], buf[at + 2]);
            at += 3;
            continue;
        } else if (cmd == 0x61 && at + 2 < size) {
            n = buf[at + 1] | buf[at + 2] << 8;
            at += 3;
        } else if (cmd == 0x62) {
            n = 735;
            ++at;
        } else if ((cmd & 0xF0) == 0x70) {
            n = (uint32_t)(cmd & 15) + 1;
            ++at;
        } else if (cmd == 0x66) {
            ++pass;
            at = loop;
            continue;
        } else {
            bad = 1;
            break;
        }
        if (pass == 0) waited += n;
        vgm_time += n;
        const uint64_t until = vgm_time * 62500 / 44100;
        while (native < until && native < frames_cap) {
            int32_t l, r;
            omc_generate(chip, 0, &l, &r);
            env[native++] = omc_envelope(chip, 0, 3);
        }
    }
    omc_destroy(chip);
    EXPECT(!bad && pass == 2 && entry[1] > entry[0] && entry[1] + K < native,
           "the stream plays twice (command error %d at %zu, loop entered at samples %zu and %zu)", bad, at, entry[0], entry[1]);
    EXPECT(waited == samples, "the waits add up to the header's %u samples (%llu)", samples, (unsigned long long)waited);
    if (!bad && pass == 2 && entry[1] > entry[0] && entry[1] + K < native) {
        const int end = env[entry[1] - 1], first = env[entry[0] + K], second = env[entry[1] + K];
        EXPECT(end >= 0x100, "FM 1's held note has decayed by the song's end (attenuation %d of 1023)", end);
        EXPECT(abs(second - first) <= 8,
               "the loop row's note attacks again on pass 2: attenuation %d, %d samples into the loop (pass 1: %d, "
               "song end: %d)", second, K, first, end);
    }
    free(env);
    free(buf);
}

/* The hosts' entry point (opm_export.h): the reference VGM plus a GD3 tag. */
static void test_export(void) {
    printf("VGM export entry point\n");
    size_t size = 0;
    const char* problem = NULL;
    const char* title = "Thor \xC3\x96pening \xF0\x9F\x8E\xB9"; /* "Thor Öpening 🎹": 2-byte and 4-byte UTF-8 */
    uint8_t* vgm = om_export_vgm(g_song.bytes, sizeof g_song.bytes, 36000, title, "Esko", "2026/11/08", &size, &problem);
    EXPECT(vgm && !problem, "renders (%s)", problem ? problem : "ok");
    if (!vgm) return;
    const uint32_t gd3_at = 0x14 + (vgm[0x14] | vgm[0x15] << 8 | vgm[0x16] << 16 | (uint32_t)vgm[0x17] << 24);
    const uint32_t eof = 4 + (vgm[4] | vgm[5] << 8 | vgm[6] << 16 | (uint32_t)vgm[7] << 24);
    EXPECT(gd3_at == OPM_REF_VGM_SIZE && eof == size, "GD3 after the reference data (at %u), EOF offset = size %zu", gd3_at, size);
    uint8_t* body = (uint8_t*)malloc(OPM_REF_VGM_SIZE);
    memcpy(body, vgm, OPM_REF_VGM_SIZE);
    const uint32_t ref_eof = OPM_REF_VGM_SIZE - 4;
    memcpy(body + 4, &ref_eof, 4);
    memset(body + 0x14, 0, 4);
    EXPECT(fnv(0xCBF29CE484222325ull, body, OPM_REF_VGM_SIZE) == OPM_REF_VGM_FNV,
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
    EXPECT(!strcmp(fields[0], title) && !strcmp(fields[4], "OPM (Thor Tracker)") && !strcmp(fields[6], "Esko") &&
               !strcmp(fields[8], "2026/11/08") && !strcmp(fields[9], "Thor Tracker") && !fields[2][0] && !fields[10][0],
           "GD3 fields: '%s', '%s', '%s', '%s', '%s'", fields[0], fields[4], fields[6], fields[8], fields[9]);
    om_export_free(vgm);
    /* rejected images report the loader's reason */
    memcpy(g_bad.bytes, g_song.bytes, sizeof g_bad.bytes);
    g_bad.bytes[OM_H_MULTISPEED] = 9;
    vgm = om_export_vgm(g_bad.bytes, sizeof g_bad.bytes, 36000, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && size == 0 && problem && strstr(problem, "multispeed"), "bad image: NULL, '%s'", problem ? problem : "");
    vgm = om_export_vgm(g_song.bytes, 100, 36000, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && problem && !strcmp(problem, "image size"), "short image: '%s'", problem ? problem : "");
    vgm = om_export_vgm(g_song.bytes, sizeof g_song.bytes, 100, NULL, NULL, NULL, &size, &problem);
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
    om_export_free(vgm);
}

static void test_ops(void) {
    printf("ops table\n");
    const ChipEngineOps* ops = chip_engine_ops("ym2151-opm");
    EXPECT(ops && !strcmp(ops->system_id, "ym2151-opm"), "registry");
    if (!ops) return;
    EXPECT(ops->image_size == OM_IMAGE_SIZE && ops->image_storage == sizeof(OpmImage), "sizes");
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
    EXPECT(k >= 0 && k + 60 < OPM_REF_TICKS, "1/4 is in the reference");
    EXPECT(ops->start(m, image, 1, 4, SEEK_BUDGET, &mode) == CHIP_OK && mode == CHIP_SEEK_FROM_START, "start 1/4");
    ops->position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 4 && t == 0, "position 1/4/0");
    float out[CHIP_MAX_TICK_FRAMES * 2];
    int frames = 0, n = 0;
    for (int j = 0; j < 60; ++j) frames += (n = ops->tick(m, out));
    EXPECT(frames >= 48000 - 2 && frames <= 48000 + 2 && n >= 799 && n <= 801, "60 ticks = 1 s (%d frames)", frames);
    ops->position(m, &o, &r, &t);
    if (k >= 0 && k + 60 < OPM_REF_TICKS)
        EXPECT(o == REF_SONG.pos[3 * (k + 60)] && r == REF_SONG.pos[3 * (k + 60) + 1] && t == REF_SONG.pos[3 * (k + 60) + 2],
               "60 ticks later = straight play (%d/%d/%d)", o, r, t);
    ops->apply_image(m, image);
    EXPECT(ops->mute(m, 0, 1) == CHIP_OK && ops->mute(m, 0, 0) == CHIP_OK, "mute");
    EXPECT(ops->audition(m, tone, 0, A4, T_SINE, 0) == CHIP_OK, "audition");
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
    OpmMachine* m = omm_create(RATE);
    int mode;
    double t0 = now_us();
    omm_start(m, &g_song, 3, 20, SEEK_BUDGET, &mode);
    const double seek = now_us() - t0;
    t0 = now_us();
    OmPlayer* p = (OmPlayer*)malloc(sizeof *p);
    omp_reset(p, &g_song);
    for (int k = 0; k < 12000; ++k) omp_tick(p, &g_song);
    const double per = (now_us() - t0) / 12000;
    free(p);
    printf("  seek to 3/20: %.0f us; player alone %.2f us per tick (12000 ticks: %.1f ms)\n", seek, per, per * 12);
    omm_destroy(m);
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    EXPECT(load(&g_song, opm_song_image, sizeof opm_song_image) == 0, "song loads: %s", g_song.info.problem);
    EXPECT(load(&g_moved, opm_moved_image, sizeof opm_moved_image) == 0, "moved loads: %s", g_moved.info.problem);
    EXPECT(load(&g_edited, opm_edited_image, sizeof opm_edited_image) == 0, "edited loads: %s", g_edited.info.problem);
    EXPECT(load(&g_tempo, opm_tempo_image, sizeof opm_tempo_image) == 0, "tempo loads: %s", g_tempo.info.problem);
    EXPECT(load(&g_short, opm_short_image, sizeof opm_short_image) == 0, "short loads: %s", g_short.info.problem);
    EXPECT(load(&g_x2, opm_x2_image, sizeof opm_x2_image) == 0, "x2 loads: %s", g_x2.info.problem);
    EXPECT(load(&g_lfo, opm_lfo_image, sizeof opm_lfo_image) == 0, "lfo loads: %s", g_lfo.info.problem);
    build_tone();
    build_csm(&g_csm, 1);
    build_csm(&g_csm_off, 0);
    build_loop();
    test_key_codes();
    test_images();
    test_reference(dir);
    test_audio(dir);
    test_output_rates();
    measure_tick_cost();
    test_loop();
    test_seek();
    test_live_edits();
    test_audition(dir);
    test_lfo();
    test_noise();
    test_csm();
    test_mute();
    test_multispeed();
    test_vgm(dir);
    test_vgm_loop();
    test_export();
    test_ops();
    test_costs();
    printf("OPM: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
