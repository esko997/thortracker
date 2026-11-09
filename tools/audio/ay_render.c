/* Renders a lowered AY-3-8910 / YM2149 image (the 65536 bytes AyLowering writes) through the native engine of its
 * variant ("ay-zx128", "ay-msx", "ay-atari-st" or "ay-cpc", from image byte 5) exactly as the audio hosts run it:
 * chip_engine_ops lookup, load_image (the full validation; its problem and byte are printed when it fails), start at
 * order 0 row 0, one tick at a time. Writes a 48 kHz stereo 16-bit WAV (the mono mix on both channels; the CPC's
 * stereo socket, left A + B and right B + C) and prints
 * peak/rms. `mute=13` mutes channels (1-3 = A, B, C) to hear the others alone; `--vgm out.vgm` also writes the VGM
 * export through the hosts' entry point (ay_export_vgm, ten minutes of ticks at most, GD3 tag from
 * `title=`/`author=`/`date=`). Built and run by tools/audio/render_ay_image.sh. */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "ay_export.h"
#include "ay_image.h"
#include "chip_engine.h"

#define RATE 48000

static int write_vgm(int variant, const uint8_t* bytes, size_t size, const char* path, const char* title,
                     const char* author, const char* date) {
    const AyVariantSpec* v = &ay_variants[variant];
    const int max_ticks = (int)(600ull * v->master_hz / v->frame_div);
    size_t n = 0;
    const char* problem = NULL;
    uint8_t* vgm = ay_export_vgm(variant, bytes, size, max_ticks, title, author, date, &n, &problem);
    if (!vgm) {
        fprintf(stderr, "VGM export failed: %s\n", problem);
        return 1;
    }
    FILE* f = fopen(path, "wb");
    if (!f) {
        perror(path);
        ay_export_free(vgm);
        return 1;
    }
    fwrite(vgm, 1, n, f);
    fclose(f);
    const uint32_t samples = vgm[0x18] | vgm[0x19] << 8 | vgm[0x1A] << 16 | (uint32_t)vgm[0x1B] << 24;
    const uint32_t loop = vgm[0x20] | vgm[0x21] << 8 | vgm[0x22] << 16 | (uint32_t)vgm[0x23] << 24;
    printf("%s: %zu bytes, %.2f s, loop %.2f s\n", path, n, samples / 44100.0, loop / 44100.0);
    ay_export_free(vgm);
    return 0;
}

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s image.ayimg out.wav [seconds] [mute=13] [--vgm out.vgm] [title=..] [author=..] [date=..]\n",
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
    static uint8_t bytes[AY_IMAGE_SIZE + 1];
    const size_t size = fread(bytes, 1, sizeof bytes, f);
    fclose(f);
    if (size != AY_IMAGE_SIZE) {
        fprintf(stderr, "%s is %zu bytes; AY images are %u\n", argv[1], size, AY_IMAGE_SIZE);
        return 1;
    }
    const int variant = bytes[AY_H_VARIANT];
    if (variant >= AY_VARIANTS) {
        fprintf(stderr, "image byte %d (variant) is %d: not 0..%d\n", AY_H_VARIANT, variant, AY_VARIANTS - 1);
        return 1;
    }
    const ChipEngineOps* ops = chip_engine_ops(ay_variants[variant].engine_id);
    if (!ops) {
        fprintf(stderr, "no %s engine in this build\n", ay_variants[variant].engine_id);
        return 1;
    }
    ChipImage* image = aligned_alloc(64, (ops->image_storage + 63) / 64 * 64);
    if (ops->load_image(image, bytes, size) != CHIP_OK) {
        const AyImage* bad = (const AyImage*)image;
        fprintf(stderr, "image rejected: %s (at byte %d)\n", bad->info.problem ? bad->info.problem : "?", bad->info.problem_offset);
        return 1;
    }
    printf("%s engine\n", ops->system_id);
    if (vgm && write_vgm(variant, bytes, size, vgm, title, author, date)) return 1;
    ChipMachine* m = ops->create(RATE);
    int mode = 0;
    if (!m || ops->start(m, image, 0, 0, 12000, &mode) != CHIP_OK) {
        fprintf(stderr, "start failed\n");
        return 1;
    }
    for (const char* c = mutes; *c; ++c)
        if (*c >= '1' && *c <= '3') ops->mute(m, *c - '1', 1);
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
    double peak = 0, sum = 0;
    for (long i = 0; i < frames * 2; ++i) {
        const double v = fabs(pcm[i]);
        if (v > peak) peak = v;
        sum += pcm[i] * (double)pcm[i];
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
    printf("%s: %.1f s, peak %.3f, rms %.4f, ended at order %d row %d\n", argv[2], frames / (double)RATE, peak,
           frames ? sqrt(sum / (frames * 2.0)) : 0.0, order, row);
    ops->destroy(m);
    free(image);
    free(pcm);
    return 0;
}
