/* STM2 -> realtime engine, checked against direct DSP note/legato calls at the expected frames. */
#include "chip_engine.h"
#include "studio/mono_synth.h"
#include "studio/studio_runtime.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

enum { PATCHES=1328, SLOT_BYTES=312, STRIPS=PATCHES+32*SLOT_BYTES, STRIP_BYTES=136, ROWS_AT=STRIPS+10*STRIP_BYTES };
static void word(uint8_t *p, unsigned v) { p[0]=v; p[1]=v>>8; }
static void dword(uint8_t *p,uint32_t v){word(p,v);word(p+2,v>>16);}
static void sample(uint8_t *p,float v){uint32_t bits;memcpy(&bits,&v,4);dword(p,bits);}
static void put_event(uint8_t *e, unsigned at, unsigned lane, unsigned op, unsigned a, unsigned b, unsigned c, unsigned d) {
    word(e,at); e[2]=lane; e[3]=op; e[4]=a; e[5]=b; e[6]=c; e[7]=d;
}
static uint8_t *fixture(int tracks) {
    uint8_t *p=calloc(1,studio_mono_ops.image_size); assert(p);
    memcpy(p,"STM2",4); p[4]=tracks; p[5]=1; p[8]=1; p[10]=1; p[1072]=3;
    for (int i=0;i<tracks;i++) { p[16+6*i]=i+1; p[20+6*i]=127; p[21+6*i]=1; p[STRIPS+i*STRIP_BYTES]=p[STRIPS+1+i*STRIP_BYTES]=100; }
    for (int i=0;i<116;i++) {
        float hz=i==45 ? 220.f : 440.f; uint32_t bits; memcpy(&bits,&hz,4);
        word(p+64+4*i,bits); word(p+66+4*i,bits>>16);
    }
    uint8_t *patch=p+PATCHES+SLOT_BYTES+4; /* Mono controls in slot 2; other slots silent */
    patch[4]=100; patch[4*5]=100; patch[4*8]=100;
    int n=0; uint8_t *events=p+ROWS_AT+2*65;
    for (int i=0;i<tracks;i++) put_event(events+8*n++,0,i,0,2,0,0,0);
    for (int i=0;i<tracks;i++) put_event(events+8*n++,32768,i,1,45,200,127,1);
    word(p+ROWS_AT+2,n);
    for (int i=0;i<tracks;i++) put_event(events+8*n++,0,i,1,57,175,0,1);
    for (int i=2;i<=64;i++) word(p+ROWS_AT+2*i,n);
    word(p+12,n); return p;
}

static void sampler_seek_and_bank_lifetime(void) {
    const ChipEngineOps *ops=&studio_mono_ops; enum {FRAMES=4096};
    size_t size=ops->image_size+24+FRAMES*8;
    uint8_t *base=fixture(1),*wire=calloc(1,size);assert(wire);
    memcpy(wire,base,ops->image_size);free(base);
    uint8_t *pcm=wire+ops->image_size;memcpy(pcm,"PCM1",4);
    dword(pcm+4,1);dword(pcm+8,1);dword(pcm+12,2);dword(pcm+16,48000);dword(pcm+20,FRAMES);
    for(int i=0;i<FRAMES;i++){float sign=i<2048?1.f:-1.f;sample(pcm+24+i*8,.8f*sign);sample(pcm+28+i*8,-.3f*sign);}
    for(int slot=1;slot<=2;slot++){
        uint8_t *patch=wire+PATCHES+slot*SLOT_BYTES;memset(patch,0,SLOT_BYTES);
        patch[0]=1;patch[1]=1;patch[2]=4; /* default ID 03; sparse IDs 03 and 05 */
        dword(patch+4,57);dword(patch+4+6*4,20000);dword(patch+4+7*4,100);
        dword(patch+60+8*3,2048);dword(patch+56+8*5,2048);dword(patch+60+8*5,FRAMES);
    }
    uint8_t *ev=wire+ROWS_AT+130;int n=0;
    for(int row=0;row<3;row++){
        put_event(ev+8*n++,0,0,0,row==2?3:2,0,0,0);
        if(!row)put_event(ev+8*n++,0,0,9,5,0,0,0);
        put_event(ev+8*n++,8192,0,1,57,255,0,0);word(wire+ROWS_AT+2*(row+1),n);
    }
    for(int i=4;i<=64;i++)word(wire+ROWS_AT+2*i,n);
    word(wire+12,n);
    ChipImage *image=calloc(1,ops->image_storage);ChipMachine *m=ops->create(48000);StudioBank *bank=NULL;int mode;
    assert(image&&m);assert(studio_load_score(image,wire,size-1,&bank)==CHIP_ERR_IMAGE && !bank);
    assert(studio_load_score(image,wire,size,&bank)==CHIP_OK && bank);
    assert(ops->start(m,image,0,0,96,&mode)==CHIP_OK);
    float buf[2*CHIP_MAX_TICK_FRAMES];
    for(int i=0;i<3;i++){
        assert(ops->tick(m,buf)==1000);float sign=i==2?1.f:-1.f;
        assert(sign*buf[1800]>.2f && sign*buf[1801]<-.08f);
    }
    assert(ops->start(m,image,0,1,0,&mode)==CHIP_OK);
    studio_bank_drop(bank);bank=NULL; /* machine is now the only PCM owner */
    assert(ops->tick(m,buf)==1000 && buf[1800]<-.2f && buf[1801]>.08f);
    // Replace a playing sample bank with a score that has none. Retired storage is
    // reclaimed on the producer, not from the process callback.
    base=fixture(1);assert(studio_load_score(image,base,ops->image_size,&bank)==CHIP_OK);
    StudioLive *live=NULL;assert(studio_live_prepare(&live,image,48000)==CHIP_OK);
    ops->apply_image(m,image); studio_live_destroy(live);
    assert(ops->tick(m,buf)>0);ops->destroy(m);free(image);free(wire);free(base);
}

