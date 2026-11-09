/* The Mega Drive music machine: SGDK's XGM2 driver (generated/xgm2_driver_image.h) on a Z80 (floooh z80.h) with a
 * YM2612 (ymfm with its timers, ym_ymfm.h) and the VDP's SN76489 (ym_psg.h), fed with the XGM2 FM and PSG frames of
 * Thor Tracker's player (ym_player.h -> ym_xgm.h) through two packed stream rings and the song's compiled PCM bank in
 * an emulated cartridge; the 68000's part (SGDK's driver load, XGM2_load's sample table, XGM2_playTrack, its access
 * protocol) is done by the machine as SGDK's xgm2.c does it, and the VDP's vertical interrupt reaches the Z80 as the
 * hardware's (the INT line held for one line, YMM_INT_CYCLES). The driver mixes the three PCM channels into the
 * YM2612's DAC itself (FM 6 is silent while it does). Output resampled band-limited to the host rate with blip_buf.
 * One tick = one vertical interrupt (NTSC 59.92 Hz, PAL 49.70 Hz). The Z80 waits on every 68000-bus access as the
 * driver's cycle counts assume. Behaviour (frames one tick ahead, the player waiting a tick when the driver skips a
 * frame, fully clocked seek, live edits with sample-bank changes, audition, mute) is specified in docs/AUDIO_ENGINE.md,
 * "YM2612". No allocation after ymm_create. One thread at a time. */
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
#define YMM_INT_CYCLES 228u           /* Z80 cycles the vertical interrupt holds INT (one line) */
#define YMM_RELEASE_SECONDS 10        /* a released audition ends after this long even if it never decays */
#define YMM_ORDER_SEEK_TICKS 65536    /* bound of an order-fallback seek: < 256 rows x 255 ticks */
#define YMM_MAX_LAG 128               /* ticks in a row the driver may execute no frame before the machine faults */
#define YMM_FM_STREAM 0x020000u       /* 68000 address of the FM stream ring */
#define YMM_PSG_STREAM 0x030000u      /* 68000 address of the PSG stream ring */
#define YMM_STREAM_SIZE 0x10000u
#define YMM_SAMPLES 0x100000u         /* 68000 address of the PCM bank (YM_PCM_BANK_SIZE) */
#define YMM_LOG_MAX 8192              /* test and export hook: chip writes logged per tick */

/* Driver variables (SGDK drv_xgm2.s80). */
#define YMM_COMMAND 0x0100
#define YMM_STATUS 0x0102
#define YMM_PARAMS 0x0104
#define YMM_VARS 0x0110
#define YMM_ACCESS (YMM_VARS + 0x50)
#define YMM_PENDING_FRAME (YMM_VARS + 0x54)
#define YMM_ELAPSED_FRAME (YMM_VARS + 0x55) /* 24 bits: frames the FM stream has processed since play */
#define YMM_PCM_VARS (YMM_VARS + 0xD0)      /* 3 x 8: address (24 bits), remaining length / 64, priority and flags */
#define YMM_SID_TABLE 0x1C00
#define YMM_STAT_PLAY 0x10
#define YMM_STAT_READY 0x80

enum { YMM_OK = 0, YMM_ERR_IMAGE = -1, YMM_ERR_FAULT = -2 };
enum { YMM_SEEK_FROM_START = 0, YMM_SEEK_FROM_ORDER = 1 };

typedef struct YmMachine YmMachine;

/* A chip write the Z80 made: port 0/1 (YM2612 register reg) or 2 (PSG byte val). */
typedef struct YmmLogEntry {
    uint32_t cycle; /* Z80 cycle within the tick */
    uint8_t kind, reg, val;
} YmmLogEntry;

YmMachine* ymm_create(uint32_t sample_rate);
void ymm_destroy(YmMachine* m);

/* Machine reset, driver boot, then the whole machine runs from the song start until the next tick starts (order, row)
 * - at most max_ticks ticks (YMM_SEEK_FROM_START) - with the output discarded; beyond the budget it starts with the
 * player at the order's row 0 with the speed straight play starts it with and runs the order's earlier rows
 * (YMM_SEEK_FROM_ORDER, at most YMM_ORDER_SEEK_TICKS). Either way the driver, the chips and the PCM channels are in
 * the state that straight play from that start reaches. */
