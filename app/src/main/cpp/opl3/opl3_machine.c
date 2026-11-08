/* OPL3 music machine (opl3_machine.h): player + Nuked-OPL3 + blip_buf. */
#include "opl3_machine.h"

#include <stdlib.h>
#include <string.h>

#include "blip_buf.h"
#include "opl3.h"

struct Opl3Machine {
    uint32_t rate;
    opl3_chip chip;
    blip_t* blip[2];
    int last[2];
    O3Player player;
    Opl3Image image;
    int loaded;
    uint32_t native_acc;
    int native_samples;
    uint32_t ticks;
    int muted[O3_CHANNELS];
    uint8_t chip_c0[O3_VOICES]; /* C0 values on the chip (the player's, output bits cleared when muted) */
    int audition, audition_channel, released, release_ticks;
    short pcm[2 * O3M_MAX_TICK_FRAMES + 16];
};

static int c0_voice(int reg) {
    const int low = reg & 0xFF;
    if (low < 0xC0 || low > 0xC8) return -1;
    return (reg >> 8) * 9 + (low - 0xC0);
}

static int c0_reg(int v) { return (v >= 9 ? 0x100 : 0) + 0xC0 + v % 9; }

/* Output enables of muted channels' voices are cleared on the chip only. */
static void sync_c0(Opl3Machine* m) {
    for (int v = 0; v < O3_VOICES; ++v) {
        const int c = o3p_voice_channel(&m->player, v);
        uint8_t want = m->player.shadow[c0_reg(v)];
        if (c >= 0 && m->muted[c]) want &= 0x0F;
        if (m->chip_c0[v] != want) {
            m->chip_c0[v] = want;
            OPL3_WriteReg(&m->chip, (uint16_t)c0_reg(v), want);
        }
    }
}

/* Writes write[from .. to - 1] (C0 through the mute routing). */
static void send_range(Opl3Machine* m, int from, int to) {
    const O3Player* p = &m->player;
    for (int i = from; i < to; ++i)
        if (c0_voice(p->write[i].reg) < 0) OPL3_WriteReg(&m->chip, p->write[i].reg, p->write[i].val);
    sync_c0(m);
}

static void send_writes(Opl3Machine* m) { send_range(m, 0, m->player.writes); }

static void generate(Opl3Machine* m, int from, int to) {
    for (int i = from; i < to; ++i) {
        int16_t s[2];
        OPL3_Generate(&m->chip, s);
        for (int k = 0; k < 2; ++k) {
            if (s[k] != m->last[k]) {
                blip_add_delta(m->blip[k], (unsigned)i, s[k] - m->last[k]);
                m->last[k] = s[k];
            }
        }
    }
}

static void chip_reset(Opl3Machine* m) {
    OPL3_Reset(&m->chip, (O3M_CLOCK + O3M_NATIVE_DIVIDER / 2) / O3M_NATIVE_DIVIDER);
    memset(m->chip_c0, 0, sizeof m->chip_c0);
    for (int i = 0; i < 2; ++i) {
        blip_clear(m->blip[i]);
        m->last[i] = 0;
    }
    m->native_acc = 0;
    m->ticks = 0;
    m->audition = m->released = m->release_ticks = 0;
    memset(m->muted, 0, sizeof m->muted);
}

Opl3Machine* o3m_create(uint32_t sample_rate) {
    if (sample_rate < 8000 || sample_rate > O3M_MAX_SAMPLE_RATE) return NULL;
    Opl3Machine* m = (Opl3Machine*)calloc(1, sizeof *m);
    if (!m) return NULL;
    m->rate = sample_rate;
    for (int i = 0; i < 2; ++i) {
        m->blip[i] = blip_new(O3M_MAX_TICK_FRAMES + 64);
        if (!m->blip[i]) {
            o3m_destroy(m);
            return NULL;
        }
        blip_set_rates(m->blip[i], (double)O3M_CLOCK / O3M_NATIVE_DIVIDER, sample_rate);
    }
    chip_reset(m);
    return m;
}

void o3m_destroy(Opl3Machine* m) {
    if (!m) return;
    for (int i = 0; i < 2; ++i)
        if (m->blip[i]) blip_delete(m->blip[i]);
    free(m);
}

