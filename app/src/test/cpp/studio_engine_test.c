/* STM2 -> realtime engine, checked against direct DSP note/legato calls at the expected frames. */
#include "chip_engine.h"
#include "studio/mono_synth.h"
#include "studio/studio_runtime.h"
#include <assert.h>
#include <stdbool.h>
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

/* A different partition from both the internal 128-frame DSP span and a device
 * burst. Keep checking the *musical* position while a logical tick is in flight. */
static int partitioned_tick(ChipMachine *m,float *out) {
    static const int sizes[]={1,191,127,509,17,256};
    assert(studio_tick_boundary(m));
    int order,row,tick,total=0;
    studio_mono_ops.position(m,&order,&row,&tick);
    for(unsigned i=0;;i++){
        int capacity=sizes[i%(sizeof sizes/sizeof sizes[0])];
        if(capacity>(int)CHIP_MAX_TICK_FRAMES-total)capacity=(int)CHIP_MAX_TICK_FRAMES-total;
        assert(capacity>0);
        int n=studio_render_frames(m,out+2*total,capacity);
        assert(n>=0 && n<=capacity);
        total+=n;
        if(studio_tick_boundary(m))return total;
        int o,r,t;studio_mono_ops.position(m,&o,&r,&t);
        assert(n==capacity && o==order && r==row && t==tick);
    }
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
    assert(image&&m);assert(studio_load_score(image,wire,size-1,&bank,NULL)==CHIP_ERR_IMAGE && !bank);
    assert(studio_load_score(image,wire,size,&bank,NULL)==CHIP_OK && bank);
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
    base=fixture(1);assert(studio_load_score(image,base,ops->image_size,&bank,NULL)==CHIP_OK);
    StudioLive *live=NULL;assert(studio_live_prepare(&live,image,48000)==CHIP_OK);
    ops->apply_image(m,image); studio_live_destroy(live);
    assert(ops->tick(m,buf)>0);ops->destroy(m);free(image);free(wire);free(base);
}

static void pedal(uint8_t *w,unsigned id,unsigned kind,int a,int b,int c,int d){
    memset(w,0,32);dword(w,id);w[4]=kind;
    dword(w+8,a);dword(w+12,b);dword(w+16,c);dword(w+20,d);
}

static void fractional_transport_partitions(int rate){
    const ChipEngineOps *ops=&studio_mono_ops;int mode;
    uint8_t *wire=fixture(2),*ev=wire+ROWS_AT+130;
    wire[5]=3; /* row start rotates through fractional tick phases at 44100 Hz */
    uint8_t *fm=wire+PATCHES+SLOT_BYTES,*drum=fm+SLOT_BYTES;
    const int fm_args[]={100,150,300,50,2,50,70,40,80},drum_args[]={1,70,60,80,30,90};
    fm[0]=2;drum[0]=3;
    for(int j=0;j<9;j++)dword(fm+4+4*j,fm_args[j]);
    for(int j=0;j<6;j++)dword(drum+4+4*j,drum_args[j]);
    ev[8+4]=3; /* second lane's row-zero patch event selects the drum */
    put_event(ev+8*6,41234,1,3,0,0,0,0); /* delayed CUT after tied attacks on row 1 */
    word(wire+12,7);for(int i=2;i<=64;i++)word(wire+ROWS_AT+2*i,7);
    wire[STRIPS+1]=0;wire[STRIPS+STRIP_BYTES+1]=200;
    wire[STRIPS+4]=wire[STRIPS+STRIP_BYTES+4]=1;
    pedal(wire+STRIPS+8,101,7,60,800,5000,7);dword(wire+STRIPS+32,30);
    pedal(wire+STRIPS+STRIP_BYTES+8,102,8,100,4000,50,0);
    ChipImage *image=calloc(1,ops->image_storage);
    ChipMachine *whole=ops->create(rate),*part=ops->create(rate);
    assert(image && whole && part && ops->load_image(image,wire,ops->image_size)==CHIP_OK);
    for(int pass=0;pass<2;pass++){
        int row=pass; /* seek and reset a partially rendered machine on the second pass */
        assert(ops->start(whole,image,0,row,96,&mode)==CHIP_OK);
        assert(ops->start(part,image,0,row,96,&mode)==CHIP_OK);
        float a[2*CHIP_MAX_TICK_FRAMES],b[2*CHIP_MAX_TICK_FRAMES];
        double left=0,right=0;
        for(int i=0;i<24;i++){
            int tick=3*row+i,o,r,t;ops->position(part,&o,&r,&t);
            assert(o==0 && r==tick/3%3 && t==tick%3);
            int n=ops->tick(whole,a);
            assert(n==(tick+1)*rate/48-tick*rate/48);
            assert(partitioned_tick(part,b)==n && !memcmp(a,b,(size_t)2*n*sizeof(float)));
            for(int j=0;j<n;j++){left+=fabsf(b[2*j]);right+=fabsf(b[2*j+1]);}
        }
        assert(left>1 && right>1);
        int n=ops->tick(whole,a);
        assert(studio_render_frames(part,b,37)==37 && !studio_tick_boundary(part));
        assert(!studio_render_frames(part,NULL,0) && studio_render_frames(part,NULL,1)<0);
        assert(ops->tick(part,b+74)==n-37 && studio_tick_boundary(part));
        assert(!memcmp(a,b,(size_t)2*n*sizeof(float)));
        assert(studio_render_frames(part,b,111)==111 && !studio_tick_boundary(part));
    }
    ops->destroy(whole);ops->destroy(part);free(image);free(wire);
}

