/* The EPSM's sound chip.
 *
 * EPSM (Expansion Port Sound Module, NESdev "Expansion Port Sound Module"): a Yamaha YMF288 (OPN3-L: the YM2608's
 * six 4-operator FM channels, three SSG channels and six ADPCM-A rhythm drums from an on-chip ROM; no ADPCM-B, no
 * clock prescaler) on an 8 MHz clock, written through $401C/$401D (address/data, A1 = 0) and $401E/$401F (A1 = 1).
 * Synthesis is ymfm's ymf288 (Aaron Giles, BSD-3-Clause; third_party/ymfm, unmodified) at OPN_FIDELITY_MED: one
 * native sample per 144 clocks, 55555.6 Hz, the same rate as the chip's own FM output; the SSG runs at its 2 MHz
 * effective clock and is resampled 9:2 exactly as ymfm's ymf288::generate does. nes_epsm_ymfm.cpp compiles against
 * ymfm_opn.cpp, which ym/ym_ymfm.cpp compiles once for the whole program (link both), and ym2608_rhythm_rom.c.
 *
 * The rhythm drums play the YM2608 rhythm ROM, which the host installs once into the module every chip with rhythm
 * drums reads (ym2608_rhythm_rom.h: ym2608_rhythm_rom_install). A rhythm key-on while no ROM is installed makes no
 * sound (that drum is left out of the mix until its next key-on with the ROM) and sets the chip's
 * nes_epsm_chip_rhythm_missing flag; the Kotlin side refuses to play such songs, so the flag is a diagnostic, never
 * a substitute sound. */
#ifndef THORTRACKER_NES_EPSM_H_
#define THORTRACKER_NES_EPSM_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

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
 * rhythm left/right (ADPCM-A, pans applied) and the SSG (centred; ymfm's 2/3-scaled three-channel mix). */
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
