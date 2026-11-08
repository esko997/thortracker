/* Host regression test for the SCC systems ("msx-scc", "msx-scc-plus"): Thor Tracker's own player (scc/scc_player.c)
 * on our port of MAME's K051649 / K052539 (scc/scc_k051649.c), playing the hand-built test song
 * tools/audio/scc/scc_song.py (scc_test_song.h, from tools/audio/prepare_scc.py), plus small images built here.
 *
 * Checks: the core against its documented model (power-on state; output = (sample x volume) >> 4 with an arithmetic
 * shift; effective periods <= 8 halting the channel; the latch reloading one clock after a period write and the next
 * step E + 1 clocks later; volume, key and wave changes heard at the next step; test bit 5 resetting the position, bit
 * 6 freezing every wave RAM on both chips, bit 7 freezing RAM 3 on the SCC only, bits 0/1 choosing the effective
 * period at the period write; channels 4 and 5 sharing RAM 3 on the SCC, five RAMs on the SCC+; writes to the other
 * chip's wave port ignored; sck_advance, sck_render and a clock-by-clock model of MAME's loop leaving memcmp-equal
 * state and the same level events after pseudo-random write streams); the period formula and table (A-4 = P 253);
 * image validation (single-byte corruptions and small constructed images, each rejected with its reason and byte);
 * register writes and positions identical to the independent Python reference player (tools/audio/scc/scc_ref.py) in
 * each of SCC_REF_TICKS ticks for the song on both chips, a note edit and a tempo edit, and the reset batch; the
 * core's registers following the player's shadow; deterministic, non-silent, unclipped output, flat tick cost; loop
 * passes; seeks (positions, writes, core state memcmp-equal to straight play's at the target, output within 1/32768,
 * bit-identical inside the pre-roll) and the order fallback; live edits (re-laid image, note edit, tempo edit, cut
 * song, multispeed, a wave edited while it sounds, an instrument deleted under a sounding note); audition (A-4
 * measured 440.40 Hz on P 253, fine pitch, releases, held notes, the 10 s release bound, argument limits); mute; the
 * SCC's shared wave RAM against the SCC+'s five; VGM bytes identical to the reference's for both chips; the hosts' VGM
 * entry point and its GD3 tag; both ops tables as the hosts drive them, including the variant check; costs.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir]
 * Writes <dir>/scc_song.wav and scc_plus_song.wav (48 kHz stereo, 30 s), scc_tone.wav (the A-4 audition, 2 s, and
 * its release), scc_trace.txt (per-tick player writes), scc_chip_trace.txt (a scripted register sequence and the
 * core's level events), scc_song.vgm and scc_plus_song.vgm (VGM 1.71), scc_song.bin and scc_plus_song.bin (the
 * images): the inputs of tools/audio/scc_reference_check.py. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "chip_engine.h"
#include "generated/scc_tables.h"
#include "scc_export.h"
#include "scc_k051649.h"
#include "scc_machine.h"
#include "scc_test_song.h"
#include "scc_vgm.h"

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
#define A4_HZ (3579545.0 / (32.0 * 254))  /* P 253: 440.397 Hz */
#define AS4_HZ (3579545.0 / (32.0 * 240)) /* P 239: 466.087 Hz */
#define SINE_SLOT 1    /* scc_song.py: wave envelope [1] (wave 1 = a sine), no other envelopes, no RESTART */
#define PLUCK_SLOT 2   /* a volume envelope with a release point, ending at 0 */
#define RESTART_SLOT 3 /* RESTART */
#define SEEK_BUDGET SCM_SEEK_TICKS
#define VGM_MAX_TICKS 36000
#define MAX_EVENTS 131072

static float g_out[SCM_MAX_TICK_FRAMES * 2];
static SccImage g_song, g_plus, g_moved, g_edited, g_tempo, g_short, g_x2, g_wave_edit;
static SccImage g_bad, g_work, g_tempo_plus, g_x2_plus, g_tone;

typedef struct Ref {
    const int* offsets;
    const unsigned char* ports;
    const unsigned char* regs;
    const unsigned char* vals;
    const short* pos;
} Ref;

#define REF(name) \
    {scc_ref_##name##_offsets, scc_ref_##name##_ports, scc_ref_##name##_regs, scc_ref_##name##_vals, scc_ref_##name##_pos}
static const Ref REF_SONG = REF(song);
static const Ref REF_PLUS = REF(plus);
static const Ref REF_EDITED = REF(edited);
static const Ref REF_TEMPO = REF(tempo);

/* ---- helpers ------------------------------------------------------------------------------------------------- */

static double now_us(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e6 + t.tv_nsec / 1e3;
}

static double cpu_us(void) {
    struct timespec t;
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &t);
    return t.tv_sec * 1e6 + t.tv_nsec / 1e3;
}

