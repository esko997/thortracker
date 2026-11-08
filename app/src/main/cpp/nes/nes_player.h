/* The FamiStudio player builds a NES machine runs, described from their generated headers so the image
 * loader and the machine handle every build the same way:
 *
 *   nes_player_2a03  generated/nes_player_image.h       the 2A03 alone ("nes-famistudio"): 5 channels
 *   nes_player_vrc6  generated/nes_vrc6_player_image.h FAMISTUDIO_EXP_VRC6 = 1 ("nes-famistudio-vrc6"): the
 *                    2A03's 5 channels, then VRC6 pulse 1, pulse 2 and saw (engine channels 5, 6, 7)
 *
 * Both are tools/audio/nes/nes_player.s assembled by tools/audio/prepare_nes_player.py: the same entry table
 * and image layout (header, envelope directory, samples from NES_SAMPLE_BASE, region up to NES_REGION_END);
 * they differ in player bytes and id, song base (the page after the player), channel streams in the song
 * header, engine envelope slots and RAM addresses, and the VRC6 opcode/duty rules. */
#ifndef THORTRACKER_NES_PLAYER_H_
#define THORTRACKER_NES_PLAYER_H_

#include <stdint.h>

#include "generated/nes_player_image.h"
#include "generated/nes_vrc6_player_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define NM_MAX_CHANNELS 8         /* array sizes: the largest build's engine channels */
#define NM_MAX_ENVELOPES 20       /* ... engine envelope slots (volume, arpeggio, duty per tonal channel) */
#define NM_MAX_PITCH_ENVELOPES 6  /* ... engine pitch envelope slots */
#define NM_DPCM 4                 /* the DPCM channel (both builds) */
#define NM_IMAGE_MAX_SIZE NES_IMAGE_SIZE /* the largest image (the 2A03 build's: its song base is lowest) */

enum { NES_EXPANSION_NONE = 0, NES_EXPANSION_VRC6 = 1 };

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

typedef struct NesPlayer {
    uint32_t id;                    /* image header bytes 4..7 */
    const uint8_t* code;            /* the player bytes at base */
    uint16_t base, size;
    uint16_t init, play, idle, trap; /* entry table (NSF conventions) */
    uint16_t song_base;             /* region = CPU memory song_base..NES_REGION_END */
    uint16_t region_size;
    uint32_t image_size;            /* header + directory + region */
    uint16_t ram_end;               /* engine RAM ends here (below NES_AUDITION_RAM) */
    uint8_t channels;               /* channel streams in the song header: 5 or 8 */
    uint8_t envelopes, pitch_envelopes; /* engine envelope / pitch envelope slots */
    uint8_t duty_max;               /* largest duty value an envelope or `$4F` may hold: 3, or 7 with VRC6 pulses */
    uint8_t expansion;              /* NES_EXPANSION_* */
    NesPlayerSymbols sym;
} NesPlayer;

extern const NesPlayer nes_player_2a03;
extern const NesPlayer nes_player_vrc6;

/* Channel roles of an engine channel index of `p`: carries notes and instruments (not DPCM), has a pitch
 * envelope (fine pitch, vibrato), is a VRC6 channel (5..7). */
static inline int nes_player_tonal(const NesPlayer* p, int c) { return c >= 0 && c < p->channels && c != NM_DPCM; }
static inline int nes_player_pitched(const NesPlayer* p, int c) { return (c >= 0 && c <= 2) || (c >= 5 && c < p->channels); }
static inline int nes_player_vrc6_channel(const NesPlayer* p, int c) { return p->expansion == NES_EXPANSION_VRC6 && c >= 5 && c < 8; }

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_NES_PLAYER_H_ */
