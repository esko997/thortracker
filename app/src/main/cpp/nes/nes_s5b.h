/* Sunsoft 5B audio: the YM2149F SSG inside the 5B mapper (three square tones, one noise generator, one envelope
 * generator), as on Gimmick! (iNES mapper 069). Written for Thor Tracker from the NESdev wiki page "Sunsoft 5B audio";
 * emu2149 (Mitsutaka Okazaki, MIT; the copy FamiStudio 4.5.3 uses in Nes_Sunsoft.cpp) was read as a reference for
 * the noise generator and envelope details the wiki leaves implicit. No code is taken from either.
 *
 * Bus (cartridge space, write-only; reads there return the ROM):
 *   $C000-$DFFF  register select: bits 0-3 = the internal register for the next data write; if bits 4-7 are not all
 *                zero, data writes are ignored until a select with them clear (as the original YM2149F)
 *   $E000-$FFFF  data: written to the selected register
 * Registers: $00-$05 tone periods A-C (12 bits, low/high), $06 noise period (5 bits), $07 mixer (bits 0-2 tone off
 * A-C, bits 3-5 noise off A-C), $08-$0A volume A-C (bit 4 E: the envelope's level, else the 4-bit volume V),
 * $0B/$0C envelope period (16 bits), $0D envelope shape (C A a H) and reset, $0E/$0F I/O ports (no sound).
 *
 * Clock: the CPU clock (1789773 Hz, the YM2149F's SEL pin low); one shared prescaler clocks every generator once per
 * 16 CPU cycles. Nothing can be halted: muting or zero volume leaves every generator running.
 *   Tone: an up-counter per channel; on a clock it counts, and when it is >= the period (0 acts as 1) the square
 *     output flips and the counter resets to 0: f = CPU / (32 P). Shortening the period below the count flips at the
 *     next clock (NESdev: "an immediate flip").
 *   Noise: the same counter on the 5-bit period (0 as 1); each reach toggles a /2 prescaler, and on its rising toggle
 *     the 17-bit LFSR steps: new bit 16 = bit 0 ^ bit 3, shift right (taps 16 and 13 as NESdev counts them; emu2149's
 *     form), seed 1 at power-on. Noise output = !(LFSR bit 0) (emu2149). A new bit at most every 32 CPU cycles.
 *   Envelope: a 16-bit period (0 as 1) counter as the tone's; each reach steps the 5-bit level (0..31) one way. A $0D
 *     write resets it at once: counter 0, level 31 (attack 0, falling) or 0 (attack 1, rising), running. At the end
 *     of a ramp (the step after level 0 falling or 31 rising): continue 0 -> level 0, held; continue and hold ->
 *     held at 31 if attack ^ alternate else 0; continue and alternate -> the direction flips (the edge level is
 *     output once more); otherwise the ramp restarts (31 or 0). So $00-$03 behave as $09 and $04-$07 as $0F.
 * Channel: on = (tone off | square) & (noise off | noise): both sources off gives a constant level (DC). Its 5-bit
 * level is the envelope's (E) or 2 V + 1. DAC: logarithmic, 1.5 dB per 5-bit step, level 31 = full, levels 0 and 1
 * silent (so V = 0 is silent; NESdev: every 2 envelope steps = 1 volume step). The three channels mix linearly.
 *
 * Mix level: one channel at full = a 2A03 pulse at volume 15 (95.88 / (8128 / 15 + 100)) x NES_S5B_RATIO, FamiStudio
 * 4.5.3's balance (Nes_Sunsoft's synth volume 0.793774 x 4080 / 12240 against Nes_Vrc6's square volume 0.169, and
 * NESdev: a full VRC6 pulse ~ a full 2A03 pulse), added with positive polarity into the 2A03's band-limited buffer
 * (amplitude NES_APU_AMP_SCALE = a level of 1.0). The 5B's amplifier (nonlinear at high levels, with some filtering;
 * NESdev: still under investigation) is not modelled.
 *
 * Time is counted in CPU cycles from the start of the current output frame, as nes_apu.c. With output attached the
 * unit runs clock by clock and sends level changes as band-limited deltas at their CPU cycle; with no output
 * (seeking) every counter, the LFSR and the envelope advance arithmetically to the same state. Plain data, no
 * allocation. */
#ifndef THORTRACKER_NES_S5B_H_
#define THORTRACKER_NES_S5B_H_

#include <stdint.h>

