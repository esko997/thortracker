/* NES machine: m6502 + nes_apu running the FamiStudio player (see nes_machine.h). */
#include "nes_machine.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#include "blip_buf.h"
#include "nes_apu.h"

#define CHIPS_IMPL
#include "m6502.h"

#define INIT_BUDGET 200000u   /* famistudio_init + famistudio_music_play */
#define LOG_CAPACITY 4096     /* APU writes buffered between nm_apu_log calls */
#define OUTPUT_GAIN 2.0f      /* mixer level 1.0 (all channels at full) ~ peak 2.0 before DC removal */
#define HIGHPASS_HZ 37.0f     /* Famicom-style output stage */
#define LOWPASS_HZ 14000.0f

_Static_assert(NM_MAX_SAMPLE_RATE * 29781ull / NM_CPU_HZ + 1 <= NM_MAX_TICK_FRAMES, "tick fits the host buffer");
_Static_assert(NES_PLAYER_BASE + NES_PLAYER_SIZE <= NES_SONG_BASE, "player below the song region");
_Static_assert(NES_SONG_BASE + NES_IMAGE_REGION_SIZE == 0xFFFA, "the region ends at the vectors");

/* Documented 6502 opcodes except BRK (FamiStudio's invalid-data trap). Anything else fetched by
 * the CPU is a fault. */
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

struct NesMachine {
    m6502_t cpu;
    NesApu apu;
    blip_t* blip;
    uint32_t sample_rate;
    uint32_t time;        /* CPU cycle within the current frame */
    uint32_t frame_index; /* frames since power-on (29780/29781 alternation) */
    uint32_t frames;      /* ticks since start */
    int faulted;
    int order, row;       /* row most recently started (-1: none yet in `order`) */
    uint32_t row_ticks;   /* frames run in that row */
    int audition, released, ended, aud_channel, rel_slot, rel_end;
    uint64_t driver_cycles;
    uint32_t last_call;
    float hp_a, lp_a, hp_in, hp_out, lp_out;
    int logging, log_len;
    uint8_t official[32];
    uint8_t channel_env[NM_CHANNELS];       /* engine tables read from the player image */
    uint8_t slot_kind[NES_NUM_ENVELOPES];
    NesImageInfo info;                      /* of the image playing */
    uint8_t meta[NM_REGION_OFFSET];         /* its header + envelope directory */
    uint8_t ram[0x800];
    uint8_t prg[0x8000];
    uint8_t log[2 * LOG_CAPACITY];
    short samples[NM_MAX_TICK_FRAMES];
};

/* Engine RAM by symbol. */
#define RAM(sym) m->ram[NES_FAMISTUDIO_##sym]
#define RAMX(sym, i) m->ram[NES_FAMISTUDIO_##sym + (i)]

/* ---- bus ---------------------------------------------------------------------------------- */

static uint8_t bus_read(NesMachine* m, uint16_t addr) {
    if (addr < 0x2000) return m->ram[addr & 0x7FF];
    if (addr >= 0x8000) return m->prg[addr - 0x8000];
    if (addr == 0x4015) return nes_apu_read_status(&m->apu, m->time);
    return 0;
}

static void bus_write(NesMachine* m, uint16_t addr, uint8_t v) {
    if (addr < 0x2000) {
        m->ram[addr & 0x7FF] = v;
    } else if (addr >= 0x4000 && addr <= 0x4017 && addr != 0x4014 && addr != 0x4016) {
        if (m->logging && m->log_len < LOG_CAPACITY) {
            m->log[2 * m->log_len] = (uint8_t)addr;
            m->log[2 * m->log_len + 1] = v;
            ++m->log_len;
        }
        nes_apu_write(&m->apu, m->time, addr, v);
    }
}

static int official(const NesMachine* m, uint8_t op) { return (m->official[op >> 3] >> (op & 7)) & 1; }

/* Runs the routine at `addr` (A = a) until it returns to NES_PLAYER_IDLE. One bus access per CPU
 * cycle at frame time m->time. */
