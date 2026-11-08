/* VGM 1.71 export (ay_vgm.h) and the hosts' entry point (ay_export.h). */
#include "ay_vgm.h"

#include <stdlib.h>
#include <string.h>

#include "ay_export.h"
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
    put(o, 0xA0);
    put(o, reg);
    put(o, val);
}

static void put_writes(Out* o, const AyPlayer* p) {
    for (int i = 0; i < p->writes; ++i) put_reg(o, p->write[i].reg, p->write[i].val);
}

static void put_wait(Out* o, uint32_t n) {
    while (n) {
        if (n == 735 || n == 882) {
            put(o, n == 735 ? 0x62 : 0x63);
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

size_t ay_vgm_render(const AyImage* image, uint32_t max_ticks, AyPlayer* player, uint8_t* out, size_t capacity,
                     AyVgmInfo* info) {
    memset(info, 0, sizeof *info);
    if (image->info.loaded != AY_LOADED) return 0;
    const AyVariantSpec* v = &ay_variants[image->info.variant];
    Out o = {out, VGM_HEADER, capacity, capacity < VGM_HEADER};
    ay_player_reset(player, image);
    put_writes(&o, player);
    uint64_t acc = 0;
    uint32_t samples = 0, loop_samples = 0;
    size_t loop_offset = 0;
    int seen_loop = 0;
    uint32_t t = 0;
    for (; t < max_ticks; ++t) {
        if (player->order == image->info.loop && player->row == 0 && player->tick == 0) {
            if (seen_loop) break;
            seen_loop = 1;
            loop_offset = o.n;
            loop_samples = samples;
            /* Every pass starts from the same registers (the player's writes are diffed): R0-R12, not R13, whose
             * write would restart the envelope. */
            for (int r = 0; r < AYP_SHADOW; ++r) put_reg(&o, r, player->shadow[r]);
        }
        ay_player_tick(player, image);
        put_writes(&o, player);
        acc += 44100ull * v->frame_div;
        const uint32_t n = (uint32_t)(acc / v->master_hz);
        acc %= v->master_hz;
        samples += n;
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
    vgm_put32(out + 0x24, v->vgm_rate);
    vgm_put32(out + 0x34, VGM_HEADER - 0x34);
    vgm_put32(out + 0x74, v->vgm_clock);
    out[0x78] = v->vgm_type;
    out[0x79] = 0x01; /* legacy single mixed output */
    info->samples = samples;
    return o.n;
}

/* ---- host entry point (ay_export.h): the file plus a GD3 tag ---------------------------------------------- */

uint8_t* ay_export_vgm(int variant, const uint8_t* image, size_t image_size, int max_ticks, const char* title,
                       const char* author, const char* date, size_t* out_size, const char** problem) {
    *out_size = 0;
    *problem = NULL;
    if (variant < 0 || variant >= AY_VARIANTS) {
        *problem = "variant";
        return NULL;
    }
    if (image_size != AY_IMAGE_SIZE) {
        *problem = "image size";
        return NULL;
    }
    AyImage* img = (AyImage*)malloc(sizeof *img);
    AyPlayer* player = (AyPlayer*)malloc(sizeof *player);
    uint8_t* vgm = NULL;
    if (!img || !player) {
        *problem = "out of memory";
        goto done;
    }
    memcpy(img->bytes, image, AY_IMAGE_SIZE);
    if (ay_load_image(img, variant)) {
        *problem = img->info.problem;
        goto done;
    }
    const uint32_t ticks = max_ticks < 0 ? 0u : (uint32_t)max_ticks;
    const char* system = ay_variants[variant].gd3_system;
    AyVgmInfo info;
    char today[16];
    date = vgm_date(date, today);
    ay_vgm_render(img, ticks, player, NULL, 0, &info); /* measure: the player is deterministic */
    const size_t body = info.size, tag = vgm_gd3(title, system, author, date, AY_GD3_CONVERTER, NULL);
    vgm = (uint8_t*)malloc(body + tag);
    if (!vgm) {
        *problem = "out of memory";
        goto done;
    }
    ay_vgm_render(img, ticks, player, vgm, body, &info);
    vgm_gd3(title, system, author, date, AY_GD3_CONVERTER, vgm + body);
    vgm_put32(vgm + 0x04, (uint32_t)(body + tag - 4));
    vgm_put32(vgm + 0x14, (uint32_t)(body - 0x14));
    *out_size = body + tag;
done:
    free(img);
    free(player);
    return vgm;
}

void ay_export_free(uint8_t* file) { free(file); }
