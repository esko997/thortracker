/* XGM / XGC frame encoding (ym_xgm.h): SGDK xgmtool 1.76 (MIT, Stephane Dallongeville), the functions named below,
 * for songs without PCM. Every rule mirrors xgmtool's source; the host test checks the bytes against xgmtool. */
#include "ym_xgm.h"

#include <string.h>

/* ---- YM2612 state (xgmtool ym2612.c) ----------------------------------------------------------- */

static const uint8_t DUALS[7][2] = {{0x24, 0x25}, {0xA4, 0xA0}, {0xA5, 0xA1}, {0xA6, 0xA2},
                                    {0xAC, 0xA8}, {0xAD, 0xA9}, {0xAE, 0xAA}};

static void ym_clear(YmxYmState* s) {
    for (int p = 0; p < 2; ++p)
        for (int r = 0; r < 256; ++r) s->reg[p][r] = -1;
    memset(s->init, 0, sizeof s->init);
}

/* YM2612_canIgnore */
static int can_ignore(int port, int reg) {
    switch (reg) {
    case 0x22: case 0x24: case 0x25: case 0x26: case 0x27: case 0x28: case 0x2B:
        return port == 1;
    }
    if (reg >= 0x30 && reg < 0xB8) return (reg & 3) == 3;
    return 1;
}

static int is_dual(int reg) {
    for (int i = 0; i < 7; ++i)
        if (DUALS[i][0] == reg || DUALS[i][1] == reg) return 1;
    return 0;
}

static int ym_get(const YmxYmState* s, int port, int reg) { return can_ignore(port, reg) ? 0 : s->reg[port][reg]; }

/* YM2612_set */
static void ym_set(YmxYmState* s, int port, int reg, int value) {
    if (can_ignore(port, reg)) return;
    int v = value;
    if (port == 0) {
        if (reg == 0x28) {
            s->reg[0][value & 7] = (int16_t)(value & 0xF0);
            return;
        }
        if (reg == 0x27) v &= 0xC0;
    }
    s->reg[port][reg] = (int16_t)v;
    s->init[port][reg] = 1;
}

/* YM2612_isSame(source, state) */
static int ym_same(const YmxYmState* source, const YmxYmState* state, int port, int reg) {
    if (!source->init[port][reg] && !state->init[port][reg]) return 1;
    return (source->init[port][reg] || can_ignore(port, reg)) && ym_get(source, port, reg) == ym_get(state, port, reg);
}

/* ---- PSG state (xgmtool psg.c) ------------------------------------------------------------------ */

static void psg_clear(YmxPsgState* s) {
    for (int i = 0; i < 4; ++i)
        for (int t = 0; t < 2; ++t) s->reg[i][t] = -1;
    memset(s->init, 0, sizeof s->init);
    s->index = s->type = -1;
}

static int psg_get(const YmxPsgState* s, int ind, int typ) {
    if (typ == 0) return ind == 3 ? s->reg[ind][typ] & 0x7 : s->reg[ind][typ] & 0x3FF;
    return s->reg[ind][typ] & 0xF;
}

static void psg_write(YmxPsgState* s, int value) {
    if (value & 0x80) {
        s->index = (value >> 5) & 3;
        s->type = (value >> 4) & 1;
    } else if (s->index < 0) {
        return; /* a data byte without a latch: the player never writes one */
    }
    int32_t* r = &s->reg[s->index][s->type];
    if (s->type == 0 && s->index == 3) *r = (*r & ~0x7) | (value & 0x7);
    else if ((value & 0x80) || s->type == 1) *r = (*r & ~0xF) | (value & 0xF);
    else *r = (*r & ~0x3F0) | ((value & 0x3F) << 4);
    s->init[s->index][s->type] = 1;
}

/* ---- the clean step (VGM_cleanCommands, one frame) ------------------------------------------------ */

static void push(YmXgmEncoder* e, int kind, int reg, int val) {
    if (e->ncleaned < YMX_MAX_CMDS) e->cleaned[e->ncleaned++] = (YmxCmd){(uint8_t)kind, (uint8_t)reg, (uint8_t)val};
}

/* YM2612_getDelta(source, state): the register pairs first, then each port's registers in order. */
static void ym_delta(YmXgmEncoder* e, const YmxYmState* source, const YmxYmState* state) {
    for (int i = 0; i < 7; ++i) {
        const int r0 = DUALS[i][0], r1 = DUALS[i][1];
        for (int port = 0; port < (r0 > 0x30 ? 2 : 1); ++port) {
            if (!ym_same(source, state, port, r0) || !ym_same(source, state, port, r1)) {
                push(e, port, r0, ym_get(state, port, r0));
                push(e, port, r1, ym_get(state, port, r1));
            }
        }
    }
    for (int port = 0; port < 2; ++port)
        for (int reg = 0; reg < 256; ++reg) {
            if (can_ignore(port, reg) || (port == 0 && reg == 0x28) || is_dual(reg)) continue;
            if (!ym_same(source, state, port, reg)) push(e, port, reg, ym_get(state, port, reg));
        }
}

