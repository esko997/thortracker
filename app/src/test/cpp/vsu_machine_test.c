/* Host regression test for the Virtual Boy VSU system: Thor Tracker's own player (vsu/vsu_player.c) on the VSU core
 * (vsu/vsu_chip.c), playing the test song built in C (vsu_test_song.h).
 *
 * Checks: the chip against the Sacred Tech Scroll's facts (wave RAM writes ignored while any channel is active,
 * modulation RAM writes while channel 5 is; envelope steps every (n + 1) x 76800 clocks, grow, decay, the repeat
 * reloading the written value after one frame at the limit; auto length stopping at (n + 1) x 19200 clocks; the sweep
 * overflow stop, also with modification disabled, before a value > 2047 plays; modulation holding or wrapping after 32
 * values; S5INT keeping the current frequency, SxFQL setting both; the noise register's sequence lengths 32767, 1953,
 * 254, 217, 73, 63, 42, 28 and its reset on S6INT/S6EV1; SSTOP; the mixer's amplitude formula and maximum 685) and
 * chunk invariance; image validation (corruptions each rejected with its reason, boundaries accepted); the pitch table
 * (A-4 = F 1693); the song's coverage and deterministic non-silent output; the A-4 pitch measured from the digital output
 * (440.14 Hz) and the noise rate; seeks leaving the chip exactly as straight play and the output within a tolerance,
 * and the order fallback; mute (chip state identical, other channels' output unchanged, unmuting resumes mid-note);
 * live edits (same image, re-laid-out image, note/tempo edits, song cut, an instrument edit by slot with an identical
 * twin slot, a wave edit heard at once, a modulation edit); audition and release; the VGM parsed back against the
 * machine's writes with its loop point at two consecutive equal loop arrivals; the hosts' entry point; the ops table.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir]
 * Writes <dir>/vsu_song.bin (the test image), vsu_trace.txt (`R:` the reset batch, then 1500 ticks of
 * `T tick order row tickInRow:` lines with ` RRR=VV` writes), vsu_out.txt (`clock left right` at every change of the
 * 10-bit digital output during the first 300 ticks), vsu_chip_cases.txt (`CASE name clocks`, `W clock reg val`,
 * `O clock left right activeMask` at every change of a 120-clock sample, `END`), vsu_song.vgm (no GD3), vsu_song.wav
 * (30 s) and vsu_tone.wav (A-4 + release): the inputs of tools/audio/vsu_reference_check.py. Thresholds marked
 * "unverified" are first guesses until the integrator runs this. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "blip_buf.h"
#include "chip_engine.h"
#include "vsu_chip.h"
#include "vsu_export.h"
#include "vsu_machine.h"
#include "vsu_test_song.h"
#include "vsu_vgm.h"

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
#define TICK_FRAMES 960 /* 100000 VSU clocks at 48 kHz */
#define SEEK_BUDGET 12000
#define TRACE_TICKS 1500
#define OUT_TICKS 300
#define VGM_TICKS 36000
#define A4 57
#define TICK VBP_TICK_CLOCKS
#define FNV_BASIS 0xCBF29CE484222325ull

static float g_out[VBM_MAX_TICK_FRAMES * 2];
static VsuImage g_song, g_moved, g_edited, g_tempo, g_short, g_inst, g_wave, g_modedit, g_drop1, g_bad;

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

