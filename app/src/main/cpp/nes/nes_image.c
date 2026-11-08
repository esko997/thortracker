/* NES song image validation and walkers (see nes_image.h). */
#include "nes_image.h"

#include <string.h>

#define DEFAULT_SPEED 6 /* famistudio_music_play's speed until the first $46 */

static uint32_t rd32(const uint8_t* p) { return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24; }

static int fail(NesImage* image, const char* why, uint32_t at) {
    image->info.problem = why;
    image->info.problem_at = at;
    return -1;
}

/* ---- envelopes ---------------------------------------------------------------------------- */

/* Marks instruction starts of a well-formed envelope in `starts` (bitmap of 256); returns NULL or why
 * it is malformed. Instructions: value (>= $80), hold count ($01-$7F), loop ($00 index). The envelope
 * ends with its final loop; loops go to a value or hold of the same envelope. */
static const char* envelope_shape(const uint8_t* e, int len, int kind, uint8_t* starts) {
    memset(starts, 0, 32);
    const int start = nm_envelope_start(kind);
    if (len < start + 2) return "envelope too short";
    int p = start, ended = 0;
    while (p < len) {
        starts[p >> 3] |= (uint8_t)(1 << (p & 7));
        const uint8_t b = e[p];
        if (b >= 0x80) {
            if (kind == NM_KIND_VOLUME && (b < 0xC0 || b > 0xCF)) return "volume envelope value outside 0..15";
            if (kind == NM_KIND_DUTY && (b < 0xC0 || b > 0xC3)) return "duty envelope value outside 0..3";
            ++p;
        } else if (b) {
            ++p;
        } else {
            if (p + 1 >= len) return "envelope loop without index";
            p += 2;
            if (p == len) ended = 1;
        }
    }
    if (!ended) return "envelope does not end with a loop";
#define IS_START(i) ((i) >= start && (i) < len && (starts[(i) >> 3] >> ((i) & 7) & 1) && e[i] != 0)
    for (p = start; p < len; ++p) {
        if ((starts[p >> 3] >> (p & 7) & 1) && e[p] == 0 && !IS_START(e[p + 1])) return "envelope loops to a non-instruction";
    }
    if (kind == NM_KIND_VOLUME && e[0] && !IS_START(e[0])) return "volume release index is not an instruction";
    if (kind == NM_KIND_PITCH && e[0] != 0x00 && e[0] != 0x80) return "pitch envelope flag is not $00/$80";
#undef IS_START
    return NULL;
}

int nm_envelope_instruction_at(const uint8_t* env, int len, int kind, int index) {
    uint8_t starts[32];
    if (index < 0 || index >= len || envelope_shape(env, len, kind, starts)) return 0;
    return starts[index >> 3] >> (index & 7) & 1;
}

int nm_find_envelope(const NesImageInfo* info, const uint8_t* directory, uint16_t addr) {
    int lo = 0, hi = (int)info->used_count - 1;
    while (lo <= hi) {
        const int mid = (lo + hi) / 2;
        const int idx = info->used[mid];
        const uint16_t a = nm_rd16(directory + 4 * idx);
        if (a == addr) return idx;
        if (a < addr) lo = mid + 1;
        else hi = mid - 1;
    }
    return -1;
}

/* ---- streams ------------------------------------------------------------------------------ */

