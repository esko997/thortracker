/* SAA1099 song images: the bytes a lowering produces for the "sam-saa1099" and "cms-saa1099" engines (Thor Tracker's
 * own player on one or two Philips SAA1099s, docs/AUDIO_ENGINE.md "SAA1099"). One fixed-size, position-independent
 * image: a header, fixed tables (rows per order, the order table of pattern offsets, 64 instruments) and the data
 * region holding patterns and envelopes. Every reference is an offset into the region, so data may move freely between
 * images (live edits locate rows by (order, row) and instruments by slot, never by pointer).
 *
 * sa_load_image validates every byte the player can reach and derives SaInfo; images that fail are never played. All
 * multi-byte fields are little-endian. tools/audio/prepare_saa.py parses the SA_* constants below into
 * SaaPlayerImage.kt: keep each a plain literal. */
#ifndef THORTRACKER_SAA_IMAGE_H_
#define THORTRACKER_SAA_IMAGE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SA_IMAGE_SIZE 65536
#define SA_MAGIC "TTSA"
#define SA_LAYOUT 1
#define SA_MAX_ORDERS 256
#define SA_MAX_INSTRUMENTS 64
#define SA_MAX_EFFECTS 4     /* effect columns per channel and row */
#define SA_MAX_MULTISPEED 8
#define SA_MAX_ENVELOPE 256  /* values per envelope */

/* Variants (header byte 5). */
#define SA_VARIANT_SAM 0 /* SAM Coupe: one SAA1099 at 8 MHz, 50 Hz ticks */
#define SA_VARIANT_CMS 1 /* Creative Music System: two SAA1099s at 7159090 Hz, 60 Hz ticks */
#define SA_CHANNELS_SAM 6
#define SA_CHANNELS_CMS 12
#define SA_MAX_CHANNELS 12   /* the order table's stride */
#define SA_CHIP_CHANNELS 6   /* tracker channel c = chip c / 6, chip channel c % 6 */

/* Header (64 bytes). */
#define SA_H_MAGIC 0        /* "TTSA" */
#define SA_H_LAYOUT 4       /* SA_LAYOUT */
#define SA_H_VARIANT 5      /* SA_VARIANT_* */
#define SA_H_MULTISPEED 6   /* N = 1..8: player ticks per 50 Hz (SAM) / 60 Hz (CMS) frame */
#define SA_H_SPEED 7        /* ticks per row at the song start, 1..255 */
#define SA_H_ORDERS 8       /* u16 order count 1..256 */
#define SA_H_LOOP 10        /* u16 loop order < order count */
#define SA_H_INSTRUMENTS 12 /* highest instrument slot present, 0..64 */
#define SA_H_CHANNELS 13    /* channel count C: 6 (SAM) or 12 (CMS) */
#define SA_H_DATA_SIZE 14   /* u16 bytes of the data region used (<= SA_DATA_CAPACITY) */
#define SA_HEADER_SIZE 64   /* bytes 16..63 are 0 */

/* Fixed tables. */
#define SA_OFF_ROWS 64          /* 256 x u8: rows of order o (1..255; 0 = 256) */
#define SA_OFF_ORDERS 320       /* 256 x 12 x u16: pattern of (order o, channel c) at 320 + 2 (12 o + c), a data offset */
#define SA_OFF_INSTRUMENTS 6464 /* 64 x 16: slot n (1..64) at 6464 + 16 (n - 1) */
#define SA_INSTRUMENT_SIZE 16
#define SA_OFF_DATA 7488        /* data region: patterns and envelopes */
#define SA_DATA_CAPACITY 58048  /* SA_IMAGE_SIZE - SA_OFF_DATA */

