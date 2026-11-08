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
#define PSG_DIV (YMS_MCLK_PER_STEP / YMM_MCLK_PER_Z80) /* Z80 cycles per PSG counter step */

/* Pass replay (below, "pass replay"): capacities. A mixer pass reads ~1100 bytes (code, variables, 768 null-sample
 * bytes), writes ~1060 and makes ~190 chip writes; a frame-wait pass a few dozen. */
#define RP_ENTRIES 64
#define RP_ARENA (640u * 1024u)
#define RP_MAX_READS 2048
#define RP_MAX_EVENTS 512
#define RP_MAX_REL 512
#define RP_MAX_OBS 8
#define RP_MAX_CYCLES 100000u
#define PCM_RING 0x1800 /* the driver's four 256-byte PCM buffers, read through BC' */
#define PCM_RING_SIZE 0x400
#define IDLE_LOOP (YMM_PARAMS + 0x7C) /* the frame-wait loop's 16-bit iteration counter */

typedef struct RpRead { uint32_t addr; uint8_t val; } RpRead;     /* addr bit 31: 68000 bus (ROM window) */
typedef struct RpWrite { uint16_t addr; uint8_t val; } RpWrite;
typedef struct RpEvent { uint32_t at; uint8_t kind, val; } RpEvent; /* kind 0..3 YM port, 4 PSG, 5 bank bit */
typedef struct RpObs { uint16_t at; uint8_t b; } RpObs;          /* B' read after `at` relative reads */

/* One recorded pass: from an opcode fetch at `start` to the next at `exit`, `len` cycles. */
typedef struct RpEntry {
    uint16_t start, exit;
    uint32_t len;
    z80_t in, out;       /* the Z80 at the two fetches */
    uint64_t out_pins;
    uint16_t ptr;        /* BC' (the PCM read pointer) at entry */
    uint16_t ym_addr_in;
    uint32_t bank_in;
    uint8_t bank_free;   /* the pass sets all 9 bank bits before reading ROM: the entry bank is no input */
    uint8_t r_loaded;    /* LD R,A in the pass: out.r is exact; else R advances by r_steps */
    uint8_t r_steps;
    uint8_t shared;      /* reads memory the 68000 side changes between ticks: may not straddle a tick */
    uint16_t nrel, nobs;
    uint32_t nreads, nwrites, nevents;
    const uint8_t* rel;  /* values of the relative reads (BC' ring) */
    const RpObs* obs;
    const RpRead* reads; /* first reads of each byte, before any write of it */
    const RpWrite* writes; /* final value of every byte written (applied at the pass end) */
    const RpEvent* events;
    uint8_t ring_written[PCM_RING_SIZE / 8];
} RpEntry;

typedef struct RpRecorder {
    int on;
    RpEntry e;
    uint32_t cycle;
    uint16_t ptr;        /* BC' as the relative reads advance it */
    int rel_pending;     /* LD A,(BC) on BC' fetched: its data read is relative */
    int prev_ed;         /* the last fetched byte was an ED prefix */
    int bank_writes;
    int counter_read;    /* frame-wait pass: IDLE_LOOP as read, -1 before */
    uint8_t written[0x2000 / 8], seen[0x2000 / 8];
    RpRead reads[RP_MAX_READS];
    RpEvent events[RP_MAX_EVENTS];
    uint8_t rel[RP_MAX_REL];
    RpObs obs[RP_MAX_OBS];
} RpRecorder;

typedef struct RpPlayer {
    int active, resume;  /* resume: the exit state is installed, its fetch is the next cycle */
    const RpEntry* e;
    uint32_t at, next;   /* cycles done, next event */
    z80_t out;
    uint16_t counter;
} RpPlayer;

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
    /* pass replay */
    int rp_enabled;
    RpEntry rp[RP_ENTRIES];
    int nrp;
    uint8_t* arena;
    uint32_t arena_used;
    RpRecorder rec;
    RpPlayer play;
    uint64_t rp_hits, rp_cycles, rp_recorded;
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

