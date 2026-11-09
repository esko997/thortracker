/* The FamiStudio player builds a NES machine runs, described from their generated headers so the image
 * loader and the machine handle every build the same way:
 *
 *   nes_player_2a03  generated/nes_player_image.h       the 2A03 alone ("nes-famistudio"): 5 channels
 *   nes_player_vrc6  generated/nes_vrc6_player_image.h FAMISTUDIO_EXP_VRC6 = 1 ("nes-famistudio-vrc6"): the
 *                    2A03's 5 channels, then VRC6 pulse 1, pulse 2 and saw (engine channels 5, 6, 7)
 *   nes_player_vrc7  generated/nes_vrc7_player_image.h FAMISTUDIO_EXP_VRC7 = 1 ("nes-famistudio-vrc7"): the
 *                    2A03's 5 channels, then VRC7 FM 1-6 (engine channels 5..10)
 *   nes_player_n163[n - 1]  generated/nes_n163_<n>_player_image.h, n = 1..8: FAMISTUDIO_EXP_N163 = 1 with
 *                    FAMISTUDIO_EXP_N163_CHN_CNT = n ("nes-famistudio-n163-<n>"): the 2A03's 5 channels, then
 *                    Namco 163 channels 1..n (engine channels 5..4+n)
 *   nes_player_fds   generated/nes_fds_player_image.h  FAMISTUDIO_EXP_FDS = 1 with FAMISTUDIO_USE_FDS_AUTOMOD = 1
 *                    ("nes-famistudio-fds"): the 2A03's 5 channels, then the FDS channel (engine channel 5)
 *   nes_player_s5b   generated/nes_s5b_player_image.h  FAMISTUDIO_EXP_S5B = 1 ("nes-famistudio-s5b"): the
 *                    2A03's 5 channels, then Sunsoft 5B square 1, 2 and 3 (engine channels 5, 6, 7)
 *   nes_player_epsm  generated/nes_epsm_player_image.h FAMISTUDIO_EXP_EPSM = 1 ("nes-famistudio-epsm"): the
 *                    2A03's 5 channels, then EPSM SSG 1-3 (5..7), FM 1-6 (8..13) and the six rhythm drums (14..19:
 *                    bass drum, snare, top cymbal, hi-hat, tom, rim shot)
 *
 * All are tools/audio/nes/nes_player.s assembled by tools/audio/prepare_nes_player.py: the same entry table
 * and image layout (header, envelope directory, samples from NES_SAMPLE_BASE, region up to NES_REGION_END);
 * they differ in player bytes and id, song base (the page after the player), channel streams in the song
 * header, engine envelope slots and RAM addresses, the VRC6 opcode/duty rules, with VRC7 the song header's
 * expansion instrument pointer (FamiStudio's `instruments_exp`: 16-byte entries) and the `$56` release opcode,
 * with N163 the same expansion instrument pointer, the wave-index envelope (the third envelope slot of each N163
 * channel) and the `$5A` release / `$5B` phase reset opcodes, with FDS the same expansion instrument pointer and
 * wave-index envelope (the third envelope slot of the FDS channel) and the `$57` mod speed / `$58` mod depth / `$59`
 * release opcodes, with the 5B the same expansion instrument pointer, the mixer and noise envelope kinds
 * (envelope slots 2 and 3 of each 5B square) and the `$5E` envelope period opcode, and with the EPSM the same
 * expansion instrument pointer (each entry also pointing at a 31-byte FM patch block), the mixer and noise envelope
 * kinds (envelope slots 2 and 3 of each SSG), the `$5C` FM release and `$5D` envelope period opcodes. The FDS, 5B and
 * EPSM builds also play Thor Tracker's `$5F` (each its own scoped engine patch, prepare_nes_player.py). */
#ifndef THORTRACKER_NES_PLAYER_H_
#define THORTRACKER_NES_PLAYER_H_

#include <stdint.h>

