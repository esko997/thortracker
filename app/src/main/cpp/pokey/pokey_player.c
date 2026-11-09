/* Thor Tracker's POKEY player (pokey_player.h). Specification: docs/AUDIO_ENGINE.md, "POKEY: player". Every rule
 * here has a counterpart in tools/audio/pokey/pokey_ref.py and the pitch search one in core's PokeyPitch.kt; change
 * them and the spec together. */
#include "pokey_player.h"

#include <math.h>
#include <string.h>

#include "pokey_chip.h"

_Static_assert((-1 >> 1) == -1, "the vibrato offset uses an arithmetic (floor) right shift");

/* Vibrato: floor(127 sin(2 pi i / 64) + 0.5), the OPL3 player's table (generated/opl3_tables.h O3_SINE), copied so the
 * POKEY player does not depend on the OPL3's generated header. */
static const int8_t SINE[64] = {
    0,    12,   25,   37,   49,   60,   71,   81,   90,   98,   106,  112,  117,  122,  125,  126,
    127,  126,  125,  122,  117,  112,  106,  98,   90,   81,   71,   60,   49,   37,   25,   12,
    0,    -12,  -25,  -37,  -49,  -60,  -71,  -81,  -90,  -98,  -106, -112, -117, -122, -125, -126,
    -127, -126, -125, -122, -117, -112, -106, -98,  -90,  -81,  -71,  -60,  -49,  -37,  -25,  -12,
};

static const int ENV_FIELD[PKP_ENVS] = {PK_I_ENV_VOLUME, PK_I_ENV_ARPEGGIO, PK_I_ENV_PITCH, PK_I_ENV_DISTORTION};
static const int ENV_DEFAULT[PKP_ENVS] = {15, 0, 0, PK_DIST_PURE};

#define AUDF(c) (2 * (c))
#define AUDC(c) (2 * (c) + 1)

static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

double pkp_clock(int machine) { return machine == PK_MACHINE_PAL ? PK_CLOCK_PAL : PK_CLOCK_NTSC; }

/* ---- writes ---------------------------------------------------------------------------------- */

static void emit(PkPlayer* p, int reg, int val) {
    p->shadow[reg] = (uint8_t)val;
    if (p->writes < PKP_MAX_WRITES) p->write[p->writes++] = (PkWrite){(uint8_t)reg, (uint8_t)val};
}

static void wr(PkPlayer* p, int reg, int val) {
    if (p->shadow[reg] != (uint8_t)val) emit(p, reg, val);
}

/* ---- image access ---------------------------------------------------------------------------- */

static const uint8_t* instrument(const PokeyImage* image, int slot) {
    if (slot < 1 || slot > image->info.instruments) return NULL;
    const uint8_t* rec = image->bytes + PK_OFF_INSTRUMENTS + PK_INSTRUMENT_SIZE * (slot - 1);
    return (rec[PK_I_FLAGS] & PK_FLAG_DEFINED) ? rec : NULL;
}

/* Envelope k of slot's record in the current image, or NULL. */
static const uint8_t* envelope(const PokeyImage* image, int slot, int k) {
    const uint8_t* rec = instrument(image, slot);
    if (!rec) return NULL;
    const int off = pk_u16(rec + ENV_FIELD[k]);
    return off == PK_ENV_NONE ? NULL : image->bytes + PK_OFF_DATA + off;
}

static int env_length(const uint8_t* e) { return pk_u16(e); }
static int env_loop(const uint8_t* e) { return pk_u16(e + 2); }
static int env_release(const uint8_t* e) { return pk_u16(e + 4); }

static int env_value(const uint8_t* e, int k, int step) {
    if (!e) return ENV_DEFAULT[k];
    const int length = env_length(e);
    const int i = step < length ? step : length - 1; /* a shortened envelope: its last step */
    const uint8_t v = e[PK_ENV_HEADER + i];
    return (k == PKP_ENV_ARPEGGIO || k == PKP_ENV_PITCH) ? (int8_t)v : v;
}

/* FamiStudio's step rule. */
static int env_next(const uint8_t* e, int step, int released) {
    const int length = env_length(e), loop = env_loop(e), release = env_release(e);
    if (step >= length) step = length - 1;
    const int next = step + 1;
    if (release != PK_ENV_NONE && !released && next == release) return loop != PK_ENV_NONE ? loop : release - 1;
    if (next >= length) return (loop != PK_ENV_NONE && release == PK_ENV_NONE) ? loop : length - 1;
    return next;
}

