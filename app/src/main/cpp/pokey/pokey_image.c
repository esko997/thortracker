/* POKEY image validation and row decoding (pokey_image.h; layout in docs/AUDIO_ENGINE.md "POKEY"). */
#include "pokey_image.h"

#include <string.h>

static int fail(PokeyImage* image, const char* problem, int offset) {
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

static int effect_problem(const PkInfo* info, int code, int param, const char** why) {
    switch (code) {
    case PK_FX_ARPEGGIO: case PK_FX_SLIDE_UP: case PK_FX_SLIDE_DOWN: case PK_FX_PORTAMENTO: case PK_FX_VIBRATO:
    case PK_FX_VOLUME_SLIDE: case PK_FX_BREAK: case PK_FX_DELAY: case PK_FX_FILTER: case PK_FX_FINE: case PK_FX_CUT:
        return 0;
    case PK_FX_JUMP:
        *why = "Bxx jumps past the last order";
        return param >= info->orders;
    case PK_FX_VOLUME:
        *why = "Cxx volume above 0F";
        return param > 0x0F;
    case PK_FX_SPEED:
        *why = "F00: speed must be 1..FF";
        return param == 0;
    case PK_FX_CLOCK:
        *why = "Kxx base clock must be 00 or 01";
        return param > 1;
    case PK_FX_POLY:
        *why = "Nxx noise generator must be 00 or 01";
        return param > 1;
    case PK_FX_RESTART:
        *why = "Rxx: only R00";
        return param != 0;
    case PK_FX_TIMERS:
        *why = "Txx: only T00";
        return param != 0;
    case PK_FX_DISTORTION:
        *why = "Vxx distortion above 08";
        return param > 8;
    case PK_FX_TWO_TONE:
        *why = "Wxx two-tone must be 00..02";
        return param > 2;
    default:
        *why = "unknown effect code";
        return 1;
    }
}

/* Envelope at data offset `off` (0xFFFF = none) with values in lo..hi. */
static int check_envelope(PokeyImage* image, int at, int lo, int hi) {
    const uint8_t* b = image->bytes;
    const int off = pk_u16(b + at), size = pk_u16(b + PK_H_DATA_SIZE);
    if (off == PK_ENV_NONE) return 0;
    if (off + PK_ENV_HEADER > size) return fail(image, "envelope offset past the data size", at);
    const uint8_t* e = b + PK_OFF_DATA + off;
    const int length = pk_u16(e), loop = pk_u16(e + 2), release = pk_u16(e + 4);
    const int base = PK_OFF_DATA + off;
    if (length < 1 || length > PK_MAX_ENVELOPE) return fail(image, "envelope length must be 1..256", base);
    if (loop != PK_ENV_NONE && loop >= length) return fail(image, "envelope loop past its length", base + 2);
    if (release != PK_ENV_NONE && (release < 1 || release >= length || (loop != PK_ENV_NONE && release <= loop)))
        return fail(image, "envelope release must be 1..length-1 and after the loop", base + 4);
    if (off + PK_ENV_HEADER + length > size) return fail(image, "envelope runs past the data size", base);
    for (int i = 0; i < length; ++i) {
        const int v = lo < 0 ? (int8_t)e[PK_ENV_HEADER + i] : e[PK_ENV_HEADER + i];
        if (v < lo || v > hi) return fail(image, "envelope value out of range", base + PK_ENV_HEADER + i);
    }
    return 0;
}

static int check_instrument(PokeyImage* image, int slot) {
    const int base = PK_OFF_INSTRUMENTS + PK_INSTRUMENT_SIZE * (slot - 1);
    const uint8_t* p = image->bytes + base;
    if (zero(p, PK_INSTRUMENT_SIZE)) return 0;
    if (slot > image->info.instruments) return fail(image, "instrument record past the instrument count", base);
    const int flags = p[PK_I_FLAGS];
    if ((flags & ~PK_FLAG_ALL) || !(flags & PK_FLAG_DEFINED)) return fail(image, "instrument flags", base + PK_I_FLAGS);
    const int sample = flags & PK_FLAG_SAMPLE, filtered = flags & PK_FLAG_FILTERED;
    if (sample && (flags & (PK_FLAG_JOINED | PK_FLAG_FILTERED)))
        return fail(image, "instrument flags: samples cannot be joined or filtered", base + PK_I_FLAGS);
    if ((flags & PK_FLAG_DIFFERENCE) && !filtered)
        return fail(image, "instrument flags: difference without filtered", base + PK_I_FLAGS);
    const int offset = (int8_t)p[PK_I_FILTER_OFFSET];
    if (flags & PK_FLAG_DIFFERENCE) {
        if (offset < 1) return fail(image, "difference filter offset must be 1..127", base + PK_I_FILTER_OFFSET);
    } else if (!filtered && offset) {
        return fail(image, "filter offset without filtered", base + PK_I_FILTER_OFFSET);
    }
    if (!(flags & (PK_FLAG_JOINED | PK_FLAG_FILTERED)) && p[PK_I_PARTNER_AUDC])
        return fail(image, "partner AUDC without joined or filtered", base + PK_I_PARTNER_AUDC);
    if (p[5]) return fail(image, "instrument reserved byte 5", base + 5);
    const int rate = pk_u16(p + PK_I_RATE), loop = pk_u16(p + PK_I_LOOP);
    if (sample) {
        if (p[PK_I_SAMPLE] >= image->info.samples) return fail(image, "instrument names a sample the image does not have", base + PK_I_SAMPLE);
        if (p[PK_I_ROOT] >= PK_NOTES) return fail(image, "sample root note above B-8", base + PK_I_ROOT);
        if (rate < 1000 || rate > 32000) return fail(image, "sample rate must be 1000..32000", base + PK_I_RATE);
        const int length = pk_u16(image->bytes + PK_OFF_SAMPLES + PK_SAMPLE_ENTRY_SIZE * p[PK_I_SAMPLE] + 2);
        if (loop != 0xFFFF && loop >= length) return fail(image, "sample loop past the sample's length", base + PK_I_LOOP);
        if (pk_u16(p + PK_I_ENV_VOLUME) != PK_ENV_NONE || pk_u16(p + PK_I_ENV_DISTORTION) != PK_ENV_NONE)
            return fail(image, "sample sounds have no volume or distortion envelope", base + PK_I_ENV_VOLUME);
    } else {
        if (p[PK_I_SAMPLE]) return fail(image, "sample index without samples", base + PK_I_SAMPLE);
        if (p[PK_I_ROOT]) return fail(image, "root note without samples", base + PK_I_ROOT);
        if (rate) return fail(image, "sample rate without samples", base + PK_I_RATE);
        if (loop != 0xFFFF) return fail(image, "sample loop without samples", base + PK_I_LOOP);
    }
    if (check_envelope(image, base + PK_I_ENV_VOLUME, 0, 15)) return -1;
    if (check_envelope(image, base + PK_I_ENV_ARPEGGIO, -64, 63)) return -1;
    if (check_envelope(image, base + PK_I_ENV_PITCH, -64, 63)) return -1;
    if (check_envelope(image, base + PK_I_ENV_DISTORTION, 0, PK_DISTORTIONS - 1)) return -1;
    if (!zero(p + 18, PK_INSTRUMENT_SIZE - 18)) return fail(image, "instrument reserved bytes 18..31", base + 18);
    return 0;
}

static int defined(const PokeyImage* image, int slot) {
    return slot >= 1 && slot <= image->info.instruments &&
           (image->bytes[PK_OFF_INSTRUMENTS + PK_INSTRUMENT_SIZE * (slot - 1) + PK_I_FLAGS] & PK_FLAG_DEFINED);
}

int pk_load_image(PokeyImage* image) {
    const uint8_t* b = image->bytes;
    PkInfo* info = &image->info;
    memset(info, 0, sizeof *info);
    info->problem_offset = -1;
    if (memcmp(b, "TTPK", 4)) return fail(image, "not a POKEY image (magic)", 0);
    if (b[PK_H_LAYOUT] != PK_LAYOUT) return fail(image, "unknown layout", PK_H_LAYOUT);
    info->machine = b[PK_H_MACHINE];
    if (info->machine > PK_MACHINE_PAL) return fail(image, "machine must be 0 (NTSC) or 1 (PAL)", PK_H_MACHINE);
    info->multispeed = b[PK_H_MULTISPEED];
    if (info->multispeed < 1 || info->multispeed > PK_MAX_MULTISPEED) return fail(image, "multispeed must be 1..4", PK_H_MULTISPEED);
    info->speed = b[PK_H_SPEED];
    if (!info->speed) return fail(image, "speed must be 1..255", PK_H_SPEED);
    info->orders = pk_u16(b + PK_H_ORDERS);
    if (info->orders < 1 || info->orders > PK_MAX_ORDERS) return fail(image, "order count must be 1..256", PK_H_ORDERS);
    info->loop = pk_u16(b + PK_H_LOOP);
    if (info->loop >= info->orders) return fail(image, "loop order past the last order", PK_H_LOOP);
    info->instruments = b[PK_H_INSTRUMENTS];
    if (info->instruments > PK_MAX_INSTRUMENTS) return fail(image, "instrument count above 64", PK_H_INSTRUMENTS);
    if (b[PK_H_AUDCTL] & 0x7E) return fail(image, "shared AUDCTL byte: only bits 7 and 0", PK_H_AUDCTL);
    const int size = pk_u16(b + PK_H_DATA_SIZE);
    if (size > (int)PK_DATA_CAPACITY) return fail(image, "data size above the region", PK_H_DATA_SIZE);
    const int sample_bytes = pk_u16(b + PK_H_SAMPLE_SIZE);
    if (sample_bytes > (int)PK_SAMPLE_CAPACITY) return fail(image, "sample bytes above the region", PK_H_SAMPLE_SIZE);
    info->samples = b[PK_H_SAMPLES];
    if (info->samples > PK_MAX_SAMPLES) return fail(image, "sample count above 32", PK_H_SAMPLES);
    if (!zero(b + 19, PK_HEADER_SIZE - 19)) return fail(image, "header reserved bytes 19..63", 19);

    for (int o = info->orders; o < PK_MAX_ORDERS; ++o) {
        if (b[PK_OFF_ROWS + o]) return fail(image, "rows of an order past the order count", PK_OFF_ROWS + o);
        if (!zero(b + PK_OFF_ORDERS + 2 * PK_CHANNELS * o, 2 * PK_CHANNELS))
            return fail(image, "patterns of an order past the order count", PK_OFF_ORDERS + 2 * PK_CHANNELS * o);
    }
    for (int o = 0; o < info->orders; ++o) info->rows[o] = b[PK_OFF_ROWS + o] ? b[PK_OFF_ROWS + o] : 256;
    for (int k = 0; k < PK_MAX_SAMPLES; ++k) {
        const int at = PK_OFF_SAMPLES + PK_SAMPLE_ENTRY_SIZE * k;
        if (k >= info->samples) {
            if (!zero(b + at, PK_SAMPLE_ENTRY_SIZE)) return fail(image, "sample entry past the sample count", at);
            continue;
        }
        const int offset = pk_u16(b + at), length = pk_u16(b + at + 2);
        if (length < 1 || length > PK_MAX_SAMPLE_LENGTH) return fail(image, "sample length must be 1..32768", at + 2);
        if (offset + (length + 1) / 2 > sample_bytes) return fail(image, "sample runs past the sample bytes", at);
    }
    for (int n = 1; n <= PK_MAX_INSTRUMENTS; ++n)
        if (check_instrument(image, n)) return -1;

    /* Patterns: every (order, channel) decodes rows(o) rows inside the used data. */
    int speed = info->speed, audctl = b[PK_H_AUDCTL], two_tone = 0;
    for (int o = 0; o < info->orders; ++o) {
        info->start_speed[o] = (uint8_t)speed;
        info->start_audctl[o] = (uint8_t)audctl;
        info->start_two_tone[o] = (uint8_t)two_tone;
        int offsets[PK_CHANNELS], runs[PK_CHANNELS];
        for (int c = 0; c < PK_CHANNELS; ++c) {
            const int at = PK_OFF_ORDERS + 2 * (PK_CHANNELS * o + c);
            offsets[c] = pk_u16(b + at);
            runs[c] = 0;
            if (offsets[c] >= size) return fail(image, "pattern offset past the data size", at);
        }
        for (int r = 0; r < info->rows[o]; ++r) {
            for (int c = 0; c < PK_CHANNELS; ++c) {
                if (runs[c]) { --runs[c]; continue; }
                int at = offsets[c];
                if (at >= size) return fail(image, "pattern runs past the data size", PK_OFF_DATA + at);
                const int m = b[PK_OFF_DATA + at++];
                if (m & 0x80) { runs[c] = m & 0x7F; offsets[c] = at; continue; }
                const int effects = (m >> 2) & 7;
                if (!m || (m & 0x60) || effects > PK_MAX_EFFECTS) return fail(image, "row mask byte", PK_OFF_DATA + at - 1);
                const int need = (m & 1) + ((m >> 1) & 1) + 2 * effects;
                if (at + need > size) return fail(image, "row runs past the data size", PK_OFF_DATA + at);
                if (m & 1) {
                    const int note = b[PK_OFF_DATA + at];
                    if (note >= PK_NOTES && note != PK_NOTE_OFF) return fail(image, "note byte", PK_OFF_DATA + at);
                    ++at;
                }
                if (m & 2) {
                    if (!defined(image, b[PK_OFF_DATA + at]))
                        return fail(image, "row names an instrument the image does not define", PK_OFF_DATA + at);
                    ++at;
                }
                for (int e = 0; e < effects; ++e, at += 2) {
                    const int code = b[PK_OFF_DATA + at], param = b[PK_OFF_DATA + at + 1];
                    const char* why = "effect code above Z";
                    if (code > 35 || effect_problem(info, code, param, &why)) return fail(image, why, PK_OFF_DATA + at);
                    /* rows, then channels, then columns: the last one wins */
                    if (code == PK_FX_SPEED) speed = param;
                    else if (code == PK_FX_CLOCK) audctl = (audctl & ~0x01) | param;
                    else if (code == PK_FX_POLY) audctl = (audctl & ~0x80) | (param ? 0x80 : 0);
                    else if (code == PK_FX_TWO_TONE) two_tone = param;
                }
                offsets[c] = at;
            }
        }
    }
    info->loaded = PK_LOADED;
    return 0;
}

void pk_next_row(const uint8_t* bytes, PkCursor* cursor, PkRow* row) {
    row->note = -1;
    row->instrument = 0;
    row->effects = 0;
    if (cursor->run) {
        --cursor->run;
        return;
    }
    const uint8_t* p = bytes + PK_OFF_DATA + cursor->offset;
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

void pk_read_row(const PokeyImage* image, int order, int channel, int row, PkRow* out, PkCursor* after) {
    PkCursor c = {pk_u16(image->bytes + PK_OFF_ORDERS + 2 * (PK_CHANNELS * order + channel)), 0};
    for (int r = 0; r < row; ++r) pk_next_row(image->bytes, &c, out);
    pk_next_row(image->bytes, &c, out);
    if (after) *after = c;
}
