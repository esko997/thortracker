/* Studio score resources, SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef THOR_STUDIO_RUNTIME_H
#define THOR_STUDIO_RUNTIME_H
#include "../chip_engine.h"
#ifdef __cplusplus
extern "C" {
#endif
extern const ChipEngineOps studio_mono_ops;
typedef struct StudioBank StudioBank;
typedef struct StudioLive StudioLive;
/* Control thread only. A PCM1 trailer transfers owned immutable PCM once per bank edit.
 * An empty trailer decodes to NULL. Errors never change *out. */
int studio_bank_decode(const uint8_t *bytes,size_t size,StudioBank **out);
void studio_bank_drop(StudioBank *bank);
/* Attach a borrowed, validated bank to this score. start/audition retain it on their machines. */
int studio_bind_bank(ChipImage *image,StudioBank *bank);
/* Live host: validate fixed score prefix + optional PCM1. Old bank retained on failure. */
int studio_load_score(ChipImage *image,const uint8_t *bytes,size_t size,StudioBank **current);
/* Prepare/retain storage for both render paths outside callback; the publication slot must be
 * exclusively producer-owned. apply[_audition]_image consumes its corresponding rack once.
 * A reclaimed publication slot owns the retired rack; reuse/dispose it on the control thread. */
int studio_live_prepare(StudioLive **owner,ChipImage *image,uint32_t rate);
void studio_live_destroy(StudioLive *owner);
#ifdef __cplusplus
}
#endif
#endif
