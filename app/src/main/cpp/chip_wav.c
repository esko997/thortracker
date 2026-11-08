/* Offline WAV rendering (chip_wav.h). */
#ifndef _POSIX_C_SOURCE
#define _POSIX_C_SOURCE 200809L /* fileno, ftruncate */
#endif
#include "chip_wav.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "chip_engine.h"

#define POSITIONS 256              /* orders and rows both fit in a byte (the hosts pack them so) */
#define TRAIL_FRAMES CHIP_WAV_RATE /* silence kept after the last audible frame when fading */
#define START_SEEK_TICKS 65536     /* (0, 0) is reached before any tick; the budget is never used */
#define SPOOL_BUFFER (1u << 16)

enum { PLAYING, FADING, DONE, FAULTED, WRITE_FAILED };

struct ChipWav {
    const ChipEngineOps* ops;
    ChipMachine* machine;
    ChipImage* image;
    int phase;
    int loops;
    uint64_t fade_frames, faded;
    uint64_t limit; /* song-part frames before CHIP_WAV_CAPPED */
    int capped;
    /* Row entries: the row serial (engines with row_serial) or tick 0 of the position before a tick. */
    int have_serial;
    uint32_t serial;
    int order; /* of the position before the last tick */
    /* Loop detection: the frame each (order, row) was first entered at, plus one (0 = not yet). */
    uint32_t* first_frame;
    int loop_order, loop_row; /* loop_order < 0 until a row is entered again */
    int passes;               /* arrivals at the loop point after the first */
    int64_t target;
    /* The spool: header, then interleaved 16-bit frames. */
    FILE* file;
    char* path;
    int created; /* the spool exists: close removes it unless the file is complete */
    uint64_t frames;
    uint64_t audible; /* frames up to and including the last one louder than 1 LSB */
    uint64_t size;    /* complete file bytes, 0 until DONE */
    float buf[CHIP_MAX_TICK_FRAMES * 2];
    uint8_t pcm[CHIP_MAX_TICK_FRAMES * 4];
};

int16_t chip_wav_pcm16(float x) {
    const float v = x * 32767.0f;
    if (!(v == v)) return 0; /* NaN */
    if (v >= 32767.0f) return 32767;
    if (v <= -32767.0f) return -32767;
    return (int16_t)(v < 0 ? v - 0.5f : v + 0.5f);
}

static void put16(uint8_t* p, uint32_t v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
}

static void put32(uint8_t* p, uint32_t v) {
    put16(p, v & 0xFFFF);
    put16(p + 2, v >> 16);
}

/* Canonical PCM header for `frames` (<= CHIP_WAV_MAX_FRAMES) stereo 16-bit frames. */
static void header(uint8_t* p, uint64_t frames) {
    const uint32_t bytes = (uint32_t)(frames * 4);
    memcpy(p, "RIFF", 4);
    put32(p + 4, 36 + bytes);
    memcpy(p + 8, "WAVEfmt ", 8);
    put32(p + 16, 16);
    put16(p + 20, 1); /* PCM */
    put16(p + 22, 2);
    put32(p + 24, CHIP_WAV_RATE);
    put32(p + 28, CHIP_WAV_RATE * 4);
    put16(p + 32, 4);
    put16(p + 34, 16);
    memcpy(p + 36, "data", 4);
    put32(p + 40, bytes);
}

/* Ends the file: drops trailing silence after a fade, writes the real header and closes the spool. */
static void finish(ChipWav* w) {
    uint64_t keep = w->frames;
    if (w->fade_frames && w->audible + TRAIL_FRAMES < keep) keep = w->audible + TRAIL_FRAMES;
    const uint64_t size = CHIP_WAV_HEADER + keep * 4;
    uint8_t h[CHIP_WAV_HEADER];
    header(h, keep);
    int ok = fflush(w->file) == 0;
    if (ok && keep < w->frames) ok = ftruncate(fileno(w->file), (off_t)size) == 0;
    ok = ok && fseek(w->file, 0, SEEK_SET) == 0 && fwrite(h, 1, sizeof h, w->file) == sizeof h;
    ok = fclose(w->file) == 0 && ok;
    w->file = NULL;
    if (!ok) {
        w->phase = WRITE_FAILED;
        return;
    }
    w->frames = keep;
    w->size = size;
    w->target = (int64_t)keep;
    w->phase = DONE;
}

