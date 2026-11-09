/* Philips SAA1099 sound core: a C port of SAASound's SAA1099 behaviour, restructured on exact chip clocks so that state
 * can be advanced without output (analytic per tone generator) or rendered as output-change events. Behaviour and
 * sources: docs/AUDIO_ENGINE.md, "SAA1099" ("The chip", "Core").
 *
 * license:BSD-3-Clause
 * Derived from SAASound (github.com/stripwax/SAASound, commit 3d92322030d1e5627cbf8295bed1efc872bad6eb: SAAEnv.cpp,
 * SAAAmp.cpp, SAAFreq.cpp, SAANoise.cpp, SAADevice.cpp):
 *   Copyright (c) 1998-2004, Dave Hooper <dave@beermex.com>
 *   Copyright (c) 2004-2025, Dave Hooper <dave@beermex.com> + Simon Owen <simon@simonowen.com>
 *   All rights reserved.
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * conditions of the BSD-3-Clause licence are met (full text in app/src/main/assets/NOTICE.txt). The envelope control
 * (SetEnvControl, Tick, SetLevels, SetNewEnvData), its level tables (cs_EnvData) and the amplitude PDM table are ported
 * verbatim; the tone, noise and envelope clocking is re-derived per chip clock.
 *
 * One clock (nothing moves while sync is set): the noise prescaler counts (mod 1024); each tone generator counts
 * down and at 0 makes an edge (level flips; tone 0 / 3 shifts noise 0 / 1 in mode 3; tone 1 / 4 clocks envelope 0 / 1
 * if enabled and internal; pending offset/octave data latches; the count reloads with the half-period
 * H = (511 - N) << (8 - O)); noise generators in mode m < 3 shift when the prescaler is a multiple of 256 << m. The
 * output of a channel is 0 while sync is set or the chip disabled; else from its mixer level x (half-steps) either
 * SAC_PDM[amp >> 1][envelope level] * 4 * (2 - x) (channels 2 and 5 with their envelope enabled) or amp * x * 16.
 *
 * The core also holds each channel's output as last rendered or advanced (out_l, out_r): sac_render first emits the
 * changes writes made since then (at offset 0), then for each clock the channels whose output changed in it, channels
 * ascending. No allocation, no globals; plain data with no padding (tests compare cores with memcmp). */
#ifndef THORTRACKER_SAA_CORE_H_
#define THORTRACKER_SAA_CORE_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SAC_CHANNELS 6
#define SAC_CLOCK_SAM 8000000  /* SAM Coupe: 24 MHz master / 3 */
#define SAC_CLOCK_CMS 7159090  /* Creative Music System / Game Blaster: 14.31818 MHz / 2 */
#define SAC_PRESCALE 1024      /* the noise prescaler's period in clocks */
#define SAC_LFSR_SEED 0x3FFFF  /* the noise LFSRs after reset (the hardware seed is unknown, only non-zero) */
#define SAC_LFSR_TAPS 0x20400  /* 18-bit Galois LFSR, x^18 + x^11 + x^1 (Jepael) */
#define SAC_OUT_MAX 480        /* one channel's largest output per side (amp 15, tone or noise high, no envelope) */
#define SAC_HALF_MAX 130816    /* the longest half-period: O 0, N 0 */

/* Registers (address & 0x1F). */
enum {
    SAC_REG_AMP = 0x00,     /* 0x00-0x05: amplitude of channel reg, left = low nibble, right = high nibble */
    SAC_REG_OFFSET = 0x08,  /* 0x08-0x0D: tone offset N of channel reg - 8 */
    SAC_REG_OCTAVE = 0x10,  /* 0x10-0x12: octaves of channels 2k (bits 0-2) and 2k + 1 (bits 4-6) */
    SAC_REG_TONE = 0x14,    /* tone mixer enables, bit c = channel c */
    SAC_REG_NOISE = 0x15,   /* noise mixer enables */
    SAC_REG_NOISE_MODE = 0x16, /* noise 0 clock = bits 0-1, noise 1 clock = bits 4-5 */
    SAC_REG_ENV0 = 0x18,    /* envelope 0 control (channel 2); the address write clocks it when external */
    SAC_REG_ENV1 = 0x19,    /* envelope 1 control (channel 5) */
    SAC_REG_CONTROL = 0x1C, /* bit 0 sound enable, bit 1 sync & reset */
};

