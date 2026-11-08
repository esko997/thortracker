/* The FamiStudio player builds a NES machine runs, described from their generated headers so the image
 * loader and the machine handle every build the same way:
 *
 *   nes_player_2a03  generated/nes_player_image.h       the 2A03 alone ("nes-famistudio"): 5 channels
 *   nes_player_vrc6  generated/nes_vrc6_player_image.h FAMISTUDIO_EXP_VRC6 = 1 ("nes-famistudio-vrc6"): the
 *                    2A03's 5 channels, then VRC6 pulse 1, pulse 2 and saw (engine channels 5, 6, 7)
 *   nes_player_epsm  generated/nes_epsm_player_image.h FAMISTUDIO_EXP_EPSM = 1 ("nes-famistudio-epsm"): the
 *                    2A03's 5 channels, then EPSM SSG 1-3 (5..7), FM 1-6 (8..13) and the six rhythm drums (14..19:
 *                    bass drum, snare, top cymbal, hi-hat, tom, rim shot)
 *
 * All are tools/audio/nes/nes_player.s assembled by tools/audio/prepare_nes_player.py: the same entry table
 * and image layout (header, envelope directory, samples from NES_SAMPLE_BASE, region up to NES_REGION_END);
 * they differ in player bytes and id, song base (the page after the player), channel streams in the song
 * header, engine envelope slots and RAM addresses, the VRC6 opcode/duty rules, and with the EPSM the song header's
 * expansion instrument pointer (FamiStudio's `instruments_exp`: 16-byte entries, each pointing at a 31-byte patch
 * block), the mixer and noise envelope kinds, the `$5C` FM release and `$5D` envelope period opcodes. */
#ifndef THORTRACKER_NES_PLAYER_H_
#define THORTRACKER_NES_PLAYER_H_

#include <stdint.h>

#include "generated/nes_epsm_player_image.h"
#include "generated/nes_player_image.h"
#include "generated/nes_vrc6_player_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define NM_MAX_CHANNELS 20        /* array sizes: the largest build's engine channels (EPSM) */
#define NM_MAX_ENVELOPES 35       /* ... engine envelope slots (volume, arpeggio, duty / EPSM SSG mixer, noise) */
#define NM_MAX_PITCH_ENVELOPES 12 /* ... engine pitch envelope slots */
#define NM_DPCM 4                 /* the DPCM channel (every build) */
#define NM_IMAGE_MAX_SIZE NES_IMAGE_SIZE /* the largest image (the 2A03 build's: its song base is lowest) */
#define NM_FIRST_EXP 5            /* the first expansion channel */
#define NM_EXP_INSTRUMENT_SIZE 16 /* FamiStudio's expansion instruments (EPSM): volume, arpeggio, pitch, mixer, noise
                                     words, envelope shape, auto-period octave, manual period word, patch pointer */
#define NM_EPSM_PATCH_SIZE 31     /* an EPSM patch block: $B0, $B4, 4 x ($30 $50 $60 $70 $80 $90), 4 x $40, $22 */
#define NM_EPSM_SSG1 5            /* the EPSM build's SSG 1 */
#define NM_EPSM_FM1 8             /* ... FM 1 */
#define NM_EPSM_RHYTHM1 14        /* ... the first rhythm drum (bass drum) */

enum { NES_EXPANSION_NONE = 0, NES_EXPANSION_VRC6 = 1, NES_EXPANSION_EPSM = 7 }; /* FamiStudio's ExpansionType */

/* Engine addresses the machine uses (RAM variables and ROM tables of that build). */
typedef struct NesPlayerSymbols {
    uint16_t song_speed, tempo_step_lo, tempo_step_hi, tempo_acc_lo, tempo_acc_hi;
    uint16_t instrument_lo, instrument_hi, dpcm_list_lo, dpcm_list_hi;
    uint16_t chn_ptr_lo, chn_ptr_hi, chn_note, chn_repeat, chn_return_lo, chn_return_hi, chn_ref_len;
    uint16_t chn_note_delay, chn_cut_delay;
    uint16_t env_value, env_repeat, env_addr_lo, env_addr_hi, env_ptr;
    uint16_t pitch_env_repeat, pitch_env_addr_lo, pitch_env_addr_hi, pitch_env_ptr;
    uint16_t dummy_envelope, dummy_pitch_envelope, channel_env;
} NesPlayerSymbols;

