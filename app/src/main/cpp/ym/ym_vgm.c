/* VGM 1.71 / XGM2 / XGC exports of a YM2612 image (ym_vgm.h) and the hosts' entry point (ym_export.h). */
#include "ym_vgm.h"

#include <stdlib.h>
#include <string.h>

#include "vgm_gd3.h"
#include "ym_export.h"
#include "ym_machine.h"
#include "ym_player.h"
#include "ym_xgm.h"

#define VGM_HEADER 0x100
#define VGM_RATE 44100u
#define SID_ENTRIES 124

/* A growable byte buffer (exports only). */
typedef struct Buf {
    uint8_t* p;
    size_t n, cap;
    int failed;
} Buf;

static void reserve(Buf* b, size_t more) {
    if (b->failed || b->n + more <= b->cap) return;
    size_t cap = b->cap ? b->cap : 4096;
    while (cap < b->n + more) cap *= 2;
    uint8_t* p = (uint8_t*)realloc(b->p, cap);
    if (!p) {
        b->failed = 1;
        return;
    }
    b->p = p;
    b->cap = cap;
}

static void put(Buf* b, int byte) {
    reserve(b, 1);
    if (!b->failed) b->p[b->n++] = (uint8_t)byte;
}

static void put_bytes(Buf* b, const uint8_t* data, size_t n) {
    reserve(b, n);
    if (b->failed) return;
    memcpy(b->p + b->n, data, n);
    b->n += n;
}

static void put16(Buf* b, uint32_t v) {
    put(b, v & 0xFF);
    put(b, (v >> 8) & 0xFF);
}

static void put32(Buf* b, uint32_t v) {
    put16(b, v & 0xFFFF);
    put16(b, v >> 16);
}

/* ---- VGM: the machine's chip writes ------------------------------------------------------------------------------- */

typedef struct VgmWriter {
    Buf body, dac;
    uint64_t cur;        /* samples emitted */
    size_t last_dac_cmd; /* body offset of the last 0x80 command, or SIZE_MAX */
    uint64_t keyoff_at[8]; /* sample of each key select's last key-off + 1 (0 = none) */
    int dac_on, mode27, have27;
} VgmWriter;

static void vgm_wait(VgmWriter* w, uint64_t to) {
    if (to <= w->cur) return;
    uint64_t n = to - w->cur;
    w->cur = to;
    if (w->last_dac_cmd != (size_t)-1) { /* the DAC write's own wait (0x8n) */
        const uint64_t k = n < 15 ? n : 15;
        w->body.p[w->last_dac_cmd] = (uint8_t)(0x80 | k);
        n -= k;
        w->last_dac_cmd = (size_t)-1;
    }
    while (n) {
        if (n == 735 || n == 882) {
            put(&w->body, n == 735 ? 0x62 : 0x63);
            n = 0;
        } else if (n <= 16) {
            put(&w->body, 0x70 | (int)(n - 1));
            n = 0;
        } else {
            const uint64_t k = n > 65535 ? 65535 : n;
            put(&w->body, 0x61);
            put16(&w->body, (uint32_t)k);
            n -= k;
        }
    }
}

/* One chip write at sample `at`. */
static void vgm_write(VgmWriter* w, uint64_t at, int kind, int reg, int val) {
    if (kind == YM_W_PSG) {
        vgm_wait(w, at);
        w->last_dac_cmd = (size_t)-1;
        put(&w->body, 0x50);
        put(&w->body, val);
        return;
    }
    if (kind == YM_W_PORT0) {
        if (reg == 0x24 || reg == 0x25 || reg == 0x26) return; /* the driver's PCM timer */
        if (reg == 0x27) {
            if (w->have27 && (val & 0xC0) == w->mode27) return;
            w->have27 = 1;
            w->mode27 = val & 0xC0;
            val &= 0xC0;
        }
        if (reg == 0x2B) {
            const int on = (val & 0x80) != 0;
            if (on == w->dac_on) return;
            w->dac_on = on;
        }
        if (reg == 0x2A) {
            if (!w->dac_on) return; /* inaudible: FM 6 plays */
            vgm_wait(w, at);
            put(&w->dac, val);
            w->last_dac_cmd = w->body.n;
            put(&w->body, 0x80);
            return;
        }
        if (reg == 0x28 && (val & 3) != 3) {
            const int sel = val & 7;
            if (val & 0xF0) {
                if (w->keyoff_at[sel] && w->keyoff_at[sel] - 1 >= at) at = w->keyoff_at[sel]; /* retrigger: a sample apart */
            } else {
                w->keyoff_at[sel] = (at > w->cur ? at : w->cur) + 1;
            }
        }
    }
    vgm_wait(w, at);
    w->last_dac_cmd = (size_t)-1;
    put(&w->body, kind == YM_W_PORT1 ? 0x53 : 0x52);
    put(&w->body, reg);
    put(&w->body, val);
}