static uint64_t fnv(uint64_t h, const void* data, size_t n) {
    const uint8_t* p = (const uint8_t*)data;
    for (size_t i = 0; i < n; ++i) h = (h ^ p[i]) * 0x100000001B3ull;
    return h;
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

/* Frequency from rising zero crossings of the left channel of interleaved stereo (mean removed). */
static double zero_cross_hz(const float* x, int n) {
    double mean = 0;
    for (int i = 0; i < n; ++i) mean += x[2 * i];
    mean /= n;
    int count = 0;
    double first_t = -1, last_t = 0;
    for (int i = 1; i < n; ++i) {
        double a = x[2 * (i - 1)] - mean, b = x[2 * i] - mean;
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

/* Whether both stereo channels carry the same (mono) signal. */
static int mono(const float* x, int frames) {
    for (int i = 0; i < frames; ++i)
        if (x[2 * i] != x[2 * i + 1]) return 0;
    return 1;
}

static int render(SccMachine* m, int ticks, float** buf) {
    float* out = (float*)malloc((size_t)ticks * SCM_MAX_TICK_FRAMES * 2 * sizeof(float));
    int frames = 0;
    for (int t = 0; t < ticks; ++t) {
        int n = scm_tick(m, out + 2 * frames);
        if (n <= 0) break;
        frames += n;
    }
    *buf = out;
    return frames;
}

/* The generated images are stored up to their last non-zero byte: zero-padded to the image size here. */
static int load(SccImage* image, const uint8_t* bytes, size_t used) {
    memset(image, 0, sizeof *image);
    memcpy(image->bytes, bytes, used);
    return sc_load_image(image);
}

/* A copy of `in` as the other chip (variant byte flipped), loaded. */
static int other_variant(SccImage* out, const SccImage* in) {
    memcpy(out, in, sizeof *out);
    out->bytes[SC_H_VARIANT] ^= 1;
    return sc_load_image(out);
}

static int ref_count(const Ref* r, int k) { return r->offsets[k + 1] - r->offsets[k]; }

/* Whether the player's last batch equals reference batch k (0 = the reset batch, k >= 1 = tick k - 1). */
static int same(const ScPlayer* p, const Ref* r, int k) {
    if (p->writes != ref_count(r, k)) return 0;
    for (int i = 0; i < p->writes; ++i) {
        const int j = r->offsets[k] + i;
        if (p->write[i].port != r->ports[j] || p->write[i].reg != r->regs[j] || p->write[i].val != r->vals[j]) return 0;
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
        if (a->ports[x] != b->ports[y] || a->regs[x] != b->regs[y] || a->vals[x] != b->vals[y]) return 0;
    }
    return 1;
}

/* First batch where two references differ, or -1. */
static int first_difference(const Ref* a, const Ref* b) {
    for (int k = 0; k <= SCC_REF_TICKS; ++k)
        if (!ref_batch_equal(a, b, k)) return k;
    return -1;
}

/* The batch that starts the row batch k belongs to. */
static int row_start_of(const Ref* r, int k) { return k - r->pos[3 * k + 2]; }

/* First batch whose position is (order, row, 0), or -1. */
static int ref_row_start(const Ref* r, int order, int row) {
    for (int k = 1; k <= SCC_REF_TICKS; ++k)
        if (r->pos[3 * k] == order && r->pos[3 * k + 1] == row && r->pos[3 * k + 2] == 0) return k;
    return -1;
}

/* Ticks of the row starting at batch k. */
static int ref_row_length(const Ref* r, int k) {
    int n = 1;
    while (k + n <= SCC_REF_TICKS && r->pos[3 * (k + n) + 2] != 0) ++n;
    return n;
}

static int pos_is(const SccMachine* m, const Ref* r, int k) {
    int o, w, t;
    scm_position(m, &o, &w, &t);
    return o == r->pos[3 * k] && w == r->pos[3 * k + 1] && t == r->pos[3 * k + 2];
}

/* The chip holds what the player's shadow says it holds. */
static int core_matches_shadow(const SccCore* c, const ScPlayer* p) {
    if (c->test != p->sh_test || (c->key & 0x1F) != (p->sh_key & 0x1F)) return 0;
    for (int ch = 0; ch < SCK_CHANNELS; ++ch) {
        const int period = p->sh_period[2 * ch] | (p->sh_period[2 * ch + 1] & 0x0F) << 8;
        if (c->ch[ch].period != period || c->ch[ch].volume != (p->sh_volume[ch] & 0x0F)) return 0;
    }
    const int rams = c->plus ? 5 : 4;
    for (int k = 0; k < rams; ++k)
        if (memcmp(c->ram[k], p->sh_ram[k], SCK_WAVE_BYTES)) return 0;
    return 1;
}

/* The reset batch as specified: test, key, the ten period bytes, five volumes, every wave RAM byte (register order),
 * all 0, in that order. */
static int reset_batch_ok(const ScPlayer* p, int plus) {
    ScWrite want[SCP_MAX_WRITES];
    int n = 0;
    want[n++] = (ScWrite){SCK_PORT_TEST, 0, 0};
    want[n++] = (ScWrite){SCK_PORT_KEY, 0, 0};
    for (int r = 0; r < 2 * SCK_CHANNELS; ++r) want[n++] = (ScWrite){SCK_PORT_PERIOD, (uint8_t)r, 0};
    for (int c = 0; c < SCK_CHANNELS; ++c) want[n++] = (ScWrite){SCK_PORT_VOLUME, (uint8_t)c, 0};
    for (int r = 0; r < (plus ? 0xA0 : 0x80); ++r) want[n++] = (ScWrite){plus ? SCK_PORT_WAVE_PLUS : SCK_PORT_WAVE, (uint8_t)r, 0};
    if (p->writes != n || n != (plus ? 177 : 145)) return 0;
    for (int i = 0; i < n; ++i)
        if (p->write[i].port != want[i].port || p->write[i].reg != want[i].reg || p->write[i].val != want[i].val) return 0;
    return 1;
}

static void trace_batch(FILE* f, int index, int o, int r, int t, const ScPlayer* p) {
    if (!f) return;
    fprintf(f, "T %d %d %d %d :", index, o, r, t);
    for (int i = 0; i < p->writes; ++i) fprintf(f, " %d:%02x=%02x", p->write[i].port, p->write[i].reg, p->write[i].val);
    fputc('\n', f);
}

static void put16(uint8_t* p, int v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
}

/* ---- the core ------------------------------------------------------------------------------------------------ */

typedef struct Event {
    uint32_t clock;
    int16_t channel, level;
} Event;

typedef struct Events {
    int n, overflow;
    Event e[MAX_EVENTS];
} Events;

static Events g_ev_a, g_ev_b;

static void collect(void* ctx, uint32_t clock, int channel, int level) {
    Events* ev = (Events*)ctx;
    if (ev->n >= MAX_EVENTS) {
        ev->overflow = 1;
        return;
    }
    ev->e[ev->n].clock = clock;
    ev->e[ev->n].channel = (int16_t)channel;
    ev->e[ev->n].level = (int16_t)level;
    ++ev->n;
}

static void clear(Events* ev) { ev->n = ev->overflow = 0; }

/* MAME's sound_stream_update loop, one clock at a time, on the state scc_k051649.h documents: with E > 8 the counter
 * counts; past E the position advances and the counter restarts; at counter 0 the latch reloads. Level events as
 * sck_render reports them (clock offset, channel, new level). */
static void model_run(SccCore* c, uint32_t clocks, Events* ev) {
    for (uint32_t i = 0; i < clocks; ++i)
        for (int ch = 0; ch < SCK_CHANNELS; ++ch) {
            SccCoreChannel* v = &c->ch[ch];
            if (v->effective <= SCK_HALT_PERIOD) continue;
            const int before = sck_level(c, ch);
            if (++v->clk > (int32_t)v->effective) {
                v->pos = (uint8_t)((v->pos + 1) & 31);
                v->clk = 0;
            }
            if (v->clk == 0) v->latch = (int16_t)((c->key >> ch & 1 ? c->ram[sck_ram_of(c, ch)][v->pos] : 0) * v->volume);
            const int after = sck_level(c, ch);
            if (ev && after != before) collect(ev, i, ch, after);
        }
}

/* The same level events per channel, in the same order (sck_render orders events per channel only). */
static int same_events(const Events* a, const Events* b) {
    if (a->overflow || b->overflow) return 0;
    for (int ch = 0; ch < SCK_CHANNELS; ++ch) {
        int i = 0, j = 0;
        for (;;) {
            while (i < a->n && a->e[i].channel != ch) ++i;
            while (j < b->n && b->e[j].channel != ch) ++j;
            if (i >= a->n || j >= b->n) {
                if (i < a->n || j < b->n) return 0;
                break;
            }
            if (a->e[i].clock != b->e[j].clock || a->e[i].level != b->e[j].level) return 0;
            ++i;
            ++j;
        }
    }
    return 1;
}

/* Events inside the render, increasing per channel, each a real change of that channel's level. */
static int well_formed(const Events* ev, uint32_t clocks, const int* start_level) {
    int last_clock[SCK_CHANNELS], level[SCK_CHANNELS];
    for (int ch = 0; ch < SCK_CHANNELS; ++ch) last_clock[ch] = -1, level[ch] = start_level[ch];
    for (int i = 0; i < ev->n; ++i) {
        const Event* e = &ev->e[i];
        if (e->channel < 0 || e->channel >= SCK_CHANNELS || e->clock >= clocks || (int)e->clock <= last_clock[e->channel] ||
            e->level == level[e->channel] || e->level < -120 || e->level > 119)
            return 0;
        last_clock[e->channel] = (int)e->clock;
        level[e->channel] = e->level;
    }
    return 1;
}

/* Events of channels a and b identical (clock and level). */
static int channels_alike(const Events* ev, int a, int b, int* count) {
    int i = 0, j = 0, n = 0;
    for (;;) {
        while (i < ev->n && ev->e[i].channel != a) ++i;
        while (j < ev->n && ev->e[j].channel != b) ++j;
        if (i >= ev->n || j >= ev->n) {
            *count = n;
            return i >= ev->n && j >= ev->n;
        }
        if (ev->e[i].clock != ev->e[j].clock || ev->e[i].level != ev->e[j].level) {
            *count = n;
            return 0;
        }
        ++n;
        ++i;
        ++j;
    }
}

static void fresh(SccCore* c, int plus) {
    memset(c, 0, sizeof *c);
    sck_reset(c, plus);
}

static void fill_wave(SccCore* c, int ram, const int8_t* w) {
    for (int i = 0; i < SCK_WAVE_BYTES; ++i)
        sck_write(c, c->plus ? SCK_PORT_WAVE_PLUS : SCK_PORT_WAVE, ram * SCK_WAVE_BYTES + i, w[i] & 0xFF);
}

static void set_period(SccCore* c, int ch, int period) {
    sck_write(c, SCK_PORT_PERIOD, 2 * ch, period & 0xFF);
    sck_write(c, SCK_PORT_PERIOD, 2 * ch + 1, period >> 8 & 0x0F);
}

/* floor(sample x volume / 16): the level the chip outputs. */
static int level_of(int sample, int volume) {
    const int v = sample * volume;
    return v >= 0 ? v / 16 : -((-v + 15) / 16);
}

/* Render clock offset of a running channel's next step (its counter >= 0). */
static int next_step(const SccCore* c, int ch) { return c->ch[ch].effective - c->ch[ch].clk; }

static int8_t g_ramp[32], g_sine[32], g_saw[32], g_square[32], g_triangle[32];

static void make_waves(void) {
    for (int i = 0; i < 32; ++i) {
        g_ramp[i] = (int8_t)(i * 8 - 128);
        g_sine[i] = (int8_t)floor(127 * sin(2 * M_PI * i / 32) + 0.5);
        g_saw[i] = (int8_t)(i * 8 - 124);
        g_square[i] = (int8_t)(i < 16 ? 100 : -100);
        g_triangle[i] = (int8_t)(i < 16 ? -120 + 16 * i : 120 - 16 * (i - 16));
    }
}

static uint32_t g_rng = 1;
static uint32_t rnd(void) {
    g_rng ^= g_rng << 13;
    g_rng ^= g_rng >> 17;
    g_rng ^= g_rng << 5;
    return g_rng;
}

/* A random register write, weighted toward what matters: small periods around the halting limit, test bits. */
static void random_write(int plus, int* port, int* reg, int* val) {
    static const int tests[] = {0, 0, 0, 0, 0x20, 0x40, 0x80, 0x01, 0x02, 0x03, 0x21, 0xC0, -1};
    const uint32_t r = rnd() % 100;
    if (r < 28) {
        *port = plus ? SCK_PORT_WAVE_PLUS : SCK_PORT_WAVE;
        *reg = (int)(rnd() % (plus ? 0xA0 : 0x80));
        *val = (int)(rnd() & 0xFF);
    } else if (r < 32) { /* the other chip's wave port: ignored */
        *port = plus ? SCK_PORT_WAVE : SCK_PORT_WAVE_PLUS;
        *reg = (int)(rnd() % 0xA0);
        *val = (int)(rnd() & 0xFF);
    } else if (r < 58) {
        *port = SCK_PORT_PERIOD;
        *reg = (int)(rnd() % 12); /* 10 and 11 are ignored */
        if (*reg & 1) *val = rnd() % 3 == 0 ? 0 : (int)(rnd() & 0xFF); /* high bits beyond 11 must be dropped */
        else *val = rnd() % 3 == 0 ? (int)(rnd() % 12) : (int)(rnd() & 0xFF);
    } else if (r < 75) {
        *port = SCK_PORT_VOLUME;
        *reg = (int)(rnd() % SCK_CHANNELS);
        *val = (int)(rnd() & 0xFF);
    } else if (r < 88) {
        *port = SCK_PORT_KEY;
        *reg = 0;
        *val = (int)(rnd() & 0xFF);
    } else {
        *port = SCK_PORT_TEST;
        *reg = 0;
        const int t = tests[rnd() % (sizeof tests / sizeof tests[0])];
        *val = t < 0 ? (int)(rnd() & 0xFF) : t;
    }
}

/* sck_advance, sck_render and the clock-by-clock model from the same random write stream: memcmp-equal state after
 * every segment, the same events. Returns the segments that differed. */
static int random_streams(int plus, int segments, uint32_t seed, uint64_t* clocks) {
    static SccCore a, b, m;
    fresh(&a, plus);
    fresh(&b, plus);
    fresh(&m, plus);
    g_rng = seed;
    int bad = 0;
    *clocks = 0;
    for (int s = 0; s < segments; ++s) {
        const int writes = (int)(rnd() % 5);
        for (int w = 0; w < writes; ++w) {
            int port, reg, val;
            random_write(plus, &port, &reg, &val);
            sck_write(&a, port, reg, val);
            sck_write(&b, port, reg, val);
            sck_write(&m, port, reg, val);
        }
        const uint32_t n = rnd() % 8 == 0 ? 20000 + rnd() % 60000 : rnd() % 3000;
        int start[SCK_CHANNELS];
        for (int ch = 0; ch < SCK_CHANNELS; ++ch) start[ch] = sck_level(&b, ch);
        clear(&g_ev_a);
        clear(&g_ev_b);
        sck_advance(&a, n);
        sck_render(&b, n, collect, &g_ev_a);
        model_run(&m, n, &g_ev_b);
        *clocks += n;
        const int ab = !memcmp(&a, &b, sizeof a), bm = !memcmp(&b, &m, sizeof b), ev = same_events(&g_ev_a, &g_ev_b);
        const int ordered = well_formed(&g_ev_a, n, start);
        if (!ab || !bm || !ev || !ordered) {
            if (!bad)
                printf("  %s segment %d (%u clocks): advance %s render, render %s model, events %s, %s\n", plus ? "SCC+" : "SCC",
                       s, n, ab ? "=" : "!=", bm ? "=" : "!=", ev ? "equal" : "differ", ordered ? "ordered" : "malformed");
            ++bad;
            a = m; /* continue from the model's state */
            b = m;
        }
    }
    return bad;
}

static void test_core(void) {
    printf("core\n");
    static SccCore c, saved;
    Events* ev = &g_ev_a;
    for (int plus = 0; plus < 2; ++plus) {
        memset(&c, 0x55, sizeof c);
        sck_reset(&c, plus);
        int ok = c.plus == plus && c.test == 0 && (c.key & 0x1F) == 0;
        for (int ch = 0; ch < SCK_CHANNELS; ++ch)
            ok &= c.ch[ch].period == 0 && c.ch[ch].effective == 0 && c.ch[ch].clk == 0 && c.ch[ch].pos == 0 &&
                  c.ch[ch].volume == 15 && c.ch[ch].latch == 0;
        for (int k = 0; k < SCK_RAMS; ++k)
            for (int i = 0; i < SCK_WAVE_BYTES; ++i) ok &= c.ram[k][i] == 0;
        EXPECT(ok, "%s power-on: periods 0, volumes 15, keys off, test 0, RAM 0", plus ? "SCC+" : "SCC");
        EXPECT(sck_ram_of(&c, 4) == (plus ? 4 : 3) && sck_ram_of(&c, 3) == 3 && sck_ram_of(&c, 0) == 0,
               "%s channel 5 plays RAM %d", plus ? "SCC+" : "SCC", sck_ram_of(&c, 4));
    }

    /* Output: (sample x volume) >> 4, arithmetic. */
    static const int cases[][3] = {{127, 15, 119}, {-128, 15, -120}, {-1, 1, -1}, {1, 15, 0},   {-1, 15, -1}, {16, 1, 1},
                                   {-16, 1, -1},   {-17, 1, -2},     {15, 1, 0},  {100, 7, 43}, {-100, 7, -44}, {0, 15, 0}};
    int bad = 0;
    for (size_t i = 0; i < sizeof cases / sizeof cases[0]; ++i) {
        const int s = cases[i][0], v = cases[i][1], want = cases[i][2];
        int8_t w[32];
        memset(w, s, sizeof w);
        fresh(&c, 0);
        fill_wave(&c, 0, w);
        sck_write(&c, SCK_PORT_VOLUME, 0, v);
        sck_write(&c, SCK_PORT_KEY, 0, 0x01);
        set_period(&c, 0, 100);
        clear(ev);
        sck_render(&c, 1, collect, ev);
        if (c.ch[0].latch != s * v || sck_level(&c, 0) != want || level_of(s, v) != want) ++bad;
        if (want ? !(ev->n == 1 && ev->e[0].clock == 0 && ev->e[0].channel == 0 && ev->e[0].level == want) : ev->n != 0) ++bad;
    }
    EXPECT(bad == 0, "level = (sample x volume) >> 4, arithmetic: -1 x 1 = -1, -128 x 15 = -120, 127 x 15 = 119 (%d wrong)", bad);
    {
        int8_t w[32];
        memset(w, 127, sizeof w);
        fresh(&c, 0);
        fill_wave(&c, 0, w);
        sck_write(&c, SCK_PORT_VOLUME, 0, 15);
        set_period(&c, 0, 100);
        clear(ev);
        sck_render(&c, 1000, collect, ev);
        EXPECT(ev->n == 0 && c.ch[0].latch == 0, "key off: the latch reloads 0 (%d events)", ev->n);
    }

    /* Halting at E <= 8. */
    fresh(&c, 0);
    fill_wave(&c, 0, g_ramp);
    sck_write(&c, SCK_PORT_VOLUME, 0, 15);
    sck_write(&c, SCK_PORT_KEY, 0, 0x01);
    set_period(&c, 0, 8);
    clear(ev);
    sck_render(&c, 10000, collect, ev);
    EXPECT(ev->n == 0 && c.ch[0].pos == 0 && c.ch[0].latch == 0 && c.ch[0].clk == -1,
           "period 8 halts: no reload, no step in 10000 clocks (%d events, position %d, clk %d)", ev->n, c.ch[0].pos, c.ch[0].clk);
    set_period(&c, 0, 9);
    clear(ev);
    sck_render(&c, 100, collect, ev);
    int ok = ev->n == 10 && c.ch[0].pos == 9;
    for (int i = 0; ok && i < 10; ++i) ok = ev->e[i].clock == (uint32_t)(10 * i) && ev->e[i].level == level_of(g_ramp[i], 15);
    EXPECT(ok, "period 9 runs: the reload at once, then a step every 10 clocks (%d events, position %d)", ev->n, c.ch[0].pos);
    set_period(&c, 0, 8);
    saved = c;
    clear(ev);
    sck_render(&c, 50000, collect, ev);
    EXPECT(ev->n == 0 && !memcmp(&c, &saved, sizeof c) && sck_level(&c, 0) == level_of(g_ramp[9], 15),
           "halted mid-wave: position, latch and level frozen (%d events, position %d)", ev->n, c.ch[0].pos);
    sck_advance(&c, 1234567);
    EXPECT(!memcmp(&c, &saved, sizeof c), "halted: sck_advance changes nothing");
    set_period(&c, 0, 0);
    clear(ev);
    sck_render(&c, 3000, collect, ev);
    EXPECT(ev->n == 0 && c.ch[0].pos == 9, "period 0 halts too");

    /* The step restart one clock after a period write; volume, key and wave changes heard at the next step. */
    fresh(&c, 0);
    fill_wave(&c, 0, g_ramp);
    sck_write(&c, SCK_PORT_VOLUME, 0, 15);
    sck_write(&c, SCK_PORT_KEY, 0, 0x01);
    set_period(&c, 0, 50);
    EXPECT(c.ch[0].clk == -1 && c.ch[0].effective == 50, "a period write leaves the reload pending (clk %d)", c.ch[0].clk);
    clear(ev);
    sck_render(&c, 1, collect, ev);
    EXPECT(ev->n == 1 && ev->e[0].clock == 0 && ev->e[0].level == level_of(g_ramp[0], 15) && c.ch[0].clk == 0,
           "the latch reloads one clock after a period write (clock offset 0)");
    clear(ev);
    sck_render(&c, 25, collect, ev);
    EXPECT(ev->n == 0, "no step for E = 50 clocks");
    sck_write(&c, SCK_PORT_VOLUME, 0, 8);
    EXPECT(c.ch[0].latch == g_ramp[0] * 15 && c.ch[0].volume == 8, "a volume write does not change the latch");
    clear(ev);
    sck_render(&c, 26, collect, ev);
    EXPECT(ev->n == 1 && ev->e[0].clock == 25 && ev->e[0].level == level_of(g_ramp[1], 8) && c.ch[0].pos == 1,
           "the new volume is heard at the next step, E + 1 = 51 clocks after the reload (%d events, first at %u)", ev->n,
           ev->n ? ev->e[0].clock : 0);
    sck_render(&c, 10, NULL, NULL);
    sck_write(&c, SCK_PORT_VOLUME, 0, 4);
    set_period(&c, 0, 50);
    clear(ev);
    sck_render(&c, 60, collect, ev);
    EXPECT(ev->n == 2 && ev->e[0].clock == 0 && ev->e[0].level == level_of(g_ramp[1], 4) && ev->e[1].clock == 51 &&
               ev->e[1].level == level_of(g_ramp[2], 4),
           "a period write mid-step restarts it: reload at the same position at once, the next step 51 clocks later");
    {
        const int at = next_step(&c, 0);
        sck_write(&c, SCK_PORT_KEY, 0, 0x00);
        clear(ev);
        sck_render(&c, (uint32_t)at, collect, ev);
        const int before = ev->n;
        sck_render(&c, 1, collect, ev);
        EXPECT(before == 0 && ev->n == 1 && ev->e[0].level == 0, "key off is heard at the next step, not at once");
    }
    {
        sck_write(&c, SCK_PORT_KEY, 0, 0x01);
        const int p = c.ch[0].pos, at = next_step(&c, 0);
        sck_write(&c, SCK_PORT_WAVE, p, -100 & 0xFF);          /* the current position: read only at a reload */
        sck_write(&c, SCK_PORT_WAVE, (p + 1) & 31, 85);        /* the next one */
        clear(ev);
        sck_render(&c, (uint32_t)at, collect, ev);
        const int before = ev->n;
        clear(ev);
        sck_render(&c, 1, collect, ev);
        EXPECT(before == 0 && ev->n == 1 && ev->e[0].clock == 0 && ev->e[0].level == level_of(85, 4),
               "key on and a wave RAM write are heard at the next step (%d before, level %d)", before,
               ev->n ? ev->e[0].level : 999);
    }

    /* Test bit 5: a period write resets the position (both chips). */
    for (int plus = 0; plus < 2; ++plus) {
        fresh(&c, plus);
        fill_wave(&c, 0, g_ramp);
        sck_write(&c, SCK_PORT_VOLUME, 0, 15);
        sck_write(&c, SCK_PORT_KEY, 0, 0x01);
        set_period(&c, 0, 50);
        sck_render(&c, 51 * 5 + 3, NULL, NULL);
        const int p = c.ch[0].pos;
        set_period(&c, 0, 50);
        const int kept = c.ch[0].pos == p && p == 5;
        sck_write(&c, SCK_PORT_TEST, 0, SCK_TEST_RESTART);
        set_period(&c, 0, 50);
        const int reset = c.ch[0].pos == 0 && c.ch[0].clk == -1;
        clear(ev);
        sck_render(&c, 1, collect, ev);
        EXPECT(kept && reset && ev->n == 1 && ev->e[0].level == level_of(g_ramp[0], 15),
               "%s test bit 5: a period write resets the position to 0 (without it: kept at %d)", plus ? "SCC+" : "SCC", p);
    }

    /* Test bits 6 and 7. */
    for (int plus = 0; plus < 2; ++plus) {
        fresh(&c, plus);
        const int port = plus ? SCK_PORT_WAVE_PLUS : SCK_PORT_WAVE;
        sck_write(&c, SCK_PORT_TEST, 0, SCK_TEST_FREEZE);
        sck_write(&c, port, 0x05, 77);
        sck_write(&c, port, 0x65, 77);
        if (plus) sck_write(&c, port, 0x85, 77);
        int frozen = 1;
        for (int k = 0; k < SCK_RAMS; ++k)
            for (int i = 0; i < SCK_WAVE_BYTES; ++i) frozen &= c.ram[k][i] == 0;
        sck_write(&c, SCK_PORT_TEST, 0, SCK_TEST_FREEZE4);
        sck_write(&c, port, 0x05, 66);
        sck_write(&c, port, 0x65, 66);
        if (plus) sck_write(&c, port, 0x85, 66);
        const int bit7 = plus ? c.ram[0][5] == 66 && c.ram[3][5] == 66 && c.ram[4][5] == 66 : c.ram[0][5] == 66 && c.ram[3][5] == 0;
        sck_write(&c, SCK_PORT_TEST, 0, 0);
        sck_write(&c, port, 0x65, 55);
        EXPECT(frozen, "%s test bit 6: every wave RAM read-only", plus ? "SCC+" : "SCC");
        EXPECT(bit7, plus ? "SCC+ ignores test bit 7" : "SCC test bit 7: RAM 3 read-only, RAMs 0-2 writable");
        EXPECT(c.ram[3][5] == 55, "%s test 0: RAM 3 writable again", plus ? "SCC+" : "SCC");
    }

    /* Test bits 0 and 1: the effective period, taken at the period write. */
    fresh(&c, 0);
    fill_wave(&c, 0, g_ramp);
    sck_write(&c, SCK_PORT_VOLUME, 0, 15);
    sck_write(&c, SCK_PORT_KEY, 0, 0x01);
    sck_write(&c, SCK_PORT_TEST, 0, SCK_TEST_FREQ4);
    set_period(&c, 0, 0xA64);
    const int e4 = c.ch[0].effective;
    clear(ev);
    sck_render(&c, 1 + 11 * 3, collect, ev);
    EXPECT(e4 == 0x0A && c.ch[0].period == 0xA64 && ev->n == 4 && ev->e[1].clock == 11 && ev->e[3].clock == 33,
           "test bit 0: P 0xA64 counts P >> 8 = 10, a step every 11 clocks (effective %d, %d events)", e4, ev->n);
    sck_write(&c, SCK_PORT_TEST, 0, SCK_TEST_FREQ8);
    set_period(&c, 0, 0xA64);
    const int e8 = c.ch[0].effective;
    sck_write(&c, SCK_PORT_TEST, 0, SCK_TEST_FREQ4 | SCK_TEST_FREQ8);
    set_period(&c, 0, 0xA64);
    const int e48 = c.ch[0].effective;
    sck_write(&c, SCK_PORT_TEST, 0, 0);
    const int kept = c.ch[0].effective;
    set_period(&c, 0, 0xA64);
    EXPECT(e8 == 0x64 && e48 == 0x64 && kept == 0x64 && c.ch[0].effective == 0xA64,
           "test bit 1: P & 0xFF (%d), bit 1 wins over bit 0 (%d), the mode applies at the period write (kept %d, then %d)", e8,
           e48, kept, c.ch[0].effective);
    sck_write(&c, SCK_PORT_TEST, 0, SCK_TEST_FREQ4);
    set_period(&c, 0, 0x805);
    clear(ev);
    sck_render(&c, 1000, collect, ev);
    EXPECT(c.ch[0].effective == 8 && ev->n == 0, "test bit 0 with P 0x805: effective 8 halts");

    /* Shared RAM 3 on the SCC, five RAMs on the SCC+, the other chip's wave port ignored. */
    fresh(&c, 0);
    for (int i = 0; i < 32; ++i) sck_write(&c, SCK_PORT_WAVE, 0x60 + i, g_sine[i] & 0xFF);
    int zero4 = 1;
    for (int i = 0; i < 32; ++i) zero4 &= c.ram[4][i] == 0;
    EXPECT(!memcmp(c.ram[3], g_sine, 32) && zero4, "SCC: regs 0x60-0x7F fill RAM 3, RAM 4 stays 0");
    saved = c;
    for (int i = 0; i < 0xA0; ++i) sck_write(&c, SCK_PORT_WAVE_PLUS, i, 99);
    EXPECT(!memcmp(&c, &saved, sizeof c), "SCC: wave port 4 (the SCC+'s) ignored");
    sck_write(&c, SCK_PORT_VOLUME, 3, 15);
    sck_write(&c, SCK_PORT_VOLUME, 4, 15);
    sck_write(&c, SCK_PORT_KEY, 0, 0x18);
    set_period(&c, 3, 77);
    set_period(&c, 4, 77);
    clear(ev);
    sck_render(&c, 5000, collect, ev);
    int alike = 0;
    EXPECT(channels_alike(ev, 3, 4, &alike) && alike > 30, "SCC: channels 4 and 5 play the same RAM (%d identical events)", alike);
    fresh(&c, 1);
    for (int i = 0; i < 32; ++i) sck_write(&c, SCK_PORT_WAVE_PLUS, 0x60 + i, g_sine[i] & 0xFF);
    for (int i = 0; i < 32; ++i) sck_write(&c, SCK_PORT_WAVE_PLUS, 0x80 + i, g_saw[i] & 0xFF);
    EXPECT(!memcmp(c.ram[3], g_sine, 32) && !memcmp(c.ram[4], g_saw, 32), "SCC+: regs 0x60-0x7F RAM 3, 0x80-0x9F RAM 4");
    saved = c;
    for (int i = 0; i < 0x80; ++i) sck_write(&c, SCK_PORT_WAVE, i, 99);
    EXPECT(!memcmp(&c, &saved, sizeof c), "SCC+: wave port 0 (the SCC's) ignored");
    sck_write(&c, SCK_PORT_VOLUME, 3, 15);
    sck_write(&c, SCK_PORT_VOLUME, 4, 15);
    sck_write(&c, SCK_PORT_KEY, 0, 0x18);
    set_period(&c, 3, 77);
    set_period(&c, 4, 77);
    clear(ev);
    sck_render(&c, 5000, collect, ev);
    EXPECT(!channels_alike(ev, 3, 4, &alike), "SCC+: channels 4 and 5 play their own RAMs");

    /* Register masks. */
    fresh(&c, 0);
    saved = c;
    sck_write(&c, SCK_PORT_PERIOD, 10, 0x55);
    sck_write(&c, SCK_PORT_PERIOD, 15, 0x55);
    EXPECT(!memcmp(&c, &saved, sizeof c), "period regs 10 and up ignored");
    sck_write(&c, SCK_PORT_VOLUME, 2, 0xF7);
    sck_write(&c, SCK_PORT_PERIOD, 3, 0xFF);
    EXPECT(c.ch[2].volume == 7 && c.ch[1].period == 0xF00, "volume: low 4 bits; period: 12 bits (%d, 0x%X)", c.ch[2].volume,
           c.ch[1].period);

    /* sck_advance = sck_render = the clock-by-clock model after random write streams. */
    for (int plus = 0; plus < 2; ++plus) {
        uint64_t clocks = 0;
        const double t0 = now_us();
        const int differ = random_streams(plus, 600, plus ? 0x5CC0B1u : 0x5CC0A1u, &clocks);
        EXPECT(differ == 0, "%s: sck_advance = sck_render = per-clock model after random writes (%d of 600 segments differ)",
               plus ? "SCC+" : "SCC", differ);
        printf("  %s random streams: 600 segments, %.1f M clocks, %.0f ms with the per-clock model\n", plus ? "SCC+" : "SCC",
               clocks / 1e6, (now_us() - t0) / 1000);
    }
}

/* ---- the scripted chip trace (evidence for the Python per-clock model) ---------------------------------------- */

typedef struct Script {
    SccCore core, model;
    FILE* f;
    uint64_t clock;
    int bad;
} Script;

static void script_write(Script* s, int port, int reg, int val) {
    if (s->f) fprintf(s->f, "W %llu %d %d %d\n", (unsigned long long)s->clock, port, reg, val);
    sck_write(&s->core, port, reg, val);
    sck_write(&s->model, port, reg, val);
}

static void script_period(Script* s, int ch, int period) {
    script_write(s, SCK_PORT_PERIOD, 2 * ch, period & 0xFF);
    script_write(s, SCK_PORT_PERIOD, 2 * ch + 1, period >> 8 & 0x0F);
}

static void script_wave(Script* s, int ram, const int8_t* w) {
    for (int i = 0; i < 32; ++i) script_write(s, SCK_PORT_WAVE, ram * 32 + i, w[i] & 0xFF);
}

static void script_render(Script* s, uint32_t clocks) {
    clear(&g_ev_a);
    clear(&g_ev_b);
    sck_render(&s->core, clocks, collect, &g_ev_a);
    model_run(&s->model, clocks, &g_ev_b);
    if (memcmp(&s->core, &s->model, sizeof s->core) || !same_events(&g_ev_a, &g_ev_b)) {
        if (!s->bad) printf("  chip trace: render at clock %llu differs from the model\n", (unsigned long long)s->clock);
        ++s->bad;
        s->core = s->model;
    }
    if (s->f)
        for (int i = 0; i < g_ev_a.n; ++i)
            fprintf(s->f, "E %llu %d %d\n", (unsigned long long)(s->clock + g_ev_a.e[i].clock), g_ev_a.e[i].channel,
                    g_ev_a.e[i].level);
    s->clock += clocks;
}

static void test_chip_trace(const char* dir) {
    printf("chip trace\n");
    static Script s;
    char path[512];
    snprintf(path, sizeof path, "%s/scc_chip_trace.txt", dir);
    memset(&s, 0, sizeof s);
    s.f = fopen(path, "w");
    fresh(&s.core, 0);
    fresh(&s.model, 0);
    if (s.f) fprintf(s.f, "R 0\n");
    script_wave(&s, 0, g_sine);
    script_wave(&s, 1, g_saw);
    script_wave(&s, 2, g_square);
    script_wave(&s, 3, g_triangle);
    static const int volumes[5] = {15, 12, 9, 6, 3};
    for (int c = 0; c < 5; ++c) script_write(&s, SCK_PORT_VOLUME, c, volumes[c]);
    script_write(&s, SCK_PORT_KEY, 0, 0x1F);
    script_period(&s, 0, 253);  /* A-4 */
    script_period(&s, 1, 100);
    script_period(&s, 2, 9);    /* the fastest running period */
    script_period(&s, 3, 8);    /* halted */
    script_period(&s, 4, 4095); /* the slowest; plays RAM 3 */
    script_render(&s, 20000);
    script_write(&s, SCK_PORT_VOLUME, 0, 8); /* heard at the next step */
    script_write(&s, SCK_PORT_KEY, 0, 0x1D); /* channel 2 off at its next step */
    script_render(&s, 5000);
    script_write(&s, SCK_PORT_TEST, 0, 0x20);
    script_period(&s, 0, 253); /* position 0 */
    script_render(&s, 3000);
    script_write(&s, SCK_PORT_TEST, 0, 0x40);
    script_write(&s, SCK_PORT_WAVE, 3, 99); /* ignored */
    script_render(&s, 2000);
    script_write(&s, SCK_PORT_TEST, 0, 0x80);
    script_write(&s, SCK_PORT_WAVE, 0x60, 50); /* ignored on the SCC */
    script_write(&s, SCK_PORT_WAVE, 3, 99);    /* accepted */
    script_render(&s, 2000);
    script_write(&s, SCK_PORT_TEST, 0, 0x01);
    script_write(&s, SCK_PORT_KEY, 0, 0x1F);
    script_period(&s, 1, 0xA64); /* effective 10 */
    script_render(&s, 3000);
    script_write(&s, SCK_PORT_TEST, 0, 0x02);
    script_period(&s, 2, 0x164); /* effective 0x64 */
    script_render(&s, 3000);
    script_write(&s, SCK_PORT_TEST, 0, 0x03);
    script_period(&s, 3, 0x9FF); /* effective 0xFF: bit 1 wins */
    script_render(&s, 3000);
    script_write(&s, SCK_PORT_TEST, 0, 0x00); /* effective periods kept until the next period write */
    script_render(&s, 3000);
    script_period(&s, 1, 0xA64); /* 2660 */
    script_render(&s, 6000);
    script_period(&s, 4, 8); /* halted mid-wave */
    script_render(&s, 4000);
    script_write(&s, SCK_PORT_WAVE_PLUS, 0x10, 1); /* the SCC+'s port: ignored */
    script_write(&s, SCK_PORT_PERIOD, 10, 1);       /* ignored */
    script_render(&s, 1000);
    g_rng = 0xC0FFEEu;
    while (s.clock < 285000) {
        const int writes = (int)(rnd() % 4);
        for (int w = 0; w < writes; ++w) {
            int port, reg, val;
            random_write(0, &port, &reg, &val);
            script_write(&s, port, reg, val);
        }
        script_render(&s, 1 + rnd() % 4000);
    }
    if (s.f) fclose(s.f);
    EXPECT(s.f != NULL, "%s written", path);
    EXPECT(s.bad == 0, "scripted sequence: sck_render = the per-clock model (%d renders differ)", s.bad);
    EXPECT(s.clock < 300000, "under 300000 clocks (%llu)", (unsigned long long)s.clock);
    printf("  %llu clocks scripted\n", (unsigned long long)s.clock);
}

/* ---- periods ------------------------------------------------------------------------------------------------- */

static void test_periods(void) {
    printf("periods\n");
    const double d = 3579545.0 / (32 * 440 * pow(2.0, -57 / 12.0));
    int table_bad = 0;
    for (int f = 0; f < 768; ++f)
        if (SC_DIV[f] != (uint32_t)floor(d * pow(2.0, -f / 768.0) * 256 + 0.5)) ++table_bad;
    EXPECT(table_bad == 0, "SC_DIV[f] = floor(D 2^(-f/768) 256 + 0.5), D = %.4f (%d differ)", d, table_bad);
    int formula_bad = 0, halting = 0;
    for (int pitch = 0; pitch <= SCP_PITCH_MAX; ++pitch) {
        const int o = pitch / 768, f = pitch % 768;
        int want = (int)((SC_DIV[f] + (1u << (7 + o))) >> (8 + o)) - 1;
        if (want > SCP_PERIOD_MAX) want = SCP_PERIOD_MAX;
        const int got = scp_period(pitch);
        if (got != want) ++formula_bad;
        if (got <= SCK_HALT_PERIOD) ++halting;
    }
    EXPECT(formula_bad == 0, "scp_period = the formula for every pitch 0..%d (%d differ)", SCP_PITCH_MAX, formula_bad);
    EXPECT(halting == 0 && scp_period(SCP_PITCH_MAX) == 26, "no pitch halts a channel (highest P %d)", scp_period(SCP_PITCH_MAX));
    EXPECT(scp_period(A4 * 64) == 253, "A-4 = P 253 (got %d)", scp_period(A4 * 64));
    EXPECT(scp_period((A4 + 1) * 64) == 239, "A#4 = P 239 (got %d)", scp_period((A4 + 1) * 64));
    EXPECT(scp_period(95 * 64) == 27 && scp_period(0) == 4095 && scp_period(8 * 64) == 4095, "B-7 = P 27, below A-0 P 4095");
    double worst5 = 0, worst6 = 0, b7 = 0, a4 = 0;
    for (int n = 9; n <= 95; ++n) {
        const double hz = 3579545.0 / (32.0 * (scp_period(64 * n) + 1)), want = 440.0 * pow(2.0, (n - A4) / 12.0);
        const double cents = 1200.0 * log2(hz / want);
        if (n <= 68 && fabs(cents) > worst5) worst5 = fabs(cents);
        if (n <= 80 && fabs(cents) > worst6) worst6 = fabs(cents);
        if (n == 95) b7 = cents;
        if (n == A4) a4 = cents;
    }
    EXPECT(worst5 <= 4.2 && worst6 <= 12.0 && fabs(b7 - 19.2) < 0.1 && fabs(a4 - 1.6) < 0.1,
           "A-0..G#5 within %.2f cents, ..G#6 within %.2f, B-7 %+.2f, A-4 %+.2f", worst5, worst6, b7, a4);
    printf("  A-4 = P 253 (%.3f Hz, %+.2f cents); A-0..G#5 within %.2f cents, ..G#6 within %.2f; B-7 %+.2f\n", A4_HZ, a4, worst5,
           worst6, b7);
}

/* ---- images -------------------------------------------------------------------------------------------------- */

static void corrupt_bytes(int offset, int value, int width, const char* want, int at) {
    memcpy(&g_bad, &g_song, sizeof g_bad);
    g_bad.bytes[offset] = (uint8_t)value;
    if (width == 2) g_bad.bytes[offset + 1] = (uint8_t)(value >> 8);
    const int rc = sc_load_image(&g_bad);
    EXPECT(rc != 0 && g_bad.info.loaded != SC_LOADED && g_bad.info.problem && strstr(g_bad.info.problem, want),
           "byte %d = %d rejected for \"%s\" (got %s)", offset, value, want, rc ? g_bad.info.problem : "accepted");
    if (rc) {
        EXPECT(at < 0 || g_bad.info.problem_offset == at, "\"%s\" reported at %d (want %d)", g_bad.info.problem,
               g_bad.info.problem_offset, at);
        printf("  rejected @%d: %s\n", g_bad.info.problem_offset, g_bad.info.problem);
    }
}

static void corrupt(int offset, int value, const char* want) { corrupt_bytes(offset, value, 1, want, offset); }

static void accepted(int offset, int value, int width, const char* what) {
    memcpy(&g_bad, &g_song, sizeof g_bad);
    g_bad.bytes[offset] = (uint8_t)value;
    if (width == 2) g_bad.bytes[offset + 1] = (uint8_t)(value >> 8);
    const int rc = sc_load_image(&g_bad);
    EXPECT(rc == 0, "%s accepted (%s)", what, rc ? g_bad.info.problem : "ok");
}

static int instrument_at(int slot) { return SC_OFF_INSTRUMENTS + SC_INSTRUMENT_SIZE * (slot - 1); }

/* Absolute image offset of the first value of envelope `which` of some defined slot of the song, or -1. */
static int find_envelope(int which) {
    for (int s = 1; s <= g_song.info.instruments; ++s) {
        if (!(sc_instrument_flags(&g_song, s) & SC_FLAG_DEFINED)) continue;
        const int off = sc_u16(g_song.bytes + instrument_at(s) + which);
        if (off != (int)SC_ENV_NONE) return SC_OFF_DATA + off + SC_ENV_HEADER;
    }
    return -1;
}

/* A minimal valid image: one order of one row (an empty run) on every channel, wave 1 = the sine, `waves` waves,
 * instrument 1 = wave envelope [1]. Data region: 0x80, then the envelope at 1. */
static void base_image(SccImage* im, int variant, int waves) {
    memset(im, 0, sizeof *im);
    uint8_t* b = im->bytes;
    memcpy(b, "TTSC", 4);
    b[SC_H_LAYOUT] = SC_LAYOUT;
    b[SC_H_VARIANT] = (uint8_t)variant;
    b[SC_H_MULTISPEED] = 1;
    b[SC_H_SPEED] = 6;
    put16(b + SC_H_ORDERS, 1);
    b[SC_H_INSTRUMENTS] = 1;
    b[SC_H_WAVES] = (uint8_t)waves;
    b[SC_OFF_ROWS] = 1;
    memcpy(b + SC_OFF_WAVES, g_sine, 32);
    static const uint8_t data[] = {0x80, 1, 0, 0xFF, 0xFF, 0xFF, 0xFF, 1};
    memcpy(b + SC_OFF_DATA, data, sizeof data);
    put16(b + SC_H_DATA_SIZE, (int)sizeof data);
    uint8_t* ins = b + instrument_at(1);
    ins[SC_I_FLAGS] = SC_FLAG_DEFINED;
    put16(ins + SC_I_VOLUME, SC_ENV_NONE);
    put16(ins + SC_I_ARPEGGIO, SC_ENV_NONE);
    put16(ins + SC_I_PITCH, SC_ENV_NONE);
    put16(ins + SC_I_WAVE, 1);
}

/* Puts `n` pattern bytes after base_image's data (at data offset 8, the new end of the data) and points every
 * channel of orders 0..orders-1 at them. */
static void tail_pattern(SccImage* im, const uint8_t* bytes, int n, int orders) {
    memcpy(im->bytes + SC_OFF_DATA + 8, bytes, (size_t)n);
    put16(im->bytes + SC_H_DATA_SIZE, 8 + n);
    for (int o = 0; o < orders; ++o)
        for (int c = 0; c < SC_CHANNELS; ++c) put16(im->bytes + SC_OFF_ORDERS + 2 * (SC_CHANNELS * o + c), 8);
}

/* g_bad (filled) rejected for `want` (at `at`, -1 = anywhere) or accepted (want NULL). */
static void constructed(const char* what, const char* want, int at) {
    const int rc = sc_load_image(&g_bad);
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
    const ScInfo* info = &g_song.info;
    EXPECT(info->loaded == SC_LOADED && info->variant == SC_VARIANT_SCC && info->orders == 4 && info->loop == 1 &&
               info->multispeed == 1 && info->waves == 64,
           "song info");
    EXPECT(info->rows[0] == 16 && info->rows[1] == 32 && info->rows[2] == 16 && info->rows[3] == 24, "rows per order");
    EXPECT(g_plus.info.loaded == SC_LOADED && g_plus.info.variant == SC_VARIANT_PLUS && g_plus.info.orders == 4 &&
               !memcmp(g_plus.info.rows, info->rows, sizeof info->rows) &&
               !memcmp(g_plus.info.start_speed, info->start_speed, sizeof info->start_speed),
           "the SCC+ image: the same song");
    EXPECT(g_x2.info.multispeed == 2, "x2 image: multispeed 2");
    EXPECT(info->start_speed[0] == g_song.bytes[SC_H_SPEED], "order 0 starts at the header speed");
    printf("  start speeds %d %d %d %d, %d instruments, %d waves\n", info->start_speed[0], info->start_speed[1],
           info->start_speed[2], info->start_speed[3], info->instruments, info->waves);
    EXPECT(sc_instrument_flags(&g_song, SINE_SLOT) & SC_FLAG_DEFINED, "slot %d defined", SINE_SLOT);
    EXPECT(sc_instrument_flags(&g_song, PLUCK_SLOT) & SC_FLAG_DEFINED, "slot %d defined", PLUCK_SLOT);
    EXPECT(sc_instrument_flags(&g_song, RESTART_SLOT) & SC_FLAG_RESTART, "slot %d has RESTART", RESTART_SLOT);

    corrupt(SC_H_MAGIC, 'X', "magic");
    corrupt(SC_H_LAYOUT, 2, "layout");
    corrupt(SC_H_VARIANT, 2, "variant");
    corrupt(SC_H_MULTISPEED, 0, "multispeed");
    corrupt(SC_H_MULTISPEED, 9, "multispeed");
    corrupt(SC_H_SPEED, 0, "speed");
    corrupt_bytes(SC_H_ORDERS, 0, 2, "order count", SC_H_ORDERS);
    corrupt_bytes(SC_H_ORDERS, 257, 2, "order count", SC_H_ORDERS);
    corrupt_bytes(SC_H_LOOP, 4, 2, "loop", SC_H_LOOP);
    corrupt(SC_H_INSTRUMENTS, 65, "instrument count");
    corrupt_bytes(SC_H_INSTRUMENTS, info->instruments - 1, 1, "past the instrument count", instrument_at(info->instruments));
    corrupt(SC_H_WAVES, 0, "wave count");
    corrupt(SC_H_WAVES, 65, "wave count");
    corrupt_bytes(SC_H_DATA_SIZE + 1, 0xFF, 1, "data size", SC_H_DATA_SIZE);
    corrupt(20, 1, "header reserved");
    corrupt(63, 1, "header reserved");
    corrupt(SC_OFF_ROWS + 9, 4, "past the order count");
    corrupt(SC_OFF_ORDERS + 2 * SC_CHANNELS * 9 + 1, 1, "past the order count");
    corrupt_bytes(SC_OFF_ORDERS + 1, 0xF0, 1, "pattern offset", SC_OFF_ORDERS);

    const int ins = SCC_OFFSET_INSTRUMENT;
    EXPECT(ins == instrument_at(1), "SCC_OFFSET_INSTRUMENT is slot 1's record");
    corrupt(ins + SC_I_FLAGS, 0x84, "instrument flags");
    corrupt(ins + SC_I_FLAGS, 0x01, "instrument flags");
    corrupt(ins + 1, 1, "instrument reserved");
    corrupt(ins + 12, 1, "instrument reserved");
    corrupt(ins + 15, 1, "instrument reserved");
    corrupt_bytes(ins + SC_I_WAVE + 1, 0xF0, 1, "envelope offset", ins + SC_I_WAVE);
    corrupt_bytes(ins + SC_I_WAVE, 0xFFFF, 2, "wave envelope", ins + SC_I_WAVE);

    const int ev = SCC_OFFSET_ENV_VOLUME, length = sc_u16(g_song.bytes + ev);
    const int release = sc_u16(g_song.bytes + ev + SC_ENV_RELEASE);
    EXPECT(length >= 2 && release >= 1 && release < length, "SCC_OFFSET_ENV_VOLUME: a volume envelope with a release point");
    corrupt_bytes(ev, 0, 2, "envelope length", ev);
    corrupt_bytes(ev + SC_ENV_RELEASE, 0, 2, "envelope release", ev + SC_ENV_RELEASE);
    corrupt_bytes(ev + SC_ENV_RELEASE, length, 2, "envelope release", ev + SC_ENV_RELEASE);
    corrupt_bytes(ev + SC_ENV_LOOP, length, 2, "envelope loop", ev + SC_ENV_LOOP);
    corrupt_bytes(ev + SC_ENV_LOOP, release, 2, "envelope loop", ev + SC_ENV_LOOP);
    corrupt(ev + SC_ENV_HEADER, 16, "volume value");
    corrupt(SCC_OFFSET_ENV_WAVE, 0, "wave value");
    corrupt(SCC_OFFSET_ENV_WAVE, 65, "wave value");
    corrupt_bytes(SCC_OFFSET_ENV_WAVE - SC_ENV_HEADER + SC_ENV_RELEASE, 1, 2, "envelope release",
                  SCC_OFFSET_ENV_WAVE - SC_ENV_HEADER + SC_ENV_RELEASE);
    const int arp = find_envelope(SC_I_ARPEGGIO), pitch = find_envelope(SC_I_PITCH);
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

    corrupt(SCC_OFFSET_MASK, 0x20, "mask");
    corrupt(SCC_OFFSET_MASK, 0x40, "mask");
    corrupt(SCC_OFFSET_MASK, 0x17, "mask");
    corrupt(SCC_OFFSET_MASK, 0x00, "mask");
    corrupt(SCC_OFFSET_NOTE, 96, "note");
    corrupt(SCC_OFFSET_NOTE, 0xFF, "note");
    accepted(SCC_OFFSET_NOTE, 95, 1, "note 95");
    accepted(SCC_OFFSET_NOTE, SC_NOTE_OFF, 1, "OFF");
    corrupt(SCC_OFFSET_INST, 0, "instrument");
    corrupt(SCC_OFFSET_INST, 65, "instrument");
    {
        int free_slot = 0;
        for (int s = 1; s <= 64 && !free_slot; ++s)
            if (!sc_instrument_flags(&g_song, s)) free_slot = s;
        EXPECT(free_slot > 0, "the song leaves a slot free");
        if (free_slot) corrupt(SCC_OFFSET_INST, free_slot, "instrument");
    }
    corrupt(SCC_OFFSET_EFFECT, 36, "effect code");
    corrupt(SCC_OFFSET_EFFECT, 5, "unknown effect");
    corrupt(SCC_OFFSET_EFFECT, 14, "unknown effect");
    corrupt(SCC_OFFSET_PARAM_B, info->orders, "Bxx");
    accepted(SCC_OFFSET_PARAM_B, info->orders - 1, 1, "Bxx to the last order");
    corrupt(SCC_OFFSET_PARAM_C, 0x10, "Cxx");
    accepted(SCC_OFFSET_PARAM_C, 0x0F, 1, "C0F");
    corrupt(SCC_OFFSET_PARAM_F, 0, "F00");
    accepted(SCC_OFFSET_PARAM_F, 0xFF, 1, "FFF");
    corrupt(SCC_OFFSET_PARAM_W, 0, "Wxx");
    corrupt(SCC_OFFSET_PARAM_W, 65, "Wxx");
    accepted(SCC_OFFSET_PARAM_W, 64, 1, "W40");

    /* Constructed images for the reasons a single byte of the song cannot reach. */
    base_image(&g_bad, SC_VARIANT_SCC, 1);
    constructed("the minimal image", NULL, -1);
    base_image(&g_bad, SC_VARIANT_PLUS, 64);
    g_bad.bytes[SC_H_MULTISPEED] = 8;
    g_bad.bytes[SC_H_SPEED] = 255;
    constructed("the largest header values (SCC+, multispeed 8, speed 255, 64 waves)", NULL, -1);
    {
        /* 256 orders (order 0 of 256 rows: two runs of 128), loop 255 */
        static const uint8_t runs[] = {0xFF, 0xFF};
        base_image(&g_bad, SC_VARIANT_SCC, 1);
        put16(g_bad.bytes + SC_H_ORDERS, 256);
        put16(g_bad.bytes + SC_H_LOOP, 255);
        for (int o = 0; o < 256; ++o) g_bad.bytes[SC_OFF_ROWS + o] = (uint8_t)(o == 0 ? 0 : 1);
        tail_pattern(&g_bad, runs, (int)sizeof runs, 256);
        constructed("256 orders, order 0 of 256 rows, loop 255", NULL, -1);
        tail_pattern(&g_bad, runs, 1, 256);
        constructed("a run of 128 rows for an order of 256", "runs past the data size", -1);
    }
    base_image(&g_bad, SC_VARIANT_SCC, 1);
    g_bad.bytes[SC_OFF_WAVES + 32 + 5] = 3;
    constructed("a non-zero wave above W", "past the wave count", SC_OFF_WAVES + 32 + 5);
    {
        static const uint8_t one_row[] = {0x80};
        base_image(&g_bad, SC_VARIANT_SCC, 1);
        g_bad.bytes[SC_OFF_ROWS] = 2;
        tail_pattern(&g_bad, one_row, 1, 1);
        constructed("a pattern decoding past the data", "runs past the data size", -1);
        static const uint8_t note_mask[] = {SC_MASK_NOTE}; /* the note byte would be at the data size */
        base_image(&g_bad, SC_VARIANT_SCC, 1);
        tail_pattern(&g_bad, note_mask, 1, 1);
        constructed("a row whose note byte is past the data", "runs past the data size", -1);
        static const uint8_t note_row[] = {SC_MASK_NOTE, A4};
        base_image(&g_bad, SC_VARIANT_SCC, 1);
        tail_pattern(&g_bad, note_row, 2, 1);
        constructed("the same row with its note byte", NULL, -1);
    }
    base_image(&g_bad, SC_VARIANT_SCC, 1);
    put16(g_bad.bytes + SC_OFF_DATA + 1, 5); /* L 5, but the data ends after one value */
    constructed("an envelope past the data", "runs past the data size", SC_OFF_DATA + 1);
    base_image(&g_bad, SC_VARIANT_SCC, 1);
    put16(g_bad.bytes + SC_H_DATA_SIZE, (int)SC_DATA_CAPACITY + 1);
    constructed("data size above the region", "data size", SC_H_DATA_SIZE);
    {
        /* volume envelope [15 15 15 0], loop 2, release 2: the loop must come before the release */
        static const uint8_t env[] = {4, 0, 2, 0, 2, 0, 15, 15, 15, 0};
        base_image(&g_bad, SC_VARIANT_SCC, 1);
        memcpy(g_bad.bytes + SC_OFF_DATA + 8, env, sizeof env);
        put16(g_bad.bytes + SC_H_DATA_SIZE, 8 + (int)sizeof env);
        put16(g_bad.bytes + instrument_at(1) + SC_I_VOLUME, 8);
        constructed("loop = release", "envelope loop", SC_OFF_DATA + 8 + SC_ENV_LOOP);
        g_bad.bytes[SC_OFF_DATA + 8 + SC_ENV_LOOP] = 1;
        constructed("loop 1 before release 2", NULL, -1);
        put16(g_bad.bytes + instrument_at(1) + SC_I_ARPEGGIO, 8); /* the same bytes as an arpeggio: no release allowed */
        constructed("a release point on an arpeggio envelope", "envelope release", SC_OFF_DATA + 8 + SC_ENV_RELEASE);
    }

    SccImage* again = &g_bad;
    memcpy(again, &g_song, sizeof *again);
    EXPECT(sc_load_image(again) == 0 && !memcmp(&again->info, &g_song.info, sizeof again->info), "loading is a pure function");
}

/* ---- the reference player ------------------------------------------------------------------------------------ */

static void run_reference(const char* name, const SccImage* image, const Ref* ref, FILE* trace) {
    SccMachine* m = scm_create(RATE);
    int mode = -1;
    EXPECT(scm_start(m, image, 0, 0, SEEK_BUDGET, &mode) == SCM_OK && mode == SCM_SEEK_FROM_START, "%s: start", name);
    const ScPlayer* p = scm_player(m);
    const int plus = image->info.variant == SC_VARIANT_PLUS;
    EXPECT(ref->pos[0] == -1 && ref->pos[1] == -1 && ref->pos[2] == -1, "%s: reference batch 0 is the reset", name);
    EXPECT(same(p, ref, 0), "%s: reset batch = reference (%d writes)", name, p->writes);
    EXPECT(reset_batch_ok(p, plus), "%s: the reset batch writes every register 0, forced, in order (%d writes)", name, p->writes);
    EXPECT(core_matches_shadow(scm_core(m), p), "%s: the core holds the reset", name);
    trace_batch(trace, -1, -1, -1, -1, p);
    int mismatches = 0, first = -1, position_errors = 0, shadow_errors = 0, wrong_port = 0, writes = 0, max_writes = 0;
    for (int k = 1; k <= SCC_REF_TICKS; ++k) {
        if (!pos_is(m, ref, k)) ++position_errors;
        int o, r, t;
        scm_position(m, &o, &r, &t);
        scm_tick(m, g_out);
        if (!same(p, ref, k)) {
            if (first < 0) first = k;
            ++mismatches;
        }
        if (!core_matches_shadow(scm_core(m), p)) ++shadow_errors;
        for (int i = 0; i < p->writes; ++i)
            if (p->write[i].port > SCK_PORT_TEST || p->write[i].port == (plus ? SCK_PORT_WAVE : SCK_PORT_WAVE_PLUS)) ++wrong_port;
        writes += p->writes;
        if (p->writes > max_writes) max_writes = p->writes;
        trace_batch(trace, k - 1, o, r, t, p);
    }
    EXPECT(mismatches == 0, "%s: writes = reference player in all %d ticks (%d differ, first at tick %d)", name, SCC_REF_TICKS,
           mismatches, first - 1);
    if (first > 0) {
        /* Replay to the first difference and show both batches. */
        scm_start(m, image, 0, 0, SEEK_BUDGET, &mode);
        for (int k = 1; k <= first; ++k) scm_tick(m, g_out);
        printf("  tick %d machine  :", first - 1);
        for (int i = 0; i < p->writes && i < 16; ++i) printf(" %d:%02x=%02x", p->write[i].port, p->write[i].reg, p->write[i].val);
        printf("\n  tick %d reference:", first - 1);
        for (int i = 0; i < ref_count(ref, first) && i < 16; ++i) {
            const int j = ref->offsets[first] + i;
            printf(" %d:%02x=%02x", ref->ports[j], ref->regs[j], ref->vals[j]);
        }
        printf("\n");
    }
    EXPECT(position_errors == 0, "%s: positions = reference in every tick (%d differ)", name, position_errors);
    EXPECT(shadow_errors == 0, "%s: the core's registers = the player's shadow after every tick (%d differ)", name, shadow_errors);
    EXPECT(wrong_port == 0, "%s: only this chip's ports written (%d others)", name, wrong_port);
    EXPECT(max_writes <= SCP_MAX_WRITES, "%s: batches fit", name);
    printf("  %s: %d ticks, %d writes (max %d per tick)\n", name, SCC_REF_TICKS, writes, max_writes);
    scm_destroy(m);
}

static void test_reference(const char* dir) {
    printf("reference player\n");
    char path[512];
    snprintf(path, sizeof path, "%s/scc_trace.txt", dir);
    FILE* trace = fopen(path, "w");
    EXPECT(trace != NULL, "%s opened", path);
    run_reference("song", &g_song, &REF_SONG, trace);
    if (trace) fclose(trace);
    run_reference("plus", &g_plus, &REF_PLUS, NULL);
    run_reference("edited", &g_edited, &REF_EDITED, NULL);
    run_reference("tempo", &g_tempo, &REF_TEMPO, NULL);
    int same_positions = 1;
    for (int k = 0; k < 3 * (SCC_REF_TICKS + 1); ++k) same_positions &= REF_SONG.pos[k] == REF_PLUS.pos[k];
    EXPECT(same_positions, "the SCC+ song steps through the SCC song's positions");
}

/* ---- audio --------------------------------------------------------------------------------------------------- */

static void test_audio(const char* dir) {
    printf("audio\n");
    const int ticks = SECONDS * 60;
    for (int plus = 0; plus < 2; ++plus) {
        const SccImage* image = plus ? &g_plus : &g_song;
        const char* name = plus ? "SCC+" : "SCC";
        uint64_t hash[2] = {0, 0};
        for (int pass = 0; pass < 2; ++pass) {
            SccMachine* m = scm_create(RATE);
            int mode;
            scm_start(m, image, 0, 0, SEEK_BUDGET, &mode);
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
                EXPECT(mono(buf, frames), "%s: mono, the same on both stereo channels", name);
                int quiet = 0;
                for (int s = 0; s < SECONDS && (s + 1) * RATE <= frames; ++s)
                    if (rms(buf + 2 * s * RATE, RATE) < 0.005) ++quiet;
                EXPECT(quiet == 0, "%s: every second non-silent (%d quiet)", name, quiet);
                write_wav(dir, plus ? "scc_plus_song.wav" : "scc_song.wav", buf, frames);
                printf("  %s 30 s: rms %.4f, peak %.4f, %.1f us per tick (%.0fx realtime)\n", name, r, pk, us, 16666.7 / us);
            }
            free(buf);
            scm_destroy(m);
        }
        EXPECT(hash[0] == hash[1], "%s: bit-identical on a second start", name);
    }
}

/* Tick cost must be flat: after 1 s of warm-up, 30 s of the test song with no tick above 3x the mean. Thread CPU time,
 * so preemption by other processes does not count. */
static void test_outliers(void) {
    printf("tick cost outliers\n");
    const int ticks = SECONDS * 60;
    double* cpu = (double*)malloc(sizeof(double) * (size_t)ticks);
    SccMachine* m = scm_create(RATE);
    int mode;
    scm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 60; ++k) scm_tick(m, g_out);
    double sum = 0, worst = 0;
    int worst_at = 0;
    for (int k = 0; k < ticks; ++k) {
        const double c0 = cpu_us();
        scm_tick(m, g_out);
        cpu[k] = cpu_us() - c0;
        sum += cpu[k];
        if (cpu[k] > worst) worst = cpu[k], worst_at = k;
    }
    const double mean = sum / ticks;
    EXPECT(worst < 3 * mean, "no tick above 3x the mean: worst %.1f us (tick %d) vs mean %.1f us", worst, worst_at, mean);
    printf("  thread CPU per tick: mean %.1f us, worst %.1f us (%.2fx, tick %d)\n", mean, worst, worst / mean, worst_at);
    scm_destroy(m);
    free(cpu);
}

/* Every pass of the loop steps through the same positions: the loop order is entered first from order 0 and later by
 * B01 from order 3 at speed 5, without an Fxx there; the player re-derives the speed. */
static void test_loop(void) {
    printf("loop passes\n");
    enum { MAX_PASS = 8192 };
    static int pos[4][MAX_PASS];
    int len[4] = {0};
    SccMachine* m = scm_create(RATE);
    int mode, o, r, t, pass = -1;
    scm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 4 * MAX_PASS && pass < 4; ++k) {
        scm_position(m, &o, &r, &t);
        if (o == g_song.info.loop && r == 0 && t == 0) ++pass;
        scm_tick(m, g_out);
        if (pass < 0 || pass >= 4 || len[pass] >= MAX_PASS) continue;
        pos[pass][len[pass]++] = o << 16 | r << 8 | t;
    }
    EXPECT(pass == 4 && len[0] == len[1] && len[1] == len[2] && len[0] < MAX_PASS, "passes of %d, %d, %d ticks", len[0], len[1],
           len[2]);
    EXPECT(!memcmp(pos[0], pos[1], sizeof(int) * (size_t)len[0]) && !memcmp(pos[1], pos[2], sizeof(int) * (size_t)len[0]),
           "every pass steps through the same (order, row, tick) positions");
    EXPECT((uint32_t)len[0] * 735 == SCC_REF_VGM_LOOP_SAMPLES, "= the VGM loop (%u samples)", (unsigned)SCC_REF_VGM_LOOP_SAMPLES);
    printf("  %d ticks per pass; speed at the loop point %d\n", len[0], g_song.info.start_speed[g_song.info.loop]);
    scm_destroy(m);
}

