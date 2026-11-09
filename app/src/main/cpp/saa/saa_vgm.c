/* VGM 1.71 export (saa_vgm.h) and the hosts' entry point (saa_export.h). Specification: docs/AUDIO_ENGINE.md,
 * "SAA1099: VGM export"; tools/audio/saa/saa_ref.py writes the same bytes. */
#include "saa_vgm.h"

#include <stdlib.h>
#include <string.h>

#include "saa_core.h"
#include "saa_export.h"
#include "saa_machine.h"
#include "vgm_gd3.h"

#define VGM_HEADER 0x100
#define VGM_RATE 44100u

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

static void put_writes(Out* o, const SaPlayer* p) {
    for (int i = 0; i < p->writes; ++i) {
        const SaWrite* w = &p->write[i];
        put(o, 0xBD);
        put(o, w->reg | (w->chip ? SA_VGM_CHIP_B : 0));
        put(o, w->val);
    }
}

static void put_wait(Out* o, uint32_t n) {
    while (n) {
        if (n == 735) {
            put(o, 0x62);
            n = 0;
        } else if (n == 882) {
            put(o, 0x63);
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

static int at_loop(const SaPlayer* p, const SaaImage* image) {
    return p->order == image->info.loop && p->row == 0 && p->tick == 0;
}

size_t sa_vgm_render(const SaaImage* image, uint32_t max_ticks, SaPlayer scratch[2], uint8_t* out, size_t capacity,
                     SaVgmInfo* info) {
    memset(info, 0, sizeof *info);
    if (image->info.loaded != SA_LOADED) return 0;
    SaPlayer* player = &scratch[0];
    SaPlayer* arrival = &scratch[1]; /* the player as it was at the previous arrival at the loop point */
    const int cms = image->info.variant == SA_VARIANT_CMS;
    const uint32_t rate = cms ? SAAM_RATE_CMS : SAAM_RATE_SAM;
    Out o = {out, VGM_HEADER, capacity, capacity < VGM_HEADER};
    sap_reset(player, image);
    put_writes(&o, player);
    const uint32_t period = rate * (uint32_t)image->info.multispeed;
    uint32_t acc = 0, samples = 0, arrival_samples = 0;
    size_t arrival_offset = 0;
    int arrived = 0, looped = 0;
    uint32_t t = 0;
    for (; t < max_ticks; ++t) {
        if (at_loop(player, image)) {
            if (arrived && sap_same_state(arrival, player)) {
                looped = 1; /* the previous arrival's pass repeats exactly from here */
                break;
            }
            arrived = 1;
            memcpy(arrival, player, sizeof *arrival);
            arrival_offset = o.n;
            arrival_samples = samples;
        }
        sap_tick(player, image);
        acc += VGM_RATE;
        const uint32_t n = acc / period;
        acc -= n * period;
        samples += n;
        put_writes(&o, player);
        put_wait(&o, n);
    }
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
        vgm_put32(out + 0x1C, (uint32_t)(arrival_offset - 0x1C));
        vgm_put32(out + 0x20, samples - arrival_samples);
        info->loop_offset = (uint32_t)arrival_offset;
        info->loop_samples = samples - arrival_samples;
    }
    vgm_put32(out + 0x24, rate);
    vgm_put32(out + 0x34, VGM_HEADER - 0x34);
    vgm_put32(out + 0xC8, cms ? SA_VGM_DUAL | (uint32_t)SAC_CLOCK_CMS : (uint32_t)SAC_CLOCK_SAM);
    info->samples = samples;
    return o.n;
}

/* ---- host entry point (saa_export.h): the file plus a GD3 tag ---------------------------------------------------- */

uint8_t* sa_export_vgm(const uint8_t* image, size_t image_size, uint32_t max_ticks, const char* title,
                       const char* author, const char* date, size_t* size, const char** problem) {
    *size = 0;
    *problem = NULL;
    if (image_size != SA_IMAGE_SIZE) {
        *problem = "image size";
        return NULL;
    }
    SaaImage* img = (SaaImage*)malloc(sizeof *img);
    SaPlayer* scratch = (SaPlayer*)malloc(2 * sizeof *scratch);
    uint8_t* vgm = NULL;
    if (!img || !scratch) {
        *problem = "out of memory";
        goto done;
    }
    memcpy(img->bytes, image, SA_IMAGE_SIZE);
    if (sa_load_image(img)) {
        *problem = img->info.problem;
        goto done;
    }
    const char* system = img->info.variant == SA_VARIANT_CMS ? SA_GD3_SYSTEM_CMS : SA_GD3_SYSTEM_SAM;
    SaVgmInfo info;
    char today[16];
    date = vgm_date(date, today);
    sa_vgm_render(img, max_ticks, scratch, NULL, 0, &info); /* measure: the player is deterministic */
    const size_t body = info.size, tag = vgm_gd3(title, system, author, date, SA_GD3_CONVERTER, NULL);
    vgm = (uint8_t*)malloc(body + tag);
    if (!vgm) {
        *problem = "out of memory";
        goto done;
    }
    sa_vgm_render(img, max_ticks, scratch, vgm, body, &info);
    vgm_gd3(title, system, author, date, SA_GD3_CONVERTER, vgm + body);
    vgm_put32(vgm + 0x04, (uint32_t)(body + tag - 4));
    vgm_put32(vgm + 0x14, (uint32_t)(body - 0x14));
    *size = body + tag;
done:
    free(img);
    free(scratch);
    return vgm;
}

void sa_export_free(uint8_t* vgm) { free(vgm); }
