/*
 * cbm_vic.h - the MOS 6560/6561 VIC's sound part (three square voices, one noise voice, one master volume), adapted
 * from VICE's src/vic20/vic20sound.c (Rami Rasanen, Ville-Matias Heikkila) for Thor Tracker; see cbm_vic.c for the
 * licence (GPL-2.0-or-later), the pin and what was changed.
 *
 * The unit is clocked in VIC-20 phi2 cycles. Register writes and the output level are handed to blip_buf at their
 * cycle (relative to the current blip frame); muting suppresses output only. No allocation.
 */
#ifndef THORTRACKER_CBM_VIC_H_
#define THORTRACKER_CBM_VIC_H_

#include <stdint.h>

#include "blip_buf.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct CbVicVoice {
    int32_t ctr;     /* phi2 cycles to the next counter expiry (VICE's signed short ctr) */
    uint8_t reg;     /* $900A + j: bit 7 enable, bits 0-6 period register */
    uint8_t shift;   /* 8-bit Johnson counter */
    uint8_t out;     /* the voice's output bit */
} CbVicVoice;

typedef struct CbVic {
    CbVicVoice ch[4];   /* bass, alto, soprano, noise */
    uint16_t lfsr;      /* noise LFSR */
    uint8_t lfsr0_old;
    uint8_t volume;     /* $900E bits 0-3 */
    unsigned mute;      /* bit j: voice j left out of the output */
    int level;          /* the level last handed to blip_buf */
} CbVic;

/* Reset state: registers, counters, shift registers and outputs 0, LFSR $FFFF with lfsr0_old 1, mutes cleared, level 0. */
void cbv_reset(CbVic* v);
/* Write register `reg` ($0A..$0E, the address's low byte) at blip time `time`. */
void cbv_write(CbVic* v, blip_t* blip, unsigned time, int reg, int val);
/* Clocks the cycles [from, to) of the current blip frame. */
void cbv_run(CbVic* v, blip_t* blip, unsigned from, unsigned to);
/* Sets the mute mask (bit j = voice j) at blip time `time`. */
void cbv_mute(CbVic* v, blip_t* blip, unsigned time, unsigned mask);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_CBM_VIC_H_ */
