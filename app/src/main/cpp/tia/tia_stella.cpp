// The C interface of Stella 7.0's TIA audio channel (tia_stella.h). Stella's AudioChannel.cxx (unmodified) is compiled
// here, once, against the minimal shim in third_party/stella-tia/shim (on the include path: "bspf.hxx",
// "Serializable.hxx"). Its own "AudioChannel.hxx" resolves next to it. The vendored save()/load() contain try/catch
// (never taken with the shim's Serializer), so this file is compiled with C++ exceptions enabled (the default of every
// toolchain the project uses; CMake adds -fexceptions for it explicitly).
#include "../third_party/stella-tia/AudioChannel.cxx"

#include "tia_stella.h"

#include <cstring>
#include <new>

static_assert(2 * sizeof(AudioChannel) <= TIS_STORAGE_BYTES, "TiaStella holds two channels");
static_assert(alignof(AudioChannel) <= alignof(TiaStella), "TiaStella is aligned for AudioChannel");

static AudioChannel* channel(TiaStella* s, int c) {
    return std::launder(reinterpret_cast<AudioChannel*>(s->u.bytes + (c & 1) * sizeof(AudioChannel)));
}

static const AudioChannel* channel(const TiaStella* s, int c) {
    return std::launder(reinterpret_cast<const AudioChannel*>(s->u.bytes + (c & 1) * sizeof(AudioChannel)));
}

extern "C" void tis_init(TiaStella* s) {
    std::memset(s, 0, sizeof *s);
    for (int c = 0; c < 2; ++c) new (s->u.bytes + c * sizeof(AudioChannel)) AudioChannel();
    tis_reset(s);
}

extern "C" void tis_destroy(TiaStella* s) {
    for (int c = 0; c < 2; ++c) channel(s, c)->~AudioChannel();
}

extern "C" void tis_reset(TiaStella* s) {
    channel(s, 0)->reset();
    channel(s, 1)->reset();
}

extern "C" void tis_audc(TiaStella* s, int c, uint8_t value) { channel(s, c)->audc(value); }
extern "C" void tis_audf(TiaStella* s, int c, uint8_t value) { channel(s, c)->audf(value); }
extern "C" void tis_audv(TiaStella* s, int c, uint8_t value) { channel(s, c)->audv(value); }

extern "C" void tis_phase0(TiaStella* s) {
    channel(s, 0)->phase0();
    channel(s, 1)->phase0();
}

extern "C" void tis_phase1(TiaStella* s) {
    channel(s, 0)->phase1();
    channel(s, 1)->phase1();
}

extern "C" uint8_t tis_actual_volume(const TiaStella* s, int c) { return channel(s, c)->actualVolume(); }

extern "C" void tis_state(const TiaStella* s, int c, uint8_t out[TIS_STATE_BYTES]) {
    Serializer ser;
    channel(s, c)->save(ser);
    std::memset(out, 0, TIS_STATE_BYTES);
    std::memcpy(out, ser.data(), ser.size() < TIS_STATE_BYTES ? static_cast<size_t>(ser.size()) : TIS_STATE_BYTES);
}
