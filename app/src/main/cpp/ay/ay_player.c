/* Thor Tracker's AY-3-8910 / YM2149 player (ay_player.h). Specification: docs/AUDIO_ENGINE.md, "AY-3-8910 / YM2149:
 * player" and "Shared noise and envelope". Every rule here has a counterpart in tools/audio/ay/ay_ref.py; change both
 * and the spec together. */
#include "ay_player.h"

#include <string.h>

#include "generated/ay_tables.h"

_Static_assert((-1 >> 1) == -1, "the vibrato offset uses an arithmetic (floor) right shift");

static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

/* ---- pitch ----------------------------------------------------------------------------------- */

int ay_tone_period(int variant, int pitch) {
    const int o = pitch / 768, f = pitch % 768;
    const uint32_t n = (AY_DIV[variant][f] + (128u << o)) >> (8 + o);
    return n > AY_TONE_PERIOD_MAX ? AY_TONE_PERIOD_MAX : n < 1 ? 1 : (int)n;
}

/* Triangle shapes (two ramps per audible cycle) sound an octave below a saw of the same period. */
static int triangle(int shape) { return shape == 10 || shape == 14; }

/* The envelope's audible cycle (saw: 256 EP clocks, triangle: 512 EP) sounds the note J octaves up: EP = TP / 2^(4 + t + J)
 * with TP = DIV >> (8 + o), i.e. DIV >> (8 + o + 4 + t + J), rounded. */
int ay_envelope_period(int variant, int pitch, int octave, int shape) {
    const int o = pitch / 768, f = pitch % 768;
    const int s = 8 + o + 4 + triangle(shape) + octave; /* 4..27 */
    const uint32_t n = (AY_DIV[variant][f] + (1u << (s - 1))) >> s;
    return n > AY_ENV_PERIOD_MAX ? AY_ENV_PERIOD_MAX : n < 1 ? 1 : (int)n;
}

/* The tone one exact ratio below the envelope: TP = EP * 2^(4 + t + J) (k < 0: rounded division). */
int ay_synced_tone_period(int envelope_period, int octave, int shape) {
    const int k = 4 + triangle(shape) + octave; /* -4..12 */
    const uint32_t ep = (uint32_t)envelope_period;
    const uint32_t n = k >= 0 ? ep << k : (ep + (1u << (-k - 1))) >> -k;
    return n > AY_TONE_PERIOD_MAX ? AY_TONE_PERIOD_MAX : n < 1 ? 1 : (int)n;
}

/* ---- writes ---------------------------------------------------------------------------------- */

static void emit(AyPlayer* p, int reg, int val) {
    if (p->writes < AYP_MAX_WRITES) p->write[p->writes++] = (AyWrite){(uint8_t)reg, (uint8_t)val};
}

static void wr(AyPlayer* p, int reg, int val) {
    if (p->shadow[reg] == (uint8_t)val) return;
    p->shadow[reg] = (uint8_t)val;
    emit(p, reg, val);
}

/* ---- notes ----------------------------------------------------------------------------------- */

static void key_off(AyPlayer* p, int c) {
    AyChannel* ch = &p->ch[c];
    if (ch->phase != AYP_ON) return;
    const AyEnvelope* vol = &p->env[c][AYP_ENV_VOLUME];
    if (vol->length && vol->release >= 0) {
        ch->pos[AYP_ENV_VOLUME] = vol->release;
        ch->phase = AYP_RELEASED;
    } else {
        ch->phase = AYP_OFF;
    }
}

void ay_player_key_off(AyPlayer* p, int channel) {
    p->writes = 0;
    if (channel >= 0 && channel < AY_CHANNELS) key_off(p, channel);
}

/* A note setting (E, H, I, J, V, W). `now`: applied to the sounding note (a shape change restarts the envelope). */
static void apply_setting(AyChannel* ch, int code, int v, int now) {
    AySettings* s = &ch->set;
    switch (code) {
    case AY_FX_ENV_SHAPE:
        if (now && v != s->shape) ch->restart = 1;
        s->shape = v;
        break;
    case AY_FX_ENV_HIGH: s->period = (s->period & 0xFF) | v << 8; s->autop = 0; break;
    case AY_FX_ENV_LOW: s->period = (s->period & 0xFF00) | v; s->autop = 0; break;
    case AY_FX_ENV_AUTO: s->autop = 1; s->octave = (int8_t)v; break;
    case AY_FX_MIXER: s->mixer = v; break;
    case AY_FX_NOISE: s->noise = v; break;
    }
}

