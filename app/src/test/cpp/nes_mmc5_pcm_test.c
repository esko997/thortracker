/* Host regression test for the MMC5 PCM lane (nes_mmc5_pcm.c: Thor Tracker's software sample player on the MMC5 DAC):
 * extension validation (bindings included), the DAC's zero rule and centring, step timing on the CPU-cycle timebase,
 * volume, loops, the seek path's arithmetic equalling stepped play, events on the engine's real row boundaries (delay,
 * cut, OFF) with FamiStudio's delay/cut chronology (cuts crossing rows, notes cancelling earlier cuts), muted phase,
 * full-replay and order-fallback seeks reconstructing the voice across speed changes and inside an order, live edits
 * following bindings (re-indexed held and pending notes, removed bindings, pitch/loop edits, moved and changed
 * samples), and audition. The mixer budgets are nes_mix_budget_test.c's. The FamiStudio side is the seven-channel test
 * song of nes_mmc5_song.h (rows of the engine's own timing).
 * Build and run: tools/audio/run_native_host_test.sh [evidence-dir]. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "chip_engine.h"
#include "nes_machine.h"
#include "nes_mmc5.h"
#include "nes_mmc5_pcm.h"
#include "nes_mmc5_song.h"

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
static float g_out[NM_MAX_TICK_FRAMES * 2];
static NesImage g_base;
static NesPcmImage g_pcm, g_pcm2;

/* ---- building extensions ------------------------------------------------------------------ */

