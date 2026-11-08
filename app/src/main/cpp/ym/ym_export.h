/* The YM2612 exports as the hosts call them: Android through JNI (audio_jni.cpp), the Deck through cinterop
 * (decknative). Self-contained (no engine types) so cinterop can read it. Control thread only: it allocates. */
#ifndef THORTRACKER_YM_EXPORT_H_
#define THORTRACKER_YM_EXPORT_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define YM_EXPORT_VGM 0 /* .vgm: VGM 1.71, YM2612 + SN76489, with a GD3 tag */
#define YM_EXPORT_XGM 1 /* .xgm: XGM 1.01 (SGDK rescomp's XGM resource), with the same GD3 tag */
#define YM_EXPORT_XGC 2 /* .bin: the compiled XGM XGM_startPlay() plays (what rescomp makes of the .xgm), no tag */

/* Renders a lowered "ym2612-fm" image (exactly 65536 bytes) in `format` (at most max_ticks player ticks). The GD3
 * 1.00 tag: track = title, system "Mega Drive (Thor Tracker)", author, date (VGM convention "yyyy/mm/dd"; NULL or
 * "" = today, local time), converter "Thor Tracker"; game and notes empty; UTF-8 in (NULL = ""), UTF-16LE in the
 * file. Returns the file (free it with ym_export_free) and its size, or NULL with *problem = ym_load_image's reason
 * (or "image size", "format", "out of memory"): a static string. */
uint8_t* ym_export(const uint8_t* image, size_t image_size, int format, uint32_t max_ticks, const char* title,
                   const char* author, const char* date, size_t* size, const char** problem);
void ym_export_free(uint8_t* file);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_YM_EXPORT_H_ */
