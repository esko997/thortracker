/* VGM 1.71 export (ws_vgm.h) and the hosts' entry point (ws_export.h). */
#include "ws_vgm.h"

#include <stdlib.h>
#include <string.h>

#include "vgm_gd3.h"
#include "ws_apu.h"
#include "ws_export.h"
#include "ws_player.h"

#define VGM_HEADER 0x100
#define GD3_SYSTEM_MONO "WonderSwan (Thor Tracker)"
#define GD3_SYSTEM_COLOR "WonderSwan Color (Thor Tracker)"
#define GD3_CONVERTER "Thor Tracker"

static const char HYPER_VOICE_REFUSAL[] = "VGM's WonderSwan chip has no Hyper Voice: export WAV";

typedef struct Out {
    uint8_t* p;
    size_t n, cap;
    int overflow;
    int64_t sample; /* 44.1 kHz samples written so far (the current time) */
} Out;

static void put(Out* o, int byte) {
    if (o->p && o->n < o->cap) o->p[o->n] = (uint8_t)byte;
    else if (o->p) o->overflow = 1;
    ++o->n;
}

/* 44.1 kHz sample of cycle t: t * 44100 / 3072000 = t * 147 / 10240. */
static int64_t sample_of(int64_t cycle) { return cycle * 147 / 10240; }

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

static int loggable(const WsWrite* w) { return w->space == WS_SPACE_RAM || (w->addr >= 0x80 && w->addr <= 0x9F); }

/* An event (a batch or a transfer) at `cycle`: nothing without a loggable write, else the wait, then its writes. */
static void put_event(Out* o, int64_t cycle, const WsWrite* w, int n) {
    int any = 0;
    for (int i = 0; i < n && !any; ++i) any = loggable(&w[i]);
    if (!any) return;
    wait_until(o, sample_of(cycle));
    for (int i = 0; i < n; ++i) {
        if (!loggable(&w[i])) continue;
        if (w[i].space == WS_SPACE_RAM) {
            put(o, 0xC6);
            put(o, w[i].addr >> 8 & 0xFF);
            put(o, w[i].addr & 0xFF);
        } else {
            put(o, 0xBC);
            put(o, w[i].addr - 0x80);
        }
        put(o, w[i].val);
    }
}

/* The player, the voice timer and Sound DMA on the cycle timeline (no sound). */
typedef struct Run {
    WsPlayer player;
    WsApu apu;          /* only its Sound DMA runs */
    int64_t tick;       /* the next tick */
    int64_t next_irq;
    Out* out;           /* events go here (NULL: none) */
} Run;

static void dma_event(void* ctx, uint64_t cycle, int target, uint8_t data) {
    Run* r = (Run*)ctx;
    if (!r->out || target != 0) return;
    const WsWrite w = {WS_SPACE_PORT, 0x89, data};
    put_event(r->out, (int64_t)cycle, &w, 1);
}

static void send(Run* r, int64_t cycle) {
    const WsPlayer* p = &r->player;
    if (r->out) put_event(r->out, cycle, p->write, p->writes);
    for (int i = 0; i < p->writes; ++i) ws_apu_write(&r->apu, &p->write[i]);
}

static void run_reset(Run* r, const WsImage* image, Out* out) {
    r->out = out;
    ws_apu_reset(&r->apu, image->info.model);
    ws_apu_map_rom(&r->apu, WS_DMA_BASE, image->bytes + WS_OFF_DATA, WS_DATA_CAPACITY);
    ws_apu_set_hook(&r->apu, dma_event, r);
    wsp_reset(&r->player, image);
    r->tick = 0;
    r->next_irq = wsp_timer_cycles(&r->player);
    send(r, 0);
}

