/* AY-3-8910/8912 and YM2149 sound core for the AY systems (docs/AUDIO_ENGINE.md "AY-3-8910 / YM2149: chip core").
 * Written for Thor Tracker from the GI AY-3-8910/8912 data manual, the Yamaha YM2149 manual and the MSX2 Technical
 * Handbook; MAME's ay8910.cpp and ayumi were read for behaviour the manuals leave implicit. The DAC curves are
 * ayumi's measured tables (MIT; see ay_psg.c).
 *
 * The chip runs in steps (chip clock / 8): three tone counters, the noise counter with its 17-bit LFSR and the
 * envelope generator (AY: 16 levels, counter period 2 EP; YM: 32 levels, period EP). The three channel outputs are
 * mixed mono (all three), or as the Amstrad CPC's stereo socket wires them (left A + B, right B + C), and every change
 * of a mix goes to a blip_buf buffer as a band-limited delta at its step. With no buffer the chip advances
 * arithmetically to exactly the state stepping reaches (seeks). Registers R14/R15 (I/O) are not modelled. No
 * allocation; plain data (copyable with memcpy). */
#ifndef THORTRACKER_AY_PSG_H_
#define THORTRACKER_AY_PSG_H_

#include <stdint.h>

#include "blip_buf.h"

#ifdef __cplusplus
extern "C" {
#endif

#define AY_AMP 10922 /* mix units of one channel at DAC level 1.0: three full channels reach 32766 */

typedef struct AyPsg {
    uint8_t ym;            /* 1: YM2149 (32 levels, envelope divider 1); 0: AY-3-8910/8912 (16 levels, divider 2) */
    uint8_t stereo;        /* 0: one mono mix (A + B + C); 1: left A + B, right B + C */
    uint8_t mute;          /* bit c: channel c left out of the mix, both sides (every generator keeps running) */
    uint8_t reg[16];       /* R0-R13 as written (R14/R15 unused) */
    /* derived from the registers */
    uint32_t tone_period[3]; /* 1..4095 (0 acts as 1) */
    uint32_t noise_period;   /* 2 * (1..31): steps per LFSR step */
    uint32_t env_period;     /* (1..65535) * divider: steps per envelope step */
    uint8_t env_hold, env_alt; /* the shape's effective hold and alternate bits */
    /* generators */
    uint32_t tone_count[3];
    uint8_t tone_out[3];
    uint32_t noise_count;
    uint32_t lfsr;         /* 17 bits, seed 1; output = bit 0 */
    uint32_t env_count;    /* frozen while holding */
    int env_cnt;           /* position in the ramp: mask .. 0 (MAME's count) */
    int env_att;           /* 0 or mask: level = env_cnt ^ env_att */
    int env_mask;          /* 15 (AY) or 31 (YM) */
    uint8_t env_holding;
    int last;              /* the mono or left mix after the last step (what its blip buffer holds) */
    int last_r;            /* stereo: the right mix after the last step; mono: = last */
    int amp[32];           /* mix units per DAC level (AY 0..15, YM 0..31), from the chip type */
} AyPsg;

/* Power-on: registers 0, tone outputs low, LFSR 1, envelope holding at level 0. `stereo`: AY_STEREO_* of the
 * machine's wiring. */
void ay_psg_reset(AyPsg* c, int ym, int stereo);
/* A register write, effective from the next step. R13 (any value) restarts the envelope. */
void ay_psg_write(AyPsg* c, int reg, int value);
/* Runs `steps` steps. `left` != NULL: each change of the mono (or left) mix is added to it as a delta at its step
 * (clock = step index within this frame), and on a stereo chip each change of the right mix to `right`; the caller
 * ends the frames. `left` == NULL: silent arithmetic advance to the same state (and `last`/`last_r` = the mixes of that
 * state). */
void ay_psg_run(AyPsg* c, uint32_t steps, blip_t* left, blip_t* right);
/* Leaves channels out of the mix (bit c = channel c), on both sides. */
void ay_psg_set_mute(AyPsg* c, int mask);

/* ---- state accessors (machine and unit tests) ---- */
/* The mix of the current state (mute applied), in AY_AMP units: side 0 = the mono mix (A + B + C) or, stereo, the
 * left (A + B); side 1 = the same mono mix, or the right (B + C). */
int ay_psg_mix(const AyPsg* c, int side);
/* The envelope's current level: 0..15 (AY) or 0..31 (YM). */
int ay_psg_env_level(const AyPsg* c);
/* The DAC level index channel c outputs now (0 when its gated output is low). */
int ay_psg_channel_level(const AyPsg* c, int channel);
/* Whether two chips are in the same state (registers, every counter, output, LFSR, envelope, mute, mix). */
int ay_psg_same(const AyPsg* a, const AyPsg* b);
/* Runs `steps` single steps, writing the side-0 mix after each into `left` and the side-1 mix into `right` (NULL: not
 * written): the output path's samples before blip_buf (unit tests). */
void ay_psg_render_mix(AyPsg* c, uint32_t steps, int32_t* left, int32_t* right);
/* The LFSR after n steps from `lfsr` (closed form in blocks of 14 steps). */
uint32_t ay_psg_lfsr_advance(uint32_t lfsr, uint32_t n);
/* The mix units of a DAC level (AY 0..15, YM 0..31). */
int ay_psg_amplitude(int ym, int level);
/* ayumi's measured DAC curves (0..1, level max = 1.0). */
extern const double AY_PSG_AY_DAC[16];
extern const double AY_PSG_YM_DAC[32];

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_AY_PSG_H_ */
