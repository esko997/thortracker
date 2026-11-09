/* Master System music machine (sms_machine.h): the Banjo driver on z80.h with the SN76489 (ym_psg.c), the YM2413
 * (emu2413) and blip_buf. */
#include "sms_machine.h"

#include <stdlib.h>
#include <string.h>

#include "blip_buf.h"
#include "generated/banjo_driver_image.h"
#include "chip_opll.h"
#include "z80.h"

#define PSG_DIV 16u               /* Z80 cycles per SN76489 counter step (clock / 16) */
#define OPLL_DIV 72u              /* Z80 cycles per YM2413 sample (clock / 72 = 49716 Hz) */
#define OPLL_RATE 49716u
#define INIT_BUDGET 200000u       /* banjo_init / banjo_play_song */
#define OUTPUT_GAIN 2.5f
#define HIGHPASS_HZ 20.0f
#define QUIET_LEVEL 0.0001f       /* a released audition is over after 3 ticks below this peak */

/* Mix (before the output gain, / 32768): a full-level PSG square (level 4096) and a full-level YM2413 melodic channel
 * (emu2413 about ±2048) peak alike; docs/AUDIO_ENGINE.md, "Master System: mix". */
#define PSG_MIX(v) ((v) * 3 / 8)
#define FM_MIX(v) ((v) * 3 / 4)

_Static_assert(BANJO_DRIVER_SIZE == SMS_REGION_BASE, "the driver bank lies below the song region");
_Static_assert(SMS_REGION_BASE + SMS_REGION_SIZE == 0xC000, "the song region ends where RAM starts");
_Static_assert(SMSM_MAX_SAMPLE_RATE * (uint64_t)SMSM_CYCLES_PER_TICK / SMSM_CLOCK + 3 <= SMSM_MAX_TICK_FRAMES, "a tick fits");
_Static_assert(SMS_AUDITION_RAM >= 0xC800 && SMS_STACK_TOP < 0xE000, "audition song and stack in RAM");

enum { MODE_SILENT = 0, MODE_CLOCKED = 1, MODE_EMIT = 2 };


struct SmsMachine {
    uint32_t rate;
    z80_t cpu;
    uint8_t ram[0x2000];               /* $C000-$DFFF */
    uint8_t region[SMS_REGION_SIZE];   /* $4000-$BFFF: the playing song, call fields filled */
    SmsImageInfo info;
    int loaded;
    /* chips */
    YmPsg psg;
    OPLL* opll;
    uint8_t opll_reg[0x40];
    uint8_t opll_addr, pan;
    uint32_t fm_mask;
    int fm_out;
    uint32_t psg_wait, opll_wait;      /* cycles to the next PSG step / YM2413 sample */
    /* output */
    blip_t* blip[2];
    int stereo;
    int last_l, last_r;
    float hp_a, hp_in[2], hp_out[2];
    short pcm[2][SMSM_MAX_TICK_FRAMES + 8];
    /* timing */
    int mode;
    uint32_t cycle;                    /* Z80 cycles since the tick (or reset) began */
    uint32_t chip_t;                   /* the chips have run up to this cycle */
    uint32_t last_call;
    /* song state */
    int faulted;
    uint32_t ticks;
    int order, row;                    /* row most recently started (-1: none yet in `order`) */
    uint32_t row_ticks;
    int audition, aud_channel, released, ended, quiet;
    uint32_t release_ticks;
    int custom_ins;                    /* the custom FM voice the YM2413 holds (instrument index), -1 none */
    uint8_t last_ins[SMS_MAX_CHANNELS];
    /* test hooks */
    int log_on, nlog;
    SmsmWrite log[SMSM_LOG_MAX];
};

#define RAM(m, addr) ((m)->ram[((addr) - 0xC000) & 0x1FFF])

static uint8_t* state(SmsMachine* m) { return &RAM(m, BANJO_SONG_STATE); }
static const uint8_t* cstate(const SmsMachine* m) { return &m->ram[(BANJO_SONG_STATE - 0xC000) & 0x1FFF]; }
static uint8_t* chan(SmsMachine* m, int c) { return &RAM(m, BANJO_SONG_CHANNELS + BJC_SIZE * c); }

static void wr16(uint8_t* p, uint16_t v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
}

/* ---- bus ----------------------------------------------------------------------------------------------------- */

static uint8_t mem_read(const SmsMachine* m, uint16_t a) {
    if (a < SMS_REGION_BASE) return BANJO_DRIVER[a];
    if (a < 0xC000) return m->region[a - SMS_REGION_BASE];
    return m->ram[a & 0x1FFF];
}

static void mix(SmsMachine* m, uint32_t t) {
    if (m->mode != MODE_EMIT) return;
    if (t >= SMSM_CYCLES_PER_TICK) t = SMSM_CYCLES_PER_TICK - 1;
    int l, r;
    if (m->stereo) {
        ymps_output_pan(&m->psg, m->pan, &l, &r);
    } else {
        l = r = ymps_output(&m->psg);
    }
    l = PSG_MIX(l) + FM_MIX(m->fm_out);
    r = PSG_MIX(r) + FM_MIX(m->fm_out);
    if (l != m->last_l) {
        blip_add_delta(m->blip[0], t, l - m->last_l);
        m->last_l = l;
    }
    if (m->stereo && r != m->last_r) {
        blip_add_delta(m->blip[1], t, r - m->last_r);
        m->last_r = r;
    }
}

