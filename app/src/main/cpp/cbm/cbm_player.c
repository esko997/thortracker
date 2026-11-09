/* Thor Tracker's VIC-20 / C16-Plus/4 player (cbm_player.h). Specification: docs/AUDIO_ENGINE.md, "VIC-20 and
 * C16/Plus/4", "Player". Every rule here has a counterpart in tools/audio/cbm/cbm_ref.py; change both and the spec
 * together. */
#include "cbm_player.h"

#include <string.h>

_Static_assert((-1 >> 1) == -1, "the vibrato offset uses an arithmetic (floor) right shift");

#define R_VIC_BASS 0x0A    /* voices 0..3 at $0A + channel */
#define R_VIC_VOLUME 0x0E
#define R_TED_V1_LO 0x0E
#define R_TED_V2_LO 0x0F
#define R_TED_V2_HI 0x10
#define R_TED_CONTROL 0x11
#define R_TED_V1_HI 0x12

static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

static int max_volume(const CbPlayer* p) { return p->chip == CB_CHIP_VIC ? CB_VIC_MAX_VOLUME : CB_TED_MAX_VOLUME; }

static void append(CbPlayer* p, int reg, int val) {
    if (p->writes < CBP_MAX_WRITES) {
        p->write[p->writes].reg = (uint8_t)reg;
        p->write[p->writes].val = (uint8_t)val;
        ++p->writes;
    }
}

/* A register write through its shadow. */
static void wr(CbPlayer* p, int reg, int val) {
    if (p->shadow[reg] == (uint8_t)val) return;
    append(p, reg, val);
    p->shadow[reg] = (uint8_t)val;
}

/* The instrument record of a slot as the image holds it (sounding notes read it every tick: live edits). */
static const uint8_t* raw_record(const CbmImage* image, int slot) {
    return image->bytes + CB_OFF_INSTRUMENTS + CB_INSTRUMENT_SIZE * (clamp(slot, 1, CB_MAX_INSTRUMENTS) - 1);
}

static int vic_noise_lane(const CbPlayer* p, int c) { return p->chip == CB_CHIP_VIC && c == CB_VIC_NOISE_CHANNEL; }
static int dac_lane(const CbPlayer* p, int c) { return p->chip == CB_CHIP_TED && c == CB_TED_DAC_CHANNEL; }

/* ---- pitch ----------------------------------------------------------------------------------- */

int cbp_period(int variant, int pitch, int shift) {
    const int q = clamp(pitch, 0, CBP_PITCH_MAX) + 768 * shift, o = q / 768;
    const uint64_t t = CBP_PERIOD_Q16[variant & 3][q % 768];
    const int a = (int)((t + (1ull << (15 + o))) >> (16 + o));
    return variant < CB_VARIANT_TED_PAL ? clamp(a, 1, 128) : clamp(a, 2, 1024);
}

uint32_t cbp_sample_step(int pitch, int root_note) {
    const int d = clamp(pitch, 0, CBP_PITCH_MAX) - 64 * root_note;
    const int o = d >= 0 ? d / 768 : -((-d + 767) / 768); /* floor(d / 768) */
    const int i = d - 768 * o;
    uint64_t s = o >= 0 ? (uint64_t)CBP_STEP_Q16[i] << o : (uint64_t)CBP_STEP_Q16[i] >> -o;
    if (s < 1) s = 1;
    if (s > (1u << 24)) s = 1u << 24;
    return (uint32_t)s;
}

int cbp_timer_cycles(const CbPlayer* p) { return 64 + 4 * p->timer; }

int cbp_ted_control(const CbPlayer* p) {
    if (p->dac) return 0x80 | 0x30 | p->dac_level;
    int v = p->vol_out;
    const CbChannel *v1 = &p->ch[0], *v2 = &p->ch[1];
    if (v1->sounding && v1->wave == CB_WAVE_SQUARE) v |= 0x10;
    if (v2->sounding) {
        if (v2->wave == CB_WAVE_SQUARE) v |= 0x20;
        else if (v2->wave == CB_WAVE_NOISE) v |= 0x40;
    }
    return v;
}

/* ---- envelopes ------------------------------------------------------------------------------- */

typedef struct Envelope {
    int offset, length, loop, release;
} Envelope;

