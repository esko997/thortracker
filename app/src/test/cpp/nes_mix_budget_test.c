/* Host regression test for the MMC5 and N163 builds' mixer budget (nes_machine.c: MIX_PEAK, MIX_BUFFER_PEAK,
 * MIX_OUTPUT_PEAK) and the MMC5 PCM DAC's mute centre (nes_mmc5.c), all rendered through the machine's own audio path
 * (nm_tick: the sound units, blip_buf, the 37 Hz high-pass and the 14 kHz low-pass). No constant is recomputed here.
 *
 * Worst-case fixture: after nm_start, the build's play entry is pointed (nm_poke) at small register routines in RAM that
 * swing every voice the build has between the two ends of its mixer span. One end has the 2A03 loud (both pulses and the
 * noise at volume 15, the DMC at 127) and the expansion at its most positive: the MMC5 pulses at 0 and its DAC at 1, or
 * the N163 at sample 15 x volume 15. The other end has the 2A03 silent and the expansion at its most negative: the MMC5
 * pulses at 15 and its DAC at 255, or the N163 at sample 0 x 15. The swing switches every 1..16 frames at every supported
 * rate class (8 to 96 kHz), so the two high-passes' undershoots line up with the edges. The routines replace the
 * FamiStudio engine for the run. The writes, units and filters are the production ones.
 *
 * Build and run: tools/audio/run_native_host_test.sh [evidence-dir]. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "nes_apu.h"
#include "nes_machine.h"
#include "nes_mmc5.h"
#include "nes_mmc5_song.h"
#include "nes_n163.h"
#include "nes_n163_song.h"

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
#define N163_BUILD (&nes_player_n163[N163_TEST_CHANNELS - 1]) /* the build of the N163 test song */
static float g_out[NM_MAX_TICK_FRAMES * 2];
static float g_ref[NM_MAX_TICK_FRAMES * 2];
static NesImage g_mmc5, g_n163;

/* ---- register routines -------------------------------------------------------------------- */

typedef struct Write {
    uint16_t addr;
    uint8_t value;
} Write;
#define COUNT(a) ((int)(sizeof(a) / sizeof((a)[0])))

/* RAM for the routines, below the audition streams at NES_AUDITION_RAM ($07C0). Once the play entry jumps here the
 * engine no longer runs, so its RAM is not in use. */
enum { AT_SETUP = 0x0600, AT_HIGH = 0x0680, AT_LOW = 0x06B0, AT_IDLE = 0x06E0, AT_END = 0x0700 };

/* LDA #value / STA addr for every write, in order (6 CPU cycles each), then RTS. */
static int put_routine(NesMachine* m, uint16_t at, uint16_t limit, const Write* w, int n) {
    if (at + 5 * n + 1 > limit) return -1;
    for (int i = 0; i < n; ++i) {
        const uint8_t code[5] = {0xA9, w[i].value, 0x8D, (uint8_t)w[i].addr, (uint8_t)(w[i].addr >> 8)};
        for (int k = 0; k < 5; ++k) nm_poke(m, (uint16_t)(at + 5 * i + k), code[k]);
    }
    nm_poke(m, (uint16_t)(at + 5 * n), 0x60);
    return 0;
}

/* The next nm_tick's play call runs the routine at `at`: the build's play entry becomes JMP at. */
static void play_routine(NesMachine* m, uint16_t at) {
    const uint16_t play = nm_player(m)->play;
    nm_poke(m, play, 0x4C);
    nm_poke(m, (uint16_t)(play + 1), (uint8_t)at);
    nm_poke(m, (uint16_t)(play + 2), (uint8_t)(at >> 8));
}

/* 2A03 setup: everything enabled with halted length counters and constant volume 0 (the halt and volume first, so
 * no length counter runs out), pulse periods $3FF (the sweep unit's target $7FE does not mute; 109 Hz), the noise
 * at period index 4, the DMC at 0. Triangle untouched: it holds a step, a constant inside the span. */