/* The chips run up to cycle `target` of the tick (silent mode: their registers only). */
static void chips_to(SmsMachine* m, uint32_t target) {
    if (target <= m->chip_t) return;
    if (m->mode == MODE_SILENT) {
        m->chip_t = target;
        return;
    }
    const int fm = m->info.layout >= SMS_LAYOUT_FM;
    uint32_t t = m->chip_t;
    while (t < target) {
        uint32_t step = m->psg_wait;
        if (fm && m->opll_wait < step) step = m->opll_wait;
        if (target - t < step) step = target - t;
        t += step;
        m->psg_wait -= step;
        if (fm) m->opll_wait -= step;
        int changed = 0;
        if (!m->psg_wait) {
            m->psg_wait = PSG_DIV;
            changed |= ymps_step(&m->psg);
        }
        if (fm && !m->opll_wait) {
            m->opll_wait = OPLL_DIV;
            const int v = OPLL_calc(m->opll);
            if (v != m->fm_out) {
                m->fm_out = v;
                changed = 1;
            }
        }
        if (changed) mix(m, t);
    }
    m->chip_t = target;
}

static void chip_write(SmsMachine* m, int kind, uint8_t reg, uint8_t val) {
    chips_to(m, m->cycle);
    switch (kind) {
    case SMSM_W_PSG: ymps_write(&m->psg, val); break;
    case SMSM_W_OPLL:
        m->opll_reg[reg & 0x3F] = val;
        if (m->mode != MODE_SILENT) OPLL_writeReg(m->opll, reg, val);
        break;
    default: m->pan = val; break;
    }
    mix(m, m->cycle);
    if (m->log_on && m->nlog < SMSM_LOG_MAX) {
        SmsmWrite* w = &m->log[m->nlog++];
        w->cycle = m->cycle;
        w->kind = (uint8_t)kind;
        w->reg = reg;
        w->val = val;
    }
}

static void io_write(SmsMachine* m, uint8_t port, uint8_t v) {
    if ((port & 0xC0) == 0x40) {
        chip_write(m, SMSM_W_PSG, 0, v);
    } else if (port == 0xF0) {
        m->opll_addr = v;
    } else if (port == 0xF1) {
        if (m->info.layout >= SMS_LAYOUT_FM) chip_write(m, SMSM_W_OPLL, m->opll_addr, v);
    } else if (port == 0x06) {
        if (m->stereo) chip_write(m, SMSM_W_PAN, 0, v);
    }
    /* $F2 (audio control: which chips are heard) is fixed by the layout */
}

static uint64_t bus(SmsMachine* m, uint64_t pins) {
    if (pins & Z80_MREQ) {
        const uint16_t a = Z80_GET_ADDR(pins);
        if (pins & Z80_RD) {
            Z80_SET_DATA(pins, mem_read(m, a));
        } else if ((pins & Z80_WR) && a >= 0xC000) {
            m->ram[a & 0x1FFF] = Z80_GET_DATA(pins);
        }
    } else if ((pins & Z80_IORQ) && !(pins & Z80_M1)) {
        if (pins & Z80_WR) io_write(m, (uint8_t)Z80_GET_ADDR(pins), Z80_GET_DATA(pins));
        else if (pins & Z80_RD) Z80_SET_DATA(pins, 0xFF);
    }
    return pins;
}

/* Calls the driver routine at `addr` (A, HL, D as given) until it returns to SMS_RETURN_TRAP; at most `budget`
 * cycles, every opcode fetched from the driver bank. */
static int call(SmsMachine* m, uint16_t addr, uint8_t a, uint16_t hl, uint8_t d, uint32_t budget) {
    if (m->faulted) return SMSM_ERR_FAULT;
    z80_t* c = &m->cpu;
    const uint16_t sp = SMS_STACK_TOP - 2;
    wr16(&RAM(m, sp), SMS_RETURN_TRAP);
    c->sp = sp;
    c->af = (uint16_t)(a << 8);
    c->hl = hl;
    c->d = d;
    c->iff1 = c->iff2 = false;
    uint64_t pins = z80_prefetch(c, addr);
    uint32_t n = 0;
    for (;;) {
        pins = z80_tick(c, pins);
        if ((pins & (Z80_M1 | Z80_MREQ | Z80_RD)) == (Z80_M1 | Z80_MREQ | Z80_RD)) {
            const uint16_t pc = Z80_GET_ADDR(pins);
            if (pc == SMS_RETURN_TRAP) break;
            if (pc >= BANJO_DRIVER_SIZE) goto fault;
        }
        pins = bus(m, pins);
        ++m->cycle;
        if (++n > budget) goto fault;
    }
    m->last_call = n;
    return SMSM_OK;
fault:
    m->faulted = 1;
    return SMSM_ERR_FAULT;
}

/* ---- lifecycle ----------------------------------------------------------------------------------------------- */

SmsMachine* smsm_create(uint32_t sample_rate) {
    if (sample_rate < 8000 || sample_rate > SMSM_MAX_SAMPLE_RATE) return NULL;
    SmsMachine* m = (SmsMachine*)calloc(1, sizeof *m);
    if (!m) return NULL;
    m->rate = sample_rate;
    for (int k = 0; k < 2; ++k) {
        m->blip[k] = blip_new((int)(sample_rate / 10));
        if (!m->blip[k]) goto fail;
        blip_set_rates(m->blip[k], (double)SMSM_CLOCK, (double)sample_rate);
    }
    m->opll = chip_opll_new(SMSM_CLOCK, OPLL_RATE);
    if (!m->opll) goto fail;
    const float dt = 1.0f / (float)sample_rate;
    const float rc = 1.0f / (2.0f * 3.14159265f * HIGHPASS_HZ);
    m->hp_a = rc / (rc + dt);
    m->faulted = 1; /* until a song or an audition starts */
    return m;
fail:
    smsm_destroy(m);
    return NULL;
}

