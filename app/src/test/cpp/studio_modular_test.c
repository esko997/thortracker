/* Independent public-ABI behavioral tests for Studio Modular. SPDX-License-Identifier: GPL-3.0-or-later */
#include "studio/modular/studio_modular.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define RATE 8000u
#define PI 3.14159265358979323846
#define MAX_WORDS (5+SM_MAX_NODES*5+SM_MAX_EDGES*7+SM_MACROS*SM_MAX_TARGETS*5)

/* Optional ELF link audit: -DSM_TEST_WRAP_ALLOC -Wl,--wrap=malloc,--wrap=calloc,--wrap=realloc,--wrap=free */
static bool rendering;
#ifdef SM_TEST_WRAP_ALLOC
void *__real_malloc(size_t); void *__real_calloc(size_t,size_t); void *__real_realloc(void *,size_t); void __real_free(void *);
void *__wrap_malloc(size_t n) { assert(!rendering); return __real_malloc(n); }
void *__wrap_calloc(size_t n,size_t s) { assert(!rendering); return __real_calloc(n,s); }
void *__wrap_realloc(void *p,size_t n) { assert(!rendering); return __real_realloc(p,n); }
void __wrap_free(void *p) { assert(!rendering); __real_free(p); }
#endif

typedef struct Graph {
    unsigned nn,ne,nt,nw;
    int32_t seed, nodes[SM_MAX_NODES*5], edges[SM_MAX_EDGES][7], targets[SM_MACROS*SM_MAX_TARGETS][5];
} Graph;
typedef struct Stereo { float l,r; } Stereo;
static StudioDspTables tables;
static int32_t bits(float f) { int32_t b; memcpy(&b,&f,sizeof(b)); return b; }
static void node(Graph *g, int id, enum SmKind kind, float a,float b,float c) {
    static const uint8_t params[]={0,3,0,2,1,3,1,3,1,0,1,2};
    float p[]={a,b,c};
    assert(g->nn<SM_MAX_NODES); ++g->nn;
    g->nodes[g->nw++]=id; g->nodes[g->nw++]=kind;
    for (unsigned j=0; j<params[kind]; ++j) g->nodes[g->nw++]=bits(p[j]);
}
static void cable(Graph *g,int src,int sport,int dst,int dport,float scale,float offset) {
    assert(g->ne<SM_MAX_EDGES);
    int32_t e[]={(int32_t)g->ne+1,src,sport,dst,dport,bits(scale),bits(offset)};
    memcpy(g->edges[g->ne++],e,sizeof(e));
}
static void join(Graph *g,int src,int sport,int dst,int dport) { cable(g,src,sport,dst,dport,1,0); }
static void target(Graph *g,int macro,int dst,int param,float min,float max) {
    assert(g->nt<SM_MACROS*SM_MAX_TARGETS);
    int32_t t[]={macro,dst,param,bits(min),bits(max)};
    memcpy(g->targets[g->nt++],t,sizeof(t));
}
static size_t encode(const Graph *g, int32_t *w) {
    w[0]=1; w[1]=g->seed; w[2]=(int)g->nn; w[3]=(int)g->ne; w[4]=(int)g->nt;
    int32_t *p=w+5;
    memcpy(p,g->nodes,g->nw*4); p+=g->nw;
    memcpy(p,g->edges,g->ne*7*4); p+=g->ne*7;
    memcpy(p,g->targets,g->nt*5*4); p+=g->nt*5;
    return (size_t)(p-w);
}
static SmPlan *compile(const Graph *g) {
    int32_t words[MAX_WORDS]; size_t count=encode(g,words); const char *reason=NULL;
    SmPlan *p=sm_compile(words,count,&reason);
    if (!p) fprintf(stderr,"unexpected modular rejection: %s\n",reason);
    assert(p && !reason);
    return p;
}
static void invalid(const Graph *g) {
    int32_t words[MAX_WORDS]; size_t count=encode(g,words); const char *reason=NULL;
    assert(sm_compile(words,count,&reason)==NULL && reason && *reason);
}
static SmVoice *voice(const SmPlan *p,unsigned rate) {
    SmVoice *v=sm_voice_create(rate,&tables);
    assert(v); sm_bind(v,p,NULL,false); assert(!sm_active(v)); return v;
}
static Stereo frame(SmVoice *v) {
    Stereo x; sm_frame(v,&x.l,&x.r);
    assert(isfinite(x.l) && isfinite(x.r) && fabsf(x.l)<=1 && fabsf(x.r)<=1);
    return x;
}
static void skip(SmVoice *v,unsigned n) { while (n--) (void)frame(v); }
static void near_at(float actual,double expected,double tolerance,int line) {
    if (!(fabs(actual-expected)<=tolerance)) {
        fprintf(stderr,"modular:%d got %.9g expected %.12g +/- %.4g\n",line,(double)actual,expected,tolerance); abort();
    }
}
#define NEAR(a,b,t) near_at((a),(b),(t),__LINE__)
static void done(SmVoice *v,SmPlan *p) { sm_voice_destroy(v); sm_plan_destroy(p); }
static void start(SmVoice *v) { sm_note(v,440,1,0,false); }
static void dc(Graph *g,int id,float value) { node(g,id,SM_MIX,0,0,value); }
static void output(Graph *g,int id) { node(g,id,SM_OUT,1,0,0); }