/* ---- seek ---------------------------------------------------------------------------------------------------- */

static void test_seek(void) {
    printf("seek\n");
    /* Targets: the rows straight play is in at these ticks (each sought at its first arrival); some inside the 60-tick
     * pre-roll (rendered from the reset: bit-identical), the rest beyond it. */
    static const int at_ticks[] = {8, 30, 59, 61, 100, 250, 400, 600, 900, 1200};
    const int preroll = SCM_PREROLL_TICKS * g_song.info.multispeed;
    SccMachine* m = scm_create(RATE);
    int inside = 0, beyond = 0;
    for (size_t i = 0; i < sizeof at_ticks / sizeof at_ticks[0]; ++i) {
        const int kt = at_ticks[i] + 1;
        const int o = REF_SONG.pos[3 * kt], r = REF_SONG.pos[3 * kt + 1], k = ref_row_start(&REF_SONG, o, r);
        EXPECT(k > 0, "target %d/%d is in the reference", o, r);
        if (k <= 0) continue;
        const int n = k - 1; /* ticks straight play runs before the target */
        int mode = -1;
        const double t0 = now_us();
        EXPECT(scm_start(m, &g_song, o, r, SEEK_BUDGET, &mode) == SCM_OK && mode == SCM_SEEK_FROM_START, "seek %d/%d", o, r);
        const double us = now_us() - t0;
        EXPECT(pos_is(m, &REF_SONG, k) && scm_ticks(m) == 0, "lands on %d/%d tick 0", o, r);
        /* straight play to the same tick */
        SccMachine* a = scm_create(RATE);
        scm_start(a, &g_song, 0, 0, SEEK_BUDGET, &mode);
        for (int j = 0; j < n; ++j) scm_tick(a, g_out);
        EXPECT(!memcmp(scm_core(a), scm_core(m), sizeof(SccCore)), "seek %d/%d (%d ticks): core state = straight play's", o, r, n);
        float *x, *y;
        const int fx = render(a, 60, &x);
        const int count = SCC_REF_TICKS - k + 1 < 200 ? SCC_REF_TICKS - k + 1 : 200;
        int bad = 0, fy = 0;
        y = (float*)malloc((size_t)60 * SCM_MAX_TICK_FRAMES * 2 * sizeof(float));
        for (int j = 0; j < count; ++j) {
            if (!pos_is(m, &REF_SONG, k + j)) ++bad;
            const int f = scm_tick(m, j < 60 ? y + 2 * fy : g_out);
            if (j < 60) fy += f;
            if (!same(scm_player(m), &REF_SONG, k + j)) ++bad;
        }
        EXPECT(bad == 0, "seek %d/%d: %d ticks of writes and positions = straight play (%d differ)", o, r, count, bad);
        double worst = 0;
        for (int s = 0; s < 2 * (fx < fy ? fx : fy); ++s)
            if (fabs((double)x[s] - y[s]) > worst) worst = fabs((double)x[s] - y[s]);
        const int identical = fx == fy && !memcmp(x, y, sizeof(float) * 2 * (size_t)fx);
        if (n <= preroll) {
            ++inside;
            EXPECT(identical, "seek %d/%d (%d ticks, inside the pre-roll): sound bit-identical to straight play", o, r, n);
        } else {
            ++beyond;
            EXPECT(fx == fy && worst <= 1.0 / 32768 + 1e-9, "seek %d/%d (%d ticks): every sample within 1/32768 (worst %.3g)", o, r,
                   n, worst * 32768);
        }
        printf("  %d/%d: %d ticks fast-forwarded in %.0f us; sound %s (worst %.2f LSB)\n", o, r, n, us,
               identical ? "identical" : "differs", worst * 32768);
        free(x);
        free(y);
        scm_destroy(a);
    }
    EXPECT(inside >= 2 && beyond >= 4, "targets inside (%d) and beyond (%d) the pre-roll", inside, beyond);
    /* The budget: exactly the ticks needed seek from the start, one fewer falls back to the order. */
    {
        const int k = ref_row_start(&REF_SONG, 1, 17), n = k - 1;
        int mode = -1, o, r, t;
        EXPECT(k > 2, "1/17 is in the reference");
        EXPECT(scm_start(m, &g_song, 1, 17, n, &mode) == SCM_OK && mode == SCM_SEEK_FROM_START, "budget %d = the ticks needed", n);
        EXPECT(scm_start(m, &g_song, 1, 17, n - 1, &mode) == SCM_OK && mode == SCM_SEEK_FROM_ORDER, "budget %d: order fallback", n - 1);
        scm_position(m, &o, &r, &t);
        EXPECT(o == 1 && r == 17 && t == 0, "budget fallback lands on 1/17 (%d/%d/%d)", o, r, t);
        EXPECT(scm_start(m, &g_song, 1, 17, 10, &mode) == SCM_OK && mode == SCM_SEEK_FROM_ORDER, "small budget: order fallback");
    }
    /* Order 3 rows 0-1 are only reached by the order fallback (order 2 breaks to row 2). */
    EXPECT(ref_row_start(&REF_SONG, 3, 0) < 0 && ref_row_start(&REF_SONG, 3, 1) < 0, "3/0 and 3/1 are not reached by straight play");
    for (int target = 0; target < 2; ++target) {
        int mode = -1, o, r, t;
        EXPECT(scm_start(m, &g_song, 3, target, SEEK_BUDGET, &mode) == SCM_OK && mode == SCM_SEEK_FROM_ORDER,
               "3/%d: order fallback", target);
        scm_position(m, &o, &r, &t);
        EXPECT(o == 3 && r == target && t == 0 && scm_ticks(m) == 0, "fallback lands on 3/%d (%d/%d/%d)", target, o, r, t);
        int ok = 1, rows_seen = 0, last_row = target;
        for (int j = 0; j < 255 * 4 && rows_seen < 3; ++j) {
            scm_tick(m, g_out);
            scm_position(m, &o, &r, &t);
            if (t == 0) {
                if (o != 3 || r != last_row + 1) ok = 0;
                last_row = r;
                ++rows_seen;
            }
        }
        EXPECT(ok && rows_seen == 3, "fallback then plays order 3 row by row (%d rows)", rows_seen);
    }
    /* A row behind a Dxx of its own order is unreachable even from the order start. */
    {
        int behind_order = -1, behind_row = -1;
        for (int o = 0; o < g_song.info.orders && behind_order < 0; ++o) {
            if (ref_row_start(&REF_SONG, o, 0) < 0) continue;
            int max_row = -1;
            for (int k = 1; k <= SCC_REF_TICKS; ++k)
                if (REF_SONG.pos[3 * k] == o && REF_SONG.pos[3 * k + 1] > max_row) max_row = REF_SONG.pos[3 * k + 1];
            if (max_row + 1 < g_song.info.rows[o]) behind_order = o, behind_row = max_row + 1;
        }
        EXPECT(behind_order >= 0, "the song has a row behind a Dxx of its order");
        if (behind_order >= 0) {
            int mode;
            EXPECT(scm_start(m, &g_song, behind_order, behind_row, SEEK_BUDGET, &mode) == SCM_ERR_IMAGE,
                   "%d/%d (behind its order's Dxx): unreachable, rejected", behind_order, behind_row);
        }
    }
    int mode;
    EXPECT(scm_start(m, &g_song, 4, 0, SEEK_BUDGET, &mode) == SCM_ERR_IMAGE, "order past the song rejected");
    EXPECT(scm_start(m, &g_song, 0, 16, SEEK_BUDGET, &mode) == SCM_ERR_IMAGE, "row past the order rejected");
    EXPECT(scm_start(m, &g_song, -1, 0, SEEK_BUDGET, &mode) == SCM_ERR_IMAGE && scm_start(m, &g_song, 0, -1, SEEK_BUDGET, &mode) ==
           SCM_ERR_IMAGE, "negative positions rejected");
    memcpy(&g_bad, &g_song, sizeof g_bad);
    g_bad.bytes[0] = 'X';
    sc_load_image(&g_bad);
    EXPECT(scm_start(m, &g_bad, 0, 0, SEEK_BUDGET, &mode) == SCM_ERR_IMAGE, "an image that failed to load rejected");
    scm_destroy(m);
}

