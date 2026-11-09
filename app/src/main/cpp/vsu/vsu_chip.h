/* Thor Tracker - the Virtual Boy VSU core (vsu_chip.c). Copyright (C) 2026 Thor Tracker contributors.
 * GPL-3.0-or-later. Derived from Mednafen 1.32.1 src/vb/vsu.cpp, Copyright (C) 2010-2016 Mednafen Team,
 * GPL-2.0-or-later; amended from the Virtual Boy Sacred Tech Scroll (vsu_chip.c lists the amendments).
 *
 * The six channels of the VSU at its 5 MHz clock, event-driven: vsu_run advances every active channel by whole
 * events (wave steps by division, noise per shift-register step, effects ticks every 4800 clocks), so running a
 * then b clocks leaves exactly the state of running a + b (fully clocked seeking relies on it). Registers are
 * addressed as word indices (byte address within the VSU window >> 2, the numbers VGM's 0xC7 command uses):
 * wave w (0-4) sample i = VSU_WAVE(w, i), modulation value i = VSU_MOD(i), channel c (0-5) register r =
 * VSU_REG(c, r), SSTOP = VSU_SSTOP. Writes act after every event of the current clock; vsu_sample reads the mixer
 * after every event of the current clock.
 *
 * Timing contract (also tools/audio/vsu/vsu_ref.py's): an SxINT write with bit 7 set at time T starts the channel:
 * its wave position (or noise register) restarts, the first wave step is at T + P (P = 2048 - current frequency
 * clocks; noise 10 (2048 - F)) and every later step one period, read from the current frequency at the step, after
 * it; effects ticks are at T + 4800 j (j >= 1); every 4th one is a length unit, every 16th an envelope unit. At a
 * clock with both, the wave/noise step comes first. At an effects tick: length (may stop the channel), envelope,
 * then on channel 5 the sweep overflow check (may stop it) and the sweep/modulation frame. A stopped channel's
 * state stays frozen until its next SxINT. No allocation; plain data (copy with memcpy/assignment). */
#ifndef THORTRACKER_VSU_CHIP_H_
#define THORTRACKER_VSU_CHIP_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define VSU_CLOCK 5000000u        /* Hz */
#define VSU_CHANNELS 6
#define VSU_WAVES 5
#define VSU_WAVE_SAMPLES 32
#define VSU_MOD_VALUES 32
#define VSU_EFFECT_CLOCKS 4800u   /* 0.96 ms */
#define VSU_SAMPLE_CLOCKS 120u    /* the mixer's output rate: 41666.7 Hz */
#define VSU_MAX_OUTPUT 685        /* the largest 10-bit mixer output */
#define VSU_REGISTERS 0x161       /* word indices 0x000-0x160 */

/* Word indices. */
#define VSU_WAVE(w, i) (0x20 * (w) + (i))
#define VSU_MOD(i) (0xA0 + (i))
#define VSU_REG(c, r) (0x100 + 0x10 * (c) + (r))
#define VSU_SSTOP 0x160
enum { VSU_INT = 0, VSU_LRV = 1, VSU_FQL = 2, VSU_FQH = 3, VSU_EV0 = 4, VSU_EV1 = 5, VSU_RAM = 6, VSU_SWP = 7 };

typedef struct VsuChannel {
    /* registers (as written, masked as the hardware keeps them) */
    uint8_t intv;      /* SxINT as written: bit 7 enable, bit 5 auto, bits 0-4 interval (bit 6 dropped) */
    uint8_t lrv;       /* SxLRV: left << 4 | right */
    uint8_t ev0;       /* SxEV0: value << 4 | grow << 3 | interval */
    uint8_t ev1;       /* SxEV1 (channels 1-4: bits 0-1; channels 5, 6: bits 0-1, 4-6) */
    uint8_t ram;       /* SxRAM: wave index (> 4: silent) */
    uint16_t freq;     /* the frequency last written through SxFQL/SxFQH (11 bits) */
    /* held state */
    uint8_t active;
    uint8_t env;       /* envelope level 0..15 */
    uint8_t env_count; /* envelope units left in the envelope frame, 1..8 */
    uint8_t length;    /* length units left (auto), 1..32 */
    uint16_t cur;      /* the current frequency (sweep/modulation change it on channel 5) */
    /* free-running phases */
    uint8_t wave_pos;  /* 0..31 */
    uint8_t len_div;   /* effects ticks to the next length unit, 1..4 */
    uint8_t env_div;   /* length units to the next envelope unit, 1..4 */
    int32_t step_count; /* clocks to the next wave or noise step, >= 1 */
    int32_t fx_count;   /* clocks to the next effects tick, 1..4800 */
} VsuChannel;

typedef struct VsuChip {
    VsuChannel ch[VSU_CHANNELS];
    uint8_t wave[VSU_WAVES][VSU_WAVE_SAMPLES]; /* 6-bit samples */
    uint8_t mod[VSU_MOD_VALUES];               /* s8 values */
    uint8_t swp;       /* S5SWP: clock << 7 | interval << 4 | up << 3 | shift */
    uint8_t mod_pos;   /* channel 5's next modulation value, 0..32 (32: past the end, without repeat) */
    uint16_t mod_ticks; /* effects ticks into channel 5's current frequency-modification frame */
    uint16_t mod_len;   /* that frame's length in effects ticks (0: no frame runs, interval 0) */
    uint16_t lfsr;     /* the noise shift register (15 bits) */
    uint64_t clocks;   /* clocks run since vsu_reset */
} VsuChip;

/* Power-on: every register, RAM and counter 0 (the Tech Scroll believes the hardware starts so). */
void vsu_reset(VsuChip* chip);
/* One register write (word index; anything outside the map is ignored). Wave RAM writes are ignored while any
 * channel is active, modulation RAM writes while channel 5 is. */
void vsu_write(VsuChip* chip, uint16_t reg, uint8_t val);
/* Advances `clocks` clocks. */
void vsu_run(VsuChip* chip, uint32_t clocks);
/* The 10-bit mixer output now (0..VSU_MAX_OUTPUT per side); channels whose bit is set in mute_mask are left out
 * of the sum (the chip is untouched). */
void vsu_sample(const VsuChip* chip, unsigned mute_mask, int* left, int* right);
/* Editor path (live edits): sets a wave sample / modulation value even while channels play. */
void vsu_poke_wave(VsuChip* chip, int wave, int index, uint8_t value);
void vsu_poke_mod(VsuChip* chip, int index, uint8_t value);

/* Read-only views. */
static inline int vsu_active(const VsuChip* chip, int c) { return chip->ch[c].active; }
static inline unsigned vsu_active_mask(const VsuChip* chip) {
    unsigned m = 0;
    for (int c = 0; c < VSU_CHANNELS; ++c) m |= (unsigned)chip->ch[c].active << c;
    return m;
}
/* The register value as the chip holds it (wave/modulation RAM, channel registers, S5SWP); SSTOP and unmapped: 0. */
uint8_t vsu_register(const VsuChip* chip, uint16_t reg);

/* Field-by-field equality. vsu_equal: every field (the whole state, free-running phases and the clock count
 * included). vsu_held_equal: the held state a register write stream can restore - every register value including
 * wave and modulation RAM, per channel its active flag, envelope level, envelope step counter, length counter and
 * current frequency, and channel 5's modulation position and frame counters; the free-running phases (wave
 * position, step countdown, noise register, effects/length/envelope dividers) and the clock count are left out. */
int vsu_equal(const VsuChip* a, const VsuChip* b);
int vsu_held_equal(const VsuChip* a, const VsuChip* b);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_VSU_CHIP_H_ */