/* `n` cycles of chips_cycle from blip time t, jumping from one PSG step or YM2612 sample to the next. */
static void chips_run(YmMachine* m, uint32_t n, int emit, uint32_t t) {
    while (n) {
        const uint32_t to_psg = PSG_DIV - m->psg_div;
        const uint32_t to_ym = (YMM_MCLK_PER_YM_SAMPLE - m->ym_acc + YMM_MCLK_PER_Z80 - 1) / YMM_MCLK_PER_Z80;
        uint32_t k = n < to_psg ? n : to_psg;
        if (to_ym < k) k = to_ym;
        m->mclk += YMM_MCLK_PER_Z80 * (uint64_t)k;
        m->cycle_in_tick += k;
        m->psg_div += k;
        m->ym_acc += YMM_MCLK_PER_Z80 * k;
        t += k;
        n -= k;
        if (m->psg_div == PSG_DIV) {
            m->psg_div = 0;
            psg_step(m, emit, t - 1);
        }
        if (m->ym_acc >= YMM_MCLK_PER_YM_SAMPLE) {
            m->ym_acc -= YMM_MCLK_PER_YM_SAMPLE;
            ym_sample(m, emit, t - 1);
        }
    }
}

/* ---- pass replay ---------------------------------------------------------------------------------
 * Without PCM the driver spends ~92 % of its cycles in two pieces of fixed code: the PCM mixer (pcm_mix, ~47 k
 * cycles per 256 silent samples) and the frame-wait loop (sync_frame_loop, 254 cycles per iteration). Neither reads
 * the music. The machine records one execution of such a pass - from the opcode fetch at one of the two labels to
 * the next fetch at either - with everything it reads and writes and its chip writes by cycle, and replays it when
 * a pass starts with the same inputs: the chips run alone for the pass's cycles and receive its writes at the
 * same cycles, its memory writes land, and the Z80 resumes in the recorded exit state. Inputs: the registers
 * (those a pass loads before using are free), the first read of every byte, the YM address latch, the bank when
 * read before set, and the PCM read pointer BC': its reads are re-based (values compared, never inside bytes the
 * pass writes) and its page compared wherever the pass reads B'. The frame-wait counter is incremented, not
 * compared. A pass reading memory the 68000 side changes between ticks (PENDING_FRM) only replays if it ends
 * before the tick does. Exact by construction; the host test compares replayed and fully stepped machines bit for
 * bit (samples, chip writes, Z80 state and RAM between passes). */

static int ram_shared(uint16_t a) { return a == YMM_PENDING_FRM || a == YMM_REM_LOOP; }
static uint16_t ring_add(uint16_t p, uint32_t n) { return (uint16_t)(PCM_RING + ((p - PCM_RING + n) & (PCM_RING_SIZE - 1))); }
static int in_ring(uint16_t p) { return p >= PCM_RING && p < PCM_RING + PCM_RING_SIZE; }
static int bit(const uint8_t* map, uint32_t i) { return map[i >> 3] >> (i & 7) & 1; }
static void set_bit(uint8_t* map, uint32_t i) { map[i >> 3] |= (uint8_t)(1u << (i & 7)); }

static void rec_start(YmMachine* m, uint16_t pc) {
    RpRecorder* r = &m->rec;
    if (!in_ring(m->cpu.bc2) || m->nrp == RP_ENTRIES) return;
    memset(&r->e, 0, sizeof r->e);
    r->e.start = pc;
    r->e.in = m->cpu;
    r->e.ptr = r->ptr = m->cpu.bc2;
    r->e.ym_addr_in = m->ym_addr;
    r->e.bank_in = m->bank;
    r->e.bank_free = 1;
    memset(r->written, 0, sizeof r->written);
    memset(r->seen, 0, sizeof r->seen);
    r->cycle = 0;
    r->rel_pending = r->prev_ed = r->bank_writes = 0;
    r->counter_read = -1;
    r->on = 1;
}

