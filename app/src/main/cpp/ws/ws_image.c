/* WonderSwan image validation and row decoding (ws_image.h; layout in docs/AUDIO_ENGINE.md "WonderSwan"). Checks run
 * in this order: header, the order tables, waves, sample entries, instruments, patterns. Every reason is a string
 * literal passed as fail(image, "...", offset) or assigned as why = "..." (tools/audio/prepare_ws.py collects them
 * into WsPlayerImage.LOADER_PROBLEMS). */
#include "ws_image.h"

#include <string.h>

static int fail(WsImage* image, const char* problem, int offset) {
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

/* An effect code's parameter and lane rules. Returns 1 (with *why) when the effect is not allowed. */
static int effect_problem(const WsInfo* info, int lane, int code, int param, const char** why) {
    switch (code) {
    case WS_FX_ARPEGGIO: case WS_FX_SLIDE_UP: case WS_FX_SLIDE_DOWN: case WS_FX_PORTAMENTO: case WS_FX_VIBRATO:
    case WS_FX_PAN: case WS_FX_VOLUME_SLIDE: case WS_FX_BREAK: case WS_FX_DELAY: case WS_FX_FINE: case WS_FX_CUT:
        return 0;
    case WS_FX_JUMP:
        *why = "Bxx jumps past the last order";
        return param >= info->orders;
    case WS_FX_VOLUME:
        *why = "Cxx volume above 0F";
        return param > 0x0F;
    case WS_FX_SPEED:
        *why = "F00: speed must be 1..FF";
        return param == 0;
    case WS_FX_TIMER:
        *why = "T00: the voice timer reload must be 1..FF";
        return param == 0;
    case WS_FX_HOLD:
        if (info->model != WS_MODEL_COLOR) {
            *why = "Hxx needs Sound DMA (WonderSwan Color only)";
            return 1;
        }
        if (lane != WS_VOICE_CHANNEL && lane != WS_HYPER_VOICE) {
            *why = "Hxx only on CH2 and Hyper Voice";
            return 1;
        }
        *why = "Hxx must be 00 or 01";
        return param > 1;
    case WS_FX_SHIFT:
        *why = "Kxx speaker shift above 03";
        return param > WS_MAX_SHIFT;
    case WS_FX_TAP:
        if (lane != WS_NOISE_CHANNEL) {
            *why = "Nxx only on CH4";
            return 1;
        }
        *why = "Nxx noise tap above 07";
        return param > WS_MAX_TAP;
    case WS_FX_SWEEP:
        *why = "Uxx only on CH3";
        return lane != WS_SWEEP_CHANNEL;
    case WS_FX_SWEEP_TIME:
        if (lane != WS_SWEEP_CHANNEL) {
            *why = "Vxx only on CH3";
            return 1;
        }
        *why = "Vxx sweep time above 1F";
        return param > WS_MAX_SWEEP_TIME;
    case WS_FX_WAVE:
        if (lane > WS_NOISE_CHANNEL) {
            *why = "Wxx only on CH1-CH4";
            return 1;
        }
        *why = "Wxx names a wave the image does not have";
        return param >= info->waves;
    default:
        *why = "unknown effect code";
        return 1;
    }
}

/* The sample entry of index s. */
static const uint8_t* entry(const WsImage* image, int s) {
    return image->bytes + WS_OFF_SAMPLES + WS_SAMPLE_ENTRY_SIZE * (s & (WS_MAX_SAMPLES - 1));
}

/* Envelope k of the instrument at `base`: range inside the data and values in range. */
static int check_envelope(WsImage* image, int base, int kind, int dma, int k, uint32_t size) {
    const uint8_t* b = image->bytes;
    const int at = base + WS_I_ENVELOPES + WS_ENVELOPE_BYTES * k;
    const int offset = ws_u16(b + at), length = b[at + 2], loop = b[at + 3], release = b[at + 4];
    if (b[at + 5]) return fail(image, "envelope reserved byte", at + 5);
    if (!length) {
        if (offset || loop != WS_ENV_NONE || release != WS_ENV_NONE)
            return fail(image, "empty envelope: offset 0, loop and release FF", at);
        return 0;
    }
    if (k == WS_ENV_WAVE && kind == WS_KIND_SAMPLE) return fail(image, "wave envelope on a sample instrument", at + 2);
    if (dma && (k == WS_ENV_ARPEGGIO || k == WS_ENV_PITCH))
        return fail(image, "arpeggio or pitch envelope on a DMA sample (its rate is fixed)", at + 2);
    if (loop != WS_ENV_NONE && loop >= length) return fail(image, "envelope loop past its length", at + 3);
    if (release != WS_ENV_NONE && (release < 1 || release >= length))
        return fail(image, "envelope release must be 1..length - 1", at + 4);
    if (loop != WS_ENV_NONE && release != WS_ENV_NONE && release <= loop)
        return fail(image, "envelope release must follow its loop", at + 4);
    if ((uint32_t)offset + (uint32_t)length > size) return fail(image, "envelope runs past the data size", at);
    for (int i = 0; i < length; ++i) {
        const int pos = WS_OFF_DATA + offset + i, v = b[pos], s = (int8_t)b[pos];
        if (k == WS_ENV_VOLUME && v > WS_WAVE_MAX) return fail(image, "volume envelope value above 15", pos);
        if (k == WS_ENV_ARPEGGIO && (s < -64 || s > 63)) return fail(image, "arpeggio envelope value outside -64..63", pos);
        if (k == WS_ENV_WAVE && kind == WS_KIND_TONE && v >= image->info.waves)
            return fail(image, "wave envelope names a wave the image does not have", pos);
        if (k == WS_ENV_WAVE && kind == WS_KIND_NOISE && v > WS_MAX_TAP) return fail(image, "tap envelope value above 7", pos);
    }
    return 0;
}

static int check_instrument(WsImage* image, int slot, uint32_t size) {
    const uint8_t* b = image->bytes;
    const int base = WS_OFF_INSTRUMENTS + WS_INSTRUMENT_SIZE * (slot - 1);
    const uint8_t* p = b + base;
    const WsInfo* info = &image->info;
    if (zero(p, WS_INSTRUMENT_SIZE)) return 0;
    if (slot > info->instruments) return fail(image, "instrument record past the instrument count", base);
    const int flags = p[WS_I_FLAGS], kind = flags & WS_KIND_MASK;
    if (!(flags & WS_FLAG_DEFINED) || (flags & 0x40)) return fail(image, "instrument flags", base + WS_I_FLAGS);
    if (kind == 3) return fail(image, "instrument kind 3", base + WS_I_FLAGS);
    const int dma = kind == WS_KIND_SAMPLE && (flags & WS_FLAG_DMA);
    if ((flags & WS_FLAG_DMA) && kind != WS_KIND_SAMPLE) return fail(image, "DMA flag on a tone or noise instrument", base + WS_I_FLAGS);
    if (dma && info->model != WS_MODEL_COLOR)
        return fail(image, "DMA sample on the original WonderSwan (Sound DMA is the Color's)", base + WS_I_FLAGS);
    if ((flags & WS_FLAG_LFSR_RESET) && kind != WS_KIND_NOISE)
        return fail(image, "LFSR reset flag on an instrument that is not noise", base + WS_I_FLAGS);
    if ((flags & WS_FLAG_REVERSE) && !dma) return fail(image, "reverse flag without DMA", base + WS_I_FLAGS);
    if (dma && (flags & WS_FLAG_RELATIVE)) return fail(image, "relative pitch flag on a DMA sample", base + WS_I_FLAGS);
    const int source = p[WS_I_SOURCE];
    if (kind == WS_KIND_TONE && source >= info->waves)
        return fail(image, "tone instrument names a wave the image does not have", base + WS_I_SOURCE);
    if (kind == WS_KIND_NOISE && source > WS_MAX_TAP) return fail(image, "noise tap mode above 7", base + WS_I_SOURCE);
    if (kind == WS_KIND_SAMPLE) {
        if (source >= info->samples)
            return fail(image, "sample instrument names a sample the image does not have", base + WS_I_SOURCE);
        const uint8_t* e = entry(image, source);
        const uint32_t frames = ws_u32(e + WS_S_FRAMES);
        const int loop_start = ws_u16(p + WS_I_LOOP_START);
        if (p[WS_I_LOOP] > 1) return fail(image, "sample loop flag must be 0 or 1", base + WS_I_LOOP);
        if (dma) {
            if (p[WS_I_ROOT] > WS_MAX_DMA_RATE) return fail(image, "DMA rate above 3", base + WS_I_ROOT);
            if (!(e[WS_S_FLAGS] & WS_SAMPLE_SIGNED))
                return fail(image, "DMA sample without a signed copy", base + WS_I_SOURCE);
            if (loop_start) return fail(image, "DMA sample loop start must be 0 (DMA loops the whole sample)", base + WS_I_LOOP_START);
        } else {
            if (p[WS_I_ROOT] < WS_NOTE_MIN || p[WS_I_ROOT] > WS_NOTE_MAX)
                return fail(image, "sample root note outside 19..95", base + WS_I_ROOT);
            if (p[WS_I_LOOP] ? (uint32_t)loop_start >= frames : loop_start != 0)
                return fail(image, "sample loop start past the sample (or set without loop)", base + WS_I_LOOP_START);
        }
    } else if (p[WS_I_ROOT] || p[WS_I_LOOP] || ws_u16(p + WS_I_LOOP_START)) {
        return fail(image, "sample fields of a tone or noise instrument", base + WS_I_ROOT);
    }
    if (kind != WS_KIND_TONE && (p[WS_I_SWEEP] || p[WS_I_SWEEP_TIME]))
        return fail(image, "sweep fields of a noise or sample instrument", base + WS_I_SWEEP);
    if (p[WS_I_SWEEP_TIME] > WS_MAX_SWEEP_TIME) return fail(image, "sweep time above 31", base + WS_I_SWEEP_TIME);
    for (int k = 0; k < WS_ENVELOPE_COUNT; ++k)
        if (check_envelope(image, base, kind, dma, k, size)) return -1;
    return 0;
}

static const uint8_t* record(const WsImage* image, int slot) {
    if (slot < 1 || slot > image->info.instruments) return NULL;
    const uint8_t* rec = image->bytes + WS_OFF_INSTRUMENTS + WS_INSTRUMENT_SIZE * (slot - 1);
    return (rec[WS_I_FLAGS] & WS_FLAG_DEFINED) ? rec : NULL;
}

/* The lane rule of a row's instrument: tone on lanes 0-3, noise on lane 3, samples on lane 1 (mono) and lane 4. */
static const char* fit_problem(const WsImage* image, int lane, const uint8_t* rec) {
    const int kind = rec[WS_I_FLAGS] & WS_KIND_MASK;
    const char* why = NULL;
    if (kind == WS_KIND_TONE && lane > WS_NOISE_CHANNEL) why = "tone instrument on the Hyper Voice lane";
    if (kind == WS_KIND_NOISE && lane != WS_NOISE_CHANNEL) why = "noise instrument off CH4";
    if (kind == WS_KIND_SAMPLE) {
        if (lane != WS_VOICE_CHANNEL && lane != WS_HYPER_VOICE) why = "sample instrument off CH2 and Hyper Voice";
        else if (lane == WS_VOICE_CHANNEL && (entry(image, rec[WS_I_SOURCE])[WS_S_FLAGS] & WS_SAMPLE_STEREO))
            why = "stereo sample on CH2 (only Hyper Voice plays stereo)";
    }
    return why;
}

int ws_load_image(WsImage* image) {
    const uint8_t* b = image->bytes;
    WsInfo* info = &image->info;
    memset(info, 0, sizeof *info);
    info->problem_offset = -1;
    if (memcmp(b, "TTWS", 4)) return fail(image, "not a WonderSwan image (magic)", 0);
    if (b[WS_H_LAYOUT] != WS_LAYOUT) return fail(image, "unknown layout", WS_H_LAYOUT);
    info->model = b[WS_H_MODEL];
    if (info->model != WS_MODEL_MONO && info->model != WS_MODEL_COLOR) return fail(image, "model must be 0 or 1", WS_H_MODEL);
    info->lanes = info->model == WS_MODEL_COLOR ? WS_CHANNELS : WS_CHANNELS_MONO;
    info->speed = b[WS_H_SPEED];
    if (!info->speed) return fail(image, "speed must be 1..255", WS_H_SPEED);
    info->instruments = b[WS_H_INSTRUMENTS];
    if (info->instruments > WS_MAX_INSTRUMENTS) return fail(image, "instrument count above 64", WS_H_INSTRUMENTS);
    info->orders = ws_u16(b + WS_H_ORDERS);
    if (info->orders < 1 || info->orders > WS_MAX_ORDERS) return fail(image, "order count must be 1..256", WS_H_ORDERS);
    info->loop = ws_u16(b + WS_H_LOOP);
    if (info->loop >= info->orders) return fail(image, "loop order past the last order", WS_H_LOOP);
    info->waves = b[WS_H_WAVES];
    if (info->waves > WS_MAX_WAVES) return fail(image, "wave count above 64", WS_H_WAVES);
    info->samples = b[WS_H_SAMPLES];
    if (info->samples > WS_MAX_SAMPLES) return fail(image, "sample count above 64", WS_H_SAMPLES);
    info->timer = b[WS_H_TIMER];
    if (info->timer < 1) return fail(image, "voice timer reload must be 1..255", WS_H_TIMER);
    info->shift = b[WS_H_SHIFT];
    if (info->shift > WS_MAX_SHIFT) return fail(image, "speaker shift above 3", WS_H_SHIFT);
    info->output = b[WS_H_OUTPUT];
    if (info->output > WS_OUTPUT_SPEAKER) return fail(image, "output must be 0 (headphones) or 1 (speaker)", WS_H_OUTPUT);
    if (!zero(b + 17, 3)) return fail(image, "header reserved bytes 17..19", 17);
    const uint32_t size = ws_u32(b + WS_H_DATA_SIZE);
    if (size > WS_DATA_CAPACITY) return fail(image, "data size above the region", WS_H_DATA_SIZE);
    info->data_size = size;
    if (!zero(b + 24, WS_HEADER_SIZE - 24)) return fail(image, "header reserved bytes 24..63", 24);
    if (!zero(b + WS_OFF_SAMPLES + WS_SAMPLE_ENTRY_SIZE * WS_MAX_SAMPLES, WS_OFF_DATA - WS_OFF_SAMPLES - WS_SAMPLE_ENTRY_SIZE * WS_MAX_SAMPLES))
        return fail(image, "reserved bytes 8000..8191", WS_OFF_SAMPLES + WS_SAMPLE_ENTRY_SIZE * WS_MAX_SAMPLES);

    for (int o = 0; o < WS_MAX_ORDERS; ++o) {
        const int at = WS_OFF_ORDERS + 2 * WS_CHANNELS * o;
        if (o >= info->orders) {
            if (b[WS_OFF_ROWS + o]) return fail(image, "rows of an order past the order count", WS_OFF_ROWS + o);
            if (!zero(b + at, 2 * WS_CHANNELS)) return fail(image, "patterns of an order past the order count", at);
            continue;
        }
        info->rows[o] = b[WS_OFF_ROWS + o] ? b[WS_OFF_ROWS + o] : 256;
        if (info->model == WS_MODEL_MONO && ws_u16(b + at + 2 * WS_HYPER_VOICE))
            return fail(image, "Hyper Voice pattern on the original WonderSwan", at + 2 * WS_HYPER_VOICE);
    }

    for (int w = 0; w < WS_MAX_WAVES; ++w) {
        const int at = WS_OFF_WAVES + WS_WAVE_SIZE * w;
        if (w >= info->waves) {
            if (!zero(b + at, WS_WAVE_SIZE)) return fail(image, "wave past the wave count", at);
            continue;
        }
        for (int i = 0; i < WS_WAVE_SIZE; ++i)
            if (b[at + i] > WS_WAVE_MAX) return fail(image, "wave sample above 15", at + i);
    }
    for (int s = 0; s < WS_MAX_SAMPLES; ++s) {
        const int at = WS_OFF_SAMPLES + WS_SAMPLE_ENTRY_SIZE * s;
        const uint8_t* e = b + at;
        if (s >= info->samples) {
            if (!zero(e, WS_SAMPLE_ENTRY_SIZE)) return fail(image, "sample entry past the sample count", at);
            continue;
        }
        const uint32_t offset = ws_u32(e + WS_S_OFFSET), frames = ws_u32(e + WS_S_FRAMES);
        const uint32_t signed_offset = ws_u32(e + WS_S_SIGNED_OFFSET);
        const int flags = e[WS_S_FLAGS];
        if (frames < 1) return fail(image, "sample without frames", at + WS_S_FRAMES);
        if (frames > WS_MAX_SAMPLE_FRAMES) return fail(image, "sample longer than 65535 frames", at + WS_S_FRAMES);
        if (flags & ~(WS_SAMPLE_STEREO | WS_SAMPLE_SIGNED)) return fail(image, "sample flags", at + WS_S_FLAGS);
        if (!zero(e + WS_S_FLAGS + 1, 3)) return fail(image, "sample entry reserved bytes", at + WS_S_FLAGS + 1);
        const uint32_t bytes = frames * ((flags & WS_SAMPLE_STEREO) ? 2u : 1u);
        if (offset > size || bytes > size - offset) return fail(image, "sample runs past the data size", at + WS_S_OFFSET);
        if (flags & WS_SAMPLE_SIGNED) {
            if (signed_offset > size || bytes > size - signed_offset)
                return fail(image, "signed sample copy runs past the data size", at + WS_S_SIGNED_OFFSET);
        } else if (signed_offset) {
            return fail(image, "signed copy offset without the signed flag", at + WS_S_SIGNED_OFFSET);
        }
    }
    for (int n = 1; n <= WS_MAX_INSTRUMENTS; ++n)
        if (check_instrument(image, n, size)) return -1;

    /* Patterns: every (order, lane) decodes rows(o) rows inside the used data. */
    const int lanes = info->lanes;
    int speed = info->speed;
    for (int o = 0; o < info->orders; ++o) {
        info->start_speed[o] = (uint8_t)speed;
        int offsets[WS_CHANNELS], runs[WS_CHANNELS];
        for (int c = 0; c < lanes; ++c) {
            offsets[c] = ws_u16(b + WS_OFF_ORDERS + 2 * (WS_CHANNELS * o + c));
            runs[c] = 0;
            if ((uint32_t)offsets[c] >= size)
                return fail(image, "pattern offset past the data size", WS_OFF_ORDERS + 2 * (WS_CHANNELS * o + c));
        }
        for (int r = 0; r < info->rows[o]; ++r) {
            for (int c = 0; c < lanes; ++c) {
                if (runs[c]) {
                    --runs[c];
                    continue;
                }
                int at = offsets[c];
                if ((uint32_t)at >= size) return fail(image, "pattern runs past the data size", WS_OFF_DATA + at);
                const int m = b[WS_OFF_DATA + at++];
                if (m & 0x80) {
                    runs[c] = m & 0x7F;
                    offsets[c] = at;
                    continue;
                }
                const int effects = (m >> 2) & 7;
                if (!m || (m & 0x60) || effects > WS_MAX_EFFECTS) return fail(image, "row mask byte", WS_OFF_DATA + at - 1);
                const int need = (m & 1) + ((m >> 1) & 1) + 2 * effects;
                if ((uint32_t)(at + need) > size) return fail(image, "row runs past the data size", WS_OFF_DATA + at);
                if (m & 1) {
                    const int note = b[WS_OFF_DATA + at];
                    if ((note < WS_NOTE_MIN || note > WS_NOTE_MAX) && note != WS_NOTE_OFF)
                        return fail(image, "note byte", WS_OFF_DATA + at);
                    ++at;
                }
                if (m & 2) {
                    const uint8_t* rec = record(image, b[WS_OFF_DATA + at]);
                    if (!rec) return fail(image, "row names an instrument the image does not define", WS_OFF_DATA + at);
                    const char* why = fit_problem(image, c, rec);
                    if (why) return fail(image, why, WS_OFF_DATA + at);
                    ++at;
                }
                for (int e = 0; e < effects; ++e, at += 2) {
                    const int code = b[WS_OFF_DATA + at], param = b[WS_OFF_DATA + at + 1];
                    const char* why = "effect code above Z";
                    if (code > 35 || effect_problem(info, c, code, param, &why)) return fail(image, why, WS_OFF_DATA + at);
                    if (code == WS_FX_SPEED) speed = param; /* rows, then lanes, then columns: the last F wins */
                }
                offsets[c] = at;
            }
        }
    }
    info->loaded = WS_LOADED;
    return 0;
}

void ws_next_row(const uint8_t* bytes, WsCursor* cursor, WsRow* row) {
    row->note = -1;
    row->instrument = 0;
    row->effects = 0;
    if (cursor->run) {
        --cursor->run;
        return;
    }
    const uint8_t* p = bytes + WS_OFF_DATA + cursor->offset;
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

void ws_read_row(const WsImage* image, int order, int lane, int row, WsRow* out, WsCursor* after) {
    WsCursor c = {ws_u16(image->bytes + WS_OFF_ORDERS + 2 * (WS_CHANNELS * order + lane)), 0};
    for (int r = 0; r < row; ++r) ws_next_row(image->bytes, &c, out);
    ws_next_row(image->bytes, &c, out);
    if (after) *after = c;
}
