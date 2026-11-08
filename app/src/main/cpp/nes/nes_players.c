/* The NES player builds (see nes_player.h), from their generated headers. */
#include "nes_player.h"

_Static_assert(NES_PLAYER_BASE + NES_PLAYER_SIZE <= NES_SONG_BASE, "2A03 player below its song region");
_Static_assert(NES_VRC6_PLAYER_BASE + NES_VRC6_PLAYER_SIZE <= NES_VRC6_SONG_BASE, "VRC6 player below its song region");
_Static_assert(NES_MMC5_PLAYER_BASE + NES_MMC5_PLAYER_SIZE <= NES_MMC5_SONG_BASE, "MMC5 player below its song region");
_Static_assert(NES_SONG_BASE + NES_IMAGE_REGION_SIZE == 0xFFFA && NES_VRC6_SONG_BASE + NES_VRC6_IMAGE_REGION_SIZE == 0xFFFA &&
                   NES_MMC5_SONG_BASE + NES_MMC5_IMAGE_REGION_SIZE == 0xFFFA,
               "the regions end at the vectors");
_Static_assert(NES_REGION_END == 0xFFFA, "the region ends at the vectors");
_Static_assert(NES_VRC6_IMAGE_SIZE == NES_IMAGE_HEADER_SIZE + NES_IMAGE_DIRECTORY_SIZE + NES_VRC6_IMAGE_REGION_SIZE,
               "VRC6 image = the shared header + directory + its region");
_Static_assert(NES_MMC5_IMAGE_SIZE == NES_IMAGE_HEADER_SIZE + NES_IMAGE_DIRECTORY_SIZE + NES_MMC5_IMAGE_REGION_SIZE,
               "MMC5 image = the shared header + directory + its region");
_Static_assert(NES_VRC6_IMAGE_SIZE <= NM_IMAGE_MAX_SIZE && NES_MMC5_IMAGE_SIZE <= NM_IMAGE_MAX_SIZE,
               "image storage holds every build's images");
_Static_assert(NES_RAM_BASE + NES_RAM_SIZE <= NES_AUDITION_RAM && NES_VRC6_RAM_BASE + NES_VRC6_RAM_SIZE <= NES_AUDITION_RAM &&
                   NES_MMC5_RAM_BASE + NES_MMC5_RAM_SIZE <= NES_AUDITION_RAM,
               "engine RAM below the audition streams");
_Static_assert(NES_NUM_ENVELOPES <= NM_MAX_ENVELOPES && NES_VRC6_NUM_ENVELOPES <= NM_MAX_ENVELOPES &&
                   NES_MMC5_NUM_ENVELOPES <= NM_MAX_ENVELOPES,
               "envelope slots");
_Static_assert(NES_NUM_PITCH_ENVELOPES <= NM_MAX_PITCH_ENVELOPES && NES_VRC6_NUM_PITCH_ENVELOPES <= NM_MAX_PITCH_ENVELOPES &&
                   NES_MMC5_NUM_PITCH_ENVELOPES <= NM_MAX_PITCH_ENVELOPES,
               "pitch envelope slots");
_Static_assert(NES_VRC6_CHANNELS == NM_MAX_CHANNELS && NES_MMC5_CHANNELS <= NM_MAX_CHANNELS, "channel arrays");
_Static_assert(NES_MMC5_CHANNELS == 7, "MMC5 build: the 2A03's 5 channels + 2 pulses");
_Static_assert(NES_VRC6_PLAYER_INIT == NES_PLAYER_INIT && NES_VRC6_PLAYER_PLAY == NES_PLAYER_PLAY &&
                   NES_MMC5_PLAYER_INIT == NES_PLAYER_INIT && NES_MMC5_PLAYER_PLAY == NES_PLAYER_PLAY,
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

const NesPlayer nes_player_mmc5 = {
    .id = NES_MMC5_PLAYER_ID,
    .code = nes_mmc5_player_code,
    .base = NES_MMC5_PLAYER_BASE,
    .size = NES_MMC5_PLAYER_SIZE,
    .init = NES_MMC5_PLAYER_INIT,
    .play = NES_MMC5_PLAYER_PLAY,
    .idle = NES_MMC5_PLAYER_IDLE,
    .trap = NES_MMC5_PLAYER_TRAP,
    .song_base = NES_MMC5_SONG_BASE,
    .region_size = NES_MMC5_IMAGE_REGION_SIZE,
    .image_size = NES_MMC5_IMAGE_SIZE,
    .ram_end = NES_MMC5_RAM_BASE + NES_MMC5_RAM_SIZE,
    .channels = NES_MMC5_CHANNELS,
    .envelopes = NES_MMC5_NUM_ENVELOPES,
    .pitch_envelopes = NES_MMC5_NUM_PITCH_ENVELOPES,
    .duty_max = 3, /* the MMC5 pulses use the 2A03 duty lookup */
    .expansion = NES_EXPANSION_MMC5,
    .sym = SYMBOLS(NES_MMC5_FAMISTUDIO_),
};
