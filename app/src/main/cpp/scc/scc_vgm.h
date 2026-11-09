/* VGM 1.71 export of an SCC image: the player runs headless and its register writes are logged (K051649 at the VGM
 * convention's 1789773 Hz = half the MSX's 3579545, bit 31 set for the K052539; command 0xD2 pp aa dd; waits in 44100 Hz
 * samples). Specified in docs/AUDIO_ENGINE.md ("SCC and SCC+: VGM export"); tools/audio/scc/scc_ref.py writes the same
 * bytes. */
#ifndef THORTRACKER_SCC_VGM_H_
#define THORTRACKER_SCC_VGM_H_

#include <stddef.h>
#include <stdint.h>

#include "scc_image.h"
#include "scc_player.h"

#ifdef __cplusplus
extern "C" {
#endif

#define SC_VGM_CLOCK 1789773u        /* header 0x9C: VGM players play f = clock / (16 (P + 1)) */
#define SC_VGM_CLOCK_PLUS 0x80000000u /* bit 31: K052539 */
#define SC_GD3_SYSTEM_SCC "MSX SCC (Thor Tracker)"
#define SC_GD3_SYSTEM_PLUS "MSX SCC+ (Thor Tracker)"
#define SC_GD3_CONVERTER "Thor Tracker"

typedef struct ScVgmInfo {
    uint32_t ticks;        /* player ticks rendered */
    uint32_t samples;      /* total 44.1 kHz samples */
    uint32_t loop_samples; /* 0 = no loop (max_ticks reached first) */
    uint32_t loop_offset;  /* file offset of the loop point, 0 = none */
    size_t size;           /* file size, also when `capacity` was too small (the capacity needed) */
} ScVgmInfo;

/* Renders until straight play returns to (loop order, row 0) a second time, or max_ticks ticks. `player` is scratch
 * space; `out` may be NULL with capacity 0 to measure. Returns the file size, or 0 if `capacity` was too small
 * (info->size = the size needed) or the image is not loaded. */
size_t sc_vgm_render(const SccImage* image, uint32_t max_ticks, ScPlayer* player, uint8_t* out, size_t capacity,
                     ScVgmInfo* info);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_SCC_VGM_H_ */
