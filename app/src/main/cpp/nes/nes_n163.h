/* Namco 163 audio (up to eight wavetable channels), written for Thor Tracker from the NESdev wiki page
 * "Namco 163 audio" (https://www.nesdev.org/wiki/Namco_163_audio; iNES mapper 019).
 *
 * Registers: the address port $F800-$FFFF (bits 0-6 address, bit 7 auto-increment: the address then advances
 * after every data port access and stops at $7F, it does not wrap), the data port $4800-$4FFF (writes store,
 * reads return the addressed byte of the 128-byte sound RAM) and $E000-$E7FF, whose bit 6 disables sound (bits
 * 0-5 select a PRG page: not audio, ignored here). Power-on: sound enabled, address 0 without auto-increment.
 * Sound RAM holds the 4-bit samples (two per byte, the even sample in the low nibble) and, from the top down, the
 * channel registers: hardware channel h (1..8) at $40 + 8 (h - 1): +0 frequency bits 0-7, +1 phase bits 0-7,
 * +2 frequency bits 8-15, +3 phase bits 8-15, +4 [LLLLLLFF] (wave length 256 - (v & $FC) samples, frequency
 * bits 16-17), +5 phase bits 16-23, +6 wave address (in samples), +7 volume (bits 0-3; at $7F only, bits 4-6 =
 * enabled channels - 1).
 * Channel update: the chip does not mix. Every 15 CPU cycles it updates one channel, cycling from channel 8
 * ($78) downwards through the enabled count (channel 8 only with C = 0, 8 and 7 with C = 1, ...): phase =
 * (phase + freq) mod (length << 16), stored back into RAM; its DAC output (sample at ((phase >> 16) + address) &
 * $FF, minus 8, times volume: -120..105) is held until the next update. Each channel thus sounds f = CPU * freq /
 * (15 * 65536 * length * channels), and the output switches channel every 15 cycles (119.3 kHz / channels per
 * round: an audible whine at 7-8 channels, as on the hardware). The enabled count is read at every update, so a
 * change takes effect at the next slot: the round restarts at channel 8 once the position reaches the count.
 * While $E000 bit 6 is set the DAC outputs 0; the oscillators keep running.
 *
 * Mixing: NESdev "Mixing" measures the loudest N163 square in 1-channel mode 11 to 19.5 dB louder than the
 * loudest 2A03 square, depending on the board (submapper 3: 11.0-13.0 dB, 4: 16.0-17.0, 5: 18.0-19.5). FamiStudio
 * songs are balanced for the quieter class, so we take 12 dB: a full swing of the DAC (225 units: sample 0 to 15
 * at volume 15) equals the 2A03 mixer's level of one pulse at volume 15 times 10^(12/20). Added with positive
 * polarity into the 2A03's band-limited buffer.
 *
 * Channel index k (mute bits, accessors) is FamiStudio's N163 channel k = engine channel 5 + k = hardware
 * channel 8 - k (registers $78 - 8k). A muted channel outputs 0 in its slots; its oscillator and the RAM run on
 * unchanged.
 *
 * Time is counted in CPU cycles from the start of the current output frame, as nes_apu.c. Every channel slot
 * is computed exactly, with output attached or not (seeking), so phases and the round-robin position after a
 * silent seek equal those of audible play; with output the DAC level is sent as band-limited deltas (amplitude
 * amp_scale, by default NES_APU_AMP_SCALE, = a level of 1.0) whenever it changes. Plain data, no allocation. */
#ifndef THORTRACKER_NES_N163_H_
#define THORTRACKER_NES_N163_H_

#include <stdint.h>

#include "blip_buf.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Mixer weight of one DAC unit: the 2A03 mixer's level of one pulse at volume 15, 12 dB up (10^(12/20)), over
 * the 225 units of a full-volume square. */