#include "generated/nes_player_image.h"
#include "generated/nes_s5b_player_image.h"
#include "generated/nes_vrc6_player_image.h"
#include "generated/nes_vrc7_player_image.h"
#include "generated/nes_n163_1_player_image.h"
#include "generated/nes_n163_2_player_image.h"
#include "generated/nes_n163_3_player_image.h"
#include "generated/nes_n163_4_player_image.h"
#include "generated/nes_n163_5_player_image.h"
#include "generated/nes_n163_6_player_image.h"
#include "generated/nes_n163_7_player_image.h"
#include "generated/nes_n163_8_player_image.h"
#include "generated/nes_fds_player_image.h"
#include "generated/nes_epsm_player_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define NM_MAX_CHANNELS 20        /* array sizes: the largest build's engine channels (EPSM; N163 with 8: 13) */
#define NM_MAX_ENVELOPES 35       /* ... engine envelope slots (volume, arpeggio, duty/wave index per tonal channel;
                                     5B squares and EPSM SSGs: volume, arpeggio, mixer, noise; EPSM FM: volume,
                                     arpeggio; the EPSM rhythm drums none) */
#define NM_MAX_PITCH_ENVELOPES 12 /* ... engine pitch envelope slots */
#define NM_DPCM 4                 /* the DPCM channel (every build) */
#define NM_IMAGE_MAX_SIZE NES_IMAGE_SIZE /* the largest image (the 2A03 build's: its song base is lowest) */
#define NM_FIRST_EXP 5            /* the first expansion channel */
#define NM_EXP_INSTRUMENT_SIZE 16 /* FamiStudio's expansion instruments: vol, arp, pitch envelope words, then VRC7:
                                     overrides, patch << 4, the custom patch's 8 registers; N163: wave-index envelope
                                     word, wave position, wave size, wave table word, 4 zero bytes; FDS: wave-index
                                     envelope word, mod depth << 2 | master volume, wave table word, mod table word,
                                     mod speed (nes_image.h), mod delay; 5B: mixer and noise envelope words, envelope
                                     shape, auto-period octave, manual period word, 2 zeros; EPSM: as the 5B, but the
                                     last word points at the instrument's patch block */
#define NM_N163_BUILDS 8          /* nes_player_n163[n - 1]: n N163 channels */
#define NM_FDS 5                  /* the FDS build's FDS channel */
#define NM_EPSM_PATCH_SIZE 31     /* an EPSM patch block: $B0, $B4, 4 x ($30 $50 $60 $70 $80 $90), 4 x $40, $22 */
#define NM_EPSM_SSG1 5            /* the EPSM build's SSG 1 */
#define NM_EPSM_FM1 8             /* ... FM 1 */
#define NM_EPSM_RHYTHM1 14        /* ... the first rhythm drum (bass drum) */

/* The NSF expansion sound flag bit of each build's chip (EPSM: NSF2's bit 7). */
enum {
    NES_EXPANSION_NONE = 0,
    NES_EXPANSION_VRC6 = 1,
    NES_EXPANSION_VRC7 = 2,
    NES_EXPANSION_FDS = 4,
    NES_EXPANSION_N163 = 0x10,
    NES_EXPANSION_S5B = 0x20,
    NES_EXPANSION_EPSM = 0x80
};

/* Engine addresses the machine uses (RAM variables and ROM tables of that build). */
typedef struct NesPlayerSymbols {
    uint16_t song_speed, tempo_step_lo, tempo_step_hi, tempo_acc_lo, tempo_acc_hi;
    uint16_t instrument_lo, instrument_hi, dpcm_list_lo, dpcm_list_hi;
    uint16_t chn_ptr_lo, chn_ptr_hi, chn_note, chn_repeat, chn_return_lo, chn_return_hi, chn_ref_len;
    uint16_t chn_note_delay, chn_cut_delay;
    uint16_t env_value, env_repeat, env_addr_lo, env_addr_hi, env_ptr;
    uint16_t pitch_env_repeat, pitch_env_addr_lo, pitch_env_addr_hi, pitch_env_ptr;
    uint16_t dummy_envelope, dummy_pitch_envelope, channel_env;
    /* famistudio_set_instrument (r0 = channel, A = instrument index; r1 bit 7 = no attack) and its inputs: the seek
     * fallback loads the instrument each channel holds at the order start (nes_machine.c, restore_instruments). */
    uint16_t r0, r1, set_instrument;
} NesPlayerSymbols;

