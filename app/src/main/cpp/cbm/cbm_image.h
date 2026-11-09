/* VIC-20 / C16-Plus/4 song images: the bytes a lowering produces for the "vic20-vic" and "c16-ted" engines (Thor
 * Tracker's own player on the VIC's or the TED's sound part, docs/AUDIO_ENGINE.md "VIC-20 and C16/Plus/4"). One
 * fixed-size, position-independent layout for both chips (header byte 5 = chip): a header (with the chip's shared
 * master volume and DAC timer at the song start), fixed tables (rows per order, the order table of pattern offsets, 64
 * instruments, 64 sample entries) and the data region (patterns, envelopes, sample frames). Every pattern and envelope
 * reference is a u16 offset and every sample a u32 offset into the region, so data may move freely between images (live
 * edits locate rows by (order, row), never by pointer).
 *
 * cb_load_image validates every byte the player can reach and derives CbInfo; images that fail are never played.
 * All multi-byte fields are little-endian. Kotlin's backend/CbmImage.kt mirrors every CB_* name without the prefix
 * (tools/audio/prepare_cbm.py checks the values). */
#ifndef THORTRACKER_CBM_IMAGE_H_
#define THORTRACKER_CBM_IMAGE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CB_MAGIC "TTCB"
#define CB_IMAGE_SIZE 131072
#define CB_HEADER_SIZE 64
#define CB_LAYOUT 1
#define CB_CHIP_VIC 0
#define CB_CHIP_TED 1
#define CB_VIDEO_PAL 0
#define CB_VIDEO_NTSC 1
#define CB_MAX_MULTISPEED 4
#define CB_MAX_CHANNELS 4       /* order-table columns */
#define CB_VIC_CHANNELS 4       /* Bass, Alto, Soprano, Noise */
#define CB_TED_CHANNELS 3       /* Voice 1, Voice 2, DAC */
#define CB_MAX_ORDERS 256
#define CB_MAX_INSTRUMENTS 64
#define CB_MAX_SAMPLES 64
#define CB_MAX_EFFECTS 2        /* effect columns per channel and row */
#define CB_MAX_ENVELOPE 255     /* envelope length limit */
#define CB_MAX_SAMPLE_FRAMES 65535
#define CB_VIC_MAX_VOLUME 15
#define CB_TED_MAX_VOLUME 8
#define CB_DAC_MAX 8            /* largest sample frame value (a TED volume level) */
#define CB_VIC_NOISE_CHANNEL 3
#define CB_TED_NOISE_CHANNEL 1  /* the only TED lane that can play noise */
#define CB_TED_DAC_CHANNEL 2

/* Variant index of the player's period tables (cbm_tables.c): chip * 2 + video. */
#define CB_VARIANT_VIC_PAL 0
#define CB_VARIANT_VIC_NTSC 1
#define CB_VARIANT_TED_PAL 2
#define CB_VARIANT_TED_NTSC 3

/* Header (64 bytes). */
#define CB_H_MAGIC 0            /* "TTCB" */
#define CB_H_LAYOUT 4           /* CB_LAYOUT */
#define CB_H_CHIP 5             /* CB_CHIP_*: must match the ops */
#define CB_H_VIDEO 6            /* CB_VIDEO_* */
#define CB_H_MULTISPEED 7       /* N = 1..4: player ticks per video frame */
#define CB_H_SPEED 8            /* ticks per row at the song start, 1..255 */
#define CB_H_INSTRUMENTS 9      /* highest instrument slot present, 0..64 */
#define CB_H_ORDERS 10          /* u16 order count 1..256 */
#define CB_H_LOOP 12            /* u16 loop order < order count */
#define CB_H_SAMPLES 14         /* sample count S, 0..64 (VIC: 0) */
#define CB_H_VOLUME 15          /* master volume M at the start: VIC 0..15, TED 0..8 */
#define CB_H_TIMER 16           /* DAC timer T at the start, 0..255 (VIC: 0) */
#define CB_H_DATA_SIZE 20       /* u32 bytes of the data region used (<= CB_DATA_CAPACITY) */
/* bytes 17..19 and 24..63 are 0 */

