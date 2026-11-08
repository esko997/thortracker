/* One emulated NTSC NES (6502 core m6502.h with BCD disabled + the 2A03 APU in nes_apu.c, + the Konami
 * VRC6 audio in nes_vrc6.c for the VRC6 build, the Konami VRC7 FM audio in nes_vrc7.c (emu2413) for the VRC7
 * build, or the Namco 163 audio in nes_n163.c for the N163 builds) running a pinned FamiStudio sound engine 4.5.3
 * player build in FamiTracker tempo (nes_player.h: nes_player_2a03, nes_player_vrc6, nes_player_vrc7 or
 * nes_player_n163[n - 1], fixed at nm_create). Only the build's own expansion unit is reset, clocked and mixed.
 *
 * CPU address space:
 *   $0000-$07FF  RAM (mirrored to $1FFF): engine zero page/variables, stack, audition streams at
 *                NES_AUDITION_RAM
 *   $4000-$4017  APU (writes timestamped to the CPU cycle), $4015 status
 *   $8000-...    player: NSF-style entry table + engine code and tables
 *   song base-NES_REGION_END  the image's region: FamiStudio music data from the player's song base up
 *                and its DPCM sample block (64-aligned, >= $C000) above it; the DMC reads samples by DMA
 *   $9000-$9003, $A000-$A002, $B000-$B002  VRC6 build: VRC6 audio register writes (timestamped); reads
 *                there return the ROM (song data) as on the cartridge
 *   $4800-$4FFF  N163 builds: the N163 data port (writes and reads of its sound RAM, timestamped)
 *   $E000-$E7FF, $F800-$FFFF  N163 builds: the sound disable bit (bit 6) and the N163 address port (writes,
 *                timestamped); reads there return the ROM
 *   $9010, $9030, $E000  VRC7 build: VRC7 register select / write and the audio reset latch (timestamped)
 *   $FFFA-$FFFF  vectors -> the player's trap entry (an interrupt or BRK is a driver fault)
 * Everything else reads 0 and ignores writes.
 *
 * The host calls the player like an NSF player does: nes_player_init(song) once, then
 * nes_player_play at the start of every NTSC frame (29780.5 CPU cycles: 29780 and 29781
 * alternating, 60.0988 Hz). One tick = one frame. Called routines return to the idle entry; the
 * machine stops at that fetch. Faults: an undocumented/JAM/BRK opcode fetch, a fetch of the trap
 * entry, or a call that runs longer than one frame.
 *
 * Rows: in FamiTracker tempo the engine starts a row on a frame when famistudio_tempo_acc_hi >=
 * famistudio_song_speed (it then subtracts the speed; every frame adds the tempo step, 256 per frame
 * at tempo 150). The machine reads that condition before each frame, so position, seek and live
 * edits are exact rows from engine state; orders come from the image (nes_image.h).
 *
 * Images are loaded with nm_load_image (nes_image.h) for the machine's player; everything here takes
 * loaded images of that player (others are NM_ERR_IMAGE / ignored). nm_create allocates (the VRC7 build also
 * its emu2413 instance); nothing after it does. Seeking runs the expansion chip silently (FM envelopes, phases
 * and LFOs advance as in straight play). Threading: one thread at a time. */
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

/* A machine for one player build (nes_player_2a03 / nes_player_vrc6 / nes_player_vrc7 / nes_player_n163[n - 1]);
 * NULL for an unsupported rate or when out of memory. */
NesMachine* nm_create(uint32_t sample_rate, const NesPlayer* player);
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
 * pointers move to the same directory entry (instrument envelopes always stay their slot's; effect
 * envelopes may move to an identical one), keeping their position when it is still an instruction start;
 * instrument table, DPCM list, tempo and a changed order-start speed are re-read. A speed the engine got
 * another way (a loop, an Fxx later edited away) is otherwise kept. VRC7: the FM channels' patches follow
 * their edited instruments at once (patch number, override byte, the custom patch registers of the
 * instrument they came from). N163: the expansion instrument table is re-read too, and an N163 channel whose
 * current instrument's wave changed (position, size or wave bytes, by content) re-uploads it at the engine's
 * next update (its wave index is set to $FF); N163 RAM and phases are untouched. Otherwise the region is copied
 * (correct only when no data moved). Either way a sample the DMC is playing continues if its bytes are
 * unchanged in place, continues at the same position of an identical sample of the new DPCM list if it moved,
 * and stops otherwise. Ignored while faulted or auditioning. */
