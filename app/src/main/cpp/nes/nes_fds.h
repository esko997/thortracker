/* Famicom Disk System audio (the 2C33's wavetable channel), written for Thor Tracker from the NESdev wiki page
 * "FDS audio" (https://www.nesdev.org/wiki/FDS_audio, as of 2026-11-08: sections "Registers", "Unit tick", "Frequency
 * calculation and timing", "Mixing"), with Disch's "FDS Sound" notes (https://www.nesdev.org/FDS.txt, "Unit Activity")
 * where the wiki is silent, and the DAC curve FamiStudio 4.5.3 uses in its own FDS emulator (FamiStudio commit
 * c09dca64, ThirdParty/NesSndEmu/nes_apu/Nes_Fds.cpp, `dac_lookup`: plgDavid's measurements cleaned up by Persune;
 * Nes_Snd_Emu, LGPL-2.1-or-later, the table only).
 *
 * Registers (writes $4040-$408A, reads $4040-$407F and $4090-$4097; the sound registers are enabled as the FDS BIOS
 * leaves them, $4023 = $83, which is not modelled further):
 *   $4040-$407F  wave RAM, 64 samples 0..63: written only while $4089 bit 7 is set; read: bits 6-7 = 01 (open bus),
 *                the addressed sample while writable, else the sample at the current wave position
 *   $4080  MDVVVVVV  M = 1: volume gain = V (envelope off), M = 0: envelope on; D: direction (1 up); V also the
 *                envelope speed. Resets the volume envelope timer.
 *   $4082/$4083  frequency bits 0-7 / bits 8-11; $4083 bit 7 halts the wave unit and resets its accumulator (wave
 *                position 0), bit 6 disables both envelopes and resets their timers
 *   $4084  as $4080 for the mod gain and the mod envelope
 *   $4085  mod counter (7-bit signed)
 *   $4086/$4087  mod frequency bits 0-7 / bits 8-11; $4087 bit 7 halts the mod unit, forces mod accumulator bits 0-12
 *                to 0 (the table address, bits 13-17, stays) and allows $4088 writes; bit 6 forces a carry (a step
 *                every unit tick)
 *   $4088  while the mod unit is halted: mod table entry at the current address (bits 13-17) = value & 7, then the
 *          address advances (32 writes = a full table, ending where it began)
 *   $4089  W.....VV  W: wave RAM write enable, the output holds its sample meanwhile (the wave unit keeps running);
 *          VV: master volume 2/2, 2/3, 2/4, 2/5
 *   $408A  envelope speed multiplier (the BIOS leaves $E8; 0 stops both envelopes)
 *   $4090 volume gain, $4091 wave accumulator bits 12-19, $4092 mod gain, $4093 mod accumulator bits 5-11, $4094 bits
 *   4-11 of counter x gain (the completed product: the sequential multiplier's intermediate steps are not modelled),
 *   $4095 the increment the next mod step adds (0,1,2,4,C,C,E,F; the unknown high nibble reads 0), $4096 the sample
 *   fed to the DAC (the 32-cycle volume PWM masking is not modelled: volume is applied linearly, as the wiki allows),
 *   $4097 the mod counter.
 *
 * Unit tick, every 16 CPU cycles: first the mod unit (not halted): accumulator bits 0-11 += mod frequency; on a carry
 * out of bit 11 (or with $4087 bit 6) the counter takes the table entry at address bits 13-17 (0, +1, +2, +4, reset
 * to 0, -4, -2, -1; wrapping in 7 bits) and the 6-bit position (bits 12-17, bit 12 the "ghost" bit: every entry acts
 * twice) advances. Then the wave unit (not halted): accumulator (24 bits) += NESdev's modulated pitch:
 *   temp = counter * gain; if ((temp & 0x0F) && !(temp & 0x800)) temp += 0x20; temp += 0x400;
 *   temp = (temp >> 4) & 0xFF; wave_pitch = (frequency * temp) & 0xFFFFF
 * (in effect whether the mod unit runs or not; counter 0 gives frequency * 64). Wave position = bits 18-23, so a
 * wave of 64 samples sounds CPU * frequency / 2^22 Hz unmodulated.
 * Envelopes (each enabled by bit 7 = 0 of its register, $408A != 0, $4083 bits 6-7 clear: Disch's "Unit Activity",
 * NESdev "the envelopes are not ticked while the waveform is halted"): one step every 8 * (speed + 1) * ($408A + 1)
 * CPU cycles, up to 32 or down to 0. A timer counts only while its envelope is enabled; a new period applies from
 * the next reload.
 * Output: the gain the DAC uses is latched from the volume gain only while the wave position is 0 (or the unit just
 * wrapped past it, as FamiStudio's Nes_Fds does), except that writing gain 0 through $4080 mutes at once; level =
 * DAC(sample) * min(latched gain, 32) / 32 * 2 / (2 + master volume), DAC = FamiStudio's measured curve normalised to
 * its value at 63. A full level (sample 63, gain >= 32, master 0) equals 2.4 times the 2A03 mixer's level of one pulse
 * at volume 15 (NESdev "Mixing": "roughly 2.4x the maximum volume of the APU square, and the polarity is the same"),
 * added with positive polarity into the 2A03's band-limited buffer through the RAM card's filter, approximated as NESdev
 * says by a 1-pole low-pass at 2000 Hz (stepped once per unit tick, sent with blip_add_delta_fast: the filtered signal
 * has no edges).
 * Not modelled (the wiki marks them unverified or they need cycle-level multiplier detail no song depends on): the
 * $4023 audio reset, the "4x faster envelopes" and "halts the mod table accumulator" remarks on $4083 bit 7 (the wiki's
 * own TODO; Disch and FamiStudio keep the mod unit running), the delayed first mod step after clearing $4087 bit 7.
 *
 * Mute forces the output to 0; every register, accumulator, timer and the latch run on unchanged. Time is counted in
 * CPU cycles from the start of the current output frame, as nes_apu.c. Every unit tick and envelope step is computed
 * exactly with output attached or not (seeking), so the chip state after a silent run equals that of audible play;
 * only the filter and the band-limited amplitude are output state (set_output restarts them at the current level).
 * Plain data, no allocation. */
