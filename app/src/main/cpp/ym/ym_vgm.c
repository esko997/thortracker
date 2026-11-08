/* VGM 1.71 / XGM / XGC export of a YM2612 image (ym_vgm.h) and the hosts' entry point (ym_export.h). */
#include "ym_vgm.h"

#include <stdlib.h>
#include <string.h>

#include "vgm_gd3.h"
#include "ym_export.h"

#define VGM_HEADER 0x100
#define XGM_HEADER 0x108 /* "XGM ", 63 sample ids, sample block size, version, flags, music size */
#define XGC_HEADER 0x104 /* 63 sample ids, sample block size, version, flags, music size */
#define FRAME_WRITES (YMP_MACHINE_RESET_WRITES + YMP_DUMP_WRITES + YMP_MAX_WRITES + 8)

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

static void put_bytes(Out* o, const uint8_t* b, size_t n) {
    for (size_t i = 0; i < n; ++i) put(o, b[i]);
}

static void put_writes(Out* o, const YmWrite* w, int from, int to) {
    for (int i = from; i < to; ++i) {
        if (w[i].kind == YM_W_PSG) {
            put(o, 0x50);
        } else {
            put(o, w[i].kind ? 0x53 : 0x52);
            put(o, w[i].reg);
        }
        put(o, w[i].val);
    }
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

static void put24(Out* o, uint32_t v) {
    put(o, v & 0xFF);
    put(o, (v >> 8) & 0xFF);
    put(o, (v >> 16) & 0xFF);
}

size_t ym_render(const YmImage* image, int format, uint32_t max_ticks, YmPlayer* player, YmXgmEncoder* encoder,
                 uint8_t* out, size_t capacity, YmVgmInfo* info) {
    memset(info, 0, sizeof *info);
    if (image->info.loaded != YM_LOADED || format < YM_FORMAT_VGM || format > YM_FORMAT_XGC) return 0;
    if (format != YM_FORMAT_VGM && !encoder) return 0;
    const size_t header = format == YM_FORMAT_VGM ? VGM_HEADER : format == YM_FORMAT_XGM ? XGM_HEADER : XGC_HEADER;
    Out o = {out, header, capacity, capacity < header};
    const int pal = image->info.region == YM_PAL;
    const uint32_t tick_samples = pal ? 882 : 735;
    static const uint8_t xgc_empty_frame = 0x01;
    if (format == YM_FORMAT_XGC) /* xgmtool's 3 empty frames for PCM alignment */
        for (int i = 0; i < 3; ++i) put(&o, xgc_empty_frame);
    if (encoder) ymx_reset(encoder);
    YmWrite frame[FRAME_WRITES];
    ymp_reset(player, image);
    int n = ymp_machine_reset(frame);
    memcpy(frame + n, player->write, sizeof frame[0] * (size_t)player->writes);
    n += player->writes;
    uint32_t samples = 0, loop_samples = 0, loop_offset = 0;
    int seen_loop = 0;
    uint32_t t = 0;
    for (; t < max_ticks; ++t) {
        int loop_at = -1;
        if (player->order == image->info.loop && player->row == 0 && player->tick == 0) {
            if (seen_loop) break;
            seen_loop = 1;
            loop_at = n;
            loop_samples = samples;
            n += ymp_dump(player, frame + n);
        }
        ymp_tick(player, image);
        const int offs_end = n + player->offs;
        memcpy(frame + n, player->write, sizeof frame[0] * (size_t)player->writes);
        n += player->writes;
        samples += tick_samples;
        if (format == YM_FORMAT_VGM) {
            /* the prefix, the loop point, the dump, the key-off phase, one sample, the rest (ym_player.h) */
            put_writes(&o, frame, 0, loop_at < 0 ? 0 : loop_at);
            if (loop_at >= 0) loop_offset = (uint32_t)o.n;
            put_writes(&o, frame, loop_at < 0 ? 0 : loop_at, offs_end);
            uint32_t wait = tick_samples;
            if (player->offs) {
                put_wait(&o, 1);
                --wait;
            }
            put_writes(&o, frame, offs_end, n);
            put_wait(&o, wait);
        } else {
            if (loop_at >= 0) loop_offset = (uint32_t)(o.n - header);
            size_t xgm_size = 0, xgc_size = 0;
            uint8_t xgc[YMX_MAX_FRAME_BYTES];
            const uint8_t* xgm = ymx_frame(encoder, frame, n, loop_at, &xgm_size, format == YM_FORMAT_XGC ? xgc : NULL,
                                           sizeof xgc, &xgc_size);
            if (!xgm) return 0;
            if (format == YM_FORMAT_XGM) {
                put_bytes(&o, xgm, xgm_size);
                put(&o, 0x00); /* frame */
            } else {
                put_bytes(&o, xgc, xgc_size);
            }
        }
        n = 0;
    }
    const int looped = t < max_ticks;
    if (format == YM_FORMAT_VGM) {
        put(&o, 0x66);
    } else if (format == YM_FORMAT_XGM) {
        if (looped) {
            put(&o, 0x7E);
            put24(&o, loop_offset);
        }
        put(&o, 0x7F);
    } else if (looped) { /* the last frame: frame skip, loop, end (XGC_extractMusic) */
        put(&o, 7);
        put(&o, YMX_XGC_FRAME_SKIP);
        put(&o, YMX_XGC_LOOP);
        put24(&o, loop_offset);
        put(&o, YMX_XGC_END);
    } else {
        put(&o, 2);
        put(&o, YMX_XGC_END);
    }
    info->ticks = t;
    info->size = o.n;
    info->looped = looped;
    info->samples = samples;
    if (looped) {
        info->loop_offset = loop_offset;
        info->loop_samples = samples - loop_samples;
    }
    if (o.overflow) return 0;
    memset(out, 0, header);
    if (format == YM_FORMAT_VGM) {
        memcpy(out, "Vgm ", 4);
        vgm_put32(out + 0x04, (uint32_t)(o.n - 4));
        vgm_put32(out + 0x08, 0x171);
        vgm_put32(out + 0x0C, pal ? 3546895 : 3579545);
        vgm_put32(out + 0x18, samples);
        if (looped) {
            vgm_put32(out + 0x1C, loop_offset - 0x1C);
            vgm_put32(out + 0x20, samples - loop_samples);
        }
        vgm_put32(out + 0x24, pal ? 50 : 60);
        out[0x28] = 0x09; /* SN76489 feedback 0x0009 */
        out[0x2A] = 16;   /* shift register width */
        vgm_put32(out + 0x2C, pal ? 7600489 : 7670453);
        vgm_put32(out + 0x34, VGM_HEADER - 0x34);
    } else {
        uint8_t* ids = out;
        if (format == YM_FORMAT_XGM) {
            memcpy(out, "XGM ", 4);
            ids = out + 4;
        }
        for (int i = 0; i < 63; ++i) { /* no samples: the "silent" mark xgmtool writes */
            ids[4 * i] = ids[4 * i + 1] = 0xFF;
            ids[4 * i + 2] = format == YM_FORMAT_XGC ? 0x01 : 0x00;
        }
        /* ids + 252: sample block size 0 (2 bytes); then version (XGM 1, XGC 0) and flags (bit 0 PAL) */
        ids[254] = format == YM_FORMAT_XGM ? 0x01 : 0x00;
        ids[255] = (uint8_t)pal;
        vgm_put32(ids + 256, (uint32_t)(o.n - header));
    }
    return o.n;
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
    YmPlayer* player = (YmPlayer*)malloc(sizeof *player);
    YmXgmEncoder* encoder = format == YM_EXPORT_VGM ? NULL : (YmXgmEncoder*)malloc(sizeof *encoder);
    uint8_t* file = NULL;
    if (!img || !player || (format != YM_EXPORT_VGM && !encoder)) {
        *problem = "out of memory";
        goto done;
    }
    memcpy(img->bytes, image, YM_IMAGE_SIZE);
    if (ym_load_image(img)) {
        *problem = img->info.problem;
        goto done;
    }
    char today[16];
    date = vgm_date(date, today);
    YmVgmInfo info;
    ym_render(img, format, max_ticks, player, encoder, NULL, 0, &info); /* measure: the player is deterministic */
    const size_t body = info.size;
    const size_t tag = format == YM_EXPORT_XGC ? 0 : vgm_gd3(title, GD3_SYSTEM, author, date, GD3_CONVERTER, NULL);
    file = (uint8_t*)malloc(body + tag);
    if (!file) {
        *problem = "out of memory";
        goto done;
    }
    if (!ym_render(img, format, max_ticks, player, encoder, file, body, &info)) {
        free(file);
        file = NULL;
        *problem = "frame too large";
        goto done;
    }
    if (format == YM_EXPORT_VGM) {
        vgm_gd3(title, GD3_SYSTEM, author, date, GD3_CONVERTER, file + body);
        vgm_put32(file + 0x04, (uint32_t)(body + tag - 4));
        vgm_put32(file + 0x14, (uint32_t)(body - 0x14));
    } else if (format == YM_EXPORT_XGM) {
        vgm_gd3(title, GD3_SYSTEM, author, date, GD3_CONVERTER, file + body);
        file[0x103] |= 0x02; /* GD3 tag after the music data */
    }
    *size = body + tag;
done:
    free(img);
    free(player);
    free(encoder);
    return file;
}

void ym_export_free(uint8_t* file) { free(file); }
