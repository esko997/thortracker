/* "nes-famistudio": the FamiStudio sound engine (FamiTracker tempo) on the NES machine
 * (nes_machine.h) behind the chip engine interface. Images are validated and indexed by
 * nm_load_image (nes_image.h); see docs/AUDIO_ENGINE.md for the layout the Kotlin lowering
 * produces and the ops' behaviour. */
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

static ChipMachine* nes_create(uint32_t sample_rate) { return (ChipMachine*)nm_create(sample_rate); }

static void nes_destroy(ChipMachine* m) { nm_destroy(MACHINE(m)); }

static int nes_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    NesImage* image = (NesImage*)storage;
    if (size != sizeof image->bytes) return CHIP_ERR_IMAGE;
    memcpy(image->bytes, bytes, size);
    return nm_load_image(image) ? CHIP_ERR_IMAGE : CHIP_OK;
}

static int nes_start(ChipMachine* m, const ChipImage* image, int order, int row, int max_ticks, int* seek_mode) {
    return nm_start(MACHINE(m), IMAGE(image), order, row, max_ticks, seek_mode);
}

static void nes_apply_image(ChipMachine* m, const ChipImage* image) { nm_apply_image(MACHINE(m), IMAGE(image)); }

static int nes_audition(ChipMachine* m, const ChipImage* image, int channel, int note, int instrument, int fine) {
    return nm_audition(MACHINE(m), IMAGE(image), channel, note, instrument, fine);
}

static int nes_release(ChipMachine* m) { return nm_release(MACHINE(m)); }

static int nes_mute(ChipMachine* m, int channel, int muted) { return nm_mute_channel(MACHINE(m), channel, muted); }

static void nes_position(const ChipMachine* m, int* order, int* row, int* tick) {
    nm_position((const NesMachine*)m, order, row, tick);
}

static int nes_tick(ChipMachine* m, float* out) { return nm_tick(MACHINE(m), out); }

const ChipEngineOps nes_chip_ops = {
    .system_id = "nes-famistudio",
    .image_size = sizeof(((NesImage*)0)->bytes),
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
