/* VGM 1.71 export of a Virtual Boy VSU image: the player and the VSU core run headless and the player's writes are
 * logged for one VSU (header 0xC4 clock 5000000, commands 0xC7 mm ll dd with the register's word index MSB first,
 * each tick exactly 882 samples: 0x63). The data starts with the reset batch. The loop needs no synthetic writes: at
 * every arrival at (loop order, row 0, tick 0) the renderer captures the player's loop state (vbp_state_equal) and
 * the chip's held state (vsu_held_equal) and compares them with the previous arrival's; the loop point is the first
 * arrival k whose state equals arrival k + 1's, and the file ends at arrival k + 1. Without such a pair within
 * max_ticks the file ends there without a loop. Specified in docs/AUDIO_ENGINE.md ("Virtual Boy VSU: VGM export");
 * tools/audio/vsu/vsu_ref.py writes the same bytes. */
#ifndef THORTRACKER_VSU_VGM_H_
#define THORTRACKER_VSU_VGM_H_

#include <stddef.h>
#include <stdint.h>

#include "vsu_chip.h"
#include "vsu_image.h"
#include "vsu_player.h"

#ifdef __cplusplus
extern "C" {
#endif

#define VB_VGM_CLOCK 5000000u
#define VB_VGM_TICK_SAMPLES 882u /* 44100 / 50 */
#define VB_VGM_HEADER 0x100

typedef struct VbVgmInfo {
    uint32_t ticks;        /* player ticks rendered */
    uint32_t samples;      /* total 44.1 kHz samples */
    uint32_t loop_samples; /* 0 = no loop */
    uint32_t loop_offset;  /* file offset of the loop point, 0 = none */
    uint32_t loop_tick;    /* the tick the loop point starts (arrival k), 0 = none */
    size_t size;           /* file size, also when `capacity` was too small (the capacity needed) */
} VbVgmInfo;

/* Scratch space of a render: the player and chip, and the previous loop arrival's state. */
typedef struct VbVgmScratch {
    VbPlayer player;
    VsuChip chip;
    VbPlayer arrival_player;
    VsuChip arrival_chip;
} VbVgmScratch;

/* Renders the image for at most max_ticks ticks (above). `out` may be NULL with capacity 0 to measure. Returns the file
 * size, or 0 if `capacity` was too small (info->size = the size needed) or the image is not loaded. No GD3 tag
 * (vsu_export.h adds one). */
size_t vb_vgm_render(const VsuImage* image, uint32_t max_ticks, VbVgmScratch* scratch, uint8_t* out, size_t capacity,
                     VbVgmInfo* info);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_VSU_VGM_H_ */
