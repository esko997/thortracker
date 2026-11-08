/* Thor Tracker's SAA1099 player (saa_player.h). Specification: docs/AUDIO_ENGINE.md, "SAA1099: Player".
 * Every rule here has a counterpart in tools/audio/saa/saa_ref.py; change both and the spec together. The sequencing
 * (rows, F/B/D/G/S, jumps, loop speed, relocation) is the SCC player's (scc/scc_player.c) with C channels. */
#include "saa_player.h"

#include <string.h>

#include "generated/saa_tables.h"
#include "saa_core.h"

_Static_assert((-1 >> 1) == -1, "the vibrato offset uses an arithmetic (floor) right shift");
_Static_assert(SA_CHIP_CHANNELS == SAC_CHANNELS, "image and core agree");
_Static_assert(SAP_PITCH_MAX == 10 * 768 - 1, "pitch range: C-0..B-9 + 63");

/* Envelopes in SaChannel.env order. */
enum { ENV_VOLUME, ENV_ARPEGGIO, ENV_PITCH, ENV_MIXER, ENVS };
static const int ENV_FIELD[ENVS] = {SA_I_VOLUME, SA_I_ARPEGGIO, SA_I_PITCH, SA_I_MIXER};

static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

static int defined(const SaaImage* image, int slot) { return sa_instrument_flags(image, slot) & SA_FLAG_DEFINED; }

/* ---- writes ---------------------------------------------------------------------------------------------------- */

static void wr(SaPlayer* p, int chip, int reg, int val, int forced) {
    if (!forced && p->sh[chip][reg] == (uint8_t)val) return;
    p->sh[chip][reg] = (uint8_t)val;
    if (p->writes >= SAP_MAX_WRITES) return;
    SaWrite* w = &p->write[p->writes++];
    w->chip = (uint8_t)chip;
    w->reg = (uint8_t)reg;
    w->val = (uint8_t)val;
}

/* ---- pitch ----------------------------------------------------------------------------------------------------- */

int sap_octave_offset(int pitch, int cms) {
    pitch = clamp(pitch, 0, SAP_PITCH_MAX);
    const int k = pitch / 768, r = pitch % 768;
    const long t = cms ? (long)SA_DIV_CMS[r] : (long)SA_DIV_SAM[r]; /* 511 - N at octave 0, Q8 */
    int s, d;
    if (t >= 131072) {
        s = -1;
        d = (int)((t + 256) >> 9);
    } else {
        s = 0;
        d = (int)((t + 128) >> 8);
        if (d >= 512) {
            s = -1;
            d = (int)((t + 256) >> 9);
        }
    }
    const int o = k + s;
    if (o < 0) return 0;              /* the chip's lowest tone: O 0, N 0 */
    if (o > 7) return 7 << 8 | 255;   /* its highest: O 7, N 255 */
    return o << 8 | (511 - d);
}

/* ---- notes ----------------------------------------------------------------------------------------------------- */

static void key_on(SaPlayer* p, const SaaImage* image, int c, int note) {
    SaChannel* ch = &p->ch[c];
    if (!defined(image, ch->instrument)) return;
    ch->slot = ch->instrument;
    for (int k = 0; k < ENVS; ++k) ch->env[k] = 0;
    ch->released = 0;
    ch->accumulator = 0;
    ch->base = ch->target = 64 * note;
    ch->slide = 0;
    ch->arp_step = 0;
    ch->vib_phase = 0;
    ch->keyed = 1;
    ch->active = 1;
    ch->keyon = 1;
    ch->ext_counter = 0;
    ch->ext_clocks = 0;
}

/* OFF, Sxx, the audition release: a note with a release point not yet passed is released, otherwise no longer held. */
static void key_off(SaPlayer* p, const SaaImage* image, int c) {
    SaChannel* ch = &p->ch[c];
    if (!ch->keyed) return;
    if (defined(image, ch->slot) && !ch->released) {
        SaEnvelope e;
        sa_envelope(image, ch->slot, SA_I_VOLUME, &e);
        if (e.length && e.release >= 0) {
            ch->released = 1;
            ch->env[ENV_VOLUME] = e.release;
            return;
        }
    }
    ch->keyed = 0;
}

