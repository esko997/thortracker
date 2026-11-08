/* C64 music machine: m6502 + reSID running the GoatTracker 2 playroutine (see sid_machine.h). */
#include "sid_machine.h"

#include <stdlib.h>
#include <string.h>

#include "m6502.h"
#include "sid_resid.h"

#define INIT_BUDGET 100000u  /* mt_init (and a PSID's init) */
#define LOG_CAPACITY 4096    /* SID writes buffered between sm_log calls */
#define MAX_WRITES 5120      /* SID writes in one tick (a write takes at least 4 cycles) */
#define SETTLE_SECONDS_TICKS 150 /* seek: the last ~3 s of ticks (x the speed multiplier) reach the SID clocked */
#define SETTLE_TICKS 512     /* ring capacity in ticks */
#define SETTLE_WRITES 16384  /* ring capacity in writes (the player writes at most ~25 per tick) */
#define OUTPUT_GAIN 1.0f     /* reSID's 16-bit output, already DC-free (its external filter) */
#define CHN(sym, c) m->ram[SID_MT_##sym + 7 * (c)]
#define GT_NOTE 0x60
#define GT_LASTNOTE 0xBC
#define GT_REST 0xBD
#define GT_KEYOFF 0xBE
#define GT_KEYON 0xBF

_Static_assert(SM_MAX_SAMPLE_RATE * (uint64_t)SID_PAL_FRAME_CYCLES / SM_CPU_HZ + 2 <= SM_MAX_TICK_FRAMES,
               "a tick fits the host buffer");
_Static_assert(MAX_WRITES * 4 > SID_PAL_FRAME_CYCLES, "every write of a tick fits");
_Static_assert(SID_AUDITION_RAM + 0x20 <= SID_PLAYER_IDLE, "audition data below the idle address");

/* Documented 6502 opcodes except BRK (never executed by the player). Anything else fetched by the CPU
 * is a fault. */
static const uint8_t kOfficialOpcodes[] = {
    0x01, 0x05, 0x06, 0x08, 0x09, 0x0A, 0x0D, 0x0E, 0x10, 0x11, 0x15, 0x16, 0x18, 0x19, 0x1D, 0x1E, 0x20, 0x21,
    0x24, 0x25, 0x26, 0x28, 0x29, 0x2A, 0x2C, 0x2D, 0x2E, 0x30, 0x31, 0x35, 0x36, 0x38, 0x39, 0x3D, 0x3E, 0x40,
    0x41, 0x45, 0x46, 0x48, 0x49, 0x4A, 0x4C, 0x4D, 0x4E, 0x50, 0x51, 0x55, 0x56, 0x58, 0x59, 0x5D, 0x5E, 0x60,
    0x61, 0x65, 0x66, 0x68, 0x69, 0x6A, 0x6C, 0x6D, 0x6E, 0x70, 0x71, 0x75, 0x76, 0x78, 0x79, 0x7D, 0x7E, 0x81,
    0x84, 0x85, 0x86, 0x88, 0x8A, 0x8C, 0x8D, 0x8E, 0x90, 0x91, 0x94, 0x95, 0x96, 0x98, 0x99, 0x9A, 0x9D, 0xA0,
    0xA1, 0xA2, 0xA4, 0xA5, 0xA6, 0xA8, 0xA9, 0xAA, 0xAC, 0xAD, 0xAE, 0xB0, 0xB1, 0xB4, 0xB5, 0xB6, 0xB8, 0xB9,
    0xBA, 0xBC, 0xBD, 0xBE, 0xC0, 0xC1, 0xC4, 0xC5, 0xC6, 0xC8, 0xC9, 0xCA, 0xCC, 0xCD, 0xCE, 0xD0, 0xD1, 0xD5,
    0xD6, 0xD8, 0xD9, 0xDD, 0xDE, 0xE0, 0xE1, 0xE4, 0xE5, 0xE6, 0xE8, 0xE9, 0xEA, 0xEC, 0xED, 0xEE, 0xF0, 0xF1,
    0xF5, 0xF6, 0xF8, 0xF9, 0xFD, 0xFE,
};
_Static_assert(sizeof kOfficialOpcodes == 150, "151 documented opcodes minus BRK");

typedef struct SidWrite {
    uint32_t time; /* CPU cycle within the tick */
    uint8_t reg, value;
} SidWrite;

struct SidMachine {
    m6502_t cpu;
    SidResid* sid;
    uint32_t sample_rate;
    uint32_t time;          /* CPU cycle within the current call */
    uint32_t tick_cycles;
    uint16_t play_addr, cia_latch;
    int faulted, raw;
    int preroll;            /* the player's first tick 0 (pattern load only) is still to come */
    int order, row;         /* row most recently started (-1: none yet in `order`) */
    uint32_t row_ticks, ticks;
    int audition, aud_channel, released, ended;
    uint32_t aud_ticks;
    unsigned muted;         /* bit per voice */
    uint64_t driver_cycles;
    uint32_t last_call, max_call;
    int logging, log_len, nwrites;
    SidImageInfo info;      /* of the image playing */
    uint8_t slots[64];      /* its slot -> player instrument map */
    uint8_t official[32];
    uint8_t log[2 * LOG_CAPACITY];
    SidWrite writes[MAX_WRITES];
    SidWrite settle[SETTLE_WRITES]; /* seek: writes of the deferred ticks (ring) */
    uint16_t settle_count[SETTLE_TICKS];
    int settle_head, settle_ticks, settle_first, settle_writes;
    int16_t samples[SM_MAX_TICK_FRAMES + 16];
    uint8_t ram[0x10000];
};

