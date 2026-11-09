/* VGM 1.71 export (pce_vgm.h) and the hosts' entry point (pce_export.h). */
#include "pce_vgm.h"

#include <stdlib.h>
#include <string.h>

#include "pce_export.h"
#include "pce_machine.h"
#include "pce_player.h"
#include "vgm_gd3.h"

#define VGM_HEADER 0x100
#define GD3_SYSTEM "PC Engine (Thor Tracker)"
#define GD3_CONVERTER "Thor Tracker"

typedef struct Out {
    uint8_t* p;
    size_t n, cap;
    int overflow;
    int64_t sample;  /* 44.1 kHz samples written so far (the current time) */
} Out;

static void put(Out* o, int byte) {
    if (o->p && o->n < o->cap) o->p[o->n] = (uint8_t)byte;
    else if (o->p) o->overflow = 1;
    ++o->n;
}

static void put_reg(Out* o, int reg, int val) {
    put(o, 0xB9);
    put(o, reg);
    put(o, val);
}

static void put_writes(Out* o, const PcPlayer* p) {
    for (int i = 0; i < p->writes; ++i) put_reg(o, p->write[i].reg, p->write[i].val);
}

/* 44.1 kHz sample of CPU cycle t: t * 44100 / (78750000 / 11) = t * 4851 / 787500. */
static int64_t sample_of(int64_t cycle) { return cycle * 4851 / 787500; }

/* Waits from the current time to `sample`. */
static void wait_until(Out* o, int64_t sample) {
    int64_t n = sample - o->sample;
    o->sample = sample;
    while (n > 0) {
        if (n == 735) {
            put(o, 0x62);
            n = 0;
        } else if (n == 882) {
            put(o, 0x63);
            n = 0;
        } else if (n <= 16) {
            put(o, 0x70 | (int)(n - 1));
            n = 0;
        } else {
            const int64_t k = n > 65535 ? 65535 : n;
            put(o, 0x61);
            put(o, (int)(k & 0xFF));
            put(o, (int)(k >> 8));
            n -= k;
        }
    }
}

/* An event (a tick's or an interrupt's batch) at `cycle`: nothing for an empty batch, else the wait, then its writes. */
static void put_event(Out* o, const PcPlayer* p, int64_t cycle) {
    if (!p->writes) return;
    wait_until(o, sample_of(cycle));
    put_writes(o, p);
}

/* The chip state as the player's shadows hold it, at the loop point: every pass of the loop starts from the same
 * registers (the player's writes are diffed, so the loop body alone would assume the first pass's). */
static void put_state(Out* o, const PcPlayer* p, const PceImage* image) {
    put_reg(o, 1, p->shadow_r1);
    put_reg(o, 8, p->shadow_r8);
    put_reg(o, 9, p->shadow_r9);
    for (int c = 0; c < PC_CHANNELS; ++c) {
        const PcChannel* ch = &p->ch[c];
        put_reg(o, 0, c);
        if (ch->loaded >= 0) {
            const uint8_t* wave = image->bytes + PC_OFF_WAVES + PC_WAVE_SIZE * ch->loaded;
            put_reg(o, 4, 0x40);
            put_reg(o, 4, 0x00);
            for (int i = 0; i < PC_WAVE_SIZE; ++i) put_reg(o, 6, wave[i]);
        }
        put_reg(o, 2, ch->r2);
        put_reg(o, 3, ch->r3);
        put_reg(o, 5, ch->r5);
        if (c >= PC_NOISE_FIRST) put_reg(o, 7, ch->r7);
        put_reg(o, 4, ch->r4);
    }
    if (p->select >= 0) put_reg(o, 0, p->select);
}

