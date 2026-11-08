/* SID image validation (sid_image.h). */
#include "sid_image.h"

#include <string.h>

/* GoatTracker packed pattern bytes (player.s) */
#define GT_FX 0x40
#define GT_FXONLY 0x50
#define GT_NOTE 0x60
#define GT_LASTNOTE 0xBC
#define GT_REST 0xBD
#define GT_KEYON 0xBF
#define GT_PACKEDREST 0xC0
#define GT_LOOPSONG 0xFF
#define GT_REPEAT 0xD0
#define FX_FUNKTEMPO 0x0E
#define FX_SETTEMPO 0x0F

static uint16_t rd16(const uint8_t* p) { return (uint16_t)(p[0] | p[1] << 8); }

int sm_parse_row(const uint8_t* p, int i, int size, int* rows, int* instrument, int* fx, int* param, int* note) {
    *rows = 1;
    *instrument = 0;
    *fx = *param = -1;
    *note = 0;
#define NEXT() (i < size ? p[i++] : -1)
    int b = NEXT();
    if (b < 0) return -1;
    if (b >= GT_PACKEDREST) {
        *rows = 0x100 - b;
        return i;
    }
    if (b < GT_FX) { /* instrument change, followed by a command or a note */
        if (b == 0) return -1;
        *instrument = b;
        if ((b = NEXT()) < 0 || b >= GT_PACKEDREST || b < GT_FX) return -1;
    }
    if (b < GT_NOTE) { /* command: FX (a note follows) or FXONLY (the row is a rest) */
        *fx = b & 0x0F;
        *param = 0;
        if (*fx && (*param = NEXT()) < 0) return -1;
        if (b >= GT_FXONLY) {
            *note = GT_REST;
            return i;
        }
        if ((b = NEXT()) < GT_NOTE || b >= GT_PACKEDREST) return -1;
    }
    *note = b;
    return i;
#undef NEXT
}

static int fail(SidImage* image, const char* why, uint32_t at) {
    image->info.problem = why;
    image->info.problem_at = at;
    image->info.loaded = 0;
    return -1;
}

#define REGION_OFFSET(addr) (SID_IMAGE_HEADER_SIZE + (uint32_t)((addr) - SID_REGION_BASE))

/* Rows of the packed pattern at `addr` (as the player walks it: up to the $00 end mark after a row,
 * or 256 bytes), or -1 with *why set. Instruments must exist; packed rests not on the first row. */
static int pattern_rows(const SidImage* image, uint16_t addr, const char** why, uint32_t* at) {
    const uint8_t* region = sm_region(image);
    if (addr < SID_HEAP_BASE || addr >= SID_REGION_END) {
        *why = "pattern outside the song data";
        return -1;
    }
    int size = SID_REGION_END - addr;
    if (size > 256) size = 256;
    const uint8_t* p = region + (addr - SID_REGION_BASE);
    int i = 0, rows = 0;
    for (;;) {
        int n, ins, fx, param, note;
        const int next = sm_parse_row(p, i, size, &n, &ins, &fx, &param, &note);
        *at = REGION_OFFSET(addr + i);
        if (next < 0) {
            *why = "invalid pattern row";
            return -1;
        }
        if (!rows && note == 0) {
            *why = "packed rest on the first row of a pattern";
            return -1;
        }
        if (ins > image->info.instruments) {
            *why = "instrument number above the instrument count";
            return -1;
        }
        rows += n;
        i = next;
        if (i >= 256 || (i < size && p[i] == 0)) break; /* the end mark, or the 256-byte wrap */
        if (i >= size) {
            *why = "pattern runs past the region";
            return -1;
        }
    }
    if (rows > SID_MAX_ROWS) {
        *why = "pattern longer than 128 rows";
        return -1;
    }
    return rows;
}

static void walk_rows(const uint8_t* region, const SidImageInfo* info, int order, int rows, int* fx, int* param,
                      int* tempo, int* funk);

