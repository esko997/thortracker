/* The NES player builds (see nes_player.h), from their generated headers. */
#include "nes_player.h"

_Static_assert(NES_PLAYER_BASE + NES_PLAYER_SIZE <= NES_SONG_BASE, "2A03 player below its song region");
_Static_assert(NES_VRC6_PLAYER_BASE + NES_VRC6_PLAYER_SIZE <= NES_VRC6_SONG_BASE, "VRC6 player below its song region");
_Static_assert(NES_VRC7_PLAYER_BASE + NES_VRC7_PLAYER_SIZE <= NES_VRC7_SONG_BASE, "VRC7 player below its song region");
_Static_assert(NES_S5B_PLAYER_BASE + NES_S5B_PLAYER_SIZE <= NES_S5B_SONG_BASE, "5B player below its song region");
_Static_assert(NES_MMC5_PLAYER_BASE + NES_MMC5_PLAYER_SIZE <= NES_MMC5_SONG_BASE, "MMC5 player below its song region");
_Static_assert(NES_SONG_BASE + NES_IMAGE_REGION_SIZE == 0xFFFA && NES_VRC6_SONG_BASE + NES_VRC6_IMAGE_REGION_SIZE == 0xFFFA &&
                   NES_VRC7_SONG_BASE + NES_VRC7_IMAGE_REGION_SIZE == 0xFFFA && NES_S5B_SONG_BASE + NES_S5B_IMAGE_REGION_SIZE == 0xFFFA &&
                   NES_MMC5_SONG_BASE + NES_MMC5_IMAGE_REGION_SIZE == 0xFFFA,
               "the regions end at the vectors");
_Static_assert(NES_REGION_END == 0xFFFA, "the region ends at the vectors");
_Static_assert(NES_VRC6_IMAGE_SIZE == NES_IMAGE_HEADER_SIZE + NES_IMAGE_DIRECTORY_SIZE + NES_VRC6_IMAGE_REGION_SIZE,
               "VRC6 image = the shared header + directory + its region");
_Static_assert(NES_VRC7_IMAGE_SIZE == NES_IMAGE_HEADER_SIZE + NES_IMAGE_DIRECTORY_SIZE + NES_VRC7_IMAGE_REGION_SIZE,
               "VRC7 image = the shared header + directory + its region");
_Static_assert(NES_S5B_IMAGE_SIZE == NES_IMAGE_HEADER_SIZE + NES_IMAGE_DIRECTORY_SIZE + NES_S5B_IMAGE_REGION_SIZE,
               "5B image = the shared header + directory + its region");
_Static_assert(NES_MMC5_IMAGE_SIZE == NES_IMAGE_HEADER_SIZE + NES_IMAGE_DIRECTORY_SIZE + NES_MMC5_IMAGE_REGION_SIZE,
               "MMC5 image = the shared header + directory + its region");
_Static_assert(NES_VRC6_IMAGE_SIZE <= NM_IMAGE_MAX_SIZE && NES_VRC7_IMAGE_SIZE <= NM_IMAGE_MAX_SIZE &&
                   NES_S5B_IMAGE_SIZE <= NM_IMAGE_MAX_SIZE && NES_MMC5_IMAGE_SIZE <= NM_IMAGE_MAX_SIZE,
               "image storage holds every build's images");
_Static_assert(NES_RAM_BASE + NES_RAM_SIZE <= NES_AUDITION_RAM && NES_VRC6_RAM_BASE + NES_VRC6_RAM_SIZE <= NES_AUDITION_RAM &&
                   NES_VRC7_RAM_BASE + NES_VRC7_RAM_SIZE <= NES_AUDITION_RAM && NES_S5B_RAM_BASE + NES_S5B_RAM_SIZE <= NES_AUDITION_RAM &&
                   NES_MMC5_RAM_BASE + NES_MMC5_RAM_SIZE <= NES_AUDITION_RAM,
               "engine RAM below the audition streams");