/* ---- live edits ---------------------------------------------------------------------------------------------- */

/* Plays `image` from the start, applying alt0/alt1 (odd/even batches) before batch k (from batch `from`; before every
 * batch or only then), and compares with `ref`. */
static int play_applying(const SccImage* image, const SccImage* alt0, const SccImage* alt1, int from, int every,
                         const Ref* ref, int* first) {
    SccMachine* m = scm_create(RATE);
    int mode, bad = 0;
    scm_start(m, image, 0, 0, SEEK_BUDGET, &mode);
    *first = -1;
    for (int k = 1; k <= SCC_REF_TICKS; ++k) {
        if (k >= from && (every || k == from)) scm_apply_image(m, (k % 2) ? alt0 : alt1);
        if (!pos_is(m, ref, k)) ++bad;
        scm_tick(m, g_out);
        if (!same(scm_player(m), ref, k)) {
            if (*first < 0) *first = k - 1;
            ++bad;
        }
    }
    scm_destroy(m);
    return bad;
}

/* Ticks the current row lasts, the tick just run included (the machine has run one tick of it). */
static int rest_of_row(SccMachine* m) {
    int o, r, t, n = 1;
    for (;;) {
        scm_position(m, &o, &r, &t);
        if (t == 0 || n > 600) return n;
        scm_tick(m, g_out);
        ++n;
    }
}

