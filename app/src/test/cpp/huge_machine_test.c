/* Host regression test for the preview machine: the real hUGEDriver 6.1.3 image running on
 * binjgb, observed through binjgb's own APU register-write log and the driver's WRAM.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir]
 *
 * Song images are built here with the same fixed layout as HugeImageLayout.kt (no track
 * sharing needed for these small songs). Expected register values come from the driver
 * source (third_party/hUGEDriver-6.1.3/rgbds/hUGEDriver.asm) and its note table. The last
 * group drives the same machine through the chip engine interface the audio hosts use. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "chip_engine.h"
#include "huge_machine.h"

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

static const uint16_t kPeriod[72] = {
    44,   156,  262,  363,  457,  547,  631,  710,  786,  854,  923,  986,  1046, 1102, 1155,
    1205, 1253, 1297, 1339, 1379, 1417, 1452, 1486, 1517, 1546, 1575, 1602, 1627, 1650, 1673,
    1694, 1714, 1732, 1750, 1767, 1783, 1798, 1812, 1825, 1837, 1849, 1860, 1871, 1881, 1890,
    1899, 1907, 1915, 1923, 1930, 1936, 1943, 1949, 1954, 1959, 1964, 1969, 1974, 1978, 1982,
    1985, 1988, 1992, 1995, 1998, 2001, 2004, 2006, 2009, 2011, 2013, 2015};

/* APU register offsets from 0xFF10, as logged by binjgb. */
enum { NR10 = 0x00, NR11, NR12, NR13, NR14, NR21 = 0x06, NR22, NR23, NR24, NR30, NR31, NR32, NR33,
       NR34, NR41 = 0x10, NR42, NR43, NR44, NR50, NR51, NR52 };

/* ---- song builder (layout == HugeImageLayout.kt) ---- */
#define L_DESC 0x0900
#define L_ORDER_COUNT 0x0915
#define L_DUTY 0x0920
#define L_WAVE 0x0980
#define L_NOISE 0x09E0
#define L_WAVES 0x0A40
#define L_ORDERS 0x0B40
#define L_TRACKS_A 0x0F40
#define L_SLOTS_A 235
#define L_REGION_B 0xC400
#define MAX_PATS 8

typedef struct TSong {
    int tpr;
    int orders;
    int order_pat[16];
    uint8_t pats[MAX_PATS][4][64 * 3];
    uint8_t duty[15][6], wave[15][6], noise[15][6], waves[16][16];
} TSong;

static void song_init(TSong* s, int tpr, int orders) {
    memset(s, 0, sizeof(*s));
    s->tpr = tpr;
    s->orders = orders;
    for (int i = 0; i < orders; ++i) s->order_pat[i] = i % MAX_PATS;
    for (int p = 0; p < MAX_PATS; ++p)
        for (int c = 0; c < 4; ++c)
            for (int r = 0; r < 64; ++r) s->pats[p][c][r * 3] = 90;
    /* duty 1: NR10=0, NR11=50% duty, NR12=vol15 no envelope, highmask=trigger */
    uint8_t d1[6] = {0x00, 0x80, 0xF0, 0, 0, 0x80};
    /* duty 2: NR10 sweep 0, NR11 25% length 0, NR12 vol 10 decreasing pace 3 */
    uint8_t d2[6] = {0x00, 0x40, 0xA3, 0, 0, 0x80};
    memcpy(s->duty[0], d1, 6);
    memcpy(s->duty[1], d2, 6);
    uint8_t w1[6] = {0x00, 0x20, 0x00, 0, 0, 0x80}; /* NR31, NR32 full, wave 0 */
    memcpy(s->wave[0], w1, 6);
    uint8_t n1[6] = {0xF1, 0, 0, 0x80, 0, 0}; /* NR42, highmask: trigger, 15-bit */
    memcpy(s->noise[0], n1, 6);
    uint8_t n2[6] = {0xA2, 0, 0, 0xC5, 0, 0}; /* 7-bit, length enabled, length 5 */
    memcpy(s->noise[1], n2, 6);
    for (int i = 0; i < 16; ++i) s->waves[0][i] = (uint8_t)((i * 2) << 4 | (i * 2 + 1)); /* ramp */
    for (int i = 0; i < 16; ++i) s->waves[1][i] = i < 8 ? 0xFF : 0x00;                   /* square */
}

static void cell(TSong* s, int pat, int ch, int row, int note, int instr, int fx, int param) {
    uint8_t* c = &s->pats[pat][ch][row * 3];
    c[0] = (uint8_t)(note | ((instr & 0x10) << 3));
    c[1] = (uint8_t)(((instr << 4) & 0xF0) | (fx & 0x0F));
    c[2] = (uint8_t)param;
}

static void put16(uint8_t* m, int at, int v) {
    m[at] = (uint8_t)(v & 0xFF);
    m[at + 1] = (uint8_t)(v >> 8);
}

static int slot_addr(int slot) {
    return slot < L_SLOTS_A ? L_TRACKS_A + slot * 192 : L_REGION_B + (slot - L_SLOTS_A) * 192;
}