static void write_wav(const char* dir, const char* name, const float* data, long frames) {
    char path[512];
    snprintf(path, sizeof path, "%s/%s", dir, name);
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
static long render(VsuMachine* m, int ticks, float** buf) {
    float* out = (float*)malloc((size_t)(ticks + 1) * VBM_MAX_TICK_FRAMES * 2 * sizeof(float));
    long frames = 0;
    for (int t = 0; t < ticks; ++t) {
        const int n = vbm_tick(m, out + 2 * frames);
        if (n <= 0) break;
        frames += n;
    }
    *buf = out;
    return frames;
}

static void build(VsuImage* image, VbSongOptions opt) {
    vb_build_test_song(image->bytes, &opt);
    const int rc = vb_load_image(image);
    EXPECT(rc == 0, "test image loads: %s at %d", image->info.problem ? image->info.problem : "", image->info.problem_offset);
}

/* ---- the chip ------------------------------------------------------------------------------------------------- */

typedef struct CW {
    uint32_t at; /* the write acts after this clock's events */
    uint16_t reg;
    uint8_t val;
} CW;

typedef struct Case {
    const char* name;
    uint32_t clocks;
    int n;
    CW w[512];
} Case;

static void cw(Case* k, uint32_t at, int reg, int val) { k->w[k->n++] = (CW){at, (uint16_t)reg, (uint8_t)val}; }

/* Channel c sounding at frequency f: LRV FF, FQL, FQH, EV0, EV1, RAM (wave channels), then INT. */
static void tone(Case* k, uint32_t at, int c, int f, int ev0, int ev1, int ram, int intv) {
    cw(k, at, VSU_REG(c, VSU_LRV), 0xFF);
    cw(k, at, VSU_REG(c, VSU_FQL), f & 0xFF);
    cw(k, at, VSU_REG(c, VSU_FQH), f >> 8);
    cw(k, at, VSU_REG(c, VSU_EV0), ev0);
    cw(k, at, VSU_REG(c, VSU_EV1), ev1);
    if (c < 5) cw(k, at, VSU_REG(c, VSU_RAM), ram);
    cw(k, at, VSU_REG(c, VSU_INT), intv);
}

static void case_init(Case* k, const char* name, uint32_t clocks) {
    k->name = name;
    k->clocks = clocks;
    k->n = 0;
}

/* The chip after the case's writes up to clock `until` (writes at `until` included). */
static void case_state(const Case* k, uint32_t until, VsuChip* chip) {
    vsu_reset(chip);
    uint32_t now = 0;
    for (int i = 0; i < k->n && k->w[i].at <= until; ++i) {
        vsu_run(chip, k->w[i].at - now);
        now = k->w[i].at;
        vsu_write(chip, k->w[i].reg, k->w[i].val);
    }
    vsu_run(chip, until - now);
}

/* The case into the cases file: its writes, then every change of the 120-clock samples (a sample at clock t comes
 * after that clock's events and before its writes) and of the active mask. */
static void case_emit(FILE* f, const Case* k) {
    if (!f) return;
    fprintf(f, "CASE %s %u\n", k->name, k->clocks);
    for (int i = 0; i < k->n; ++i) fprintf(f, "W %u %03X %02X\n", k->w[i].at, k->w[i].reg, k->w[i].val);
    static VsuChip chip;
    vsu_reset(&chip);
    uint32_t now = 0;
    int i = 0, pl = 0, pr = 0;
    unsigned pm = 0;
    for (uint32_t t = VSU_SAMPLE_CLOCKS; t <= k->clocks; t += VSU_SAMPLE_CLOCKS) {
        for (; i < k->n && k->w[i].at < t; ++i) {
            vsu_run(&chip, k->w[i].at - now);
            now = k->w[i].at;
            vsu_write(&chip, k->w[i].reg, k->w[i].val);
        }
        vsu_run(&chip, t - now);
        now = t;
        int l, r;
        vsu_sample(&chip, 0, &l, &r);
        const unsigned mask = vsu_active_mask(&chip);
        if (l != pl || r != pr || mask != pm) fprintf(f, "O %u %d %d %u\n", t, l, r, mask);
        pl = l, pr = r, pm = mask;
    }
    fprintf(f, "END\n");
}

static int env_at(const Case* k, uint32_t at, int c) {
    static VsuChip chip;
    case_state(k, at, &chip);
    return chip.ch[c].env;
}

static int active_at(const Case* k, uint32_t at, int c) {
    static VsuChip chip;
    case_state(k, at, &chip);
    return chip.ch[c].active;
}

static int cur_at(const Case* k, uint32_t at, int c) {
    static VsuChip chip;
    case_state(k, at, &chip);
    return chip.ch[c].cur;
}

/* The noise register's period for a tap: steps of 10 clocks (F = 2047), the first recurrence of step 1000's state. */
static long noise_period(int tap) {
    static VsuChip chip;
    vsu_reset(&chip);
    vsu_write(&chip, VSU_REG(5, VSU_FQL), 0xFF);
    vsu_write(&chip, VSU_REG(5, VSU_FQH), 0x07);
    vsu_write(&chip, VSU_REG(5, VSU_EV1), tap << 4);
    vsu_write(&chip, VSU_REG(5, VSU_INT), 0x80);
    vsu_run(&chip, 10 * 1000);
    const uint16_t s = chip.lfsr;
    for (long n = 1; n <= 40000; ++n) {
        vsu_run(&chip, 10);
        if (chip.lfsr == s) return n;
    }
    return -1;
}

static uint32_t lcg(uint32_t* s) {
    *s = *s * 1664525u + 1013904223u;
    return *s >> 8;
}

static void test_chip(const char* dir) {
    printf("chip\n");
    char path[512];
    snprintf(path, sizeof path, "%s/vsu_chip_cases.txt", dir);
    FILE* f = fopen(path, "w");
    static Case k;
    static VsuChip chip;

    /* RAM locks (amendment 1). */
    case_init(&k, "ram-locks", 20000);
    cw(&k, 0, VSU_WAVE(0, 0), 10);
    cw(&k, 0, VSU_WAVE(0, 1), 70); /* 6 bits kept */
    tone(&k, 0, 0, 1800, 0xF0, 0, 0, 0x80);
    cw(&k, 1000, VSU_WAVE(0, 2), 20); /* channel 1 active: ignored */
    cw(&k, 1000, VSU_MOD(0), 5);      /* channel 5 inactive: accepted */
    tone(&k, 2000, 4, 1800, 0xF0, 0, 0, 0x80);
    cw(&k, 3000, VSU_MOD(1), 7);      /* channel 5 active: ignored */
    cw(&k, 5000, VSU_SSTOP, 1);
    cw(&k, 6000, VSU_WAVE(0, 2), 20); /* all stopped: accepted */
    cw(&k, 6000, VSU_MOD(1), 7);
    case_emit(f, &k);
    case_state(&k, 4000, &chip);
    EXPECT(chip.wave[0][0] == 10 && chip.wave[0][1] == 6 && chip.wave[0][2] == 0 && chip.mod[0] == 5 && chip.mod[1] == 0,
           "wave RAM writes ignored while a channel plays, modulation RAM while channel 5 plays (%d %d %d, %d %d)",
           chip.wave[0][0], chip.wave[0][1], chip.wave[0][2], chip.mod[0], chip.mod[1]);
    case_state(&k, 6000, &chip);
    EXPECT(chip.wave[0][2] == 20 && chip.mod[1] == 7 && vsu_active_mask(&chip) == 0, "accepted after SSTOP stopped every channel");

    /* Envelope timing (n + 1) x 76800 clocks, directions and repeat (amendment 2). */
    case_init(&k, "env-decay", 500000);
    tone(&k, 0, 0, 1024, 0xF2, 0x01, 0, 0x80);
    case_emit(f, &k);
    EXPECT(env_at(&k, 230399, 0) == 15 && env_at(&k, 230400, 0) == 14 && env_at(&k, 460799, 0) == 14 &&
               env_at(&k, 460800, 0) == 13,
           "decay, interval 2: a step every 3 x 76800 clocks");
    case_init(&k, "env-grow", 400000);
    tone(&k, 0, 1, 1024, 0x0B, 0x01, 0, 0x80);
    case_emit(f, &k);
    EXPECT(env_at(&k, 307199, 1) == 0 && env_at(&k, 307200, 1) == 1, "grow, interval 3: the first step at 4 x 76800 clocks");
    case_init(&k, "env-decay-repeat", 400000);
    tone(&k, 0, 2, 1024, 0x20, 0x03, 0, 0x80);
    case_emit(f, &k);
    EXPECT(env_at(&k, 76800, 2) == 1 && env_at(&k, 153600, 2) == 0 && env_at(&k, 230399, 2) == 0 && env_at(&k, 230400, 2) == 2 &&
               env_at(&k, 307200, 2) == 1,
           "decay-repeat: one frame at 0, then the written value 2 again (no wrap to 15)");
    case_init(&k, "env-grow-repeat", 300000);
    tone(&k, 0, 3, 1024, 0xE8, 0x03, 0, 0x80);
    case_emit(f, &k);
    EXPECT(env_at(&k, 76800, 3) == 15 && env_at(&k, 153600, 3) == 14 && env_at(&k, 230400, 3) == 15,
           "grow-repeat: one frame at 15, then the written value 14 again");
    case_init(&k, "env-no-repeat", 300000);
    tone(&k, 0, 0, 1024, 0x10, 0x01, 0, 0x80);
    case_emit(f, &k);
    EXPECT(env_at(&k, 76800, 0) == 0 && env_at(&k, 153600, 0) == 0 && env_at(&k, 230400, 0) == 0, "decay without repeat stays at 0");
    case_init(&k, "env-disabled", 300000);
    tone(&k, 0, 0, 1024, 0xF0, 0x00, 0, 0x80);
    cw(&k, 100000, VSU_REG(0, VSU_EV0), 0x70);
    case_emit(f, &k);
    EXPECT(env_at(&k, 99999, 0) == 15 && env_at(&k, 100000, 0) == 7 && env_at(&k, 300000, 0) == 7,
           "envelope off: the level holds; an EV0 write sets it");

    /* Auto length: (n + 1) x 19200 clocks. */
    case_init(&k, "auto-length", 700000);
    tone(&k, 0, 0, 1024, 0xF0, 0, 0, 0xA4);
    tone(&k, 0, 1, 1024, 0xF0, 0, 0, 0xBF);
    tone(&k, 0, 2, 1024, 0xF0, 0, 0, 0x9F); /* interval without Auto: plays on */
    case_emit(f, &k);
    EXPECT(active_at(&k, 95999, 0) && !active_at(&k, 96000, 0), "auto length 4: stops at 5 x 19200 = 96000 clocks");
    EXPECT(active_at(&k, 614399, 1) && !active_at(&k, 614400, 1), "auto length 31: stops at 32 x 19200 = 614400 clocks");
    EXPECT(active_at(&k, 700000, 2), "without Auto the interval is ignored");

    /* Sweep (amendment 5): a frame's value is computed when the frame starts, so the channel stops as soon as the
     * next value would exceed 2047 — the last value applied never plays (Tech Scroll: 510, 1020, then 2040 is
     * skipped because 4080 overflows). */
    {
        case_init(&k, "sweep-up", 120000);
        cw(&k, 0, VSU_REG(4, VSU_SWP), 0x2C); /* interval 2, fast, up, shift 4 */
        tone(&k, 0, 4, 1451, 0xF0, 0x40, 4, 0x80);
        case_emit(f, &k);
        int cur = 1451, last = cur;
        uint32_t stop = 0;
        for (int j = 2; j < 100 && !stop; j += 2) {
            cur += cur >> 4; /* the frame ending at effects tick j applies its value */
            if (cur + (cur >> 4) > 2047) stop = 4800u * (uint32_t)j; /* the next frame overflows: stopped before cur plays */
            else last = cur;
        }
        EXPECT(stop == 48000, "sweep 1451 up by >> 4 every 2 effects ticks stops at %u clocks (expected 48000)", stop);
        EXPECT(active_at(&k, stop - 1, 4) && !active_at(&k, stop, 4) && cur_at(&k, stop - 1, 4) == last && last == 1847,
               "stopped when the next frame's value overflows, the last played value %d", last);
        EXPECT(cur_at(&k, 9599, 4) == 1451 && cur_at(&k, 9600, 4) == 1541, "the first frame ends after 2 x 4800 clocks: 1541");
    }
    case_init(&k, "sweep-bug", 20000);
    cw(&k, 0, VSU_REG(4, VSU_SWP), 0x08); /* up, shift 0, interval 0: no modification */
    tone(&k, 0, 4, 1100, 0xF0, 0x00, 4, 0x80);
    case_emit(f, &k);
    EXPECT(!active_at(&k, 0, 4) && !active_at(&k, 4800, 4),
           "sweep overflow stops channel 5 at its start with modification disabled (the hardware bug)");
    case_init(&k, "sweep-bug-safe", 200000);
    cw(&k, 0, VSU_REG(4, VSU_SWP), 0x08);
    tone(&k, 0, 4, 1000, 0xF0, 0x00, 4, 0x80);
    case_emit(f, &k);
    EXPECT(active_at(&k, 200000, 4) && cur_at(&k, 200000, 4) == 1000, "below the overflow it plays on unmodified");
    case_init(&k, "sweep-early", 40000);
    cw(&k, 0, VSU_REG(4, VSU_SWP), 0x49); /* interval 4, up, shift 1 */
    tone(&k, 0, 4, 1000, 0xF0, 0x40, 4, 0x80);
    case_emit(f, &k);
    EXPECT(active_at(&k, 19199, 4) && cur_at(&k, 19199, 4) == 1000 && !active_at(&k, 19200, 4),
           "1000 plays for 4 ticks; 1500 is applied at the 4th but never plays (2250 would overflow)");
    case_init(&k, "sweep-interval-change", 60000);
    cw(&k, 0, VSU_REG(4, VSU_SWP), 0x79); /* interval 7, up, shift 1 */
    tone(&k, 0, 4, 600, 0xF0, 0x40, 4, 0x80);
    cw(&k, 10000, VSU_REG(4, VSU_SWP), 0x39); /* 2 ticks elapsed: interval 3 takes effect at once */
    cw(&k, 40000, VSU_REG(4, VSU_SWP), 0x19); /* the frame since 28800 has run 2 ticks (33600, 38400): interval 1 is too short */
    case_emit(f, &k);
    EXPECT(cur_at(&k, 14399, 4) == 600 && cur_at(&k, 14400, 4) == 900, "a shorter interval written mid-frame takes effect at once");
    EXPECT(cur_at(&k, 28800, 4) == 1350 && cur_at(&k, 43199, 4) == 1350 && cur_at(&k, 43200, 4) == 2025,
           "an interval shorter than the frame's elapsed time: the frame ends at its old interval");

    /* Modulation (amendment 6): hold after 32 values without repeat, wrap with it; & 0x7FF. */
    for (int rep = 0; rep < 2; ++rep) {
        case_init(&k, rep ? "mod-wrap" : "mod-hold", 4800 * 40);
        for (int i = 0; i < 32; ++i) cw(&k, 0, VSU_MOD(i), i + 1);
        cw(&k, 0, VSU_REG(4, VSU_SWP), 0x10); /* interval 1, fast */
        tone(&k, 0, 4, 1000, 0xF0, rep ? 0x70 : 0x50, 4, 0x80);
        case_emit(f, &k);
        EXPECT(cur_at(&k, 4799, 4) == 1000 && cur_at(&k, 4800, 4) == 1001 && cur_at(&k, 4800 * 32, 4) == 1032,
               "modulation: written frequency + table value per frame");
        if (rep)
            EXPECT(cur_at(&k, 4800 * 33, 4) == 1001 && cur_at(&k, 4800 * 34, 4) == 1002, "with repeat it wraps to the first value");
        else
            EXPECT(cur_at(&k, 4800 * 33, 4) == 1032 && cur_at(&k, 4800 * 40, 4) == 1032, "without repeat it stays at the last value");
    }
    case_init(&k, "mod-negative", 20000);
    cw(&k, 0, VSU_MOD(0), 0xF6); /* -10 */
    cw(&k, 0, VSU_REG(4, VSU_SWP), 0x10);
    tone(&k, 0, 4, 5, 0xF0, 0x50, 4, 0x80);
    case_emit(f, &k);
    EXPECT(cur_at(&k, 4800, 4) == 2043, "(5 - 10) & 0x7FF = 2043");

    /* S5INT keeps the current frequency; SxFQL sets both (amendment 3). */
    case_init(&k, "int-keeps-current", 20000);
    cw(&k, 0, VSU_REG(4, VSU_SWP), 0x19); /* interval 1, up, shift 1 */
    tone(&k, 0, 4, 1000, 0xF0, 0x40, 4, 0x80);
    cw(&k, 5000, VSU_REG(4, VSU_INT), 0x80);
    cw(&k, 6000, VSU_REG(4, VSU_FQL), 0x20);
    case_emit(f, &k);
    case_state(&k, 5000, &chip);
    EXPECT(chip.ch[4].cur == 1500 && chip.ch[4].freq == 1000, "S5INT keeps the swept current frequency (%d)", chip.ch[4].cur);
    case_state(&k, 6000, &chip);
    EXPECT(chip.ch[4].cur == 0x520 && chip.ch[4].freq == 0x320, "S5FQL sets the low bits of both (%03X %03X)", chip.ch[4].cur,
           chip.ch[4].freq);
    EXPECT(cur_at(&k, 9800, 4) == 1968 && active_at(&k, 14599, 4) && !active_at(&k, 14600, 4),
           "the restarted frame: 1968 at 9800, stopped at 14600");

    /* Noise: sequence lengths per tap, reset to 0 on S6INT/S6EV1 (amendment 4). */
    static const long periods[8] = {32767, 1953, 254, 217, 73, 63, 42, 28};
    long got[8];
    int all = 1;
    for (int t = 0; t < 8; ++t) all &= (got[t] = noise_period(t)) == periods[t];
    EXPECT(all, "noise sequence lengths per tap: %ld %ld %ld %ld %ld %ld %ld %ld", got[0], got[1], got[2], got[3], got[4], got[5],
           got[6], got[7]);
    for (int t = 0; t < 8; ++t) {
        static char names[8][16];
        snprintf(names[t], sizeof names[t], "noise-tap-%d", t);
        case_init(&k, names[t], 60000);
        tone(&k, 0, 5, 2047 - 3 * t, 0xF0, t << 4, 0, 0x80);
        cw(&k, 30000, VSU_REG(5, VSU_EV1), t << 4);
        case_emit(f, &k);
    }
    case_init(&k, "noise-reset", 70000);
    tone(&k, 0, 5, 2000, 0xF0, 0x10, 0, 0x80);
    cw(&k, 50000, VSU_REG(5, VSU_EV1), 0x20);
    cw(&k, 60000, VSU_REG(5, VSU_INT), 0x80);
    case_emit(f, &k);
    case_state(&k, 49999, &chip);
    const int ran = chip.lfsr != 0;
    case_state(&k, 50000, &chip);
    const int ev1_reset = chip.lfsr == 0;
    case_state(&k, 59999, &chip);
    const int ran2 = chip.lfsr != 0;
    case_state(&k, 60000, &chip);
    EXPECT(ran && ev1_reset && ran2 && chip.lfsr == 0, "the noise register restarts at 0 on S6EV1 and S6INT writes");

    /* Mixer: amplitude and the maximum (amendment 8). */
    case_init(&k, "mixer-max", 20000);
    for (int w = 0; w < 5; ++w)
        for (int i = 0; i < 32; ++i) cw(&k, 0, VSU_WAVE(w, i), 63);
    for (int c = 0; c < 5; ++c) tone(&k, 0, c, 1000 + c, 0xF0, 0, c, 0x80);
    tone(&k, 0, 5, 2047, 0xF0, 0, 0, 0x80);
    case_emit(f, &k);
    {
        int max = 0, min = 1 << 20;
        for (uint32_t t = 120; t <= 20000; t += 120) {
            case_state(&k, t, &chip);
            int l, r;
            vsu_sample(&chip, 0, &l, &r);
            if (l > max) max = l;
            if (l < min) min = l;
            EXPECT(l == r, "both sides alike at %u", t);
        }
        EXPECT(max == VSU_MAX_OUTPUT && max == (6 * 63 * 29) >> 4 && min == (5 * 63 * 29) >> 4,
               "six full channels: %d (max 685), noise low %d", max, min);
        case_state(&k, 20000, &chip);
        int l, r;
        vsu_sample(&chip, 0x3E, &l, &r);
        EXPECT(l == (63 * 29) >> 4, "one channel at full level: 63 x 29 >> 4 = %d", l);
    }
    case_init(&k, "amplitude", 5000);
    cw(&k, 0, VSU_WAVE(0, 0), 63);
    cw(&k, 0, VSU_WAVE(0, 1), 63);
    tone(&k, 0, 0, 0, 0x10, 0, 0, 0x80);
    cw(&k, 0, VSU_REG(0, VSU_LRV), 0x1F);
    tone(&k, 0, 1, 0, 0xF0, 0, 5, 0x80); /* wave index 5: silent */
    case_emit(f, &k);
    case_state(&k, 100, &chip);
    {
        int l, r;
        vsu_sample(&chip, 0, &l, &r);
        EXPECT(l == ((63 * 1) >> 4) && r == ((63 * 2) >> 4) && chip.ch[1].active,
               "amplitude (level x envelope) >> 3, + 1 when not 0: %d %d; wave index 5 plays silence", l, r);
    }

    /* SSTOP. */
    case_init(&k, "sstop", 60000);
    for (int c = 0; c < 6; ++c) tone(&k, 0, c, 1500, 0xA0, 0, c % 5, 0x80);
    cw(&k, 30000, VSU_SSTOP, 0);
    cw(&k, 40000, VSU_SSTOP, 1);
    case_emit(f, &k);
    case_state(&k, 39999, &chip);
    const unsigned before = vsu_active_mask(&chip);
    case_state(&k, 40000, &chip);
    EXPECT(before == 0x3F && vsu_active_mask(&chip) == 0, "SSTOP 0 does nothing, SSTOP 1 stops every channel");

    /* Chunk invariance: a busy scenario in one run = in random chunks = in 120-clock chunks. */
    case_init(&k, "busy", 2000000);
    for (int i = 0; i < 32; ++i) cw(&k, 0, VSU_MOD(i), (uint8_t)(int8_t)((i % 8) - 4));
    for (int w = 0; w < 5; ++w)
        for (int i = 0; i < 32; ++i) cw(&k, 0, VSU_WAVE(w, i), (i * (w + 3)) & 63);
    cw(&k, 0, VSU_REG(4, VSU_SWP), 0x92);
    tone(&k, 0, 0, 1700, 0xF1, 0x03, 0, 0x80);
    tone(&k, 0, 1, 1200, 0x3C, 0x03, 1, 0x80);
    tone(&k, 0, 2, 2040, 0xC0, 0x01, 2, 0xA9);
    tone(&k, 0, 3, 300, 0x80, 0x00, 3, 0x80);
    tone(&k, 0, 4, 900, 0xF2, 0x71, 4, 0x80);
    tone(&k, 0, 5, 1990, 0xF3, 0x33, 0, 0x80);
    cw(&k, 333333, VSU_REG(4, VSU_SWP), 0x1B);
    cw(&k, 333333, VSU_REG(4, VSU_EV1), 0x41);
    cw(&k, 700001, VSU_REG(5, VSU_EV1), 0x61);
    cw(&k, 900000, VSU_REG(1, VSU_FQL), 0x10);
    cw(&k, 1200007, VSU_REG(2, VSU_INT), 0xA3);
    cw(&k, 1500000, VSU_REG(3, VSU_FQH), 0x06);
    case_emit(f, &k);
    {
        static VsuChip one, many, grid;
        case_state(&k, k.clocks, &one);
        uint32_t seed = 12345;
        for (int mode = 0; mode < 2; ++mode) {
            VsuChip* c = mode ? &grid : &many;
            vsu_reset(c);
            uint32_t now = 0;
            int i = 0;
            while (now < k.clocks) {
                const uint32_t next_write = i < k.n ? k.w[i].at : k.clocks;
                if (i < k.n && k.w[i].at == now) {
                    vsu_write(c, k.w[i].reg, k.w[i].val);
                    ++i;
                    continue;
                }
                uint32_t step = mode ? VSU_SAMPLE_CLOCKS - now % VSU_SAMPLE_CLOCKS : 1 + lcg(&seed) % 30000;
                if (now + step > next_write) step = next_write - now;
                vsu_run(c, step);
                now += step;
            }
            for (; i < k.n; ++i) vsu_write(c, k.w[i].reg, k.w[i].val);
        }
        EXPECT(vsu_equal(&one, &many) && vsu_equal(&one, &grid),
               "chunk invariance: 2 000 000 clocks in one run = in random chunks = in 120-clock chunks (every field)");
        EXPECT(one.ch[2].active == 0 && one.ch[0].active, "the busy case's auto-length channel stopped, others play");
    }
    if (f) fclose(f);
}

/* ---- images ---------------------------------------------------------------------------------------------------- */

/* Image offset of the byte starting row `row` of (order, lane), or -1 inside a run. */
static int row_offset(const VsuImage* image, int order, int lane, int row) {
    VbCursor c = {vb_pattern(image->bytes, order, lane), 0};
    VbRow x;
    for (int r = 0; r < row; ++r) vb_next_row(image->bytes, &c, &x);
    return c.run ? -1 : VB_OFF_DATA + c.offset;
}

static void corrupt(int offset, int value, const char* want) {
    memcpy(&g_bad, &g_song, sizeof g_bad);
    g_bad.bytes[offset] = (uint8_t)value;
    const int rc = vb_load_image(&g_bad);
    EXPECT(rc != 0 && g_bad.info.problem && strstr(g_bad.info.problem, want) && g_bad.info.loaded != VB_LOADED,
           "byte %d = %d rejected: want '%s', got '%s' at %d", offset, value, want, g_bad.info.problem ? g_bad.info.problem : "(none)",
           g_bad.info.problem_offset);
}

static void accept(int offset, int value, const char* what) {
    memcpy(&g_bad, &g_song, sizeof g_bad);
    g_bad.bytes[offset] = (uint8_t)value;
    EXPECT(vb_load_image(&g_bad) == 0, "%s accepted (%s)", what, g_bad.info.problem ? g_bad.info.problem : "");
}

static int inst_at(int slot, int field) { return VB_OFF_INSTRUMENTS + VB_INSTRUMENT_SIZE * (slot - 1) + field; }

static void test_images(void) {
    printf("images\n");
    const VbInfo* in = &g_song.info;
    EXPECT(in->loaded == VB_LOADED && in->orders == 4 && in->loop == 1 && in->speed == 6 && in->instruments == VBT_INSTRUMENTS &&
               in->rows[0] == 16 && in->rows[1] == 32 && in->rows[3] == 24,
           "derived info");
    EXPECT(in->start_speed[0] == 6 && in->start_speed[1] == 6 && in->start_speed[2] == 6 && in->start_speed[3] == 6,
           "start speeds 6 6 6 6 (F05 is in the last order)");
    EXPECT(g_tempo.info.start_speed[1] == 6 && g_tempo.info.start_speed[2] == 9 && g_tempo.info.start_speed[3] == 9,
           "F09 in order 1: orders 2 and 3 start at speed 9");
    EXPECT(vb_pattern(g_song.bytes, 0, 3) == vb_pattern(g_song.bytes, 2, 3), "byte-identical patterns are shared");
    EXPECT(vb_pattern(g_drop1.bytes, 0, 1) == vb_pattern(g_drop1.bytes, 2, 1), "empty 16-row lanes share one run pattern");
    /* header */
    corrupt(0, 'X', "magic");
    corrupt(VB_H_LAYOUT, 2, "layout");
    corrupt(VB_H_SPEED, 0, "speed");
    corrupt(VB_H_ORDERS, 0, "order count");
    corrupt(VB_H_ORDERS + 1, 1, "order count"); /* 260 */
    corrupt(VB_H_LOOP, 4, "loop order");
    corrupt(VB_H_INSTRUMENTS, 65, "instrument count");
    corrupt(VB_H_ZERO, 1, "header byte 11");
    corrupt(VB_H_DATA_SIZE + 1, 0xF0, "data size above");
    corrupt(20, 1, "header reserved");
    memcpy(&g_bad, &g_song, sizeof g_bad);
    g_bad.bytes[VB_H_DATA_SIZE] = g_bad.bytes[VB_H_DATA_SIZE + 1] = 0;
    EXPECT(vb_load_image(&g_bad) && strstr(g_bad.info.problem, "pattern offset past the data size") &&
               g_bad.info.problem_offset == VB_OFF_ORDERS,
           "data size 0: the first pattern offset is past it");
    corrupt(VB_OFF_ROWS + 4, 8, "rows of an order past");
    corrupt(VB_OFF_ORDERS + 2 * VB_LANES * 4, 1, "patterns of an order past");
    /* instruments */
    corrupt(inst_at(VBT_INSTRUMENTS + 1, VB_I_FLAGS), 0x80, "past the instrument count");
    corrupt(inst_at(1, VB_I_FLAGS), 0x90, "instrument flags");
    corrupt(inst_at(1, VB_I_FLAGS), 0x01, "instrument flags");
    corrupt(inst_at(1, VB_I_LEVEL), 16, "level above 15");
    corrupt(inst_at(1, VB_I_INTERVAL), 8, "interval above 7");
    corrupt(inst_at(1, VB_I_LENGTH), 32, "length above 31");
    corrupt(inst_at(1, VB_I_WAVE), 5, "wave above 4");
    corrupt(inst_at(1, VB_I_TAP), 8, "tap above 7");
    corrupt(inst_at(1, VB_I_FREQMOD), 0x08, "frequency modification");
    corrupt(inst_at(VBT_BASS, VB_I_SWEEP_START), 97, "-96..96");
    corrupt(inst_at(VBT_BASS, VB_I_SWEEP_END), (uint8_t)-97, "-96..96");
    corrupt(inst_at(1, VB_I_SWEEP_START), 3, "without a sweep speed");
    corrupt(inst_at(1, 11), 1, "reserved bytes 11..15");
    corrupt(inst_at(1, 15), 1, "reserved bytes 11..15");
    accept(inst_at(1, VB_I_LEVEL), 15, "level 15");
    accept(inst_at(1, VB_I_LENGTH), 31, "length 31");
    accept(inst_at(VBT_BASS, VB_I_SWEEP_START), 96, "sweep start 96");
    accept(inst_at(VBT_SWEEP5, VB_I_SWP), 0xFF, "any S5SWP byte");
    accept(VB_H_INSTRUMENTS, 64, "instrument count 64 with empty records above");
    /* waves */
    corrupt(VB_OFF_WAVES + 5, 64, "wave sample above 63");
    accept(VB_OFF_WAVES + 159, 63, "sample 63");
    /* rows */
    const int lead = row_offset(&g_song, 0, 0, 0); /* C-4 LEAD */
    const int hat = row_offset(&g_song, 0, 5, 0);  /* C-6 HAT */
    const int arp = row_offset(&g_song, 0, 0, 2);  /* 047 */
    const int tap = row_offset(&g_song, 0, 5, 6);  /* N06 */
    const int swp = row_offset(&g_song, 0, 4, 12); /* M20 */
    EXPECT(lead >= 0 && hat >= 0 && arp >= 0 && tap >= 0 && swp >= 0 && g_song.bytes[lead] == 0x03 && g_song.bytes[arp] == 0x04,
           "rows located");
    corrupt(lead + 1, 26, "below D#2");
    accept(lead + 1, 27, "D#2 on a wave lane");
    accept(lead + 1, 95, "B-7 on a wave lane");
    corrupt(lead + 1, 96, "note byte");
    corrupt(hat + 1, 96, "note byte");
    accept(hat + 1, 0, "C-0 on the noise lane");
    accept(hat + 1, VB_NOTE_OFF, "OFF");
    corrupt(lead + 2, 20, "does not define");
    corrupt(lead + 2, 0, "does not define");
    corrupt(arp, 0x0C, "row mask byte");
    corrupt(arp, 0x14, "row mask byte");
    corrupt(arp, 0x00, "row mask byte");
    corrupt(arp + 1, 36, "above Z");
    corrupt(arp + 1, 10, "not declared");
    corrupt(arp + 1, 5, "not declared");
    memcpy(&g_bad, &g_song, sizeof g_bad);
    struct {
        int code, param;
        const char* want; /* NULL = accepted */
    } fx[] = {
        {VB_FX_LEVEL, 0x10, "Cxx"}, {VB_FX_LEVEL, 0x0F, NULL}, {VB_FX_SPEED, 0, "F00"},    {VB_FX_SPEED, 0xFF, NULL},
        {VB_FX_LENGTH, 0x21, "Lxx"}, {VB_FX_LENGTH, 0x20, NULL}, {VB_FX_SWEEPMOD, 0, "Wave 5"}, {VB_FX_TAP, 0, "Noise"},
        {VB_FX_WAVE, 0, "Wxx"},     {VB_FX_WAVE, 6, "Wxx"},     {VB_FX_WAVE, 1, NULL},     {VB_FX_WAVE, 5, NULL},
        {VB_FX_JUMP, 4, "Bxx"},     {VB_FX_JUMP, 3, NULL},     {VB_FX_BREAK, 0xFF, NULL}, {VB_FX_DELAY, 0xFF, NULL},
    };
    for (size_t i = 0; i < sizeof fx / sizeof fx[0]; ++i) {
        memcpy(&g_bad, &g_song, sizeof g_bad);
        g_bad.bytes[arp + 1] = (uint8_t)fx[i].code;
        g_bad.bytes[arp + 2] = (uint8_t)fx[i].param;
        const int rc = vb_load_image(&g_bad);
        if (fx[i].want)
            EXPECT(rc && g_bad.info.problem && strstr(g_bad.info.problem, fx[i].want) && g_bad.info.problem_offset == arp + 1,
                   "effect %d %02X on Wave 1 rejected at its code byte: '%s'", fx[i].code, fx[i].param,
                   g_bad.info.problem ? g_bad.info.problem : "(none)");
        else
            EXPECT(rc == 0, "effect %d %02X on Wave 1 accepted (%s)", fx[i].code, fx[i].param, g_bad.info.problem ? g_bad.info.problem : "");
    }
    corrupt(tap + 2, 8, "Nxx tap above 07");
    accept(tap + 2, 7, "N07");
    memcpy(&g_bad, &g_song, sizeof g_bad);
    g_bad.bytes[tap + 1] = VB_FX_WAVE;
    g_bad.bytes[tap + 2] = 1;
    EXPECT(vb_load_image(&g_bad) && strstr(g_bad.info.problem, "only on wave lanes"), "Wxx on Noise rejected");
    memcpy(&g_bad, &g_song, sizeof g_bad);
    g_bad.bytes[tap + 1] = VB_FX_SWEEPMOD;
    EXPECT(vb_load_image(&g_bad) && strstr(g_bad.info.problem, "Wave 5"), "Mxx on Noise rejected");
    accept(swp + 2, 0xFF, "Mxx FF on Wave 5");
    /* the data size just short of the last pattern byte */
    memcpy(&g_bad, &g_song, sizeof g_bad);
    g_bad.bytes[VB_H_DATA_SIZE] = (uint8_t)(vb_u16(g_song.bytes + VB_H_DATA_SIZE) - 1);
    g_bad.bytes[VB_H_DATA_SIZE + 1] = (uint8_t)((vb_u16(g_song.bytes + VB_H_DATA_SIZE) - 1) >> 8);
    EXPECT(vb_load_image(&g_bad) && strstr(g_bad.info.problem, "data size"), "data size one short: '%s'",
           g_bad.info.problem ? g_bad.info.problem : "(none)");
}

/* ---- pitch ----------------------------------------------------------------------------------------------------- */

static void test_pitch_table(void) {
    printf("pitch table\n");
    EXPECT(vbp_period_q(0) == 626238494u && vbp_period_q(767) == 313401976u && vbp_period_q(576) == 372363636u,
           "VSU_PERIOD_Q ends and A-4's entry");
    EXPECT(vbp_frequency(0, A4 * 64) == 1693, "A-4 = F 1693 (got %d)", vbp_frequency(0, A4 * 64));
    EXPECT(vbp_frequency(0, 0) == vbp_frequency(0, VBP_WAVE_PITCH_MIN) && vbp_frequency(0, VBP_WAVE_PITCH_MIN) >= 0 &&
               vbp_frequency(0, VBP_WAVE_PITCH_MIN - 64) == vbp_frequency(0, VBP_WAVE_PITCH_MIN),
           "wave pitch clamped at D#2 (F %d)", vbp_frequency(0, VBP_WAVE_PITCH_MIN));
    int mono = 1, nmono = 1;
    for (int p = VBP_WAVE_PITCH_MIN + 1; p <= VBP_PITCH_MAX; ++p) mono &= vbp_frequency(0, p) >= vbp_frequency(0, p - 1);
    for (int p = 1; p <= VBP_PITCH_MAX; ++p) nmono &= vbp_frequency(5, p) >= vbp_frequency(5, p - 1);
    EXPECT(mono && nmono && vbp_frequency(0, VBP_PITCH_MAX) <= 2047 && vbp_frequency(5, VBP_PITCH_MAX) <= 2047 &&
               vbp_frequency(5, 0) >= 0,
           "frequency values rise with pitch and stay in 0..2047");
    /* every wave note within 20 cents of its true pitch (expected: -19.7 cents at B-7) */
    double worst = 0;
    for (int n = VB_WAVE_NOTE_MIN; n < VB_NOTES; ++n) {
        const double hz = 5e6 / (32.0 * (2048 - vbp_frequency(0, n * 64)));
        const double want = 440.0 * pow(2.0, (n - A4) / 12.0);
        const double cents = 1200.0 * log2(hz / want);
        if (fabs(cents) > fabs(worst)) worst = cents;
    }
    EXPECT(fabs(worst) < 20.0, "worst wave note error %.2f cents (expected -19.7 at B-7)", worst);
    const int fn = vbp_frequency(5, 48 * 64);
    EXPECT(fn == 2048 - 60, "noise C-4: N = 60 (F %d)", fn);
}

/* ---- the song ------------------------------------------------------------------------------------------------- */

typedef struct Trace {
    FILE* f;
    FILE* out;
    uint64_t out_until;
    int pl, pr;
    long changes;
    int max_writes;
    uint64_t hash;
    uint8_t reg_seen[VSU_REGISTERS];
    uint8_t taps;      /* bit t: EV1 of the noise channel written with tap t */
    long ticks;
    uint64_t* tick_hash; /* per tick, may be NULL */
    int* samples;        /* digital samples (left, right) of every tick, may be NULL */
    long nsamples, cap;
} Trace;

static void trace_tick(void* ctx, int order, int row, int tick, uint64_t clock, const VbWrite* w, int n) {
    Trace* t = (Trace*)ctx;
    (void)clock;
    if (t->f) {
        fprintf(t->f, "T %ld %d %d %d:", t->ticks, order, row, tick);
        for (int i = 0; i < n; ++i) fprintf(t->f, " %03X=%02X", w[i].reg, w[i].val);
        fprintf(t->f, "\n");
    }
    uint64_t h = fnv(FNV_BASIS, &order, sizeof order);
    h = fnv(fnv(h, &row, sizeof row), &tick, sizeof tick);
    for (int i = 0; i < n; ++i) {
        h = fnv(fnv(h, &w[i].reg, sizeof w[i].reg), &w[i].val, 1);
        t->reg_seen[w[i].reg] = 1;
        if (w[i].reg == VSU_REG(5, VSU_EV1)) t->taps |= (uint8_t)(1u << ((w[i].val >> 4) & 7));
    }
    if (t->tick_hash) t->tick_hash[t->ticks] = h;
    t->hash = fnv(t->hash, &h, sizeof h);
    if (n > t->max_writes) t->max_writes = n;
    ++t->ticks;
}

static void trace_sample(void* ctx, uint64_t clock, int left, int right) {
    Trace* t = (Trace*)ctx;
    if (t->out && clock <= t->out_until && (left != t->pl || right != t->pr)) {
        fprintf(t->out, "%llu %d %d\n", (unsigned long long)clock, left, right);
        ++t->changes;
    }
    t->pl = left, t->pr = right;
    if (t->samples && t->nsamples + 2 <= t->cap) {
        t->samples[t->nsamples++] = left;
        t->samples[t->nsamples++] = right;
    }
}

static void trace_init(Trace* t, long sample_ticks) {
    memset(t, 0, sizeof *t);
    t->hash = FNV_BASIS;
    if (sample_ticks) {
        t->cap = 2 * (sample_ticks + 1) * 834;
        t->samples = (int*)malloc(sizeof(int) * (size_t)t->cap);
    }
}

static void test_trace(const char* dir) {
    printf("trace\n");
    char path[512];
    Trace t;
    trace_init(&t, 0);
    snprintf(path, sizeof path, "%s/vsu_trace.txt", dir);
    t.f = fopen(path, "w");
    snprintf(path, sizeof path, "%s/vsu_out.txt", dir);
    t.out = fopen(path, "w");
    t.out_until = (uint64_t)OUT_TICKS * TICK;
    if (t.out) fprintf(t.out, "# 10-bit digital output (clock left right) at every change, ticks 0..%d\n", OUT_TICKS - 1);
    VsuMachine* m = vbm_create(RATE);
    int mode;
    EXPECT(vbm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode) == VBM_OK && mode == VBM_SEEK_FROM_START, "start");
    const VbPlayer* p = vbm_player(m);
    EXPECT(p->writes >= 1 && p->write[0].reg == VSU_SSTOP && p->write[0].val == 1 && p->writes <= 1 + 160 + 32,
           "the reset batch: SSTOP first, at most 193 writes (%d)", p->writes);
    if (t.f) {
        fprintf(t.f, "R:");
        for (int i = 0; i < p->writes; ++i) fprintf(t.f, " %03X=%02X", p->write[i].reg, p->write[i].val);
        fprintf(t.f, "\n");
    }
    vbm_set_trace(m, trace_tick, trace_sample, &t);
    int clocks_ok = 1, frames_ok = 1, hw_stop = 0, wave5_mod = 0;
    for (int k = 0; k < TRACE_TICKS; ++k) {
        const uint64_t before = vbm_clock(m);
        frames_ok &= vbm_tick(m, g_out) == TICK_FRAMES;
        clocks_ok &= vbm_clock(m) == before + TICK;
        const VsuChip* chip = vbm_chip(m);
        if (p->lane[4].keyed && !chip->ch[4].active) hw_stop = 1;
        if (chip->ch[4].active && chip->ch[4].cur != chip->ch[4].freq) wave5_mod = 1;
    }
    vbm_set_trace(m, NULL, NULL, NULL);
    if (t.f) fclose(t.f);
    if (t.out) fclose(t.out);
    EXPECT(clocks_ok && frames_ok, "every tick = 100000 VSU clocks = %d frames at 48 kHz", TICK_FRAMES);
    EXPECT(t.max_writes < 80, "at most %d writes per tick (expected below 80; unverified)", t.max_writes);
    int regs = 1;
    for (int c = 0; c < 6; ++c)
        for (int r = 0; r <= VSU_SWP; ++r) {
            if ((r == VSU_RAM && c == 5) || (r == VSU_SWP && c != 4)) continue;
            regs &= t.reg_seen[VSU_REG(c, r)];
        }
    EXPECT(regs, "every channel register written");
    EXPECT(t.taps == 0xFF, "every noise tap written (mask %02X)", t.taps);
    EXPECT(hw_stop, "Wave 5's hardware sweep stops the channel by itself while the lane is keyed");
    EXPECT(wave5_mod, "the hardware sweep/modulation moves channel 5's current frequency");
    EXPECT(t.changes > 1000, "%ld digital output changes in %d ticks", t.changes, OUT_TICKS);
    printf("  %d ticks, at most %d writes per tick, %ld output changes in the first %d ticks\n", TRACE_TICKS, t.max_writes,
           t.changes, OUT_TICKS);
    vbm_destroy(m);
    write_file(dir, "vsu_song.bin", g_song.bytes, sizeof g_song.bytes);
}