static void envelope(const uint8_t* rec, int k, Envelope* e) {
    const uint8_t* at = rec + CB_I_ENVELOPES + CB_ENVELOPE_BYTES * k;
    e->offset = cb_u16(at);
    e->length = at[2];
    e->loop = at[3] == CB_ENV_NONE ? -1 : at[3];
    e->release = at[4] == CB_ENV_NONE ? -1 : at[4];
}

/* The byte at the channel's index (clamped to the length: live edits). */
static int env_byte(const CbmImage* image, const Envelope* e, int idx) {
    return image->bytes[CB_OFF_DATA + e->offset + (idx < e->length ? idx : e->length - 1)];
}

static int env_next(const Envelope* e, int idx, int released) {
    if (idx > e->length - 1) idx = e->length - 1;
    const int next = idx + 1;
    if (released && e->release >= 0) return next < e->length ? next : e->length - 1;
    const int held = e->release >= 0 ? e->release : e->length;
    if (next < held) return next;
    return e->loop >= 0 ? e->loop : held - 1;
}

/* ---- notes ----------------------------------------------------------------------------------- */

/* The note stops: VIC voices write their register with the gate (bit 7) clear at once; the TED's voices are gated by
 * the control step, the DAC lane leaves D/A mode. */
static void stop(CbPlayer* p, int c) {
    p->ch[c].sounding = 0;
    if (p->owner == c) p->owner = -1;
    if (p->chip == CB_CHIP_VIC) {
        const int reg = R_VIC_BASS + c;
        wr(p, reg, p->shadow[reg] & 0x7F);
    } else if (c == CB_TED_DAC_CHANNEL) {
        p->dac = 0;
    }
}

static void off(CbPlayer* p, const CbmImage* image, int c) {
    CbChannel* ch = &p->ch[c];
    if (!ch->sounding) return;
    const uint8_t* rec = raw_record(image, ch->slot);
    Envelope vol, wave;
    envelope(rec, CB_ENV_VOLUME, &vol);
    envelope(rec, CB_ENV_WAVE, &wave);
    if ((vol.length && vol.release >= 0) || (wave.length && wave.release >= 0)) {
        ch->released = 1;
        for (int k = 0; k < CB_ENVELOPE_COUNT; ++k) {
            Envelope e;
            envelope(rec, k, &e);
            if (e.length && e.release >= 0) ch->env[k] = e.release;
        }
    } else {
        stop(p, c);
    }
}

void cbp_off(CbPlayer* p, const CbmImage* image, int channel) {
    p->writes = 0;
    if (channel < 0 || channel >= p->channels) return;
    const int was = p->ch[channel].sounding;
    off(p, image, channel);
    if (was && !p->ch[channel].sounding && p->chip == CB_CHIP_TED) wr(p, R_TED_CONTROL, cbp_ted_control(p));
}

static void key_on(CbPlayer* p, const CbmImage* image, int c, int note) {
    CbChannel* ch = &p->ch[c];
    const uint8_t* rec = cb_allowed(image, ch->instrument, c);
    if (!rec) return;
    ch->slot = ch->instrument;
    for (int k = 0; k < CB_ENVELOPE_COUNT; ++k) ch->env[k] = 0;
    ch->released = 0;
    ch->rel_pitch = 0;
    ch->note = note;
    ch->base = ch->target = note * 64;
    ch->slide = 0;
    ch->arp_step = 0;
    ch->vib_phase = 0;
    ch->sounding = 1;
    ch->fresh = 1;
    if (rec[CB_I_ENVELOPES + CB_ENVELOPE_BYTES * CB_ENV_VOLUME + 2]) p->owner = c;
    else if (p->owner == c) p->owner = -1;
    if (dac_lane(p, c)) ch->pos = 0x10000;
}

