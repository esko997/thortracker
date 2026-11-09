/* XGM2 encoding of the player's batches (SGDK's XGM2 format, bin/xgm2.txt of the pinned SGDK): one tick becomes one
 * frame of the FM stream and one frame of the PSG stream, which the machine streams to SGDK's XGM2 Z80 driver while it
 * plays and the export writes as .xgm / .xgc. Specified in docs/AUDIO_ENGINE.md ("YM2612: XGM2 encoding"); the host
 * test executes the frames on the real driver and compares its chip writes with the player's state.
 *
 * FM frame, in this order: the batch's key-offs ($4x); the FM 3 mode ($FA/$FB) and LFO ($F9) when written; per FM
 * channel its registers - a load-instrument command ($2x, 30 bytes: what the driver's TL/SL-RR attenuation state
 * needs before an algorithm write) when the batch writes the algorithm, when that is shorter, or in a full frame;
 * else TL commands ($9x) and general register writes ($Ex) - then FM 3's special frequencies ($38-$3A) and the
 * channel frequency ($3x, with the key-on flag) or a bare key-on ($4x); partial or late key writes ($F8); the PCM
 * commands ($1x: channel k, low priority, half rate per variant; id 0 stops). PSG frame: tones ($2x), noise control
 * ($10 direct write), attenuations ($8x-$Bx). Every value is the player's register shadow after the batch, so a
 * frame leaves the chips exactly as the player's writes would. A full frame (the stream start and every arrival at
 * the loop point) writes every register; its size bounds every frame below the driver's 256-byte command buffer.
 *
 * Frames end with a wait ($0x / $0F xx FM, $0x / $0E xx PSG) or, at a stream's end, its loop command ($FF / $0F
 * dddddd, which also ends the frame). Packed streams are literal-only LZ blocks of one frame each (header LLL00000 or,
 * at a 256-byte page end, LLL00001; end byte $00), so no block crosses a 32 KiB bank and no match reaches behind a
 * loop. No allocation. */
#ifndef THORTRACKER_YM_XGM_H_
#define THORTRACKER_YM_XGM_H_

#include <stddef.h>
#include <stdint.h>

#include "ym_image.h"
#include "ym_player.h"

#ifdef __cplusplus
extern "C" {
#endif

#define YMX_BUFFER 256          /* the driver's FM and PSG command buffers (circular) */
#define YMX_FRAME_MAX 255       /* unpacked bytes of one frame with its end (a full buffer would read as empty) */
#define YMX_END_MAX 4           /* bytes of a frame end: a wait or a loop command */
#define YMX_CMDS_MAX (YMX_FRAME_MAX - YMX_END_MAX)
#define YMX_PACKED_MAX 320      /* packed bytes of one frame block (literal headers, page splits, end byte) */
#define YMX_FM_WAIT_MAX 271     /* frames one FM wait command spans */
#define YMX_PSG_WAIT_MAX 270
#define YMX_NO_LOOP (-1)

/* One tick's commands, without their frame end. */
typedef struct YmxFrame {
    uint8_t fm[YMX_CMDS_MAX];
    int fm_size;
    uint8_t psg[YMX_CMDS_MAX];
    int psg_size;
} YmxFrame;

/* Encodes one tick: `w` (n records) = the tick's writes in order (any prefix batch first, then the player's batch),
 * `p` = the player after the tick (register shadow), `pcm` = the PCM commands (YMP_PCM_NONE / YMP_PCM_STOP / variant
 * of `image`), `full` = every register (stream start, loop point). Returns 0 or -1 (a frame above YMX_CMDS_MAX, which
 * the player's batches cannot make). */
int ymx_encode(const YmImage* image, const YmPlayer* p, const YmWrite* w, int n, const int* pcm, int full, YmxFrame* out);
/* A frame end: a wait of `frames` (1..YMX_FM_WAIT_MAX / YMX_PSG_WAIT_MAX) in the FM (psg 0) or PSG (psg 1) stream.
 * Returns its bytes (1 or 2). */
int ymx_wait(uint8_t* out, int psg, int frames);
/* A frame end: the stream's loop command to `offset` from the stream start (YMX_NO_LOOP: the end). Returns 4. */
int ymx_loop(uint8_t* out, int psg, int32_t offset);
/* One packed block of `n` (1..YMX_FRAME_MAX) bytes at stream offset `at` (streams start 256-byte aligned). Returns
 * its bytes (<= YMX_PACKED_MAX). */
size_t ymx_pack(const uint8_t* in, int n, uint32_t at, uint8_t* out);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_YM_XGM_H_ */
