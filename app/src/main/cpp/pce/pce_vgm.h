/* VGM 1.71 export of a PC Engine image: the player and the DDA timer run headless and their register writes are
 * logged (one HuC6280 at 3579545 Hz, command 0xB9, waits in 44100 Hz samples: an event at CPU cycle t is at sample
 * floor(t 4851 / 787500)). Specified in docs/AUDIO_ENGINE.md ("PC Engine: VGM export"); tools/audio/pce/pce_ref.py
 * writes the same bytes. */
#ifndef THORTRACKER_PCE_VGM_H_
#define THORTRACKER_PCE_VGM_H_

#include <stddef.h>
#include <stdint.h>

#include "pce_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define PC_VGM_CLOCK 3579545u /* header 0xA4: the HuC6280 PSG clock as VGM players expect it */

typedef struct PcVgmInfo {
    uint32_t ticks;        /* player ticks rendered */
    uint32_t samples;      /* total 44.1 kHz samples */
    uint32_t loop_samples; /* 0 = no loop (max_ticks reached first) */
    uint32_t loop_offset;  /* absolute file offset of the loop point (header 0x1C = this - 0x1C), 0 = none */
    size_t size;           /* file size (the capacity needed) */
} PcVgmInfo;

/* Renders until straight play arrives at (loop order, row 0, tick 0) a second time, or max_ticks ticks. `out` may be
 * NULL to measure. *size (and info->size) = the file size (no GD3 tag: pc_export_vgm adds one). Returns 0 when the file
 * was written (or only measured), -1 when the image is not loaded or `capacity` is too small. */
int pc_vgm_render(const PceImage* image, int max_ticks, uint8_t* out, size_t capacity, size_t* size, PcVgmInfo* info);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_PCE_VGM_H_ */
