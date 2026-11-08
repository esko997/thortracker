/* C interface over ymfm's YM2612 (Aaron Giles, BSD-3-Clause; third_party/ymfm): the YM2612 of the "ym2612-fm"
 * machine. One sample per call at the chip's native rate (input clock / 144). The busy flag the XGM driver polls
 * is modelled on the machine's master clock: a data write keeps it set for 32 x 6 chip clocks (ymfm's figure for
 * the OPN2), i.e. 1344 master clocks. Muted channels are left out of the output sum exactly as the chip leaves out
 * a channel whose $B4 output bits are clear (the DAC's offset remains); the chip itself keeps running.
 * Allocation during create/reset only; hosts call these on their control threads. */
#ifndef THORTRACKER_YM_YMFM_H_
#define THORTRACKER_YM_YMFM_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define YMO_BUSY_MCLK (32u * 6u * 7u) /* busy after a data write, in master clocks (YM2612 clock = MCLK / 7) */

typedef struct YmOpn2 YmOpn2;

YmOpn2* ymo_create(void);
void ymo_destroy(YmOpn2* chip);
/* Chip reset; the busy flag clears. */
void ymo_reset(YmOpn2* chip);
/* `now` = the machine's master-clock time of the access (busy flag). port: 0/2 address, 1/3 data. */
void ymo_write(YmOpn2* chip, uint64_t now, int port, uint8_t value);
uint8_t ymo_read_status(YmOpn2* chip, uint64_t now);
/* One native sample (left, right; ymfm's scale) with channels whose bit is set in `mute_mask` left out. */
void ymo_generate(YmOpn2* chip, unsigned mute_mask, int32_t* left, int32_t* right);
/* Envelope attenuation (0 = loudest, 0x3FF = silent) of operator op (0..3 = operators 1..4) of channel ch (0..5). */
int ymo_envelope(const YmOpn2* chip, int ch, int op);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_YM_YMFM_H_ */