static void test_validation(void) {
    Graph g={0}; invalid(&g);
    output(&g,1); output(&g,2); invalid(&g);
    g=(Graph){0}; output(&g,1); node(&g,1,SM_NOISE,0,0,0); invalid(&g);
    g=(Graph){0}; output(&g,1); node(&g,2,SM_OSC,1.5f,0,.5f); invalid(&g);
    g.nodes[5]=bits(0); g.nodes[6]=bits(INFINITY); invalid(&g);
    g=(Graph){0}; output(&g,1); node(&g,2,SM_NOTE,0,0,0); join(&g,2,0,1,0); invalid(&g); /* pitch != audio */
    g.edges[0][1]=3; invalid(&g); g.ne=0;
    node(&g,3,SM_FUNCTION,1,1,1); cable(&g,2,2,3,0,-1,0); invalid(&g);
    g.edges[0][5]=bits(1); join(&g,2,2,3,0); invalid(&g); /* same endpoints, different IDs */
    g.edges[1][2]=1; g.edges[1][4]=1; g.edges[1][0]=1; invalid(&g); /* duplicate ID, distinct compatible cable */
    g.ne=0; dc(&g,4,0); node(&g,5,SM_VCA,1,0,0); join(&g,4,0,5,0); join(&g,5,0,4,0); invalid(&g);
    g.ne=0; join(&g,5,0,5,1); invalid(&g); /* AUDIO->MOD still instantaneous */
    g.ne=0; target(&g,0,3,2,0,1); invalid(&g); /* loop is discrete */
    g.nt=0; target(&g,0,1,0,2,0); target(&g,7,1,0,0,1); invalid(&g);
    g.nt=1; g.targets[0][4]=bits(NAN); invalid(&g);
    g.nt=0; cable(&g,4,0,1,0,16.01f,0); invalid(&g); g.edges[0][5]=bits(1);
    int32_t words[MAX_WORDS]; size_t count=encode(&g,words);
    assert(!sm_compile(words,count-1,NULL) && !sm_compile(words,count+1,NULL));
    words[3]=INT32_MAX; assert(!sm_compile(words,count,NULL));
    assert(!sm_voice_create(7999,&tables) && !sm_voice_create(96001,&tables) && !sm_voice_create(48000,NULL));
}