static int is_setting(int code) {
    return code == AY_FX_ENV_SHAPE || code == AY_FX_ENV_HIGH || code == AY_FX_ENV_LOW || code == AY_FX_ENV_AUTO ||
           code == AY_FX_MIXER || code == AY_FX_NOISE;
}

/* Key on `note` with the channel's instrument and the row's settings (codes/params, n of them). Returns 1 if keyed. */
static int key_on(AyPlayer* p, const AyImage* image, int c, int note, const uint8_t* codes, const uint8_t* params, int n) {
    AyChannel* ch = &p->ch[c];
    const uint8_t* rec = ay_instrument(image, ch->instrument);
    if (!rec) return 0;
    for (int k = 0; k < AYP_ENVELOPES; ++k) {
        AyEnvelope* e = &p->env[c][k];
        const int off = ay_u16(rec + AY_I_ENV_VOLUME + 2 * k);
        e->length = 0;
        if (off != AY_NO_ENVELOPE) {
            const uint8_t* b = image->bytes + AY_OFF_DATA + off;
            const int loop = ay_u16(b + 2), release = ay_u16(b + 4);
            e->length = ay_u16(b);
            e->loop = loop == AY_NO_ENVELOPE ? -1 : loop;
            e->release = release == AY_NO_ENVELOPE ? -1 : release;
            memcpy(e->value, b + AY_ENV_HEADER, (size_t)e->length);
        }
        ch->pos[k] = 0;
    }
    const int flags = rec[AY_I_FLAGS];
    ch->pitch_relative = (flags & AY_FLAG_PITCH_RELATIVE) != 0;
    ch->pitch_acc = 0;
    ch->set.shape = rec[AY_I_SHAPE];
    ch->set.autop = (flags & AY_FLAG_AUTO_PERIOD) != 0;
    ch->set.octave = (int8_t)rec[AY_I_OCTAVE];
    ch->set.period = ay_u16(rec + AY_I_PERIOD);
    ch->set.sync = (flags & AY_FLAG_SYNC) != 0;
    ch->set.mixer = -1;
    ch->set.noise = -1;
    for (int i = 0; i < n; ++i) apply_setting(ch, codes[i], params[i], 0);
    ch->phase = AYP_ON;
    ch->base = ch->target = note * AY_PITCH_UNITS_PER_SEMITONE;
    ch->slide = 0;
    ch->arp_step = 0;
    ch->vib_phase = 0;
    const int speed = rec[AY_I_SWEEP_SPEED];
    ch->sweep = speed ? (int8_t)rec[AY_I_SWEEP_START] * 64 : 0;
    ch->sweep_end = speed ? (int8_t)rec[AY_I_SWEEP_END] * 64 : 0;
    ch->sweep_step = speed * 4;
    ch->stamp = ++p->stamps;
    ch->restart = 1;
    ch->active = 1;
    return 1;
}

static void process_row(AyPlayer* p, const AyImage* image, int c) {
    AyChannel* ch = &p->ch[c];
    const AyRow* row = &ch->row;
    const int has_note = row->note >= 0 && row->note < AY_NOTES;
    if (has_note) {
        ch->arp = 0;
        ch->fine = 0;
    }
    if (row->instrument) ch->instrument = row->instrument;
    uint8_t codes[AY_MAX_EFFECTS], params[AY_MAX_EFFECTS];
    int settings = 0;
    for (int e = 0; e < row->effects; ++e) {
        const int v = row->param[e], x = v >> 4, y = v & 15;
        switch (row->code[e]) {
        case AY_FX_ARPEGGIO: ch->arp = v; ch->arp_step = 0; break;
        case AY_FX_SLIDE_UP: ch->slide_mode = v ? 1 : 0; ch->slide_speed = v; break;
        case AY_FX_SLIDE_DOWN: ch->slide_mode = v ? 2 : 0; ch->slide_speed = v; break;
        case AY_FX_PORTAMENTO: ch->slide_mode = v ? 3 : 0; ch->slide_speed = v; break;
        case AY_FX_VIBRATO: ch->vib_speed = x; ch->vib_depth = y; break;
        case AY_FX_VOLUME_SLIDE: ch->volslide = v; break;
        case AY_FX_VOLUME: ch->vol8 = v * 8; break;
        case AY_FX_FINE: ch->fine = v - 0x80; break;
        case AY_FX_CUT: ch->cut = v; break;
        default:
            if (is_setting(row->code[e])) {
                codes[settings] = row->code[e];
                params[settings++] = (uint8_t)v;
            }
            break;
        }
    }
    int keyed = 0;
    if (row->note == AY_NOTE_OFF) {
        key_off(p, c);
    } else if (has_note) {
        if (ch->slide_mode == 3 && ch->phase == AYP_ON) ch->target = row->note * AY_PITCH_UNITS_PER_SEMITONE;
        else keyed = key_on(p, image, c, row->note, codes, params, settings);
    }
    if (!keyed)
        for (int i = 0; i < settings; ++i) apply_setting(ch, codes[i], params[i], 1);
}

