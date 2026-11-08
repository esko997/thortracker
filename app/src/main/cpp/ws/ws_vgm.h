/* VGM 1.71 export of a WonderSwan image: the player, the voice timer and the sound unit's Sound DMA run headless and
 * their writes are logged for one WonderSwan chip (header 0xC0 = 3072000 Hz): ports $80-$9F as 0xBC (port - $80) val,
 * wave RAM as 0xC6 hi lo val, CH2 Sound DMA transfers as $89 writes at their cycle (block start + 122); $4A-$52 and
 * $64-$6B have no VGM form. Waits are in 44100 Hz samples: an event at cycle t is at sample floor(t 147 / 10240). The
 * loop needs no injected writes: the first pair of arrivals at (loop order, row 0, tick 0) with identical complete
 * state (player, timer, DMA unit) closes it, so every pass sends native playback's writes. A song with any note on
 * the Hyper Voice lane is refused (VGM's chip has none). Specified in docs/AUDIO_ENGINE.md ("WonderSwan: VGM export");
 * tools/audio/ws/ws_ref.py writes the same bytes. */
#ifndef THORTRACKER_WS_VGM_H_
#define THORTRACKER_WS_VGM_H_

#include <stddef.h>
#include <stdint.h>

#include "ws_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define WS_VGM_CLOCK 3072000u   /* header 0xC0 */
#define WS_VGM_MAX_ARRIVALS 64  /* loop candidates: the first 64 arrivals at the loop point */

typedef struct WsVgmInfo {
    uint32_t ticks;        /* player ticks rendered (Aa with a loop, else max_ticks) */
    uint32_t samples;      /* total 44.1 kHz samples */
    uint32_t loop_tick;    /* Ab (with a loop) */
    uint32_t loop_samples; /* 0 = no loop */
    uint32_t loop_offset;  /* absolute file offset of the loop point (header 0x1C = this - 0x1C), 0 = none */
    size_t size;           /* file size without a GD3 tag (the capacity needed) */
} WsVgmInfo;

/* Whether a Color image has a note (19..95) in any row of a Hyper Voice lane pattern (the VGM refusal). */
int ws_vgm_has_hyper_voice(const WsImage* image);

/* Renders a loaded image (no GD3 tag: ws_export_vgm adds one). `out` may be NULL to measure. Returns 0 when the file
 * was written (or only measured; info->size = its size), -1 when the image is not loaded, the work memory cannot be
 * allocated or `capacity` is too small (info->size still says what is needed), -2 when the song is refused (*problem
 * says why; `problem` may be NULL). */
int ws_vgm_render(const WsImage* image, uint32_t max_ticks, uint8_t* out, size_t capacity, WsVgmInfo* info,
                  const char** problem);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_WS_VGM_H_ */
