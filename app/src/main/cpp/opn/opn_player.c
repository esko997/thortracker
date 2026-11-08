/* Thor Tracker's YM2203 / YM2608 player (opn_player.h). Specification: docs/AUDIO_ENGINE.md, "OPN and OPNA: player".
 * A port of the YM2612 player (ym/ym_player.c) to the OPN family: the same sequencing, effects and FM voice handling,
 * plus the SSG (software envelopes, mixer, shared noise and hardware envelope), FM 3's special and CSM modes and the
 * YM2608's rhythm and ADPCM-B parts. */
#include "opn_player.h"

#include <math.h>
#include <pthread.h>
#include <string.h>

_Static_assert((-1 >> 1) == -1, "the vibrato offset uses an arithmetic (floor) right shift");

const uint8_t OPNP_OP_OFFSET[4] = {0, 8, 4, 12};
/* Carrier operators (bit k = operator k + 1) per algorithm. */
static const uint8_t CARRIERS[8] = {0x8, 0x8, 0x8, 0x8, 0xA, 0xE, 0xE, 0xF};
static const uint8_t OP_REG[OPN_OP_BYTES] = {0x30, 0x40, 0x50, 0x60, 0x70, 0x80, 0x90};
/* FM 3 special / CSM frequency registers (high byte) of operators 1..4; the low byte is 4 below. */
static const uint8_t FM3_HI[4] = {0xAD, 0xAE, 0xAC, 0xA6};
/* Vibrato: floor(127 sin(2 pi i / 64) + 0.5) (the YM2612 player's table, generated/ym_tables.h YM_SINE). */
static const int8_t SINE[64] = {
       0,   12,   25,   37,   49,   60,   71,   81,   90,   98,  106,  112,  117,  122,  125,  126,
     127,  126,  125,  122,  117,  112,  106,   98,   90,   81,   71,   60,   49,   37,   25,   12,
       0,  -12,  -25,  -37,  -49,  -60,  -71,  -81,  -90,  -98, -106, -112, -117, -122, -125, -126,
    -127, -126, -125, -122, -117, -112, -106,  -98,  -90,  -81,  -71,  -60,  -49,  -37,  -25,  -12,
};

/* ---- pitch tables ----------------------------------------------------------------------------------------------- */

/* POW2[i] = round(2^(i / 768) x 2^24): one octave in pitch units (1/64 semitone). */
static uint32_t POW2[768];
static pthread_once_t tables_once = PTHREAD_ONCE_INIT;

static void build_tables(void) {
    for (int i = 0; i < 768; ++i) POW2[i] = (uint32_t)llround(exp2(i / 768.0) * 16777216.0);
}

void opn_tables_init(void) { pthread_once(&tables_once, build_tables); }

/* Pitch units u (0 = C-0) are 13.75 Hz x 2^((u + 192) / 768): A-4 (u = 3648) is 440 Hz. */
#define PITCH_A_OFFSET 192
/* FM: fnum = f x 2^21 / 2^block / native rate = 13.75 x 2^21 x 72 / 3993600 x 2^((frac + 192) / 768). */
#define FM_K 2076180480ull /* 13.75 x 2^21 x 72 */
/* SSG: TP = 124800 / f = (124800 x 4 / 55) / 2^((u + 192) / 768). */
#define SSG_K 499200ull
/* ADPCM-B: delta-N = rate x 65536 / native rate x 2^(d / 768) = rate x 65536 x 72 / 3993600 x 2^(d / 768). */
#define ADPCM_K 4718592ull /* 65536 x 72 */

static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

static void fm_frequency(int units, int* hi, int* lo) {
    const int block = units / 768, y = units % 768 + PITCH_A_OFFSET;
    const uint64_t num = (FM_K * POW2[y % 768]) << (y / 768);
    const uint64_t den = (uint64_t)OPN_NATIVE_RATE_NUM << 24;
    const int fnum = (int)((num + den / 2) / den);
    *hi = block << 3 | fnum >> 8;
    *lo = fnum & 0xFF;
}

static int ssg_period(int units) {
    const int x = units + PITCH_A_OFFSET;
    const uint64_t num = SSG_K << 24;
    const uint64_t den = (55ull * POW2[x % 768]) << (x / 768);
    return clamp((int)((num + den / 2) / den), 1, 4095);
}

static int adpcm_delta(uint32_t rate, int root, int units) {
    const int d = units - root * 64;
    const int oct = d >= 0 ? d / 768 : -((767 - d) / 768);
    const int frac = d - oct * 768;
    const uint64_t num = (uint64_t)rate * ADPCM_K * POW2[frac];
    uint64_t den = (uint64_t)OPN_NATIVE_RATE_NUM << 24;
    den = oct >= 0 ? den >> oct : den << -oct;
    const uint64_t n = (num + den / 2) / den;
    return n < 1 ? 1 : n > 65535 ? 65535 : (int)n;
}

