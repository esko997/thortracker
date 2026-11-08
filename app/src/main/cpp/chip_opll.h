#ifndef THOR_CHIP_OPLL_H
#define THOR_CHIP_OPLL_H

#include "third_party/emu2413/emu2413.h"

/* emu2413 lazily initializes shared tables in OPLL_new. All hosts/engines must use this factory so
 * background WAV exports and playback cannot initialize those tables concurrently. */
OPLL* chip_opll_new(uint32_t clock, uint32_t rate);

#endif
