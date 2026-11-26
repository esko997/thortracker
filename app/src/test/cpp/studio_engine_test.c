/* STM1 -> realtime engine, checked against direct DSP note/legato calls at the expected frames. */
#include "chip_engine.h"
#include "studio/mono_synth.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

extern const ChipEngineOps studio_mono_ops;
static void word(uint8_t *p, unsigned v) { p[0]=v; p[1]=v>>8; }
static void put_event(uint8_t *e, unsigned at, unsigned lane, unsigned op, unsigned a, unsigned b, unsigned c, unsigned d) {
    word(e,at); e[2]=lane; e[3]=op; e[4]=a; e[5]=b; e[6]=c; e[7]=d;
}
static uint8_t *fixture(int tracks) {
    uint8_t *p=calloc(1,studio_mono_ops.image_size); assert(p);
    memcpy(p,"STM1",4); p[4]=tracks; p[5]=1; p[8]=1; p[10]=1; p[1072]=3;
    for (int i=0;i<tracks;i++) { p[16+6*i]=i+1; p[20+6*i]=127; p[21+6*i]=1; }
    for (int i=0;i<116;i++) {
        float hz=i==45 ? 220.f : 440.f; uint32_t bits; memcpy(&bits,&hz,4);
        word(p+64+4*i,bits); word(p+66+4*i,bits>>16);
    }
    uint8_t *patch=p+528+9; /* slot 2, other slots are silent */
    patch[1]=100; patch[5]=100; patch[7]=24; patch[8]=100;
    int n=0; uint8_t *events=p+1328+2*65;
    for (int i=0;i<tracks;i++) put_event(events+8*n++,0,i,0,2,0,0,0);
    for (int i=0;i<tracks;i++) put_event(events+8*n++,32768,i,1,45,200,127,1);
    word(p+1330,n);
    for (int i=0;i<tracks;i++) put_event(events+8*n++,0,i,1,57,175,0,1);
    for (int i=2;i<=64;i++) word(p+1328+2*i,n);
    word(p+12,n); return p;
}
int main(void) {
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
    wire[528+9+8]=0; assert(ops->load_image(image,wire,ops->image_size)==CHIP_OK);
    ops->apply_audition_image(m,image);
    for (int k=0;k<8;k++) assert(ops->tick(m,buf)==1000);
    float peak=0; for (int i=0;i<2000;i++) peak=fmaxf(peak,fabsf(buf[i])); assert(peak<.001f);
    assert(ops->release(m)==1 && ops->tick(m,buf)>0 && ops->tick(m,buf)==0);
    wire[1328+130+2]=8; assert(ops->load_image(image,wire,ops->image_size)==CHIP_ERR_IMAGE);
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