/* Sample k of the image: offset into the sample region and length in levels; 0 if the image has no such sample. */
static int sample_bounds(const PokeyImage* image, int k, int* offset) {
    if (k < 0 || k >= image->info.samples) return 0;
    const uint8_t* s = image->bytes + PK_OFF_SAMPLES + PK_SAMPLE_ENTRY_SIZE * k;
    *offset = pk_u16(s);
    return pk_u16(s + 2);
}

static int sample_level(const PokeyImage* image, int offset, int index) {
    return (image->bytes[PK_OFF_SAMPLE_DATA + offset + (index >> 1)] >> ((index & 1) * 4)) & 15;
}

/* ---- kinds and channels ---------------------------------------------------------------------- */

int pkp_kind(int flags) {
    if (flags & PK_FLAG_SAMPLE) return PKP_SAMPLE;
    const int j = flags & PK_FLAG_JOINED, f = flags & PK_FLAG_FILTERED;
    return j && f ? PKP_JOINED_FILTERED : j ? PKP_JOINED : f ? PKP_FILTERED : PKP_TONE;
}

int pkp_can_play(int flags, int lane) {
    const int fast = flags & PK_FLAG_FAST;
    if (lane < 0 || lane >= PK_CHANNELS) return 0;
    switch (pkp_kind(flags)) {
    case PKP_TONE: return !fast || lane == 0 || lane == 2;
    case PKP_JOINED: return lane == 1 || lane == 3;
    case PKP_FILTERED: return lane == 0 || (lane == 1 && !fast);
    case PKP_JOINED_FILTERED: return lane == 1;
    default: return (lane == 0 || lane == 1 || lane == 3) && (!fast || lane == 0);
    }
}

/* The POKEY channels a kind takes on a lane, its own first. */
static int taken(int kind, int lane, int* ch) {
    switch (kind) {
    case PKP_JOINED: ch[0] = lane, ch[1] = lane - 1; return 2;
    case PKP_FILTERED: ch[0] = lane, ch[1] = lane + 2; return 2;
    case PKP_JOINED_FILTERED: ch[0] = 1, ch[1] = 0, ch[2] = 3, ch[3] = 2; return 4;
    default: ch[0] = lane; return 1;
    }
}

/* The AUDCTL bits a voice sets. */
static int voice_audctl(const PkVoice* v, int lane) {
    const int fast = v->flags & PK_FLAG_FAST;
    switch (v->kind) {
    case PKP_TONE: return fast ? (lane == 0 ? PK_AUDCTL_CH1_FAST : PK_AUDCTL_CH3_FAST) : 0;
    case PKP_JOINED:
        return lane == 1 ? PK_AUDCTL_JOIN12 | (fast ? PK_AUDCTL_CH1_FAST : 0) : PK_AUDCTL_JOIN34 | (fast ? PK_AUDCTL_CH3_FAST : 0);
    case PKP_FILTERED:
        return lane == 0 ? PK_AUDCTL_HP13 | (fast ? PK_AUDCTL_CH1_FAST | PK_AUDCTL_CH3_FAST : 0) : PK_AUDCTL_HP24;
    case PKP_JOINED_FILTERED:
        return PK_AUDCTL_JOIN12 | PK_AUDCTL_JOIN34 | PK_AUDCTL_HP24 | (fast ? PK_AUDCTL_CH1_FAST | PK_AUDCTL_CH3_FAST : 0);
    default: return fast ? PK_AUDCTL_CH1_FAST : 0;
    }
}

static int timer_bit(int lane) { return lane == 0 ? PK_IRQ_TIMER1 : lane == 1 ? PK_IRQ_TIMER2 : PK_IRQ_TIMER4; }

/* ---- pitch to counter (doubles; PokeyPitch.kt and pokey_ref.py do the same operations) ------------------------ */

typedef struct Counter {
    int fast, wide; /* 1.79 MHz; 16-bit */
    long m;         /* base clock: cycles per count (28 or 114) */
} Counter;

static long period(const Counter* k, long n) { return k->fast ? n + (k->wide ? 7 : 4) : k->m * (n + 1); }
static long counter_max(const Counter* k) { return k->wide ? 65535 : 255; }

/* The N whose period is nearest x cycles (16-bit windows), clamped to 0..hi. */
static long nearest(const Counter* k, double x, long hi) {
    long n = k->fast ? llround(x) - (k->wide ? 7 : 4) : llround(x / (double)k->m) - 1;
    return n < 0 ? 0 : n > hi ? hi : n;
}

static long gcd(long a, long b) {
    while (b) {
        const long t = a % b;
        a = b;
        b = t;
    }
    return a;
}

