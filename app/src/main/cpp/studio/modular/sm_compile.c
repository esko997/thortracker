/* Studio modular instrument: off-thread validation and scheduling. SPDX-License-Identifier: GPL-3.0-or-later */
#include "sm_internal.h"
#include <stdlib.h>
#include <string.h>

/* Inputs, outputs, parameter count, discrete bit mask, relative sample cost, bounds. */
const SmSpec sm_specs[SM_KINDS] = {
    [SM_NOTE]      = {{NONE}, {PITCH,GATE,EVENT,MOD,MOD}, 0,0,1, {0},{0}},
    [SM_OSC]       = {{PITCH,MOD,MOD}, {AUDIO}, 3,1,8, {0,-48,.05f},{2,48,.95f}},
    [SM_NOISE]     = {{NONE}, {AUDIO}, 0,0,2, {0},{0}},
    [SM_FILTER]    = {{AUDIO,MOD}, {AUDIO}, 2,0,10, {20,0},{20000,1}},
    [SM_VCA]       = {{AUDIO,MOD}, {AUDIO}, 1,0,2, {0},{2}},
    [SM_FUNCTION]  = {{EVENT,GATE,MOD}, {MOD,EVENT}, 3,4,6, {1,1,0},{8000,8000,1}},
    [SM_RANDOM]    = {{EVENT,MOD}, {MOD,MOD}, 1,0,4, {1},{8000}},
    [SM_MIX]       = {{AUDIO,AUDIO}, {AUDIO}, 3,0,3, {-4,-4,-2},{4,4,2}},
    [SM_OUT]       = {{AUDIO,AUDIO}, {NONE}, 1,0,2, {0},{2}},
    [SM_DELAY]     = {{AUDIO}, {AUDIO}, 0,0,2, {0},{0}},
    [SM_FOLD]      = {{AUDIO,MOD}, {AUDIO}, 1,0,4, {1},{16}},
    [SM_RESONATOR] = {{AUDIO,PITCH,MOD}, {AUDIO}, 2,0,12, {-48,10},{48,4000}},
};

typedef struct Cable {
    int32_t id;
    float scale, offset;
    uint8_t from, src, to, dst;
    bool deferred;
} Cable;

static int find_id(const SmNode *nodes, unsigned count, int32_t id) {
    for (unsigned i=0; i<count; ++i) if (nodes[i].id == id) return (int)i;
    return -1;
}

static float real_word(int32_t bits) {
    float value;
    _Static_assert(sizeof(value) == sizeof(bits), "Studio wire requires binary32");
    memcpy(&value, &bits, sizeof(value));
    return value;
}

static bool in_range(float value, float lo, float hi) {
    return value >= lo && value <= hi;
}

static int by_id(const void *lhs, const void *rhs) {
    const int32_t a=((const Cable *)lhs)->id, b=((const Cable *)rhs)->id;
    return (a>b) - (a<b);
}

static SmPlan *reject(const char **error, const char *reason) {
    if (error) *error = reason;
    return NULL;
}