/* Instrument record (16 bytes; all zero = no instrument). */
#define SA_I_FLAGS 0     /* SA_FLAG_*; other bits 0 */
#define SA_I_NOISE 1     /* noise clock 0..3 (0 = clock / 256, 1 = / 512, 2 = / 1024, 3 = tone generator 0 / 3) */
#define SA_I_PAN 2       /* bits 0-3 left level, bits 4-7 right level */
#define SA_I_ENVELOPE 3  /* hardware envelope: 0 none, else the control value (bit 7 set, bit 6 clear) */
#define SA_I_RATE 4      /* external envelope clock: 1..255 ticks per step when byte 3 has bit 5, else 0 */
#define SA_I_VOLUME 6    /* u16 data offset of the volume envelope, SA_ENV_NONE = none (constant 15) */
#define SA_I_ARPEGGIO 8  /* u16 arpeggio envelope (s8 semitones), SA_ENV_NONE = none */
#define SA_I_PITCH 10    /* u16 pitch envelope (s8 1/16 semitones), SA_ENV_NONE = none */
#define SA_I_MIXER 12    /* u16 mixer envelope (0..3: bit 0 tone, bit 1 noise), SA_ENV_NONE = none (tone) */
#define SA_FLAG_DEFINED 0x80
#define SA_FLAG_PITCH_RELATIVE 0x01 /* the pitch envelope accumulates every tick */
#define SA_FLAG_ENV_RETRIGGER 0x02  /* a note restarts the hardware envelope (only with byte 3 != 0) */

/* Hardware envelope control value (byte 3, Exx). */
#define SA_ENV_ENABLE 0x80
#define SA_ENV_EXTERNAL 0x20
#define SA_ENV_3BIT 0x10
#define SA_ENV_SHAPE_SHIFT 1 /* bits 3-1: shape 0..7 */
#define SA_ENV_INVERT 0x01

/* Envelope (in the data region): u16 length L 1..256, u16 loop 0..L-1 (SA_ENV_NONE none), u16 release 1..L-1
 * (SA_ENV_NONE none; volume envelopes only; loop < release when both), then L value bytes. */
#define SA_ENV_NONE 0xFFFF
#define SA_ENV_HEADER 6
#define SA_ENV_LENGTH 0
#define SA_ENV_LOOP 2
#define SA_ENV_RELEASE 4
#define SA_ARPEGGIO_LIMIT 64  /* arpeggio values -64..63 */
#define SA_PITCH_ENV_LIMIT 64 /* pitch envelope values -64..63 */

/* Pattern rows (the OPL3's and SCC's encoding). A run byte 0x80 | k = k + 1 empty rows (k 0..127). Otherwise a mask
 * byte 0x01..0x13: bit 0 note byte follows, bit 1 instrument byte follows, bits 2-4 effect count 0..4; then the note
 * (0..119 = C-0..B-9, absolute; 0xFE = key off), the instrument slot (1..64), and per effect column in order the effect
 * code (0..35 = '0'..'9', 'A'..'Z') and its parameter. */
#define SA_RUN 0x80
#define SA_MAX_RUN 128
#define SA_MASK_NOTE 0x01
#define SA_MASK_INSTRUMENT 0x02
#define SA_MASK_EFFECT_SHIFT 2
#define SA_NOTE_OFF 0xFE
#define SA_NOTES 120

/* The effect letters the engines accept, in code order. */
#define SA_EFFECT_LETTERS "012348ABCDEFGMNPSY"

/* Effect codes ('0'..'9' = 0..9, 'A'..'Z' = 10..35). */
enum {
    SA_FX_ARPEGGIO = 0,      /* 0xy */
    SA_FX_SLIDE_UP = 1,      /* 1xx */
    SA_FX_SLIDE_DOWN = 2,    /* 2xx */
    SA_FX_PORTAMENTO = 3,    /* 3xx */
    SA_FX_VIBRATO = 4,       /* 4xy */
    SA_FX_PAN = 8,           /* 8xy: x left, y right */
    SA_FX_VOLUME_SLIDE = 10, /* Axy */
    SA_FX_JUMP = 11,         /* Bxx */
    SA_FX_VOLUME = 12,       /* Cxx, 00..0F */
    SA_FX_BREAK = 13,        /* Dxx */
    SA_FX_ENVELOPE = 14,     /* Exx, 00 or 80..BF */
    SA_FX_SPEED = 15,        /* Fxx, 01..FF */
    SA_FX_DELAY = 16,        /* Gxx */
    SA_FX_MIXER = 22,        /* Mxx, 00..03 */
    SA_FX_NOISE = 23,        /* Nxx, 00..03 */
    SA_FX_FINE = 25,         /* Pxx */
    SA_FX_CUT = 28,          /* Sxx */
    SA_FX_SYNC = 34,         /* Yxx, 00..03 */
};

