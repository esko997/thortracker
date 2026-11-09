/* NES song images: the bytes a lowering produces for the "nes-famistudio", "nes-famistudio-vrc6",
 * "nes-famistudio-vrc7", "nes-famistudio-n163-1".."-8", "nes-famistudio-fds", "nes-famistudio-s5b" and
 * "nes-famistudio-mmc5" engines, their validation and the stream/envelope walkers the machine uses to seek and to
 * relocate a playing song onto a new image. Layout and contract: docs/AUDIO_ENGINE.md, "Image layout", "VRC6", "VRC7",
 * "N163", "FDS", "Sunsoft 5B" and "MMC5"; numbers in generated/nes_player_image.h, nes_vrc6_player_image.h,
 * nes_vrc7_player_image.h, nes_n163_<n>_player_image.h, nes_fds_player_image.h, nes_s5b_player_image.h and
 * nes_mmc5_player_image.h (owned by tools/audio/prepare_nes_player.py), one NesPlayer per build (nes_player.h).
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
 * VRC7: then FM 1-6; 5 + n with N163: then N163 1..n; 6 with FDS: then the FDS channel; 8 with the 5B: then 5B square
 * 1-3; 7 with MMC5: then MMC5 pulse 1, pulse 2). With VRC7, N163, FDS and the 5B FamiStudio's expansion instrument
 * pointer follows the instrument pointer (nes_player_head: 7 header bytes before the stream words; MMC5 has none: 5).
 * The Thor layout of a VRC7 image holds one 16-byte
 * expansion instrument per instrument (nm_load_image checks their envelopes, override and patch bytes). The Thor layout
 * of an N163 image holds one 16-byte expansion instrument per instrument (volume, arpeggio, pitch and wave-index
 * envelope words, wave position and size in samples, wave table pointer, 4 zero bytes), the wave tables (a pointer per
 * wave to size / 2 packed bytes, the even sample in the low nibble) and envelope directory kind NM_KIND_WAVE (the
 * wave-index envelopes, directory 256..319; effect envelopes then start at 320). The Thor layout of an FDS image holds
 * one 16-byte expansion instrument per instrument (volume, arpeggio, pitch and wave-index envelope words, byte 8 = mod
 * depth << 2 | master volume, wave table pointer at 9, mod table pointer at 11, bytes 13-14 the mod speed
 * (nm_fds_manual_speed), byte 15 the mod delay), the wave tables (a pointer per wave to 64 samples 0..63), the
 * 32-entry mod tables (0..7) and the same NM_KIND_WAVE directory entries. The Thor layout of a 5B image holds one
 * 16-byte expansion instrument per instrument (nm_load_image checks their envelopes, shape, octave and zero bytes) and
 * directory kinds NM_KIND_MIXER and NM_KIND_NOISE (instrument slot s's at 256 + 2 s and 257 + 2 s; effect envelopes
 * then start at 384). An MMC5 image is the 2A03 layout with two more stream words (plain 2A03 instruments).
 * player->effect_envelopes is each build's first effect envelope index.
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
/* Directory kinds. NM_KIND_WAVE: the N163 / FDS wave-index envelope (encoded like a volume envelope: release byte,
 * values $C0 + wave index 0..63); N163 and FDS images only. NM_KIND_MIXER and NM_KIND_NOISE: 5B images only (the 5B
 * squares' envelope slots 2 and 3). */
enum {
    NM_KIND_VOLUME = 0,
    NM_KIND_ARPEGGIO = 1,
    NM_KIND_DUTY = 2,
    NM_KIND_PITCH = 3,
    NM_KIND_WAVE = 4,
    NM_KIND_MIXER = 5,
    NM_KIND_NOISE = 6
};
_Static_assert(NM_KIND_MIXER == NES_S5B_KIND_MIXER && NM_KIND_NOISE == NES_S5B_KIND_NOISE, "the 5B build's envelope kinds");

