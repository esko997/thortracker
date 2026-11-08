/* One emulated PAL Commodore 64 for music: a 6510 (m6502.h, decimal mode on) with 64 KiB of RAM and
 * a MOS 6581/8580 SID (reSID 1.0 through sid_resid.h) at $D400, running the pinned GoatTracker 2.77
 * playroutine (generated/sid_player_image.h).
 *
 * Address space:
 *   $00FC-$00FD  player zero page          $0100-$01FF stack
 *   $0E00-$0EFF  audition orderlists and patterns (spare machines only)
 *   $0FF0        SID_PLAYER_IDLE: return address of host calls (the machine stops at its fetch)
 *   $1000-...    player (code and variables; parameter operands written from the image header)
 *   $1800-$CFFF  the image's region (fixed tables, orderlists, patterns)
 *   $D400-$D7FF  SID (registers mirrored every 32 bytes; writes timestamped to the CPU cycle)
 *   $FFFA-$FFFF  vectors -> SID_PLAYER_TRAP (an interrupt or BRK is a driver fault)
 * Other I/O reads 0 and ignores writes.
 *
 * The host calls the player like a PSID player does: init (A = 0) once, then play once per tick. One
 * tick = one play call = SID_PAL_FRAME_CYCLES cycles (50.1245 Hz) at speed 1; at speed multiplier N
 * (multispeed) SID_CIA_PAL_LATCH / N + 1 cycles, the CIA timer period gt2reloc's PSIDs program. The
 * SID is clocked cycle by cycle between the writes of a tick and resampled to the host rate.
 * Faults: an undocumented/JAM/BRK opcode fetch, a fetch of SID_PLAYER_TRAP, or a call longer than a tick.
 *
 * Rows: the player starts a row on channel 1 when its counter (mt_chncounter) counts from 1 to 0 in a
 * play call, except the first such call after init, which only loads the first patterns. The machine
 * reads that before each tick, so position, seek and the order fallback are exact rows from player
 * state; orders and rows per order come from the image (sid_image.h).
 *
 * Images are loaded with sm_load_image; everything here takes loaded images. No allocation after
 * sm_create. Threading: one thread at a time. */
#ifndef THORTRACKER_SID_MACHINE_H_
#define THORTRACKER_SID_MACHINE_H_

#include <stddef.h>
#include <stdint.h>

#include "sid_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define SM_MAX_SAMPLE_RATE 96000u
#define SM_MAX_TICK_FRAMES 1920u /* >= SID_PAL_FRAME_CYCLES * 96000 / 985248 + 1 */
#define SM_CPU_HZ SID_PAL_CPU_HZ

typedef struct SidMachine SidMachine;

enum {
    SM_OK = 0,
    SM_ERR_IMAGE = -1, /* invalid image/arguments */
    SM_ERR_FAULT = -2, /* the driver faulted; silent until restarted */
};

enum {
    SM_SEEK_FROM_START = 0, /* replayed silently from the song start */
    SM_SEEK_FROM_ORDER = 1, /* budget exceeded: orderlists placed at the order, its earlier rows replayed */
};

enum {
    SM_RESAMPLE = 0,    /* reSID SAMPLE_RESAMPLE (band-limited): the default */
    SM_INTERPOLATE = 1, /* reSID SAMPLE_INTERPOLATE (benchmarks) */
};

SidMachine* sm_create(uint32_t sample_rate);
SidMachine* sm_create_sampling(uint32_t sample_rate, int method);
void sm_destroy(SidMachine* m);

/* Power-on reset with the image's chip model, init, then position the player so the next tick plays
 * (order, row) by silently running it from the song start (the SID clocked, no samples) for at most
 * max_ticks ticks (*seek_mode = SM_SEEK_FROM_START); if that is not enough, the orderlists are placed at
 * the order with the tempo it starts with in straight play and its earlier rows are replayed silently
 * (SM_SEEK_FROM_ORDER). (0, 0) runs the player's start-up ticks silently: the next tick is row 0's
 * first. SM_ERR_IMAGE for a target outside the song or not reached. */
