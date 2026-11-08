/* Konami VRC6 audio (two pulses and a sawtooth), written for Thor Tracker from the NESdev wiki page
 * "VRC6 audio" (registers as on Akumajou Densetsu, iNES mapper 024: $9000-$9003, $A000-$A002, $B000-$B002).
 *
 * Pulses: a 12-bit divider clocks the duty generator every period + 1 CPU cycles; the generator takes 16
 * steps counting down from 15 to 0 and outputs the volume V while the step is <= the duty D (D + 1 of 16
 * steps), or always with the mode bit M. Clearing the enable bit E ($x002 bit 7) forces the output to 0 and
 * resets the generator to step 15, halted until E is set again. f = CPU / (16 (t + 1)).
 * Saw: the divider clocks a 14-step sequence: the rate A ($B000 bits 0-5) is added to an 8-bit accumulator on
 * even steps 2..12, the accumulator resets to 0 on the 14th clock (step 0); output = accumulator >> 3 (0..31;
 * A > 42 wraps and distorts). Clearing E forces the accumulator to 0 (and the sequence to step 0) until E is
 * set; the divider keeps running. f = CPU / (14 (t + 1)).
 * $9003 (frequency control): H (bit 0) halts all oscillators in their current state; B (bit 1) shifts the
 * three 12-bit periods right by 4 (16x), A (bit 2) by 8 (256x; overrides B); H overrides both.
 * Output: a linear DAC of p1 + p2 + saw (0..61). NESdev: a full-volume VRC6 pulse is roughly a full-volume 2A03
 * pulse; the 6-bit DAC sums the two 4-bit pulses and the saw's 5 bits with one weight, so one step is
 * NES_VRC6_STEP = (2A03 pulse at 15) / 15 of the 2A03 mixer's output scale. Added with positive polarity (NESdev:
 * hardware pulses appear inverted relative to the 2A03's; inaudible) into the 2A03's band-limited buffer.
 *
 * Time is counted in CPU cycles from the start of the current output frame, as nes_apu.c. With output
 * attached the unit steps from one oscillator clock to the next and sends level changes as band-limited
 * deltas (amplitude NES_APU_AMP_SCALE = a level of 1.0); with no output (seeking) phases advance
 * arithmetically. Plain data, no allocation. */
#ifndef THORTRACKER_NES_VRC6_H_
#define THORTRACKER_NES_VRC6_H_

#include <stdint.h>

#include "blip_buf.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Mixer weight of one DAC step: the 2A03 mixer's level of one pulse at volume 15, divided by 15. */
#define NES_VRC6_STEP (95.88 / (8128.0 / 15.0 + 100.0) / 15.0)

enum { NES_VRC6_PULSE1 = 0, NES_VRC6_PULSE2, NES_VRC6_SAW, NES_VRC6_VOICES };

typedef struct NesVrc6Pulse {
    uint8_t ctrl;    /* $9000/$A000: M (bit 7), duty D (bits 4-6), volume V (bits 0-3) */
    uint8_t lo, hi;  /* $9001/$A001, $9002/$A002: period bits 0-7; E (bit 7) and period bits 8-11 */
    uint8_t step;    /* duty generator step 15..0 (counts down) */
    uint32_t timer;  /* CPU cycles until the next divider clock */
} NesVrc6Pulse;

typedef struct NesVrc6Saw {
    uint8_t rate;    /* $B000 bits 0-5 */
    uint8_t lo, hi;  /* $B001, $B002 */
    uint8_t step;    /* 0..13 */
    uint8_t acc;     /* 8-bit accumulator */
    uint32_t timer;
} NesVrc6Saw;

typedef struct NesVrc6 {
    NesVrc6Pulse pulse[2];
    NesVrc6Saw saw;
    uint8_t freq;   /* $9003: H, B, A */
    uint8_t mute;   /* bit per NES_VRC6_* channel, forced silent in the mix */
    uint32_t time;  /* frame-relative CPU cycle reached */
    int32_t amp;    /* last amplitude sent to the buffer */
    blip_t* out;    /* NULL: silent (phases advance arithmetically, no synthesis) */
} NesVrc6;

/* Power-on state (all registers 0: channels disabled, pulse steps 15, saw step 0). Keeps `out` and `mute`. */
void nes_vrc6_reset(NesVrc6* v);
/* Attach the band-limited output (NULL = silent); starts from the current level without a step. */
void nes_vrc6_set_output(NesVrc6* v, blip_t* out);
/* Advances to frame-relative CPU cycle `t` (>= the time reached). */
void nes_vrc6_run(NesVrc6* v, uint32_t t);
/* Whether `addr` is a VRC6 audio register ($9000-$9003, $A000-$A002, $B000-$B002). */
int nes_vrc6_register(uint16_t addr);
/* Register write at frame-relative cycle `t` (runs up to `t` first); other addresses are ignored. */
void nes_vrc6_write(NesVrc6* v, uint32_t t, uint16_t addr, uint8_t value);
/* Runs to `frame_cycles` and starts the next frame at time 0 (the caller ends the blip frame). */
void nes_vrc6_end_frame(NesVrc6* v, uint32_t frame_cycles);
/* Forces channels silent in the mix (bit per NES_VRC6_*); the oscillators keep running. */
void nes_vrc6_set_mute(NesVrc6* v, uint8_t mask);
/* Current DAC input of one channel (pulse 0..15, saw 0..31), ignoring mutes (tests). */
int nes_vrc6_channel_out(const NesVrc6* v, int channel);
/* Current mixed level (NES_VRC6_STEP per DAC step, mutes applied), in the 2A03 mixer's scale. */
float nes_vrc6_mix_level(const NesVrc6* v);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_NES_VRC6_H_ */
