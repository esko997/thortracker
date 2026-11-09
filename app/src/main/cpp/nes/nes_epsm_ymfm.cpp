// The EPSM's YMF288 over ymfm, and the rhythm ROM firmware (nes_epsm.h).
//
// ymfm's OPN family is compiled once, in ym/ym_ymfm.cpp, which also explicitly instantiates the OPNA register and FM
// engine templates (opn_registers_base<true>, fm_engine_base<opna_registers>) this file calls; here only ymfm's headers
// are included and those templates are declared extern, so the program holds one copy of each.
#include "nes_epsm.h"

#include <atomic>
#include <cstring>
#include <new>
#include <vector>

#include "ymfm_opn.h"

extern template class ymfm::opn_registers_base<true>;
extern template class ymfm::fm_engine_base<ymfm::opna_registers>;

namespace {

uint8_t g_rom[NES_EPSM_RHYTHM_ROM_SIZE];
std::atomic<int> g_rom_ready{0};

const uint8_t* installed_rom() { return g_rom_ready.load(std::memory_order_acquire) ? g_rom : nullptr; }

// The rhythm ROM as the ADPCM-A engine's external memory (byte addresses 0..$1FFF).
class EpsmInterface : public ymfm::ymfm_interface {
public:
    const uint8_t* rom = nullptr;  // set at a rhythm key-on that finds the ROM installed
    uint8_t ymfm_external_read(ymfm::access_class type, uint32_t address) override {
        if (type != ymfm::ACCESS_ADPCM_A || !rom || address >= NES_EPSM_RHYTHM_ROM_SIZE) return 0;
        return rom[address];
    }
};

// ymf288 with its one-sample generate split by part and with mutes that only leave parts out of the sums:
// ymf288::generate + clock_fm_and_adpcm at OPN_FIDELITY_MED (one FM/ADPCM clock per output sample) with the SSG
// resampled 9:2 as ssg_resampler::resample_2_9 does. FM outputs are computed channel by channel (every channel the
// chip itself would compute, so FM feedback inputs advance whether muted or not).
class EpsmChip : public ymfm::ymf288 {
public:
    explicit EpsmChip(EpsmInterface& intf) : ymfm::ymf288(intf), m_rom_intf(intf) {}

    uint32_t playing = 0;   // rhythm drums keyed on, not yet at their end address
    uint32_t silent = 0;    // rhythm drums keyed on without the ROM
    int missing = 0;        // a rhythm key-on found no ROM since reset
    uint32_t ssg_index = 0; // the 9:2 resampler's output sample index
    ymfm::ssg_engine::output_data ssg_last{};

    void clear_extras() {
        playing = silent = 0;
        missing = 0;
        ssg_index = 0;
        ssg_last.clear();
        m_rom_intf.rom = nullptr;
    }

    void write_port(int port, uint8_t value) {
        // $10 (A1 = 0): ADPCM-A key control, bit 7 = dump (key off), bits 0-5 the drums.
        if ((port & 3) == 1 && m_address == 0x10) {
            const uint32_t drums = value & 0x3Fu;
            if (value & 0x80) {
                playing &= ~drums;
            } else if (drums) {
                const uint8_t* rom = installed_rom();
                if (rom) {
                    m_rom_intf.rom = rom;
                    silent &= ~drums;
                } else {
                    silent |= drums;
                    missing = 1;
                }
                playing |= drums;
            }
        }
        write(uint32_t(port & 3), value);
    }

    void generate_parts(uint32_t mute, NesEpsmOutput* out) {
        // FM and ADPCM-A (clock_fm_and_adpcm): bit 7 of $29 selects six FM channels (else three).
        const uint32_t fmmask = ymfm::bitfield(m_irq_enable, 7) ? 0x3f : 0x07;
        const uint32_t env_counter = m_fm.clock(fm_engine::ALL_CHANNELS);
        if (ymfm::bitfield(env_counter, 0, 2) == 0) playing &= ~m_adpcm_a.clock(ymfm::bitfield(env_counter, 2) ? 0x0f : 0x3f);
        fm_engine::output_data sum, one;
        sum.clear();
        for (uint32_t ch = 0; ch < NES_EPSM_FM; ++ch) {
            if (!ymfm::bitfield(fmmask, ch)) continue;
            m_fm.output(one.clear(), 1, 32767, 1u << ch);
            if (mute & NES_EPSM_MUTE_FM(ch)) continue;
            sum.data[0] += one.data[0];
            sum.data[1] += one.data[1];
        }
        out->fm[0] = sum.data[0];
        out->fm[1] = sum.data[1];
        ymfm::ymfm_output<2> rhythm;
        rhythm.clear();
        const uint32_t rhythm_mask = ~((mute >> 9) | silent) & 0x3f;
        m_adpcm_a.output(rhythm, rhythm_mask);
        out->rhythm[0] = rhythm.data[0];
        out->rhythm[1] = rhythm.data[1];
        // SSG: 9 SSG samples to every 2 outputs (ssg_resampler<..., 2, true>::resample_2_9 and write_to_output).
        int32_t s[3] = {0, 0, 0};
        if (ssg_index & 1) add_last(s, 1);
        for (int i = 0; i < 4; ++i) clock_and_add(s, 2);
        if (!(ssg_index & 1)) clock_and_add(s, 1);
        ++ssg_index;
        int32_t mixed = 0;
        for (int c = 0; c < NES_EPSM_SSG; ++c) {
            if (!(mute & NES_EPSM_MUTE_SSG(c))) mixed += s[c];
        }
        out->ssg = mixed * 2 / (3 * 9);
    }

