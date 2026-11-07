/* NES song images: the bytes a lowering produces for the "nes-famistudio" engine, their validation
 * and the stream/envelope walkers the machine uses to seek and to relocate a playing song onto a new
 * image. Layout and contract: docs/AUDIO_ENGINE.md, "Image layout"; numbers in
 * generated/nes_player_image.h (owned by tools/audio/prepare_nes_player.py).
 *
 * An image is NES_IMAGE_HEADER_SIZE header bytes, the envelope directory (NES_IMAGE_MAX_ENVELOPES
 * entries of 4 bytes) and the song region (NM_SONG_SIZE bytes placed at NES_SONG_BASE). Header,
 * little-endian:
 *   0 "TTNS"   4 u32 player id   8 u16 song base   10 u16 data size   12 u8 song index
 *   13 u8 layout: NM_LAYOUT_THOR (Thor Tracker lowering) or NM_LAYOUT_RAW (any FamiTracker-tempo
 *      FamiStudio export; play, seek from the start and byte-identical live edits only)
 *   14 u8 instrument count (Thor)   15 zero   16 u16 envelope count (Thor: highest used index + 1)
 *   18 u8 rows per order, 19 u8 order count, 20 u8 loop order or 0xFF (raw only; position/seek)
 *   other bytes zero.
 * Pure functions; no allocation. */
#ifndef THORTRACKER_NES_IMAGE_H_
#define THORTRACKER_NES_IMAGE_H_

#include <stdint.h>

#include "generated/nes_player_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define NM_SONG_SIZE (NES_SONG_END - NES_SONG_BASE)
#define NM_REGION_OFFSET (NES_IMAGE_HEADER_SIZE + NES_IMAGE_DIRECTORY_SIZE)
#define NM_MAGIC 0x534E5454u /* "TTNS" little-endian */
#define NM_CHANNELS 5        /* pulse 1, pulse 2, triangle, noise, DPCM (FamiStudio order) */
#define NM_LOADED 0x4C4F4144u

_Static_assert(NM_REGION_OFFSET + NM_SONG_SIZE == NES_IMAGE_SIZE, "image = header + directory + region");

enum { NM_LAYOUT_RAW = 0, NM_LAYOUT_THOR = 1 };
enum { NM_KIND_VOLUME = 0, NM_KIND_ARPEGGIO = 1, NM_KIND_DUTY = 2, NM_KIND_PITCH = 3 };

/* What nm_load_image derives from a valid image (copied with it; no pointers into the bytes). */
typedef struct NesImageInfo {
    uint32_t loaded; /* NM_LOADED after a successful nm_load_image */
    uint8_t layout, orders, loop, instruments; /* loop 0xFF: the song ends (raw only) */
    uint16_t size, envelopes, tempo_ntsc;
    uint16_t streams[NM_CHANNELS];         /* Thor: order-stream address of each channel */
    uint8_t rows[NES_IMAGE_MAX_ORDERS];    /* rows of each order (raw: the header's constant, or 0) */
    uint8_t speed[NES_IMAGE_MAX_ORDERS];   /* Thor: speed in effect when each order starts (straight play) */
    uint16_t used_count;                   /* Thor: used directory entries, ascending address */
    uint16_t used[NES_IMAGE_MAX_ENVELOPES];
    const char* problem; /* why nm_load_image failed (static string), else NULL */
    uint32_t problem_at; /* image byte offset the problem refers to */
} NesImageInfo;

typedef struct NesImage {
    uint8_t bytes[NES_IMAGE_SIZE];
    NesImageInfo info;
} NesImage;

/* Validates image->bytes and fills image->info. Returns 0, or -1 with info.problem set. */
int nm_load_image(NesImage* image);

/* Raw-layout image around song-region bytes (tests and tools); then call nm_load_image. */
void nm_build_raw_image(NesImage* image, const uint8_t* song, uint32_t size, int song_index, int rows_per_order,
                        int order_count, int loop_order);

/* ---- walkers shared with the machine (images already loaded) ------------------------------ */

static inline uint16_t nm_rd16(const uint8_t* p) { return (uint16_t)(p[0] | p[1] << 8); }
static inline const uint8_t* nm_region(const NesImage* image) { return image->bytes + NM_REGION_OFFSET; }

/* Song data as the walkers read it: a loaded image, or the copy a machine plays (its song region
 * in CPU memory, the image's directory and info). */
typedef struct NmView {
    const uint8_t* region;    /* bytes at NES_SONG_BASE */
    const uint8_t* directory; /* NES_IMAGE_MAX_ENVELOPES entries */
    const NesImageInfo* info;
} NmView;

static inline NmView nm_view(const NesImage* image) {
    NmView v = {nm_region(image), image->bytes + NES_IMAGE_HEADER_SIZE, &image->info};
    return v;
}

/* One row entry: prefix opcodes then one terminator (note, stop, release, slide or empty run). */
typedef struct NmEntry {
    uint16_t end;       /* address after the terminator */
    uint16_t delay_end; /* address after a `$47 d` prefix, or 0 */
    int rows;           /* rows covered: 1 + empty-run length */
    int speed;          /* operand of the entry's last `$46`, or -1 */
} NmEntry;

/* Walks the entry of channel `channel` at song address `addr`; returns NULL or why it is invalid.
 * Checks every opcode against the channel and, with the Thor layout, instrument and envelope
 * operands against the table and directory. */
const char* nm_walk_entry(const NmView* v, uint16_t addr, int channel, NmEntry* out);

/* Speed in effect after row `row` of `order` (-1: at the order start) when the order is entered
 * with the speed straight play gives it (Thor layout). */
int nm_speed_at(const NmView* v, int order, int row);

/* Directory index of the used entry at song address `addr`, or -1. */
int nm_find_envelope(const NesImageInfo* info, const uint8_t* directory, uint16_t addr);

/* First instruction index of an envelope of `kind` (volume and pitch keep a header byte). */
static inline int nm_envelope_start(int kind) { return kind == NM_KIND_VOLUME || kind == NM_KIND_PITCH ? 1 : 0; }

/* Whether the engine may resume the envelope `env` (len bytes, `kind`, well formed) at `index`: an
 * instruction starts there (value, hold count or loop code; not a loop's index byte or header). */
int nm_envelope_instruction_at(const uint8_t* env, int len, int kind, int index);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_NES_IMAGE_H_ */
