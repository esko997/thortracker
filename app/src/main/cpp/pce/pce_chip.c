/* "pce-huc6280": Thor Tracker's own PC Engine player on the HuC6280's PSG (Mednafen's, with the DDA streamed from the
 * CPU timer) behind the chip engine interface. Images are validated by pc_load_image (pce_image.h);
 * docs/AUDIO_ENGINE.md ("PC Engine") has the layout the lowering produces and the ops' behaviour. */
#include <string.h>

#include "chip_engine.h"
#include "pce_machine.h"

_Static_assert(CHIP_OK == PCM_OK && CHIP_ERR_IMAGE == PCM_ERR_IMAGE && CHIP_ERR_FAULT == PCM_ERR_FAULT,
               "error codes pass through unchanged");
_Static_assert(CHIP_SEEK_FROM_START == PCM_SEEK_FROM_START && CHIP_SEEK_FROM_ORDER == PCM_SEEK_FROM_ORDER,
               "seek modes pass through unchanged");
_Static_assert(PCM_MAX_TICK_FRAMES <= CHIP_MAX_TICK_FRAMES && PCM_MAX_SAMPLE_RATE == CHIP_MAX_SAMPLE_RATE,
               "host buffers fit a tick");

#define MACHINE(m) ((PceMachine*)(m))
#define IMAGE(i) ((const PceImage*)(i))

static ChipMachine* pce_create(uint32_t sample_rate) { return (ChipMachine*)pcm_create(sample_rate); }

static void pce_destroy(ChipMachine* m) { pcm_destroy(MACHINE(m)); }

static int pce_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    PceImage* image = (PceImage*)storage;
    if (size != sizeof image->bytes) return CHIP_ERR_IMAGE;
    memcpy(image->bytes, bytes, size);
    return pc_load_image(image) ? CHIP_ERR_IMAGE : CHIP_OK;
}

static int pce_start(ChipMachine* m, const ChipImage* image, int order, int row, int max_ticks, int* seek_mode) {
    return pcm_start(MACHINE(m), IMAGE(image), order, row, max_ticks, seek_mode);
}

static void pce_apply_image(ChipMachine* m, const ChipImage* image) { pcm_apply_image(MACHINE(m), IMAGE(image)); }

/* `note` = the absolute note (0 = C-0, A-4 = 57; playable 9..95), `instrument` = the cell's slot (1..64), `fine` =
 * 1/64 semitones (-128..127). */
static int pce_audition(ChipMachine* m, const ChipImage* image, int channel, int note, int instrument, int fine) {
    return pcm_audition(MACHINE(m), IMAGE(image), channel, note, instrument, fine);
}

static int pce_release(ChipMachine* m) { return pcm_release(MACHINE(m)); }

static int pce_mute(ChipMachine* m, int channel, int muted) { return pcm_mute(MACHINE(m), channel, muted); }

static void pce_position(const ChipMachine* m, int* order, int* row, int* tick) {
    pcm_position((const PceMachine*)m, order, row, tick);
}

static int pce_tick(ChipMachine* m, float* out) { return pcm_tick(MACHINE(m), out); }

const ChipEngineOps pce_chip_ops = {
    .system_id = "pce-huc6280",
    .image_size = sizeof(((PceImage*)0)->bytes),
    .image_storage = sizeof(PceImage),
    .create = pce_create,
    .destroy = pce_destroy,
    .load_image = pce_load_image,
    .start = pce_start,
    .apply_image = pce_apply_image,
    .audition = pce_audition,
    .release = pce_release,
    .mute = pce_mute,
    .position = pce_position,
    .tick = pce_tick,
};
