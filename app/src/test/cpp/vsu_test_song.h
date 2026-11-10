/* The Virtual Boy VSU test song, built in C (header-only, for app/src/test/cpp/vsu_machine_test.c and
 * tools/audio/vsu_bench.c): 4 orders (16/32/16/24 rows, speed 6, loop order 1) in the image layout of vsu_image.h,
 * written for the host test and the Python reference (tools/audio/vsu/vsu_ref.py reads the image the test writes as
 * vsu_song.bin).
 *
 * It covers every effect on its lanes (0 1 2 3 4 8 B C D F G L M N P S W; C, M, N, W both as locks on a note and
 * written at once on rows without a key-on: no note, an OFF row, a glide note), both effect columns, OFF, Gxx delays
 * (also with Sxx in the same row), Sxx cuts, Lxx lengths (L00
 * overriding an auto instrument, L01-L20), hardware envelopes (decay, grow, decay-repeat, grow-repeat), auto length,
 * a software pitch sweep, Wave 5's hardware sweep (running into its overflow stop), modulation with and without
 * repeat, the sweep-overflow hardware bug with modulation disabled (M08 on C-6), all noise taps' extremes (N00..N07,
 * C-0 and B-7), stereo levels (8xy, 800 silencing), two byte-identical instrument slots (1 and 10), empty runs, shared
 * patterns (order 0 and 2 lane 3 are one pattern; empty lanes share theirs), Dxx to order 3 row 2 (rows 0-1 of order 3
 * only reachable by the order fallback), F05 there, and B01 on the last row back to the loop order (the loop speed rule
 * restores speed 6).
 *
 * Variants (VbSongOptions): re-laid-out data (patterns in reverse order behind a 37-byte gap), a note edit (order 1 row
 * 16 lane 0: E-5 -> A-5), a tempo edit (F09 on order 1 row 4 lane 3), the song cut to its first two orders, an
 * instrument edit (slot 1's level 15 -> 9; slot 10 unchanged), a wave edit (wave 1 inverted), a modulation edit, and a
 * lane dropped (its patterns empty: for the mute test). */
#ifndef THORTRACKER_VSU_TEST_SONG_H_
#define THORTRACKER_VSU_TEST_SONG_H_

#include <stdint.h>
#include <string.h>

#include "vsu_image.h"

typedef struct VbSongOptions {
    int relayout;
    int note_edit;
    int tempo_edit;
    int cut;        /* two orders only */
    int inst_edit;  /* slot 1 level 9 */
    int wave_edit;  /* wave 1 inverted */
    int mod_edit;   /* modulation table negated */
    int drop_lane;  /* lane + 1 whose patterns are empty, 0 none */
} VbSongOptions;

enum { VBT_C, VBT_CS, VBT_D, VBT_DS, VBT_E, VBT_F, VBT_FS, VBT_G, VBT_GS, VBT_A, VBT_AS, VBT_B };
#define VBT_N(n, o) ((o) * 12 + VBT_##n)
#define VBT_OFF VB_NOTE_OFF
#define VBT_NONE (-1)

/* Instrument slots of the test song. */
enum {
    VBT_LEAD = 1,   /* wave 1, level 15, decay every 2 x 15.36 ms */
    VBT_PLUCK,      /* wave 2, level 12, fast decay, auto length 11 x 3.84 ms */
    VBT_PAD,        /* wave 3, level 4, grow-repeat */
    VBT_BASS,       /* wave 4, level 14, no envelope, software sweep 12 -> 0 semitones */
    VBT_SWEEP5,     /* wave 5, hardware sweep up (S5SWP 0x2C) */
    VBT_MOD5,       /* wave 5, modulation with repeat, slow clock (S5SWP 0x90) */
    VBT_MODONCE,    /* wave 5, modulation once (S5SWP 0x30) */
    VBT_HAT,        /* noise tap 0, level 12, fast decay, auto length 5 x 3.84 ms */
    VBT_DRUM,       /* noise tap 5, level 15, decay-repeat */
    VBT_LEAD10,     /* byte-identical to VBT_LEAD */
    VBT_TONE,       /* wave 1, level 15, no envelope: a sustained tone (pitch and mute tests) */
    VBT_NOISE,      /* noise tap 0, level 15, no envelope: sustained noise */
    VBT_INSTRUMENTS = VBT_NOISE
};

typedef struct VbtEvent {
    int order, lane, row, note, inst;
    const char* fx; /* effect columns: "047 P90" = 0-47, P-90 */
} VbtEvent;