/* `extra`: the SSG envelopes' arpeggio and pitch offset (pitch units). */
static int pitch(const OpnChannel* ch, int extra) {
    int arp = 0;
    if (ch->arp && ch->arp_step) arp = ch->arp_step == 1 ? ch->arp >> 4 : ch->arp & 15;
    const int vib = ch->vib_depth ? (SINE[ch->vib_phase] * ch->vib_depth) >> 4 : 0;
    return clamp(ch->base + arp * 64 + ch->fine + ch->slide + vib + ch->sweep + extra, 0, OPNP_PITCH_MAX);
}

/* ---- writes ----------------------------------------------------------------------------------------------------- */

static int opna(const OpnPlayer* p) { return p->chip == OPN_CHIP_YM2608; }
static int part_of(const OpnPlayer* p, int c) { return opn_part(p->chip, c); }
static int fm_port(int c) { return c / 3; }
static int fm_reg(int base, int c) { return base + c % 3; }
static int key_select(int c) { return (c / 3) * 4 + c % 3; }

static void emit(OpnPlayer* p, int port, int reg, int val) {
    if (p->writes < OPNP_MAX_WRITES) p->write[p->writes++] = (OpnWrite){(uint8_t)port, (uint8_t)reg, (uint8_t)val};
}

/* A diffed write. */
static void wr(OpnPlayer* p, int port, int reg, int val) {
    if (p->reg[port][reg] == (uint8_t)val) return;
    p->reg[port][reg] = (uint8_t)val;
    emit(p, port, reg, val);
}

/* A write that always reaches the chip (key, envelope restart, init). */
static void wr_force(OpnPlayer* p, int port, int reg, int val) {
    p->reg[port][reg] = (uint8_t)val;
    emit(p, port, reg, val);
}

/* A frequency pair, high byte first, both when either differs (the chip latches the high byte until the low one). */
static void wr_pair(OpnPlayer* p, int port, int hi_reg, int hi, int lo) {
    const int lo_reg = hi_reg - 4;
    if (p->reg[port][hi_reg] == (uint8_t)hi && p->reg[port][lo_reg] == (uint8_t)lo) return;
    p->reg[port][hi_reg] = (uint8_t)hi;
    p->reg[port][lo_reg] = (uint8_t)lo;
    emit(p, port, hi_reg, hi);
    emit(p, port, lo_reg, lo);
}

/* A key-off phase write. */
static void push_off(OpnPlayer* p, int port, int reg, int val) {
    p->reg[port][reg] = (uint8_t)val;
    if (p->pending_offs < (int)(sizeof p->off / sizeof p->off[0]))
        p->off[p->pending_offs++] = (OpnWrite){(uint8_t)port, (uint8_t)reg, (uint8_t)val};
}

static void begin_batch(OpnPlayer* p) { p->writes = p->offs = p->pending_offs = 0; }

/* Puts the key-off phase in front of the batch. */
static void end_batch(OpnPlayer* p) {
    const int n = p->pending_offs;
    if (p->writes + n > OPNP_MAX_WRITES) p->writes = OPNP_MAX_WRITES - n;
    memmove(p->write + n, p->write, sizeof p->write[0] * (size_t)p->writes);
    memcpy(p->write, p->off, sizeof p->off[0] * (size_t)n);
    p->writes += n;
    p->offs = n;
    p->pending_offs = 0;
}

/* $27: FM 3's mode; in CSM mode $81 (CSM, timer A loaded) while FM 3 holds a note, else $40. */
static int mode_reg(const OpnPlayer* p) {
    if (p->fm3_mode == OPN_FM3_NORMAL) return 0x00;
    if (p->fm3_mode == OPN_FM3_CSM && p->ch[OPN_CH_FM3].keyed) return 0x81;
    return 0x40;
}

static int csm_fm3(const OpnPlayer* p, int c) { return c == OPN_CH_FM3 && p->fm3_mode == OPN_FM3_CSM; }

static void write_timer_a(OpnPlayer* p) {
    wr(p, 0, 0x24, p->timer_a >> 2);
    wr(p, 0, 0x25, p->timer_a & 3);
}

/* ---- notes ------------------------------------------------------------------------------------------------------ */

static int tl_out(const OpnChannel* ch, int k) {
    const int tl = ch->ops[k][1];
    if (!(ch->carriers >> k & 1)) return tl;
    const int v = tl + 127 - (ch->vol8 >> 3);
    return v > 127 ? 127 : v;
}

static void key_off(OpnPlayer* p, int c) {
    OpnChannel* ch = &p->ch[c];
    if (!ch->keyed) return;
    ch->keyed = 0;
    ch->key_pending = 0;
    switch (part_of(p, c)) {
    case OPN_PART_FM:
        if (!csm_fm3(p, c)) push_off(p, 0, 0x28, key_select(c)); /* CSM: $27 = $40 at the tick's end */
        break;
    case OPN_PART_SSG: {
        const OpnEnvelope* vol = &p->env[c - p->fm_channels][OPNP_ENV_VOL];
        if (vol->length && vol->release >= 0) {
            ch->pos[OPNP_ENV_VOL] = vol->release;
            ch->phase = OPNP_SSG_RELEASED;
        } else {
            ch->phase = OPNP_SSG_OFF;
        }
        break;
    }
    case OPN_PART_RHYTHM: push_off(p, 0, 0x10, 0x80 | 1 << (c - OPNA_CH_RHYTHM)); break;
    default: push_off(p, 1, 0x00, 0x01); break; /* ADPCM-B: reset (stop) */
    }
}