static int at_loop(const YmPlayer* p, const YmImage* image) {
    return p->order == image->info.loop && p->row == 0 && p->tick == 0;
}

static uint8_t* render_vgm(const YmImage* image, uint32_t max_ticks, YmVgmInfo* info, const char** problem) {
    const int pal = image->info.region == YM_PAL;
    const uint64_t mclk = pal ? YMM_MCLK_PAL : YMM_MCLK_NTSC;
    YmMachine* m = ymm_create(VGM_RATE);
    if (!m) {
        *problem = "out of memory";
        return NULL;
    }
    VgmWriter w;
    memset(&w, 0, sizeof w);
    w.last_dac_cmd = (size_t)-1;
    uint8_t* file = NULL;
    if (ymm_start(m, image, 0, 0, 0, NULL) != YMM_OK) {
        *problem = "driver fault";
        goto done;
    }
    /* The chips at the stream start: SGDK's resets (the driver's boot and play mutes leave the same registers). */
    YmWrite reset[YMP_MACHINE_RESET_WRITES];
    const int nreset = ymp_machine_reset(reset);
    for (int i = 0; i < nreset; ++i) vgm_write(&w, 0, reset[i].kind, reset[i].reg, reset[i].val);
    ymm_set_log(m, 1);
    /* Frame f executes at the interrupt that starts the tick after the driver executed frame f - 1 (normally silent
     * tick f + 1; a frame the driver skips executes a tick later). The VGM starts at silent tick 1, loops at the
     * tick executing the loop frame (the first arrival at the loop point) and ends after the tick executing the
     * frame before the second arrival (frames 0 .. E - 1, as the XGM2 export has them). */
    uint64_t cycles = 0, loop_cycles = 0;
    size_t loop_body = 0;
    uint32_t loop_dac = 0;
    /* the player has made frame 0 (the song start); before a tick it is at the next frame it encodes */
    int seen_loop = image->info.loop == 0;
    uint32_t loop_frame = 0, end_frame = max_ticks, checked = 0, ran = 0;
    int looped = 0;
    while (ran < end_frame) {
        const uint32_t next = ymm_frames(m);
        if (!looped && next > checked && next < max_ticks) {
            checked = next;
            if (at_loop(ymm_player(m), image)) {
                if (seen_loop) {
                    looped = 1;
                    end_frame = next; /* the pass ends with the frame before this one */
                } else {
                    seen_loop = 1;
                    loop_frame = next;
                }
            }
        }
        if (ymm_tick_silent(m) != YMM_OK) {
            *problem = "driver fault";
            goto done;
        }
        const uint32_t was = ran;
        ran = ymm_executed(m);
        int count;
        const YmmLogEntry* log = ymm_log(m, &count);
        if (seen_loop && was <= loop_frame && loop_frame < ran && !loop_body) { /* this tick executes the loop frame */
            vgm_wait(&w, cycles * VGM_RATE * YMM_MCLK_PER_Z80 / mclk);
            w.last_dac_cmd = (size_t)-1;
            loop_cycles = cycles;
            loop_body = w.body.n + 1;
            loop_dac = (uint32_t)w.dac.n;
            put(&w.body, 0xE0);
            put32(&w.body, loop_dac);
        }
        for (int i = 0; i < count; ++i)
            vgm_write(&w, (cycles + log[i].cycle) * VGM_RATE * YMM_MCLK_PER_Z80 / mclk, log[i].kind, log[i].reg, log[i].val);
        cycles += ymm_z80_cycles_per_tick(m);
    }
    const uint64_t total = cycles * VGM_RATE * YMM_MCLK_PER_Z80 / mclk;
    vgm_wait(&w, total);
    put(&w.body, 0x66);
    if (w.body.failed || w.dac.failed) {
        *problem = "out of memory";
        goto done;
    }
    {
        const size_t block = w.dac.n ? 7 + w.dac.n + 5 : 0; /* data block, then 0xE0 to its start */
        Buf out = {0};
        reserve(&out, VGM_HEADER + block + w.body.n);
        if (out.failed) {
            *problem = "out of memory";
            goto done;
        }
        memset(out.p, 0, VGM_HEADER);
        out.n = VGM_HEADER;
        if (w.dac.n) {
            put(&out, 0x67);
            put(&out, 0x66);
            put(&out, 0x00); /* YM2612 PCM data */
            put32(&out, (uint32_t)w.dac.n);
            put_bytes(&out, w.dac.p, w.dac.n);
            put(&out, 0xE0);
            put32(&out, 0);
        }
        const size_t body_at = out.n;
        put_bytes(&out, w.body.p, w.body.n);
        uint8_t* h = out.p;
        memcpy(h, "Vgm ", 4);
        vgm_put32(h + 0x04, (uint32_t)(out.n - 4));
        vgm_put32(h + 0x08, 0x171);
        vgm_put32(h + 0x0C, pal ? 3546895 : 3579545);
        vgm_put32(h + 0x18, (uint32_t)total);
        info->samples = (uint32_t)total;
        if (looped && loop_body) {
            /* at the 0xE0 that points the PCM data back to where the loop frame's DAC bytes start */
            const uint32_t at = (uint32_t)(body_at + loop_body - 1);
            const uint64_t loop_at = loop_cycles * VGM_RATE * YMM_MCLK_PER_Z80 / mclk;
            vgm_put32(h + 0x1C, at - 0x1C);
            vgm_put32(h + 0x20, (uint32_t)(total - loop_at));
            info->loop_offset = at;
            info->loop_samples = (uint32_t)(total - loop_at);
        }
        vgm_put32(h + 0x24, pal ? 50 : 60);
        h[0x28] = 0x09; /* SN76489 feedback 0x0009 */
        h[0x2A] = 16;   /* shift register width */
        vgm_put32(h + 0x2C, pal ? 7600489 : 7670453);
        vgm_put32(h + 0x34, VGM_HEADER - 0x34);
        info->ticks = ran;
        info->looped = looped;
        info->dac_bytes = (uint32_t)w.dac.n;
        info->size = out.n;
        file = out.p;
    }
done:
    free(w.body.p);
    free(w.dac.p);
    ymm_destroy(m);
    return file;
}