/* The player's register state onto a freshly reset chip: mode registers, operators, connections,
 * frequencies, then key-ons. */
static void restore(Opl3Machine* m) {
    const uint8_t* s = m->player.shadow;
    static const int globals[] = {0x105, 0x104, 0x08, 0xBD};
    for (int i = 0; i < 4; ++i)
        if (s[globals[i]]) OPL3_WriteReg(&m->chip, (uint16_t)globals[i], s[globals[i]]);
    static const int op_bases[] = {0x20, 0x40, 0x60, 0x80, 0xE0};
    for (int bank = 0; bank < 0x200; bank += 0x100)
        for (int b = 0; b < 5; ++b)
            for (int r = op_bases[b]; r < op_bases[b] + 0x16; ++r)
                if ((r & 7) < 6 && s[bank + r]) OPL3_WriteReg(&m->chip, (uint16_t)(bank + r), s[bank + r]);
    sync_c0(m);
    for (int base = 0xA0; base <= 0xB0; base += 0x10)
        for (int v = 0; v < O3_VOICES; ++v) {
            const int reg = (v >= 9 ? 0x100 : 0) + base + v % 9;
            if (s[reg]) OPL3_WriteReg(&m->chip, (uint16_t)reg, s[reg]);
        }
}

static int at(const O3Player* p, int order, int row) { return p->order == order && p->row == row && p->tick == 0; }

/* Puts the player at the song start (or at the order's row 0 with its start speed: from_order) and runs it alone
 * for `ticks` ticks, or until the next tick starts (order, row) when ticks < 0 (at most `bound` ticks, never
 * leaving the order from_order). Returns the ticks run, or -1 if (order, row) was not reached. */
static int run_player(O3Player* p, const Opl3Image* image, int from_order, int order, int row, int ticks, int bound) {
    if (from_order) o3p_place(p, image, order);
    else o3p_reset(p, image);
    int n = 0;
    for (; ticks < 0 ? !at(p, order, row) && n < bound && (!from_order || p->order == order) : n < ticks; ++n)
        o3p_tick(p, image);
    return ticks >= 0 || at(p, order, row) ? n : -1;
}

static int run_tick(Opl3Machine* m);

int o3m_start(Opl3Machine* m, const Opl3Image* image, int order, int row, int max_ticks, int* seek_mode) {
    if (seek_mode) *seek_mode = O3M_SEEK_FROM_START;
    if (image->info.loaded != O3_LOADED || order < 0 || order >= image->info.orders || row < 0 ||
        row >= image->info.rows[order])
        return O3M_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    m->loaded = 1;
    chip_reset(m);
    O3Player* p = &m->player;
    int from_order = 0;
    int ticks = run_player(p, &m->image, 0, order, row, -1, max_ticks);
    if (ticks < 0) {
        /* Replaying the order's earlier rows takes < 256 rows x 255 ticks (~0.2 us each), so it has its own
         * bound instead of max_ticks. A row behind a Dxx/Bxx of the same order is unreachable. */
        from_order = 1;
        if (seek_mode) *seek_mode = O3M_SEEK_FROM_ORDER;
        ticks = run_player(p, &m->image, 1, order, row, -1, 256 * 256);
        if (ticks < 0) {
            m->loaded = 0;
            return O3M_ERR_IMAGE;
        }
    }
    if (ticks == 0 && !from_order) {
        send_writes(m); /* plain start at 0/0: the init writes as straight play sends them */
        return O3M_OK;
    }
    /* The chip is clocked through the last O3M_PREROLL_TICKS ticks before the target (the player's own writes,
     * output discarded), so held notes and release tails arrive with their envelopes under way. Earlier ticks run
     * the player alone, then the chip receives its register state. */
    const int preroll = O3M_PREROLL_TICKS * m->image.info.multispeed;
    const int silent = ticks > preroll ? ticks - preroll : 0;
    run_player(p, &m->image, from_order, order, row, silent, 0);
    if (silent) restore(m);
    else send_writes(m); /* from the song start (or the placement): the chip's state is straight play's */
    for (int t = silent; t < ticks; ++t) {
        run_tick(m);
        for (int k = 0; k < 2; ++k) blip_read_samples(m->blip[k], m->pcm, (int)O3M_MAX_TICK_FRAMES, 0);
    }
    p->writes = p->offs = 0;
    m->ticks = 0;
    return O3M_OK;
}