static int call(NesMachine* m, uint16_t addr, uint8_t a, uint32_t budget) {
    if (m->faulted) return NM_ERR_FAULT;
    m6502_t* c = &m->cpu;
    const uint16_t ret = NES_PLAYER_IDLE - 1; /* RTS adds one */
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
    if (!official(m, op)) goto fault;
    uint64_t pins = M6502_SYNC | M6502_RW;
    M6502_SET_ADDR(pins, addr);
    M6502_SET_DATA(pins, op);
    uint32_t cycles = 1; /* the opcode fetch above */
    ++m->time;
    for (;;) {
        pins = m6502_tick(c, pins);
        const uint16_t at = M6502_GET_ADDR(pins);
        if (pins & M6502_RW) {
            if ((pins & M6502_SYNC) && at == NES_PLAYER_IDLE) break;
            const uint8_t v = bus_read(m, at);
            if ((pins & M6502_SYNC) && (at == NES_PLAYER_TRAP || !official(m, v))) goto fault;
            M6502_SET_DATA(pins, v);
        } else {
            bus_write(m, at, M6502_GET_DATA(pins));
        }
        ++m->time;
        if (++cycles > budget) goto fault;
    }
    m->last_call = cycles;
    m->driver_cycles += cycles;
    return NM_OK;
fault:
    m->faulted = 1;
    return NM_ERR_FAULT;
}

/* ---- lifecycle ---------------------------------------------------------------------------- */

NesMachine* nm_create(uint32_t sample_rate) {
    if (sample_rate < 8000 || sample_rate > NM_MAX_SAMPLE_RATE) return NULL;
    NesMachine* m = (NesMachine*)calloc(1, sizeof *m);
    if (!m) return NULL;
    m->blip = blip_new((int)(sample_rate / 10));
    if (!m->blip) {
        free(m);
        return NULL;
    }
    blip_set_rates(m->blip, (double)NM_CPU_HZ, (double)sample_rate);
    m->sample_rate = sample_rate;
    for (size_t i = 0; i < sizeof kOfficialOpcodes; ++i) m->official[kOfficialOpcodes[i] >> 3] |= (uint8_t)(1 << (kOfficialOpcodes[i] & 7));
    /* famistudio_channel_env: first envelope slot of each channel (volume, arpeggio, duty follow). */
    for (int c = 0; c < NM_CHANNELS; ++c) m->channel_env[c] = nes_player_code[NES_FAMISTUDIO_CHANNEL_ENV - NES_PLAYER_BASE + c];
    for (int s = 0; s < NES_NUM_ENVELOPES; ++s) {
        int base = 0;
        for (int c = 0; c < 4; ++c) {
            if (m->channel_env[c] <= s && m->channel_env[c] >= base) base = m->channel_env[c];
        }
        m->slot_kind[s] = (uint8_t)(s - base);
    }
    const float dt = 1.0f / (float)sample_rate;
    const float hp_rc = 1.0f / (2.0f * 3.14159265f * HIGHPASS_HZ);
    const float lp_rc = 1.0f / (2.0f * 3.14159265f * LOWPASS_HZ);
    m->hp_a = hp_rc / (hp_rc + dt);
    m->lp_a = dt / (lp_rc + dt);
    m6502_desc_t desc;
    memset(&desc, 0, sizeof desc);
    desc.bcd_disabled = true; /* the 2A03 has no decimal mode */
    m6502_init(&m->cpu, &desc);
    nes_apu_set_memory(&m->apu, m->prg); /* DMC sample fetches read the cartridge space */
    m->faulted = 1; /* until a song is started */
    return m;
}

void nm_destroy(NesMachine* m) {
    if (!m) return;
    blip_delete(m->blip);
    free(m);
}

static int loaded(const NesImage* image) { return image && image->info.loaded == NM_LOADED; }

