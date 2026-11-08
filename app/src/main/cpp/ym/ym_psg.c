/* SN76489 (Mega Drive VDP PSG), ym_psg.h. */
#include "ym_psg.h"

#include <string.h>

/* 2 dB per attenuation step: floor(4096 * 10^(-a/10) + 0.5), a = 0..14; 15 = off. */
static const int16_t LEVEL[16] = {4096, 3254, 2584, 2053, 1631, 1295, 1029, 817, 649, 516, 410, 325, 258, 205, 163, 0};

void ymps_reset(YmPsg* p) {
    const unsigned mute = p->mute;
    memset(p, 0, sizeof *p);
    p->mute = mute;
    for (int i = 0; i < 4; ++i) p->att[i] = 15;
    p->lfsr = 0x8000;
    p->latch_index = 0;
}

void ymps_write(YmPsg* p, uint8_t v) {
    if (v & 0x80) {
        p->latch_index = (v >> 5) & 3;
        p->latch_type = (v >> 4) & 1;
    }
    const int i = p->latch_index;
    if (p->latch_type) {
        p->att[i] = v & 15;
    } else if (i == 3) {
        p->noise = v & 7;
        p->lfsr = 0x8000;
    } else if (v & 0x80) {
        p->period[i] = (uint16_t)((p->period[i] & 0x3F0) | (v & 15));
    } else {
        p->period[i] = (uint16_t)((p->period[i] & 15) | (v & 0x3F) << 4);
    }
}

int ymps_output(const YmPsg* p) {
    int out = 0;
    for (int i = 0; i < 3; ++i)
        if (!(p->mute >> i & 1)) out += p->flip[i] ? LEVEL[p->att[i]] : -LEVEL[p->att[i]];
    if (!(p->mute >> 3 & 1)) out += (p->lfsr & 1) ? LEVEL[p->att[3]] : -LEVEL[p->att[3]];
    return out;
}

int ymps_step(YmPsg* p) {
    int flipped = 0;
    for (int i = 0; i < 3; ++i) {
        if (p->counter[i] > 1) {
            --p->counter[i];
        } else {
            p->counter[i] = p->period[i] ? p->period[i] : 1;
            p->flip[i] ^= 1;
            flipped = 1;
        }
    }
    if (p->counter[3] > 1) {
        --p->counter[3];
    } else {
        const int rate = p->noise & 3;
        p->counter[3] = rate == 3 ? (p->period[2] ? p->period[2] : 1) : (uint16_t)(0x10 << rate);
        p->flip[3] ^= 1;
        if (p->flip[3]) {
            const int fb = (p->noise & 4) ? ((p->lfsr ^ (p->lfsr >> 3)) & 1) : (p->lfsr & 1);
            p->lfsr = (uint16_t)((p->lfsr >> 1) | fb << 15);
            flipped = 1;
        }
    }
    return flipped;
}