/* Candidates: every 8-bit N, or the 49 16-bit values around the N nearest pt (clipped). The N minimising
 * |P(N) - pt| among those whose period is coprime to `mod` and at least min_period, or among all (with the period
 * limit) if none is; ties take the smaller N. */
static long search(const Counter* k, double pt, long mod, long min_period) {
    long lo = 0, hi = counter_max(k);
    if (k->wide) {
        const long n0 = nearest(k, pt, hi);
        lo = n0 - 24 < 0 ? 0 : n0 - 24;
        hi = n0 + 24 > hi ? hi : n0 + 24;
    }
    for (int pass = 0; pass < 2; ++pass) {
        long best = -1;
        double best_d = 0;
        for (long n = lo; n <= hi; ++n) {
            const long per = period(k, n);
            if (per < min_period || (pass == 0 && gcd(per, mod) != 1)) continue;
            const double d = fabs((double)per - pt);
            if (best < 0 || d < best_d) best = n, best_d = d;
        }
        if (best >= 0) return best;
    }
    return hi;
}

/* Difference sounds: N minimising |C/P(N) - C/P(N + d) - f|. */
static long search_difference(const Counter* k, double clock, double f, int d) {
    const long max = counter_max(k) - d;
    long lo = 0, hi = max;
    if (k->wide) {
        const double delta = (double)(k->fast ? d : k->m * d);
        const double x = (sqrt(delta * delta + 4.0 * clock * delta / f) - delta) / 2.0;
        const long n0 = nearest(k, x, max);
        lo = n0 - 24 < 0 ? 0 : n0 - 24;
        hi = n0 + 24 > max ? max : n0 + 24;
    }
    long best = lo;
    double best_d = -1;
    for (long n = lo; n <= hi; ++n) {
        const double diff = clock / (double)period(k, n) - clock / (double)period(k, n + d);
        const double e = fabs(diff - f);
        if (best_d < 0 || e < best_d) best = n, best_d = e;
    }
    return best;
}

/* Fires per waveform period (D) and the pattern length the period must be coprime to (M) of a distortion. */
static void divisor(int dist, double* D, long* M) {
    switch (dist) {
    case 0: *D = 2, *M = 31; break;
    case 1: case 3: *D = 62, *M = 31; break;
    case 2: *D = 465, *M = 465; break;
    case 6: *D = 15, *M = 15; break;
    default: *D = 2, *M = 1; break; /* 4 (noise), 5, 7 (pure), 8 (volume only) */
    }
}

static double pitch_hz(int p) { return 440.0 * pow(2.0, (double)(p - PKP_PITCH_A4) / 768.0); }

/* ---- voices ---------------------------------------------------------------------------------- */

/* The voice of lane l ends: its channels are free (AUDC 0 unless retaken). */
static void end_voice(PkPlayer* p, int l, int* freed) {
    PkVoice* v = &p->lane[l].voice;
    if (!v->active) return;
    for (int i = 0; i < v->nchannels; ++i) {
        const int c = v->channels[i];
        if (p->holder[c] == l) p->holder[c] = -1;
        freed[c] = 1;
    }
    v->active = 0;
    v->playing = 0;
}

static void key_on(PkPlayer* p, const PokeyImage* image, int l, int note) {
    PkLane* lane = &p->lane[l];
    const uint8_t* rec = instrument(image, lane->instrument);
    if (!rec) return;
    const int flags = rec[PK_I_FLAGS];
    if (!pkp_can_play(flags, l)) return;
    const int kind = pkp_kind(flags);
    int ch[4] = {0, 0, 0, 0};
    const int n = taken(kind, l, ch);
    int freed[4] = {0, 0, 0, 0};
    end_voice(p, l, freed);
    for (int i = 0; i < n; ++i)
        if (p->holder[ch[i]] >= 0) end_voice(p, p->holder[ch[i]], freed); /* cut */
    for (int i = 0; i < n; ++i) {
        p->holder[ch[i]] = l;
        freed[ch[i]] = 0;
    }
    for (int c = 0; c < 4; ++c)
        if (freed[c]) p->want[AUDC(c)] = 0;
    PkVoice* v = &lane->voice;
    memset(v, 0, sizeof *v);
    v->active = 1;
    v->kind = kind;
    v->flags = flags;
    v->slot = lane->instrument;
    memcpy(v->channels, ch, sizeof ch);
    v->nchannels = n;
    v->filter_offset = (int8_t)rec[PK_I_FILTER_OFFSET];
    v->partner = rec[PK_I_PARTNER_AUDC];
    if (kind == PKP_SAMPLE) {
        v->sample = rec[PK_I_SAMPLE];
        v->root = rec[PK_I_ROOT];
        v->rate = pk_u16(rec + PK_I_RATE);
        v->loop = pk_u16(rec + PK_I_LOOP);
        v->playing = 1;
        v->index = 0;
    }
    lane->base = lane->target = note * 64;
    lane->slide = 0;
    lane->arp_step = 0;
    lane->vib_phase = 0;
}