const char* nm_walk_entry(const NmView* v, uint16_t addr, int channel, NmEntry* out) {
    const NesImageInfo* info = v->info;
    const uint8_t* s = v->region;
    const uint32_t limit = NES_SONG_BASE + info->size;
    const int thor = info->layout == NM_LAYOUT_THOR;
    const int tonal = channel <= 3, pitched = channel <= 2;
    uint32_t a = addr;
    int delays = 0;
    out->delay_end = 0;
    out->speed = -1;
#define NEED(n) \
    if (a < NES_SONG_BASE || a + (n) > limit) return "stream runs past the data"
#define BYTE(k) s[a + (k) - NES_SONG_BASE]
    for (;;) {
        NEED(1);
        const uint8_t b = BYTE(0);
        if (b >= 0x80) {
            if (b & 1) { /* empty run */
                out->rows = 1 + ((b & 0x7F) >> 1);
                out->end = (uint16_t)(a + 1);
                return NULL;
            }
            if (!tonal) return "instrument on the DPCM channel";
            if (thor && ((b & 0x7F) >> 1) >= info->instruments) return "instrument beyond the table";
            a += 1;
            continue;
        }
        if (!tonal) {
            /* FamiStudio's DPCM rows: [$46 s] [$47 d] [$48 c] [$52 v] [$43] then mapping k as note byte k
             * (engine note k + 12; the engine plays list entry k), `$40 k+12`, `$00` stop or an empty run. */
            if (b < 0x40) {
                if (b && thor && b > info->mappings) return "DPCM note without a DPCM list entry";
                out->rows = 1;
                out->end = (uint16_t)(a + 1);
                return NULL;
            }
            if (b == 0x40) {
                NEED(2);
                if (BYTE(1) < 13 || BYTE(1) > 12 + NES_IMAGE_MAX_DPCM_MAPPINGS || (thor && BYTE(1) - 12 > info->mappings)) {
                    return "DPCM note without a DPCM list entry";
                }
                out->rows = 1;
                out->end = (uint16_t)(a + 2);
                return NULL;
            }
            if (b == 0x52) { /* DMC delta counter: $80|v now, else at the next sample start */
                NEED(2);
                a += 2;
                continue;
            }
            if (b != 0x43 && b != 0x46 && b != 0x47 && b != 0x48) return "opcode not allowed on the DPCM channel";
        } else if (b < 0x40 || b == 0x45) { /* note / stop / release */
            out->rows = 1;
            out->end = (uint16_t)(a + 1);
            return NULL;
        }
        if (b >= 0x70) { /* volume track */
            a += 1;
            continue;
        }
        int size;
        switch (b) {
        case 0x40: /* extended note */
            NEED(2);
            if (BYTE(1) > 96) return "note beyond 96";
            out->rows = 1;
            out->end = (uint16_t)(a + 2);
            return NULL;
        case 0x50: /* slide: step, from, to */
            NEED(4);
            if (channel != 3 && (BYTE(2) < 1 || BYTE(2) > 96 || BYTE(3) < 1 || BYTE(3) > 96)) return "slide note outside 1..96";
            out->rows = 1;
            out->end = (uint16_t)(a + 4);
            return NULL;
        case 0x43: case 0x4C: case 0x4D: size = 0; break;
        case 0x4A: if (!pitched) return "vibrato opcode on a channel without pitch envelope"; size = 0; break;
        case 0x53: if (channel > 1) return "phase reset only on the pulses"; size = 0; break;
        case 0x46:
            NEED(2);
            if (BYTE(1) < 1 || BYTE(1) > 127) return "speed outside 1..127";
            out->speed = BYTE(1);
            size = 1;
            break;
        case 0x47:
            NEED(2);
            if (BYTE(1) > 127) return "note delay above 127";
            if (++delays > 1) return "two note delays in one row";
            out->delay_end = (uint16_t)(a + 2);
            size = 1;
            break;
        case 0x48: NEED(2); if (BYTE(1) > 127) return "cut delay above 127"; size = 1; break;
        case 0x4E: if (!pitched) return "fine pitch on a channel without pitch envelope"; size = 1; break;
        case 0x4F:
            NEED(2);
            if (channel == 2) return "duty effect on the triangle";
            if (BYTE(1) > 3) return "duty outside 0..3";
            size = 1;
            break;
        case 0x51: size = 2; break;
        case 0x54:
            NEED(2);
            if (thor && BYTE(1) >= info->instruments) return "instrument beyond the table";
            size = 1;
            break;
        case 0x49: case 0x4B:
            NEED(3);
            if (b == 0x49 && !pitched) return "vibrato opcode on a channel without pitch envelope";
            if (thor) {
                const uint8_t* dir = v->directory;
                const int idx = nm_find_envelope(info, dir, (uint16_t)(BYTE(1) | BYTE(2) << 8));
                if (idx < 0) return "envelope operand is not a directory entry";
                if (dir[4 * idx + 3] != (b == 0x49 ? NM_KIND_PITCH : NM_KIND_ARPEGGIO)) return "envelope operand of the wrong kind";
            }
            size = 2;
            break;
        default: return "opcode not allowed in a row";
        }
        NEED(1 + size);
        a += 1 + (uint32_t)size;
    }
#undef BYTE
#undef NEED
}

