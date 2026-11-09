/* YM2612 song images: the bytes a lowering produces for the "ym2612-fm" engine (Thor Tracker's own player, its
 * writes streamed through SGDK's XGM2 driver on a Z80 + YM2612 + PSG; docs/AUDIO_ENGINE.md "YM2612"). One
 * fixed-size, position-independent image of layout 2:
 *
 *   0 ..  65535  the tonal prefix, unchanged from layout 1: header, rows per order, the order table of the ten
 *                tonal channels' pattern offsets, 64 instruments, the tonal pattern/envelope data region
 *   65536 ..     extensions: FM 3 operator offsets per slot, the three PCM channels' order table, PCM key maps,
 *                compiled PCM variants (the XGM2 sample table), the PCM pattern region and the PCM sample bank.
 *
 * Every pattern reference is an offset into its region, so data may move freely between images (live edits locate
 * rows by (order, row), never by pointer). ym_load_image validates every byte the player or the driver can reach and
 * derives YmInfo; images that fail are never played. All multi-byte fields are little-endian. */
#ifndef THORTRACKER_YM_IMAGE_H_
#define THORTRACKER_YM_IMAGE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define YM_IMAGE_SIZE 1188864u
#define YM_LAYOUT 2
#define YM_CHANNELS 13      /* tracker channels: FM 1-6, PSG 1-3, Noise, PCM 1-3 */
#define YM_TONAL_CHANNELS 10 /* channels of the tonal order table (its stride) */
#define YM_FM_CHANNELS 6
#define YM_CH_FM3 2         /* FM 3: the channel with the special (per-operator frequency) mode */
#define YM_CH_FM6 5         /* FM 6: its output is the DAC's while a PCM sample plays */
#define YM_CH_PSG 6         /* first PSG tone channel */
#define YM_CH_NOISE 9
#define YM_CH_PCM 10        /* first PCM channel (XGM2 PCM channel 0) */
#define YM_PCM_CHANNELS 3
#define YM_MAX_ORDERS 256
#define YM_MAX_INSTRUMENTS 64
#define YM_MAX_EFFECTS 4    /* effect columns per channel and row */

enum { YM_NTSC = 0, YM_PAL = 1 };
enum { YM_KIND_ANY = -1, YM_KIND_FM = 0, YM_KIND_PSG = 1, YM_KIND_PCM = 2 };

/* Header (64 bytes). */
#define YM_H_MAGIC 0        /* "TTYM" */
#define YM_H_LAYOUT 4       /* YM_LAYOUT */
#define YM_H_REGION 5       /* YM_NTSC / YM_PAL */
#define YM_H_SPEED 6        /* ticks per row at the song start, 1..255 */
#define YM_H_LFO 7          /* register $22 at the start: 0 off, $08..$0F on with rate 0..7 */
#define YM_H_ORDERS 8       /* u16 order count 1..256 */
#define YM_H_LOOP 10        /* u16 loop order < order count */
#define YM_H_INSTRUMENTS 12 /* highest instrument slot present, 0..64 */
#define YM_H_DATA_SIZE 14   /* u16 bytes of tonal pattern and envelope data used (<= YM_DATA_CAPACITY) */
#define YM_H_FM3 16         /* FM 3 mode: YM_FM3_NORMAL or YM_FM3_SPECIAL */
#define YM_H_PCM_VARIANTS 17 /* compiled PCM variants 0..YM_PCM_MAX_VARIANTS */
#define YM_H_PCM_BANK_SIZE 18 /* u16 256-byte pages of the PCM bank used, 0..YM_PCM_BANK_PAGES */
#define YM_H_PCM_DATA_SIZE 20 /* u32 bytes of PCM pattern data used, 0..YM_PCM_DATA_CAPACITY */
#define YM_HEADER_SIZE 64   /* byte 13 and bytes 24..63 are 0 */
#define YM_FM3_NORMAL 0
#define YM_FM3_SPECIAL 1

