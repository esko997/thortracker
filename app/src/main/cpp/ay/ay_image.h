/* AY-3-8910 / YM2149 song images: the bytes a lowering produces for the "ay-zx128", "ay-msx", "ay-atari-st" and "ay-cpc"
 * engines (Thor Tracker's own player on its own AY/YM core; docs/AUDIO_ENGINE.md "AY-3-8910 / YM2149"). One
 * fixed-size, position-independent image: a header, fixed tables (rows per order, the order table of pattern
 * offsets, 64 instruments) and the data region (patterns and envelopes). Every pattern and envelope reference is an
 * offset into the region, so data may move freely between images (live edits locate rows by (order, row) and copy
 * envelopes at key-on, never keeping a pointer).
 *
 * ay_load_image validates every byte the player can reach and derives AyInfo; images that fail are never played.
 * All multi-byte fields are little-endian. tools/audio/prepare_ay.py parses every `#define AY_*` here (and in
 * ay_player.h, ay_machine.h) into backend/AyPlayerImage.kt and the reasons of ay_image.c into LOADER_PROBLEMS. */
#ifndef THORTRACKER_AY_IMAGE_H_
#define THORTRACKER_AY_IMAGE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define AY_IMAGE_SIZE 65536u
#define AY_MAGIC "TTAY"
#define AY_LAYOUT 1

/* Variants (image byte 5): the stock computers. Each engine accepts only its own. */
#define AY_VARIANT_ZX128 0    /* ZX Spectrum 128, GI AY-3-8912 */
#define AY_VARIANT_MSX 1      /* MSX, GI AY-3-8910 */
#define AY_VARIANT_ATARI_ST 2 /* Atari ST, Yamaha YM2149F */
#define AY_VARIANT_CPC 3      /* Amstrad CPC, GI AY-3-8912 */
#define AY_VARIANTS 4

#define AY_ENGINE_ID_ZX128 "ay-zx128"
#define AY_ENGINE_ID_MSX "ay-msx"
#define AY_ENGINE_ID_ATARI_ST "ay-atari-st"
#define AY_ENGINE_ID_CPC "ay-cpc"

/* Timing as exact ratios of the master clock M: chip clock = M / AY_DIV_*, one tick (the frame interrupt) =
 * AY_FRAME_DIV_* master clocks, one chip step (the generators' clock, chip clock / 8) = 8 * AY_DIV_* master clocks. */
#define AY_MASTER_HZ_ZX128 3546900u     /* Z80 clock */
#define AY_MASTER_HZ_MSX 10738635u      /* VDP crystal; Z80 = M / 3 */
#define AY_MASTER_HZ_ATARI_ST 32084988u /* PAL ST master clock */
#define AY_MASTER_HZ_CPC 16000000u      /* Gate Array crystal; Z80 = M / 4 */
#define AY_DIV_ZX128 2u                 /* 1773450 Hz */
#define AY_DIV_MSX 6u                   /* 1789772.5 Hz */
#define AY_DIV_ATARI_ST 16u             /* 2005311.75 Hz */
#define AY_DIV_CPC 16u                  /* 1000000 Hz */
#define AY_FRAME_DIV_ZX128 70908u       /* T-states per frame: 50.0212 Hz */
#define AY_FRAME_DIV_MSX 179208u        /* 2 * 342 * 262: one NTSC TMS9918A frame, 59.9227 Hz */
#define AY_FRAME_DIV_ATARI_ST 641024u   /* 4 * 160256: 50.0527 Hz */
#define AY_FRAME_DIV_CPC 319488u        /* 16 * 19968 us: 312 lines of 64 us, 50.0801 Hz */
/* R7 bits 7-6 (I/O port directions) every R7 write carries. */
#define AY_IO_BITS_ZX128 0xC0
#define AY_IO_BITS_MSX 0x80
#define AY_IO_BITS_ATARI_ST 0xC0
#define AY_IO_BITS_CPC 0x00
/* Output wiring: 0 = one mono output (both host channels); 1 = the CPC's stereo socket, left = A + B, right = B + C. */
#define AY_STEREO_ZX128 0
#define AY_STEREO_MSX 0
#define AY_STEREO_ATARI_ST 0
#define AY_STEREO_CPC 1
/* VGM header: 0x78 chip type, 0x74 clock (integer Hz), 0x24 rate. */
#define AY_VGM_TYPE_ZX128 0x01
#define AY_VGM_TYPE_MSX 0x00
#define AY_VGM_TYPE_ATARI_ST 0x10
#define AY_VGM_TYPE_CPC 0x01
#define AY_VGM_CLOCK_ZX128 1773450u
#define AY_VGM_CLOCK_MSX 1789772u
#define AY_VGM_CLOCK_ATARI_ST 2005312u
#define AY_VGM_CLOCK_CPC 1000000u
#define AY_VGM_RATE_ZX128 50u
#define AY_VGM_RATE_MSX 60u
#define AY_VGM_RATE_ATARI_ST 50u
#define AY_VGM_RATE_CPC 50u
/* GD3 tag of the VGM export. */
#define AY_GD3_SYSTEM_ZX128 "ZX Spectrum 128 (Thor Tracker)"
#define AY_GD3_SYSTEM_MSX "MSX (Thor Tracker)"
#define AY_GD3_SYSTEM_ATARI_ST "Atari ST (Thor Tracker)"
#define AY_GD3_SYSTEM_CPC "Amstrad CPC (Thor Tracker)"
#define AY_GD3_CONVERTER "Thor Tracker"

