/* Thor Tracker's Atari 2600 player (tia_player.h). Specification: docs/AUDIO_ENGINE.md, "Atari 2600: Player".
 * Every rule here has a counterpart in tools/audio/tia/tia_ref.py; change both and the spec together. */
#include "tia_player.h"

#include <string.h>

_Static_assert((-1 >> 1) == -1, "the vibrato offset uses an arithmetic (floor) right shift");

const int8_t TIP_CLASS[16] = {-1, 2, 5, 5, 0, 0, 3, 3, 6, 3, 3, -1, 1, 1, 4, 4};

const uint16_t TIP_PERIOD[TIP_CLASSES] = {2, 6, 15, 31, 93, 465, 511};

/* ceil(768 log2(A_r / (P_c C0 sqrt((k + 1)(k + 2))))), A_NTSC = 39375000 / 11 / 114, A_PAL = 3546894 / 114,
 * C0 = 440 2^(-57/12) (tia_ref.py computes it with this formula; prepare_tia.py fails on any difference). */
const int16_t TIP_AUDF_EDGE[TI_REGIONS][TIP_CLASSES][TIP_EDGES] = {
    { /* NTSC */
        {7225, 6617, 6233, 5950, 5725, 5538, 5379, 5240, 5116, 5005, 4904, 4812, 4726, 4647, 4573, 4504, 4438, 4377, 4318, 4263, 4210, 4160, 4111, 4065, 4021, 3978, 3937, 3898, 3859, 3822, 3787}, /* period 2 */
        {6008, 5399, 5015, 4732, 4508, 4321, 4162, 4023, 3899, 3788, 3687, 3594, 3509, 3430, 3356, 3286, 3221, 3159, 3101, 3046, 2993, 2942, 2894, 2848, 2804, 2761, 2720, 2680, 2642, 2605, 2569}, /* period 6 */
        {4993, 4384, 4000, 3717, 3492, 3306, 3147, 3007, 2884, 2773, 2672, 2579, 2494, 2414, 2340, 2271, 2206, 2144, 2086, 2030, 1978, 1927, 1879, 1833, 1788, 1746, 1705, 1665, 1627, 1590, 1554}, /* period 15 */
        {4188, 3580, 3196, 2913, 2688, 2502, 2342, 2203, 2079, 1968, 1867, 1775, 1689, 1610, 1536, 1467, 1401, 1340, 1282, 1226, 1173, 1123, 1075, 1028, 984, 941, 900, 861, 823, 786, 750}, /* period 31 */
        {2971, 2362, 1978, 1695, 1471, 1284, 1125, 986, 862, 751, 650, 557, 472, 393, 319, 249, 184, 123, 64, 9, -44, -94, -143, -189, -233, -276, -317, -356, -395, -432, -467}, /* period 93 */
        {1188, 579, 195, -88, -312, -499, -658, -797, -921, -1032, -1133, -1226, -1311, -1390, -1464, -1534, -1599, -1661, -1719, -1774, -1827, -1878, -1926, -1972, -2016, -2059, -2100, -2140, -2178, -2215, -2251}, /* period 465 */
        {1083, 475, 91, -192, -417, -603, -763, -902, -1026, -1137, -1238, -1330, -1416, -1495, -1569, -1638, -1704, -1765, -1824, -1879, -1932, -1982, -2030, -2077, -2121, -2164, -2205, -2244, -2282, -2319, -2355}, /* period 511 */
    },
    { /* PAL */
        {7215, 6606, 6222, 5939, 5715, 5528, 5369, 5230, 5106, 4995, 4894, 4801, 4716, 4637, 4563, 4493, 4428, 4367, 4308, 4253, 4200, 4150, 4101, 4055, 4011, 3968, 3927, 3888, 3849, 3812, 3777}, /* period 2 */
        {5998, 5389, 5005, 4722, 4497, 4311, 4152, 4012, 3889, 3778, 3677, 3584, 3499, 3419, 3345, 3276, 3211, 3149, 3091, 3035, 2983, 2932, 2884, 2838, 2794, 2751, 2710, 2670, 2632, 2595, 2559}, /* period 6 */
        {4983, 4374, 3990, 3707, 3482, 3296, 3136, 2997, 2874, 2762, 2661, 2569, 2484, 2404, 2330, 2261, 2196, 2134, 2076, 2020, 1967, 1917, 1869, 1823, 1778, 1736, 1695, 1655, 1617, 1580, 1544}, /* period 15 */
        {4178, 3570, 3186, 2903, 2678, 2492, 2332, 2193, 2069, 1958, 1857, 1765, 1679, 1600, 1526, 1457, 1391, 1330, 1271, 1216, 1163, 1113, 1064, 1018, 974, 931, 890, 851, 812, 776, 740}, /* period 31 */
        {2961, 2352, 1968, 1685, 1461, 1274, 1115, 976, 852, 741, 640, 547, 462, 383, 309, 239, 174, 112, 54, -1, -54, -105, -153, -199, -243, -286, -327, -367, -405, -442, -477}, /* period 93 */
        {1178, 569, 185, -98, -323, -509, -668, -808, -931, -1042, -1143, -1236, -1321, -1401, -1475, -1544, -1609, -1671, -1729, -1785, -1837, -1888, -1936, -1982, -2027, -2069, -2110, -2150, -2188, -2225, -2261}, /* period 465 */
        {1073, 465, 81, -202, -427, -613, -773, -912, -1036, -1147, -1248, -1340, -1426, -1505, -1579, -1648, -1714, -1775, -1834, -1889, -1942, -1992, -2041, -2087, -2131, -2174, -2215, -2254, -2293, -2329, -2365}, /* period 511 */
    },
};

