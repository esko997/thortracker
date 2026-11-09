/* The Master System / Game Gear music machine: the Banjo sound driver (generated/banjo_driver_image.h: joffb/banjo
 * 7aaf837 with Thor Tracker's host routines, assembled by tools/audio/prepare_sms.py) on a Z80 (floooh z80.h) with
 * the Sega SN76489 (ym/ym_psg.h, shared with the Mega Drive; the Game Gear's stereo register $06) and, for FM songs,
 * a YM2413 (emu2413 1.6.4). One tick = one NTSC frame (228 x 262 = 59736 Z80 cycles at 3579545 Hz, 59.92 Hz): the
 * machine calls banjo_update_song at the frame's start as a game's vertical interrupt does, and the chips render the
 * frame with the driver's writes at the Z80 cycles they were made. Output band-limited to the host rate (blip_buf).
 *
 * Z80 address space (what Banjo sees in a game):
 *   $0000-$3FFF  the driver bank (BANJO_DRIVER); an opcode fetch anywhere else (but the return trap) is a fault
 *   $4000-$BFFF  the song region of the image (Banjo song data at $4000, read only)
 *   $C000-$DFFF  RAM (mirrored at $E000): the driver's variables and the 15 channel structs, audition song at
 *                SMS_AUDITION_RAM, the stack below SMS_STACK_TOP
 * I/O writes: $40-$7F SN76489, $F0/$F1 YM2413 address/data, $F2 audio control, $06 Game Gear stereo (Game Gear
 * layout only). Reads return $FF (the driver's hardware probe, banjo_check_hardware, is not called: the machine sets
 * banjo_has_chips and the Game Gear flag itself, as a game does after probing).
 *
 * Behaviour (seek with clocked silent replay, live edits at the playhead, audition through a one-note song, mute) is
 * specified in docs/AUDIO_ENGINE.md, "Master System". No allocation after smsm_create. One thread at a time. */
#ifndef THORTRACKER_SMS_MACHINE_H_
#define THORTRACKER_SMS_MACHINE_H_

#include <stdint.h>

#include "sms_image.h"
#include "ym/ym_psg.h"

#ifdef __cplusplus
extern "C" {
#endif

#define SMSM_MAX_SAMPLE_RATE 96000u
#define SMSM_CLOCK 3579545u            /* Z80, SN76489 and YM2413 clock (NTSC) */
#define SMSM_CYCLES_PER_TICK 59736u    /* 228 cycles x 262 lines */
#define SMSM_MAX_TICK_FRAMES 1605u     /* 96000 x 59736 / 3579545 + 3 */
#define SMSM_RELEASE_SECONDS 10        /* a released audition ends after this long even if it never falls silent */
#define SMSM_LOG_MAX 2048              /* chip writes kept per tick (test hook, VGM export) */
#define SMS_AUDITION_RAM 0xD000        /* the audition song (header, order, tracks) */
#define SMS_STACK_TOP 0xDFF0
#define SMS_RETURN_TRAP 0xFFF0         /* calls return here; the machine stops at its fetch */

enum { SMSM_OK = 0, SMSM_ERR_IMAGE = -1, SMSM_ERR_FAULT = -2 };
enum { SMSM_SEEK_FROM_START = 0, SMSM_SEEK_FROM_ORDER = 1 };

/* One chip write, at a Z80 cycle of the tick (cycle 0 = the start of the driver call; writes made while the song is
 * reset and started have cycle 0 of the first tick). */
enum { SMSM_W_PSG = 0, SMSM_W_OPLL = 1, SMSM_W_PAN = 2 };
typedef struct SmsmWrite {
    uint32_t cycle;
    uint8_t kind, reg, val; /* reg: the YM2413 register (OPLL writes) */
} SmsmWrite;

typedef struct SmsMachine SmsMachine;

SmsMachine* smsm_create(uint32_t sample_rate);
void smsm_destroy(SmsMachine* m);

/* Reset, the driver initialised for the image's chips (banjo_init; the Game Gear flag on the Game Gear layout), the
 * song started (banjo_play_song), then positioned so the next tick plays (order, row): silent ticks from the song start,
 * at most max_ticks (SMSM_SEEK_FROM_START); past the budget the channels are placed at the order's start with the
 * speeds straight play enters it with, plus its carried instruments and shared FM voice; its earlier rows are
 * replayed (SMSM_SEEK_FROM_ORDER). Both paths clock the chips silently; only full replay preserves earlier held
 * notes, oscillator, envelope and LFO history. The bounded fallback starts cold at the selected order. */
int smsm_start(SmsMachine* m, const SmsImage* image, int order, int row, int max_ticks, int* seek_mode);

/* Live edit at a tick boundary (realtime-safe): the song continues at its order and row in the new data (pending
 * note delays kept where the new row has the same delay), instruments' envelope pointers follow their records, the
 * custom FM patch is rewritten when the voice the chip holds was edited. Ignored while faulted or auditioning, or
 * for an image of another layout. */
void smsm_apply_image(SmsMachine* m, const SmsImage* image);

/* One held note through the driver: a one-order song in RAM whose `channel` track loads instrument `slot` (1-based)
 * and plays driver note `note` (0-127: SN channels count from A-2 + 9, i.e. absolute - 24; FM and rhythm channels
 * absolute). `fine` must be 0 (Banjo has no fine pitch). */
int smsm_audition(SmsMachine* m, const SmsImage* image, int channel, int note, int slot, int fine);

/* Key-off of the audition: an instrument whose volume envelope has a release part releases it (NOTE_RELEASE); FM and
 * rhythm channels key off (NOTE_OFF; the chip's release plays). Returns 1 when a release follows (smsm_tick returns 0
 * once the output has been silent for 3 ticks, or after SMSM_RELEASE_SECONDS), 0 when the note simply stops (an SN
 * sound without a release part). */
int smsm_release(SmsMachine* m);

/* Leaves a song channel out of the chips' output (the driver and the chips run on). */
int smsm_mute(SmsMachine* m, int channel, int muted);

/* The row the next tick plays: (order, row, tick in the row; 0 = the next tick starts it). */
void smsm_position(const SmsMachine* m, int* order, int* row, int* tick);

/* One tick: float stereo frames into out (<= SMSM_MAX_TICK_FRAMES); 0 once a released audition has ended;
 * SMSM_ERR_FAULT if the driver faulted. */
int smsm_tick(SmsMachine* m, float* out);
/* One tick without the chips clocked (their registers follow the writes): register-trace VGM export only. */
int smsm_tick_silent(SmsMachine* m);

/* ---- diagnostics and test hooks ---- */
uint8_t smsm_peek(const SmsMachine* m, uint16_t addr);  /* the Z80's view of memory */
uint32_t smsm_ticks(const SmsMachine* m);                /* ticks since start/audition (seek excluded) */
uint32_t smsm_last_call_cycles(const SmsMachine* m);     /* Z80 cycles of the last driver call */
/* Chip writes of the last tick (or of the reset and song start before the first tick) when enabled. */
void smsm_set_log(SmsMachine* m, int enabled);
const SmsmWrite* smsm_log(const SmsMachine* m, int* count);
const YmPsg* smsm_psg(const SmsMachine* m);
const uint8_t* smsm_opll_registers(const SmsMachine* m); /* the 64 YM2413 registers as written */
uint8_t smsm_pan(const SmsMachine* m);                   /* the Game Gear stereo register */
int smsm_faulted(const SmsMachine* m);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_SMS_MACHINE_H_ */