/* ---- per tick -------------------------------------------------------------------------------- */

static void evaluate(AyPlayer* p, int c) {
    AyChannel* ch = &p->ch[c];
    const AyEnvelope* env = p->env[c];
    const AyEnvelope* vol = &env[AYP_ENV_VOLUME];
    const int level = ch->phase == AYP_OFF ? 0 : vol->length ? vol->value[ch->pos[AYP_ENV_VOLUME]] : 15;
    const int arp_value = env[AYP_ENV_ARPEGGIO].length ? env[AYP_ENV_ARPEGGIO].value[ch->pos[AYP_ENV_ARPEGGIO]] : 0;
    const int bend =
        env[AYP_ENV_PITCH].length ? AY_PITCH_UNIT * env[AYP_ENV_PITCH].value[ch->pos[AYP_ENV_PITCH]] : 0;
    int offset;
    if (ch->pitch_relative) {
        ch->pitch_acc = clamp(ch->pitch_acc + bend, -6144, 6144);
        offset = ch->pitch_acc;
    } else {
        offset = bend;
    }
    ch->mixer = ch->set.mixer >= 0 ? ch->set.mixer
                : env[AYP_ENV_MIXER].length ? env[AYP_ENV_MIXER].value[ch->pos[AYP_ENV_MIXER]]
                                            : 1;
    ch->nz = ch->set.noise >= 0 ? ch->set.noise
             : env[AYP_ENV_NOISE].length ? env[AYP_ENV_NOISE].value[ch->pos[AYP_ENV_NOISE]]
                                         : -1;
    int arp_fx = 0;
    if (ch->arp && ch->arp_step) arp_fx = ch->arp_step == 1 ? ch->arp >> 4 : ch->arp & 15;
    const int vib = (AY_SINE[ch->vib_phase] * ch->vib_depth) >> 4;
    const int pitch = clamp(ch->base + AY_PITCH_UNITS_PER_SEMITONE * (arp_fx + arp_value) + ch->fine + ch->slide + vib +
                                ch->sweep + offset,
                            0, AY_PITCH_MAX);
    const int v = (level * (ch->vol8 >> 3) + 14) / 15;
    ch->sounds = ch->phase != AYP_OFF && v > 0;
    ch->uses_env = ch->sounds && ch->set.shape != 0;
    ch->ep = ch->set.autop ? ay_envelope_period(p->variant, pitch, ch->set.octave, ch->set.shape) : ch->set.period;
    ch->tp = ch->uses_env && ch->set.autop && ch->set.sync ? ay_synced_tone_period(ch->ep, ch->set.octave, ch->set.shape)
                                                           : ay_tone_period(p->variant, pitch);
    ch->amp = !ch->sounds ? 0 : ch->uses_env ? 0x10 : v;
}

/* The shared resources and the tick's writes: tone periods, R6, R7, amplitudes, R11/R12, R13. */
static void emit_tick(AyPlayer* p) {
    for (int c = 0; c < AY_CHANNELS; ++c) {
        const AyChannel* ch = &p->ch[c];
        if (!ch->active) continue;
        wr(p, 2 * c, ch->tp & 0xFF);
        wr(p, 2 * c + 1, ch->tp >> 8);
    }
    int noise = -1, owner = -1, r7 = p->io;
    for (int c = 0; c < AY_CHANNELS; ++c) {
        const AyChannel* ch = &p->ch[c];
        const int tone_on = ch->active && ch->sounds && (ch->mixer & 1);
        const int noise_on = ch->active && ch->sounds && (ch->mixer & 2);
        if (!tone_on) r7 |= 1 << c;
        if (!noise_on) r7 |= 8 << c;
        if (noise_on && ch->nz >= 0 && (noise < 0 || ch->stamp > p->ch[noise].stamp)) noise = c;
        if (ch->active && ch->uses_env && (owner < 0 || ch->stamp > p->ch[owner].stamp)) owner = c;
    }
    if (noise >= 0) wr(p, 6, p->ch[noise].nz);
    wr(p, 7, r7);
    for (int c = 0; c < AY_CHANNELS; ++c)
        if (p->ch[c].active) wr(p, 8 + c, p->ch[c].amp);
    if (owner >= 0) {
        const AyChannel* ch = &p->ch[owner];
        wr(p, 11, ch->ep & 0xFF);
        wr(p, 12, ch->ep >> 8);
        if (ch->restart || (owner != p->owner && ch->set.shape != p->last_r13)) {
            emit(p, 13, ch->set.shape);
            p->last_r13 = ch->set.shape;
        }
    }
    p->owner = owner;
    for (int c = 0; c < AY_CHANNELS; ++c) p->ch[c].restart = 0;
}

