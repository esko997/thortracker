/* Minimal stand-in for Mednafen's <mednafen/mednafen.h>, written for Thor Tracker (not part of Mednafen): just what
 * the unmodified pce_psg.cpp / pce_psg.h of Mednafen 1.32.1 use. The integer types as Mednafen's types.h declares
 * them (global namespace), INLINE, MDFN_COLD, the C headers it relies on, and the save-state declarations reduced to
 * no-ops (Thor Tracker never saves PSG state: PCE_PSG::StateAction only has to compile). Compiled as C++ only, through
 * pce/pce_psg.cpp. */
#ifndef THORTRACKER_MEDNAFEN_SHIM_H_
#define THORTRACKER_MEDNAFEN_SHIM_H_

#include <assert.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef int8_t int8;
typedef int16_t int16;
typedef int32_t int32;
typedef int64_t int64;
typedef uint8_t uint8;
typedef uint16_t uint16;
typedef uint32_t uint32;
typedef uint64_t uint64;

#define INLINE inline
#if defined(__GNUC__) || defined(__clang__)
#define MDFN_COLD __attribute__((cold))
#else
#define MDFN_COLD
#endif

namespace Mednafen {

struct StateMem;

/* One save-state variable: what Mednafen's SFVAR/SFVARN record (address, size, name); never read here. */
struct SFORMAT {
    const void* data;
    uint32 size;
    const char* name;
};

static inline void MDFNSS_StateAction(StateMem*, const unsigned, const bool, const SFORMAT*, const char*,
                                      const bool = false) {}

}  // namespace Mednafen

#define SFVARN(x, n) { (const void*)&(x), (uint32)sizeof(x), (n) }
#define SFVAR(x) SFVARN(x, #x)
#define SFEND { nullptr, 0, nullptr }

#endif /* THORTRACKER_MEDNAFEN_SHIM_H_ */
