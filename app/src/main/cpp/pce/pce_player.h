/* Thor Tracker's PC Engine player: sequences an image (pce_image.h) and produces HuC6280 PSG register writes, one
 * batch per tick and one per DDA timer interrupt. Pure integer code without a chip: the machine (pce_machine.c) sends
 * the writes to Mednafen's PSG, the VGM export (pce_vgm.c) logs them, and seeking runs it alone. The behaviour is
 * specified in docs/AUDIO_ENGINE.md ("PC Engine: Player"); tools/audio/pce/pce_ref.py is an independent Python
 * implementation of the same specification, and the host test compares their writes.
 *
 * Writes are (register 0..9, value) pairs. Registers 2-7 act on the selected channel: before a write to channel c's
 * register the player emits R0 = c if `select` differs. Shadows (the value last written): global R1, R8, R9; per
 * channel R2, R3, R4, R5 and R7 (channels 4, 5); a write equal to its shadow is not emitted (nor its R0). R6 (wave or
 * DDA data) is never shadowed; wave loads write R4 twice unconditionally. The player keeps no pointer to an image;
 * every call takes the (copied, loaded) image it should read. No allocation. */
#ifndef THORTRACKER_PCE_PLAYER_H_
#define THORTRACKER_PCE_PLAYER_H_

#include <stdint.h>

#include "pce_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define PCP_MAX_WRITES 512   /* per batch; the worst tick (six wave loads, every register changing) is below 300 */
#define PCP_PITCH_MIN 569    /* pitch units (1/64 semitone, 0 = C-0): the lowest whose period fits 12 bits (4093) */
#define PCP_PITCH_MAX 6143   /* B-7 + 63/64 */
#define PCP_SLIDE_LIMIT 6144 /* slide offsets and the relative pitch accumulator stay within +-this */
#define PCP_TIMER_DIVIDER 1024 /* CPU cycles per DDA timer count */

extern const uint32_t PCP_PERIOD_Q16[768]; /* round(39375000 / 11 / (32 * 440 * 2^((i / 64 - 57) / 12)) * 65536): C-0's octave */
extern const int8_t PCP_SINE[64];          /* floor(127 sin(2 pi i / 64) + 0.5): the player's vibrato (the OPL3's table) */

typedef struct PcWrite {
    uint8_t reg;
    uint8_t val;
} PcWrite;

typedef struct PcChannel {
    /* row */
    PcRow row;          /* this row's data (read at the row start) */
    int pending;        /* row delayed by Gxx: run at tick `delay` */
    int delay;
    int cut;            /* Sxx: OFF at this tick of the row, -1 none */
    /* note */
    int instrument;     /* slot for the next note, 0 none */
    int slot;           /* the sounding note's instrument slot (latched at key-on) */
    int kind;           /* its kind, PC_KIND_* (latched at key-on) */
    int sounding;
    int fresh;          /* keyed this tick: the first update (writes a sample's frame 0) */
    int released;
    int dda;            /* the timer interrupt streams this channel's sample */
    uint64_t pos;       /* sample position, 16.16 frames */
    uint32_t step;      /* frames per timer interrupt, 16.16 (the last update's) */
    int env[PC_ENVELOPE_COUNT]; /* envelope indices */
    int rel_pitch;      /* relative pitch envelope accumulator */
    int base, target;   /* pitch units */
    int fine;           /* Pxx */
    int arp, arp_step;  /* 0xy */
    int slide_mode, slide_speed, slide; /* 0 none, 1 up, 2 down, 3 portamento */
    int vib_speed, vib_depth, vib_phase;
    int vol8;           /* Cxx volume x 8, 0..248 */
    int volslide;       /* Axy param */
    int pan;            /* R5 value (8xy) */
    int wave_override;  /* Wxx, -1 none */
    int loaded;         /* wave in the channel's waveform RAM, -1 none */
    /* shadows of the registers the chip holds for this channel */
    uint8_t r2, r3, r4, r5, r7;
} PcChannel;

typedef struct PcPlayer {
    int order, row, tick, speed;
    int jump;                    /* row start saw B/D: next position is (jump_order, jump_row) */
    int jump_order, jump_row;
    int audition;                /* audition mode: no sequencing */
    PcChannel ch[PC_CHANNELS];
    /* the chip's shared state as the player sets it (written by every tick's globals through the shadows) */
    int master, lfo_freq, lfo_ctrl;
    int timer;                   /* DDA timer reload R (no register: the machine's timer) */
    uint8_t shadow_r1, shadow_r8, shadow_r9;
    int select;                  /* R0 as last written, -1 before the reset batch */
    PcCursor cursor[PC_CHANNELS];
    int cursor_order, cursor_row; /* cursors are at (cursor_order, cursor_row), -1 = invalid */
    int writes;
    PcWrite write[PCP_MAX_WRITES];
} PcPlayer;

/* Reset: all state cleared, shared state from the header, position (0, 0, 0) with the header speed; write[] = the
 * reset batch (R1, R8, R9, then per channel R0, R2 = 0, R3 = 0, R4 = 0, R5 = $FF, R7 = 0 on channels 4-5). */
void pcp_reset(PcPlayer* p, const PceImage* image);
/* One tick: write[] receives its writes (the previous batch is dropped). */
void pcp_tick(PcPlayer* p, const PceImage* image);
/* One DDA timer interrupt: write[] receives its writes (R6 = the next frame of every DDA channel). */
void pcp_timer(PcPlayer* p, const PceImage* image);
/* Reset, then start from (order, row 0) with the speed straight play starts that order with (seek fallback). */
void pcp_place(PcPlayer* p, const PceImage* image, int order);
/* Live edit at a tick boundary: positions kept (clamped), rows re-read from `next`, loaded waves whose bytes changed
 * reload, shared fields whose header value changed take the new value (see the spec). */
void pcp_relocate(PcPlayer* p, const PceImage* old, const PceImage* next);
/* Audition: reset, then key on `note` (PC_NOTE_MIN..PC_NOTE_MAX) on `channel` with instrument `slot` and fine pitch
 * `fine` (pitch units, -128..127); ticks only update. Returns 0 (write[] = the reset batch) or -1 (bad arguments, an
 * undefined slot, a noise instrument off channels 4-5). */
int pcp_audition(PcPlayer* p, const PceImage* image, int channel, int note, int slot, int fine);
/* OFF on a channel (audition release) as a new batch in write[] (call between ticks). */
void pcp_off(PcPlayer* p, const PceImage* image, int channel);

/* 12-bit period N (1..4095) of a pitch (clamped to PCP_PITCH_MIN..PCP_PITCH_MAX): 3579545.45 / (32 N) Hz. */
int pcp_period(int pitch);
/* Sample frames per timer interrupt in 16.16 for a pitch (clamped) and a root note: the root plays 65536. */
uint32_t pcp_sample_step(int pitch, int root_note);
/* CPU cycles from one timer interrupt to the next with the player's reload value: 1024 (timer + 1). */
int pcp_timer_cycles(const PcPlayer* p);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_PCE_PLAYER_H_ */