SmPlan *sm_compile(const int32_t *w, size_t count, const char **error) {
    if (!w || count<5 || w[0]!=1) return reject(error,"Missing or unknown modular graph header");
    if (w[2]<1 || w[2]>SM_MAX_NODES || w[3]<0 || w[3]>SM_MAX_EDGES ||
        w[4]<0 || w[4]>SM_MACROS*SM_MAX_TARGETS) return reject(error,"Modular graph count exceeds format limits");
    const unsigned nn=(unsigned)w[2], ne=(unsigned)w[3], nt=(unsigned)w[4];
    const size_t fixed=5 + ne*7 + nt*5;
    if (count<fixed+nn*2 || count>fixed+nn*(2+SM_PARAMS)) return reject(error,"Incorrect modular graph length");
    const int32_t *cur=w+5, *node_end=w+count-ne*7-nt*5;
    SmNode nodes[SM_MAX_NODES]={0};
    unsigned outputs=0, weight=0;
    for (unsigned i=0; i<nn; ++i) {
        if (node_end-cur<2) return reject(error,"Truncated modular node");
        int32_t id=*cur++, kind=*cur++;
        if (id<=0 || find_id(nodes,i,id)>=0) return reject(error,"Invalid or duplicate modular node ID");
        if (kind<0 || kind>=SM_KINDS) return reject(error,"Unknown modular node kind");
        const SmSpec *s=sm_specs+kind;
        if (node_end-cur<s->params) return reject(error,"Truncated modular node parameters");
        nodes[i].id=id; nodes[i].kind=(uint8_t)kind;
        outputs += kind==SM_OUT;
        weight += s->weight;
        for (unsigned p=0; p<s->params; ++p) {
            float x=real_word(*cur++);
            if (!in_range(x,s->lo[p],s->hi[p]) || ((s->discrete&(1u<<p)) && x!=(float)(int)x))
                return reject(error,"Modular parameter outside range");
            nodes[i].p[p]=x;
        }
    }
    if (cur!=node_end || outputs!=1) return reject(error,"Expected complete node records and exactly one Output");

    Cable cables[SM_MAX_EDGES];
    uint64_t dependencies[SM_MAX_NODES]={0};
    for (unsigned i=0; i<ne; ++i, cur+=7) {
        int from=find_id(nodes,nn,cur[1]), to=find_id(nodes,nn,cur[3]);
        int32_t src=cur[2], dst=cur[4];
        if (from<0 || to<0 || src<0 || src>=SM_OUTPUTS || dst<0 || dst>=SM_INPUTS)
            return reject(error,"Dangling modular cable endpoint");
        unsigned a=sm_specs[nodes[from].kind].outs[src], b=sm_specs[nodes[to].kind].ins[dst];
        if (!a || !b || (a!=b && !(b==MOD && (a==GATE || a==AUDIO))))
            return reject(error,"Incompatible modular cable ports");
        float scale=real_word(cur[5]), offset=real_word(cur[6]);
        if (!in_range(scale,-16,16) || !in_range(offset,-16,16) ||
            ((b==GATE || b==EVENT) && (scale!=1 || offset!=0))) return reject(error,"Invalid modular cable transform");
        if (cur[0]<=0) return reject(error,"Invalid modular cable ID");
        for (unsigned j=0; j<i; ++j) {
            const Cable *e=cables+j;
            if (e->id==cur[0] || (e->from==from && e->to==to && e->src==src && e->dst==dst))
                return reject(error,"Duplicate modular cable");
        }
        bool deferred=a==EVENT || nodes[from].kind==SM_DELAY;
        cables[i]=(Cable){cur[0],scale,offset,(uint8_t)from,(uint8_t)src,(uint8_t)to,(uint8_t)dst,deferred};
        if (!deferred) dependencies[to] |= UINT64_C(1)<<from;
        nodes[to].wired |= (uint8_t)(1u<<dst);
    }
    qsort(cables,ne,sizeof(*cables),by_id);
    uint8_t old_to_new[SM_MAX_NODES], new_to_old[SM_MAX_NODES];
    uint64_t emitted=0;
    for (unsigned i=0; i<nn; ++i) {
        unsigned k=nn;
        for (unsigned j=0; j<nn; ++j) {
            if ((emitted & (UINT64_C(1)<<j)) || (dependencies[j] & ~emitted)) continue;
            if (k==nn || nodes[j].id<nodes[k].id) k=j;
        }
        if (k==nn) return reject(error,"Instantaneous modular cycle: insert a Delay");
        emitted |= UINT64_C(1)<<k;
        old_to_new[k]=(uint8_t)i; new_to_old[i]=(uint8_t)k;
    }
    SmTarget targets[SM_MACROS*SM_MAX_TARGETS];
    uint8_t owners[SM_MAX_NODES]={0}, counts[SM_MACROS]={0};
    for (unsigned i=0; i<nt; ++i, cur+=5) {
        int32_t m=cur[0], p=cur[2];
        int to=find_id(nodes,nn,cur[1]);
        if (m<0 || m>=SM_MACROS || ++counts[m]>SM_MAX_TARGETS || to<0 || p<0 || p>=sm_specs[nodes[to].kind].params)
            return reject(error,"Invalid modular macro target");
        const SmSpec *s=sm_specs+nodes[to].kind;
        if ((owners[to] | s->discrete) & (1u<<p)) return reject(error,"Modular macro target already owned or discrete");
        float lo=real_word(cur[3]), hi=real_word(cur[4]);
        if (!in_range(lo,s->lo[p],s->hi[p]) || !in_range(hi,s->lo[p],s->hi[p]))
            return reject(error,"Modular macro endpoint outside parameter bounds");
        owners[to] |= (uint8_t)(1u<<p);
        targets[i]=(SmTarget){lo,hi,(uint8_t)m,old_to_new[to],(uint8_t)p};
    }

    /* Each tail array has alignment <= pointer alignment; each element size is a multiple
     * of float alignment. There are no external resources and no partial allocation path. */
    size_t bytes=sizeof(SmPlan)+nn*sizeof(SmNode)+ne*(sizeof(SmEdge)+sizeof(SmLink))+nt*sizeof(SmTarget);
    SmPlan *plan=malloc(bytes);
    if (!plan) return reject(error,"Cannot allocate modular plan");
    plan->seed=(uint32_t)w[1];
    plan->cost=(SmCost){.nodes=nn,.edges=ne,.targets=nt,.work_units=weight+ne,.plan_bytes=bytes};
    plan->node=(SmNode *)(plan+1); plan->edge=(SmEdge *)(plan->node+nn); plan->target=(SmTarget *)(plan->edge+ne);
    plan->links=(SmLink *)(plan->target+nt);
    unsigned ti=0;
    for (unsigned m=0; m<SM_MACROS; ++m) {
        plan->target_start[m]=(uint8_t)ti;
        for (unsigned i=0; i<nt; ++i) if (targets[i].macro==m) plan->target[ti++]=targets[i];
    }
    plan->target_start[SM_MACROS]=(uint8_t)ti;
    uint64_t delayed=0;
    unsigned pos=0;
    plan->delayed_count=0;
    for (unsigned i=0; i<nn; ++i) {
        unsigned original=new_to_old[i];
        SmNode *n=plan->node+i;
        *n=nodes[original]; n->first_edge=(uint16_t)pos;
        unsigned rank=i;
        while (rank>0 && plan->node[plan->by_id[rank-1]].id>n->id) {
            plan->by_id[rank]=plan->by_id[rank-1];
            --rank;
        }
        plan->by_id[rank]=(uint8_t)i;
        for (unsigned j=0; j<ne; ++j) {
            const Cable *c=cables+j;
            if (c->to!=original) continue;
            unsigned src_node=old_to_new[c->from], base=src_node*SM_STRIDE;
            if (c->deferred && !(delayed & (UINT64_C(1)<<src_node))) {
                /* V1 has at most one delayed port per source node. */
                delayed |= UINT64_C(1)<<src_node;
                plan->delayed[plan->delayed_count++]=(uint16_t)(base+c->src);
            }
            plan->links[j]=(SmLink){c->id,(uint16_t)pos,(uint8_t)i};
            plan->edge[pos++]=(SmEdge){c->scale,c->offset,(uint16_t)(base+(c->deferred?SM_PRIOR:c->src)),c->dst,(uint8_t)j};
        }
        n->end_edge=(uint16_t)pos;
    }
    if (error) *error=NULL;
    return plan;
}

void sm_plan_destroy(SmPlan *plan) { free(plan); }
