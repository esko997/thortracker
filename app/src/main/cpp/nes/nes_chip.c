/* "nes-famistudio", "nes-famistudio-vrc6", "nes-famistudio-vrc7", "nes-famistudio-n163-1".."-8", "nes-famistudio-fds",
 * "nes-famistudio-s5b", "nes-famistudio-mmc5" and "nes-famistudio-epsm": the FamiStudio sound engine (FamiTracker
 * tempo), the 2A03 build, the Konami VRC6 build, the Konami VRC7 build, the eight Namco 163 builds (1..8 N163 channels),
 * the Famicom Disk System build, the Sunsoft 5B build, the Nintendo MMC5 build and the EPSM build, on the NES machine
 * (nes_machine.h) behind the chip engine interface.
 * Images are validated and indexed by nm_load_image (nes_image.h) for the engine's player; see
 * docs/AUDIO_ENGINE.md for the layout the Kotlin lowering produces and the ops' behaviour. */
#include <string.h>

#include "chip_engine.h"
#include "nes_machine.h"
#include "nes_mmc5_pcm.h"

_Static_assert(CHIP_OK == NM_OK && CHIP_ERR_IMAGE == NM_ERR_IMAGE && CHIP_ERR_FAULT == NM_ERR_FAULT,
               "error codes pass through unchanged");
_Static_assert(CHIP_SEEK_FROM_START == NM_SEEK_FROM_START && CHIP_SEEK_FROM_ORDER == NM_SEEK_FROM_ORDER,
               "seek modes pass through unchanged");
_Static_assert(NM_MAX_TICK_FRAMES <= CHIP_MAX_TICK_FRAMES && NM_MAX_SAMPLE_RATE == CHIP_MAX_SAMPLE_RATE,
               "host buffers fit a tick");

#define MACHINE(m) ((NesMachine*)(m))
#define IMAGE(i) ((const NesImage*)(i))

static ChipMachine* nes_create(uint32_t sample_rate) { return (ChipMachine*)nm_create(sample_rate, &nes_player_2a03); }
static ChipMachine* vrc6_create(uint32_t sample_rate) { return (ChipMachine*)nm_create(sample_rate, &nes_player_vrc6); }
static ChipMachine* vrc7_create(uint32_t sample_rate) { return (ChipMachine*)nm_create(sample_rate, &nes_player_vrc7); }
static ChipMachine* s5b_create(uint32_t sample_rate) { return (ChipMachine*)nm_create(sample_rate, &nes_player_s5b); }
static ChipMachine* mmc5_create(uint32_t sample_rate) { return (ChipMachine*)nm_create(sample_rate, &nes_player_mmc5); }

static void nes_destroy(ChipMachine* m) { nm_destroy(MACHINE(m)); }

/* Copies a lowered image of exactly the player's image size (storage beyond it zeroed) and validates it. */
static int load_for(const NesPlayer* player, ChipImage* storage, const uint8_t* bytes, size_t size) {
    NesImage* image = (NesImage*)storage;
    if (size != player->image_size) return CHIP_ERR_IMAGE;
    memcpy(image->bytes, bytes, size);
    memset(image->bytes + size, 0, sizeof image->bytes - size);
    return nm_load_image(image, player) ? CHIP_ERR_IMAGE : CHIP_OK;
}

static int nes_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load_for(&nes_player_2a03, storage, bytes, size);
}

static int vrc6_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load_for(&nes_player_vrc6, storage, bytes, size);
}

static int vrc7_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load_for(&nes_player_vrc7, storage, bytes, size);
}

static int s5b_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load_for(&nes_player_s5b, storage, bytes, size);
}

/* The MMC5 engine's image: the seven-channel FamiStudio image (NES_MMC5_IMAGE_SIZE bytes, unchanged) followed by the PCM
 * lane's extension (NES_PCM_EXT_SIZE bytes, nes_mmc5_pcm.h), loaded into a NesMmc5Image. */
#define MMC5_IMAGE_SIZE (NES_MMC5_IMAGE_SIZE + NES_PCM_EXT_SIZE)

static int mmc5_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    NesMmc5Image* image = (NesMmc5Image*)storage;
    if (size != MMC5_IMAGE_SIZE) return CHIP_ERR_IMAGE;
    if (load_for(&nes_player_mmc5, (ChipImage*)&image->base, bytes, NES_MMC5_IMAGE_SIZE) != CHIP_OK) return CHIP_ERR_IMAGE;
    memcpy(image->pcm.bytes, bytes + NES_MMC5_IMAGE_SIZE, NES_PCM_EXT_SIZE);
    return nes_pcm_load(&image->pcm, &image->base.info) ? CHIP_ERR_IMAGE : CHIP_OK;
}

#define MMC5_IMAGE(i) ((const NesMmc5Image*)(i))

