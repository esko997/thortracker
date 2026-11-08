/* Renders a lowered NES image (the bytes NesSystem.engine.lower, NesVrc6System.engine.lower or an N163 system's
 * engine.lower write, e.g. app/build/samples/thor-quest.nesimg or saw-sunrise.nesimg) through the native engine of the
 * player build named in its header (bytes 4..7: "nes-famistudio", "nes-famistudio-vrc6" or "nes-famistudio-n163-1"
 * .."-8") exactly as the audio hosts run it:
 * chip_engine_ops lookup, load_image (the full image validation; its problem is printed when it fails), start at
 * order 0 row 0, then one tick per engine frame. Writes a 48 kHz stereo 16-bit WAV and prints peak/rms and any
 * driver fault. Built and run by tools/audio/render_nes_image.sh. */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#include "chip_engine.h"
#include "nes_image.h"

#define RATE 48000

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s image.nesimg out.wav [seconds]\n", argv[0]);
        return 2;
    }
    double seconds = argc > 3 ? atof(argv[3]) : 30.0;
    FILE* f = fopen(argv[1], "rb");
    if (!f) { perror(argv[1]); return 1; }
    static uint8_t bytes[NES_IMAGE_SIZE + 1];
    size_t size = fread(bytes, 1, sizeof bytes, f);
    fclose(f);
    const uint32_t id = size >= 8 ? (uint32_t)bytes[4] | (uint32_t)bytes[5] << 8 | (uint32_t)bytes[6] << 16 | (uint32_t)bytes[7] << 24 : 0;
    static const char* const n163_engines[NM_N163_BUILDS] = {"nes-famistudio-n163-1", "nes-famistudio-n163-2", "nes-famistudio-n163-3",
                                                             "nes-famistudio-n163-4", "nes-famistudio-n163-5", "nes-famistudio-n163-6",
                                                             "nes-famistudio-n163-7", "nes-famistudio-n163-8"};
    const char* engine = id == NES_VRC6_PLAYER_ID ? "nes-famistudio-vrc6" : "nes-famistudio";
    for (int n = 0; n < NM_N163_BUILDS; ++n) {
        if (id == nes_player_n163[n].id) engine = n163_engines[n];
    }
    const ChipEngineOps* ops = chip_engine_ops(engine);
    if (!ops) { fprintf(stderr, "no %s engine in this build\n", engine); return 1; }
    if (size != ops->image_size) {
        fprintf(stderr, "%s is %zu bytes; the engine's images are %zu\n", argv[1], size, ops->image_size);
        return 1;
    }
    ChipImage* image = aligned_alloc(64, (ops->image_storage + 63) / 64 * 64);
    if (ops->load_image(image, bytes, size) != CHIP_OK) {
        const NesImage* bad = (const NesImage*)image;
        fprintf(stderr, "image rejected: %s (at byte %u)\n", bad->info.problem ? bad->info.problem : "?", (unsigned)bad->info.problem_at);
        return 1;
    }
    ChipMachine* m = ops->create(RATE);
    int mode = 0;
    if (ops->start(m, image, 0, 0, 12000, &mode) != CHIP_OK) { fprintf(stderr, "start failed\n"); return 1; }
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
