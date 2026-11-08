/* The NES machine's EPSM unit: the YMF288 (nes_epsm.h) on the expansion bus, clocked in step with the CPU and mixed
 * in stereo into two band-limited buffers in the 2A03 mixer's scale.
 *
 * Bus (NESdev "Expansion Port Sound Module"; FamiStudio's FAMISTUDIO_EPSM_REG_SEL0/WRITE0/SEL1/WRITE1):
 *   $401C  address, A1 = 0 (registers $00-$FF: SSG $00-$0D, rhythm $10-$1D, FM channels 1-3 and globals $20-$B6)
 *   $401D  data,    A1 = 0
 *   $401E  address, A1 = 1 (FM channels 4-6 at $30-$B6)
 *   $401F  data,    A1 = 1
 * Writes take effect at their CPU cycle: the chip is first run up to it. Reads see nothing (the driver never reads).
 *
 * Clock: the 8 MHz chip makes one native sample per 144 clocks (55555.6 Hz), one per 144 x 1789773 / 8000000 =
 * 32.216 NTSC CPU cycles, counted exactly in fixed point (NES_EPSM_CYCLE_UNITS per CPU cycle, NES_EPSM_SAMPLE_UNITS
 * per sample). The chip runs whether or not output is attached: a silent run (seeking) advances FM envelopes,
 * phases, feedback and LFO, the SSG's tone/noise/envelope counters and the rhythm drums exactly as an audible one.
 *
 * Mix: each native sample's left and right levels (2A03 mixer scale, level 1.0 = NES_APU_AMP_SCALE) go into the left
 * and right buffers as steps at the sample's CPU cycle; the machine adds its centred 2A03 to both. The levels are
 * FamiStudio 4.5.3's own balance of the EPSM against the 2A03 (the composing tool's; no hardware measurement of the
 * EPSM's output level is published), from its Nes_EPSM.cpp, ym3438.cpp and Simple_Apu.cpp:
 *   - FamiStudio unit: its EPSM Blip_Synth has range 163430 and volume 10.59857, and its mixer level 1.0 is the same
 *     65536 output steps as the 2A03's (mix_squares / nonlinearize): NES_EPSM_FS_UNIT = 10.59857 / 163430.
 *   - FM: per 24-cycle chip sample FamiStudio sums 6 x the YM3438-mode 9-bit channel output, present on 3 of each
 *     channel's 4 cycles: 18 x (14-bit operator sum >> 5); ymfm's OPNA output is that sum >> 1, so one ymfm unit is
 *     18 / 16 FamiStudio units.
 *   - Rhythm: each drum's (ADPCM x level >> shift) & ~3 (ymfm's and FamiStudio's MAME-derived formula) appears on
 *     4 of 24 cycles as value >> 1, summed x 11/20: 1.1 FamiStudio units per ymfm unit.
 *   - SSG: FamiStudio's emu2149 (YM2149 table, top 0xFF << 4 = 4080) x 10 / 18; ymfm's amplitude table tops at 16382
 *     and its 3-channel mix is x 2/3: (4080 x 10 / 18) / (16382 x 2 / 3) FamiStudio units per ymfm mix unit.
 * Muted channels are left out of the chip's sums; the chip keeps running (unmuting resumes mid-note).
 *
 * Time is counted in CPU cycles from the start of the current output frame, as nes_apu.c. Plain data except the
 * chip nes_epsm_init allocates; nothing else allocates. */
#ifndef THORTRACKER_NES_EPSM_UNIT_H_
#define THORTRACKER_NES_EPSM_UNIT_H_

#include <stdint.h>

#include "blip_buf.h"
#include "nes_epsm.h"

#ifdef __cplusplus
extern "C" {
#endif

#define NES_EPSM_CHANNELS 15                         /* SSG 1-3, FM 1-6, rhythm 1-6 (machine channels 5..19) */
#define NES_EPSM_CYCLE_UNITS NES_EPSM_CLOCK           /* fixed-point units per CPU cycle */
#define NES_EPSM_SAMPLE_UNITS (NES_EPSM_CLOCKS_PER_SAMPLE * 1789773u) /* ... per native sample (NTSC CPU clock) */
#define NES_EPSM_FS_UNIT (10.59857 / 163430.0)
#define NES_EPSM_FM_STEP (NES_EPSM_FS_UNIT * 18.0 / 16.0)
#define NES_EPSM_RHYTHM_STEP (NES_EPSM_FS_UNIT * 1.1)
#define NES_EPSM_SSG_STEP (NES_EPSM_FS_UNIT * (4080.0 * 10.0 / 18.0) / (16382.0 * 2.0 / 3.0))

typedef struct NesEpsm {
    NesEpsmChip* chip;    /* nes_epsm_init */
    uint16_t mute;        /* bit per channel 0..14 (NES_EPSM_MUTE_* order), left out of the mix */
    uint8_t select[2];    /* register selected by $401C / $401E */
    uint8_t reg[2][256];  /* every value written since reset, by bank (A1) and register (tests, diagnostics) */
    uint32_t time;        /* frame-relative CPU cycle reached */
    uint32_t phase;       /* fixed-point units into the current native sample (< NES_EPSM_SAMPLE_UNITS) */
    int32_t amp[2];       /* last amplitudes sent to the buffers */
    float level[2];       /* last mixed levels (2A03 mixer scale) */
    blip_t* out[2];       /* left, right; NULL: silent (the chip still runs) */
} NesEpsm;

/* Creates the chip and resets; 0, or -1 when out of memory. */
int nes_epsm_init(NesEpsm* e);
void nes_epsm_free(NesEpsm* e);
/* Power-on state (the chip exactly as created, registers 0, time 0). Keeps outputs and mutes. */
void nes_epsm_reset(NesEpsm* e);
/* Attach the band-limited outputs (NULL, NULL = silent); starts from the current level without a step. */
void nes_epsm_set_output(NesEpsm* e, blip_t* left, blip_t* right);
/* Advances to frame-relative CPU cycle `t` (>= the time reached): one native sample per 32.216 cycles. */
void nes_epsm_run(NesEpsm* e, uint32_t t);
/* Whether `addr` is an EPSM address ($401C-$401F). */
static inline int nes_epsm_register(uint16_t addr) { return addr >= 0x401C && addr <= 0x401F; }
/* Write at frame-relative cycle `t` (runs up to `t` first); other addresses are ignored. */
void nes_epsm_write(NesEpsm* e, uint32_t t, uint16_t addr, uint8_t value);
/* Runs to `frame_cycles` and starts the next frame at time 0 (the caller ends the blip frames). */
void nes_epsm_end_frame(NesEpsm* e, uint32_t frame_cycles);
/* Leaves channels out of the mix (bit per channel 0..14); the chip keeps running. */
void nes_epsm_set_mute(NesEpsm* e, uint16_t mask);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_NES_EPSM_UNIT_H_ */