/* PSG_getDelta(psg, state) */
static void psg_delta(YmXgmEncoder* e, const YmxPsgState* psg, const YmxPsgState* state) {
    for (int ind = 0; ind < 4; ++ind)
        for (int typ = 0; typ < 2; ++typ) {
            const int unset = !psg->init[ind][typ] && !state->init[ind][typ];
            const int v = psg_get(state, ind, typ), o = psg_get(psg, ind, typ);
            if (typ == 0) {
                const int low_same = unset || (psg->init[ind][typ] && (v & 0xF) == (o & 0xF));
                const int high_same = unset || (psg->init[ind][typ] && (v & 0x3F0) == (o & 0x3F0));
                if (!low_same && high_same) {
                    push(e, YM_W_PSG, 0, 0x80 | ind << 5 | typ << 4 | (v & 0xF));
                    continue;
                }
            }
            if (!(unset || (psg->init[ind][typ] && v == o))) {
                push(e, YM_W_PSG, 0, 0x80 | ind << 5 | typ << 4 | (v & 0xF));
                if (typ == 0 && ind != 3) push(e, YM_W_PSG, 0, (v >> 4) & 0x3F);
            }
        }
}

static int is_key(const YmxCmd* c) { return c->kind == YM_W_PORT0 && c->reg == 0x28; }

static void clean(YmXgmEncoder* e, const YmWrite* in, int n, int loop_at) {
    YmxYmState* ym = &e->cur;
    YmxPsgState* psg = &e->cur_psg;
    *ym = e->clean_ym;
    *psg = e->clean_psg;
    psg->index = psg->type = -1;
    e->ncleaned = 0;
    int nkeys = 0;
    for (int i = 0; i <= n; ++i) {
        if (i == loop_at) { /* the loop-start command resets the previous state: the frame carries every register */
            ym_clear(&e->clean_ym);
            psg_clear(&e->clean_psg);
        }
        if (i == n) break;
        const YmxCmd c = {in[i].kind, in[i].reg, in[i].val};
        if (c.kind == YM_W_PSG) {
            psg_write(psg, c.val);
            continue;
        }
        if (is_key(&c)) {
            if (nkeys < YMX_MAX_CMDS) e->keys[nkeys++] = c;
        } else if (nkeys) { /* a register write after key writes: the delta so far, then those keys */
            ym_delta(e, &e->clean_ym, ym);
            for (int k = 0; k < nkeys; ++k) push(e, e->keys[k].kind, e->keys[k].reg, e->keys[k].val);
            nkeys = 0;
            e->clean_ym = *ym;
        }
        ym_set(ym, c.kind, c.reg, c.val);
    }
    ym_delta(e, &e->clean_ym, ym);
    for (int k = 0; k < nkeys; ++k) push(e, e->keys[k].kind, e->keys[k].reg, e->keys[k].val);
    psg_delta(e, &e->clean_psg, psg);
    e->clean_ym = *ym;
    e->clean_psg = *psg;
}

/* ---- byte lists ----------------------------------------------------------------------------------- */

typedef struct Out {
    uint8_t* p;
    size_t n, cap;
    int overflow;
} Out;

static void put(Out* o, int b) {
    if (o->n < o->cap) o->p[o->n++] = (uint8_t)b;
    else o->overflow = 1;
}

static void close_cmd(YmxList* list, const Out* o, size_t start) {
    if (list->n >= YMX_MAX_CMDS) return;
    list->start[list->n] = (int)start;
    list->len[list->n++] = (int)(o->n - start);
}

/* ---- XGM (XGM_extractMusic, one frame) ------------------------------------------------------------ */

/* XGMCommand_create{YMPort0,YMPort1,YMKey,PSG}Commands: chunks of at most 16 writes. */
static void xgm_group(Out* o, YmxList* cmds, const YmxCmd* list, int n, int code, int pairs) {
    for (int i = 0; i < n; i += 16) {
        const int m = n - i < 16 ? n - i : 16;
        const size_t start = o->n;
        put(o, code | (m - 1));
        for (int j = 0; j < m; ++j) {
            if (pairs) put(o, list[i + j].reg);
            put(o, list[i + j].val);
        }
        close_cmd(cmds, o, start);
    }
}