void smsm_destroy(SmsMachine* m) {
    if (!m) return;
    for (int k = 0; k < 2; ++k)
        if (m->blip[k]) blip_delete(m->blip[k]);
    if (m->opll) OPLL_delete(m->opll);
    free(m);
}

static int loaded(const SmsImage* image) { return image && image->info.loaded == SMS_LOADED; }

/* The driver routines a song of `layout` runs: init, update, channel mute table, song mute (json2sms's for the chips;
 * Thor Tracker's host routines for two chips), and banjo_song_stop. */
static void fill_calls(uint8_t* header, int layout) {
    uint16_t init, update, mutes, mute_all;
    switch (layout) {
    case SMS_LAYOUT_FM:
        init = BANJO_THOR_INIT_SN_OPLL;
        update = BANJO_THOR_UPDATE_SN_OPLL;
        mutes = BANJO_THOR_MUTE_CALLS_SN_OPLL;
        mute_all = BANJO_THOR_MUTE_ALL_SN_OPLL;
        break;
    case SMS_LAYOUT_FM_DRUMS:
        init = BANJO_THOR_INIT_SN_DRUMS;
        update = BANJO_THOR_UPDATE_SN_DRUMS;
        mutes = BANJO_THOR_MUTE_CALLS_SN_DRUMS;
        mute_all = BANJO_THOR_MUTE_ALL_SN_DRUMS;
        break;
    default:
        init = BANJO_THOR_INIT_SN;
        update = BANJO_UPDATE_CHANNELS_SN;
        mutes = BANJO_THOR_MUTE_CALLS_SN;
        mute_all = BANJO_MUTE_ALL_SN;
        break;
    }
    wr16(header + BJ_CHANNEL_INIT_CALL, init);
    wr16(header + BJ_CHANNEL_UPDATE_CALL, update);
    wr16(header + BJ_CHANNEL_MUTE_CALLS, mutes);
    wr16(header + BJ_SONG_MUTE, mute_all);
    wr16(header + BJ_SONG_STOP, BANJO_SONG_STOP);
}

/* Current output as the reference level: a following tick starts without a step. */
static void output_start(SmsMachine* m) {
    for (int k = 0; k < 2; ++k) {
        blip_clear(m->blip[k]);
        m->hp_in[k] = m->hp_out[k] = 0.0f;
    }
    int l, r;
    if (m->stereo) {
        ymps_output_pan(&m->psg, m->pan, &l, &r);
    } else {
        l = r = ymps_output(&m->psg);
    }
    m->last_l = PSG_MIX(l) + FM_MIX(m->fm_out);
    m->last_r = PSG_MIX(r) + FM_MIX(m->fm_out);
    m->ticks = 0;
}

/* Power on with `image`'s region; the chips reset. */
static void power_on(SmsMachine* m, const SmsImage* image) {
    memset(m->ram, 0, sizeof m->ram);
    memcpy(m->region, sms_region(image), SMS_REGION_SIZE);
    m->info = image->info;
    fill_calls(m->region, m->info.layout);
    m->loaded = 1;
    m->stereo = m->info.layout == SMS_LAYOUT_GG;
    ymps_reset(&m->psg);
    m->psg.mute = 0;
    OPLL_reset(m->opll);
    m->fm_mask = 0;
    OPLL_setMask(m->opll, 0);
    memset(m->opll_reg, 0, sizeof m->opll_reg);
    m->opll_addr = 0;
    m->pan = 0xFF;
    m->fm_out = 0;
    m->psg_wait = PSG_DIV;
    m->opll_wait = OPLL_DIV;
    m->mode = MODE_SILENT;
    m->cycle = m->chip_t = 0;
    m->nlog = 0;
    m->faulted = 0;
    m->ticks = 0;
    m->order = 0;
    m->row = -1;
    m->row_ticks = 0;
    m->audition = m->released = m->ended = m->quiet = 0;
    m->release_ticks = 0;
    m->custom_ins = -1;
    memset(m->last_ins, 0xFF, sizeof m->last_ins);
    z80_init(&m->cpu);
}

/* banjo_init for the layout's chips, the Game Gear flag (banjo_check_hardware's result on a Game Gear), then
 * banjo_play_song of the song at `song` (looping). */
static int start_driver(SmsMachine* m, uint16_t song) {
    const uint8_t chips = m->info.layout >= SMS_LAYOUT_FM ? 0x03 : 0x01; /* BANJO_HAS_SN | BANJO_HAS_OPLL */
    if (call(m, BANJO_INIT, SMS_MAX_CHANNELS, chips, 0, INIT_BUDGET) != SMSM_OK) return SMSM_ERR_FAULT;
    RAM(m, BANJO_SYSTEM_FLAGS) = m->stereo ? 0x01 : 0x00; /* SYS_FLAG_GG */
    if (call(m, BANJO_PLAY_SONG, 0, song, 1, INIT_BUDGET) != SMSM_OK) return SMSM_ERR_FAULT;
    if (!RAM(m, BANJO_SONG_PLAYING)) {
        m->faulted = 1;
        return SMSM_ERR_FAULT;
    }
    return SMSM_OK;
}

