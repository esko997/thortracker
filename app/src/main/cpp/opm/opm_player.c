/* Thor Tracker's OPM player (opm_player.h). Specification: docs/AUDIO_ENGINE.md, "OPM: Player".
 * Every rule here has a counterpart in tools/audio/opm/opm_ref.py; change both and the spec together. */
#include "opm_player.h"

#include <string.h>

_Static_assert((-1 >> 1) == -1, "the vibrato offset uses an arithmetic (floor) right shift");

/* floor(127 sin(2 pi i / 64) + 0.5): the player's own vibrato (the OPL3's table). */
static const int8_t SINE[64] = {
    0,   12,  25,  37,  49,  60,  71,  81,  90,  98,  106,  112,  117,  122,  125,  126,
    127, 126, 125, 122, 117, 112, 106, 98,  90,  81,  71,   60,   49,   37,   25,   12,
    0,   -12, -25, -37, -49, -60, -71, -81, -90, -98, -106, -112, -117, -122, -125, -126,
    -127, -126, -125, -122, -117, -112, -106, -98, -90, -81, -71, -60, -49, -37, -25, -12,
};

/* Register slot of operator k (operators 1..4 = M1, C1, M2, C2 occupy slots 0, 2, 1, 3). */
static const int OP_SLOT[4] = {0, 2, 1, 3};
/* Carrier operators per algorithm (bit k = operator k + 1). */
static const int CARRIERS[8] = {0x8, 0x8, 0x8, 0x8, 0xA, 0xE, 0xE, 0xF};
/* Operator register banks in instrument order. */
static const int OP_BANK[OM_OPERATOR_BYTES] = {0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0};

#define KEY_REG 0x08

static void append(OmPlayer* p, int reg, int val) {
    if (p->writes < OMP_MAX_WRITES) {
        p->write[p->writes].reg = (uint8_t)reg;
        p->write[p->writes].val = (uint8_t)val;
        ++p->writes;
    }
}

/* A write through the shadow ($19 holds two values: PMD, bit 7 set, is shadowed at OMP_SHADOW_PMD). */
static void wr(OmPlayer* p, int reg, int val) {
    int slot = reg, value = val;
    if (reg == 0x19 && (val & 0x80)) {
        slot = OMP_SHADOW_PMD;
        value = val & 0x7F;
    }
    if (p->shadow[slot] == (uint8_t)value) return;
    p->shadow[slot] = (uint8_t)value;
    append(p, reg, val);
}

/* The key register is a command (not shadowed); a key-off (operator mask 0) belongs to the key-off phase. */
static void key_write(OmPlayer* p, int val) {
    if (val & OMP_KEY_ALL) {
        append(p, KEY_REG, val);
    } else if (p->pending_offs < OM_CHANNELS) {
        p->off[p->pending_offs].reg = KEY_REG;
        p->off[p->pending_offs].val = (uint8_t)val;
        ++p->pending_offs;
    }
}

static void begin_batch(OmPlayer* p) { p->writes = p->offs = p->pending_offs = 0; }

/* Puts the key-off phase in front of the batch. */
static void end_batch(OmPlayer* p) {
    const int n = p->pending_offs;
    if (p->writes + n > OMP_MAX_WRITES) p->writes = OMP_MAX_WRITES - n;
    memmove(p->write + n, p->write, sizeof p->write[0] * (size_t)p->writes);
    memcpy(p->write, p->off, sizeof p->off[0] * (size_t)n);
    p->writes += n;
    p->offs = n;
    p->pending_offs = 0;
}

static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

static const uint8_t* instrument(const OpmImage* image, int slot) {
    if (slot < 1 || slot > image->info.instruments) return NULL;
    const uint8_t* rec = image->bytes + OM_OFF_INSTRUMENTS + OM_INSTRUMENT_SIZE * (slot - 1);
    return (rec[OM_I_FLAGS] & OM_FLAG_DEFINED) ? rec : NULL;
}

void omp_key_code(int pitch, int* kc, int* kf) {
    const int c = clamp(pitch, OMP_PITCH_MIN, OMP_PITCH_MAX) - OMP_PITCH_OFFSET;
    const int i = (c >> 6) - 1, octave = i / 12, s = i % 12;
    *kc = octave << 4 | (s + s / 3);
    *kf = (c & 63) << 2;
}

int omp_operator_offset(int channel, int k) { return 8 * OP_SLOT[k] + channel; }