static void process_row(SaPlayer* p, const SaaImage* image, int c) {
    SaChannel* ch = &p->ch[c];
    const SaRow* row = &ch->row;
    const int has_note = row->note >= 0 && row->note < SA_NOTES;
    if (has_note) {
        ch->arp = 0;
        ch->fine = 0;
        ch->pan = ch->mixer = ch->noise = ch->envelope = -1;
    }
    if (row->instrument) ch->instrument = row->instrument;
    for (int e = 0; e < row->effects; ++e) {
        const int v = row->param[e], x = v >> 4, y = v & 15;
        switch (row->code[e]) {
        case SA_FX_ARPEGGIO: ch->arp = v; ch->arp_step = 0; break;
        case SA_FX_SLIDE_UP: ch->slide_mode = v ? 1 : 0; ch->slide_speed = v; break;
        case SA_FX_SLIDE_DOWN: ch->slide_mode = v ? 2 : 0; ch->slide_speed = v; break;
        case SA_FX_PORTAMENTO: ch->slide_mode = v ? 3 : 0; ch->slide_speed = v; break;
        case SA_FX_VIBRATO: ch->vib_speed = x; ch->vib_depth = y; break;
        case SA_FX_PAN: ch->pan = v; break;
        case SA_FX_VOLUME_SLIDE: ch->volslide = v; break;
        case SA_FX_VOLUME: ch->volume = v * 16; break;
        case SA_FX_ENVELOPE: ch->envelope = v; break;
        case SA_FX_MIXER: ch->mixer = v; break;
        case SA_FX_NOISE: ch->noise = v; break;
        case SA_FX_FINE: ch->fine = v - 0x80; break;
        case SA_FX_CUT: ch->cut = v; break;
        case SA_FX_SYNC: p->sync_cmd[c / SA_CHIP_CHANNELS] = v; break; /* the last of the chip's rows run this tick */
        default: break; /* B, D, F, G: the row start's */
        }
    }
    if (row->note == SA_NOTE_OFF) {
        key_off(p, image, c);
    } else if (has_note) {
        if (ch->slide_mode == 3 && ch->keyed) ch->target = 64 * row->note;
        else key_on(p, image, c, row->note);
    }
}

/* ---- per tick -------------------------------------------------------------------------------------------------- */

/* The envelope's value index, moved to its last value if a live edit shortened it. */
static int env_index(SaChannel* ch, int k, const SaEnvelope* e) {
    if (ch->env[k] >= e->length) ch->env[k] = e->length - 1;
    return ch->env[k];
}

static void env_advance(SaChannel* ch, int k, const SaEnvelope* e) {
    if (!e->length) return;
    const int end = !ch->released && e->release >= 0 ? e->release : e->length;
    if (ch->env[k] + 1 < end) ++ch->env[k];
    else if (e->loop >= 0 && !ch->released) ch->env[k] = e->loop;
    else ch->env[k] = end - 1;
}

/* One side's amplitude: e cv p / 225, at least 1 when all three are non-zero. */
static int side(int e, int cv, int pan) {
    const int v = e * cv * pan / 225;
    return !v && e > 0 && cv > 0 && pan > 0 ? 1 : v;
}

/* The amplitude register value of a held note at volume envelope value e. */
static int amplitude(const SaChannel* ch, const uint8_t* rec, int e) {
    int left, right;
    if (ch->pan >= 0) {
        left = ch->pan >> 4; /* 8xy: x left, y right */
        right = ch->pan & 15;
    } else {
        left = rec[SA_I_PAN] & 15; /* instrument: bits 0-3 left, 4-7 right */
        right = rec[SA_I_PAN] >> 4;
    }
    const int cv = ch->volume / 16;
    return side(e, cv, left) | side(e, cv, right) << 4;
}

