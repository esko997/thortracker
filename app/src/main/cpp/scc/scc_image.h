/* SCC song images: the bytes a lowering produces for the "msx-scc" and "msx-scc-plus" engines (Thor Tracker's own
 * player on a Konami K051649 / K052539, docs/AUDIO_ENGINE.md "SCC and SCC+"). One fixed-size, position-independent
 * image: a header, fixed tables (rows per order, the order table of pattern offsets, 64 instruments, 64 waves) and the
 * data region holding patterns and envelopes. Every reference is an offset into the region, so data may move freely
 * between images (live edits locate rows by (order, row) and instruments by slot, never by pointer).
 *
 * sc_load_image validates every byte the player can reach and derives ScInfo; images that fail are never played. All
 * multi-byte fields are little-endian. */
#ifndef THORTRACKER_SCC_IMAGE_H_
#define THORTRACKER_SCC_IMAGE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SC_IMAGE_SIZE 65536u
#define SC_CHANNELS 5
#define SC_MAX_ORDERS 256
#define SC_MAX_INSTRUMENTS 64
#define SC_MAX_WAVES 64
#define SC_WAVE_BYTES 32
#define SC_MAX_EFFECTS 4   /* effect columns per channel and row */
#define SC_MAX_MULTISPEED 8
#define SC_MAX_ENVELOPE 256 /* values per envelope */
#define SC_LAYOUT 1

/* Variants (header byte 5). */
#define SC_VARIANT_SCC 0  /* K051649: channels 4 and 5 share wave RAM 3 */
#define SC_VARIANT_PLUS 1 /* K052539 in SCC+ mode: five wave RAMs */

/* Header (64 bytes). */
#define SC_H_MAGIC 0        /* "TTSC" */
#define SC_H_LAYOUT 4       /* SC_LAYOUT */
#define SC_H_VARIANT 5      /* SC_VARIANT_* */
#define SC_H_MULTISPEED 6   /* N = 1..8: player ticks per 60 Hz frame */
#define SC_H_SPEED 7        /* ticks per row at the song start, 1..255 */
#define SC_H_ORDERS 8       /* u16 order count 1..256 */
#define SC_H_LOOP 10        /* u16 loop order < order count */
#define SC_H_INSTRUMENTS 12 /* highest instrument slot present, 0..64 */
#define SC_H_WAVES 13       /* wave count W, 1..64 */
#define SC_H_DATA_SIZE 14   /* u16 bytes of the data region used (<= SC_DATA_CAPACITY) */
#define SC_HEADER_SIZE 64   /* bytes 16..63 are 0 */

/* Fixed tables. */
#define SC_OFF_ROWS 64           /* 256 x u8: rows of order o (1..255; 0 = 256) */
#define SC_OFF_ORDERS 320        /* 256 x 5 x u16: pattern of (order o, channel c) at 320 + 2 (5 o + c), a data offset */
#define SC_OFF_INSTRUMENTS 2880  /* 64 x 16: slot n (1..64) at 2880 + 16 (n - 1) */
#define SC_INSTRUMENT_SIZE 16
#define SC_OFF_WAVES 3904        /* 64 x 32 signed bytes: wave k (1..64) at 3904 + 32 (k - 1); waves above W are 0 */
#define SC_OFF_DATA 5952         /* data region: patterns and envelopes */
#define SC_DATA_CAPACITY (SC_IMAGE_SIZE - SC_OFF_DATA) /* 59584 */

/* Instrument record (16 bytes; all zero = no instrument). */
#define SC_I_FLAGS 0     /* SC_FLAG_*; other bits 0 */
#define SC_I_VOLUME 2    /* u16 data offset of the volume envelope, SC_ENV_NONE = none (constant 15) */
#define SC_I_ARPEGGIO 4  /* u16 arpeggio envelope (s8 semitones), SC_ENV_NONE = none */
#define SC_I_PITCH 6     /* u16 pitch envelope (s8 1/16 semitones), SC_ENV_NONE = none */
#define SC_I_WAVE 8      /* u16 wave envelope (wave numbers 1..W), required */
#define SC_FLAG_DEFINED 0x80
#define SC_FLAG_RESTART 0x01        /* the waveform restarts at position 0 at each key-on */
#define SC_FLAG_PITCH_RELATIVE 0x02 /* the pitch envelope accumulates every tick */
#define SC_ENV_NONE 0xFFFFu

/* Envelope (in the data region): u16 length L 1..256, u16 loop 0..L-1 (SC_ENV_NONE none), u16 release 1..L-1
 * (SC_ENV_NONE none; volume envelopes only; loop < release when both), then L value bytes. */
#define SC_ENV_HEADER 6
#define SC_ENV_LENGTH 0
#define SC_ENV_LOOP 2
#define SC_ENV_RELEASE 4
#define SC_ARPEGGIO_LIMIT 64 /* arpeggio values -64..63 */
#define SC_PITCH_ENV_LIMIT 64 /* pitch envelope values -64..63 */

