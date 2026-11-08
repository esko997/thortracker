/* Exports of a YM2612 image: VGM 1.71 (the player's writes for one YM2612 and one SN76489: commands 0x52/0x53/0x50,
 * waits in 44100 Hz samples) and, from the same frames, the XGM 1.01 file and the compiled XGC that xgmtool makes of
 * that VGM (ym_xgm.h). The player runs headless. Specified in docs/AUDIO_ENGINE.md ("YM2612: VGM export", "XGM /
 * XGC encoding"); tools/audio/ym/ym_ref.py writes the same VGM bytes, xgmtool the same XGM/XGC bytes. */
#ifndef THORTRACKER_YM_VGM_H_
#define THORTRACKER_YM_VGM_H_

#include <stddef.h>
#include <stdint.h>

#include "ym_image.h"
#include "ym_player.h"
#include "ym_xgm.h"

#ifdef __cplusplus
extern "C" {
#endif

enum { YM_FORMAT_VGM = 0, YM_FORMAT_XGM = 1, YM_FORMAT_XGC = 2 };

typedef struct YmVgmInfo {
    uint32_t ticks;        /* player ticks rendered */
    uint32_t samples;      /* total 44.1 kHz samples (VGM) */
    uint32_t loop_samples; /* 0 = no loop (max_ticks reached first) */
    uint32_t loop_offset;  /* file offset of the loop point (VGM), loop offset in the music data (XGM, XGC); 0 = none */
    int looped;
    size_t size;           /* file size, also when `capacity` was too small (the capacity needed) */
} YmVgmInfo;

/* Renders until straight play returns to (loop order, row 0) a second time, or max_ticks ticks, in `format`.
 * `player` and `encoder` (XGM/XGC only; may be NULL for VGM) are scratch space; `out` may be NULL with capacity 0 to
 * measure. Returns the file size, or 0 if `capacity` was too small (info->size = the size needed), the encoder
 * overflowed or the image is not loaded. No GD3 tag (ym_export adds one). */
size_t ym_render(const YmImage* image, int format, uint32_t max_ticks, YmPlayer* player, YmXgmEncoder* encoder,
                 uint8_t* out, size_t capacity, YmVgmInfo* info);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_YM_VGM_H_ */