#define NES_N163_STEP (95.88 / (8128.0 / 15.0 + 100.0) * 3.9810717055349722 / 225.0)
/* The mixer span of the N163's one DAC: -120..105 units (sample 0..15 minus 8, times volume 15), 225 units. */
#define NES_N163_SPAN (225.0 * NES_N163_STEP)
#define NES_N163_CHANNELS 8
#define NES_N163_RAM 128
#define NES_N163_SLOT_CYCLES 15 /* CPU cycles per channel update */

typedef struct NesN163 {
    uint8_t ram[NES_N163_RAM]; /* sound RAM: samples and channel registers */
    uint8_t addr;              /* address port: bits 0-6 address, bit 7 auto-increment */
    uint8_t disabled;          /* $E000 bit 6: DAC output 0 */
    uint8_t mute;              /* bit k: FamiStudio N163 channel k (hardware channel 8 - k) silent in the mix */
    uint8_t pos;               /* round-robin position of the next update: channel index k (0 = channel 8) */
    uint8_t last;              /* channel index of the update the DAC holds */
    int8_t level;              /* (sample - 8) * volume of that update (-120..105), mutes and disable ignored */
    uint32_t timer;            /* CPU cycles until the next update (1..15) */
    uint32_t time;             /* frame-relative CPU cycle reached */
    int32_t amp;               /* last amplitude sent to the buffer */
    blip_t* out;               /* NULL: silent (slots still computed, no synthesis) */
    double amp_scale;          /* blip amplitude of a mixer level of 1.0; 0 = NES_APU_AMP_SCALE (nes_n163_set_amp_scale) */
} NesN163;

/* Power-on state (RAM 0, sound enabled, address 0, the next update channel 8 in 15 cycles). Keeps `out`, `mute` and
 * `amp_scale`. */
void nes_n163_reset(NesN163* n);
/* Blip amplitude of a mixer level of 1.0 (0: NES_APU_AMP_SCALE, as the 2A03's); the machine's N163 builds scale the APU
 * and the N163 alike (nes_machine.c). Set it before attaching the output. */
void nes_n163_set_amp_scale(NesN163* n, double scale);
/* Attach the band-limited output (NULL = silent); starts from the current level without a step. */
void nes_n163_set_output(NesN163* n, blip_t* out);
/* Advances to frame-relative CPU cycle `t` (>= the time reached). */
void nes_n163_run(NesN163* n, uint32_t t);
/* Whether `addr` is an N163 audio port: data $4800-$4FFF, sound enable $E000-$E7FF, address $F800-$FFFF. */
int nes_n163_register(uint16_t addr);
/* Port write at frame-relative cycle `t` (runs up to `t` first); other addresses are ignored. */
void nes_n163_write(NesN163* n, uint32_t t, uint16_t addr, uint8_t value);
/* Data port read at cycle `t` (runs up to `t` first): the addressed RAM byte; auto-increment advances. */
uint8_t nes_n163_read(NesN163* n, uint32_t t);
/* Runs to `frame_cycles` and starts the next frame at time 0 (the caller ends the blip frame). */
void nes_n163_end_frame(NesN163* n, uint32_t frame_cycles);
/* Forces channels silent in the mix (bit k = FamiStudio N163 channel k); RAM and phases keep running. */
void nes_n163_set_mute(NesN163* n, uint8_t mask);
/* Enabled channels ($7F bits 4-6, + 1). */
int nes_n163_channel_count(const NesN163* n);
/* RAM byte (tests: no auto-increment, no side effect). */
uint8_t nes_n163_peek(const NesN163* n, int addr);
/* 24-bit phase of channel index k (FamiStudio N163 channel k, registers $78 - 8k). */
uint32_t nes_n163_phase(const NesN163* n, int k);
/* Current DAC value (-120..105) with mutes and the disable bit applied, and the channel index whose update
 * it holds. */
int nes_n163_dac(const NesN163* n);
int nes_n163_dac_channel(const NesN163* n);
/* Current mixed level (NES_N163_STEP per DAC unit), in the 2A03 mixer's scale. */
float nes_n163_mix_level(const NesN163* n);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_NES_N163_H_ */
