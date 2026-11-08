/* Thor Tracker's Atari 2600 player: sequences an image (tia_image.h) and produces TIA audio register writes, one batch
 * per tick (one TV frame). Pure integer code without a chip: the machine (tia_machine.c) sends the writes to Stella's
 * audio channels and seeking runs it alone. The behaviour is specified in docs/AUDIO_ENGINE.md ("Atari 2600:
 * Player"); tools/audio/tia/tia_ref.py is an independent Python implementation of the same specification, and the
 * host test compares their writes.
 *
 * Writes are (register, value) pairs with the TIA's own addresses: TI_REG_AUDC0/1 ($15/$16), TI_REG_AUDF0/1
 * ($17/$18), TI_REG_AUDV0/1 ($19/$1A). Shadows (the value last written) for all six; a write equal to its shadow is
 * not emitted. Per channel an update writes AUDC, AUDF, AUDV in that order. The player keeps no pointer to an image;
 * every call takes the (copied, loaded) image it should read. The region (which AUDF edge table) is the image
 * header's, taken at the reset. No allocation. */
#ifndef THORTRACKER_TIA_PLAYER_H_
#define THORTRACKER_TIA_PLAYER_H_

#include <stdint.h>

#include "tia_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define TIP_MAX_WRITES 32 /* per batch; the most a tick writes is 6 */
#define TIP_PITCH_MIN 0 /* pitch units: 1/64 semitone, 0 = C-0 */
#define TIP_PITCH_MAX 7679 /* B-9 + 63/64 */
#define TIP_SLIDE_LIMIT 7680 /* slide offsets and the relative pitch accumulator stay within +-this */
#define TIP_CLASSES 7 /* waveform period classes: P = 2, 6, 15, 31, 93, 465, 511 */
#define TIP_EDGES 31 /* edges between the 32 dividers AUDF 0..31 */

/* AUDC -> period class (index into TIP_PERIOD), -1 = constant output (AUDC 0, B: no AUDF write in tonal mode). */
extern const int8_t TIP_CLASS[16];
/* Audio clocks per waveform repetition at AUDF 0: the fundamental is A / (P (AUDF + 1)). */
extern const uint16_t TIP_PERIOD[TIP_CLASSES];
/* TIP_AUDF_EDGE[r][c][k] = ceil(768 log2(A_r / (P_c C0 sqrt((k + 1)(k + 2))))): the lowest pitch closer to divider
 * k + 1 than to k + 2 (docs/AUDIO_ENGINE.md "Pitch"). */
extern const int16_t TIP_AUDF_EDGE[TI_REGIONS][TIP_CLASSES][TIP_EDGES];
/* floor(127 sin(2 pi i / 64) + 0.5): the player's vibrato (the OPL3's table). */
extern const int8_t TIP_SINE[64];

typedef struct TiWrite {
    uint8_t reg;
    uint8_t val;
} TiWrite;

typedef struct TiChannel {
    /* row */
    TiRow row;          /* this row's data (read at the row start) */
    int pending;        /* row delayed by Gxx: run at tick `delay` */
    int delay;
    int cut;            /* Sxx: OFF at this tick of the row, -1 none */
    /* note */
    int instrument;     /* slot for the next note, 0 none */
    int slot;           /* the sounding note's instrument slot (latched at key-on) */
    int sounding;
    int released;
    int env[TI_ENVELOPE_COUNT]; /* envelope indices */
    int rel_pitch;      /* relative pitch envelope accumulator */
    int base, target;   /* pitch units */
    int fine;           /* Pxx (or the audition's fine), pitch units */
    int arp, arp_step;  /* 0xy */
    int slide_mode, slide_speed, slide; /* 0 none, 1 up, 2 down, 3 portamento */
    int vib_speed, vib_depth, vib_phase;
    int vol8;           /* Cxx volume x 8, 0..120 */
    int volslide;       /* Axy param */
    int wave_override;  /* Wxx, -1 none */
    /* shadows of the channel's registers */
    uint8_t audc, audf, audv;
} TiChannel;

typedef struct TiPlayer {
    int region;                  /* TI_REGION_* of the image the player was reset with */
    int order, row, tick, speed;
    int jump;                    /* row start saw B/D: next position is (jump_order, jump_row) */
    int jump_order, jump_row;
    int audition;                /* audition mode: no sequencing */
    TiChannel ch[TI_CHANNELS];
    TiCursor cursor[TI_CHANNELS];
    int cursor_order, cursor_row; /* cursors are at (cursor_order, cursor_row), -1 = invalid */
    int writes;
    TiWrite write[TIP_MAX_WRITES];
} TiPlayer;

/* Reset: all state cleared, position (0, 0, 0) with the header speed; write[] = the reset batch ($15 0, $17 0, $19 0,
 * $16 0, $18 0, $1A 0). */
void tip_reset(TiPlayer* p, const TiaImage* image);
/* One tick: write[] receives its writes (the previous batch is dropped). */
void tip_tick(TiPlayer* p, const TiaImage* image);
/* Reset, then start from (order, row 0) with the speed straight play starts that order with (seek fallback). */
void tip_place(TiPlayer* p, const TiaImage* image, int order);
/* Live edit at a tick boundary: position kept (clamped), speed, a started row's jump and delayed rows re-read from
 * `next` (docs/AUDIO_ENGINE.md "Position, seek and live edits"). Sounding notes read their latched slot from the image
 * each tick; a slot no longer defined stops its note at the next update. Ignored in audition mode. */
void tip_relocate(TiPlayer* p, const TiaImage* old, const TiaImage* next);
/* Audition: reset, then key on `note` (TI_NOTE_MIN..TI_NOTE_MAX) on `channel` (0..1) with instrument `slot` (1..64,
 * defined) and fine pitch `fine` (pitch units, -128..127); ticks only update. Returns 0 (write[] = the reset batch)
 * or -1 (bad arguments or an undefined slot; the player is then unchanged). */
int tip_audition(TiPlayer* p, const TiaImage* image, int channel, int note, int slot, int fine);
/* OFF on a channel (audition release) as a new batch in write[] (call between ticks). */
void tip_off(TiPlayer* p, const TiaImage* image, int channel);

/* AUDF of a pitch for period class `cls` (0..TIP_CLASSES - 1) of `region`: the smallest k (0..30) with
 * pitch >= TIP_AUDF_EDGE[region][cls][k], else 31. */
int tip_audf(int region, int cls, int pitch);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_TIA_PLAYER_H_ */
