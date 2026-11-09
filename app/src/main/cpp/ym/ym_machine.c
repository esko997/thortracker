/* Mega Drive music machine (ym_machine.h): SGDK's XGM2 driver on z80.h + ymfm YM2612 + SN76489 + blip_buf, fed by the
 * player through the XGM2 encoder. */
#include "ym_machine.h"

#include <stdlib.h>
#include <string.h>

#include "blip_buf.h"
#include "generated/xgm2_driver_image.h"
#include "ym_psg.h"
#include "ym_xgm.h"
#include "ym_ymfm.h"
#include "z80.h"

/* Output scale (/ 256, then sample / 32768): ymfm's YM2612 sum (one full-level sine channel peaks at 0.125, six at
 * 0.75; the DAC replaces FM 6) and the PSG's level table, in libvgm's default balance (VGMPlay volumes YM2612 0x200 :
 * SN76489 0x100 with its GPGX and MAME cores: a full-level PSG square has 0.35 times the rms of a full-level FM sine);
 * docs "Mix". */
#define YM_FM_GAIN 192
#define YM_PSG_GAIN 65

#define FRAME_WRITES (2 * YMP_MAX_WRITES + 8)
#define FIFO 64 /* positions of the last encoded player ticks (the driver lags behind heavy frames) */
#define PSG_DIV (YMS_MCLK_PER_STEP / YMM_MCLK_PER_Z80) /* Z80 cycles per PSG counter step */
#define BOOT_CYCLES 400000u  /* the driver's boot: clears, mutes, ready (well below 0.2 s) */
#define ACCESS_WAIT 8192u    /* the 68000 waits at most this long for the driver's access flag */

/* Driver command and parameter bits (SGDK drv_xgm2.s80, xgm2.c). */
#define CMD_RESUME 0x08
#define CMD_PLAY 0x10
#define CMD_PAUSE 0x20
#define PLAY_ARG_FM_ADDR (YMM_PARAMS + 0x00)
#define PLAY_ARG_PSG_ADDR (YMM_PARAMS + 0x02)
#define PLAY_ARG_LOOP (YMM_PARAMS + 0x04)
#define TEMPO_ARG_INC_FRAC (YMM_PARAMS + 0x0A)
#define TEMPO_ARG_INC (YMM_PARAMS + 0x0B)
#define SID_ENTRIES 124 /* the single-track sample table XGM2_load writes: 123 starts and the last end */

struct YmMachine {
    uint32_t rate;
    int region;
    uint32_t mclk_per_tick;
    /* Z80 side */
    z80_t cpu;
    uint64_t pins;
    uint8_t ram[0x2000];
    uint32_t bank;
    uint32_t int_left;   /* cycles the vertical interrupt still holds INT */
    uint16_t ym_addr;    /* the YM2612 address latch as the Z80 last wrote it (bit 8: port 2) */
    uint8_t fm_ring[YMM_STREAM_SIZE];
    uint8_t psg_ring[YMM_STREAM_SIZE];
    uint32_t fm_pos, psg_pos;
    int fm_last_at, fm_last_size, psg_last_at, psg_last_size;
    /* chips */
    YmOpn2* opn;
    YmPsg psg;
    uint64_t mclk;       /* master clocks since machine reset */
    uint32_t ym_acc;     /* master clocks since the last YM2612 sample */
    uint32_t psg_div;    /* Z80 cycles since the last PSG step (one per 16) */
    int psg_written;     /* a PSG write since the last step (its output may change) */
    uint32_t tick_acc;   /* NTSC/PAL frame accumulator (whole Z80 cycles per tick) */
    unsigned fm_mute;
    unsigned pcm_mute;   /* bit k: PCM channel k muted */
    unsigned pcm_stop;   /* bit k: the next frame stops PCM channel k */
    /* output */
    blip_t* blip[2];
    int fm_last[2];
    int psg_last;
    short pcm[2 * YMM_MAX_TICK_FRAMES + 16];
    /* player, images and stream */
    YmPlayer player;
    YmImage* img[2];
    int cur;             /* the player's image */
    int rom;             /* the image whose PCM bank the cartridge maps (and the sample table points into) */
    int swap_pending;    /* img[cur]'s bank waits for the driver's next PCM fill boundary */
    uint32_t swap_frame; /* the first frame encoded from img[cur] */
    int loaded;
    int fifo[FIFO][3];   /* position of player tick i at fifo[i % FIFO] */
    uint32_t fifo_rows[FIFO]; /* row_starts after player tick i was encoded: its row instance (ymm_row_serial) */
    uint32_t row_starts;      /* player ticks encoded at tick 0 since begin_stream */
    uint32_t encoded;    /* player ticks encoded since the start (the last one is queued, not yet executed) */
    uint32_t ticks;
    int audition, audition_channel, released, release_ticks;
    uint32_t keyoff_tick; /* the encoded tick whose frame carries the audition's key-off */
    /* scratch */
    YmWrite prefix[YMP_MAX_WRITES];
    int prefix_pcm[YM_PCM_CHANNELS]; /* the prefix batch's PCM commands */
    int nprefix;
    YmWrite frame[FRAME_WRITES];
    YmxFrame xf;
    uint8_t cmds[YMX_FRAME_MAX];
    /* test hooks */
    int log_on;
    YmmLogEntry log[YMM_LOG_MAX];
    int nlog;
    uint32_t cycle_in_tick, last_tick_cycles;
};