/* VRC7 build only (all 0 otherwise): the expansion instrument table pointer, the per-FM-channel patch byte
 * (patch << 4, ORed into the $3x volume write), the global release/stop override byte (from the last loaded
 * instrument), the FM channels' last $2x value and their trigger flags (bit 0 attack, bit 7 release). */
typedef struct NesVrc7Symbols {
    uint16_t exp_instrument_lo, exp_instrument_hi;
    uint16_t chn_vrc7_patch, chn_vrc7_sustain, chn_vrc7_prev_hi, chn_vrc7_trigger;
} NesVrc7Symbols;

/* N163 builds only (all 0 otherwise): the expansion instrument table pointer, and per N163 channel the
 * expansion instrument index it loaded, the wave index uploaded last ($FF: upload on the next update) and the
 * length bits written with the frequency (256 - wave size). */
typedef struct NesN163Symbols {
    uint16_t exp_instrument_lo, exp_instrument_hi;
    uint16_t chn_n163_instrument, chn_n163_wave_index, chn_n163_wave_len;
} NesN163Symbols;

/* The FDS build only (all 0 otherwise): the expansion instrument table pointer and the engine's FDS variables
 * (famistudio_<name>): the expansion instrument index the FDS channel loaded, the mod table pointer loaded into the
 * chip last (2 bytes), the mod speed word (2 bytes), depth, delay and its counter, the override flags of `$57`/`$58`
 * (bit 7 speed, bit 6 depth; cleared every update), the wave index uploaded last ($FF: upload on the next update),
 * the auto-mod numerator (0: manual speed) and denominator, and the last $4083 value (phase reset). */
typedef struct NesFdsSymbols {
    uint16_t exp_instrument_lo, exp_instrument_hi;
    uint16_t chn_fds_instrument, fds_mod_envelope, fds_mod_speed, fds_mod_depth, fds_mod_delay, fds_mod_delay_counter;
    uint16_t fds_override_flags, fds_wave_index, fds_automod_numer, fds_automod_denom, fds_prev_hi;
} NesFdsSymbols;
/* 5B build only (all 0 otherwise): the expansion instrument table pointer, the shared manual envelope period (from
 * the last instrument with a manual period, or a `$5E`), the "`$5E` this frame" flag, and per 5B channel the
 * envelope shape (bit 7: reset at the next update, after a note attack; low nibble 0 or $8-$F) and auto-period
 * octave ($80: manual). */
typedef struct NesS5bSymbols {
    uint16_t exp_instrument_lo, exp_instrument_hi;
    uint16_t env_period_lo, env_period_hi, env_override;
    uint16_t chn_env_shape, chn_env_octave;
} NesS5bSymbols;
/* EPSM build only (all 0 otherwise): `ssg` as the 5B's for the three SSGs (famistudio_epsm_*: the "`$5D` this frame"
 * flag); per FM channel the instrument whose patch the next update loads ($FF: none pending;
 * famistudio_set_epsm_instrument stores the index there); per rhythm drum the level and pan its instrument set; and
 * famistudio_ptr1 / famistudio_r3, which hold the expansion entry (ptr1 + r3) while famistudio_set_epsm_instrument
 * loads a rhythm drum's level and pan. */
