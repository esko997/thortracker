// C interface over ymfm's YM2612 (ym_ymfm.h).
#include "ym_ymfm.h"

#include <new>

// ymfm's OPN family, compiled here (unmodified, as huge_machine.c includes binjgb's emulator.c): the muting output
// sum below calls engine templates (fm_engine_base::output) that ymfm_opn.cpp only instantiates for its own use.
#include "ymfm_opn.cpp"

namespace {

// The busy flag on the machine's master clock.
class Interface : public ymfm::ymfm_interface {
public:
    uint64_t now = 0;
    uint64_t busy_end = 0;
    void ymfm_set_busy_end(uint32_t clocks) override { busy_end = now + uint64_t(clocks) * 7u; }
    bool ymfm_is_busy() override { return now < busy_end; }
};

// ym2612 with a muting output sum: ym2612::generate with each muted channel contributing what a channel with its
// output bits clear contributes (the DAC offset of 0). Everything else is ymfm's.
class Chip : public ymfm::ym2612 {
public:
    explicit Chip(Interface& intf) : ymfm::ym2612(intf) {}

    void generate_masked(unsigned mute, int32_t* left, int32_t* right) {
        m_fm.clock(fm_engine::ALL_CHANNELS);
        int32_t out[2] = {0, 0};
        output_data temp;
        int const last_fm_channel = m_dac_enable ? 5 : 6;
        for (int chan = 0; chan < last_fm_channel; chan++) {
            if (mute >> chan & 1) {
                out[0] += dac_discontinuity(0);
                out[1] += dac_discontinuity(0);
                continue;
            }
            m_fm.output(temp.clear(), 5, 256, 1 << chan);
            out[0] += dac_discontinuity(temp.data[0]);
            out[1] += dac_discontinuity(temp.data[1]);
        }
        if (m_dac_enable) {
            int32_t dacval = dac_discontinuity(int16_t(m_dac_data << 7) >> 7);
            out[0] += m_fm.regs().ch_output_0(0x102) ? dacval : dac_discontinuity(0);
            out[1] += m_fm.regs().ch_output_1(0x102) ? dacval : dac_discontinuity(0);
        }
        *left = (out[0] * 128) * 64 / (6 * 65);
        *right = (out[1] * 128) * 64 / (6 * 65);
    }

    int envelope(int ch, int op) const {
        auto* channel = m_fm.debug_channel(uint32_t(ch));
        auto* oper = channel ? channel->debug_operator(uint32_t(op)) : nullptr;
        return oper ? oper->debug_eg_attenuation() : 0x3FF;
    }
};

} // namespace

struct YmOpn2 {
    Interface intf;
    Chip chip;
    YmOpn2() : chip(intf) {}
};

extern "C" {

YmOpn2* ymo_create(void) {
    YmOpn2* c = new (std::nothrow) YmOpn2();
    if (c) c->chip.reset();
    return c;
}

void ymo_destroy(YmOpn2* chip) { delete chip; }

void ymo_reset(YmOpn2* chip) {
    // A new chip in place (no allocation): ymfm's reset() leaves engine counters (envelope clock, LFO) running, and a
    // reused machine must sound like a fresh one.
    chip->~YmOpn2();
    new (chip) YmOpn2();
    chip->chip.reset();
}

void ymo_write(YmOpn2* chip, uint64_t now, int port, uint8_t value) {
    chip->intf.now = now;
    chip->chip.write(uint32_t(port & 3), value);
}

uint8_t ymo_read_status(YmOpn2* chip, uint64_t now) {
    chip->intf.now = now;
    return chip->chip.read_status();
}

void ymo_generate(YmOpn2* chip, unsigned mute_mask, int32_t* left, int32_t* right) {
    chip->chip.generate_masked(mute_mask, left, right);
}

int ymo_envelope(const YmOpn2* chip, int ch, int op) { return chip->chip.envelope(ch, op); }

} // extern "C"