static void process_row(CbPlayer* p, const CbmImage* image, int c) {
    CbChannel* ch = &p->ch[c];
    const CbRow* row = &ch->row;
    const int has_note = row->note >= 0 && row->note != CB_NOTE_OFF;
    if (has_note) {
        ch->arp = 0;
        ch->fine = 0;
        ch->wave_override = -1;
    }
    if (row->instrument) ch->instrument = row->instrument;
    for (int e = 0; e < row->effects; ++e) {
        const int v = row->param[e];
        switch (row->code[e]) {
        case CB_FX_ARPEGGIO: ch->arp = v; ch->arp_step = 0; break;
        case CB_FX_SLIDE_UP: ch->slide_mode = v ? CBP_SLIDE_UP : CBP_SLIDE_NONE; ch->slide_speed = v; break;
        case CB_FX_SLIDE_DOWN: ch->slide_mode = v ? CBP_SLIDE_DOWN : CBP_SLIDE_NONE; ch->slide_speed = v; break;
        case CB_FX_PORTAMENTO: ch->slide_mode = v ? CBP_SLIDE_PORTA : CBP_SLIDE_NONE; ch->slide_speed = v; break;
        case CB_FX_VIBRATO: ch->vib_speed = v >> 4; ch->vib_depth = v & 15; break;
        case CB_FX_VOLUME_SLIDE: p->volslide = v; break;
        case CB_FX_FINE: ch->fine = v - CB_FINE_NONE; break;
        case CB_FX_CUT: ch->cut = v; break;
        case CB_FX_TIMER: p->timer = v; break;
        case CB_FX_VOLUME: p->m8 = clamp(v, 0, max_volume(p)) * 8; break;
        case CB_FX_WAVE: ch->wave_override = v; break;
        default: break; /* B, D, F, G acted at the row start */
        }
    }
    if (row->note == CB_NOTE_OFF) {
        off(p, image, c);
    } else if (has_note) {
        /* Portamento glides a sounding note; never on the VIC noise lane (its notes are rates: always a key-on). */
        if (ch->slide_mode == CBP_SLIDE_PORTA && ch->sounding && !vic_noise_lane(p, c)) ch->target = row->note * 64;
        else key_on(p, image, c, row->note);
    }
}

/* ---- per tick -------------------------------------------------------------------------------- */

static const uint8_t* sample_entry(const CbmImage* image, const uint8_t* rec) {
    return image->bytes + CB_OFF_SAMPLES + CB_SAMPLE_ENTRY_SIZE * (rec[CB_I_SAMPLE] & (CB_MAX_SAMPLES - 1));
}