static void test_audio(const char* dir) {
    printf("audio\n");
    const int ticks = 1500;
    uint64_t hash[2] = {0, 0};
    for (int pass = 0; pass < 2; ++pass) {
        VsuMachine* m = vbm_create(RATE);
        int mode;
        vbm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
        float* buf;
        const double t0 = now_us();
        const long frames = render(m, ticks, &buf);
        const double us = (now_us() - t0) / ticks;
        hash[pass] = fnv(FNV_BASIS, buf, (size_t)frames * 2 * sizeof(float));
        if (pass == 0) {
            const double r = rms(buf, frames), pk = peak(buf, frames);
            EXPECT(frames == (long)ticks * TICK_FRAMES, "1500 ticks = 30 s = %ld frames", frames);
            EXPECT(r > 0.00125, "non-silent (rms %.4f; includes 1/8 host headroom gain)", r);
            EXPECT(pk < 1.0, "below full scale (peak %.4f; unverified)", pk);
            int quiet = 0;
            for (long s = 0; s + RATE <= frames; s += RATE)
                if (rms(buf + 2 * s, RATE) < 0.00025) ++quiet;
            EXPECT(quiet == 0, "every second non-silent (%d quiet)", quiet);
            int stereo = 0;
            for (long i = 0; i < frames; ++i) stereo |= buf[2 * i] != buf[2 * i + 1];
            EXPECT(stereo, "left and right differ somewhere (8xy)");
            write_wav(dir, "vsu_song.wav", buf, frames);
            printf("  30 s: rms %.4f, peak %.4f, %.1f us per tick (%.0fx realtime)\n", r, pk, us, 20000.0 / us);
        }
        free(buf);
        vbm_destroy(m);
    }
    EXPECT(hash[0] == hash[1], "bit-identical on a second start");
}

