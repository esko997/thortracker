/* PC Engine music machine (pce_machine.h): player + Mednafen's HuC6280 PSG (pce_psg.h) + the CPU timer's DDA
 * interrupts, clocked in CPU cycles. */
#include "pce_machine.h"

#include <stdlib.h>
#include <string.h>

#include "pce_psg.h"

#define R_SELECT 0
#define R_CONTROL 4

struct PceMachine {
    uint32_t rate;
    PcePsg* psg;
    PcPlayer player;
    PceImage image;
    int loaded;
    /* time: tick k starts at cycle base_cycle + floor((k - base_tick) 1312500 / (11 N)) */
    int64_t tick_index;        /* ticks since the reset (seek included) */
    int64_t base_cycle, base_tick;
    int multispeed;            /* N of the cycle formula (the image's, from the tick after a live edit) */
    int64_t next_irq;          /* cycle of the next timer interrupt */
    uint32_t ticks;            /* ticks since start/audition, seek excluded */
    int native_samples, interrupts;
    unsigned mute;             /* bit c: channel c's R4 volume forced to 0 */
    int chip_select;           /* R0 as last sent to the chip */
    int audition, audition_channel, released, release_ticks, ended;
    PcmTraceFn trace;
    void* trace_ctx;
    short pcm[2 * PCM_MAX_TICK_FRAMES + 16];
};

/* Cycle at which tick k starts. */
static int64_t tick_cycle(const PceMachine* m, int64_t k) {
    return m->base_cycle + (k - m->base_tick) * PCM_CYCLES_NUM / (11 * (int64_t)m->multispeed);
}

/* Sends the player's current batch at `offset` cycles into the tick (mute masking applied to R4). */
static void send(PceMachine* m, int32_t offset, int64_t cycle, int kind) {
    const PcPlayer* p = &m->player;
    if (m->trace) m->trace(m->trace_ctx, cycle, kind, p->write, p->writes);
    for (int i = 0; i < p->writes; ++i) {
        int val = p->write[i].val;
        const int reg = p->write[i].reg;
        if (reg == R_SELECT) m->chip_select = val & 7;
        else if (reg == R_CONTROL && m->chip_select < PC_CHANNELS && (m->mute >> m->chip_select & 1)) val &= 0xC0;
        pcs_write(m->psg, offset, (unsigned)reg, (unsigned)val);
    }
}

/* Reset: chip, time and timer (the timer counts from cycle 0 with the player's reload value; call after the player's
 * reset), then the player's reset batch at cycle 0. */
static void machine_reset(PceMachine* m) {
    pcs_reset(m->psg);
    m->tick_index = m->base_tick = m->base_cycle = 0;
    m->multispeed = m->image.info.multispeed;
    m->next_irq = pcp_timer_cycles(&m->player);
    m->ticks = 0;
    m->native_samples = m->interrupts = 0;
    m->chip_select = 0; /* Mednafen's Power() */
    m->mute = 0;
    m->audition = m->released = m->release_ticks = m->ended = 0;
    send(m, 0, 0, PCM_TRACE_TICK);
}

PceMachine* pcm_create(uint32_t sample_rate) {
    if (sample_rate < 8000 || sample_rate > PCM_MAX_SAMPLE_RATE) return NULL;
    PceMachine* m = (PceMachine*)calloc(1, sizeof *m);
    if (!m) return NULL;
    m->rate = sample_rate;
    m->psg = pcs_create(sample_rate, (int)PCM_MAX_TICK_FRAMES + 64);
    if (!m->psg) {
        pcm_destroy(m);
        return NULL;
    }
    m->multispeed = 1;
    return m;
}

void pcm_destroy(PceMachine* m) {
    if (!m) return;
    if (m->psg) pcs_destroy(m->psg);
    free(m);
}

/* One player tick and its timer interrupts, clocked through the chip into the resampler (frames then wait there). */
static void run_tick(PceMachine* m) {
    PcPlayer* p = &m->player;
    const int64_t start = tick_cycle(m, m->tick_index), end = tick_cycle(m, m->tick_index + 1);
    pcp_tick(p, &m->image);
    send(m, 0, start, PCM_TRACE_TICK);
    int n = 0;
    while (m->next_irq < end) {
        const int64_t at = m->next_irq;
        pcp_timer(p, &m->image);
        send(m, (int32_t)(at - start), at, PCM_TRACE_TIMER);
        m->next_irq = at + pcp_timer_cycles(p); /* the reload value as it is now: a new one applies from here */
        ++n;
    }
    m->interrupts = n;
    m->native_samples = pcs_end_tick(m->psg, (int32_t)(end - start));
    ++m->tick_index;
}

static int at(const PcPlayer* p, int order, int row) { return p->order == order && p->row == row && p->tick == 0; }

/* Puts the player at the song start (or at the order's row 0 with its start speed: from_order) and runs it alone
 * until the next tick starts (order, row) - at most `bound` ticks, never leaving the order with from_order. Returns
 * the ticks run, or -1 if (order, row) was not reached. */
static int find_ticks(PcPlayer* p, const PceImage* image, int from_order, int order, int row, int bound) {
    if (from_order) pcp_place(p, image, order);
    else pcp_reset(p, image);
    int n = 0;
    while (!at(p, order, row) && n < bound && (!from_order || p->order == order)) {
        pcp_tick(p, image);
        ++n;
    }
    return at(p, order, row) ? n : -1;
}

