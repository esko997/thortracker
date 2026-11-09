/* The YM2608 rhythm ROM: the 8192 bytes of Yamaha ADPCM-A drum samples (bass drum, snare, top cymbal, hi-hat, tom,
 * rim shot at ymfm's fixed addresses) the YM2608's six rhythm channels and the YMF288's (the NES EPSM) play. It is
 * Yamaha's and is not distributed with Thor Tracker: the user imports it once, the host stores it as device firmware
 * (outside every project and export) and installs it here, and every chip that plays rhythm reads it from here:
 * "nes-famistudio-epsm" (nes/nes_epsm_ymfm.cpp) and "ym2608-opna" (opn/opn_ymfm.cpp). A rhythm key-on while no ROM is
 * installed makes no sound and is flagged by the chip; the Kotlin side refuses to play songs that need it
 * (Ym2608RhythmRom.kt), so nothing ever substitutes another sound.
 *
 * Plain C, no other includes: the Deck's Kotlin reads this header through cinterop (decknative.def). */
#ifndef THORTRACKER_YM2608_RHYTHM_ROM_H_
#define THORTRACKER_YM2608_RHYTHM_ROM_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define YM2608_RHYTHM_ROM_SIZE 8192u

/* Copies `size` bytes into static storage and publishes them (atomic release store) for every chip. 0 ok (also: the
 * identical bytes again, a no-op); -1 when `rom` is NULL, `size` is not 8192, or other bytes than an already installed
 * ROM (installed storage is never rewritten while audio threads may read it). The caller checks the ROM's identity
 * (size and SHA-256, Ym2608RhythmRom.kt) first. Never called concurrently with itself; any thread. */
int ym2608_rhythm_rom_install(const uint8_t* rom, size_t size);
/* 1 once a ROM is installed, else 0. Any thread. */
int ym2608_rhythm_rom_installed(void);
/* The installed ROM (YM2608_RHYTHM_ROM_SIZE bytes, never changes once installed), or NULL. Any thread (acquire). */
const uint8_t* ym2608_rhythm_rom(void);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_YM2608_RHYTHM_ROM_H_ */
