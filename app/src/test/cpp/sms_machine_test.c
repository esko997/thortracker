/* Host regression test for the Master System system ("sms-banjo"): the Banjo driver (generated/banjo_driver_image.h,
 * from tools/audio/prepare_sms.py) on z80.h with the SN76489 (ym/ym_psg.c), the YM2413 (emu2413) and the Game Gear's
 * stereo register, playing images built here by hand in the format of docs/AUDIO_ENGINE.md ("Master System: image
 * layout") — independently of the Kotlin lowering, so the format contract is checked from both sides.
 *
 * Checks: image validation (good images load, corruptions are rejected with their reason); the driver's chip writes
 * for a PSG note (Banjo's period for A-4, full volume), a volume macro with its loop and release part, an FM note on a
 * built-in instrument (F-number, block, key, voice/volume), a custom voice's eight registers, a fixed-pitch rhythm kit
 * (pitch registers, rhythm-mode key bits) and Game Gear panning; rows lasting their speed, the order loop; seeks
 * equal to straight play (driver RAM and chips) and the order fallback; a live edit keeping the position and playing
 * the new note; audition and release (silent end); mute; the VGM export (header, loop); the ops table.
 *
 * Build and run: tools/audio/run_native_host_test.sh [evidence-dir]. Writes <dir>/sms_psg.wav, sms_gg.wav, sms_fm.wav,
 * sms_drums.wav (5 s each) and sms_fm.vgm. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "chip_engine.h"
#include "generated/banjo_driver_image.h"
#include "sms_export.h"
#include "sms_image.h"
#include "sms_machine.h"

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
#define A4_SN (0x80 | (57 - 24)) /* NOTE_ON A-4 on an SN channel: Banjo's byte 33 */
#define C4_FM (0x80 | 48)        /* NOTE_ON C-4 on the YM2413 */
#define EMPTY(rows) ((uint8_t)(BJ_END_LINE | ((rows) - 1)))

static float g_out[SMSM_MAX_TICK_FRAMES * 2];

/* ---- image builder -------------------------------------------------------------------------------------------- */

typedef struct Track {
    const uint8_t* bytes;
    int n;
} Track;
#define TRACK(...) ((Track){(const uint8_t[]){__VA_ARGS__}, (int)sizeof((const uint8_t[]){__VA_ARGS__})})

typedef struct Builder {
    SmsImage im;
    uint32_t at;
} Builder;

static uint8_t* at(Builder* b, uint32_t addr) { return b->im.bytes + SMS_META_SIZE + (addr - SMS_REGION_BASE); }

static void w16(uint8_t* p, uint32_t v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
}

static void b_start(Builder* b) {
    memset(&b->im, 0, sizeof b->im);
    b->at = SMS_REGION_BASE + BJ_HEADER_SIZE;
}

static uint16_t b_bytes(Builder* b, const uint8_t* p, int n) {
    const uint16_t a = (uint16_t)b->at;
    memcpy(at(b, a), p, (size_t)n);
    b->at += (uint32_t)n;
    return a;
}

/* An instrument record (12 bytes). */
static void rec(uint8_t* r, uint8_t flags, uint16_t vol, uint16_t arp, uint8_t ex_type, uint16_t ex, uint8_t preset, uint16_t patch) {
    r[0] = flags;
    w16(r + 1, vol);
    w16(r + 3, arp);
    r[5] = ex_type;
    w16(r + 6, ex);
    r[8] = preset;
    w16(r + 9, patch);
    r[11] = 0xFF;
}