#include "blip_buf.h"

#ifdef __cplusplus
extern "C" {
#endif

#define NES_S5B_CLOCK_DIVIDER 16u /* CPU cycles per generator clock */
#define NES_S5B_APU_SQUARE_LEVEL (95.88 / (8128.0 / 15.0 + 100.0)) /* one 2A03 pulse at 15 */
#define NES_S5B_RATIO 1.56563     /* FamiStudio 4.5.3: 0.793774 x 4080 / 12240 / 0.169 */
/* Mixer level of one 5B channel at DAC level 31, in the 2A03 mixer's scale. */
#define NES_S5B_LEVEL (NES_S5B_APU_SQUARE_LEVEL * NES_S5B_RATIO)

/* The three tone channels (named so as not to clash with the generated NES_S5B_CHANNELS = the build's 8). */
enum { NES_S5B_SQUARE1 = 0, NES_S5B_SQUARE2, NES_S5B_SQUARE3, NES_S5B_VOICES };

typedef struct NesS5b {
    uint8_t reg[16];       /* registers as written, unused bits cleared */
    uint8_t select;        /* the last $C000 value (bits 4-7 set: data writes ignored) */
    uint8_t prescale;      /* CPU cycles until the next generator clock, 1..16 */
    uint8_t square[NES_S5B_VOICES];   /* tone outputs (0/1) */
    uint16_t tone_count[NES_S5B_VOICES];
    uint16_t noise_count;
    uint8_t noise_half;    /* the noise's /2 prescaler */
    uint32_t lfsr;         /* 17 bits */
    uint16_t env_count;
    uint8_t env_level;     /* 0..31 */
    uint8_t env_up;        /* 1: rising */
    uint8_t env_held;      /* 1: the shape holds its level */
    uint8_t mute;          /* bit per NES_S5B_* channel, left out of the mix */
    uint32_t time;         /* frame-relative CPU cycle reached */
    int32_t amp;           /* last amplitude sent to the buffer */
    blip_t* out;           /* NULL: silent (generators advance arithmetically, no synthesis) */
} NesS5b;

/* Power-on state: registers 0, select 0, squares low, LFSR 1, envelope level 0 held until the first $0D write (as
 * emu2149's reset), the prescaler 16 cycles from the next clock. Keeps `out` and `mute`. */
void nes_s5b_reset(NesS5b* s);
/* Attach the band-limited output (NULL = silent); starts from the current level without a step. */
void nes_s5b_set_output(NesS5b* s, blip_t* out);
/* Advances to frame-relative CPU cycle `t` (>= the time reached). */
void nes_s5b_run(NesS5b* s, uint32_t t);
/* Whether `addr` is a 5B audio address ($C000-$FFFF: select below $E000, data from it). */
int nes_s5b_register(uint16_t addr);
/* Bus write at frame-relative cycle `t` (runs up to `t` first); addresses below $C000 are ignored. */
void nes_s5b_write(NesS5b* s, uint32_t t, uint16_t addr, uint8_t value);
/* Runs to `frame_cycles` and starts the next frame at time 0 (the caller ends the blip frame). */
void nes_s5b_end_frame(NesS5b* s, uint32_t frame_cycles);
/* Leaves channels out of the mix (bit per NES_S5B_*); the generators keep running. */
void nes_s5b_set_mute(NesS5b* s, uint8_t mask);

/* Tests and tools. */
/* The 5-bit DAC input of channel 0..2 now (0 while its mixer gate is closed), ignoring mutes. */
int nes_s5b_channel_level(const NesS5b* s, int channel);
/* The DAC's output for a 5-bit level 0..31 (0 for levels 0 and 1, 1.0 for 31; 1.5 dB per step). */
double nes_s5b_dac(int level);
/* Current mixed level (NES_S5B_LEVEL x DAC per channel, mutes applied), in the 2A03 mixer's scale. */
float nes_s5b_mix_level(const NesS5b* s);
static inline int nes_s5b_envelope_level(const NesS5b* s) { return s->env_level; }
static inline int nes_s5b_envelope_holding(const NesS5b* s) { return s->env_held; }
static inline uint32_t nes_s5b_lfsr(const NesS5b* s) { return s->lfsr; }
/* Noise output bit now (!(LFSR bit 0)). */
static inline int nes_s5b_noise(const NesS5b* s) { return (int)(~s->lfsr & 1u); }

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_NES_S5B_H_ */