static void build(const TSong* s, HugeImage* img, uint32_t version) {
    memset(img, 0, sizeof(*img));
    img->version = version;
    uint8_t* m = img->mem;
    m[L_DESC] = (uint8_t)s->tpr;
    put16(m, L_DESC + 1, L_ORDER_COUNT);
    for (int ch = 0; ch < 4; ++ch) put16(m, L_DESC + 3 + 2 * ch, L_ORDERS + ch * 256);
    put16(m, L_DESC + 11, L_DUTY);
    put16(m, L_DESC + 13, L_WAVE);
    put16(m, L_DESC + 15, L_NOISE);
    put16(m, L_DESC + 17, 0);
    put16(m, L_DESC + 19, L_WAVES);
    m[L_ORDER_COUNT] = (uint8_t)(s->orders * 2);
    memcpy(m + L_DUTY, s->duty, sizeof(s->duty));
    memcpy(m + L_WAVE, s->wave, sizeof(s->wave));
    memcpy(m + L_NOISE, s->noise, sizeof(s->noise));
    memcpy(m + L_WAVES, s->waves, sizeof(s->waves));
    for (int p = 0; p < MAX_PATS; ++p)
        for (int ch = 0; ch < 4; ++ch) memcpy(m + slot_addr(p * 4 + ch), s->pats[p][ch], 192);
    for (int o = 0; o < s->orders; ++o)
        for (int ch = 0; ch < 4; ++ch) put16(m, L_ORDERS + ch * 256 + o * 2, slot_addr(s->order_pat[o] * 4 + ch));
}

/* ---- observation helpers ---- */
typedef struct Tick {
    int order, row, tick; /* position processed by this tick */
    int n;
    uint8_t w[2 * 1024];
    int frames;
    double rms;
} Tick;

static float g_audio[HM_MAX_TICK_FRAMES * 2];

static int run_tick(HugeMachine* m, Tick* t) {
    hm_position(m, &t->order, &t->row, &t->tick);
    t->frames = hm_tick(m, g_audio);
    t->n = hm_apu_log(m, t->w, 1024);
    double sum = 0;
    for (int i = 0; i < t->frames * 2; ++i) sum += (double)g_audio[i] * g_audio[i];
    t->rms = t->frames > 0 ? sqrt(sum / (t->frames * 2)) : 0;
    return t->frames;
}

/* Last value written to `reg` in the tick, or -1. */
static int last_write(const Tick* t, int reg) {
    int v = -1;
    for (int i = 0; i < t->n; ++i)
        if (t->w[2 * i] == reg) v = t->w[2 * i + 1];
    return v;
}
static int count_writes(const Tick* t, int reg) {
    int c = 0;
    for (int i = 0; i < t->n; ++i) c += t->w[2 * i] == reg;
    return c;
}
static int ch1_period(const Tick* t) {
    int lo = last_write(t, NR13), hi = last_write(t, NR14);
    return lo < 0 || hi < 0 ? -1 : ((hi & 7) << 8) | lo;
}

static HugeImage g_img;

static HugeMachine* start(HugeMachine* m, const TSong* s, uint32_t version, int order, int row, int* mode) {
    build(s, &g_img, version);
    int dummy;
    int r = hm_start_song(m, &g_img, order, row, 12000, mode ? mode : &dummy);
    EXPECT(r == HM_OK, "hm_start_song returned %d", r);
    hm_set_apu_logging(m, 1);
    return m;
}

static void run_until(HugeMachine* m, int order, int row, int tick, int limit) {
    Tick t;
    for (int i = 0; i < limit; ++i) {
        int o, r, k;
        hm_position(m, &o, &r, &k);
        if (o == order && r == row && k == tick) return;
        run_tick(m, &t);
    }
    EXPECT(0, "position %d/%d/%d not reached", order, row, tick);
}

/* ---- tests ---- */
static void test_note_trigger_and_audio(HugeMachine* m) {
    printf("note trigger, instrument bytes, audio\n");
    TSong s;
    song_init(&s, 6, 2);
    cell(&s, 0, 0, 0, 24, 1, 0, 0); /* C-5 duty instr 1 */
    start(m, &s, 1, 0, 0, NULL);
    Tick t;
    run_tick(m, &t);
    EXPECT(t.order == 0 && t.row == 0 && t.tick == 0, "first tick position %d/%d/%d", t.order, t.row, t.tick);
    EXPECT(last_write(&t, NR10) == 0x00, "NR10 %02X", last_write(&t, NR10));
    EXPECT(last_write(&t, NR11) == 0x80, "NR11 %02X", last_write(&t, NR11));
    EXPECT(last_write(&t, NR12) == 0xF0, "NR12 %02X", last_write(&t, NR12));
    EXPECT(last_write(&t, NR13) == (kPeriod[24] & 0xFF), "NR13 %02X", last_write(&t, NR13));
    EXPECT(last_write(&t, NR14) == (0x80 | (kPeriod[24] >> 8)), "NR14 %02X", last_write(&t, NR14));
    EXPECT(t.frames >= 803 && t.frames <= 805, "frames per tick %d", t.frames);
    double rms = 0;
    for (int i = 0; i < 20; ++i) {
        run_tick(m, &t);
        rms += t.rms;
    }
    EXPECT(rms / 20 > 0.02, "pulse audible, mean rms %.4f", rms / 20);
}

static void test_row_and_order_timing(HugeMachine* m) {
    printf("row/order timing and wrap\n");
    TSong s;
    song_init(&s, 6, 2);
    start(m, &s, 1, 0, 0, NULL);
    Tick t;
    for (int i = 0; i < 6; ++i) run_tick(m, &t);
    int o, r, k;
    hm_position(m, &o, &r, &k);
    EXPECT(o == 0 && r == 1 && k == 0, "after 6 ticks %d/%d/%d", o, r, k);
    for (int i = 0; i < 63 * 6; ++i) run_tick(m, &t);
    hm_position(m, &o, &r, &k);
    EXPECT(o == 1 && r == 0 && k == 0, "after one pattern %d/%d/%d", o, r, k);
    for (int i = 0; i < 64 * 6; ++i) run_tick(m, &t);
    hm_position(m, &o, &r, &k);
    EXPECT(o == 0 && r == 0, "song wraps to order 0: %d/%d", o, r);
}

