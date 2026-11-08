/* VGM 1.71 export of an OPL3 image: the player runs headless and its register writes are logged
 * (YMF262 at 14318180 Hz, commands 0x5E/0x5F, waits in 44100 Hz samples). Specified in
 * docs/AUDIO_ENGINE.md ("OPL3: VGM export"); tools/audio/opl3/opl3_ref.py writes the same bytes. */
#ifndef THORTRACKER_OPL3_VGM_H_
#define THORTRACKER_OPL3_VGM_H_

#include <stddef.h>
#include <stdint.h>

#include "opl3_image.h"
#include "opl3_player.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct O3VgmInfo {
    uint32_t ticks;        /* player ticks rendered */
    uint32_t samples;      /* total 44.1 kHz samples */
    uint32_t loop_samples; /* 0 = no loop (max_ticks reached first) */
    uint32_t loop_offset;  /* file offset of the loop point, 0 = none */
    size_t size;           /* file size, also when `capacity` was too small (the capacity needed) */
} O3VgmInfo;

/* Renders until straight play returns to (loop order, row 0) a second time, or max_ticks ticks. `player` is
 * scratch space; `out` may be NULL with capacity 0 to measure. Returns the file size, or 0 if `capacity` was too
 * small (info->size = the size needed) or the image is not loaded. */
size_t o3_vgm_render(const Opl3Image* image, uint32_t max_ticks, O3Player* player, uint8_t* out, size_t capacity,
                     O3VgmInfo* info);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_OPL3_VGM_H_ */
