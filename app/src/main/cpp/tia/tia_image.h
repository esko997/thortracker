/* Atari 2600 song images: the bytes a lowering produces for the "atari2600-tia-ntsc" and "atari2600-tia-pal" engines
 * (Thor Tracker's own player on the TIA's two audio channels, docs/AUDIO_ENGINE.md "Atari 2600"). One fixed-size,
 * position-independent image: a header (with the region the song was lowered for), fixed tables (rows per order, the
 * order table of pattern offsets, 64 instruments) and the data region (patterns, then envelopes). Every pattern and
 * envelope reference is a u16 offset into the region, so data may move freely between images (live edits locate rows
 * by (order, row), never by pointer).
 *
 * ti_load_image validates every byte the player can reach and derives TiInfo; images that fail are never played.
 * All multi-byte fields are little-endian. tools/audio/prepare_tia.py reads the TI_* defines below (each value an
 * integer literal or arithmetic of earlier names) and the effect enum lines into TiaPlayerImage.kt. */
#ifndef THORTRACKER_TIA_IMAGE_H_
#define THORTRACKER_TIA_IMAGE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define TI_IMAGE_SIZE 65536
#define TI_LAYOUT 1
#define TI_CHANNELS 2 /* tracker channels = the TIA's audio channels 0 and 1 (TIA 1, TIA 2) */
#define TI_REGIONS 2
#define TI_REGION_NTSC 0
#define TI_REGION_PAL 1
#define TI_MAX_ORDERS 256
#define TI_MAX_ROWS 256
#define TI_MAX_INSTRUMENTS 64
#define TI_MAX_EFFECTS 2 /* effect columns per channel and row */
#define TI_MAX_ENVELOPE 255 /* envelope length limit */

/* Header (64 bytes). */
#define TI_H_MAGIC 0 /* "TTIA" */
#define TI_H_LAYOUT 4 /* TI_LAYOUT */
#define TI_H_REGION 5 /* TI_REGION_NTSC or TI_REGION_PAL: must be the loading engine's */
#define TI_H_SPEED 6 /* ticks per row at the song start, 1..255 */
#define TI_H_INSTRUMENTS 7 /* highest instrument slot present, 0..64 */
#define TI_H_ORDERS 8 /* u16 order count 1..256 */
#define TI_H_LOOP 10 /* u16 loop order < order count */
#define TI_H_DATA_SIZE 20 /* u32 bytes of the data region used (<= TI_DATA_CAPACITY) */
#define TI_HEADER_SIZE 64 /* bytes 12..19 and 24..63 are 0 */

/* Fixed tables. */
#define TI_OFF_ROWS 64 /* 256 x u8: rows of order o (1..255; 0 = 256); 0 past the order count */
#define TI_OFF_ORDERS 320 /* 256 x 2 x u16: pattern of (order o, channel c) at 320 + 2 (2 o + c) */
#define TI_OFF_INSTRUMENTS 1344 /* 64 x 32: slot n (1..64) at 1344 + 32 (n - 1) */
#define TI_INSTRUMENT_SIZE 32
#define TI_OFF_DATA 3392 /* data region: patterns, then envelopes */
#define TI_DATA_CAPACITY (TI_IMAGE_SIZE - TI_OFF_DATA)

/* Instrument record (32 bytes; an all-zero record = no instrument; records above the instrument count are zero). */
#define TI_I_FLAGS 0 /* TI_FLAG_DEFINED, optional TI_FLAG_RAW and TI_FLAG_RELATIVE; other bits 0 */
#define TI_I_WAVEFORM 1 /* AUDC 0..15 (bytes 2..7 are 0) */
#define TI_I_ENVELOPES 8 /* envelope k at 8 + 6 k: u16 data offset, u8 length L, u8 loop, u8 release, u8 0 */
#define TI_ENVELOPE_BYTES 6
#define TI_ENVELOPE_COUNT 4
#define TI_ENV_VOLUME 0 /* values 0..15 */
#define TI_ENV_ARPEGGIO 1 /* s8 -64..63 semitones */
#define TI_ENV_PITCH 2 /* s8 -128..127 in 1/64 semitones (absolute, or relative with TI_FLAG_RELATIVE) */
#define TI_ENV_WAVEFORM 3 /* AUDC 0..15 per tick */
#define TI_ENV_NONE 0xFF /* loop or release: none; an empty envelope has offset 0 and both 0xFF */
#define TI_FLAG_DEFINED 0x80
#define TI_FLAG_RAW 0x01 /* AUDF from the note number (31 - note mod 32) instead of the nearest-pitch divider */
#define TI_FLAG_RELATIVE 0x04 /* the pitch envelope accumulates */