/* Recording: an opcode fetch or memory read of the pass (pins = the request, d = the byte). */
static void rec_read(YmMachine* m, uint64_t pins, uint16_t a, uint8_t d) {
    RpRecorder* r = &m->rec;
    RpEntry* e = &r->e;
    if (a >= 0x4000 && a < 0x6000) { r->on = 0; return; } /* YM status: depends on time */
    if (a >= 0x8000) {
        const uint32_t abs = m->bank << 15 | (a & 0x7FFF);
        if (r->bank_writes < 9) e->bank_free = 0;
        if (abs >= YMM_RING && abs < YMM_RING + YMM_RING_SIZE) e->shared = 1;
        if (e->nreads == RP_MAX_READS) { r->on = 0; return; }
        r->reads[e->nreads++] = (RpRead){0x80000000u | abs, d};
        return;
    }
    if (a >= 0x4000) return; /* $6000-$7FFF reads: always $FF */
    const uint16_t ra = a & 0x1FFF;
    if (pins & Z80_M1) {
        if (r->prev_ed) {
            if (d == 0x5F) { r->on = 0; return; } /* LD A,R: R would be an input */
            if (d == 0x4F) e->r_loaded = 1;       /* LD R,A */
        }
        r->prev_ed = !m->cpu.prefix_active && d == 0xED;
        if (!m->cpu.prefix_active && m->cpu.bc == r->ptr) { /* EXX'd: BC holds the PCM read pointer */
            if (d == 0x0A) r->rel_pending = 1;             /* LD A,(BC) */
            else if (d == 0x79) { r->on = 0; return; }     /* LD A,C */
            else if (d == 0x78) {                          /* LD A,B */
                if (e->nobs == RP_MAX_OBS) { r->on = 0; return; }
                r->obs[e->nobs++] = (RpObs){e->nrel, m->cpu.b};
            }
        }
    } else if (r->rel_pending) {
        r->rel_pending = 0;
        if (ra != r->ptr || bit(r->written, ra) || e->nrel == RP_MAX_REL) { r->on = 0; return; }
        r->rel[e->nrel++] = d;
        r->ptr = ring_add(r->ptr, 1);
        return;
    }
    if (e->start == XGM_DRIVER_SYNC_FRAME_LOOP && (ra == IDLE_LOOP || ra == IDLE_LOOP + 1)) {
        if (bit(r->written, ra)) { r->on = 0; return; }
        r->counter_read = (r->counter_read < 0 ? 0 : r->counter_read) | d << (ra == IDLE_LOOP ? 0 : 8);
        return;
    }
    if (ram_shared(ra)) e->shared = 1;
    if (bit(r->written, ra) || bit(r->seen, ra)) return;
    set_bit(r->seen, ra);
    if (e->nreads == RP_MAX_READS) { r->on = 0; return; }
    r->reads[e->nreads++] = (RpRead){ra, d};
}

static void rec_event(YmMachine* m, int kind, uint8_t v) {
    RpEntry* e = &m->rec.e;
    if (e->nevents == RP_MAX_EVENTS) { m->rec.on = 0; return; }
    m->rec.events[e->nevents++] = (RpEvent){m->rec.cycle, (uint8_t)kind, v};
}

static void rec_write(YmMachine* m, uint16_t a, uint8_t d) {
    RpRecorder* r = &m->rec;
    if (a < 0x4000) {
        const uint16_t ra = a & 0x1FFF;
        if (ram_shared(ra) || ra == YMM_MODIFYING_F || ra == YMM_STATUS) { r->on = 0; return; }
        set_bit(r->written, ra);
    } else if (a < 0x6000) {
        rec_event(m, a & 3, d);
    } else if (a < 0x6100) {
        rec_event(m, 5, d);
        ++r->bank_writes;
    } else if ((a & 0xFFF9) == 0x7F11) {
        rec_event(m, 4, d);
    }
}

static void* arena_take(YmMachine* m, size_t bytes) {
    const uint32_t at = (m->arena_used + 7u) & ~7u;
    if (at + bytes > RP_ARENA) return NULL;
    m->arena_used = at + (uint32_t)bytes;
    return m->arena + at;
}