/* ---- bus ---------------------------------------------------------------------------------- */

static uint8_t bus_read(const SidMachine* m, uint16_t addr) {
    if (addr >= 0xD000 && addr < 0xE000) return 0;
    return m->ram[addr];
}

static void bus_write(SidMachine* m, uint16_t addr, uint8_t v) {
    if (addr >= 0xD400 && addr < 0xD800) {
        const uint8_t reg = (uint8_t)(addr & 0x1F);
        if (m->nwrites < MAX_WRITES) {
            m->writes[m->nwrites].time = m->time;
            m->writes[m->nwrites].reg = reg;
            m->writes[m->nwrites].value = v;
            ++m->nwrites;
        }
        if (m->logging && m->log_len < LOG_CAPACITY) {
            m->log[2 * m->log_len] = reg;
            m->log[2 * m->log_len + 1] = v;
            ++m->log_len;
        }
    } else if (addr == 0xDC04) {
        m->cia_latch = (uint16_t)((m->cia_latch & 0xFF00) | v);
    } else if (addr == 0xDC05) {
        m->cia_latch = (uint16_t)((m->cia_latch & 0x00FF) | v << 8);
    } else if (addr < 0xD000 || addr >= 0xE000) {
        m->ram[addr] = v;
    }
}

static int official(const SidMachine* m, uint8_t op) { return (m->official[op >> 3] >> (op & 7)) & 1; }

/* Runs the routine at `addr` (A = a) until it returns to SID_PLAYER_IDLE. One bus access per CPU cycle
 * at call time m->time. */
static int call(SidMachine* m, uint16_t addr, uint8_t a, uint32_t budget) {
    if (m->faulted) return SM_ERR_FAULT;
    m6502_t* c = &m->cpu;
    const uint16_t ret = SID_PLAYER_IDLE - 1; /* RTS adds one */
    m->ram[0x1FD] = (uint8_t)(ret >> 8);
    m->ram[0x1FC] = (uint8_t)ret;
    c->S = 0xFB;
    c->A = a;
    c->X = 0;
    c->Y = 0;
    c->P = M6502_IF | M6502_XF;
    c->PC = addr;
    c->brk_flags = 0;
    uint8_t op = bus_read(m, addr);
    if (!official(m, op) || addr == SID_PLAYER_TRAP) goto fault;
    uint64_t pins = M6502_SYNC | M6502_RW;
    M6502_SET_ADDR(pins, addr);
    M6502_SET_DATA(pins, op);
    uint32_t cycles = 1; /* the opcode fetch above */
    ++m->time;
    for (;;) {
        pins = m6502_tick(c, pins);
        const uint16_t at = M6502_GET_ADDR(pins);
        if (pins & M6502_RW) {
            if ((pins & M6502_SYNC) && at == SID_PLAYER_IDLE) break;
            const uint8_t v = bus_read(m, at);
            if ((pins & M6502_SYNC) && (at == SID_PLAYER_TRAP || !official(m, v))) goto fault;
            M6502_SET_DATA(pins, v);
        } else {
            bus_write(m, at, M6502_GET_DATA(pins));
        }
        ++m->time;
        if (++cycles > budget) goto fault;
    }
    m->last_call = cycles;
    if (cycles > m->max_call) m->max_call = cycles;
    m->driver_cycles += cycles;
    return SM_OK;
fault:
    m->faulted = 1;
    return SM_ERR_FAULT;
}

/* ---- lifecycle ---------------------------------------------------------------------------- */

SidMachine* sm_create_sampling(uint32_t sample_rate, int method) {
    if (sample_rate < 8000 || sample_rate > SM_MAX_SAMPLE_RATE) return NULL;
    SidMachine* m = (SidMachine*)calloc(1, sizeof *m);
    if (!m) return NULL;
    m->sid = sid_resid_new((double)SM_CPU_HZ, sample_rate, SID_RESID_8580,
                           method == SM_INTERPOLATE ? SID_RESID_INTERPOLATE : SID_RESID_RESAMPLE);
    if (!m->sid) {
        free(m);
        return NULL;
    }
    m->sample_rate = sample_rate;
    for (size_t i = 0; i < sizeof kOfficialOpcodes; ++i) m->official[kOfficialOpcodes[i] >> 3] |= (uint8_t)(1 << (kOfficialOpcodes[i] & 7));
    m6502_desc_t desc;
    memset(&desc, 0, sizeof desc); /* the 6510 has decimal mode */
    m6502_init(&m->cpu, &desc);
    m->tick_cycles = SID_PAL_FRAME_CYCLES;
    m->faulted = 1; /* until a song is started */
    return m;
}

SidMachine* sm_create(uint32_t sample_rate) { return sm_create_sampling(sample_rate, SM_RESAMPLE); }

