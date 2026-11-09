/* The POKEY test song, built in C (header-only, for app/src/test/cpp/pokey_machine_test.c and
 * tools/audio/pokey_bench.c): an NTSC song of 4 orders (16/32/16/24 rows, speed 6, loop order 1) in the image layout of
 * pokey_image.h, written for the host test and the Python reference (tools/audio/pokey/pokey_ref.py reads the image the
 * test writes as pokey_song.bin).
 *
 * It covers every sound kind (8-bit pure and fast, distortion C, noise with a relative pitch envelope, 16-bit fast and
 * base-clocked, 16-bit with an audible partner, high-pass detune, the difference "saw", 16-bit high-pass, volume only
 * with an arpeggio envelope, samples once on channels 1, 2 and 4 and looping on channel 1), every distortion through
 * Vxx, every effect (0 1 2 3 4 A B C D F G H K N P R S T V W), notes cutting the sounds whose channels they take, OFF
 * with and without a volume release point, Gxx delays, Sxx, Dxx to order 3 row 2 (rows 0-1 of order 3 only reachable
 * by the order fallback), F05 there, and B01 on the last row back to the loop order (which starts with N01 in effect:
 * the player's loop rule restores the shared AUDCTL bits, the two-tone state and the speed).
 *
 * Variants (PkSongOptions): PAL machine, multispeed, re-laid-out data (patterns in reverse order behind a 37-byte gap,
 * envelopes after them: the same song at other offsets), a note edit (order 1 row 16 lane 2: E-5 -> A-5), a tempo edit
 * (F09 on order 1 row 4 lane 4) and the song cut to its first two orders. */
#ifndef THORTRACKER_POKEY_TEST_SONG_H_
#define THORTRACKER_POKEY_TEST_SONG_H_

#include <stdint.h>
#include <string.h>

#include "pokey_image.h"

typedef struct PkSongOptions {
    int machine;    /* PK_MACHINE_* */
    int multispeed; /* 1..4 */
    int relayout;
    int note_edit;
    int tempo_edit;
    int cut;        /* two orders only */
} PkSongOptions;

enum { PKT_C, PKT_CS, PKT_D, PKT_DS, PKT_E, PKT_F, PKT_FS, PKT_G, PKT_GS, PKT_A, PKT_AS, PKT_B };
#define PKT_N(n, o) ((o) * 12 + PKT_##n)
#define PKT_OFF PK_NOTE_OFF
#define PKT_NONE (-1)

/* Instrument slots of the test song. */
enum {
    PKT_LEAD = 1, PKT_FAST, PKT_BUZZ, PKT_NOISE, PKT_BASS16F, PKT_BASS16, PKT_HPDET, PKT_SAW, PKT_HP16, PKT_KICK, PKT_LOOPS,
    PKT_VOLONLY, PKT_PARTNER, PKT_INSTRUMENTS = PKT_PARTNER
};

typedef struct PktEvent {
    int order, lane, row, note, inst;
    const char* fx; /* effect columns: "047 P90" = 0-47, P-90 */
} PktEvent;

