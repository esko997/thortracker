/* POKEY song images: the bytes a lowering produces for the "atari-pokey" engine (Thor Tracker's own player on the
 * Atari 8-bit POKEY, docs/AUDIO_ENGINE.md "POKEY"). One fixed-size, position-independent image: a header, fixed
 * tables (rows per order, the order table of pattern offsets, 64 instruments, 32 samples), the data region
 * (patterns and envelopes) and the sample region (4-bit levels). Every reference is an offset into a region, so data
 * may move freely between images (live edits locate rows by (order, row) and envelopes by instrument slot).
 *
 * pk_load_image validates every byte the player can reach and derives PkInfo; images that fail are never played.
 * All multi-byte fields are little-endian. core/.../backend/PokeyPlayerImage.kt mirrors these numbers by hand. */
#ifndef THORTRACKER_POKEY_IMAGE_H_
#define THORTRACKER_POKEY_IMAGE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define PK_IMAGE_SIZE 65536u
#define PK_CHANNELS 4 /* tracker channels = POKEY channels 1-4 */
#define PK_MAX_ORDERS 256
#define PK_MAX_INSTRUMENTS 64
#define PK_MAX_SAMPLES 32
#define PK_MAX_EFFECTS 4 /* effect columns per channel and row */
#define PK_MAX_MULTISPEED 4
#define PK_MAX_ENVELOPE 256
#define PK_LAYOUT 1

#define PK_MACHINE_NTSC 0
#define PK_MACHINE_PAL 1

/* Header (64 bytes). */
#define PK_H_MAGIC 0        /* "TTPK" */
#define PK_H_LAYOUT 4       /* PK_LAYOUT */
#define PK_H_MACHINE 5      /* PK_MACHINE_NTSC (1.79 MHz, 59.92 Hz frames) or PK_MACHINE_PAL (1.77 MHz, 49.86 Hz) */
#define PK_H_MULTISPEED 6   /* N = 1..4: player ticks per video frame */
#define PK_H_SPEED 7        /* ticks per row at the song start, 1..255 */
#define PK_H_ORDERS 8       /* u16 order count 1..256 */
#define PK_H_LOOP 10        /* u16 loop order < order count */
#define PK_H_INSTRUMENTS 12 /* highest instrument slot present, 0..64 */
#define PK_H_AUDCTL 13      /* shared AUDCTL bits at the song start: only PK_AUDCTL_POLY9 (0x80), PK_AUDCTL_BASE15 (0x01) */
#define PK_H_DATA_SIZE 14   /* u16 bytes of the data region used (<= PK_DATA_CAPACITY) */
#define PK_H_SAMPLE_SIZE 16 /* u16 bytes of the sample region used (<= PK_SAMPLE_CAPACITY) */
#define PK_H_SAMPLES 18     /* sample count, 0..32 */
#define PK_HEADER_SIZE 64   /* bytes 19..63 are 0 */

/* Fixed tables and regions. */
#define PK_OFF_ROWS 64          /* 256 x u8: rows of order o (1..255; 0 = 256) */
#define PK_OFF_ORDERS 320       /* 256 x 4 x u16: pattern of (order o, channel c) at 320 + 2 (4 o + c), a data region offset */
#define PK_OFF_INSTRUMENTS 2368 /* 64 x 32: slot n (1..64) at 2368 + 32 (n - 1) */
#define PK_INSTRUMENT_SIZE 32
#define PK_OFF_SAMPLES 4416     /* 32 x 4: sample k (0..31) at 4416 + 4 k: u16 byte offset into the sample region, u16 length in levels */
#define PK_SAMPLE_ENTRY_SIZE 4
#define PK_OFF_DATA 4544        /* data region: patterns and envelopes */
#define PK_OFF_SAMPLE_DATA 49152 /* sample region: 4-bit levels, two per byte, the earlier level in the low nibble */
#define PK_DATA_CAPACITY (PK_OFF_SAMPLE_DATA - PK_OFF_DATA)      /* 44608 */
#define PK_SAMPLE_CAPACITY (PK_IMAGE_SIZE - PK_OFF_SAMPLE_DATA)  /* 16384 bytes = 32768 levels */
#define PK_MAX_SAMPLE_LENGTH 32768