/* OFF: envelopes with a release point go to it; samples stop; without a volume release point the voice is silent. */
static void release_voice(PkPlayer* p, const PokeyImage* image, int l) {
    PkVoice* v = &p->lane[l].voice;
    if (!v->active || v->released) return;
    v->released = 1;
    for (int k = 0; k < PKP_ENVS; ++k) {
        const uint8_t* e = envelope(image, v->slot, k);
        if (e && env_release(e) != PK_ENV_NONE) v->env_step[k] = env_release(e);
    }
    if (v->kind == PKP_SAMPLE) {
        v->playing = 0;
    } else {
        const uint8_t* e = envelope(image, v->slot, PKP_ENV_VOLUME);
        if (!e || env_release(e) == PK_ENV_NONE) v->silenced = 1;
    }
}

void pkp_key_off(PkPlayer* p, const PokeyImage* image, int lane) {
    if (lane >= 0 && lane < PK_CHANNELS) release_voice(p, image, lane);
}

static int level_of(int env, int vol) { return (env * vol + 14) / 15; }

/* The voice's counters for this tick: N (and the high-pass clock's N'), searched as the spec says. The search is a
 * pure function of the memo's inputs, so an unchanged tick reuses its result. */
static void counters(const PkPlayer* p, const PokeyImage* image, const PkLane* lane, PkVoice* v, int pitch, int dist, long* n,
                     long* n2) {
    const int m = (p->shared & PK_AUDCTL_BASE15) ? 114 : 28;
    const int filt = lane->has_filter ? lane->filter : v->filter_offset;
    const int key[6] = {pitch, v->kind == PKP_SAMPLE ? 0 : dist, m, image->info.machine, filt, 1};
    if (v->memo_valid && !memcmp(key, v->memo, sizeof key)) {
        *n = v->counter;
        *n2 = v->clock_counter;
        return;
    }
    const double clock = pkp_clock(image->info.machine);
    const int wide = v->kind == PKP_JOINED || v->kind == PKP_JOINED_FILTERED;
    const Counter k = {(v->flags & PK_FLAG_FAST) != 0, wide, m};
    *n2 = 0;
    if (v->kind == PKP_SAMPLE) {
        const double rate = v->rate * pow(2.0, (double)(pitch - 64 * v->root) / 768.0);
        *n = search(&k, clock / rate, 1, PK_PCM_MIN_PERIOD);
    } else if ((v->flags & PK_FLAG_DIFFERENCE) && (v->kind == PKP_FILTERED || v->kind == PKP_JOINED_FILTERED)) {
        const int d = clamp(filt, 1, 127);
        *n = search_difference(&k, clock, pitch_hz(pitch), d);
        *n2 = *n + d;
    } else {
        double D;
        long M;
        divisor(dist, &D, &M);
        *n = search(&k, clock / (pitch_hz(pitch) * D), M, 0);
        *n2 = *n + filt;
        if (*n2 < 0) *n2 = 0;
        if (*n2 > counter_max(&k)) *n2 = counter_max(&k);
    }
    memcpy(v->memo, key, sizeof key);
    v->memo_valid = 1;
    v->counter = (int)*n;
    v->clock_counter = (int)*n2;
}

