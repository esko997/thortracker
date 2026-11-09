/* Atari 2600 image validation and row decoding (tia_image.h; layout in docs/AUDIO_ENGINE.md "Atari 2600"). Checks run
 * in this order: header, the order tables past the order count, instruments, patterns (with the speed each order
 * starts with). The offset reported is the offending byte; for a u16/u32 field (order count, loop, data size, pattern
 * and envelope offsets) the field's first byte; for reserved ranges the first non-zero byte.
 *
 * Every rejection reason is written through the TI_PROBLEM macro, one string literal per use (no other text in this
 * file may take that form): tools/audio/prepare_tia.py collects them into TiaPlayerImage.LOADER_PROBLEMS. Field
 * offsets for the empty-envelope reason: the offset field, else the loop byte, else the release byte. */
#include "tia_image.h"

#include <string.h>

#define TI_PROBLEM(text) (text)

static int fail(TiaImage* image, const char* problem, int offset) {
    image->info.loaded = 0;
    image->info.problem = problem;
    image->info.problem_offset = offset;
    return -1;
}

/* Offset of the first non-zero byte of [at, at + n), or -1. */
static int first_nonzero(const uint8_t* b, int at, int n) {
    for (int i = 0; i < n; ++i)
        if (b[at + i]) return at + i;
    return -1;
}

/* 0: fine; 1: the parameter is out of range; 2: the code has no effect. `why` receives the reason. */
static int effect_problem(const TiInfo* info, int code, int param, const char** why) {
    switch (code) {
    case TI_FX_ARPEGGIO: case TI_FX_SLIDE_UP: case TI_FX_SLIDE_DOWN: case TI_FX_PORTAMENTO: case TI_FX_VIBRATO:
    case TI_FX_VOLUME_SLIDE: case TI_FX_BREAK: case TI_FX_DELAY: case TI_FX_FINE: case TI_FX_CUT:
        return 0;
    case TI_FX_JUMP:
        *why = TI_PROBLEM("Bxx jumps past the last order");
        return param >= info->orders;
    case TI_FX_VOLUME:
        *why = TI_PROBLEM("Cxx volume above 0F");
        return param > TI_VOLUME_MAX;
    case TI_FX_SPEED:
        *why = TI_PROBLEM("F00: speed must be 01..FF");
        return param == 0;
    case TI_FX_WAVEFORM:
        *why = TI_PROBLEM("Wxx waveform above 0F");
        return param > TI_WAVEFORM_MAX;
    default:
        *why = code > 35 ? TI_PROBLEM("effect code above Z") : TI_PROBLEM("unknown effect code");
        return 2;
    }
}

/* Envelope k of the instrument at `base`: shape, range inside the data and values in range. */
static int check_envelope(TiaImage* image, int base, int k, uint32_t size) {
    const uint8_t* b = image->bytes;
    const int at = base + TI_I_ENVELOPES + TI_ENVELOPE_BYTES * k;
    const int offset = ti_u16(b + at), length = b[at + 2], loop = b[at + 3], release = b[at + 4];
    if (b[at + 5]) return fail(image, TI_PROBLEM("envelope reserved byte"), at + 5);
    if (!length) {
        if (offset) return fail(image, TI_PROBLEM("empty envelope: offset 0, loop and release FF"), at);
        if (loop != TI_ENV_NONE) return fail(image, TI_PROBLEM("empty envelope: offset 0, loop and release FF"), at + 3);
        if (release != TI_ENV_NONE) return fail(image, TI_PROBLEM("empty envelope: offset 0, loop and release FF"), at + 4);
        return 0;
    }
    if (loop != TI_ENV_NONE && loop >= length) return fail(image, TI_PROBLEM("envelope loop past its length"), at + 3);
    if (release != TI_ENV_NONE && (release < 1 || release >= length))
        return fail(image, TI_PROBLEM("envelope release must be 1..length - 1"), at + 4);
    if (loop != TI_ENV_NONE && release != TI_ENV_NONE && release <= loop)
        return fail(image, TI_PROBLEM("envelope release must follow its loop"), at + 4);
    if ((uint32_t)offset + (uint32_t)length > size) return fail(image, TI_PROBLEM("envelope runs past the data size"), at);
    for (int i = 0; i < length; ++i) {
        const int pos = TI_OFF_DATA + offset + i, v = b[pos], s = (int8_t)b[pos];
        if (k == TI_ENV_VOLUME && v > TI_VOLUME_MAX) return fail(image, TI_PROBLEM("volume envelope value above 15"), pos);
        if (k == TI_ENV_ARPEGGIO && (s < -64 || s > 63))
            return fail(image, TI_PROBLEM("arpeggio envelope value outside -64..63"), pos);
        if (k == TI_ENV_WAVEFORM && v > TI_WAVEFORM_MAX) return fail(image, TI_PROBLEM("waveform envelope value above 15"), pos);
    }
    return 0;
}