/* Power-on with `image`: player, song region, vectors, APU, then init + music_play of its song. */
static int reset(NesMachine* m, const NesImage* image) {
    memset(m->ram, 0, sizeof m->ram);
    memset(m->prg, 0, sizeof m->prg);
    memcpy(m->prg + (NES_PLAYER_BASE - 0x8000), nes_player_code, NES_PLAYER_SIZE);
    memcpy(m->prg + (NES_SONG_BASE - 0x8000), nm_region(image), NES_IMAGE_REGION_SIZE);
    for (int v = 0xFFFA; v < 0x10000; v += 2) {
        m->prg[v - 0x8000] = (uint8_t)NES_PLAYER_TRAP;
        m->prg[v - 0x8000 + 1] = (uint8_t)(NES_PLAYER_TRAP >> 8);
    }
    m->info = image->info;
    memcpy(m->meta, image->bytes, NM_REGION_OFFSET);
    nes_apu_set_output(&m->apu, NULL);
    nes_apu_reset(&m->apu);
    nes_apu_set_mute(&m->apu, 0);
    blip_clear(m->blip);
    m->hp_in = m->hp_out = m->lp_out = 0.0f;
    m->time = 0;
    m->frame_index = 0;
    m->frames = 0;
    m->driver_cycles = 0;
    m->faulted = 0;
    m->log_len = 0;
    m->order = 0;
    m->row = -1;
    m->row_ticks = 0;
    m->audition = m->released = m->ended = 0;
    if (call(m, NES_PLAYER_INIT, image->bytes[12], INIT_BUDGET) != NM_OK) return NM_ERR_FAULT;
    nes_apu_end_frame(&m->apu, m->time); /* init runs before the first frame */
    return NM_OK;
}

/* ---- rows --------------------------------------------------------------------------------- */

/* Whether the engine starts a row on the next frame (famistudio_update's FamiTracker-tempo test). */
static int row_starts(const NesMachine* m) {
    const uint8_t speed = RAM(SONG_SPEED);
    return !m->audition && speed && !(speed & 0x80) && RAM(TEMPO_ACC_HI) >= speed;
}

/* The row after (order, row) in play order: wraps at the order's rows and at the loop. */
static void next_row(const NesMachine* m, int* order, int* row) {
    const NesImageInfo* info = &m->info;
    int o = *order, r = *row + 1;
    if (info->orders && r >= info->rows[o]) {
        r = 0;
        if (++o >= info->orders) {
            if (info->loop == 0xFF) { /* the song has ended: stay on its last row */
                o = info->orders - 1;
                r = info->rows[o] - 1;
            } else {
                o = info->loop;
            }
        }
    }
    *order = o;
    *row = r;
}

void nm_position(const NesMachine* m, int* order, int* row, int* tick) {
    int o = m->order, r = m->row;
    if (row_starts(m)) {
        next_row(m, &o, &r);
        *tick = 0;
    } else {
        if (r < 0) r = 0;
        *tick = m->row_ticks > 0x7FFFFFFF ? 0x7FFFFFFF : (int)m->row_ticks;
    }
    *order = o;
    *row = r;
}

static int at(const NesMachine* m, int order, int row) {
    int o, r, t;
    nm_position(m, &o, &r, &t);
    return o == order && r == row && t == 0;
}

/* Silent frames until the next tick plays (order, row), at most `budget` frames. 1 reached, 0 not,
 * NM_ERR_FAULT. */
static int fast_forward(NesMachine* m, int order, int row, int budget) {
    for (int i = 0;; ++i) {
        if (at(m, order, row)) return 1;
        if (i >= budget) return 0;
        if (nm_tick_silent(m) != NM_OK) return NM_ERR_FAULT;
    }
}

/* Thor layout: every channel at `order`'s entry of its order stream, the speed that order starts
 * with, and a row start on the next frame. */
static void place_at_order(NesMachine* m, int order) {
    for (int c = 0; c < NM_CHANNELS; ++c) {
        const uint16_t e = (uint16_t)(m->info.streams[c] + 4 * order);
        RAMX(CHN_PTR_LO, c) = (uint8_t)e;
        RAMX(CHN_PTR_HI, c) = (uint8_t)(e >> 8);
        RAMX(CHN_REPEAT, c) = 0;
        RAMX(CHN_REF_LEN, c) = 0;
        RAMX(CHN_NOTE_DELAY, c) = 0xFF;
        RAMX(CHN_CUT_DELAY, c) = 0xFF;
    }
    RAM(SONG_SPEED) = m->info.speed[order];
    RAM(TEMPO_ACC_HI) = m->info.speed[order];
    RAM(TEMPO_ACC_LO) = 0;
    m->order = order;
    m->row = -1;
    m->row_ticks = 0;
}

