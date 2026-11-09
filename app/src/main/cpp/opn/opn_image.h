/* YM2203 (OPN) and YM2608 (OPNA) song images: the bytes the lowering (core OpnLowering.kt, constants mirrored by hand
 * in core OpnPlayerImage.kt) produces for the "ym2203-opn" and "ym2608-opna" engines (Thor Tracker's own player on
 * ymfm's YM2203 / YM2608; docs/AUDIO_ENGINE.md "OPN and OPNA"). One fixed-size, position-independent image: a header,
 * fixed tables (rows per order, the order table of pattern offsets, 64 instruments, 64 ADPCM-B samples), the pattern
 * and envelope data region and, on the YM2608 only, the 256 KiB ADPCM-B memory image. Every pattern reference is an
 * offset into the data region, so data may move freely between images (live edits locate rows by (order, row), never
 * by pointer).
 *
 * Clocks (both are the PC-88 / PC-98 sound clocks; see docs/AUDIO_ENGINE.md for the sources): YM2203 3993600 Hz,
 * YM2608 7987200 Hz, both at the power-on prescaler (/6 FM): one FM sample per 72 (YM2203) / 144 (YM2608) master
 * clocks, 55466.67 Hz on both, the "native sample" every timing below counts in. The SSG runs at an effective
 * 1996800 Hz on both (tone = 124800 / TP Hz).
 *
 * Ticks: one player tick is one timer B period, 16 x (256 - TB) native samples (TB = OPN_H_TIMER_B), i.e.
 * 3466.67 / (256 - TB) ticks per second (TB 198: 59.77 Hz). Timer A (CSM) counts (1024 - NA) native samples.
 *
 * opn_load_image validates every byte the player can reach and derives OpnInfo; images that fail are never played.
 * All multi-byte fields are little-endian. */
#ifndef THORTRACKER_OPN_IMAGE_H_
#define THORTRACKER_OPN_IMAGE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ---- chips and channels ----------------------------------------------------------------------------------------- */

enum { OPN_CHIP_YM2203 = 0, OPN_CHIP_YM2608 = 1 };

#define OPN_CLOCK_YM2203 3993600u
#define OPN_CLOCK_YM2608 7987200u
#define OPN_NATIVE_RATE_NUM 3993600u /* native sample rate = OPN_NATIVE_RATE_NUM / OPN_NATIVE_RATE_DEN Hz (55466.67) */
#define OPN_NATIVE_RATE_DEN 72u

/* Tracker channels. YM2203: FM 1-3 (0-2), SSG 1-3 (3-5). YM2608: FM 1-6 (0-5), SSG 1-3 (6-8), the six rhythm drums
 * (9-14: bass drum, snare, top cymbal, hi-hat, tom, rim shot, as register $10's bits 0-5) and the ADPCM-B channel
 * (15). */
#define OPN_MAX_CHANNELS 16
#define OPN_CHANNELS_YM2203 6
#define OPN_CHANNELS_YM2608 16
#define OPN_FM_YM2203 3
#define OPN_FM_YM2608 6
#define OPN_SSG 3
#define OPN_RHYTHM 6
#define OPNA_CH_SSG 6
#define OPNA_CH_RHYTHM 9
#define OPNA_CH_ADPCM 15
#define OPN_CH_FM3 2 /* FM 3: the channel with the special (per-operator frequency) and CSM modes */

enum { OPN_PART_FM = 0, OPN_PART_SSG = 1, OPN_PART_RHYTHM = 2, OPN_PART_ADPCM = 3 };

static inline int opn_channels(int chip) { return chip == OPN_CHIP_YM2608 ? OPN_CHANNELS_YM2608 : OPN_CHANNELS_YM2203; }
static inline int opn_fm_channels(int chip) { return chip == OPN_CHIP_YM2608 ? OPN_FM_YM2608 : OPN_FM_YM2203; }
static inline int opn_ssg_first(int chip) { return opn_fm_channels(chip); }
/* What tracker channel `c` of `chip` is (OPN_PART_*), or -1 outside the chip's channels. */
static inline int opn_part(int chip, int c) {
    if (c < 0 || c >= opn_channels(chip)) return -1;
    if (c < opn_fm_channels(chip)) return OPN_PART_FM;
    if (c < opn_fm_channels(chip) + OPN_SSG) return OPN_PART_SSG;
    return c < OPNA_CH_ADPCM ? OPN_PART_RHYTHM : OPN_PART_ADPCM;
}