int nm_speed_at(const NmView* v, int order, int row) {
    const NesImageInfo* info = v->info;
    const uint8_t* s = v->region;
    int speed = info->speed[order], last_row = -1, last_channel = -1;
    for (int c = 0; c < NM_CHANNELS; ++c) {
        const uint8_t* e = s + (info->streams[c] + 4u * (uint32_t)order - NES_SONG_BASE);
        uint16_t a = nm_rd16(e + 2);
        int at = 0;
        for (int k = 0; k < e[1] && at <= row; ++k) {
            NmEntry entry;
            if (nm_walk_entry(v, a, c, &entry)) break;
            /* The engine runs rows in order and channels 0..4 within a row: the last $46 wins. */
            if (entry.speed >= 0 && (at > last_row || (at == last_row && c > last_channel))) {
                speed = entry.speed;
                last_row = at;
                last_channel = c;
            }
            at += entry.rows;
            a = entry.end;
        }
    }
    return speed;
}

/* ---- loading ------------------------------------------------------------------------------ */

static int load_raw(NesImage* image) {
    NesImageInfo* info = &image->info;
    const uint8_t* h = image->bytes;
    const uint8_t* s = nm_region(image);
    for (int i = 14; i < NES_IMAGE_HEADER_SIZE; ++i) {
        if ((i >= 18 && i <= 20) || (i >= NES_IMAGE_HEADER_SAMPLE_SIZE && i < NES_IMAGE_HEADER_SAMPLE_START + 2)) continue;
        if (h[i]) return fail(image, "reserved header byte not zero", (uint32_t)i);
    }
    for (int i = 0; i < NES_IMAGE_DIRECTORY_SIZE; ++i) {
        if (h[NES_IMAGE_HEADER_SIZE + i]) return fail(image, "raw image with an envelope directory", NES_IMAGE_HEADER_SIZE + (uint32_t)i);
    }
    /* FamiStudio header: count, instruments, samples-4, then 5 channel pointers + 4 tempo bytes per
     * song; the engine indexes songs with an 8-bit offset, so at most 17. */
    const uint32_t songs = s[0], index = h[12], size = info->size;
    if (size < 5 + 14 || songs == 0 || songs > 17 || index >= songs || 5 + 14 * songs > size) {
        return fail(image, "bad FamiStudio song list", NM_REGION_OFFSET);
    }
#define INSIDE(ptr) ((ptr) >= NES_SONG_BASE && (ptr) < NES_SONG_BASE + size)
    if (!INSIDE(nm_rd16(s + 1))) return fail(image, "instrument pointer outside the data", NM_REGION_OFFSET + 1);
    for (uint32_t k = 0; k < songs; ++k) {
        for (int ch = 0; ch < NM_CHANNELS; ++ch) {
            if (!INSIDE(nm_rd16(s + 5 + 14 * k + 2 * ch))) return fail(image, "channel pointer outside the data", NM_REGION_OFFSET + 5 + 14 * k);
        }
    }
#undef INSIDE
    const int rows = h[18], orders = h[19], loop = h[20];
    if (rows || orders || loop) {
        if (!rows || !orders || (loop != 0xFF && loop >= orders)) return fail(image, "bad raw timing", 18);
    }
    info->orders = (uint8_t)orders;
    info->loop = (uint8_t)loop;
    for (int o = 0; o < orders; ++o) info->rows[o] = (uint8_t)rows;
    info->tempo_ntsc = nm_rd16(s + 5 + 14 * index + 12);
    return 0;
}

