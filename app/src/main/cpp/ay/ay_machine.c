/* AY music machine (ay_machine.h): player + AY/YM core + blip_buf (one buffer, or left and right on the CPC). */
#include "ay_machine.h"

#include <stdlib.h>
#include <string.h>

struct AyMachine {
    uint32_t rate;
    int variant;
    uint32_t master_hz, frame_div, step_div; /* step_div = 8 * ay_div master clocks per chip step */
    int stereo;     /* AY_STEREO_*: blip[1] holds the right side */
    AyPsg chip;
    blip_t* blip[2];
    AyPlayer player;
    AyImage image;
    int loaded;
    uint32_t acc;   /* master clocks of the tick not yet run as whole steps */
    uint32_t steps; /* chip steps of the last tick */
    uint32_t ticks;
    int mute;
    int audition, audition_channel, released, release_ticks, release_limit;
    short pcm[2 * AYM_MAX_TICK_FRAMES + 16];
};

static void send_writes(AyMachine* m) {
    const AyPlayer* p = &m->player;
    for (int i = 0; i < p->writes; ++i) ay_psg_write(&m->chip, p->write[i].reg, p->write[i].val);
}

static void machine_reset(AyMachine* m) {
    ay_psg_reset(&m->chip, ay_variants[m->variant].ym, m->stereo);
    for (int k = 0; k <= m->stereo; ++k) blip_clear(m->blip[k]);
    m->acc = 0;
    m->steps = 0;
    m->ticks = 0;
    m->mute = 0;
    m->audition = m->released = m->release_ticks = 0;
}

AyMachine* ay_machine_create(uint32_t sample_rate, int variant) {
    if (sample_rate < 8000 || sample_rate > AYM_MAX_SAMPLE_RATE || variant < 0 || variant >= AY_VARIANTS) return NULL;
    AyMachine* m = (AyMachine*)calloc(1, sizeof *m);
    if (!m) return NULL;
    const AyVariantSpec* v = &ay_variants[variant];
    m->rate = sample_rate;
    m->variant = variant;
    m->stereo = v->stereo;
    m->master_hz = v->master_hz;
    m->frame_div = v->frame_div;
    m->step_div = 8 * v->ay_div;
    m->release_limit = (int)((uint64_t)AYM_RELEASE_SECONDS * v->master_hz / v->frame_div);
    for (int k = 0; k <= m->stereo; ++k) {
        m->blip[k] = blip_new((int)AYM_MAX_TICK_FRAMES + 64);
        if (!m->blip[k]) {
            ay_machine_destroy(m);
            return NULL;
        }
        blip_set_rates(m->blip[k], (double)v->master_hz / m->step_div, sample_rate);
    }
    machine_reset(m);
    return m;
}

void ay_machine_destroy(AyMachine* m) {
    if (!m) return;
    for (int k = 0; k < 2; ++k) blip_delete(m->blip[k]);
    free(m);
}

/* One player tick: its writes at the tick's first step, then the tick's steps (into the blip buffer, or silently). */
static void run_tick(AyMachine* m, int audible) {
    ay_player_tick(&m->player, &m->image);
    send_writes(m);
    m->acc += m->frame_div;
    m->steps = m->acc / m->step_div;
    m->acc -= m->steps * m->step_div;
    ay_psg_run(&m->chip, m->steps, audible ? m->blip[0] : NULL, audible ? m->blip[1] : NULL);
    if (audible)
        for (int k = 0; k <= m->stereo; ++k) blip_end_frame(m->blip[k], m->steps);
}

uint32_t ay_machine_tick_silent(AyMachine* m) {
    run_tick(m, 0);
    return m->steps;
}

static int at(const AyPlayer* p, int order, int row) { return p->order == order && p->row == row && p->tick == 0; }

/* Ticks the player (with the chip silently advanced) until the next tick starts (order, row): at most `bound` ticks,
 * never leaving `order` when `in_order`. Returns 1 when reached. */
static int seek(AyMachine* m, int order, int row, int bound, int in_order) {
    for (int n = 0; !at(&m->player, order, row); ++n) {
        if (n >= bound || (in_order && m->player.order != order)) return 0;
        run_tick(m, 0);
    }
    return 1;
}

