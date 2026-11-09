/* VGM 1.71 export of an OPM image: the player runs headless and its register writes are logged (one YM2151 at
 * 4000000 Hz, command 0x54, waits in 44100 Hz samples). Specified in docs/AUDIO_ENGINE.md ("OPM: VGM export");
 * tools/audio/opm/opm_ref.py writes the same bytes. */
#ifndef THORTRACKER_OPM_VGM_H_
#define THORTRACKER_OPM_VGM_H_

#include <stddef.h>
#include <stdint.h>

#include "opm_image.h"
#include "opm_player.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct OmVgmInfo {
    uint32_t ticks;        /* player ticks rendered */
    uint32_t samples;      /* total 44.1 kHz samples */
    uint32_t loop_samples; /* 0 = no loop (max_ticks reached first) */
    uint32_t loop_offset;  /* file offset of the loop point, 0 = none */
    size_t size;           /* file size, also when `capacity` was too small (the capacity needed) */
} OmVgmInfo;

/* Renders until straight play returns to (loop order, row 0) a second time, or max_ticks ticks. `player` is
 * scratch space; `out` may be NULL with capacity 0 to measure. Returns the file size, or 0 if `capacity` was too
 * small (info->size = the size needed) or the image is not loaded. No GD3 tag (om_export_vgm adds one). */
size_t om_vgm_render(const OpmImage* image, uint32_t max_ticks, OmPlayer* player, uint8_t* out, size_t capacity,
                     OmVgmInfo* info);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_OPM_VGM_H_ */