void sm_destroy(SidMachine* m) {
    if (!m) return;
    sid_resid_free(m->sid);
    free(m);
}

static int loaded(const SidImage* image) { return image && image->info.loaded == SM_LOADED; }

/* CPU cycles per player call: a PAL frame, or gt2reloc's CIA period in multispeed. */
static uint32_t tick_cycles(int multiplier) {
    return multiplier == 1 ? SID_PAL_FRAME_CYCLES : SID_CIA_PAL_LATCH / (uint32_t)multiplier + 1u;
}

/* The image header's values into the player's parameter operands. */
static void patch_parameters(SidMachine* m, const SidImageInfo* info) {
    m->ram[SID_PATCH_FIRSTNOHRINSTR] = info->firstnohr;
    m->ram[SID_PATCH_FIRSTLEGATOINSTR] = info->firstlegato;
    m->ram[SID_PATCH_ADPARAM] = info->hr_ad;
    m->ram[SID_PATCH_SRPARAM] = info->hr_sr;
    m->ram[SID_PATCH_DEFAULTTEMPO] = info->default_tempo;
}

static void power_on(SidMachine* m, int model) {
    memset(m->ram, 0, sizeof m->ram);
    for (int v = 0xFFFA; v < 0x10000; v += 2) {
        m->ram[v] = (uint8_t)SID_PLAYER_TRAP;
        m->ram[v + 1] = (uint8_t)(SID_PLAYER_TRAP >> 8);
    }
    sid_resid_reset(m->sid, model);
    m->muted = 0;
    m->faulted = 0;
    m->raw = 0;
    m->time = 0;
    m->ticks = 0;
    m->driver_cycles = 0;
    m->last_call = m->max_call = 0;
    m->log_len = 0;
    m->nwrites = 0;
    m->cia_latch = 0;
    m->preroll = 1;
    m->order = 0;
    m->row = -1;
    m->row_ticks = 0;
    m->audition = m->released = m->ended = 0;
    m->aud_ticks = 0;
    m->settle_head = m->settle_ticks = m->settle_first = m->settle_writes = 0;
}

/* Writes made outside a tick (init) reach the SID at once. */
static void flush_writes(SidMachine* m) {
    for (int i = 0; i < m->nwrites; ++i) sid_resid_write(m->sid, m->writes[i].reg, m->writes[i].value);
    m->nwrites = 0;
}

/* Power-on with `image`: player with the image's parameters, region, then the player's init. */
static int reset(SidMachine* m, const SidImage* image) {
    const SidImageInfo* info = &image->info;
    power_on(m, info->model);
    memcpy(m->ram + SID_PLAYER_BASE, sid_player_code, SID_PLAYER_SIZE);
    patch_parameters(m, info);
    memcpy(m->ram + SID_REGION_BASE, sm_region(image), SM_REGION_SIZE);
    m->info = *info;
    memcpy(m->slots, image->bytes + SM_SLOT_MAP, sizeof m->slots);
    m->play_addr = SID_PLAYER_PLAY;
    m->tick_cycles = tick_cycles(info->multiplier);
    if (call(m, SID_PLAYER_INIT, 0, INIT_BUDGET) != SM_OK) return SM_ERR_FAULT;
    flush_writes(m);
    return SM_OK;
}

int sm_power_on(SidMachine* m, const SidImage* image) {
    if (!loaded(image)) return SM_ERR_IMAGE;
    return reset(m, image);
}

int sm_load_psid(SidMachine* m, const uint8_t* psid, size_t size, int model) {
    if (size < 0x7C || (memcmp(psid, "PSID", 4) && memcmp(psid, "RSID", 4))) return SM_ERR_IMAGE;
    const size_t off = (size_t)(psid[6] << 8 | psid[7]);
    uint16_t load = (uint16_t)(psid[8] << 8 | psid[9]);
    const uint16_t init = (uint16_t)(psid[10] << 8 | psid[11]);
    const uint16_t play = (uint16_t)(psid[12] << 8 | psid[13]);
    const int start = psid[16] << 8 | psid[17];
    const uint32_t speed = (uint32_t)psid[18] << 24 | (uint32_t)psid[19] << 16 | (uint32_t)psid[20] << 8 | psid[21];
    if (off >= size || !play) return SM_ERR_IMAGE;
    const uint8_t* data = psid + off;
    size_t n = size - off;
    if (!load) {
        if (n < 2) return SM_ERR_IMAGE;
        load = (uint16_t)(data[0] | data[1] << 8);
        data += 2;
        n -= 2;
    }
    if (load + n > 0x10000) return SM_ERR_IMAGE;
    power_on(m, model);
    memset(&m->info, 0, sizeof m->info);
    memcpy(m->ram + load, data, n);
    m->raw = 1;
    m->play_addr = play;
    m->tick_cycles = SID_PAL_FRAME_CYCLES;
    if (call(m, init, (uint8_t)(start > 0 ? start - 1 : 0), INIT_BUDGET) != SM_OK) return SM_ERR_FAULT;
    flush_writes(m);
    if (((speed >> ((start > 0 ? start - 1 : 0) & 31)) & 1) && m->cia_latch) m->tick_cycles = m->cia_latch + 1u;
    return SM_OK;
}

