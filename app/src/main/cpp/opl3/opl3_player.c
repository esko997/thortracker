/* Thor Tracker's OPL3 player (opl3_player.h). Specification: docs/AUDIO_ENGINE.md, "OPL3: player".
 * Every rule here has a counterpart in tools/audio/opl3/opl3_ref.py; change both and the spec together. */
#include "opl3_player.h"

#include <string.h>

#include "generated/opl3_tables.h"

_Static_assert((-1 >> 1) == -1, "the vibrato offset uses an arithmetic (floor) right shift");

/* Tracker channel -> primary voice. Channels 0-5 own the 4-op pairs (v, v + 3). */
static const int CH_VOICE[O3_CHANNELS] = {0, 1, 2, 9, 10, 11, 6, 7, 8};
/* Chord pool in priority order; entries 3..8 are the secondaries of channels 0..5 (pool only in 2-op mode). */
static const int POOL_VOICE[O3P_POOL] = {15, 16, 17, 3, 4, 5, 12, 13, 14};
static const uint8_t OP_OFFSET[9] = {0x00, 0x01, 0x02, 0x08, 0x09, 0x0A, 0x10, 0x11, 0x12};
/* Carrier operators (bit k = operator k + 1): 2-op by CNT, 4-op by CNT_A * 2 + CNT_B. */
static const int CARRIERS_2OP[2] = {0x2, 0x3};
static const int CARRIERS_4OP[4] = {0x8, 0xA, 0x9, 0xD};

static int chreg(int base, int v) { return (v >= 9 ? 0x100 : 0) + base + v % 9; }
static int opreg(int base, int v, int half) { return (v >= 9 ? 0x100 : 0) + base + OP_OFFSET[v % 9] + 3 * half; }

static int is_key_reg(int reg) { return (reg & 0xFF) >= 0xB0 && (reg & 0xFF) <= 0xB8; }

static void wr(O3Player* p, int reg, int val) {
    const int old = p->shadow[reg];
    if (old == (uint8_t)val) return;
    p->shadow[reg] = (uint8_t)val;
    O3Write w = {(uint16_t)reg, (uint8_t)val};
    if (is_key_reg(reg) && (old & 0x20) && !(val & 0x20)) {
        if (p->pending_offs < O3_VOICES) p->off[p->pending_offs++] = w; /* once per voice and batch */
    } else if (p->writes < O3P_MAX_WRITES) {
        p->write[p->writes++] = w;
    }
}

static void begin_batch(O3Player* p) { p->writes = p->offs = p->pending_offs = 0; }

/* Puts the key-off phase in front of the batch. */
static void end_batch(O3Player* p) {
    const int n = p->pending_offs;
    if (p->writes + n > O3P_MAX_WRITES) p->writes = O3P_MAX_WRITES - n;
    memmove(p->write + n, p->write, sizeof p->write[0] * (size_t)p->writes);
    memcpy(p->write, p->off, sizeof p->off[0] * (size_t)n);
    p->writes += n;
    p->offs = n;
    p->pending_offs = 0;
}

static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

static const uint8_t* instrument(const Opl3Image* image, int slot) {
    if (slot < 1 || slot > image->info.instruments) return NULL;
    const uint8_t* rec = image->bytes + O3_OFF_INSTRUMENTS + O3_INSTRUMENT_SIZE * (slot - 1);
    return (rec[O3_I_FLAGS] & O3_FLAG_DEFINED) ? rec : NULL;
}

int o3p_channel_voice(int channel) { return CH_VOICE[channel]; }

/* ---- voices ---------------------------------------------------------------------------------- */

static int tl_out(const O3Channel* ch, int k) {
    const int r40 = ch->ops[k][1];
    if (!(ch->carriers >> k & 1)) return r40;
    const int tl = (r40 & 63) + 63 - (ch->vol8 >> 3);
    return (r40 & 0xC0) | (tl > 63 ? 63 : tl);
}

/* Operator k of a note on primary voice v: (voice, half). */
static void op_voice(int v, int k, int* voice, int* half) {
    *voice = k < 2 ? v : v + 3;
    *half = k & 1;
}

static void write_c0(O3Player* p, const O3Channel* ch, int v, int nops) {
    if (nops == 4) {
        wr(p, chreg(0xC0, v), ch->pan | ch->feedback << 1 | ch->alg >> 1);
        wr(p, chreg(0xC0, v + 3), ch->pan | (ch->alg & 1));
    } else {
        wr(p, chreg(0xC0, v), ch->pan | ch->feedback << 1 | (ch->alg & 1));
    }
}

