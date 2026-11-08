/* VIC-20 / C16-Plus/4 music machine (cbm_machine.h): player + the VIC or TED sound unit + the TED timer's DAC
 * interrupts, clocked on the chip's timeline, resampled with blip_buf. */
#include "cbm_machine.h"

#include <stdlib.h>
#include <string.h>

#include "blip_buf.h"
#include "cbm_ted.h"
#include "cbm_vic.h"

const int CBM_FRAME_CYCLES[4] = {71 * 312, 65 * 261, 57 * 312, 57 * 262};
const double CBM_CLOCK_HZ[4] = {17734475.0 / 16.0, 315000000.0 / 308.0, 17734475.0 / 20.0, 315000000.0 / 352.0};

#define NO_IRQ INT64_MAX

struct CbmMachine {
    uint32_t rate;
    int chip;
    blip_t* blip;
    CbVic vic;
    CbTed ted;
    CbPlayer player;
    CbmImage image;
    int loaded;
    /* time: tick k starts at cycle base_cycle + floor((k - base_tick) F / N) */
    int64_t tick_index;        /* ticks since the reset (seek included) */
    int64_t base_cycle, base_tick;
    int variant;               /* CB_VARIANT_* of the timeline, the unit's clock and blip_buf's rate */
    int multispeed;            /* N of the timeline (the image's, from the tick after a live edit) */
    int64_t next_irq;          /* cycle of the next TED timer interrupt (NO_IRQ on the VIC) */
    int64_t irq_index;         /* interrupts since the reset */
    uint32_t ticks;            /* ticks since start/audition, seek excluded */
    int interrupts;
    float lp, hp, lb, hb;      /* the VIC-20 board's output stage (VICE) */
    unsigned mute;
    int audition, audition_channel, released, ended;
    int64_t release_cycle;
    CbmTraceFn trace;
    void* trace_ctx;
    short pcm[CBM_MAX_TICK_FRAMES + 16];
};

/* Cycle at which tick k starts. */
static int64_t tick_cycle(const CbmMachine* m, int64_t k) {
    return m->base_cycle + (k - m->base_tick) * CBM_FRAME_CYCLES[m->variant] / m->multispeed;
}

static void chip_run(CbmMachine* m, unsigned from, unsigned to) {
    if (m->chip == CB_CHIP_VIC) cbv_run(&m->vic, m->blip, from, to);
    else cbt_run(&m->ted, m->blip, from, to);
}

/* Sends the player's current batch at `time` cycles into the current blip frame (timeline cycle `cycle`). */
static void send(CbmMachine* m, int kind, int64_t index, int64_t cycle, unsigned time, int order, int row, int tick) {
    const CbPlayer* p = &m->player;
    if (m->trace) m->trace(m->trace_ctx, kind, index, cycle, order, row, tick, p->write, p->writes);
    for (int i = 0; i < p->writes; ++i) {
        if (m->chip == CB_CHIP_VIC) cbv_write(&m->vic, m->blip, time, p->write[i].reg, p->write[i].val);
        else cbt_write(&m->ted, p->write[i].reg, p->write[i].val);
    }
}

/* Reset: chip unit, resampler, output stage, time and timer (the timer counts from cycle 0 with the player's T: call
 * after the player's reset), then the player's reset batch at cycle 0. */
static void machine_reset(CbmMachine* m) {
    m->variant = m->chip * 2 + m->image.info.video;
    m->multispeed = m->image.info.multispeed;
    blip_set_rates(m->blip, CBM_CLOCK_HZ[m->variant], m->rate);
    blip_clear(m->blip);
    cbv_reset(&m->vic);
    cbt_reset(&m->ted);
    m->lp = m->hp = 0.0f;
    m->tick_index = m->base_tick = m->base_cycle = 0;
    m->next_irq = m->chip == CB_CHIP_TED ? cbp_timer_cycles(&m->player) : NO_IRQ;
    m->irq_index = 0;
    m->ticks = 0;
    m->interrupts = 0;
    m->mute = 0;
    m->audition = m->released = m->ended = 0;
    m->release_cycle = 0;
    send(m, CBM_TRACE_RESET, 0, 0, 0, m->player.order, m->player.row, m->player.tick);
}