int nm_start(NesMachine* m, const NesImage* image, int order, int row, int max_ticks, int* seek_mode) {
    *seek_mode = NM_SEEK_FROM_START;
    if (!loaded(image) || order < 0 || row < 0) return NM_ERR_IMAGE;
    const NesImageInfo* info = &image->info;
    if (order || row) {
        if (!info->orders || order >= info->orders || row >= info->rows[order]) return NM_ERR_IMAGE;
    }
    if (reset(m, image) != NM_OK) return NM_ERR_FAULT;
    const int budget = max_ticks < 0 ? 0 : max_ticks;
    int r = fast_forward(m, order, row, budget);
    if (r) return r < 0 ? r : NM_OK;
    if (info->layout != NM_LAYOUT_THOR) return NM_ERR_IMAGE;
    if (reset(m, image) != NM_OK) return NM_ERR_FAULT;
    place_at_order(m, order);
    *seek_mode = NM_SEEK_FROM_ORDER;
    r = fast_forward(m, order, row, budget);
    return r < 0 ? r : r ? NM_OK : NM_ERR_IMAGE;
}

/* ---- live edits --------------------------------------------------------------------------- */

typedef struct StreamState {
    uint16_t ptr, ret;
    uint8_t repeat, ref_len;
} StreamState;

/* Channel c's engine stream state in `image` once rows 0..consumed-1 of `order` have been read; with
 * `pending` (a delayed note waiting) the next row's entry is positioned after its `$47 d`. */
static StreamState stream_state(const NmView* v, int c, int order, int consumed, int pending) {
    const uint8_t* s = v->region;
    const uint16_t e = (uint16_t)(v->info->streams[c] + 4 * order);
    const int n = s[e + 1 - NES_SONG_BASE];
    StreamState st = {e, (uint16_t)(e + 4), 0, 0};
    if (!consumed && !pending) return st;
    uint16_t a = nm_rd16(s + (e + 2 - NES_SONG_BASE));
    int row = 0, done = 0;
    NmEntry entry;
    while (row < consumed && done < n) {
        if (nm_walk_entry(v, a, c, &entry)) return st; /* cannot happen for a loaded image */
        ++done;
        a = entry.end;
        if (consumed < row + entry.rows) st.repeat = (uint8_t)(row + entry.rows - consumed);
        row += entry.rows;
    }
    if (done == n) {
        st.ptr = st.ret;
    } else {
        st.ptr = a;
        st.ref_len = (uint8_t)(n - done);
        if (pending && !st.repeat && !nm_walk_entry(v, a, c, &entry) && entry.delay_end) st.ptr = entry.delay_end;
    }
    return st;
}

/* New address for an engine envelope pointer (and its read position) when `image` replaces the
 * playing one: the same directory entry if unchanged; else an entry of the same kind with the
 * same bytes; else the same entry edited (position kept if still an instruction start, else the
 * envelope restarts); else the engine's silent dummy. Pointers outside the directory are kept. */
