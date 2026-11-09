/* The EPSM's sound chip and its rhythm ROM firmware.
 *
 * EPSM (Expansion Port Sound Module, NESdev "Expansion Port Sound Module"): a Yamaha YMF288 (OPN3-L: the YM2608's
 * six 4-operator FM channels, three SSG channels and six ADPCM-A rhythm drums from an on-chip ROM; no ADPCM-B, no
 * clock prescaler) on an 8 MHz clock, written through $401C/$401D (address/data, A1 = 0) and $401E/$401F (A1 = 1).
 * Synthesis is ymfm's ymf288 (Aaron Giles, BSD-3-Clause; third_party/ymfm, unmodified) at OPN_FIDELITY_MED: one
 * native sample per 144 clocks, 55555.6 Hz, the same rate as the chip's own FM output; the SSG runs at its 2 MHz
 * effective clock and is resampled 9:2 exactly as ymfm's ymf288::generate does. nes_epsm_ymfm.cpp compiles against
 * ymfm_opn.cpp, which ym/ym_ymfm.cpp compiles once for the whole program (link both).
 *
 * The rhythm drums play the YM2608 rhythm ROM (8192 bytes: bass drum, snare, top cymbal, hi-hat, tom, rim shot at
 * ymfm's fixed addresses). The ROM is Yamaha's and is not distributed with Thor Tracker: the user imports it, the
 * host installs it once with nes_epsm_install_rhythm_rom, and every chip reads it from there. A rhythm key-on while
 * no ROM is installed makes no sound (that drum is left out of the mix until its next key-on with the ROM) and sets
 * the chip's nes_epsm_chip_rhythm_missing flag; the Kotlin side refuses to play such songs, so the flag is a
 * diagnostic, never a substitute sound.
 *
 * Only this header is visible to the Deck's Kotlin (decknative.def): plain C, no other includes. */
#ifndef THORTRACKER_NES_EPSM_H_
#define THORTRACKER_NES_EPSM_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define NES_EPSM_RHYTHM_ROM_SIZE 8192u

/* Installs the rhythm ROM: copies `size` bytes into static storage and publishes it (atomic release store) for every
 * EPSM chip. 0 ok (also: the identical bytes again, a no-op); -1 when `rom` is NULL, `size` is not 8192, or other
 * bytes than an already installed ROM (installed storage is never rewritten while audio threads may read it). The
 * caller checks the ROM's identity (SHA-256, EpsmRhythmRom.kt). Never called concurrently with itself; any thread. */
int nes_epsm_install_rhythm_rom(const uint8_t* rom, size_t size);
/* 1 once a ROM is installed, else 0. Any thread. */
int nes_epsm_rhythm_rom_installed(void);

/* ---- the chip (used by the NES machine's EPSM unit, nes_epsm_unit.h) ---------------------------------------------- */

#define NES_EPSM_CLOCK 8000000u          /* the EPSM's oscillator */
#define NES_EPSM_CLOCKS_PER_SAMPLE 144u  /* one native sample (FM, rhythm, resampled SSG) */
#define NES_EPSM_SSG 3
#define NES_EPSM_FM 6
#define NES_EPSM_RHYTHM 6
/* Mute bits of nes_epsm_chip_generate, in FamiStudio's channel order: SSG A-C, FM 1-6, rhythm BD SD TOP HH TOM RIM. */
#define NES_EPSM_MUTE_SSG(i) (1u << (i))
#define NES_EPSM_MUTE_FM(i) (1u << (3 + (i)))
#define NES_EPSM_MUTE_RHYTHM(i) (1u << (9 + (i)))

typedef struct NesEpsmChip NesEpsmChip;

/* One native sample, by part (each in ymfm's own units): FM left/right (the OPNA's 13-bit sum, B4 pans applied),
 * rhythm left/right (ADPCM-A, pans applied) and the SSG (centred; ymfm's 2/3-scaled three-channel mix).
 *
 * Ranges, for any register sequence (ymfm's arithmetic, which the mix headroom in nes_epsm_unit.h is derived from):
 *   - FM: an operator's 14-bit value is at most attenuation_to_volume(0) = (0x3FA | 0x400) << 2 = 8168 in magnitude;
 *     the OPNA adds each carrier >> 1 (+-4084) without clipping (ymf288 output: rshift 1, clip +-32767 never reached),
 *     so algorithm 7's four carriers give +-16336 per channel and the six channels +-NES_EPSM_FM_PEAK per side.
 *   - Rhythm: a drum adds ((int16)(12-bit accumulator << 4) x mul >> shift) & ~3 with mul <= 15 and shift >= 5:
 *     -15360..15352 per drum, six drums NES_EPSM_RHYTHM_LOW..NES_EPSM_RHYTHM_HIGH per side, whatever the ROM holds.
 *   - SSG: three channels of ymfm's amplitude table (0..16382) resampled 9:2 (weights summing to 9) and x 2/3:
 *     0..NES_EPSM_SSG_HIGH. */
#define NES_EPSM_FM_PEAK 98016          /* 6 channels x 4 carriers x (8168 >> 1) */
#define NES_EPSM_RHYTHM_LOW (-92160)    /* 6 x ((-32768 x 15) >> 5) */
#define NES_EPSM_RHYTHM_HIGH 92112      /* 6 x (((32752 x 15) >> 5) & ~3) */
#define NES_EPSM_SSG_HIGH 32764         /* 3 x 9 x 16382 x 2 / 27 */

typedef struct NesEpsmOutput {
    int32_t fm[2];
    int32_t rhythm[2];
    int32_t ssg;
} NesEpsmOutput;

/* Allocates a chip in its power-on state (the only allocation); NULL when out of memory. */
NesEpsmChip* nes_epsm_chip_create(void);
void nes_epsm_chip_destroy(NesEpsmChip* chip);
/* Back to exactly the state nes_epsm_chip_create gave (every engine counter, LFO and the SSG resampler phase
 * included; no allocation). */
void nes_epsm_chip_reset(NesEpsmChip* chip);
/* A CPU write to $401C + port: 0 address (A1 = 0), 1 data, 2 address (A1 = 1), 3 data. */
void nes_epsm_chip_write(NesEpsmChip* chip, int port, uint8_t value);
/* Clocks the chip one native sample (144 clocks) and returns its output. Muted parts are left out of the sums only:
 * every channel's state (including FM feedback) advances exactly as unmuted. */
void nes_epsm_chip_generate(NesEpsmChip* chip, uint32_t mute, NesEpsmOutput* out);
/* Rhythm drums keyed on and not yet at their end address (bit per drum). */
uint32_t nes_epsm_chip_rhythm_playing(const NesEpsmChip* chip);
/* Rhythm drums keyed on while no ROM was installed (bit per drum; silent until their next key-on). */
uint32_t nes_epsm_chip_rhythm_silent(const NesEpsmChip* chip);
/* 1 if a rhythm key-on found no ROM installed since the last reset. */
int nes_epsm_chip_rhythm_missing(const NesEpsmChip* chip);
/* Envelope attenuation of FM channel 0..5's operator 1..4 (op 0..3; 0 = loudest, 0x3FF = silent). */
int nes_epsm_chip_fm_attenuation(const NesEpsmChip* chip, int channel, int op);
/* 1 when every carrier of FM channel 0..5 (by its algorithm) is at or past ymfm's quiet threshold (0x380), so the
 * channel outputs nothing. */
int nes_epsm_chip_fm_quiet(const NesEpsmChip* chip, int channel);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_NES_EPSM_H_ */
