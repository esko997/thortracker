/* System id -> engine implementation. A new chip adds its ops table here. */
#include <string.h>

#include "chip_engine.h"

extern const ChipEngineOps huge_chip_ops;
extern const ChipEngineOps nes_chip_ops;
extern const ChipEngineOps nes_vrc6_chip_ops;
extern const ChipEngineOps nes_vrc7_chip_ops;
extern const ChipEngineOps nes_n163_1_chip_ops, nes_n163_2_chip_ops, nes_n163_3_chip_ops, nes_n163_4_chip_ops;
extern const ChipEngineOps nes_n163_5_chip_ops, nes_n163_6_chip_ops, nes_n163_7_chip_ops, nes_n163_8_chip_ops;
extern const ChipEngineOps nes_fds_chip_ops;
extern const ChipEngineOps nes_s5b_chip_ops;
extern const ChipEngineOps nes_mmc5_chip_ops;
extern const ChipEngineOps nes_epsm_chip_ops;
extern const ChipEngineOps sid_chip_ops;
extern const ChipEngineOps opl3_chip_ops;
extern const ChipEngineOps ym_chip_ops;
extern const ChipEngineOps sms_chip_ops;

static const ChipEngineOps* const systems[] = {&huge_chip_ops,       &nes_chip_ops,        &nes_vrc6_chip_ops,
                                               &nes_vrc7_chip_ops,   &nes_n163_1_chip_ops, &nes_n163_2_chip_ops,
                                               &nes_n163_3_chip_ops, &nes_n163_4_chip_ops, &nes_n163_5_chip_ops,
                                               &nes_n163_6_chip_ops, &nes_n163_7_chip_ops, &nes_n163_8_chip_ops,
                                               &nes_fds_chip_ops,    &nes_s5b_chip_ops,    &nes_mmc5_chip_ops,
                                               &nes_epsm_chip_ops,   &sid_chip_ops,        &opl3_chip_ops,
                                               &ym_chip_ops,        &sms_chip_ops};

const ChipEngineOps* chip_engine_ops(const char* system_id) {
    if (!system_id) return NULL;
    for (size_t i = 0; i < sizeof systems / sizeof systems[0]; ++i) {
        if (strcmp(systems[i]->system_id, system_id) == 0) return systems[i];
    }
    return NULL;
}