static void update(CbPlayer* p, const CbmImage* image, int c) {
    CbChannel* ch = &p->ch[c];
    const uint8_t* rec = cb_allowed(image, ch->slot, c);
    if (!rec) { /* live edit: the latched slot is gone or no longer allowed on the lane */
        stop(p, c);
        return;
    }
    Envelope env[CB_ENVELOPE_COUNT];
    for (int k = 0; k < CB_ENVELOPE_COUNT; ++k) envelope(rec, k, &env[k]);
    const int arp = ch->arp_step == 1 ? ch->arp >> 4 : ch->arp_step == 2 ? ch->arp & 15 : 0;
    const int arp_env = env[CB_ENV_ARPEGGIO].length ? (int8_t)env_byte(image, &env[CB_ENV_ARPEGGIO], ch->env[CB_ENV_ARPEGGIO]) : 0;
    const int semitones = arp + arp_env;
    int pitch_env = 0;
    if (env[CB_ENV_PITCH].length) {
        const int v = (int8_t)env_byte(image, &env[CB_ENV_PITCH], ch->env[CB_ENV_PITCH]);
        if (rec[CB_I_FLAGS] & CB_FLAG_RELATIVE) {
            ch->rel_pitch = clamp(ch->rel_pitch + v, -CBP_SLIDE_LIMIT, CBP_SLIDE_LIMIT);
            pitch_env = ch->rel_pitch;
        } else {
            pitch_env = v;
        }
    }
    const int vib = (CBP_SINE[ch->vib_phase] * ch->vib_depth) >> 4;
    const int pitch = clamp(ch->base + 64 * semitones + ch->fine + ch->slide + vib + pitch_env, 0, CBP_PITCH_MAX);
    const int wave = env[CB_ENV_WAVE].length ? env_byte(image, &env[CB_ENV_WAVE], ch->env[CB_ENV_WAVE])
                   : ch->wave_override >= 0 ? ch->wave_override : rec[CB_I_WAVE];
    ch->wave = wave;
    if (c == p->owner)
        p->owner_value = env[CB_ENV_VOLUME].length ? env_byte(image, &env[CB_ENV_VOLUME], ch->env[CB_ENV_VOLUME]) : 0;
    const int gate = wave >= CB_WAVE_SQUARE ? 0x80 : 0;
    const int variant = p->chip * 2 + image->info.video;
    if (p->chip == CB_CHIP_VIC) {
        if (c == CB_VIC_NOISE_CHANNEL) {
            const int k = clamp(ch->note + semitones, 0, CB_NOISE_RATE_MAX);
            wr(p, R_VIC_BASS + c, gate | ((k + 127) & 127));
        } else {
            const int a = cbp_period(variant, pitch, 2 - c); /* bass 2, alto 1, soprano 0 octaves down */
            wr(p, R_VIC_BASS + c, gate | ((127 - a) & 127));
        }
    } else if (c == CB_TED_DAC_CHANNEL) {
        ch->step = cbp_sample_step(pitch, rec[CB_I_ROOT]);
        if (ch->fresh) {
            p->dac = 1;
            p->dac_level = image->bytes[CB_OFF_DATA + cb_u32(sample_entry(image, rec))];
            wr(p, R_TED_CONTROL, 0xB0 | p->dac_level);
        }
    } else {
        const int x = (1023 - cbp_period(variant, pitch, 0)) & 0x3FF;
        wr(p, c == 0 ? R_TED_V1_LO : R_TED_V2_LO, x & 0xFF);
        wr(p, c == 0 ? R_TED_V1_HI : R_TED_V2_HI, x >> 8);
    }
    ch->fresh = 0;
    for (int k = 0; k < CB_ENVELOPE_COUNT; ++k)
        if (env[k].length) ch->env[k] = env_next(&env[k], ch->env[k], ch->released);
    if (ch->arp) ch->arp_step = (ch->arp_step + 1) % 3;
    if (ch->slide_mode == CBP_SLIDE_UP) ch->slide = clamp(ch->slide + ch->slide_speed, -CBP_SLIDE_LIMIT, CBP_SLIDE_LIMIT);
    if (ch->slide_mode == CBP_SLIDE_DOWN) ch->slide = clamp(ch->slide - ch->slide_speed, -CBP_SLIDE_LIMIT, CBP_SLIDE_LIMIT);
    if (ch->slide_mode == CBP_SLIDE_PORTA) {
        if (ch->base < ch->target) ch->base = ch->base + ch->slide_speed > ch->target ? ch->target : ch->base + ch->slide_speed;
        else if (ch->base > ch->target) ch->base = ch->base - ch->slide_speed < ch->target ? ch->target : ch->base - ch->slide_speed;
    }
    if (ch->vib_depth && ch->vib_speed) ch->vib_phase = (ch->vib_phase + ch->vib_speed) & 63;
}

/* The control step: the master volume out (owner rule), VIC $0E or TED $11; then the shared Axy slide. */
static void control(CbPlayer* p) {
    const int mx = max_volume(p), m = p->m8 >> 3;
    p->vol_out = p->owner >= 0 ? (p->owner_value + m - mx > 0 ? p->owner_value + m - mx : 0) : m;
    if (p->chip == CB_CHIP_VIC) wr(p, R_VIC_VOLUME, p->vol_out);
    else wr(p, R_TED_CONTROL, cbp_ted_control(p));
    if (p->volslide) p->m8 = clamp(p->m8 + (p->volslide >> 4) - (p->volslide & 15), 0, mx * 8);
}