#define APU_SETUP                                                                                                   \
    {0x4015, 0x0F}, {0x4000, 0xF0}, {0x4004, 0xF0}, {0x4001, 0x00}, {0x4005, 0x00}, {0x4002, 0xFF}, {0x4006, 0xFF}, \
        {0x4003, 0xFB}, {0x4007, 0xFB}, {0x400C, 0x30}, {0x400E, 0x04}, {0x400F, 0xF8}, {0x4011, 0x00}
/* The 2A03 loud (pulses at 15, duty 75%; noise at 15; DMC 127) or silent. */
#define APU_LOUD {0x4000, 0xFF}, {0x4004, 0xFF}, {0x400C, 0x3F}, {0x4011, 0x7F}
#define APU_SILENT {0x4000, 0xF0}, {0x4004, 0xF0}, {0x400C, 0x30}, {0x4011, 0x00}

/* MMC5: both pulses enabled at period $3FF, duty 75%, halted, volume 0; write mode; DAC at its centre. */
static const Write kMmc5Setup[] = {APU_SETUP,      {0x5015, 0x03}, {0x5010, 0x00}, {0x5000, 0xF0}, {0x5004, 0xF0},
                                   {0x5002, 0xFF}, {0x5006, 0xFF}, {0x5003, 0xFB}, {0x5007, 0xFB}, {0x5011, NES_MMC5_DAC_CENTRE}};
/* The positive end: 2A03 loud, MMC5 pulses silent, DAC 1 (the inverted DAC's highest level). */
static const Write kMmc5High[] = {APU_LOUD, {0x5000, 0xF0}, {0x5004, 0xF0}, {0x5011, 0x01}};
/* The negative end: 2A03 silent, MMC5 pulses at 15, DAC 255. */
static const Write kMmc5Low[] = {APU_SILENT, {0x5000, 0xFF}, {0x5004, 0xFF}, {0x5011, 0xFF}};
/* The same swing with the DAC held at its centre (the lane's resting value). */
static const Write kMmc5HighCentred[] = {APU_LOUD, {0x5000, 0xF0}, {0x5004, 0xF0}, {0x5011, NES_MMC5_DAC_CENTRE}};
static const Write kMmc5LowCentred[] = {APU_SILENT, {0x5000, 0xFF}, {0x5004, 0xFF}, {0x5011, NES_MMC5_DAC_CENTRE}};

/* N163: sound on; channel 8 alone (enabled count 1, so the DAC holds its level), frequency 0 (the phase stays at 0),
 * a 4-sample wave at address 0, volume 15; sample 0 = 0 (level -120). */
static const Write kN163Setup[] = {APU_SETUP,      {0xE000, 0x00}, {0xF800, 0xF8}, {0x4800, 0x00}, {0x4800, 0x00},
                                   {0x4800, 0x00}, {0x4800, 0x00}, {0x4800, 0xFC}, {0x4800, 0x00}, {0x4800, 0x00},
                                   {0x4800, 0x0F}, {0xF800, 0x00}, {0x4800, 0x00}};
/* The positive end: 2A03 loud, sample 15 (+105); the negative end: 2A03 silent, sample 0 (-120). */
static const Write kN163High[] = {APU_LOUD, {0xF800, 0x00}, {0x4800, 0xFF}};
static const Write kN163Low[] = {APU_SILENT, {0xF800, 0x00}, {0x4800, 0x00}};

typedef struct Fixture {
    const char* name;
    const NesPlayer* player;
    const NesImage* image;
    const Write *setup, *high, *low;
    int n_setup, n_high, n_low;
} Fixture;

/* A machine at `rate` started on the fixture's song, with its routines in RAM and an idle (RTS) routine. */
static NesMachine* start_fixture(const Fixture* f, uint32_t rate) {
    NesMachine* m = nm_create(rate, f->player);
    int mode;
    if (!m) return NULL;
    if (nm_start(m, f->image, 0, 0, 0, &mode) != NM_OK || put_routine(m, AT_SETUP, AT_HIGH, f->setup, f->n_setup) ||
        put_routine(m, AT_HIGH, AT_LOW, f->high, f->n_high) || put_routine(m, AT_LOW, AT_IDLE, f->low, f->n_low) ||
        put_routine(m, AT_IDLE, AT_END, NULL, 0)) {
        nm_destroy(m);
        return NULL;
    }
    return m;
}

