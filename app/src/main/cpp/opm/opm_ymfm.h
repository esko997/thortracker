/* C interface over ymfm's YM2151 (Aaron Giles, BSD-3-Clause; third_party/ymfm, ymfm_opm.h/.cpp at the vendored pin):
 * the YM2151 of the "ym2151-opm" machine. One sample per call at the chip's native rate (input clock / 64: 62500 Hz at
 * the X68000's 4 MHz). The chip's two timers run on that sample clock (one sample = 64 input clocks; timer A's period
 * is (1024 - NA) samples, timer B's 16 (256 - NB)), so timer A's overflow keys on every channel in CSM mode exactly as
 * ymfm's engine does it when its host reports the timer (engine_timer_expired). Timer B only sets status flags (no
 * IRQ consumer: nothing sounds from it). Muted channels are left out of the output sum (before the YM3012 DAC's
 * floating-point round trip); the chip itself keeps running. Allocation only in omc_create. */
#ifndef THORTRACKER_OPM_YMFM_H_
#define THORTRACKER_OPM_YMFM_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct YmOpm YmOpm;

YmOpm* omc_create(void);
void omc_destroy(YmOpm* chip);
/* A freshly reset chip (every register 0 as ymfm resets it, except $20-$27 = $C0; timers stopped, LFO, noise and
 * envelope counters at their power-on values). */
void omc_reset(YmOpm* chip);
/* One register write (address then data). */
void omc_write(YmOpm* chip, uint8_t reg, uint8_t value);
/* One native sample (left, right; ymfm's scale, about +-32767) with channels whose bit is set in `mute_mask` left out.
 * Timers are advanced first (an overflow in this sample takes effect in it). */
void omc_generate(YmOpm* chip, unsigned mute_mask, int32_t* left, int32_t* right);
/* Envelope attenuation (0 = loudest, 0x3FF = silent) of operator op (0..3 = operators 1..4 = M1, C1, M2, C2) of
 * channel ch (0..7). */
int omc_envelope(const YmOpm* chip, int ch, int op);
/* Status register (bit 0 timer A overflowed, bit 1 timer B; test hook). */
uint8_t omc_status(YmOpm* chip);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_OPM_YMFM_H_ */
