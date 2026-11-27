/* Per-sample modular kernels. SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef THOR_SM_KERNEL_H
#define THOR_SM_KERNEL_H
#include "sm_internal.h"
#include <string.h>

typedef struct OscState { double phase; float pitch, step; } OscState;
typedef struct FilterState { float ic1,ic2, cutoff,mod,res, a1,a2,a3; } FilterState;
typedef struct FunctionState { double level; float rate, rise, fall, up, down; uint8_t stage, gate; } FunctionState;
typedef struct RandomState { uint32_t rng; float held, smooth, ms, alpha; } RandomState;
typedef struct RingState { float re,im,pitch,decay,radius,cr,si; } RingState;
typedef union Kernel {
    OscState osc; FilterState filter; FunctionState function; RandomState random; RingState ring;
} Kernel;

/* For bounded octave modulation: synthesize 2^n exactly, approximate the fractional
 * part around zero (|t| <= ln(2)/2). No libm transcendental on the sample path. */
static inline float sm_exp2(float octaves) {
    int n=(int)(octaves+(octaves<0 ? -.5f : .5f));
    uint32_t bits=(uint32_t)(n+127)<<23;
    float base;
    memcpy(&base,&bits,sizeof(base));
    float t=(octaves-(float)n)*.69314718056f;
    return base*(1+t*(1+t*(.5f+t*(1.f/6+t*(1.f/24+t/120)))));
}

static inline float sine(const StudioDspTables *basis, float phase) {
    float p=phase*STUDIO_DSP_SINE_SIZE;
    unsigned i=(unsigned)p;
    float frac=p-(float)i;
    i &= STUDIO_DSP_SINE_SIZE-1;
    return dsp_lerp(basis->sine[i],basis->sine[i+1],frac);
}

static inline float step_for(float pitch, float inv) {
    float oct=dsp_clamp((pitch-69)*(1.f/12),-16,8);
    return dsp_min(.45f, 440*inv*sm_exp2(oct));
}

static inline uint32_t sm_seed(uint32_t seed, int32_t id) {
    /* Avalanche the id: consecutive noise nodes must not begin in adjacent xorshift states. */
    uint32_t x=seed+(uint32_t)id*UINT32_C(0x9e3779b9);
    x=(x^(x>>16))*UINT32_C(0x21f0aaad);
    x=(x^(x>>15))*UINT32_C(0x735a2d97);
    x^=x>>15;
    return x ? x : UINT32_C(0x6d2b79f5);
}

static inline float random_bipolar(uint32_t *state) {
    uint32_t x=*state;
    x^=x<<13; x^=x>>17; x^=x<<5; *state=x;
    return (float)(x>>8)*(1.f/8388608.f)-1;
}

/* MonoSynth's quadratic bandlimited edge correction, applied separately at wrap and width. */
static inline float blep(float phase, float step) {
    if (phase<step) { float t=1-phase/step; return -t*t; }
    if (phase>1-step) { float t=1+(phase-1)/step; return t*t; }
    return 0;
}

static inline float osc_frame(OscState *s, const StudioDspTables *basis, float inv,
                              float pitch, float width, unsigned wave) {
    if (s->pitch!=pitch || !s->step) { s->step=step_for(pitch,inv); s->pitch=pitch; }
    float ph=(float)s->phase, out;
    if (!wave) out=sine(basis,ph);
    else if (wave==1) out=2*ph-1-blep(ph,s->step);
    else {
        width=dsp_clamp(width,.05f,.95f);
        float other=ph-width;
        out=(other<0 ? 1.f : -1.f)+blep(ph,s->step)-blep(other<0 ? other+1 : other,s->step);
    }
    s->phase+=s->step;
    if (s->phase>=1) s->phase-=1;
    return out;
}

/* Same TPT topology and continued-fraction tuning as MonoSynth. Cache the solved
 * coefficients for steady controls; fully modulated cutoff still updates every sample. */
