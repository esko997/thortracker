/* WonderSwan music machine (ws_machine.h): player + sound unit + the voice timer's interrupts + blip_buf, clocked in
 * 128-cycle blocks at 3072000 Hz. */
#include "ws_machine.h"

#include <stdlib.h>
#include <string.h>

#include "blip_buf.h"

#define RELEASE_TICKS ((WSM_RELEASE_SECONDS * (int64_t)WS_CPU_HZ + WS_TICK_CYCLES - 1) / WS_TICK_CYCLES)

_Static_assert(WSM_BLOCKS_PER_TICK * WS_BLOCK_CYCLES == WS_TICK_CYCLES, "a tick is a whole number of blocks");
_Static_assert((WS_LINE_CYCLES % WS_BLOCK_CYCLES) == 0, "timer interrupts fall on block boundaries");
_Static_assert((int64_t)WSM_MAX_SAMPLE_RATE * WS_TICK_CYCLES / WS_CPU_HZ + 1 == WSM_MAX_TICK_FRAMES,
               "a tick's frames at the highest rate");

struct WsMachine {
    uint32_t rate;
    int model;
    blip_t* blip[2];
    int last[2];               /* the level last fed to each side */
    WsApu apu;
    WsPlayer player;
    WsImage image;
    int loaded;
    int64_t tick_index;        /* ticks since the reset (seek included): tick k starts at cycle 40704 k */
    int64_t next_irq;          /* cycle of the next timer interrupt */
    uint32_t ticks;            /* ticks since start/audition, seek excluded */
    int interrupts;
    unsigned mute;
    int audition, audition_lane, released, release_ticks, ended;
    WsmTraceFn trace;
    void* trace_ctx;
    WsmDmaTraceFn dma_trace;
    void* dma_ctx;
    short pcm[2 * WSM_MAX_TICK_FRAMES + 16];
};

static void dma_forward(void* ctx, uint64_t cycle, int target, uint8_t data) {
    WsMachine* m = (WsMachine*)ctx;
    if (m->dma_trace) m->dma_trace(m->dma_ctx, (int64_t)cycle, target, data);
}

/* Sends the player's current batch at `cycle` to the sound unit. */
static void send(WsMachine* m, int64_t cycle, int kind) {
    const WsPlayer* p = &m->player;
    if (m->trace) m->trace(m->trace_ctx, cycle, kind, p->write, p->writes);
    for (int i = 0; i < p->writes; ++i) ws_apu_write(&m->apu, &p->write[i]);
}

/* Reset: sound unit, time, timer and resampler (call after the player's reset: the timer counts from cycle 0 with the
 * player's reload), then the player's reset batch at cycle 0. */
static void machine_reset(WsMachine* m) {
    ws_apu_reset(&m->apu, m->model);
    ws_apu_map_rom(&m->apu, WS_DMA_BASE, m->image.bytes + WS_OFF_DATA, WS_DATA_CAPACITY);
    ws_apu_set_output(&m->apu, m->image.info.output == WS_OUTPUT_SPEAKER);
    m->mute = 0;
    ws_apu_set_mute(&m->apu, 0);
    for (int k = 0; k < 2; ++k) {
        blip_clear(m->blip[k]);
        m->last[k] = 0;
    }
    m->tick_index = 0;
    m->next_irq = wsp_timer_cycles(&m->player);
    m->ticks = 0;
    m->interrupts = 0;
    m->audition = m->released = m->release_ticks = m->ended = 0;
    send(m, 0, WSM_TRACE_RESET);
}

WsMachine* wsm_create(uint32_t sample_rate, int model) {
    if (sample_rate < 8000 || sample_rate > WSM_MAX_SAMPLE_RATE) return NULL;
    if (model != WS_MODEL_MONO && model != WS_MODEL_COLOR) return NULL;
    WsMachine* m = (WsMachine*)calloc(1, sizeof *m);
    if (!m) return NULL;
    m->rate = sample_rate;
    m->model = model;
    for (int k = 0; k < 2; ++k) {
        m->blip[k] = blip_new((int)WSM_MAX_TICK_FRAMES * 2 + 64);
        if (!m->blip[k]) {
            wsm_destroy(m);
            return NULL;
        }
        blip_set_rates(m->blip[k], (double)WS_CPU_HZ, (double)sample_rate);
    }
    ws_apu_reset(&m->apu, model);
    return m;
}

void wsm_destroy(WsMachine* m) {
    if (!m) return;
    for (int k = 0; k < 2; ++k)
        if (m->blip[k]) blip_delete(m->blip[k]);
    free(m);
}