typedef struct SacTone {
    int32_t oct, off;           /* the octave O (0..7) and offset N (0..255) in use */
    int32_t next_oct, next_off; /* the latest written values */
    int32_t pending;            /* next_* latch at the next edge */
    int32_t level;              /* 0 / 1 */
    int32_t half;               /* H(oct, off) */
    int32_t count;              /* clocks to the next edge, 1..half */
} SacTone;

typedef struct SacNoise {
    int32_t mode;  /* 0 = clock / 256, 1 = / 512, 2 = / 1024, 3 = every edge of tone 0 (noise 0) / 3 (noise 1) */
    uint32_t lfsr; /* 18 bits; the noise bit = bit 0 */
} SacNoise;

/* SAASound's CSAAEnv fields (the table pointer as the shape index). */
typedef struct SacEnv {
    int32_t enabled;    /* m_bEnabled */
    int32_t external;   /* m_bClockExternally */
    int32_t invert;     /* m_bInvertRightChannel */
    int32_t shape;      /* (data >> 1) & 7: the cs_EnvData entry */
    int32_t looping;    /* m_bLooping */
    int32_t phases;     /* m_nNumberOfPhases */
    int32_t resolution; /* m_nResolution: 1 = 4-bit, 2 = 3-bit */
    int32_t phase;      /* m_nPhase */
    int32_t pos;        /* m_nPhasePosition */
    int32_t ended;      /* m_bEnvelopeEnded */
    int32_t new_data;   /* m_bNewData */
    int32_t next_data;  /* m_nNextData */
    int32_t left, right; /* m_nLeftLevel, m_nRightLevel */
} SacEnv;

typedef struct SacChannel {
    int32_t amp_l, amp_r; /* 0..15 */
    int32_t tone, noise;  /* mixer enables 0 / 1 */
} SacChannel;

typedef struct SacCore {
    int32_t enable;   /* register 0x1C bit 0 */
    int32_t sync;     /* register 0x1C bit 1 */
    int32_t prescale; /* 0..1023 */
    SacTone tone[SAC_CHANNELS];
    SacNoise noise[2];
    SacEnv env[2];
    SacChannel ch[SAC_CHANNELS];
    int32_t out_l[SAC_CHANNELS], out_r[SAC_CHANNELS]; /* each channel's output as last rendered / advanced */
} SacCore;

/* Called by sac_render when a channel's output pair changes: clock = offset 0..clocks - 1 into the render
 * (nondecreasing), left and right 0..SAC_OUT_MAX. */
typedef void (*SacEmit)(void* ctx, uint32_t clock, int channel, int left, int right);

/* The measured PDM levels (SAASound CSAAAmp::EffectiveAmplitude): [amplitude >> 1][envelope level]. */
extern const uint8_t SAC_PDM[8][16];

/* Power-on state (docs/AUDIO_ENGINE.md, "Reset"). */
void sac_reset(SacCore* core);
/* An address write of reg & 0x1F, then a data write of val (a VGM 0xBD command; SAASound WriteAddressData). */
void sac_write(SacCore* core, int reg, int val);
/* Advances `clocks` clocks without output. Leaves exactly the state sac_render would (out_* included). */
void sac_advance(SacCore* core, uint32_t clocks);
/* Advances `clocks` clocks, calling emit for every output change (emit may be NULL). */
void sac_render(SacCore* core, uint32_t clocks, SacEmit emit, void* ctx);
/* A channel's output now (0 while sync is set or the chip is disabled). */
void sac_output(const SacCore* core, int channel, int* left, int* right);
/* The half-period in clocks of octave O and offset N. */
static inline int32_t sac_half(int oct, int off) { return (511 - off) << (8 - oct); }

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_SAA_CORE_H_ */