static void test_live_cell_edit(HugeMachine* m) {
    printf("live cell edit reaches an upcoming row without moving transport\n");
    TSong s;
    song_init(&s, 4, 2);
    cell(&s, 0, 0, 0, 24, 1, 0, 0);
    start(m, &s, 1, 0, 0, NULL);
    Tick t;
    run_tick(m, &t);
    run_tick(m, &t);
    int o0, r0, k0, o1, r1, k1;
    hm_position(m, &o0, &r0, &k0);
    cell(&s, 0, 0, 3, 28, 1, 0, 0); /* E-5 on row 3 */
    build(&s, &g_img, 2);
    hm_apply_image(m, &g_img);
    hm_position(m, &o1, &r1, &k1);
    EXPECT(o0 == o1 && r0 == r1 && k0 == k1, "apply moved transport %d/%d/%d -> %d/%d/%d", o0, r0, k0, o1, r1, k1);
    EXPECT(hm_image_version(m) == 2, "image version %u", hm_image_version(m));
    run_until(m, 0, 3, 0, 64);
    run_tick(m, &t);
    EXPECT(ch1_period(&t) == kPeriod[28], "edited note period %d", ch1_period(&t));
    EXPECT((last_write(&t, NR14) & 0x80) != 0, "edited note retriggers");

    printf("live order edit switches the current order's pattern at the next row\n");
    cell(&s, 1, 1, 5, 31, 1, 0, 0); /* pattern 1 ch2 row 5: G-5 */
    s.order_pat[0] = 1;
    build(&s, &g_img, 3);
    hm_apply_image(m, &g_img);
    run_until(m, 0, 5, 0, 64);
    run_tick(m, &t);
    int lo = last_write(&t, NR23), hi = last_write(&t, NR24);
    EXPECT(lo >= 0 && hi >= 0 && (((hi & 7) << 8) | lo) == kPeriod[31], "CH2 from re-resolved pattern: %d %d", lo, hi);
}

static void test_live_instrument_edit(HugeMachine* m) {
    printf("instrument edit: sustained note untouched, next note uses new registers\n");
    TSong s;
    song_init(&s, 4, 1);
    cell(&s, 0, 0, 0, 24, 1, 0, 0);
    cell(&s, 0, 0, 4, 26, 1, 0, 0);
    start(m, &s, 1, 0, 0, NULL);
    Tick t;
    run_tick(m, &t);
    EXPECT(last_write(&t, NR12) == 0xF0, "initial NR12 %02X", last_write(&t, NR12));
    s.duty[0][2] = 0x83; /* volume 8, decreasing, pace 3 */
    s.duty[0][1] = 0xC0; /* 75% duty */
    build(&s, &g_img, 2);
    hm_apply_image(m, &g_img);
    run_tick(m, &t);
    EXPECT(count_writes(&t, NR12) == 0 && count_writes(&t, NR11) == 0, "sustained note was rewritten");
    run_until(m, 0, 4, 0, 64);
    run_tick(m, &t);
    EXPECT(last_write(&t, NR12) == 0x83, "new NR12 %02X", last_write(&t, NR12));
    EXPECT(last_write(&t, NR11) == 0xC0, "new NR11 %02X", last_write(&t, NR11));
}

static void test_live_tempo_and_waves(HugeMachine* m) {
    printf("base tempo edit applies immediately; wave edit reloads at the next CH3 note\n");
    TSong s;
    song_init(&s, 8, 1);
    cell(&s, 0, 2, 0, 24, 1, 0, 0);
    cell(&s, 0, 2, 2, 24, 1, 0, 0);
    start(m, &s, 1, 0, 0, NULL);
    Tick t;
    run_tick(m, &t);
    EXPECT(count_writes(&t, NR30) >= 1, "wave load toggles NR30");
    for (int i = 0; i < 4; ++i) run_tick(m, &t); /* now row 0 tick 5 */
    s.tpr = 3;
    s.waves[0][0] = 0x5A;
    build(&s, &g_img, 2);
    hm_apply_image(m, &g_img);
    EXPECT(hm_peek(m, 0xC000 + 0x1C) == HM_NO_WAVE, "current_wave not invalidated");
    run_tick(m, &t);
    int o, r, k;
    hm_position(m, &o, &r, &k);
    EXPECT(r == 1 && k == 0, "row ends promptly after tempo cut: %d/%d", r, k);
    /* The shortened row ended on tick 5; the edited speed is installed for the next row. */
    EXPECT(hm_peek(m, 0xC000 + 0x1B) == 3, "ticks_per_row %d", hm_peek(m, 0xC000 + 0x1B));
    for (int i = 0; i < 3; ++i) run_tick(m, &t);
    hm_position(m, &o, &r, &k);
    EXPECT(r == 2 && k == 0, "3 ticks per row now: %d/%d", r, k);
    run_tick(m, &t); /* row 2 note reloads the wave */
    EXPECT(count_writes(&t, NR30) >= 2, "wave reloaded (NR30 off/on) on the next note");
    EXPECT(hm_peek(m, 0xC000 + 0x1C) == 0, "current_wave = 0 after reload");
}

