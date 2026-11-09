/* Host regression test for the AY-3-8910 / YM2149 systems ("ay-zx128", "ay-msx", "ay-atari-st", "ay-cpc"): Thor Tracker's own
 * player (ay/ay_player.c) on its own chip core (ay/ay_psg.c), playing the test song of tools/audio/ay/ay_song.py
 * (ay_test_song.h, from tools/audio/prepare_ay.py) once per variant.
 *
 * Checks: the pitch tables (A-4 per variant, every note of the range within the period quantisation, the lowest
 * reachable note); image validation (each fixture corruption rejected at its reference byte; variants
 * accepted only by their own engine); the player's writes and positions identical to the independent Python
 * reference player (tools/audio/ay/ay_ref.py) in every tick for every variant; a note that starts silent restarting
 * the envelope on its first audible tick (one-shot shape reused) and dropping the restart when keyed off; chip unit
 * checks (tone frequency by
 * output transitions, the 17-bit noise LFSR sequence and its closed form, all 16 envelope shapes on the AY and the YM
 * against the manuals' table, period 0, R13 restarts, a period shortened below its counter, DAC scale, mute, the CPC's
 * stereo wiring left A + B / right B + C);
 * silent arithmetic advance = stepping (random register sets, and the chip state after every tick of the song); seeks
 * landing with the chip state bit-identical to straight play and the same output steps after it, the order fallback,
 * rejected targets; loop passes; live edits (re-layout before every tick, a note edit, a tempo edit, the song cut
 * under the playhead, all against the reference's traces; the variant byte ignored); audition (A-4 measured per
 * variant, fine pitch, the envelope buzzer's pitch, the synced buzzer, release, argument limits; on the CPC A left only,
 * C right only, B on both equally); mute (writes unchanged, silence, soloed channels summing to the mix on each side);
 * VGM bytes identical to the reference's plus a structural parse; the hosts' export entry point (GD3 decode,
 * rejections); the four ops tables; costs. Independent comparison: exact generator states and gated DAC levels
 * against vendored ayumi (third_party/ayumi, MIT; test-only), then register writes replayed at exact chip-step
 * timestamps through its resampler. Each solo and mix is compared by loudness-envelope correlation and RMS level;
 * CPC sides are compared with ayumi panned to exactly that side's channels.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir]
 * Writes per variant key k (zx128, msx, atari-st, cpc): <dir>/ay_<k>.wav (48 kHz stereo, 30 s of the song), ay_<k>_tone.wav
 * (the A-4 audition, 2 s + release), ay_<k>_ayumi.wav (ayumi's render of the same writes), ay_<k>_trace.txt (per-tick
 * player writes: "order row tick: RR=VV ..."), ay_<k>.vgm (VGM 1.71, no GD3) and ay_<k>_image.bin (the image): the
 * inputs of tools/audio/ay_reference_check.py. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "ay_export.h"
#include "ay_machine.h"
#include "ay_test_song.h"
#include "ay_vgm.h"
/* Unmodified MIT reference core, test-only. Access its chip-step mixer so register writes are not rounded
 * to 48 kHz sample boundaries; that rounding changes the phase-sensitive synced buzzer's timbre. */
#include "ayumi.c"
#include "chip_engine.h"
#include "generated/ay_tables.h"

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
#define SLOT_SQUARE 1      /* test song: plain square, no envelopes, tone, volume 15 */
#define SLOT_BUZZER 2      /* shape 8, auto J00, mixer envelope [0]: the bare envelope at the note's pitch */
#define SLOT_SYNC_BUZZER 3 /* shape 10, auto J00, sync, tone */
#define AYUMI_TICKS 600    /* ticks compared with ayumi per render (12 s at 50 Hz) */

static const char* const KEY[AY_VARIANTS] = {"zx128", "msx", "atari-st", "cpc"};
static float g_out[AYM_MAX_TICK_FRAMES * 2];
static AyImage g_song[AY_VARIANTS], g_relayout[AY_VARIANTS], g_note[AY_VARIANTS],
    g_tempo[AY_VARIANTS], g_cut[AY_VARIANTS], g_bad;

/* ---- helpers --------------------------------------------------------------------------------- */

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

static uint32_t rd32(const uint8_t* p) { return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24; }

static double clock_hz(int v) { return (double)ay_variants[v].master_hz / ay_variants[v].ay_div; }
static double tick_hz(int v) { return (double)ay_variants[v].master_hz / ay_variants[v].frame_div; }

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

static void write_file(const char* path, const void* data, size_t n) {
    FILE* f = fopen(path, "wb");
    if (!f) return;
    fwrite(data, 1, n, f);
    fclose(f);
}