_Static_assert(sizeof XGM2_DRIVER <= sizeof ((YmMachine*)0)->ram, "the driver fits the Z80's 8 KiB");
_Static_assert(YMX_FRAME_MAX < YMX_BUFFER, "a frame never fills the driver's command buffer");

/* ---- the Z80 bus --------------------------------------------------------------------------------- */

static void log_write(YmMachine* m, int kind, int reg, int val) {
    if (m->log_on && m->nlog < YMM_LOG_MAX)
        m->log[m->nlog++] = (YmmLogEntry){m->cycle_in_tick, (uint8_t)kind, (uint8_t)reg, (uint8_t)val};
}

static uint8_t rom_read(const YmMachine* m, uint32_t a) {
    if (a >= YMM_FM_STREAM && a < YMM_FM_STREAM + YMM_STREAM_SIZE) return m->fm_ring[a - YMM_FM_STREAM];
    if (a >= YMM_PSG_STREAM && a < YMM_PSG_STREAM + YMM_STREAM_SIZE) return m->psg_ring[a - YMM_PSG_STREAM];
    const YmImage* rom = m->img[m->rom];
    if (a >= YMM_SAMPLES && a < YMM_SAMPLES + (uint32_t)rom->info.pcm_pages * YM_PCM_ALIGN)
        return rom->bytes[YM_OFF_PCM_BANK + (a - YMM_SAMPLES)];
    return 0xFF;
}

static void bank_write(YmMachine* m, uint8_t d) { m->bank = ((m->bank >> 1) | (uint32_t)(d & 1) << 8) & 0x1FF; }

static void psg_bus_write(YmMachine* m, uint8_t d) {
    ymps_write(&m->psg, d);
    m->psg_written = 1; /* heard from the next step */
    log_write(m, YM_W_PSG, 0, d);
}

/* ---- the chips, clocked in Z80 cycles ------------------------------------------------------------ */

static void psg_step(YmMachine* m, int emit, uint32_t t) {
    if (!ymps_step(&m->psg) && m->psg_written == 0) return;
    m->psg_written = 0;
    const int s = ymps_output(&m->psg);
    if (s != m->psg_last) {
        if (emit) {
            const int d = (s - m->psg_last) * YM_PSG_GAIN >> 8;
            blip_add_delta(m->blip[0], t, d);
            blip_add_delta(m->blip[1], t, d);
        }
        m->psg_last = s;
    }
}

static void ym_sample(YmMachine* m, int emit, uint32_t t) {
    int32_t s[2];
    ymo_generate(m->opn, m->fm_mute, &s[0], &s[1]);
    for (int k = 0; k < 2; ++k) {
        if (s[k] != m->fm_last[k]) {
            if (emit) blip_add_delta(m->blip[k], t, (s[k] - m->fm_last[k]) * YM_FM_GAIN >> 8);
            m->fm_last[k] = s[k];
        }
    }
}

/* The chips' part of one Z80 cycle (after its bus access), at blip time t. */
static inline void chips_cycle(YmMachine* m, int emit, uint32_t t) {
    m->mclk += YMM_MCLK_PER_Z80;
    ++m->cycle_in_tick;
    if (++m->psg_div == PSG_DIV) {
        m->psg_div = 0;
        psg_step(m, emit, t);
    }
    m->ym_acc += YMM_MCLK_PER_Z80;
    if (m->ym_acc >= YMM_MCLK_PER_YM_SAMPLE) {
        m->ym_acc -= YMM_MCLK_PER_YM_SAMPLE;
        ym_sample(m, emit, t);
    }
}