static void test_live_tempo_to_one_midrow(HugeMachine* m) {
    printf("base tempo edit to 1 mid-row ends the row without replaying it\n");
    TSong s;
    song_init(&s, 8, 1);
    cell(&s, 0, 0, 0, 24, 1, 0, 0); /* row 0 triggers CH1; row 1 is empty */
    start(m, &s, 1, 0, 0, NULL);
    Tick t;
    for (int i = 0; i < 5; ++i) run_tick(m, &t); /* row 0 ticks 0..4 played */
    int o, r, k;
    hm_position(m, &o, &r, &k);
    EXPECT(o == 0 && r == 0 && k == 5, "before edit %d/%d/%d", o, r, k);
    s.tpr = 1;
    build(&s, &g_img, 2);
    hm_apply_image(m, &g_img);
    run_tick(m, &t);
    EXPECT(t.row == 0 && t.tick != 0, "row 0 must not restart at tick 0 (processed %d/%d)", t.row, t.tick);
    EXPECT(count_writes(&t, NR14) == 0 && count_writes(&t, NR12) == 0, "row 0 note retriggered (NR14 writes %d)",
           count_writes(&t, NR14));
    hm_position(m, &o, &r, &k);
    EXPECT(r == 1 && k == 0, "next tick ends the row: %d/%d", r, k);
    run_tick(m, &t);
    hm_position(m, &o, &r, &k);
    EXPECT(r == 2 && k == 0, "1 tick per row afterwards: %d/%d", r, k);
    EXPECT(hm_peek(m, 0xC000 + 0x1B) == 1, "ticks_per_row %d", hm_peek(m, 0xC000 + 0x1B));
}

static void test_seek(HugeMachine* m) {
    printf("seek reconstructs earlier driver and APU state\n");
    TSong s;
    song_init(&s, 6, 3);
    cell(&s, 0, 0, 0, 24, 2, 0xF, 0x03); /* order 0: instrument 2, speed 3 */
    cell(&s, 0, 1, 0, 90, 0, 0x5, 0x35); /* order 0: master volume NR50 = $35 */
    cell(&s, 1, 0, 8, 31, 0, 0, 0);      /* order 1 row 8: note without instrument */
    int mode = -1;
    struct timespec a, b;
    clock_gettime(CLOCK_MONOTONIC, &a);
    start(m, &s, 1, 1, 8, &mode);
    clock_gettime(CLOCK_MONOTONIC, &b);
    printf("  seek to 1/8: %.2f ms\n", (b.tv_sec - a.tv_sec) * 1e3 + (b.tv_nsec - a.tv_nsec) / 1e6);
    EXPECT(mode == HM_SEEK_FROM_START, "seek mode %d", mode);
    EXPECT(hm_peek(m, 0xC000 + 0x1B) == 3, "Fxx speed from order 0 reconstructed: %d", hm_peek(m, 0xC000 + 0x1B));
    EXPECT(hm_peek(m, 0xFF24) == 0x35, "5xx NR50 from order 0 reconstructed: %02X", hm_peek(m, 0xFF24));
    Tick t;
    run_tick(m, &t);
    EXPECT(t.order == 1 && t.row == 8 && t.tick == 0, "seek position %d/%d/%d", t.order, t.row, t.tick);
    EXPECT(ch1_period(&t) == kPeriod[31], "seek note period %d", ch1_period(&t));
    EXPECT((last_write(&t, NR14) & 0x80) == 0, "instrument-less note is legato (no trigger)");
    EXPECT(count_writes(&t, NR12) == 0, "instrument 0 must not reload the envelope");

    printf("seek falls back to order start when the row is unreachable\n");
    song_init(&s, 6, 3);
    cell(&s, 0, 0, 0, 24, 1, 0xB, 0x01); /* B01: order 0 loops forever */
    start(m, &s, 1, 2, 5, &mode);
    EXPECT(mode == HM_SEEK_FROM_ORDER, "fallback mode %d", mode);
    run_tick(m, &t);
    EXPECT(t.order == 2 && t.row == 5 && t.tick == 0, "fallback position %d/%d/%d", t.order, t.row, t.tick);

    printf("deep seek budget (12000 ticks)\n");
    song_init(&s, 6, 16);
    for (int p = 0; p < MAX_PATS; ++p)
        for (int r = 0; r < 64; r += 2) cell(&s, p, r % 4, r, 24 + (r % 12), 1, 0, 0);
    clock_gettime(CLOCK_MONOTONIC, &a);
    start(m, &s, 1, 15, 60, &mode);
    clock_gettime(CLOCK_MONOTONIC, &b);
    printf("  seek to 15/60 (~6100 ticks): %.2f ms, mode %d\n",
           (b.tv_sec - a.tv_sec) * 1e3 + (b.tv_nsec - a.tv_nsec) / 1e6, mode);
    EXPECT(mode == HM_SEEK_FROM_START, "deep seek mode %d", mode);
}

static void effect_song(TSong* s, int fx, int param) {
    song_init(s, 6, 3);
    cell(s, 0, 0, 0, 24, 1, fx, param);
}