static int check_instrument(TiaImage* image, int slot, uint32_t size) {
    const uint8_t* b = image->bytes;
    const int base = TI_OFF_INSTRUMENTS + TI_INSTRUMENT_SIZE * (slot - 1);
    const uint8_t* p = b + base;
    if (first_nonzero(b, base, TI_INSTRUMENT_SIZE) < 0) return 0;
    if (slot > image->info.instruments) return fail(image, TI_PROBLEM("instrument record past the instrument count"), base);
    const int flags = p[TI_I_FLAGS];
    if (!(flags & TI_FLAG_DEFINED) || (flags & ~(TI_FLAG_DEFINED | TI_FLAG_RAW | TI_FLAG_RELATIVE)))
        return fail(image, TI_PROBLEM("instrument flags"), base + TI_I_FLAGS);
    if (p[TI_I_WAVEFORM] > TI_WAVEFORM_MAX) return fail(image, TI_PROBLEM("instrument waveform above 15"), base + TI_I_WAVEFORM);
    const int reserved = first_nonzero(b, base + 2, TI_I_ENVELOPES - 2);
    if (reserved >= 0) return fail(image, TI_PROBLEM("instrument reserved bytes 2..7"), reserved);
    for (int k = 0; k < TI_ENVELOPE_COUNT; ++k)
        if (check_envelope(image, base, k, size)) return -1;
    return 0;
}

/* The record of a defined slot, else NULL. */
static const uint8_t* defined_record(const TiaImage* image, int slot) {
    if (slot < 1 || slot > image->info.instruments) return NULL;
    const uint8_t* rec = image->bytes + TI_OFF_INSTRUMENTS + TI_INSTRUMENT_SIZE * (slot - 1);
    return (rec[TI_I_FLAGS] & TI_FLAG_DEFINED) ? rec : NULL;
}