/* The song part is over (loop passes done, or the limit): fade out, or end here. */
static void end_song(ChipWav* w) {
    if (w->fade_frames) {
        w->phase = FADING;
        w->target = (int64_t)(w->frames + w->fade_frames);
    } else {
        finish(w);
    }
}

/* A row instance was entered; returns 1 when the song part ends here. */
static int arrive(ChipWav* w, int order, int row) {
    if (order < 0 || order >= POSITIONS || row < 0 || row >= POSITIONS) return 0;
    if (w->loop_order < 0) {
        uint32_t* first = &w->first_frame[order * POSITIONS + row];
        if (!*first) {
            *first = (uint32_t)w->frames + 1; /* frames < CHIP_WAV_MAX_FRAMES < 2^32 - 1 */
            return 0;
        }
        w->loop_order = order;
        w->loop_row = row;
        w->passes = 1;
        const uint64_t loop = w->frames - (*first - 1);
        uint64_t song = w->frames + (uint64_t)w->loops * loop;
        if (song > w->limit) song = w->limit;
        w->target = (int64_t)(song + w->fade_frames);
        return w->passes > w->loops;
    }
    if (order != w->loop_order || row != w->loop_row) return 0;
    return ++w->passes > w->loops;
}

/* Appends n frames of buf (fading: at the gain of each frame's fade position). 0, or -1 when the write failed. */
static int append(ChipWav* w, int n, int fading) {
    for (int i = 0; i < n; ++i) {
        float gain = 1.0f;
        if (fading) gain = (float)(1.0 - (double)(w->faded + (uint64_t)i) / (double)w->fade_frames);
        const int16_t l = chip_wav_pcm16(w->buf[2 * i] * gain), r = chip_wav_pcm16(w->buf[2 * i + 1] * gain);
        put16(w->pcm + 4 * i, (uint16_t)l);
        put16(w->pcm + 4 * i + 2, (uint16_t)r);
        if (l > 1 || l < -1 || r > 1 || r < -1) w->audible = w->frames + (uint64_t)i + 1;
    }
    if (fwrite(w->pcm, 4, (size_t)n, w->file) != (size_t)n) return -1;
    w->frames += (uint64_t)n;
    if (fading) w->faded += (uint64_t)n;
    return 0;
}

ChipWav* chip_wav_open(const char* system_id, const uint8_t* image, size_t image_size, int loops, uint32_t fade_ms,
                       uint32_t max_seconds, const char* spool_path, const char** problem) {
    *problem = NULL;
    const ChipEngineOps* ops = chip_engine_ops(system_id);
    if (!ops) {
        *problem = "unknown system";
        return NULL;
    }
    if (image_size != ops->image_size) {
        *problem = "image";
        return NULL;
    }
    /* The fade and at least one tick of song must fit in a file. */
    const uint64_t fade_frames = (uint64_t)fade_ms * CHIP_WAV_RATE / 1000;
    if (fade_frames + 2 * CHIP_MAX_TICK_FRAMES >= CHIP_WAV_MAX_FRAMES || !spool_path) {
        *problem = "arguments";
        return NULL;
    }
    ChipWav* w = (ChipWav*)calloc(1, sizeof *w);
    if (!w) {
        *problem = "out of memory";
        return NULL;
    }
    w->ops = ops;
    w->phase = FAULTED; /* not complete */
    w->image = (ChipImage*)malloc(ops->image_storage);
    w->first_frame = (uint32_t*)calloc(POSITIONS * POSITIONS, sizeof(uint32_t));
    w->path = (char*)malloc(strlen(spool_path) + 1);
    w->machine = w->image && w->first_frame && w->path ? ops->create(CHIP_WAV_RATE) : NULL;
    if (!w->machine) {
        *problem = "out of memory";
        chip_wav_close(w);
        return NULL;
    }
    strcpy(w->path, spool_path);
    int seek = 0;
    if (ops->load_image(w->image, image, image_size) != CHIP_OK ||
        ops->start(w->machine, w->image, 0, 0, START_SEEK_TICKS, &seek) != CHIP_OK) {
        *problem = "image";
        chip_wav_close(w);
        return NULL;
    }
    w->file = fopen(spool_path, "wb");
    w->created = w->file != NULL;
    uint8_t h[CHIP_WAV_HEADER];
    header(h, 0); /* replaced when the file completes */
    if (!w->file || setvbuf(w->file, NULL, _IOFBF, SPOOL_BUFFER) != 0 || fwrite(h, 1, sizeof h, w->file) != sizeof h) {
        *problem = "spool";
        chip_wav_close(w);
        return NULL;
    }
    w->loops = loops < 0 ? 0 : loops;
    w->fade_frames = fade_frames;
    /* The song part stops by the tick that reaches the limit: leave room for that tick and the fade. */
    w->limit = CHIP_WAV_MAX_FRAMES - fade_frames - 2 * CHIP_MAX_TICK_FRAMES;
    if (max_seconds && (uint64_t)max_seconds * CHIP_WAV_RATE < w->limit) w->limit = (uint64_t)max_seconds * CHIP_WAV_RATE;
    w->loop_order = -1;
    w->phase = PLAYING;
    return w;
}