/* ---- the PCM bank of a live edit ------------------------------------------------------------------
 * The cartridge maps img[rom]'s bank at YMM_SAMPLES and the driver's sample table points into it. A live edit that
 * changes the bank (img[cur]) is mapped when the driver fetches main_loop: the start of its PCM fill round, where the
 * three channels' address and length variables hold where each continues (no fill has them in registers, the
 * interrupt handler is not running). A sounding sample whose bytes the new bank holds unchanged (the same variant id
 * first, else any variant with the same bytes, length and rate) continues at the same offset there; any other stops
 * (its STATUS bit cleared, as the driver clears it at a sample's end). Then the sample table is rewritten. */

static void write_sids(YmMachine* m) {
    const YmImage* rom = m->img[m->rom];
    const int n = rom->info.pcm_variants;
    for (int i = 0; i < SID_ENTRIES; ++i) {
        uint32_t page = 0;
        if (i < n) {
            int start, pages, flags;
            ym_pcm_region(rom, i + 1, &start, &pages, &flags);
            page = (uint32_t)start;
        } else if (i == n && n) {
            page = (uint32_t)rom->info.pcm_pages;
        }
        const uint32_t addr = i <= n && n ? (YMM_SAMPLES >> 8) + page : 0;
        m->ram[YMM_SID_TABLE + 2 * i] = (uint8_t)addr;
        m->ram[YMM_SID_TABLE + 2 * i + 1] = (uint8_t)(addr >> 8);
    }
}

static int same_region(const YmImage* a, int ida, const YmImage* b, int idb) {
    int sa, pa, fa, sb, pb, fb;
    ym_pcm_region(a, ida, &sa, &pa, &fa);
    ym_pcm_region(b, idb, &sb, &pb, &fb);
    return pa == pb && fa == fb &&
           !memcmp(a->bytes + YM_OFF_PCM_BANK + (size_t)sa * YM_PCM_ALIGN, b->bytes + YM_OFF_PCM_BANK + (size_t)sb * YM_PCM_ALIGN,
                   (size_t)pa * YM_PCM_ALIGN);
}

static int banks_equal(const YmImage* a, const YmImage* b) {
    return a->info.pcm_variants == b->info.pcm_variants && a->info.pcm_pages == b->info.pcm_pages &&
           !memcmp(a->bytes + YM_OFF_PCM_VARIANTS, b->bytes + YM_OFF_PCM_VARIANTS, (size_t)YM_PCM_VARIANT_SIZE * (size_t)a->info.pcm_variants) &&
           !memcmp(a->bytes + YM_OFF_PCM_BANK, b->bytes + YM_OFF_PCM_BANK, (size_t)a->info.pcm_pages * YM_PCM_ALIGN);
}

static void map_bank(YmMachine* m) {
    const YmImage* old = m->img[m->rom];
    const YmImage* next = m->img[m->cur];
    for (int k = 0; k < YM_PCM_CHANNELS; ++k) {
        if (!(m->ram[YMM_STATUS] & (1 << k))) continue;
        uint8_t* v = m->ram + YMM_PCM_VARS + 8 * k;
        const uint32_t addr = (uint32_t)v[0] | (uint32_t)v[1] << 8 | (uint32_t)v[2] << 16;
        int id = 0, start = 0, pages = 0, flags = 0;
        if (addr >= YMM_SAMPLES) {
            const uint32_t page = (addr - YMM_SAMPLES) >> 8;
            for (int i = 1; i <= old->info.pcm_variants && !id; ++i) {
                ym_pcm_region(old, i, &start, &pages, &flags);
                if (page >= (uint32_t)start && page < (uint32_t)(start + pages)) id = i;
            }
        }
        int to = 0;
        if (id) {
            if (id <= next->info.pcm_variants && same_region(old, id, next, id)) to = id;
            for (int i = 1; i <= next->info.pcm_variants && !to; ++i)
                if (same_region(old, id, next, i)) to = i;
        }
        if (!to) {
            m->ram[YMM_STATUS] &= (uint8_t)~(1 << k);
            continue;
        }
        int nstart, npages, nflags;
        ym_pcm_region(next, to, &nstart, &npages, &nflags);
        const uint32_t moved = addr + (uint32_t)(nstart - start) * YM_PCM_ALIGN;
        v[0] = (uint8_t)moved;
        v[1] = (uint8_t)(moved >> 8);
        v[2] = (uint8_t)(moved >> 16);
    }
    m->rom = m->cur;
    m->swap_pending = 0;
    write_sids(m);
}

