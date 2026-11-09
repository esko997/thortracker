/* "sms-banjo": the Banjo sound driver on the Master System / Game Gear machine (sms_machine.h) behind the chip engine
 * interface, for every Master System layout (SN76489; Game Gear stereo; SN76489 + YM2413; YM2413 rhythm mode): the
 * image says which. Images are validated by sms_load_image (sms_image.h); docs/AUDIO_ENGINE.md ("Master System")
 * has the layout the lowering produces and the ops' behaviour. */
#include <string.h>

#include "chip_engine.h"
#include "sms_machine.h"

_Static_assert(CHIP_OK == SMSM_OK && CHIP_ERR_IMAGE == SMSM_ERR_IMAGE && CHIP_ERR_FAULT == SMSM_ERR_FAULT,
               "error codes pass through unchanged");
_Static_assert(CHIP_SEEK_FROM_START == SMSM_SEEK_FROM_START && CHIP_SEEK_FROM_ORDER == SMSM_SEEK_FROM_ORDER,
               "seek modes pass through unchanged");
_Static_assert(SMSM_MAX_TICK_FRAMES <= CHIP_MAX_TICK_FRAMES && SMSM_MAX_SAMPLE_RATE == CHIP_MAX_SAMPLE_RATE,
               "host buffers fit a tick");

#define MACHINE(m) ((SmsMachine*)(m))
#define IMAGE(i) ((const SmsImage*)(i))

static ChipMachine* sms_create(uint32_t sample_rate) { return (ChipMachine*)smsm_create(sample_rate); }

static void sms_destroy(ChipMachine* m) { smsm_destroy(MACHINE(m)); }

static int sms_load(ChipImage* storage, const uint8_t* bytes, size_t size) {
    SmsImage* image = (SmsImage*)storage;
    if (size != sizeof image->bytes) return CHIP_ERR_IMAGE;
    memcpy(image->bytes, bytes, size);
    return sms_load_image(image) ? CHIP_ERR_IMAGE : CHIP_OK;
}

static int sms_start(ChipMachine* m, const ChipImage* image, int order, int row, int max_ticks, int* seek_mode) {
    return smsm_start(MACHINE(m), IMAGE(image), order, row, max_ticks, seek_mode);
}

static void sms_apply_image(ChipMachine* m, const ChipImage* image) { smsm_apply_image(MACHINE(m), IMAGE(image)); }

/* `note` = the driver's note byte (SmsSystem's engine.note: SN channels absolute - 24, FM and rhythm absolute),
 * `instrument` = the cell's slot (1-based), `fine` = 0 (Banjo has no fine pitch). */
static int sms_audition(ChipMachine* m, const ChipImage* image, int channel, int note, int instrument, int fine) {
    return smsm_audition(MACHINE(m), IMAGE(image), channel, note, instrument, fine);
}

static int sms_release(ChipMachine* m) { return smsm_release(MACHINE(m)); }

static int sms_mute(ChipMachine* m, int channel, int muted) { return smsm_mute(MACHINE(m), channel, muted); }

static void sms_position(const ChipMachine* m, int* order, int* row, int* tick) {
    smsm_position((const SmsMachine*)m, order, row, tick);
}

static int sms_tick(ChipMachine* m, float* out) { return smsm_tick(MACHINE(m), out); }

const ChipEngineOps sms_chip_ops = {
    .system_id = "sms-banjo",
    .image_size = sizeof(((SmsImage*)0)->bytes),
    .image_storage = sizeof(SmsImage),
    .create = sms_create,
    .destroy = sms_destroy,
    .load_image = sms_load,
    .start = sms_start,
    .apply_image = sms_apply_image,
    .audition = sms_audition,
    .release = sms_release,
    .mute = sms_mute,
    .position = sms_position,
    .tick = sms_tick,
};