/* ---- positions ----------------------------------------------------------------------------------------------- */

static int playing(const SmsMachine* m) { return !m->faulted && m->ram[(BANJO_SONG_PLAYING - 0xC000) & 0x1FFF]; }

/* The next tick starts a row: the song was just started or placed (process-new-line set), or the row's last tick. */
static int row_starts(const SmsMachine* m) {
    if (m->audition || !playing(m)) return 0;
    const uint8_t* s = cstate(m);
    return (s[BJ_FLAGS] & BJ_STATE_PROCESS_NEW_LINE) || s[BJ_TIC] == 1;
}

void smsm_position(const SmsMachine* m, int* order, int* row, int* tick) {
    if (!m->audition && playing(m)) {
        const uint8_t* s = cstate(m);
        const int line = s[BJ_LINE], length = s[BJ_PATTERN_LENGTH];
        if (s[BJ_FLAGS] & BJ_STATE_PROCESS_NEW_LINE) {
            *order = s[BJ_ORDER];
            *row = length - line;
            *tick = 0;
            return;
        }
        if (s[BJ_TIC] == 1) {
            if (s[BJ_FLAGS] & BJ_STATE_ORDER_JUMP) {
                *order = s[BJ_ORDER_JUMP_TARGET];
                *row = 0;
            } else if (line == 1) {
                const int next = s[BJ_ORDER] + 1;
                *order = next >= s[BJ_ORDERS_LENGTH] ? 0 : next;
                *row = 0;
            } else {
                *order = s[BJ_ORDER];
                *row = length - line + 1;
            }
            *tick = 0;
            return;
        }
    }
    *order = m->order;
    *row = m->row < 0 ? 0 : m->row;
    *tick = m->row_ticks > 0x7FFFFFFF ? 0x7FFFFFFF : (int)m->row_ticks;
}

static int at(const SmsMachine* m, int order, int row) {
    int o, r, t;
    smsm_position(m, &o, &r, &t);
    return o == order && r == row && t == 0;
}

/* ---- ticks --------------------------------------------------------------------------------------------------- */

/* The custom FM voice the chip holds: the last custom instrument an FM channel changed to. */
static void track_custom(SmsMachine* m) {
    if (m->info.layout < SMS_LAYOUT_FM) return;
    for (int c = 0; c < m->info.channels; ++c) {
        if (m->info.kind[c] != SMS_KIND_OPLL) continue;
        const uint8_t ins = chan(m, c)[BJC_INSTRUMENT_NUM];
        if (ins == m->last_ins[c]) continue;
        m->last_ins[c] = ins;
        if (ins < m->info.instruments) {
            const uint8_t* rec = m->region + (m->info.instrument_table - SMS_REGION_BASE) + BJI_SIZE * ins;
            if (rec[BJI_FM_PRESET] == BJ_FM_CUSTOM) m->custom_ins = ins;
        }
    }
}

static int run_tick(SmsMachine* m, int mode) {
    if (m->faulted) return SMSM_ERR_FAULT;
    m->mode = mode;
    m->cycle = m->chip_t = 0;
    if (m->log_on) m->nlog = 0;
    const int starts = row_starts(m);
    if (call(m, BANJO_UPDATE_SONG, 0, 0, 0, SMSM_CYCLES_PER_TICK) != SMSM_OK) return SMSM_ERR_FAULT;
    chips_to(m, SMSM_CYCLES_PER_TICK);
    if (mode == MODE_EMIT) {
        blip_end_frame(m->blip[0], SMSM_CYCLES_PER_TICK);
        if (m->stereo) blip_end_frame(m->blip[1], SMSM_CYCLES_PER_TICK);
    }
    if (starts) {
        const uint8_t* s = cstate(m);
        m->order = s[BJ_ORDER];
        m->row = s[BJ_PATTERN_LENGTH] - s[BJ_LINE];
        m->row_ticks = 1;
    } else {
        ++m->row_ticks;
    }
    track_custom(m);
    return SMSM_OK;
}

int smsm_tick_silent(SmsMachine* m) {
    const int r = run_tick(m, MODE_SILENT);
    if (r == SMSM_OK) ++m->ticks;
    return r;
}

int smsm_tick(SmsMachine* m, float* out) {
    if (m->ended) return 0;
    if (run_tick(m, MODE_EMIT) != SMSM_OK) return SMSM_ERR_FAULT;
    int n = blip_samples_avail(m->blip[0]);
    if (n > (int)SMSM_MAX_TICK_FRAMES) n = (int)SMSM_MAX_TICK_FRAMES;
    n = blip_read_samples(m->blip[0], m->pcm[0], n, 0);
    if (m->stereo) {
        const int n2 = blip_read_samples(m->blip[1], m->pcm[1], n, 0);
        if (n2 < n) memset(m->pcm[1] + n2, 0, sizeof(short) * (size_t)(n - n2));
    }
    float peak = 0.0f;
    for (int i = 0; i < n; ++i) {
        for (int k = 0; k < 2; ++k) {
            const float x = (float)(m->stereo ? m->pcm[k][i] : m->pcm[0][i]) * (1.0f / 32768.0f);
            m->hp_out[k] = m->hp_a * (m->hp_out[k] + x - m->hp_in[k]);
            m->hp_in[k] = x;
            const float y = m->hp_out[k] * OUTPUT_GAIN;
            out[2 * i + k] = y;
            const float a = y < 0.0f ? -y : y;
            if (a > peak) peak = a;
        }
    }
    ++m->ticks;
    if (m->released) {
        ++m->release_ticks;
        m->quiet = peak < QUIET_LEVEL ? m->quiet + 1 : 0;
        if (m->quiet >= 3 || m->release_ticks >= SMSM_RELEASE_SECONDS * 60u) m->ended = 1;
    }
    return n;
}

