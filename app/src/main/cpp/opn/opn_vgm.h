/* VGM 1.71 rendering of an OPN / OPNA image with the player (opn_player.h): exactly the player's own writes, timed at
 * 44100 Hz from the native sample count (16 x (256 - TB) native samples per tick at 55466.67 Hz, accumulated exactly).
 *
 * YM2203: header clock at $44 = 3993600, writes as command $55. YM2608: clock at $48 = 7987200, port 0 writes $56,
 * port 1 writes $57, and before the first write a data block ($67 $66 $81, "YM2608 DELTA-T ROM data": u32 memory size
 * 262144, u32 start 0, then the used ADPCM-B memory, info.adpcm_size bytes) when the song has samples. The init writes
 * (opnp_machine_reset, then opnp_reset's) come first, the key-off phase of each tick one 44.1 kHz sample before the
 * rest.
 *
 * Loop: nothing is ever written that native playback does not write. The player is run to its arrivals at (loop
 * order, row 0, tick 0) A1, A2, ...; the loop is the first pair Ab < Aa whose complete player states are identical
 * (opnp_same_state, confirmed exactly after a digest match), so from Ab on the writes of [Ab, Aa) repeat forever
 * write for write. The file holds the ticks before Aa with its loop offset at Ab: the first pass and every repeat send
 * the chip exactly the writes native playback sends (held notes across the wrap, the SSG envelope, CSM and ADPCM-B
 * included). Usually b = 2, a = 3: the file then holds the intro, one pass and the loop body. Only the waits of a
 * repeat reuse pass b's 44.1 kHz rounding (within one VGM sample of native timing). When no pair repeats within
 * max_ticks ticks and OPN_VGM_MAX_ARRIVALS arrivals (a song whose state keeps changing, e.g. a slide that never
 * settles), the file has no loop and holds max_ticks ticks. */
#ifndef THORTRACKER_OPN_VGM_H_
#define THORTRACKER_OPN_VGM_H_

#include <stddef.h>
#include <stdint.h>

#include "opn_image.h"
#include "opn_player.h"

#ifdef __cplusplus
extern "C" {
#endif

#define OPN_VGM_MAX_ARRIVALS 64

typedef struct OpnVgmInfo {
    size_t size;           /* bytes the file needs (also when `out` was too small) */
    uint32_t ticks;        /* player ticks rendered: the tick of arrival Aa, or max_ticks without a loop */
    uint32_t samples;      /* 44.1 kHz samples */
    uint32_t loop_tick;    /* the tick of arrival Ab (where the loop starts), 0 without a loop */
    uint32_t loop_offset;  /* file offset of the loop point, 0 without a loop */
    uint32_t loop_samples; /* samples in the loop */
} OpnVgmInfo;

/* Writes the VGM (header, data block, commands, end; no GD3 tag) into out[0 .. capacity - 1] and returns its size, or
 * 0 when `capacity` is too small or the image is not loaded (info->size still tells the size needed). `players` is
 * scratch storage for two players. Deterministic. */
size_t opn_vgm_render(const OpnImage* image, uint32_t max_ticks, OpnPlayer* players, uint8_t* out, size_t capacity,
                      OpnVgmInfo* info);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_OPN_VGM_H_ */