static void extract_xgm(YmXgmEncoder* e, Out* o) {
    enum { P0, P1, KEYS, PSG };
    int count[4] = {0, 0, 0, 0}, has_key = 0;
    e->xgm_cmds.n = 0;
    for (int i = 0; i < e->ncleaned; ++i) {
        const YmxCmd* c = &e->cleaned[i];
        if (c->kind == YM_W_PSG) {
            e->group[PSG][count[PSG]++] = *c;
        } else if (is_key(c)) {
            e->group[KEYS][count[KEYS]++] = *c;
            has_key = 1;
        } else {
            if (has_key) { /* port 0, port 1, then the keys, before this write */
                xgm_group(o, &e->xgm_cmds, e->group[P0], count[P0], 0x20, 1);
                xgm_group(o, &e->xgm_cmds, e->group[P1], count[P1], 0x30, 1);
                xgm_group(o, &e->xgm_cmds, e->group[KEYS], count[KEYS], 0x40, 0);
                count[P0] = count[P1] = count[KEYS] = 0;
                has_key = 0;
            }
            const int g = c->kind == YM_W_PORT0 ? P0 : P1;
            e->group[g][count[g]++] = *c;
        }
    }
    xgm_group(o, &e->xgm_cmds, e->group[P0], count[P0], 0x20, 1);
    xgm_group(o, &e->xgm_cmds, e->group[P1], count[P1], 0x30, 1);
    xgm_group(o, &e->xgm_cmds, e->group[KEYS], count[KEYS], 0x40, 0);
    xgm_group(o, &e->xgm_cmds, e->group[PSG], count[PSG], 0x10, 0);
}

/* ---- XGC (XGC_extractMusic, one frame) ------------------------------------------------------------ */

/* XGCCommand_convertSingle: PSG commands become envelope commands (chunks of 4) then tone commands (8), key
 * commands chunks of 6, others stay; command bytes are the XGM codes << 1 (XGC_asByteArray). */
static void xgc_convert(const uint8_t* cmd, int len, Out* o, YmxList* out) {
    const int code = cmd[0] & 0xF0;
    if (code == 0x10 || code == 0x40) {
        uint8_t env[16], tone[16];
        int ne = 0, nt = 0;
        for (int i = 1; i < len; ++i) {
            if (code == 0x10 && (cmd[i] & 0x91) == 0x91) env[ne++] = cmd[i];
            else tone[nt++] = cmd[i];
        }
        for (int pass = 0; pass < 2; ++pass) {
            const uint8_t* list = pass ? tone : env;
            const int count = pass ? nt : ne;
            const int chunk = code == 0x40 ? 6 : pass ? 8 : 4;
            const int base = code == 0x40 ? 0x40 : pass ? 0x10 : 0x18;
            for (int i = 0; i < count; i += chunk) {
                const int m = count - i < chunk ? count - i : chunk;
                const size_t start = o->n;
                put(o, (base | (m - 1)) << 1);
                for (int j = 0; j < m; ++j) put(o, list[i + j]);
                close_cmd(out, o, start);
            }
        }
        return;
    }
    const size_t start = o->n;
    put(o, cmd[0] << 1);
    for (int i = 1; i < len; ++i) put(o, cmd[i]);
    close_cmd(out, o, start);
}

/* A port command with its port-0 $2B writes removed (XGMCommand_removeYM2612RegWrite(command, 0, 0x2B); the DAC
 * enable is the driver's), converted; nothing if no write is left. */
static void xgc_port(const uint8_t* c, Out* o, YmxList* out) {
    uint8_t cmd[1 + 32];
    int m = 0;
    for (int w = 0; w < (c[0] & 15) + 1; ++w) {
        if ((c[0] & 0xF0) == 0x20 && c[1 + 2 * w] == 0x2B) continue;
        cmd[1 + 2 * m] = c[1 + 2 * w];
        cmd[2 + 2 * m] = c[2 + 2 * w];
        ++m;
    }
    if (!m) return;
    cmd[0] = (uint8_t)((c[0] & 0xF0) | (m - 1));
    xgc_convert(cmd, 1 + 2 * m, o, out);
}