/* The voice's registers into want[] (pitch search, levels), then its lane's effects and envelopes advance. */
static void update(PkPlayer* p, const PokeyImage* image, int l) {
    PkLane* lane = &p->lane[l];
    PkVoice* v = &lane->voice;
    if (!v->active) return;
    const uint8_t* env[PKP_ENVS];
    int val[PKP_ENVS];
    for (int k = 0; k < PKP_ENVS; ++k) {
        env[k] = envelope(image, v->slot, k);
        val[k] = env_value(env[k], k, v->env_step[k]);
    }
    int pitch_env = val[PKP_ENV_PITCH];
    if (v->flags & PK_FLAG_PITCH_RELATIVE) {
        v->pitch_acc = clamp(v->pitch_acc + pitch_env, -PKP_RELATIVE_LIMIT, PKP_RELATIVE_LIMIT);
        pitch_env = v->pitch_acc;
    }
    int arp = 0;
    if (lane->arp && lane->arp_step) arp = lane->arp_step == 1 ? lane->arp >> 4 : lane->arp & 15;
    const int vib = lane->vib_depth ? (SINE[lane->vib_phase] * lane->vib_depth) >> 4 : 0;
    const int pitch = clamp(lane->base + 64 * (val[PKP_ENV_ARPEGGIO] + arp) + lane->fine + lane->slide + vib + pitch_env * 4, 0,
                            PKP_PITCH_MAX);
    const int vol = lane->vol8 >> 3;
    const int dist = lane->distortion >= 0 ? lane->distortion : val[PKP_ENV_DISTORTION];
    v->pitch = pitch;
    v->level = v->silenced ? 0 : level_of(val[PKP_ENV_VOLUME], vol);

    const int own = l;
    long n, n2;
    counters(p, image, lane, v, pitch, dist, &n, &n2);
    if (v->kind == PKP_SAMPLE) {
        int offset = 0;
        const int length = sample_bounds(image, v->sample, &offset);
        if (v->playing && v->index >= length) v->playing = 0; /* the sample shrank under a live edit */
        p->want[AUDF(own)] = (uint8_t)n;
        p->want[AUDC(own)] = v->playing ? (uint8_t)(0x10 | level_of(sample_level(image, offset, v->index), vol)) : 0;
    } else {
        const int audc = dist == PK_DIST_VOLUME_ONLY ? 0x10 | v->level : dist << 5 | v->level;
        switch (v->kind) {
        case PKP_TONE:
            p->want[AUDF(own)] = (uint8_t)n;
            p->want[AUDC(own)] = (uint8_t)audc;
            break;
        case PKP_JOINED:
            p->want[AUDF(own - 1)] = (uint8_t)(n & 0xFF);
            p->want[AUDF(own)] = (uint8_t)(n >> 8);
            p->want[AUDC(own)] = (uint8_t)audc;
            p->want[AUDC(own - 1)] = (uint8_t)v->partner;
            break;
        case PKP_FILTERED:
            p->want[AUDF(own)] = (uint8_t)n;
            p->want[AUDF(own + 2)] = (uint8_t)n2;
            p->want[AUDC(own)] = (uint8_t)audc;
            p->want[AUDC(own + 2)] = (uint8_t)v->partner;
            break;
        default: /* 16-bit high-pass: 1+2 the sound, 3+4 its clock */
            p->want[AUDF(0)] = (uint8_t)(n & 0xFF);
            p->want[AUDF(1)] = (uint8_t)(n >> 8);
            p->want[AUDF(2)] = (uint8_t)(n2 & 0xFF);
            p->want[AUDF(3)] = (uint8_t)(n2 >> 8);
            p->want[AUDC(1)] = (uint8_t)audc;
            p->want[AUDC(0)] = p->want[AUDC(2)] = p->want[AUDC(3)] = (uint8_t)v->partner;
            break;
        }
    }
    /* effects advance (as the OPL3 player's) */
    if (lane->arp) lane->arp_step = (lane->arp_step + 1) % 3;
    if (lane->slide_mode == 1) lane->slide = clamp(lane->slide + lane->slide_speed, -6912, 6912);
    if (lane->slide_mode == 2) lane->slide = clamp(lane->slide - lane->slide_speed, -6912, 6912);
    if (lane->slide_mode == 3) {
        if (lane->base < lane->target) lane->base = clamp(lane->base + lane->slide_speed, 0, lane->target);
        else if (lane->base > lane->target) lane->base = clamp(lane->base - lane->slide_speed, lane->target, PKP_PITCH_MAX);
    }
    if (lane->vib_depth && lane->vib_speed) lane->vib_phase = (lane->vib_phase + lane->vib_speed) & 63;
    if (lane->volslide) lane->vol8 = clamp(lane->vol8 + (lane->volslide >> 4) - (lane->volslide & 15), 0, 120);
    /* envelopes step */
    if (!v->silenced)
        for (int e = 0; e < PKP_ENVS; ++e)
            if (env[e]) v->env_step[e] = env_next(env[e], v->env_step[e], v->released);
}

int pkp_voice_done(const PkPlayer* p, const PokeyImage* image, int l) {
    const PkVoice* v = &p->lane[l].voice;
    if (!v->active || v->silenced) return 1;
    if (v->kind == PKP_SAMPLE) return !v->playing;
    if (v->level) return 0;
    for (int k = 0; k < PKP_ENVS; ++k) {
        const uint8_t* e = envelope(image, v->slot, k);
        if (e && env_next(e, v->env_step[k], v->released) != (v->env_step[k] < env_length(e) ? v->env_step[k] : env_length(e) - 1))
            return 0;
    }
    return 1;
}

