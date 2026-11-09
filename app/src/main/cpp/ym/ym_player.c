/* Thor Tracker's YM2612 player (ym_player.h). Specification: docs/AUDIO_ENGINE.md, "YM2612: player".
 * Every rule here has a counterpart in tools/audio/ym/ym_ref.py; change both and the spec together. */
#include "ym_player.h"

#include <string.h>

#include "generated/ym_tables.h"

_Static_assert((-1 >> 1) == -1, "the vibrato offset uses an arithmetic (floor) right shift");

const uint8_t YMP_OP_OFFSET[4] = {0, 8, 4, 12};
/* Carrier operators (bit k = operator k + 1) per algorithm. */
static const uint8_t CARRIERS[8] = {0x8, 0x8, 0x8, 0x8, 0xA, 0xE, 0xE, 0xF};
static const uint8_t OP_REG[YM_OP_BYTES] = {0x30, 0x40, 0x50, 0x60, 0x70, 0x80, 0x90};

static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

static int fm_port(int c) { return c / 3; }
static int fm_reg(int base, int c) { return base + c % 3; }
static int key_select(int c) { return (c / 3) * 4 + c % 3; }

/* ---- writes ---------------------------------------------------------------------------------- */

static void emit(YmPlayer* p, int kind, int reg, int val) {
    if (p->writes < YMP_MAX_WRITES) p->write[p->writes++] = (YmWrite){(uint8_t)kind, (uint8_t)reg, (uint8_t)val};
}

static void wr(YmPlayer* p, int port, int reg, int val) {
    if (p->ym[port][reg] == (uint8_t)val) return;
    p->ym[port][reg] = (uint8_t)val;
    emit(p, port, reg, val);
}

static void key_write(YmPlayer* p, int c, int on) {
    const YmWrite w = {YM_W_PORT0, 0x28, (uint8_t)((on ? 0xF0 : 0x00) | key_select(c))};
    if (on) {
        emit(p, w.kind, w.reg, w.val);
    } else if (p->pending_offs < (int)(sizeof p->off / sizeof p->off[0])) {
        p->off[p->pending_offs++] = w;
    }
}

/* $A4 then $A0, both when either differs (the chip latches $A4 until $A0 is written). */
static void wr_pair(YmPlayer* p, int port, int hi_reg, int hi, int lo) {
    if (p->ym[port][hi_reg] == (uint8_t)hi && p->ym[port][hi_reg - 4] == (uint8_t)lo) return;
    p->ym[port][hi_reg] = (uint8_t)hi;
    p->ym[port][hi_reg - 4] = (uint8_t)lo;
    emit(p, port, hi_reg, hi);
    emit(p, port, hi_reg - 4, lo);
}

static void psg_tone(YmPlayer* p, int i, int n) {
    const int old = p->tone[i];
    if (old == n) return;
    p->tone[i] = (uint16_t)n;
    emit(p, YM_W_PSG, 0, 0x80 | i << 5 | (n & 15));
    if ((old >> 4) != (n >> 4)) emit(p, YM_W_PSG, 0, (n >> 4) & 0x3F);
}

static void psg_att(YmPlayer* p, int i, int a) {
    if (p->att[i] == a) return;
    p->att[i] = (uint8_t)a;
    emit(p, YM_W_PSG, 0, 0x90 | i << 5 | a);
}

static void psg_noise(YmPlayer* p, int mode) {
    if (p->noise_ctrl == mode) return;
    p->noise_ctrl = (uint8_t)mode;
    emit(p, YM_W_PSG, 0, 0xE0 | mode);
}

static void begin_batch(YmPlayer* p) {
    p->writes = p->offs = p->pending_offs = 0;
    for (int k = 0; k < YM_PCM_CHANNELS; ++k) p->pcm[k] = YMP_PCM_NONE;
}