/* The position after `pos`: a held volume note loops before its release point; a released one plays its tail and
 * holds the last value; others loop at the end, or hold the last value without a loop. */
static int envelope_next(const AyEnvelope* e, int pos, int released) {
    const int n = pos + 1;
    if (!released && e->release >= 0 && n == e->release) return e->loop;
    if (n < e->length) return n;
    return e->loop >= 0 && e->release < 0 ? e->loop : e->length - 1;
}

static void advance_channel(AyPlayer* p, int c) {
    AyChannel* ch = &p->ch[c];
    if (ch->arp) ch->arp_step = (ch->arp_step + 1) % 3;
    if (ch->slide_mode == 1) ch->slide = clamp(ch->slide + ch->slide_speed, -6144, 6144);
    if (ch->slide_mode == 2) ch->slide = clamp(ch->slide - ch->slide_speed, -6144, 6144);
    if (ch->slide_mode == 3) {
        if (ch->base < ch->target) ch->base = clamp(ch->base + ch->slide_speed, 0, ch->target);
        else if (ch->base > ch->target) ch->base = clamp(ch->base - ch->slide_speed, ch->target, AY_PITCH_MAX);
    }
    if (ch->vib_depth && ch->vib_speed) ch->vib_phase = (ch->vib_phase + ch->vib_speed) & 63;
    if (ch->sweep_step) {
        if (ch->sweep < ch->sweep_end) ch->sweep = clamp(ch->sweep + ch->sweep_step, ch->sweep, ch->sweep_end);
        else if (ch->sweep > ch->sweep_end) ch->sweep = clamp(ch->sweep - ch->sweep_step, ch->sweep_end, ch->sweep);
    }
    if (ch->volslide) ch->vol8 = clamp(ch->vol8 + (ch->volslide >> 4) - (ch->volslide & 15), 0, 120);
    const AyEnvelope* env = p->env[c];
    const int released = ch->phase == AYP_RELEASED;
    for (int k = 0; k < AYP_ENVELOPES; ++k)
        if (env[k].length) ch->pos[k] = envelope_next(&env[k], ch->pos[k], released);
    const AyEnvelope* vol = &env[AYP_ENV_VOLUME];
    if (released && ch->pos[AYP_ENV_VOLUME] == vol->length - 1 && vol->value[vol->length - 1] == 0) ch->phase = AYP_OFF;
}

static void read_rows(AyPlayer* p, const AyImage* image) {
    if (p->cursor_order != p->order || p->cursor_row != p->row) {
        for (int c = 0; c < AY_CHANNELS; ++c) {
            AyCursor cur = ay_pattern(image, p->order, c);
            AyRow skip;
            for (int r = 0; r < p->row; ++r) ay_next_row(image->bytes, &cur, &skip);
            p->cursor[c] = cur;
        }
    }
    for (int c = 0; c < AY_CHANNELS; ++c) ay_next_row(image->bytes, &p->cursor[c], &p->ch[c].row);
    p->cursor_order = p->order;
    p->cursor_row = p->row + 1;
}

/* B/D of the current row (rows already read) -> p->jump. */
static void derive_jump(AyPlayer* p, const AyImage* image) {
    int has_b = 0, has_d = 0, b = 0, d = 0;
    for (int c = 0; c < AY_CHANNELS; ++c) {
        const AyRow* row = &p->ch[c].row;
        for (int e = 0; e < row->effects; ++e) {
            if (row->code[e] == AY_FX_JUMP) has_b = 1, b = row->param[e];
            if (row->code[e] == AY_FX_BREAK) has_d = 1, d = row->param[e];
        }
    }
    p->jump = has_b || has_d;
    p->jump_order = has_b ? b : p->order + 1 < image->info.orders ? p->order + 1 : image->info.loop;
    p->jump_row = has_d ? d : 0;
}

static int row_delay(const AyRow* row) {
    int g = 0;
    for (int e = 0; e < row->effects; ++e)
        if (row->code[e] == AY_FX_DELAY) g = row->param[e];
    return g;
}