/* Pattern rows. A run byte 0x80 | k = k + 1 empty rows (k 0..127). Otherwise a mask byte 0x01..0x0B:
 * bit 0 note byte follows, bit 1 instrument byte follows, bits 2-4 effect count 0..2, bits 5-6 zero; then the note
 * (TI_NOTE_MIN..TI_NOTE_MAX absolute, 57 = A-4; TI_NOTE_OFF), the instrument slot (1..64, defined), and per effect
 * column in order the effect code (0..35 = '0'..'9', 'A'..'Z') and its parameter. */
#define TI_NOTE_MIN 0 /* C-0 */
#define TI_NOTE_MAX 119 /* B-9 */
#define TI_NOTE_OFF 0xFE
#define TI_VOLUME_MAX 15 /* AUDV */
#define TI_WAVEFORM_MAX 15 /* AUDC */
#define TI_AUDF_MAX 31 /* AUDF */

/* The TIA's audio registers (write addresses). */
#define TI_REG_AUDC0 0x15
#define TI_REG_AUDC1 0x16
#define TI_REG_AUDF0 0x17
#define TI_REG_AUDF1 0x18
#define TI_REG_AUDV0 0x19
#define TI_REG_AUDV1 0x1A

/* Effect codes ('0'..'9' = 0..9, 'A'..'Z' = 10..35). Every other code is rejected by the loader. In order: 0xy
 * arpeggio, 1xx slide up, 2xx slide down, 3xx portamento, 4xy vibrato, Axy volume slide, Bxx jump, Cxx volume
 * 00..0F, Dxx break, Fxx speed 01..FF, Gxx note delay, Pxx fine pitch (80 = none), Sxx note off, Wxx waveform
 * 00..0F. */
enum {
    TI_FX_ARPEGGIO = 0,
    TI_FX_SLIDE_UP = 1,
    TI_FX_SLIDE_DOWN = 2,
    TI_FX_PORTAMENTO = 3,
    TI_FX_VIBRATO = 4,
    TI_FX_VOLUME_SLIDE = 10,
    TI_FX_JUMP = 11,
    TI_FX_VOLUME = 12,
    TI_FX_BREAK = 13,
    TI_FX_SPEED = 15,
    TI_FX_DELAY = 16,
    TI_FX_FINE = 25,
    TI_FX_CUT = 28,
    TI_FX_WAVEFORM = 32,
};

typedef struct TiInfo {
    int loaded;            /* TI_LOADED when valid */
    const char* problem;   /* why ti_load_image rejected the image (static string), else NULL */
    int problem_offset;    /* image offset of the offending byte (a field's first byte), or -1 */
    int region;            /* TI_REGION_NTSC or TI_REGION_PAL (the header's, = the loading engine's) */
    int orders;            /* 1..256 */
    int loop;              /* loop order */
    int speed;             /* ticks per row at the song start */
    int instruments;       /* highest slot present, 0..64 */
    uint32_t data_size;    /* bytes of the data region used */
    uint16_t rows[TI_MAX_ORDERS];        /* rows of each order, 1..256 (0 past the order count) */
    uint8_t start_speed[TI_MAX_ORDERS];  /* ticks per row each order starts with in sequential straight play */
} TiInfo;

#define TI_LOADED 0x5449414C

typedef struct TiaImage {
    uint8_t bytes[TI_IMAGE_SIZE];
    TiInfo info;
} TiaImage;

/* One decoded pattern row of a channel. */
typedef struct TiRow {
    int note;        /* -1 none, TI_NOTE_MIN..TI_NOTE_MAX, TI_NOTE_OFF */
    int instrument;  /* 0 none, 1..64 */
    int effects;     /* 0..2 */
    uint8_t code[TI_MAX_EFFECTS];
    uint8_t param[TI_MAX_EFFECTS];
} TiRow;

/* Validates image->bytes for an engine of `region` (TI_REGION_*) and fills image->info. Returns 0 or -1
 * (info.problem says why, info.problem_offset where). A pure function of the bytes and the region. */
int ti_load_image(TiaImage* image, int region);

/* Pattern cursor of one channel: decodes rows sequentially from an offset. */
typedef struct TiCursor {
    int offset; /* data region offset of the next byte */
    int run;    /* empty rows left in the current run */
} TiCursor;

/* Decodes the next row at the cursor (no bounds checks: loaded images only). */
void ti_next_row(const uint8_t* bytes, TiCursor* cursor, TiRow* row);
/* The cursor at the start of (order, channel)'s pattern. */
void ti_pattern_cursor(const TiaImage* image, int order, int channel, TiCursor* cursor);
/* Decodes row `row` of (order, channel) from the pattern start; `after` (may be NULL) receives the cursor past it. */
void ti_read_row(const TiaImage* image, int order, int channel, int row, TiRow* out, TiCursor* after);

static inline int ti_u16(const uint8_t* p) { return p[0] | p[1] << 8; }
static inline uint32_t ti_u32(const uint8_t* p) {
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_TIA_IMAGE_H_ */