/* The note's operators and connection into voice v (a chord voice: nops = 2). */
static void load_voice(O3Player* p, const O3Channel* ch, int v, int nops) {
    for (int k = 0; k < nops; ++k) {
        int voice, half;
        op_voice(v, k, &voice, &half);
        wr(p, opreg(0x20, voice, half), ch->ops[k][0]);
        wr(p, opreg(0x40, voice, half), tl_out(ch, k));
        wr(p, opreg(0x60, voice, half), ch->ops[k][2]);
        wr(p, opreg(0x80, voice, half), ch->ops[k][3]);
        wr(p, opreg(0xE0, voice, half), ch->ops[k][4]);
    }
    write_c0(p, ch, v, nops);
}

static void voice_key_off(O3Player* p, int v) { wr(p, chreg(0xB0, v), p->shadow[chreg(0xB0, v)] & ~0x20); }

static int pool_index(int v) {
    for (int i = 0; i < O3P_POOL; ++i)
        if (POOL_VOICE[i] == v) return i;
    return -1;
}

static int pool_available(const O3Player* p, int i) { return i < 3 || !p->ch[i - 3].four; }

/* Removes voice v from its owner's chord list (order kept). */
static void drop_chord_voice(O3Player* p, int owner, int v) {
    O3Channel* ch = &p->ch[owner];
    for (int i = 0; i < ch->chord_count; ++i) {
        if (ch->chord_voice[i] != v) continue;
        for (int j = i + 1; j < ch->chord_count; ++j) {
            ch->chord_voice[j - 1] = ch->chord_voice[j];
            ch->chord_interval[j - 1] = ch->chord_interval[j];
        }
        --ch->chord_count;
        return;
    }
}

static void release_chord(O3Player* p, int c) {
    O3Channel* ch = &p->ch[c];
    for (int i = 0; i < ch->chord_count; ++i) {
        const int v = ch->chord_voice[i], k = pool_index(v);
        voice_key_off(p, v);
        p->pool_owner[k] = -1;
        p->pool_stamp[k] = ++p->stamp;
    }
    ch->chord_count = 0;
}

/* A pool voice for channel c: the free one released longest ago, else the oldest allocated one (stolen). */
static int alloc_voice(O3Player* p, int c) {
    int best = -1;
    for (int i = 0; i < O3P_POOL; ++i)
        if (pool_available(p, i) && p->pool_owner[i] < 0 && (best < 0 || p->pool_stamp[i] < p->pool_stamp[best])) best = i;
    if (best < 0) {
        for (int i = 0; i < O3P_POOL; ++i)
            if (pool_available(p, i) && (best < 0 || p->pool_stamp[i] < p->pool_stamp[best])) best = i;
        const int v = POOL_VOICE[best];
        drop_chord_voice(p, p->pool_owner[best], v);
        voice_key_off(p, v);
    }
    p->pool_owner[best] = c;
    p->pool_last[best] = c;
    p->pool_stamp[best] = ++p->stamp;
    return POOL_VOICE[best];
}

/* ---- notes ----------------------------------------------------------------------------------- */

static void key_off(O3Player* p, int c) {
    O3Channel* ch = &p->ch[c];
    if (ch->keyed) {
        voice_key_off(p, CH_VOICE[c]);
        ch->keyed = 0;
    }
    release_chord(p, c);
}

void o3p_key_off(O3Player* p, int channel) {
    begin_batch(p);
    if (channel >= 0 && channel < O3_CHANNELS) key_off(p, channel);
    end_batch(p);
}

static void set_pair_mode(O3Player* p, int c, int four) {
    O3Channel* ch = &p->ch[c];
    if (c >= 6 || ch->four == four) return;
    const int k = 3 + c;
    if (four && p->pool_owner[k] >= 0) {
        const int v = POOL_VOICE[k];
        drop_chord_voice(p, p->pool_owner[k], v);
        voice_key_off(p, v);
        p->pool_owner[k] = -1;
        p->pool_stamp[k] = ++p->stamp;
    }
    ch->four = four;
    int bits = 0;
    for (int i = 0; i < 6; ++i) bits |= p->ch[i].four << i;
    wr(p, 0x104, bits);
    if (!four) p->pool_stamp[k] = ++p->stamp; /* the secondary joins the pool */
}

