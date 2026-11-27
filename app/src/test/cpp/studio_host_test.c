/* Real SDL-host mixer + Studio DSP, with an in-memory output sink instead of a device.
 * Covers transport/tail lifetime; not a device scheduling or acoustic timing test. */
#include "deck_audio.h"
#include "audio_output.h"
#include <assert.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void (*callback)(int);
static float output[2000];
static int written;
static uint64_t clock_ns;

int ao_open(int rate, void (*feed)(int), int* frames) {
    assert(rate == 48000);
    callback = feed;
    *frames = 1000;
    return 0;
}
void ao_put(const float* pcm, int bytes) {
    assert(bytes >= 0 && bytes % (2 * (int)sizeof(float)) == 0);
    const int samples = bytes / (int)sizeof(float);
    assert(written + samples <= (int)(sizeof output / sizeof output[0]));
    memcpy(output + written, pcm, (size_t)bytes);
    written += samples;
}
void ao_lock(void) {}
void ao_unlock(void) {}
void ao_close(void) { callback = NULL; }
uint64_t ao_ticks_ns(void) { return ++clock_ns; }

static float tick(void) {
    written = 0;
    callback((int)sizeof output);
    assert(written == 2000);
    float peak = 0;
    for (int i = 0; i < written; ++i) {
        assert(isfinite(output[i]));
        if (fabsf(output[i]) > peak) peak = fabsf(output[i]);
    }
    return peak;
}

int main(int argc, char** argv) {
    assert(argc == 2);
    FILE* f = fopen(argv[1], "rb");
    assert(f);
    assert(fseek(f, 0, SEEK_END) == 0);
    long size = ftell(f);
    assert(size > 0 && size < 1024 * 1024);
    rewind(f);
    unsigned char* image = malloc((size_t)size);
    assert(image && fread(image, 1, (size_t)size, f) == (size_t)size);
    fclose(f);

    assert(da_open("studio-mono") == 0);
    assert(da_set_image(image, (int)size) == 0);
    assert(da_play(0, 0, 65536) == 0 && da_playing());
    assert(tick() > .05f);
    float first[2000];
    memcpy(first, output, sizeof first);
    da_stop();
    assert(!da_playing());
    assert(tick() == 0.f); /* The first echo has not reached its 80 ms delay yet. */
    assert(da_set_image(image, (int)size) == 0); /* Editing a stopped rack must keep its tail. */
    assert(tick() == 0.f);
    assert(tick() > .01f);
    assert(output[1800] > 0.f && output[1801] < 0.f); /* Stereo polarity is preserved. */
    da_panic();
    assert(tick() == 0.f && !da_playing());

    assert(da_play(0, 0, 65536) == 0);
    tick();
    assert(memcmp(first, output, sizeof first) == 0); /* Empty effect history on a fresh start. */
    assert(da_audition(0, 48, 1, 0, 480) == 0);
    tick();
    assert(da_play(0, 0, 65536) == 0);
    tick();
    assert(memcmp(first, output, sizeof first) == 0); /* A cursor start also ends parallel audition. */
    da_stop();
    for (int i = 0; i < 390; ++i) {
        da_stop(); /* Repeated Stop must not extend the eight-second cap. */
        tick();
    }
    assert(tick() == 0.f);
    assert(da_audition(0, 48, 1, 0, 480) == 0);
    assert(tick() > .05f);
    da_panic();
    assert(tick() == 0.f);
    long long stats[DA_STATS] = {0};
    da_stats(stats, DA_STATS);
    assert(stats[5] == 0);
    da_close();
    free(image);
    puts("Studio host: Stop keeps pending stereo echoes, live rack updates retain tails, Panic/fresh Play cut old paths, tail cap holds");
    return 0;
}