/* ---- image ------------------------------------------------------------------------------------------------------ */

#define OPN_IMAGE_SIZE 65536u                                /* the song part; a whole YM2203 image */
#define OPN_ADPCM_MEMORY 262144u                             /* YM2608 ADPCM-B RAM: 256 KiB */
#define OPNA_IMAGE_SIZE (OPN_IMAGE_SIZE + OPN_ADPCM_MEMORY) /* a whole YM2608 image */
#define OPN_MAX_ORDERS 256
#define OPN_MAX_INSTRUMENTS 64
#define OPN_MAX_SAMPLES 64
#define OPN_MAX_EFFECTS 4 /* effect columns per channel and row */
#define OPN_LAYOUT 1

/* Header (64 bytes). */
#define OPN_H_MAGIC 0        /* "TTOP" */
#define OPN_H_LAYOUT 4       /* OPN_LAYOUT */
#define OPN_H_CHIP 5         /* OPN_CHIP_YM2203 / OPN_CHIP_YM2608: must match the engine */
#define OPN_H_SPEED 6        /* ticks per row at the song start, 1..255 */
#define OPN_H_LFO 7          /* YM2608 register $22 at the start: 0 off, $08..$0F on at rate 0..7; YM2203: 0 */
#define OPN_H_ORDERS 8       /* u16 order count 1..256 */
#define OPN_H_LOOP 10        /* u16 loop order < order count */
#define OPN_H_INSTRUMENTS 12 /* highest instrument slot present, 0..64 */
#define OPN_H_FM3_MODE 13    /* OPN_FM3_NORMAL / OPN_FM3_SPECIAL / OPN_FM3_CSM */
#define OPN_H_DATA_SIZE 14   /* u16 bytes of pattern and envelope data used (<= OPN_DATA_CAPACITY) */
#define OPN_H_TIMER_A 16     /* u16 timer A value NA 0..1023 at the start (CSM rate) */
#define OPN_H_TIMER_B 18     /* timer B value TB OPN_TIMER_B_MIN..OPN_TIMER_B_MAX: the tick rate */
#define OPN_H_RHYTHM_LEVEL 19 /* YM2608 rhythm total level ($11) 0..63 at the start; YM2203: 0 */
#define OPN_H_SAMPLES 20     /* ADPCM-B sample table entries 0..64; YM2203: 0 */
#define OPN_H_ADPCM_SIZE 24  /* u32 bytes of ADPCM-B memory used (a multiple of 32, <= OPN_ADPCM_MEMORY); YM2203: 0 */
#define OPN_HEADER_SIZE 64   /* bytes 21..23 and 28..63 are 0 */

enum { OPN_FM3_NORMAL = 0, OPN_FM3_SPECIAL = 1, OPN_FM3_CSM = 2 };

#define OPN_TIMER_B_MIN 183     /* 47.49 ticks per second: a tick fits CHIP_MAX_TICK_FRAMES at 96 kHz */
#define OPN_TIMER_B_MAX 250     /* 577.8 ticks per second */
#define OPN_TIMER_B_DEFAULT 198 /* 59.77 ticks per second */
#define OPN_TIMER_A_MAX 1023

/* Native samples per tick at timer B value `tb`. */
static inline uint32_t opn_tick_samples(int tb) { return 16u * (uint32_t)(256 - tb); }

/* Fixed tables. */
#define OPN_OFF_ROWS 64          /* 256 x u8: rows of order o (1..255; 0 = 256) */
#define OPN_OFF_ORDERS 320       /* 256 x 16 x u16: pattern of (order o, channel c) at 320 + 2 (16 o + c); channels
                                  * at or past the chip's count and orders at or past the order count are 0 */
#define OPN_OFF_INSTRUMENTS 8512 /* 64 x 48: slot n (1..64) at 8512 + 48 (n - 1) */
#define OPN_INSTRUMENT_SIZE 48
#define OPN_OFF_SAMPLES 11584    /* 64 x 16: ADPCM-B sample k (0..63) at 11584 + 16 k; unused entries 0 */
#define OPN_SAMPLE_SIZE 16
#define OPN_OFF_DATA 12608       /* data region: patterns and SSG envelopes */
#define OPN_DATA_CAPACITY (OPN_IMAGE_SIZE - OPN_OFF_DATA) /* 52928 */
#define OPN_OFF_ADPCM 65536      /* YM2608: the ADPCM-B memory, byte 0 = chip address 0; unused bytes 0 */

