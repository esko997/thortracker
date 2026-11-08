/* Thor Tracker's YM2612 player: sequences an image (ym_image.h) and produces YM2612 and SN76489 register writes,
 * one batch per tick. Pure integer code without a chip: the machine (ym_machine.c) encodes the writes into XGC frames
 * for SGDK's XGM driver, the VGM/XGM export (ym_vgm.c, ym_xgm.c) logs them, and seeking runs it alone. The behaviour
 * is specified in docs/AUDIO_ENGINE.md ("YM2612: player"); tools/audio/ym/ym_ref.py is an independent Python
 * implementation of the same specification, and the host test compares their writes.
 *
 * YM writes are diffed against a shadow of both ports' registers, PSG writes against a shadow of the tone periods,
 * attenuations and noise control (the shadow starts as the chips after SGDK's YM2612_reset() + PSG_reset()); a write
 * equal to the shadow is not emitted, except that the frequency pair $A4/$A0 is written together when either
 * changes. Key-on/off ($28) follows the channels' key state. A batch has two phases: first every $28 key-off in the
 * order the player made them (write[0 .. offs - 1]), then the rest; the VGM separates them by one sample (a key-off
 * and key-on in the same sample would not retrigger on a VGM player's chip). The player keeps no pointer to an
 * image; every call takes the (copied, loaded) image it should read. No allocation. */
#ifndef THORTRACKER_YM_PLAYER_H_
#define THORTRACKER_YM_PLAYER_H_

#include <stdint.h>

#include "ym_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define YMP_MAX_WRITES 1024       /* per tick; a reset or loop dump frame is below 400 */
#define YMP_PITCH_MAX (8 * 768 - 1) /* pitch units: 1/64 semitone, 0 = C-0 */
#define YMP_MACHINE_RESET_WRITES (3 + 2 * 3 * 4 * 7 + 2 * 3 * 6 + 6 + 4 * 3)
#define YMP_DUMP_WRITES (1 + 6 * (4 * 7 + 4) + 3 * 3 + 2)

enum { YM_W_PORT0 = 0, YM_W_PORT1 = 1, YM_W_PSG = 2 };
enum { YM_ENV_ATTACK = 0, YM_ENV_DECAY, YM_ENV_SUSTAIN, YM_ENV_RELEASE, YM_ENV_OFF };

typedef struct YmWrite {
    uint8_t kind; /* YM_W_PORT0 / YM_W_PORT1 (reg, val) or YM_W_PSG (val = the byte) */
    uint8_t reg;
    uint8_t val;
} YmWrite;

typedef struct YmChannel {
    /* row */
    YmRow row;           /* this row's data (read at the row start) */
    int pending;         /* row delayed by Gxx: processed at tick `delay` */
    int delay;
    int cut;             /* Sxx: key off at this tick of the row, -1 none */
    /* note */
    int instrument;      /* slot for the next note, 0 none */
    int active;          /* a note has been keyed since reset (updates run) */
    int keyed;
    int key_pending;     /* FM: the key-on is written by the next update */
    uint8_t ops[4][YM_OP_BYTES]; /* FM: the note's operator registers (locks applied, TL without volume) */
    int alg, feedback, ams_fms, carriers; /* carriers: bit k = operator k + 1 */
    int env, phase, counter, attack, decay, sustain, release; /* PSG envelope */
    int noise;           /* Noise: the note's mode (bit 2 white, bits 0-1 source) */
    int base, target;    /* pitch units */
    int fine;
    int arp, arp_step;
    int slide_mode, slide_speed, slide; /* 0 none, 1 up, 2 down, 3 portamento */
    int vib_speed, vib_depth, vib_phase;
    int sweep, sweep_end, sweep_step;
    int vol8;            /* volume x 8: FM 0..1016, PSG 0..120 */
    int volslide;        /* Axy param */
    int pan;             /* FM: $B4 bits 7/6 */
} YmChannel;

typedef struct YmPlayer {
    int order, row, tick, speed;
    int jump;                    /* row start saw B/D: next position is (jump_order, jump_row) */
    int jump_order, jump_row;
    int audition;                /* audition mode: no sequencing */
    int region;
    int lfo;                     /* register $22 */
    YmChannel ch[YM_CHANNELS];
    YmCursor cursor[YM_CHANNELS];
    int cursor_order, cursor_row; /* cursors are at (cursor_order, cursor_row), -1 = invalid */
    uint8_t ym[2][256];          /* shadow of the YM2612 registers (port 0, 1) */
    uint16_t tone[3];            /* shadow of the PSG tone periods */
    uint8_t att[4];              /* shadow of the PSG attenuations */
    uint8_t noise_ctrl;          /* shadow of the PSG noise control */
    int writes;
    int offs;                    /* write[0 .. offs - 1] is the key-off phase */
    YmWrite write[YMP_MAX_WRITES];
    int pending_offs;            /* key-off writes of the batch being built */
    YmWrite off[YM_FM_CHANNELS * 2];
} YmPlayer;

/* Reset: shadow = the chips after the machine reset writes, then the init write ($22 = header LFO) is in write[].
 * Position (0, 0, 0) with the header speed. */
void ymp_reset(YmPlayer* p, const YmImage* image);
/* One tick: write[] receives its writes (the previous batch is dropped). */
void ymp_tick(YmPlayer* p, const YmImage* image);
/* Start playing from (order, row 0) with the speed straight play starts that order with (seek fallback). */
void ymp_place(YmPlayer* p, const YmImage* image, int order);
/* Live edit at a tick boundary: positions kept (clamped), rows re-read from `next` (see the spec). */
void ymp_relocate(YmPlayer* p, const YmImage* old, const YmImage* next);
/* Audition: reset, then key on `note` (0..95) on `channel` with instrument `slot` (FM slots on 0-5, PSG on 6-9) and
 * fine pitch `fine` (pitch units, -128..127); ticks only update. Returns 0 or -1 (bad arguments). */
int ymp_audition(YmPlayer* p, const YmImage* image, int channel, int note, int slot, int fine);
/* Key off a channel (audition release) as a new batch in write[] (call between ticks). */
void ymp_key_off(YmPlayer* p, int channel);

/* The machine reset writes (SGDK's YM2612_reset() then PSG_reset()), YMP_MACHINE_RESET_WRITES records. */
int ymp_machine_reset(YmWrite* out);
/* The player's register state as writes (VGM loop dump, seek restore), YMP_DUMP_WRITES records: $22; per FM channel
 * operators 1..4 $30-$90, $B0, $B4, $A4, $A0; per tone period (two bytes) and attenuation; noise control,
 * attenuation 3. */
int ymp_dump(const YmPlayer* p, YmWrite* out);
/* Pitched noise owns PSG 3 (channel 8 is silent). */
int ymp_noise_owns_tone3(const YmPlayer* p);
/* Operator register offset of operator k (0..3 = operators 1..4) from the channel's: +0, +8, +4, +12. */
extern const uint8_t YMP_OP_OFFSET[4];

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_YM_PLAYER_H_ */