/* ---- the Z80 ---------------------------------------------------------------------------------------- */

static void ym_bus_write(YmMachine* m, int port, uint8_t v) {
    ymo_write(m->opn, m->mclk, port, v);
    if (!(port & 1)) {
        m->ym_addr = (uint16_t)((port & 2 ? 0x100 : 0) | v);
    } else if ((port & 2) == (m->ym_addr & 0x100 ? 2 : 0)) {
        log_write(m, m->ym_addr >> 8, m->ym_addr & 0xFF, v);
    }
}

/* The bus access of one Z80 cycle (pins as z80_tick returned them). */
static inline uint64_t bus(YmMachine* m, uint64_t pins) {
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
            if (a < 0x4000) m->ram[a & 0x1FFF] = d;
            else if (a < 0x6000) ym_bus_write(m, a & 3, d);
            else if (a < 0x6100) bank_write(m, d);
            else if ((a & 0xFFF9) == 0x7F11) psg_bus_write(m, d);
        }
    } else if ((pins & Z80_IORQ) && (pins & Z80_RD)) {
        Z80_SET_DATA(pins, 0xFF);
    }
    return pins;
}

/* `cycles` Z80 cycles with the chips; output into the blip buffers when `emit`, at Z80-cycle times from `t0`. */
static void run(YmMachine* m, uint32_t cycles, int emit, uint32_t t0) {
    uint64_t pins = m->pins;
    for (uint32_t c = 0; c < cycles; ++c) {
        if (m->int_left) {
            pins |= Z80_INT;
            --m->int_left;
        } else {
            pins &= ~Z80_INT;
        }
        pins = z80_tick(&m->cpu, pins);
        if (m->swap_pending && (pins & (Z80_M1 | Z80_MREQ | Z80_RD)) == (Z80_M1 | Z80_MREQ | Z80_RD) &&
            Z80_GET_ADDR(pins) == XGM2_DRIVER_MAIN_LOOP)
            map_bank(m);
        pins = bus(m, pins);
        chips_cycle(m, emit, t0 + c);
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

/* ---- the 68000 side (SGDK z80_ctrl.c, xgm2.c) ------------------------------------------------------- */

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
}

/* getAccess: the 68000 waits (the Z80 runs) while the driver processes its commands. Returns the cycles waited. */
static uint32_t access_wait(YmMachine* m, int emit, uint32_t t0) {
    uint32_t waited = 0;
    while ((m->ram[YMM_ACCESS] & 1) && waited < ACCESS_WAIT) {
        run(m, 1, emit, t0 + waited);
        ++waited;
    }
    return waited;
}

/* Power on: chips reset, SGDK's Z80_loadDriverInternal (YM2612_reset(), PSG_reset(), RAM cleared, the driver
 * uploaded, the Z80 reset) until the driver is ready, then XGM2_loadDriver's infinite loop count and the tempo of a
 * song played in its own region (one frame per vertical interrupt). */
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
    memcpy(m->ram, XGM2_DRIVER, sizeof XGM2_DRIVER);
    m->bank = 0;
    m->int_left = 0;
    m->pins = z80_init(&m->cpu);
    m->cycle_in_tick = 0;
    m->fm_mute = m->pcm_mute = m->pcm_stop = 0;
    m->psg.mute = 0;
    const int was_logging = m->log_on;
    m->log_on = 0;
    uint32_t boot = 0;
    while (!(m->ram[YMM_STATUS] & YMM_STAT_READY)) {
        run(m, 256, 0, 0);
        if ((boot += 256) > BOOT_CYCLES) return -1;
    }
    m->log_on = was_logging;
    m->ram[PLAY_ARG_LOOP] = 0xFF;
    access_wait(m, 0, 0);
    m->ram[TEMPO_ARG_INC_FRAC] = 0;
    m->ram[TEMPO_ARG_INC] = 1;
    /* Output starts here: the blip buffers hold no history, the chips' present output is their zero. */
    for (int k = 0; k < 2; ++k) blip_clear(m->blip[k]);
    m->fm_pos = m->psg_pos = 0;
    m->fm_last_at = m->fm_last_size = m->psg_last_at = m->psg_last_size = 0;
    return 0;
}

