/* Thor Tracker's VIC-20 / C16-Plus/4 player: sequences an image (cbm_image.h) and produces register writes for the
 * VIC's sound part ($900A-$900E) or the TED's ($FF0E-$FF12), one batch per tick and, on the TED, one per DAC timer
 * interrupt. Pure integer code without a chip: the machine (cbm_machine.c) sends the writes to the chip unit
 * (cbm_vic.c / cbm_ted.c) and seeking runs it alone. The behaviour is specified in docs/AUDIO_ENGINE.md ("VIC-20 and
 * C16/Plus/4", "Player"); tools/audio/cbm/cbm_ref.py is an independent Python implementation of the same
 * specification, and the host test compares their writes batch by batch.
 *
 * Writes are (register, value) pairs, register = the address's low byte ($0A..$0E VIC, $0E..$12 TED). Every register
 * has a shadow (the value last written); a write equal to its shadow is not emitted. The player keeps no pointer to an
 * image; every call takes the (copied, loaded) image it should read. No allocation. */
#ifndef THORTRACKER_CBM_PLAYER_H_
#define THORTRACKER_CBM_PLAYER_H_

#include <stdint.h>

#include "cbm_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define CBP_MAX_WRITES 32     /* per batch: the worst tick writes every register once (VIC 5, TED 5) plus a stop */
#define CBP_PITCH_MAX 8191    /* pitch units (1/64 semitone, 0 = C-0); pitches are clamped to 0..CBP_PITCH_MAX */
#define CBP_SLIDE_LIMIT 8192  /* slide offsets and the relative pitch accumulator stay within +-this */
#define CBP_SHADOWS 0x13      /* register low bytes $00..$12 */

/* cbm_tables.c (checked by tools/audio/prepare_cbm.py): periods of the C-0 octave in 16.16 per variant
 * (CB_VARIANT_*), the sample step over one octave and the vibrato sine. */
extern const uint32_t CBP_PERIOD_Q16[4][768];
extern const uint32_t CBP_STEP_Q16[768];
extern const int8_t CBP_SINE[64];

typedef struct CbWrite {
    uint8_t reg;
    uint8_t val;
} CbWrite;

enum { CBP_SLIDE_NONE = 0, CBP_SLIDE_UP = 1, CBP_SLIDE_DOWN = 2, CBP_SLIDE_PORTA = 3 };

typedef struct CbChannel {
    /* row */
    CbRow row;          /* this row's data (read at the row start) */
    int pending;        /* row delayed by Gxx: run at tick `delay` */
    int delay;
    int cut;            /* Sxx: OFF at this tick of the row, -1 none */
    /* note */
    int instrument;     /* slot for the next note, 0 none */
    int slot;           /* the sounding note's slot (latched at key-on) */
    int sounding;
    int fresh;          /* keyed since the last update (the DAC lane writes frame 0) */
    int released;
    int note;           /* the keyed note (VIC noise lane: the rate index) */
    uint64_t pos;       /* DAC lane: sample position, 16.16 frames */
    uint32_t step;      /* DAC lane: frames per timer interrupt, 16.16 (the last update's) */
    int env[CB_ENVELOPE_COUNT]; /* envelope indices */
    int rel_pitch;      /* relative pitch envelope accumulator */
    int base, target;   /* pitch units */
    int fine;           /* Pxx (audition: the fine argument) */
    int arp, arp_step;  /* 0xy */
    int slide_mode, slide_speed, slide; /* CBP_SLIDE_* */
    int vib_speed, vib_depth, vib_phase;
    int wave_override;  /* Wxx, -1 none */
    int wave;           /* the wave of the last update (the TED control step) */
} CbChannel;

typedef struct CbPlayer {
    int chip;                    /* CB_CHIP_* of the image the player was reset with */
    int channels;
    int order, row, tick, speed;
    int jump;                    /* row start saw B/D: next position is (jump_order, jump_row) */
    int jump_order, jump_row;
    int audition;                /* audition mode: no sequencing */
    CbChannel ch[CB_MAX_CHANNELS];
    /* shared state */
    int m8;                      /* master volume M x 8 (Vxx, Axy) */
    int volslide;                /* Axy parameter */
    int timer;                   /* DAC timer T (no register: the machine's timer reloads 64 + 4 T) */
    int owner;                   /* master volume envelope owner lane, -1 none */
    int owner_value;             /* the owner's volume envelope value of its last update */
    int vol_out;                 /* the volume of the last control step */
    int dac;                     /* D/A mode held by the DAC lane */
    int dac_level;               /* the last frame written in D/A mode */
    uint8_t shadow[CBP_SHADOWS];
    CbCursor cursor[CB_MAX_CHANNELS];
    int cursor_order, cursor_row; /* cursors are at (cursor_order, cursor_row), -1 = invalid */
    int writes;
    CbWrite write[CBP_MAX_WRITES];
} CbPlayer;

/* Reset: all state cleared, shared state from the header, position (0, 0, 0) with the header speed; write[] = the
 * reset batch (VIC $0A..$0D = 0, $0E = M; TED $0E, $12, $0F, $10 = 0, $11 = M), setting the shadows. */
void cbp_reset(CbPlayer* p, const CbmImage* image);
/* One tick: write[] receives its writes (the previous batch is dropped). */
void cbp_tick(CbPlayer* p, const CbmImage* image);
/* One TED timer interrupt: write[] receives its writes ($11 = the next DAC frame, or the control value at a one-shot's
 * end; nothing on the VIC or without D/A mode). */
void cbp_timer(CbPlayer* p, const CbmImage* image);
/* Reset, then start from (order, row 0) with the speed straight play starts that order with (seek fallback). */
void cbp_place(CbPlayer* p, const CbmImage* image, int order);
/* Live edit at a tick boundary: positions kept (clamped), rows re-read from `next`, shared fields whose header value
 * changed take the new value (docs: "Position, seek and live edits"). */
void cbp_relocate(CbPlayer* p, const CbmImage* old, const CbmImage* next);
/* Audition: reset, then key on `note` (inside the lane's range) on `channel` with instrument `slot` and fine pitch
 * `fine` (pitch units, -128..127); ticks only update and run the control step. Returns 0 (write[] = the reset batch)
 * or -1 (bad channel or note, a slot that is undefined or not allowed on the lane). */
int cbp_audition(CbPlayer* p, const CbmImage* image, int channel, int note, int slot, int fine);
/* OFF on a channel (audition release) as a new batch in write[] (call between ticks): a stop writes at once (VIC: the
 * voice register; TED: the control value). */
void cbp_off(CbPlayer* p, const CbmImage* image, int channel);

/* The chip period of a pitch (clamped to 0..CBP_PITCH_MAX) for variant v (CB_VARIANT_*) and octave shift (VIC alto 1,
 * bass 2, else 0): VIC a in 1..128 (register (127 - a) & 127), TED reload P in 2..1024 (register (1023 - P) & $3FF). */
int cbp_period(int variant, int pitch, int shift);
/* Sample frames per timer interrupt in 16.16 for a pitch (clamped) and a root note: the root plays 65536. */
uint32_t cbp_sample_step(int pitch, int root_note);
/* Single clocks from one TED timer interrupt to the next with the player's T: 64 + 4 T. */
int cbp_timer_cycles(const CbPlayer* p);
/* TED $11 as the control step composes it, with the volume of the last control step. */
int cbp_ted_control(const CbPlayer* p);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_CBM_PLAYER_H_ */
