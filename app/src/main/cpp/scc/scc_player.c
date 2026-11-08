/* Thor Tracker's SCC player (scc_player.h). Specification: docs/AUDIO_ENGINE.md, "SCC and SCC+: Player".
 * Every rule here has a counterpart in tools/audio/scc/scc_ref.py; change both and the spec together. The sequencing
 * (rows, F/B/D/G/S, jumps, loop speed, relocation) is the OPL3 player's (opl3/opl3_player.c). */
#include "scc_player.h"

#include <string.h>

#include "generated/scc_tables.h"
#include "scc_k051649.h"

_Static_assert((-1 >> 1) == -1, "the vibrato offset uses an arithmetic (floor) right shift");
_Static_assert(SC_CHANNELS == SCK_CHANNELS && SC_WAVE_BYTES == SCK_WAVE_BYTES, "image and core agree");

/* Envelopes in ScChannel.env order. */
enum { ENV_VOLUME, ENV_ARPEGGIO, ENV_PITCH, ENV_WAVE, ENVS };
static const int ENV_FIELD[ENVS] = {SC_I_VOLUME, SC_I_ARPEGGIO, SC_I_PITCH, SC_I_WAVE};

#define PITCH_SPAN 6144 /* slide / relative pitch accumulator limit, pitch units */

static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

static int plus(const ScPlayer* p) { return p->variant == SC_VARIANT_PLUS; }

/* Wave RAMs of the variant: the SCC has four (channels 4 and 5 share RAM 3), the SCC+ five. */
static int rams(const ScPlayer* p) { return plus(p) ? SC_CHANNELS : 4; }

static int defined(const SccImage* image, int slot) { return sc_instrument_flags(image, slot) & SC_FLAG_DEFINED; }

/* ---- writes ---------------------------------------------------------------------------------------------------- */

static void put(ScPlayer* p, int port, int reg, int val) {
    if (p->writes >= SCP_MAX_WRITES) return;
    ScWrite* w = &p->write[p->writes++];
    w->port = (uint8_t)port;
    w->reg = (uint8_t)reg;
    w->val = (uint8_t)val;
}

static void wr_test(ScPlayer* p, int val, int forced) {
    if (!forced && p->sh_test == (uint8_t)val) return;
    p->sh_test = (uint8_t)val;
    put(p, SCK_PORT_TEST, 0, val);
}

static void wr_key(ScPlayer* p, int val, int forced) {
    if (!forced && p->sh_key == (uint8_t)val) return;
    p->sh_key = (uint8_t)val;
    put(p, SCK_PORT_KEY, 0, val);
}

static void wr_period(ScPlayer* p, int reg, int val, int forced) {
    if (!forced && p->sh_period[reg] == (uint8_t)val) return;
    p->sh_period[reg] = (uint8_t)val;
    put(p, SCK_PORT_PERIOD, reg, val);
}

static void wr_volume(ScPlayer* p, int c, int val, int forced) {
    if (!forced && p->sh_volume[c] == (uint8_t)val) return;
    p->sh_volume[c] = (uint8_t)val;
    put(p, SCK_PORT_VOLUME, c, val);
}

/* Wave RAM k byte i; never made while the test register (as the chip holds it) protects the RAM. */
static int ram_protected(const ScPlayer* p, int k) {
    if (p->sh_test & SCK_TEST_FREEZE) return 1;
    return !plus(p) && k == 3 && (p->sh_test & SCK_TEST_FREEZE4);
}

static void wr_wave(ScPlayer* p, int k, int i, int val, int forced) {
    if (!forced && p->sh_ram[k][i] == (int8_t)val) return;
    p->sh_ram[k][i] = (int8_t)val;
    put(p, plus(p) ? SCK_PORT_WAVE_PLUS : SCK_PORT_WAVE, SC_WAVE_BYTES * k + i, (uint8_t)val);
}

/* ---- pitch ----------------------------------------------------------------------------------------------------- */

int scp_period(int pitch) {
    pitch = clamp(pitch, 0, SCP_PITCH_MAX);
    const int o = pitch / 768, f = pitch % 768;
    const int period = (int)((SC_DIV[f] + (1u << (7 + o))) >> (8 + o)) - 1;
    return period > SCP_PERIOD_MAX ? SCP_PERIOD_MAX : period;
}

/* ---- notes ----------------------------------------------------------------------------------------------------- */