/* One tick running the routine at `at`: the frames rendered (<= 0 on a fault), the peak |sample| raised into *peak and
 * NaNs counted. */
static int tick(NesMachine* m, uint16_t at, float* peak, int* nans) {
    play_routine(m, at);
    const int n = nm_tick(m, g_out);
    for (int i = 0; i < 2 * n; ++i) {
        if (g_out[i] != g_out[i]) ++*nans;
        if (fabsf(g_out[i]) > *peak) *peak = fabsf(g_out[i]);
    }
    return n;
}

/* ---- worst-case swings -------------------------------------------------------------------- */

/* The fixture swinging end to end every `half` frames (the first edge to the positive end after the setup frame) for
 * at least 8 full periods and 2 s; returns the peak |output|, -1 on a fault or NaN. */
static float swing_peak(const Fixture* f, uint32_t rate, int half) {
    NesMachine* m = start_fixture(f, rate);
    if (!m) return -1.0f;
    float peak = 0.0f;
    int nans = 0, ok = tick(m, AT_SETUP, &peak, &nans) > 0;
    const int frames = 16 * half > 120 ? 16 * half : 120;
    for (int k = 0; ok && k < frames; ++k) {
        const uint16_t at = k % half ? AT_IDLE : (k / half) % 2 ? AT_LOW : AT_HIGH;
        ok = tick(m, at, &peak, &nans) > 0;
    }
    nm_destroy(m);
    return ok && !nans ? peak : -1.0f;
}

static void test_worst_case(const Fixture* f) {
    printf("%s build: end-to-end swings of every voice at 8-96 kHz stay inside the output\n", f->name);
    static const uint32_t rates[] = {8000, 22050, 44100, 48000, 96000};
    static const int halves[] = {1, 2, 3, 4, 6, 8, 16};
    float worst = 0.0f;
    uint32_t worst_rate = 0;
    int worst_half = 0, faults = 0, over = 0;
    for (int r = 0; r < COUNT(rates); ++r) {
        for (int h = 0; h < COUNT(halves); ++h) {
            const float peak = swing_peak(f, rates[r], halves[h]);
            if (peak < 0.0f) {
                ++faults;
                continue;
            }
            /* MIX_OUTPUT_PEAK: the derived bound for any register sequence is 0.98 x 1.1184 / 1.12 = 0.979. */
            if (!(peak < 0.98f)) {
                ++over;
                printf("  %u Hz, edges every %d frames: peak %.4f\n", rates[r], halves[h], peak);
            }
            if (peak > worst) worst = peak, worst_rate = rates[r], worst_half = halves[h];
        }
    }
    printf("  worst peak %.4f (%u Hz, edges every %d frames)\n", worst, worst_rate, worst_half);
    EXPECT(faults == 0, "%s: every render runs (no fault, no NaN; %d failed)", f->name, faults);
    EXPECT(over == 0, "%s: every render stays below the budget's 0.98 output peak (%d over; worst %.4f)", f->name, over, worst);
    /* The fixture really spans the mix: a full-span edge after the filters is about 0.84 of the budget's 0.875 per W. */
    EXPECT(worst > 0.5f, "%s: the swing covers most of the span (worst-case peak %.4f)", f->name, worst);
}

/* ---- relative levels, rendered ------------------------------------------------------------ */

/* The positive output peak of one step: the fixture's setup frame, `settle` idle frames (silent 2A03 triangle and
 * noise: muted, so the DMC is alone in the nonlinear tnd mix), then `step` and two idle frames. */