/* floor(127 sin(2 pi i / 64) + 0.5): the player's own vibrato (the OPL3's table). */
const int8_t TIP_SINE[64] = {
    0,   12,  25,  37,  49,  60,  71,  81,  90,  98,  106,  112,  117,  122,  125,  126,
    127, 126, 125, 122, 117, 112, 106, 98,  90,  81,  71,   60,   49,   37,   25,   12,
    0,   -12, -25, -37, -49, -60, -71, -81, -90, -98, -106, -112, -117, -122, -125, -126,
    -127, -126, -125, -122, -117, -112, -106, -98, -90, -81, -71, -60, -49, -37, -25, -12,
};

static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

int tip_audf(int region, int cls, int pitch) {
    const int16_t* edge = TIP_AUDF_EDGE[region == TI_REGION_PAL ? TI_REGION_PAL : TI_REGION_NTSC][clamp(cls, 0, TIP_CLASSES - 1)];
    for (int k = 0; k < TIP_EDGES; ++k)
        if (pitch >= edge[k]) return k;
    return TI_AUDF_MAX;
}

static void append(TiPlayer* p, int reg, int val) {
    if (p->writes < TIP_MAX_WRITES) {
        p->write[p->writes].reg = (uint8_t)reg;
        p->write[p->writes].val = (uint8_t)val;
        ++p->writes;
    }
}

/* A register through its shadow. */
static void wr(TiPlayer* p, uint8_t* shadow, int reg, int val) {
    if (*shadow == (uint8_t)val) return;
    append(p, reg, val);
    *shadow = (uint8_t)val;
}

static void wr_audc(TiPlayer* p, int c, int v) { wr(p, &p->ch[c].audc, TI_REG_AUDC0 + c, v); }
static void wr_audf(TiPlayer* p, int c, int v) { wr(p, &p->ch[c].audf, TI_REG_AUDF0 + c, v); }
static void wr_audv(TiPlayer* p, int c, int v) { wr(p, &p->ch[c].audv, TI_REG_AUDV0 + c, v); }

