/* Renders a lowered VIC-20 or C16/Plus/4 image (the 131072 bytes Vic20System / C16System .engine.lower write; header
 * byte 5 picks "vic20-vic" or "c16-ted") through the native engine exactly as the audio hosts run it:
 * chip_engine_ops lookup, load_image (the full validation; its problem and byte are printed when it fails), start at
 * order 0 row 0, one tick at a time. Writes a 48 kHz stereo 16-bit WAV and prints peak/rms. `mute=13` mutes lanes
 * (1-4 VIC: bass, alto, soprano, noise; 1-3 TED: voice 1, voice 2, DAC) to hear the others alone.
 * Built and run by tools/audio/render_cbm_image.sh. */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "cbm_image.h"
#include "chip_engine.h"

#define RATE 48000

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s image.cbimg out.wav [seconds] [mute=13]\n", argv[0]);
        return 2;
    }
    double seconds = 30.0;
    const char* mutes = "";
    for (int a = 3; a < argc; ++a) {
        if (!strncmp(argv[a], "mute=", 5)) mutes = argv[a] + 5;
        else seconds = atof(argv[a]);
    }
    FILE* f = fopen(argv[1], "rb");
    if (!f) {
        perror(argv[1]);
        return 1;
    }
    static uint8_t bytes[CB_IMAGE_SIZE + 1];
    const size_t size = fread(bytes, 1, sizeof bytes, f);
    fclose(f);
    if (size != CB_IMAGE_SIZE) {
        fprintf(stderr, "%s is %zu bytes; VIC-20 / C16 images are %d\n", argv[1], size, CB_IMAGE_SIZE);
        return 1;
    }
    const char* system = bytes[CB_H_CHIP] == CB_CHIP_TED ? "c16-ted" : "vic20-vic";
    const ChipEngineOps* ops = chip_engine_ops(system);
    if (!ops) {
        fprintf(stderr, "no %s engine in this build\n", system);
        return 1;
    }
    ChipImage* image = aligned_alloc(64, (ops->image_storage + 63) / 64 * 64);
    if (ops->load_image(image, bytes, size) != CHIP_OK) {
        const CbmImage* bad = (const CbmImage*)image;
        fprintf(stderr, "image rejected: %s (at byte %d)\n", bad->info.problem ? bad->info.problem : "?", bad->info.problem_offset);
        return 1;
    }
    ChipMachine* m = ops->create(RATE);
    int mode = 0;
    if (!m || ops->start(m, image, 0, 0, 3600, &mode) != CHIP_OK) {
        fprintf(stderr, "start failed\n");
        return 1;
    }
    for (const char* c = mutes; *c; ++c)
        if (*c >= '1' && *c <= '4') ops->mute(m, *c - '1', 1);
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
    printf("%s (%s): %.1f s, peak %.3f, rms %.4f, ended at order %d row %d\n", argv[2], system, frames / (double)RATE, peak,
           frames ? sqrt(sum / (frames * 2.0)) : 0.0, order, row);
    ops->destroy(m);
    free(image);
    free(pcm);
    return 0;
}
