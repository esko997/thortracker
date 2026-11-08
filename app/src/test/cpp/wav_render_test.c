/* Host regression test for the WAV export (chip_wav.c): renders every system's demo song image (the app's own demos,
 * written by the exportSamples Gradle task) through chip_wav_open/run and chip_render_wav into spool files.
 *
 * Checks per demo: a canonical 48 kHz 16-bit stereo RIFF header matching the file length; a length between 10 s and
 * 10 min; non-silent output; the data identical to straight ticking of a fresh machine from (0, 0) up to the second
 * entry into a row (row entries found here from the engine contract: tick 0 before a tick, or row_serial changing);
 * the same bytes from the one-call entry and from a job stepped a few ticks at a time (deterministic, step size
 * irrelevant); one more loop pass adding about one loop length after an identical prefix; a fade-out adding exactly
 * its length after an identical prefix and ending near silence; the length limit stopping at the tick that reaches it
 * and reporting CHIP_WAV_CAPPED; closing a job before it completes removing its spool, a complete file staying; bad
 * arguments. Plus chip_wav_pcm16's rounding and clamping.
 *
 * The shortest songs (ws-shortest.wsimg and wsc-shortest.wsimg: one order of one row, A-4 held on CH1 of the original
 * WonderSwan and of the WonderSwan Color, looping to order 0) run the same render paths at the other extreme: once
 * through is a single row (under 1 s), and it must still render a valid non-silent file equal to straight ticking,
 * deterministically for any step size, with loops=1 adding about one loop and a fade adding exactly its length; a
 * wrong image size is refused.
 *
 * Build and run: tools/audio/run_native_host_test.sh [evidence-dir] (after `tools/build.sh exportSamples`; the demo
 * images are read from app/build/samples, or the directory THOR_SAMPLES names). Writes <dir>/wav_<demo>.wav (once
 * through and a 5 s fade, as the app's default export); its spool files go to <dir> and are removed. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "chip_engine.h"
#include "chip_wav.h"

static int failures = 0;
static int checks = 0;
#define EXPECT(cond, ...)                                              \
    do {                                                               \
        ++checks;                                                      \
        if (!(cond)) {                                                 \
            ++failures;                                                \
            printf("  FAIL %s:%d: ", __FILE__, __LINE__);              \
            printf(__VA_ARGS__);                                       \
            printf("\n");                                              \
        }                                                              \
    } while (0)

#define RATE 48000
#define FADE_MS 5000
#define MAX_SECONDS 600

typedef struct Demo {
    const char* file;   /* in the samples directory */
    const char* system; /* chip_engine_ops id */
} Demo;

static const Demo kDemos[] = {
    {"thor-groove.gbimg", "gb-huge"},
    {"thor-quest.nesimg", "nes-famistudio"},
    {"saw-sunrise.nesimg", "nes-famistudio-vrc6"},
    {"lantern-point.nesimg", "nes-famistudio-vrc7"},
    {"raster-run.sidimg", "c64-sid"},
    {"glass-harbour.o3img", "opl3-fm"},
    {"neon-causeway.ymimg", "ym2612-fm"},
    {"ws-demo.wsimg", "ws-wonderswan"},
    {"wsc-demo.wsimg", "ws-wonderswan-color"},
};

/* The shortest valid songs (written by exportSamples beside the demos). */
static const Demo kShortest[] = {
    {"ws-shortest.wsimg", "ws-wonderswan"},
    {"wsc-shortest.wsimg", "ws-wonderswan-color"},
};

static char spool[512];

static uint8_t* read_file(const char* path, size_t* size) {
    FILE* f = fopen(path, "rb");
    *size = 0;
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t* bytes = n > 0 ? (uint8_t*)malloc((size_t)n) : NULL;
    if (bytes && fread(bytes, 1, (size_t)n, f) != (size_t)n) {
        free(bytes);
        bytes = NULL;
    }
    fclose(f);
    *size = bytes ? (size_t)n : 0;
    return bytes;
}