/* Puts the key-off phase in front of the batch. */
static void end_batch(YmPlayer* p) {
    const int n = p->pending_offs;
    if (p->writes + n > YMP_MAX_WRITES) p->writes = YMP_MAX_WRITES - n;
    memmove(p->write + n, p->write, sizeof p->write[0] * (size_t)p->writes);
    memcpy(p->write, p->off, sizeof p->off[0] * (size_t)n);
    p->writes += n;
    p->offs = n;
    p->pending_offs = 0;
}

/* ---- pitch ----------------------------------------------------------------------------------- */

static void fm_frequency(int region, int units, int* a4, int* a0) {
    const int oct = units / 768, frac = units % 768;
    int fnum = YM_FNUM[region][frac], block = oct - (frac < YM_FNUM_SPLIT[region] ? 1 : 0);
    if (block < 0) {
        block = 0;
        fnum >>= 1;
    }
    *a4 = block << 3 | fnum >> 8;
    *a0 = fnum & 0xFF;
}

static int psg_period(int region, int units) {
    const int oct = units / 768, frac = units % 768;
    const int n = (int)((YM_PSG_DIV[region][frac] + (128u << oct)) >> (8 + oct));
    return clamp(n, 1, 1023);
}

/* `extra`: the PSG envelopes' arpeggio and pitch offset (pitch units). */
static int pitch(const YmChannel* ch, int extra) {
    int arp = 0;
    if (ch->arp && ch->arp_step) arp = ch->arp_step == 1 ? ch->arp >> 4 : ch->arp & 15;
    const int vib = ch->vib_depth ? (YM_SINE[ch->vib_phase] * ch->vib_depth) >> 4 : 0;
    return clamp(ch->base + arp * 64 + ch->fine + ch->slide + vib + ch->sweep + extra, 0, YMP_PITCH_MAX);
}

/* ---- notes ----------------------------------------------------------------------------------- */

static int tl_out(const YmChannel* ch, int k) {
    const int tl = ch->ops[k][1];
    if (!(ch->carriers >> k & 1)) return tl;
    const int v = tl + 127 - (ch->vol8 >> 3);
    return v > 127 ? 127 : v;
}

static void key_off(YmPlayer* p, int c) {
    YmChannel* ch = &p->ch[c];
    if (!ch->keyed) return;
    ch->keyed = 0;
    if (c >= YM_CH_PCM) {
        ch->pcm = 0;
        p->pcm[c - YM_CH_PCM] = YMP_PCM_STOP;
    } else if (c < YM_FM_CHANNELS) {
        ch->key_pending = 0;
        key_write(p, c, 0);
    } else {
        const YmEnvelope* vol = &p->env[c - YM_CH_PSG][YM_ENV_VOL];
        if (vol->length && vol->release >= 0) {
            ch->pos[YM_ENV_VOL] = vol->release;
            ch->phase = YM_PSG_RELEASED;
        } else {
            ch->phase = YM_PSG_OFF;
        }
    }
}

void ymp_key_off(YmPlayer* p, int channel) {
    begin_batch(p);
    if (channel >= 0 && channel < YM_CHANNELS) key_off(p, channel);
    end_batch(p);
}

static void start_pitch(YmChannel* ch, const uint8_t* rec, int note) {
    ch->base = ch->target = note * 64;
    ch->slide = 0;
    ch->arp_step = 0;
    ch->vib_phase = 0;
    const int speed = rec[YM_I_SWEEP_SPEED];
    ch->sweep = speed ? (int8_t)rec[YM_I_SWEEP_START] * 64 : 0;
    ch->sweep_end = speed ? (int8_t)rec[YM_I_SWEEP_END] * 64 : 0;
    ch->sweep_step = speed * 4;
}

/* Key on `note` with the channel's instrument; `row` gives this row's locks (NULL: none). A missing instrument (or an
 * unmapped PCM note) does nothing: the sounding note goes on. A PCM note starts its variant on the channel's XGM2
 * channel, which replaces whatever it played. */
