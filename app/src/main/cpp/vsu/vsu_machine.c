/* Virtual Boy VSU music machine (vsu_machine.h): player + VSU core + RC high-pass + blip_buf. */
#include "vsu_machine.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#include "blip_buf.h"

struct VsuMachine {
    uint32_t rate;
    VsuChip chip;
    blip_t* blip[2];
    int last[2];      /* the step value last added to each blip buffer */
    double alpha;     /* RC / (RC + 120 / 5 MHz) */
    double y[2], x[2]; /* RC high-pass: last output, last input */
    uint64_t clock;   /* VSU clocks since the machine's reset */
    VbPlayer player;
    VsuImage image;
    int loaded;
    uint32_t ticks;
    unsigned mute;    /* bit c: lane c left out of the mixer */
    int audition, audition_lane;
    VbmTickFn trace_tick;
    VbmSampleFn trace_sample;
    void* trace_ctx;
    short pcm[2 * VBM_MAX_TICK_FRAMES + 16];
};

static void send(VsuMachine* m) {
    const VbPlayer* p = &m->player;
    for (int i = 0; i < p->writes; ++i) vsu_write(&m->chip, p->write[i].reg, p->write[i].val);
}

/* A fresh machine: chip power-on, empty resampler and filter, no mutes, clock 0. */
static void machine_reset(VsuMachine* m) {
    vsu_reset(&m->chip);
    for (int k = 0; k < 2; ++k) {
        blip_clear(m->blip[k]);
        m->last[k] = 0;
        m->y[k] = m->x[k] = 0;
    }
    m->clock = 0;
    m->ticks = 0;
    m->mute = 0;
    m->audition = 0;
}

VsuMachine* vbm_create(uint32_t sample_rate) {
    if (sample_rate < 8000 || sample_rate > VBM_MAX_SAMPLE_RATE) return NULL;
    VsuMachine* m = (VsuMachine*)calloc(1, sizeof *m);
    if (!m) return NULL;
    m->rate = sample_rate;
    for (int k = 0; k < 2; ++k) {
        m->blip[k] = blip_new((int)VBM_MAX_TICK_FRAMES + 64);
        if (!m->blip[k]) {
            vbm_destroy(m);
            return NULL;
        }
        blip_set_rates(m->blip[k], (double)VSU_CLOCK, sample_rate);
    }
    m->alpha = VBM_RC / (VBM_RC + (double)VSU_SAMPLE_CLOCKS / VSU_CLOCK);
    machine_reset(m);
    return m;
}

void vbm_destroy(VsuMachine* m) {
    if (!m) return;
    for (int k = 0; k < 2; ++k)
        if (m->blip[k]) blip_delete(m->blip[k]);
    free(m);
}

/* One player tick and its 100000 chip clocks; with `output`, every mixer sample through the RC filter into blip_buf
 * (frames then wait there). */
static void run_tick(VsuMachine* m, int output) {
    VbPlayer* p = &m->player;
    const int order = p->order, row = p->row, tick = p->tick;
    vbp_tick(p, &m->image);
    send(m);
    const uint64_t start = m->clock, end = start + VBP_TICK_CLOCKS;
    if (m->trace_tick) m->trace_tick(m->trace_ctx, order, row, tick, start, p->write, p->writes);
    if (!output) {
        vsu_run(&m->chip, VBP_TICK_CLOCKS);
        for (int k = 0; k < 2; ++k) blip_skip_silence(m->blip[k], VBP_TICK_CLOCKS);
        m->clock = end;
        return;
    }
    uint64_t at = start;
    for (uint64_t t = (start / VSU_SAMPLE_CLOCKS + 1) * VSU_SAMPLE_CLOCKS; t <= end; t += VSU_SAMPLE_CLOCKS) {
        vsu_run(&m->chip, (uint32_t)(t - at));
        at = t;
        int s[2];
        vsu_sample(&m->chip, m->mute, &s[0], &s[1]);
        if (m->trace_sample) m->trace_sample(m->trace_ctx, t, s[0], s[1]);
        for (int k = 0; k < 2; ++k) {
            m->y[k] = m->alpha * (m->y[k] + s[k] - m->x[k]);
            m->x[k] = s[k];
            const int v = (int)lround(m->y[k] * VBM_STEP_SCALE);
            if (v != m->last[k]) {
                blip_add_delta(m->blip[k], (unsigned)(t - start), v - m->last[k]);
                m->last[k] = v;
            }
        }
    }
    vsu_run(&m->chip, (uint32_t)(end - at));
    for (int k = 0; k < 2; ++k) {
        blip_end_frame(m->blip[k], VBP_TICK_CLOCKS);
        if (fabs(m->y[k]) < 1e-20) m->y[k] = 0; /* no denormals in long silences */
    }
    m->clock = end;
}

/* The tick's host frames into out (stereo). */
static int read_frames(VsuMachine* m, float* out) {
    int frames = 0;
    for (int k = 0; k < 2; ++k) frames = blip_read_samples(m->blip[k], m->pcm + k, (int)VBM_MAX_TICK_FRAMES, 1);
    if (out)
        for (int i = 0; i < 2 * frames; ++i) out[i] = m->pcm[i] * (1.0f / VBM_FLOAT_SCALE);
    return frames;
}

static int at(const VbPlayer* p, int order, int row) { return p->order == order && p->row == row && p->tick == 0; }

