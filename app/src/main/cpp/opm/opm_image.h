/* OPM song images: the bytes a lowering produces for the "ym2151-opm" engine (Thor Tracker's own player on a
 * YM2151 at the X68000's 4 MHz, docs/AUDIO_ENGINE.md "OPM"). One fixed-size, position-independent image: a header
 * (with the chip's shared LFO, noise and CSM state at the song start), fixed tables (rows per order, the order table
 * of pattern offsets, 64 instruments) and the pattern data region. Every pattern reference is an offset into the
 * region, so data may move freely between images (live edits locate rows by (order, row), never by pointer).
 *
 * om_load_image validates every byte the player can reach and derives OmInfo; images that fail are never played.
 * All multi-byte fields are little-endian. */
#ifndef THORTRACKER_OPM_IMAGE_H_
#define THORTRACKER_OPM_IMAGE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define OM_IMAGE_SIZE 65536u
#define OM_CHANNELS 8       /* tracker channels = YM2151 channels */
#define OM_NOISE_CHANNEL 7  /* FM 8: its operator 4 (C2) can be the noise generator */
#define OM_MAX_ORDERS 256
#define OM_MAX_INSTRUMENTS 64
#define OM_MAX_EFFECTS 4    /* effect columns per channel and row */
#define OM_MAX_MULTISPEED 8
#define OM_LAYOUT 1

/* Header (64 bytes). */
#define OM_H_MAGIC 0        /* "TTOM" */
#define OM_H_LAYOUT 4       /* OM_LAYOUT */
#define OM_H_MULTISPEED 5   /* N = 1..8: player ticks per 60 Hz frame */
#define OM_H_SPEED 6        /* ticks per row at the song start, 1..255 */
#define OM_H_LFO_WAVE 7     /* register $1B bits 0-1 at the start: 0 saw, 1 square, 2 triangle, 3 noise */
#define OM_H_ORDERS 8       /* u16 order count 1..256 */
#define OM_H_LOOP 10        /* u16 loop order < order count */
#define OM_H_INSTRUMENTS 12 /* highest instrument slot present, 0..64 */
#define OM_H_CSM 13         /* 0 = CSM off, 1 = on (register $14 = $81 at the start) */
#define OM_H_DATA_SIZE 14   /* u16 bytes of pattern data used (<= OM_DATA_CAPACITY) */
#define OM_H_LFO_RATE 16    /* register $18 (LFRQ) at the start, 0..255 */
#define OM_H_AMD 17         /* register $19 with bit 7 clear (AMD) at the start, 0..127 */
#define OM_H_PMD 18         /* register $19 with bit 7 set (PMD) at the start, 0..127 */
#define OM_H_TIMER_A 20     /* u16 timer A (CSM rate) at the start, 0..1023: $10 = value >> 2, $11 = value & 3 */
#define OM_HEADER_SIZE 64   /* byte 19 and bytes 22..63 are 0 */

/* Fixed tables. */
#define OM_OFF_ROWS 64          /* 256 x u8: rows of order o (1..255; 0 = 256) */
#define OM_OFF_ORDERS 320       /* 256 x 8 x u16: pattern of (order o, channel c) at 320 + 2 (8 o + c), an offset into the data region */
#define OM_OFF_INSTRUMENTS 4416 /* 64 x 32: slot n (1..64) at 4416 + 32 (n - 1) */
#define OM_INSTRUMENT_SIZE 32
#define OM_OFF_DATA 6464        /* pattern data region */
#define OM_DATA_CAPACITY (OM_IMAGE_SIZE - OM_OFF_DATA) /* 59072 */

/* Instrument record (32 bytes). Operators are numbered as in the YM2151 algorithm diagrams: operator 1 = M1,
 * 2 = C1, 3 = M2, 4 = C2 (operator 1 has the feedback, operator 4 of FM 8 is the noise operator). */
#define OM_I_FLAGS 0        /* bit 7 = defined, bit 0 = noise (operator 4 is the noise generator: FM 8 only); others 0. An all-zero record = no instrument */
#define OM_I_CONNECT 1      /* register $20 bits 0-5: feedback << 3 | algorithm (bits 6-7 = pan: 0 here) */
#define OM_I_SENS 2         /* register $38: PMS << 4 | AMS (bits 7, 3, 2 zero) */
#define OM_I_NOISE 3        /* noise frequency NFRQ 0..31 (register $0F bits 0-4); 0 without the noise flag */
#define OM_I_OPS 4          /* operator k (0..3) at 4 + 6k: registers $40 (DT1 << 4 | MUL), $60 (TL), $80 (KS << 6 | AR),
                               $A0 (AMS-EN << 7 | D1R), $C0 (DT2 << 6 | D2R), $E0 (D1L << 4 | RR); reserved bits zero */
#define OM_OPERATOR_BYTES 6
#define OM_I_SWEEP_START 28 /* s8 semitones relative to the note, -96..96 */
#define OM_I_SWEEP_END 29   /* s8 semitones, -96..96 */
#define OM_I_SWEEP_SPEED 30 /* u8 1/16 semitones per tick toward the end; 0 = no sweep */
#define OM_FLAG_DEFINED 0x80
#define OM_FLAG_NOISE 0x01