int sm_load_image(SidImage* image) {
    SidImageInfo* info = &image->info;
    memset(info, 0, sizeof *info);
    const uint8_t* h = image->bytes;
    const uint8_t* region = sm_region(image);
    if (rd16(h) != (SM_MAGIC & 0xFFFF) || rd16(h + 2) != (SM_MAGIC >> 16)) return fail(image, "not a SID image", 0);
    if ((uint32_t)(h[4] | h[5] << 8 | h[6] << 16 | (uint32_t)h[7] << 24) != SID_PLAYER_ID) {
        return fail(image, "lowered for another player build", 4);
    }
    if (rd16(h + 8) != SID_REGION_BASE) return fail(image, "region base", 8);
    info->data_end = rd16(h + 10);
    if (info->data_end < SID_HEAP_BASE || info->data_end > SID_REGION_END) return fail(image, "data end", 10);
    if (h[12] != SID_LAYOUT_THOR) return fail(image, "unknown layout", 12);
    if (h[13] > 1) return fail(image, "chip model", 13);
    if (h[14] < 1 || h[14] > 16) return fail(image, "speed multiplier must be 1..16", 14);
    if (h[15] < 2 || h[15] > 127) return fail(image, "default tempo", 15);
    info->model = h[13];
    info->multiplier = h[14];
    info->default_tempo = h[15];
    info->firstnohr = h[16];
    info->firstlegato = h[17];
    info->hr_ad = h[18];
    info->hr_sr = h[19];
    info->instruments = h[20];
    if (info->instruments < 1 || info->instruments > SID_MAX_INSTRUMENTS) return fail(image, "instrument count", 20);
    if (info->firstnohr < 1 || info->firstlegato < info->firstnohr || info->firstlegato > SID_MAX_INSTRUMENTS + 2) {
        return fail(image, "instrument order (FIRSTNOHRINSTR / FIRSTLEGATOINSTR)", 16);
    }
    for (int i = 21; i < SM_SLOT_MAP; ++i) {
        if (h[i]) return fail(image, "reserved header byte", (uint32_t)i);
    }
    if (h[SM_SLOT_MAP]) return fail(image, "slot 0 has no instrument", SM_SLOT_MAP);
    uint8_t seen[SID_MAX_INSTRUMENTS + 1] = {0};
    for (int s = 1; s < 64; ++s) {
        const uint8_t n = h[SM_SLOT_MAP + s];
        if (!n) continue;
        if (n > info->instruments || seen[n]) return fail(image, "instrument slot map", (uint32_t)(SM_SLOT_MAP + s));
        seen[n] = 1;
    }
    /* Orderlists: pattern numbers only, then $FF and the restart order; the same shape on all channels. */
    for (int c = 0; c < SM_CHANNELS; ++c) {
        const uint16_t ol = (uint16_t)(region[SID_MT_SONGTBLLO - SID_REGION_BASE + c] |
                                       region[SID_MT_SONGTBLHI - SID_REGION_BASE + c] << 8);
        const uint32_t at = REGION_OFFSET(SID_MT_SONGTBLLO + c);
        if (ol < SID_HEAP_BASE || ol >= info->data_end) return fail(image, "orderlist outside the song data", at);
        info->orderlist[c] = ol;
        int n = 0;
        for (;; ++n) {
            if (ol + n + 1 >= info->data_end) return fail(image, "orderlist runs past the song data", REGION_OFFSET(ol + n));
            const uint8_t e = region[ol + n - SID_REGION_BASE];
            if (e == GT_LOOPSONG) break;
            if (e >= GT_REPEAT) return fail(image, "orderlist command (repeat/transpose) in the Thor layout", REGION_OFFSET(ol + n));
            if (e >= SID_MAX_PATTERNS) return fail(image, "pattern number", REGION_OFFSET(ol + n));
            if (n >= SID_MAX_ORDERS) return fail(image, "more than 254 orders", REGION_OFFSET(ol + n));
            info->pattern[c][n] = e;
        }
        const uint8_t restart = region[ol + n + 1 - SID_REGION_BASE];
        if (!n || restart >= n) return fail(image, "restart order", REGION_OFFSET(ol + n + 1));
        if (c == 0) {
            info->orders = (uint8_t)n;
            info->loop = restart;
        } else if (n != info->orders || restart != info->loop) {
            return fail(image, "channels differ in order count or restart", REGION_OFFSET(ol + n));
        }
    }
    /* Patterns: valid, inside the song data, equal rows per order on every channel. */
    for (int o = 0; o < info->orders; ++o) {
        for (int c = 0; c < SM_CHANNELS; ++c) {
            const int k = info->pattern[c][o];
            const uint16_t pa = (uint16_t)(region[SID_MT_PATTTBLLO - SID_REGION_BASE + k] |
                                           region[SID_MT_PATTTBLHI - SID_REGION_BASE + k] << 8);
            const char* why = NULL;
            uint32_t at = REGION_OFFSET(SID_MT_PATTTBLLO + k);
            const int rows = pattern_rows(image, pa, &why, &at);
            if (rows < 0) return fail(image, why, at);
            if (pa >= info->data_end) return fail(image, "pattern outside the song data", REGION_OFFSET(SID_MT_PATTTBLLO + k));
            if (c == 0) {
                info->rows[o] = (uint8_t)rows;
            } else if (rows != info->rows[o]) {
                return fail(image, "patterns of an order differ in length", at);
            }
        }
    }
    /* The global tempo each order starts with in straight play (see walk_rows). */
    int tempo = info->default_tempo, funk = 0;
    int fx[SM_CHANNELS] = {-1, -1, -1}, param[SM_CHANNELS] = {0, 0, 0};
    for (int o = 0; o < info->orders; ++o) {
        info->tempo[o] = (uint8_t)tempo;
        info->funk[o] = (uint8_t)funk;
        for (int c = 0; c < SM_CHANNELS; ++c) {
            info->start_fx[c][o] = (uint8_t)(fx[c] < 0 ? 0xFF : fx[c]);
            info->start_param[c][o] = (uint8_t)param[c];
        }
        walk_rows(region, info, o, info->rows[o], fx, param, &tempo, &funk);
    }
    info->loaded = SM_LOADED;
    return 0;
}