/* ---- seeking ------------------------------------------------------------------------------------------------- */


/* Replay to the next tick at (order, row), at most `budget`. Clock every oscillator and envelope without
 * generating output; replaying only the final second would restart held FM voices and lose their phase. */
static int seek(SmsMachine* m, int order, int row, int budget) {
    for (int t = 0; !at(m, order, row); ++t) {
        if (t >= budget) return 0;
        if (run_tick(m, MODE_CLOCKED) != SMSM_OK) return SMSM_ERR_FAULT;
    }
    return 1;
}

/* The bounded fallback starts cold, but a later note without an instrument still uses its preceding selection.
 * Scan each preceding order's commands once, in row/channel order, then let Banjo load those selections without
 * notes. The shared custom FM registers can outlive the channel that last wrote them. */
static int carry_instruments(SmsMachine* m, int order) {
    if (order == 0) return SMSM_OK;
    uint8_t instruments[SMS_MAX_CHANNELS];
    memset(instruments, 0xFF, sizeof instruments);
    int custom = -1;
    for (int o = 0; o < order; ++o) {
        uint16_t cursor[SMS_MAX_CHANNELS];
        uint8_t wait[SMS_MAX_CHANNELS] = {0};
        for (int c = 0; c < m->info.channels; ++c) cursor[c] = sms_track(m->region, &m->info, o, c);
        for (int row = 0; row < m->info.rows[o]; ++row) {
            for (int c = 0; c < m->info.channels; ++c) {
                if (wait[c]) { --wait[c]; continue; }
                for (;;) {
                    const uint8_t op = mem_read(m, cursor[c]);
                    if (op >= BJ_END_LINE && op < BJ_NOTE_ON) {
                        wait[c] = op - BJ_END_LINE;
                        ++cursor[c];
                        break;
                    }
                    if (op == BJ_INSTRUMENT_CHANGE || op == BJ_FM_DRUM) {
                        const uint8_t ins = mem_read(m, cursor[c] + 1);
                        if (ins != instruments[c] && m->info.kind[c] == SMS_KIND_OPLL) {
                            const uint8_t* rec = m->region + m->info.instrument_table - SMS_REGION_BASE + BJI_SIZE * ins;
                            if (rec[BJI_FM_PRESET] == BJ_FM_CUSTOM) custom = ins;
                        }
                        instruments[c] = ins;
                    }
                    cursor[c] += sms_command_size(op); /* the loaded image has validated commands and references */
                }
            }
        }
    }
    for (int c = 0; c < m->info.channels; ++c) {
        const uint16_t p = SMS_AUDITION_RAM + 3 * c;
        wr16(chan(m, c) + BJC_PATTERN_PTR, p);
        uint8_t* commands = &RAM(m, p);
        if (instruments[c] != 0xFF) {
            *commands++ = m->info.kind[c] == SMS_KIND_DRUM ? BJ_FM_DRUM : BJ_INSTRUMENT_CHANGE;
            *commands++ = instruments[c];
        }
        *commands = BJ_END_LINE;
    }
    if (run_tick(m, MODE_CLOCKED) != SMSM_OK) return SMSM_ERR_FAULT;
    m->cycle = m->chip_t = 0; /* the initialization tick ended; shared-register restoration is at the next boundary */
    if (custom >= 0) {
        const uint8_t* rec = m->region + m->info.instrument_table - SMS_REGION_BASE + BJI_SIZE * custom;
        const uint16_t patch = sms_rd16(rec + BJI_FM_PATCH);
        for (int r = 0; r < 8; ++r) chip_write(m, SMSM_W_OPLL, (uint8_t)r, mem_read(m, patch + r));
        m->custom_ins = custom;
    }
    return SMSM_OK;
}

/* Every channel at `order`'s start, the speeds straight play enters it with, a row start on the next tick. */
static void place_at_order(SmsMachine* m, int order) {
    uint8_t* s = state(m);
    s[BJ_ORDER] = (uint8_t)order;
    s[BJ_LINE] = (uint8_t)m->info.pattern_length;
    s[BJ_SPEED_1] = m->info.speed1[order];
    s[BJ_SPEED_2] = m->info.speed2[order];
    s[BJ_TIC] = (uint8_t)(m->info.speed1[order] + 1);
    s[BJ_FLAGS] = (uint8_t)((s[BJ_FLAGS] | BJ_STATE_PROCESS_NEW_LINE) & ~BJ_STATE_ORDER_JUMP);
    for (int c = 0; c < m->info.channels; ++c) {
        uint8_t* ch = chan(m, c);
        wr16(ch + BJC_PATTERN_PTR, sms_track(m->region, &m->info, order, c));
        ch[BJC_WAIT] = 0;
        ch[BJC_EVENTS] = (uint8_t)(ch[BJC_EVENTS] & ~BJ_EVENT_TIC_WAIT);
    }
    m->order = order;
    m->row = -1;
    m->row_ticks = 0;
}

