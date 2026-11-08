/* Thor Tracker's YM2203 / YM2608 player: sequences an image (opn_image.h) and produces chip register writes, one batch
 * per tick. Pure integer code without a chip (pitch math uses a 768-entry 2^(i/768) table built once by
 * opn_tables_init, which opnp_reset calls): the machine (opn_machine.c) sends the writes to ymfm, the VGM export
 * (opn_vgm.c) logs them, and seeking runs it alone. The behaviour is specified in docs/AUDIO_ENGINE.md ("OPN and OPNA:
 * player").
 *
 * Writes are (port, reg, val): port 0 = registers $00-$FF (SSG $00-$0F, rhythm $10-$1D, FM $20-$B6 channels 1-3),
 * port 1 = $100-$1FF (YM2608: ADPCM-B $00-$10, FM channels 4-6 $30-$B6). They are diffed against a shadow of both
 * ports (the shadow starts as the chip after opnp_machine_reset's writes); a write equal to the shadow is not emitted,
 * except: key on/off ($28), the SSG envelope shape ($0D: a write restarts the envelope), rhythm key ($10), ADPCM-B
 * control ($100) and the frequency pairs, written high byte first ($A4/$A0, $AC-$AE/$A8-$AA; the chip latches the high
 * byte until the low one) both whenever either differs. A batch has two phases: first every key-off ($28 off, rhythm
 * dump, ADPCM-B stop) in the order the player made them (write[0 .. offs - 1]), then the rest; the machine and VGM
 * separate them by one native sample (a key-off and key-on in the same sample would not retrigger).
 *
 * Shared SSG resources: the mixer ($07) is composed from the three channels' current mixer values; the noise period
 * ($06) is Nxx's, overridden each tick by the highest-numbered SSG channel sounding a note with a noise envelope; the
 * hardware envelope period ($0B/$0C) is written each tick by the highest-numbered SSG channel sounding a note with a
 * hardware envelope, and its shape ($0D, which restarts the generator) at the key-on of such a note.
 *
 * FM 3 modes (OPN_H_FM3_MODE): special = FM 3's four operators each play the note plus their instrument's
 * OPN_I_CH3 offset ($A9/$AD op 1, $AA/$AE op 2, $A8/$AC op 3, $A2/$A6 op 4; $27 bits 7-6 = 01); CSM = the same
 * frequencies, and an FM 3 note does not key on: it sets $27 to $81 (CSM, timer A loaded) so the chip's timer A keys
 * all four operators on every (1024 - NA) native samples until its OFF / Sxx sets $27 back to $40.
 *
 * The player keeps no pointer to an image; every call takes the (copied, loaded) image it should read. No
 * allocation. */
#ifndef THORTRACKER_OPN_PLAYER_H_
#define THORTRACKER_OPN_PLAYER_H_

#include <stdint.h>

#include "opn_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define OPNP_MAX_WRITES 1024             /* per tick; a reset is below 600 */
#define OPNP_PITCH_MAX (8 * 768 - 1)     /* pitch units: 1/64 semitone, 0 = C-0 */
#define OPNP_MAX_RESET_WRITES 512

enum { OPNP_SSG_OFF = 0, OPNP_SSG_ON, OPNP_SSG_RELEASED }; /* SSG note phase */
enum { OPNP_ENV_VOL = 0, OPNP_ENV_ARP, OPNP_ENV_PITCH, OPNP_ENV_MIXER, OPNP_ENV_NOISE, OPNP_ENVS };

/* An SSG envelope copied from the instrument at key-on (instrument edits apply to the next note, as FM's). */
typedef struct OpnEnvelope {
    int length;  /* 0 = no envelope */
    int loop;    /* -1 none */
    int release; /* -1 none */
    int8_t value[OPN_ENV_MAX_LENGTH];
} OpnEnvelope;

typedef struct OpnWrite {
    uint8_t port; /* 0: $000-$0FF, 1: $100-$1FF */
    uint8_t reg;
    uint8_t val;
} OpnWrite;

typedef struct OpnChannel {
    /* row */
    OpnRow row;          /* this row's data (read at the row start) */
    int pending;         /* row delayed by Gxx: processed at tick `delay` */
    int delay;
    int cut;             /* Sxx: key off at this tick of the row, -1 none */
    /* note */
    int instrument;      /* slot for the next note, 0 none */
    int active;          /* a note has been keyed since reset (updates run) */
    int keyed;
    int key_pending;     /* FM / rhythm / ADPCM: the key-on is written by the next update */
    uint8_t ops[4][OPN_OP_BYTES]; /* FM: the note's operator registers (locks applied, TL without volume) */
    int alg, feedback, ams_fms, carriers; /* FM; carriers: bit k = operator k + 1 */
    int ch3[4];          /* FM 3: the note's operator pitch offsets (pitch units) */
    int phase;           /* SSG: OPNP_SSG_OFF / ON / RELEASED */
    int pos[OPNP_ENVS];  /* SSG: envelope positions */
    int level;           /* SSG: this tick's volume envelope value 0..15 */
    int mixer;           /* SSG: this tick's OPN_MIX_* */
    int pitch_relative, pitch_acc; /* SSG: pitch envelope mode and accumulator (pitch units) */
    int env_shape, env_auto, env_octave, env_period; /* SSG: the note's hardware envelope (locks applied) */
    int sample;          /* ADPCM: sample table index of the note */
    int root;            /* ADPCM: the sound's root note */
    int loop;            /* ADPCM: repeat */
    int ins_level;       /* rhythm 0..31 / ADPCM 0..255: the sound's level */
    int base, target;    /* pitch units */
    int fine;
    int arp, arp_step;
    int slide_mode, slide_speed, slide; /* 0 none, 1 up, 2 down, 3 portamento */
    int vib_speed, vib_depth, vib_phase;
    int sweep, sweep_end, sweep_step;
    int vol8;            /* volume x 8: FM 0..1016, SSG 0..120, rhythm 0..248, ADPCM 0..2040 */
    int volslide;        /* Axy param */
    int pan;             /* YM2608 FM / rhythm / ADPCM: bit 7 left, bit 6 right */
} OpnChannel;

