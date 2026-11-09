/* The C interface of the TIA's two audio channels: Stella 7.0's AudioChannel (third_party/stella-tia, vendored
 * unmodified, GPL-2.0-or-later; Christian Speckner's gate-level model of the counters), compiled once in
 * tia_stella.cpp. The two channel objects live in fixed storage inside TiaStella (placement new in tis_init, no
 * allocation anywhere). The caller (tia_machine.c) owns the timeline: it calls phase0/phase1 at the TIA's audio clock
 * positions and reads actual_volume. */
#ifndef THORTRACKER_TIA_STELLA_H_
#define THORTRACKER_TIA_STELLA_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define TIS_STORAGE_BYTES 128 /* >= 2 x sizeof(AudioChannel); checked in tia_stella.cpp */
#define TIS_STATE_BYTES 10

/* Storage for two AudioChannel objects (opaque; aligned for any scalar or pointer). */
typedef struct TiaStella {
    union {
        uint64_t align_u64;
        void* align_ptr;
        double align_double;
        unsigned char bytes[TIS_STORAGE_BYTES];
    } u;
} TiaStella;

/* Constructs both channels in `s` (placement new) and resets them. Call once before any other function. */
void tis_init(TiaStella* s);
/* Destroys both channels (call once when done with `s`). */
void tis_destroy(TiaStella* s);
/* Both channels' AudioChannel::reset(): registers and counters 0 (Stella's power-on state). */
void tis_reset(TiaStella* s);
/* Register writes of channel c (0, 1): AUDC (4 bits used), AUDF (5 bits), AUDV (4 bits). */
void tis_audc(TiaStella* s, int c, uint8_t value);
void tis_audf(TiaStella* s, int c, uint8_t value);
void tis_audv(TiaStella* s, int c, uint8_t value);
/* The two-phase audio clock, both channels (channel 0 first): phase 0 = the divider, phase 1 = the pulse and noise
 * counters (the output). */
void tis_phase0(TiaStella* s);
void tis_phase1(TiaStella* s);
/* Channel c's output level 0..15: (pulse counter bit 0) x AUDV. */
uint8_t tis_actual_volume(const TiaStella* s, int c);
/* Channel c's state through AudioChannel::save, TIS_STATE_BYTES bytes: audc, audf, audv, clockEnable,
 * noiseFeedback, noiseCounterBit4, pulseCounterHold (bools 0/1), divCounter, pulseCounter, noiseCounter. */
void tis_state(const TiaStella* s, int c, uint8_t out[TIS_STATE_BYTES]);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_TIA_STELLA_H_ */
