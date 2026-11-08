/* "opl3-fm": Thor Tracker's own OPL3 player on a YMF262 (Nuked-OPL3) behind the chip engine interface.
 * Images are validated by o3_load_image (opl3_image.h); docs/AUDIO_ENGINE.md ("OPL3") has the layout the
 * lowering produces and the ops' behaviour. */
#include <string.h>

#include "chip_engine.h"
#include "opl3_machine.h"

_Static_assert(CHIP_OK == O3M_OK && CHIP_ERR_IMAGE == O3M_ERR_IMAGE && CHIP_ERR_FAULT == O3M_ERR_FAULT,
               "error codes pass through unchanged");
_Static_assert(CHIP_SEEK_FROM_START == O3M_SEEK_FROM_START && CHIP_SEEK_FROM_ORDER == O3M_SEEK_FROM_ORDER,
               "seek modes pass through unchanged");
_Static_assert(O3M_MAX_TICK_FRAMES <= CHIP_MAX_TICK_FRAMES && O3M_MAX_SAMPLE_RATE == CHIP_MAX_SAMPLE_RATE,
               "host buffers fit a tick");

#define MACHINE(m) ((Opl3Machine*)(m))
#define IMAGE(i) ((const Opl3Image*)(i))

static ChipMachine* opl3_create(uint32_t sample_rate) { return (ChipMachine*)o3m_create(sample_rate); }

static void opl3_destroy(ChipMachine* m) { o3m_destroy(MACHINE(m)); }

static int opl3_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    Opl3Image* image = (Opl3Image*)storage;
    if (size != sizeof image->bytes) return CHIP_ERR_IMAGE;
    memcpy(image->bytes, bytes, size);
    return o3_load_image(image) ? CHIP_ERR_IMAGE : CHIP_OK;
}

static int opl3_start(ChipMachine* m, const ChipImage* image, int order, int row, int max_ticks, int* seek_mode) {
    return o3m_start(MACHINE(m), IMAGE(image), order, row, max_ticks, seek_mode);
}

static void opl3_apply_image(ChipMachine* m, const ChipImage* image) { o3m_apply_image(MACHINE(m), IMAGE(image)); }

/* `note` = the absolute note (0 = C-0, A-4 = 57), `instrument` = the cell's slot (1..64), `fine` = 1/64
 * semitones (-128..127). */
static int opl3_audition(ChipMachine* m, const ChipImage* image, int channel, int note, int instrument, int fine) {
    return o3m_audition(MACHINE(m), IMAGE(image), channel, note, instrument, fine);
}

static int opl3_release(ChipMachine* m) { return o3m_release(MACHINE(m)); }

static int opl3_mute(ChipMachine* m, int channel, int muted) { return o3m_mute(MACHINE(m), channel, muted); }

static void opl3_position(const ChipMachine* m, int* order, int* row, int* tick) {
    o3m_position((const Opl3Machine*)m, order, row, tick);
}

static int opl3_tick(ChipMachine* m, float* out) { return o3m_tick(MACHINE(m), out); }

const ChipEngineOps opl3_chip_ops = {
    .system_id = "opl3-fm",
    .image_size = sizeof(((Opl3Image*)0)->bytes),
    .image_storage = sizeof(Opl3Image),
    .create = opl3_create,
    .destroy = opl3_destroy,
    .load_image = opl3_load_image,
    .start = opl3_start,
    .apply_image = opl3_apply_image,
    .audition = opl3_audition,
    .release = opl3_release,
    .mute = opl3_mute,
    .position = opl3_position,
    .tick = opl3_tick,
};