static void update(SaPlayer* p, const SaaImage* image, int c) {
    SaChannel* ch = &p->ch[c];
    const int chip = c / SA_CHIP_CHANNELS, l = c % SA_CHIP_CHANNELS;
    /* a sounding note whose slot a live edit removed is cut: amplitude 0, mixer bits 0, inactive */
    if (ch->active && !defined(image, ch->slot)) ch->active = ch->keyed = 0;
    const int held = ch->active && ch->keyed;
    int amp = 0, m = 0, clock = 0;
    SaEnvelope env[ENVS];
    if (ch->active) {
        const uint8_t* rec = sa_instrument(image, ch->slot);
        int value[ENVS] = {15, 0, 0, 1};
        for (int k = 0; k < ENVS; ++k) {
            sa_envelope(image, ch->slot, ENV_FIELD[k], &env[k]);
            if (!env[k].length) continue;
            const int raw = env[k].values[env_index(ch, k, &env[k])];
            value[k] = k == ENV_ARPEGGIO || k == ENV_PITCH ? (int8_t)raw : raw;
        }
        m = ch->mixer >= 0 ? ch->mixer : value[ENV_MIXER];
        int q = 4 * value[ENV_PITCH];
        if (rec[SA_I_FLAGS] & SA_FLAG_PITCH_RELATIVE)
            q = ch->accumulator = clamp(ch->accumulator + q, -SAP_PITCH_SPAN, SAP_PITCH_SPAN);
        int arp = 0;
        if (ch->arp && ch->arp_step) arp = ch->arp_step == 1 ? ch->arp >> 4 : ch->arp & 15;
        const int vib = ch->vib_depth ? (SA_SINE[ch->vib_phase] * ch->vib_depth) >> 4 : 0;
        const int pitch =
            clamp(ch->base + 64 * (value[ENV_ARPEGGIO] + arp) + ch->fine + ch->slide + vib + q, 0, SAP_PITCH_MAX);
        const int oo = sap_octave_offset(pitch, p->variant == SA_VARIANT_CMS);
        /* offset, then the octave byte from the shadow with this channel's field */
        wr(p, chip, SAC_REG_OFFSET + l, oo & 0xFF, 0);
        const int reg = SAC_REG_OCTAVE + l / 2, shift = (l & 1) ? 4 : 0;
        wr(p, chip, reg, (p->sh[chip][reg] & ~(0x07 << shift) & 0xFF) | (oo >> 8) << shift, 0);
        if (held) {
            amp = amplitude(ch, rec, value[ENV_VOLUME]);
            clock = ch->noise >= 0 ? ch->noise : rec[SA_I_NOISE];
        }
    }
    wr(p, chip, SAC_REG_AMP + l, amp, 0);
    ch->mix = held ? m : 0;
    /* a noise claim: the triple's generator takes this channel's clock (the latest claim of the tick wins) */
    if (held && (m & 2) && (ch->keyon || !(ch->last_mix & 2) || clock != ch->last_noise))
        p->noise_value[chip][l / 3] = clock;
    ch->last_mix = ch->mix;
    ch->last_noise = held ? clock : 0;
    if (!ch->active) return;

    for (int k = 0; k < ENVS; ++k) env_advance(ch, k, &env[k]);
    if (ch->arp) ch->arp_step = (ch->arp_step + 1) % 3;
    if (ch->slide_mode == 1) ch->slide = clamp(ch->slide + ch->slide_speed, -SAP_PITCH_SPAN, SAP_PITCH_SPAN);
    if (ch->slide_mode == 2) ch->slide = clamp(ch->slide - ch->slide_speed, -SAP_PITCH_SPAN, SAP_PITCH_SPAN);
    if (ch->slide_mode == 3) {
        if (ch->base < ch->target) ch->base = clamp(ch->base + ch->slide_speed, ch->base, ch->target);
        else if (ch->base > ch->target) ch->base = clamp(ch->base - ch->slide_speed, ch->target, ch->base);
    }
    if (ch->vib_depth && ch->vib_speed) ch->vib_phase = (ch->vib_phase + ch->vib_speed) & 63;
    if (ch->volslide) ch->volume = clamp(ch->volume + (ch->volslide >> 4) - (ch->volslide & 15), 0, SAP_VOLUME_MAX);
}

static void mixer_pass(SaPlayer* p, int chip) {
    int tone = 0, noise = 0;
    for (int l = 0; l < SA_CHIP_CHANNELS; ++l) {
        const int m = p->ch[chip * SA_CHIP_CHANNELS + l].mix;
        tone |= (m & 1) << l;
        noise |= ((m >> 1) & 1) << l;
    }
    wr(p, chip, SAC_REG_TONE, tone, 0);
    wr(p, chip, SAC_REG_NOISE, noise, 0);
}

static void noise_pass(SaPlayer* p, int chip) {
    wr(p, chip, SAC_REG_NOISE_MODE, p->noise_value[chip][0] | p->noise_value[chip][1] << 4, 0);
}