/* Header (64 bytes). */
#define AY_H_MAGIC 0        /* "TTAY" */
#define AY_H_LAYOUT 4       /* AY_LAYOUT */
#define AY_H_VARIANT 5      /* AY_VARIANT_* */
#define AY_H_SPEED 6        /* ticks per row at the song start, 1..255 */
#define AY_H_ORDERS 8       /* u16 order count 1..256 */
#define AY_H_LOOP 10        /* u16 loop order < order count */
#define AY_H_INSTRUMENTS 12 /* highest instrument slot present, 0..64 */
#define AY_H_DATA_SIZE 14   /* u16 bytes of pattern and envelope data used (<= AY_DATA_CAPACITY) */
#define AY_HEADER_SIZE 64   /* bytes 7, 13 and 16..63 are 0 */

/* Fixed tables. */
#define AY_OFF_ROWS 64          /* 256 x u8: rows of order o (1..255; 0 = 256) */
#define AY_OFF_ORDERS 320       /* 256 x 3 x u16: pattern of (order o, channel c) at 320 + 2 (3 o + c) */
#define AY_OFF_RESERVED 1856    /* 192 bytes 0 */
#define AY_OFF_INSTRUMENTS 2048 /* 64 x 32: slot n (1..64) at 2048 + 32 (n - 1) */
#define AY_INSTRUMENT_SIZE 32
#define AY_OFF_DATA 4096        /* data region: patterns and envelopes */
#define AY_DATA_CAPACITY 61440  /* AY_IMAGE_SIZE - AY_OFF_DATA */

/* Instrument record (32 bytes; all zero = no instrument). */
#define AY_I_FLAGS 0
#define AY_FLAG_DEFINED 0x80
#define AY_FLAG_PITCH_RELATIVE 0x01 /* the pitch envelope accumulates every tick */
#define AY_FLAG_AUTO_PERIOD 0x02    /* the envelope period follows the note (else the manual period) */
#define AY_FLAG_SYNC 0x04           /* tone period derived from the envelope period (auto only) */
#define AY_I_SHAPE 1        /* 0 = fixed volume, 8..15 = the hardware envelope shape written to R13 */
#define AY_I_OCTAVE 2       /* s8 auto octave offset J, AY_OCTAVE_MIN..AY_OCTAVE_MAX (auto only; else 0) */
#define AY_I_PERIOD 4       /* u16 manual envelope period (manual only; else 0) */
#define AY_I_ENV_VOLUME 6   /* u16 data offsets of the envelopes, AY_NO_ENVELOPE = none: volume 0..15 (none: 15), */
#define AY_I_ENV_ARPEGGIO 8 /* arpeggio -64..63 semitones (none: 0), */
#define AY_I_ENV_PITCH 10   /* pitch -64..63 x AY_PITCH_UNIT (none: 0), */
#define AY_I_ENV_MIXER 12   /* mixer 0..3: bit 0 tone, bit 1 noise (none: 1), */
#define AY_I_ENV_NOISE 14   /* noise period 0..31 (none: the note does not set R6) */
#define AY_I_SWEEP_START 16 /* s8 semitones relative to the note, -AY_SWEEP_LIMIT..AY_SWEEP_LIMIT */
#define AY_I_SWEEP_END 17   /* s8 semitones */
#define AY_I_SWEEP_SPEED 18 /* u8 1/16 semitone per tick toward the end; 0 = no sweep */
#define AY_SWEEP_LIMIT 96
#define AY_OCTAVE_MIN (-8)
#define AY_OCTAVE_MAX 7

/* Envelope: u16 length 1..AY_ENV_MAX_LENGTH, u16 loop index (< length, AY_NO_ENVELOPE = none), u16 release index
 * (volume only, AY_NO_ENVELOPE = none; needs a loop, loop < release < length), then `length` signed bytes. */
#define AY_ENV_HEADER 6
#define AY_ENV_MAX_LENGTH 256
#define AY_NO_ENVELOPE 0xFFFF
#define AY_PITCH_UNIT 4 /* pitch envelope step in pitch units: 1/16 semitone */

/* Pattern rows. A run byte AY_RUN | k = k + 1 empty rows (k 0..127). Otherwise a mask byte 0x01..0x13: bit 0 note
 * byte follows, bit 1 instrument byte follows, bits 2-4 effect count 0..4; then the note (0..95 = C-0..B-7,
 * absolute; AY_NOTE_OFF = key off), the instrument slot (1..64), and per effect column in order the effect code
 * (0..35 = '0'..'9', 'A'..'Z') and its parameter. */
#define AY_RUN 0x80
#define AY_MAX_RUN 128
#define AY_MASK_NOTE 0x01
#define AY_MASK_INSTRUMENT 0x02
#define AY_MASK_EFFECT_SHIFT 2
#define AY_NOTE_OFF 0xFE
#define AY_NOTES 96

