/* Mega Drive music machine (ym_machine.h): SGDK's XGM driver on z80.h + ymfm YM2612 + SN76489 + blip_buf, fed by
 * the player through the XGC encoder. */
#include "ym_machine.h"

#include <stdlib.h>
#include <string.h>

#include "blip_buf.h"
#include "generated/xgm_driver_image.h"
#include "ym_psg.h"
#include "ym_xgm.h"
#include "ym_ymfm.h"
#include "z80.h"

/* Output scale (/ 256, then sample / 32768): ymfm's YM2612 sum (one full-level sine channel peaks at 0.125, six at
 * 0.75) and the PSG's level table, in libvgm's default balance (VGMPlay volumes YM2612 0x200 : SN76489 0x100 with its
 * GPGX and MAME cores: a full-level PSG square has 0.35 times the rms of a full-level FM sine); docs "Mix". */
#define YM_FM_GAIN 192
#define YM_PSG_GAIN 65

#define PREFIX_MAX (YMP_MACHINE_RESET_WRITES + YMP_DUMP_WRITES + YMP_MAX_WRITES + 8)
#define FIFO 64 /* positions of the last encoded player ticks (the driver lags behind heavy frames) */

struct YmMachine {
    uint32_t rate;
    int region;
    uint32_t mclk_per_tick;
    /* Z80 side */
    z80_t cpu;
    uint64_t pins;
    uint8_t ram[0x2000];
    uint32_t bank;
    uint16_t ym_addr; /* the YM2612 address latch as the Z80 last wrote it (bit 8: port 2) */
    uint8_t ring[YMM_RING_SIZE];
    uint32_t ring_pos;
    /* chips */
    YmOpn2* opn;
    YmPsg psg;
    uint64_t mclk;       /* master clocks since machine reset */
    uint32_t ym_acc;     /* master clocks since the last YM2612 sample */
    uint32_t psg_div;    /* Z80 cycles since the last PSG step (one per 16) */
    int psg_written;     /* a PSG write since the last step (its output may change) */
    uint32_t tick_acc;   /* NTSC/PAL frame accumulator (whole Z80 cycles per tick) */
    unsigned fm_mute;
    /* output */
    blip_t* blip[2];
    int fm_last[2];
    int psg_last;
    short pcm[2 * YMM_MAX_TICK_FRAMES + 16];
    /* player and encoder */
    YmPlayer player;
    YmXgmEncoder enc;
    YmImage image;
    int loaded;
    int fifo[FIFO][3];   /* position of player tick i at fifo[i % FIFO] */
    uint32_t encoded;    /* player ticks encoded since the start (the last one is queued, not yet executed) */
    uint32_t ticks;
    int audition, audition_channel, released, release_ticks;
    uint32_t keyoff_tick; /* the encoded tick whose frame carries the audition's key-off */
    /* scratch */
    YmWrite prefix[PREFIX_MAX];
    int nprefix;
    YmWrite frame[PREFIX_MAX];
    uint8_t xgc[YMX_MAX_FRAME_BYTES];
    int last_frames_at, last_frames_size;
    /* test hooks */
    int log_on;
    YmmLogEntry log[YMM_LOG_MAX];
    int nlog;
    uint32_t cycle_in_tick, last_tick_cycles;
};

/* ---- the Z80 bus --------------------------------------------------------------------------------- */

static void log_write(YmMachine* m, int kind, int reg, int val) {
    if (m->log_on && m->nlog < YMM_LOG_MAX)
        m->log[m->nlog++] = (YmmLogEntry){m->cycle_in_tick, (uint8_t)kind, (uint8_t)reg, (uint8_t)val};
}

static void ym_bus_write(YmMachine* m, int port, uint8_t v) {
    ymo_write(m->opn, m->mclk, port, v);
    if (!(port & 1)) {
        m->ym_addr = (uint16_t)((port & 2 ? 0x100 : 0) | v);
    } else if ((port & 2) == (m->ym_addr & 0x100 ? 2 : 0)) {
        log_write(m, m->ym_addr >> 8, m->ym_addr & 0xFF, v);
    }
}

