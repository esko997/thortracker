/* OPL3 image validation and row decoding (opl3_image.h; layout in docs/AUDIO_ENGINE.md "OPL3"). */
#include "opl3_image.h"

#include <string.h>

static int fail(Opl3Image* image, const char* problem, int offset) {
    image->info.loaded = 0;
    image->info.problem = problem;
    image->info.problem_offset = offset;
    return -1;
}

static int zero(const uint8_t* p, int n) {
    for (int i = 0; i < n; ++i)
        if (p[i]) return 0;
    return 1;
}

static int effect_problem(const O3Info* info, int code, int param, const char** why) {
    const int x = param >> 4, y = param & 15;
    switch (code) {
    case O3_FX_ARPEGGIO: case O3_FX_SLIDE_UP: case O3_FX_SLIDE_DOWN: case O3_FX_PORTAMENTO:
    case O3_FX_VIBRATO: case O3_FX_PAN: case O3_FX_VOLUME_SLIDE: case O3_FX_BREAK: case O3_FX_DELAY:
    case O3_FX_CHORD: case O3_FX_FINE: case O3_FX_CUT:
        return 0;
    case O3_FX_JUMP:
        *why = "Bxx jumps past the last order";
        return param >= info->orders;
    case O3_FX_VOLUME:
        *why = "Cxx volume above 3F";
        return param > 0x3F;
    case O3_FX_FEEDBACK:
        *why = "Exx feedback above 7";
        return param > 7;
    case O3_FX_SPEED:
        *why = "F00: speed must be 1..FF";
        return param == 0;
    case O3_FX_BRIGHTNESS:
        *why = "Ixx brightness above 3F";
        return param > 0x3F;
    case O3_FX_SHAPE:
        *why = "Kxx names a chord shape the image does not have";
        return param > info->shapes;
    case O3_FX_MULT:
        *why = "Mxy operator must be 1..4";
        return x < 1 || x > 4;
    case O3_FX_WAVE:
        *why = "Wxy operator must be 0..4 and wave 0..7";
        return x > 4 || y > 7;
    default:
        *why = "unknown effect code";
        return 1;
    }
}

static int check_instrument(Opl3Image* image, int slot) {
    const int base = O3_OFF_INSTRUMENTS + O3_INSTRUMENT_SIZE * (slot - 1);
    const uint8_t* p = image->bytes + base;
    if (zero(p, O3_INSTRUMENT_SIZE)) return 0;
    if (slot > image->info.instruments) return fail(image, "instrument record past the instrument count", base);
    if ((p[O3_I_FLAGS] & ~(O3_FLAG_DEFINED | O3_FLAG_FOUR_OP)) || !(p[O3_I_FLAGS] & O3_FLAG_DEFINED))
        return fail(image, "instrument flags", base + O3_I_FLAGS);
    const int four = p[O3_I_FLAGS] & O3_FLAG_FOUR_OP;
    if (p[O3_I_ALG] > (four ? 3 : 1)) return fail(image, "instrument algorithm", base + O3_I_ALG);
    if (p[O3_I_FEEDBACK] > 7) return fail(image, "instrument feedback above 7", base + O3_I_FEEDBACK);
    if (p[3]) return fail(image, "instrument reserved byte", base + 3);
    for (int k = 0; k < 4; ++k) {
        const uint8_t* op = p + O3_I_OPS + 5 * k;
        if (k >= 2 && !four) {
            if (!zero(op, 5)) return fail(image, "two-operator instrument with operator 3/4 data", base + O3_I_OPS + 5 * k);
        } else if (op[4] > 7) {
            return fail(image, "operator waveform above 7", base + O3_I_OPS + 5 * k + 4);
        }
    }
    for (int i = O3_I_SWEEP_START; i <= O3_I_SWEEP_END; ++i) {
        const int v = (int8_t)p[i];
        if (v < -96 || v > 96) return fail(image, "sweep start/end outside -96..96 semitones", base + i);
    }
    if (!zero(p + 27, 5)) return fail(image, "instrument reserved bytes 27..31", base + 27);
    return 0;
}

static int defined(const Opl3Image* image, int slot) {
    return slot >= 1 && slot <= image->info.instruments &&
           (image->bytes[O3_OFF_INSTRUMENTS + O3_INSTRUMENT_SIZE * (slot - 1) + O3_I_FLAGS] & O3_FLAG_DEFINED);
}