void cbp_timer(CbPlayer* p, const CbmImage* image) {
    p->writes = 0;
    if (p->chip != CB_CHIP_TED || !p->dac) return;
    const int c = CB_TED_DAC_CHANNEL;
    CbChannel* ch = &p->ch[c];
    const uint8_t* rec = cb_allowed(image, ch->slot, c);
    uint64_t f = ch->pos >> 16;
    uint32_t offset = 0, length = 0;
    if (rec) {
        const uint8_t* entry = sample_entry(image, rec);
        offset = cb_u32(entry);
        length = cb_u32(entry + 4);
    }
    if (!rec || f >= length) {
        const uint32_t start = rec ? (uint32_t)cb_u16(rec + CB_I_LOOP_START) : 0;
        if (!rec || !rec[CB_I_LOOP] || start >= length) {
            /* A one-shot's end (or a slot a live edit removed): D/A mode ends, the control value at once. */
            ch->sounding = 0;
            p->dac = 0;
            wr(p, R_TED_CONTROL, cbp_ted_control(p));
            return;
        }
        const uint64_t span = (uint64_t)(length - start) << 16;
        ch->pos = ((uint64_t)start << 16) + (ch->pos - ((uint64_t)length << 16)) % span;
        f = ch->pos >> 16;
    }
    p->dac_level = image->bytes[CB_OFF_DATA + offset + (uint32_t)f];
    wr(p, R_TED_CONTROL, 0xB0 | p->dac_level);
    ch->pos += ch->step;
}

static void read_rows(CbPlayer* p, const CbmImage* image) {
    if (p->cursor_order != p->order || p->cursor_row != p->row) {
        for (int c = 0; c < p->channels; ++c) {
            CbCursor cur;
            cb_cursor(image, p->order, c, &cur);
            CbRow skip;
            for (int r = 0; r < p->row; ++r) cb_next_row(image->bytes, &cur, &skip);
            p->cursor[c] = cur;
        }
    }
    for (int c = 0; c < p->channels; ++c) cb_next_row(image->bytes, &p->cursor[c], &p->ch[c].row);
    p->cursor_order = p->order;
    p->cursor_row = p->row + 1;
}

/* B/D of the current row (rows already read) -> p->jump. */
static void derive_jump(CbPlayer* p, const CbmImage* image) {
    int has_b = 0, has_d = 0, b = 0, d = 0;
    for (int c = 0; c < p->channels; ++c) {
        const CbRow* row = &p->ch[c].row;
        for (int e = 0; e < row->effects; ++e) {
            if (row->code[e] == CB_FX_JUMP) has_b = 1, b = row->param[e];
            if (row->code[e] == CB_FX_BREAK) has_d = 1, d = row->param[e];
        }
    }
    p->jump = has_b || has_d;
    p->jump_order = has_b ? b : p->order + 1 < image->info.orders ? p->order + 1 : image->info.loop;
    p->jump_row = has_d ? d : 0;
}

static int row_delay(const CbRow* row) {
    int g = 0;
    for (int e = 0; e < row->effects; ++e)
        if (row->code[e] == CB_FX_DELAY) g = row->param[e];
    return g;
}