static uint16_t remap_envelope(const NesMachine* m, const NesImage* image, int kind, uint16_t addr, uint8_t* ptr,
                               uint8_t* repeat) {
    const uint8_t* odir = m->meta + NES_IMAGE_HEADER_SIZE;
    const uint8_t* ndir = image->bytes + NES_IMAGE_HEADER_SIZE;
    const NesImageInfo* ni = &image->info;
    const int idx = nm_find_envelope(&m->info, odir, addr);
    if (idx < 0 || odir[4 * idx + 3] != kind) return addr;
    const uint8_t* old = m->prg + (addr - 0x8000);
    const int len = odir[4 * idx + 2];
#define NEW_BYTES(i) (nm_region(image) + (nm_rd16(ndir + 4 * (i)) - NES_SONG_BASE))
#define SAME(i) (ndir[4 * (i) + 3] == kind && ndir[4 * (i) + 2] == len && !memcmp(NEW_BYTES(i), old, (size_t)len))
    const int same_index = idx < ni->envelopes && nm_rd16(ndir + 4 * idx) && ndir[4 * idx + 3] == kind;
    if (same_index && SAME(idx)) return nm_rd16(ndir + 4 * idx);
    for (int u = 0; u < ni->used_count; ++u) {
        if (SAME(ni->used[u])) return nm_rd16(ndir + 4 * ni->used[u]);
    }
    if (same_index) {
        if (!nm_envelope_instruction_at(NEW_BYTES(idx), ndir[4 * idx + 2], kind, *ptr)) {
            *ptr = (uint8_t)nm_envelope_start(kind);
            *repeat = 0;
        }
        return nm_rd16(ndir + 4 * idx);
    }
#undef SAME
#undef NEW_BYTES
    *ptr = kind == NM_KIND_PITCH ? 1 : 0;
    *repeat = 0;
    return kind == NM_KIND_PITCH ? NES_FAMISTUDIO_DUMMY_PITCH_ENVELOPE : NES_FAMISTUDIO_DUMMY_ENVELOPE;
}

/* The sample the DMC is playing when `image` replaces the memory it reads (decided while the old bytes are
 * still in place): it keeps playing if its bytes are unchanged where they are; else it continues at the same
 * position of an identical sample of the new DPCM list (the samples moved); else it stops as a $4015 write
 * does (the bits already fetched play out; the level holds). The latched $4010 rate and loop stay until the
 * engine starts the next sample. */
static void follow_sample(NesMachine* m, const NesImage* image) {
    NesApu* a = &m->apu;
    if (!a->dmc.remaining) return;
    const uint32_t start = NES_SAMPLE_BASE + 64u * a->dmc.start, len = 16u * a->dmc.length + 1u;
    if (start + len > NES_SAMPLE_END) { /* off-contract raw data running into the vectors */
        nes_apu_dmc_stop(a);
        return;
    }
    const uint8_t* old = m->prg + (start - 0x8000);
    const uint8_t* fresh = nm_region(image); /* CPU address a at fresh[a - NES_SONG_BASE] */
    if (!memcmp(old, fresh + (start - NES_SONG_BASE), len)) return;
    const NesImageInfo* ni = &image->info;
    for (uint32_t k = 0; k < ni->mappings; ++k) {
        const uint8_t* e = fresh + (ni->dpcm_list - NES_SONG_BASE) + 4u * k;
        if (16u * e[1] + 1u == len && !memcmp(old, fresh + (NES_SAMPLE_BASE - NES_SONG_BASE) + 64u * e[0], len)) {
            nes_apu_dmc_move(a, e[0]);
            return;
        }
    }
    nes_apu_dmc_stop(a);
}