static const PktEvent PKT_EVENTS[] = {
    /* order 0 */
    {0, 0, 0, PKT_N(C, 4), PKT_LEAD, ""},
    {0, 0, 2, PKT_NONE, 0, "047"},
    {0, 0, 4, PKT_N(E, 4), PKT_LEAD, "P90"},
    {0, 0, 6, PKT_NONE, 0, "000"},
    {0, 0, 8, PKT_N(G, 4), PKT_LEAD, "436"},
    {0, 0, 10, PKT_NONE, 0, "400"},
    {0, 0, 12, PKT_OFF, 0, ""},
    {0, 0, 13, PKT_N(C, 5), PKT_HPDET, ""},
    {0, 0, 14, PKT_N(A, 7), PKT_FAST, "120"},
    {0, 0, 15, PKT_NONE, 0, "100"},
    {0, 1, 0, PKT_N(C, 3), PKT_BUZZ, "V00"},
    {0, 1, 2, PKT_NONE, 0, "V01"},
    {0, 1, 4, PKT_NONE, 0, "V02"},
    {0, 1, 6, PKT_NONE, 0, "V03"},
    {0, 1, 8, PKT_NONE, 0, "V04"},
    {0, 1, 10, PKT_NONE, 0, "V05"},
    {0, 1, 11, PKT_NONE, 0, "V06"},
    {0, 1, 12, PKT_NONE, 0, "V07"},
    {0, 1, 13, PKT_NONE, 0, "V08"},
    {0, 1, 14, PKT_N(D, 3), PKT_BUZZ, ""},
    {0, 1, 15, PKT_NONE, 0, "S03"},
    {0, 2, 0, PKT_N(C, 5), PKT_NOISE, ""},
    {0, 2, 4, PKT_N(C, 5), PKT_NOISE, "N01"},
    {0, 2, 8, PKT_N(C, 6), PKT_NOISE, "G02"},
    {0, 2, 12, PKT_N(C, 5), PKT_NOISE, "C08"},
    {0, 3, 8, PKT_N(C, 4), PKT_VOLONLY, ""},
    {0, 3, 12, PKT_NONE, 0, "A02"},
    {0, 3, 15, PKT_OFF, 0, ""},
    /* order 1 (the loop order) */
    {1, 0, 0, PKT_N(C, 4), PKT_KICK, ""},
    {1, 0, 8, PKT_N(C, 4), PKT_KICK, ""},
    {1, 0, 16, PKT_N(G, 4), PKT_KICK, ""},
    {1, 0, 24, PKT_N(C, 4), PKT_KICK, "C0A"},
    {1, 0, 28, PKT_N(C, 4), PKT_KICK, "C0F"},
    {1, 1, 0, PKT_N(C, 3), PKT_SAW, ""},
    {1, 1, 4, PKT_NONE, 0, "H05"},
    {1, 1, 8, PKT_N(E, 3), 0, ""},
    {1, 1, 12, PKT_NONE, 0, "H7F"},
    {1, 1, 16, PKT_N(G, 2), PKT_SAW, "K01"},
    {1, 1, 20, PKT_NONE, 0, "W01"},
    {1, 1, 24, PKT_N(C, 3), 0, "K00"},
    {1, 1, 28, PKT_NONE, 0, "W00"},
    {1, 1, 31, PKT_NONE, 0, "S02"},
    {1, 2, 0, PKT_N(C, 5), PKT_LEAD, ""},
    {1, 2, 4, PKT_N(E, 5), 0, "303"},
    {1, 2, 8, PKT_N(G, 5), 0, ""},
    {1, 2, 12, PKT_N(C, 6), 0, "300"},
    {1, 2, 16, PKT_N(E, 5), PKT_LEAD, ""},
    {1, 2, 20, PKT_NONE, 0, "220"},
    {1, 2, 22, PKT_NONE, 0, "200"},
    {1, 2, 24, PKT_N(G, 4), 0, "G03"},
    {1, 2, 26, PKT_NONE, 0, "C06"},
    {1, 2, 27, PKT_NONE, 0, "A30"},
    {1, 2, 30, PKT_OFF, 0, "A00"},
    /* order 2 */
    {2, 0, 8, PKT_N(C, 4), PKT_LOOPS, ""},
    {2, 1, 0, PKT_N(C, 3), PKT_HP16, ""},
    {2, 1, 2, PKT_NONE, 0, "HE0"},
    {2, 1, 4, PKT_NONE, 0, "R00"},
    {2, 1, 6, PKT_NONE, 0, "T00"},
    {2, 1, 12, PKT_N(C, 2), PKT_PARTNER, ""},
    {2, 2, 10, PKT_NONE, 0, "W02"},
    {2, 2, 15, PKT_NONE, 0, "D02"},
    {2, 3, 8, PKT_N(C, 2), PKT_BASS16, ""},
    {2, 3, 12, PKT_N(G, 1), PKT_BASS16, ""},
    /* order 3 (entered at row 2 by D02) */
    {3, 0, 0, PKT_N(C, 5), PKT_FAST, ""},
    {3, 0, 2, PKT_N(A, 7), PKT_FAST, "F05"},
    {3, 0, 4, PKT_N(C, 8), 0, "308"},
    {3, 0, 8, PKT_NONE, 0, "300"},
    {3, 0, 10, PKT_OFF, 0, ""},
    {3, 0, 12, PKT_N(E, 7), PKT_FAST, "N00"},
    {3, 0, 16, PKT_NONE, 0, "110"},
    {3, 0, 18, PKT_NONE, 0, "100"},
    {3, 0, 20, PKT_OFF, 0, ""},
    {3, 1, 2, PKT_N(C, 4), PKT_KICK, ""},
    {3, 1, 4, PKT_NONE, 0, "W00"},
    {3, 1, 10, PKT_N(C, 4), PKT_KICK, ""},
    {3, 1, 18, PKT_N(D, 4), PKT_KICK, ""},
    {3, 2, 2, PKT_N(C, 5), PKT_VOLONLY, ""},
    {3, 2, 6, PKT_OFF, 0, ""},
    {3, 2, 12, PKT_N(C, 4), PKT_BUZZ, ""},
    {3, 3, 4, PKT_N(C, 4), PKT_KICK, ""},
    {3, 3, 12, PKT_N(C, 4), PKT_KICK, ""},
    {3, 3, 16, PKT_OFF, 0, ""},
    {3, 3, 23, PKT_NONE, 0, "B01"},
};