/* One player tick, its timer interrupts and its 318 blocks, clocked into the resampler (frames then wait there). */
static void run_tick(WsMachine* m) {
    WsPlayer* p = &m->player;
    const int64_t start = m->tick_index * WS_TICK_CYCLES;
    wsp_tick(p, &m->image);
    send(m, start, WSM_TRACE_TICK);
    int n = 0;
    for (int b = 0; b < WSM_BLOCKS_PER_TICK; ++b) {
        const int64_t c = start + (int64_t)WS_BLOCK_CYCLES * b;
        if (m->next_irq <= c) {
            wsp_timer(p, &m->image);
            send(m, c, WSM_TRACE_TIMER);
            m->next_irq = c + wsp_timer_cycles(p); /* the reload as it is now: a new one applies from here */
            ++n;
        }
        int16_t frame[2];
        ws_apu_block(&m->apu, frame);
        for (int k = 0; k < 2; ++k) {
            /* Keep the chip's signed-16 wrap, then reserve headroom for arbitrary DC reversals:
             * blip step TV < 1.3, high-pass L1 <= 2; 8192 * 1.3 * 2 + rounding < 32767.
             * Scaling after blip_read_samples would be too late (its short output clamps).
             * See AUDIO_ENGINE.md, WonderSwan machine output, for the all-rate bound. */
            const int v = frame[k] >> 2;
            if (v != m->last[k]) {
                blip_add_delta(m->blip[k], (unsigned)(WS_BLOCK_CYCLES * b + WS_BLOCK_CYCLES - 1), v - m->last[k]);
                m->last[k] = v;
            }
        }
    }
    for (int k = 0; k < 2; ++k) blip_end_frame(m->blip[k], WS_TICK_CYCLES);
    m->interrupts = n;
    ++m->tick_index;
}

static int read_frames(WsMachine* m) {
    int frames = 0;
    for (int k = 0; k < 2; ++k) frames = blip_read_samples(m->blip[k], m->pcm + k, (int)WSM_MAX_TICK_FRAMES, 1);
    return frames;
}

static int at(const WsPlayer* p, int order, int row) { return p->order == order && p->row == row && p->tick == 0; }

/* Puts the player at the song start (or at the order's row 0 with its start speed: from_order) and runs it alone
 * until the next tick starts (order, row) - at most `bound` ticks, never leaving the order with from_order. Returns
 * the ticks run, or -1 if (order, row) was not reached. */
static int find_ticks(WsPlayer* p, const WsImage* image, int from_order, int order, int row, int bound) {
    if (from_order) wsp_place(p, image, order);
    else wsp_reset(p, image);
    int n = 0;
    while (!at(p, order, row) && n < bound && (!from_order || p->order == order)) {
        wsp_tick(p, image);
        ++n;
    }
    return at(p, order, row) ? n : -1;
}

int wsm_start(WsMachine* m, const WsImage* image, int order, int row, int max_ticks, int* seek_mode) {
    if (seek_mode) *seek_mode = WSM_SEEK_FROM_START;
    if (image->info.loaded != WS_LOADED || image->info.model != m->model || order < 0 || order >= image->info.orders ||
        row < 0 || row >= image->info.rows[order])
        return WSM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    m->loaded = 0;
    WsPlayer* p = &m->player;
    int from_order = 0;
    int ticks = find_ticks(p, &m->image, 0, order, row, max_ticks < 0 ? 0 : max_ticks);
    if (ticks < 0) {
        /* Replaying the order's earlier rows takes < 256 rows x 255 ticks: its own bound instead of max_ticks. A row
         * behind a Dxx/Bxx of the same order is unreachable. */
        from_order = 1;
        if (seek_mode) *seek_mode = WSM_SEEK_FROM_ORDER;
        ticks = find_ticks(p, &m->image, 1, order, row, 256 * 256);
        if (ticks < 0) return WSM_ERR_IMAGE;
    }
    /* The same ticks again, clocked as audible playback (timer, sound unit, DMA, resampler), the output discarded. */
    if (from_order) wsp_place(p, &m->image, order);
    else wsp_reset(p, &m->image);
    machine_reset(m);
    for (int t = 0; t < ticks; ++t) {
        run_tick(m);
        while (read_frames(m)) {
        }
    }
    m->ticks = 0;
    m->loaded = 1;
    return WSM_OK;
}

void wsm_apply_image(WsMachine* m, const WsImage* image) {
    if (!m->loaded || m->audition || image->info.loaded != WS_LOADED || image->info.model != m->model) return;
    int32_t delta = 0;
    if (wsp_relocate(&m->player, &m->image, image, &delta)) ws_apu_dma_rebase(&m->apu, delta);
    memcpy(&m->image, image, sizeof m->image);
    ws_apu_set_output(&m->apu, m->image.info.output == WS_OUTPUT_SPEAKER);
}

