/* VGM 1.71 export (opm_vgm.h). */
#include "opm_vgm.h"

#include <stdlib.h>
#include <string.h>

#include "opm_export.h"
#include "opm_machine.h"
#include "vgm_gd3.h"

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

static void put_reg(Out* o, int reg, int val) {
    put(o, 0x54);
    put(o, reg);
    put(o, val);
}

static void put_writes(Out* o, const OmPlayer* p, int from, int to) {
    for (int i = from; i < to; ++i) put_reg(o, p->write[i].reg, p->write[i].val);
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

/* The player's whole register state, written at the loop point so every pass of the loop starts from the same
 * registers (the player's writes are diffed, so the loop body alone would assume the first pass's). */
static void put_state(Out* o, const OmPlayer* p) {
    const uint8_t* s = p->shadow;
    static const int shared[] = {0x0F, 0x10, 0x11, 0x18, 0x19};
    for (int i = 0; i < 5; ++i) put_reg(o, shared[i], s[shared[i]]);
    put_reg(o, 0x19, 0x80 | s[OMP_SHADOW_PMD]);
    put_reg(o, 0x1B, s[0x1B]);
    put_reg(o, 0x14, s[0x14]);
    for (int r = 0x20; r < 0x100; ++r) put_reg(o, r, s[r]);
    for (int c = 0; c < OM_CHANNELS; ++c) put_reg(o, 0x08, p->ch[c].keyed ? OMP_KEY_ALL | c : c);
}

size_t om_vgm_render(const OpmImage* image, uint32_t max_ticks, OmPlayer* player, uint8_t* out, size_t capacity,
                     OmVgmInfo* info) {
    memset(info, 0, sizeof *info);
    if (image->info.loaded != OM_LOADED) return 0;
    Out o = {out, VGM_HEADER, capacity, capacity < VGM_HEADER};
    omp_reset(player, image);
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
        omp_tick(player, image);
        acc += 44100;
        uint32_t n = acc / period;
        acc -= n * period;
        samples += n;
        /* the key-off phase, one sample, then the rest of the tick's writes (opm_player.h) */
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
    vgm_put32(out + 0x04, (uint32_t)(o.n - 4));
    vgm_put32(out + 0x08, 0x171);
    vgm_put32(out + 0x18, samples);
    if (looped) {
        vgm_put32(out + 0x1C, (uint32_t)(loop_offset - 0x1C));
        vgm_put32(out + 0x20, samples - loop_samples);
        info->loop_offset = (uint32_t)loop_offset;
        info->loop_samples = samples - loop_samples;
    }
    vgm_put32(out + 0x24, 60);
    vgm_put32(out + 0x30, OMM_CLOCK);
    vgm_put32(out + 0x34, VGM_HEADER - 0x34);
    info->samples = samples;
    return o.n;
}

/* ---- host entry point (opm_export.h): the file plus a GD3 tag -------------------------------------------- */

#define GD3_SYSTEM "OPM (Thor Tracker)"
#define GD3_CONVERTER "Thor Tracker"

uint8_t* om_export_vgm(const uint8_t* image, size_t image_size, uint32_t max_ticks, const char* title,
                       const char* author, const char* date, size_t* size, const char** problem) {
    *size = 0;
    *problem = NULL;
    if (image_size != OM_IMAGE_SIZE) {
        *problem = "image size";
        return NULL;
    }
    OpmImage* img = (OpmImage*)malloc(sizeof *img);
    OmPlayer* player = (OmPlayer*)malloc(sizeof *player);
    uint8_t* vgm = NULL;
    if (!img || !player) {
        *problem = "out of memory";
        goto done;
    }
    memcpy(img->bytes, image, OM_IMAGE_SIZE);
    if (om_load_image(img)) {
        *problem = img->info.problem;
        goto done;
    }
    OmVgmInfo info;
    char today[16];
    date = vgm_date(date, today);
    om_vgm_render(img, max_ticks, player, NULL, 0, &info); /* measure: the player is deterministic */
    const size_t body = info.size, tag = vgm_gd3(title, GD3_SYSTEM, author, date, GD3_CONVERTER, NULL);
    vgm = (uint8_t*)malloc(body + tag);
    if (!vgm) {
        *problem = "out of memory";
        goto done;
    }
    om_vgm_render(img, max_ticks, player, vgm, body, &info);
    vgm_gd3(title, GD3_SYSTEM, author, date, GD3_CONVERTER, vgm + body);
    vgm_put32(vgm + 0x04, (uint32_t)(body + tag - 4));
    vgm_put32(vgm + 0x14, (uint32_t)(body - 0x14));
    *size = body + tag;
done:
    free(img);
    free(player);
    return vgm;
}

void om_export_free(uint8_t* vgm) { free(vgm); }
