/* YM2203 / YM2608 image validation and row decoding (opn_image.h; layout in docs/AUDIO_ENGINE.md "OPN and OPNA"). */
#include "opn_image.h"

#include <string.h>

static int fail(OpnImage* image, const char* problem, int offset) {
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

/* 0 if `code` with `param` may stand on a channel of `part`, else 1 with *why. */
static int effect_problem(const OpnInfo* info, int part, int code, int param, const char** why) {
    const int opna = info->chip == OPN_CHIP_YM2608;
    const int fm = part == OPN_PART_FM, ssg = part == OPN_PART_SSG;
    switch (code) {
    case OPN_FX_ARPEGGIO: case OPN_FX_SLIDE_UP: case OPN_FX_SLIDE_DOWN: case OPN_FX_PORTAMENTO: case OPN_FX_VIBRATO:
    case OPN_FX_FINE:
        *why = "pitch effect (0xy, 1xx-4xy, Pxx) on a rhythm channel";
        return part == OPN_PART_RHYTHM;
    case OPN_FX_PAN:
        if (!opna) {
            *why = "8xy pan on the YM2203 (no stereo)";
            return 1;
        }
        *why = "8xy pan on an SSG channel";
        return ssg;
    case OPN_FX_VOLUME_SLIDE: case OPN_FX_BREAK: case OPN_FX_DELAY: case OPN_FX_CUT: case OPN_FX_TIMER_A:
        return 0;
    case OPN_FX_JUMP:
        *why = "Bxx jumps past the last order";
        return param >= info->orders;
    case OPN_FX_VOLUME:
        *why = fm ? "Cxx volume above 7F on an FM channel"
             : ssg ? "Cxx volume above 0F on an SSG channel"
                   : "Cxx volume above 1F on a rhythm channel";
        return param > (fm ? 0x7F : ssg ? 0x0F : part == OPN_PART_RHYTHM ? 0x1F : 0xFF);
    case OPN_FX_FEEDBACK:
        *why = fm ? "Exx feedback above 7" : "Exx feedback on a non-FM channel (FM only)";
        return !fm || param > 7;
    case OPN_FX_SPEED:
        *why = "F00: speed must be 1..FF";
        return param == 0;
    case OPN_FX_ENV_SHAPE:
        *why = ssg ? "Hxx envelope shape must be 00 or 08..0F" : "Hxx envelope shape on a non-SSG channel (SSG only)";
        return !ssg || (param != 0 && (param < 0x08 || param > 0x0F));
    case OPN_FX_BRIGHTNESS:
        *why = fm ? "Ixx brightness above 7F" : "Ixx brightness on a non-FM channel (FM only)";
        return !fm || param > 0x7F;
    case OPN_FX_ENV_PERIOD:
        *why = ssg ? "J00: envelope period must be 01..FF" : "Jxx envelope period on a non-SSG channel (SSG only)";
        return !ssg || param == 0;
    case OPN_FX_LFO:
        if (!opna) {
            *why = "Lxx LFO on the YM2203 (YM2608 only)";
            return 1;
        }
        *why = "Lxx LFO must be 00 or 08..0F";
        return param != 0 && (param < 0x08 || param > 0x0F);
    case OPN_FX_MULT:
        *why = fm ? "Mxy operator must be 1..4" : "Mxy ratio on a non-FM channel (FM only)";
        return !fm || (param >> 4) < 1 || (param >> 4) > 4;
    case OPN_FX_NOISE:
        *why = ssg ? "Nxx noise period above 1F" : "Nxx noise on a non-SSG channel (SSG only)";
        return !ssg || param > 0x1F;
    case OPN_FX_RHYTHM_LEVEL:
        if (!opna) {
            *why = "Rxx rhythm level on the YM2203 (YM2608 only)";
            return 1;
        }
        *why = "Rxx rhythm level above 3F";
        return param > 0x3F;
    default:
        *why = "effect not used on the OPN / OPNA";
        return 1;
    }
}

enum { ENV_VOLUME = 0, ENV_ARPEGGIO, ENV_PITCH, ENV_MIXER, ENV_NOISE };

/* The SSG envelope at instrument field `at` (u16 data offset or OPN_NO_ENVELOPE) of `kind`. */
static int check_envelope(OpnImage* image, int at, int kind, int size) {
    const uint8_t* b = image->bytes;
    const int off = opn_u16(b + at);
    if (off == OPN_NO_ENVELOPE) return 0;
    if (off + OPN_ENV_HEADER > size) return fail(image, "envelope outside the data size", at);
    const uint8_t* e = b + OPN_OFF_DATA + off;
    const int length = opn_u16(e), loop = opn_u16(e + 2), release = opn_u16(e + 4);
    if (length < 1 || length > OPN_ENV_MAX_LENGTH)
        return fail(image, "envelope length outside 1..256", OPN_OFF_DATA + off);
    if (off + OPN_ENV_HEADER + length > size) return fail(image, "envelope outside the data size", OPN_OFF_DATA + off);
    if (loop != OPN_NO_ENVELOPE && loop >= length)
        return fail(image, "envelope loop past its end", OPN_OFF_DATA + off + 2);
    if (release != OPN_NO_ENVELOPE) {
        if (kind != ENV_VOLUME)
            return fail(image, "release point on an envelope other than the volume", OPN_OFF_DATA + off + 4);
        if (loop == OPN_NO_ENVELOPE || release <= loop || release >= length)
            return fail(image, "volume release point needs a loop before it", OPN_OFF_DATA + off + 4);
    }
    static const int lo[5] = {0, -64, -64, 0, 0}, hi[5] = {15, 63, 63, 3, 31};
    static const char* const why[5] = {
        "volume envelope value outside 0..15",
        "arpeggio envelope value outside -64..63",
        "pitch envelope value outside -64..63",
        "mixer envelope value outside 0..3",
        "noise envelope value outside 0..31",
    };
    for (int i = 0; i < length; ++i) {
        const int v = (int8_t)e[OPN_ENV_HEADER + i];
        if (v < lo[kind] || v > hi[kind]) return fail(image, why[kind], OPN_OFF_DATA + off + OPN_ENV_HEADER + i);
    }
    return 0;
}

static int check_pan(OpnImage* image, const uint8_t* p, int base) {
    const int pan = p[OPN_I_PAN];
    if ((pan & 0x3F) || !(pan & 0xC0))
        return fail(image, "pan byte: bit 7 left, bit 6 right, at least one, other bits 0", base + OPN_I_PAN);
    return 0;
}

static int check_sweep(OpnImage* image, const uint8_t* p, int base) {
    for (int i = OPN_I_SWEEP_START; i <= OPN_I_SWEEP_END; ++i) {
        const int v = (int8_t)p[i];
        if (v < -OPN_SWEEP_LIMIT || v > OPN_SWEEP_LIMIT)
            return fail(image, "sweep start/end outside -96..96 semitones", base + i);
        if (!p[OPN_I_SWEEP_SPEED] && v) return fail(image, "sweep start/end without a sweep speed", base + i);
    }
    return 0;
}

static int check_instrument(OpnImage* image, int slot, int size) {
    const OpnInfo* info = &image->info;
    const int base = OPN_OFF_INSTRUMENTS + OPN_INSTRUMENT_SIZE * (slot - 1);
    const uint8_t* p = image->bytes + base;
    if (zero(p, OPN_INSTRUMENT_SIZE)) return 0;
    if (slot > info->instruments) return fail(image, "instrument record past the instrument count", base);
    const int flags = p[OPN_I_FLAGS];
    if (!(flags & OPN_FLAG_DEFINED) || (flags & ~(OPN_FLAG_DEFINED | OPN_KIND_MASK)))
        return fail(image, "instrument flags", base + OPN_I_FLAGS);
    const int kind = flags & OPN_KIND_MASK;
    const int opna = info->chip == OPN_CHIP_YM2608;
    if (!opna && kind >= OPN_KIND_RHYTHM) return fail(image, "rhythm or ADPCM sound on the YM2203", base + OPN_I_FLAGS);
    switch (kind) {
    case OPN_KIND_FM: {
        if (p[OPN_I_ALG] > 7) return fail(image, "FM algorithm above 7", base + OPN_I_ALG);
        if (p[OPN_I_FEEDBACK] > 7) return fail(image, "FM feedback above 7", base + OPN_I_FEEDBACK);
        if (p[OPN_I_AMS_FMS] & 0xC8)
            return fail(image, "FM AMS/FMS byte: only bits 0-2 and 4-5", base + OPN_I_AMS_FMS);
        if (!opna && p[OPN_I_AMS_FMS]) return fail(image, "FM AMS/FMS on the YM2203 (no LFO)", base + OPN_I_AMS_FMS);
        /* Bits each operator register must leave 0: $30 $40 $50 $60 $70 $80 $90 ($60 bit 7, AM, is the YM2608's). */
        static const uint8_t reserved[OPN_OP_BYTES] = {0x80, 0x80, 0x20, 0x60, 0xE0, 0x00, 0xF0};
        for (int k = 0; k < 4; ++k)
            for (int r = 0; r < OPN_OP_BYTES; ++r) {
                const int at = OPN_I_OPS + OPN_OP_BYTES * k + r;
                if (p[at] & reserved[r]) return fail(image, "FM operator register with reserved bits set", base + at);
                if (!opna && r == 3 && (p[at] & 0x80))
                    return fail(image, "FM operator AM bit on the YM2203 (no LFO)", base + at);
            }
        for (int k = 0; k < 4; ++k) {
            const int v = (int16_t)opn_u16(p + OPN_I_CH3 + 2 * k);
            if (v < -OPN_CH3_LIMIT || v > OPN_CH3_LIMIT)
                return fail(image, "FM 3 operator pitch offset outside -3072..3072", base + OPN_I_CH3 + 2 * k);
        }
        if (check_sweep(image, p, base)) return -1;
        break;
    }
    case OPN_KIND_SSG: {
        if (p[OPN_I_SSG_FLAGS] & ~OPN_SSG_PITCH_RELATIVE)
            return fail(image, "SSG flags: only bit 0", base + OPN_I_SSG_FLAGS);
        const int shape = p[OPN_I_SSG_SHAPE];
        if (shape != 0 && (shape < 0x08 || shape > 0x0F))
            return fail(image, "SSG envelope shape must be 0 or 8..15", base + OPN_I_SSG_SHAPE);
        if (p[OPN_I_SSG_AUTO] > 1) return fail(image, "SSG envelope auto byte must be 0 or 1", base + OPN_I_SSG_AUTO);
        const int octave = (int8_t)p[OPN_I_SSG_OCTAVE];
        if (octave < -8 || octave > 8) return fail(image, "SSG envelope octave outside -8..8", base + OPN_I_SSG_OCTAVE);
        if (p[7]) return fail(image, "SSG sound reserved byte 7", base + 7);
        for (int k = 0; k < 5; ++k)
            if (check_envelope(image, base + OPN_I_ENV_VOLUME + 2 * k, k, size)) return -1;
        if (!zero(p + 18, OPN_I_SWEEP_START - 18)) return fail(image, "SSG sound reserved bytes 18..39", base + 18);
        if (check_sweep(image, p, base)) return -1;
        break;
    }
    case OPN_KIND_RHYTHM:
        if (p[OPN_I_RHYTHM_LEVEL] > 31) return fail(image, "rhythm level above 31", base + OPN_I_RHYTHM_LEVEL);
        if (check_pan(image, p, base)) return -1;
        if (!zero(p + 3, OPN_INSTRUMENT_SIZE - 3)) return fail(image, "rhythm sound reserved bytes 3..47", base + 3);
        break;
    default: /* OPN_KIND_ADPCM */
        if (p[1]) return fail(image, "ADPCM sound reserved byte 1", base + 1);
        if (check_pan(image, p, base)) return -1;
        if (p[OPN_I_ADPCM_SAMPLE] >= info->samples)
            return fail(image, "ADPCM sound names a sample past the sample table", base + OPN_I_ADPCM_SAMPLE);
        if (p[OPN_I_ADPCM_ROOT] >= OPN_NOTES) return fail(image, "ADPCM root note above 95 (B-7)", base + OPN_I_ADPCM_ROOT);
        if (p[OPN_I_ADPCM_FLAGS] & ~OPN_ADPCM_LOOP) return fail(image, "ADPCM flags: only bit 0", base + OPN_I_ADPCM_FLAGS);
        if (!zero(p + 7, OPN_I_SWEEP_START - 7)) return fail(image, "ADPCM sound reserved bytes 7..39", base + 7);
        if (check_sweep(image, p, base)) return -1;
        break;
    }
    if (!zero(p + OPN_I_SWEEP_SPEED + 1, OPN_INSTRUMENT_SIZE - OPN_I_SWEEP_SPEED - 1))
        return fail(image, "instrument reserved bytes 43..47", base + OPN_I_SWEEP_SPEED + 1);
    return 0;
}

static int check_samples(OpnImage* image) {
    const OpnInfo* info = &image->info;
    uint32_t previous_end = 0;
    for (int k = 0; k < OPN_MAX_SAMPLES; ++k) {
        const int base = OPN_OFF_SAMPLES + OPN_SAMPLE_SIZE * k;
        const uint8_t* s = image->bytes + base;
        if (k >= info->samples) {
            if (!zero(s, OPN_SAMPLE_SIZE)) return fail(image, "sample entry past the sample count", base);
            continue;
        }
        const uint32_t start = opn_u32(s + OPN_S_START), end = opn_u32(s + OPN_S_END), rate = opn_u32(s + OPN_S_RATE);
        if (start % OPN_ADPCM_ALIGN) return fail(image, "sample start not a multiple of 32", base + OPN_S_START);
        if (end % OPN_ADPCM_ALIGN) return fail(image, "sample end not a multiple of 32", base + OPN_S_END);
        if (end <= start) return fail(image, "sample end not after its start", base + OPN_S_END);
        if (end > info->adpcm_size) return fail(image, "sample end past the ADPCM-B size", base + OPN_S_END);
        if (start < previous_end) return fail(image, "samples overlap or are out of order", base + OPN_S_START);
        if (rate < OPN_RATE_MIN || rate > OPN_RATE_MAX)
            return fail(image, "sample rate outside 1000..55466 Hz", base + OPN_S_RATE);
        if (!zero(s + 12, OPN_SAMPLE_SIZE - 12)) return fail(image, "sample reserved bytes 12..15", base + 12);
        previous_end = end;
    }
    return 0;
}

/* Every (order, channel) pattern decodes exactly rows(o) rows inside the used data; derives start_speed and
 * uses_rhythm. */
static int check_patterns(OpnImage* image, int size) {
    const uint8_t* b = image->bytes;
    OpnInfo* info = &image->info;
    const int channels = info->channels;
    int speed = info->speed;
    for (int o = 0; o < info->orders; ++o) {
        info->start_speed[o] = (uint8_t)speed;
        int offsets[OPN_MAX_CHANNELS], runs[OPN_MAX_CHANNELS];
        for (int c = 0; c < channels; ++c) {
            const int at = OPN_OFF_ORDERS + 2 * (16 * o + c);
            offsets[c] = opn_u16(b + at);
            runs[c] = 0;
            if (offsets[c] >= size) return fail(image, "pattern offset past the data size", at);
        }
        const int rows = info->rows[o];
        for (int r = 0; r < rows; ++r) {
            for (int c = 0; c < channels; ++c) {
                if (runs[c]) {
                    --runs[c];
                    continue;
                }
                const int part = opn_part(info->chip, c);
                int at = offsets[c];
                if (at >= size) return fail(image, "pattern runs past the data size", OPN_OFF_DATA + at);
                const int m = b[OPN_OFF_DATA + at++];
                if (m & 0x80) {
                    runs[c] = m & 0x7F;
                    if (r + runs[c] + 1 > rows)
                        return fail(image, "empty-row run past the pattern's last row", OPN_OFF_DATA + at - 1);
                    offsets[c] = at;
                    continue;
                }
                const int effects = (m >> 2) & 7;
                if (!m || (m & 0x60) || effects > OPN_MAX_EFFECTS)
                    return fail(image, "row mask byte", OPN_OFF_DATA + at - 1);
                const int need = (m & 1) + ((m >> 1) & 1) + 2 * effects;
                if (at + need > size) return fail(image, "row runs past the data size", OPN_OFF_DATA + at);
                if (m & 1) {
                    const int note = b[OPN_OFF_DATA + at];
                    if (note >= OPN_NOTES && note != OPN_NOTE_OFF) return fail(image, "note byte", OPN_OFF_DATA + at);
                    if (part == OPN_PART_SSG && note < OPN_SSG_LOWEST)
                        return fail(image, "SSG note below B-0 (the SSG's lowest)", OPN_OFF_DATA + at);
                    if (part == OPN_PART_RHYTHM && note < OPN_NOTES) info->uses_rhythm = 1;
                    ++at;
                }
                if (m & 2) {
                    const int slot = b[OPN_OFF_DATA + at];
                    if (!opn_instrument(image, slot, -1))
                        return fail(image, "row names an instrument the image does not define", OPN_OFF_DATA + at);
                    if (!opn_instrument(image, slot, part))
                        return fail(image, "row names a sound of another part than the channel's", OPN_OFF_DATA + at);
                    ++at;
                }
                for (int e = 0; e < effects; ++e, at += 2) {
                    const int code = b[OPN_OFF_DATA + at], param = b[OPN_OFF_DATA + at + 1];
                    const char* why = "effect code above Z";
                    if (code > 35 || effect_problem(info, part, code, param, &why))
                        return fail(image, why, OPN_OFF_DATA + at);
                    if (code == OPN_FX_SPEED) speed = param; /* rows, then channels, then columns: the last F wins */
                }
                offsets[c] = at;
            }
        }
    }
    return 0;
}

int opn_load_image(OpnImage* image, int chip) {
    const uint8_t* b = image->bytes;
    OpnInfo* info = &image->info;
    memset(info, 0, sizeof *info);
    info->problem_offset = -1;
    if (memcmp(b, "TTOP", 4)) return fail(image, "not an OPN / OPNA image (magic)", OPN_H_MAGIC);
    if (b[OPN_H_LAYOUT] != OPN_LAYOUT) return fail(image, "unknown layout", OPN_H_LAYOUT);
    if (chip != OPN_CHIP_YM2203 && chip != OPN_CHIP_YM2608) return fail(image, "unknown chip", OPN_H_CHIP);
    if (b[OPN_H_CHIP] != chip) return fail(image, "chip byte does not match the engine", OPN_H_CHIP);
    const int opna = chip == OPN_CHIP_YM2608;
    info->chip = chip;
    info->channels = opn_channels(chip);
    info->speed = b[OPN_H_SPEED];
    if (!info->speed) return fail(image, "speed must be 1..255", OPN_H_SPEED);
    info->lfo = b[OPN_H_LFO];
    if (!opna && info->lfo) return fail(image, "LFO byte must be 0 on the YM2203", OPN_H_LFO);
    if (info->lfo != 0 && (info->lfo < 0x08 || info->lfo > 0x0F))
        return fail(image, "LFO byte must be 0 or 8..15", OPN_H_LFO);
    info->orders = opn_u16(b + OPN_H_ORDERS);
    if (info->orders < 1 || info->orders > OPN_MAX_ORDERS) return fail(image, "order count must be 1..256", OPN_H_ORDERS);
    info->loop = opn_u16(b + OPN_H_LOOP);
    if (info->loop >= info->orders) return fail(image, "loop order past the last order", OPN_H_LOOP);
    info->instruments = b[OPN_H_INSTRUMENTS];
    if (info->instruments > OPN_MAX_INSTRUMENTS) return fail(image, "instrument count above 64", OPN_H_INSTRUMENTS);
    info->fm3_mode = b[OPN_H_FM3_MODE];
    if (info->fm3_mode > OPN_FM3_CSM)
        return fail(image, "FM 3 mode must be 0 (normal), 1 (special) or 2 (CSM)", OPN_H_FM3_MODE);
    const int size = opn_u16(b + OPN_H_DATA_SIZE);
    if (size > (int)OPN_DATA_CAPACITY) return fail(image, "data size above the region", OPN_H_DATA_SIZE);
    info->data_size = size;
    info->timer_a = opn_u16(b + OPN_H_TIMER_A);
    if (info->timer_a > OPN_TIMER_A_MAX) return fail(image, "timer A above 1023", OPN_H_TIMER_A);
    info->timer_b = b[OPN_H_TIMER_B];
    if (info->timer_b < OPN_TIMER_B_MIN || info->timer_b > OPN_TIMER_B_MAX)
        return fail(image, "timer B outside 183..250", OPN_H_TIMER_B);
    info->tick_samples = opn_tick_samples(info->timer_b);
    info->rhythm_level = b[OPN_H_RHYTHM_LEVEL];
    if (!opna && info->rhythm_level) return fail(image, "rhythm level must be 0 on the YM2203", OPN_H_RHYTHM_LEVEL);
    if (info->rhythm_level > 63) return fail(image, "rhythm level above 63", OPN_H_RHYTHM_LEVEL);
    info->samples = b[OPN_H_SAMPLES];
    if (!opna && info->samples) return fail(image, "sample count must be 0 on the YM2203", OPN_H_SAMPLES);
    if (info->samples > OPN_MAX_SAMPLES) return fail(image, "sample count above 64", OPN_H_SAMPLES);
    if (!zero(b + 21, 3)) return fail(image, "header reserved bytes 21..23", 21);
    info->adpcm_size = opn_u32(b + OPN_H_ADPCM_SIZE);
    if (!opna && info->adpcm_size) return fail(image, "ADPCM-B size must be 0 on the YM2203", OPN_H_ADPCM_SIZE);
    if (info->adpcm_size % OPN_ADPCM_ALIGN) return fail(image, "ADPCM-B size not a multiple of 32", OPN_H_ADPCM_SIZE);
    if (info->adpcm_size > OPN_ADPCM_MEMORY) return fail(image, "ADPCM-B size above 256 KiB", OPN_H_ADPCM_SIZE);
    if (!zero(b + 28, OPN_HEADER_SIZE - 28)) return fail(image, "header reserved bytes 28..63", 28);

    for (int o = 0; o < OPN_MAX_ORDERS; ++o) {
        const int at = OPN_OFF_ORDERS + 2 * 16 * o;
        if (o >= info->orders) {
            if (b[OPN_OFF_ROWS + o]) return fail(image, "rows of an order past the order count", OPN_OFF_ROWS + o);
            if (!zero(b + at, 2 * 16)) return fail(image, "patterns of an order past the order count", at);
            continue;
        }
        info->rows[o] = b[OPN_OFF_ROWS + o] ? b[OPN_OFF_ROWS + o] : 256;
        if (!zero(b + at + 2 * info->channels, 2 * (16 - (size_t)info->channels)))
            return fail(image, "pattern of a channel the chip does not have", at + 2 * info->channels);
    }
    if (check_samples(image)) return -1;
    if (opna && !zero(b + OPN_OFF_ADPCM + info->adpcm_size, OPN_ADPCM_MEMORY - info->adpcm_size))
        return fail(image, "ADPCM-B memory past the used size is not 0", (int)(OPN_OFF_ADPCM + info->adpcm_size));
    for (int n = 1; n <= OPN_MAX_INSTRUMENTS; ++n)
        if (check_instrument(image, n, size)) return -1;
    if (check_patterns(image, size)) return -1;
    info->problem = NULL;
    info->problem_offset = -1;
    info->loaded = OPN_LOADED;
    return 0;
}

void opn_copy_image(OpnImage* dest, const OpnImage* src) {
    dest->info = src->info;
    memcpy(dest->bytes, src->bytes, OPN_OFF_DATA + (size_t)src->info.data_size);
    /* The used ADPCM-B memory only when its bytes differ (an edit that keeps the samples writes none of them). */
    uint8_t* d = dest->bytes + OPN_OFF_ADPCM;
    const uint8_t* s = src->bytes + OPN_OFF_ADPCM;
    if (memcmp(d, s, src->info.adpcm_size) != 0) memcpy(d, s, src->info.adpcm_size);
}

void opn_next_row(const uint8_t* bytes, OpnCursor* cursor, OpnRow* row) {
    row->note = -1;
    row->instrument = 0;
    row->effects = 0;
    if (cursor->run) {
        --cursor->run;
        return;
    }
    const uint8_t* p = bytes + OPN_OFF_DATA + cursor->offset;
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

OpnCursor opn_pattern(const OpnImage* image, int order, int channel) {
    OpnCursor c = {opn_u16(image->bytes + OPN_OFF_ORDERS + 2 * (16 * order + channel)), 0};
    return c;
}

void opn_read_row(const OpnImage* image, int order, int channel, int row, OpnRow* out) {
    OpnCursor c = opn_pattern(image, order, channel);
    for (int r = 0; r < row; ++r) opn_next_row(image->bytes, &c, out);
    opn_next_row(image->bytes, &c, out);
}

const uint8_t* opn_instrument(const OpnImage* image, int slot, int kind) {
    if (slot < 1 || slot > image->info.instruments) return NULL;
    const uint8_t* rec = image->bytes + OPN_OFF_INSTRUMENTS + OPN_INSTRUMENT_SIZE * (slot - 1);
    if (!(rec[OPN_I_FLAGS] & OPN_FLAG_DEFINED)) return NULL;
    if (kind >= 0 && (rec[OPN_I_FLAGS] & OPN_KIND_MASK) != kind) return NULL;
    return rec;
}

const uint8_t* opn_sample(const OpnImage* image, int k) {
    if (k < 0 || k >= image->info.samples) return NULL;
    return image->bytes + OPN_OFF_SAMPLES + OPN_SAMPLE_SIZE * k;
}
