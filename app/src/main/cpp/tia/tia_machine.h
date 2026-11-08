/* The Atari 2600 music machine: Thor Tracker's player (tia_player.h) driving the TIA's two audio channels (Stella 7.0's
 * AudioChannel, tia_stella.h) on a colour-clock timeline, the mono output resampled band-limited to the host rate with
 * blip_buf. One machine plays one region (TI_REGION_NTSC or TI_REGION_PAL), fixed at tim_create.
 *
 * Timeline (colour clocks C: 39375000 / 11 Hz NTSC, 3546894 Hz PAL, counted as int64 from the start/audition reset):
 * tick k spans clocks k F .. (k + 1) F, F = lines x 228 (262 NTSC, 312 PAL). The player's batch is written at the
 * tick's clock 0 (before any audio event of that clock). In every scanline (228 clocks) both channels run phase 0 at
 * line clocks 9 and 81 and phase 1 at 37 and 149 (Stella 7.0's Audio::tick positions). The mono level L =
 * TIM_MIX[v0 + v1] (v = a channel's actualVolume(), 0 when muted; Stella's mixing table) is recomputed after the batch
 * (blip delta at the tick's clock 0) and after each phase 1 (delta at line clock 38 / 150), one blip frame per tick
 * (blip_buf's ~15 Hz high-pass removes the DC); float = sample / 32768 on both stereo sides. Behaviour (seek, live
 * edits, audition, mute) is specified in docs/AUDIO_ENGINE.md, "Atari 2600". No allocation after tim_create. One
 * thread at a time. */
#ifndef THORTRACKER_TIA_MACHINE_H_
#define THORTRACKER_TIA_MACHINE_H_

#include <stdint.h>

#include "tia_image.h"
#include "tia_player.h"

