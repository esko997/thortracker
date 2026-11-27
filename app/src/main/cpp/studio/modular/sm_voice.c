/* Studio modular instrument: one allocation per monophonic track, never per patch or note.
 * SPDX-License-Identifier: GPL-3.0-or-later */
#include "sm_kernel.h"
#include <stdlib.h>

typedef struct Unit {
    Kernel state;
    float p[SM_PARAMS], goal[SM_PARAMS], delta[SM_PARAMS];
    uint16_t ramp;
} Unit;
typedef struct Transform { float scale,offset; } Transform;
struct SmVoice {
    const StudioDspTables *basis;
    const SmPlan *plan;
    Unit unit[SM_MAX_NODES];
    float signal[SM_MAX_NODES*SM_STRIDE];
    Transform cable_delta[SM_MAX_EDGES];
    float inv,inv_ms,inv_fade,inv_ramp;
    float hz,pitch,velocity,accent,last[2],anchor[2],release_gain,release_step;
    uint16_t fade_frames,ramp_frames,off_frames,fade,ramp,release;
    uint8_t macros[SM_MACROS];
    bool active,gate,trigger;
};

SmCost sm_cost(const SmPlan *p) {
    SmCost cost=p ? p->cost : (SmCost){0};
    cost.voice_bytes=sizeof(SmVoice);
    return cost;
}

SmVoice *sm_voice_create(uint32_t rate, const StudioDspTables *basis) {
    if (!basis || !dsp_valid_rate(rate)) return NULL;
    SmVoice *v=calloc(1,sizeof(*v));
    if (v) {
        v->basis=basis; v->inv=1.f/(float)rate; v->inv_ms=1000.f/(float)rate;
        v->fade_frames=(uint16_t)(rate*4/1000); v->off_frames=(uint16_t)(rate*32/1000);
        v->ramp_frames=(uint16_t)(rate*3/1000); v->inv_fade=1.f/v->fade_frames; v->inv_ramp=1.f/v->ramp_frames;
    }
    return v;
}
void sm_voice_destroy(SmVoice *v) { free(v); }
void sm_voice_rebase(SmVoice *v, const StudioDspTables *basis) { if (v && basis) v->basis=basis; }
bool sm_active(const SmVoice *v) { return v && v->active; }

void sm_clear(SmVoice *v) {
    if (!v) return;
    v->active=v->gate=v->trigger=false;
    v->fade=v->ramp=v->release=0; v->last[0]=v->last[1]=0;
}

static void declick(SmVoice *v) {
    v->anchor[0]=v->last[0]; v->anchor[1]=v->last[1]; v->fade=v->fade_frames;
}

static void reset_kernel(SmVoice *v, unsigned i) {
    const SmNode *n=v->plan->node+i;
    Kernel *s=&v->unit[i].state;
    memset(s,0,sizeof(*s));
    if (n->kind==SM_RANDOM || n->kind==SM_NOISE) s->random.rng=sm_seed(v->plan->seed,n->id);
    v->signal[i*SM_STRIDE+SM_PRIOR]=0;
}

/* All changed controls of one kernel share a 3 ms linear ramp; a second edit ramps
 * from what was actually rendered. Discrete modes switch at bind, never interpolate. */
static void ramp_unit(SmVoice *v, unsigned i, bool snap) {
    Unit *u=v->unit+i;
    const SmSpec *s=sm_specs+v->plan->node[i].kind;
    u->ramp=0;
    for (unsigned p=0; p<s->params; ++p) {
        if (snap || (s->discrete&(1u<<p))) u->p[p]=u->goal[p];
        u->delta[p]=(u->p[p]-u->goal[p])*v->inv_ramp;
        if (u->delta[p]!=0) u->ramp=v->ramp_frames;
    }
}

static float mapped(const SmTarget *t, unsigned value) {
    if (value==0) return t->min;
    if (value==127) return t->max;
    return dsp_lerp(t->min,t->max,(float)value*(1.f/127));
}