_Static_assert(NES_NUM_ENVELOPES <= NM_MAX_ENVELOPES && NES_VRC6_NUM_ENVELOPES <= NM_MAX_ENVELOPES &&
                   NES_VRC7_NUM_ENVELOPES <= NM_MAX_ENVELOPES && NES_S5B_NUM_ENVELOPES <= NM_MAX_ENVELOPES &&
                   NES_MMC5_NUM_ENVELOPES <= NM_MAX_ENVELOPES,
               "envelope slots");
_Static_assert(NES_NUM_PITCH_ENVELOPES <= NM_MAX_PITCH_ENVELOPES && NES_VRC6_NUM_PITCH_ENVELOPES <= NM_MAX_PITCH_ENVELOPES &&
                   NES_VRC7_NUM_PITCH_ENVELOPES <= NM_MAX_PITCH_ENVELOPES && NES_S5B_NUM_PITCH_ENVELOPES <= NM_MAX_PITCH_ENVELOPES &&
                   NES_MMC5_NUM_PITCH_ENVELOPES <= NM_MAX_PITCH_ENVELOPES,
               "pitch envelope slots");
_Static_assert(NES_VRC6_CHANNELS <= NM_MAX_CHANNELS && NES_VRC7_CHANNELS <= NM_MAX_CHANNELS && NES_S5B_CHANNELS <= NM_MAX_CHANNELS &&
                   NES_MMC5_CHANNELS <= NM_MAX_CHANNELS,
               "channel arrays");
_Static_assert(NES_S5B_CHANNELS == NM_FIRST_EXP + 3, "the 2A03's five channels, then 5B square 1-3");
_Static_assert(NES_MMC5_CHANNELS == NM_FIRST_EXP + 2, "the 2A03's five channels, then MMC5 pulse 1 and 2");
_Static_assert(NES_VRC7_EXP_INSTRUMENT_SIZE == NM_EXP_INSTRUMENT_SIZE && NES_S5B_EXP_INSTRUMENT_SIZE == NM_EXP_INSTRUMENT_SIZE,
               "FamiStudio's expansion instrument entries");
_Static_assert(NES_S5B_EFFECT_ENVELOPES == 4 * NES_IMAGE_MAX_INSTRUMENTS + 2 * NES_IMAGE_MAX_INSTRUMENTS &&
                   NES_S5B_EFFECT_ENVELOPES < NES_IMAGE_MAX_ENVELOPES,
               "5B directory: 4 envelopes per slot, then a mixer and a noise envelope per slot, then effect envelopes");
_Static_assert(NES_VRC6_PLAYER_INIT == NES_PLAYER_INIT && NES_VRC6_PLAYER_PLAY == NES_PLAYER_PLAY &&
                   NES_VRC7_PLAYER_INIT == NES_PLAYER_INIT && NES_VRC7_PLAYER_PLAY == NES_PLAYER_PLAY &&
                   NES_S5B_PLAYER_INIT == NES_PLAYER_INIT && NES_S5B_PLAYER_PLAY == NES_PLAYER_PLAY &&
                   NES_MMC5_PLAYER_INIT == NES_PLAYER_INIT && NES_MMC5_PLAYER_PLAY == NES_PLAYER_PLAY,
               "one entry table");

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
_Static_assert(NES_N163_8_CHANNELS <= NM_MAX_CHANNELS && NES_N163_8_NUM_ENVELOPES == NM_MAX_ENVELOPES &&
                   NES_N163_8_NUM_PITCH_ENVELOPES <= NM_MAX_PITCH_ENVELOPES,
               "the 8-channel N163 build's envelope slots");

/* Directory indices below a build's effect_envelopes belong to instrument slots: 4 per slot; N163 builds and the FDS
 * build then a wave-index envelope per slot (256..319); the 5B build a mixer and a noise envelope per slot (256..383). */
#define NM_EFFECT_ENVELOPES (4 * NES_IMAGE_MAX_INSTRUMENTS)
#define NM_WAVE_EFFECT_ENVELOPES (5 * NES_IMAGE_MAX_INSTRUMENTS)
_Static_assert(NM_WAVE_EFFECT_ENVELOPES < NES_IMAGE_MAX_ENVELOPES, "N163/FDS directory: room for effect envelopes");

