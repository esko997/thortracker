/* Host regression test for the SAA1099 systems ("sam-saa1099", "cms-saa1099"): Thor Tracker's own player
 * (saa/saa_player.c) on our clock-exact port of SAASound's SAA1099 model (saa/saa_core.c), playing the hand-built test
 * songs tools/audio/saa/saa_song.py (saa_test_song.h, from tools/audio/prepare_saa.py), plus small images built here.
 *
 * Checks: the core against its documented model (reset state; half-periods H = (511 - N) << (8 - O) at the corners;
 * offset/octave data latching at the end of the running half-cycle; sync holding every generator, applying data at
 * once and restarting them in phase; the noise prescaler rates and the clock-3 coupling to tone 0 / 3; the LFSR
 * sequence and period; every envelope shape in both resolutions, inversion, buffering until the cycle's end, immediate
 * disable, a resolution change mid-cycle, the external clock per address; the mixer-off envelope level; the tone +
 * noise rule; outputs 0 while synced or disabled; sac_advance, sac_render and a clock-by-clock render leaving
 * memcmp-equal state and the same events after pseudo-random write streams); the divider tables and the octave/offset
 * formula (A-4 = SAM O 3 N 227, CMS O 4 N 3; the note ranges within 3.4 cents); image validation (single-byte
 * corruptions and small constructed images, each rejected with its reason and byte); register writes and positions
 * identical to the independent Python reference player (tools/audio/saa/saa_ref.py) in each of SAA_REF_TICKS ticks for
 * the SAM and CMS songs, a note edit and a tempo edit, and the reset batch; the cores' registers following the
 * player's shadow; deterministic, non-silent, unclipped stereo output; loop passes; seeks (positions, writes, core state
 * memcmp-equal to straight play's at the target, output within 1/32768, bit-identical inside the pre-roll) and the
 * order fallback; live edits (re-laid image, note edit, tempo edit, cut song, multispeed, a hardware and volume
 * envelope edited under a sounding note, an instrument deleted under a sounding note); audition (A-4 measured 440.14 Hz
 * on the SAM Coupe and 440.40 Hz on the CMS's chip B, fine pitch, releases, the 10 s release bound, argument limits);
 * mute; the shared noise generator and the envelope clocked by lane 2's (5's) tone; VGM bytes identical to the
 * reference's for both variants, its loop expanded over three passes equal to a fresh player's writes; the hosts' VGM
 * entry point and its GD3 tag; both ops tables as the hosts drive them, including the variant check; costs.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir]
 * Writes <dir>/saa_sam_song.wav and saa_cms_song.wav (48 kHz stereo, 30 s), saa_tone_sam.wav and saa_tone_cms.wav (the
 * A-4 audition, 2 s, and its release), saa_trace.txt (per-tick player writes of the SAM song), saa_chip_trace.txt (a
 * scripted single-chip register sequence and the core's output events), saa_sam_song.vgm and saa_cms_song.vgm (VGM
 * 1.71), saa_sam_song.bin and saa_cms_song.bin (the images): the inputs of tools/audio/saa_reference_check.py. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "chip_engine.h"
#include "generated/saa_tables.h"
#include "saa_core.h"
#include "saa_export.h"
#include "saa_machine.h"
#include "saa_test_song.h"
#include "saa_vgm.h"

static int failures = 0;
static int checks = 0;
#define EXPECT(cond, ...)                                              \
    do {                                                               \
        ++checks;                                                      \
        if (!(cond)) {                                                 \
            ++failures;                                                \
            printf("  FAIL %s:%d: ", __FILE__, __LINE__);              \
            printf(__VA_ARGS__);                                       \
            printf("\n");                                              \
        }                                                              \
    } while (0)

#define RATE 48000
#define SECONDS 30
#define A4 57
#define A4_HZ_SAM (8000000.0 * 8 / (512.0 * 284))  /* O 3 N 227: 440.141 Hz */
#define A4_HZ_CMS (7159090.0 * 16 / (512.0 * 508)) /* O 4 N 3: 440.396 Hz */
#define TONE_SLOT 1  /* saa_song.py: no envelopes, tone, pan 15/15 */
#define PLUCK_SLOT 2 /* a volume envelope with a release point, ending at 0 */
#define BUZZ_SLOT 3  /* the internal hardware envelope 0x86 with retrigger (lane 3) */
#define SEEK_BUDGET SAAM_SEEK_TICKS
#define VGM_MAX_TICKS (50 * 600) /* tools/audio/prepare_saa.py's */
#define MAX_EVENTS 262144
#define MAX_VGM_WRITES 400000
#define MAX_VGM_BATCHES 40000

static float g_out[SAAM_MAX_TICK_FRAMES * 2];
static SaaImage g_sam, g_cms, g_moved, g_edited, g_tempo, g_short, g_x2, g_env_edit;
static SaaImage g_bad, g_work, g_tone;

typedef struct Ref {
    const int* offsets;
    const unsigned char* chips;
    const unsigned char* regs;
    const unsigned char* vals;
    const short* pos;
} Ref;

