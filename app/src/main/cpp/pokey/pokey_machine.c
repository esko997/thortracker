/* POKEY music machine (pokey_machine.h): player + chip + Altirra's mix and output stage + blip_buf. */
#include "pokey_machine.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#include "blip_buf.h"

struct PokeyMachine {
    uint32_t rate;
    PokeyChip chip;
    int mix[PK_MIX_ENTRIES]; /* Altirra's nonlinear mix x PKM_MIX_SCALE */
    blip_t* blip;
    int last;                /* the mixed level last added to blip_buf */
    uint16_t levels;         /* the chip's channel levels last reported */
    uint64_t frame_start;    /* chip cycle of blip_buf's frame time 0 */
    uint32_t cycle_acc;
    uint32_t tick_cycles;
    int clock_machine;       /* the machine blip_buf's rates are set for, -1 none */
    double coeff, hp_y, hp_x; /* AC coupling */
    PkPlayer player;
    PokeyImage image;
    int loaded;
    uint32_t ticks;
    int muted[PK_CHANNELS];
    int audition, audition_lane, released, release_ticks;
    PkmTraceFn trace;
    void* trace_ctx;
    short pcm[PKM_MAX_TICK_FRAMES + 64];
    float scratch[2 * PKM_MAX_TICK_FRAMES + 128];
};

/* The levels of the channels held by muted lanes' voices are left out. */
static uint16_t audible(const PokeyMachine* m, uint16_t levels) {
    for (int c = 0; c < 4; ++c) {
        const int l = m->player.holder[c];
        if (l >= 0 && m->muted[l]) levels &= (uint16_t)~(15u << (4 * c));
    }
    return levels;
}

/* The mix at chip cycle `cycle` (within the current frame) into blip_buf. */
static void remix(PokeyMachine* m, uint64_t cycle) {
    const int v = m->mix[pk_mix_index(audible(m, m->levels))];
    if (v == m->last) return;
    blip_add_delta(m->blip, (unsigned)(cycle - m->frame_start), v - m->last);
    m->last = v;
}

static void on_output(void* ctx, uint64_t cycle, uint16_t levels) {
    PokeyMachine* m = (PokeyMachine*)ctx;
    m->levels = levels;
    remix(m, cycle);
}

static void send(PokeyMachine* m) {
    const PkPlayer* p = &m->player;
    for (int i = 0; i < p->writes; ++i) pk_write(&m->chip, p->write[i].reg, p->write[i].val);
}

static void set_clock(PokeyMachine* m, int machine) {
    if (m->clock_machine == machine) return;
    blip_set_rates(m->blip, pkp_clock(machine), m->rate);
    m->clock_machine = machine;
}

/* A fresh machine: chip power-on, empty resampler and coupling filter, no mutes, cycle 0. */
static void machine_reset(PokeyMachine* m) {
    pk_reset(&m->chip);
    blip_clear(m->blip);
    m->clock_machine = -1;
    set_clock(m, m->image.info.machine);
    m->last = 0;
    m->levels = 0;
    m->frame_start = 0;
    m->cycle_acc = 0;
    m->tick_cycles = 0;
    m->hp_y = m->hp_x = 0;
    m->ticks = 0;
    m->audition = m->released = m->release_ticks = 0;
    memset(m->muted, 0, sizeof m->muted);
}

PokeyMachine* pkm_create(uint32_t sample_rate) {
    if (sample_rate < 8000 || sample_rate > PKM_MAX_SAMPLE_RATE) return NULL;
    PokeyMachine* m = (PokeyMachine*)calloc(1, sizeof *m);
    if (!m) return NULL;
    m->rate = sample_rate;
    m->blip = blip_new((int)PKM_MAX_TICK_FRAMES + 64);
    if (!m->blip) {
        pkm_destroy(m);
        return NULL;
    }
    float table[PK_MIX_ENTRIES];
    pk_mix_init(table);
    for (int i = 0; i < PK_MIX_ENTRIES; ++i) m->mix[i] = (int)lrintf(table[i] * PKM_MIX_SCALE);
    m->coeff = exp(-1.0 / ((double)sample_rate * PK_COUPLING_TAU));
    pk_init(&m->chip, on_output, m);
    m->clock_machine = -1;
    machine_reset(m);
    return m;
}