/* The FDS build: the shared layout, engine channels 6 (FDS = channel 5), envelope slots 14 (3+3+2+3+3: the FDS
 * channel's volume, arpeggio and wave index from slot 11), pitch envelope slots 4 (FDS: 3). */
_Static_assert(NES_FDS_PLAYER_BASE + NES_FDS_PLAYER_SIZE <= NES_FDS_SONG_BASE, "FDS player below its song region");
_Static_assert(NES_FDS_SONG_BASE + NES_FDS_IMAGE_REGION_SIZE == NES_REGION_END, "FDS region ends at the vectors");
_Static_assert(NES_FDS_IMAGE_SIZE == NES_IMAGE_HEADER_SIZE + NES_IMAGE_DIRECTORY_SIZE + NES_FDS_IMAGE_REGION_SIZE,
               "FDS image = the shared header + directory + its region");
_Static_assert(NES_FDS_IMAGE_SIZE <= NM_IMAGE_MAX_SIZE, "image storage holds the FDS images");
_Static_assert(NES_FDS_RAM_BASE + NES_FDS_RAM_SIZE <= NES_AUDITION_RAM, "FDS engine RAM below the audition streams");
_Static_assert(NES_FDS_CHANNELS == 6 && NES_FDS_CHANNELS <= NM_MAX_CHANNELS && NM_FDS == NES_FDS_CHANNELS - 1, "FDS channels");
_Static_assert(NES_FDS_NUM_ENVELOPES == 14 && NES_FDS_NUM_ENVELOPES <= NM_MAX_ENVELOPES, "FDS envelope slots");
_Static_assert(NES_FDS_NUM_PITCH_ENVELOPES == 4 && NES_FDS_NUM_PITCH_ENVELOPES <= NM_MAX_PITCH_ENVELOPES, "FDS pitch envelope slots");
_Static_assert(NES_FDS_EXP_INSTRUMENT_SIZE == NM_EXP_INSTRUMENT_SIZE, "FDS expansion instrument entries");
_Static_assert(NES_FDS_PLAYER_INIT == NES_PLAYER_INIT && NES_FDS_PLAYER_PLAY == NES_PLAYER_PLAY, "one entry table");

/* The EPSM build: the shared layout, engine channels 20 (SSG 1-3 = 5..7, FM 1-6 = 8..13, rhythm 14..19), envelope slots
 * 35 (the 2A03's 11, then volume, arpeggio, mixer, noise per SSG from slot 11, volume and arpeggio per FM channel from
 * slot 23; none for the rhythm drums, whose famistudio_channel_env / _to_pitch_env entries do not exist), pitch envelope
 * slots 12 (SSG and FM). It sizes the channel and pitch envelope arrays. */
_Static_assert(NES_EPSM_PLAYER_BASE + NES_EPSM_PLAYER_SIZE <= NES_EPSM_SONG_BASE, "EPSM player below its song region");
_Static_assert(NES_EPSM_SONG_BASE + NES_EPSM_IMAGE_REGION_SIZE == NES_REGION_END, "EPSM region ends at the vectors");
_Static_assert(NES_EPSM_IMAGE_SIZE == NES_IMAGE_HEADER_SIZE + NES_IMAGE_DIRECTORY_SIZE + NES_EPSM_IMAGE_REGION_SIZE,
               "EPSM image = the shared header + directory + its region");
_Static_assert(NES_EPSM_IMAGE_SIZE <= NM_IMAGE_MAX_SIZE, "image storage holds the EPSM images");
_Static_assert(NES_EPSM_RAM_BASE + NES_EPSM_RAM_SIZE <= NES_AUDITION_RAM, "EPSM engine RAM below the audition streams");
_Static_assert(NES_EPSM_CHANNELS == NM_MAX_CHANNELS && NES_EPSM_CHANNELS == NM_EPSM_RHYTHM1 + 6 &&
                   NES_EPSM_ENVELOPE_CHANNELS == NM_EPSM_RHYTHM1,
               "the 2A03's five channels, SSG 1-3, FM 1-6 (with envelopes), then the six rhythm drums (without)");