int pc_vgm_render(const PceImage* image, int max_ticks, uint8_t* out, size_t capacity, size_t* size, PcVgmInfo* info) {
    memset(info, 0, sizeof *info);
    *size = 0;
    if (image->info.loaded != PC_LOADED) return -1;
    PcPlayer player;
    PcPlayer* p = &player;
    Out o = {out, VGM_HEADER, capacity, out && capacity < VGM_HEADER, 0};
    const int64_t n_mul = 11 * (int64_t)image->info.multispeed;
    pcp_reset(p, image);
    put_writes(&o, p);
    int64_t next_irq = pcp_timer_cycles(p);
    int64_t loop_sample = 0;
    size_t loop_offset = 0;
    int seen_loop = 0, looped = 0;
    int t = 0;
    for (; t < max_ticks; ++t) {
        const int64_t start = (int64_t)t * PCM_CYCLES_NUM / n_mul, end = (int64_t)(t + 1) * PCM_CYCLES_NUM / n_mul;
        if (p->order == image->info.loop && p->row == 0 && p->tick == 0) {
            if (seen_loop) {
                looped = 1;
                break;
            }
            seen_loop = 1;
            wait_until(&o, sample_of(start));
            loop_offset = o.n;
            loop_sample = o.sample;
            put_state(&o, p, image);
        }
        pcp_tick(p, image);
        put_event(&o, p, start);
        while (next_irq < end) {
            pcp_timer(p, image);
            put_event(&o, p, next_irq);
            next_irq += pcp_timer_cycles(p);
        }
    }
    wait_until(&o, sample_of((int64_t)t * PCM_CYCLES_NUM / n_mul));
    put(&o, 0x66);
    info->ticks = (uint32_t)t;
    info->size = *size = o.n;
    info->samples = (uint32_t)o.sample;
    if (looped) {
        info->loop_offset = (uint32_t)loop_offset;
        info->loop_samples = (uint32_t)(o.sample - loop_sample);
    }
    if (!out) return 0;
    if (o.overflow) return -1;
    memset(out, 0, VGM_HEADER);
    memcpy(out, "Vgm ", 4);
    vgm_put32(out + 0x04, (uint32_t)(o.n - 4));
    vgm_put32(out + 0x08, 0x171);
    vgm_put32(out + 0x18, info->samples);
    if (looped) {
        vgm_put32(out + 0x1C, info->loop_offset - 0x1C);
        vgm_put32(out + 0x20, info->loop_samples);
    }
    vgm_put32(out + 0x24, 60);
    vgm_put32(out + 0x34, VGM_HEADER - 0x34);
    vgm_put32(out + 0xA4, PC_VGM_CLOCK);
    return 0;
}

/* ---- host entry point (pce_export.h): the file plus a GD3 tag -------------------------------------------- */

uint8_t* pc_export_vgm(const uint8_t* image, size_t image_size, uint32_t max_ticks, const char* title,
                       const char* author, const char* date, size_t* size, const char** problem) {
    *size = 0;
    *problem = NULL;
    if (image_size != PC_IMAGE_SIZE) {
        *problem = "image size";
        return NULL;
    }
    PceImage* img = (PceImage*)malloc(sizeof *img);
    uint8_t* vgm = NULL;
    if (!img) {
        *problem = "out of memory";
        return NULL;
    }
    memcpy(img->bytes, image, PC_IMAGE_SIZE);
    if (pc_load_image(img)) {
        *problem = img->info.problem;
        goto done;
    }
    const int ticks = max_ticks > 0x7FFFFFFFu ? 0x7FFFFFFF : (int)max_ticks;
    PcVgmInfo info;
    size_t body = 0;
    char today[16];
    date = vgm_date(date, today);
    pc_vgm_render(img, ticks, NULL, 0, &body, &info); /* measure: the player is deterministic */
    const size_t tag = vgm_gd3(title, GD3_SYSTEM, author, date, GD3_CONVERTER, NULL);
    vgm = (uint8_t*)malloc(body + tag);
    if (!vgm) {
        *problem = "out of memory";
        goto done;
    }
    pc_vgm_render(img, ticks, vgm, body, &body, &info);
    vgm_gd3(title, GD3_SYSTEM, author, date, GD3_CONVERTER, vgm + body);
    vgm_put32(vgm + 0x04, (uint32_t)(body + tag - 4));
    vgm_put32(vgm + 0x14, (uint32_t)(body - 0x14));
    *size = body + tag;
done:
    free(img);
    return vgm;
}

void pc_export_free(uint8_t* vgm) { free(vgm); }
