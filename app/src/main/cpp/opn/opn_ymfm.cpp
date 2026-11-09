// ymfm's YM2203 and YM2608 behind the C interface of opn_ymfm.h.
//
// ymfm's OPN family is compiled once, in ym/ym_ymfm.cpp, which also explicitly instantiates the register and FM engine
// templates of both chips (opn_registers_base<false> / fm_engine_base<opn_registers> for the YM2203,
// opn_registers_base<true> / fm_engine_base<opna_registers> for the YM2608); here only ymfm's headers are included
// and those templates are declared extern, so the program holds one copy of each.
//
// One native sample = one FM clock at the power-on prescaler (/6): ym2203::clock_fm / ym2608::clock_fm_and_adpcm
// split by part, with the FM computed channel by channel (every channel the chip itself computes, so muted channels'
// feedback inputs advance exactly as unmuted) and the SSG resampled 9:2 exactly as ymfm's ssg_resampler::resample_2_9
// (the OPN_FIDELITY_MED resampler of the YM2610 / YMF288, whose FM and SSG rates stand in the same 2:9 ratio as the
// YM2203's and YM2608's at prescaler 6: 4.5 SSG samples per FM sample), per channel instead of mixed.
#include "opn_ymfm.h"

#include <new>
#include <vector>

#include "opn_image.h"
#include "ym2608_rhythm_rom.h"
#include "ymfm_opn.h"

extern template class ymfm::opn_registers_base<false>;
extern template class ymfm::fm_engine_base<ymfm::opn_registers>;
extern template class ymfm::opn_registers_base<true>;
extern template class ymfm::fm_engine_base<ymfm::opna_registers>;

namespace {

// Master clocks per native sample at the power-on prescaler: one FM sample per 6 x 12 (YM2203) / 6 x 24 (YM2608)
// clocks (ymfm: fm_engine_base::update_timer counts timers in period x OPERATORS x prescale clocks).
constexpr uint32_t CLOCKS_YM2203 = 72;
constexpr uint32_t CLOCKS_YM2608 = 144;

// The chip's outside world: timers on a master-clock counter, the rhythm ROM (ADPCM-A) and the ADPCM-B memory.
class OpnInterface : public ymfm::ymfm_interface {
public:
    uint64_t now = 0;                 // master clocks since reset
    int64_t deadline[2] = {-1, -1};   // master clock at which timer A / B expires, -1 = stopped
    int64_t fired_at = 0;             // the deadline of the timer expiring now (its reload counts from there)
    int firing = -1;                  // timer whose expiry is being delivered, else -1
    uint32_t timer_a_expiries = 0;
    const uint8_t* rom = nullptr;     // set at a rhythm key-on that finds the ROM installed
    const uint8_t* adpcm = nullptr;   // ADPCM-B memory (the machine's), reads at or past adpcm_size return 0
    uint32_t adpcm_size = 0;

    void clear() {
        now = 0;
        deadline[0] = deadline[1] = -1;
        fired_at = 0;
        firing = -1;
        timer_a_expiries = 0;
        rom = nullptr;
    }

    void ymfm_set_timer(uint32_t tnum, int32_t duration_in_clocks) override {
        if (tnum > 1) return;
        if (duration_in_clocks < 0) {
            deadline[tnum] = -1;
            return;
        }
        // A reload from the expiry itself counts from the expired deadline, so the period never drifts.
        const int64_t base = firing == int(tnum) ? fired_at : int64_t(now);
        deadline[tnum] = base + duration_in_clocks;
    }

    uint8_t ymfm_external_read(ymfm::access_class type, uint32_t address) override {
        if (type == ymfm::ACCESS_ADPCM_A) return rom && address < YM2608_RHYTHM_ROM_SIZE ? rom[address] : 0;
        if (type == ymfm::ACCESS_ADPCM_B) return adpcm && address < adpcm_size ? adpcm[address] : 0;
        return 0;
    }

    // Delivers every timer due at the current master clock (before the sample's FM clock), then advances the clock.
    // A timer period is at least 72 clocks, so each timer expires at most once per native sample.
    void run_timers(uint32_t clocks) {
        for (uint32_t t = 0; t < 2; ++t) {
            if (deadline[t] < 0 || deadline[t] > int64_t(now)) continue;
            fired_at = deadline[t];
            deadline[t] = -1;
            firing = int(t);
            m_engine->engine_timer_expired(t);
            firing = -1;
            if (t == 0) ++timer_a_expiries;
        }
        now += clocks;
    }
};

// The SSG resampled 9:2 per channel (ssg_resampler<..>::resample_2_9 with write_to_output's per-channel divide).
struct SsgResampler {
    uint32_t index = 0;  // output sample index
    ymfm::ssg_engine::output_data last{};