/* EPSM build only (all 0 otherwise): the expansion instrument table pointer; the SSGs' shared manual envelope period
 * (from the last manual-period instrument, or a `$5D`), the "`$5D` this frame" flag, per SSG the envelope shape (bit
 * 7: reset at the next update) and auto-period octave ($80: manual); per FM channel the instrument whose patch the
 * next update loads ($FF: none pending); per rhythm drum the level and pan the instrument set. */
typedef struct NesEpsmSymbols {
    uint16_t exp_instrument_lo, exp_instrument_hi;
    uint16_t env_period_lo, env_period_hi, env_override;
    uint16_t chn_env_shape, chn_env_octave;
    uint16_t chn_fm_instrument, chn_rhythm_volume, chn_rhythm_stereo;
} NesEpsmSymbols;

typedef struct NesPlayer {
    uint32_t id;                    /* image header bytes 4..7 */
    const uint8_t* code;            /* the player bytes at base */
    uint16_t base, size;
    uint16_t init, play, idle, trap; /* entry table (NSF conventions) */
    uint16_t song_base;             /* region = CPU memory song_base..NES_REGION_END */
    uint16_t region_size;
    uint32_t image_size;            /* header + directory + region */
    uint16_t ram_end;               /* engine RAM ends here (below NES_AUDITION_RAM) */
    uint16_t effect_envelopes;      /* first directory index of effect envelopes (0xy arpeggios, vibratos): 256, the
                                       EPSM build 384 (its mixer and noise envelopes take 256..383); indices below it
                                       belong to an instrument slot and keep it through live edits */
    uint8_t channels;               /* channel streams in the song header: 5, 8 or 20 */
    uint8_t envelope_channels;      /* channels with engine envelopes (famistudio_channel_env entries): all, except the
                                       EPSM's rhythm drums (14) */
    uint8_t envelopes, pitch_envelopes; /* engine envelope / pitch envelope slots */
    uint8_t duty_max;               /* largest duty value an envelope or `$4F` may hold: 3, or 7 with VRC6 pulses */
    uint8_t expansion;              /* NES_EXPANSION_* */
    uint8_t exp_instruments;        /* 1: the song header holds the expansion instrument pointer (EPSM) */
    NesPlayerSymbols sym;
    NesEpsmSymbols epsm;
} NesPlayer;

extern const NesPlayer nes_player_2a03;
extern const NesPlayer nes_player_vrc6;
extern const NesPlayer nes_player_epsm;

/* Bytes of the FamiStudio song header before the channel pointers: song count, instrument pointer, [expansion
 * instrument pointer,] DPCM list pointer - 4. */
static inline int nes_player_head(const NesPlayer* p) { return p->exp_instruments ? 7 : 5; }

/* Channel roles of an engine channel index of `p`: carries notes and instruments (not DPCM), has a pitch
 * envelope (fine pitch, vibrato), is a VRC6 channel (5..7), is an EPSM SSG (5..7), FM (8..13) or rhythm drum
 * (14..19). */
static inline int nes_player_tonal(const NesPlayer* p, int c) { return c >= 0 && c < p->channels && c != NM_DPCM; }
static inline int nes_player_pitched(const NesPlayer* p, int c) {
    return (c >= 0 && c <= 2) || (c >= NM_FIRST_EXP && c < p->envelope_channels);
}
static inline int nes_player_vrc6_channel(const NesPlayer* p, int c) { return p->expansion == NES_EXPANSION_VRC6 && c >= 5 && c < 8; }
static inline int nes_player_epsm_ssg(const NesPlayer* p, int c) {
    return p->expansion == NES_EXPANSION_EPSM && c >= NM_EPSM_SSG1 && c < NM_EPSM_FM1;
}
static inline int nes_player_epsm_fm(const NesPlayer* p, int c) {
    return p->expansion == NES_EXPANSION_EPSM && c >= NM_EPSM_FM1 && c < NM_EPSM_RHYTHM1;
}
static inline int nes_player_epsm_rhythm(const NesPlayer* p, int c) {
    return p->expansion == NES_EXPANSION_EPSM && c >= NM_EPSM_RHYTHM1 && c < NM_EPSM_RHYTHM1 + 6;
}

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_NES_PLAYER_H_ */