/* Key-off at frame 901 leaves 99 frames in this tick. Compare each tail sample
 * against a direct synth release at that cursor, including Studio's final fade. */
static void cancel_at_cursor(void){
    const ChipEngineOps *ops=&studio_mono_ops;int mode;
    uint8_t *wire=fixture(1);
    ChipImage *image=calloc(1,ops->image_storage);ChipMachine *m=ops->create(48000);
    assert(image && m && ops->load_image(image,wire,ops->image_size)==CHIP_OK);
    float buf[2*CHIP_MAX_TICK_FRAMES];
    for(int audition=0;audition<2;audition++){
        MonoSynth ref;mono_synth_init(&ref,48000);
        MonoSynthPatch patch={0,16000,0,0,.01f,1,0,1};mono_synth_apply_patch(&ref,&patch);
        if(audition)assert(ops->audition(m,image,0,45,2,0)==CHIP_OK);
        else assert(ops->start(m,image,0,0,96,&mode)==CHIP_OK);
        assert(studio_render_frames(m,buf,901)==901);
        for(int j=0;j<901;j++){
            if(j==(audition?0:500))mono_synth_note_on(&ref,220,audition?1.f:200/255.f,audition?0.f:1.f,false);
            (void)mono_synth_render(&ref);
        }
        mono_synth_note_off(&ref);
        assert(audition?ops->release(m):ops->stop_tail(m));
        int n=studio_render_frames(m,buf,1000);
        assert(n==99 && studio_tick_boundary(m));
        assert(partitioned_tick(m,buf+2*n)==385-n);
        double energy=0;
        for(int j=0;j<385;j++){
            float expected=.35f*mono_synth_render(&ref)*((384-j)/960.f);
            assert(fabsf(buf[2*j]-expected)<.0001f && buf[2*j]==buf[2*j+1]);energy+=fabsf(expected);
        }
        assert(energy>.01 && studio_render_frames(m,buf,192)==0);
    }
    /* Before the delayed attack there is no tail and no future attack may run. */
    assert(ops->start(m,image,0,0,96,&mode)==CHIP_OK);
    assert(studio_render_frames(m,buf,499)==499 && !ops->stop_tail(m));
    assert(studio_tick_boundary(m) && !ops->tick(m,buf));
    ops->destroy(m);free(image);free(wire);
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
        assert(ops->tick(machines[0],samples[0])==1000 && partitioned_tick(machines[1],samples[1])==1000);
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
static void audition_ignores_other_tracks_but_keeps_shared_tails(void) {
    const ChipEngineOps *ops=&studio_mono_ops;
    ChipMachine *machines[2]; ChipImage *images[2];
    uint8_t *wire[2]; float pcm[2][CHIP_MAX_TICK_FRAMES*2];
    for(int variant=0;variant<2;variant++) {
        wire[variant]=fixture(8); uint8_t *p=wire[variant];
        p[7]=1; p[STRIPS+2*STRIP_BYTES+2]=50;
        uint8_t *bus=p+STRIPS+8*STRIP_BYTES;
        bus[0]=bus[1]=100; bus[4]=1;
        pedal(bus+8,900,4,0,80,50,6000); dword(bus+8+24,100);
        if(variant) for(int lane=0;lane<8;lane++) if(lane!=2) {
            uint8_t *strip=p+STRIPS+lane*STRIP_BYTES;
            strip[4]=1;
            switch(lane%5) {
                case 0: pedal(strip+8,lane+1,4,0,2000,50,6000); dword(strip+32,100); break;
                case 1: pedal(strip+8,lane+1,5,24,5000,0,0); break;
                case 2: pedal(strip+8,lane+1,6,8,8000,100,0); break;
                case 3: pedal(strip+8,lane+1,7,60,2000,6000,20); dword(strip+32,100); break;
                case 4: pedal(strip+8,lane+1,8,100,5000,100,0); break;
            }
        }
        images[variant]=calloc(1,ops->image_storage); machines[variant]=ops->create(48000);
        assert(images[variant] && machines[variant]);
        assert(ops->load_image(images[variant],p,ops->image_size)==CHIP_OK);
        /* Reusing a machine must clear the previous audition's insert histories. */
        assert(ops->audition(machines[variant],images[variant],5,45,2,0)==CHIP_OK);
        for(int tick=0;tick<10;tick++) assert(ops->tick(machines[variant],pcm[variant])==1000);
        assert(ops->audition(machines[variant],images[variant],2,57,2,0)==CHIP_OK);
    }
    double sounding=0,tail=0;
    for(int tick=0;tick<480;tick++) {
        if(tick==96) for(int i=0;i<2;i++) assert(ops->release(machines[i])==1);
        assert(ops->tick(machines[0],pcm[0])==1000 && partitioned_tick(machines[1],pcm[1])==1000);
        assert(memcmp(pcm[0],pcm[1],2000*sizeof(float))==0);
        for(int sample=0;sample<2000;sample++) {
            if(tick<96) sounding+=fabsf(pcm[0][sample]);
            else tail+=fabsf(pcm[0][sample]);
        }
    }
    assert(sounding>100 && tail>1);
    for(int i=0;i<2;i++) {
        assert(ops->tick(machines[i],pcm[i])==0);
        ops->destroy(machines[i]); free(images[i]); free(wire[i]);
    }
}

static void sampler_root_is_a_recording_reference_not_a_song_tuning(void) {
    const ChipEngineOps *ops=&studio_mono_ops;
    enum { FRAMES=1024 };
    size_t size=ops->image_size+24+FRAMES*4;
    uint8_t *base=fixture(1),*wire=calloc(1,size);assert(wire);
    memcpy(wire,base,ops->image_size);free(base);
    sample(wire+64+4*57,880.f); /* A-4 target retuned one octave above its 440 Hz recording root. */
    uint8_t *patch=wire+PATCHES+SLOT_BYTES;
    memset(patch,0,SLOT_BYTES);patch[0]=1;patch[1]=1;
    dword(patch+4,57);dword(patch+4+6*4,20000);dword(patch+4+7*4,100);
    uint8_t *events=wire+ROWS_AT+130;
    put_event(events,0,0,0,2,0,0,0);put_event(events+8,0,0,1,57,255,0,0);
    for(int row=1;row<=64;row++)word(wire+ROWS_AT+2*row,2);
    word(wire+12,2);
    uint8_t *pcm=wire+ops->image_size;memcpy(pcm,"PCM1",4);
    dword(pcm+4,1);dword(pcm+8,1);dword(pcm+12,1);dword(pcm+16,48000);dword(pcm+20,FRAMES);
    for(int frame=0;frame<FRAMES;frame++)sample(pcm+24+4*frame,frame<256?.5f:-.5f);
    ChipImage *image=calloc(1,ops->image_storage);ChipMachine *machine=ops->create(48000);
    StudioBank *bank=NULL;int mode;float output[2*CHIP_MAX_TICK_FRAMES];
    assert(image&&machine&&studio_load_score(image,wire,size,&bank,NULL)==CHIP_OK);
    assert(ops->start(machine,image,0,0,96,&mode)==CHIP_OK);
    studio_bank_drop(bank);
    assert(ops->tick(machine,output)==1000);
    /* Double speed crosses the sign transition at frame 128, not frame 256. */
    assert(output[2*200]<-.05f && output[2*200+1]<-.05f);
    ops->destroy(machine);free(image);free(wire);
}

/* Two consumers share one publication. Left is the editable wave (with an outgoing
 * retrigger at the first boundary); right is independent held, looping PCM. Reclaim
 * retired generations before ticking again so ASan catches either stale oscillator. */
static void wave_generations_while_sampling(void){
    const ChipEngineOps *ops=&studio_mono_ops;
    const size_t size=ops->image_size+24+64*4;
    uint8_t *wire=calloc(1,size),*base=fixture(2);assert(wire);
    memcpy(wire,base,ops->image_size);free(base);
    uint8_t *wave=wire+PATCHES,*sampler=wave+SLOT_BYTES,*events=wire+ROWS_AT+130;
    wave[0]=9;dword(wave+4+5*4,100);
    for(int i=0;i<64;i++)wave[40+i]=(uint8_t)(4*i-128);
    memset(sampler,0,SLOT_BYTES);sampler[0]=1;sampler[1]=1;
    dword(sampler+4,57);dword(sampler+4+3*4,1);dword(sampler+4+6*4,20000);dword(sampler+4+7*4,100);
    uint8_t *pcm=wire+ops->image_size;memcpy(pcm,"PCM1",4);
    dword(pcm+4,1);dword(pcm+8,1);dword(pcm+12,1);dword(pcm+16,48000);dword(pcm+20,64);
    for(int i=0;i<64;i++)sample(pcm+24+4*i,.5f);
    wire[5]=16;wire[1072]=1;wire[STRIPS+1]=0;wire[STRIPS+STRIP_BYTES+1]=200;
    put_event(events,0,1,0,2,0,0,0);
    put_event(events+8,0,1,1,57,255,0,0);
    put_event(events+16,0,0,1,45,255,0,0);
    put_event(events+24,4055,0,1,57,255,0,0); /* ~990 frames into the first tick */
    for(int row=1;row<=64;row++)word(wire+ROWS_AT+2*row,4);
    word(wire+12,4);
    ChipMachine *song=ops->create(48000),*key=ops->create(48000);
    ChipImage *image=calloc(1,ops->image_storage);
    StudioBank *bank=NULL;StudioWaveBank *waves=NULL;StudioLive *live=NULL;
    int mode;float a[2*CHIP_MAX_TICK_FRAMES],b[2*CHIP_MAX_TICK_FRAMES];
    assert(song && key && image && studio_load_score(image,wire,size,&bank,NULL)==CHIP_OK);
    assert(studio_prepare_waves(image,&waves,song)==CHIP_OK && waves);
    assert(ops->start(song,image,0,0,96,&mode)==CHIP_OK);
    assert(ops->audition(key,image,0,45,1,0)==CHIP_OK);
    assert(ops->tick(song,a)==1000 && ops->tick(key,b)==1000);
    assert(a[1999]>.15f);
    for(int edit=0;edit<13;edit++){
        StudioWaveBank *before=waves;
        bool scalar=edit==1;
        if(scalar)dword(wave+4+4*4,750);
        else for(int i=0;i<64;i++)wave[40+i]=(edit&1)?(uint8_t)(4*i-128):0;
        assert(studio_load_score(image,wire,ops->image_size,&bank,NULL)==CHIP_OK);
        assert(studio_prepare_waves(image,&waves,song)==CHIP_OK);
        if(scalar)assert(waves==before);
        assert(studio_live_prepare(&live,image,48000)==CHIP_OK);
        ops->apply_image(song,image);ops->apply_audition_image(key,image);
        studio_live_destroy(live);live=NULL;
        const float previous=b[1998];
        assert(partitioned_tick(song,a)==1000 && partitioned_tick(key,b)==1000);
        if(!scalar)assert(fabsf(previous-b[0])<.0001f);
        double left=0,key_left=0;
        for(int i=256;i<1000;i++){assert(a[2*i+1]>.15f);left+=fabs(a[2*i]);key_left+=fabs(b[2*i]);}
        if((edit&1) && !scalar){assert(left>5 && key_left>5);}
        else {assert(left<.001 && key_left<.001);}
    }
    /* Replace the patch and reclaim the final wave generation before rendering residuals. */
    memset(wave,0,SLOT_BYTES);wave[0]=10;dword(wave+4+3*4,750);dword(wave+4+5*4,100);
    assert(studio_load_score(image,wire,ops->image_size,&bank,NULL)==CHIP_OK);
    assert(studio_prepare_waves(image,&waves,song)==CHIP_OK && !waves);
    assert(studio_live_prepare(&live,image,48000)==CHIP_OK);
    ops->apply_image(song,image);ops->apply_audition_image(key,image);studio_live_destroy(live);
    assert(ops->tick(song,a)==1000 && a[1999]>.15f && ops->tick(key,b)>0);
    assert(ops->audition(key,image,0,57,1,0)==CHIP_OK && partitioned_tick(key,b)==1000);
    double pluck=0;for(int i=0;i<1000;i++)pluck+=fabs(b[2*i]);assert(pluck>1);
    studio_bank_drop(bank);ops->destroy(song);ops->destroy(key);free(wire);free(image);
}

static void extension_wire_refusals(void){
    const ChipEngineOps *ops=&studio_mono_ops;uint8_t *wire=fixture(1);
    ChipImage *image=calloc(1,ops->image_storage);assert(image);
    uint8_t *patch=wire+PATCHES+SLOT_BYTES,*ev=wire+ROWS_AT+130;
    memset(patch,0,SLOT_BYTES);patch[0]=9;
    put_event(ev,0,0,10,0,0x90,44,1); /* native morph=300 */
    assert(ops->load_image(image,wire,ops->image_size)==CHIP_OK);
    ev[6]=45;assert(ops->load_image(image,wire,ops->image_size)==CHIP_ERR_IMAGE);ev[6]=44;
    ev[5]=0x96;assert(ops->load_image(image,wire,ops->image_size)==CHIP_ERR_IMAGE);ev[5]=0x90;
    wire[STRIPS+4]=1;pedal(wire+STRIPS+8,100,9,0,0,0,0);
    assert(ops->load_image(image,wire,ops->image_size)==CHIP_ERR_IMAGE);
    wire[STRIPS+4]=0;patch[296]=1;assert(ops->load_image(image,wire,ops->image_size)==CHIP_ERR_IMAGE);patch[296]=0;
    patch[0]=11;assert(ops->load_image(image,wire,ops->image_size)==CHIP_ERR_IMAGE);
    patch[0]=10;dword(patch+4+3*4,750);patch[40]=1;
    assert(ops->load_image(image,wire,ops->image_size)==CHIP_ERR_IMAGE);
    free(wire);free(image);
}

/* The rack owns its generation, not the caller's wire or the previous live slot.
 * Same biased mixer on two panned lanes and an independent held audition. SGM1
 * authoring uses native numbers, not the Kotlin encoder under test elsewhere. */
static void modular_generations_and_lane_macros(void){
    const ChipEngineOps *ops=&studio_mono_ops;
    uint8_t *base=fixture(2),*wire=calloc(1,ops->image_size+256);assert(wire);
    memcpy(wire,base,ops->image_size);free(base);
    memset(wire+PATCHES,0,SLOT_BYTES);wire[PATCHES]=11;dword(wire+PATCHES+4,127);
    wire[5]=255;wire[STRIPS+1]=0;wire[STRIPS+STRIP_BYTES+1]=200;
    uint8_t *ev=wire+ROWS_AT+130;
    put_event(ev,0,0,10,0,0xb0,0,0); /* only left playing lane gets macro=0 */
    put_event(ev+8,0,0,1,57,255,0,0);put_event(ev+16,0,1,1,57,255,0,0);
    word(wire+12,3);for(int i=1;i<=64;i++)word(wire+ROWS_AT+2*i,3);
    uint8_t *g=wire+ops->image_size;memcpy(g,"SGM1",4);dword(g+4,1);dword(g+8,0);
    uint8_t *w=g+16;uint32_t one=0x3f800000u;
    ChipImage *s=calloc(1,ops->image_storage);ChipMachine *play=ops->create(48000),*key=ops->create(48000);
    StudioBank *pcm=NULL;StudioGraphBank *graphs=NULL;StudioWaveBank *waves=NULL;
    assert(s && play && key);
    int mode;float a[2*CHIP_MAX_TICK_FRAMES],b[2*CHIP_MAX_TICK_FRAMES];
    for(int edit=0;edit<24;edit++){
        /* Alternate topology (extra DELAY in the path), including scalar-only publications. */
        int delay=(edit/2)&1;
        uint32_t words[]={1,123,(uint32_t)(2+delay),(uint32_t)(1+delay),1,
            10,7,0,0,one,20,8,one};
        size_t n=sizeof words/sizeof words[0];for(size_t i=0;i<n;i++)dword(w+4*i,words[i]);
        if(delay){dword(w+4*n++,30);dword(w+4*n++,9);}
        uint32_t edge[]={1,10,0,delay?30u:20u,0,one,0};
        for(int i=0;i<7;i++)dword(w+4*n++,edge[i]);
        if(delay){uint32_t e[]={2,30,0,20,0,one,0};for(int i=0;i<7;i++)dword(w+4*n++,e[i]);}
        uint32_t target[]={0,20,0,0,one};for(int i=0;i<5;i++)dword(w+4*n++,target[i]);
        dword(g+12,n);size_t size=ops->image_size+16+n*4;
        StudioGraphBank *before=graphs;
        assert(studio_load_score(s,wire,size-1,&pcm,&graphs)==CHIP_ERR_IMAGE && graphs==before);
        dword(w+4*(n-4),999); /* dangling macro endpoint */
        assert(studio_load_score(s,wire,size,&pcm,&graphs)==CHIP_ERR_IMAGE && graphs==before);
        dword(w+4*(n-4),20);
        dword(wire+PATCHES+4,(edit&1)?64:127);
        assert(studio_load_score(s,wire,size,&pcm,&graphs)==CHIP_OK && graphs);
        if(edit&1)assert(graphs==before);
        assert(studio_prepare_waves(s,&waves,play)==CHIP_OK);
        if(!edit){
            assert(ops->start(play,s,0,0,96,&mode)==CHIP_OK);
            assert(ops->audition(key,s,0,57,1,0)==CHIP_OK);
        }else{
            StudioLive *live=NULL;assert(studio_live_prepare(&live,s,48000)==CHIP_OK);
            ops->apply_image(play,s);ops->apply_audition_image(key,s);
            studio_live_destroy(live); /* reclaim before both machines render */
        }
        assert(partitioned_tick(play,a)==1000 && partitioned_tick(key,b)==1000);
        assert(fabsf(a[1998])<.0001f && fabsf(b[1999])<.0001f);
        float level=(edit&1)?.35f*64/127:.35f;
        assert(fabsf(a[1999]-level)<.002f && fabsf(b[1998]-level)<.002f);
    }
    studio_graph_bank_drop(graphs);studio_bank_drop(pcm);studio_wave_bank_drop(waves);
    assert(ops->stop_tail(play));assert(ops->tick(play,a)>0 && ops->tick(play,a)==0);
    assert(ops->release(key));assert(ops->tick(key,b)>0 && ops->tick(key,b)>0 && ops->tick(key,b)==0);
    ops->destroy(play);ops->destroy(key);free(s);free(wire);
}

int main(void) {
    fractional_transport_partitions(44100);
    fractional_transport_partitions(48000);
    cancel_at_cursor();
    modular_generations_and_lane_macros();
    extension_wire_refusals();
    wave_generations_while_sampling();
    sampler_root_is_a_recording_reference_not_a_song_tuning();
    sampler_seek_and_bank_lifetime();
    reorder_and_post_fader_send();
    audition_ignores_other_tracks_but_keeps_shared_tails();
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
        assert(partitioned_tick(m,buf)==1000);
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