void pkm_destroy(PokeyMachine* m) {
    if (!m) return;
    blip_delete(m->blip);
    free(m);
}

static void trace_batch(PokeyMachine* m, int irq, int timers, int order, int row, int tick) {
    if (!m->trace) return;
    const PkmEvent e = {irq, m->chip.now, timers, order, row, tick, m->player.write, m->player.writes};
    m->trace(m->trace_ctx, &e);
}

/* One player tick and its chip cycles into blip_buf (frames then wait there). */
static void run_tick(PokeyMachine* m) {
    const PkInfo* info = &m->image.info;
    set_clock(m, info->machine);
    m->cycle_acc += info->machine == PK_MACHINE_PAL ? PK_FRAME_CYCLES_PAL : PK_FRAME_CYCLES_NTSC;
    const uint32_t cycles = m->cycle_acc / (uint32_t)info->multispeed;
    m->cycle_acc -= cycles * (uint32_t)info->multispeed;
    m->tick_cycles = cycles;
    const int order = m->player.order, row = m->player.row, tick = m->player.tick;
    pkp_tick(&m->player, &m->image);
    send(m);
    trace_batch(m, 0, 0, order, row, tick);
    remix(m, m->chip.now); /* the voices' channels may have changed hands (mutes follow them) */
    const uint64_t end = m->frame_start + cycles;
    while (pk_run(&m->chip, end)) {
        const uint8_t timers = pk_take_irq(&m->chip);
        pkp_irq(&m->player, &m->image, timers);
        send(m);
        trace_batch(m, 1, timers, order, row, tick);
    }
    blip_end_frame(m->blip, cycles);
    m->frame_start = end;
}

/* The tick's host frames, through the AC coupling and the amplifier clamp, into out (stereo). */
static int read_frames(PokeyMachine* m, float* out) {
    const int n = blip_read_samples(m->blip, m->pcm, (int)PKM_MAX_TICK_FRAMES, 0);
    double y = m->hp_y, xp = m->hp_x;
    for (int i = 0; i < n; ++i) {
        const double x = m->pcm[i] * (1.0 / PKM_MIX_SCALE);
        y = m->coeff * y + (x - xp);
        xp = x;
        float v = (float)y;
        if (v < PK_CLAMP_LOW) v = PK_CLAMP_LOW;
        if (v > PK_CLAMP_HIGH) v = PK_CLAMP_HIGH;
        out[2 * i] = out[2 * i + 1] = v;
    }
    if (fabs(y) < 1e-20) y = 0; /* no denormals */
    m->hp_y = y;
    m->hp_x = xp;
    return n;
}

static int at(const PkPlayer* p, int order, int row) { return p->order == order && p->row == row && p->tick == 0; }

/* Ticks the player alone needs from the song start (or the order's row 0: from_order) until the next tick starts
 * (order, row): at most `bound`, never leaving the order from_order. -1 if not reached. */
static int count_ticks(PkPlayer* p, const PokeyImage* image, int from_order, int order, int row, int bound) {
    if (from_order) pkp_place(p, image, order);
    else pkp_reset(p, image);
    int n = 0;
    while (!at(p, order, row) && n < bound && (!from_order || p->order == order)) {
        pkp_tick(p, image);
        ++n;
    }
    return at(p, order, row) ? n : -1;
}

