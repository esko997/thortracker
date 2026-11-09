/* "ym2151-opm": Thor Tracker's own OPM player on a YM2151 (ymfm, the X68000's 4 MHz) behind the chip engine
 * interface. Images are validated by om_load_image (opm_image.h); docs/AUDIO_ENGINE.md ("OPM") has the layout the
 * lowering produces and the ops' behaviour. */
#include <string.h>

#include "chip_engine.h"
#include "opm_machine.h"

_Static_assert(CHIP_OK == OMM_OK && CHIP_ERR_IMAGE == OMM_ERR_IMAGE && CHIP_ERR_FAULT == OMM_ERR_FAULT,
               "error codes pass through unchanged");
_Static_assert(CHIP_SEEK_FROM_START == OMM_SEEK_FROM_START && CHIP_SEEK_FROM_ORDER == OMM_SEEK_FROM_ORDER,
               "seek modes pass through unchanged");
_Static_assert(OMM_MAX_TICK_FRAMES <= CHIP_MAX_TICK_FRAMES && OMM_MAX_SAMPLE_RATE == CHIP_MAX_SAMPLE_RATE,
               "host buffers fit a tick");

#define MACHINE(m) ((OpmMachine*)(m))
#define IMAGE(i) ((const OpmImage*)(i))

static ChipMachine* opm_create(uint32_t sample_rate) { return (ChipMachine*)omm_create(sample_rate); }

static void opm_destroy(ChipMachine* m) { omm_destroy(MACHINE(m)); }

static int opm_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    OpmImage* image = (OpmImage*)storage;
    if (size != sizeof image->bytes) return CHIP_ERR_IMAGE;
    memcpy(image->bytes, bytes, size);
    return om_load_image(image) ? CHIP_ERR_IMAGE : CHIP_OK;
}

static int opm_start(ChipMachine* m, const ChipImage* image, int order, int row, int max_ticks, int* seek_mode) {
    return omm_start(MACHINE(m), IMAGE(image), order, row, max_ticks, seek_mode);
}

static void opm_apply_image(ChipMachine* m, const ChipImage* image) { omm_apply_image(MACHINE(m), IMAGE(image)); }

/* `note` = the absolute note (0 = C-0, A-4 = 57; playable 3..98), `instrument` = the cell's slot (1..64), `fine` =
 * 1/64 semitones (-128..127). */
static int opm_audition(ChipMachine* m, const ChipImage* image, int channel, int note, int instrument, int fine) {
    return omm_audition(MACHINE(m), IMAGE(image), channel, note, instrument, fine);
}

static int opm_release(ChipMachine* m) { return omm_release(MACHINE(m)); }

static int opm_mute(ChipMachine* m, int channel, int muted) { return omm_mute(MACHINE(m), channel, muted); }

static void opm_position(const ChipMachine* m, int* order, int* row, int* tick) {
    omm_position((const OpmMachine*)m, order, row, tick);
}

static int opm_tick(ChipMachine* m, float* out) { return omm_tick(MACHINE(m), out); }

const ChipEngineOps opm_chip_ops = {
    .system_id = "ym2151-opm",
    .image_size = sizeof(((OpmImage*)0)->bytes),
    .image_storage = sizeof(OpmImage),
    .create = opm_create,
    .destroy = opm_destroy,
    .load_image = opm_load_image,
    .start = opm_start,
    .apply_image = opm_apply_image,
    .audition = opm_audition,
    .release = opm_release,
    .mute = opm_mute,
    .position = opm_position,
    .tick = opm_tick,
};