static uint8_t rom_read(const YmMachine* m, uint32_t a) {
    if (a >= YMM_NULL_SAMPLE && a < YMM_NULL_SAMPLE + 0x100) return 0;
    if (a >= YMM_RING && a < YMM_RING + YMM_RING_SIZE) return m->ring[a - YMM_RING];
    return 0xFF;
}

/* `cycles` Z80 cycles with the chips; output into the blip buffers when `emit`, at Z80-cycle times from `t0`. */
static void run(YmMachine* m, uint32_t cycles, int emit, uint32_t t0) {
    uint64_t pins = m->pins;
    for (uint32_t c = 0; c < cycles; ++c) {
        pins = z80_tick(&m->cpu, pins);
#ifdef YMM_PROFILE_HOOK
        YMM_PROFILE_HOOK(m);
#endif
        if (pins & Z80_MREQ) {
            const uint16_t a = Z80_GET_ADDR(pins);
            if (pins & Z80_RD) {
                uint8_t d = 0xFF;
                if (a < 0x4000) d = m->ram[a & 0x1FFF];
                else if (a < 0x6000) d = ymo_read_status(m->opn, m->mclk);
                else if (a >= 0x8000) d = rom_read(m, m->bank << 15 | (a & 0x7FFF));
                Z80_SET_DATA(pins, d);
            } else if (pins & Z80_WR) {
                const uint8_t d = Z80_GET_DATA(pins);
                if (a < 0x4000) {
                    m->ram[a & 0x1FFF] = d;
                } else if (a < 0x6000) {
                    ym_bus_write(m, a & 3, d);
                } else if (a < 0x6100) {
                    m->bank = ((m->bank >> 1) | (uint32_t)(d & 1) << 8) & 0x1FF;
                } else if ((a & 0xFFF9) == 0x7F11) {
                    ymps_write(&m->psg, d);
                    m->psg_written = 1; /* heard from the next step */
                    log_write(m, YM_W_PSG, 0, d);
                }
            }
        } else if ((pins & (Z80_IORQ | Z80_RD)) == (Z80_IORQ | Z80_RD)) {
            Z80_SET_DATA(pins, 0xFF);
        }
        m->mclk += YMM_MCLK_PER_Z80;
        ++m->cycle_in_tick;
        if (++m->psg_div == YMS_MCLK_PER_STEP / YMM_MCLK_PER_Z80) {
            m->psg_div = 0;
            if (!ymps_step(&m->psg) && m->psg_written == 0) goto ym;
            m->psg_written = 0;
            const int s = ymps_output(&m->psg);
            if (s != m->psg_last) {
                if (emit) {
                    const int d = (s - m->psg_last) * YM_PSG_GAIN >> 8;
                    blip_add_delta(m->blip[0], t0 + c, d);
                    blip_add_delta(m->blip[1], t0 + c, d);
                }
                m->psg_last = s;
            }
        }
    ym:
        m->ym_acc += YMM_MCLK_PER_Z80;
        if (m->ym_acc >= YMM_MCLK_PER_YM_SAMPLE) {
            m->ym_acc -= YMM_MCLK_PER_YM_SAMPLE;
            int32_t s[2];
            ymo_generate(m->opn, m->fm_mute, &s[0], &s[1]);
            for (int k = 0; k < 2; ++k) {
                if (s[k] != m->fm_last[k]) {
                    if (emit) blip_add_delta(m->blip[k], t0 + c, (s[k] - m->fm_last[k]) * YM_FM_GAIN >> 8);
                    m->fm_last[k] = s[k];
                }
            }
        }
    }
    m->pins = pins;
}

