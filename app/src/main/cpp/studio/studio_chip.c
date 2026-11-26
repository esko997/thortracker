/* Studio native player. STM1 (StudioEngine.kt) stores musical events, not MIDI bytes.
 * GPL-3.0-or-later. Only create allocates; each machine owns its compact validated image. */
#include "../chip_engine.h"
#include "mono_synth.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

enum { TRACKS=8, SLOTS=32, NOTES=116, ROWS=64, PATTERNS=256, EVENTS=32768, INDEX=1328,
       IMAGE_SIZE=INDEX+2*(PATTERNS*ROWS+1)+8*EVENTS, HZ=48 };
enum { PATCH, ATTACK, RELEASE, CUT, PITCH_RESET, SLIDE, EXPRESSION, CUTOFF, RESONANCE };
static unsigned u16(const uint8_t *p) { return p[0] | ((unsigned)p[1]<<8); }
static uint32_t u32(const uint8_t *p) { return u16(p) | ((uint32_t)u16(p+2)<<16); }
static float cutoff(float v) { return 30.f*powf(16000.f/30.f,v); }
struct ChipImage {
    uint32_t bytes, event_base;
    float frequencies[NOTES], transpose[SLOTS], cutoff_cc[128];
    MonoSynthPatch patches[SLOTS];
    uint8_t data[IMAGE_SIZE]; /* only bytes through last event need copying into the player */
};
typedef struct {
    MonoSynth synth;
    int slot, note, tail, expression, slide, cutoff_cc, resonance_cc;
    bool gate, tied, muted;
    MonoSynthPatch applied;
} Lane;
struct ChipMachine {
    ChipImage image;
    Lane lanes[TRACKS];
    int rate, order, row, tick, fraction, elapsed, row_frames, speed, cursor, end, audition;
    bool releasing;
};