/* The rest of the song: instruments, tracks ([order * channels + channel]), order rows and table, header, meta. */
static void b_finish(Builder* b, int layout, int pattern_length, int speed, int orders, int loop, const int* rows,
                     const uint8_t* instruments, int ninstr, const Track* tracks) {
    const int channels = sms_layout_channels(layout);
    const uint16_t table = ninstr ? b_bytes(b, instruments, BJI_SIZE * ninstr) : (uint16_t)b->at;
    uint16_t track_at[SMS_MAX_ORDERS * SMS_MAX_CHANNELS];
    for (int i = 0; i < orders * channels; ++i) track_at[i] = b_bytes(b, tracks[i].bytes, tracks[i].n);
    uint16_t row_at[SMS_MAX_ORDERS];
    for (int o = 0; o < orders; ++o) {
        uint8_t row[2 * SMS_MAX_CHANNELS];
        for (int c = 0; c < channels; ++c) w16(row + 2 * c, track_at[o * channels + c]);
        row_at[o] = b_bytes(b, row, 2 * channels);
    }
    uint8_t order_table[2 * SMS_MAX_ORDERS];
    for (int o = 0; o < orders; ++o) w16(order_table + 2 * o, row_at[o]);
    const uint16_t order_ptrs = b_bytes(b, order_table, 2 * orders);
    uint8_t* h = at(b, SMS_REGION_BASE);
    h[BJ_MAGIC] = BJ_MAGIC_BYTE;
    h[BJ_CHANNEL_COUNT] = (uint8_t)channels;
    h[BJ_FLAGS] = BJ_STATE_LOOP;
    h[BJ_HAS_CHIPS] = layout >= SMS_LAYOUT_FM ? 3 : 1;
    h[BJ_SFX_CHANNEL] = h[BJ_SFX_SUBCHANNEL] = 0xFF;
    h[BJ_TIME_BASE] = 1;
    h[BJ_SPEED_1] = h[BJ_SPEED_2] = (uint8_t)speed;
    h[BJ_PATTERN_LENGTH] = (uint8_t)pattern_length;
    h[BJ_ORDERS_LENGTH] = (uint8_t)orders;
    w16(h + BJ_INSTRUMENT_PTRS, table);
    w16(h + BJ_ORDER_PTRS, order_ptrs);
    h[BJ_TIC] = (uint8_t)(speed + 1);
    h[BJ_LINE] = (uint8_t)pattern_length;
    h[BJ_ORDER_JUMP] = 0xFF;
    h[BJ_PANNING] = 0xFF;
    uint8_t* m = b->im.bytes;
    memcpy(m + SMS_META_MAGIC, "TTSB", 4);
    m[SMS_META_VERSION] = SMS_IMAGE_VERSION;
    m[SMS_META_LAYOUT] = (uint8_t)layout;
    m[SMS_META_CHANNELS] = (uint8_t)channels;
    m[SMS_META_ORDERS] = (uint8_t)orders;
    m[SMS_META_LOOP] = (uint8_t)loop;
    m[SMS_META_PATTERN_LENGTH] = (uint8_t)pattern_length;
    w16(m + SMS_META_DATA_SIZE, b->at - SMS_REGION_BASE);
    w16(m + SMS_META_INSTRUMENTS, (uint32_t)ninstr);
    for (int o = 0; o < orders; ++o) {
        m[SMS_META_ROWS + o] = (uint8_t)(rows ? rows[o] : pattern_length);
        m[SMS_META_SPEED1 + o] = m[SMS_META_SPEED2 + o] = (uint8_t)speed;
    }
}

static int load(SmsImage* im) { return sms_load_image(im); }

/* ---- songs ---------------------------------------------------------------------------------------------------- */

static Builder g_psg, g_release, g_fm, g_custom, g_drums, g_gg, g_two, g_two_edit;

/* PSG: Square 1 plays A-4 with a plain instrument on row 0 of 4 (speed 3, one order, looping). */
static void build_psg(void) {
    Builder* b = &g_psg;
    b_start(b);
    uint8_t ins[BJI_SIZE];
    rec(ins, 0, 0xFFFF, 0xFFFF, 0, 0xFFFF, 0, 0xFFFF);
    const Track t[4] = {TRACK(BJ_INSTRUMENT_CHANGE, 0, A4_SN, EMPTY(4)), TRACK(EMPTY(4)), TRACK(EMPTY(4)), TRACK(EMPTY(4))};
    b_finish(b, SMS_LAYOUT_PSG, 4, 3, 1, 0, NULL, ins, 1, t);
}

/* A volume macro, levels 15 12 9 0, loop 1, release 3 (Thor Tracker's encoding: sustain 0..2 looping 1..2, release
 * to the end): attenuations 0 3 6 3 6 3 ... and after NOTE_RELEASE on row 3 the rest, ending silent. Speed 2. */
