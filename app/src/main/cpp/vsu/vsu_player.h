/* Thor Tracker's Virtual Boy VSU player: sequences an image (vsu_image.h) and produces VSU register writes (word
 * indices, vsu_chip.h), one batch per tick. Pure integer code without a chip: the machine (vsu_machine.c) sends the
 * writes to the VSU core, the VGM export (vsu_vgm.c) logs them, and seeking counts ticks with it alone. The behaviour
 * is specified in docs/AUDIO_ENGINE.md ("Virtual Boy VSU: Player", "Effects"); tools/audio/vsu/vsu_ref.py is an
 * independent Python implementation of the same specification, and the host test compares their writes.
 *
 * Writes are diffed against a shadow of every register (word indices 0x000-0x160, chip reset state 0): a write equal
 * to the shadow is not emitted, except the "always" writes, which have side effects on the chip (SSTOP; SxFQL/SxFQH at
 * key-on and, on Wave 5, whenever the frequency changes; SxEV0; SxINT). The player keeps no pointer to an image;
 * every call takes the (copied, loaded) image it should read. No allocation. */
#ifndef THORTRACKER_VSU_PLAYER_H_
#define THORTRACKER_VSU_PLAYER_H_

#include <stdint.h>

#include "vsu_chip.h"
#include "vsu_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define VBP_MAX_WRITES 256          /* per tick; the reset batch is at most 1 + 160 + 32, a tick's below 80 (expected) */
#define VBP_PITCH_MAX (8 * 768 - 1) /* pitch units: 1/64 semitone, 0 = C-0 */
#define VBP_WAVE_PITCH_MIN (VB_WAVE_NOTE_MIN * 64) /* D#2: the lowest wave pitch with a frequency value >= 0 */
#define VBP_TICK_CLOCKS 100000u     /* one tick = one 50 Hz VIP frame of VSU clocks */

typedef struct VbWrite {
    uint16_t reg; /* word index */
    uint8_t val;
} VbWrite;

typedef struct VbLane {
    /* this row (overwritten at every row start) */
    VbRow row;      /* the row's data */
    int pending;    /* row delayed by Gxx: processed at tick `delay` */
    int delay;
    int cut;        /* Sxx: key off at this tick of the row, -1 none */
    /* lane */
    int instrument; /* slot for the next note, 0 none */
    int active;     /* a note has been keyed since reset (pitch updates run) */
    int keyed;
    int base, target; /* pitch units */
    int fine;
    int arp, arp_step;
    int slide_mode, slide_speed, slide; /* 0 none, 1 up, 2 down, 3 portamento */
    int vib_speed, vib_depth, vib_phase;
    int sweep, sweep_end, sweep_step;   /* software pitch sweep, pitch units */
    int stereo;     /* SxLRV for the lane's notes (8xy), start 0xFF */
} VbLane;

typedef struct VbPlayer {
    int order, row, tick, speed;
    int jump;                     /* row start saw B/D: next position is (jump_order, jump_row) */
    int jump_order, jump_row;
    int audition;                 /* audition mode: no sequencing */
    VbLane lane[VB_LANES];
    VbCursor cursor[VB_LANES];
    int cursor_order, cursor_row; /* cursors are at (cursor_order, cursor_row), -1 = invalid */
    uint8_t shadow[VSU_REGISTERS];
    int writes;
    VbWrite write[VBP_MAX_WRITES];
} VbPlayer;

/* Reset: player state cleared, shadow = chip reset state, then the reset batch is in write[]: SSTOP = 1, wave RAM and
 * modulation RAM from the image (diffed). Position (0, 0, 0) with the header speed. */
void vbp_reset(VbPlayer* p, const VsuImage* image);
/* One tick: write[] receives its writes (the previous batch is dropped). */
void vbp_tick(VbPlayer* p, const VsuImage* image);
/* Reset (with the reset batch), then (order, row 0) with the speed straight play starts that order with (seek
 * fallback). */
void vbp_place(VbPlayer* p, const VsuImage* image, int order);
/* Live edit at a tick boundary: positions kept (clamped), speed and the started row's jump and delays re-read from
 * `next` (docs: apply_image rules 1-3). Sounding notes keep what they were keyed with; the next key-on reads the
 * edited instrument by slot. Waves and modulation are the machine's (vbp_adopt_ram). */
void vbp_relocate(VbPlayer* p, const VsuImage* old, const VsuImage* next);
/* The shadow of wave and modulation RAM takes the image's values (the machine poked them into the chip): no writes. */
void vbp_adopt_ram(VbPlayer* p, const VsuImage* image);
/* Audition: reset (the reset batch stays in write[]), then key on `note` on `lane` with instrument `slot` and fine
 * pitch `fine` (1/64 semitone, -128..127, as Pxx); ticks only update pitch. Returns 0, or -1 for a lane outside 0..5,
 * a note outside the lane's range, an undefined slot or a fine value outside -128..127 (nothing changed). */
int vbp_audition(VbPlayer* p, const VsuImage* image, int lane, int note, int slot, int fine);
/* Key off a lane (audition release) as a new batch in write[] (call between ticks). */
void vbp_key_off(VbPlayer* p, int lane);

/* The 11-bit frequency value of a pitch (pitch units, clamped to the lane's range: wave lanes 1728..6143, Noise
 * 0..6143). Wave: 2048 - ((Q[f] + 2^(15+o)) >> (16+o)); Noise: 2048 - (Q[f] + 5 * 2^(16+o)) / (10 * 2^(16+o)). */
int vbp_frequency(int lane, int pitch);
/* VSU_PERIOD_Q[f], f 0..767 (octave 0 of 156250 / frequency in 1/65536 units). */
uint32_t vbp_period_q(int f);

/* The loop state at a row start (tick 0): every field that survives a row start - position, speed, audition flag,
 * the shadow, and per lane its instrument, flags, pitch and effect state and stereo. The row scratch
 * (decoded row, pending/delay/cut, the jump target), the decode cursors and the write batch are left out: the row
 * start of tick 0 overwrites them before anything reads them. */
int vbp_state_equal(const VbPlayer* a, const VbPlayer* b);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_VSU_PLAYER_H_ */
