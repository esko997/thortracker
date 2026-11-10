/* Renders a lowered SAA1099 image (the 65536 bytes SamCoupeSystem.engine / CmsSystem.engine lower) through the native
 * engine of its variant exactly as the audio hosts run it: chip_engine_ops lookup ("sam-saa1099" or "cms-saa1099" from
 * header byte 5), load_image (the full validation; its problem and byte are printed when it fails), start at order 0
 * row 0, one tick at a time. Writes a 48 kHz stereo 16-bit WAV and prints peak/rms per side. `mute=13` mutes the lanes
 * whose digits it lists (1-9, then a-c for the CMS's lanes 10-12: B4-B6), to hear the others alone; by default nothing
 * is muted. `--vgm out.vgm` also writes the VGM export through the hosts' entry point (sa_export_vgm, GD3 tag from
 * `title=`/`author=`/`date=`). Built and run by tools/audio/render_saa_image.sh. */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "chip_engine.h"
#include "saa_export.h"
#include "saa_image.h"
#include "saa_machine.h"

#define RATE 48000

static uint32_t rd32(const uint8_t* p) { return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24; }

static int write_vgm(const uint8_t* bytes, size_t size, const char* path, const char* title, const char* author,
                     const char* date) {
    size_t n = 0;
    const char* problem = NULL;
    /* up to ten minutes at the highest tick rate (60 Hz x multispeed 8) */
    uint8_t* vgm = sa_export_vgm(bytes, size, 60u * 8u * 600u, title, author, date, &n, &problem);
    if (!vgm) {
        fprintf(stderr, "VGM export failed: %s\n", problem);
        return 1;
    }
    FILE* f = fopen(path, "wb");
    if (!f) {
        perror(path);
        sa_export_free(vgm);
        return 1;
    }
    fwrite(vgm, 1, n, f);
    fclose(f);
    const uint32_t samples = rd32(vgm + 0x18), loop = rd32(vgm + 0x20), clock = rd32(vgm + 0xC8);
    printf("%s: %zu bytes, %.2f s, loop %.2f s, SAA1099 clock %u%s\n", path, n, samples / 44100.0, loop / 44100.0,
           clock & 0x3FFFFFFFu, clock & 0x40000000u ? " x 2 (CMS)" : " (SAM Coupe)");
    sa_export_free(vgm);
    return 0;
}

static int lane_of(char c) {
    if (c >= '1' && c <= '9') return c - '1';
    if (c >= 'a' && c <= 'c') return 9 + (c - 'a');
    if (c >= 'A' && c <= 'C') return 9 + (c - 'A');
    return -1;
}

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s image.saaimg out.wav [seconds] [mute=13] [--vgm out.vgm] [title=..] [author=..] [date=..]\n",
                argv[0]);
        return 2;
    }
    double seconds = 30.0;
    const char *mutes = "", *vgm = NULL, *title = "", *author = "", *date = "";
    for (int a = 3; a < argc; ++a) {
        if (!strncmp(argv[a], "mute=", 5)) mutes = argv[a] + 5;
        else if (!strcmp(argv[a], "--vgm") && a + 1 < argc) vgm = argv[++a];
        else if (!strncmp(argv[a], "title=", 6)) title = argv[a] + 6;
        else if (!strncmp(argv[a], "author=", 7)) author = argv[a] + 7;
        else if (!strncmp(argv[a], "date=", 5)) date = argv[a] + 5;
        else seconds = atof(argv[a]);
    }
    FILE* f = fopen(argv[1], "rb");
    if (!f) {
        perror(argv[1]);
        return 1;
    }
    static uint8_t bytes[SA_IMAGE_SIZE + 1];
    const size_t size = fread(bytes, 1, sizeof bytes, f);
    fclose(f);
    const char* id = size > SA_H_VARIANT && bytes[SA_H_VARIANT] == SA_VARIANT_CMS ? "cms-saa1099" : "sam-saa1099";
    const ChipEngineOps* ops = chip_engine_ops(id);
    if (!ops) {
        fprintf(stderr, "no %s engine in this build\n", id);
        return 1;
    }
    if (size != ops->image_size) {
        fprintf(stderr, "%s is %zu bytes; the engine's images are %zu\n", argv[1], size, ops->image_size);
        return 1;
    }
    ChipImage* image = aligned_alloc(64, (ops->image_storage + 63) / 64 * 64);
    if (ops->load_image(image, bytes, size) != CHIP_OK) {
        const SaaImage* bad = (const SaaImage*)image;
        fprintf(stderr, "image rejected: %s (at byte %d)\n", bad->info.problem ? bad->info.problem : "?", bad->info.problem_offset);
        return 1;
    }
    if (vgm && write_vgm(bytes, size, vgm, title, author, date)) return 1;
    ChipMachine* m = ops->create(RATE);
    int mode = 0;
    if (ops->start(m, image, 0, 0, SAAM_SEEK_TICKS, &mode) != CHIP_OK) {
        fprintf(stderr, "start failed\n");
        return 1;
    }
    for (const char* c = mutes; *c; ++c)
        if (lane_of(*c) >= 0) ops->mute(m, lane_of(*c), 1);
    const long want = (long)(seconds * RATE);
    float* pcm = malloc(sizeof(float) * 2 * (size_t)(want + CHIP_MAX_TICK_FRAMES));
    long frames = 0;
    float tick[CHIP_MAX_TICK_FRAMES * 2];
    while (frames < want) {
        const int n = ops->tick(m, tick);
        if (n <= 0) break;
        memcpy(pcm + frames * 2, tick, sizeof(float) * 2 * (size_t)n);
        frames += n;
    }
    double peak[2] = {0, 0}, sum[2] = {0, 0};
    for (long i = 0; i < frames * 2; ++i) {
        const double v = fabs(pcm[i]);
        if (v > peak[i & 1]) peak[i & 1] = v;
        sum[i & 1] += pcm[i] * (double)pcm[i];
    }
    FILE* w = fopen(argv[2], "wb");
    if (!w) {
        perror(argv[2]);
        return 1;
    }
    const uint32_t data = (uint32_t)(frames * 4);
    const uint32_t hdr[] = {0x46464952u, 36 + data, 0x45564157u, 0x20746d66u, 16, 0x00020001u, RATE, RATE * 4,
                            0x00100004u, 0x61746164u, data};
    fwrite(hdr, sizeof hdr, 1, w);
    for (long i = 0; i < frames * 2; ++i) {
        const float v = pcm[i] < -1 ? -1 : pcm[i] > 1 ? 1 : pcm[i];
        const int16_t s = (int16_t)lrintf(v * 32767);
        fwrite(&s, 2, 1, w);
    }
    fclose(w);
    int order, row, t;
    ops->position(m, &order, &row, &t);
    const double n = frames > 0 ? (double)frames : 1.0;
    printf("%s (%s): %.1f s, peak L %.3f R %.3f, rms L %.4f R %.4f, ended at order %d row %d\n", argv[2], id,
           frames / (double)RATE, peak[0], peak[1], sqrt(sum[0] / n), sqrt(sum[1] / n), order, row);
    ops->destroy(m);
    free(image);
    free(pcm);
    return 0;
}
