/* The one installed YM2608 rhythm ROM (ym2608_rhythm_rom.h): static storage published with a release store, read by
 * every chip that plays rhythm drums (nes/nes_epsm_ymfm.cpp, opn/opn_ymfm.cpp) with an acquire load. */
#include "ym2608_rhythm_rom.h"

#include <stdatomic.h>
#include <string.h>

static uint8_t g_rom[YM2608_RHYTHM_ROM_SIZE];
static atomic_int g_rom_ready;

int ym2608_rhythm_rom_install(const uint8_t* rom, size_t size) {
    if (!rom || size != YM2608_RHYTHM_ROM_SIZE) return -1;
    if (atomic_load_explicit(&g_rom_ready, memory_order_acquire)) return memcmp(g_rom, rom, YM2608_RHYTHM_ROM_SIZE) ? -1 : 0;
    memcpy(g_rom, rom, YM2608_RHYTHM_ROM_SIZE);
    atomic_store_explicit(&g_rom_ready, 1, memory_order_release);
    return 0;
}

int ym2608_rhythm_rom_installed(void) { return atomic_load_explicit(&g_rom_ready, memory_order_acquire) ? 1 : 0; }

const uint8_t* ym2608_rhythm_rom(void) {
    return atomic_load_explicit(&g_rom_ready, memory_order_acquire) ? g_rom : NULL;
}