int o3_load_image(Opl3Image* image) {
    const uint8_t* b = image->bytes;
    O3Info* info = &image->info;
    memset(info, 0, sizeof *info);
    info->problem_offset = -1;
    if (memcmp(b, "TTO3", 4)) return fail(image, "not an OPL3 image (magic)", 0);
    if (b[O3_H_LAYOUT] != O3_LAYOUT) return fail(image, "unknown layout", O3_H_LAYOUT);
    info->multispeed = b[O3_H_MULTISPEED];
    if (info->multispeed < 1 || info->multispeed > O3_MAX_MULTISPEED) return fail(image, "multispeed must be 1..8", O3_H_MULTISPEED);
    info->speed = b[O3_H_SPEED];
    if (!info->speed) return fail(image, "speed must be 1..255", O3_H_SPEED);
    if (b[O3_H_DEPTH] & 0x3F) return fail(image, "depth byte: only bits 7 and 6", O3_H_DEPTH);
    info->orders = o3_u16(b + O3_H_ORDERS);
    if (info->orders < 1 || info->orders > O3_MAX_ORDERS) return fail(image, "order count must be 1..256", O3_H_ORDERS);
    info->loop = o3_u16(b + O3_H_LOOP);
    if (info->loop >= info->orders) return fail(image, "loop order past the last order", O3_H_LOOP);
    info->instruments = b[O3_H_INSTRUMENTS];
    if (info->instruments > O3_MAX_INSTRUMENTS) return fail(image, "instrument count above 64", O3_H_INSTRUMENTS);
    info->shapes = b[O3_H_SHAPES];
    if (info->shapes > O3_MAX_SHAPES) return fail(image, "chord shape count above 64", O3_H_SHAPES);
    const int size = o3_u16(b + O3_H_DATA_SIZE);
    if (size > (int)O3_DATA_CAPACITY) return fail(image, "data size above the region", O3_H_DATA_SIZE);
    if (!zero(b + 16, O3_HEADER_SIZE - 16)) return fail(image, "header reserved bytes 16..63", 16);

    for (int o = info->orders; o < O3_MAX_ORDERS; ++o) {
        if (b[O3_OFF_ROWS + o]) return fail(image, "rows of an order past the order count", O3_OFF_ROWS + o);
        if (!zero(b + O3_OFF_ORDERS + 2 * O3_CHANNELS * o, 2 * O3_CHANNELS))
            return fail(image, "patterns of an order past the order count", O3_OFF_ORDERS + 2 * O3_CHANNELS * o);
    }
    for (int o = 0; o < info->orders; ++o) info->rows[o] = b[O3_OFF_ROWS + o] ? b[O3_OFF_ROWS + o] : 256;
    for (int n = 1; n <= O3_MAX_INSTRUMENTS; ++n)
        if (check_instrument(image, n)) return -1;
    for (int k = 1; k <= O3_MAX_SHAPES; ++k) {
        const int at = O3_OFF_SHAPES + 4 * (k - 1);
        const int8_t* s = (const int8_t*)(b + at);
        if (k > info->shapes) {
            if (!zero(b + at, 4)) return fail(image, "chord shape past the shape count", at);
            continue;
        }
        if (!s[0]) return fail(image, "empty chord shape", at);
        int ended = 0;
        for (int i = 0; i < 4; ++i) {
            if (s[i] < -48 || s[i] > 48) return fail(image, "chord interval outside -48..48", at + i);
            if (!s[i]) ended = 1;
            else if (ended) return fail(image, "chord interval after a 0", at + i);
        }
    }

    /* Patterns: every (order, channel) decodes rows(o) rows inside the used data. */
    int speed = info->speed;
    for (int o = 0; o < info->orders; ++o) {
        info->start_speed[o] = (uint8_t)speed;
        int offsets[O3_CHANNELS], runs[O3_CHANNELS];
        for (int c = 0; c < O3_CHANNELS; ++c) {
            offsets[c] = o3_u16(b + O3_OFF_ORDERS + 2 * (O3_CHANNELS * o + c));
            runs[c] = 0;
            if (offsets[c] >= size) return fail(image, "pattern offset past the data size", O3_OFF_ORDERS + 2 * (O3_CHANNELS * o + c));
        }
        for (int r = 0; r < info->rows[o]; ++r) {
            for (int c = 0; c < O3_CHANNELS; ++c) {
                if (runs[c]) { --runs[c]; continue; }
                int at = offsets[c];
                if (at >= size) return fail(image, "pattern runs past the data size", O3_OFF_DATA + at);
                const int m = b[O3_OFF_DATA + at++];
                if (m & 0x80) { runs[c] = m & 0x7F; offsets[c] = at; continue; }
                const int effects = (m >> 2) & 7;
                if (!m || (m & 0x60) || effects > O3_MAX_EFFECTS) return fail(image, "row mask byte", O3_OFF_DATA + at - 1);
                const int need = (m & 1) + ((m >> 1) & 1) + 2 * effects;
                if (at + need > size) return fail(image, "row runs past the data size", O3_OFF_DATA + at);
                if (m & 1) {
                    const int note = b[O3_OFF_DATA + at];
                    if (note >= O3_NOTES && note != O3_NOTE_OFF) return fail(image, "note byte", O3_OFF_DATA + at);
                    ++at;
                }
                if (m & 2) {
                    const int slot = b[O3_OFF_DATA + at];
                    if (!defined(image, slot)) return fail(image, "row names an instrument the image does not define", O3_OFF_DATA + at);
                    if (c >= 6 && (b[O3_OFF_INSTRUMENTS + O3_INSTRUMENT_SIZE * (slot - 1)] & O3_FLAG_FOUR_OP))
                        return fail(image, "four-operator instrument on channel 7-9 (two-operator channels)", O3_OFF_DATA + at);
                    ++at;
                }
                for (int e = 0; e < effects; ++e, at += 2) {
                    const int code = b[O3_OFF_DATA + at], param = b[O3_OFF_DATA + at + 1];
                    const char* why = "effect code above Z";
                    if (code > 35 || effect_problem(info, code, param, &why)) return fail(image, why, O3_OFF_DATA + at);
                    if (code == O3_FX_SPEED) speed = param; /* rows, then channels, then columns: the last F wins */
                }
                offsets[c] = at;
            }
        }
    }
    info->loaded = O3_LOADED;
    return 0;
}

void o3_next_row(const uint8_t* bytes, O3Cursor* cursor, O3Row* row) {
    row->note = -1;
    row->instrument = 0;
    row->effects = 0;
    if (cursor->run) {
        --cursor->run;
        return;
    }
    const uint8_t* p = bytes + O3_OFF_DATA + cursor->offset;
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

void o3_read_row(const Opl3Image* image, int order, int channel, int row, O3Row* out, O3Cursor* after) {
    O3Cursor c = {o3_u16(image->bytes + O3_OFF_ORDERS + 2 * (O3_CHANNELS * order + channel)), 0};
    for (int r = 0; r < row; ++r) o3_next_row(image->bytes, &c, out);
    o3_next_row(image->bytes, &c, out);
    if (after) *after = c;
}