static void test_pitch_and_shapes(void) {
    for (unsigned wave=0; wave<3; ++wave) {
        Graph g={0}; output(&g,3); node(&g,2,SM_OSC,(float)wave,12,.25f); node(&g,1,SM_NOTE,0,0,0);
        join(&g,1,0,2,0); join(&g,2,0,3,0);
        SmPlan *p=compile(&g); SmVoice *v=voice(p,RATE);
        assert(frame(v).l==0); sm_note(v,125,1,1,false);
        /* 125 Hz note +12 st is a 32-frame period. Sample away from BLEP edges. */
        for (unsigned i=0; i<256; ++i) {
            Stereo x=frame(v); assert(x.l==x.r);
            unsigned phase=i%32;
            if (i<40 || phase<2 || phase>30 || (wave==2 && phase>=7 && phase<=9)) continue;
            float ph=(float)phase/32;
            double expected=wave==0 ? sin(2*PI*ph) : wave==1 ? 2*ph-1 : ph<.25 ? 1 : -1;
            NEAR(x.l,expected,.001);
        }
        if (!wave) {
            sm_pitch(v,250); /* phase continuous; now a 16-sample period */
            for (unsigned i=0; i<64; ++i) NEAR(frame(v).l,sin(2*PI*i/16),.002);
            sm_pitch(v,NAN); NEAR(frame(v).l,0,.002);
        }
        done(v,p);
    }
}

static void test_note_ports_mix_fold(void) {
    Graph g={0}; node(&g,1,SM_NOTE,0,0,0); dc(&g,2,.5f); node(&g,3,SM_VCA,1,0,0);
    node(&g,4,SM_MIX,2,-1,.1f); node(&g,5,SM_FOLD,4,0,0); output(&g,6);
    join(&g,2,0,3,0); cable(&g,1,3,3,1,2,0); /* velocity */
    join(&g,3,0,4,0); join(&g,2,0,4,1); join(&g,4,0,5,0);
    join(&g,3,0,6,0); join(&g,5,0,6,1);
    SmPlan *p=compile(&g); SmVoice *v=voice(p,RATE); start(v); skip(v,40);
    for (int i=0; i<=16; ++i) {
        float vel=(float)i/16; sm_note(v,440,vel,0,true);
        Stereo x=frame(v); NEAR(x.l,vel,1e-6);
        double folded=2/PI*asin(sin((2*vel-.5+.1)*4*PI/2));
        NEAR(x.r,folded,2e-5);
    }
    done(v,p);
    /* Velocity, accent and gate are independent; gates can drive modulation with an explicit transform. */
    g=(Graph){0}; dc(&g,1,1); node(&g,2,SM_NOTE,0,0,0); node(&g,3,SM_VCA,1,0,0); output(&g,4);
    join(&g,1,0,3,0); cable(&g,2,1,3,1,.5f,.125f); cable(&g,2,4,3,1,-.25f,0); join(&g,3,0,4,0);
    p=compile(&g); v=voice(p,RATE); sm_note(v,440,0,.75f,false); skip(v,40);
    NEAR(frame(v).l,.625-.25*.75,1e-6); sm_off(v); assert(frame(v).l<0); done(v,p);
}

/* Bilinear two-pole lowpass oracle: normalized frequency t=tan(pi*f/fs)/tan(pi*fc/fs).
 * |H|^-2=(1-t^2)^2+(t/Q)^2. Uses an independent double-precision transfer function. */
static void test_filter(void) {
    const float cutoff[]={250,500,2000,500},res[]={0,1,.35f,0};
    for (unsigned k=0; k<4; ++k) {
        Graph g={0}; node(&g,1,SM_NOTE,0,0,0); node(&g,2,SM_OSC,0,0,.5f);
        node(&g,3,SM_FILTER,cutoff[k],res[k],0); output(&g,4);
        join(&g,1,0,2,0); cable(&g,2,0,3,0,.1f,0); cable(&g,2,0,4,0,.1f,0); join(&g,3,0,4,1);
        if (k==3) cable(&g,1,4,3,1,2,0); /* accent .5 -> one octave */
        SmPlan *p=compile(&g); SmVoice *v=voice(p,RATE); sm_note(v,500,1,.5f,false); skip(v,512);
        double raw=0,lp=0;
        for (unsigned i=0; i<1024; ++i) { Stereo x=frame(v); raw+=x.l*x.l; lp+=x.r*x.r; }
        double t=tan(PI*500/RATE)/tan(PI*cutoff[k]*(k==3 ? 2 : 1)/RATE), damp=2-1.7*res[k];
        assert(raw>1); NEAR((float)sqrt(lp/raw),1/hypot(1-t*t,t*damp),.006);
        done(v,p);
    }
}

