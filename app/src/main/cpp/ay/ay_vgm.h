/* VGM 1.71 export of an AY image: the player runs headless and its register writes are logged for one AY8910-family
 * chip (command 0xA0, the variant's chip type and clock, legacy single mixed output), waits in 44100 Hz samples.
 * Specified in docs/AUDIO_ENGINE.md ("AY-3-8910 / YM2149: VGM export"); tools/audio/ay/ay_ref.py writes the same
 * bytes. */
#ifndef THORTRACKER_AY_VGM_H_
#define THORTRACKER_AY_VGM_H_

#include <stddef.h>
#include <stdint.h>

#include "ay_image.h"
#include "ay_player.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct AyVgmInfo {
    uint32_t ticks;        /* player ticks rendered */
    uint32_t samples;      /* total 44.1 kHz samples */
    uint32_t loop_samples; /* 0 = no loop (max_ticks reached first) */
    uint32_t loop_offset;  /* file offset of the loop point, 0 = none */
    size_t size;           /* file size, also when `capacity` was too small (the capacity needed) */
} AyVgmInfo;

/* Renders the image's own variant until straight play returns to (loop order, row 0) a second time, or max_ticks
 * ticks. `player` is scratch space; `out` may be NULL with capacity 0 to measure. Returns the file size, or 0 if
 * `capacity` was too small (info->size = the size needed) or the image is not loaded. */
size_t ay_vgm_render(const AyImage* image, uint32_t max_ticks, AyPlayer* player, uint8_t* out, size_t capacity,
                     AyVgmInfo* info);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_AY_VGM_H_ */