/* The recorded pass ends at the fetch at `pc` (pins = that fetch): keep it if it is replayable. */
static void rec_finish(YmMachine* m, uint64_t pins, uint16_t pc) {
    RpRecorder* r = &m->rec;
    RpEntry* e = &r->e;
    r->on = 0;
    const int idle = e->start == XGM_DRIVER_SYNC_FRAME_LOOP;
    if (!r->cycle || r->rel_pending || m->cpu.bc2 != r->ptr) return;
    if (!e->r_loaded && ((m->cpu.r ^ e->in.r) & 0x80)) return;
    if (idle && (r->counter_read < 0 || m->cpu.hl != ((r->counter_read + 1) & 0xFFFF))) return;
    /* registers neither pass touches are carried through, not compared (regs_match) */
    if (m->cpu.iy != e->in.iy || m->cpu.af2 != e->in.af2 || (idle && (m->cpu.bc != e->in.bc || m->cpu.sp != e->in.sp)))
        return;
    e->exit = pc;
    e->len = r->cycle;
    e->out = m->cpu;
    e->out_pins = pins;
    e->r_steps = (uint8_t)((m->cpu.r - e->in.r) & 0x7F);
    uint32_t nwrites = 0;
    for (uint32_t a = 0; a < 0x2000; ++a)
        if (bit(r->written, a) && !(idle && (a == IDLE_LOOP || a == IDLE_LOOP + 1))) ++nwrites;
    RpRead* reads = (RpRead*)arena_take(m, sizeof(RpRead) * e->nreads);
    RpWrite* writes = (RpWrite*)arena_take(m, sizeof(RpWrite) * nwrites);
    RpEvent* events = (RpEvent*)arena_take(m, sizeof(RpEvent) * e->nevents);
    RpObs* obs = (RpObs*)arena_take(m, sizeof(RpObs) * e->nobs);
    uint8_t* rel = (uint8_t*)arena_take(m, e->nrel);
    if (!reads || !writes || !events || !obs || !rel) return;
    memcpy(reads, r->reads, sizeof(RpRead) * e->nreads);
    memcpy(events, r->events, sizeof(RpEvent) * e->nevents);
    memcpy(obs, r->obs, sizeof(RpObs) * e->nobs);
    memcpy(rel, r->rel, e->nrel);
    for (uint32_t a = 0, i = 0; a < 0x2000; ++a) {
        if (!bit(r->written, a) || (idle && (a == IDLE_LOOP || a == IDLE_LOOP + 1))) continue;
        writes[i++] = (RpWrite){(uint16_t)a, m->ram[a]};
        if (in_ring((uint16_t)a)) set_bit(e->ring_written, a - PCM_RING);
    }
    e->nwrites = nwrites;
    e->reads = reads;
    e->writes = writes;
    e->events = events;
    e->obs = obs;
    e->rel = rel;
    m->rp[m->nrp++] = *e;
    ++m->rp_recorded;
}

/* Registers a pass may read: all but those it loads before use (the mixer: AF BC DE HL SP; the frame wait: AF HL),
 * those it never touches (IY, AF'; the frame wait: BC, SP: carried through), WZ and the decoder's scratch; BC' and R
 * are handled apart. */
static int regs_match(const z80_t* a, const z80_t* b, int mix) {
    if (a->ix != b->ix || a->de2 != b->de2 || a->hl2 != b->hl2 || a->i != b->i || a->im != b->im || a->iff1 != b->iff1 ||
        a->iff2 != b->iff2 || a->int_bits != b->int_bits || a->step != b->step || a->hlx_idx != b->hlx_idx ||
        a->prefix_active != b->prefix_active)
        return 0;
    return mix || a->de == b->de;
}