static void test_delay_and_resonator(void) {
    Graph g={0}; node(&g,1,SM_NOTE,0,0,0); dc(&g,2,1); node(&g,3,SM_VCA,1,0,0);
    node(&g,4,SM_MIX,1,.5f,0); node(&g,5,SM_DELAY,0,0,0); output(&g,6);
    join(&g,2,0,3,0); join(&g,1,3,3,1); join(&g,3,0,4,0); join(&g,5,0,4,1); join(&g,4,0,5,0);
    join(&g,4,0,6,0); join(&g,5,0,6,1);
    SmPlan *p=compile(&g); SmVoice *v=voice(p,48000); sm_note(v,440,0,0,false); skip(v,240);
    sm_note(v,440,1,0,true);
    for (unsigned i=0; i<20; ++i) {
        if (i==1) sm_note(v,440,0,0,true);
        Stereo x=frame(v); NEAR(x.l,ldexp(1,-(int)i),1e-8); NEAR(x.r,i ? ldexp(1,1-(int)i) : 0,1e-8);
    }
    done(v,p);
    g=(Graph){0}; dc(&g,1,1); node(&g,2,SM_NOTE,0,0,0); node(&g,3,SM_VCA,1,0,0);
    node(&g,4,SM_RESONATOR,12,100,0); output(&g,5);
    join(&g,1,0,3,0); join(&g,2,3,3,1); join(&g,2,0,4,1); join(&g,3,0,4,0); join(&g,4,0,5,0);
    p=compile(&g); v=voice(p,RATE); sm_note(v,250,0,0,false); skip(v,40); sm_note(v,250,1,0,true);
    for (unsigned i=0; i<840; ++i) {
        if (i==1) sm_note(v,250,0,0,true);
        NEAR(frame(v).l,.25*cos(2*PI*500*i/RATE)*pow(.001,(double)i/800),.0003);
    }
    done(v,p);
}

static void test_function_event_feedback(void) {
    Graph g={0}; node(&g,1,SM_NOTE,0,0,0); node(&g,2,SM_FUNCTION,8,8,0); node(&g,3,SM_RANDOM,25,0,0);
    dc(&g,4,1); node(&g,5,SM_VCA,1,0,0); node(&g,6,SM_VCA,1,0,0); output(&g,7);
    join(&g,1,2,2,0); join(&g,2,1,2,0); /* EOC re-triggers a non-looping function one sample later. */
    join(&g,2,1,3,0); join(&g,1,3,3,1); join(&g,4,0,5,0); join(&g,2,0,5,1);
    join(&g,4,0,6,0); join(&g,3,0,6,1); join(&g,5,0,7,0); join(&g,6,0,7,1);
    SmPlan *p=compile(&g); SmVoice *v=voice(p,RATE); assert(frame(v).l==0);
    sm_note(v,330,.75f,.5f,false);
    for (unsigned i=0; i<=384; ++i) {
        if (i==190) sm_note(v,660,.25f,.5f,true); /* tie must not inject a second trigger */
        Stereo x=frame(v);
        if (i>=32) { unsigned j=i%128; NEAR(x.l,j<=64 ? j/64. : (128-j)/64.,1e-6); }
        NEAR(x.r,i<129 ? 0 : i<257 ? .75 : .25,1e-6);
    }
    sm_off(v);
    for (unsigned i=0; i<256; ++i) { sm_off(v); frame(v); } /* OFF cannot be extended by EOC or repeated OFF */
    assert(!sm_active(v)); for (unsigned i=0; i<256; ++i) assert(frame(v).r==0);
    start(v); skip(v,200); sm_cut(v); skip(v,31); assert(sm_active(v)); assert(frame(v).l==0 && !sm_active(v));
    start(v); skip(v,140); sm_clear(v); assert(frame(v).r==0 && !sm_active(v));
    done(v,p);
}