static int exists(const char* path) { return access(path, F_OK) == 0; }

/* chip_render_wav into the spool, read back and removed: the file, or NULL. */
static uint8_t* render(const char* system, const uint8_t* image, size_t size, int loops, uint32_t fade_ms,
                       uint32_t max_seconds, size_t* out_size, int* capped, const char** problem) {
    *out_size = 0;
    if (chip_render_wav(system, image, size, loops, fade_ms, max_seconds, spool, capped, problem) != 0) {
        EXPECT(!exists(spool), "%s: a failed render leaves no file (%s)", system, *problem);
        return NULL;
    }
    uint8_t* file = read_file(spool, out_size);
    remove(spool);
    return file;
}

static uint32_t u32(const uint8_t* p) { return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24; }
static uint16_t u16(const uint8_t* p) { return (uint16_t)(p[0] | p[1] << 8); }

/* Frames in a file after checking its header. */
static uint64_t check_header(const char* what, const uint8_t* f, size_t size) {
    const int ok = size >= CHIP_WAV_HEADER && !memcmp(f, "RIFF", 4) && u32(f + 4) == size - 8 &&
                   !memcmp(f + 8, "WAVEfmt ", 8) && u32(f + 16) == 16 && u16(f + 20) == 1 && u16(f + 22) == 2 &&
                   u32(f + 24) == RATE && u32(f + 28) == RATE * 4 && u16(f + 32) == 4 && u16(f + 34) == 16 &&
                   !memcmp(f + 36, "data", 4) && u32(f + 40) == size - CHIP_WAV_HEADER && (size - CHIP_WAV_HEADER) % 4 == 0;
    EXPECT(ok, "%s: canonical PCM header for %zu bytes", what, size);
    return ok ? (size - CHIP_WAV_HEADER) / 4 : 0;
}

static int peak(const uint8_t* data, uint64_t from, uint64_t to) {
    int p = 0;
    for (uint64_t i = from * 2; i < to * 2; ++i) {
        int v = (int16_t)u16(data + 2 * i);
        if (v < 0) v = -v;
        if (v > p) p = v;
    }
    return p;
}

static void save(const char* dir, const char* demo, const uint8_t* file, size_t size) {
    char path[512];
    snprintf(path, sizeof path, "%s/wav_%.*s.wav", dir, (int)(strchr(demo, '.') - demo), demo);
    FILE* f = fopen(path, "wb");
    if (!f) return;
    fwrite(file, 1, size, f);
    fclose(f);
}

/*
 * Straight ticking, independent of chip_wav.c's bookkeeping: a fresh machine from (0, 0), ticking until a row is
 * entered a second time (chip_engine.h: tick 0 before a tick, or a changed row_serial). Returns its 16-bit frames
 * (the caller frees them), their count and the frame the loop row was first entered at.
 */
static int16_t* straight(const ChipEngineOps* ops, const uint8_t* image, size_t size, uint64_t* frames, uint64_t* loop_start) {
    ChipImage* img = (ChipImage*)malloc(ops->image_storage);
    ChipMachine* m = ops->create(RATE);
    static uint32_t first[256 * 256];
    memset(first, 0, sizeof first);
    size_t cap = (size_t)RATE * 2 * MAX_SECONDS;
    int16_t* out = (int16_t*)malloc(cap * sizeof *out);
    float buf[CHIP_MAX_TICK_FRAMES * 2];
    int seek = 0, have = 0;
    uint32_t serial = 0;
    uint64_t n = 0;
    *loop_start = 0;
    if (ops->load_image(img, image, size) == CHIP_OK && ops->start(m, img, 0, 0, 65536, &seek) == CHIP_OK) {
        for (;;) {
            int o, r, t, entered;
            ops->position(m, &o, &r, &t);
            if (ops->row_serial) {
                const uint32_t s = ops->row_serial(m);
                entered = !have || s != serial;
                serial = s;
                have = 1;
            } else {
                entered = t == 0;
            }
            if (entered && o >= 0 && o < 256 && r >= 0 && r < 256) {
                if (first[o * 256 + r]) {
                    *loop_start = first[o * 256 + r] - 1;
                    break;
                }
                first[o * 256 + r] = (uint32_t)n + 1;
            }
            const int k = ops->tick(m, buf);
            if (k <= 0 || (n + (uint64_t)k) * 2 > cap) break;
            for (int i = 0; i < k * 2; ++i) out[n * 2 + (uint64_t)i] = chip_wav_pcm16(buf[i]);
            n += (uint64_t)k;
        }
    }
    ops->destroy(m);
    free(img);
    *frames = n;
    return out;
}