/* Z80 cycles of the next tick (one vertical interrupt), accumulated exactly in master clocks. */
static uint32_t tick_cycles(YmMachine* m) {
    m->tick_acc += m->mclk_per_tick;
    const uint32_t n = m->tick_acc / YMM_MCLK_PER_Z80;
    m->tick_acc -= n * YMM_MCLK_PER_Z80;
    return n;
}

/* ---- the 68000 side (SGDK xgm.c) ------------------------------------------------------------------ */

static void write_chips(YmMachine* m, const YmWrite* w, int n) {
    for (int i = 0; i < n; ++i) {
        if (w[i].kind == YM_W_PSG) {
            ymps_write(&m->psg, w[i].val);
            m->psg_written = 1;
        } else {
            ymo_write(m->opn, m->mclk, w[i].kind * 2, w[i].reg);
            ymo_write(m->opn, m->mclk, w[i].kind * 2 + 1, w[i].val);
        }
    }
    /* the driver keeps the address latch on the DAC register between its writes */
    ymo_write(m->opn, m->mclk, 0, 0x2A);
    m->ym_addr = 0x2A;
}

/* Power on: chips reset, SGDK's YM2612_reset() + PSG_reset(), the driver loaded and booted until ready, the
 * null sample and the 63 sample ids (all silent), the music address = the ring, the play command. */
static int machine_reset(YmMachine* m, int region) {
    m->region = region;
    m->mclk_per_tick = YMM_MCLK_PER_LINE * (region == YM_PAL ? YMM_LINES_PAL : YMM_LINES_NTSC);
    const double mclk_hz = region == YM_PAL ? YMM_MCLK_PAL : YMM_MCLK_NTSC;
    for (int k = 0; k < 2; ++k) {
        blip_set_rates(m->blip[k], mclk_hz / YMM_MCLK_PER_Z80, m->rate);
        blip_clear(m->blip[k]);
        m->fm_last[k] = 0;
    }
    ymo_reset(m->opn);
    ymps_reset(&m->psg);
    m->psg_last = ymps_output(&m->psg);
    m->mclk = 0;
    m->ym_acc = m->psg_div = m->tick_acc = 0;
    m->psg_written = 0;
    YmWrite reset[YMP_MACHINE_RESET_WRITES];
    write_chips(m, reset, ymp_machine_reset(reset));
    memset(m->ram, 0, sizeof m->ram);
    memcpy(m->ram, XGM_DRIVER, sizeof XGM_DRIVER);
    m->bank = 0;
    m->pins = z80_init(&m->cpu);
    m->cycle_in_tick = 0;
    const int was_logging = m->log_on;
    m->log_on = 0;
    uint32_t boot = 0;
    while (!(m->ram[YMM_STATUS] & 0x80)) {
        run(m, 256, 0, 0);
        if ((boot += 256) > 200000) return -1;
    }
    m->log_on = was_logging;
    /* Output starts here: the blip buffers hold no history, the chips' present output is their zero. */
    for (int k = 0; k < 2; ++k) blip_clear(m->blip[k]);
    const uint32_t null = YMM_NULL_SAMPLE;
    for (int i = 0; i < 64; ++i) { /* entry 0: the null sample; ids 1..63: silent (the null sample) */
        m->ram[YMM_ID_TABLE + 4 * i + 0] = (uint8_t)(null >> 8);
        m->ram[YMM_ID_TABLE + 4 * i + 1] = (uint8_t)(null >> 16);
        m->ram[YMM_ID_TABLE + 4 * i + 2] = 0x01;
        m->ram[YMM_ID_TABLE + 4 * i + 3] = 0x00;
    }
    const uint32_t music = YMM_RING;
    for (int i = 0; i < 4; ++i) m->ram[YMM_PARAMS + i] = (uint8_t)(music >> (8 * i));
    m->ram[YMM_COMMAND] = (uint8_t)((m->ram[YMM_COMMAND] & 0x0F) | 0x40);
    m->ram[YMM_PENDING_FRM] = 0;
    m->ring_pos = 0;
    m->fm_mute = 0;
    m->psg.mute = 0;
    return 0;
}