static int mmc5_start(ChipMachine* m, const ChipImage* image, int order, int row, int max_ticks, int* seek_mode) {
    return nm_start_pcm(MACHINE(m), &MMC5_IMAGE(image)->base, &MMC5_IMAGE(image)->pcm, order, row, max_ticks, seek_mode);
}

static void mmc5_apply_image(ChipMachine* m, const ChipImage* image) {
    nm_apply_image_pcm(MACHINE(m), &MMC5_IMAGE(image)->base, &MMC5_IMAGE(image)->pcm);
}

/* As nes_audition; on the PCM lane (NM_MMC5_PCM) `note` is the PCM mapping (1-based) and the slot only has to be real. */
static int mmc5_audition(ChipMachine* m, const ChipImage* image, int channel, int note, int instrument, int fine) {
    if (instrument < 1) return CHIP_ERR_IMAGE;
    return nm_audition_pcm(MACHINE(m), &MMC5_IMAGE(image)->base, &MMC5_IMAGE(image)->pcm, channel, note, instrument - 1, fine);
}

static int nes_start(ChipMachine* m, const ChipImage* image, int order, int row, int max_ticks, int* seek_mode) {
    return nm_start(MACHINE(m), IMAGE(image), order, row, max_ticks, seek_mode);
}

static void nes_apply_image(ChipMachine* m, const ChipImage* image) { nm_apply_image(MACHINE(m), IMAGE(image)); }

/* Hosts pass the cell's instrument slot (1-based, as for the Game Boy); the lowering puts slot s at
 * instrument table index s - 1. Slot 0 (no instrument) has no sound. On the DPCM channel `note` is the
 * mapping's DPCM list entry and the slot only has to be a real one. */
static int nes_audition(ChipMachine* m, const ChipImage* image, int channel, int note, int instrument, int fine) {
    if (instrument < 1) return CHIP_ERR_IMAGE;
    return nm_audition(MACHINE(m), IMAGE(image), channel, note, instrument - 1, fine);
}

static int nes_release(ChipMachine* m) { return nm_release(MACHINE(m)); }

static int nes_mute(ChipMachine* m, int channel, int muted) { return nm_mute_channel(MACHINE(m), channel, muted); }

static void nes_position(const ChipMachine* m, int* order, int* row, int* tick) {
    nm_position((const NesMachine*)m, order, row, tick);
}

static int nes_tick(ChipMachine* m, float* out) { return nm_tick(MACHINE(m), out); }

const ChipEngineOps nes_chip_ops = {
    .system_id = "nes-famistudio",
    .image_size = NES_IMAGE_SIZE,
    .image_storage = sizeof(NesImage),
    .create = nes_create,
    .destroy = nes_destroy,
    .load_image = nes_load_image,
    .start = nes_start,
    .apply_image = nes_apply_image,
    .audition = nes_audition,
    .release = nes_release,
    .mute = nes_mute,
    .position = nes_position,
    .tick = nes_tick,
};

/* Channels 0-4 as "nes-famistudio", then VRC6 pulse 1 (5), pulse 2 (6) and saw (7). */
const ChipEngineOps nes_vrc6_chip_ops = {
    .system_id = "nes-famistudio-vrc6",
    .image_size = NES_VRC6_IMAGE_SIZE,
    .image_storage = sizeof(NesImage),
    .create = vrc6_create,
    .destroy = nes_destroy,
    .load_image = vrc6_load_image,
    .start = nes_start,
    .apply_image = nes_apply_image,
    .audition = nes_audition,
    .release = nes_release,
    .mute = nes_mute,
    .position = nes_position,
    .tick = nes_tick,
};

/* Channels 0-4 as "nes-famistudio", then VRC7 FM 1-6 (5..10). On an FM channel the instrument slot addresses the
 * image's expansion instrument table (the same index as the 2A03 table). */
const ChipEngineOps nes_vrc7_chip_ops = {
    .system_id = "nes-famistudio-vrc7",
    .image_size = NES_VRC7_IMAGE_SIZE,
    .image_storage = sizeof(NesImage),
    .create = vrc7_create,
    .destroy = nes_destroy,
    .load_image = vrc7_load_image,
    .start = nes_start,
    .apply_image = nes_apply_image,
    .audition = nes_audition,
    .release = nes_release,
    .mute = nes_mute,
    .position = nes_position,
    .tick = nes_tick,
};

/* Channels 0-4 as "nes-famistudio", then 5B square 1 (5), 2 (6) and 3 (7). On a 5B square the instrument slot
 * addresses the image's expansion instrument table (the same index as the 2A03 table). */
const ChipEngineOps nes_s5b_chip_ops = {
    .system_id = "nes-famistudio-s5b",
    .image_size = NES_S5B_IMAGE_SIZE,
    .image_storage = sizeof(NesImage),
    .create = s5b_create,
    .destroy = nes_destroy,
    .load_image = s5b_load_image,
    .start = nes_start,
    .apply_image = nes_apply_image,
    .audition = nes_audition,
    .release = nes_release,
    .mute = nes_mute,
    .position = nes_position,
    .tick = nes_tick,
};

