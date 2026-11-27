/* Studio modular instrument. SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef THOR_STUDIO_MODULAR_H
#define THOR_STUDIO_MODULAR_H
#include "../modules/studio_modules.h"
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

enum { SM_MAX_NODES=64, SM_MAX_EDGES=256, SM_MACROS=8, SM_MAX_TARGETS=16 };
enum SmKind { SM_NOTE, SM_OSC, SM_NOISE, SM_FILTER, SM_VCA, SM_FUNCTION, SM_RANDOM,
    SM_MIX, SM_OUT, SM_DELAY, SM_FOLD, SM_RESONATOR, SM_KINDS };

/* Host-endian int32 words (decode little endian at the storage boundary).
 * Header: version=1, seed (all 32 bits), nodeCount, edgeCount, targetCount.
 * Then nodeCount records: id, kind, followed by exactly that kind's parameter words;
 * edgeCount records: id, srcId, srcPort, dstId, dstPort, scale, offset;
 * targetCount records: macroIndex, dstId, paramIndex, minimum, maximum. No trailing words.
 * Parameters, scales, offsets and macro endpoints are binary32 bits (also enum params).
 * Positive signed node/edge IDs unique in their own namespace. No duplicate cable endpoints.
 * Exactly one OUT. At most one assignment per (node,param) across all macros. Only continuous
 * params; endpoints within parameter bounds in either order. Result = lerp(min,max,value/127);
 * unassigned params use the node's stored base. Eight macro values live with the host.
 * Continuous ports sum in ascending cable ID order. Gate/event OR; exact types, audio->mod
 * and gate->mod permitted. Scale and offset both [-16,16]; gate/event destinations require
 * identity. Every event cable reads previous sample, as does every cable leaving DELAY.
 * All other edges must form a DAG. No queue; simultaneous pulses at a port coalesce.
 * Module wire indices are the enum above. Inputs / outputs / params (units; min:max):
 * NOTE     - / pitch,gate,trigger,velocity,accent / -     pitch=MIDI semitones, A4=69
 * OSC      pitch,fm,width / audio / wave 0:2 (sine,saw,pulse), tune -48:48 st, width .05:.95
 * NOISE    - / audio / -
 * FILTER   audio,cutoff / audio / cutoff 20:20000 Hz, resonance 0:1; cutoff mod in octaves
 * VCA      audio,level / audio / gain 0:2                unwired level=1
 * FUNCTION trig,gate,rate / level,end / rise 1:8000 ms, fall 1:8000 ms, loop 0:1
 * RANDOM   trig,sample / stepped,smooth / slew 1:8000 ms (one-pole time constant)
 * MIX      a,b / audio / a -4:4, b -4:4, bias -2:2
 * OUT      L,R / - / gain 0:2                           unwired R duplicates L
 * DELAY    audio / audio / -                            1 sample, including feedback
 * FOLD     audio,drive / audio / drive 1:16
 * RESONATOR audio,pitch,fm / audio / tune -48:48 st, decay 10:4000 ms (RT60)
 * Unwired pitch=69, fm in semitones; all others default 0 except as noted. FUNCTION
 * is linear attack/decay on trigger or rising gate, cycling if loop=1. rate in octaves.
 * RANDOM samples a connected mod input or draws bipolar seeded noise on each trigger.
 * Audio L/R bounded [-1,1]; internal audio headroom [-8,8].
 */
typedef struct SmPlan SmPlan;
typedef struct SmVoice SmVoice;
typedef struct SmCost {
    uint32_t nodes, edges, targets, work_units; /* relative sample work; not CPU % */
    size_t plan_bytes, voice_bytes; /* includes all allocations, excludes allocator overhead */
} SmCost;
/* Producer-only. Rate-independent immutable plan. Never borrows words. NULL on failure;
 * optional error gets static message, NULL on success. A NULL plan has zero plan costs. */
SmPlan *sm_compile(const int32_t *words, size_t count, const char **error);
void sm_plan_destroy(SmPlan *plan);
SmCost sm_cost(const SmPlan *plan);
/* Producer-only. Holds basis until rebase/destroy. */
SmVoice *sm_voice_create(uint32_t rate, const StudioDspTables *basis);
void sm_voice_destroy(SmVoice *v);
/* At exclusive transfer, an equivalent prepared basis may replace the original. */
void sm_voice_rebase(SmVoice *v, const StudioDspTables *basis);
/* Remaining functions: same thread as render, no heap/locks. Plan is borrowed until
 * rebind/destroy. migrate=true preserves compatible running state by node ID+kind and
 * keeps host note/release; false (or NULL plan) hard silences, no automatic note-on.
 * Eight supplied macro values are the NEW runtime values; NULL means all zero. */
void sm_bind(SmVoice *v, const SmPlan *plan, const uint8_t macros[SM_MACROS], bool migrate);
void sm_macro(SmVoice *v, unsigned index, unsigned value);
void sm_note(SmVoice *v, float hz, float velocity, float accent, bool legato);
void sm_pitch(SmVoice *v, float hz);
void sm_off(SmVoice *v);   /* gate down, unconditional <=32 ms host fade */
void sm_cut(SmVoice *v);   /* gate down, unconditional <=4 ms host fade */
void sm_clear(SmVoice *v); /* panic/seek: immediate silence; keeps binding/macros */
bool sm_active(const SmVoice *v);
void sm_frame(SmVoice *v, float *left, float *right); /* overwrite distinct outputs */
#ifdef __cplusplus
}
#endif
#endif