void opnp_key_off(OpnPlayer* p, int channel) {
    begin_batch(p);
    if (channel >= 0 && channel < p->channels) key_off(p, channel);
    end_batch(p);
}

static void start_pitch(OpnChannel* ch, const uint8_t* rec, int note) {
    ch->base = ch->target = note * 64;
    ch->slide = 0;
    ch->arp_step = 0;
    ch->vib_phase = 0;
    const int speed = rec[OPN_I_SWEEP_SPEED];
    ch->sweep = speed ? (int8_t)rec[OPN_I_SWEEP_START] * 64 : 0;
    ch->sweep_end = speed ? (int8_t)rec[OPN_I_SWEEP_END] * 64 : 0;
    ch->sweep_step = speed * 4;
}

static int row_has(const OpnRow* row, int code) {
    for (int e = 0; row && e < row->effects; ++e)
        if (row->code[e] == code) return 1;
    return 0;
}

static void key_on_fm(OpnPlayer* p, OpnChannel* ch, int c, const uint8_t* rec, const OpnRow* row) {
    key_off(p, c);
    ch->alg = rec[OPN_I_ALG];
    ch->feedback = rec[OPN_I_FEEDBACK];
    ch->ams_fms = rec[OPN_I_AMS_FMS];
    ch->carriers = CARRIERS[ch->alg];
    memcpy(ch->ops, rec + OPN_I_OPS, sizeof ch->ops);
    for (int k = 0; k < 4; ++k) ch->ch3[k] = (int16_t)opn_u16(rec + OPN_I_CH3 + 2 * k);
    for (int e = 0; row && e < row->effects; ++e) {
        const int v = row->param[e], x = v >> 4, y = v & 15;
        switch (row->code[e]) {
        case OPN_FX_FEEDBACK: ch->feedback = v; break;
        case OPN_FX_BRIGHTNESS:
            for (int k = 0; k < 4; ++k)
                if (!(ch->carriers >> k & 1)) ch->ops[k][1] = (uint8_t)(0x7F - v);
            break;
        case OPN_FX_MULT: ch->ops[x - 1][0] = (uint8_t)((ch->ops[x - 1][0] & 0x70) | y); break;
        }
    }
    const int port = fm_port(c);
    for (int k = 0; k < 4; ++k)
        for (int r = 0; r < OPN_OP_BYTES; ++r)
            wr(p, port, fm_reg(OP_REG[r], c) + OPNP_OP_OFFSET[k], r == 1 ? tl_out(ch, k) : ch->ops[k][r]);
    wr(p, port, fm_reg(0xB0, c), ch->feedback << 3 | ch->alg);
    if (opna(p)) wr(p, port, fm_reg(0xB4, c), ch->pan | ch->ams_fms);
    ch->key_pending = 1;
}

static void key_on_ssg(OpnPlayer* p, const OpnImage* image, OpnChannel* ch, int c, const uint8_t* rec,
                       const OpnRow* row) {
    key_off(p, c);
    const int i = c - p->fm_channels;
    for (int k = 0; k < OPNP_ENVS; ++k) {
        OpnEnvelope* e = &p->env[i][k];
        const int off = opn_u16(rec + OPN_I_ENV_VOLUME + 2 * k);
        e->length = 0;
        if (off != OPN_NO_ENVELOPE) {
            const uint8_t* b = image->bytes + OPN_OFF_DATA + off;
            const int loop = opn_u16(b + 2), release = opn_u16(b + 4);
            e->length = opn_u16(b);
            e->loop = loop == OPN_NO_ENVELOPE ? -1 : loop;
            e->release = release == OPN_NO_ENVELOPE ? -1 : release;
            memcpy(e->value, b + OPN_ENV_HEADER, (size_t)e->length);
        }
        ch->pos[k] = 0;
    }
    ch->pitch_relative = rec[OPN_I_SSG_FLAGS] & OPN_SSG_PITCH_RELATIVE;
    ch->pitch_acc = 0;
    ch->phase = OPNP_SSG_ON;
    ch->env_shape = rec[OPN_I_SSG_SHAPE];
    ch->env_auto = rec[OPN_I_SSG_AUTO];
    ch->env_octave = (int8_t)rec[OPN_I_SSG_OCTAVE];
    ch->env_period = opn_u16(rec + OPN_I_SSG_PERIOD);
    for (int e = 0; row && e < row->effects; ++e) {
        const int v = row->param[e];
        if (row->code[e] == OPN_FX_ENV_SHAPE) ch->env_shape = v;
        if (row->code[e] == OPN_FX_ENV_PERIOD) {
            ch->env_auto = 0;
            ch->env_period = v << 8;
        }
    }
    if (ch->env_shape) wr_force(p, 0, 0x0D, ch->env_shape); /* restarts the shared envelope generator */
}

