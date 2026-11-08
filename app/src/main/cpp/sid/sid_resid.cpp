/* reSID behind a C interface (sid_resid.h). */
#include "sid_resid.h"

#include <new>
#include <string.h>

#include "sid.h"

/* reSID's reset() is a RES pin reset: oscillator accumulators keep their phase, and the resampler (ring
 * buffer, phase) and the 8580 write pipeline are left as they were, so a restarted song would not render
 * the same samples twice. power_on() also puts those back where construction and
 * set_sampling_parameters() leave them (accumulators $555555, as the constructor sets them), without
 * reallocating. */
struct SidResid : public reSID::SID {
    void power_on(reSID::chip_model model) {
        set_chip_model(model);
        reset();
        set_voice_mask(0x07); /* reSID's default: the three voices, no external input */
        State state = read_state();
        for (int i = 0; i < 3; ++i) state.accumulator[i] = 0x555555;
        state.write_pipeline = 0;
        state.write_address = 0;
        write_state(state);
        sample_offset = 0;
        sample_prev = sample_now = 0;
        sample_index = 0;
        if (sample) memset(sample, 0, sizeof(short) * RINGSIZE * 2);
    }

    /* clock(delta) without samples, keeping the resampler's phase where producing them would have left
     * it (clock_resample's arithmetic), so output resumes on the same sample grid as straight play. */
    void skip(reSID::cycle_count delta) {
        clock(delta);
        for (;;) {
            const reSID::cycle_count next = sample_offset + cycles_per_sample;
            reSID::cycle_count step = next >> FIXP_SHIFT;
            if (step > delta) step = delta;
            if ((delta -= step) == 0) {
                sample_offset -= step << FIXP_SHIFT;
                break;
            }
            sample_offset = next & FIXP_MASK;
        }
    }
};

extern "C" SidResid* sid_resid_new(double clock_hz, uint32_t sample_rate, int model, int method) {
    SidResid* s = new (std::nothrow) SidResid;
    if (!s) return nullptr;
    const reSID::sampling_method m = method == SID_RESID_INTERPOLATE ? reSID::SAMPLE_INTERPOLATE : reSID::SAMPLE_RESAMPLE;
    if (!s->set_sampling_parameters(clock_hz, m, (double)sample_rate)) {
        delete s;
        return nullptr;
    }
    sid_resid_reset(s, model);
    return s;
}

extern "C" void sid_resid_free(SidResid* s) { delete s; }

extern "C" void sid_resid_reset(SidResid* s, int model) {
    s->power_on(model == SID_RESID_6581 ? reSID::MOS6581 : reSID::MOS8580);
}

/* Allocation-free; reSID resets the filter's integrators (the player rewrites the cutoff every tick). */
extern "C" void sid_resid_set_model(SidResid* s, int model) {
    s->set_chip_model(model == SID_RESID_6581 ? reSID::MOS6581 : reSID::MOS8580);
}

extern "C" void sid_resid_write(SidResid* s, uint8_t reg, uint8_t value) { s->write(reg & 0x1F, value); }

extern "C" void sid_resid_clock(SidResid* s, uint32_t cycles) {
    if (cycles) s->skip((reSID::cycle_count)cycles);
}

extern "C" int sid_resid_render(SidResid* s, uint32_t cycles, int16_t* out, int max) {
    reSID::cycle_count delta = (reSID::cycle_count)cycles;
    int n = 0;
    while (delta > 0 && n < max) n += s->clock(delta, out + n, max - n);
    if (delta > 0) s->clock(delta); /* buffer full (cannot happen with a tick-sized buffer) */
    return n;
}

extern "C" void sid_resid_set_voice_mask(SidResid* s, unsigned mask) { s->set_voice_mask(mask & 0x07); }

extern "C" int sid_resid_envelope(SidResid* s, int voice) {
    if (voice < 0 || voice > 2) return 0;
    return (int)s->read_state().envelope_counter[voice];
}
