/* Thor Tracker's OPL3 player: sequences an image (opl3_image.h) and produces YMF262 register writes,
 * one batch per tick. Pure integer code without a chip: the machine (opl3_machine.c) sends the writes to
 * Nuked-OPL3, the VGM export (opl3_vgm.c) logs them, and seeking runs it alone. The behaviour is
 * specified in docs/AUDIO_ENGINE.md ("OPL3: player"); tools/audio/opl3/opl3_ref.py is an independent
 * Python implementation of the same specification, and the host test compares their writes.
 *
 * Writes are diffed against a shadow of all 512 registers (chip reset state = all 0): a write whose value
 * equals the shadow is not emitted. A batch has two phases: first every write that turns a voice's key-on
 * bit off (0xB0-0xB8 bit 5, 1 -> 0) in the order the player made them (write[0 .. offs - 1]), then the rest.
 * The chip must run at least one sample between the phases (the machine runs one native sample, the VGM
 * waits one 44.1 kHz sample): the YMF262 samples key-on once per sample, so a key-off immediately followed
 * by a key-on would not retrigger the envelope. The player keeps no pointer to an image; every call takes the
 * (copied, loaded) image it should read. No allocation. */
#ifndef THORTRACKER_OPL3_PLAYER_H_
#define THORTRACKER_OPL3_PLAYER_H_

#include <stdint.h>

#include "opl3_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define O3P_MAX_WRITES 1024 /* per tick; the worst case is below 700 */
#define O3P_POOL 9          /* chord voices: 15, 16, 17 and the secondaries 3, 4, 5, 12, 13, 14 of 2-op pairs */
#define O3P_MAX_CHORD 4     /* chord voices per channel (shape: 4 intervals) */
#define O3P_PITCH_MAX (8 * 768 - 1) /* pitch units: 1/64 semitone, 0 = C-0 */

typedef struct O3Write {
    uint16_t reg; /* 0x000-0x0FF bank 0, 0x100-0x1FF bank 1 */
    uint8_t val;
} O3Write;

typedef struct O3Channel {
    /* row */
    O3Row row;          /* this row's data (read at the row start) */
    int pending;        /* row delayed by Gxx: processed at tick `delay` */
    int delay;
    int cut;            /* Sxx: key off at this tick of the row, -1 none */
    /* note */
    int instrument;     /* slot for the next note, 0 none */
    int active;         /* a note has been keyed since reset (pitch/volume updates run) */
    int keyed;
    int four;           /* the playing note's instrument is 4-op (pair mode of channels 0-5) */
    uint8_t ops[4][5];  /* the playing note's operator registers (locks applied, TL without volume) */
    int nops, alg, feedback, carriers; /* carriers: bit k = operator k+1 is a carrier */
    int base, target;   /* pitch units */
    int fine;
    int arp, arp_step;
    int slide_mode, slide_speed, slide; /* 0 none, 1 up, 2 down, 3 portamento */
    int vib_speed, vib_depth, vib_phase;
    int sweep, sweep_end, sweep_step;
    int vol8;           /* volume x 8, 0..504 */
    int volslide;       /* Axy param */
    int pan;            /* 0xC0 bits 4/5 */
    int chord_count;
    int chord_voice[O3P_MAX_CHORD], chord_interval[O3P_MAX_CHORD];
} O3Channel;

typedef struct O3Player {
    int order, row, tick, speed;
    int jump;                    /* row start saw B/D: next position is (jump_order, jump_row) */
    int jump_order, jump_row;
    int audition;                /* audition mode: no sequencing */
    O3Channel ch[O3_CHANNELS];
    int pool_owner[O3P_POOL];    /* channel or -1 */
    int pool_last[O3P_POOL];     /* last owner (mute routing of release tails), -1 never */
    uint32_t pool_stamp[O3P_POOL];
    uint32_t stamp;
    O3Cursor cursor[O3_CHANNELS];
    int cursor_order, cursor_row; /* cursors are at (cursor_order, cursor_row), -1 = invalid */
    uint8_t shadow[512];
    int writes;
    int offs;                    /* write[0 .. offs - 1] is the key-off phase */
    O3Write write[O3P_MAX_WRITES];
    int pending_offs;            /* key-off writes of the batch being built */
    O3Write off[O3_VOICES];
} O3Player;

/* Reset: shadow = chip reset state, then the init writes (0x105 = 1, every C0 = 0x30, 0xBD = header depth) are in write[].
 * Position (0, 0, 0) with the header speed. */
void o3p_reset(O3Player* p, const Opl3Image* image);
/* One tick: write[] receives its writes (the previous batch is dropped). */
void o3p_tick(O3Player* p, const Opl3Image* image);
/* Start playing from (order, row 0) with the speed straight play starts that order with (seek fallback). */
void o3p_place(O3Player* p, const Opl3Image* image, int order);
/* Live edit at a tick boundary: positions kept (clamped), rows re-read from `next` (see the spec). */
void o3p_relocate(O3Player* p, const Opl3Image* old, const Opl3Image* next);
/* Audition: reset, then key on `note` (0..95) on `channel` with instrument `slot` and fine pitch `fine`
 * (pitch units, -128..127); ticks only update pitch/volume. Returns 0 or -1 (bad arguments). */
int o3p_audition(O3Player* p, const Opl3Image* image, int channel, int note, int slot, int fine);
/* Key off a channel (audition release) as a new batch in write[] (call between ticks). */
void o3p_key_off(O3Player* p, int channel);

/* Which tracker channel voice v sounds for (mute routing), or -1. */
int o3p_voice_channel(const O3Player* p, int voice);
/* Primary voice of a tracker channel. */
int o3p_channel_voice(int channel);
/* Carrier operator slots (Nuked slot numbers 0..35) of a channel's playing note (audition silence). */
int o3p_carrier_slots(const O3Player* p, int channel, int* slots);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_OPL3_PLAYER_H_ */