typedef struct SaInfo {
    int loaded;            /* SA_LOADED when valid */
    const char* problem;   /* why sa_load_image rejected the image (static string), else NULL */
    int problem_offset;    /* image offset of the offending byte, or -1 */
    int variant;           /* SA_VARIANT_* */
    int channels;          /* C: 6 or 12 */
    int chips;             /* 1 or 2 */
    int orders;
    int loop;
    int multispeed;
    int speed;
    int instruments;
    uint16_t rows[SA_MAX_ORDERS];       /* rows of each order, 1..256 */
    uint8_t start_speed[SA_MAX_ORDERS]; /* ticks per row each order starts with in sequential straight play */
} SaInfo;

#define SA_LOADED 0x53414C44

typedef struct SaaImage {
    uint8_t bytes[SA_IMAGE_SIZE];
    SaInfo info;
} SaaImage;

/* One decoded pattern row of a channel. */
typedef struct SaRow {
    int note;        /* -1 none, 0..119, SA_NOTE_OFF */
    int instrument;  /* 0 none, 1..64 */
    int effects;     /* 0..4 */
    uint8_t code[SA_MAX_EFFECTS];
    uint8_t param[SA_MAX_EFFECTS];
} SaRow;

/* Validates image->bytes and fills image->info. Returns 0 or -1 (info.problem says why). */
int sa_load_image(SaaImage* image);

/* Pattern cursor of one channel: decodes rows sequentially from an offset. */
typedef struct SaCursor {
    int offset; /* data region offset of the next byte */
    int run;    /* empty rows left in the current run */
} SaCursor;

/* Decodes the next row at the cursor (no bounds checks: loaded images only). */
void sa_next_row(const uint8_t* bytes, SaCursor* cursor, SaRow* row);
/* Decodes row `row` of (order, channel) from the pattern start. */
void sa_read_row(const SaaImage* image, int order, int channel, int row, SaRow* out, SaCursor* after);
/* The data offset of (order, channel)'s pattern. */
static inline int sa_pattern(const SaaImage* image, int order, int channel) {
    const uint8_t* p = image->bytes + SA_OFF_ORDERS + 2 * (SA_MAX_CHANNELS * order + channel);
    return p[0] | p[1] << 8;
}

/* An envelope of a loaded image (no checks). */
typedef struct SaEnvelope {
    const uint8_t* values; /* L bytes */
    int length;            /* L, 1..256; 0 = the instrument has none */
    int loop;              /* -1 none */
    int release;           /* -1 none */
} SaEnvelope;

/* Envelope `which` (SA_I_VOLUME, SA_I_ARPEGGIO, SA_I_PITCH, SA_I_MIXER) of defined slot 1..64; length 0 = none. */
void sa_envelope(const SaaImage* image, int slot, int which, SaEnvelope* out);
/* The 16-byte record of slot 1..64. */
static inline const uint8_t* sa_instrument(const SaaImage* image, int slot) {
    return image->bytes + SA_OFF_INSTRUMENTS + SA_INSTRUMENT_SIZE * (slot - 1);
}
/* Instrument flags of slot 1..64 (0 = undefined). */
static inline int sa_instrument_flags(const SaaImage* image, int slot) {
    return slot >= 1 && slot <= SA_MAX_INSTRUMENTS ? sa_instrument(image, slot)[SA_I_FLAGS] : 0;
}

static inline int sa_u16(const uint8_t* p) { return p[0] | p[1] << 8; }

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_SAA_IMAGE_H_ */