int sm_start(SidMachine* m, const SidImage* image, int order, int row, int max_ticks, int* seek_mode);

/* Live edit at a tick boundary: the song continues at the same (order, row) in the new image's data,
 * which may be laid out anew (realtime-safe, no allocation). Per channel the sequencer state (orderlist
 * entry, pattern, byte offset, packed rest, persisting instrument and command, a fetched note) is what
 * straight play of the new data has there; table pointers inside an instrument's moved program follow
 * it (same offset, by slot); straight play's tempo/funktempo at the playhead is set if the edit changed
 * it; chip model and speed multiplier follow the header. A position that no longer exists is clamped
 * to the last row of the last (or the current) order. Ignored while faulted or auditioning. */
void sm_apply_image(SidMachine* m, const SidImage* image);

/* Resets the machine to sound one held note through the player: channel 0..2, GoatTracker note 0..92
 * (C-0..G#7 = absolute note), player instrument number 1..instruments, fine 0 (the SID realises
 * tunings in the song's frequency table). The other channels play rests. */
int sm_audition(SidMachine* m, const SidImage* image, int channel, int note, int instrument, int fine);

/* Key-off of an audition: the player's key-off state (mt_chngate = $FE, as a key-off row sets it) from
 * the next tick; returns 1 (sm_tick returns 0 once the voice's envelope has reached 0), or 0 when the
 * voice is silent already or no tick has run (cut: the host drops the machine). */
int sm_release(SidMachine* m);

/* Leaves voice `channel` (0..2) out of the mix (it keeps running, so unmuting resumes mid-note). */
int sm_mute_channel(SidMachine* m, int channel, int muted);

/* Order/row/tick-in-row the next tick plays, from player state (tick 0 = the next tick starts the row). */
void sm_position(const SidMachine* m, int* order, int* row, int* tick);

/* One player tick: play routine + SID, float stereo (L = R) into out. Returns the frame count
 * (<= SM_MAX_TICK_FRAMES), 0 once a released audition has ended, or SM_ERR_FAULT. */
int sm_tick(SidMachine* m, float* out);
/* Same without samples (the SID is clocked); used for seeking. */
int sm_tick_silent(SidMachine* m);

/* ---- diagnostics and test hooks ----------------------------------------------------------- */

/* Power-on + init only (no fast-forward): the next tick is the player's first call, as in a PSID player. */
int sm_power_on(SidMachine* m, const SidImage* image);
/* Loads a PSID (v2, load address in the data or the header) and calls its init with A = start song - 1;
 * then ticks call its play address every PAL frame. Position is (0, 0, ticks). */
int sm_load_psid(SidMachine* m, const uint8_t* psid, size_t size, int model);
uint64_t sm_driver_cycles(const SidMachine* m);    /* CPU cycles inside player calls since start */
uint32_t sm_last_call_cycles(const SidMachine* m); /* cycles of the last player call */
uint32_t sm_max_call_cycles(const SidMachine* m);  /* longest player call since start */
uint32_t sm_ticks(const SidMachine* m);            /* ticks since start (incl. seek) */
uint32_t sm_tick_cycles(const SidMachine* m);      /* CPU cycles per tick */
void sm_set_logging(SidMachine* m, int enabled);
/* (register 0..31, value) pairs of SID writes since the last call, at most `capacity` pairs. */
int sm_log(SidMachine* m, uint8_t* pairs, int capacity);
uint8_t sm_peek(const SidMachine* m, uint16_t addr);
void sm_poke(SidMachine* m, uint16_t addr, uint8_t value);
int sm_envelope(SidMachine* m, int voice); /* reSID envelope counter 0..255 */

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_SID_MACHINE_H_ */