/* The entry whose inputs the Z80 meets now (the caller checks that a shared pass ends within the tick). */
static const RpEntry* rp_find(const YmMachine* m, uint16_t pc) {
    const uint16_t ptr = m->cpu.bc2;
    if (!in_ring(ptr)) return NULL;
    for (int i = 0; i < m->nrp; ++i) {
        const RpEntry* e = &m->rp[i];
        if (e->start != pc || !regs_match(&e->in, &m->cpu, pc == XGM_DRIVER_PCM_MIX) || m->ym_addr != e->ym_addr_in ||
            (!e->bank_free && m->bank != e->bank_in))
            continue;
        int ok = 1;
        for (uint32_t k = 0; ok && k < e->nobs; ++k) ok = ring_add(ptr, e->obs[k].at) >> 8 == e->obs[k].b;
        for (uint32_t k = 0; ok && k < e->nrel; ++k) {
            const uint16_t a = ring_add(ptr, k);
            ok = m->ram[a] == e->rel[k] && !bit(e->ring_written, a - PCM_RING);
        }
        for (uint32_t k = 0; ok && k < e->nreads; ++k) {
            const RpRead* rd = &e->reads[k];
            ok = (rd->addr & 0x80000000u ? rom_read(m, rd->addr & 0x7FFFFFFFu) : m->ram[rd->addr]) == rd->val;
        }
        if (ok) return e;
    }
    return NULL;
}

static void rp_begin(YmMachine* m, const RpEntry* e) {
    RpPlayer* p = &m->play;
    p->active = 1;
    p->e = e;
    p->at = p->next = 0;
    p->out = e->out;
    p->out.bc2 = ring_add(m->cpu.bc2, e->nrel);
    p->out.iy = m->cpu.iy;
    p->out.af2 = m->cpu.af2;
    if (e->start == XGM_DRIVER_SYNC_FRAME_LOOP) {
        p->out.bc = m->cpu.bc;
        p->out.sp = m->cpu.sp;
    }
    if (!e->r_loaded) p->out.r = (uint8_t)((m->cpu.r & 0x80) | ((m->cpu.r + e->r_steps) & 0x7F));
    if (e->start == XGM_DRIVER_SYNC_FRAME_LOOP) {
        p->counter = (uint16_t)(m->ram[IDLE_LOOP] + (m->ram[IDLE_LOOP + 1] << 8) + 1);
        if (e->out.addr == e->out.hl) p->out.addr = p->counter;
        p->out.hl = p->counter;
    }
    ++m->rp_hits;
}

/* Up to `budget` cycles of the replayed pass from blip time t; returns the cycles done. At its end the pass's
 * memory writes land and the Z80 takes the exit state (its fetch is the next cycle). */
static uint32_t replay(YmMachine* m, uint32_t budget, int emit, uint32_t t) {
    RpPlayer* p = &m->play;
    const RpEntry* e = p->e;
    uint32_t done = 0;
    while (done < budget) {
        if (p->next < e->nevents && e->events[p->next].at == p->at) {
            const RpEvent* ev = &e->events[p->next++];
            if (ev->kind < 4) ym_bus_write(m, ev->kind, ev->val);
            else if (ev->kind == 4) psg_bus_write(m, ev->val);
            else bank_write(m, ev->val);
            continue;
        }
        if (p->at == e->len) {
            for (uint32_t k = 0; k < e->nwrites; ++k) m->ram[e->writes[k].addr] = e->writes[k].val;
            if (e->start == XGM_DRIVER_SYNC_FRAME_LOOP) {
                m->ram[IDLE_LOOP] = (uint8_t)p->counter;
                m->ram[IDLE_LOOP + 1] = (uint8_t)(p->counter >> 8);
            }
            m->cpu = p->out;
            m->pins = e->out_pins;
            p->active = 0;
            p->resume = 1;
            m->rp_cycles += e->len;
            break;
        }
        const uint32_t until = p->next < e->nevents ? e->events[p->next].at : e->len;
        uint32_t n = until - p->at;
        if (n > budget - done) n = budget - done;
        chips_run(m, n, emit, t + done);
        p->at += n;
        done += n;
    }
    return done;
}

/* ---- the Z80 ---------------------------------------------------------------------------------------- */