int wsm_audition(WsMachine* m, const WsImage* image, int lane, int note, int slot, int fine) {
    if (image->info.loaded != WS_LOADED || image->info.model != m->model) return WSM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    m->loaded = 0;
    if (wsp_audition(&m->player, &m->image, lane, note, slot, fine)) return WSM_ERR_IMAGE;
    machine_reset(m);
    m->loaded = 1;
    m->audition = 1;
    m->audition_lane = lane;
    return WSM_OK;
}

int wsm_release(WsMachine* m) {
    if (!m->loaded || !m->audition) return 0;
    if (!m->ticks) {
        m->loaded = 0;
        return 0;
    }
    if (m->released) return m->ended ? 0 : 1;
    WsPlayer* p = &m->player;
    wsp_off(p, &m->image, m->audition_lane);
    send(m, m->tick_index * WS_TICK_CYCLES, WSM_TRACE_OFF);
    m->released = 1;
    m->release_ticks = 0;
    if (!p->lane[m->audition_lane].sounding) {
        m->ended = 1;
        return 0;
    }
    return 1;
}

int wsm_mute(WsMachine* m, int lane, int muted) {
    const int lanes = m->model == WS_MODEL_COLOR ? WS_CHANNELS : WS_CHANNELS_MONO;
    if (lane < 0 || lane >= lanes) return WSM_ERR_IMAGE;
    if (muted) m->mute |= 1u << lane;
    else m->mute &= ~(1u << lane);
    ws_apu_set_mute(&m->apu, m->mute);
    return WSM_OK;
}

void wsm_position(const WsMachine* m, int* order, int* row, int* tick) {
    *order = m->player.order;
    *row = m->player.row;
    *tick = m->player.tick;
}

/* The audition note has ended: its lane stopped (a one-shot CPU-fed sample's end, or OFF without a release point),
 * its one-shot DMA sample ended in the chip, or, released, its written volume is 0 with the volume envelope at its
 * last step. */
static int audition_done(const WsMachine* m) {
    const WsPlayer* p = &m->player;
    const int c = m->audition_lane;
    const WsLane* ln = &p->lane[c];
    if (!ln->sounding) return 1;
    if (p->owner == c && !m->apu.dma.enable) return 1;
    if (!m->released || !ln->released) return 0;
    const int volume = ln->kind != WS_KIND_SAMPLE ? wsp_shadow(p, 0x88 + c)
                       : c == WS_HYPER_VOICE      ? wsp_shadow(p, 0x6A)
                                                  : wsp_shadow(p, 0x94);
    if (volume != 0) return 0;
    const uint8_t* env = m->image.bytes + WS_OFF_INSTRUMENTS + WS_INSTRUMENT_SIZE * (ln->slot - 1) + WS_I_ENVELOPES +
                         WS_ENVELOPE_BYTES * WS_ENV_VOLUME;
    const int length = env[2];
    return length && ln->env[WS_ENV_VOLUME] >= length - 1;
}

int wsm_tick(WsMachine* m, float* out) {
    if (!m->loaded) return 0;
    if (m->audition && m->ticks) {
        if (m->ended || audition_done(m) || (m->released && m->release_ticks >= RELEASE_TICKS)) {
            m->ended = 1;
            m->loaded = 0;
            return 0;
        }
        if (m->released) ++m->release_ticks;
    }
    run_tick(m);
    const int frames = read_frames(m);
    for (int i = 0; i < 2 * frames; ++i) out[i] = m->pcm[i] * (1.0f / 32768.0f);
    ++m->ticks;
    return frames;
}

const WsPlayer* wsm_player(const WsMachine* m) { return &m->player; }
const WsApu* wsm_apu(const WsMachine* m) { return &m->apu; }
uint32_t wsm_ticks(const WsMachine* m) { return m->ticks; }
int wsm_timer_interrupts(const WsMachine* m) { return m->interrupts; }
int wsm_model(const WsMachine* m) { return m->model; }

void wsm_set_trace(WsMachine* m, WsmTraceFn fn, void* ctx) {
    m->trace = fn;
    m->trace_ctx = ctx;
}

void wsm_set_dma_trace(WsMachine* m, WsmDmaTraceFn fn, void* ctx) {
    m->dma_trace = fn;
    m->dma_ctx = ctx;
    ws_apu_set_hook(&m->apu, fn ? dma_forward : NULL, m);
}