int smsm_start(SmsMachine* m, const SmsImage* image, int order, int row, int max_ticks, int* seek_mode) {
    *seek_mode = SMSM_SEEK_FROM_START;
    if (!loaded(image) || order < 0 || row < 0 || order >= image->info.orders || row >= image->info.rows[order]) return SMSM_ERR_IMAGE;
    const int budget = max_ticks < 0 ? 0 : max_ticks;
    power_on(m, image);
    if (start_driver(m, SMS_REGION_BASE) != SMSM_OK) return SMSM_ERR_FAULT;
    int r = seek(m, order, row, budget);
    if (r == 0) {
        power_on(m, image);
        if (start_driver(m, SMS_REGION_BASE) != SMSM_OK) return SMSM_ERR_FAULT;
        if (carry_instruments(m, order) != SMSM_OK) return SMSM_ERR_FAULT;
        place_at_order(m, order);
        *seek_mode = SMSM_SEEK_FROM_ORDER;
        r = seek(m, order, row, budget);
    }
    if (r < 0) return r;
    if (r == 0) return SMSM_ERR_IMAGE;
    output_start(m);
    return SMSM_OK;
}

/* ---- live edits ---------------------------------------------------------------------------------------------- */

/* A channel's instrument fields follow its record in the new data (envelope pointers, flags, the FM preset);
 * envelope positions past a shortened envelope stop it. */
static void refresh_instrument(SmsMachine* m, int c) {
    uint8_t* ch = chan(m, c);
    const int kind = m->info.kind[c];
    if (kind == SMS_KIND_DRUM) return;
    const uint8_t ins = ch[BJC_INSTRUMENT_NUM];
    const uint8_t macros = BJ_CHAN_FLAG_ARP_MACRO | BJ_CHAN_FLAG_VOLUME_MACRO | BJ_CHAN_FLAG_EX_MACRO;
    if (ins == 0xFF) return;
    if (ins >= m->info.instruments) {
        ch[BJC_FLAGS] = (uint8_t)(ch[BJC_FLAGS] & ~macros);
        ch[BJC_INSTRUMENT_NUM] = 0xFF;
        return;
    }
    const uint8_t* rec = m->region + (m->info.instrument_table - SMS_REGION_BASE) + BJI_SIZE * ins;
    ch[BJC_FLAGS] = (uint8_t)((ch[BJC_FLAGS] & ~macros) | (rec[BJI_FLAGS] & macros));
    memcpy(ch + BJC_VOLUME_MACRO_PTR, rec + BJI_VOLUME_MACRO, 2);
    memcpy(ch + BJC_ARP_MACRO_PTR, rec + BJI_ARP_MACRO, 2);
    ch[BJC_EX_MACRO_TYPE] = rec[BJI_EX_MACRO_TYPE];
    memcpy(ch + BJC_EX_MACRO_PTR, rec + BJI_EX_MACRO, 2);
    if (rec[BJI_FLAGS] & BJ_CHAN_FLAG_VOLUME_MACRO) {
        const uint16_t p = sms_rd16(rec + BJI_VOLUME_MACRO);
        const uint8_t len = mem_read(m, (uint16_t)(p + ((ch[BJC_EVENTS] & BJ_EVENT_MACRO_RELEASE) ? 2 : 0)));
        if (ch[BJC_VOLUME_MACRO_POS] >= len) ch[BJC_VOLUME_MACRO_POS] = 0;
    }
    if ((rec[BJI_FLAGS] & BJ_CHAN_FLAG_ARP_MACRO) && ch[BJC_ARP_MACRO_POS] >= mem_read(m, sms_rd16(rec + BJI_ARP_MACRO))) ch[BJC_ARP_MACRO_POS] = 0;
    if ((rec[BJI_FLAGS] & BJ_CHAN_FLAG_EX_MACRO) && ch[BJC_EX_MACRO_POS] >= mem_read(m, sms_rd16(rec + BJI_EX_MACRO))) ch[BJC_EX_MACRO_POS] = 0;
    if (kind == SMS_KIND_OPLL && ch[BJC_GP1] != rec[BJI_FM_PRESET]) {
        ch[BJC_GP1] = rec[BJI_FM_PRESET];
        ch[BJC_EVENTS] |= 0x04; /* CHAN_EVENT_VOLUME_CHANGE: the volume/voice register is rewritten */
    }
}