CbmMachine* cbm_create(uint32_t sample_rate, int chip) {
    if (sample_rate < 8000 || sample_rate > CBM_MAX_SAMPLE_RATE || (chip != CB_CHIP_VIC && chip != CB_CHIP_TED)) return NULL;
    CbmMachine* m = (CbmMachine*)calloc(1, sizeof *m);
    if (!m) return NULL;
    m->rate = sample_rate;
    m->chip = chip;
    m->blip = blip_new((int)CBM_MAX_TICK_FRAMES + 64);
    if (!m->blip) {
        cbm_destroy(m);
        return NULL;
    }
    m->variant = chip * 2;
    m->multispeed = 1;
    blip_set_rates(m->blip, CBM_CLOCK_HZ[m->variant], sample_rate);
    /* VICE's vic_sound_machine_init: dt = 1 / rate; low-pass 1 kOhm, 100 nF; high-pass 1 kOhm, 1 uF. */
    const float dt = 1.f / (float)sample_rate;
    m->lb = dt / (dt + 1e-4f);
    m->hb = dt / (dt + 1e-3f);
    cbv_reset(&m->vic);
    cbt_reset(&m->ted);
    return m;
}

void cbm_destroy(CbmMachine* m) {
    if (!m) return;
    if (m->blip) blip_delete(m->blip);
    free(m);
}

/* One player tick and its timer interrupts, clocked through the chip unit into the resampler (frames then wait). */
static void run_tick(CbmMachine* m) {
    CbPlayer* p = &m->player;
    const int64_t start = tick_cycle(m, m->tick_index), end = tick_cycle(m, m->tick_index + 1);
    const int order = p->order, row = p->row, tick = p->tick;
    cbp_tick(p, &m->image);
    send(m, CBM_TRACE_TICK, m->tick_index, start, 0, order, row, tick);
    unsigned now = 0;
    int n = 0;
    while (m->next_irq < end) {
        const int64_t at = m->next_irq;
        const unsigned rel = (unsigned)(at - start);
        chip_run(m, now, rel);
        now = rel;
        cbp_timer(p, &m->image);
        send(m, CBM_TRACE_TIMER, m->irq_index, at, rel, p->order, p->row, p->tick);
        ++m->irq_index;
        m->next_irq = at + cbp_timer_cycles(p); /* the T held now: a Txx applies from this reload */
        ++n;
    }
    chip_run(m, now, (unsigned)(end - start));
    blip_end_frame(m->blip, (unsigned)(end - start));
    m->interrupts = n;
    ++m->tick_index;
}

/* The frames waiting in the resampler (through the VIC-20 output stage on the VIC), as float stereo into `out` (NULL:
 * discarded, the filters still run). */
static int render(CbmMachine* m, float* out) {
    const int frames = blip_read_samples(m->blip, m->pcm, (int)CBM_MAX_TICK_FRAMES, 0);
    for (int i = 0; i < frames; ++i) {
        float v;
        if (m->chip == CB_CHIP_VIC) {
            const float x = (float)m->pcm[i];
            const float o = m->lp - m->hp;
            m->hp += m->hb * (m->lp - m->hp);
            m->lp += m->lb * (x - m->lp);
            v = o / 32768.0f;
        } else {
            v = m->pcm[i] * (1.0f / 32768.0f);
        }
        if (out) out[2 * i] = out[2 * i + 1] = v;
    }
    return frames;
}

static int at(const CbPlayer* p, int order, int row) { return p->order == order && p->row == row && p->tick == 0; }

/* Puts the player at the song start (or at the order's row 0 with its start speed: from_order) and runs it alone
 * until the next tick starts (order, row) - at most `bound` ticks, never leaving the order with from_order. Returns
 * the ticks run, or -1 if (order, row) was not reached. */
static int find_ticks(CbPlayer* p, const CbmImage* image, int from_order, int order, int row, int bound) {
    if (from_order) cbp_place(p, image, order);
    else cbp_reset(p, image);
    int n = 0;
    while (!at(p, order, row) && n < bound && (!from_order || p->order == order)) {
        cbp_tick(p, image);
        ++n;
    }
    return at(p, order, row) ? n : -1;
}

