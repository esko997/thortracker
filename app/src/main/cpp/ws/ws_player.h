/* Thor Tracker's WonderSwan player: sequences an image (ws_image.h) and produces sound unit writes, one batch per tick
 * (the VBlank interrupt, 40704 cycles) and one per voice timer interrupt (the HBlank timer, 256 R cycles). Pure integer
 * code without a chip: the machine (ws_machine.c) sends the writes to the sound unit (ws_apu.c), the VGM export
 * (ws_vgm.c) logs them, and seeking runs it alone. The behaviour is specified in docs/AUDIO_ENGINE.md ("WonderSwan:
 * Player"); tools/audio/ws/ws_ref.py is an independent Python implementation of the same specification, and the host
 * test compares their writes.
 *
 * Writes are WsWrite {space, addr, val}. Shadows (the value last written, -1 unknown): ports $80-$8C, $8E (without bit
 * 3), $90, $91, $94, $6A, $6B (without bit 4); "through the shadow" writes only a different value. $8D, the DMA ports
 * $4A-$4C, $4E-$50, $52, $69 and RAM are never shadowed; voice frames written to $89 set its shadow to -1. The player
 * keeps no pointer to an image: every call takes the (copied, loaded) image it should read. No allocation. */
#ifndef THORTRACKER_WS_PLAYER_H_
#define THORTRACKER_WS_PLAYER_H_

#include <stdint.h>

#include "ws_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define WSP_MAX_WRITES 512   /* per batch; the worst tick (four wave loads, every register changing) is below 200 */
#define WSP_PITCH_MIN 1167   /* pitch units (1/64 semitone, 0 = C-0): the lowest whose N <= 2048 */
#define WSP_PITCH_MAX 6143   /* B-7 + 63/64 */
#define WSP_SLIDE_LIMIT 6144 /* slide offsets and the relative pitch accumulator stay within +-this */
#define WSP_VOL8_MAX 120     /* Cxx volume x 8 */
#define WSP_SHADOWS 19       /* $80-$8C, $8E, $90, $91, $94, $6A, $6B */

extern const uint32_t WSP_PERIOD_Q16[768]; /* round(96000 / (440 * 2^((i / 64 - 57) / 12)) * 65536): C-0's octave */
extern const int8_t WSP_SINE[64];          /* floor(127 sin(2 pi i / 64) + 0.5): the player's vibrato */

typedef struct WsLane {
    /* row */
    WsRow row;          /* this row's data (read at the row start) */
    int pending;        /* row delayed by Gxx: run at tick `delay` */
    int delay;
    int cut;            /* Sxx: OFF at this tick of the row, -1 none */
    /* note */
    int instrument;     /* slot for the next note, 0 none */
    int slot;           /* the sounding note's slot (latched at key-on) */
    int kind;           /* its kind (latched) */
    int dma;            /* its feed: 1 Sound DMA, 0 CPU (latched) */
    int sounding;
    int fresh;          /* keyed this tick: the first update */
    int released;
    int feeding;        /* the timer interrupt streams this lane's CPU-fed sample */
    uint64_t pos;       /* sample position, 16.16 frames */
    uint32_t step;      /* frames per timer interrupt, 16.16 (the last update's) */
    int env[WS_ENVELOPE_COUNT];
    int rel_pitch;      /* relative pitch envelope accumulator */
    int base, target;   /* pitch units */
    int fine;           /* Pxx */
    int arp, arp_step;  /* 0xy */
    int slide_mode, slide_speed, slide; /* 0 none, 1 up, 2 down, 3 portamento */
    int vib_speed, vib_depth, vib_phase;
    int vol8;           /* Cxx volume x 8, 0..120 */
    int volslide;       /* Axy */
    int pan;            /* 8xy */
    int wave_ov;        /* Wxx, -1 none */
    int tap_ov;         /* Nxx, -1 none */
    int sweep_ov;       /* Uxx as s8, WSP_NONE none */
    int time_ov;        /* Vxx, -1 none */
    int hold;           /* Hxx */
    int hold_written;   /* the hold bit of the lane's DMA program */
    int loaded;         /* wave in the lane's wave RAM, -1 none */
    int was_sweep;      /* lane 2: the last update swept (persists across notes) */
    int last_d;         /* lane 2: the last update's frequency register */
    int sweep_s;        /* lane 2: the last update's sweep value */
    int side_l, side_r; /* the last update's l > 0, r > 0 (Hyper Voice frames) */
} WsLane;