/* ---- rows --------------------------------------------------------------------------------- */

/* Whether the next play call runs tick 0 of a channel-1 row (not the init call's reset, not the
 * first tick 0 that only loads patterns). */
static int tick0_next(const SidMachine* m) {
    return !m->raw && m->ram[SID_MT_INITSONGNUM + 1] >= 0x80 && CHN(CHNCOUNTER, 0) == 1;
}

static int row_starts(const SidMachine* m) { return !m->audition && !m->preroll && tick0_next(m); }

/* The row after (order, row) in play order: wraps at the order's rows and at the restart order. */
static void next_row(const SidImageInfo* info, int* order, int* row) {
    int o = *order, r = *row + 1;
    if (r >= info->rows[o]) {
        r = 0;
        if (++o >= info->orders) o = info->loop;
    }
    *order = o;
    *row = r;
}

void sm_position(const SidMachine* m, int* order, int* row, int* tick) {
    if (m->raw || m->audition) {
        *order = 0;
        *row = 0;
        *tick = m->ticks > 0x7FFFFFFF ? 0x7FFFFFFF : (int)m->ticks;
        return;
    }
    int o = m->order, r = m->row;
    if (row_starts(m)) {
        next_row(&m->info, &o, &r);
        *tick = 0;
    } else {
        if (r < 0) r = 0;
        *tick = m->row_ticks > 0x7FFFFFFF ? 0x7FFFFFFF : (int)m->row_ticks;
    }
    *order = o;
    *row = r;
}

static int at(const SidMachine* m, int order, int row) {
    if (!row_starts(m)) return 0;
    int o, r, t;
    sm_position(m, &o, &r, &t);
    return o == order && r == row;
}

enum { TICK_SAMPLES, TICK_CLOCKED, TICK_DEFERRED };
static int run_tick(SidMachine* m, int mode, int* count);
static void settle_flush(SidMachine* m);

/* Silent ticks until the next tick plays (order, row), at most `budget` ticks, with the SID deferred
 * (settle window) and caught up at the end. 1 reached, 0 not, SM_ERR_FAULT. */
static int fast_forward(SidMachine* m, int order, int row, int budget) {
    int r = 0;
    for (int i = 0;; ++i) {
        if (at(m, order, row)) {
            r = 1;
            break;
        }
        if (i >= budget) break;
        if (run_tick(m, TICK_DEFERRED, NULL) != SM_OK) return SM_ERR_FAULT;
    }
    settle_flush(m);
    return r;
}

/* After the init call's reset: every channel's orderlist at `order`, the tempo straight play has there
 * (and the funktempo pair), so the player's first tick 0 loads that order's patterns. */
static void place_at_order(SidMachine* m, int order) {
    for (int c = 0; c < SM_CHANNELS; ++c) {
        CHN(CHNSONGPTR, c) = (uint8_t)order;
        CHN(CHNTEMPO, c) = m->info.tempo[order];
    }
    const int funk = m->info.funk[order];
    if (funk) {
        m->ram[SID_MT_FUNKTEMPOTBL] = m->ram[SID_MT_SPEEDLEFTTBL - 1 + funk];
        m->ram[SID_MT_FUNKTEMPOTBL + 1] = m->ram[SID_MT_SPEEDRIGHTTBL - 1 + funk];
    }
    m->order = order;
    m->row = -1;
    m->row_ticks = 0;
}

int sm_start(SidMachine* m, const SidImage* image, int order, int row, int max_ticks, int* seek_mode) {
    *seek_mode = SM_SEEK_FROM_START;
    if (!loaded(image) || order < 0 || row < 0) return SM_ERR_IMAGE;
    const SidImageInfo* info = &image->info;
    if (order >= info->orders || row >= info->rows[order]) return SM_ERR_IMAGE;
    if (reset(m, image) != SM_OK) return SM_ERR_FAULT;
    const int budget = max_ticks < 0 ? 0 : max_ticks;
    int r = fast_forward(m, order, row, budget);
    if (r) return r < 0 ? r : SM_OK;
    if (reset(m, image) != SM_OK) return SM_ERR_FAULT;
    if (sm_tick_silent(m) != SM_OK) return SM_ERR_FAULT; /* the init call's reset */
    place_at_order(m, order);
    *seek_mode = SM_SEEK_FROM_ORDER;
    r = fast_forward(m, order, row, budget);
    return r < 0 ? r : r ? SM_OK : SM_ERR_IMAGE;
}

/* ---- live edits (relocating) ---------------------------------------------------------------- */

/* end[r] = the first row >= r of a table whose left byte is the jump $FF (rows 1..255; 255 if none). */
static void jump_rows(const uint8_t* region, uint16_t left, uint8_t end[256]) {
    end[255] = 255;
    for (int r = 254; r >= 1; --r) end[r] = region[left - SID_REGION_BASE + r - 1] == 0xFF ? (uint8_t)r : end[r + 1];
    end[0] = 0;
}

typedef struct TableMap {
    uint16_t col, left; /* instrument start-row column, the table's left column */
    uint8_t oend[256], nend[256];
} TableMap;

