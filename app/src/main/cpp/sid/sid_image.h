/* SID song images: the bytes a lowering produces for the "c64-sid" engine (GoatTracker 2 playroutine
 * data at fixed addresses) and their validation. Layout and contract: docs/AUDIO_ENGINE.md, SID
 * "Image layout"; numbers in generated/sid_player_image.h (owned by tools/audio/prepare_sid_player.py,
 * whose gt_song.lower is the reference lowering).
 *
 * An image is SID_IMAGE_HEADER_SIZE header bytes and the region: C64 RAM SID_REGION_BASE..SID_REGION_END
 * (fixed tables at the player's data labels, then orderlists and patterns from SID_HEAP_BASE).
 * Header, little-endian:
 *    0 "TTSD"   4 u32 player id   8 u16 region base ($1800)   10 u16 data end (export: last byte + 1)
 *   12 u8 layout (SID_LAYOUT_THOR)   13 u8 chip model (0 = MOS 6581, 1 = MOS 8580)
 *   14 u8 speed multiplier 1..16 (player calls per PAL frame)
 *   15 u8 DEFAULTTEMPO (ticks per row - 1, 2..127)   16 u8 FIRSTNOHRINSTR   17 u8 FIRSTLEGATOINSTR
 *   18 u8 hard restart attack/decay (ADPARAM)   19 u8 hard restart sustain/release (SRPARAM)
 *   20 u8 instrument count 1..63   32..95 u8[64]: player instrument number of each Thor slot (0 = none)
 *   other bytes zero.
 * Pure functions; no allocation. */
#ifndef THORTRACKER_SID_IMAGE_H_
#define THORTRACKER_SID_IMAGE_H_

#include <stdint.h>

#include "generated/sid_player_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define SM_REGION_SIZE (SID_REGION_END - SID_REGION_BASE)
#define SM_MAGIC 0x44535454u /* "TTSD" little-endian */
#define SM_CHANNELS 3
#define SM_LOADED 0x4C4F4144u
#define SM_SLOT_MAP 32       /* header offset of the slot -> player instrument map */

_Static_assert(SID_IMAGE_HEADER_SIZE + SM_REGION_SIZE == SID_IMAGE_SIZE, "image = header + region");
_Static_assert(SID_PLAYER_BASE + SID_PLAYER_SIZE <= SID_REGION_BASE, "player below the region");

/* What sm_load_image derives from a valid image (copied with it; no pointers into the bytes). */
typedef struct SidImageInfo {
    uint32_t loaded;                       /* SM_LOADED after a successful sm_load_image */
    uint8_t model, multiplier, default_tempo, firstnohr, firstlegato, hr_ad, hr_sr, instruments;
    uint8_t orders, loop;                  /* order count 1..254, restart order */
    uint16_t data_end;
    uint16_t orderlist[SM_CHANNELS];       /* orderlist address of each channel */
    uint8_t rows[SID_MAX_ORDERS];          /* rows of each order (equal on all channels) */
    uint8_t pattern[SM_CHANNELS][SID_MAX_ORDERS]; /* packed pattern number per channel and order */
    uint8_t tempo[SID_MAX_ORDERS];         /* global mt_chntempo when each order starts (straight play) */
    uint8_t funk[SID_MAX_ORDERS];          /* speedtable index of the last funktempo before it (0: none) */
    const char* problem;                   /* why sm_load_image failed (static string), else NULL */
    uint32_t problem_at;                   /* image byte offset the problem refers to */
} SidImageInfo;

typedef struct SidImage {
    uint8_t bytes[SID_IMAGE_SIZE];
    SidImageInfo info;
} SidImage;

/* Validates image->bytes and fills image->info. Returns 0, or -1 with info.problem set. */
int sm_load_image(SidImage* image);

static inline const uint8_t* sm_region(const SidImage* image) { return image->bytes + SID_IMAGE_HEADER_SIZE; }

/* One packed pattern row at `p[i]` (as the player reads it): returns the index after it and sets *rows
 * (1, or the length of a packed rest), *instrument (0: none), *fx / *param (-1: no command byte), *note
 * (the note/rest/key byte, or 0 for a packed rest); -1 if the bytes are not a row. */
int sm_parse_row(const uint8_t* p, int i, int size, int* rows, int* instrument, int* fx, int* param, int* note);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_SID_IMAGE_H_ */
