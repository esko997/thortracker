/* YM2612 song images: the bytes a lowering produces for the "ym2612-fm" engine (Thor Tracker's own player, its
 * writes streamed through SGDK's XGM driver on a Z80 + YM2612 + PSG; docs/AUDIO_ENGINE.md "YM2612"). One
 * fixed-size, position-independent image: a header, fixed tables (rows per order, the order table of pattern
 * offsets, 64 instruments) and the pattern data region. Every pattern reference is an offset into the region, so
 * data may move freely between images (live edits locate rows by (order, row), never by pointer).
 *
 * ym_load_image validates every byte the player can reach and derives YmInfo; images that fail are never played.
 * All multi-byte fields are little-endian. */
#ifndef THORTRACKER_YM_IMAGE_H_
#define THORTRACKER_YM_IMAGE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define YM_IMAGE_SIZE 65536u
#define YM_CHANNELS 10      /* tracker channels: FM 1-6, PSG 1-3, Noise */
#define YM_FM_CHANNELS 6
#define YM_CH_PSG 6         /* first PSG tone channel */
#define YM_CH_NOISE 9
#define YM_MAX_ORDERS 256
#define YM_MAX_INSTRUMENTS 64
#define YM_MAX_EFFECTS 4    /* effect columns per channel and row */
#define YM_LAYOUT 1

enum { YM_NTSC = 0, YM_PAL = 1 };

/* Header (64 bytes). */
#define YM_H_MAGIC 0        /* "TTYM" */
#define YM_H_LAYOUT 4       /* YM_LAYOUT */
#define YM_H_REGION 5       /* YM_NTSC / YM_PAL */
#define YM_H_SPEED 6        /* ticks per row at the song start, 1..255 */
#define YM_H_LFO 7          /* register $22 at the start: 0 off, $08..$0F on with rate 0..7 */
#define YM_H_ORDERS 8       /* u16 order count 1..256 */
#define YM_H_LOOP 10        /* u16 loop order < order count */
#define YM_H_INSTRUMENTS 12 /* highest instrument slot present, 0..64 */
#define YM_H_DATA_SIZE 14   /* u16 bytes of pattern data used (<= YM_DATA_CAPACITY) */
#define YM_HEADER_SIZE 64   /* byte 13 and bytes 16..63 are 0 */

/* Fixed tables. */
#define YM_OFF_ROWS 64          /* 256 x u8: rows of order o (1..255; 0 = 256) */
#define YM_OFF_ORDERS 320       /* 256 x 10 x u16: pattern of (order o, channel c) at 320 + 2 (10 o + c) */
#define YM_OFF_INSTRUMENTS 5440 /* 64 x 40: slot n (1..64) at 5440 + 40 (n - 1) */
#define YM_INSTRUMENT_SIZE 40
#define YM_OFF_DATA 8000        /* pattern data region */
#define YM_DATA_CAPACITY (YM_IMAGE_SIZE - YM_OFF_DATA) /* 57536 */

/* Instrument record (40 bytes). Byte 0: flags. */
#define YM_I_FLAGS 0
#define YM_FLAG_DEFINED 0x80
#define YM_FLAG_PSG 0x01
/* FM instrument (flags 0x80). */
#define YM_I_ALG 1          /* algorithm 0..7 */
#define YM_I_FEEDBACK 2     /* operator 1 feedback 0..7 */
#define YM_I_AMS_FMS 3      /* register $B4 without pan: AMS bits 4-5, FMS bits 0-2 */
#define YM_I_OPS 4          /* operator k (0..3) at 4 + 7k: $30 $40 $50 $60 $70 $80 $90 */
#define YM_OP_BYTES 7
/* PSG instrument (flags 0x81). */
#define YM_I_ATTACK 1       /* ticks per envelope step up (0 = start at 15) */
#define YM_I_DECAY 2        /* ticks per step down to sustain (0 = at once) */
#define YM_I_SUSTAIN 3      /* sustain level 0..15 */
#define YM_I_RELEASE 4      /* ticks per step down to 0 after key-off (0 = at once) */
#define YM_I_NOISE 5        /* noise mode: bit 2 white, bits 0-1 source (3 = PSG 3's period) */
/* Both. */
#define YM_I_SWEEP_START 32 /* s8 semitones relative to the note, -96..96 */
#define YM_I_SWEEP_END 33   /* s8 semitones, -96..96 */
#define YM_I_SWEEP_SPEED 34 /* u8 1/16 semitones per tick toward the end; 0 = no sweep */