_Static_assert(NES_EPSM_NUM_ENVELOPES == NM_MAX_ENVELOPES && NES_EPSM_NUM_PITCH_ENVELOPES == NM_MAX_PITCH_ENVELOPES,
               "EPSM envelope and pitch envelope slots");
_Static_assert(NES_EPSM_EXP_INSTRUMENT_SIZE == NM_EXP_INSTRUMENT_SIZE && NES_EPSM_PATCH_SIZE == NM_EPSM_PATCH_SIZE,
               "FamiStudio's expansion instrument entries and EPSM patch blocks");
_Static_assert(NES_EPSM_KIND_MIXER == NES_S5B_KIND_MIXER && NES_EPSM_KIND_NOISE == NES_S5B_KIND_NOISE &&
                   NES_EPSM_EFFECT_ENVELOPES == NES_S5B_EFFECT_ENVELOPES,
               "EPSM directory: the 5B's mixer and noise kinds and slots, effect envelopes from 384");
_Static_assert(NES_EPSM_PLAYER_INIT == NES_PLAYER_INIT && NES_EPSM_PLAYER_PLAY == NES_PLAYER_PLAY, "one entry table");

#define SYMBOLS(P)                                                                                             \
    {                                                                                                          \
        P##SONG_SPEED, P##TEMPO_STEP_LO, P##TEMPO_STEP_HI, P##TEMPO_ACC_LO, P##TEMPO_ACC_HI, P##INSTRUMENT_LO, \
            P##INSTRUMENT_HI, P##DPCM_LIST_LO, P##DPCM_LIST_HI, P##CHN_PTR_LO, P##CHN_PTR_HI, P##CHN_NOTE,     \
            P##CHN_REPEAT, P##CHN_RETURN_LO, P##CHN_RETURN_HI, P##CHN_REF_LEN, P##CHN_NOTE_DELAY,               \
            P##CHN_CUT_DELAY, P##ENV_VALUE, P##ENV_REPEAT, P##ENV_ADDR_LO, P##ENV_ADDR_HI, P##ENV_PTR,          \
            P##PITCH_ENV_REPEAT, P##PITCH_ENV_ADDR_LO, P##PITCH_ENV_ADDR_HI, P##PITCH_ENV_PTR,                  \
            P##DUMMY_ENVELOPE, P##DUMMY_PITCH_ENVELOPE, P##CHANNEL_ENV, P##R0, P##R1, P##SET_INSTRUMENT,          \
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
    .effect_envelopes = NM_EFFECT_ENVELOPES,
    .channels = 5,
    .envelope_channels = 5,
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
    .effect_envelopes = NM_EFFECT_ENVELOPES,
    .channels = NES_VRC6_CHANNELS,
    .envelope_channels = NES_VRC6_CHANNELS,
    .envelopes = NES_VRC6_NUM_ENVELOPES,
    .pitch_envelopes = NES_VRC6_NUM_PITCH_ENVELOPES,
    .duty_max = 7, /* famistudio_vrc6_duty_lookup: $00..$70 */
    .expansion = NES_EXPANSION_VRC6,
    .sym = SYMBOLS(NES_VRC6_FAMISTUDIO_),
};