static void key_on(YmPlayer* p, const YmImage* image, int c, int note, const YmRow* row) {
    YmChannel* ch = &p->ch[c];
    const int kind = ym_channel_kind(c), psg = kind == YM_KIND_PSG;
    const uint8_t* rec = ym_instrument(image, ch->instrument, kind);
    if (!rec) return;
    if (kind == YM_KIND_PCM) {
        const int v = ym_pcm_variant(image, ch->instrument, note);
        if (!v) return;
        ch->pcm = v;
        p->pcm[c - YM_CH_PCM] = v;
        ch->keyed = 1;
        ch->active = 1;
        return;
    }
    key_off(p, c);
    if (!psg) {
        ch->alg = rec[YM_I_ALG];
        ch->feedback = rec[YM_I_FEEDBACK];
        ch->ams_fms = rec[YM_I_AMS_FMS];
        ch->carriers = CARRIERS[ch->alg];
        memcpy(ch->ops, rec + YM_I_OPS, sizeof ch->ops);
        for (int k = 0; k < 4; ++k) ch->ch3[k] = ym_ch3_offset(image, ch->instrument, k);
        for (int e = 0; row && e < row->effects; ++e) {
            const int v = row->param[e], x = v >> 4, y = v & 15;
            switch (row->code[e]) {
            case YM_FX_FEEDBACK: ch->feedback = v; break;
            case YM_FX_BRIGHTNESS:
                for (int k = 0; k < 4; ++k)
                    if (!(ch->carriers >> k & 1)) ch->ops[k][1] = (uint8_t)(0x7F - v);
                break;
            case YM_FX_MULT: ch->ops[x - 1][0] = (uint8_t)((ch->ops[x - 1][0] & 0x70) | y); break;
            }
        }
        const int port = fm_port(c);
        for (int k = 0; k < 4; ++k)
            for (int r = 0; r < YM_OP_BYTES; ++r)
                wr(p, port, fm_reg(OP_REG[r], c) + YMP_OP_OFFSET[k], r == 1 ? tl_out(ch, k) : ch->ops[k][r]);
        wr(p, port, fm_reg(0xB0, c), ch->feedback << 3 | ch->alg);
        wr(p, port, fm_reg(0xB4, c), ch->pan | ch->ams_fms);
        ch->key_pending = 1;
    } else {
        for (int k = 0; k < 3; ++k) {
            YmEnvelope* e = &p->env[c - YM_CH_PSG][k];
            const int off = ym_u16(rec + YM_I_ENV_VOLUME + 2 * k);
            e->length = 0;
            if (off != YM_NO_ENVELOPE) {
                const uint8_t* b = image->bytes + YM_OFF_DATA + off;
                const int loop = ym_u16(b + 2), release = ym_u16(b + 4);
                e->length = ym_u16(b);
                e->loop = loop == YM_NO_ENVELOPE ? -1 : loop;
                e->release = release == YM_NO_ENVELOPE ? -1 : release;
                memcpy(e->value, b + YM_ENV_HEADER, (size_t)e->length);
            }
            ch->pos[k] = 0;
        }
        ch->pitch_relative = rec[YM_I_PSG_FLAGS] & YM_PSG_PITCH_RELATIVE;
        ch->pitch_acc = 0;
        ch->phase = YM_PSG_ON;
        if (c == YM_CH_NOISE) {
            ch->noise = rec[YM_I_PSG_NOISE];
            for (int e = 0; row && e < row->effects; ++e)
                if (row->code[e] == YM_FX_NOISE) ch->noise = (row->param[e] >> 4) << 2 | (row->param[e] & 3);
        }
    }
    start_pitch(ch, rec, note);
    ch->keyed = 1;
    ch->active = 1;
}

static void write_pan(YmPlayer* p, int c) {
    YmChannel* ch = &p->ch[c];
    if (!ch->active) return;
    wr(p, fm_port(c), fm_reg(0xB4, c), ch->pan | ch->ams_fms);
}

