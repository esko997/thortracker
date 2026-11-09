/* VIC-20 / C16-Plus/4 image validation and row decoding (cbm_image.h; layout in docs/AUDIO_ENGINE.md "VIC-20 and
 * C16/Plus/4", "Image layout"). Checks run in this order: header, reserved table bytes, the order tables, sample
 * entries, instruments, patterns (the order of tools/audio/cbm/cbm_ref.py's loader; the reason strings are ours). */
#include "cbm_image.h"

#include <string.h>

const uint8_t CB_LANE_RANGE[2][2][CB_MAX_CHANNELS][2] = {
    /* VIC: Bass, Alto, Soprano, Noise (rate index) */
    {{{13, 52}, {25, 64}, {37, 76}, {0, 127}},   /* PAL */
     {{11, 48}, {23, 60}, {35, 72}, {0, 127}}},  /* NTSC */
    /* TED: Voice 1, Voice 2, DAC */
    {{{33, 108}, {33, 127}, {24, 108}, {0, 0}},
     {{33, 108}, {33, 127}, {24, 108}, {0, 0}}},
};

static int fail(CbmImage* image, const char* problem, int offset) {
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

static int channels_of(int chip) { return chip == CB_CHIP_VIC ? CB_VIC_CHANNELS : CB_TED_CHANNELS; }
static int max_volume(int chip) { return chip == CB_CHIP_VIC ? CB_VIC_MAX_VOLUME : CB_TED_MAX_VOLUME; }

int cb_lane_range(int chip, int video, int channel, int* lo, int* hi) {
    if (chip < 0 || chip > 1 || video < 0 || video > 1 || channel < 0 || channel >= channels_of(chip)) return -1;
    *lo = CB_LANE_RANGE[chip][video][channel][0];
    *hi = CB_LANE_RANGE[chip][video][channel][1];
    return 0;
}

static const uint8_t* record(const CbmImage* image, int slot) {
    if (slot < 1 || slot > image->info.instruments) return NULL;
    const uint8_t* rec = image->bytes + CB_OFF_INSTRUMENTS + CB_INSTRUMENT_SIZE * (slot - 1);
    return (rec[CB_I_FLAGS] & CB_FLAG_DEFINED) ? rec : NULL;
}

/* A tone instrument that can play noise: wave byte 2, or a wave envelope value 2. */
static int noise_capable(const CbmImage* image, const uint8_t* rec) {
    if (rec[CB_I_WAVE] == CB_WAVE_NOISE) return 1;
    const uint8_t* e = rec + CB_I_ENVELOPES + CB_ENVELOPE_BYTES * CB_ENV_WAVE;
    const int offset = cb_u16(e), length = e[2];
    for (int i = 0; i < length; ++i)
        if (image->bytes[CB_OFF_DATA + offset + i] == CB_WAVE_NOISE) return 1;
    return 0;
}

const uint8_t* cb_allowed(const CbmImage* image, int slot, int channel) {
    const uint8_t* rec = record(image, slot);
    if (!rec || channel < 0 || channel >= image->info.channels) return NULL;
    const int kind = rec[CB_I_FLAGS] & CB_KIND_MASK;
    if (image->info.chip == CB_CHIP_VIC) return kind == CB_KIND_TONE ? rec : NULL;
    if (channel == CB_TED_DAC_CHANNEL) return kind == CB_KIND_SAMPLE ? rec : NULL;
    if (kind != CB_KIND_TONE) return NULL;
    return channel == CB_TED_NOISE_CHANNEL || !noise_capable(image, rec) ? rec : NULL;
}

static int effect_problem(const CbInfo* info, int channel, int code, int param, const char** why) {
    const int ted = info->chip == CB_CHIP_TED;
    switch (code) {
    case CB_FX_ARPEGGIO: case CB_FX_SLIDE_UP: case CB_FX_SLIDE_DOWN: case CB_FX_PORTAMENTO: case CB_FX_VIBRATO:
    case CB_FX_VOLUME_SLIDE: case CB_FX_BREAK: case CB_FX_DELAY: case CB_FX_FINE: case CB_FX_CUT:
        return 0;
    case CB_FX_JUMP:
        *why = "Bxx jumps past the last order";
        return param >= info->orders;
    case CB_FX_SPEED:
        *why = "F00: speed must be 1..FF";
        return param == 0;
    case CB_FX_VOLUME:
        *why = "Vxx above the chip's master volume";
        return param > max_volume(info->chip);
    case CB_FX_TIMER:
        *why = "Txx (DAC rate) only on the C16/Plus/4";
        return !ted;
    case CB_FX_WAVE:
        if (!ted) {
            *why = "Wxx (wave) only on the C16/Plus/4";
            return 1;
        }
        if (channel == CB_TED_DAC_CHANNEL) {
            *why = "Wxx on the DAC lane";
            return 1;
        }
        if (param > CB_WAVE_NOISE) {
            *why = "Wxx above 02";
            return 1;
        }
        *why = "W02 (noise) only on voice 2";
        return param == CB_WAVE_NOISE && channel != CB_TED_NOISE_CHANNEL;
    default:
        *why = "unknown effect code";
        return 1;
    }
}

/* Envelope k of the instrument at `base`: fields, range inside the data and values in range. */
static int check_envelope(CbmImage* image, int base, int kind, int k, uint32_t size) {
    const uint8_t* b = image->bytes;
    const int at = base + CB_I_ENVELOPES + CB_ENVELOPE_BYTES * k;
    const int offset = cb_u16(b + at), length = b[at + 2], loop = b[at + 3], release = b[at + 4];
    if (b[at + 5]) return fail(image, "envelope reserved byte", at + 5);
    if (!length) {
        if (offset || loop != CB_ENV_NONE || release != CB_ENV_NONE)
            return fail(image, "empty envelope: offset 0, loop and release FF", at);
        return 0;
    }
    if (kind == CB_KIND_SAMPLE && (k == CB_ENV_VOLUME || k == CB_ENV_WAVE))
        return fail(image, "volume or wave envelope on a sample instrument", at + 2);
    if (loop != CB_ENV_NONE && loop >= length) return fail(image, "envelope loop past its length", at + 3);
    if (release != CB_ENV_NONE && (release < 1 || release >= length))
        return fail(image, "envelope release must be 1..length - 1", at + 4);
    if (loop != CB_ENV_NONE && release != CB_ENV_NONE && release <= loop)
        return fail(image, "envelope release must follow its loop", at + 4);
    if ((uint32_t)offset + (uint32_t)length > size) return fail(image, "envelope runs past the data size", at);
    const int wave_max = image->info.chip == CB_CHIP_VIC ? CB_WAVE_SQUARE : CB_WAVE_NOISE;
    for (int i = 0; i < length; ++i) {
        const int pos = CB_OFF_DATA + offset + i, v = b[pos], s = (int8_t)b[pos];
        if (k == CB_ENV_VOLUME && v > max_volume(image->info.chip))
            return fail(image, "volume envelope value above the chip's master volume", pos);
        if (k == CB_ENV_ARPEGGIO && (s < -64 || s > 63)) return fail(image, "arpeggio envelope value outside -64..63", pos);
        if (k == CB_ENV_WAVE && v > wave_max) return fail(image, "wave envelope value above the chip's waves", pos);
    }
    return 0;
}

static int check_instrument(CbmImage* image, int slot, uint32_t size) {
    const uint8_t* b = image->bytes;
    const int base = CB_OFF_INSTRUMENTS + CB_INSTRUMENT_SIZE * (slot - 1);
    const uint8_t* p = b + base;
    const CbInfo* info = &image->info;
    if (zero(p, CB_INSTRUMENT_SIZE)) return 0;
    if (slot > info->instruments) return fail(image, "instrument record past the instrument count", base);
    const int flags = p[CB_I_FLAGS], kind = flags & CB_KIND_MASK;
    if (!(flags & CB_FLAG_DEFINED) || (flags & ~(CB_FLAG_DEFINED | CB_FLAG_RELATIVE | CB_KIND_MASK)))
        return fail(image, "instrument flags", base + CB_I_FLAGS);
    if (kind > CB_KIND_SAMPLE || (kind == CB_KIND_SAMPLE && info->chip == CB_CHIP_VIC))
        return fail(image, "instrument kind (sample instruments only on the C16/Plus/4)", base + CB_I_FLAGS);
    const int loop_start = cb_u16(p + CB_I_LOOP_START);
    if (kind == CB_KIND_TONE) {
        const int wave_max = info->chip == CB_CHIP_VIC ? CB_WAVE_SQUARE : CB_WAVE_NOISE;
        if (p[CB_I_WAVE] < CB_WAVE_SQUARE || p[CB_I_WAVE] > wave_max)
            return fail(image, "tone instrument wave (VIC: 1; TED: 1 or 2)", base + CB_I_WAVE);
        if (p[CB_I_SAMPLE] || p[CB_I_ROOT] || p[CB_I_LOOP] || loop_start)
            return fail(image, "sample fields of a tone instrument", base + CB_I_SAMPLE);
    } else {
        if (p[CB_I_WAVE]) return fail(image, "wave byte of a sample instrument", base + CB_I_WAVE);
        if (p[CB_I_SAMPLE] >= info->samples)
            return fail(image, "sample instrument names a sample the image does not have", base + CB_I_SAMPLE);
        if (p[CB_I_ROOT] < CB_ROOT_MIN || p[CB_I_ROOT] > CB_ROOT_MAX)
            return fail(image, "sample root note outside 24..108", base + CB_I_ROOT);
        if (p[CB_I_LOOP] > 1) return fail(image, "sample loop flag must be 0 or 1", base + CB_I_LOOP);
        const uint32_t length = cb_u32(b + CB_OFF_SAMPLES + CB_SAMPLE_ENTRY_SIZE * p[CB_I_SAMPLE] + 4);
        if (p[CB_I_LOOP] ? (uint32_t)loop_start >= length : loop_start != 0)
            return fail(image, "sample loop start past the sample (or set without loop)", base + CB_I_LOOP_START);
    }
    if (p[5]) return fail(image, "instrument reserved byte 5", base + 5);
    for (int k = 0; k < CB_ENVELOPE_COUNT; ++k)
        if (check_envelope(image, base, kind, k, size)) return -1;
    return 0;
}

int cb_load_image(CbmImage* image, int chip) {
    const uint8_t* b = image->bytes;
    CbInfo* info = &image->info;
    memset(info, 0, sizeof *info);
    info->problem_offset = -1;
    if (memcmp(b, CB_MAGIC, 4)) return fail(image, "not a VIC-20 / C16 image (magic)", CB_H_MAGIC);
    if (b[CB_H_LAYOUT] != CB_LAYOUT) return fail(image, "unknown layout", CB_H_LAYOUT);
    info->chip = b[CB_H_CHIP];
    if (info->chip > CB_CHIP_TED || info->chip != chip) return fail(image, "image for another chip", CB_H_CHIP);
    info->channels = channels_of(info->chip);
    info->video = b[CB_H_VIDEO];
    if (info->video > CB_VIDEO_NTSC) return fail(image, "video must be PAL (0) or NTSC (1)", CB_H_VIDEO);
    info->multispeed = b[CB_H_MULTISPEED];
    if (info->multispeed < 1 || info->multispeed > CB_MAX_MULTISPEED)
        return fail(image, "multispeed must be 1..4", CB_H_MULTISPEED);
    info->speed = b[CB_H_SPEED];
    if (!info->speed) return fail(image, "speed must be 1..255", CB_H_SPEED);
    info->instruments = b[CB_H_INSTRUMENTS];
    if (info->instruments > CB_MAX_INSTRUMENTS) return fail(image, "instrument count above 64", CB_H_INSTRUMENTS);
    info->orders = cb_u16(b + CB_H_ORDERS);
    if (info->orders < 1 || info->orders > CB_MAX_ORDERS) return fail(image, "order count must be 1..256", CB_H_ORDERS);
    info->loop = cb_u16(b + CB_H_LOOP);
    if (info->loop >= info->orders) return fail(image, "loop order past the last order", CB_H_LOOP);
    info->samples = b[CB_H_SAMPLES];
    if (info->samples > CB_MAX_SAMPLES || (info->chip == CB_CHIP_VIC && info->samples))
        return fail(image, "sample count (VIC: 0, TED: 0..64)", CB_H_SAMPLES);
    if (b[CB_H_VOLUME] > max_volume(info->chip)) return fail(image, "master volume above the chip's", CB_H_VOLUME);
    if (info->chip == CB_CHIP_VIC && b[CB_H_TIMER]) return fail(image, "DAC timer on the VIC", CB_H_TIMER);
    if (!zero(b + 17, 3)) return fail(image, "header reserved bytes 17..19", 17);
    const uint32_t size = cb_u32(b + CB_H_DATA_SIZE);
    if (size > CB_DATA_CAPACITY) return fail(image, "data size above the region", CB_H_DATA_SIZE);
    if (!zero(b + 24, CB_HEADER_SIZE - 24)) return fail(image, "header reserved bytes 24..63", 24);
    if (!zero(b + CB_OFF_RESERVED, CB_OFF_DATA - CB_OFF_RESERVED))
        return fail(image, "reserved bytes before the data region", CB_OFF_RESERVED);

    for (int o = 0; o < CB_MAX_ORDERS; ++o) {
        const int at = CB_OFF_ORDERS + 2 * CB_MAX_CHANNELS * o;
        if (o < info->orders) {
            if (info->chip == CB_CHIP_TED && cb_u16(b + at + 6))
                return fail(image, "TED channel 3 order entry", at + 6);
            info->rows[o] = b[CB_OFF_ROWS + o] ? b[CB_OFF_ROWS + o] : 256;
            continue;
        }
        if (b[CB_OFF_ROWS + o]) return fail(image, "rows of an order past the order count", CB_OFF_ROWS + o);
        if (!zero(b + at, 2 * CB_MAX_CHANNELS)) return fail(image, "patterns of an order past the order count", at);
    }
    for (int s = 0; s < CB_MAX_SAMPLES; ++s) {
        const int at = CB_OFF_SAMPLES + CB_SAMPLE_ENTRY_SIZE * s;
        if (s >= info->samples) {
            if (!zero(b + at, CB_SAMPLE_ENTRY_SIZE)) return fail(image, "sample entry past the sample count", at);
            continue;
        }
        const uint32_t offset = cb_u32(b + at), length = cb_u32(b + at + 4);
        if (length < 1 || length > CB_MAX_SAMPLE_FRAMES) return fail(image, "sample length must be 1..65535 frames", at + 4);
        if (offset > size || length > size - offset) return fail(image, "sample runs past the data size", at);
        for (uint32_t i = 0; i < length; ++i)
            if (b[CB_OFF_DATA + offset + i] > CB_DAC_MAX)
                return fail(image, "sample frame above 8", (int)(CB_OFF_DATA + offset + i));
    }
    for (int n = 1; n <= CB_MAX_INSTRUMENTS; ++n)
        if (check_instrument(image, n, size)) return -1;

    /* Patterns: every (order, channel) decodes rows(o) rows inside the used data. */
    int speed = info->speed;
    for (int o = 0; o < info->orders; ++o) {
        info->start_speed[o] = (uint8_t)speed;
        int offsets[CB_MAX_CHANNELS], runs[CB_MAX_CHANNELS];
        for (int c = 0; c < info->channels; ++c) {
            const int at = CB_OFF_ORDERS + 2 * (CB_MAX_CHANNELS * o + c);
            offsets[c] = cb_u16(b + at);
            runs[c] = 0;
            if ((uint32_t)offsets[c] >= size) return fail(image, "pattern offset past the data size", at);
        }
        for (int r = 0; r < info->rows[o]; ++r) {
            for (int c = 0; c < info->channels; ++c) {
                if (runs[c]) {
                    --runs[c];
                    continue;
                }
                int at = offsets[c];
                if ((uint32_t)at >= size) return fail(image, "pattern runs past the data size", CB_OFF_DATA + at);
                const int m = b[CB_OFF_DATA + at++];
                if (m & CB_RUN) {
                    runs[c] = m & 0x7F;
                    offsets[c] = at;
                    continue;
                }
                const int effects = (m >> CB_MASK_EFFECT_SHIFT) & 3;
                if (!m || (m & 0x70) || effects > CB_MAX_EFFECTS) return fail(image, "row mask byte", CB_OFF_DATA + at - 1);
                const int need = (m & CB_MASK_NOTE) + ((m >> 1) & 1) + 2 * effects;
                if ((uint32_t)(at + need) > size) return fail(image, "row runs past the data size", CB_OFF_DATA + at);
                if (m & CB_MASK_NOTE) {
                    const int note = b[CB_OFF_DATA + at];
                    const int lo = CB_LANE_RANGE[info->chip][info->video][c][0];
                    const int hi = CB_LANE_RANGE[info->chip][info->video][c][1];
                    if (note != CB_NOTE_OFF && (note < lo || note > hi))
                        return fail(image, "note outside the lane's range", CB_OFF_DATA + at);
                    ++at;
                }
                if (m & CB_MASK_INSTRUMENT) {
                    if (!cb_allowed(image, b[CB_OFF_DATA + at], c))
                        return fail(image, "row names an instrument the image does not define or the lane cannot play",
                                    CB_OFF_DATA + at);
                    ++at;
                }
                for (int e = 0; e < effects; ++e, at += 2) {
                    const int code = b[CB_OFF_DATA + at], param = b[CB_OFF_DATA + at + 1];
                    const char* why = "effect code above Z";
                    if (code > 35 || effect_problem(info, c, code, param, &why)) return fail(image, why, CB_OFF_DATA + at);
                    if (code == CB_FX_SPEED) speed = param; /* rows, then channels, then columns: the last F wins */
                }
                offsets[c] = at;
            }
        }
    }
    info->loaded = CB_LOADED;
    return 0;
}

void cb_next_row(const uint8_t* bytes, CbCursor* cursor, CbRow* row) {
    row->note = -1;
    row->instrument = 0;
    row->effects = 0;
    if (cursor->run) {
        --cursor->run;
        return;
    }
    const uint8_t* p = bytes + CB_OFF_DATA + cursor->offset;
    int at = 0;
    const int m = p[at++];
    if (m & CB_RUN) {
        cursor->run = m & 0x7F;
        cursor->offset += 1;
        return;
    }
    if (m & CB_MASK_NOTE) row->note = p[at++];
    if (m & CB_MASK_INSTRUMENT) row->instrument = p[at++];
    row->effects = (m >> CB_MASK_EFFECT_SHIFT) & 3;
    for (int e = 0; e < row->effects; ++e) {
        row->code[e] = p[at++];
        row->param[e] = p[at++];
    }
    cursor->offset += at;
}

void cb_cursor(const CbmImage* image, int order, int channel, CbCursor* cursor) {
    cursor->offset = cb_u16(image->bytes + CB_OFF_ORDERS + 2 * (CB_MAX_CHANNELS * order + channel));
    cursor->run = 0;
}

void cb_read_row(const CbmImage* image, int order, int channel, int row, CbRow* out, CbCursor* after) {
    CbCursor c;
    cb_cursor(image, order, channel, &c);
    for (int r = 0; r < row; ++r) cb_next_row(image->bytes, &c, out);
    cb_next_row(image->bytes, &c, out);
    if (after) *after = c;
}