static const uint8_t* pattern_bytes(const uint8_t* region, const SidImageInfo* info, int channel, int order, int* size) {
    const int k = info->pattern[channel][order];
    const int a = region[SID_MT_PATTTBLLO - SID_REGION_BASE + k] | region[SID_MT_PATTTBLHI - SID_REGION_BASE + k] << 8;
    *size = SID_REGION_END - a < 256 ? SID_REGION_END - a : 256;
    return region + (a - SID_REGION_BASE);
}

/* Rows 0..rows-1 of `order` as the player starts them: tick-0 commands persist per channel until the
 * next command byte (fx/param in-out, -1 = none) and run at every row start; F (< $80) sets the global
 * mt_chntempo, E sets it to 0 (funktempo) with its speedtable index. Channels run in order 1, 2, 3. */
static void walk_rows(const uint8_t* region, const SidImageInfo* info, int order, int rows, int* fx, int* param,
                      int* tempo, int* funk) {
    const uint8_t* p[SM_CHANNELS];
    int size[SM_CHANNELS], pos[SM_CHANNELS] = {0, 0, 0}, left[SM_CHANNELS] = {0, 0, 0};
    for (int c = 0; c < SM_CHANNELS; ++c) p[c] = pattern_bytes(region, info, c, order, &size[c]);
    for (int r = 0; r < rows; ++r) {
        for (int c = 0; c < SM_CHANNELS; ++c) {
            if (left[c] > 0) {
                --left[c];
            } else {
                int n, ins, f, pr, note;
                const int next = sm_parse_row(p[c], pos[c], size[c], &n, &ins, &f, &pr, &note);
                if (next < 0) continue; /* not a loaded image's pattern */
                pos[c] = next;
                left[c] = n - 1;
                if (f >= 0) {
                    fx[c] = f;
                    param[c] = pr;
                }
            }
            if (fx[c] == FX_SETTEMPO && param[c] < 0x80) *tempo = param[c];
            if (fx[c] == FX_FUNKTEMPO) {
                *tempo = 0;
                *funk = param[c];
            }
        }
    }
}

void sm_tempo_at(const uint8_t* region, const SidImageInfo* info, int order, int row, int* tempo, int* funk) {
    int fx[SM_CHANNELS], param[SM_CHANNELS];
    for (int c = 0; c < SM_CHANNELS; ++c) {
        fx[c] = info->start_fx[c][order] == 0xFF ? -1 : info->start_fx[c][order];
        param[c] = info->start_param[c][order];
    }
    *tempo = info->tempo[order];
    *funk = info->funk[order];
    walk_rows(region, info, order, row + 1, fx, param, tempo, funk);
}

void sm_fetch_state(const uint8_t* region, const SidImageInfo* info, int channel, int order, int row, SmFetch* out) {
    int size;
    const uint8_t* p = pattern_bytes(region, info, channel, order, &size);
    out->pattptr = out->packedrest = out->instrument = 0;
    out->fx = out->param = -1;
    out->note = GT_REST;
    int i = 0, fetched = 0;
    while (fetched <= row) {
        int n, ins, fx, param, note;
        const int next = sm_parse_row(p, i, size, &n, &ins, &fx, &param, &note);
        if (next < 0) return;
        if (ins) out->instrument = (uint8_t)ins;
        if (fx >= 0) {
            out->fx = fx;
            out->param = param;
        }
        if (row < fetched + n - 1) { /* inside a packed rest: the counter has counted row - fetched + 1 */
            out->pattptr = (uint8_t)i;
            out->packedrest = (uint8_t)(p[i] + (row - fetched + 1));
            out->note = GT_REST;
            return;
        }
        fetched += n;
        i = next;
        out->note = note ? note : GT_REST;
    }
    out->pattptr = (uint8_t)(i >= 256 || i >= size || p[i] == 0 ? 0 : i); /* end mark or 256-byte wrap: 0 */
}
