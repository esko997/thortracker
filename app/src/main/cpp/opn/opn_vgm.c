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

/* At the loop point: the next tick starts (loop order, row 0). */
static int at_loop(const OpnPlayer* p, const OpnImage* image) {
    return p->order == image->info.loop && p->row == 0 && p->tick == 0;
}

/* Finds the loop: the first pair of loop-point arrivals b < a whose player states are identical (opnp_same_state),
 * by a digest per arrival and an exact comparison against a second player replayed to arrival b. Sets *loop_tick and
 * *end_tick (the ticks before arrivals b and a) and returns 1, or returns 0 when no such pair exists within max_ticks
 * ticks and OPN_VGM_MAX_ARRIVALS arrivals. */
static int find_loop(const OpnImage* image, uint32_t max_ticks, OpnPlayer* p, OpnPlayer* q, uint32_t* loop_tick,
                     uint32_t* end_tick) {
    uint32_t tick_at[OPN_VGM_MAX_ARRIVALS];
    uint64_t hash_at[OPN_VGM_MAX_ARRIVALS];
    int arrivals = 0;
    opnp_reset(p, image);
    for (uint32_t t = 0;; ++t) {
        if (at_loop(p, image)) {
            const uint64_t h = opnp_state_hash(p);
            for (int b = 0; b < arrivals; ++b) {
                if (hash_at[b] != h) continue;
                opnp_reset(q, image);
                for (uint32_t k = 0; k < tick_at[b]; ++k) opnp_tick(q, image);
                if (opnp_same_state(p, q)) {
                    *loop_tick = tick_at[b];
                    *end_tick = t;
                    return 1;
                }
            }
            if (arrivals == OPN_VGM_MAX_ARRIVALS) return 0;
            tick_at[arrivals] = t;
            hash_at[arrivals++] = h;
        }
        if (t == max_ticks) return 0;
        opnp_tick(p, image);
    }
}

size_t opn_vgm_render(const OpnImage* image, uint32_t max_ticks, OpnPlayer* players, uint8_t* out, size_t capacity,
                      OpnVgmInfo* info) {
    memset(info, 0, sizeof *info);
    if (image->info.loaded != OPN_LOADED) return 0;
    uint32_t loop_tick = 0, end_tick = max_ticks;
    const int looped = find_loop(image, max_ticks, &players[0], &players[1], &loop_tick, &end_tick);
    if (!looped) end_tick = max_ticks;
    OpnPlayer* player = &players[0];
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
    for (uint32_t t = 0; t < end_tick; ++t) {
        if (looped && t == loop_tick) { /* only the player's own writes follow, on every pass */
            loop_offset = o.n;
            loop_samples = samples;
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
    put(&o, 0x66);
    info->ticks = end_tick;
    info->size = o.n;
    info->samples = samples;
    if (looped) {
        info->loop_tick = loop_tick;
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
    /* SSG (AY8910) flags. VGM 1.71 specification (vgmrips vgmspec171.txt): $79 "AY8910 Flags ... Default is 0x01"
     * (bit 0 Legacy Output), and $7A / $7B are the same flags for the AY8910 "connected with/part of" the YM2203 /
     * YM2608: the default. Bit 4 (YM2149 pin 26, an extra /2) stays 0: the SSG divider is the chip's own prescaler. */
    if (opna) {
        vgm_put32(out + 0x48, OPN_CLOCK_YM2608);
        out[0x7B] = 0x01;
    } else {
        vgm_put32(out + 0x44, OPN_CLOCK_YM2203);
        out[0x7A] = 0x01;
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
    OpnPlayer* players = (OpnPlayer*)malloc(2 * sizeof *players);
    uint8_t* vgm = NULL;
    if (!img || !players) {
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
    opn_vgm_render(img, max_ticks, players, NULL, 0, &info); /* measure: the player is deterministic */
    const size_t body = info.size, tag = vgm_gd3(title, system, author, date, GD3_CONVERTER, NULL);
    vgm = (uint8_t*)malloc(body + tag);
    if (!vgm) {
        *problem = "out of memory";
        goto done;
    }
    opn_vgm_render(img, max_ticks, players, vgm, body, &info);
    vgm_gd3(title, system, author, date, GD3_CONVERTER, vgm + body);
    vgm_put32(vgm + 0x04, (uint32_t)(body + tag - 4));
    vgm_put32(vgm + 0x14, (uint32_t)(body - 0x14));
    *size = body + tag;
done:
    free(img);
    free(players);
    return vgm;
}

void opn_export_free(uint8_t* vgm) { free(vgm); }