void o3m_apply_image(Opl3Machine* m, const Opl3Image* image) {
    if (!m->loaded || m->audition || image->info.loaded != O3_LOADED) return;
    o3p_relocate(&m->player, &m->image, image);
    memcpy(&m->image, image, sizeof m->image);
}

int o3m_audition(Opl3Machine* m, const Opl3Image* image, int channel, int note, int slot, int fine) {
    if (image->info.loaded != O3_LOADED) return O3M_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    chip_reset(m);
    if (o3p_audition(&m->player, &m->image, channel, note, slot, fine)) {
        m->loaded = 0;
        return O3M_ERR_IMAGE;
    }
    m->loaded = 1;
    m->audition = 1;
    m->audition_channel = channel;
    send_writes(m);
    return O3M_OK;
}

static int audition_silent(const Opl3Machine* m) {
    int slots[4];
    const int n = o3p_carrier_slots(&m->player, m->audition_channel, slots);
    for (int i = 0; i < n; ++i) {
        const opl3_slot* s = m->chip.channel[slots[i] >> 1].slotz[slots[i] & 1];
        if (s->eg_rout != 0x1FF) return 0;
    }
    return 1;
}

int o3m_release(Opl3Machine* m) {
    if (!m->loaded || !m->audition) return 0;
    if (!m->ticks || audition_silent(m)) {
        m->loaded = 0;
        return 0;
    }
    if (!m->released) {
        o3p_key_off(&m->player, m->audition_channel);
        send_writes(m);
        m->released = 1;
        m->release_ticks = 0;
    }
    return 1;
}

int o3m_mute(Opl3Machine* m, int channel, int muted) {
    if (channel < 0 || channel >= O3_CHANNELS) return O3M_ERR_IMAGE;
    m->muted[channel] = muted != 0;
    sync_c0(m);
    return O3M_OK;
}

void o3m_position(const Opl3Machine* m, int* order, int* row, int* tick) {
    *order = m->player.order;
    *row = m->player.row;
    *tick = m->player.tick;
}

/* One player tick and its chip samples into the resampler (frames then wait in the blip buffers). */
static int run_tick(Opl3Machine* m) {
    o3p_tick(&m->player, &m->image);
    const uint32_t period = 60u * O3M_NATIVE_DIVIDER * (uint32_t)m->image.info.multispeed;
    m->native_acc += O3M_CLOCK;
    const int n = (int)(m->native_acc / period);
    m->native_acc -= (uint32_t)n * period;
    m->native_samples = n;
    /* Key-offs one chip sample before the rest, so a key-off + key-on retriggers (opl3_player.h). */
    const int offs = m->player.offs;
    if (offs) {
        send_range(m, 0, offs);
        generate(m, 0, 1);
    }
    send_range(m, offs, m->player.writes);
    generate(m, offs ? 1 : 0, n);
    for (int k = 0; k < 2; ++k) blip_end_frame(m->blip[k], (unsigned)n);
    return n;
}

int o3m_tick(Opl3Machine* m, float* out) {
    if (!m->loaded) return 0;
    if (m->released) {
        const int limit = O3M_RELEASE_SECONDS * 60 * m->image.info.multispeed;
        if (audition_silent(m) || m->release_ticks >= limit) {
            m->loaded = 0;
            return 0;
        }
        ++m->release_ticks;
    }
    run_tick(m);
    int frames = 0;
    for (int k = 0; k < 2; ++k) frames = blip_read_samples(m->blip[k], m->pcm + k, (int)O3M_MAX_TICK_FRAMES, 1);
    for (int i = 0; i < 2 * frames; ++i) out[i] = m->pcm[i] * (1.0f / 32768.0f);
    ++m->ticks;
    return frames;
}

const O3Player* o3m_player(const Opl3Machine* m) { return &m->player; }
uint32_t o3m_ticks(const Opl3Machine* m) { return m->ticks; }
int o3m_native_samples(const Opl3Machine* m) { return m->native_samples; }
