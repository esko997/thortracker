/* The Atari 8-bit POKEY's sound section (C012294), cycle-exact at the machine clock and event-driven: every
 * cycle in which something happens (a timer counts to underflow, a timer fires, a two-tone resync) runs the
 * same per-cycle step, and the cycles between them are skipped arithmetically (they only decrement counters).
 * pk_run_reference() steps every cycle through that same step for the tests.
 *
 * Behaviour (docs/AUDIO_ENGINE.md, "POKEY: the chip"), from the Altirra Hardware Reference Manual (Avery Lee,
 * 2026-01-02 edition, chapter 5: measured on hardware) and the Atari 400/800 Hardware Manual, with the cycle
 * structure of MAME's pokey.cpp 4.9 (BSD-3-Clause) and the poly generators, nonlinear mix and output coupling of
 * Altirra 4.40's ATAudio (GPL-2.0-or-later; ported, see NOTICE.txt):
 *
 *  - four 8-bit down counters reloaded from AUDF1-4; AUDCTL selects per counter the 1.79 MHz machine clock
 *    (channels 1, 3), the 64 kHz (machine / 28) or 15 kHz (machine / 114) clock (one choice for all), and links
 *    1+2 / 3+4 into 16-bit counters; a counter that underflows fires 3 cycles later and reloads then (periods
 *    N + 4 at 1.79 MHz, N + 7 linked, (N + 1) x 28 or x 114 otherwise);
 *  - the 4-, 5-, 9- and 17-bit polynomial generators, shared, one bit per cycle, each channel sampling them one
 *    cycle after the channel before it; AUDC bits 7-5 select the eight distortions, bit 4 volume only;
 *  - the high-pass flip-flops of channels 1 and 2 (clocked by channels 3 and 4), forced to 1 when off, so
 *    channels 1 and 2 are inverted against 3 and 4;
 *  - STIMER (reload every counter, every output flip-flop to 1), SKCTL initialization mode (generators and the
 *    15/64 kHz clocks reset and held; 1.79 MHz counters keep counting) and two-tone mode (timer 1 and 2
 *    resyncs, SKCTL bit 3, with force break bit 7); IRQEN for timers 1, 2 and 4.
 *
 * Not modelled (no sound of their own, or need devices this machine has no use for): the serial port's shift
 * registers and its clock modes SKCTL bits 4-6 (asynchronous receive holds timers 3/4 until a start bit arrives),
 * keyboard and paddle scanning, the 1.5-cycle high-pass delay Altirra measures and the first-period offsets
 * right after STIMER (sub-period phase, not pitch). */
#ifndef THORTRACKER_POKEY_CHIP_H_
#define THORTRACKER_POKEY_CHIP_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Machine clocks: NTSC = 315 MHz / 176 (half the 3.579545 MHz colour clock), PAL = 17.734475 MHz / 10. */
#define PK_CLOCK_NTSC (315000000.0 / 176.0)
#define PK_CLOCK_PAL 1773447.5
/* Video frames in machine cycles (114 cycles per scan line): NTSC 262 lines (59.92 Hz), PAL 312 (49.86 Hz). */
#define PK_FRAME_CYCLES_NTSC 29868u
#define PK_FRAME_CYCLES_PAL 35568u

#define PK_NEVER UINT64_MAX

/* Registers (write side). */
enum {
    PK_AUDF1 = 0x0,
    PK_AUDC1 = 0x1,
    PK_AUDF2 = 0x2,
    PK_AUDC2 = 0x3,
    PK_AUDF3 = 0x4,
    PK_AUDC3 = 0x5,
    PK_AUDF4 = 0x6,
    PK_AUDC4 = 0x7,
    PK_AUDCTL = 0x8,
    PK_STIMER = 0x9,
    PK_IRQEN = 0xE,
    PK_SKCTL = 0xF,
};

/* AUDCTL bits. */
#define PK_AUDCTL_POLY9 0x80   /* the 9-bit generator instead of the 17-bit one */
#define PK_AUDCTL_CH1_FAST 0x40 /* channel 1 counts the 1.79 MHz machine clock */
#define PK_AUDCTL_CH3_FAST 0x20 /* channel 3 likewise */
#define PK_AUDCTL_JOIN12 0x10   /* channel 2 counts channel 1's fires: one 16-bit counter */
#define PK_AUDCTL_JOIN34 0x08   /* channel 4 counts channel 3's fires */
#define PK_AUDCTL_HP13 0x04     /* channel 1 high-pass, clocked by channel 3 */
#define PK_AUDCTL_HP24 0x02     /* channel 2 high-pass, clocked by channel 4 */
#define PK_AUDCTL_BASE15 0x01   /* base clock 15 kHz (machine / 114) instead of 64 kHz (machine / 28) */

/* AUDC bits. */
#define PK_AUDC_NO_POLY5 0x80   /* fires are not gated by the 5-bit generator */
#define PK_AUDC_POLY4 0x40      /* noise from the 4-bit generator (when not pure) */
#define PK_AUDC_PURE 0x20       /* each fire toggles the output (square wave) */
#define PK_AUDC_VOLUME_ONLY 0x10 /* output forced on: the volume is a DC level (the DAC) */