static void put16(uint8_t* p, uint32_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static void put32(uint8_t* p, uint32_t v) { put16(p, v); put16(p + 2, v >> 16); }

typedef struct Ev { int order, row, flags, mapping, volume, delay, cut; } Ev;

/* An extension with `n` samples of `data` (`len[i]` bytes each, loop starts `loop[i]`), `m` mappings (sample, loop,
 * period, binding: `mbind` NULL gives mapping i binding i) and events; orders = the base image's. */
static void build_bound(NesPcmImage* img, int n, const uint8_t* const* data, const uint32_t* len, const uint32_t* loop,
                        int m, const int* msample, const int* mloop, const uint32_t* period, const int* mbind,
                        const Ev* ev, int nev) {
    uint8_t* e = img->bytes;
    nes_pcm_build_empty(e, g_base.info.orders);
    uint32_t off = 0;
    for (int s = 0; s < n; ++s) {
        uint8_t* se = e + NES_PCM_SAMPLES_AT + NES_PCM_SAMPLE_SIZE * s;
        put32(se, off);
        put32(se + 4, len[s]);
        put32(se + 8, loop[s]);
        memcpy(e + NES_PCM_DATA_AT + off, data[s], len[s]);
        off += len[s];
    }
    for (int i = 0; i < m; ++i) {
        uint8_t* me = e + NES_PCM_MAPPINGS_AT + NES_PCM_MAPPING_SIZE * i;
        me[0] = (uint8_t)msample[i];
        me[1] = (uint8_t)mloop[i];
        put16(me + 2, (uint32_t)(mbind ? mbind[i] : i));
        put32(me + 4, period[i]);
    }
    /* Events grouped by order, in the given order (ascending rows per order). */
    int k = 0;
    for (int o = 0; o < g_base.info.orders; ++o) {
        const int first = k;
        for (int i = 0; i < nev; ++i) {
            if (ev[i].order != o) continue;
            uint8_t* x = e + NES_PCM_EVENTS_AT + NES_PCM_EVENT_SIZE * k++;
            x[0] = (uint8_t)ev[i].row;
            x[1] = (uint8_t)ev[i].flags;
            put16(x + 2, (uint32_t)ev[i].mapping);
            put16(x + 4, (uint32_t)(ev[i].volume | ev[i].delay << 4 | ev[i].cut << 9));
        }
        put16(e + NES_PCM_ORDERS_AT + 4 * o, (uint32_t)first);
        put16(e + NES_PCM_ORDERS_AT + 4 * o + 2, (uint32_t)(k - first));
    }
    e[5] = (uint8_t)n;
    put16(e + 6, (uint32_t)m);
    put32(e + 8, off);
    put16(e + 12, (uint32_t)k);
}

static void build(NesPcmImage* img, int n, const uint8_t* const* data, const uint32_t* len, const uint32_t* loop, int m,
                  const int* msample, const int* mloop, const uint32_t* period, const Ev* ev, int nev) {
    build_bound(img, n, data, len, loop, m, msample, mloop, period, NULL, ev, nev);
}

/* A long looping ramp (sample 0: 1..255 repeating, 6000 bytes, loop from 1000) and a one-shot (sample 1: 6000 bytes at
 * 100.5 cycles each, about 20 frames). */
static uint8_t g_ramp[6000], g_short[6000];
static const uint8_t* g_data[2] = {g_ramp, g_short};
static const uint32_t g_len[2] = {sizeof g_ramp, sizeof g_short}, g_loop[2] = {1000, 0};
/* Mapping 0: the ramp looping at 12.7 cycles per sample (16.16: 832000); mapping 1: the one-shot at 100.5 cycles. */
static const int g_ms[2] = {0, 1}, g_ml[2] = {1, 0};
static const uint32_t g_period[2] = {832000u, 6586368u};

/* Order 0 of the test song: note m0 on row 0, C05 on row 2, m1 delayed 2 frames on row 4, S03 on row 6, OFF on row 8,
 * m0 again on row 10 (it loops on into order 1). */
static const Ev g_events[] = {
    {0, 0, NES_PCM_EV_NOTE, 0, 0, 0, 0},
    {0, 2, NES_PCM_EV_VOLUME, 0, 5, 0, 0},
    {0, 4, NES_PCM_EV_NOTE | NES_PCM_EV_DELAY, 1, 0, 2, 0},
    {0, 6, NES_PCM_EV_CUT, 0, 0, 0, 3},
    {0, 8, NES_PCM_EV_STOP, 0, 0, 0, 0},
    {0, 10, NES_PCM_EV_NOTE | NES_PCM_EV_VOLUME, 0, 15, 0, 0},
};

static void build_default(NesPcmImage* img) {
    build(img, 2, g_data, g_len, g_loop, 2, g_ms, g_ml, g_period, g_events, (int)(sizeof g_events / sizeof g_events[0]));
}

/* ---- extension validation ----------------------------------------------------------------- */

static void test_load(void) {
    printf("PCM extension validation\n");
    build_default(&g_pcm);
    EXPECT(nes_pcm_load(&g_pcm, &g_base.info) == 0, "the test extension loads (%s)", g_pcm.info.problem ? g_pcm.info.problem : "-");
    EXPECT(g_pcm.info.mappings == 2 && g_pcm.info.events == 6 && g_pcm.info.data == sizeof g_ramp + sizeof g_short, "counts");
    static NesPcmImage bad;
#define REJECT(mutate, what)                                    \
    do {                                                        \
        memcpy(bad.bytes, g_pcm.bytes, NES_PCM_EXT_SIZE);       \
        mutate;                                                 \
        EXPECT(nes_pcm_load(&bad, &g_base.info) != 0, what);    \
    } while (0)
    REJECT(bad.bytes[0] = 'X', "bad magic");
    REJECT(bad.bytes[NES_PCM_DATA_AT + 17] = 0, "a $00 sample byte (the DAC ignores it)");
    REJECT(bad.bytes[NES_PCM_MAPPINGS_AT] = 2, "a mapping of a missing sample");
    REJECT(put32(bad.bytes + NES_PCM_MAPPINGS_AT + 4, NES_PCM_PERIOD_MIN - 1), "a period below 8 cycles");
    REJECT(bad.bytes[14] = (uint8_t)(g_base.info.orders + 1), "another order count than the FamiStudio image's");
    REJECT(bad.bytes[NES_PCM_EVENTS_AT + NES_PCM_EVENT_SIZE] = 0, "event rows not ascending");
    REJECT(bad.bytes[NES_PCM_EVENTS_AT + 1] = NES_PCM_EV_NOTE | NES_PCM_EV_STOP, "note and stop on one event");
    REJECT(put32(bad.bytes + NES_PCM_SAMPLES_AT + 8, sizeof g_ramp), "a loop start past the sample");
    REJECT(bad.bytes[NES_PCM_DATA_AT + g_pcm.info.data] = 1, "data after the end");
    /* Bindings: unique, ascending, inside 64 slots x 96 keys. */
    REJECT(put16(bad.bytes + NES_PCM_MAPPINGS_AT + NES_PCM_MAPPING_SIZE + 2, 0), "two mappings with one binding");
    REJECT(put16(bad.bytes + NES_PCM_MAPPINGS_AT + 2, 5), "bindings descending through the table");
    REJECT(put16(bad.bytes + NES_PCM_MAPPINGS_AT + NES_PCM_MAPPING_SIZE + 2, NES_PCM_BINDINGS), "a binding past slot 64, key B-8");
    /* One effect per cell: a delayed note carries no cut. */
    REJECT((bad.bytes[NES_PCM_EVENTS_AT + 2 * NES_PCM_EVENT_SIZE + 1] |= NES_PCM_EV_CUT,
            put16(bad.bytes + NES_PCM_EVENTS_AT + 2 * NES_PCM_EVENT_SIZE + 4, 2 << 4 | 5 << 9)),
           "a delay and a cut on one event");
    memcpy(bad.bytes, g_pcm.bytes, NES_PCM_EXT_SIZE);
    put16(bad.bytes + NES_PCM_MAPPINGS_AT + NES_PCM_MAPPING_SIZE + 2, NES_PCM_BINDING(64, 107));
    EXPECT(nes_pcm_load(&bad, &g_base.info) == 0 && NES_PCM_BINDING(64, 107) == NES_PCM_BINDINGS - 1 && NES_PCM_BINDING(1, 12) == 0,
           "slot 64's B-8 is the last binding (%s)", bad.info.problem ? bad.info.problem : "-");
#undef REJECT
}

/* ---- the voice on the unit ---------------------------------------------------------------- */

static NesPcm g_p, g_q;

static void test_voice(void) {
    printf("PCM voice: zero rule, centre, timing, volume, loops, seek arithmetic\n");
    NesMmc5 unit, other;
    /* Reset centres the DAC with or without mappings: a first mapping added by a live edit starts from the centre. */
    static NesPcmImage empty;
    nes_pcm_build_empty(empty.bytes, g_base.info.orders);
    EXPECT(nes_pcm_load(&empty, &g_base.info) == 0, "an empty extension loads");
    memset(&unit, 0, sizeof unit);
    nes_mmc5_reset(&unit);
    nes_pcm_set_image(&g_p, &empty);
    nes_pcm_reset(&g_p, &unit);
    EXPECT(unit.dac == NES_MMC5_DAC_CENTRE && !unit.irq_trip, "without mappings the DAC is centred at reset too");
    static uint8_t level[64];
    memset(level, NES_MMC5_DAC_CENTRE, sizeof level);
    const uint8_t* level_data[1] = {level};
    const uint32_t level_len[1] = {sizeof level}, level_loop[1] = {0};
    const int level_ms[1] = {0}, level_ml[1] = {0};
    build(&g_pcm2, 1, level_data, level_len, level_loop, 1, level_ms, level_ml, g_period, NULL, 0);
    EXPECT(nes_pcm_load(&g_pcm2, &g_base.info) == 0, "a one-mapping extension loads");
    nes_pcm_apply(&g_p, &unit, &g_pcm2);
    const uint8_t centre_before = unit.dac;
    EXPECT(nes_pcm_note(&g_p, &unit, 0) == 0 && centre_before == unit.dac, "the first note of a centred sample after adding it: no step");
    memset(&unit, 0, sizeof unit);
    nes_mmc5_reset(&unit);
    nes_pcm_set_image(&g_p, &g_pcm);
    nes_pcm_reset(&g_p, &unit);
    EXPECT(unit.dac == NES_MMC5_DAC_CENTRE && !unit.irq_trip, "with mappings the DAC is centred at power-on (128, never $00)");
    nes_pcm_note(&g_p, &unit, 1); /* the one-shot: 100.5 cycles per sample */
    EXPECT(unit.dac == g_short[0], "the note writes its first sample at once");
    /* Each later sample lands on the cycle its 16.16 step time rounds up to. */
    int wrong = 0, steps = 0;
    uint8_t last = unit.dac;
    for (uint32_t t = 1; t < 2000; ++t) {
        nes_pcm_run(&g_p, &unit, t);
        if (unit.dac != last) {
            ++steps;
            const uint64_t at = ((uint64_t)steps * g_period[1] + 0xFFFF) >> 16;
            if (at != t || unit.dac != g_short[steps]) ++wrong;
            last = unit.dac;
        }
    }
    EXPECT(steps > 15 && wrong == 0, "%d steps, %d off their cycle or value", steps, wrong);
    /* Volume: 128 + (s - 128) v / 15, never 0. */
    g_p.volume = 0;
    EXPECT(nes_pcm_value(&g_p) == NES_MMC5_DAC_CENTRE, "volume 0 is the centre");
    g_p.volume = 7;
    const int s = g_short[g_p.pos];
    EXPECT(nes_pcm_value(&g_p) == NES_MMC5_DAC_CENTRE + (s - NES_MMC5_DAC_CENTRE) * 7 / 15, "volume 7");
    g_p.volume = 15;
    /* The one-shot ends: the DAC returns to the centre (a write of 128, not 0). The unit's frames end with the voice's,
     * as on the machine, so both start the next frame at time 0. */
    for (int f = 0; f < 25; ++f) {
        nes_pcm_end_frame(&g_p, &unit, 29780);
        nes_mmc5_end_frame(&unit, 29780);
    }
    EXPECT(!g_p.playing && unit.dac == NES_MMC5_DAC_CENTRE && !unit.irq_trip, "the end of a one-shot centres the DAC");
    /* Stepped (output) and counted (seek) advancement agree on position, phase and DAC, loops included. */
    memset(&other, 0, sizeof other);
    nes_mmc5_reset(&other);
    nes_pcm_set_image(&g_q, &g_pcm);
    nes_pcm_reset(&g_q, &other);
    nes_pcm_note(&g_p, &unit, 0);
    nes_pcm_note(&g_q, &other, 0);
    static blip_t* blip;
    blip = blip_new(RATE / 10);
    blip_set_rates(blip, NM_CPU_HZ, RATE);
    nes_mmc5_set_output(&unit, blip); /* stepped: every sample written */
    for (int frame = 0; frame < 40; ++frame) {
        for (uint32_t t = 997; t < 29780; t += 997) nes_pcm_run(&g_p, &unit, t);
        nes_pcm_end_frame(&g_p, &unit, 29780);
        nes_mmc5_end_frame(&unit, 29780);
        blip_end_frame(blip, 29780);
        blip_clear(blip);
        nes_pcm_end_frame(&g_q, &other, 29780); /* counted */
        nes_mmc5_end_frame(&other, 29780);
    }
    EXPECT(g_p.playing && g_q.playing && g_p.pos == g_q.pos && g_p.next == g_q.next && unit.dac == other.dac,
           "40 frames looping: stepped pos %u next %lld dac %d, counted pos %u next %lld dac %d", g_p.pos, (long long)g_p.next,
           unit.dac, g_q.pos, (long long)g_q.next, other.dac);
    EXPECT(g_p.pos >= g_loop[0] && g_p.pos < g_len[0], "the loop runs from the loop start (pos %u)", g_p.pos);
    nes_mmc5_set_output(&unit, NULL);
    blip_delete(blip);
}

/* ---- on the machine: rows, delay, cut, OFF, mute, seeks, live edits, audition ---------------- */

static int same_voice(const NesPcm* a, const NesPcm* b) {
    return a->playing == b->playing && (!a->playing || (a->mapping == b->mapping && a->pos == b->pos && a->next == b->next)) &&
           a->volume == b->volume && a->pending_flags == b->pending_flags && a->pending_frames == b->pending_frames &&
           a->cut_frames == b->cut_frames;
}

/* Ticks until the next tick starts (order, row) (at most 2000); the frames run. */
static int run_to(NesMachine* m, int order, int row) {
    for (int i = 0; i < 2000; ++i) {
        int o, r, t;
        nm_position(m, &o, &r, &t);
        if (o == order && r == row && t == 0) return i;
        nm_tick_silent(m);
    }
    return -1;
}

static void test_machine(void) {
    printf("PCM lane on the machine\n");
    NesMachine* m = nm_create(RATE, &nes_player_mmc5);
    NesMachine* n = nm_create(RATE, &nes_player_mmc5);
    int mode = -1;
    EXPECT(m && n && nm_pcm(m) != NULL, "the MMC5 build allocates the lane at create");
    EXPECT(nm_start_pcm(m, &g_base, &g_pcm, 0, 0, 0, &mode) == NM_OK, "start");
    nm_tick_silent(m); /* row 0: the note starts at cycle 0 */
    const NesPcm* p = nm_pcm(m);
    const uint64_t k = ((uint64_t)29780 << 16) / g_period[0];
    EXPECT(p->playing && p->mapping == 0 && p->pos == k, "row 0's note, %llu steps after one frame (pos %u)", (unsigned long long)k, p->pos);
    run_to(m, 0, 3);
    EXPECT(p->volume == 5, "C05 on row 2");
    run_to(m, 0, 4);
    nm_tick_silent(m); /* row 4 starts: m1 is delayed by 2 frames */
    EXPECT(p->mapping == 0 && p->pending_flags == NES_PCM_EV_NOTE, "the delayed note waits");
    nm_tick_silent(m);
    EXPECT(p->mapping == 0, "still waiting one frame later");
    nm_tick_silent(m);
    EXPECT(p->mapping == 1 && p->playing, "the delayed note starts two frames after its row");
    run_to(m, 0, 6);
    for (int i = 0; i < 3; ++i) nm_tick_silent(m);
    EXPECT(p->playing, "S03: still playing after the row's first 3 frames have run their counters (frame 0..2)");
    nm_tick_silent(m);
    EXPECT(!p->playing && nm_mmc5(m)->dac == NES_MMC5_DAC_CENTRE, "S03 cut it on the third frame after its row (centred)");
    run_to(m, 0, 11);
    EXPECT(p->playing && p->mapping == 0 && p->volume == 15, "row 10's looping note with C0F");

    /* Muting keeps the phase: two machines, one muted, agree after many frames. */
    nm_start_pcm(m, &g_base, &g_pcm, 0, 0, 0, &mode);
    nm_start_pcm(n, &g_base, &g_pcm, 0, 0, 0, &mode);
    nm_mute_channel(n, NM_MMC5_PCM, 1);
    double e_on = 0, e_off = 0;
    for (int i = 0; i < 200; ++i) {
        int c = nm_tick(m, g_out);
        for (int j = 0; j < 2 * c; ++j) e_on += g_out[j] * g_out[j];
        c = nm_tick(n, g_out);
        for (int j = 0; j < 2 * c; ++j) e_off += g_out[j] * g_out[j];
    }
    EXPECT(same_voice(nm_pcm(m), nm_pcm(n)) && nm_mmc5(m)->dac == nm_mmc5(n)->dac, "muted lane keeps position and phase");
    EXPECT(e_on != e_off, "the mute changes the mix (%.3f vs %.3f)", e_on, e_off);

    /* Seeks: full replay and the order fallback reconstruct the voice straight play has. */
    nm_start_pcm(m, &g_base, &g_pcm, 0, 0, 0, &mode);
    EXPECT(run_to(m, 1, 0) > 0, "straight play reaches order 1");
    nm_start_pcm(n, &g_base, &g_pcm, 1, 0, 12000, &mode);
    EXPECT(mode == NM_SEEK_FROM_START && same_voice(nm_pcm(m), nm_pcm(n)) && nm_mmc5(m)->dac == nm_mmc5(n)->dac,
           "full replay: the same voice (pos %u / %u)", nm_pcm(m)->pos, nm_pcm(n)->pos);
    nm_start_pcm(n, &g_base, &g_pcm, 1, 0, 0, &mode);
    EXPECT(mode == NM_SEEK_FROM_ORDER && nm_pcm(n)->playing && same_voice(nm_pcm(m), nm_pcm(n)) && nm_mmc5(m)->dac == nm_mmc5(n)->dac,
           "order fallback: the looping note carried with its phase (pos %u / %u, next %lld / %lld)", nm_pcm(m)->pos,
           nm_pcm(n)->pos, (long long)nm_pcm(m)->next, (long long)nm_pcm(n)->next);

    /* Live edits: identical extension continues; the ramp moved behind a new sample continues; changed bytes stop. */
    nm_start_pcm(m, &g_base, &g_pcm, 0, 0, 0, &mode);
    nm_start_pcm(n, &g_base, &g_pcm, 0, 0, 0, &mode);
    for (int i = 0; i < 5; ++i) { nm_tick_silent(m); nm_tick_silent(n); }
    nm_apply_image_pcm(n, &g_base, &g_pcm);
    for (int i = 0; i < 5; ++i) { nm_tick_silent(m); nm_tick_silent(n); }
    EXPECT(same_voice(nm_pcm(m), nm_pcm(n)), "an identical extension changes nothing");
    static uint8_t extra[64];
    memset(extra, 77, sizeof extra);
    const uint8_t* moved_data[3] = {extra, g_ramp, g_short};
    const uint32_t moved_len[3] = {sizeof extra, sizeof g_ramp, sizeof g_short}, moved_loop[3] = {0, 1000, 0};
    const int moved_ms[2] = {1, 2};
    build(&g_pcm2, 3, moved_data, moved_len, moved_loop, 2, moved_ms, g_ml, g_period, g_events, 6);
    EXPECT(nes_pcm_load(&g_pcm2, &g_base.info) == 0, "the moved extension loads");
    nm_apply_image_pcm(n, &g_base, &g_pcm2);
    for (int i = 0; i < 5; ++i) { nm_tick_silent(m); nm_tick_silent(n); }
    EXPECT(nm_pcm(n)->playing && nm_pcm(n)->pos == nm_pcm(m)->pos && nm_pcm(n)->sample == 1, "a moved identical sample continues in place");
    g_ramp[3000] = 9;
    build_default(&g_pcm2);
    g_ramp[3000] = (uint8_t)(3000 % 255 + 1);
    EXPECT(nes_pcm_load(&g_pcm2, &g_base.info) == 0, "the changed extension loads");
    nm_apply_image_pcm(n, &g_base, &g_pcm2);
    EXPECT(!nm_pcm(n)->playing && nm_mmc5(n)->dac == NES_MMC5_DAC_CENTRE, "changed bytes stop the voice (centred)");

    /* Audition on the lane: held, release cuts. */
    EXPECT(nm_audition_pcm(n, &g_base, &g_pcm, NM_MMC5_PCM, 1, 0, 0) == NM_OK && nm_pcm(n)->playing && nm_pcm(n)->mapping == 0,
           "audition mapping 1 (index 0)");
    EXPECT(nm_audition_pcm(n, &g_base, &g_pcm, NM_MMC5_PCM, 3, 0, 0) == NM_ERR_IMAGE, "a mapping the extension lacks");
    EXPECT(nm_tick(n, g_out) > 0 && nm_release(n) == 0, "a PCM audition has no release: cut");
    nm_destroy(m);
    nm_destroy(n);
}

/* ---- the delay/cut chronology on the voice ------------------------------------------------- */

static NesMmc5 g_unit, g_unit2;

/* A fresh unit and voice on `img`. */
static void fresh(NesPcm* p, NesMmc5* unit, const NesPcmImage* img) {
    memset(unit, 0, sizeof *unit);
    nes_mmc5_reset(unit);
    nes_pcm_set_image(p, img);
    nes_pcm_reset(p, unit);
}

/* Frames [from, to) of order 0 with a row starting every `speed` frames, as the machine runs them: the lane at cycle 0,
 * then the frame's end. */
static void frames(NesPcm* p, NesMmc5* unit, int from, int to, int speed) {
    for (int f = from; f < to; ++f) {
        nes_pcm_frame(p, unit, f % speed == 0, 0, f / speed);
        nes_pcm_end_frame(p, unit, 29780);
        nes_mmc5_end_frame(unit, 29780);
    }
}

/* Plays order 0 events `ev` (the default samples and mappings: m0 the looping ramp, m1 the one-shot) for frames
 * [0, to) at speed 6 on g_p; returns whether the voice still plays (-1: the extension does not load). */
static int plays_after(const Ev* ev, int nev, int to) {
    build(&g_pcm2, 2, g_data, g_len, g_loop, 2, g_ms, g_ml, g_period, ev, nev);
    if (nes_pcm_load(&g_pcm2, &g_base.info)) return -1;
    fresh(&g_p, &g_unit, &g_pcm2);
    frames(&g_p, &g_unit, 0, to, 6);
    return g_p.playing;
}

static void test_cuts(void) {
    printf("PCM delay/cut chronology: cuts cross rows, notes cancel earlier cuts\n");
    const int N = NES_PCM_EV_NOTE, C = NES_PCM_EV_CUT, D = NES_PCM_EV_DELAY;
    /* S08 at speed 6: the next row starts on frame 6, the cut still fires on frame 8 (FamiStudio counts it on). */
    const Ev s08[] = {{0, 0, N | C, 0, 0, 0, 8}};
    EXPECT(plays_after(s08, 1, 8) == 1, "S08: still playing through frame 7");
    EXPECT(plays_after(s08, 1, 9) == 0 && g_unit.dac == NES_MMC5_DAC_CENTRE, "S08 at speed 6 cuts on frame 8 (centred)");
    const Ev s1f[] = {{0, 0, N | C, 0, 0, 0, 31}};
    EXPECT(plays_after(s1f, 1, 31) == 1 && plays_after(s1f, 1, 32) == 0, "S1F crosses five row starts and cuts on frame 31");
    const Ev vol[] = {{0, 0, N | C, 0, 0, 0, 8}, {0, 1, NES_PCM_EV_VOLUME, 0, 9, 0, 0}};
    EXPECT(plays_after(vol, 2, 9) == 0, "a later row without a note leaves the cut pending");
    /* A note on a later row cancels it; so does a delayed note when it fires, not before. */
    const Ev renote[] = {{0, 0, N | C, 0, 0, 0, 8}, {0, 1, N, 1, 0, 0, 0}};
    EXPECT(plays_after(renote, 2, 12) == 1 && g_p.mapping == 1, "row 1's note cancels row 0's S08");
    const Ev early[] = {{0, 0, N | C, 0, 0, 0, 8}, {0, 1, N | D, 1, 0, 1, 0}};
    EXPECT(plays_after(early, 2, 12) == 1 && g_p.mapping == 1, "a delayed note firing on frame 7 cancels the S08");
    const Ev same[] = {{0, 0, N | C, 0, 0, 0, 8}, {0, 1, N | D, 1, 0, 2, 0}};
    EXPECT(plays_after(same, 2, 12) == 1 && g_p.mapping == 1, "a delayed note due on the cut's own frame fires first and cancels it");
    const Ev late[] = {{0, 0, N | C, 0, 0, 0, 8}, {0, 1, N | D, 1, 0, 3, 0}};
    EXPECT(plays_after(late, 2, 9) == 0, "a delayed note due after the cut does not cancel it: frame 8 cuts");
    EXPECT(plays_after(late, 2, 10) == 1 && g_p.mapping == 1, "then the delayed note starts on frame 9");
    /* The row's own Sxx is set after its note: S00 with a note stops on the row's frame. */
    const Ev own[] = {{0, 0, N, 0, 0, 0, 0}, {0, 1, N | C, 1, 0, 0, 0}};
    EXPECT(plays_after(own, 2, 6) == 1 && plays_after(own, 2, 7) == 0, "a note with S00 stops on its own frame");
    /* A delay longer than its row is forced at the next row start, ahead of that row's event (its cut counts on). */
    const Ev forced[] = {{0, 0, N | D, 1, 0, 9, 0}, {0, 1, C, 0, 0, 0, 2}};
    EXPECT(plays_after(forced, 2, 6) == 0 && plays_after(forced, 2, 7) == 1 && g_p.mapping == 1,
           "G09 at speed 6 is forced on frame 6, the next row start");
    EXPECT(plays_after(forced, 2, 8) == 1 && plays_after(forced, 2, 9) == 0, "row 1's S02 then cuts the forced note on frame 8");
}

/* ---- live edits by binding ----------------------------------------------------------------- */

static void test_live(void) {
    printf("PCM live edits follow bindings, not table entries\n");
    const int N = NES_PCM_EV_NOTE, D = NES_PCM_EV_DELAY;
    static NesPcmImage a, edit;
    /* Bindings 100 (the looping ramp) and 200 (the one-shot); row 0 plays 100, row 1 delays 200 by 3 frames. */
    const int a_ms[2] = {0, 1}, a_ml[2] = {1, 0}, a_bind[2] = {100, 200};
    const uint32_t a_per[2] = {832000u, 6586368u};
    const Ev a_ev[] = {{0, 0, N, 0, 0, 0, 0}, {0, 1, N | D, 1, 0, 3, 0}};
    build_bound(&a, 2, g_data, g_len, g_loop, 2, a_ms, a_ml, a_per, a_bind, a_ev, 2);
    EXPECT(nes_pcm_load(&a, &g_base.info) == 0, "the bound extension loads (%s)", a.info.problem ? a.info.problem : "-");

    /* Another key of the ramp's sound mapped below it (binding 50, the same sample an octave down): every later entry
     * moves up one, entry 0 now plays the ramp at another pitch, entry 1 the ramp. */
    const int b_ms[3] = {0, 0, 1}, b_ml[3] = {1, 1, 0}, b_bind[3] = {50, 100, 200};
    const uint32_t b_per[3] = {1664000u, 832000u, 6586368u};
    const Ev b_ev[] = {{0, 0, N, 1, 0, 0, 0}, {0, 1, N | D, 2, 0, 3, 0}};
    build_bound(&edit, 2, g_data, g_len, g_loop, 3, b_ms, b_ml, b_per, b_bind, b_ev, 2);
    EXPECT(nes_pcm_load(&edit, &g_base.info) == 0, "the re-indexed extension loads");
    fresh(&g_p, &g_unit, &a);
    fresh(&g_q, &g_unit2, &a);
    frames(&g_p, &g_unit, 0, 7, 6); /* row 1 on frame 6: binding 200 pending */
    frames(&g_q, &g_unit2, 0, 7, 6);
    nes_pcm_apply(&g_p, &g_unit, &edit);
    EXPECT(g_p.playing && g_p.mapping == 1 && g_p.period == 832000u && g_p.loop,
           "re-indexed: the held note follows binding 100 to entry %d and keeps its period (%u)", g_p.mapping, g_p.period);
    EXPECT(g_p.pending_flags == N && g_p.pending_mapping == 2, "the pending note follows binding 200 to entry %d", g_p.pending_mapping);
    frames(&g_p, &g_unit, 7, 9, 6);
    frames(&g_q, &g_unit2, 7, 9, 6);
    EXPECT(g_p.pos == g_q.pos && g_p.next == g_q.next && g_unit.dac == g_unit2.dac,
           "the held note's position and phase continue (pos %u / %u)", g_p.pos, g_q.pos);
    frames(&g_p, &g_unit, 9, 10, 6);
    frames(&g_q, &g_unit2, 9, 10, 6);
    EXPECT(g_p.playing && g_p.mapping == 2 && g_q.mapping == 1 && g_p.sample == 1 && g_p.period == g_q.period &&
               g_p.pos == g_q.pos && g_p.next == g_q.next && g_unit.dac == g_unit2.dac,
           "the pending note fires binding 200's one-shot on frame 9 as without the edit");

    /* Binding 200 removed while its note is pending, binding 300 now at its old entry: cancelled, not retargeted. */
    const int c_ms[2] = {0, 0}, c_ml[2] = {1, 1}, c_bind[2] = {100, 300};
    const uint32_t c_per[2] = {832000u, 1664000u};
    const Ev c_ev[] = {{0, 0, N, 0, 0, 0, 0}};
    build_bound(&edit, 2, g_data, g_len, g_loop, 2, c_ms, c_ml, c_per, c_bind, c_ev, 1);
    EXPECT(nes_pcm_load(&edit, &g_base.info) == 0, "the extension without binding 200 loads");
    fresh(&g_p, &g_unit, &a);
    frames(&g_p, &g_unit, 0, 7, 6);
    nes_pcm_apply(&g_p, &g_unit, &edit);
    EXPECT(!g_p.pending_flags, "a removed binding's pending note is cancelled");
    frames(&g_p, &g_unit, 7, 12, 6);
    EXPECT(g_p.playing && g_p.mapping == 0 && g_p.period == 832000u, "past its frame the held ramp plays on (entry %d)", g_p.mapping);

    /* Binding 100 removed while it plays, though entry 0 (binding 101) holds the same sample and period: it stops. */
    const int d_ms[1] = {0}, d_ml[1] = {1}, d_bind[1] = {101};
    const uint32_t d_per[1] = {832000u};
    build_bound(&edit, 2, g_data, g_len, g_loop, 1, d_ms, d_ml, d_per, d_bind, NULL, 0);
    EXPECT(nes_pcm_load(&edit, &g_base.info) == 0, "the extension without binding 100 loads");
    nes_pcm_apply(&g_p, &g_unit, &edit);
    EXPECT(!g_p.playing && g_unit.dac == NES_MMC5_DAC_CENTRE, "a removed binding stops its note (centred)");

    /* A pitch and loop edit of the held binding applies at once, at the note's position and phase. */
    const int e_ms[2] = {0, 1}, e_ml[2] = {0, 0}, e_bind[2] = {100, 200};
    const uint32_t e_per[2] = {1664000u, 6586368u};
    build_bound(&edit, 2, g_data, g_len, g_loop, 2, e_ms, e_ml, e_per, e_bind, a_ev, 2);
    EXPECT(nes_pcm_load(&edit, &g_base.info) == 0, "the pitch/loop edit loads");
    fresh(&g_p, &g_unit, &a);
    frames(&g_p, &g_unit, 0, 3, 6);
    const uint32_t pos = g_p.pos;
    const int64_t next = g_p.next;
    nes_pcm_apply(&g_p, &g_unit, &edit);
    EXPECT(g_p.playing && g_p.mapping == 0 && g_p.period == e_per[0] && !g_p.loop && g_p.pos == pos && g_p.next == next,
           "the binding's new period %u and loop off apply without a restart (pos %u / %u)", g_p.period, g_p.pos, pos);
}

/* ---- the order fallback across speed changes ----------------------------------------------- */

/* Events over the test song's variable speeds (nes_mmc5_speed: order 0 at 6; order 1 rows 0-7 at 7, 8-15 at 3; order
 * 2 rows 0-7 at 3, 8-15 at 4; order 3 at 5), with cuts and delays crossing rows and order starts. */
static const Ev g_seek_events[] = {
    {0, 0, NES_PCM_EV_NOTE, 0, 0, 0, 0},
    {0, 4, NES_PCM_EV_VOLUME, 0, 9, 0, 0},
    {0, 15, NES_PCM_EV_NOTE | NES_PCM_EV_CUT, 0, 0, 0, 9},   /* frame 90: S09 crosses into order 1 (96), cuts on 99 */
    {1, 2, NES_PCM_EV_NOTE | NES_PCM_EV_DELAY, 0, 0, 4, 0},  /* frame 110: G04, the note on 114 */
    {1, 7, NES_PCM_EV_CUT, 0, 0, 0, 10},                     /* frame 145: S0A due on 155 ... */
    {1, 9, NES_PCM_EV_NOTE | NES_PCM_EV_VOLUME, 0, 15, 0, 0}, /* ... where row 9's note cancels it */
    {1, 15, NES_PCM_EV_NOTE | NES_PCM_EV_DELAY, 1, 0, 5, 0}, /* frame 173: G05 due on 178, forced at order 2 (176) */
    {2, 3, NES_PCM_EV_VOLUME, 0, 7, 0, 0},
    {2, 4, NES_PCM_EV_NOTE, 0, 0, 0, 0},
    {2, 14, NES_PCM_EV_NOTE | NES_PCM_EV_CUT, 0, 0, 0, 12},  /* frame 224: S0C crosses into order 3 (232), cuts on 236 */
    {3, 2, NES_PCM_EV_NOTE | NES_PCM_EV_DELAY, 0, 0, 3, 0},
};

static void test_seek(void) {
    printf("PCM order fallback: variable speeds, rows inside the order, cuts and delays crossing boundaries\n");
    build(&g_pcm2, 2, g_data, g_len, g_loop, 2, g_ms, g_ml, g_period, g_seek_events,
          (int)(sizeof g_seek_events / sizeof g_seek_events[0]));
    EXPECT(nes_pcm_load(&g_pcm2, &g_base.info) == 0, "the seek extension loads (%s)", g_pcm2.info.problem ? g_pcm2.info.problem : "-");
    NesMachine* m = nm_create(RATE, &nes_player_mmc5);
    NesMachine* n = nm_create(RATE, &nes_player_mmc5);
    static const int targets[][2] = {{1, 0}, {1, 8}, {2, 0}, {2, 9}, {3, 0}, {3, 5}};
    for (int i = 0; i < (int)(sizeof targets / sizeof targets[0]); ++i) {
        const int o = targets[i][0], r = targets[i][1];
        int mode = -1;
        nm_start_pcm(m, &g_base, &g_pcm2, 0, 0, 0, &mode);
        const int ticks = run_to(m, o, r);
        const NesPcm *a = nm_pcm(m), *b = nm_pcm(n);
        if (o == 1 && r == 0) EXPECT(a->playing && a->cut_frames == 4, "straight play enters order 1 with order 0's S09 pending");
        if (o == 2 && r == 0) EXPECT(a->pending_flags == NES_PCM_EV_NOTE && a->pending_frames == 3, "and order 2 with order 1's G05 pending");
        if (o == 3 && r == 0) EXPECT(a->playing && a->cut_frames == 5, "and order 3 with order 2's S0C pending");
        /* 60 ticks: too few from the song start, enough inside the order. */
        EXPECT(nm_start_pcm(n, &g_base, &g_pcm2, o, r, 60, &mode) == NM_OK && ticks > 60 && mode == NM_SEEK_FROM_ORDER,
               "order %d row %d: the order fallback (straight play takes %d ticks)", o, r, ticks);
        EXPECT(same_voice(a, b) && nm_mmc5(m)->dac == nm_mmc5(n)->dac,
               "order %d row %d: playing %d/%d entry %d/%d pos %u/%u next %lld/%lld volume %d/%d pending %d:%d/%d:%d cut %d/%d dac %d/%d",
               o, r, a->playing, b->playing, a->mapping, b->mapping, a->pos, b->pos, (long long)a->next, (long long)b->next,
               a->volume, b->volume, a->pending_flags, a->pending_frames, b->pending_flags, b->pending_frames, a->cut_frames,
               b->cut_frames, nm_mmc5(m)->dac, nm_mmc5(n)->dac);
    }
    nm_destroy(m);
    nm_destroy(n);
}

/* ---- the chip engine interface ------------------------------------------------------------ */

static void test_ops(void) {
    printf("chip engine interface with a PCM extension\n");
    const ChipEngineOps* ops = chip_engine_ops("nes-famistudio-mmc5");
    static uint8_t bytes[NES_MMC5_IMAGE_SIZE + NES_PCM_EXT_SIZE];
    memset(bytes, 0, sizeof bytes);
    memcpy(bytes, nes_mmc5_thor, NES_MMC5_THOR_SIZE);
    memcpy(bytes + NES_MMC5_IMAGE_SIZE, g_pcm.bytes, NES_PCM_EXT_SIZE);
    ChipImage* img = (ChipImage*)malloc(ops->image_storage);
    EXPECT(ops->load_image(img, bytes, ops->image_size) == CHIP_OK, "loads");
    ChipMachine* cm = ops->create(RATE);
    int mode;
    EXPECT(ops->start(cm, img, 0, 0, 0, &mode) == CHIP_OK && ops->tick(cm, g_out) > 0, "plays");
    EXPECT(ops->audition(cm, img, NM_MMC5_PCM, 2, 1, 0) == CHIP_OK && ops->release(cm) == 0, "audition on lane 7, release cuts");
    ops->destroy(cm);
    free(img);
}

int main(void) {
    for (int i = 0; i < (int)sizeof g_ramp; ++i) g_ramp[i] = (uint8_t)(i % 255 + 1);
    for (int i = 0; i < (int)sizeof g_short; ++i) g_short[i] = (uint8_t)(i % 2 ? 200 : 40 + i % 50);
    memset(&g_base, 0, sizeof g_base);
    memcpy(g_base.bytes, nes_mmc5_thor, NES_MMC5_THOR_SIZE);
    if (nm_load_image(&g_base, &nes_player_mmc5) != 0) {
        printf("the MMC5 fixture does not load: %s\n", g_base.info.problem);
        return 2;
    }
    test_load();
    test_voice();
    test_machine();
    test_cuts();
    test_live();
    test_seek();
    test_ops();
    printf("nes_mmc5_pcm_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