static void test_seeded_random_and_noise(void) {
    Graph g={.seed=17}; node(&g,1,SM_FUNCTION,1,1,1); node(&g,2,SM_RANDOM,10,0,0); dc(&g,3,1);
    node(&g,4,SM_VCA,1,0,0); node(&g,5,SM_VCA,1,0,0); output(&g,6);
    join(&g,1,1,2,0); join(&g,3,0,4,0); join(&g,2,0,4,1); join(&g,3,0,5,0); join(&g,2,1,5,1);
    join(&g,4,0,6,0); join(&g,5,0,6,1);
    SmPlan *p=compile(&g); SmVoice *v=voice(p,RATE); start(v); skip(v,40);
    Stereo prev=frame(v);
    unsigned changes=0;
    for (unsigned i=41; i<1000; ++i) {
        Stereo x=frame(v);
        if (i%16!=0) assert(x.l==prev.l); else if (x.l!=prev.l) ++changes;
        double alpha=-expm1(-1./80); NEAR(x.r,prev.r+(x.l-prev.r)*alpha,2e-6); prev=x;
    }
    assert(changes>50); done(v,p);
    g=(Graph){.seed=17}; node(&g,3,SM_NOISE,0,0,0); node(&g,2,SM_NOISE,0,0,0); output(&g,1);
    join(&g,2,0,1,0); join(&g,3,0,1,1);
    p=compile(&g); v=voice(p,RATE); SmVoice *ref=voice(p,RATE);
    ++g.seed; SmPlan *other=compile(&g); SmVoice *w=voice(other,RATE);
    double cross_id=0,cross_seed=0,variance=0,mean=0;
    for (unsigned pass=0; pass<2; ++pass) {
        start(v); start(ref); start(w);
        for (unsigned i=0; i<2048; ++i) {
            Stereo x=frame(v), y=frame(ref), z=frame(w); assert(x.l==y.l && x.r==y.r);
            if (!pass) { cross_id+=x.l*x.r; cross_seed+=x.l*z.l; variance+=x.l*x.l; mean+=x.l; }
        }
        sm_clear(v); sm_clear(ref); sm_clear(w);
    }
    assert(variance>550 && variance<810 && fabs(cross_id)<75 && fabs(cross_seed)<75 && fabs(mean)<120);
    sm_voice_destroy(ref); done(v,p); done(w,other);
}

static void test_macro_and_cable_slew(void) {
    Graph g={0}; dc(&g,1,1); output(&g,2); cable(&g,1,0,2,0,.5f,0);
    target(&g,3,2,0,1,0); /* inverted performance range */
    SmPlan *p=compile(&g); SmVoice *v=voice(p,RATE), *other=voice(p,RATE);
    start(v); start(other); skip(v,40); skip(other,40); NEAR(frame(v).l,.5,0);
    sm_macro(v,3,127);
    for (unsigned i=1; i<=24; ++i) {
        NEAR(frame(v).l,.5*(1-i/24.),1e-6); NEAR(frame(other).l,.5,0);
    }
    sm_macro(v,3,64); skip(v,24); NEAR(frame(v).l,.5*(1-64./127),1e-6);
    g.edges[0][5]=bits(-.5f); g.edges[0][6]=bits(.25f); SmPlan *next=compile(&g);
    sm_bind(other,next,NULL,true); /* same endpoints, transform edit, no held-sample crossfade */
    for (unsigned i=0; i<=24; ++i) NEAR(frame(other).l,.5-.75*i/24,1e-6);
    sm_bind(other,p,NULL,true); skip(other,8); float before=frame(other).l;
    sm_bind(other,next,NULL,true); assert(fabsf(frame(other).l-before)<.04f); skip(other,24); NEAR(frame(other).l,-.25,0);
    sm_voice_destroy(other); done(v,p); sm_plan_destroy(next);
}