/* The vertical interrupt: the VDP raises the Z80's INT for one line. A live edit's bank still waiting when the first
 * frame encoded from it is about to execute is mapped now (a driver busy the whole tick in its interrupt handler). */
static void vint(YmMachine* m) {
    if (m->swap_pending && m->encoded >= m->swap_frame + 2) map_bank(m);
    m->int_left = YMM_INT_CYCLES;
}

/* Appends one frame (its commands, a one-frame wait or - near the ring's end - the loop command to the ring start) as
 * a packed block to a stream ring. */
static void stream_append(uint8_t* ring, uint32_t* pos, const uint8_t* cmds, int n, int psg, uint8_t* scratch, int* last_at,
                          int* last_size) {
    memcpy(scratch, cmds, (size_t)n);
    const int wrap = *pos + 2u * YMX_PACKED_MAX > YMM_STREAM_SIZE;
    n += wrap ? ymx_loop(scratch + n, psg, 0) : ymx_wait(scratch + n, psg, 1);
    const size_t size = ymx_pack(scratch, n, *pos, ring + *pos);
    *last_at = (int)*pos;
    *last_size = (int)size;
    *pos = wrap ? 0 : *pos + (uint32_t)size;
}

/* Runs the player one tick (the prefix first: a reset or key-off batch) and streams its FM and PSG frames. The stream
 * start and every arrival at the loop point (outside auditions) are full frames, as the export has them. Returns 0 or
 * -1 (a frame the encoder cannot hold). */
static int encode_tick(YmMachine* m) {
    YmPlayer* p = &m->player;
    const YmImage* image = m->img[m->cur];
    int* pos = m->fifo[m->encoded % FIFO];
    pos[0] = p->order;
    pos[1] = p->row;
    pos[2] = p->tick;
    if (p->tick == 0) ++m->row_starts;
    m->fifo_rows[m->encoded % FIFO] = m->row_starts;
    const int full = m->encoded == 0 || (!m->audition && p->order == image->info.loop && p->row == 0 && p->tick == 0);
    int n = m->nprefix;
    memcpy(m->frame, m->prefix, sizeof m->prefix[0] * (size_t)n);
    m->nprefix = 0;
    ymp_tick(p, image);
    memcpy(m->frame + n, p->write, sizeof p->write[0] * (size_t)p->writes);
    n += p->writes;
    int pcm[YM_PCM_CHANNELS];
    for (int k = 0; k < YM_PCM_CHANNELS; ++k) {
        const unsigned bit = 1u << k;
        int v = p->pcm[k] != YMP_PCM_NONE ? p->pcm[k] : m->prefix_pcm[k];
        m->prefix_pcm[k] = YMP_PCM_NONE;
        if (v > 0 && (m->pcm_mute & bit)) v = YMP_PCM_NONE; /* a muted channel's notes are not sent */
        if ((m->pcm_stop & bit) && v <= 0) v = YMP_PCM_STOP;
        pcm[k] = v;
    }
    m->pcm_stop = 0;
    if (ymx_encode(image, p, m->frame, n, pcm, full, &m->xf)) return -1;
    stream_append(m->fm_ring, &m->fm_pos, m->xf.fm, m->xf.fm_size, 0, m->cmds, &m->fm_last_at, &m->fm_last_size);
    stream_append(m->psg_ring, &m->psg_pos, m->xf.psg, m->xf.psg_size, 1, m->cmds, &m->psg_last_at, &m->psg_last_size);
    ++m->encoded;
    return 0;
}

/* Player ticks the driver has executed completely: ELAPSED_FRAME counts the FM frames it processed since play. */
static uint32_t executed(const YmMachine* m) {
    const uint8_t* e = m->ram + YMM_ELAPSED_FRAME;
    const uint32_t elapsed = (uint32_t)e[0] | (uint32_t)e[1] << 8 | (uint32_t)e[2] << 16;
    const uint32_t behind = (m->encoded - elapsed) & 0xFFFFFFu;
    return m->encoded > behind ? m->encoded - behind : 0;
}