/* The file of a job stepped `step` ticks at a time, checking its status along the way. */
static uint8_t* stepped(const char* system, const uint8_t* image, size_t size, int loops, uint32_t fade_ms, int step,
                        size_t* out_size) {
    const char* problem = NULL;
    ChipWav* w = chip_wav_open(system, image, size, loops, fade_ms, MAX_SECONDS, spool, &problem);
    *out_size = 0;
    if (!w) return NULL;
    int64_t st[CHIP_WAV_FIELDS], last = -1;
    int r, monotonic = 1, target_seen = 0;
    do {
        r = chip_wav_run(w, step);
        chip_wav_status(w, st, CHIP_WAV_FIELDS);
        if (st[CHIP_WAV_FRAMES] < last) monotonic = 0;
        last = st[CHIP_WAV_FRAMES];
        if (st[CHIP_WAV_LOOPED] && st[CHIP_WAV_TARGET] > 0) target_seen = 1;
        if (r > 0 && st[CHIP_WAV_SIZE] != 0) monotonic = 0; /* no size before completion */
    } while (r > 0);
    EXPECT(r == 0, "%s stepped by %d: completes (%d)", system, step, r);
    EXPECT(monotonic, "%s stepped by %d: frames only grow, no size before the end", system, step);
    EXPECT(target_seen, "%s stepped by %d: the target length is known once the loop point is", system, step);
    EXPECT(!st[CHIP_WAV_CAPPED], "%s stepped by %d: not capped", system, step);
    chip_wav_close(w);
    EXPECT(exists(spool), "%s: a complete file survives closing its job", system);
    uint8_t* file = read_file(spool, out_size);
    remove(spool);
    EXPECT(file && (int64_t)*out_size == st[CHIP_WAV_SIZE], "%s: the file has the reported size (%zu of %lld)", system,
           *out_size, (long long)st[CHIP_WAV_SIZE]);
    EXPECT(st[CHIP_WAV_TARGET] == (int64_t)(*out_size - CHIP_WAV_HEADER) / 4, "%s: final target = frames", system);
    return file;
}