/* The 68000's vertical interrupt (setNextXFrame): PENDING_FRM += 1 once the Z80 is not modifying it. Returns the
 * Z80 cycles spent waiting (the first cycles of the tick). */
static uint32_t vint(YmMachine* m) {
    uint32_t waited = 0;
    while (m->ram[YMM_MODIFYING_F] && waited < 4096) {
        run(m, 1, 1, waited);
        ++waited;
    }
    m->ram[YMM_PENDING_FRM] = (uint8_t)(m->ram[YMM_PENDING_FRM] + 1);
    return waited;
}

/* Appends XGC bytes to the ring; wraps with a frame-skip + loop frame (and the driver's loop counter reset). */
static void ring_append(YmMachine* m, const uint8_t* bytes, size_t n) {
    if (m->ring_pos + n + 6 > YMM_RING_SIZE) {
        static const uint8_t wrap[6] = {0x06, YMX_XGC_FRAME_SKIP, YMX_XGC_LOOP, 0, 0, 0};
        memcpy(m->ring + m->ring_pos, wrap, sizeof wrap);
        m->ring_pos = 0;
        m->ram[YMM_REM_LOOP] = 0;
    }
    memcpy(m->ring + m->ring_pos, bytes, n);
    m->last_frames_at = (int)m->ring_pos;
    m->last_frames_size = (int)n;
    m->ring_pos += (uint32_t)n;
}

/* Runs the player one tick (the prefix first: machine reset writes, a reset or key-off batch) and streams its
 * XGC frames. Returns 0 or -1 (encoder overflow). */
static int encode_tick(YmMachine* m) {
    YmPlayer* p = &m->player;
    int* pos = m->fifo[m->encoded % FIFO];
    pos[0] = p->order;
    pos[1] = p->row;
    pos[2] = p->tick;
    int n = m->nprefix, loop_at = -1;
    memcpy(m->frame, m->prefix, sizeof m->prefix[0] * (size_t)n);
    m->nprefix = 0;
    if (!m->audition && p->order == m->image.info.loop && p->row == 0 && p->tick == 0) {
        /* The loop frame, as the export has it: the register dump, and xgmtool's loop start (every register) */
        loop_at = n;
        n += ymp_dump(p, m->frame + n);
    }
    ymp_tick(p, &m->image);
    memcpy(m->frame + n, p->write, sizeof p->write[0] * (size_t)p->writes);
    n += p->writes;
    size_t xgc_size = 0;
    if (!ymx_frame(&m->enc, m->frame, n, loop_at, NULL, m->xgc, sizeof m->xgc, &xgc_size)) return -1;
    ring_append(m, m->xgc, xgc_size);
    ++m->encoded;
    return 0;
}

/* Player ticks the driver has executed completely: all encoded ones but the queued frame and the vertical
 * interrupts it has not processed yet (heavy frames make it lag). */
static uint32_t executed(const YmMachine* m) {
    const uint32_t behind = 1u + m->ram[YMM_PENDING_FRM];
    return m->encoded > behind ? m->encoded - behind : 0;
}

/* The prefix the next frame starts with: `n` writes. */
static void add_prefix(YmMachine* m, const YmWrite* w, int n) {
    if (m->nprefix + n > PREFIX_MAX) n = PREFIX_MAX - m->nprefix;
    memcpy(m->prefix + m->nprefix, w, sizeof w[0] * (size_t)n);
    m->nprefix += n;
}

/* One vertical interrupt's worth of Z80 and chips into the blip buffers. */
static void run_tick(YmMachine* m) {
    m->nlog = 0;
    m->cycle_in_tick = 0;
    const uint32_t cycles = tick_cycles(m);
    const uint32_t waited = vint(m);
    run(m, cycles > waited ? cycles - waited : 0, 1, waited);
    m->last_tick_cycles = cycles;
    for (int k = 0; k < 2; ++k) blip_end_frame(m->blip[k], cycles);
}