#define REF(name) \
    {saa_ref_##name##_offsets, saa_ref_##name##_chips, saa_ref_##name##_regs, saa_ref_##name##_vals, saa_ref_##name##_pos}
static const Ref REF_SAM = REF(sam);
static const Ref REF_CMS = REF(cms);
static const Ref REF_EDITED = REF(edited);
static const Ref REF_TEMPO = REF(tempo);

/* ---- helpers ------------------------------------------------------------------------------------------------- */

static double now_us(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e6 + t.tv_nsec / 1e3;
}

static uint64_t fnv(uint64_t h, const void* data, size_t n) {
    const uint8_t* p = (const uint8_t*)data;
    for (size_t i = 0; i < n; ++i) h = (h ^ p[i]) * 0x100000001B3ull;
    return h;
}

static uint32_t rd32(const uint8_t* p) { return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24; }

static void put16(uint8_t* p, int v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
}

static void write_file(const char* dir, const char* name, const void* data, size_t size) {
    char path[512];
    snprintf(path, sizeof path, "%s/%s", dir, name);
    FILE* f = fopen(path, "wb");
    if (!f) return;
    fwrite(data, 1, size, f);
    fclose(f);
}

static void write_wav(const char* dir, const char* name, const float* data, int frames) {
    char path[512];
    snprintf(path, sizeof path, "%s/%s", dir, name);
    FILE* f = fopen(path, "wb");
    if (!f) return;
    int bytes = frames * 4;
    int hdr[] = {0x46464952, 36 + bytes, 0x45564157, 0x20746d66, 16, 0x00020001, RATE, RATE * 4, 0x00100004,
                 0x61746164, bytes};
    fwrite(hdr, sizeof hdr, 1, f);
    for (int i = 0; i < frames * 2; ++i) {
        float v = data[i] < -1 ? -1 : data[i] > 1 ? 1 : data[i];
        short s = (short)lrintf(v * 32767);
        fwrite(&s, 2, 1, f);
    }
    fclose(f);
}

/* Frequency from rising zero crossings of one side (0 left, 1 right) of interleaved stereo (mean removed). */
static double zero_cross_hz(const float* x, int n, int side) {
    double mean = 0;
    for (int i = 0; i < n; ++i) mean += x[2 * i + side];
    mean /= n;
    int count = 0;
    double first_t = -1, last_t = 0;
    for (int i = 1; i < n; ++i) {
        double a = x[2 * (i - 1) + side] - mean, b = x[2 * i + side] - mean;
        if (a < 0 && b >= 0) {
            double t = (i - 1) + a / (a - b);
            if (first_t < 0) first_t = t;
            else {
                last_t = t;
                ++count;
            }
        }
    }
    return count ? count * (double)RATE / (last_t - first_t) : 0;
}

static double rms(const float* x, int frames) {
    double s = 0;
    for (int i = 0; i < frames * 2; ++i) s += (double)x[i] * x[i];
    return frames > 0 ? sqrt(s / (frames * 2.0)) : 0;
}

static double peak(const float* x, int frames) {
    double p = 0;
    for (int i = 0; i < frames * 2; ++i)
        if (fabs(x[i]) > p) p = fabs(x[i]);
    return p;
}

/* Whether the two stereo channels differ somewhere (pans). */
static int stereo(const float* x, int frames) {
    for (int i = 0; i < frames; ++i)
        if (fabsf(x[2 * i] - x[2 * i + 1]) > 1e-4f) return 1;
    return 0;
}

static int render(SaaMachine* m, int ticks, float** buf) {
    float* out = (float*)malloc((size_t)ticks * SAAM_MAX_TICK_FRAMES * 2 * sizeof(float));
    int frames = 0;
    for (int t = 0; t < ticks; ++t) {
        int n = saam_tick(m, out + 2 * frames);
        if (n <= 0) break;
        frames += n;
    }
    *buf = out;
    return frames;
}

/* The generated images are stored up to their last non-zero byte: zero-padded to the image size here. */
static int load(SaaImage* image, const uint8_t* bytes, size_t used) {
    memset(image, 0, sizeof *image);
    memcpy(image->bytes, bytes, used);
    return sa_load_image(image);
}

static int ref_count(const Ref* r, int k) { return r->offsets[k + 1] - r->offsets[k]; }

/* Whether the player's last batch equals reference batch k (0 = the reset batch, k >= 1 = tick k - 1). */
static int same(const SaPlayer* p, const Ref* r, int k) {
    if (p->writes != ref_count(r, k)) return 0;
    for (int i = 0; i < p->writes; ++i) {
        const int j = r->offsets[k] + i;
        if (p->write[i].chip != r->chips[j] || p->write[i].reg != r->regs[j] || p->write[i].val != r->vals[j]) return 0;
    }
    return 1;
}

/* Whether batches k of two references have the same writes and positions. */
static int ref_batch_equal(const Ref* a, const Ref* b, int k) {
    if (ref_count(a, k) != ref_count(b, k)) return 0;
    for (int i = 0; i < 3; ++i)
        if (a->pos[3 * k + i] != b->pos[3 * k + i]) return 0;
    for (int i = 0; i < ref_count(a, k); ++i) {
        const int x = a->offsets[k] + i, y = b->offsets[k] + i;
        if (a->chips[x] != b->chips[y] || a->regs[x] != b->regs[y] || a->vals[x] != b->vals[y]) return 0;
    }
    return 1;
}

static int first_difference(const Ref* a, const Ref* b) {
    for (int k = 0; k <= SAA_REF_TICKS; ++k)
        if (!ref_batch_equal(a, b, k)) return k;
    return -1;
}

static int row_start_of(const Ref* r, int k) { return k - r->pos[3 * k + 2]; }

static int ref_row_start(const Ref* r, int order, int row) {
    for (int k = 1; k <= SAA_REF_TICKS; ++k)
        if (r->pos[3 * k] == order && r->pos[3 * k + 1] == row && r->pos[3 * k + 2] == 0) return k;
    return -1;
}

static int ref_row_length(const Ref* r, int k) {
    int n = 1;
    while (k + n <= SAA_REF_TICKS && r->pos[3 * (k + n) + 2] != 0) ++n;
    return n;
}

static int pos_is(const SaaMachine* m, const Ref* r, int k) {
    int o, w, t;
    saam_position(m, &o, &w, &t);
    return o == r->pos[3 * k] && w == r->pos[3 * k + 1] && t == r->pos[3 * k + 2];
}

/* The chips hold what the player's shadow says they hold (the envelope's enable bit; its other bits may be buffered). */
static int core_matches_shadow(const SaaMachine* m, const SaPlayer* p) {
    for (int chip = 0; chip < p->chips; ++chip) {
        const SacCore* c = saam_core(m, chip);
        const uint8_t* sh = p->sh[chip];
        for (int l = 0; l < SAC_CHANNELS; ++l) {
            if (c->ch[l].amp_l != (sh[l] & 15) || c->ch[l].amp_r != sh[l] >> 4) return 0;
            if (c->tone[l].next_off != sh[SAC_REG_OFFSET + l]) return 0;
            if (c->tone[l].next_oct != ((sh[SAC_REG_OCTAVE + l / 2] >> ((l & 1) ? 4 : 0)) & 7)) return 0;
            if (c->ch[l].tone != ((sh[SAC_REG_TONE] >> l) & 1) || c->ch[l].noise != ((sh[SAC_REG_NOISE] >> l) & 1)) return 0;
        }
        if (c->noise[0].mode != (sh[SAC_REG_NOISE_MODE] & 3) || c->noise[1].mode != ((sh[SAC_REG_NOISE_MODE] >> 4) & 3)) return 0;
        if (c->enable != (sh[SAC_REG_CONTROL] & 1) || c->sync != ((sh[SAC_REG_CONTROL] >> 1) & 1)) return 0;
        for (int e = 0; e < 2; ++e)
            if (c->env[e].enabled != (sh[SAC_REG_ENV0 + e] >> 7)) return 0;
    }
    return 1;
}

/* The reset batch as specified, per chip: 0x1C = 0x02, 0x00-0x05, 0x08-0x0D, 0x10-0x12, 0x14, 0x15, 0x16, 0x18, 0x19
 * = 0, 0x1C = 0x01. */
static int reset_batch_ok(const SaPlayer* p, int chips) {
    SaWrite want[SAP_MAX_WRITES];
    int n = 0;
    for (int chip = 0; chip < chips; ++chip) {
        want[n++] = (SaWrite){(uint8_t)chip, 0x1C, 0x02};
        for (int r = 0x00; r <= 0x05; ++r) want[n++] = (SaWrite){(uint8_t)chip, (uint8_t)r, 0};
        for (int r = 0x08; r <= 0x0D; ++r) want[n++] = (SaWrite){(uint8_t)chip, (uint8_t)r, 0};
        static const int rest[] = {0x10, 0x11, 0x12, 0x14, 0x15, 0x16, 0x18, 0x19};
        for (size_t i = 0; i < sizeof rest / sizeof rest[0]; ++i) want[n++] = (SaWrite){(uint8_t)chip, (uint8_t)rest[i], 0};
        want[n++] = (SaWrite){(uint8_t)chip, 0x1C, 0x01};
    }
    if (p->writes != n || n != 22 * chips) return 0;
    for (int i = 0; i < n; ++i)
        if (p->write[i].chip != want[i].chip || p->write[i].reg != want[i].reg || p->write[i].val != want[i].val) return 0;
    return 1;
}

/* One line of saa_trace.txt (tools/audio/saa/saa_ref.py format_batch). */
static void trace_batch(FILE* f, int index, int o, int r, int t, const SaPlayer* p) {
    if (!f) return;
    fprintf(f, "T %d %d %d %d :", index, o, r, t);
    for (int i = 0; i < p->writes; ++i) fprintf(f, " %d:%02x=%02x", p->write[i].chip, p->write[i].reg, p->write[i].val);
    fputc('\n', f);
}

static int instrument_at(int slot) { return SA_OFF_INSTRUMENTS + SA_INSTRUMENT_SIZE * (slot - 1); }

/* ---- small images -------------------------------------------------------------------------------------------- */

/* One order of `rows` rows (1..128, an empty run on every channel at data offset 0), speed `speed`, no instruments. */
static void b_init(SaaImage* im, int variant, int speed, int rows) {
    memset(im, 0, sizeof *im);
    uint8_t* b = im->bytes;
    memcpy(b, SA_MAGIC, 4);
    b[SA_H_LAYOUT] = SA_LAYOUT;
    b[SA_H_VARIANT] = (uint8_t)variant;
    b[SA_H_MULTISPEED] = 1;
    b[SA_H_SPEED] = (uint8_t)speed;
    put16(b + SA_H_ORDERS, 1);
    b[SA_H_CHANNELS] = (uint8_t)(variant == SA_VARIANT_CMS ? SA_CHANNELS_CMS : SA_CHANNELS_SAM);
    b[SA_OFF_ROWS] = (uint8_t)rows;
    b[SA_OFF_DATA] = (uint8_t)(SA_RUN | (rows - 1));
    put16(b + SA_H_DATA_SIZE, 1);
}

/* Appends bytes to the data region; returns their offset. */
static int b_put(SaaImage* im, const uint8_t* bytes, int n) {
    const int at = sa_u16(im->bytes + SA_H_DATA_SIZE);
    memcpy(im->bytes + SA_OFF_DATA + at, bytes, (size_t)n);
    put16(im->bytes + SA_H_DATA_SIZE, at + n);
    return at;
}

/* An envelope (values as bytes); returns its offset. */
static int b_env(SaaImage* im, const int8_t* values, int n, int loop, int release) {
    uint8_t buf[SA_ENV_HEADER + SA_MAX_ENVELOPE];
    put16(buf, n);
    put16(buf + 2, loop < 0 ? SA_ENV_NONE : loop);
    put16(buf + 4, release < 0 ? SA_ENV_NONE : release);
    for (int i = 0; i < n; ++i) buf[SA_ENV_HEADER + i] = (uint8_t)values[i];
    return b_put(im, buf, SA_ENV_HEADER + n);
}

/* Instrument `slot` (envelope offsets -1 = none); raises the instrument count. */
static void b_inst(SaaImage* im, int slot, int flags, int noise, int pan, int env, int rate, int vol, int arp, int pitch,
                   int mixer) {
    uint8_t* r = im->bytes + instrument_at(slot);
    memset(r, 0, SA_INSTRUMENT_SIZE);
    r[SA_I_FLAGS] = (uint8_t)(SA_FLAG_DEFINED | flags);
    r[SA_I_NOISE] = (uint8_t)noise;
    r[SA_I_PAN] = (uint8_t)pan;
    r[SA_I_ENVELOPE] = (uint8_t)env;
    r[SA_I_RATE] = (uint8_t)rate;
    put16(r + SA_I_VOLUME, vol < 0 ? SA_ENV_NONE : vol);
    put16(r + SA_I_ARPEGGIO, arp < 0 ? SA_ENV_NONE : arp);
    put16(r + SA_I_PITCH, pitch < 0 ? SA_ENV_NONE : pitch);
    put16(r + SA_I_MIXER, mixer < 0 ? SA_ENV_NONE : mixer);
    if (im->bytes[SA_H_INSTRUMENTS] < slot) im->bytes[SA_H_INSTRUMENTS] = (uint8_t)slot;
}

/* Channel c of order 0 plays `bytes` (a whole track). */
static void b_track(SaaImage* im, int c, const uint8_t* bytes, int n) {
    put16(im->bytes + SA_OFF_ORDERS + 2 * c, b_put(im, bytes, n));
}

/* The minimal image the corruption tests start from: one row, instrument 1 = a plain tone (pan 15/15). */
static void base_image(SaaImage* im, int variant) {
    b_init(im, variant, 6, 1);
    b_inst(im, 1, 0, 0, 0xFF, 0, 0, -1, -1, -1, -1);
}

/* ---- the core ------------------------------------------------------------------------------------------------ */

typedef struct Event {
    uint32_t clock;
    int16_t channel, left, right;
} Event;

typedef struct Events {
    int n, overflow;
    uint32_t base; /* added to every clock (the clock-by-clock model's position) */
    Event e[MAX_EVENTS];
} Events;

static Events g_ev_a, g_ev_b;

static void collect(void* ctx, uint32_t clock, int channel, int left, int right) {
    Events* ev = (Events*)ctx;
    if (ev->n >= MAX_EVENTS) {
        ev->overflow = 1;
        return;
    }
    Event* e = &ev->e[ev->n++];
    e->clock = ev->base + clock;
    e->channel = (int16_t)channel;
    e->left = (int16_t)left;
    e->right = (int16_t)right;
}

static void clear(Events* ev) { ev->n = ev->overflow = 0, ev->base = 0; }

/* The clock-by-clock path: the write changes at offset 0, then one sac_render per clock (the "One clock" steps applied
 * once each, no event skipping). Events as sac_render reports them. */
static void model_run(SacCore* c, uint32_t clocks, Events* ev) {
    if (ev) ev->base = 0;
    sac_render(c, 0, ev ? collect : NULL, ev);
    for (uint32_t i = 0; i < clocks; ++i) {
        if (ev) ev->base = i;
        sac_render(c, 1, ev ? collect : NULL, ev);
    }
    if (ev) ev->base = 0;
}

static int same_events(const Events* a, const Events* b) {
    if (a->overflow || b->overflow || a->n != b->n) return 0;
    for (int i = 0; i < a->n; ++i)
        if (a->e[i].clock != b->e[i].clock || a->e[i].channel != b->e[i].channel || a->e[i].left != b->e[i].left ||
            a->e[i].right != b->e[i].right)
            return 0;
    return 1;
}

/* Events inside the render, nondecreasing in clock, each a real change of its channel's output. */
static int well_formed(const Events* ev, uint32_t clocks, const int* start_l, const int* start_r) {
    int l[SAC_CHANNELS], r[SAC_CHANNELS];
    uint32_t last = 0;
    for (int ch = 0; ch < SAC_CHANNELS; ++ch) l[ch] = start_l[ch], r[ch] = start_r[ch];
    for (int i = 0; i < ev->n; ++i) {
        const Event* e = &ev->e[i];
        if (e->channel < 0 || e->channel >= SAC_CHANNELS || (clocks ? e->clock >= clocks : e->clock != 0) || e->clock < last)
            return 0;
        if ((e->left == l[e->channel] && e->right == r[e->channel]) || e->left < 0 || e->left > SAC_OUT_MAX || e->right < 0 ||
            e->right > SAC_OUT_MAX)
            return 0;
        last = e->clock;
        l[e->channel] = e->left;
        r[e->channel] = e->right;
    }
    return 1;
}

static void fresh(SacCore* c) {
    memset(c, 0x55, sizeof *c);
    sac_reset(c);
}

/* Channel ch's tone generator set to (O, N) at once (through a sync), released with sound on. */
static void set_tone_now(SacCore* c, int ch, int oct, int off) {
    sac_write(c, SAC_REG_CONTROL, 0x02);
    sac_write(c, SAC_REG_OFFSET + ch, off);
    const int reg = SAC_REG_OCTAVE + ch / 2, other = ch ^ 1;
    const int both = (ch & 1) ? (c->tone[other].next_oct | oct << 4) : (oct | c->tone[other].next_oct << 4);
    sac_write(c, reg, both);
    sac_write(c, SAC_REG_CONTROL, 0x01);
}

/* The 18-bit LFSR, independently. */
static uint32_t lfsr_step(uint32_t v) { return (v & 1) ? (v >> 1) ^ 0x20400u : v >> 1; }

/* The level of envelope step s (0..length-1) of a shape at 4-bit resolution (the data sheet's shapes). */
static int shape_level4(int shape, int s) {
    switch (shape) {
    case 0: return 0;
    case 1: return 15;
    case 2: case 3: return 15 - s;
    case 4: case 5: return s < 16 ? s : 31 - s;
    default: return s;
    }
}

/* At 3-bit resolution step s is position 2 (s mod 8) of phase s / 8 and the level loses its LSB. */
static int shape_level3(int shape, int s) {
    const int at = (s / 8) * 16 + 2 * (s % 8);
    return shape_level4(shape, at) & 0x0E;
}

static uint32_t g_rng = 1;
static uint32_t rnd(void) {
    g_rng ^= g_rng << 13;
    g_rng ^= g_rng >> 17;
    g_rng ^= g_rng << 5;
    return g_rng;
}

/* A random register write, weighted toward what matters: frequencies, noise clocks, envelope controls and clocks. */
static void random_write(int* reg, int* val) {
    const uint32_t r = rnd() % 100;
    if (r < 12) {
        *reg = (int)(rnd() % 6);
        *val = (int)(rnd() & 0xFF);
    } else if (r < 30) {
        *reg = SAC_REG_OFFSET + (int)(rnd() % 6);
        *val = rnd() % 4 == 0 ? (int)(250 + rnd() % 6) : (int)(rnd() & 0xFF);
    } else if (r < 44) {
        *reg = SAC_REG_OCTAVE + (int)(rnd() % 3);
        *val = rnd() % 3 == 0 ? 0x77 : (int)(rnd() & 0xFF);
    } else if (r < 52) {
        *reg = SAC_REG_TONE + (int)(rnd() % 2);
        *val = (int)(rnd() & 0xFF);
    } else if (r < 60) {
        *reg = SAC_REG_NOISE_MODE;
        *val = (int)(rnd() & 0xFF);
    } else if (r < 82) {
        *reg = SAC_REG_ENV0 + (int)(rnd() % 2);
        const uint32_t k = rnd() % 6;
        *val = k == 0 ? 0 : k == 1 ? (int)(rnd() & 0xFF) : (int)(0x80 | (rnd() & 0x3F));
    } else if (r < 94) {
        *reg = SAC_REG_CONTROL;
        static const int controls[] = {1, 1, 1, 1, 3, 2, 0, 1};
        *val = controls[rnd() % 8];
    } else {
        static const int ignored[] = {0x06, 0x07, 0x0E, 0x0F, 0x13, 0x17, 0x1A, 0x1B, 0x1D, 0x1F, 0x3C};
        *reg = ignored[rnd() % (sizeof ignored / sizeof ignored[0])];
        *val = (int)(rnd() & 0xFF);
    }
}

/* sac_advance, sac_render and the clock-by-clock render from the same random write stream: memcmp-equal state after
 * every segment, the same events. Returns the segments that differed. */
static int random_streams(int segments, uint32_t seed, uint64_t* clocks) {
    static SacCore a, b, m;
    fresh(&a);
    fresh(&b);
    fresh(&m);
    g_rng = seed;
    int bad = 0;
    *clocks = 0;
    for (int s = 0; s < segments; ++s) {
        const int writes = (int)(rnd() % 5);
        for (int w = 0; w < writes; ++w) {
            int reg, val;
            random_write(&reg, &val);
            sac_write(&a, reg, val);
            sac_write(&b, reg, val);
            sac_write(&m, reg, val);
        }
        const uint32_t n = rnd() % 8 == 0 ? 20000 + rnd() % 60000 : rnd() % 3000;
        int start_l[SAC_CHANNELS], start_r[SAC_CHANNELS];
        for (int ch = 0; ch < SAC_CHANNELS; ++ch) start_l[ch] = b.out_l[ch], start_r[ch] = b.out_r[ch];
        clear(&g_ev_a);
        clear(&g_ev_b);
        sac_advance(&a, n);
        sac_render(&b, n, collect, &g_ev_a);
        model_run(&m, n, &g_ev_b);
        *clocks += n;
        const int ab = !memcmp(&a, &b, sizeof a), bm = !memcmp(&b, &m, sizeof b), ev = same_events(&g_ev_a, &g_ev_b);
        const int ordered = well_formed(&g_ev_a, n, start_l, start_r);
        if (!ab || !bm || !ev || !ordered) {
            if (!bad)
                printf("  segment %d (%u clocks): advance %s render, render %s per-clock, events %s, %s\n", s, n,
                       ab ? "=" : "!=", bm ? "=" : "!=", ev ? "equal" : "differ", ordered ? "ordered" : "malformed");
            ++bad;
            a = m; /* continue from the per-clock state */
            b = m;
        }
    }
    return bad;
}

/* Clock offsets of channel ch's events in ev. */
static int channel_clocks(const Events* ev, int ch, uint32_t* out, int max) {
    int n = 0;
    for (int i = 0; i < ev->n && n < max; ++i)
        if (ev->e[i].channel == ch) out[n++] = ev->e[i].clock;
    return n;
}

static void test_core(void) {
    printf("core\n");
    static SacCore c, saved;
    Events* ev = &g_ev_a;

    /* Reset. */
    fresh(&c);
    int ok = c.enable == 0 && c.sync == 0 && c.prescale == 0;
    for (int i = 0; i < SAC_CHANNELS; ++i)
        ok &= c.tone[i].oct == 0 && c.tone[i].off == 0 && c.tone[i].next_oct == 0 && c.tone[i].next_off == 0 &&
              !c.tone[i].pending && c.tone[i].level == 1 && c.tone[i].half == SAC_HALF_MAX &&
              c.tone[i].count == SAC_HALF_MAX && c.ch[i].amp_l == 0 && c.ch[i].amp_r == 0 && c.ch[i].tone == 0 &&
              c.ch[i].noise == 0 && c.out_l[i] == 0 && c.out_r[i] == 0;
    for (int g = 0; g < 2; ++g)
        ok &= c.noise[g].mode == 0 && c.noise[g].lfsr == SAC_LFSR_SEED && !c.env[g].enabled && c.env[g].ended &&
              c.env[g].phase == 0 && c.env[g].pos == 0 && c.env[g].resolution == 1 && c.env[g].shape == 0 &&
              !c.env[g].looping && c.env[g].phases == 1 && !c.env[g].external && !c.env[g].invert && !c.env[g].new_data &&
              c.env[g].left == 0 && c.env[g].right == 0;
    EXPECT(ok, "reset: generators O 0 N 0 at level 1, count 130816, LFSRs 0x3FFFF, envelopes off, everything else 0");

    /* Half-periods at the corners and latching at the half-cycle's end. */
    EXPECT(sac_half(0, 0) == 130816 && sac_half(7, 255) == 512 && sac_half(3, 227) == 9088 && sac_half(4, 3) == 8128,
           "H = (511 - N) << (8 - O): O0 N0 130816, O7 N255 512, O3 N227 9088, O4 N3 8128");
    fresh(&c);
    sac_write(&c, SAC_REG_CONTROL, 0x01);
    sac_write(&c, SAC_REG_TONE, 0x01);
    sac_write(&c, SAC_REG_AMP + 0, 0xFF);
    sac_write(&c, SAC_REG_OFFSET + 0, 255);
    sac_write(&c, SAC_REG_OCTAVE + 0, 0x07);
    EXPECT(c.tone[0].pending && c.tone[0].off == 0 && c.tone[0].oct == 0 && c.tone[0].next_off == 255 && c.tone[0].next_oct == 7,
           "a frequency write while running is pending");
    clear(ev);
    sac_render(&c, 130816 + 512 * 4, collect, ev);
    {
        uint32_t at[16];
        const int n = channel_clocks(ev, 0, at, 16);
        ok = n == 6 && at[0] == 0 && at[1] == 130815 && at[2] == 130815 + 512 && at[5] == 130815 + 512 * 4;
        EXPECT(ok && ev->e[0].left == 480 && ev->e[0].right == 480,
               "the running half-cycle ends at 130816 clocks (O0 N0), then O7 N255 edges every 512 (%d events)", n);
    }
    EXPECT(!c.tone[0].pending && c.tone[0].oct == 7 && c.tone[0].off == 255 && c.tone[0].half == 512, "latched at the edge");

    /* Sync: held, data applied at once, released in phase. */
    fresh(&c);
    sac_write(&c, SAC_REG_CONTROL, 0x01);
    sac_write(&c, SAC_REG_TONE, 0x03);
    sac_write(&c, SAC_REG_AMP + 0, 0xFF);
    sac_write(&c, SAC_REG_AMP + 1, 0x88);
    sac_render(&c, 1000, NULL, NULL);
    sac_write(&c, SAC_REG_CONTROL, 0x03);
    EXPECT(c.sync && c.prescale == 0 && c.tone[0].level == 1 && c.tone[1].level == 1, "sync: prescaler 0, levels 1");
    sac_write(&c, SAC_REG_OFFSET + 0, 200);
    sac_write(&c, SAC_REG_OFFSET + 1, 100);
    sac_write(&c, SAC_REG_OCTAVE + 0, 0x45);
    EXPECT(!c.tone[0].pending && c.tone[0].off == 200 && c.tone[0].oct == 5 && c.tone[0].half == sac_half(5, 200) &&
               !c.tone[1].pending && c.tone[1].off == 100 && c.tone[1].oct == 4,
           "sync: offsets and octaves apply at once");
    saved = c;
    clear(ev);
    sac_render(&c, 50000, collect, ev);
    EXPECT(ev->n == 2 && ev->e[0].left == 0 && ev->e[1].left == 0, "sync: outputs 0 (the two writes' changes only)");
    saved.out_l[0] = saved.out_r[0] = saved.out_l[1] = saved.out_r[1] = 0;
    EXPECT(!memcmp(&c, &saved, sizeof c), "sync: nothing moves in 50000 clocks");
    sac_write(&c, SAC_REG_CONTROL, 0x01);
    EXPECT(c.tone[0].count == c.tone[0].half && c.tone[1].count == c.tone[1].half, "release: every count restarts at H");
    clear(ev);
    sac_render(&c, sac_half(4, 100) + 24, collect, ev); /* covers two of tone 0's edges and one of tone 1's */
    {
        uint32_t a[8], b[8];
        const int na = channel_clocks(ev, 0, a, 8), nb = channel_clocks(ev, 1, b, 8);
        EXPECT(na >= 3 && nb >= 2 && a[0] == 0 && b[0] == 0 && a[1] == (uint32_t)sac_half(5, 200) - 1 &&
                   a[2] == 2u * sac_half(5, 200) - 1 && b[1] == (uint32_t)sac_half(4, 100) - 1,
               "released in phase: both generators restart high together and edge at their own H");
    }
    sac_write(&c, SAC_REG_CONTROL, 0x00);
    int l, r;
    sac_output(&c, 0, &l, &r);
    EXPECT(l == 0 && r == 0 && c.tone[0].count > 0, "sound off: output 0, generators run");

    /* Noise rates, the LFSR and the clock-3 coupling. */
    for (int mode = 0; mode < 3; ++mode) {
        fresh(&c);
        sac_write(&c, SAC_REG_NOISE_MODE, mode | (mode << 4));
        sac_advance(&c, (256u << mode) * 100 + 255);
        uint32_t want = SAC_LFSR_SEED;
        for (int s = 0; s < 100; ++s) want = lfsr_step(want);
        EXPECT(c.noise[0].lfsr == want && c.noise[1].lfsr == want, "noise clock %d: a shift every %u clocks (100 shifts)", mode,
               256u << mode);
    }
    {
        fresh(&c);
        uint32_t v = SAC_LFSR_SEED;
        int bits = 0, ones = 0;
        sac_write(&c, SAC_REG_NOISE_MODE, 0x00);
        for (int s = 0; s < 64; ++s) {
            sac_advance(&c, 256);
            v = lfsr_step(v);
            bits += c.noise[0].lfsr == v;
            ones += (int)(v & 1);
        }
        EXPECT(bits == 64 && ones > 10 && ones < 54, "LFSR: x^18 + x^11 + x^1 Galois steps from 0x3FFFF (%d/64, %d ones)", bits, ones);
        fresh(&c);
        sac_advance(&c, 256u * 131071);
        const int mid = c.noise[0].lfsr != SAC_LFSR_SEED;
        sac_advance(&c, 256u * 131072);
        EXPECT(mid && c.noise[0].lfsr == SAC_LFSR_SEED, "LFSR period 262143");
    }
    for (int g = 0; g < 2; ++g) {
        fresh(&c);
        set_tone_now(&c, 3 * g, 7, 255);
        sac_write(&c, SAC_REG_NOISE_MODE, 3 << (4 * g));
        sac_advance(&c, 512 * 37 + 100);
        uint32_t want = SAC_LFSR_SEED;
        for (int s = 0; s < 37; ++s) want = lfsr_step(want);
        EXPECT(c.noise[g].lfsr == want && c.noise[1 - g].lfsr != want, "noise %d clock 3: a shift per edge of tone %d", g, 3 * g);
        set_tone_now(&c, 3 * g, 6, 255);
        const uint32_t before = c.noise[g].lfsr;
        sac_advance(&c, 1024 * 5);
        uint32_t w = before;
        for (int s = 0; s < 5; ++s) w = lfsr_step(w);
        EXPECT(c.noise[g].lfsr == w, "noise %d clock 3 follows tone %d's new frequency (twice its frequency)", g, 3 * g);
    }

    /* Envelope shapes (internal clock: tone 1 at H 512), both resolutions, inversion. */
    int shapes_bad = 0;
    for (int shape = 0; shape < 8; ++shape)
        for (int res = 0; res < 2; ++res)
            for (int inv = 0; inv < 2; ++inv) {
                fresh(&c);
                set_tone_now(&c, 1, 7, 255);
                const int v = SA_ENV_ENABLE | (res ? SA_ENV_3BIT : 0) | shape << SA_ENV_SHAPE_SHIFT | inv;
                sac_write(&c, SAC_REG_ENV0, v);
                const int length = (shape == 4 || shape == 5 ? 2 : 1) * (res ? 8 : 16);
                const int looping = shape & 1;
                for (int s = 0; s <= length + 2; ++s) {
                    int want;
                    if (s < length) want = res ? shape_level3(shape, s) : shape_level4(shape, s);
                    else if (looping) want = res ? shape_level3(shape, s - length) : shape_level4(shape, s - length);
                    else want = 0;
                    const int want_r = inv ? (res ? 14 : 15) - want : want;
                    if (c.env[0].left != want || c.env[0].right != want_r || c.env[0].ended != (!looping && s >= length)) {
                        if (!shapes_bad)
                            printf("  shape %d %s%s step %d: left %d right %d (want %d %d)\n", shape, res ? "3-bit" : "4-bit",
                                   inv ? " inverted" : "", s, c.env[0].left, c.env[0].right, want, want_r);
                        ++shapes_bad;
                    }
                    sac_advance(&c, 512);
                }
            }
    EXPECT(shapes_bad == 0, "envelope shapes 0-7, 4- and 3-bit, inverted right (15 - / 14 - left), one-shots end at 0 (%d bad)",
           shapes_bad);

    /* Buffering until the cycle's end. */
    fresh(&c);
    set_tone_now(&c, 1, 7, 255);
    sac_write(&c, SAC_REG_ENV0, 0x86); /* repetitive decay */
    sac_advance(&c, 512 * 5);
    sac_write(&c, SAC_REG_ENV0, 0x8E); /* repetitive attack, buffered */
    ok = c.env[0].new_data && c.env[0].next_data == 0x8E && c.env[0].shape == 3 && c.env[0].left == 10;
    sac_advance(&c, 512 * 10);
    ok &= c.env[0].shape == 3 && c.env[0].left == 0;
    sac_advance(&c, 512);
    EXPECT(ok && c.env[0].shape == 7 && !c.env[0].new_data && c.env[0].left == 0 && c.env[0].pos == 0,
           "a control write while the cycle runs is buffered until its end point");
    sac_advance(&c, 512 * 3);
    EXPECT(c.env[0].left == 3, "then the new shape runs (attack at 3)");
    /* a one-shot: buffered until position (3), then loaded there */
    fresh(&c);
    set_tone_now(&c, 1, 7, 255);
    sac_write(&c, SAC_REG_ENV0, 0x84); /* single decay */
    sac_advance(&c, 512 * 3);
    sac_write(&c, SAC_REG_ENV0, 0x8C); /* single attack */
    sac_advance(&c, 512 * 12);
    ok = c.env[0].shape == 2 && c.env[0].left == 0 && c.env[0].new_data;
    sac_advance(&c, 512);
    EXPECT(ok && c.env[0].shape == 6 && !c.env[0].ended && c.env[0].left == 0, "a one-shot loads buffered data at its end");
    sac_advance(&c, 512 * 16);
    EXPECT(c.env[0].ended && c.env[0].left == 0, "single attack ends at 0");
    sac_write(&c, SAC_REG_ENV0, 0x84);
    EXPECT(!c.env[0].ended && c.env[0].shape == 2 && c.env[0].left == 15 && !c.env[0].new_data,
           "a write to an ended envelope applies at once");

    /* Immediate disable, resolution change mid-cycle. */
    sac_advance(&c, 512 * 4);
    sac_write(&c, SAC_REG_ENV0, 0x00);
    EXPECT(!c.env[0].enabled && c.env[0].ended, "disabling is immediate");
    fresh(&c);
    set_tone_now(&c, 1, 7, 255);
    sac_write(&c, SAC_REG_ENV0, 0x86);
    sac_advance(&c, 512 * 5); /* position 5 */
    sac_write(&c, SAC_REG_ENV0, 0x96);
    ok = c.env[0].resolution == 2 && c.env[0].pos == 4 && c.env[0].left == 10;
    sac_advance(&c, 512); /* position 6 */
    sac_write(&c, SAC_REG_ENV0, 0x86);
    EXPECT(ok && c.env[0].resolution == 1 && c.env[0].pos == 7 && c.env[0].left == 8,
           "resolution 4 -> 3 bits clears the position's LSB (5 -> 4, level 10), 3 -> 4 sets it (6 -> 7, level 8)");

    /* The external clock per address. */
    fresh(&c);
    sac_write(&c, SAC_REG_ENV0, 0xA6);
    sac_write(&c, SAC_REG_ENV1, 0xA6);
    const int p0 = c.env[0].pos, p1 = c.env[1].pos;
    sac_write(&c, SAC_REG_ENV0, 0xA6);
    ok = c.env[0].pos == p0 + 1 && c.env[1].pos == p1;
    sac_write(&c, SAC_REG_ENV1, 0xA6);
    sac_write(&c, SAC_REG_ENV1, 0xA6);
    ok &= c.env[0].pos == p0 + 1 && c.env[1].pos == p1 + 2;
    sac_write(&c, SAC_REG_AMP, 0);
    sac_advance(&c, 200000);
    EXPECT(ok && c.env[0].pos == p0 + 1, "an external envelope steps at each write of its own address only");
    fresh(&c);
    set_tone_now(&c, 1, 7, 255);
    sac_write(&c, SAC_REG_ENV0, 0x86);
    sac_write(&c, SAC_REG_ENV0, 0x86);
    EXPECT(c.env[0].pos == 0, "an internal envelope ignores its address");

    /* The output formula. */
    fresh(&c);
    sac_write(&c, SAC_REG_CONTROL, 0x01);
    sac_write(&c, SAC_REG_AMP + 2, 0xFF);
    sac_write(&c, SAC_REG_ENV0, 0x82); /* maximum */
    sac_output(&c, 2, &l, &r);
    EXPECT(l == 53 * 4 * 2 && r == 53 * 4 * 2 && l == 424, "mixer off with the envelope: PDM[7][15] x 4 x 2 = 424 (%d)", l);
    sac_write(&c, SAC_REG_AMP + 2, 0x3A); /* left 10, right 3 */
    sac_output(&c, 2, &l, &r);
    EXPECT(l == SAC_PDM[5][15] * 8 && r == SAC_PDM[1][15] * 8, "PDM[amp >> 1][level] per side (%d, %d)", l, r);
    sac_write(&c, SAC_REG_TONE, 0x04);
    c.tone[2].level = 1;
    sac_output(&c, 2, &l, &r);
    const int env_high = l;
    c.tone[2].level = 0;
    sac_output(&c, 2, &l, &r);
    EXPECT(env_high == 0 && l == SAC_PDM[5][15] * 8, "with the tone: silent while it is high, the envelope while low");
    sac_write(&c, SAC_REG_AMP + 0, 0xFF);
    sac_write(&c, SAC_REG_TONE, 0x01);
    sac_write(&c, SAC_REG_NOISE, 0x01);
    static const int tn[4][3] = {{0, 0, 0}, {1, 0, 480}, {0, 1, 0}, {1, 1, 240}};
    ok = 1;
    for (int i = 0; i < 4; ++i) {
        c.tone[0].level = tn[i][0];
        c.noise[0].lfsr = (c.noise[0].lfsr & ~1u) | (uint32_t)tn[i][1];
        sac_output(&c, 0, &l, &r);
        ok &= l == tn[i][2] && r == tn[i][2];
    }
    EXPECT(ok, "tone + noise: amp x tone x (2 - noise) x 16 (480 / 240 / 0)");
    sac_write(&c, SAC_REG_TONE, 0x00);
    c.noise[0].lfsr |= 1;
    sac_output(&c, 0, &l, &r);
    EXPECT(l == 480, "noise only: 2 x noise");
    sac_write(&c, SAC_REG_NOISE, 0x00);
    sac_output(&c, 0, &l, &r);
    EXPECT(l == 0, "mixer off without an envelope: 0");
    sac_write(&c, SAC_REG_ENV0, 0x82);
    sac_write(&c, SAC_REG_AMP + 1, 0xFF);
    sac_write(&c, SAC_REG_AMP + 5, 0xFF);
    sac_output(&c, 1, &l, &r);
    int l5, r5;
    sac_output(&c, 5, &l5, &r5);
    EXPECT(l == 0 && l5 == 0, "envelope 0 controls channel 2 only");

    /* sac_advance = sac_render = clock by clock after random write streams. */
    uint64_t clocks = 0;
    const double t0 = now_us();
    const int differ = random_streams(600, 0x5AA1099u, &clocks);
    EXPECT(differ == 0, "sac_advance = sac_render = per-clock render after random writes (%d of 600 segments differ)", differ);
    printf("  random streams: 600 segments, %.1f M clocks, %.0f ms with the per-clock render\n", clocks / 1e6,
           (now_us() - t0) / 1000);
}

/* ---- the scripted chip trace (evidence for the Python per-clock model) ---------------------------------------- */

typedef struct Script {
    SacCore core, model;
    FILE* f;
    uint64_t clock;
    int bad;
} Script;

static void script_write(Script* s, int reg, int val) {
    if (s->f) fprintf(s->f, "W %llu %d %d\n", (unsigned long long)s->clock, reg & 0x1F, val & 0xFF);
    sac_write(&s->core, reg, val);
    sac_write(&s->model, reg, val);
}

static void script_tone(Script* s, int ch, int oct, int off) {
    script_write(s, SAC_REG_OFFSET + ch, off);
    const int other = ch ^ 1, reg = SAC_REG_OCTAVE + ch / 2;
    const int both = (ch & 1) ? (s->core.tone[other].next_oct | oct << 4) : (oct | s->core.tone[other].next_oct << 4);
    script_write(s, reg, both);
}

static void script_render(Script* s, uint32_t clocks) {
    clear(&g_ev_a);
    clear(&g_ev_b);
    sac_render(&s->core, clocks, collect, &g_ev_a);
    model_run(&s->model, clocks, &g_ev_b);
    if (memcmp(&s->core, &s->model, sizeof s->core) || !same_events(&g_ev_a, &g_ev_b)) {
        if (!s->bad) printf("  chip trace: render at clock %llu differs from the per-clock render\n", (unsigned long long)s->clock);
        ++s->bad;
        s->core = s->model;
    }
    if (s->f)
        for (int i = 0; i < g_ev_a.n; ++i)
            fprintf(s->f, "E %llu %d %d %d\n", (unsigned long long)(s->clock + g_ev_a.e[i].clock), g_ev_a.e[i].channel,
                    g_ev_a.e[i].left, g_ev_a.e[i].right);
    s->clock += clocks;
}

static void test_chip_trace(const char* dir) {
    printf("chip trace\n");
    static Script s;
    char path[512];
    snprintf(path, sizeof path, "%s/saa_chip_trace.txt", dir);
    memset(&s, 0, sizeof s);
    s.f = fopen(path, "w");
    sac_reset(&s.core);
    sac_reset(&s.model);
    /* tones: held by sync while they are set, then released together */
    script_write(&s, SAC_REG_CONTROL, 0x02);
    static const int amps[6] = {0xFF, 0x8C, 0xFF, 0x5A, 0x3C, 0xF7};
    for (int c = 0; c < 6; ++c) script_write(&s, SAC_REG_AMP + c, amps[c]);
    script_tone(&s, 0, 3, 227); /* A-4 on the SAM Coupe */
    script_tone(&s, 1, 4, 100);
    script_tone(&s, 2, 2, 50);
    script_tone(&s, 3, 5, 200);
    script_tone(&s, 4, 7, 255); /* the highest tone */
    script_tone(&s, 5, 0, 0);   /* the lowest */
    script_write(&s, SAC_REG_TONE, 0x3F);
    script_write(&s, SAC_REG_NOISE, 0x09);
    script_write(&s, SAC_REG_NOISE_MODE, 0x10);
    script_render(&s, 20000);
    script_write(&s, SAC_REG_CONTROL, 0x01);
    script_render(&s, 60000);
    /* octave latching: offset then octave mid-cycle, heard at the half-cycle's end */
    script_tone(&s, 0, 4, 100);
    script_render(&s, 30000);
    script_tone(&s, 1, 2, 255);
    script_render(&s, 15000);
    /* noise clocks: / 1024 and clock 3 (tone 3) on noise 1, then clock 3 (tone 0) and / 256 */
    script_write(&s, SAC_REG_NOISE_MODE, 0x32);
    script_render(&s, 40000);
    script_write(&s, SAC_REG_NOISE_MODE, 0x03);
    script_write(&s, SAC_REG_NOISE, 0x3F);
    script_render(&s, 30000);
    script_write(&s, SAC_REG_NOISE_MODE, 0x21);
    script_write(&s, SAC_REG_TONE, 0x36); /* channels 0 and 3: noise only */
    script_render(&s, 30000);
    script_write(&s, SAC_REG_NOISE, 0x09);
    script_write(&s, SAC_REG_TONE, 0x3F);
    /* envelope 0, internal (tone 1 as the clock): repetitive decay, then buffered repetitive triangle inverted */
    script_tone(&s, 1, 6, 200);
    script_write(&s, SAC_REG_ENV0, 0x86);
    script_render(&s, 80000);
    script_write(&s, SAC_REG_ENV0, 0x8B);
    script_render(&s, 90000);
    /* resolution changes mid-cycle */
    script_write(&s, SAC_REG_ENV0, 0x9B);
    script_render(&s, 40000);
    script_write(&s, SAC_REG_ENV0, 0x8B);
    script_render(&s, 20000);
    /* the envelope as the waveform: channel 2's mixer off */
    script_write(&s, SAC_REG_TONE, 0x3B);
    script_render(&s, 50000);
    /* envelope 1, external: each address write a clock; then a one-shot decay to its end and its restart */
    script_write(&s, SAC_REG_ENV1, 0xA6);
    for (int k = 0; k < 20; ++k) {
        script_render(&s, 2000);
        script_write(&s, SAC_REG_ENV1, 0xA6);
    }
    script_write(&s, SAC_REG_ENV1, 0x00);
    script_write(&s, SAC_REG_ENV1, 0xB5); /* external single triangle, 3-bit, inverted */
    for (int k = 0; k < 20; ++k) {
        script_render(&s, 1500);
        script_write(&s, SAC_REG_ENV1, 0xB5);
    }
    script_write(&s, SAC_REG_ENV1, 0xA4);
    for (int k = 0; k < 18; ++k) {
        script_render(&s, 1000);
        script_write(&s, SAC_REG_ENV1, 0xA4);
    }
    script_render(&s, 5000);
    /* immediate disable */
    script_write(&s, SAC_REG_ENV0, 0x00);
    script_render(&s, 10000);
    /* sync: held (frequency writes apply at once), released in phase; sound off and on */
    script_write(&s, SAC_REG_CONTROL, 0x03);
    script_render(&s, 5000);
    script_tone(&s, 0, 3, 227);
    script_tone(&s, 3, 3, 227);
    script_render(&s, 3000);
    script_write(&s, SAC_REG_CONTROL, 0x01);
    script_render(&s, 30000);
    script_write(&s, SAC_REG_CONTROL, 0x00);
    script_render(&s, 3000);
    script_write(&s, SAC_REG_CONTROL, 0x01);
    script_write(&s, 0x07, 0x55); /* ignored registers */
    script_write(&s, 0x1F, 0x55);
    script_render(&s, 10000);
    g_rng = 0xC0FFEEu;
    while (s.clock < 2000000) {
        const int writes = (int)(rnd() % 4);
        for (int w = 0; w < writes; ++w) {
            int reg, val;
            random_write(&reg, &val);
            script_write(&s, reg, val);
        }
        script_render(&s, 1 + rnd() % 8000);
    }
    if (s.f) fclose(s.f);
    EXPECT(s.f != NULL, "%s written", path);
    EXPECT(s.bad == 0, "scripted sequence: sac_render = the per-clock render (%d renders differ)", s.bad);
    EXPECT(s.clock < 2100000, "under 2.1 M clocks (%llu)", (unsigned long long)s.clock);
    printf("  %llu clocks scripted\n", (unsigned long long)s.clock);
}

/* ---- pitch --------------------------------------------------------------------------------------------------- */

static double tone_hz(int oo, int cms) {
    return (cms ? 7159090.0 : 8000000.0) * (1 << (oo >> 8)) / (512.0 * (511 - (oo & 0xFF)));
}

static void test_pitch(void) {
    printf("pitch\n");
    for (int cms = 0; cms < 2; ++cms) {
        const double k = cms ? SAC_CLOCK_CMS : SAC_CLOCK_SAM;
        const uint32_t* table = cms ? SA_DIV_CMS : SA_DIV_SAM;
        int table_bad = 0;
        for (int r = 0; r < 768; ++r) {
            const double f = 440.0 * pow(2.0, (r / 64.0 - 57) / 12.0);
            if (table[r] != (uint32_t)floor(k / (512 * f) * 256 + 0.5)) ++table_bad;
        }
        EXPECT(table_bad == 0, "%s: SA_DIV[r] = floor(K / (512 f(r)) 256 + 0.5) (%d differ)", cms ? "CMS" : "SAM", table_bad);
        int formula_bad = 0;
        for (int p = 0; p <= SAP_PITCH_MAX; ++p) {
            const int kk = p / 768;
            const long t = (long)table[p % 768];
            int s = 0, d;
            if (t >= 131072) s = -1, d = (int)((t + 256) >> 9);
            else {
                d = (int)((t + 128) >> 8);
                if (d >= 512) s = -1, d = (int)((t + 256) >> 9);
            }
            const int o = kk + s;
            const int want = o < 0 ? 0 : o > 7 ? (7 << 8 | 255) : (o << 8 | (511 - d));
            if (sap_octave_offset(p, cms) != want) ++formula_bad;
        }
        EXPECT(formula_bad == 0, "%s: sap_octave_offset = the formula for every pitch (%d differ)", cms ? "CMS" : "SAM", formula_bad);
        const int lo = cms ? 9 : 11, hi = cms ? 104 : 106;
        double worst = 0;
        for (int n = lo; n <= hi; ++n) {
            const double cents = 1200.0 * log2(tone_hz(sap_octave_offset(64 * n, cms), cms) / (440.0 * pow(2.0, (n - A4) / 12.0)));
            if (fabs(cents) > worst) worst = fabs(cents);
        }
        const int below = sap_octave_offset(64 * (lo - 1), cms), above = sap_octave_offset(64 * (hi + 1), cms);
        EXPECT(worst <= 3.4 && below == 0 && above == (7 << 8 | 255),
               "%s: notes %d..%d within %.2f cents; %d and %d clamp to the chip's lowest and highest tones", cms ? "CMS" : "SAM", lo,
               hi, worst, lo - 1, hi + 1);
        EXPECT(sap_octave_offset(-5, cms) == 0 && sap_octave_offset(99999, cms) == (7 << 8 | 255), "pitch clamped");
    }
    EXPECT(sap_octave_offset(A4 * 64, 0) == (3 << 8 | 227) && fabs(tone_hz(3 << 8 | 227, 0) - 440.14) < 0.01,
           "SAM A-4 = O 3 N 227 (%.3f Hz)", tone_hz(3 << 8 | 227, 0));
    EXPECT(sap_octave_offset(A4 * 64, 1) == (4 << 8 | 3) && fabs(tone_hz(4 << 8 | 3, 1) - 440.40) < 0.01,
           "CMS A-4 = O 4 N 3 (%.3f Hz)", tone_hz(4 << 8 | 3, 1));
}

/* ---- images -------------------------------------------------------------------------------------------------- */

static void corrupt_bytes(int offset, int value, int width, const char* want, int at) {
    memcpy(&g_bad, &g_sam, sizeof g_bad);
    g_bad.bytes[offset] = (uint8_t)value;
    if (width == 2) g_bad.bytes[offset + 1] = (uint8_t)(value >> 8);
    const int rc = sa_load_image(&g_bad);
    EXPECT(rc != 0 && g_bad.info.loaded != SA_LOADED && g_bad.info.problem && strstr(g_bad.info.problem, want),
           "byte %d = %d rejected for \"%s\" (got %s)", offset, value, want, rc ? g_bad.info.problem : "accepted");
    if (rc) {
        EXPECT(at < 0 || g_bad.info.problem_offset == at, "\"%s\" reported at %d (want %d)", g_bad.info.problem,
               g_bad.info.problem_offset, at);
        printf("  rejected @%d: %s\n", g_bad.info.problem_offset, g_bad.info.problem);
    }
}

static void corrupt(int offset, int value, const char* want) { corrupt_bytes(offset, value, 1, want, offset); }

static void accepted(int offset, int value, int width, const char* what) {
    memcpy(&g_bad, &g_sam, sizeof g_bad);
    g_bad.bytes[offset] = (uint8_t)value;
    if (width == 2) g_bad.bytes[offset + 1] = (uint8_t)(value >> 8);
    const int rc = sa_load_image(&g_bad);
    EXPECT(rc == 0, "%s accepted (%s)", what, rc ? g_bad.info.problem : "ok");
}

/* Absolute image offset of the first value of envelope `which` of some defined slot of the song, or -1. */
static int find_envelope(int which) {
    for (int s = 1; s <= g_sam.info.instruments; ++s) {
        if (!(sa_instrument_flags(&g_sam, s) & SA_FLAG_DEFINED)) continue;
        const int off = sa_u16(sa_instrument(&g_sam, s) + which);
        if (off != SA_ENV_NONE) return SA_OFF_DATA + off + SA_ENV_HEADER;
    }
    return -1;
}

/* g_bad (filled) rejected for `want` (at `at`, -1 = anywhere) or accepted (want NULL). */
static void constructed(const char* what, const char* want, int at) {
    const int rc = sa_load_image(&g_bad);
    if (want) {
        EXPECT(rc != 0 && g_bad.info.problem && strstr(g_bad.info.problem, want), "%s rejected for \"%s\" (got %s)", what, want,
               rc ? g_bad.info.problem : "accepted");
        if (rc)
            EXPECT(at < 0 || g_bad.info.problem_offset == at, "%s: \"%s\" at %d (want %d)", what, g_bad.info.problem,
                   g_bad.info.problem_offset, at);
    } else {
        EXPECT(rc == 0, "%s accepted (%s)", what, rc ? g_bad.info.problem : "ok");
    }
}

static void test_images(void) {
    printf("images\n");
    const SaInfo* info = &g_sam.info;
    EXPECT(info->loaded == SA_LOADED && info->variant == SA_VARIANT_SAM && info->channels == 6 && info->chips == 1 &&
               info->orders == 4 && info->loop == 1 && info->multispeed == 1,
           "SAM song info");
    EXPECT(info->rows[0] == 16 && info->rows[1] == 32 && info->rows[2] == 16 && info->rows[3] == 24, "rows per order");
    EXPECT(g_cms.info.loaded == SA_LOADED && g_cms.info.variant == SA_VARIANT_CMS && g_cms.info.channels == 12 &&
               g_cms.info.chips == 2 && !memcmp(g_cms.info.rows, info->rows, sizeof info->rows),
           "CMS song info: twelve channels, two chips, the same orders");
    EXPECT(g_x2.info.multispeed == 2, "x2 image: multispeed 2");
    EXPECT(info->start_speed[0] == g_sam.bytes[SA_H_SPEED], "order 0 starts at the header speed");
    printf("  start speeds %d %d %d %d, %d instruments\n", info->start_speed[0], info->start_speed[1], info->start_speed[2],
           info->start_speed[3], info->instruments);
    EXPECT(sa_instrument(&g_sam, BUZZ_SLOT)[SA_I_ENVELOPE] == 0x86 && SAA_ENV_EDIT_SLOT == BUZZ_SLOT, "slot 3: the buzz");

    corrupt(SA_H_MAGIC, 'X', "magic");
    corrupt(SA_H_LAYOUT, 2, "layout");
    corrupt(SA_H_VARIANT, 2, "variant");
    corrupt_bytes(SA_H_VARIANT, SA_VARIANT_CMS, 1, "channel count", SA_H_CHANNELS);
    corrupt(SA_H_CHANNELS, 12, "channel count");
    corrupt(SA_H_CHANNELS, 7, "channel count");
    corrupt(SA_H_MULTISPEED, 0, "multispeed");
    corrupt(SA_H_MULTISPEED, 9, "multispeed");
    corrupt(SA_H_SPEED, 0, "speed");
    corrupt_bytes(SA_H_ORDERS, 0, 2, "order count", SA_H_ORDERS);
    corrupt_bytes(SA_H_ORDERS, 257, 2, "order count", SA_H_ORDERS);
    corrupt_bytes(SA_H_LOOP, 4, 2, "loop", SA_H_LOOP);
    corrupt(SA_H_INSTRUMENTS, 65, "instrument count");
    corrupt_bytes(SA_H_INSTRUMENTS, info->instruments - 1, 1, "past the instrument count", instrument_at(info->instruments));
    if (info->instruments < 64) corrupt(SA_H_INSTRUMENTS, info->instruments + 1, "empty slot");
    corrupt_bytes(SA_H_DATA_SIZE + 1, 0xFF, 1, "data size", SA_H_DATA_SIZE);
    corrupt(20, 1, "header reserved");
    corrupt(63, 1, "header reserved");
    corrupt(SA_OFF_ROWS + 9, 4, "past the order count");
    corrupt(SA_OFF_ORDERS + 2 * SA_MAX_CHANNELS * 9 + 1, 1, "past the order count");
    corrupt(SA_OFF_ORDERS + 2 * 6, 1, "past the channel count");
    corrupt(SA_OFF_ORDERS + 2 * SA_MAX_CHANNELS * 3 + 2 * 11 + 1, 1, "past the channel count");
    corrupt_bytes(SA_OFF_ORDERS + 1, 0xF0, 1, "pattern offset", SA_OFF_ORDERS);

    const int ins = SAA_OFFSET_INSTRUMENT;
    EXPECT(ins == instrument_at(1), "SAA_OFFSET_INSTRUMENT is slot 1's record");
    corrupt(ins + SA_I_FLAGS, 0x84, "instrument flags");
    corrupt(ins + SA_I_FLAGS, 0x01, "instrument flags");
    corrupt(ins + SA_I_FLAGS, 0x82, "retrigger");
    accepted(ins + SA_I_FLAGS, 0x81, 1, "PITCH_RELATIVE");
    corrupt(ins + SA_I_NOISE, 4, "noise clock");
    accepted(ins + SA_I_NOISE, 3, 1, "noise clock 3");
    corrupt(ins + SA_I_ENVELOPE, 0x40, "hardware envelope");
    corrupt(ins + SA_I_ENVELOPE, 0xC0, "hardware envelope");
    corrupt(ins + SA_I_ENVELOPE, 0x20, "hardware envelope");
    accepted(ins + SA_I_ENVELOPE, 0x9F, 1, "an internal envelope 0x9F");
    corrupt_bytes(ins + SA_I_ENVELOPE, 0xA0, 1, "external envelope rate", ins + SA_I_RATE);
    corrupt(ins + SA_I_RATE, 1, "rate without an external envelope");
    corrupt(ins + 5, 1, "reserved byte 5");
    corrupt(ins + 14, 1, "reserved bytes 14..15");
    corrupt(ins + 15, 1, "reserved bytes 14..15");
    accepted(ins + SA_I_PAN, 0x00, 1, "pan 0/0");
    {
        int external = 0;
        for (int s = 1; s <= info->instruments && !external; ++s)
            if (sa_instrument(&g_sam, s)[SA_I_ENVELOPE] & SA_ENV_EXTERNAL) external = s;
        EXPECT(external > 0, "the song has an external envelope instrument");
        if (external) {
            corrupt(instrument_at(external) + SA_I_RATE, 0, "external envelope rate");
            accepted(instrument_at(external) + SA_I_RATE, 255, 1, "rate 255");
        }
    }

    const int ev = SAA_OFFSET_ENV_VOLUME, length = sa_u16(g_sam.bytes + ev);
    const int release = sa_u16(g_sam.bytes + ev + SA_ENV_RELEASE);
    EXPECT(length >= 2 && release >= 1 && release < length, "SAA_OFFSET_ENV_VOLUME: a volume envelope with a release point");
    corrupt_bytes(ev, 0, 2, "envelope length", ev);
    corrupt_bytes(ev, 257, 2, "envelope length", ev);
    corrupt_bytes(ev + SA_ENV_RELEASE, 0, 2, "envelope release", ev + SA_ENV_RELEASE);
    corrupt_bytes(ev + SA_ENV_RELEASE, length, 2, "envelope release", ev + SA_ENV_RELEASE);
    corrupt_bytes(ev + SA_ENV_LOOP, length, 2, "envelope loop", ev + SA_ENV_LOOP);
    corrupt_bytes(ev + SA_ENV_LOOP, release, 2, "envelope loop", ev + SA_ENV_LOOP);
    corrupt(ev + SA_ENV_HEADER, 16, "volume value");
    corrupt(SAA_OFFSET_ENV_MIXER, 4, "mixer value");
    accepted(SAA_OFFSET_ENV_MIXER, 3, 1, "mixer 3");
    accepted(SAA_OFFSET_ENV_MIXER, 0, 1, "mixer 0");
    corrupt_bytes(SAA_OFFSET_ENV_MIXER - SA_ENV_HEADER + SA_ENV_RELEASE, 1, 2, "envelope release",
                  SAA_OFFSET_ENV_MIXER - SA_ENV_HEADER + SA_ENV_RELEASE);
    const int arp = find_envelope(SA_I_ARPEGGIO), pitch = find_envelope(SA_I_PITCH);
    EXPECT(arp > 0 && pitch > 0, "the song has arpeggio and pitch envelopes");
    if (arp > 0) {
        corrupt(arp, 64, "arpeggio value");
        corrupt(arp, 0xBF, "arpeggio value");
        accepted(arp, 0xC0, 1, "arpeggio -64");
    }
    if (pitch > 0) {
        corrupt(pitch, 64, "pitch value");
        corrupt(pitch, 0xBF, "pitch value");
        accepted(pitch, 63, 1, "pitch +63");
    }

    corrupt(SAA_OFFSET_MASK, 0x20, "mask");
    corrupt(SAA_OFFSET_MASK, 0x40, "mask");
    corrupt(SAA_OFFSET_MASK, 0x17, "mask");
    corrupt(SAA_OFFSET_MASK, 0x00, "mask");
    corrupt(SAA_OFFSET_NOTE, 120, "note");
    corrupt(SAA_OFFSET_NOTE, 0xFF, "note");
    accepted(SAA_OFFSET_NOTE, 119, 1, "note 119 (B-9)");
    accepted(SAA_OFFSET_NOTE, SA_NOTE_OFF, 1, "OFF");
    corrupt(SAA_OFFSET_INST, 0, "instrument");
    corrupt(SAA_OFFSET_INST, 65, "instrument");
    {
        int free_slot = 0;
        for (int s = 1; s <= 64 && !free_slot; ++s)
            if (!sa_instrument_flags(&g_sam, s)) free_slot = s;
        EXPECT(free_slot > 0, "the song leaves a slot free");
        if (free_slot) corrupt(SAA_OFFSET_INST, free_slot, "instrument");
    }
    corrupt(SAA_OFFSET_EFFECT, 36, "effect code");
    corrupt(SAA_OFFSET_EFFECT, 5, "unknown effect");
    corrupt(SAA_OFFSET_EFFECT, 29, "unknown effect"); /* T */
    corrupt(SAA_OFFSET_EFFECT, 32, "unknown effect"); /* W */
    corrupt(SAA_OFFSET_PARAM_B, info->orders, "Bxx");
    accepted(SAA_OFFSET_PARAM_B, info->orders - 1, 1, "Bxx to the last order");
    corrupt(SAA_OFFSET_PARAM_C, 0x10, "Cxx");
    accepted(SAA_OFFSET_PARAM_C, 0x0F, 1, "C0F");
    corrupt(SAA_OFFSET_PARAM_E, 0x40, "Exx");
    corrupt(SAA_OFFSET_PARAM_E, 0x7F, "Exx");
    corrupt(SAA_OFFSET_PARAM_E, 0xC0, "Exx");
    accepted(SAA_OFFSET_PARAM_E, 0x00, 1, "E00");
    accepted(SAA_OFFSET_PARAM_E, 0xBF, 1, "EBF");
    corrupt(SAA_OFFSET_PARAM_F, 0, "F00");
    accepted(SAA_OFFSET_PARAM_F, 0xFF, 1, "FFF");
    corrupt(SAA_OFFSET_PARAM_M, 4, "Mxx");
    accepted(SAA_OFFSET_PARAM_M, 0, 1, "M00");
    corrupt(SAA_OFFSET_PARAM_N, 4, "Nxx");
    accepted(SAA_OFFSET_PARAM_N, 3, 1, "N03");
    corrupt(SAA_OFFSET_PARAM_Y, 4, "Yxx");
    accepted(SAA_OFFSET_PARAM_Y, 3, 1, "Y03");

    /* Constructed images for the reasons a single byte of the song cannot reach. */
    base_image(&g_bad, SA_VARIANT_SAM);
    constructed("the minimal SAM image", NULL, -1);
    base_image(&g_bad, SA_VARIANT_CMS);
    constructed("the minimal CMS image", NULL, -1);
    put16(g_bad.bytes + SA_OFF_ORDERS + 2 * 11, 0);
    constructed("CMS: channel 12's pattern at offset 0", NULL, -1);
    base_image(&g_bad, SA_VARIANT_CMS);
    g_bad.bytes[SA_H_MULTISPEED] = 8;
    g_bad.bytes[SA_H_SPEED] = 255;
    constructed("the largest header values (CMS, multispeed 8, speed 255)", NULL, -1);
    base_image(&g_bad, SA_VARIANT_SAM);
    g_bad.bytes[SA_H_INSTRUMENTS] = 0;
    memset(g_bad.bytes + instrument_at(1), 0, SA_INSTRUMENT_SIZE);
    constructed("no instruments", NULL, -1);
    {
        /* 256 orders (order 0 of 256 rows: two runs of 128), loop 255 */
        static const uint8_t runs[] = {0xFF, 0xFF};
        base_image(&g_bad, SA_VARIANT_SAM);
        put16(g_bad.bytes + SA_H_ORDERS, 256);
        put16(g_bad.bytes + SA_H_LOOP, 255);
        for (int o = 0; o < 256; ++o) g_bad.bytes[SA_OFF_ROWS + o] = (uint8_t)(o == 0 ? 0 : 1);
        const int at = b_put(&g_bad, runs, (int)sizeof runs);
        for (int c = 0; c < 6; ++c) put16(g_bad.bytes + SA_OFF_ORDERS + 2 * c, at);
        constructed("256 orders, order 0 of 256 rows, loop 255", NULL, -1);
        put16(g_bad.bytes + SA_H_DATA_SIZE, at + 1);
        constructed("a run of 128 rows for an order of 256", "runs past the data size", -1);
    }
    {
        base_image(&g_bad, SA_VARIANT_SAM);
        g_bad.bytes[SA_OFF_ROWS] = 2;
        constructed("a pattern decoding past the data", "runs past the data size", -1);
        static const uint8_t note_mask[] = {SA_MASK_NOTE}; /* the note byte would be at the data size */
        base_image(&g_bad, SA_VARIANT_SAM);
        b_track(&g_bad, 0, note_mask, 1);
        constructed("a row whose note byte is past the data", "runs past the data size", -1);
        static const uint8_t note_row[] = {SA_MASK_NOTE, A4};
        base_image(&g_bad, SA_VARIANT_SAM);
        b_track(&g_bad, 0, note_row, 2);
        constructed("the same row with its note byte", NULL, -1);
    }
    {
        base_image(&g_bad, SA_VARIANT_SAM);
        static const int8_t v[] = {15, 15, 15, 0};
        const int at = b_env(&g_bad, v, 4, 2, 2);
        put16(g_bad.bytes + instrument_at(1) + SA_I_VOLUME, at);
        constructed("loop = release", "envelope loop", SA_OFF_DATA + at + SA_ENV_LOOP);
        g_bad.bytes[SA_OFF_DATA + at + SA_ENV_LOOP] = 1;
        constructed("loop 1 before release 2", NULL, -1);
        put16(g_bad.bytes + instrument_at(1) + SA_I_ARPEGGIO, at);
        constructed("a release point on an arpeggio envelope", "envelope release", SA_OFF_DATA + at + SA_ENV_RELEASE);
        put16(g_bad.bytes + instrument_at(1) + SA_I_ARPEGGIO, SA_ENV_NONE);
        put16(g_bad.bytes + SA_OFF_DATA + at + SA_ENV_LENGTH, 9);
        constructed("an envelope past the data", "runs past the data size", SA_OFF_DATA + at);
    }
    base_image(&g_bad, SA_VARIANT_SAM);
    put16(g_bad.bytes + SA_H_DATA_SIZE, SA_DATA_CAPACITY + 1);
    constructed("data size above the region", "data size", SA_H_DATA_SIZE);
    base_image(&g_bad, SA_VARIANT_SAM);
    b_inst(&g_bad, 2, SA_FLAG_ENV_RETRIGGER, 3, 0x0F, 0xB5, 7, -1, -1, -1, -1);
    constructed("an external envelope instrument with rate 7 and retrigger", NULL, -1);
    g_bad.bytes[SA_H_INSTRUMENTS] = 3;
    constructed("the count naming an empty slot", "empty slot", SA_H_INSTRUMENTS);

    SaaImage* again = &g_bad;
    memcpy(again, &g_sam, sizeof *again);
    EXPECT(sa_load_image(again) == 0 && !memcmp(&again->info, &g_sam.info, sizeof again->info), "loading is a pure function");
}

/* ---- the reference player ------------------------------------------------------------------------------------ */

static void run_reference(const char* name, const SaaImage* image, const Ref* ref, FILE* trace) {
    SaaMachine* m = saam_create(RATE);
    int mode = -1;
    EXPECT(saam_start(m, image, 0, 0, SEEK_BUDGET, &mode) == SAAM_OK && mode == SAAM_SEEK_FROM_START, "%s: start", name);
    const SaPlayer* p = saam_player(m);
    EXPECT(ref->pos[0] == -1 && ref->pos[1] == -1 && ref->pos[2] == -1, "%s: reference batch 0 is the reset", name);
    EXPECT(same(p, ref, 0), "%s: reset batch = reference (%d writes)", name, p->writes);
    EXPECT(reset_batch_ok(p, image->info.chips), "%s: the reset batch writes every register, forced, in order (%d writes)", name,
           p->writes);
    EXPECT(core_matches_shadow(m, p), "%s: the cores hold the reset", name);
    trace_batch(trace, -1, -1, -1, -1, p);
    int mismatches = 0, first = -1, position_errors = 0, shadow_errors = 0, wrong_chip = 0, writes = 0, max_writes = 0;
    for (int k = 1; k <= SAA_REF_TICKS; ++k) {
        if (!pos_is(m, ref, k)) ++position_errors;
        int o, r, t;
        saam_position(m, &o, &r, &t);
        saam_tick(m, g_out);
        if (!same(p, ref, k)) {
            if (first < 0) first = k;
            ++mismatches;
        }
        if (!core_matches_shadow(m, p)) ++shadow_errors;
        for (int i = 0; i < p->writes; ++i)
            if (p->write[i].chip >= image->info.chips || p->write[i].reg > 0x1C) ++wrong_chip;
        writes += p->writes;
        if (p->writes > max_writes) max_writes = p->writes;
        trace_batch(trace, k - 1, o, r, t, p);
    }
    EXPECT(mismatches == 0, "%s: writes = reference player in all %d ticks (%d differ, first at tick %d)", name, SAA_REF_TICKS,
           mismatches, first - 1);
    if (first > 0) {
        saam_start(m, image, 0, 0, SEEK_BUDGET, &mode);
        for (int k = 1; k <= first; ++k) saam_tick(m, g_out);
        printf("  tick %d machine  :", first - 1);
        for (int i = 0; i < p->writes && i < 16; ++i) printf(" %d:%02x=%02x", p->write[i].chip, p->write[i].reg, p->write[i].val);
        printf("\n  tick %d reference:", first - 1);
        for (int i = 0; i < ref_count(ref, first) && i < 16; ++i) {
            const int j = ref->offsets[first] + i;
            printf(" %d:%02x=%02x", ref->chips[j], ref->regs[j], ref->vals[j]);
        }
        printf("\n");
    }
    EXPECT(position_errors == 0, "%s: positions = reference in every tick (%d differ)", name, position_errors);
    EXPECT(shadow_errors == 0, "%s: the chips' registers = the player's shadow after every tick (%d differ)", name, shadow_errors);
    EXPECT(wrong_chip == 0, "%s: only this machine's chips and registers written (%d others)", name, wrong_chip);
    EXPECT(max_writes < SAP_MAX_WRITES, "%s: batches fit", name);
    printf("  %s: %d ticks, %d writes (max %d per tick)\n", name, SAA_REF_TICKS, writes, max_writes);
    saam_destroy(m);
}

static void test_reference(const char* dir) {
    printf("reference player\n");
    char path[512];
    snprintf(path, sizeof path, "%s/saa_trace.txt", dir);
    FILE* trace = fopen(path, "w");
    EXPECT(trace != NULL, "%s opened", path);
    run_reference("sam", &g_sam, &REF_SAM, trace);
    if (trace) fclose(trace);
    run_reference("cms", &g_cms, &REF_CMS, NULL);
    run_reference("edited", &g_edited, &REF_EDITED, NULL);
    run_reference("tempo", &g_tempo, &REF_TEMPO, NULL);
    int chip_b = 0;
    for (int i = 0; i < REF_CMS.offsets[SAA_REF_TICKS + 1]; ++i) chip_b += REF_CMS.chips[i] == 1;
    EXPECT(chip_b > 200, "the CMS song writes chip B (%d writes)", chip_b);
}

/* ---- audio --------------------------------------------------------------------------------------------------- */

static void test_audio(const char* dir) {
    printf("audio\n");
    for (int cms = 0; cms < 2; ++cms) {
        const SaaImage* image = cms ? &g_cms : &g_sam;
        const char* name = cms ? "CMS" : "SAM";
        const int ticks = SECONDS * (cms ? SAAM_RATE_CMS : SAAM_RATE_SAM);
        uint64_t hash[2] = {0, 0};
        for (int pass = 0; pass < 2; ++pass) {
            SaaMachine* m = saam_create(RATE);
            int mode;
            saam_start(m, image, 0, 0, SEEK_BUDGET, &mode);
            float* buf;
            const double t0 = now_us();
            const int frames = render(m, ticks, &buf);
            const double us = (now_us() - t0) / ticks;
            hash[pass] = fnv(0xCBF29CE484222325ull, buf, (size_t)frames * 2 * sizeof(float));
            if (pass == 0) {
                const double r = rms(buf, frames), pk = peak(buf, frames);
                EXPECT(frames >= SECONDS * RATE - 2 && frames <= SECONDS * RATE + 2, "%s: 30 s = %d frames (got %d)", name,
                       SECONDS * RATE, frames);
                EXPECT(r > 0.02, "%s: non-silent (rms %.4f)", name, r);
                EXPECT(pk < 0.999, "%s: no clipping (peak %.4f)", name, pk);
                EXPECT(stereo(buf, frames), "%s: stereo (pans differ per side)", name);
                int quiet = 0, run = 0, longest = 0;
                for (int s = 0; s < SECONDS && (s + 1) * RATE <= frames; ++s) {
                    if (rms(buf + 2 * s * RATE, RATE) < 0.003) ++quiet, ++run;
                    else run = 0;
                    if (run > longest) longest = run;
                }
                EXPECT(longest < 2, "%s: no two quiet seconds in a row (%d quiet)", name, quiet);
                write_wav(dir, cms ? "saa_cms_song.wav" : "saa_sam_song.wav", buf, frames);
                printf("  %s 30 s: rms %.4f, peak %.4f, %.1f us per tick (%.0fx realtime)\n", name, r, pk, us,
                       1e6 / (cms ? SAAM_RATE_CMS : SAAM_RATE_SAM) / us);
            }
            free(buf);
            saam_destroy(m);
        }
        EXPECT(hash[0] == hash[1], "%s: bit-identical on a second start", name);
    }
}

/* Every pass of the loop steps through the same positions (the loop order entered from order 0 and later by B01 from
 * order 3 at speed 5; the player re-derives the speed). */
static void test_loop(void) {
    printf("loop passes\n");
    enum { MAX_PASS = 8192 };
    static int pos[4][MAX_PASS];
    for (int cms = 0; cms < 2; ++cms) {
        const SaaImage* image = cms ? &g_cms : &g_sam;
        int len[4] = {0};
        SaaMachine* m = saam_create(RATE);
        int mode, o, r, t, pass = -1;
        saam_start(m, image, 0, 0, SEEK_BUDGET, &mode);
        for (int k = 0; k < 4 * MAX_PASS && pass < 4; ++k) {
            saam_position(m, &o, &r, &t);
            if (o == image->info.loop && r == 0 && t == 0) ++pass;
            saam_tick(m, g_out);
            if (pass < 0 || pass >= 4 || len[pass] >= MAX_PASS) continue;
            pos[pass][len[pass]++] = o << 16 | r << 8 | t;
        }
        EXPECT(pass == 4 && len[0] == len[1] && len[1] == len[2] && len[0] < MAX_PASS, "%s: passes of %d, %d, %d ticks",
               cms ? "CMS" : "SAM", len[0], len[1], len[2]);
        EXPECT(!memcmp(pos[0], pos[1], sizeof(int) * (size_t)len[0]) && !memcmp(pos[1], pos[2], sizeof(int) * (size_t)len[0]),
               "every pass steps through the same (order, row, tick) positions");
        const uint32_t per_tick = cms ? 735 : 882;
        const uint32_t want = cms ? SAA_CMS_REF_VGM_LOOP_SAMPLES : SAA_SAM_REF_VGM_LOOP_SAMPLES;
        EXPECT((uint32_t)len[0] * per_tick == want, "%s: one pass = the VGM loop (%u samples)", cms ? "CMS" : "SAM", (unsigned)want);
        printf("  %s: %d ticks per pass\n", cms ? "CMS" : "SAM", len[0]);
        saam_destroy(m);
    }
}

/* ---- seek ---------------------------------------------------------------------------------------------------- */

static void seek_targets(const char* name, const SaaImage* image, const Ref* ref, const int* at_ticks, int count) {
    const int preroll = SAAM_PREROLL_SECONDS * (image->info.variant == SA_VARIANT_CMS ? SAAM_RATE_CMS : SAAM_RATE_SAM) *
                        image->info.multispeed;
    SaaMachine* m = saam_create(RATE);
    int inside = 0, beyond = 0;
    for (int i = 0; i < count; ++i) {
        const int kt = at_ticks[i] + 1;
        const int o = ref->pos[3 * kt], r = ref->pos[3 * kt + 1], k = ref_row_start(ref, o, r);
        EXPECT(k > 0, "%s: target %d/%d is in the reference", name, o, r);
        if (k <= 0) continue;
        const int n = k - 1; /* ticks straight play runs before the target */
        int mode = -1;
        const double t0 = now_us();
        EXPECT(saam_start(m, image, o, r, SEEK_BUDGET, &mode) == SAAM_OK && mode == SAAM_SEEK_FROM_START, "%s: seek %d/%d", name, o,
               r);
        const double us = now_us() - t0;
        EXPECT(pos_is(m, ref, k) && saam_ticks(m) == 0, "%s: lands on %d/%d tick 0", name, o, r);
        SaaMachine* a = saam_create(RATE);
        saam_start(a, image, 0, 0, SEEK_BUDGET, &mode);
        for (int j = 0; j < n; ++j) saam_tick(a, g_out);
        EXPECT(!memcmp(saam_core(a, 0), saam_core(m, 0), sizeof(SacCore)) &&
                   !memcmp(saam_core(a, 1), saam_core(m, 1), sizeof(SacCore)),
               "%s: seek %d/%d (%d ticks): both cores = straight play's (memcmp)", name, o, r, n);
        EXPECT(sap_same_state(saam_player(a), saam_player(m)), "%s: player state = straight play's", name);
        float *x, *y;
        const int fx = render(a, preroll, &x);
        const int left = SAA_REF_TICKS - k + 1, span = left < 200 ? left : 200;
        int bad = 0, fy = 0;
        y = (float*)malloc((size_t)preroll * SAAM_MAX_TICK_FRAMES * 2 * sizeof(float));
        for (int j = 0; j < span; ++j) {
            if (!pos_is(m, ref, k + j)) ++bad;
            const int f = saam_tick(m, j < preroll ? y + 2 * fy : g_out);
            if (j < preroll) fy += f;
            if (!same(saam_player(m), ref, k + j)) ++bad;
        }
        EXPECT(bad == 0, "%s: seek %d/%d: %d ticks of writes and positions = straight play (%d differ)", name, o, r, span, bad);
        double worst = 0;
        for (int s = 0; s < 2 * (fx < fy ? fx : fy); ++s)
            if (fabs((double)x[s] - y[s]) > worst) worst = fabs((double)x[s] - y[s]);
        const int identical = fx == fy && !memcmp(x, y, sizeof(float) * 2 * (size_t)fx);
        if (n <= preroll) {
            ++inside;
            EXPECT(identical, "%s: seek %d/%d (%d ticks, inside the pre-roll): sound bit-identical", name, o, r, n);
        } else {
            ++beyond;
            EXPECT(fx == fy && worst <= 1.0 / 32768 + 1e-9, "%s: seek %d/%d (%d ticks): every sample within 1/32768 (worst %.3g)",
                   name, o, r, n, worst * 32768);
        }
        printf("  %s %d/%d: %d ticks fast-forwarded in %.0f us; sound %s (worst %.2f LSB)\n", name, o, r, n, us,
               identical ? "identical" : "differs", worst * 32768);
        free(x);
        free(y);
        saam_destroy(a);
    }
    EXPECT(inside >= 1 && beyond >= 2, "%s: targets inside (%d) and beyond (%d) the pre-roll", name, inside, beyond);
    saam_destroy(m);
}

static void test_seek(void) {
    printf("seek\n");
    static const int sam_ticks[] = {8, 30, 49, 51, 100, 250, 400, 600, 900, 1200};
    static const int cms_ticks[] = {20, 59, 61, 300, 800, 1300};
    seek_targets("SAM", &g_sam, &REF_SAM, sam_ticks, (int)(sizeof sam_ticks / sizeof sam_ticks[0]));
    seek_targets("CMS", &g_cms, &REF_CMS, cms_ticks, (int)(sizeof cms_ticks / sizeof cms_ticks[0]));
    SaaMachine* m = saam_create(RATE);
    {
        const int k = ref_row_start(&REF_SAM, 1, 17), n = k - 1;
        int mode = -1, o, r, t;
        EXPECT(k > 2, "1/17 is in the reference");
        EXPECT(saam_start(m, &g_sam, 1, 17, n, &mode) == SAAM_OK && mode == SAAM_SEEK_FROM_START, "budget %d = the ticks needed", n);
        EXPECT(saam_start(m, &g_sam, 1, 17, n - 1, &mode) == SAAM_OK && mode == SAAM_SEEK_FROM_ORDER, "budget %d: order fallback",
               n - 1);
        saam_position(m, &o, &r, &t);
        EXPECT(o == 1 && r == 17 && t == 0, "budget fallback lands on 1/17 (%d/%d/%d)", o, r, t);
        EXPECT(saam_start(m, &g_sam, 1, 17, 10, &mode) == SAAM_OK && mode == SAAM_SEEK_FROM_ORDER, "small budget: order fallback");
    }
    /* Order 3 rows 0-1 are only reached by the order fallback (order 2 breaks to row 2). */
    EXPECT(ref_row_start(&REF_SAM, 3, 0) < 0 && ref_row_start(&REF_SAM, 3, 1) < 0, "3/0 and 3/1 are not reached by straight play");
    for (int target = 0; target < 2; ++target) {
        int mode = -1, o, r, t;
        EXPECT(saam_start(m, &g_sam, 3, target, SEEK_BUDGET, &mode) == SAAM_OK && mode == SAAM_SEEK_FROM_ORDER,
               "3/%d: order fallback", target);
        saam_position(m, &o, &r, &t);
        EXPECT(o == 3 && r == target && t == 0 && saam_ticks(m) == 0, "fallback lands on 3/%d (%d/%d/%d)", target, o, r, t);
        int good = 1, rows_seen = 0, last_row = target;
        for (int j = 0; j < 255 * 4 && rows_seen < 3; ++j) {
            saam_tick(m, g_out);
            saam_position(m, &o, &r, &t);
            if (t == 0) {
                if (o != 3 || r != last_row + 1) good = 0;
                last_row = r;
                ++rows_seen;
            }
        }
        EXPECT(good && rows_seen == 3, "fallback then plays order 3 row by row (%d rows)", rows_seen);
    }
    {
        int behind_order = -1, behind_row = -1;
        for (int o = 0; o < g_sam.info.orders && behind_order < 0; ++o) {
            if (ref_row_start(&REF_SAM, o, 0) < 0) continue;
            int max_row = -1;
            for (int k = 1; k <= SAA_REF_TICKS; ++k)
                if (REF_SAM.pos[3 * k] == o && REF_SAM.pos[3 * k + 1] > max_row) max_row = REF_SAM.pos[3 * k + 1];
            if (max_row + 1 < g_sam.info.rows[o]) behind_order = o, behind_row = max_row + 1;
        }
        EXPECT(behind_order >= 0, "the song has a row behind a Dxx of its order");
        if (behind_order >= 0) {
            int mode;
            EXPECT(saam_start(m, &g_sam, behind_order, behind_row, SEEK_BUDGET, &mode) == SAAM_ERR_IMAGE,
                   "%d/%d (behind its order's Dxx): unreachable, rejected", behind_order, behind_row);
        }
    }
    int mode;
    EXPECT(saam_start(m, &g_sam, 4, 0, SEEK_BUDGET, &mode) == SAAM_ERR_IMAGE, "order past the song rejected");
    EXPECT(saam_start(m, &g_sam, 0, 16, SEEK_BUDGET, &mode) == SAAM_ERR_IMAGE, "row past the order rejected");
    EXPECT(saam_start(m, &g_sam, -1, 0, SEEK_BUDGET, &mode) == SAAM_ERR_IMAGE &&
               saam_start(m, &g_sam, 0, -1, SEEK_BUDGET, &mode) == SAAM_ERR_IMAGE,
           "negative positions rejected");
    memcpy(&g_bad, &g_sam, sizeof g_bad);
    g_bad.bytes[0] = 'X';
    sa_load_image(&g_bad);
    EXPECT(saam_start(m, &g_bad, 0, 0, SEEK_BUDGET, &mode) == SAAM_ERR_IMAGE, "an image that failed to load rejected");
    saam_destroy(m);
}

/* ---- live edits ---------------------------------------------------------------------------------------------- */

/* Plays `image` from the start, applying alt0/alt1 (odd/even batches) before batch k (from batch `from`; before every
 * batch or only then), and compares with `ref`. */
static int play_applying(const SaaImage* image, const SaaImage* alt0, const SaaImage* alt1, int from, int every,
                         const Ref* ref, int* first) {
    SaaMachine* m = saam_create(RATE);
    int mode, bad = 0;
    saam_start(m, image, 0, 0, SEEK_BUDGET, &mode);
    *first = -1;
    for (int k = 1; k <= SAA_REF_TICKS; ++k) {
        if (k >= from && (every || k == from)) saam_apply_image(m, (k % 2) ? alt0 : alt1);
        if (!pos_is(m, ref, k)) ++bad;
        saam_tick(m, g_out);
        if (!same(saam_player(m), ref, k)) {
            if (*first < 0) *first = k - 1;
            ++bad;
        }
    }
    saam_destroy(m);
    return bad;
}

static int rest_of_row(SaaMachine* m) {
    int o, r, t, n = 1;
    for (;;) {
        saam_position(m, &o, &r, &t);
        if (t == 0 || n > 600) return n;
        saam_tick(m, g_out);
        ++n;
    }
}

/* The song with instrument `slot` deleted, as an editor deleting it would lower the song: its record zeroed, the count
 * lowered to the highest slot left, rows naming it pointed at `instead` (every track decoded in place). */
static int delete_slot(SaaImage* out, const SaaImage* in, int slot, int instead) {
    memcpy(out, in, sizeof *out);
    uint8_t* b = out->bytes;
    memset(b + instrument_at(slot), 0, SA_INSTRUMENT_SIZE);
    int top = 0;
    for (int s = 1; s <= SA_MAX_INSTRUMENTS; ++s)
        if (sa_instrument_flags(out, s)) top = s;
    b[SA_H_INSTRUMENTS] = (uint8_t)top;
    for (int o = 0; o < in->info.orders; ++o)
        for (int c = 0; c < in->info.channels; ++c) {
            int at = SA_OFF_DATA + sa_pattern(out, o, c);
            for (int left = in->info.rows[o]; left > 0;) {
                const int m = b[at++];
                if (m & SA_RUN) {
                    left -= (m & 0x7F) + 1;
                    continue;
                }
                if (m & SA_MASK_NOTE) ++at;
                if (m & SA_MASK_INSTRUMENT) {
                    if (b[at] == slot) b[at] = (uint8_t)instead;
                    ++at;
                }
                at += 2 * (m >> SA_MASK_EFFECT_SHIFT & 7);
                --left;
            }
        }
    return sa_load_image(out);
}

/* The amplitude register value of a held note: the player's rule. */
static int amplitude_of(int e, int volume, int pan_left, int pan_right) {
    const int cv = volume / 16;
    int l = e * cv * pan_left / 225, r = e * cv * pan_right / 225;
    if (!l && e && cv && pan_left) l = 1;
    if (!r && e && cv && pan_right) r = 1;
    return l | r << 4;
}

static void test_live_edits(void) {
    printf("live edits\n");
    int first;
    int bad = play_applying(&g_sam, &g_sam, &g_sam, 1, 1, &REF_SAM, &first);
    EXPECT(bad == 0, "same image applied before every tick: writes = reference (%d differ, first %d)", bad, first);
    bad = play_applying(&g_sam, &g_moved, &g_sam, 1, 1, &REF_SAM, &first);
    EXPECT(bad == 0, "re-laid-out image and the original alternately before every tick: writes = reference (%d differ, first %d)",
           bad, first);
    bad = play_applying(&g_cms, &g_cms, &g_cms, 1, 1, &REF_CMS, &first);
    EXPECT(bad == 0, "CMS: same image applied before every tick: writes = reference (%d differ, first %d)", bad, first);
    bad = play_applying(&g_sam, &g_cms, &g_cms, 1, 1, &REF_SAM, &first);
    EXPECT(bad == 0, "the other variant's image is ignored (%d differ, first %d)", bad, first);
    {
        SaaMachine* m = saam_create(RATE);
        int mode;
        saam_start(m, &g_sam, 0, 0, SEEK_BUDGET, &mode);
        double worst = 0, total = 0;
        for (int k = 0; k < 1500; ++k) {
            const double t0 = now_us();
            saam_apply_image(m, (k & 1) ? &g_moved : &g_sam);
            const double us = now_us() - t0;
            total += us;
            if (us > worst) worst = us;
            saam_tick(m, g_out);
        }
        printf("  apply_image: %.2f us mean, worst %.1f us\n", total / 1500, worst);
        saam_destroy(m);
    }
    const int d_edit = first_difference(&REF_SAM, &REF_EDITED);
    EXPECT(d_edit > 1, "the note edit changes the reference (first at batch %d)", d_edit);
    if (d_edit > 1) {
        const int k_edit = row_start_of(&REF_SAM, d_edit);
        const int at[] = {1, 40, k_edit - 30, k_edit - 2, k_edit - 1, k_edit};
        for (size_t i = 0; i < sizeof at / sizeof at[0]; ++i) {
            if (at[i] < 1) continue;
            bad = play_applying(&g_sam, &g_edited, &g_edited, at[i], 0, &REF_EDITED, &first);
            EXPECT(bad == 0, "note edit applied before tick %d: = edited straight play (%d differ, first %d)", at[i] - 1, bad, first);
        }
    }
    const int d_tempo = first_difference(&REF_SAM, &REF_TEMPO);
    EXPECT(d_tempo > 1, "the tempo edit changes the reference (first at batch %d)", d_tempo);
    if (d_tempo > 1) {
        const int a0 = row_start_of(&REF_SAM, d_tempo), a1 = row_start_of(&REF_TEMPO, d_tempo);
        const int k_tempo = a0 < a1 ? a0 : a1;
        bad = play_applying(&g_sam, &g_tempo, &g_tempo, k_tempo > 3 ? k_tempo - 3 : 1, 0, &REF_TEMPO, &first);
        EXPECT(bad == 0, "tempo edit ahead of the playhead: = tempo song straight play (%d differ, first %d)", bad, first);
        const int o = REF_SAM.pos[3 * k_tempo], r = REF_SAM.pos[3 * k_tempo + 1];
        const int old_len = ref_row_length(&REF_SAM, k_tempo), new_len = ref_row_length(&REF_TEMPO, k_tempo);
        SaaMachine* m = saam_create(RATE);
        int mode;
        saam_start(m, &g_sam, o, r, SEEK_BUDGET, &mode);
        saam_tick(m, g_out);
        saam_apply_image(m, &g_tempo);
        const int a = rest_of_row(m);
        saam_start(m, &g_sam, o, r, SEEK_BUDGET, &mode);
        saam_tick(m, g_out);
        saam_apply_image(m, &g_tempo);
        saam_apply_image(m, &g_sam);
        const int b = rest_of_row(m);
        EXPECT(old_len != new_len && a == new_len && b == old_len,
               "tempo edit behind the playhead (%d/%d): row of %d ticks (want %d), undo %d (want %d)", o, r, a, new_len, b, old_len);
        saam_destroy(m);
    }
    {
        SaaMachine* m = saam_create(RATE);
        int mode, o, r, t;
        const SaInfo* si = &g_short.info;
        EXPECT(si->orders == 2, "the short image has two orders (%d)", si->orders);
        saam_start(m, &g_sam, 3, 5, SEEK_BUDGET, &mode);
        saam_tick(m, g_out);
        saam_apply_image(m, &g_short);
        saam_position(m, &o, &r, &t);
        EXPECT(o == si->orders - 1 && r == si->rows[si->orders - 1] - 1, "clamped to the last row of the last order (%d/%d)", o, r);
        int good = 1, rows_seen = 0, last_order = o, last_row = r;
        for (int k = 0; k < 255 * 8 && rows_seen < 6; ++k) {
            saam_tick(m, g_out);
            saam_position(m, &o, &r, &t);
            if (t == 0) {
                int want_order = last_order, want_row = last_row + 1;
                if (want_row >= si->rows[last_order]) {
                    want_order = last_order + 1 < si->orders ? last_order + 1 : si->loop;
                    want_row = 0;
                }
                if (o != want_order || r != want_row) good = 0;
                last_order = o;
                last_row = r;
                ++rows_seen;
            }
        }
        EXPECT(good && rows_seen == 6, "then the song plays on in order (%d rows)", rows_seen);
        saam_start(m, &g_sam, 0, 0, SEEK_BUDGET, &mode);
        const int f1 = saam_tick(m, g_out);
        const uint32_t c1 = saam_clocks(m);
        saam_apply_image(m, &g_x2);
        const int f2 = saam_tick(m, g_out);
        const uint32_t c2 = saam_clocks(m);
        EXPECT(c1 == 160000 && c2 == 80000 && f1 >= 959 && f1 <= 961 && f2 >= 479 && f2 <= 481,
               "x2 live: %u -> %u clocks, %d -> %d frames per tick", c1, c2, f1, f2);
        saam_destroy(m);
    }
    /* The buzz's hardware and volume envelopes edited under a sounding note on SAA 3. */
    {
        const int slot = SAA_ENV_EDIT_SLOT;
        const uint8_t* rec = sa_instrument(&g_env_edit, slot);
        const int v = rec[SA_I_ENVELOPE];
        SaEnvelope vol;
        sa_envelope(&g_env_edit, slot, SA_I_VOLUME, &vol);
        EXPECT(v != sa_instrument(&g_sam, slot)[SA_I_ENVELOPE] && vol.length > 0, "env_edit changes slot %d's hardware envelope", slot);
        SaaMachine* a = saam_create(RATE);
        int mode, found = -1;
        saam_start(a, &g_sam, 0, 0, SEEK_BUDGET, &mode);
        const SaPlayer* pa = saam_player(a);
        for (int k = 1; k <= SAA_REF_TICKS && found < 0; ++k) {
            int o, r, t;
            saam_position(a, &o, &r, &t);
            const SaChannel* ch = &pa->ch[2];
            const int before = ch->active && ch->keyed && ch->slot == slot && ch->envelope < 0 && !ch->pending;
            saam_tick(a, g_out);
            if (before && t != 0 && ch->active && ch->keyed && ch->slot == slot && ch->envelope < 0) found = k;
        }
        saam_destroy(a);
        EXPECT(found > 0, "SAA 3 holds slot %d through a tick without a row start", slot);
        if (found > 0) {
            SaaMachine* m = saam_create(RATE);
            saam_start(m, &g_sam, 0, 0, SEEK_BUDGET, &mode);
            for (int k = 1; k < found; ++k) saam_tick(m, g_out);
            const SaPlayer* p = saam_player(m);
            saam_apply_image(m, &g_env_edit);
            int index = p->ch[2].env[0];
            if (index >= vol.length) index = vol.length - 1;
            const int want_amp = amplitude_of(vol.values[index], p->ch[2].volume, p->ch[2].pan >= 0 ? p->ch[2].pan >> 4 : rec[SA_I_PAN] & 15,
                                              p->ch[2].pan >= 0 ? p->ch[2].pan & 15 : rec[SA_I_PAN] >> 4);
            saam_tick(m, g_out);
            int wrote = 0;
            for (int i = 0; i < p->writes; ++i)
                if (p->write[i].chip == 0 && p->write[i].reg == SAC_REG_ENV0 && p->write[i].val == v) wrote = 1;
            const SacEnv* e = &saam_core(m, 0)->env[0];
            const int taken = (e->new_data && e->next_data == v) || (!e->new_data && e->shape == ((v >> 1) & 7));
            EXPECT(wrote && p->sh[0][SAC_REG_ENV0] == v && taken && e->enabled,
                   "slot %d's envelope edited at tick %d: 0x%02X written at the next tick, the chip buffers or runs it", slot,
                   found - 1, v);
            EXPECT(p->sh[0][SAC_REG_AMP + 2] == want_amp, "the edited volume envelope is heard at the next tick (0x%02X, want 0x%02X)",
                   p->sh[0][SAC_REG_AMP + 2], want_amp);
            saam_destroy(m);
        }
    }
    /* An instrument deleted under a sounding note cuts it at the next tick. */
    {
        SaaMachine* a = saam_create(RATE);
        int mode, found = -1, channel = -1, slot = 0;
        saam_start(a, &g_sam, 0, 0, SEEK_BUDGET, &mode);
        const SaPlayer* pa = saam_player(a);
        for (int k = 1; k <= SAA_REF_TICKS && found < 0; ++k) {
            int o, r, t, before[SA_MAX_CHANNELS];
            saam_position(a, &o, &r, &t);
            for (int c = 0; c < 6; ++c)
                before[c] = pa->ch[c].active && pa->ch[c].keyed && !pa->ch[c].pending ? pa->ch[c].slot : 0;
            saam_tick(a, g_out);
            if (k < 100 || t == 0) continue;
            for (int c = 0; c < 6 && found < 0; ++c)
                if (before[c] && before[c] != TONE_SLOT && pa->ch[c].active && pa->ch[c].keyed && pa->ch[c].slot == before[c] &&
                    pa->sh[0][SAC_REG_AMP + c] != 0)
                    found = k, channel = c, slot = before[c];
        }
        saam_destroy(a);
        EXPECT(found > 0, "a note sounding through a tick without a row start");
        if (found > 0) {
            EXPECT(delete_slot(&g_work, &g_sam, slot, TONE_SLOT) == 0, "the song without slot %d loads (%s)", slot,
                   g_work.info.problem ? g_work.info.problem : "ok");
            SaaMachine* m = saam_create(RATE);
            saam_start(m, &g_sam, 0, 0, SEEK_BUDGET, &mode);
            for (int k = 1; k < found; ++k) saam_tick(m, g_out);
            const SaPlayer* p = saam_player(m);
            const int sounding = p->ch[channel].active && p->ch[channel].keyed && p->ch[channel].slot == slot;
            saam_apply_image(m, &g_work);
            saam_tick(m, g_out);
            const SacCore* c = saam_core(m, 0);
            EXPECT(sounding && !p->ch[channel].active && !p->ch[channel].keyed && c->ch[channel].amp_l == 0 &&
                       c->ch[channel].amp_r == 0 && !c->ch[channel].tone && !c->ch[channel].noise && c->out_l[channel] == 0 &&
                       c->out_r[channel] == 0,
                   "slot %d deleted under SAA %d's note at tick %d: cut (amplitude 0, mixer bits 0, inactive)", slot, channel + 1,
                   found - 1);
            saam_destroy(m);
        }
    }
}

/* ---- audition ------------------------------------------------------------------------------------------------ */

static void tone_check(const char* dir, const char* file, const SaaImage* image, int channel, double want_hz, int want_oo) {
    const int cms = image->info.variant == SA_VARIANT_CMS;
    const int rate = cms ? SAAM_RATE_CMS : SAAM_RATE_SAM;
    SaaMachine* m = saam_create(RATE);
    const double t0 = now_us();
    EXPECT(saam_audition(m, image, channel, A4, TONE_SLOT, 0) == SAAM_OK, "%s: A-4 on lane %d", file, channel + 1);
    const double setup = now_us() - t0;
    float* buf;
    const int frames = render(m, 2 * rate, &buf);
    const int chip = channel / 6, l = channel % 6;
    const SacCore* c = saam_core(m, chip);
    EXPECT(c->tone[l].oct == want_oo >> 8 && c->tone[l].off == (want_oo & 0xFF) && c->ch[l].amp_l == 15 && c->ch[l].amp_r == 15 &&
               c->ch[l].tone && !c->ch[l].noise,
           "%s: chip %d channel %d plays O %d N %d at 15/15, tone only", file, chip, l, c->tone[l].oct, c->tone[l].off);
    const double hz = zero_cross_hz(buf + 2 * (RATE / 10), frames - RATE / 10, 0);
    const double hz_r = zero_cross_hz(buf + 2 * (RATE / 10), frames - RATE / 10, 1);
    EXPECT(fabs(hz - want_hz) < 0.05 && fabs(hz_r - want_hz) < 0.05, "%s: A-4 measured %.3f / %.3f Hz (want %.3f)", file, hz, hz_r,
           want_hz);
    /* the step: the means of the high and low plateaus (the band-limited steps ring around both) */
    double mid = 0, hi = 0, lo = 0;
    int n_hi = 0, n_lo = 0;
    for (int i = RATE / 2; i < frames; ++i) mid += buf[2 * i];
    mid /= frames - RATE / 2;
    for (int i = RATE / 2; i < frames; ++i) {
        if (buf[2 * i] > mid) hi += buf[2 * i], ++n_hi;
        else lo += buf[2 * i], ++n_lo;
    }
    hi = n_hi ? hi / n_hi : 0;
    lo = n_lo ? lo / n_lo : 0;
    const double want_p2p = SAC_OUT_MAX * (double)(cms ? SAAM_LEVEL_SCALE_CMS : SAAM_LEVEL_SCALE_SAM) / 32768;
    EXPECT(fabs((hi - lo) - want_p2p) < 0.05 * want_p2p, "%s: square step %.4f = 480 x scale / 32768 (%.4f)", file, hi - lo,
           want_p2p);
    /* no release point: release cuts (amplitude 0) */
    EXPECT(saam_release(m) == 0 && c->ch[l].amp_l == 0 && c->ch[l].amp_r == 0, "%s: no release point: release zeroes the amplitude",
           file);
    float* tail = (float*)malloc(sizeof(float) * 2 * SAAM_MAX_TICK_FRAMES * 15);
    int tail_frames = 0, n;
    for (int k = 0; k < 15 && (n = saam_tick(m, tail + 2 * tail_frames)) > 0; ++k) tail_frames += n;
    float* both = (float*)malloc(sizeof(float) * 2 * (size_t)(frames + tail_frames + 1));
    memcpy(both, buf, sizeof(float) * 2 * (size_t)frames);
    memcpy(both + 2 * frames, tail, sizeof(float) * 2 * (size_t)tail_frames);
    write_wav(dir, file, both, frames + tail_frames);
    printf("  %s: A-4 %.3f Hz (O %d N %d), step %.4f, setup %.0f us\n", file, hz, want_oo >> 8, want_oo & 0xFF, hi - lo, setup);
    free(both);
    free(tail);
    free(buf);
    saam_destroy(m);
}

static void test_audition(const char* dir) {
    printf("audition\n");
    tone_check(dir, "saa_tone_sam.wav", &g_sam, 0, A4_HZ_SAM, 3 << 8 | 227);
    tone_check(dir, "saa_tone_cms.wav", &g_cms, 6, A4_HZ_CMS, 4 << 8 | 3); /* chip B, lane B1 */

    SaaMachine* m = saam_create(RATE);
    const SaPlayer* p = saam_player(m);
    /* fine pitch: +64 units = +1 semitone */
    EXPECT(saam_audition(m, &g_sam, 4, A4, TONE_SLOT, 64) == SAAM_OK, "A-4 fine +64 on SAA 5");
    float* buf;
    const int f2 = render(m, 50, &buf);
    const int want = sap_octave_offset(58 * 64, 0);
    const double up = zero_cross_hz(buf + 2 * (RATE / 10), f2 - RATE / 10, 0);
    EXPECT(saam_core(m, 0)->tone[4].oct == want >> 8 && saam_core(m, 0)->tone[4].off == (want & 0xFF) &&
               fabs(up - tone_hz(want, 0)) < 0.06,
           "fine +64 = A#4: %.3f Hz (want %.3f)", up, tone_hz(want, 0));
    free(buf);

    /* the buzz on SAA 3: its hardware envelope written in the first tick */
    EXPECT(saam_audition(m, &g_sam, 2, 45, BUZZ_SLOT, 0) == SAAM_OK, "the buzz on SAA 3");
    saam_tick(m, g_out);
    EXPECT(p->sh[0][SAC_REG_ENV0] == 0x86 && saam_core(m, 0)->env[0].enabled && !saam_core(m, 0)->env[0].external,
           "its internal envelope runs (0x%02X)", p->sh[0][SAC_REG_ENV0]);

    /* a release point: the pluck releases, then ends once its envelope holds 0 */
    EXPECT(saam_audition(m, &g_sam, 0, A4, PLUCK_SLOT, 0) == SAAM_OK, "the pluck");
    for (int k = 0; k < 6; ++k) saam_tick(m, g_out);
    EXPECT(saam_release(m) == 1, "release phase");
    int ticks = 0, n;
    while ((n = saam_tick(m, g_out)) > 0 && ticks < 2 * 50 * SAAM_RELEASE_SECONDS) ++ticks;
    EXPECT(n == 0 && ticks < 50 * SAAM_RELEASE_SECONDS && sap_silent(p, &g_sam, 0), "the release ends when its envelope holds 0 (%d ticks)",
           ticks);
    EXPECT(saam_tick(m, g_out) == 0, "and stays ended");

    /* a release that never falls silent ends after SAAM_RELEASE_SECONDS */
    {
        static const int8_t env[] = {15, 15};
        b_init(&g_tone, SA_VARIANT_SAM, 6, 1);
        const int at = b_env(&g_tone, env, 2, -1, 1);
        b_inst(&g_tone, 1, 0, 0, 0xFF, 0, 0, at, -1, -1, -1);
        EXPECT(sa_load_image(&g_tone) == 0, "tone image loads (%s)", g_tone.info.problem ? g_tone.info.problem : "ok");
        EXPECT(saam_audition(m, &g_tone, 2, A4, 1, 0) == SAAM_OK, "a sustaining release");
        saam_tick(m, g_out);
        EXPECT(saam_release(m) == 1, "release phase");
        ticks = 0;
        while ((n = saam_tick(m, g_out)) > 0 && ticks < 4 * 50 * SAAM_RELEASE_SECONDS) ++ticks;
        EXPECT(n == 0 && ticks >= 50 * SAAM_RELEASE_SECONDS - 2 && ticks <= 50 * SAAM_RELEASE_SECONDS + 1, "ends after %d s (%d ticks)",
               SAAM_RELEASE_SECONDS, ticks);
    }

    EXPECT(saam_audition(m, &g_sam, 0, A4, TONE_SLOT, 0) == SAAM_OK && saam_release(m) == 0, "release before any tick: cut");
    int free_slot = 0;
    for (int s = 1; s <= 64 && !free_slot; ++s)
        if (!sa_instrument_flags(&g_sam, s)) free_slot = s;
    EXPECT(saam_audition(m, &g_sam, 0, 48, free_slot, 0) == SAAM_ERR_IMAGE, "undefined slot %d rejected", free_slot);
    EXPECT(saam_audition(m, &g_sam, 0, 48, 0, 0) == SAAM_ERR_IMAGE && saam_audition(m, &g_sam, 0, 48, 65, 0) == SAAM_ERR_IMAGE,
           "slots 0 and 65 rejected");
    EXPECT(saam_audition(m, &g_sam, 0, 120, 1, 0) == SAAM_ERR_IMAGE && saam_audition(m, &g_sam, 0, -1, 1, 0) == SAAM_ERR_IMAGE,
           "notes 120 and -1 rejected");
    EXPECT(saam_audition(m, &g_sam, 6, 48, 1, 0) == SAAM_ERR_IMAGE && saam_audition(m, &g_sam, -1, 48, 1, 0) == SAAM_ERR_IMAGE &&
               saam_audition(m, &g_cms, 12, 48, 1, 0) == SAAM_ERR_IMAGE,
           "SAM channel 6, CMS channel 12 and -1 rejected");
    EXPECT(saam_audition(m, &g_sam, 0, 48, 1, 128) == SAAM_ERR_IMAGE && saam_audition(m, &g_sam, 0, 48, 1, -129) == SAAM_ERR_IMAGE,
           "fine 128 and -129 rejected");
    EXPECT(saam_audition(m, &g_sam, 5, 119, 1, 127) == SAAM_OK && saam_audition(m, &g_sam, 0, 0, 1, -128) == SAAM_OK &&
               saam_audition(m, &g_cms, 11, 60, 1, 0) == SAAM_OK,
           "the limits themselves accepted");
    saam_destroy(m);
}

/* ---- mute ---------------------------------------------------------------------------------------------------- */

static void test_mute(void) {
    printf("mute\n");
    SaaMachine* m = saam_create(RATE);
    SaaMachine* a = saam_create(RATE);
    int mode, bad = 0, core_bad = 0;
    saam_start(m, &g_sam, 0, 0, SEEK_BUDGET, &mode);
    saam_start(a, &g_sam, 0, 0, SEEK_BUDGET, &mode);
    EXPECT(saam_mute(m, 2, 1) == SAAM_OK && saam_mute(m, 0, 1) == SAAM_OK, "mute SAA 1 and SAA 3");
    for (int k = 1; k <= SAA_REF_TICKS; ++k) {
        saam_tick(m, g_out);
        saam_tick(a, g_out);
        if (!same(saam_player(m), &REF_SAM, k)) ++bad;
        if (memcmp(saam_core(m, 0), saam_core(a, 0), sizeof(SacCore))) ++core_bad;
    }
    EXPECT(bad == 0, "muted channels leave the player's writes unchanged (%d differ)", bad);
    EXPECT(core_bad == 0, "and the chip: core state = unmuted play's (%d differ)", core_bad);
    saam_start(m, &g_sam, 1, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 25; ++k) saam_tick(m, g_out);
    for (int c = 0; c < 6; ++c) saam_mute(m, c, 1);
    float* buf;
    int frames = render(m, 25, &buf);
    const double quiet = rms(buf + 2 * (RATE / 10), frames - RATE / 10);
    EXPECT(quiet < 0.0005, "all channels muted: rms %.6f after 100 ms", quiet);
    free(buf);
    for (int c = 0; c < 6; ++c) saam_mute(m, c, 0);
    frames = render(m, 25, &buf);
    EXPECT(rms(buf, frames) > 0.01, "unmuted: resumes mid-note (rms %.4f)", rms(buf, frames));
    free(buf);
    saam_start(m, &g_sam, 0, 0, SEEK_BUDGET, &mode);
    for (int c = 0; c < 6; ++c) saam_mute(m, c, 1);
    frames = render(m, 50, &buf);
    EXPECT(rms(buf, frames) < 0.0005, "muted before the first tick: rms %.6f", rms(buf, frames));
    free(buf);
    saam_start(m, &g_sam, 1, 17, SEEK_BUDGET, &mode);
    for (int c = 0; c < 6; ++c) saam_mute(m, c, 1);
    frames = render(m, 50, &buf);
    EXPECT(rms(buf + 2 * (RATE / 10), frames - RATE / 10) < 0.0005, "muted right after a seek: rms %.6f after 100 ms",
           rms(buf + 2 * (RATE / 10), frames - RATE / 10));
    free(buf);
    saam_start(m, &g_sam, 1, 0, SEEK_BUDGET, &mode);
    frames = render(m, 25, &buf);
    EXPECT(rms(buf, frames) > 0.01, "start clears mutes (rms %.4f)", rms(buf, frames));
    free(buf);
    /* only the muted lane: an audition on SAA 1 sounds with SAA 2 muted, not with SAA 1 muted */
    saam_audition(m, &g_sam, 0, A4, TONE_SLOT, 0);
    saam_mute(m, 1, 1);
    frames = render(m, 25, &buf);
    const double other = rms(buf + 2 * (RATE / 10), frames - RATE / 10);
    free(buf);
    saam_mute(m, 0, 1);
    frames = render(m, 25, &buf);
    const double self = rms(buf + 2 * (RATE / 10), frames - RATE / 10);
    free(buf);
    EXPECT(other > 0.01 && self < 0.0005, "a mute silences its own lane only (%.4f with SAA 2 muted, %.6f with SAA 1)", other, self);
    EXPECT(saam_mute(m, 6, 1) == SAAM_ERR_IMAGE && saam_mute(m, -1, 1) == SAAM_ERR_IMAGE, "SAM: channels 6 and -1 rejected");
    saam_start(m, &g_cms, 0, 0, SEEK_BUDGET, &mode);
    EXPECT(saam_mute(m, 11, 1) == SAAM_OK && saam_mute(m, 12, 1) == SAAM_ERR_IMAGE, "CMS: channel 11 accepted, 12 rejected");
    saam_destroy(m);
    saam_destroy(a);
}

/* ---- shared resources ---------------------------------------------------------------------------------------- */

static void test_shared(void) {
    printf("shared noise and envelope clocks\n");
    for (int cms = 0; cms < 2; ++cms) {
        const int chip = cms, base = 6 * chip;
        const char* name = cms ? "CMS chip B" : "SAM";
        /* lane 1: noise at clock 0 from row 0; lane 2: noise at clock 2 from row 2 */
        b_init(&g_tone, cms ? SA_VARIANT_CMS : SA_VARIANT_SAM, 1, 6);
        static const int8_t noise_only[] = {2};
        const int mix = b_env(&g_tone, noise_only, 1, -1, -1);
        b_inst(&g_tone, 1, 0, 0, 0xFF, 0, 0, -1, -1, -1, mix);
        b_inst(&g_tone, 2, 0, 2, 0xFF, 0, 0, -1, -1, -1, mix);
        static const uint8_t lane1[] = {0x03, 48, 1, 0x84};
        static const uint8_t lane2[] = {0x81, 0x03, 50, 2, 0x82};
        b_track(&g_tone, base + 0, lane1, (int)sizeof lane1);
        b_track(&g_tone, base + 1, lane2, (int)sizeof lane2);
        EXPECT(sa_load_image(&g_tone) == 0, "%s: noise image loads (%s)", name, g_tone.info.problem ? g_tone.info.problem : "ok");
        SaaMachine* m = saam_create(RATE);
        int mode;
        saam_start(m, &g_tone, 0, 0, SEEK_BUDGET, &mode);
        const SacCore* c = saam_core(m, chip);
        int modes[6];
        for (int k = 0; k < 6; ++k) {
            saam_tick(m, g_out);
            modes[k] = c->noise[0].mode;
        }
        EXPECT(modes[0] == 0 && modes[1] == 0 && modes[2] == 2 && modes[5] == 2 && c->ch[0].noise && c->ch[1].noise &&
                   saam_player(m)->ch[base].keyed,
               "%s: lane 2's claim sets the shared generator to its clock while lane 1 sounds (%d %d %d .. %d)", name, modes[0],
               modes[1], modes[2], modes[5]);
        if (cms) EXPECT(saam_core(m, 0)->noise[0].mode == 0 && !saam_core(m, 0)->ch[0].noise, "chip A untouched");
        saam_destroy(m);

        /* lane 3's internal envelope clocked by lane 2's tone (lanes 6 / 5 on the CMS's chip B) */
        const int clk = cms ? 4 : 1, env_lane = cms ? 5 : 2, e = cms ? 1 : 0;
        b_init(&g_tone, cms ? SA_VARIANT_CMS : SA_VARIANT_SAM, 255, 1);
        static const int8_t off[] = {0};
        const int silent = b_env(&g_tone, off, 1, -1, -1);
        b_inst(&g_tone, 1, 0, 0, 0xFF, 0x86, 0, -1, -1, -1, -1);
        b_inst(&g_tone, 2, 0, 0, 0xFF, 0, 0, -1, -1, -1, silent);
        static const uint8_t clock_row[] = {0x03, A4, 2};
        static const uint8_t buzz_row[] = {0x03, 45, 1};
        b_track(&g_tone, base + clk, clock_row, (int)sizeof clock_row);
        b_track(&g_tone, base + env_lane, buzz_row, (int)sizeof buzz_row);
        EXPECT(sa_load_image(&g_tone) == 0, "%s: envelope image loads (%s)", name, g_tone.info.problem ? g_tone.info.problem : "ok");
        m = saam_create(RATE);
        saam_start(m, &g_tone, 0, 0, SEEK_BUDGET, &mode);
        saam_tick(m, g_out);
        static SacCore copy;
        copy = *saam_core(m, chip);
        const int want_half = sac_half(sap_octave_offset(A4 * 64, cms) >> 8, sap_octave_offset(A4 * 64, cms) & 0xFF);
        int edges = 0, steps = 0, off_edge = 0;
        for (int i = 0; i < 8 * want_half + 7; ++i) {
            const int edge = copy.tone[clk].count == 1;
            const int pos = copy.env[e].pos + 16 * copy.env[e].phase;
            sac_render(&copy, 1, NULL, NULL);
            const int moved = copy.env[e].pos + 16 * copy.env[e].phase != pos;
            edges += edge;
            steps += moved;
            off_edge += moved != edge;
        }
        EXPECT(copy.tone[clk].half == want_half && copy.env[e].enabled && !copy.env[e].external && edges >= 8 && steps == edges &&
                   off_edge == 0,
               "%s: envelope %d steps exactly at the edges of tone %d (H %d: %d edges, %d steps)", name, e, clk, want_half, edges,
               steps);
        saam_destroy(m);
    }
}

/* ---- VGM ----------------------------------------------------------------------------------------------------- */

typedef struct VgmBatches {
    int n;                         /* batches (split at waits) */
    int start[MAX_VGM_BATCHES + 1];
    int writes;
    uint8_t chip[MAX_VGM_WRITES], reg[MAX_VGM_WRITES], val[MAX_VGM_WRITES];
    int loop_batch;                /* batch the loop point starts, -1 none */
    uint32_t samples, loop_samples;
} VgmBatches;

static VgmBatches g_vb;

/* Walks the command stream from 0x100: the reset batch (`reset` writes) as its own batch, then tick batches ending at
 * each wait; waits summed; the loop point at a batch start; only 0xBD writes (chip B only on the CMS). */
static int parse_vgm(const uint8_t* v, size_t size, int cms, uint32_t loop_offset, int reset, VgmBatches* vb) {
    size_t at = 0x100;
    uint32_t total = 0, at_loop = 0;
    int ok = 1, in_batch = 0;
    vb->n = 0;
    vb->writes = 0;
    vb->loop_batch = -1;
    vb->start[0] = 0;
    while (at < size) {
        if (loop_offset && at == loop_offset) {
            if (in_batch) ok = 0; /* the loop point must start a batch */
            vb->loop_batch = vb->n;
            at_loop = total;
        }
        const uint8_t cmd = v[at];
        if (cmd == 0xBD) {
            if (vb->writes >= MAX_VGM_WRITES) return 0;
            if ((v[at + 1] & 0x80) && !cms) ok = 0;
            vb->chip[vb->writes] = (uint8_t)(v[at + 1] >> 7);
            vb->reg[vb->writes] = (uint8_t)(v[at + 1] & 0x7F);
            vb->val[vb->writes] = v[at + 2];
            ++vb->writes;
            in_batch = 1;
            at += 3;
            if (vb->n == 0 && vb->writes == reset) {
                /* the reset batch ends here */
                vb->start[++vb->n] = vb->writes;
                in_batch = 0;
            }
            continue;
        }
        uint32_t wait = 0;
        if (cmd == 0x61) wait = v[at + 1] | v[at + 2] << 8, at += 3;
        else if (cmd == 0x62) wait = 735, ++at;
        else if (cmd == 0x63) wait = 882, ++at;
        else if (cmd >= 0x70 && cmd <= 0x7F) wait = (cmd & 15) + 1u, ++at;
        else if (cmd == 0x66) {
            ++at;
            break;
        } else
            return 0;
        total += wait;
        if (vb->n >= MAX_VGM_BATCHES) return 0;
        vb->start[++vb->n] = vb->writes;
        in_batch = 0;
    }
    vb->samples = total;
    vb->loop_samples = loop_offset ? total - at_loop : 0;
    return ok && at == size && (!loop_offset || vb->loop_batch >= 1);
}

/* Batch b of the expanded stream: the file's batches, then the loop's again and again. */
static int expanded(const VgmBatches* vb, int b) {
    if (b < vb->n) return b;
    if (vb->loop_batch < 0) return -1;
    const int len = vb->n - vb->loop_batch;
    return vb->loop_batch + (b - vb->n) % len;
}

static void check_vgm(const char* dir, const char* name, const SaaImage* image, size_t want_size, uint64_t want_fnv,
                      uint32_t want_ticks, uint32_t want_loop_offset, uint32_t want_loop_samples, uint32_t want_samples) {
    const int cms = image->info.variant == SA_VARIANT_CMS;
    static SaPlayer scratch[2];
    SaVgmInfo info;
    memset(&info, 0, sizeof info);
    EXPECT(sa_vgm_render(image, VGM_MAX_TICKS, scratch, NULL, 0, &info) == 0 && info.size == want_size,
           "%s: measuring reports %zu bytes (want %zu)", name, info.size, want_size);
    size_t cap = 1 << 22;
    uint8_t* buf = (uint8_t*)malloc(cap);
    const double t0 = now_us();
    const size_t size = sa_vgm_render(image, VGM_MAX_TICKS, scratch, buf, cap, &info);
    const double us = now_us() - t0;
    EXPECT(size == want_size && fnv(0xCBF29CE484222325ull, buf, size) == want_fnv,
           "%s: VGM bytes = the reference player's (%zu vs %zu bytes)", name, size, want_size);
    EXPECT(info.ticks == want_ticks && info.samples == want_samples && info.loop_offset == want_loop_offset &&
               info.loop_samples == want_loop_samples && info.size == size,
           "%s: VGM length and loop (%u ticks, loop at %u, %u of %u samples)", name, info.ticks, info.loop_offset, info.loop_samples,
           info.samples);
    const uint32_t clock = rd32(buf + 0xC8);
    EXPECT(size > 0x100 && !memcmp(buf, "Vgm ", 4) && rd32(buf + 4) == size - 4 && rd32(buf + 8) == 0x171 &&
               rd32(buf + 0x18) == info.samples && rd32(buf + 0x24) == (uint32_t)(cms ? 60 : 50) && rd32(buf + 0x34) == 0xCC &&
               rd32(buf + 0x20) == info.loop_samples &&
               (info.loop_offset ? rd32(buf + 0x1C) + 0x1C == info.loop_offset : rd32(buf + 0x1C) == 0),
           "%s: header", name);
    EXPECT(clock == (cms ? (SA_VGM_DUAL | (uint32_t)SAC_CLOCK_CMS) : (uint32_t)SAC_CLOCK_SAM) &&
               clock == (cms ? 0x40000000u | 7159090u : 8000000u),
           "%s: SAA1099 clock 0x%08X (%s)", name, clock, cms ? "7159090, bit 30 = two chips" : "8000000");
    int others = 0;
    for (int at = 0x0C; at < 0x100; at += 4)
        if (at != 0x14 && at != 0x18 && at != 0x1C && at != 0x20 && at != 0x24 && at != 0x34 && at != 0xC8 && rd32(buf + at)) ++others;
    EXPECT(others == 0, "%s: no other header field set (%d)", name, others);
    const int reset = 22 * image->info.chips;
    const int parsed = size > 0x100 && parse_vgm(buf, size, cms, info.loop_offset, reset, &g_vb);
    EXPECT(parsed && g_vb.samples == info.samples && g_vb.loop_samples == info.loop_samples && g_vb.n == (int)info.ticks + 1,
           "%s: the stream parses: the reset batch and %d tick batches, waits sum to %u samples (loop %u), the loop at a tick",
           name, g_vb.n - 1, g_vb.samples, g_vb.loop_samples);
    if (parsed && info.loop_offset) {
        /* the loop expanded over three passes = a fresh player's writes, tick by tick */
        SaPlayer* p = &scratch[0];
        sap_reset(p, image);
        int bad = 0, total = g_vb.n + 2 * (g_vb.n - g_vb.loop_batch);
        for (int b = 0; b < total; ++b) {
            if (b > 0) sap_tick(p, image);
            const int e = expanded(&g_vb, b);
            const int count = g_vb.start[e + 1] - g_vb.start[e];
            int same_batch = count == p->writes;
            for (int i = 0; same_batch && i < count; ++i) {
                const int j = g_vb.start[e] + i;
                same_batch = g_vb.chip[j] == p->write[i].chip && g_vb.reg[j] == p->write[i].reg && g_vb.val[j] == p->write[i].val;
            }
            if (!same_batch) {
                if (!bad) printf("  %s: expanded batch %d (file batch %d) differs from fresh play\n", name, b, e);
                ++bad;
            }
        }
        EXPECT(bad == 0, "%s: the loop expanded over three passes = a fresh player's writes (%d ticks, %d differ)", name, total - 1, bad);
    }
    EXPECT(sa_vgm_render(image, VGM_MAX_TICKS, scratch, buf, 1000, &info) == 0 && info.size == want_size,
           "%s: too small a buffer reports 0 and the size needed", name);
    char file[64];
    snprintf(file, sizeof file, "%s.vgm", name);
    write_file(dir, file, buf, size);
    snprintf(file, sizeof file, "%s.bin", name); /* the image, for tools/audio/saa_reference_check.py */
    write_file(dir, file, image->bytes, sizeof image->bytes);
    printf("  %s: %zu bytes, %d writes, %u ticks, loop %u samples (%.2f s), rendered in %.0f us\n", name, size, g_vb.writes,
           (unsigned)want_ticks, (unsigned)want_loop_samples, want_loop_samples / 44100.0, us);
    free(buf);
}

static void test_vgm(const char* dir) {
    printf("VGM export\n");
    check_vgm(dir, "saa_sam_song", &g_sam, SAA_SAM_REF_VGM_SIZE, SAA_SAM_REF_VGM_FNV, SAA_SAM_REF_VGM_TICKS,
              SAA_SAM_REF_VGM_LOOP_OFFSET, SAA_SAM_REF_VGM_LOOP_SAMPLES, SAA_SAM_REF_VGM_SAMPLES);
    check_vgm(dir, "saa_cms_song", &g_cms, SAA_CMS_REF_VGM_SIZE, SAA_CMS_REF_VGM_FNV, SAA_CMS_REF_VGM_TICKS,
              SAA_CMS_REF_VGM_LOOP_OFFSET, SAA_CMS_REF_VGM_LOOP_SAMPLES, SAA_CMS_REF_VGM_SAMPLES);
    /* sap_same_state: equal for two fresh players, different one tick later, the write buffer ignored */
    static SaPlayer a, b;
    sap_reset(&a, &g_sam);
    sap_reset(&b, &g_sam);
    b.writes = 0;
    int eq0 = sap_same_state(&a, &b);
    sap_tick(&b, &g_sam);
    EXPECT(eq0 && !sap_same_state(&a, &b), "sap_same_state: fresh players equal, a tick apart not");
}

/* GD3 strings decoded back to UTF-8 (BMP + surrogate pairs); returns the end of the tag's strings. */
static const uint8_t* gd3_fields(const uint8_t* s, char fields[11][64]) {
    for (int f = 0; f < 11; ++f) {
        int n = 0;
        for (;;) {
            uint32_t c = s[0] | s[1] << 8;
            s += 2;
            if (!c) break;
            if (c >= 0xD800 && c < 0xDC00) {
                c = 0x10000 + ((c - 0xD800) << 10) + ((s[0] | s[1] << 8) - 0xDC00);
                s += 2;
            }
            if (n > 58) continue;
            if (c < 0x80) fields[f][n++] = (char)c;
            else if (c < 0x800) fields[f][n++] = (char)(0xC0 | c >> 6), fields[f][n++] = (char)(0x80 | (c & 0x3F));
            else if (c < 0x10000)
                fields[f][n++] = (char)(0xE0 | c >> 12), fields[f][n++] = (char)(0x80 | (c >> 6 & 0x3F)),
                fields[f][n++] = (char)(0x80 | (c & 0x3F));
            else
                fields[f][n++] = (char)(0xF0 | c >> 18), fields[f][n++] = (char)(0x80 | (c >> 12 & 0x3F)),
                fields[f][n++] = (char)(0x80 | (c >> 6 & 0x3F)), fields[f][n++] = (char)(0x80 | (c & 0x3F));
        }
        fields[f][n] = 0;
    }
    return s;
}

/* The hosts' entry point (saa_export.h): the reference VGM plus a GD3 tag. */
static void export_one(const SaaImage* image, size_t ref_size, uint64_t ref_fnv, const char* system) {
    size_t size = 0;
    const char* problem = NULL;
    const char* title = "Thor \xC3\x96pening \xF0\x9F\x8E\xB9"; /* 2-byte and 4-byte UTF-8 */
    uint8_t* vgm = sa_export_vgm(image->bytes, sizeof image->bytes, VGM_MAX_TICKS, title, "Esko", "2026/11/08", &size, &problem);
    EXPECT(vgm && !problem, "%s: renders (%s)", system, problem ? problem : "ok");
    if (!vgm) return;
    const uint32_t gd3_at = 0x14 + rd32(vgm + 0x14);
    const uint32_t eof = 4 + rd32(vgm + 4);
    EXPECT(gd3_at == ref_size && eof == size, "%s: GD3 after the reference data (at %u), EOF offset = size %zu", system, gd3_at, size);
    if (gd3_at != ref_size || size < ref_size + 12) {
        sa_export_free(vgm);
        return;
    }
    uint8_t* body = (uint8_t*)malloc(ref_size);
    memcpy(body, vgm, ref_size);
    const uint32_t ref_eof = (uint32_t)ref_size - 4;
    memcpy(body + 4, &ref_eof, 4);
    memset(body + 0x14, 0, 4);
    EXPECT(fnv(0xCBF29CE484222325ull, body, ref_size) == ref_fnv, "%s: the data = the reference player's VGM (header 0x04/0x14 aside)",
           system);
    free(body);
    const uint8_t* g = vgm + gd3_at;
    const uint32_t len = rd32(g + 8);
    EXPECT(!memcmp(g, "Gd3 ", 4) && rd32(g + 4) == 0x100 && gd3_at + 12 + len == size, "%s: GD3 1.00 header, length %u", system, len);
    char fields[11][64];
    const uint8_t* end = gd3_fields(g + 12, fields);
    EXPECT(end == vgm + size, "%s: 11 strings fill the tag", system);
    EXPECT(!strcmp(fields[0], title) && !strcmp(fields[4], system) && !strcmp(fields[6], "Esko") &&
               !strcmp(fields[8], "2026/11/08") && !strcmp(fields[9], SA_GD3_CONVERTER) && !fields[2][0] && !fields[10][0],
           "%s: GD3 fields: '%s', '%s', '%s', '%s', '%s'", system, fields[0], fields[4], fields[6], fields[8], fields[9]);
    sa_export_free(vgm);
    vgm = sa_export_vgm(image->bytes, sizeof image->bytes, 100, NULL, NULL, NULL, &size, &problem);
    EXPECT(vgm && rd32(vgm + 0x1C) == 0 && rd32(vgm + 0x20) == 0 && size > 0x100, "%s: tick limit before the loop closes: no loop",
           system);
    if (vgm) {
        const uint32_t at = 0x14 + rd32(vgm + 0x14);
        char none[11][64];
        gd3_fields(vgm + at + 12, none);
        EXPECT(strlen(none[8]) == 10 && none[8][4] == '/' && none[8][7] == '/' && !strncmp(none[8], "20", 2) && !none[0][0] &&
                   !strcmp(none[4], system),
               "%s: default date today: %s", system, none[8]);
    }
    sa_export_free(vgm);
}

static void test_export(void) {
    printf("VGM export entry point\n");
    export_one(&g_sam, SAA_SAM_REF_VGM_SIZE, SAA_SAM_REF_VGM_FNV, SA_GD3_SYSTEM_SAM);
    export_one(&g_cms, SAA_CMS_REF_VGM_SIZE, SAA_CMS_REF_VGM_FNV, SA_GD3_SYSTEM_CMS);
    EXPECT(!strcmp(SA_GD3_SYSTEM_SAM, "SAM Coup\xC3\xA9 (Thor Tracker)") &&
               !strcmp(SA_GD3_SYSTEM_CMS, "Creative Music System (Thor Tracker)") && !strcmp(SA_GD3_CONVERTER, "Thor Tracker"),
           "GD3 system and converter names");
    size_t size = 1;
    const char* problem = NULL;
    memcpy(g_bad.bytes, g_sam.bytes, sizeof g_bad.bytes);
    g_bad.bytes[SA_H_MULTISPEED] = 9;
    uint8_t* vgm = sa_export_vgm(g_bad.bytes, sizeof g_bad.bytes, VGM_MAX_TICKS, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && size == 0 && problem && strstr(problem, "multispeed"), "bad image: NULL, '%s'", problem ? problem : "");
    memcpy(g_bad.bytes, g_sam.bytes, sizeof g_bad.bytes);
    g_bad.bytes[SAA_OFFSET_NOTE] = 120;
    vgm = sa_export_vgm(g_bad.bytes, sizeof g_bad.bytes, VGM_MAX_TICKS, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && problem && strstr(problem, "note"), "bad note: '%s'", problem ? problem : "");
    vgm = sa_export_vgm(g_sam.bytes, 100, VGM_MAX_TICKS, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && problem && !strcmp(problem, "image size"), "short image: '%s'", problem ? problem : "");
}

/* ---- the ops tables ------------------------------------------------------------------------------------------ */

static ChipImage* ops_load(const ChipEngineOps* ops, const SaaImage* image, int* rc) {
    ChipImage* storage = (ChipImage*)calloc(1, ops->image_storage);
    *rc = ops->load_image(storage, image->bytes, ops->image_size);
    return storage;
}

static int ops_follow(const ChipEngineOps* ops, ChipMachine* m, const Ref* ref, int k, int ticks) {
    float out[CHIP_MAX_TICK_FRAMES * 2];
    int bad = 0;
    for (int j = 0; j < ticks && k + j <= SAA_REF_TICKS; ++j) {
        int o, r, t;
        ops->position(m, &o, &r, &t);
        if (o != ref->pos[3 * (k + j)] || r != ref->pos[3 * (k + j) + 1] || t != ref->pos[3 * (k + j) + 2]) ++bad;
        ops->tick(m, out);
    }
    return bad;
}

static void test_ops_for(const char* id, const char* other_id, const SaaImage* song, const SaaImage* other_song, const Ref* ref,
                         const SaaImage* tempo, const SaaImage* x2) {
    printf("ops table \"%s\"\n", id);
    const ChipEngineOps* ops = chip_engine_ops(id);
    const ChipEngineOps* other_ops = chip_engine_ops(other_id);
    EXPECT(ops && !strcmp(ops->system_id, id) && other_ops, "registry");
    if (!ops || !other_ops) return;
    const int cms = song->info.variant == SA_VARIANT_CMS;
    const int rate = cms ? SAAM_RATE_CMS : SAAM_RATE_SAM, per_tick = RATE / rate, channels = song->info.channels;
    EXPECT(ops->image_size == SA_IMAGE_SIZE && ops->image_storage == sizeof(SaaImage), "sizes");
    EXPECT(ops->create(CHIP_MAX_SAMPLE_RATE + 1) == NULL, "rate above the maximum rejected");
    int rc;
    ChipImage* image = ops_load(ops, song, &rc);
    EXPECT(rc == CHIP_OK, "song loads");
    ChipImage* scratch = (ChipImage*)calloc(1, ops->image_storage);
    EXPECT(ops->load_image(scratch, song->bytes, ops->image_size - 1) == CHIP_ERR_IMAGE, "wrong size rejected");
    memcpy(g_bad.bytes, song->bytes, sizeof g_bad.bytes);
    g_bad.bytes[0] = 'X';
    EXPECT(ops->load_image(scratch, g_bad.bytes, ops->image_size) == CHIP_ERR_IMAGE, "invalid rejected");
    EXPECT(ops->load_image(scratch, other_song->bytes, ops->image_size) == CHIP_ERR_IMAGE, "the other machine's image rejected");
    ChipImage* wrong = ops_load(other_ops, other_song, &rc); /* loaded by the other machine's ops */
    EXPECT(rc == CHIP_OK, "the other machine's image loads there");
    ChipImage* tempo_image = tempo ? ops_load(ops, tempo, &rc) : NULL;
    if (tempo) EXPECT(rc == CHIP_OK, "tempo image loads");
    ChipImage* fast = x2 ? ops_load(ops, x2, &rc) : NULL;
    if (x2) EXPECT(rc == CHIP_OK, "x2 image loads");

    ChipMachine* m = ops->create(RATE);
    int mode = -1, o, r, t;
    const int k = ref_row_start(ref, 1, 4);
    EXPECT(k > 0 && k + rate <= SAA_REF_TICKS, "1/4 is in the reference");
    EXPECT(ops->start(m, image, 1, 4, SEEK_BUDGET, &mode) == CHIP_OK && mode == CHIP_SEEK_FROM_START, "start 1/4");
    ops->position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 4 && t == 0, "position 1/4/0");
    float out[CHIP_MAX_TICK_FRAMES * 2];
    int frames = 0, n = 0, n_lo = 1 << 30, n_hi = 0;
    for (int j = 0; j < rate; ++j) {
        frames += (n = ops->tick(m, out));
        if (n < n_lo) n_lo = n;
        if (n > n_hi) n_hi = n;
    }
    EXPECT(frames >= RATE - 2 && frames <= RATE + 2 && n_lo >= per_tick - 1 && n_hi <= per_tick + 1,
           "48 kHz: %d ticks = 1 s (%d frames, %d..%d per tick)", rate, frames, n_lo, n_hi);
    ops->position(m, &o, &r, &t);
    if (k > 0 && k + rate <= SAA_REF_TICKS)
        EXPECT(o == ref->pos[3 * (k + rate)] && r == ref->pos[3 * (k + rate) + 1] && t == ref->pos[3 * (k + rate) + 2],
               "%d ticks later = straight play (%d/%d/%d)", rate, o, r, t);
    /* apply_image: the other machine's image is ignored, this machine's taken */
    ops->start(m, image, 1, 4, SEEK_BUDGET, &mode);
    ops->apply_image(m, wrong);
    EXPECT(ops_follow(ops, m, ref, k, 120) == 0, "the other machine's image ignored: positions = the song's");
    if (tempo) {
        int d = -1;
        for (int j = 1; j <= SAA_REF_TICKS && d < 0; ++j)
            for (int i = 0; i < 3; ++i)
                if (ref->pos[3 * j + i] != REF_TEMPO.pos[3 * j + i]) d = j;
        EXPECT(d > 1, "the tempo edit changes the positions (batch %d)", d);
        if (d > 1) {
            const int s0 = row_start_of(ref, d), s1 = row_start_of(&REF_TEMPO, d), start = s0 < s1 ? s0 : s1;
            const int from = start > 3 ? start - 3 : 1;
            const int ro = ref->pos[3 * from], rr = ref->pos[3 * from + 1];
            const int kf = ref_row_start(ref, ro, rr);
            ops->start(m, image, ro, rr, SEEK_BUDGET, &mode);
            ops->apply_image(m, tempo_image);
            EXPECT(ops_follow(ops, m, &REF_TEMPO, kf, 120) == 0, "this machine's tempo image applied: positions = the tempo song's");
        }
    }
    EXPECT(ops->mute(m, 0, 1) == CHIP_OK && ops->mute(m, channels - 1, 1) == CHIP_OK && ops->mute(m, 0, 0) == CHIP_OK &&
               ops->mute(m, channels, 1) == CHIP_ERR_IMAGE,
           "mute");
    EXPECT(ops->audition(m, image, 0, A4, PLUCK_SLOT, 0) == CHIP_OK, "audition");
    n = ops->tick(m, out);
    EXPECT(n >= per_tick - 1 && n <= per_tick + 1, "an audition tick: %d frames", n);
    EXPECT(ops->release(m) == 1, "release phase");
    int ticks = 0;
    while (ops->tick(m, out) > 0 && ticks < 1000) ++ticks;
    EXPECT(ticks < rate * SAAM_RELEASE_SECONDS, "released note ends (%d ticks)", ticks);
    EXPECT(ops->audition(m, image, 0, 120, 1, 0) == CHIP_ERR_IMAGE && ops->audition(m, image, channels, 48, 1, 0) == CHIP_ERR_IMAGE,
           "audition limits");
    if (fast) {
        EXPECT(ops->start(m, fast, 0, 0, SEEK_BUDGET, &mode) == CHIP_OK, "x2 start");
        frames = 0;
        n_lo = 1 << 30, n_hi = 0;
        for (int j = 0; j < 2 * rate; ++j) {
            frames += (n = ops->tick(m, out));
            if (n < n_lo) n_lo = n;
            if (n > n_hi) n_hi = n;
        }
        EXPECT(frames >= RATE - 2 && frames <= RATE + 2 && n_lo >= per_tick / 2 - 1 && n_hi <= per_tick / 2 + 1,
               "x2: %d ticks = 1 s (%d frames, %d..%d per tick)", 2 * rate, frames, n_lo, n_hi);
    }
    ops->destroy(m);
    m = ops->create(96000);
    EXPECT(m != NULL, "96 kHz machine");
    if (m) {
        ops->start(m, image, 0, 0, SEEK_BUDGET, &mode);
        float big[CHIP_MAX_TICK_FRAMES * 2];
        frames = 0;
        n_lo = 1 << 30, n_hi = 0;
        for (int j = 0; j < rate; ++j) {
            frames += (n = ops->tick(m, big));
            if (n < n_lo) n_lo = n;
            if (n > n_hi) n_hi = n;
        }
        EXPECT(frames >= 96000 - 2 && frames <= 96000 + 2 && n_lo >= 96000 / rate - 1 && n_hi <= 96000 / rate + 1 &&
                   n_hi <= (int)SAAM_MAX_TICK_FRAMES && n_hi <= (int)CHIP_MAX_TICK_FRAMES,
               "96 kHz: %d ticks = 1 s (%d frames, %d..%d per tick, <= %u)", rate, frames, n_lo, n_hi, SAAM_MAX_TICK_FRAMES);
        ops->destroy(m);
    }
    free(image);
    free(scratch);
    free(wrong);
    free(tempo_image);
    free(fast);
}