static float step_peak(const Fixture* f, const Write* step, int n_step) {
    NesMachine* m = start_fixture(f, RATE);
    if (!m || put_routine(m, AT_HIGH, AT_LOW, step, n_step)) {
        nm_destroy(m);
        return -1.0f;
    }
    nm_mute_channel(m, NES_APU_TRIANGLE, 1);
    nm_mute_channel(m, NES_APU_NOISE, 1);
    float peak = 0.0f, high = 0.0f;
    int nans = 0, ok = tick(m, AT_SETUP, &peak, &nans) > 0;
    for (int k = 0; ok && k < 60; ++k) ok = tick(m, AT_IDLE, &peak, &nans) > 0; /* 1 s: both high-passes settle */
    for (int k = 0; ok && k < 3; ++k) {
        const int n = tick(m, k ? AT_IDLE : AT_HIGH, &peak, &nans);
        ok = n > 0;
        for (int i = 0; i < 2 * n; ++i) high = g_out[i] > high ? g_out[i] : high;
    }
    nm_destroy(m);
    return ok && !nans ? high : -1.0f;
}

static void test_balance(const Fixture* mmc5, const Fixture* n163) {
    printf("relative levels: the budget scales every unit alike (rendered steps)\n");
    const double dmc127 = 159.79 / (22638.0 / 127.0 + 100.0); /* the 2A03 tnd formula, the DMC alone at 127 */
    /* MMC5: the DMC 0 -> 127 and the DAC 128 -> 1 (inverted: up by 127 values) at the same CPU cycle. NESdev: the DAC is
     * equivalent in volume to the DMC with equivalent input. */
    static const Write dmc_step[] = {{0x4011, 0x7F}}, dac_step[] = {{0x5011, 0x01}};
    const float a = step_peak(mmc5, dmc_step, 1), b = step_peak(mmc5, dac_step, 1);
    EXPECT(a > 0.05f && b > 0.05f && fabsf(b / a - 1.0f) < 0.005f,
           "MMC5 build: DAC 128 -> 1 renders as the DMC 0 -> 127 (peaks %.4f, %.4f)", b, a);
    /* N163: the DMC 0 -> 127 against the N163 -120 -> +105 (225 units, 12 dB over a full 2A03 pulse), the write one
     * instruction later in both, the N163's level at its channel's next 15-cycle slot. */
    static const Write dmc_step2[] = {{0xF800, 0x00}, {0x4011, 0x7F}}, n163_step[] = {{0xF800, 0x00}, {0x4800, 0xFF}};
    const float c = step_peak(n163, dmc_step2, 2), d = step_peak(n163, n163_step, 2);
    const double want = NES_N163_SPAN / dmc127;
    EXPECT(c > 0.05f && d > 0.05f && fabs(d / c / want - 1.0) < 0.02,
           "N163 build: the full N163 swing renders %.4f x the DMC's 0 -> 127 (want %.4f; peaks %.4f, %.4f)", d / c, want, d, c);
}

/* ---- the MMC5 PCM lane's mute ------------------------------------------------------------- */

/* Two machines on the real MMC5 test song (FamiStudio engine, pulses sounding, the PCM lane idle): one mutes lane 7 at
 * frame 40 and unmutes it at frame 80. A muted DAC mixes as its centre, where the idle lane rests: no step, so the
 * outputs are identical sample for sample. */
static void test_idle_mute(void) {
    printf("MMC5 PCM lane: mute and unmute of the idle lane add no step\n");
    NesMachine* a = nm_create(RATE, &nes_player_mmc5);
    NesMachine* b = nm_create(RATE, &nes_player_mmc5);
    int mode, differ = 0, faults = 0, first = -1;
    float worst = 0.0f, loud = 0.0f;
    if (!a || !b || nm_start(a, &g_mmc5, 0, 0, 0, &mode) != NM_OK || nm_start(b, &g_mmc5, 0, 0, 0, &mode) != NM_OK) ++faults;
    for (int f = 0; !faults && f < 120; ++f) {
        if (f == 40 || f == 80) nm_mute_channel(a, NM_MMC5_PCM, f == 40);
        const int n = nm_tick(a, g_ref);
        if (n <= 0 || nm_tick(b, g_out) != n) {
            ++faults;
            break;
        }
        for (int i = 0; i < 2 * n; ++i) {
            const float e = fabsf(g_ref[i] - g_out[i]);
            if (e > 0.0f && first < 0) first = f;
            if (e > 0.0f) ++differ;
            if (e > worst) worst = e;
            if (fabsf(g_out[i]) > loud) loud = fabsf(g_out[i]);
        }
    }
    EXPECT(!faults && loud > 0.05f, "both machines play the song (peak %.4f)", loud);
    EXPECT(differ == 0, "toggling the idle lane's mute leaves every sample unchanged (%d differ from frame %d, worst %.6f)", differ,
           first, worst);
    nm_destroy(a);
    nm_destroy(b);
}

