/* C interface to Mednafen 1.32.1's HuC6280 PSG (third_party/mednafen-pce-psg, unmodified, GPL-2.0-or-later), the
 * PC Engine's sound chip, for the "pce-huc6280" machine (pce_machine.c). docs/AUDIO_ENGINE.md, "PC Engine: chip, clocks
 * and core".
 *
 * Time is counted in CPU cycles (master / 3 = 78750000 / 11 = 7159090.91 Hz), Mednafen's PSG timestamps. The machine
 * works one tick at a time: register writes at cycle offsets from the tick start (non-decreasing), then pcs_end_tick
 * with the tick's length. Mednafen writes band-limited deltas (its 7-tap phase filter, gain 2048) into two 32-bit
 * buffers at master / 12 = 1789772.73 Hz (index = timestamp >> 2); the end of a tick integrates them per index
 * (level += delta, value = level >> 12) and feeds the value changes to two blip_buf instances at 1789772.73 Hz -> the
 * host rate. The filter tail past the tick end (the last 8 indices) carries into the next tick, whose timestamps
 * restart at the leftover (start + length) & 3. Revision: the original HuC6280 (REVISION_HUC6280), SetVolume(1.0).
 * No allocation after pcs_create. One thread at a time. */
#ifndef THORTRACKER_PCE_PSG_H_
#define THORTRACKER_PCE_PSG_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define PCS_HR_SIZE 65536      /* Mednafen's OwlBuffer index range (timestamp >> 2) & 0xFFFF */
#define PCS_HR_PADDING 32      /* OwlBuffer's overflow padding: the filter writes indices up to +6 past an event */
#define PCS_HR_TAIL 8          /* indices carried from a tick's end into the next tick */
#define PCS_LEVEL_SHIFT 12     /* value = level >> 12: one channel at full volume swings 0..5290 */
#define PCS_NATIVE_HZ (78750000.0 / 11.0 / 4.0) /* 1789772.73 Hz, the delta buffers' rate */

typedef struct PcePsg PcePsg;

/* The chip (powered on), its delta buffers and the two resamplers to `sample_rate` frames, each holding up to
 * `max_frames` frames between reads. NULL on failure. */
PcePsg* pcs_create(uint32_t sample_rate, int max_frames);
void pcs_destroy(PcePsg* s);
/* A freshly powered-on chip: the PSG object is reconstructed in place (Mednafen's Power() keeps per-channel filter
 * history and timestamps, a fresh object starts them at 0), SetVolume(1.0), Power(0); delta buffers, integrators and
 * resamplers cleared, timestamps at 0. No allocation. */
void pcs_reset(PcePsg* s);
/* Register write (0..9; R0 selects the channel registers 2-7 act on) at `cycle` cycles after the tick's start. */
void pcs_write(PcePsg* s, int32_t cycle, unsigned reg, unsigned val);
/* Ends the tick `length` cycles (<= 119320) after its start: runs the chip to the end, integrates the complete
 * native samples into the resamplers (one blip frame) and carries the rest. Returns the native samples of the tick. */
int pcs_end_tick(PcePsg* s, int32_t length);
/* Reads up to max_frames resampled stereo frames (interleaved left, right) into out; returns the frames read. */
int pcs_read(PcePsg* s, short* out, int max_frames);
/* Mednafen's PCE_PSG::GetRegister(id): PSG_GSREG_* ids (pce_psg.h), e.g. 0x1000 select, 0x1001 master balance,
 * 0x1002 LFO frequency, 0x1003 LFO control, 0x100 * c + 0..6 channel c's frequency, control, balance, waveform
 * index, latch (dda), noise control, LFSR. For tests. */
uint32_t pcs_register(const PcePsg* s, unsigned id);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_PCE_PSG_H_ */