void smsm_apply_image(SmsMachine* m, const SmsImage* image) {
    if (m->faulted || m->audition || !m->loaded || !loaded(image) || image->info.layout != m->info.layout) return;
    const SmsImageInfo* ni = &image->info;
    const uint8_t* nr = sms_region(image);
    int o = m->order, r = m->row, clamped = 0;
    if (o >= ni->orders) {
        o = ni->orders - 1;
        r = ni->rows[o] - 1;
        clamped = 1;
    } else if (r >= ni->rows[o]) {
        r = ni->rows[o] - 1;
        clamped = 1;
    }
    /* Where each channel continues, decided while the old data is in place. */
    uint16_t ptr[SMS_MAX_CHANNELS];
    uint8_t wait[SMS_MAX_CHANNELS];
    uint8_t delayed[SMS_MAX_CHANNELS];
    for (int c = 0; c < ni->channels; ++c) {
        uint8_t* ch = chan(m, c);
        const uint16_t track = sms_track(nr, ni, o, c);
        delayed[c] = 0;
        if (!clamped && r >= 0 && (ch[BJC_EVENTS] & BJ_EVENT_TIC_WAIT)) {
            const uint16_t old = sms_track(m->region, &m->info, m->order, c);
            const int k = sms_track_delays_before(m->region, old, r, sms_rd16(ch + BJC_PATTERN_PTR));
            const uint16_t p = sms_track_after_delay(nr, track, r, k);
            if (p) {
                ptr[c] = p;
                wait[c] = ch[BJC_WAIT];
                delayed[c] = 1;
            }
        }
        if (!delayed[c]) {
            const SmsTrackPos pos = sms_track_after(nr, track, r + 1);
            ptr[c] = pos.ptr;
            wait[c] = pos.wait;
        }
    }
    int os1 = 0, os2 = 0, ns1 = 0, ns2 = 0;
    if (r >= 0) {
        sms_speeds_after(m->region, &m->info, m->order, r + 1, &os1, &os2);
        sms_speeds_after(nr, ni, o, r + 1, &ns1, &ns2);
    }
    const uint8_t* old_patch = NULL;
    uint8_t held[8];
    if (m->custom_ins >= 0 && m->custom_ins < m->info.instruments) {
        const uint8_t* rec = m->region + (m->info.instrument_table - SMS_REGION_BASE) + BJI_SIZE * m->custom_ins;
        const uint32_t at = sms_rd16(rec + BJI_FM_PATCH);
        if (at >= SMS_REGION_BASE && at + 8 <= (uint32_t)SMS_REGION_BASE + m->info.data_size) {
            memcpy(held, m->region + (at - SMS_REGION_BASE), 8);
            old_patch = held;
        }
    }
    memcpy(m->region, nr, SMS_REGION_SIZE);
    fill_calls(m->region, ni->layout);
    m->info = *ni;
    for (int c = 0; c < ni->channels; ++c) {
        uint8_t* ch = chan(m, c);
        wr16(ch + BJC_PATTERN_PTR, ptr[c]);
        ch[BJC_WAIT] = wait[c];
        if (!delayed[c]) ch[BJC_EVENTS] = (uint8_t)(ch[BJC_EVENTS] & ~BJ_EVENT_TIC_WAIT);
        refresh_instrument(m, c);
    }
    uint8_t* s = state(m);
    wr16(s + BJ_INSTRUMENT_PTRS, ni->instrument_table);
    wr16(s + BJ_ORDER_PTRS, ni->order_table);
    s[BJ_PATTERN_LENGTH] = (uint8_t)ni->pattern_length;
    s[BJ_ORDERS_LENGTH] = (uint8_t)ni->orders;
    s[BJ_ORDER] = (uint8_t)o;
    s[BJ_LINE] = (uint8_t)(ni->pattern_length - (r < 0 ? 0 : r));
    if ((s[BJ_FLAGS] & BJ_STATE_ORDER_JUMP) && s[BJ_ORDER_JUMP_TARGET] >= ni->orders) s[BJ_ORDER_JUMP_TARGET] = (uint8_t)ni->loop;
    if (r >= 0 && (clamped || os1 != ns1 || os2 != ns2)) {
        s[BJ_SPEED_1] = (uint8_t)ns1;
        s[BJ_SPEED_2] = (uint8_t)ns2;
    }
    m->order = o;
    m->row = r;
    /* The custom voice the YM2413 holds, rewritten if it was edited. */
    if (old_patch && m->custom_ins < ni->instruments) {
        const uint8_t* rec = m->region + (ni->instrument_table - SMS_REGION_BASE) + BJI_SIZE * m->custom_ins;
        const uint32_t at = sms_rd16(rec + BJI_FM_PATCH);
        if (rec[BJI_FM_PRESET] == BJ_FM_CUSTOM && at >= SMS_REGION_BASE && at + 8 <= (uint32_t)SMS_REGION_BASE + ni->data_size) {
            const uint8_t* patch = m->region + (at - SMS_REGION_BASE);
            if (memcmp(patch, old_patch, 8) != 0) {
                const int mode = m->mode;
                m->mode = MODE_CLOCKED;
                m->cycle = m->chip_t = 0;
                for (int k = 0; k < 8; ++k) chip_write(m, SMSM_W_OPLL, (uint8_t)k, patch[k]);
                m->mode = mode;
            }
        }
    }
}

/* ---- audition ------------------------------------------------------------------------------------------------ */

#define AUD_ORDERS (SMS_AUDITION_RAM + BJ_HEADER_SIZE)   /* one order pointer */
#define AUD_ROW (AUD_ORDERS + 2)                          /* SMS_MAX_CHANNELS track pointers */
#define AUD_SILENT (AUD_ROW + 2 * SMS_MAX_CHANNELS)      /* END_LINE + 63: 64 empty rows */
#define AUD_NOTE (AUD_SILENT + 2)                        /* instrument (or rhythm kit), note, END_LINE + 63 */
#define AUD_RELEASE (AUD_NOTE + 8)                       /* release or note off, END_LINE + 63 */