/* The instrument record of a slot as the image holds it (sounding notes read it every tick: live edits). */
static const uint8_t* raw_record(const TiaImage* image, int slot) {
    return image->bytes + TI_OFF_INSTRUMENTS + TI_INSTRUMENT_SIZE * (clamp(slot, 1, TI_MAX_INSTRUMENTS) - 1);
}

/* A defined instrument slot of the image, else NULL. */
static const uint8_t* instrument(const TiaImage* image, int slot) {
    if (slot < 1 || slot > image->info.instruments) return NULL;
    const uint8_t* rec = raw_record(image, slot);
    return (rec[TI_I_FLAGS] & TI_FLAG_DEFINED) ? rec : NULL;
}

/* ---- envelopes ------------------------------------------------------------------------------- */

typedef struct Envelope {
    int offset, length, loop, release;
} Envelope;

static void envelope(const uint8_t* rec, int k, Envelope* e) {
    const uint8_t* at = rec + TI_I_ENVELOPES + TI_ENVELOPE_BYTES * k;
    e->offset = ti_u16(at);
    e->length = at[2];
    e->loop = at[3] == TI_ENV_NONE ? -1 : at[3];
    e->release = at[4] == TI_ENV_NONE ? -1 : at[4];
}

/* The byte at the channel's index (clamped to the length: live edits). */
static int env_byte(const TiaImage* image, const Envelope* e, int idx) {
    return image->bytes[TI_OFF_DATA + e->offset + (idx < e->length ? idx : e->length - 1)];
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

/* The note stops: AUDV 0 (AUDC and AUDF keep their values; the counters run on). */
static void stop(TiPlayer* p, int c) {
    p->ch[c].sounding = 0;
    wr_audv(p, c, 0);
}

static void off(TiPlayer* p, const TiaImage* image, int c) {
    TiChannel* ch = &p->ch[c];
    if (!ch->sounding) return;
    const uint8_t* rec = instrument(image, ch->slot);
    Envelope vol;
    if (rec) envelope(rec, TI_ENV_VOLUME, &vol);
    if (rec && vol.length && vol.release >= 0) {
        ch->released = 1;
        for (int k = 0; k < TI_ENVELOPE_COUNT; ++k) {
            Envelope e;
            envelope(rec, k, &e);
            if (e.length && e.release >= 0) ch->env[k] = e.release;
        }
    } else {
        stop(p, c);
    }
}

void tip_off(TiPlayer* p, const TiaImage* image, int channel) {
    p->writes = 0;
    if (channel >= 0 && channel < TI_CHANNELS) off(p, image, channel);
}

static void key_on(TiPlayer* p, const TiaImage* image, int c, int note) {
    TiChannel* ch = &p->ch[c];
    if (!instrument(image, ch->instrument)) return;
    ch->slot = ch->instrument;
    for (int k = 0; k < TI_ENVELOPE_COUNT; ++k) ch->env[k] = 0;
    ch->released = 0;
    ch->rel_pitch = 0;
    ch->base = ch->target = note * 64;
    ch->slide = 0;
    ch->arp_step = 0;
    ch->vib_phase = 0;
    ch->sounding = 1;
}

static void process_row(TiPlayer* p, const TiaImage* image, int c) {
    TiChannel* ch = &p->ch[c];
    const TiRow* row = &ch->row;
    const int has_note = row->note >= TI_NOTE_MIN && row->note <= TI_NOTE_MAX;
    if (has_note) {
        ch->arp = 0;
        ch->fine = 0;
        ch->wave_override = -1;
    }
    if (row->instrument) ch->instrument = row->instrument;
    for (int e = 0; e < row->effects; ++e) {
        const int v = row->param[e];
        switch (row->code[e]) {
        case TI_FX_ARPEGGIO: ch->arp = v; ch->arp_step = 0; break;
        case TI_FX_SLIDE_UP: ch->slide_mode = v ? 1 : 0; ch->slide_speed = v; break;
        case TI_FX_SLIDE_DOWN: ch->slide_mode = v ? 2 : 0; ch->slide_speed = v; break;
        case TI_FX_PORTAMENTO: ch->slide_mode = v ? 3 : 0; ch->slide_speed = v; break;
        case TI_FX_VIBRATO: ch->vib_speed = v >> 4; ch->vib_depth = v & 15; break;
        case TI_FX_VOLUME_SLIDE: ch->volslide = v; break;
        case TI_FX_VOLUME: ch->vol8 = v * 8; break;
        case TI_FX_FINE: ch->fine = v - 0x80; break;
        case TI_FX_CUT: ch->cut = v; break;
        case TI_FX_WAVEFORM: ch->wave_override = v; break;
        }
    }
    if (row->note == TI_NOTE_OFF) {
        off(p, image, c);
    } else if (has_note) {
        /* Portamento glides a sounding tonal note; a raw instrument's note (latched slot, current image) keys on. */
        const int raw = (raw_record(image, ch->slot)[TI_I_FLAGS] & TI_FLAG_RAW) != 0;
        if (ch->slide_mode == 3 && ch->sounding && !raw) ch->target = row->note * 64;
        else key_on(p, image, c, row->note);
    }
}

/* ---- per tick -------------------------------------------------------------------------------- */

static void update(TiPlayer* p, const TiaImage* image, int c) {
    TiChannel* ch = &p->ch[c];
    if (!ch->sounding) return;
    const uint8_t* rec = instrument(image, ch->slot);
    if (!rec) { /* the latched slot is no longer defined (live edit) */
        stop(p, c);
        return;
    }
    Envelope env[TI_ENVELOPE_COUNT];
    for (int k = 0; k < TI_ENVELOPE_COUNT; ++k) envelope(rec, k, &env[k]);
    const int volume = env[TI_ENV_VOLUME].length ? env_byte(image, &env[TI_ENV_VOLUME], ch->env[TI_ENV_VOLUME]) : TI_VOLUME_MAX;
    const int arp_env = env[TI_ENV_ARPEGGIO].length ? (int8_t)env_byte(image, &env[TI_ENV_ARPEGGIO], ch->env[TI_ENV_ARPEGGIO]) : 0;
    int pitch_env = 0;
    if (env[TI_ENV_PITCH].length) {
        const int v = (int8_t)env_byte(image, &env[TI_ENV_PITCH], ch->env[TI_ENV_PITCH]);
        if (rec[TI_I_FLAGS] & TI_FLAG_RELATIVE) {
            ch->rel_pitch = clamp(ch->rel_pitch + v, -TIP_SLIDE_LIMIT, TIP_SLIDE_LIMIT);
            pitch_env = ch->rel_pitch;
        } else {
            pitch_env = v;
        }
    }
    const int w = env[TI_ENV_WAVEFORM].length ? env_byte(image, &env[TI_ENV_WAVEFORM], ch->env[TI_ENV_WAVEFORM])
                : ch->wave_override >= 0 ? ch->wave_override : rec[TI_I_WAVEFORM];
    const int vol = clamp(volume + (ch->vol8 >> 3) - 15, 0, TI_VOLUME_MAX);
    const int arp = ch->arp_step == 1 ? ch->arp >> 4 : ch->arp_step == 2 ? ch->arp & 15 : 0;
    const int semitones = arp + arp_env;
    int audf = -1;
    if (rec[TI_I_FLAGS] & TI_FLAG_RAW) {
        int n = ((ch->base >> 6) + semitones) % 32;
        if (n < 0) n += 32;
        audf = TI_AUDF_MAX - n;
    } else if (TIP_CLASS[w & 15] >= 0) {
        const int vib = (TIP_SINE[ch->vib_phase] * ch->vib_depth) >> 4;
        const int pitch = clamp(ch->base + 64 * semitones + ch->fine + ch->slide + vib + pitch_env, TIP_PITCH_MIN, TIP_PITCH_MAX);
        audf = tip_audf(p->region, TIP_CLASS[w & 15], pitch);
    }
    wr_audc(p, c, w);
    if (audf >= 0) wr_audf(p, c, audf);
    wr_audv(p, c, vol);
    for (int k = 0; k < TI_ENVELOPE_COUNT; ++k)
        if (env[k].length) ch->env[k] = env_next(&env[k], ch->env[k], ch->released);
    if (ch->arp) ch->arp_step = (ch->arp_step + 1) % 3;
    if (ch->slide_mode == 1) ch->slide = clamp(ch->slide + ch->slide_speed, -TIP_SLIDE_LIMIT, TIP_SLIDE_LIMIT);
    if (ch->slide_mode == 2) ch->slide = clamp(ch->slide - ch->slide_speed, -TIP_SLIDE_LIMIT, TIP_SLIDE_LIMIT);
    if (ch->slide_mode == 3) {
        if (ch->base < ch->target) ch->base = ch->base + ch->slide_speed > ch->target ? ch->target : ch->base + ch->slide_speed;
        else if (ch->base > ch->target) ch->base = ch->base - ch->slide_speed < ch->target ? ch->target : ch->base - ch->slide_speed;
    }
    if (ch->vib_depth && ch->vib_speed) ch->vib_phase = (ch->vib_phase + ch->vib_speed) & 63;
    if (ch->volslide) ch->vol8 = clamp(ch->vol8 + (ch->volslide >> 4) - (ch->volslide & 15), 0, 8 * TI_VOLUME_MAX);
}

static void read_rows(TiPlayer* p, const TiaImage* image) {
    if (p->cursor_order != p->order || p->cursor_row != p->row) {
        for (int c = 0; c < TI_CHANNELS; ++c) {
            TiCursor cur;
            ti_pattern_cursor(image, p->order, c, &cur);
            TiRow skip;
            for (int r = 0; r < p->row; ++r) ti_next_row(image->bytes, &cur, &skip);
            p->cursor[c] = cur;
        }
    }
    for (int c = 0; c < TI_CHANNELS; ++c) ti_next_row(image->bytes, &p->cursor[c], &p->ch[c].row);
    p->cursor_order = p->order;
    p->cursor_row = p->row + 1;
}

/* B/D of the current row (rows already read) -> p->jump. */
static void derive_jump(TiPlayer* p, const TiaImage* image) {
    int has_b = 0, has_d = 0, b = 0, d = 0;
    for (int c = 0; c < TI_CHANNELS; ++c) {
        const TiRow* row = &p->ch[c].row;
        for (int e = 0; e < row->effects; ++e) {
            if (row->code[e] == TI_FX_JUMP) has_b = 1, b = row->param[e];
            if (row->code[e] == TI_FX_BREAK) has_d = 1, d = row->param[e];
        }
    }
    p->jump = has_b || has_d;
    p->jump_order = has_b ? b : p->order + 1 < image->info.orders ? p->order + 1 : image->info.loop;
    p->jump_row = has_d ? d : 0;
}

static int row_delay(const TiRow* row) {
    int g = 0;
    for (int e = 0; e < row->effects; ++e)
        if (row->code[e] == TI_FX_DELAY) g = row->param[e];
    return g;
}

static void row_start(TiPlayer* p, const TiaImage* image) {
    read_rows(p, image);
    for (int c = 0; c < TI_CHANNELS; ++c) {
        const TiRow* row = &p->ch[c].row;
        for (int e = 0; e < row->effects; ++e)
            if (row->code[e] == TI_FX_SPEED) p->speed = row->param[e];
    }
    derive_jump(p, image);
    for (int c = 0; c < TI_CHANNELS; ++c) {
        TiChannel* ch = &p->ch[c];
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
static void advance(TiPlayer* p, const TiaImage* image) {
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

void tip_tick(TiPlayer* p, const TiaImage* image) {
    p->writes = 0;
    if (!p->audition) {
        if (p->tick == 0) {
            row_start(p, image);
        } else {
            for (int c = 0; c < TI_CHANNELS; ++c) {
                if (p->ch[c].pending && p->ch[c].delay == p->tick) {
                    p->ch[c].pending = 0;
                    process_row(p, image, c);
                }
            }
        }
        for (int c = 0; c < TI_CHANNELS; ++c)
            if (p->ch[c].cut == p->tick) off(p, image, c);
    }
    for (int c = 0; c < TI_CHANNELS; ++c) update(p, image, c);
    if (!p->audition && ++p->tick >= p->speed) {
        p->tick = 0;
        advance(p, image);
    }
}

static void reset(TiPlayer* p, const TiaImage* image, int audition) {
    memset(p, 0, sizeof *p);
    for (int c = 0; c < TI_CHANNELS; ++c) {
        TiChannel* ch = &p->ch[c];
        ch->cut = -1;
        ch->vol8 = 8 * TI_VOLUME_MAX;
        ch->wave_override = -1;
    }
    p->region = image->info.region;
    p->cursor_order = p->cursor_row = -1;
    p->speed = image->info.speed;
    p->audition = audition;
    /* The reset batch: every write emitted, setting the shadows (all 0). */
    for (int c = 0; c < TI_CHANNELS; ++c) {
        append(p, TI_REG_AUDC0 + c, 0);
        append(p, TI_REG_AUDF0 + c, 0);
        append(p, TI_REG_AUDV0 + c, 0);
    }
}

void tip_reset(TiPlayer* p, const TiaImage* image) { reset(p, image, 0); }

void tip_place(TiPlayer* p, const TiaImage* image, int order) {
    reset(p, image, 0);
    p->order = order;
    p->speed = image->info.start_speed[order];
}

/* Ticks per row straight play has at (order, row): the order's start speed and the Fxx of the rows before it (and of
 * the row itself once it has started). */
static int speed_at(const TiaImage* image, int order, int row, int started) {
    int s = image->info.start_speed[order];
    TiCursor cur[TI_CHANNELS];
    for (int c = 0; c < TI_CHANNELS; ++c) ti_pattern_cursor(image, order, c, &cur[c]);
    const int last = started ? row : row - 1;
    for (int r = 0; r <= last; ++r) {
        for (int c = 0; c < TI_CHANNELS; ++c) {
            TiRow x;
            ti_next_row(image->bytes, &cur[c], &x);
            for (int e = 0; e < x.effects; ++e)
                if (x.code[e] == TI_FX_SPEED) s = x.param[e];
        }
    }
    return s;
}

void tip_relocate(TiPlayer* p, const TiaImage* old, const TiaImage* next) {
    if (p->audition) return;
    const TiInfo* info = &next->info;
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
        for (int c = 0; c < TI_CHANNELS; ++c) p->ch[c].pending = 0;
        p->speed = speed_at(next, p->order, p->row, 1);
        if (!started) advance(p, next); /* the clamped row counts as played: the next row follows it */
        return;
    }
    const int new_speed = speed_at(next, p->order, p->row, started);
    if (new_speed != old_speed) p->speed = new_speed;
    if (!started) return;
    /* The row has started: its jump and any still-delayed rows come from the new data. */
    for (int c = 0; c < TI_CHANNELS; ++c) {
        TiRow row;
        ti_read_row(next, p->order, c, p->row, &row, NULL);
        TiChannel* ch = &p->ch[c];
        ch->row = row;
        if (ch->pending) {
            const int g = row_delay(&row);
            ch->pending = g >= p->tick;
            ch->delay = g;
        }
    }
    derive_jump(p, next);
}

int tip_audition(TiPlayer* p, const TiaImage* image, int channel, int note, int slot, int fine) {
    if (channel < 0 || channel >= TI_CHANNELS || note < TI_NOTE_MIN || note > TI_NOTE_MAX || fine < -128 || fine > 127)
        return -1;
    if (!instrument(image, slot)) return -1;
    reset(p, image, 1);
    p->ch[channel].instrument = slot;
    key_on(p, image, channel, note);
    p->ch[channel].fine = fine;
    return 0;
}
