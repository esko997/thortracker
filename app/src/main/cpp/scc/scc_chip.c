/* "msx-scc" and "msx-scc-plus": Thor Tracker's own SCC player on a Konami K051649 (SCC) or K052539 (SCC+ mode), our
 * port of MAME's k051649 core, behind the chip engine interface. One machine serves both; each ops table loads only
 * images of its own variant (header byte 5). Images are validated by sc_load_image (scc_image.h);
 * docs/AUDIO_ENGINE.md ("SCC and SCC+") has the layout the lowering produces and the ops' behaviour. */
#include <string.h>

#include "chip_engine.h"
#include "scc_machine.h"

_Static_assert(CHIP_OK == SCM_OK && CHIP_ERR_IMAGE == SCM_ERR_IMAGE && CHIP_ERR_FAULT == SCM_ERR_FAULT,
               "error codes pass through unchanged");
_Static_assert(CHIP_SEEK_FROM_START == SCM_SEEK_FROM_START && CHIP_SEEK_FROM_ORDER == SCM_SEEK_FROM_ORDER,
               "seek modes pass through unchanged");
_Static_assert(SCM_MAX_TICK_FRAMES <= CHIP_MAX_TICK_FRAMES && SCM_MAX_SAMPLE_RATE == CHIP_MAX_SAMPLE_RATE,
               "host buffers fit a tick");
_Static_assert(sizeof(((SccImage*)0)->bytes) == SC_IMAGE_SIZE, "the lowered image is the whole byte array");

#define MACHINE(m) ((SccMachine*)(m))
#define IMAGE(i) ((const SccImage*)(i))

static ChipMachine* scc_create(uint32_t sample_rate) { return (ChipMachine*)scm_create(sample_rate); }

static void scc_destroy(ChipMachine* m) { scm_destroy(MACHINE(m)); }

static int load_variant(ChipImage* storage, const uint8_t* bytes, size_t size, int variant) {
    SccImage* image = (SccImage*)storage;
    if (size != sizeof image->bytes) return CHIP_ERR_IMAGE;
    memcpy(image->bytes, bytes, size);
    if (sc_load_image(image)) return CHIP_ERR_IMAGE;
    if (image->info.variant != variant) {
        image->info.loaded = 0; /* an image for the other chip: never played by this id */
        return CHIP_ERR_IMAGE;
    }
    return CHIP_OK;
}

static int scc_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load_variant(storage, bytes, size, SC_VARIANT_SCC);
}

static int scc_plus_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load_variant(storage, bytes, size, SC_VARIANT_PLUS);
}

static int scc_start(ChipMachine* m, const ChipImage* image, int order, int row, int max_ticks, int* seek_mode) {
    return scm_start(MACHINE(m), IMAGE(image), order, row, max_ticks, seek_mode);
}

/* Ignored while auditioning or for an image of the other variant (scm_apply_image). */
static void scc_apply_image(ChipMachine* m, const ChipImage* image) { scm_apply_image(MACHINE(m), IMAGE(image)); }

/* `note` = the absolute note (0 = C-0, A-4 = 57), `instrument` = the cell's slot (1..64), `fine` = 1/64 semitones
 * (-128..127). */
static int scc_audition(ChipMachine* m, const ChipImage* image, int channel, int note, int instrument, int fine) {
    return scm_audition(MACHINE(m), IMAGE(image), channel, note, instrument, fine);
}

static int scc_release(ChipMachine* m) { return scm_release(MACHINE(m)); }

static int scc_mute(ChipMachine* m, int channel, int muted) { return scm_mute(MACHINE(m), channel, muted); }

static void scc_position(const ChipMachine* m, int* order, int* row, int* tick) {
    scm_position((const SccMachine*)m, order, row, tick);
}

static int scc_tick(ChipMachine* m, float* out) { return scm_tick(MACHINE(m), out); }

const ChipEngineOps scc_chip_ops = {
    .system_id = "msx-scc",
    .image_size = SC_IMAGE_SIZE,
    .image_storage = sizeof(SccImage),
    .create = scc_create,
    .destroy = scc_destroy,
    .load_image = scc_load_image,
    .start = scc_start,
    .apply_image = scc_apply_image,
    .audition = scc_audition,
    .release = scc_release,
    .mute = scc_mute,
    .position = scc_position,
    .tick = scc_tick,
};

const ChipEngineOps scc_plus_chip_ops = {
    .system_id = "msx-scc-plus",
    .image_size = SC_IMAGE_SIZE,
    .image_storage = sizeof(SccImage),
    .create = scc_create,
    .destroy = scc_destroy,
    .load_image = scc_plus_load_image,
    .start = scc_start,
    .apply_image = scc_apply_image,
    .audition = scc_audition,
    .release = scc_release,
    .mute = scc_mute,
    .position = scc_position,
    .tick = scc_tick,
};