/* A table pointer of the old image (row 1..255, 0 = stopped) in the new one: if it lies in an
 * instrument's program (its start row .. the program's jump row; the channel's own instrument `prefer`
 * first, else the innermost), it keeps its offset in that instrument slot's program in the new image,
 * clamped to the program's last row before its jump (0 if the instrument no longer has one). Other
 * pointers (programs reached only by commands) are kept. Identity when programs did not move. */
static int map_pointer(const SidMachine* m, const SidImage* image, const TableMap* t, int ptr, int prefer) {
    if (!ptr) return 0;
    const uint8_t* oreg = m->ram + SID_REGION_BASE;
    const uint8_t* nreg = sm_region(image);
    int owner = 0, start = 0;
    for (int n = 1; n <= m->info.instruments; ++n) {
        const int s = oreg[t->col - SID_REGION_BASE + n - 1];
        if (!s || s > ptr || ptr > t->oend[s]) continue;
        if (n == prefer) {
            owner = n;
            start = s;
            break;
        }
        if (s > start) {
            owner = n;
            start = s;
        }
    }
    if (!owner) return ptr;
    int slot = 1;
    while (slot < 64 && m->slots[slot] != owner) ++slot;
    if (slot == 64) return ptr;
    const int nn = image->bytes[SM_SLOT_MAP + slot];
    if (!nn) return ptr;
    const int ns = nreg[t->col - SID_REGION_BASE + nn - 1];
    if (!ns) return 0;
    const int last = t->nend[ns] > ns ? t->nend[ns] - 1 : ns;
    const int np = ns + (ptr - start);
    return np > last ? last : np;
}

/* Whether channel c has fetched the row after the one playing: the player fetches when its counter
 * equals the gate timer (counting down to tick 0), so from that tick until the next tick 0. */
static int fetched_next(const SidMachine* m, int c) {
    const uint8_t counter = CHN(CHNCOUNTER, c);
    return counter != 0 && counter <= CHN(CHNGATETIMER, c);
}

/* Channel c's sequencer state in the new data at the playhead (order o, row r = the row playing, -1:
 * none yet in o): what straight play of the new image leaves there. */
static void place_channel(SidMachine* m, const SidImage* image, int c, int o, int r, int fetched) {
    const SidImageInfo* ni = &image->info;
    int fo = o, fr = r; /* the last row fetched (fr -1: none) */
    if (fetched) next_row(ni, &fo, &fr);
    int loaded = fo; /* order whose pattern is in mt_chnpattnum */
    SmFetch f = {0, 0, 0, -1, -1, GT_REST};
    if (fr >= 0) sm_fetch_state(sm_region(image), ni, c, fo, fr, &f);
    if (!fetched && fr >= 0 && fr == ni->rows[fo] - 1) { /* its last row started: the sequencer advanced */
        int no = fo, nr = fr;
        next_row(ni, &no, &nr);
        loaded = no;
        f.pattptr = f.packedrest = 0;
    }
    CHN(CHNPATTNUM, c) = ni->pattern[c][loaded];
    CHN(CHNSONGPTR, c) = (uint8_t)(loaded + 1);
    CHN(CHNPATTPTR, c) = f.pattptr;
    CHN(CHNPACKEDREST, c) = f.packedrest;
    if (f.instrument) CHN(CHNINSTR, c) = f.instrument;
    if (f.fx >= 0) {
        CHN(CHNNEWFX, c) = (uint8_t)f.fx;
        if (f.fx) CHN(CHNNEWPARAM, c) = (uint8_t)f.param;
    }
    if (fetched) { /* the fetched row's note waits for its tick 0 */
        CHN(CHNNEWNOTE, c) = (uint8_t)(f.note >= GT_NOTE && f.note <= GT_LASTNOTE ? f.note : 0);
        if (f.note == GT_KEYOFF) CHN(CHNGATE, c) = 0xFE;
        if (f.note == GT_KEYON) CHN(CHNGATE, c) = 0xFF;
    }
}

static void funk_pair(const uint8_t* region, int funk, uint8_t pair[2]) {
    pair[0] = funk ? region[SID_MT_SPEEDLEFTTBL - SID_REGION_BASE + funk - 1] : 0;
    pair[1] = funk ? region[SID_MT_SPEEDRIGHTTBL - SID_REGION_BASE + funk - 1] : 0;
}

