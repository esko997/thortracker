/* Konami VRC7 audio on emu2413 (see nes_vrc7.h for scope, clock and mix sources). Event-driven like nes_apu.c:
 * run() steps from one chip sample to the next and sends the mixed level to the band-limited buffer when it
 * changes; without output the chip samples all the same and nothing is mixed. */
#include "nes_vrc7.h"

#include <math.h>
#include <string.h>

#include "nes_apu.h"

#define AMP_PER_UNIT (NES_VRC7_STEP * NES_APU_AMP_SCALE)
#define EG_MAX 123 /* emu2413: to_linear outputs 0 for eg_out above (1 << EG_BITS) - 1 - 4 */

int nes_vrc7_init(NesVrc7* v) {
    memset(v, 0, sizeof *v);
    v->opll = OPLL_new(NES_VRC7_OPLL_CLOCK, NES_VRC7_OPLL_RATE);
    if (!v->opll) return -1;
    OPLL_setChipType(v->opll, 1);
    OPLL_resetPatch(v->opll, OPLL_VRC7_TONE);
    nes_vrc7_reset(v);
    return 0;
}

void nes_vrc7_free(NesVrc7* v) {
    if (v->opll) OPLL_delete(v->opll);
    v->opll = NULL;
}

void nes_vrc7_reset(NesVrc7* v) {
    OPLL_reset(v->opll); /* registers, slots, LFOs; keeps the chip type and the patch ROM; no allocation */
    v->select = 0;
    v->held = 0;
    memset(v->reg, 0, sizeof v->reg);
    v->time = 0;
    v->wait = NES_VRC7_CYCLES_PER_SAMPLE;
    v->amp = 0;
}

/* ---- output ------------------------------------------------------------------------------- */

int nes_vrc7_channel_out(const NesVrc7* v, int channel) { return v->opll->ch_out[channel]; }

static int32_t level(const NesVrc7* v) {
    if (v->held) return 0;
    int32_t sum = 0;
    for (int c = 0; c < NES_VRC7_VOICES; ++c) {
        if (!(v->mute & (1 << c))) sum += v->opll->ch_out[c];
    }
    return sum;
}

float nes_vrc7_mix_level(const NesVrc7* v) { return (float)(level(v) * NES_VRC7_STEP); }

static int32_t amplitude(const NesVrc7* v) { return (int32_t)lrint(level(v) * AMP_PER_UNIT); }

static void update_output(NesVrc7* v) {
    const int32_t amp = amplitude(v);
    if (amp != v->amp) {
        blip_add_delta(v->out, v->time, amp - v->amp);
        v->amp = amp;
    }
}

void nes_vrc7_set_output(NesVrc7* v, blip_t* out) {
    v->out = out;
    v->amp = amplitude(v);
}

void nes_vrc7_set_mute(NesVrc7* v, uint8_t mask) {
    v->mute = (uint8_t)(mask & ((1 << NES_VRC7_VOICES) - 1));
    if (v->out) update_output(v);
}

int nes_vrc7_release_over(const NesVrc7* v, int channel) {
    const OPLL_SLOT* car = &v->opll->slot[2 * channel + 1];
    /* A pending update (key-off, rate change) is committed at the next sample: undecided until then. */
    if (car->key_flag || car->update_requests) return 0;
    return car->eg_out > EG_MAX || car->eg_rate_h == 0;
}

/* ---- clock -------------------------------------------------------------------------------- */

void nes_vrc7_run(NesVrc7* v, uint32_t t) {
    while (v->time < t) {
        const uint32_t step = t - v->time;
        if (step < v->wait) {
            v->wait -= step;
            v->time = t;
            return;
        }
        v->time += v->wait;
        v->wait = NES_VRC7_CYCLES_PER_SAMPLE;
        if (v->held) continue; /* held in reset: not clocked, silent */
        OPLL_calc(v->opll);
        if (v->out) update_output(v);
    }
}

void nes_vrc7_end_frame(NesVrc7* v, uint32_t frame_cycles) {
    nes_vrc7_run(v, frame_cycles);
    v->time = 0;
}

/* ---- registers ---------------------------------------------------------------------------- */

int nes_vrc7_register(uint16_t addr) { return addr == 0x9010 || addr == 0x9030 || addr == 0xE000; }

/* The VRC7's internal registers ($0E rhythm, $16-$18, $26-$28, $36-$38 and the YM2413 mirrors are not). */
static int vrc7_register(uint8_t r) {
    return r <= 0x07 || r == 0x0F || (r >= 0x10 && r <= 0x15) || (r >= 0x20 && r <= 0x25) || (r >= 0x30 && r <= 0x35);
}

/* $E000 bit 6 set: registers and the tremolo LFO cleared, the vibrato LFO's phase kept; silent while held. */
static void hold(NesVrc7* v) {
    const uint32_t vibrato = v->opll->pm_phase;
    OPLL_reset(v->opll);
    v->opll->pm_phase = vibrato;
    memset(v->reg, 0, sizeof v->reg);
    v->select = 0;
    v->held = 1;
    if (v->out) update_output(v);
}

void nes_vrc7_write(NesVrc7* v, uint32_t t, uint16_t addr, uint8_t value) {
    nes_vrc7_run(v, t);
    if (addr == 0xE000) {
        if (value & 0x40) {
            if (!v->held) hold(v);
        } else {
            v->held = 0; /* clocked again from the next sample; its output is the reset chip's (0) until then */
        }
        return;
    }
    if (v->held) return;
    if (addr == 0x9010) {
        v->select = value;
    } else if (addr == 0x9030 && vrc7_register(v->select)) {
        v->reg[v->select] = value;
        OPLL_writeReg(v->opll, v->select, value);
    }
}
