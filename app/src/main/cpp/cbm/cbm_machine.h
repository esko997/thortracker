/* The VIC-20 / C16-Plus/4 music machine: Thor Tracker's player (cbm_player.h) driving the VIC's sound unit (cbm_vic.h,
 * clocked in phi2 cycles) or the TED's (cbm_ted.h, clocked in single clocks, its D/A samples streamed from the TED
 * timer 1 interrupt), output resampled band-limited to the host rate with blip_buf (the VIC also through its board's
 * output stage). Tick k (from the start or audition reset) spans timeline cycles floor(k F / N) .. floor((k + 1) F / N)
 * (F = the variant's frame in cycles, N = multispeed; a live multispeed or video change rebases from the next tick):
 * the player's batch is written at the tick's first cycle, each timer interrupt inside the tick at its cycle (the
 * interrupt after the tick's batch when they share a cycle). The TED timer interrupts every 64 + 4 T single clocks,
 * the first 64 + 4 T after the reset; a new T applies from the next reload. Behaviour (seek, live edits, audition,
 * mute) is specified in docs/AUDIO_ENGINE.md, "VIC-20 and C16/Plus/4". No allocation after cbm_create. One thread at
 * a time. */
#ifndef THORTRACKER_CBM_MACHINE_H_
#define THORTRACKER_CBM_MACHINE_H_

#include <stdint.h>

#include "cbm_image.h"
#include "cbm_player.h"

#ifdef __cplusplus
extern "C" {
#endif

#define CBM_MAX_SAMPLE_RATE 96000u
#define CBM_MAX_TICK_FRAMES 1930u  /* TED PAL at N = 1, 96 kHz: 17784 / 886723.75 s = 1925.4 frames, + margin */
#define CBM_SEEK_TICKS 3600        /* the suggested start() budget of clocked ticks (72 s PAL) */
#define CBM_RELEASE_SECONDS 10     /* a released audition ends after this long at the latest */

enum { CBM_OK = 0, CBM_ERR_IMAGE = -1, CBM_ERR_FAULT = -2 };
enum { CBM_SEEK_FROM_START = 0, CBM_SEEK_FROM_ORDER = 1 };
/* Trace batch kinds (the fixture's bytes). */
enum { CBM_TRACE_RESET = 'R', CBM_TRACE_TICK = 'T', CBM_TRACE_TIMER = 'I', CBM_TRACE_OFF = 'O' };

/* Timeline frame (cycles) and clock (Hz) of a variant (CB_VARIANT_*). */
extern const int CBM_FRAME_CYCLES[4];   /* 22152, 16965, 17784, 14934 */
extern const double CBM_CLOCK_HZ[4];    /* 17734475 / 16, 315000000 / 308, 17734475 / 20, 315000000 / 352 */

typedef struct CbmMachine CbmMachine;

/* Called with every batch the machine sends to the chip, in order: kind CBM_TRACE_*; `index` = the tick's index since
 * the reset (T; seek ticks included), the interrupt's index since the reset counting every interrupt (I), 0 (R, O);
 * `cycle` = timeline cycles since the last start/audition reset; (order, row, tick) = the player's position before the
 * tick (T) or now; `w`/`n` = the player's writes (the interrupts' batches may be empty). Mute toggles are not traced. */
typedef void (*CbmTraceFn)(void* ctx, int kind, int64_t index, int64_t cycle, int order, int row, int tick,
                           const CbWrite* w, int n);

/* `chip` = CB_CHIP_VIC or CB_CHIP_TED: the machine plays only that chip's images. */
CbmMachine* cbm_create(uint32_t sample_rate, int chip);
void cbm_destroy(CbmMachine* m);

/* The player alone finds the ticks straight play needs to reach (order, row, 0); if at most max_ticks, the machine
 * resets and plays them with the chip unit, the timer, blip_buf and the VIC output stage clocked exactly as audible
 * playback, output discarded (CBM_SEEK_FROM_START). Otherwise the player is placed at the order's start with the speed
 * straight play starts it with and the order's earlier rows replay the same clocked way (CBM_SEEK_FROM_ORDER); a row
 * behind a Dxx/Bxx of the same order is unreachable (CBM_ERR_IMAGE). */
int cbm_start(CbmMachine* m, const CbmImage* image, int order, int row, int max_ticks, int* seek_mode);
/* Live edit at a tick boundary (realtime-safe): the song continues at the same (order, row) in the new data; a new
 * multispeed or video standard applies from the next tick. Images of the other chip are ignored. */
void cbm_apply_image(CbmMachine* m, const CbmImage* image);
/* One held note: channel of the chip, note inside the lane's range (absolute: 0 = C-0, 57 = A-4; VIC noise lane: the
 * rate 0..127), instrument slot allowed on the lane, fine -128..127 (1/64 semitone). */
int cbm_audition(CbmMachine* m, const CbmImage* image, int channel, int note, int slot, int fine);
/* OFF on the audition note, its writes sent at once: 0 = silent now (no tick yet, or the note stopped), 1 = release
 * phase (cbm_tick returns 0 once the note stopped, its wave envelope sits at its last step with value 0 written, or
 * the written volume is 0 with the owner's volume envelope at its last step, or CBM_RELEASE_SECONDS after the
 * release). */
int cbm_release(CbmMachine* m);
/* Leaves a channel out of the chip's output (VIC: its voice; TED: voice 1, voice 2, or the DAC lane's D/A output);
 * every counter, shift register, LFSR, decay and phase keeps running and the player is untouched. start() and
 * audition() clear every mute. */
int cbm_mute(CbmMachine* m, int channel, int muted);
void cbm_position(const CbmMachine* m, int* order, int* row, int* tick);
/* One tick: float stereo frames into out (<= CBM_MAX_TICK_FRAMES); 0 once an audition ended. */
int cbm_tick(CbmMachine* m, float* out);

/* ---- diagnostics and test hooks ---- */
const CbPlayer* cbm_player(const CbmMachine* m);
uint32_t cbm_ticks(const CbmMachine* m);           /* ticks since start/audition, seek excluded */
int cbm_timer_interrupts(const CbmMachine* m);     /* timer interrupts of the last tick */
int64_t cbm_cycle(const CbmMachine* m);            /* timeline cycle at which the next tick starts */
void cbm_set_trace(CbmMachine* m, CbmTraceFn fn, void* ctx); /* NULL = off; kept across start/audition */
/* The chip unit's state that muting must not change (VIC: voices' counters, registers, shift registers and outputs,
 * LFSR, volume; TED: registers, phase, counters, reloads, overflows, decays, states, noise register, prvCycleCount,
 * flags, volume, the unmuted outputs) as words; returns the count (<= 32). */
int cbm_chip_state(const CbmMachine* m, uint32_t* out);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_CBM_MACHINE_H_ */