int pcm_start(PceMachine* m, const PceImage* image, int order, int row, int max_ticks, int* seek_mode) {
    if (seek_mode) *seek_mode = PCM_SEEK_FROM_START;
    if (image->info.loaded != PC_LOADED || order < 0 || order >= image->info.orders || row < 0 ||
        row >= image->info.rows[order])
        return PCM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    m->loaded = 0;
    PcPlayer* p = &m->player;
    int from_order = 0;
    int ticks = find_ticks(p, &m->image, 0, order, row, max_ticks < 0 ? 0 : max_ticks);
    if (ticks < 0) {
        /* Replaying the order's earlier rows takes < 256 rows x 255 ticks, so it has its own bound instead of
         * max_ticks. A row behind a Dxx/Bxx of the same order is unreachable. */
        from_order = 1;
        if (seek_mode) *seek_mode = PCM_SEEK_FROM_ORDER;
        ticks = find_ticks(p, &m->image, 1, order, row, 256 * 256);
        if (ticks < 0) return PCM_ERR_IMAGE;
    }
    /* The same ticks again, clocked as audible playback (chip, timer, resampler), the output discarded. */
    if (from_order) pcp_place(p, &m->image, order);
    else pcp_reset(p, &m->image);
    machine_reset(m);
    for (int t = 0; t < ticks; ++t) {
        run_tick(m);
        while (pcs_read(m->psg, m->pcm, (int)PCM_MAX_TICK_FRAMES)) {
        }
    }
    m->ticks = 0;
    m->loaded = 1;
    return PCM_OK;
}

void pcm_apply_image(PceMachine* m, const PceImage* image) {
    if (!m->loaded || m->audition || image->info.loaded != PC_LOADED) return;
    pcp_relocate(&m->player, &m->image, image);
    if (image->info.multispeed != m->multispeed) { /* the next tick is the first of the new tick length */
        m->base_cycle = tick_cycle(m, m->tick_index);
        m->base_tick = m->tick_index;
        m->multispeed = image->info.multispeed;
    }
    memcpy(&m->image, image, sizeof m->image);
}

int pcm_audition(PceMachine* m, const PceImage* image, int channel, int note, int slot, int fine) {
    if (image->info.loaded != PC_LOADED) return PCM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    m->loaded = 0;
    if (pcp_audition(&m->player, &m->image, channel, note, slot, fine)) return PCM_ERR_IMAGE;
    machine_reset(m);
    m->loaded = 1;
    m->audition = 1;
    m->audition_channel = channel;
    return PCM_OK;
}

int pcm_release(PceMachine* m) {
    if (!m->loaded || !m->audition) return 0;
    if (!m->ticks) {
        m->loaded = 0;
        return 0;
    }
    if (m->released) return m->ended ? 0 : 1;
    PcPlayer* p = &m->player;
    pcp_off(p, &m->image, m->audition_channel);
    const int64_t cycle = tick_cycle(m, m->tick_index);
    send(m, 0, cycle, PCM_TRACE_OFF);
    m->released = 1;
    m->release_ticks = 0;
    if (!p->ch[m->audition_channel].sounding) {
        m->ended = 1;
        return 0;
    }
    return 1;
}

int pcm_mute(PceMachine* m, int channel, int muted) {
    if (channel < 0 || channel >= PC_CHANNELS) return PCM_ERR_IMAGE;
    const unsigned before = m->mute;
    if (muted) m->mute |= 1u << channel;
    else m->mute &= ~(1u << channel);
    if (m->mute != before && m->loaded) {
        /* The channel's R4 as the player last wrote it (masked or not), at the next tick's start; select restored. */
        const int r4 = m->player.ch[channel].r4, select = m->chip_select;
        pcs_write(m->psg, 0, R_SELECT, (unsigned)channel);
        pcs_write(m->psg, 0, R_CONTROL, (unsigned)(muted ? r4 & 0xC0 : r4));
        pcs_write(m->psg, 0, R_SELECT, (unsigned)select);
    }
    return PCM_OK;
}

void pcm_position(const PceMachine* m, int* order, int* row, int* tick) {
    *order = m->player.order;
    *row = m->player.row;
    *tick = m->player.tick;
}


int pcm_tick(PceMachine* m, float* out) {
    if (!m->loaded) return 0;
    if (m->audition && m->ticks) {
        const int limit = PCM_RELEASE_SECONDS * 60 * m->image.info.multispeed;
        if (m->ended || !m->player.ch[m->audition_channel].sounding || (m->released && m->release_ticks >= limit)) {
            m->ended = 1;
            m->loaded = 0;
            return 0;
        }
        if (m->released) ++m->release_ticks;
    }
    run_tick(m);
    const int frames = pcs_read(m->psg, m->pcm, (int)PCM_MAX_TICK_FRAMES);
    for (int i = 0; i < 2 * frames; ++i) out[i] = m->pcm[i] * (1.0f / 32768.0f);
    ++m->ticks;
    return frames;
}

const PcPlayer* pcm_player(const PceMachine* m) { return &m->player; }
uint32_t pcm_ticks(const PceMachine* m) { return m->ticks; }
int pcm_native_samples(const PceMachine* m) { return m->native_samples; }
int pcm_timer_interrupts(const PceMachine* m) { return m->interrupts; }
void pcm_set_trace(PceMachine* m, PcmTraceFn fn, void* ctx) {
    m->trace = fn;
    m->trace_ctx = ctx;
}
uint32_t pcm_psg_register(const PceMachine* m, unsigned id) { return pcs_register(m->psg, id); }
