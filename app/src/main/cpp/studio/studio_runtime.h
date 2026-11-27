/* Studio score resources, SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef THOR_STUDIO_RUNTIME_H
#define THOR_STUDIO_RUNTIME_H
#include "../chip_engine.h"
#ifdef __cplusplus
extern "C" {
#endif
extern const ChipEngineOps studio_mono_ops;
typedef struct StudioBank StudioBank;
typedef struct StudioWaveBank StudioWaveBank;
typedef struct StudioLive StudioLive;
/* Control thread only. A PCM1 trailer transfers owned immutable PCM once per bank edit.
 * An empty trailer decodes to NULL. Errors never change *out. */
int studio_bank_decode(const uint8_t *bytes,size_t size,StudioBank **out);
void studio_bank_drop(StudioBank *bank);
/* Attach a borrowed, validated bank to this score. start/audition retain it on their machines. */
int studio_bind_bank(ChipImage *image,StudioBank *bank);
/* Live host: validate fixed score prefix + optional PCM1. Old bank retained on failure. */
int studio_load_score(ChipImage *image,const uint8_t *bytes,size_t size,StudioBank **current);
/* Producer only. Reuse unchanged slots across publications, independent of PCM1. The
 * machine supplies only its immutable sine basis. Score borrows *owner until prepared.
 * Direct start/audition on a raw validated image prepares into its own spare rack instead. */
int studio_prepare_waves(ChipImage *image,StudioWaveBank **owner,const ChipMachine *basis);
void studio_wave_bank_drop(StudioWaveBank *bank);
/* After studio_prepare_waves, prepare/retain storage for both paths outside callback; the publication slot must be
 * exclusively producer-owned. apply[_audition]_image consumes its corresponding rack once.
 * A reclaimed publication slot owns the retired rack; reuse/dispose it on the control thread. */
int studio_live_prepare(StudioLive **owner,ChipImage *image,uint32_t rate);
void studio_live_destroy(StudioLive *owner);
#ifdef __cplusplus
}
#endif
#endif