/* Envelope e of the chip, on chip channel 3e + 2 while it is active and held. */
static void envelope_pass(SaPlayer* p, const SaaImage* image, int chip) {
    for (int e = 0; e < 2; ++e) {
        SaChannel* ch = &p->ch[chip * SA_CHIP_CHANNELS + 3 * e + 2];
        if (!(ch->active && ch->keyed)) continue;
        const uint8_t* rec = sa_instrument(image, ch->slot);
        const int reg = SAC_REG_ENV0 + e;
        const int v = ch->envelope >= 0 ? ch->envelope : rec[SA_I_ENVELOPE];
        const int rate = rec[SA_I_RATE] ? rec[SA_I_RATE] : 1;
        if (ch->keyon) {
            const int retrigger = (rec[SA_I_FLAGS] & SA_FLAG_ENV_RETRIGGER) || ch->envelope >= 0;
            if (v && retrigger && (p->sh[chip][reg] & SA_ENV_ENABLE)) wr(p, chip, reg, 0x00, 0); /* off at once ... */
            wr(p, chip, reg, v, 0);                                                              /* ... reloaded at once */
            ch->ext_counter = ch->ext_clocks = 0;
        } else if (v != p->sh[chip][reg]) {
            wr(p, chip, reg, v, 0); /* buffered by the chip until the running cycle's end */
            ch->ext_counter = ch->ext_clocks = 0;
        } else if ((v & (SA_ENV_ENABLE | SA_ENV_EXTERNAL)) == (SA_ENV_ENABLE | SA_ENV_EXTERNAL)) {
            if (++ch->ext_counter >= rate) {
                ch->ext_counter = 0;
                const int shape = (v >> SA_ENV_SHAPE_SHIFT) & 7;
                const int length = (shape == 4 || shape == 5 ? 2 : 1) * (v & SA_ENV_3BIT ? 8 : 16);
                /* the address write is the clock; a one-shot shape stops one step before its end (holding its last
                 * level) because the final clock would load the buffered same value and restart it */
                if ((shape & 1) || ch->ext_clocks < length - 1) {
                    wr(p, chip, reg, v, 1);
                    ++ch->ext_clocks;
                }
            }
        }
    }
}

static void sync_pass(SaPlayer* p, int chip) {
    switch (p->sync_cmd[chip]) {
    case 0: wr(p, chip, SAC_REG_CONTROL, 0x01, 0); break;
    case 1: /* every generator restarts in phase, after this tick's frequency writes */
        wr(p, chip, SAC_REG_CONTROL, 0x03, 1);
        wr(p, chip, SAC_REG_CONTROL, 0x01, 1);
        break;
    case 2: wr(p, chip, SAC_REG_CONTROL, 0x03, 0); break; /* held: silent and frozen */
    case 3: wr(p, chip, SAC_REG_CONTROL, 0x00, 0); break; /* sound off */
    default: break;
    }
    p->sync_cmd[chip] = -1;
}

static void read_rows(SaPlayer* p, const SaaImage* image) {
    if (p->cursor_order != p->order || p->cursor_row != p->row) {
        for (int c = 0; c < p->channels; ++c) {
            SaCursor cur = {sa_pattern(image, p->order, c), 0};
            SaRow skip;
            for (int r = 0; r < p->row; ++r) sa_next_row(image->bytes, &cur, &skip);
            p->cursor[c] = cur;
        }
    }
    for (int c = 0; c < p->channels; ++c) sa_next_row(image->bytes, &p->cursor[c], &p->ch[c].row);
    p->cursor_order = p->order;
    p->cursor_row = p->row + 1;
}

/* B/D of the current row (rows already read) -> p->jump. */
static void derive_jump(SaPlayer* p, const SaaImage* image) {
    int has_b = 0, has_d = 0, b = 0, d = 0;
    for (int c = 0; c < p->channels; ++c) {
        const SaRow* row = &p->ch[c].row;
        for (int e = 0; e < row->effects; ++e) {
            if (row->code[e] == SA_FX_JUMP) has_b = 1, b = row->param[e];
            if (row->code[e] == SA_FX_BREAK) has_d = 1, d = row->param[e];
        }
    }
    p->jump = has_b || has_d;
    p->jump_order = has_b ? b : p->order + 1 < image->info.orders ? p->order + 1 : image->info.loop;
    p->jump_row = has_d ? d : 0;
}

static int row_delay(const SaRow* row) {
    int g = 0;
    for (int e = 0; e < row->effects; ++e)
        if (row->code[e] == SA_FX_DELAY) g = row->param[e];
    return g;
}

