/* SCC image validation and row decoding (scc_image.h; layout in docs/AUDIO_ENGINE.md "SCC and SCC+: Image layout").
 * Every rejection is `return reject(image, "reason", offset);` with a literal: tools/audio/prepare_scc.py collects
 * them (in source order) into SccPlayerImage.LOADER_PROBLEMS, which the Kotlin validator mirrors. */
#include "scc_image.h"

#include <string.h>

static int reject(SccImage* image, const char* problem, int offset) {
    image->info.loaded = 0;
    image->info.problem = problem;
    image->info.problem_offset = offset;
    return -1;
}

/* Index of the first non-zero byte of p[0..n), or -1. */
static int nonzero(const uint8_t* p, int n) {
    for (int i = 0; i < n; ++i)
        if (p[i]) return i;
    return -1;
}

static int defined(const SccImage* image, int slot) {
    return slot >= 1 && slot <= image->info.instruments &&
           (image->bytes[SC_OFF_INSTRUMENTS + SC_INSTRUMENT_SIZE * (slot - 1) + SC_I_FLAGS] & SC_FLAG_DEFINED);
}

/* The envelope referenced by field `which` of the record at `base` (absolute image offsets). */
static int check_envelope(SccImage* image, int base, int which, int size) {
    const uint8_t* b = image->bytes;
    const int field = base + which;
    const int off = sc_u16(b + field);
    if (off == (int)SC_ENV_NONE) return 0;
    if (off >= size) return reject(image, "envelope offset past the data size", field);
    const int at = SC_OFF_DATA + off;
    if (off + SC_ENV_HEADER > size) return reject(image, "envelope runs past the data size", at);
    const int length = sc_u16(b + at + SC_ENV_LENGTH);
    const int loop = sc_u16(b + at + SC_ENV_LOOP);
    const int release = sc_u16(b + at + SC_ENV_RELEASE);
    if (length < 1 || length > SC_MAX_ENVELOPE) return reject(image, "envelope length must be 1..256", at + SC_ENV_LENGTH);
    if (off + SC_ENV_HEADER + length > size) return reject(image, "envelope runs past the data size", at);
    if (loop != (int)SC_ENV_NONE && loop >= length)
        return reject(image, "envelope loop point past its length", at + SC_ENV_LOOP);
    if (release != (int)SC_ENV_NONE) {
        if (which != SC_I_VOLUME) return reject(image, "envelope release point on a non-volume envelope", at + SC_ENV_RELEASE);
        if (release < 1 || release >= length)
            return reject(image, "envelope release point must be 1..length-1", at + SC_ENV_RELEASE);
        if (loop != (int)SC_ENV_NONE && loop >= release)
            return reject(image, "envelope loop point must come before its release point", at + SC_ENV_LOOP);
    }
    const uint8_t* values = b + at + SC_ENV_HEADER;
    for (int i = 0; i < length; ++i) {
        const int v = values[i], s = (int8_t)values[i];
        const int where = at + SC_ENV_HEADER + i;
        switch (which) {
        case SC_I_VOLUME:
            if (v > 15) return reject(image, "envelope volume value above 15", where);
            break;
        case SC_I_ARPEGGIO:
            if (s < -SC_ARPEGGIO_LIMIT || s >= SC_ARPEGGIO_LIMIT)
                return reject(image, "envelope arpeggio value outside -64..63", where);
            break;
        case SC_I_PITCH:
            if (s < -SC_PITCH_ENV_LIMIT || s >= SC_PITCH_ENV_LIMIT)
                return reject(image, "envelope pitch value outside -64..63", where);
            break;
        default:
            if (v < 1 || v > image->info.waves) return reject(image, "envelope wave value must be 1..wave count", where);
            break;
        }
    }
    return 0;
}

static int check_instrument(SccImage* image, int slot, int size) {
    const int base = SC_OFF_INSTRUMENTS + SC_INSTRUMENT_SIZE * (slot - 1);
    const uint8_t* p = image->bytes + base;
    const int any = nonzero(p, SC_INSTRUMENT_SIZE);
    if (any < 0) return 0;
    if (slot > image->info.instruments)
        return reject(image, "instrument record past the instrument count", base + any);
    const int flags = p[SC_I_FLAGS];
    if (!(flags & SC_FLAG_DEFINED) || (flags & ~(SC_FLAG_DEFINED | SC_FLAG_RESTART | SC_FLAG_PITCH_RELATIVE)))
        return reject(image, "instrument flags", base + SC_I_FLAGS);
    if (p[1]) return reject(image, "instrument reserved byte 1", base + 1);
    const int reserved = nonzero(p + 10, SC_INSTRUMENT_SIZE - 10);
    if (reserved >= 0) return reject(image, "instrument reserved bytes 10..15", base + 10 + reserved);
    if (sc_u16(p + SC_I_WAVE) == (int)SC_ENV_NONE) return reject(image, "instrument without a wave envelope", base + SC_I_WAVE);
    static const int which[4] = {SC_I_VOLUME, SC_I_ARPEGGIO, SC_I_PITCH, SC_I_WAVE};
    for (int k = 0; k < 4; ++k)
        if (check_envelope(image, base, which[k], size)) return -1;
    return 0;
}