/* Representation oracle, not an independent chip model: consume the untouched raw diagnostic samples at scale 1.
 * Its worst-case signal is <2740, so neither its FIR history nor int16 conversion can hide production clipping. */
typedef struct HeadroomReference {
    blip_t* blip[2];
    double y[2], x[2];
    int last[2], maximum, valid;
    uint64_t start;
} HeadroomReference;

static void headroom_sample(void* ctx, uint64_t clock, int left, int right) {
    HeadroomReference* r = (HeadroomReference*)ctx;
    const int sample[2] = {left, right};
    const double alpha = VBM_RC / (VBM_RC + (double)VSU_SAMPLE_CLOCKS / VSU_CLOCK);
    for (int k = 0; k < 2; ++k) {
        r->valid &= sample[k] >= 0 && sample[k] <= 685;
        if (sample[k] > r->maximum) r->maximum = sample[k];
        r->y[k] = alpha * (r->y[k] + sample[k] - r->x[k]);
        r->x[k] = sample[k];
        const int v = (int)lround(r->y[k]);
        blip_add_delta(r->blip[k], (unsigned)(clock - r->start), v - r->last[k]);
        r->last[k] = v;
    }
}

/* Legal image: six max-level channels, sustained DC or arbitrary wave/noise plus hardware modulation; OFF and
 * re-key after long filter history. All lanes share a pattern; the noise lane accepts the same note as wave lanes. */
