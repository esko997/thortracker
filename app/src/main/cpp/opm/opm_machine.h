/* The OPM music machine: Thor Tracker's player (opm_player.h) driving a YM2151 emulated by ymfm (opm_ymfm.h) at the
 * X68000's 4 MHz clock, native rate 4000000 / 64 = 62500 Hz, resampled band-limited to the host rate with blip_buf.
 * One tick = 1/60 s / multispeed; all of a tick's register writes reach the chip before its samples (the key-off
 * phase one native sample before the rest). Behaviour (seek, live edits, audition, mute) is specified in
 * docs/AUDIO_ENGINE.md, "OPM". No allocation after omm_create. One thread at a time. */
#ifndef THORTRACKER_OPM_MACHINE_H_
#define THORTRACKER_OPM_MACHINE_H_

#include <stdint.h>

#include "opm_image.h"
#include "opm_player.h"

#ifdef __cplusplus
extern "C" {
#endif

#define OMM_MAX_SAMPLE_RATE 96000u
#define OMM_MAX_TICK_FRAMES 1601u /* 96000 / 60 + 1 */
#define OMM_CLOCK 4000000u        /* X68000: 16 MHz crystal / 4 (MAME x68k.cpp: YM2151(config, m_ym2151, 16_MHz_XTAL / 4)) */
#define OMM_NATIVE_DIVIDER 64u    /* 32 operator slots x prescale 2 */
#define OMM_RELEASE_SECONDS 10    /* a released audition ends after this long even if an operator never decays */
#define OMM_PREROLL_TICKS 60      /* x multispeed: a seek clocks the chip through the last second before its target */

enum { OMM_OK = 0, OMM_ERR_IMAGE = -1, OMM_ERR_FAULT = -2 };
enum { OMM_SEEK_FROM_START = 0, OMM_SEEK_FROM_ORDER = 1 };

typedef struct OpmMachine OpmMachine;

OpmMachine* omm_create(uint32_t sample_rate);
void omm_destroy(OpmMachine* m);

/* Chip reset, then the player is fast-forwarded from the song start until the next tick starts (order, row) - at
 * most max_ticks ticks (OMM_SEEK_FROM_START). Beyond the budget: the player is placed at the order start with the
 * speed straight play starts it with and replays the order's earlier rows (OMM_SEEK_FROM_ORDER). Either way the
 * player runs alone (no chip clocking) until OMM_PREROLL_TICKS x multispeed ticks before the target, the chip
 * receives its register state, and the rest runs with the chip clocked (output discarded). */
int omm_start(OpmMachine* m, const OpmImage* image, int order, int row, int max_ticks, int* seek_mode);
/* Live edit at a tick boundary (realtime-safe): the song continues at the same (order, row) in the new data. */
void omm_apply_image(OpmMachine* m, const OpmImage* image);
/* One held note: channel 0..7, note OM_NOTE_MIN..OM_NOTE_MAX (absolute: 0 = C-0, 57 = A-4), instrument slot 1..64,
 * fine -128..127 (1/64 semitone). Noise instruments only on channel 7 (FM 8). */
int omm_audition(OpmMachine* m, const OpmImage* image, int channel, int note, int slot, int fine);
/* Key off the audition note: 0 = silent now (no tick yet, or already decayed), 1 = release phase (omm_tick returns 0
 * once every carrier operator has decayed to silence, or after OMM_RELEASE_SECONDS). */
int omm_release(OpmMachine* m);
/* Silences a tracker channel at the chip output (left out of the sum); the player and the chip registers are untouched. */
int omm_mute(OpmMachine* m, int channel, int muted);
void omm_position(const OpmMachine* m, int* order, int* row, int* tick);
/* One tick: float stereo frames into out (<= OMM_MAX_TICK_FRAMES); 0 once a released audition ended. */
int omm_tick(OpmMachine* m, float* out);

/* ---- diagnostics and test hooks ---- */
const OmPlayer* omm_player(const OpmMachine* m); /* write[] = the player writes of the last tick (or start) */
uint32_t omm_ticks(const OpmMachine* m);         /* ticks since start/audition, seek excluded */
int omm_native_samples(const OpmMachine* m);     /* native chip samples of the last tick */
int omm_envelope(const OpmMachine* m, int channel, int op); /* omc_envelope of the machine's chip */

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_OPM_MACHINE_H_ */