/* The chip's shared state, in the specified order, each through the shadow. */
static void globals(OmPlayer* p) {
    wr(p, 0x18, p->lfo_rate);
    wr(p, 0x1B, p->lfo_wave);
    wr(p, 0x19, p->amd);
    wr(p, 0x19, 0x80 | p->pmd);
    wr(p, 0x0F, p->noise);
    wr(p, 0x10, p->timer_a >> 2);
    wr(p, 0x11, p->timer_a & 3);
    wr(p, 0x14, p->csm ? 0x81 : 0x00);
}

/* ---- notes ----------------------------------------------------------------------------------- */

/* Carrier TL with the channel volume (0.75 dB per volume step), modulators as they are. */
static int tl_out(const OmChannel* ch, int k) {
    const int tl = ch->ops[k][1] & 0x7F;
    if (!(ch->carriers >> k & 1)) return tl;
    const int out = tl + 127 - (ch->vol8 >> 3);
    return out > 127 ? 127 : out;
}

static void key_off(OmPlayer* p, int c) {
    OmChannel* ch = &p->ch[c];
    ch->keyon = 0;
    if (ch->keyed) {
        key_write(p, c);
        ch->keyed = 0;
    }
}

void omp_key_off(OmPlayer* p, int channel) {
    begin_batch(p);
    if (channel >= 0 && channel < OM_CHANNELS) key_off(p, channel);
    end_batch(p);
}

/* Key on `note` with the channel's instrument; `row` gives this row's locks (NULL: none). */
static void key_on(OmPlayer* p, const OpmImage* image, int c, int note, const OmRow* row) {
    OmChannel* ch = &p->ch[c];
    const uint8_t* rec = instrument(image, ch->instrument);
    if (!rec) return;
    key_off(p, c);
    memcpy(ch->ops, rec + OM_I_OPS, sizeof ch->ops);
    ch->connect = rec[OM_I_CONNECT];
    ch->sens = rec[OM_I_SENS];
    ch->carriers = CARRIERS[ch->connect & 7];
    if (c == OM_NOISE_CHANNEL)
        p->noise = (rec[OM_I_FLAGS] & OM_FLAG_NOISE) ? 0x80 | rec[OM_I_NOISE] : p->noise & 0x1F;
    for (int e = 0; row && e < row->effects; ++e) {
        const int v = row->param[e], x = v >> 4, y = v & 15;
        switch (row->code[e]) {
        case OM_FX_FEEDBACK: ch->connect = (ch->connect & 7) | v << 3; break;
        case OM_FX_BRIGHTNESS:
            for (int k = 0; k < 4; ++k)
                if (!(ch->carriers >> k & 1)) ch->ops[k][1] = (uint8_t)(0x7F - v);
            break;
        case OM_FX_MULT:
            if (x >= 1 && x <= 4) ch->ops[x - 1][0] = (uint8_t)((ch->ops[x - 1][0] & 0x70) | y);
            break;
        case OM_FX_SENSITIVITY: ch->sens = x << 4 | y; break;
        case OM_FX_NOISE:
            if (c == OM_NOISE_CHANNEL) p->noise = (p->noise & 0x80) | v;
            break;
        }
    }
    for (int k = 0; k < 4; ++k) {
        const int off = omp_operator_offset(c, k);
        for (int i = 0; i < OM_OPERATOR_BYTES; ++i) wr(p, OP_BANK[i] + off, i == 1 ? tl_out(ch, k) : ch->ops[k][i]);
    }
    wr(p, 0x20 + c, ch->pan | ch->connect);
    wr(p, 0x38 + c, ch->sens);
    if (c == OM_NOISE_CHANNEL) globals(p);
    ch->base = ch->target = note * 64;
    ch->slide = 0;
    ch->arp_step = 0;
    ch->vib_phase = 0;
    const int speed = rec[OM_I_SWEEP_SPEED];
    ch->sweep = speed ? (int8_t)rec[OM_I_SWEEP_START] * 64 : 0;
    ch->sweep_end = speed ? (int8_t)rec[OM_I_SWEEP_END] * 64 : 0;
    ch->sweep_step = speed * 4;
    ch->keyon = 1;
    ch->active = 1;
}

