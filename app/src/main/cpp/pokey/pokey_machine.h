/* The POKEY music machine: Thor Tracker's player (pokey_player.h) driving the cycle-exact POKEY model (pokey_chip.h)
 * at the Atari's machine clock (NTSC 1.79 MHz, PAL 1.77 MHz). One tick = one video frame / multispeed (NTSC 29868,
 * PAL 35568 cycles at N = 1); the tick's batch is written at its first cycle, the chip then runs to the tick's end and
 * every timer interrupt it raises runs the player's sample routine (pkp_irq) at that cycle. The four channel levels
 * go through Altirra's nonlinear mix into blip_buf (machine clock -> host rate), then Altirra's AC coupling and
 * amplifier clamp per host sample; mono, duplicated to both host channels. Behaviour (seek, live edits, audition,
 * mute) is specified in docs/AUDIO_ENGINE.md, "POKEY". No allocation after pkm_create. One thread at a time. */
#ifndef THORTRACKER_POKEY_MACHINE_H_
#define THORTRACKER_POKEY_MACHINE_H_

#include <stdint.h>

#include "pokey_chip.h"
#include "pokey_image.h"
#include "pokey_player.h"

#ifdef __cplusplus
extern "C" {
#endif

#define PKM_MAX_SAMPLE_RATE 96000u
#define PKM_MAX_TICK_FRAMES 1928u /* PAL at N = 1: 35568 cycles of 1773447.5 Hz at 96 kHz = 1925.3 frames */
#define PKM_RELEASE_SECONDS 10    /* a released audition ends after this long even if its voice never falls silent */
#define PKM_MIX_SCALE 16384       /* mix level 1.0 in blip_buf units */

enum { PKM_OK = 0, PKM_ERR_IMAGE = -1, PKM_ERR_FAULT = -2 };
enum { PKM_SEEK_FROM_START = 0, PKM_SEEK_FROM_ORDER = 1 };

typedef struct PokeyMachine PokeyMachine;

PokeyMachine* pkm_create(uint32_t sample_rate);
void pkm_destroy(PokeyMachine* m);

/* The player runs alone from the song start to count the ticks until the next tick starts (order, row) - at most
 * max_ticks (PKM_SEEK_FROM_START); beyond, from the order's row 0 with the order's start state
 * (PKM_SEEK_FROM_ORDER). Then the machine is reset as a fresh one and every one of those ticks runs as pkm_tick runs
 * it (chip, interrupts, mix, blip_buf, coupling), the frames discarded: the output is straight play's. */
int pkm_start(PokeyMachine* m, const PokeyImage* image, int order, int row, int max_ticks, int* seek_mode);
/* Live edit at a tick boundary (realtime-safe): the song continues at the same (order, row) in the new data. */
void pkm_apply_image(PokeyMachine* m, const PokeyImage* image);
/* One held note: lane 0..3, note 0..107 (absolute: 57 = A-4), instrument slot 1..64, fine -128..127 (1/64 semitone). */
int pkm_audition(PokeyMachine* m, const PokeyImage* image, int lane, int note, int slot, int fine);
/* OFF of the audition note: 0 = silent now (no tick yet, or already silent), 1 = release phase (pkm_tick returns 0
 * once the voice is silent for good, or after PKM_RELEASE_SECONDS). */
int pkm_release(PokeyMachine* m);
/* Leaves the POKEY channels a lane's voice holds out of the mix; chip and player are untouched. */
int pkm_mute(PokeyMachine* m, int lane, int muted);
void pkm_position(const PokeyMachine* m, int* order, int* row, int* tick);
/* One tick: float stereo frames into out (<= PKM_MAX_TICK_FRAMES); 0 once a released audition ended. */
int pkm_tick(PokeyMachine* m, float* out);

/* ---- diagnostics and test hooks ---- */

/* A batch the machine wrote to the chip: a tick's (irq = 0; order/row/tick = the position before the tick) or a timer
 * interrupt's (irq = 1, timers = PK_IRQ_* that fired). cycle = absolute chip cycle (tick 0 of start at cycle 0). */
typedef struct PkmEvent {
    int irq;
    uint64_t cycle;
    int timers;
    int order, row, tick;
    const PkWrite* writes;
    int count;
} PkmEvent;
typedef void (*PkmTraceFn)(void* ctx, const PkmEvent* event);
/* Called for every batch of every following tick (not for the ticks start() runs to reach its position). */
void pkm_set_trace(PokeyMachine* m, PkmTraceFn fn, void* ctx);
const PkPlayer* pkm_player(const PokeyMachine* m);
const PokeyChip* pkm_chip(const PokeyMachine* m);
uint32_t pkm_ticks(const PokeyMachine* m);     /* ticks since start/audition, seek excluded */
uint32_t pkm_tick_cycles(const PokeyMachine* m); /* machine cycles of the last tick */

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_POKEY_MACHINE_H_ */