static void headroom_image(VsuImage* image, int shaped) {
    uint8_t* b = image->bytes;
    memset(image, 0, sizeof *image);
    memcpy(b, "TTVB", 4);
    b[VB_H_LAYOUT] = VB_LAYOUT;
    b[VB_H_SPEED] = 1;
    vbt_u16(b + VB_H_ORDERS, 1);
    b[VB_H_INSTRUMENTS] = 1;
    b[VB_OFF_ROWS] = 64;
    vbt_instrument(b, 1, 0, 15, 0, 0, 0, 0,
                   shaped ? VB_FM_ON | VB_FM_MODULATION | VB_FM_REPEAT : 0,
                   shaped ? 0x10 : 0, 0, 0, 0);
    for (int i = 0; i < 32; ++i) {
        b[VB_OFF_WAVES + i] = (uint8_t)(shaped ? ((i * 37 + 19) & 63) : 63);
        b[VB_OFF_MOD + i] = (uint8_t)(i & 1 ? 127 : 128);
    }
    VbtOut o = {.b = b};
    vbt_put(&o, 3); vbt_put(&o, 27); vbt_put(&o, 1); /* row 0 */
    vbt_put(&o, 0x80 | 30);                         /* rows 1..31 */
    vbt_put(&o, 1); vbt_put(&o, VB_NOTE_OFF);        /* row 32 */
    vbt_put(&o, 0x80 | 2);                          /* rows 33..35 */
    vbt_put(&o, 3); vbt_put(&o, 95); vbt_put(&o, 1); /* row 36 */
    vbt_put(&o, 0x80 | 26);                         /* rows 37..63 */
    vbt_u16(b + VB_H_DATA_SIZE, o.at);
    EXPECT(vb_load_image(image) == 0, "headroom image loads");
}

static void test_headroom(void) {
    printf("headroom (raw trace -> low-gain representation oracle)\n");
    static const unsigned rates[] = {8000, 11025, 22050, 32000, 44100, 48000, 96000};
    static VsuImage image, edited;
    for (unsigned ri = 0; ri < sizeof rates / sizeof rates[0]; ++ri)
        for (int shaped = 0; shaped < 2; ++shaped) {
            headroom_image(&image, shaped);
            edited = image;
            memset(edited.bytes + VB_OFF_WAVES, 0, 32);
            EXPECT(vb_load_image(&edited) == 0, "DC-to-zero edit loads");
            VsuMachine* m = vbm_create(rates[ri]);
            HeadroomReference ref = {.valid = 1};
            for (int k = 0; k < 2; ++k) {
                ref.blip[k] = blip_new(VBM_MAX_TICK_FRAMES + 64);
                blip_set_rates(ref.blip[k], VSU_CLOCK, rates[ri]);
            }
            int mode, frames_ok = 1;
            EXPECT(vbm_start(m, &image, 0, 0, SEEK_BUDGET, &mode) == VBM_OK, "headroom starts");
            vbm_set_trace(m, NULL, headroom_sample, &ref);
            double worst = 0, maximum = 0;
            for (int tick = 0; tick < 64; ++tick) {
                if (tick == 12 || tick == 16)
                    for (int c = 0; c < 6; ++c) vbm_mute(m, c, tick == 12);
                if (tick == 20 || tick == 24) vbm_apply_image(m, tick == 20 ? &edited : &image);
                ref.start = vbm_clock(m);
                const int frames = vbm_tick(m, g_out);
                short low[2 * VBM_MAX_TICK_FRAMES];
                for (int k = 0; k < 2; ++k) {
                    blip_end_frame(ref.blip[k], TICK);
                    frames_ok &= blip_read_samples(ref.blip[k], low + k, VBM_MAX_TICK_FRAMES, 1) == frames;
                }
                for (int i = 0; i < frames * 2; ++i) {
                    const double expected = low[i] * (VBM_STEP_SCALE / (double)VBM_FLOAT_SCALE);
                    const double error = fabs(g_out[i] - expected);
                    if (error > worst) worst = error;
                    if (fabs(g_out[i]) > maximum) maximum = fabs(g_out[i]);
                }
            }
            EXPECT(frames_ok && ref.valid, "%u Hz shape %d: sample/frame contracts", rates[ri], shaped);
            EXPECT(worst < 0.003, "%u Hz shape %d: no hidden blip clipping (error %.6f)", rates[ri], shaped, worst);
            EXPECT(maximum < 0.976, "%u Hz shape %d: host output headroom (peak %.6f)", rates[ri], shaped, maximum);
            if (!shaped) EXPECT(ref.maximum == 685, "%u Hz: all-six maximum reached (%d)", rates[ri], ref.maximum);
            for (int k = 0; k < 2; ++k) blip_delete(ref.blip[k]);
            vbm_destroy(m);
        }
}

/* Pin the supported software timing, not the unresolved hardware editor's note about a 5-10 ms zero gate. */
static void test_note_on_timing(void) {
    VsuChip chip;
    vsu_reset(&chip);
    for (int i = 0; i < 32; ++i) vsu_write(&chip, (uint16_t)i, 63);
    vsu_write(&chip, VSU_REG(0, VSU_LRV), 0xFF);
    vsu_write(&chip, VSU_REG(0, VSU_EV0), 0xF0);
    vsu_write(&chip, VSU_REG(0, VSU_EV1), 1);
    vsu_write(&chip, VSU_REG(0, VSU_INT), 0x80);
    int l, r;
    vsu_sample(&chip, 0, &l, &r);
    EXPECT(l == 114 && r == 114, "envelope-enabled wave audible immediately in raw core (no guessed zero gate)");
    vsu_run(&chip, 76799);
    EXPECT(chip.ch[0].env == 15, "first envelope interval holds initial value through clock 76799");
    vsu_run(&chip, 1);
    EXPECT(chip.ch[0].env == 14, "first envelope step exactly at clock 76800 (15.36 ms)");
}

/* ---- seek ------------------------------------------------------------------------------------------------------ */

static void test_seek(void) {
    printf("seek\n");
    enum { N = 1100 };
    VsuMachine* m = vbm_create(RATE);
    int mode;
    vbm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    float* all = (float*)malloc(sizeof(float) * 2 * TICK_FRAMES * (N + 1));
    VsuChip* chips = (VsuChip*)malloc(sizeof(VsuChip) * (N + 1));
    VbPlayer* players = (VbPlayer*)malloc(sizeof(VbPlayer) * (N + 1));
    static int pos[N + 1][3];
    for (int t = 0; t <= N; ++t) {
        chips[t] = *vbm_chip(m);
        players[t] = *vbm_player(m);
        vbm_position(m, &pos[t][0], &pos[t][1], &pos[t][2]);
        if (t < N) vbm_tick(m, all + 2 * (size_t)TICK_FRAMES * t);
    }
    static const int targets[][2] = {{0, 5}, {0, 15}, {1, 0}, {1, 17}, {2, 3}, {2, 12}, {3, 2}, {3, 20}};
    for (size_t i = 0; i < sizeof targets / sizeof targets[0]; ++i) {
        const int o = targets[i][0], r = targets[i][1];
        int k = -1;
        for (int t = 0; t <= N - 60 && k < 0; ++t)
            if (pos[t][0] == o && pos[t][1] == r && pos[t][2] == 0) k = t;
        EXPECT(k >= 0, "target %d/%d reached by straight play within %d ticks", o, r, N - 60);
        if (k < 0) continue;
        const double t0 = now_us();
        EXPECT(vbm_start(m, &g_song, o, r, SEEK_BUDGET, &mode) == VBM_OK && mode == VBM_SEEK_FROM_START, "seek %d/%d", o, r);
        const double us = now_us() - t0;
        int po, pr, pt;
        vbm_position(m, &po, &pr, &pt);
        EXPECT(po == o && pr == r && pt == 0, "lands on %d/%d tick 0", o, r);
        EXPECT(vsu_equal(vbm_chip(m), &chips[k]) && vbm_clock(m) == (uint64_t)k * TICK,
               "seek %d/%d: the chip state is straight play's exactly (every field)", o, r);
        EXPECT(vbp_state_equal(vbm_player(m), &players[k]), "seek %d/%d: the player state is straight play's", o, r);
        float* y;
        const long fy = render(m, 60, &y);
        double worst = 0;
        for (long s = 0; s < 2 * fy; ++s) {
            const double d = fabs((double)y[s] - all[2 * (size_t)TICK_FRAMES * k + s]);
            if (d > worst) worst = d;
        }
        EXPECT(fy == 60 * TICK_FRAMES && worst < 2e-3, "seek %d/%d: 60 ticks of output within 2e-3 of straight play (%.2e; unverified)",
               o, r, worst);
        EXPECT(vsu_equal(vbm_chip(m), &chips[k + 60]), "seek %d/%d: still straight play's chip 60 ticks later", o, r);
        free(y);
        printf("  %d/%d: %d ticks fully clocked in %.0f us, output difference %.2e\n", o, r, k, us, worst);
    }
    /* Order 3 rows 0-1 are only reached by the order fallback (order 2 breaks to row 2). */
    EXPECT(vbm_start(m, &g_song, 3, 0, SEEK_BUDGET, &mode) == VBM_OK && mode == VBM_SEEK_FROM_ORDER, "3/0: order fallback");
    int o, r, t;
    vbm_position(m, &o, &r, &t);
    EXPECT(o == 3 && r == 0 && t == 0, "fallback lands on 3/0 (%d/%d/%d)", o, r, t);
    for (int j = 0; j < 12; ++j) vbm_tick(m, g_out);
    vbm_position(m, &o, &r, &t);
    EXPECT(o == 3 && r == 2 && t == 0, "rows 0-1 at the order's start speed 6 (%d/%d/%d)", o, r, t);
    for (int j = 0; j < 5; ++j) vbm_tick(m, g_out);
    vbm_position(m, &o, &r, &t);
    EXPECT(o == 3 && r == 3 && t == 0, "then F05 (%d/%d/%d)", o, r, t);
    EXPECT(vbm_start(m, &g_song, 1, 17, 10, &mode) == VBM_OK && mode == VBM_SEEK_FROM_ORDER, "small budget: fallback");
    vbm_position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 17 && t == 0, "budget fallback lands on 1/17");
    EXPECT(vbm_start(m, &g_song, 4, 0, SEEK_BUDGET, &mode) == VBM_ERR_IMAGE, "order past the song rejected");
    EXPECT(vbm_start(m, &g_song, 0, 16, SEEK_BUDGET, &mode) == VBM_ERR_IMAGE, "row past the order rejected");
    {
        const double t0 = now_us();
        vbm_start(m, &g_song, 3, 20, SEEK_BUDGET, &mode);
        printf("  seek to 3/20: %.0f us\n", now_us() - t0);
    }
    free(all);
    free(chips);
    free(players);
    vbm_destroy(m);
}

/* ---- live edits ------------------------------------------------------------------------------------------------ */

/* Per-tick write hashes of straight play of `image` with alt0 applied before tick `from` (or, with `every`, alt0 and
 * alt1 alternately before every tick from `from`). */
static uint64_t* tick_hashes(const VsuImage* image, const VsuImage* alt0, const VsuImage* alt1, int from, int every) {
    uint64_t* h = (uint64_t*)calloc(TRACE_TICKS, sizeof(uint64_t));
    Trace t;
    trace_init(&t, 0);
    t.tick_hash = h;
    VsuMachine* m = vbm_create(RATE);
    int mode;
    vbm_start(m, image, 0, 0, SEEK_BUDGET, &mode);
    vbm_set_trace(m, trace_tick, NULL, &t);
    for (int k = 0; k < TRACE_TICKS; ++k) {
        if (alt0 && (every ? k >= from : k == from)) vbm_apply_image(m, (every && (k & 1)) ? alt1 : alt0);
        vbm_tick(m, g_out);
    }
    vbm_destroy(m);
    return h;
}

