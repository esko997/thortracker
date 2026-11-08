/* OPN / OPNA music machine (opn_machine.h): player + ymfm's YM2203 / YM2608 (opn_ymfm.h) + blip_buf.
 *
 * Mix (MAME's routing, pc8801.cpp; levels in 16-bit units, 32768 = 1.0 at the host), at the chip's own DAC range as
 * the OPL3 machine plays Nuked-OPL3's (a full-level FM carrier peaks at 0.125 on both chips, as on the OPL3):
 *  - YM2203: FM (ymfm's 14-bit DAC value, a full carrier +-8191) and the three SSG channels (ymfm's ssg_engine levels,
 *    0..16382 each) at equal weight, halved: a full carrier peaks at 0.125, a full-volume SSG channel swings 0..0.25
 *    (+-0.125 once blip_buf's high-pass has taken its DC off); everything at full is 1.125. Mono: left = right.
 *  - YM2608: FM + rhythm + ADPCM-B summed and clamped to 16 bits as ymfm's ym2608::clock_fm_and_adpcm does (FM right
 *    shift 1, a full carrier +-4095 = 0.125; ADPCM-B right shift 1, a full sample at level 255 = 0.5); the SSG mixed to
 *    mono as ymfm does ((a + b + c) x 2/3) at half the FM route's weight (MAME: SSG 0.25, FM 0.5), added to both
 *    sides: a full SSG channel swings 0..0.167.
 *  Levels are clamped to 16 bits before blip_buf (its integrator's range; the host clamps to [-1, 1] anyway).
 *
 * Seek (opnm_start): the player runs alone once to find how many ticks reach the target (within max_ticks from the
 * song start, else from the order start). Then the machine is reset exactly as a fresh one (chip power-on state and
 * power-on writes, empty resamplers, no mutes) and every tick from the start point to the target runs exactly as
 * opnm_tick runs it - all player writes, the key-off phase one native sample before the rest, the whole chip clocked,
 * blip_end_frame, the frames read out of blip_buf the same way - with the frames discarded. After a FROM_START seek
 * the PCM opnm_tick produces is therefore sample-identical to straight play from (0, 0) reaching that row (FM
 * envelopes and release tails, SSG, rhythm drums, ADPCM-B, CSM timer phase and the resampler state included). The
 * cost is that of rendering the skipped music itself (every native sample of every skipped tick through ymfm and
 * blip_buf, output dropped), so a seek deep into a long song takes as long as the WAV export of that stretch;
 * max_ticks bounds it, past which the order-start fallback replays at most the order's earlier rows. */
#include "opn_machine.h"

#include <stdlib.h>
#include <string.h>

#include "blip_buf.h"

#define OPNM_MIX_DIVISOR_YM2203 2 /* (FM + SSG a + b + c) / 2 */
#define OPNM_SSG_DIVISOR_YM2608 3 /* (a + b + c) x 2/3, then / 2 */

struct OpnMachine {
    int kind; /* OPN_CHIP_* */
    uint32_t rate;
    OpnChip* chip;
    OpnImage* image; /* the machine's own copy (the ADPCM-B memory the chip reads is image->bytes[OPN_OFF_ADPCM..]) */
    blip_t* blip[2];
    int32_t last[2];
    OpnPlayer player;
    int loaded;
    uint32_t mute; /* OPN_MUTE_* bits of muted tracker channels */
    int native_samples;
    uint32_t ticks;
    int audition, audition_channel, released;
    uint32_t release_ticks;
    OpnWrite scratch[OPNP_MAX_RESET_WRITES];
    short pcm[2 * OPNM_MAX_TICK_FRAMES + 16];
};

