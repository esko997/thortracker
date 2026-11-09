/* VGM 1.71 export of an SAA1099 image: the player runs headless and its register writes are logged (header 0xC8 =
 * the SAA1099 clock, bit 30 set for the CMS's two chips; command 0xBD aa dd with aa bit 7 = chip B; waits in 44100 Hz
 * samples). Specified in docs/AUDIO_ENGINE.md ("SAA1099: VGM export"); tools/audio/saa/saa_ref.py writes the same
 * bytes. */
#ifndef THORTRACKER_SAA_VGM_H_
#define THORTRACKER_SAA_VGM_H_

#include <stddef.h>
#include <stdint.h>

#include "saa_image.h"
#include "saa_player.h"

#ifdef __cplusplus
extern "C" {
#endif

#define SA_VGM_DUAL 0x40000000u /* header 0xC8 bit 30: two chips (the CMS) */
#define SA_VGM_CHIP_B 0x80      /* 0xBD aa dd: aa bit 7 = the second chip */
#define SA_GD3_SYSTEM_SAM "SAM Coupé (Thor Tracker)" /* UTF-8 */
#define SA_GD3_SYSTEM_CMS "Creative Music System (Thor Tracker)"
#define SA_GD3_CONVERTER "Thor Tracker"

typedef struct SaVgmInfo {
    uint32_t ticks;        /* player ticks rendered */
    uint32_t samples;      /* total 44.1 kHz samples */
    uint32_t loop_samples; /* 0 = no loop (max_ticks reached first) */
    uint32_t loop_offset;  /* file offset of the loop point, 0 = none */
    size_t size;           /* file size, also when `capacity` was too small (the capacity needed) */
} SaVgmInfo;

/* Renders straight play from the reset batch: at each arrival at (loop order, row 0, tick 0), before its tick, the
 * player state is compared (sap_same_state) with the previous arrival's; when equal, the loop runs from that arrival
 * to here and the file ends; else this arrival is remembered. Without an equal pair within max_ticks there is no loop
 * and the file ends at max_ticks. `scratch` is two players of scratch space; `out` may be NULL with capacity 0 to
 * measure. Returns the file size, or 0 if `capacity` was too small (info->size = the size needed) or the image is not
 * loaded. */
size_t sa_vgm_render(const SaaImage* image, uint32_t max_ticks, SaPlayer scratch[2], uint8_t* out, size_t capacity,
                     SaVgmInfo* info);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_SAA_VGM_H_ */
