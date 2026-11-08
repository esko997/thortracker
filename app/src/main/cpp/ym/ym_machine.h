/* The Mega Drive music machine: SGDK's XGM driver (generated/xgm_driver_image.h) on a Z80 (floooh z80.h) with a
 * YM2612 (ymfm, ym_ymfm.h) and the VDP's SN76489 (ym_psg.h), fed with the XGC frames of Thor Tracker's player
 * (ym_player.h -> ym_xgm.h) through an emulated cartridge window; the 68000's part (driver load, play command, one
 * PENDING_FRM per vertical interrupt) is done by the machine as SGDK's xgm.c does it. Output resampled band-limited
 * to the host rate with blip_buf. One tick = one vertical interrupt (NTSC 59.92 Hz, PAL 49.70 Hz). Behaviour (frames
 * one tick ahead, seek, live edits, audition, mute) is specified in docs/AUDIO_ENGINE.md, "YM2612".
 * No allocation after ymm_create. One thread at a time. */
#ifndef THORTRACKER_YM_MACHINE_H_
#define THORTRACKER_YM_MACHINE_H_

#include <stdint.h>

#include "ym_image.h"
#include "ym_player.h"

#ifdef __cplusplus
extern "C" {
#endif

#define YMM_MAX_SAMPLE_RATE 96000u
#define YMM_MAX_TICK_FRAMES 1933u     /* 96000 x 1070460 / 53203424 (a PAL tick) + 2 */
#define YMM_MCLK_PER_LINE 3420u
#define YMM_LINES_NTSC 262u
#define YMM_LINES_PAL 313u
#define YMM_MCLK_NTSC 53693175u
#define YMM_MCLK_PAL 53203424u
#define YMM_MCLK_PER_Z80 15u
#define YMM_MCLK_PER_YM_SAMPLE 1008u  /* YM2612 clock MCLK / 7, one sample per 144 clocks */
#define YMM_RELEASE_SECONDS 10        /* a released audition ends after this long even if an operator never decays */
#define YMM_PREROLL_TICKS 60          /* a seek streams the last 60 ticks before its target through the driver */
#define YMM_NULL_SAMPLE 0x010000u     /* 68000 address of the 256-byte null sample */
#define YMM_RING 0x020000u            /* 68000 address of the XGC ring */
#define YMM_RING_SIZE 0x10000u
#define YMM_LOG_MAX 4096              /* test hook: chip writes logged per tick */

/* Driver variables (SGDK z80_def.i80 / drv_xgm.s80). */
#define YMM_COMMAND 0x0100
#define YMM_STATUS 0x0102
#define YMM_PARAMS 0x0104
#define YMM_MODIFYING_F (YMM_PARAMS + 0x0E)
#define YMM_PENDING_FRM (YMM_PARAMS + 0x0F)
#define YMM_REM_LOOP (YMM_PARAMS + 0x38)
#define YMM_ID_TABLE 0x1C00

enum { YMM_OK = 0, YMM_ERR_IMAGE = -1, YMM_ERR_FAULT = -2 };
enum { YMM_SEEK_FROM_START = 0, YMM_SEEK_FROM_ORDER = 1 };

typedef struct YmMachine YmMachine;

/* A chip write the Z80 (or the 68000 side) made: port 0/1 (YM2612 register reg) or 2 (PSG byte val). */
typedef struct YmmLogEntry {
    uint32_t cycle; /* Z80 cycle within the tick */
    uint8_t kind, reg, val;
} YmmLogEntry;

YmMachine* ymm_create(uint32_t sample_rate);
void ymm_destroy(YmMachine* m);

/* Machine reset, driver boot, then the player is fast-forwarded from the song start until the next tick starts
 * (order, row) - at most max_ticks ticks (YMM_SEEK_FROM_START); beyond the budget it is placed at the order's row 0 with
 * the speed straight play starts it with and replays the order's earlier rows (YMM_SEEK_FROM_ORDER). Either way the
 * player runs alone (the XGC encoder absorbing its writes) until YMM_PREROLL_TICKS ticks before the target, the chips
 * receive its register state, and the rest is streamed through the driver with the output discarded. */
int ymm_start(YmMachine* m, const YmImage* image, int order, int row, int max_ticks, int* seek_mode);
/* Live edit at a tick boundary (realtime-safe): the player continues at its position in the new data (it is one
 * tick ahead of the sound, so the edit is heard from the tick after next). */
void ymm_apply_image(YmMachine* m, const YmImage* image);
/* One held note: channel 0..9, note 0..95 (absolute), slot 1..64 (FM on 0-5, PSG on 6-9), fine -128..127. */
int ymm_audition(YmMachine* m, const YmImage* image, int channel, int note, int slot, int fine);
/* Key off the audition note: 0 = silent now, 1 = release phase (ymm_tick returns 0 once it has decayed or after
 * YMM_RELEASE_SECONDS). */
int ymm_release(YmMachine* m);
/* Leaves a tracker channel out of the chips' output; the player, the driver and the chips are untouched. */
int ymm_mute(YmMachine* m, int channel, int muted);
/* The position of the player tick the driver executes next: the tick after the last one it finished (behind heavy
 * frames the driver lags, and the position waits with it). */
void ymm_position(const YmMachine* m, int* order, int* row, int* tick);
/* One tick: float stereo frames into out (<= YMM_MAX_TICK_FRAMES); 0 once a released audition ended; YMM_ERR_FAULT
 * if the driver stopped. */
int ymm_tick(YmMachine* m, float* out);

/* ---- diagnostics and test hooks ---- */
const YmPlayer* ymm_player(const YmMachine* m);      /* the player (one tick ahead of the sound) */
uint32_t ymm_ticks(const YmMachine* m);              /* ticks since start/audition, seek excluded */
/* The XGC bytes the last tick appended to the ring (the next tick executes them). */
const uint8_t* ymm_last_frames(const YmMachine* m, int* size);
/* Chip writes of the last tick, when enabled (YM2612 $2A/$2B DAC writes included). */
void ymm_set_log(YmMachine* m, int enabled);
const YmmLogEntry* ymm_log(const YmMachine* m, int* count);
const uint8_t* ymm_z80_ram(const YmMachine* m);
uint32_t ymm_z80_cycles_per_tick(const YmMachine* m); /* of the last tick */
int ymm_lag(const YmMachine* m); /* vertical interrupts the driver has not processed yet (heavy frames) */

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_YM_MACHINE_H_ */