typedef struct OpnPlayer {
    int chip, channels, fm_channels;
    int order, row, tick, speed;
    int jump;                    /* row start saw B/D: next position is (jump_order, jump_row) */
    int jump_order, jump_row;
    int audition;                /* audition mode: no sequencing */
    int lfo;                     /* YM2608 register $22 */
    int timer_a;                 /* NA 0..1023 */
    int rhythm_level;            /* YM2608 $11 */
    int fm3_mode;                /* OPN_FM3_* (the image's) */
    int noise_period;            /* Nxx / the image default 0 */
    OpnChannel ch[OPN_MAX_CHANNELS];
    OpnCursor cursor[OPN_MAX_CHANNELS];
    int cursor_order, cursor_row; /* cursors are at (cursor_order, cursor_row), -1 = invalid */
    uint8_t reg[2][256];         /* shadow of both register ports */
    OpnEnvelope env[OPN_SSG][OPNP_ENVS]; /* SSG 1-3: the sounding note's envelopes */
    int writes;
    int offs;                    /* write[0 .. offs - 1] is the key-off phase */
    OpnWrite write[OPNP_MAX_WRITES];
    int pending_offs;            /* key-off writes of the batch being built */
    OpnWrite off[OPN_MAX_CHANNELS * 2];
} OpnPlayer;

/* Builds the shared pitch table (idempotent, thread-safe; opnp_reset calls it). */
void opn_tables_init(void);

/* Reset: shadow = the chip after opnp_machine_reset, then the init writes (header LFO, rhythm total level, timer A and
 * the FM 3 mode on $27, ADPCM-B memory mode $101 / limit) are in write[]. Position (0, 0, 0) with the header speed. */
void opnp_reset(OpnPlayer* p, const OpnImage* image);
/* One tick: write[] receives its writes (the previous batch is dropped). */
void opnp_tick(OpnPlayer* p, const OpnImage* image);
/* Start playing from (order, row 0) with the speed straight play starts that order with (seek fallback). */
void opnp_place(OpnPlayer* p, const OpnImage* image, int order);
/* Live edit at a tick boundary: positions kept (clamped), rows re-read from `next`; song-wide settings (LFO, timer A,
 * rhythm level, FM 3 mode) follow `next` from the next tick when they changed in the image header. */
void opnp_relocate(OpnPlayer* p, const OpnImage* old, const OpnImage* next);
/* Audition: reset, then key on `note` (0..95) on `channel` with instrument `slot` (of the channel's kind) and fine
 * pitch `fine` (pitch units, -128..127); ticks only update. Returns 0 or -1 (bad arguments). */
int opnp_audition(OpnPlayer* p, const OpnImage* image, int channel, int note, int slot, int fine);
/* Key off a channel (audition release) as a new batch in write[] (call between ticks). */
void opnp_key_off(OpnPlayer* p, int channel);

/* The chip init writes after a power-on reset, for machines and the VGM (FM: every operator TL $7F, RR $F, SL $F,
 * every frequency 0, $B4 pans $C0 on the YM2608, keys off, $29 = $80 on the YM2608 (six FM channels); SSG: tones 0,
 * mixer all off, volumes 0; rhythm: dump all, levels 0; ADPCM-B: reset, 8-bit DRAM mode, limit $FFFF). Returns the
 * count (<= OPNP_MAX_RESET_WRITES). */
int opnp_machine_reset(int chip, OpnWrite* out);
/* Whether two players are in the same state: every field that decides their future writes (position, speed, jump,
 * song-wide settings, every channel's note, effect and envelope state, the rows and cursors, the SSG envelopes' used
 * values, the register shadow), compared field by field (never padding); the last batch (write[], writes, offs) is
 * not part of it. Players in the same state produce identical write batches from then on (same image). The VGM export
 * finds its loop with it. */
int opnp_same_state(const OpnPlayer* a, const OpnPlayer* b);
/* A 64-bit FNV-1a digest of the fields opnp_same_state compares (equal states, equal digests). */
uint64_t opnp_state_hash(const OpnPlayer* p);

/* Operator register offset of operator k (0..3 = operators 1..4) from the channel's: +0, +8, +4, +12. */
extern const uint8_t OPNP_OP_OFFSET[4];

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_OPN_PLAYER_H_ */
