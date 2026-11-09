/* The OPN / OPNA VGM export as the hosts call it: Android through JNI (audio_jni.cpp, ChipAudio.nativeOpnVgm), the
 * Deck through cinterop (decknative). Self-contained (no engine types) so cinterop can read it. Control thread only:
 * it allocates. */
#ifndef THORTRACKER_OPN_EXPORT_H_
#define THORTRACKER_OPN_EXPORT_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Renders a lowered "ym2203-opn" (65536 bytes) or "ym2608-opna" (327680 bytes) image - the chip is the image header's
 * and must match the size - as a VGM 1.71 file (opn_vgm.h, at most max_ticks player ticks) with a GD3 1.00 tag: track
 * = title, system "PC-88/98 OPN (Thor Tracker)" or "PC-88/98 OPNA (Thor Tracker)", author, date (VGM convention
 * "yyyy/mm/dd"; NULL or "" = today, local time), converter "Thor Tracker"; game and notes empty; UTF-8 in (NULL = ""),
 * UTF-16LE in the file. A YM2608 file carries the song's ADPCM-B memory as a VGM data block (type $81) and never the
 * rhythm ROM (VGM players supply their own).
 * Returns the file (free it with opn_export_free) and its size, or NULL with *problem = opn_load_image's reason (or
 * "image size", "out of memory"): a static string. */
uint8_t* opn_export_vgm(const uint8_t* image, size_t image_size, uint32_t max_ticks, const char* title,
                        const char* author, const char* date, size_t* size, const char** problem);
void opn_export_free(uint8_t* vgm);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_OPN_EXPORT_H_ */