int ay_machine_start(AyMachine* m, const AyImage* image, int order, int row, int max_ticks, int* seek_mode) {
    if (seek_mode) *seek_mode = AYM_SEEK_FROM_START;
    if (image->info.loaded != AY_LOADED || image->info.variant != m->variant || order < 0 || order >= image->info.orders ||
        row < 0 || row >= image->info.rows[order])
        return AYM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    machine_reset(m);
    ay_player_reset(&m->player, &m->image);
    send_writes(m);
    if (!seek(m, order, row, max_ticks < 0 ? 0 : max_ticks, 0)) {
        /* Replaying the order's earlier rows takes < 256 rows x 255 ticks, so it has its own bound instead of
         * max_ticks. A row behind a Dxx/Bxx of the same order is unreachable. */
        if (seek_mode) *seek_mode = AYM_SEEK_FROM_ORDER;
        machine_reset(m);
        ay_player_place(&m->player, &m->image, order);
        send_writes(m);
        if (!seek(m, order, row, 256 * 256, 1)) {
            m->loaded = 0;
            return AYM_ERR_IMAGE;
        }
    }
    m->player.writes = 0;
    m->ticks = 0;
    m->loaded = 1;
    return AYM_OK;
}

void ay_machine_apply_image(AyMachine* m, const AyImage* image) {
    if (!m->loaded || m->audition || image->info.loaded != AY_LOADED) return;
    ay_player_relocate(&m->player, &m->image, image);
    memcpy(&m->image, image, sizeof m->image);
}

int ay_machine_audition(AyMachine* m, const AyImage* image, int channel, int note, int slot, int fine) {
    if (image->info.loaded != AY_LOADED || image->info.variant != m->variant) return AYM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    machine_reset(m);
    if (ay_player_audition(&m->player, &m->image, channel, note, slot, fine)) {
        m->loaded = 0;
        return AYM_ERR_IMAGE;
    }
    send_writes(m);
    m->loaded = 1;
    m->audition = 1;
    m->audition_channel = channel;
    return AYM_OK;
}

int ay_machine_release(AyMachine* m) {
    if (!m->loaded || !m->audition) return 0;
    if (!m->ticks || m->player.ch[m->audition_channel].phase == AYP_OFF) {
        m->loaded = 0;
        return 0;
    }
    if (!m->released) {
        ay_player_key_off(&m->player, m->audition_channel);
        m->released = 1;
        m->release_ticks = 0;
    }
    return 1;
}

int ay_machine_mute(AyMachine* m, int channel, int muted) {
    if (channel < 0 || channel >= AY_CHANNELS) return AYM_ERR_IMAGE;
    if (muted) m->mute |= 1 << channel;
    else m->mute &= ~(1 << channel);
    ay_psg_set_mute(&m->chip, m->mute);
    return AYM_OK;
}

void ay_machine_position(const AyMachine* m, int* order, int* row, int* tick) {
    *order = m->player.order;
    *row = m->player.row;
    *tick = m->player.tick;
}

int ay_machine_tick(AyMachine* m, float* out) {
    if (!m->loaded) return 0;
    if (m->released) {
        if (m->player.ch[m->audition_channel].phase == AYP_OFF || m->release_ticks >= m->release_limit) {
            m->loaded = 0;
            return 0;
        }
        ++m->release_ticks;
    }
    run_tick(m, 1);
    int frames;
    if (m->stereo) {
        frames = blip_read_samples(m->blip[0], m->pcm, (int)AYM_MAX_TICK_FRAMES, 1);
        blip_read_samples(m->blip[1], m->pcm + 1, (int)AYM_MAX_TICK_FRAMES, 1);
        for (int i = 0; i < 2 * frames; ++i) out[i] = m->pcm[i] * (1.0f / 32768.0f);
    } else {
        frames = blip_read_samples(m->blip[0], m->pcm, (int)AYM_MAX_TICK_FRAMES, 0);
        for (int i = 0; i < frames; ++i) out[2 * i] = out[2 * i + 1] = m->pcm[i] * (1.0f / 32768.0f);
    }
    ++m->ticks;
    return frames;
}

const AyPlayer* ay_machine_player(const AyMachine* m) { return &m->player; }
const AyPsg* ay_machine_chip(const AyMachine* m) { return &m->chip; }
int ay_machine_variant(const AyMachine* m) { return m->variant; }
uint32_t ay_machine_ticks(const AyMachine* m) { return m->ticks; }
uint32_t ay_machine_steps(const AyMachine* m) { return m->steps; }
