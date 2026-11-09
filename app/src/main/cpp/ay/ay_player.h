/* Thor Tracker's AY-3-8910 / YM2149 player: sequences an image (ay_image.h) and produces register writes (R0-R13),
 * one batch per tick. Pure integer code without a chip: the machine (ay_machine.c) applies the writes to its chip
 * core, the VGM export (ay_vgm.c) logs them, and seeking runs it with the chip silently clocked. The behaviour is
 * specified in docs/AUDIO_ENGINE.md ("AY-3-8910 / YM2149: player", "Shared noise and envelope");
 * tools/audio/ay/ay_ref.py is an independent Python implementation of the same specification, and the host test
 * compares their writes.
 *
 * Writes to R0-R12 are diffed against a shadow (reset state: all 0); a write equal to the shadow is not emitted. R13
 * is written only when the player restarts the envelope, and always emitted then. The player keeps no pointer to an
 * image (rows are read by (order, row) at the row start; envelopes are copied at key-on); every call takes the
 * (copied, loaded) image it should read. No allocation. */
#ifndef THORTRACKER_AY_PLAYER_H_
#define THORTRACKER_AY_PLAYER_H_

#include <stdint.h>

#include "ay_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define AYP_MAX_WRITES 16 /* per tick: 6 tone + R6 + R7 + 3 amplitudes + R11/R12 + R13 = 14 */
#define AYP_SHADOW 13     /* R0-R12 */

enum { AYP_OFF = 0, AYP_ON, AYP_RELEASED }; /* note phase */
enum { AYP_ENV_VOLUME = 0, AYP_ENV_ARPEGGIO, AYP_ENV_PITCH, AYP_ENV_MIXER, AYP_ENV_NOISE, AYP_ENVELOPES };

typedef struct AyWrite {
    uint8_t reg;
    uint8_t val;
} AyWrite;

/* An instrument envelope copied at key-on (instrument edits apply from the next note). */
typedef struct AyEnvelope {
    int length;  /* 0 = no envelope */
    int loop;    /* -1 none */
    int release; /* -1 none */
    int8_t value[AY_ENV_MAX_LENGTH];
} AyEnvelope;

/* The note settings a key-on loads from the instrument and E/H/I/J/V/W change. */
typedef struct AySettings {
    int shape;  /* 0 = fixed volume, 8..15 */
    int autop;  /* the envelope period follows the note */
    int octave; /* J, -8..7 */
    int period; /* manual envelope period 0..65535 */
    int sync;   /* tone synced to the envelope (auto only) */
    int mixer;  /* override 0..3, -1 none */
    int noise;  /* override 0..31, -1 none */
} AySettings;

typedef struct AyChannel {
    /* row */
    AyRow row;           /* this row's data (read at the row start) */
    int pending;         /* row delayed by Gxx: processed at tick `delay` */
    int delay;
    int cut;             /* Sxx: key off at this tick of the row, -1 none */
    /* note */
    int instrument;      /* slot for the next note, 0 none */
    int active;          /* a note has been keyed since reset (evaluated every tick) */
    int phase;           /* AYP_OFF / ON / RELEASED */
    int pos[AYP_ENVELOPES];
    int pitch_relative, pitch_acc;
    AySettings set;
    int restart;         /* pending while silent; consumed when using the shared envelope (owner or not), or off */
    uint32_t stamp;      /* key-on order: the highest stamp owns the shared noise and envelope */
    int base, target;    /* pitch units */
    int fine;
    int arp, arp_step;
    int slide_mode, slide_speed, slide; /* 0 none, 1 up, 2 down, 3 portamento */
    int vib_speed, vib_depth, vib_phase;
    int sweep, sweep_end, sweep_step;
    int vol8;            /* volume x 8: 0..120 */
    int volslide;        /* Axy param */
    /* this tick's evaluation */
    int sounds, uses_env, amp, mixer, nz, tp, ep;
} AyChannel;

typedef struct AyPlayer {
    int order, row, tick, speed;
    int jump;                    /* row start saw B/D: next position is (jump_order, jump_row) */
    int jump_order, jump_row;
    int audition;                /* audition mode: no sequencing */
    int variant;
    int io;                      /* R7 bits 7-6 */
    AyChannel ch[AY_CHANNELS];
    AyCursor cursor[AY_CHANNELS];
    int cursor_order, cursor_row; /* cursors are at (cursor_order, cursor_row), -1 = invalid */
    uint32_t stamps;             /* key-on counter */
    int owner;                   /* envelope owner of the last tick, -1 none */
    int last_r13;                /* the last shape written to R13, -1 none since reset */
    uint8_t shadow[AYP_SHADOW];
    int writes;
    AyWrite write[AYP_MAX_WRITES];
    AyEnvelope env[AY_CHANNELS][AYP_ENVELOPES];
} AyPlayer;

/* Reset: shadow 0, position (0, 0, 0) with the header speed; write[] = the reset batch (R7 = io | 0x3F). */
void ay_player_reset(AyPlayer* p, const AyImage* image);
/* One tick: write[] receives its writes (the previous batch is dropped). */
void ay_player_tick(AyPlayer* p, const AyImage* image);
/* Reset, then start playing from (order, row 0) with the speed straight play starts that order with (seek fallback). */
void ay_player_place(AyPlayer* p, const AyImage* image, int order);
/* Live edit at a tick boundary: positions kept (clamped), rows re-read from `next` (see the spec). */
void ay_player_relocate(AyPlayer* p, const AyImage* old, const AyImage* next);
/* Audition: reset, then key on `note` (0..95) on `channel` (0..2) with instrument `slot` (1..64) and its own settings,
 * fine pitch `fine` (pitch units, -128..127); ticks only evaluate and advance. write[] = the reset batch. Returns 0 or
 * -1 (bad arguments or undefined slot). */
int ay_player_audition(AyPlayer* p, const AyImage* image, int channel, int note, int slot, int fine);
/* Key off a channel (audition release); applies from the next tick (no writes). */
void ay_player_key_off(AyPlayer* p, int channel);

/* Pitch to periods (variant tables; units as the spec's "Pitch"). */
int ay_tone_period(int variant, int pitch);
int ay_envelope_period(int variant, int pitch, int octave, int shape);
int ay_synced_tone_period(int envelope_period, int octave, int shape);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_AY_PLAYER_H_ */
