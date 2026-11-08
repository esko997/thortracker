/* The FamiStudio player builds a NES machine runs, described from their generated headers so the image
 * loader and the machine handle every build the same way:
 *
 *   nes_player_2a03  generated/nes_player_image.h       the 2A03 alone ("nes-famistudio"): 5 channels
 *   nes_player_vrc6  generated/nes_vrc6_player_image.h FAMISTUDIO_EXP_VRC6 = 1 ("nes-famistudio-vrc6"): the
 *                    2A03's 5 channels, then VRC6 pulse 1, pulse 2 and saw (engine channels 5, 6, 7)
 *   nes_player_n163[n - 1]  generated/nes_n163_<n>_player_image.h, n = 1..8: FAMISTUDIO_EXP_N163 = 1 with
 *                    FAMISTUDIO_EXP_N163_CHN_CNT = n ("nes-famistudio-n163-<n>"): the 2A03's 5 channels, then
 *                    Namco 163 channels 1..n (engine channels 5..4+n)
 *
 * All are tools/audio/nes/nes_player.s assembled by tools/audio/prepare_nes_player.py: the same entry table
 * and image layout (header, envelope directory, samples from NES_SAMPLE_BASE, region up to NES_REGION_END);
 * they differ in player bytes and id, song base (the page after the player), channel streams in the song
 * header, engine envelope slots and RAM addresses, the VRC6 opcode/duty rules, and with N163 the song header's
 * expansion instrument pointer (FamiStudio's `instruments_exp`: 16-byte entries), the wave-index envelope (the
 * third envelope slot of each N163 channel) and the `$5A` release / `$5B` phase reset opcodes. */
#ifndef THORTRACKER_NES_PLAYER_H_
#define THORTRACKER_NES_PLAYER_H_

#include <stdint.h>

#include "generated/nes_player_image.h"
#include "generated/nes_vrc6_player_image.h"
#include "generated/nes_n163_1_player_image.h"
#include "generated/nes_n163_2_player_image.h"
#include "generated/nes_n163_3_player_image.h"
#include "generated/nes_n163_4_player_image.h"
#include "generated/nes_n163_5_player_image.h"
#include "generated/nes_n163_6_player_image.h"
#include "generated/nes_n163_7_player_image.h"
#include "generated/nes_n163_8_player_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define NM_MAX_CHANNELS 13        /* array sizes: the largest build's engine channels (N163 with 8 channels) */
#define NM_MAX_ENVELOPES 35       /* ... engine envelope slots (volume, arpeggio, duty/wave index per tonal channel) */
#define NM_MAX_PITCH_ENVELOPES 11 /* ... engine pitch envelope slots */
#define NM_DPCM 4                 /* the DPCM channel (every build) */
#define NM_IMAGE_MAX_SIZE NES_IMAGE_SIZE /* the largest image (the 2A03 build's: its song base is lowest) */
#define NM_FIRST_EXP 5            /* the first expansion channel */
#define NM_EXP_INSTRUMENT_SIZE 16 /* FamiStudio's expansion instruments (N163): vol, arp, pitch, wave-index
                                     envelope words, wave position, wave size, wave table word, 4 zero bytes */
#define NM_N163_BUILDS 8          /* nes_player_n163[n - 1]: n N163 channels */

enum { NES_EXPANSION_NONE = 0, NES_EXPANSION_VRC6 = 1, NES_EXPANSION_N163 = 3 }; /* 2: VRC7 (branch nes-vrc7) */

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

/* N163 builds only (all 0 otherwise): the expansion instrument table pointer, and per N163 channel the
 * expansion instrument index it loaded, the wave index uploaded last ($FF: upload on the next update) and the
 * length bits written with the frequency (256 - wave size). */
typedef struct NesN163Symbols {
    uint16_t exp_instrument_lo, exp_instrument_hi;
    uint16_t chn_n163_instrument, chn_n163_wave_index, chn_n163_wave_len;
} NesN163Symbols;

typedef struct NesPlayer {
    uint32_t id;                    /* image header bytes 4..7 */
    const uint8_t* code;            /* the player bytes at base */
    uint16_t base, size;
    uint16_t init, play, idle, trap; /* entry table (NSF conventions) */
    uint16_t song_base;             /* region = CPU memory song_base..NES_REGION_END */
    uint16_t region_size;
    uint32_t image_size;            /* header + directory + region */
    uint16_t ram_end;               /* engine RAM ends here (below NES_AUDITION_RAM) */
    uint8_t channels;               /* channel streams in the song header: 5, 8 (VRC6) or 5 + n (N163) */
    uint8_t envelopes, pitch_envelopes; /* engine envelope / pitch envelope slots */
    uint8_t duty_max;               /* largest duty value an envelope or `$4F` may hold: 3, or 7 with VRC6 pulses */
    uint8_t expansion;              /* NES_EXPANSION_* */
    uint8_t exp_instruments;        /* 1: the song header holds the expansion instrument pointer (N163) */
    uint8_t n163_channels;          /* N163 channels (engine channels 5..4+n), 0 for other builds */
    NesPlayerSymbols sym;
    NesN163Symbols n163;
} NesPlayer;

extern const NesPlayer nes_player_2a03;
extern const NesPlayer nes_player_vrc6;
extern const NesPlayer nes_player_n163[NM_N163_BUILDS];

/* The N163 build with `channels` N163 channels (1..8), else NULL. */
static inline const NesPlayer* nes_player_n163_build(int channels) {
    return channels >= 1 && channels <= NM_N163_BUILDS ? &nes_player_n163[channels - 1] : (const NesPlayer*)0;
}

/* Bytes of the FamiStudio song header before the channel pointers: song count, instrument pointer, [expansion
 * instrument pointer,] DPCM list pointer - 4. */
static inline int nes_player_head(const NesPlayer* p) { return p->exp_instruments ? 7 : 5; }

/* Channel roles of an engine channel index of `p`: carries notes and instruments (not DPCM), has a pitch
 * envelope (fine pitch, vibrato), is a VRC6 channel (5..7), is an N163 channel (5..4+n). */
static inline int nes_player_tonal(const NesPlayer* p, int c) { return c >= 0 && c < p->channels && c != NM_DPCM; }
static inline int nes_player_pitched(const NesPlayer* p, int c) { return (c >= 0 && c <= 2) || (c >= NM_FIRST_EXP && c < p->channels); }
static inline int nes_player_vrc6_channel(const NesPlayer* p, int c) { return p->expansion == NES_EXPANSION_VRC6 && c >= 5 && c < 8; }
static inline int nes_player_n163_channel(const NesPlayer* p, int c) {
    return p->expansion == NES_EXPANSION_N163 && c >= NM_FIRST_EXP && c < NM_FIRST_EXP + p->n163_channels;
}

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_NES_PLAYER_H_ */