void nm_apply_image(NesMachine* m, const NesImage* image) {
    if (m->faulted || m->audition || !loaded(image)) return;
    const NesImageInfo* ni = &image->info;
    follow_sample(m, image);
    if (m->info.layout != NM_LAYOUT_THOR || ni->layout != NM_LAYOUT_THOR) {
        memcpy(m->prg + (NES_SONG_BASE - 0x8000), nm_region(image), NES_IMAGE_REGION_SIZE);
        m->info = *ni;
        memcpy(m->meta, image->bytes, NM_REGION_OFFSET);
        return;
    }
    /* Where the song is, in the new song. */
    int o = m->order, r = m->row, clamped = 0;
    if (o >= ni->orders) {
        o = ni->orders - 1;
        r = ni->rows[o] - 1;
        clamped = 1;
    } else if (r >= ni->rows[o]) {
        r = ni->rows[o] - 1;
        clamped = 1;
    }
    const NmView nv = nm_view(image);
    const NmView ov = {m->prg + (NES_SONG_BASE - 0x8000), m->meta + NES_IMAGE_HEADER_SIZE, &m->info};
    StreamState st[NM_CHANNELS];
    for (int c = 0; c < NM_CHANNELS; ++c) {
        const int pending = !clamped && r >= 0 && RAMX(CHN_NOTE_DELAY, c) < 0x80;
        st[c] = stream_state(&nv, c, o, pending ? r : r + 1, pending);
    }
    /* Envelope pointers, decided while the old bytes are still in place. */
    uint16_t env[NES_NUM_ENVELOPES], pitch[NES_NUM_PITCH_ENVELOPES];
    for (int s = 0; s < NES_NUM_ENVELOPES; ++s) {
        env[s] = remap_envelope(m, image, m->slot_kind[s], (uint16_t)(RAMX(ENV_ADDR_LO, s) | RAMX(ENV_ADDR_HI, s) << 8),
                                &RAMX(ENV_PTR, s), &RAMX(ENV_REPEAT, s));
    }
    for (int s = 0; s < NES_NUM_PITCH_ENVELOPES; ++s) {
        pitch[s] = remap_envelope(m, image, NM_KIND_PITCH,
                                  (uint16_t)(RAMX(PITCH_ENV_ADDR_LO, s) | RAMX(PITCH_ENV_ADDR_HI, s) << 8),
                                  &RAMX(PITCH_ENV_PTR, s), &RAMX(PITCH_ENV_REPEAT, s));
    }
    /* Speed: straight play's speed at the playhead, if the edit changed it (song speed = order 0's
     * first $46, or an Fxx earlier in this order) or the playhead was clamped; a speed the engine
     * got another way (a loop, an Fxx later edited away) is otherwise kept. */
    const int speed_new = nm_speed_at(&nv, o, r);
    const int set_speed = clamped || nm_speed_at(&ov, m->order, m->row) != speed_new;
    memcpy(m->prg + (NES_SONG_BASE - 0x8000), nm_region(image), NES_IMAGE_REGION_SIZE);
    for (int c = 0; c < NM_CHANNELS; ++c) {
        RAMX(CHN_PTR_LO, c) = (uint8_t)st[c].ptr;
        RAMX(CHN_PTR_HI, c) = (uint8_t)(st[c].ptr >> 8);
        RAMX(CHN_RETURN_LO, c) = (uint8_t)st[c].ret;
        RAMX(CHN_RETURN_HI, c) = (uint8_t)(st[c].ret >> 8);
        RAMX(CHN_REPEAT, c) = st[c].repeat;
        RAMX(CHN_REF_LEN, c) = st[c].ref_len;
        if (clamped) RAMX(CHN_NOTE_DELAY, c) = 0xFF;
    }
    for (int s = 0; s < NES_NUM_ENVELOPES; ++s) {
        RAMX(ENV_ADDR_LO, s) = (uint8_t)env[s];
        RAMX(ENV_ADDR_HI, s) = (uint8_t)(env[s] >> 8);
    }
    for (int s = 0; s < NES_NUM_PITCH_ENVELOPES; ++s) {
        RAMX(PITCH_ENV_ADDR_LO, s) = (uint8_t)pitch[s];
        RAMX(PITCH_ENV_ADDR_HI, s) = (uint8_t)(pitch[s] >> 8);
    }
    const uint8_t* s = nm_region(image);
    RAM(INSTRUMENT_LO) = s[1];
    RAM(INSTRUMENT_HI) = s[2];
    RAM(DPCM_LIST_LO) = s[3];
    RAM(DPCM_LIST_HI) = s[4];
    RAM(TEMPO_STEP_LO) = (uint8_t)ni->tempo_ntsc;
    RAM(TEMPO_STEP_HI) = (uint8_t)(ni->tempo_ntsc >> 8);
    if (set_speed && !(RAM(SONG_SPEED) & 0x80)) RAM(SONG_SPEED) = (uint8_t)speed_new;
    m->order = o;
    m->row = r;
    m->info = *ni;
    memcpy(m->meta, image->bytes, NM_REGION_OFFSET);
}

/* ---- audition ----------------------------------------------------------------------------- */

