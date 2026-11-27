/* Score validation and control-thread preparation. SPDX-License-Identifier: GPL-3.0-or-later */
#include "studio_score.h"
#include <math.h>
#include <stdlib.h>

typedef struct { int lo,hi; } Range;
static const uint8_t counts[]={9,8,9,6,5,3,4,5,3};
static const Range limits[][9]={
    {{0,1},{0,100},{0,100},{0,100},{0,100},{0,100},{0,100},{-24,24},{0,100}},
    {{0,115},{-1200,1200},{0,1},{0,1},{0,2000},{0,4000},{30,20000},{0,100}},
    {{25,1600},{25,1600},{0,1200},{0,100},{0,2000},{10,8000},{0,100},{0,4000},{0,100}},
    {{0,2},{0,100},{0,100},{0,100},{10,4000},{0,100}},
    {{0,9},{1,2000},{0,95},{30,20000},{0,100}},{{0,36},{30,20000},{-24,6}},
    {{2,16},{100,48000},{0,100},{-24,6}},{{0,100},{200,8000},{30,20000},{0,120},{0,100}},
    {{5,800},{0,10000},{0,100}}
};
static int valid(int k,int id,int v){ return k>=0 && k<=CHORUS && id>=0 && id<counts[k] && v>=limits[k][id].lo && v<=limits[k][id].hi; }
static int values_ok(int k,const uint8_t *p){ for(int i=0;i<counts[k];i++) if(!valid(k,i,(int32_t)u32(p+4*i))) return 0; return 1; }
static float f32(const uint8_t *p){ uint32_t u=u32(p); float f; memcpy(&f,&u,4); return f; }
int studio_bank_decode(const uint8_t *p,size_t size,StudioBank **out){
    if(!p || !out || size<8 || u32(p)!=0x314d4350u || u32(p+4)>SLOTS) return CHIP_ERR_IMAGE;
    const unsigned count=u32(p+4); p+=8; size-=8;
    if(size<count*16u || size-count*16u>32u*1024*1024 || (!count && size)) return CHIP_ERR_IMAGE;
    if(!count){*out=NULL;return CHIP_OK;}
    StudioBank *b=calloc(1,sizeof *b + size-count*16u); if(!b)return CHIP_ERR_IMAGE;
    b->refs=1; float *dst=b->samples;
    for(unsigned i=0;i<count;i++){
        if(size<16)goto bad;
        unsigned id=u32(p),channels=u32(p+4),rate=u32(p+8),frames=u32(p+12); p+=16; size-=16;
        if(!id || id>SLOTS || b->pcm[id].samples || (channels!=1 && channels!=2) || rate<8000 || rate>192000 ||
           !frames || frames>(16u*1024*1024)/(4*channels))goto bad;
        size_t n=(size_t)frames*channels;
        if(n*4>size || size-n*4<(count-i-1)*16u)goto bad;
        b->pcm[id]=(StudioPcm){dst,frames,rate,channels};
        for(size_t j=0;j<n;j++,p+=4){float f=f32(p); if(!isfinite(f)||f < -1.f||f>1.f)goto bad; *dst++=f;}
        size-=n*4;
    }
    if(size)goto bad;
    *out=b;return CHIP_OK;
bad: free(b);return CHIP_ERR_IMAGE;
}
void studio_bank_drop(StudioBank *b){if(b && !--b->refs) free(b);}
int studio_bind_bank(ChipImage *s,StudioBank *b){
    for(int i=0;i<SLOTS;i++){
        const uint8_t *p=record(s,i); if(p[0]!=SAMPLER || !p[1])continue;
        if(!b || !b->pcm[p[1]].samples)return CHIP_ERR_IMAGE;
        unsigned frames=b->pcm[p[1]].frames,start=u32(p+40),end=u32(p+44); if(!end)end=frames;
        if(start>=end || end>frames || u32(p+48)>=end || u32(p+52)>end)return CHIP_ERR_IMAGE;
        for(int j=0;j<SLOTS;j++){unsigned a=u32(p+56+8*j),z=u32(p+60+8*j); if((a||z) && (a<start||z>end||a>=z))return CHIP_ERR_IMAGE;}
    }
    s->bank=b;return CHIP_OK;
}
int studio_score_load(ChipImage *s,const uint8_t *p,size_t size){
    if(!s||!p||size!=IMAGE_SIZE||u32(p)!=0x324d5453u||!p[4]||p[4]>TRACKS||!p[5]||p[7]>BUS)return CHIP_ERR_IMAGE;
    unsigned orders=u16(p+8),patterns=u16(p+10),events=u16(p+12);
    if(!orders||orders>PATTERNS||p[6]>=orders||p[14]>p[6]||!patterns||patterns>PATTERNS||events>EVENTS)return CHIP_ERR_IMAGE;
    for(unsigned i=0;i<p[4];i++){
        const uint8_t *t=p+16+6*i; if(!u32(t)||!t[4]||t[4]>127||t[5]>1)return CHIP_ERR_IMAGE;
        for(unsigned j=0;j<i;j++)if(u32(t)==u32(p+16+6*j))return CHIP_ERR_IMAGE;
    }
    for(int i=0;i<NOTES;i++){float f=f32(p+64+4*i);if(!isfinite(f)||f<=0.f)return CHIP_ERR_IMAGE;s->frequencies[i]=f;}
    for(int i=0;i<SLOTS;i++){
        const uint8_t *r=p+PATCH_BASE+PATCH_BYTES*i;
        if(r[0]>DRUM||!values_ok(r[0],r+4)||r[1]>SLOTS||r[2]>SLOTS)return CHIP_ERR_IMAGE;
        if(r[2] && !u32(r+60+8*(r[2]-1)))return CHIP_ERR_IMAGE;
    }
    uint32_t ids[STRIPS*INSERTS]; int count=0;
    for(int i=0;i<STRIPS;i++){
        const uint8_t *g=p+GRAPH_BASE+STRIP_BYTES*i;
        if(g[0]>200||g[1]>200||g[2]>100||g[3]>100||g[4]>INSERTS)return CHIP_ERR_IMAGE;
        if((i>=TRACKS+p[7] || (i<TRACKS && (i>=p[4] || !p[21+6*i]))) && g[4])return CHIP_ERR_IMAGE;
        for(int j=0;j<g[4];j++){
            const uint8_t *v=g+8+32*j; uint32_t id=u32(v);
            if(!id||id>INT32_MAX||v[4]<DELAY||v[4]>CHORUS||v[5]>1||!values_ok(v[4],v+8))return CHIP_ERR_IMAGE;
            for(int k=0;k<count;k++)if(ids[k]==id)return CHIP_ERR_IMAGE;
            ids[count++]=id;
        }
    }
    for(unsigned i=0;i<orders;i++)if(p[816+i]>=patterns)return CHIP_ERR_IMAGE;
    for(unsigned i=0;i<patterns;i++)if(!p[1072+i]||p[1072+i]>ROWS)return CHIP_ERR_IMAGE;
    unsigned rows=patterns*ROWS,base=INDEX+2*(rows+1);
    if(u16(p+INDEX)||u16(p+base-2)!=events)return CHIP_ERR_IMAGE;
    for(unsigned r=0;r<rows;r++){
        unsigned begin=u16(p+INDEX+2*r),end=u16(p+INDEX+2*r+2),prev=0;
        if(end<begin||end>events||end-begin>TRACKS*48)return CHIP_ERR_IMAGE;
        for(unsigned i=begin;i<end;i++){
            const uint8_t *e=p+base+8*i; unsigned op=e[3];
            if(u16(e)<prev || e[2]>=p[4] || !p[21+6*e[2]] || op>CONTROL ||
               (op==PATCH && (!e[4]||e[4]>SLOTS)) || ((op==ATTACK||op==RELEASE)&&e[4]>=NOTES) ||
               (op==ATTACK && (e[6]>127||e[7]>1)) || ((op==CUTOFF||op==RESONANCE)&&e[4]>127) || (op==SLICE&&e[4]>=SLOTS))return CHIP_ERR_IMAGE;
            if(op==CONTROL){
                const uint8_t *g=p+GRAPH_BASE+STRIP_BYTES*e[2];int k=e[5]>>4,id=e[5]&15;
                if(k>CHORUS||id>=counts[k]||e[4]>g[4]||(!e[4])!=(k<DELAY))return CHIP_ERR_IMAGE;
                if(e[4] && g[8+32*(e[4]-1)+4]!=k)return CHIP_ERR_IMAGE;
                int v=limits[k][id].lo<0?(int16_t)u16(e+6):(int)u16(e+6);
                if(!valid(k,id,v))return CHIP_ERR_IMAGE;
            }
            prev=u16(e);
        }
    }
    s->bytes=base+events*8;s->event_base=base;s->bank=NULL;s->live=NULL;memcpy(s->data,p,s->bytes);return CHIP_OK;
}
int studio_load_score(ChipImage *s,const uint8_t *p,size_t size,StudioBank **current){
    if(size<IMAGE_SIZE || studio_score_load(s,p,IMAGE_SIZE)!=CHIP_OK)return CHIP_ERR_IMAGE;
    if(size==IMAGE_SIZE)return studio_bind_bank(s,*current);
    StudioBank *next=NULL;
    if(studio_bank_decode(p+IMAGE_SIZE,size-IMAGE_SIZE,&next)!=CHIP_OK)return CHIP_ERR_IMAGE;
    if(studio_bind_bank(s,next)!=CHIP_OK){studio_bank_drop(next);return CHIP_ERR_IMAGE;}
    studio_bank_drop(*current);*current=next;return CHIP_OK;
}
void studio_pedal_set(StudioPedal *p,const uint8_t *w,int speed){
    int v[5]; for(int i=0;i<counts[p->kind];i++) v[i]=(p->mask & (1u<<i))?p->value[i]:(int32_t)u32(w+8+4*i);
    StudioFxParams c={0};
    switch(p->kind){
    case DELAY:{ /* Four tracker rows per beat. 1/8, dotted/triplet; 1/4; 1/16. */
        static const float rows[]={0,2,3,4.f/3,4,6,8.f/3,1,1.5f,2.f/3};
        c.delay.time_ms=v[0]?fminf(2000.f,rows[v[0]]*speed*(1000.f/HZ)):v[1];
        c.delay.feedback=v[2]*.01f;c.delay.tone_hz=v[3];c.delay.mix=v[4]*.01f;break;}
    case DRIVE:c.drive.drive_db=v[0];c.drive.tone_hz=v[1];c.drive.trim_db=v[2];break;
    case CRUSHER:c.crusher.bits=v[0];c.crusher.rate_hz=v[1];c.crusher.mix=v[2]*.01f;c.crusher.trim_db=v[3];break;
    case REVERB:c.reverb.size=v[0]*.01f;c.reverb.decay_seconds=v[1]*.001f;c.reverb.damping_hz=v[2];c.reverb.predelay_ms=v[3];c.reverb.mix=v[4]*.01f;break;
    case CHORUS:c.chorus.rate_hz=v[0]*.01f;c.chorus.depth_ms=v[1]*.001f;c.chorus.mix=v[2]*.01f;break;
    }
    studio_fx_set(p->fx,&c);studio_fx_set_bypass(p->fx,w[5]!=0);
}
void studio_rack_dispose(StudioRack *r){
    for(int i=0;i<STRIPS;i++)for(int j=0;j<INSERTS;j++)studio_fx_destroy(r->pedals[i][j].fx);
    studio_bank_drop(r->bank);memset(r,0,sizeof *r);
}
int studio_rack_prepare(StudioRack *r,const ChipImage *s,uint32_t rate){
    for(int i=0;i<STRIPS;i++){
        const uint8_t *g=strip(s,i);
        for(int j=0;j<INSERTS;j++){
            StudioPedal *p=&r->pedals[i][j]; const uint8_t *w=g+8+32*j;unsigned kind=j<g[4]?w[4]:0;
            if(p->kind!=kind){studio_fx_destroy(p->fx);memset(p,0,sizeof *p);
                if(kind){p->fx=studio_fx_create((StudioFxKind)(kind-DELAY+1),rate);if(!p->fx)return CHIP_ERR_IMAGE;p->kind=kind;}}
            if(kind){p->id=u32(w);p->mask=0;studio_fx_clear(p->fx);studio_pedal_set(p,w,s->data[5]);}
        }
    }
    if(r->bank!=s->bank){if(s->bank)++s->bank->refs;studio_bank_drop(r->bank);r->bank=s->bank;}
    return CHIP_OK;
}
/* Preserve the running effect and its overrides across moves. Then exchange whole racks:
 * addon storage moves into the machine, removed modules/old bank return to the producer. */