void sm_macro(SmVoice *v, unsigned index, unsigned value) {
    if (!v || index>=SM_MACROS) return;
    if (value>127) value=127;
    if (v->macros[index]==value) return;
    v->macros[index]=(uint8_t)value;
    const SmPlan *p=v->plan;
    if (!p) return;
    uint64_t touched=0;
    for (unsigned j=p->target_start[index]; j<p->target_start[index+1]; ++j) {
        const SmTarget *t=p->target+j;
        v->unit[t->node].goal[t->param]=mapped(t,value);
        touched |= UINT64_C(1)<<t->node;
    }
    for (unsigned j=p->target_start[index]; touched!=0; ++j) {
        unsigned i=p->target[j].node;
        uint64_t bit=UINT64_C(1)<<i;
        if (touched & bit) { touched ^= bit; ramp_unit(v,i,!v->active); }
    }
}

/* Remap matching stable IDs as a permutation, in-place: a node newly inserted before
 * an existing node must NOT overwrite that old node before we move it. Only the previous-
 * sample output needs moving (every instantaneous edge reads newly computed outputs). */
static uint64_t migrate_nodes(SmVoice *v, const SmPlan *old, const SmPlan *next, bool *topology) {
    uint8_t at[SM_MAX_NODES], where[SM_MAX_NODES];
    unsigned cap=old->cost.nodes>next->cost.nodes ? old->cost.nodes : next->cost.nodes;
    for (unsigned i=0; i<cap; ++i) at[i]=where[i]=(uint8_t)i;
    uint64_t matched=0;
    for (unsigned a=0,b=0; a<old->cost.nodes && b<next->cost.nodes;) {
        unsigned oi=old->by_id[a], ni=next->by_id[b];
        const SmNode *on=old->node+oi,*nn=next->node+ni;
        if (on->id<nn->id) { ++a; continue; }
        if (on->id>nn->id) { ++b; continue; }
        ++a; ++b;
        if (on->kind!=nn->kind) { *topology=true; continue; }
        matched |= UINT64_C(1)<<ni;
        if (ni!=oi || nn->end_edge!=on->end_edge || nn->wired!=on->wired) *topology=true;
        unsigned here=where[oi];
        if (here!=ni) {
            Unit tmp=v->unit[ni]; v->unit[ni]=v->unit[here]; v->unit[here]=tmp;
            float prior=v->signal[ni*SM_STRIDE+SM_PRIOR];
            v->signal[ni*SM_STRIDE+SM_PRIOR]=v->signal[here*SM_STRIDE+SM_PRIOR]; v->signal[here*SM_STRIDE+SM_PRIOR]=prior;
            unsigned displaced=at[ni]; at[here]=(uint8_t)displaced; where[displaced]=(uint8_t)here;
            at[ni]=(uint8_t)oi; where[oi]=(uint8_t)ni;
        }
        for (unsigned k=0; k<sm_specs[nn->kind].params; ++k)
            if ((sm_specs[nn->kind].discrete&(1u<<k)) && nn->p[k]!=on->p[k]) *topology=true;
        if (old->seed!=next->seed && (nn->kind==SM_NOISE || nn->kind==SM_RANDOM))
            v->unit[ni].state.random.rng=sm_seed(next->seed,nn->id);
    }
    return matched;
}

static void migrate_cables(SmVoice *v, const SmPlan *old, const SmPlan *next, bool *topology) {
    Transform delta[SM_MAX_EDGES];
    float fraction=(float)v->ramp*v->inv_ramp;
    bool changes=false;
    unsigned a=0;
    for (unsigned b=0; b<next->cost.edges; ++b) {
        const SmLink *n=next->links+b;
        while (a<old->cost.edges && old->links[a].id<n->id) ++a;
        Transform d={0,0};
        if (a<old->cost.edges && old->links[a].id==n->id) {
            const SmLink *o=old->links+a;
            const SmEdge *oe=old->edge+o->edge, *ne=next->edge+n->edge;
            const SmNode *old_src=old->node+oe->source/SM_STRIDE, *new_src=next->node+ne->source/SM_STRIDE;
            const SmNode *old_dst=old->node+o->destination, *new_dst=next->node+n->destination;
            if (old_src->id!=new_src->id || old_src->kind!=new_src->kind || old_dst->id!=new_dst->id ||
                old_dst->kind!=new_dst->kind || oe->source%SM_STRIDE!=ne->source%SM_STRIDE || oe->input!=ne->input) {
                *topology=true;
            } else {
                d.scale=oe->scale-ne->scale; d.offset=oe->offset-ne->offset;
                if (v->ramp) { d.scale+=v->cable_delta[a].scale*fraction; d.offset+=v->cable_delta[a].offset*fraction; }
            }
            ++a;
        } else *topology=true;
        delta[b]=d; changes |= d.scale!=0 || d.offset!=0;
    }
    v->ramp=changes ? v->ramp_frames : 0;
    if (changes) memcpy(v->cable_delta,delta,next->cost.edges*sizeof(*delta));
}

