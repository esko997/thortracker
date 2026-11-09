/* VGM 1.51 export of a Master System image (sms_export.h): the machine runs the Banjo driver without clocking the
 * chips and every write it makes becomes a VGM command at its Z80 cycle. Specified in docs/AUDIO_ENGINE.md
 * ("Master System: VGM export"). */
#include <stdlib.h>
#include <string.h>

#include "sms_export.h"
#include "sms_machine.h"
#include "vgm_gd3.h"

#define VGM_RATE 44100u
#define HEADER_SIZE 0x80u

typedef struct Buf {
    uint8_t* p;
    size_t n, cap;
    int oom;
} Buf;

static void put(Buf* b, const void* data, size_t n) {
    if (b->oom) return;
    if (b->n + n > b->cap) {
        size_t cap = b->cap ? b->cap : 65536;
        while (cap < b->n + n) cap *= 2;
        uint8_t* p = (uint8_t*)realloc(b->p, cap);
        if (!p) {
            b->oom = 1;
            return;
        }
        b->p = p;
        b->cap = cap;
    }
    memcpy(b->p + b->n, data, n);
    b->n += n;
}

static void put8(Buf* b, uint8_t v) { put(b, &v, 1); }

/* Waits of n samples: 0x70-0x7F for 1-16, 0x62 for a 60 Hz frame, else 0x61 nn nn. */
static void put_wait(Buf* b, uint32_t n) {
    while (n) {
        if (n <= 16) {
            put8(b, (uint8_t)(0x70 + n - 1));
            return;
        }
        if (n == 735) {
            put8(b, 0x62);
            return;
        }
        const uint32_t k = n > 0xFFFF ? 0xFFFF : n;
        const uint8_t cmd[3] = {0x61, (uint8_t)k, (uint8_t)(k >> 8)};
        put(b, cmd, 3);
        n -= k;
    }
}

static uint32_t samples_at(uint64_t cycles) { return (uint32_t)(cycles * VGM_RATE / SMSM_CLOCK); }

static void command(Buf* b, const SmsmWrite* w) {
    switch (w->kind) {
    case SMSM_W_PSG: {
        const uint8_t c[2] = {0x50, w->val};
        put(b, c, 2);
        break;
    }
    case SMSM_W_OPLL: {
        const uint8_t c[3] = {0x51, w->reg, w->val};
        put(b, c, 3);
        break;
    }
    default: {
        const uint8_t c[2] = {0x4F, w->val};
        put(b, c, 2);
        break;
    }
    }
}

static const char* system_name(int layout) {
    switch (layout) {
    case SMS_LAYOUT_GG: return "Sega Game Gear";
    case SMS_LAYOUT_FM:
    case SMS_LAYOUT_FM_DRUMS: return "Sega Master System + FM Sound Unit";
    default: return "Sega Master System";
    }
}

uint8_t* sms_export_vgm(const uint8_t* bytes, size_t image_size, uint32_t max_ticks, const char* title, const char* author,
                        const char* date, size_t* size, const char** problem) {
    *size = 0;
    *problem = NULL;
    if (image_size != SMS_IMAGE_SIZE) {
        *problem = "image size";
        return NULL;
    }
    SmsImage* image = (SmsImage*)malloc(sizeof *image);
    SmsMachine* m = smsm_create(VGM_RATE);
    Buf b = {0};
    if (!image || !m) {
        *problem = "out of memory";
        goto fail;
    }
    memcpy(image->bytes, bytes, SMS_IMAGE_SIZE);
    const int code = sms_load_image(image);
    if (code) {
        *problem = sms_image_problem(code);
        goto fail;
    }
    const SmsImageInfo* info = &image->info;
    smsm_set_log(m, 1);
    int seek_mode;
    if (smsm_start(m, image, 0, 0, 0, &seek_mode) != SMSM_OK) {
        *problem = "the driver faulted";
        goto fail;
    }
    uint8_t header[HEADER_SIZE];
    memset(header, 0, sizeof header);
    put(&b, header, sizeof header);
    /* The driver's initialisation (banjo_init, banjo_play_song: chips silenced, the Game Gear's stereo reset). */
    int n;
    const SmsmWrite* log = smsm_log(m, &n);
    for (int i = 0; i < n; ++i) command(&b, &log[i]);
    uint64_t cycles = 0;
    uint32_t written = 0, loop_sample = 0;
    size_t loop_offset = 0;
    int arrived = 0, looped = 0;
    for (uint32_t t = 0;; ++t) {
        int o, r, k;
        smsm_position(m, &o, &r, &k);
        if (o == info->loop && r == 0 && k == 0) {
            if (!arrived) {
                const uint32_t s = samples_at(cycles);
                put_wait(&b, s - written);
                written = s;
                loop_offset = b.n;
                loop_sample = s;
                arrived = 1;
            } else if (t > 0) {
                looped = 1;
                break;
            }
        }
        if (t >= max_ticks) break;
        if (smsm_tick_silent(m) != SMSM_OK) {
            *problem = "the driver faulted";
            goto fail;
        }
        log = smsm_log(m, &n);
        for (int i = 0; i < n; ++i) {
            const uint32_t s = samples_at(cycles + log[i].cycle);
            put_wait(&b, s - written);
            written = s;
            command(&b, &log[i]);
        }
        cycles += SMSM_CYCLES_PER_TICK;
    }
    const uint32_t total = samples_at(cycles);
    put_wait(&b, total - written);
    put8(&b, 0x66);
    const size_t gd3_at = b.n;
    char today[16];
    const char* when = vgm_date(date, today);
    const size_t gd3_size = vgm_gd3(title, system_name(info->layout), author, when, "Thor Tracker", NULL);
    uint8_t* gd3 = (uint8_t*)malloc(gd3_size);
    if (!gd3) {
        *problem = "out of memory";
        goto fail;
    }
    vgm_gd3(title, system_name(info->layout), author, when, "Thor Tracker", gd3);
    put(&b, gd3, gd3_size);
    free(gd3);
    if (b.oom) {
        *problem = "out of memory";
        goto fail;
    }
    uint8_t* h = b.p;
    memcpy(h, "Vgm ", 4);
    vgm_put32(h + 0x04, (uint32_t)(b.n - 0x04));
    vgm_put32(h + 0x08, 0x00000151);
    vgm_put32(h + 0x0C, SMSM_CLOCK);                                         /* SN76489 */
    vgm_put32(h + 0x10, info->layout >= SMS_LAYOUT_FM ? SMSM_CLOCK : 0);      /* YM2413 */
    vgm_put32(h + 0x14, (uint32_t)(gd3_at - 0x14));
    vgm_put32(h + 0x18, total);
    vgm_put32(h + 0x1C, looped ? (uint32_t)(loop_offset - 0x1C) : 0);
    vgm_put32(h + 0x20, looped ? total - loop_sample : 0);
    vgm_put32(h + 0x24, 60);
    h[0x28] = 0x09; /* SN76489 feedback 0x0009: the Sega VDP's PSG */
    h[0x29] = 0x00;
    h[0x2A] = 16;   /* shift register width */
    h[0x2B] = 0x00; /* flags: none set for 1.51 files (Game Gear stereo on) */
    vgm_put32(h + 0x34, HEADER_SIZE - 0x34);
    smsm_destroy(m);
    free(image);
    *size = b.n;
    return b.p;
fail:
    smsm_destroy(m);
    free(image);
    free(b.p);
    return NULL;
}

void sms_export_free(uint8_t* file) { free(file); }