static void test_ops(void) {
    test_ops_for("sam-saa1099", "cms-saa1099", &g_sam, &g_cms, &REF_SAM, &g_tempo, &g_x2);
    test_ops_for("cms-saa1099", "sam-saa1099", &g_cms, &g_sam, &REF_CMS, NULL, NULL);
}

/* ---- multispeed and costs ------------------------------------------------------------------------------------ */

static void test_multispeed(void) {
    printf("multispeed\n");
    SaaMachine* m = saam_create(RATE);
    int mode, o, r, t, frames = 0;
    uint64_t clocks = 0;
    saam_start(m, &g_sam, 0, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 50; ++k) {
        frames += saam_tick(m, g_out);
        clocks += saam_clocks(m);
    }
    EXPECT(clocks == SAC_CLOCK_SAM && saam_clock(m) == SAC_CLOCK_SAM, "SAM: 50 ticks = 8000000 chip clocks (got %llu)",
           (unsigned long long)clocks);
    saam_start(m, &g_x2, 0, 0, SEEK_BUDGET, &mode);
    clocks = 0;
    frames = 0;
    for (int k = 0; k < 100; ++k) {
        frames += saam_tick(m, g_out);
        clocks += saam_clocks(m);
    }
    saam_position(m, &o, &r, &t);
    EXPECT(clocks == SAC_CLOCK_SAM && saam_ticks(m) == 100, "100 ticks at x2 = 8000000 chip clocks (got %llu)", (unsigned long long)clocks);
    EXPECT(frames >= RATE - 2 && frames <= RATE + 2, "= 1 s of host frames (%d)", frames);
    const int k = 101;
    EXPECT(o == REF_SAM.pos[3 * k] && r == REF_SAM.pos[3 * k + 1] && t == REF_SAM.pos[3 * k + 2],
           "x2: 100 fast ticks step the rows of 100 song ticks (%d/%d/%d)", o, r, t);
    saam_start(m, &g_cms, 0, 0, SEEK_BUDGET, &mode);
    clocks = 0;
    uint32_t lo = 1u << 31, hi = 0;
    for (int k2 = 0; k2 < 60; ++k2) {
        saam_tick(m, g_out);
        clocks += saam_clocks(m);
        if (saam_clocks(m) < lo) lo = saam_clocks(m);
        if (saam_clocks(m) > hi) hi = saam_clocks(m);
    }
    EXPECT(clocks == SAC_CLOCK_CMS && lo == 119318 && hi == 119319 && saam_clock(m) == SAC_CLOCK_CMS,
           "CMS: 60 ticks = 7159090 clocks (119318 or 119319 each)");
    saam_destroy(m);
}