int ti_load_image(TiaImage* image, int region) {
    const uint8_t* b = image->bytes;
    TiInfo* info = &image->info;
    memset(info, 0, sizeof *info);
    info->problem_offset = -1;
    if (memcmp(b, "TTIA", 4)) return fail(image, TI_PROBLEM("not an Atari 2600 image (magic)"), TI_H_MAGIC);
    if (b[TI_H_LAYOUT] != TI_LAYOUT) return fail(image, TI_PROBLEM("unknown layout"), TI_H_LAYOUT);
    info->region = b[TI_H_REGION];
    if (info->region != TI_REGION_NTSC && info->region != TI_REGION_PAL)
        return fail(image, TI_PROBLEM("region must be 0 (NTSC) or 1 (PAL)"), TI_H_REGION);
    if (info->region != region) return fail(image, TI_PROBLEM("image is for the other TV region"), TI_H_REGION);
    info->speed = b[TI_H_SPEED];
    if (!info->speed) return fail(image, TI_PROBLEM("speed must be 1..255"), TI_H_SPEED);
    info->instruments = b[TI_H_INSTRUMENTS];
    if (info->instruments > TI_MAX_INSTRUMENTS) return fail(image, TI_PROBLEM("instrument count above 64"), TI_H_INSTRUMENTS);
    info->orders = ti_u16(b + TI_H_ORDERS);
    if (info->orders < 1 || info->orders > TI_MAX_ORDERS) return fail(image, TI_PROBLEM("order count must be 1..256"), TI_H_ORDERS);
    info->loop = ti_u16(b + TI_H_LOOP);
    if (info->loop >= info->orders) return fail(image, TI_PROBLEM("loop order past the last order"), TI_H_LOOP);
    int reserved = first_nonzero(b, 12, TI_H_DATA_SIZE - 12);
    if (reserved >= 0) return fail(image, TI_PROBLEM("header reserved bytes 12..19"), reserved);
    const uint32_t size = ti_u32(b + TI_H_DATA_SIZE);
    if (size > TI_DATA_CAPACITY) return fail(image, TI_PROBLEM("data size above the region"), TI_H_DATA_SIZE);
    info->data_size = size;
    reserved = first_nonzero(b, TI_H_DATA_SIZE + 4, TI_HEADER_SIZE - (TI_H_DATA_SIZE + 4));
    if (reserved >= 0) return fail(image, TI_PROBLEM("header reserved bytes 24..63"), reserved);

    for (int o = info->orders; o < TI_MAX_ORDERS; ++o) {
        if (b[TI_OFF_ROWS + o]) return fail(image, TI_PROBLEM("rows of an order past the order count"), TI_OFF_ROWS + o);
        for (int c = 0; c < TI_CHANNELS; ++c) {
            const int at = TI_OFF_ORDERS + 2 * (TI_CHANNELS * o + c);
            if (ti_u16(b + at)) return fail(image, TI_PROBLEM("pattern of an order past the order count"), at);
        }
    }
    for (int o = 0; o < info->orders; ++o) info->rows[o] = b[TI_OFF_ROWS + o] ? b[TI_OFF_ROWS + o] : TI_MAX_ROWS;

    for (int n = 1; n <= TI_MAX_INSTRUMENTS; ++n)
        if (check_instrument(image, n, size)) return -1;

    /* Patterns: every (order, channel) decodes rows(o) rows inside the used data (a run longer than the order's
     * remaining rows is cut there). The speed each order starts with in sequential straight play: the header's,
     * then the last Fxx of the previous orders (rows, then channels, then columns; jumps ignored). */
    int speed = info->speed;
    for (int o = 0; o < info->orders; ++o) {
        info->start_speed[o] = (uint8_t)speed;
        int offsets[TI_CHANNELS], runs[TI_CHANNELS];
        for (int c = 0; c < TI_CHANNELS; ++c) {
            const int at = TI_OFF_ORDERS + 2 * (TI_CHANNELS * o + c);
            offsets[c] = ti_u16(b + at);
            runs[c] = 0;
            if ((uint32_t)offsets[c] >= size) return fail(image, TI_PROBLEM("pattern offset past the data size"), at);
        }
        for (int r = 0; r < info->rows[o]; ++r) {
            for (int c = 0; c < TI_CHANNELS; ++c) {
                if (runs[c]) {
                    --runs[c];
                    continue;
                }
                int at = offsets[c];
                if ((uint32_t)at >= size) return fail(image, TI_PROBLEM("pattern runs past the data size"), TI_OFF_DATA + at);
                const int m = b[TI_OFF_DATA + at++];
                if (m & 0x80) {
                    runs[c] = m & 0x7F;
                    offsets[c] = at;
                    continue;
                }
                const int effects = (m >> 2) & 7;
                if (!m || (m & 0x60) || effects > TI_MAX_EFFECTS) return fail(image, TI_PROBLEM("row mask byte"), TI_OFF_DATA + at - 1);
                const int need = (m & 1) + ((m >> 1) & 1) + 2 * effects;
                if ((uint32_t)(at + need) > size) return fail(image, TI_PROBLEM("row runs past the data size"), TI_OFF_DATA + at);
                if (m & 1) {
                    const int note = b[TI_OFF_DATA + at];
                    if (note > TI_NOTE_MAX && note != TI_NOTE_OFF) return fail(image, TI_PROBLEM("note byte"), TI_OFF_DATA + at);
                    ++at;
                }
                if (m & 2) {
                    if (!defined_record(image, b[TI_OFF_DATA + at]))
                        return fail(image, TI_PROBLEM("row names an instrument the image does not define"), TI_OFF_DATA + at);
                    ++at;
                }
                for (int e = 0; e < effects; ++e, at += 2) {
                    const int code = b[TI_OFF_DATA + at], param = b[TI_OFF_DATA + at + 1];
                    const char* why = NULL;
                    const int problem = effect_problem(info, code, param, &why);
                    /* the code's byte for a code without an effect, the parameter's for a parameter out of range */
                    if (problem) return fail(image, why, TI_OFF_DATA + (problem == 2 ? at : at + 1));
                    if (code == TI_FX_SPEED) speed = param; /* rows, then channels, then columns: the last F wins */
                }
                offsets[c] = at;
            }
        }
    }
    info->loaded = TI_LOADED;
    return 0;
}

void ti_next_row(const uint8_t* bytes, TiCursor* cursor, TiRow* row) {
    row->note = -1;
    row->instrument = 0;
    row->effects = 0;
    if (cursor->run) {
        --cursor->run;
        return;
    }
    const uint8_t* p = bytes + TI_OFF_DATA + cursor->offset;
    int at = 0;
    const int m = p[at++];
    if (m & 0x80) {
        cursor->run = m & 0x7F;
        cursor->offset += 1;
        return;
    }
    if (m & 1) row->note = p[at++];
    if (m & 2) row->instrument = p[at++];
    row->effects = (m >> 2) & 7;
    for (int e = 0; e < row->effects; ++e) {
        row->code[e] = p[at++];
        row->param[e] = p[at++];
    }
    cursor->offset += at;
}

void ti_pattern_cursor(const TiaImage* image, int order, int channel, TiCursor* cursor) {
    cursor->offset = ti_u16(image->bytes + TI_OFF_ORDERS + 2 * (TI_CHANNELS * order + channel));
    cursor->run = 0;
}

void ti_read_row(const TiaImage* image, int order, int channel, int row, TiRow* out, TiCursor* after) {
    TiCursor c;
    ti_pattern_cursor(image, order, channel, &c);
    for (int r = 0; r < row; ++r) ti_next_row(image->bytes, &c, out);
    ti_next_row(image->bytes, &c, out);
    if (after) *after = c;
}