typedef struct NesEpsmSymbols {
    NesS5bSymbols ssg;
    uint16_t chn_fm_instrument, chn_rhythm_volume, chn_rhythm_stereo;
    uint16_t ptr1, r3;
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
    uint16_t effect_envelopes;      /* first directory index of effect envelopes (0xy arpeggios, vibratos): 256, N163
                                       builds and the FDS build 320 (their wave-index envelopes take 256..319), the 5B
                                       and EPSM builds 384 (their mixer and noise envelopes take 256..383); indices below
                                       it belong to an instrument slot and keep it through live edits */
    uint8_t channels;               /* channel streams in the song header: 5, 6 (FDS), 8 (VRC6, 5B), 11 (VRC7), 5 + n
                                       (N163) or 20 (EPSM) */
    uint8_t envelope_channels;      /* channels with engine envelopes (famistudio_channel_env and
                                       famistudio_channel_to_pitch_env entries): `channels`, except the EPSM build's 14
                                       (its rhythm drums have none) */
    uint8_t envelopes, pitch_envelopes; /* engine envelope / pitch envelope slots */
    uint8_t duty_max;               /* largest duty value an envelope or `$4F` may hold: 3, or 7 with VRC6 pulses */
    uint8_t expansion;              /* NES_EXPANSION_* */
    uint8_t exp_instruments;        /* 1: the song header holds the expansion instrument pointer (VRC7, N163, FDS, 5B,
                                       EPSM) */
    uint8_t n163_channels;          /* N163 channels (engine channels 5..4+n), 0 for other builds */
    NesPlayerSymbols sym;
    NesVrc7Symbols vrc7;
    NesN163Symbols n163;
    NesFdsSymbols fds;
    NesS5bSymbols s5b;
    NesEpsmSymbols epsm;
} NesPlayer;

extern const NesPlayer nes_player_2a03;
extern const NesPlayer nes_player_vrc6;
extern const NesPlayer nes_player_vrc7;
extern const NesPlayer nes_player_n163[NM_N163_BUILDS];
extern const NesPlayer nes_player_fds;
extern const NesPlayer nes_player_s5b;
extern const NesPlayer nes_player_epsm;

/* The N163 build with `channels` N163 channels (1..8), else NULL. */
static inline const NesPlayer* nes_player_n163_build(int channels) {
    return channels >= 1 && channels <= NM_N163_BUILDS ? &nes_player_n163[channels - 1] : (const NesPlayer*)0;
}

/* Bytes of the FamiStudio song header before the channel pointers: song count, instrument pointer, [expansion
 * instrument pointer,] DPCM list pointer - 4. */
static inline int nes_player_head(const NesPlayer* p) { return p->exp_instruments ? 7 : 5; }

/* Channel roles of an engine channel index of `p`: carries notes and instruments (not DPCM), has a pitch
 * envelope (fine pitch, vibrato), is a VRC6 channel (5..7), is a VRC7 FM channel (5..10), is an N163 channel
 * (5..4+n), is the FDS channel (5), has a wave-index envelope (N163 and FDS channels: third envelope slot,
 * FAMISTUDIO_ENV_N163/FDS_WAVE_IDX_OFF = 2), is a 5B square (5..7), is an EPSM SSG (5..7), FM channel (8..13) or
 * rhythm drum (14..19). */
static inline int nes_player_tonal(const NesPlayer* p, int c) { return c >= 0 && c < p->channels && c != NM_DPCM; }
static inline int nes_player_pitched(const NesPlayer* p, int c) {
    return (c >= 0 && c <= 2) || (c >= NM_FIRST_EXP && c < p->envelope_channels);
}
static inline int nes_player_vrc6_channel(const NesPlayer* p, int c) { return p->expansion == NES_EXPANSION_VRC6 && c >= 5 && c < 8; }
static inline int nes_player_vrc7_channel(const NesPlayer* p, int c) { return p->expansion == NES_EXPANSION_VRC7 && c >= 5 && c < 11; }
static inline int nes_player_n163_channel(const NesPlayer* p, int c) {
    return p->expansion == NES_EXPANSION_N163 && c >= NM_FIRST_EXP && c < NM_FIRST_EXP + p->n163_channels;
}
static inline int nes_player_fds_channel(const NesPlayer* p, int c) { return p->expansion == NES_EXPANSION_FDS && c == NM_FDS; }
static inline int nes_player_wave_channel(const NesPlayer* p, int c) {
    return nes_player_n163_channel(p, c) || nes_player_fds_channel(p, c);
}
static inline int nes_player_s5b_channel(const NesPlayer* p, int c) { return p->expansion == NES_EXPANSION_S5B && c >= 5 && c < 8; }
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