static int load_thor(NesImage* image) {
    NesImageInfo* info = &image->info;
    const uint8_t* h = image->bytes;
    const uint8_t* dir = h + NES_IMAGE_HEADER_SIZE;
    const uint8_t* s = nm_region(image);
    const uint32_t size = info->size;
    if (h[12] != 0) return fail(image, "Thor layout: song index must be 0", 12);
    for (int i = 18; i < NES_IMAGE_HEADER_SIZE; ++i) {
        if (i >= NES_IMAGE_HEADER_SAMPLE_SIZE && i < NES_IMAGE_HEADER_SAMPLE_START + 2) continue;
        if (h[i]) return fail(image, "reserved header byte not zero", (uint32_t)i);
    }
    info->mappings = h[NES_IMAGE_HEADER_DPCM_COUNT];
    if (info->mappings > NES_IMAGE_MAX_DPCM_MAPPINGS) return fail(image, "more than 63 DPCM list entries", NES_IMAGE_HEADER_DPCM_COUNT);
    info->instruments = h[14];
    info->envelopes = nm_rd16(h + 16);
    if (info->instruments < 1 || info->instruments > NES_IMAGE_MAX_INSTRUMENTS) return fail(image, "instrument count outside 1..64", 14);
    if (info->envelopes > NES_IMAGE_MAX_ENVELOPES) return fail(image, "envelope count above the directory", 16);
    for (uint32_t i = 4u * info->envelopes; i < NES_IMAGE_DIRECTORY_SIZE; ++i) {
        if (dir[i]) return fail(image, "directory entry beyond the envelope count", NES_IMAGE_HEADER_SIZE + i);
    }
    /* Directory: used entries ascending by address, non-overlapping, well formed. */
    uint32_t prev_end = NES_SONG_BASE;
    uint8_t starts[32];
    info->used_count = 0;
    for (int i = 0; i < info->envelopes; ++i) {
        const uint8_t* d = dir + 4 * i;
        const uint16_t addr = nm_rd16(d);
        const uint32_t at = NES_IMAGE_HEADER_SIZE + 4u * (uint32_t)i;
        if (!addr) {
            if (d[2] || d[3]) return fail(image, "unused directory entry not zero", at);
            continue;
        }
        if (d[3] > NM_KIND_PITCH) return fail(image, "envelope kind outside 0..3", at + 3);
        if (d[2] < 2) return fail(image, "envelope shorter than 2 bytes", at + 2);
        if (addr < prev_end || addr + (uint32_t)d[2] > NES_SONG_BASE + size) return fail(image, "envelope overlaps, is out of order or outside the data", at);
        const char* why = envelope_shape(s + (addr - NES_SONG_BASE), d[2], d[3], starts);
        if (why) return fail(image, why, NM_REGION_OFFSET + (addr - NES_SONG_BASE));
        prev_end = addr + d[2];
        info->used[info->used_count++] = (uint16_t)i;
    }
    /* FamiStudio header: one song, the instrument table, channel streams, tempo words. */
    if (size < 19 || s[0] != 1) return fail(image, "Thor layout: exactly one song", NM_REGION_OFFSET);
    const uint16_t table = nm_rd16(s + 1);
    if (table < NES_SONG_BASE || table + 8u * info->instruments > NES_SONG_BASE + size) return fail(image, "instrument table outside the data", NM_REGION_OFFSET + 1);
    for (int i = 0; i < info->instruments; ++i) {
        for (int k = 0; k < 4; ++k) {
            const uint32_t at = table - NES_SONG_BASE + 8u * (uint32_t)i + 2u * (uint32_t)k;
            const int idx = nm_find_envelope(info, dir, nm_rd16(s + at));
            if (idx < 0) return fail(image, "instrument envelope is not a directory entry", NM_REGION_OFFSET + at);
            if (dir[4 * idx + 3] != k) return fail(image, "instrument envelope of the wrong kind", NM_REGION_OFFSET + at);
        }
    }
    info->tempo_ntsc = nm_rd16(s + 17);
    /* DPCM list: `mappings` FamiStudio entries {$4012, $4013, $4010, $4011} in the data (the header word
     * points 4 bytes before entry 1), each sample inside the used sample bytes. */
    if (info->mappings) {
        const uint32_t list = (uint32_t)nm_rd16(s + 3) + 4u;
        if (list < NES_SONG_BASE || list + 4u * info->mappings > NES_SONG_BASE + size) {
            return fail(image, "DPCM list outside the data", NM_REGION_OFFSET + 3);
        }
        info->dpcm_list = (uint16_t)list;
        for (uint32_t k = 0; k < info->mappings; ++k) {
            const uint32_t at = list - NES_SONG_BASE + 4u * k;
            const uint8_t* e = s + at;
            if (e[2] & 0xB0) return fail(image, "DPCM entry with IRQ or unused $4010 bits", NM_REGION_OFFSET + at + 2);
            if (e[3] > 127) return fail(image, "DPCM entry initial level above 127", NM_REGION_OFFSET + at + 3);
            const uint32_t from = NES_SAMPLE_BASE + 64u * e[0], to = from + 16u * e[1] + 1u;
            if (from < info->sample_start || to > (uint32_t)info->sample_start + info->samples) {
                return fail(image, "DPCM sample outside the sample block", NM_REGION_OFFSET + at);
            }
        }
    }
    /* Order streams: `$41 n pattern` per order, then `$42` to the loop order's entry. */
    int orders = -1, loop = -1;
    for (int c = 0; c < NM_CHANNELS; ++c) {
        const uint16_t base = nm_rd16(s + 5 + 2 * c);
        info->streams[c] = base;
        int o = 0;
        for (;; ++o) {
            const uint32_t a = base + 4u * (uint32_t)o;
            if (a < NES_SONG_BASE || a + 3 > NES_SONG_BASE + size) return fail(image, "order stream outside the data", NM_REGION_OFFSET + 5 + 2 * c);
            const uint8_t* e = s + (a - NES_SONG_BASE);
            if (e[0] == 0x42) {
                const uint16_t target = nm_rd16(e + 1);
                if (target < base || (target - base) % 4 || (target - base) / 4 >= (uint32_t)o) return fail(image, "order stream loop is not an order entry", a - NES_SONG_BASE + NM_REGION_OFFSET);
                if (o == 0) return fail(image, "no orders", a - NES_SONG_BASE + NM_REGION_OFFSET);
                if (orders >= 0 && (o != orders || (int)(target - base) / 4 != loop)) return fail(image, "channels disagree on orders or loop", a - NES_SONG_BASE + NM_REGION_OFFSET);
                orders = o;
                loop = (int)(target - base) / 4;
                break;
            }
            if (e[0] != 0x41 || a + 4 > NES_SONG_BASE + size) return fail(image, "order stream entry is not $41 n address", a - NES_SONG_BASE + NM_REGION_OFFSET);
            if (o >= NES_IMAGE_MAX_ORDERS) return fail(image, "more than 255 orders", a - NES_SONG_BASE + NM_REGION_OFFSET);
        }
    }
    info->orders = (uint8_t)orders;
    info->loop = (uint8_t)loop;
    /* Patterns: n entries each, the same row count on every channel; speed at each order start. */
    const NmView view = nm_view(image);
    uint8_t speed = DEFAULT_SPEED;
    for (int o = 0; o < orders; ++o) {
        info->speed[o] = speed;
        int order_rows = -1;
        for (int c = 0; c < NM_CHANNELS; ++c) {
            const uint8_t* e = s + (info->streams[c] + 4u * (uint32_t)o - NES_SONG_BASE);
            const int n = e[1];
            uint16_t a = nm_rd16(e + 2);
            if (!n) return fail(image, "pattern with zero entries", (uint32_t)(e - h) + 1);
            int rows = 0;
            for (int k = 0; k < n; ++k) {
                NmEntry entry;
                const char* why = nm_walk_entry(&view, a, c, &entry);
                if (why) return fail(image, why, a >= NES_SONG_BASE ? NM_REGION_OFFSET + (uint32_t)(a - NES_SONG_BASE) : (uint32_t)(e - h) + 2);
                rows += entry.rows;
                a = entry.end;
            }
            if (rows > NES_IMAGE_MAX_ROWS) return fail(image, "order longer than 255 rows", (uint32_t)(e - h));
            if (order_rows >= 0 && rows != order_rows) return fail(image, "channels disagree on the order's rows", (uint32_t)(e - h));
            order_rows = rows;
        }
        info->rows[o] = (uint8_t)order_rows;
        speed = (uint8_t)nm_speed_at(&view, o, order_rows - 1);
    }
    return 0;
}

