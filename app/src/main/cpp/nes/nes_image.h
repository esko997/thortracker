/* NES song images: the bytes a lowering produces for the "nes-famistudio", "nes-famistudio-vrc6",
 * "nes-famistudio-vrc7" and "nes-famistudio-n163-1".."-8" engines, their validation and the stream/envelope walkers
 * the machine uses to seek and to relocate a playing song onto a new image. Layout and contract:
 * docs/AUDIO_ENGINE.md, "Image layout", "VRC6", "VRC7" and "N163"; numbers in generated/nes_player_image.h,
 * nes_vrc6_player_image.h, nes_vrc7_player_image.h and nes_n163_<n>_player_image.h (owned by
 * tools/audio/prepare_nes_player.py), one NesPlayer per build (nes_player.h).
 *
 * An image is NES_IMAGE_HEADER_SIZE header bytes, the envelope directory (NES_IMAGE_MAX_ENVELOPES
 * entries of 4 bytes) and the region: CPU memory player->song_base..NES_REGION_END, shared by the song
 * data (from the song base upward) and one block of DPCM samples (64-aligned, at or above
 * NES_SAMPLE_BASE; the song data ends at or below it). Header, little-endian:
 *   0 "TTNS"   4 u32 player id   8 u16 song base   10 u16 data size   12 u8 song index
 *   13 u8 layout: NM_LAYOUT_THOR (Thor Tracker lowering) or NM_LAYOUT_RAW (any FamiTracker-tempo
 *      FamiStudio export; play, seek from the start and byte-identical live edits only)
 *   14 u8 instrument count (Thor)   15 u8 DPCM list entries (Thor)
 *   16 u16 envelope count (Thor: highest used index + 1)
 *   18 u8 rows per order, 19 u8 order count, 20 u8 loop order or 0xFF (raw only; position/seek)
 *   22 u16 sample block bytes, 24 u16 sample block address (both 0: no samples; both layouts)
 *   other bytes zero.
 * The song header holds player->channels stream words (5; 8 with VRC6: then VRC6 pulse 1, pulse 2, saw; 11 with
 * VRC7: then FM 1-6; 5 + n with N163: then N163 1..n). With VRC7 and N163 FamiStudio's expansion instrument pointer
 * follows the instrument pointer (nes_player_head: 7 header bytes before the stream words). The Thor layout of a
 * VRC7 image holds one 16-byte expansion instrument per instrument (nm_load_image checks their envelopes, override
 * and patch bytes). The Thor layout of an N163 image holds one 16-byte expansion instrument per instrument
 * (volume, arpeggio, pitch and wave-index envelope words, wave position and size in samples, wave table pointer,
 * 4 zero bytes), the wave tables (a pointer per wave to size / 2 packed bytes, the even sample in the low nibble)
 * and envelope directory kind NM_KIND_WAVE (the wave-index envelopes, directory 256..319; effect envelopes then
 * start at 320).
 * Pure functions; no allocation. */
#ifndef THORTRACKER_NES_IMAGE_H_
#define THORTRACKER_NES_IMAGE_H_

#include <stdint.h>

#include "nes_player.h"

