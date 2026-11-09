/* XGM2 frame encoding and packing (ym_xgm.h). Command encodings and the driver behaviour they rely on are SGDK's
 * (bin/xgm2.txt, src/snd/xgm2/drv_xgm2*.i80 of the pinned commit): the load-instrument data order is FM_loadInst's,
 * TL is a $9x command because FM_updateAttCh rewrites TL and SL/RR from the driver's state on every algorithm write,
 * and the special frequencies follow FM_setFreq0_CH2_SL ($38 + i writes $AC + i / $A8 + i). */
#include "ym_xgm.h"

#include <string.h>

typedef struct Out {
    uint8_t* p;
    int n, cap, overflow;
} Out;

static void put(Out* o, int b) {
    if (o->n < o->cap) o->p[o->n++] = (uint8_t)b;
    else o->overflow = 1;
}

/* Register offsets of operator slots in register order (+0, +4, +8, +12) as the load-instrument data has them. */
static const uint8_t LOAD_BASE[7] = {0x30, 0x40, 0x50, 0x60, 0x70, 0x80, 0x90};
#define LOAD_INST_BYTES 31

static int is_tl(int reg) { return reg >= 0x40 && reg < 0x50; }

/* Writes of FM channel c (port, ch) the batch touched: op registers $30-$9F and $B4 (not $B0). */
static void channel_writes(const uint8_t touched[2][256], int port, int ch, int* tl, int* other) {
    *tl = *other = 0;
    for (int reg = 0x30 + ch; reg < 0xA0; reg += 4) {
        if (!touched[port][reg]) continue;
        if (is_tl(reg)) ++*tl;
        else ++*other;
    }
    if (touched[port][0xB4 + ch]) ++*other;
}

static void load_instrument(Out* o, const YmPlayer* p, int port, int ch) {
    const uint8_t* ym = p->ym[port];
    put(o, 0x20 | port << 2 | ch);
    for (int r = 0; r < 7; ++r)
        for (int s = 0; s < 16; s += 4) put(o, ym[LOAD_BASE[r] + s + ch]);
    put(o, ym[0xB0 + ch]);
    put(o, ym[0xB4 + ch]);
}

static void individual(Out* o, const YmPlayer* p, const uint8_t touched[2][256], int port, int ch) {
    const uint8_t* ym = p->ym[port];
    for (int s = 0; s < 4; ++s) {
        const int reg = 0x40 + 4 * s + ch;
        if (touched[port][reg]) {
            put(o, 0x90 | s << 2 | ch);
            put(o, ym[reg] << 1 | port);
        }
    }
    uint8_t regs[32];
    int m = 0;
    for (int reg = 0x30 + ch; reg < 0xA0; reg += 4)
        if (touched[port][reg] && !is_tl(reg)) regs[m++] = (uint8_t)reg;
    if (touched[port][0xB4 + ch]) regs[m++] = (uint8_t)(0xB4 + ch);
    for (int i = 0; i < m; i += 8) {
        const int k = m - i < 8 ? m - i : 8;
        put(o, 0xE0 | port << 3 | (k - 1));
        for (int j = 0; j < k; ++j) {
            put(o, regs[i + j]);
            put(o, ym[regs[i + j]]);
        }
    }
}

/* A frequency command ($30 | slot) with its 14-bit value, high byte first; `key_on` sets the post key-on flag. */
static void frequency(Out* o, int cmd, int hi, int lo, int key_on) {
    put(o, cmd);
    put(o, (key_on ? 0x80 : 0) | (hi & 0x3F));
    put(o, lo);
}

