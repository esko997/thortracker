/* VGM 1.71 export (opl3_vgm.h). */
#include "opl3_vgm.h"

#include <string.h>

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
