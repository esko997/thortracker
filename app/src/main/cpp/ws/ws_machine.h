/* The WonderSwan music machine: Thor Tracker's player (ws_player.h) driving the sound unit (ws_apu.h) at 3072000 Hz,
 * CPU-fed samples streamed from the voice timer (the HBlank timer) interrupt, Sound DMA reading the image's data
 * region at WS_DMA_BASE, output resampled band-limited to the host rate with blip_buf. Tick k (from the start or
 * audition reset) spans cycles 40704 k .. 40704 (k + 1): the player's batch is written at the tick's first cycle, each
 * timer interrupt inside the tick at its cycle (after the tick's batch when they share a cycle). The timer interrupts
 * every 256 R cycles, the first 256 R cycles after the reset; a new reload R applies from the next reload (after the
 * next interrupt). Every event falls on a 128-cycle block boundary; each block's frame, halved, is a level change at
 * the block's last cycle. Behaviour (seek, live edits, audition, mute) is specified in docs/AUDIO_ENGINE.md,
 * "WonderSwan". A machine plays one model (wsm_create). No allocation after wsm_create. One thread at a time. */
#ifndef THORTRACKER_WS_MACHINE_H_
#define THORTRACKER_WS_MACHINE_H_

#include <stdint.h>

#include "ws_apu.h"
#include "ws_image.h"
#include "ws_player.h"

#ifdef __cplusplus
extern "C" {
#endif

#define WSM_MAX_SAMPLE_RATE 96000u
#define WSM_MAX_TICK_FRAMES 1273u  /* 96000 x 40704 / 3072000 + 1 */
#define WSM_SEEK_TICKS 4528        /* the suggested start() budget of clocked ticks (60 s) */
#define WSM_RELEASE_SECONDS 10     /* a released audition ends after this long at the latest */
#define WSM_BLOCKS_PER_TICK 318    /* 40704 / 128 */

enum { WSM_OK = 0, WSM_ERR_IMAGE = -1, WSM_ERR_FAULT = -2 };
enum { WSM_SEEK_FROM_START = 0, WSM_SEEK_FROM_ORDER = 1 };
/* Trace batch kinds. */
enum { WSM_TRACE_RESET = 0, WSM_TRACE_TICK = 1, WSM_TRACE_TIMER = 2, WSM_TRACE_OFF = 3 };

typedef struct WsMachine WsMachine;

/* Called with every batch the machine sends to the sound unit, in order: `cycle` = cycles since the last
 * start/audition reset (seek ticks included), `w`/`n` = the player's writes. Mute toggles are not traced. */
typedef void (*WsmTraceFn)(void* ctx, int64_t cycle, int kind, const WsWrite* w, int n);
/* Called with every Sound DMA transfer: its cycle (block start + 122), target (0 CH2, 1 Hyper Voice), data. */
typedef void (*WsmDmaTraceFn)(void* ctx, int64_t cycle, int target, uint8_t data);

/* A machine for one model (WS_MODEL_MONO or WS_MODEL_COLOR); images of the other model are refused. */
WsMachine* wsm_create(uint32_t sample_rate, int model);
void wsm_destroy(WsMachine* m);

/* Reset, then the player alone finds the ticks straight play needs to reach (order, row, 0); if at most max_ticks,
 * the machine resets and plays them with the timer, the sound unit (Sound DMA included) and the resampler clocked
 * exactly as audible playback, output discarded (WSM_SEEK_FROM_START). Otherwise the player is placed at the order's
 * start with the speed straight play starts it with and the order's earlier rows replay the same clocked way
 * (WSM_SEEK_FROM_ORDER); a row behind a Dxx/Bxx of the same order is unreachable (WSM_ERR_IMAGE). */
int wsm_start(WsMachine* m, const WsImage* image, int order, int row, int max_ticks, int* seek_mode);
/* Live edit at a tick boundary (realtime-safe): the song continues at the same (order, row) in the new data; a DMA
 * note whose bytes moved unchanged is rebased, one whose bytes changed stops at the next tick; the output path follows
 * the new header from the next tick. */
void wsm_apply_image(WsMachine* m, const WsImage* image);
/* One held note: lane 0..3 (0..4 on the Color), note WS_NOTE_MIN..WS_NOTE_MAX (absolute: 57 = A-4), instrument slot
 * 1..64, fine -128..127 (1/64 semitone). The instrument must fit the lane. */
int wsm_audition(WsMachine* m, const WsImage* image, int lane, int note, int slot, int fine);
/* OFF on the audition note, written at once: 0 = silent now (no tick yet, or the lane stopped), 1 = release phase
 * (wsm_tick returns 0 once the lane stopped, its one-shot DMA sample ended, or its written volume is 0 with the volume
 * envelope at its last step, or after WSM_RELEASE_SECONDS). */
int wsm_release(WsMachine* m);
/* The sound unit's mute mask: the lane's output is computed and discarded; nothing else changes. start() and
 * audition() clear every mute. */
int wsm_mute(WsMachine* m, int lane, int muted);
void wsm_position(const WsMachine* m, int* order, int* row, int* tick);
/* One tick: float stereo frames into out (<= WSM_MAX_TICK_FRAMES); 0 once an audition ended. */
int wsm_tick(WsMachine* m, float* out);

/* ---- diagnostics and test hooks ---- */
const WsPlayer* wsm_player(const WsMachine* m); /* write[] = the last batch sent */
const WsApu* wsm_apu(const WsMachine* m);
uint32_t wsm_ticks(const WsMachine* m);         /* ticks since start/audition, seek excluded */
int wsm_timer_interrupts(const WsMachine* m);   /* timer interrupts of the last tick */
int wsm_model(const WsMachine* m);
void wsm_set_trace(WsMachine* m, WsmTraceFn fn, void* ctx);        /* NULL = off; kept across start/audition */
void wsm_set_dma_trace(WsMachine* m, WsmDmaTraceFn fn, void* ctx); /* NULL = off; kept across start/audition */

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_WS_MACHINE_H_ */
