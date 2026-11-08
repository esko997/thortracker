/* VGM 1.71 export of an OPN / OPNA image (opn_vgm.h) and the hosts' entry point (opn_export.h). */
#include "opn_vgm.h"

#include <stdlib.h>
#include <string.h>

#include "opn_export.h"
#include "vgm_gd3.h"

#define VGM_HEADER 0x100
#define VGM_RATE 44100u
/* 44.1 kHz samples per native sample = VGM_RATE x OPN_NATIVE_RATE_DEN / OPN_NATIVE_RATE_NUM (exact rational). */
#define VGM_NUM ((uint64_t)VGM_RATE * OPN_NATIVE_RATE_DEN)
#define VGM_DEN ((uint64_t)OPN_NATIVE_RATE_NUM)

typedef struct Out {
    uint8_t* p;
    size_t n, cap;
    int overflow;
    int opna;
} Out;

static void put(Out* o, int byte) {
    if (o->n < o->cap) o->p[o->n] = (uint8_t)byte;
    else o->overflow = 1;
    ++o->n;
}

static void put32(Out* o, uint32_t v) {
    for (int i = 0; i < 4; ++i) put(o, (int)((v >> (8 * i)) & 0xFF));
}

static void put_bytes(Out* o, const uint8_t* b, size_t n) {
    if (o->n + n <= o->cap) memcpy(o->p + o->n, b, n);
    else o->overflow = 1;
    o->n += n;
}

/* YM2203 $55 reg val; YM2608 $56 (port 0) / $57 (port 1) reg val. */
static void put_writes(Out* o, const OpnWrite* w, int from, int to) {
    for (int i = from; i < to; ++i) {
        put(o, o->opna ? (w[i].port ? 0x57 : 0x56) : 0x55);
        put(o, w[i].reg);
        put(o, w[i].val);
    }
}

static void put_wait(Out* o, uint32_t n) {
    while (n) {
        if (n == 735 || n == 882) {
            put(o, n == 735 ? 0x62 : 0x63);
            n = 0;
        } else if (n <= 16) {
            put(o, 0x70 | (int)(n - 1));
            n = 0;
        } else {
            const uint32_t k = n > 65535 ? 65535 : n;
            put(o, 0x61);
            put(o, (int)(k & 0xFF));
            put(o, (int)(k >> 8));
            n -= k;
        }
    }
}

