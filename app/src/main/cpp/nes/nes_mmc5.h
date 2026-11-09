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
 * PCM: $5010 bit 0 selects write mode (0) or read mode (1), bit 7 enables the IRQ. A value reaches the 8-bit DAC from a
 * $5011 write in write mode (writes are ignored in read mode) or, in read mode, from every CPU read of $8000-$BFFF
 * (nes_mmc5_rom_read; the machine calls it for each such bus read, opcode fetches and dummy reads included, as the
 * cartridge sees them): $00 leaves the DAC unchanged and sets the IRQ trip flag, any other value is the DAC's and
 * clears it. There is no sample timer: the DAC changes only on those accesses. The pinned FamiStudio engine writes
 * $5010 = $00 once at init and never $5011 (FamiStudio has no MMC5 PCM); Thor Tracker's software PCM player
 * (nes_mmc5_pcm.h) writes $5011 in write mode on the CPU-cycle timebase. $5010 reads return the IRQ (trip AND enable,
 * bit 7; clears the trip) and revision bit 0 = 1 (MMC5A). The IRQ is a flag only; nothing interrupts the CPU.
 * Power-on: every register 0 and the DAC 0. Hardware powers the DAC on at $EF or $FF (NESdev); 0 keeps renders
 * deterministic, and a constant DC level is removed by the machine's high-pass anyway. The machine's MMC5 build then
 * centres the DAC at NES_MMC5_DAC_CENTRE through the software PCM lane's reset (nes_pcm_reset), output detached.
 *
 * Output (NESdev "MMC5 audio": the pulses are equivalent in volume to the APU pulses; the PCM is equivalent in volume to
 * the APU's DMC with equivalent input, its high bit making it up to twice as loud; every MMC5 channel has the opposite
 * polarity; the Pin 2 DAC is linear, V = (value / 255) 0.4 AVcc + 0.1 AVcc): the two pulses through the 2A03 pulse
 * formula 95.88 / (8128 / (p1 + p2) + 100) on their own DAC, plus the PCM DAC value times NES_MMC5_DAC_STEP (linear;
 * the 0.1 AVcc offset is DC), the sum NEGATED (inverted polarity) and added at amp_scale (by default
 * NES_APU_AMP_SCALE) per level 1.0 into the 2A03's band-limited buffer. Mutes: a muted pulse counts as 0, a muted DAC
 * as NES_MMC5_DAC_CENTRE (its resting value, so a mute toggle of an idle lane adds no step).
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
/* Mixer level per PCM DAC value: calibrated so value 127 equals the APU DMC at its full 7-bit level 127 through the
 * 2A03 tnd formula, 159.79 / (22638 / 127 + 100) ("equivalent input"); linear above it, so 255 is about twice that. */
#define NES_MMC5_DAC_STEP (159.79 / (22638.0 / 127.0 + 100.0) / 127.0)
/* The PCM DAC's resting value: the software PCM lane (nes_mmc5_pcm.h) centres the DAC here when it resets and whenever
 * it is silent, and a muted DAC mixes as this value, so muting or unmuting an idle lane is no step and a muted lane
 * matches an idle one exactly. */
#define NES_MMC5_DAC_CENTRE 128
/* The unit's whole mixer span: both pulses at volume 15 through the pulse formula plus the DAC at 255 (about 1.41: the
 * pulses 0.258, the DAC 1.153), all of it on the negative side (inverted polarity). A muted DAC (the centre) lies inside. */
#define NES_MMC5_SPAN (95.88 / (8128.0 / 30.0 + 100.0) + 255.0 * NES_MMC5_DAC_STEP)

/* Mute bits: the pulses (machine channels 5 and 6) and the PCM DAC (machine channel 7: the software PCM lane). A muted
 * pulse mixes as 0, a muted DAC as NES_MMC5_DAC_CENTRE. */
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
    uint8_t irq_trip;     /* set by a $00 DAC input ($5011 write or read-mode ROM read), cleared by another value or a
                             $5010 read */
    uint8_t dac;          /* PCM DAC value */
    uint8_t mute;         /* bit per NES_MMC5_* voice, out of the mix (a pulse as 0, the DAC as NES_MMC5_DAC_CENTRE) */
    uint32_t clock_timer; /* CPU cycles until the next envelope/length clock */
    uint32_t time;        /* frame-relative CPU cycle reached */
    int32_t amp;          /* last amplitude sent to the buffer */
    blip_t* out;          /* NULL: silent (state advances arithmetically, no synthesis) */
    double amp_scale;     /* blip amplitude of a mixer level of 1.0; 0 = NES_APU_AMP_SCALE (nes_mmc5_set_amp_scale) */
} NesMmc5;

/* Power-on state (registers 0, pulses disabled, DAC 0, the clock divider restarted). Keeps `out`, `mute` and
 * `amp_scale`. */
void nes_mmc5_reset(NesMmc5* v);
/* Blip amplitude of a mixer level of 1.0 (0: NES_APU_AMP_SCALE); the machine's MMC5 build scales the APU and the MMC5
 * alike (nes_machine.c). Set it before attaching the output. */
void nes_mmc5_set_amp_scale(NesMmc5* v, double scale);
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
/* A CPU read of `addr` returning `value` at cycle `t`: in read mode a read of $8000-$BFFF feeds the DAC (as above);
 * otherwise nothing. */
void nes_mmc5_rom_read(NesMmc5* v, uint32_t t, uint16_t addr, uint8_t value);
/* Runs to `frame_cycles` and starts the next frame at time 0 (the caller ends the blip frame). */
void nes_mmc5_end_frame(NesMmc5* v, uint32_t frame_cycles);
/* Forces voices out of the mix (bit per NES_MMC5_*: a pulse counts as 0, the DAC as NES_MMC5_DAC_CENTRE); the state keeps
 * running. */
void nes_mmc5_set_mute(NesMmc5* v, uint8_t mask);
/* Current DAC input of one voice (pulse 0..15, PCM 0..255), ignoring mutes (tests). */
int nes_mmc5_channel_out(const NesMmc5* v, int channel);
/* Current mixed level in the 2A03 mixer's scale, mutes applied (a muted DAC at its centre): NEGATIVE (inverted polarity)
 * or 0. */
float nes_mmc5_mix_level(const NesMmc5* v);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_NES_MMC5_H_ */