static const int PKT_ROWS[4] = {16, 32, 16, 24};

/* 4-bit samples: 0 = a 600-level decaying kick, 1 = a 64-level triangle (loops from level 4). */
#define PKT_KICK_LEVELS 600
#define PKT_LOOP_LEVELS 64
static int pkt_sample_level(int k, int i) {
    if (k == 0) {
        const int amp = 7 * (PKT_KICK_LEVELS - i) / PKT_KICK_LEVELS;
        const int half = 6 + i / 40; /* the period grows as the kick falls */
        return 8 + ((i / half) & 1 ? amp : -amp);
    }
    const int t = i % 32;
    return t < 16 ? t : 31 - t;
}

typedef struct PktOut {
    uint8_t* b;
    int at; /* data region offset */
} PktOut;

static void pkt_put(PktOut* o, int v) { o->b[PK_OFF_DATA + o->at++] = (uint8_t)v; }
static void pkt_u16(uint8_t* p, int v) { p[0] = (uint8_t)v, p[1] = (uint8_t)(v >> 8); }

static int pkt_env(PktOut* o, const int* v, int n, int loop, int release) {
    const int off = o->at;
    pkt_put(o, n), pkt_put(o, n >> 8);
    pkt_put(o, loop), pkt_put(o, loop >> 8);
    pkt_put(o, release), pkt_put(o, release >> 8);
    for (int i = 0; i < n; ++i) pkt_put(o, v[i]);
    return off;
}

static int pkt_code(char c) { return c >= '0' && c <= '9' ? c - '0' : c - 'A' + 10; }
static int pkt_hex(char c) { return c >= '0' && c <= '9' ? c - '0' : c - 'A' + 10; }

static void pkt_flush(PktOut* o, int* run) {
    while (*run) {
        const int k = *run > 128 ? 128 : *run;
        pkt_put(o, 0x80 | (k - 1));
        *run -= k;
    }
}

/* The pattern of (order, lane) at the cursor; returns its offset. */
static int pkt_pattern(PktOut* o, const PkSongOptions* opt, int order, int lane) {
    const int off = o->at;
    int run = 0;
    for (int r = 0; r < PKT_ROWS[order]; ++r) {
        PktEvent e = {order, lane, r, PKT_NONE, 0, ""};
        int found = 0;
        for (size_t i = 0; i < sizeof PKT_EVENTS / sizeof PKT_EVENTS[0]; ++i) {
            const PktEvent* x = &PKT_EVENTS[i];
            if (x->order == order && x->lane == lane && x->row == r) e = *x, found = 1;
        }
        if (opt->note_edit && order == 1 && lane == 2 && r == 16) e.note = PKT_N(A, 5);
        if (opt->tempo_edit && order == 1 && lane == 3 && r == 4) e.fx = "F09", found = 1;
        if (!found) {
            ++run;
            continue;
        }
        pkt_flush(o, &run);
        const int fx = (int)(strlen(e.fx) + 1) / 4;
        pkt_put(o, (e.note != PKT_NONE ? 1 : 0) | (e.inst ? 2 : 0) | fx << 2);
        if (e.note != PKT_NONE) pkt_put(o, e.note);
        if (e.inst) pkt_put(o, e.inst);
        for (int k = 0; k < fx; ++k) {
            const char* s = e.fx + 4 * k;
            pkt_put(o, pkt_code(s[0]));
            pkt_put(o, pkt_hex(s[1]) << 4 | pkt_hex(s[2]));
        }
    }
    pkt_flush(o, &run);
    return off;
}