#ifdef __cplusplus
extern "C" {
#endif

#define NM_REGION_OFFSET (NES_IMAGE_HEADER_SIZE + NES_IMAGE_DIRECTORY_SIZE)
#define NM_MAGIC 0x534E5454u /* "TTNS" little-endian */
#define NM_LOADED 0x4C4F4144u

_Static_assert(NM_REGION_OFFSET + NES_IMAGE_REGION_SIZE == NES_IMAGE_SIZE, "image = header + directory + region");
_Static_assert(NES_SAMPLE_BASE == 0xC000, "$4012 addresses samples from $C000");

enum { NM_LAYOUT_RAW = 0, NM_LAYOUT_THOR = 1 };
/* NM_KIND_WAVE: the N163 wave-index envelope (encoded like a volume envelope: release byte, values $C0 + wave
 * index 0..63); N163 images only. */
enum { NM_KIND_VOLUME = 0, NM_KIND_ARPEGGIO = 1, NM_KIND_DUTY = 2, NM_KIND_PITCH = 3, NM_KIND_WAVE = 4 };

/* Thor layout: directory indices below this hold instrument envelopes, a stable slot per instrument and kind (4 per
 * instrument; N163 builds also each instrument's wave-index envelope, 256..319); the effect envelopes follow. */
static inline int nm_instrument_envelope_end(const NesPlayer* p) {
    return 4 * NES_IMAGE_MAX_INSTRUMENTS + (p->expansion == NES_EXPANSION_N163 ? NES_IMAGE_MAX_INSTRUMENTS : 0);
}

/* What nm_load_image derives from a valid image (copied with it; no pointers into the bytes). */
typedef struct NesImageInfo {
    uint32_t loaded; /* NM_LOADED after a successful nm_load_image */
    const NesPlayer* player;               /* the build the image was loaded for (static data) */
    uint8_t layout, orders, loop, instruments; /* loop 0xFF: the song ends (raw only) */
    uint8_t mappings;                      /* Thor: DPCM list entries (stream notes 1..mappings) */
    uint16_t size, envelopes, tempo_ntsc;
    uint16_t samples, sample_start;        /* the sample block: bytes and address (0, 0: none) */
    uint16_t dpcm_list;                    /* Thor: address of DPCM list entry 1 (the header word + 4) */
    uint16_t exp_instruments;              /* Thor, VRC7 and N163: address of the expansion instrument table, else 0 */
    uint16_t streams[NM_MAX_CHANNELS];     /* Thor: order-stream address of each channel */
    uint8_t rows[NES_IMAGE_MAX_ORDERS];    /* rows of each order (raw: the header's constant, or 0) */
    uint8_t speed[NES_IMAGE_MAX_ORDERS];   /* Thor: speed in effect when each order starts (straight play) */
    uint16_t used_count;                   /* Thor: used directory entries, ascending address */
    uint16_t used[NES_IMAGE_MAX_ENVELOPES];
    const char* problem; /* why nm_load_image failed (static string), else NULL */
    uint32_t problem_at; /* image byte offset the problem refers to */
} NesImageInfo;

typedef struct NesImage {
    uint8_t bytes[NM_IMAGE_MAX_SIZE]; /* player->image_size bytes; the rest zero */
    NesImageInfo info;
} NesImage;

/* Validates image->bytes as an image for `player` and fills image->info. Returns 0, or -1 with info.problem
 * set. */
int nm_load_image(NesImage* image, const NesPlayer* player);
/* Raw-layout image for `player` around song bytes and sample bytes (tests and tools; samples at
 * NES_SAMPLE_BASE, as FamiStudio's .dmc with FAMISTUDIO_DPCM_PTR 0); then call nm_load_image. */
void nm_build_raw_image(NesImage* image, const NesPlayer* player, const uint8_t* song, uint32_t size, const uint8_t* samples,
                        uint32_t sample_size, int song_index, int rows_per_order, int order_count, int loop_order);

/* ---- walkers shared with the machine (images already loaded) ------------------------------ */

static inline uint16_t nm_rd16(const uint8_t* p) { return (uint16_t)(p[0] | p[1] << 8); }
static inline const uint8_t* nm_region(const NesImage* image) { return image->bytes + NM_REGION_OFFSET; }

/* Song data as the walkers read it: a loaded image, or the copy a machine plays (its song region
 * in CPU memory, the image's directory and info). */
typedef struct NmView {
    const uint8_t* region;    /* bytes at info->player->song_base (and samples at NES_SAMPLE_BASE) */
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
 * Checks every opcode against the channel (and the player's VRC6/VRC7/N163 rules) and, with the Thor layout,
 * instrument, envelope and DPCM mapping operands against the table, directory and DPCM list. */
const char* nm_walk_entry(const NmView* v, uint16_t addr, int channel, NmEntry* out);

/* Speed in effect after row `row` of `order` (-1: at the order start) when the order is entered
 * with the speed straight play gives it (Thor layout). */
int nm_speed_at(const NmView* v, int order, int row);

/* Directory index of the used entry at song address `addr`, or -1. */
int nm_find_envelope(const NesImageInfo* info, const uint8_t* directory, uint16_t addr);

/* First instruction index of an envelope of `kind` (volume, pitch and wave index keep a header byte). */
static inline int nm_envelope_start(int kind) {
    return kind == NM_KIND_VOLUME || kind == NM_KIND_PITCH || kind == NM_KIND_WAVE ? 1 : 0;
}

/* Whether the engine may resume the envelope `env` (len bytes, `kind`, well formed for its player) at
 * `index`: an instruction starts there (value, hold count or loop code; not a loop's index byte or header). */
int nm_envelope_instruction_at(const uint8_t* env, int len, int kind, int index);

/* N163 builds, Thor layout: wave table entries the expansion instrument entry at song address `entry` uses (the
 * largest value of its wave-index envelope + 1), or -1 when its wave-index word is not a WAVE directory entry. */
int nm_n163_waves(const NmView* v, uint16_t entry);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_NES_IMAGE_H_ */
