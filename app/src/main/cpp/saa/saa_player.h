/* Thor Tracker's SAA1099 player: sequences an image (saa_image.h) and produces register writes for one (SAM Coupe) or
 * two (CMS) Philips SAA1099s, one batch per tick. Pure integer code without a chip: the machine (saa_machine.c) sends
 * the writes to the cores (saa_core.h), the VGM export (saa_vgm.c) logs them, and seeking runs it with the cores
 * advanced silently. The behaviour is specified in docs/AUDIO_ENGINE.md ("SAA1099: Player"); tools/audio/saa/saa_ref.py
 * is an independent Python implementation of the same specification, and the host test compares their writes.
 *
 * Writes are {chip, reg, val} (chip 0 or 1, reg 0x00..0x1C) and are diffed against a per-chip shadow of the registers:
 * a write equal to the shadow is not emitted, except forced ones (the reset batch, the external envelope clock, Y01).
 * A batch is applied at one instant in the order made. The player keeps no pointer to an image; every call takes the
 * (copied, loaded) image it should read, and sounding notes read their slot's envelopes from it every tick. No
 * allocation. */
#ifndef THORTRACKER_SAA_PLAYER_H_
#define THORTRACKER_SAA_PLAYER_H_

#include <stdint.h>

#include "saa_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define SAP_MAX_WRITES 128 /* per tick; the reset batch is 22 per chip, a busy CMS tick at most 3 x 12 + 2 x 9 = 54 */
#define SAP_PITCH_MAX 7679 /* pitch units: 1/64 semitone, 0 = C-0; B-9 + 63 */
#define SAP_PITCH_UNITS_PER_SEMITONE 64
#define SAP_PITCH_SPAN 7680 /* slide / relative pitch accumulator limit, pitch units */
#define SAP_VOLUME_MAX 240  /* channel volume in 1/16 steps (Cxx * 16) */
#define SAP_REGS 32         /* shadow size per chip (registers 0x00..0x1F; the player writes 0x00..0x1C) */

typedef struct SaWrite {
    uint8_t chip; /* 0 = chip A (tracker channels 1-6), 1 = chip B (7-12, CMS) */
    uint8_t reg;  /* 0x00..0x1C */
    uint8_t val;
} SaWrite;

typedef struct SaChannel {
    /* row (transient: tick 0 overwrites these before reading them) */
    SaRow row;          /* this row's data (read at the row start) */
    int pending;        /* row delayed by Gxx: processed at tick `delay` */
    int delay;
    int cut;            /* Sxx: key off at this tick of the row, -1 none */
    /* note */
    int instrument;     /* slot for the next note, 0 none */
    int slot;           /* the sounding note's slot, 0 none */
    int active;         /* a note has been keyed and its slot is defined (updates run) */
    int keyed;          /* the note is held (amplitude, mixer) */
    int released;       /* the volume envelope passed its release point */
    int env[4];         /* envelope indexes: volume, arpeggio, pitch, mixer */
    int accumulator;    /* relative pitch envelope, pitch units */
    int base, target;   /* pitch units */
    int fine;           /* Pxx (or the audition's fine), pitch units */
    int arp, arp_step;  /* 0xy param, step 0..2 */
    int slide_mode, slide_speed, slide; /* 0 none, 1 up, 2 down, 3 portamento */
    int vib_speed, vib_depth, vib_phase;
    int volume;         /* channel volume 0..240 (1/16 steps) */
    int volslide;       /* Axy param */
    /* note-scoped overrides, -1 = none */
    int pan;            /* 8xy: x left, y right */
    int mixer;          /* Mxx 0..3 */
    int noise;          /* Nxx 0..3 */
    int envelope;       /* Exx: the control value (0 = off) */
    /* passes */
    int keyon;          /* this tick is the note's key-on tick */
    int mix;            /* mixer value of this tick (0 when not active and keyed) */
    int last_mix;       /* mixer value of the previous tick */
    int last_noise;     /* noise clock of the previous tick (0 when not keyed) */
    int ext_counter;    /* ticks toward the next external envelope clock */
    int ext_clocks;     /* external envelope clocks given to this note's envelope */
} SaChannel;

typedef struct SaPlayer {
    int variant;                 /* SA_VARIANT_* of the image the player was reset with */
    int channels, chips;         /* 6 / 1 or 12 / 2 */
    int order, row, tick, speed;
    int jump;                    /* row start saw B/D: next position is (jump_order, jump_row) */
    int jump_order, jump_row;
    int audition;                /* audition mode: no sequencing */
    int audition_channel;
    int noise_value[2][2];       /* [chip][generator]: the clock of the latest noise claim */
    int sync_cmd[2];             /* [chip]: Yxx of a row run this tick, -1 none (transient) */
    SaChannel ch[SA_MAX_CHANNELS];
    SaCursor cursor[SA_MAX_CHANNELS];
    int cursor_order, cursor_row; /* cursors are at (cursor_order, cursor_row), -1 = invalid */
    uint8_t sh[2][SAP_REGS];     /* shadow of each chip's registers */
    int writes;
    SaWrite write[SAP_MAX_WRITES];
} SaPlayer;

/* Reset: shadow and writes as specified (every register written, forced); position (0, 0, 0) with the header speed. */
void sap_reset(SaPlayer* p, const SaaImage* image);
/* One tick: write[] receives its writes (the previous batch is dropped). */
void sap_tick(SaPlayer* p, const SaaImage* image);
/* Start playing from (order, row 0) with the speed straight play starts that order with (seek fallback). */
void sap_place(SaPlayer* p, const SaaImage* image, int order);
/* Live edit at a tick boundary: positions kept (clamped), rows re-read from `next` (see the spec). */
void sap_relocate(SaPlayer* p, const SaaImage* old, const SaaImage* next);
/* Audition: reset, then key on `note` (0..119) on `channel` (0..C - 1) with instrument `slot` and fine pitch `fine`
 * (pitch units, -128..127); ticks only update that channel and its chip's passes. Returns 0 or -1 (bad arguments,
 * undefined slot). */
int sap_audition(SaPlayer* p, const SaaImage* image, int channel, int note, int slot, int fine);
/* Key off a channel (audition release; call between ticks): empties write[] and arms the channel's cut for the next
 * tick, whose cut step (before the updates) releases or cuts the note as an OFF would. */
void sap_key_off(SaPlayer* p, int channel);
/* Octave and offset of a pitch (clamped to 0..SAP_PITCH_MAX) on the SAM Coupe's (cms 0) or the CMS's (cms 1) clock:
 * O << 8 | N. */
int sap_octave_offset(int pitch, int cms);
/* True when a released audition channel can never sound again (not keyed, or its released volume envelope holds a
 * silent value that has been written). */
int sap_silent(const SaPlayer* p, const SaaImage* image, int channel);
/* Whether two players are in the same state: every field except the write buffer and the transient row state (row
 * data, cursors, delays, cuts, pending Yxx), compared field by field (the VGM loop search). */
int sap_same_state(const SaPlayer* a, const SaPlayer* b);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_SAA_PLAYER_H_ */