static int load_image(ChipImage *s,const uint8_t *p,size_t size) {
    if (!s || !p || size!=IMAGE_SIZE || u32(p)!=0x314d5453u || !p[4] || p[4]>TRACKS || !p[5]) return CHIP_ERR_IMAGE;
    unsigned orders=u16(p+8), patterns=u16(p+10), events=u16(p+12);
    if (!orders || orders>256 || p[6]>=orders || !patterns || patterns>PATTERNS || events>EVENTS) return CHIP_ERR_IMAGE;
    for (unsigned i=0;i<p[4];i++) {
        const uint8_t *t=p+16+6*i;
        if (!u32(t) || !t[4] || t[4]>127 || t[5]>1) return CHIP_ERR_IMAGE;
        for (unsigned j=0;j<i;j++) if (u32(t)==u32(p+16+6*j)) return CHIP_ERR_IMAGE;
    }
    for (int i=0;i<NOTES;i++) {
        uint32_t bits=u32(p+64+4*i); float hz;
        memcpy(&hz,&bits,sizeof hz);
        if (!isfinite(hz) || hz<=0.f) return CHIP_ERR_IMAGE;
        s->frequencies[i]=hz;
    }
    for (int i=0;i<SLOTS;i++) {
        const uint8_t *v=p+528+9*i;
        if (v[0]>1 || v[7]>48) return CHIP_ERR_IMAGE;
        for (int j=1;j<9;j++) if (v[j]>100) return CHIP_ERR_IMAGE;
        s->patches[i]=(MonoSynthPatch){v[0],cutoff(v[1]*.01f),v[2]*.01f,v[3]*.06f,
            .01f*powf(400.f,v[4]*.01f),v[5]*.01f,2.f*v[6]*v[6]*.0001f,v[8]*.01f};
        s->transpose[i]=exp2f(((int)v[7]-24)/12.f);
    }
    for (int i=0;i<128;i++) s->cutoff_cc[i]=cutoff(i/127.f);
    for (unsigned i=0;i<orders;i++) if (p[816+i]>=patterns) return CHIP_ERR_IMAGE;
    for (unsigned i=0;i<patterns;i++) if (!p[1072+i] || p[1072+i]>ROWS) return CHIP_ERR_IMAGE;
    unsigned rows=patterns*ROWS, base=INDEX+2*(rows+1);
    if (u16(p+INDEX)!=0 || u16(p+base-2)!=events) return CHIP_ERR_IMAGE;
    for (unsigned r=0;r<rows;r++) {
        unsigned begin=u16(p+INDEX+r*2), end=u16(p+INDEX+r*2+2), prev=0;
        if (end<begin || end>events || end-begin>TRACKS*48) return CHIP_ERR_IMAGE;
        for (unsigned i=begin;i<end;i++) {
            const uint8_t *e=p+base+8*i;
            if (u16(e)<prev || e[2]>=p[4] || !p[21+6*e[2]] || e[3]>RESONANCE) return CHIP_ERR_IMAGE;
            if ((e[3]==PATCH && (!e[4] || e[4]>SLOTS)) || ((e[3]==ATTACK || e[3]==RELEASE) && e[4]>=NOTES) ||
                (e[3]==ATTACK && (e[6]>127 || e[7]>1)) || (e[3]>=CUTOFF && e[4]>127)) return CHIP_ERR_IMAGE;
            prev=u16(e);
        }
    }
    s->bytes=base+events*8; s->event_base=base; memcpy(s->data,p,s->bytes);
    return CHIP_OK;
}
static ChipMachine *create(uint32_t rate) {
    if (rate<8000 || rate>CHIP_MAX_SAMPLE_RATE) return NULL;
    ChipMachine *m=calloc(1,sizeof *m);
    if (m) m->rate=(int)rate;
    return m;
}
static void destroy(ChipMachine *m) { free(m); }
static void copy_image(ChipMachine *m,const ChipImage *s) { memcpy(&m->image,s,offsetof(ChipImage,data)+s->bytes); }
static void reset_lane(ChipMachine *m,int ch) {
    Lane *v=&m->lanes[ch]; memset(v,0,sizeof *v); mono_synth_init(&v->synth,(float)m->rate);
    v->note=-1; v->expression=635; v->cutoff_cc=v->resonance_cc=-1; v->applied.waveform=-1;
}
static void set_patch(ChipMachine *m,Lane *v) {
    MonoSynthPatch p=m->image.patches[v->slot];
    if (v->cutoff_cc>=0) p.cutoff_hz=m->image.cutoff_cc[v->cutoff_cc];
    if (v->resonance_cc>=0) p.resonance=v->resonance_cc/127.f;
    p.level*=v->expression/635.f;
    if (memcmp(&v->applied,&p,sizeof p)!=0) { mono_synth_apply_patch(&v->synth,&p); v->applied=p; }
}
static float frequency(const ChipMachine *m,const Lane *v) {
    float hz=m->image.frequencies[v->note]*m->image.transpose[v->slot];
    return v->slide ? hz*exp2f(v->slide/6000.f) : hz;
}
static void off(ChipMachine *m,Lane *v) {
    if (v->gate) { mono_synth_note_off(&v->synth); v->tail=(m->rate*8+999)/1000+1; }
    v->gate=v->tied=false;
}
static void dispatch(ChipMachine *m,const uint8_t *e) {
    Lane *v=&m->lanes[e[2]];
    int n=(int16_t)u16(e+4);
    switch (e[3]) {
    case PATCH:
        v->slot=e[4]-1; v->cutoff_cc=v->resonance_cc=-1; set_patch(m,v);
        if (v->gate) mono_synth_set_frequency(&v->synth,frequency(m,v));
        break;
    case ATTACK:
        if (!v->muted) {
            v->note=e[4]; set_patch(m,v);
            mono_synth_note_on(&v->synth,frequency(m,v),e[5]/255.f,e[6]/127.f,v->tied);
            v->tied=e[7]!=0; v->gate=true; v->tail=0;
        }
        break;
    case RELEASE: if (v->note==e[4]) off(m,v); break;
    case CUT: off(m,v); break;
    case PITCH_RESET: v->slide=0; break; /* immediately precedes the row's attack */
    case SLIDE:
        v->slide+=n;
        if (v->slide < -24000) v->slide=-24000;
        if (v->slide > 24000) v->slide=24000;
        if (v->gate) mono_synth_set_frequency(&v->synth,frequency(m,v));
        break;
    case EXPRESSION:
        v->expression+=n;
        if (v->expression<0) v->expression=0;
        if (v->expression>635) v->expression=635;
        set_patch(m,v); break;
    case CUTOFF: v->cutoff_cc=e[4]; set_patch(m,v); break;
    case RESONANCE: v->resonance_cc=e[4]; set_patch(m,v); break;
    }
}
static void row_events(ChipMachine *m) {
    const uint8_t *p=m->image.data;
    int index=p[816+m->order]*ROWS+m->row;
    m->cursor=u16(p+INDEX+2*index); m->end=u16(p+INDEX+2*index+2);
}
static const uint8_t *event(const ChipMachine *m,int i) { return m->image.data+m->image.event_base+8*i; }
static int frame(const ChipMachine *m,const uint8_t *e) { return (int)(((uint64_t)u16(e)*m->row_frames)>>16); }
static void enter_row(ChipMachine *m) {
    m->speed=m->image.data[5]; m->row_frames=(m->fraction+m->rate*m->speed)/HZ; m->elapsed=0;
    row_events(m);
    unsigned ties=0;
    for (int i=0;i<m->image.data[4];i++) if (m->lanes[i].tied) ties|=1u<<i;
    for (int i=m->cursor;ties && i<m->end;i++) {
        const uint8_t *e=event(m,i); if (e[3]==ATTACK || e[3]==CUT) ties &= ~(1u<<e[2]);
    }
    for (int i=0;ties;i++,ties>>=1) if (ties&1) off(m,&m->lanes[i]);
}
static void next_row(ChipMachine *m) {
    const uint8_t *p=m->image.data;
    m->tick=0;
    if (++m->row>=p[1072+p[816+m->order]]) {
        m->row=0; if (++m->order>=(int)u16(p+8)) m->order=p[6];
    }
}
/* Render only audible lanes, once per uninterrupted span. Constant mix gain is independent of
 * track count/destination changes; final clamp is the same bounded bus as the host. */
