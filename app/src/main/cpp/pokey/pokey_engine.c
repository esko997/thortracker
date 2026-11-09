/* "atari-pokey": Thor Tracker's own player on the Atari 8-bit POKEY (pokey_chip.c) behind the chip engine interface.
 * Images are validated by pk_load_image (pokey_image.h); docs/AUDIO_ENGINE.md ("POKEY") has the layout the lowering
 * produces and the ops' behaviour. */
#include <string.h>

#include "chip_engine.h"
#include "pokey_machine.h"

_Static_assert(CHIP_OK == PKM_OK && CHIP_ERR_IMAGE == PKM_ERR_IMAGE && CHIP_ERR_FAULT == PKM_ERR_FAULT,
               "error codes pass through unchanged");
_Static_assert(CHIP_SEEK_FROM_START == PKM_SEEK_FROM_START && CHIP_SEEK_FROM_ORDER == PKM_SEEK_FROM_ORDER,
               "seek modes pass through unchanged");
_Static_assert(PKM_MAX_TICK_FRAMES <= CHIP_MAX_TICK_FRAMES && PKM_MAX_SAMPLE_RATE == CHIP_MAX_SAMPLE_RATE,
               "host buffers fit a tick");

#define MACHINE(m) ((PokeyMachine*)(m))
#define IMAGE(i) ((const PokeyImage*)(i))

static ChipMachine* pokey_create(uint32_t sample_rate) { return (ChipMachine*)pkm_create(sample_rate); }

static void pokey_destroy(ChipMachine* m) { pkm_destroy(MACHINE(m)); }

static int pokey_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    PokeyImage* image = (PokeyImage*)storage;
    if (size != sizeof image->bytes) return CHIP_ERR_IMAGE;
    memcpy(image->bytes, bytes, size);
    return pk_load_image(image) ? CHIP_ERR_IMAGE : CHIP_OK;
}

static int pokey_start(ChipMachine* m, const ChipImage* image, int order, int row, int max_ticks, int* seek_mode) {
    return pkm_start(MACHINE(m), IMAGE(image), order, row, max_ticks, seek_mode);
}

static void pokey_apply_image(ChipMachine* m, const ChipImage* image) { pkm_apply_image(MACHINE(m), IMAGE(image)); }

/* `channel` = the lane (POKEY channel 1-4 = 0-3), `note` = the absolute note (0 = C-0, A-4 = 57), `instrument` = the
 * cell's slot (1..64), `fine` = 1/64 semitones (-128..127). */
static int pokey_audition(ChipMachine* m, const ChipImage* image, int channel, int note, int instrument, int fine) {
    return pkm_audition(MACHINE(m), IMAGE(image), channel, note, instrument, fine);
}

static int pokey_release(ChipMachine* m) { return pkm_release(MACHINE(m)); }

static int pokey_mute(ChipMachine* m, int channel, int muted) { return pkm_mute(MACHINE(m), channel, muted); }

static void pokey_position(const ChipMachine* m, int* order, int* row, int* tick) {
    pkm_position((const PokeyMachine*)m, order, row, tick);
}

static int pokey_tick(ChipMachine* m, float* out) { return pkm_tick(MACHINE(m), out); }

const ChipEngineOps pokey_chip_ops = {
    .system_id = "atari-pokey",
    .image_size = sizeof(((PokeyImage*)0)->bytes),
    .image_storage = sizeof(PokeyImage),
    .create = pokey_create,
    .destroy = pokey_destroy,
    .load_image = pokey_load_image,
    .start = pokey_start,
    .apply_image = pokey_apply_image,
    .audition = pokey_audition,
    .release = pokey_release,
    .mute = pokey_mute,
    .position = pokey_position,
    .tick = pokey_tick,
};