/* Limits. */
#define AY_CHANNELS 3
#define AY_MAX_ORDERS 256
#define AY_MAX_INSTRUMENTS 64
#define AY_MAX_EFFECTS 4
#define AY_TONE_PERIOD_MAX 4095
#define AY_NOISE_PERIOD_MAX 31
#define AY_ENV_PERIOD_MAX 65535

/* Pitch units: 1/64 semitone, 0 = C-0; note n = pitch 64 n. */
#define AY_PITCH_UNITS_PER_SEMITONE 64
#define AY_PITCH_MAX 6143

/* Effect codes ('0'..'9' = 0..9, 'A'..'Z' = 10..35). */
#define AY_EFFECT_LETTERS "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
#define AY_FX_ARPEGGIO 0      /* 0xy */
#define AY_FX_SLIDE_UP 1      /* 1xx */
#define AY_FX_SLIDE_DOWN 2    /* 2xx */
#define AY_FX_PORTAMENTO 3    /* 3xx */
#define AY_FX_VIBRATO 4       /* 4xy */
#define AY_FX_VOLUME_SLIDE 10 /* Axy */
#define AY_FX_JUMP 11         /* Bxx */
#define AY_FX_VOLUME 12       /* Cxx */
#define AY_FX_BREAK 13        /* Dxx */
#define AY_FX_ENV_SHAPE 14    /* Exx (note setting) */
#define AY_FX_SPEED 15        /* Fxx */
#define AY_FX_DELAY 16        /* Gxx */
#define AY_FX_ENV_HIGH 17     /* Hxx (note setting) */
#define AY_FX_ENV_LOW 18      /* Ixx (note setting) */
#define AY_FX_ENV_AUTO 19     /* Jxx (note setting) */
#define AY_FX_FINE 25         /* Pxx */
#define AY_FX_CUT 28          /* Sxx */
#define AY_FX_MIXER 31        /* Vxx (note setting) */
#define AY_FX_NOISE 32        /* Wxx (note setting) */

/* One variant's numbers (index AY_VARIANT_*), from the defines above. */
typedef struct AyVariantSpec {
    const char* engine_id;
    const char* gd3_system;
    uint32_t master_hz;
    uint32_t ay_div;
    uint32_t frame_div;
    uint32_t vgm_clock;
    uint32_t vgm_rate;
    uint8_t io_bits;
    uint8_t vgm_type;
    uint8_t ym; /* 1: YM2149 (32 levels, envelope divider 1); 0: AY-3-8910/8912 (16 levels, divider 2) */
    uint8_t stereo; /* AY_STEREO_*: 1 = left A + B, right B + C */
} AyVariantSpec;

extern const AyVariantSpec ay_variants[AY_VARIANTS];

typedef struct AyInfo {
    int loaded;            /* AY_LOADED when valid */
    const char* problem;   /* why ay_load_image rejected the image (static string), else NULL */
    int problem_offset;    /* image offset of the offending byte, or -1 */
    int variant;
    int orders;
    int loop;
    int speed;
    int instruments;
    int data_size;
    uint16_t rows[AY_MAX_ORDERS];       /* rows of each order, 1..256 */
    uint8_t start_speed[AY_MAX_ORDERS]; /* ticks per row each order starts with in sequential straight play */
} AyInfo;

#define AY_LOADED 0x41594C44

typedef struct AyImage {
    uint8_t bytes[AY_IMAGE_SIZE];
    AyInfo info;
} AyImage;

/* One decoded pattern row of a channel. */
typedef struct AyRow {
    int note;       /* -1 none, 0..95, AY_NOTE_OFF */
    int instrument; /* 0 none, 1..64 */
    int effects;    /* 0..4 */
    uint8_t code[AY_MAX_EFFECTS];
    uint8_t param[AY_MAX_EFFECTS];
} AyRow;

/* Validates image->bytes and fills image->info. `variant` = the engine's variant (AY_VARIANT_*), or -1 to accept
 * any. Returns 0 or -1 (info.problem says why). */
int ay_load_image(AyImage* image, int variant);

/* Pattern cursor of one channel: decodes rows sequentially from an offset. */
typedef struct AyCursor {
    int offset; /* data region offset of the next byte */
    int run;    /* empty rows left in the current run */
} AyCursor;

/* Decodes the next row at the cursor (no bounds checks: loaded images only). */
void ay_next_row(const uint8_t* bytes, AyCursor* cursor, AyRow* row);
/* The pattern cursor at row 0 of (order, channel). */
AyCursor ay_pattern(const AyImage* image, int order, int channel);
/* Decodes row `row` of (order, channel) from the pattern start. */
void ay_read_row(const AyImage* image, int order, int channel, int row, AyRow* out);
/* The instrument record of a slot if it is defined, else NULL. */
const uint8_t* ay_instrument(const AyImage* image, int slot);

static inline int ay_u16(const uint8_t* p) { return p[0] | p[1] << 8; }

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_AY_IMAGE_H_ */
