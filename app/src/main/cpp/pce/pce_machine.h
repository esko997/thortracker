/* The PC Engine music machine: Thor Tracker's player (pce_player.h) driving Mednafen's HuC6280 PSG (pce_psg.h) in
 * CPU cycles (78750000 / 11 = 7159090.91 Hz), its DDA samples streamed from the HuC6280 timer's interrupt, output
 * resampled band-limited to the host rate with blip_buf. Tick k (from the start or audition reset) spans cycles
 * floor(k 1312500 / (11 N)) .. floor((k + 1) 1312500 / (11 N)) (N = multispeed): the player's batch is written at the
 * tick's first cycle, each timer interrupt inside the tick at its cycle (the interrupt batch after the tick's batch
 * when they share a cycle). The timer interrupts every 1024 (R + 1) cycles, the first 1024 (R + 1) cycles after the
 * reset; a new reload value R applies from the next reload (after the next interrupt). Behaviour (seek, live edits,
 * audition, mute) is specified in docs/AUDIO_ENGINE.md, "PC Engine". No allocation after pcm_create. One thread at a
 * time. */
#ifndef THORTRACKER_PCE_MACHINE_H_
#define THORTRACKER_PCE_MACHINE_H_

#include <stdint.h>

#include "pce_image.h"
#include "pce_player.h"

#ifdef __cplusplus
extern "C" {
#endif

#define PCM_MAX_SAMPLE_RATE 96000u
#define PCM_MAX_TICK_FRAMES 1601u  /* 96000 / 60 + 1 */
#define PCM_SEEK_TICKS 3600        /* the suggested start() budget of clocked ticks */
#define PCM_RELEASE_SECONDS 10     /* a released audition ends after this long at the latest */
#define PCM_TIMER_DIVIDER 1024     /* CPU cycles per timer count */
#define PCM_CYCLES_NUM 1312500     /* tick cycles = 1312500 / (11 N) = 78750000 / 11 / 60 / N */
#define PCM_CPU_HZ_NUM 78750000    /* CPU clock = 78750000 / 11 Hz (NTSC master 236250000 / 11, divided by 3) */
#define PCM_CPU_HZ_DEN 11

enum { PCM_OK = 0, PCM_ERR_IMAGE = -1, PCM_ERR_FAULT = -2 };
enum { PCM_SEEK_FROM_START = 0, PCM_SEEK_FROM_ORDER = 1 };
/* Trace batch kinds. */
enum { PCM_TRACE_TICK = 0, PCM_TRACE_TIMER = 1, PCM_TRACE_OFF = 2 };

typedef struct PceMachine PceMachine;

/* Called with every batch the machine sends to the chip (also the reset batch: kind 0 at cycle 0), in order: `cycle`
 * = CPU cycles since the last start/audition reset (seek ticks included), `w`/`n` = the player's writes as the player
 * made them (before mute masking). Mute toggles are not traced. */
typedef void (*PcmTraceFn)(void* ctx, int64_t cycle, int kind, const PcWrite* w, int n);

PceMachine* pcm_create(uint32_t sample_rate);
void pcm_destroy(PceMachine* m);

/* Chip reset, then the player alone finds the ticks straight play needs to reach (order, row, 0); if at most
 * max_ticks, the machine resets and plays them with the chip, the timer and the resampler clocked exactly as audible
 * playback, output discarded (PCM_SEEK_FROM_START). Otherwise the player is placed at the order's start with the
 * speed straight play starts it with and the order's earlier rows replay the same clocked way (PCM_SEEK_FROM_ORDER);
 * a row behind a Dxx/Bxx of the same order is unreachable (PCM_ERR_IMAGE). */
int pcm_start(PceMachine* m, const PceImage* image, int order, int row, int max_ticks, int* seek_mode);
/* Live edit at a tick boundary (realtime-safe): the song continues at the same (order, row) in the new data; a new
 * multispeed applies from the next tick. */
void pcm_apply_image(PceMachine* m, const PceImage* image);
/* One held note: channel 0..5, note PC_NOTE_MIN..PC_NOTE_MAX (absolute: 0 = C-0, 57 = A-4), instrument slot 1..64,
 * fine -128..127 (1/64 semitone). Noise instruments only on channels 4-5. */
int pcm_audition(PceMachine* m, const PceImage* image, int channel, int note, int slot, int fine);
/* OFF on the audition note, written at once: 0 = silent now (no tick yet, or the channel stopped), 1 = release phase
 * (pcm_tick returns 0 once the channel stopped, or its written volume is 0 with the volume envelope at its last step,
 * or after PCM_RELEASE_SECONDS). */
int pcm_release(PceMachine* m);
/* Silences a channel at the chip: every R4 the channel receives has its volume bits 0-4 forced to 0 (toggling writes
 * its R4 from the player's shadow, masked or not, at the next tick's start and restores the select register). The
 * player is untouched. start() and audition() clear every mute. */
int pcm_mute(PceMachine* m, int channel, int muted);
void pcm_position(const PceMachine* m, int* order, int* row, int* tick);
/* One tick: float stereo frames into out (<= PCM_MAX_TICK_FRAMES); 0 once an audition ended (its channel stopped: a
 * one-shot sample's end, released or not; or released as pcm_release describes). */
int pcm_tick(PceMachine* m, float* out);

/* ---- diagnostics and test hooks ---- */
const PcPlayer* pcm_player(const PceMachine* m); /* write[] = the last batch sent (the tick's or its last interrupt's) */
uint32_t pcm_ticks(const PceMachine* m);         /* ticks since start/audition, seek excluded */
int pcm_native_samples(const PceMachine* m);     /* 1789772.73 Hz native samples of the last tick */
int pcm_timer_interrupts(const PceMachine* m);   /* timer interrupts of the last tick */
void pcm_set_trace(PceMachine* m, PcmTraceFn fn, void* ctx); /* NULL = off; kept across start/audition */
uint32_t pcm_psg_register(const PceMachine* m, unsigned id); /* Mednafen PCE_PSG::GetRegister(id) (pce_psg.h) */

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_PCE_MACHINE_H_ */