static void run_tick(Run* r, const WsImage* image) {
    WsPlayer* p = &r->player;
    const int64_t start = r->tick * WS_TICK_CYCLES;
    wsp_tick(p, image);
    send(r, start);
    for (int b = 0; b < WS_TICK_CYCLES / WS_BLOCK_CYCLES; ++b) {
        const int64_t c = start + (int64_t)WS_BLOCK_CYCLES * b;
        if (r->next_irq <= c) {
            wsp_timer(p, image);
            send(r, c);
            r->next_irq = c + wsp_timer_cycles(p);
        }
        ws_apu_dma_block(&r->apu);
    }
    ++r->tick;
}

static int at_loop(const Run* r, const WsImage* image) {
    return r->player.order == image->info.loop && r->player.row == 0 && r->player.tick == 0;
}

static uint64_t fnv(uint64_t h, uint64_t v) {
    for (int i = 0; i < 8; ++i) h = (h ^ (v >> (8 * i) & 0xFF)) * 0x100000001B3ull;
    return h;
}

/* The digest of a run's complete state at a tick start: player, timer (cycles to the next interrupt), DMA unit. */
static uint64_t run_hash(const Run* r) {
    const WsApuDma* d = &r->apu.dma;
    uint64_t h = wsp_state_hash(&r->player);
    h = fnv(h, (uint64_t)(r->next_irq - r->tick * WS_TICK_CYCLES));
    const uint64_t f[] = {d->source, d->length, d->shadow_source, d->shadow_length, d->rate, d->hold,
                          d->loop,   d->target, d->direction,     d->enable,        d->clock};
    for (size_t i = 0; i < sizeof f / sizeof f[0]; ++i) h = fnv(h, f[i]);
    return h;
}

static int same_run(const Run* a, const Run* b) {
    return wsp_same_state(&a->player, &b->player) &&
           a->next_irq - a->tick * WS_TICK_CYCLES == b->next_irq - b->tick * WS_TICK_CYCLES &&
           ws_apu_same_dma(&a->apu.dma, &b->apu.dma);
}

/* Finds the loop: Ab one of the first 64 arrivals at the loop point, Aa the first later arrival at a tick below
 * max_ticks whose complete state equals one of them (a digest proposes, an exact comparison against a second run
 * replayed to Ab decides). Returns 1 with *loop_tick = Ab, *end_tick = Aa, or 0. */
static int find_loop(const WsImage* image, uint32_t max_ticks, Run* a, Run* b, uint32_t* loop_tick, uint32_t* end_tick) {
    uint32_t tick_at[WS_VGM_MAX_ARRIVALS];
    uint64_t hash_at[WS_VGM_MAX_ARRIVALS];
    int arrivals = 0;
    run_reset(a, image, NULL);
    for (uint32_t t = 0; t < max_ticks; ++t) {
        if (at_loop(a, image)) {
            const uint64_t h = run_hash(a);
            for (int k = 0; k < arrivals; ++k) {
                if (hash_at[k] != h) continue;
                run_reset(b, image, NULL);
                while (b->tick < tick_at[k]) run_tick(b, image);
                if (same_run(a, b)) {
                    *loop_tick = tick_at[k];
                    *end_tick = t;
                    return 1;
                }
            }
            if (arrivals < WS_VGM_MAX_ARRIVALS) {
                tick_at[arrivals] = t;
                hash_at[arrivals++] = h;
            }
        }
        run_tick(a, image);
    }
    return 0;
}

int ws_vgm_has_hyper_voice(const WsImage* image) {
    if (image->info.model != WS_MODEL_COLOR) return 0;
    for (int o = 0; o < image->info.orders; ++o) {
        WsCursor c = {ws_u16(image->bytes + WS_OFF_ORDERS + 2 * (WS_CHANNELS * o + WS_HYPER_VOICE)), 0};
        for (int r = 0; r < image->info.rows[o]; ++r) {
            WsRow row;
            ws_next_row(image->bytes, &c, &row);
            if (row.note >= WS_NOTE_MIN && row.note <= WS_NOTE_MAX) return 1;
        }
    }
    return 0;
}