/* The chip's mute bit of a tracker channel. */
static uint32_t mute_bit(int kind, int channel) {
    switch (opn_part(kind, channel)) {
    case OPN_PART_FM: return OPN_MUTE_FM(channel);
    case OPN_PART_SSG: return OPN_MUTE_SSG(channel - opn_ssg_first(kind));
    case OPN_PART_RHYTHM: return OPN_MUTE_RHYTHM(channel - OPNA_CH_RHYTHM);
    case OPN_PART_ADPCM: return OPN_MUTE_ADPCM;
    default: return 0;
    }
}

/* Writes write[from .. to - 1] of the player's batch. */
static void send_range(OpnMachine* m, int from, int to) {
    const OpnPlayer* p = &m->player;
    for (int i = from; i < to; ++i) opn_chip_write(m->chip, p->write[i].port, p->write[i].reg, p->write[i].val);
}

static void send_writes(OpnMachine* m) { send_range(m, 0, m->player.writes); }

static int32_t clamp16(int32_t v) { return v < -32768 ? -32768 : v > 32767 ? 32767 : v; }

/* Native samples [from, to) of the current frame into the resampler. */
static void generate(OpnMachine* m, int from, int to) {
    for (int i = from; i < to; ++i) {
        OpnOutput o;
        opn_chip_generate(m->chip, m->mute, &o);
        int32_t level[2];
        if (m->kind == OPN_CHIP_YM2203) {
            level[0] = level[1] = clamp16((o.fm[0] + o.ssg[0] + o.ssg[1] + o.ssg[2]) / OPNM_MIX_DIVISOR_YM2203);
        } else {
            const int32_t ssg = (o.ssg[0] + o.ssg[1] + o.ssg[2]) / OPNM_SSG_DIVISOR_YM2608;
            for (int k = 0; k < 2; ++k) level[k] = clamp16(clamp16(o.fm[k] + o.rhythm[k] + o.adpcm[k]) + ssg);
        }
        for (int k = 0; k < 2; ++k) {
            if (level[k] != m->last[k]) {
                blip_add_delta(m->blip[k], (unsigned)i, level[k] - m->last[k]);
                m->last[k] = level[k];
            }
        }
    }
}

/* The chip at power-on plus the power-on writes (the state opnp_reset's shadow starts from), an empty resampler and
 * no audition. */
static void machine_reset(OpnMachine* m) {
    opn_chip_reset(m->chip);
    const int n = opnp_machine_reset(m->kind, m->scratch);
    for (int i = 0; i < n; ++i) opn_chip_write(m->chip, m->scratch[i].port, m->scratch[i].reg, m->scratch[i].val);
    for (int k = 0; k < 2; ++k) {
        blip_clear(m->blip[k]);
        m->last[k] = 0;
    }
    m->ticks = 0;
    m->native_samples = 0;
    m->audition = m->released = 0;
    m->release_ticks = 0;
    m->mute = 0;
}

/* The machine's image copy: info and used bytes, and the ADPCM-B memory size the chip may read. */
static void take_image(OpnMachine* m, const OpnImage* image) {
    opn_copy_image(m->image, image);
    if (m->kind == OPN_CHIP_YM2608)
        opn_chip_set_adpcm(m->chip, m->image->bytes + OPN_OFF_ADPCM, m->image->info.adpcm_size);
}

static int image_ok(const OpnMachine* m, const OpnImage* image) {
    return image->info.loaded == OPN_LOADED && image->info.chip == m->kind;
}

OpnMachine* opnm_create(int chip, uint32_t sample_rate) {
    if (chip != OPN_CHIP_YM2203 && chip != OPN_CHIP_YM2608) return NULL;
    if (sample_rate < 8000 || sample_rate > OPNM_MAX_SAMPLE_RATE) return NULL;
    OpnMachine* m = (OpnMachine*)calloc(1, sizeof *m);
    if (!m) return NULL;
    m->kind = chip;
    m->rate = sample_rate;
    m->image = (OpnImage*)calloc(1, sizeof *m->image);
    m->chip = opn_chip_create(chip);
    if (!m->image || !m->chip) {
        opnm_destroy(m);
        return NULL;
    }
    for (int k = 0; k < 2; ++k) {
        m->blip[k] = blip_new(OPNM_MAX_TICK_FRAMES + 64);
        if (!m->blip[k]) {
            opnm_destroy(m);
            return NULL;
        }
        blip_set_rates(m->blip[k], (double)OPN_NATIVE_RATE_NUM / OPN_NATIVE_RATE_DEN, sample_rate);
    }
    if (chip == OPN_CHIP_YM2608) opn_chip_set_adpcm(m->chip, m->image->bytes + OPN_OFF_ADPCM, 0);
    machine_reset(m);
    return m;
}