/* Instrument records and their envelopes. */
static void pkt_instruments(PktOut* o, uint8_t* b) {
    static const int vlead[] = {15, 14, 12, 11, 10, 9, 6, 3, 1, 0};
    static const int v12[] = {12}, v11[] = {11}, v14[] = {14}, v10[] = {10};
    static const int vnoise[] = {15, 12, 9, 7, 5, 3, 2, 1, 0}, pnoise[] = {-6, -4, -2}, dnoise[] = {4, 0, 2, 4};
    static const int vbass[] = {13, 13, 12, 12, 11};
    static const int d6[] = {6}, d8[] = {8};
    static const int pkick[] = {0, -8, -16};
    static const int vvol[] = {15, 8, 4, 2, 0}, avol[] = {0, 12, 7};
    const int NONE = PK_ENV_NONE;
    struct {
        int slot, flags, filter, partner, sample, root, rate, loop, env[4];
    } r[PKT_INSTRUMENTS];
    int n = 0;
#define PKT_I(s, f, fo, pa, sa, ro, ra, lo, e0, e1, e2, e3) \
    r[n].slot = s, r[n].flags = f, r[n].filter = fo, r[n].partner = pa, r[n].sample = sa, r[n].root = ro, r[n].rate = ra, \
    r[n].loop = lo, r[n].env[0] = e0, r[n].env[1] = e1, r[n].env[2] = e2, r[n].env[3] = e3, ++n
    PKT_I(PKT_LEAD, 0, 0, 0, 0, 0, 0, 0xFFFF, pkt_env(o, vlead, 10, 4, 5), NONE, NONE, NONE);
    PKT_I(PKT_FAST, PK_FLAG_FAST, 0, 0, 0, 0, 0, 0xFFFF, pkt_env(o, v12, 1, NONE, NONE), NONE, NONE, NONE);
    PKT_I(PKT_BUZZ, 0, 0, 0, 0, 0, 0, 0xFFFF, pkt_env(o, v11, 1, NONE, NONE), NONE, NONE, pkt_env(o, d6, 1, NONE, NONE));
    PKT_I(PKT_NOISE, PK_FLAG_PITCH_RELATIVE, 0, 0, 0, 0, 0, 0xFFFF, pkt_env(o, vnoise, 9, NONE, NONE), NONE,
          pkt_env(o, pnoise, 3, 2, NONE), pkt_env(o, dnoise, 4, NONE, NONE));
    PKT_I(PKT_BASS16F, PK_FLAG_JOINED | PK_FLAG_FAST, 0, 0, 0, 0, 0, 0xFFFF, pkt_env(o, v14, 1, NONE, NONE), NONE, NONE, NONE);
    PKT_I(PKT_BASS16, PK_FLAG_JOINED, 0, 0, 0, 0, 0, 0xFFFF, pkt_env(o, vbass, 5, 4, NONE), NONE, NONE, NONE);
    PKT_I(PKT_HPDET, PK_FLAG_FILTERED | PK_FLAG_FAST, 1, 0, 0, 0, 0, 0xFFFF, pkt_env(o, v12, 1, NONE, NONE), NONE, NONE, NONE);
    PKT_I(PKT_SAW, PK_FLAG_FILTERED | PK_FLAG_DIFFERENCE, 3, 0, 0, 0, 0, 0xFFFF, pkt_env(o, v12, 1, NONE, NONE), NONE, NONE, NONE);
    PKT_I(PKT_HP16, PK_FLAG_JOINED | PK_FLAG_FILTERED | PK_FLAG_FAST, 0xC0, 0, 0, 0, 0, 0xFFFF, pkt_env(o, v12, 1, NONE, NONE),
          NONE, NONE, NONE);
    PKT_I(PKT_KICK, PK_FLAG_SAMPLE, 0, 0, 0, PKT_N(C, 4), 7990, 0xFFFF, NONE, NONE, pkt_env(o, pkick, 3, NONE, NONE), NONE);
    PKT_I(PKT_LOOPS, PK_FLAG_SAMPLE | PK_FLAG_FAST, 0, 0, 1, PKT_N(C, 4), 15980, 4, NONE, NONE, NONE, NONE);
    PKT_I(PKT_VOLONLY, 0, 0, 0, 0, 0, 0, 0xFFFF, pkt_env(o, vvol, 5, NONE, NONE), pkt_env(o, avol, 3, 0, NONE), NONE,
          pkt_env(o, d8, 1, NONE, NONE));
    PKT_I(PKT_PARTNER, PK_FLAG_JOINED, 0, 0xA4, 0, 0, 0, 0xFFFF, pkt_env(o, v10, 1, NONE, NONE), NONE, NONE, NONE);
#undef PKT_I
    for (int i = 0; i < n; ++i) {
        uint8_t* p = b + PK_OFF_INSTRUMENTS + PK_INSTRUMENT_SIZE * (r[i].slot - 1);
        p[PK_I_FLAGS] = (uint8_t)(PK_FLAG_DEFINED | r[i].flags);
        p[PK_I_FILTER_OFFSET] = (uint8_t)r[i].filter;
        p[PK_I_PARTNER_AUDC] = (uint8_t)r[i].partner;
        p[PK_I_SAMPLE] = (uint8_t)r[i].sample;
        p[PK_I_ROOT] = (uint8_t)r[i].root;
        pkt_u16(p + PK_I_RATE, r[i].rate);
        pkt_u16(p + PK_I_LOOP, r[i].loop);
        for (int k = 0; k < 4; ++k) pkt_u16(p + PK_I_ENV_VOLUME + 2 * k, r[i].env[k]);
    }
}