/* The prefix the next frame starts with: a batch's writes and PCM commands. */
static void add_prefix(YmMachine* m, const YmPlayer* p) {
    int n = p->writes;
    if (m->nprefix + n > YMP_MAX_WRITES) n = YMP_MAX_WRITES - m->nprefix;
    memcpy(m->prefix + m->nprefix, p->write, sizeof p->write[0] * (size_t)n);
    m->nprefix += n;
    for (int k = 0; k < YM_PCM_CHANNELS; ++k)
        if (p->pcm[k] != YMP_PCM_NONE) m->prefix_pcm[k] = p->pcm[k];
}

static void clear_prefix(YmMachine* m) {
    m->nprefix = 0;
    for (int k = 0; k < YM_PCM_CHANNELS; ++k) m->prefix_pcm[k] = YMP_PCM_NONE;
}

/* One vertical interrupt's worth of Z80 and chips (into the blip buffers when `emit`). */
static void run_tick(YmMachine* m, int emit) {
    m->nlog = 0;
    m->cycle_in_tick = 0;
    const uint32_t cycles = tick_cycles(m);
    vint(m);
    run(m, cycles, emit, 0);
    m->last_tick_cycles = cycles;
    if (emit)
        for (int k = 0; k < 2; ++k) blip_end_frame(m->blip[k], cycles);
}

/* After the player is in place: the sample table (XGM2_load), the stream addresses and the play command
 * (XGM2_playTrack, after the access wait), and the first frame encoded; the driver takes the command and unpacks the
 * frame in one tick (no output). */
static int begin_stream(YmMachine* m) {
    m->encoded = 0;
    m->row_starts = 0;
    write_sids(m);
    access_wait(m, 0, 0);
    m->ram[PLAY_ARG_FM_ADDR] = (uint8_t)(YMM_FM_STREAM >> 8);
    m->ram[PLAY_ARG_FM_ADDR + 1] = (uint8_t)(YMM_FM_STREAM >> 16);
    m->ram[PLAY_ARG_PSG_ADDR] = (uint8_t)(YMM_PSG_STREAM >> 8);
    m->ram[PLAY_ARG_PSG_ADDR + 1] = (uint8_t)(YMM_PSG_STREAM >> 16);
    m->ram[YMM_COMMAND] = (uint8_t)((m->ram[YMM_COMMAND] & ~(CMD_RESUME | CMD_PAUSE)) | CMD_PLAY);
    if (encode_tick(m)) return -1;
    run_tick(m, 0);
    return 0;
}

/* ---- life cycle ----------------------------------------------------------------------------------- */

YmMachine* ymm_create(uint32_t sample_rate) {
    if (sample_rate < 8000 || sample_rate > YMM_MAX_SAMPLE_RATE) return NULL;
    YmMachine* m = (YmMachine*)calloc(1, sizeof *m);
    if (!m) return NULL;
    m->rate = sample_rate;
    m->opn = ymo_create();
    for (int i = 0; i < 2; ++i) {
        m->blip[i] = blip_new((int)YMM_MAX_TICK_FRAMES + 64);
        m->img[i] = (YmImage*)calloc(1, sizeof(YmImage));
    }
    if (!m->opn || !m->blip[0] || !m->blip[1] || !m->img[0] || !m->img[1] || machine_reset(m, YM_NTSC)) {
        ymm_destroy(m);
        return NULL;
    }
    return m;
}

void ymm_destroy(YmMachine* m) {
    if (!m) return;
    for (int i = 0; i < 2; ++i) {
        if (m->blip[i]) blip_delete(m->blip[i]);
        free(m->img[i]);
    }
    if (m->opn) ymo_destroy(m->opn);
    free(m);
}

/* ---- start and seek ------------------------------------------------------------------------------- */

static int at(const YmPlayer* p, int order, int row) { return p->order == order && p->row == row && p->tick == 0; }

/* The player alone from the song start (or at the order's row 0 with its start speed: from_order) until the next
 * tick starts (order, row), at most `bound` ticks (never leaving the order from_order). Returns the ticks, or -1 if
 * (order, row) was not reached. */
static int ticks_to(YmMachine* m, int from_order, int order, int row, int bound) {
    YmPlayer* p = &m->player;
    const YmImage* image = m->img[m->cur];
    if (from_order) ymp_place(p, image, order);
    else ymp_reset(p, image);
    int n = 0;
    for (; !at(p, order, row) && n < bound && (!from_order || p->order == order); ++n) ymp_tick(p, image);
    return at(p, order, row) ? n : -1;
}

static int stream_tick(YmMachine* m, int emit);

