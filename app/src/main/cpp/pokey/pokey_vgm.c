/* VGM 1.71 export (pokey_vgm.h) and the hosts' entry point (pokey_export.h). */
#include "pokey_vgm.h"

#include <stdlib.h>
#include <string.h>

#include "pokey_export.h"
#include "vgm_gd3.h"

#define VGM_HEADER 0x100

typedef struct Out {
    uint8_t* p;
    size_t n, cap;
    int overflow;
    int machine;
    uint64_t samples; /* 44.1 kHz samples waited so far */
} Out;

static void put(Out* o, int byte) {
    if (o->n < o->cap) o->p[o->n] = (uint8_t)byte;
    else o->overflow = 1;
    ++o->n;
}

uint64_t pk_vgm_samples(int machine, uint64_t cycle) {
    /* 44100 / (315 MHz / 176) = 77 / 3125; 44100 / 1773447.5 = 88200 / 3546895 */
    return machine == PK_MACHINE_PAL ? cycle * 88200u / 3546895u : cycle * 77u / 3125u;
}

static void put_wait(Out* o, uint64_t n) {
    while (n) {
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
            const uint64_t k = n > 65535 ? 65535 : n;
            put(o, 0x61);
            put(o, (int)(k & 0xFF));
            put(o, (int)(k >> 8));
            n -= k;
        }
    }
}

/* Waits up to machine cycle `cycle`. */
static void wait_until(Out* o, uint64_t cycle) {
    const uint64_t s = pk_vgm_samples(o->machine, cycle);
    if (s > o->samples) {
        put_wait(o, s - o->samples);
        o->samples = s;
    }
}

static void put_reg(Out* o, int reg, int val) {
    put(o, 0xBB);
    put(o, reg);
    put(o, val);
}

/* The batch in player->write[] to the chip (every register: IRQEN routes the interrupts) and into the file (IRQEN
 * only routes interrupts to the player: not logged), at the chip's cycle. */
static void put_batch(Out* o, PkVgmScratch* s) {
    const PkPlayer* p = &s->player;
    for (int i = 0; i < p->writes; ++i) {
        pk_write(&s->chip, p->write[i].reg, p->write[i].val);
        if (p->write[i].reg == PK_IRQEN) continue;
        wait_until(o, s->chip.now);
        put_reg(o, p->write[i].reg, p->write[i].val);
    }
}

/* The registers a VGM player needs at the loop point (the player's writes are diffed, so the loop body alone would
 * assume the first pass's state). */
static void put_state(Out* o, const PkPlayer* p) {
    put_reg(o, PK_SKCTL, p->shadow[PK_SKCTL]);
    put_reg(o, PK_AUDCTL, p->shadow[PK_AUDCTL]);
    for (int reg = PK_AUDF1; reg <= PK_AUDC4; ++reg) put_reg(o, reg, p->shadow[reg]);
}

