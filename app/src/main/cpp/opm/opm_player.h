/* Thor Tracker's OPM player: sequences an image (opm_image.h) and produces YM2151 register writes, one batch per
 * tick. Pure integer code without a chip: the machine (opm_machine.c) sends the writes to ymfm's YM2151, the VGM
 * export (opm_vgm.c) logs them, and seeking runs it alone. The behaviour is specified in docs/AUDIO_ENGINE.md
 * ("OPM: player"); tools/audio/opm/opm_ref.py is an independent Python implementation of the same specification, and
 * the host test compares their writes.
 *
 * Writes are diffed against a shadow of the 256 registers (chip reset state = all 0), with two exceptions: register
 * $19 holds two values (AMD when bit 7 of the written byte is clear, PMD when set), shadowed separately (PMD in the
 * unused slot $1A, so a write record is always the byte the chip receives: reg $19, value $80 | PMD); and the key
 * register $08 is a command, not shadowed: the player writes it when a channel's key state changes (OMP keyed[]).
 * A batch has two phases: first every $08 write that keys a channel off (operator mask 0) in the order made
 * (write[0 .. offs - 1]), then the rest. The chip must run at least one sample between the phases (the machine runs one
 * native sample, the VGM waits one 44.1 kHz sample): the YM2151 samples key-on once per sample, so a key-off
 * immediately followed by a key-on would not retrigger the envelope. The player keeps no pointer to an image; every
 * call takes the (copied, loaded) image it should read. No allocation. */
#ifndef THORTRACKER_OPM_PLAYER_H_
#define THORTRACKER_OPM_PLAYER_H_

#include <stdint.h>

#include "opm_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define OMP_MAX_WRITES 512    /* per tick; the worst case (8 key-ons with every register changing, globals) is below 300 */
#define OMP_PITCH_OFFSET 123  /* 1/64 semitones the 4 MHz chip sounds above the 3.579545 MHz key code table:
                                 1200 log2(4000000 / 3579545) = 192.27 cents = 123.05 units */
#define OMP_PITCH_MIN 187     /* pitch units (1/64 semitone, 0 = C-0): key code C#0, fraction 0, at 4 MHz */
#define OMP_PITCH_MAX 6330    /* key code C-8, fraction 63, at 4 MHz */
#define OMP_KEY_ALL 0x78      /* $08 bits 3-6: operators 1 (M1), 2 (C1), 3 (M2), 4 (C2) */
#define OMP_SHADOW_PMD 0x1A   /* shadow slot of $19's PMD value */

typedef struct OmWrite {
    uint8_t reg;
    uint8_t val;
} OmWrite;

typedef struct OmChannel {
    /* row */
    OmRow row;          /* this row's data (read at the row start) */
    int pending;        /* row delayed by Gxx: processed at tick `delay` */
    int delay;
    int cut;            /* Sxx: key off at this tick of the row, -1 none */
    /* note */
    int instrument;     /* slot for the next note, 0 none */
    int active;         /* a note has been keyed since reset (pitch/volume updates run) */
    int keyed;          /* key state as written to $08 (all four operators) */
    int keyon;          /* a key-on is pending: the update writes $08 = $78 | channel after the pitch */
    uint8_t ops[4][6];  /* the playing note's operator registers $40, $60, $80, $A0, $C0, $E0 (locks applied, TL without volume) */
    int connect;        /* $20 bits 0-5: feedback << 3 | algorithm of the playing note (locks applied) */
    int sens;           /* $38: PMS << 4 | AMS of the playing note (Hxy applied) */
    int carriers;       /* bit k = operator k + 1 is a carrier */
    int base, target;   /* pitch units */
    int fine;
    int arp, arp_step;
    int slide_mode, slide_speed, slide; /* 0 none, 1 up, 2 down, 3 portamento */
    int vib_speed, vib_depth, vib_phase;
    int sweep, sweep_end, sweep_step;
    int vol8;           /* volume x 8, 0..1016 */
    int volslide;       /* Axy param */
    int pan;            /* $20 bits 6 (left) and 7 (right) */
} OmChannel;

typedef struct OmPlayer {
    int order, row, tick, speed;
    int jump;                    /* row start saw B/D: next position is (jump_order, jump_row) */
    int jump_order, jump_row;
    int audition;                /* audition mode: no sequencing, CSM off */
    OmChannel ch[OM_CHANNELS];
    /* the chip's shared state as the player sets it (rewritten every tick, emitted only on change) */
    int lfo_rate, lfo_wave, amd, pmd, csm, timer_a;
    int noise;                   /* register $0F: bit 7 noise on FM 8's operator 4, bits 0-4 NFRQ */
    OmCursor cursor[OM_CHANNELS];
    int cursor_order, cursor_row; /* cursors are at (cursor_order, cursor_row), -1 = invalid */
    uint8_t shadow[256];
    int writes;
    int offs;                    /* write[0 .. offs - 1] is the key-off phase */
    OmWrite write[OMP_MAX_WRITES];
    int pending_offs;            /* key-off writes of the batch being built */
    OmWrite off[OM_CHANNELS];
} OmPlayer;

/* Reset: shadow = chip reset state (all 0), then the init writes ($20-$27 = $C0, the header's shared state) are in
 * write[]. Position (0, 0, 0) with the header speed. */
void omp_reset(OmPlayer* p, const OpmImage* image);
/* One tick: write[] receives its writes (the previous batch is dropped). */
void omp_tick(OmPlayer* p, const OpmImage* image);
/* Start playing from (order, row 0) with the speed straight play starts that order with (seek fallback). */
void omp_place(OmPlayer* p, const OpmImage* image, int order);
/* Live edit at a tick boundary: positions kept (clamped), rows re-read from `next`, shared state fields whose header
 * value changed take the new value (see the spec). */
void omp_relocate(OmPlayer* p, const OpmImage* old, const OpmImage* next);
/* Audition: reset (CSM off), then key on `note` (OM_NOTE_MIN..OM_NOTE_MAX) on `channel` with instrument `slot` and fine
 * pitch `fine` (pitch units, -128..127); ticks only update pitch/volume. Returns 0 or -1 (bad arguments: a noise
 * instrument off FM 8 included). */
int omp_audition(OmPlayer* p, const OpmImage* image, int channel, int note, int slot, int fine);
/* Key off a channel (audition release) as a new batch in write[] (call between ticks). */
void omp_key_off(OmPlayer* p, int channel);

/* Key code ($28) and key fraction ($30, already << 2) of a pitch (clamped to OMP_PITCH_MIN..OMP_PITCH_MAX). */
void omp_key_code(int pitch, int* kc, int* kf);
/* Register offset of operator k (0..3 = operators 1..4 = M1, C1, M2, C2) of channel c within an operator bank
 * ($40, $60, ...): 8 * slot + c with slot = 0, 2, 1, 3. */
int omp_operator_offset(int channel, int k);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_OPM_PLAYER_H_ */
