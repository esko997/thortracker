/* C interface to reSID 1.0 (third_party/resid-1.0, C++), so the SID machine and the hosts stay C.
 * One SidResid is one MOS 6581/8580 clocked at the C64's CPU rate and resampled to the host rate.
 * Only sid_resid_new allocates (reSID's FIR tables); everything else is realtime-safe. */
#ifndef THORTRACKER_SID_RESID_H_
#define THORTRACKER_SID_RESID_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct SidResid SidResid;

enum { SID_RESID_6581 = 0, SID_RESID_8580 = 1 };

enum {
    SID_RESID_RESAMPLE = 0,    /* reSID SAMPLE_RESAMPLE: band-limited (Kaiser-windowed sinc FIR), the default */
    SID_RESID_INTERPOLATE = 1, /* reSID SAMPLE_INTERPOLATE: linear interpolation between cycles (cheaper, aliases) */
};

/* NULL on failure (allocation, or a rate reSID's resampler rejects). */
SidResid* sid_resid_new(double clock_hz, uint32_t sample_rate, int model, int method);
void sid_resid_free(SidResid* s);
/* Power-on state of the chip (registers 0, envelopes idle) with the given model. */
void sid_resid_reset(SidResid* s, int model);
void sid_resid_write(SidResid* s, uint8_t reg, uint8_t value);
/* Switches the chip model in place (a live song edit); the running voices keep their state. */
void sid_resid_set_model(SidResid* s, int model);
/* Advances `cycles` without producing samples (seeking). */
void sid_resid_clock(SidResid* s, uint32_t cycles);
/* Advances `cycles`, writing the samples that fall due (at most `max`); returns their count. */
int sid_resid_render(SidResid* s, uint32_t cycles, int16_t* out, int max);
/* Voices whose bit is clear are left out of the mix (they keep running; sync/ring still work). */
void sid_resid_set_voice_mask(SidResid* s, unsigned mask);
/* The voice's envelope counter, 0..255. */
int sid_resid_envelope(SidResid* s, int voice);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_SID_RESID_H_ */