static void test_effects(HugeMachine* m) {
    printf("row effects are executed by the driver\n");
    TSong s;
    Tick t[6];
    const int base = kPeriod[24];

    effect_song(&s, 0x0, 0x37); /* arpeggio */
    start(m, &s, 1, 0, 0, NULL);
    int seen0 = 0, seen3 = 0, seen7 = 0;
    for (int i = 0; i < 6; ++i) {
        run_tick(m, &t[i]);
        int p = ch1_period(&t[i]);
        seen0 |= p == base;
        seen3 |= p == kPeriod[27];
        seen7 |= p == kPeriod[31];
    }
    EXPECT(seen0 && seen3 && seen7, "0xy arpeggio periods %d %d %d", seen0, seen3, seen7);

    effect_song(&s, 0x1, 0x10);
    start(m, &s, 1, 0, 0, NULL);
    run_tick(m, &t[0]);
    run_tick(m, &t[1]);
    run_tick(m, &t[2]);
    EXPECT(ch1_period(&t[1]) == base + 16 && ch1_period(&t[2]) == base + 32, "1xx porta up %d %d",
           ch1_period(&t[1]), ch1_period(&t[2]));

    effect_song(&s, 0x2, 0x10);
    start(m, &s, 1, 0, 0, NULL);
    run_tick(m, &t[0]);
    run_tick(m, &t[1]);
    EXPECT(ch1_period(&t[1]) == base - 16, "2xx porta down %d", ch1_period(&t[1]));

    song_init(&s, 6, 2);
    cell(&s, 0, 0, 0, 24, 1, 0, 0);
    cell(&s, 0, 0, 1, 28, 1, 0x3, 0x20); /* toneporta toward E-5 */
    start(m, &s, 1, 0, 0, NULL);
    for (int i = 0; i < 6; ++i) run_tick(m, &t[0]);
    run_tick(m, &t[0]); /* row 1 tick 0: no retrigger */
    EXPECT(count_writes(&t[0], NR14) == 0 || (last_write(&t[0], NR14) & 0x80) == 0, "3xx must not retrigger");
    run_tick(m, &t[1]);
    EXPECT(ch1_period(&t[1]) == base + 0x20, "3xx step %d", ch1_period(&t[1]));
    for (int i = 0; i < 4; ++i) run_tick(m, &t[2]);
    EXPECT(ch1_period(&t[2]) <= kPeriod[28] && ch1_period(&t[2]) > base + 0x20, "3xx progresses %d",
           ch1_period(&t[2]));

    effect_song(&s, 0x4, 0x12); /* vibrato speed 1 (alternate ticks), depth 2 */
    start(m, &s, 1, 0, 0, NULL);
    int up = 0, back = 0;
    for (int i = 0; i < 6; ++i) {
        run_tick(m, &t[i]);
        if (i == 0) continue;
        up |= ch1_period(&t[i]) == base + 2;
        back |= ch1_period(&t[i]) == base;
    }
    EXPECT(up && back, "4xy vibrato %d %d", up, back);

    effect_song(&s, 0x5, 0x35);
    start(m, &s, 1, 0, 0, NULL);
    run_tick(m, &t[0]);
    EXPECT(last_write(&t[0], NR50) == 0x35, "5xx NR50 %02X", last_write(&t[0], NR50));

    effect_song(&s, 0x7, 0x03);
    start(m, &s, 1, 0, 0, NULL);
    for (int i = 0; i < 4; ++i) run_tick(m, &t[i]);
    EXPECT(last_write(&t[0], NR14) < 0 || (last_write(&t[0], NR14) & 0x80) == 0, "7xx: no trigger on tick 0");
    EXPECT((last_write(&t[3], NR14) & 0x80) != 0 && ch1_period(&t[3]) == base, "7xx: trigger on tick 3");

    effect_song(&s, 0x8, 0x5A);
    start(m, &s, 1, 0, 0, NULL);
    run_tick(m, &t[0]);
    EXPECT(last_write(&t[0], NR51) == 0x5A, "8xx NR51 %02X", last_write(&t[0], NR51));

    effect_song(&s, 0x9, 0x40);
    start(m, &s, 1, 0, 0, NULL);
    run_tick(m, &t[0]);
    EXPECT(last_write(&t[0], NR11) == 0x40, "9xx NR11 %02X", last_write(&t[0], NR11));

    effect_song(&s, 0xA, 0x02); /* volume slide down 2 */
    start(m, &s, 1, 0, 0, NULL);
    run_tick(m, &t[0]);
    EXPECT(last_write(&t[0], NR12) == 0xD0, "Axy NR12 %02X", last_write(&t[0], NR12));

    song_init(&s, 2, 3);
    cell(&s, 0, 0, 5, 24, 1, 0xB, 0x03); /* jump to order 2 */
    start(m, &s, 1, 0, 0, NULL);
    run_until(m, 2, 0, 0, 20);

    effect_song(&s, 0xC, 0x05);
    start(m, &s, 1, 0, 0, NULL);
    run_tick(m, &t[0]);
    EXPECT(last_write(&t[0], NR12) == 0x50, "Cxx NR12 %02X", last_write(&t[0], NR12));

    song_init(&s, 2, 3);
    cell(&s, 0, 0, 2, 24, 1, 0xD, 0x11); /* break to next order, row 16 */
    start(m, &s, 1, 0, 0, NULL);
    run_until(m, 1, 16, 0, 20);

    effect_song(&s, 0xE, 0x03);
    start(m, &s, 1, 0, 0, NULL);
    for (int i = 0; i < 4; ++i) run_tick(m, &t[i]);
    EXPECT(last_write(&t[3], NR12) == 0x00 && last_write(&t[3], NR14) == 0xFF, "Exx cut on tick 3: %02X %02X",
           last_write(&t[3], NR12), last_write(&t[3], NR14));
    EXPECT(count_writes(&t[2], NR12) == 0, "Exx: nothing before tick 3");

    effect_song(&s, 0xF, 0x03);
    start(m, &s, 1, 0, 0, NULL);
    for (int i = 0; i < 3; ++i) run_tick(m, &t[0]);
    int o, r, k;
    hm_position(m, &o, &r, &k);
    EXPECT(r == 1 && k == 0, "Fxx speed 3: %d/%d", r, k);

    printf("wave and noise channels\n");
    song_init(&s, 6, 1);
    cell(&s, 0, 2, 0, 24, 1, 0, 0);
    cell(&s, 0, 3, 0, 40, 2, 0, 0);
    start(m, &s, 1, 0, 0, NULL);
    run_tick(m, &t[0]);
    EXPECT(last_write(&t[0], NR32) == 0x20, "NR32 %02X", last_write(&t[0], NR32));
    /* play_ch3_note re-arms the DAC last: NR30 = 0 then cpl -> $FF. */
    EXPECT(last_write(&t[0], NR30) == 0xFF, "NR30 %02X", last_write(&t[0], NR30));
    EXPECT(last_write(&t[0], NR34) == (0x80 | (kPeriod[24] >> 8)), "NR34 %02X", last_write(&t[0], NR34));
    int a = 63 - 40, poly = a < 7 ? a : (((a >> 2) - 1) << 4) | ((a & 3) + 4);
    EXPECT(last_write(&t[0], NR42) == 0xA2, "NR42 %02X", last_write(&t[0], NR42));
    EXPECT(last_write(&t[0], NR41) == 0x05, "NR41 %02X", last_write(&t[0], NR41));
    EXPECT(last_write(&t[0], NR43) == (poly | 0x08), "NR43 %02X expected %02X", last_write(&t[0], NR43), poly | 0x08);
    EXPECT(last_write(&t[0], NR44) == 0xC0, "NR44 %02X", last_write(&t[0], NR44));
}