static inline float filter_frame(FilterState *s, float inv, float in, float cutoff, float mod, float res) {
    mod=dsp_clamp(mod,-16,16);
    if (cutoff!=s->cutoff || mod!=s->mod || res!=s->res) {
        s->cutoff=cutoff; s->mod=mod; s->res=res;
        float norm=dsp_clamp(cutoff*inv*(mod==0 ? 1 : sm_exp2(mod)),.5f*inv,.4f);
        float x=3.14159265359f*norm, xx=x*x;
        float n=x*(105-10*xx), d=105-xx*(45-xx);
        float denom=1/(d*d+n*(n+d*(2-1.7f*res)));
        s->a1=d*d*denom; s->a2=n*d*denom; s->a3=n*n*denom;
    }
    float diff=in-s->ic2, band=s->a1*s->ic1+s->a2*diff;
    float low=s->ic2+s->a2*s->ic1+s->a3*diff;
    s->ic1=dsp_zap_tiny(dsp_clamp(2*band-s->ic1,-16,16));
    s->ic2=dsp_zap_tiny(dsp_clamp(2*low-s->ic2,-16,16));
    return dsp_clamp(low,-8,8);
}

static inline void function_frame(FunctionState *s, float inv_ms, const float *p,
                                  const float *in, bool host_gate, float *out) {
    bool gate=in[1]>0, trig=in[0]>0 || (gate && !s->gate);
    s->gate=gate;
    if (trig) { s->stage=1; s->level=0; }
    else if (!s->stage && p[2]!=0 && host_gate) s->stage=1;
    out[1]=0;
    if (s->stage) {
        float rate=dsp_clamp(in[2],-8,8);
        if (rate!=s->rate || p[0]!=s->rise || p[1]!=s->fall) {
            float base=inv_ms*(rate==0 ? 1 : sm_exp2(rate));
            s->up=base/p[0]; s->down=base/p[1];
            s->rate=rate; s->rise=p[0]; s->fall=p[1];
        }
        if (s->stage==1) {
            s->level+=s->up;
            if (s->level>=1) { s->level=1; s->stage=2; }
        } else {
            s->level-=s->down;
            if (s->level<=0) { s->level=0; s->stage=0; out[1]=1; }
        }
    }
    out[0]=s->level;
}

static inline void random_frame(RandomState *s, float inv_ms, float ms,
                                const float *in, bool sampled, float *out) {
    if (in[0]>0) s->held=sampled ? dsp_clamp(in[1],-8,8) : random_bipolar(&s->rng);
    if (ms!=s->ms) {
        /* [1/1] Pade for exp(-dt/tau), max dt/tau is 1/8 at 8 kHz and 1 ms. */
        s->alpha=inv_ms/(ms+.5f*inv_ms); s->ms=ms;
    }
    s->smooth=dsp_filter(s->smooth,s->held,s->alpha);
    out[0]=s->held; out[1]=s->smooth;
}

static inline float fold_frame(float in, float drive) {
    /* Triangle extension of identity on [-1,1]. Don't round away quiet input. */
    float x=dsp_clamp(in,-8,8)*dsp_clamp(drive,1,16);
    if (fabsf(x)<=1) return x;
    float shifted=x+129;
    float t=shifted-(float)((int)(shifted*.25f)*4);
    return 1-fabsf(t-2);
}

static inline float ring_frame(RingState *s, const StudioDspTables *basis, float inv,
                               float in, float pitch, float decay) {
    if (pitch!=s->pitch || decay!=s->decay) {
        float step=step_for(pitch,inv);
        float si=sine(basis,step), cr=sine(basis,step+.25f);
        /* Prevent interpolation error from shortening long decays. One reciprocal-length
         * Newton step suffices for the 1024-entry sine basis. Rotation is never expansive. */
        float norm=1.5f-.5f*(si*si+cr*cr);
        /* FM changes the angle, not the pole radius. Use the exact same decay
         * calculation on each effective decay change, including every slew sample.
         * Zero-initialized decay is outside its legal range (10..4000 ms). */
        if (decay!=s->decay) {
            float dt=6907.755279f*inv/decay;
            s->radius=1-dt/(1+.5f*dt);
            s->decay=decay;
        }
        s->si=si*(norm*s->radius); s->cr=cr*(norm*s->radius);
        s->pitch=pitch;
    }
    float re=s->cr*s->re-s->si*s->im+.25f*in;
    s->im=dsp_zap_tiny(dsp_clamp(s->si*s->re+s->cr*s->im,-8,8));
    s->re=dsp_zap_tiny(dsp_clamp(re,-8,8));
    return s->re;
}
#endif
