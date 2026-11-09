/* VGM 1.71 export of a POKEY image: the player runs headless with the chip's timers (for the interrupts' cycles) and
 * its register writes are logged for one POKEY (header 0xB0 clock, commands 0xBB reg val, waits in 44100 Hz samples
 * from the writes' machine cycles). Specified in docs/AUDIO_ENGINE.md ("POKEY: VGM export");
 * tools/audio/pokey/pokey_ref.py writes the same bytes. */
#ifndef THORTRACKER_POKEY_VGM_H_
#define THORTRACKER_POKEY_VGM_H_

#include <stddef.h>
#include <stdint.h>

#include "pokey_chip.h"
#include "pokey_image.h"
#include "pokey_player.h"

#ifdef __cplusplus
extern "C" {
#endif

#define PK_VGM_CLOCK_NTSC 1789773u /* the format's integer Hz */
#define PK_VGM_CLOCK_PAL 1773448u

typedef struct PkVgmInfo {
    uint32_t ticks;        /* player ticks rendered */
    uint32_t samples;      /* total 44.1 kHz samples */
    uint32_t loop_samples; /* 0 = no loop (max_ticks reached first) */
    uint32_t loop_offset;  /* file offset of the loop point, 0 = none */
    size_t size;           /* file size, also when `capacity` was too small (the capacity needed) */
} PkVgmInfo;

/* Scratch space of a render: the player and the chip (pk_init it once before the first render; renders reset it). */
typedef struct PkVgmScratch {
    PkPlayer player;
    PokeyChip chip;
} PkVgmScratch;

/* 44.1 kHz samples before machine cycle `cycle`: floor(cycle * 44100 / clock), exact integers. */
uint64_t pk_vgm_samples(int machine, uint64_t cycle);

/* Renders until straight play arrives at (loop order, row 0, tick 0) a second time, or max_ticks ticks. `out` may be
 * NULL with capacity 0 to measure. Returns the file size, or 0 if `capacity` was too small (info->size = the size
 * needed) or the image is not loaded. No GD3 tag (pokey_export.h adds one). */
size_t pk_vgm_render(const PokeyImage* image, uint32_t max_ticks, PkVgmScratch* scratch, uint8_t* out, size_t capacity,
                     PkVgmInfo* info);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_POKEY_VGM_H_ */