static void process_row(OmPlayer* p, const OpmImage* image, int c) {
    OmChannel* ch = &p->ch[c];
    const OmRow* row = &ch->row;
    const int has_note = row->note >= OM_NOTE_MIN && row->note <= OM_NOTE_MAX;
    if (has_note) {
        ch->arp = 0;
        ch->fine = 0;
    }
    if (row->instrument) ch->instrument = row->instrument;
    for (int e = 0; e < row->effects; ++e) {
        const int v = row->param[e], x = v >> 4, y = v & 15;
        switch (row->code[e]) {
        case OM_FX_ARPEGGIO: ch->arp = v; ch->arp_step = 0; break;
        case OM_FX_SLIDE_UP: ch->slide_mode = v ? 1 : 0; ch->slide_speed = v; break;
        case OM_FX_SLIDE_DOWN: ch->slide_mode = v ? 2 : 0; ch->slide_speed = v; break;
        case OM_FX_PORTAMENTO: ch->slide_mode = v ? 3 : 0; ch->slide_speed = v; break;
        case OM_FX_VIBRATO: ch->vib_speed = x; ch->vib_depth = y; break;
        case OM_FX_PAN:
            ch->pan = (x ? 0x40 : 0) | (y ? 0x80 : 0);
            if (ch->active) wr(p, 0x20 + c, ch->pan | ch->connect);
            break;
        case OM_FX_VOLUME_SLIDE: ch->volslide = v; break;
        case OM_FX_VOLUME: ch->vol8 = v * 8; break;
        case OM_FX_SENSITIVITY:
            ch->sens = x << 4 | y;
            if (ch->active) wr(p, 0x38 + c, ch->sens);
            break;
        case OM_FX_FINE: ch->fine = v - 0x80; break;
        case OM_FX_CUT: ch->cut = v; break;
        case OM_FX_LFO_RATE: p->lfo_rate = v; globals(p); break;
        case OM_FX_NOISE: p->noise = (p->noise & 0x80) | v; globals(p); break;
        case OM_FX_TIMER_A: p->timer_a = v << 2 | (p->timer_a & 3); globals(p); break;
        case OM_FX_AMD: p->amd = v; globals(p); break;
        case OM_FX_PMD: p->pmd = v; globals(p); break;
        case OM_FX_LFO_WAVE: p->lfo_wave = v; globals(p); break;
        case OM_FX_CSM: p->csm = v; globals(p); break;
        }
    }
    if (row->note == OM_NOTE_OFF) {
        key_off(p, c);
    } else if (has_note) {
        if (ch->slide_mode == 3 && ch->keyed) ch->target = row->note * 64;
        else key_on(p, image, c, row->note, row);
    }
}

/* ---- per tick -------------------------------------------------------------------------------- */

static int pitch(const OmChannel* ch) {
    int arp = 0;
    if (ch->arp && ch->arp_step) arp = ch->arp_step == 1 ? ch->arp >> 4 : ch->arp & 15;
    const int vib = (SINE[ch->vib_phase] * ch->vib_depth) >> 4;
    return clamp(ch->base + arp * 64 + ch->fine + ch->slide + vib + ch->sweep, OMP_PITCH_MIN, OMP_PITCH_MAX);
}

static void update(OmPlayer* p, int c) {
    OmChannel* ch = &p->ch[c];
    if (!ch->active) return;
    int kc, kf;
    omp_key_code(pitch(ch), &kc, &kf);
    for (int k = 0; k < 4; ++k)
        if (ch->carriers >> k & 1) wr(p, 0x60 + omp_operator_offset(c, k), tl_out(ch, k));
    wr(p, 0x28 + c, kc);
    wr(p, 0x30 + c, kf);
    if (ch->keyon) {
        key_write(p, OMP_KEY_ALL | c);
        ch->keyed = 1;
        ch->keyon = 0;
    }
    if (ch->arp) ch->arp_step = (ch->arp_step + 1) % 3;
    if (ch->slide_mode == 1) ch->slide = clamp(ch->slide + ch->slide_speed, -6144, 6144);
    if (ch->slide_mode == 2) ch->slide = clamp(ch->slide - ch->slide_speed, -6144, 6144);
    if (ch->slide_mode == 3) {
        if (ch->base < ch->target) ch->base = ch->base + ch->slide_speed > ch->target ? ch->target : ch->base + ch->slide_speed;
        else if (ch->base > ch->target) ch->base = ch->base - ch->slide_speed < ch->target ? ch->target : ch->base - ch->slide_speed;
    }
    if (ch->vib_depth && ch->vib_speed) ch->vib_phase = (ch->vib_phase + ch->vib_speed) & 63;
    if (ch->sweep_step) {
        if (ch->sweep < ch->sweep_end) ch->sweep = clamp(ch->sweep + ch->sweep_step, ch->sweep, ch->sweep_end);
        else if (ch->sweep > ch->sweep_end) ch->sweep = clamp(ch->sweep - ch->sweep_step, ch->sweep_end, ch->sweep);
    }
    if (ch->volslide) ch->vol8 = clamp(ch->vol8 + (ch->volslide >> 4) - (ch->volslide & 15), 0, 1016);
}