/* The song with instrument `slot` deleted, as an editor deleting it would lower the song: its record zeroed, the count
 * lowered to the highest slot left, rows naming it pointed at `instead` (every track decoded in place). */
static int delete_slot(SccImage* out, const SccImage* in, int slot, int instead) {
    memcpy(out, in, sizeof *out);
    uint8_t* b = out->bytes;
    memset(b + instrument_at(slot), 0, SC_INSTRUMENT_SIZE);
    int top = 0;
    for (int s = 1; s <= SC_MAX_INSTRUMENTS; ++s)
        if (sc_instrument_flags(out, s)) top = s;
    b[SC_H_INSTRUMENTS] = (uint8_t)top;
    for (int o = 0; o < in->info.orders; ++o)
        for (int c = 0; c < SC_CHANNELS; ++c) {
            int at = SC_OFF_DATA + sc_u16(b + SC_OFF_ORDERS + 2 * (SC_CHANNELS * o + c));
            for (int left = in->info.rows[o]; left > 0;) {
                const int m = b[at++];
                if (m & SC_RUN) {
                    left -= (m & 0x7F) + 1;
                    continue;
                }
                if (m & SC_MASK_NOTE) ++at;
                if (m & SC_MASK_INSTRUMENT) {
                    if (b[at] == slot) b[at] = (uint8_t)instead;
                    ++at;
                }
                at += 2 * (m >> SC_MASK_EFFECT_SHIFT & 7);
                --left;
            }
        }
    return sc_load_image(out);
}