static void test_costs(void) {
    printf("costs\n");
    SaaMachine* m = saam_create(RATE);
    int mode;
    double t0 = now_us();
    saam_start(m, &g_sam, 3, 20, SEEK_BUDGET, &mode);
    const double seek = now_us() - t0;
    t0 = now_us();
    SaPlayer* p = (SaPlayer*)malloc(sizeof *p);
    sap_reset(p, &g_cms);
    for (int k = 0; k < SAAM_SEEK_TICKS; ++k) sap_tick(p, &g_cms);
    const double per = (now_us() - t0) / SAAM_SEEK_TICKS;
    free(p);
    static SacCore c;
    sac_reset(&c);
    sac_write(&c, SAC_REG_CONTROL, 0x01);
    for (int ch = 0; ch < 6; ++ch) {
        sac_write(&c, SAC_REG_AMP + ch, 0xFF);
        sac_write(&c, SAC_REG_OFFSET + ch, 227);
    }
    for (int k = 0; k < 3; ++k) sac_write(&c, SAC_REG_OCTAVE + k, 0x33);
    sac_write(&c, SAC_REG_TONE, 0x3F);
    sac_write(&c, SAC_REG_NOISE, 0x09);
    sac_write(&c, SAC_REG_NOISE_MODE, 0x00);
    t0 = now_us();
    for (int k = 0; k < 500; ++k) sac_advance(&c, 160000);
    const double adv = (now_us() - t0) / 500;
    t0 = now_us();
    for (int k = 0; k < 500; ++k) sac_render(&c, 160000, NULL, NULL);
    const double ren = (now_us() - t0) / 500;
    printf("  seek to 3/20: %.0f us; CMS player alone %.2f us per tick (%d ticks: %.1f ms); core per SAM tick, six A-4s and "
           "noise: advance %.2f us, render %.1f us\n",
           seek, per, SAAM_SEEK_TICKS, per * SAAM_SEEK_TICKS / 1000, adv, ren);
    saam_destroy(m);
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    EXPECT(load(&g_sam, saa_sam_image, sizeof saa_sam_image) == 0, "sam loads: %s", g_sam.info.problem);
    EXPECT(load(&g_cms, saa_cms_image, sizeof saa_cms_image) == 0, "cms loads: %s", g_cms.info.problem);
    EXPECT(load(&g_moved, saa_moved_image, sizeof saa_moved_image) == 0, "moved loads: %s", g_moved.info.problem);
    EXPECT(load(&g_edited, saa_edited_image, sizeof saa_edited_image) == 0, "edited loads: %s", g_edited.info.problem);
    EXPECT(load(&g_tempo, saa_tempo_image, sizeof saa_tempo_image) == 0, "tempo loads: %s", g_tempo.info.problem);
    EXPECT(load(&g_short, saa_short_image, sizeof saa_short_image) == 0, "short loads: %s", g_short.info.problem);
    EXPECT(load(&g_x2, saa_x2_image, sizeof saa_x2_image) == 0, "x2 loads: %s", g_x2.info.problem);
    EXPECT(load(&g_env_edit, saa_env_edit_image, sizeof saa_env_edit_image) == 0, "env_edit loads: %s", g_env_edit.info.problem);
    test_core();
    test_chip_trace(dir);
    test_pitch();
    test_images();
    test_reference(dir);
    test_audio(dir);
    test_loop();
    test_seek();
    test_live_edits();
    test_audition(dir);
    test_mute();
    test_shared();
    test_multispeed();
    test_vgm(dir);
    test_export();
    test_ops();
    test_costs();
    printf("SAA1099: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
