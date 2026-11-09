/* PC Engine song images: the bytes a lowering produces for the "pce-huc6280" engine (Thor Tracker's own player on
 * the HuC6280's PSG, docs/AUDIO_ENGINE.md "PC Engine"). One fixed-size, position-independent image: a header (with
 * the chip's shared master balance, LFO and DDA timer state at the song start), fixed tables (rows per order, the order
 * table of pattern offsets, 64 instruments, 64 waves, 64 sample entries) and the data region (patterns, envelopes,
 * samples). Every pattern and envelope reference is a u16 offset and every sample a u32 offset into the region, so data
 * may move freely between images (live edits locate rows by (order, row), never by pointer).
 *
 * pc_load_image validates every byte the player can reach and derives PcInfo; images that fail are never played.
 * All multi-byte fields are little-endian. */
#ifndef THORTRACKER_PCE_IMAGE_H_
#define THORTRACKER_PCE_IMAGE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define PC_IMAGE_SIZE 131072u
#define PC_CHANNELS 6           /* tracker channels = PSG channels 0-5 (PSG 1..PSG 6) */
#define PC_NOISE_FIRST 4        /* noise only on channels >= 4 (PSG 5, PSG 6) */
#define PC_MAX_ORDERS 256
#define PC_MAX_INSTRUMENTS 64
#define PC_MAX_WAVES 64
#define PC_MAX_SAMPLES 64
#define PC_MAX_EFFECTS 4        /* effect columns per channel and row */
#define PC_MAX_MULTISPEED 8
#define PC_MAX_TIMER 127        /* DDA timer reload R: 6991.3 / (R + 1) Hz */
#define PC_LAYOUT 1
#define PC_WAVE_SIZE 32         /* samples per wave */
#define PC_WAVE_MAX 31          /* largest wave sample and sample frame value (5 bits) */
#define PC_MAX_ENVELOPE 255     /* envelope length limit */
#define PC_MAX_SAMPLE_FRAMES 65535 /* sample length limit (loop starts are u16) */

/* Header (64 bytes). */
#define PC_H_MAGIC 0            /* "TTPC" */
#define PC_H_LAYOUT 4           /* PC_LAYOUT */
#define PC_H_MULTISPEED 5       /* N = 1..8: player ticks per 60 Hz frame */
#define PC_H_SPEED 6            /* ticks per row at the song start, 1..255 */
#define PC_H_INSTRUMENTS 7      /* highest instrument slot present, 0..64 */
#define PC_H_ORDERS 8           /* u16 order count 1..256 */
#define PC_H_LOOP 10            /* u16 loop order < order count */
#define PC_H_WAVES 12           /* wave count W, 0..64 */
#define PC_H_SAMPLES 13         /* sample count S, 0..64 */
#define PC_H_MASTER 14          /* R1 master balance at the start: left << 4 | right */
#define PC_H_LFO_FREQ 15        /* R8 LFO frequency at the start, 0..255 (0 = 256) */
#define PC_H_LFO_CTRL 16        /* R9 LFO control at the start: 0..3 or $80..$83 */
#define PC_H_TIMER 17           /* DDA timer reload R at the start, 0..127 */
#define PC_H_DATA_SIZE 20       /* u32 bytes of the data region used (<= PC_DATA_CAPACITY) */
#define PC_HEADER_SIZE 64       /* bytes 18-19 and 24..63 are 0 */

/* Fixed tables. */
#define PC_OFF_ROWS 64          /* 256 x u8: rows of order o (1..255; 0 = 256) */
#define PC_OFF_ORDERS 320       /* 256 x 6 x u16: pattern of (order o, channel c) at 320 + 2 (6 o + c), an offset into the data region */
#define PC_OFF_INSTRUMENTS 3392 /* 64 x 32: slot n (1..64) at 3392 + 32 (n - 1) */
#define PC_INSTRUMENT_SIZE 32
#define PC_OFF_WAVES 5440       /* 64 x 32: wave w at 5440 + 32 w, samples 0..31 in play order; waves >= W all zero */
#define PC_OFF_SAMPLES 7488     /* 64 x 8: sample s at 7488 + 8 s: u32 data offset, u32 length in frames (>= 1); entries >= S zero */
#define PC_SAMPLE_ENTRY_SIZE 8
#define PC_OFF_DATA 8000        /* data region: patterns, envelopes, sample data (one 5-bit frame per byte) */
#define PC_DATA_CAPACITY 123072 /* PC_IMAGE_SIZE - PC_OFF_DATA */

/* Instrument record (32 bytes; an all-zero record = no instrument; records above the instrument count are zero). */
#define PC_I_FLAGS 0            /* bit 7 = defined, bits 0-1 kind (PC_KIND_*), bit 2 = pitch envelope relative; others 0 */
#define PC_I_WAVE 1             /* tone: wave 0..W - 1; other kinds 0 */
#define PC_I_SAMPLE 2           /* sample: sample index 0..S - 1; other kinds 0 */
#define PC_I_ROOT 3             /* sample: root note 9..95 (plays one frame per timer interrupt); other kinds 0 */
#define PC_I_LOOP 4             /* sample: 1 = loop, 0 = one-shot; other kinds 0 (byte 5 is 0) */
#define PC_I_LOOP_START 6       /* u16 sample loop start frame (< the sample's length; 0 without loop or for other kinds) */
#define PC_I_ENVELOPES 8        /* envelope k (PC_ENV_*) at 8 + 6 k: u16 data offset, u8 length L (0 = none: offset 0,
                                   loop and release PC_ENV_NONE), u8 loop (PC_ENV_NONE or < L), u8 release (PC_ENV_NONE or
                                   1..L - 1, > loop when both), u8 0 */