static int count_differences(const uint64_t* a, const uint64_t* b, int from, int* first) {
    int bad = 0;
    *first = -1;
    for (int i = from; i < TRACE_TICKS; ++i)
        if (a[i] != b[i]) {
            if (*first < 0) *first = i;
            ++bad;
        }
    return bad;
}

static int positions_until(const VsuImage* image, int order, int row, int nth) {
    VsuMachine* m = vbm_create(RATE);
    int mode, o, r, t, k = -1, seen = 0;
    vbm_start(m, image, 0, 0, SEEK_BUDGET, &mode);
    for (int i = 0; i < TRACE_TICKS; ++i) {
        vbm_position(m, &o, &r, &t);
        if (o == order && r == row && t == 0 && ++seen == nth) {
            k = i;
            break;
        }
        vbm_tick(m, g_out);
    }
    vbm_destroy(m);
    return k;
}

/* Ticks between the next two row starts. */
static int row_length(VsuMachine* m) {
    int o, r, t, n = 0;
    do {
        vbm_tick(m, g_out);
        vbm_position(m, &o, &r, &t);
    } while (t != 0);
    do {
        vbm_tick(m, g_out);
        ++n;
        vbm_position(m, &o, &r, &t);
    } while (t != 0);
    return n;
}

/* The EV0 values written to channel c in tick k of straight play of `image` with `edit` applied before tick `at`. */
static int ev0_written(const VsuImage* image, const VsuImage* edit, int at, int k, int c) {
    VsuMachine* m = vbm_create(RATE);
    int mode, v = -1;
    vbm_start(m, image, 0, 0, SEEK_BUDGET, &mode);
    for (int i = 0; i <= k; ++i) {
        if (edit && i == at) vbm_apply_image(m, edit);
        vbm_tick(m, g_out);
    }
    const VbPlayer* p = vbm_player(m);
    for (int i = 0; i < p->writes; ++i)
        if (p->write[i].reg == VSU_REG(c, VSU_EV0)) v = p->write[i].val;
    vbm_destroy(m);
    return v;
}

static void test_live_edits(void) {
    printf("live edits\n");
    uint64_t* song = tick_hashes(&g_song, NULL, NULL, 0, 0);
    int first;
    uint64_t* h = tick_hashes(&g_song, &g_song, &g_song, 0, 1);
    int bad = count_differences(song, h, 0, &first);
    EXPECT(bad == 0, "same image applied before every tick: = straight play (%d differ, first %d)", bad, first);
    free(h);
    h = tick_hashes(&g_song, &g_moved, &g_song, 0, 1);
    bad = count_differences(song, h, 0, &first);
    EXPECT(bad == 0, "re-laid-out image and the original alternately before every tick: = straight play (%d differ, first %d)", bad,
           first);
    free(h);
    {
        VsuMachine* m = vbm_create(RATE);
        int mode;
        vbm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
        double worst = 0, total = 0;
        for (int k = 0; k < TRACE_TICKS; ++k) {
            const double t0 = now_us();
            vbm_apply_image(m, (k & 1) ? &g_moved : &g_song);
            const double us = now_us() - t0;
            total += us;
            if (us > worst) worst = us;
            vbm_tick(m, g_out);
        }
        printf("  apply_image: %.2f us mean, worst %.1f us\n", total / TRACE_TICKS, worst);
        vbm_destroy(m);
    }
    /* A note edit in order 1 row 16 (lane 0), applied at ticks before that row: straight play of the edited song. */
    uint64_t* edited = tick_hashes(&g_edited, NULL, NULL, 0, 0);
    const int k_edit = positions_until(&g_song, 1, 16, 1);
    const int when[] = {0, 40, k_edit - 30, k_edit - 2, k_edit};
    for (size_t i = 0; i < sizeof when / sizeof when[0]; ++i) {
        h = tick_hashes(&g_song, &g_edited, &g_edited, when[i], 0);
        bad = count_differences(edited, h, 0, &first);
        EXPECT(bad == 0, "note edit applied before tick %d: = edited straight play (%d differ, first %d)", when[i], bad, first);
        free(h);
    }
    EXPECT(count_differences(song, edited, 0, &first) > 0, "the note edit changes the writes");
    free(edited);
    /* Tempo: F09 on order 1 row 4. Applied ahead of it: straight play of the tempo song. */
    uint64_t* tempo = tick_hashes(&g_tempo, NULL, NULL, 0, 0);
    const int k_tempo = positions_until(&g_song, 1, 4, 1);
    h = tick_hashes(&g_song, &g_tempo, &g_tempo, k_tempo - 3, 0);
    bad = count_differences(tempo, h, 0, &first);
    EXPECT(bad == 0, "tempo edit ahead of the playhead: = tempo song straight play (%d differ, first %d)", bad, first);
    free(h);
    free(tempo);
    /* An instrument edit by slot (slot 1's level 15 -> 9; slot 10 is its byte-identical twin) applied while a slot-1
     * note sounds: until then the original's writes, after it the edited song's (only key-ons read the record). */
    const int k_lead = positions_until(&g_song, 1, 0, 1); /* lane 0 keys slot 1, lane 3 slot 10 */
    uint64_t* inst = tick_hashes(&g_inst, NULL, NULL, 0, 0);
    h = tick_hashes(&g_song, &g_inst, &g_inst, k_lead + 2, 0);
    bad = count_differences(song, h, 0, &first);
    EXPECT(first >= k_lead + 2 || first < 0, "instrument edit: nothing changes before it is applied (first difference %d)", first);
    bad = count_differences(inst, h, k_lead + 2, &first);
    EXPECT(bad == 0, "instrument edit: from then on the edited song's writes (%d differ, first %d)", bad, first);
    free(h);
    free(inst);
    {
        const int k2 = positions_until(&g_song, 1, 0, 2); /* the next pass keys both slots again */
        EXPECT(k2 > k_lead, "the loop order is entered twice (%d, %d)", k_lead, k2);
        const int lead_before = ev0_written(&g_song, NULL, 0, k_lead, 0), twin_before = ev0_written(&g_song, NULL, 0, k_lead, 3);
        const int lead_after = ev0_written(&g_song, &g_inst, k_lead + 2, k2, 0), twin_after = ev0_written(&g_song, &g_inst, k_lead + 2, k2, 3);
        EXPECT(lead_before == 0xF1 && twin_before == 0xF1 && lead_after == 0x91 && twin_after == 0xF1,
               "slot 1's next key-on uses level 9 (EV0 %02X -> %02X); its identical twin slot 10 keeps 15 (%02X -> %02X)",
               lead_before, lead_after, twin_before, twin_after);
        VsuMachine* a = vbm_create(RATE);
        VsuMachine* b = vbm_create(RATE);
        int mode;
        vbm_start(a, &g_song, 1, 0, SEEK_BUDGET, &mode);
        vbm_start(b, &g_song, 1, 0, SEEK_BUDGET, &mode);
        for (int i = 0; i < 4; ++i) vbm_tick(a, g_out), vbm_tick(b, g_out);
        vbm_apply_image(b, &g_inst);
        for (int i = 0; i < 20; ++i) vbm_tick(a, g_out), vbm_tick(b, g_out);
        EXPECT(vsu_equal(vbm_chip(a), vbm_chip(b)), "the sounding slot-1 note keeps what it was keyed with");
        vbm_destroy(a);
        vbm_destroy(b);
    }
    /* A wave edit while lane 0 plays wave 1: poked into the chip at once, heard at once, no writes. */
    {
        Trace ta, tb;
        trace_init(&ta, 10);
        trace_init(&tb, 10);
        VsuMachine* a = vbm_create(RATE);
        VsuMachine* b = vbm_create(RATE);
        int mode;
        vbm_start(a, &g_song, 1, 0, SEEK_BUDGET, &mode);
        vbm_start(b, &g_song, 1, 0, SEEK_BUDGET, &mode);
        vbm_tick(a, g_out), vbm_tick(b, g_out);
        EXPECT(vbm_chip(b)->ch[0].active && vbm_chip(b)->ch[0].ram == 0, "lane 0 plays wave 1");
        vbm_apply_image(b, &g_wave);
        int poked = 1;
        for (int i = 0; i < 32; ++i) poked &= vbm_chip(b)->wave[0][i] == g_wave.bytes[VB_OFF_WAVES + i];
        EXPECT(poked, "the edited wave is in the chip's wave RAM at once (channels playing)");
        vbm_set_trace(a, trace_tick, trace_sample, &ta);
        vbm_set_trace(b, trace_tick, trace_sample, &tb);
        vbm_tick(a, g_out), vbm_tick(b, g_out);
        int differ = 0;
        for (long i = 0; i < ta.nsamples && i < tb.nsamples; ++i) differ |= ta.samples[i] != tb.samples[i];
        EXPECT(differ && ta.hash == tb.hash, "heard in the very next tick, with the same register writes");
        EXPECT(vbm_player(b)->shadow[VSU_WAVE(0, 0)] == g_wave.bytes[VB_OFF_WAVES], "the player's shadow took the new wave");
        free(ta.samples);
        free(tb.samples);
        vbm_destroy(a);
        vbm_destroy(b);
    }
    /* A modulation edit while Wave 5 modulates. */
    {
        VsuMachine* m = vbm_create(RATE);
        int mode;
        vbm_start(m, &g_song, 1, 0, SEEK_BUDGET, &mode);
        vbm_tick(m, g_out);
        EXPECT(vbm_chip(m)->ch[4].active, "Wave 5 plays (modulation)");
        vbm_apply_image(m, &g_modedit);
        int poked = 1;
        for (int i = 0; i < 32; ++i) poked &= vbm_chip(m)->mod[i] == g_modedit.bytes[VB_OFF_MOD + i];
        EXPECT(poked, "the edited modulation table is in the chip at once (channel 5 playing)");
        vbm_destroy(m);
    }
    free(song);
    VsuMachine* m = vbm_create(RATE);
    int mode, o, r, t;
    vbm_start(m, &g_song, 1, 8, SEEK_BUDGET, &mode);
    vbm_apply_image(m, &g_tempo);
    const int a = row_length(m);
    vbm_apply_image(m, &g_song);
    const int b = row_length(m);
    EXPECT(a == 9 && b == 6, "tempo edit behind the playhead: rows of %d ticks, undo %d", a, b);
    /* Cut the song to two orders while order 3 plays: clamped, then order 1 in order. */
    vbm_start(m, &g_song, 3, 5, SEEK_BUDGET, &mode);
    vbm_tick(m, g_out);
    vbm_apply_image(m, &g_short);
    vbm_position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 31, "clamped to the last row of the last order (%d/%d)", o, r);
    int ok = 1, rows_seen = 0, last_row = r;
    for (int k = 0; k < 6 * 10; ++k) {
        vbm_tick(m, g_out);
        vbm_position(m, &o, &r, &t);
        if (t == 0 && r != last_row) {
            if (o != 1 || r != (last_row + 1) % 32) ok = 0;
            last_row = r;
            ++rows_seen;
        }
    }
    EXPECT(ok && rows_seen >= 8, "then the loop order plays in order (%d rows)", rows_seen);
    vbm_destroy(m);
}

/* ---- audition -------------------------------------------------------------------------------------------------- */

/* Frequency of the left digital output from its rising crossings of the midpoint (samples 120 clocks apart). */
static double tone_hz(const int* s, long n) {
    int lo = 1 << 20, hi = -1;
    for (long i = 0; i < n; ++i) {
        if (s[2 * i] < lo) lo = s[2 * i];
        if (s[2 * i] > hi) hi = s[2 * i];
    }
    const double mid = (lo + hi) / 2.0;
    long first = -1, last = -1, count = 0;
    for (long i = 1; i < n; ++i) {
        if (s[2 * (i - 1)] < mid && s[2 * i] >= mid) {
            if (first < 0) first = i;
            last = i;
            ++count;
        }
    }
    return count > 1 ? (count - 1) * (double)VSU_CLOCK / ((last - first) * (double)VSU_SAMPLE_CLOCKS) : 0;
}