/* Instrument record (32 bytes; an all-zero record = no instrument; records above PK_H_INSTRUMENTS must be zero). */
#define PK_I_FLAGS 0          /* PK_FLAG_* */
#define PK_I_FILTER_OFFSET 1  /* s8: high-pass partner counter = this sound's counter + offset (difference mode: 1..127) */
#define PK_I_PARTNER_AUDC 2   /* AUDC written to the channels the sound takes besides its own (0 = silent) */
#define PK_I_SAMPLE 3         /* sample sounds: sample index < PK_H_SAMPLES; else 0 */
#define PK_I_ROOT 4           /* sample sounds: the note (0..107) that plays the sample at its rate; else 0 */
#define PK_I_RATE 6           /* u16 sample sounds: levels per second at the root note, 1000..32000; else 0 */
#define PK_I_LOOP 8           /* u16 sample sounds: level index the end loops back to (< length), 0xFFFF none; else 0xFFFF */
#define PK_I_ENV_VOLUME 10     /* u16 data offset of the volume envelope (levels 0..15), 0xFFFF = none (15) */
#define PK_I_ENV_ARPEGGIO 12   /* u16: semitones -64..63 added to the note, 0xFFFF = none (0) */
#define PK_I_ENV_PITCH 14      /* u16: 1/16 semitones -64..63 (absolute offset, or added up per tick with PK_FLAG_PITCH_RELATIVE) */
#define PK_I_ENV_DISTORTION 16 /* u16: distortions 0..8 (below), 0xFFFF = none (5: pure tone) */
/* bytes 5, 18..31 are 0 */

#define PK_FLAG_DEFINED 0x80
#define PK_FLAG_JOINED 0x01         /* 16-bit: plays on channel 2 or 4 and takes channel 1 or 3 (the low byte) */
#define PK_FLAG_FILTERED 0x02       /* high-pass: plays on channel 1 or 2 and takes channel 3 or 4 (its clock) */
#define PK_FLAG_SAMPLE 0x04         /* 4-bit samples through volume-only mode, one level per timer interrupt */
#define PK_FLAG_FAST 0x08           /* counts the 1.79 MHz machine clock (where the channel allows it) */
#define PK_FLAG_PITCH_RELATIVE 0x10 /* the pitch envelope's steps add up */
#define PK_FLAG_DIFFERENCE 0x20     /* filtered: the note is the difference of the two counters' rates (the "saw") */
#define PK_FLAG_ALL 0xBF

/* Distortion values (envelopes, Vxx): AUDC bits 7-5 = value << 5 for 0..7; 8 = volume only (AUDC bit 4). */
#define PK_DISTORTIONS 9
#define PK_DIST_PURE 5
#define PK_DIST_VOLUME_ONLY 8

/* Envelope (in the data region): u16 length 1..256, u16 loop (< length, 0xFFFF none), u16 release (1..length-1,
 * > loop, 0xFFFF none), then `length` value bytes (s8 for arpeggio and pitch). */
#define PK_ENV_HEADER 6
#define PK_ENV_NONE 0xFFFF

/* Pattern rows. A run byte 0x80 | k = k + 1 empty rows (k 0..127). Otherwise a mask byte 0x01..0x13:
 * bit 0 note byte follows, bit 1 instrument byte follows, bits 2-4 effect count 0..4; then the note
 * (0..107 = C-0..B-8, absolute; 0xFE = OFF), the instrument slot (1..64, defined), and per effect column in
 * order the effect code (0..35 = '0'..'9', 'A'..'Z') and its parameter. */
#define PK_NOTE_OFF 0xFE
#define PK_NOTES 108

