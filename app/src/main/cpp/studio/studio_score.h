/* Internal STM2 layout, mirrored by StudioEngine.kt. No C struct layout enters the file. */
#ifndef THOR_STUDIO_SCORE_H
#define THOR_STUDIO_SCORE_H
#include "studio_runtime.h"
#include "modules/studio_modules.h"
#include <string.h>
enum { TRACKS=8,BUS=2,STRIPS=10,INSERTS=4,SLOTS=32,NOTES=116,ROWS=64,PATTERNS=256,EVENTS=32768,HZ=48,
       PATCH_BASE=1328,PATCH_BYTES=312,GRAPH_BASE=PATCH_BASE+SLOTS*PATCH_BYTES,STRIP_BYTES=136,
       INDEX=GRAPH_BASE+STRIPS*STRIP_BYTES,IMAGE_SIZE=INDEX+2*(PATTERNS*ROWS+1)+8*EVENTS };
enum { MONO, SAMPLER, FM, DRUM, DELAY, DRIVE, CRUSHER, REVERB, CHORUS };
enum { PATCH,ATTACK,RELEASE,CUT,PITCH_RESET,SLIDE,EXPRESSION,CUTOFF,RESONANCE,SLICE,CONTROL };
static inline unsigned u16(const uint8_t *p) { return p[0]|((unsigned)p[1]<<8); }
static inline uint32_t u32(const uint8_t *p) { return u16(p)|((uint32_t)u16(p+2)<<16); }
struct StudioBank { unsigned refs; StudioPcm pcm[SLOTS+1]; float samples[]; };
typedef struct { StudioFx *fx; uint32_t id; unsigned kind,mask; int value[5]; } StudioPedal;
typedef struct { StudioPedal pedals[STRIPS][INSERTS]; StudioBank *bank; } StudioRack;
struct StudioLive { StudioRack rack[2]; };
struct ChipImage {
    uint32_t bytes,event_base;
    float frequencies[NOTES]; StudioBank *bank; StudioLive *live;
    uint8_t data[IMAGE_SIZE];
};
static inline const uint8_t *record(const ChipImage *s,int slot) {return s->data+PATCH_BASE+PATCH_BYTES*slot;}
static inline const uint8_t *strip(const ChipImage *s,int ch) {return s->data+GRAPH_BASE+STRIP_BYTES*ch;}
int studio_score_load(ChipImage *s,const uint8_t *p,size_t size);
int studio_rack_prepare(StudioRack *r,const ChipImage *s,uint32_t rate);
void studio_rack_dispose(StudioRack *r);
void studio_rack_swap(StudioRack *current,StudioRack *incoming);
void studio_pedal_set(StudioPedal *p,const uint8_t *wire,int speed);
#endif