int ws_vgm_render(const WsImage* image, uint32_t max_ticks, uint8_t* out, size_t capacity, WsVgmInfo* info,
                  const char** problem) {
    memset(info, 0, sizeof *info);
    if (problem) *problem = NULL;
    if (image->info.loaded != WS_LOADED) return -1;
    if (ws_vgm_has_hyper_voice(image)) {
        if (problem) *problem = HYPER_VOICE_REFUSAL;
        return -2;
    }
    Run* runs = (Run*)calloc(2, sizeof *runs);
    if (!runs) return -1;
    uint32_t loop_tick = 0, end_tick = max_ticks;
    const int looped = find_loop(image, max_ticks, &runs[0], &runs[1], &loop_tick, &end_tick);
    if (!looped) end_tick = max_ticks;
    Out o = {out, VGM_HEADER, capacity, out && capacity < VGM_HEADER, 0};
    Run* r = &runs[0];
    run_reset(r, image, &o);
    int64_t loop_sample = 0;
    size_t loop_offset = 0;
    for (uint32_t t = 0; t < end_tick; ++t) {
        if (looped && t == loop_tick) { /* after the wait to Ab's start, before its writes */
            loop_sample = sample_of((int64_t)t * WS_TICK_CYCLES);
            wait_until(&o, loop_sample);
            loop_offset = o.n;
        }
        run_tick(r, image);
    }
    const int64_t total = sample_of((int64_t)end_tick * WS_TICK_CYCLES);
    wait_until(&o, total);
    put(&o, 0x66);
    free(runs);
    info->ticks = end_tick;
    info->size = o.n;
    info->samples = (uint32_t)total;
    if (looped) {
        info->loop_tick = loop_tick;
        info->loop_offset = (uint32_t)loop_offset;
        info->loop_samples = (uint32_t)(total - loop_sample);
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
    vgm_put32(out + 0x34, VGM_HEADER - 0x34);
    vgm_put32(out + 0xC0, WS_VGM_CLOCK);
    return 0;
}

/* ---- host entry point (ws_export.h): the file plus a GD3 tag ---------------------------------------------------- */

uint8_t* ws_export_vgm(const uint8_t* image, size_t size, uint32_t max_ticks, const char* title, const char* author,
                       const char* date, size_t* out_size, const char** problem) {
    *out_size = 0;
    *problem = NULL;
    if (!image || size != WS_IMAGE_SIZE) {
        *problem = "image size";
        return NULL;
    }
    WsImage* img = (WsImage*)malloc(sizeof *img);
    uint8_t* vgm = NULL;
    if (!img) {
        *problem = "out of memory";
        return NULL;
    }
    memcpy(img->bytes, image, WS_IMAGE_SIZE);
    if (ws_load_image(img)) {
        *problem = img->info.problem;
        goto done;
    }
    const char* system = img->info.model == WS_MODEL_COLOR ? GD3_SYSTEM_COLOR : GD3_SYSTEM_MONO;
    WsVgmInfo info;
    char today[16];
    date = vgm_date(date, today);
    const int rc = ws_vgm_render(img, max_ticks, NULL, 0, &info, problem); /* measure: the player is deterministic */
    if (rc == -2) goto done;
    if (rc) {
        *problem = "out of memory";
        goto done;
    }
    const size_t body = info.size, tag = vgm_gd3(title, system, author, date, GD3_CONVERTER, NULL);
    vgm = (uint8_t*)malloc(body + tag);
    if (!vgm) {
        *problem = "out of memory";
        goto done;
    }
    if (ws_vgm_render(img, max_ticks, vgm, body, &info, problem)) {
        free(vgm);
        vgm = NULL;
        if (!*problem) *problem = "out of memory";
        goto done;
    }
    vgm_gd3(title, system, author, date, GD3_CONVERTER, vgm + body);
    vgm_put32(vgm + 0x04, (uint32_t)(body + tag - 4));
    vgm_put32(vgm + 0x14, (uint32_t)(body - 0x14));
    *out_size = body + tag;
done:
    free(img);
    return vgm;
}

void ws_export_free(uint8_t* vgm) { free(vgm); }