#define AUDITION_SILENT NES_AUDITION_RAM          /* $FF (64 empty rows), $42 back */
#define AUDITION_NOTE (NES_AUDITION_RAM + 4)      /* [$54 i] [$4E f] $40 n, $42 to the silent loop; DPCM: k, $42 */
#define AUDITION_RELEASE (NES_AUDITION_RAM + 16)  /* $45, $42 to the silent loop */
_Static_assert(AUDITION_RELEASE + 4 <= 0x800, "audition streams fit RAM");
_Static_assert(NES_RAM_BASE + NES_RAM_SIZE <= NES_AUDITION_RAM, "engine RAM below the audition streams");

static void put_stream(NesMachine* m, uint16_t at, const uint8_t* bytes, int n) { memcpy(m->ram + at, bytes, (size_t)n); }

int nm_audition(NesMachine* m, const NesImage* image, int channel, int note, int instrument, int fine) {
    if (!loaded(image) || image->info.layout != NM_LAYOUT_THOR || channel < 0 || channel > NM_DPCM) return NM_ERR_IMAGE;
    if (channel == NM_DPCM ? note < 1 || note > image->info.mappings
                           : note < 1 || note > 96 || instrument < 0 || instrument >= image->info.instruments || fine < -128 ||
                                 fine > 127) {
        return NM_ERR_IMAGE;
    }
    if (reset(m, image) != NM_OK) return NM_ERR_FAULT;
    const uint8_t lo = (uint8_t)AUDITION_SILENT, hi = (uint8_t)(AUDITION_SILENT >> 8);
    const uint8_t silent[] = {0xFF, 0x42, lo, hi};
    uint8_t held[12];
    int n = 0;
    if (channel == NM_DPCM) { /* mapping k of the DPCM list: the engine starts its sample */
        held[n++] = (uint8_t)note;
    } else {
        held[n++] = 0x54;
        held[n++] = (uint8_t)instrument;
        if (channel <= 2) { /* the noise channel has no pitch envelope (and no fine pitch) */
            held[n++] = 0x4E;
            held[n++] = (uint8_t)(int8_t)fine;
        }
        held[n++] = 0x40;
        held[n++] = (uint8_t)note;
    }
    held[n++] = 0x42;
    held[n++] = lo;
    held[n++] = hi;
    const uint8_t release[] = {0x45, 0x42, lo, hi};
    put_stream(m, AUDITION_SILENT, silent, sizeof silent);
    put_stream(m, AUDITION_NOTE, held, n);
    put_stream(m, AUDITION_RELEASE, release, sizeof release);
    for (int c = 0; c < NM_CHANNELS; ++c) {
        const uint16_t p = c == channel ? AUDITION_NOTE : AUDITION_SILENT;
        RAMX(CHN_PTR_LO, c) = (uint8_t)p;
        RAMX(CHN_PTR_HI, c) = (uint8_t)(p >> 8);
    }
    m->audition = 1;
    m->aud_channel = channel;
    return NM_OK;
}

int nm_release(NesMachine* m) {
    if (!m->audition || m->released || m->faulted || !m->frames || m->aud_channel == NM_DPCM) return 0; /* DPCM: stop */
    const int slot = m->channel_env[m->aud_channel];
    const uint16_t addr = (uint16_t)(RAMX(ENV_ADDR_LO, slot) | RAMX(ENV_ADDR_HI, slot) << 8);
    const int idx = nm_find_envelope(&m->info, m->meta + NES_IMAGE_HEADER_SIZE, addr);
    if (idx < 0 || !m->prg[addr - 0x8000]) return 0; /* no release point: cut */
    const int c = m->aud_channel;
    RAMX(CHN_PTR_LO, c) = (uint8_t)AUDITION_RELEASE;
    RAMX(CHN_PTR_HI, c) = (uint8_t)(AUDITION_RELEASE >> 8);
    RAMX(CHN_REPEAT, c) = 0;
    RAMX(CHN_REF_LEN, c) = 0;
    RAMX(CHN_NOTE_DELAY, c) = 0xFF;
    RAM(TEMPO_ACC_HI) = RAM(SONG_SPEED); /* the next frame reads the release */
    m->released = 1;
    m->rel_slot = slot;
    m->rel_end = m->meta[NES_IMAGE_HEADER_SIZE + 4 * idx + 2] - 2; /* the final loop: release tail done */
    return 1;
}

