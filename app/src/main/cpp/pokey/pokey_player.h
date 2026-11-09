/* Thor Tracker's POKEY player: sequences an image (pokey_image.h) and produces POKEY register writes - one batch per
 * tick (the video frame interrupt) and one per timer interrupt (sample playback, pkp_irq). Pure C without a chip
 * (integers, apart from the pitch search's doubles): the machine (pokey_machine.c) sends the writes to the chip
 * model, the VGM export (pokey_vgm.c) logs them, and seeking runs it alone to count ticks. The behaviour is specified
 * in docs/AUDIO_ENGINE.md ("POKEY: player"); tools/audio/pokey/pokey_ref.py implements the same specification
 * separately and the host test requires identical writes.
 *
 * Writes go through a shadow of the 16 registers: a write equal to the shadow is not emitted, except the reset batch
 * (emitted in full), STIMER and the R pair. A tick's batch is in the order SKCTL (R: SKCTL with bits 0-1 cleared, then
 * SKCTL), AUDCTL, AUDF1, AUDC1, .. AUDF4, AUDC4, IRQEN, STIMER. The player keeps no pointer to an image; every call
 * takes the (copied, loaded) image it should read. No allocation. */
#ifndef THORTRACKER_POKEY_PLAYER_H_
#define THORTRACKER_POKEY_PLAYER_H_

#include <stdint.h>

#include "pokey_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define PKP_MAX_WRITES 16                   /* per batch: a tick writes at most 13, the reset batch 12 */
#define PKP_PITCH_MAX (PK_NOTES * 64 - 1)    /* 6911: pitch units 1/64 semitone, 0 = C-0 */
#define PKP_PITCH_A4 (57 * 64)              /* 3648 = 440 Hz */
#define PKP_RELATIVE_LIMIT 1728             /* relative pitch accumulator, sixteenths of a semitone (+-) */
#define PK_PCM_MIN_PERIOD 114               /* shortest sample level period in cycles: one scan line */

/* Sound kinds (from the instrument flags). */
enum { PKP_TONE, PKP_JOINED, PKP_FILTERED, PKP_JOINED_FILTERED, PKP_SAMPLE };
/* Envelopes of a sound. */
enum { PKP_ENV_VOLUME, PKP_ENV_ARPEGGIO, PKP_ENV_PITCH, PKP_ENV_DISTORTION, PKP_ENVS };

typedef struct PkWrite {
    uint8_t reg; /* 0x0..0xF */
    uint8_t val;
} PkWrite;

/* The sound a lane's last note keyed (its POKEY channels, its own first). */
typedef struct PkVoice {
    int active;       /* holds its channels (until its lane's next note or a note that takes one of them) */
    int released;     /* OFF / Sxx seen */
    int silenced;     /* released without a volume release point: level 0, envelopes stopped */
    int kind, flags, slot;
    int channels[4], nchannels;
    int filter_offset; /* s8 */
    int partner;       /* AUDC of the taken channels */
    int sample, root, rate, loop; /* samples: index, root note, levels per second at the root, loop level (0xFFFF none) */
    int playing, index;           /* samples: a level is playing, its index */
    int env_step[PKP_ENVS];
    int pitch_acc;     /* relative pitch envelope: sixteenths */
    int level;         /* the own channel's level of the last update (0..15) */
    int counter, clock_counter; /* the counters (N, high-pass clock N') of the last update */
    int pitch;         /* pitch units of the last update */
    int memo[6], memo_valid; /* the search's inputs of `counter` (a pure cache: the same inputs give the same N) */
} PkVoice;

typedef struct PkLane {
    /* row */
    PkRow row;
    int pending, delay; /* row delayed by Gxx: processed at tick `delay` */
    int cut;            /* Sxx: release at this tick of the row, -1 none */
    /* note */
    int instrument;
    int base, target;   /* pitch units */
    int fine;
    int arp, arp_step;
    int slide_mode, slide_speed, slide; /* 0 none, 1 up, 2 down, 3 portamento */
    int vib_speed, vib_depth, vib_phase;
    int vol8;           /* volume x 8, 0..120 */
    int volslide;       /* Axy */
    int distortion;     /* Vxx override 0..8, -1 none */
    int has_filter, filter; /* Hxx override (s8) */
    PkVoice voice;
} PkLane;

typedef struct PkPlayer {
    int order, row, tick, speed;
    int jump, jump_order, jump_row;
    int audition;
    int shared;     /* shared AUDCTL bits: PK_AUDCTL_POLY9 | PK_AUDCTL_BASE15 */
    int two_tone;   /* Wxx: 0 off, 1 on, 2 on with force break */
    int restart, stimer; /* R00 / T00 seen in this tick */
    PkLane lane[PK_CHANNELS];
    int holder[4];  /* lane holding POKEY channel c, -1 none */
    PkCursor cursor[PK_CHANNELS];
    int cursor_order, cursor_row; /* cursors are at (cursor_order, cursor_row), -1 = invalid */
    uint8_t want[16];   /* the registers the player wants (AUDF/AUDC persist between ticks) */
    uint8_t shadow[16]; /* what it has written */
    int writes;
    PkWrite write[PKP_MAX_WRITES];
} PkPlayer;

/* Reset: all state cleared, header speed and shared AUDCTL bits, position (0, 0, 0); write[] = the reset batch
 * SKCTL 0, AUDCTL = header byte 13, AUDF1..AUDC4 = 0, IRQEN 0, SKCTL 3 (all emitted). */
void pkp_reset(PkPlayer* p, const PokeyImage* image);
/* One tick: write[] receives its batch (the previous batch is dropped). */
void pkp_tick(PkPlayer* p, const PokeyImage* image);
/* Timer interrupt: `timers` (PK_IRQ_*) fired; write[] receives the sample routine's writes (possibly none). */
void pkp_irq(PkPlayer* p, const PokeyImage* image, uint8_t timers);
/* Start playing from (order, row 0) with the speed, shared AUDCTL bits and two-tone state sequential straight play
 * starts that order with (seek fallback). write[] = the reset batch. */
void pkp_place(PkPlayer* p, const PokeyImage* image, int order);
/* Live edit at a tick boundary: positions kept (clamped), rows re-read from `next` (see the spec). */
void pkp_relocate(PkPlayer* p, const PokeyImage* old, const PokeyImage* next);
/* Audition: reset, then key on `note` (0..107) on `lane` (0..3) with instrument `slot` and fine pitch `fine` (pitch
 * units, -128..127); ticks only update the voice. Returns 0 or -1 (bad arguments, undefined slot, a sound that
 * cannot play on that lane). write[] = the reset batch. */
int pkp_audition(PkPlayer* p, const PokeyImage* image, int lane, int note, int slot, int fine);
/* OFF of a lane's voice (audition release): state only, heard from the next tick's batch. */
void pkp_key_off(PkPlayer* p, const PokeyImage* image, int lane);
/* Whether a lane's voice is silent for good: none, a stopped sample, silenced, or level 0 with every envelope held
 * at its end. */
int pkp_voice_done(const PkPlayer* p, const PokeyImage* image, int lane);

/* Whether a sound with instrument flags `flags` can play on `lane`. */
int pkp_can_play(int flags, int lane);
/* The kind of instrument flags. */
int pkp_kind(int flags);
/* Machine clock of an image's machine (Hz). */
double pkp_clock(int machine);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_POKEY_PLAYER_H_ */