static void build_release(void) {
    Builder* b = &g_release;
    b_start(b);
    const uint8_t macro[] = {3 + 4, 1 + 4, 4 + 4, 0, 15 - 15, 15 - 12, 15 - 9, 15 - 0};
    const uint16_t vol = b_bytes(b, macro, sizeof macro);
    uint8_t ins[BJI_SIZE];
    rec(ins, BJ_CHAN_FLAG_VOLUME_MACRO, vol, 0xFFFF, 0, 0xFFFF, 0, 0xFFFF);
    const Track t[4] = {TRACK(BJ_INSTRUMENT_CHANGE, 0, A4_SN, BJ_END_LINE | 2, BJ_NOTE_RELEASE, BJ_END_LINE | 4),
                        TRACK(EMPTY(8)), TRACK(EMPTY(8)), TRACK(EMPTY(8))};
    b_finish(b, SMS_LAYOUT_PSG, 8, 2, 1, 0, NULL, ins, 1, t);
}

/* FM: FM 1 plays C-4 on built-in Piano (3); FM 2 loads the custom voice in g_custom. */
static void build_fm(Builder* b, int custom) {
    b_start(b);
    const uint8_t patch[8] = {0x21, 0x12, 0x1A, 0x05, 0xF4, 0xE3, 0x23, 0x46};
    const uint16_t p = b_bytes(b, patch, sizeof patch);
    uint8_t ins[2 * BJI_SIZE];
    rec(ins, 0, 0xFFFF, 0xFFFF, 0, 0xFFFF, 3 << 4, 0xFFFF);
    rec(ins + BJI_SIZE, 0, 0xFFFF, 0xFFFF, 0, 0xFFFF, BJ_FM_CUSTOM, p);
    static const uint8_t empty4[] = {EMPTY(4)};
    static const uint8_t piano[] = {BJ_INSTRUMENT_CHANGE, 0, C4_FM, EMPTY(4)};
    static const uint8_t voice[] = {BJ_INSTRUMENT_CHANGE, 1, (uint8_t)(0x80 | 45), EMPTY(4)};
    Track t[13];
    for (int c = 0; c < 13; ++c) t[c] = (Track){empty4, (int)sizeof empty4};
    t[4] = (Track){piano, (int)sizeof piano};
    if (custom) t[5] = (Track){voice, (int)sizeof voice};
    b_finish(b, SMS_LAYOUT_FM, 4, 3, 1, 0, NULL, ins, 2, t);
}

/* Rhythm mode: the bass drum loads a fixed kit (instrument 0) and plays. */
static void build_drums(void) {
    Builder* b = &g_drums;
    b_start(b);
    const uint8_t pitches[6] = {0x20, 0x05, 0x50, 0x05, 0xC0, 0x01};
    const uint16_t p = b_bytes(b, pitches, sizeof pitches);
    uint8_t ins[BJI_SIZE];
    rec(ins, 0, 0xFFFF, 0xFFFF, 0, 0xFFFF, BJ_FM_DRUMS_FIXED, p);
    static const uint8_t empty4[] = {EMPTY(4)};
    static const uint8_t kick[] = {BJ_FM_DRUM, 0, (uint8_t)(0x80 | 33), EMPTY(4)};
    Track t[15];
    for (int c = 0; c < 15; ++c) t[c] = (Track){empty4, (int)sizeof empty4};
    t[10] = (Track){kick, (int)sizeof kick};
    b_finish(b, SMS_LAYOUT_FM_DRUMS, 4, 3, 1, 0, NULL, ins, 1, t);
}

/* Game Gear: Square 2 pans left; Square 1 plays. */
static void build_gg(void) {
    Builder* b = &g_gg;
    b_start(b);
    uint8_t ins[BJI_SIZE];
    rec(ins, 0, 0xFFFF, 0xFFFF, 0, 0xFFFF, 0, 0xFFFF);
    const Track t[4] = {TRACK(BJ_INSTRUMENT_CHANGE, 0, A4_SN, EMPTY(4)), TRACK(BJ_GAME_GEAR_PAN, 0x20, 0xDD, EMPTY(4)),
                        TRACK(EMPTY(4)), TRACK(EMPTY(4))};
    b_finish(b, SMS_LAYOUT_GG, 4, 3, 1, 0, NULL, ins, 1, t);
}

/* Two orders of 8 rows (speed 3, loop 1): notes on rows 0 and 4 of each, order 1 with a speed change on row 2. The
 * edit changes order 1 row 4's note. */