static void test_audition(const char* dir) {
    printf("audition\n");
    VsuMachine* m = vbm_create(RATE);
    Trace t;
    trace_init(&t, 60);
    /* A-4 on a sustained wave instrument: 5 MHz / (32 x 355) = 440.14 Hz */
    EXPECT(vbm_audition(m, &g_song, 0, A4, VBT_TONE, 0) == VBM_OK, "audition A-4");
    EXPECT(vbm_chip(m)->ch[0].active && vbm_chip(m)->ch[0].freq == 1693, "keyed at F 1693");
    vbm_set_trace(m, NULL, trace_sample, &t);
    float* buf;
    const long frames = render(m, 50, &buf);
    vbm_set_trace(m, NULL, NULL, NULL);
    const double hz = tone_hz(t.samples + 2 * 834, t.nsamples / 2 - 834);
    const double want = 5e6 / (32.0 * 355);
    EXPECT(fabs(hz - want) < 0.05, "A-4 measured from the digital output: %.3f Hz (expected %.3f; tolerance unverified)", hz, want);
    EXPECT(vbm_release(m) == 0, "release: no release phase");
    EXPECT(!vbm_chip(m)->ch[0].active, "the channel stopped at once");
    int l, r;
    vsu_sample(vbm_chip(m), 0, &l, &r);
    EXPECT(l == 0 && r == 0, "silence after the release");
    EXPECT(vbm_tick(m, g_out) == 0, "tick returns 0 after the release");
    write_wav(dir, "vsu_tone.wav", buf, frames);
    free(buf);
    /* fine pitch: +64 = one semitone */
    vbm_audition(m, &g_song, 0, A4, VBT_TONE, 64);
    EXPECT(vbm_chip(m)->ch[0].freq == vbp_frequency(0, (A4 + 1) * 64), "fine +64 keys A#4's frequency value");
    vbm_audition(m, &g_song, 0, A4, VBT_TONE, -128);
    EXPECT(vbm_chip(m)->ch[0].freq == vbp_frequency(0, (A4 - 2) * 64), "fine -128 keys G-4's frequency value");
    /* noise: the register steps every 10 (2048 - F) clocks */
    t.nsamples = 0;
    EXPECT(vbm_audition(m, &g_song, 5, 48, VBT_NOISE, 0) == VBM_OK, "audition noise C-4");
    const int fn = vbm_chip(m)->ch[5].freq;
    vbm_set_trace(m, NULL, trace_sample, &t);
    for (int i = 0; i < 20; ++i) vbm_tick(m, g_out);
    vbm_set_trace(m, NULL, NULL, NULL);
    {
        const long step = 10L * (2048 - fn);
        long prev = -1, min = 1L << 30, odd = 0, runs = 0;
        for (long i = 1; i < t.nsamples / 2; ++i) {
            if (t.samples[2 * i] == t.samples[2 * (i - 1)]) continue;
            if (prev >= 0) {
                const long d = (i - prev) * (long)VSU_SAMPLE_CLOCKS;
                if (d % step) ++odd;
                if (d < min) min = d;
                ++runs;
            }
            prev = i;
        }
        EXPECT(step == 600 && odd == 0 && min == step && runs > 1000,
               "noise C-4: output changes %ld clocks apart at the least, every gap a multiple of 10 (2048 - F) = %ld (%ld runs, %.1f Hz rate)",
               min, step, runs, 5e6 / step);
    }
    /* release before the first tick */
    vbm_audition(m, &g_song, 2, A4, VBT_LEAD, 0);
    EXPECT(vbm_release(m) == 0 && vbm_tick(m, g_out) == 0, "release before the first tick: silent at once");
    /* arguments */
    EXPECT(vbm_audition(m, &g_song, 0, VB_WAVE_NOTE_MIN, VBT_TONE, 0) == VBM_OK, "D#2 on a wave lane");
    EXPECT(vbm_audition(m, &g_song, 5, 0, VBT_NOISE, 0) == VBM_OK, "C-0 on the noise lane");
    EXPECT(vbm_audition(m, &g_song, 0, VB_WAVE_NOTE_MIN - 1, VBT_TONE, 0) == VBM_ERR_IMAGE, "D-2 on a wave lane rejected");
    EXPECT(vbm_audition(m, &g_song, 5, 96, VBT_NOISE, 0) == VBM_ERR_IMAGE, "note 96 rejected");
    EXPECT(vbm_audition(m, &g_song, 6, A4, VBT_TONE, 0) == VBM_ERR_IMAGE, "lane 6 rejected");
    EXPECT(vbm_audition(m, &g_song, 0, A4, 0, 0) == VBM_ERR_IMAGE, "slot 0 rejected");
    EXPECT(vbm_audition(m, &g_song, 0, A4, VBT_INSTRUMENTS + 1, 0) == VBM_ERR_IMAGE, "an undefined slot rejected");
    EXPECT(vbm_audition(m, &g_song, 0, A4, VBT_TONE, 128) == VBM_ERR_IMAGE && vbm_audition(m, &g_song, 0, A4, VBT_TONE, -129) == VBM_ERR_IMAGE,
           "fine outside -128..127 rejected");
    EXPECT(vbm_tick(m, g_out) == 0, "a rejected audition leaves the machine silent");
    /* mutes cleared; live edits ignored while auditioning */
    vbm_audition(m, &g_song, 1, A4, VBT_TONE, 0);
    vbm_mute(m, 1, 1);
    vbm_audition(m, &g_song, 1, A4, VBT_TONE, 0);
    const long fr = render(m, 10, &buf);
    EXPECT(rms(buf, fr) > 0.00125, "audition clears mutes (rms %.4f)", rms(buf, fr));
    free(buf);
    vbm_apply_image(m, &g_wave);
    EXPECT(vbm_chip(m)->wave[0][0] == g_song.bytes[VB_OFF_WAVES], "apply_image ignored while auditioning");
    /* Wave 5's hardware sweep stops its own channel; the machine keeps ticking until the host releases */
    EXPECT(vbm_audition(m, &g_song, 4, 48, VBT_SWEEP5, 0) == VBM_OK, "audition the sweep instrument");
    for (int i = 0; i < 3; ++i) vbm_tick(m, g_out);
    EXPECT(!vbm_chip(m)->ch[4].active && vbm_tick(m, g_out) == TICK_FRAMES, "the sweep overflow stopped channel 5 by itself");
    EXPECT(vbm_release(m) == 0, "release");
    free(t.samples);
    vbm_destroy(m);
}

/* ---- mute ------------------------------------------------------------------------------------------------------ */

static void test_mute(void) {
    printf("mute\n");
    enum { TICKS = 600, UNMUTE = 106 }; /* order 1 row 1: lane 1's held C-3 (no envelope) sounds */
    VsuChip* chips = (VsuChip*)malloc(sizeof(VsuChip) * TICKS);
    Trace a, b, c, d;
    trace_init(&a, TICKS);
    trace_init(&b, TICKS);
    trace_init(&c, TICKS);
    trace_init(&d, TICKS);
    VsuMachine* m = vbm_create(RATE);
    int mode, same_chip = 1;
    /* A: plain; B: lane 1 muted; C: the song without lane 1's notes; D: lane 1 muted, unmuted at UNMUTE. */
    vbm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    vbm_set_trace(m, trace_tick, trace_sample, &a);
    for (int k = 0; k < TICKS; ++k) {
        vbm_tick(m, g_out);
        chips[k] = *vbm_chip(m);
    }
    vbm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    EXPECT(vbm_mute(m, 1, 1) == VBM_OK, "mute lane 1");
    vbm_set_trace(m, trace_tick, trace_sample, &b);
    for (int k = 0; k < TICKS; ++k) {
        vbm_tick(m, g_out);
        same_chip &= vsu_equal(vbm_chip(m), &chips[k]);
    }
    EXPECT(same_chip && a.hash == b.hash, "a muted channel's chip state and the player's writes = unmuted (every field, every tick)");
    vbm_start(m, &g_drop1, 0, 0, SEEK_BUDGET, &mode);
    vbm_set_trace(m, trace_tick, trace_sample, &c);
    for (int k = 0; k < TICKS; ++k) vbm_tick(m, g_out);
    EXPECT(b.nsamples == c.nsamples && !memcmp(b.samples, c.samples, sizeof(int) * (size_t)b.nsamples),
           "lane 1 muted = the song without lane 1's notes: the other channels' output unchanged (%ld samples)", b.nsamples / 2);
    vbm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    vbm_mute(m, 1, 1);
    vbm_set_trace(m, trace_tick, trace_sample, &d);
    long at_unmute = 0;
    for (int k = 0; k < TICKS; ++k) {
        if (k == UNMUTE) {
            vbm_mute(m, 1, 0);
            at_unmute = d.nsamples;
        }
        vbm_tick(m, g_out);
    }
    vbm_set_trace(m, NULL, NULL, NULL);
    EXPECT(!memcmp(d.samples, b.samples, sizeof(int) * (size_t)at_unmute) &&
               !memcmp(d.samples + at_unmute, a.samples + at_unmute, sizeof(int) * (size_t)(a.nsamples - at_unmute)),
           "unmuted mid-note at tick %d: the muted output before, the plain output from the next sample on", UNMUTE);
    int heard = 0;
    for (long i = at_unmute; i < at_unmute + 2 * 834; ++i) heard |= a.samples[i] != b.samples[i];
    EXPECT(heard, "lane 1's held note is heard right after unmuting");
    /* every lane muted: digital silence; start clears mutes */
    vbm_start(m, &g_song, 1, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 6; ++k) vbm_mute(m, k, 1);
    Trace e;
    trace_init(&e, 30);
    vbm_set_trace(m, NULL, trace_sample, &e);
    for (int k = 0; k < 30; ++k) vbm_tick(m, g_out);
    vbm_set_trace(m, NULL, NULL, NULL);
    int silent = 1;
    for (long i = 0; i < e.nsamples; ++i) silent &= e.samples[i] == 0;
    EXPECT(silent, "every lane muted: digital output 0");
    vbm_start(m, &g_song, 1, 0, SEEK_BUDGET, &mode);
    float* buf;
    long frames = render(m, 30, &buf);
    EXPECT(rms(buf, frames) > 0.00125, "start clears mutes (rms %.4f)", rms(buf, frames));
    free(buf);
    EXPECT(vbm_mute(m, 6, 1) == VBM_ERR_IMAGE && vbm_mute(m, -1, 1) == VBM_ERR_IMAGE, "lane 6 rejected");
    free(a.samples), free(b.samples), free(c.samples), free(d.samples), free(e.samples);
    free(chips);
    vbm_destroy(m);
}

/* ---- VGM ------------------------------------------------------------------------------------------------------ */

static uint32_t u32(const uint8_t* p) { return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24; }

typedef struct Log {
    long n, cap;
    uint16_t* reg;
    uint8_t* val;
    long* tick_start; /* index of tick t's first write */
    long ticks;
} Log;

static void log_add(Log* l, int reg, int val) {
    if (l->n == l->cap) {
        l->cap = l->cap ? 2 * l->cap : 4096;
        l->reg = (uint16_t*)realloc(l->reg, sizeof(uint16_t) * (size_t)l->cap);
        l->val = (uint8_t*)realloc(l->val, (size_t)l->cap);
    }
    l->reg[l->n] = (uint16_t)reg;
    l->val[l->n++] = (uint8_t)val;
}

static void log_tick(void* ctx, int order, int row, int tick, uint64_t clock, const VbWrite* w, int n) {
    Log* l = (Log*)ctx;
    (void)order, (void)row, (void)tick, (void)clock;
    l->tick_start[l->ticks++] = l->n;
    for (int i = 0; i < n; ++i) log_add(l, w[i].reg, w[i].val);
}

static int mapped(int reg) {
    if (reg < 0xC0) return 1;
    if (reg == VSU_SSTOP) return 1;
    if (reg < 0x100 || reg > 0x15F) return 0;
    const int c = (reg - 0x100) >> 4, r = reg & 15;
    return r <= VSU_RAM ? !(r == VSU_RAM && c == 5) : r == VSU_SWP && c == 4;
}

/* Plays the file's commands from `at` to the end into chip (0x63: 100000 clocks). Returns 0 on a bad command. */
static int vgm_play(const uint8_t* buf, size_t size, size_t at, VsuChip* chip, size_t* stop_at, size_t stop_offset, VsuChip* snapshot) {
    while (at < size) {
        if (at == stop_offset && snapshot) *snapshot = *chip;
        const int cmd = buf[at];
        if (cmd == 0xC7) {
            vsu_write(chip, (uint16_t)(buf[at + 1] << 8 | buf[at + 2]), buf[at + 3]);
            at += 4;
        } else if (cmd == 0x63) {
            vsu_run(chip, TICK);
            ++at;
        } else if (cmd == 0x66) {
            if (stop_at) *stop_at = at + 1;
            return 1;
        } else {
            return 0;
        }
    }
    return 0;
}

