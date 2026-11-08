/* The NES player builds (see nes_player.h), from their generated headers. */
#include "nes_player.h"

_Static_assert(NES_PLAYER_BASE + NES_PLAYER_SIZE <= NES_SONG_BASE, "2A03 player below its song region");
_Static_assert(NES_VRC6_PLAYER_BASE + NES_VRC6_PLAYER_SIZE <= NES_VRC6_SONG_BASE, "VRC6 player below its song region");
_Static_assert(NES_S5B_PLAYER_BASE + NES_S5B_PLAYER_SIZE <= NES_S5B_SONG_BASE, "5B player below its song region");
_Static_assert(NES_SONG_BASE + NES_IMAGE_REGION_SIZE == 0xFFFA && NES_VRC6_SONG_BASE + NES_VRC6_IMAGE_REGION_SIZE == 0xFFFA &&
                   NES_S5B_SONG_BASE + NES_S5B_IMAGE_REGION_SIZE == 0xFFFA,
               "the regions end at the vectors");
_Static_assert(NES_REGION_END == 0xFFFA, "the region ends at the vectors");
_Static_assert(NES_VRC6_IMAGE_SIZE == NES_IMAGE_HEADER_SIZE + NES_IMAGE_DIRECTORY_SIZE + NES_VRC6_IMAGE_REGION_SIZE,
               "VRC6 image = the shared header + directory + its region");
_Static_assert(NES_S5B_IMAGE_SIZE == NES_IMAGE_HEADER_SIZE + NES_IMAGE_DIRECTORY_SIZE + NES_S5B_IMAGE_REGION_SIZE,
               "5B image = the shared header + directory + its region");
_Static_assert(NES_VRC6_IMAGE_SIZE <= NM_IMAGE_MAX_SIZE && NES_S5B_IMAGE_SIZE <= NM_IMAGE_MAX_SIZE,
               "image storage holds every build's images");
_Static_assert(NES_RAM_BASE + NES_RAM_SIZE <= NES_AUDITION_RAM && NES_VRC6_RAM_BASE + NES_VRC6_RAM_SIZE <= NES_AUDITION_RAM &&
                   NES_S5B_RAM_BASE + NES_S5B_RAM_SIZE <= NES_AUDITION_RAM,
               "engine RAM below the audition streams");
_Static_assert(NES_NUM_ENVELOPES <= NM_MAX_ENVELOPES && NES_VRC6_NUM_ENVELOPES <= NM_MAX_ENVELOPES &&
                   NES_S5B_NUM_ENVELOPES == NM_MAX_ENVELOPES,
               "envelope slots");
_Static_assert(NES_NUM_PITCH_ENVELOPES <= NM_MAX_PITCH_ENVELOPES && NES_VRC6_NUM_PITCH_ENVELOPES <= NM_MAX_PITCH_ENVELOPES &&
                   NES_S5B_NUM_PITCH_ENVELOPES <= NM_MAX_PITCH_ENVELOPES,
               "pitch envelope slots");
_Static_assert(NES_VRC6_CHANNELS <= NM_MAX_CHANNELS && NES_S5B_CHANNELS <= NM_MAX_CHANNELS, "channel arrays");
_Static_assert(NES_S5B_CHANNELS == NM_FIRST_EXP + 3, "the 2A03's five channels, then 5B square 1-3");
_Static_assert(NES_S5B_EXP_INSTRUMENT_SIZE == NM_EXP_INSTRUMENT_SIZE, "FamiStudio's expansion instrument entries");
_Static_assert(NES_S5B_EFFECT_ENVELOPES == 4 * NES_IMAGE_MAX_INSTRUMENTS + 2 * NES_IMAGE_MAX_INSTRUMENTS &&
                   NES_S5B_EFFECT_ENVELOPES < NES_IMAGE_MAX_ENVELOPES,
               "5B directory: 4 envelopes per slot, then a mixer and a noise envelope per slot, then effect envelopes");
_Static_assert(NES_VRC6_PLAYER_INIT == NES_PLAYER_INIT && NES_VRC6_PLAYER_PLAY == NES_PLAYER_PLAY &&
                   NES_S5B_PLAYER_INIT == NES_PLAYER_INIT && NES_S5B_PLAYER_PLAY == NES_PLAYER_PLAY,
               "one entry table");

#define SYMBOLS(P)                                                                                             \
    {                                                                                                          \
        P##SONG_SPEED, P##TEMPO_STEP_LO, P##TEMPO_STEP_HI, P##TEMPO_ACC_LO, P##TEMPO_ACC_HI, P##INSTRUMENT_LO, \
            P##INSTRUMENT_HI, P##DPCM_LIST_LO, P##DPCM_LIST_HI, P##CHN_PTR_LO, P##CHN_PTR_HI, P##CHN_NOTE,     \
            P##CHN_REPEAT, P##CHN_RETURN_LO, P##CHN_RETURN_HI, P##CHN_REF_LEN, P##CHN_NOTE_DELAY,               \
            P##CHN_CUT_DELAY, P##ENV_VALUE, P##ENV_REPEAT, P##ENV_ADDR_LO, P##ENV_ADDR_HI, P##ENV_PTR,          \
            P##PITCH_ENV_REPEAT, P##PITCH_ENV_ADDR_LO, P##PITCH_ENV_ADDR_HI, P##PITCH_ENV_PTR,                  \
            P##DUMMY_ENVELOPE, P##DUMMY_PITCH_ENVELOPE, P##CHANNEL_ENV,                                        \
    }