/* Key on `note` with the channel's instrument; `row` gives this row's locks and pan (NULL: none). */
static void key_on(OpnPlayer* p, const OpnImage* image, int c, int note, const OpnRow* row) {
    OpnChannel* ch = &p->ch[c];
    const int part = part_of(p, c);
    const uint8_t* rec = opn_instrument(image, ch->instrument, part);
    if (!rec) return;
    switch (part) {
    case OPN_PART_FM: key_on_fm(p, ch, c, rec, row); break;
    case OPN_PART_SSG: key_on_ssg(p, image, ch, c, rec, row); break;
    case OPN_PART_RHYTHM: /* a note re-triggers the drum: no dump first */
        ch->ins_level = rec[OPN_I_RHYTHM_LEVEL];
        if (!row_has(row, OPN_FX_PAN)) ch->pan = rec[OPN_I_PAN];
        ch->key_pending = 1;
        break;
    default: /* ADPCM-B: stop in the key-off phase, start in the main phase */
        ch->keyed = 0;
        ch->key_pending = 0;
        push_off(p, 1, 0x00, 0x01);
        ch->sample = rec[OPN_I_ADPCM_SAMPLE];
        ch->root = rec[OPN_I_ADPCM_ROOT];
        ch->loop = rec[OPN_I_ADPCM_FLAGS] & OPN_ADPCM_LOOP;
        ch->ins_level = rec[OPN_I_ADPCM_LEVEL];
        if (!row_has(row, OPN_FX_PAN)) ch->pan = rec[OPN_I_PAN];
        ch->key_pending = 1;
        break;
    }
    if (part != OPN_PART_RHYTHM) start_pitch(ch, rec, note);
    ch->keyed = 1;
    ch->active = 1;
}

static void process_row(OpnPlayer* p, const OpnImage* image, int c) {
    OpnChannel* ch = &p->ch[c];
    const OpnRow* row = &ch->row;
    const int part = part_of(p, c);
    const int has_note = row->note >= 0 && row->note < OPN_NOTES;
    if (has_note) {
        ch->arp = 0;
        ch->fine = 0;
    }
    if (row->instrument) ch->instrument = row->instrument;
    for (int e = 0; e < row->effects; ++e) {
        const int v = row->param[e], x = v >> 4, y = v & 15;
        switch (row->code[e]) {
        case OPN_FX_ARPEGGIO: ch->arp = v; ch->arp_step = 0; break;
        case OPN_FX_SLIDE_UP: ch->slide_mode = v ? 1 : 0; ch->slide_speed = v; break;
        case OPN_FX_SLIDE_DOWN: ch->slide_mode = v ? 2 : 0; ch->slide_speed = v; break;
        case OPN_FX_PORTAMENTO: ch->slide_mode = v ? 3 : 0; ch->slide_speed = v; break;
        case OPN_FX_VIBRATO: ch->vib_speed = x; ch->vib_depth = y; break;
        case OPN_FX_PAN:
            ch->pan = (x ? 0x80 : 0) | (y ? 0x40 : 0);
            /* FM at once; rhythm and ADPCM-B by their next update */
            if (part == OPN_PART_FM && ch->active && opna(p)) wr(p, fm_port(c), fm_reg(0xB4, c), ch->pan | ch->ams_fms);
            break;
        case OPN_FX_VOLUME_SLIDE: ch->volslide = v; break;
        case OPN_FX_VOLUME: ch->vol8 = v * 8; break;
        case OPN_FX_LFO: p->lfo = v; wr(p, 0, 0x22, v); break;
        case OPN_FX_NOISE: p->noise_period = v; break;
        case OPN_FX_FINE: ch->fine = v - 0x80; break;
        case OPN_FX_RHYTHM_LEVEL: p->rhythm_level = v; wr(p, 0, 0x11, v); break;
        case OPN_FX_CUT: ch->cut = v; break;
        case OPN_FX_TIMER_A: p->timer_a = v * 4; write_timer_a(p); break;
        }
    }
    if (row->note == OPN_NOTE_OFF) {
        key_off(p, c);
    } else if (has_note) {
        if (ch->slide_mode == 3 && ch->keyed && part != OPN_PART_RHYTHM) ch->target = row->note * 64;
        else key_on(p, image, c, row->note, row);
    }
}

/* ---- per tick --------------------------------------------------------------------------------------------------- */

