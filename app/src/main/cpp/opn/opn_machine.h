/* The OPN / OPNA music machine: Thor Tracker's player (opn_player.h) driving ymfm's YM2203 or YM2608 (opn_ymfm.h) at
 * the native rate (55466.67 Hz), resampled band-limited to the host rate with blip_buf. One tick = 16 x (256 - TB)
 * native samples; all of a tick's register writes reach the chip before its samples (the key-off phase one native
 * sample before the rest). Behaviour (seek, live edits, audition, mute) is specified in docs/AUDIO_ENGINE.md, "OPN and
 * OPNA". No allocation after opnm_create. One thread at a time. */
#ifndef THORTRACKER_OPN_MACHINE_H_
#define THORTRACKER_OPN_MACHINE_H_

#include <stdint.h>

#include "opn_image.h"
#include "opn_player.h"
#include "opn_ymfm.h"

#ifdef __cplusplus
extern "C" {
#endif

#define OPNM_MAX_SAMPLE_RATE 96000u
#define OPNM_MAX_TICK_FRAMES 2048u /* 96000 x 16 x (256 - OPN_TIMER_B_MIN) / 55466.67 = 2021.5, rounded up */
#define OPNM_RELEASE_SECONDS 10    /* a released audition ends after this long even if it never decays */

enum { OPNM_OK = 0, OPNM_ERR_IMAGE = -1, OPNM_ERR_FAULT = -2 };
enum { OPNM_SEEK_FROM_START = 0, OPNM_SEEK_FROM_ORDER = 1 };

typedef struct OpnMachine OpnMachine;

/* A machine for `chip` (OPN_CHIP_*) at the host rate; NULL on failure. */
OpnMachine* opnm_create(int chip, uint32_t sample_rate);
void opnm_destroy(OpnMachine* m);

/* The machine is reset exactly as a fresh one, then the player finds the target from the song start - at most
 * max_ticks ticks to the tick that starts (order, row) (OPNM_SEEK_FROM_START). Beyond the budget: the player is placed
 * at the order start with the speed straight play starts it with and replays the order's earlier rows
 * (OPNM_SEEK_FROM_ORDER). Either way every tick from that start point to the target runs exactly as opnm_tick runs it
 * (all writes, the whole chip clocked, the resamplers fed and read) with the output discarded, so after a FROM_START
 * seek opnm_tick's PCM is sample-identical to straight play from (0, 0) reaching that row. The cost is that of
 * rendering the skipped music (WAV export speed), bounded by max_ticks. Images of another chip are refused
 * (OPNM_ERR_IMAGE). */
int opnm_start(OpnMachine* m, const OpnImage* image, int order, int row, int max_ticks, int* seek_mode);
/* Live edit at a tick boundary (realtime-safe): the song continues at the same (order, row) in the new data; the
 * machine's ADPCM-B memory copy is replaced (a sample under way continues from the same address in the new bytes). */
void opnm_apply_image(OpnMachine* m, const OpnImage* image);
/* One held note: a channel of the chip, note 0..95 (absolute: 0 = C-0, 57 = A-4), instrument slot 1..64 of the
 * channel's kind, fine -128..127 (1/64 semitone). */
int opnm_audition(OpnMachine* m, const OpnImage* image, int channel, int note, int slot, int fine);
/* Key off the audition note: 0 = silent now (no tick yet, or already silent), 1 = release phase (opnm_tick returns 0
 * once the channel is silent - FM carriers quiet, SSG volume 0, rhythm drum and ADPCM-B sample ended - or after
 * OPNM_RELEASE_SECONDS). */
int opnm_release(OpnMachine* m);
/* Silences a tracker channel at the chip output; the player is untouched. */
int opnm_mute(OpnMachine* m, int channel, int muted);
void opnm_position(const OpnMachine* m, int* order, int* row, int* tick);
/* One tick: float stereo frames into out (<= OPNM_MAX_TICK_FRAMES); 0 once a released audition ended. */
int opnm_tick(OpnMachine* m, float* out);

/* ---- diagnostics and test hooks ---- */
const OpnPlayer* opnm_player(const OpnMachine* m); /* write[] = the player writes of the last tick (or start) */
const OpnChip* opnm_chip(const OpnMachine* m);
uint32_t opnm_ticks(const OpnMachine* m);          /* ticks since start/audition, seek excluded */
int opnm_native_samples(const OpnMachine* m);      /* native chip samples of the last tick */

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_OPN_MACHINE_H_ */