static void read_rows(OmPlayer* p, const OpmImage* image) {
    if (p->cursor_order != p->order || p->cursor_row != p->row) {
        for (int c = 0; c < OM_CHANNELS; ++c) {
            OmCursor cur = {om_u16(image->bytes + OM_OFF_ORDERS + 2 * (OM_CHANNELS * p->order + c)), 0};
            OmRow skip;
            for (int r = 0; r < p->row; ++r) om_next_row(image->bytes, &cur, &skip);
            p->cursor[c] = cur;
        }
    }
    for (int c = 0; c < OM_CHANNELS; ++c) om_next_row(image->bytes, &p->cursor[c], &p->ch[c].row);
    p->cursor_order = p->order;
    p->cursor_row = p->row + 1;
}

/* B/D of the current row (rows already read) -> p->jump. */
static void derive_jump(OmPlayer* p, const OpmImage* image) {
    int has_b = 0, has_d = 0, b = 0, d = 0;
    for (int c = 0; c < OM_CHANNELS; ++c) {
        const OmRow* row = &p->ch[c].row;
        for (int e = 0; e < row->effects; ++e) {
            if (row->code[e] == OM_FX_JUMP) has_b = 1, b = row->param[e];
            if (row->code[e] == OM_FX_BREAK) has_d = 1, d = row->param[e];
        }
    }
    p->jump = has_b || has_d;
    p->jump_order = has_b ? b : p->order + 1 < image->info.orders ? p->order + 1 : image->info.loop;
    p->jump_row = has_d ? d : 0;
}

static int row_delay(const OmRow* row) {
    int g = 0;
    for (int e = 0; e < row->effects; ++e)
        if (row->code[e] == OM_FX_DELAY) g = row->param[e];
    return g;
}

