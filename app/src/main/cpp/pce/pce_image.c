/* PC Engine image validation and row decoding (pce_image.h; layout in docs/AUDIO_ENGINE.md "PC Engine"). Checks run
 * in this order: header, the order tables past the order count, waves, sample entries, instruments, patterns. */
#include "pce_image.h"

#include <string.h>

static int fail(PceImage* image, const char* problem, int offset) {
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

static int lfo_ctrl_ok(int v) { return (v & ~0x83) == 0; }

static int effect_problem(const PcInfo* info, int code, int param, const char** why) {
    switch (code) {
    case PC_FX_ARPEGGIO: case PC_FX_SLIDE_UP: case PC_FX_SLIDE_DOWN: case PC_FX_PORTAMENTO:
    case PC_FX_VIBRATO: case PC_FX_PAN: case PC_FX_VOLUME_SLIDE: case PC_FX_BREAK: case PC_FX_DELAY:
    case PC_FX_LFO_FREQ: case PC_FX_FINE: case PC_FX_CUT: case PC_FX_MASTER:
        return 0;
    case PC_FX_JUMP:
        *why = "Bxx jumps past the last order";
        return param >= info->orders;
    case PC_FX_VOLUME:
        *why = "Cxx volume above 1F";
        return param > 0x1F;
    case PC_FX_SPEED:
        *why = "F00: speed must be 1..FF";
        return param == 0;
    case PC_FX_LFO_CTRL:
        *why = "Mxx LFO control must be 00..03 or 80..83";
        return !lfo_ctrl_ok(param);
    case PC_FX_TIMER:
        *why = "Txx timer above 7F";
        return param > PC_MAX_TIMER;
    case PC_FX_WAVE:
        *why = "Wxx names a wave the image does not have";
        return param >= info->waves;
    default:
        *why = "unknown effect code";
        return 1;
    }
}

/* Envelope k of the instrument at `base`: range inside the data and values in range. */
static int check_envelope(PceImage* image, int base, int kind, int k, uint32_t size) {
    const uint8_t* b = image->bytes;
    const int at = base + PC_I_ENVELOPES + PC_ENVELOPE_BYTES * k;
    const int offset = pc_u16(b + at), length = b[at + 2], loop = b[at + 3], release = b[at + 4];
    if (b[at + 5]) return fail(image, "envelope reserved byte", at + 5);
    if (!length) {
        if (offset || loop != PC_ENV_NONE || release != PC_ENV_NONE)
            return fail(image, "empty envelope: offset 0, loop and release FF", at);
        return 0;
    }
    if (k == PC_ENV_WAVE && kind != PC_KIND_TONE) return fail(image, "wave envelope on a noise or sample instrument", at + 2);
    if (loop != PC_ENV_NONE && loop >= length) return fail(image, "envelope loop past its length", at + 3);
    if (release != PC_ENV_NONE && (release < 1 || release >= length))
        return fail(image, "envelope release must be 1..length - 1", at + 4);
    if (loop != PC_ENV_NONE && release != PC_ENV_NONE && release <= loop)
        return fail(image, "envelope release must follow its loop", at + 4);
    if ((uint32_t)offset + (uint32_t)length > size) return fail(image, "envelope runs past the data size", at);
    for (int i = 0; i < length; ++i) {
        const int pos = PC_OFF_DATA + offset + i, v = b[pos], s = (int8_t)b[pos];
        if (k == PC_ENV_VOLUME && v > PC_WAVE_MAX) return fail(image, "volume envelope value above 31", pos);
        if (k == PC_ENV_ARPEGGIO && (s < -64 || s > 63)) return fail(image, "arpeggio envelope value outside -64..63", pos);
        if (k == PC_ENV_WAVE && v >= image->info.waves) return fail(image, "wave envelope names a wave the image does not have", pos);
    }
    return 0;
}

static int check_instrument(PceImage* image, int slot, uint32_t size) {
    const uint8_t* b = image->bytes;
    const int base = PC_OFF_INSTRUMENTS + PC_INSTRUMENT_SIZE * (slot - 1);
    const uint8_t* p = b + base;
    const PcInfo* info = &image->info;
    if (zero(p, PC_INSTRUMENT_SIZE)) return 0;
    if (slot > info->instruments) return fail(image, "instrument record past the instrument count", base);
    const int flags = p[PC_I_FLAGS], kind = flags & PC_KIND_MASK;
    if (!(flags & PC_FLAG_DEFINED) || (flags & ~(PC_FLAG_DEFINED | PC_FLAG_RELATIVE | PC_KIND_MASK)))
        return fail(image, "instrument flags", base + PC_I_FLAGS);
    if (kind == 3) return fail(image, "instrument kind 3", base + PC_I_FLAGS);
    if (kind == PC_KIND_TONE) {
        if (p[PC_I_WAVE] >= info->waves) return fail(image, "tone instrument names a wave the image does not have", base + PC_I_WAVE);
    } else if (p[PC_I_WAVE]) {
        return fail(image, "wave byte of a noise or sample instrument", base + PC_I_WAVE);
    }
    if (kind == PC_KIND_SAMPLE) {
        if (p[PC_I_SAMPLE] >= info->samples) return fail(image, "sample instrument names a sample the image does not have", base + PC_I_SAMPLE);
        if (p[PC_I_ROOT] < PC_NOTE_MIN || p[PC_I_ROOT] > PC_NOTE_MAX) return fail(image, "sample root note outside 9..95", base + PC_I_ROOT);
        if (p[PC_I_LOOP] > 1) return fail(image, "sample loop flag must be 0 or 1", base + PC_I_LOOP);
        const uint32_t length = pc_u32(b + PC_OFF_SAMPLES + PC_SAMPLE_ENTRY_SIZE * p[PC_I_SAMPLE] + 4);
        const int loop_start = pc_u16(p + PC_I_LOOP_START);
        if (p[PC_I_LOOP] ? (uint32_t)loop_start >= length : loop_start != 0)
            return fail(image, "sample loop start past the sample (or set without loop)", base + PC_I_LOOP_START);
    } else if (p[PC_I_SAMPLE] || p[PC_I_ROOT] || p[PC_I_LOOP] || pc_u16(p + PC_I_LOOP_START)) {
        return fail(image, "sample fields of a tone or noise instrument", base + PC_I_SAMPLE);
    }
    if (p[5]) return fail(image, "instrument reserved byte 5", base + 5);
    for (int k = 0; k < PC_ENVELOPE_COUNT; ++k)
        if (check_envelope(image, base, kind, k, size)) return -1;
    return 0;
}

static const uint8_t* record(const PceImage* image, int slot) {
    if (slot < 1 || slot > image->info.instruments) return NULL;
    const uint8_t* rec = image->bytes + PC_OFF_INSTRUMENTS + PC_INSTRUMENT_SIZE * (slot - 1);
    return (rec[PC_I_FLAGS] & PC_FLAG_DEFINED) ? rec : NULL;
}

int pc_load_image(PceImage* image) {
    const uint8_t* b = image->bytes;
    PcInfo* info = &image->info;
    memset(info, 0, sizeof *info);
    info->problem_offset = -1;
    if (memcmp(b, "TTPC", 4)) return fail(image, "not a PC Engine image (magic)", 0);
    if (b[PC_H_LAYOUT] != PC_LAYOUT) return fail(image, "unknown layout", PC_H_LAYOUT);
    info->multispeed = b[PC_H_MULTISPEED];
    if (info->multispeed < 1 || info->multispeed > PC_MAX_MULTISPEED) return fail(image, "multispeed must be 1..8", PC_H_MULTISPEED);
    info->speed = b[PC_H_SPEED];
    if (!info->speed) return fail(image, "speed must be 1..255", PC_H_SPEED);
    info->instruments = b[PC_H_INSTRUMENTS];
    if (info->instruments > PC_MAX_INSTRUMENTS) return fail(image, "instrument count above 64", PC_H_INSTRUMENTS);
    info->orders = pc_u16(b + PC_H_ORDERS);
    if (info->orders < 1 || info->orders > PC_MAX_ORDERS) return fail(image, "order count must be 1..256", PC_H_ORDERS);
    info->loop = pc_u16(b + PC_H_LOOP);
    if (info->loop >= info->orders) return fail(image, "loop order past the last order", PC_H_LOOP);
    info->waves = b[PC_H_WAVES];
    if (info->waves > PC_MAX_WAVES) return fail(image, "wave count above 64", PC_H_WAVES);
    info->samples = b[PC_H_SAMPLES];
    if (info->samples > PC_MAX_SAMPLES) return fail(image, "sample count above 64", PC_H_SAMPLES);
    if (!lfo_ctrl_ok(b[PC_H_LFO_CTRL])) return fail(image, "LFO control must be 00..03 or 80..83", PC_H_LFO_CTRL);
    if (b[PC_H_TIMER] > PC_MAX_TIMER) return fail(image, "timer reload above 127", PC_H_TIMER);
    if (b[18] || b[19]) return fail(image, "header reserved bytes 18..19", 18);
    const uint32_t size = pc_u32(b + PC_H_DATA_SIZE);
    if (size > PC_DATA_CAPACITY) return fail(image, "data size above the region", PC_H_DATA_SIZE);
    if (!zero(b + 24, PC_HEADER_SIZE - 24)) return fail(image, "header reserved bytes 24..63", 24);

    for (int o = info->orders; o < PC_MAX_ORDERS; ++o) {
        if (b[PC_OFF_ROWS + o]) return fail(image, "rows of an order past the order count", PC_OFF_ROWS + o);
        if (!zero(b + PC_OFF_ORDERS + 2 * PC_CHANNELS * o, 2 * PC_CHANNELS))
            return fail(image, "patterns of an order past the order count", PC_OFF_ORDERS + 2 * PC_CHANNELS * o);
    }
    for (int o = 0; o < info->orders; ++o) info->rows[o] = b[PC_OFF_ROWS + o] ? b[PC_OFF_ROWS + o] : 256;

    for (int w = 0; w < PC_MAX_WAVES; ++w) {
        const int at = PC_OFF_WAVES + PC_WAVE_SIZE * w;
        if (w >= info->waves) {
            if (!zero(b + at, PC_WAVE_SIZE)) return fail(image, "wave past the wave count", at);
            continue;
        }
        for (int i = 0; i < PC_WAVE_SIZE; ++i)
            if (b[at + i] > PC_WAVE_MAX) return fail(image, "wave sample above 31", at + i);
    }
    for (int s = 0; s < PC_MAX_SAMPLES; ++s) {
        const int at = PC_OFF_SAMPLES + PC_SAMPLE_ENTRY_SIZE * s;
        if (s >= info->samples) {
            if (!zero(b + at, PC_SAMPLE_ENTRY_SIZE)) return fail(image, "sample entry past the sample count", at);
            continue;
        }
        const uint32_t offset = pc_u32(b + at), length = pc_u32(b + at + 4);
        if (length < 1) return fail(image, "sample without frames", at + 4);
        if (length > PC_MAX_SAMPLE_FRAMES) return fail(image, "sample longer than 65535 frames", at + 4);
        if (offset > size || length > size - offset) return fail(image, "sample runs past the data size", at);
        for (uint32_t i = 0; i < length; ++i)
            if (b[PC_OFF_DATA + offset + i] > PC_WAVE_MAX) return fail(image, "sample frame above 31", (int)(PC_OFF_DATA + offset + i));
    }
    for (int n = 1; n <= PC_MAX_INSTRUMENTS; ++n)
        if (check_instrument(image, n, size)) return -1;

    /* Patterns: every (order, channel) decodes rows(o) rows inside the used data. */
    int speed = info->speed;
    for (int o = 0; o < info->orders; ++o) {
        info->start_speed[o] = (uint8_t)speed;
        int offsets[PC_CHANNELS], runs[PC_CHANNELS];
        for (int c = 0; c < PC_CHANNELS; ++c) {
            offsets[c] = pc_u16(b + PC_OFF_ORDERS + 2 * (PC_CHANNELS * o + c));
            runs[c] = 0;
            if ((uint32_t)offsets[c] >= size) return fail(image, "pattern offset past the data size", PC_OFF_ORDERS + 2 * (PC_CHANNELS * o + c));
        }
        for (int r = 0; r < info->rows[o]; ++r) {
            for (int c = 0; c < PC_CHANNELS; ++c) {
                if (runs[c]) { --runs[c]; continue; }
                int at = offsets[c];
                if ((uint32_t)at >= size) return fail(image, "pattern runs past the data size", PC_OFF_DATA + at);
                const int m = b[PC_OFF_DATA + at++];
                if (m & 0x80) { runs[c] = m & 0x7F; offsets[c] = at; continue; }
                const int effects = (m >> 2) & 7;
                if (!m || (m & 0x60) || effects > PC_MAX_EFFECTS) return fail(image, "row mask byte", PC_OFF_DATA + at - 1);
                const int need = (m & 1) + ((m >> 1) & 1) + 2 * effects;
                if ((uint32_t)(at + need) > size) return fail(image, "row runs past the data size", PC_OFF_DATA + at);
                if (m & 1) {
                    const int note = b[PC_OFF_DATA + at];
                    if ((note < PC_NOTE_MIN || note > PC_NOTE_MAX) && note != PC_NOTE_OFF) return fail(image, "note byte", PC_OFF_DATA + at);
                    ++at;
                }
                if (m & 2) {
                    const uint8_t* rec = record(image, b[PC_OFF_DATA + at]);
                    if (!rec) return fail(image, "row names an instrument the image does not define", PC_OFF_DATA + at);
                    if (c < PC_NOISE_FIRST && (rec[PC_I_FLAGS] & PC_KIND_MASK) == PC_KIND_NOISE)
                        return fail(image, "noise instrument off PSG 5-6 (only channels 5 and 6 have noise)", PC_OFF_DATA + at);
                    ++at;
                }
                for (int e = 0; e < effects; ++e, at += 2) {
                    const int code = b[PC_OFF_DATA + at], param = b[PC_OFF_DATA + at + 1];
                    const char* why = "effect code above Z";
                    if (code > 35 || effect_problem(info, code, param, &why)) return fail(image, why, PC_OFF_DATA + at);
                    if (code == PC_FX_SPEED) speed = param; /* rows, then channels, then columns: the last F wins */
                }
                offsets[c] = at;
            }
        }
    }
    info->loaded = PC_LOADED;
    return 0;
}

void pc_next_row(const uint8_t* bytes, PcCursor* cursor, PcRow* row) {
    row->note = -1;
    row->instrument = 0;
    row->effects = 0;
    if (cursor->run) {
        --cursor->run;
        return;
    }
    const uint8_t* p = bytes + PC_OFF_DATA + cursor->offset;
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

void pc_read_row(const PceImage* image, int order, int channel, int row, PcRow* out, PcCursor* after) {
    PcCursor c = {pc_u16(image->bytes + PC_OFF_ORDERS + 2 * (PC_CHANNELS * order + channel)), 0};
    for (int r = 0; r < row; ++r) pc_next_row(image->bytes, &c, out);
    pc_next_row(image->bytes, &c, out);
    if (after) *after = c;
}