void opnm_destroy(OpnMachine* m) {
    if (!m) return;
    for (int k = 0; k < 2; ++k)
        if (m->blip[k]) blip_delete(m->blip[k]);
    if (m->chip) opn_chip_destroy(m->chip);
    free(m->image);
    free(m);
}

static int at(const OpnPlayer* p, int order, int row) { return p->order == order && p->row == row && p->tick == 0; }

/* Puts the player at the song start (or at the order's row 0 with its start speed: from_order) and runs it alone
 * until the next tick starts (order, row) (at most `bound` ticks, never leaving the order from_order). Returns the
 * ticks run, or -1 if (order, row) was not reached. */
static int count_ticks(OpnPlayer* p, const OpnImage* image, int from_order, int order, int row, int bound) {
    if (from_order) opnp_place(p, image, order);
    else opnp_reset(p, image);
    int n = 0;
    for (; !at(p, order, row) && n < bound && (!from_order || p->order == order); ++n) opnp_tick(p, image);
    return at(p, order, row) ? n : -1;
}

static int run_tick(OpnMachine* m);
static int read_frames(OpnMachine* m);

int opnm_start(OpnMachine* m, const OpnImage* image, int order, int row, int max_ticks, int* seek_mode) {
    if (seek_mode) *seek_mode = OPNM_SEEK_FROM_START;
    if (!image_ok(m, image) || order < 0 || order >= image->info.orders || row < 0 || row >= image->info.rows[order])
        return OPNM_ERR_IMAGE;
    take_image(m, image);
    m->loaded = 1;
    machine_reset(m);
    OpnPlayer* p = &m->player;
    int from_order = 0;
    int ticks = count_ticks(p, m->image, 0, order, row, max_ticks);
    if (ticks < 0) {
        /* Replaying the order's earlier rows takes < 256 rows x 255 ticks, so it has its own bound instead of
         * max_ticks. A row behind a Dxx/Bxx of the same order is unreachable. */
        from_order = 1;
        if (seek_mode) *seek_mode = OPNM_SEEK_FROM_ORDER;
        ticks = count_ticks(p, m->image, 1, order, row, 256 * 256);
        if (ticks < 0) {
            m->loaded = 0;
            return OPNM_ERR_IMAGE;
        }
    }
    if (from_order) opnp_place(p, m->image, order);
    else opnp_reset(p, m->image);
    send_writes(m);
    if (ticks == 0 && !from_order) return OPNM_OK; /* plain start at the song start: write[] = the init writes */
    /* Every tick as opnm_tick runs it, frames discarded. */
    for (int t = 0; t < ticks; ++t) {
        run_tick(m);
        read_frames(m);
    }
    p->writes = p->offs = 0;
    m->ticks = 0;
    return OPNM_OK;
}

void opnm_apply_image(OpnMachine* m, const OpnImage* image) {
    if (!m->loaded || m->audition || !image_ok(m, image)) return;
    opnp_relocate(&m->player, m->image, image);
    take_image(m, image);
}