void sm_bind(SmVoice *v, const SmPlan *plan, const uint8_t macros[SM_MACROS], bool migrate) {
    if (!v) return;
    const SmPlan *old=v->plan;
    if (migrate && old==plan) {
        for (unsigned i=0; i<SM_MACROS; ++i) sm_macro(v,i,macros ? macros[i] : 0);
        return;
    }
    bool live=migrate && v->active && old && plan;
    if (!live) sm_clear(v);
    v->plan=plan;
    if (!plan) return;
    uint64_t matched=0;
    if (live) {
        bool topology=old->cost.nodes!=plan->cost.nodes || old->cost.edges!=plan->cost.edges;
        matched=migrate_nodes(v,old,plan,&topology);
        migrate_cables(v,old,plan,&topology);
        for (unsigned i=0; i<plan->cost.nodes; ++i) {
            if (!(matched & (UINT64_C(1)<<i))) { reset_kernel(v,i); topology=true; }
        }
        if (topology) {
            declick(v);
            /* last[] already includes host release; sm_frame will apply it again. */
            if (v->release && v->release_gain>0) {
                v->anchor[0]/=v->release_gain; v->anchor[1]/=v->release_gain;
            }
        }
    }
    for (unsigned i=0; i<plan->cost.nodes; ++i) memcpy(v->unit[i].goal,plan->node[i].p,sizeof(plan->node[i].p));
    for (unsigned m=0; m<SM_MACROS; ++m) {
        unsigned x=macros ? macros[m] : 0;
        v->macros[m]=(uint8_t)(x<=127 ? x : 127);
        for (unsigned t=plan->target_start[m]; t<plan->target_start[m+1]; ++t) {
            const SmTarget *dst=plan->target+t;
            v->unit[dst->node].goal[dst->param]=mapped(dst,v->macros[m]);
        }
    }
    for (unsigned i=0; i<plan->cost.nodes; ++i) ramp_unit(v,i,!(matched & (UINT64_C(1)<<i)));
}

void sm_pitch(SmVoice *v, float hz) {
    if (v && hz!=v->hz && isfinite(hz) && hz>0) {
        v->hz=hz; v->pitch=69+12*log2f(dsp_clamp(hz,.01f,96000.f)*(1.f/440));
    }
}

void sm_note(SmVoice *v, float hz, float velocity, float accent, bool legato) {
    if (!v || !v->plan || !isfinite(hz) || hz<=0) return;
    bool tied=legato && v->gate;
    sm_pitch(v,hz); v->velocity=dsp_param(velocity,0,1,0); v->accent=dsp_param(accent,0,1,0);
    if (!tied) {
        declick(v);
        for (unsigned i=0; i<v->plan->cost.nodes; ++i) { reset_kernel(v,i); ramp_unit(v,i,true); }
        v->ramp=0; v->trigger=true;
    }
    v->gate=v->active=true; v->release=0; v->release_gain=1;
}

static void release(SmVoice *v, unsigned frames) {
    if (!sm_active(v)) return;
    /* A note cancelled at its own boundary must not emit a new self-generating tail. */
    if (v->trigger && v->last[0]==0 && v->last[1]==0) { sm_clear(v); return; }
    v->gate=v->trigger=false;
    if (!v->release || v->release>frames) {
        v->release=(uint16_t)frames;
        v->release_step=v->release_gain/(float)frames;
    }
}
void sm_off(SmVoice *v) { if (v) release(v,v->off_frames); }
void sm_cut(SmVoice *v) { if (v) release(v,v->fade_frames); }