/* Loads `image` as the machine's only image (player and cartridge). */
static void take_image(YmMachine* m, const YmImage* image) {
    ym_copy_image(m->img[0], image);
    m->cur = m->rom = 0;
    m->swap_pending = 0;
}

int ymm_start(YmMachine* m, const YmImage* image, int order, int row, int max_ticks, int* seek_mode) {
    if (seek_mode) *seek_mode = YMM_SEEK_FROM_START;
    if (image->info.loaded != YM_LOADED || order < 0 || order >= image->info.orders || row < 0 ||
        row >= image->info.rows[order])
        return YMM_ERR_IMAGE;
    m->loaded = 0;
    m->audition = m->released = m->release_ticks = 0;
    take_image(m, image);
    int from_order = 0;
    int ticks = ticks_to(m, 0, order, row, max_ticks);
    if (ticks < 0) {
        /* A row behind a Dxx/Bxx of the same order is unreachable. */
        from_order = 1;
        if (seek_mode) *seek_mode = YMM_SEEK_FROM_ORDER;
        ticks = ticks_to(m, 1, order, row, YMM_ORDER_SEEK_TICKS);
        if (ticks < 0) return YMM_ERR_IMAGE;
    }
    if (machine_reset(m, image->info.region)) return YMM_ERR_FAULT;
    if (from_order) ymp_place(&m->player, m->img[m->cur], order);
    else ymp_reset(&m->player, m->img[m->cur]);
    clear_prefix(m);
    add_prefix(m, &m->player);
    if (begin_stream(m)) return YMM_ERR_FAULT;
    m->loaded = 1;
    /* The whole machine runs to the target: the driver, the chips and the PCM channels as straight play leaves them. */
    for (int t = 0; t < ticks; ++t) {
        if (stream_tick(m, 0) < 0) {
            m->loaded = 0;
            return YMM_ERR_FAULT;
        }
    }
    for (int k = 0; k < 2; ++k) blip_clear(m->blip[k]); /* the present output is the zero */
    m->ticks = 0;
    return YMM_OK;
}

void ymm_apply_image(YmMachine* m, const YmImage* image) {
    if (!m->loaded || m->audition || image->info.loaded != YM_LOADED) return;
    if (m->swap_pending) map_bank(m); /* two edits within one fill round: the first is mapped now */
    const int next = m->cur ^ 1;
    ym_copy_image(m->img[next], image);
    ymp_relocate(&m->player, m->img[m->cur], m->img[next]);
    m->cur = next;
    if (banks_equal(m->img[m->rom], m->img[next])) {
        m->rom = next;
    } else {
        m->swap_pending = 1;
        m->swap_frame = m->encoded;
    }
}

int ymm_audition(YmMachine* m, const YmImage* image, int channel, int note, int slot, int fine) {
    if (image->info.loaded != YM_LOADED) return YMM_ERR_IMAGE;
    m->loaded = 0;
    m->released = m->release_ticks = 0;
    take_image(m, image);
    if (machine_reset(m, image->info.region)) return YMM_ERR_FAULT;
    if (ymp_audition(&m->player, m->img[m->cur], channel, note, slot, fine)) return YMM_ERR_IMAGE;
    /* The stream starts with the audition batch (a full frame the driver executes at once). */
    clear_prefix(m);
    add_prefix(m, &m->player);
    m->audition = 1;
    m->audition_channel = channel;
    if (begin_stream(m)) return YMM_ERR_FAULT;
    m->loaded = 1;
    m->ticks = 0;
    return YMM_OK;
}

/* The audition note is silent: FM every carrier's envelope at the bottom; PSG the attenuation 15 (and the envelope
 * off); PCM its sample played out (the driver cleared the channel's STATUS bit). */
static int audition_silent(const YmMachine* m) {
    const int c = m->audition_channel;
    const YmChannel* ch = &m->player.ch[c];
    if (c >= YM_CH_PCM) return executed(m) > 0 && !(m->ram[YMM_STATUS] & (1 << (c - YM_CH_PCM)));
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
        /* A PCM sample is a one-shot: it plays out (a key-off would stop it). */
        if (m->audition_channel < YM_CH_PCM) {
            ymp_key_off(&m->player, m->audition_channel);
            add_prefix(m, &m->player);
        }
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
    } else if (channel < YM_CH_PCM) {
        const unsigned bit = 1u << (channel - YM_CH_PSG);
        if (muted) m->psg.mute |= bit;
        else m->psg.mute &= ~bit;
        m->psg_written = 1; /* the output changes at the next step */
    } else {
        const unsigned bit = 1u << (channel - YM_CH_PCM);
        if (muted && !(m->pcm_mute & bit)) m->pcm_stop |= bit; /* the sounding sample stops through the driver */
        if (muted) m->pcm_mute |= bit;
        else m->pcm_mute &= ~bit;
    }
    return YMM_OK;
}

