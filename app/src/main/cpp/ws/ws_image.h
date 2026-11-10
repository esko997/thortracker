/* WonderSwan song images: the bytes a lowering produces for the "ws-wonderswan" (original model) and
 * "ws-wonderswan-color" (WonderSwan Color) engines, Thor Tracker's own player on the WonderSwan sound unit
 * (docs/AUDIO_ENGINE.md, "WonderSwan"). One fixed-size, position-independent image: a header (model, the shared
 * voice timer reload, speaker shift and output path at the song start), fixed tables (rows per order, the order table
 * of pattern offsets, 64 instruments, 64 waves, 64 sample entries) and the data region (patterns, envelopes, sample
 * bytes). Every pattern and envelope reference is a u16 offset and every sample a u32 offset into the region, so data
 * may move freely between images (live edits locate rows by (order, row), never by pointer).
 *
 * ws_load_image validates every byte the player can reach and derives WsInfo; images that fail are never played.
 * All multi-byte fields are little-endian. tools/audio/prepare_ws.py reads every `#define WS_*` line below (one
 * `#define NAME value` per line, an optional trailing block comment) and the WS_FX_* enum into WsPlayerImage.kt. */
#ifndef THORTRACKER_WS_IMAGE_H_
#define THORTRACKER_WS_IMAGE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define WS_IMAGE_SIZE 131072
#define WS_LAYOUT 1
#define WS_MODEL_MONO 0          /* the original WonderSwan: 4 lanes */
#define WS_MODEL_COLOR 1         /* the WonderSwan Color: 5 lanes (Hyper Voice), Sound DMA */
#define WS_OUTPUT_HEADPHONES 0   /* stereo: (L10 << 5) + Hyper Voice per side, 16 bits wrapping */
#define WS_OUTPUT_SPEAKER 1      /* mono: ((L10 + R10) >> shift) & $FF, << 7 */
#define WS_CHANNELS 5            /* lanes on the Color: CH1-CH4, Hyper Voice */
#define WS_CHANNELS_MONO 4       /* lanes on the original model */
#define WS_HYPER_VOICE 4         /* the Hyper Voice lane */
#define WS_VOICE_CHANNEL 1       /* CH2: voice (8-bit PCM) mode */
#define WS_SWEEP_CHANNEL 2       /* CH3: hardware frequency sweep */
#define WS_NOISE_CHANNEL 3       /* CH4: LFSR noise */
#define WS_MAX_ORDERS 256
#define WS_MAX_INSTRUMENTS 64
#define WS_MAX_WAVES 64
#define WS_MAX_SAMPLES 64
#define WS_MAX_EFFECTS 4         /* effect columns per lane and row */
#define WS_MAX_TIMER 255         /* voice timer reload R: 12000 / R Hz */
#define WS_MAX_SHIFT 3           /* speaker shift */
#define WS_MAX_ENVELOPE 255      /* envelope length limit */
#define WS_MAX_SAMPLE_FRAMES 65535 /* sample length limit (loop starts are u16) */
#define WS_MAX_TAP 7             /* noise tap modes 0..7 */
#define WS_MAX_SWEEP_TIME 31
#define WS_MAX_DMA_RATE 3        /* 4000, 6000, 12000, 24000 bytes/s */
#define WS_DMA_BASE 0x40000      /* linear address of data byte 0 for Sound DMA (the cartridge ROM area) */
#define WS_WAVE_RAM 0x3FC0       /* the player's wave RAM ($8F = $FF): CH c's wave at $3FC0 + 16 c */
#define WS_WAVE_SIZE 32          /* samples per wave */
#define WS_WAVE_MAX 15           /* largest wave sample */
#define WS_NOTE_OFF 0xFE
#define WS_NOTE_MIN 19           /* G-1: the lowest note whose N = 2048 - d fits (N <= 2048) */
#define WS_NOTE_MAX 95           /* B-7 */

