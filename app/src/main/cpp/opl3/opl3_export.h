/* The OPL3 VGM export as the hosts call it: Android through JNI (audio_jni.cpp), the Deck through cinterop
 * (decknative). Self-contained (no engine types) so cinterop can read it. Control thread only: it allocates. */
#ifndef THORTRACKER_OPL3_EXPORT_H_
#define THORTRACKER_OPL3_EXPORT_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Renders a lowered "opl3-fm" image (exactly 65536 bytes) as a VGM 1.71 file (opl3_vgm.h, at most max_ticks
 * player ticks) with a GD3 1.00 tag: track = title, system "OPL3 (Thor Tracker)", author, date (VGM convention
 * "yyyy/mm/dd"; NULL or "" = today, local time), converter "Thor Tracker"; game and notes empty; UTF-8 in
 * (NULL = ""), UTF-16LE in the file.
 * Returns the file (free it with o3_export_free) and its size, or NULL with *problem = o3_load_image's reason
 * (or "image size", "out of memory"): a static string. */
uint8_t* o3_export_vgm(const uint8_t* image, size_t image_size, uint32_t max_ticks, const char* title,
                       const char* author, const char* date, size_t* size, const char** problem);
void o3_export_free(uint8_t* vgm);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_OPL3_EXPORT_H_ */
