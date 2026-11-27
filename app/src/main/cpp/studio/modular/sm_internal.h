/* Studio modular instrument. SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef THOR_SM_INTERNAL_H
#define THOR_SM_INTERNAL_H
#include "studio_modular.h"
#include "../modules/studio_dsp.h"

enum { SM_PARAMS=3, SM_INPUTS=3, SM_OUTPUTS=5, SM_PRIOR=5, SM_STRIDE=6 };
enum SmPort { NONE, AUDIO, MOD, PITCH, GATE, EVENT };
typedef struct SmSpec {
    uint8_t ins[SM_INPUTS], outs[SM_OUTPUTS], params, discrete, weight;
    float lo[SM_PARAMS], hi[SM_PARAMS];
} SmSpec;
extern const SmSpec sm_specs[SM_KINDS];
typedef struct SmNode {
    int32_t id;
    float p[SM_PARAMS];
    uint16_t first_edge, end_edge;
    uint8_t kind, wired;
} SmNode;
typedef struct SmEdge {
    float scale, offset;
    uint16_t source; /* flattened signal bank index; previous-sample signals use SM_PRIOR */
    uint8_t input;
    uint8_t sorted; /* index in the stable-ID lookup, also used for live cable ramps */
} SmEdge;
typedef struct SmTarget { float min, max; uint8_t macro, node, param; } SmTarget;
typedef struct SmLink { int32_t id; uint16_t edge; uint8_t destination; } SmLink;
struct SmPlan {
    uint32_t seed;
    SmCost cost;
    SmNode *node; SmEdge *edge; SmTarget *target;
    SmLink *links;
    uint8_t target_start[SM_MACROS+1];
    uint8_t by_id[SM_MAX_NODES]; /* node slots sorted by stable ID */
    uint16_t delayed[SM_MAX_NODES]; /* output indices to snapshot after each frame */
    uint8_t delayed_count;
};
#endif
