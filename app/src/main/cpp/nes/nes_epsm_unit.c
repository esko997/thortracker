/* The NES machine's EPSM unit (see nes_epsm_unit.h). */
#include "nes_epsm_unit.h"

#include <math.h>
#include <string.h>

#include "nes_apu.h"

_Static_assert(NES_EPSM_SAMPLE_UNITS + NES_EPSM_CYCLE_UNITS > NES_EPSM_SAMPLE_UNITS, "fixed-point phase fits 32 bits");

#define AMP_SCALE ((float)NES_EPSM_AMP_SCALE) /* buffer amplitude of a level of 1.0 */
_Static_assert((NES_EPSM_MUTE_RHYTHM(5) << 1) == (1u << NES_EPSM_VOICES), "15 mute bits");

int nes_epsm_init(NesEpsm* e) {
    memset(e, 0, sizeof *e);
    e->chip = nes_epsm_chip_create();
    if (!e->chip) return -1;
    nes_epsm_reset(e);
    return 0;
}

void nes_epsm_free(NesEpsm* e) {
    nes_epsm_chip_destroy(e->chip);
    e->chip = NULL;
}

void nes_epsm_reset(NesEpsm* e) {
    nes_epsm_chip_reset(e->chip);
    memset(e->select, 0, sizeof e->select);
    memset(e->reg, 0, sizeof e->reg);
    e->time = 0;
    e->phase = 0;
    e->level[0] = e->level[1] = 0.0f;
    e->amp[0] = e->amp[1] = 0;
}

void nes_epsm_set_output(NesEpsm* e, blip_t* left, blip_t* right) {
    e->out[0] = left;
    e->out[1] = right;
    /* Start from the current level (no step at the attach). */
    for (int s = 0; s < 2; ++s) e->amp[s] = (int32_t)lrintf(e->level[s] * AMP_SCALE);
}

/* One native sample at frame-relative cycle `t`. */
static void sample(NesEpsm* e, uint32_t t) {
    NesEpsmOutput o;
    nes_epsm_chip_generate(e->chip, e->mute, &o);
    const float ssg = (float)(o.ssg * NES_EPSM_SSG_STEP);
    for (int s = 0; s < 2; ++s) {
        e->level[s] = (float)(o.fm[s] * NES_EPSM_FM_STEP + o.rhythm[s] * NES_EPSM_RHYTHM_STEP) + ssg;
        if (!e->out[s]) continue;
        const int32_t amp = (int32_t)lrintf(e->level[s] * AMP_SCALE);
        if (amp != e->amp[s]) {
            blip_add_delta(e->out[s], t, amp - e->amp[s]);
            e->amp[s] = amp;
        }
    }
}

void nes_epsm_run(NesEpsm* e, uint32_t t) {
    while (e->time < t) {
        /* CPU cycles until the next native sample is due (ceil). */
        const uint32_t left = NES_EPSM_SAMPLE_UNITS - e->phase;
        const uint32_t cycles = (left + NES_EPSM_CYCLE_UNITS - 1) / NES_EPSM_CYCLE_UNITS;
        if (e->time + cycles > t) {
            e->phase += (t - e->time) * NES_EPSM_CYCLE_UNITS;
            e->time = t;
            return;
        }
        e->time += cycles;
        e->phase = e->phase + cycles * NES_EPSM_CYCLE_UNITS - NES_EPSM_SAMPLE_UNITS;
        sample(e, e->time);
    }
}

void nes_epsm_write(NesEpsm* e, uint32_t t, uint16_t addr, uint8_t value) {
    if (!nes_epsm_register(addr)) return;
    nes_epsm_run(e, t);
    const int port = addr - 0x401C, bank = port >> 1;
    if (port & 1) {
        e->reg[bank][e->select[bank]] = value;
    } else {
        e->select[bank] = value;
    }
    nes_epsm_chip_write(e->chip, port, value);
}

void nes_epsm_end_frame(NesEpsm* e, uint32_t frame_cycles) {
    nes_epsm_run(e, frame_cycles);
    e->time = 0;
}

void nes_epsm_set_mute(NesEpsm* e, uint16_t mask) { e->mute = (uint16_t)(mask & ((1u << NES_EPSM_VOICES) - 1)); }