/* Header (64 bytes). */
#define WS_H_MAGIC 0             /* "TTWS" */
#define WS_H_LAYOUT 4            /* WS_LAYOUT */
#define WS_H_MODEL 5             /* WS_MODEL_MONO or WS_MODEL_COLOR */
#define WS_H_SPEED 6             /* ticks per row at the song start, 1..255 */
#define WS_H_INSTRUMENTS 7       /* highest instrument slot present, 0..64 */
#define WS_H_ORDERS 8            /* u16 order count 1..256 */
#define WS_H_LOOP 10             /* u16 loop order < order count */
#define WS_H_WAVES 12            /* wave count W, 0..64 */
#define WS_H_SAMPLES 13          /* sample count S, 0..64 */
#define WS_H_TIMER 14            /* voice timer reload R at the start, 1..255 */
#define WS_H_SHIFT 15            /* speaker shift at the start, 0..3 */
#define WS_H_OUTPUT 16           /* WS_OUTPUT_HEADPHONES or WS_OUTPUT_SPEAKER */
#define WS_H_DATA_SIZE 20        /* u32 bytes of the data region used (<= WS_DATA_CAPACITY) */
#define WS_HEADER_SIZE 64        /* bytes 17-19 and 24..63 are 0 */

/* Fixed tables. */
#define WS_OFF_ROWS 64           /* 256 x u8: rows of order o (1..255; 0 = 256) */
#define WS_OFF_ORDERS 320        /* 256 x 5 x u16: pattern of (order o, lane c) at 320 + 2 (5 o + c) */
#define WS_OFF_INSTRUMENTS 2880  /* 64 x 32: slot n (1..64) at 2880 + 32 (n - 1) */
#define WS_INSTRUMENT_SIZE 32
#define WS_OFF_WAVES 4928        /* 64 x 32: wave w at 4928 + 32 w, samples 0..15 in play order; waves >= W zero */
#define WS_OFF_SAMPLES 6976      /* 64 x 16: sample s at 6976 + 16 s; entries >= S zero */
#define WS_SAMPLE_ENTRY_SIZE 16
#define WS_OFF_DATA 8192         /* data region (bytes 8000..8191 are 0) */
#define WS_DATA_CAPACITY 122880  /* WS_IMAGE_SIZE - WS_OFF_DATA */

/* Sample entry (16 bytes). */
#define WS_S_OFFSET 0            /* u32 data offset of the unsigned bytes */
#define WS_S_FRAMES 4            /* u32 frames 1..65535 */
#define WS_S_SIGNED_OFFSET 8     /* u32 data offset of the signed copy (byte XOR $80), 0 without */
#define WS_S_FLAGS 12            /* u8 flags, then 3 x 0 */
#define WS_SAMPLE_STEREO 1       /* frames are L, R byte pairs */
#define WS_SAMPLE_SIGNED 2       /* a signed copy exists */

/* Instrument record (32 bytes; an all-zero record = no instrument; records above the instrument count are zero). */
#define WS_I_FLAGS 0             /* bit 7 defined, bits 0-1 kind, bit 2 relative pitch, bit 3 DMA, bit 4 LFSR reset, bit 5 reverse */
#define WS_I_SOURCE 1            /* tone: wave; noise: tap mode; sample: sample index */
#define WS_I_ROOT 2              /* CPU-fed sample: root note 19..95; DMA sample: rate 0..3; other kinds 0 */
#define WS_I_LOOP 3              /* sample: 1 loop, 0 one-shot; other kinds 0 */
#define WS_I_LOOP_START 4        /* u16 loop start frame (CPU-fed looping samples: < frames; otherwise 0) */
#define WS_I_SWEEP 6             /* tone: s8 sweep value; other kinds 0 */
#define WS_I_SWEEP_TIME 7        /* tone: sweep time 0..31; other kinds 0 */
#define WS_I_ENVELOPES 8         /* envelope k at 8 + 6 k: u16 offset, u8 length, u8 loop, u8 release, u8 0 */
#define WS_ENVELOPE_BYTES 6
#define WS_ENVELOPE_COUNT 4
#define WS_ENV_VOLUME 0          /* values 0..15 */
#define WS_ENV_ARPEGGIO 1        /* s8 -64..63 semitones */
#define WS_ENV_PITCH 2           /* s8 -128..127, 1/64 semitones */
#define WS_ENV_WAVE 3            /* tone: wave 0..W - 1; noise: tap mode 0..7 */
#define WS_ENV_NONE 0xFF
#define WS_FLAG_DEFINED 0x80
#define WS_FLAG_RELATIVE 0x04
#define WS_FLAG_DMA 0x08         /* sample fed by Sound DMA (Color only) */
#define WS_FLAG_LFSR_RESET 0x10  /* noise: reset the LFSR at key-on */
#define WS_FLAG_REVERSE 0x20     /* DMA sample played backwards */
#define WS_KIND_MASK 0x03
#define WS_KIND_TONE 0
#define WS_KIND_NOISE 1
#define WS_KIND_SAMPLE 2