static void test_vgm(const char* dir) {
    printf("VGM export\n");
    static VbVgmScratch scratch;
    VbVgmInfo info;
    const double t0 = now_us();
    vb_vgm_render(&g_song, VGM_TICKS, &scratch, NULL, 0, &info);
    const size_t cap = info.size;
    uint8_t* buf = (uint8_t*)malloc(cap);
    const size_t size = vb_vgm_render(&g_song, VGM_TICKS, &scratch, buf, cap, &info);
    const double us = now_us() - t0;
    EXPECT(size == cap && size > 0x100, "renders (%zu bytes)", size);
    EXPECT(vb_vgm_render(&g_song, VGM_TICKS, &scratch, buf, 1000, &info) == 0, "too small a buffer reports 0");
    vb_vgm_render(&g_song, VGM_TICKS, &scratch, buf, cap, &info);
    int zero = 1;
    for (int i = 0x0C; i < 0x100; ++i)
        if ((i < 0x18 || i >= 0x28) && i != 0x34 && !(i >= 0x34 && i < 0x38) && !(i >= 0xC4 && i < 0xC8)) zero &= buf[i] == 0;
    EXPECT(!memcmp(buf, "Vgm ", 4) && u32(buf + 4) == size - 4 && u32(buf + 8) == 0x171 && u32(buf + 0x24) == 50 &&
               u32(buf + 0x34) == 0xCC && u32(buf + 0xC4) == 5000000 && u32(buf + 0x14) == 0 && zero,
           "header: version 1.71, 50 Hz, data at 0x100, VSU clock 5000000, other fields 0");
    EXPECT(info.loop_samples > 0 && info.ticks < VGM_TICKS, "the song loops (%u ticks, loop of %u samples)", info.ticks, info.loop_samples);
    EXPECT(u32(buf + 0x18) == info.ticks * VB_VGM_TICK_SAMPLES && u32(buf + 0x20) == (info.ticks - info.loop_tick) * VB_VGM_TICK_SAMPLES &&
               0x1C + u32(buf + 0x1C) == info.loop_offset,
           "total = ticks x 882 samples, loop = (end - loop tick) x 882, loop offset");
    /* The machine's writes over the same ticks; its loop arrivals and states. */
    Log want;
    memset(&want, 0, sizeof want);
    want.tick_start = (long*)malloc(sizeof(long) * (info.ticks + 2));
    VsuMachine* m = vbm_create(RATE);
    int mode;
    vbm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    const VbPlayer* p = vbm_player(m);
    for (int i = 0; i < p->writes; ++i) log_add(&want, p->write[i].reg, p->write[i].val);
    vbm_set_trace(m, log_tick, NULL, &want);
    enum { MAX_ARRIVALS = 64 };
    static VbPlayer ap[MAX_ARRIVALS];
    static VsuChip ac[MAX_ARRIVALS];
    uint32_t at_tick[MAX_ARRIVALS];
    int arrivals = 0;
    for (uint32_t t = 0; t <= info.ticks; ++t) {
        if (p->order == g_song.info.loop && p->row == 0 && p->tick == 0 && arrivals < MAX_ARRIVALS) {
            ap[arrivals] = *p;
            ac[arrivals] = *vbm_chip(m);
            at_tick[arrivals++] = t;
        }
        if (t < info.ticks) vbm_tick(m, g_out);
    }
    vbm_set_trace(m, NULL, NULL, NULL);
    vbm_destroy(m);
    int k = -1;
    for (int i = 0; i + 1 < arrivals && k < 0; ++i)
        if (vbp_state_equal(&ap[i], &ap[i + 1]) && vsu_held_equal(&ac[i], &ac[i + 1])) k = i;
    EXPECT(k >= 0 && info.loop_tick == at_tick[k] && info.ticks == at_tick[k + 1],
           "the loop point is the first arrival equal to the next (arrival %d at tick %u; file ends at tick %u)", k,
           k >= 0 ? at_tick[k] : 0, info.ticks);
    /* Parse the file from the format alone. */
    long n = 0, mismatches = 0, first = -1, waits = 0, loop_entry = -1, unmapped = 0;
    size_t at = 0x100;
    int ended = 0, bad_command = 0;
    while (at < size && !ended && !bad_command) {
        if (at == info.loop_offset) loop_entry = n;
        const int cmd = buf[at];
        if (cmd == 0xC7) {
            const int reg = buf[at + 1] << 8 | buf[at + 2], val = buf[at + 3];
            if (!mapped(reg)) ++unmapped;
            if (n >= want.n || reg != want.reg[n] || val != want.val[n]) {
                if (first < 0) first = n;
                ++mismatches;
            }
            ++n;
            at += 4;
        } else if (cmd == 0x63) {
            ++waits, ++at;
        } else if (cmd == 0x66) {
            ended = 1, ++at;
        } else {
            bad_command = 1;
        }
    }
    EXPECT(ended && !bad_command && at == size, "commands parse (0xC7, 0x63) to 0x66 at the end of the file");
    EXPECT(unmapped == 0, "every write decodes to a mapped VSU register");
    EXPECT(waits == (long)info.ticks, "one 0x63 per tick (%ld)", waits);
    EXPECT(n == want.n && mismatches == 0, "the VGM's %ld writes = the machine's (%ld differ, first %ld; %ld expected): no synthetic writes",
           n, mismatches, first, want.n);
    EXPECT(k < 0 || loop_entry == want.tick_start[at_tick[k]], "the loop offset is the first write of the loop tick");
    /* A player looping the file starts every pass from the same held state. */
    static VsuChip c1, at_loop, c2;
    vsu_reset(&c1);
    size_t end = 0;
    EXPECT(vgm_play(buf, size, 0x100, &c1, &end, info.loop_offset, &at_loop), "the file plays");
    c2 = c1;
    vgm_play(buf, size, info.loop_offset, &c2, NULL, 0, NULL);
    EXPECT(vsu_held_equal(&at_loop, &c1) && vsu_held_equal(&c1, &c2),
           "held state at the loop point = at the end of the first pass = at the end of the second");
    write_file(dir, "vsu_song.vgm", buf, size);
    printf("  %zu bytes, %u ticks, %ld writes, loop from tick %u (%.2f s), rendered in %.0f us\n", size, info.ticks, n, info.loop_tick,
           info.loop_samples / 44100.0, us);
    free(want.reg), free(want.val), free(want.tick_start);
    free(buf);
}

/* The hosts' entry point (vsu_export.h): the VGM plus a GD3 tag. */
static void test_export(void) {
    printf("VGM export entry point\n");
    static VbVgmScratch scratch;
    VbVgmInfo info;
    vb_vgm_render(&g_song, VGM_TICKS, &scratch, NULL, 0, &info);
    const size_t body_size = info.size;
    uint8_t* body = (uint8_t*)malloc(body_size);
    vb_vgm_render(&g_song, VGM_TICKS, &scratch, body, body_size, &info);
    const uint64_t body_hash = fnv(FNV_BASIS, body + 8, body_size - 8);
    size_t size = 0;
    const char* problem = NULL;
    const char* title = "Crimson \xC3\x96pening \xF0\x9F\x8E\xB9"; /* 2-byte and 4-byte UTF-8 */
    uint8_t* vgm = vb_export_vgm(g_song.bytes, sizeof g_song.bytes, VGM_TICKS, title, "Esko", "2026/11/08", &size, &problem);
    EXPECT(vgm && !problem, "renders (%s)", problem ? problem : "ok");
    if (!vgm) {
        free(body);
        return;
    }
    const uint32_t gd3_at = 0x14 + u32(vgm + 0x14);
    EXPECT(gd3_at == body_size && 4 + u32(vgm + 4) == size, "GD3 after the data (at %u), EOF offset = size %zu", gd3_at, size);
    memset(vgm + 0x14, 0, 4);
    EXPECT(fnv(FNV_BASIS, vgm + 8, body_size - 8) == body_hash, "the data = vb_vgm_render's (header 0x04/0x14 aside)");
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
    EXPECT(!strcmp(fields[0], title) && !strcmp(fields[4], "Virtual Boy VSU (Thor Tracker)") && !strcmp(fields[6], "Esko") &&
               !strcmp(fields[8], "2026/11/08") && !strcmp(fields[9], "Thor Tracker") && !fields[2][0] && !fields[10][0],
           "GD3 fields: '%s', '%s', '%s', '%s', '%s'", fields[0], fields[4], fields[6], fields[8], fields[9]);
    vb_export_free(vgm);
    free(body);
    memcpy(g_bad.bytes, g_song.bytes, sizeof g_bad.bytes);
    g_bad.bytes[VB_H_SPEED] = 0;
    vgm = vb_export_vgm(g_bad.bytes, sizeof g_bad.bytes, VGM_TICKS, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && size == 0 && problem && strstr(problem, "speed"), "bad image: NULL, '%s'", problem ? problem : "");
    vgm = vb_export_vgm(g_song.bytes, 100, VGM_TICKS, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && problem && !strcmp(problem, "image size"), "short image: '%s'", problem ? problem : "");
    vgm = vb_export_vgm(g_song.bytes, sizeof g_song.bytes, 100, NULL, NULL, NULL, &size, &problem);
    EXPECT(vgm && u32(vgm + 0x1C) == 0 && u32(vgm + 0x20) == 0 && u32(vgm + 0x18) == 100 * VB_VGM_TICK_SAMPLES && size > 0x100,
           "tick limit before the loop closes: no loop, ends at the limit");
    if (vgm) {
        const uint8_t* d = vgm + 0x14 + u32(vgm + 0x14) + 12;
        for (int f = 0; f < 8; ++f, d += 2)
            while (d[0] | d[1]) d += 2;
        char date[16] = {0};
        for (int i = 0; i < 15 && (d[2 * i] | d[2 * i + 1]); ++i) date[i] = (char)d[2 * i];
        EXPECT(strlen(date) == 10 && date[4] == '/' && date[7] == '/' && !strncmp(date, "20", 2), "default date today: %s", date);
    }
    vb_export_free(vgm);
}

/* ---- ops ------------------------------------------------------------------------------------------------------ */

static void test_ops(void) {
    printf("ops table\n");
    const ChipEngineOps* ops = chip_engine_ops("vb-vsu");
    EXPECT(ops && !strcmp(ops->system_id, "vb-vsu"), "registry");
    if (!ops) return;
    EXPECT(ops->image_size == VB_IMAGE_SIZE && ops->image_storage >= ops->image_size, "sizes");
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
    EXPECT(frames == 60 * TICK_FRAMES && n == TICK_FRAMES, "60 ticks = 60 x 960 frames (%d)", frames);
    ops->position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 14 && t == 0, "10 rows later (%d/%d/%d)", o, r, t);
    ops->apply_image(m, image);
    EXPECT(ops->mute(m, 0, 1) == CHIP_OK && ops->mute(m, 0, 0) == CHIP_OK && ops->mute(m, 6, 1) == CHIP_ERR_IMAGE, "mute");
    EXPECT(ops->audition(m, image, 0, A4, VBT_LEAD, 0) == CHIP_OK, "audition");
    EXPECT(ops->tick(m, out) == TICK_FRAMES, "audition ticks");
    EXPECT(ops->release(m) == 0 && ops->tick(m, out) == 0, "release: silent at once, tick returns 0");
    EXPECT(ops->audition(m, image, 0, 26, VBT_LEAD, 0) == CHIP_ERR_IMAGE, "D-2 on Wave 1: CHIP_ERR_IMAGE");
    EXPECT(ops->audition(m, image, 5, 0, VBT_HAT, 0) == CHIP_OK, "C-0 on Noise");
    EXPECT(ops->row_serial == NULL, "no row_serial: tick 0 marks row starts");
    ops->destroy(m);
    free(image);
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    build(&g_song, (VbSongOptions){0});
    build(&g_moved, (VbSongOptions){.relayout = 1});
    build(&g_edited, (VbSongOptions){.note_edit = 1});
    build(&g_tempo, (VbSongOptions){.tempo_edit = 1});
    build(&g_short, (VbSongOptions){.cut = 1});
    build(&g_inst, (VbSongOptions){.inst_edit = 1});
    build(&g_wave, (VbSongOptions){.wave_edit = 1});
    build(&g_modedit, (VbSongOptions){.mod_edit = 1});
    build(&g_drop1, (VbSongOptions){.drop_lane = 2});
    EXPECT(memcmp(g_song.bytes, g_moved.bytes, VB_IMAGE_SIZE) != 0, "the re-laid-out image differs in its bytes");
    test_chip(dir);
    test_images();
    test_pitch_table();
    test_trace(dir);
    test_audio(dir);
    test_headroom();
    test_note_on_timing();
    test_seek();
    test_live_edits();
    test_audition(dir);
    test_mute();
    test_vgm(dir);
    test_export();
    test_ops();
    printf("VSU: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
