/* Thor Tracker's SCC player: sequences an image (scc_image.h) and produces K051649 / K052539 register writes, one
 * batch per tick. Pure integer code without a chip: the machine (scc_machine.c) sends the writes to the core
 * (scc_k051649.h), the VGM export (scc_vgm.c) logs them, and seeking runs it with the core advanced silently. The
 * behaviour is specified in docs/AUDIO_ENGINE.md ("SCC and SCC+: Player"); tools/audio/scc/scc_ref.py is an independent
 * Python implementation of the same specification, and the host test compares their writes.
 *
 * Writes use the VGM 0xD2 port numbering (SCK_PORT_*: wave port 0 on the SCC, 4 on the SCC+) and are diffed against a
 * shadow of what the chip holds: a write equal to the shadow is not emitted, except forced ones (the reset batch, the
 * period at key-on). Wave writes to a RAM the test register protects are never made. A batch is applied at one instant
 * in the order made. The player keeps no pointer to an image; every call takes the (copied, loaded) image it should
 * read, and sounding notes read their slot's envelopes and waves from it every tick. No allocation. */
#ifndef THORTRACKER_SCC_PLAYER_H_
#define THORTRACKER_SCC_PLAYER_H_

#include <stdint.h>

#include "scc_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define SCP_MAX_WRITES 512 /* per tick; the reset batch is 145 (SCC) / 177 (SCC+), a busy tick < 200 */
#define SCP_PITCH_MAX (8 * 768 - 1) /* pitch units: 1/64 semitone, 0 = C-0 */
#define SCP_PERIOD_MAX 4095
#define SCP_VOLUME_MAX 240 /* channel volume in 1/16 steps (Cxx * 16) */

typedef struct ScWrite {
    uint8_t port; /* SCK_PORT_* (0 wave SCC, 1 period, 2 volume, 3 key, 4 wave SCC+, 5 test) */
    uint8_t reg;
    uint8_t val;
} ScWrite;

typedef struct ScChannel {
    /* row */
    ScRow row;          /* this row's data (read at the row start) */
    int pending;        /* row delayed by Gxx: processed at tick `delay` */
    int delay;
    int cut;            /* Sxx: key off at this tick of the row, -1 none */
    /* note */
    int instrument;     /* slot for the next note, 0 none */
    int slot;           /* the sounding note's slot, 0 none */
    int active;         /* a note has been keyed and its slot is defined (updates run) */
    int keyed;          /* key enable bit */
    int released;       /* the volume envelope passed its release point */
    int env[4];         /* envelope indexes: volume, arpeggio, pitch, wave */
    int accumulator;    /* relative pitch envelope, pitch units */
    int force;          /* the next update writes the period forced */
    int restart;        /* ... wrapped in test | 0x20 (RESTART instrument at key-on) */
    int base, target;   /* pitch units */
    int fine;
    int arp, arp_step;  /* 0xy param, step 0..2 */
    int slide_mode, slide_speed, slide; /* 0 none, 1 up, 2 down, 3 portamento */
    int vib_speed, vib_depth, vib_phase;
    int volume;         /* channel volume 0..240 (1/16 steps) */
    int volslide;       /* Axy param */
    int wave_lock;      /* Wxx: 0 none, 1..64 */
    int wave;           /* wave number played at the last update, 0 none */
} ScChannel;

typedef struct ScPlayer {
    int variant;                 /* SC_VARIANT_* of the image the player was reset with */
    int order, row, tick, speed;
    int jump;                    /* row start saw B/D: next position is (jump_order, jump_row) */
    int jump_order, jump_row;
    int audition;                /* audition mode: no sequencing */
    int test;                    /* the test register value the song set (Txx) */
    int owner3;                  /* SCC: channel (3 or 4) whose wave RAM 3 holds, -1 none */
    ScChannel ch[SC_CHANNELS];
    ScCursor cursor[SC_CHANNELS];
    int cursor_order, cursor_row; /* cursors are at (cursor_order, cursor_row), -1 = invalid */
    /* shadow of the chip registers */
    uint8_t sh_test, sh_key;
    uint8_t sh_period[2 * SC_CHANNELS];
    uint8_t sh_volume[SC_CHANNELS];
    int8_t sh_ram[SC_CHANNELS][SC_WAVE_BYTES];
    int writes;
    ScWrite write[SCP_MAX_WRITES];
} ScPlayer;

/* Reset: shadow and writes as specified (every register written, forced); position (0, 0, 0) with the header speed. */
void scp_reset(ScPlayer* p, const SccImage* image);
/* One tick: write[] receives its writes (the previous batch is dropped). */
void scp_tick(ScPlayer* p, const SccImage* image);
/* Start playing from (order, row 0) with the speed straight play starts that order with (seek fallback). */
void scp_place(ScPlayer* p, const SccImage* image, int order);
/* Live edit at a tick boundary: positions kept (clamped), rows re-read from `next` (see the spec). */
void scp_relocate(ScPlayer* p, const SccImage* old, const SccImage* next);
/* Audition: reset, then key on `note` (0..95) on `channel` (0..4) with instrument `slot` and fine pitch `fine`
 * (pitch units, -128..127); ticks only update that channel. Returns 0 or -1 (bad arguments, undefined slot). */
int scp_audition(ScPlayer* p, const SccImage* image, int channel, int note, int slot, int fine);
/* Key off a channel (audition release; call between ticks): empties write[] and arms the channel's cut for the next
 * tick, whose cut step (before the updates) releases or cuts the note as an OFF would; the key register follows in that
 * tick's batch, so the chip sees the key-off when an immediate one would have reached it. */
void scp_key_off(ScPlayer* p, int channel);
/* The period of a pitch (0..SCP_PITCH_MAX), clamped to SCP_PERIOD_MAX. */
int scp_period(int pitch);
/* True when a released audition channel can never sound again (key off, or its released volume envelope holds a
 * silent value). */
int scp_silent(const ScPlayer* p, const SccImage* image, int channel);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_SCC_PLAYER_H_ */