static void test_demo(const char* dir, const char* samples, const Demo* d) {
    printf("%s (%s)\n", d->file, d->system);
    char path[512];
    snprintf(path, sizeof path, "%s/%s", samples, d->file);
    size_t size = 0;
    uint8_t* image = read_file(path, &size);
    EXPECT(image != NULL, "%s missing: run `tools/build.sh exportSamples` first (or set THOR_SAMPLES)", path);
    if (!image) return;
    const ChipEngineOps* ops = chip_engine_ops(d->system);
    EXPECT(ops && ops->image_size == size, "%s: image of %zu bytes for the engine", d->file, size);
    if (!ops || ops->image_size != size) { free(image); return; }

    /* Once through, no fade: the loop point exactly. */
    size_t s0 = 0;
    int capped = -1;
    const char* problem = NULL;
    const clock_t t0 = clock();
    uint8_t* once = render(d->system, image, size, 0, 0, MAX_SECONDS, &s0, &capped, &problem);
    const double secs = (double)(clock() - t0) / CLOCKS_PER_SEC;
    EXPECT(once != NULL, "%s: renders (%s)", d->file, problem ? problem : "");
    if (!once) { free(image); return; }
    EXPECT(capped == 0, "%s: once through is not capped", d->file);
    const uint64_t f0 = check_header("once", once, s0);
    const uint8_t* data0 = once + CHIP_WAV_HEADER;
    EXPECT(f0 >= 10u * RATE && f0 < (uint64_t)MAX_SECONDS * RATE, "%s: once through lasts %.1f s", d->file, (double)f0 / RATE);
    const int p0 = peak(data0, 0, f0);
    EXPECT(p0 > 1000, "%s: not silent (peak %d)", d->file, p0);
    printf("  once through: %.2f s, peak %d, rendered in %.2f s (%.0fx realtime)\n", (double)f0 / RATE, p0, secs,
           secs > 0 ? (double)f0 / RATE / secs : 0.0);

    /* Equal to straight ticking up to the first row entered again. */
    uint64_t fs = 0, loop_start = 0;
    int16_t* pcm = straight(ops, image, size, &fs, &loop_start);
    EXPECT(fs == f0, "%s: ends where straight ticking first re-enters a row (%llu vs %llu frames)", d->file,
           (unsigned long long)f0, (unsigned long long)fs);
    int same = fs == f0;
    for (uint64_t i = 0; same && i < f0 * 2; ++i) same = (int16_t)u16(data0 + 2 * i) == pcm[i];
    EXPECT(same, "%s: samples identical to straight ticking", d->file);
    free(pcm);
    printf("  loop point at %.2f s, loop %.2f s\n", (double)loop_start / RATE, (double)(f0 - loop_start) / RATE);

    /* Deterministic, whatever the step size. */
    for (int step = 1; step <= 997; step = step * 31 + 3) {
        size_t ss = 0;
        uint8_t* again = stepped(d->system, image, size, 0, 0, step, &ss);
        EXPECT(again && ss == s0 && !memcmp(again, once, s0), "%s: stepped by %d = one call", d->file, step);
        free(again);
    }

    /* One more pass: an identical prefix, then about one loop length. */
    size_t s1 = 0;
    uint8_t* twice = render(d->system, image, size, 1, 0, MAX_SECONDS, &s1, &capped, &problem);
    const uint64_t f1 = twice ? check_header("loops=1", twice, s1) : 0;
    const uint64_t loop = f0 - loop_start;
    EXPECT(twice && f1 > f0 && !memcmp(twice + CHIP_WAV_HEADER, data0, (size_t)f0 * 4), "%s: loops=1 extends once through", d->file);
    EXPECT(f1 + 2 * CHIP_MAX_TICK_FRAMES >= f0 + loop && f1 <= f0 + loop + 2 * CHIP_MAX_TICK_FRAMES,
           "%s: loops=1 adds one loop (%llu frames, loop %llu)", d->file, (unsigned long long)(f1 - f0), (unsigned long long)loop);
    free(twice);

    /* Fade-out: exactly its length after an identical prefix, ending near silence. */
    size_t sf = 0;
    uint8_t* faded = render(d->system, image, size, 0, FADE_MS, MAX_SECONDS, &sf, &capped, &problem);
    const uint64_t ff = faded ? check_header("fade", faded, sf) : 0;
    const uint64_t fade = (uint64_t)FADE_MS * RATE / 1000;
    EXPECT(ff == f0 + fade, "%s: fade adds %llu frames (got %llu)", d->file, (unsigned long long)fade,
           (unsigned long long)(ff - f0));
    if (faded && ff == f0 + fade) {
        const uint8_t* df = faded + CHIP_WAV_HEADER;
        EXPECT(!memcmp(df, data0, (size_t)f0 * 4), "%s: fade starts at the loop point", d->file);
        const int head = peak(df, f0, f0 + RATE / 2), tail = peak(df, ff - RATE / 10, ff);
        EXPECT(tail * 20 < p0 && tail <= head, "%s: fade ends near silence (last 100 ms peak %d, first 500 ms %d)", d->file, tail, head);
        save(dir, d->file, faded, sf);
    }
    free(faded);

    /* The length limit: stops at the tick boundary after 2 s, and says so. */
    size_t sl = 0;
    uint8_t* limited = render(d->system, image, size, 0, 0, 2, &sl, &capped, &problem);
    const uint64_t fl = limited ? check_header("limit", limited, sl) : 0;
    EXPECT(fl >= 2u * RATE && fl < 2u * RATE + CHIP_MAX_TICK_FRAMES, "%s: 2 s limit -> %llu frames", d->file, (unsigned long long)fl);
    EXPECT(capped == 1, "%s: the 2 s file reports the limit", d->file);
    EXPECT(limited && !memcmp(limited + CHIP_WAV_HEADER, data0, (size_t)fl * 4), "%s: limited file is the song's start", d->file);
    free(limited);

    /* Closing a job before it completes leaves no file. */
    ChipWav* w = chip_wav_open(d->system, image, size, 0, FADE_MS, MAX_SECONDS, spool, &problem);
    EXPECT(w && exists(spool), "%s: the spool exists while rendering", d->file);
    if (w) {
        EXPECT(chip_wav_run(w, 200) == 1, "%s: still rendering after 200 ticks", d->file);
        chip_wav_close(w);
        EXPECT(!exists(spool), "%s: a cancelled render removes its spool", d->file);
    }

    /* Bad arguments. */
    EXPECT(!chip_wav_open(d->system, image, size - 1, 0, 0, 1, spool, &problem) && problem && !strcmp(problem, "image"),
           "%s: wrong image size refused", d->file);
    EXPECT(!chip_wav_open(d->system, image, size, 0, 0xFFFFFFFFu, 1, spool, &problem) && problem &&
               !strcmp(problem, "arguments") && !exists(spool),
           "%s: a fade longer than a file can hold refused", d->file);
    free(once);
    free(image);
}

