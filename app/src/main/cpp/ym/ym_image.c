/* YM2612 image validation and row decoding (ym_image.h; layout in docs/AUDIO_ENGINE.md "YM2612"). */
#include "ym_image.h"

#include <string.h>

static int fail(YmImage* image, const char* problem, int offset) {
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

/* 0 if `code` with `param` may stand on channel c, else 1 with *why. */
static int effect_problem(const YmInfo* info, int c, int code, int param, const char** why) {
    const int x = param >> 4, y = param & 15;
    const int fm = c < YM_FM_CHANNELS;
    switch (code) {
    case YM_FX_ARPEGGIO: case YM_FX_SLIDE_UP: case YM_FX_SLIDE_DOWN: case YM_FX_PORTAMENTO: case YM_FX_VIBRATO:
    case YM_FX_VOLUME_SLIDE: case YM_FX_BREAK: case YM_FX_DELAY: case YM_FX_FINE: case YM_FX_CUT:
        return 0;
    case YM_FX_PAN:
        *why = "8xy pan on a PSG channel (FM only)";
        return !fm;
    case YM_FX_JUMP:
        *why = "Bxx jumps past the last order";
        return param >= info->orders;
    case YM_FX_VOLUME:
        *why = fm ? "Cxx volume above 7F" : "Cxx volume above 0F on a PSG channel";
        return param > (fm ? 0x7F : 0x0F);
    case YM_FX_FEEDBACK:
        *why = fm ? "Exx feedback above 7" : "Exx feedback on a PSG channel (FM only)";
        return !fm || param > 7;
    case YM_FX_SPEED:
        *why = "F00: speed must be 1..FF";
        return param == 0;
    case YM_FX_BRIGHTNESS:
        *why = fm ? "Ixx brightness above 7F" : "Ixx brightness on a PSG channel (FM only)";
        return !fm || param > 0x7F;
    case YM_FX_LFO:
        *why = "Lxx LFO must be 00 or 08..0F";
        return param != 0 && (param < 0x08 || param > 0x0F);
    case YM_FX_MULT:
        *why = fm ? "Mxy operator must be 1..4" : "Mxy ratio on a PSG channel (FM only)";
        return !fm || x < 1 || x > 4;
    case YM_FX_NOISE:
        *why = c == YM_CH_NOISE ? "Nxy: x must be 0..1 and y 0..3" : "Nxy noise mode outside the Noise channel";
        return c != YM_CH_NOISE || x > 1 || y > 3;
    default:
        *why = "unknown effect code";
        return 1;
    }
}

static int check_instrument(YmImage* image, int slot) {
    const int base = YM_OFF_INSTRUMENTS + YM_INSTRUMENT_SIZE * (slot - 1);
    const uint8_t* p = image->bytes + base;
    if (zero(p, YM_INSTRUMENT_SIZE)) return 0;
    if (slot > image->info.instruments) return fail(image, "instrument record past the instrument count", base);
    if ((p[YM_I_FLAGS] & ~(YM_FLAG_DEFINED | YM_FLAG_PSG)) || !(p[YM_I_FLAGS] & YM_FLAG_DEFINED))
        return fail(image, "instrument flags", base + YM_I_FLAGS);
    if (p[YM_I_FLAGS] & YM_FLAG_PSG) {
        if (p[YM_I_SUSTAIN] > 15) return fail(image, "PSG sustain level above 15", base + YM_I_SUSTAIN);
        if (p[YM_I_NOISE] > 7) return fail(image, "PSG noise mode above 7", base + YM_I_NOISE);
        if (!zero(p + 6, YM_I_SWEEP_START - 6)) return fail(image, "PSG instrument reserved bytes 6..31", base + 6);
    } else {
        if (p[YM_I_ALG] > 7) return fail(image, "FM algorithm above 7", base + YM_I_ALG);
        if (p[YM_I_FEEDBACK] > 7) return fail(image, "FM feedback above 7", base + YM_I_FEEDBACK);
        if (p[YM_I_AMS_FMS] & 0xC8) return fail(image, "FM AMS/FMS byte: only bits 0-2 and 4-5", base + YM_I_AMS_FMS);
        /* Bits each operator register must leave 0: $30 $40 $50 $60 $70 $80 $90. */
        static const uint8_t reserved[YM_OP_BYTES] = {0x80, 0x80, 0x20, 0x60, 0xE0, 0x00, 0xF0};
        for (int k = 0; k < 4; ++k)
            for (int r = 0; r < YM_OP_BYTES; ++r) {
                const int at = YM_I_OPS + YM_OP_BYTES * k + r;
                if (p[at] & reserved[r]) return fail(image, "FM operator register with reserved bits set", base + at);
            }
    }
    for (int i = YM_I_SWEEP_START; i <= YM_I_SWEEP_END; ++i) {
        const int v = (int8_t)p[i];
        if (v < -96 || v > 96) return fail(image, "sweep start/end outside -96..96 semitones", base + i);
    }
    if (!zero(p + 35, 5)) return fail(image, "instrument reserved bytes 35..39", base + 35);
    return 0;
}

const uint8_t* ym_instrument(const YmImage* image, int slot, int psg) {
    if (slot < 1 || slot > image->info.instruments) return NULL;
    const uint8_t* rec = image->bytes + YM_OFF_INSTRUMENTS + YM_INSTRUMENT_SIZE * (slot - 1);
    if (!(rec[YM_I_FLAGS] & YM_FLAG_DEFINED)) return NULL;
    if (psg >= 0 && (rec[YM_I_FLAGS] & YM_FLAG_PSG) != (psg ? YM_FLAG_PSG : 0)) return NULL;
    return rec;
}

int ym_load_image(YmImage* image) {
    const uint8_t* b = image->bytes;
    YmInfo* info = &image->info;
    memset(info, 0, sizeof *info);
    info->problem_offset = -1;
    if (memcmp(b, "TTYM", 4)) return fail(image, "not a YM2612 image (magic)", 0);
    if (b[YM_H_LAYOUT] != YM_LAYOUT) return fail(image, "unknown layout", YM_H_LAYOUT);
    info->region = b[YM_H_REGION];
    if (info->region > YM_PAL) return fail(image, "region must be 0 (NTSC) or 1 (PAL)", YM_H_REGION);
    info->speed = b[YM_H_SPEED];
    if (!info->speed) return fail(image, "speed must be 1..255", YM_H_SPEED);
    info->lfo = b[YM_H_LFO];
    if (info->lfo != 0 && (info->lfo < 0x08 || info->lfo > 0x0F)) return fail(image, "LFO byte must be 0 or 8..15", YM_H_LFO);
    info->orders = ym_u16(b + YM_H_ORDERS);
    if (info->orders < 1 || info->orders > YM_MAX_ORDERS) return fail(image, "order count must be 1..256", YM_H_ORDERS);
    info->loop = ym_u16(b + YM_H_LOOP);
    if (info->loop >= info->orders) return fail(image, "loop order past the last order", YM_H_LOOP);
    info->instruments = b[YM_H_INSTRUMENTS];
    if (info->instruments > YM_MAX_INSTRUMENTS) return fail(image, "instrument count above 64", YM_H_INSTRUMENTS);
    if (b[13]) return fail(image, "header reserved byte 13", 13);
    const int size = ym_u16(b + YM_H_DATA_SIZE);
    if (size > (int)YM_DATA_CAPACITY) return fail(image, "data size above the region", YM_H_DATA_SIZE);
    if (!zero(b + 16, YM_HEADER_SIZE - 16)) return fail(image, "header reserved bytes 16..63", 16);

    for (int o = info->orders; o < YM_MAX_ORDERS; ++o) {
        if (b[YM_OFF_ROWS + o]) return fail(image, "rows of an order past the order count", YM_OFF_ROWS + o);
        if (!zero(b + YM_OFF_ORDERS + 2 * YM_CHANNELS * o, 2 * YM_CHANNELS))
            return fail(image, "patterns of an order past the order count", YM_OFF_ORDERS + 2 * YM_CHANNELS * o);
    }
    for (int o = 0; o < info->orders; ++o) info->rows[o] = b[YM_OFF_ROWS + o] ? b[YM_OFF_ROWS + o] : 256;
    for (int n = 1; n <= YM_MAX_INSTRUMENTS; ++n)
        if (check_instrument(image, n)) return -1;

    /* Patterns: every (order, channel) decodes rows(o) rows inside the used data. */
    int speed = info->speed;
    for (int o = 0; o < info->orders; ++o) {
        info->start_speed[o] = (uint8_t)speed;
        int offsets[YM_CHANNELS], runs[YM_CHANNELS];
        for (int c = 0; c < YM_CHANNELS; ++c) {
            offsets[c] = ym_u16(b + YM_OFF_ORDERS + 2 * (YM_CHANNELS * o + c));
            runs[c] = 0;
            if (offsets[c] >= size) return fail(image, "pattern offset past the data size", YM_OFF_ORDERS + 2 * (YM_CHANNELS * o + c));
        }
        for (int r = 0; r < info->rows[o]; ++r) {
            for (int c = 0; c < YM_CHANNELS; ++c) {
                if (runs[c]) { --runs[c]; continue; }
                int at = offsets[c];
                if (at >= size) return fail(image, "pattern runs past the data size", YM_OFF_DATA + at);
                const int m = b[YM_OFF_DATA + at++];
                if (m & 0x80) { runs[c] = m & 0x7F; offsets[c] = at; continue; }
                const int effects = (m >> 2) & 7;
                if (!m || (m & 0x60) || effects > YM_MAX_EFFECTS) return fail(image, "row mask byte", YM_OFF_DATA + at - 1);
                const int need = (m & 1) + ((m >> 1) & 1) + 2 * effects;
                if (at + need > size) return fail(image, "row runs past the data size", YM_OFF_DATA + at);
                if (m & 1) {
                    const int note = b[YM_OFF_DATA + at];
                    if (note >= YM_NOTES && note != YM_NOTE_OFF) return fail(image, "note byte", YM_OFF_DATA + at);
                    ++at;
                }
                if (m & 2) {
                    const int slot = b[YM_OFF_DATA + at];
                    if (!ym_instrument(image, slot, -1)) return fail(image, "row names an instrument the image does not define", YM_OFF_DATA + at);
                    if (!ym_instrument(image, slot, ym_is_psg_channel(c)))
                        return fail(image, ym_is_psg_channel(c) ? "FM instrument on a PSG channel" : "PSG instrument on an FM channel",
                                    YM_OFF_DATA + at);
                    ++at;
                }
                for (int e = 0; e < effects; ++e, at += 2) {
                    const int code = b[YM_OFF_DATA + at], param = b[YM_OFF_DATA + at + 1];
                    const char* why = "effect code above Z";
                    if (code > 35 || effect_problem(info, c, code, param, &why)) return fail(image, why, YM_OFF_DATA + at);
                    if (code == YM_FX_SPEED) speed = param; /* rows, then channels, then columns: the last F wins */
                }
                offsets[c] = at;
            }
        }
    }
    info->loaded = YM_LOADED;
    return 0;
}

void ym_next_row(const uint8_t* bytes, YmCursor* cursor, YmRow* row) {
    row->note = -1;
    row->instrument = 0;
    row->effects = 0;
    if (cursor->run) {
        --cursor->run;
        return;
    }
    const uint8_t* p = bytes + YM_OFF_DATA + cursor->offset;
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

YmCursor ym_pattern(const YmImage* image, int order, int channel) {
    YmCursor c = {ym_u16(image->bytes + YM_OFF_ORDERS + 2 * (YM_CHANNELS * order + channel)), 0};
    return c;
}

void ym_read_row(const YmImage* image, int order, int channel, int row, YmRow* out) {
    YmCursor c = ym_pattern(image, order, channel);
    for (int r = 0; r < row; ++r) ym_next_row(image->bytes, &c, out);
    ym_next_row(image->bytes, &c, out);
}