/* Writes and time. */
#define WS_SPACE_PORT 0          /* a sound port write ($4A..$9F) */
#define WS_SPACE_RAM 1           /* an internal RAM write (wave tables) */
#define WS_CPU_HZ 3072000        /* the sound unit's clock */
#define WS_TICK_CYCLES 40704     /* one frame: 159 lines of 256 cycles */
#define WS_LINE_CYCLES 256       /* one display line: the voice timer's unit */
#define WS_BLOCK_CYCLES 128      /* one sound unit output frame (24 kHz) */
#define WS_DMA_TRANSFER_CYCLE 122

/* Effect codes ('0'..'9' = 0..9, 'A'..'Z' = 10..35). Every other code is rejected by the loader. */
enum {
    WS_FX_ARPEGGIO = 0,
    WS_FX_SLIDE_UP = 1,
    WS_FX_SLIDE_DOWN = 2,
    WS_FX_PORTAMENTO = 3,
    WS_FX_VIBRATO = 4,
    WS_FX_PAN = 8,
    WS_FX_VOLUME_SLIDE = 10,
    WS_FX_JUMP = 11,
    WS_FX_VOLUME = 12,
    WS_FX_BREAK = 13,
    WS_FX_SPEED = 15,
    WS_FX_DELAY = 16,
    WS_FX_HOLD = 17,
    WS_FX_SHIFT = 20,
    WS_FX_TAP = 23,
    WS_FX_FINE = 25,
    WS_FX_CUT = 28,
    WS_FX_TIMER = 29,
    WS_FX_SWEEP = 30,
    WS_FX_SWEEP_TIME = 31,
    WS_FX_WAVE = 32,
};

/* One register write of the player: a port (WS_SPACE_PORT, addr $4A..$9F) or an internal RAM byte (WS_SPACE_RAM). */
typedef struct WsWrite {
    uint8_t space;
    uint16_t addr;
    uint8_t val;
} WsWrite;

typedef struct WsInfo {
    int loaded;            /* WS_LOADED when valid */
    const char* problem;   /* why ws_load_image rejected the image (static string), else NULL */
    int problem_offset;    /* image offset of the offending byte, or -1 */
    int model;             /* WS_MODEL_* */
    int lanes;             /* 4 or 5 */
    int orders;
    int loop;
    int speed;
    int instruments;
    int waves;
    int samples;
    int timer;             /* voice timer reload at the start */
    int shift;             /* speaker shift at the start */
    int output;            /* WS_OUTPUT_* */
    uint32_t data_size;
    uint16_t rows[WS_MAX_ORDERS];        /* rows of each order, 1..256 */
    uint8_t start_speed[WS_MAX_ORDERS];  /* ticks per row each order starts with in sequential straight play */
} WsInfo;

#define WS_LOADED 0x5753494D

typedef struct WsImage {
    uint8_t bytes[WS_IMAGE_SIZE];
    WsInfo info;
} WsImage;

/* One decoded pattern row of a lane. */
typedef struct WsRow {
    int note;        /* -1 none, WS_NOTE_MIN..WS_NOTE_MAX, WS_NOTE_OFF */
    int instrument;  /* 0 none, 1..64 */
    int effects;     /* 0..4 */
    uint8_t code[WS_MAX_EFFECTS];
    uint8_t param[WS_MAX_EFFECTS];
} WsRow;

/* Validates image->bytes (either model) and fills image->info. Returns 0 or -1 (info.problem says why). */
int ws_load_image(WsImage* image);

/* Pattern cursor of one lane: decodes rows sequentially from an offset. */
typedef struct WsCursor {
    int offset; /* data region offset of the next byte */
    int run;    /* empty rows left in the current run */
} WsCursor;

/* Decodes the next row at the cursor (no bounds checks: loaded images only). */
void ws_next_row(const uint8_t* bytes, WsCursor* cursor, WsRow* row);
/* Decodes row `row` of (order, lane) from the pattern start; `after` (may be NULL) receives the cursor past it. */
void ws_read_row(const WsImage* image, int order, int lane, int row, WsRow* out, WsCursor* after);

static inline int ws_u16(const uint8_t* p) { return p[0] | p[1] << 8; }
static inline uint32_t ws_u32(const uint8_t* p) {
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_WS_IMAGE_H_ */