/* Pattern rows (the OPL3's encoding). A run byte 0x80 | k = k + 1 empty rows (k 0..127). Otherwise a mask byte
 * 0x01..0x13: bit 0 note byte follows, bit 1 instrument byte follows, bits 2-4 effect count 0..4; then the note
 * (0..95 = C-0..B-7, absolute; 0xFE = key off), the instrument slot (1..64), and per effect column in order the effect
 * code (0..35 = '0'..'9', 'A'..'Z') and its parameter. */
#define SC_RUN 0x80
#define SC_MAX_RUN 128
#define SC_MASK_NOTE 0x01
#define SC_MASK_INSTRUMENT 0x02
#define SC_MASK_EFFECT_SHIFT 2
#define SC_NOTE_OFF 0xFE
#define SC_NOTES 96

/* Effect codes ('0'..'9' = 0..9, 'A'..'Z' = 10..35). */
enum {
    SC_FX_ARPEGGIO = 0,      /* 0xy */
    SC_FX_SLIDE_UP = 1,      /* 1xx */
    SC_FX_SLIDE_DOWN = 2,    /* 2xx */
    SC_FX_PORTAMENTO = 3,    /* 3xx */
    SC_FX_VIBRATO = 4,       /* 4xy */
    SC_FX_VOLUME_SLIDE = 10, /* Axy */
    SC_FX_JUMP = 11,         /* Bxx */
    SC_FX_VOLUME = 12,       /* Cxx, 00..0F */
    SC_FX_BREAK = 13,        /* Dxx */
    SC_FX_SPEED = 15,        /* Fxx, 01..FF */
    SC_FX_DELAY = 16,        /* Gxx */
    SC_FX_FINE = 25,         /* Pxx */
    SC_FX_CUT = 28,          /* Sxx */
    SC_FX_TEST = 29,         /* Txx: the chip's test register */
    SC_FX_WAVE = 32,         /* Wxx, 01..W */
};

typedef struct ScInfo {
    int loaded;            /* SC_LOADED when valid */
    const char* problem;   /* why sc_load_image rejected the image (static string), else NULL */
    int problem_offset;    /* image offset of the offending byte, or -1 */
    int variant;           /* SC_VARIANT_* */
    int orders;
    int loop;
    int multispeed;
    int speed;
    int instruments;
    int waves;
    uint16_t rows[SC_MAX_ORDERS];        /* rows of each order, 1..256 */
    uint8_t start_speed[SC_MAX_ORDERS];  /* ticks per row each order starts with in sequential straight play */
} ScInfo;

#define SC_LOADED 0x53434C44

typedef struct SccImage {
    uint8_t bytes[SC_IMAGE_SIZE];
    ScInfo info;
} SccImage;

/* One decoded pattern row of a channel. */
typedef struct ScRow {
    int note;        /* -1 none, 0..95, SC_NOTE_OFF */
    int instrument;  /* 0 none, 1..64 */
    int effects;     /* 0..4 */
    uint8_t code[SC_MAX_EFFECTS];
    uint8_t param[SC_MAX_EFFECTS];
} ScRow;

/* Validates image->bytes and fills image->info. Returns 0 or -1 (info.problem says why). */
int sc_load_image(SccImage* image);

/* Pattern cursor of one channel: decodes rows sequentially from an offset. */
typedef struct ScCursor {
    int offset; /* data region offset of the next byte */
    int run;    /* empty rows left in the current run */
} ScCursor;

/* Decodes the next row at the cursor (no bounds checks: loaded images only). */
void sc_next_row(const uint8_t* bytes, ScCursor* cursor, ScRow* row);
/* Decodes row `row` of (order, channel) from the pattern start. */
void sc_read_row(const SccImage* image, int order, int channel, int row, ScRow* out, ScCursor* after);

/* An envelope of a loaded image (no checks). */
typedef struct ScEnvelope {
    const uint8_t* values; /* L bytes */
    int length;            /* L, 1..256; 0 = the instrument has none */
    int loop;              /* -1 none */
    int release;           /* -1 none */
} ScEnvelope;

/* Envelope `which` (SC_I_VOLUME, SC_I_ARPEGGIO, SC_I_PITCH, SC_I_WAVE) of defined slot 1..64; length 0 = none. */
void sc_envelope(const SccImage* image, int slot, int which, ScEnvelope* out);
/* Instrument flags of slot 1..64 (0 = undefined). */
static inline int sc_instrument_flags(const SccImage* image, int slot) {
    return slot >= 1 && slot <= SC_MAX_INSTRUMENTS ? image->bytes[SC_OFF_INSTRUMENTS + SC_INSTRUMENT_SIZE * (slot - 1)] : 0;
}
/* Wave k (1..64) bytes. */
static inline const int8_t* sc_wave(const SccImage* image, int k) {
    return (const int8_t*)(image->bytes + SC_OFF_WAVES + SC_WAVE_BYTES * (k - 1));
}

static inline int sc_u16(const uint8_t* p) { return p[0] | p[1] << 8; }

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_SCC_IMAGE_H_ */