/* Fixed tables of the tonal prefix (layout 1's, unchanged). */
#define YM_OFF_ROWS 64          /* 256 x u8: rows of order o (1..255; 0 = 256) */
#define YM_OFF_ORDERS 320       /* 256 x 10 x u16: pattern of (order o, tonal channel c) at 320 + 2 (10 o + c) */
#define YM_OFF_INSTRUMENTS 5440 /* 64 x 40: slot n (1..64) at 5440 + 40 (n - 1) */
#define YM_INSTRUMENT_SIZE 40
#define YM_OFF_DATA 8000        /* tonal data region: patterns and PSG envelopes */
#define YM_TONAL_SIZE 65536
#define YM_DATA_CAPACITY (YM_TONAL_SIZE - YM_OFF_DATA) /* 57536 */

/* Extensions. */
#define YM_OFF_CH3 65536        /* 64 x 4 x s16: FM 3 special-mode offsets of slot n at 65536 + 8 (n - 1), operators 1..4
                                 * in algorithm-diagram order, 1/64 semitone; FM records only (else zero) */
#define YM_CH3_LIMIT 3072
#define YM_OFF_PCM_ORDERS 66048 /* 256 x 3 x u16: PCM pattern of (order o, PCM channel k) at 66048 + 2 (3 o + k) */
#define YM_OFF_PCM_KEYS 67584   /* 64 x 96 x u8: variant of (slot n, note x) at 67584 + 96 (n - 1) + x, 0 = unmapped;
                                 * PCM records only (else zero) */
#define YM_PCM_KEYS 96
#define YM_OFF_PCM_VARIANTS 73728 /* 123 x 8: variant id (1..123) at 73728 + 8 (id - 1) */
#define YM_PCM_VARIANT_SIZE 8   /* u16 start page, u16 length in pages (>= 1), u8 flags, 3 bytes zero */
#define YM_PCM_MAX_VARIANTS 123 /* XGM2 single track: a 124-entry sample table = 123 samples and their end */
#define YM_PCM_HALF_RATE 0x01   /* variant flag: played at the driver's half rate (its region's second half zero) */
#define YM_OFF_PCM_DATA 74752   /* PCM data region: the PCM channels' patterns */
#define YM_PCM_DATA_CAPACITY 65536
#define YM_OFF_PCM_BANK 140288  /* compiled signed 8-bit samples, variant k at 140288 + 256 start(k) */
#define YM_PCM_BANK_SIZE 1048576
#define YM_PCM_ALIGN 256
#define YM_PCM_BANK_PAGES 4096  /* YM_PCM_BANK_SIZE / YM_PCM_ALIGN */
#if YM_PCM_BANK_PAGES * YM_PCM_ALIGN != YM_PCM_BANK_SIZE
#error "the PCM bank is a whole number of pages"
#endif

/* PCM rates: master clocks per sample output by the driver (YM2612 timer A = 1020: 4 x 144 YM2612 clocks of MCLK / 7;
 * NTSC 13316.8 Hz, PAL 13195.3 Hz) and per source byte of a half-rate variant. */
#define YM_PCM_FULL_DIVIDER 4032
#define YM_PCM_HALF_DIVIDER 8064

/* Instrument record (40 bytes). Byte 0: flags. */
#define YM_I_FLAGS 0
#define YM_FLAG_DEFINED 0x80
#define YM_FLAG_PSG 0x01
#define YM_FLAG_PCM 0x02    /* PCM instrument: flags 0x82, the other 39 bytes zero; its keys are at YM_OFF_PCM_KEYS */
/* FM instrument (flags 0x80). */
#define YM_I_ALG 1          /* algorithm 0..7 */
#define YM_I_FEEDBACK 2     /* operator 1 feedback 0..7 */
#define YM_I_AMS_FMS 3      /* register $B4 without pan: AMS bits 4-5, FMS bits 0-2 */
#define YM_I_OPS 4          /* operator k (0..3) at 4 + 7k: $30 $40 $50 $60 $70 $80 $90 */
#define YM_OP_BYTES 7
/* PSG instrument (flags 0x81). Envelopes are FamiStudio-style sequences, one value per tick, in the data region. */
#define YM_I_PSG_NOISE 1    /* noise mode: bit 2 white, bits 0-1 source (3 = PSG 3's period); Noise channel only */
#define YM_I_PSG_FLAGS 2    /* YM_PSG_PITCH_RELATIVE; other bits 0 */
#define YM_PSG_PITCH_RELATIVE 0x01 /* the pitch envelope accumulates every tick instead of offsetting */
#define YM_I_ENV_VOLUME 4   /* u16 data offset of the volume envelope (0..15), YM_NO_ENVELOPE = constant 15 */
#define YM_I_ENV_ARPEGGIO 6 /* u16 data offset of the arpeggio envelope (-64..63 semitones), none = 0 */
#define YM_I_ENV_PITCH 8    /* u16 data offset of the pitch envelope (-64..63 x YM_PITCH_UNIT), none = 0 */
#define YM_NO_ENVELOPE 0xFFFF
/* Envelope: u16 length 1..YM_ENV_MAX_LENGTH, u16 loop index (< length, YM_NO_ENVELOPE = none), u16 release index
 * (volume only, YM_NO_ENVELOPE = none; needs a loop, loop < release < length), then `length` signed bytes. */