const NesPlayer nes_player_2a03 = {
    .id = NES_PLAYER_ID,
    .code = nes_player_code,
    .base = NES_PLAYER_BASE,
    .size = NES_PLAYER_SIZE,
    .init = NES_PLAYER_INIT,
    .play = NES_PLAYER_PLAY,
    .idle = NES_PLAYER_IDLE,
    .trap = NES_PLAYER_TRAP,
    .song_base = NES_SONG_BASE,
    .region_size = NES_IMAGE_REGION_SIZE,
    .image_size = NES_IMAGE_SIZE,
    .ram_end = NES_RAM_BASE + NES_RAM_SIZE,
    .effect_envelopes = 4 * NES_IMAGE_MAX_INSTRUMENTS,
    .channels = 5,
    .envelopes = NES_NUM_ENVELOPES,
    .pitch_envelopes = NES_NUM_PITCH_ENVELOPES,
    .duty_max = 3,
    .expansion = NES_EXPANSION_NONE,
    .sym = SYMBOLS(NES_FAMISTUDIO_),
};

const NesPlayer nes_player_vrc6 = {
    .id = NES_VRC6_PLAYER_ID,
    .code = nes_vrc6_player_code,
    .base = NES_VRC6_PLAYER_BASE,
    .size = NES_VRC6_PLAYER_SIZE,
    .init = NES_VRC6_PLAYER_INIT,
    .play = NES_VRC6_PLAYER_PLAY,
    .idle = NES_VRC6_PLAYER_IDLE,
    .trap = NES_VRC6_PLAYER_TRAP,
    .song_base = NES_VRC6_SONG_BASE,
    .region_size = NES_VRC6_IMAGE_REGION_SIZE,
    .image_size = NES_VRC6_IMAGE_SIZE,
    .ram_end = NES_VRC6_RAM_BASE + NES_VRC6_RAM_SIZE,
    .effect_envelopes = 4 * NES_IMAGE_MAX_INSTRUMENTS,
    .channels = NES_VRC6_CHANNELS,
    .envelopes = NES_VRC6_NUM_ENVELOPES,
    .pitch_envelopes = NES_VRC6_NUM_PITCH_ENVELOPES,
    .duty_max = 7, /* famistudio_vrc6_duty_lookup: $00..$70 */
    .expansion = NES_EXPANSION_VRC6,
    .sym = SYMBOLS(NES_VRC6_FAMISTUDIO_),
};

const NesPlayer nes_player_s5b = {
    .id = NES_S5B_PLAYER_ID,
    .code = nes_s5b_player_code,
    .base = NES_S5B_PLAYER_BASE,
    .size = NES_S5B_PLAYER_SIZE,
    .init = NES_S5B_PLAYER_INIT,
    .play = NES_S5B_PLAYER_PLAY,
    .idle = NES_S5B_PLAYER_IDLE,
    .trap = NES_S5B_PLAYER_TRAP,
    .song_base = NES_S5B_SONG_BASE,
    .region_size = NES_S5B_IMAGE_REGION_SIZE,
    .image_size = NES_S5B_IMAGE_SIZE,
    .ram_end = NES_S5B_RAM_BASE + NES_S5B_RAM_SIZE,
    .effect_envelopes = NES_S5B_EFFECT_ENVELOPES,
    .channels = NES_S5B_CHANNELS,
    .envelopes = NES_S5B_NUM_ENVELOPES,
    .pitch_envelopes = NES_S5B_NUM_PITCH_ENVELOPES,
    .duty_max = 3, /* duty only on the 2A03 pulses and noise */
    .expansion = NES_EXPANSION_S5B,
    .exp_instruments = 1,
    .sym = SYMBOLS(NES_S5B_FAMISTUDIO_),
    .s5b = {NES_S5B_FAMISTUDIO_EXP_INSTRUMENT_LO, NES_S5B_FAMISTUDIO_EXP_INSTRUMENT_HI, NES_S5B_FAMISTUDIO_S5B_ENV_PERIOD_LO,
            NES_S5B_FAMISTUDIO_S5B_ENV_PERIOD_HI, NES_S5B_FAMISTUDIO_S5B_ENV_OVERRIDE, NES_S5B_FAMISTUDIO_S5B_CHN_ENV_SHAPE,
            NES_S5B_FAMISTUDIO_S5B_CHN_ENV_OCTAVE},
};