/* What nm_load_image derives from a valid image (copied with it; no pointers into the bytes). */
typedef struct NesImageInfo {
    uint32_t loaded; /* NM_LOADED after a successful nm_load_image */
    const NesPlayer* player;               /* the build the image was loaded for (static data) */
    uint8_t layout, orders, loop, instruments; /* loop 0xFF: the song ends (raw only) */
    uint8_t mappings;                      /* Thor: DPCM list entries (stream notes 1..mappings) */
    uint16_t size, envelopes, tempo_ntsc;
    uint16_t samples, sample_start;        /* the sample block: bytes and address (0, 0: none) */
    uint16_t dpcm_list;                    /* Thor: address of DPCM list entry 1 (the header word + 4) */
    uint16_t exp_instruments;              /* Thor, VRC7, N163, FDS and 5B: address of the expansion instrument table,
                                              else 0 */
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
    int instrument;     /* instrument index the entry's last instrument change (`$80 | i << 1` or `$54 i`) sets, or -1 */
    int note;           /* 1: the terminator starts a note (a note byte, `$40 n` or a slide), not a stop, release or run */
} NmEntry;

/* Walks the entry of channel `channel` at song address `addr`; returns NULL or why it is invalid.
 * Checks every opcode against the channel (and the player's VRC6/VRC7/N163/FDS/5B/MMC5 rules) and, with the Thor layout,
 * instrument, envelope and DPCM mapping operands against the table, directory and DPCM list. */
const char* nm_walk_entry(const NmView* v, uint16_t addr, int channel, NmEntry* out);

/* Speed in effect after row `row` of `order` (-1: at the order start) when the order is entered
 * with the speed straight play gives it (Thor layout). */
int nm_speed_at(const NmView* v, int order, int row);

/* The speed in effect after each row of `order` (speeds[0..rows - 1]; the same rule as nm_speed_at, in one pass over the
 * order's streams): at FamiTracker tempo 150 a row lasts that many frames (Thor layout). */
void nm_row_speeds(const NmView* v, int order, uint8_t speeds[NES_IMAGE_MAX_ROWS]);

/* Directory index of the used entry at song address `addr`, or -1. */
int nm_find_envelope(const NesImageInfo* info, const uint8_t* directory, uint16_t addr);

/* First instruction index of an envelope of `kind` (volume, pitch and wave index keep a header byte; arpeggio, duty,
 * mixer and noise start with their first instruction). */
static inline int nm_envelope_start(int kind) {
    return kind == NM_KIND_VOLUME || kind == NM_KIND_PITCH || kind == NM_KIND_WAVE ? 1 : 0;
}

/* Whether the engine may resume the envelope `env` (len bytes, `kind`, well formed for its player) at
 * `index`: an instruction starts there (value, hold count or loop code; not a loop's index byte or header). */
int nm_envelope_instruction_at(const uint8_t* env, int len, int kind, int index);

/* N163 and FDS builds, Thor layout: wave table entries the expansion instrument entry at song address `entry` uses
 * (the largest value of its wave-index envelope + 1), or -1 when its wave-index word is not a WAVE directory entry. */
int nm_n163_waves(const NmView* v, uint16_t entry);

/* FDS expansion instrument bytes 13-14 (`b` points at byte 13), as the FDS build's patched loader reads them
 * (prepare_nes_player.py FDS_ENGINE_PATCH): a manual mod speed 0..$0FFF, or -1 for an auto-mod fraction.
 *   lo < $80:              manual, FamiStudio's own word (hi <= $0F)
 *   lo >= $80, hi >= $80:  manual, Thor Tracker's tag for a low byte with bit 7 set (lo, hi | $80; hi & $70 == 0)
 *   lo >= $80, hi < $80:   auto-mod $80 | numerator, denominator (FamiStudio's; 1..32 each)
 * FamiStudio 4.5.3's own loader reads any lo >= $80 as auto-mod, so it cannot hold manual speeds $x80-$xFF. */
static inline int nm_fds_manual_speed(const uint8_t* b) {
    if (!(b[0] & 0x80)) return b[0] | b[1] << 8;
    return b[1] & 0x80 ? b[0] | (b[1] & 0x0F) << 8 : -1;
}

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_NES_IMAGE_H_ */