/* Instrument record (48 bytes). Byte 0: flags = OPN_FLAG_DEFINED | kind; all bytes of an undefined slot are 0 and
 * every byte a kind does not name is 0. */
#define OPN_I_FLAGS 0
#define OPN_FLAG_DEFINED 0x80
#define OPN_KIND_MASK 0x03
enum { OPN_KIND_FM = 0, OPN_KIND_SSG = 1, OPN_KIND_RHYTHM = 2, OPN_KIND_ADPCM = 3 }; /* = the OPN_PART_* it plays on */
/* FM sound (FM channels). */
#define OPN_I_ALG 1          /* algorithm 0..7 */
#define OPN_I_FEEDBACK 2     /* operator 1 feedback 0..7 */
#define OPN_I_AMS_FMS 3      /* YM2608 register $B4 without pan: AMS bits 4-5, FMS bits 0-2; YM2203: 0 */
#define OPN_I_OPS 4          /* operator k (0..3 = operators 1..4) at 4 + 7k: $30 $40 $50 $60 $70 $80 $90 (YM2203: $60
                              * bit 7, the LFO AM enable, is 0) */
#define OPN_OP_BYTES 7
#define OPN_I_CH3 32         /* 4 x s16: operator k's pitch offset in pitch units (1/64 semitone), -3072..3072: used on
                              * FM 3 in the special and CSM modes only */
#define OPN_CH3_LIMIT 3072
/* SSG sound (SSG channels). Envelopes are FamiStudio-style sequences, one value per tick, in the data region. */
#define OPN_I_SSG_FLAGS 1    /* OPN_SSG_PITCH_RELATIVE; other bits 0 */
#define OPN_SSG_PITCH_RELATIVE 0x01 /* the pitch envelope accumulates every tick instead of offsetting */
#define OPN_I_SSG_SHAPE 2    /* hardware envelope: 0 none, $08..$0F = the shape register $0D value */
#define OPN_I_SSG_AUTO 3     /* 1: the envelope period follows the note (tone period >> octave); 0: manual period */
#define OPN_I_SSG_OCTAVE 4   /* s8 -8..8: auto period = tone period >> octave (<< -octave), clamped 1..65535 */
#define OPN_I_SSG_PERIOD 5   /* u16 manual envelope period 0..65535 ($0B/$0C) */
/* SSG envelope offsets: u16 data offsets, or OPN_NO_ENVELOPE when the sound has none (then the value named here). */
#define OPN_I_ENV_VOLUME 8   /* volume envelope (0..15); none = constant 15 */
#define OPN_I_ENV_ARPEGGIO 10 /* arpeggio envelope (-64..63 semitones); none = no offset */
#define OPN_I_ENV_PITCH 12   /* pitch envelope (-64..63 x OPN_PITCH_UNIT); none = no offset */
#define OPN_I_ENV_MIXER 14   /* mixer envelope (OPN_MIX_*); none = tone only */
#define OPN_I_ENV_NOISE 16   /* noise envelope (shared noise period 0..31); none = untouched */
#define OPN_NO_ENVELOPE 0xFFFF
enum { OPN_MIX_BOTH = 0, OPN_MIX_NOISE = 1, OPN_MIX_TONE = 2, OPN_MIX_NONE = 3 };
/* Envelope: u16 length 1..OPN_ENV_MAX_LENGTH, u16 loop index (< length, OPN_NO_ENVELOPE = none), u16 release index
 * (volume only, OPN_NO_ENVELOPE = none; needs a loop, loop < release < length), then `length` signed bytes. */
#define OPN_ENV_HEADER 6
#define OPN_ENV_MAX_LENGTH 256
#define OPN_PITCH_UNIT 4     /* pitch envelope step in pitch units: 1/16 semitone */
/* Rhythm sound (YM2608 rhythm channels: the channel's own drum). */
#define OPN_I_RHYTHM_LEVEL 1 /* instrument level 0..31 ($18-$1D bits 0-4) */
#define OPN_I_PAN 2          /* rhythm and ADPCM: bit 7 left, bit 6 right (at least one set) */
/* ADPCM-B sound (YM2608 ADPCM channel). */
#define OPN_I_ADPCM_LEVEL 3  /* level 0..255 ($10B) */
#define OPN_I_ADPCM_SAMPLE 4 /* sample table index < OPN_H_SAMPLES */
#define OPN_I_ADPCM_ROOT 5   /* absolute note 0..95 at which the sample plays at its recorded rate */
#define OPN_I_ADPCM_FLAGS 6  /* OPN_ADPCM_LOOP; other bits 0 */
#define OPN_ADPCM_LOOP 0x01  /* repeat ($100 bit 4): the sample loops from its start until key off */
/* FM, SSG and ADPCM sounds. */
#define OPN_I_SWEEP_START 40 /* s8 semitones relative to the note, -96..96 */
#define OPN_I_SWEEP_END 41   /* s8 semitones, -96..96 */
#define OPN_I_SWEEP_SPEED 42 /* u8 1/16 semitones per tick toward the end; 0 = no sweep (start/end then 0) */
#define OPN_SWEEP_LIMIT 96

