/* The NTSC 2A03 APU: two pulses, triangle, noise and the DMC, written for Thor Tracker from the
 * NESdev wiki descriptions (APU, APU Pulse/Triangle/Noise/DMC, APU Envelope, APU Sweep, APU Length
 * Counter, APU Frame Counter, APU Mixer).
 *
 * Emulated: register writes, $4015 enable/status, the frame counter (4- and 5-step modes, $4017
 * write delay and immediate clock, frame IRQ flag), length counters with halt, envelopes, the
 * sweep units (pulse 1 ones' complement negate, target-period muting), the triangle linear
 * counter, the noise LFSR in both modes, duty sequencers, the DMC (NTSC rate table, $4011 direct
 * load, sample address/length $4012/$4013, memory reader with DMA reads of CPU memory $8000-$FFFF
 * wrapping $FFFF -> $8000, sample buffer, 8-bit output shift unit with silence flag, +-2 level
 * steps clamped to 0..127, loop, DMC IRQ flag) and the nonlinear mixer (the exact NESdev formulas).
 * IRQs are flags only; nothing interrupts the CPU. DMA does not stall the CPU (a real fetch takes
 * 1-4 cycles; register writes of the player land those few cycles early, which no output or row
 * timing here depends on), and a fetch happens on the cycle the buffer empties (hardware: 1-3
 * cycles later).
 *
 * Time is counted in CPU cycles from the start of the current output frame. Output is the mixed
 * level as band-limited steps into a blip_buf (amplitude NES_APU_AMP_SCALE = mixer output 1.0);
 * with no buffer attached (seeking) pulse/triangle/noise timers are not stepped at all, while the
 * DMC always runs (its level and sample position carry over a seek). Plain data, no allocation. */
#ifndef THORTRACKER_NES_APU_H_
#define THORTRACKER_NES_APU_H_

#include <stdint.h>

#include "blip_buf.h"

#ifdef __cplusplus
extern "C" {
#endif

#define NES_APU_AMP_SCALE 30000 /* blip amplitude of a mixer output of 1.0 (max is ~1.0) */

enum { NES_APU_PULSE1 = 0, NES_APU_PULSE2, NES_APU_TRIANGLE, NES_APU_NOISE, NES_APU_DMC, NES_APU_CHANNELS };

typedef struct NesEnvelope {
    uint8_t start, divider, decay;
} NesEnvelope;

typedef struct NesPulse {
    uint8_t ctrl;      /* $4000/$4004: duty, halt/loop, constant, volume/period */
    uint8_t sweep;     /* $4001/$4005 */
    uint16_t period;   /* 11-bit timer period */
    uint8_t length, seq, sweep_divider, sweep_reload;
    NesEnvelope env;
    uint32_t timer;    /* CPU cycles until the next sequencer step */
} NesPulse;

typedef struct NesTriangle {
    uint8_t ctrl;      /* $4008: control/halt, linear reload value */
    uint16_t period;
    uint8_t length, linear, linear_reload, seq;
    uint32_t timer;
} NesTriangle;

typedef struct NesNoise {
    uint8_t ctrl;      /* $400C */
    uint8_t mode_period; /* $400E */
    uint8_t length;
    uint16_t lfsr;
    NesEnvelope env;
    uint32_t timer;
} NesNoise;

/* The delta modulation channel ($4010-$4013, $4015 bit 4). */
typedef struct NesDmc {
    uint8_t ctrl;              /* $4010: IRQ enable (bit 7), loop (bit 6), rate index (bits 0-3) */
    uint8_t level;             /* 7-bit output level ($4011 and the delta steps) */
    uint8_t start, length;     /* $4012 (address = $C000 + 64 * start), $4013 (bytes = 16 * length + 1) */
    uint16_t addr;             /* memory reader: address of the next byte */
    uint16_t remaining;        /* memory reader: bytes left in the sample (0 = idle; $4015 bit 4) */
    uint8_t buffer, buffer_full;
    uint8_t shift, bits, silence; /* output unit: shift register, bits left in this cycle (1..8) */
    uint8_t irq;               /* interrupt flag ($4015 bit 7) */
    uint32_t timer;            /* CPU cycles until the next output clock */
    uint32_t fetches;          /* DMA reads since reset (diagnostics) */
} NesDmc;

typedef struct NesApu {
    NesPulse pulse[2];
    NesTriangle tri;
    NesNoise noise;
    NesDmc dmc;
    uint8_t enabled;        /* $4015 bits 0-3 */
    uint8_t mode5;          /* $4017 bit 7 */
    uint8_t irq_inhibit, frame_irq;
    uint32_t fc_cycle;      /* CPU cycles since the frame-counter sequence (re)started */
    int32_t fc_reset_at;    /* pending $4017 effect time (frame-relative cycle), -1 if none */
    uint8_t fc_reset_mode5;
    uint32_t time;          /* frame-relative CPU cycle reached */
    uint64_t abs_cycle;     /* CPU cycles since power-on at frame start (parity for $4017) */
    uint8_t mute;           /* bit per NES_APU_* channel, forced silent in the mixer */
    int32_t amp;            /* last amplitude sent to the buffer */
    blip_t* out;            /* NULL: silent (no pulse/triangle/noise timer stepping, no synthesis) */
    const uint8_t* prg;     /* CPU memory $8000-$FFFF the DMC reads (32 KiB); NULL reads 0 */
} NesApu;

/* NTSC DMC rate table: CPU cycles per output bit for $4010 rate index 0..15. */
extern const uint16_t nes_dmc_rate[16];

/* Power-on state ($4017 = 0, all channels off, noise LFSR = 1, DMC level 0). Keeps `out`, `mute`
 * and `prg`. */
void nes_apu_reset(NesApu* a);
/* CPU memory $8000-$FFFF for DMC sample reads (not copied; must outlive the APU's use). */
void nes_apu_set_memory(NesApu* a, const uint8_t* prg);

/* Attach the band-limited output (NULL = silent). */
void nes_apu_set_output(NesApu* a, blip_t* out);
/* Advances to frame-relative CPU cycle `t` (>= the time reached). */
void nes_apu_run(NesApu* a, uint32_t t);
/* Register write $4000-$4017 at frame-relative cycle `t` (runs up to `t` first). */
void nes_apu_write(NesApu* a, uint32_t t, uint16_t addr, uint8_t value);
/* $4015 read at cycle `t` (clears the frame IRQ flag, not the DMC's). */
uint8_t nes_apu_read_status(NesApu* a, uint32_t t);
/* Runs to `frame_cycles` and starts the next frame at time 0 (the caller ends the blip frame). */
void nes_apu_end_frame(NesApu* a, uint32_t frame_cycles);
/* Forces channels silent in the mixer (bit per NES_APU_*); state keeps running. */
void nes_apu_set_mute(NesApu* a, uint8_t mask);
/* Current mixer output in [0, ~1] (test/diagnostic). */
float nes_apu_mix_level(const NesApu* a);

/* Live edits of the sample memory (the machine, between frames): the playing sample continues at
 * the same position of its copy at start register `start` (the bytes are identical there), or
 * stops as a $4015 write with bit 4 clear does (the bits already fetched still play out). */
void nes_apu_dmc_move(NesApu* a, uint8_t start);
void nes_apu_dmc_stop(NesApu* a);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_NES_APU_H_ */