    void clear() {
        index = 0;
        last.clear();
    }

    void run(ymfm::ssg_engine& ssg, uint32_t mute, int32_t* out) {
        int32_t s[3] = {0, 0, 0};
        if (index & 1) add(s, 1);
        for (int i = 0; i < 4; ++i) clock_and_add(ssg, s, 2);
        if (!(index & 1)) clock_and_add(ssg, s, 1);
        ++index;
        for (int c = 0; c < 3; ++c) out[c] = (mute & OPN_MUTE_SSG(c)) ? 0 : s[c] / 9;
    }

private:
    void add(int32_t* s, int32_t scale) {
        for (int c = 0; c < 3; ++c) s[c] += last.data[c] * scale;
    }
    void clock_and_add(ymfm::ssg_engine& ssg, int32_t* s, int32_t scale) {
        ssg.clock();
        ssg.output(last);
        add(s, scale);
    }
};

// Carriers (operator bits, op 1 = bit 0) of OPN algorithms 0-7: op4; op4; op4; op4; op2+op4; op2-4; op2-4; all.
constexpr uint8_t CARRIERS[8] = {0x8, 0x8, 0x8, 0x8, 0xA, 0xE, 0xE, 0xF};

template <class Engine>
int fm_attenuation(const Engine& fm, int channel, int op) {
    auto* chan = fm.debug_channel(uint32_t(channel));
    auto* oper = chan ? chan->debug_operator(uint32_t(op)) : nullptr;
    return oper ? oper->debug_eg_attenuation() : 0x3FF;
}

// Quiet = every carrier at or past ymfm's EG_QUIET attenuation (0x380, fm_operator's private constant), where an
// operator outputs nothing.
template <class Engine>
int fm_quiet(const Engine& fm, int channel) {
    const uint32_t reg = 0xB0 + uint32_t(channel % 3) + (channel >= 3 ? 0x100u : 0u);
    const uint8_t c = CARRIERS[const_cast<Engine&>(fm).regs().read(uint16_t(reg)) & 7];
    for (int op = 0; op < 4; ++op) {
        if ((c >> op & 1) && fm_attenuation(fm, channel, op) < 0x380) return 0;
    }
    return 1;
}

// ym2203 split by part: clock_fm (FM 14-bit, no intermediate clipping, through the 10.3 floating-point DAC) with
// muted channels left out of the DAC's input, the SSG's three channels apart.
class Ym2203Parts : public ymfm::ym2203 {
public:
    explicit Ym2203Parts(OpnInterface& intf) : ymfm::ym2203(intf) {}

    SsgResampler ssg;

    void write_reg(int port, uint8_t reg, uint8_t value) {
        if (port != 0) return;
        write(0, reg);
        write(1, value);
    }

    void generate_parts(uint32_t mute, OpnOutput* out) {
        m_fm.clock(fm_engine::ALL_CHANNELS);
        fm_engine::output_data sum, one;
        sum.clear();
        for (uint32_t ch = 0; ch < OPN_FM_YM2203; ++ch) {
            m_fm.output(one.clear(), 0, 32767, 1u << ch);
            if (mute & OPN_MUTE_FM(ch)) continue;
            sum.data[0] += one.data[0];
        }
        sum.roundtrip_fp();
        out->fm[0] = out->fm[1] = sum.data[0];
        out->rhythm[0] = out->rhythm[1] = 0;
        out->adpcm[0] = out->adpcm[1] = 0;
        ssg.run(m_ssg, mute, out->ssg);
    }

    const fm_engine& fm() const { return m_fm; }
};

// ym2608 split by part: clock_fm_and_adpcm (FM 13-bit: right shift 1; ADPCM-A clocked on the envelope cycle, ADPCM-B
// every sample, its output right shift 1) with the FM channel count from $29 bit 7 as ymfm, and the SSG apart.
class Ym2608Parts : public ymfm::ym2608 {
public:
    Ym2608Parts(OpnInterface& intf) : ymfm::ym2608(intf), m_opn_intf(intf) {}