int smsm_audition(SmsMachine* m, const SmsImage* image, int channel, int note, int slot, int fine) {
    if (!loaded(image) || channel < 0 || channel >= image->info.channels || note < 0 || note > 127 || slot < 1 ||
        slot > image->info.instruments || fine != 0) {
        return SMSM_ERR_IMAGE;
    }
    const SmsImageInfo* info = &image->info;
    const uint8_t* rec = sms_region(image) + (info->instrument_table - SMS_REGION_BASE) + BJI_SIZE * (slot - 1);
    const int kind = info->kind[channel];
    const uint32_t patch = sms_rd16(rec + BJI_FM_PATCH), end = (uint32_t)SMS_REGION_BASE + info->data_size;
    if (kind == SMS_KIND_OPLL && rec[BJI_FM_PRESET] == BJ_FM_CUSTOM && (patch < SMS_REGION_BASE || patch + 8 > end)) return SMSM_ERR_IMAGE;
    if (kind == SMS_KIND_DRUM && rec[BJI_FM_PRESET] == BJ_FM_DRUMS_FIXED && (patch < SMS_REGION_BASE || patch + 6 > end)) return SMSM_ERR_IMAGE;
    power_on(m, image);
    uint8_t* a = &RAM(m, SMS_AUDITION_RAM);
    memcpy(a, m->region, BJ_HEADER_SIZE);
    a[BJ_FLAGS] = 0; /* no loop: after 64 rows of 255 ticks the song stops */
    a[BJ_SPEED_1] = a[BJ_SPEED_2] = a[BJ_TIC] = 255;
    a[BJ_PATTERN_LENGTH] = a[BJ_LINE] = 64;
    a[BJ_ORDERS_LENGTH] = 1;
    a[BJ_ORDER] = 0;
    a[BJ_ORDER_JUMP_TARGET] = 0xFF;
    wr16(a + BJ_ORDER_PTRS, AUD_ORDERS);
    wr16(&RAM(m, AUD_ORDERS), AUD_ROW);
    for (int c = 0; c < info->channels; ++c) wr16(&RAM(m, AUD_ROW + 2 * c), c == channel ? AUD_NOTE : AUD_SILENT);
    RAM(m, AUD_SILENT) = BJ_END_LINE | 63;
    uint8_t* n = &RAM(m, AUD_NOTE);
    n[0] = kind == SMS_KIND_DRUM ? BJ_FM_DRUM : BJ_INSTRUMENT_CHANGE;
    n[1] = (uint8_t)(slot - 1);
    n[2] = (uint8_t)(BJ_NOTE_ON | note);
    n[3] = BJ_END_LINE | 63;
    if (start_driver(m, SMS_AUDITION_RAM) != SMSM_OK) return SMSM_ERR_FAULT;
    m->audition = 1;
    m->aud_channel = channel;
    output_start(m);
    return SMSM_OK;
}

int smsm_release(SmsMachine* m) {
    if (!m->audition || m->released || m->faulted || !m->ticks) return 0;
    const int c = m->aud_channel;
    const int kind = m->info.kind[c];
    uint8_t* ch = chan(m, c);
    uint8_t op = BJ_NOTE_OFF;
    int tail = kind != SMS_KIND_SN; /* FM and rhythm voices fade with the chip's release */
    if (kind != SMS_KIND_DRUM && (ch[BJC_FLAGS] & BJ_CHAN_FLAG_VOLUME_MACRO)) {
        const uint16_t p = sms_rd16(ch + BJC_VOLUME_MACRO_PTR);
        if (mem_read(m, (uint16_t)(p + 2)) != mem_read(m, p)) { /* a release part: release length > sustain length */
            op = BJ_NOTE_RELEASE;
            tail = 1;
        }
    }
    RAM(m, AUD_RELEASE) = op;
    RAM(m, AUD_RELEASE + 1) = BJ_END_LINE | 63;
    wr16(ch + BJC_PATTERN_PTR, AUD_RELEASE);
    ch[BJC_WAIT] = 1;
    ch[BJC_EVENTS] |= BJ_EVENT_TIC_WAIT; /* the next tick reads it, whatever the row timer says */
    m->released = 1;
    m->release_ticks = 0;
    m->quiet = 0;
    return tail;
}

int smsm_mute(SmsMachine* m, int channel, int muted) {
    if (!m->loaded || channel < 0 || channel >= m->info.channels) return SMSM_ERR_IMAGE;
    const int kind = m->info.kind[channel];
    if (kind == SMS_KIND_SN) {
        m->psg.mute = muted ? (m->psg.mute | (1u << channel)) : (m->psg.mute & ~(1u << channel));
        return SMSM_OK;
    }
    /* rhythm channels in Banjo's order: bass drum, snare, tom, top cymbal, hi-hat */
    static const uint32_t DRUM_MASKS[5] = {OPLL_MASK_BD, OPLL_MASK_SD, OPLL_MASK_TOM, OPLL_MASK_CYM, OPLL_MASK_HH};
    const uint32_t bit = kind == SMS_KIND_DRUM ? DRUM_MASKS[channel - 10] : (uint32_t)OPLL_MASK_CH(channel - 4);
    m->fm_mask = muted ? (m->fm_mask | bit) : (m->fm_mask & ~bit);
    OPLL_setMask(m->opll, m->fm_mask);
    return SMSM_OK;
}

/* ---- diagnostics and test hooks ------------------------------------------------------------------------------ */

uint8_t smsm_peek(const SmsMachine* m, uint16_t addr) { return mem_read(m, addr); }
uint32_t smsm_ticks(const SmsMachine* m) { return m->ticks; }
uint32_t smsm_last_call_cycles(const SmsMachine* m) { return m->last_call; }

void smsm_set_log(SmsMachine* m, int enabled) {
    m->log_on = enabled;
    m->nlog = 0;
}

const SmsmWrite* smsm_log(const SmsMachine* m, int* count) {
    *count = m->nlog;
    return m->log;
}

const YmPsg* smsm_psg(const SmsMachine* m) { return &m->psg; }
const uint8_t* smsm_opll_registers(const SmsMachine* m) { return m->opll_reg; }
uint8_t smsm_pan(const SmsMachine* m) { return m->pan; }
int smsm_faulted(const SmsMachine* m) { return m->faulted; }