/* Ticks the player alone needs from the song start (or the order's row 0: from_order) until the next tick starts
 * (order, row): at most `bound`, never leaving the order from_order. -1 if not reached. */
static int count_ticks(VbPlayer* p, const VsuImage* image, int from_order, int order, int row, int bound) {
    if (from_order) vbp_place(p, image, order);
    else vbp_reset(p, image);
    int n = 0;
    while (!at(p, order, row) && n < bound && (!from_order || p->order == order)) {
        vbp_tick(p, image);
        ++n;
    }
    return at(p, order, row) ? n : -1;
}

int vbm_start(VsuMachine* m, const VsuImage* image, int order, int row, int max_ticks, int* seek_mode) {
    if (seek_mode) *seek_mode = VBM_SEEK_FROM_START;
    if (image->info.loaded != VB_LOADED || order < 0 || order >= image->info.orders || row < 0 ||
        row >= image->info.rows[order])
        return VBM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    m->loaded = 1;
    VbPlayer* p = &m->player;
    int from_order = 0;
    int ticks = count_ticks(p, &m->image, 0, order, row, max_ticks < 0 ? 0 : max_ticks);
    if (ticks < 0) {
        /* Replaying the order's earlier rows takes < 256 rows x 255 ticks, so it has its own bound instead of
         * max_ticks. A row behind a Dxx/Bxx of the same order is unreachable. */
        from_order = 1;
        if (seek_mode) *seek_mode = VBM_SEEK_FROM_ORDER;
        ticks = count_ticks(p, &m->image, 1, order, row, 256 * 256);
        if (ticks < 0) {
            m->loaded = 0;
            return VBM_ERR_IMAGE;
        }
    }
    /* A fresh machine, then every tick fully clocked; the output path only for the last VBM_OUTPUT_TICKS. */
    const VbmTickFn trace_tick = m->trace_tick;
    const VbmSampleFn trace_sample = m->trace_sample;
    m->trace_tick = NULL;
    m->trace_sample = NULL;
    machine_reset(m);
    if (from_order) vbp_place(p, &m->image, order);
    else vbp_reset(p, &m->image);
    send(m);
    const int quiet = ticks > VBM_OUTPUT_TICKS ? ticks - VBM_OUTPUT_TICKS : 0;
    for (int t = 0; t < ticks; ++t) {
        run_tick(m, t >= quiet);
        if (t >= quiet) read_frames(m, NULL);
    }
    m->trace_tick = trace_tick;
    m->trace_sample = trace_sample;
    m->ticks = 0;
    return VBM_OK;
}

void vbm_apply_image(VsuMachine* m, const VsuImage* image) {
    if (!m->loaded || m->audition || image->info.loaded != VB_LOADED) return;
    vbp_relocate(&m->player, &m->image, image);
    for (int i = 0; i < VSU_WAVES * VSU_WAVE_SAMPLES; ++i) {
        const uint8_t v = image->bytes[VB_OFF_WAVES + i];
        if (v != m->image.bytes[VB_OFF_WAVES + i]) vsu_poke_wave(&m->chip, i / VSU_WAVE_SAMPLES, i % VSU_WAVE_SAMPLES, v);
    }
    for (int i = 0; i < VSU_MOD_VALUES; ++i) {
        const uint8_t v = image->bytes[VB_OFF_MOD + i];
        if (v != m->image.bytes[VB_OFF_MOD + i]) vsu_poke_mod(&m->chip, i, v);
    }
    vbp_adopt_ram(&m->player, image);
    memcpy(&m->image, image, sizeof m->image);
}

int vbm_audition(VsuMachine* m, const VsuImage* image, int lane, int note, int slot, int fine) {
    if (image->info.loaded != VB_LOADED) return VBM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    machine_reset(m);
    if (vbp_audition(&m->player, &m->image, lane, note, slot, fine)) {
        m->loaded = 0;
        return VBM_ERR_IMAGE;
    }
    m->loaded = 1;
    m->audition = 1;
    m->audition_lane = lane;
    send(m);
    return VBM_OK;
}

int vbm_release(VsuMachine* m) {
    if (m->loaded && m->audition) {
        vbp_key_off(&m->player, m->audition_lane);
        send(m);
        m->loaded = 0;
    }
    return 0;
}

int vbm_mute(VsuMachine* m, int lane, int muted) {
    if (lane < 0 || lane >= VB_LANES) return VBM_ERR_IMAGE;
    if (muted) m->mute |= 1u << lane;
    else m->mute &= ~(1u << lane);
    return VBM_OK;
}

void vbm_position(const VsuMachine* m, int* order, int* row, int* tick) {
    *order = m->player.order;
    *row = m->player.row;
    *tick = m->player.tick;
}

int vbm_tick(VsuMachine* m, float* out) {
    if (!m->loaded) return 0;
    run_tick(m, 1);
    ++m->ticks;
    return read_frames(m, out);
}

void vbm_set_trace(VsuMachine* m, VbmTickFn tick, VbmSampleFn sample, void* ctx) {
    m->trace_tick = tick;
    m->trace_sample = sample;
    m->trace_ctx = ctx;
}

const VbPlayer* vbm_player(const VsuMachine* m) { return &m->player; }
const VsuChip* vbm_chip(const VsuMachine* m) { return &m->chip; }
uint32_t vbm_ticks(const VsuMachine* m) { return m->ticks; }
uint64_t vbm_clock(const VsuMachine* m) { return m->clock; }