int cbm_start(CbmMachine* m, const CbmImage* image, int order, int row, int max_ticks, int* seek_mode) {
    if (seek_mode) *seek_mode = CBM_SEEK_FROM_START;
    if (image->info.loaded != CB_LOADED || image->info.chip != m->chip || order < 0 || order >= image->info.orders ||
        row < 0 || row >= image->info.rows[order])
        return CBM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    m->loaded = 0;
    CbPlayer* p = &m->player;
    int from_order = 0;
    int ticks = find_ticks(p, &m->image, 0, order, row, max_ticks < 0 ? 0 : max_ticks);
    if (ticks < 0) {
        /* Replaying the order's earlier rows takes < 256 rows x 255 ticks, so it has its own bound instead of
         * max_ticks. A row behind a Dxx/Bxx of the same order is unreachable. */
        from_order = 1;
        if (seek_mode) *seek_mode = CBM_SEEK_FROM_ORDER;
        ticks = find_ticks(p, &m->image, 1, order, row, 256 * 256);
        if (ticks < 0) return CBM_ERR_IMAGE;
    }
    /* The same ticks again, clocked as audible playback (chip unit, timer, resampler, output stage), the output
     * discarded. */
    if (from_order) cbp_place(p, &m->image, order);
    else cbp_reset(p, &m->image);
    machine_reset(m);
    for (int t = 0; t < ticks; ++t) {
        run_tick(m);
        render(m, NULL);
    }
    m->ticks = 0;
    m->loaded = 1;
    return CBM_OK;
}

void cbm_apply_image(CbmMachine* m, const CbmImage* image) {
    if (!m->loaded || m->audition || image->info.loaded != CB_LOADED || image->info.chip != m->chip) return;
    cbp_relocate(&m->player, &m->image, image);
    const int variant = m->chip * 2 + image->info.video;
    if (image->info.multispeed != m->multispeed || variant != m->variant) {
        /* The next tick is the first of the new tick length; a new video standard also switches the unit's clock (the
         * resampler's input rate) from there; the unit's state continues. */
        m->base_cycle = tick_cycle(m, m->tick_index);
        m->base_tick = m->tick_index;
        m->multispeed = image->info.multispeed;
        if (variant != m->variant) {
            m->variant = variant;
            blip_set_rates(m->blip, CBM_CLOCK_HZ[variant], m->rate);
        }
    }
    memcpy(&m->image, image, sizeof m->image);
}

int cbm_audition(CbmMachine* m, const CbmImage* image, int channel, int note, int slot, int fine) {
    if (image->info.loaded != CB_LOADED || image->info.chip != m->chip) return CBM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    m->loaded = 0;
    if (cbp_audition(&m->player, &m->image, channel, note, slot, fine)) return CBM_ERR_IMAGE;
    machine_reset(m);
    m->loaded = 1;
    m->audition = 1;
    m->audition_channel = channel;
    return CBM_OK;
}

int cbm_release(CbmMachine* m) {
    if (!m->loaded || !m->audition) return 0;
    if (!m->ticks) {
        m->loaded = 0;
        return 0;
    }
    if (m->released) return m->ended ? 0 : 1;
    CbPlayer* p = &m->player;
    cbp_off(p, &m->image, m->audition_channel);
    m->release_cycle = tick_cycle(m, m->tick_index);
    send(m, CBM_TRACE_OFF, 0, m->release_cycle, 0, p->order, p->row, p->tick);
    m->released = 1;
    if (!p->ch[m->audition_channel].sounding) {
        m->ended = 1;
        return 0;
    }
    return 1;
}

int cbm_mute(CbmMachine* m, int channel, int muted) {
    const int channels = m->chip == CB_CHIP_VIC ? CB_VIC_CHANNELS : CB_TED_CHANNELS;
    if (channel < 0 || channel >= channels) return CBM_ERR_IMAGE;
    if (muted) m->mute |= 1u << channel;
    else m->mute &= ~(1u << channel);
    /* Between ticks: the next tick's frame starts at blip time 0. */
    if (m->chip == CB_CHIP_VIC) cbv_mute(&m->vic, m->blip, 0, m->mute);
    else cbt_mute(&m->ted, m->mute);
    return CBM_OK;
}

void cbm_position(const CbmMachine* m, int* order, int* row, int* tick) {
    *order = m->player.order;
    *row = m->player.row;
    *tick = m->player.tick;
}

/* The released audition note has ended: it stopped (a one-shot sample's end, or OFF without a release point), or its
 * wave envelope sits at its last step with value 0 (written), or the written volume is 0 with the owner's volume
 * envelope at its last step. */