/* Wave claims of one tick (SCC channels 4 and 5: index 0, 1), resolved and dropped by that tick's wave pass. Song
 * key-ons happen in the tick whose pass resolves them. scp_audition's key-on happens outside a tick, but it follows a
 * reset (the channel's last-played wave is 0) and the first update of the note plays a wave 1..64, which is a claim
 * of its own: the same owner at the same pass. */
typedef struct Claims {
    int c[2];
} Claims;

static void claim(int* claims, const ScPlayer* p, int c) {
    if (!plus(p) && c >= 3) claims[c - 3] = 1;
}

static void key_on(ScPlayer* p, const SccImage* image, int c, int note, Claims* claims) {
    ScChannel* ch = &p->ch[c];
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
    ch->force = 1;
    ch->restart = (sc_instrument_flags(image, ch->slot) & SC_FLAG_RESTART) != 0;
    claim(claims->c, p, c);
}

/* OFF, Sxx, the audition release: a note with a release point not yet passed is released, otherwise cut. */
static void key_off(ScPlayer* p, const SccImage* image, int c) {
    ScChannel* ch = &p->ch[c];
    if (!ch->keyed) return;
    if (ch->active && !ch->released) {
        ScEnvelope e;
        sc_envelope(image, ch->slot, SC_I_VOLUME, &e);
        if (e.length && e.release >= 0) {
            ch->released = 1;
            ch->env[ENV_VOLUME] = e.release;
            return;
        }
    }
    ch->keyed = 0;
}

static void set_test(ScPlayer* p, int value) {
    const int changed = (p->test ^ value) & (SCK_TEST_FREQ4 | SCK_TEST_FREQ8);
    p->test = value;
    wr_test(p, value, 0);
    if (!changed) return;
    /* the new frequency mode applies when a period is written: rewrite every sounding period now */
    for (int c = 0; c < SC_CHANNELS; ++c)
        if (p->ch[c].active) p->ch[c].force = 1;
}

static void process_row(ScPlayer* p, const SccImage* image, int c, Claims* claims) {
    ScChannel* ch = &p->ch[c];
    const ScRow* row = &ch->row;
    const int has_note = row->note >= 0 && row->note < SC_NOTES;
    if (has_note) {
        ch->arp = 0;
        ch->fine = 0;
        ch->wave_lock = 0;
    }
    if (row->instrument) ch->instrument = row->instrument;
    for (int e = 0; e < row->effects; ++e) {
        const int v = row->param[e], x = v >> 4, y = v & 15;
        switch (row->code[e]) {
        case SC_FX_ARPEGGIO: ch->arp = v; ch->arp_step = 0; break;
        case SC_FX_SLIDE_UP: ch->slide_mode = v ? 1 : 0; ch->slide_speed = v; break;
        case SC_FX_SLIDE_DOWN: ch->slide_mode = v ? 2 : 0; ch->slide_speed = v; break;
        case SC_FX_PORTAMENTO: ch->slide_mode = v ? 3 : 0; ch->slide_speed = v; break;
        case SC_FX_VIBRATO: ch->vib_speed = x; ch->vib_depth = y; break;
        case SC_FX_VOLUME_SLIDE: ch->volslide = v; break;
        case SC_FX_VOLUME: ch->volume = v * 16; break;
        case SC_FX_FINE: ch->fine = v - 0x80; break;
        case SC_FX_CUT: ch->cut = v; break;
        case SC_FX_TEST: set_test(p, v); break;
        case SC_FX_WAVE: ch->wave_lock = v; break;
        default: break; /* B, D, F, G: the row start's */
        }
    }
    if (row->note == SC_NOTE_OFF) {
        key_off(p, image, c);
    } else if (has_note) {
        if (ch->slide_mode == 3 && ch->keyed) ch->target = 64 * row->note;
        else key_on(p, image, c, row->note, claims);
    }
}

/* ---- per tick -------------------------------------------------------------------------------------------------- */

/* The envelope's value index, moved to its last value if a live edit shortened it. */
static int env_index(ScChannel* ch, int k, const ScEnvelope* e) {
    if (ch->env[k] >= e->length) ch->env[k] = e->length - 1;
    return ch->env[k];
}

static void env_advance(ScChannel* ch, int k, const ScEnvelope* e) {
    if (!e->length) return;
    const int end = !ch->released && e->release >= 0 ? e->release : e->length;
    if (ch->env[k] + 1 < end) ++ch->env[k];
    else if (e->loop >= 0 && !ch->released) ch->env[k] = e->loop;
    else ch->env[k] = end - 1;
}