static void row_start(CbPlayer* p, const CbmImage* image) {
    read_rows(p, image);
    for (int c = 0; c < p->channels; ++c) {
        const CbRow* row = &p->ch[c].row;
        for (int e = 0; e < row->effects; ++e)
            if (row->code[e] == CB_FX_SPEED) p->speed = row->param[e];
    }
    derive_jump(p, image);
    for (int c = 0; c < p->channels; ++c) {
        CbChannel* ch = &p->ch[c];
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
static void advance(CbPlayer* p, const CbmImage* image) {
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

void cbp_tick(CbPlayer* p, const CbmImage* image) {
    p->writes = 0;
    if (!p->audition) {
        if (p->tick == 0) {
            row_start(p, image);
        } else {
            for (int c = 0; c < p->channels; ++c) {
                if (p->ch[c].pending && p->ch[c].delay == p->tick) {
                    p->ch[c].pending = 0;
                    process_row(p, image, c);
                }
            }
        }
        for (int c = 0; c < p->channels; ++c)
            if (p->ch[c].cut == p->tick) off(p, image, c);
    }
    for (int c = 0; c < p->channels; ++c)
        if (p->ch[c].sounding) update(p, image, c);
    control(p);
    if (!p->audition && ++p->tick >= p->speed) {
        p->tick = 0;
        advance(p, image);
    }
}

static void reset(CbPlayer* p, const CbmImage* image, int audition) {
    const uint8_t* b = image->bytes;
    memset(p, 0, sizeof *p);
    p->chip = image->info.chip;
    p->channels = image->info.channels;
    for (int c = 0; c < CB_MAX_CHANNELS; ++c) {
        p->ch[c].cut = -1;
        p->ch[c].wave_override = -1;
    }
    p->cursor_order = p->cursor_row = -1;
    p->speed = image->info.speed;
    p->audition = audition;
    p->m8 = b[CB_H_VOLUME] * 8;
    p->timer = b[CB_H_TIMER];
    p->owner = -1;
    p->vol_out = b[CB_H_VOLUME];
    /* The reset batch: every write emitted, setting the shadows. */
    if (p->chip == CB_CHIP_VIC) {
        for (int reg = R_VIC_BASS; reg < R_VIC_VOLUME; ++reg) append(p, reg, 0);
        append(p, R_VIC_VOLUME, p->vol_out);
        p->shadow[R_VIC_VOLUME] = (uint8_t)p->vol_out;
    } else {
        append(p, R_TED_V1_LO, 0);
        append(p, R_TED_V1_HI, 0);
        append(p, R_TED_V2_LO, 0);
        append(p, R_TED_V2_HI, 0);
        append(p, R_TED_CONTROL, p->vol_out);
        p->shadow[R_TED_CONTROL] = (uint8_t)p->vol_out;
    }
}

void cbp_reset(CbPlayer* p, const CbmImage* image) { reset(p, image, 0); }

void cbp_place(CbPlayer* p, const CbmImage* image, int order) {
    reset(p, image, 0);
    p->order = order;
    p->speed = image->info.start_speed[order];
}

/* Ticks per row straight play has at (order, row): the order's start speed and the Fxx of the rows before it (and of
 * the row itself once it has started). */
static int speed_at(const CbmImage* image, int order, int row, int started) {
    int s = image->info.start_speed[order];
    CbCursor cur[CB_MAX_CHANNELS];
    for (int c = 0; c < image->info.channels; ++c) cb_cursor(image, order, c, &cur[c]);
    const int last = started ? row : row - 1;
    for (int r = 0; r <= last; ++r) {
        for (int c = 0; c < image->info.channels; ++c) {
            CbRow x;
            cb_next_row(image->bytes, &cur[c], &x);
            for (int e = 0; e < x.effects; ++e)
                if (x.code[e] == CB_FX_SPEED) s = x.param[e];
        }
    }
    return s;
}

void cbp_relocate(CbPlayer* p, const CbmImage* old, const CbmImage* next) {
    if (p->audition || next->info.chip != p->chip) return;
    const CbInfo* info = &next->info;
    /* Shared fields whose song-start (header) value the edit changed take the new value (the timer applies from the
     * next reload); values set by effects persist when the start values are not edited. */
    if (old->bytes[CB_H_VOLUME] != next->bytes[CB_H_VOLUME]) p->m8 = next->bytes[CB_H_VOLUME] * 8;
    if (old->bytes[CB_H_TIMER] != next->bytes[CB_H_TIMER]) p->timer = next->bytes[CB_H_TIMER];
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
        for (int c = 0; c < p->channels; ++c) p->ch[c].pending = 0;
        p->speed = speed_at(next, p->order, p->row, 1);
        if (!started) advance(p, next); /* the clamped row counts as played: the next row follows it */
        return;
    }
    const int new_speed = speed_at(next, p->order, p->row, started);
    if (new_speed != old_speed) p->speed = new_speed;
    if (!started) return;
    /* The row has started: its jump and any still-delayed rows come from the new data. */
    for (int c = 0; c < p->channels; ++c) {
        CbRow row;
        cb_read_row(next, p->order, c, p->row, &row, NULL);
        CbChannel* ch = &p->ch[c];
        ch->row = row;
        if (ch->pending) {
            const int g = row_delay(&row);
            ch->pending = g >= p->tick;
            ch->delay = g;
        }
    }
    derive_jump(p, next);
}

int cbp_audition(CbPlayer* p, const CbmImage* image, int channel, int note, int slot, int fine) {
    int lo, hi;
    if (image->info.loaded != CB_LOADED || cb_lane_range(image->info.chip, image->info.video, channel, &lo, &hi) ||
        note < lo || note > hi || fine < -128 || fine > 127)
        return -1;
    if (!cb_allowed(image, slot, channel)) return -1;
    reset(p, image, 1);
    p->ch[channel].instrument = slot;
    key_on(p, image, channel, note);
    p->ch[channel].fine = fine;
    return 0;
}