/* The fifo slot of the player tick the driver executes next. */
static uint32_t heard(const YmMachine* m) {
    uint32_t next = executed(m);
    if (m->encoded - next > FIFO) next = m->encoded - FIFO;
    return next % FIFO;
}

void ymm_position(const YmMachine* m, int* order, int* row, int* tick) {
    const int* pos = m->fifo[heard(m)];
    *order = pos[0];
    *row = pos[1];
    *tick = pos[2];
}

uint32_t ymm_row_serial(const YmMachine* m) { return m->fifo_rows[heard(m)]; }

/* One engine tick: the player's next frame is encoded and streamed, then one vertical interrupt's worth of Z80 and
 * chips run (the driver executes the frame queued the tick before and unpacks this one). */
static int stream_tick(YmMachine* m, int emit) {
    if (encode_tick(m)) return -1;
    run_tick(m, emit);
    if (!(m->ram[YMM_STATUS] & YMM_STAT_PLAY)) return -1; /* the driver stopped playing: a fault */
    if (m->encoded - executed(m) > YMM_MAX_LAG) return -1; /* the rings would overwrite frames still queued */
    return 0;
}

/* Released auditions end once silent past the key-off (or after YMM_RELEASE_SECONDS): 1 = ended. */
static int release_done(YmMachine* m) {
    if (!m->released) return 0;
    const int limit = YMM_RELEASE_SECONDS * (m->region == YM_PAL ? 50 : 60);
    if ((executed(m) > m->keyoff_tick && audition_silent(m)) || m->release_ticks >= limit) {
        m->loaded = 0;
        return 1;
    }
    ++m->release_ticks;
    return 0;
}

int ymm_tick(YmMachine* m, float* out) {
    if (!m->loaded || release_done(m)) return 0;
    if (stream_tick(m, 1) < 0) {
        m->loaded = 0;
        return YMM_ERR_FAULT;
    }
    int frames = 0;
    for (int k = 0; k < 2; ++k) frames = blip_read_samples(m->blip[k], m->pcm + k, (int)YMM_MAX_TICK_FRAMES, 1);
    for (int i = 0; i < 2 * frames; ++i) out[i] = m->pcm[i] * (1.0f / 32768.0f);
    ++m->ticks;
    return frames;
}

int ymm_tick_silent(YmMachine* m) {
    if (!m->loaded) return YMM_ERR_FAULT;
    if (stream_tick(m, 0) < 0) {
        m->loaded = 0;
        return YMM_ERR_FAULT;
    }
    ++m->ticks;
    return YMM_OK;
}

const YmPlayer* ymm_player(const YmMachine* m) { return &m->player; }
uint32_t ymm_ticks(const YmMachine* m) { return m->ticks; }
const uint8_t* ymm_last_fm(const YmMachine* m, int* size) {
    *size = m->fm_last_size;
    return m->fm_ring + m->fm_last_at;
}
const uint8_t* ymm_last_psg(const YmMachine* m, int* size) {
    *size = m->psg_last_size;
    return m->psg_ring + m->psg_last_at;
}
void ymm_set_log(YmMachine* m, int enabled) { m->log_on = enabled != 0; }
const YmmLogEntry* ymm_log(const YmMachine* m, int* count) {
    *count = m->nlog;
    return m->log;
}
const uint8_t* ymm_z80_ram(const YmMachine* m) { return m->ram; }
uint32_t ymm_z80_cycles_per_tick(const YmMachine* m) { return m->last_tick_cycles; }
int ymm_lag(const YmMachine* m) {
    const uint32_t behind = m->encoded - executed(m);
    return behind > 1 ? (int)(behind - 1) : 0;
}
unsigned ymm_pcm_playing(const YmMachine* m) { return m->ram[YMM_STATUS] & 7u; }
int ymm_dac_enabled(const YmMachine* m) { return ymo_dac_enabled(m->opn); }
int ymm_swap_pending(const YmMachine* m) { return m->swap_pending; }
const void* ymm_z80(const YmMachine* m) { return &m->cpu; }