static void test_live_edits(void) {
    printf("live edits\n");
    int first;
    int bad = play_applying(&g_song, &g_song, &g_song, 1, 1, &REF_SONG, &first);
    EXPECT(bad == 0, "same image applied before every tick: writes = reference (%d differ, first %d)", bad, first);
    bad = play_applying(&g_song, &g_moved, &g_song, 1, 1, &REF_SONG, &first);
    EXPECT(bad == 0, "re-laid-out image and the original alternately before every tick: writes = reference (%d differ, first %d)",
           bad, first);
    bad = play_applying(&g_plus, &g_plus, &g_plus, 1, 1, &REF_PLUS, &first);
    EXPECT(bad == 0, "SCC+: same image applied before every tick: writes = reference (%d differ, first %d)", bad, first);
    {
        SccMachine* m = scm_create(RATE);
        int mode;
        scm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
        double worst = 0, total = 0;
        for (int k = 0; k < 1500; ++k) {
            const double t0 = now_us();
            scm_apply_image(m, (k & 1) ? &g_moved : &g_song);
            const double us = now_us() - t0;
            total += us;
            if (us > worst) worst = us;
            scm_tick(m, g_out);
        }
        printf("  apply_image: %.2f us mean, worst %.1f us\n", total / 1500, worst);
        scm_destroy(m);
    }
    /* A note edit, applied at ticks before its row starts: straight play of the edited song. */
    const int d_edit = first_difference(&REF_SONG, &REF_EDITED);
    EXPECT(d_edit > 1, "the note edit changes the reference (first at batch %d)", d_edit);
    if (d_edit > 1) {
        const int k_edit = row_start_of(&REF_SONG, d_edit);
        const int at[] = {1, 40, k_edit - 30, k_edit - 2, k_edit - 1, k_edit};
        for (size_t i = 0; i < sizeof at / sizeof at[0]; ++i) {
            if (at[i] < 1) continue;
            bad = play_applying(&g_song, &g_edited, &g_edited, at[i], 0, &REF_EDITED, &first);
            EXPECT(bad == 0, "note edit applied before tick %d: = edited straight play (%d differ, first %d)", at[i] - 1, bad, first);
        }
    }
    /* Tempo: one Fxx changed. Applied ahead of its row: straight play of the tempo song. */
    const int d_tempo = first_difference(&REF_SONG, &REF_TEMPO);
    EXPECT(d_tempo > 1, "the tempo edit changes the reference (first at batch %d)", d_tempo);
    if (d_tempo > 1) {
        const int a0 = row_start_of(&REF_SONG, d_tempo), a1 = row_start_of(&REF_TEMPO, d_tempo);
        const int k_tempo = a0 < a1 ? a0 : a1;
        bad = play_applying(&g_song, &g_tempo, &g_tempo, k_tempo > 3 ? k_tempo - 3 : 1, 0, &REF_TEMPO, &first);
        EXPECT(bad == 0, "tempo edit ahead of the playhead: = tempo song straight play (%d differ, first %d)", bad, first);
        /* Behind the playhead: the row has started at the old speed; the edit gives it the new one, the undo the old. */
        const int o = REF_SONG.pos[3 * k_tempo], r = REF_SONG.pos[3 * k_tempo + 1];
        const int old_len = ref_row_length(&REF_SONG, k_tempo), new_len = ref_row_length(&REF_TEMPO, k_tempo);
        SccMachine* m = scm_create(RATE);
        int mode;
        scm_start(m, &g_song, o, r, SEEK_BUDGET, &mode);
        scm_tick(m, g_out);
        scm_apply_image(m, &g_tempo);
        const int a = rest_of_row(m);
        scm_start(m, &g_song, o, r, SEEK_BUDGET, &mode);
        scm_tick(m, g_out);
        scm_apply_image(m, &g_tempo);
        scm_apply_image(m, &g_song);
        const int b = rest_of_row(m);
        EXPECT(old_len != new_len && a == new_len && b == old_len,
               "tempo edit behind the playhead (%d/%d): row of %d ticks (want %d), undo %d (want %d)", o, r, a, new_len, b, old_len);
        scm_destroy(m);
    }
    {
        SccMachine* m = scm_create(RATE);
        int mode, o, r, t;
        /* Cut the song to two orders while order 3 plays: clamped to the last row of the last order, then on in order. */
        const ScInfo* si = &g_short.info;
        EXPECT(si->orders == 2, "the short image has two orders (%d)", si->orders);
        scm_start(m, &g_song, 3, 5, SEEK_BUDGET, &mode);
        scm_tick(m, g_out);
        scm_apply_image(m, &g_short);
        scm_position(m, &o, &r, &t);
        EXPECT(o == si->orders - 1 && r == si->rows[si->orders - 1] - 1, "clamped to the last row of the last order (%d/%d)", o, r);
        int ok = 1, rows_seen = 0, last_order = o, last_row = r;
        for (int k = 0; k < 255 * 8 && rows_seen < 6; ++k) {
            scm_tick(m, g_out);
            scm_position(m, &o, &r, &t);
            if (t == 0) {
                int want_order = last_order, want_row = last_row + 1;
                if (want_row >= si->rows[last_order]) {
                    want_order = last_order + 1 < si->orders ? last_order + 1 : si->loop;
                    want_row = 0;
                }
                if (o != want_order || r != want_row) ok = 0;
                last_order = o;
                last_row = r;
                ++rows_seen;
            }
        }
        EXPECT(ok && rows_seen == 6, "then the song plays on in order (%d rows)", rows_seen);
        /* Multispeed applied live halves the tick. */
        scm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
        const int f1 = scm_tick(m, g_out);
        const uint32_t c1 = scm_clocks(m);
        scm_apply_image(m, &g_x2);
        const int f2 = scm_tick(m, g_out);
        const uint32_t c2 = scm_clocks(m);
        EXPECT((c1 == 59659 || c1 == 59660) && (c2 == 29829 || c2 == 29830) && f1 >= 799 && f1 <= 801 && f2 >= 399 && f2 <= 401,
               "x2 live: %u -> %u clocks, %d -> %d frames per tick", c1, c2, f1, f2);
        scm_destroy(m);
    }
    /* A wave edited while it sounds on SCC 1: the RAM follows at the next tick, only the changed bytes written. */
    {
        int w = 0, changed = 0;
        for (int k = 1; k <= SC_MAX_WAVES && !w; ++k)
            if (memcmp(sc_wave(&g_song, k), sc_wave(&g_wave_edit, k), SC_WAVE_BYTES)) w = k;
        for (int i = 0; w && i < SC_WAVE_BYTES; ++i) changed += sc_wave(&g_song, w)[i] != sc_wave(&g_wave_edit, w)[i];
        EXPECT(w > 0, "the wave_edit image changes a wave");
        SccMachine* a = scm_create(RATE);
        int mode, found = -1;
        scm_start(a, &g_song, 0, 0, SEEK_BUDGET, &mode);
        const ScPlayer* pa = scm_player(a);
        for (int k = 1; k <= SCC_REF_TICKS && found < 0 && w; ++k) {
            const int before = pa->ch[0].active && pa->ch[0].wave == w && !(scm_core(a)->test & SCK_TEST_FREEZE) &&
                               !memcmp(scm_core(a)->ram[0], sc_wave(&g_song, w), SC_WAVE_BYTES);
            scm_tick(a, g_out);
            if (before && pa->ch[0].active && pa->ch[0].wave == w && !(scm_core(a)->test & SCK_TEST_FREEZE)) found = k;
        }
        scm_destroy(a);
        EXPECT(found > 0, "SCC 1 plays wave %d in straight play", w);
        if (found > 0) {
            SccMachine* m = scm_create(RATE);
            scm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
            for (int k = 1; k < found; ++k) scm_tick(m, g_out);
            const int held = !memcmp(scm_core(m)->ram[0], sc_wave(&g_song, w), SC_WAVE_BYTES);
            scm_apply_image(m, &g_wave_edit);
            scm_tick(m, g_out);
            const ScPlayer* p = scm_player(m);
            int writes = 0, ordered = 1, last = -1;
            for (int i = 0; i < p->writes; ++i)
                if (p->write[i].port == SCK_PORT_WAVE && p->write[i].reg < SC_WAVE_BYTES) {
                    ++writes;
                    if (p->write[i].reg <= last) ordered = 0;
                    last = p->write[i].reg;
                }
            EXPECT(held && !memcmp(scm_core(m)->ram[0], sc_wave(&g_wave_edit, w), SC_WAVE_BYTES),
                   "wave %d edited at tick %d: RAM 0 holds the edited wave after the next tick", w, found - 1);
            EXPECT(writes == changed && ordered, "only the %d changed bytes written, in index order (%d)", changed, writes);
            scm_destroy(m);
        }
    }
    /* An instrument deleted under a sounding note cuts it at the next tick. */
    {
        SccMachine* a = scm_create(RATE);
        int mode, found = -1, channel = -1, slot = 0;
        scm_start(a, &g_song, 0, 0, SEEK_BUDGET, &mode);
        const ScPlayer* pa = scm_player(a);
        for (int k = 1; k <= SCC_REF_TICKS && found < 0; ++k) {
            int o, r, t, before[SC_CHANNELS];
            scm_position(a, &o, &r, &t);
            for (int c = 0; c < SC_CHANNELS; ++c)
                before[c] = pa->ch[c].active && pa->ch[c].keyed && !pa->ch[c].pending ? pa->ch[c].slot : 0;
            scm_tick(a, g_out);
            if (k < 100 || t == 0) continue;
            for (int c = 0; c < SC_CHANNELS && found < 0; ++c)
                if (before[c] && pa->ch[c].active && pa->ch[c].keyed && pa->ch[c].slot == before[c] &&
                    scm_core(a)->ch[c].effective > SCK_HALT_PERIOD && scm_core(a)->ch[c].volume > 0)
                    found = k, channel = c, slot = before[c];
        }
        scm_destroy(a);
        EXPECT(found > 0, "a note sounding through a tick without a row start");
        if (found > 0) {
            const int instead = slot == SINE_SLOT ? PLUCK_SLOT : SINE_SLOT;
            EXPECT(delete_slot(&g_work, &g_song, slot, instead) == 0, "the song without slot %d loads (%s)", slot,
                   g_work.info.problem ? g_work.info.problem : "ok");
            SccMachine* m = scm_create(RATE);
            scm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
            for (int k = 1; k < found; ++k) scm_tick(m, g_out);
            const ScPlayer* p = scm_player(m);
            const int sounding = p->ch[channel].active && p->ch[channel].keyed && p->ch[channel].slot == slot;
            scm_apply_image(m, &g_work);
            scm_tick(m, g_out);
            const SccCore* c = scm_core(m);
            EXPECT(sounding && !p->ch[channel].active && !p->ch[channel].keyed && !(c->key >> channel & 1) &&
                       sck_level(c, channel) == 0,
                   "slot %d deleted under SCC %d's note at tick %d: cut (active %d, keyed %d, key bit %d, level %d)", slot,
                   channel + 1, found - 1, p->ch[channel].active, p->ch[channel].keyed, c->key >> channel & 1,
                   sck_level(c, channel));
            scm_destroy(m);
        }
    }
}

/* ---- audition ------------------------------------------------------------------------------------------------ */

static void test_audition(const char* dir) {
    printf("audition\n");
    SccMachine* m = scm_create(RATE);
    const double t0 = now_us();
    EXPECT(scm_audition(m, &g_song, 0, A4, SINE_SLOT, 0) == SCM_OK, "A-4 on the sine instrument");
    const double setup = now_us() - t0;
    const ScPlayer* p = scm_player(m);
    float* buf;
    const int frames = render(m, 120, &buf);
    const SccCore* c = scm_core(m);
    EXPECT(c->ch[0].period == 253 && c->ch[0].effective == 253 && (c->key & 0x1F) == 0x01 && c->ch[0].volume == 15,
           "the chip plays P %d on channel 1 only (key %02X, volume %d)", c->ch[0].period, c->key, c->ch[0].volume);
    EXPECT(!memcmp(c->ram[0], sc_wave(&g_song, 1), SC_WAVE_BYTES) && !memcmp(c->ram[0], g_sine, SC_WAVE_BYTES),
           "RAM 0 holds wave 1, the sine");
    const double hz = zero_cross_hz(buf + 2 * (RATE / 10), frames - RATE / 10);
    EXPECT(fabs(hz - A4_HZ) < 0.05, "A-4 measured %.3f Hz (want %.3f)", hz, A4_HZ);
    double lo = 0, hi = 0;
    for (int i = RATE / 2; i < frames; ++i) {
        if (buf[2 * i] < lo) lo = buf[2 * i];
        if (buf[2 * i] > hi) hi = buf[2 * i];
    }
    const double want_p2p = (119 + 120) * (double)SCM_LEVEL_SCALE / 32768;
    EXPECT(fabs((hi - lo) - want_p2p) < 0.02 * want_p2p, "output scale: peak-to-peak %.4f = levels 119..-120 x 48 / 32768 (%.4f)",
           hi - lo, want_p2p);
    EXPECT(rms(buf + 2 * (frames - RATE / 2), RATE / 2) > 0.9 * rms(buf + 2 * (RATE / 2), RATE / 2), "held");
    EXPECT(mono(buf, frames), "mono");
    /* the sine has no release point: release cuts it (the key bit clears) */
    EXPECT(scm_release(m) == 0 && !p->ch[0].keyed && !(p->sh_key & 1), "no release point: release cuts (silent now)");
    float* tail = (float*)malloc(sizeof(float) * 2 * SCM_MAX_TICK_FRAMES * 15);
    int tail_frames = 0, n;
    for (int k = 0; k < 15 && (n = scm_tick(m, tail + 2 * tail_frames)) > 0; ++k) tail_frames += n;
    float* both = (float*)malloc(sizeof(float) * 2 * (size_t)(frames + tail_frames));
    memcpy(both, buf, sizeof(float) * 2 * (size_t)frames);
    memcpy(both + 2 * frames, tail, sizeof(float) * 2 * (size_t)tail_frames);
    write_wav(dir, "scc_tone.wav", both, frames + tail_frames);
    printf("  A-4 %.3f Hz (P 253), peak-to-peak %.4f, setup %.0f us\n", hz, hi - lo, setup);
    free(both);
    free(tail);
    free(buf);

    /* fine pitch: +64 units = +1 semitone; channel 5 on the SCC claims RAM 3 */
    EXPECT(scm_audition(m, &g_song, 4, A4, SINE_SLOT, 64) == SCM_OK, "A-4 fine +64 on SCC 5");
    const int f2 = render(m, 60, &buf);
    const double up = zero_cross_hz(buf + 2 * (RATE / 10), f2 - RATE / 10);
    EXPECT(c->ch[4].period == 239 && fabs(up - AS4_HZ) < 0.06, "fine +64 = A#4: P %d, %.3f Hz (want %.3f)", c->ch[4].period, up,
           AS4_HZ);
    EXPECT(!memcmp(c->ram[3], g_sine, SC_WAVE_BYTES) && (c->key & 0x1F) == 0x10, "SCC 5's wave goes to the shared RAM 3");
    free(buf);

    /* RESTART: the key-on's period write wrapped in test | 0x20 ... test */
    EXPECT(scm_audition(m, &g_song, 1, 48, RESTART_SLOT, 0) == SCM_OK, "RESTART instrument");
    scm_tick(m, g_out);
    {
        int wrapped = 0, set = -1, period_hi = -1, cleared = -1;
        for (int i = 0; i < p->writes; ++i) {
            const ScWrite* w = &p->write[i];
            if (w->port == SCK_PORT_TEST && (w->val & SCK_TEST_RESTART) && set < 0) set = i;
            if (w->port == SCK_PORT_PERIOD && w->reg == 3) period_hi = i;
            if (w->port == SCK_PORT_TEST && !(w->val & SCK_TEST_RESTART) && set >= 0) cleared = i;
        }
        wrapped = set >= 0 && period_hi > set && cleared > period_hi;
        EXPECT(wrapped && scm_core(m)->test == 0, "test 0x20 before the forced period write, test 0 after (%d %d %d)", set,
               period_hi, cleared);
    }

    /* a release point: the pluck releases, then ends once its envelope holds 0 */
    EXPECT(scm_audition(m, &g_song, 0, A4, PLUCK_SLOT, 0) == SCM_OK, "the pluck");
    for (int k = 0; k < 6; ++k) scm_tick(m, g_out);
    EXPECT(scm_release(m) == 1, "release phase");
    int ticks = 0;
    while ((n = scm_tick(m, g_out)) > 0 && ticks < 2 * 60 * SCM_RELEASE_SECONDS) ++ticks;
    EXPECT(n == 0 && ticks < 60 * SCM_RELEASE_SECONDS && scp_silent(p, &g_song, 0),
           "the release ends when its envelope holds 0 (%d ticks)", ticks);
    EXPECT(scm_tick(m, g_out) == 0, "and stays ended");
    printf("  pluck release: %d ticks\n", ticks);

    /* a release that never falls silent ends after SCM_RELEASE_SECONDS */
    {
        /* volume envelope [15 15], release 1: after the release it holds 15 */
        static const uint8_t env[] = {2, 0, 0xFF, 0xFF, 1, 0, 15, 15};
        base_image(&g_tone, SC_VARIANT_SCC, 1);
        memcpy(g_tone.bytes + SC_OFF_DATA + 8, env, sizeof env);
        put16(g_tone.bytes + SC_H_DATA_SIZE, 8 + (int)sizeof env);
        put16(g_tone.bytes + instrument_at(1) + SC_I_VOLUME, 8);
        EXPECT(sc_load_image(&g_tone) == 0, "tone image loads (%s)", g_tone.info.problem ? g_tone.info.problem : "ok");
        EXPECT(scm_audition(m, &g_tone, 2, A4, 1, 0) == SCM_OK, "a sustaining release");
        scm_tick(m, g_out);
        EXPECT(scm_release(m) == 1, "release phase");
        ticks = 0;
        while ((n = scm_tick(m, g_out)) > 0 && ticks < 4 * 60 * SCM_RELEASE_SECONDS) ++ticks;
        EXPECT(n == 0 && ticks >= 60 * SCM_RELEASE_SECONDS - 2 && ticks <= 60 * SCM_RELEASE_SECONDS + 1,
               "ends after %d s (%d ticks)", SCM_RELEASE_SECONDS, ticks);
    }

    EXPECT(scm_audition(m, &g_song, 0, A4, SINE_SLOT, 0) == SCM_OK && scm_release(m) == 0, "release before any tick: cut");
    int free_slot = 0;
    for (int s = 1; s <= 64 && !free_slot; ++s)
        if (!sc_instrument_flags(&g_song, s)) free_slot = s;
    EXPECT(scm_audition(m, &g_song, 0, 48, free_slot, 0) == SCM_ERR_IMAGE, "undefined slot %d rejected", free_slot);
    EXPECT(scm_audition(m, &g_song, 0, 48, 0, 0) == SCM_ERR_IMAGE && scm_audition(m, &g_song, 0, 48, 65, 0) == SCM_ERR_IMAGE,
           "slots 0 and 65 rejected");
    EXPECT(scm_audition(m, &g_song, 0, 96, 1, 0) == SCM_ERR_IMAGE && scm_audition(m, &g_song, 0, -1, 1, 0) == SCM_ERR_IMAGE,
           "notes 96 and -1 rejected");
    EXPECT(scm_audition(m, &g_song, 5, 48, 1, 0) == SCM_ERR_IMAGE && scm_audition(m, &g_song, -1, 48, 1, 0) == SCM_ERR_IMAGE,
           "channels 5 and -1 rejected");
    EXPECT(scm_audition(m, &g_song, 0, 48, 1, 128) == SCM_ERR_IMAGE && scm_audition(m, &g_song, 0, 48, 1, -129) == SCM_ERR_IMAGE,
           "fine 128 and -129 rejected");
    EXPECT(scm_audition(m, &g_song, 4, 95, 1, 127) == SCM_OK && scm_audition(m, &g_song, 0, 0, 1, -128) == SCM_OK,
           "the limits themselves accepted");
    scm_destroy(m);
}

