/* The SAA1099 music machine: Thor Tracker's player (saa_player.h) driving one (SAM Coupe, 8 MHz) or two (Creative Music
 * System, 7159090 Hz) SAA1099 cores (saa_core.h), resampled band-limited to the host rate with two blip_buf buffers
 * (left, right). One tick = 1/50 s (SAM) or 1/60 s (CMS) / multispeed; all of a tick's register writes reach the cores
 * at its first clock. Behaviour (seek, live edits, audition, mute) is specified in docs/AUDIO_ENGINE.md, "SAA1099". The
 * variant is the loaded image's; the ops (saa_chip.c) accept only their own. No allocation after saam_create. One
 * thread at a time. */
#ifndef THORTRACKER_SAA_MACHINE_H_
#define THORTRACKER_SAA_MACHINE_H_

#include <stdint.h>

#include "saa_core.h"
#include "saa_image.h"
#include "saa_player.h"

#ifdef __cplusplus
extern "C" {
#endif

#define SAAM_MAX_SAMPLE_RATE 96000u
#define SAAM_MAX_TICK_FRAMES 1921u /* 96000 / 50 + 1 (the SAM Coupe's tick; the CMS's is <= 1601) */
#define SAAM_RATE_SAM 50           /* SAM Coupe ticks per second (x multispeed) */
#define SAAM_RATE_CMS 60           /* CMS ticks per second (x multispeed) */
#define SAAM_LEVEL_SCALE_SAM 10    /* resampler input = sum of the outputs per side x 10 (<= 6 x 480 x 10 = 28800) */
#define SAAM_LEVEL_SCALE_CMS 5     /* x 5 (<= 12 x 480 x 5 = 28800) */
#define SAAM_RELEASE_SECONDS 10    /* a released audition ends after this long even if its envelope never falls silent */
#define SAAM_PREROLL_SECONDS 1     /* a seek renders the last second (rate x multispeed ticks) before its target */
#define SAAM_SEEK_TICKS 12000      /* the Kotlin engine's seekTicks */

enum { SAAM_OK = 0, SAAM_ERR_IMAGE = -1, SAAM_ERR_FAULT = -2 };
enum { SAAM_SEEK_FROM_START = 0, SAAM_SEEK_FROM_ORDER = 1 };

typedef struct SaaMachine SaaMachine;

SaaMachine* saam_create(uint32_t sample_rate);
void saam_destroy(SaaMachine* m);

/* Cores and player reset, then the player runs alone from the song start until the next tick starts (order, row) - at
 * most max_ticks ticks (SAAM_SEEK_FROM_START); beyond, from the order start with the speed straight play starts it
 * with (SAAM_SEEK_FROM_ORDER). Then from the reset again: the cores follow the player's writes with sac_advance until
 * SAAM_PREROLL_SECONDS before the target and render the rest (output discarded), so the core state at the target
 * equals straight play's exactly. */
int saam_start(SaaMachine* m, const SaaImage* image, int order, int row, int max_ticks, int* seek_mode);
/* Live edit at a tick boundary (realtime-safe): the song continues at the same (order, row) in the new data; ignored
 * while auditioning or for an image of the other variant. */
void saam_apply_image(SaaMachine* m, const SaaImage* image);
/* One held note: channel 0..C - 1, note 0..119 (absolute: 0 = C-0, 57 = A-4), instrument slot 1..64, fine -128..127
 * (1/64 semitone). */
int saam_audition(SaaMachine* m, const SaaImage* image, int channel, int note, int slot, int fine);
/* Key off the audition note: 0 = silent now (no tick yet, no longer held, or no release point: the amplitude is
 * zeroed), 1 = release phase (saam_tick returns 0 once the release is silent for good, or after
 * SAAM_RELEASE_SECONDS). */
int saam_release(SaaMachine* m);
/* Leaves a tracker channel out of the output (the player and the cores are untouched). */
int saam_mute(SaaMachine* m, int channel, int muted);
void saam_position(const SaaMachine* m, int* order, int* row, int* tick);
/* One tick: float stereo frames into out (<= SAAM_MAX_TICK_FRAMES); 0 once a released audition ended. */
int saam_tick(SaaMachine* m, float* out);

/* ---- diagnostics and test hooks ---- */
const SaPlayer* saam_player(const SaaMachine* m);         /* write[] = the player writes of the last tick (or start) */
const SacCore* saam_core(const SaaMachine* m, int chip);  /* chip 0 or 1 (the CMS's chip B) */
uint32_t saam_ticks(const SaaMachine* m);                 /* ticks since start/audition, seek excluded */
uint32_t saam_clocks(const SaaMachine* m);                /* chip clocks of the last tick */
uint32_t saam_clock(const SaaMachine* m);                 /* the chip clock of the loaded variant */

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_SAA_MACHINE_H_ */