int nm_load_image(NesImage* image) {
    NesImageInfo* info = &image->info;
    memset(info, 0, sizeof *info);
    const uint8_t* h = image->bytes;
    if (rd32(h) != NM_MAGIC) return fail(image, "not a TTNS image", 0);
    if (rd32(h + 4) != NES_PLAYER_ID) return fail(image, "lowered for another player build", 4);
    if (nm_rd16(h + 8) != NES_SONG_BASE) return fail(image, "song base is not the player's NES_SONG_BASE", 8);
    /* One region: song data from NES_SONG_BASE up, the sample block (if any) above it; zero elsewhere. */
    info->size = nm_rd16(h + 10);
    if (info->size > NM_REGION_SIZE) return fail(image, "data larger than the region", 10);
    info->samples = nm_rd16(h + NES_IMAGE_HEADER_SAMPLE_SIZE);
    info->sample_start = nm_rd16(h + NES_IMAGE_HEADER_SAMPLE_START);
    const uint32_t start = info->sample_start, end = start + info->samples;
    if (!info->samples != !start) return fail(image, "sample block size and start must both be 0 or both set", NES_IMAGE_HEADER_SAMPLE_SIZE);
    if (start && (start < NES_SAMPLE_BASE || start % NES_IMAGE_SAMPLE_ALIGN || end > NES_REGION_END)) {
        return fail(image, "sample block not 64-aligned within $C000..the vectors", NES_IMAGE_HEADER_SAMPLE_START);
    }
    if (start && NES_SONG_BASE + (uint32_t)info->size > start) return fail(image, "song data overlaps the samples", 10);
    const uint8_t* s = nm_region(image);
    for (uint32_t a = NES_SONG_BASE + info->size; a < NES_REGION_END; ++a) {
        if (a == start) a = end;
        if (a < NES_REGION_END && s[a - NES_SONG_BASE]) {
            return fail(image, "region not zero outside the song data and the samples", NM_REGION_OFFSET + a - NES_SONG_BASE);
        }
    }
    info->layout = h[13];
    int r;
    if (info->layout == NM_LAYOUT_THOR) {
        r = load_thor(image);
    } else if (info->layout == NM_LAYOUT_RAW) {
        r = load_raw(image);
    } else {
        r = fail(image, "unknown layout", 13);
    }
    if (r) return r;
    info->loaded = NM_LOADED;
    return 0;
}