static const VbtEvent VBT_EVENTS[] = {
    /* order 0 */
    {0, 0, 0, VBT_N(C, 4), VBT_LEAD, ""},
    {0, 0, 2, VBT_NONE, 0, "047"},
    {0, 0, 4, VBT_N(E, 4), VBT_LEAD, "P90"},
    {0, 0, 6, VBT_NONE, 0, "000"},
    {0, 0, 8, VBT_N(G, 4), VBT_LEAD, "436"},
    {0, 0, 10, VBT_NONE, 0, "400"},
    {0, 0, 12, VBT_OFF, 0, ""},
    {0, 0, 13, VBT_N(A, 4), VBT_PLUCK, "L05"},
    {0, 0, 14, VBT_N(C, 5), 0, "W03 C08"},
    {0, 0, 15, VBT_NONE, 0, "W02"},
    {0, 1, 0, VBT_N(C, 3), VBT_BASS, ""},
    {0, 1, 4, VBT_NONE, 0, "220"},
    {0, 1, 6, VBT_NONE, 0, "200"},
    {0, 1, 8, VBT_N(E, 3), VBT_BASS, "810"},
    {0, 1, 10, VBT_NONE, 0, "8F4"},
    {0, 1, 12, VBT_NONE, 0, "S03"},
    {0, 1, 14, VBT_N(G, 3), VBT_LEAD10, ""},
    {0, 2, 0, VBT_N(C, 5), VBT_PAD, "G02"},
    {0, 2, 8, VBT_NONE, 0, "C0A"},
    {0, 2, 10, VBT_N(E, 5), 0, ""},
    {0, 3, 0, VBT_N(C, 4), VBT_PLUCK, ""},
    {0, 3, 4, VBT_N(C, 4), VBT_PLUCK, "L00"},
    {0, 3, 8, VBT_OFF, 0, ""},
    {0, 3, 12, VBT_N(D, 4), VBT_PLUCK, "L20"},
    {0, 4, 0, VBT_N(C, 4), VBT_SWEEP5, ""},
    {0, 4, 6, VBT_OFF, 0, ""},
    {0, 4, 8, VBT_N(A, 3), VBT_MOD5, ""},
    {0, 4, 12, VBT_NONE, 0, "M20"},
    {0, 4, 14, VBT_N(C, 4), VBT_MOD5, "M3B"},
    {0, 4, 15, VBT_NONE, 0, "W05"},
    {0, 5, 0, VBT_N(C, 6), VBT_HAT, ""},
    {0, 5, 2, VBT_N(C, 6), VBT_HAT, "N03"},
    {0, 5, 4, VBT_N(A, 7), VBT_DRUM, ""},
    {0, 5, 6, VBT_NONE, 0, "N06"},
    {0, 5, 8, VBT_N(C, 2), VBT_DRUM, "C0F"},
    {0, 5, 12, VBT_OFF, 0, ""},
    {0, 5, 14, VBT_N(G, 4), VBT_HAT, ""},
    /* order 1 (the loop order) */
    {1, 0, 0, VBT_N(C, 5), VBT_LEAD, ""},
    {1, 0, 4, VBT_N(E, 5), 0, "303"},
    {1, 0, 8, VBT_N(G, 5), 0, "C0C"},
    {1, 0, 12, VBT_N(C, 6), 0, "300"},
    {1, 0, 16, VBT_N(E, 5), VBT_LEAD, ""},
    {1, 0, 20, VBT_NONE, 0, "120"},
    {1, 0, 22, VBT_NONE, 0, "100"},
    {1, 0, 24, VBT_N(G, 4), 0, "G03"},
    {1, 0, 26, VBT_NONE, 0, "C06"},
    {1, 0, 28, VBT_NONE, 0, "037 P70"},
    {1, 0, 30, VBT_OFF, 0, ""},
    {1, 1, 0, VBT_N(C, 3), VBT_BASS, ""},
    {1, 1, 8, VBT_N(C, 3), VBT_BASS, "S02"},
    {1, 1, 16, VBT_N(G, 2), VBT_BASS, ""},
    {1, 1, 24, VBT_OFF, 0, "8FF"},
    {1, 2, 0, VBT_N(G, 4), VBT_PAD, ""},
    {1, 2, 16, VBT_NONE, 0, "800"},
    {1, 2, 20, VBT_NONE, 0, "8AA"},
    {1, 2, 24, VBT_OFF, 0, "C05 W01"},
    {1, 3, 0, VBT_N(C, 4), VBT_LEAD10, ""},
    {1, 3, 16, VBT_N(C, 4), VBT_LEAD, ""},
    {1, 4, 0, VBT_N(C, 4), VBT_MODONCE, ""},
    {1, 4, 16, VBT_N(D, 4), VBT_MOD5, ""},
    {1, 4, 24, VBT_OFF, 0, ""},
    {1, 5, 0, VBT_N(C, 5), VBT_HAT, ""},
    {1, 5, 4, VBT_N(C, 5), VBT_HAT, ""},
    {1, 5, 8, VBT_N(F, 6), VBT_DRUM, ""},
    {1, 5, 12, VBT_N(C, 5), VBT_HAT, ""},
    {1, 5, 16, VBT_N(C, 5), VBT_HAT, "N01"},
    {1, 5, 20, VBT_N(C, 5), VBT_HAT, ""},
    {1, 5, 24, VBT_N(F, 6), VBT_DRUM, "L03"},
    {1, 5, 28, VBT_N(C, 5), VBT_HAT, "S01"},
    /* order 2 */
    {2, 0, 0, VBT_N(A, 4), VBT_LEAD, ""},
    {2, 0, 8, VBT_OFF, 0, ""},
    {2, 1, 4, VBT_N(C, 3), VBT_BASS, "G03 S05"},
    {2, 2, 15, VBT_NONE, 0, "D02"},
    {2, 3, 0, VBT_N(C, 4), VBT_PLUCK, ""},
    {2, 3, 4, VBT_N(C, 4), VBT_PLUCK, "L00"},
    {2, 3, 8, VBT_OFF, 0, ""},
    {2, 3, 12, VBT_N(D, 4), VBT_PLUCK, "L20"},
    {2, 4, 0, VBT_N(G, 4), VBT_SWEEP5, "M00"},
    {2, 4, 8, VBT_N(C, 6), VBT_SWEEP5, "M08"},
    {2, 5, 0, VBT_N(C, 0), VBT_DRUM, "N07"},
    {2, 5, 8, VBT_N(B, 7), VBT_HAT, "N00"},
    /* order 3 (entered at row 2 by D02) */
    {3, 0, 0, VBT_N(C, 5), VBT_LEAD, ""},
    {3, 0, 2, VBT_N(A, 5), VBT_PLUCK, "F05"},
    {3, 0, 4, VBT_N(C, 6), 0, "308"},
    {3, 0, 8, VBT_NONE, 0, "300"},
    {3, 0, 10, VBT_OFF, 0, ""},
    {3, 0, 12, VBT_N(E, 5), VBT_LEAD, ""},
    {3, 0, 16, VBT_NONE, 0, "110"},
    {3, 0, 18, VBT_NONE, 0, "100"},
    {3, 0, 20, VBT_OFF, 0, ""},
    {3, 1, 2, VBT_N(C, 3), VBT_BASS, ""},
    {3, 1, 10, VBT_N(C, 3), VBT_BASS, "W04"},
    {3, 1, 18, VBT_N(D, 3), VBT_BASS, ""},
    {3, 2, 2, VBT_N(C, 5), VBT_PAD, ""},
    {3, 2, 6, VBT_OFF, 0, ""},
    {3, 2, 12, VBT_N(C, 4), VBT_PAD, "C03 L08"},
    {3, 3, 4, VBT_N(C, 4), VBT_LEAD, ""},
    {3, 3, 12, VBT_N(C, 4), VBT_LEAD10, ""},
    {3, 3, 16, VBT_OFF, 0, ""},
    {3, 4, 4, VBT_N(E, 4), VBT_MOD5, ""},
    {3, 4, 12, VBT_NONE, 0, "M90"},
    {3, 4, 20, VBT_OFF, 0, ""},
    {3, 5, 2, VBT_N(C, 5), VBT_HAT, "N02"},
    {3, 5, 10, VBT_N(C, 5), VBT_HAT, "N04"},
    {3, 5, 23, VBT_NONE, 0, "B01"},
};

