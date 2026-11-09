/* Renders a lowered Master System image (the 33792 bytes the "sms-banjo" lowering writes) through the native engine
 * as the audio hosts run it: the ops table, load_image (its problem is printed when it fails), start at order 0 row 0,
 * one tick at a time. Writes a 48 kHz stereo 16-bit WAV and prints peak/rms. `mute=0123` mutes channels by index
 * (hex digits 0-e: 0-3 the SN76489, 4- the YM2413's channels, then the rhythm drums); `--vgm out.vgm` also writes the
 * VGM export through the hosts' entry point (sms_export_vgm, GD3 tag from `title=`/`author=`/`date=`).
 * Built and run by tools/audio/render_sms_image.sh. */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "chip_engine.h"
#include "sms_export.h"
#include "sms_image.h"

#define RATE 48000

extern const ChipEngineOps sms_chip_ops;

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s image.smsimg out.wav [seconds] [mute=0123] [--vgm out.vgm] [title=..] [author=..] [date=..]\n", argv[0]);
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
    static uint8_t bytes[SMS_IMAGE_SIZE + 1];
    const size_t size = fread(bytes, 1, sizeof bytes, f);
    fclose(f);
    const ChipEngineOps* ops = &sms_chip_ops;
    if (size != ops->image_size) {
        fprintf(stderr, "%s is %zu bytes; the engine's images are %zu\n", argv[1], size, ops->image_size);
        return 1;
    }
    SmsImage* image = malloc(sizeof *image);
    memcpy(image->bytes, bytes, size);
    const int problem = sms_load_image(image);
    if (problem) {
        fprintf(stderr, "image rejected: %s\n", sms_image_problem(problem));
        return 1;
    }
    if (vgm) {
        size_t n = 0;
        const char* why = NULL;
        uint8_t* file = sms_export_vgm(bytes, size, 60u * 600u, title, author, date, &n, &why);
        if (!file) {
            fprintf(stderr, "VGM export failed: %s\n", why);
            return 1;
        }
        FILE* v = fopen(vgm, "wb");
        if (!v) {
            perror(vgm);
            return 1;
        }
        fwrite(file, 1, n, v);
        fclose(v);
        printf("%s: %zu bytes\n", vgm, n);
        sms_export_free(file);
    }
    ChipMachine* m = ops->create(RATE);
    int mode = 0;
    if (ops->start(m, (const ChipImage*)image, 0, 0, 12000, &mode) != CHIP_OK) {
        fprintf(stderr, "start failed\n");
        return 1;
    }
    for (const char* c = mutes; *c; ++c) {
        const int ch = *c >= '0' && *c <= '9' ? *c - '0' : *c >= 'a' && *c <= 'e' ? *c - 'a' + 10 : -1;
        if (ch >= 0) ops->mute(m, ch, 1);
    }
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
           sqrt(sum / (frames * 2.0)), order, row);
    ops->destroy(m);
    free(image);
    free(pcm);
    return 0;
}
