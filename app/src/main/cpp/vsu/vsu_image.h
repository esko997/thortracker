/* Virtual Boy VSU song images: the bytes a lowering produces for the "vb-vsu" engine (Thor Tracker's own player on
 * the VSU, docs/AUDIO_ENGINE.md "Virtual Boy VSU: Image layout"). One fixed-size, position-independent image: a
 * header, fixed tables (rows per order, the order table of pattern offsets, 64 instruments, the five song-wide waves
 * and the modulation table) and the pattern data region. Every pattern reference is an offset into the region, so
 * data may move freely between images (live edits locate rows by (order, row), instruments by slot).
 *
 * vb_load_image validates every byte the player can reach and derives VbInfo; images that fail are never played.
 * All multi-byte fields are little-endian. core/.../backend/VsuPlayerImage.kt mirrors these numbers by hand. */
#ifndef THORTRACKER_VSU_IMAGE_H_
#define THORTRACKER_VSU_IMAGE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define VB_IMAGE_SIZE 65536u
#define VB_LANES 6          /* Wave 1-4, Wave 5 (index 4), Noise (index 5) */
#define VB_LANE_WAVE5 4
#define VB_LANE_NOISE 5
#define VB_MAX_ORDERS 256
#define VB_MAX_INSTRUMENTS 64
#define VB_MAX_EFFECTS 2    /* effect columns per lane and row */
#define VB_LAYOUT 1

/* Header (64 bytes). */
#define VB_H_MAGIC 0        /* "TTVB" */
#define VB_H_LAYOUT 4       /* VB_LAYOUT */
#define VB_H_SPEED 5        /* ticks per row at the song start, 1..255 */
#define VB_H_ORDERS 6       /* u16 order count 1..256 */
#define VB_H_LOOP 8         /* u16 loop order < order count */
#define VB_H_INSTRUMENTS 10 /* highest instrument slot present, 0..64 */
#define VB_H_ZERO 11        /* 0 */
#define VB_H_DATA_SIZE 12   /* u16 bytes of pattern data used (<= VB_DATA_CAPACITY) */
#define VB_HEADER_SIZE 64   /* bytes 14..63 are 0 */

/* Fixed tables. */
#define VB_OFF_ROWS 64          /* 256 x u8: rows of order o (1..255; 0 = 256) */
#define VB_OFF_ORDERS 320       /* 256 x 6 x u16: pattern of (order o, lane c) at 320 + 2 (6 o + c), a data region offset */
#define VB_OFF_INSTRUMENTS 3392 /* 64 x 16: slot n (1..64) at 3392 + 16 (n - 1) */
#define VB_INSTRUMENT_SIZE 16
#define VB_OFF_WAVES 4416       /* 5 x 32: wave w (0..4) sample i at 4416 + 32 w + i, 0..63 */
#define VB_OFF_MOD 4576         /* 32 x s8: the modulation table */
#define VB_OFF_DATA 4608        /* pattern data region */
#define VB_DATA_CAPACITY (VB_IMAGE_SIZE - VB_OFF_DATA) /* 60928 */

/* Instrument record (16 bytes; an all-zero record = no instrument; records above VB_H_INSTRUMENTS must be zero). */
#define VB_I_FLAGS 0        /* VB_FLAG_* */
#define VB_I_LEVEL 1        /* envelope start level 0..15 (EV0 Value) */
#define VB_I_INTERVAL 2     /* envelope interval 0..7 ((n + 1) x 15.36 ms per step) */
#define VB_I_LENGTH 3       /* 0..31 ((n + 1) x 3.84 ms; used with VB_FLAG_AUTO) */
#define VB_I_WAVE 4         /* 0..4 = wave 1..5 (ignored on the noise lane) */
#define VB_I_TAP 5          /* noise tap 0..7 (S6EV1 bits 4-6; ignored on wave lanes) */
#define VB_I_FREQMOD 6      /* Wave 5 only: VB_FM_* */
#define VB_I_SWP 7          /* Wave 5 only: S5SWP (bit 7 slow clock, bits 4-6 interval, bit 3 up, bits 0-2 shift) */
#define VB_I_SWEEP_START 8  /* s8 software pitch sweep start, semitones relative to the note, -96..96 */
#define VB_I_SWEEP_END 9    /* s8 semitones, -96..96 */
#define VB_I_SWEEP_SPEED 10 /* 1/16 semitone per tick toward the end; 0 = none (start/end must be 0) */
/* bytes 11..15 are 0 */

#define VB_FLAG_DEFINED 0x80
#define VB_FLAG_ENVELOPE 0x01 /* EVx1 Enb */
#define VB_FLAG_REPEAT 0x02   /* EVx1 Rep */
#define VB_FLAG_GROW 0x04     /* EVx0 Dir */
#define VB_FLAG_AUTO 0x08     /* SxINT Auto */
#define VB_FLAG_ALL 0x8F