/* The shortest valid song: everything test_demo checks that does not need a song longer than one row. */
static void test_shortest(const char* samples, const Demo* d) {
    printf("%s (%s): the shortest song\n", d->file, d->system);
    char path[512];
    snprintf(path, sizeof path, "%s/%s", samples, d->file);
    size_t size = 0;
    uint8_t* image = read_file(path, &size);
    EXPECT(image != NULL, "%s missing: run `tools/build.sh exportSamples` first (or set THOR_SAMPLES)", path);
    if (!image) return;
    const ChipEngineOps* ops = chip_engine_ops(d->system);
    EXPECT(ops && ops->image_size == size, "%s: image of %zu bytes for the engine", d->file, size);
    if (!ops || ops->image_size != size) {
        free(image);
        return;
    }

    size_t s0 = 0;
    int capped = -1;
    const char* problem = NULL;
    uint8_t* once = render(d->system, image, size, 0, 0, MAX_SECONDS, &s0, &capped, &problem);
    EXPECT(once != NULL, "%s: renders (%s)", d->file, problem ? problem : "");
    if (!once) {
        free(image);
        return;
    }
    EXPECT(capped == 0, "%s: once through is not capped", d->file);
    const uint64_t f0 = check_header("once", once, s0);
    const uint8_t* data0 = once + CHIP_WAV_HEADER;
    EXPECT(f0 > 0 && f0 < (uint64_t)RATE, "%s: once through lasts %llu frames (one row, under 1 s)", d->file,
           (unsigned long long)f0);
    const int p0 = f0 ? peak(data0, 0, f0) : 0;
    EXPECT(p0 > 500, "%s: not silent (peak %d)", d->file, p0);

    uint64_t fs = 0, loop_start = 0;
    int16_t* pcm = straight(ops, image, size, &fs, &loop_start);
    EXPECT(fs == f0 && loop_start == 0,
           "%s: straight ticking re-enters its only row after %llu frames (file %llu, loop at %llu)", d->file,
           (unsigned long long)fs, (unsigned long long)f0, (unsigned long long)loop_start);
    int same = fs == f0;
    for (uint64_t i = 0; same && i < f0 * 2; ++i) same = (int16_t)u16(data0 + 2 * i) == pcm[i];
    EXPECT(same, "%s: samples identical to straight ticking", d->file);
    free(pcm);

    for (int step = 1; step <= 997; step = step * 31 + 3) {
        size_t ss = 0;
        uint8_t* again = stepped(d->system, image, size, 0, 0, step, &ss);
        EXPECT(again && ss == s0 && !memcmp(again, once, s0), "%s: stepped by %d = one call", d->file, step);
        free(again);
    }

    size_t s1 = 0;
    uint8_t* twice = render(d->system, image, size, 1, 0, MAX_SECONDS, &s1, &capped, &problem);
    const uint64_t f1 = twice ? check_header("loops=1", twice, s1) : 0;
    const uint64_t loop = f0 - loop_start;
    EXPECT(twice && f1 > f0 && !memcmp(twice + CHIP_WAV_HEADER, data0, (size_t)f0 * 4), "%s: loops=1 extends once through",
           d->file);
    EXPECT(f1 + 2 * CHIP_MAX_TICK_FRAMES >= f0 + loop && f1 <= f0 + loop + 2 * CHIP_MAX_TICK_FRAMES,
           "%s: loops=1 adds one loop (%llu frames, loop %llu)", d->file, (unsigned long long)(f1 - f0),
           (unsigned long long)loop);
    free(twice);

    size_t sf = 0;
    uint8_t* faded = render(d->system, image, size, 0, FADE_MS, MAX_SECONDS, &sf, &capped, &problem);
    const uint64_t ff = faded ? check_header("fade", faded, sf) : 0;
    const uint64_t fade = (uint64_t)FADE_MS * RATE / 1000;
    EXPECT(ff == f0 + fade, "%s: fade adds %llu frames (got %llu)", d->file, (unsigned long long)fade,
           (unsigned long long)(ff - f0));
    EXPECT(faded && ff == f0 + fade && !memcmp(faded + CHIP_WAV_HEADER, data0, (size_t)f0 * 4),
           "%s: fade starts at the loop point", d->file);
    free(faded);

    EXPECT(!chip_wav_open(d->system, image, size - 1, 0, 0, 1, spool, &problem) && problem && !strcmp(problem, "image"),
           "%s: wrong image size refused", d->file);
    free(once);
    free(image);
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    const char* samples = argc > 2 ? argv[2] : "app/build/samples";
    snprintf(spool, sizeof spool, "%s/wav_render_test.part", dir);
    const char* problem = NULL;
    uint8_t byte = 0;
    EXPECT(!chip_wav_open("no-such-chip", &byte, 1, 0, 0, 1, spool, &problem) && problem && !strcmp(problem, "unknown system"),
           "unknown system refused");
    EXPECT(chip_wav_pcm16(0.0f) == 0 && chip_wav_pcm16(1.0f) == 32767 && chip_wav_pcm16(-1.0f) == -32767 &&
               chip_wav_pcm16(2.0f) == 32767 && chip_wav_pcm16(-7.0f) == -32767 && chip_wav_pcm16(NAN) == 0 &&
               chip_wav_pcm16(0.5f / 32767) == 1 && chip_wav_pcm16(-0.5f / 32767) == -1 && chip_wav_pcm16(0.49f / 32767) == 0,
           "pcm16 rounds to nearest and clamps");
    EXPECT(CHIP_WAV_HEADER + 4ull * CHIP_WAV_MAX_FRAMES <= 0x7FFFFFFFull, "the largest file fits RIFF and a 32-bit off_t");
    for (size_t i = 0; i < sizeof kDemos / sizeof kDemos[0]; ++i) test_demo(dir, samples, &kDemos[i]);
    for (size_t i = 0; i < sizeof kShortest / sizeof kShortest[0]; ++i) test_shortest(samples, &kShortest[i]);
    printf("WAV export: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