static void row_start(AyPlayer* p, const AyImage* image) {
    read_rows(p, image);
    for (int c = 0; c < AY_CHANNELS; ++c) {
        const AyRow* row = &p->ch[c].row;
        for (int e = 0; e < row->effects; ++e)
            if (row->code[e] == AY_FX_SPEED) p->speed = row->param[e];
    }
    derive_jump(p, image);
    for (int c = 0; c < AY_CHANNELS; ++c) {
        AyChannel* ch = &p->ch[c];
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
static void advance(AyPlayer* p, const AyImage* image) {
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

void ay_player_tick(AyPlayer* p, const AyImage* image) {
    p->writes = 0;
    if (!p->audition) {
        if (p->tick == 0) {
            row_start(p, image);
        } else {
            for (int c = 0; c < AY_CHANNELS; ++c) {
                if (p->ch[c].pending && p->ch[c].delay == p->tick) {
                    p->ch[c].pending = 0;
                    process_row(p, image, c);
                }
            }
        }
        for (int c = 0; c < AY_CHANNELS; ++c)
            if (p->ch[c].cut == p->tick) key_off(p, c);
    }
    for (int c = 0; c < AY_CHANNELS; ++c)
        if (p->ch[c].active) evaluate(p, c);
    emit_tick(p);
    for (int c = 0; c < AY_CHANNELS; ++c)
        if (p->ch[c].active) advance_channel(p, c);
    if (!p->audition && ++p->tick >= p->speed) {
        p->tick = 0;
        advance(p, image);
    }
}

/* ---- reset, placement, live edits, audition ------------------------------------------------- */

void ay_player_reset(AyPlayer* p, const AyImage* image) {
    memset(p, 0, sizeof *p);
    for (int c = 0; c < AY_CHANNELS; ++c) {
        p->ch[c].cut = -1;
        p->ch[c].phase = AYP_OFF;
        p->ch[c].vol8 = 120;
        p->ch[c].set.mixer = p->ch[c].set.noise = -1;
    }
    p->cursor_order = p->cursor_row = -1;
    p->owner = -1;
    p->last_r13 = -1;
    p->speed = image->info.speed;
    p->variant = image->info.variant;
    p->io = ay_variants[p->variant].io_bits;
    wr(p, 7, p->io | 0x3F);
}

void ay_player_place(AyPlayer* p, const AyImage* image, int order) {
    ay_player_reset(p, image);
    p->order = order;
    p->speed = image->info.start_speed[order];
}

/* Ticks per row straight play has at (order, row): the order's start speed and the Fxx of the rows before it (and of
 * the row itself once it has started). */
static int speed_at(const AyImage* image, int order, int row, int started) {
    int s = image->info.start_speed[order];
    AyCursor cur[AY_CHANNELS];
    for (int c = 0; c < AY_CHANNELS; ++c) cur[c] = ay_pattern(image, order, c);
    const int last = started ? row : row - 1;
    for (int r = 0; r <= last; ++r) {
        for (int c = 0; c < AY_CHANNELS; ++c) {
            AyRow x;
            ay_next_row(image->bytes, &cur[c], &x);
            for (int e = 0; e < x.effects; ++e)
                if (x.code[e] == AY_FX_SPEED) s = x.param[e];
        }
    }
    return s;
}

void ay_player_relocate(AyPlayer* p, const AyImage* old, const AyImage* next) {
    if (p->audition) return;
    const AyInfo* info = &next->info;
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
        for (int c = 0; c < AY_CHANNELS; ++c) p->ch[c].pending = 0;
        p->speed = speed_at(next, p->order, p->row, 1);
        if (!started) advance(p, next); /* the clamped row counts as played: the next row follows it */
        return;
    }
    const int new_speed = speed_at(next, p->order, p->row, started);
    if (new_speed != old_speed) p->speed = new_speed;
    if (!started) return;
    /* The row has started: its jump and any still-delayed rows come from the new data. */
    for (int c = 0; c < AY_CHANNELS; ++c) {
        AyRow row;
        ay_read_row(next, p->order, c, p->row, &row);
        AyChannel* ch = &p->ch[c];
        ch->row = row;
        if (ch->pending) {
            const int g = row_delay(&row);
            ch->pending = g >= p->tick;
            ch->delay = g;
        }
    }
    derive_jump(p, next);
}

int ay_player_audition(AyPlayer* p, const AyImage* image, int channel, int note, int slot, int fine) {
    if (channel < 0 || channel >= AY_CHANNELS || note < 0 || note >= AY_NOTES || fine < -128 || fine > 127) return -1;
    if (!ay_instrument(image, slot)) return -1;
    ay_player_reset(p, image);
    p->audition = 1;
    p->ch[channel].instrument = slot;
    key_on(p, image, channel, note, NULL, NULL, 0);
    p->ch[channel].fine = fine;
    return 0;
}
