/* The WonderSwan VGM export as the hosts call it: Android through JNI (audio_jni.cpp, ChipAudio.nativeWsVgm), the
 * Deck through cinterop (decknative, DeckWsVgm). Self-contained (no engine types) so cinterop can read it. Control
 * thread only: it allocates. */
#ifndef THORTRACKER_WS_EXPORT_H_
#define THORTRACKER_WS_EXPORT_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Renders a lowered "ws-wonderswan" or "ws-wonderswan-color" image (exactly 131072 bytes) as a VGM 1.71 file
 * (ws_vgm.h, at most max_ticks player ticks) with a GD3 1.00 tag: track = title, system "WonderSwan (Thor Tracker)"
 * or "WonderSwan Color (Thor Tracker)", author, date (VGM convention "yyyy/mm/dd"; NULL or "" = today, local time),
 * converter "Thor Tracker"; game and notes empty; UTF-8 in (NULL = ""), UTF-16LE in the file.
 * Returns the file (free it with ws_export_free) and its size, or NULL with *problem = ws_load_image's reason, the
 * Hyper Voice refusal ("VGM's WonderSwan chip has no Hyper Voice: export WAV"), "image size" or "out of memory": a
 * static string. */
uint8_t* ws_export_vgm(const uint8_t* image, size_t size, uint32_t max_ticks, const char* title, const char* author,
                       const char* date, size_t* out_size, const char** problem);
void ws_export_free(uint8_t* vgm);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_WS_EXPORT_H_ */