void sm_apply_image(SidMachine* m, const SidImage* image) {
    if (m->faulted || m->audition || m->raw || !loaded(image)) return;
    const SidImageInfo* ni = &image->info;
    const uint8_t* oreg = m->ram + SID_REGION_BASE;
    const uint8_t* nreg = sm_region(image);
    const uint8_t* slots = image->bytes + SM_SLOT_MAP;

    /* From the old data before it is replaced: table pointers, straight play's tempo at the playhead. */
    static const uint16_t tables[3][2] = {{SID_MT_INSWAVEPTR, SID_MT_WAVETBL},
                                          {SID_MT_INSPULSEPTR, SID_MT_PULSETIMETBL},
                                          {SID_MT_INSFILTPTR, SID_MT_FILTTIMETBL}};
    int wave[SM_CHANNELS], pulse[SM_CHANNELS], filt = m->ram[SID_MT_FILTSTEP + 1];
    for (int c = 0; c < SM_CHANNELS; ++c) {
        wave[c] = CHN(CHNWAVEPTR, c);
        pulse[c] = CHN(CHNPULSEPTR, c);
    }
    int moved = memcmp(oreg + (SID_MT_INSWAVEPTR - SID_REGION_BASE), nreg + (SID_MT_INSWAVEPTR - SID_REGION_BASE),
                       SID_MT_INSVIBPARAM - SID_MT_INSWAVEPTR) != 0 ||
                memcmp(m->slots, slots, sizeof m->slots) != 0;
    for (int t = 0; t < 3 && !moved; ++t) {
        moved = memcmp(oreg + (tables[t][1] - SID_REGION_BASE), nreg + (tables[t][1] - SID_REGION_BASE), 255) != 0;
    }
    if (moved) {
        TableMap map;
        for (int t = 0; t < 3; ++t) {
            map.col = tables[t][0];
            map.left = tables[t][1];
            jump_rows(oreg, map.left, map.oend);
            jump_rows(nreg, map.left, map.nend);
            if (t == 2) {
                filt = map_pointer(m, image, &map, filt, 0);
            } else {
                for (int c = 0; c < SM_CHANNELS; ++c) {
                    int* p = t == 0 ? &wave[c] : &pulse[c];
                    *p = map_pointer(m, image, &map, *p, CHN(CHNINSTR, c));
                }
            }
        }
    }
    int old_tempo = 0, old_funk = 0, new_tempo, new_funk;
    uint8_t old_pair[2], new_pair[2];
    if (!m->preroll) sm_tempo_at(oreg, &m->info, m->order, m->row, &old_tempo, &old_funk);
    funk_pair(oreg, old_funk, old_pair);
    for (int c = 0; c < SM_CHANNELS; ++c) { /* the playing instrument keeps its slot */
        const uint8_t n = CHN(CHNINSTR, c);
        for (int s = 1; s < 64; ++s) {
            if (m->slots[s] == n && slots[s]) {
                CHN(CHNINSTR, c) = slots[s];
                break;
            }
        }
    }

    memcpy(m->ram + SID_REGION_BASE, nreg, SM_REGION_SIZE);
    patch_parameters(m, ni);
    memcpy(m->slots, slots, sizeof m->slots);
    if (ni->model != m->info.model) sid_resid_set_model(m->sid, ni->model);
    m->tick_cycles = tick_cycles(ni->multiplier);
    for (int c = 0; c < SM_CHANNELS; ++c) {
        CHN(CHNWAVEPTR, c) = (uint8_t)wave[c];
        CHN(CHNPULSEPTR, c) = (uint8_t)pulse[c];
    }
    m->ram[SID_MT_FILTSTEP + 1] = (uint8_t)filt;

    if (m->preroll) { /* no pattern loaded yet: the orderlists are read from the next tick 0 */
        for (int c = 0; c < SM_CHANNELS; ++c) {
            if (CHN(CHNSONGPTR, c) >= ni->orders) CHN(CHNSONGPTR, c) = 0;
        }
        if (m->order >= ni->orders) m->order = 0;
        m->info = *ni;
        return;
    }
    /* Position: kept; clamped to the last row of the last (or the current) order if gone. */
    int clamped = 0;
    if (m->order >= ni->orders) {
        m->order = ni->orders - 1;
        m->row = ni->rows[m->order] - 1;
        clamped = 1;
    } else if (m->row >= ni->rows[m->order]) {
        m->row = ni->rows[m->order] - 1;
        clamped = 1;
    }
    for (int c = 0; c < SM_CHANNELS; ++c) place_channel(m, image, c, m->order, m->row, fetched_next(m, c));
    /* Tempo: straight play's at the playhead if the edit changed it (or the playhead was clamped);
     * otherwise the player keeps its own (e.g. one inherited through the loop). */
    sm_tempo_at(nreg, ni, m->order, m->row, &new_tempo, &new_funk);
    funk_pair(nreg, new_funk, new_pair);
    if (clamped || new_tempo != old_tempo || new_funk != old_funk || memcmp(old_pair, new_pair, 2)) {
        for (int c = 0; c < SM_CHANNELS; ++c) {
            if (new_tempo >= 2) {
                CHN(CHNTEMPO, c) = (uint8_t)new_tempo;
            } else if (CHN(CHNTEMPO, c) >= 2) {
                CHN(CHNTEMPO, c) = 0; /* funktempo, from its first value */
            }
        }
        if (new_tempo < 2 && new_funk) memcpy(m->ram + SID_MT_FUNKTEMPOTBL, new_pair, 2);
    }
    m->info = *ni;
}

/* ---- audition ----------------------------------------------------------------------------- */