/* A muted lane sounds as an idle one: the full swing with its DAC moving 1 <-> 255 and lane 7 muted equals, sample for
 * sample, the same swing (same writes, same cycles) with the DAC held at its centre and lane 7 open. */
static void test_muted_is_centred(const Fixture* f) {
    printf("MMC5 PCM lane: a muted DAC mixes as its centre\n");
    const Fixture centred = {f->name, f->player, f->image, f->setup, kMmc5HighCentred, kMmc5LowCentred,
                             f->n_setup, COUNT(kMmc5HighCentred), COUNT(kMmc5LowCentred)};
    NesMachine* a = start_fixture(f, RATE);
    NesMachine* b = start_fixture(&centred, RATE);
    int differ = 0, faults = !a || !b;
    float pa = 0.0f, pb = 0.0f;
    int na = 0, nb = 0;
    if (!faults) nm_mute_channel(a, NM_MMC5_PCM, 1);
    for (int k = -1; !faults && k < 96; ++k) {
        const uint16_t at = k < 0 ? AT_SETUP : k % 6 ? AT_IDLE : (k / 6) % 2 ? AT_LOW : AT_HIGH;
        const int n = tick(a, at, &pa, &na);
        memcpy(g_ref, g_out, sizeof g_ref);
        if (n <= 0 || tick(b, at, &pb, &nb) != n) {
            ++faults;
            break;
        }
        for (int i = 0; i < 2 * n; ++i) differ += g_ref[i] != g_out[i];
    }
    EXPECT(!faults && !na && !nb && pa > 0.1f, "both swings render (peaks %.4f, %.4f)", pa, pb);
    EXPECT(nm_mmc5(a)->dac != NES_MMC5_DAC_CENTRE && nm_mmc5(b)->dac == NES_MMC5_DAC_CENTRE,
           "the muted lane's DAC kept moving (%d), the open one stayed centred (%d)", nm_mmc5(a)->dac, nm_mmc5(b)->dac);
    EXPECT(differ == 0, "muted moving DAC = open centred DAC, every sample (%d differ)", differ);
    nm_destroy(a);
    nm_destroy(b);
}

int main(void) {
    memset(&g_mmc5, 0, sizeof g_mmc5);
    memcpy(g_mmc5.bytes, nes_mmc5_thor, NES_MMC5_THOR_SIZE);
    memset(&g_n163, 0, sizeof g_n163);
    memcpy(g_n163.bytes, nes_n163_thor, NES_N163_THOR_SIZE);
    if (nm_load_image(&g_mmc5, &nes_player_mmc5) != 0 || nm_load_image(&g_n163, N163_BUILD) != 0) {
        printf("a fixture does not load: %s / %s\n", g_mmc5.info.problem ? g_mmc5.info.problem : "-",
               g_n163.info.problem ? g_n163.info.problem : "-");
        return 2;
    }
    const Fixture mmc5 = {"MMC5", &nes_player_mmc5, &g_mmc5, kMmc5Setup, kMmc5High, kMmc5Low,
                          COUNT(kMmc5Setup), COUNT(kMmc5High), COUNT(kMmc5Low)};
    const Fixture n163 = {"N163", N163_BUILD, &g_n163, kN163Setup, kN163High, kN163Low,
                          COUNT(kN163Setup), COUNT(kN163High), COUNT(kN163Low)};
    test_worst_case(&mmc5);
    test_worst_case(&n163);
    test_balance(&mmc5, &n163);
    test_idle_mute();
    test_muted_is_centred(&mmc5);
    printf("nes_mix_budget_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
