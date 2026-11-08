/* The NES player builds (see nes_player.h), from their generated headers. */
#include "nes_player.h"

_Static_assert(NES_PLAYER_BASE + NES_PLAYER_SIZE <= NES_SONG_BASE, "2A03 player below its song region");
_Static_assert(NES_VRC6_PLAYER_BASE + NES_VRC6_PLAYER_SIZE <= NES_VRC6_SONG_BASE, "VRC6 player below its song region");
_Static_assert(NES_SONG_BASE + NES_IMAGE_REGION_SIZE == 0xFFFA && NES_VRC6_SONG_BASE + NES_VRC6_IMAGE_REGION_SIZE == 0xFFFA,
               "the regions end at the vectors");
_Static_assert(NES_REGION_END == 0xFFFA, "the region ends at the vectors");
_Static_assert(NES_VRC6_IMAGE_SIZE == NES_IMAGE_HEADER_SIZE + NES_IMAGE_DIRECTORY_SIZE + NES_VRC6_IMAGE_REGION_SIZE,
               "VRC6 image = the shared header + directory + its region");
_Static_assert(NES_VRC6_IMAGE_SIZE <= NM_IMAGE_MAX_SIZE, "image storage holds every build's images");
_Static_assert(NES_RAM_BASE + NES_RAM_SIZE <= NES_AUDITION_RAM && NES_VRC6_RAM_BASE + NES_VRC6_RAM_SIZE <= NES_AUDITION_RAM,
               "engine RAM below the audition streams");
_Static_assert(NES_NUM_ENVELOPES <= NM_MAX_ENVELOPES && NES_VRC6_NUM_ENVELOPES <= NM_MAX_ENVELOPES, "envelope slots");
_Static_assert(NES_NUM_PITCH_ENVELOPES <= NM_MAX_PITCH_ENVELOPES && NES_VRC6_NUM_PITCH_ENVELOPES <= NM_MAX_PITCH_ENVELOPES,
               "pitch envelope slots");
_Static_assert(NES_VRC6_CHANNELS <= NM_MAX_CHANNELS, "channel arrays");
_Static_assert(NES_VRC6_PLAYER_INIT == NES_PLAYER_INIT && NES_VRC6_PLAYER_PLAY == NES_PLAYER_PLAY, "one entry table");

/* Every N163 build: the shared layout, engine channels 5 + n, envelope slots 11 + 3n (N163 channel k: volume,
 * arpeggio and wave index from slot 11 + 3k), pitch envelope slots 3 + n. The 8-channel build sizes the arrays. */