#define VB_FM_ON 0x01         /* S5EV1 bit 6 */
#define VB_FM_MODULATION 0x02 /* S5EV1 bit 4 (else sweep) */
#define VB_FM_REPEAT 0x04     /* S5EV1 bit 5 */
#define VB_FM_ALL 0x07

/* Pattern rows. A run byte 0x80 | k = k + 1 empty rows (k 0..127). Otherwise a mask byte 0x01..0x0B:
 * bit 0 note byte follows, bit 1 instrument byte follows, bits 2-3 effect count 0..2 (bits 4-6 zero); then the
 * note (absolute; wave lanes 27..95, noise lane 0..95; 0xFE = OFF), the instrument slot (1..I, defined), and per
 * effect column in order the effect code (0..35 = '0'..'9', 'A'..'Z') and its parameter. */
#define VB_NOTE_OFF 0xFE
#define VB_NOTES 96
#define VB_WAVE_NOTE_MIN 27 /* D#2: lower notes would need a negative frequency value */

/* Effect codes ('0'..'9' = 0..9, 'A'..'Z' = 10..35). */
enum {
    VB_FX_ARPEGGIO = 0,   /* 0xy */
    VB_FX_SLIDE_UP = 1,   /* 1xx: 1/64 semitones per tick */
    VB_FX_SLIDE_DOWN = 2, /* 2xx */
    VB_FX_PORTAMENTO = 3, /* 3xx */
    VB_FX_VIBRATO = 4,    /* 4xy */
    VB_FX_STEREO = 8,     /* 8xy: left x, right y */
    VB_FX_JUMP = 11,      /* Bxx */
    VB_FX_LEVEL = 12,     /* Cxx: 00-0F (lock) */
    VB_FX_BREAK = 13,     /* Dxx */
    VB_FX_SPEED = 15,     /* Fxx: 01-FF */
    VB_FX_DELAY = 16,     /* Gxx */
    VB_FX_LENGTH = 21,    /* Lxx: 00-20 (lock) */
    VB_FX_SWEEPMOD = 22,  /* Mxx: S5SWP byte, Wave 5 only (lock) */
    VB_FX_TAP = 23,       /* Nxx: 00-07, Noise only (lock) */
    VB_FX_FINE = 25,      /* Pxx: 80 = none */
    VB_FX_CUT = 28,       /* Sxx: note off at tick xx */
    VB_FX_WAVE = 32,      /* Wxx: 01-05, wave lanes only (lock) */
};

#define VB_FINE_CENTRE 0x80

typedef struct VbInfo {
    int loaded;            /* VB_LOADED when valid */
    const char* problem;   /* why vb_load_image rejected the image (static string), else NULL */
    int problem_offset;    /* image offset of the offending byte, or -1 */
    int orders;
    int loop;
    int speed;
    int instruments;
    uint16_t rows[VB_MAX_ORDERS];        /* rows of each order, 1..256 */
    uint8_t start_speed[VB_MAX_ORDERS];  /* ticks per row each order starts with in sequential straight play */
} VbInfo;

#define VB_LOADED 0x56424C44

typedef struct VsuImage {
    uint8_t bytes[VB_IMAGE_SIZE];
    VbInfo info;
} VsuImage;

/* One decoded pattern row of a lane. */
typedef struct VbRow {
    int note;       /* -1 none, 0..95, VB_NOTE_OFF */
    int instrument; /* 0 none, 1..64 */
    int effects;    /* 0..2 */
    uint8_t code[VB_MAX_EFFECTS];
    uint8_t param[VB_MAX_EFFECTS];
} VbRow;

/* Validates image->bytes and fills image->info. Returns 0 or -1 (info.problem says why, problem_offset where). */
int vb_load_image(VsuImage* image);

/* Pattern cursor of one lane: decodes rows sequentially from an offset. */
typedef struct VbCursor {
    int offset; /* data region offset of the next byte */
    int run;    /* empty rows left in the current run */
} VbCursor;

/* Decodes the next row at the cursor (no bounds checks: loaded images only). */
void vb_next_row(const uint8_t* bytes, VbCursor* cursor, VbRow* row);
/* Decodes row `row` of (order, lane) from the pattern start. */
void vb_read_row(const VsuImage* image, int order, int lane, int row, VbRow* out, VbCursor* after);

static inline int vb_u16(const uint8_t* p) { return p[0] | p[1] << 8; }
/* The pattern offset of (order, lane). */
static inline int vb_pattern(const uint8_t* bytes, int order, int lane) {
    return vb_u16(bytes + VB_OFF_ORDERS + 2 * (VB_LANES * order + lane));
}
/* An instrument record of a loaded image, or NULL (slot outside 1..instruments, or not defined). */
const uint8_t* vb_instrument(const VsuImage* image, int slot);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_VSU_IMAGE_H_ */
