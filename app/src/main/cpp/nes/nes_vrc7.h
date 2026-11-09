/* Konami VRC7 audio: six 2-operator FM channels of a YM2413 (OPLL) derivative with its own patch ROM, as on
 * Lagrange Point (iNES mapper 085). Synthesis is emu2413 1.6.4 (third_party/emu2413, MIT, unmodified) in VRC7
 * mode: OPLL_setChipType(1) (no rhythm mode) and the VRC7 patch set (OPLL_VRC7_TONE, Nuke.YKT's die dump, the
 * set FamiStudio 4.5.3's Vrc7Presets uses). Registers per the NESdev wiki page "VRC7 audio":
 *
 *   $9010  register select (the internal register for the next $9030 write)
 *   $9030  register write
 *   $E000  bit 6: reset expansion sound. While set the chip is silent, its registers are cleared (including the
 *          tremolo LFO, not the vibrato LFO) and $9010/$9030 writes are disregarded. FamiStudio's init writes 0
 *          (famistudio_init: "Enable VRC7 audio"). Its power-on value is undocumented; the unit powers on with it
 *          clear, which is unobservable to the player (it writes $E000 before any register). Bits other than 6
 *          (mirroring, WRAM) do not concern audio. While the bit is held the chip is not clocked: its state is the
 *          reset state, the vibrato phase kept from before (the wiki does not say whether that LFO runs on).
 *
 * Only the VRC7's internal registers reach the chip: $00-$07 (custom patch, instrument 0), $0F (test),
 * $10-$15, $20-$25, $30-$35 (channels 1-6); "other register values are ignored" (NESdev), so emu2413's YM2413
 * channels 7-9 and register mirrors are never touched. The player's own wait loops keep the wiki's 6/42-cycle
 * write spacing; a write takes effect at its CPU cycle.
 *
 * Clock: the VRC7's 3.58 MHz oscillator takes 72 clocks per sample, 49715.9 Hz, = 36 NTSC CPU cycles (the wiki:
 * "roughly 36 NES CPU clocks"). The unit runs emu2413 one sample (OPLL_calc) per NES_VRC7_CYCLES_PER_SAMPLE CPU
 * cycles; emu2413 is created with clock = 72 x rate so each OPLL_calc is exactly one internal sample (its rate
 * converter off, no allocation after creation). The chip runs whether or not output is attached: a silent run
 * (seeking) advances phases, envelopes and LFOs exactly as an audible one and only skips the mix.
 *
 * Mix: the six channel outputs (emu2413's ch_out, +-2047 at full level; the wiki: the channels are output in
 * series, inaudibly different from mixing) are summed, muted channels left out (the chip keeps running), and
 * added to the 2A03's band-limited buffer at NES_VRC7_STEP per output unit, in the 2A03 mixer's scale (level 1.0
 * = NES_APU_AMP_SCALE). The weight is from hardware measurement, not an emulator: rainwarrior's db_vrc7 test
 * (github.com/bbbradsmith/nes-audio-tests; NESdev forum "Audio levels survey, expansion audio etc.", t=17741)
 * plays a full-volume 440 Hz 50% APU square, then on the VRC7 a custom patch ($00-$07 = 22 21 20 07 F0 F0 0F 0F,
 * $30 = 0, fnum 290, octave 4) at full volume; the VRC7 tone measured +11.3 dB (rainwarrior) and +10.88 dB
 * (James-F) RMS above the square on Lagrange Point carts: NES_VRC7_DB = their mean, 11.09 dB. The square's AC RMS
 * is half its 2A03 mixer level (one pulse at 15: 95.88 / (8128 / 15 + 100)); the test tone's AC RMS on emu2413 is
 * NES_VRC7_DB_TONE_RMS output units (computed from emu2413 1.6.4's synthesis of that patch over 2 s, the first
 * 0.2 s skipped). The VRC7 amplifier's clipping at high levels and its board filters are not modelled.
 *
 * Time is counted in CPU cycles from the start of the current output frame, as nes_apu.c. Plain data except
 * the emu2413 instance nes_vrc7_init allocates; nothing else allocates. */
#ifndef THORTRACKER_NES_VRC7_H_
#define THORTRACKER_NES_VRC7_H_

#include <stdint.h>

#include "blip_buf.h"
#include "third_party/emu2413/emu2413.h"