/* Fixed tables. */
#define CB_OFF_ROWS 64          /* 256 x u8: rows of order o (1..255; 0 = 256) */
#define CB_OFF_ORDERS 320       /* 256 x 4 x u16: pattern of (order o, channel c) at 320 + 2 (4 o + c) */
#define CB_OFF_INSTRUMENTS 2368 /* 64 x 32: slot n (1..64) at 2368 + 32 (n - 1) */
#define CB_INSTRUMENT_SIZE 32
#define CB_OFF_SAMPLES 4416     /* 64 x 8: sample s at 4416 + 8 s: u32 data offset, u32 length in frames */
#define CB_SAMPLE_ENTRY_SIZE 8
#define CB_OFF_RESERVED 4928    /* 192 bytes, 0 */
#define CB_OFF_DATA 5120        /* data region: patterns, envelopes, sample frames (0..8, one per byte) */
#define CB_DATA_CAPACITY 125952 /* CB_IMAGE_SIZE - CB_OFF_DATA */

/* Instrument record (32 bytes; an all-zero record = no instrument; records above the instrument count are zero). */
#define CB_I_FLAGS 0            /* bit 7 defined, bits 0-1 kind (CB_KIND_*), bit 2 pitch envelope relative; others 0 */
#define CB_I_WAVE 1             /* tone: wave without a wave envelope, CB_WAVE_SQUARE (VIC: always) or CB_WAVE_NOISE (TED); sample: 0 */
#define CB_I_SAMPLE 2           /* sample: index 0..S - 1; tone 0 */
#define CB_I_ROOT 3             /* sample: root note CB_ROOT_MIN..CB_ROOT_MAX (one frame per timer interrupt); tone 0 */
#define CB_I_LOOP 4             /* sample: 1 loop, 0 one-shot; tone 0 (byte 5 is 0) */
#define CB_I_LOOP_START 6       /* u16 loop start frame (< the sample's length; 0 without loop or for tones) */
#define CB_I_ENVELOPES 8        /* envelope k at 8 + 6 k: u16 data offset, u8 length L (0 = none: offset 0, loop and
                                   release CB_ENV_NONE), u8 loop (CB_ENV_NONE or < L), u8 release (CB_ENV_NONE or 1..L - 1,
                                   > loop when both), u8 0 */
#define CB_ENVELOPE_BYTES 6
#define CB_ENVELOPE_COUNT 4
#define CB_ENV_VOLUME 0         /* master volume shape 0..15 (VIC) / 0..8 (TED); tone instruments only */
#define CB_ENV_ARPEGGIO 1       /* s8 -64..63 semitones */
#define CB_ENV_PITCH 2          /* s8 -128..127 in 1/64 semitone (absolute, or relative with CB_FLAG_RELATIVE) */
#define CB_ENV_WAVE 3           /* per tick 0 off, 1 square, 2 noise (TED only); tone instruments only */
#define CB_ENV_NONE 0xFF
#define CB_FLAG_DEFINED 0x80
#define CB_FLAG_RELATIVE 0x04
#define CB_KIND_MASK 0x03
#define CB_KIND_TONE 0
#define CB_KIND_SAMPLE 1        /* TED only, DAC lane only */
#define CB_WAVE_OFF 0
#define CB_WAVE_SQUARE 1
#define CB_WAVE_NOISE 2         /* TED voice 2 only */
#define CB_ROOT_MIN 24
#define CB_ROOT_MAX 108

/* Pattern rows. A run byte CB_RUN | k = k + 1 empty rows (k 0..127). Otherwise a mask byte 0x01..0x0B: bit 0 note
 * byte follows, bit 1 instrument byte follows, bits 2-3 effect count 0..2, bits 4-6 zero; then the note (inside the
 * lane's range, or CB_NOTE_OFF), the instrument slot (1..64), and per effect column in order the effect code (0..35 =
 * '0'..'9', 'A'..'Z') and its parameter. */
#define CB_RUN 0x80
#define CB_MAX_RUN 128
#define CB_MASK_NOTE 1
#define CB_MASK_INSTRUMENT 2
#define CB_MASK_EFFECT_SHIFT 2
#define CB_NOTE_OFF 0xFE
#define CB_NOISE_RATE_MAX 127   /* VIC noise lane: notes are rate indices 0..127 */
#define CB_FINE_NONE 0x80       /* Pxx: xx - 80 in 1/64 semitone */