/* Pattern rows. A run byte 0x80 | k = k + 1 empty rows (k 0..127). Otherwise a mask byte 0x01..0x13:
 * bit 0 note byte follows, bit 1 instrument byte follows, bits 2-4 effect count 0..4; then the note
 * (0..95 = C-0..B-7, absolute; 0xFE = key off), the instrument slot (1..64), and per effect column in
 * order the effect code (0..35 = '0'..'9', 'A'..'Z') and its parameter. */
#define YM_NOTE_OFF 0xFE
#define YM_NOTES 96

/* Effect codes ('0'..'9' = 0..9, 'A'..'Z' = 10..35). */
enum {
    YM_FX_ARPEGGIO = 0,     /* 0xy */
    YM_FX_SLIDE_UP = 1,     /* 1xx */
    YM_FX_SLIDE_DOWN = 2,   /* 2xx */
    YM_FX_PORTAMENTO = 3,   /* 3xx */
    YM_FX_VIBRATO = 4,      /* 4xy */
    YM_FX_PAN = 8,          /* 8xy (FM) */
    YM_FX_VOLUME_SLIDE = 10, /* Axy */
    YM_FX_JUMP = 11,        /* Bxx */
    YM_FX_VOLUME = 12,      /* Cxx */
    YM_FX_BREAK = 13,       /* Dxx */
    YM_FX_FEEDBACK = 14,    /* Exx (FM lock) */
    YM_FX_SPEED = 15,       /* Fxx */
    YM_FX_DELAY = 16,       /* Gxx */
    YM_FX_BRIGHTNESS = 18,  /* Ixx (FM lock) */
    YM_FX_LFO = 21,         /* Lxx */
    YM_FX_MULT = 22,        /* Mxy (FM lock) */
    YM_FX_NOISE = 23,       /* Nxy (Noise lock) */
    YM_FX_FINE = 25,        /* Pxx */
    YM_FX_CUT = 28,         /* Sxx */
};

typedef struct YmInfo {
    int loaded;            /* YM_LOADED when valid */
    const char* problem;   /* why ym_load_image rejected the image (static string), else NULL */
    int problem_offset;    /* image offset of the offending byte, or -1 */
    int orders;
    int loop;
    int region;
    int speed;
    int lfo;
    int instruments;
    uint16_t rows[YM_MAX_ORDERS];        /* rows of each order, 1..256 */
    uint8_t start_speed[YM_MAX_ORDERS];  /* ticks per row each order starts with in sequential straight play */
} YmInfo;

#define YM_LOADED 0x594D4C44

typedef struct YmImage {
    uint8_t bytes[YM_IMAGE_SIZE];
    YmInfo info;
} YmImage;

/* One decoded pattern row of a channel. */
typedef struct YmRow {
    int note;        /* -1 none, 0..95, YM_NOTE_OFF */
    int instrument;  /* 0 none, 1..64 */
    int effects;     /* 0..4 */
    uint8_t code[YM_MAX_EFFECTS];
    uint8_t param[YM_MAX_EFFECTS];
} YmRow;

/* Validates image->bytes and fills image->info. Returns 0 or -1 (info.problem says why). */
int ym_load_image(YmImage* image);

/* Pattern cursor of one channel: decodes rows sequentially from an offset. */
typedef struct YmCursor {
    int offset; /* data region offset of the next byte */
    int run;    /* empty rows left in the current run */
} YmCursor;

/* Decodes the next row at the cursor (no bounds checks: loaded images only). */
void ym_next_row(const uint8_t* bytes, YmCursor* cursor, YmRow* row);
/* Decodes row `row` of (order, channel) from the pattern start. */
void ym_read_row(const YmImage* image, int order, int channel, int row, YmRow* out);
/* The pattern cursor at row 0 of (order, channel). */
YmCursor ym_pattern(const YmImage* image, int order, int channel);
/* The instrument record of a slot if it is defined (and of the kind: psg 0 FM, 1 PSG, -1 any), else NULL. */
const uint8_t* ym_instrument(const YmImage* image, int slot, int psg);

static inline int ym_u16(const uint8_t* p) { return p[0] | p[1] << 8; }
static inline int ym_is_psg_channel(int c) { return c >= YM_CH_PSG; }

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_YM_IMAGE_H_ */