void nm_apply_image(NesMachine* m, const NesImage* image);

/* Resets the machine to sound one held note through the engine with a one-note stream in RAM, using
 * the image's instruments and DPCM list (Thor layout). Tonal channels (0..3; VRC6 build also 5..7, VRC7 build
 * 5..10, N163 builds 5..4+n): engine note 1..96 (absolute - 11), instrument table index, fine pitch -128..127
 * (period offset, +lowers; VRC7: FM frequency offset; N163: frequency offset, +raises; channels with a pitch
 * envelope: 0..2 and the expansion channels). Channel 4 (DPCM): `note` = DPCM list entry 1..mappings (the
 * stream's note byte); instrument and fine are ignored. */
int nm_audition(NesMachine* m, const NesImage* image, int channel, int note, int instrument, int fine);

/* Key-off of an audition: with a volume release point the engine releases the note (returns 1;
 * nm_tick returns 0 once the release tail has reached its final loop); otherwise, and always on the
 * DPCM channel, 0 (cut now: the host drops the machine). VRC7 FM channels always release (returns 1): the
 * engine's `$56` keys the FM note off and starts the volume envelope's release, if any; nm_tick returns 0 once
 * that tail is done and the channel's carrier is silent or can no longer change (release rate 0). N163 channels
 * release with `$5A` (the volume and wave-index envelopes jump to their release points): with a volume release
 * point the tail ends at the volume envelope's final loop, else with a wave-index release point at that
 * envelope's final loop. */
int nm_release(NesMachine* m);

/* Forces channel (0..4; VRC6 build 0..7, VRC7 build 0..10, N163 builds 0..4+n) silent in the mixer (state keeps
 * running, so unmuting resumes mid-note). */
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
/* (address low byte, value) pairs of the APU ($40xx) writes since the last log call, at most `capacity`
 * pairs. Both log calls drain the log. */
int nm_apu_log(NesMachine* m, uint8_t* pairs, int capacity);
/* (address low, address high, value) triples of every sound write (APU, VRC6 and N163 ports) since the last log
 * call, at most `capacity`. */
int nm_write_log(NesMachine* m, uint8_t* triples, int capacity);
/* The same, with the frame-relative CPU cycle each write happened at in `cycles` (the sound units' timebase). */
int nm_write_log_timed(NesMachine* m, uint8_t* triples, uint32_t* cycles, int capacity);
uint8_t nm_peek(NesMachine* m, uint16_t addr);
void nm_poke(NesMachine* m, uint16_t addr, uint8_t value); /* RAM/PRG, for fault tests */
/* Calls a routine with A, returning NM_OK or NM_ERR_FAULT (test hook). */
int nm_call(NesMachine* m, uint16_t addr, uint8_t a);
/* The APU, the VRC6, VRC7 and N163 units (tests read channel periods, levels, chip registers, N163 RAM and phases;
 * only the machine's own build's unit is in use), and the machine's player. */
const struct NesApu* nm_apu(const NesMachine* m);
const struct NesVrc6* nm_vrc6(const NesMachine* m);
const struct NesVrc7* nm_vrc7(const NesMachine* m);
const struct NesN163* nm_n163(const NesMachine* m);
/* VRC7 build: the expansion instrument (table index) FM channel `channel` (5..10) loaded last, and the one the
 * chip's custom patch registers $00-$07 came from; -1: none (or not the VRC7 build). */
int nm_vrc7_instrument(const NesMachine* m, int channel);
int nm_vrc7_custom_instrument(const NesMachine* m);
const NesPlayer* nm_player(const NesMachine* m);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_NES_MACHINE_H_ */