/* After the player is in place: the first frame is encoded and the driver takes the play command and prefetches it
 * in one tick (the caller discards its output). */
static int begin_stream(YmMachine* m) {
    m->encoded = 0;
    if (encode_tick(m)) return -1;
    run_tick(m);
    return 0;
}

/* ---- life cycle ----------------------------------------------------------------------------------- */

YmMachine* ymm_create(uint32_t sample_rate) {
    if (sample_rate < 8000 || sample_rate > YMM_MAX_SAMPLE_RATE) return NULL;
    YmMachine* m = (YmMachine*)calloc(1, sizeof *m);
    if (!m) return NULL;
    m->rate = sample_rate;
    m->opn = ymo_create();
    for (int i = 0; i < 2; ++i) m->blip[i] = blip_new((int)YMM_MAX_TICK_FRAMES + 64);
    if (!m->opn || !m->blip[0] || !m->blip[1] || machine_reset(m, YM_NTSC)) {
        ymm_destroy(m);
        return NULL;
    }
    return m;
}

void ymm_destroy(YmMachine* m) {
    if (!m) return;
    for (int i = 0; i < 2; ++i)
        if (m->blip[i]) blip_delete(m->blip[i]);
    if (m->opn) ymo_destroy(m->opn);
    free(m);
}

/* ---- start and seek ------------------------------------------------------------------------------- */

static int at(const YmPlayer* p, int order, int row) { return p->order == order && p->row == row && p->tick == 0; }

/* The player from the song start (or at the order's row 0 with its start speed: from_order) for `ticks` ticks, or
 * until the next tick starts (order, row) when ticks < 0 (at most `bound` ticks, never leaving the order
 * from_order); the encoder absorbs every frame (the first with the machine reset writes) when `feed`. Returns the
 * ticks run, or -1 if (order, row) was not reached. */
static int run_player(YmMachine* m, int from_order, int order, int row, int ticks, int bound, int feed) {
    YmPlayer* p = &m->player;
    if (from_order) ymp_place(p, &m->image, order);
    else ymp_reset(p, &m->image);
    ymx_reset(&m->enc);
    m->nprefix = 0;
    YmWrite reset[YMP_MACHINE_RESET_WRITES];
    add_prefix(m, reset, ymp_machine_reset(reset));
    add_prefix(m, p->write, p->writes);
    int n = 0;
    for (; ticks < 0 ? !at(p, order, row) && n < bound && (!from_order || p->order == order) : n < ticks; ++n) {
        ymp_tick(p, &m->image);
        if (feed) {
            int k = m->nprefix;
            memcpy(m->frame, m->prefix, sizeof m->prefix[0] * (size_t)k);
            memcpy(m->frame + k, p->write, sizeof p->write[0] * (size_t)p->writes);
            ymx_absorb(&m->enc, m->frame, k + p->writes);
        }
        m->nprefix = 0;
    }
    return ticks >= 0 || at(p, order, row) ? n : -1;
}

/* The player's register state onto the chips (the driver has booted; the encoder state already matches): its
 * dump, then key-on for the FM channels it has keyed. */
static void restore(YmMachine* m) {
    YmWrite dump[YMP_DUMP_WRITES + YM_FM_CHANNELS];
    int n = ymp_dump(&m->player, dump);
    for (int c = 0; c < YM_FM_CHANNELS; ++c)
        if (m->player.ch[c].keyed && !m->player.ch[c].key_pending)
            dump[n++] = (YmWrite){YM_W_PORT0, 0x28, (uint8_t)(0xF0 | ((c / 3) * 4 + c % 3))};
    write_chips(m, dump, n);
}

static void discard_output(YmMachine* m) {
    for (int k = 0; k < 2; ++k) blip_read_samples(m->blip[k], m->pcm, (int)YMM_MAX_TICK_FRAMES, 0);
}