static int channel_volume(const ScChannel* ch, int e) {
    const int cv = ch->volume / 16;
    int v = e * cv / 15;
    if (!v && e > 0 && cv > 0) v = 1;
    return v;
}

static void update(ScPlayer* p, const SccImage* image, int c, Claims* claims) {
    ScChannel* ch = &p->ch[c];
    if (!ch->active) return;
    const int flags = sc_instrument_flags(image, ch->slot);
    ScEnvelope env[ENVS];
    int value[ENVS] = {15, 0, 0, 0};
    for (int k = 0; k < ENVS; ++k) {
        sc_envelope(image, ch->slot, ENV_FIELD[k], &env[k]);
        if (!env[k].length) continue;
        const int raw = env[k].values[env_index(ch, k, &env[k])];
        value[k] = k == ENV_ARPEGGIO || k == ENV_PITCH ? (int8_t)raw : raw;
    }
    const int w = ch->wave_lock ? ch->wave_lock : value[ENV_WAVE];
    int q = 4 * value[ENV_PITCH];
    if (flags & SC_FLAG_PITCH_RELATIVE) q = ch->accumulator = clamp(ch->accumulator + q, -PITCH_SPAN, PITCH_SPAN);
    int arp = 0;
    if (ch->arp && ch->arp_step) arp = ch->arp_step == 1 ? ch->arp >> 4 : ch->arp & 15;
    const int vib = ch->vib_depth ? (SC_SINE[ch->vib_phase] * ch->vib_depth) >> 4 : 0;
    const int pitch = clamp(ch->base + 64 * (value[ENV_ARPEGGIO] + arp) + ch->fine + ch->slide + vib + q, 0, SCP_PITCH_MAX);
    const int period = scp_period(pitch);
    if (ch->restart) wr_test(p, p->test | SCK_TEST_RESTART, 0); /* RESTART: the period write resets the position */
    wr_period(p, 2 * c, period & 0xFF, ch->force);
    wr_period(p, 2 * c + 1, period >> 8, ch->force);
    if (ch->restart) wr_test(p, p->test, 0);
    ch->force = ch->restart = 0;
    wr_volume(p, c, channel_volume(ch, value[ENV_VOLUME]), 0);
    if (w != ch->wave) claim(claims->c, p, c);
    ch->wave = w;

    for (int k = 0; k < ENVS; ++k) env_advance(ch, k, &env[k]);
    if (ch->arp) ch->arp_step = (ch->arp_step + 1) % 3;
    if (ch->slide_mode == 1) ch->slide = clamp(ch->slide + ch->slide_speed, -PITCH_SPAN, PITCH_SPAN);
    if (ch->slide_mode == 2) ch->slide = clamp(ch->slide - ch->slide_speed, -PITCH_SPAN, PITCH_SPAN);
    if (ch->slide_mode == 3) {
        if (ch->base < ch->target) ch->base = clamp(ch->base + ch->slide_speed, ch->base, ch->target);
        else if (ch->base > ch->target) ch->base = clamp(ch->base - ch->slide_speed, ch->target, ch->base);
    }
    if (ch->vib_depth && ch->vib_speed) ch->vib_phase = (ch->vib_phase + ch->vib_speed) & 63;
    if (ch->volslide) ch->volume = clamp(ch->volume + (ch->volslide >> 4) - (ch->volslide & 15), 0, SCP_VOLUME_MAX);
}

/* The SCC's RAM 3 goes to the latest claimant (channel 5 wins a tie within a tick); then every RAM with an active
 * owner receives the bytes of the owner's wave that differ from the shadow. */
static void wave_pass(ScPlayer* p, const SccImage* image, Claims* claims) {
    if (!plus(p)) {
        if (claims->c[1]) p->owner3 = 4;
        else if (claims->c[0]) p->owner3 = 3;
    }
    claims->c[0] = claims->c[1] = 0;
    for (int k = 0; k < rams(p); ++k) {
        const int owner = plus(p) || k < 3 ? k : p->owner3;
        if (owner < 0 || !p->ch[owner].active || ram_protected(p, k)) continue;
        const int8_t* wave = sc_wave(image, p->ch[owner].wave);
        for (int i = 0; i < SC_WAVE_BYTES; ++i) wr_wave(p, k, i, wave[i], 0);
    }
}