/* ---- XGM2: the player's frames ----------------------------------------------------------------------------------- */

typedef struct Frames {
    Buf fm, psg;            /* every frame's commands, back to back */
    uint32_t* fm_at;        /* frame f's commands: fm.p[fm_at[f] .. fm_at[f + 1]) */
    uint32_t* psg_at;
    uint32_t n, cap;
    int failed;
} Frames;

static void add_frame(Frames* f, const YmxFrame* x) {
    if (f->n + 2 > f->cap) {
        const uint32_t cap = f->cap ? 2 * f->cap : 1024;
        uint32_t* a = (uint32_t*)realloc(f->fm_at, sizeof *a * cap);
        if (a) f->fm_at = a;
        uint32_t* b = (uint32_t*)realloc(f->psg_at, sizeof *b * cap);
        if (b) f->psg_at = b;
        if (!a || !b) {
            f->failed = 1;
            return;
        }
        f->cap = cap;
    }
    if (f->n == 0) f->fm_at[0] = f->psg_at[0] = 0;
    put_bytes(&f->fm, x->fm, (size_t)x->fm_size);
    put_bytes(&f->psg, x->psg, (size_t)x->psg_size);
    ++f->n;
    f->fm_at[f->n] = (uint32_t)f->fm.n;
    f->psg_at[f->n] = (uint32_t)f->psg.n;
}

/* One stream: each frame's commands with a wait over the empty frames after it (never across the loop frame or into
 * the last frame), the last frame ending with the loop command; packed one block per frame. *loop_off = the stream
 * offset of the loop frame. */
