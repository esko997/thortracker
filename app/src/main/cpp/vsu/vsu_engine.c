/* "vb-vsu": Thor Tracker's own player on the Virtual Boy VSU (vsu_chip.c) behind the chip engine interface. Images
 * are validated by vb_load_image (vsu_image.h); docs/AUDIO_ENGINE.md ("Virtual Boy VSU") has the layout the lowering
 * produces and the ops' behaviour. */
#include <string.h>

#include "chip_engine.h"
#include "vsu_machine.h"

_Static_assert(CHIP_OK == VBM_OK && CHIP_ERR_IMAGE == VBM_ERR_IMAGE && CHIP_ERR_FAULT == VBM_ERR_FAULT,
               "error codes pass through unchanged");
_Static_assert(CHIP_SEEK_FROM_START == VBM_SEEK_FROM_START && CHIP_SEEK_FROM_ORDER == VBM_SEEK_FROM_ORDER,
               "seek modes pass through unchanged");
_Static_assert(VBM_MAX_TICK_FRAMES <= CHIP_MAX_TICK_FRAMES && VBM_MAX_SAMPLE_RATE == CHIP_MAX_SAMPLE_RATE,
               "host buffers fit a tick");

#define MACHINE(m) ((VsuMachine*)(m))
#define IMAGE(i) ((const VsuImage*)(i))

static ChipMachine* vsu_create(uint32_t sample_rate) { return (ChipMachine*)vbm_create(sample_rate); }

static void vsu_destroy(ChipMachine* m) { vbm_destroy(MACHINE(m)); }

static int vsu_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    VsuImage* image = (VsuImage*)storage;
    if (size != sizeof image->bytes) return CHIP_ERR_IMAGE;
    memcpy(image->bytes, bytes, size);
    return vb_load_image(image) ? CHIP_ERR_IMAGE : CHIP_OK;
}

static int vsu_start(ChipMachine* m, const ChipImage* image, int order, int row, int max_ticks, int* seek_mode) {
    return vbm_start(MACHINE(m), IMAGE(image), order, row, max_ticks, seek_mode);
}

static void vsu_apply_image(ChipMachine* m, const ChipImage* image) { vbm_apply_image(MACHINE(m), IMAGE(image)); }

/* `channel` = the lane (Wave 1-4 = 0-3, Wave 5 = 4, Noise = 5), `note` = the absolute note (0 = C-0, A-4 = 57),
 * `instrument` = the cell's slot (1..64), `fine` = 1/64 semitones (-128..127). */
static int vsu_audition(ChipMachine* m, const ChipImage* image, int channel, int note, int instrument, int fine) {
    return vbm_audition(MACHINE(m), IMAGE(image), channel, note, instrument, fine);
}

static int vsu_release(ChipMachine* m) { return vbm_release(MACHINE(m)); }

static int vsu_mute(ChipMachine* m, int channel, int muted) { return vbm_mute(MACHINE(m), channel, muted); }

static void vsu_position(const ChipMachine* m, int* order, int* row, int* tick) {
    vbm_position((const VsuMachine*)m, order, row, tick);
}

static int vsu_tick(ChipMachine* m, float* out) { return vbm_tick(MACHINE(m), out); }

const ChipEngineOps vsu_chip_ops = {
    .system_id = "vb-vsu",
    .image_size = sizeof(((VsuImage*)0)->bytes),
    .image_storage = sizeof(VsuImage),
    .create = vsu_create,
    .destroy = vsu_destroy,
    .load_image = vsu_load_image,
    .start = vsu_start,
    .apply_image = vsu_apply_image,
    .audition = vsu_audition,
    .release = vsu_release,
    .mute = vsu_mute,
    .position = vsu_position,
    .tick = vsu_tick,
};