static const int VBT_ROWS[4] = {16, 32, 16, 24};

/* Waves: 1 sine-like, 2 square, 3 triangle, 4 saw, 5 a 25 % pulse. */
static const uint8_t VBT_SINE32[32] = {32, 38, 44, 49, 54, 58, 61, 62, 63, 62, 61, 58, 54, 49, 44, 38,
                                       32, 25, 19, 14, 9,  5,  2,  1,  0,  1,  2,  5,  9,  14, 19, 25};
static int vbt_wave(int w, int i, int edited) {
    switch (w) {
    case 0: return edited ? 63 - VBT_SINE32[i] : VBT_SINE32[i];
    case 1: return i < 16 ? 63 : 0;
    case 2: return i < 16 ? i * 4 : (31 - i) * 4;
    case 3: return i * 2;
    default: return i < 8 ? 63 : 8;
    }
}

/* The modulation table: a triangle of +-9 twice (negated in the modulation edit). */
static const int8_t VBT_MOD16[16] = {0, 3, 6, 9, 9, 6, 3, 0, 0, -3, -6, -9, -9, -6, -3, 0};
static int vbt_mod(int i, int edited) { return edited ? -VBT_MOD16[i % 16] : VBT_MOD16[i % 16]; }

typedef struct VbtOut {
    uint8_t* b;
    int at; /* data region offset */
    int npat;
    int pat_off[64], pat_len[64];
} VbtOut;

