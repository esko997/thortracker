/* The AY music machine: Thor Tracker's player (ay_player.h) driving its AY-3-8910 / YM2149 core (ay_psg.h) as the
 * stock computer of one variant (ZX Spectrum 128, MSX, Atari ST, Amstrad CPC): the variant's chip, clock and frame
 * interrupt (the tick) and its output wiring, resampled band-limited to the host rate with blip_buf: the mono mix on
 * both host channels, or on the CPC its stereo socket (left A + B, right B + C).
 * A tick: the player runs once, its writes reach the chip at the tick's first step, the chip runs the tick's steps
 * (exact accumulation of the variant's master-clock ratio). Behaviour (seek, live edits, audition, mute) is
 * specified in docs/AUDIO_ENGINE.md, "AY-3-8910 / YM2149". No allocation after ay_machine_create. One thread at a
 * time. */
#ifndef THORTRACKER_AY_MACHINE_H_
#define THORTRACKER_AY_MACHINE_H_

#include <stdint.h>

#include "ay_image.h"
#include "ay_player.h"
#include "ay_psg.h"

#ifdef __cplusplus
extern "C" {
#endif

#define AY_SEEK_TICKS 12000 /* the hosts' seek budget (PlaybackEngine.seekTicks) */
#define AYM_MAX_SAMPLE_RATE 96000u
#define AYM_MAX_TICK_FRAMES 1921u /* frames per tick: 48 kHz ZX 959-960, MSX 800-802, ST 958-959, CPC 958-959; 96 kHz ZX 1918-1920, MSX 1601-1603, ST 1917-1918, CPC 1916-1917 */
#define AYM_RELEASE_SECONDS 10    /* a released audition ends after this long */

enum { AYM_OK = 0, AYM_ERR_IMAGE = -1, AYM_ERR_FAULT = -2 };
enum { AYM_SEEK_FROM_START = 0, AYM_SEEK_FROM_ORDER = 1 };

typedef struct AyMachine AyMachine;

/* A machine of one variant (AY_VARIANT_*) at `sample_rate` (8000..96000); NULL on failure. */
AyMachine* ay_machine_create(uint32_t sample_rate, int variant);
void ay_machine_destroy(AyMachine* m);

/* Machine reset (chip power-on), then the player runs from the song start until the next tick starts (order, row) -
 * at most max_ticks ticks (AYM_SEEK_FROM_START) - with every tick's writes applied to the chip and the chip silently
 * advanced through every step: the chip state is straight play's exactly. Beyond the budget: reset, the player
 * placed at the order's row 0 with its start speed, the order's earlier rows replayed the same way
 * (AYM_SEEK_FROM_ORDER). Images of another variant are rejected. */
int ay_machine_start(AyMachine* m, const AyImage* image, int order, int row, int max_ticks, int* seek_mode);
/* Live edit at a tick boundary (realtime-safe): the song continues at the same (order, row) in the new data; a
 * changed variant byte is ignored (the machine is its variant). Ignored while auditioning. */
void ay_machine_apply_image(AyMachine* m, const AyImage* image);
/* One held note: channel 0..2, note 0..95 (absolute: 0 = C-0, 57 = A-4), instrument slot 1..64, fine -128..127
 * (1/64 semitone). */
int ay_machine_audition(AyMachine* m, const AyImage* image, int channel, int note, int slot, int fine);
/* Key off the audition note: 0 = silent now (no tick yet, or the phase is already off), 1 = release phase
 * (ay_machine_tick returns 0 once the phase is off, or after AYM_RELEASE_SECONDS). */
int ay_machine_release(AyMachine* m);
/* Leaves a channel (0..2) out of the chip's mix; the player and the chip run on. */
int ay_machine_mute(AyMachine* m, int channel, int muted);
void ay_machine_position(const AyMachine* m, int* order, int* row, int* tick);
/* One tick: float stereo frames into out (<= AYM_MAX_TICK_FRAMES; L = R except on the CPC); 0 once a released audition
 * ended. */
int ay_machine_tick(AyMachine* m, float* out);

/* ---- diagnostics and test hooks ---- */
const AyPlayer* ay_machine_player(const AyMachine* m); /* write[] = the player writes of the last tick */
const AyPsg* ay_machine_chip(const AyMachine* m);
int ay_machine_variant(const AyMachine* m);
uint32_t ay_machine_ticks(const AyMachine* m);         /* ticks since start/audition, seek excluded */
uint32_t ay_machine_steps(const AyMachine* m);         /* chip steps of the last tick */
/* Runs one tick exactly as ay_machine_tick but advances the chip silently (no output, no blip work): the seek's
 * tick. Returns the steps run. */
uint32_t ay_machine_tick_silent(AyMachine* m);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_AY_MACHINE_H_ */
