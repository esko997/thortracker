/* "nes-famistudio", "nes-famistudio-vrc6" and "nes-famistudio-mmc5": the FamiStudio sound engine (FamiTracker
 * tempo), the 2A03 build, the Konami VRC6 build and the Nintendo MMC5 build, on the NES machine (nes_machine.h)
 * behind the chip engine interface.
 * Images are validated and indexed by nm_load_image (nes_image.h) for the engine's player; see
 * docs/AUDIO_ENGINE.md for the layout the Kotlin lowering produces and the ops' behaviour. */
#include <string.h>

#include "chip_engine.h"
#include "nes_machine.h"

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

static int mmc5_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load_for(&nes_player_mmc5, storage, bytes, size);
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

/* Channels 0-4 as "nes-famistudio", then MMC5 pulse 1 (5) and pulse 2 (6). */
const ChipEngineOps nes_mmc5_chip_ops = {
    .system_id = "nes-famistudio-mmc5",
    .image_size = NES_MMC5_IMAGE_SIZE,
    .image_storage = sizeof(NesImage),
    .create = mmc5_create,
    .destroy = nes_destroy,
    .load_image = mmc5_load_image,
    .start = nes_start,
    .apply_image = nes_apply_image,
    .audition = nes_audition,
    .release = nes_release,
    .mute = nes_mute,
    .position = nes_position,
    .tick = nes_tick,
};