static int audition_done(const CbmMachine* m) {
    const CbPlayer* p = &m->player;
    const int c = m->audition_channel;
    const CbChannel* ch = &p->ch[c];
    if (!ch->sounding) return 1;
    if (!m->released) return 0;
    const uint8_t* rec = m->image.bytes + CB_OFF_INSTRUMENTS + CB_INSTRUMENT_SIZE * (ch->slot - 1);
    const uint8_t* wave = rec + CB_I_ENVELOPES + CB_ENVELOPE_BYTES * CB_ENV_WAVE;
    if (wave[2] && ch->env[CB_ENV_WAVE] >= wave[2] - 1 && ch->wave == CB_WAVE_OFF &&
        m->image.bytes[CB_OFF_DATA + cb_u16(wave) + wave[2] - 1] == CB_WAVE_OFF)
        return 1;
    const uint8_t* vol = rec + CB_I_ENVELOPES + CB_ENVELOPE_BYTES * CB_ENV_VOLUME;
    const int written = m->chip == CB_CHIP_VIC ? p->shadow[0x0E] & 0x0F : (p->dac ? 1 : p->shadow[0x11] & 0x0F);
    return p->owner == c && vol[2] && ch->env[CB_ENV_VOLUME] >= vol[2] - 1 && written == 0;
}

int cbm_tick(CbmMachine* m, float* out) {
    if (!m->loaded) return 0;
    if (m->audition && m->ticks) {
        const int64_t limit = (int64_t)(CBM_RELEASE_SECONDS * CBM_CLOCK_HZ[m->variant]);
        if (m->ended || audition_done(m) || (m->released && tick_cycle(m, m->tick_index) - m->release_cycle >= limit)) {
            m->ended = 1;
            m->loaded = 0;
            return 0;
        }
    }
    run_tick(m);
    const int frames = render(m, out);
    ++m->ticks;
    return frames;
}

const CbPlayer* cbm_player(const CbmMachine* m) { return &m->player; }
uint32_t cbm_ticks(const CbmMachine* m) { return m->ticks; }
int cbm_timer_interrupts(const CbmMachine* m) { return m->interrupts; }
int64_t cbm_cycle(const CbmMachine* m) { return tick_cycle(m, m->tick_index); }
void cbm_set_trace(CbmMachine* m, CbmTraceFn fn, void* ctx) {
    m->trace = fn;
    m->trace_ctx = ctx;
}

int cbm_chip_state(const CbmMachine* m, uint32_t* out) {
    int n = 0;
    if (m->chip == CB_CHIP_VIC) {
        const CbVic* v = &m->vic;
        for (int j = 0; j < 4; ++j) {
            out[n++] = (uint32_t)v->ch[j].ctr;
            out[n++] = v->ch[j].reg | (uint32_t)v->ch[j].shift << 8 | (uint32_t)v->ch[j].out << 16;
        }
        out[n++] = v->lfsr | (uint32_t)v->lfsr0_old << 16 | (uint32_t)v->volume << 24;
    } else {
        const CbTed* t = &m->ted;
        out[n++] = t->regs[0x0E] | (uint32_t)t->regs[0x0F] << 8 | (uint32_t)t->regs[0x10] << 16 | (uint32_t)t->regs[0x11] << 24;
        out[n++] = t->regs[0x12] | (uint32_t)t->cycle_count << 8 | (uint32_t)t->prvCycleCount << 16;
        out[n++] = t->soundChannel1Cnt | (uint32_t)t->soundChannel1Reload << 16;
        out[n++] = t->soundChannel2Cnt | (uint32_t)t->soundChannel2Reload << 16;
        out[n++] = t->soundChannel1Decay;
        out[n++] = t->soundChannel2Decay;
        out[n++] = t->prvSoundChannel1Overflow | (uint32_t)t->prvSoundChannel2Overflow << 8 |
                   (uint32_t)t->soundChannel1State << 16 | (uint32_t)t->soundChannel2State << 24;
        out[n++] = t->soundChannel2NoiseState | (uint32_t)t->soundFlags << 8 | (uint32_t)t->soundVolume << 16;
        out[n++] = t->soundOutput | (uint32_t)t->prvSoundOutput << 8;
    }
    return n;
}