static void build_stream(const Frames* f, int psg, uint32_t loop_frame, int looped, int packed, Buf* out, uint32_t* loop_off) {
    const Buf* src = psg ? &f->psg : &f->fm;
    const uint32_t* at = psg ? f->psg_at : f->fm_at;
    const int wait_max = psg ? YMX_PSG_WAIT_MAX : YMX_FM_WAIT_MAX;
    uint8_t block[YMX_FRAME_MAX];
    uint32_t i = 0;
    *loop_off = 0;
    while (i < f->n) {
        const int n = (int)(at[i + 1] - at[i]);
        memcpy(block, src->p + at[i], (size_t)n);
        int size = n;
        uint32_t next;
        if (i == loop_frame) *loop_off = (uint32_t)out->n;
        if (i + 1 == f->n) {
            size += ymx_loop(block + size, psg, looped ? (int32_t)*loop_off : YMX_NO_LOOP);
            next = f->n;
        } else {
            next = i + 1;
            while (next + 1 < f->n && next != loop_frame && at[next + 1] == at[next] && (int)(next - i) < wait_max) ++next;
            size += ymx_wait(block + size, psg, (int)(next - i));
        }
        if (packed) {
            uint8_t packed_block[YMX_PACKED_MAX];
            put_bytes(out, packed_block, ymx_pack(block, size, (uint32_t)out->n, packed_block));
        } else {
            put_bytes(out, block, (size_t)size);
        }
        i = next;
    }
}

static uint8_t* render_xgm2(const YmImage* image, int format, uint32_t max_ticks, YmVgmInfo* info, const char** problem) {
    YmPlayer* p = (YmPlayer*)malloc(sizeof *p);
    YmxFrame* x = (YmxFrame*)malloc(sizeof *x);
    YmWrite* writes = (YmWrite*)malloc(sizeof *writes * 2 * YMP_MAX_WRITES);
    Frames f;
    memset(&f, 0, sizeof f);
    Buf fm = {0}, psg = {0}, out = {0};
    uint8_t* file = NULL;
    if (!p || !x || !writes) {
        *problem = "out of memory";
        goto done;
    }
    ymp_reset(p, image);
    int n = p->writes, prefix_pcm[YM_PCM_CHANNELS];
    memcpy(writes, p->write, sizeof *writes * (size_t)n);
    memcpy(prefix_pcm, p->pcm, sizeof prefix_pcm);
    uint32_t loop_frame = 0, t = 0;
    int seen_loop = 0;
    for (; t < max_ticks; ++t) {
        const int loop = at_loop(p, image);
        if (loop) {
            if (seen_loop) break;
            seen_loop = 1;
            loop_frame = t;
        }
        ymp_tick(p, image);
        memcpy(writes + n, p->write, sizeof *writes * (size_t)p->writes);
        n += p->writes;
        int pcm[YM_PCM_CHANNELS];
        for (int k = 0; k < YM_PCM_CHANNELS; ++k) pcm[k] = p->pcm[k] != YMP_PCM_NONE ? p->pcm[k] : prefix_pcm[k];
        if (ymx_encode(image, p, writes, n, pcm, t == 0 || loop, x)) {
            *problem = "frame too large";
            goto done;
        }
        add_frame(&f, x);
        n = 0;
        for (int k = 0; k < YM_PCM_CHANNELS; ++k) prefix_pcm[k] = YMP_PCM_NONE;
    }
    const int looped = t < max_ticks;
    if (f.failed || f.fm.failed || f.psg.failed || f.n == 0) {
        *problem = "out of memory";
        goto done;
    }
    const int packed = format == YM_FORMAT_XGC;
    uint32_t fm_loop, psg_loop;
    build_stream(&f, 0, loop_frame, looped, packed, &fm, &fm_loop);
    build_stream(&f, 1, loop_frame, looped, packed, &psg, &psg_loop);
    info->fm_size = (uint32_t)fm.n;
    info->psg_size = (uint32_t)psg.n;
    const size_t fm_pad = (fm.n + 255) & ~(size_t)255, psg_pad = (psg.n + 255) & ~(size_t)255;
    const size_t samples = (size_t)image->info.pcm_pages * YM_PCM_ALIGN;
    if (format == YM_FORMAT_XGM) put_bytes(&out, (const uint8_t*)"XGM2", 4);
    put(&out, YM_XGM2_VERSION);
    put(&out, (image->info.region == YM_PAL ? 0x01 : 0) | (format == YM_FORMAT_XGM ? 0x04 : 0) | (packed ? 0x08 : 0));
    put16(&out, (uint32_t)(samples >> 8));
    put16(&out, (uint32_t)(fm_pad >> 8));
    put16(&out, (uint32_t)(psg_pad >> 8));
    const int variants = image->info.pcm_variants;
    for (int i = 0; i < SID_ENTRIES; ++i) {
        int start = 0, pages = 0, flags = 0;
        if (i < variants) ym_pcm_region(image, i + 1, &start, &pages, &flags);
        if (i < variants) put16(&out, (uint32_t)start);
        else if (i == variants && variants) put16(&out, (uint32_t)image->info.pcm_pages);
        else put16(&out, 0xFFFF);
    }
    put_bytes(&out, image->bytes + YM_OFF_PCM_BANK, samples);
    put_bytes(&out, fm.p, fm.n);
    for (size_t i = fm.n; i < fm_pad; ++i) put(&out, 0);
    put_bytes(&out, psg.p, psg.n);
    for (size_t i = psg.n; i < psg_pad; ++i) put(&out, 0);
    if (out.failed || fm.failed || psg.failed) {
        *problem = "out of memory";
        goto done;
    }
    info->ticks = t;
    info->looped = looped;
    if (looped) {
        info->loop_offset = fm_loop;
        info->psg_loop_offset = psg_loop;
    }
    info->size = out.n;
    file = out.p;
    out.p = NULL;
done:
    free(p);
    free(x);
    free(writes);
    free(f.fm.p);
    free(f.psg.p);
    free(f.fm_at);
    free(f.psg_at);
    free(fm.p);
    free(psg.p);
    free(out.p);
    return file;
}

