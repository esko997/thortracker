/* Nintendo MMC5 audio (two pulses and an 8-bit PCM DAC), written for Thor Tracker from the NESdev wiki page
 * "MMC5 audio" (registers $5000-$5007, $5010, $5011, $5015).
 *
 * Pulses ($5000-$5003, $5004-$5007): as the 2A03 pulses (nes_apu.c: the same duty table, envelope, length table
 * and 11-bit timer, f = CPU / (16 (t + 1))) except:
 *   - no sweep unit: $5001/$5005 are accepted and ignored;
 *   - periods below 8 are not silenced (ultrasonic output);
 *   - no $4017 frame sequencer: the envelopes and the length counters are clocked together every
 *     NES_MMC5_CLOCK_PERIOD = 7424 CPU cycles (241.08 Hz; NESdev derives the divider from die images and notes
 *     that no bench test has confirmed it against the nominal 240 Hz). The divider runs from power-on.
 * $5003/$5007 write: period bits 8-10, length counter load (if enabled in $5015), duty sequencer reset (step 0)
 * and envelope start, as $4003/$4007. $5015 bits 0-1 enable the pulses (clearing a bit zeroes its length
 * counter); reads return the length counters' non-zero bits.
 * PCM: $5010 bit 0 selects write mode (0) or read mode (1), bit 7 enables the IRQ. In write mode $5011 sets the
 * 8-bit DAC; writing $00 leaves the DAC unchanged and sets the IRQ trip flag (any other value clears it). $5011
 * writes are ignored in read mode. Read mode itself (the DAC fed by CPU reads of $8000-$BFFF) is NOT emulated:
 * the machine only runs a music driver, and the pinned FamiStudio engine writes $5010 = $00 once at init and never
 * $5011 (FamiStudio has no MMC5 PCM). $5010 reads return the IRQ (trip AND enable, bit 7; clears the trip) and
 * revision bit 0 = 1 (MMC5A). The IRQ is a flag only; nothing interrupts the CPU.
 * Power-on: every register 0 and the DAC 0. Hardware powers the DAC on at $EF or $FF (NESdev); 0 keeps renders
 * deterministic, and a constant DC level is removed by the machine's high-pass anyway.
 *
 * Output (NESdev: the pulses are equivalent in volume to the APU pulses, the PCM to the APU's DMC with equivalent
 * input, its high bit making it up to twice as loud, and every MMC5 channel has the opposite polarity): the two
 * pulses through the 2A03 pulse formula 95.88 / (8128 / (p1 + p2) + 100) on their own DAC, plus the PCM DAC value
 * as a DMC level through the tnd formula alone 159.79 / (22638 / value + 100), the sum NEGATED (inverted polarity)
 * and added at NES_APU_AMP_SCALE per level 1.0 into the 2A03's band-limited buffer.
 *
 * Time is counted in CPU cycles from the start of the current output frame, as nes_apu.c. With output attached
 * the unit steps from one event (a timer clock of an audible pulse, the envelope/length clock) to the next and
 * sends level changes as band-limited deltas; with no output (seeking) every timer, duty sequencer, envelope and
 * length counter still advances, arithmetically, so the state after a silent run equals straight play's. Plain
 * data, no allocation. */
#ifndef THORTRACKER_NES_MMC5_H_
#define THORTRACKER_NES_MMC5_H_

#include <stdint.h>

#include "blip_buf.h"
#include "nes_apu.h"

#ifdef __cplusplus
extern "C" {
#endif

#define NES_MMC5_CLOCK_PERIOD 7424u /* CPU cycles per envelope + length counter clock (M2 / 7424 = 241.08 Hz) */

/* Mute bits: the pulses (machine channels 5 and 6) and the PCM DAC (no engine channel; tests only). */
enum { NES_MMC5_PULSE1 = 0, NES_MMC5_PULSE2, NES_MMC5_PCM, NES_MMC5_VOICES };

typedef struct NesMmc5Pulse {
    uint8_t ctrl;     /* $5000/$5004: duty (bits 6-7), length halt / envelope loop (5), constant volume (4), volume (0-3) */
    uint16_t period;  /* 11-bit timer period ($5002/$5006, $5003/$5007 bits 0-2) */
    uint8_t length;   /* length counter */
    uint8_t seq;      /* duty sequencer step 0..7 (counts down, as the APU's) */
    NesEnvelope env;
    uint32_t timer;   /* CPU cycles until the next sequencer step (2 (period + 1) per step) */
} NesMmc5Pulse;

typedef struct NesMmc5 {
    NesMmc5Pulse pulse[2];
    uint8_t enabled;      /* $5015 bits 0-1 */
    uint8_t pcm_mode;     /* $5010 bit 0: 0 write mode, 1 read mode */
    uint8_t irq_enable;   /* $5010 bit 7 */
    uint8_t irq_trip;     /* set by a $00 DAC write, cleared by another value or a $5010 read */
    uint8_t dac;          /* PCM DAC value */
    uint8_t mute;         /* bit per NES_MMC5_* voice, forced silent in the mix */
    uint32_t clock_timer; /* CPU cycles until the next envelope/length clock */
    uint32_t time;        /* frame-relative CPU cycle reached */
    int32_t amp;          /* last amplitude sent to the buffer */
    blip_t* out;          /* NULL: silent (state advances arithmetically, no synthesis) */
} NesMmc5;

/* Power-on state (registers 0, pulses disabled, DAC 0, the clock divider restarted). Keeps `out` and `mute`. */
void nes_mmc5_reset(NesMmc5* v);
/* Attach the band-limited output (NULL = silent); starts from the current level without a step. */
void nes_mmc5_set_output(NesMmc5* v, blip_t* out);
/* Advances to frame-relative CPU cycle `t` (>= the time reached). */
void nes_mmc5_run(NesMmc5* v, uint32_t t);
/* Whether `addr` is an MMC5 audio register ($5000-$5007, $5010, $5011, $5015). */
int nes_mmc5_register(uint16_t addr);
/* Register write at frame-relative cycle `t` (runs up to `t` first); other addresses are ignored. */
void nes_mmc5_write(NesMmc5* v, uint32_t t, uint16_t addr, uint8_t value);
/* Register read at cycle `t`: $5015 (length counter status), $5010 (IRQ and revision; acknowledges the IRQ);
 * other addresses read 0. */
uint8_t nes_mmc5_read(NesMmc5* v, uint32_t t, uint16_t addr);
/* Runs to `frame_cycles` and starts the next frame at time 0 (the caller ends the blip frame). */
void nes_mmc5_end_frame(NesMmc5* v, uint32_t frame_cycles);
/* Forces voices silent in the mix (bit per NES_MMC5_*); the state keeps running. */
void nes_mmc5_set_mute(NesMmc5* v, uint8_t mask);
/* Current DAC input of one voice (pulse 0..15, PCM 0..255), ignoring mutes (tests). */
int nes_mmc5_channel_out(const NesMmc5* v, int channel);
/* Current mixed level in the 2A03 mixer's scale, mutes applied: NEGATIVE (inverted polarity) or 0. */
float nes_mmc5_mix_level(const NesMmc5* v);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_NES_MMC5_H_ */
