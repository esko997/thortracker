/* Renders a lowered SID image (the bytes SidSystem.engine.lower writes, e.g. app/build/samples/raster-run.sidimg)
 * through the native "c64-sid" engine exactly as the audio hosts run it: chip_engine_ops lookup, load_image
 * (the full image validation; its problem is printed when it fails), start at order 0 row 0, then one tick per
 * engine frame. Writes a 48 kHz stereo 16-bit WAV and prints peak/rms and any driver fault.
 * The image header chooses the chip model; `model=6581|8580` overrides it for comparisons, and `mute=23` (voice
 * numbers 1-3) mutes voices, to hear one in isolation.
 * Built and run by tools/audio/render_sid_image.sh. */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#include "chip_engine.h"
#include <string.h>

#include "sid_image.h"

#define RATE 48000

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s image.sidimg out.wav [seconds] [model=6581|8580] [mute=123]\n", argv[0]);
        return 2;
    }
    double seconds = argc > 3 ? atof(argv[3]) : 30.0;
    FILE* f = fopen(argv[1], "rb");
    if (!f) { perror(argv[1]); return 1; }
    static uint8_t bytes[SID_IMAGE_SIZE + 1];
    size_t size = fread(bytes, 1, sizeof bytes, f);
    fclose(f);
    const char* mutes = "";
    for (int a = 4; a < argc; ++a) {
        if (!strncmp(argv[a], "model=", 6) && size > 13) bytes[13] = atoi(argv[a] + 6) == 6581 ? 0 : 1;
        if (!strncmp(argv[a], "mute=", 5)) mutes = argv[a] + 5;
    }
    const ChipEngineOps* ops = chip_engine_ops("c64-sid");
    if (!ops) { fprintf(stderr, "no c64-sid engine in this build\n"); return 1; }
    if (size != ops->image_size) {
        fprintf(stderr, "%s is %zu bytes; the engine's images are %zu\n", argv[1], size, ops->image_size);
        return 1;
    }
    ChipImage* image = aligned_alloc(64, (ops->image_storage + 63) / 64 * 64);
    if (ops->load_image(image, bytes, size) != CHIP_OK) {
        const SidImage* bad = (const SidImage*)image;
        fprintf(stderr, "image rejected: %s (at byte %u)\n", bad->info.problem ? bad->info.problem : "?", (unsigned)bad->info.problem_at);
        return 1;
    }
    ChipMachine* m = ops->create(RATE);
    int mode = 0;
    if (ops->start(m, image, 0, 0, 12000, &mode) != CHIP_OK) { fprintf(stderr, "start failed\n"); return 1; }
    for (const char* c = mutes; *c; ++c) if (*c >= '1' && *c <= '3') ops->mute(m, *c - '1', 1);
    long want = (long)(seconds * RATE);
    float* pcm = malloc(sizeof(float) * 2 * (want + CHIP_MAX_TICK_FRAMES));
    long frames = 0;
    float tick[CHIP_MAX_TICK_FRAMES * 2];
    while (frames < want) {
        int n = ops->tick(m, tick);
        if (n < 0) { fprintf(stderr, "driver fault after %.2f s\n", frames / (double)RATE); return 1; }
        for (int i = 0; i < n * 2; ++i) pcm[frames * 2 + i] = tick[i];
        frames += n;
    }
    double peak = 0, sum = 0;
    for (long i = 0; i < frames * 2; ++i) {
        double v = fabs(pcm[i]);
        if (v > peak) peak = v;
        sum += pcm[i] * (double)pcm[i];
    }
    FILE* w = fopen(argv[2], "wb");
    if (!w) { perror(argv[2]); return 1; }
    uint32_t data = (uint32_t)(frames * 4);
    uint32_t hdr[] = {0x46464952u, 36 + data, 0x45564157u, 0x20746d66u, 16, 0x00020001u, RATE, RATE * 4, 0x00100004u,
                      0x61746164u, data};
    fwrite(hdr, sizeof hdr, 1, w);
    for (long i = 0; i < frames * 2; ++i) {
        float v = pcm[i] < -1 ? -1 : pcm[i] > 1 ? 1 : pcm[i];
        int16_t s = (int16_t)lrintf(v * 32767);
        fwrite(&s, 2, 1, w);
    }
    fclose(w);
    int order, row, t;
    ops->position(m, &order, &row, &t);
    printf("%s: %.1f s, peak %.3f, rms %.4f, ended at order %d row %d\n", argv[2], frames / (double)RATE, peak,
           sqrt(sum / (frames * 2.0)), order, row);
    ops->destroy(m);
    free(image);
    free(pcm);
    return 0;
}