int pkm_start(PokeyMachine* m, const PokeyImage* image, int order, int row, int max_ticks, int* seek_mode) {
    if (seek_mode) *seek_mode = PKM_SEEK_FROM_START;
    if (image->info.loaded != PK_LOADED || order < 0 || order >= image->info.orders || row < 0 ||
        row >= image->info.rows[order])
        return PKM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    m->loaded = 1;
    PkPlayer* p = &m->player;
    int from_order = 0;
    int ticks = count_ticks(p, &m->image, 0, order, row, max_ticks < 0 ? 0 : max_ticks);
    if (ticks < 0) {
        /* Replaying the order's earlier rows takes < 256 rows x 255 ticks, so it has its own bound instead of
         * max_ticks. A row behind a Dxx/Bxx of the same order is unreachable. */
        from_order = 1;
        if (seek_mode) *seek_mode = PKM_SEEK_FROM_ORDER;
        ticks = count_ticks(p, &m->image, 1, order, row, 256 * 256);
        if (ticks < 0) {
            m->loaded = 0;
            return PKM_ERR_IMAGE;
        }
    }
    /* A fresh machine, then every tick as pkm_tick runs it, the frames discarded. */
    const PkmTraceFn trace = m->trace;
    m->trace = NULL;
    machine_reset(m);
    if (from_order) pkp_place(p, &m->image, order);
    else pkp_reset(p, &m->image);
    send(m);
    for (int t = 0; t < ticks; ++t) {
        run_tick(m);
        read_frames(m, m->scratch);
    }
    m->trace = trace;
    p->writes = 0;
    m->ticks = 0;
    return PKM_OK;
}

void pkm_apply_image(PokeyMachine* m, const PokeyImage* image) {
    if (!m->loaded || m->audition || image->info.loaded != PK_LOADED) return;
    pkp_relocate(&m->player, &m->image, image);
    memcpy(&m->image, image, sizeof m->image);
}

int pkm_audition(PokeyMachine* m, const PokeyImage* image, int lane, int note, int slot, int fine) {
    if (image->info.loaded != PK_LOADED) return PKM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    machine_reset(m);
    if (pkp_audition(&m->player, &m->image, lane, note, slot, fine)) {
        m->loaded = 0;
        return PKM_ERR_IMAGE;
    }
    m->loaded = 1;
    m->audition = 1;
    m->audition_lane = lane;
    send(m);
    return PKM_OK;
}

int pkm_release(PokeyMachine* m) {
    if (!m->loaded || !m->audition) return 0;
    if (!m->ticks || pkp_voice_done(&m->player, &m->image, m->audition_lane)) {
        m->loaded = 0;
        return 0;
    }
    if (!m->released) {
        pkp_key_off(&m->player, &m->image, m->audition_lane);
        m->released = 1;
        m->release_ticks = 0;
    }
    return 1;
}

int pkm_mute(PokeyMachine* m, int lane, int muted) {
    if (lane < 0 || lane >= PK_CHANNELS) return PKM_ERR_IMAGE;
    m->muted[lane] = muted != 0;
    remix(m, m->chip.now);
    return PKM_OK;
}

void pkm_position(const PokeyMachine* m, int* order, int* row, int* tick) {
    *order = m->player.order;
    *row = m->player.row;
    *tick = m->player.tick;
}

int pkm_tick(PokeyMachine* m, float* out) {
    if (!m->loaded) return 0;
    if (m->released) {
        const int per_second = m->image.info.machine == PK_MACHINE_PAL ? 50 : 60;
        const int limit = PKM_RELEASE_SECONDS * per_second * m->image.info.multispeed;
        if (pkp_voice_done(&m->player, &m->image, m->audition_lane) || m->release_ticks >= limit) {
            m->loaded = 0;
            return 0;
        }
        ++m->release_ticks;
    }
    run_tick(m);
    ++m->ticks;
    return read_frames(m, out);
}

void pkm_set_trace(PokeyMachine* m, PkmTraceFn fn, void* ctx) {
    m->trace = fn;
    m->trace_ctx = ctx;
}

const PkPlayer* pkm_player(const PokeyMachine* m) { return &m->player; }
const PokeyChip* pkm_chip(const PokeyMachine* m) { return &m->chip; }
uint32_t pkm_ticks(const PokeyMachine* m) { return m->ticks; }
uint32_t pkm_tick_cycles(const PokeyMachine* m) { return m->tick_cycles; }