/* Pattern rows. A run byte 0x80 | k = k + 1 empty rows (k 0..127). Otherwise a mask byte 0x01..0x13:
 * bit 0 note byte follows, bit 1 instrument byte follows, bits 2-4 effect count 0..4; then the note
 * (OM_NOTE_MIN..OM_NOTE_MAX absolute; 0xFE = key off), the instrument slot (1..64), and per effect column in
 * order the effect code (0..35 = '0'..'9', 'A'..'Z') and its parameter. */
#define OM_NOTE_OFF 0xFE
#define OM_NOTE_MIN 3   /* D#0: the lowest note the 4 MHz chip sounds (key code C#0 + key fraction 5/64) */
#define OM_NOTE_MAX 98  /* D-8: the highest (key code C-8 + key fraction 5/64) */

/* Effect codes ('0'..'9' = 0..9, 'A'..'Z' = 10..35). */
enum {
    OM_FX_ARPEGGIO = 0,     /* 0xy */
    OM_FX_SLIDE_UP = 1,     /* 1xx */
    OM_FX_SLIDE_DOWN = 2,   /* 2xx */
    OM_FX_PORTAMENTO = 3,   /* 3xx */
    OM_FX_VIBRATO = 4,      /* 4xy */
    OM_FX_PAN = 8,          /* 8xy */
    OM_FX_VOLUME_SLIDE = 10, /* Axy */
    OM_FX_JUMP = 11,        /* Bxx */
    OM_FX_VOLUME = 12,      /* Cxx */
    OM_FX_BREAK = 13,       /* Dxx */
    OM_FX_FEEDBACK = 14,    /* Exx (lock) */
    OM_FX_SPEED = 15,       /* Fxx */
    OM_FX_DELAY = 16,       /* Gxx */
    OM_FX_SENSITIVITY = 17, /* Hxy: the channel's PMS x, AMS y until the next note */
    OM_FX_BRIGHTNESS = 18,  /* Ixx (lock) */
    OM_FX_LFO_RATE = 21,    /* Lxx: shared LFO frequency ($18) */
    OM_FX_MULT = 22,        /* Mxy (lock) */
    OM_FX_NOISE = 23,       /* Nxx: FM 8's noise frequency ($0F), FM 8 only */
    OM_FX_FINE = 25,        /* Pxx */
    OM_FX_TIMER_A = 26,     /* Qxx: shared timer A high byte ($10), the CSM rate */
    OM_FX_CUT = 28,         /* Sxx */
    OM_FX_AMD = 29,         /* Txx: shared LFO amplitude depth ($19, bit 7 clear) */
    OM_FX_PMD = 31,         /* Vxx: shared LFO pitch depth ($19, bit 7 set) */
    OM_FX_LFO_WAVE = 32,    /* Wxx: shared LFO waveform ($1B bits 0-1) */
    OM_FX_CSM = 33,         /* Xxx: shared CSM mode ($14 bit 7): X00 off, X01 on */
};

typedef struct OmInfo {
    int loaded;            /* OM_LOADED when valid */
    const char* problem;   /* why om_load_image rejected the image (static string), else NULL */
    int problem_offset;    /* image offset of the offending byte, or -1 */
    int orders;
    int loop;
    int multispeed;
    int speed;
    int instruments;
    uint16_t rows[OM_MAX_ORDERS];        /* rows of each order, 1..256 */
    uint8_t start_speed[OM_MAX_ORDERS];  /* ticks per row each order starts with in sequential straight play */
} OmInfo;

#define OM_LOADED 0x4F504D4C

typedef struct OpmImage {
    uint8_t bytes[OM_IMAGE_SIZE];
    OmInfo info;
} OpmImage;

/* One decoded pattern row of a channel. */
typedef struct OmRow {
    int note;        /* -1 none, OM_NOTE_MIN..OM_NOTE_MAX, OM_NOTE_OFF */
    int instrument;  /* 0 none, 1..64 */
    int effects;     /* 0..4 */
    uint8_t code[OM_MAX_EFFECTS];
    uint8_t param[OM_MAX_EFFECTS];
} OmRow;

/* Validates image->bytes and fills image->info. Returns 0 or -1 (info.problem says why). */
int om_load_image(OpmImage* image);

/* Pattern cursor of one channel: decodes rows sequentially from an offset. */
typedef struct OmCursor {
    int offset; /* data region offset of the next byte */
    int run;    /* empty rows left in the current run */
} OmCursor;

/* Decodes the next row at the cursor (no bounds checks: loaded images only). */
void om_next_row(const uint8_t* bytes, OmCursor* cursor, OmRow* row);
/* Decodes row `row` of (order, channel) from the pattern start; `after` (may be NULL) receives the cursor past it. */
void om_read_row(const OpmImage* image, int order, int channel, int row, OmRow* out, OmCursor* after);

static inline int om_u16(const uint8_t* p) { return p[0] | p[1] << 8; }

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_OPM_IMAGE_H_ */
