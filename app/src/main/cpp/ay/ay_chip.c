/* "ay-zx128", "ay-msx", "ay-atari-st", "ay-cpc": Thor Tracker's own AY-3-8910 / YM2149 player on its own chip core, one
 * engine per stock computer, behind the chip engine interface. Images are validated by ay_load_image (ay_image.h)
 * for the engine's variant only; docs/AUDIO_ENGINE.md ("AY-3-8910 / YM2149") has the layout the lowering produces
 * and the ops' behaviour. */
#include <string.h>

#include "ay_machine.h"
#include "chip_engine.h"

_Static_assert(CHIP_OK == AYM_OK && CHIP_ERR_IMAGE == AYM_ERR_IMAGE && CHIP_ERR_FAULT == AYM_ERR_FAULT,
               "error codes pass through unchanged");
_Static_assert(CHIP_SEEK_FROM_START == AYM_SEEK_FROM_START && CHIP_SEEK_FROM_ORDER == AYM_SEEK_FROM_ORDER,
               "seek modes pass through unchanged");
_Static_assert(AYM_MAX_TICK_FRAMES <= CHIP_MAX_TICK_FRAMES && AYM_MAX_SAMPLE_RATE == CHIP_MAX_SAMPLE_RATE,
               "host buffers fit a tick");

#define MACHINE(m) ((AyMachine*)(m))
#define IMAGE(i) ((const AyImage*)(i))

static int load(ChipImage* storage, const uint8_t* bytes, size_t size, int variant) {
    AyImage* image = (AyImage*)storage;
    if (size != sizeof image->bytes) return CHIP_ERR_IMAGE;
    memcpy(image->bytes, bytes, size);
    return ay_load_image(image, variant) ? CHIP_ERR_IMAGE : CHIP_OK;
}

static ChipMachine* ay_zx128_create(uint32_t sample_rate) { return (ChipMachine*)ay_machine_create(sample_rate, AY_VARIANT_ZX128); }
static ChipMachine* ay_msx_create(uint32_t sample_rate) { return (ChipMachine*)ay_machine_create(sample_rate, AY_VARIANT_MSX); }
static ChipMachine* ay_atari_st_create(uint32_t sample_rate) {
    return (ChipMachine*)ay_machine_create(sample_rate, AY_VARIANT_ATARI_ST);
}
static ChipMachine* ay_cpc_create(uint32_t sample_rate) { return (ChipMachine*)ay_machine_create(sample_rate, AY_VARIANT_CPC); }

static int ay_zx128_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load(storage, bytes, size, AY_VARIANT_ZX128);
}
static int ay_msx_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load(storage, bytes, size, AY_VARIANT_MSX);
}
static int ay_atari_st_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load(storage, bytes, size, AY_VARIANT_ATARI_ST);
}
static int ay_cpc_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load(storage, bytes, size, AY_VARIANT_CPC);
}

static void ay_destroy(ChipMachine* m) { ay_machine_destroy(MACHINE(m)); }

static int ay_start(ChipMachine* m, const ChipImage* image, int order, int row, int max_ticks, int* seek_mode) {
    return ay_machine_start(MACHINE(m), IMAGE(image), order, row, max_ticks, seek_mode);
}

static void ay_apply_image(ChipMachine* m, const ChipImage* image) { ay_machine_apply_image(MACHINE(m), IMAGE(image)); }

/* `note` = the absolute note (0 = C-0, A-4 = 57), `instrument` = the cell's slot (1..64), `fine` = 1/64 semitones
 * (-128..127). */
static int ay_audition(ChipMachine* m, const ChipImage* image, int channel, int note, int instrument, int fine) {
    return ay_machine_audition(MACHINE(m), IMAGE(image), channel, note, instrument, fine);
}

static int ay_release(ChipMachine* m) { return ay_machine_release(MACHINE(m)); }

static int ay_mute(ChipMachine* m, int channel, int muted) { return ay_machine_mute(MACHINE(m), channel, muted); }

static void ay_position(const ChipMachine* m, int* order, int* row, int* tick) {
    ay_machine_position((const AyMachine*)m, order, row, tick);
}

static int ay_tick(ChipMachine* m, float* out) { return ay_machine_tick(MACHINE(m), out); }

#define AY_OPS(id, create_fn, load_fn)                  \
    {                                                   \
        .system_id = id,                                \
        .image_size = sizeof(((AyImage*)0)->bytes),     \
        .image_storage = sizeof(AyImage),               \
        .create = create_fn,                            \
        .destroy = ay_destroy,                          \
        .load_image = load_fn,                          \
        .start = ay_start,                              \
        .apply_image = ay_apply_image,                  \
        .audition = ay_audition,                        \
        .release = ay_release,                          \
        .mute = ay_mute,                                \
        .position = ay_position,                        \
        .tick = ay_tick,                                \
    }

const ChipEngineOps ay_zx128_chip_ops = AY_OPS(AY_ENGINE_ID_ZX128, ay_zx128_create, ay_zx128_load_image);
const ChipEngineOps ay_msx_chip_ops = AY_OPS(AY_ENGINE_ID_MSX, ay_msx_create, ay_msx_load_image);
const ChipEngineOps ay_atari_st_chip_ops = AY_OPS(AY_ENGINE_ID_ATARI_ST, ay_atari_st_create, ay_atari_st_load_image);
const ChipEngineOps ay_cpc_chip_ops = AY_OPS(AY_ENGINE_ID_CPC, ay_cpc_create, ay_cpc_load_image);