static void advance_effects(OpnChannel* ch, int vol_max) {
    if (ch->arp) ch->arp_step = (ch->arp_step + 1) % 3;
    if (ch->slide_mode == 1) ch->slide = clamp(ch->slide + ch->slide_speed, -6144, 6144);
    if (ch->slide_mode == 2) ch->slide = clamp(ch->slide - ch->slide_speed, -6144, 6144);
    if (ch->slide_mode == 3) {
        if (ch->base < ch->target) ch->base = clamp(ch->base + ch->slide_speed, 0, ch->target);
        else if (ch->base > ch->target) ch->base = clamp(ch->base - ch->slide_speed, ch->target, OPNP_PITCH_MAX);
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
static int envelope_next(const OpnEnvelope* e, int pos, int released) {
    const int n = pos + 1;
    if (!released && e->release >= 0 && n == e->release) return e->loop;
    if (n < e->length) return n;
    return e->loop >= 0 && e->release < 0 ? e->loop : e->length - 1;
}

static void advance_envelopes(OpnPlayer* p, int i) {
    OpnChannel* ch = &p->ch[p->fm_channels + i];
    const OpnEnvelope* env = p->env[i];
    const int released = ch->phase == OPNP_SSG_RELEASED;
    for (int k = 0; k < OPNP_ENVS; ++k)
        if (env[k].length) ch->pos[k] = envelope_next(&env[k], ch->pos[k], released);
    const OpnEnvelope* vol = &env[OPNP_ENV_VOL];
    if (released && ch->pos[OPNP_ENV_VOL] == vol->length - 1 && vol->value[vol->length - 1] == 0)
        ch->phase = OPNP_SSG_OFF;
}

static void update_fm(OpnPlayer* p, int c) {
    OpnChannel* ch = &p->ch[c];
    const int port = fm_port(c);
    for (int k = 0; k < 4; ++k)
        if (ch->carriers >> k & 1) wr(p, port, fm_reg(0x40, c) + OPNP_OP_OFFSET[k], tl_out(ch, k));
    const int units = pitch(ch, 0);
    int hi, lo;
    if (c == OPN_CH_FM3 && p->fm3_mode != OPN_FM3_NORMAL) {
        for (int k = 0; k < 4; ++k) {
            fm_frequency(clamp(units + ch->ch3[k], 0, OPNP_PITCH_MAX), &hi, &lo);
            wr_pair(p, 0, FM3_HI[k], hi, lo);
        }
    } else {
        fm_frequency(units, &hi, &lo);
        wr_pair(p, port, fm_reg(0xA4, c), hi, lo);
    }
    if (ch->key_pending) {
        ch->key_pending = 0;
        if (!csm_fm3(p, c)) wr_force(p, 0, 0x28, 0xF0 | key_select(c)); /* CSM: $27 = $81 at the tick's end */
    }
    advance_effects(ch, 1016);
}

/* Envelope period of the auto mode: the tone period shifted right by the octave (left when negative). */
static int auto_period(int tp, int octave) {
    const long v = octave >= 0 ? (long)tp >> octave : (long)tp << -octave;
    return v < 1 ? 1 : v > 65535 ? 65535 : (int)v;
}

/* The three SSG channels and the registers they share: noise period $06, envelope period $0B/$0C, mixer $07. */
static void update_ssg(OpnPlayer* p) {
    int noise = p->noise_period, hw_period = -1, mix = 0;
    for (int i = 0; i < OPN_SSG; ++i) {
        OpnChannel* ch = &p->ch[p->fm_channels + i];
        if (!ch->active) {
            mix |= 9 << i;
            continue;
        }
        const OpnEnvelope* env = p->env[i];
        const int sounding = ch->phase != OPNP_SSG_OFF;
        const OpnEnvelope* vol = &env[OPNP_ENV_VOL];
        ch->level = !sounding ? 0 : vol->length ? vol->value[ch->pos[OPNP_ENV_VOL]] : 15;
        const OpnEnvelope* mixer = &env[OPNP_ENV_MIXER];
        ch->mixer = !sounding ? OPN_MIX_NONE : mixer->length ? mixer->value[ch->pos[OPNP_ENV_MIXER]] : OPN_MIX_TONE;
        int extra = env[OPNP_ENV_ARP].length ? 64 * env[OPNP_ENV_ARP].value[ch->pos[OPNP_ENV_ARP]] : 0;
        const int bend = env[OPNP_ENV_PITCH].length ? OPN_PITCH_UNIT * env[OPNP_ENV_PITCH].value[ch->pos[OPNP_ENV_PITCH]] : 0;
        if (ch->pitch_relative) {
            ch->pitch_acc = clamp(ch->pitch_acc + bend, -6144, 6144);
            extra += ch->pitch_acc;
        } else {
            extra += bend;
        }
        const int tp = ssg_period(pitch(ch, extra));
        wr(p, 0, 2 * i, tp & 0xFF);
        wr(p, 0, 2 * i + 1, tp >> 8);
        const int level = (ch->level * (ch->vol8 >> 3) + 7) / 15;
        wr(p, 0, 0x08 + i, ch->env_shape ? (level > 0 ? 0x10 : 0) : level);
        if (sounding && env[OPNP_ENV_NOISE].length) noise = env[OPNP_ENV_NOISE].value[ch->pos[OPNP_ENV_NOISE]];
        if (sounding && ch->env_shape) hw_period = ch->env_auto ? auto_period(tp, ch->env_octave) : ch->env_period;
        if (ch->mixer == OPN_MIX_NOISE || ch->mixer == OPN_MIX_NONE) mix |= 1 << i;
        if (ch->mixer == OPN_MIX_TONE || ch->mixer == OPN_MIX_NONE) mix |= 8 << i;
        advance_effects(ch, 120);
        advance_envelopes(p, i);
    }
    wr(p, 0, 0x06, noise & 0x1F);
    if (hw_period >= 0) {
        wr(p, 0, 0x0B, hw_period & 0xFF);
        wr(p, 0, 0x0C, hw_period >> 8);
    }
    wr(p, 0, 0x07, mix);
}

static void update_rhythm(OpnPlayer* p, int c) {
    OpnChannel* ch = &p->ch[c];
    const int d = c - OPNA_CH_RHYTHM;
    const int level = (ch->ins_level * (ch->vol8 >> 3) + 15) / 31;
    wr(p, 0, 0x18 + d, (ch->pan & 0xC0) | level);
    if (ch->key_pending) {
        ch->key_pending = 0;
        wr_force(p, 0, 0x10, 1 << d);
    }
    advance_effects(ch, 248);
}

static void update_adpcm(OpnPlayer* p, const OpnImage* image, int c) {
    OpnChannel* ch = &p->ch[c];
    const uint8_t* s = opn_sample(image, ch->sample);
    wr(p, 1, 0x01, (ch->pan & 0xC0) | 0x02);
    if (s && ch->key_pending) {
        const uint32_t start = opn_u32(s + OPN_S_START) / OPN_ADPCM_ALIGN;
        const uint32_t end = opn_u32(s + OPN_S_END) / OPN_ADPCM_ALIGN - 1;
        wr(p, 1, 0x02, (int)(start & 0xFF));
        wr(p, 1, 0x03, (int)(start >> 8));
        wr(p, 1, 0x04, (int)(end & 0xFF));
        wr(p, 1, 0x05, (int)(end >> 8));
    }
    if (s) {
        const int dn = adpcm_delta(opn_u32(s + OPN_S_RATE), ch->root, pitch(ch, 0));
        wr(p, 1, 0x09, dn & 0xFF);
        wr(p, 1, 0x0A, dn >> 8);
    }
    wr(p, 1, 0x0B, ch->ins_level * (ch->vol8 >> 3) / 255);
    if (ch->key_pending) {
        ch->key_pending = 0;
        if (s) {
            wr_force(p, 1, 0x00, 0xA0 | (ch->loop ? 0x10 : 0x00));
            p->adpcm_key_tick = p->ticks;
        }
    }
    advance_effects(ch, 2040);
}

static void read_rows(OpnPlayer* p, const OpnImage* image) {
    if (p->cursor_order != p->order || p->cursor_row != p->row) {
        for (int c = 0; c < p->channels; ++c) {
            OpnCursor cur = opn_pattern(image, p->order, c);
            OpnRow skip;
            for (int r = 0; r < p->row; ++r) opn_next_row(image->bytes, &cur, &skip);
            p->cursor[c] = cur;
        }
    }
    for (int c = 0; c < p->channels; ++c) opn_next_row(image->bytes, &p->cursor[c], &p->ch[c].row);
    p->cursor_order = p->order;
    p->cursor_row = p->row + 1;
}

/* B/D of the current row (rows already read) -> p->jump. */
static void derive_jump(OpnPlayer* p, const OpnImage* image) {
    int has_b = 0, has_d = 0, b = 0, d = 0;
    for (int c = 0; c < p->channels; ++c) {
        const OpnRow* row = &p->ch[c].row;
        for (int e = 0; e < row->effects; ++e) {
            if (row->code[e] == OPN_FX_JUMP) has_b = 1, b = row->param[e];
            if (row->code[e] == OPN_FX_BREAK) has_d = 1, d = row->param[e];
        }
    }
    p->jump = has_b || has_d;
    p->jump_order = has_b ? b : p->order + 1 < image->info.orders ? p->order + 1 : image->info.loop;
    p->jump_row = has_d ? d : 0;
}

static int row_delay(const OpnRow* row) {
    int g = 0;
    for (int e = 0; e < row->effects; ++e)
        if (row->code[e] == OPN_FX_DELAY) g = row->param[e];
    return g;
}

static void row_start(OpnPlayer* p, const OpnImage* image) {
    read_rows(p, image);
    for (int c = 0; c < p->channels; ++c) {
        const OpnRow* row = &p->ch[c].row;
        for (int e = 0; e < row->effects; ++e)
            if (row->code[e] == OPN_FX_SPEED) p->speed = row->param[e];
    }
    derive_jump(p, image);
    for (int c = 0; c < p->channels; ++c) {
        OpnChannel* ch = &p->ch[c];
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
static void advance(OpnPlayer* p, const OpnImage* image) {
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

void opnp_tick(OpnPlayer* p, const OpnImage* image) {
    begin_batch(p);
    if (opna(p)) {
        wr(p, 0, 0x22, p->lfo);
        wr(p, 0, 0x11, p->rhythm_level);
    }
    write_timer_a(p);
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
            if (p->ch[c].cut == p->tick) key_off(p, c);
    }
    for (int c = 0; c < p->fm_channels; ++c)
        if (p->ch[c].active) update_fm(p, c);
    update_ssg(p);
    if (opna(p)) {
        for (int c = OPNA_CH_RHYTHM; c < OPNA_CH_ADPCM; ++c)
            if (p->ch[c].active) update_rhythm(p, c);
        if (p->ch[OPNA_CH_ADPCM].active) update_adpcm(p, image, OPNA_CH_ADPCM);
    }
    wr(p, 0, 0x27, mode_reg(p));
    ++p->ticks;
    if (!p->audition && ++p->tick >= p->speed) {
        p->tick = 0;
        advance(p, image);
    }
    end_batch(p);
}

/* ---- reset, placement, live edits, audition --------------------------------------------------------------------- */

int opnp_machine_reset(int chip, OpnWrite* out) {
    const int a = chip == OPN_CHIP_YM2608;
    const int ports = a ? 2 : 1, fm = opn_fm_channels(chip);
    int n = 0;
#define W(port, r, v) out[n++] = (OpnWrite){(uint8_t)(port), (uint8_t)(r), (uint8_t)(v)}
    if (a) {
        W(0, 0x29, 0x80); /* six FM channels */
        W(0, 0x22, 0x00);
    }
    W(0, 0x27, 0x00);
    W(0, 0x24, 0x00);
    W(0, 0x25, 0x00);
    static const uint8_t op_val[OPN_OP_BYTES] = {0x00, 0x7F, 0x00, 0x00, 0x00, 0xFF, 0x00};
    for (int port = 0; port < ports; ++port)
        for (int ch = 0; ch < 3; ++ch) {
            for (int k = 0; k < 4; ++k)
                for (int r = 0; r < OPN_OP_BYTES; ++r) W(port, OP_REG[r] + 4 * k + ch, op_val[r]);
            W(port, 0xA4 + ch, 0x00);
            W(port, 0xA0 + ch, 0x00);
            W(port, 0xB0 + ch, 0x00);
            if (a) W(port, 0xB4 + ch, 0xC0);
        }
    for (int k = 0; k < 3; ++k) { /* FM 3's special-mode frequencies of operators 1-3 */
        W(0, FM3_HI[k], 0x00);
        W(0, FM3_HI[k] - 4, 0x00);
    }
    for (int c = 0; c < fm; ++c) W(0, 0x28, key_select(c));
    for (int r = 0x00; r <= 0x0C; ++r) W(0, r, r == 0x07 ? 0x3F : 0x00);
    if (a) {
        W(0, 0x10, 0xBF); /* dump every drum */
        W(0, 0x11, 0x00);
        for (int d = 0; d < OPN_RHYTHM; ++d) W(0, 0x18 + d, 0x00);
        W(1, 0x00, 0x01); /* ADPCM-B reset */
        W(1, 0x01, 0x02); /* 8-bit DRAM */
        for (int r = 0x02; r <= 0x0B; ++r)
            if (r != 0x08) W(1, r, 0x00);
        W(1, 0x0C, 0xFF);
        W(1, 0x0D, 0xFF);
    }
#undef W
    return n;
}

static void shadow_reset(OpnPlayer* p) {
    OpnWrite w[OPNP_MAX_RESET_WRITES];
    const int n = opnp_machine_reset(p->chip, w);
    memset(p->reg, 0, sizeof p->reg);
    for (int i = 0; i < n; ++i) p->reg[w[i].port][w[i].reg] = w[i].val;
}

int opnp_dump(const OpnPlayer* p, OpnWrite* out) {
    const int a = opna(p);
    int n = 0;
#define W(port, r) out[n++] = (OpnWrite){(uint8_t)(port), (uint8_t)(r), p->reg[port][r]}
#define WV(port, r, v) out[n++] = (OpnWrite){(uint8_t)(port), (uint8_t)(r), (uint8_t)(v)}
    if (a) {
        W(0, 0x29);
        W(0, 0x22);
        W(0, 0x11);
    }
    W(0, 0x24);
    W(0, 0x25);
    const int csm_on = p->reg[0][0x27] == 0x81;
    WV(0, 0x27, csm_on ? 0x40 : p->reg[0][0x27]);
    for (int r = 0x00; r <= 0x0C; ++r) W(0, r);
    for (int c = 0; c < p->fm_channels; ++c) {
        const int port = fm_port(c);
        for (int k = 0; k < 4; ++k)
            for (int r = 0; r < OPN_OP_BYTES; ++r) W(port, fm_reg(OP_REG[r], c) + OPNP_OP_OFFSET[k]);
        W(port, fm_reg(0xB0, c));
        if (a) W(port, fm_reg(0xB4, c));
        W(port, fm_reg(0xA4, c));
        W(port, fm_reg(0xA0, c));
        if (c == OPN_CH_FM3)
            for (int k = 0; k < 3; ++k) {
                W(0, FM3_HI[k]);
                W(0, FM3_HI[k] - 4);
            }
    }
    if (a)
        for (int d = 0; d < OPN_RHYTHM; ++d) W(0, 0x18 + d);
    for (int i = 0; i < OPN_SSG; ++i) {
        const OpnChannel* ch = &p->ch[p->fm_channels + i];
        if (ch->active && ch->phase != OPNP_SSG_OFF && ch->env_shape) {
            W(0, 0x0D);
            break;
        }
    }
    for (int c = 0; c < p->fm_channels; ++c) {
        const OpnChannel* ch = &p->ch[c];
        if (ch->keyed && !ch->key_pending && !csm_fm3(p, c)) WV(0, 0x28, 0xF0 | key_select(c));
    }
    if (csm_on) WV(0, 0x27, 0x81);
#undef W
#undef WV
    return n;
}

int opnp_dump_adpcm(const OpnPlayer* p, OpnWrite* out) {
    if (!opna(p)) return 0;
    int n = 0;
    for (int r = 0x01; r <= 0x0D; ++r)
        if (r != 0x08) out[n++] = (OpnWrite){1, (uint8_t)r, p->reg[1][r]};
    return n;
}

void opnp_reset(OpnPlayer* p, const OpnImage* image) {
    opn_tables_init();
    memset(p, 0, sizeof *p);
    p->chip = image->info.chip;
    p->channels = opn_channels(p->chip);
    p->fm_channels = opn_fm_channels(p->chip);
    shadow_reset(p);
    for (int c = 0; c < OPN_MAX_CHANNELS; ++c) {
        OpnChannel* ch = &p->ch[c];
        ch->cut = -1;
        ch->phase = OPNP_SSG_OFF;
        switch (part_of(p, c)) {
        case OPN_PART_SSG: ch->vol8 = 120; break;
        case OPN_PART_RHYTHM: ch->vol8 = 248; break;
        case OPN_PART_ADPCM: ch->vol8 = 2040; break;
        default: ch->vol8 = 1016; break;
        }
        ch->pan = 0xC0;
    }
    p->cursor_order = p->cursor_row = -1;
    p->speed = image->info.speed;
    p->lfo = image->info.lfo;
    p->timer_a = image->info.timer_a;
    p->rhythm_level = image->info.rhythm_level;
    p->fm3_mode = image->info.fm3_mode;
    p->noise_period = 0;
    if (opna(p)) {
        wr_force(p, 0, 0x22, p->lfo);
        wr_force(p, 0, 0x11, p->rhythm_level);
    }
    wr_force(p, 0, 0x24, p->timer_a >> 2);
    wr_force(p, 0, 0x25, p->timer_a & 3);
    wr_force(p, 0, 0x27, mode_reg(p));
    if (opna(p)) {
        wr_force(p, 0, 0x29, 0x80);
        wr_force(p, 1, 0x01, 0x02);
        wr_force(p, 1, 0x0C, 0xFF);
        wr_force(p, 1, 0x0D, 0xFF);
    }
    end_batch(p);
}

void opnp_place(OpnPlayer* p, const OpnImage* image, int order) {
    opnp_reset(p, image);
    p->order = order;
    p->speed = image->info.start_speed[order];
}

/* Ticks per row straight play has at (order, row): the order's start speed and the Fxx of the rows before it (and of
 * the row itself once it has started). */
static int speed_at(const OpnImage* image, int order, int row, int started) {
    int s = image->info.start_speed[order];
    const int channels = image->info.channels;
    OpnCursor cur[OPN_MAX_CHANNELS];
    for (int c = 0; c < channels; ++c) cur[c] = opn_pattern(image, order, c);
    const int last = started ? row : row - 1;
    for (int r = 0; r <= last; ++r) {
        for (int c = 0; c < channels; ++c) {
            OpnRow x;
            opn_next_row(image->bytes, &cur[c], &x);
            for (int e = 0; e < x.effects; ++e)
                if (x.code[e] == OPN_FX_SPEED) s = x.param[e];
        }
    }
    return s;
}

void opnp_relocate(OpnPlayer* p, const OpnImage* old, const OpnImage* next) {
    if (p->audition) return;
    const OpnInfo* info = &next->info;
    if (info->lfo != old->info.lfo) p->lfo = info->lfo;
    if (info->timer_a != old->info.timer_a) p->timer_a = info->timer_a;
    if (info->rhythm_level != old->info.rhythm_level) p->rhythm_level = info->rhythm_level;
    if (info->fm3_mode != old->info.fm3_mode) p->fm3_mode = info->fm3_mode;
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
        OpnRow row;
        opn_read_row(next, p->order, c, p->row, &row);
        OpnChannel* ch = &p->ch[c];
        ch->row = row;
        if (ch->pending) {
            const int g = row_delay(&row);
            ch->pending = g >= p->tick;
            ch->delay = g;
        }
    }
    derive_jump(p, next);
}

int opnp_audition(OpnPlayer* p, const OpnImage* image, int channel, int note, int slot, int fine) {
    const int chip = image->info.chip;
    if (channel < 0 || channel >= opn_channels(chip) || note < 0 || note >= OPN_NOTES || fine < -128 || fine > 127)
        return -1;
    if (!opn_instrument(image, slot, opn_part(chip, channel))) return -1;
    opnp_reset(p, image);
    p->audition = 1;
    p->ch[channel].instrument = slot;
    key_on(p, image, channel, note, NULL);
    p->ch[channel].fine = fine;
    end_batch(p);
    return 0;
}
