/* "vic20-vic" and "c16-ted": Thor Tracker's own player on the VIC-20's VIC (VICE's sound model) and on the
 * C16/Plus/4's TED (plus4emu's), behind the chip engine interface. One image layout and machine for both; each ops
 * table accepts only its chip's images (cb_load_image with the ops' chip). docs/AUDIO_ENGINE.md ("VIC-20 and
 * C16/Plus/4") has the layout the lowering produces and the ops' behaviour. */
#include <string.h>

#include "chip_engine.h"
#include "cbm_machine.h"

_Static_assert(CHIP_OK == CBM_OK && CHIP_ERR_IMAGE == CBM_ERR_IMAGE && CHIP_ERR_FAULT == CBM_ERR_FAULT,
               "error codes pass through unchanged");
_Static_assert(CHIP_SEEK_FROM_START == CBM_SEEK_FROM_START && CHIP_SEEK_FROM_ORDER == CBM_SEEK_FROM_ORDER,
               "seek modes pass through unchanged");
_Static_assert(CBM_MAX_TICK_FRAMES <= CHIP_MAX_TICK_FRAMES && CBM_MAX_SAMPLE_RATE == CHIP_MAX_SAMPLE_RATE,
               "host buffers fit a tick");

#define MACHINE(m) ((CbmMachine*)(m))
#define IMAGE(i) ((const CbmImage*)(i))

static ChipMachine* vic_create(uint32_t sample_rate) { return (ChipMachine*)cbm_create(sample_rate, CB_CHIP_VIC); }
static ChipMachine* ted_create(uint32_t sample_rate) { return (ChipMachine*)cbm_create(sample_rate, CB_CHIP_TED); }

static void cbm_ops_destroy(ChipMachine* m) { cbm_destroy(MACHINE(m)); }

static int load(ChipImage* storage, const uint8_t* bytes, size_t size, int chip) {
    CbmImage* image = (CbmImage*)storage;
    if (size != sizeof image->bytes) return CHIP_ERR_IMAGE;
    memcpy(image->bytes, bytes, size);
    return cb_load_image(image, chip) ? CHIP_ERR_IMAGE : CHIP_OK;
}

static int vic_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load(storage, bytes, size, CB_CHIP_VIC);
}

static int ted_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load(storage, bytes, size, CB_CHIP_TED);
}

static int cbm_ops_start(ChipMachine* m, const ChipImage* image, int order, int row, int max_ticks, int* seek_mode) {
    return cbm_start(MACHINE(m), IMAGE(image), order, row, max_ticks, seek_mode);
}

static void cbm_ops_apply_image(ChipMachine* m, const ChipImage* image) { cbm_apply_image(MACHINE(m), IMAGE(image)); }

/* `note` = the absolute note (0 = C-0, A-4 = 57; the VIC noise lane's rate 0..127), `instrument` = the cell's slot
 * (1..64), `fine` = 1/64 semitones (-128..127; ignored on the VIC noise lane). */
static int cbm_ops_audition(ChipMachine* m, const ChipImage* image, int channel, int note, int instrument, int fine) {
    return cbm_audition(MACHINE(m), IMAGE(image), channel, note, instrument, fine);
}

static int cbm_ops_release(ChipMachine* m) { return cbm_release(MACHINE(m)); }

static int cbm_ops_mute(ChipMachine* m, int channel, int muted) { return cbm_mute(MACHINE(m), channel, muted); }

static void cbm_ops_position(const ChipMachine* m, int* order, int* row, int* tick) {
    cbm_position((const CbmMachine*)m, order, row, tick);
}

static int cbm_ops_tick(ChipMachine* m, float* out) { return cbm_tick(MACHINE(m), out); }

const ChipEngineOps vic_chip_ops = {
    .system_id = "vic20-vic",
    .image_size = sizeof(((CbmImage*)0)->bytes),
    .image_storage = sizeof(CbmImage),
    .create = vic_create,
    .destroy = cbm_ops_destroy,
    .load_image = vic_load_image,
    .start = cbm_ops_start,
    .apply_image = cbm_ops_apply_image,
    .audition = cbm_ops_audition,
    .release = cbm_ops_release,
    .mute = cbm_ops_mute,
    .position = cbm_ops_position,
    .tick = cbm_ops_tick,
    .row_serial = NULL,
};

const ChipEngineOps ted_chip_ops = {
    .system_id = "c16-ted",
    .image_size = sizeof(((CbmImage*)0)->bytes),
    .image_storage = sizeof(CbmImage),
    .create = ted_create,
    .destroy = cbm_ops_destroy,
    .load_image = ted_load_image,
    .start = cbm_ops_start,
    .apply_image = cbm_ops_apply_image,
    .audition = cbm_ops_audition,
    .release = cbm_ops_release,
    .mute = cbm_ops_mute,
    .position = cbm_ops_position,
    .tick = cbm_ops_tick,
    .row_serial = NULL,
};
