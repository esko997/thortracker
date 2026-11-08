/* VGM 1.71 rendering of an OPN / OPNA image with the player (opn_player.h): the player's own writes, timed at 44100 Hz
 * from the native sample count (16 x (256 - TB) native samples per tick at 55466.67 Hz, accumulated exactly).
 *
 * YM2203: header clock at $44 = 3993600, writes as command $55. YM2608: clock at $48 = 7987200, port 0 writes $56,
 * port 1 writes $57, and before the first write a data block ($67 $66 $81, "YM2608 DELTA-T ROM data": u32 memory size
 * 262144, u32 start 0, then the used ADPCM-B memory, info.adpcm_size bytes) when the song has samples. The init writes
 * (opnp_machine_reset, then opnp_reset's) come first, the key-off phase of each tick one 44.1 kHz sample before the
 * rest. Rendering stops at the second arrival at (loop order, row 0, tick 0) - the loop point, where the file's loop
 * offset is and where the player's full register state is written (opnp_dump, opnp_dump_adpcm) so every pass starts
 * from the same registers - or after max_ticks ticks (then without a loop). */
#ifndef THORTRACKER_OPN_VGM_H_
#define THORTRACKER_OPN_VGM_H_

#include <stddef.h>
#include <stdint.h>

#include "opn_image.h"
#include "opn_player.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct OpnVgmInfo {
    size_t size;           /* bytes the file needs (also when `out` was too small) */
    uint32_t ticks;        /* player ticks rendered */
    uint32_t samples;      /* 44.1 kHz samples */
    uint32_t loop_offset;  /* file offset of the loop point, 0 without a loop */
    uint32_t loop_samples; /* samples in the loop */
} OpnVgmInfo;

/* Writes the VGM (header, data block, commands, end; no GD3 tag) into out[0 .. capacity - 1] and returns its size, or
 * 0 when `capacity` is too small or the image is not loaded (info->size still tells the size needed). `player` is
 * scratch storage. Deterministic. */
size_t opn_vgm_render(const OpnImage* image, uint32_t max_ticks, OpnPlayer* player, uint8_t* out, size_t capacity,
                      OpnVgmInfo* info);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_OPN_VGM_H_ */