static void render_span(ChipMachine *m,float *out,int frames) {
    memset(out,0,2*(size_t)frames*sizeof(float));
    for (int ch=0;ch<m->image.data[4];ch++) {
        Lane *v=&m->lanes[ch]; int n=v->gate ? frames : (v->tail<frames ? v->tail : frames);
        if (!v->gate) v->tail-=n;
        for (int j=0;j<n;j++) out[2*j]+=.35f*mono_synth_render(&v->synth);
    }
    for (int j=0;j<frames;j++) out[2*j+1]=out[2*j]=fmaxf(-1.f,fminf(1.f,out[2*j]));
}
static int tick(ChipMachine *m,float *out) {
    if (m->audition<0 && !m->tick) enter_row(m);
    int frames=(m->fraction+m->rate)/HZ;
    m->fraction=(m->fraction+m->rate)%HZ;
    if (m->audition>=0) {
        if (m->releasing && !m->lanes[m->audition].tail) return 0;
        render_span(m,out,frames); return frames;
    }
    int i=0;
    while (i<frames) {
        int n=frames-i;
        if (m->cursor<m->end) {
            const uint8_t *e=event(m,m->cursor); int distance=frame(m,e)-m->elapsed-i;
            if (distance<=0) { dispatch(m,e); m->cursor++; continue; }
            if (n>distance) n=distance;
        }
        render_span(m,out+2*i,n); i+=n;
    }
    m->elapsed+=frames;
    if (++m->tick>=m->speed) next_row(m);
    return frames;
}
static void reset(ChipMachine *m,const ChipImage *s) {
    copy_image(m,s); m->order=m->row=m->tick=m->fraction=m->elapsed=0; m->audition=-1; m->releasing=false;
    for (int i=0;i<TRACKS;i++) reset_lane(m,i);
}
static int start(ChipMachine *m,const ChipImage *s,int order,int row,int max_ticks,int *mode) {
    if (order<0 || order>=(int)u16(s->data+8) || row<0 || row>=s->data[1072+s->data[816+order]]) return CHIP_ERR_IMAGE;
    reset(m,s);
    int rows=row; for (int i=0;i<order;i++) rows+=s->data[1072+s->data[816+i]];
    /* Chase all historical musical settings even on long seeks. Only the bounded last rows
     * reconstruct DSP time; older oscillator, filter and envelope state is approximate. */
    int replay=max_ticks>0 ? max_ticks/s->data[5] : 0;
    if (mode) *mode=rows<=replay ? CHIP_SEEK_FROM_START : CHIP_SEEK_FROM_ORDER;
    float scratch[2*CHIP_MAX_TICK_FRAMES];
    while (rows>0) {
        if (rows>replay) {
            enter_row(m); while (m->cursor<m->end) dispatch(m,event(m,m->cursor++));
            m->fraction=(m->fraction+m->rate*m->speed)%HZ; next_row(m);
        } else { do { tick(m,scratch); } while (m->tick); }
        rows--;
    }
    return CHIP_OK;
}
static void apply(ChipMachine *m,const ChipImage *s) {
    for (int i=0;i<TRACKS;i++) {
        Lane *v=&m->lanes[i];
        if (i>=s->data[4] && i>=m->image.data[4]) continue;
        if (i>=s->data[4] || i>=m->image.data[4] || u32(s->data+16+6*i)!=u32(m->image.data+16+6*i) ||
            s->data[21+6*i]!=m->image.data[21+6*i]) { reset_lane(m,i); continue; }
        bool retune=v->gate && (s->frequencies[v->note]!=m->image.frequencies[v->note] ||
            s->transpose[v->slot]!=m->image.transpose[v->slot]);
        if (retune) mono_synth_set_frequency(&v->synth,s->frequencies[v->note]*s->transpose[v->slot]*exp2f(v->slide/6000.f));
    }
    copy_image(m,s);
    for (int i=0;i<s->data[4];i++) if (m->lanes[i].gate || m->lanes[i].tail) set_patch(m,&m->lanes[i]);
    if (m->audition>=0) {
        if (!m->lanes[m->audition].gate) m->releasing=true;
        return;
    }
    if (m->order>=(int)u16(s->data+8) || m->row>=s->data[1072+s->data[816+m->order]]) {
        m->order=m->order>=(int)u16(s->data+8) ? s->data[6] : m->order; m->row=m->tick=0;
    } else if (m->tick) {
        row_events(m); while (m->cursor<m->end && frame(m,event(m,m->cursor))<m->elapsed) m->cursor++;
    }
}
static int audition(ChipMachine *m,const ChipImage *s,int ch,int note,int slot,int fine) {
    if (ch<0 || ch>=s->data[4] || !s->data[21+6*ch] || note<0 || note>=NOTES || slot<0 || slot>SLOTS || fine) return CHIP_ERR_IMAGE;
    reset(m,s); m->audition=ch; Lane *v=&m->lanes[ch]; v->slot=slot ? slot-1 : 0; v->note=note; set_patch(m,v);
    mono_synth_note_on(&v->synth,frequency(m,v),s->data[20+6*ch]/127.f,0.f,false); v->gate=true;
    return CHIP_OK;
}
static int release(ChipMachine *m) {
    if (m->audition<0) return 0;
    off(m,&m->lanes[m->audition]); m->releasing=true; return 1;
}
static int mute(ChipMachine *m,int ch,int muted) {
    if (ch<0 || ch>=TRACKS) return CHIP_ERR_IMAGE;
    Lane *v=&m->lanes[ch]; v->muted=muted!=0;
    if (muted) { mono_synth_reset(&v->synth); v->gate=v->tied=false; v->tail=0; }
    return CHIP_OK;
}
static void position(const ChipMachine *m,int *o,int *r,int *t) { *o=m->order; *r=m->row; *t=m->tick; }
const ChipEngineOps studio_mono_ops={"studio-mono",IMAGE_SIZE,sizeof(ChipImage),create,destroy,load_image,start,apply,audition,release,mute,position,tick,NULL,apply};