/* Microtuning relies on 40y: a fixed +y period detune from tick 1, held on later rows without the effect. */
static void test_fixed_detune(HugeMachine* m) {
    printf("40y fixed detune: tick 0 untuned, held across effect-less rows, re-applied after a legato note\n");
    TSong s;
    Tick t;
    const int base = kPeriod[36];
    song_init(&s, 4, 1);
    cell(&s, 0, 0, 0, 36, 1, 0x4, 0x03); /* C-6 +3 units */
    cell(&s, 0, 0, 3, 38, 0, 0x4, 0x05); /* legato D-6 +5 units */
    start(m, &s, 1, 0, 0, NULL);
    run_tick(m, &t);
    EXPECT(ch1_period(&t) == base, "tick 0 plays the table period %d", ch1_period(&t));
    int detuned = 1, rewrites = 0;
    for (int i = 1; i < 12; ++i) { /* rest of row 0, then rows 1-2 without effects */
        run_tick(m, &t);
        int p = ch1_period(&t);
        if (p >= 0) { ++rewrites; detuned &= p == base + 3; }
        if (i == 1) EXPECT(p == base + 3, "tick 1 applies +3: %d", p);
    }
    EXPECT(detuned, "only the detuned period is written while held (%d writes)", rewrites);
    EXPECT(count_writes(&t, NR12) == 0, "no retrigger while held");
    run_tick(m, &t); /* row 3 tick 0: legato note at its table period */
    EXPECT(ch1_period(&t) == kPeriod[38] && (last_write(&t, NR14) & 0x80) == 0, "legato note %d", ch1_period(&t));
    run_tick(m, &t);
    EXPECT(ch1_period(&t) == kPeriod[38] + 5, "legato note +5 from tick 1: %d", ch1_period(&t));
}

static void test_audition_and_mute(HugeMachine* m, HugeMachine* aud) {
    printf("audition machine and channel borrowing\n");
    TSong s;
    song_init(&s, 4, 1);
    for (int r = 0; r < 64; r += 4) cell(&s, 0, 0, r, 24, 1, 0, 0);
    build(&s, &g_img, 1);
    EXPECT(hm_start_audition(aud, &g_img, 0, 0, 0, 0) != HM_OK, "instrument 0 must be rejected");
    EXPECT(hm_start_audition(aud, &g_img, 0, 36, 1, 16) != HM_OK, "detune 16 must be rejected");
    EXPECT(hm_start_audition(aud, &g_img, 0, 36, 1, -1) != HM_OK, "negative detune must be rejected");
    EXPECT(hm_start_audition(aud, &g_img, 3, 30, 1, 0) == HM_OK, "audition start");
    hm_set_apu_logging(aud, 1);
    Tick t;
    run_tick(aud, &t);
    EXPECT(last_write(&t, NR42) == 0xF1 && last_write(&t, NR44) == 0x80, "audition noise trigger");
    double rms = 0;
    for (int i = 0; i < 10; ++i) {
        run_tick(aud, &t);
        rms += t.rms;
    }
    EXPECT(rms / 10 > 0.01, "audition audible %.4f", rms / 10);
    EXPECT(hm_start_audition(aud, &g_img, 2, 24, 1, 0) == HM_OK, "wave audition");
    hm_set_apu_logging(aud, 1);
    run_tick(aud, &t);
    EXPECT(last_write(&t, NR32) == 0x20 && (last_write(&t, NR34) & 0x80), "wave audition trigger");

    printf("detuned audition: 40y from tick 1, plain without detune\n");
    const int base = kPeriod[36];
    for (int detune = 0; detune <= 5; detune += 5) {
        EXPECT(hm_start_audition(aud, &g_img, 0, 36, 1, detune) == HM_OK, "CH1 audition detune %d", detune);
        hm_set_apu_logging(aud, 1);
        run_tick(aud, &t);
        EXPECT(ch1_period(&t) == base && (last_write(&t, NR14) & 0x80), "detune %d: tick 0 triggers the table period %d",
               detune, ch1_period(&t));
        int held = 1, later = -1;
        for (int i = 1; i < 12; ++i) {
            run_tick(aud, &t);
            int p = ch1_period(&t);
            if (p >= 0) { later = p; held &= p == base + detune; }
            if (count_writes(&t, NR12) != 0) held = 0; /* no retrigger */
        }
        if (detune) EXPECT(later == base + detune, "detune %d: later tick writes %d", detune, later);
        else EXPECT(later < 0 || later == base, "no detune: period stays %d", later);
        EXPECT(held, "detune %d: only period %d while held", detune, base + detune);
    }
    EXPECT(hm_start_audition(aud, &g_img, 3, 30, 1, 5) == HM_OK, "noise audition ignores detune");
    hm_set_apu_logging(aud, 1);
    run_tick(aud, &t);
    EXPECT(last_write(&t, NR42) == 0xF1 && last_write(&t, NR44) == 0x80, "detuned noise trigger unchanged");

    start(m, &s, 1, 0, 0, NULL);
    run_tick(m, &t);
    EXPECT(hm_mute_channel(m, 0, 1) == HM_OK, "mute");
    t.n = hm_apu_log(m, t.w, 1024);
    EXPECT(last_write(&t, NR12) == 0x00 && last_write(&t, NR14) == 0xFF, "mute cuts the channel");
    run_until(m, 0, 4, 0, 32);
    run_tick(m, &t);
    EXPECT(count_writes(&t, NR13) == 0 && count_writes(&t, NR12) == 0, "muted channel left alone");
    EXPECT(hm_mute_channel(m, 0, 0) == HM_OK, "unmute");
    t.n = hm_apu_log(m, t.w, 1024);
    run_until(m, 0, 8, 0, 32);
    run_tick(m, &t);
    EXPECT(last_write(&t, NR12) == 0xF0 && (last_write(&t, NR14) & 0x80), "restored at next note");
}

