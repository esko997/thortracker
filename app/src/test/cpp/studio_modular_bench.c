/* Bounded modular CPU probe; not an audio-device/underrun test. GPL-3.0-or-later. */
#define _POSIX_C_SOURCE 200809L
#include "studio/modular/studio_modular.h"
#include <errno.h>
#include <inttypes.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static int32_t words[4096];
static size_t used;
static void integer(int32_t value) { words[used++] = value; }
static void number(float value) { int32_t bits; memcpy(&bits, &value, sizeof bits); integer(bits); }
static uint64_t now_ns(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) { perror("clock_gettime"); exit(2); }
    return (uint64_t)now.tv_sec * UINT64_C(1000000000) + (uint64_t)now.tv_nsec;
}
static unsigned argument(const char *text, unsigned maximum) {
    char *end = NULL;
    errno = 0;
    unsigned long value = strtoul(text, &end, 10);
    if (errno || !text[0] || *end || value < 1 || value > maximum) {
        fprintf(stderr, "Argument outside 1..%u: %s\n", maximum, text); exit(2);
    }
    return (unsigned)value;
}
static void edge(unsigned id, unsigned from, unsigned output, unsigned to, unsigned input, float scale) {
    integer((int32_t)id); integer((int32_t)from); integer((int32_t)output);
    integer((int32_t)to); integer((int32_t)input); number(scale); number(0.f);
}

int main(int argc, char **argv) {
    if (argc != 4) {
        fprintf(stderr, "usage: %s oscillator-filter-pairs(1..31) voices(1..9) audio-seconds(1..30)\n", argv[0]);
        return 2;
    }
    const unsigned pairs = argument(argv[1], 31), voices = argument(argv[2], 9);
    const unsigned seconds = argument(argv[3], 30), rate = 48000, block = 256;
    const unsigned output_id = 2 + pairs * 2;
    integer(1); integer(0x4D4F4455); integer((int32_t)(2 + pairs * 2));
    integer((int32_t)(pairs * 4)); integer((int32_t)pairs);
    integer(1); integer(SM_NOTE);
    for (unsigned i = 0; i < pairs; ++i) {
        integer((int32_t)(2 + i * 2)); integer(SM_OSC);
        number(2.f); number((float)((int)(i % 3) * 12 - 12)); number(.07f);
        integer((int32_t)(3 + i * 2)); integer(SM_FILTER); number(2000.f); number(.95f);
    }
    integer((int32_t)output_id); integer(SM_OUT); number(.5f / (float)pairs);
    unsigned cable = 1;
    for (unsigned i = 0; i < pairs; ++i) {
        unsigned oscillator = 2 + i * 2, filter = oscillator + 1;
        edge(cable++, 1, 0, oscillator, 0, 1.f);
        edge(cable++, oscillator, 0, filter, 0, 1.f);
        edge(cable++, oscillator, 0, filter, 1, 8.f); /* Audio-rate cutoff modulation. */
        edge(cable++, filter, 0, output_id, 0, 1.f);
    }
    for (unsigned i = 0; i < pairs; ++i) {
        integer((int32_t)(i % SM_MACROS)); integer((int32_t)(3 + i * 2)); integer(1);
        number(0.f); number(.95f);
    }
    const char *error = NULL;
    uint64_t prepare_begin = now_ns();
    SmPlan *plan = sm_compile(words, used, &error);
    if (!plan) { fprintf(stderr, "compile: %s\n", error ? error : "allocation failed"); return 1; }
    StudioDspTables tables;
    studio_dsp_prepare_tables(&tables);
    SmVoice *voice[9] = {0};
    uint8_t macros[SM_MACROS]; memset(macros, 127, sizeof macros);
    for (unsigned i = 0; i < voices; ++i) {
        voice[i] = sm_voice_create(rate, &tables);
        if (!voice[i]) { fprintf(stderr, "voice allocation failed\n"); return 1; }
        sm_bind(voice[i], plan, macros, false);
        sm_note(voice[i], 110.f + (float)i * 17.f, .8f, .5f, false);
    }
    const uint64_t prepare_ns = now_ns() - prepare_begin;
    const uint32_t frames = rate * seconds;
    uint64_t worst = 0, total = 0;
    double power = 0.0;
    float peak = 0.f;
    /* One fixed warmup block, excluded from timing, but still checked in the measured run. */
    for (unsigned j = 0; j < block; ++j) for (unsigned i = 0; i < voices; ++i) {
        float left, right; sm_frame(voice[i], &left, &right);
    }
    for (uint32_t frame = 0; frame < frames;) {
        unsigned count = frames - frame < block ? frames - frame : block;
        uint64_t begin = now_ns();
        for (unsigned j = 0; j < count; ++j) for (unsigned i = 0; i < voices; ++i) {
            float left, right; sm_frame(voice[i], &left, &right);
            if (!isfinite(left) || !isfinite(right) || fabsf(left) > 1.001f || fabsf(right) > 1.001f) {
                fprintf(stderr, "Invalid render output at frame %u voice %u\n", frame + j, i); return 1;
            }
            power += (double)left * left + (double)right * right;
            if (fabsf(left) > peak) peak = fabsf(left);
            if (fabsf(right) > peak) peak = fabsf(right);
        }
        uint64_t elapsed = now_ns() - begin;
        total += elapsed; if (elapsed > worst) worst = elapsed;
        frame += count;
    }
    SmCost cost = sm_cost(plan);
    printf("pairs=%u voices=%u frames=%u rate=%u block=%u nodes=%u edges=%u targets=%u "
           "work_per_voice=%u plan_bytes=%zu voice_bytes=%zu prepare_ns=%" PRIu64
           " render_ns=%" PRIu64 " worst_block_ns=%" PRIu64 " rms=%.9f peak=%.9f\n",
           pairs, voices, frames, rate, block, cost.nodes, cost.edges, cost.targets,
           cost.work_units, cost.plan_bytes, cost.voice_bytes, prepare_ns, total, worst,
           sqrt(power / ((double)frames * voices * 2.0)), (double)peak);
    for (unsigned i = 0; i < voices; ++i) sm_voice_destroy(voice[i]);
    sm_plan_destroy(plan);
    return 0;
}