static int stream_tick(YmMachine* m);

int ymm_start(YmMachine* m, const YmImage* image, int order, int row, int max_ticks, int* seek_mode) {
    if (seek_mode) *seek_mode = YMM_SEEK_FROM_START;
    if (image->info.loaded != YM_LOADED || order < 0 || order >= image->info.orders || row < 0 ||
        row >= image->info.rows[order])
        return YMM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    m->loaded = 0;
    m->audition = m->released = m->release_ticks = 0;
    if (machine_reset(m, image->info.region)) return YMM_ERR_FAULT;
    int from_order = 0;
    int ticks = run_player(m, 0, order, row, -1, max_ticks, 0);
    if (ticks < 0) {
        /* Replaying the order's earlier rows takes < 256 rows x 255 ticks: its own bound instead of max_ticks. A
         * row behind a Dxx/Bxx of the same order is unreachable. */
        from_order = 1;
        if (seek_mode) *seek_mode = YMM_SEEK_FROM_ORDER;
        ticks = run_player(m, 1, order, row, -1, 256 * 256, 0);
        if (ticks < 0) return YMM_ERR_IMAGE;
    }
    /* The player and encoder run alone until YMM_PREROLL_TICKS before the target; the chips then receive the
     * player's state and the rest streams through the driver (output discarded), so held notes and release tails
     * arrive under way. From the song start the stream is straight play's. */
    const int silent = ticks > YMM_PREROLL_TICKS ? ticks - YMM_PREROLL_TICKS : 0;
    run_player(m, from_order, order, row, silent, 0, 1);
    if (silent) restore(m);
    if (begin_stream(m)) return YMM_ERR_FAULT;
    discard_output(m);
    m->loaded = 1;
    for (int t = silent; t < ticks; ++t) {
        if (stream_tick(m) < 0) {
            m->loaded = 0;
            return YMM_ERR_FAULT;
        }
        discard_output(m);
    }
    m->ticks = 0;
    return YMM_OK;
}

void ymm_apply_image(YmMachine* m, const YmImage* image) {
    if (!m->loaded || m->audition || image->info.loaded != YM_LOADED) return;
    ymp_relocate(&m->player, &m->image, image);
    memcpy(&m->image, image, sizeof m->image);
}

int ymm_audition(YmMachine* m, const YmImage* image, int channel, int note, int slot, int fine) {
    if (image->info.loaded != YM_LOADED) return YMM_ERR_IMAGE;
    memcpy(&m->image, image, sizeof m->image);
    m->loaded = 0;
    m->released = m->release_ticks = 0;
    if (machine_reset(m, image->info.region)) return YMM_ERR_FAULT;
    if (ymp_audition(&m->player, &m->image, channel, note, slot, fine)) return YMM_ERR_IMAGE;
    /* The chips hold the reset state already: the stream starts with the audition batch alone (a light frame the
     * driver executes at once), the encoder knowing the reset registers. */
    ymx_reset(&m->enc);
    YmWrite reset[YMP_MACHINE_RESET_WRITES];
    ymx_absorb(&m->enc, reset, ymp_machine_reset(reset));
    m->nprefix = 0;
    add_prefix(m, m->player.write, m->player.writes);
    m->audition = 1;
    m->audition_channel = channel;
    if (begin_stream(m)) return YMM_ERR_FAULT;
    discard_output(m);
    m->loaded = 1;
    m->ticks = 0;
    return YMM_OK;
}

/* The audition note is silent on the chips: FM every carrier's envelope at the bottom, PSG the attenuation 15 (and
 * the envelope off). */
static int audition_silent(const YmMachine* m) {
    const int c = m->audition_channel;
    const YmChannel* ch = &m->player.ch[c];
    if (c < YM_FM_CHANNELS) {
        for (int k = 0; k < 4; ++k)
            if ((ch->carriers >> k & 1) && ymo_envelope(m->opn, c, k) < 0x3FF) return 0;
        return 1;
    }
    const int i = c == YM_CH_NOISE ? 3 : c - YM_CH_PSG;
    return ch->phase == YM_PSG_OFF && m->psg.att[i] == 15;
}