#define PC_ENVELOPE_BYTES 6
#define PC_ENVELOPE_COUNT 4
#define PC_ENV_VOLUME 0         /* values 0..31 */
#define PC_ENV_ARPEGGIO 1       /* s8 -64..63 semitones */
#define PC_ENV_PITCH 2          /* s8 -128..127, 1/64 semitones (absolute, or relative with PC_FLAG_RELATIVE) */
#define PC_ENV_WAVE 3           /* wave 0..W - 1 per tick; tone instruments only */
#define PC_ENV_NONE 0xFF
#define PC_FLAG_DEFINED 0x80
#define PC_FLAG_RELATIVE 0x04
#define PC_KIND_MASK 0x03
#define PC_KIND_TONE 0
#define PC_KIND_NOISE 1         /* channels >= PC_NOISE_FIRST only */
#define PC_KIND_SAMPLE 2

/* Pattern rows. A run byte 0x80 | k = k + 1 empty rows (k 0..127). Otherwise a mask byte 0x01..0x13:
 * bit 0 note byte follows, bit 1 instrument byte follows, bits 2-4 effect count 0..4; then the note
 * (PC_NOTE_MIN..PC_NOTE_MAX absolute; PC_NOTE_OFF), the instrument slot (1..64), and per effect column in order the
 * effect code (0..35 = '0'..'9', 'A'..'Z') and its parameter. */
#define PC_NOTE_OFF 0xFE
#define PC_NOTE_MIN 9           /* A-0: the lowest note whose period fits 12 bits */
#define PC_NOTE_MAX 95          /* B-7 */

/* Effect codes ('0'..'9' = 0..9, 'A'..'Z' = 10..35). Every other code is rejected by the loader. */
enum {
    PC_FX_ARPEGGIO = 0,      /* 0xy */
    PC_FX_SLIDE_UP = 1,      /* 1xx */
    PC_FX_SLIDE_DOWN = 2,    /* 2xx */
    PC_FX_PORTAMENTO = 3,    /* 3xx */
    PC_FX_VIBRATO = 4,       /* 4xy */
    PC_FX_PAN = 8,           /* 8xy: channel balance R5 */
    PC_FX_VOLUME_SLIDE = 10, /* Axy */
    PC_FX_JUMP = 11,         /* Bxx */
    PC_FX_VOLUME = 12,       /* Cxx 00..1F */
    PC_FX_BREAK = 13,        /* Dxx */
    PC_FX_SPEED = 15,        /* Fxx */
    PC_FX_DELAY = 16,        /* Gxx */
    PC_FX_LFO_FREQ = 21,     /* Lxx: shared R8 */
    PC_FX_LFO_CTRL = 22,     /* Mxx: shared R9 (00..03, 80..83) */
    PC_FX_FINE = 25,         /* Pxx */
    PC_FX_CUT = 28,          /* Sxx */
    PC_FX_TIMER = 29,        /* Txx: shared DDA timer reload 00..7F */
    PC_FX_WAVE = 32,         /* Wxx: tone wave override */
    PC_FX_MASTER = 33,       /* Xxy: shared R1 */
};

typedef struct PcInfo {
    int loaded;            /* PC_LOADED when valid */
    const char* problem;   /* why pc_load_image rejected the image (static string), else NULL */
    int problem_offset;    /* image offset of the offending byte, or -1 */
    int orders;
    int loop;
    int multispeed;
    int speed;
    int instruments;
    int waves;
    int samples;
    uint16_t rows[PC_MAX_ORDERS];        /* rows of each order, 1..256 */
    uint8_t start_speed[PC_MAX_ORDERS];  /* ticks per row each order starts with in sequential straight play */
} PcInfo;

#define PC_LOADED 0x5043454C

typedef struct PceImage {
    uint8_t bytes[PC_IMAGE_SIZE];
    PcInfo info;
} PceImage;

/* One decoded pattern row of a channel. */
typedef struct PcRow {
    int note;        /* -1 none, PC_NOTE_MIN..PC_NOTE_MAX, PC_NOTE_OFF */
    int instrument;  /* 0 none, 1..64 */
    int effects;     /* 0..4 */
    uint8_t code[PC_MAX_EFFECTS];
    uint8_t param[PC_MAX_EFFECTS];
} PcRow;

/* Validates image->bytes and fills image->info. Returns 0 or -1 (info.problem says why). */
int pc_load_image(PceImage* image);

/* Pattern cursor of one channel: decodes rows sequentially from an offset. */
typedef struct PcCursor {
    int offset; /* data region offset of the next byte */
    int run;    /* empty rows left in the current run */
} PcCursor;

/* Decodes the next row at the cursor (no bounds checks: loaded images only). */
void pc_next_row(const uint8_t* bytes, PcCursor* cursor, PcRow* row);
/* Decodes row `row` of (order, channel) from the pattern start; `after` (may be NULL) receives the cursor past it. */
void pc_read_row(const PceImage* image, int order, int channel, int row, PcRow* out, PcCursor* after);

static inline int pc_u16(const uint8_t* p) { return p[0] | p[1] << 8; }
static inline uint32_t pc_u32(const uint8_t* p) {
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_PCE_IMAGE_H_ */
