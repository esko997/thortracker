/* "gb-huge": the hUGEDriver machine (huge_machine.h) behind the chip engine interface. A thin
 * adapter: every call maps to one hm_* call, so the Game Boy plays exactly as before. */
#include <string.h>

#include "chip_engine.h"
#include "huge_machine.h"

_Static_assert(CHIP_OK == HM_OK && CHIP_ERR_IMAGE == HM_ERR_IMAGE && CHIP_ERR_FAULT == HM_ERR_FAULT,
               "error codes pass through unchanged");
_Static_assert(CHIP_SEEK_FROM_START == HM_SEEK_FROM_START && CHIP_SEEK_FROM_ORDER == HM_SEEK_FROM_ORDER,
               "seek modes pass through unchanged");
_Static_assert(HM_MAX_TICK_FRAMES <= CHIP_MAX_TICK_FRAMES && HM_MAX_SAMPLE_RATE == CHIP_MAX_SAMPLE_RATE,
               "host buffers fit a tick");

#define MACHINE(m) ((HugeMachine*)(m))
#define IMAGE(i) ((const HugeImage*)(i))

static ChipMachine* gb_create(uint32_t sample_rate) { return (ChipMachine*)hm_create(sample_rate); }

static void gb_destroy(ChipMachine* m) { hm_destroy(MACHINE(m)); }

/* The version field is the machine's own bookkeeping; hosts track image versions themselves. */
static int gb_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    HugeImage* image = (HugeImage*)storage;
    if (size != sizeof image->mem) return CHIP_ERR_IMAGE;
    memcpy(image->mem, bytes, size);
    image->version = 0;
    return hm_validate(image);
}

static int gb_start(ChipMachine* m, const ChipImage* image, int order, int row, int max_ticks,
                    int* seek_mode) {
    return hm_start_song(MACHINE(m), IMAGE(image), order, row, max_ticks, seek_mode);
}

static void gb_apply_image(ChipMachine* m, const ChipImage* image) { hm_apply_image(MACHINE(m), IMAGE(image)); }

static int gb_audition(ChipMachine* m, const ChipImage* image, int channel, int note, int instrument,
                       int fine) {
    return hm_start_audition(MACHINE(m), IMAGE(image), channel, note, instrument, fine);
}

/* hUGE instruments have no release phase: the note stops when the host stops ticking. */
static int gb_release(ChipMachine* m) {
    (void)m;
    return 0;
}

static int gb_mute(ChipMachine* m, int channel, int muted) { return hm_mute_channel(MACHINE(m), channel, muted); }

static void gb_position(const ChipMachine* m, int* order, int* row, int* tick) {
    hm_position((const HugeMachine*)m, order, row, tick);
}

static int gb_tick(ChipMachine* m, float* out) { return hm_tick(MACHINE(m), out); }

const ChipEngineOps huge_chip_ops = {
    .system_id = "gb-huge",
    .image_size = sizeof(((HugeImage*)0)->mem),
    .image_storage = sizeof(HugeImage),
    .create = gb_create,
    .destroy = gb_destroy,
    .load_image = gb_load_image,
    .start = gb_start,
    .apply_image = gb_apply_image,
    .audition = gb_audition,
    .release = gb_release,
    .mute = gb_mute,
    .position = gb_position,
    .tick = gb_tick,
};
