/* VSU image validation and row decoding (vsu_image.h; layout in docs/AUDIO_ENGINE.md "Virtual Boy VSU: Image
 * layout"). Every reason here has a user-facing counterpart in the Kotlin validator. */
#include "vsu_image.h"

#include <string.h>

static int fail(VsuImage* image, const char* problem, int offset) {
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

/* Whether lane c declares the effect, and its parameter's range. */
static int effect_problem(const VbInfo* info, int lane, int code, int param, const char** why) {
    switch (code) {
    case VB_FX_ARPEGGIO: case VB_FX_SLIDE_UP: case VB_FX_SLIDE_DOWN: case VB_FX_PORTAMENTO: case VB_FX_VIBRATO:
    case VB_FX_STEREO: case VB_FX_BREAK: case VB_FX_DELAY: case VB_FX_FINE: case VB_FX_CUT:
        return 0;
    case VB_FX_JUMP:
        *why = "Bxx jumps past the last order";
        return param >= info->orders;
    case VB_FX_LEVEL:
        *why = "Cxx level above 0F";
        return param > 0x0F;
    case VB_FX_SPEED:
        *why = "F00: speed must be 1..FF";
        return param == 0;
    case VB_FX_LENGTH:
        *why = "Lxx length above 20";
        return param > 0x20;
    case VB_FX_SWEEPMOD:
        *why = "Mxx only on Wave 5";
        return lane != VB_LANE_WAVE5;
    case VB_FX_TAP:
        *why = lane != VB_LANE_NOISE ? "Nxx only on Noise" : "Nxx tap above 07";
        return lane != VB_LANE_NOISE || param > 7;
    case VB_FX_WAVE:
        *why = lane == VB_LANE_NOISE ? "Wxx only on wave lanes" : "Wxx wave must be 01..05";
        return lane == VB_LANE_NOISE || param < 1 || param > 5;
    default:
        *why = "effect not declared for the lane";
        return 1;
    }
}

static int check_instrument(VsuImage* image, int slot) {
    const int base = VB_OFF_INSTRUMENTS + VB_INSTRUMENT_SIZE * (slot - 1);
    const uint8_t* p = image->bytes + base;
    if (zero(p, VB_INSTRUMENT_SIZE)) return 0;
    if (slot > image->info.instruments) return fail(image, "instrument record past the instrument count", base);
    if ((p[VB_I_FLAGS] & ~VB_FLAG_ALL) || !(p[VB_I_FLAGS] & VB_FLAG_DEFINED)) return fail(image, "instrument flags", base);
    if (p[VB_I_LEVEL] > 15) return fail(image, "instrument envelope level above 15", base + VB_I_LEVEL);
    if (p[VB_I_INTERVAL] > 7) return fail(image, "instrument envelope interval above 7", base + VB_I_INTERVAL);
    if (p[VB_I_LENGTH] > 31) return fail(image, "instrument length above 31", base + VB_I_LENGTH);
    if (p[VB_I_WAVE] > 4) return fail(image, "instrument wave above 4", base + VB_I_WAVE);
    if (p[VB_I_TAP] > 7) return fail(image, "instrument noise tap above 7", base + VB_I_TAP);
    if (p[VB_I_FREQMOD] & ~VB_FM_ALL) return fail(image, "instrument frequency modification bits", base + VB_I_FREQMOD);
    for (int i = VB_I_SWEEP_START; i <= VB_I_SWEEP_END; ++i) {
        const int v = (int8_t)p[i];
        if (v < -96 || v > 96) return fail(image, "sweep start/end outside -96..96 semitones", base + i);
        if (v && !p[VB_I_SWEEP_SPEED]) return fail(image, "sweep start/end without a sweep speed", base + i);
    }
    if (!zero(p + 11, 5)) return fail(image, "instrument reserved bytes 11..15", base + 11);
    return 0;
}

const uint8_t* vb_instrument(const VsuImage* image, int slot) {
    if (slot < 1 || slot > image->info.instruments) return NULL;
    const uint8_t* rec = image->bytes + VB_OFF_INSTRUMENTS + VB_INSTRUMENT_SIZE * (slot - 1);
    return (rec[VB_I_FLAGS] & VB_FLAG_DEFINED) ? rec : NULL;
}

int vb_load_image(VsuImage* image) {
    const uint8_t* b = image->bytes;
    VbInfo* info = &image->info;
    memset(info, 0, sizeof *info);
    info->problem_offset = -1;
    if (memcmp(b, "TTVB", 4)) return fail(image, "not a VSU image (magic)", 0);
    if (b[VB_H_LAYOUT] != VB_LAYOUT) return fail(image, "unknown layout", VB_H_LAYOUT);
    info->speed = b[VB_H_SPEED];
    if (!info->speed) return fail(image, "speed must be 1..255", VB_H_SPEED);
    info->orders = vb_u16(b + VB_H_ORDERS);
    if (info->orders < 1 || info->orders > VB_MAX_ORDERS) return fail(image, "order count must be 1..256", VB_H_ORDERS);
    info->loop = vb_u16(b + VB_H_LOOP);
    if (info->loop >= info->orders) return fail(image, "loop order past the last order", VB_H_LOOP);
    info->instruments = b[VB_H_INSTRUMENTS];
    if (info->instruments > VB_MAX_INSTRUMENTS) return fail(image, "instrument count above 64", VB_H_INSTRUMENTS);
    if (b[VB_H_ZERO]) return fail(image, "header byte 11", VB_H_ZERO);
    const int size = vb_u16(b + VB_H_DATA_SIZE);
    if (size > (int)VB_DATA_CAPACITY) return fail(image, "data size above the region", VB_H_DATA_SIZE);
    if (!zero(b + 14, VB_HEADER_SIZE - 14)) return fail(image, "header reserved bytes 14..63", 14);

    for (int o = info->orders; o < VB_MAX_ORDERS; ++o) {
        if (b[VB_OFF_ROWS + o]) return fail(image, "rows of an order past the order count", VB_OFF_ROWS + o);
        if (!zero(b + VB_OFF_ORDERS + 2 * VB_LANES * o, 2 * VB_LANES))
            return fail(image, "patterns of an order past the order count", VB_OFF_ORDERS + 2 * VB_LANES * o);
    }
    for (int o = 0; o < info->orders; ++o) info->rows[o] = b[VB_OFF_ROWS + o] ? b[VB_OFF_ROWS + o] : 256;
    for (int n = 1; n <= VB_MAX_INSTRUMENTS; ++n)
        if (check_instrument(image, n)) return -1;
    for (int i = 0; i < 5 * 32; ++i)
        if (b[VB_OFF_WAVES + i] > 63) return fail(image, "wave sample above 63", VB_OFF_WAVES + i);

    /* Patterns: every (order, lane) decodes rows(o) rows inside the used data. */
    int speed = info->speed;
    for (int o = 0; o < info->orders; ++o) {
        info->start_speed[o] = (uint8_t)speed;
        int offsets[VB_LANES], runs[VB_LANES];
        for (int c = 0; c < VB_LANES; ++c) {
            offsets[c] = vb_pattern(b, o, c);
            runs[c] = 0;
            if (offsets[c] >= size) return fail(image, "pattern offset past the data size", VB_OFF_ORDERS + 2 * (VB_LANES * o + c));
        }
        for (int r = 0; r < info->rows[o]; ++r) {
            for (int c = 0; c < VB_LANES; ++c) {
                if (runs[c]) {
                    --runs[c];
                    continue;
                }
                int at = offsets[c];
                if (at >= size) return fail(image, "pattern runs past the data size", VB_OFF_DATA + at);
                const int m = b[VB_OFF_DATA + at++];
                if (m & 0x80) {
                    runs[c] = m & 0x7F;
                    offsets[c] = at;
                    continue;
                }
                const int effects = (m >> 2) & 3;
                if (!m || (m & 0x70) || effects > VB_MAX_EFFECTS) return fail(image, "row mask byte", VB_OFF_DATA + at - 1);
                const int need = (m & 1) + ((m >> 1) & 1) + 2 * effects;
                if (at + need > size) return fail(image, "row runs past the data size", VB_OFF_DATA + at);
                if (m & 1) {
                    const int note = b[VB_OFF_DATA + at];
                    const int low = c == VB_LANE_NOISE ? 0 : VB_WAVE_NOTE_MIN;
                    if (note != VB_NOTE_OFF && note >= VB_NOTES) return fail(image, "note byte", VB_OFF_DATA + at);
                    if (note != VB_NOTE_OFF && note < low) return fail(image, "note below D#2 on a wave lane", VB_OFF_DATA + at);
                    ++at;
                }
                if (m & 2) {
                    if (!vb_instrument(image, b[VB_OFF_DATA + at]))
                        return fail(image, "row names an instrument the image does not define", VB_OFF_DATA + at);
                    ++at;
                }
                for (int e = 0; e < effects; ++e, at += 2) {
                    const int code = b[VB_OFF_DATA + at], param = b[VB_OFF_DATA + at + 1];
                    const char* why = "effect code above Z";
                    if (code > 35 || effect_problem(info, c, code, param, &why)) return fail(image, why, VB_OFF_DATA + at);
                    if (code == VB_FX_SPEED) speed = param; /* rows, then lanes, then columns: the last F wins */
                }
                offsets[c] = at;
            }
        }
    }
    info->loaded = VB_LOADED;
    return 0;
}

void vb_next_row(const uint8_t* bytes, VbCursor* cursor, VbRow* row) {
    row->note = -1;
    row->instrument = 0;
    row->effects = 0;
    if (cursor->run) {
        --cursor->run;
        return;
    }
    const uint8_t* p = bytes + VB_OFF_DATA + cursor->offset;
    int at = 0;
    const int m = p[at++];
    if (m & 0x80) {
        cursor->run = m & 0x7F;
        cursor->offset += 1;
        return;
    }
    if (m & 1) row->note = p[at++];
    if (m & 2) row->instrument = p[at++];
    row->effects = (m >> 2) & 3;
    for (int e = 0; e < row->effects; ++e) {
        row->code[e] = p[at++];
        row->param[e] = p[at++];
    }
    cursor->offset += at;
}

void vb_read_row(const VsuImage* image, int order, int lane, int row, VbRow* out, VbCursor* after) {
    VbCursor c = {vb_pattern(image->bytes, order, lane), 0};
    for (int r = 0; r < row; ++r) vb_next_row(image->bytes, &c, out);
    vb_next_row(image->bytes, &c, out);
    if (after) *after = c;
}
