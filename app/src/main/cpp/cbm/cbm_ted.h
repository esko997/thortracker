/*
 * cbm_ted.h - the MOS 7360/8360 TED's sound part (two square voices, voice 2 alternatively noise, one volume, the D/A
 * mode), adapted from plus4emu's TED7360 sound code (Istvan Varga) for Thor Tracker; see cbm_ted.c for the licence
 * (GPL-2.0-or-later), the pin and what was changed.
 *
 * The unit is clocked in TED single clocks; the sound generators step every fourth one (plus4emu's cycle_count phase).
 * Register writes act at the next sound step; each output change is handed to blip_buf at its single clock (relative
 * to the current blip frame). Muting suppresses output only. No allocation.
 */
#ifndef THORTRACKER_CBM_TED_H_
#define THORTRACKER_CBM_TED_H_

#include <stdint.h>

#include "blip_buf.h"

#ifdef __cplusplus
extern "C" {
#endif

#define CBT_SOUND_DECAY_CYCLES 0x02E000u /* plus4emu's soundDecayCycles, in sound clock cycles */

typedef struct CbTed {
    uint8_t regs[0x13];            /* tedRegisters $0E..$12 as last written */
    uint8_t cycle_count;           /* single clocks to the next sound step (0..3) */
    uint16_t soundChannel1Cnt;     /* counts from soundChannel1Reload to 0 */
    uint16_t soundChannel1Reload;  /* 1 to 1024 */
    uint16_t soundChannel2Cnt;
    uint16_t soundChannel2Reload;
    uint8_t prvSoundChannel1Overflow;
    uint8_t prvSoundChannel2Overflow;
    uint32_t soundChannel1Decay;   /* set the state to 1 after soundDecayCycles sound cycles (frequency $3FE) */
    uint32_t soundChannel2Decay;
    uint8_t soundChannel1State;
    uint8_t soundChannel2State;
    uint8_t soundChannel2NoiseState;
    uint8_t prvCycleCount;         /* used when resetting the shift register */
    uint8_t soundFlags;            /* ($FF11 & $F0) >> 1 */
    uint8_t soundVolume;           /* 0 to 75 */
    uint8_t soundOutput;           /* 0 to 150 (plus4emu's, unmuted) */
    uint8_t prvSoundOutput;
    /* output plumbing (not plus4emu state) */
    uint8_t mixIndex;              /* soundMixTable index of the last updateSoundOutput */
    uint8_t heard, prvHeard;       /* soundOutput / prvSoundOutput with the mutes applied */
    unsigned mute;                 /* bit 0 voice 1, bit 1 voice 2, bit 2 the DAC lane (D/A mode) */
    int level;                     /* the output last handed to blip_buf */
} CbTed;

/* plus4emu's TED7360::reset sound part: counters and reloads $3FF, states 1, noise $FF, decays soundDecayCycles,
 * flags, volume and outputs 0, cycle_count 0, prvCycleCount 0; registers 0, mutes cleared, level 0. */
void cbt_reset(CbTed* t);
/* Write register `reg` ($0E..$12, the address's low byte; other values ignored) before single clock `now` runs. */
void cbt_write(CbTed* t, int reg, int val);
/* Clocks the single clocks [from, to) of the current blip frame. */
void cbt_run(CbTed* t, blip_t* blip, unsigned from, unsigned to);
/* Sets the mute mask (bit 0 voice 1, bit 1 voice 2, bit 2 DAC); heard from the next sound step. */
void cbt_mute(CbTed* t, unsigned mask);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_CBM_TED_H_ */