static void build_two(Builder* b, int edited) {
    b_start(b);
    uint8_t ins[BJI_SIZE];
    rec(ins, 0, 0xFFFF, 0xFFFF, 0, 0xFFFF, 0, 0xFFFF);
    const uint8_t late = edited ? (uint8_t)(0x80 | 45) : (uint8_t)(0x80 | 40);
    const Track t[8] = {
        TRACK(BJ_INSTRUMENT_CHANGE, 0, (uint8_t)(0x80 | 33), BJ_END_LINE | 3, (uint8_t)(0x80 | 36), BJ_END_LINE | 3),
        TRACK(EMPTY(8)), TRACK(EMPTY(8)), TRACK(EMPTY(8)),
        TRACK(BJ_INSTRUMENT_CHANGE, 0, (uint8_t)(0x80 | 38), BJ_END_LINE | 1, BJ_SET_SPEED_1, 4, BJ_SET_SPEED_2, 4, BJ_END_LINE | 1, late,
              BJ_END_LINE | 2, BJ_ORDER_JUMP, 1, BJ_END_LINE),
        TRACK(EMPTY(8)), TRACK(EMPTY(8)), TRACK(EMPTY(8)),
    };
    b_finish(b, SMS_LAYOUT_PSG, 8, 3, 2, 1, NULL, ins, 1, t);
}

/* ---- tests ---------------------------------------------------------------------------------------------------- */

static SmsMachine* started(Builder* b) {
    SmsMachine* m = smsm_create(RATE);
    int mode = -1;
    const int r = smsm_start(m, &b->im, 0, 0, 0, &mode);
    EXPECT(r == SMSM_OK && mode == SMSM_SEEK_FROM_START, "start: %d mode %d", r, mode);
    return m;
}

static void test_images(void) {
    printf("images\n");
    Builder* all[] = {&g_psg, &g_release, &g_fm, &g_custom, &g_drums, &g_gg, &g_two, &g_two_edit};
    for (size_t i = 0; i < sizeof all / sizeof all[0]; ++i) {
        const int p = load(&all[i]->im);
        EXPECT(p == 0, "image %zu loads: %s", i, sms_image_problem(p));
    }
    Builder bad = g_psg;
    memcpy(bad.im.bytes, "XXXX", 4);
    EXPECT(load(&bad.im) != 0, "a wrong magic is rejected");
    bad = g_psg;
    bad.im.bytes[SMS_META_LAYOUT] = 7;
    EXPECT(load(&bad.im) != 0, "an unknown layout is rejected");
    bad = g_psg;
    bad.im.bytes[SMS_META_CHANNELS] = 13;
    EXPECT(load(&bad.im) != 0, "a channel count that does not match the layout is rejected");
    bad = g_psg;
    at(&bad, SMS_REGION_BASE)[BJ_CHANNEL_INIT_CALL] = 0x12;
    EXPECT(load(&bad.im) != 0, "a call field the image fills itself is rejected");
    /* Square 1's track: [INSTRUMENT_CHANGE 0, note, END_LINE+3] — find it through the order table. */
    bad = g_psg;
    const uint16_t track = sms_track(sms_region(&bad.im), &(SmsImageInfo){.order_table = sms_rd16(at(&bad, SMS_REGION_BASE) + BJ_ORDER_PTRS)}, 0, 0);
    at(&bad, track)[0] = 0x1F;
    EXPECT(load(&bad.im) != 0, "a command past the jump tables is rejected: %s", sms_image_problem(load(&bad.im)));
    bad = g_psg;
    at(&bad, track)[1] = 5;
    EXPECT(load(&bad.im) != 0, "an instrument past the table is rejected");
    bad = g_psg;
    at(&bad, track)[3] = BJ_END_LINE | 2;
    EXPECT(load(&bad.im) != 0, "a track one row short is rejected");
    bad = g_psg;
    bad.im.bytes[SMS_META_ROWS] = 5;
    EXPECT(load(&bad.im) != 0, "order rows beyond the pattern length are rejected");
    bad = g_release;
    const uint16_t vol = sms_rd16(sms_region(&bad.im) + (sms_rd16(at(&bad, SMS_REGION_BASE) + BJ_INSTRUMENT_PTRS) - SMS_REGION_BASE) + BJI_VOLUME_MACRO);
    at(&bad, vol)[1] = 2; /* a loop inside the header */
    EXPECT(load(&bad.im) != 0, "a volume loop inside the macro header is rejected");
}