static void test_invalid_images(HugeMachine* m) {
    printf("invalid images are rejected, not executed\n");
    TSong s;
    song_init(&s, 6, 1);
    build(&s, &g_img, 1);
    g_img.mem[L_ORDER_COUNT] = 3;
    int mode;
    EXPECT(hm_start_song(m, &g_img, 0, 0, 10, &mode) == HM_ERR_IMAGE, "odd order count");
    build(&s, &g_img, 1);
    put16(g_img.mem, L_ORDERS, 0xC050); /* points into driver variables */
    EXPECT(hm_validate(&g_img) == HM_ERR_IMAGE, "track pointer outside song regions");
}

/* Two orders with all four channels busy; also rendered to host_demo.wav. */
static void demo_song(TSong* s) {
    song_init(s, 6, 2);
    static const int melody[16] = {24, 28, 31, 36, 31, 28, 26, 29, 33, 38, 33, 29, 24, 31, 36, 40};
    for (int r = 0; r < 64; r += 4) {
        cell(s, 0, 0, r, melody[(r / 4) % 16], 1, r == 16 ? 0x4 : 0, r == 16 ? 0x13 : 0);
        cell(s, 0, 1, r + 2, melody[(r / 4 + 3) % 16] - 12, 2, 0, 0);
        cell(s, 0, 2, r, melody[(r / 8) % 16] - 12, 1, 0, 0);
        cell(s, 0, 3, r, 40 + (r % 8), (r % 8) ? 2 : 1, 0, 0);
        cell(s, 1, 0, r, melody[15 - (r / 4) % 16], 2, r == 32 ? 0x0 : 0, r == 32 ? 0x47 : 0);
        cell(s, 1, 2, r, melody[(r / 8) % 16] - 12, 1, 0, 0);
        cell(s, 1, 3, r + 2, 44, 1, 0, 0);
    }
    cell(s, 1, 1, 0, 36, 1, 0x1, 0x02);
}

/* Runs `ticks` ticks through the ops; returns the peak sample, -1 if a tick returned no frames. */
static float ops_render(const ChipEngineOps* ops, ChipMachine* m, int ticks) {
    static float buf[CHIP_MAX_TICK_FRAMES * 2];
    float peak = 0;
    for (int i = 0; i < ticks; ++i) {
        const int k = ops->tick(m, buf);
        if (k <= 0 || k > (int)CHIP_MAX_TICK_FRAMES) return -1;
        for (int j = 0; j < 2 * k; ++j) peak = fabsf(buf[j]) > peak ? fabsf(buf[j]) : peak;
    }
    return peak;
}