static void test_migration(void) {
    Graph g={.seed=42}; node(&g,20,SM_NOISE,0,0,0); node(&g,30,SM_OSC,0,-7,.5f);
    output(&g,40); node(&g,18,SM_DELAY,0,0,0);
    join(&g,20,0,18,0); join(&g,18,0,40,0); join(&g,30,0,40,1);
    SmPlan *p=compile(&g); SmVoice *a=voice(p,RATE),*ref=voice(p,RATE); start(a); start(ref);
    skip(a,93); skip(ref,93); Stereo before=frame(a); frame(ref);
    /* Inserting a lower ID moves multiple mutable kernels, including a previous-sample delay. */
    dc(&g,1,.5f); SmPlan *next=compile(&g);
    sm_bind(a,next,NULL,true); StudioDspTables equivalent=tables; sm_voice_rebase(a,&equivalent);
    Stereo x=frame(a); frame(ref); NEAR(x.l,before.l,1e-7); NEAR(x.r,before.r,1e-7);
    for (unsigned i=1; i<200; ++i) {
        x=frame(a); Stereo y=frame(ref); if (i>=32) assert(x.l==y.l && x.r==y.r);
    }
    /* Replacing compatible state while OFF is running must not renew its deadline. */
    sm_off(a); for (unsigned i=0; i<256; ++i) { sm_bind(a,i%2 ? p : next,NULL,true); frame(a); }
    assert(!sm_active(a) && frame(a).l==0); sm_bind(a,NULL,NULL,false); start(a); assert(frame(a).r==0);
    sm_voice_destroy(ref); done(a,p); sm_plan_destroy(next);
}

static uint32_t draw(uint32_t *s) { *s=*s*1664525u+1013904223u; return *s; }
static void test_format_ceiling_and_rt(void) {
    uint32_t seed=88; Graph g={0};
    for (int i=1; i<64; ++i) node(&g,i,SM_MIX,i%2 ? 4 : -4,i%3 ? -4 : 4,i%2 ? 2 : -2);
    output(&g,64);
    bool used[65][65][2]={0};
    for (int dst=2; dst<=64; ++dst) { cable(&g,dst-1,0,dst,0,1,0); used[dst-1][dst][0]=true; }
    while (g.ne<SM_MAX_EDGES) {
        int dst=2+(int)((draw(&seed)>>8)%63),src=1+(int)((draw(&seed)>>8)%(unsigned)(dst-1));
        unsigned input=draw(&seed)>>31;
        if (used[src][dst][input]) continue;
        cable(&g,src,0,dst,(int)input,input ? -16 : 16,dst%2 ? 16 : -16); used[src][dst][input]=true;
    }
    for (unsigned i=0; i<128; ++i) target(&g,(int)(i/16),(int)(1+i/3),(int)(i%3),i%3==2 ? 2 : 4,i%3==2 ? -2 : -4);
    SmPlan *p=compile(&g); SmCost c=sm_cost(p);
    assert(c.nodes==64 && c.edges==256 && c.targets==128 && c.work_units>=c.nodes+c.edges);
    assert(c.plan_bytes>0 && c.voice_bytes==sm_cost(NULL).voice_bytes && !sm_cost(NULL).plan_bytes);
    g.targets[16][0]=0; invalid(&g); g.targets[16][0]=1;
    g.edges[0][5]=bits(-16); SmPlan *q=compile(&g);
    const unsigned rates[]={8000,44100,96000};
    for (unsigned k=0; k<3; ++k) {
        SmVoice *v=voice(p,rates[k]); uint8_t macros[8]={0};
        rendering=true; start(v);
        for (unsigned i=0; i<4000; ++i) {
            unsigned m=draw(&seed)>>29; macros[m]=(uint8_t)((draw(&seed)>>8)&127); sm_macro(v,m,macros[m]);
            if (i%17==0) sm_bind(v,i%2 ? p : q,macros,true);
            if (i%101==0) sm_note(v,i%2 ? 1e-8f : 1e20f,i%2 ? NAN : 1,INFINITY,i%2!=0);
            if (i%127==0) sm_cut(v);
            (void)frame(v);
        }
        sm_clear(v); sm_bind(v,NULL,NULL,false); assert(frame(v).r==0); rendering=false;
        sm_voice_destroy(v);
    }
    sm_plan_destroy(p); sm_plan_destroy(q);
}

