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
    uint32_t frames;      /* ticks since the song started */
    int faulted;
    uint8_t fpr, order_count, loop_order;
    uint16_t rpo;
    uint64_t driver_cycles;
    uint32_t last_call;
    float hp_a, lp_a, hp_in, hp_out, lp_out;
    int logging, log_len;
    uint8_t official[32];
    uint8_t ram[0x800];
    uint8_t prg[0x8000];
    uint8_t log[2 * LOG_CAPACITY];
    short samples[NM_MAX_TICK_FRAMES];
};

static uint16_t rd16(const uint8_t* p) { return (uint16_t)(p[0] | p[1] << 8); }
static uint32_t rd32(const uint8_t* p) { return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24; }

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
    const float dt = 1.0f / (float)sample_rate;
    const float hp_rc = 1.0f / (2.0f * 3.14159265f * HIGHPASS_HZ);
    const float lp_rc = 1.0f / (2.0f * 3.14159265f * LOWPASS_HZ);
    m->hp_a = hp_rc / (hp_rc + dt);
    m->lp_a = dt / (lp_rc + dt);
    m6502_desc_t desc;
    memset(&desc, 0, sizeof desc);
    desc.bcd_disabled = true; /* the 2A03 has no decimal mode */
    m6502_init(&m->cpu, &desc);
    m->faulted = 1; /* until a song is started */
    return m;
}

void nm_destroy(NesMachine* m) {
    if (!m) return;
    blip_delete(m->blip);
    free(m);
}

int nm_validate(const NesImage* image) {
    const uint8_t* h = image->bytes;
    if (rd32(h) != NM_MAGIC || rd32(h + 4) != NES_PLAYER_ID || rd16(h + 8) != NES_SONG_BASE) return NM_ERR_IMAGE;
    const uint32_t size = rd16(h + 10);
    for (int i = 18; i < (int)NM_HEADER_SIZE; ++i) {
        if (h[i]) return NM_ERR_IMAGE;
    }
    const uint8_t* s = h + NM_HEADER_SIZE;
    const uint32_t songs = s[0], index = h[12];
    /* FamiStudio header: count, instruments, samples-4, then 5 channel pointers + 4 tempo bytes per
     * song; the engine indexes songs with an 8-bit offset, so at most 17. */
    if (size < 5 + 14 || size > NM_SONG_SIZE || songs == 0 || songs > 17 || index >= songs || 5 + 14 * songs > size) {
        return NM_ERR_IMAGE;
    }
    for (uint32_t i = size; i < NM_SONG_SIZE; ++i) {
        if (s[i]) return NM_ERR_IMAGE;
    }
#define INSIDE(ptr) ((ptr) >= NES_SONG_BASE && (ptr) < NES_SONG_BASE + size)
    if (!INSIDE(rd16(s + 1))) return NM_ERR_IMAGE;
    for (uint32_t k = 0; k < songs; ++k) {
        const uint8_t* song = s + 5 + 14 * k;
        for (int ch = 0; ch < NM_CHANNELS; ++ch) {
            if (!INSIDE(rd16(song + 2 * ch))) return NM_ERR_IMAGE;
        }
        if (!INSIDE(rd16(song + 10))) return NM_ERR_IMAGE; /* tempo envelope (FamiStudio tempo) */
    }
#undef INSIDE
    const int fpr = h[13], rpo = rd16(h + 14), orders = h[16], loop = h[17];
    if ((fpr || rpo || orders || loop) && (!fpr || !rpo || !orders || loop >= orders)) return NM_ERR_IMAGE;
    return NM_OK;
}

static void load_timing(NesMachine* m, const NesImage* image) {
    m->fpr = image->bytes[13];
    m->rpo = rd16(image->bytes + 14);
    m->order_count = image->bytes[16];
    m->loop_order = image->bytes[17];
}