int ymm_release(YmMachine* m) {
    if (!m->loaded || !m->audition) return 0;
    if (!m->released && (!m->ticks || audition_silent(m))) { /* a cut before any tick, or already silent */
        m->loaded = 0;
        return 0;
    }
    if (!m->released) {
        ymp_key_off(&m->player, m->audition_channel);
        add_prefix(m, m->player.write, m->player.writes);
        m->released = 1;
        m->release_ticks = 0;
        m->keyoff_tick = m->encoded; /* the next frame encoded carries the key-off */
    }
    return 1;
}

int ymm_mute(YmMachine* m, int channel, int muted) {
    if (channel < 0 || channel >= YM_CHANNELS) return YMM_ERR_IMAGE;
    if (channel < YM_FM_CHANNELS) {
        if (muted) m->fm_mute |= 1u << channel;
        else m->fm_mute &= ~(1u << channel);
    } else {
        const unsigned bit = 1u << (channel - YM_CH_PSG);
        if (muted) m->psg.mute |= bit;
        else m->psg.mute &= ~bit;
        m->psg_written = 1; /* the output changes at the next step */
    }
    return YMM_OK;
}

void ymm_position(const YmMachine* m, int* order, int* row, int* tick) {
    uint32_t next = executed(m);
    if (m->encoded - next > FIFO) next = m->encoded - FIFO;
    const int* pos = m->fifo[next % FIFO];
    *order = pos[0];
    *row = pos[1];
    *tick = pos[2];
}

/* One engine tick: the player's next frame is encoded and streamed, then one vertical interrupt's worth of Z80 and
 * chips run (the driver executes the frame queued the tick before). Returns the frames now in the blip buffers. */
static int stream_tick(YmMachine* m) {
    if (encode_tick(m)) return -1;
    run_tick(m);
    if (!(m->ram[YMM_STATUS] & 0x40)) return -1; /* the driver stopped playing: a fault */
    return 0;
}

int ymm_tick(YmMachine* m, float* out) {
    if (!m->loaded) return 0;
    if (m->released) {
        const int limit = YMM_RELEASE_SECONDS * (m->region == YM_PAL ? 50 : 60);
        if ((executed(m) > m->keyoff_tick && audition_silent(m)) || m->release_ticks >= limit) {
            m->loaded = 0;
            return 0;
        }
        ++m->release_ticks;
    }
    if (stream_tick(m) < 0) {
        m->loaded = 0;
        return YMM_ERR_FAULT;
    }
    int frames = 0;
    for (int k = 0; k < 2; ++k) frames = blip_read_samples(m->blip[k], m->pcm + k, (int)YMM_MAX_TICK_FRAMES, 1);
    for (int i = 0; i < 2 * frames; ++i) out[i] = m->pcm[i] * (1.0f / 32768.0f);
    ++m->ticks;
    return frames;
}

const YmPlayer* ymm_player(const YmMachine* m) { return &m->player; }
uint32_t ymm_ticks(const YmMachine* m) { return m->ticks; }
const uint8_t* ymm_last_frames(const YmMachine* m, int* size) {
    *size = m->last_frames_size;
    return m->ring + m->last_frames_at;
}
void ymm_set_log(YmMachine* m, int enabled) { m->log_on = enabled != 0; }
const YmmLogEntry* ymm_log(const YmMachine* m, int* count) {
    *count = m->nlog;
    return m->log;
}
const uint8_t* ymm_z80_ram(const YmMachine* m) { return m->ram; }
uint32_t ymm_z80_cycles_per_tick(const YmMachine* m) { return m->last_tick_cycles; }
int ymm_lag(const YmMachine* m) { return m->ram[YMM_PENDING_FRM]; }