/* ---- mute ---------------------------------------------------------------------------------------------------- */

static void test_mute(void) {
    printf("mute\n");
    SccMachine* m = scm_create(RATE);
    SccMachine* a = scm_create(RATE);
    int mode, bad = 0, core_bad = 0;
    scm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    scm_start(a, &g_song, 0, 0, SEEK_BUDGET, &mode);
    EXPECT(scm_mute(m, 3, 1) == SCM_OK && scm_mute(m, 1, 1) == SCM_OK, "mute SCC 2 and SCC 4");
    for (int k = 1; k <= SCC_REF_TICKS; ++k) {
        scm_tick(m, g_out);
        scm_tick(a, g_out);
        if (!same(scm_player(m), &REF_SONG, k)) ++bad;
        if (memcmp(scm_core(m), scm_core(a), sizeof(SccCore))) ++core_bad;
    }
    EXPECT(bad == 0, "muted channels leave the player's writes unchanged (%d differ)", bad);
    EXPECT(core_bad == 0, "and the chip: core state = unmuted play's (%d differ)", core_bad);
    scm_start(m, &g_song, 1, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 30; ++k) scm_tick(m, g_out);
    for (int c = 0; c < SC_CHANNELS; ++c) scm_mute(m, c, 1);
    float* buf;
    int frames = render(m, 30, &buf);
    const double quiet = rms(buf + 2 * (RATE / 10), frames - RATE / 10);
    EXPECT(quiet < 0.0005, "all channels muted: rms %.6f after 100 ms", quiet);
    free(buf);
    for (int c = 0; c < SC_CHANNELS; ++c) scm_mute(m, c, 0);
    frames = render(m, 30, &buf);
    EXPECT(rms(buf, frames) > 0.01, "unmuted: resumes mid-note (rms %.4f)", rms(buf, frames));
    free(buf);
    /* muted between start and the first tick (a host re-applying mutes on Play) */
    scm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    for (int c = 0; c < SC_CHANNELS; ++c) scm_mute(m, c, 1);
    frames = render(m, 60, &buf);
    EXPECT(rms(buf, frames) < 0.0005, "muted before the first tick: rms %.6f", rms(buf, frames));
    free(buf);
    scm_start(m, &g_song, 1, 17, SEEK_BUDGET, &mode); /* after a seek's pre-roll */
    for (int c = 0; c < SC_CHANNELS; ++c) scm_mute(m, c, 1);
    frames = render(m, 60, &buf);
    EXPECT(rms(buf + 2 * (RATE / 10), frames - RATE / 10) < 0.0005, "muted right after a seek: rms %.6f after 100 ms",
           rms(buf + 2 * (RATE / 10), frames - RATE / 10));
    free(buf);
    /* start clears mutes */
    scm_start(m, &g_song, 1, 0, SEEK_BUDGET, &mode);
    frames = render(m, 30, &buf);
    EXPECT(rms(buf, frames) > 0.01, "start clears mutes (rms %.4f)", rms(buf, frames));
    free(buf);
    EXPECT(scm_mute(m, 5, 1) == SCM_ERR_IMAGE && scm_mute(m, -1, 1) == SCM_ERR_IMAGE, "channels 5 and -1 rejected");
    scm_destroy(m);
    scm_destroy(a);
}

/* ---- the SCC's shared wave RAM against the SCC+'s five -------------------------------------------------------- */

/* Whether channel ch's latch follows `wave` over 32 steps from the core's state (a copy, advanced step by step). */
static int follows(const SccCore* core, int ch, const int8_t* wave) {
    SccCore c = *core;
    if (c.ch[ch].effective <= SCK_HALT_PERIOD || c.ch[ch].clk < 0) return 0;
    for (int j = 0; j < 32; ++j) {
        sck_advance(&c, (uint32_t)c.ch[ch].effective + 1);
        const int want = (c.key >> ch & 1 ? wave[c.ch[ch].pos] : 0) * c.ch[ch].volume;
        if (c.ch[ch].latch != want) return 0;
    }
    return 1;
}

static void test_shared_wave(void) {
    printf("shared wave RAM\n");
    SccMachine* a = scm_create(RATE);
    SccMachine* b = scm_create(RATE);
    int mode, found[2] = {-1, -1}, owners = 0;
    scm_start(a, &g_song, 0, 0, SEEK_BUDGET, &mode);
    scm_start(b, &g_plus, 0, 0, SEEK_BUDGET, &mode);
    const ScPlayer* p = scm_player(a);
    const ScPlayer* pp = scm_player(b);
    const SccCore* c = scm_core(a);
    const SccCore* cp = scm_core(b);
    for (int k = 1; k <= SCC_REF_TICKS && (found[0] < 0 || found[1] < 0); ++k) {
        scm_tick(a, g_out);
        scm_tick(b, g_out);
        if (p->owner3 == 3 || p->owner3 == 4) ++owners;
        if (c->test & (SCK_TEST_FREEZE | SCK_TEST_FREEZE4)) continue;
        const ScChannel* c4 = &p->ch[3];
        const ScChannel* c5 = &p->ch[4];
        if (!(c4->active && c5->active && c4->keyed && c5->keyed && c4->wave && c5->wave)) continue;
        if (!memcmp(sc_wave(&g_song, c4->wave), sc_wave(&g_song, c5->wave), SC_WAVE_BYTES)) continue;
        const int owner = p->owner3, other = owner == 4 ? 3 : 4, slot = owner == 4 ? 0 : 1;
        if ((owner != 3 && owner != 4) || found[slot] >= 0) continue;
        if (c->ch[3].volume == 0 || c->ch[3].effective <= SCK_HALT_PERIOD || cp->ch[3].effective <= SCK_HALT_PERIOD ||
            cp->ch[4].effective <= SCK_HALT_PERIOD)
            continue;
        found[slot] = k;
        const int8_t* w = sc_wave(&g_song, p->ch[owner].wave);
        const int8_t* lost = sc_wave(&g_song, p->ch[other].wave);
        int zero4 = 1;
        for (int i = 0; i < SC_WAVE_BYTES; ++i) zero4 &= c->ram[4][i] == 0;
        EXPECT(!memcmp(c->ram[3], w, SC_WAVE_BYTES) && zero4, "SCC tick %d: RAM 3 = SCC %d's wave %d (the latest claim)", k - 1,
               owner + 1, p->ch[owner].wave);
        EXPECT(follows(c, 3, w) && !follows(c, 3, lost) && follows(c, 4, w),
               "SCC tick %d: SCC 4 and SCC 5 both play wave %d, not SCC %d's wave %d", k - 1, p->ch[owner].wave, other + 1,
               p->ch[other].wave);
        EXPECT(pp->ch[3].wave == c4->wave && pp->ch[4].wave == c5->wave &&
                   !memcmp(cp->ram[3], sc_wave(&g_plus, c4->wave), SC_WAVE_BYTES) &&
                   !memcmp(cp->ram[4], sc_wave(&g_plus, c5->wave), SC_WAVE_BYTES),
               "SCC+ tick %d: RAM 3 = SCC 4's wave %d, RAM 4 = SCC 5's wave %d", k - 1, c4->wave, c5->wave);
        EXPECT(follows(cp, 3, sc_wave(&g_plus, c4->wave)) && follows(cp, 4, sc_wave(&g_plus, c5->wave)),
               "SCC+ tick %d: each channel plays its own wave", k - 1);
    }
    EXPECT(found[0] > 0, "SCC 5 claims the shared RAM while SCC 4 sounds a different wave");
    EXPECT(found[1] > 0, "SCC 4 claims it back while SCC 5 sounds a different wave");
    EXPECT(owners > 0, "the SCC player records RAM 3's owner (%d ticks)", owners);
    printf("  claims checked at ticks %d (SCC 5) and %d (SCC 4)\n", found[0] - 1, found[1] - 1);
    scm_destroy(a);
    scm_destroy(b);
}

/* ---- VGM ----------------------------------------------------------------------------------------------------- */

static uint32_t rd32(const uint8_t* p) { return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24; }

/* Walks the command stream from 0x100: waits summed, the loop point at a command, only this chip's ports. */
static int parse_vgm(const uint8_t* v, size_t size, int plus, uint32_t loop_offset, uint32_t* samples, uint32_t* loop_samples,
                     int* writes) {
    size_t at = 0x100;
    uint32_t total = 0, at_loop = 0;
    int loop_seen = loop_offset == 0, ok = 1;
    *writes = 0;
    while (at < size) {
        if (at == loop_offset) {
            loop_seen = 1;
            at_loop = total;
            /* the register dump opens with test = shadow & 3 */
            if (!(v[at] == 0xD2 && v[at + 1] == SCK_PORT_TEST && (v[at + 3] & ~3) == 0)) ok = 0;
        }
        const uint8_t cmd = v[at];
        if (cmd == 0xD2) {
            const int port = v[at + 1];
            if (port > SCK_PORT_TEST || port == (plus ? SCK_PORT_WAVE : SCK_PORT_WAVE_PLUS)) ok = 0;
            ++*writes;
            at += 4;
        } else if (cmd == 0x61) {
            total += v[at + 1] | v[at + 2] << 8;
            at += 3;
        } else if (cmd == 0x62) {
            total += 735;
            ++at;
        } else if (cmd == 0x63) {
            total += 882;
            ++at;
        } else if (cmd >= 0x70 && cmd <= 0x7F) {
            total += (cmd & 15) + 1u;
            ++at;
        } else if (cmd == 0x66) {
            ++at;
            break;
        } else {
            return 0;
        }
    }
    *samples = total;
    *loop_samples = loop_offset ? total - at_loop : 0;
    return ok && loop_seen && at == size;
}

static void check_vgm(const char* dir, const char* name, const SccImage* image, size_t want_size, uint64_t want_fnv,
                      uint32_t want_ticks, uint32_t want_loop_offset, uint32_t want_loop_samples, uint32_t want_samples) {
    const int plus = image->info.variant == SC_VARIANT_PLUS;
    static ScPlayer scratch;
    ScVgmInfo info;
    memset(&info, 0, sizeof info);
    EXPECT(sc_vgm_render(image, VGM_MAX_TICKS, &scratch, NULL, 0, &info) == 0 && info.size == want_size,
           "%s: measuring reports %zu bytes (want %zu)", name, info.size, want_size);
    size_t cap = 1 << 22;
    uint8_t* buf = (uint8_t*)malloc(cap);
    const double t0 = now_us();
    const size_t size = sc_vgm_render(image, VGM_MAX_TICKS, &scratch, buf, cap, &info);
    const double us = now_us() - t0;
    EXPECT(size == want_size && fnv(0xCBF29CE484222325ull, buf, size) == want_fnv,
           "%s: VGM bytes = the reference player's (%zu vs %zu bytes)", name, size, want_size);
    EXPECT(info.ticks == want_ticks && info.samples == want_samples && info.loop_offset == want_loop_offset &&
               info.loop_samples == want_loop_samples && info.size == size,
           "%s: VGM length and loop (%u ticks, loop at %u, %u of %u samples)", name, info.ticks, info.loop_offset,
           info.loop_samples, info.samples);
    const uint32_t clock = rd32(buf + 0x9C);
    EXPECT(size > 0x100 && !memcmp(buf, "Vgm ", 4) && rd32(buf + 4) == size - 4 && rd32(buf + 8) == 0x171 &&
               rd32(buf + 0x18) == info.samples && rd32(buf + 0x24) == 60 && rd32(buf + 0x34) == 0xCC &&
               rd32(buf + 0x20) == info.loop_samples && (info.loop_offset ? rd32(buf + 0x1C) + 0x1C == info.loop_offset : rd32(buf + 0x1C) == 0),
           "%s: header", name);
    EXPECT(clock == (plus ? (SC_VGM_CLOCK_PLUS | SC_VGM_CLOCK) : SC_VGM_CLOCK) && clock == (plus ? 0x80000000u | 1789773u : 1789773u),
           "%s: clock 0x%08X (1789773%s)", name, clock, plus ? ", bit 31 = K052539" : "");
    uint32_t samples = 0, loop_samples = 0;
    int writes = 0;
    EXPECT(size > 0x100 && parse_vgm(buf, size, plus, info.loop_offset, &samples, &loop_samples, &writes) &&
               samples == info.samples && loop_samples == info.loop_samples,
           "%s: the stream parses: waits sum to %u samples (loop %u), the loop at the register dump, this chip's ports", name,
           samples, loop_samples);
    EXPECT(sc_vgm_render(image, VGM_MAX_TICKS, &scratch, buf, 1000, &info) == 0 && info.size == want_size,
           "%s: too small a buffer reports 0 and the size needed", name);
    char file[64];
    snprintf(file, sizeof file, "%s.vgm", name);
    write_file(dir, file, buf, size);
    snprintf(file, sizeof file, "%s.bin", name); /* the image, for tools/audio/scc_reference_check.py */
    write_file(dir, file, image->bytes, sizeof image->bytes);
    printf("  %s: %zu bytes, %d writes, %u ticks, loop %u samples (%.2f s), rendered in %.0f us\n", name, size, writes,
           (unsigned)want_ticks, (unsigned)want_loop_samples, want_loop_samples / 44100.0, us);
    free(buf);
}

