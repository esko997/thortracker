/* OPL3 song images: the bytes a lowering produces for the "opl3-fm" engine (Thor Tracker's own player on
 * a YMF262, docs/AUDIO_ENGINE.md "OPL3"). One fixed-size, position-independent image: a header, fixed
 * tables (rows per order, the order table of pattern offsets, 64 instruments, 64 chord shapes) and the
 * pattern data region. Every pattern reference is an offset into the region, so data may move freely
 * between images (live edits locate rows by (order, row), never by pointer).
 *
 * o3_load_image validates every byte the player can reach and derives O3Info; images that fail are
 * never played. All multi-byte fields are little-endian. */
#ifndef THORTRACKER_OPL3_IMAGE_H_
#define THORTRACKER_OPL3_IMAGE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define O3_IMAGE_SIZE 65536u
#define O3_CHANNELS 9     /* tracker channels */
#define O3_VOICES 18      /* YMF262 two-operator channels */
#define O3_MAX_ORDERS 256
#define O3_MAX_INSTRUMENTS 64
#define O3_MAX_SHAPES 64
#define O3_MAX_EFFECTS 4  /* effect columns per channel and row */
#define O3_MAX_MULTISPEED 8
#define O3_LAYOUT 1

/* Header (64 bytes). */
#define O3_H_MAGIC 0        /* "TTO3" */
#define O3_H_LAYOUT 4       /* O3_LAYOUT */
#define O3_H_MULTISPEED 5   /* N = 1..8: player ticks per 60 Hz frame */
#define O3_H_SPEED 6        /* ticks per row at the song start, 1..255 */
#define O3_H_DEPTH 7        /* register 0xBD: bit 7 deep tremolo (4.8 dB), bit 6 deep vibrato (14 cents); others 0 */
#define O3_H_ORDERS 8       /* u16 order count 1..256 */
#define O3_H_LOOP 10        /* u16 loop order < order count */
#define O3_H_INSTRUMENTS 12 /* highest instrument slot present, 0..64 */
#define O3_H_SHAPES 13      /* chord shapes, 0..64 */
#define O3_H_DATA_SIZE 14   /* u16 bytes of pattern data used (<= O3_DATA_CAPACITY) */
#define O3_HEADER_SIZE 64   /* bytes 16..63 are 0 */

/* Fixed tables. */
#define O3_OFF_ROWS 64          /* 256 x u8: rows of order o (1..255; 0 = 256) */
#define O3_OFF_ORDERS 320       /* 256 x 9 x u16: pattern of (order o, channel c) at 320 + 2 (9 o + c), an offset into the data region */
#define O3_OFF_INSTRUMENTS 4928 /* 64 x 32: slot n (1..64) at 4928 + 32 (n - 1) */
#define O3_INSTRUMENT_SIZE 32
#define O3_OFF_SHAPES 6976      /* 64 x 4: shape k (1..64) at 6976 + 4 (k - 1) */
#define O3_OFF_DATA 7232        /* pattern data region */
#define O3_DATA_CAPACITY (O3_IMAGE_SIZE - O3_OFF_DATA) /* 58304 */

/* Instrument record (32 bytes). */
#define O3_I_FLAGS 0   /* bit 7 = defined, bit 0 = four-operator; others 0. An all-zero record = no instrument */
#define O3_I_ALG 1     /* 2-op: 0 = FM (1->2), 1 = additive (1+2). 4-op: CNT_A * 2 + CNT_B, 0..3 */
#define O3_I_FEEDBACK 2 /* operator 1 feedback, 0..7 */
#define O3_I_OPS 4     /* operator k (0..3) at 4 + 5k: regs 0x20, 0x40, 0x60, 0x80, 0xE0 (wave 0..7); 2-op: ops 2, 3 zero */
#define O3_I_SWEEP_START 24 /* s8 semitones relative to the note, -96..96 */
#define O3_I_SWEEP_END 25   /* s8 semitones, -96..96 */
#define O3_I_SWEEP_SPEED 26 /* u8 1/16 semitones per tick toward the end; 0 = no sweep */
#define O3_FLAG_DEFINED 0x80
#define O3_FLAG_FOUR_OP 0x01

