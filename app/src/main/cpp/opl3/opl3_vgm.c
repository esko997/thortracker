/* VGM 1.71 export (opl3_vgm.h). */
#include "opl3_vgm.h"

#include <stdlib.h>
#include <string.h>

#include "opl3_export.h"

#define VGM_HEADER 0x100

typedef struct Out {
    uint8_t* p;
    size_t n, cap;
    int overflow;
} Out;

static void put(Out* o, int byte) {
    if (o->n < o->cap) o->p[o->n] = (uint8_t)byte;
    else o->overflow = 1;
    ++o->n;
}

static void put32(uint8_t* p, uint32_t v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

static void put_writes(Out* o, const O3Player* p, int from, int to) {
    for (int i = from; i < to; ++i) {
        put(o, p->write[i].reg & 0x100 ? 0x5F : 0x5E);
        put(o, p->write[i].reg & 0xFF);
        put(o, p->write[i].val);
    }
}

static void put_wait(Out* o, uint32_t n) {
    while (n) {
        if (n == 735) {
            put(o, 0x62);
            n = 0;
        } else if (n <= 16) {
            put(o, 0x70 | (n - 1));
            n = 0;
        } else {
            const uint32_t k = n > 65535 ? 65535 : n;
            put(o, 0x61);
            put(o, k & 0xFF);
            put(o, k >> 8);
            n -= k;
        }
    }
}

static void put_reg(Out* o, int reg, int val) {
    put(o, reg & 0x100 ? 0x5F : 0x5E);
    put(o, reg & 0xFF);
    put(o, val);
}

/* The player's whole register state, written at the loop point so every pass of the loop starts from the
 * same registers (the player's writes are diffed, so the loop body alone would assume the first pass's). */
static void put_state(Out* o, const O3Player* p) {
    static const int globals[] = {0x105, 0x104, 0x08, 0xBD};
    static const int op_bases[] = {0x20, 0x40, 0x60, 0x80, 0xE0};
    static const int channel_bases[] = {0xC0, 0xA0, 0xB0}; /* key-ons last */
    for (int i = 0; i < 4; ++i) put_reg(o, globals[i], p->shadow[globals[i]]);
    for (int bank = 0; bank < 0x200; bank += 0x100)
        for (int b = 0; b < 5; ++b)
            for (int r = op_bases[b]; r < op_bases[b] + 0x16; ++r)
                if ((r & 7) < 6) put_reg(o, bank + r, p->shadow[bank + r]);
    for (int b = 0; b < 3; ++b)
        for (int v = 0; v < O3_VOICES; ++v) {
            const int reg = (v >= 9 ? 0x100 : 0) + channel_bases[b] + v % 9;
            put_reg(o, reg, p->shadow[reg]);
        }
}

size_t o3_vgm_render(const Opl3Image* image, uint32_t max_ticks, O3Player* player, uint8_t* out, size_t capacity,
                     O3VgmInfo* info) {
    memset(info, 0, sizeof *info);
    if (image->info.loaded != O3_LOADED) return 0;
    Out o = {out, VGM_HEADER, capacity, capacity < VGM_HEADER};
    o3p_reset(player, image);
    put_writes(&o, player, 0, player->writes);
    const uint32_t period = 60u * (uint32_t)image->info.multispeed;
    uint32_t acc = 0, samples = 0, loop_samples = 0;
    size_t loop_offset = 0;
    int seen_loop = 0;
    uint32_t t = 0;
    for (; t < max_ticks; ++t) {
        if (player->order == image->info.loop && player->row == 0 && player->tick == 0) {
            if (seen_loop) break;
            seen_loop = 1;
            loop_offset = o.n;
            loop_samples = samples;
            put_state(&o, player);
        }
        o3p_tick(player, image);
        acc += 44100;
        uint32_t n = acc / period;
        acc -= n * period;
        samples += n;
        /* the key-off phase, one sample, then the rest of the tick's writes (opl3_player.h) */
        put_writes(&o, player, 0, player->offs);
        if (player->offs) {
            put_wait(&o, 1);
            --n;
        }
        put_writes(&o, player, player->offs, player->writes);
        put_wait(&o, n);
    }
    const int looped = t < max_ticks;
    put(&o, 0x66);
    info->ticks = t;
    info->size = o.n;
    if (o.overflow) return 0;
    memset(out, 0, VGM_HEADER);
    memcpy(out, "Vgm ", 4);
    put32(out + 0x04, (uint32_t)(o.n - 4));
    put32(out + 0x08, 0x171);
    put32(out + 0x18, samples);
    if (looped) {
        put32(out + 0x1C, (uint32_t)(loop_offset - 0x1C));
        put32(out + 0x20, samples - loop_samples);
        info->loop_offset = (uint32_t)loop_offset;
        info->loop_samples = samples - loop_samples;
    }
    put32(out + 0x24, 60);
    put32(out + 0x34, VGM_HEADER - 0x34);
    put32(out + 0x5C, 14318180);
    info->samples = samples;
    return o.n;
}

/* ---- host entry point (opl3_export.h): the file plus a GD3 tag -------------------------------------------- */

#define GD3_SYSTEM "OPL3 (Thor Tracker)"
#define GD3_CONVERTER "Thor Tracker"

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

/* GD3 1.00: track, track (JP), game, game (JP), system, system (JP), author, author (JP), date, converter, notes. */
static size_t gd3(const char* title, const char* author, const char* date, uint8_t* out) {
    const char* fields[11] = {title, "", "", "", GD3_SYSTEM, "", author, "", date, GD3_CONVERTER, ""};
    size_t n = 12;
    for (int i = 0; i < 11; ++i) n += utf16(fields[i], out ? out + n : NULL);
    if (out) {
        memcpy(out, "Gd3 ", 4);
        put32(out + 4, 0x100);
        put32(out + 8, (uint32_t)(n - 12));
    }
    return n;
}

uint8_t* o3_export_vgm(const uint8_t* image, size_t image_size, uint32_t max_ticks, const char* title,
                       const char* author, const char* date, size_t* size, const char** problem) {
    *size = 0;
    *problem = NULL;
    if (image_size != O3_IMAGE_SIZE) {
        *problem = "image size";
        return NULL;
    }
    Opl3Image* img = (Opl3Image*)malloc(sizeof *img);
    O3Player* player = (O3Player*)malloc(sizeof *player);
    uint8_t* vgm = NULL;
    if (!img || !player) {
        *problem = "out of memory";
        goto done;
    }
    memcpy(img->bytes, image, O3_IMAGE_SIZE);
    if (o3_load_image(img)) {
        *problem = img->info.problem;
        goto done;
    }
    O3VgmInfo info;
    o3_vgm_render(img, max_ticks, player, NULL, 0, &info); /* measure: the player is deterministic */
    const size_t body = info.size, tag = gd3(title, author, date, NULL);
    vgm = (uint8_t*)malloc(body + tag);
    if (!vgm) {
        *problem = "out of memory";
        goto done;
    }
    o3_vgm_render(img, max_ticks, player, vgm, body, &info);
    gd3(title, author, date, vgm + body);
    put32(vgm + 0x04, (uint32_t)(body + tag - 4));
    put32(vgm + 0x14, (uint32_t)(body - 0x14));
    *size = body + tag;
done:
    free(img);
    free(player);
    return vgm;
}

void o3_export_free(uint8_t* vgm) { free(vgm); }
