/* "sam-saa1099" and "cms-saa1099": Thor Tracker's own SAA1099 player on one SAA1099 at 8 MHz (SAM Coupe) or two at
 * 7159090 Hz (Creative Music System / Game Blaster), our clock-exact port of SAASound's model, behind the chip engine
 * interface. One machine serves both; each ops table loads only images of its own variant (header byte 5). Images are
 * validated by sa_load_image (saa_image.h); docs/AUDIO_ENGINE.md ("SAA1099") has the layout the lowering produces and
 * the ops' behaviour. */
#include <string.h>

#include "chip_engine.h"
#include "saa_machine.h"

_Static_assert(CHIP_OK == SAAM_OK && CHIP_ERR_IMAGE == SAAM_ERR_IMAGE && CHIP_ERR_FAULT == SAAM_ERR_FAULT,
               "error codes pass through unchanged");
_Static_assert(CHIP_SEEK_FROM_START == SAAM_SEEK_FROM_START && CHIP_SEEK_FROM_ORDER == SAAM_SEEK_FROM_ORDER,
               "seek modes pass through unchanged");
_Static_assert(SAAM_MAX_TICK_FRAMES <= CHIP_MAX_TICK_FRAMES && SAAM_MAX_SAMPLE_RATE == CHIP_MAX_SAMPLE_RATE,
               "host buffers fit a tick");
_Static_assert(sizeof(((SaaImage*)0)->bytes) == SA_IMAGE_SIZE, "the lowered image is the whole byte array");

#define MACHINE(m) ((SaaMachine*)(m))
#define IMAGE(i) ((const SaaImage*)(i))

static ChipMachine* saa_create(uint32_t sample_rate) { return (ChipMachine*)saam_create(sample_rate); }

static void saa_destroy(ChipMachine* m) { saam_destroy(MACHINE(m)); }

static int load_variant(ChipImage* storage, const uint8_t* bytes, size_t size, int variant) {
    SaaImage* image = (SaaImage*)storage;
    if (size != sizeof image->bytes) return CHIP_ERR_IMAGE;
    memcpy(image->bytes, bytes, size);
    if (sa_load_image(image)) return CHIP_ERR_IMAGE;
    if (image->info.variant != variant) {
        image->info.loaded = 0; /* an image for the other machine: never played by this id */
        return CHIP_ERR_IMAGE;
    }
    return CHIP_OK;
}

static int sam_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load_variant(storage, bytes, size, SA_VARIANT_SAM);
}

static int cms_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load_variant(storage, bytes, size, SA_VARIANT_CMS);
}

static int saa_start(ChipMachine* m, const ChipImage* image, int order, int row, int max_ticks, int* seek_mode) {
    return saam_start(MACHINE(m), IMAGE(image), order, row, max_ticks, seek_mode);
}

/* Ignored while auditioning or for an image of the other variant (saam_apply_image). */
static void saa_apply_image(ChipMachine* m, const ChipImage* image) { saam_apply_image(MACHINE(m), IMAGE(image)); }

/* `note` = the absolute note (0 = C-0, A-4 = 57), `instrument` = the cell's slot (1..64), `fine` = 1/64 semitones
 * (-128..127). */
static int saa_audition(ChipMachine* m, const ChipImage* image, int channel, int note, int instrument, int fine) {
    return saam_audition(MACHINE(m), IMAGE(image), channel, note, instrument, fine);
}

static int saa_release(ChipMachine* m) { return saam_release(MACHINE(m)); }

static int saa_mute(ChipMachine* m, int channel, int muted) { return saam_mute(MACHINE(m), channel, muted); }

static void saa_position(const ChipMachine* m, int* order, int* row, int* tick) {
    saam_position((const SaaMachine*)m, order, row, tick);
}

static int saa_tick(ChipMachine* m, float* out) { return saam_tick(MACHINE(m), out); }

const ChipEngineOps saa_sam_chip_ops = {
    .system_id = "sam-saa1099",
    .image_size = SA_IMAGE_SIZE,
    .image_storage = sizeof(SaaImage),
    .create = saa_create,
    .destroy = saa_destroy,
    .load_image = sam_load_image,
    .start = saa_start,
    .apply_image = saa_apply_image,
    .audition = saa_audition,
    .release = saa_release,
    .mute = saa_mute,
    .position = saa_position,
    .tick = saa_tick,
};

const ChipEngineOps saa_cms_chip_ops = {
    .system_id = "cms-saa1099",
    .image_size = SA_IMAGE_SIZE,
    .image_storage = sizeof(SaaImage),
    .create = saa_create,
    .destroy = saa_destroy,
    .load_image = cms_load_image,
    .start = saa_start,
    .apply_image = saa_apply_image,
    .audition = saa_audition,
    .release = saa_release,
    .mute = saa_mute,
    .position = saa_position,
    .tick = saa_tick,
};