int sc_load_image(SccImage* image) {
    const uint8_t* b = image->bytes;
    ScInfo* info = &image->info;
    memset(info, 0, sizeof *info);
    info->problem_offset = -1;
    if (memcmp(b, "TTSC", 4)) return reject(image, "not an SCC image (magic)", SC_H_MAGIC);
    if (b[SC_H_LAYOUT] != SC_LAYOUT) return reject(image, "unknown layout", SC_H_LAYOUT);
    info->variant = b[SC_H_VARIANT];
    if (info->variant != SC_VARIANT_SCC && info->variant != SC_VARIANT_PLUS)
        return reject(image, "variant must be 0 (SCC) or 1 (SCC+)", SC_H_VARIANT);
    info->multispeed = b[SC_H_MULTISPEED];
    if (info->multispeed < 1 || info->multispeed > SC_MAX_MULTISPEED)
        return reject(image, "multispeed must be 1..8", SC_H_MULTISPEED);
    info->speed = b[SC_H_SPEED];
    if (!info->speed) return reject(image, "speed must be 1..255", SC_H_SPEED);
    info->orders = sc_u16(b + SC_H_ORDERS);
    if (info->orders < 1 || info->orders > SC_MAX_ORDERS) return reject(image, "order count must be 1..256", SC_H_ORDERS);
    info->loop = sc_u16(b + SC_H_LOOP);
    if (info->loop >= info->orders) return reject(image, "loop order past the last order", SC_H_LOOP);
    info->instruments = b[SC_H_INSTRUMENTS];
    if (info->instruments > SC_MAX_INSTRUMENTS) return reject(image, "instrument count above 64", SC_H_INSTRUMENTS);
    info->waves = b[SC_H_WAVES];
    if (info->waves < 1 || info->waves > SC_MAX_WAVES) return reject(image, "wave count must be 1..64", SC_H_WAVES);
    const int size = sc_u16(b + SC_H_DATA_SIZE);
    if (size > (int)SC_DATA_CAPACITY) return reject(image, "data size above the region", SC_H_DATA_SIZE);
    const int header = nonzero(b + 16, SC_HEADER_SIZE - 16);
    if (header >= 0) return reject(image, "header reserved bytes 16..63", 16 + header);

    for (int o = info->orders; o < SC_MAX_ORDERS; ++o) {
        if (b[SC_OFF_ROWS + o]) return reject(image, "rows of an order past the order count", SC_OFF_ROWS + o);
        const int at = SC_OFF_ORDERS + 2 * SC_CHANNELS * o, any = nonzero(b + at, 2 * SC_CHANNELS);
        if (any >= 0) return reject(image, "patterns of an order past the order count", at + any);
    }
    for (int o = 0; o < info->orders; ++o) info->rows[o] = b[SC_OFF_ROWS + o] ? b[SC_OFF_ROWS + o] : 256;
    for (int n = 1; n <= SC_MAX_INSTRUMENTS; ++n)
        if (check_instrument(image, n, size)) return -1;
    for (int k = info->waves + 1; k <= SC_MAX_WAVES; ++k) {
        const int at = SC_OFF_WAVES + SC_WAVE_BYTES * (k - 1), any = nonzero(b + at, SC_WAVE_BYTES);
        if (any >= 0) return reject(image, "wave past the wave count", at + any);
    }

    /* Patterns: every (order, channel) decodes rows(o) rows inside the used data. */
    int speed = info->speed;
    for (int o = 0; o < info->orders; ++o) {
        info->start_speed[o] = (uint8_t)speed;
        int offsets[SC_CHANNELS], runs[SC_CHANNELS];
        for (int c = 0; c < SC_CHANNELS; ++c) {
            const int field = SC_OFF_ORDERS + 2 * (SC_CHANNELS * o + c);
            offsets[c] = sc_u16(b + field);
            runs[c] = 0;
            if (offsets[c] >= size) return reject(image, "pattern offset past the data size", field);
        }
        for (int r = 0; r < info->rows[o]; ++r) {
            for (int c = 0; c < SC_CHANNELS; ++c) {
                if (runs[c]) {
                    --runs[c];
                    continue;
                }
                int at = offsets[c];
                if (at >= size) return reject(image, "pattern runs past the data size", SC_OFF_DATA + at);
                const int m = b[SC_OFF_DATA + at++];
                if (m & SC_RUN) {
                    runs[c] = m & 0x7F;
                    offsets[c] = at;
                    continue;
                }
                const int effects = (m >> SC_MASK_EFFECT_SHIFT) & 7;
                if (!m || (m & 0x60) || effects > SC_MAX_EFFECTS) return reject(image, "row mask byte", SC_OFF_DATA + at - 1);
                const int need = (m & SC_MASK_NOTE) + ((m >> 1) & 1) + 2 * effects;
                if (at + need > size) return reject(image, "row runs past the data size", SC_OFF_DATA + at);
                if (m & SC_MASK_NOTE) {
                    const int note = b[SC_OFF_DATA + at];
                    if (note >= SC_NOTES && note != SC_NOTE_OFF) return reject(image, "note byte", SC_OFF_DATA + at);
                    ++at;
                }
                if (m & SC_MASK_INSTRUMENT) {
                    if (!defined(image, b[SC_OFF_DATA + at]))
                        return reject(image, "row names an instrument the image does not define", SC_OFF_DATA + at);
                    ++at;
                }
                for (int e = 0; e < effects; ++e, at += 2) {
                    const int code = b[SC_OFF_DATA + at], param = b[SC_OFF_DATA + at + 1];
                    const int where = SC_OFF_DATA + at, param_at = where + 1;
                    switch (code) {
                    case SC_FX_ARPEGGIO: case SC_FX_SLIDE_UP: case SC_FX_SLIDE_DOWN: case SC_FX_PORTAMENTO:
                    case SC_FX_VIBRATO: case SC_FX_VOLUME_SLIDE: case SC_FX_BREAK: case SC_FX_DELAY: case SC_FX_FINE:
                    case SC_FX_CUT: case SC_FX_TEST:
                        break;
                    case SC_FX_JUMP:
                        if (param >= info->orders) return reject(image, "Bxx jumps past the last order", param_at);
                        break;
                    case SC_FX_VOLUME:
                        if (param > 0x0F) return reject(image, "Cxx volume above 0F", param_at);
                        break;
                    case SC_FX_SPEED:
                        if (!param) return reject(image, "F00: speed must be 1..FF", param_at);
                        speed = param; /* rows, then channels, then columns: the last F wins */
                        break;
                    case SC_FX_WAVE:
                        if (param < 1 || param > info->waves)
                            return reject(image, "Wxx names a wave the image does not have", param_at);
                        break;
                    default:
                        if (code > 35) return reject(image, "effect code above Z", where);
                        return reject(image, "unknown effect code", where);
                    }
                }
                offsets[c] = at;
            }
        }
    }
    info->loaded = SC_LOADED;
    return 0;
}

