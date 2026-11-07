/* One emulated NTSC NES (6502 core m6502.h with BCD disabled + the 2A03 APU in nes_apu.c)
 * running the pinned FamiStudio sound engine 4.5.3 player (generated/nes_player_image.h).
 *
 * CPU address space:
 *   $0000-$07FF  RAM (mirrored to $1FFF): engine zero page/variables, stack
 *   $4000-$4017  APU (writes timestamped to the CPU cycle), $4015 status
 *   $8000-$8AE6  player: NSF-style entry table + engine code and tables
 *   NES_SONG_BASE-$FFF9  song region: the image's FamiStudio music data
 *   $FFFA-$FFFF  vectors -> NES_PLAYER_TRAP (an interrupt or BRK is a driver fault)
 * Everything else reads 0 and ignores writes.
 *
 * The host calls the player like an NSF player does: nes_player_init(song) once, then
 * nes_player_play at the start of every NTSC frame (29780.5 CPU cycles: 29780 and 29781
 * alternating, 60.0988 Hz). Called routines return to NES_PLAYER_IDLE; the machine stops at that
 * fetch. Faults: an undocumented/JAM/BRK opcode fetch, a fetch of NES_PLAYER_TRAP, or a call that
 * runs longer than one frame.
 *
 * Images are produced by the frontend's lowering (step 5) in the layout NesImage describes; this
 * file validates the header and FamiStudio pointers and copies bytes. No allocation after
 * nm_create. Threading: one thread at a time. */
#ifndef THORTRACKER_NES_MACHINE_H_
#define THORTRACKER_NES_MACHINE_H_

#include <stdint.h>

#include "generated/nes_player_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define NM_MAX_SAMPLE_RATE 96000u
#define NM_MAX_TICK_FRAMES 1600u /* >= 29781 * 96000 / 1789773 + 1 */
#define NM_CPU_HZ 1789773u
#define NM_SONG_SIZE (NES_SONG_END - NES_SONG_BASE)
#define NM_HEADER_SIZE 32u
#define NM_MAGIC 0x534E5454u /* "TTNS" little-endian */
#define NM_CHANNELS 5          /* pulse 1, pulse 2, triangle, noise, DPCM (FamiStudio order) */

/* A lowered song image, little-endian:
 *   0  u32 magic "TTNS"
 *   4  u32 player id (NES_PLAYER_ID: the image was lowered for this engine build/config)
 *   8  u16 song base (NES_SONG_BASE)        10 u16 data size (bytes of FamiStudio data used)
 *   12 u8  song index                        13 u8  frames per row (0 = timing unknown)
 *   14 u16 rows per order                    16 u8  order count   17 u8 loop order
 *   18..31 zero
 *   32 song region: NM_SONG_SIZE bytes placed at NES_SONG_BASE (zero after data size)
 * Timing (frames per row, rows per order, order count, loop order) describes a FamiStudio-tempo
 * song with a constant groove; it is only used for position() and start() targets. */
typedef struct NesImage {
    uint8_t bytes[NM_HEADER_SIZE + NM_SONG_SIZE];
} NesImage;

typedef struct NesMachine NesMachine;

enum {
    NM_OK = 0,
    NM_ERR_IMAGE = -1, /* invalid image/arguments */
    NM_ERR_FAULT = -2, /* the driver faulted; silent until restarted */
};

NesMachine* nm_create(uint32_t sample_rate);
void nm_destroy(NesMachine* m);

/* Validates the header, the FamiStudio music data header and channel pointers. Pure. */
int nm_validate(const NesImage* image);

/* Power-on reset, load the image, init + play the song, then silently fast-forward the real
 * engine to (order, row) using the image timing (exactly (order*rows_per_order + row) *
 * frames_per_row frames). Fails with NM_ERR_IMAGE if the target is beyond the song, needs more
 * than max_ticks frames, or (order, row) != (0, 0) without timing. */
int nm_start(NesMachine* m, const NesImage* image, int order, int row, int max_ticks);

/* Copies the song region and timing of a compatible image (same player id/base) into the running
 * machine at a frame boundary. The engine keeps its stream pointers: safe for edits that keep
 * every data offset (note/instrument/envelope values); a relayout needs nm_start. */
void nm_apply_image(NesMachine* m, const NesImage* image);

/* Forces channel (0..4) silent in the APU mixer (state keeps running, so unmuting resumes
 * mid-note). */
int nm_mute_channel(NesMachine* m, int channel, int muted);

/* Order/row/frame-in-row the next tick plays (from the frame counter and the image timing,
 * wrapped at the loop); without timing: (0, 0, frames since start). */
void nm_position(const NesMachine* m, int* order, int* row, int* tick);

/* One NTSC frame: play routine + APU, 48 kHz-class float stereo (L = R) into out. Returns the
 * frame count (<= NM_MAX_TICK_FRAMES) or NM_ERR_FAULT. */
int nm_tick(NesMachine* m, float* out);
/* Same without synthesis (APU frame counter only); used for seeking. */
int nm_tick_silent(NesMachine* m);

/* Diagnostics / test hooks. */
uint64_t nm_driver_cycles(const NesMachine* m);    /* CPU cycles inside player calls since start */
uint32_t nm_last_call_cycles(const NesMachine* m); /* cycles of the last player call */
uint32_t nm_frames(const NesMachine* m);           /* ticks since song start (incl. seek) */
void nm_set_apu_logging(NesMachine* m, int enabled);
/* (address low byte, value) pairs of APU writes since the last call, at most `capacity` pairs. */
int nm_apu_log(NesMachine* m, uint8_t* pairs, int capacity);
uint8_t nm_peek(NesMachine* m, uint16_t addr);
void nm_poke(NesMachine* m, uint16_t addr, uint8_t value); /* RAM/PRG, for fault tests */
/* Calls a routine with A, returning NM_OK or NM_ERR_FAULT (test hook). */
int nm_call(NesMachine* m, uint16_t addr, uint8_t a);
/* The APU (tests read channel periods and levels). */
const struct NesApu* nm_apu(const NesMachine* m);
/* Builds an image around raw song-region bytes (tests and tools). */
void nm_build_image(NesImage* image, const uint8_t* song, uint32_t size, int song_index, int frames_per_row,
                    int rows_per_order, int order_count, int loop_order);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_NES_MACHINE_H_ */
