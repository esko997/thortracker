/* OPM image validation and row decoding (opm_image.h; layout in docs/AUDIO_ENGINE.md "OPM"). */
#include "opm_image.h"

#include <string.h>

static int fail(OpmImage* image, const char* problem, int offset) {
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

static int effect_problem(const OmInfo* info, int channel, int code, int param, const char** why) {
    const int x = param >> 4, y = param & 15;
    switch (code) {
    case OM_FX_ARPEGGIO: case OM_FX_SLIDE_UP: case OM_FX_SLIDE_DOWN: case OM_FX_PORTAMENTO:
    case OM_FX_VIBRATO: case OM_FX_PAN: case OM_FX_VOLUME_SLIDE: case OM_FX_BREAK: case OM_FX_DELAY:
    case OM_FX_LFO_RATE: case OM_FX_FINE: case OM_FX_TIMER_A: case OM_FX_CUT:
        return 0;
    case OM_FX_JUMP:
        *why = "Bxx jumps past the last order";
        return param >= info->orders;
    case OM_FX_VOLUME:
        *why = "Cxx volume above 7F";
        return param > 0x7F;
    case OM_FX_FEEDBACK:
        *why = "Exx feedback above 7";
        return param > 7;
    case OM_FX_SPEED:
        *why = "F00: speed must be 1..FF";
        return param == 0;
    case OM_FX_SENSITIVITY:
        *why = "Hxy: PMS must be 0..7 and AMS 0..3";
        return x > 7 || y > 3;
    case OM_FX_BRIGHTNESS:
        *why = "Ixx brightness above 7F";
        return param > 0x7F;
    case OM_FX_MULT:
        *why = "Mxy operator must be 1..4";
        return x < 1 || x > 4;
    case OM_FX_NOISE:
        if (channel != OM_NOISE_CHANNEL) {
            *why = "Nxx noise frequency off FM 8";
            return 1;
        }
        *why = "Nxx noise frequency above 1F";
        return param > 0x1F;
    case OM_FX_AMD:
        *why = "Txx LFO amplitude depth above 7F";
        return param > 0x7F;
    case OM_FX_PMD:
        *why = "Vxx LFO pitch depth above 7F";
        return param > 0x7F;
    case OM_FX_LFO_WAVE:
        *why = "Wxx LFO waveform above 3";
        return param > 3;
    case OM_FX_CSM:
        *why = "Xxx CSM must be 0 or 1";
        return param > 1;
    default:
        *why = "unknown effect code";
        return 1;
    }
}

static int check_instrument(OpmImage* image, int slot) {
    const int base = OM_OFF_INSTRUMENTS + OM_INSTRUMENT_SIZE * (slot - 1);
    const uint8_t* p = image->bytes + base;
    if (zero(p, OM_INSTRUMENT_SIZE)) return 0;
    if (slot > image->info.instruments) return fail(image, "instrument record past the instrument count", base);
    if ((p[OM_I_FLAGS] & ~(OM_FLAG_DEFINED | OM_FLAG_NOISE)) || !(p[OM_I_FLAGS] & OM_FLAG_DEFINED))
        return fail(image, "instrument flags", base + OM_I_FLAGS);
    if (p[OM_I_CONNECT] & 0xC0) return fail(image, "instrument connection: bits 6-7 must be 0", base + OM_I_CONNECT);
    if (p[OM_I_SENS] & 0x8C) return fail(image, "instrument sensitivity: bits 7, 3 and 2 must be 0", base + OM_I_SENS);
    if (p[OM_I_NOISE] > 31) return fail(image, "instrument noise frequency above 31", base + OM_I_NOISE);
    if (p[OM_I_NOISE] && !(p[OM_I_FLAGS] & OM_FLAG_NOISE))
        return fail(image, "noise frequency without the noise flag", base + OM_I_NOISE);
    /* Reserved bits of $40, $60, $80, $A0, $C0 ($E0 uses all eight). */
    static const uint8_t RESERVED[OM_OPERATOR_BYTES] = {0x80, 0x80, 0x20, 0x60, 0x20, 0x00};
    for (int k = 0; k < 4; ++k)
        for (int i = 0; i < OM_OPERATOR_BYTES; ++i) {
            const int at = OM_I_OPS + OM_OPERATOR_BYTES * k + i;
            if (p[at] & RESERVED[i]) return fail(image, "operator register reserved bits", base + at);
        }
    for (int i = OM_I_SWEEP_START; i <= OM_I_SWEEP_END; ++i) {
        const int v = (int8_t)p[i];
        if (v < -96 || v > 96) return fail(image, "sweep start/end outside -96..96 semitones", base + i);
    }
    if (p[31]) return fail(image, "instrument reserved byte 31", base + 31);
    return 0;
}

static const uint8_t* record(const OpmImage* image, int slot) {
    if (slot < 1 || slot > image->info.instruments) return NULL;
    const uint8_t* rec = image->bytes + OM_OFF_INSTRUMENTS + OM_INSTRUMENT_SIZE * (slot - 1);
    return (rec[OM_I_FLAGS] & OM_FLAG_DEFINED) ? rec : NULL;
}

int om_load_image(OpmImage* image) {
    const uint8_t* b = image->bytes;
    OmInfo* info = &image->info;
    memset(info, 0, sizeof *info);
    info->problem_offset = -1;
    if (memcmp(b, "TTOM", 4)) return fail(image, "not an OPM image (magic)", 0);
    if (b[OM_H_LAYOUT] != OM_LAYOUT) return fail(image, "unknown layout", OM_H_LAYOUT);
    info->multispeed = b[OM_H_MULTISPEED];
    if (info->multispeed < 1 || info->multispeed > OM_MAX_MULTISPEED) return fail(image, "multispeed must be 1..8", OM_H_MULTISPEED);
    info->speed = b[OM_H_SPEED];
    if (!info->speed) return fail(image, "speed must be 1..255", OM_H_SPEED);
    if (b[OM_H_LFO_WAVE] > 3) return fail(image, "LFO waveform above 3", OM_H_LFO_WAVE);
    info->orders = om_u16(b + OM_H_ORDERS);
    if (info->orders < 1 || info->orders > OM_MAX_ORDERS) return fail(image, "order count must be 1..256", OM_H_ORDERS);
    info->loop = om_u16(b + OM_H_LOOP);
    if (info->loop >= info->orders) return fail(image, "loop order past the last order", OM_H_LOOP);
    info->instruments = b[OM_H_INSTRUMENTS];
    if (info->instruments > OM_MAX_INSTRUMENTS) return fail(image, "instrument count above 64", OM_H_INSTRUMENTS);
    if (b[OM_H_CSM] > 1) return fail(image, "CSM must be 0 or 1", OM_H_CSM);
    const int size = om_u16(b + OM_H_DATA_SIZE);
    if (size > (int)OM_DATA_CAPACITY) return fail(image, "data size above the region", OM_H_DATA_SIZE);
    if (b[OM_H_AMD] > 127) return fail(image, "LFO amplitude depth above 127", OM_H_AMD);
    if (b[OM_H_PMD] > 127) return fail(image, "LFO pitch depth above 127", OM_H_PMD);
    if (b[19]) return fail(image, "header reserved byte 19", 19);
    if (om_u16(b + OM_H_TIMER_A) > 1023) return fail(image, "timer A above 1023", OM_H_TIMER_A);
    if (!zero(b + 22, OM_HEADER_SIZE - 22)) return fail(image, "header reserved bytes 22..63", 22);

    for (int o = info->orders; o < OM_MAX_ORDERS; ++o) {
        if (b[OM_OFF_ROWS + o]) return fail(image, "rows of an order past the order count", OM_OFF_ROWS + o);
        if (!zero(b + OM_OFF_ORDERS + 2 * OM_CHANNELS * o, 2 * OM_CHANNELS))
            return fail(image, "patterns of an order past the order count", OM_OFF_ORDERS + 2 * OM_CHANNELS * o);
    }
    for (int o = 0; o < info->orders; ++o) info->rows[o] = b[OM_OFF_ROWS + o] ? b[OM_OFF_ROWS + o] : 256;
    for (int n = 1; n <= OM_MAX_INSTRUMENTS; ++n)
        if (check_instrument(image, n)) return -1;

    /* Patterns: every (order, channel) decodes rows(o) rows inside the used data. */
    int speed = info->speed;
    for (int o = 0; o < info->orders; ++o) {
        info->start_speed[o] = (uint8_t)speed;
        int offsets[OM_CHANNELS], runs[OM_CHANNELS];
        for (int c = 0; c < OM_CHANNELS; ++c) {
            offsets[c] = om_u16(b + OM_OFF_ORDERS + 2 * (OM_CHANNELS * o + c));
            runs[c] = 0;
            if (offsets[c] >= size) return fail(image, "pattern offset past the data size", OM_OFF_ORDERS + 2 * (OM_CHANNELS * o + c));
        }
        for (int r = 0; r < info->rows[o]; ++r) {
            for (int c = 0; c < OM_CHANNELS; ++c) {
                if (runs[c]) { --runs[c]; continue; }
                int at = offsets[c];
                if (at >= size) return fail(image, "pattern runs past the data size", OM_OFF_DATA + at);
                const int m = b[OM_OFF_DATA + at++];
                if (m & 0x80) { runs[c] = m & 0x7F; offsets[c] = at; continue; }
                const int effects = (m >> 2) & 7;
                if (!m || (m & 0x60) || effects > OM_MAX_EFFECTS) return fail(image, "row mask byte", OM_OFF_DATA + at - 1);
                const int need = (m & 1) + ((m >> 1) & 1) + 2 * effects;
                if (at + need > size) return fail(image, "row runs past the data size", OM_OFF_DATA + at);
                if (m & 1) {
                    const int note = b[OM_OFF_DATA + at];
                    if ((note < OM_NOTE_MIN || note > OM_NOTE_MAX) && note != OM_NOTE_OFF) return fail(image, "note byte", OM_OFF_DATA + at);
                    ++at;
                }
                if (m & 2) {
                    const uint8_t* rec = record(image, b[OM_OFF_DATA + at]);
                    if (!rec) return fail(image, "row names an instrument the image does not define", OM_OFF_DATA + at);
                    if (c != OM_NOISE_CHANNEL && (rec[OM_I_FLAGS] & OM_FLAG_NOISE))
                        return fail(image, "noise instrument off FM 8 (only FM 8 plays the noise)", OM_OFF_DATA + at);
                    ++at;
                }
                for (int e = 0; e < effects; ++e, at += 2) {
                    const int code = b[OM_OFF_DATA + at], param = b[OM_OFF_DATA + at + 1];
                    const char* why = "effect code above Z";
                    if (code > 35 || effect_problem(info, c, code, param, &why)) return fail(image, why, OM_OFF_DATA + at);
                    if (code == OM_FX_SPEED) speed = param; /* rows, then channels, then columns: the last F wins */
                }
                offsets[c] = at;
            }
        }
    }
    info->loaded = OM_LOADED;
    return 0;
}

void om_next_row(const uint8_t* bytes, OmCursor* cursor, OmRow* row) {
    row->note = -1;
    row->instrument = 0;
    row->effects = 0;
    if (cursor->run) {
        --cursor->run;
        return;
    }
    const uint8_t* p = bytes + OM_OFF_DATA + cursor->offset;
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

void om_read_row(const OpmImage* image, int order, int channel, int row, OmRow* out, OmCursor* after) {
    OmCursor c = {om_u16(image->bytes + OM_OFF_ORDERS + 2 * (OM_CHANNELS * order + channel)), 0};
    for (int r = 0; r < row; ++r) om_next_row(image->bytes, &c, out);
    om_next_row(image->bytes, &c, out);
    if (after) *after = c;
}
