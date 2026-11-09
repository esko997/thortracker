// Minimal stand-in for Stella's bspf.hxx, written for Thor Tracker (not part of Stella): just what the unmodified
// AudioChannel.cxx / AudioChannel.hxx of Stella 7.0 use. The integer types as Stella's bspf.hxx declares them (global
// namespace) and a no-op `cerr` that accepts `<<` (AudioChannel only prints from its save/load error paths, which the
// fixed-buffer Serializer in Serializable.hxx never reaches). No iostream, no allocation. Compiled as C++ only, through
// tia/tia_stella.cpp.
#ifndef BSPF_HXX
#define BSPF_HXX

#include <climits>
#include <cstdint>

using Int8 = int8_t;
using uInt8 = uint8_t;
using Int16 = int16_t;
using uInt16 = uint16_t;
using Int32 = int32_t;
using uInt32 = uint32_t;
using Int64 = int64_t;
using uInt64 = uint64_t;

// Swallows anything streamed into it.
struct ThorTrackerNullStream {
    template <class T>
    const ThorTrackerNullStream& operator<<(const T&) const {
        return *this;
    }
};
static const ThorTrackerNullStream cerr{};

#endif  // BSPF_HXX
