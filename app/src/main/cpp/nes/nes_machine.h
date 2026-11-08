/* One emulated NTSC NES (6502 core m6502.h with BCD disabled + the 2A03 APU in nes_apu.c)
 * running the pinned FamiStudio sound engine 4.5.3 player in FamiTracker tempo
 * (generated/nes_player_image.h).
 *
 * CPU address space:
 *   $0000-$07FF  RAM (mirrored to $1FFF): engine zero page/variables, stack, audition streams at
 *                NES_AUDITION_RAM
 *   $4000-$4017  APU (writes timestamped to the CPU cycle), $4015 status
 *   $8000-...    player: NSF-style entry table + engine code and tables
 *   NES_SONG_BASE-NES_SONG_END  song region: the image's FamiStudio music data
 *   NES_SAMPLE_BASE-$FFF9  sample region: the image's DPCM samples (the DMC reads them by DMA)
 *   $FFFA-$FFFF  vectors -> NES_PLAYER_TRAP (an interrupt or BRK is a driver fault)
 * Everything else reads 0 and ignores writes.
 *
 * The host calls the player like an NSF player does: nes_player_init(song) once, then
 * nes_player_play at the start of every NTSC frame (29780.5 CPU cycles: 29780 and 29781
 * alternating, 60.0988 Hz). One tick = one frame. Called routines return to NES_PLAYER_IDLE; the
 * machine stops at that fetch. Faults: an undocumented/JAM/BRK opcode fetch, a fetch of
 * NES_PLAYER_TRAP, or a call that runs longer than one frame.
 *
 * Rows: in FamiTracker tempo the engine starts a row on a frame when famistudio_tempo_acc_hi >=
 * famistudio_song_speed (it then subtracts the speed; every frame adds the tempo step, 256 per frame
 * at tempo 150). The machine reads that condition before each frame, so position, seek and live
 * edits are exact rows from engine state; orders come from the image (nes_image.h).
 *
 * Images are loaded with nm_load_image (nes_image.h); everything here takes loaded images. No
 * allocation after nm_create. Threading: one thread at a time. */
#ifndef THORTRACKER_NES_MACHINE_H_
#define THORTRACKER_NES_MACHINE_H_

#include <stdint.h>

#include "nes_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define NM_MAX_SAMPLE_RATE 96000u
#define NM_MAX_TICK_FRAMES 1600u /* >= 29781 * 96000 / 1789773 + 1 */
#define NM_CPU_HZ 1789773u

typedef struct NesMachine NesMachine;

enum {
    NM_OK = 0,
    NM_ERR_IMAGE = -1, /* invalid image/arguments */
    NM_ERR_FAULT = -2, /* the driver faulted; silent until restarted */
};

enum {
    NM_SEEK_FROM_START = 0, /* replayed silently from the song start */
    NM_SEEK_FROM_ORDER = 1, /* budget exceeded: streams placed at the order start, its earlier rows replayed */
};

NesMachine* nm_create(uint32_t sample_rate);
void nm_destroy(NesMachine* m);

/* Power-on reset, load the image, init + play the song, then position the real engine so the next
 * tick plays (order, row): silent fast-forward from the song start for at most max_ticks frames
 * (*seek_mode = NM_SEEK_FROM_START); if that is not enough (Thor layout only) the channel streams
 * are placed at the order's start with the speed that order starts with, and its earlier rows are
 * replayed silently, again within max_ticks (NM_SEEK_FROM_ORDER). The DMC runs through the fast-
 * forward, so a sample started before the target is heard from its position there. NM_ERR_IMAGE for
 * a target outside the song or not reached. */
int nm_start(NesMachine* m, const NesImage* image, int order, int row, int max_ticks, int* seek_mode);

/* Live edit at a tick boundary (realtime-safe, bounded work). Thor layout to Thor layout: the
 * playing order/row is kept (clamped to the new song) and every channel's stream state is placed at
 * the same row of the new data (pending note delays and empty-run remainders kept); engine envelope
 * pointers move to the same directory entry (or an identical one), keeping their position when it is
 * still an instruction start; instrument table, DPCM list, tempo and a changed order-start speed are
 * re-read. Otherwise the region is copied (correct only when no data moved). Either way a sample the
 * DMC is playing continues if its bytes are unchanged in place, continues at the same position of an
 * identical sample of the new DPCM list if it moved, and stops otherwise. Ignored while faulted or
 * auditioning. */
void nm_apply_image(NesMachine* m, const NesImage* image);

/* Resets the machine to sound one held note through the engine with a one-note stream in RAM, using
 * the image's instruments and DPCM list (Thor layout). Channels 0..3: engine note 1..96 (absolute -
 * 11), instrument table index, fine pitch -128..127 (period offset, +lowers; pulses/triangle only).
 * Channel 4 (DPCM): `note` = DPCM list entry 1..mappings (the stream's note byte); instrument and fine
 * are ignored. */
int nm_audition(NesMachine* m, const NesImage* image, int channel, int note, int instrument, int fine);

/* Key-off of an audition: with a volume release point the engine releases the note (returns 1;
 * nm_tick returns 0 once the release tail has reached its final loop); otherwise, and always on the
 * DPCM channel, 0 (cut now: the host drops the machine). */
int nm_release(NesMachine* m);

/* Forces channel (0..4) silent in the APU mixer (state keeps running, so unmuting resumes
 * mid-note). */
int nm_mute_channel(NesMachine* m, int channel, int muted);

/* Order/row/frame-in-row the next tick plays, from engine state (tick 0 = the next tick starts the
 * row). Raw layout without timing: (0, rows since start, tick). */
void nm_position(const NesMachine* m, int* order, int* row, int* tick);

/* One NTSC frame: play routine + APU, 48 kHz-class float stereo (L = R) into out. Returns the
 * frame count (<= NM_MAX_TICK_FRAMES), 0 once a released audition has ended, or NM_ERR_FAULT. */
int nm_tick(NesMachine* m, float* out);
/* Same without synthesis (APU frame counter only); used for seeking. */
int nm_tick_silent(NesMachine* m);

/* Diagnostics / test hooks. */
uint64_t nm_driver_cycles(const NesMachine* m);    /* CPU cycles inside player calls since start */
uint32_t nm_last_call_cycles(const NesMachine* m); /* cycles of the last player call */
uint32_t nm_frames(const NesMachine* m);           /* ticks since start (incl. seek) */
void nm_set_apu_logging(NesMachine* m, int enabled);
/* (address low byte, value) pairs of APU writes since the last call, at most `capacity` pairs. */
int nm_apu_log(NesMachine* m, uint8_t* pairs, int capacity);
uint8_t nm_peek(NesMachine* m, uint16_t addr);
void nm_poke(NesMachine* m, uint16_t addr, uint8_t value); /* RAM/PRG, for fault tests */
/* Calls a routine with A, returning NM_OK or NM_ERR_FAULT (test hook). */
int nm_call(NesMachine* m, uint16_t addr, uint8_t a);
/* The APU (tests read channel periods and levels). */
const struct NesApu* nm_apu(const NesMachine* m);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_NES_MACHINE_H_ */
