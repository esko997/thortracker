// C interface over ymfm's YM2612 (ym_ymfm.h).
#include "ym_ymfm.h"

#include <new>

// ymfm's OPN family, compiled here (unmodified, as huge_machine.c includes binjgb's emulator.c): the muting output
// sum below calls engine templates (fm_engine_base::output) that ymfm_opn.cpp only instantiates for its own use.
// This is the program's one copy of ymfm_opn.cpp; the EPSM's YMF288 wrapper (nes/nes_epsm_ymfm.cpp) and the YM2203 /
// YM2608 wrapper (opn/opn_ymfm.cpp) include only ymfm's headers and use the OPN and OPNA templates instantiated
// explicitly below.
#include "ymfm_opn.cpp"

template class ymfm::opn_registers_base<true>;
template class ymfm::fm_engine_base<ymfm::opna_registers>;
template class ymfm::opn_registers_base<false>;
template class ymfm::fm_engine_base<ymfm::opn_registers>;

namespace {

constexpr uint64_t kNever = ~uint64_t(0);

// The busy flag and the two timers on the machine's master clock. ymfm asks for a timer with ymfm_set_timer (in
// YM2612 clocks, -1 = stop) and is told it expired with engine_timer_expired, which sets its status flag and reloads
// it (a free-running timer: the reload is counted from the expiry, not from when it is noticed).
class Interface : public ymfm::ymfm_interface {
public:
    uint64_t now = 0;
    uint64_t busy_end = 0;
    uint64_t timer_end[2] = {kNever, kNever};
    void ymfm_set_busy_end(uint32_t clocks) override { busy_end = now + uint64_t(clocks) * 7u; }
    bool ymfm_is_busy() override { return now < busy_end; }
    void ymfm_set_timer(uint32_t tnum, int32_t duration_in_clocks) override {
        timer_end[tnum] = duration_in_clocks < 0 ? kNever : now + uint64_t(duration_in_clocks) * 7u;
    }
    // Every expiry up to `t`, in time order, each at its own time.
    void sync(uint64_t t) {
        for (;;) {
            const int k = timer_end[1] < timer_end[0] ? 1 : 0;
            if (timer_end[k] > t) break;
            now = timer_end[k];
            m_engine->engine_timer_expired(uint32_t(k));
        }
        now = t;
    }
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
            // ymfm advances operator feedback during output, not clock; mute only the resulting signal.
            m_fm.output(temp.clear(), 5, 256, 1 << chan);
            if (mute >> chan & 1) {
                out[0] += dac_discontinuity(0);
                out[1] += dac_discontinuity(0);
                continue;
            }
            out[0] += dac_discontinuity(temp.data[0]);
            out[1] += dac_discontinuity(temp.data[1]);
        }
        // The DAC replaces FM 6 (the XGM2 driver's PCM mix); FM 6's mute bit is the FM channel's, not the DAC's.
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

    bool dac_enabled() const { return m_dac_enable != 0; }
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
    // Rebuild at the same address: ymfm's reset() leaves envelope/LFO counters running.
    // Construction allocates internally; hosts reset spare machines off the audio thread.
    chip->~YmOpn2();
    new (chip) YmOpn2();
    chip->chip.reset();
}

void ymo_write(YmOpn2* chip, uint64_t now, int port, uint8_t value) {
    chip->intf.sync(now);
    chip->chip.write(uint32_t(port & 3), value);
}

uint8_t ymo_read_status(YmOpn2* chip, uint64_t now) {
    chip->intf.sync(now);
    return chip->chip.read_status();
}

void ymo_generate(YmOpn2* chip, unsigned mute_mask, int32_t* left, int32_t* right) {
    chip->chip.generate_masked(mute_mask, left, right);
}

int ymo_envelope(const YmOpn2* chip, int ch, int op) { return chip->chip.envelope(ch, op); }

int ymo_dac_enabled(const YmOpn2* chip) { return chip->chip.dac_enabled() ? 1 : 0; }

} // extern "C"