size_t opn_vgm_render(const OpnImage* image, uint32_t max_ticks, OpnPlayer* player, uint8_t* out, size_t capacity,
                      OpnVgmInfo* info) {
    memset(info, 0, sizeof *info);
    if (image->info.loaded != OPN_LOADED) return 0;
    const int chip = image->info.chip, opna = chip == OPN_CHIP_YM2608;
    Out o = {out, VGM_HEADER, capacity, capacity < VGM_HEADER, opna};
    if (opna && image->info.adpcm_size) { /* data block: YM2608 DELTA-T (ADPCM-B) memory */
        put(&o, 0x67);
        put(&o, 0x66);
        put(&o, 0x81);
        put32(&o, 8 + image->info.adpcm_size);
        put32(&o, OPN_ADPCM_MEMORY);
        put32(&o, 0);
        put_bytes(&o, image->bytes + OPN_OFF_ADPCM, image->info.adpcm_size);
    }
    OpnWrite init[OPNP_MAX_RESET_WRITES];
    put_writes(&o, init, 0, opnp_machine_reset(chip, init));
    opnp_reset(player, image);
    put_writes(&o, player->write, 0, player->writes);
    const uint64_t step = (uint64_t)image->info.tick_samples * VGM_NUM;
    uint64_t acc = 0;
    uint32_t samples = 0, loop_samples = 0;
    size_t loop_offset = 0;
    int seen_loop = 0;
    uint32_t t = 0;
    OpnWrite dump[OPNP_MAX_DUMP_WRITES];
    for (; t < max_ticks; ++t) {
        if (player->order == image->info.loop && player->row == 0 && player->tick == 0) {
            if (seen_loop) break;
            seen_loop = 1;
            loop_offset = o.n;
            loop_samples = samples;
            /* the player's whole register state, so every pass of the loop starts from the same registers */
            put_writes(&o, dump, 0, opnp_dump(player, dump));
            put_writes(&o, dump, 0, opnp_dump_adpcm(player, dump));
        }
        opnp_tick(player, image);
        acc += step;
        uint32_t n = (uint32_t)(acc / VGM_DEN);
        acc -= (uint64_t)n * VGM_DEN;
        samples += n;
        /* the key-off phase, one sample, then the rest of the tick's writes (opn_player.h) */
        put_writes(&o, player->write, 0, player->offs);
        if (player->offs && n) {
            put_wait(&o, 1);
            --n;
        }
        put_writes(&o, player->write, player->offs, player->writes);
        put_wait(&o, n);
    }
    const int looped = t < max_ticks;
    put(&o, 0x66);
    info->ticks = t;
    info->size = o.n;
    info->samples = samples;
    if (looped) {
        info->loop_offset = (uint32_t)loop_offset;
        info->loop_samples = samples - loop_samples;
    }
    if (o.overflow) return 0;
    memset(out, 0, VGM_HEADER);
    memcpy(out, "Vgm ", 4);
    vgm_put32(out + 0x04, (uint32_t)(o.n - 4));
    vgm_put32(out + 0x08, 0x171);
    vgm_put32(out + 0x18, samples);
    if (looped) {
        vgm_put32(out + 0x1C, (uint32_t)(loop_offset - 0x1C));
        vgm_put32(out + 0x20, samples - loop_samples);
    }
    vgm_put32(out + 0x34, VGM_HEADER - 0x34);
    if (opna) {
        vgm_put32(out + 0x48, OPN_CLOCK_YM2608);
        out[0x7B] = 0x01; /* YM2608 SSG (AY8910) flags: the default, legacy output */
    } else {
        vgm_put32(out + 0x44, OPN_CLOCK_YM2203);
        out[0x7A] = 0x01; /* YM2203 SSG (AY8910) flags: the default, legacy output */
    }
    return o.n;
}

/* ---- host entry point (opn_export.h): the file plus a GD3 tag ------------------------------------------------- */

#define GD3_CONVERTER "Thor Tracker"

uint8_t* opn_export_vgm(const uint8_t* image, size_t image_size, uint32_t max_ticks, const char* title,
                        const char* author, const char* date, size_t* size, const char** problem) {
    *size = 0;
    *problem = NULL;
    if (!image || image_size <= OPN_H_CHIP) {
        *problem = "image size";
        return NULL;
    }
    const int chip = image[OPN_H_CHIP];
    if ((chip != OPN_CHIP_YM2203 && chip != OPN_CHIP_YM2608) || image_size != opn_image_size(chip)) {
        *problem = "image size";
        return NULL;
    }
    const char* system = chip == OPN_CHIP_YM2608 ? "PC-88/98 OPNA (Thor Tracker)" : "PC-88/98 OPN (Thor Tracker)";
    OpnImage* img = (OpnImage*)malloc(sizeof *img);
    OpnPlayer* player = (OpnPlayer*)malloc(sizeof *player);
    uint8_t* vgm = NULL;
    if (!img || !player) {
        *problem = "out of memory";
        goto done;
    }
    memcpy(img->bytes, image, image_size);
    if (opn_load_image(img, chip)) {
        *problem = img->info.problem;
        goto done;
    }
    OpnVgmInfo info;
    char today[16];
    date = vgm_date(date, today);
    opn_vgm_render(img, max_ticks, player, NULL, 0, &info); /* measure: the player is deterministic */
    const size_t body = info.size, tag = vgm_gd3(title, system, author, date, GD3_CONVERTER, NULL);
    vgm = (uint8_t*)malloc(body + tag);
    if (!vgm) {
        *problem = "out of memory";
        goto done;
    }
    opn_vgm_render(img, max_ticks, player, vgm, body, &info);
    vgm_gd3(title, system, author, date, GD3_CONVERTER, vgm + body);
    vgm_put32(vgm + 0x04, (uint32_t)(body + tag - 4));
    vgm_put32(vgm + 0x14, (uint32_t)(body - 0x14));
    *size = body + tag;
done:
    free(img);
    free(player);
    return vgm;
}

void opn_export_free(uint8_t* vgm) { free(vgm); }
