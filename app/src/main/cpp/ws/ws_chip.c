/* "ws-wonderswan" and "ws-wonderswan-color": Thor Tracker's own WonderSwan player on the sound unit (a port of ares's
 * APU), CPU-fed samples from the voice timer and, on the Color, Sound DMA and Hyper Voice, behind the chip engine
 * interface. One ops table per model: each loads only its own model's images (ws_load_image validates either).
 * docs/AUDIO_ENGINE.md ("WonderSwan") has the layout the lowering produces and the ops' behaviour. */
#include <string.h>

#include "chip_engine.h"
#include "ws_machine.h"

_Static_assert(CHIP_OK == WSM_OK && CHIP_ERR_IMAGE == WSM_ERR_IMAGE && CHIP_ERR_FAULT == WSM_ERR_FAULT,
               "error codes pass through unchanged");
_Static_assert(CHIP_SEEK_FROM_START == WSM_SEEK_FROM_START && CHIP_SEEK_FROM_ORDER == WSM_SEEK_FROM_ORDER,
               "seek modes pass through unchanged");
_Static_assert(WSM_MAX_TICK_FRAMES <= CHIP_MAX_TICK_FRAMES && WSM_MAX_SAMPLE_RATE == CHIP_MAX_SAMPLE_RATE,
               "host buffers fit a tick");

#define MACHINE(m) ((WsMachine*)(m))
#define IMAGE(i) ((const WsImage*)(i))

static int load_model(ChipImage* storage, const uint8_t* bytes, size_t size, int model) {
    WsImage* image = (WsImage*)storage;
    if (size != sizeof image->bytes) return CHIP_ERR_IMAGE;
    memcpy(image->bytes, bytes, size);
    if (ws_load_image(image)) return CHIP_ERR_IMAGE;
    if (image->info.model != model) {
        image->info.loaded = 0;
        image->info.problem = model == WS_MODEL_COLOR ? "a WonderSwan (original model) image, not a WonderSwan Color one"
                                                       : "a WonderSwan Color image, not an original WonderSwan one";
        image->info.problem_offset = WS_H_MODEL;
        return CHIP_ERR_IMAGE;
    }
    return CHIP_OK;
}

static ChipMachine* ws_create(uint32_t sample_rate) { return (ChipMachine*)wsm_create(sample_rate, WS_MODEL_MONO); }
static ChipMachine* wsc_create(uint32_t sample_rate) { return (ChipMachine*)wsm_create(sample_rate, WS_MODEL_COLOR); }

static void ws_destroy(ChipMachine* m) { wsm_destroy(MACHINE(m)); }

static int ws_load(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load_model(storage, bytes, size, WS_MODEL_MONO);
}

static int wsc_load(ChipImage* storage, const uint8_t* bytes, size_t size) {
    return load_model(storage, bytes, size, WS_MODEL_COLOR);
}

static int ws_start(ChipMachine* m, const ChipImage* image, int order, int row, int max_ticks, int* seek_mode) {
    return wsm_start(MACHINE(m), IMAGE(image), order, row, max_ticks, seek_mode);
}

static void ws_apply_image(ChipMachine* m, const ChipImage* image) { wsm_apply_image(MACHINE(m), IMAGE(image)); }

/* `note` = the absolute note (0 = C-0, A-4 = 57; playable 19..95), `instrument` = the cell's slot (1..64), `fine` =
 * 1/64 semitones (-128..127). */
static int ws_audition(ChipMachine* m, const ChipImage* image, int channel, int note, int instrument, int fine) {
    return wsm_audition(MACHINE(m), IMAGE(image), channel, note, instrument, fine);
}

static int ws_release(ChipMachine* m) { return wsm_release(MACHINE(m)); }

static int ws_mute(ChipMachine* m, int channel, int muted) { return wsm_mute(MACHINE(m), channel, muted); }

static void ws_position(const ChipMachine* m, int* order, int* row, int* tick) {
    wsm_position((const WsMachine*)m, order, row, tick);
}

static int ws_tick(ChipMachine* m, float* out) { return wsm_tick(MACHINE(m), out); }

const ChipEngineOps ws_chip_ops = {
    .system_id = "ws-wonderswan",
    .image_size = sizeof(((WsImage*)0)->bytes),
    .image_storage = sizeof(WsImage),
    .create = ws_create,
    .destroy = ws_destroy,
    .load_image = ws_load,
    .start = ws_start,
    .apply_image = ws_apply_image,
    .audition = ws_audition,
    .release = ws_release,
    .mute = ws_mute,
    .position = ws_position,
    .tick = ws_tick,
};

const ChipEngineOps wsc_chip_ops = {
    .system_id = "ws-wonderswan-color",
    .image_size = sizeof(((WsImage*)0)->bytes),
    .image_storage = sizeof(WsImage),
    .create = wsc_create,
    .destroy = ws_destroy,
    .load_image = wsc_load,
    .start = ws_start,
    .apply_image = ws_apply_image,
    .audition = ws_audition,
    .release = ws_release,
    .mute = ws_mute,
    .position = ws_position,
    .tick = ws_tick,
};