static void process_row(YmPlayer* p, const YmImage* image, int c) {
    YmChannel* ch = &p->ch[c];
    const YmRow* row = &ch->row;
    const int has_note = row->note >= 0 && row->note < YM_NOTES;
    if (has_note) {
        ch->arp = 0;
        ch->fine = 0;
    }
    if (row->instrument) ch->instrument = row->instrument;
    for (int e = 0; e < row->effects; ++e) {
        const int v = row->param[e], x = v >> 4, y = v & 15;
        switch (row->code[e]) {
        case YM_FX_ARPEGGIO: ch->arp = v; ch->arp_step = 0; break;
        case YM_FX_SLIDE_UP: ch->slide_mode = v ? 1 : 0; ch->slide_speed = v; break;
        case YM_FX_SLIDE_DOWN: ch->slide_mode = v ? 2 : 0; ch->slide_speed = v; break;
        case YM_FX_PORTAMENTO: ch->slide_mode = v ? 3 : 0; ch->slide_speed = v; break;
        case YM_FX_VIBRATO: ch->vib_speed = x; ch->vib_depth = y; break;
        case YM_FX_PAN: ch->pan = (x ? 0x80 : 0) | (y ? 0x40 : 0); write_pan(p, c); break;
        case YM_FX_VOLUME_SLIDE: ch->volslide = v; break;
        case YM_FX_VOLUME: ch->vol8 = v * 8; break;
        case YM_FX_LFO: p->lfo = v; wr(p, 0, 0x22, v); break;
        case YM_FX_FINE: ch->fine = v - 0x80; break;
        case YM_FX_CUT: ch->cut = v; break;
        }
    }
    if (row->note == YM_NOTE_OFF) {
        key_off(p, c);
    } else if (has_note) {
        if (ch->slide_mode == 3 && ch->keyed) ch->target = row->note * 64;
        else key_on(p, image, c, row->note, row);
    }
}

/* ---- per tick -------------------------------------------------------------------------------- */

int ymp_noise_owns_tone3(const YmPlayer* p) {
    const YmChannel* n = &p->ch[YM_CH_NOISE];
    return n->active && (n->noise & 3) == 3 && n->phase != YM_PSG_OFF;
}

static int attenuation(const YmChannel* ch) { return 15 - (ch->level * (ch->vol8 >> 3) + 7) / 15; }

static void advance_effects(YmChannel* ch, int vol_max) {
    if (ch->arp) ch->arp_step = (ch->arp_step + 1) % 3;
    if (ch->slide_mode == 1) ch->slide = clamp(ch->slide + ch->slide_speed, -6144, 6144);
    if (ch->slide_mode == 2) ch->slide = clamp(ch->slide - ch->slide_speed, -6144, 6144);
    if (ch->slide_mode == 3) {
        if (ch->base < ch->target) ch->base = clamp(ch->base + ch->slide_speed, 0, ch->target);
        else if (ch->base > ch->target) ch->base = clamp(ch->base - ch->slide_speed, ch->target, YMP_PITCH_MAX);
    }
    if (ch->vib_depth && ch->vib_speed) ch->vib_phase = (ch->vib_phase + ch->vib_speed) & 63;
    if (ch->sweep_step) {
        if (ch->sweep < ch->sweep_end) ch->sweep = clamp(ch->sweep + ch->sweep_step, ch->sweep, ch->sweep_end);
        else if (ch->sweep > ch->sweep_end) ch->sweep = clamp(ch->sweep - ch->sweep_step, ch->sweep_end, ch->sweep);
    }
    if (ch->volslide) ch->vol8 = clamp(ch->vol8 + (ch->volslide >> 4) - (ch->volslide & 15), 0, vol_max);
}

/* The position after `pos`: a held volume note loops before its release point; a released one plays its tail and
 * holds the last value; others loop at the end, or hold the last value without a loop. */
