/* The Master System VGM export as the hosts call it: Android through JNI (audio_jni.cpp), the Deck through cinterop
 * (decknative). Self-contained (no engine types) so cinterop can read it. Control thread only: it allocates.
 * The Banjo song sources (WLA-DX .asm, SDAS .s) are written by the Kotlin side from the same lowering. */
#ifndef THORTRACKER_SMS_EXPORT_H_
#define THORTRACKER_SMS_EXPORT_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Renders a lowered "sms-banjo" image (exactly SMS_IMAGE_SIZE = 33792 bytes) as VGM 1.51: the Banjo driver's writes
 * on the Z80 (SN76489 0x50, YM2413 0x51 on FM layouts, Game Gear stereo 0x4F), waits in 44100 Hz samples, until
 * straight play returns to (loop order, row 0) a second time (the loop point is its first arrival) or max_ticks ticks
 * (then without a loop). GD3 1.00 tag: track = title, system ("Sega Master System", "Sega Game Gear", "Sega Master
 * System + FM Sound Unit"), author, date ("yyyy/mm/dd"; NULL or "" = today), converter "Thor Tracker"; UTF-8 in.
 * Returns the file (free it with sms_export_free) and its size, or NULL with *problem = the loader's reason (or
 * "image size", "the driver faulted", "out of memory"): a static string. */
uint8_t* sms_export_vgm(const uint8_t* image, size_t image_size, uint32_t max_ticks, const char* title, const char* author,
                        const char* date, size_t* size, const char** problem);
void sms_export_free(uint8_t* file);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_SMS_EXPORT_H_ */
