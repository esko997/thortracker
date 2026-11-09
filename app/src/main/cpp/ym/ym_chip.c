/* "ym2612-fm": Thor Tracker's own player streamed through SGDK's XGM2 driver on a Z80 + YM2612 (ymfm) + SN76489, the
 * driver mixing the song's PCM into the DAC, behind the chip engine interface. Images (layout 2, YM_IMAGE_SIZE bytes)
 * are validated by ym_load_image (ym_image.h); docs/AUDIO_ENGINE.md ("YM2612") has the layout the lowering produces
 * and the ops' behaviour. Channels 0-12: FM 1-6, PSG 1-3, Noise, PCM 1-3. */
#include <string.h>

#include "chip_engine.h"
#include "ym_machine.h"

_Static_assert(CHIP_OK == YMM_OK && CHIP_ERR_IMAGE == YMM_ERR_IMAGE && CHIP_ERR_FAULT == YMM_ERR_FAULT,
               "error codes pass through unchanged");
_Static_assert(CHIP_SEEK_FROM_START == YMM_SEEK_FROM_START && CHIP_SEEK_FROM_ORDER == YMM_SEEK_FROM_ORDER,
               "seek modes pass through unchanged");
_Static_assert(YMM_MAX_TICK_FRAMES <= CHIP_MAX_TICK_FRAMES && YMM_MAX_SAMPLE_RATE == CHIP_MAX_SAMPLE_RATE,
               "host buffers fit a tick");

#define MACHINE(m) ((YmMachine*)(m))
#define IMAGE(i) ((const YmImage*)(i))

static ChipMachine* ym_create(uint32_t sample_rate) { return (ChipMachine*)ymm_create(sample_rate); }

static void ym_destroy(ChipMachine* m) { ymm_destroy(MACHINE(m)); }

static int ym_load(ChipImage* storage, const uint8_t* bytes, size_t size) {
    YmImage* image = (YmImage*)storage;
    if (size != sizeof image->bytes) return CHIP_ERR_IMAGE;
    memcpy(image->bytes, bytes, size);
    return ym_load_image(image) ? CHIP_ERR_IMAGE : CHIP_OK;
}

static int ym_start(ChipMachine* m, const ChipImage* image, int order, int row, int max_ticks, int* seek_mode) {
    return ymm_start(MACHINE(m), IMAGE(image), order, row, max_ticks, seek_mode);
}

static void ym_apply_image(ChipMachine* m, const ChipImage* image) { ymm_apply_image(MACHINE(m), IMAGE(image)); }

/* `note` = the absolute note (0 = C-0, A-4 = 57), `instrument` = the cell's slot (1..64), `fine` = 1/64 semitones
 * (-128..127). */
static int ym_audition(ChipMachine* m, const ChipImage* image, int channel, int note, int instrument, int fine) {
    return ymm_audition(MACHINE(m), IMAGE(image), channel, note, instrument, fine);
}

static int ym_release(ChipMachine* m) { return ymm_release(MACHINE(m)); }

static int ym_mute(ChipMachine* m, int channel, int muted) { return ymm_mute(MACHINE(m), channel, muted); }

static void ym_position(const ChipMachine* m, int* order, int* row, int* tick) {
    ymm_position((const YmMachine*)m, order, row, tick);
}

static uint32_t ym_row_serial(const ChipMachine* m) { return ymm_row_serial((const YmMachine*)m); }

static int ym_tick(ChipMachine* m, float* out) { return ymm_tick(MACHINE(m), out); }

const ChipEngineOps ym_chip_ops = {
    .system_id = "ym2612-fm",
    .image_size = sizeof(((YmImage*)0)->bytes),
    .image_storage = sizeof(YmImage),
    .create = ym_create,
    .destroy = ym_destroy,
    .load_image = ym_load,
    .start = ym_start,
    .apply_image = ym_apply_image,
    .audition = ym_audition,
    .release = ym_release,
    .mute = ym_mute,
    .position = ym_position,
    .tick = ym_tick,
    .row_serial = ym_row_serial,
};