#ifndef THORTRACKER_NES_FDS_H_
#define THORTRACKER_NES_FDS_H_

#include <stdint.h>

#include "blip_buf.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Mixer level of the full FDS output: 2.4 x the 2A03 mixer's level of one pulse at volume 15 (NESdev "Mixing"). */
#define NES_FDS_FULL_LEVEL (2.4 * 95.88 / (8128.0 / 15.0 + 100.0))
#define NES_FDS_UNIT_CYCLES 16  /* CPU cycles per mod/wave unit tick */
#define NES_FDS_LOWPASS_HZ 2000.0
#define NES_FDS_WAVE 64
#define NES_FDS_MOD_TABLE 32

typedef struct NesFds {
    uint8_t wave[NES_FDS_WAVE];     /* wave RAM, samples 0..63 */
    uint8_t mod[NES_FDS_MOD_TABLE]; /* mod table, entries 0..7 */
    uint8_t vol_ctrl, mod_ctrl;     /* last $4080 / $4084 (envelope mode, direction, speed) */
    uint8_t vol_gain, mod_gain;     /* gains 0..63 */
    uint8_t gain;                   /* volume gain latched for the output (at wave position 0) */
    uint8_t freq_hi;                /* last $4083: bit 7 wave halt, bit 6 envelopes off, bits 0-3 frequency */
    uint8_t mod_hi;                 /* last $4087: bit 7 mod halt, bit 6 forced carry, bits 0-3 frequency */
    uint8_t freq_lo, mod_lo;        /* $4082, $4086 */
    int8_t counter;                 /* mod counter -64..63 */
    uint8_t ctrl;                   /* last $4089: bit 7 write enable, bits 0-1 master volume */
    uint8_t env_speed;              /* $408A */
    uint8_t sample;                 /* DAC input: the sample at the wave position, held while write-enabled */
    uint8_t mute;                   /* 1: output 0 */
    uint32_t wave_acc;              /* 24 bits; position = bits 18-23 */
    uint32_t mod_acc;               /* 18 bits; table address = bits 13-17, ghost bit 12 */
    uint32_t unit_timer;            /* CPU cycles until the next unit tick (1..16) */
    uint32_t vol_timer, mod_timer;  /* CPU cycles until the next envelope step (counting while enabled) */
    uint32_t time;                  /* frame-relative CPU cycle reached */
    /* output state */
    float lp, lp_k;                 /* filtered level, filter coefficient per unit tick */
    int32_t amp;                    /* last amplitude sent to the buffer */
    blip_t* out;                    /* NULL: silent (units still computed, no synthesis) */
} NesFds;

/* Power-on as the FDS BIOS leaves the unit: $4080 = $80 (gain 0, envelope off), $408A = $E8, everything else 0, the
 * next unit tick in 16 cycles. Keeps `out` and `mute`. */
void nes_fds_reset(NesFds* f);
/* Attach the band-limited output (NULL = silent); the filter starts at the current level without a step. */
void nes_fds_set_output(NesFds* f, blip_t* out);
/* Advances to frame-relative CPU cycle `t` (>= the time reached). */
void nes_fds_run(NesFds* f, uint32_t t);
/* Whether `addr` is an FDS sound register write ($4040-$408A) / read ($4040-$407F, $4090-$4097). */
int nes_fds_register(uint16_t addr);
int nes_fds_readable(uint16_t addr);
/* Register write / read at frame-relative cycle `t` (runs up to `t` first); other addresses are ignored / read 0. */
void nes_fds_write(NesFds* f, uint32_t t, uint16_t addr, uint8_t value);
uint8_t nes_fds_read(NesFds* f, uint32_t t, uint16_t addr);
/* Runs to `frame_cycles` and starts the next frame at time 0 (the caller ends the blip frame). */
void nes_fds_end_frame(NesFds* f, uint32_t frame_cycles);
/* Forces the output to 0 (1) or not (0); the unit keeps running. */
void nes_fds_set_mute(NesFds* f, int muted);
/* Wave position 0..63, frequency (12 bits), mod frequency, the 20-bit modulated pitch of the next wave step. */
int nes_fds_position(const NesFds* f);
uint32_t nes_fds_frequency(const NesFds* f);
uint32_t nes_fds_mod_frequency(const NesFds* f);
uint32_t nes_fds_pitch(const NesFds* f);
/* Mixer level now (unfiltered, mute applied), in the 2A03 mixer's scale; NES_FDS_FULL_LEVEL at most. */
float nes_fds_mix_level(const NesFds* f);
/* FamiStudio's measured DAC value of `sample` (0..63) normalised to 1.0 at 63. */
double nes_fds_dac(int sample);
/* Whether two units hold the same chip state (registers, RAMs, accumulators, timers, latch, time; not the output). */
int nes_fds_same_state(const NesFds* a, const NesFds* b);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_NES_FDS_H_ */