/* Frequency from rising zero crossings of a mono signal (stride 1 or 2), mean removed, interpolated crossing times. */
static double zero_cross_hz(const float* x, int n, int stride) {
    double mean = 0;
    for (int i = 0; i < n; ++i) mean += x[stride * i];
    mean /= n;
    int count = 0;
    double first_t = -1, last_t = 0;
    for (int i = 1; i < n; ++i) {
        const double a = x[stride * (i - 1)] - mean, b = x[stride * i] - mean;
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

static double rms_mono(const float* x, int n, int stride) {
    double s = 0;
    for (int i = 0; i < n; ++i) s += (double)x[stride * i] * x[stride * i];
    return n ? sqrt(s / n) : 0;
}

static int render(AyMachine* m, int ticks, float** buf) {
    float* out = (float*)malloc((size_t)ticks * AYM_MAX_TICK_FRAMES * 2 * sizeof(float));
    int frames = 0;
    for (int t = 0; t < ticks; ++t) {
        const int n = ay_machine_tick(m, out + 2 * frames);
        if (n <= 0) break;
        frames += n;
    }
    *buf = out;
    return frames;
}

static int load(AyImage* image, const AyTestImage* t, int variant) {
    memset(image->bytes, 0, sizeof image->bytes);
    memcpy(image->bytes, t->bytes, t->used);
    return ay_load_image(image, variant);
}

static int trace_count(const AyTestTrace* r, int t) { return (int)(r->first[t + 1] - r->first[t]); }

/* Whether the player's last batch equals reference tick t. */
static int same(const AyPlayer* p, const AyTestTrace* r, int t) {
    if (p->writes != trace_count(r, t)) return 0;
    for (int i = 0; i < p->writes; ++i) {
        const uint32_t j = r->first[t] + (uint32_t)i;
        if (p->write[i].reg != r->writes[2 * j] || p->write[i].val != r->writes[2 * j + 1]) return 0;
    }
    return 1;
}

static int pos_is(const AyMachine* m, const AyTestTrace* r, int t) {
    int o, w, k;
    ay_machine_position(m, &o, &w, &k);
    return o == r->positions[3 * t] && w == r->positions[3 * t + 1] && k == r->positions[3 * t + 2];
}

/* Reference tick index whose position is (order, row, 0), first occurrence, or -1. */
static int ref_row_start(const AyTestTrace* r, int order, int row) {
    for (uint32_t t = 0; t < r->ticks; ++t)
        if (r->positions[3 * t] == order && r->positions[3 * t + 1] == row && r->positions[3 * t + 2] == 0) return (int)t;
    return -1;
}

/* Frames per tick: a tick runs whole chip steps (frame_div / (8 ay_div), rounded down or up by the accumulation), and
 * blip_buf yields the floor or ceiling of those steps at the host rate. */
static int frames_low(int v, uint32_t rate) {
    const uint32_t per = 8 * ay_variants[v].ay_div;
    return (int)floor((double)rate * (ay_variants[v].frame_div / per) * per / ay_variants[v].master_hz);
}
static int frames_high(int v, uint32_t rate) {
    const uint32_t per = 8 * ay_variants[v].ay_div;
    const uint32_t steps = ay_variants[v].frame_div / per + (ay_variants[v].frame_div % per != 0);
    return (int)ceil((double)rate * steps * per / ay_variants[v].master_hz);
}

/* ---- pitch tables ---------------------------------------------------------------------------- */

static void test_tables(void) {
    printf("pitch tables\n");
    static const int a4_tp[AY_VARIANTS] = {252, 254, 285, 142};
    static const int lowest[AY_VARIANTS] = {9, 9, 11, 0}; /* A-0, A-0, B-0, C-0 */
    for (int v = 0; v < AY_VARIANTS; ++v) {
        const double clk = clock_hz(v);
        const int tp = ay_tone_period(v, A4 * 64);
        const double hz = clk / (16.0 * tp);
        EXPECT(tp == a4_tp[v], "%s: A-4 tone period %d (want %d)", KEY[v], tp, a4_tp[v]);
        EXPECT(fabs(hz - 440.0) < 0.75, "%s: A-4 = %.2f Hz", KEY[v], hz);
        /* Every note of the range: the period is the rounded ideal one (DIV is itself rounded at 256x: + 1/256). */
        double worst = 0, worst_cents = 0;
        for (int n = lowest[v]; n < AY_NOTES; ++n) {
            const double want = 440.0 * pow(2.0, (n - A4) / 12.0);
            const double ideal = clk / (16.0 * want);
            const int got = ay_tone_period(v, n * 64);
            const double err = fabs(got - ideal);
            if (err > worst) worst = err;
            const double cents = fabs(1200.0 * log2(clk / (16.0 * got) / want));
            if (cents > worst_cents) worst_cents = cents;
        }
        EXPECT(worst <= 0.5 + 1.0 / 256, "%s: every note %d..95 within the period quantisation (worst %.4f periods)", KEY[v],
               lowest[v], worst);
        if (lowest[v] > 0)
            EXPECT(ay_tone_period(v, lowest[v] * 64) <= AY_TONE_PERIOD_MAX &&
                       clk / (16.0 * 440.0 * pow(2.0, (lowest[v] - 1 - A4) / 12.0)) > AY_TONE_PERIOD_MAX &&
                       ay_tone_period(v, (lowest[v] - 1) * 64) == AY_TONE_PERIOD_MAX,
                   "%s: note %d is the lowest reachable (the one below clamps at 4095)", KEY[v], lowest[v]);
        else
            EXPECT(ay_tone_period(v, 0) < AY_TONE_PERIOD_MAX, "%s: C-0 is reachable (TP %d)", KEY[v], ay_tone_period(v, 0));
        /* Auto envelope: a saw at J00 has the note's frequency (256 EP clocks per cycle), J01 an octave up, a triangle
         * (512 EP clocks) J00 the note's. Each EP is the ideal period rounded (DIV is rounded at 256x, shifted >= 16
         * bits here: within 1/2 + 2^-17); the A-4 saw is a usable buzzer on every clock (within 4%: the CPC's 1 MHz
         * gives the coarsest, EP 9 = 434 Hz). */
        const int ep = ay_envelope_period(v, A4 * 64, 0, 8), ep_up = ay_envelope_period(v, A4 * 64, 1, 8);
        const int ep_tri = ay_envelope_period(v, A4 * 64, 0, 10);
        const double ideal = clk / (256.0 * 440.0);
        EXPECT(fabs(ep - ideal) <= 0.501 && fabs(ep_up - ideal / 2) <= 0.501 && fabs(ep_tri - ideal / 2) <= 0.501 &&
                   fabs(clk / (256.0 * ep) - 440.0) < 440.0 * 0.04,
               "%s: auto envelope periods A-4 saw %d (%.1f Hz), J01 %d (%.1f Hz), triangle %d (%.1f Hz); ideal %.3f", KEY[v],
               ep, clk / (256.0 * ep), ep_up, clk / (256.0 * ep_up), ep_tri, clk / (512.0 * ep_tri), ideal);
        EXPECT(ay_synced_tone_period(ep, 0, 8) == ep * 16 && ay_synced_tone_period(ep_tri, 0, 10) == ep_tri * 32,
               "%s: synced tone = EP x 16 (saw), x 32 (triangle)", KEY[v]);
        printf("  %s: A-4 TP %d = %.2f Hz; notes within %.4f periods (%.2f cents); A-4 saw EP %d\n", KEY[v], tp, hz, worst,
               worst_cents, ep);
    }
    int sine_ok = 1;
    for (int i = 0; i < 64; ++i)
        if (AY_SINE[i] != (int)floor(127 * sin(2 * M_PI * i / 64) + 0.5)) sine_ok = 0;
    EXPECT(sine_ok, "vibrato sine table");
}

/* ---- images ---------------------------------------------------------------------------------- */

static void test_images(void) {
    printf("images\n");
    for (int c = 0; c < AY_TEST_CORRUPTION_COUNT; ++c) {
        const AyTestCorruption* k = &AY_TEST_CORRUPTIONS[c];
        memcpy(&g_bad, &g_song[0], sizeof g_bad);
        g_bad.bytes[k->offset] = k->value;
        const int rc = ay_load_image(&g_bad, AY_VARIANT_ZX128);
        EXPECT(rc != 0 && g_bad.info.problem && g_bad.info.problem_offset == k->problem_offset,
               "byte %u = %u rejected for \"%s\" at %d (got %s at %d)", k->offset, k->value, k->reason, k->problem_offset,
               rc ? g_bad.info.problem : "accepted", g_bad.info.problem_offset);
    }
    printf("  %d corruptions rejected at the reference's error locations\n", AY_TEST_CORRUPTION_COUNT);
    for (int v = 0; v < AY_VARIANTS; ++v) {
        EXPECT(g_song[v].info.variant == v && g_song[v].info.start_speed[0] == g_song[v].bytes[AY_H_SPEED], "%s: info",
               KEY[v]);
        for (int w = 0; w < AY_VARIANTS; ++w) {
            memcpy(&g_bad, &g_song[v], sizeof g_bad);
            const int rc = ay_load_image(&g_bad, w);
            if (w == v) EXPECT(rc == 0, "%s image loads for its own variant", KEY[v]);
            else
                EXPECT(rc != 0 && g_bad.info.problem && g_bad.info.problem_offset == AY_H_VARIANT,
                       "%s image rejected by the %s engine (%s)", KEY[v], KEY[w], rc ? g_bad.info.problem : "accepted");
        }
        memcpy(&g_bad, &g_song[v], sizeof g_bad);
        EXPECT(ay_load_image(&g_bad, -1) == 0 && !memcmp(&g_bad.info, &g_song[v].info, sizeof g_bad.info),
               "%s: loading is a pure function (any variant)", KEY[v]);
    }
    memcpy(&g_bad, &g_song[0], sizeof g_bad);
    g_bad.bytes[AY_H_VARIANT] = AY_VARIANTS;
    EXPECT(ay_load_image(&g_bad, -1) != 0 && g_bad.info.problem_offset == AY_H_VARIANT,
           "variant 4 rejected");
}

/* ---- reference player ------------------------------------------------------------------------ */

static void test_reference(const char* dir) {
    printf("reference player\n");
    for (int v = 0; v < AY_VARIANTS; ++v) {
        const AyTestTrace* ref = &AY_TEST_TRACE[v];
        EXPECT(ref->ticks == AY_TEST_TICKS, "%s: trace of %d ticks", KEY[v], AY_TEST_TICKS);
        AyMachine* m = ay_machine_create(RATE, v);
        int mode = -1;
        EXPECT(ay_machine_start(m, &g_song[v], 0, 0, AY_SEEK_TICKS, &mode) == AYM_OK && mode == AYM_SEEK_FROM_START,
               "%s: start", KEY[v]);
        const AyPsg* chip = ay_machine_chip(m);
        EXPECT(chip->reg[7] == (ay_variants[v].io_bits | 0x3F) && chip->ym == ay_variants[v].ym,
               "%s: reset batch R7 = %02X on the %s", KEY[v], chip->reg[7], chip->ym ? "YM2149" : "AY-3-8910");
        const AyPlayer* p = ay_machine_player(m);
        char path[512];
        snprintf(path, sizeof path, "%s/ay_%s_trace.txt", dir, KEY[v]);
        FILE* trace = fopen(path, "w");
        int mismatches = 0, first = -1, position_errors = 0, writes = 0, max_writes = 0, r13 = 0;
        unsigned shapes = 0;
        for (int t = 0; t < AY_TEST_TICKS; ++t) {
            if (!pos_is(m, ref, t)) ++position_errors;
            int o, r, k;
            ay_machine_position(m, &o, &r, &k);
            ay_machine_tick(m, g_out);
            if (!same(p, ref, t)) {
                if (first < 0) first = t;
                ++mismatches;
            }
            writes += p->writes;
            if (p->writes > max_writes) max_writes = p->writes;
            for (int i = 0; i < p->writes; ++i)
                if (p->write[i].reg == 13) ++r13, shapes |= 1u << p->write[i].val;
            if (trace) {
                fprintf(trace, "%d %d %d:", o, r, k);
                for (int i = 0; i < p->writes; ++i) fprintf(trace, " %02X=%02X", p->write[i].reg, p->write[i].val);
                fputc('\n', trace);
            }
        }
        if (trace) fclose(trace);
        EXPECT(mismatches == 0, "%s: writes = reference player in all %d ticks (%d differ, first at tick %d)", KEY[v],
               AY_TEST_TICKS, mismatches, first);
        if (first >= 0) {
            printf("  %s tick %d reference:", KEY[v], first);
            for (uint32_t j = ref->first[first]; j < ref->first[first + 1]; ++j)
                printf(" %02X=%02X", ref->writes[2 * j], ref->writes[2 * j + 1]);
            printf("\n");
        }
        EXPECT(position_errors == 0, "%s: positions = reference in every tick (%d differ)", KEY[v], position_errors);
        EXPECT(max_writes <= AYP_MAX_WRITES, "%s: batches fit", KEY[v]);
        printf("  %s: %d ticks, %d writes (max %d per tick), %d R13 writes, shapes used %04X\n", KEY[v], AY_TEST_TICKS,
               writes, max_writes, r13, shapes);
        snprintf(path, sizeof path, "%s/ay_%s_image.bin", dir, KEY[v]);
        write_file(path, g_song[v].bytes, sizeof g_song[v].bytes);
        ay_machine_destroy(m);
    }
}

/* A hardware-envelope note that starts silent (volume envelope [0, 15]; or C00, then C0F on a later row) and owns the
 * envelope only from a later tick restarts it there, although the previous note left the same one-shot shape 9 (\___)
 * in R13 and its ramp has ended (without the restart the note would play the held level 0); the restart is written
 * once; a note keyed off before it sounds drops its pending restart. The player's writes go to a chip core, which shows
 * the envelope's level before and after each tick's writes. */
static void test_delayed_restart(void) {
    printf("delayed envelope restart\n");
    enum { SPEED = 2, ROWS = 8, NOTE = 48, PERIOD = 0x40, STEPS = 4000 }; /* one ramp = 16 * 0x40 * 2 = 2048 steps */
    static const uint8_t vol_from_0[] = {2, 0, 0xFF, 0xFF, 0xFF, 0xFF, 0, 15}; /* volume [0, 15], no loop or release */
    static const uint8_t pattern[] = {
        0x03, NOTE, 1,                    /* row 0: sound 1 (no volume envelope): R13 = 9 at once */
        0x03, NOTE, 2,                    /* row 1: sound 2: silent tick 0, sounds from tick 1 */
        AY_RUN | 0,                       /* row 2 */
        0x07, NOTE, 1, AY_FX_VOLUME, 0x00, /* row 3: sound 1 at C00: silent */
        0x04, AY_FX_VOLUME, 0x0F,         /* row 4: C0F: sounds */
        0x07, NOTE, 2, AY_FX_CUT, 0x01,   /* row 5: sound 2 keyed off at tick 1, before it sounds */
        AY_RUN | 1,                       /* rows 6-7 */
    };
    static const uint8_t rest[] = {AY_RUN | (ROWS - 1)};
    static const int restart_tick[] = {0, 1 * SPEED + 1, 4 * SPEED}; /* the ticks that must write R13 */
    const int at_pattern = (int)sizeof vol_from_0, at_rest = at_pattern + (int)sizeof pattern;
    static AyImage image;
    static AyPlayer p;
    for (int v = 0; v < AY_VARIANTS; ++v) {
        uint8_t* b = image.bytes;
        memset(b, 0, sizeof image.bytes);
        memcpy(b + AY_H_MAGIC, AY_MAGIC, 4);
        b[AY_H_LAYOUT] = AY_LAYOUT;
        b[AY_H_VARIANT] = (uint8_t)v;
        b[AY_H_SPEED] = SPEED;
        b[AY_H_ORDERS] = 1;
        b[AY_H_INSTRUMENTS] = 2;
        b[AY_H_DATA_SIZE] = (uint8_t)(at_rest + (int)sizeof rest);
        b[AY_OFF_ROWS] = ROWS;
        b[AY_OFF_ORDERS] = (uint8_t)at_pattern;
        b[AY_OFF_ORDERS + 2] = b[AY_OFF_ORDERS + 4] = (uint8_t)at_rest;
        for (int s = 1; s <= 2; ++s) {
            uint8_t* rec = b + AY_OFF_INSTRUMENTS + AY_INSTRUMENT_SIZE * (s - 1);
            rec[AY_I_FLAGS] = AY_FLAG_DEFINED; /* manual period */
            rec[AY_I_SHAPE] = 9;
            rec[AY_I_PERIOD] = PERIOD;
            memset(rec + AY_I_ENV_VOLUME, 0xFF, 2 * AYP_ENVELOPES);
        }
        b[AY_OFF_INSTRUMENTS + AY_INSTRUMENT_SIZE + AY_I_ENV_VOLUME] = 0; /* sound 2: the volume envelope at 0 */
        b[AY_OFF_INSTRUMENTS + AY_INSTRUMENT_SIZE + AY_I_ENV_VOLUME + 1] = 0;
        memcpy(b + AY_OFF_DATA, vol_from_0, sizeof vol_from_0);
        memcpy(b + AY_OFF_DATA + at_pattern, pattern, sizeof pattern);
        memcpy(b + AY_OFF_DATA + at_rest, rest, sizeof rest);
        if (ay_load_image(&image, v)) {
            EXPECT(0, "%s: delayed-restart image loads: %s", KEY[v], image.info.problem);
            continue;
        }
        AyPsg chip;
        ay_psg_reset(&chip, ay_variants[v].ym, ay_variants[v].stereo);
        ay_player_reset(&p, &image);
        for (int i = 0; i < p.writes; ++i) ay_psg_write(&chip, p.write[i].reg, p.write[i].val);
        int r13_bad = 0, r13_count = 0, pending_kept = 0, pending_dropped = 0;
        for (int t = 0; t < ROWS * SPEED; ++t) {
            const int level_before = ay_psg_env_level(&chip), held_before = chip.env_holding;
            ay_player_tick(&p, &image);
            int r13 = -1;
            for (int i = 0; i < p.writes; ++i) {
                ay_psg_write(&chip, p.write[i].reg, p.write[i].val);
                if (p.write[i].reg == 13) r13 = p.write[i].val, ++r13_count;
            }
            int expected = 0;
            for (size_t k = 0; k < sizeof restart_tick / sizeof restart_tick[0]; ++k) expected |= restart_tick[k] == t;
            if (expected != (r13 >= 0) || (expected && r13 != 9)) ++r13_bad;
            if (expected && t > 0) {
                EXPECT(level_before == 0 && held_before, "%s: tick %d: the previous \\___ has ended (level %d, holding %d)",
                       KEY[v], t, level_before, held_before);
                EXPECT(chip.reg[8] == 0x10 && ay_psg_env_level(&chip) == chip.env_mask,
                       "%s: tick %d: the first audible tick plays the restarted envelope (R8 %02X, level %d of %d)", KEY[v],
                       t, chip.reg[8], ay_psg_env_level(&chip), chip.env_mask);
            }
            if (t == 5 * SPEED) pending_kept = p.ch[0].restart;     /* row 5 tick 0: keyed, silent */
            if (t == 5 * SPEED + 1) pending_dropped = !p.ch[0].restart; /* row 5 tick 1: cut, phase off */
            ay_psg_run(&chip, STEPS, NULL, NULL);
        }
        EXPECT(r13_bad == 0 && r13_count == 3,
               "%s: R13 = 9 exactly at ticks 0 (key-on), 3 (volume envelope's first audible tick), 8 (C0F) (%d ticks "
               "differ, %d R13 writes)", KEY[v], r13_bad, r13_count);
        EXPECT(pending_kept && pending_dropped && p.ch[0].phase == AYP_OFF,
               "%s: a silent note keeps its restart pending; keyed off it drops it (kept %d, dropped %d)", KEY[v],
               pending_kept, pending_dropped);

        /* A and C start together; C owns until row 4. A already heard that shared envelope, so the handoff must not
         * replay its old key-on, for either a completed one-shot or a continuously cycling shape. */
        static const uint8_t held[] = {0x03, NOTE, 1, AY_RUN | (ROWS - 2)};
        static const uint8_t borrowed[] = {0x03, NOTE, 1, AY_RUN | 2, 0x01, AY_NOTE_OFF, AY_RUN | 2};
        const int at_held = at_rest + (int)sizeof rest, at_borrowed = at_held + (int)sizeof held;
        memcpy(b + AY_OFF_DATA + at_held, held, sizeof held);
        memcpy(b + AY_OFF_DATA + at_borrowed, borrowed, sizeof borrowed);
        b[AY_H_DATA_SIZE] = (uint8_t)(at_borrowed + (int)sizeof borrowed);
        b[AY_OFF_ORDERS] = (uint8_t)at_held;
        b[AY_OFF_ORDERS + 4] = (uint8_t)at_borrowed;
        for (int shape = 9; shape <= 10; ++shape) {
            b[AY_OFF_INSTRUMENTS + AY_I_SHAPE] = (uint8_t)shape;
            if (ay_load_image(&image, v)) {
                EXPECT(0, "%s: shared-envelope image loads: %s", KEY[v], image.info.problem);
                continue;
            }
            ay_psg_reset(&chip, ay_variants[v].ym, ay_variants[v].stereo);
            ay_player_reset(&p, &image);
            for (int i = 0; i < p.writes; ++i) ay_psg_write(&chip, p.write[i].reg, p.write[i].val);
            r13_count = 0;
            for (int t = 0; t < ROWS * SPEED; ++t) {
                const int before = ay_psg_env_level(&chip);
                ay_player_tick(&p, &image);
                for (int i = 0; i < p.writes; ++i) {
                    ay_psg_write(&chip, p.write[i].reg, p.write[i].val);
                    if (p.write[i].reg == 13) ++r13_count;
                }
                if (t == 4 * SPEED)
                    EXPECT(chip.reg[8] == 0x10 && chip.reg[10] == 0 && ay_psg_env_level(&chip) == before,
                           "%s: shape %d: cutting C leaves A on the uninterrupted envelope (%d -> %d)", KEY[v], shape,
                           before, ay_psg_env_level(&chip));
                ay_psg_run(&chip, 317, NULL, NULL);
            }
            EXPECT(r13_count == 1, "%s: shape %d: shared note onset writes R13 once, not again on handoff (%d writes)",
                   KEY[v], shape, r13_count);
        }
    }
}

/* ---- chip unit checks ------------------------------------------------------------------------ */

static int32_t g_mix[1 << 17];

/* Tone: output flips every TP steps (period 0 acts as 1), so 2 TP K steps from reset hold exactly K rising edges:
 * f = step rate / (2 TP) = clock / (16 TP). */
static void tone_edges(int ym, int tp) {
    AyPsg c;
    ay_psg_reset(&c, ym, 0);
    ay_psg_write(&c, 0, tp & 0xFF);
    ay_psg_write(&c, 1, tp >> 8);
    ay_psg_write(&c, 7, 0x3E);
    ay_psg_write(&c, 8, 15);
    const int eff = tp ? tp : 1, fit = (int)(sizeof g_mix / sizeof g_mix[0]) / (2 * eff), k = fit < 64 ? fit : 64;
    const uint32_t n = (uint32_t)(2 * eff * k);
    ay_psg_render_mix(&c, n, g_mix, NULL);
    int rising = 0, prev = 0, first = -1;
    for (uint32_t i = 0; i < n; ++i) {
        if (g_mix[i] && !prev) {
            ++rising;
            if (first < 0) first = (int)i;
        }
        prev = g_mix[i];
    }
    EXPECT(rising == k && first == eff - 1 && g_mix[eff - 1] == ay_psg_amplitude(ym, ym ? 31 : 15),
           "%s tone period %d: %d rising edges in %u steps (want %d), first at step %d", ym ? "YM" : "AY", tp, rising, n,
           k, first);
}

static uint32_t lfsr_ref(uint32_t x) { return (x >> 1) | (((x ^ (x >> 3)) & 1) << 16); }

/* The manuals' shape table: ramps D (down from max), U (up from 0); held L (0) and H (max). */
static const char* const SHAPE_RAMPS[16] = {"DL", "DL", "DL", "DL", "UL", "UL", "UL", "UL",
                                            "D",  "DL", "DU", "DH", "U",  "UH", "UD", "UL"};

static int shape_level(int shape, int j, int mask) {
    const char* r = SHAPE_RAMPS[shape];
    const int s = j / (mask + 1), i = j % (mask + 1);
    const int held = shape < 8 || (shape & 1);
    const char c = held ? (s == 0 ? r[0] : r[1]) : r[s % (int)strlen(r)];
    return c == 'D' ? mask - i : c == 'U' ? i : c == 'L' ? 0 : mask;
}

static void test_shapes(int ym) {
    const int mask = ym ? 31 : 15, div = ym ? 1 : 2;
    int bad = 0, bad_silent = 0, bad_jump = 0;
    for (int shape = 0; shape < 16; ++shape) {
        AyPsg a, b;
        ay_psg_reset(&a, ym, 0);
        ay_psg_write(&a, 11, 1);
        ay_psg_write(&a, 12, 0);
        ay_psg_write(&a, 13, shape);
        b = a;
        for (int j = 0; j < 4 * (mask + 1); ++j) {
            if (ay_psg_env_level(&a) != shape_level(shape, j, mask)) ++bad;
            if (ay_psg_env_level(&b) != shape_level(shape, j, mask)) ++bad_silent;
            ay_psg_render_mix(&a, (uint32_t)div, g_mix, NULL);
            ay_psg_run(&b, (uint32_t)div, NULL, NULL);
        }
        /* one silent jump of k envelope steps lands on the table's level */
        static const int jumps[] = {1, 7, 31, 32, 33, 63, 64, 65, 100, 1000, 4999};
        for (size_t i = 0; i < sizeof jumps / sizeof jumps[0]; ++i) {
            AyPsg c;
            ay_psg_reset(&c, ym, 0);
            ay_psg_write(&c, 11, 1);
            ay_psg_write(&c, 13, shape);
            ay_psg_run(&c, (uint32_t)(jumps[i] * div), NULL, NULL);
            if (ay_psg_env_level(&c) != shape_level(shape, jumps[i], mask)) ++bad_jump;
        }
    }
    EXPECT(bad == 0, "%s: all 16 shapes follow the table over 4 ramps when stepped (%d levels differ)", ym ? "YM" : "AY", bad);
    EXPECT(bad_silent == 0, "%s: ... and when advanced silently (%d differ)", ym ? "YM" : "AY", bad_silent);
    EXPECT(bad_jump == 0, "%s: ... and after single silent jumps of 1..4999 envelope steps (%d differ)", ym ? "YM" : "AY", bad_jump);
    /* AY and YM ramps take the same time: 32 EP steps (AY 16 levels of 2 EP, YM 32 levels of EP). */
    AyPsg c;
    ay_psg_reset(&c, ym, 0);
    ay_psg_write(&c, 11, 3);
    ay_psg_write(&c, 13, 8);
    int changes = 0, prev = ay_psg_env_level(&c), restart_at = -1;
    for (int s = 1; s <= 3 * 32 * 2; ++s) {
        ay_psg_render_mix(&c, 1, g_mix, NULL);
        const int l = ay_psg_env_level(&c);
        if (l != prev) ++changes;
        if (l == mask && prev != mask && restart_at < 0) restart_at = s;
        prev = l;
    }
    EXPECT(restart_at == 32 * 3 && changes == 2 * (mask + 1), "%s: a saw ramp at EP 3 lasts %d steps (want 96), %d level changes",
           ym ? "YM" : "AY", restart_at, changes);
}

/* Random register sets and step counts: silent advance = stepping, state for state (both mixes included). */
static void test_silent_random(int ym, int stereo) {
    uint32_t seed = ym ? 0x9E3779B9u : stereo ? 0x2545F491u : 0x12345678u;
#define RND() (seed ^= seed << 13, seed ^= seed >> 17, seed ^= seed << 5, seed)
    int bad = 0, trials = 0;
    for (int t = 0; t < 300; ++t) {
        AyPsg a, b;
        ay_psg_reset(&a, ym, stereo);
        b = a;
        for (int seg = 0; seg < 6; ++seg) {
            const int writes = (int)(RND() % 6);
            for (int w = 0; w < writes; ++w) {
                const int reg = (int)(RND() % 14);
                int val = (int)(RND() & 0xFF);
                if (reg < 6 && (RND() & 1)) val &= 3;            /* short tone periods */
                if (reg == 6 && (RND() & 1)) val &= 1;           /* noise period 0/1 */
                if ((reg == 11 || reg == 12) && (RND() & 1)) val &= reg == 11 ? 3 : 0;
                if (reg == 13) val &= 15;
                ay_psg_write(&a, reg, val);
                ay_psg_write(&b, reg, val);
            }
            if (RND() & 1) {
                const int m = (int)(RND() & 7);
                ay_psg_set_mute(&a, m);
                ay_psg_set_mute(&b, m);
            }
            const uint32_t n = 1 + RND() % 20000;
            ay_psg_render_mix(&a, n, g_mix, NULL);
            ay_psg_run(&b, n, NULL, NULL);
            ++trials;
            if (!ay_psg_same(&a, &b)) ++bad;
        }
    }
#undef RND
    EXPECT(bad == 0, "%s%s: silent advance = stepping after %d random register sets and step counts (%d differ)",
           ym ? "YM" : "AY", stereo ? " stereo" : "", trials, bad);
}

static void test_chip(void) {
    printf("chip core\n");
    for (int ym = 0; ym <= 1; ++ym) {
        const char* name = ym ? "YM" : "AY";
        static const int periods[] = {0, 1, 2, 3, 252, 285, 1000, 4095};
        for (size_t i = 0; i < sizeof periods / sizeof periods[0]; ++i) tone_edges(ym, periods[i]);
        /* Period 0 = period 1 for tone and noise; keep both envelopes at the same period. */
        AyPsg z, o;
        ay_psg_reset(&z, ym, 0);
        ay_psg_reset(&o, ym, 0);
        static const uint8_t regs[] = {7, 0x00, 8, 0x1F, 9, 0x0F, 10, 0x10, 13, 14};
        for (size_t i = 0; i < sizeof regs; i += 2) {
            ay_psg_write(&z, regs[i], regs[i + 1]);
            ay_psg_write(&o, regs[i], regs[i + 1]);
        }
        for (int r = 0; r <= 4; r += 2) ay_psg_write(&o, r, 1);
        ay_psg_write(&o, 6, 1);
        static int32_t mz[5000], mo[5000];
        ay_psg_render_mix(&z, 5000, mz, NULL);
        ay_psg_render_mix(&o, 5000, mo, NULL);
        EXPECT(!memcmp(mz, mo, sizeof mz) && z.lfsr == o.lfsr && ay_psg_env_level(&z) == ay_psg_env_level(&o),
               "%s: period 0 acts as 1 (tone and noise)", name);
        /* R11/R12 = 0 clocks one envelope step per clock/8: half EP=1 on AY, equal on YM. */
        AyPsg e0, e1;
        ay_psg_reset(&e0, ym, 0);
        ay_psg_reset(&e1, ym, 0);
        for (int i = 0; i < 2; ++i) {
            AyPsg* e = i ? &e1 : &e0;
            ay_psg_write(e, 7, 0x3F);
            ay_psg_write(e, 8, 0x10);
            ay_psg_write(e, 11, i);
            ay_psg_write(e, 13, 8);
        }
        int32_t zero_envelope[32], one_envelope[32];
        ay_psg_render_mix(&e0, 32, zero_envelope, NULL);
        ay_psg_render_mix(&e1, 32, one_envelope, NULL);
        const int mask = ym ? 31 : 15, divider = ym ? 1 : 2;
        for (int n = 1; n <= 32; ++n) {
            EXPECT(zero_envelope[n - 1] == ay_psg_amplitude(ym, (mask - n) & mask),
                   "%s: zero-period envelope DAC after %d steps", name, n);
            EXPECT(one_envelope[n - 1] == ay_psg_amplitude(ym, (mask - n / divider) & mask),
                   "%s: period-one envelope DAC after %d steps", name, n);
        }
        /* noise: LFSR seed 1, one step per 2 NP steps, = the reference recurrence; closed form = stepping */
        AyPsg nz;
        ay_psg_reset(&nz, ym, 0);
        ay_psg_write(&nz, 6, 3);
        uint32_t x = 1;
        int lfsr_bad = 0;
        for (int k = 0; k < 2000; ++k) {
            ay_psg_render_mix(&nz, 6, g_mix, NULL);
            x = lfsr_ref(x);
            if (nz.lfsr != x) ++lfsr_bad;
        }
        EXPECT(lfsr_bad == 0, "%s: noise LFSR = bit 0 ^ bit 3 into bit 16, one step per 2 NP steps (%d differ)", name, lfsr_bad);
        if (!ym) {
            uint32_t s = 1, period = 0;
            int closed_bad = 0;
            for (uint32_t k = 1; k <= 131071; ++k) {
                s = lfsr_ref(s);
                if (k % 997 == 0 && ay_psg_lfsr_advance(1, k) != s) ++closed_bad;
                if (s == 1 && !period) period = k;
            }
            EXPECT(period == 131071, "LFSR period 2^17 - 1 (got %u)", period);
            EXPECT(closed_bad == 0 && ay_psg_lfsr_advance(0x1ACE5, 100000) == ay_psg_lfsr_advance(ay_psg_lfsr_advance(0x1ACE5, 99986), 14),
                   "LFSR closed form = stepping (%d differ)", closed_bad);
        }
        test_shapes(ym);
        /* R13 restarts, also with an unchanged value and while holding */
        AyPsg e;
        ay_psg_reset(&e, ym, 0);
        ay_psg_write(&e, 11, 5);
        ay_psg_write(&e, 13, 8);
        ay_psg_render_mix(&e, 77, g_mix, NULL);
        const int mid = ay_psg_env_level(&e);
        ay_psg_write(&e, 13, 8);
        EXPECT(mid != e.env_mask && ay_psg_env_level(&e) == e.env_mask && e.env_count == 0,
               "%s: rewriting R13 restarts the envelope (level %d -> %d)", name, mid, ay_psg_env_level(&e));
        ay_psg_write(&e, 13, 13);
        ay_psg_run(&e, 100000, NULL, NULL);
        const int held = ay_psg_env_level(&e), holding = e.env_holding;
        ay_psg_write(&e, 13, 13);
        EXPECT(holding && held == e.env_mask && ay_psg_env_level(&e) == 0 && !e.env_holding,
               "%s: a held /‾‾ restarts from 0 on R13 (%d -> %d)", name, held, ay_psg_env_level(&e));
        /* a period shortened below its counter flips on the next step */
        AyPsg t;
        ay_psg_reset(&t, ym, 0);
        ay_psg_write(&t, 1, 1); /* TP 256 */
        ay_psg_run(&t, 100, NULL, NULL);
        ay_psg_write(&t, 0, 50);
        ay_psg_write(&t, 1, 0);
        const int before = t.tone_out[0];
        ay_psg_render_mix(&t, 1, g_mix, NULL);
        EXPECT(t.tone_out[0] != before && t.tone_count[0] == 0, "%s: tone period shortened below the counter flips next step", name);
        /* DAC scale and mute */
        AyPsg d;
        ay_psg_reset(&d, ym, 0);
        ay_psg_write(&d, 7, 0x3F);
        for (int c = 0; c < 3; ++c) ay_psg_write(&d, 8 + c, 15);
        const int full = ay_psg_mix(&d, 0);
        ay_psg_set_mute(&d, 2);
        const int muted = ay_psg_mix(&d, 0);
        EXPECT(full == 3 * AY_AMP && muted == 2 * AY_AMP && ay_psg_mix(&d, 1) == muted && ay_psg_amplitude(ym, 0) == 0,
               "%s: three channels at 15 = %d, channel B muted = %d, both sides the same mono mix", name, full, muted);
        if (ym) {
            ay_psg_set_mute(&d, 0);
            ay_psg_write(&d, 8, 7);
            EXPECT(ay_psg_channel_level(&d, 0) == 15, "YM: fixed volume V plays level 2V + 1");
        }
        test_silent_random(ym, 0);
    }
    /* The CPC's stereo socket: left A + B, right B + C, each channel at the same weight; mute on both sides. */
    AyPsg s;
    ay_psg_reset(&s, 0, 1);
    ay_psg_write(&s, 7, 0x3F);
    ay_psg_write(&s, 8, 15);
    ay_psg_write(&s, 9, 10);
    ay_psg_write(&s, 10, 5);
    const int a = ay_psg_amplitude(0, 15), b = ay_psg_amplitude(0, 10), c = ay_psg_amplitude(0, 5);
    int ok = ay_psg_mix(&s, 0) == a + b && ay_psg_mix(&s, 1) == b + c;
    ay_psg_set_mute(&s, 2);
    ok = ok && ay_psg_mix(&s, 0) == a && ay_psg_mix(&s, 1) == c;
    ay_psg_set_mute(&s, 5);
    ok = ok && ay_psg_mix(&s, 0) == b && ay_psg_mix(&s, 1) == b;
    EXPECT(ok, "stereo: left = A + B, right = B + C, a muted channel leaves both sides");
    static int32_t sl[4096], sr[4096];
    /* Three tones under way, every step: each side is exactly its channels' sum (an independent chip stepped alongside
     * gives the channel levels). */
    AyPsg u, w;
    ay_psg_reset(&u, 0, 1);
    ay_psg_reset(&w, 0, 1);
    static const uint8_t init[][2] = {{7, 0x38}, {8, 15}, {9, 10}, {10, 5}, {0, 7}, {2, 11}, {4, 13}};
    for (size_t i = 0; i < sizeof init / sizeof init[0]; ++i) {
        ay_psg_write(&u, init[i][0], init[i][1]);
        ay_psg_write(&w, init[i][0], init[i][1]);
    }
    ay_psg_render_mix(&u, 4096, sl, sr);
    int side_bad = 0;
    for (int i = 0; i < 4096; ++i) {
        ay_psg_render_mix(&w, 1, NULL, NULL);
        const int la = w.amp[ay_psg_channel_level(&w, 0)], lb = w.amp[ay_psg_channel_level(&w, 1)],
                  lc = w.amp[ay_psg_channel_level(&w, 2)];
        if (sl[i] != la + lb || sr[i] != lb + lc) ++side_bad;
    }
    EXPECT(side_bad == 0, "stereo: three tones step by step: every step's left = A + B, right = B + C (%d differ)", side_bad);
    test_silent_random(0, 1);
}

/* ---- song-level: silent advance = stepping; audio -------------------------------------------- */

static void test_silent_song(void) {
    printf("silent advance on the song\n");
    for (int v = 0; v < AY_VARIANTS; ++v) {
        AyMachine* a = ay_machine_create(RATE, v);
        AyMachine* b = ay_machine_create(RATE, v);
        int mode, bad = 0, first = -1;
        ay_machine_start(a, &g_song[v], 0, 0, AY_SEEK_TICKS, &mode);
        ay_machine_start(b, &g_song[v], 0, 0, AY_SEEK_TICKS, &mode);
        for (int t = 0; t < AY_TEST_TICKS; ++t) {
            ay_machine_tick(a, g_out);
            ay_machine_tick_silent(b);
            if (!ay_psg_same(ay_machine_chip(a), ay_machine_chip(b)) || ay_machine_steps(a) != ay_machine_steps(b)) {
                if (first < 0) first = t;
                ++bad;
            }
        }
        EXPECT(bad == 0, "%s: chip state after every one of %d ticks: silent = stepped (%d differ, first %d)", KEY[v],
               AY_TEST_TICKS, bad, first);
        ay_machine_destroy(a);
        ay_machine_destroy(b);
    }
}

static void test_audio(const char* dir) {
    printf("audio\n");
    for (int v = 0; v < AY_VARIANTS; ++v) {
        const int ticks = (int)(SECONDS * tick_hz(v));
        uint64_t hash[2] = {0, 0};
        for (int pass = 0; pass < 2; ++pass) {
            AyMachine* m = ay_machine_create(RATE, v);
            int mode;
            ay_machine_start(m, &g_song[v], 0, 0, AY_SEEK_TICKS, &mode);
            float* buf;
            const double t0 = now_us();
            int frames = 0, step_bad = 0, frame_bad = 0;
            buf = (float*)malloc((size_t)ticks * AYM_MAX_TICK_FRAMES * 2 * sizeof(float));
            for (int t = 0; t < ticks; ++t) {
                const int n = ay_machine_tick(m, buf + 2 * frames);
                const uint32_t steps = ay_machine_steps(m), per = 8 * ay_variants[v].ay_div;
                const uint32_t lo = ay_variants[v].frame_div / per;
                if (steps != lo && steps != lo + 1) ++step_bad;
                if (n < frames_low(v, RATE) || n > frames_high(v, RATE)) ++frame_bad;
                frames += n;
            }
            const double us = (now_us() - t0) / ticks;
            hash[pass] = fnv(0xCBF29CE484222325ull, buf, (size_t)frames * 2 * sizeof(float));
            if (pass == 0) {
                const double r = rms_mono(buf, frames, 2), r_right = rms_mono(buf + 1, frames, 2);
                double pk = 0;
                int differ = 0;
                for (int i = 0; i < 2 * frames; ++i)
                    if (fabs(buf[i]) > pk) pk = fabs(buf[i]);
                for (int i = 0; i < frames; ++i)
                    if (buf[2 * i] != buf[2 * i + 1]) ++differ;
                EXPECT(step_bad == 0, "%s: steps per tick from exact accumulation (%d off)", KEY[v], step_bad);
                EXPECT(frame_bad == 0, "%s: %d-%d frames per tick at 48 kHz (%d off)", KEY[v], frames_low(v, RATE),
                       frames_high(v, RATE), frame_bad);
                const double want = ticks * (double)RATE / tick_hz(v);
                EXPECT(fabs(frames - want) <= 2, "%s: %d ticks = %d frames (%.1f)", KEY[v], ticks, frames, want);
                if (ay_variants[v].stereo)
                    EXPECT(differ > frames / 10 && r_right > 0.02, "%s: stereo socket: the sides differ (%d of %d frames)",
                           KEY[v], differ, frames);
                else
                    EXPECT(differ == 0, "%s: mono mix on both channels (%d frames differ)", KEY[v], differ);
                EXPECT(r > 0.02, "%s: non-silent (rms %.4f, right %.4f)", KEY[v], r, r_right);
                EXPECT(pk < 0.999, "%s: no clipping (peak %.4f)", KEY[v], pk);
                char path[512];
                snprintf(path, sizeof path, "%s/ay_%s.wav", dir, KEY[v]);
                write_wav(path, buf, frames);
                printf("  %s: %d s rms %.4f peak %.4f, %.1f us per tick (%.0fx realtime)\n", KEY[v], SECONDS, r, pk, us,
                       1e6 / tick_hz(v) / us);
            }
            free(buf);
            ay_machine_destroy(m);
        }
        EXPECT(hash[0] == hash[1], "%s: bit-identical on a second start", KEY[v]);
    }
    /* 96 kHz: every tick fits the host buffer */
    for (int v = 0; v < AY_VARIANTS; ++v) {
        AyMachine* m = ay_machine_create(96000, v);
        int mode, worst = 0, least = 1 << 30;
        ay_machine_start(m, &g_song[v], 0, 0, AY_SEEK_TICKS, &mode);
        for (int t = 0; t < 200; ++t) {
            const int n = ay_machine_tick(m, g_out);
            if (n > worst) worst = n;
            if (n < least) least = n;
        }
        EXPECT(worst <= (int)AYM_MAX_TICK_FRAMES && worst <= frames_high(v, 96000) && least >= frames_low(v, 96000),
               "%s at 96 kHz: %d..%d frames per tick (want %d..%d, <= %u)", KEY[v], least, worst, frames_low(v, 96000),
               frames_high(v, 96000), AYM_MAX_TICK_FRAMES);
        ay_machine_destroy(m);
    }
    EXPECT(ay_machine_create(RATE, AY_VARIANTS) == NULL && ay_machine_create(RATE, -1) == NULL &&
               ay_machine_create(96001, 0) == NULL && ay_machine_create(7999, 0) == NULL,
           "bad variant / rates rejected");
}

/* ---- loop passes ----------------------------------------------------------------------------- */

static void test_loop(void) {
    printf("loop passes\n");
    enum { MAX_PASS = 8192 };
    static int pos[3][MAX_PASS];
    for (int v = 0; v < AY_VARIANTS; ++v) {
        int len[3] = {0};
        AyMachine* m = ay_machine_create(RATE, v);
        int mode, o, r, t, pass = -1;
        ay_machine_start(m, &g_song[v], 0, 0, AY_SEEK_TICKS, &mode);
        for (int k = 0; k < 4 * MAX_PASS && pass < 3; ++k) {
            ay_machine_position(m, &o, &r, &t);
            if (o == g_song[v].info.loop && r == 0 && t == 0) ++pass;
            ay_machine_tick_silent(m);
            if (pass < 0 || pass >= 3 || len[pass] >= MAX_PASS) continue;
            pos[pass][len[pass]++] = o << 16 | r << 8 | t;
        }
        EXPECT(pass == 3 && len[0] == len[1] && len[1] == len[2] && len[0] < MAX_PASS, "%s: passes of %d, %d, %d ticks",
               KEY[v], len[0], len[1], len[2]);
        EXPECT(!memcmp(pos[0], pos[1], sizeof(int) * (size_t)len[0]) && !memcmp(pos[1], pos[2], sizeof(int) * (size_t)len[0]),
               "%s: every pass steps through the same (order, row, tick) positions", KEY[v]);
        EXPECT(fabs(len[0] / tick_hz(v) * 44100.0 - AY_TEST_VGM[v].loop_samples) < 2.0,
               "%s: = the VGM loop (%d ticks, %u samples)", KEY[v], len[0], AY_TEST_VGM[v].loop_samples);
        printf("  %s: %d ticks per pass (%.2f s)\n", KEY[v], len[0], len[0] / tick_hz(v));
        ay_machine_destroy(m);
    }
}

/* ---- seek ------------------------------------------------------------------------------------ */

static void test_seek(void) {
    printf("seek\n");
    static int32_t ma[8192], ms[8192], mar[8192], msr[8192];
    for (int v = 0; v < AY_VARIANTS; ++v) {
        const AyTestTrace* ref = &AY_TEST_TRACE[v];
        /* targets: row starts spread over the trace, each first reached at reference tick k */
        int targets = 0;
        for (int part = 1; part <= 8; ++part) {
            int k = (int)((AY_TEST_TICKS - 260) * part / 8);
            while (k < AY_TEST_TICKS - 250 && ref->positions[3 * k + 2] != 0) ++k;
            const int o = ref->positions[3 * k], r = ref->positions[3 * k + 1];
            k = ref_row_start(ref, o, r);
            if (k < 1) continue;
            ++targets;
            AyMachine* a = ay_machine_create(RATE, v);
            AyMachine* s = ay_machine_create(RATE, v);
            int mode = -1;
            ay_machine_start(a, &g_song[v], 0, 0, AY_SEEK_TICKS, &mode);
            for (int t = 0; t < k; ++t) ay_machine_tick(a, g_out);
            const double t0 = now_us();
            EXPECT(ay_machine_start(s, &g_song[v], o, r, AY_SEEK_TICKS, &mode) == AYM_OK && mode == AYM_SEEK_FROM_START,
                   "%s: seek %d/%d", KEY[v], o, r);
            const double us = now_us() - t0;
            EXPECT(pos_is(s, ref, k), "%s: lands on %d/%d tick 0", KEY[v], o, r);
            EXPECT(ay_psg_same(ay_machine_chip(a), ay_machine_chip(s)), "%s %d/%d: chip state = straight play's after %d ticks",
                   KEY[v], o, r, k);
            /* The following 200 ticks: writes, positions, states and every step's output before blip_buf. */
            int bad = 0, out_bad = 0, path_bad = 0;
            for (int j = 0; j < 200; ++j) {
                AyPsg ca = *ay_machine_chip(a), cs = *ay_machine_chip(s);
                if (!pos_is(s, ref, k + j)) ++bad;
                ay_machine_tick(a, g_out);
                ay_machine_tick(s, g_out);
                const AyPlayer* pa = ay_machine_player(a);
                if (!same(ay_machine_player(s), ref, k + j) || !same(pa, ref, k + j)) ++bad;
                const uint32_t steps = ay_machine_steps(a);
                if (steps != ay_machine_steps(s) || steps > 8192) {
                    ++out_bad;
                    continue;
                }
                for (int i = 0; i < pa->writes; ++i) {
                    ay_psg_write(&ca, pa->write[i].reg, pa->write[i].val);
                    ay_psg_write(&cs, pa->write[i].reg, pa->write[i].val);
                }
                ay_psg_render_mix(&ca, steps, ma, mar);
                ay_psg_render_mix(&cs, steps, ms, msr);
                if (memcmp(ma, ms, sizeof(int32_t) * steps) || memcmp(mar, msr, sizeof(int32_t) * steps)) ++out_bad;
                if (!ay_psg_same(&ca, ay_machine_chip(a)) || !ay_psg_same(ay_machine_chip(a), ay_machine_chip(s))) ++path_bad;
            }
            EXPECT(bad == 0, "%s seek %d/%d: 200 ticks of writes and positions = straight play (%d differ)", KEY[v], o, r, bad);
            EXPECT(out_bad == 0, "%s seek %d/%d: every step's output = straight play's (%d ticks differ)", KEY[v], o, r, out_bad);
            EXPECT(path_bad == 0, "%s seek %d/%d: states stay equal (%d differ)", KEY[v], o, r, path_bad);
            printf("  %s %d/%d: %d ticks fast-forwarded in %.0f us\n", KEY[v], o, r, k, us);
            ay_machine_destroy(a);
            ay_machine_destroy(s);
        }
        EXPECT(targets >= 4, "%s: %d seek targets", KEY[v], targets);
        /* Order fallback: a budget below the target's distance resets and places the player at the order's row 0 (with
         * its start speed) and replays the earlier rows with the chip silently clocked. The target: a row reached in
         * the trace by playing its order from row 0 row by row. Its result must equal a machine placed at (o, 0) by
         * the same fallback and ticked audibly to (o, r): positions, writes and chip states, landing and after. */
        AyMachine* m = ay_machine_create(RATE, v);
        int o = -1, r = -1, run = -1, po = -1, pr = -1;
        for (int t = AY_TEST_TICKS / 3; t < AY_TEST_TICKS && o < 0; ++t) {
            if (ref->positions[3 * t + 2] != 0) continue;
            const int co = ref->positions[3 * t], cr = ref->positions[3 * t + 1];
            run = cr == 0 ? 0 : (run >= 0 && co == po && cr == pr + 1) ? run + 1 : -1;
            po = co, pr = cr;
            if (run >= 2) o = co, r = cr;
        }
        EXPECT(o >= 0, "%s: a row played in order from its order's start", KEY[v]);
        if (o < 0) o = 0, r = 0;
        int mode = -1;
        EXPECT(ay_machine_start(m, &g_song[v], o, r, 1, &mode) == AYM_OK && mode == AYM_SEEK_FROM_ORDER,
               "%s: budget 1 to %d/%d: order fallback", KEY[v], o, r);
        int lo, lr, lt;
        ay_machine_position(m, &lo, &lr, &lt);
        EXPECT(lo == o && lr == r && lt == 0, "%s: fallback lands on %d/%d (%d/%d/%d)", KEY[v], o, r, lo, lr, lt);
        AyMachine* b = ay_machine_create(RATE, v);
        ay_machine_start(b, &g_song[v], o, 0, 0, &mode);
        int guard = 0;
        for (ay_machine_position(b, &lo, &lr, &lt); !(lo == o && lr == r && lt == 0) && guard < 256 * 256; ++guard) {
            ay_machine_tick(b, g_out);
            ay_machine_position(b, &lo, &lr, &lt);
        }
        int bad = !ay_psg_same(ay_machine_chip(m), ay_machine_chip(b));
        for (int j = 0; j < 100; ++j) {
            ay_machine_tick(m, g_out);
            ay_machine_tick(b, g_out);
            const AyPlayer *pm = ay_machine_player(m), *pb = ay_machine_player(b);
            if (pm->writes != pb->writes || memcmp(pm->write, pb->write, sizeof pm->write[0] * (size_t)pm->writes) ||
                pm->order != pb->order || pm->row != pb->row || pm->tick != pb->tick ||
                !ay_psg_same(ay_machine_chip(m), ay_machine_chip(b)))
                ++bad;
        }
        EXPECT(bad == 0, "%s: fallback to %d/%d = the order played from its start (%d ticks differ)", KEY[v], o, r, bad);
        ay_machine_destroy(b);
        EXPECT(ay_machine_start(m, &g_song[v], g_song[v].info.orders, 0, AY_SEEK_TICKS, &mode) == AYM_ERR_IMAGE,
               "%s: order past the song rejected", KEY[v]);
        EXPECT(ay_machine_start(m, &g_song[v], 0, g_song[v].info.rows[0], AY_SEEK_TICKS, &mode) == AYM_ERR_IMAGE,
               "%s: row past the order rejected", KEY[v]);
        EXPECT(ay_machine_start(m, &g_song[(v + 1) % AY_VARIANTS], 0, 0, AY_SEEK_TICKS, &mode) == AYM_ERR_IMAGE,
               "%s: another variant's image rejected", KEY[v]);
        ay_machine_destroy(m);
    }
}

/* ---- live edits ------------------------------------------------------------------------------ */

/* Plays SONG[v] from the start, applying alt[t % 2] before tick t (from tick `from`; every tick or once), comparing with
 * `ref`. */
static int play_applying(int v, const AyImage* alt0, const AyImage* alt1, int from, int every, const AyTestTrace* ref,
                         int* first) {
    AyMachine* m = ay_machine_create(RATE, v);
    int mode, bad = 0;
    ay_machine_start(m, &g_song[v], 0, 0, AY_SEEK_TICKS, &mode);
    *first = -1;
    for (int t = 0; t < (int)ref->ticks; ++t) {
        if (t >= from && (every || t == from)) ay_machine_apply_image(m, (t % 2) ? alt1 : alt0);
        if (!pos_is(m, ref, t)) ++bad;
        ay_machine_tick_silent(m);
        if (!same(ay_machine_player(m), ref, t)) {
            if (*first < 0) *first = t;
            ++bad;
        }
    }
    ay_machine_destroy(m);
    return bad;
}

static void test_live_edits(void) {
    printf("live edits\n");
    for (int v = 0; v < AY_VARIANTS; ++v) {
        int first;
        {
            /* the re-laid-out image plays straight like the song */
            AyMachine* m = ay_machine_create(RATE, v);
            int mode, bad = 0;
            ay_machine_start(m, &g_relayout[v], 0, 0, AY_SEEK_TICKS, &mode);
            for (int t = 0; t < AY_TEST_TICKS; ++t) {
                if (!pos_is(m, &AY_TEST_TRACE[v], t)) ++bad;
                ay_machine_tick_silent(m);
                if (!same(ay_machine_player(m), &AY_TEST_TRACE[v], t)) ++bad;
            }
            EXPECT(bad == 0, "%s: re-laid-out image straight = reference (%d differ)", KEY[v], bad);
            ay_machine_destroy(m);
        }
        int bad = play_applying(v, &g_song[v], &g_song[v], 0, 1, &AY_TEST_TRACE[v], &first);
        EXPECT(bad == 0, "%s: same image applied before every tick = reference (%d differ, first %d)", KEY[v], bad, first);
        bad = play_applying(v, &g_relayout[v], &g_song[v], 0, 1, &AY_TEST_TRACE[v], &first);
        EXPECT(bad == 0, "%s: re-laid-out and original alternately before every tick = reference (%d differ, first %d)",
               KEY[v], bad, first);
        bad = play_applying(v, &g_note[v], &g_note[v], AY_TEST_NOTE_EDIT_APPLY_TICK, 0, &AY_TEST_NOTE_EDIT_TRACE[v], &first);
        EXPECT(bad == 0, "%s: note edit (order %d row %d channel %d) applied before tick %d = reference (%d differ, first %d)",
               KEY[v], AY_TEST_NOTE_EDIT_ORDER, AY_TEST_NOTE_EDIT_ROW, AY_TEST_NOTE_EDIT_CHANNEL, AY_TEST_NOTE_EDIT_APPLY_TICK,
               bad, first);
        bad = play_applying(v, &g_tempo[v], &g_tempo[v], AY_TEST_TEMPO_EDIT_APPLY_TICK, 0, &AY_TEST_TEMPO_EDIT_TRACE[v], &first);
        EXPECT(bad == 0, "%s: tempo edit (order %d row %d) applied before tick %d = reference (%d differ, first %d)", KEY[v],
               AY_TEST_TEMPO_EDIT_ORDER, AY_TEST_TEMPO_EDIT_ROW, AY_TEST_TEMPO_EDIT_APPLY_TICK, bad, first);
        bad = play_applying(v, &g_cut[v], &g_cut[v], AY_TEST_CUT_APPLY_TICK, 0, &AY_TEST_CUT_TRACE[v], &first);
        EXPECT(bad == 0, "%s: song cut under the playhead before tick %d = reference (%d differ, first %d)", KEY[v],
               AY_TEST_CUT_APPLY_TICK, bad, first);
        /* The variant byte is the engine's: the song with only byte 5 changed, applied live, keeps this variant's
         * periods. */
        memcpy(&g_bad, &g_song[v], sizeof g_bad);
        g_bad.bytes[AY_H_VARIANT] = (uint8_t)((v + 1) % AY_VARIANTS);
        EXPECT(ay_load_image(&g_bad, -1) == 0, "%s: the song as another variant loads (any variant)", KEY[v]);
        bad = play_applying(v, &g_bad, &g_bad, 10, 0, &AY_TEST_TRACE[v], &first);
        EXPECT(bad == 0, "%s: a changed variant byte is ignored (%d differ, first %d)", KEY[v], bad, first);
        AyMachine* m = ay_machine_create(RATE, v);
        int mode;
        ay_machine_start(m, &g_song[v], 0, 0, AY_SEEK_TICKS, &mode);
        double worst = 0, total = 0;
        for (int k = 0; k < 1500; ++k) {
            const double t0 = now_us();
            ay_machine_apply_image(m, (k & 1) ? &g_relayout[v] : &g_song[v]);
            const double us = now_us() - t0;
            total += us;
            if (us > worst) worst = us;
            ay_machine_tick(m, g_out);
        }
        printf("  %s apply_image: %.2f us mean, worst %.1f us\n", KEY[v], total / 1500, worst);
        /* ignored while auditioning */
        EXPECT(ay_machine_audition(m, &g_song[v], 0, A4, SLOT_SQUARE, 0) == AYM_OK, "%s: audition", KEY[v]);
        ay_machine_tick(m, g_out);
        const AyPlayer before = *ay_machine_player(m);
        ay_machine_apply_image(m, &g_cut[v]);
        EXPECT(!memcmp(&before, ay_machine_player(m), sizeof before), "%s: apply_image ignored while auditioning", KEY[v]);
        ay_machine_destroy(m);
    }
}

/* ---- audition -------------------------------------------------------------------------------- */

/* An instrument slot whose volume envelope has a release point (length, release, last value), or 0. */
static int release_slot(const AyImage* image, int* length, int* release, int* last) {
    for (int s = 1; s <= image->info.instruments; ++s) {
        const uint8_t* rec = ay_instrument(image, s);
        if (!rec) continue;
        const int off = ay_u16(rec + AY_I_ENV_VOLUME);
        if (off == AY_NO_ENVELOPE) continue;
        const uint8_t* e = image->bytes + AY_OFF_DATA + off;
        if (ay_u16(e + 4) == AY_NO_ENVELOPE) continue;
        *length = ay_u16(e);
        *release = ay_u16(e + 4);
        *last = (int8_t)e[AY_ENV_HEADER + *length - 1];
        return s;
    }
    return 0;
}

static int undefined_slot(const AyImage* image) {
    for (int s = 1; s <= AY_MAX_INSTRUMENTS; ++s)
        if (!ay_instrument(image, s)) return s;
    return 0;
}

static void test_audition(const char* dir) {
    printf("audition\n");
    for (int v = 0; v < AY_VARIANTS; ++v) {
        const double clk = clock_hz(v);
        AyMachine* m = ay_machine_create(RATE, v);
        const int ticks = (int)(2 * tick_hz(v));
        for (int c = 0; c < AY_CHANNELS; ++c) {
            const double t0 = now_us();
            EXPECT(ay_machine_audition(m, &g_song[v], c, A4, SLOT_SQUARE, 0) == AYM_OK, "%s: A-4 on channel %d", KEY[v], c);
            const double setup = now_us() - t0;
            float* buf;
            const int frames = render(m, ticks, &buf);
            /* the side that carries channel c: on the CPC's socket C is right only */
            const int side = ay_variants[v].stereo && c == 2;
            const double hz = zero_cross_hz(buf + 2 * (RATE / 10) + side, frames - RATE / 10, 2);
            const double want = clk / (16.0 * ay_tone_period(v, A4 * 64));
            EXPECT(fabs(hz - want) < 0.3 && fabs(hz - 440.0) < 1.0, "%s channel %d: A-4 measured %.3f Hz (registers %.3f)",
                   KEY[v], c, hz, want);
            EXPECT(rms_mono(buf + 2 * (frames - RATE / 2) + side, RATE / 2, 2) >
                       0.5 * rms_mono(buf + 2 * (RATE / 2) + side, RATE / 2, 2),
                   "%s channel %d: held", KEY[v], c);
            if (ay_variants[v].stereo) {
                /* the socket: A left only, C right only (the other side exactly 0: no delta ever reaches it), B both,
                 * identical (both buffers receive the same deltas) */
                int only_left = 1, only_right = 1, equal = 1;
                for (int i = 0; i < frames; ++i) {
                    if (buf[2 * i + 1] != 0) only_left = 0;
                    if (buf[2 * i] != 0) only_right = 0;
                    if (buf[2 * i] != buf[2 * i + 1]) equal = 0;
                }
                if (c == 0) EXPECT(only_left, "%s: channel A soloed sounds on the left only", KEY[v]);
                if (c == 1) EXPECT(equal, "%s: channel B sounds on both sides, equally", KEY[v]);
                if (c == 2) EXPECT(only_right, "%s: channel C soloed sounds on the right only", KEY[v]);
            }
            const AyPsg* chip = ay_machine_chip(m);
            EXPECT(((chip->reg[7] >> c) & 1) == 0 && (chip->reg[7] & 0x3F & ~(1 << c)) == (0x3F & ~(1 << c)),
                   "%s channel %d: only its tone enabled (R7 %02X)", KEY[v], c, chip->reg[7]);
            if (c == 0) {
                EXPECT(ay_machine_release(m) == 1, "%s: release phase", KEY[v]);
                float tail[AYM_MAX_TICK_FRAMES * 2 * 4];
                int tail_frames = 0, n, released = 0;
                while ((n = ay_machine_tick(m, tail + 2 * tail_frames)) > 0 && released < 4) {
                    tail_frames += n;
                    ++released;
                }
                EXPECT(n == 0 && released == 0, "%s: no release envelope: silenced at key-off (%d ticks)", KEY[v], released);
                char path[512];
                snprintf(path, sizeof path, "%s/ay_%s_tone.wav", dir, KEY[v]);
                write_wav(path, buf, frames);
                printf("  %s: A-4 %.3f Hz (%.3f from the registers), setup %.0f us\n", KEY[v], hz, want, setup);
            }
            free(buf);
        }
        /* fine +64 = A#4 */
        ay_machine_audition(m, &g_song[v], 1, A4, SLOT_SQUARE, 64);
        float* buf;
        int frames = render(m, ticks, &buf);
        const double up = zero_cross_hz(buf + 2 * (RATE / 10), frames - RATE / 10, 2);
        const double up_want = clk / (16.0 * ay_tone_period(v, A4 * 64 + 64));
        EXPECT(fabs(up - up_want) < 0.3 && fabs(up - 466.164) < 1.5, "%s: fine +64 = A#4 %.3f Hz (%.3f)", KEY[v], up, up_want);
        free(buf);
        /* the buzzer: the bare envelope (saw 8, J00) at the note's pitch: clock / (256 EP) */
        EXPECT(ay_machine_audition(m, &g_song[v], 2, A4, SLOT_BUZZER, 0) == AYM_OK, "%s: buzzer", KEY[v]);
        frames = render(m, ticks, &buf);
        const int ep = ay_envelope_period(v, A4 * 64, 0, 8);
        const int buzz_side = ay_variants[v].stereo; /* channel C */
        const double buzz = zero_cross_hz(buf + 2 * (RATE / 10) + buzz_side, frames - RATE / 10, 2),
                     buzz_want = clk / (256.0 * ep);
        const AyPsg* chip = ay_machine_chip(m);
        EXPECT(chip->reg[10] == 0x10 && chip->reg[13] == 8 && (chip->reg[11] | chip->reg[12] << 8) == ep,
               "%s: buzzer registers R10 %02X R13 %d EP %d", KEY[v], chip->reg[10], chip->reg[13], chip->reg[11] | chip->reg[12] << 8);
        EXPECT(fabs(buzz - buzz_want) < 0.5 && fabs(buzz - 440.0) < 440.0 * 0.04, "%s: buzzer %.3f Hz (registers %.3f)", KEY[v],
               buzz, buzz_want);
        free(buf);
        /* the synced buzzer: triangle 10, tone = EP x 32: one exact period */
        EXPECT(ay_machine_audition(m, &g_song[v], 0, A4, SLOT_SYNC_BUZZER, 0) == AYM_OK, "%s: synced buzzer", KEY[v]);
        frames = render(m, ticks, &buf);
        const int ept = ay_envelope_period(v, A4 * 64, 0, 10);
        const int tp = chip->reg[0] | (chip->reg[1] & 15) << 8;
        const double sync = zero_cross_hz(buf + 2 * (RATE / 10), frames - RATE / 10, 2), sync_want = clk / (512.0 * ept);
        EXPECT(tp == 32 * ept && fabs(sync - sync_want) < 0.5, "%s: synced buzzer TP %d = 32 EP (%d), %.3f Hz (%.3f)", KEY[v],
               tp, ept, sync, sync_want);
        free(buf);
        /* a volume envelope with a release point plays its tail */
        int length = 0, release = 0, last = 0;
        const int slot = release_slot(&g_song[v], &length, &release, &last);
        EXPECT(slot > 0, "%s: the test song has an instrument with a release point", KEY[v]);
        if (slot) {
            ay_machine_audition(m, &g_song[v], 0, 48, slot, 0);
            for (int t = 0; t < 10; ++t) ay_machine_tick(m, g_out);
            EXPECT(ay_machine_release(m) == 1, "%s: slot %d release phase", KEY[v], slot);
            int n, tail = 0;
            const int limit = (int)(AYM_RELEASE_SECONDS * tick_hz(v)) + 2;
            while ((n = ay_machine_tick(m, g_out)) > 0 && tail < limit) ++tail;
            const int want = last == 0 ? (length - 1 - release > 1 ? length - 1 - release : 1) : (int)(AYM_RELEASE_SECONDS * tick_hz(v));
            EXPECT(n == 0 && abs(tail - want) <= 1, "%s: slot %d (length %d, release %d, last %d): tail of %d ticks (want %d)",
                   KEY[v], slot, length, release, last, tail, want);
            EXPECT(ay_machine_release(m) == 0, "%s: released and ended: release() = 0", KEY[v]);
        }
        EXPECT(ay_machine_audition(m, &g_song[v], 0, A4, SLOT_SQUARE, 0) == AYM_OK && ay_machine_release(m) == 0,
               "%s: release before any tick: cut", KEY[v]);
        const int undef = undefined_slot(&g_song[v]);
        EXPECT(undef == 0 || ay_machine_audition(m, &g_song[v], 0, 48, undef, 0) == AYM_ERR_IMAGE, "%s: undefined slot %d rejected",
               KEY[v], undef);
        EXPECT(ay_machine_audition(m, &g_song[v], 0, 48, 0, 0) == AYM_ERR_IMAGE, "%s: slot 0 rejected", KEY[v]);
        EXPECT(ay_machine_audition(m, &g_song[v], 0, 96, SLOT_SQUARE, 0) == AYM_ERR_IMAGE, "%s: note 96 rejected", KEY[v]);
        EXPECT(ay_machine_audition(m, &g_song[v], 3, 48, SLOT_SQUARE, 0) == AYM_ERR_IMAGE, "%s: channel 3 rejected", KEY[v]);
        EXPECT(ay_machine_audition(m, &g_song[v], 0, 48, SLOT_SQUARE, 128) == AYM_ERR_IMAGE, "%s: fine 128 rejected", KEY[v]);
        EXPECT(ay_machine_audition(m, &g_song[(v + 1) % AY_VARIANTS], 0, 48, SLOT_SQUARE, 0) == AYM_ERR_IMAGE,
               "%s: another variant's image rejected", KEY[v]);
        EXPECT(ay_machine_tick(m, g_out) == 0, "%s: a rejected audition leaves the machine silent", KEY[v]);
        ay_machine_destroy(m);
    }
}

/* ---- mute ------------------------------------------------------------------------------------ */

static void test_mute(void) {
    printf("mute\n");
    for (int v = 0; v < AY_VARIANTS; ++v) {
        AyMachine* m = ay_machine_create(RATE, v);
        int mode, bad = 0;
        ay_machine_start(m, &g_song[v], 0, 0, AY_SEEK_TICKS, &mode);
        ay_machine_mute(m, 0, 1);
        ay_machine_mute(m, 2, 1);
        for (int t = 0; t < AY_TEST_TICKS; ++t) {
            ay_machine_tick(m, g_out);
            if (!same(ay_machine_player(m), &AY_TEST_TRACE[v], t)) ++bad;
        }
        EXPECT(bad == 0, "%s: muted channels leave the player's writes unchanged (%d differ)", KEY[v], bad);
        /* soloed channels sum to the mix, on each side (the CPC's left gets A + B, its right B + C; a solo of a channel
         * not on a side leaves that side exactly 0): blip_buf is linear up to its fixed-point rounding (the output shift
         * and the truncated high-pass feedback: under 2 LSB per render, so under 8 LSB for three solos against the mix) */
        const int ticks = (int)(10 * tick_hz(v));
        float* part[4];
        int frames[4];
        for (int s = 0; s < 4; ++s) {
            ay_machine_start(m, &g_song[v], 0, 0, AY_SEEK_TICKS, &mode);
            for (int c = 0; c < 3; ++c) ay_machine_mute(m, c, s < 3 && c != s);
            frames[s] = render(m, ticks, &part[s]);
        }
        double worst = 0;
        for (int i = 0; i < 2 * frames[3] && frames[0] == frames[3]; ++i) {
            const double d = fabs(part[0][i] + part[1][i] + part[2][i] - part[3][i]);
            if (d > worst) worst = d;
        }
        EXPECT(frames[0] == frames[3] && worst <= 8.0 / 32768, "%s: A + B + C soloed = the mix (worst %.1f LSB)", KEY[v],
               worst * 32768);
        int audible = 0;
        for (int s = 0; s < 3; ++s)
            if (rms_mono(part[s], frames[s], 2) > 0.003 || rms_mono(part[s] + 1, frames[s], 2) > 0.003) ++audible;
        EXPECT(audible == 3, "%s: every channel plays in the song (%d audible soloed)", KEY[v], audible);
        for (int s = 0; s < 4; ++s) free(part[s]);
        /* all muted from the start: exactly silent; unmuted mid-note: resumes */
        ay_machine_start(m, &g_song[v], 0, 0, AY_SEEK_TICKS, &mode);
        for (int c = 0; c < 3; ++c) ay_machine_mute(m, c, 1);
        float* buf;
        int n = render(m, 100, &buf);
        double pk = 0;
        for (int i = 0; i < 2 * n; ++i)
            if (fabs(buf[i]) > pk) pk = fabs(buf[i]);
        EXPECT(pk == 0, "%s: all channels muted: silent (peak %g)", KEY[v], pk);
        free(buf);
        for (int c = 0; c < 3; ++c) ay_machine_mute(m, c, 0);
        n = render(m, 100, &buf);
        EXPECT(rms_mono(buf, n, 2) > 0.01, "%s: unmuted: resumes mid-note (rms %.4f)", KEY[v], rms_mono(buf, n, 2));
        free(buf);
        ay_machine_mute(m, 1, 1);
        ay_machine_start(m, &g_song[v], 0, 0, AY_SEEK_TICKS, &mode);
        EXPECT(ay_machine_chip(m)->mute == 0, "%s: start clears mutes", KEY[v]);
        EXPECT(ay_machine_mute(m, 3, 1) == AYM_ERR_IMAGE && ay_machine_mute(m, -1, 1) == AYM_ERR_IMAGE, "%s: channel 3 rejected",
               KEY[v]);
        ay_machine_destroy(m);
    }
}

/* ---- VGM ------------------------------------------------------------------------------------- */

/* Commands from 0x100 to 0x66 (only A0, 61, 62, 63, 7x): their waits sum to the header's samples, and the loop point
 * starts with the R0-R12 dump. */
static int parse_vgm(const uint8_t* b, size_t size, uint32_t* waits, int* dump_ok) {
    size_t p = 0x100;
    *waits = 0;
    *dump_ok = 0;
    const uint32_t loop = rd32(b + 0x1C) ? 0x1C + rd32(b + 0x1C) : 0;
    if (loop) {
        *dump_ok = 1;
        for (int r = 0; r < 13; ++r)
            if (loop + 3 * r + 2 >= size || b[loop + 3 * r] != 0xA0 || b[loop + 3 * r + 1] != r) *dump_ok = 0;
    }
    while (p < size) {
        const int c = b[p];
        if (c == 0xA0) {
            if (b[p + 1] > 13) return 0;
            p += 3;
        } else if (c == 0x61) {
            *waits += b[p + 1] | b[p + 2] << 8;
            p += 3;
        } else if (c == 0x62 || c == 0x63) {
            *waits += c == 0x62 ? 735 : 882;
            ++p;
        } else if ((c & 0xF0) == 0x70) {
            *waits += (uint32_t)(c & 15) + 1;
            ++p;
        } else if (c == 0x66) {
            return p + 1 == size;
        } else {
            return 0;
        }
    }
    return 0;
}

static void test_vgm(const char* dir) {
    printf("VGM export\n");
    size_t cap = 1 << 22;
    uint8_t* buf = (uint8_t*)malloc(cap);
    static AyPlayer scratch;
    for (int v = 0; v < AY_VARIANTS; ++v) {
        const AyTestVgm* ref = &AY_TEST_VGM[v];
        AyVgmInfo info;
        const double t0 = now_us();
        const size_t size = ay_vgm_render(&g_song[v], AY_TEST_VGM_MAX_TICKS, &scratch, buf, cap, &info);
        const double us = now_us() - t0;
        EXPECT(size == ref->size && fnv(0xCBF29CE484222325ull, buf, size) == ref->fnv1a,
               "%s: VGM bytes = the reference player's (%zu vs %u bytes)", KEY[v], size, ref->size);
        EXPECT(info.ticks == ref->ticks && info.samples == ref->total_samples && info.loop_offset == ref->loop_offset &&
                   info.loop_samples == ref->loop_samples,
               "%s: VGM length and loop (%u ticks, loop at %u, %u of %u samples)", KEY[v], info.ticks, info.loop_offset,
               info.loop_samples, info.samples);
        const AyVariantSpec* s = &ay_variants[v];
        EXPECT(!memcmp(buf, "Vgm ", 4) && rd32(buf + 4) == size - 4 && rd32(buf + 8) == 0x171 && rd32(buf + 0x24) == s->vgm_rate &&
                   rd32(buf + 0x34) == 0xCC && rd32(buf + 0x74) == s->vgm_clock && buf[0x78] == s->vgm_type && buf[0x79] == 1 &&
                   buf[0x7A] == 0 && buf[0x7B] == 0 && rd32(buf + 0x18) == info.samples,
               "%s: header (clock %u, type %02X, flags %02X, rate %u)", KEY[v], rd32(buf + 0x74), buf[0x78], buf[0x79],
               rd32(buf + 0x24));
        uint32_t waits = 0;
        int dump_ok = 0;
        EXPECT(parse_vgm(buf, size, &waits, &dump_ok) && waits == info.samples && dump_ok,
               "%s: parses as AY writes and waits summing to %u samples (%u), loop point dumps R0-R12", KEY[v], info.samples,
               waits);
        EXPECT(buf[0x100] == 0xA0 && buf[0x101] == 7 && buf[0x102] == (s->io_bits | 0x3F), "%s: starts with the reset R7",
               KEY[v]);
        EXPECT(ay_vgm_render(&g_song[v], AY_TEST_VGM_MAX_TICKS, &scratch, buf, 1000, &info) == 0 && info.size == ref->size,
               "%s: too small a buffer reports 0 and the size needed", KEY[v]);
        char path[512];
        snprintf(path, sizeof path, "%s/ay_%s.vgm", dir, KEY[v]);
        ay_vgm_render(&g_song[v], AY_TEST_VGM_MAX_TICKS, &scratch, buf, cap, &info);
        write_file(path, buf, size);
        printf("  %s: %zu bytes, %u ticks, loop %u samples (%.2f s), rendered in %.0f us\n", KEY[v], size, info.ticks,
               info.loop_samples, info.loop_samples / 44100.0, us);
    }
    free(buf);
}

/* The hosts' entry point (ay_export.h): the reference VGM plus a GD3 tag. */
static void test_export(void) {
    printf("VGM export entry point\n");
    static const char* const systems[AY_VARIANTS] = {"ZX Spectrum 128 (Thor Tracker)", "MSX (Thor Tracker)",
                                                     "Atari ST (Thor Tracker)", "Amstrad CPC (Thor Tracker)"};
    for (int v = 0; v < AY_VARIANTS; ++v) {
        const AyTestVgm* ref = &AY_TEST_VGM[v];
        size_t size = 0;
        const char* problem = NULL;
        const char* title = "Copper \xC3\x96pening \xF0\x9F\x8E\xB9"; /* 2-byte and 4-byte UTF-8 */
        uint8_t* vgm = ay_export_vgm(v, g_song[v].bytes, sizeof g_song[v].bytes, AY_TEST_VGM_MAX_TICKS, title, "Esko",
                                     "2026/11/08", &size, &problem);
        EXPECT(vgm && !problem, "%s: renders (%s)", KEY[v], problem ? problem : "ok");
        if (!vgm) continue;
        const uint32_t gd3_at = 0x14 + rd32(vgm + 0x14), eof = 4 + rd32(vgm + 4);
        EXPECT(gd3_at == ref->size && eof == size, "%s: GD3 after the reference data (at %u), EOF offset = size %zu", KEY[v],
               gd3_at, size);
        uint8_t* body = (uint8_t*)malloc(ref->size);
        memcpy(body, vgm, ref->size);
        const uint32_t ref_eof = ref->size - 4;
        memcpy(body + 4, &ref_eof, 4);
        memset(body + 0x14, 0, 4);
        EXPECT(fnv(0xCBF29CE484222325ull, body, ref->size) == ref->fnv1a,
               "%s: the data = the reference player's VGM (header 0x04/0x14 aside)", KEY[v]);
        free(body);
        const uint8_t* g = vgm + gd3_at;
        const uint32_t len = rd32(g + 8);
        EXPECT(!memcmp(g, "Gd3 ", 4) && g[4] == 0 && g[5] == 1 && gd3_at + 12 + len == size, "%s: GD3 1.00 header, length %u",
               KEY[v], len);
        char fields[11][80];
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
                if (n > 72) continue;
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
        EXPECT(s == vgm + size, "%s: 11 strings fill the tag", KEY[v]);
        EXPECT(!strcmp(fields[0], title) && !strcmp(fields[4], systems[v]) && !strcmp(fields[6], "Esko") &&
                   !strcmp(fields[8], "2026/11/08") && !strcmp(fields[9], "Thor Tracker") && !fields[2][0] && !fields[10][0],
               "%s: GD3 fields: '%s', '%s', '%s', '%s', '%s'", KEY[v], fields[0], fields[4], fields[6], fields[8], fields[9]);
        ay_export_free(vgm);
        /* rejections */
        vgm = ay_export_vgm((v + 1) % AY_VARIANTS, g_song[v].bytes, sizeof g_song[v].bytes, 1000, NULL, NULL, NULL, &size, &problem);
        EXPECT(!vgm && size == 0 && problem,
               "%s image as another variant: '%s'", KEY[v], problem ? problem : "");
        memcpy(g_bad.bytes, g_song[v].bytes, sizeof g_bad.bytes);
        g_bad.bytes[AY_H_SPEED] = 0;
        vgm = ay_export_vgm(v, g_bad.bytes, sizeof g_bad.bytes, 1000, NULL, NULL, NULL, &size, &problem);
        EXPECT(!vgm && size == 0 && problem, "%s: bad image: '%s'", KEY[v], problem ? problem : "");
        vgm = ay_export_vgm(v, g_song[v].bytes, sizeof g_song[v].bytes, 100, NULL, NULL, NULL, &size, &problem);
        EXPECT(vgm && rd32(vgm + 0x1C) == 0 && rd32(vgm + 0x20) == 0 && size > 0x100, "%s: tick limit before the loop: no loop",
               KEY[v]);
        if (vgm) {
            const uint8_t* d = vgm + 0x14 + rd32(vgm + 0x14) + 12;
            for (int f = 0; f < 8; ++f, d += 2)
                while (d[0] | d[1]) d += 2;
            char date[16] = {0};
            for (int i = 0; i < 15 && (d[2 * i] | d[2 * i + 1]); ++i) date[i] = (char)d[2 * i];
            EXPECT(strlen(date) == 10 && date[4] == '/' && date[7] == '/' && !strncmp(date, "20", 2), "%s: default date today: %s",
                   KEY[v], date);
        }
        ay_export_free(vgm);
    }
    size_t size = 0;
    const char* problem = NULL;
    uint8_t* vgm = ay_export_vgm(AY_VARIANTS, g_song[0].bytes, sizeof g_song[0].bytes, 100, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && size == 0 && problem, "variant %d: '%s'", AY_VARIANTS, problem ? problem : "");
    vgm = ay_export_vgm(0, g_song[0].bytes, 100, 100, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && size == 0 && problem, "short image: '%s'", problem ? problem : "");
    vgm = ay_export_vgm(0, g_song[0].bytes, sizeof g_song[0].bytes, -5, NULL, NULL, NULL, &size, &problem);
    EXPECT(vgm && rd32(vgm + 0x18) == 0, "negative tick limit = 0 ticks");
    ay_export_free(vgm);
}

/* ---- ops tables ------------------------------------------------------------------------------ */

static void test_ops(void) {
    printf("ops tables\n");
    for (int v = 0; v < AY_VARIANTS; ++v) {
        const char* id = ay_variants[v].engine_id;
        const ChipEngineOps* ops = chip_engine_ops(id);
        EXPECT(ops && !strcmp(ops->system_id, id), "registry: %s", id);
        if (!ops) continue;
        EXPECT(ops->image_size == AY_IMAGE_SIZE && ops->image_storage == sizeof(AyImage) && !ops->row_serial, "%s: sizes", id);
        EXPECT(ops->create(CHIP_MAX_SAMPLE_RATE + 1) == NULL, "%s: rate above the maximum rejected", id);
        ChipImage* image = (ChipImage*)calloc(1, ops->image_storage);
        EXPECT(ops->load_image(image, g_song[v].bytes, ops->image_size - 1) == CHIP_ERR_IMAGE, "%s: wrong size rejected", id);
        EXPECT(ops->load_image(image, g_song[(v + 1) % AY_VARIANTS].bytes, ops->image_size) == CHIP_ERR_IMAGE,
               "%s: another variant's image rejected", id);
        memcpy(g_bad.bytes, g_song[v].bytes, sizeof g_bad.bytes);
        g_bad.bytes[0] = 'X';
        EXPECT(ops->load_image(image, g_bad.bytes, ops->image_size) == CHIP_ERR_IMAGE, "%s: invalid rejected", id);
        EXPECT(ops->load_image(image, g_song[v].bytes, ops->image_size) == CHIP_OK, "%s: song loads", id);
        ChipMachine* m = ops->create(RATE);
        int mode = -1, o, r, t;
        EXPECT(ops->start(m, image, 0, 0, AY_SEEK_TICKS, &mode) == CHIP_OK && mode == CHIP_SEEK_FROM_START, "%s: start", id);
        ops->position(m, &o, &r, &t);
        EXPECT(o == 0 && r == 0 && t == 0, "%s: position 0/0/0", id);
        float out[CHIP_MAX_TICK_FRAMES * 2];
        const int ticks = (int)lrint(tick_hz(v));
        int frames = 0, n = 0, range = 1;
        for (int k = 0; k < ticks; ++k) {
            frames += (n = ops->tick(m, out));
            if (n < frames_low(v, RATE) || n > frames_high(v, RATE)) range = 0;
        }
        const double want = ticks * RATE / tick_hz(v);
        EXPECT(fabs(frames - want) <= 2 && range, "%s: %d ticks = %d frames (%.1f), each %d-%d", id, ticks, frames, want,
               frames_low(v, RATE), frames_high(v, RATE));
        ops->position(m, &o, &r, &t);
        EXPECT(o == AY_TEST_TRACE[v].positions[3 * ticks] && r == AY_TEST_TRACE[v].positions[3 * ticks + 1] &&
                   t == AY_TEST_TRACE[v].positions[3 * ticks + 2],
               "%s: position after %d ticks = reference (%d/%d/%d)", id, ticks, o, r, t);
        ops->apply_image(m, image);
        EXPECT(ops->mute(m, 0, 1) == CHIP_OK && ops->mute(m, 0, 0) == CHIP_OK && ops->mute(m, 3, 1) == CHIP_ERR_IMAGE, "%s: mute",
               id);
        EXPECT(ops->audition(m, image, 0, A4, SLOT_SQUARE, 0) == CHIP_OK, "%s: audition", id);
        ops->tick(m, out);
        EXPECT(ops->release(m) == 1, "%s: release phase", id);
        int ended = 0;
        while (ops->tick(m, out) > 0 && ended < 1000) ++ended;
        EXPECT(ended < 1000, "%s: released note ends (%d ticks)", id, ended);
        ops->destroy(m);
        free(image);
    }
}

/* ---- independent comparison: ayumi ------------------------------------------------------------ */

static void ayumi_write(struct ayumi* ay, uint8_t* r, int reg, int val) {
    r[reg] = (uint8_t)val;
    if (reg < 6) {
        const int c = reg >> 1;
        ayumi_set_tone(ay, c, r[2 * c] | (r[2 * c + 1] & 15) << 8);
    } else if (reg == 6) {
        ayumi_set_noise(ay, r[6]);
    } else if (reg <= 10) {
        for (int c = 0; c < 3; ++c) {
            ayumi_set_mixer(ay, c, r[7] >> c & 1, r[7] >> (c + 3) & 1, r[8 + c] >> 4 & 1);
            ayumi_set_volume(ay, c, r[8 + c] & 15);
        }
    } else if (reg <= 12) {
        ayumi_set_envelope(ay, r[11] | r[12] << 8);
    } else if (reg == 13) {
        ayumi_set_envelope_shape(ay, val);
    }
}

typedef struct AyumiTimed {
    struct ayumi chip;
    int variant;
    uint32_t tick, steps_left, master_acc;
    uint8_t regs[16];
} AyumiTimed;

static void ayumi_timed_step(AyumiTimed* clock) {
    if (!clock->steps_left) {
        const AyTestTrace* tr = &AY_TEST_TRACE[clock->variant];
        for (uint32_t i = tr->first[clock->tick]; i < tr->first[clock->tick + 1]; ++i)
            ayumi_write(&clock->chip, clock->regs, tr->writes[2 * i], tr->writes[2 * i + 1]);
        ++clock->tick;
        clock->master_acc += ay_variants[clock->variant].frame_div;
        const uint32_t divider = 8 * ay_variants[clock->variant].ay_div;
        clock->steps_left = clock->master_acc / divider;
        clock->master_acc %= divider;
    }
    update_mixer(&clock->chip);
    --clock->steps_left;
}

/* ayumi_process's MIT-licensed resampler, Peter Sovietov; only its update_mixer call is replaced by the
 * exact-chip-timestamp scheduler above. The vendored implementation and chip state machines stay unmodified. */
static void ayumi_timed_process(AyumiTimed* clock) {
    struct ayumi* ay = &clock->chip;
    double* c_left = ay->interpolator_left.c;
    double* y_left = ay->interpolator_left.y;
    double* c_right = ay->interpolator_right.c;
    double* y_right = ay->interpolator_right.y;
    double* fir_left = &ay->fir_left[FIR_SIZE - ay->fir_index * DECIMATE_FACTOR];
    double* fir_right = &ay->fir_right[FIR_SIZE - ay->fir_index * DECIMATE_FACTOR];
    ay->fir_index = (ay->fir_index + 1) % (FIR_SIZE / DECIMATE_FACTOR - 1);
    for (int i = DECIMATE_FACTOR - 1; i >= 0; --i) {
        ay->x += ay->step;
        if (ay->x >= 1) {
            ay->x -= 1;
            y_left[0] = y_left[1]; y_left[1] = y_left[2]; y_left[2] = y_left[3];
            y_right[0] = y_right[1]; y_right[1] = y_right[2]; y_right[2] = y_right[3];
            ayumi_timed_step(clock);
            y_left[3] = ay->left;
            y_right[3] = ay->right;
            double y1 = y_left[2] - y_left[0];
            c_left[0] = 0.5 * y_left[1] + 0.25 * (y_left[0] + y_left[2]);
            c_left[1] = 0.5 * y1;
            c_left[2] = 0.25 * (y_left[3] - y_left[1] - y1);
            y1 = y_right[2] - y_right[0];
            c_right[0] = 0.5 * y_right[1] + 0.25 * (y_right[0] + y_right[2]);
            c_right[1] = 0.5 * y1;
            c_right[2] = 0.25 * (y_right[3] - y_right[1] - y1);
        }
        fir_left[i] = (c_left[2] * ay->x + c_left[1]) * ay->x + c_left[0];
        fir_right[i] = (c_right[2] * ay->x + c_right[1]) * ay->x + c_right[0];
    }
    ay->left = decimate(fir_left);
    ay->right = decimate(fir_right);
}

/* Exact generator/DAC comparison, not a pitch estimate from zero crossings of mixed voices and noise.
 * ayumi uses 32 envelope steps even for AY (duplicated DAC levels): unlike the physical 16-step divider,
 * an EP change halfway through a duplicated level can alter its phase. Compare AY envelopes from each
 * nonzero-period R13 restart until the next period change. EP=0 is checked against MAME's divider in test_chip. */
static void test_ayumi_clock(void) {
    printf("independent comparison: ayumi chip clocks\n");
    for (int v = 0; v < AY_VARIANTS; ++v) {
        const int ym = ay_variants[v].ym;
        AyPsg chip;
        struct ayumi ref;
        uint8_t regs[16] = {0};
        ay_psg_reset(&chip, ym, 0);
        ayumi_configure(&ref, ym, 800000, 100000);
        ayumi_set_noise(&ref, 0);
        ay_psg_write(&chip, 7, ay_variants[v].io_bits | 0x3F);
        ayumi_write(&ref, regs, 7, ay_variants[v].io_bits | 0x3F);
        const AyTestTrace* tr = &AY_TEST_TRACE[v];
        uint32_t acc = 0;
        uint64_t steps = 0, tone_errors = 0, noise_errors = 0, envelope_errors = 0, output_errors = 0, envelope_checked = 0;
        int comparable = ym;
        for (uint32_t t = 0; t < AYUMI_TICKS; ++t) {
            for (uint32_t i = tr->first[t]; i < tr->first[t + 1]; ++i) {
                const int r = tr->writes[2 * i], value = tr->writes[2 * i + 1];
                if (!ym && (r == 11 || r == 12) && regs[r] != value) comparable = 0;
                ay_psg_write(&chip, r, value);
                ayumi_write(&ref, regs, r, value);
                if (r == 13) comparable = ym || (regs[11] | regs[12] << 8) != 0;
            }
            acc += ay_variants[v].frame_div;
            const uint32_t count = acc / (8 * ay_variants[v].ay_div);
            acc %= 8 * ay_variants[v].ay_div;
            for (uint32_t i = 0; i < count; ++i) {
                int32_t mix;
                ay_psg_render_mix(&chip, 1, &mix, NULL);
                update_mixer(&ref);
                ++steps;
                if (chip.lfsr != (uint32_t)ref.noise || chip.noise_count != (uint32_t)ref.noise_counter) ++noise_errors;
                if (comparable) {
                    ++envelope_checked;
                    if (ay_psg_env_level(&chip) != (ym ? ref.envelope : ref.envelope >> 1)) ++envelope_errors;
                }
                for (int c = 0; c < 3; ++c) {
                    const struct tone_channel* ch = &ref.channels[c];
                    if (chip.tone_out[c] != ch->tone || chip.tone_count[c] != (uint32_t)ch->tone_counter) ++tone_errors;
                    if (!ch->e_on || comparable) {
                        int level = (ch->tone | ch->t_off) & ((ref.noise & 1) | ch->n_off);
                        level *= ch->e_on ? ref.envelope : ch->volume * 2 + 1;
                        if (chip.amp[ay_psg_channel_level(&chip, c)] != (int)lround(ref.dac_table[level] * AY_AMP)) ++output_errors;
                    }
                }
            }
        }
        EXPECT(tone_errors == 0, "%s: ayumi tone counters and edges (%llu mismatches)", KEY[v], (unsigned long long)tone_errors);
        EXPECT(noise_errors == 0, "%s: ayumi noise counter and LFSR (%llu mismatches)", KEY[v], (unsigned long long)noise_errors);
        EXPECT(envelope_checked > 1000 && envelope_errors == 0, "%s: ayumi envelope steps (%llu mismatches)", KEY[v], (unsigned long long)envelope_errors);
        EXPECT(output_errors == 0, "%s: ayumi gated DAC levels (%llu mismatches)", KEY[v], (unsigned long long)output_errors);
        printf("  %s: %llu chip steps, %llu comparable envelope steps\n", KEY[v],
               (unsigned long long)steps, (unsigned long long)envelope_checked);
    }
}

static double correlation(const double* a, const double* b, int n) {
    double ma = 0, mb = 0;
    for (int i = 0; i < n; ++i) ma += a[i], mb += b[i];
    ma /= n, mb /= n;
    double sab = 0, saa = 0, sbb = 0;
    for (int i = 0; i < n; ++i) {
        sab += (a[i] - ma) * (b[i] - mb);
        saa += (a[i] - ma) * (a[i] - ma);
        sbb += (b[i] - mb) * (b[i] - mb);
    }
    return saa > 0 && sbb > 0 ? sab / sqrt(saa * sbb) : (saa == 0 && sbb == 0 ? 1.0 : 0.0);
}

/*
 * The engine renders AYUMI_TICKS ticks of the song with channels outside `solo` muted (solo 7 = the mix); ayumi gets
 * every tick's writes at their exact chip-step timestamps and renders exactly as many samples as the engine.
 * One output side is compared: the mono variants' (left = right), or one side of the
 * CPC's socket (left A + B, right B + C). ayumi's right channel is used with pan 1 for exactly the channels that side
 * carries among the soloed ones and pan 0 for the others (so it is that side's sum), then remove_dc, scaled by
 * AY_AMP / 32768 to the engine's units. A solo whose channel the side does not carry is not compared here (the engine
 * side is exactly 0: test_audition and test_mute check that).
 *
 * The renderers use different resamplers and DC removal (blip_buf's band-limited steps and one-pole high-pass
 * versus ayumi's interpolation/FIR and moving-average DC removal). Keep the 0.95 RMS-envelope correlation
 * and 0.8..1.25 overall level bounds. Pitch and noise are checked by exact generator states in test_ayumi_clock:
 * zero-crossing counts of noise, polyphony and changing envelopes are not measurements of their fundamental.
 */
static void compare_ayumi(int v, int solo, int side, float* wav, int* wav_frames) {
    const int carried = ay_variants[v].stereo ? (side ? 6 : 3) : 7, mask = carried & solo;
    if (!mask) return;
    AyMachine* m = ay_machine_create(RATE, v);
    int mode;
    ay_machine_start(m, &g_song[v], 0, 0, AY_SEEK_TICKS, &mode);
    for (int c = 0; c < 3; ++c) ay_machine_mute(m, c, !(solo >> c & 1));
    AyumiTimed* reference = (AyumiTimed*)calloc(1, sizeof *reference);
    reference->variant = v;
    struct ayumi* ay = &reference->chip;
    ayumi_configure(ay, ay_variants[v].ym, clock_hz(v), RATE);
    ayumi_set_noise(ay, 0); /* power-on R6 = 0 (acts as 1); ayumi_configure leaves the period at 0 */
    for (int c = 0; c < 3; ++c) ayumi_set_pan(ay, c, (mask >> c & 1) ? 1.0 : 0.0, 0);
    ayumi_write(ay, reference->regs, 7, ay_variants[v].io_bits | 0x3F);
    const size_t cap = (size_t)AYUMI_TICKS * AYM_MAX_TICK_FRAMES;
    float* eng = (float*)malloc(cap * 2 * sizeof(float));
    float* ref = (float*)malloc(cap * 2 * sizeof(float));
    int frames = 0;
    const float scale = (float)AY_AMP / 32768.0f;
    for (int t = 0; t < AYUMI_TICKS; ++t) {
        const int n = ay_machine_tick(m, eng + 2 * frames);
        for (int i = 0; i < n; ++i) {
            ayumi_timed_process(reference);
            ayumi_remove_dc(ay);
            ref[2 * (frames + i)] = ref[2 * (frames + i) + 1] = (float)ay->right * scale;
            eng[2 * (frames + i)] = eng[2 * (frames + i) + side]; /* the compared side into column 0 */
        }
        frames += n;
    }
    const int skip = RATE / 10, win = RATE / 50, wins = (frames - skip) / win;
    double* ea = (double*)malloc(sizeof(double) * (size_t)wins);
    double* ra = (double*)malloc(sizeof(double) * (size_t)wins);
    for (int w = 0; w < wins; ++w) {
        ea[w] = rms_mono(eng + 2 * (skip + w * win), win, 2);
        ra[w] = rms_mono(ref + 2 * (skip + w * win), win, 2);
    }
    const double corr = correlation(ea, ra, wins);
    const double er = rms_mono(eng + 2 * skip, frames - skip, 2), rr = rms_mono(ref + 2 * skip, frames - skip, 2);
    char what[24];
    snprintf(what, sizeof what, "%s%s", solo == 7 ? "mix" : solo == 1 ? "A" : solo == 2 ? "B" : "C",
             !ay_variants[v].stereo ? "" : side ? " right" : " left");
    if (er < 0.003 && rr < 0.003) {
        EXPECT(0, "%s ayumi %s: both silent: the test song must play every channel", KEY[v], what);
    } else {
        EXPECT(corr >= 0.95, "%s ayumi %s: 20 ms loudness envelopes correlate %.4f", KEY[v], what, corr);
        EXPECT(er > 0.8 * rr && er < 1.25 * rr, "%s ayumi %s: rms %.4f vs ayumi %.4f", KEY[v], what, er, rr);
    }
    printf("  %s %s: envelope correlation %.4f, rms %.4f vs %.4f\n", KEY[v], what, corr, er, rr);
    if (wav) {
        for (int i = 0; i < frames; ++i) {
            wav[2 * i + side] = ref[2 * i];
            if (!ay_variants[v].stereo) wav[2 * i + 1] = ref[2 * i];
        }
        *wav_frames = frames;
    }
    free(ea);
    free(ra);
    free(eng);
    free(ref);
    free(reference);
    ay_machine_destroy(m);
}

static void test_ayumi(const char* dir) {
    printf("independent comparison: ayumi\n");
    float* wav = (float*)calloc((size_t)AYUMI_TICKS * AYM_MAX_TICK_FRAMES * 2, sizeof(float));
    for (int v = 0; v < AY_VARIANTS; ++v) {
        int frames = 0;
        for (int solo = 1; solo <= 8; solo <<= 1)
            for (int side = 0; side <= ay_variants[v].stereo; ++side)
                compare_ayumi(v, solo == 8 ? 7 : solo, side, solo == 8 ? wav : NULL, &frames);
        char path[512];
        snprintf(path, sizeof path, "%s/ay_%s_ayumi.wav", dir, KEY[v]);
        write_wav(path, wav, frames); /* ayumi's render of the mix: mono, or the CPC's two sides */
    }
    free(wav);
}

/* ---- costs ----------------------------------------------------------------------------------- */

static void test_costs(void) {
    printf("costs\n");
    for (int v = 0; v < AY_VARIANTS; ++v) {
        AyMachine* m = ay_machine_create(RATE, v);
        int mode;
        double t0 = now_us();
        ay_machine_start(m, &g_song[v], 0, 0, AY_SEEK_TICKS, &mode);
        for (int k = 0; k < AY_SEEK_TICKS; ++k) ay_machine_tick_silent(m);
        const double per = (now_us() - t0) / AY_SEEK_TICKS;
        t0 = now_us();
        for (int k = 0; k < 600; ++k) ay_machine_tick(m, g_out);
        const double audible = (now_us() - t0) / 600;
        printf("  %s: seek tick (player + silent chip) %.2f us (%d ticks: %.1f ms); audible tick %.1f us\n", KEY[v], per,
               AY_SEEK_TICKS, per * AY_SEEK_TICKS / 1000, audible);
        ay_machine_destroy(m);
    }
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    for (int v = 0; v < AY_VARIANTS; ++v) {
        EXPECT(load(&g_song[v], &AY_TEST_SONG[v], v) == 0, "%s song loads: %s", KEY[v], g_song[v].info.problem);
        EXPECT(load(&g_relayout[v], &AY_TEST_RELAYOUT[v], v) == 0, "%s re-layout loads: %s", KEY[v], g_relayout[v].info.problem);
        EXPECT(load(&g_note[v], &AY_TEST_NOTE_EDIT[v], v) == 0, "%s note edit loads: %s", KEY[v], g_note[v].info.problem);
        EXPECT(load(&g_tempo[v], &AY_TEST_TEMPO_EDIT[v], v) == 0, "%s tempo edit loads: %s", KEY[v], g_tempo[v].info.problem);
        EXPECT(load(&g_cut[v], &AY_TEST_CUT[v], v) == 0, "%s cut loads: %s", KEY[v], g_cut[v].info.problem);
        EXPECT(memcmp(g_song[v].bytes, g_relayout[v].bytes, sizeof g_song[v].bytes) != 0, "%s: the re-layout moves data", KEY[v]);
    }
    if (failures) {
        printf("AY: fixtures do not load; %d checks, %d failures\n", checks, failures);
        return 1;
    }
    test_tables();
    test_images();
    test_reference(dir);
    test_delayed_restart();
    test_chip();
    test_silent_song();
    test_audio(dir);
    test_loop();
    test_seek();
    test_live_edits();
    test_audition(dir);
    test_mute();
    test_vgm(dir);
    test_export();
    test_ops();
    test_ayumi_clock();
    test_ayumi(dir);
    test_costs();
    printf("AY: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
