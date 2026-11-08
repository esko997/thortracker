/* System id -> engine implementation. A new chip adds its ops table here. */
#include <string.h>

#include "chip_engine.h"

extern const ChipEngineOps huge_chip_ops;
extern const ChipEngineOps nes_chip_ops;
extern const ChipEngineOps nes_vrc6_chip_ops;
extern const ChipEngineOps nes_vrc7_chip_ops;
extern const ChipEngineOps sid_chip_ops;
extern const ChipEngineOps opl3_chip_ops;
extern const ChipEngineOps ym_chip_ops;
extern const ChipEngineOps pokey_chip_ops;

static const ChipEngineOps* const systems[] = {&huge_chip_ops, &nes_chip_ops, &nes_vrc6_chip_ops, &nes_vrc7_chip_ops, &sid_chip_ops,
                                               &opl3_chip_ops, &ym_chip_ops, &pokey_chip_ops};

const ChipEngineOps* chip_engine_ops(const char* system_id) {
    if (!system_id) return NULL;
    for (size_t i = 0; i < sizeof systems / sizeof systems[0]; ++i) {
        if (strcmp(systems[i]->system_id, system_id) == 0) return systems[i];
    }
    return NULL;
}
