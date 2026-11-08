/* "atari2600-tia-ntsc" and "atari2600-tia-pal": Thor Tracker's own Atari 2600 player on the TIA's two audio channels
 * (Stella 7.0's AudioChannel) behind the chip engine interface. The two variants share the machine and differ in the
 * region (colour clock, lines per frame, AUDF edge table); each loads only images of its own region (header byte 5).
 * Images are validated by ti_load_image (tia_image.h); docs/AUDIO_ENGINE.md ("Atari 2600") has the layout the lowering
 * produces and the ops' behaviour. No export entry point: WAV export goes through chip_wav.c with these ops. */
#include <string.h>

#include "chip_engine.h"
#include "tia_machine.h"

_Static_assert(CHIP_OK == TIM_OK && CHIP_ERR_IMAGE == TIM_ERR_IMAGE && CHIP_ERR_FAULT == TIM_ERR_FAULT,
               "error codes pass through unchanged");
_Static_assert(CHIP_SEEK_FROM_START == TIM_SEEK_FROM_START && CHIP_SEEK_FROM_ORDER == TIM_SEEK_FROM_ORDER,
               "seek modes pass through unchanged");
_Static_assert(TIM_MAX_TICK_FRAMES <= CHIP_MAX_TICK_FRAMES && TIM_MAX_SAMPLE_RATE == CHIP_MAX_SAMPLE_RATE,
               "host buffers fit a tick");
_Static_assert(sizeof(((TiaImage*)0)->bytes) == TI_IMAGE_SIZE, "image size");

#define MACHINE(m) ((TiaMachine*)(m))
#define IMAGE(i) ((const TiaImage*)(i))

static ChipMachine* tia_create_ntsc(uint32_t sample_rate) { return (ChipMachine*)tim_create(sample_rate, TI_REGION_NTSC); }

static ChipMachine* tia_create_pal(uint32_t sample_rate) { return (ChipMachine*)tim_create(sample_rate, TI_REGION_PAL); }

static void tia_destroy(ChipMachine* m) { tim_destroy(MACHINE(m)); }

static int load(ChipImage* storage, const uint8_t* bytes, size_t size, int region) {
    TiaImage* image = (TiaImage*)storage;
    if (size != sizeof image->bytes) return CHIP_ERR_IMAGE;
    memcpy(image->bytes, bytes, size);
    return ti_load_image(image, region) ? CHIP_ERR_IMAGE : CHIP_OK;
}

static int tia_load_ntsc(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load(storage, bytes, size, TI_REGION_NTSC);
}

static int tia_load_pal(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load(storage, bytes, size, TI_REGION_PAL);
}

static int tia_start(ChipMachine* m, const ChipImage* image, int order, int row, int max_ticks, int* seek_mode) {
    return tim_start(MACHINE(m), IMAGE(image), order, row, max_ticks, seek_mode);
}

static void tia_apply_image(ChipMachine* m, const ChipImage* image) { tim_apply_image(MACHINE(m), IMAGE(image)); }

/* `note` = the absolute note (0 = C-0, A-4 = 57; 0..119), `instrument` = the cell's slot (1..64), `fine` = 1/64
 * semitones (-128..127). */
static int tia_audition(ChipMachine* m, const ChipImage* image, int channel, int note, int instrument, int fine) {
    return tim_audition(MACHINE(m), IMAGE(image), channel, note, instrument, fine);
}

static int tia_release(ChipMachine* m) { return tim_release(MACHINE(m)); }

static int tia_mute(ChipMachine* m, int channel, int muted) { return tim_mute(MACHINE(m), channel, muted); }

static void tia_position(const ChipMachine* m, int* order, int* row, int* tick) {
    tim_position((const TiaMachine*)m, order, row, tick);
}

static int tia_tick(ChipMachine* m, float* out) { return tim_tick(MACHINE(m), out); }

const ChipEngineOps tia_ntsc_chip_ops = {
    .system_id = "atari2600-tia-ntsc",
    .image_size = sizeof(((TiaImage*)0)->bytes),
    .image_storage = sizeof(TiaImage),
    .create = tia_create_ntsc,
    .destroy = tia_destroy,
    .load_image = tia_load_ntsc,
    .start = tia_start,
    .apply_image = tia_apply_image,
    .audition = tia_audition,
    .release = tia_release,
    .mute = tia_mute,
    .position = tia_position,
    .tick = tia_tick,
};

const ChipEngineOps tia_pal_chip_ops = {
    .system_id = "atari2600-tia-pal",
    .image_size = sizeof(((TiaImage*)0)->bytes),
    .image_storage = sizeof(TiaImage),
    .create = tia_create_pal,
    .destroy = tia_destroy,
    .load_image = tia_load_pal,
    .start = tia_start,
    .apply_image = tia_apply_image,
    .audition = tia_audition,
    .release = tia_release,
    .mute = tia_mute,
    .position = tia_position,
    .tick = tia_tick,
};