const NesPlayer nes_player_vrc7 = {
    .id = NES_VRC7_PLAYER_ID,
    .code = nes_vrc7_player_code,
    .base = NES_VRC7_PLAYER_BASE,
    .size = NES_VRC7_PLAYER_SIZE,
    .init = NES_VRC7_PLAYER_INIT,
    .play = NES_VRC7_PLAYER_PLAY,
    .idle = NES_VRC7_PLAYER_IDLE,
    .trap = NES_VRC7_PLAYER_TRAP,
    .song_base = NES_VRC7_SONG_BASE,
    .region_size = NES_VRC7_IMAGE_REGION_SIZE,
    .image_size = NES_VRC7_IMAGE_SIZE,
    .ram_end = NES_VRC7_RAM_BASE + NES_VRC7_RAM_SIZE,
    .effect_envelopes = NM_EFFECT_ENVELOPES,
    .channels = NES_VRC7_CHANNELS,
    .envelope_channels = NES_VRC7_CHANNELS,
    .envelopes = NES_VRC7_NUM_ENVELOPES,
    .pitch_envelopes = NES_VRC7_NUM_PITCH_ENVELOPES,
    .duty_max = 3, /* duty only on the 2A03 pulses and noise */
    .expansion = NES_EXPANSION_VRC7,
    .exp_instruments = 1,
    .sym = SYMBOLS(NES_VRC7_FAMISTUDIO_),
    .vrc7 = {NES_VRC7_FAMISTUDIO_EXP_INSTRUMENT_LO, NES_VRC7_FAMISTUDIO_EXP_INSTRUMENT_HI, NES_VRC7_FAMISTUDIO_CHN_VRC7_PATCH,
             NES_VRC7_FAMISTUDIO_CHN_VRC7_SUSTAIN, NES_VRC7_FAMISTUDIO_CHN_VRC7_PREV_HI, NES_VRC7_FAMISTUDIO_CHN_VRC7_TRIGGER},
};

