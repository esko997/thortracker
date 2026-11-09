/* AY image validation and row decoding (ay_image.h; layout in docs/AUDIO_ENGINE.md "AY-3-8910 / YM2149"). Every
 * rejection reason is a string literal in this file: tools/audio/prepare_ay.py extracts them in source order into
 * AyPlayerImage.LOADER_PROBLEMS, and tools/audio/ay/ay_ref.py checks the same rules in the same order. */
#include "ay_image.h"

#include <string.h>

const AyVariantSpec ay_variants[AY_VARIANTS] = {
    {AY_ENGINE_ID_ZX128, AY_GD3_SYSTEM_ZX128, AY_MASTER_HZ_ZX128, AY_DIV_ZX128, AY_FRAME_DIV_ZX128, AY_VGM_CLOCK_ZX128,
     AY_VGM_RATE_ZX128, AY_IO_BITS_ZX128, AY_VGM_TYPE_ZX128, 0, AY_STEREO_ZX128},
    {AY_ENGINE_ID_MSX, AY_GD3_SYSTEM_MSX, AY_MASTER_HZ_MSX, AY_DIV_MSX, AY_FRAME_DIV_MSX, AY_VGM_CLOCK_MSX, AY_VGM_RATE_MSX,
     AY_IO_BITS_MSX, AY_VGM_TYPE_MSX, 0, AY_STEREO_MSX},
    {AY_ENGINE_ID_ATARI_ST, AY_GD3_SYSTEM_ATARI_ST, AY_MASTER_HZ_ATARI_ST, AY_DIV_ATARI_ST, AY_FRAME_DIV_ATARI_ST,
     AY_VGM_CLOCK_ATARI_ST, AY_VGM_RATE_ATARI_ST, AY_IO_BITS_ATARI_ST, AY_VGM_TYPE_ATARI_ST, 1, AY_STEREO_ATARI_ST},
    {AY_ENGINE_ID_CPC, AY_GD3_SYSTEM_CPC, AY_MASTER_HZ_CPC, AY_DIV_CPC, AY_FRAME_DIV_CPC, AY_VGM_CLOCK_CPC, AY_VGM_RATE_CPC,
     AY_IO_BITS_CPC, AY_VGM_TYPE_CPC, 0, AY_STEREO_CPC},
};