/* ---- rows ------------------------------------------------------------------------------------ */

static void process_row(PkPlayer* p, const PokeyImage* image, int l) {
    PkLane* lane = &p->lane[l];
    const PkRow* row = &lane->row;
    const int has_note = row->note >= 0 && row->note < PK_NOTES;
    if (has_note) {
        lane->arp = 0;
        lane->fine = 0;
        lane->distortion = -1;
        lane->has_filter = 0;
    }
    if (row->instrument) lane->instrument = row->instrument;
    for (int e = 0; e < row->effects; ++e) {
        const int v = row->param[e], x = v >> 4, y = v & 15;
        switch (row->code[e]) {
        case PK_FX_ARPEGGIO: lane->arp = v; lane->arp_step = 0; break;
        case PK_FX_SLIDE_UP: lane->slide_mode = v ? 1 : 0; lane->slide_speed = v; break;
        case PK_FX_SLIDE_DOWN: lane->slide_mode = v ? 2 : 0; lane->slide_speed = v; break;
        case PK_FX_PORTAMENTO: lane->slide_mode = v ? 3 : 0; lane->slide_speed = v; break;
        case PK_FX_VIBRATO: lane->vib_speed = x; lane->vib_depth = y; break;
        case PK_FX_VOLUME_SLIDE: lane->volslide = v; break;
        case PK_FX_VOLUME: lane->vol8 = v * 8; break;
        case PK_FX_FILTER: lane->has_filter = 1; lane->filter = (int8_t)v; break;
        case PK_FX_FINE: lane->fine = v - PK_FINE_CENTRE; break;
        case PK_FX_CUT: lane->cut = v; break;
        case PK_FX_DISTORTION: lane->distortion = v; break;
        }
    }
    if (row->note == PK_NOTE_OFF) {
        release_voice(p, image, l);
    } else if (has_note) {
        /* Portamento glides only a held voice: keyed on, not released, and (a sample) still playing. A one-shot sample
         * the timer interrupt has run to its end keys on afresh; a synthesized voice whose envelopes have ended is
         * still held. */
        const PkVoice* v = &lane->voice;
        const int held = v->active && !v->released && (v->kind != PKP_SAMPLE || v->playing);
        if (lane->slide_mode == 3 && held) lane->target = row->note * 64;
        else key_on(p, image, l, row->note);
    }
}

static void read_rows(PkPlayer* p, const PokeyImage* image) {
    if (p->cursor_order != p->order || p->cursor_row != p->row) {
        for (int c = 0; c < PK_CHANNELS; ++c) {
            PkCursor cur = {pk_u16(image->bytes + PK_OFF_ORDERS + 2 * (PK_CHANNELS * p->order + c)), 0};
            PkRow skip;
            for (int r = 0; r < p->row; ++r) pk_next_row(image->bytes, &cur, &skip);
            p->cursor[c] = cur;
        }
    }
    for (int c = 0; c < PK_CHANNELS; ++c) pk_next_row(image->bytes, &p->cursor[c], &p->lane[c].row);
    p->cursor_order = p->order;
    p->cursor_row = p->row + 1;
}

/* B/D of the current row (rows already read) -> p->jump. */
static void derive_jump(PkPlayer* p, const PokeyImage* image) {
    int has_b = 0, has_d = 0, b = 0, d = 0;
    for (int c = 0; c < PK_CHANNELS; ++c) {
        const PkRow* row = &p->lane[c].row;
        for (int e = 0; e < row->effects; ++e) {
            if (row->code[e] == PK_FX_JUMP) has_b = 1, b = row->param[e];
            if (row->code[e] == PK_FX_BREAK) has_d = 1, d = row->param[e];
        }
    }
    p->jump = has_b || has_d;
    p->jump_order = has_b ? b : p->order + 1 < image->info.orders ? p->order + 1 : image->info.loop;
    p->jump_row = has_d ? d : 0;
}

static int row_delay(const PkRow* row) {
    int g = 0;
    for (int e = 0; e < row->effects; ++e)
        if (row->code[e] == PK_FX_DELAY) g = row->param[e];
    return g;
}