uint8_t* ym_render(const YmImage* image, int format, uint32_t max_ticks, YmVgmInfo* info, const char** problem) {
    memset(info, 0, sizeof *info);
    *problem = NULL;
    if (image->info.loaded != YM_LOADED || format < YM_FORMAT_VGM || format > YM_FORMAT_XGC) {
        *problem = "format";
        return NULL;
    }
    return format == YM_FORMAT_VGM ? render_vgm(image, max_ticks, info, problem) : render_xgm2(image, format, max_ticks, info, problem);
}

/* ---- host entry point (ym_export.h) --------------------------------------------------------------------------- */

#define GD3_SYSTEM "Mega Drive (Thor Tracker)"
#define GD3_CONVERTER "Thor Tracker"

uint8_t* ym_export(const uint8_t* image, size_t image_size, int format, uint32_t max_ticks, const char* title,
                   const char* author, const char* date, size_t* size, const char** problem) {
    *size = 0;
    *problem = NULL;
    if (image_size != YM_IMAGE_SIZE) {
        *problem = "image size";
        return NULL;
    }
    if (format < YM_EXPORT_VGM || format > YM_EXPORT_XGC) {
        *problem = "format";
        return NULL;
    }
    YmImage* img = (YmImage*)malloc(sizeof *img);
    uint8_t* file = NULL;
    if (!img) {
        *problem = "out of memory";
        return NULL;
    }
    memcpy(img->bytes, image, YM_IMAGE_SIZE);
    if (ym_load_image(img)) {
        *problem = img->info.problem;
        free(img);
        return NULL;
    }
    char today[16];
    date = vgm_date(date, today);
    YmVgmInfo info;
    uint8_t* body = ym_render(img, format, max_ticks, &info, problem);
    free(img);
    if (!body) return NULL;
    const size_t tag = format == YM_EXPORT_XGC ? 0 : vgm_gd3(title, GD3_SYSTEM, author, date, GD3_CONVERTER, NULL);
    file = (uint8_t*)realloc(body, info.size + tag);
    if (!file) {
        free(body);
        *problem = "out of memory";
        return NULL;
    }
    if (format == YM_EXPORT_VGM) {
        vgm_gd3(title, GD3_SYSTEM, author, date, GD3_CONVERTER, file + info.size);
        vgm_put32(file + 0x04, (uint32_t)(info.size + tag - 4));
        vgm_put32(file + 0x14, (uint32_t)(info.size - 0x14));
    } else if (format == YM_EXPORT_XGM) {
        vgm_gd3(title, GD3_SYSTEM, author, date, GD3_CONVERTER, file + info.size); /* flags bit 2: after the data */
    }
    *size = info.size + tag;
    return file;
}

void ym_export_free(uint8_t* file) { free(file); }
