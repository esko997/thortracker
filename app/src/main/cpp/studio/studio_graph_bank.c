/* Immutable modular plan bank. Producer-only allocation and reference counting. */
#include "studio_score.h"
#include <stdlib.h>

typedef struct {
    SmPlan *plan;
    unsigned refs, words;
    uint8_t wire[]; /* exact comparison, never hash-only reuse */
} GraphEntry;
struct StudioGraphBank { unsigned refs; uint32_t mask; GraphEntry *slot[SLOTS]; };

void studio_graph_retain(StudioGraphBank *b){ if(b)++b->refs; }
void studio_graph_bank_drop(StudioGraphBank *b){
    if(!b || --b->refs)return;
    for(int i=0;i<SLOTS;i++){
        GraphEntry *e=b->slot[i];
        if(e && !--e->refs){sm_plan_destroy(e->plan);free(e);}
    }
    free(b);
}
const SmPlan *studio_graph_plan(const StudioGraphBank *b,int i){return b && b->slot[i]?b->slot[i]->plan:NULL;}

/* Exact slot coverage; defend size before decoding a word, compiling or allocating. */
int studio_graph_decode(StudioGraphBank *old,uint32_t mask,const uint8_t **cursor,size_t *remaining,StudioGraphBank **result){
    enum { MAX_WORDS=5+SM_MAX_NODES*5+SM_MAX_EDGES*7+SM_MACROS*SM_MAX_TARGETS*5 };
    if(!mask){*result=NULL;return CHIP_OK;}
    const uint8_t *p=*cursor,*wire[SLOTS]={0}; unsigned words[SLOTS]={0};size_t left=*remaining;
    if(left<8 || u32(p)!=0x314d4753u)return CHIP_ERR_IMAGE;
    unsigned count=u32(p+4);if(!count || count>SLOTS)return CHIP_ERR_IMAGE;
    p+=8;left-=8;uint32_t missing=mask,changed=0;
    for(unsigned j=0;j<count;j++){
        if(left<8)return CHIP_ERR_IMAGE;
        unsigned slot=u32(p),n=u32(p+4);p+=8;left-=8;
        if(slot>=SLOTS || n<5 || n>MAX_WORDS || n>left/4)return CHIP_ERR_IMAGE;
        uint32_t bit=UINT32_C(1)<<slot;if(!(missing&bit))return CHIP_ERR_IMAGE;
        missing^=bit;wire[slot]=p;words[slot]=n;
        GraphEntry *e=old?old->slot[slot]:NULL;
        if(!e || e->words!=n || memcmp(e->wire,p,4*n))changed|=bit;
        p+=4*n;left-=4*n;
    }
    if(missing)return CHIP_ERR_IMAGE;
    StudioGraphBank *next;
    if(old && old->mask==mask && !changed){next=old;studio_graph_retain(next);}
    else {
        next=calloc(1,sizeof *next);if(!next)return CHIP_ERR_IMAGE;
        next->refs=1;next->mask=mask;
        int32_t scratch[MAX_WORDS];
        for(int i=0;i<SLOTS;i++)if(wire[i]){
            if(!(changed&(UINT32_C(1)<<i))){next->slot[i]=old->slot[i];++next->slot[i]->refs;continue;}
            unsigned n=words[i];GraphEntry *e=malloc(sizeof *e+4*n);
            if(!e)goto fail;
            for(unsigned j=0;j<n;j++)scratch[j]=(int32_t)u32(wire[i]+4*j);
            e->plan=sm_compile(scratch,n,NULL);if(!e->plan){free(e);goto fail;}
            e->refs=1;e->words=n;memcpy(e->wire,wire[i],4*n);next->slot[i]=e;
        }
    }
    *result=next;*cursor=p;*remaining=left;return CHIP_OK;
fail:
    studio_graph_bank_drop(next);return CHIP_ERR_IMAGE;
}