/* Key on `note` with the channel's instrument; `row` gives this row's locks and the chord (NULL: none). */
static void key_on(O3Player* p, const Opl3Image* image, int c, int note, const O3Row* row, const int8_t* chord, int chord_n) {
    O3Channel* ch = &p->ch[c];
    const uint8_t* rec = instrument(image, ch->instrument);
    if (!rec) return;
    key_off(p, c);
    const int four = rec[O3_I_FLAGS] & O3_FLAG_FOUR_OP;
    set_pair_mode(p, c, four);
    ch->nops = four ? 4 : 2;
    ch->alg = rec[O3_I_ALG];
    ch->feedback = rec[O3_I_FEEDBACK];
    ch->carriers = four ? CARRIERS_4OP[ch->alg] : CARRIERS_2OP[ch->alg];
    memcpy(ch->ops, rec + O3_I_OPS, sizeof ch->ops);
    for (int e = 0; row && e < row->effects; ++e) {
        const int x = row->param[e] >> 4, y = row->param[e] & 15;
        switch (row->code[e]) {
        case O3_FX_FEEDBACK: ch->feedback = row->param[e]; break;
        case O3_FX_BRIGHTNESS:
            for (int k = 0; k < ch->nops; ++k)
                if (!(ch->carriers >> k & 1)) ch->ops[k][1] = (uint8_t)((ch->ops[k][1] & 0xC0) | (63 - row->param[e]));
            break;
        case O3_FX_MULT:
            if (x <= ch->nops) ch->ops[x - 1][0] = (uint8_t)((ch->ops[x - 1][0] & 0xF0) | y);
            break;
        case O3_FX_WAVE:
            for (int k = 0; k < ch->nops; ++k)
                if (x == 0 || x == k + 1) ch->ops[k][4] = (uint8_t)y;
            break;
        }
    }
    load_voice(p, ch, CH_VOICE[c], ch->nops);
    ch->base = ch->target = note * 64;
    ch->slide = 0;
    ch->arp_step = 0;
    ch->vib_phase = 0;
    const int speed = rec[O3_I_SWEEP_SPEED];
    ch->sweep = speed ? (int8_t)rec[O3_I_SWEEP_START] * 64 : 0;
    ch->sweep_end = speed ? (int8_t)rec[O3_I_SWEEP_END] * 64 : 0;
    ch->sweep_step = speed * 4;
    ch->keyed = 1;
    ch->active = 1;
    if (!four) {
        for (int i = 0; i < chord_n; ++i) {
            const int v = alloc_voice(p, c);
            ch->chord_voice[ch->chord_count] = v;
            ch->chord_interval[ch->chord_count] = chord[i];
            ++ch->chord_count;
            load_voice(p, ch, v, 2);
        }
    }
}

static void write_pan(O3Player* p, int c) {
    O3Channel* ch = &p->ch[c];
    if (!ch->active) return;
    write_c0(p, ch, CH_VOICE[c], ch->nops);
    for (int i = 0; i < ch->chord_count; ++i) write_c0(p, ch, ch->chord_voice[i], 2);
}

static void process_row(O3Player* p, const Opl3Image* image, int c) {
    O3Channel* ch = &p->ch[c];
    const O3Row* row = &ch->row;
    const int has_note = row->note >= 0 && row->note < O3_NOTES;
    int8_t chord[O3P_MAX_CHORD];
    int chord_n = 0;
    if (has_note) {
        ch->arp = 0;
        ch->fine = 0;
    }
    if (row->instrument) ch->instrument = row->instrument;
    for (int e = 0; e < row->effects; ++e) {
        const int v = row->param[e], x = v >> 4, y = v & 15;
        switch (row->code[e]) {
        case O3_FX_ARPEGGIO: ch->arp = v; ch->arp_step = 0; break;
        case O3_FX_SLIDE_UP: ch->slide_mode = v ? 1 : 0; ch->slide_speed = v; break;
        case O3_FX_SLIDE_DOWN: ch->slide_mode = v ? 2 : 0; ch->slide_speed = v; break;
        case O3_FX_PORTAMENTO: ch->slide_mode = v ? 3 : 0; ch->slide_speed = v; break;
        case O3_FX_VIBRATO: ch->vib_speed = x; ch->vib_depth = y; break;
        case O3_FX_PAN: ch->pan = (x ? 0x10 : 0) | (y ? 0x20 : 0); write_pan(p, c); break;
        case O3_FX_VOLUME_SLIDE: ch->volslide = v; break;
        case O3_FX_VOLUME: ch->vol8 = v * 8; break;
        case O3_FX_CHORD:
            chord_n = 0;
            if (x) chord[chord_n++] = (int8_t)x;
            if (y) chord[chord_n++] = (int8_t)y;
            break;
        case O3_FX_SHAPE:
            chord_n = 0;
            if (v) {
                const int8_t* s = (const int8_t*)(image->bytes + O3_OFF_SHAPES + 4 * (v - 1));
                for (int i = 0; i < 4 && s[i]; ++i) chord[chord_n++] = s[i];
            }
            break;
        case O3_FX_FINE: ch->fine = v - 0x80; break;
        case O3_FX_CUT: ch->cut = v; break;
        }
    }
    if (row->note == O3_NOTE_OFF) {
        key_off(p, c);
    } else if (has_note) {
        if (ch->slide_mode == 3 && ch->keyed) ch->target = row->note * 64;
        else key_on(p, image, c, row->note, row, chord, chord_n);
    }
}