static void pedal(uint8_t *w,unsigned id,unsigned kind,int a,int b,int c,int d){
    memset(w,0,32);dword(w,id);w[4]=kind;
    dword(w+8,a);dword(w+12,b);dword(w+16,c);dword(w+20,d);
}
static void reorder_and_post_fader_send(void){
    const ChipEngineOps *ops=&studio_mono_ops;int mode;
    ChipMachine *machines[2]; ChipImage *images[2];StudioLive *live[2]={0};
    uint8_t *w[2];float samples[2][CHIP_MAX_TICK_FRAMES*2];
    // Both play a clipped Mono through drive and crusher. The first sets trim by a
    // programmed controller, the second bakes the identical trim into the patch.
    for(int i=0;i<2;i++){
        w[i]=fixture(1);uint8_t *p=w[i],*ev=p+ROWS_AT+130;
        p[1072]=64;p[STRIPS+4]=2;
        pedal(p+STRIPS+8,101,5,30,5000,i?-24:6,0);pedal(p+STRIPS+40,102,6,8,8000,100,0);
        int n=0;put_event(ev+8*n++,0,0,0,2,0,0,0);
        if(!i)put_event(ev+8*n++,0,0,10,1,0x52,232,255); /* drive trim -> -24 dB */
        put_event(ev+8*n++,0,0,1,45,255,0,1);word(p+ROWS_AT+2,n);
        for(int row=1;row<12;row++){put_event(ev+8*n++,0,0,1,45,255,0,1);word(p+ROWS_AT+2*(row+1),n);}
        for(int row=13;row<=64;row++)word(p+ROWS_AT+2*row,n);
        word(p+12,n);
        images[i]=calloc(1,ops->image_storage);machines[i]=ops->create(48000);
        assert(images[i]&&machines[i]&&ops->load_image(images[i],p,ops->image_size)==CHIP_OK);
        assert(ops->start(machines[i],images[i],0,0,96,&mode)==CHIP_OK);
        assert(ops->tick(machines[i],samples[i])==1000);
        uint8_t tmp[32];memcpy(tmp,p+STRIPS+8,32);memcpy(p+STRIPS+8,p+STRIPS+40,32);memcpy(p+STRIPS+40,tmp,32);
        if(!i)ev[8+4]=2; /* republished command now resolves to the moved drive */
        assert(ops->load_image(images[i],p,ops->image_size)==CHIP_OK);
        assert(studio_live_prepare(&live[i],images[i],48000)==CHIP_OK);
        ops->apply_image(machines[i],images[i]);
        studio_live_destroy(live[i]);live[i]=NULL;
    }
    for(int k=0;k<3;k++){
        for(int i=0;i<2;i++)assert(ops->tick(machines[i],samples[i])==1000);
        double energy=0;
        for(int j=0;j<2000;j++){energy+=fabs(samples[0][j]);assert(fabsf(samples[0][j]-samples[1][j])<.00001f);}
        assert(energy>1.);
    }
    for(int i=0;i<2;i++){
        free(w[i]);w[i]=fixture(1);w[i][STRIPS]=50;w[i][STRIPS+1]=200;
        if(i){w[i][7]=1;w[i][STRIPS+2]=50;w[i][STRIPS+8*STRIP_BYTES]=100;w[i][STRIPS+8*STRIP_BYTES+1]=100;}
        assert(ops->load_image(images[i],w[i],ops->image_size)==CHIP_OK);
        assert(ops->start(machines[i],images[i],0,0,96,&mode)==CHIP_OK && ops->tick(machines[i],samples[i])==1000);
        ops->destroy(machines[i]);free(images[i]);free(w[i]);
    }
    double energy=0;for(int j=0;j<1000;j++){
        assert(samples[1][2*j]==0.f);energy+=fabs(samples[1][2*j+1]);
        assert(fabsf(samples[1][2*j+1]-1.5f*samples[0][2*j+1])<.00001f);
    }
    assert(energy>1.);
}
int main(void) {
    sampler_seek_and_bank_lifetime();
    reorder_and_post_fader_send();
    const ChipEngineOps *ops=&studio_mono_ops;
    uint8_t *wire=fixture(1); ChipImage *image=calloc(1,ops->image_storage);
    ChipMachine *m=ops->create(48000); int mode;
    assert(m && image && ops->load_image(image,wire,ops->image_size)==CHIP_OK);
    assert(ops->start(m,image,0,0,96,&mode)==CHIP_OK && mode==CHIP_SEEK_FROM_START);
    MonoSynth ref; mono_synth_init(&ref,48000);
    MonoSynthPatch patch={0,16000,0,0,.01f,1,0,1}; mono_synth_apply_patch(&ref,&patch);
    float buf[2*CHIP_MAX_TICK_FRAMES], second[2000];
    for (int row=0;row<3;row++) {
        int o,r,t; ops->position(m,&o,&r,&t); assert(o==0 && r==row && t==0);
        assert(ops->tick(m,buf)==1000);
        for (int i=0;i<1000;i++) {
            if (row==0 && i==500) mono_synth_note_on(&ref,220,200/255.f,1,false);
            if (row==1 && !i) mono_synth_note_on(&ref,440,175/255.f,0,true);
            if (row==2 && !i) mono_synth_note_off(&ref);
            float expected=.35f*mono_synth_render(&ref);
            assert(fabsf(expected-buf[2*i])<.0001f && buf[2*i]==buf[2*i+1]);
        }
        if (row==1) memcpy(second,buf,sizeof second);
    }
    assert(ops->start(m,image,0,1,96,&mode)==CHIP_OK);
    assert(ops->tick(m,buf)==1000 && memcmp(second,buf,sizeof second)==0);
    assert(ops->audition(m,image,0,57,2,0)==CHIP_OK && ops->tick(m,buf)==1000);
    wire[PATCHES+SLOT_BYTES+4+4*8]=0; assert(ops->load_image(image,wire,ops->image_size)==CHIP_OK);
    ops->apply_audition_image(m,image);
    for (int k=0;k<8;k++) assert(ops->tick(m,buf)==1000);
    float peak=0; for (int i=0;i<2000;i++) peak=fmaxf(peak,fabsf(buf[i])); assert(peak<.001f);
    assert(ops->release(m)==1 && ops->tick(m,buf)>0 && ops->tick(m,buf)==0);
    wire[ROWS_AT+130+2]=8; assert(ops->load_image(image,wire,ops->image_size)==CHIP_ERR_IMAGE);
    free(wire); wire=fixture(8); assert(ops->load_image(image,wire,ops->image_size)==CHIP_OK);
    assert(ops->start(m,image,0,0,96,&mode)==CHIP_OK);
    for (int i=0;i<8;i++) ops->mute(m,i,1);
    assert(ops->tick(m,buf)==1000); for (int i=0;i<2000;i++) assert(buf[i]==0);
    ops->mute(m,0,0); assert(ops->tick(m,buf)==1000);
    peak=0; for (int i=0;i<2000;i++) peak=fmaxf(peak,fabsf(buf[i])); assert(peak>.01f);
    assert(ops->start(m,image,0,0,96,&mode)==CHIP_OK);
    const clock_t before=clock(); double checksum=0.;
    for (int k=0;k<480;k++) {
        assert(ops->tick(m,buf)==1000);
        for (int i=0;i<2000;i++) { assert(isfinite(buf[i]) && fabsf(buf[i])<=1.f); checksum+=fabsf(buf[i]); }
    }
    assert(checksum>100.); printf("Studio: seek/position, tie, gate, patch, mute and validation OK; 8-track loop, 10 s audio: %.3f s host CPU\n",(double)(clock()-before)/CLOCKS_PER_SEC);
    free(wire); free(image); ops->destroy(m); return 0;
}