#define AUD_OL_NOTE SID_AUDITION_RAM          /* pattern 0, pattern 1, $FF, restart 1 */
#define AUD_OL_REST (SID_AUDITION_RAM + 4)    /* pattern 1, $FF, restart 0 */
#define AUD_PATT_NOTE (SID_AUDITION_RAM + 8)  /* instrument, FX 0, note, 63 rests, end */
#define AUD_PATT_REST (SID_AUDITION_RAM + 16) /* FXONLY 0, 63 rests, end */
#define AUD_PREROLL 64                         /* ticks until row 0 (7 at the default tempo) */

int sm_audition(SidMachine* m, const SidImage* image, int channel, int note, int instrument, int fine) {
    if (!loaded(image) || channel < 0 || channel >= SM_CHANNELS || note < 0 || note > GT_LASTNOTE - GT_NOTE ||
        instrument < 1 || instrument > image->info.instruments || fine) {
        return SM_ERR_IMAGE;
    }
    if (reset(m, image) != SM_OK) return SM_ERR_FAULT;
    const uint8_t ol_note[] = {0, 1, 0xFF, 1}, ol_rest[] = {1, 0xFF, 0};
    const uint8_t patt_note[] = {(uint8_t)instrument, 0x40, (uint8_t)(GT_NOTE + note), 0xC1, 0x00};
    const uint8_t patt_rest[] = {0x50, 0xC1, 0x00};
    memcpy(m->ram + AUD_OL_NOTE, ol_note, sizeof ol_note);
    memcpy(m->ram + AUD_OL_REST, ol_rest, sizeof ol_rest);
    memcpy(m->ram + AUD_PATT_NOTE, patt_note, sizeof patt_note);
    memcpy(m->ram + AUD_PATT_REST, patt_rest, sizeof patt_rest);
    for (int c = 0; c < SM_CHANNELS; ++c) {
        const uint16_t ol = c == channel ? AUD_OL_NOTE : AUD_OL_REST;
        m->ram[SID_MT_SONGTBLLO + c] = (uint8_t)ol;
        m->ram[SID_MT_SONGTBLHI + c] = (uint8_t)(ol >> 8);
    }
    m->ram[SID_MT_PATTTBLLO] = (uint8_t)AUD_PATT_NOTE;
    m->ram[SID_MT_PATTTBLHI] = (uint8_t)(AUD_PATT_NOTE >> 8);
    m->ram[SID_MT_PATTTBLLO + 1] = (uint8_t)AUD_PATT_REST;
    m->ram[SID_MT_PATTTBLHI + 1] = (uint8_t)(AUD_PATT_REST >> 8);
    m->audition = 1;
    m->aud_channel = channel;
    /* The start-up ticks (reset, pattern load, the note's fetch and hard restart) run silently: the next
     * tick is the note's first, as when a song row plays it. */
    for (int i = 0; !(!m->preroll && tick0_next(m)); ++i) {
        if (i >= AUD_PREROLL || sm_tick_silent(m) != SM_OK) return SM_ERR_FAULT;
    }
    m->aud_ticks = 0;
    return SM_OK;
}

int sm_release(SidMachine* m) {
    if (!m->audition || m->released || m->faulted || !m->aud_ticks) return 0;
    if (sid_resid_envelope(m->sid, m->aud_channel) == 0) { /* silent already (decayed to sustain 0) */
        m->ended = 1;
        return 0;
    }
    CHN(CHNGATE, m->aud_channel) = 0xFE;
    m->released = 1;
    return 1;
}

int sm_mute_channel(SidMachine* m, int channel, int muted) {
    if (channel < 0 || channel >= SM_CHANNELS) return SM_ERR_IMAGE;
    m->muted = muted ? (m->muted | 1u << channel) : (m->muted & ~(1u << channel));
    sid_resid_set_voice_mask(m->sid, 0x07u & ~m->muted);
    return SM_OK;
}

/* ---- ticks -------------------------------------------------------------------------------- */

/* The tick's writes at their cycles, then the rest of the tick: with samples or only clocked. */
static int play_writes(SidMachine* m, const SidWrite* w, int count, int16_t* samples) {
    uint32_t last = 0;
    int n = 0;
    for (int i = 0; i < count; ++i) {
        if (samples) {
            n += sid_resid_render(m->sid, w[i].time - last, samples + n, (int)SM_MAX_TICK_FRAMES + 16 - n);
        } else {
            sid_resid_clock(m->sid, w[i].time - last);
        }
        sid_resid_write(m->sid, w[i].reg, w[i].value);
        last = w[i].time;
    }
    if (samples) {
        n += sid_resid_render(m->sid, m->tick_cycles - last, samples + n, (int)SM_MAX_TICK_FRAMES + 16 - n);
    } else {
        sid_resid_clock(m->sid, m->tick_cycles - last);
    }
    return n;
}

/* Seeking defers the SID: the writes of the last settle-window ticks wait in a ring and are played
 * clocked when the seek ends; older ticks reach the SID as bare writes (one cycle apart, so the 8580's
 * write pipeline keeps each one) without the ~19656 cycles of clocking that dominate a tick's cost. */
static void settle_pop_unclocked(SidMachine* m) {
    const int n = m->settle_count[m->settle_head];
    for (int i = 0; i < n; ++i) {
        const SidWrite* w = &m->settle[(m->settle_first + i) % SETTLE_WRITES];
        sid_resid_write(m->sid, w->reg, w->value);
        sid_resid_clock(m->sid, 1);
    }
    m->settle_first = (m->settle_first + n) % SETTLE_WRITES;
    m->settle_writes -= n;
    m->settle_head = (m->settle_head + 1) % SETTLE_TICKS;
    --m->settle_ticks;
}