static void row_start(OmPlayer* p, const OpmImage* image) {
    read_rows(p, image);
    for (int c = 0; c < OM_CHANNELS; ++c) {
        const OmRow* row = &p->ch[c].row;
        for (int e = 0; e < row->effects; ++e)
            if (row->code[e] == OM_FX_SPEED) p->speed = row->param[e];
    }
    derive_jump(p, image);
    for (int c = 0; c < OM_CHANNELS; ++c) {
        OmChannel* ch = &p->ch[c];
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

/* Next row. Every arrival at the loop order's row 0 starts it at the speed sequential straight play reaches it with
 * (a row-0 Fxx still applies on top, at the row start). */
static void advance(OmPlayer* p, const OpmImage* image) {
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

void omp_tick(OmPlayer* p, const OpmImage* image) {
    begin_batch(p);
    globals(p);
    if (!p->audition) {
        if (p->tick == 0) {
            row_start(p, image);
        } else {
            for (int c = 0; c < OM_CHANNELS; ++c) {
                if (p->ch[c].pending && p->ch[c].delay == p->tick) {
                    p->ch[c].pending = 0;
                    process_row(p, image, c);
                }
            }
        }
        for (int c = 0; c < OM_CHANNELS; ++c)
            if (p->ch[c].cut == p->tick) key_off(p, c);
    }
    for (int c = 0; c < OM_CHANNELS; ++c) update(p, c);
    if (!p->audition && ++p->tick >= p->speed) {
        p->tick = 0;
        advance(p, image);
    }
    end_batch(p);
}

static void reset(OmPlayer* p, const OpmImage* image, int audition) {
    const uint8_t* b = image->bytes;
    memset(p, 0, sizeof *p);
    for (int c = 0; c < OM_CHANNELS; ++c) {
        p->ch[c].cut = -1;
        p->ch[c].vol8 = 1016;
        p->ch[c].pan = 0xC0;
    }
    p->cursor_order = p->cursor_row = -1;
    p->speed = image->info.speed;
    p->audition = audition;
    p->lfo_rate = b[OM_H_LFO_RATE];
    p->lfo_wave = b[OM_H_LFO_WAVE];
    p->amd = b[OM_H_AMD];
    p->pmd = b[OM_H_PMD];
    p->csm = audition ? 0 : b[OM_H_CSM];
    p->timer_a = om_u16(b + OM_H_TIMER_A);
    p->noise = 0;
    /* The chip resets $20-$27 to 0 (both outputs off), ymfm to $C0: written, every chip and VGM player agree. */
    for (int c = 0; c < OM_CHANNELS; ++c) wr(p, 0x20 + c, 0xC0);
    globals(p);
    end_batch(p);
}

void omp_reset(OmPlayer* p, const OpmImage* image) { reset(p, image, 0); }

void omp_place(OmPlayer* p, const OpmImage* image, int order) {
    reset(p, image, 0);
    p->order = order;
    p->speed = image->info.start_speed[order];
}

/* Ticks per row straight play has at (order, row): the order's start speed and the Fxx of the rows before
 * it (and of the row itself once it has started). */
static int speed_at(const OpmImage* image, int order, int row, int started) {
    int s = image->info.start_speed[order];
    OmCursor cur[OM_CHANNELS];
    for (int c = 0; c < OM_CHANNELS; ++c) {
        cur[c].offset = om_u16(image->bytes + OM_OFF_ORDERS + 2 * (OM_CHANNELS * order + c));
        cur[c].run = 0;
    }
    const int last = started ? row : row - 1;
    for (int r = 0; r <= last; ++r) {
        for (int c = 0; c < OM_CHANNELS; ++c) {
            OmRow x;
            om_next_row(image->bytes, &cur[c], &x);
            for (int e = 0; e < x.effects; ++e)
                if (x.code[e] == OM_FX_SPEED) s = x.param[e];
        }
    }
    return s;
}

/* Shared state fields whose song-start (header) value the edit changed take the new value; the next tick's globals
 * write them. Values set by effects persist when the start values are not edited. */
static void relocate_shared(OmPlayer* p, const OpmImage* old, const OpmImage* next) {
    const uint8_t *a = old->bytes, *b = next->bytes;
    if (a[OM_H_LFO_RATE] != b[OM_H_LFO_RATE]) p->lfo_rate = b[OM_H_LFO_RATE];
    if (a[OM_H_LFO_WAVE] != b[OM_H_LFO_WAVE]) p->lfo_wave = b[OM_H_LFO_WAVE];
    if (a[OM_H_AMD] != b[OM_H_AMD]) p->amd = b[OM_H_AMD];
    if (a[OM_H_PMD] != b[OM_H_PMD]) p->pmd = b[OM_H_PMD];
    if (a[OM_H_CSM] != b[OM_H_CSM]) p->csm = b[OM_H_CSM];
    if (om_u16(a + OM_H_TIMER_A) != om_u16(b + OM_H_TIMER_A)) p->timer_a = om_u16(b + OM_H_TIMER_A);
}

void omp_relocate(OmPlayer* p, const OpmImage* old, const OpmImage* next) {
    if (p->audition) return;
    const OmInfo* info = &next->info;
    relocate_shared(p, old, next);
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
        for (int c = 0; c < OM_CHANNELS; ++c) p->ch[c].pending = 0;
        p->speed = speed_at(next, p->order, p->row, 1);
        if (!started) advance(p, next); /* the clamped row counts as played: the next row follows it */
        return;
    }
    const int new_speed = speed_at(next, p->order, p->row, started);
    if (new_speed != old_speed) p->speed = new_speed;
    if (!started) return;
    /* The row has started: its jump and any still-delayed rows come from the new data. */
    for (int c = 0; c < OM_CHANNELS; ++c) {
        OmRow row;
        om_read_row(next, p->order, c, p->row, &row, NULL);
        OmChannel* ch = &p->ch[c];
        ch->row = row;
        if (ch->pending) {
            const int g = row_delay(&row);
            ch->pending = g >= p->tick;
            ch->delay = g;
        }
    }
    derive_jump(p, next);
}

int omp_audition(OmPlayer* p, const OpmImage* image, int channel, int note, int slot, int fine) {
    if (channel < 0 || channel >= OM_CHANNELS || note < OM_NOTE_MIN || note > OM_NOTE_MAX || fine < -128 || fine > 127)
        return -1;
    const uint8_t* rec = instrument(image, slot);
    if (!rec || (channel != OM_NOISE_CHANNEL && (rec[OM_I_FLAGS] & OM_FLAG_NOISE))) return -1;
    reset(p, image, 1);
    p->ch[channel].instrument = slot;
    key_on(p, image, channel, note, NULL);
    p->ch[channel].fine = fine;
    end_batch(p);
    return 0;
}
