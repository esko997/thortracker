/* The Virtual Boy VSU music machine: Thor Tracker's player (vsu_player.h) driving the VSU core (vsu_chip.h). One tick
 * = one 50 Hz VIP frame = exactly 100000 VSU clocks: the player's batch reaches the chip at the tick's first clock,
 * then the chip runs the tick and its 10-bit mixer output is sampled at every multiple of 120 clocks of the machine's
 * clock count (sample k at clock 120 k, k >= 1, after that clock's events: 833 or 834 per tick), each through the
 * analog RC high-pass (100 ohm, 220 uF) discretised at that rate and into blip_buf (VSU clock -> host rate) as integer
 * steps of round(y x 64); float output = blip sample / (64 x 384). Behaviour (seek, live edits, audition, mute) is
 * specified in docs/AUDIO_ENGINE.md, "Virtual Boy VSU". No allocation after vbm_create. One thread at a time. */
#ifndef THORTRACKER_VSU_MACHINE_H_
#define THORTRACKER_VSU_MACHINE_H_

#include <stdint.h>

#include "vsu_chip.h"
#include "vsu_image.h"
#include "vsu_player.h"

#ifdef __cplusplus
extern "C" {
#endif

#define VBM_MAX_SAMPLE_RATE 96000u
#define VBM_MAX_TICK_FRAMES 1921u /* 96000 / 50 + 1 */
#define VBM_OUTPUT_TICKS 50       /* a seek runs the output path for its last min(n, 50) ticks (1 s, 45 RC time constants) */
#define VBM_STEP_SCALE 64         /* blip_buf units per mixer unit */
#define VBM_FLOAT_SCALE (64.0f * 384.0f)
#define VBM_RC 0.022              /* seconds: 100 ohm x 220 uF */

enum { VBM_OK = 0, VBM_ERR_IMAGE = -1, VBM_ERR_FAULT = -2 };
enum { VBM_SEEK_FROM_START = 0, VBM_SEEK_FROM_ORDER = 1 };

typedef struct VsuMachine VsuMachine;

VsuMachine* vbm_create(uint32_t sample_rate);
void vbm_destroy(VsuMachine* m);

/* The player runs alone from the song start to count the ticks n until the next tick starts (order, row) - at most
 * max_ticks (VBM_SEEK_FROM_START); beyond, from the order's row 0 with the order's start speed (VBM_SEEK_FROM_ORDER;
 * a row behind a Dxx of the same order is unreachable: VBM_ERR_IMAGE). Then the machine is reset as a fresh one and
 * every one of the n ticks runs with the chip fully clocked; the output path (sampling, RC filter, blip_buf) runs,
 * discarded, for the last min(n, VBM_OUTPUT_TICKS) ticks. Mutes are cleared. */
int vbm_start(VsuMachine* m, const VsuImage* image, int order, int row, int max_ticks, int* seek_mode);
/* Live edit at a tick boundary (realtime-safe; ignored while auditioning): the song continues at the same (order,
 * row) in the new data; changed waves and modulation values are poked into the chip at once. */
void vbm_apply_image(VsuMachine* m, const VsuImage* image);
/* One held note: lane 0..5, note (absolute: 57 = A-4; wave lanes 27..95, Noise 0..95), instrument slot 1..64, fine
 * -128..127 (1/64 semitone). Mutes are cleared. */
int vbm_audition(VsuMachine* m, const VsuImage* image, int lane, int note, int slot, int fine);
/* Key-off of the audition note (SxINT = 0 to the chip now): the VSU has no release phase, so this returns 0 and the
 * next vbm_tick returns 0. */
int vbm_release(VsuMachine* m);
/* Leaves a channel out of the mixer sum (from the next sample); chip and player are untouched. */
int vbm_mute(VsuMachine* m, int lane, int muted);
void vbm_position(const VsuMachine* m, int* order, int* row, int* tick);
/* One tick: float stereo frames into out (<= VBM_MAX_TICK_FRAMES); 0 after a released audition. */
int vbm_tick(VsuMachine* m, float* out);

/* ---- diagnostics and test hooks ---- */

/* Called for every following tick (not for the ticks start() runs to reach its position) with the position before
 * the tick, the tick's first clock and its writes. */
typedef void (*VbmTickFn)(void* ctx, int order, int row, int tick, uint64_t clock, const VbWrite* writes, int count);
/* Called for every mixer sample of every following tick (clock, 10-bit left/right after mutes). */
typedef void (*VbmSampleFn)(void* ctx, uint64_t clock, int left, int right);
void vbm_set_trace(VsuMachine* m, VbmTickFn tick, VbmSampleFn sample, void* ctx);
const VbPlayer* vbm_player(const VsuMachine* m); /* write[] = the writes of the last tick (or of start/audition) */
const VsuChip* vbm_chip(const VsuMachine* m);
uint32_t vbm_ticks(const VsuMachine* m);         /* ticks since start/audition, seek excluded */
uint64_t vbm_clock(const VsuMachine* m);         /* VSU clocks since the machine's reset (the song start) */

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_VSU_MACHINE_H_ */