    SsgResampler ssg;
    uint32_t playing = 0;  // rhythm drums keyed on, not yet at their end address
    uint32_t silent = 0;   // rhythm drums keyed on without the ROM (left out of the output until their next key-on)
    int missing = 0;       // a rhythm key-on found no ROM since reset

    void clear_extras() {
        ssg.clear();
        playing = silent = 0;
        missing = 0;
    }

    void write_reg(int port, uint8_t reg, uint8_t value) {
        // $10: rhythm key control, bit 7 = dump (key off), bits 0-5 the drums.
        if (port == 0 && reg == 0x10) {
            const uint32_t drums = value & 0x3Fu;
            if (value & 0x80) {
                playing &= ~drums;
            } else if (drums) {
                const uint8_t* rom = ym2608_rhythm_rom();
                if (rom) {
                    m_opn_intf.rom = rom;
                    silent &= ~drums;
                } else {
                    silent |= drums;
                    missing = 1;
                }
                playing |= drums;
            }
        }
        write(port ? 2u : 0u, reg);
        write(port ? 3u : 1u, value);
    }

    void generate_parts(uint32_t mute, OpnOutput* out) {
        const uint32_t fmmask = ymfm::bitfield(m_irq_enable, 7) ? 0x3f : 0x07;
        const uint32_t env_counter = m_fm.clock(fm_engine::ALL_CHANNELS);
        if (ymfm::bitfield(env_counter, 0, 2) == 0) playing &= ~m_adpcm_a.clock(ymfm::bitfield(env_counter, 2) ? 0x0f : 0x3f);
        m_adpcm_b.clock();
        fm_engine::output_data sum, one;
        sum.clear();
        for (uint32_t ch = 0; ch < OPN_FM_YM2608; ++ch) {
            if (!ymfm::bitfield(fmmask, ch)) continue;
            m_fm.output(one.clear(), 1, 32767, 1u << ch);
            if (mute & OPN_MUTE_FM(ch)) continue;
            sum.data[0] += one.data[0];
            sum.data[1] += one.data[1];
        }
        out->fm[0] = sum.data[0];
        out->fm[1] = sum.data[1];
        ymfm::ymfm_output<2> part;
        part.clear();
        m_adpcm_a.output(part, ~((mute >> 9) | silent) & 0x3f);
        out->rhythm[0] = part.data[0];
        out->rhythm[1] = part.data[1];
        part.clear();
        if (!(mute & OPN_MUTE_ADPCM)) m_adpcm_b.output(part, 1);
        out->adpcm[0] = part.data[0];
        out->adpcm[1] = part.data[1];
        ssg.run(m_ssg, mute, out->ssg);
    }

    int adpcm_playing() const { return (m_adpcm_b.status() & ymfm::adpcm_b_channel::STATUS_PLAYING) != 0; }

    const fm_engine& fm() const { return m_fm; }

private:
    OpnInterface& m_opn_intf;
};

} // namespace

struct OpnChip {
    explicit OpnChip(int kind) : kind(kind) {}
    virtual ~OpnChip() = default;

    const int kind; // OPN_CHIP_*
    OpnInterface intf;
    std::vector<uint8_t> power_on; // ymfm save_restore state right after construction and reset
    uint64_t samples = 0;

    virtual void save(bool saving) = 0;
    virtual void clear_extras() = 0;
    virtual void write_reg(int port, uint8_t reg, uint8_t value) = 0;
    virtual void generate(uint32_t mute, OpnOutput* out) = 0;
    virtual int fm_channels() const = 0;
    virtual int attenuation(int channel, int op) const = 0;
    virtual int quiet(int channel) const = 0;
};

namespace {

class Opn2203Chip final : public OpnChip {
public:
    Opn2203Chip() : OpnChip(OPN_CHIP_YM2203), chip(intf) {}
    Ym2203Parts chip;