void sc_next_row(const uint8_t* bytes, ScCursor* cursor, ScRow* row) {
    row->note = -1;
    row->instrument = 0;
    row->effects = 0;
    if (cursor->run) {
        --cursor->run;
        return;
    }
    const uint8_t* p = bytes + SC_OFF_DATA + cursor->offset;
    int at = 0;
    const int m = p[at++];
    if (m & SC_RUN) {
        cursor->run = m & 0x7F;
        cursor->offset += 1;
        return;
    }
    if (m & SC_MASK_NOTE) row->note = p[at++];
    if (m & SC_MASK_INSTRUMENT) row->instrument = p[at++];
    row->effects = (m >> SC_MASK_EFFECT_SHIFT) & 7;
    for (int e = 0; e < row->effects; ++e) {
        row->code[e] = p[at++];
        row->param[e] = p[at++];
    }
    cursor->offset += at;
}

void sc_read_row(const SccImage* image, int order, int channel, int row, ScRow* out, ScCursor* after) {
    ScCursor c = {sc_u16(image->bytes + SC_OFF_ORDERS + 2 * (SC_CHANNELS * order + channel)), 0};
    for (int r = 0; r < row; ++r) sc_next_row(image->bytes, &c, out);
    sc_next_row(image->bytes, &c, out);
    if (after) *after = c;
}

void sc_envelope(const SccImage* image, int slot, int which, ScEnvelope* out) {
    const uint8_t* rec = image->bytes + SC_OFF_INSTRUMENTS + SC_INSTRUMENT_SIZE * (slot - 1);
    const int off = sc_u16(rec + which);
    if (off == (int)SC_ENV_NONE) {
        out->values = NULL;
        out->length = 0;
        out->loop = out->release = -1;
        return;
    }
    const uint8_t* e = image->bytes + SC_OFF_DATA + off;
    const int loop = sc_u16(e + SC_ENV_LOOP), release = sc_u16(e + SC_ENV_RELEASE);
    out->values = e + SC_ENV_HEADER;
    out->length = sc_u16(e + SC_ENV_LENGTH);
    out->loop = loop == (int)SC_ENV_NONE ? -1 : loop;
    out->release = release == (int)SC_ENV_NONE ? -1 : release;
}