/* ADPCM-B sample (16 bytes). The chip runs in 8-bit DRAM mode ($101 bit 1): start/end registers count 32 bytes. */
#define OPN_S_START 0 /* u32 byte address in the ADPCM-B memory, a multiple of 32 */
#define OPN_S_END 4   /* u32 byte address past the last byte, a multiple of 32, > start, <= OPN_H_ADPCM_SIZE */
#define OPN_S_RATE 8  /* u32 recorded rate in Hz, OPN_RATE_MIN..OPN_RATE_MAX; bytes 12..15 are 0 */
#define OPN_ADPCM_ALIGN 32
#define OPN_RATE_MIN 1000
#define OPN_RATE_MAX 55466 /* delta-N at the root note stays <= 65535 */

/* Pattern rows. A run byte 0x80 | k = k + 1 empty rows (k 0..127). Otherwise a mask byte 0x01..0x13:
 * bit 0 note byte follows, bit 1 instrument byte follows, bits 2-4 effect count 0..4; then the note (0..95 = C-0..B-7,
 * absolute; OPN_NOTE_OFF = key off), the instrument slot (1..64), and per effect column in order the effect code
 * (0..35 = '0'..'9', 'A'..'Z') and its parameter. A row's instrument must be of the channel's kind (or 0). */
#define OPN_NOTE_OFF 0xFE
#define OPN_NOTES 96
#define OPN_SSG_LOWEST 11 /* B-0: lower SSG notes need a tone period above 4095 */

/* Effect codes ('0'..'9' = 0..9, 'A'..'Z' = 10..35) and the channels they load on (docs/AUDIO_ENGINE.md has the
 * semantics). Global effects (B, D, F, L, R, T) load on every channel. */
enum {
    OPN_FX_ARPEGGIO = 0,      /* 0xy  FM SSG ADPCM */
    OPN_FX_SLIDE_UP = 1,      /* 1xx  FM SSG ADPCM */
    OPN_FX_SLIDE_DOWN = 2,    /* 2xx  FM SSG ADPCM */
    OPN_FX_PORTAMENTO = 3,    /* 3xx  FM SSG ADPCM */
    OPN_FX_VIBRATO = 4,       /* 4xy  FM SSG ADPCM */
    OPN_FX_PAN = 8,           /* 8xy  YM2608 FM, rhythm, ADPCM */
    OPN_FX_VOLUME_SLIDE = 10, /* Axy  all parts */
    OPN_FX_JUMP = 11,         /* Bxx  global */
    OPN_FX_VOLUME = 12,       /* Cxx  FM 00-7F, SSG 00-0F, rhythm 00-1F, ADPCM 00-FF */
    OPN_FX_BREAK = 13,        /* Dxx  global */
    OPN_FX_FEEDBACK = 14,     /* Exx  FM lock 0-7 */
    OPN_FX_SPEED = 15,        /* Fxx  global, 01-FF */
    OPN_FX_DELAY = 16,        /* Gxx  all parts */
    OPN_FX_ENV_SHAPE = 17,    /* Hxx  SSG lock: hardware envelope 00 none or 08-0F */
    OPN_FX_BRIGHTNESS = 18,   /* Ixx  FM lock 00-7F */
    OPN_FX_ENV_PERIOD = 19,   /* Jxx  SSG lock: manual hardware envelope period xx * 256 (01-FF) */
    OPN_FX_LFO = 21,          /* Lxx  global, YM2608 only: 00 or 08-0F */
    OPN_FX_MULT = 22,         /* Mxy  FM lock: operator x 1-4, multiplier y */
    OPN_FX_NOISE = 23,        /* Nxx  SSG: shared noise period 00-1F from this row */
    OPN_FX_FINE = 25,         /* Pxx  FM SSG ADPCM: 80 = none */
    OPN_FX_RHYTHM_LEVEL = 27, /* Rxx  global, YM2608 only: rhythm total level 00-3F */
    OPN_FX_CUT = 28,          /* Sxx  all parts */
    OPN_FX_TIMER_A = 29,      /* Txx  global: timer A = xx * 4 (CSM rate) */
};

