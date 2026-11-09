/* The AY-3-8910 / YM2149 VGM export as the hosts call it: Android through JNI (audio_jni.cpp), the Deck through
 * cinterop (decknative). Self-contained (no engine types) so cinterop can read it. Control thread only: it
 * allocates. */
#ifndef THORTRACKER_AY_EXPORT_H_
#define THORTRACKER_AY_EXPORT_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Renders a lowered AY image (exactly 65536 bytes) of `variant` (0 ZX Spectrum 128, 1 MSX, 2 Atari ST, 3 Amstrad CPC;
 * the image's variant byte must match) as a VGM 1.71 file (ay_vgm.h, at most max_ticks player ticks; negative = 0) with
 * a GD3 1.00 tag: track = title, system "ZX Spectrum 128 (Thor Tracker)", "MSX (Thor Tracker)", "Atari ST (Thor
 * Tracker)" or "Amstrad CPC (Thor Tracker)",
 * author, date (VGM convention "yyyy/mm/dd"; NULL or "" = today, local time), converter "Thor Tracker"; game and
 * notes empty; UTF-8 in (NULL = ""), UTF-16LE in the file.
 * Returns the file (free it with ay_export_free) and its size, or NULL with *problem = ay_load_image's reason (or
 * "variant", "image size", "out of memory"): a static string. */
uint8_t* ay_export_vgm(int variant, const uint8_t* image, size_t image_size, int max_ticks, const char* title,
                       const char* author, const char* date, size_t* out_size, const char** problem);
void ay_export_free(uint8_t* file);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_AY_EXPORT_H_ */