static void test_psg_note(void) {
    printf("PSG note\n");
    SmsMachine* m = started(&g_psg);
    EXPECT(smsm_tick(m, g_out) > 0, "tick");
    const YmPsg* p = smsm_psg(m);
    /* Banjo: byte 33 - 9 = note 24, octave 2 of its A table: 0x3F9 >> 2 = 254 (440.4 Hz). */
    EXPECT(p->period[0] == 254, "A-4 period %d, expected 254", p->period[0]);
    EXPECT(p->att[0] == 0, "full volume: attenuation %d", p->att[0]);
    EXPECT(p->att[1] == 15 && p->att[2] == 15 && p->att[3] == 15, "the other channels are silent");
    /* Rows last 3 ticks; the order loops after 4 rows. */
    int o, r, t;
    smsm_position(m, &o, &r, &t);
    EXPECT(o == 0 && r == 0 && t == 1, "after 1 tick: %d/%d/%d", o, r, t);
    smsm_tick(m, g_out);
    smsm_tick(m, g_out);
    smsm_position(m, &o, &r, &t);
    EXPECT(o == 0 && r == 1 && t == 0, "after 3 ticks the next tick starts row 1: %d/%d/%d", o, r, t);
    for (int i = 0; i < 9; ++i) smsm_tick(m, g_out);
    smsm_position(m, &o, &r, &t);
    EXPECT(o == 0 && r == 0 && t == 0, "after 12 ticks the song loops: %d/%d/%d", o, r, t);
    EXPECT(smsm_last_call_cycles(m) > 0 && smsm_last_call_cycles(m) < SMSM_CYCLES_PER_TICK, "the update fits a frame: %u", smsm_last_call_cycles(m));
    smsm_destroy(m);
}

static void test_release_macro(void) {
    printf("volume macro and release\n");
    SmsMachine* m = started(&g_release);
    const int want[10] = {0, 3, 6, 3, 6, 3, 6, 15, 15, 15};
    for (int i = 0; i < 10; ++i) {
        smsm_tick(m, g_out);
        EXPECT(smsm_psg(m)->att[0] == want[i], "tick %d: attenuation %d, expected %d", i + 1, smsm_psg(m)->att[0], want[i]);
    }
    smsm_destroy(m);
}

static void test_fm(void) {
    printf("FM\n");
    SmsMachine* m = started(&g_fm);
    smsm_tick(m, g_out);
    const uint8_t* reg = smsm_opll_registers(m);
    EXPECT(reg[0x10] == 0xAC, "C-4 F-number low %02X", reg[0x10]);
    EXPECT(reg[0x20] == 0x18, "block 4, key on: %02X", reg[0x20]);
    EXPECT(reg[0x30] == 0x30, "Piano at full volume: %02X", reg[0x30]);
    smsm_destroy(m);
    m = started(&g_custom);
    smsm_tick(m, g_out);
    reg = smsm_opll_registers(m);
    const uint8_t patch[8] = {0x21, 0x12, 0x1A, 0x05, 0xF4, 0xE3, 0x23, 0x46};
    EXPECT(!memcmp(reg, patch, 8), "the custom voice's registers");
    EXPECT((reg[0x31] & 0xF0) == 0x00, "FM 2 plays instrument 0: %02X", reg[0x31]);
    /* A-3 = 45: block 3, A's F-number 0x122. */
    EXPECT(reg[0x11] == 0x22 && reg[0x21] == (0x10 | (3 << 1) | 1), "A-3 on FM 2: %02X %02X", reg[0x11], reg[0x21]);
    float peak = 0.0f;
    for (int i = 0; i < 30; ++i) {
        const int n = smsm_tick(m, g_out);
        for (int k = 0; k < 2 * n; ++k) peak = fmaxf(peak, fabsf(g_out[k]));
    }
    EXPECT(peak > 0.01f, "the YM2413 sounds: peak %f", peak);
    smsm_destroy(m);
}

static void test_drums(void) {
    printf("rhythm mode\n");
    SmsMachine* m = started(&g_drums);
    smsm_tick(m, g_out);
    const uint8_t* reg = smsm_opll_registers(m);
    EXPECT(reg[0x16] == 0x20 && reg[0x26] == 0x05, "kit bass drum pitch %02X %02X", reg[0x16], reg[0x26]);
    EXPECT(reg[0x17] == 0x50 && reg[0x27] == 0x05 && reg[0x18] == 0xC0 && reg[0x28] == 0x01, "kit pitches");
    EXPECT(reg[0x0E] == 0x30, "rhythm mode with the bass drum keyed: %02X", reg[0x0E]);
    EXPECT(smsm_peek(m, BANJO_OPLL_DRUM_FIXED_PITCH_MODE) == 1, "fixed-pitch mode");
    smsm_destroy(m);
}