int ymm_start(YmMachine* m, const YmImage* image, int order, int row, int max_ticks, int* seek_mode);
/* Live edit at a tick boundary (realtime-safe): the player continues at its position in the new data (it is one tick
 * ahead of the sound, so the edit is heard from the tick after next). A changed PCM bank is mapped at the driver's
 * PCM fill boundary after it has executed the frames encoded before the edit: a sounding sample whose bytes the new
 * bank holds unchanged continues there (moved if it moved), any other stops. An edit made while such a mapping is
 * still pending waits in a third image buffer and applies at the first tick boundary after it (the latest wins). */
void ymm_apply_image(YmMachine* m, const YmImage* image);
/* One held note: channel 0..12, note 0..95 (absolute), slot 1..64 (FM on 0-5, PSG on 6-9, PCM on 10-12 with the
 * note mapped), fine -128..127 (FM and PSG). */
int ymm_audition(YmMachine* m, const YmImage* image, int channel, int note, int slot, int fine);
/* Key off the audition note: 0 = silent now, 1 = release phase (ymm_tick returns 0 once it has decayed - a PCM sample
 * once it has played out - or after YMM_RELEASE_SECONDS). */
int ymm_release(YmMachine* m);
/* Leaves a tracker channel out of the output: FM and PSG channels in the chips' sum (the player, the driver and the
 * chips are untouched); a PCM channel's samples stop through the driver (the next frame) and its notes are not sent
 * while it is muted. */
int ymm_mute(YmMachine* m, int channel, int muted);
/* The position of the player tick the driver executes next: the tick after the last one it finished (when the driver
 * skips a frame the position waits a tick with it). */
void ymm_position(const YmMachine* m, int* order, int* row, int* tick);
/* Row starts (player ticks at tick 0) up to the tick ymm_position reports: it changes exactly when the heard position
 * moves into a new row instance, also one a jump sends back to itself (chip_engine.h, row_serial). */
uint32_t ymm_row_serial(const YmMachine* m);
/* One tick: float stereo frames into out (<= YMM_MAX_TICK_FRAMES); 0 once a released audition ended; YMM_ERR_FAULT
 * if the driver stopped or executed no frame for YMM_MAX_LAG ticks. */
int ymm_tick(YmMachine* m, float* out);

/* ---- export, diagnostics and test hooks ---- */
/* One tick without output (exports): the Z80 and chips run, nothing is resampled. Returns 0 or YMM_ERR_FAULT. */
int ymm_tick_silent(YmMachine* m);
const YmPlayer* ymm_player(const YmMachine* m);      /* the player (one tick ahead of the sound) */
uint32_t ymm_ticks(const YmMachine* m);              /* ticks since start/audition, seek excluded */
/* The packed FM and PSG blocks the last tick appended to the rings (the driver unpacks them this tick and executes
 * them the next); size 0 when the tick appended none (the player waited for a skipped frame). */
const uint8_t* ymm_last_fm(const YmMachine* m, int* size);
const uint8_t* ymm_last_psg(const YmMachine* m, int* size);
uint32_t ymm_frames(const YmMachine* m);   /* player ticks encoded since start/audition (the last one queued) */
uint32_t ymm_executed(const YmMachine* m); /* of them, the frames the driver has executed */
/* Chip writes of the last tick, when enabled (YM2612 $27 timer resets and $2A/$2B DAC writes included). */
void ymm_set_log(YmMachine* m, int enabled);
const YmmLogEntry* ymm_log(const YmMachine* m, int* count);
const uint8_t* ymm_z80_ram(const YmMachine* m);
uint32_t ymm_z80_cycles_per_tick(const YmMachine* m); /* of the last tick */
int ymm_lag(const YmMachine* m); /* frames encoded but not yet executed beyond the queued one: 1 after a skipped frame */
unsigned ymm_pcm_playing(const YmMachine* m); /* driver STATUS bits 0-2: PCM channels playing */
int ymm_dac_enabled(const YmMachine* m);       /* YM2612 $2B bit 7 as the chip holds it */
int ymm_swap_pending(const YmMachine* m);      /* a live edit's PCM bank waits for the driver's fill boundary */
const void* ymm_z80(const YmMachine* m);       /* the z80_t */

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_YM_MACHINE_H_ */