static void test_audio_rate_fm(void) {
    Graph g={0}; node(&g,10,SM_OSC,0,0,.5f); node(&g,20,SM_NOTE,0,0,0);
    node(&g,30,SM_OSC,0,0,.5f); output(&g,40);
    join(&g,20,0,30,0); cable(&g,10,0,30,1,12,0);
    join(&g,10,0,40,0); join(&g,30,0,40,1);
    SmPlan *p=compile(&g); SmVoice *v=voice(p,RATE); sm_note(v,220,1,1,false);
    double phase=0;
    for (unsigned i=0; i<1024; ++i) {
        double mod=sin(2*PI*440*i/RATE);
        Stereo x=frame(v);
        if (i>32) { NEAR(x.l,mod,3e-5); NEAR(x.r,sin(2*PI*phase),.002); }
        phase+=220./RATE*exp2(mod);  /* independent integral of instantaneous frequency */
    }
    done(v,p);
}

static void test_modulated_feedback_extremes(void) {
    Graph g={.seed=-1}; node(&g,1,SM_NOISE,0,0,0); node(&g,2,SM_NOTE,0,0,0);
    node(&g,3,SM_OSC,2,48,.05f); node(&g,4,SM_FILTER,20000,1,0); node(&g,5,SM_DELAY,0,0,0);
    node(&g,6,SM_FOLD,16,0,0); node(&g,7,SM_RESONATOR,-48,4000,0); output(&g,8);
    join(&g,2,0,3,0); cable(&g,1,0,3,1,-16,16); cable(&g,1,0,3,2,16,-8);
    cable(&g,3,0,4,0,16,0); cable(&g,5,0,4,0,-16,0); cable(&g,1,0,4,1,16,0);
    join(&g,4,0,6,0); cable(&g,1,0,6,1,-16,0); join(&g,6,0,7,0); join(&g,2,0,7,1);
    join(&g,7,0,5,0); cable(&g,1,0,7,2,16,0); cable(&g,4,0,8,0,.1f,0); cable(&g,7,0,8,1,.125f,0);
    target(&g,0,7,1,10,4000); target(&g,1,4,0,20,20000); target(&g,2,3,1,-48,48);
    SmPlan *p=compile(&g); uint32_t seed=0x7ff;
    for (unsigned rate=8000; rate<=96000; rate+=88000) {
        SmVoice *v=voice(p,rate); start(v);
        double l2=0,r2=0;
        for (unsigned i=0; i<rate/2; ++i) {
            if (i%37==0) {
                unsigned m=(draw(&seed)>>8)%3, value=draw(&seed)>>25;
                sm_macro(v,m,value);
            }
            if (i%211==0) sm_pitch(v,i%2 ? .01f : 90000);
            Stereo x=frame(v); l2+=x.l*x.l; r2+=x.r*x.r;
        }
        assert(l2>1 && r2>1); sm_cut(v); skip(v,rate*4/1000); assert(!sm_active(v));
        sm_voice_destroy(v);
    }
    sm_plan_destroy(p);
}

int main(void) {
    studio_dsp_prepare_tables(&tables);
    test_validation(); test_pitch_and_shapes(); test_note_ports_mix_fold(); test_filter();
    test_delay_and_resonator(); test_function_event_feedback(); test_seeded_random_and_noise();
    test_macro_and_cable_slew(); test_migration(); test_audio_rate_fm();
    test_format_ceiling_and_rt(); test_modulated_feedback_extremes();
    puts("Studio Modular: typed graph, synthesis, one-sample feedback, macros, migration, stop and format ceilings OK");
    return 0;
}