/* Effect codes ('0'..'9' = 0..9, 'A'..'Z' = 10..35). Global ones (B D F K N R T W) act at the row's tick 0. */
enum {
    PK_FX_ARPEGGIO = 0,      /* 0xy */
    PK_FX_SLIDE_UP = 1,      /* 1xx: 1/64 semitones per tick */
    PK_FX_SLIDE_DOWN = 2,    /* 2xx */
    PK_FX_PORTAMENTO = 3,    /* 3xx */
    PK_FX_VIBRATO = 4,       /* 4xy */
    PK_FX_VOLUME_SLIDE = 10, /* Axy */
    PK_FX_JUMP = 11,         /* Bxx */
    PK_FX_VOLUME = 12,       /* Cxx: 00-0F */
    PK_FX_BREAK = 13,        /* Dxx */
    PK_FX_SPEED = 15,        /* Fxx: 01-FF */
    PK_FX_DELAY = 16,        /* Gxx */
    PK_FX_FILTER = 17,       /* Hxx: high-pass offset, s8 (difference mode 01-7F) */
    PK_FX_CLOCK = 20,        /* Kxx: base clock 00 = 64 kHz, 01 = 15 kHz (global) */
    PK_FX_POLY = 23,         /* Nxx: 00 = 17-bit, 01 = 9-bit noise generator (global) */
    PK_FX_FINE = 25,         /* Pxx: 80 = none */
    PK_FX_RESTART = 27,      /* R00: initialization-mode pulse: generators and base clocks restart (global) */
    PK_FX_CUT = 28,          /* Sxx: release at tick xx */
    PK_FX_TIMERS = 29,       /* T00: STIMER, every counter reloads (global) */
    PK_FX_DISTORTION = 31,   /* Vxx: 00-08 */
    PK_FX_TWO_TONE = 32,     /* Wxx: 00 off, 01 on, 02 on with force break (global) */
};

#define PK_FINE_CENTRE 0x80

typedef struct PkInfo {
    int loaded;            /* PK_LOADED when valid */
    const char* problem;   /* why pk_load_image rejected the image (static string), else NULL */
    int problem_offset;    /* image offset of the offending byte, or -1 */
    int machine;           /* PK_MACHINE_* */
    int orders;
    int loop;
    int multispeed;
    int speed;
    int instruments;
    int samples;
    uint16_t rows[PK_MAX_ORDERS];        /* rows of each order, 1..256 */
    uint8_t start_speed[PK_MAX_ORDERS];  /* ticks per row each order starts with in sequential straight play */
    uint8_t start_audctl[PK_MAX_ORDERS]; /* shared AUDCTL bits (POLY9, BASE15) each order starts with, likewise */
    uint8_t start_two_tone[PK_MAX_ORDERS]; /* Wxx state (0..2) each order starts with, likewise */
} PkInfo;

#define PK_LOADED 0x504B4C44

typedef struct PokeyImage {
    uint8_t bytes[PK_IMAGE_SIZE];
    PkInfo info;
} PokeyImage;

/* One decoded pattern row of a channel. */
typedef struct PkRow {
    int note;       /* -1 none, 0..107, PK_NOTE_OFF */
    int instrument; /* 0 none, 1..64 */
    int effects;    /* 0..4 */
    uint8_t code[PK_MAX_EFFECTS];
    uint8_t param[PK_MAX_EFFECTS];
} PkRow;

/* Validates image->bytes and fills image->info. Returns 0 or -1 (info.problem says why). */
int pk_load_image(PokeyImage* image);

/* Pattern cursor of one channel: decodes rows sequentially from an offset. */
typedef struct PkCursor {
    int offset; /* data region offset of the next byte */
    int run;    /* empty rows left in the current run */
} PkCursor;

/* Decodes the next row at the cursor (no bounds checks: loaded images only). */
void pk_next_row(const uint8_t* bytes, PkCursor* cursor, PkRow* row);
/* Decodes row `row` of (order, channel) from the pattern start. */
void pk_read_row(const PokeyImage* image, int order, int channel, int row, PkRow* out, PkCursor* after);

static inline int pk_u16(const uint8_t* p) { return p[0] | p[1] << 8; }

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_POKEY_IMAGE_H_ */