/* ---- per tick -------------------------------------------------------------------------------- */

static int pitch(const O3Channel* ch) {
    int arp = 0;
    if (ch->arp && ch->arp_step) arp = ch->arp_step == 1 ? ch->arp >> 4 : ch->arp & 15;
    const int vib = ch->vib_depth ? (O3_SINE[ch->vib_phase] * ch->vib_depth) >> 4 : 0;
    return clamp(ch->base + arp * 64 + ch->fine + ch->slide + vib + ch->sweep, 0, O3P_PITCH_MAX);
}

static void write_frequency(O3Player* p, int v, int units, int keyed) {
    const int oct = units / 768, frac = units % 768;
    int fnum = O3_FNUM[frac], block = oct - (frac < O3_FNUM_SPLIT ? 1 : 0);
    if (block < 0) {
        block = 0;
        fnum >>= 1;
    }
    wr(p, chreg(0xA0, v), fnum & 0xFF);
    wr(p, chreg(0xB0, v), (keyed ? 0x20 : 0) | block << 2 | fnum >> 8);
}

static void write_levels(O3Player* p, const O3Channel* ch, int v, int nops) {
    for (int k = 0; k < nops; ++k) {
        if (!(ch->carriers >> k & 1)) continue;
        int voice, half;
        op_voice(v, k, &voice, &half);
        wr(p, opreg(0x40, voice, half), tl_out(ch, k));
    }
}

static void update(O3Player* p, int c) {
    O3Channel* ch = &p->ch[c];
    if (!ch->active) return;
    const int units = pitch(ch);
    write_levels(p, ch, CH_VOICE[c], ch->nops);
    write_frequency(p, CH_VOICE[c], units, ch->keyed);
    for (int i = 0; i < ch->chord_count; ++i) {
        write_levels(p, ch, ch->chord_voice[i], 2);
        write_frequency(p, ch->chord_voice[i], clamp(units + ch->chord_interval[i] * 64, 0, O3P_PITCH_MAX), 1);
    }
    if (ch->arp) ch->arp_step = (ch->arp_step + 1) % 3;
    if (ch->slide_mode == 1) ch->slide = clamp(ch->slide + ch->slide_speed, -6144, 6144);
    if (ch->slide_mode == 2) ch->slide = clamp(ch->slide - ch->slide_speed, -6144, 6144);
    if (ch->slide_mode == 3) {
        if (ch->base < ch->target) ch->base = clamp(ch->base + ch->slide_speed, 0, ch->target);
        else if (ch->base > ch->target) ch->base = clamp(ch->base - ch->slide_speed, ch->target, O3P_PITCH_MAX);
    }
    if (ch->vib_depth && ch->vib_speed) ch->vib_phase = (ch->vib_phase + ch->vib_speed) & 63;
    if (ch->sweep_step) {
        if (ch->sweep < ch->sweep_end) ch->sweep = clamp(ch->sweep + ch->sweep_step, ch->sweep, ch->sweep_end);
        else if (ch->sweep > ch->sweep_end) ch->sweep = clamp(ch->sweep - ch->sweep_step, ch->sweep_end, ch->sweep);
    }
    if (ch->volslide) ch->vol8 = clamp(ch->vol8 + (ch->volslide >> 4) - (ch->volslide & 15), 0, 504);
}

static void read_rows(O3Player* p, const Opl3Image* image) {
    if (p->cursor_order != p->order || p->cursor_row != p->row) {
        for (int c = 0; c < O3_CHANNELS; ++c) {
            O3Cursor cur = {o3_u16(image->bytes + O3_OFF_ORDERS + 2 * (O3_CHANNELS * p->order + c)), 0};
            O3Row skip;
            for (int r = 0; r < p->row; ++r) o3_next_row(image->bytes, &cur, &skip);
            p->cursor[c] = cur;
        }
    }
    for (int c = 0; c < O3_CHANNELS; ++c) o3_next_row(image->bytes, &p->cursor[c], &p->ch[c].row);
    p->cursor_order = p->order;
    p->cursor_row = p->row + 1;
}