size_t pk_vgm_render(const PokeyImage* image, uint32_t max_ticks, PkVgmScratch* scratch, uint8_t* out, size_t capacity,
                     PkVgmInfo* info) {
    memset(info, 0, sizeof *info);
    if (image->info.loaded != PK_LOADED) return 0;
    const PkInfo* in = &image->info;
    Out o = {out, VGM_HEADER, capacity, capacity < VGM_HEADER, in->machine, 0};
    PkPlayer* p = &scratch->player;
    PokeyChip* chip = &scratch->chip;
    pk_reset(chip);
    pkp_reset(p, image);
    put_batch(&o, scratch);
    const uint32_t frame = in->machine == PK_MACHINE_PAL ? PK_FRAME_CYCLES_PAL : PK_FRAME_CYCLES_NTSC;
    uint32_t acc = 0;
    uint64_t start = 0, loop_samples = 0;
    size_t loop_offset = 0;
    int seen_loop = 0;
    uint32_t t = 0;
    for (; t < max_ticks; ++t) {
        if (p->order == in->loop && p->row == 0 && p->tick == 0) {
            if (seen_loop) break;
            seen_loop = 1;
            wait_until(&o, start);
            loop_offset = o.n;
            loop_samples = o.samples;
            put_state(&o, p);
        }
        acc += frame;
        const uint32_t cycles = acc / (uint32_t)in->multispeed;
        acc -= cycles * (uint32_t)in->multispeed;
        pkp_tick(p, image);
        put_batch(&o, scratch);
        const uint64_t end = start + cycles;
        while (pk_run(chip, end)) {
            pkp_irq(p, image, pk_take_irq(chip));
            put_batch(&o, scratch);
        }
        start = end;
    }
    const int looped = t < max_ticks;
    wait_until(&o, start);
    put(&o, 0x66);
    info->ticks = t;
    info->size = o.n;
    if (o.overflow) return 0;
    memset(out, 0, VGM_HEADER);
    memcpy(out, "Vgm ", 4);
    vgm_put32(out + 0x04, (uint32_t)(o.n - 4));
    vgm_put32(out + 0x08, 0x171);
    vgm_put32(out + 0x18, (uint32_t)o.samples);
    if (looped) {
        vgm_put32(out + 0x1C, (uint32_t)(loop_offset - 0x1C));
        vgm_put32(out + 0x20, (uint32_t)(o.samples - loop_samples));
        info->loop_offset = (uint32_t)loop_offset;
        info->loop_samples = (uint32_t)(o.samples - loop_samples);
    }
    vgm_put32(out + 0x24, in->machine == PK_MACHINE_PAL ? 50 : 60);
    vgm_put32(out + 0x34, VGM_HEADER - 0x34);
    vgm_put32(out + 0xB0, in->machine == PK_MACHINE_PAL ? PK_VGM_CLOCK_PAL : PK_VGM_CLOCK_NTSC);
    info->samples = (uint32_t)o.samples;
    return o.n;
}

/* ---- host entry point (pokey_export.h): the file plus a GD3 tag -------------------------------------------- */

#define GD3_SYSTEM "Atari 8-bit POKEY (Thor Tracker)"
#define GD3_CONVERTER "Thor Tracker"

uint8_t* pk_export_vgm(const uint8_t* image, size_t size, int max_ticks, const char* title, const char* author,
                       const char* date, size_t* out_size, const char** problem) {
    *out_size = 0;
    *problem = NULL;
    if (size != PK_IMAGE_SIZE) {
        *problem = "image size";
        return NULL;
    }
    PokeyImage* img = (PokeyImage*)malloc(sizeof *img);
    PkVgmScratch* scratch = (PkVgmScratch*)malloc(sizeof *scratch);
    uint8_t* vgm = NULL;
    if (!img || !scratch) {
        *problem = "out of memory";
        goto done;
    }
    memcpy(img->bytes, image, PK_IMAGE_SIZE);
    if (pk_load_image(img)) {
        *problem = img->info.problem;
        goto done;
    }
    pk_init(&scratch->chip, NULL, NULL);
    const uint32_t ticks = max_ticks < 0 ? 0u : (uint32_t)max_ticks;
    PkVgmInfo info;
    char today[16];
    date = vgm_date(date, today);
    pk_vgm_render(img, ticks, scratch, NULL, 0, &info); /* measure: the player and chip are deterministic */
    const size_t body = info.size, tag = vgm_gd3(title, GD3_SYSTEM, author, date, GD3_CONVERTER, NULL);
    vgm = (uint8_t*)malloc(body + tag);
    if (!vgm) {
        *problem = "out of memory";
        goto done;
    }
    pk_vgm_render(img, ticks, scratch, vgm, body, &info);
    vgm_gd3(title, GD3_SYSTEM, author, date, GD3_CONVERTER, vgm + body);
    vgm_put32(vgm + 0x04, (uint32_t)(body + tag - 4));
    vgm_put32(vgm + 0x14, (uint32_t)(body - 0x14));
    *out_size = body + tag;
done:
    free(img);
    free(scratch);
    return vgm;
}

void pk_export_free(uint8_t* vgm) { free(vgm); }