    void save(bool saving) override {
        ymfm::ymfm_saved_state state(power_on, saving);
        chip.save_restore(state);
    }
    void clear_extras() override { chip.ssg.clear(); }
    void write_reg(int port, uint8_t reg, uint8_t value) override { chip.write_reg(port, reg, value); }
    void generate(uint32_t mute, OpnOutput* out) override {
        intf.run_timers(CLOCKS_YM2203);
        chip.generate_parts(mute, out);
    }
    int fm_channels() const override { return OPN_FM_YM2203; }
    int attenuation(int channel, int op) const override { return fm_attenuation(chip.fm(), channel, op); }
    int quiet(int channel) const override { return fm_quiet(chip.fm(), channel); }
};

class Opn2608Chip final : public OpnChip {
public:
    Opn2608Chip() : OpnChip(OPN_CHIP_YM2608), chip(intf) {}
    Ym2608Parts chip;

    void save(bool saving) override {
        ymfm::ymfm_saved_state state(power_on, saving);
        chip.save_restore(state);
    }
    void clear_extras() override { chip.clear_extras(); }
    void write_reg(int port, uint8_t reg, uint8_t value) override { chip.write_reg(port, reg, value); }
    void generate(uint32_t mute, OpnOutput* out) override {
        intf.run_timers(CLOCKS_YM2608);
        chip.generate_parts(mute, out);
    }
    int fm_channels() const override { return OPN_FM_YM2608; }
    int attenuation(int channel, int op) const override { return fm_attenuation(chip.fm(), channel, op); }
    int quiet(int channel) const override { return fm_quiet(chip.fm(), channel); }
};

const Opn2608Chip* opna(const OpnChip* c) {
    return c->kind == OPN_CHIP_YM2608 ? static_cast<const Opn2608Chip*>(c) : nullptr;
}

} // namespace

extern "C" {

OpnChip* opn_chip_create(int chip) {
    OpnChip* c = nullptr;
    if (chip == OPN_CHIP_YM2203) {
        auto* x = new (std::nothrow) Opn2203Chip();
        if (x) x->chip.reset();
        c = x;
    } else if (chip == OPN_CHIP_YM2608) {
        auto* x = new (std::nothrow) Opn2608Chip();
        if (x) x->chip.reset();
        c = x;
    }
    if (!c) return nullptr;
    // The power-on state, restored by opn_chip_reset without allocating (saving here is the only allocation).
    c->power_on.reserve(16384);
    c->save(true);
    c->clear_extras();
    c->intf.clear();
    c->samples = 0;
    return c;
}

void opn_chip_destroy(OpnChip* c) { delete c; }

void opn_chip_reset(OpnChip* c) {
    // Restoring reads the saved bytes only (no allocation). Not saved by ymfm and constant here: the fidelity (unused:
    // this wrapper clocks the engines itself) and the operator mapping; the FM engine's prepare counter restarts at the
    // first clock (restore invalidates its caches). The ADPCM-B memory pointer stays.
    c->save(false);
    c->clear_extras();
    c->intf.clear();
    c->samples = 0;
}

void opn_chip_set_adpcm(OpnChip* c, const uint8_t* adpcm, uint32_t size) {
    c->intf.adpcm = adpcm;
    c->intf.adpcm_size = adpcm ? size : 0;
}

void opn_chip_write(OpnChip* c, int port, uint8_t reg, uint8_t value) { c->write_reg(port, reg, value); }

void opn_chip_generate(OpnChip* c, uint32_t mute, OpnOutput* out) {
    c->generate(mute, out);
    ++c->samples;
}

int opn_chip_fm_quiet(const OpnChip* c, int channel) {
    if (channel < 0 || channel >= c->fm_channels()) return 1;
    return c->quiet(channel);
}

int opn_chip_fm_attenuation(const OpnChip* c, int channel, int op) {
    if (channel < 0 || channel >= c->fm_channels() || op < 0 || op > 3) return 0x3FF;
    return c->attenuation(channel, op);
}

uint32_t opn_chip_rhythm_playing(const OpnChip* c) {
    const Opn2608Chip* x = opna(c);
    return x ? x->chip.playing : 0;
}

int opn_chip_rhythm_missing(const OpnChip* c) {
    const Opn2608Chip* x = opna(c);
    return x ? x->chip.missing : 0;
}

int opn_chip_adpcm_playing(const OpnChip* c) {
    const Opn2608Chip* x = opna(c);
    return x ? x->chip.adpcm_playing() : 0;
}

uint64_t opn_chip_samples(const OpnChip* c) { return c->samples; }

uint32_t opn_chip_timer_a_expiries(const OpnChip* c) { return c->intf.timer_a_expiries; }

} // extern "C"
