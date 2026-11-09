/* VGM 1.71 export (scc_vgm.h) and the hosts' entry point (scc_export.h). Specification: docs/AUDIO_ENGINE.md,
 * "SCC and SCC+: VGM export"; tools/audio/scc/scc_ref.py writes the same bytes. */
#include "scc_vgm.h"

#include <stdlib.h>
#include <string.h>

#include "scc_export.h"
#include "scc_k051649.h"
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

static void put_reg(Out* o, int port, int reg, int val) {
    put(o, 0xD2);
    put(o, port);
    put(o, reg);
    put(o, val);
}

static void put_writes(Out* o, const ScPlayer* p) {
    for (int i = 0; i < p->writes; ++i) put_reg(o, p->write[i].port, p->write[i].reg, p->write[i].val);
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

/* The chip's whole register state from the player's shadow, at the loop point, so every pass of the loop starts from
 * the same registers although the player's writes are diffed: test & 3 first (frequency mode kept; protection and
 * restart off, so the wave and period writes land unchanged), every wave RAM byte, periods, volumes, key, then the
 * test register itself. */
static void put_state(Out* o, const ScPlayer* p) {
    const int plus = p->variant == SC_VARIANT_PLUS;
    put_reg(o, SCK_PORT_TEST, 0, p->sh_test & (SCK_TEST_FREQ4 | SCK_TEST_FREQ8));
    const int rams = plus ? SC_CHANNELS : 4;
    for (int r = 0; r < rams * SC_WAVE_BYTES; ++r)
        put_reg(o, plus ? SCK_PORT_WAVE_PLUS : SCK_PORT_WAVE, r, (uint8_t)p->sh_ram[r / SC_WAVE_BYTES][r % SC_WAVE_BYTES]);
    for (int r = 0; r < 2 * SC_CHANNELS; ++r) put_reg(o, SCK_PORT_PERIOD, r, p->sh_period[r]);
    for (int c = 0; c < SC_CHANNELS; ++c) put_reg(o, SCK_PORT_VOLUME, c, p->sh_volume[c]);
    put_reg(o, SCK_PORT_KEY, 0, p->sh_key);
    put_reg(o, SCK_PORT_TEST, 0, p->sh_test);
}

size_t sc_vgm_render(const SccImage* image, uint32_t max_ticks, ScPlayer* player, uint8_t* out, size_t capacity,
                     ScVgmInfo* info) {
    memset(info, 0, sizeof *info);
    if (image->info.loaded != SC_LOADED) return 0;
    Out o = {out, VGM_HEADER, capacity, capacity < VGM_HEADER};
    scp_reset(player, image);
    put_writes(&o, player);
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
        scp_tick(player, image);
        acc += 44100;
        const uint32_t n = acc / period;
        acc -= n * period;
        samples += n;
        put_writes(&o, player);
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
    vgm_put32(out + 0x34, VGM_HEADER - 0x34);
    vgm_put32(out + 0x9C, SC_VGM_CLOCK | (image->info.variant == SC_VARIANT_PLUS ? SC_VGM_CLOCK_PLUS : 0));
    info->samples = samples;
    return o.n;
}

/* ---- host entry point (scc_export.h): the file plus a GD3 tag ---------------------------------------------------- */

uint8_t* sc_export_vgm(const uint8_t* image, size_t image_size, uint32_t max_ticks, const char* title,
                       const char* author, const char* date, size_t* size, const char** problem) {
    *size = 0;
    *problem = NULL;
    if (image_size != SC_IMAGE_SIZE) {
        *problem = "image size";
        return NULL;
    }
    SccImage* img = (SccImage*)malloc(sizeof *img);
    ScPlayer* player = (ScPlayer*)malloc(sizeof *player);
    uint8_t* vgm = NULL;
    if (!img || !player) {
        *problem = "out of memory";
        goto done;
    }
    memcpy(img->bytes, image, SC_IMAGE_SIZE);
    if (sc_load_image(img)) {
        *problem = img->info.problem;
        goto done;
    }
    const char* system = img->info.variant == SC_VARIANT_PLUS ? SC_GD3_SYSTEM_PLUS : SC_GD3_SYSTEM_SCC;
    ScVgmInfo info;
    char today[16];
    date = vgm_date(date, today);
    sc_vgm_render(img, max_ticks, player, NULL, 0, &info); /* measure: the player is deterministic */
    const size_t body = info.size, tag = vgm_gd3(title, system, author, date, SC_GD3_CONVERTER, NULL);
    vgm = (uint8_t*)malloc(body + tag);
    if (!vgm) {
        *problem = "out of memory";
        goto done;
    }
    sc_vgm_render(img, max_ticks, player, vgm, body, &info);
    vgm_gd3(title, system, author, date, SC_GD3_CONVERTER, vgm + body);
    vgm_put32(vgm + 0x04, (uint32_t)(body + tag - 4));
    vgm_put32(vgm + 0x14, (uint32_t)(body - 0x14));
    *size = body + tag;
done:
    free(img);
    free(player);
    return vgm;
}

void sc_export_free(uint8_t* vgm) { free(vgm); }