void sm_frame(SmVoice *v, float *left, float *right) {
    if (!sm_active(v)) { *left=*right=0; return; }
    const SmPlan *plan=v->plan;
    float l=0,r=0, fraction=(float)v->ramp*v->inv_ramp;
    for (unsigned i=0; i<plan->cost.nodes; ++i) {
        const SmNode *n=plan->node+i;
        Unit *u=v->unit+i;
        const float *p=u->p;
        if (u->ramp) {
            --u->ramp;
            for (unsigned j=0; j<sm_specs[n->kind].params; ++j) u->p[j]=u->goal[j]+u->delta[j]*u->ramp;
        }
        float in[SM_INPUTS]={0,0,0}, *out=v->signal+i*SM_STRIDE;
        if (v->ramp) {
            for (unsigned j=n->first_edge; j<n->end_edge; ++j) {
                const SmEdge *e=plan->edge+j; const Transform *d=v->cable_delta+e->sorted;
                in[e->input]+=v->signal[e->source]*(e->scale+d->scale*fraction)+(e->offset+d->offset*fraction);
            }
        } else {
            for (unsigned j=n->first_edge; j<n->end_edge; ++j) {
                const SmEdge *e=plan->edge+j;
                in[e->input]+=v->signal[e->source]*e->scale+e->offset;
            }
        }
        switch (n->kind) {
        case SM_NOTE:
            out[0]=v->pitch; out[1]=(float)v->gate; out[2]=(float)v->trigger; out[3]=v->velocity; out[4]=v->accent;
            break;
        case SM_OSC:
            out[0]=osc_frame(&u->state.osc,v->basis,v->inv,((n->wired&1)?in[0]:69)+in[1]+p[1],p[2]+in[2],(unsigned)p[0]);
            break;
        case SM_NOISE: out[0]=random_bipolar(&u->state.random.rng); break;
        case SM_FILTER: out[0]=filter_frame(&u->state.filter,v->inv,dsp_clamp(in[0],-8,8),p[0],in[1],p[1]); break;
        case SM_VCA: out[0]=dsp_clamp(in[0]*((n->wired&2)?in[1]:1)*p[0],-8,8); break;
        case SM_FUNCTION: function_frame(&u->state.function,v->inv_ms,p,in,v->gate,out); break;
        case SM_RANDOM: random_frame(&u->state.random,v->inv_ms,p[0],in,(n->wired&2)!=0,out); break;
        case SM_MIX: out[0]=dsp_clamp(in[0]*p[0]+in[1]*p[1]+p[2],-8,8); break;
        case SM_OUT: l=dsp_clamp(in[0]*p[0],-1,1); r=(n->wired&2) ? dsp_clamp(in[1]*p[0],-1,1) : l; break;
        case SM_DELAY: out[0]=dsp_clamp(in[0],-8,8); break;
        case SM_FOLD: out[0]=fold_frame(in[0],p[0]+in[1]); break;
        case SM_RESONATOR:
            out[0]=ring_frame(&u->state.ring,v->basis,v->inv,dsp_clamp(in[0],-8,8),((n->wired&2)?in[1]:69)+in[2]+p[0],p[1]);
            break;
        }
    }
    for (unsigned i=0; i<plan->delayed_count; ++i) {
        unsigned at=plan->delayed[i]; v->signal[(at/SM_STRIDE)*SM_STRIDE+SM_PRIOR]=v->signal[at];
    }
    v->trigger=false;
    if (v->ramp) --v->ramp;
    if (v->fade) {
        float t=dsp_min(1,(float)v->fade*v->inv_fade); --v->fade;
        l=dsp_clamp((1-t)*l+t*v->anchor[0],-1,1);
        r=dsp_clamp((1-t)*r+t*v->anchor[1],-1,1);
    }
    if (v->release) {
        --v->release; v->release_gain=v->release_step*(float)v->release;
        l*=v->release_gain; r*=v->release_gain;
        if (!v->release) sm_clear(v);
    }
    *left=v->last[0]=l; *right=v->last[1]=r;
}
