/* Konami 051649 (SCC) / 052539 (SCC+ mode) sound core: a C port of MAME's k051649 sound logic, restructured so that
 * state can be advanced without output (exact, O(1) per channel) or rendered as level-change events. Behaviour and
 * sources: docs/AUDIO_ENGINE.md, "SCC and SCC+" ("The chips", "Core").
 *
 * license:BSD-3-Clause
 * Derived from MAME src/devices/sound/k051649.cpp (copyright-holders: Bryan McPhail), commit
 * 20d4667cdf485066a113f1ef24bae0887cd7134c. Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD-3-Clause licence are met (full text in
 * app/src/main/assets/NOTICE.txt). Test-register bits 0-1 follow openMSX's documented model of Manuel Pazos's
 * measurements (behaviour only, no openMSX code).
 *
 * Model (per clock, per channel; MAME): with effective period E > 8 the channel counts clk + 1; when clk passes E the
 * waveform position advances (mod 32) and clk = 0; whenever clk is 0 the latch reloads = (key ? wave[pos] : 0) * volume.
 * E <= 8 halts the channel (nothing moves). A period write sets clk = -1 (the latch reloads one clock later; bit 5 of
 * the test register also sets pos = 0) and E = the period, P >> 8 (test bit 0) or P & 0xFF (test bit 1, wins over bit
 * 0) as the test register is at that write. Level = latch >> 4 (arithmetic); the chip outputs the sum of the levels.
 * No allocation, no globals; plain data (tests compare cores with memcmp). */
#ifndef THORTRACKER_SCC_K051649_H_
#define THORTRACKER_SCC_K051649_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SCK_CLOCK 3579545u /* MSX bus clock: frequency = SCK_CLOCK / (32 (P + 1)) */
#define SCK_CHANNELS 5
#define SCK_RAMS 5
#define SCK_WAVE_BYTES 32
#define SCK_HALT_PERIOD 8 /* effective periods <= 8 halt a channel */

/* Write ports: the VGM 0xD2 command's (libvgm / VGMPlay numbering). */
enum {
    SCK_PORT_WAVE = 0,      /* SCC: reg 0x00-0x7F (0x60-0x7F = RAM 3, played by channels 4 and 5); ignored on the SCC+ */
    SCK_PORT_PERIOD = 1,    /* reg 2c = bits 0-7, 2c + 1 = bits 8-11 of channel c's period; regs >= 10 ignored */
    SCK_PORT_VOLUME = 2,    /* reg c (0..4), low 4 bits */
    SCK_PORT_KEY = 3,       /* bits 0-4 = key enables of channels 0-4 (reg ignored) */
    SCK_PORT_WAVE_PLUS = 4, /* SCC+: reg 0x00-0x9F = RAM reg >> 5; ignored on the SCC */
    SCK_PORT_TEST = 5,      /* the test ("deformation") register (reg ignored) */
};

/* Test register bits. */
#define SCK_TEST_FREQ4 0x01   /* the counter uses P >> 8 (unverified; openMSX's model) */
#define SCK_TEST_FREQ8 0x02   /* the counter uses P & 0xFF (unverified; wins over FREQ4) */
#define SCK_TEST_RESTART 0x20 /* a period write also resets the waveform position to 0 */
#define SCK_TEST_FREEZE 0x40  /* every wave RAM is read-only */
#define SCK_TEST_FREEZE4 0x80 /* SCC only: RAM 3 (channels 4 and 5) is read-only; ignored by the SCC+ */

typedef struct SccCoreChannel {
    uint16_t period;    /* the 12-bit register value */
    uint16_t effective; /* the period the counter uses (test bits 0-1 at the last period write) */
    int32_t clk;        /* clocks since the last step; -1 = latch reload pending (after a period write) */
    uint8_t pos;        /* waveform position 0..31 */
    uint8_t volume;     /* 0..15 */
    int16_t latch;      /* (key ? wave[pos] : 0) * volume at the last reload, -1920..1905 */
} SccCoreChannel;

typedef struct SccCore {
    uint8_t plus;  /* 1 = K052539 in SCC+ mode: one RAM per channel; 0 = K051649: channels 4 and 5 play RAM 3 */
    uint8_t test;  /* test register */
    uint8_t key;   /* key enables, bits 0-4 */
    uint8_t pad;
    int8_t ram[SCK_RAMS][SCK_WAVE_BYTES]; /* the SCC uses RAMs 0-3; ram[4] stays 0 */
    SccCoreChannel ch[SCK_CHANNELS];
} SccCore;

/* Called by sck_render when a channel's level (latch >> 4) changes: clock = offset 0..clocks - 1 into the render,
 * nondecreasing per channel; level -120..119. */
typedef void (*SckEmit)(void* ctx, uint32_t clock, int channel, int level);

/* Power-on state (MAME device_reset): periods 0, volumes 15, keys off, test 0, positions 0, clk 0, latches 0, RAM 0. */
void sck_reset(SccCore* core, int plus);
/* One register write at the current instant (see the port enum; wave writes respect the test register's freeze bits). */
void sck_write(SccCore* core, int port, int reg, int val);
/* Advances `clocks` clocks without output. Leaves exactly the state sck_render would. */
void sck_advance(SccCore* core, uint32_t clocks);
/* Advances `clocks` clocks, calling emit for every level change (emit may be NULL). */
void sck_render(SccCore* core, uint32_t clocks, SckEmit emit, void* ctx);
/* The wave RAM channel c plays (SCC: channel 4 -> RAM 3). */
static inline int sck_ram_of(const SccCore* core, int channel) { return !core->plus && channel == 4 ? 3 : channel; }
/* A channel's current level: latch >> 4 (arithmetic shift). */
static inline int sck_level(const SccCore* core, int channel) {
    const int v = core->ch[channel].latch;
    return v >= 0 ? v >> 4 : -((-v + 15) >> 4);
}

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_SCC_K051649_H_ */