static void row_start(SaPlayer* p, const SaaImage* image) {
    read_rows(p, image);
    for (int c = 0; c < p->channels; ++c) {
        const SaRow* row = &p->ch[c].row;
        for (int e = 0; e < row->effects; ++e)
            if (row->code[e] == SA_FX_SPEED) p->speed = row->param[e];
    }
    derive_jump(p, image);
    for (int c = 0; c < p->channels; ++c) {
        SaChannel* ch = &p->ch[c];
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
 * (SaInfo.start_speed; a row-0 Fxx still applies on top, at the row start), as the SCC's. */
static void advance(SaPlayer* p, const SaaImage* image) {
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

void sap_tick(SaPlayer* p, const SaaImage* image) {
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
    }
    /* cuts (Sxx) and the audition release (sap_key_off) */
    for (int c = 0; c < p->channels; ++c) {
        if (p->ch[c].cut == p->tick) {
            p->ch[c].cut = -1;
            key_off(p, image, c);
        }
    }
    if (p->audition) {
        /* only the auditioned channel and its chip's passes */
        const int c = p->audition_channel, chip = c / SA_CHIP_CHANNELS;
        update(p, image, c);
        mixer_pass(p, chip);
        noise_pass(p, chip);
        envelope_pass(p, image, chip);
        sync_pass(p, chip);
    } else {
        for (int c = 0; c < p->channels; ++c) update(p, image, c);
        for (int chip = 0; chip < p->chips; ++chip) {
            mixer_pass(p, chip);
            noise_pass(p, chip);
            envelope_pass(p, image, chip);
            sync_pass(p, chip);
        }
    }
    for (int c = 0; c < p->channels; ++c) p->ch[c].keyon = 0;
    if (!p->audition && ++p->tick >= p->speed) {
        p->tick = 0;
        advance(p, image);
    }
}

void sap_reset(SaPlayer* p, const SaaImage* image) {
    memset(p, 0, sizeof *p);
    p->variant = image->info.variant;
    p->channels = image->info.channels;
    p->chips = image->info.chips;
    for (int c = 0; c < SA_MAX_CHANNELS; ++c) {
        SaChannel* ch = &p->ch[c];
        ch->cut = -1;
        ch->volume = SAP_VOLUME_MAX;
        ch->pan = ch->mixer = ch->noise = ch->envelope = -1;
    }
    p->sync_cmd[0] = p->sync_cmd[1] = -1;
    p->cursor_order = p->cursor_row = -1;
    p->speed = image->info.speed;
    /* every register, forced, so any VGM player starts from these registers and phases whatever its own reset does */
    for (int chip = 0; chip < p->chips; ++chip) {
        wr(p, chip, SAC_REG_CONTROL, 0x02, 1); /* sync: generators held */
        for (int l = 0; l < SA_CHIP_CHANNELS; ++l) wr(p, chip, SAC_REG_AMP + l, 0, 1);
        for (int l = 0; l < SA_CHIP_CHANNELS; ++l) wr(p, chip, SAC_REG_OFFSET + l, 0, 1);
        for (int k = 0; k < 3; ++k) wr(p, chip, SAC_REG_OCTAVE + k, 0, 1);
        wr(p, chip, SAC_REG_TONE, 0, 1);
        wr(p, chip, SAC_REG_NOISE, 0, 1);
        wr(p, chip, SAC_REG_NOISE_MODE, 0, 1);
        wr(p, chip, SAC_REG_ENV0, 0, 1);
        wr(p, chip, SAC_REG_ENV1, 0, 1);
        wr(p, chip, SAC_REG_CONTROL, 0x01, 1); /* sound on, generators released together */
    }
}

void sap_place(SaPlayer* p, const SaaImage* image, int order) {
    sap_reset(p, image);
    p->order = order;
    p->speed = image->info.start_speed[order];
}

/* Ticks per row straight play has at (order, row): the order's start speed and the Fxx of the rows before it (and of
 * the row itself once it has started). */
static int speed_at(const SaaImage* image, int order, int row, int started) {
    int s = image->info.start_speed[order];
    SaCursor cur[SA_MAX_CHANNELS];
    for (int c = 0; c < image->info.channels; ++c) cur[c] = (SaCursor){sa_pattern(image, order, c), 0};
    const int last = started ? row : row - 1;
    for (int r = 0; r <= last; ++r) {
        for (int c = 0; c < image->info.channels; ++c) {
            SaRow x;
            sa_next_row(image->bytes, &cur[c], &x);
            for (int e = 0; e < x.effects; ++e)
                if (x.code[e] == SA_FX_SPEED) s = x.param[e];
        }
    }
    return s;
}

void sap_relocate(SaPlayer* p, const SaaImage* old, const SaaImage* next) {
    if (p->audition) return;
    const SaInfo* info = &next->info;
    /* sounding notes keep their slot and read it from the new image from the next tick (update cuts a note whose
     * slot is gone) */
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
        SaRow row;
        sa_read_row(next, p->order, c, p->row, &row, NULL);
        SaChannel* ch = &p->ch[c];
        ch->row = row;
        if (ch->pending) {
            const int g = row_delay(&row);
            ch->pending = g >= p->tick;
            ch->delay = g;
        }
    }
    derive_jump(p, next);
}

int sap_audition(SaPlayer* p, const SaaImage* image, int channel, int note, int slot, int fine) {
    if (channel < 0 || channel >= image->info.channels || note < 0 || note >= SA_NOTES || fine < -128 || fine > 127)
        return -1;
    if (slot < 1 || slot > SA_MAX_INSTRUMENTS || !defined(image, slot)) return -1;
    sap_reset(p, image);
    p->audition = 1;
    p->audition_channel = channel;
    p->ch[channel].instrument = slot;
    key_on(p, image, channel, note); /* its first tick is the key-on tick */
    p->ch[channel].fine = fine;
    return 0;
}

void sap_key_off(SaPlayer* p, int channel) {
    p->writes = 0;
    /* the key-off itself runs at the next tick's cut step (it needs the image for the release point) */
    if (channel >= 0 && channel < p->channels) p->ch[channel].cut = p->tick;
}

int sap_silent(const SaPlayer* p, const SaaImage* image, int channel) {
    if (channel < 0 || channel >= p->channels) return 1;
    const SaChannel* ch = &p->ch[channel];
    if (!ch->active || !ch->keyed) return 1;
    if (!ch->released) return 0;
    SaEnvelope e;
    sa_envelope(image, ch->slot, SA_I_VOLUME, &e);
    if (!e.length) return 0;
    /* released: the index runs to the end and holds there; silent once the held value has been written as 0 */
    const int index = ch->env[ENV_VOLUME] < e.length ? ch->env[ENV_VOLUME] : e.length - 1;
    const int chip = channel / SA_CHIP_CHANNELS, l = channel % SA_CHIP_CHANNELS;
    return index == e.length - 1 && amplitude(ch, sa_instrument(image, ch->slot), e.values[e.length - 1]) == 0 &&
           p->sh[chip][SAC_REG_AMP + l] == 0;
}

/* ---- loop detection ------------------------------------------------------------------------------------------- */

static int same_channel(const SaChannel* a, const SaChannel* b) {
    /* not compared: row, pending, delay, cut (tick 0 rewrites them before reading them) */
    for (int k = 0; k < ENVS; ++k)
        if (a->env[k] != b->env[k]) return 0;
    return a->instrument == b->instrument && a->slot == b->slot && a->active == b->active && a->keyed == b->keyed &&
           a->released == b->released && a->accumulator == b->accumulator && a->base == b->base &&
           a->target == b->target && a->fine == b->fine && a->arp == b->arp && a->arp_step == b->arp_step &&
           a->slide_mode == b->slide_mode && a->slide_speed == b->slide_speed && a->slide == b->slide &&
           a->vib_speed == b->vib_speed && a->vib_depth == b->vib_depth && a->vib_phase == b->vib_phase &&
           a->volume == b->volume && a->volslide == b->volslide && a->pan == b->pan && a->mixer == b->mixer &&
           a->noise == b->noise && a->envelope == b->envelope && a->keyon == b->keyon && a->mix == b->mix &&
           a->last_mix == b->last_mix && a->last_noise == b->last_noise && a->ext_counter == b->ext_counter &&
           a->ext_clocks == b->ext_clocks;
}

int sap_same_state(const SaPlayer* a, const SaPlayer* b) {
    /* not compared: writes and write[] (the last batch), cursor[], cursor_order/row and sync_cmd (transient) */
    if (a->variant != b->variant || a->channels != b->channels || a->chips != b->chips || a->order != b->order ||
        a->row != b->row || a->tick != b->tick || a->speed != b->speed || a->jump != b->jump ||
        (a->jump && (a->jump_order != b->jump_order || a->jump_row != b->jump_row)) || a->audition != b->audition ||
        a->audition_channel != b->audition_channel)
        return 0;
    for (int chip = 0; chip < 2; ++chip)
        for (int g = 0; g < 2; ++g)
            if (a->noise_value[chip][g] != b->noise_value[chip][g]) return 0;
    if (memcmp(a->sh, b->sh, sizeof a->sh)) return 0;
    for (int c = 0; c < SA_MAX_CHANNELS; ++c)
        if (!same_channel(&a->ch[c], &b->ch[c])) return 0;
    return 1;
}