static void row_start(PkPlayer* p, const PokeyImage* image) {
    read_rows(p, image);
    /* global effects: lanes, then columns; the last one wins */
    for (int c = 0; c < PK_CHANNELS; ++c) {
        const PkRow* row = &p->lane[c].row;
        for (int e = 0; e < row->effects; ++e) {
            const int v = row->param[e];
            switch (row->code[e]) {
            case PK_FX_SPEED: p->speed = v; break;
            case PK_FX_CLOCK: p->shared = (p->shared & ~PK_AUDCTL_BASE15) | (v ? PK_AUDCTL_BASE15 : 0); break;
            case PK_FX_POLY: p->shared = (p->shared & ~PK_AUDCTL_POLY9) | (v ? PK_AUDCTL_POLY9 : 0); break;
            case PK_FX_TWO_TONE: p->two_tone = v; break;
            case PK_FX_RESTART: p->restart = 1; break;
            case PK_FX_TIMERS: p->stimer = 1; break;
            }
        }
    }
    derive_jump(p, image);
    for (int c = 0; c < PK_CHANNELS; ++c) {
        PkLane* lane = &p->lane[c];
        lane->cut = -1;
        lane->pending = 0;
        const int g = row_delay(&lane->row);
        if (g) {
            lane->pending = 1;
            lane->delay = g;
        } else {
            process_row(p, image, c);
        }
    }
}

/* Next row. Every arrival at the loop order's row 0 starts it with the speed, shared AUDCTL bits and two-tone state
 * sequential straight play reaches it with, so every pass of the loop plays alike. */
static void advance(PkPlayer* p, const PokeyImage* image) {
    const PkInfo* info = &image->info;
    if (p->jump) {
        p->order = p->jump_order;
        p->row = p->jump_row < info->rows[p->order] ? p->jump_row : info->rows[p->order] - 1;
        p->jump = 0;
    } else if (++p->row >= info->rows[p->order]) {
        p->row = 0;
        if (++p->order >= info->orders) p->order = info->loop;
    }
    if (p->order == info->loop && p->row == 0) {
        p->speed = info->start_speed[p->order];
        p->shared = info->start_audctl[p->order];
        p->two_tone = info->start_two_tone[p->order];
    }
}

/* ---- batches --------------------------------------------------------------------------------- */

static void write_batch(PkPlayer* p) {
    int audctl = p->shared, irqen = 0;
    for (int l = 0; l < PK_CHANNELS; ++l) {
        const PkVoice* v = &p->lane[l].voice;
        if (!v->active) continue;
        audctl |= voice_audctl(v, l);
        if (v->kind == PKP_SAMPLE && v->playing) irqen |= timer_bit(l);
    }
    const int skctl = 0x03 | (p->two_tone ? PK_SKCTL_TWO_TONE : 0) | (p->two_tone == 2 ? PK_SKCTL_BREAK : 0);
    p->want[PK_SKCTL] = (uint8_t)skctl;
    p->want[PK_AUDCTL] = (uint8_t)audctl;
    p->want[PK_IRQEN] = (uint8_t)irqen;
    if (p->restart) {
        emit(p, PK_SKCTL, skctl & ~PK_SKCTL_INIT_MASK);
        emit(p, PK_SKCTL, skctl);
    } else {
        wr(p, PK_SKCTL, skctl);
    }
    wr(p, PK_AUDCTL, audctl);
    for (int reg = PK_AUDF1; reg <= PK_AUDC4; ++reg) wr(p, reg, p->want[reg]);
    wr(p, PK_IRQEN, irqen);
    if (p->stimer) emit(p, PK_STIMER, 0);
    p->restart = p->stimer = 0;
}

void pkp_tick(PkPlayer* p, const PokeyImage* image) {
    p->writes = 0;
    if (!p->audition) {
        if (p->tick == 0) {
            row_start(p, image);
        } else {
            for (int c = 0; c < PK_CHANNELS; ++c) {
                if (p->lane[c].pending && p->lane[c].delay == p->tick) {
                    p->lane[c].pending = 0;
                    process_row(p, image, c);
                }
            }
        }
        for (int c = 0; c < PK_CHANNELS; ++c)
            if (p->lane[c].cut == p->tick) release_voice(p, image, c);
    }
    for (int c = 0; c < PK_CHANNELS; ++c) update(p, image, c);
    write_batch(p);
    if (!p->audition && ++p->tick >= p->speed) {
        p->tick = 0;
        advance(p, image);
    }
}