void studio_rack_swap(StudioRack *current,StudioRack *incoming){
    for(int i=0;i<STRIPS;i++){
        StudioPedal old[INSERTS];memcpy(old,current->pedals[i],sizeof old);
        bool used[INSERTS]={0};
        for(int j=0;j<INSERTS;j++){
            StudioPedal *p=&incoming->pedals[i][j];int match=-1;
            for(int k=0;p->id && k<INSERTS;k++)if(!used[k]&&p->id==old[k].id&&p->kind==old[k].kind){match=k;break;}
            if(match>=0){current->pedals[i][j]=old[match];used[match]=true;}else {current->pedals[i][j]=*p;memset(p,0,sizeof *p);}
        }
        int k=0;for(int j=0;j<INSERTS;j++)if(!used[j]){while(k<INSERTS && incoming->pedals[i][k].fx)k++;if(k<INSERTS)incoming->pedals[i][k++]=old[j];}
    }
    StudioBank *old=current->bank;current->bank=incoming->bank;incoming->bank=old;
}
int studio_live_prepare(StudioLive **owner,ChipImage *s,uint32_t rate){
    if(!*owner && !(*owner=calloc(1,sizeof **owner)))return CHIP_ERR_IMAGE;
    for(int i=0;i<2;i++)if(studio_rack_prepare(&(*owner)->rack[i],s,rate)!=CHIP_OK)return CHIP_ERR_IMAGE;
    s->live=*owner;return CHIP_OK;
}
void studio_live_destroy(StudioLive *s){if(s){studio_rack_dispose(&s->rack[0]);studio_rack_dispose(&s->rack[1]);free(s);}}