#ifdef __cplusplus
extern "C" {
#endif

#define TIM_MAX_SAMPLE_RATE 96000u
#define TIM_MAX_TICK_FRAMES 1927u /* PAL at 96 kHz: 96000 x 71136 / 3546894 = 1925.4, + margin */
#define TIM_SEEK_TICKS 7200 /* the suggested start() budget of clocked ticks (2 min NTSC) */
#define TIM_RELEASE_SECONDS 10 /* a released audition ends after this long at the latest */
#define TIM_CLOCKS_PER_LINE 228 /* colour clocks per scanline */
#define TIM_AUDIO_DIVIDER 114 /* colour clocks per audio clock (two audio clocks per line) */
#define TIM_LINES_NTSC 262
#define TIM_LINES_PAL 312
#define TIM_CLOCK_NTSC_NUM 39375000 /* NTSC colour clock = 39375000 / 11 Hz */
#define TIM_CLOCK_NTSC_DEN 11
#define TIM_CLOCK_PAL_NUM 3546894 /* PAL colour clock = 3546894 / 1 Hz */
#define TIM_CLOCK_PAL_DEN 1
#define TIM_PHASE0_A 9 /* line clocks of the audio events */
#define TIM_PHASE1_A 37
#define TIM_PHASE0_B 81
#define TIM_PHASE1_B 149

/* floor(32767 (s / 30) (30 + 30) / (30 + s)), s = v0 + v1 = 0..30, evaluated in Stella's order
 * (0x7fff * s / 30.0 * 60.0 / (30.0 + s), = the exact rational floor). */
extern const int16_t TIM_MIX[31];

enum { TIM_OK = 0, TIM_ERR_IMAGE = -1, TIM_ERR_FAULT = -2 };
enum { TIM_SEEK_FROM_START = 0, TIM_SEEK_FROM_ORDER = 1 };
/* Trace batch kinds. */
enum { TIM_TRACE_TICK = 0, TIM_TRACE_OFF = 1 };

typedef struct TiaMachine TiaMachine;

/* Called with every batch the machine sends to the chip, in order (the reset batch as kind TIM_TRACE_TICK at clock 0):
 * `clock` = colour clocks since the last start/audition reset (seek ticks included), `w`/`n` = the player's writes.
 * Mute toggles are not traced (they never write). */
typedef void (*TimTraceFn)(void* ctx, int64_t clock, int kind, const TiWrite* w, int n);
/* Called after every phase-1 event with its colour clock (line clock 37 or 149 of its line, from the reset) and both
 * channels' actualVolume() as the chip has it (unmuted). */
typedef void (*TimLevelFn)(void* ctx, int64_t clock, uint8_t v0, uint8_t v1);

/* NULL for an unsupported rate (8000..TIM_MAX_SAMPLE_RATE) or region, or when allocation fails. */
TiaMachine* tim_create(uint32_t sample_rate, int region);
void tim_destroy(TiaMachine* m);

/* Chip reset, then the player alone finds the ticks straight play needs to reach (order, row, 0); if at most
 * max_ticks, the machine resets and plays them with the chip and the resampler clocked exactly as audible playback,
 * output discarded (TIM_SEEK_FROM_START). Otherwise the player is placed at the order's start with the speed straight
 * play starts it with and the order's earlier rows replay the same clocked way (TIM_SEEK_FROM_ORDER); a row behind a
 * Dxx/Bxx of the same order is unreachable (TIM_ERR_IMAGE). An image of the other region, an unloaded image or a
 * position outside the song is TIM_ERR_IMAGE. */
int tim_start(TiaMachine* m, const TiaImage* image, int order, int row, int max_ticks, int* seek_mode);
/* Live edit at a tick boundary (realtime-safe): the song continues at the same (order, row) in the new data. Ignored
 * for an unloaded image, an image of the other region, before a start and during an audition. */
void tim_apply_image(TiaMachine* m, const TiaImage* image);
/* One held note: channel 0..1, note TI_NOTE_MIN..TI_NOTE_MAX (absolute: 0 = C-0, 57 = A-4), instrument slot 1..64
 * (defined), fine -128..127 (1/64 semitone). TIM_ERR_IMAGE otherwise (or for an image of the other region). */
int tim_audition(TiaMachine* m, const TiaImage* image, int channel, int note, int slot, int fine);
/* OFF on the audition note, written to the chip at once: 0 = silent now (no tick yet: the audition is over; or the
 * channel stopped), 1 = release phase (tim_tick returns 0 once the channel stopped, or its written AUDV is 0 with
 * its volume envelope at its last step, or TIM_RELEASE_SECONDS after the release). */
int tim_release(TiaMachine* m);
/* The mix takes a muted channel's level as 0 from the next tick's clock 0; chip, registers and player untouched.
 * start() and audition() clear every mute. TIM_ERR_IMAGE for a channel outside 0..1. */
int tim_mute(TiaMachine* m, int channel, int muted);
void tim_position(const TiaMachine* m, int* order, int* row, int* tick);
/* One tick: float stereo frames into out (<= TIM_MAX_TICK_FRAMES); 0 when nothing is loaded or once an audition
 * ended. */
int tim_tick(TiaMachine* m, float* out);

/* ---- diagnostics and test hooks ---- */
const TiPlayer* tim_player(const TiaMachine* m); /* write[] = the last batch sent */
uint32_t tim_ticks(const TiaMachine* m);         /* ticks since start/audition, seek excluded */
int tim_frames(const TiaMachine* m);             /* host frames of the last tick */
int64_t tim_clock(const TiaMachine* m);          /* colour clock of the next tick's start, from the reset */
int tim_level(const TiaMachine* m);              /* the mono level L as last fed to blip_buf (0..32767) */
void tim_set_trace(TiaMachine* m, TimTraceFn fn, void* ctx);       /* NULL = off; kept across start/audition */
void tim_set_level_probe(TiaMachine* m, TimLevelFn fn, void* ctx); /* NULL = off; kept across start/audition */
/* Channel c's ten state bytes (tia_stella.h tis_state order). */
void tim_channel_state(const TiaMachine* m, int c, uint8_t out[10]);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_TIA_MACHINE_H_ */