static void test_gg(void) {
    printf("Game Gear\n");
    SmsMachine* m = started(&g_gg);
    EXPECT(smsm_pan(m) == 0xFF, "init centres every channel: %02X", smsm_pan(m));
    smsm_tick(m, g_out);
    EXPECT(smsm_pan(m) == 0xFD, "Square 2 on the left only: %02X", smsm_pan(m));
    smsm_destroy(m);
    /* The same command on the Master System layout changes nothing (Banjo writes $06 only on a Game Gear). */
    Builder sms = g_gg;
    sms.im.bytes[SMS_META_LAYOUT] = SMS_LAYOUT_PSG;
    EXPECT(load(&sms.im) == 0, "loads as SMS");
    m = started(&sms);
    smsm_tick(m, g_out);
    EXPECT(smsm_pan(m) == 0xFF, "no stereo on the Master System: %02X", smsm_pan(m));
    smsm_destroy(m);
}

/* Driver RAM (song state and channels) and the PSG registers of two machines. */
static int same_state(const SmsMachine* a, const SmsMachine* b) {
    for (int k = 0; k < BJ_HEADER_SIZE; ++k)
        if (smsm_peek(a, (uint16_t)(BANJO_SONG_STATE + k)) != smsm_peek(b, (uint16_t)(BANJO_SONG_STATE + k))) return 0;
    for (int k = 0; k < 4 * BJC_SIZE; ++k)
        if (smsm_peek(a, (uint16_t)(BANJO_SONG_CHANNELS + k)) != smsm_peek(b, (uint16_t)(BANJO_SONG_CHANNELS + k))) return 0;
    const YmPsg *p = smsm_psg(a), *q = smsm_psg(b);
    return !memcmp(p->period, q->period, sizeof p->period) && !memcmp(p->att, q->att, sizeof p->att) && p->noise == q->noise;
}

static void test_seek(void) {
    printf("seek\n");
    SmsMachine* a = started(&g_two);
    int o, r, t, guard = 0;
    for (;;) {
        smsm_position(a, &o, &r, &t);
        if ((o == 1 && r == 5 && t == 0) || ++guard > 200) break;
        smsm_tick(a, g_out);
    }
    EXPECT(guard <= 200, "straight play reaches order 1 row 5");
    SmsMachine* b = smsm_create(RATE);
    int mode = -1;
    EXPECT(smsm_start(b, &g_two.im, 1, 5, 12000, &mode) == SMSM_OK && mode == SMSM_SEEK_FROM_START, "seek from the start");
    smsm_position(b, &o, &r, &t);
    EXPECT(o == 1 && r == 5 && t == 0, "seek lands on 1/5: %d/%d/%d", o, r, t);
    EXPECT(same_state(a, b), "the seek's driver state equals straight play's");
    for (int i = 0; i < 40; ++i) {
        smsm_tick(a, g_out);
        smsm_tick(b, g_out);
    }
    EXPECT(same_state(a, b), "and stays equal");
    smsm_destroy(b);
    b = smsm_create(RATE);
    EXPECT(smsm_start(b, &g_two.im, 1, 5, 0, &mode) == SMSM_OK && mode == SMSM_SEEK_FROM_ORDER, "budget 0: from the order start");
    smsm_position(b, &o, &r, &t);
    EXPECT(o == 1 && r == 5 && t == 0, "the order fallback lands on 1/5: %d/%d/%d", o, r, t);
    /* From the order's start straight play's speed there (row 2 set 4) carries on: row 5 lasts 4 ticks. */
    for (int i = 0; i < 4; ++i) smsm_tick(b, g_out);
    smsm_position(b, &o, &r, &t);
    EXPECT(o == 1 && r == 6 && t == 0, "rows of 4 ticks after the speed change: %d/%d/%d", o, r, t);
    smsm_destroy(a);
    smsm_destroy(b);
}

