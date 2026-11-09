/* "ym2203-opn" and "ym2608-opna": Thor Tracker's own OPN player on ymfm's YM2203 / YM2608 behind the chip engine
 * interface, one ops table per chip over the same machine (opn_machine.h). Images are validated by opn_load_image
 * (opn_image.h): a YM2203 image is OPN_IMAGE_SIZE bytes, a YM2608 image OPNA_IMAGE_SIZE (the song part plus the
 * ADPCM-B memory), and the header's chip must be the engine's. docs/AUDIO_ENGINE.md ("OPN and OPNA") has the layout
 * the lowering produces and the ops' behaviour. */
#include <string.h>

#include "chip_engine.h"
#include "opn_machine.h"

_Static_assert(CHIP_OK == OPNM_OK && CHIP_ERR_IMAGE == OPNM_ERR_IMAGE && CHIP_ERR_FAULT == OPNM_ERR_FAULT,
               "error codes pass through unchanged");
_Static_assert(CHIP_SEEK_FROM_START == OPNM_SEEK_FROM_START && CHIP_SEEK_FROM_ORDER == OPNM_SEEK_FROM_ORDER,
               "seek modes pass through unchanged");
_Static_assert(OPNM_MAX_TICK_FRAMES <= CHIP_MAX_TICK_FRAMES && OPNM_MAX_SAMPLE_RATE == CHIP_MAX_SAMPLE_RATE,
               "host buffers fit a tick");
_Static_assert(OPNA_IMAGE_SIZE == sizeof(((OpnImage*)0)->bytes), "a YM2608 image fills the image bytes");

#define MACHINE(m) ((OpnMachine*)(m))
#define IMAGE(i) ((const OpnImage*)(i))

static int load(ChipImage* storage, const uint8_t* bytes, size_t size, int chip) {
    OpnImage* image = (OpnImage*)storage;
    if (size != opn_image_size(chip)) return CHIP_ERR_IMAGE;
    memcpy(image->bytes, bytes, size);
    return opn_load_image(image, chip) ? CHIP_ERR_IMAGE : CHIP_OK;
}

static ChipMachine* opn_create(uint32_t sample_rate) { return (ChipMachine*)opnm_create(OPN_CHIP_YM2203, sample_rate); }

static ChipMachine* opna_create(uint32_t sample_rate) { return (ChipMachine*)opnm_create(OPN_CHIP_YM2608, sample_rate); }

static void opn_destroy(ChipMachine* m) { opnm_destroy(MACHINE(m)); }

static int opn_load(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load(storage, bytes, size, OPN_CHIP_YM2203);
}

static int opna_load(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load(storage, bytes, size, OPN_CHIP_YM2608);
}

static int opn_start(ChipMachine* m, const ChipImage* image, int order, int row, int max_ticks, int* seek_mode) {
    return opnm_start(MACHINE(m), IMAGE(image), order, row, max_ticks, seek_mode);
}

static void opn_apply_image(ChipMachine* m, const ChipImage* image) { opnm_apply_image(MACHINE(m), IMAGE(image)); }

/* `note` = the absolute note (0 = C-0, A-4 = 57), `instrument` = the cell's slot (1..64, of the channel's kind),
 * `fine` = 1/64 semitones (-128..127). */
static int opn_audition(ChipMachine* m, const ChipImage* image, int channel, int note, int instrument, int fine) {
    return opnm_audition(MACHINE(m), IMAGE(image), channel, note, instrument, fine);
}

static int opn_release(ChipMachine* m) { return opnm_release(MACHINE(m)); }

static int opn_mute(ChipMachine* m, int channel, int muted) { return opnm_mute(MACHINE(m), channel, muted); }

static void opn_position(const ChipMachine* m, int* order, int* row, int* tick) {
    opnm_position((const OpnMachine*)m, order, row, tick);
}

static int opn_tick(ChipMachine* m, float* out) { return opnm_tick(MACHINE(m), out); }

const ChipEngineOps opn_chip_ops = {
    .system_id = "ym2203-opn",
    .image_size = OPN_IMAGE_SIZE,
    .image_storage = sizeof(OpnImage),
    .create = opn_create,
    .destroy = opn_destroy,
    .load_image = opn_load,
    .start = opn_start,
    .apply_image = opn_apply_image,
    .audition = opn_audition,
    .release = opn_release,
    .mute = opn_mute,
    .position = opn_position,
    .tick = opn_tick,
};

const ChipEngineOps opna_chip_ops = {
    .system_id = "ym2608-opna",
    .image_size = OPNA_IMAGE_SIZE,
    .image_storage = sizeof(OpnImage),
    .create = opna_create,
    .destroy = opn_destroy,
    .load_image = opna_load,
    .start = opn_start,
    .apply_image = opn_apply_image,
    .audition = opn_audition,
    .release = opn_release,
    .mute = opn_mute,
    .position = opn_position,
    .tick = opn_tick,
};
