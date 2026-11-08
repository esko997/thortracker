/* The OPL3 music machine: Thor Tracker's player (opl3_player.h) driving a YMF262 emulated by Nuked-OPL3
 * 1.8 at its native rate (14318180 / 288 = 49715.9 Hz), resampled band-limited to the host rate with
 * blip_buf. One tick = 1/60 s / multispeed; all of a tick's register writes reach the chip before its
 * samples. Behaviour (seek, live edits, audition, mute) is specified in docs/AUDIO_ENGINE.md, "OPL3".
 * No allocation after o3m_create. One thread at a time. */
#ifndef THORTRACKER_OPL3_MACHINE_H_
#define THORTRACKER_OPL3_MACHINE_H_

#include <stdint.h>

#include "opl3_image.h"
#include "opl3_player.h"

#ifdef __cplusplus
extern "C" {
#endif

#define O3M_MAX_SAMPLE_RATE 96000u
#define O3M_MAX_TICK_FRAMES 1601u /* 96000 / 60 + 1 */
#define O3M_CLOCK 14318180u
#define O3M_NATIVE_DIVIDER 288u
#define O3M_RELEASE_SECONDS 10 /* a released audition ends after this long even if an operator never decays */
#define O3M_PREROLL_TICKS 60   /* x multispeed: a seek clocks the chip through the last second before its target */

enum { O3M_OK = 0, O3M_ERR_IMAGE = -1, O3M_ERR_FAULT = -2 };
enum { O3M_SEEK_FROM_START = 0, O3M_SEEK_FROM_ORDER = 1 };

typedef struct Opl3Machine Opl3Machine;

Opl3Machine* o3m_create(uint32_t sample_rate);
void o3m_destroy(Opl3Machine* m);

/* Chip reset, then the player is fast-forwarded from the song start until the next tick starts (order, row) - at
 * most max_ticks ticks (O3M_SEEK_FROM_START). Beyond the budget: the player is placed at the order start with the
 * speed straight play starts it with and replays the order's earlier rows (O3M_SEEK_FROM_ORDER). Either way the
 * player runs alone (no chip clocking) until O3M_PREROLL_TICKS x multispeed ticks before the target, the chip
 * receives its register state, and the rest runs with the chip clocked (output discarded): from <= 1 s before
 * the target the chip state is straight play's exactly. */
int o3m_start(Opl3Machine* m, const Opl3Image* image, int order, int row, int max_ticks, int* seek_mode);
/* Live edit at a tick boundary (realtime-safe): the song continues at the same (order, row) in the new data. */
void o3m_apply_image(Opl3Machine* m, const Opl3Image* image);
/* One held note: channel 0..8, note 0..95 (absolute: 0 = C-0, 57 = A-4), instrument slot 1..64, fine -128..127
 * (1/64 semitone). 4-op instruments only on channels 0-5. */
int o3m_audition(Opl3Machine* m, const Opl3Image* image, int channel, int note, int slot, int fine);
/* Key off the audition note: 0 = silent now (no tick yet, or already decayed), 1 = release phase (o3m_tick
 * returns 0 once every carrier operator has decayed to silence, or after O3M_RELEASE_SECONDS). */
int o3m_release(Opl3Machine* m);
/* Silences a tracker channel at the chip output (its voices' output enables), the player is untouched. */
int o3m_mute(Opl3Machine* m, int channel, int muted);
void o3m_position(const Opl3Machine* m, int* order, int* row, int* tick);
/* One tick: float stereo frames into out (<= O3M_MAX_TICK_FRAMES); 0 once a released audition ended. */
int o3m_tick(Opl3Machine* m, float* out);

/* ---- diagnostics and test hooks ---- */
const O3Player* o3m_player(const Opl3Machine* m); /* write[] = the player writes of the last tick (or start) */
uint32_t o3m_ticks(const Opl3Machine* m);         /* ticks since start/audition, seek excluded */
int o3m_native_samples(const Opl3Machine* m);     /* native chip samples of the last tick */

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_OPL3_MACHINE_H_ */