#define N163_PLAYER(N)                                                                                                    \
    {                                                                                                                     \
        .id = NES_N163_##N##_PLAYER_ID, .code = nes_n163_##N##_player_code, .base = NES_N163_##N##_PLAYER_BASE,           \
        .size = NES_N163_##N##_PLAYER_SIZE, .init = NES_N163_##N##_PLAYER_INIT, .play = NES_N163_##N##_PLAYER_PLAY,       \
        .idle = NES_N163_##N##_PLAYER_IDLE, .trap = NES_N163_##N##_PLAYER_TRAP, .song_base = NES_N163_##N##_SONG_BASE,    \
        .region_size = NES_N163_##N##_IMAGE_REGION_SIZE, .image_size = NES_N163_##N##_IMAGE_SIZE,                         \
        .ram_end = NES_N163_##N##_RAM_BASE + NES_N163_##N##_RAM_SIZE, .effect_envelopes = NM_WAVE_EFFECT_ENVELOPES,       \
        .channels = NES_N163_##N##_CHANNELS, .envelope_channels = NES_N163_##N##_CHANNELS,                               \
        .envelopes = NES_N163_##N##_NUM_ENVELOPES,                                                                        \
        .pitch_envelopes = NES_N163_##N##_NUM_PITCH_ENVELOPES,                                                            \
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

const NesPlayer nes_player_fds = {
    .id = NES_FDS_PLAYER_ID,
    .code = nes_fds_player_code,
    .base = NES_FDS_PLAYER_BASE,
    .size = NES_FDS_PLAYER_SIZE,
    .init = NES_FDS_PLAYER_INIT,
    .play = NES_FDS_PLAYER_PLAY,
    .idle = NES_FDS_PLAYER_IDLE,
    .trap = NES_FDS_PLAYER_TRAP,
    .song_base = NES_FDS_SONG_BASE,
    .region_size = NES_FDS_IMAGE_REGION_SIZE,
    .image_size = NES_FDS_IMAGE_SIZE,
    .ram_end = NES_FDS_RAM_BASE + NES_FDS_RAM_SIZE,
    .effect_envelopes = NM_WAVE_EFFECT_ENVELOPES,
    .channels = NES_FDS_CHANNELS,
    .envelope_channels = NES_FDS_CHANNELS,
    .envelopes = NES_FDS_NUM_ENVELOPES,
    .pitch_envelopes = NES_FDS_NUM_PITCH_ENVELOPES,
    .duty_max = 3, /* duty only on the 2A03 pulses and noise */
    .expansion = NES_EXPANSION_FDS,
    .exp_instruments = 1,
    .sym = SYMBOLS(NES_FDS_FAMISTUDIO_),
    .fds = {NES_FDS_FAMISTUDIO_EXP_INSTRUMENT_LO, NES_FDS_FAMISTUDIO_EXP_INSTRUMENT_HI, NES_FDS_FAMISTUDIO_CHN_FDS_INSTRUMENT,
            NES_FDS_FAMISTUDIO_FDS_MOD_ENVELOPE, NES_FDS_FAMISTUDIO_FDS_MOD_SPEED, NES_FDS_FAMISTUDIO_FDS_MOD_DEPTH,
            NES_FDS_FAMISTUDIO_FDS_MOD_DELAY, NES_FDS_FAMISTUDIO_FDS_MOD_DELAY_COUNTER, NES_FDS_FAMISTUDIO_FDS_OVERRIDE_FLAGS,
            NES_FDS_FAMISTUDIO_FDS_WAVE_INDEX, NES_FDS_FAMISTUDIO_FDS_AUTOMOD_NUMER, NES_FDS_FAMISTUDIO_FDS_AUTOMOD_DENOM,
            NES_FDS_FAMISTUDIO_FDS_PREV_HI},
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
    .envelope_channels = NES_S5B_CHANNELS,
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
    .effect_envelopes = NM_EFFECT_ENVELOPES,
    .channels = NES_MMC5_CHANNELS,
    .envelope_channels = NES_MMC5_CHANNELS,
    .envelopes = NES_MMC5_NUM_ENVELOPES,
    .pitch_envelopes = NES_MMC5_NUM_PITCH_ENVELOPES,
    .duty_max = 3, /* the MMC5 pulses use the 2A03 duty lookup */
    .expansion = NES_EXPANSION_MMC5,
    .sym = SYMBOLS(NES_MMC5_FAMISTUDIO_),
};

const NesPlayer nes_player_epsm = {
    .id = NES_EPSM_PLAYER_ID,
    .code = nes_epsm_player_code,
    .base = NES_EPSM_PLAYER_BASE,
    .size = NES_EPSM_PLAYER_SIZE,
    .init = NES_EPSM_PLAYER_INIT,
    .play = NES_EPSM_PLAYER_PLAY,
    .idle = NES_EPSM_PLAYER_IDLE,
    .trap = NES_EPSM_PLAYER_TRAP,
    .song_base = NES_EPSM_SONG_BASE,
    .region_size = NES_EPSM_IMAGE_REGION_SIZE,
    .image_size = NES_EPSM_IMAGE_SIZE,
    .ram_end = NES_EPSM_RAM_BASE + NES_EPSM_RAM_SIZE,
    .effect_envelopes = NES_EPSM_EFFECT_ENVELOPES,
    .channels = NES_EPSM_CHANNELS,
    .envelope_channels = NES_EPSM_ENVELOPE_CHANNELS,
    .envelopes = NES_EPSM_NUM_ENVELOPES,
    .pitch_envelopes = NES_EPSM_NUM_PITCH_ENVELOPES,
    .duty_max = 3, /* duty only on the 2A03 pulses and noise */
    .expansion = NES_EXPANSION_EPSM,
    .exp_instruments = 1,
    .sym = SYMBOLS(NES_EPSM_FAMISTUDIO_),
    .epsm = {.ssg = {NES_EPSM_FAMISTUDIO_EXP_INSTRUMENT_LO, NES_EPSM_FAMISTUDIO_EXP_INSTRUMENT_HI,
                     NES_EPSM_FAMISTUDIO_EPSM_ENV_PERIOD_LO, NES_EPSM_FAMISTUDIO_EPSM_ENV_PERIOD_HI,
                     NES_EPSM_FAMISTUDIO_EPSM_ENV_OVERRIDE, NES_EPSM_FAMISTUDIO_EPSM_CHN_ENV_SHAPE,
                     NES_EPSM_FAMISTUDIO_EPSM_CHN_ENV_OCTAVE},
             .chn_fm_instrument = NES_EPSM_FAMISTUDIO_CHN_EPSM_FM_INSTRUMENT,
             .chn_rhythm_volume = NES_EPSM_FAMISTUDIO_CHN_EPSM_RHYTHM_VOLUME,
             .chn_rhythm_stereo = NES_EPSM_FAMISTUDIO_CHN_EPSM_RHYTHM_STEREO,
             .ptr1 = NES_EPSM_FAMISTUDIO_PTR1,
             .r3 = NES_EPSM_FAMISTUDIO_R3},
};