static void test_live_edit(void) {
    printf("live edit\n");
    SmsMachine* m = started(&g_two);
    int o, r, t, guard = 0;
    for (;;) {
        smsm_position(m, &o, &r, &t);
        if ((o == 1 && r == 2 && t == 0) || ++guard > 200) break;
        smsm_tick(m, g_out);
    }
    smsm_tick(m, g_out); /* row 2 started */
    smsm_apply_image(m, &g_two_edit.im);
    smsm_position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 2 && t == 1, "the edit keeps the position: %d/%d/%d", o, r, t);
    for (guard = 0; guard < 40; ++guard) {
        smsm_position(m, &o, &r, &t);
        if (o == 1 && r == 5) break;
        smsm_tick(m, g_out);
    }
    /* Row 4 played the edited note A-2 + 12 (byte 45 - 9 = 36: octave 3 of A = 0x3F9 >> 3 = 127). */
    EXPECT(smsm_psg(m)->period[0] == 127, "the edited note plays: period %d", smsm_psg(m)->period[0]);
    smsm_destroy(m);
}

static void test_audition(void) {
    printf("audition\n");
    SmsMachine* m = smsm_create(RATE);
    EXPECT(smsm_audition(m, &g_psg.im, 0, 33, 1, 0) == SMSM_OK, "audition starts");
    smsm_tick(m, g_out);
    EXPECT(smsm_psg(m)->period[0] == 254 && smsm_psg(m)->att[0] == 0, "A-4 sounds");
    EXPECT(smsm_release(m) == 0, "no release part: the note just stops");
    EXPECT(smsm_audition(m, &g_psg.im, 0, 33, 1, 3) == SMSM_ERR_IMAGE, "fine pitch is refused");
    EXPECT(smsm_audition(m, &g_psg.im, 0, 33, 2, 0) == SMSM_ERR_IMAGE, "a slot past the table is refused");
    EXPECT(smsm_audition(m, &g_release.im, 0, 33, 1, 0) == SMSM_OK, "release audition starts");
    for (int i = 0; i < 5; ++i) smsm_tick(m, g_out);
    EXPECT(smsm_release(m) == 1, "a release part plays");
    int ticks = 0;
    while (smsm_tick(m, g_out) > 0 && ticks < 700) ++ticks;
    EXPECT(ticks < 60, "the release ends silent within a second: %d ticks", ticks);
    EXPECT(smsm_audition(m, &g_fm.im, 4, 48, 1, 0) == SMSM_OK, "FM audition");
    smsm_tick(m, g_out);
    EXPECT(smsm_opll_registers(m)[0x20] == 0x18, "FM key on");
    EXPECT(smsm_release(m) == 1, "FM keys off with a release tail");
    smsm_tick(m, g_out);
    EXPECT((smsm_opll_registers(m)[0x20] & 0x10) == 0, "key off written");
    smsm_destroy(m);
}

static void test_mute(void) {
    printf("mute\n");
    SmsMachine* m = started(&g_psg);
    EXPECT(smsm_mute(m, 0, 1) == SMSM_OK && (smsm_psg(m)->mute & 1), "Square 1 muted");
    float peak = 0.0f;
    for (int i = 0; i < 10; ++i) {
        const int n = smsm_tick(m, g_out);
        for (int k = 0; k < 2 * n; ++k) peak = fmaxf(peak, fabsf(g_out[k]));
    }
    EXPECT(peak < 0.001f, "nothing else plays: peak %f", peak);
    EXPECT(smsm_mute(m, 0, 0) == SMSM_OK && !(smsm_psg(m)->mute & 1), "unmuted");
    EXPECT(smsm_mute(m, 4, 1) == SMSM_ERR_IMAGE, "no channel 5 on the PSG layout");
    smsm_destroy(m);
}