/* B/D of the current row (rows already read) -> p->jump. */
static void derive_jump(O3Player* p, const Opl3Image* image) {
    int has_b = 0, has_d = 0, b = 0, d = 0;
    for (int c = 0; c < O3_CHANNELS; ++c) {
        const O3Row* row = &p->ch[c].row;
        for (int e = 0; e < row->effects; ++e) {
            if (row->code[e] == O3_FX_JUMP) has_b = 1, b = row->param[e];
            if (row->code[e] == O3_FX_BREAK) has_d = 1, d = row->param[e];
        }
    }
    p->jump = has_b || has_d;
    p->jump_order = has_b ? b : p->order + 1 < image->info.orders ? p->order + 1 : image->info.loop;
    p->jump_row = has_d ? d : 0;
}

static int row_delay(const O3Row* row) {
    int g = 0;
    for (int e = 0; e < row->effects; ++e)
        if (row->code[e] == O3_FX_DELAY) g = row->param[e];
    return g;
}

static void row_start(O3Player* p, const Opl3Image* image) {
    read_rows(p, image);
    for (int c = 0; c < O3_CHANNELS; ++c) {
        const O3Row* row = &p->ch[c].row;
        for (int e = 0; e < row->effects; ++e)
            if (row->code[e] == O3_FX_SPEED) p->speed = row->param[e];
    }
    derive_jump(p, image);
    for (int c = 0; c < O3_CHANNELS; ++c) {
        O3Channel* ch = &p->ch[c];
        ch->cut = -1;
        ch->pending = 0;
        const int g = row_delay(&ch->row);
        if (g) {
            ch->pending = 1;
            ch->delay = g;
        } else {
            process_row(p, image, c);
        }
    }
}

/* Next row. Every arrival at the loop order's row 0 (the wrap after the last order, a jump, the first pass)
 * starts it at the speed sequential straight play reaches it with, so every pass of the loop plays alike and
 * the lowering needs no Fxx there (a row-0 Fxx still applies on top, at the row start). */
static void advance(O3Player* p, const Opl3Image* image) {
    if (p->jump) {
        p->order = p->jump_order;
        p->row = p->jump_row < image->info.rows[p->order] ? p->jump_row : image->info.rows[p->order] - 1;
        p->jump = 0;
    } else if (++p->row >= image->info.rows[p->order]) {
        p->row = 0;
        if (++p->order >= image->info.orders) p->order = image->info.loop;
    }
    if (p->order == image->info.loop && p->row == 0) p->speed = image->info.start_speed[p->order];
}

void o3p_tick(O3Player* p, const Opl3Image* image) {
    begin_batch(p);
    wr(p, 0xBD, image->bytes[O3_H_DEPTH]);
    if (!p->audition) {
        if (p->tick == 0) {
            row_start(p, image);
        } else {
            for (int c = 0; c < O3_CHANNELS; ++c) {
                if (p->ch[c].pending && p->ch[c].delay == p->tick) {
                    p->ch[c].pending = 0;
                    process_row(p, image, c);
                }
            }
        }
        for (int c = 0; c < O3_CHANNELS; ++c)
            if (p->ch[c].cut == p->tick) key_off(p, c);
    }
    for (int c = 0; c < O3_CHANNELS; ++c) update(p, c);
    if (!p->audition && ++p->tick >= p->speed) {
        p->tick = 0;
        advance(p, image);
    }
    end_batch(p);
}

void o3p_reset(O3Player* p, const Opl3Image* image) {
    memset(p, 0, sizeof *p);
    for (int c = 0; c < O3_CHANNELS; ++c) {
        p->ch[c].cut = -1;
        p->ch[c].vol8 = 504;
        p->ch[c].pan = 0x30;
    }
    for (int i = 0; i < O3P_POOL; ++i) p->pool_owner[i] = p->pool_last[i] = -1;
    p->cursor_order = p->cursor_row = -1;
    p->speed = image->info.speed;
    wr(p, 0x105, 0x01);
    /* The chip latches a voice's output enables only when its C0 is written, and they reset on: write C0 = both
     * outputs on every voice, so the register (and the diffing shadow) agrees with what the chip outputs. */
    for (int v = 0; v < O3_VOICES; ++v) wr(p, chreg(0xC0, v), 0x30);
    wr(p, 0xBD, image->bytes[O3_H_DEPTH]);
    end_batch(p);
}