/* SKCTL bits the sound section uses. */
#define PK_SKCTL_INIT_MASK 0x03 /* both 0 = initialization mode */
#define PK_SKCTL_TWO_TONE 0x08
#define PK_SKCTL_BREAK 0x80

/* IRQEN bits (timers 1, 2 and 4; timer 3 has no interrupt). */
#define PK_IRQ_TIMER1 0x01
#define PK_IRQ_TIMER2 0x02
#define PK_IRQ_TIMER4 0x04

/* Cycles from a counter's underflow to its fire (and reload). */
#define PK_FIRE_DELAY 3
/* Cycles from the fire that triggers a two-tone resync to the resync. */
#define PK_RESYNC_DELAY 2
/* First 64 kHz / 15 kHz clock pulse after initialization mode ends, then every 28 / 114 cycles. */
#define PK_BASE64_FIRST 19
#define PK_BASE15_FIRST 78

#define PK_POLY4_LENGTH 15
#define PK_POLY5_LENGTH 31
#define PK_POLY9_LENGTH 511
#define PK_POLY17_LENGTH 131071

/* Receives the channels' output levels whenever they change: nibble i (bits 4i..4i+3) = channel i + 1's
 * volume while its digital output is on, else 0. `cycle` is the cycle of the change. */
typedef void (*PkOutputFn)(void* ctx, uint64_t cycle, uint16_t levels);

typedef struct PokeyChip {
    /* All events of cycles <= now have happened; writes act at now. */
    uint64_t now;
    uint8_t audf[4], audc[4], audctl, skctl, irqen;
    int init;            /* initialization mode */
    uint64_t exit;       /* cycle initialization mode last ended (the generators and base clocks start there) */
    uint16_t u[4];       /* counts left until the counter underflows (1..256) */
    uint64_t fire_at[4]; /* cycle of the pending fire, PK_NEVER without one */
    uint8_t out[4];      /* output flip-flops */
    uint8_t hp[2];       /* high-pass flip-flops of channels 1 and 2 */
    uint64_t resync[4];  /* pending two-tone resyncs, ascending */
    int resyncs;
    uint16_t levels;     /* last reported */
    uint8_t irq;         /* timers (PK_IRQ_*) that fired with their IRQEN bit set, until pk_take_irq */
    int irq_new;         /* an enabled timer fired in the last stepped cycle */
    PkOutputFn output;
    void* ctx;
    /* The generators' output sequences from the reset state (index = shifts since initialization mode ended). */
    uint8_t poly4[PK_POLY4_LENGTH];
    uint8_t poly5[PK_POLY5_LENGTH];
    uint8_t poly9[PK_POLY9_LENGTH];
    uint8_t poly17[(PK_POLY17_LENGTH + 7) / 8]; /* bit-packed */
} PokeyChip;

/* Builds the generator sequences (131071 steps) and resets. Once per chip (the machine's create). */
void pk_init(PokeyChip* c, PkOutputFn output, void* ctx);

/* Power-on: every register 0 (SKCTL 0 = initialization mode), counters reloaded, outputs 0, cycle 0.
 * POKEY has no reset line and powers up in an undefined state; this is the state a driver's init writes leave. */
void pk_reset(PokeyChip* c);

/* Writes `value` to register `reg` (0..15; read-only and serial registers are ignored) at cycle now. */
void pk_write(PokeyChip* c, int reg, uint8_t value);

/* Runs cycles now + 1 .. until. Returns 1 early (now = that cycle) after a cycle in which a timer with its IRQEN
 * bit set fired (pk_take_irq says which), else 0 with now = until. */
int pk_run(PokeyChip* c, uint64_t until);

/* pk_run stepping every cycle (tests: the skipped cycles must not matter). */
int pk_run_reference(PokeyChip* c, uint64_t until);

/* The timers (PK_IRQ_*) that fired with interrupts enabled since the last call, cleared. */
uint8_t pk_take_irq(PokeyChip* c);

/* Bit `k` (0 or 1) of the generators as channel `ch` (0..3) samples them at cycle t (tests). which: 4, 5, 9, 17. */
int pk_poly_bit(const PokeyChip* c, int which, int ch, uint64_t t);

/* Altirra's nonlinear mix: 325 levels indexed by pk_mix_index (Altirra pokey.cpp UpdateMixTable, measured
 * per-bit DAC drops and the output's exponential saturation). Values are <= 0: the output pin sinks current. */
#define PK_MIX_ENTRIES 325
void pk_mix_init(float table[PK_MIX_ENTRIES]);
/* The mix index of a levels word: per channel 1 for volume bit 0, 5 for bit 1, 25 for bit 2, 50 for bit 3. */
int pk_mix_index(uint16_t levels);

/* The output's AC coupling (time constant 2.6 ms, measured by Altirra) and the amplifier's clamp of the coupled
 * signal to [-0.65, 0.5] of POKEY's full level: y = coeff * y + (x - x_prev), coeff = exp(-1 / (rate x tau)). */
#define PK_COUPLING_TAU 0.0025968510736001341
#define PK_CLAMP_LOW (-0.65f)
#define PK_CLAMP_HIGH 0.5f

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_POKEY_CHIP_H_ */
