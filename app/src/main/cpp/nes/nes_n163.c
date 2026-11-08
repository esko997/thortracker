/* Namco 163 audio (see nes_n163.h for scope and sources). Event-driven like nes_vrc6.c: run() jumps from one
 * channel update to the next (every NES_N163_SLOT_CYCLES CPU cycles), computes that slot exactly and, with output
 * attached, sends the DAC level to the band-limited buffer whenever it changes. */
#include "nes_n163.h"

#include <math.h>
#include <string.h>

#include "nes_apu.h"

#define AMP_PER_STEP (NES_N163_STEP * NES_APU_AMP_SCALE)
#define REGS(k) (0x78 - 8 * (k)) /* register base of channel index k (hardware channel 8 - k) */

void nes_n163_reset(NesN163* n) {
    blip_t* out = n->out;
    const uint8_t mute = n->mute;
    memset(n, 0, sizeof *n);
    n->out = out;
    n->mute = mute;
    n->timer = NES_N163_SLOT_CYCLES;
}

int nes_n163_channel_count(const NesN163* n) { return ((n->ram[0x7F] >> 4) & 7) + 1; }

uint8_t nes_n163_peek(const NesN163* n, int addr) { return n->ram[addr & 0x7F]; }

uint32_t nes_n163_phase(const NesN163* n, int k) {
    const uint8_t* r = n->ram + REGS(k & 7);
    return (uint32_t)r[1] | (uint32_t)r[3] << 8 | (uint32_t)r[5] << 16;
}

int nes_n163_dac(const NesN163* n) {
    if (n->disabled || (n->mute >> n->last & 1)) return 0;
    return n->level;
}

int nes_n163_dac_channel(const NesN163* n) { return n->last; }

float nes_n163_mix_level(const NesN163* n) { return (float)(nes_n163_dac(n) * NES_N163_STEP); }

static int32_t amplitude(const NesN163* n) { return (int32_t)lrint(nes_n163_dac(n) * AMP_PER_STEP); }

static void update_output(NesN163* n) {
    const int32_t amp = amplitude(n);
    if (amp != n->amp) {
        blip_add_delta(n->out, n->time, amp - n->amp);
        n->amp = amp;
    }
}

void nes_n163_set_output(NesN163* n, blip_t* out) {
    n->out = out;
    n->amp = amplitude(n);
}

void nes_n163_set_mute(NesN163* n, uint8_t mask) {
    n->mute = mask;
    if (n->out) update_output(n);
}

/* One channel update (NESdev "Channel Update"): the enabled count is read now; past it the round restarts at
 * channel 8. Phase += frequency modulo (length << 16), stored back; the DAC takes (sample - 8) * volume. */
static void slot(NesN163* n) {
    if (n->pos >= nes_n163_channel_count(n)) n->pos = 0;
    const int k = n->pos;
    uint8_t* r = n->ram + REGS(k);
    const uint32_t freq = (uint32_t)r[0] | (uint32_t)r[2] << 8 | (uint32_t)(r[4] & 3) << 16;
    const uint32_t length = 256u - (r[4] & 0xFCu);
    uint32_t phase = (uint32_t)r[1] | (uint32_t)r[3] << 8 | (uint32_t)r[5] << 16;
    phase = (phase + freq) % (length << 16);
    r[1] = (uint8_t)phase;
    r[3] = (uint8_t)(phase >> 8);
    r[5] = (uint8_t)(phase >> 16);
    const uint32_t at = ((phase >> 16) + r[6]) & 0xFF;
    const int sample = (n->ram[at >> 1] >> ((at & 1) * 4)) & 15;
    n->level = (int8_t)((sample - 8) * (r[7] & 15));
    n->last = (uint8_t)k;
    n->pos = (uint8_t)(k + 1);
}

void nes_n163_run(NesN163* n, uint32_t t) {
    while (n->time < t) {
        const uint32_t step = t - n->time;
        if (step < n->timer) {
            n->timer -= step;
            n->time = t;
            return;
        }
        n->time += n->timer;
        n->timer = NES_N163_SLOT_CYCLES;
        slot(n);
        if (n->out) update_output(n);
    }
}

/* ---- ports -------------------------------------------------------------------------------- */

int nes_n163_register(uint16_t addr) {
    return (addr >= 0x4800 && addr <= 0x4FFF) || (addr >= 0xE000 && addr <= 0xE7FF) || addr >= 0xF800;
}

/* The data port's access is done: with auto-increment the address advances, stopping at $7F. */
static void data_accessed(NesN163* n) {
    if ((n->addr & 0x80) && (n->addr & 0x7F) < 0x7F) ++n->addr;
}

void nes_n163_write(NesN163* n, uint32_t t, uint16_t addr, uint8_t value) {
    nes_n163_run(n, t);
    if (addr >= 0x4800 && addr <= 0x4FFF) {
        /* The DAC holds the last update's level: a register write is heard from the channel's next slot. */
        n->ram[n->addr & 0x7F] = value;
        data_accessed(n);
    } else if (addr >= 0xE000 && addr <= 0xE7FF) {
        n->disabled = (uint8_t)(value & 0x40);
        if (n->out) update_output(n);
    } else if (addr >= 0xF800) {
        n->addr = value;
    }
}

uint8_t nes_n163_read(NesN163* n, uint32_t t) {
    nes_n163_run(n, t);
    const uint8_t v = n->ram[n->addr & 0x7F];
    data_accessed(n);
    return v;
}

void nes_n163_end_frame(NesN163* n, uint32_t frame_cycles) {
    nes_n163_run(n, frame_cycles);
    n->time = 0;
}