/* Pattern rows. A run byte 0x80 | k = k + 1 empty rows (k 0..127). Otherwise a mask byte 0x01..0x13:
 * bit 0 note byte follows, bit 1 instrument byte follows, bits 2-4 effect count 0..4; then the note
 * (0..95 = C-0..B-7, absolute; 0xFE = key off), the instrument slot (1..64), and per effect column in
 * order the effect code (0..35 = '0'..'9', 'A'..'Z') and its parameter. */
#define O3_NOTE_OFF 0xFE
#define O3_NOTES 96

/* Effect codes ('0'..'9' = 0..9, 'A'..'Z' = 10..35). */
enum {
    O3_FX_ARPEGGIO = 0,    /* 0xy */
    O3_FX_SLIDE_UP = 1,    /* 1xx */
    O3_FX_SLIDE_DOWN = 2,  /* 2xx */
    O3_FX_PORTAMENTO = 3,  /* 3xx */
    O3_FX_VIBRATO = 4,     /* 4xy */
    O3_FX_PAN = 8,         /* 8xy */
    O3_FX_VOLUME_SLIDE = 10, /* Axy */
    O3_FX_JUMP = 11,       /* Bxx */
    O3_FX_VOLUME = 12,     /* Cxx */
    O3_FX_BREAK = 13,      /* Dxx */
    O3_FX_FEEDBACK = 14,   /* Exx (lock) */
    O3_FX_SPEED = 15,      /* Fxx */
    O3_FX_DELAY = 16,      /* Gxx */
    O3_FX_CHORD = 17,      /* Hxy */
    O3_FX_BRIGHTNESS = 18, /* Ixx (lock) */
    O3_FX_SHAPE = 20,      /* Kxx */
    O3_FX_MULT = 22,       /* Mxy (lock) */
    O3_FX_FINE = 25,       /* Pxx */
    O3_FX_CUT = 28,        /* Sxx */
    O3_FX_WAVE = 32,       /* Wxy (lock) */
};

typedef struct O3Info {
    int loaded;            /* O3_LOADED when valid */
    const char* problem;   /* why o3_load_image rejected the image (static string), else NULL */
    int problem_offset;    /* image offset of the offending byte, or -1 */
    int orders;
    int loop;
    int multispeed;
    int speed;
    int instruments;
    int shapes;
    uint16_t rows[O3_MAX_ORDERS];        /* rows of each order, 1..256 */
    uint8_t start_speed[O3_MAX_ORDERS];  /* ticks per row each order starts with in sequential straight play */
} O3Info;

#define O3_LOADED 0x4F334C44

typedef struct Opl3Image {
    uint8_t bytes[O3_IMAGE_SIZE];
    O3Info info;
} Opl3Image;

/* One decoded pattern row of a channel. */
typedef struct O3Row {
    int note;        /* -1 none, 0..95, O3_NOTE_OFF */
    int instrument;  /* 0 none, 1..64 */
    int effects;     /* 0..4 */
    uint8_t code[O3_MAX_EFFECTS];
    uint8_t param[O3_MAX_EFFECTS];
} O3Row;

/* Validates image->bytes and fills image->info. Returns 0 or -1 (info.problem says why). */
int o3_load_image(Opl3Image* image);

/* Pattern cursor of one channel: decodes rows sequentially from an offset. */
typedef struct O3Cursor {
    int offset; /* data region offset of the next byte */
    int run;    /* empty rows left in the current run */
} O3Cursor;

/* Decodes the next row at the cursor (no bounds checks: loaded images only). */
void o3_next_row(const uint8_t* bytes, O3Cursor* cursor, O3Row* row);
/* Decodes row `row` of (order, channel) from the pattern start. */
void o3_read_row(const Opl3Image* image, int order, int channel, int row, O3Row* out, O3Cursor* after);

static inline int o3_u16(const uint8_t* p) { return p[0] | p[1] << 8; }

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_OPL3_IMAGE_H_ */
