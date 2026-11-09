/* SAA1099 image validation and row decoding (saa_image.h; layout in docs/AUDIO_ENGINE.md "SAA1099: Image layout").
 * Every rejection is `return reject(image, "reason", offset);` with a literal: tools/audio/prepare_saa.py collects them
 * (in source order) into SaaPlayerImage.LOADER_PROBLEMS, which the Kotlin validator mirrors. */
#include "saa_image.h"

#include <string.h>

_Static_assert(SA_DATA_CAPACITY == SA_IMAGE_SIZE - SA_OFF_DATA, "the data region ends the image");
_Static_assert(SA_OFF_ORDERS == SA_OFF_ROWS + SA_MAX_ORDERS, "order table after the rows");
_Static_assert(SA_OFF_INSTRUMENTS == SA_OFF_ORDERS + 2 * SA_MAX_CHANNELS * SA_MAX_ORDERS, "instruments after the orders");
_Static_assert(SA_OFF_DATA == SA_OFF_INSTRUMENTS + SA_INSTRUMENT_SIZE * SA_MAX_INSTRUMENTS, "data after the instruments");

static int reject(SaaImage* image, const char* problem, int offset) {
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

static int defined(const SaaImage* image, int slot) {
    return slot >= 1 && slot <= image->info.instruments && (sa_instrument(image, slot)[SA_I_FLAGS] & SA_FLAG_DEFINED);
}

/* The envelope referenced by field `which` of the record at `base` (absolute image offsets). */
static int check_envelope(SaaImage* image, int base, int which, int size) {
    const uint8_t* b = image->bytes;
    const int field = base + which;
    const int off = sa_u16(b + field);
    if (off == SA_ENV_NONE) return 0;
    if (off >= size) return reject(image, "envelope offset past the data size", field);
    const int at = SA_OFF_DATA + off;
    if (off + SA_ENV_HEADER > size) return reject(image, "envelope runs past the data size", at);
    const int length = sa_u16(b + at + SA_ENV_LENGTH);
    const int loop = sa_u16(b + at + SA_ENV_LOOP);
    const int release = sa_u16(b + at + SA_ENV_RELEASE);
    if (length < 1 || length > SA_MAX_ENVELOPE) return reject(image, "envelope length must be 1..256", at + SA_ENV_LENGTH);
    if (off + SA_ENV_HEADER + length > size) return reject(image, "envelope runs past the data size", at);
    if (loop != SA_ENV_NONE && loop >= length) return reject(image, "envelope loop point past its length", at + SA_ENV_LOOP);
    if (release != SA_ENV_NONE) {
        if (which != SA_I_VOLUME) return reject(image, "envelope release point on a non-volume envelope", at + SA_ENV_RELEASE);
        if (release < 1 || release >= length)
            return reject(image, "envelope release point must be 1..length-1", at + SA_ENV_RELEASE);
        if (loop != SA_ENV_NONE && loop >= release)
            return reject(image, "envelope loop point must come before its release point", at + SA_ENV_LOOP);
    }
    const uint8_t* values = b + at + SA_ENV_HEADER;
    for (int i = 0; i < length; ++i) {
        const int v = values[i], s = (int8_t)values[i];
        const int where = at + SA_ENV_HEADER + i;
        switch (which) {
        case SA_I_VOLUME:
            if (v > 15) return reject(image, "envelope volume value above 15", where);
            break;
        case SA_I_ARPEGGIO:
            if (s < -SA_ARPEGGIO_LIMIT || s >= SA_ARPEGGIO_LIMIT)
                return reject(image, "envelope arpeggio value outside -64..63", where);
            break;
        case SA_I_PITCH:
            if (s < -SA_PITCH_ENV_LIMIT || s >= SA_PITCH_ENV_LIMIT)
                return reject(image, "envelope pitch value outside -64..63", where);
            break;
        default:
            if (v > 3) return reject(image, "envelope mixer value above 3", where);
            break;
        }
    }
    return 0;
}

static int check_instrument(SaaImage* image, int slot, int size) {
    const int base = SA_OFF_INSTRUMENTS + SA_INSTRUMENT_SIZE * (slot - 1);
    const uint8_t* p = image->bytes + base;
    const int any = nonzero(p, SA_INSTRUMENT_SIZE);
    if (any < 0) {
        if (slot == image->info.instruments) return reject(image, "instrument count names an empty slot", SA_H_INSTRUMENTS);
        return 0;
    }
    if (slot > image->info.instruments) return reject(image, "instrument record past the instrument count", base + any);
    const int flags = p[SA_I_FLAGS];
    if (!(flags & SA_FLAG_DEFINED) || (flags & ~(SA_FLAG_DEFINED | SA_FLAG_PITCH_RELATIVE | SA_FLAG_ENV_RETRIGGER)))
        return reject(image, "instrument flags", base + SA_I_FLAGS);
    if (p[SA_I_NOISE] > 3) return reject(image, "instrument noise clock above 3", base + SA_I_NOISE);
    const int env = p[SA_I_ENVELOPE];
    if (env && (!(env & SA_ENV_ENABLE) || (env & 0x40)))
        return reject(image, "instrument hardware envelope must be 0 or 80..BF", base + SA_I_ENVELOPE);
    if ((flags & SA_FLAG_ENV_RETRIGGER) && !env)
        return reject(image, "instrument retrigger without a hardware envelope", base + SA_I_FLAGS);
    const int rate = p[SA_I_RATE];
    if (env & SA_ENV_EXTERNAL) {
        if (!rate) return reject(image, "instrument external envelope rate must be 1..255", base + SA_I_RATE);
    } else if (rate) {
        return reject(image, "instrument envelope rate without an external envelope", base + SA_I_RATE);
    }
    if (p[5]) return reject(image, "instrument reserved byte 5", base + 5);
    const int reserved = nonzero(p + 14, SA_INSTRUMENT_SIZE - 14);
    if (reserved >= 0) return reject(image, "instrument reserved bytes 14..15", base + 14 + reserved);
    static const int which[4] = {SA_I_VOLUME, SA_I_ARPEGGIO, SA_I_PITCH, SA_I_MIXER};
    for (int k = 0; k < 4; ++k)
        if (check_envelope(image, base, which[k], size)) return -1;
    return 0;
}

int sa_load_image(SaaImage* image) {
    const uint8_t* b = image->bytes;
    SaInfo* info = &image->info;
    memset(info, 0, sizeof *info);
    info->problem_offset = -1;
    if (memcmp(b, SA_MAGIC, 4)) return reject(image, "not an SAA1099 image (magic)", SA_H_MAGIC);
    if (b[SA_H_LAYOUT] != SA_LAYOUT) return reject(image, "unknown layout", SA_H_LAYOUT);
    info->variant = b[SA_H_VARIANT];
    if (info->variant != SA_VARIANT_SAM && info->variant != SA_VARIANT_CMS)
        return reject(image, "variant must be 0 (SAM Coupe) or 1 (CMS)", SA_H_VARIANT);
    info->multispeed = b[SA_H_MULTISPEED];
    if (info->multispeed < 1 || info->multispeed > SA_MAX_MULTISPEED)
        return reject(image, "multispeed must be 1..8", SA_H_MULTISPEED);
    info->speed = b[SA_H_SPEED];
    if (!info->speed) return reject(image, "speed must be 1..255", SA_H_SPEED);
    info->orders = sa_u16(b + SA_H_ORDERS);
    if (info->orders < 1 || info->orders > SA_MAX_ORDERS) return reject(image, "order count must be 1..256", SA_H_ORDERS);
    info->loop = sa_u16(b + SA_H_LOOP);
    if (info->loop >= info->orders) return reject(image, "loop order past the last order", SA_H_LOOP);
    info->instruments = b[SA_H_INSTRUMENTS];
    if (info->instruments > SA_MAX_INSTRUMENTS) return reject(image, "instrument count above 64", SA_H_INSTRUMENTS);
    info->channels = b[SA_H_CHANNELS];
    if (info->channels != (info->variant == SA_VARIANT_CMS ? SA_CHANNELS_CMS : SA_CHANNELS_SAM))
        return reject(image, "channel count must be 6 (SAM Coupe) or 12 (CMS), as the variant", SA_H_CHANNELS);
    info->chips = info->channels / SA_CHIP_CHANNELS;
    const int size = sa_u16(b + SA_H_DATA_SIZE);
    if (size > SA_DATA_CAPACITY) return reject(image, "data size above the region", SA_H_DATA_SIZE);
    const int header = nonzero(b + 16, SA_HEADER_SIZE - 16);
    if (header >= 0) return reject(image, "header reserved bytes 16..63", 16 + header);

    for (int o = 0; o < SA_MAX_ORDERS; ++o) {
        const int at = SA_OFF_ORDERS + 2 * SA_MAX_CHANNELS * o;
        if (o >= info->orders) {
            if (b[SA_OFF_ROWS + o]) return reject(image, "rows of an order past the order count", SA_OFF_ROWS + o);
            const int any = nonzero(b + at, 2 * SA_MAX_CHANNELS);
            if (any >= 0) return reject(image, "patterns of an order past the order count", at + any);
        } else {
            const int any = nonzero(b + at + 2 * info->channels, 2 * (SA_MAX_CHANNELS - info->channels));
            if (any >= 0) return reject(image, "pattern of a channel past the channel count", at + 2 * info->channels + any);
        }
    }
    for (int o = 0; o < info->orders; ++o) info->rows[o] = b[SA_OFF_ROWS + o] ? b[SA_OFF_ROWS + o] : 256;
    for (int n = 1; n <= SA_MAX_INSTRUMENTS; ++n)
        if (check_instrument(image, n, size)) return -1;

    /* Patterns: every (order, channel) decodes rows(o) rows inside the used data. */
    int speed = info->speed;
    for (int o = 0; o < info->orders; ++o) {
        info->start_speed[o] = (uint8_t)speed;
        int offsets[SA_MAX_CHANNELS], runs[SA_MAX_CHANNELS];
        for (int c = 0; c < info->channels; ++c) {
            const int field = SA_OFF_ORDERS + 2 * (SA_MAX_CHANNELS * o + c);
            offsets[c] = sa_u16(b + field);
            runs[c] = 0;
            if (offsets[c] >= size) return reject(image, "pattern offset past the data size", field);
        }
        for (int r = 0; r < info->rows[o]; ++r) {
            for (int c = 0; c < info->channels; ++c) {
                if (runs[c]) {
                    --runs[c];
                    continue;
                }
                int at = offsets[c];
                if (at >= size) return reject(image, "pattern runs past the data size", SA_OFF_DATA + at);
                const int m = b[SA_OFF_DATA + at++];
                if (m & SA_RUN) {
                    runs[c] = m & 0x7F;
                    offsets[c] = at;
                    continue;
                }
                const int effects = (m >> SA_MASK_EFFECT_SHIFT) & 7;
                if (!m || (m & 0x60) || effects > SA_MAX_EFFECTS) return reject(image, "row mask byte", SA_OFF_DATA + at - 1);
                const int need = (m & SA_MASK_NOTE) + ((m >> 1) & 1) + 2 * effects;
                if (at + need > size) return reject(image, "row runs past the data size", SA_OFF_DATA + at);
                if (m & SA_MASK_NOTE) {
                    const int note = b[SA_OFF_DATA + at];
                    if (note >= SA_NOTES && note != SA_NOTE_OFF) return reject(image, "note byte", SA_OFF_DATA + at);
                    ++at;
                }
                if (m & SA_MASK_INSTRUMENT) {
                    if (!defined(image, b[SA_OFF_DATA + at]))
                        return reject(image, "row names an instrument the image does not define", SA_OFF_DATA + at);
                    ++at;
                }
                for (int e = 0; e < effects; ++e, at += 2) {
                    const int code = b[SA_OFF_DATA + at], param = b[SA_OFF_DATA + at + 1];
                    const int where = SA_OFF_DATA + at, param_at = where + 1;
                    switch (code) {
                    case SA_FX_ARPEGGIO: case SA_FX_SLIDE_UP: case SA_FX_SLIDE_DOWN: case SA_FX_PORTAMENTO:
                    case SA_FX_VIBRATO: case SA_FX_PAN: case SA_FX_VOLUME_SLIDE: case SA_FX_BREAK: case SA_FX_DELAY:
                    case SA_FX_FINE: case SA_FX_CUT:
                        break;
                    case SA_FX_JUMP:
                        if (param >= info->orders) return reject(image, "Bxx jumps past the last order", param_at);
                        break;
                    case SA_FX_VOLUME:
                        if (param > 0x0F) return reject(image, "Cxx volume above 0F", param_at);
                        break;
                    case SA_FX_ENVELOPE:
                        if (param && (param < 0x80 || param > 0xBF))
                            return reject(image, "Exx hardware envelope must be 00 or 80..BF", param_at);
                        break;
                    case SA_FX_SPEED:
                        if (!param) return reject(image, "F00: speed must be 1..FF", param_at);
                        speed = param; /* rows, then channels, then columns: the last F wins */
                        break;
                    case SA_FX_MIXER:
                        if (param > 3) return reject(image, "Mxx mixer above 03", param_at);
                        break;
                    case SA_FX_NOISE:
                        if (param > 3) return reject(image, "Nxx noise clock above 03", param_at);
                        break;
                    case SA_FX_SYNC:
                        if (param > 3) return reject(image, "Yxx above 03", param_at);
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
    info->loaded = SA_LOADED;
    return 0;
}

void sa_next_row(const uint8_t* bytes, SaCursor* cursor, SaRow* row) {
    row->note = -1;
    row->instrument = 0;
    row->effects = 0;
    if (cursor->run) {
        --cursor->run;
        return;
    }
    const uint8_t* p = bytes + SA_OFF_DATA + cursor->offset;
    int at = 0;
    const int m = p[at++];
    if (m & SA_RUN) {
        cursor->run = m & 0x7F;
        cursor->offset += 1;
        return;
    }
    if (m & SA_MASK_NOTE) row->note = p[at++];
    if (m & SA_MASK_INSTRUMENT) row->instrument = p[at++];
    row->effects = (m >> SA_MASK_EFFECT_SHIFT) & 7;
    for (int e = 0; e < row->effects; ++e) {
        row->code[e] = p[at++];
        row->param[e] = p[at++];
    }
    cursor->offset += at;
}

void sa_read_row(const SaaImage* image, int order, int channel, int row, SaRow* out, SaCursor* after) {
    SaCursor c = {sa_pattern(image, order, channel), 0};
    for (int r = 0; r < row; ++r) sa_next_row(image->bytes, &c, out);
    sa_next_row(image->bytes, &c, out);
    if (after) *after = c;
}

void sa_envelope(const SaaImage* image, int slot, int which, SaEnvelope* out) {
    const uint8_t* rec = sa_instrument(image, slot);
    const int off = sa_u16(rec + which);
    if (off == SA_ENV_NONE) {
        out->values = NULL;
        out->length = 0;
        out->loop = out->release = -1;
        return;
    }
    const uint8_t* e = image->bytes + SA_OFF_DATA + off;
    const int loop = sa_u16(e + SA_ENV_LOOP), release = sa_u16(e + SA_ENV_RELEASE);
    out->values = e + SA_ENV_HEADER;
    out->length = sa_u16(e + SA_ENV_LENGTH);
    out->loop = loop == SA_ENV_NONE ? -1 : loop;
    out->release = release == SA_ENV_NONE ? -1 : release;
}
