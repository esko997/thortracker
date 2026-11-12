#ifndef THORTRACKER_GBA_IMAGE_H
#define THORTRACKER_GBA_IMAGE_H
#include <stdint.h>
#include <stddef.h>
#include "../chip_engine.h"
#define GBA_IMAGE_SIZE (2u * 1024u * 1024u)
#define GBA_BANK_OFFSET 64u
#define GBA_NATIVE_RATE 15768u
#define GBA_CHANNELS 8u
struct GbaImage {
    alignas(8) uint8_t bytes[GBA_IMAGE_SIZE];
    uint32_t module, used, sample[256], instrument[64], pattern[255];
    uint32_t rows[255][257];
    uint16_t row_count[255];
    uint16_t orders, instruments, samples, patterns;
    uint32_t valid;
};
int gba_load_image(ChipImage*, const uint8_t*, size_t);
extern "C" const ChipEngineOps gba_maxmod_ops;
#endif