static void read_rows(ScPlayer* p, const SccImage* image) {
    if (p->cursor_order != p->order || p->cursor_row != p->row) {
        for (int c = 0; c < SC_CHANNELS; ++c) {
            ScCursor cur = {sc_u16(image->bytes + SC_OFF_ORDERS + 2 * (SC_CHANNELS * p->order + c)), 0};
            ScRow skip;
            for (int r = 0; r < p->row; ++r) sc_next_row(image->bytes, &cur, &skip);
            p->cursor[c] = cur;
        }
    }
    for (int c = 0; c < SC_CHANNELS; ++c) sc_next_row(image->bytes, &p->cursor[c], &p->ch[c].row);
    p->cursor_order = p->order;
    p->cursor_row = p->row + 1;
}

/* B/D of the current row (rows already read) -> p->jump. */
static void derive_jump(ScPlayer* p, const SccImage* image) {
    int has_b = 0, has_d = 0, b = 0, d = 0;
    for (int c = 0; c < SC_CHANNELS; ++c) {
        const ScRow* row = &p->ch[c].row;
        for (int e = 0; e < row->effects; ++e) {
            if (row->code[e] == SC_FX_JUMP) has_b = 1, b = row->param[e];
            if (row->code[e] == SC_FX_BREAK) has_d = 1, d = row->param[e];
        }
    }
    p->jump = has_b || has_d;
    p->jump_order = has_b ? b : p->order + 1 < image->info.orders ? p->order + 1 : image->info.loop;
    p->jump_row = has_d ? d : 0;
}

static int row_delay(const ScRow* row) {
    int g = 0;
    for (int e = 0; e < row->effects; ++e)
        if (row->code[e] == SC_FX_DELAY) g = row->param[e];
    return g;
}

static void row_start(ScPlayer* p, const SccImage* image, Claims* claims) {
    read_rows(p, image);
    for (int c = 0; c < SC_CHANNELS; ++c) {
        const ScRow* row = &p->ch[c].row;
        for (int e = 0; e < row->effects; ++e)
            if (row->code[e] == SC_FX_SPEED) p->speed = row->param[e];
    }
    derive_jump(p, image);
    for (int c = 0; c < SC_CHANNELS; ++c) {
        ScChannel* ch = &p->ch[c];
        ch->cut = -1;
        ch->pending = 0;
        const int g = row_delay(&ch->row);
        if (g) {
            ch->pending = 1;
            ch->delay = g;
        } else {
            process_row(p, image, c, claims);
        }
    }
}

/* Next row. Every arrival at the loop order's row 0 starts it at the speed sequential straight play reaches it with
 * (ScInfo.start_speed; a row-0 Fxx still applies on top, at the row start), as the OPL3's. */