static void settle_push(SidMachine* m) {
    const int window = SETTLE_SECONDS_TICKS * m->info.multiplier < SETTLE_TICKS ? SETTLE_SECONDS_TICKS * m->info.multiplier
                                                                               : SETTLE_TICKS;
    while (m->settle_ticks && (m->settle_ticks >= window || m->settle_writes + m->nwrites > SETTLE_WRITES)) {
        settle_pop_unclocked(m);
    }
    if (m->nwrites > SETTLE_WRITES) { /* never from the player: apply at once */
        for (int i = 0; i < m->nwrites; ++i) sid_resid_write(m->sid, m->writes[i].reg, m->writes[i].value);
        return;
    }
    const int end = (m->settle_first + m->settle_writes) % SETTLE_WRITES;
    for (int i = 0; i < m->nwrites; ++i) m->settle[(end + i) % SETTLE_WRITES] = m->writes[i];
    m->settle_count[(m->settle_head + m->settle_ticks) % SETTLE_TICKS] = (uint16_t)m->nwrites;
    m->settle_writes += m->nwrites;
    ++m->settle_ticks;
}

static void settle_flush(SidMachine* m) {
    while (m->settle_ticks) { /* m->writes is free between ticks */
        const int n = m->settle_count[m->settle_head];
        for (int i = 0; i < n; ++i) m->writes[i] = m->settle[(m->settle_first + i) % SETTLE_WRITES];
        /* the last tick with (discarded) samples: the resampler's history is then straight play's */
        play_writes(m, m->writes, n, m->settle_ticks == 1 ? m->samples : NULL);
        m->settle_first = (m->settle_first + n) % SETTLE_WRITES;
        m->settle_writes -= n;
        m->settle_head = (m->settle_head + 1) % SETTLE_TICKS;
        --m->settle_ticks;
    }
}

static int run_tick(SidMachine* m, int mode, int* count) {
    if (m->faulted) return SM_ERR_FAULT;
    const int tick0 = tick0_next(m);
    const int starts = row_starts(m);
    m->time = 0;
    m->nwrites = 0;
    if (call(m, m->play_addr, 0, m->tick_cycles) != SM_OK) return SM_ERR_FAULT;
    if (mode == TICK_DEFERRED) {
        settle_push(m);
    } else {
        const int n = play_writes(m, m->writes, m->nwrites, mode == TICK_SAMPLES ? m->samples : NULL);
        if (count) *count = n;
    }
    m->nwrites = 0;
    ++m->ticks;
    if (m->audition) {
        ++m->aud_ticks;
        if (m->released && sid_resid_envelope(m->sid, m->aud_channel) == 0) m->ended = 1;
        if (tick0) m->preroll = 0;
        return SM_OK;
    }
    if (starts) {
        next_row(&m->info, &m->order, &m->row);
        m->row_ticks = 1;
    } else {
        if (tick0) m->preroll = 0;
        if (m->row >= 0) ++m->row_ticks;
    }
    return SM_OK;
}

int sm_tick_silent(SidMachine* m) { return run_tick(m, TICK_CLOCKED, NULL); }

int sm_tick(SidMachine* m, float* out) {
    if (m->ended) return 0;
    int n = 0;
    if (run_tick(m, TICK_SAMPLES, &n) != SM_OK) return SM_ERR_FAULT;
    if (n > (int)SM_MAX_TICK_FRAMES) n = (int)SM_MAX_TICK_FRAMES;
    for (int i = 0; i < n; ++i) {
        const float y = (float)m->samples[i] * (OUTPUT_GAIN / 32768.0f);
        out[2 * i] = y;
        out[2 * i + 1] = y;
    }
    return n;
}

/* ---- diagnostics and test hooks ----------------------------------------------------------- */

uint64_t sm_driver_cycles(const SidMachine* m) { return m->driver_cycles; }
uint32_t sm_last_call_cycles(const SidMachine* m) { return m->last_call; }
uint32_t sm_max_call_cycles(const SidMachine* m) { return m->max_call; }
uint32_t sm_ticks(const SidMachine* m) { return m->ticks; }
uint32_t sm_tick_cycles(const SidMachine* m) { return m->tick_cycles; }

void sm_set_logging(SidMachine* m, int enabled) {
    m->logging = enabled;
    m->log_len = 0;
}

int sm_log(SidMachine* m, uint8_t* pairs, int capacity) {
    int n = m->log_len < capacity ? m->log_len : capacity;
    memcpy(pairs, m->log, (size_t)n * 2);
    m->log_len = 0;
    return n;
}

uint8_t sm_peek(const SidMachine* m, uint16_t addr) { return m->ram[addr]; }
void sm_poke(SidMachine* m, uint16_t addr, uint8_t value) { m->ram[addr] = value; }
int sm_envelope(SidMachine* m, int voice) { return sid_resid_envelope(m->sid, voice); }