static void test_vgm(const char* dir) {
    printf("VGM export\n");
    check_vgm(dir, "scc_song", &g_song, SCC_REF_VGM_SIZE, SCC_REF_VGM_FNV, SCC_REF_VGM_TICKS, SCC_REF_VGM_LOOP_OFFSET,
              SCC_REF_VGM_LOOP_SAMPLES, SCC_REF_VGM_SAMPLES);
    check_vgm(dir, "scc_plus_song", &g_plus, SCC_PLUS_REF_VGM_SIZE, SCC_PLUS_REF_VGM_FNV, SCC_PLUS_REF_VGM_TICKS,
              SCC_PLUS_REF_VGM_LOOP_OFFSET, SCC_PLUS_REF_VGM_LOOP_SAMPLES, SCC_PLUS_REF_VGM_SAMPLES);
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

/* The hosts' entry point (scc_export.h): the reference VGM plus a GD3 tag. */
static void export_one(const SccImage* image, size_t ref_size, uint64_t ref_fnv, const char* system) {
    size_t size = 0;
    const char* problem = NULL;
    const char* title = "Thor \xC3\x96pening \xF0\x9F\x8E\xB9"; /* "Thor Öpening 🎹": 2-byte and 4-byte UTF-8 */
    uint8_t* vgm = sc_export_vgm(image->bytes, sizeof image->bytes, VGM_MAX_TICKS, title, "Esko", "2026/11/08", &size, &problem);
    EXPECT(vgm && !problem, "%s: renders (%s)", system, problem ? problem : "ok");
    if (!vgm) return;
    const uint32_t gd3_at = 0x14 + rd32(vgm + 0x14);
    const uint32_t eof = 4 + rd32(vgm + 4);
    EXPECT(gd3_at == ref_size && eof == size, "%s: GD3 after the reference data (at %u), EOF offset = size %zu", system, gd3_at,
           size);
    if (gd3_at != ref_size || size < ref_size + 12) {
        sc_export_free(vgm);
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
               !strcmp(fields[8], "2026/11/08") && !strcmp(fields[9], SC_GD3_CONVERTER) && !fields[2][0] && !fields[10][0],
           "%s: GD3 fields: '%s', '%s', '%s', '%s', '%s'", system, fields[0], fields[4], fields[6], fields[8], fields[9]);
    sc_export_free(vgm);
    /* no date given: today's, "yyyy/mm/dd" */
    vgm = sc_export_vgm(image->bytes, sizeof image->bytes, 100, NULL, NULL, NULL, &size, &problem);
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
    sc_export_free(vgm);
}

static void test_export(void) {
    printf("VGM export entry point\n");
    export_one(&g_song, SCC_REF_VGM_SIZE, SCC_REF_VGM_FNV, SC_GD3_SYSTEM_SCC);
    export_one(&g_plus, SCC_PLUS_REF_VGM_SIZE, SCC_PLUS_REF_VGM_FNV, SC_GD3_SYSTEM_PLUS);
    EXPECT(!strcmp(SC_GD3_SYSTEM_SCC, "MSX SCC (Thor Tracker)") && !strcmp(SC_GD3_SYSTEM_PLUS, "MSX SCC+ (Thor Tracker)"),
           "GD3 system names");
    /* rejected images report the loader's reason */
    size_t size = 1;
    const char* problem = NULL;
    memcpy(g_bad.bytes, g_song.bytes, sizeof g_bad.bytes);
    g_bad.bytes[SC_H_MULTISPEED] = 9;
    uint8_t* vgm = sc_export_vgm(g_bad.bytes, sizeof g_bad.bytes, VGM_MAX_TICKS, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && size == 0 && problem && strstr(problem, "multispeed"), "bad image: NULL, '%s'", problem ? problem : "");
    memcpy(g_bad.bytes, g_song.bytes, sizeof g_bad.bytes);
    g_bad.bytes[SCC_OFFSET_NOTE] = 96;
    vgm = sc_export_vgm(g_bad.bytes, sizeof g_bad.bytes, VGM_MAX_TICKS, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && problem && strstr(problem, "note"), "bad note: '%s'", problem ? problem : "");
    vgm = sc_export_vgm(g_song.bytes, 100, VGM_MAX_TICKS, NULL, NULL, NULL, &size, &problem);
    EXPECT(!vgm && problem && !strcmp(problem, "image size"), "short image: '%s'", problem ? problem : "");
}

/* ---- the ops tables ------------------------------------------------------------------------------------------ */

static ChipImage* ops_load(const ChipEngineOps* ops, const SccImage* image, int* rc) {
    ChipImage* storage = (ChipImage*)calloc(1, ops->image_storage);
    *rc = ops->load_image(storage, image->bytes, ops->image_size);
    return storage;
}

/* Ticks a machine, checking positions against `ref` from batch k; returns the positions that differ. */
static int ops_follow(const ChipEngineOps* ops, ChipMachine* m, const Ref* ref, int k, int ticks) {
    float out[CHIP_MAX_TICK_FRAMES * 2];
    int bad = 0;
    for (int j = 0; j < ticks && k + j <= SCC_REF_TICKS; ++j) {
        int o, r, t;
        ops->position(m, &o, &r, &t);
        if (o != ref->pos[3 * (k + j)] || r != ref->pos[3 * (k + j) + 1] || t != ref->pos[3 * (k + j) + 2]) ++bad;
        ops->tick(m, out);
    }
    return bad;
}

static void test_ops_for(const char* id, const SccImage* song, const SccImage* other_song, const Ref* ref,
                         const SccImage* tempo_same, const SccImage* tempo_other, const SccImage* x2) {
    printf("ops table \"%s\"\n", id);
    const ChipEngineOps* ops = chip_engine_ops(id);
    const ChipEngineOps* other_ops = chip_engine_ops(song == &g_song ? "msx-scc-plus" : "msx-scc");
    EXPECT(ops && !strcmp(ops->system_id, id) && other_ops, "registry");
    if (!ops || !other_ops) return;
    EXPECT(ops->image_size == SC_IMAGE_SIZE && ops->image_storage == sizeof(SccImage), "sizes");
    EXPECT(ops->create(CHIP_MAX_SAMPLE_RATE + 1) == NULL, "rate above the maximum rejected");
    int rc;
    ChipImage* image = ops_load(ops, song, &rc);
    EXPECT(rc == CHIP_OK, "song loads");
    ChipImage* scratch = (ChipImage*)calloc(1, ops->image_storage);
    EXPECT(ops->load_image(scratch, song->bytes, ops->image_size - 1) == CHIP_ERR_IMAGE, "wrong size rejected");
    memcpy(g_bad.bytes, song->bytes, sizeof g_bad.bytes);
    g_bad.bytes[0] = 'X';
    EXPECT(ops->load_image(scratch, g_bad.bytes, ops->image_size) == CHIP_ERR_IMAGE, "invalid rejected");
    EXPECT(ops->load_image(scratch, other_song->bytes, ops->image_size) == CHIP_ERR_IMAGE, "the other chip's image rejected");
    ChipImage* tempo = ops_load(ops, tempo_same, &rc);
    EXPECT(rc == CHIP_OK, "tempo image loads");
    ChipImage* tempo_wrong = ops_load(other_ops, tempo_other, &rc); /* loaded by the other chip's ops */
    EXPECT(rc == CHIP_OK, "the tempo image as the other chip loads there");
    ChipImage* fast = ops_load(ops, x2, &rc);
    EXPECT(rc == CHIP_OK, "x2 image loads");

    ChipMachine* m = ops->create(RATE);
    int mode = -1, o, r, t;
    const int k = ref_row_start(ref, 1, 4);
    EXPECT(k > 0 && k + 60 <= SCC_REF_TICKS, "1/4 is in the reference");
    EXPECT(ops->start(m, image, 1, 4, SEEK_BUDGET, &mode) == CHIP_OK && mode == CHIP_SEEK_FROM_START, "start 1/4");
    ops->position(m, &o, &r, &t);
    EXPECT(o == 1 && r == 4 && t == 0, "position 1/4/0");
    float out[CHIP_MAX_TICK_FRAMES * 2];
    int frames = 0, n = 0, n_lo = 1 << 30, n_hi = 0;
    for (int j = 0; j < 60; ++j) {
        frames += (n = ops->tick(m, out));
        if (n < n_lo) n_lo = n;
        if (n > n_hi) n_hi = n;
    }
    EXPECT(frames >= RATE - 2 && frames <= RATE + 2 && n_lo >= 799 && n_hi <= 801, "48 kHz: 60 ticks = 1 s (%d frames, %d..%d per tick)",
           frames, n_lo, n_hi);
    ops->position(m, &o, &r, &t);
    if (k > 0 && k + 60 <= SCC_REF_TICKS)
        EXPECT(o == ref->pos[3 * (k + 60)] && r == ref->pos[3 * (k + 60) + 1] && t == ref->pos[3 * (k + 60) + 2],
               "60 ticks later = straight play (%d/%d/%d)", o, r, t);
    /* apply_image: the other chip's image is ignored, this chip's taken */
    int d = -1; /* the first position the tempo edit changes (the SCC+ trace's writes differ from the SCC tempo's) */
    for (int j = 1; j <= SCC_REF_TICKS && d < 0; ++j)
        for (int i = 0; i < 3; ++i)
            if (ref->pos[3 * j + i] != REF_TEMPO.pos[3 * j + i]) d = j;
    EXPECT(d > 1, "the tempo edit changes the positions (batch %d)", d);
    if (d > 1) {
        const int s0 = row_start_of(ref, d), s1 = row_start_of(&REF_TEMPO, d), start = s0 < s1 ? s0 : s1;
        const int from = start > 3 ? start - 3 : 1;
        const int ro = ref->pos[3 * from], rr = ref->pos[3 * from + 1];
        const int kf = ref_row_start(ref, ro, rr);
        ops->start(m, image, ro, rr, SEEK_BUDGET, &mode);
        ops->apply_image(m, tempo_wrong);
        EXPECT(ops_follow(ops, m, ref, kf, 120) == 0, "the other chip's tempo image ignored: positions = the song's");
        ops->start(m, image, ro, rr, SEEK_BUDGET, &mode);
        ops->apply_image(m, tempo);
        EXPECT(ops_follow(ops, m, &REF_TEMPO, kf, 120) == 0, "this chip's tempo image applied: positions = the tempo song's");
    }
    EXPECT(ops->mute(m, 0, 1) == CHIP_OK && ops->mute(m, 4, 1) == CHIP_OK && ops->mute(m, 0, 0) == CHIP_OK &&
               ops->mute(m, 5, 1) == CHIP_ERR_IMAGE,
           "mute");
    EXPECT(ops->audition(m, image, 0, A4, PLUCK_SLOT, 0) == CHIP_OK, "audition");
    n = ops->tick(m, out);
    EXPECT(n >= 799 && n <= 801, "an audition tick: %d frames", n);
    EXPECT(ops->release(m) == 1, "release phase");
    int ticks = 0;
    while (ops->tick(m, out) > 0 && ticks < 1000) ++ticks;
    EXPECT(ticks < 60 * SCM_RELEASE_SECONDS, "released note ends (%d ticks)", ticks);
    EXPECT(ops->audition(m, image, 0, 96, 1, 0) == CHIP_ERR_IMAGE && ops->audition(m, image, 5, 48, 1, 0) == CHIP_ERR_IMAGE,
           "audition limits");
    /* multispeed x2: half ticks */
    EXPECT(ops->start(m, fast, 0, 0, SEEK_BUDGET, &mode) == CHIP_OK, "x2 start");
    frames = 0;
    n_lo = 1 << 30, n_hi = 0;
    for (int j = 0; j < 120; ++j) {
        frames += (n = ops->tick(m, out));
        if (n < n_lo) n_lo = n;
        if (n > n_hi) n_hi = n;
    }
    EXPECT(frames >= RATE - 2 && frames <= RATE + 2 && n_lo >= 399 && n_hi <= 401, "x2: 120 ticks = 1 s (%d frames, %d..%d per tick)",
           frames, n_lo, n_hi);
    ops->destroy(m);
    /* 96 kHz */
    m = ops->create(96000);
    EXPECT(m != NULL, "96 kHz machine");
    if (m) {
        ops->start(m, image, 0, 0, SEEK_BUDGET, &mode);
        float big[CHIP_MAX_TICK_FRAMES * 2];
        frames = 0;
        n_lo = 1 << 30, n_hi = 0;
        for (int j = 0; j < 60; ++j) {
            frames += (n = ops->tick(m, big));
            if (n < n_lo) n_lo = n;
            if (n > n_hi) n_hi = n;
        }
        EXPECT(frames >= 96000 - 2 && frames <= 96000 + 2 && n_lo >= 1599 && n_hi <= (int)SCM_MAX_TICK_FRAMES,
               "96 kHz: 60 ticks = 1 s (%d frames, %d..%d per tick)", frames, n_lo, n_hi);
        ops->destroy(m);
    }
    free(image);
    free(scratch);
    free(tempo);
    free(tempo_wrong);
    free(fast);
}

static void test_ops(void) {
    EXPECT(other_variant(&g_tempo_plus, &g_tempo) == 0, "the tempo image as SCC+ loads");
    EXPECT(other_variant(&g_x2_plus, &g_x2) == 0, "the x2 image as SCC+ loads");
    test_ops_for("msx-scc", &g_song, &g_plus, &REF_SONG, &g_tempo, &g_tempo_plus, &g_x2);
    test_ops_for("msx-scc-plus", &g_plus, &g_song, &REF_PLUS, &g_tempo_plus, &g_tempo, &g_x2_plus);
}

/* ---- multispeed and costs ------------------------------------------------------------------------------------ */

static void test_multispeed(void) {
    printf("multispeed\n");
    SccMachine* m = scm_create(RATE);
    int mode, o, r, t, frames = 0;
    uint64_t clocks = 0;
    scm_start(m, &g_song, 0, 0, SEEK_BUDGET, &mode);
    for (int k = 0; k < 60; ++k) {
        frames += scm_tick(m, g_out);
        clocks += scm_clocks(m);
    }
    EXPECT(clocks == SCM_CLOCK, "60 ticks = %u chip clocks (got %llu)", SCM_CLOCK, (unsigned long long)clocks);
    scm_start(m, &g_x2, 0, 0, SEEK_BUDGET, &mode);
    clocks = 0;
    frames = 0;
    for (int k = 0; k < 120; ++k) {
        frames += scm_tick(m, g_out);
        clocks += scm_clocks(m);
    }
    scm_position(m, &o, &r, &t);
    EXPECT(clocks == SCM_CLOCK && scm_ticks(m) == 120, "120 ticks at x2 = %u chip clocks (got %llu)", SCM_CLOCK,
           (unsigned long long)clocks);
    EXPECT(frames >= RATE - 2 && frames <= RATE + 2, "= 1 s of host frames (%d)", frames);
    const int k = 121; /* the song's position after 120 ticks: x2 steps rows by ticks, as the song */
    EXPECT(o == REF_SONG.pos[3 * k] && r == REF_SONG.pos[3 * k + 1] && t == REF_SONG.pos[3 * k + 2],
           "x2: 120 fast ticks step the rows of 120 song ticks (%d/%d/%d)", o, r, t);
    scm_destroy(m);
}

static void test_costs(void) {
    printf("costs\n");
    SccMachine* m = scm_create(RATE);
    int mode;
    double t0 = now_us();
    scm_start(m, &g_song, 3, 20, SEEK_BUDGET, &mode);
    const double seek = now_us() - t0;
    t0 = now_us();
    ScPlayer* p = (ScPlayer*)malloc(sizeof *p);
    scp_reset(p, &g_song);
    for (int k = 0; k < SCM_SEEK_TICKS; ++k) scp_tick(p, &g_song);
    const double per = (now_us() - t0) / SCM_SEEK_TICKS;
    free(p);
    SccCore c;
    fresh(&c, 0);
    for (int ch = 0; ch < 5; ++ch) {
        sck_write(&c, SCK_PORT_VOLUME, ch, 15);
        set_period(&c, ch, 253);
    }
    sck_write(&c, SCK_PORT_KEY, 0, 0x1F);
    t0 = now_us();
    for (int k = 0; k < 600; ++k) sck_advance(&c, 59659);
    const double adv = (now_us() - t0) / 600;
    t0 = now_us();
    for (int k = 0; k < 600; ++k) sck_render(&c, 59659, NULL, NULL);
    const double ren = (now_us() - t0) / 600;
    printf("  seek to 3/20: %.0f us; player alone %.2f us per tick (%d ticks: %.1f ms); core per tick, five A-4s: advance %.2f us, "
           "render %.1f us\n",
           seek, per, SCM_SEEK_TICKS, per * SCM_SEEK_TICKS / 1000, adv, ren);
    scm_destroy(m);
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    make_waves();
    EXPECT(load(&g_song, scc_song_image, sizeof scc_song_image) == 0, "song loads: %s", g_song.info.problem);
    EXPECT(load(&g_plus, scc_plus_image, sizeof scc_plus_image) == 0, "plus loads: %s", g_plus.info.problem);
    EXPECT(load(&g_moved, scc_moved_image, sizeof scc_moved_image) == 0, "moved loads: %s", g_moved.info.problem);
    EXPECT(load(&g_edited, scc_edited_image, sizeof scc_edited_image) == 0, "edited loads: %s", g_edited.info.problem);
    EXPECT(load(&g_tempo, scc_tempo_image, sizeof scc_tempo_image) == 0, "tempo loads: %s", g_tempo.info.problem);
    EXPECT(load(&g_short, scc_short_image, sizeof scc_short_image) == 0, "short loads: %s", g_short.info.problem);
    EXPECT(load(&g_x2, scc_x2_image, sizeof scc_x2_image) == 0, "x2 loads: %s", g_x2.info.problem);
    EXPECT(load(&g_wave_edit, scc_wave_edit_image, sizeof scc_wave_edit_image) == 0, "wave_edit loads: %s",
           g_wave_edit.info.problem);
    test_core();
    test_chip_trace(dir);
    test_periods();
    test_images();
    test_reference(dir);
    test_audio(dir);
    test_outliers();
    test_loop();
    test_seek();
    test_live_edits();
    test_audition(dir);
    test_mute();
    test_shared_wave();
    test_multispeed();
    test_vgm(dir);
    test_export();
    test_ops();
    test_costs();
    printf("SCC: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