int nm_mute_channel(NesMachine* m, int channel, int muted) {
    if (channel < 0 || channel >= NM_CHANNELS) return NM_ERR_IMAGE;
    uint8_t mask = m->apu.mute;
    mask = muted ? (uint8_t)(mask | (1 << channel)) : (uint8_t)(mask & ~(1 << channel));
    nes_apu_set_mute(&m->apu, mask);
    return NM_OK;
}

/* ---- frames ------------------------------------------------------------------------------- */

static int run_frame(NesMachine* m, int synth) {
    if (m->faulted) return NM_ERR_FAULT;
    const uint32_t frame_cycles = (m->frame_index & 1) ? 29781u : 29780u;
    if (synth != (m->apu.out != NULL)) nes_apu_set_output(&m->apu, synth ? m->blip : NULL);
    const int starts = row_starts(m);
    m->time = 0;
    if (call(m, NES_PLAYER_PLAY, 0, frame_cycles) != NM_OK) return NM_ERR_FAULT;
    nes_apu_end_frame(&m->apu, frame_cycles);
    ++m->frame_index;
    ++m->frames;
    if (starts) {
        next_row(m, &m->order, &m->row);
        m->row_ticks = 1;
    } else {
        ++m->row_ticks;
    }
    if (m->released && RAMX(ENV_PTR, m->rel_slot) >= m->rel_end) m->ended = 1;
    return NM_OK;
}

int nm_tick_silent(NesMachine* m) { return run_frame(m, 0); }

int nm_tick(NesMachine* m, float* out) {
    if (m->ended) return 0;
    if (run_frame(m, 1) != NM_OK) return NM_ERR_FAULT;
    blip_end_frame(m->blip, (m->frame_index & 1) ? 29780u : 29781u); /* the frame just run */
    int n = blip_samples_avail(m->blip);
    if (n > (int)NM_MAX_TICK_FRAMES) n = (int)NM_MAX_TICK_FRAMES;
    n = blip_read_samples(m->blip, m->samples, n, 0);
    for (int i = 0; i < n; ++i) {
        const float x = (float)m->samples[i] * (1.0f / 32768.0f);
        m->hp_out = m->hp_a * (m->hp_out + x - m->hp_in);
        m->hp_in = x;
        m->lp_out += m->lp_a * (m->hp_out - m->lp_out);
        const float y = m->lp_out * OUTPUT_GAIN;
        out[2 * i] = y;
        out[2 * i + 1] = y;
    }
    return n;
}

/* ---- diagnostics and test hooks ----------------------------------------------------------- */

uint64_t nm_driver_cycles(const NesMachine* m) { return m->driver_cycles; }
uint32_t nm_last_call_cycles(const NesMachine* m) { return m->last_call; }
uint32_t nm_frames(const NesMachine* m) { return m->frames; }

void nm_set_apu_logging(NesMachine* m, int enabled) {
    m->logging = enabled;
    m->log_len = 0;
}

int nm_apu_log(NesMachine* m, uint8_t* pairs, int capacity) {
    int n = m->log_len < capacity ? m->log_len : capacity;
    memcpy(pairs, m->log, (size_t)n * 2);
    m->log_len = 0;
    return n;
}

uint8_t nm_peek(NesMachine* m, uint16_t addr) {
    if (addr < 0x2000) return m->ram[addr & 0x7FF];
    if (addr >= 0x8000) return m->prg[addr - 0x8000];
    return 0;
}

void nm_poke(NesMachine* m, uint16_t addr, uint8_t value) {
    if (addr < 0x2000) {
        m->ram[addr & 0x7FF] = value;
    } else if (addr >= 0x8000) {
        m->prg[addr - 0x8000] = value;
    }
}

int nm_call(NesMachine* m, uint16_t addr, uint8_t a) {
    m->time = 0;
    int r = call(m, addr, a, 29780u);
    if (r == NM_OK) nes_apu_end_frame(&m->apu, m->time);
    return r;
}

const NesApu* nm_apu(const NesMachine* m) { return &m->apu; }