static void vbt_put(VbtOut* o, int v) { o->b[VB_OFF_DATA + o->at++] = (uint8_t)v; }
static void vbt_u16(uint8_t* p, int v) { p[0] = (uint8_t)v, p[1] = (uint8_t)(v >> 8); }
static int vbt_code(char c) { return c >= '0' && c <= '9' ? c - '0' : c - 'A' + 10; }

static void vbt_flush(VbtOut* o, int* run) {
    while (*run) {
        const int k = *run > 128 ? 128 : *run;
        vbt_put(o, 0x80 | (k - 1));
        *run -= k;
    }
}

/* The pattern of (order, lane) at the cursor; byte-identical patterns are shared. Returns its offset. */
static int vbt_pattern(VbtOut* o, const VbSongOptions* opt, int order, int lane) {
    const int off = o->at;
    int run = 0;
    for (int r = 0; r < VBT_ROWS[order]; ++r) {
        VbtEvent e = {order, lane, r, VBT_NONE, 0, ""};
        int found = 0;
        if (opt->drop_lane != lane + 1) {
            for (size_t i = 0; i < sizeof VBT_EVENTS / sizeof VBT_EVENTS[0]; ++i) {
                const VbtEvent* x = &VBT_EVENTS[i];
                if (x->order == order && x->lane == lane && x->row == r) e = *x, found = 1;
            }
        }
        if (opt->note_edit && order == 1 && lane == 0 && r == 16) e.note = VBT_N(A, 5);
        if (opt->tempo_edit && order == 1 && lane == 3 && r == 4) e.fx = "F09", found = 1;
        if (!found) {
            ++run;
            continue;
        }
        vbt_flush(o, &run);
        const int fx = (int)(strlen(e.fx) + 1) / 4;
        vbt_put(o, (e.note != VBT_NONE ? 1 : 0) | (e.inst ? 2 : 0) | fx << 2);
        if (e.note != VBT_NONE) vbt_put(o, e.note);
        if (e.inst) vbt_put(o, e.inst);
        for (int k = 0; k < fx; ++k) {
            const char* s = e.fx + 4 * k;
            vbt_put(o, vbt_code(s[0]));
            vbt_put(o, vbt_code(s[1]) << 4 | vbt_code(s[2]));
        }
    }
    vbt_flush(o, &run);
    const int len = o->at - off;
    for (int i = 0; i < o->npat; ++i) {
        if (o->pat_len[i] == len && !memcmp(o->b + VB_OFF_DATA + o->pat_off[i], o->b + VB_OFF_DATA + off, (size_t)len)) {
            memset(o->b + VB_OFF_DATA + off, 0, (size_t)len);
            o->at = off;
            return o->pat_off[i];
        }
    }
    o->pat_off[o->npat] = off;
    o->pat_len[o->npat++] = len;
    return off;
}

static void vbt_instrument(uint8_t* b, int slot, int flags, int level, int interval, int length, int wave, int tap, int fm, int swp,
                           int s0, int s1, int speed) {
    uint8_t* p = b + VB_OFF_INSTRUMENTS + VB_INSTRUMENT_SIZE * (slot - 1);
    p[VB_I_FLAGS] = (uint8_t)(VB_FLAG_DEFINED | flags);
    p[VB_I_LEVEL] = (uint8_t)level;
    p[VB_I_INTERVAL] = (uint8_t)interval;
    p[VB_I_LENGTH] = (uint8_t)length;
    p[VB_I_WAVE] = (uint8_t)wave;
    p[VB_I_TAP] = (uint8_t)tap;
    p[VB_I_FREQMOD] = (uint8_t)fm;
    p[VB_I_SWP] = (uint8_t)swp;
    p[VB_I_SWEEP_START] = (uint8_t)(int8_t)s0;
    p[VB_I_SWEEP_END] = (uint8_t)(int8_t)s1;
    p[VB_I_SWEEP_SPEED] = (uint8_t)speed;
}