static int envelope_next(const YmEnvelope* e, int pos, int released) {
    const int n = pos + 1;
    if (!released && e->release >= 0 && n == e->release) return e->loop;
    if (n < e->length) return n;
    return e->loop >= 0 && e->release < 0 ? e->loop : e->length - 1;
}

static void advance_envelopes(YmPlayer* p, int c) {
    YmChannel* ch = &p->ch[c];
    const YmEnvelope* env = p->env[c - YM_CH_PSG];
    const int released = ch->phase == YM_PSG_RELEASED;
    for (int k = 0; k < 3; ++k)
        if (env[k].length) ch->pos[k] = envelope_next(&env[k], ch->pos[k], released);
    const YmEnvelope* vol = &env[YM_ENV_VOL];
    if (released && ch->pos[YM_ENV_VOL] == vol->length - 1 && vol->value[vol->length - 1] == 0) ch->phase = YM_PSG_OFF;
}

static void update_fm(YmPlayer* p, int c) {
    YmChannel* ch = &p->ch[c];
    const int port = fm_port(c);
    for (int k = 0; k < 4; ++k)
        if (ch->carriers >> k & 1) wr(p, port, fm_reg(0x40, c) + YMP_OP_OFFSET[k], tl_out(ch, k));
    int a4, a0;
    const int units = pitch(ch, 0);
    if (c == YM_CH_FM3 && p->fm3 == YM_FM3_SPECIAL) {
        /* Operators 1-3 on their own registers ($AD/$A9, $AE/$AA, $AC/$A8 as ymfm and the chip pair them), operator 4
         * on the channel's; each at the note's pitch plus its offset. */
        static const uint8_t HI[3] = {0xAD, 0xAE, 0xAC};
        for (int k = 0; k < 3; ++k) {
            fm_frequency(p->region, clamp(units + ch->ch3[k], 0, YMP_PITCH_MAX), &a4, &a0);
            wr_pair(p, 0, HI[k], a4, a0);
        }
        fm_frequency(p->region, clamp(units + ch->ch3[3], 0, YMP_PITCH_MAX), &a4, &a0);
    } else {
        fm_frequency(p->region, units, &a4, &a0);
    }
    wr_pair(p, port, fm_reg(0xA4, c), a4, a0);
    if (ch->key_pending) {
        ch->key_pending = 0;
        key_write(p, c, 1);
    }
    advance_effects(ch, 1016);
}

static void update_psg(YmPlayer* p, int c) {
    YmChannel* ch = &p->ch[c];
    const YmEnvelope* env = p->env[c - YM_CH_PSG];
    const YmEnvelope* vol = &env[YM_ENV_VOL];
    ch->level = ch->phase == YM_PSG_OFF ? 0 : vol->length ? vol->value[ch->pos[YM_ENV_VOL]] : 15;
    int extra = env[YM_ENV_ARP].length ? 64 * env[YM_ENV_ARP].value[ch->pos[YM_ENV_ARP]] : 0;
    const int bend = env[YM_ENV_PITCH].length ? YM_PITCH_UNIT * env[YM_ENV_PITCH].value[ch->pos[YM_ENV_PITCH]] : 0;
    if (ch->pitch_relative) {
        ch->pitch_acc = clamp(ch->pitch_acc + bend, -6144, 6144);
        extra += ch->pitch_acc;
    } else {
        extra += bend;
    }
    const int units = pitch(ch, extra);
    if (c == YM_CH_NOISE) {
        psg_noise(p, ch->noise);
        if ((ch->noise & 3) == 3) psg_tone(p, 2, psg_period(p->region, units + 48 * 64));
        psg_att(p, 3, attenuation(ch));
    } else if (c == YM_CH_PSG + 2 && ymp_noise_owns_tone3(p)) {
        psg_att(p, 2, 15);
    } else {
        psg_tone(p, c - YM_CH_PSG, psg_period(p->region, units));
        psg_att(p, c - YM_CH_PSG, attenuation(ch));
    }
    advance_effects(ch, 120);
    advance_envelopes(p, c);
}

