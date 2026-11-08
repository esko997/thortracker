/* "c64-sid": the GoatTracker 2 playroutine on the C64 music machine (sid_machine.h: 6510 + reSID)
 * behind the chip engine interface. Images are validated by sm_load_image (sid_image.h); see
 * docs/AUDIO_ENGINE.md (SID) for the layout the lowering produces and the ops' behaviour. */
#include <string.h>

#include "chip_engine.h"
#include "sid_machine.h"

_Static_assert(CHIP_OK == SM_OK && CHIP_ERR_IMAGE == SM_ERR_IMAGE && CHIP_ERR_FAULT == SM_ERR_FAULT,
               "error codes pass through unchanged");
_Static_assert(CHIP_SEEK_FROM_START == SM_SEEK_FROM_START && CHIP_SEEK_FROM_ORDER == SM_SEEK_FROM_ORDER,
               "seek modes pass through unchanged");
_Static_assert(SM_MAX_TICK_FRAMES <= CHIP_MAX_TICK_FRAMES && SM_MAX_SAMPLE_RATE == CHIP_MAX_SAMPLE_RATE,
               "host buffers fit a tick");

#define MACHINE(m) ((SidMachine*)(m))
#define IMAGE(i) ((const SidImage*)(i))

static ChipMachine* sid_create(uint32_t sample_rate) { return (ChipMachine*)sm_create(sample_rate); }

static void sid_destroy(ChipMachine* m) { sm_destroy(MACHINE(m)); }

static int sid_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    SidImage* image = (SidImage*)storage;
    if (size != sizeof image->bytes) return CHIP_ERR_IMAGE;
    memcpy(image->bytes, bytes, size);
    return sm_load_image(image) ? CHIP_ERR_IMAGE : CHIP_OK;
}

static int sid_start(ChipMachine* m, const ChipImage* image, int order, int row, int max_ticks, int* seek_mode) {
    return sm_start(MACHINE(m), IMAGE(image), order, row, max_ticks, seek_mode);
}

static void sid_apply_image(ChipMachine* m, const ChipImage* image) { sm_apply_image(MACHINE(m), IMAGE(image)); }

/* Hosts pass the cell's instrument slot (1-based); the image's slot map gives the player's number
 * (the lowering orders instruments by hard-restart kind). `note` is GoatTracker's note = the absolute
 * note (0 = C-0, A-4 = 57); `fine` must be 0. */
static int sid_audition(ChipMachine* m, const ChipImage* image, int channel, int note, int instrument, int fine) {
    const SidImage* im = IMAGE(image);
    if (instrument < 1 || instrument > 63 || im->info.loaded != SM_LOADED) return CHIP_ERR_IMAGE;
    const int number = im->bytes[SM_SLOT_MAP + instrument];
    if (!number) return CHIP_ERR_IMAGE;
    return sm_audition(MACHINE(m), im, channel, note, number, fine);
}

static int sid_release(ChipMachine* m) { return sm_release(MACHINE(m)); }

static int sid_mute(ChipMachine* m, int channel, int muted) { return sm_mute_channel(MACHINE(m), channel, muted); }

static void sid_position(const ChipMachine* m, int* order, int* row, int* tick) {
    sm_position((const SidMachine*)m, order, row, tick);
}

static int sid_tick(ChipMachine* m, float* out) { return sm_tick(MACHINE(m), out); }

const ChipEngineOps sid_chip_ops = {
    .system_id = "c64-sid",
    .image_size = sizeof(((SidImage*)0)->bytes),
    .image_storage = sizeof(SidImage),
    .create = sid_create,
    .destroy = sid_destroy,
    .load_image = sid_load_image,
    .start = sid_start,
    .apply_image = sid_apply_image,
    .audition = sid_audition,
    .release = sid_release,
    .mute = sid_mute,
    .position = sid_position,
    .tick = sid_tick,
};