/* Writes the test song into image (PK_IMAGE_SIZE bytes). */
static void pk_build_test_song(uint8_t* b, const PkSongOptions* opt) {
    memset(b, 0, PK_IMAGE_SIZE);
    const int orders = opt->cut ? 2 : 4;
    memcpy(b, "TTPK", 4);
    b[PK_H_LAYOUT] = PK_LAYOUT;
    b[PK_H_MACHINE] = (uint8_t)opt->machine;
    b[PK_H_MULTISPEED] = (uint8_t)(opt->multispeed ? opt->multispeed : 1);
    b[PK_H_SPEED] = 6;
    pkt_u16(b + PK_H_ORDERS, orders);
    pkt_u16(b + PK_H_LOOP, 1);
    b[PK_H_INSTRUMENTS] = PKT_INSTRUMENTS;
    b[PK_H_AUDCTL] = 0;
    PktOut o = {b, 0};
    if (opt->relayout) o.at = 37; /* a gap: the same song at other offsets */
    else pkt_instruments(&o, b);
    for (int k = 0; k < 4 * orders; ++k) {
        const int i = opt->relayout ? 4 * orders - 1 - k : k, order = i / 4, lane = i % 4;
        pkt_u16(b + PK_OFF_ORDERS + 2 * (PK_CHANNELS * order + lane), pkt_pattern(&o, opt, order, lane));
    }
    if (opt->relayout) pkt_instruments(&o, b);
    for (int order = 0; order < orders; ++order) b[PK_OFF_ROWS + order] = (uint8_t)PKT_ROWS[order];
    pkt_u16(b + PK_H_DATA_SIZE, o.at);
    /* samples, two levels per byte (the earlier in the low nibble) */
    const int lengths[2] = {PKT_KICK_LEVELS, PKT_LOOP_LEVELS};
    int bytes = 0;
    for (int k = 0; k < 2; ++k) {
        uint8_t* e = b + PK_OFF_SAMPLES + PK_SAMPLE_ENTRY_SIZE * k;
        pkt_u16(e, bytes);
        pkt_u16(e + 2, lengths[k]);
        for (int i = 0; i < lengths[k]; ++i)
            b[PK_OFF_SAMPLE_DATA + bytes + i / 2] |= (uint8_t)(pkt_sample_level(k, i) << (4 * (i & 1)));
        bytes += (lengths[k] + 1) / 2;
    }
    pkt_u16(b + PK_H_SAMPLE_SIZE, bytes);
    b[PK_H_SAMPLES] = 2;
}

#endif /* THORTRACKER_POKEY_TEST_SONG_H_ */