static void test_chip_engine_ops(void) {
    printf("chip engine ops: gb-huge as the audio hosts drive it\n");
    const ChipEngineOps* ops = chip_engine_ops("gb-huge");
    EXPECT(ops && strcmp(ops->system_id, "gb-huge") == 0, "gb-huge is registered");
    EXPECT(chip_engine_ops("nes-famistudio") == NULL && chip_engine_ops(NULL) == NULL, "unknown systems have no engine");
    if (!ops) return;
    EXPECT(ops->image_size == 0x10000 && ops->image_storage >= ops->image_size, "image %zu bytes, storage %zu",
           ops->image_size, ops->image_storage);
    ChipImage* img = (ChipImage*)calloc(1, ops->image_storage);
    ChipMachine* m = ops->create(48000);
    ChipMachine* aud = ops->create(48000);
    if (!img || !m || !aud) {
        EXPECT(0, "ops create");
        return;
    }
    TSong s;
    demo_song(&s);
    build(&s, &g_img, 1);
    EXPECT(ops->load_image(img, g_img.mem, 0x100) == CHIP_ERR_IMAGE, "short image rejected");
    EXPECT(ops->load_image(img, g_img.mem, sizeof g_img.mem) == CHIP_OK, "demo image loads");

    int mode = -1, o = -1, r = -1, t = -1;
    EXPECT(ops->start(m, img, 0, 0, 12000, &mode) == CHIP_OK && mode == CHIP_SEEK_FROM_START, "start at 0/0");
    const float peak = ops_render(ops, m, 120);
    EXPECT(peak > 0.05f, "two seconds of the demo are audible: peak %.3f", peak);
    ops->position(m, &o, &r, &t);
    EXPECT(o == 0 && r == 20 && t == 0, "position advanced to order 0 row 20 tick 0: %d/%d/%d", o, r, t);

    cell(&s, 0, 0, 24, 30, 2, 0, 0);
    build(&s, &g_img, 2);
    EXPECT(ops->load_image(img, g_img.mem, sizeof g_img.mem) == CHIP_OK, "edited image loads");
    ops->apply_image(m, img);
    ops->position(m, &o, &r, &t);
    EXPECT(o == 0 && r == 20 && t == 0, "live apply keeps the transport: %d/%d/%d", o, r, t);
    EXPECT(ops_render(ops, m, 30) > 0, "renders after a live apply");

    EXPECT(ops->start(m, img, 1, 8, 12000, &mode) == CHIP_OK, "seek to order 1 row 8");
    ops->position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 8 && t == 0, "seek position %d/%d/%d", o, r, t);
    EXPECT(ops->mute(m, 0, 1) == CHIP_OK && ops->mute(m, 0, 0) == CHIP_OK, "mute and unmute a channel");
    EXPECT(ops->mute(m, 4, 1) != CHIP_OK, "no channel 4");

    EXPECT(ops->audition(aud, img, 0, 36, 0, 0) == CHIP_ERR_IMAGE, "audition without an instrument rejected");
    EXPECT(ops->audition(aud, img, 0, 36, 1, 3) == CHIP_OK, "audition C-6 detuned");
    const float held = ops_render(ops, aud, 20);
    EXPECT(held > 0.05f, "audition audible: peak %.3f", held);
    EXPECT(ops->release(aud) == 0, "no release phase: the host drops the machine at once");

    ops->destroy(m);
    ops->destroy(aud);
    free(img);
}

static void write_wav(const char* path, const float* data, int frames) {
    FILE* f = fopen(path, "wb");
    if (!f) return;
    int bytes = frames * 4;
    int hdr[] = {0x46464952, 36 + bytes, 0x45564157, 0x20746d66, 16, 0x00020001, 48000, 48000 * 4, 0x00100004,
                 0x61746164, bytes};
    fwrite(hdr, sizeof(hdr), 1, f);
    for (int i = 0; i < frames * 2; ++i) {
        float v = data[i] < -1 ? -1 : data[i] > 1 ? 1 : data[i];
        short sample = (short)(v * 32767);
        fwrite(&sample, 2, 1, f);
    }
    fclose(f);
}

static void render_demo_and_measure(HugeMachine* m, const char* dir) {
    printf("demo render, trace and realtime cost\n");
    TSong s;
    demo_song(&s);
    start(m, &s, 1, 0, 0, NULL);
    const int ticks = 60 * 20;
    float* wav = (float*)malloc(sizeof(float) * 2 * HM_MAX_TICK_FRAMES * ticks);
    char path[512];
    snprintf(path, sizeof path, "%s/host_trace.txt", dir);
    FILE* trace = fopen(path, "w");
    int frames = 0;
    Tick t;
    struct timespec a, b;
    clock_gettime(CLOCK_MONOTONIC, &a);
    for (int i = 0; i < ticks; ++i) {
        hm_position(m, &t.order, &t.row, &t.tick);
        int k = hm_tick(m, wav + 2 * frames);
        t.n = hm_apu_log(m, t.w, 1024);
        if (trace) {
            fprintf(trace, "%d %d %d %d:", i, t.order, t.row, t.tick);
            for (int j = 0; j < t.n; ++j) fprintf(trace, " FF%02X=%02X", 0x10 + t.w[2 * j], t.w[2 * j + 1]);
            fprintf(trace, "\n");
        }
        if (k > 0) frames += k;
    }
    clock_gettime(CLOCK_MONOTONIC, &b);
    double ms = (b.tv_sec - a.tv_sec) * 1e3 + (b.tv_nsec - a.tv_nsec) / 1e6;
    printf("  %d ticks (%.1f s of audio) rendered in %.1f ms: %.1f us/tick, %.1fx realtime\n", ticks,
           frames / 48000.0, ms, ms * 1000 / ticks, (frames / 48000.0) / (ms / 1000));
    if (trace) fclose(trace);
    snprintf(path, sizeof path, "%s/host_demo.wav", dir);
    write_wav(path, wav, frames);
    printf("  wrote %s and host_trace.txt (tick order row tick: FFxx=value writes)\n", path);
    free(wav);
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    HugeMachine* m = hm_create(48000);
    HugeMachine* aud = hm_create(48000);
    if (!m || !aud) {
        printf("hm_create failed\n");
        return 2;
    }
    test_note_trigger_and_audio(m);
    test_row_and_order_timing(m);
    test_live_cell_edit(m);
    test_live_instrument_edit(m);
    test_live_tempo_and_waves(m);
    test_live_tempo_to_one_midrow(m);
    test_seek(m);
    test_effects(m);
    test_fixed_detune(m);
    test_audition_and_mute(m, aud);
    test_invalid_images(m);
    test_chip_engine_ops();
    render_demo_and_measure(m, dir);
    hm_destroy(m);
    hm_destroy(aud);
    printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
