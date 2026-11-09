// C interface over ymfm's YM2151 (opm_ymfm.h).
#include "opm_ymfm.h"

#include <new>

// ymfm's OPM, compiled here once (unmodified): the muting output sum below calls the engine templates
// (fm_engine_base::clock/output) that ymfm_opm.cpp instantiates implicitly for ym2151. ymfm_fm.ipp, which
// ym/ym_ymfm.cpp also includes through ymfm_opn.cpp, holds only templates and inline functions.
#include "ymfm_opm.cpp"

namespace {

// Both timers on the chip's sample clock: ymfm asks for `period * OPERATORS * prescale` input clocks, i.e. `period`
// samples of 64 clocks; the timer expires after that many samples, checked before each sample is generated.
class Interface : public ymfm::ymfm_interface {
public:
    int32_t remaining[2] = {-1, -1}; // samples until the timer expires, -1 = stopped

    void ymfm_set_timer(uint32_t tnum, int32_t duration_in_clocks) override {
        if (tnum > 1) return;
        if (duration_in_clocks < 0) {
            remaining[tnum] = -1;
            return;
        }
        const int32_t samples = duration_in_clocks / 64;
        remaining[tnum] = samples < 1 ? 1 : samples;
    }

    // Before a sample: report each timer that has run out (ymfm reloads it through ymfm_set_timer; in CSM mode
    // timer A's expiry keys on every channel for this sample). Returns whether timer A expired.
    bool expire_due() {
        bool timer_a = false;
        for (uint32_t t = 0; t < 2; ++t)
            if (remaining[t] == 0) {
                remaining[t] = -1;
                m_engine->engine_timer_expired(t);
                timer_a = timer_a || t == 0;
            }
        return timer_a;
    }

    // After a sample: one sample of 64 input clocks has passed.
    void count_sample() {
        for (int t = 0; t < 2; ++t)
            if (remaining[t] > 0) --remaining[t];
    }
};

// ym2151 with a muting output sum: ym2151::generate with muted channels left out of the sum (before the YM3012 DAC's
// floating-point round trip). Every channel is still computed, one at a time: ymfm advances operator 1's feedback
// history only when a channel's output is computed (fm_channel::output_*op sets m_feedback_in), so leaving a muted
// channel out of fm_engine::output's mask would freeze its feedback and make unmuting differ from never muting. The
// per-channel parts add up to ymfm's own sum (each channel is clipped on its own before it is added). Everything else
// is ymfm's.
class Chip : public ymfm::ym2151 {
public:
    explicit Chip(Interface& intf) : ymfm::ym2151(intf) {}

    void generate_masked(unsigned mute, int32_t* left, int32_t* right) {
        output_data sum, part;
        sum.clear();
        m_fm.clock(fm_engine::ALL_CHANNELS);
        for (uint32_t chan = 0; chan < fm_engine::CHANNELS; ++chan) {
            m_fm.output(part.clear(), 0, 32767, 1u << chan);
            if (mute >> chan & 1) continue;
            sum.data[0] += part.data[0];
            sum.data[1] += part.data[1];
        }
        sum.roundtrip_fp();
        *left = sum.data[0];
        *right = sum.data[1];
    }

    int envelope(int ch, int op) const {
        auto* channel = m_fm.debug_channel(uint32_t(ch));
        auto* oper = channel ? channel->debug_operator(uint32_t(op)) : nullptr;
        return oper ? oper->debug_eg_attenuation() : 0x3FF;
    }
};

} // namespace

struct YmOpm {
    Interface intf;
    Chip chip;
    YmOpm() : chip(intf) {}
};

extern "C" {

YmOpm* omc_create(void) {
    YmOpm* c = new (std::nothrow) YmOpm();
    if (c) c->chip.reset();
    return c;
}

void omc_destroy(YmOpm* chip) { delete chip; }

void omc_reset(YmOpm* chip) {
    // A new chip in place: ymfm's reset() leaves engine counters (envelope clock, LFO, noise) running, and a reused
    // machine must sound like a fresh one. The timers stop with the interface's state.
    chip->~YmOpm();
    new (chip) YmOpm();
    chip->chip.reset();
}

void omc_write(YmOpm* chip, uint8_t reg, uint8_t value) {
    chip->chip.write(0, reg);
    chip->chip.write(1, value);
}

void omc_generate(YmOpm* chip, unsigned mute_mask, int32_t* left, int32_t* right) {
    const bool timer_a = chip->intf.expire_due();
    chip->chip.generate_masked(mute_mask, left, right);
    // ymfm applies a CSM key-on in the channel preparation of this sample and clears it, but the key state only returns
    // to the channel's own at the next preparation, which ymfm runs after a register write (or every 4096 samples). The
    // specified CSM key-on lasts one sample (docs/AUDIO_ENGINE.md, "OPM"): prepare the channels again at the next sample
    // (ymfm's own preparation after a write; no register changes).
    if (timer_a) chip->chip.invalidate_caches();
    chip->intf.count_sample();
}

int omc_envelope(const YmOpm* chip, int ch, int op) { return chip->chip.envelope(ch, op); }

uint8_t omc_status(YmOpm* chip) { return chip->chip.read_status(); }

} // extern "C"