static void advance(ScPlayer* p, const SccImage* image) {
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

void scp_tick(ScPlayer* p, const SccImage* image) {
    Claims claims = {{0, 0}};
    p->writes = 0;
    if (!p->audition) {
        if (p->tick == 0) {
            row_start(p, image, &claims);
        } else {
            for (int c = 0; c < SC_CHANNELS; ++c) {
                if (p->ch[c].pending && p->ch[c].delay == p->tick) {
                    p->ch[c].pending = 0;
                    process_row(p, image, c, &claims);
                }
            }
        }
    }
    /* cuts (Sxx) and the audition release (scp_key_off) */
    for (int c = 0; c < SC_CHANNELS; ++c) {
        if (p->ch[c].cut == p->tick) {
            p->ch[c].cut = -1;
            key_off(p, image, c);
        }
    }
    for (int c = 0; c < SC_CHANNELS; ++c) update(p, image, c, &claims);
    wave_pass(p, image, &claims);
    int key = 0;
    for (int c = 0; c < SC_CHANNELS; ++c) key |= p->ch[c].keyed << c;
    wr_key(p, key, 0);
    if (!p->audition && ++p->tick >= p->speed) {
        p->tick = 0;
        advance(p, image);
    }
}

void scp_reset(ScPlayer* p, const SccImage* image) {
    memset(p, 0, sizeof *p);
    p->variant = image->info.variant;
    for (int c = 0; c < SC_CHANNELS; ++c) {
        p->ch[c].cut = -1;
        p->ch[c].volume = SCP_VOLUME_MAX;
    }
    p->owner3 = -1;
    p->cursor_order = p->cursor_row = -1;
    p->speed = image->info.speed;
    /* every register, forced, so any VGM player starts from these whatever its own reset does */
    wr_test(p, 0, 1);
    wr_key(p, 0, 1);
    for (int c = 0; c < SC_CHANNELS; ++c) {
        wr_period(p, 2 * c, 0, 1);
        wr_period(p, 2 * c + 1, 0, 1);
    }
    for (int c = 0; c < SC_CHANNELS; ++c) wr_volume(p, c, 0, 1);
    for (int k = 0; k < rams(p); ++k)
        for (int i = 0; i < SC_WAVE_BYTES; ++i) wr_wave(p, k, i, 0, 1);
}

void scp_place(ScPlayer* p, const SccImage* image, int order) {
    scp_reset(p, image);
    p->order = order;
    p->speed = image->info.start_speed[order];
}

/* Ticks per row straight play has at (order, row): the order's start speed and the Fxx of the rows before it (and of
 * the row itself once it has started). */
static int speed_at(const SccImage* image, int order, int row, int started) {
    int s = image->info.start_speed[order];
    ScCursor cur[SC_CHANNELS];
    for (int c = 0; c < SC_CHANNELS; ++c)
        cur[c] = (ScCursor){sc_u16(image->bytes + SC_OFF_ORDERS + 2 * (SC_CHANNELS * order + c)), 0};
    const int last = started ? row : row - 1;
    for (int r = 0; r <= last; ++r) {
        for (int c = 0; c < SC_CHANNELS; ++c) {
            ScRow x;
            sc_next_row(image->bytes, &cur[c], &x);
            for (int e = 0; e < x.effects; ++e)
                if (x.code[e] == SC_FX_SPEED) s = x.param[e];
        }
    }
    return s;
}

void scp_relocate(ScPlayer* p, const SccImage* old, const SccImage* next) {
    if (p->audition) return;
    const ScInfo* info = &next->info;
    /* rule (4): sounding notes keep their slot and read it from the new image; a slot that vanished cuts its note */
    for (int c = 0; c < SC_CHANNELS; ++c) {
        ScChannel* ch = &p->ch[c];
        if (ch->active && !defined(next, ch->slot)) ch->active = ch->keyed = 0;
    }
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
        for (int c = 0; c < SC_CHANNELS; ++c) p->ch[c].pending = 0;
        p->speed = speed_at(next, p->order, p->row, 1);
        if (!started) advance(p, next); /* the clamped row counts as played: the next row follows it */
        return;
    }
    const int new_speed = speed_at(next, p->order, p->row, started);
    if (new_speed != old_speed) p->speed = new_speed;
    if (!started) return;
    /* The row has started: its jump and any still-delayed rows come from the new data. */
    for (int c = 0; c < SC_CHANNELS; ++c) {
        ScRow row;
        sc_read_row(next, p->order, c, p->row, &row, NULL);
        ScChannel* ch = &p->ch[c];
        ch->row = row;
        if (ch->pending) {
            const int g = row_delay(&row);
            ch->pending = g >= p->tick;
            ch->delay = g;
        }
    }
    derive_jump(p, next);
}

int scp_audition(ScPlayer* p, const SccImage* image, int channel, int note, int slot, int fine) {
    if (channel < 0 || channel >= SC_CHANNELS || note < 0 || note >= SC_NOTES || fine < -128 || fine > 127) return -1;
    if (slot < 1 || slot > SC_MAX_INSTRUMENTS || !defined(image, slot)) return -1;
    scp_reset(p, image);
    p->audition = 1;
    Claims claims = {{0, 0}}; /* resolved by the first tick's update (see above) */
    p->ch[channel].instrument = slot;
    key_on(p, image, channel, note, &claims);
    p->ch[channel].fine = fine;
    return 0;
}

void scp_key_off(ScPlayer* p, int channel) {
    p->writes = 0;
    /* the key-off itself runs at the next tick's cut step (it needs the image for the release point); it writes
     * nothing before then: the key register follows at that tick's end */
    if (channel >= 0 && channel < SC_CHANNELS) p->ch[channel].cut = p->tick;
}

int scp_silent(const ScPlayer* p, const SccImage* image, int channel) {
    if (channel < 0 || channel >= SC_CHANNELS) return 1;
    const ScChannel* ch = &p->ch[channel];
    if (!ch->active || !ch->keyed) return 1;
    if (!ch->released) return 0;
    ScEnvelope e;
    sc_envelope(image, ch->slot, SC_I_VOLUME, &e);
    if (!e.length) return 0;
    /* released: the index runs to the end and holds there; silent once the held value has been written as 0 */
    const int index = ch->env[ENV_VOLUME] < e.length ? ch->env[ENV_VOLUME] : e.length - 1;
    return index == e.length - 1 && channel_volume(ch, e.values[e.length - 1]) == 0 && p->sh_volume[channel] == 0;
}
