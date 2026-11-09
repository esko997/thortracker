/* Exports of a YM2612 image. VGM 1.71: the chip writes SGDK's XGM2 driver makes while it plays the song in the machine
 * (ym_machine.h) - YM2612 (commands 0x52/0x53) and SN76489 (0x50) at their Z80-cycle times in 44100 Hz samples, the
 * driver's PCM mix as the DAC bytes it writes (one YM2612 PCM data block, 0x8n from it while $2B enables the DAC, 0xE0
 * at the loop point), $27 only where its FM 3 mode bits change (the timer reloads are the driver's), timer A's period
 * left out. XGM2 1.0 (.xgm, unpacked, GD3) and its compiled form (.xgc, packed, as XGM2_play() takes it): the player's
 * frames (ym_xgm.h) with the image's compiled sample bank and sample table, waits merged across empty frames, both
 * streams looping to the loop point's full frame. Specified in docs/AUDIO_ENGINE.md ("YM2612: exports"). Control
 * thread only: these allocate. */
#ifndef THORTRACKER_YM_VGM_H_
#define THORTRACKER_YM_VGM_H_

#include <stddef.h>
#include <stdint.h>

#include "ym_image.h"

#ifdef __cplusplus
extern "C" {
#endif

enum { YM_FORMAT_VGM = 0, YM_FORMAT_XGM = 1, YM_FORMAT_XGC = 2 };

#define YM_XGM2_VERSION 0x10
#define YM_XGM2_HEADER 0x104 /* "XGM2", version, flags, SLEN, FMLEN, PSGLEN, 124-entry sample table */
#define YM_XGC_HEADER 0x100  /* the same without "XGM2" */

typedef struct YmVgmInfo {
    uint32_t ticks;        /* player ticks rendered */
    uint32_t samples;      /* total 44.1 kHz samples (VGM) */
    uint32_t loop_samples; /* 0 = no loop (max_ticks reached first) */
    uint32_t loop_offset;  /* file offset of the loop point (VGM), loop offset in the FM stream (XGM, XGC); 0 = none */
    uint32_t psg_loop_offset; /* loop offset in the PSG stream (XGM, XGC) */
    uint32_t fm_size, psg_size; /* stream bytes before their 256-byte padding (XGM, XGC) */
    uint32_t dac_bytes;    /* bytes of the VGM's PCM data block */
    int looped;
    size_t size;           /* file size */
} YmVgmInfo;

/* Renders until straight play returns to (loop order, row 0) a second time, or max_ticks ticks, in `format`. Returns
 * the file without a GD3 tag (free() it; ym_export appends the tag an .xgm's flag bit 2 announces) and its info, or
 * NULL with *problem a static string ("frame too large", "out of memory", "driver fault", "format"). */
uint8_t* ym_render(const YmImage* image, int format, uint32_t max_ticks, YmVgmInfo* info, const char** problem);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_YM_VGM_H_ */