#ifdef __cplusplus
extern "C" {
#endif

#define NES_VRC7_VOICES 6
#define NES_VRC7_CYCLES_PER_SAMPLE 36u
#define NES_VRC7_OPLL_RATE 49716u                            /* nominal; one OPLL_calc per sample regardless */
#define NES_VRC7_OPLL_CLOCK (72u * NES_VRC7_OPLL_RATE)      /* emu2413: clock / 72 == rate, no rate conversion */
#define NES_VRC7_DB 11.09                                   /* db_vrc7 tone over the APU square, hardware mean */
#define NES_VRC7_DB_TONE_RMS 1863.1                         /* db_vrc7 tone, AC RMS in emu2413 output units */
#define NES_VRC7_APU_SQUARE_LEVEL (95.88 / (8128.0 / 15.0 + 100.0)) /* one 2A03 pulse at 15 */
/* 2A03 mixer level of one emu2413 output unit: (square level / 2) x 10^(11.09 / 20) / 1863.1 (= 1.4372e-4). */
#define NES_VRC7_STEP (NES_VRC7_APU_SQUARE_LEVEL / 2.0 * 3.5850894827655493 / NES_VRC7_DB_TONE_RMS)
#define NES_VRC7_FULL_SCALE 2047 /* emu2413 channel output at full level (ch_out, +-) */

typedef struct NesVrc7 {
    OPLL* opll;       /* emu2413 in VRC7 mode (nes_vrc7_init) */
    uint8_t select;   /* register selected by $9010 */
    uint8_t held;     /* $E000 bit 6: chip held in reset */
    uint8_t mute;     /* bit per FM channel, left out of the mix */
    uint8_t reg[0x40]; /* the VRC7 registers as written since the last reset (others stay 0) */
    uint32_t time;    /* frame-relative CPU cycle reached */
    uint32_t wait;    /* CPU cycles until the next chip sample (1..NES_VRC7_CYCLES_PER_SAMPLE) */
    int32_t amp;      /* last amplitude sent to the buffer */
    blip_t* out;      /* NULL: silent (the chip still runs, nothing is mixed) */
} NesVrc7;

/* Creates the emu2413 instance (VRC7 type and patch set) and resets; 0, or -1 when out of memory. */
int nes_vrc7_init(NesVrc7* v);
void nes_vrc7_free(NesVrc7* v);
/* Power-on state: chip registers 0, not held, select 0. Keeps `out` and `mute`. */
void nes_vrc7_reset(NesVrc7* v);
/* Attach the band-limited output (NULL = silent); starts from the current level without a step. */
void nes_vrc7_set_output(NesVrc7* v, blip_t* out);
/* Advances to frame-relative CPU cycle `t` (>= the time reached): one chip sample per 36 cycles. */
void nes_vrc7_run(NesVrc7* v, uint32_t t);
/* Whether `addr` is a VRC7 audio address ($9010, $9030, $E000). */
int nes_vrc7_register(uint16_t addr);
/* Write at frame-relative cycle `t` (runs up to `t` first); other addresses are ignored. */
void nes_vrc7_write(NesVrc7* v, uint32_t t, uint16_t addr, uint8_t value);
/* Runs to `frame_cycles` and starts the next frame at time 0 (the caller ends the blip frame). */
void nes_vrc7_end_frame(NesVrc7* v, uint32_t frame_cycles);
/* Leaves FM channels out of the mix (bit per channel 0..5); the chip keeps running. */
void nes_vrc7_set_mute(NesVrc7* v, uint8_t mask);
/* Current output of FM channel 0..5 (emu2413 units), ignoring mutes. */
int nes_vrc7_channel_out(const NesVrc7* v, int channel);
/* Current mixed level (NES_VRC7_STEP per output unit, mutes applied), in the 2A03 mixer's scale. */
float nes_vrc7_mix_level(const NesVrc7* v);
/* Whether FM channel 0..5's note can no longer change: keyed off and its carrier either silent (emu2413 outputs
 * nothing past attenuation EG_MAX = 123) or holding its level for ever (release rate 0: a sustained patch with
 * RR = 0 and the $2x sustain bit clear). */
int nes_vrc7_release_over(const NesVrc7* v, int channel);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_NES_VRC7_H_ */