static void read_rows(YmPlayer* p, const YmImage* image) {
    if (p->cursor_order != p->order || p->cursor_row != p->row) {
        for (int c = 0; c < YM_CHANNELS; ++c) {
            YmCursor cur = ym_pattern(image, p->order, c);
            YmRow skip;
            for (int r = 0; r < p->row; ++r) ym_next_row(image->bytes, &cur, &skip);
            p->cursor[c] = cur;
        }
    }
    for (int c = 0; c < YM_CHANNELS; ++c) ym_next_row(image->bytes, &p->cursor[c], &p->ch[c].row);
    p->cursor_order = p->order;
    p->cursor_row = p->row + 1;
}

/* B/D of the current row (rows already read) -> p->jump. */
static void derive_jump(YmPlayer* p, const YmImage* image) {
    int has_b = 0, has_d = 0, b = 0, d = 0;
    for (int c = 0; c < YM_CHANNELS; ++c) {
        const YmRow* row = &p->ch[c].row;
        for (int e = 0; e < row->effects; ++e) {
            if (row->code[e] == YM_FX_JUMP) has_b = 1, b = row->param[e];
            if (row->code[e] == YM_FX_BREAK) has_d = 1, d = row->param[e];
        }
    }
    p->jump = has_b || has_d;
    p->jump_order = has_b ? b : p->order + 1 < image->info.orders ? p->order + 1 : image->info.loop;
    p->jump_row = has_d ? d : 0;
}

static int row_delay(const YmRow* row) {
    int g = 0;
    for (int e = 0; e < row->effects; ++e)
        if (row->code[e] == YM_FX_DELAY) g = row->param[e];
    return g;
}

