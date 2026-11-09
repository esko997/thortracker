/* Thor Tracker's MMC5 PCM lane: a software sample player that writes the MMC5's 8-bit DAC ($5011, write mode) on the
 * machine's CPU-cycle timebase, beside the pinned FamiStudio engine's seven channels (the engine has no MMC5 PCM).
 *
 * What this is and is not. The MMC5 DAC has no sample clock, no DMA and no sample memory of its own: a game streams PCM
 * by writing $5011 from its CPU (or by reads in read mode). This player is the host doing those writes, at the times a
 * streaming routine would, so the DAC (nes_mmc5.c) sounds exactly as written; it is NOT emulated MMC5 hardware, the
 * emulated 6502 does not pay the streaming cost (a real game would spend most of its CPU on it at high rates), and the
 * NSF / FamiStudio exports cannot carry it (their engine has no MMC5 PCM; the frontend refuses songs whose PCM lane
 * plays). Rates, pitches and the 256 KiB sample memory are this player's limits, not register facts.
 *
 * The MMC5's zero rule holds: a $00 written to $5011 leaves the DAC unchanged and trips its IRQ flag, so sample bytes
 * are 1..255 (the importer raises 0 to 1) and every value the player writes is nonzero. Silence is the centre,
 * NES_MMC5_DAC_CENTRE (128): a stopped voice writes it, never 0. Reset always centres the DAC (the machine resets with
 * the output detached and the buffer works relative to the level it attaches at, so seven-voice MMC5 songs sound
 * exactly as without the lane, and a first mapping or note added later starts from the centre, not from 0).
 *
 * Image extension (the bytes after the seven-channel FamiStudio image of the "nes-famistudio-mmc5" engine;
 * little-endian; NES_PCM_EXT_SIZE bytes, everything not described zero):
 *   header      0  u32 "TTPC"   4 u8 version 1   5 u8 samples (0..64)   6 u16 mappings (0..6144)
 *               8  u32 sample data bytes (0..262144)   12 u16 events (0..16320)   14 u8 orders (= the FamiStudio image's)
 *               15 u8 0
 *   orders      NES_PCM_ORDERS_AT: 255 x {u16 first event, u16 event count}: order o's events (rows ascending, one per
 *               row at most, rows < the order's rows); orders may share a range
 *   samples     NES_PCM_SAMPLES_AT: 64 x {u32 offset into the data, u32 length (>= 1), u32 loop start (< length)}
 *   mappings    NES_PCM_MAPPINGS_AT: 6144 x {u8 sample, u8 flags (bit 0: loop), u16 binding, u32 period}: what a note
 *               plays. binding = the authored (instrument slot 1..64, absolute key 12..107) it belongs to,
 *               (slot - 1) x 96 + (key - 12) = 0..6143, strictly ascending through the table (so unique); a live edit
 *               follows a playing or pending note by it, never by table position. period = CPU cycles per sample
 *               step in 16.16 fixed point, NES_PCM_PERIOD_MIN..NES_PCM_PERIOD_MAX (the lowering computes it from the
 *               sample's rate and the mapping's pitch)
 *   events      NES_PCM_EVENTS_AT: 16320 (255 orders x 64 rows) x {u8 row, u8 flags, u16 mapping, u16 packed}
 *               flags: NES_PCM_EV_NOTE (start mapping table entry `mapping`), NES_PCM_EV_STOP (OFF; not with NOTE),
 *               NES_PCM_EV_VOLUME (Cxx: packed bits 0-3, 0..15), NES_PCM_EV_DELAY (Gxx: the note or stop happens
 *               packed bits 4-8 = 1..31 frames after the row starts), NES_PCM_EV_CUT (Sxx: stop packed bits 9-13 =
 *               0..31 frames after the row starts; not with DELAY: a cell has one effect); unused fields 0
 *   data        NES_PCM_DATA_AT: the samples' unsigned 8-bit bytes, each 1..255
 *
 * Playback, FamiStudio's delayed note/cut chronology (famistudio_advance_channel_with_delays) on the engine's own row
 * boundary (the machine's row_starts / next_row), at cycle 0 of each frame before the engine's play call. A frame
 * that starts a row first fires a still pending delayed note or stop at once (a delay longer than its row), then
 * applies the row's event; any other frame counts a pending delay down and fires it when it reaches 0. Then a pending
 * cut counts down and stops the voice when it reaches 0 (S00 on the row's own frame). A cut survives row boundaries
 * until it fires; a note or OFF that happens (immediate, or a delayed one firing) cancels a cut set before it, as every
 * engine lane's note does; the row's own Sxx is set after its note. A note starts its mapping's sample at position 0
 * with the DAC set to it, then steps one sample every period; a loop mapping continues at the sample's loop start, any
 * other stops (centre) after the last sample. The DAC value is 128 + (sample - 128) x volume / 15 (C division: 1..255;
 * volume 15 plays the bytes as they are, 0 is silence); Cxx applies to the playing sample at once. Speeds (Fxx) are
 * the engine's: the lane only follows its rows.
 *
 * Time: the voice's next step is a 16.16 frame-relative CPU time; nes_pcm_run advances to a cycle (the machine calls
 * it before every MMC5 register access and at the frame's end, so the DAC writes interleave with the engine's MMC5
 * writes in time order). With output attached each step writes the DAC at its cycle; without (seeking) the steps are
 * counted arithmetically and only the resulting value is written, so position, phase and DAC equal straight play's.
 * Plain data, no allocation. */