static void extract_xgc(YmXgmEncoder* e, Out* xgc) {
    const YmxList* in = &e->xgm_cmds;
    YmxYmState* old = &e->cur; /* scratch: the clean step is done with it */
    *old = e->xgc_ym;
    Out t = {e->items, 0, sizeof e->items, 0};
    YmxList* items = &e->xgc_cmds;
    items->n = 0;
    /* PSG commands first (they need the 68000 bus), then the YM commands */
    for (int i = 0; i < in->n; ++i)
        if ((e->xgm[in->start[i]] & 0xF0) == 0x10) xgc_convert(e->xgm + in->start[i], in->len[i], &t, items);
    int npo = 0, npk = 0;
    for (int i = 0; i < in->n; ++i) {
        const uint8_t* c = e->xgm + in->start[i];
        const int code = c[0] & 0xF0;
        if (code == 0x40) {
            e->pend_key[npk++] = i;
        } else if (code == 0x20 || code == 0x30) {
            if (npk) { /* the register writes before the keys, then the keys */
                for (int k = 0; k < npo; ++k) xgc_port(e->xgm + in->start[e->pend_other[k]], &t, items);
                for (int k = 0; k < npk; ++k) xgc_convert(e->xgm + in->start[e->pend_key[k]], in->len[e->pend_key[k]], &t, items);
                npo = npk = 0;
            }
            for (int w = 0; w < (c[0] & 15) + 1; ++w) ym_set(&e->xgc_ym, code == 0x30, c[1 + 2 * w], c[2 + 2 * w]);
            e->pend_other[npo++] = i;
        }
    }
    for (int k = 0; k < npo; ++k) xgc_port(e->xgm + in->start[e->pend_other[k]], &t, items);
    for (int k = 0; k < npk; ++k) xgc_convert(e->xgm + in->start[e->pend_key[k]], in->len[e->pend_key[k]], &t, items);

    /* XGC_getStateChange(current, old): D1L/RR registers ($80-$8E, index $44 + slot), then the DAC enable. */
    uint8_t pairs[2 * 26];
    int np = 0, addr = 0x44;
    for (int port = 0; port < 2; ++port)
        for (int reg = 0x80; reg < 0x90; reg += (reg & 3) == 2 ? 2 : 1, ++addr) {
            if (ym_same(&e->xgc_ym, old, port, reg)) continue;
            pairs[2 * np] = (uint8_t)addr;
            pairs[2 * np + 1] = (uint8_t)ym_get(&e->xgc_ym, port, reg);
            ++np;
        }
    if (!ym_same(&e->xgc_ym, old, 0, 0x2B)) {
        pairs[2 * np] = 0x44 + 0x1C;
        pairs[2 * np + 1] = (uint8_t)ym_get(&e->xgc_ym, 0, 0x2B);
        ++np;
    }
    for (int i = 0; i < np; i += 16) {
        const int m = np - i < 16 ? np - i : 16;
        const size_t start = t.n;
        put(&t, (0x60 | (m - 1)) << 1);
        for (int j = 0; j < 2 * m; ++j) put(&t, pairs[2 * i + j]);
        close_cmd(items, &t, start);
    }
    if (t.overflow) {
        xgc->overflow = 1;
        return;
    }
    /* A size byte, then the commands; a frame that would reach 250 bytes ends with a frame skip. */
    size_t size_at = xgc->n;
    put(xgc, 0);
    int size = 1;
    for (int i = 0; i < items->n; ++i) {
        if (size + items->len[i] >= YMX_XGC_SPLIT) {
            put(xgc, YMX_XGC_FRAME_SKIP);
            if (size_at < xgc->cap) xgc->p[size_at] = (uint8_t)(size + 1);
            size_at = xgc->n;
            put(xgc, 0);
            size = 1;
        }
        for (int j = 0; j < items->len[i]; ++j) put(xgc, e->items[items->start[i] + j]);
        size += items->len[i];
    }
    if (size_at < xgc->cap) xgc->p[size_at] = (uint8_t)size;
}

void ymx_reset(YmXgmEncoder* e) {
    ym_clear(&e->clean_ym);
    psg_clear(&e->clean_psg);
    ym_clear(&e->xgc_ym);
    e->ncleaned = 0;
}

void ymx_absorb(YmXgmEncoder* e, const YmWrite* in, int n) {
    e->clean_psg.index = e->clean_psg.type = -1;
    for (int i = 0; i < n; ++i) {
        if (in[i].kind == YM_W_PSG) psg_write(&e->clean_psg, in[i].val);
        else ym_set(&e->clean_ym, in[i].kind, in[i].reg, in[i].val);
    }
    /* The XGC state receives the deltas of the clean state, so after every frame it holds the same registers (the
     * key nibbles ym_set keeps for $28 are never compared). */
    e->xgc_ym = e->clean_ym;
}

const uint8_t* ymx_frame(YmXgmEncoder* e, const YmWrite* in, int n, int loop_at, size_t* xgm_size, uint8_t* xgc,
                         size_t xgc_cap, size_t* xgc_size) {
    clean(e, in, n, loop_at);
    Out ox = {e->xgm, 0, sizeof e->xgm, 0};
    extract_xgm(e, &ox);
    if (xgm_size) *xgm_size = ox.n;
    uint8_t none[1];
    Out oc = {xgc ? xgc : none, 0, xgc ? xgc_cap : 0, 0};
    extract_xgc(e, &oc);
    if (xgc_size) *xgc_size = oc.n;
    if (ox.overflow || e->xgm_cmds.n >= YMX_MAX_CMDS || (xgc && oc.overflow)) return NULL;
    return e->xgm;
}