void o3p_place(O3Player* p, const Opl3Image* image, int order) {
    o3p_reset(p, image);
    p->order = order;
    p->speed = image->info.start_speed[order];
}

/* Ticks per row straight play has at (order, row): the order's start speed and the Fxx of the rows before
 * it (and of the row itself once it has started). */
static int speed_at(const Opl3Image* image, int order, int row, int started) {
    int s = image->info.start_speed[order];
    O3Cursor cur[O3_CHANNELS];
    for (int c = 0; c < O3_CHANNELS; ++c) cur[c] = (O3Cursor){o3_u16(image->bytes + O3_OFF_ORDERS + 2 * (O3_CHANNELS * order + c)), 0};
    const int last = started ? row : row - 1;
    for (int r = 0; r <= last; ++r) {
        for (int c = 0; c < O3_CHANNELS; ++c) {
            O3Row x;
            o3_next_row(image->bytes, &cur[c], &x);
            for (int e = 0; e < x.effects; ++e)
                if (x.code[e] == O3_FX_SPEED) s = x.param[e];
        }
    }
    return s;
}

void o3p_relocate(O3Player* p, const Opl3Image* old, const Opl3Image* next) {
    if (p->audition) return;
    const O3Info* info = &next->info;
    const int started = p->tick > 0;
    const int old_speed = speed_at(old, p->order, p->row, started);
    int clamped = 0;
    if (p->order >= info->orders) {
        p->order = info->orders - 1;
        p->row = info->rows[p->order] - 1;
        clamped = 1;
    } else if (p->row >= info->rows[p->order]) {
        p->row = info->rows[p->order] - 1;
        clamped = 1;
    }
    p->cursor_order = p->cursor_row = -1;
    if (clamped) {
        p->jump = 0;
        for (int c = 0; c < O3_CHANNELS; ++c) p->ch[c].pending = 0;
        p->speed = speed_at(next, p->order, p->row, 1);
        if (!started) advance(p, next); /* the clamped row counts as played: the next row follows it */
        return;
    }
    const int new_speed = speed_at(next, p->order, p->row, started);
    if (new_speed != old_speed) p->speed = new_speed;
    if (!started) return;
    /* The row has started: its jump and any still-delayed rows come from the new data. */
    for (int c = 0; c < O3_CHANNELS; ++c) {
        O3Row row;
        o3_read_row(next, p->order, c, p->row, &row, NULL);
        O3Channel* ch = &p->ch[c];
        if (ch->pending) {
            ch->row = row;
            const int g = row_delay(&row);
            ch->pending = g >= p->tick;
            ch->delay = g;
        } else {
            ch->row = row;
        }
    }
    derive_jump(p, next);
}

int o3p_audition(O3Player* p, const Opl3Image* image, int channel, int note, int slot, int fine) {
    if (channel < 0 || channel >= O3_CHANNELS || note < 0 || note >= O3_NOTES || fine < -128 || fine > 127) return -1;
    const uint8_t* rec = instrument(image, slot);
    if (!rec || (channel >= 6 && (rec[O3_I_FLAGS] & O3_FLAG_FOUR_OP))) return -1;
    o3p_reset(p, image);
    p->audition = 1;
    p->ch[channel].instrument = slot;
    key_on(p, image, channel, note, NULL, NULL, 0);
    p->ch[channel].fine = fine;
    end_batch(p);
    return 0;
}

int o3p_voice_channel(const O3Player* p, int voice) {
    for (int c = 0; c < O3_CHANNELS; ++c) {
        if (CH_VOICE[c] == voice) return c;
        if (c < 6 && CH_VOICE[c] + 3 == voice && p->ch[c].four) return c;
    }
    const int k = pool_index(voice);
    if (k < 0) return -1;
    return p->pool_owner[k] >= 0 ? p->pool_owner[k] : p->pool_last[k];
}

int o3p_carrier_slots(const O3Player* p, int channel, int* slots) {
    const O3Channel* ch = &p->ch[channel];
    int n = 0;
    for (int k = 0; k < ch->nops; ++k) {
        if (!(ch->carriers >> k & 1)) continue;
        int voice, half;
        op_voice(CH_VOICE[channel], k, &voice, &half);
        slots[n++] = voice * 2 + half;
    }
    return n;
}