#ifndef THORTRACKER_NES_MMC5_PCM_H_
#define THORTRACKER_NES_MMC5_PCM_H_

#include <stdint.h>

#include "nes_image.h"
#include "nes_mmc5.h"

#ifdef __cplusplus
extern "C" {
#endif

#define NES_PCM_MAGIC 0x43505454u /* "TTPC" little-endian */
#define NES_PCM_VERSION 1
#define NES_PCM_MAX_SAMPLES 64
#define NES_PCM_SLOTS 64               /* the shared instrument bank */
#define NES_PCM_KEYS 96                /* absolute keys NES_PCM_KEY_FIRST.. per slot (C-1..B-8) */
#define NES_PCM_KEY_FIRST 12
#define NES_PCM_BINDINGS (NES_PCM_SLOTS * NES_PCM_KEYS)
/* A mapping's binding: instrument slot 1..64, absolute key 12..107. */
#define NES_PCM_BINDING(slot, key) (((slot) - 1) * NES_PCM_KEYS + (key) - NES_PCM_KEY_FIRST)
#define NES_PCM_MAX_MAPPINGS NES_PCM_BINDINGS /* every key of every instrument slot */
#define NES_PCM_MAX_ORDERS 255
#define NES_PCM_MAX_ROWS 64            /* Thor Tracker's pattern length */
#define NES_PCM_MAX_EVENTS (NES_PCM_MAX_ORDERS * NES_PCM_MAX_ROWS)
#define NES_PCM_MAX_DATA 262144u       /* 256 KiB of sample bytes */
/* Periods in 16.16 CPU cycles per sample: 8 cycles (223.7 kHz) to 32768 cycles (54.6 Hz). The lowering's rates
 * (1000..48000 Hz) and pitches (-48..+24 semitones) give 9.3 to 28636 cycles. */
#define NES_PCM_PERIOD_MIN (8u << 16)
#define NES_PCM_PERIOD_MAX (32768u << 16)

#define NES_PCM_HEADER_SIZE 16
#define NES_PCM_ORDER_SIZE 4
#define NES_PCM_SAMPLE_SIZE 12
#define NES_PCM_MAPPING_SIZE 8
#define NES_PCM_EVENT_SIZE 6
#define NES_PCM_ORDERS_AT NES_PCM_HEADER_SIZE
#define NES_PCM_SAMPLES_AT (NES_PCM_ORDERS_AT + NES_PCM_MAX_ORDERS * NES_PCM_ORDER_SIZE)
#define NES_PCM_MAPPINGS_AT (NES_PCM_SAMPLES_AT + NES_PCM_MAX_SAMPLES * NES_PCM_SAMPLE_SIZE)
#define NES_PCM_EVENTS_AT (NES_PCM_MAPPINGS_AT + NES_PCM_MAX_MAPPINGS * NES_PCM_MAPPING_SIZE)
#define NES_PCM_DATA_AT (NES_PCM_EVENTS_AT + NES_PCM_MAX_EVENTS * NES_PCM_EVENT_SIZE)
#define NES_PCM_EXT_SIZE (NES_PCM_DATA_AT + NES_PCM_MAX_DATA)
_Static_assert(NES_PCM_SAMPLES_AT == 1036 && NES_PCM_MAPPINGS_AT == 1804 && NES_PCM_EVENTS_AT == 50956 &&
                   NES_PCM_DATA_AT == 148876 && NES_PCM_EXT_SIZE == 411020,
               "the documented extension layout (docs/AUDIO_ENGINE.md, \"MMC5 PCM lane\")");

enum {
    NES_PCM_EV_NOTE = 1,
    NES_PCM_EV_STOP = 2,
    NES_PCM_EV_VOLUME = 4,
    NES_PCM_EV_DELAY = 8,
    NES_PCM_EV_CUT = 16,
};

/* What nes_pcm_load derives from a valid extension. */
typedef struct NesPcmInfo {
    uint32_t loaded; /* NM_LOADED after a successful nes_pcm_load */
    uint8_t samples, orders;
    uint16_t mappings, events;
    uint32_t data;
    const char* problem; /* why nes_pcm_load failed (static string), else NULL */
    uint32_t problem_at; /* extension byte offset the problem refers to */
} NesPcmInfo;

typedef struct NesPcmImage {
    uint8_t bytes[NES_PCM_EXT_SIZE];
    NesPcmInfo info;
} NesPcmImage;

/* The "nes-famistudio-mmc5" engine's loaded image: the seven-channel FamiStudio image and the PCM lane's extension. */
typedef struct NesMmc5Image {
    NesImage base;
    NesPcmImage pcm;
} NesMmc5Image;

/* Validates image->bytes as the extension of the loaded FamiStudio image `base` (its orders and rows) and fills
 * image->info. Returns 0, or -1 with info.problem set. */
int nes_pcm_load(NesPcmImage* image, const NesImageInfo* base);
/* An extension with no samples, mappings or events for an image of `orders` orders (tests and tools). */
void nes_pcm_build_empty(uint8_t* ext, int orders);

/* The player: a copy of the playing extension (machines never point into an image) and one voice. */
typedef struct NesPcm {
    uint8_t ext[NES_PCM_EXT_SIZE];
    NesPcmInfo info;
    uint8_t playing, loop, volume, sample;
    uint16_t mapping;               /* the mapping table entry the voice plays */
    uint32_t offset, length, loop_start, period, pos;
    int64_t next;                   /* 16.16 frame-relative CPU time of the next sample step */
    uint8_t pending_flags;          /* a delayed note or stop (NES_PCM_EV_NOTE / _STOP), 0: none */
    uint16_t pending_mapping;       /* the delayed note's mapping table entry */
    uint8_t pending_frames;         /* frames until it fires, counting the frame it fires on (a row start forces it) */
    uint8_t cut_frames;             /* frames until a cut fires, counting the frame it fires on; 0: none */
} NesPcm;

/* Takes `image` (loaded) as the extension the player plays; NULL: none (no mappings, no events). Then reset. */
void nes_pcm_set_image(NesPcm* p, const NesPcmImage* image);
/* Power-on: no voice, volume 15, nothing pending, the DAC centred (a $5011 = NES_MMC5_DAC_CENTRE write at the unit's
 * time unless it holds that already), with or without mappings. */
void nes_pcm_reset(NesPcm* p, NesMmc5* unit);
/* Advances the voice to frame-relative cycle `t` (>= the time reached), writing the DAC. */
void nes_pcm_run(NesPcm* p, NesMmc5* unit, uint32_t t);
/* Runs to `frame_cycles` and starts the next frame at time 0. */
void nes_pcm_end_frame(NesPcm* p, NesMmc5* unit, uint32_t frame_cycles);
/* Cycle 0 of a frame, before the engine's play call: with `row_started` a pending delayed note or stop, then the
 * event of (order, row), else the delay's countdown; then the cut's countdown. */
void nes_pcm_frame(NesPcm* p, NesMmc5* unit, int row_started, int order, int row);
/* Starts mapping table entry `mapping` (0-based) now, as a note does (audition). -1 for an entry the extension does
 * not hold. */
int nes_pcm_note(NesPcm* p, NesMmc5* unit, int mapping);
/* Live edit (between frames, bounded work): `image` replaces the extension. The playing and the pending delayed note
 * follow their binding to its entry in the new table (table positions shift as other keys are mapped or unmapped). A
 * playing note whose binding is gone stops (centre); one whose binding now plays different sample bytes stops; else
 * it continues at its position and phase in the sample's new place, with the binding's new period, loop and loop
 * start (a pitch or loop edit applies at once). A pending note whose binding is gone is cancelled. Then the extension
 * is copied (header, tables, used data). */
void nes_pcm_apply(NesPcm* p, NesMmc5* unit, const NesPcmImage* image);
/* The seek fallback (the engine placed at `order`'s start): the voice, volume and pending delay/cut straight play has
 * at that order's start, reconstructed with nes_pcm_frame's chronology (delays forced at row starts, cuts crossing
 * rows, notes cancelling earlier cuts) from the song's own timing (each row lasts the speed in effect after it: the
 * engine's FamiTracker tempo at 150, one tick per frame; nm_row_speeds) and the 29780/29781-cycle frame alternation of
 * straight play from the song start. Walks the earlier orders once: bounded by orders x rows. */
void nes_pcm_carry(NesPcm* p, NesMmc5* unit, const NmView* view, int order);

/* The DAC value the voice writes now (centre when silent). */
int nes_pcm_value(const NesPcm* p);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_NES_MMC5_PCM_H_ */