#define YM_ENV_HEADER 6
#define YM_ENV_MAX_LENGTH 256
#define YM_PITCH_UNIT 4     /* pitch envelope step in pitch units: 1/16 semitone */
/* FM and PSG. */
#define YM_I_SWEEP_START 32 /* s8 semitones relative to the note, -96..96 */
#define YM_I_SWEEP_END 33   /* s8 semitones, -96..96 */
#define YM_I_SWEEP_SPEED 34 /* u8 1/16 semitones per tick toward the end; 0 = no sweep */

/* Pattern rows (both regions). A run byte 0x80 | k = k + 1 empty rows (k 0..127). Otherwise a mask byte 0x01..0x13:
 * bit 0 note byte follows, bit 1 instrument byte follows, bits 2-4 effect count 0..4; then the note
 * (0..95 = C-0..B-7, absolute; 0xFE = key off), the instrument slot (1..64), and per effect column in
 * order the effect code (0..35 = '0'..'9', 'A'..'Z') and its parameter. */
#define YM_NOTE_OFF 0xFE
#define YM_NOTES 96

/* Effect codes ('0'..'9' = 0..9, 'A'..'Z' = 10..35). PCM channels take only B, D, F, G, L and S. */
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
    int fm3;               /* YM_FM3_NORMAL / YM_FM3_SPECIAL */
    int data_size;         /* tonal data bytes used */
    int pcm_data_size;     /* PCM pattern bytes used */
    int pcm_variants;      /* compiled variants (driver sample ids 1..pcm_variants) */
    int pcm_pages;         /* PCM bank pages used */
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
/* Copies a loaded image's used bytes and its info (live edits: the bytes no reader can reach are left as they were). */
void ym_copy_image(YmImage* dst, const YmImage* src);

/* Pattern cursor of one channel: decodes rows sequentially from an image offset. */
typedef struct YmCursor {
    int at;     /* image offset of the next byte */
    int run;    /* empty rows left in the current run */
} YmCursor;

/* Decodes the next row at the cursor (no bounds checks: loaded images only). */
void ym_next_row(const uint8_t* bytes, YmCursor* cursor, YmRow* row);
/* Decodes row `row` of (order, channel) from the pattern start. */
void ym_read_row(const YmImage* image, int order, int channel, int row, YmRow* out);
/* The pattern cursor at row 0 of (order, channel). */
YmCursor ym_pattern(const YmImage* image, int order, int channel);
/* The instrument record of a slot if it is defined (and of the kind: YM_KIND_*), else NULL. */
const uint8_t* ym_instrument(const YmImage* image, int slot, int kind);
/* FM 3 special-mode offset of operator k (0..3, diagram order) of slot n (pitch units). */
int ym_ch3_offset(const YmImage* image, int slot, int k);
/* The PCM variant (1..pcm_variants) slot n maps note x to, 0 = unmapped or not a PCM slot. */
int ym_pcm_variant(const YmImage* image, int slot, int note);
/* Variant id's start page, length in pages and flags (loaded images, 1 <= id <= pcm_variants). */
void ym_pcm_region(const YmImage* image, int id, int* start, int* pages, int* flags);

static inline int ym_u16(const uint8_t* p) { return p[0] | p[1] << 8; }
static inline int ym_channel_kind(int c) { return c < YM_CH_PSG ? YM_KIND_FM : c < YM_CH_PCM ? YM_KIND_PSG : YM_KIND_PCM; }

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_YM_IMAGE_H_ */
