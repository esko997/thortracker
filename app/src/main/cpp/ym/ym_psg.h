/* The Mega Drive's PSG: an SN76489 as integrated in the VDP (Sega variant), written for Thor Tracker from the SMS
 * Power documentation. Three tone channels and a noise channel; counters decrement once per step (PSG clock / 16 =
 * MCLK / 240); a tone's output flips when its counter reloads (period 0 counts as 1); the noise counter reloads
 * from 16, 32, 64 or tone 3's period and shifts the 16-bit LFSR when its flip-flop rises (white noise: feedback
 * from bits 0 and 3, taps 0x0009; periodic: bit 0 recirculated; reset to $8000 by every noise-register write).
 * Attenuation in 2 dB steps, 15 = off; bipolar output (+level / -level). Integer only, no allocation. */
#ifndef THORTRACKER_YM_PSG_H_
#define THORTRACKER_YM_PSG_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define YMS_MCLK_PER_STEP 240u /* master clocks per counter step */
#define YMS_LEVEL_MAX 4096     /* output of one channel at attenuation 0 */

typedef struct YmPsg {
    uint16_t period[3];  /* tone periods (10 bits) */
    uint8_t att[4];      /* attenuations, index 3 = noise */
    uint8_t noise;       /* noise control: bit 2 white, bits 0-1 rate */
    int latch_index, latch_type;
    uint16_t counter[4];
    uint8_t flip[4];     /* tone/noise flip-flops */
    uint16_t lfsr;
    unsigned mute;       /* bit i: channel i (3 = noise) left out of the output */
} YmPsg;

void ymps_reset(YmPsg* p);
void ymps_write(YmPsg* p, uint8_t value);
/* One counter step; returns whether an output flip-flop changed (then ymps_output has a new value). */
int ymps_step(YmPsg* p);
/* The summed output now (no step). */
int ymps_output(const YmPsg* p);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_YM_PSG_H_ */