void nm_build_raw_image(NesImage* image, const uint8_t* song, uint32_t size, const uint8_t* samples, uint32_t sample_size,
                        int song_index, int rows_per_order, int order_count, int loop_order) {
    uint8_t* h = image->bytes;
    memset(image, 0, sizeof *image);
    const uint32_t words[2] = {NM_MAGIC, NES_PLAYER_ID};
    for (int w = 0; w < 2; ++w) {
        for (int i = 0; i < 4; ++i) h[4 * w + i] = (uint8_t)(words[w] >> (8 * i));
    }
    h[8] = (uint8_t)NES_SONG_BASE;
    h[9] = (uint8_t)(NES_SONG_BASE >> 8);
    if (size > NM_REGION_SIZE) size = NM_REGION_SIZE;
    h[10] = (uint8_t)size;
    h[11] = (uint8_t)(size >> 8);
    h[12] = (uint8_t)song_index;
    h[13] = NM_LAYOUT_RAW;
    h[18] = (uint8_t)rows_per_order;
    h[19] = (uint8_t)order_count;
    h[20] = (uint8_t)loop_order;
    memcpy(h + NM_REGION_OFFSET, song, size);
    if (sample_size > NES_REGION_END - NES_SAMPLE_BASE) sample_size = NES_REGION_END - NES_SAMPLE_BASE;
    if (sample_size) {
        h[NES_IMAGE_HEADER_SAMPLE_SIZE] = (uint8_t)sample_size;
        h[NES_IMAGE_HEADER_SAMPLE_SIZE + 1] = (uint8_t)(sample_size >> 8);
        h[NES_IMAGE_HEADER_SAMPLE_START] = (uint8_t)NES_SAMPLE_BASE;
        h[NES_IMAGE_HEADER_SAMPLE_START + 1] = (uint8_t)(NES_SAMPLE_BASE >> 8);
        memcpy(h + NM_REGION_OFFSET + (NES_SAMPLE_BASE - NES_SONG_BASE), samples, sample_size);
    }
}
