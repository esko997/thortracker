/* GD3 tags (vgm_gd3.h). */
#include "vgm_gd3.h"

#include <string.h>
#include <time.h>

void vgm_put32(uint8_t* p, uint32_t v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

/* UTF-8 -> UTF-16LE with a terminating 0; malformed bytes become U+FFFD. Returns bytes written (or needed when
 * out is NULL). */
static size_t utf16(const char* s, uint8_t* out) {
    size_t n = 0;
    const uint8_t* p = (const uint8_t*)(s ? s : "");
    while (1) {
        uint32_t c = *p++;
        if (c >= 0x80) {
            const int extra = c >= 0xF8 ? -1 : c >= 0xF0 ? 3 : c >= 0xE0 ? 2 : c >= 0xC2 ? 1 : -1;
            if (extra < 0) {
                c = 0xFFFD;
            } else {
                c &= 0x3F >> extra;
                for (int i = 0; i < extra; ++i) {
                    if ((*p & 0xC0) != 0x80) {
                        c = 0xFFFD;
                        break;
                    }
                    c = c << 6 | (*p++ & 0x3F);
                }
                if (c > 0x10FFFF || (c >= 0xD800 && c < 0xE000)) c = 0xFFFD;
            }
        }
        uint16_t units[2];
        int count = 1;
        if (c > 0xFFFF) {
            units[0] = (uint16_t)(0xD800 | (c - 0x10000) >> 10);
            units[1] = (uint16_t)(0xDC00 | (c & 0x3FF));
            count = 2;
        } else {
            units[0] = (uint16_t)c;
        }
        for (int i = 0; i < count; ++i, n += 2)
            if (out) {
                out[n] = (uint8_t)units[i];
                out[n + 1] = (uint8_t)(units[i] >> 8);
            }
        if (!c) return n;
    }
}

size_t vgm_gd3(const char* title, const char* system, const char* author, const char* date, const char* converter,
               uint8_t* out) {
    const char* fields[11] = {title, "", "", "", system, "", author, "", date, converter, ""};
    size_t n = 12;
    for (int i = 0; i < 11; ++i) n += utf16(fields[i], out ? out + n : NULL);
    if (out) {
        memcpy(out, "Gd3 ", 4);
        vgm_put32(out + 4, 0x100);
        vgm_put32(out + 8, (uint32_t)(n - 12));
    }
    return n;
}

const char* vgm_date(const char* date, char* today) {
    if (date && *date) return date;
    const time_t now = time(NULL);
    struct tm local;
    return localtime_r(&now, &local) && strftime(today, 16, "%Y/%m/%d", &local) ? today : "";
}