/* Effect codes ('0'..'9' = 0..9, 'A'..'Z' = 10..35). Every other code is rejected by the loader. */
enum {
    CB_FX_ARPEGGIO = 0,      /* 0xy */
    CB_FX_SLIDE_UP = 1,      /* 1xx */
    CB_FX_SLIDE_DOWN = 2,    /* 2xx */
    CB_FX_PORTAMENTO = 3,    /* 3xx */
    CB_FX_VIBRATO = 4,       /* 4xy */
    CB_FX_VOLUME_SLIDE = 10, /* Axy: shared master volume slide */
    CB_FX_JUMP = 11,         /* Bxx */
    CB_FX_BREAK = 13,        /* Dxx */
    CB_FX_SPEED = 15,        /* Fxx 01..FF */
    CB_FX_DELAY = 16,        /* Gxx */
    CB_FX_FINE = 25,         /* Pxx */
    CB_FX_CUT = 28,          /* Sxx */
    CB_FX_TIMER = 29,        /* Txx: shared DAC timer (TED only) */
    CB_FX_VOLUME = 31,       /* Vxx: shared master volume, <= the chip's MAX */
    CB_FX_WAVE = 32,         /* Wxx 00..02: wave override (TED voice lanes; 02 voice 2 only) */
};

/* Lowest and highest note byte of each lane: [chip][video][channel][0 = min, 1 = max] (absolute notes, 0 = C-0;
 * the TED's ranges do not depend on the video standard; TED channel 3 does not exist: 0, 0). */
extern const uint8_t CB_LANE_RANGE[2][2][CB_MAX_CHANNELS][2];

typedef struct CbInfo {
    int loaded;            /* CB_LOADED when valid */
    const char* problem;   /* why cb_load_image rejected the image (static string), else NULL */
    int problem_offset;    /* image offset of the offending byte, or -1 */
    int chip, video, multispeed;
    int channels;          /* CB_VIC_CHANNELS or CB_TED_CHANNELS */
    int orders;
    int loop;
    int speed;
    int instruments;
    int samples;
    uint16_t rows[CB_MAX_ORDERS];        /* rows of each order, 1..256 */
    uint8_t start_speed[CB_MAX_ORDERS];  /* ticks per row each order starts with in sequential straight play */
} CbInfo;

#define CB_LOADED 0x4342494D

typedef struct CbmImage {
    uint8_t bytes[CB_IMAGE_SIZE];
    CbInfo info;
} CbmImage;

/* One decoded pattern row of a channel. */
typedef struct CbRow {
    int note;        /* -1 none, a note byte, CB_NOTE_OFF */
    int instrument;  /* 0 none, 1..64 */
    int effects;     /* 0..2 */
    uint8_t code[CB_MAX_EFFECTS];
    uint8_t param[CB_MAX_EFFECTS];
} CbRow;

/* Validates image->bytes for `chip` (CB_CHIP_*: the ops' chip) and fills image->info. Returns 0 or -1 (info.problem
 * says why, info.problem_offset at which byte). */
int cb_load_image(CbmImage* image, int chip);

/* The lane's note range for a chip and video standard; 0 or -1 for a channel the chip does not have. */
int cb_lane_range(int chip, int video, int channel, int* lo, int* hi);

/* The instrument record of `slot` if it is a defined slot of the (loaded) image allowed on `channel` by the placement
 * rules (tone instruments on the voice lanes, noise-capable ones only on TED voice 2, sample instruments only on the
 * TED DAC lane), else NULL. */
const uint8_t* cb_allowed(const CbmImage* image, int slot, int channel);

/* Pattern cursor of one channel: decodes rows sequentially from an offset. */
typedef struct CbCursor {
    int offset; /* data region offset of the next byte */
    int run;    /* empty rows left in the current run */
} CbCursor;

/* Decodes the next row at the cursor (no bounds checks: loaded images only). */
void cb_next_row(const uint8_t* bytes, CbCursor* cursor, CbRow* row);
/* The cursor at the start of (order, channel)'s pattern. */
void cb_cursor(const CbmImage* image, int order, int channel, CbCursor* cursor);
/* Decodes row `row` of (order, channel) from the pattern start; `after` (may be NULL) receives the cursor past it. */
void cb_read_row(const CbmImage* image, int order, int channel, int row, CbRow* out, CbCursor* after);

static inline int cb_u16(const uint8_t* p) { return p[0] | p[1] << 8; }
static inline uint32_t cb_u32(const uint8_t* p) {
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_CBM_IMAGE_H_ */
