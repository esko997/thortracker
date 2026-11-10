/* The SCC music machine: Thor Tracker's player (scc_player.h) driving a Konami K051649 or K052539 (scc_k051649.h) at
 * the MSX's 3579545 Hz, resampled band-limited to the host rate with blip_buf (mono, duplicated to both stereo
 * channels). One tick = 1/60 s / multispeed; all of a tick's register writes reach the core at its first clock.
 * Behaviour (seek, live edits, audition, mute) is specified in docs/AUDIO_ENGINE.md, "SCC and SCC+". The variant is the
 * loaded image's; the ops (scc_chip.c) accept only their own. No allocation after scm_create. One thread at a time. */
#ifndef THORTRACKER_SCC_MACHINE_H_
#define THORTRACKER_SCC_MACHINE_H_

#include <stdint.h>

#include "scc_image.h"
#include "scc_k051649.h"
#include "scc_player.h"

#ifdef __cplusplus
extern "C" {
#endif

#define SCM_MAX_SAMPLE_RATE 96000u
#define SCM_MAX_TICK_FRAMES 1601u /* 96000 / 60 + 1 */
#define SCM_CLOCK SCK_CLOCK       /* 3579545 Hz */
#define SCM_LEVEL_SCALE 24        /* raw sum ±14400; reserve room for high-pass transients and interpolation */
#define SCM_RELEASE_SECONDS 10    /* a released audition ends after this long even if its envelope never falls silent */
#define SCM_PREROLL_TICKS 60      /* x multispeed: a seek renders the last second before its target */
#define SCM_SEEK_TICKS 12000      /* the Kotlin engine's seekTicks */

enum { SCM_OK = 0, SCM_ERR_IMAGE = -1, SCM_ERR_FAULT = -2 };
enum { SCM_SEEK_FROM_START = 0, SCM_SEEK_FROM_ORDER = 1 };

typedef struct SccMachine SccMachine;

SccMachine* scm_create(uint32_t sample_rate);
void scm_destroy(SccMachine* m);

/* Core and player reset, then the player runs alone from the song start until the next tick starts (order, row) - at
 * most max_ticks ticks (SCM_SEEK_FROM_START); beyond, from the order start with the speed straight play starts it with
 * (SCM_SEEK_FROM_ORDER). Then from the reset again: the core follows the player's writes with sck_advance until
 * SCM_PREROLL_TICKS x multispeed ticks before the target and renders the rest (output discarded), so the core state
 * at the target equals straight play's exactly. */
int scm_start(SccMachine* m, const SccImage* image, int order, int row, int max_ticks, int* seek_mode);
/* Live edit at a tick boundary (realtime-safe): the song continues at the same (order, row) in the new data; ignored
 * while auditioning or for an image of another variant. */
void scm_apply_image(SccMachine* m, const SccImage* image);
/* One held note: channel 0..4, note 0..95 (absolute: 0 = C-0, 57 = A-4), instrument slot 1..64, fine -128..127
 * (1/64 semitone). */
int scm_audition(SccMachine* m, const SccImage* image, int channel, int note, int slot, int fine);
/* Key off the audition note: 0 = silent now (no tick yet, no release point, or already silent), 1 = release phase
 * (scm_tick returns 0 once the release is silent for good, or after SCM_RELEASE_SECONDS). */
int scm_release(SccMachine* m);
/* Leaves a tracker channel out of the output (the player and the core are untouched). */
int scm_mute(SccMachine* m, int channel, int muted);
void scm_position(const SccMachine* m, int* order, int* row, int* tick);
/* One tick: float stereo frames into out (<= SCM_MAX_TICK_FRAMES); 0 once a released audition ended. */
int scm_tick(SccMachine* m, float* out);

/* ---- diagnostics and test hooks ---- */
const ScPlayer* scm_player(const SccMachine* m); /* write[] = the player writes of the last tick (or start) */
const SccCore* scm_core(const SccMachine* m);    /* the chip state */
uint32_t scm_ticks(const SccMachine* m);         /* ticks since start/audition, seek excluded */
uint32_t scm_clocks(const SccMachine* m);        /* chip clocks of the last tick */

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_SCC_MACHINE_H_ */