int nm_start(NesMachine* m, const NesImage* image, int order, int row, int max_ticks) {
    if (nm_validate(image) != NM_OK) return NM_ERR_IMAGE;
    memset(m->ram, 0, sizeof m->ram);
    memset(m->prg, 0, sizeof m->prg);
    memcpy(m->prg + (NES_PLAYER_BASE - 0x8000), nes_player_code, NES_PLAYER_SIZE);
    memcpy(m->prg + (NES_SONG_BASE - 0x8000), image->bytes + NM_HEADER_SIZE, NM_SONG_SIZE);
    for (int v = 0xFFFA; v < 0x10000; v += 2) {
        m->prg[v - 0x8000] = (uint8_t)NES_PLAYER_TRAP;
        m->prg[v - 0x8000 + 1] = (uint8_t)(NES_PLAYER_TRAP >> 8);
    }
    load_timing(m, image);
    nes_apu_set_output(&m->apu, NULL);
    nes_apu_reset(&m->apu);
    blip_clear(m->blip);
    m->hp_in = m->hp_out = m->lp_out = 0.0f;
    m->time = 0;
    m->frame_index = 0;
    m->frames = 0;
    m->driver_cycles = 0;
    m->faulted = 0;
    m->log_len = 0;
    if (call(m, NES_PLAYER_INIT, image->bytes[12], INIT_BUDGET) != NM_OK) return NM_ERR_FAULT;
    nes_apu_end_frame(&m->apu, m->time); /* init runs before the first frame */
    if (order < 0 || row < 0) return NM_ERR_IMAGE;
    if (order == 0 && row == 0) return NM_OK;
    if (!m->fpr || order >= m->order_count || row >= m->rpo) return NM_ERR_IMAGE;
    const uint64_t target = ((uint64_t)order * m->rpo + (uint64_t)row) * m->fpr;
    if (target > (uint64_t)(max_ticks < 0 ? 0 : max_ticks)) return NM_ERR_IMAGE;
    for (uint64_t i = 0; i < target; ++i) {
        if (nm_tick_silent(m) != NM_OK) return NM_ERR_FAULT;
    }
    return NM_OK;
}

void nm_apply_image(NesMachine* m, const NesImage* image) {
    if (m->faulted || nm_validate(image) != NM_OK) return;
    memcpy(m->prg + (NES_SONG_BASE - 0x8000), image->bytes + NM_HEADER_SIZE, NM_SONG_SIZE);
    load_timing(m, image);
}

int nm_mute_channel(NesMachine* m, int channel, int muted) {
    if (channel < 0 || channel >= NM_CHANNELS) return NM_ERR_IMAGE;
    uint8_t mask = m->apu.mute;
    mask = muted ? (uint8_t)(mask | (1 << channel)) : (uint8_t)(mask & ~(1 << channel));
    nes_apu_set_mute(&m->apu, mask);
    return NM_OK;
}

void nm_position(const NesMachine* m, int* order, int* row, int* tick) {
    uint32_t f = m->frames;
    if (!m->fpr) {
        *order = 0;
        *row = 0;
        *tick = f > 0x7FFFFFFF ? 0x7FFFFFFF : (int)f;
        return;
    }
    const uint32_t per_order = (uint32_t)m->fpr * m->rpo;
    const uint32_t total = per_order * m->order_count, loop = per_order * m->loop_order;
    if (f >= total) f = loop + (f - loop) % (total - loop);
    const uint32_t rows = f / m->fpr;
    *tick = (int)(f % m->fpr);
    *order = (int)(rows / m->rpo);
    *row = (int)(rows % m->rpo);
}

/* ---- frames ------------------------------------------------------------------------------- */

static int run_frame(NesMachine* m, int synth) {
    if (m->faulted) return NM_ERR_FAULT;
    const uint32_t frame_cycles = (m->frame_index & 1) ? 29781u : 29780u;
    if (synth != (m->apu.out != NULL)) nes_apu_set_output(&m->apu, synth ? m->blip : NULL);
    m->time = 0;
    if (call(m, NES_PLAYER_PLAY, 0, frame_cycles) != NM_OK) return NM_ERR_FAULT;
    nes_apu_end_frame(&m->apu, frame_cycles);
    ++m->frame_index;
    ++m->frames;
    return NM_OK;
}

int nm_tick_silent(NesMachine* m) { return run_frame(m, 0); }

int nm_tick(NesMachine* m, float* out) {
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

void nm_build_image(NesImage* image, const uint8_t* song, uint32_t size, int song_index, int frames_per_row,
                    int rows_per_order, int order_count, int loop_order) {
    uint8_t* h = image->bytes;
    memset(h, 0, sizeof image->bytes);
    const uint32_t words[2] = {NM_MAGIC, NES_PLAYER_ID};
    for (int w = 0; w < 2; ++w) {
        for (int i = 0; i < 4; ++i) h[4 * w + i] = (uint8_t)(words[w] >> (8 * i));
    }
    h[8] = (uint8_t)NES_SONG_BASE;
    h[9] = (uint8_t)(NES_SONG_BASE >> 8);
    if (size > NM_SONG_SIZE) size = NM_SONG_SIZE;
    h[10] = (uint8_t)size;
    h[11] = (uint8_t)(size >> 8);
    h[12] = (uint8_t)song_index;
    h[13] = (uint8_t)frames_per_row;
    h[14] = (uint8_t)rows_per_order;
    h[15] = (uint8_t)(rows_per_order >> 8);
    h[16] = (uint8_t)order_count;
    h[17] = (uint8_t)loop_order;
    memcpy(h + NM_HEADER_SIZE, song, size);
}
