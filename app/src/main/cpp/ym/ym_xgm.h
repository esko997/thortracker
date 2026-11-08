/* XGM / XGC encoding of the player's frames: xgmtool 1.76's VGM -> XGM -> XGC conversion (SGDK tools/xgmtool,
 * MIT; VGM_cleanCommands, XGM_extractMusic, XGC_extractMusic), ported to C one frame at a time so the machine can
 * stream XGC frames to SGDK's XGM Z80 driver while it plays, and the export can write the same .xgm / .xgc files
 * xgmtool writes from the exported VGM. Specified in docs/AUDIO_ENGINE.md ("YM2612: XGM / XGC encoding"); the host
 * test compares the bytes with xgmtool's. Songs here have no PCM, so sample handling is the empty case. No
 * allocation: every buffer is in the encoder. */
#ifndef THORTRACKER_YM_XGM_H_
#define THORTRACKER_YM_XGM_H_

#include <stddef.h>
#include <stdint.h>

#include "ym_player.h"

#ifdef __cplusplus
extern "C" {
#endif

#define YMX_MAX_CMDS (2 * YMP_MAX_WRITES + 512) /* cleaned writes of one frame (deltas, keys, PSG) */
#define YMX_MAX_FRAME_BYTES 8192                /* XGM or XGC bytes of one frame */
#define YMX_XGC_SPLIT 250        /* xgmtool closes an XGC frame before it reaches 250 bytes */
#define YMX_XGC_FRAME_SKIP 0xFA  /* XGC command bytes are XGM codes << 1: frame skip $7D */
#define YMX_XGC_LOOP 0xFC        /* loop $7E, then a 24-bit offset into the music data */
#define YMX_XGC_END 0xFE         /* end $7F */

typedef struct YmxYmState {
    int16_t reg[2][256]; /* -1 = never written */
    uint8_t init[2][256];
} YmxYmState;

typedef struct YmxPsgState {
    int32_t reg[4][2];   /* [index][0 tone/noise, 1 attenuation], -1 = never written */
    uint8_t init[4][2];
    int index, type;     /* latched register */
} YmxPsgState;

typedef struct YmxCmd {
    uint8_t kind; /* YM_W_PORT0 / YM_W_PORT1 / YM_W_PSG */
    uint8_t reg;
    uint8_t val;
} YmxCmd;

typedef struct YmxList {
    int start[YMX_MAX_CMDS], len[YMX_MAX_CMDS];
    int n;
} YmxList;

typedef struct YmXgmEncoder {
    YmxYmState clean_ym;   /* VGM_cleanCommands' previous YM state */
    YmxPsgState clean_psg; /* and PSG state */
    YmxYmState xgc_ym;     /* XGC_extractMusic's YM state (D1L/RR state commands) */
    /* scratch of one frame */
    YmxYmState cur;
    YmxPsgState cur_psg;
    YmxCmd cleaned[YMX_MAX_CMDS];
    int ncleaned;
    YmxCmd group[4][YMX_MAX_CMDS];   /* XGM grouping: port 0, port 1, keys, PSG */
    YmxCmd keys[YMX_MAX_CMDS];
    uint8_t xgm[YMX_MAX_FRAME_BYTES]; /* the frame's XGM commands */
    YmxList xgm_cmds;
    uint8_t items[YMX_MAX_FRAME_BYTES]; /* the frame's XGC commands before framing */
    YmxList xgc_cmds;
    int pend_other[YMX_MAX_CMDS], pend_key[YMX_MAX_CMDS];
} YmXgmEncoder;

void ymx_reset(YmXgmEncoder* e);

/* One frame: the frame's writes in the player's order (`in`, n records; a VGM frame); `loop_at` = the index in
 * `in` where the loop point is (xgmtool's loop-start command: the clean step's previous state resets there), -1 none. Returns the frame's XGM commands (without
 * the frame/loop/end terminator; *xgm_size bytes, valid until the next call) and writes its XGC frames (size byte,
 * commands, frame skips where a frame reaches 250 bytes) to `xgc` (capacity `xgc_cap`, *xgc_size bytes). Returns
 * NULL on overflow (more than YMX_MAX_FRAME_BYTES or `xgc_cap`). */
const uint8_t* ymx_frame(YmXgmEncoder* e, const YmWrite* in, int n, int loop_at, size_t* xgm_size, uint8_t* xgc,
                         size_t xgc_cap, size_t* xgc_size);
/* Seek fast-forward: the encoder state ymx_frame would leave after this frame, without encoding (the clean and XGC
 * states both hold every register written so far; a frame with a loop start must go through ymx_frame). */
void ymx_absorb(YmXgmEncoder* e, const YmWrite* in, int n);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_YM_XGM_H_ */
