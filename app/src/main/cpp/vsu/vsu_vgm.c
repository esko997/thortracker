/* VGM 1.71 export (vsu_vgm.h) and the hosts' entry point (vsu_export.h). */
#include "vsu_vgm.h"

#include <stdlib.h>
#include <string.h>

#include "vgm_gd3.h"
#include "vsu_export.h"

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

/* The batch in player->write[] to the chip and into the file. */
static void put_batch(Out* o, VbVgmScratch* s) {
    const VbPlayer* p = &s->player;
    for (int i = 0; i < p->writes; ++i) {
        const VbWrite w = p->write[i];
        vsu_write(&s->chip, w.reg, w.val);
        put(o, 0xC7);
        put(o, w.reg >> 8);
        put(o, w.reg & 0xFF);
        put(o, w.val);
    }
}

size_t vb_vgm_render(const VsuImage* image, uint32_t max_ticks, VbVgmScratch* scratch, uint8_t* out, size_t capacity,
                     VbVgmInfo* info) {
    memset(info, 0, sizeof *info);
    if (image->info.loaded != VB_LOADED) return 0;
    const VbInfo* in = &image->info;
    Out o = {out, VB_VGM_HEADER, capacity, capacity < VB_VGM_HEADER};
    VbPlayer* p = &scratch->player;
    VsuChip* chip = &scratch->chip;
    vsu_reset(chip);
    vbp_reset(p, image);
    put_batch(&o, scratch);
    uint64_t samples = 0, arrival_samples = 0, loop_start = 0;
    size_t arrival_offset = 0, loop_offset = 0;
    uint32_t arrival_tick = 0, loop_tick = 0;
    int arrivals = 0, looped = 0;
    uint32_t t = 0;
    for (;; ++t) {
        if (p->order == in->loop && p->row == 0 && p->tick == 0) {
            if (arrivals && vbp_state_equal(p, &scratch->arrival_player) && vsu_held_equal(chip, &scratch->arrival_chip)) {
                looped = 1;
                loop_offset = arrival_offset;
                loop_start = arrival_samples;
                loop_tick = arrival_tick;
                break;
            }
            scratch->arrival_player = *p;
            scratch->arrival_chip = *chip;
            arrival_offset = o.n;
            arrival_samples = samples;
            arrival_tick = t;
            ++arrivals;
        }
        if (t >= max_ticks) break;
        vbp_tick(p, image);
        put_batch(&o, scratch);
        vsu_run(chip, VBP_TICK_CLOCKS);
        put(&o, 0x63);
        samples += VB_VGM_TICK_SAMPLES;
    }
    put(&o, 0x66);
    info->ticks = t;
    info->samples = (uint32_t)samples;
    info->size = o.n;
    if (looped) {
        info->loop_offset = (uint32_t)loop_offset;
        info->loop_samples = (uint32_t)(samples - loop_start);
        info->loop_tick = loop_tick;
    }
    if (o.overflow) return 0;
    memset(out, 0, VB_VGM_HEADER);
    memcpy(out, "Vgm ", 4);
    vgm_put32(out + 0x04, (uint32_t)(o.n - 4));
    vgm_put32(out + 0x08, 0x171);
    vgm_put32(out + 0x18, (uint32_t)samples);
    if (looped) {
        vgm_put32(out + 0x1C, (uint32_t)(loop_offset - 0x1C));
        vgm_put32(out + 0x20, info->loop_samples);
    }
    vgm_put32(out + 0x24, 50);
    vgm_put32(out + 0x34, VB_VGM_HEADER - 0x34);
    vgm_put32(out + 0xC4, VB_VGM_CLOCK);
    return o.n;
}

/* ---- host entry point (vsu_export.h): the file plus a GD3 tag -------------------------------------------- */

#define GD3_SYSTEM "Virtual Boy VSU (Thor Tracker)"
#define GD3_CONVERTER "Thor Tracker"

uint8_t* vb_export_vgm(const uint8_t* image, size_t size, int max_ticks, const char* title, const char* author,
                       const char* date, size_t* out_size, const char** problem) {
    *out_size = 0;
    *problem = NULL;
    if (size != VB_IMAGE_SIZE) {
        *problem = "image size";
        return NULL;
    }
    VsuImage* img = (VsuImage*)malloc(sizeof *img);
    VbVgmScratch* scratch = (VbVgmScratch*)malloc(sizeof *scratch);
    uint8_t* vgm = NULL;
    if (!img || !scratch) {
        *problem = "out of memory";
        goto done;
    }
    memcpy(img->bytes, image, VB_IMAGE_SIZE);
    if (vb_load_image(img)) {
        *problem = img->info.problem;
        goto done;
    }
    const uint32_t ticks = max_ticks < 0 ? 0u : (uint32_t)max_ticks;
    VbVgmInfo info;
    char today[16];
    date = vgm_date(date, today);
    vb_vgm_render(img, ticks, scratch, NULL, 0, &info); /* measure: the player and chip are deterministic */
    const size_t body = info.size, tag = vgm_gd3(title, GD3_SYSTEM, author, date, GD3_CONVERTER, NULL);
    vgm = (uint8_t*)malloc(body + tag);
    if (!vgm) {
        *problem = "out of memory";
        goto done;
    }
    vb_vgm_render(img, ticks, scratch, vgm, body, &info);
    vgm_gd3(title, GD3_SYSTEM, author, date, GD3_CONVERTER, vgm + body);
    vgm_put32(vgm + 0x04, (uint32_t)(body + tag - 4));
    vgm_put32(vgm + 0x14, (uint32_t)(body - 0x14));
    *out_size = body + tag;
done:
    free(img);
    free(scratch);
    return vgm;
}

void vb_export_free(uint8_t* file) { free(file); }