#define N163_ASSERTS(N)                                                                                                    \
    _Static_assert(NES_N163_##N##_PLAYER_BASE + NES_N163_##N##_PLAYER_SIZE <= NES_N163_##N##_SONG_BASE,                    \
                   "N163 " #N " player below its song region");                                                            \
    _Static_assert(NES_N163_##N##_SONG_BASE + NES_N163_##N##_IMAGE_REGION_SIZE == NES_REGION_END,                          \
                   "N163 " #N " region ends at the vectors");                                                              \
    _Static_assert(NES_N163_##N##_IMAGE_SIZE ==                                                                            \
                       NES_IMAGE_HEADER_SIZE + NES_IMAGE_DIRECTORY_SIZE + NES_N163_##N##_IMAGE_REGION_SIZE,                \
                   "N163 " #N " image = the shared header + directory + its region");                                     \
    _Static_assert(NES_N163_##N##_IMAGE_SIZE <= NM_IMAGE_MAX_SIZE, "image storage holds the N163 " #N " images");         \
    _Static_assert(NES_N163_##N##_RAM_BASE + NES_N163_##N##_RAM_SIZE <= NES_AUDITION_RAM,                                  \
                   "N163 " #N " engine RAM below the audition streams");                                                  \
    _Static_assert(NES_N163_##N##_CHANNELS == 5 + (N) && NES_N163_##N##_CHANNELS <= NM_MAX_CHANNELS,                       \
                   "N163 " #N " channels");                                                                                \
    _Static_assert(NES_N163_##N##_NUM_ENVELOPES == 11 + 3 * (N) && NES_N163_##N##_NUM_ENVELOPES <= NM_MAX_ENVELOPES,       \
                   "N163 " #N " envelope slots");                                                                          \
    _Static_assert(NES_N163_##N##_NUM_PITCH_ENVELOPES == 3 + (N) &&                                                        \
                       NES_N163_##N##_NUM_PITCH_ENVELOPES <= NM_MAX_PITCH_ENVELOPES,                                       \
                   "N163 " #N " pitch envelope slots");                                                                    \
    _Static_assert(NES_N163_##N##_N163_CHANNELS == (N) && NES_N163_##N##_WAVE_RAM == 256 - 16 * (N) &&                  \
                       NES_N163_##N##_EXP_INSTRUMENT_SIZE == NM_EXP_INSTRUMENT_SIZE,                                       \
                   "N163 " #N ": channels, wave RAM below the registers, expansion instrument entries");                 \
    _Static_assert(NES_N163_##N##_PLAYER_INIT == NES_PLAYER_INIT && NES_N163_##N##_PLAYER_PLAY == NES_PLAYER_PLAY,         \
                   "one entry table")

N163_ASSERTS(1);
N163_ASSERTS(2);
N163_ASSERTS(3);
N163_ASSERTS(4);
N163_ASSERTS(5);
N163_ASSERTS(6);
N163_ASSERTS(7);
N163_ASSERTS(8);
_Static_assert(NES_N163_8_CHANNELS == NM_MAX_CHANNELS && NES_N163_8_NUM_ENVELOPES == NM_MAX_ENVELOPES &&
                   NES_N163_8_NUM_PITCH_ENVELOPES == NM_MAX_PITCH_ENVELOPES,
               "the 8-channel N163 build sizes the arrays");

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
    .channels = NES_VRC6_CHANNELS,
    .envelopes = NES_VRC6_NUM_ENVELOPES,
    .pitch_envelopes = NES_VRC6_NUM_PITCH_ENVELOPES,
    .duty_max = 7, /* famistudio_vrc6_duty_lookup: $00..$70 */
    .expansion = NES_EXPANSION_VRC6,
    .sym = SYMBOLS(NES_VRC6_FAMISTUDIO_),
};

#define N163_PLAYER(N)                                                                                                    \
    {                                                                                                                     \
        .id = NES_N163_##N##_PLAYER_ID, .code = nes_n163_##N##_player_code, .base = NES_N163_##N##_PLAYER_BASE,           \
        .size = NES_N163_##N##_PLAYER_SIZE, .init = NES_N163_##N##_PLAYER_INIT, .play = NES_N163_##N##_PLAYER_PLAY,       \
        .idle = NES_N163_##N##_PLAYER_IDLE, .trap = NES_N163_##N##_PLAYER_TRAP, .song_base = NES_N163_##N##_SONG_BASE,    \
        .region_size = NES_N163_##N##_IMAGE_REGION_SIZE, .image_size = NES_N163_##N##_IMAGE_SIZE,                         \
        .ram_end = NES_N163_##N##_RAM_BASE + NES_N163_##N##_RAM_SIZE, .channels = NES_N163_##N##_CHANNELS,                \
        .envelopes = NES_N163_##N##_NUM_ENVELOPES, .pitch_envelopes = NES_N163_##N##_NUM_PITCH_ENVELOPES,                 \
        .duty_max = 3, /* duty only on the 2A03 pulses and noise */                                                       \
        .expansion = NES_EXPANSION_N163, .exp_instruments = 1, .n163_channels = (N),                                      \
        .sym = SYMBOLS(NES_N163_##N##_FAMISTUDIO_),                                                                       \
        .n163 = {NES_N163_##N##_FAMISTUDIO_EXP_INSTRUMENT_LO, NES_N163_##N##_FAMISTUDIO_EXP_INSTRUMENT_HI,                \
                 NES_N163_##N##_FAMISTUDIO_CHN_N163_INSTRUMENT, NES_N163_##N##_FAMISTUDIO_CHN_N163_WAVE_INDEX,            \
                 NES_N163_##N##_FAMISTUDIO_CHN_N163_WAVE_LEN},                                                            \
    }

const NesPlayer nes_player_n163[NM_N163_BUILDS] = {
    N163_PLAYER(1), N163_PLAYER(2), N163_PLAYER(3), N163_PLAYER(4),
    N163_PLAYER(5), N163_PLAYER(6), N163_PLAYER(7), N163_PLAYER(8),
};
