/* YM2612 image validation and row decoding (ym_image.h; layout in docs/AUDIO_ENGINE.md "YM2612"). */
#include "ym_image.h"

#include <string.h>

static int fail(YmImage* image, const char* problem, int offset) {
    image->info.loaded = 0;
    image->info.problem = problem;
    image->info.problem_offset = offset;
    return -1;
}

static int zero(const uint8_t* p, size_t n) {
    for (size_t i = 0; i < n; ++i)
        if (p[i]) return 0;
    return 1;
}

static uint32_t u32(const uint8_t* p) { return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24; }

/* 0 if `code` with `param` may stand on channel c, else 1 with *why. */
static int effect_problem(const YmInfo* info, int c, int code, int param, const char** why) {
    const int x = param >> 4, y = param & 15;
    const int kind = ym_channel_kind(c), fm = kind == YM_KIND_FM;
    if (kind == YM_KIND_PCM) {
        switch (code) {
        case YM_FX_JUMP: case YM_FX_BREAK: case YM_FX_SPEED: case YM_FX_DELAY: case YM_FX_LFO: case YM_FX_CUT: break;
        default: *why = "effect not available on a PCM channel (only B, D, F, G, L, S)"; return 1;
        }
    }
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

/* The envelope at instrument field `at` (u16 data offset or YM_NO_ENVELOPE) of kind 0 volume, 1 arpeggio, 2 pitch. */
static int check_envelope(YmImage* image, int at, int kind, int size) {
    const uint8_t* b = image->bytes;
    const int off = ym_u16(b + at);
    if (off == YM_NO_ENVELOPE) return 0;
    if (off + YM_ENV_HEADER > size) return fail(image, "envelope outside the data size", at);
    const uint8_t* e = b + YM_OFF_DATA + off;
    const int length = ym_u16(e), loop = ym_u16(e + 2), release = ym_u16(e + 4);
    if (length < 1 || length > YM_ENV_MAX_LENGTH) return fail(image, "envelope length outside 1..256", YM_OFF_DATA + off);
    if (off + YM_ENV_HEADER + length > size) return fail(image, "envelope outside the data size", YM_OFF_DATA + off);
    if (loop != YM_NO_ENVELOPE && loop >= length) return fail(image, "envelope loop past its end", YM_OFF_DATA + off + 2);
    if (release != YM_NO_ENVELOPE) {
        if (kind != 0) return fail(image, "release point on an arpeggio or pitch envelope", YM_OFF_DATA + off + 4);
        if (loop == YM_NO_ENVELOPE || release <= loop || release >= length)
            return fail(image, "volume release point needs a loop before it", YM_OFF_DATA + off + 4);
    }
    for (int i = 0; i < length; ++i) {
        const int v = (int8_t)e[YM_ENV_HEADER + i];
        if (kind == 0 ? v < 0 || v > 15 : v < -64 || v > 63)
            return fail(image, kind == 0 ? "volume envelope value outside 0..15" : "arpeggio or pitch envelope value outside -64..63",
                        YM_OFF_DATA + off + YM_ENV_HEADER + i);
    }
    return 0;
}

static int check_instrument(YmImage* image, int slot, int size) {
    const int base = YM_OFF_INSTRUMENTS + YM_INSTRUMENT_SIZE * (slot - 1);
    const uint8_t* p = image->bytes + base;
    const int ch3 = YM_OFF_CH3 + 8 * (slot - 1), keys = YM_OFF_PCM_KEYS + YM_PCM_KEYS * (slot - 1);
    const int flags = p[YM_I_FLAGS];
    if (flags != (YM_FLAG_DEFINED | YM_FLAG_PCM) && !zero(image->bytes + keys, YM_PCM_KEYS))
        return fail(image, "PCM key map of a slot that is not a PCM instrument", keys);
    if (flags != YM_FLAG_DEFINED && !zero(image->bytes + ch3, 8))
        return fail(image, "FM 3 operator offsets of a slot that is not an FM instrument", ch3);
    if (zero(p, YM_INSTRUMENT_SIZE)) return 0;
    if (slot > image->info.instruments) return fail(image, "instrument record past the instrument count", base);
    if (flags != YM_FLAG_DEFINED && flags != (YM_FLAG_DEFINED | YM_FLAG_PSG) && flags != (YM_FLAG_DEFINED | YM_FLAG_PCM))
        return fail(image, "instrument flags", base + YM_I_FLAGS);
    if (flags & YM_FLAG_PCM) {
        if (!zero(p + 1, YM_INSTRUMENT_SIZE - 1)) return fail(image, "PCM instrument reserved bytes 1..39", base + 1);
        for (int x = 0; x < YM_PCM_KEYS; ++x)
            if (image->bytes[keys + x] > image->info.pcm_variants) return fail(image, "PCM key maps a variant past the variant count", keys + x);
        return 0;
    }
    if (flags & YM_FLAG_PSG) {
        if (p[YM_I_PSG_NOISE] > 7) return fail(image, "PSG noise mode above 7", base + YM_I_PSG_NOISE);
        if (p[YM_I_PSG_FLAGS] & ~YM_PSG_PITCH_RELATIVE) return fail(image, "PSG flags: only bit 0", base + YM_I_PSG_FLAGS);
        if (p[3]) return fail(image, "PSG instrument reserved byte 3", base + 3);
        for (int k = 0; k < 3; ++k)
            if (check_envelope(image, base + YM_I_ENV_VOLUME + 2 * k, k, size)) return -1;
        if (!zero(p + 10, YM_I_SWEEP_START - 10)) return fail(image, "PSG instrument reserved bytes 10..31", base + 10);
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
        for (int k = 0; k < 4; ++k) {
            const int v = (int16_t)ym_u16(image->bytes + ch3 + 2 * k);
            if (v < -YM_CH3_LIMIT || v > YM_CH3_LIMIT) return fail(image, "FM 3 operator offset outside -3072..3072", ch3 + 2 * k);
        }
    }
    for (int i = YM_I_SWEEP_START; i <= YM_I_SWEEP_END; ++i) {
        const int v = (int8_t)p[i];
        if (v < -96 || v > 96) return fail(image, "sweep start/end outside -96..96 semitones", base + i);
    }
    if (!zero(p + 35, 5)) return fail(image, "instrument reserved bytes 35..39", base + 35);
    return 0;
}

/* The compiled variants: contiguous in id order from page 0 (the driver derives a length from the next start), filling
 * exactly the used bank; the unused bank, a half-rate variant's second half and the entries past the count zero. */
static int check_variants(YmImage* image) {
    const uint8_t* b = image->bytes;
    const YmInfo* info = &image->info;
    int next = 0;
    for (int id = 1; id <= YM_PCM_MAX_VARIANTS; ++id) {
        const int at = YM_OFF_PCM_VARIANTS + YM_PCM_VARIANT_SIZE * (id - 1);
        if (id > info->pcm_variants) {
            if (!zero(b + at, YM_PCM_VARIANT_SIZE)) return fail(image, "PCM variant entry past the variant count", at);
            continue;
        }
        const int start = ym_u16(b + at), pages = ym_u16(b + at + 2), flags = b[at + 4];
        if (start != next) return fail(image, "PCM variants must follow each other in id order", at);
        if (pages < 1) return fail(image, "PCM variant length must be at least one page", at + 2);
        if (flags & ~YM_PCM_HALF_RATE) return fail(image, "PCM variant flags: only bit 0", at + 4);
        if (!zero(b + at + 5, 3)) return fail(image, "PCM variant reserved bytes 5..7", at + 5);
        next = start + pages;
        if (next > info->pcm_pages) return fail(image, "PCM variant past the bank size", at + 2);
        if (flags & YM_PCM_HALF_RATE) {
            const size_t half = (size_t)pages * YM_PCM_ALIGN / 2;
            if (pages & 1) return fail(image, "half-rate PCM variant needs an even page count", at + 2);
            if (!zero(b + YM_OFF_PCM_BANK + (size_t)start * YM_PCM_ALIGN + half, half))
                return fail(image, "half-rate PCM variant needs its zeroed second half", YM_OFF_PCM_BANK + start * YM_PCM_ALIGN + (int)half);
        }
    }
    if (next != info->pcm_pages) return fail(image, "PCM bank size differs from its variants", YM_H_PCM_BANK_SIZE);
    const size_t used = (size_t)info->pcm_pages * YM_PCM_ALIGN;
    if (!zero(b + YM_OFF_PCM_BANK + used, YM_PCM_BANK_SIZE - used)) return fail(image, "PCM bank bytes past the bank size", YM_OFF_PCM_BANK + (int)used);
    return 0;
}

const uint8_t* ym_instrument(const YmImage* image, int slot, int kind) {
    if (slot < 1 || slot > image->info.instruments) return NULL;
    const uint8_t* rec = image->bytes + YM_OFF_INSTRUMENTS + YM_INSTRUMENT_SIZE * (slot - 1);
    const int flags = rec[YM_I_FLAGS];
    if (!(flags & YM_FLAG_DEFINED)) return NULL;
    const int is = flags & YM_FLAG_PCM ? YM_KIND_PCM : flags & YM_FLAG_PSG ? YM_KIND_PSG : YM_KIND_FM;
    return kind == YM_KIND_ANY || kind == is ? rec : NULL;
}

int ym_ch3_offset(const YmImage* image, int slot, int k) {
    return (int16_t)ym_u16(image->bytes + YM_OFF_CH3 + 8 * (slot - 1) + 2 * k);
}

int ym_pcm_variant(const YmImage* image, int slot, int note) {
    if (note < 0 || note >= YM_PCM_KEYS || !ym_instrument(image, slot, YM_KIND_PCM)) return 0;
    return image->bytes[YM_OFF_PCM_KEYS + YM_PCM_KEYS * (slot - 1) + note];
}

void ym_pcm_region(const YmImage* image, int id, int* start, int* pages, int* flags) {
    const uint8_t* e = image->bytes + YM_OFF_PCM_VARIANTS + YM_PCM_VARIANT_SIZE * (id - 1);
    *start = ym_u16(e);
    *pages = ym_u16(e + 2);
    *flags = e[4];
}

int ym_pcm_same_variant(const YmImage* a, int ida, const YmImage* b, int idb) {
    int sa, pa, fa, sb, pb, fb;
    ym_pcm_region(a, ida, &sa, &pa, &fa);
    ym_pcm_region(b, idb, &sb, &pb, &fb);
    return pa == pb && fa == fb &&
           !memcmp(a->bytes + YM_OFF_PCM_BANK + (size_t)sa * YM_PCM_ALIGN, b->bytes + YM_OFF_PCM_BANK + (size_t)sb * YM_PCM_ALIGN,
                   (size_t)pa * YM_PCM_ALIGN);
}

/* The offset table entry of (order, channel) and the data region it points into. */
static int pattern_entry(int order, int channel) {
    return channel < YM_TONAL_CHANNELS ? YM_OFF_ORDERS + 2 * (YM_TONAL_CHANNELS * order + channel)
                                       : YM_OFF_PCM_ORDERS + 2 * (YM_PCM_CHANNELS * order + channel - YM_CH_PCM);
}

static int region_base(int channel) { return channel < YM_TONAL_CHANNELS ? YM_OFF_DATA : YM_OFF_PCM_DATA; }

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
    info->data_size = ym_u16(b + YM_H_DATA_SIZE);
    if (info->data_size > (int)YM_DATA_CAPACITY) return fail(image, "data size above the region", YM_H_DATA_SIZE);
    info->fm3 = b[YM_H_FM3];
    if (info->fm3 > YM_FM3_SPECIAL) return fail(image, "FM 3 mode must be 0 (normal) or 1 (special)", YM_H_FM3);
    info->pcm_variants = b[YM_H_PCM_VARIANTS];
    if (info->pcm_variants > YM_PCM_MAX_VARIANTS) return fail(image, "PCM variant count above 123", YM_H_PCM_VARIANTS);
    info->pcm_pages = ym_u16(b + YM_H_PCM_BANK_SIZE);
    if (info->pcm_pages > YM_PCM_BANK_PAGES) return fail(image, "PCM bank size above 1 MiB", YM_H_PCM_BANK_SIZE);
    const uint32_t pcm_size = u32(b + YM_H_PCM_DATA_SIZE);
    if (pcm_size > YM_PCM_DATA_CAPACITY) return fail(image, "PCM data size above the region", YM_H_PCM_DATA_SIZE);
    info->pcm_data_size = (int)pcm_size;
    if (!zero(b + 24, YM_HEADER_SIZE - 24)) return fail(image, "header reserved bytes 24..63", 24);
    if (!zero(b + YM_OFF_DATA + info->data_size, YM_DATA_CAPACITY - (size_t)info->data_size))
        return fail(image, "tonal data bytes past the data size", YM_OFF_DATA + info->data_size);
    if (!zero(b + YM_OFF_PCM_VARIANTS + YM_PCM_VARIANT_SIZE * YM_PCM_MAX_VARIANTS,
              YM_OFF_PCM_DATA - YM_OFF_PCM_VARIANTS - YM_PCM_VARIANT_SIZE * YM_PCM_MAX_VARIANTS))
        return fail(image, "PCM variant table padding", YM_OFF_PCM_VARIANTS + YM_PCM_VARIANT_SIZE * YM_PCM_MAX_VARIANTS);
    if (!zero(b + YM_OFF_PCM_DATA + info->pcm_data_size, YM_PCM_DATA_CAPACITY - (size_t)info->pcm_data_size))
        return fail(image, "PCM data bytes past the PCM data size", YM_OFF_PCM_DATA + info->pcm_data_size);

    for (int o = info->orders; o < YM_MAX_ORDERS; ++o) {
        if (b[YM_OFF_ROWS + o]) return fail(image, "rows of an order past the order count", YM_OFF_ROWS + o);
        if (!zero(b + pattern_entry(o, 0), 2 * YM_TONAL_CHANNELS) || !zero(b + pattern_entry(o, YM_CH_PCM), 2 * YM_PCM_CHANNELS))
            return fail(image, "patterns of an order past the order count", pattern_entry(o, 0));
    }
    for (int o = 0; o < info->orders; ++o) info->rows[o] = b[YM_OFF_ROWS + o] ? b[YM_OFF_ROWS + o] : 256;
    if (check_variants(image)) return -1;
    for (int n = 1; n <= YM_MAX_INSTRUMENTS; ++n)
        if (check_instrument(image, n, info->data_size)) return -1;

    /* Patterns: every (order, channel) decodes rows(o) rows inside its region's used data. */
    int speed = info->speed;
    for (int o = 0; o < info->orders; ++o) {
        info->start_speed[o] = (uint8_t)speed;
        int offsets[YM_CHANNELS], runs[YM_CHANNELS];
        for (int c = 0; c < YM_CHANNELS; ++c) {
            const int size = c < YM_TONAL_CHANNELS ? info->data_size : info->pcm_data_size;
            offsets[c] = ym_u16(b + pattern_entry(o, c));
            runs[c] = 0;
            if (offsets[c] >= size) return fail(image, "pattern offset past the data size", pattern_entry(o, c));
        }
        for (int r = 0; r < info->rows[o]; ++r) {
            for (int c = 0; c < YM_CHANNELS; ++c) {
                if (runs[c]) { --runs[c]; continue; }
                const int size = c < YM_TONAL_CHANNELS ? info->data_size : info->pcm_data_size;
                const int base = region_base(c), kind = ym_channel_kind(c);
                int at = offsets[c];
                if (at >= size) return fail(image, "pattern runs past the data size", base + at);
                const int m = b[base + at++];
                if (m & 0x80) { runs[c] = m & 0x7F; offsets[c] = at; continue; }
                const int effects = (m >> 2) & 7;
                if (!m || (m & 0x60) || effects > YM_MAX_EFFECTS) return fail(image, "row mask byte", base + at - 1);
                const int need = (m & 1) + ((m >> 1) & 1) + 2 * effects;
                if (at + need > size) return fail(image, "row runs past the data size", base + at);
                if (m & 1) {
                    const int note = b[base + at];
                    if (note >= YM_NOTES && note != YM_NOTE_OFF) return fail(image, "note byte", base + at);
                    ++at;
                }
                if (m & 2) {
                    const int slot = b[base + at];
                    if (!ym_instrument(image, slot, YM_KIND_ANY)) return fail(image, "row names an instrument the image does not define", base + at);
                    if (!ym_instrument(image, slot, kind)) {
                        const char* why = "FM or PSG instrument on a PCM channel";
                        if (kind != YM_KIND_PCM) why = ym_instrument(image, slot, YM_KIND_PCM) ? "PCM instrument on an FM or PSG channel" : kind == YM_KIND_PSG ? "FM instrument on a PSG channel" : "PSG instrument on an FM channel";
                        return fail(image, why, base + at);
                    }
                    ++at;
                }
                for (int e = 0; e < effects; ++e, at += 2) {
                    const int code = b[base + at], param = b[base + at + 1];
                    const char* why = "effect code above Z";
                    if (code > 35 || effect_problem(info, c, code, param, &why)) return fail(image, why, base + at);
                    if (code == YM_FX_SPEED) speed = param; /* rows, then channels, then columns: the last F wins */
                }
                offsets[c] = at;
            }
        }
    }
    info->loaded = YM_LOADED;
    return 0;
}