/* "nes-famistudio-n163-<n>", n = 1..8: channels 0-4 as "nes-famistudio", then N163 1..n (5..4+n). On an N163 channel
 * the instrument slot addresses the image's expansion instrument table (the same index as the 2A03 table). */
#define N163_OPS(N)                                                                                                    \
    static ChipMachine* n163_##N##_create(uint32_t sample_rate) {                                                      \
        return (ChipMachine*)nm_create(sample_rate, &nes_player_n163[(N) - 1]);                                        \
    }                                                                                                                  \
    static int n163_##N##_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {                          \
        return load_for(&nes_player_n163[(N) - 1], storage, bytes, size);                                              \
    }                                                                                                                  \
    const ChipEngineOps nes_n163_##N##_chip_ops = {                                                                    \
        .system_id = "nes-famistudio-n163-" #N,                                                                        \
        .image_size = NES_N163_##N##_IMAGE_SIZE,                                                                       \
        .image_storage = sizeof(NesImage),                                                                             \
        .create = n163_##N##_create,                                                                                   \
        .destroy = nes_destroy,                                                                                        \
        .load_image = n163_##N##_load_image,                                                                           \
        .start = nes_start,                                                                                            \
        .apply_image = nes_apply_image,                                                                                \
        .audition = nes_audition,                                                                                      \
        .release = nes_release,                                                                                        \
        .mute = nes_mute,                                                                                              \
        .position = nes_position,                                                                                      \
        .tick = nes_tick,                                                                                              \
    }

N163_OPS(1);
N163_OPS(2);
N163_OPS(3);
N163_OPS(4);
N163_OPS(5);
N163_OPS(6);
N163_OPS(7);
N163_OPS(8);

/* "nes-famistudio-fds": channels 0-4 as "nes-famistudio", then the FDS channel (5). On the FDS channel the instrument
 * slot addresses the image's expansion instrument table (the same index as the 2A03 table). */
static ChipMachine* fds_create(uint32_t sample_rate) { return (ChipMachine*)nm_create(sample_rate, &nes_player_fds); }

static int fds_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load_for(&nes_player_fds, storage, bytes, size);
}

const ChipEngineOps nes_fds_chip_ops = {
    .system_id = "nes-famistudio-fds",
    .image_size = NES_FDS_IMAGE_SIZE,
    .image_storage = sizeof(NesImage),
    .create = fds_create,
    .destroy = nes_destroy,
    .load_image = fds_load_image,
    .start = nes_start,
    .apply_image = nes_apply_image,
    .audition = nes_audition,
    .release = nes_release,
    .mute = nes_mute,
    .position = nes_position,
    .tick = nes_tick,
};

/* Channels 0-4 as "nes-famistudio", then MMC5 pulse 1 (5) and pulse 2 (6), and the software PCM lane (7). */
const ChipEngineOps nes_mmc5_chip_ops = {
    .system_id = "nes-famistudio-mmc5",
    .image_size = MMC5_IMAGE_SIZE,
    .image_storage = sizeof(NesMmc5Image),
    .create = mmc5_create,
    .destroy = nes_destroy,
    .load_image = mmc5_load_image,
    .start = mmc5_start,
    .apply_image = mmc5_apply_image,
    .audition = mmc5_audition,
    .release = nes_release,
    .mute = nes_mute,
    .position = nes_position,
    .tick = nes_tick,
};

/* "nes-famistudio-epsm": channels 0-4 as "nes-famistudio", then EPSM SSG 1-3 (5..7), FM 1-6 (8..13) and the rhythm drums
 * bass drum, snare, top cymbal, hi-hat, tom, rim shot (14..19). On an EPSM channel the instrument slot addresses the
 * image's expansion instrument table (the same index as the 2A03 table). The rhythm drums sound only with the rhythm ROM
 * installed (nes_epsm.h); the frontend refuses to play or audition them without it. Output is true stereo. */
static ChipMachine* epsm_create(uint32_t sample_rate) { return (ChipMachine*)nm_create(sample_rate, &nes_player_epsm); }

static int epsm_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load_for(&nes_player_epsm, storage, bytes, size);
}

const ChipEngineOps nes_epsm_chip_ops = {
    .system_id = "nes-famistudio-epsm",
    .image_size = NES_EPSM_IMAGE_SIZE,
    .image_storage = sizeof(NesImage),
    .create = epsm_create,
    .destroy = nes_destroy,
    .load_image = epsm_load_image,
    .start = nes_start,
    .apply_image = nes_apply_image,
    .audition = nes_audition,
    .release = nes_release,
    .mute = nes_mute,
    .position = nes_position,
    .tick = nes_tick,
};
