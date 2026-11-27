/* STM2 per-track instruments, stereo inserts, post-fader sends. GPL-3.0-or-later.
 * Only machine creation, start/audition and producer-side rack preparation allocate. */
#include "studio_score.h"
#include "mono_synth.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

enum { BLOCK=128 };
typedef struct { float current[4],target[4],delta[4]; int remaining; } Fader;
typedef struct {
    MonoSynth synth;
    StudioVoice *voice;
    unsigned mask; int param[9],slice;
    bool borrowed_pcm, fresh_attack, explicit_slice;
    int slot, note, tail, expression, slide, cutoff_cc, resonance_cc;
    bool gate, tied, muted;
    MonoSynthPatch applied;
} Lane;
struct ChipMachine {
    ChipImage image;
    Lane lanes[TRACKS];
    StudioRack rack;
    StudioDspTables tables;
    Fader faders[STRIPS];
    float cutoff100[101],cutoff127[128],decay[101],transpose[49];
    int tail_frames;
    int rate, order, row, tick, fraction, elapsed, row_frames, speed, cursor, end, audition;
    bool releasing;
};

static void destroy(ChipMachine *m) {
    if(!m)return;
    for(int i=0;i<TRACKS;i++)studio_voice_destroy(m->lanes[i].voice);
    studio_rack_dispose(&m->rack);free(m);
}
static ChipMachine *create(uint32_t rate) {
    if (rate<8000 || rate>CHIP_MAX_SAMPLE_RATE) return NULL;
    ChipMachine *m=calloc(1,sizeof *m);
    if(!m)return NULL;
    m->rate=(int)rate;studio_dsp_prepare_tables(&m->tables);
    for(int i=0;i<TRACKS;i++)if(!(m->lanes[i].voice=studio_voice_create(rate,&m->tables))){destroy(m);return NULL;}
    for(int i=0;i<=100;i++){m->cutoff100[i]=30.f*powf(16000.f/30.f,i*.01f);m->decay[i]=.01f*powf(400.f,i*.01f);}
    for(int i=0;i<128;i++)m->cutoff127[i]=30.f*powf(16000.f/30.f,i/127.f);
    for(int i=0;i<49;i++)m->transpose[i]=exp2f((i-24)/12.f);
    return m;
}
static void copy_image(ChipMachine *m,const ChipImage *s) { memcpy(&m->image,s,offsetof(ChipImage,data)+s->bytes); }
static void reset_lane(ChipMachine *m,int ch) {
    Lane *v=&m->lanes[ch]; StudioVoice *voice=v->voice; studio_voice_clear(voice);
    memset(v,0,sizeof *v);v->voice=voice; mono_synth_init(&v->synth,(float)m->rate);
    v->slice=record(&m->image,0)[2]-1;
    v->note=-1; v->expression=635; v->cutoff_cc=v->resonance_cc=-1; v->applied.waveform=-1;
}
static int value(const uint8_t *r,const Lane *v,int id){return v->mask&(1u<<id)?v->param[id]:(int32_t)u32(r+4+4*id);}
static float frequency(const ChipMachine *m,const Lane *v);
static void sound(ChipMachine *m,Lane *v,bool attack,float velocity,float accent) {
    const uint8_t *r=record(&m->image,v->slot);float expression=v->expression/635.f;
    int x[9]={0};static const int lengths[]={9,8,9,6};
    for(int i=0;i<lengths[r[0]];i++)x[i]=value(r,v,i);
    float hz=attack?frequency(m,v):0.f;
    if(r[0]==MONO){
        MonoSynthPatch p={x[0],v->cutoff_cc>=0?m->cutoff127[v->cutoff_cc]:m->cutoff100[x[1]],
            v->resonance_cc>=0?v->resonance_cc/127.f:x[2]*.01f,x[3]*.06f,m->decay[x[4]],x[5]*.01f,2.f*x[6]*x[6]*.0001f,x[8]*.01f*expression};
        if(memcmp(&v->applied,&p,sizeof p)){mono_synth_apply_patch(&v->synth,&p);v->applied=p;}
        if(attack){mono_synth_note_on(&v->synth,hz,velocity,accent,v->tied);v->tail=0;}
        return;
    }
    if(attack && v->tied && !v->fresh_attack && (r[0]==SAMPLER || r[0]==DRUM) && studio_voice_active(v->voice)){
        studio_voice_pitch(v->voice,hz);return;
    }
    velocity=fminf(1.f,velocity*(1.f+.5f*accent));
    if(r[0]==FM){
        StudioFmPatch p={x[0]*.01f,x[1]*.01f,x[2]*.01f,x[3]*.01f,x[4]*.001f,x[5]*.001f,x[6]*.01f,x[7]*.001f,x[8]*.01f*expression};
        if(attack)studio_voice_start_fm(v->voice,&p,hz,velocity,v->tied);else studio_voice_update_fm(v->voice,&p);
    }else if(r[0]==DRUM){
        StudioDrumPatch p={(StudioDrumKind)x[0],x[1]*.01f,x[2]*.01f,x[3]*.01f,x[4]*.001f,x[5]*.01f*expression};
        if(attack)studio_voice_start_drum(v->voice,&p,hz,velocity);else studio_voice_update_drum(v->voice,&p);
    }else{
        StudioSamplerPatch p={0};
        p.attack_seconds=x[4]*.001f;p.release_seconds=x[5]*.001f;p.cutoff_hz=x[6];p.level=x[7]*.01f*expression;
        if(!attack){studio_voice_update_sampler(v->voice,&p);return;}
        if(!m->rack.bank || !r[1])return;
        p.pcm=m->rack.bank->pcm[r[1]];p.start=u32(r+40);p.end=u32(r+44);if(!p.end)p.end=p.pcm.frames;
        if(v->slice>=0){p.start=u32(r+56+8*v->slice);p.end=u32(r+60+8*v->slice);}
        if(x[3]){
            p.loop_start=u32(r+48);if(p.loop_start<p.start)p.loop_start=p.start;
            p.loop_end=u32(r+52);if(!p.loop_end || p.loop_end>p.end)p.loop_end=p.end;
            if(p.loop_start>=p.loop_end)p.loop_start=p.loop_end=0;
        }
        p.reverse=x[2]!=0;p.root_hz=m->image.frequencies[x[0]];p.fine_cents=x[1];
        v->borrowed_pcm|=studio_voice_start_sampler(v->voice,&p,hz,velocity);
    }
}
static float frequency(const ChipMachine *m,const Lane *v) {
    float hz=m->image.frequencies[v->note];const uint8_t *r=record(&m->image,v->slot);
    if(r[0]==MONO) hz*=m->transpose[value(r,v,7)+24];
    return v->slide ? hz*exp2f(v->slide/6000.f) : hz;
}
static void retune(ChipMachine *m,Lane *v) {
    if(!v->gate)return;
    if(record(&m->image,v->slot)[0]==MONO)mono_synth_set_frequency(&v->synth,frequency(m,v));
    else studio_voice_pitch(v->voice,frequency(m,v));
}
static void off(ChipMachine *m,Lane *v,bool cut) {
    if(v->synth.gate){mono_synth_note_off(&v->synth);v->tail=(m->rate*8+999)/1000+1;}
    if(cut)studio_voice_cut(v->voice);else studio_voice_release(v->voice);
    v->gate=v->tied=false;
}
static void dispatch(ChipMachine *m,const uint8_t *e) {
    Lane *v=&m->lanes[e[2]];
    int n=(int16_t)u16(e+4);
    switch (e[3]) {
    case PATCH:{
        int slot=e[4]-1;const uint8_t *r=record(&m->image,slot);
        if(slot!=v->slot){
            if(record(&m->image,v->slot)[0]!=r[0])off(m,v,true);
            v->slice=r[2]-1;v->mask=0;v->fresh_attack=true;v->explicit_slice=false;
        }
        v->slot=slot;v->cutoff_cc=v->resonance_cc=-1;sound(m,v,false,0,0);retune(m,v);
        break;}
    case ATTACK:
        if (!v->muted) {
            v->note=e[4];sound(m,v,true,e[5]/255.f,e[6]/127.f);
            v->tied=e[7]!=0; v->gate=true;v->fresh_attack=false;
        }
        break;
    case RELEASE: if (v->note==e[4]) off(m,v,false); break;
    case CUT: off(m,v,true); break;
    case PITCH_RESET: v->slide=0; break; /* immediately precedes the row's attack */
    case SLIDE:
        v->slide+=n;
        if (v->slide < -24000) v->slide=-24000;
        if (v->slide > 24000) v->slide=24000;
        retune(m,v);
        break;
    case EXPRESSION:
        v->expression+=n;
        if (v->expression<0) v->expression=0;
        if (v->expression>635) v->expression=635;
        sound(m,v,false,0,0); break;
    case CUTOFF: v->cutoff_cc=e[4];sound(m,v,false,0,0);break;
    case RESONANCE: v->resonance_cc=e[4];sound(m,v,false,0,0);break;
    case SLICE:v->fresh_attack|=v->slice!=e[4];v->slice=e[4];v->explicit_slice=true;break;
    case CONTROL:{
        unsigned k=e[5]>>4,id=e[5]&15;
        int val=k==CRUSHER && id==1?(int)u16(e+6):(int16_t)u16(e+6);
        if(e[4]){
            int j=e[4]-1;StudioPedal *p=&m->rack.pedals[e[2]][j];
            p->mask|=1u<<id;p->value[id]=val;studio_pedal_set(p,strip(&m->image,e[2])+8+32*j,m->image.data[5]);
        }else if(record(&m->image,v->slot)[0]==k){
            if((k==SAMPLER && id<4)||(k==DRUM && !id))v->fresh_attack|=value(record(&m->image,v->slot),v,id)!=val;
            v->mask|=1u<<id;v->param[id]=val;if(k==MONO && id==1)v->cutoff_cc=-1;if(k==MONO && id==2)v->resonance_cc=-1;
            sound(m,v,false,0,0);if(k==MONO && id==7)retune(m,v);
        }
        break;}
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
    for (int i=0;ties;i++,ties>>=1) if (ties&1) off(m,&m->lanes[i],false);
}
static void next_row(ChipMachine *m) {
    const uint8_t *p=m->image.data;
    m->tick=0;
    if (++m->row>=p[1072+p[816+m->order]]) {
        m->row=0; if (++m->order>=(int)u16(p+8)) m->order=p[6];
    }
}
static void faders(ChipMachine *m,bool snap) {
    for(int i=0;i<STRIPS;i++){
        const uint8_t *g=strip(&m->image,i);Fader *f=&m->faders[i];
        float gain=g[0]*.01f,pan=(g[1]-100)*.01f;
        float to[]={gain*(pan>0?1.f-pan:1.f),gain*(pan<0?1.f+pan:1.f),g[2]*.01f,g[3]*.01f};
        if(!snap && !memcmp(f->target,to,sizeof to))continue;
        memcpy(f->target,to,sizeof to);f->remaining=snap?0:m->rate/200;
        if(snap)memcpy(f->current,to,sizeof to);
        else for(int j=0;j<4;j++)f->delta[j]=(to[j]-f->current[j])/f->remaining;
    }
}
static void move_fader(Fader *f){
    if(f->remaining && --f->remaining){for(int j=0;j<4;j++)f->current[j]+=f->delta[j];}
    else if(!f->remaining)memcpy(f->current,f->target,sizeof f->current);
}
static void chain(ChipMachine *m,int i,float *audio,int n){
    const int count=strip(&m->image,i)[4];
    for(int j=0;j<count;j++)studio_fx_process(m->rack.pedals[i][j].fx,audio,(unsigned)n);
}
static void render_span(ChipMachine *m,float *out,int frames) {
    while(frames){
        int n=frames>BLOCK?BLOCK:frames; float bus[BUS][2*BLOCK],lane[2*BLOCK];
        int buses=m->image.data[7];
        for(int b=0;b<buses;b++)memset(bus[b],0,2*n*sizeof(float));
        memset(out,0,2*n*sizeof(float));
        for(int i=0;i<m->image.data[4];i++){
            if(!m->image.data[21+6*i])continue;
            Lane *v=&m->lanes[i];int mono=v->synth.gate?n:(v->tail<n?v->tail:n);
            if(!v->synth.gate)v->tail-=mono;
            bool active=studio_voice_active(v->voice);
            if(!mono && !active && !strip(&m->image,i)[4] && !m->faders[i].remaining)continue;
            for(int j=0;j<n;j++){
                float l=0.f,r=0.f;if(active)studio_voice_frame(v->voice,&l,&r);
                float a=j<mono?mono_synth_render(&v->synth):0.f;lane[2*j]=.35f*(l+a);lane[2*j+1]=.35f*(r+a);
            }
            chain(m,i,lane,n);
            Fader *f=&m->faders[i];
            for(int j=0;j<n;j++){
                if(f->remaining)move_fader(f);
                float l=lane[2*j]*f->current[0],r=lane[2*j+1]*f->current[1];
                out[2*j]+=l;out[2*j+1]+=r;
                for(int b=0;b<buses;b++){bus[b][2*j]+=l*f->current[2+b];bus[b][2*j+1]+=r*f->current[2+b];}
            }
        }
        for(int b=0;b<buses;b++){
            chain(m,TRACKS+b,bus[b],n);Fader *f=&m->faders[TRACKS+b];
            for(int j=0;j<n;j++){if(f->remaining)move_fader(f);out[2*j]+=bus[b][2*j]*f->current[0];out[2*j+1]+=bus[b][2*j+1]*f->current[1];}
        }
        for(int j=0;j<2*n;j++)out[j]=fmaxf(-1.f,fminf(1.f,out[j]));
        frames-=n;out+=2*n;
    }
}
static int tick(ChipMachine *m,float *out) {
    if (!m->releasing && m->audition<0 && !m->tick) enter_row(m);
    int frames=(m->fraction+m->rate)/HZ;
    m->fraction=(m->fraction+m->rate)%HZ;
    if (m->releasing){
        if(!m->tail_frames)return 0;
        int n=frames<m->tail_frames?frames:m->tail_frames;
        render_span(m,out,n);
        const int fade=m->rate/50;
        for(int i=0;i<n;i++)if(m->tail_frames-i<=fade){
            float g=(m->tail_frames-i-1)/(float)fade;out[2*i]*=g;out[2*i+1]*=g;
        }
        m->tail_frames-=n;return n;
    }
    if (m->audition>=0) {
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
static int reset(ChipMachine *m,const ChipImage *s) {
    // A spare machine can retain a borrowed sample from an older bank. Drop those voices before releasing it.
    for(int i=0;i<TRACKS;i++)studio_voice_clear(m->lanes[i].voice);
    if(studio_rack_prepare(&m->rack,s,m->rate)!=CHIP_OK)return CHIP_ERR_IMAGE;
    copy_image(m,s); m->order=m->row=m->tick=m->fraction=m->elapsed=0; m->audition=-1; m->releasing=false;
    for (int i=0;i<TRACKS;i++) reset_lane(m,i);
    faders(m,true);m->tail_frames=0;return CHIP_OK;
}
static int start(ChipMachine *m,const ChipImage *s,int order,int row,int max_ticks,int *mode) {
    if(order<0 || order>=(int)u16(s->data+8)-s->data[14])return CHIP_ERR_IMAGE;
    order+=s->data[14];
    if(row<0 || row>=s->data[1072+s->data[816+order]])return CHIP_ERR_IMAGE;
    if(reset(m,s)!=CHIP_OK)return CHIP_ERR_IMAGE;
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
    // Cursor starts chase source/controller state but never claim to reconstruct prior echoes.
    for(int ch=0;ch<STRIPS;ch++)for(int j=0;j<strip(s,ch)[4];j++)studio_fx_clear(m->rack.pedals[ch][j].fx);
    return CHIP_OK;
}
static void apply(ChipMachine *m,const ChipImage *s) {
    // A direct (non-host) caller may update values without a kit, never topology or bank.
    if(!s->live){
        if(s->bank!=m->rack.bank)return;
        for(int ch=0;ch<STRIPS;ch++){
            const uint8_t *g=strip(s,ch);
            for(int j=0;j<INSERTS;j++){const StudioPedal *p=&m->rack.pedals[ch][j];
                if(j>=g[4]?p->fx!=NULL:(!p->fx || p->kind!=g[8+32*j+4] || p->id!=u32(g+8+32*j)))return;
            }
        }
    }
    unsigned pitch_changes=0;
    for (int i=0;i<TRACKS;i++) {
        Lane *v=&m->lanes[i];
        if (i>=s->data[4] && i>=m->image.data[4]) continue;
        if (i>=s->data[4] || i>=m->image.data[4] || u32(s->data+16+6*i)!=u32(m->image.data+16+6*i) ||
            s->data[21+6*i]!=m->image.data[21+6*i]) { reset_lane(m,i);m->lanes[i].slice=record(s,0)[2]-1;continue; }
        const uint8_t *before=record(&m->image,v->slot),*after=record(s,v->slot);
        if(before[0]!=after[0] || (s->bank!=m->rack.bank && v->borrowed_pcm)){
            int slot=v->slot; reset_lane(m,i);v->slot=slot;v->slice=after[2]-1;
        }else{
            if(!v->explicit_slice || (v->slice>=0 && !u32(after+60+8*v->slice)))v->slice=after[2]-1;
            if(v->gate && (s->frequencies[v->note]!=m->image.frequencies[v->note] ||
               (after[0]==MONO && value(before,v,7)!=value(after,v,7))))pitch_changes|=1u<<i;
            if(after[0]==SAMPLER && (memcmp(before+1,after+1,19) || memcmp(before+40,after+40,PATCH_BYTES-40)))v->fresh_attack=true;
            if(after[0]==DRUM && value(before,v,0)!=value(after,v,0))v->fresh_attack=true;
        }
    }
    if(s->live)studio_rack_swap(&m->rack,&s->live->rack[m->audition>=0?1:0]);
    m->order+=s->data[14]-m->image.data[14];
    copy_image(m,s);
    faders(m,false);
    for(int ch=0;ch<STRIPS;ch++)for(int j=0;j<strip(s,ch)[4];j++)
        studio_pedal_set(&m->rack.pedals[ch][j],strip(s,ch)+8+32*j,s->data[5]);
    for (int i=0;i<s->data[4];i++) if (m->lanes[i].gate || m->lanes[i].tail || studio_voice_active(m->lanes[i].voice)){
        sound(m,&m->lanes[i],false,0,0);if(pitch_changes&(1u<<i))retune(m,&m->lanes[i]);
    }
    if (m->audition>=0) {
        if (!m->lanes[m->audition].gate) m->releasing=true;
        return;
    }
    if (m->order<s->data[14] || m->order>=(int)u16(s->data+8) || m->row>=s->data[1072+s->data[816+m->order]]) {
        m->order=(m->order<s->data[14] || m->order>=(int)u16(s->data+8)) ? s->data[6] : m->order; m->row=m->tick=0;
    } else if (m->tick) {
        row_events(m); while (m->cursor<m->end && frame(m,event(m,m->cursor))<m->elapsed) m->cursor++;
    }
}
static int audition(ChipMachine *m,const ChipImage *s,int ch,int note,int slot,int fine) {
    if (ch<0 || ch>=s->data[4] || !s->data[21+6*ch] || note<0 || note>=NOTES || slot<0 || slot>SLOTS || fine<0 || fine>SLOTS) return CHIP_ERR_IMAGE;
    slot=slot?slot-1:0;const uint8_t *r=record(s,slot);
    if((fine && (r[0]!=SAMPLER || !u32(r+60+8*(fine-1)))) || (r[0]==SAMPLER && (!s->bank||!r[1])))return CHIP_ERR_IMAGE;
    if(reset(m,s)!=CHIP_OK)return CHIP_ERR_IMAGE;
    m->audition=ch;Lane *v=&m->lanes[ch];v->slot=slot;v->slice=(fine?fine:r[2])-1;
    v->note=note;sound(m,v,true,s->data[20+6*ch]/127.f,0.f);v->gate=true;
    return CHIP_OK;
}
static int tail_budget(const ChipMachine *m){
    for(int i=0;i<STRIPS;i++)for(int j=0;j<strip(&m->image,i)[4];j++){
        unsigned k=m->rack.pedals[i][j].kind;if(k==DELAY||k==REVERB||k==CHORUS)return m->rate*8;
    }
    return 0;
}
static int stop_tail(ChipMachine *m){
    if(m->releasing)return m->tail_frames>0;
    int n=tail_budget(m);
    for(int i=0;i<m->image.data[4];i++){
        Lane *v=&m->lanes[i];off(m,v,true);
        if(v->tail>n)n=v->tail;
        if(studio_voice_active(v->voice) && n<m->rate/200)n=m->rate/200;
    }
    m->releasing=true;m->tail_frames=n;return n>0;
}
static int release(ChipMachine *m) {
    if (m->audition<0) return 0;
    Lane *v=&m->lanes[m->audition];off(m,v,false);
    int n=tail_budget(m);if(!n)n=record(&m->image,v->slot)[0]==MONO?v->tail:m->rate*4;
    m->tail_frames=n;m->releasing=true;return n>0;
}
static int mute(ChipMachine *m,int ch,int muted) {
    if (ch<0 || ch>=TRACKS) return CHIP_ERR_IMAGE;
    Lane *v=&m->lanes[ch]; v->muted=muted!=0;
    if (muted) { mono_synth_reset(&v->synth);studio_voice_clear(v->voice); v->gate=v->tied=false; v->tail=0; }
    return CHIP_OK;
}
static void position(const ChipMachine *m,int *o,int *r,int *t) { *o=m->order-m->image.data[14]; *r=m->row; *t=m->tick; }
const ChipEngineOps studio_mono_ops={"studio-mono",IMAGE_SIZE,sizeof(ChipImage),create,destroy,studio_score_load,start,apply,audition,release,mute,position,tick,NULL,apply,stop_tail};