int opnm_audition(OpnMachine* m, const OpnImage* image, int channel, int note, int slot, int fine) {
    if (!image_ok(m, image)) return OPNM_ERR_IMAGE;
    take_image(m, image);
    machine_reset(m);
    if (opnp_audition(&m->player, m->image, channel, note, slot, fine)) {
        m->loaded = 0;
        return OPNM_ERR_IMAGE;
    }
    m->loaded = 1;
    m->audition = 1;
    m->audition_channel = channel;
    send_writes(m);
    return OPNM_OK;
}

/* The audition channel makes no sound any more: FM carriers quiet, SSG level register 0 (no hardware envelope
 * either), the rhythm drum at its end, the ADPCM-B sample ended. */
static int audition_silent(const OpnMachine* m) {
    const int c = m->audition_channel;
    switch (opn_part(m->kind, c)) {
    case OPN_PART_FM: return opn_chip_fm_quiet(m->chip, c);
    case OPN_PART_SSG: return (m->player.reg[0][0x08 + c - opn_ssg_first(m->kind)] & 0x1F) == 0;
    case OPN_PART_RHYTHM: return !(opn_chip_rhythm_playing(m->chip) >> (c - OPNA_CH_RHYTHM) & 1u);
    case OPN_PART_ADPCM: return !opn_chip_adpcm_playing(m->chip);
    default: return 1;
    }
}

int opnm_release(OpnMachine* m) {
    if (!m->loaded || !m->audition) return 0;
    if (!m->ticks || audition_silent(m)) {
        m->loaded = 0;
        return 0;
    }
    if (!m->released) {
        opnp_key_off(&m->player, m->audition_channel);
        send_writes(m);
        m->released = 1;
        m->release_ticks = 0;
    }
    return 1;
}

int opnm_mute(OpnMachine* m, int channel, int muted) {
    const uint32_t bit = mute_bit(m->kind, channel);
    if (!bit) return OPNM_ERR_IMAGE;
    if (muted) m->mute |= bit;
    else m->mute &= ~bit;
    return OPNM_OK;
}

void opnm_position(const OpnMachine* m, int* order, int* row, int* tick) {
    *order = m->player.order;
    *row = m->player.row;
    *tick = m->player.tick;
}

/* One player tick and its chip samples into the resampler (frames then wait in the blip buffers). */
static int run_tick(OpnMachine* m) {
    opnp_tick(&m->player, m->image);
    const int n = (int)m->image->info.tick_samples;
    m->native_samples = n;
    /* Key-offs one native sample before the rest, so a key-off + key-on retriggers (opn_player.h). */
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

/* The tick's frames out of the resamplers into pcm (interleaved stereo); returns the frame count. */
static int read_frames(OpnMachine* m) {
    int frames = 0;
    for (int k = 0; k < 2; ++k) frames = blip_read_samples(m->blip[k], m->pcm + k, (int)OPNM_MAX_TICK_FRAMES, 1);
    return frames;
}

int opnm_tick(OpnMachine* m, float* out) {
    if (!m->loaded) return 0;
    if (m->released) {
        /* OPNM_RELEASE_SECONDS in ticks: seconds x native rate / native samples per tick. */
        const uint32_t limit = (uint32_t)((uint64_t)OPNM_RELEASE_SECONDS * OPN_NATIVE_RATE_NUM /
                                          ((uint64_t)OPN_NATIVE_RATE_DEN * m->image->info.tick_samples));
        if (audition_silent(m) || m->release_ticks >= limit) {
            m->loaded = 0;
            return 0;
        }
        ++m->release_ticks;
    }
    run_tick(m);
    const int frames = read_frames(m);
    for (int i = 0; i < 2 * frames; ++i) out[i] = m->pcm[i] * (1.0f / 32768.0f);
    ++m->ticks;
    return frames;
}

const OpnPlayer* opnm_player(const OpnMachine* m) { return &m->player; }
const OpnChip* opnm_chip(const OpnMachine* m) { return m->chip; }
uint32_t opnm_ticks(const OpnMachine* m) { return m->ticks; }
int opnm_native_samples(const OpnMachine* m) { return m->native_samples; }