void ym_copy_image(YmImage* dst, const YmImage* src) {
    const YmInfo* info = &src->info;
    memcpy(dst->bytes, src->bytes, (size_t)YM_OFF_PCM_DATA + (size_t)info->pcm_data_size);
    memcpy(dst->bytes + YM_OFF_PCM_BANK, src->bytes + YM_OFF_PCM_BANK, (size_t)info->pcm_pages * YM_PCM_ALIGN);
    dst->info = src->info;
}

void ym_next_row(const uint8_t* bytes, YmCursor* cursor, YmRow* row) {
    row->note = -1;
    row->instrument = 0;
    row->effects = 0;
    if (cursor->run) {
        --cursor->run;
        return;
    }
    const uint8_t* p = bytes + cursor->at;
    int at = 0;
    const int m = p[at++];
    if (m & 0x80) {
        cursor->run = m & 0x7F;
        cursor->at += 1;
        return;
    }
    if (m & 1) row->note = p[at++];
    if (m & 2) row->instrument = p[at++];
    row->effects = (m >> 2) & 7;
    for (int e = 0; e < row->effects; ++e) {
        row->code[e] = p[at++];
        row->param[e] = p[at++];
    }
    cursor->at += at;
}

YmCursor ym_pattern(const YmImage* image, int order, int channel) {
    YmCursor c = {region_base(channel) + ym_u16(image->bytes + pattern_entry(order, channel)), 0};
    return c;
}

void ym_read_row(const YmImage* image, int order, int channel, int row, YmRow* out) {
    YmCursor c = ym_pattern(image, order, channel);
    for (int r = 0; r < row; ++r) ym_next_row(image->bytes, &c, out);
    ym_next_row(image->bytes, &c, out);
}