static void test_vgm(const char* dir) {
    printf("VGM\n");
    size_t size = 0;
    const char* problem = NULL;
    uint8_t* vgm = sms_export_vgm(g_psg.im.bytes, SMS_IMAGE_SIZE, 600, "Test", "Thor", "2026/11/08", &size, &problem);
    EXPECT(vgm != NULL, "export: %s", problem ? problem : "");
    if (vgm) {
        EXPECT(!memcmp(vgm, "Vgm ", 4), "magic");
        EXPECT(vgm[8] == 0x51 && vgm[9] == 0x01, "version 1.51");
        EXPECT(sms_rd16(vgm + 0x0C) == (SMSM_CLOCK & 0xFFFF), "SN76489 clock");
        const uint32_t loop = (uint32_t)(vgm[0x1C] | vgm[0x1D] << 8 | vgm[0x1E] << 16 | (uint32_t)vgm[0x1F] << 24);
        const uint32_t loop_samples = (uint32_t)(vgm[0x20] | vgm[0x21] << 8 | vgm[0x22] << 16 | (uint32_t)vgm[0x23] << 24);
        EXPECT(loop != 0, "the song loops");
        /* 12 ticks of 59736 cycles = 8831 samples at 44.1 kHz. */
        EXPECT(loop_samples >= 8830 && loop_samples <= 8832, "loop length %u samples", loop_samples);
        EXPECT(vgm[0x80] == 0x50, "data starts with a PSG write (the driver's init)");
        sms_export_free(vgm);
    }
    vgm = sms_export_vgm(g_fm.im.bytes, SMS_IMAGE_SIZE, 600, "FM", "", "", &size, &problem);
    EXPECT(vgm != NULL && (vgm[0x10] | vgm[0x11] << 8) == (SMSM_CLOCK & 0xFFFF), "FM layouts declare the YM2413");
    if (vgm) {
        char path[1024];
        snprintf(path, sizeof path, "%s/sms_fm.vgm", dir);
        FILE* f = fopen(path, "wb");
        if (f) {
            fwrite(vgm, 1, size, f);
            fclose(f);
        }
        sms_export_free(vgm);
    }
    uint8_t bad[SMS_IMAGE_SIZE];
    memcpy(bad, g_psg.im.bytes, sizeof bad);
    bad[0] = 'X';
    EXPECT(sms_export_vgm(bad, sizeof bad, 600, "", "", "", &size, &problem) == NULL && problem, "a rejected image says why");
}

static void write_wav(const char* dir, const char* name, Builder* b, int seconds) {
    SmsMachine* m = started(b);
    char path[1024];
    snprintf(path, sizeof path, "%s/%s", dir, name);
    FILE* f = fopen(path, "wb");
    if (!f) {
        smsm_destroy(m);
        return;
    }
    const uint32_t frames_max = (uint32_t)(RATE * seconds);
    uint32_t frames = 0;
    fseek(f, 44, SEEK_SET);
    while (frames < frames_max) {
        const int n = smsm_tick(m, g_out);
        if (n <= 0) break;
        for (int i = 0; i < 2 * n; ++i) {
            const float v = g_out[i] < -1 ? -1 : g_out[i] > 1 ? 1 : g_out[i];
            const int16_t s = (int16_t)lrintf(v * 32767);
            fwrite(&s, 2, 1, f);
        }
        frames += (uint32_t)n;
    }
    const uint32_t data = frames * 4;
    const uint32_t hdr[] = {0x46464952u, 36 + data, 0x45564157u, 0x20746d66u, 16, 0x00020001u, RATE, RATE * 4,
                            0x00100004u, 0x61746164u, data};
    fseek(f, 0, SEEK_SET);
    fwrite(hdr, sizeof hdr, 1, f);
    fclose(f);
    smsm_destroy(m);
}

static void test_ops(void) {
    printf("ops\n");
    extern const ChipEngineOps sms_chip_ops;
    EXPECT(chip_engine_ops("sms-banjo") == &sms_chip_ops, "registered as sms-banjo");
    EXPECT(sms_chip_ops.image_size == SMS_IMAGE_SIZE, "image size %zu", sms_chip_ops.image_size);
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    build_psg();
    build_release();
    build_fm(&g_fm, 0);
    build_fm(&g_custom, 1);
    build_drums();
    build_gg();
    build_two(&g_two, 0);
    build_two(&g_two_edit, 1);
    test_images();
    test_psg_note();
    test_release_macro();
    test_fm();
    test_drums();
    test_gg();
    test_seek();
    test_live_edit();
    test_audition();
    test_mute();
    test_vgm(dir);
    test_ops();
    write_wav(dir, "sms_psg.wav", &g_two, 5);
    write_wav(dir, "sms_gg.wav", &g_gg, 5);
    write_wav(dir, "sms_fm.wav", &g_custom, 5);
    write_wav(dir, "sms_drums.wav", &g_drums, 5);
    printf("Master System: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