int chip_wav_run(ChipWav* w, int max_ticks) {
    for (int t = 0; t < max_ticks && (w->phase == PLAYING || w->phase == FADING); ++t) {
        if (w->phase == PLAYING) {
            int order, row, tick, entered;
            w->ops->position(w->machine, &order, &row, &tick);
            if (w->ops->row_serial) {
                const uint32_t serial = w->ops->row_serial(w->machine);
                entered = !w->have_serial || serial != w->serial;
                w->serial = serial;
                w->have_serial = 1;
            } else {
                entered = tick == 0; /* chip_engine.h: the next tick starts (order, row) */
            }
            w->order = order;
            int ends = entered && arrive(w, order, row);
            if (!ends && w->frames >= w->limit) {
                w->capped = 1;
                ends = 1;
            }
            if (ends) {
                end_song(w);
                if (w->phase != FADING) break;
            }
        }
        int n = w->ops->tick(w->machine, w->buf);
        if (n < 0) {
            w->phase = FAULTED;
            break;
        }
        if (n == 0) { /* the machine has nothing more to play */
            finish(w);
            break;
        }
        const int fading = w->phase == FADING;
        if (fading && (uint64_t)n > w->fade_frames - w->faded) n = (int)(w->fade_frames - w->faded);
        if (append(w, n, fading) < 0) {
            w->phase = WRITE_FAILED;
            break;
        }
        if (fading && w->faded >= w->fade_frames) finish(w);
    }
    switch (w->phase) {
    case DONE: return 0;
    case FAULTED: return CHIP_WAV_ERR_FAULT;
    case WRITE_FAILED: return CHIP_WAV_ERR_WRITE;
    default: return 1;
    }
}

void chip_wav_status(const ChipWav* w, int64_t* out, int count) {
    const int64_t v[CHIP_WAV_FIELDS] = {(int64_t)w->frames, w->target, w->order, w->loop_order >= 0, (int64_t)w->size,
                                        w->capped};
    for (int i = 0; i < count && i < CHIP_WAV_FIELDS; ++i) out[i] = v[i];
}

void chip_wav_close(ChipWav* w) {
    if (!w) return;
    if (w->file) fclose(w->file);
    if (w->created && w->phase != DONE) remove(w->path);
    if (w->machine) w->ops->destroy(w->machine);
    free(w->image);
    free(w->first_frame);
    free(w->path);
    free(w);
}

int chip_render_wav(const char* system_id, const uint8_t* image, size_t image_size, int loops, uint32_t fade_ms,
                    uint32_t max_seconds, const char* path, int* capped, const char** problem) {
    ChipWav* w = chip_wav_open(system_id, image, image_size, loops, fade_ms, max_seconds, path, problem);
    if (!w) return -1;
    int r;
    while ((r = chip_wav_run(w, 1 << 16)) > 0) {
    }
    if (capped) *capped = w->capped;
    if (r < 0) *problem = r == CHIP_WAV_ERR_FAULT ? "driver fault" : "write failed";
    chip_wav_close(w);
    return r == 0 ? 0 : -1;
}