static void vbt_instruments(uint8_t* b, const VbSongOptions* opt) {
    const int E = VB_FLAG_ENVELOPE, R = VB_FLAG_REPEAT, G = VB_FLAG_GROW, A = VB_FLAG_AUTO;
    vbt_instrument(b, VBT_LEAD, E, opt->inst_edit ? 9 : 15, 1, 0, 0, 0, 0, 0, 0, 0, 0);
    vbt_instrument(b, VBT_PLUCK, E | A, 12, 0, 10, 1, 0, 0, 0, 0, 0, 0);
    vbt_instrument(b, VBT_PAD, E | R | G, 4, 2, 0, 2, 0, 0, 0, 0, 0, 0);
    vbt_instrument(b, VBT_BASS, 0, 14, 0, 0, 3, 0, 0, 0, 12, 0, 8);
    vbt_instrument(b, VBT_SWEEP5, 0, 15, 0, 0, 4, 0, VB_FM_ON, 0x2C, 0, 0, 0);
    vbt_instrument(b, VBT_MOD5, E | R, 13, 3, 0, 4, 0, VB_FM_ON | VB_FM_MODULATION | VB_FM_REPEAT, 0x90, 0, 0, 0);
    vbt_instrument(b, VBT_MODONCE, 0, 12, 0, 0, 0, 0, VB_FM_ON | VB_FM_MODULATION, 0x30, 0, 0, 0);
    vbt_instrument(b, VBT_HAT, E | A, 12, 0, 4, 0, 0, 0, 0, 0, 0, 0);
    vbt_instrument(b, VBT_DRUM, E | R, 15, 1, 0, 0, 5, 0, 0, 0, 0, 0);
    vbt_instrument(b, VBT_LEAD10, E, 15, 1, 0, 0, 0, 0, 0, 0, 0, 0);
    vbt_instrument(b, VBT_TONE, 0, 15, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    vbt_instrument(b, VBT_NOISE, 0, 15, 0, 0, 0, 0, 0, 0, 0, 0, 0);
}

/* Writes the test song into image (VB_IMAGE_SIZE bytes). */
static void vb_build_test_song(uint8_t* b, const VbSongOptions* opt) {
    memset(b, 0, VB_IMAGE_SIZE);
    const int orders = opt->cut ? 2 : 4;
    memcpy(b, "TTVB", 4);
    b[VB_H_LAYOUT] = VB_LAYOUT;
    b[VB_H_SPEED] = 6;
    vbt_u16(b + VB_H_ORDERS, orders);
    vbt_u16(b + VB_H_LOOP, 1);
    b[VB_H_INSTRUMENTS] = VBT_INSTRUMENTS;
    vbt_instruments(b, opt);
    for (int w = 0; w < 5; ++w)
        for (int i = 0; i < 32; ++i) b[VB_OFF_WAVES + 32 * w + i] = (uint8_t)vbt_wave(w, i, opt->wave_edit);
    for (int i = 0; i < 32; ++i) b[VB_OFF_MOD + i] = (uint8_t)(int8_t)vbt_mod(i, opt->mod_edit);
    VbtOut o;
    memset(&o, 0, sizeof o);
    o.b = b;
    if (opt->relayout) o.at = 37; /* a gap: the same song at other offsets */
    for (int k = 0; k < VB_LANES * orders; ++k) {
        const int i = opt->relayout ? VB_LANES * orders - 1 - k : k, order = i / VB_LANES, lane = i % VB_LANES;
        vbt_u16(b + VB_OFF_ORDERS + 2 * (VB_LANES * order + lane), vbt_pattern(&o, opt, order, lane));
    }
    for (int order = 0; order < orders; ++order) b[VB_OFF_ROWS + order] = (uint8_t)VBT_ROWS[order];
    vbt_u16(b + VB_H_DATA_SIZE, o.at);
}

#endif /* THORTRACKER_VSU_TEST_SONG_H_ */
