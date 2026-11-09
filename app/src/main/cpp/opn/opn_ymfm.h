/* The YM2203 and YM2608 for the OPN machine: ymfm's ym2203 / ym2608 (Aaron Giles, BSD-3-Clause; third_party/ymfm,
 * unmodified) behind a C interface, generated one native sample at a time (one FM clock: 72 master clocks on the
 * YM2203, 144 on the YM2608, 55466.67 Hz at the PC-88/98 clocks of opn_image.h) with the SSG resampled 9:2 exactly as
 * ymfm's OPN_FIDELITY_MED resampler does at the power-on prescaler, and every part's output kept apart so tracker
 * channels can be muted (a muted part is left out of the sums only: its state, FM feedback included, advances
 * exactly as unmuted).
 *
 * Timers: ymfm asks its interface to schedule timer A / B (ymfm_set_timer); this wrapper counts master clocks per
 * native sample and calls the engine's timer expiry when one is due, so CSM mode ($27 bits 7-6 = 10) keys FM 3 on at
 * timer A's rate as on the chip. The player never loads timer B (ticks are timed by the machine, 16 x (256 - TB)
 * native samples each, the period timer B would have).
 *
 * YM2608 external memory: the ADPCM-B reads `adpcm` (set with opn_chip_set_adpcm: the machine's copy of the image's
 * ADPCM-B memory; reads past `size` return 0); the rhythm (ADPCM-A) reads the installed rhythm ROM
 * (ym2608_rhythm_rom.h). A rhythm key-on while no ROM is installed leaves that drum silent until its next key-on with
 * the ROM and sets the rhythm_missing flag (a diagnostic: the Kotlin side refuses such songs).
 *
 * ym/ym_ymfm.cpp compiles ymfm_opn.cpp once for the program and explicitly instantiates the register and engine
 * templates this wrapper uses; opn_ymfm.cpp includes only the headers. No allocation after opn_chip_create. */
#ifndef THORTRACKER_OPN_YMFM_H_
#define THORTRACKER_OPN_YMFM_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct OpnChip OpnChip;

/* Mute bits of opn_chip_generate: FM channels 0..5, SSG 0..2, rhythm drums 0..5, ADPCM-B. */
#define OPN_MUTE_FM(i) (1u << (i))
#define OPN_MUTE_SSG(i) (1u << (6 + (i)))
#define OPN_MUTE_RHYTHM(i) (1u << (9 + (i)))
#define OPN_MUTE_ADPCM (1u << 15)

/* One native sample, by part, in ymfm's units: FM left/right (the chip's FM sum with the YM2608's B4 pans applied; the
 * YM2203's mono FM in both), rhythm left/right (ADPCM-A, pans applied), ADPCM-B left/right (pan applied) and the SSG's
 * three channels (unsigned levels as ymfm's ssg_engine outputs them, averaged over the native sample). */
typedef struct OpnOutput {
    int32_t fm[2];
    int32_t rhythm[2];
    int32_t adpcm[2];
    int32_t ssg[3];
} OpnOutput;

/* Allocates a chip (OPN_CHIP_YM2203 / OPN_CHIP_YM2608) in its power-on state (the only allocation); NULL when out of
 * memory or for an unknown chip. */
OpnChip* opn_chip_create(int chip);
void opn_chip_destroy(OpnChip* c);
/* Back to exactly the state opn_chip_create gave (every counter, the timers and the SSG resampler phase included; the
 * ADPCM-B memory pointer is kept). No allocation. */
void opn_chip_reset(OpnChip* c);
/* YM2608: the ADPCM-B memory the chip reads (not copied; the caller keeps it alive and unchanged while generating). */
void opn_chip_set_adpcm(OpnChip* c, const uint8_t* adpcm, uint32_t size);
/* A register write: port 0 = $00-$FF, port 1 = $100-$1FF (YM2608). */
void opn_chip_write(OpnChip* c, int port, uint8_t reg, uint8_t value);
/* Clocks the chip one native sample and returns its output; `mute` = OPN_MUTE_* bits. */
void opn_chip_generate(OpnChip* c, uint32_t mute, OpnOutput* out);
/* 1 when every carrier of FM channel 0..5 (by its algorithm) is at or past ymfm's quiet attenuation, else 0. */
int opn_chip_fm_quiet(const OpnChip* c, int channel);
/* Envelope attenuation of FM channel 0..5's operator 1..4 (op 0..3; 0 loudest, 0x3FF silent). */
int opn_chip_fm_attenuation(const OpnChip* c, int channel, int op);
/* YM2608: rhythm drums keyed on and not yet at their end (bit per drum). */
uint32_t opn_chip_rhythm_playing(const OpnChip* c);
/* YM2608: 1 if a rhythm key-on found no ROM installed since the last reset. */
int opn_chip_rhythm_missing(const OpnChip* c);
/* YM2608: 1 while the ADPCM-B plays (started, not at its end). */
int opn_chip_adpcm_playing(const OpnChip* c);
/* Native samples clocked since create/reset, and timer A expiries since then (CSM diagnostics). */
uint64_t opn_chip_samples(const OpnChip* c);
uint32_t opn_chip_timer_a_expiries(const OpnChip* c);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_OPN_YMFM_H_ */
