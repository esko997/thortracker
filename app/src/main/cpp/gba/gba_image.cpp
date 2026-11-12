#include "gba_image.h"
#include <cstring>

namespace {
uint16_t u16(const uint8_t* p) { return p[0] | uint16_t(p[1]) << 8; }
uint32_t u32(const uint8_t* p) { return u16(p) | uint32_t(u16(p + 2)) << 16; }
bool span(uint32_t p, uint32_t n, uint32_t end) { return p <= end && n <= end - p; }
bool aligned(uint32_t p) { return (p & 3) == 0; }
// MAS block size excludes its eight-byte prefix, exactly as mmutil writes it.
bool block(const uint8_t* b, uint32_t p, unsigned type, uint32_t end, uint32_t* limit) {
    if (!aligned(p) || !span(p, 8, end) || b[p + 4] != type || b[p + 5] != 0x18 ||
        !span(p + 8, u32(b + p), end)) return false;
    *limit = p + 8 + u32(b + p);
    return true;
}
bool envelope(const uint8_t* b, uint32_t* pos, uint32_t end, bool pan) {
    uint32_t p = *pos;
    if (!span(p, 8, end)) return false;
    unsigned n = b[p + 5], size = b[p];
    if (!n || n > 12 || size != 8 + n * 4 || !span(p, size, end) || b[p + 6]) return false;
    for (unsigned i = 1; i <= 4; ++i) if (b[p + i] != 255 && b[p + i] >= n) return false;
    if ((b[p + 1] == 255) != (b[p + 2] == 255) || (b[p + 3] == 255) != (b[p + 4] == 255) ||
        b[p + 1] > b[p + 2] || b[p + 3] > b[p + 4]) return false;
    for (unsigned i = 0; i < n; ++i) {
        unsigned packed = u16(b + p + 10 + i * 4), base = packed & 127;
        if ((i + 1 < n && !(packed >> 7)) || (!pan && base > 64)) return false;
    }
    *pos += size;
    return true;
}
bool validate(GbaImage& out) {
    const uint8_t* b = out.bytes;
    if (std::memcmp(b, "GBAM", 4) || u32(b + 4) != 1 || u32(b + 12) != 64 || u32(b + 20)) return false;
    for (unsigned i = 24; i < 64; ++i) if (b[i]) return false;
    out.used = u32(b + 8);
    if (out.used < 80 || out.used > GBA_IMAGE_SIZE || u32(b + 16) != out.used - 64) return false;
    const uint32_t end = out.used;
    unsigned bank_samples = u16(b + 64);
    if (bank_samples > 256 || u16(b + 66) != 1 || std::memcmp(b + 68, "*maxmod*", 8)) return false;
    uint32_t tables_end = 76 + (bank_samples + 1) * 4;
    if (!span(76, (bank_samples + 1) * 4, end)) return false;
    for (unsigned s = 0; s < bank_samples; ++s) {
        uint32_t rel = u32(b + 76 + s * 4);
        if (rel > end - 64) return false;
        uint32_t p = 64 + rel, limit;
        if (p < tables_end || !block(b, p, 1, end, &limit) || !span(p + 8, 12, limit)) return false;
        uint32_t length = u32(b + p + 8), loop = u32(b + p + 12);
        if (!length || length > 768u * 1024u || !span(p + 20, length, limit) || b[p + 16] ||
            (loop != 0xffffffffu && (!loop || loop > length))) return false;
        out.sample[s] = p + 8;
    }
    uint32_t rel = u32(b + 76 + bank_samples * 4);
    if (rel > end - 64) return false;
    uint32_t p = 64 + rel, limit;
    if (p < tables_end || !block(b, p, 0, end, &limit)) return false;
    out.module = p;
    const uint32_t h = p + 8;
    if (!span(h, 276, limit)) return false;
    out.instruments = b[h + 1]; out.samples = b[h + 2]; out.patterns = b[h + 3];
    if (!out.instruments || out.instruments > 64 || !out.samples || !out.patterns ||
        b[h + 4] != 0x1e || b[h + 5] > 64 || !b[h + 6] || b[h + 6] > 31 || b[h + 7] < 32) return false;
    out.orders = 0;
    while (out.orders < 200 && b[h + 76 + out.orders] != 255) {
        if (b[h + 76 + out.orders] >= out.patterns) return false;
        ++out.orders;
    }
    if (!out.orders || out.orders >= 200 || b[h + 8] >= out.orders) return false;
    for (unsigned i = out.orders; i < 200; ++i) if (b[h + 76 + i] != 255) return false;
    for (unsigned i = 0; i < 32; ++i) if (b[h + 12 + i] > 64) return false;
    uint32_t count = out.instruments + out.samples + out.patterns;
    if (!span(h + 276, count * 4, limit)) return false;
    uint32_t data_start = h + 276 + count * 4;
    auto entry = [&](unsigned i, uint32_t minimum, uint32_t* value) {
        uint32_t offset = u32(b + h + 276 + i * 4);
        if (offset > limit - h) return false;
        *value = h + offset;
        return (i >= out.instruments + out.samples || aligned(*value)) &&
               *value >= data_start && span(*value, minimum, limit);
    };
    for (unsigned i = 0; i < out.instruments; ++i) {
        uint32_t at;
        if (!entry(i, 12, &at)) return false;
        out.instrument[i] = at;
        unsigned flags = b[at + 5], map = u16(b + at + 8);
        if (b[at] > 128 || b[at + 2] || b[at + 3] || b[at + 4] || b[at + 7] ||
            (flags & ~27u) || ((flags & 1) << 3) != (flags & 8) ||
            ((flags & 16) && !(flags & 2))) return false;
        uint32_t ep = at + 12;
        if ((flags & 1) && !envelope(b, &ep, limit, false)) return false;
        if ((flags & 2) && !envelope(b, &ep, limit, true)) return false;
        if (map & 0x8000) {
            if ((map & 0x7fff) > out.samples) return false;
        } else if (!map) {
            // mmutil represents an entirely empty instrument with a zeroed
            // header and zeroed 120-entry map, including a zero map offset.
            if (!span(at, 252, limit)) return false;
            for (unsigned z = 0; z < 252; ++z) if (b[at + z]) return false;
        } else {
            if (map < ep - at || !span(at + map, 240, limit) || (map & 1)) return false;
            for (unsigned k = 0; k < 120; ++k) {
                unsigned n = u16(b + at + map + k * 2);
                if ((n & 255) >= 120 || (n >> 8) > out.samples) return false;
            }
        }
    }
    for (unsigned s = 0; s < out.samples; ++s) {
        uint32_t at;
        if (!entry(out.instruments + s, 12, &at) || b[at] > 64 || b[at + 7] > 64 ||
            !u16(b + at + 2) || u16(b + at + 10) >= bank_samples || b[at + 4] || b[at + 5] || b[at + 6]) return false;
    }
    for (unsigned pat = 0; pat < out.patterns; ++pat) {
        uint32_t at;
        if (!entry(out.instruments + out.samples + pat, 1, &at)) return false;
        out.pattern[pat] = at;
        unsigned rows = b[at++] + 1;
        out.row_count[pat] = rows;
        uint8_t masks[8] = {};
        for (unsigned row = 0; row < rows; ++row) {
            out.rows[pat][row] = at;
            unsigned seen = 0;
            while (true) {
                if (!span(at, 1, limit)) return false;
                unsigned channel = b[at++];
                if (!channel) break;
                unsigned ch = (channel & 127);
                if (!ch || ch > 8 || (seen & (1u << ch))) return false;
                seen |= 1u << ch;
                if (channel & 128) {
                    if (!span(at, 1, limit)) return false;
                    masks[ch - 1] = b[at++];
                }
                unsigned flags = masks[ch - 1];
                unsigned bytes = !!(flags & 1) + !!(flags & 2) + !!(flags & 4) + 2 * !!(flags & 8);
                if (!span(at, bytes, limit)) return false;
                if (flags & 1) { unsigned note = b[at++]; if (note >= 120 && note != 254 && note != 255) return false; }
                if ((flags & 2) && b[at++] > out.instruments) return false;
                if (flags & 4) { if (b[at++] != 0) return false; }
                if (flags & 8) {
                    unsigned fx = b[at++], param = b[at++];
                    if (fx > 30 || (fx == 1 && (!param || param > 31)) ||
                        (fx == 2 && param >= out.orders) || (fx == 20 && param < 32)) return false;
                }
            }
        }
        out.rows[pat][rows] = at;
    }
    return true;
}
}
int gba_load_image(ChipImage* storage, const uint8_t* bytes, size_t size) {
    if (!storage || !bytes || size != GBA_IMAGE_SIZE) return CHIP_ERR_IMAGE;
    auto& image = *reinterpret_cast<GbaImage*>(storage);
    image.valid = 0;
    std::memcpy(image.bytes, bytes, size);
    if (!validate(image)) return CHIP_ERR_IMAGE;
    image.valid = 0x47424131;
    return CHIP_OK;
}