void pkp_irq(PkPlayer* p, const PokeyImage* image, uint8_t timers) {
    static const int lanes[3] = {0, 1, 3};
    p->writes = 0;
    for (int i = 0; i < 3; ++i) {
        const int l = lanes[i], bit = timer_bit(l);
        if (!(timers & bit) || p->holder[l] != l) continue;
        PkLane* lane = &p->lane[l];
        PkVoice* v = &lane->voice;
        if (!v->active || v->kind != PKP_SAMPLE || !v->playing) continue;
        int offset = 0;
        const int length = sample_bounds(image, v->sample, &offset);
        int next = v->index + 1;
        if (next >= length) next = v->loop != 0xFFFF && v->loop < length ? v->loop : -1;
        if (next < 0) {
            v->playing = 0;
            p->want[AUDC(l)] = 0;
            wr(p, AUDC(l), 0);
            p->want[PK_IRQEN] = (uint8_t)(p->want[PK_IRQEN] & ~bit);
            wr(p, PK_IRQEN, p->want[PK_IRQEN]);
        } else {
            v->index = next;
            p->want[AUDC(l)] = (uint8_t)(0x10 | level_of(sample_level(image, offset, next), lane->vol8 >> 3));
            wr(p, AUDC(l), p->want[AUDC(l)]);
        }
    }
}

/* ---- reset, placement, live edits, audition -------------------------------------------------- */

void pkp_reset(PkPlayer* p, const PokeyImage* image) {
    memset(p, 0, sizeof *p);
    for (int c = 0; c < PK_CHANNELS; ++c) {
        p->lane[c].cut = -1;
        p->lane[c].vol8 = 120;
        p->lane[c].distortion = -1;
        p->holder[c] = -1;
    }
    p->cursor_order = p->cursor_row = -1;
    p->speed = image->info.speed;
    p->shared = image->bytes[PK_H_AUDCTL];
    /* The reset batch is emitted in full whatever the shadow holds: a defined start for the chip and every VGM player. */
    emit(p, PK_SKCTL, 0);
    emit(p, PK_AUDCTL, p->shared);
    for (int reg = PK_AUDF1; reg <= PK_AUDC4; ++reg) emit(p, reg, 0);
    emit(p, PK_IRQEN, 0);
    emit(p, PK_SKCTL, 0x03);
    memcpy(p->want, p->shadow, sizeof p->want);
}

void pkp_place(PkPlayer* p, const PokeyImage* image, int order) {
    pkp_reset(p, image);
    p->order = order;
    p->speed = image->info.start_speed[order];
    p->shared = image->info.start_audctl[order];
    p->two_tone = image->info.start_two_tone[order];
}

/* Ticks per row straight play has at (order, row): the order's start speed and the Fxx of the rows before it (and of
 * the row itself once it has started). */
static int speed_at(const PokeyImage* image, int order, int row, int started) {
    int s = image->info.start_speed[order];
    PkCursor cur[PK_CHANNELS];
    for (int c = 0; c < PK_CHANNELS; ++c) cur[c] = (PkCursor){pk_u16(image->bytes + PK_OFF_ORDERS + 2 * (PK_CHANNELS * order + c)), 0};
    const int last = started ? row : row - 1;
    for (int r = 0; r <= last; ++r) {
        for (int c = 0; c < PK_CHANNELS; ++c) {
            PkRow x;
            pk_next_row(image->bytes, &cur[c], &x);
            for (int e = 0; e < x.effects; ++e)
                if (x.code[e] == PK_FX_SPEED) s = x.param[e];
        }
    }
    return s;
}

void pkp_relocate(PkPlayer* p, const PokeyImage* old, const PokeyImage* next) {
    if (p->audition) return;
    const PkInfo* info = &next->info;
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
        for (int c = 0; c < PK_CHANNELS; ++c) p->lane[c].pending = 0;
        p->speed = speed_at(next, p->order, p->row, 1);
        if (!started) advance(p, next); /* the clamped row counts as played: the next row follows it */
        return;
    }
    const int new_speed = speed_at(next, p->order, p->row, started);
    if (new_speed != old_speed) p->speed = new_speed;
    if (!started) return;
    /* The row has started: its jump and any still-delayed rows come from the new data. */
    for (int c = 0; c < PK_CHANNELS; ++c) {
        PkRow row;
        pk_read_row(next, p->order, c, p->row, &row, NULL);
        PkLane* lane = &p->lane[c];
        lane->row = row;
        if (lane->pending) {
            const int g = row_delay(&row);
            lane->pending = g >= p->tick;
            lane->delay = g;
        }
    }
    derive_jump(p, next);
}

int pkp_audition(PkPlayer* p, const PokeyImage* image, int lane, int note, int slot, int fine) {
    if (lane < 0 || lane >= PK_CHANNELS || note < 0 || note >= PK_NOTES || fine < -128 || fine > 127) return -1;
    const uint8_t* rec = instrument(image, slot);
    if (!rec || !pkp_can_play(rec[PK_I_FLAGS], lane)) return -1;
    pkp_reset(p, image);
    p->audition = 1;
    p->lane[lane].instrument = slot;
    key_on(p, image, lane, note);
    p->lane[lane].fine = fine;
    return 0;
}