int ymx_encode(const YmImage* image, const YmPlayer* p, const YmWrite* w, int n, const int* pcm, int full, YmxFrame* f) {
    uint8_t touched[2][256];
    memset(touched, 0, sizeof touched);
    uint8_t offs[16], late[16];
    int noffs = 0, nlate = 0, keyon[YM_FM_CHANNELS] = {0};
    unsigned psg_touch = 0; /* bits 0-2 tones, bit 3 noise, bits 4-7 attenuations */
    int latch = -1;
    for (int i = 0; i < n; ++i) {
        const int v = w[i].val;
        if (w[i].kind == YM_W_PSG) {
            if (v & 0x80) {
                latch = (v >> 4) & 7; /* channel << 1 | type */
                psg_touch |= latch & 1 ? 0x10u << (latch >> 1) : (latch >> 1) == 3 ? 0x08u : 1u << (latch >> 1);
            } else if (latch >= 0 && !(latch & 1) && (latch >> 1) < 3) {
                psg_touch |= 1u << (latch >> 1);
            }
            continue;
        }
        if (w[i].kind == YM_W_PORT0 && w[i].reg == 0x28) {
            if ((v & 3) == 3) continue;
            const int c = (v & 3) + (v & 4 ? 3 : 0), mask = v & 0xF0;
            if (mask == 0xF0 && nlate == 0) keyon[c] = 1;
            else if (mask == 0 && !keyon[c] && nlate == 0 && noffs < 16) offs[noffs++] = (uint8_t)v;
            else if (nlate < 16) late[nlate++] = (uint8_t)v;
            continue;
        }
        touched[w[i].kind][w[i].reg] = 1;
    }

    Out o = {f->fm, 0, YMX_CMDS_MAX, 0};
    for (int i = 0; i < noffs; ++i) put(&o, 0x40 | (offs[i] & 7));
    if (full || touched[0][0x27]) put(&o, p->ym[0][0x27] & 0x40 ? 0xFA : 0xFB);
    if (full || touched[0][0x22]) {
        put(&o, 0xF9);
        put(&o, p->ym[0][0x22]);
    }
    for (int c = 0; c < YM_FM_CHANNELS; ++c) {
        const int port = c / 3, ch = c % 3;
        int tl, other;
        channel_writes(touched, port, ch, &tl, &other);
        const int cost = 2 * tl + (other ? (other + 7) / 8 + 2 * other : 0);
        if (full || touched[port][0xB0 + ch] || cost >= LOAD_INST_BYTES) load_instrument(&o, p, port, ch);
        else if (cost) individual(&o, p, touched, port, ch);
        if (c == YM_CH_FM3) {
            for (int i = 0; i < 3; ++i)
                if (full || touched[0][0xAC + i] || touched[0][0xA8 + i]) frequency(&o, 0x38 + i, p->ym[0][0xAC + i], p->ym[0][0xA8 + i], 0);
        }
        if (full || touched[port][0xA4 + ch] || touched[port][0xA0 + ch]) {
            frequency(&o, 0x30 | port << 2 | ch, p->ym[port][0xA4 + ch], p->ym[port][0xA0 + ch], keyon[c]);
        } else if (keyon[c]) {
            put(&o, 0x48 | port << 2 | ch);
        }
    }
    for (int i = 0; i < nlate; ++i) {
        put(&o, 0xF8);
        put(&o, late[i]);
    }
    for (int k = 0; k < YM_PCM_CHANNELS; ++k) {
        if (pcm[k] == YMP_PCM_NONE) continue;
        int half = 0;
        if (pcm[k] > 0) {
            int start, pages, flags;
            ym_pcm_region(image, pcm[k], &start, &pages, &flags);
            half = flags & YM_PCM_HALF_RATE;
        }
        put(&o, 0x10 | (half ? 4 : 0) | k);
        put(&o, pcm[k]);
    }
    f->fm_size = o.n;
    if (o.overflow) return -1;

    Out q = {f->psg, 0, YMX_CMDS_MAX, 0};
    for (int i = 0; i < 3; ++i)
        if (full || psg_touch >> i & 1) {
            put(&q, 0x20 | i << 2 | (p->tone[i] >> 8 & 3));
            put(&q, p->tone[i] & 0xFF);
        }
    if (full || psg_touch & 0x08) {
        put(&q, 0x10);
        put(&q, 0xE0 | p->noise_ctrl);
    }
    for (int i = 0; i < 4; ++i)
        if (full || psg_touch >> (4 + i) & 1) put(&q, 0x80 + 0x10 * i + p->att[i]);
    f->psg_size = q.n;
    return q.overflow ? -1 : 0;
}

int ymx_wait(uint8_t* out, int psg, int frames) {
    const int short_max = psg ? 14 : 15;
    if (frames <= short_max) {
        out[0] = (uint8_t)(frames - 1);
        return 1;
    }
    out[0] = psg ? 0x0E : 0x0F;
    out[1] = (uint8_t)(frames - short_max - 1);
    return 2;
}

int ymx_loop(uint8_t* out, int psg, int32_t offset) {
    const uint32_t v = offset < 0 ? 0xFFFFFFu : (uint32_t)offset;
    out[0] = psg ? 0x0F : 0xFF;
    out[1] = (uint8_t)v;
    out[2] = (uint8_t)(v >> 8);
    out[3] = (uint8_t)(v >> 16);
    return 4;
}

size_t ymx_pack(const uint8_t* in, int n, uint32_t at, uint8_t* out) {
    size_t o = 0;
    uint32_t pos = at;
    int i = 0;
    while (i < n) {
        int len = n - i < 7 ? n - i : 7;
        const int remain = 0x100 - (int)(pos & 0xFF);
        int header = len << 5;
        if (1 + len > remain) { /* the block would cross a page: end this one at the page end */
            if (remain < 2) {
                out[o++] = 0x01; /* page-cross marker alone */
                ++pos;
                continue;
            }
            len = remain - 1;
            header = len << 5 | 1;
        }
        out[o++] = (uint8_t)header;
        memcpy(out + o, in + i, (size_t)len);
        o += (size_t)len;
        pos += 1u + (uint32_t)len;
        i += len;
    }
    out[o++] = 0x00; /* end of block */
    return o;
}