    int attenuation(int channel, int op) const {
        auto* chan = m_fm.debug_channel(uint32_t(channel));
        auto* oper = chan ? chan->debug_operator(uint32_t(op)) : nullptr;
        return oper ? oper->debug_eg_attenuation() : 0x3FF;
    }

    // Carriers (operator bits, op 1 = bit 0) of OPN algorithms 0-7: op4; op4; op4; op4; op2+op4; op2-4; op2-4; all.
    // Quiet = at or past ymfm's EG_QUIET attenuation (0x380, fm_operator's private constant), where an operator
    // outputs nothing.
    int quiet(int channel) const {
        static const uint8_t carriers[8] = {0x8, 0x8, 0x8, 0x8, 0xA, 0xE, 0xE, 0xF};
        const uint32_t reg = 0xB0 + (channel % 3) + (channel >= 3 ? 0x100 : 0);
        const uint8_t c = carriers[const_cast<fm_engine&>(m_fm).regs().read(uint16_t(reg)) & 7];
        for (int op = 0; op < 4; ++op) {
            if ((c >> op & 1) && attenuation(channel, op) < 0x380) return 0;
        }
        return 1;
    }

private:
    void add_last(int32_t* s, int32_t scale) {
        for (int c = 0; c < 3; ++c) s[c] += ssg_last.data[c] * scale;
    }
    void clock_and_add(int32_t* s, int32_t scale) {
        m_ssg.clock();
        m_ssg.output(ssg_last);
        add_last(s, scale);
    }

    EpsmInterface& m_rom_intf;
};

} // namespace

struct NesEpsmChip {
    EpsmInterface intf;
    EpsmChip chip;
    std::vector<uint8_t> power_on; // ymfm save_restore state right after construction, reset and MED fidelity
    NesEpsmChip() : chip(intf) {}
};

extern "C" {

int nes_epsm_install_rhythm_rom(const uint8_t* rom, size_t size) {
    if (!rom || size != NES_EPSM_RHYTHM_ROM_SIZE) return -1;
    if (g_rom_ready.load(std::memory_order_acquire)) return std::memcmp(g_rom, rom, NES_EPSM_RHYTHM_ROM_SIZE) ? -1 : 0;
    std::memcpy(g_rom, rom, NES_EPSM_RHYTHM_ROM_SIZE);
    g_rom_ready.store(1, std::memory_order_release);
    return 0;
}

int nes_epsm_rhythm_rom_installed(void) { return g_rom_ready.load(std::memory_order_acquire) ? 1 : 0; }

NesEpsmChip* nes_epsm_chip_create(void) {
    NesEpsmChip* c = new (std::nothrow) NesEpsmChip();
    if (!c) return nullptr;
    c->chip.set_fidelity(ymfm::OPN_FIDELITY_MED);
    c->chip.reset();
    c->power_on.reserve(8192);
    ymfm::ymfm_saved_state state(c->power_on, true);
    c->chip.save_restore(state);
    c->chip.clear_extras();
    return c;
}

void nes_epsm_chip_destroy(NesEpsmChip* chip) { delete chip; }

void nes_epsm_chip_reset(NesEpsmChip* chip) {
    // Restoring reads the saved bytes only (no allocation). Not saved by ymfm and constant here: the fidelity and the
    // operator mapping; the FM engine's prepare counter restarts at the first clock (restore invalidates its caches).
    ymfm::ymfm_saved_state state(chip->power_on, false);
    chip->chip.save_restore(state);
    chip->chip.clear_extras();
}

void nes_epsm_chip_write(NesEpsmChip* chip, int port, uint8_t value) { chip->chip.write_port(port, value); }

void nes_epsm_chip_generate(NesEpsmChip* chip, uint32_t mute, NesEpsmOutput* out) { chip->chip.generate_parts(mute, out); }

uint32_t nes_epsm_chip_rhythm_playing(const NesEpsmChip* chip) { return chip->chip.playing; }

uint32_t nes_epsm_chip_rhythm_silent(const NesEpsmChip* chip) { return chip->chip.silent; }

int nes_epsm_chip_rhythm_missing(const NesEpsmChip* chip) { return chip->chip.missing; }

int nes_epsm_chip_fm_attenuation(const NesEpsmChip* chip, int channel, int op) {
    if (channel < 0 || channel >= NES_EPSM_FM || op < 0 || op > 3) return 0x3FF;
    return chip->chip.attenuation(channel, op);
}

int nes_epsm_chip_fm_quiet(const NesEpsmChip* chip, int channel) {
    if (channel < 0 || channel >= NES_EPSM_FM) return 1;
    return chip->chip.quiet(channel);
}

} // extern "C"