static int fail(AyImage* image, const char* problem, int offset) {
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

/* 0 if `code` with `param` may stand in a row, else 1 with *why. */
static int effect_problem(const AyInfo* info, int code, int param, const char** why) {
    switch (code) {
    case AY_FX_ARPEGGIO: case AY_FX_SLIDE_UP: case AY_FX_SLIDE_DOWN: case AY_FX_PORTAMENTO: case AY_FX_VIBRATO:
    case AY_FX_VOLUME_SLIDE: case AY_FX_BREAK: case AY_FX_DELAY: case AY_FX_ENV_HIGH: case AY_FX_ENV_LOW:
    case AY_FX_FINE: case AY_FX_CUT:
        return 0;
    case AY_FX_JUMP:
        *why = "Bxx jumps past the last order";
        return param >= info->orders;
    case AY_FX_VOLUME:
        *why = "Cxx volume above 0F";
        return param > 0x0F;
    case AY_FX_ENV_SHAPE:
        *why = "Exx shape must be 00 or 08..0F";
        return param != 0 && (param < 0x08 || param > 0x0F);
    case AY_FX_SPEED:
        *why = "F00: speed must be 1..FF";
        return param == 0;
    case AY_FX_ENV_AUTO:
        *why = "Jxx octave must be 00..07 or F8..FF";
        return param > 0x07 && param < 0xF8;
    case AY_FX_MIXER:
        *why = "Vxx mixer above 03";
        return param > 0x03;
    case AY_FX_NOISE:
        *why = "Wxx noise period above 1F";
        return param > 0x1F;
    default:
        *why = "unknown effect code";
        return 1;
    }
}

enum { KIND_VOLUME = 0, KIND_ARPEGGIO, KIND_PITCH, KIND_MIXER, KIND_NOISE };

/* The envelope at instrument field `at` (u16 data offset or AY_NO_ENVELOPE) of kind KIND_*. */
static int check_envelope(AyImage* image, int at, int kind, int size) {
    const uint8_t* b = image->bytes;
    const int off = ay_u16(b + at);
    if (off == AY_NO_ENVELOPE) return 0;
    if (off + AY_ENV_HEADER > size) return fail(image, "envelope outside the data size", at);
    const uint8_t* e = b + AY_OFF_DATA + off;
    const int length = ay_u16(e), loop = ay_u16(e + 2), release = ay_u16(e + 4);
    if (length < 1 || length > AY_ENV_MAX_LENGTH) return fail(image, "envelope length outside 1..256", AY_OFF_DATA + off);
    if (off + AY_ENV_HEADER + length > size) return fail(image, "envelope outside the data size", AY_OFF_DATA + off);
    if (loop != AY_NO_ENVELOPE && loop >= length) return fail(image, "envelope loop past its end", AY_OFF_DATA + off + 2);
    if (release != AY_NO_ENVELOPE) {
        if (kind != KIND_VOLUME) return fail(image, "release point on a non-volume envelope", AY_OFF_DATA + off + 4);
        if (loop == AY_NO_ENVELOPE || release <= loop || release >= length)
            return fail(image, "volume release point needs a loop before it", AY_OFF_DATA + off + 4);
    }
    for (int i = 0; i < length; ++i) {
        const int v = (int8_t)e[AY_ENV_HEADER + i];
        const int at_value = AY_OFF_DATA + off + AY_ENV_HEADER + i;
        switch (kind) {
        case KIND_VOLUME:
            if (v < 0 || v > 15) return fail(image, "volume envelope value outside 0..15", at_value);
            break;
        case KIND_ARPEGGIO:
        case KIND_PITCH:
            if (v < -64 || v > 63) return fail(image, "arpeggio or pitch envelope value outside -64..63", at_value);
            break;
        case KIND_MIXER:
            if (v < 0 || v > 3) return fail(image, "mixer envelope value outside 0..3", at_value);
            break;
        default:
            if (v < 0 || v > AY_NOISE_PERIOD_MAX) return fail(image, "noise envelope value outside 0..31", at_value);
            break;
        }
    }
    return 0;
}

static int check_instrument(AyImage* image, int slot, int size) {
    const int base = AY_OFF_INSTRUMENTS + AY_INSTRUMENT_SIZE * (slot - 1);
    const uint8_t* p = image->bytes + base;
    if (zero(p, AY_INSTRUMENT_SIZE)) return 0;
    if (slot > image->info.instruments) return fail(image, "instrument record past the instrument count", base);
    const int flags = p[AY_I_FLAGS];
    if ((flags & 0x78) || !(flags & AY_FLAG_DEFINED)) return fail(image, "instrument flags", base + AY_I_FLAGS);
    const int autop = flags & AY_FLAG_AUTO_PERIOD;
    if ((flags & AY_FLAG_SYNC) && !autop) return fail(image, "sync needs the auto envelope period", base + AY_I_FLAGS);
    const int shape = p[AY_I_SHAPE];
    if (shape != 0 && (shape < 8 || shape > 15)) return fail(image, "envelope shape must be 0 or 8..15", base + AY_I_SHAPE);
    if (autop) {
        const int j = (int8_t)p[AY_I_OCTAVE];
        if (j < AY_OCTAVE_MIN || j > AY_OCTAVE_MAX) return fail(image, "auto octave outside -8..7", base + AY_I_OCTAVE);
    } else if (p[AY_I_OCTAVE]) {
        return fail(image, "auto octave on a manual-period instrument", base + AY_I_OCTAVE);
    }
    if (p[3]) return fail(image, "instrument reserved byte 3", base + 3);
    if (autop && ay_u16(p + AY_I_PERIOD)) return fail(image, "manual period on an auto-period instrument", base + AY_I_PERIOD);
    for (int k = KIND_VOLUME; k <= KIND_NOISE; ++k)
        if (check_envelope(image, base + AY_I_ENV_VOLUME + 2 * k, k, size)) return -1;
    for (int i = AY_I_SWEEP_START; i <= AY_I_SWEEP_END; ++i) {
        const int v = (int8_t)p[i];
        if (v < -AY_SWEEP_LIMIT || v > AY_SWEEP_LIMIT) return fail(image, "sweep start/end outside -96..96 semitones", base + i);
    }
    if (!zero(p + 19, AY_INSTRUMENT_SIZE - 19)) return fail(image, "instrument reserved bytes 19..31", base + 19);
    return 0;
}

const uint8_t* ay_instrument(const AyImage* image, int slot) {
    if (slot < 1 || slot > image->info.instruments) return NULL;
    const uint8_t* rec = image->bytes + AY_OFF_INSTRUMENTS + AY_INSTRUMENT_SIZE * (slot - 1);
    return (rec[AY_I_FLAGS] & AY_FLAG_DEFINED) ? rec : NULL;
}

int ay_load_image(AyImage* image, int variant) {
    const uint8_t* b = image->bytes;
    AyInfo* info = &image->info;
    memset(info, 0, sizeof *info);
    info->problem_offset = -1;
    if (memcmp(b, AY_MAGIC, 4)) return fail(image, "not an AY image (magic)", AY_H_MAGIC);
    if (b[AY_H_LAYOUT] != AY_LAYOUT) return fail(image, "unknown layout", AY_H_LAYOUT);
    info->variant = b[AY_H_VARIANT];
    if (info->variant >= AY_VARIANTS)
        return fail(image, "variant must be 0 (ZX Spectrum 128), 1 (MSX), 2 (Atari ST) or 3 (Amstrad CPC)", AY_H_VARIANT);
    if (variant >= 0 && info->variant != variant) return fail(image, "image is for another AY variant", AY_H_VARIANT);
    info->speed = b[AY_H_SPEED];
    if (!info->speed) return fail(image, "speed must be 1..255", AY_H_SPEED);
    if (b[7]) return fail(image, "header reserved byte 7", 7);
    info->orders = ay_u16(b + AY_H_ORDERS);
    if (info->orders < 1 || info->orders > AY_MAX_ORDERS) return fail(image, "order count must be 1..256", AY_H_ORDERS);
    info->loop = ay_u16(b + AY_H_LOOP);
    if (info->loop >= info->orders) return fail(image, "loop order past the last order", AY_H_LOOP);
    info->instruments = b[AY_H_INSTRUMENTS];
    if (info->instruments > AY_MAX_INSTRUMENTS) return fail(image, "instrument count above 64", AY_H_INSTRUMENTS);
    if (b[13]) return fail(image, "header reserved byte 13", 13);
    const int size = ay_u16(b + AY_H_DATA_SIZE);
    if (size > AY_DATA_CAPACITY) return fail(image, "data size above the region", AY_H_DATA_SIZE);
    info->data_size = size;
    if (!zero(b + 16, AY_HEADER_SIZE - 16)) return fail(image, "header reserved bytes 16..63", 16);

    for (int o = info->orders; o < AY_MAX_ORDERS; ++o) {
        if (b[AY_OFF_ROWS + o]) return fail(image, "rows of an order past the order count", AY_OFF_ROWS + o);
        if (!zero(b + AY_OFF_ORDERS + 2 * AY_CHANNELS * o, 2 * AY_CHANNELS))
            return fail(image, "patterns of an order past the order count", AY_OFF_ORDERS + 2 * AY_CHANNELS * o);
    }
    if (!zero(b + AY_OFF_RESERVED, AY_OFF_INSTRUMENTS - AY_OFF_RESERVED))
        return fail(image, "reserved bytes 1856..2047", AY_OFF_RESERVED);
    for (int o = 0; o < info->orders; ++o) info->rows[o] = b[AY_OFF_ROWS + o] ? b[AY_OFF_ROWS + o] : 256;
    for (int n = 1; n <= AY_MAX_INSTRUMENTS; ++n)
        if (check_instrument(image, n, size)) return -1;

    /* Patterns: every (order, channel) decodes rows(o) rows inside the used data. */
    int speed = info->speed;
    for (int o = 0; o < info->orders; ++o) {
        info->start_speed[o] = (uint8_t)speed;
        int offsets[AY_CHANNELS], runs[AY_CHANNELS];
        for (int c = 0; c < AY_CHANNELS; ++c) {
            offsets[c] = ay_u16(b + AY_OFF_ORDERS + 2 * (AY_CHANNELS * o + c));
            runs[c] = 0;
            if (offsets[c] >= size) return fail(image, "pattern offset past the data size", AY_OFF_ORDERS + 2 * (AY_CHANNELS * o + c));
        }
        for (int r = 0; r < info->rows[o]; ++r) {
            for (int c = 0; c < AY_CHANNELS; ++c) {
                if (runs[c]) { --runs[c]; continue; }
                int at = offsets[c];
                if (at >= size) return fail(image, "pattern runs past the data size", AY_OFF_DATA + at);
                const int m = b[AY_OFF_DATA + at++];
                if (m & AY_RUN) { runs[c] = m & 0x7F; offsets[c] = at; continue; }
                const int effects = (m >> AY_MASK_EFFECT_SHIFT) & 7;
                if (!m || (m & 0x60) || effects > AY_MAX_EFFECTS) return fail(image, "row mask byte", AY_OFF_DATA + at - 1);
                const int need = (m & AY_MASK_NOTE) + ((m >> 1) & 1) + 2 * effects;
                if (at + need > size) return fail(image, "row runs past the data size", AY_OFF_DATA + at);
                if (m & AY_MASK_NOTE) {
                    const int note = b[AY_OFF_DATA + at];
                    if (note >= AY_NOTES && note != AY_NOTE_OFF) return fail(image, "note byte", AY_OFF_DATA + at);
                    ++at;
                }
                if (m & AY_MASK_INSTRUMENT) {
                    if (!ay_instrument(image, b[AY_OFF_DATA + at]))
                        return fail(image, "row names an instrument the image does not define", AY_OFF_DATA + at);
                    ++at;
                }
                for (int e = 0; e < effects; ++e, at += 2) {
                    const int code = b[AY_OFF_DATA + at], param = b[AY_OFF_DATA + at + 1];
                    const char* why = "effect code above Z";
                    if (code > 35 || effect_problem(info, code, param, &why)) return fail(image, why, AY_OFF_DATA + at);
                    if (code == AY_FX_SPEED) speed = param; /* rows, then channels, then columns: the last F wins */
                }
                offsets[c] = at;
            }
        }
    }
    info->loaded = AY_LOADED;
    return 0;
}

void ay_next_row(const uint8_t* bytes, AyCursor* cursor, AyRow* row) {
    row->note = -1;
    row->instrument = 0;
    row->effects = 0;
    if (cursor->run) {
        --cursor->run;
        return;
    }
    const uint8_t* p = bytes + AY_OFF_DATA + cursor->offset;
    int at = 0;
    const int m = p[at++];
    if (m & AY_RUN) {
        cursor->run = m & 0x7F;
        cursor->offset += 1;
        return;
    }
    if (m & AY_MASK_NOTE) row->note = p[at++];
    if (m & AY_MASK_INSTRUMENT) row->instrument = p[at++];
    row->effects = (m >> AY_MASK_EFFECT_SHIFT) & 7;
    for (int e = 0; e < row->effects; ++e) {
        row->code[e] = p[at++];
        row->param[e] = p[at++];
    }
    cursor->offset += at;
}

AyCursor ay_pattern(const AyImage* image, int order, int channel) {
    AyCursor c = {ay_u16(image->bytes + AY_OFF_ORDERS + 2 * (AY_CHANNELS * order + channel)), 0};
    return c;
}

void ay_read_row(const AyImage* image, int order, int channel, int row, AyRow* out) {
    AyCursor c = ay_pattern(image, order, channel);
    for (int r = 0; r < row; ++r) ay_next_row(image->bytes, &c, out);
    ay_next_row(image->bytes, &c, out);
}