/* The bus access of one Z80 cycle (pins as z80_tick returned them). */
static inline uint64_t bus(YmMachine* m, uint64_t pins) {
    if (pins & Z80_MREQ) {
        const uint16_t a = Z80_GET_ADDR(pins);
        if (pins & Z80_RD) {
            uint8_t d = 0xFF;
            if (a < 0x4000) d = m->ram[a & 0x1FFF];
            else if (a < 0x6000) d = ymo_read_status(m->opn, m->mclk);
            else if (a >= 0x8000) d = rom_read(m, m->bank << 15 | (a & 0x7FFF));
            if (m->rec.on) rec_read(m, pins, a, d);
            Z80_SET_DATA(pins, d);
        } else if (pins & Z80_WR) {
            const uint8_t d = Z80_GET_DATA(pins);
            if (m->rec.on) rec_write(m, a, d);
            if (a < 0x4000) m->ram[a & 0x1FFF] = d;
            else if (a < 0x6000) ym_bus_write(m, a & 3, d);
            else if (a < 0x6100) bank_write(m, d);
            else if ((a & 0xFFF9) == 0x7F11) psg_bus_write(m, d);
        }
    } else if (pins & Z80_IORQ) {
        m->rec.on = 0;
        if (pins & Z80_RD) Z80_SET_DATA(pins, 0xFF);
    }
    return pins;
}

/* `cycles` Z80 cycles with the chips; output into the blip buffers when `emit`, at Z80-cycle times from `t0`. */
static void run(YmMachine* m, uint32_t cycles, int emit, uint32_t t0) {
    uint64_t pins = m->pins;
    uint32_t c = 0;
    while (c < cycles) {
        if (m->play.active) {
            c += replay(m, cycles - c, emit, t0 + c);
            pins = m->pins;
            continue;
        }
        if (m->play.resume) m->play.resume = 0; /* pins: the exit fetch, as z80_tick returned it when recorded */
        else pins = z80_tick(&m->cpu, pins);
        if (m->rp_enabled && (pins & (Z80_M1 | Z80_MREQ | Z80_RD)) == (Z80_M1 | Z80_MREQ | Z80_RD) && !m->cpu.prefix_active) {
            const uint16_t pc = Z80_GET_ADDR(pins);
            if (pc == XGM_DRIVER_PCM_MIX || pc == XGM_DRIVER_SYNC_FRAME_LOOP) {
                if (m->rec.on) rec_finish(m, pins, pc);
                const RpEntry* e = rp_find(m, pc);
                if (e && (!e->shared || e->len <= cycles - c)) {
                    rp_begin(m, e);
                    m->pins = pins;
                    continue;
                }
                if (!e) rec_start(m, pc);
            }
        }
        pins = bus(m, pins);
        chips_cycle(m, emit, t0 + c);
        if (m->rec.on && ++m->rec.cycle > RP_MAX_CYCLES) m->rec.on = 0;
        ++c;
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
    m->rec.on = m->play.active = m->play.resume = 0;
    const int was_logging = m->log_on, was_replaying = m->rp_enabled;
    m->log_on = 0;
    m->rp_enabled = 0; /* the 68000 side writes the driver's tables right after the boot */
    uint32_t boot = 0;
    while (!(m->ram[YMM_STATUS] & 0x80)) {
        run(m, 256, 0, 0);
        if ((boot += 256) > 200000) {
            m->rp_enabled = was_replaying;
            return -1;
        }
    }
    m->rp_enabled = was_replaying;
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
    m->rp_enabled = 1;
    m->arena = (uint8_t*)malloc(RP_ARENA);
    m->opn = ymo_create();
    for (int i = 0; i < 2; ++i) m->blip[i] = blip_new((int)YMM_MAX_TICK_FRAMES + 64);
    if (!m->arena || !m->opn || !m->blip[0] || !m->blip[1] || machine_reset(m, YM_NTSC)) {
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
    free(m->arena);
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
void ymm_set_replay(YmMachine* m, int enabled) {
    m->rp_enabled = enabled != 0;
    if (!enabled) m->rec.on = 0;
}
void ymm_replay_stats(const YmMachine* m, YmmReplayStats* s) {
    s->entries = m->nrp;
    s->arena_bytes = m->arena_used;
    s->recorded = m->rp_recorded;
    s->hits = m->rp_hits;
    s->cycles = m->rp_cycles;
}
int ymm_replaying(const YmMachine* m) { return m->play.active; }
const void* ymm_z80(const YmMachine* m) { return &m->cpu; }