#define WSP_NONE (-1000)     /* no Uxx override */

typedef struct WsPlayer {
    int model, lanes;
    int order, row, tick, speed;
    int jump;                    /* row start saw B/D: next position is (jump_order, jump_row) */
    int jump_order, jump_row;
    int audition;                /* audition mode: no sequencing */
    int timer;                   /* voice timer reload R (the machine's timer) */
    int shift;                   /* speaker shift */
    int owner;                   /* lane owning Sound DMA, -1 none */
    int dma_kill;                /* a live edit stops the DMA note at the next tick's start */
    int time_written;            /* the value last written to $8D */
    int shadow[WSP_SHADOWS];     /* -1 = unknown */
    WsLane lane[WS_CHANNELS];
    /* row cursor cache (not state: rows are re-located from (order, row) when it does not match) */
    WsCursor cursor[WS_CHANNELS];
    int cursor_order, cursor_row;
    /* the batch */
    int writes;
    WsWrite write[WSP_MAX_WRITES];
} WsPlayer;

/* Reset: all state cleared, shared state from the header, position (0, 0, 0) with the header speed; write[] = the
 * reset batch. */
void wsp_reset(WsPlayer* p, const WsImage* image);
/* One tick: write[] receives its writes (the previous batch is dropped). */
void wsp_tick(WsPlayer* p, const WsImage* image);
/* One voice timer interrupt: write[] receives its writes (the next frame of every CPU-fed sample). */
void wsp_timer(WsPlayer* p, const WsImage* image);
/* Reset, then start from (order, row 0) with the speed straight play starts that order with (seek fallback). */
void wsp_place(WsPlayer* p, const WsImage* image, int order);
/* Live edit at a tick boundary (see the spec): positions kept (clamped), rows re-read from `next`, loaded waves whose
 * bytes changed reload, header fields that changed take the new value. The DMA owner keeps playing only when its
 * slot is still the same kind of DMA sample with byte-identical bytes; returns 1 with *dma_delta = the bytes' move
 * (the machine rebases the chip's DMA source by it), else 0 (dma_kill is set when the note must stop). */
int wsp_relocate(WsPlayer* p, const WsImage* old, const WsImage* next, int32_t* dma_delta);
/* Audition: reset, then key on `note` on `lane` with instrument `slot` and fine pitch `fine` (-128..127); ticks only
 * update. Returns 0 (write[] = the reset batch) or -1 (bad arguments, an undefined slot, a kind that does not fit). */
int wsp_audition(WsPlayer* p, const WsImage* image, int lane, int note, int slot, int fine);
/* OFF on a lane (audition release) as a new batch in write[] (between ticks), ending with $90. */
void wsp_off(WsPlayer* p, const WsImage* image, int lane);

/* N = 2048 - d (1..2048) of a pitch (clamped to WSP_PITCH_MIN..WSP_PITCH_MAX): 96000 / N Hz. */
int wsp_period(int pitch);
/* Sample frames per timer interrupt in 16.16 for a pitch (clamped) and a root note: the root plays 65536. */
uint32_t wsp_sample_step(int pitch, int root_note);
/* Cycles from one timer interrupt to the next with the player's reload value: 256 R. */
int wsp_timer_cycles(const WsPlayer* p);
/* control(): the $90 value the player's lanes ask for. */
int wsp_control(const WsPlayer* p);
/* The shadow of a shadowed port ($80-$8C, $8E, $90, $91, $94, $6A, $6B), -1 unknown or not shadowed. */
int wsp_shadow(const WsPlayer* p, int port);

/* Every field that decides future writes is equal (the VGM loop): the batch and the row cursor cache excluded. */
int wsp_same_state(const WsPlayer* a, const WsPlayer* b);
/* A 64-bit FNV-1a digest of the fields wsp_same_state compares (equal states, equal digests). */
uint64_t wsp_state_hash(const WsPlayer* p);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_WS_PLAYER_H_ */