static void row_start(YmPlayer* p, const YmImage* image) {
    read_rows(p, image);
    for (int c = 0; c < YM_CHANNELS; ++c) {
        const YmRow* row = &p->ch[c].row;
        for (int e = 0; e < row->effects; ++e)
            if (row->code[e] == YM_FX_SPEED) p->speed = row->param[e];
    }
    derive_jump(p, image);
    for (int c = 0; c < YM_CHANNELS; ++c) {
        YmChannel* ch = &p->ch[c];
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

/* Next row. Every arrival at the loop order's row 0 (the wrap, a jump, the first pass) starts it at the speed
 * sequential straight play reaches it with (a row-0 Fxx still applies on top, at the row start). */
static void advance(YmPlayer* p, const YmImage* image) {
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

void ymp_tick(YmPlayer* p, const YmImage* image) {
    begin_batch(p);
    wr(p, 0, 0x22, p->lfo);
    wr(p, 0, 0x27, p->fm3 == YM_FM3_SPECIAL ? 0x40 : 0x00);
    if (!p->audition) {
        if (p->tick == 0) {
            row_start(p, image);
        } else {
            for (int c = 0; c < YM_CHANNELS; ++c) {
                if (p->ch[c].pending && p->ch[c].delay == p->tick) {
                    p->ch[c].pending = 0;
                    process_row(p, image, c);
                }
            }
        }
        for (int c = 0; c < YM_CHANNELS; ++c)
            if (p->ch[c].cut == p->tick) key_off(p, c);
    }
    for (int c = 0; c < YM_CHANNELS; ++c) {
        if (!p->ch[c].active) continue;
        if (c < YM_FM_CHANNELS) update_fm(p, c);
        else if (c < YM_CH_PCM) update_psg(p, c);
    }
    if (!p->audition && ++p->tick >= p->speed) {
        p->tick = 0;
        advance(p, image);
    }
    end_batch(p);
}

/* ---- reset, placement, live edits, audition ------------------------------------------------- */

static void shadow_reset(YmPlayer* p) {
    memset(p->ym, 0, sizeof p->ym);
    for (int port = 0; port < 2; ++port)
        for (int i = 0; i < 3; ++i) {
            for (int s = 0; s < 4; ++s) {
                p->ym[port][0x40 + 4 * s + i] = 0x7F;
                p->ym[port][0x80 + 4 * s + i] = 0xFF;
            }
            p->ym[port][0xB4 + i] = 0xC0;
        }
    memset(p->tone, 0, sizeof p->tone);
    memset(p->att, 15, sizeof p->att);
    p->noise_ctrl = 0;
}

int ymp_machine_reset(YmWrite* out) {
    int n = 0;
#define W(k, r, v) out[n++] = (YmWrite){(uint8_t)(k), (uint8_t)(r), (uint8_t)(v)}
    W(0, 0x22, 0x00);
    W(0, 0x27, 0x00);
    W(0, 0x2B, 0x00);
    static const uint8_t slot_val[YM_OP_BYTES] = {0x00, 0x7F, 0x00, 0x00, 0x00, 0xFF, 0x00};
    for (int port = 0; port < 2; ++port)
        for (int ch = 0; ch < 3; ++ch)
            for (int sl = 0; sl < 4; ++sl)
                for (int r = 0; r < YM_OP_BYTES; ++r) W(port, OP_REG[r] | sl * 4 | ch, slot_val[r]);
    static const uint8_t ch_reg[6] = {0xA0, 0xA4, 0xA8, 0xAC, 0xB0, 0xB4};
    for (int port = 0; port < 2; ++port)
        for (int ch = 0; ch < 3; ++ch)
            for (int r = 0; r < 6; ++r) W(port, ch_reg[r] | ch, ch_reg[r] == 0xB4 ? 0xC0 : 0x00);
    for (int ch = 0; ch < 3; ++ch) {
        W(0, 0x28, 0x00 | ch);
        W(0, 0x28, 0x04 | ch);
    }
    for (int i = 0; i < 4; ++i) {
        W(YM_W_PSG, 0, 0x80 | i << 5);
        W(YM_W_PSG, 0, 0x00);
        W(YM_W_PSG, 0, 0x90 | i << 5 | 0x0F);
    }
#undef W
    return n;
}

void ymp_reset(YmPlayer* p, const YmImage* image) {
    memset(p, 0, sizeof *p);
    for (int k = 0; k < YM_PCM_CHANNELS; ++k) p->pcm[k] = YMP_PCM_NONE;
    shadow_reset(p);
    for (int c = 0; c < YM_CHANNELS; ++c) {
        p->ch[c].cut = -1;
        p->ch[c].phase = YM_PSG_OFF;
        p->ch[c].vol8 = c < YM_FM_CHANNELS ? 1016 : c < YM_CH_PCM ? 120 : 0;
        p->ch[c].pan = 0xC0;
    }
    p->cursor_order = p->cursor_row = -1;
    p->speed = image->info.speed;
    p->region = image->info.region;
    p->lfo = image->info.lfo;
    p->fm3 = image->info.fm3;
    wr(p, 0, 0x22, p->lfo);
    wr(p, 0, 0x27, p->fm3 == YM_FM3_SPECIAL ? 0x40 : 0x00);
    end_batch(p);
}

void ymp_place(YmPlayer* p, const YmImage* image, int order) {
    ymp_reset(p, image);
    p->order = order;
    p->speed = image->info.start_speed[order];
}

/* Ticks per row straight play has at (order, row): the order's start speed and the Fxx of the rows before it (and of
 * the row itself once it has started). */
static int speed_at(const YmImage* image, int order, int row, int started) {
    int s = image->info.start_speed[order];
    YmCursor cur[YM_CHANNELS];
    for (int c = 0; c < YM_CHANNELS; ++c) cur[c] = ym_pattern(image, order, c);
    const int last = started ? row : row - 1;
    for (int r = 0; r <= last; ++r) {
        for (int c = 0; c < YM_CHANNELS; ++c) {
            YmRow x;
            ym_next_row(image->bytes, &cur[c], &x);
            for (int e = 0; e < x.effects; ++e)
                if (x.code[e] == YM_FX_SPEED) s = x.param[e];
        }
    }
    return s;
}

/* Slot sa of a and slot sb of b are the same kit: both PCM, at least one key mapped, every key unmapped in both or
 * mapped to variants with the same compiled bytes. */
static int same_kit(const YmImage* a, int sa, const YmImage* b, int sb) {
    if (!ym_instrument(b, sb, YM_KIND_PCM)) return 0;
    int mapped = 0;
    for (int x = 0; x < YM_PCM_KEYS; ++x) {
        const int va = ym_pcm_variant(a, sa, x), vb = ym_pcm_variant(b, sb, x);
        if (!va != !vb || (va && !ym_pcm_same_variant(a, va, b, vb))) return 0;
        mapped += va != 0;
    }
    return mapped > 0;
}

/* A PCM channel remembers its kit by slot and its sounding variant by id. An edit that renumbers the kits (or
 * recompiles the bank) moves them to the slot whose every key names the same sounds and to the variant with the same
 * bytes; a kit that changed keeps its slot, a variant no longer compiled is forgotten. */
static void relocate_pcm(YmChannel* ch, const YmImage* old, const YmImage* next) {
    const int s = ch->instrument;
    if (s && ym_instrument(old, s, YM_KIND_PCM) && !same_kit(old, s, next, s))
        for (int t = 1; t <= YM_MAX_INSTRUMENTS; ++t)
            if (t != s && same_kit(old, s, next, t)) {
                ch->instrument = t;
                break;
            }
    if (ch->pcm) {
        int to = ch->pcm <= next->info.pcm_variants && ym_pcm_same_variant(old, ch->pcm, next, ch->pcm) ? ch->pcm : 0;
        for (int v = 1; v <= next->info.pcm_variants && !to; ++v)
            if (ym_pcm_same_variant(old, ch->pcm, next, v)) to = v;
        ch->pcm = to;
    }
}

void ymp_relocate(YmPlayer* p, const YmImage* old, const YmImage* next) {
    if (p->audition) return;
    const YmInfo* info = &next->info;
    if (info->lfo != old->info.lfo) p->lfo = info->lfo;
    p->fm3 = info->fm3;
    for (int c = YM_CH_PCM; c < YM_CHANNELS; ++c) relocate_pcm(&p->ch[c], old, next);
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
        for (int c = 0; c < YM_CHANNELS; ++c) p->ch[c].pending = 0;
        p->speed = speed_at(next, p->order, p->row, 1);
        if (!started) advance(p, next); /* the clamped row counts as played: the next row follows it */
        return;
    }
    const int new_speed = speed_at(next, p->order, p->row, started);
    if (new_speed != old_speed) p->speed = new_speed;
    if (!started) return;
    /* The row has started: its jump and any still-delayed rows come from the new data. */
    for (int c = 0; c < YM_CHANNELS; ++c) {
        YmRow row;
        ym_read_row(next, p->order, c, p->row, &row);
        YmChannel* ch = &p->ch[c];
        ch->row = row;
        if (ch->pending) {
            const int g = row_delay(&row);
            ch->pending = g >= p->tick;
            ch->delay = g;
        }
    }
    derive_jump(p, next);
}

int ymp_audition(YmPlayer* p, const YmImage* image, int channel, int note, int slot, int fine) {
    if (channel < 0 || channel >= YM_CHANNELS || note < 0 || note >= YM_NOTES || fine < -128 || fine > 127) return -1;
    const int kind = ym_channel_kind(channel);
    if (!ym_instrument(image, slot, kind)) return -1;
    if (kind == YM_KIND_PCM && !ym_pcm_variant(image, slot, note)) return -1;
    ymp_reset(p, image);
    p->audition = 1;
    p->ch[channel].instrument = slot;
    key_on(p, image, channel, note, NULL);
    p->ch[channel].fine = fine;
    end_batch(p);
    return 0;
}