typedef struct OpnInfo {
    int loaded;          /* OPN_LOADED when valid */
    const char* problem; /* why opn_load_image rejected the image (static string), else NULL */
    int problem_offset;  /* image offset of the offending byte, or -1 */
    int chip;
    int channels;
    int orders;
    int loop;
    int speed;
    int lfo;
    int fm3_mode;
    int timer_a;
    int timer_b;
    int rhythm_level;
    int instruments;
    int samples;
    uint32_t adpcm_size;
    int data_size;                         /* bytes of the data region the patterns and envelopes use */
    uint32_t tick_samples;                 /* native samples per tick: opn_tick_samples(timer_b) */
    uint16_t rows[OPN_MAX_ORDERS];         /* rows of each order, 1..256 */
    uint8_t start_speed[OPN_MAX_ORDERS];   /* ticks per row each order starts with in sequential straight play */
    uint8_t uses_rhythm;                   /* 1 when any pattern of a played order has a note on a rhythm channel */
} OpnInfo;

#define OPN_LOADED 0x4F504E4C

/* A loaded image. YM2203 images use bytes[0 .. OPN_IMAGE_SIZE - 1] only. Machines copy only what the player and chip
 * read (opn_copy_image): the header and tables, the used data region and, when it changed, the used ADPCM-B memory. */
typedef struct OpnImage {
    OpnInfo info;
    uint8_t bytes[OPNA_IMAGE_SIZE];
} OpnImage;

/* One decoded pattern row of a channel. */
typedef struct OpnRow {
    int note;       /* -1 none, 0..95, OPN_NOTE_OFF */
    int instrument; /* 0 none, 1..64 */
    int effects;    /* 0..4 */
    uint8_t code[OPN_MAX_EFFECTS];
    uint8_t param[OPN_MAX_EFFECTS];
} OpnRow;

/* Pattern cursor of one channel: decodes rows sequentially from an offset. */
typedef struct OpnCursor {
    int offset; /* data region offset of the next byte */
    int run;    /* empty rows left in the current run */
} OpnCursor;

/* Image size of a chip's images (OPN_IMAGE_SIZE or OPNA_IMAGE_SIZE). */
static inline size_t opn_image_size(int chip) { return chip == OPN_CHIP_YM2608 ? OPNA_IMAGE_SIZE : OPN_IMAGE_SIZE; }

/* Validates image->bytes as an image of `chip` (the first opn_image_size(chip) bytes; the rest of bytes[] is ignored)
 * and fills image->info. Returns 0 or -1 (info.problem says why). */
int opn_load_image(OpnImage* image, int chip);
/* Copies a loaded image into `dest` for playing (realtime-safe, no allocation): the info, the header and fixed tables
 * and the used data region (OPN_OFF_DATA + data_size bytes), and the used ADPCM-B memory only where `dest` holds other
 * bytes (compared exactly with memcmp; a live edit that kept the samples writes none of them). `dest` bytes past those
 * are left as they were: nothing reads them (the chip reads ADPCM-B up to adpcm_size only). */
void opn_copy_image(OpnImage* dest, const OpnImage* src);

/* Decodes the next row at the cursor (no bounds checks: loaded images only). */
void opn_next_row(const uint8_t* bytes, OpnCursor* cursor, OpnRow* row);
/* Decodes row `row` of (order, channel) from the pattern start. */
void opn_read_row(const OpnImage* image, int order, int channel, int row, OpnRow* out);
/* The pattern cursor at row 0 of (order, channel). */
OpnCursor opn_pattern(const OpnImage* image, int order, int channel);
/* The instrument record of a slot if it is defined and of `kind` (OPN_KIND_*, -1 any), else NULL. */
const uint8_t* opn_instrument(const OpnImage* image, int slot, int kind);
/* The 16-byte sample table entry k (< info.samples), else NULL. */
const uint8_t* opn_sample(const OpnImage* image, int k);

static inline int opn_u16(const uint8_t* p) { return p[0] | p[1] << 8; }
static inline uint32_t opn_u32(const uint8_t* p) {
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_OPN_IMAGE_H_ */
