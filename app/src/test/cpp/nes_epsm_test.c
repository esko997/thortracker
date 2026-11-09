/* Host regression test for the NES + EPSM build ("nes-famistudio-epsm"): the EPSM unit (nes_epsm_unit.c over the YMF288
 * wrapper nes_epsm_ymfm.cpp) on its own — bus decoding, the 8 MHz clock against the CPU, FM and SSG pitch, stereo pans,
 * six full FM channels summed without clipping in the unit's buffers, mutes that leave the chip running, a reset equal to
 * a fresh chip, the rhythm ROM firmware (wrong sizes, no ROM: no sound and a diagnostic flag) — and the FamiStudio sound
 * engine 4.5.3 assembled with FAMISTUDIO_EXP_EPSM = 1 and Thor Tracker's EPSM_ENGINE_PATCH playing FamiStudio's own
 * export of tools/audio/nes/epsm_test.txt and the same rows in the Thor Tracker image layout (nes_epsm_song.h): identical
 * bus writes (APU and $401C-$401F), row timing, rhythm key-on frames, true pitch from the note tables, seek with the chip
 * clocked through it (chip state equal to straight play), live relocation and instrument edits, audition on SSG, FM and
 * rhythm, mute, the image rules of the EPSM build and the chip engine interface. Corrections and extensions: every FM
 * channel's instrument load writes the LFO to $22 only (never $23-$26); each rhythm drum's instrument is the entry the
 * loader itself reads (both layouts) and its level and pan follow live edits; `$5F o` loads an SSG instrument into the
 * right envelope slots, keeps a legato row's envelope positions and (bit 7) the shared period; the bounded seek fallback
 * restores held SSG, FM and rhythm instruments as straight play holds them, an FM patch from the first tick (also on the
 * Kotlin lowering's identical FM copies, epsm-copies.nesimg from exportSamples).
 *
 * The YM2608 rhythm ROM is not part of Thor Tracker. With THOR_RHYTHM_ROM=/path/to/your/ym2608_adpcm_rom.bin (8192
 * bytes, SHA-256 checked here) the rhythm checks with sound run too; without it they are skipped with a notice.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir], or alone:
 *   nes_epsm_test [evidence-dir] [samples-dir]   (samples default app/build/samples, after `tools/build.sh exportSamples`)
 * Writes <dir>/nes_epsm.wav (48 kHz stereo, 30 s; with the ROM the drums included), <dir>/nes_epsm_tonal.wav (the
 * rhythm channels muted), <dir>/nes_epsm_trace.txt (per-frame APU + EPSM writes of FamiStudio's export),
 * <dir>/nes_epsm.nsf (the EPSM player + FamiStudio's export, NSF expansion byte $7B = $80) and, with the ROM,
 * <dir>/nes_epsm_rhythm.wav (only the rhythm channels). */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "blip_buf.h"
#include "chip_engine.h"
#include "nes_apu.h"
#include "nes_epsm.h"
#include "nes_epsm_song.h"
#include "nes_epsm_unit.h"
#include "nes_ft_song.h"
#include "nes_machine.h"
#include "nes_vrc6_song.h"
#include "ym2608_rhythm_rom.h"

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

#define CPU_HZ 1789773.0
#define RATE 48000
#define CHIP_RATE (8000000.0 / 144.0)
#define FRAME 29780u

/* Engine channels of the EPSM build. */
#define CH_SSG1 5
#define CH_FM1 8
#define CH_RHYTHM1 14
#define CHANNELS 20

#define FIRST_PASS_ROWS (EPSM_TEST_ORDERS * EPSM_TEST_ROWS)
#define LOOP_ROWS ((EPSM_TEST_ORDERS - EPSM_TEST_LOOP) * EPSM_TEST_ROWS)
#define TRACE_WRITES 1024 /* bus writes per frame a trace keeps */

_Static_assert(EPSM_TEST_EDITED_PATCH_BYTE == 0, "the edited patch byte is $B0 (feedback, algorithm)");

static const char* ROM_SHA256 = "53afd0fa9c62eda3e2be939e23f3adf48a2af8ad37bb1640261726c5d5adeba8";

static float g_out[NM_MAX_TICK_FRAMES * 2];
static NesImage g_raw, g_thor, g_moved, g_edited;
static int g_rom; /* the rhythm ROM is installed */

static double now_us(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e6 + t.tv_nsec / 1e3;
}

static void write_wav(const char* path, const float* data, int frames) {
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

/* Frequency from rising zero crossings of a signal with `stride` (mean removed). */
static double zero_cross_hz(const float* x, int n, int stride, int rate) {
    double mean = 0;
    for (int i = 0; i < n; ++i) mean += x[i * stride];
    mean /= n;
    int first = -1, count = 0;
    double first_t = 0, last_t = 0;
    for (int i = 1; i < n; ++i) {
        double a = x[(i - 1) * stride] - mean, b = x[i * stride] - mean;
        if (a < 0 && b >= 0) {
            double t = (i - 1) + a / (a - b);
            if (first < 0) {
                first = i;
                first_t = t;
            } else {
                last_t = t;
                ++count;
            }
        }
    }
    if (count < 2) return 0;
    return count * (double)rate / (last_t - first_t);
}

/* ---- SHA-256 (FIPS 180-4), for the rhythm ROM check ------------------------------------------------------------- */

static uint32_t ror(uint32_t x, int n) { return x >> n | x << (32 - n); }

static void sha256_hex(const uint8_t* data, size_t len, char out[65]) {
    static const uint32_t k[64] = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01,
        0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
        0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08,
        0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};
    uint32_t h[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    const size_t total = ((len + 9 + 63) / 64) * 64;
    for (size_t off = 0; off < total; off += 64) {
        uint8_t block[64];
        for (int i = 0; i < 64; ++i) {
            const size_t p = off + (size_t)i;
            block[i] = p < len ? data[p] : p == len ? 0x80 : 0;
        }
        if (off + 64 == total) {
            const uint64_t bits = (uint64_t)len * 8;
            for (int i = 0; i < 8; ++i) block[56 + i] = (uint8_t)(bits >> (56 - 8 * i));
        }
        uint32_t w[64];
        for (int i = 0; i < 16; ++i) w[i] = (uint32_t)block[4 * i] << 24 | block[4 * i + 1] << 16 | block[4 * i + 2] << 8 | block[4 * i + 3];
        for (int i = 16; i < 64; ++i) {
            const uint32_t s0 = ror(w[i - 15], 7) ^ ror(w[i - 15], 18) ^ (w[i - 15] >> 3);
            const uint32_t s1 = ror(w[i - 2], 17) ^ ror(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
        for (int i = 0; i < 64; ++i) {
            const uint32_t t1 = hh + (ror(e, 6) ^ ror(e, 11) ^ ror(e, 25)) + ((e & f) ^ (~e & g)) + k[i] + w[i];
            const uint32_t t2 = (ror(a, 2) ^ ror(a, 13) ^ ror(a, 22)) + ((a & b) ^ (a & c) ^ (b & c));
            hh = g, g = f, f = e, e = d + t1, d = c, c = b, b = a, a = t1 + t2;
        }
        h[0] += a, h[1] += b, h[2] += c, h[3] += d, h[4] += e, h[5] += f, h[6] += g, h[7] += hh;
    }
    for (int i = 0; i < 8; ++i) snprintf(out + 8 * i, 9, "%08x", h[i]);
}

/* ---- the EPSM unit on its own ------------------------------------------------------------------------------------- */

static void reg_write(NesEpsm* e, uint32_t t, int bank, uint8_t reg, uint8_t value) {
    nes_epsm_write(e, t, (uint16_t)(0x401C + 2 * bank), reg);
    nes_epsm_write(e, t, (uint16_t)(0x401D + 2 * bank), value);
}

/* FM channel `ch` (0..5) with algorithm 7 (every operator a carrier), MUL 1, AR 31, sustain at full: operators 1 to
 * `loud` (1..4) at TL 0, the rest at TL 127; `fb` feedback, pan bits `pan` ($C0 both, $80 left, $40 right),
 * block/F-number, keyed on. All its operators start at phase 0 together, so `loud` of them sum in phase. */
static void fm_carriers_ch(NesEpsm* e, int ch, int block, int fnum, uint8_t pan, int fb, int loud) {
    const int bank = ch / 3, sub = ch % 3;
    reg_write(e, 0, 0, 0x22, 0x00);
    reg_write(e, 0, 0, 0x29, 0x80); /* six FM channels (the driver's init does the same) */
    reg_write(e, 0, bank, (uint8_t)(0xB0 + sub), (uint8_t)(fb << 3 | 7));
    reg_write(e, 0, bank, (uint8_t)(0xB4 + sub), pan);
    static const uint8_t ops[4] = {0x00, 0x08, 0x04, 0x0C}; /* operators 1, 2, 3, 4 (register order 1, 3, 2, 4) */
    for (int k = 0; k < 4; ++k) {
        reg_write(e, 0, bank, (uint8_t)(0x30 + ops[k] + sub), 0x01);
        reg_write(e, 0, bank, (uint8_t)(0x40 + ops[k] + sub), k < loud ? 0x00 : 0x7F);
        reg_write(e, 0, bank, (uint8_t)(0x50 + ops[k] + sub), 0x1F);
        reg_write(e, 0, bank, (uint8_t)(0x60 + ops[k] + sub), 0x00);
        reg_write(e, 0, bank, (uint8_t)(0x70 + ops[k] + sub), 0x00);
        reg_write(e, 0, bank, (uint8_t)(0x80 + ops[k] + sub), 0x0F);
        reg_write(e, 0, bank, (uint8_t)(0x90 + ops[k] + sub), 0x00);
    }
    reg_write(e, 0, bank, (uint8_t)(0xA4 + sub), (uint8_t)(block << 3 | fnum >> 8));
    reg_write(e, 0, bank, (uint8_t)(0xA0 + sub), (uint8_t)fnum);
    reg_write(e, 0, 0, 0x28, (uint8_t)(0xF0 | bank << 2 | sub));
}

/* A sine: only operator 1 audible. */
static void fm_sine_ch(NesEpsm* e, int ch, int block, int fnum, uint8_t pan, int fb) {
    fm_carriers_ch(e, ch, block, fnum, pan, fb, 1);
}

static void fm_sine(NesEpsm* e, int block, int fnum, uint8_t pan, int fb) { fm_sine_ch(e, 0, block, fnum, pan, fb); }

static int g_raw_peak; /* the largest |blip sample| (either buffer) of the last unit_render */

/* Renders `frames` NTSC frames of the unit into interleaved stereo floats (mixer level per sample); returns samples.
 * Each render is isolated: both buffers start cleared (as the machine's reset clears them), so nothing an earlier
 * render left in blip's integrator leaks into this one. */
static int unit_render(NesEpsm* e, blip_t* l, blip_t* r, int frames, float* out, int cap) {
    static short sl[4096], sr[4096];
    int got = 0;
    g_raw_peak = 0;
    blip_clear(l);
    blip_clear(r);
    nes_epsm_set_output(e, l, r);
    for (int f = 0; f < frames; ++f) {
        nes_epsm_end_frame(e, FRAME);
        blip_end_frame(l, FRAME);
        blip_end_frame(r, FRAME);
        const int n = blip_read_samples(l, sl, 4096, 0);
        blip_read_samples(r, sr, 4096, 0);
        for (int i = 0; i < n; ++i) {
            if (abs(sl[i]) > g_raw_peak) g_raw_peak = abs(sl[i]);
            if (abs(sr[i]) > g_raw_peak) g_raw_peak = abs(sr[i]);
        }
        for (int i = 0; i < n && got < cap; ++i, ++got) {
            out[2 * got] = (float)(sl[i] / NES_EPSM_AMP_SCALE);
            out[2 * got + 1] = (float)(sr[i] / NES_EPSM_AMP_SCALE);
        }
    }
    nes_epsm_set_output(e, NULL, NULL);
    return got;
}

static double side_peak(const float* x, int n, int side) {
    double p = 0;
    for (int i = 0; i < n; ++i) p = fmax(p, fabs(x[2 * i + side]));
    return p;
}

static void test_unit(void) {
    printf("EPSM unit: bus, clock, FM and SSG pitch, pans, mutes, reset\n");
    static NesEpsm e, f;
    EXPECT(nes_epsm_init(&e) == 0 && nes_epsm_init(&f) == 0, "two chips created");
    blip_t* l = blip_new(RATE / 10);
    blip_t* r = blip_new(RATE / 10);
    blip_set_rates(l, CPU_HZ, RATE);
    blip_set_rates(r, CPU_HZ, RATE);

    /* Bus: $401C/$401D address/data with A1 = 0, $401E/$401F with A1 = 1; nothing else. */
    EXPECT(nes_epsm_register(0x401C) && nes_epsm_register(0x401F) && !nes_epsm_register(0x401B) && !nes_epsm_register(0x4020) &&
               !nes_epsm_register(0x4017),
           "EPSM registers: $401C-$401F");
    nes_epsm_write(&e, 10, 0x401C, 0xB4);
    nes_epsm_write(&e, 20, 0x401D, 0x80);
    nes_epsm_write(&e, 30, 0x401E, 0xB4);
    nes_epsm_write(&e, 40, 0x401F, 0x40);
    nes_epsm_write(&e, 50, 0x4020, 0x12);
    EXPECT(e.reg[0][0xB4] == 0x80 && e.reg[1][0xB4] == 0x40 && e.select[0] == 0xB4 && e.select[1] == 0xB4,
           "A1 = 0 and A1 = 1 register banks decoded separately ($%02X, $%02X)", e.reg[0][0xB4], e.reg[1][0xB4]);
    EXPECT(e.time == 40 && e.reg[0][0x12] == 0 && e.reg[1][0x12] == 0, "a write outside $401C-$401F is ignored");

    /* Clock: 8 MHz / 144 per native sample = 144 x 1789773 / 8000000 CPU cycles, counted exactly. */
    nes_epsm_reset(&e);
    nes_epsm_run(&e, FRAME);
    const uint64_t units = (uint64_t)FRAME * NES_EPSM_CYCLE_UNITS;
    EXPECT(e.phase == units % NES_EPSM_SAMPLE_UNITS && e.time == FRAME, "after %u cycles: %llu samples, phase %u units", FRAME,
           (unsigned long long)(units / NES_EPSM_SAMPLE_UNITS), e.phase);
    nes_epsm_end_frame(&e, FRAME);
    EXPECT(e.time == 0, "a frame end restarts at cycle 0, keeping the phase");

    /* FM pitch: F-number 618, block 4 = 618 x 2^4 x (8e6 / 144) / 2^21 Hz. */
    static float pcm[2 * RATE * 2];
    nes_epsm_reset(&e);
    fm_sine(&e, 4, 618, 0xC0, 0);
    int n = unit_render(&e, l, r, 60, pcm, RATE * 2);
    const double want_fm = 618.0 * 16.0 * CHIP_RATE / 2097152.0;
    double hz = zero_cross_hz(pcm + 2 * (RATE / 10), n - RATE / 10, 2, RATE);
    EXPECT(fabs(hz / want_fm - 1) < 0.002, "FM sine F=618 B=4: %.2f Hz, want %.2f", hz, want_fm);
    const double peak_fm = side_peak(pcm + 2 * (RATE / 10), n - RATE / 10, 0);
    /* A full-level operator: ymfm's 13-bit OPNA sum, +-4095 at most, x NES_EPSM_FM_STEP. */
    EXPECT(peak_fm > 0.6 * 4095 * NES_EPSM_FM_STEP && peak_fm < 1.05 * 4095 * NES_EPSM_FM_STEP,
           "FM full level: peak %.4f of the 2A03 mixer scale (FamiStudio's balance %.4f)", peak_fm, 4095 * NES_EPSM_FM_STEP);
    printf("  FM sine %.2f Hz, peak %.4f (2A03 pulse at 15 = %.4f)\n", hz, peak_fm, 95.88 / (8128.0 / 15.0 + 100.0));

    /* Pans: B4 bit 7 left, bit 6 right. */
    nes_epsm_reset(&e);
    fm_sine(&e, 4, 618, 0x80, 0);
    n = unit_render(&e, l, r, 20, pcm, RATE * 2);
    EXPECT(side_peak(pcm, n, 0) > 0.01 && side_peak(pcm, n, 1) < 1e-6, "B4 = $80: left only (%.4f / %.6f)", side_peak(pcm, n, 0),
           side_peak(pcm, n, 1));
    nes_epsm_reset(&e);
    fm_sine(&e, 4, 618, 0x40, 0);
    n = unit_render(&e, l, r, 20, pcm, RATE * 2);
    EXPECT(side_peak(pcm, n, 1) > 0.01 && side_peak(pcm, n, 0) < 1e-6, "B4 = $40: right only");

    /* Six FM channels at full level, keyed in the same sample, sum to six times one channel: about 1.8 x the 2A03's full
     * scale, past the point where buffers at the 2A03's own amplitude scale would clip (32767 / NES_APU_AMP_SCALE = 1.09). */
    nes_epsm_reset(&e);
    for (int ch = 0; ch < 6; ++ch) fm_sine_ch(&e, ch, 4, 618, 0xC0, 0);
    n = unit_render(&e, l, r, 30, pcm, RATE * 2);
    const double peak_six = side_peak(pcm + 2 * (RATE / 10), n - RATE / 10, 0);
    EXPECT(peak_six > 5.7 * peak_fm && peak_six < 6.3 * peak_fm && peak_six > 32767.0 / NES_APU_AMP_SCALE,
           "six in-phase FM channels: peak %.4f = %.2f x one channel (%.4f), past the 2A03-scale clip point", peak_six,
           peak_six / peak_fm, peak_fm);

    /* The FM extreme the headroom is derived from (nes_epsm.h): six channels of algorithm 7 with all four carriers at
     * TL 0, in phase. The chip reaches +-NES_EPSM_FM_PEAK (24 x one operator, ymfm adds carriers without clipping) and
     * never exceeds it; through the unit's buffers that is 24 x one channel, far inside blip's 16 bits. */
    nes_epsm_reset(&e);
    for (int ch = 0; ch < 6; ++ch) fm_carriers_ch(&e, ch, 4, 618, 0xC0, 0, 4);
    const double fm_bound = NES_EPSM_FM_PEAK * NES_EPSM_FM_STEP;
    double lo = 0, hi = 0;
    int lr_same = 1;
    for (int fr = 0; fr < 30; ++fr) {
        for (uint32_t t = 32; t <= FRAME; t += 32) { /* at most one native sample (32.216 cycles) per step */
            nes_epsm_run(&e, t);
            lo = fmin(lo, e.level[0]);
            hi = fmax(hi, e.level[0]);
            lr_same &= e.level[0] == e.level[1];
        }
        nes_epsm_end_frame(&e, FRAME);
    }
    EXPECT(hi > 0.98 * fm_bound && hi <= fm_bound * (1 + 1e-6) && lo < -0.98 * fm_bound && lo >= -fm_bound * (1 + 1e-6) && lr_same,
           "six channels x four carriers: levels %.4f..%.4f, bound +-%.4f (NES_EPSM_FM_PEAK)", lo, hi, fm_bound);
    nes_epsm_reset(&e);
    for (int ch = 0; ch < 6; ++ch) fm_carriers_ch(&e, ch, 4, 618, 0xC0, 0, 4);
    n = unit_render(&e, l, r, 30, pcm, RATE * 2);
    const double peak_all = side_peak(pcm + 2 * (RATE / 10), n - RATE / 10, 0);
    EXPECT(peak_all > 22.8 * peak_fm && peak_all < 25.2 * peak_fm && g_raw_peak <= NES_EPSM_BUFFER_PEAK,
           "rendered: peak %.4f = %.2f x one channel, largest buffer sample %d (bound %.0f, clamp 32767)", peak_all,
           peak_all / peak_fm, g_raw_peak, NES_EPSM_BUFFER_PEAK);

    /* The SSG extreme: three channels at amplitude 15, tone and noise off (a constant level): exactly NES_EPSM_SSG_HIGH. */
    NesEpsmChip* ssg_chip = nes_epsm_chip_create();
    static const uint8_t ssg_full[][2] = {{0x07, 0x3F}, {0x08, 0x0F}, {0x09, 0x0F}, {0x0A, 0x0F}};
    for (size_t i = 0; i < sizeof ssg_full / sizeof ssg_full[0]; ++i) {
        nes_epsm_chip_write(ssg_chip, 0, ssg_full[i][0]);
        nes_epsm_chip_write(ssg_chip, 1, ssg_full[i][1]);
    }
    NesEpsmOutput so;
    int ssg_high = 1;
    for (int i = 0; i < 1000; ++i) {
        nes_epsm_chip_generate(ssg_chip, 0, &so);
        ssg_high &= so.ssg == NES_EPSM_SSG_HIGH && so.fm[0] == 0 && so.fm[1] == 0;
    }
    EXPECT(ssg_high, "three SSG channels at 15: %d every sample (NES_EPSM_SSG_HIGH %d)", so.ssg, NES_EPSM_SSG_HIGH);
    nes_epsm_chip_destroy(ssg_chip);

    /* SSG pitch: 2 MHz / (16 x TP); TP 284 = 440.1 Hz; centred. */
    nes_epsm_reset(&e);
    reg_write(&e, 0, 0, 0x00, 284 & 0xFF);
    reg_write(&e, 0, 0, 0x01, 284 >> 8);
    reg_write(&e, 0, 0, 0x07, 0x3E);
    reg_write(&e, 0, 0, 0x08, 0x0F);
    n = unit_render(&e, l, r, 60, pcm, RATE * 2);
    hz = zero_cross_hz(pcm + 2 * (RATE / 10), n - RATE / 10, 2, RATE);
    EXPECT(fabs(hz / (125000.0 / 284) - 1) < 0.003, "SSG A TP=284: %.2f Hz, want %.2f", hz, 125000.0 / 284);
    EXPECT(fabs(side_peak(pcm, n, 0) - side_peak(pcm, n, 1)) < 1e-6 && side_peak(pcm, n, 0) > 0.05, "SSG centred (%.4f)",
           side_peak(pcm, n, 0));

    /* Mutes leave the channel out of the mix only: with feedback (state kept by the chip's own output pass) a muted
     * then unmuted FM channel continues exactly as one never muted. */
    nes_epsm_reset(&e);
    nes_epsm_reset(&f);
    fm_sine(&e, 3, 700, 0xC0, 6);
    fm_sine(&f, 3, 700, 0xC0, 6);
    nes_epsm_set_mute(&e, (uint16_t)NES_EPSM_MUTE_FM(0));
    int muted_silent = 1, differs = 0, frames_same = 1;
    for (int fr = 0; fr < 40; ++fr) {
        if (fr == 20) nes_epsm_set_mute(&e, 0);
        for (uint32_t t = 33; t <= FRAME; t += 33) {
            nes_epsm_run(&e, t);
            nes_epsm_run(&f, t);
            if (fr < 20) {
                muted_silent &= e.level[0] == 0.0f && e.level[1] == 0.0f;
                differs |= f.level[0] != 0.0f;
            } else {
                frames_same &= e.level[0] == f.level[0] && e.level[1] == f.level[1];
            }
        }
        nes_epsm_end_frame(&e, FRAME);
        nes_epsm_end_frame(&f, FRAME);
    }
    EXPECT(muted_silent && differs, "a muted FM channel is left out of the mix while it plays");
    EXPECT(frames_same, "unmuted, it continues sample for sample as the never-muted chip (feedback state kept)");

    /* Reset = a fresh chip: LFO, envelope counter, SSG counters and noise all back to power-on. The program sounds:
     * algorithm 2 (operator 4 the carrier, at offset $C, with LFO AM on; operator 1 with feedback 7 and operator 3
     * modulating it), LFO on with AM and PM sensitivity, the SSG envelope (triangle) and noise on channel A. */
    NesEpsmChip* a = nes_epsm_chip_create();
    NesEpsmChip* b = nes_epsm_chip_create();
    static const uint8_t prog[][3] = {
        {0, 0x22, 0x0B}, {0, 0xB0, 0x3A}, {0, 0xB4, 0xF7}, {0, 0x30, 0x02}, {0, 0x40, 0x10}, {0, 0x50, 0x1F}, {0, 0x60, 0x85},
        {0, 0x80, 0x37}, {0, 0x34, 0x01}, {0, 0x44, 0x00}, {0, 0x54, 0x1F}, {0, 0x84, 0x0F}, {0, 0x3C, 0x01}, {0, 0x4C, 0x04},
        {0, 0x5C, 0x1F}, {0, 0x6C, 0x80}, {0, 0x8C, 0x0F}, {0, 0xA4, 0x22}, {0, 0xA0, 0x69}, {0, 0x28, 0xF0}, {0, 0x06, 0x05},
        {0, 0x07, 0x30}, {0, 0x08, 0x10}, {0, 0x0B, 0x40}, {0, 0x0C, 0x00}, {0, 0x0D, 0x0E}, {0, 0x00, 0x7F}, {0, 0x29, 0x80},
        {1, 0xB4, 0xC0},
    };
    NesEpsmOutput oa, ob;
    for (size_t i = 0; i < sizeof prog / sizeof prog[0]; ++i) {
        nes_epsm_chip_write(a, 2 * prog[i][0], prog[i][1]);
        nes_epsm_chip_write(a, 2 * prog[i][0] + 1, prog[i][2]);
    }
    int loud_before = 0;
    for (int i = 0; i < 30001; ++i) {
        nes_epsm_chip_generate(a, 0, &oa);
        loud_before |= oa.fm[0] != 0 && oa.fm[1] != 0 && oa.ssg != 0;
    }
    nes_epsm_chip_reset(a);
    for (size_t i = 0; i < sizeof prog / sizeof prog[0]; ++i) {
        for (int c = 0; c < 2; ++c) {
            NesEpsmChip* x = c ? b : a;
            nes_epsm_chip_write(x, 2 * prog[i][0], prog[i][1]);
            nes_epsm_chip_write(x, 2 * prog[i][0] + 1, prog[i][2]);
        }
    }
    int first_diff = -1, loud = 0;
    for (int i = 0; i < 20000; ++i) {
        nes_epsm_chip_generate(a, 0, &oa);
        nes_epsm_chip_generate(b, 0, &ob);
        if (first_diff < 0 && memcmp(&oa, &ob, sizeof oa)) first_diff = i;
        loud |= oa.fm[0] != 0 && oa.fm[1] != 0 && oa.ssg != 0;
    }
    EXPECT(loud_before && loud, "the reset program sounds: FM (both sides) and the SSG (before %d, after %d)", loud_before, loud);
    EXPECT(first_diff < 0,
           "after 30001 samples with LFO, FM and the SSG envelope running, reset = a fresh chip, sample for sample (first "
           "difference at sample %d)",
           first_diff);
    nes_epsm_chip_destroy(a);
    nes_epsm_chip_destroy(b);
    nes_epsm_free(&e);
    nes_epsm_free(&f);
    blip_delete(l);
    blip_delete(r);
}

/* No ROM installed (this runs before any install): a rhythm key-on is silent, says so, and still runs to its end. */
static void test_rhythm_without_rom(void) {
    printf("EPSM rhythm without the ROM: silent, flagged\n");
    EXPECT(!ym2608_rhythm_rom_installed() && ym2608_rhythm_rom() == NULL, "no rhythm ROM installed yet");
    static uint8_t junk[YM2608_RHYTHM_ROM_SIZE + 1];
    EXPECT(ym2608_rhythm_rom_install(NULL, YM2608_RHYTHM_ROM_SIZE) == -1, "NULL refused");
    EXPECT(ym2608_rhythm_rom_install(junk, YM2608_RHYTHM_ROM_SIZE - 1) == -1 && ym2608_rhythm_rom_install(junk, sizeof junk) == -1,
           "8191 and 8193 bytes refused");
    EXPECT(!ym2608_rhythm_rom_installed(), "still none installed");
    NesEpsmChip* c = nes_epsm_chip_create();
    nes_epsm_chip_write(c, 0, 0x11), nes_epsm_chip_write(c, 1, 0x3F); /* rhythm total level: full */
    nes_epsm_chip_write(c, 0, 0x18), nes_epsm_chip_write(c, 1, 0xDF); /* bass drum: both sides, level 31 */
    EXPECT(!nes_epsm_chip_rhythm_missing(c), "no flag before a key-on");
    nes_epsm_chip_write(c, 0, 0x10), nes_epsm_chip_write(c, 1, 0x01); /* key on the bass drum */
    EXPECT(nes_epsm_chip_rhythm_missing(c) && nes_epsm_chip_rhythm_silent(c) == 1 && nes_epsm_chip_rhythm_playing(c) == 1,
           "the key-on finds no ROM: flagged, the drum silent but playing");
    NesEpsmOutput o;
    int quiet = 1, samples = 0;
    while (nes_epsm_chip_rhythm_playing(c) && samples < 100000) {
        nes_epsm_chip_generate(c, 0, &o);
        quiet &= o.rhythm[0] == 0 && o.rhythm[1] == 0 && o.fm[0] == 0 && o.ssg == 0;
        ++samples;
    }
    EXPECT(quiet && samples > 0 && samples < 100000, "no sound, and the drum reaches its end address after %d samples", samples);
    nes_epsm_chip_reset(c);
    EXPECT(!nes_epsm_chip_rhythm_missing(c) && !nes_epsm_chip_rhythm_silent(c), "reset clears the flag");
    nes_epsm_chip_destroy(c);
}

/* The user's ROM from THOR_RHYTHM_ROM (never part of the repository): install rules, then drums with sound. */
static void test_rhythm_with_rom(void) {
    const char* path = getenv("THOR_RHYTHM_ROM");
    if (!path || !path[0]) {
        printf("EPSM rhythm ROM checks skipped (THOR_RHYTHM_ROM unset; the ROM is not part of Thor Tracker)\n");
        return;
    }
    printf("EPSM rhythm with the ROM from THOR_RHYTHM_ROM\n");
    static uint8_t rom[YM2608_RHYTHM_ROM_SIZE + 1];
    FILE* f = fopen(path, "rb");
    EXPECT(f != NULL, "%s opens", path);
    if (!f) return;
    const size_t size = fread(rom, 1, sizeof rom, f);
    fclose(f);
    char hex[65];
    sha256_hex(rom, size, hex);
    EXPECT(size == YM2608_RHYTHM_ROM_SIZE && !strcmp(hex, ROM_SHA256), "the YM2608 rhythm ROM: %zu bytes, SHA-256 %s", size, hex);
    if (size != YM2608_RHYTHM_ROM_SIZE || strcmp(hex, ROM_SHA256)) return;
    EXPECT(ym2608_rhythm_rom_install(rom, size) == 0 && ym2608_rhythm_rom_installed(), "installed");
    EXPECT(ym2608_rhythm_rom() != NULL && !memcmp(ym2608_rhythm_rom(), rom, YM2608_RHYTHM_ROM_SIZE), "the installed bytes are the ROM");
    EXPECT(ym2608_rhythm_rom_install(rom, size) == 0, "the same bytes again: no-op");
    static uint8_t other[YM2608_RHYTHM_ROM_SIZE];
    memcpy(other, rom, sizeof other);
    other[100] ^= 0x55;
    EXPECT(ym2608_rhythm_rom_install(other, sizeof other) == -1 && ym2608_rhythm_rom_installed() &&
               !memcmp(ym2608_rhythm_rom(), rom, YM2608_RHYTHM_ROM_SIZE),
           "other bytes after an install refused; the installed ROM stays");
    g_rom = 1;
    static const uint8_t pans[3] = {0xDF, 0x9F, 0x5F}; /* both, left, right */
    for (int p = 0; p < 3; ++p) {
        NesEpsmChip* c = nes_epsm_chip_create();
        nes_epsm_chip_write(c, 0, 0x11), nes_epsm_chip_write(c, 1, 0x3F);
        nes_epsm_chip_write(c, 0, 0x18), nes_epsm_chip_write(c, 1, pans[p]);
        nes_epsm_chip_write(c, 0, 0x10), nes_epsm_chip_write(c, 1, 0x01);
        NesEpsmOutput o;
        int peak[2] = {0, 0}, samples = 0;
        while (nes_epsm_chip_rhythm_playing(c) && samples < 100000) {
            nes_epsm_chip_generate(c, 0, &o);
            for (int s = 0; s < 2; ++s) peak[s] = abs(o.rhythm[s]) > peak[s] ? abs(o.rhythm[s]) : peak[s];
            ++samples;
        }
        const int want_l = p != 2, want_r = p != 1;
        EXPECT(!nes_epsm_chip_rhythm_missing(c) && (peak[0] > 100) == want_l && (peak[1] > 100) == want_r && (peak[0] == 0) == !want_l &&
                   (peak[1] == 0) == !want_r,
               "bass drum, pan $%02X: peaks %d / %d, %d samples", pans[p], peak[0], peak[1], samples);
        nes_epsm_chip_destroy(c);
    }
    /* All six drums keyed together at full level, both sides: the sum stays inside the rhythm range the headroom is
     * derived from (nes_epsm.h, any ROM); the ROM's own extremes are printed for reference. */
    NesEpsmChip* c = nes_epsm_chip_create();
    nes_epsm_chip_write(c, 0, 0x11), nes_epsm_chip_write(c, 1, 0x3F);
    for (int d = 0; d < NES_EPSM_RHYTHM; ++d) nes_epsm_chip_write(c, 0, (uint8_t)(0x18 + d)), nes_epsm_chip_write(c, 1, 0xDF);
    nes_epsm_chip_write(c, 0, 0x10), nes_epsm_chip_write(c, 1, 0x3F);
    NesEpsmOutput o;
    int lo = 0, hi = 0, samples = 0, sides_same = 1;
    while (nes_epsm_chip_rhythm_playing(c) && samples < 100000) {
        nes_epsm_chip_generate(c, 0, &o);
        lo = o.rhythm[0] < lo ? o.rhythm[0] : lo;
        hi = o.rhythm[0] > hi ? o.rhythm[0] : hi;
        sides_same &= o.rhythm[0] == o.rhythm[1];
        ++samples;
    }
    EXPECT(lo < 0 && hi > 0 && lo >= NES_EPSM_RHYTHM_LOW && hi <= NES_EPSM_RHYTHM_HIGH && sides_same && samples < 100000,
           "six drums at full level: %d..%d, inside %d..%d (%d samples)", lo, hi, NES_EPSM_RHYTHM_LOW, NES_EPSM_RHYTHM_HIGH, samples);
    printf("  six drums at full level: %d..%d of the any-ROM range %d..%d\n", lo, hi, NES_EPSM_RHYTHM_LOW, NES_EPSM_RHYTHM_HIGH);
    nes_epsm_chip_destroy(c);
}

/* ---- the EPSM build playing the test song -------------------------------------------------------------------------- */

static int load_fixture(NesImage* image, const uint8_t* bytes, size_t size, const char* name) {
    memset(image, 0, sizeof *image);
    memcpy(image->bytes, bytes, size);
    int r = nm_load_image(image, &nes_player_epsm);
    EXPECT(r == 0, "%s loads (%s at %u)", name, image->info.problem ? image->info.problem : "-", image->info.problem_at);
    return r;
}

static void song_row(int k, int* order, int* row, int* frames) {
    if (k >= FIRST_PASS_ROWS) k = EPSM_TEST_LOOP * EPSM_TEST_ROWS + (k - FIRST_PASS_ROWS) % LOOP_ROWS;
    *order = k / EPSM_TEST_ROWS;
    *row = k % EPSM_TEST_ROWS;
    *frames = nes_epsm_speed[*order][*row];
}

static int song_frames(int rows) {
    int total = 0, o, r, f;
    for (int k = 0; k < rows; ++k) {
        song_row(k, &o, &r, &f);
        total += f;
    }
    return total;
}

static int row_frame(int order, int row) { return song_frames(order * EPSM_TEST_ROWS + row); }

static int table16(NesMachine* m, uint16_t lsb, uint16_t msb, int index) {
    return nm_peek(m, (uint16_t)(lsb + index)) | nm_peek(m, (uint16_t)(msb + index)) << 8;
}

static int ssg_table(NesMachine* m, int note) {
    return table16(m, NES_EPSM_FAMISTUDIO_EPSM_S_NOTE_TABLE_LSB, NES_EPSM_FAMISTUDIO_EPSM_S_NOTE_TABLE_MSB, note);
}

static int fm_table(NesMachine* m, int note) {
    return table16(m, NES_EPSM_FAMISTUDIO_EPSM_NOTE_TABLE_LSB, NES_EPSM_FAMISTUDIO_EPSM_NOTE_TABLE_MSB, note);
}

/* famistudio_update_epsm_fm_channel_sound: a 16-bit pitch halved until below $200 (each halving one block up), then
 * F-number = pitch << 2. */
static void fm_normalize(int pitch, int* fnum, int* block) {
    int b = 0;
    while (pitch >= 0x200) {
        pitch >>= 1;
        ++b;
    }
    *fnum = pitch << 2;
    *block = b;
}

static double fm_hz(int fnum, int block) { return fnum * (double)(1 << block) * CHIP_RATE / 2097152.0; }

/* The F-number/block an FM channel (0..5) last had written. */
static void fm_regs(const NesEpsm* e, int k, int* fnum, int* block) {
    const uint8_t* bank = e->reg[k / 3];
    *fnum = (bank[0xA4 + k % 3] & 7) << 8 | bank[0xA0 + k % 3];
    *block = bank[0xA4 + k % 3] >> 3 & 7;
}

static int ssg_period(const NesEpsm* e, int k) { return e->reg[0][2 * k] | (e->reg[0][2 * k + 1] & 15) << 8; }

/* Per-frame sound bus writes (APU and EPSM) as (address low, address high, value). */
typedef struct {
    int frames;
    uint8_t (*w)[3 * TRACE_WRITES];
    int* n;
} Trace;

static void trace_alloc(Trace* t, int frames) {
    t->frames = frames;
    t->w = calloc((size_t)frames, 3 * TRACE_WRITES);
    t->n = (int*)calloc((size_t)frames, sizeof(int));
}

static void trace_free(Trace* t) {
    free(t->w);
    free(t->n);
}

static void record(NesMachine* m, Trace* t, int f) { t->n[f] = nm_write_log(m, t->w[f], TRACE_WRITES); }

static int same_frame(const Trace* a, const Trace* b, int f) {
    return a->n[f] == b->n[f] && !memcmp(a->w[f], b->w[f], (size_t)a->n[f] * 3);
}

static int first_difference(const Trace* a, const Trace* b, int from, int to) {
    for (int f = from; f < to; ++f) {
        if (!same_frame(a, b, f)) return f;
    }
    return -1;
}

static void play_trace(NesMachine* m, const NesImage* image, Trace* t) {
    int mode;
    nm_start(m, image, 0, 0, 0, &mode);
    nm_set_apu_logging(m, 1);
    for (int f = 0; f < t->frames; ++f) {
        nm_tick_silent(m);
        record(m, t, f);
    }
}

/* The first song byte of channel `c`'s pattern at `order` (Thor layout), as an image byte offset. */
static uint32_t pattern_start(const NesImage* image, int c, int order) {
    const NesImageInfo* i = &image->info;
    const uint32_t base = i->player->song_base;
    const uint8_t* s = nm_region(image);
    const uint16_t e = (uint16_t)(i->streams[c] + 4 * order);
    return NM_REGION_OFFSET + (nm_rd16(s + (e + 2 - base)) - base);
}

/* An image byte offset in `image`'s channel `c` patterns (first pass) whose byte is a note ($01-$3F), or 0. */
static uint32_t find_note(const NesImage* image, int c) {
    for (int o = 0; o < image->info.orders; ++o) {
        const uint32_t at = pattern_start(image, c, o);
        for (uint32_t k = at; k < at + 16; ++k) {
            if (image->bytes[k] >= 0x01 && image->bytes[k] < 0x40) return k;
            if (image->bytes[k] < 0x80 && image->bytes[k] >= 0x40) break; /* an opcode with operands: stop scanning */
        }
    }
    return 0;
}

static void expect_rejected(const NesImage* good, uint32_t at, uint8_t value, const char* what) {
    static NesImage bad;
    bad = *good;
    bad.bytes[at] = value;
    EXPECT(nm_load_image(&bad, &nes_player_epsm) != 0, "%s: rejected (%s)", what, bad.info.problem ? bad.info.problem : "loaded!");
}

static void test_images(void) {
    printf("EPSM images: build identity, 20 streams, expansion instruments, patch blocks, EPSM operand rules\n");
    nm_build_raw_image(&g_raw, &nes_player_epsm, nes_epsm_raw, NES_EPSM_RAW_SIZE, NULL, 0, 0, EPSM_TEST_ROWS, EPSM_TEST_ORDERS,
                       EPSM_TEST_LOOP);
    EXPECT(nm_load_image(&g_raw, &nes_player_epsm) == 0, "FamiStudio's raw EPSM export loads (%s)", g_raw.info.problem ? g_raw.info.problem : "-");
    EXPECT(g_raw.info.tempo_ntsc == EPSM_TEST_TEMPO_NTSC, "raw: NTSC tempo word after the 7-byte head and 20 channel pointers (%u)",
           g_raw.info.tempo_ntsc);
    load_fixture(&g_thor, nes_epsm_thor, NES_EPSM_THOR_SIZE, "Thor layout");
    load_fixture(&g_moved, nes_epsm_moved, NES_EPSM_MOVED_SIZE, "moved");
    load_fixture(&g_edited, nes_epsm_edited, NES_EPSM_EDITED_SIZE, "edited");
    const NesImageInfo* i = &g_thor.info;
    EXPECT(i->orders == EPSM_TEST_ORDERS && i->loop == EPSM_TEST_LOOP && i->instruments == EPSM_TEST_INSTRUMENTS &&
               i->tempo_ntsc == EPSM_TEST_TEMPO_NTSC && i->exp_instruments >= NES_EPSM_SONG_BASE,
           "Thor layout: %u orders, loop %u, %u instruments, tempo step %u, expansion table at $%04X", i->orders, i->loop,
           i->instruments, i->tempo_ntsc, i->exp_instruments);
    int streams_ok = 1;
    for (int c = 0; c < CHANNELS; ++c) streams_ok &= i->streams[c] >= NES_EPSM_SONG_BASE;
    EXPECT(streams_ok, "an order stream for each of the 20 channels");
    EXPECT(g_thor.bytes[NES_IMAGE_HEADER_SIZE + 4 * EPSM_TEST_SLEAD_VOLUME_ENVELOPE + 3] == NM_KIND_VOLUME,
           "SLead's volume envelope is directory entry %d", EPSM_TEST_SLEAD_VOLUME_ENVELOPE);

    /* The builds refuse each other's images. */
    static NesImage other;
    memset(&other, 0, sizeof other);
    memcpy(other.bytes, nes_ft_thor, NES_FT_THOR_SIZE);
    EXPECT(nm_load_image(&other, &nes_player_epsm) != 0, "a 2A03 image on the EPSM build: rejected (%s)", other.info.problem);
    memset(&other, 0, sizeof other);
    memcpy(other.bytes, nes_vrc6_thor, NES_VRC6_THOR_SIZE);
    EXPECT(nm_load_image(&other, &nes_player_epsm) != 0, "a VRC6 image on the EPSM build: rejected (%s)", other.info.problem);
    other = g_thor;
    EXPECT(nm_load_image(&other, &nes_player_2a03) != 0, "an EPSM image on the 2A03 build: rejected (%s)", other.info.problem);

    /* Expansion instrument entries: shape, octave, patch pointer. */
    const uint32_t exp = NM_REGION_OFFSET + (i->exp_instruments - NES_EPSM_SONG_BASE);
    const uint32_t j = exp + NM_EXP_INSTRUMENT_SIZE * EPSM_TEST_INSTRUMENT_SLEAD;
    expect_rejected(&g_thor, j + 10, 0x07, "envelope shape $07");
    expect_rejected(&g_thor, j + 11, 0x09, "auto-period octave 9");
    expect_rejected(&g_thor, j + 11, 0xF7, "auto-period octave -9");
    const uint16_t end = (uint16_t)(NES_EPSM_SONG_BASE + i->size);
    static NesImage bad;
    bad = g_thor;
    bad.bytes[j + 14] = (uint8_t)(end - 30), bad.bytes[j + 15] = (uint8_t)((end - 30) >> 8);
    EXPECT(nm_load_image(&bad, &nes_player_epsm) != 0, "a patch block running past the data (30 bytes left): rejected (%s)",
           bad.info.problem);
    bad = g_thor;
    bad.bytes[j + 14] = (uint8_t)(end - 31), bad.bytes[j + 15] = (uint8_t)((end - 31) >> 8);
    EXPECT(nm_load_image(&bad, &nes_player_epsm) == 0, "a patch block in the last 31 bytes of the data loads (any register values)");
    /* Exchanging the mixer and noise envelope words: wrong kinds. */
    bad = g_thor;
    for (int k = 0; k < 2; ++k) {
        const uint8_t t = bad.bytes[j + 6 + k];
        bad.bytes[j + 6 + k] = bad.bytes[j + 8 + k];
        bad.bytes[j + 8 + k] = t;
    }
    EXPECT(nm_load_image(&bad, &nes_player_epsm) != 0, "mixer and noise envelopes exchanged: rejected (%s)", bad.info.problem);

    /* Opcode rules on the channel roles. */
    uint32_t at = find_note(&g_thor, CH_RHYTHM1);
    EXPECT(at != 0, "a note on the bass drum channel");
    if (at) {
        expect_rejected(&g_thor, at, 0x45, "a release ($45) on a rhythm channel");
        expect_rejected(&g_thor, at, 0x5C, "the FM release ($5C) on a rhythm channel");
        expect_rejected(&g_thor, at, 0x4D, "an arpeggio reset on a rhythm channel");
    }
    at = find_note(&g_thor, CH_SSG1);
    EXPECT(at != 0, "a note on SSG 1");
    if (at) {
        expect_rejected(&g_thor, at, 0x5C, "the FM release ($5C) on an SSG");
        expect_rejected(&g_thor, at, 0x4F, "a duty effect on an SSG");
        expect_rejected(&g_thor, at, 0x53, "a phase reset on an SSG");
    }
    at = find_note(&g_thor, CH_FM1);
    EXPECT(at != 0, "a note on FM 1");
    if (at) {
        expect_rejected(&g_thor, at, 0x5D, "an envelope period ($5D) on an FM channel");
        bad = g_thor;
        bad.bytes[at] = 0x5C;
        EXPECT(nm_load_image(&bad, &nes_player_epsm) == 0, "the FM release ($5C) on FM 1 loads (a row terminator like a note)");
    }
    /* $5D on SSG 1 in the song; on the 2A03 build it is not an opcode. */
    const uint8_t* s = nm_region(&g_thor);
    int period_at = -1;
    for (uint32_t a = 0; a + 2 < i->size && period_at < 0; ++a) {
        if (s[a] == 0x5D && s[a + 1] == (800 & 0xFF) && s[a + 2] == (800 >> 8)) period_at = (int)a;
    }
    EXPECT(period_at >= 0, "the Thor layout holds SSG 1's envelope period 800 ($5D $20 $03)");
    bad = g_thor;
    bad.bytes[8] = (uint8_t)NES_SONG_BASE, bad.bytes[9] = (uint8_t)(NES_SONG_BASE >> 8);
    EXPECT(nm_load_image(&bad, &nes_player_epsm) != 0, "the 2A03 song base on the EPSM build: rejected (%s)", bad.info.problem);
    bad = g_thor;
    bad.bytes[10] = (uint8_t)(NES_EPSM_IMAGE_REGION_SIZE + 1), bad.bytes[11] = (uint8_t)((NES_EPSM_IMAGE_REGION_SIZE + 1) >> 8);
    EXPECT(nm_load_image(&bad, &nes_player_epsm) != 0, "data larger than the EPSM region: rejected (%s)", bad.info.problem);
}

static void write_evidence(NesMachine* m, const char* dir, const Trace* raw) {
    char path[512];
    static float wav[NM_MAX_TICK_FRAMES * 2 * 60 * 30];
    int mode, frames = 0;
    /* All channels (with the ROM the drums sound), then the tonal channels only, then (ROM) the drums only. */
    for (int pass = 0; pass < 3; ++pass) {
        if (pass == 2 && !g_rom) break;
        nm_start(m, &g_thor, 0, 0, 0, &mode);
        for (int c = 0; c < CHANNELS; ++c) nm_mute_channel(m, c, pass == 1 ? c >= CH_RHYTHM1 : pass == 2 ? c < CH_RHYTHM1 : 0);
        frames = 0;
        for (int f = 0; f < 60 * 30; ++f) {
            int n = nm_tick(m, wav + 2 * frames);
            frames += n > 0 ? n : 0;
        }
        static const char* names[3] = {"nes_epsm.wav", "nes_epsm_tonal.wav", "nes_epsm_rhythm.wav"};
        snprintf(path, sizeof path, "%s/%s", dir, names[pass]);
        write_wav(path, wav, frames);
        printf("  wrote %s (%.1f s)\n", path, frames / (double)RATE);
    }
    snprintf(path, sizeof path, "%s/nes_epsm_trace.txt", dir);
    FILE* ft = fopen(path, "w");
    if (ft) {
        fprintf(ft, "# frame: APU and EPSM ($401C-$401F) writes as address=value (hex), FamiStudio 4.5.3 EPSM player (FamiTracker tempo), epsm_test\n");
        for (int f = 0; f < raw->frames; ++f) {
            fprintf(ft, "%d:", f);
            for (int k = 0; k < raw->n[f]; ++k) {
                const uint8_t* e = raw->w[f] + 3 * k;
                fprintf(ft, " %02X%02X=%02X", e[1], e[0], e[2]);
            }
            fprintf(ft, "\n");
        }
        fclose(ft);
    }
    snprintf(path, sizeof path, "%s/nes_epsm.nsf", dir);
    FILE* fn = fopen(path, "wb");
    if (fn) {
        uint8_t h[128];
        memset(h, 0, sizeof h);
        memcpy(h, "NESM\x1A", 5);
        h[5] = 1;
        h[6] = 1;
        h[7] = 1;
        h[8] = (uint8_t)NES_EPSM_PLAYER_BASE, h[9] = (uint8_t)(NES_EPSM_PLAYER_BASE >> 8);
        h[10] = (uint8_t)NES_EPSM_PLAYER_INIT, h[11] = (uint8_t)(NES_EPSM_PLAYER_INIT >> 8);
        h[12] = (uint8_t)NES_EPSM_PLAYER_PLAY, h[13] = (uint8_t)(NES_EPSM_PLAYER_PLAY >> 8);
        strcpy((char*)h + 0x0E, "Thor Tracker EPSM test");
        strcpy((char*)h + 0x2E, "Thor Tracker");
        strcpy((char*)h + 0x4E, "FamiStudio 4.5.3 engine");
        h[0x6E] = 0xFF, h[0x6F] = 0x40;
        h[0x78] = 0x20, h[0x79] = 0x4E;
        h[0x7B] = 0x80; /* expansion: EPSM (FamiStudio NsfFile.cs) */
        fwrite(h, 1, sizeof h, fn);
        static uint8_t body[NES_EPSM_SONG_BASE - 0x8000 + NES_EPSM_RAW_SIZE];
        memset(body, 0, sizeof body);
        memcpy(body, nes_epsm_player_code, NES_EPSM_PLAYER_SIZE);
        memcpy(body + (NES_EPSM_SONG_BASE - 0x8000), nes_epsm_raw, NES_EPSM_RAW_SIZE);
        fwrite(body, 1, sizeof body, fn);
        fclose(fn);
    }
}

/* Renders `ticks` frames from (order, row) with only `channel` audible; returns the left samples. */
static int solo(NesMachine* m, int order, int row, int skip, int ticks, int channel, float* mono, int cap) {
    int mode, got = 0;
    nm_start(m, &g_thor, order, row, 12000, &mode);
    for (int c = 0; c < CHANNELS; ++c) nm_mute_channel(m, c, c != channel);
    for (int f = 0; f < skip + ticks; ++f) {
        int n = nm_tick(m, g_out);
        if (f < skip || n <= 0) continue;
        for (int i = 0; i < n && got < cap; ++i) mono[got++] = g_out[2 * i];
    }
    return got;
}

static void test_playback(NesMachine* m, const char* dir) {
    const int pass_rows = FIRST_PASS_ROWS + 2 * LOOP_ROWS;
    const int frames = song_frames(pass_rows);
    printf("FamiStudio's EPSM export of epsm_test.txt: %d rows (first pass + 2 loops), %d frames\n", pass_rows, frames);
    Trace raw, thor;
    trace_alloc(&raw, frames);
    trace_alloc(&thor, frames);
    play_trace(m, &g_raw, &raw);
    int mode, faults = 0, nans = 0, epsm_writes = 0, apu_writes = 0, bad_rows = 0, k = 0, row_start = 0, full = 0;
    uint32_t worst_call = 0;
    double sum2 = 0, peak = 0, t0 = now_us();
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_set_apu_logging(m, 1);
    for (int f = 0; f < frames; ++f) {
        int po, pr, pt;
        nm_position(m, &po, &pr, &pt);
        if (pt == 0) {
            int o, r, len;
            song_row(k++, &o, &r, &len);
            if (po != o || pr != r || f != row_start) ++bad_rows;
            row_start += len;
        }
        int n = nm_tick(m, g_out);
        if (n <= 0) {
            ++faults;
            break;
        }
        record(m, &thor, f);
        full |= thor.n[f] >= TRACE_WRITES;
        for (int w = 0; w < thor.n[f]; ++w) {
            if (thor.w[f][3 * w + 1] == 0x40 && thor.w[f][3 * w] >= 0x1C) ++epsm_writes;
            else ++apu_writes;
        }
        if (nm_last_call_cycles(m) > worst_call) worst_call = nm_last_call_cycles(m);
        for (int w = 0; w < 2 * n; ++w) {
            if (g_out[w] != g_out[w]) ++nans;
            double v = fabs(g_out[w]);
            if (v > peak) peak = v;
            sum2 += g_out[w] * g_out[w];
        }
    }
    const double per_tick = (now_us() - t0) / frames, rms = sqrt(sum2 / (2.0 * frames * 798.7));
    EXPECT(faults == 0 && nans == 0, "no driver faults or NaNs over %d frames", frames);
    EXPECT(!full, "every frame's writes fit the trace (%d)", TRACE_WRITES);
    EXPECT(peak < 1.0, "unclipped: rms %.4f peak %.4f", rms, peak);
    EXPECT(epsm_writes > frames * 3 && apu_writes > frames * 3, "the engine writes the EPSM (%d) and the APU (%d) every frame",
           epsm_writes, apu_writes);
    printf("  rms %.4f, peak %.4f, worst engine frame %u CPU cycles, %.1f us per tick (%.0fx realtime)\n", rms, peak, worst_call,
           per_tick, 16639.0 / per_tick);
    int d = first_difference(&raw, &thor, 0, frames);
    EXPECT(d < 0, "Thor layout writes the same APU and EPSM registers as FamiStudio's export, all %d frames (first difference %d)",
           frames, d);
    EXPECT(bad_rows == 0 && k == pass_rows, "%d row starts at the frames the speeds give, loop to order %d (%d wrong)", k,
           EPSM_TEST_LOOP, bad_rows);

    /* Rhythm key-ons ($401C = $10, then $401D = the drum's bit) at the frames the text gives (first pass). */
    int keys_ok = 1, keys = 0;
    for (int e = 0; e < EPSM_TEST_RHYTHM_EVENTS; ++e) {
        const int f = row_frame(nes_epsm_rhythm_events[e][0], nes_epsm_rhythm_events[e][1]) + nes_epsm_rhythm_events[e][2];
        const uint8_t bit = (uint8_t)(1 << nes_epsm_rhythm_events[e][3]);
        int found = 0;
        for (int w = 1; f < frames && w < raw.n[f] && !found; ++w) {
            const uint8_t* a = raw.w[f] + 3 * (w - 1);
            const uint8_t* b = raw.w[f] + 3 * w;
            found = a[1] == 0x40 && a[0] == 0x1C && a[2] == 0x10 && b[1] == 0x40 && b[0] == 0x1D && b[2] == bit;
        }
        keys += found;
        if (!found) {
            keys_ok = 0;
            printf("  rhythm event %d (order %d row %d +%d, drum %d) not keyed at frame %d\n", e, nes_epsm_rhythm_events[e][0],
                   nes_epsm_rhythm_events[e][1], nes_epsm_rhythm_events[e][2], nes_epsm_rhythm_events[e][3], f);
        }
    }
    EXPECT(keys_ok, "every rhythm note of the text keys its drum at its frame (%d of %d)", keys, EPSM_TEST_RHYTHM_EVENTS);

    /* True pitch from the note tables: FamiStudio EPSM note 58 = A4 (absolute 57). SSG: 2 MHz / (16 x TP), every table
     * entry within half a period unit of 12-TET; FM: the driver's F-number/block of entry 58 at 439.1 Hz. */
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_tick_silent(m);
    int worst = 0;
    for (int note = 13; note <= 96; ++note) {
        const double f = 440.0 * pow(2.0, (note - 58) / 12.0), exact = 125000.0 / f;
        const int err = (int)lround(fabs(ssg_table(m, note) - exact) * 100);
        if (err > worst) worst = err;
    }
    EXPECT(worst <= 51, "SSG note table within half a period unit of 12-TET, note 58 = 440 Hz (worst %.2f)", worst / 100.0);
    int fnum, block;
    fm_normalize(fm_table(m, 58), &fnum, &block);
    EXPECT(fabs(fm_hz(fnum, block) / 440.0 - 1) < 0.005, "FM note 58: F-number %d block %d = %.2f Hz", fnum, block, fm_hz(fnum, block));
    int fm_worst = 0;
    for (int note = 13; note <= 96; ++note) {
        fm_normalize(fm_table(m, note), &fnum, &block);
        const double cents = fabs(1200.0 * log2(fm_hz(fnum, block) / (440.0 * pow(2.0, (note - 58) / 12.0))));
        if (cents > fm_worst) fm_worst = (int)ceil(cents);
    }
    EXPECT(fm_worst <= 10, "FM note table within %d cents of 12-TET over notes 13..96", fm_worst);

    /* SSG 1 heard: its period from the chip registers. */
    static float mono[NM_MAX_TICK_FRAMES * 120];
    const int cap = (int)(sizeof mono / sizeof mono[0]);
    nm_start(m, &g_thor, 0, 0, 12000, &mode);
    nm_tick_silent(m);
    nm_tick_silent(m);
    const int p = ssg_period(nm_epsm(m), 0);
    int got = solo(m, 0, 0, 2, 20, CH_SSG1, mono, cap);
    double hz = zero_cross_hz(mono, got, 1, RATE), want = 125000.0 / p;
    EXPECT(p > 0 && fabs(hz / want - 1) < 0.01, "SSG 1 period %d: %.2f Hz, want %.2f", p, hz, want);

    write_evidence(m, dir, &raw);
    trace_free(&raw);
    trace_free(&thor);
}

/* The chip state a seek must reproduce: the unit's last mixed levels and every FM operator's envelope. */
static int same_chip(const NesMachine* a, const NesEpsm* ref_unit, const int* ref_att) {
    const NesEpsm* e = nm_epsm(a);
    if (e->level[0] != ref_unit->level[0] || e->level[1] != ref_unit->level[1] || e->phase != ref_unit->phase) return 0;
    for (int k = 0; k < 24; ++k) {
        if (nes_epsm_chip_fm_attenuation(e->chip, k / 4, k % 4) != ref_att[k]) return 0;
    }
    return 1;
}

static void test_seek(NesMachine* m) {
    printf("EPSM seek: exact rows from engine state, the chip clocked through\n");
    const int frames = song_frames(FIRST_PASS_ROWS + LOOP_ROWS);
    Trace ref, t;
    trace_alloc(&ref, frames);
    trace_alloc(&t, frames);
    /* Straight play, keeping the chip state after every frame. */
    NesEpsm* levels = calloc((size_t)frames, sizeof *levels);
    int* att = calloc((size_t)frames * 24, sizeof *att);
    int mode;
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_set_apu_logging(m, 1);
    for (int f = 0; f < frames; ++f) {
        nm_tick_silent(m);
        record(m, &ref, f);
        levels[f] = *nm_epsm(m);
        for (int k = 0; k < 24; ++k) att[24 * f + k] = nes_epsm_chip_fm_attenuation(nm_epsm(m)->chip, k / 4, k % 4);
    }
    const int targets[][2] = {{0, 4}, {1, 0}, {1, 8}, {2, 8}, {2, 12}, {3, 15}};
    for (size_t i = 0; i < sizeof targets / sizeof targets[0]; ++i) {
        const int o = targets[i][0], r = targets[i][1], f0 = row_frame(o, r);
        int po, pr, pt;
        mode = -1;
        int res = nm_start(m, &g_thor, o, r, 12000, &mode);
        nm_position(m, &po, &pr, &pt);
        EXPECT(res == NM_OK && mode == NM_SEEK_FROM_START && po == o && pr == r && pt == 0 && nm_frames(m) == (uint32_t)f0,
               "seek %d/%d: from start, %u frames (row starts at %d)", o, r, nm_frames(m), f0);
        EXPECT(f0 > 0 && same_chip(m, &levels[f0 - 1], att + 24 * (f0 - 1)),
               "seek %d/%d: the chip (levels, sample phase, FM envelopes) is straight play's at that frame", o, r);
        nm_set_apu_logging(m, 1);
        int same = 1, chip = 1;
        for (int f = f0; f < f0 + 200 && f < frames; ++f) {
            nm_tick_silent(m);
            record(m, &t, f);
            same &= same_frame(&ref, &t, f);
            chip &= same_chip(m, &levels[f], att + 24 * f);
        }
        EXPECT(same && chip, "seek %d/%d: the same bus writes and chip state as straight play for 200 frames", o, r);
    }
    int o, r, tk;
    EXPECT(nm_start(m, &g_thor, 2, 8, 70, &mode) == NM_OK && mode == NM_SEEK_FROM_ORDER, "small budget: seek from order 2's start");
    nm_position(m, &o, &r, &tk);
    EXPECT(o == 2 && r == 8 && tk == 0, "fallback lands on 2/8 (%d/%d/%d)", o, r, tk);
    EXPECT(nm_start(m, &g_thor, EPSM_TEST_ORDERS, 0, 12000, &mode) == NM_ERR_IMAGE, "order past the song rejected");
    static NesImage other;
    memset(&other, 0, sizeof other);
    memcpy(other.bytes, nes_ft_thor, NES_FT_THOR_SIZE);
    nm_load_image(&other, &nes_player_2a03);
    EXPECT(nm_start(m, &other, 0, 0, 0, &mode) == NM_ERR_IMAGE, "an EPSM machine refuses an image loaded for the 2A03 build");
    free(levels);
    free(att);
    trace_free(&ref);
    trace_free(&t);
}

/* Runs from the start until `pred` holds before a tick (at most `limit` frames); 1 found. */
static int run_until(NesMachine* m, const NesImage* image, int (*pred)(NesMachine*), int limit) {
    int mode;
    nm_start(m, image, 0, 0, 0, &mode);
    for (int f = 0; f < limit; ++f) {
        if (pred(m)) return 1;
        nm_tick_silent(m);
    }
    return 0;
}

static int g_fm_channel; /* the FM channel the predicate found */

static int fm_holds_edited(NesMachine* m) {
    for (int k = 0; k < 6; ++k) {
        if (nm_epsm_instrument(m, CH_FM1 + k) == EPSM_TEST_EDITED_FM_INSTRUMENT && nm_peek(m, (uint16_t)(NES_EPSM_FAMISTUDIO_CHN_NOTE + CH_FM1 + k))) {
            g_fm_channel = k;
            return 1;
        }
    }
    return 0;
}

static int period_from_edited(NesMachine* m) { return nm_epsm_period_instrument(m) == EPSM_TEST_EDITED_SSG_INSTRUMENT; }

static void test_live_apply(NesMachine* m) {
    printf("EPSM live apply: relocation and edits at tick boundaries\n");
    const int frames = song_frames(FIRST_PASS_ROWS + 2 * LOOP_ROWS);
    Trace ref, t;
    trace_alloc(&ref, frames);
    trace_alloc(&t, frames);
    play_trace(m, &g_thor, &ref);
    for (int pass = 0; pass < 2; ++pass) {
        int mode;
        nm_start(m, &g_thor, 0, 0, 0, &mode);
        nm_set_apu_logging(m, 1);
        double worst_us = 0;
        for (int f = 0; f < frames; ++f) {
            double a0 = now_us();
            nm_apply_image(m, pass == 0 ? ((f & 1) ? &g_moved : &g_thor) : &g_thor);
            double us = now_us() - a0;
            if (us > worst_us) worst_us = us;
            nm_tick_silent(m);
            record(m, &t, f);
        }
        int d = first_difference(&ref, &t, 0, frames);
        EXPECT(d < 0, "%s applied before each of %d frames: same writes as straight play (first difference %d)",
               pass == 0 ? "moved data (instruments, patch blocks, envelopes elsewhere)" : "the same image", frames, d);
        printf("  worst apply_image: %.1f us\n", worst_us);
    }

    /* FM patch edit: the channel holding the edited instrument reloads its patch at its next update. */
    EXPECT(run_until(m, &g_thor, fm_holds_edited, 2000), "an FM channel holds instrument %d with a note", EPSM_TEST_EDITED_FM_INSTRUMENT);
    const int k = g_fm_channel;
    const int reg = 0xB0 + k % 3; /* patch byte 0 is $B0 (feedback, algorithm) */
    nm_tick_silent(m);
    const uint8_t before = nm_epsm(m)->reg[k / 3][reg];
    nm_apply_image(m, &g_edited);
    EXPECT(nm_peek(m, (uint16_t)(NES_EPSM_FAMISTUDIO_CHN_EPSM_FM_INSTRUMENT + k)) == EPSM_TEST_EDITED_FM_INSTRUMENT,
           "the edit queues FM %d's patch reload (the engine's own instrument load)", k + 1);
    nm_tick_silent(m);
    nm_tick_silent(m);
    const uint8_t after = nm_epsm(m)->reg[k / 3][reg];
    EXPECT(before != EPSM_TEST_EDITED_PATCH_VALUE && after == EPSM_TEST_EDITED_PATCH_VALUE,
           "FM %d $%02X follows the edited patch ($%02X -> $%02X)", k + 1, reg, before, after);
    /* An identical image queues nothing. */
    nm_apply_image(m, &g_edited);
    EXPECT(nm_peek(m, (uint16_t)(NES_EPSM_FAMISTUDIO_CHN_EPSM_FM_INSTRUMENT + k)) == 0xFF, "an identical image queues no reload");

    /* SSG manual period edit: 4000 -> 2000 from the instrument it came from. */
    EXPECT(run_until(m, &g_thor, period_from_edited, 4000), "the SSGs' manual period comes from instrument %d",
           EPSM_TEST_EDITED_SSG_INSTRUMENT);
    const int period_before = nm_peek(m, NES_EPSM_FAMISTUDIO_EPSM_ENV_PERIOD_LO) | nm_peek(m, NES_EPSM_FAMISTUDIO_EPSM_ENV_PERIOD_HI) << 8;
    nm_apply_image(m, &g_edited);
    const int period_after = nm_peek(m, NES_EPSM_FAMISTUDIO_EPSM_ENV_PERIOD_LO) | nm_peek(m, NES_EPSM_FAMISTUDIO_EPSM_ENV_PERIOD_HI) << 8;
    EXPECT(period_before == 4000 && period_after == EPSM_TEST_EDITED_PERIOD, "manual period follows its edit (%d -> %d)", period_before,
           period_after);

    /* Shape edit on SEnvAuto, if an SSG holds it at some point. */
    int mode, shape_seen = 0, shape_ok = 1;
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    for (int f = 0; f < frames && !shape_seen; ++f) {
        for (int s = 0; s < 3 && !shape_seen; ++s) {
            if (nm_epsm_instrument(m, CH_SSG1 + s) != EPSM_TEST_INSTRUMENT_SENVAUTO) continue;
            shape_seen = 1;
            nm_apply_image(m, &g_edited);
            shape_ok = (nm_peek(m, (uint16_t)(NES_EPSM_FAMISTUDIO_EPSM_CHN_ENV_SHAPE + s)) & 0x0F) == EPSM_TEST_EDITED_SHAPE;
        }
        nm_tick_silent(m);
    }
    EXPECT(shape_seen && shape_ok, "an SSG holding SEnvAuto takes the edited shape $%X at once", EPSM_TEST_EDITED_SHAPE);

    /* Edited SSG note at order 2 row 8, heard after an edit made earlier. */
    nm_start(m, &g_edited, 2, 8, 12000, &mode);
    nm_tick_silent(m);
    const int edited_note = nm_peek(m, NES_EPSM_FAMISTUDIO_CHN_NOTE + CH_SSG1);
    nm_start(m, &g_thor, 2, 8, 12000, &mode);
    nm_tick_silent(m);
    const int original_note = nm_peek(m, NES_EPSM_FAMISTUDIO_CHN_NOTE + CH_SSG1);
    nm_start(m, &g_thor, 1, 4, 12000, &mode);
    nm_tick_silent(m);
    nm_apply_image(m, &g_edited);
    int o, r, tk, heard = -1;
    for (int i = 0; i < 400 && heard < 0; ++i) {
        nm_position(m, &o, &r, &tk);
        nm_tick_silent(m);
        if (o == 2 && r == 8 && tk == 0) heard = nm_peek(m, NES_EPSM_FAMISTUDIO_CHN_NOTE + CH_SSG1);
    }
    EXPECT(edited_note != original_note && heard == edited_note, "edited SSG 1 note at order 2 row 8: %d (was %d, heard %d)",
           edited_note, original_note, heard);
    trace_free(&ref);
    trace_free(&t);
}

static double energy(NesMachine* m, int ticks) {
    double e = 0;
    for (int i = 0; i < ticks; ++i) {
        int n = nm_tick(m, g_out);
        for (int k = 0; k < 2 * n; ++k) e += g_out[k] * g_out[k];
    }
    return e;
}

static void test_audition(NesMachine* m) {
    printf("EPSM audition: SSG, FM and rhythm\n");
    const NesEpsm* e = nm_epsm(m);
    /* SSG 1, note 58 (A4): the period from the SSG table, fine +10 adds 10 periods. */
    EXPECT(nm_audition(m, &g_thor, CH_SSG1, 58, EPSM_TEST_INSTRUMENT_SLEAD, 0) == NM_OK, "audition SSG 1 SLead");
    nm_tick(m, g_out);
    EXPECT(ssg_period(e, 0) == ssg_table(m, 58) && fabs(125000.0 / ssg_period(e, 0) / 440.0 - 1) < 0.003,
           "SSG A4: TP %d = %.2f Hz", ssg_period(e, 0), 125000.0 / ssg_period(e, 0));
    int held_frames = 0;
    for (int f = 0; f < 60; ++f) held_frames = nm_tick(m, g_out);
    const double held_hz = held_frames > 1 ? zero_cross_hz(g_out, held_frames, 2, RATE) : 0;
    EXPECT(fabs(held_hz / (125000.0 / ssg_period(e, 0)) - 1) < 0.01,
           "the held SSG audition reaches the output at its register pitch (%.2f Hz)", held_hz);
    const int rel = nm_release(m);
    int ticks = 0, res = 1;
    while (rel && (res = nm_tick(m, g_out)) > 0 && ticks < 600) ++ticks;
    EXPECT(rel == 0 || (res == 0 && ticks < 600), "SSG release: %s (%d ticks)", rel ? "release tail played" : "cut", ticks);
    EXPECT(nm_audition(m, &g_thor, CH_SSG1 + 1, 58, EPSM_TEST_INSTRUMENT_SLEAD, 10) == NM_OK, "audition SSG 2, fine +10");
    nm_tick(m, g_out);
    EXPECT(ssg_period(e, 1) == ssg_table(m, 58) + 10, "fine +10 adds 10 to the period (%d)", ssg_period(e, 1));

    /* FM 1, note 58: the driver's F-number/block from the FM table; the patch loaded; release always keys off. */
    EXPECT(nm_audition(m, &g_thor, CH_FM1, 58, EPSM_TEST_INSTRUMENT_FDEFAULT, 0) == NM_OK, "audition FM 1 FDefault");
    nm_tick(m, g_out);
    int fnum, block, want_f, want_b;
    fm_regs(e, 0, &fnum, &block);
    fm_normalize(fm_table(m, 58), &want_f, &want_b);
    EXPECT(fnum == want_f && block == want_b && (e->reg[0][0xA4] & 0xC0) == 0, "FM 1 A4: F-number %d block %d (table: %d/%d)", fnum,
           block, want_f, want_b);
    EXPECT(nm_epsm_instrument(m, CH_FM1) == EPSM_TEST_INSTRUMENT_FDEFAULT && e->reg[0][0xB4] != 0,
           "FM 1 loaded FDefault's patch (B4 $%02X)", e->reg[0][0xB4]);
    EXPECT(energy(m, 60) > 0.5, "held FM note sounds");
    EXPECT(nm_release(m) == 1, "FM release: always a key-off ($5C)");
    ticks = 0;
    while ((res = nm_tick(m, g_out)) > 0 && ticks < 3000) ++ticks;
    EXPECT(res == 0 && ticks < 3000 && nes_epsm_chip_fm_quiet(e->chip, 0), "the FM note ends once its carriers are quiet (%d ticks)",
           ticks);

    /* Rhythm: a one-shot of the ROM drum; release lets it run to its end. Without the ROM: silent, flagged. */
    EXPECT(nm_audition(m, &g_thor, CH_RHYTHM1, 37, EPSM_TEST_INSTRUMENT_RKICK, 0) == NM_OK, "audition the bass drum (RKick)");
    nm_tick(m, g_out);
    EXPECT(e->reg[0][0x10] == 0x01 && (e->reg[0][0x18] & 0xC0) != 0, "the drum keyed ($10 = $01) with its level and pan ($18 = $%02X)",
           e->reg[0][0x18]);
    EXPECT(nm_epsm_rhythm_missing(m) == !g_rom, g_rom ? "the ROM is installed: no flag" : "no ROM: the key-on is flagged");
    EXPECT(nm_release(m) == 1, "rhythm release: the drum plays on");
    ticks = 0;
    double drum = 0;
    while ((res = nm_tick(m, g_out)) > 0 && ticks < 600) {
        for (int i = 0; i < 2 * res; ++i) drum += g_out[i] * g_out[i];
        ++ticks;
    }
    EXPECT(res == 0 && ticks < 60, "the audition ends with the drum (%d ticks)", ticks);
    EXPECT(g_rom ? drum > 0.01 : drum < 1e-6, "%s (energy %.3g)", g_rom ? "the drum sounds" : "no sound without the ROM", drum);

    EXPECT(nm_audition(m, &g_thor, CHANNELS, 58, 0, 0) == NM_ERR_IMAGE, "channel 20 does not exist");
    EXPECT(nm_audition(m, &g_thor, CH_FM1, 58, EPSM_TEST_INSTRUMENTS, 0) == NM_ERR_IMAGE, "instrument past the table rejected");
    EXPECT(nm_audition(m, &g_raw, CH_FM1, 58, 0, 0) == NM_ERR_IMAGE, "raw layout cannot audition");
}

static void test_mute(NesMachine* m) {
    printf("EPSM mute: the 15 EPSM channels in the mix\n");
    int mode;
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    double e_all = 0, e_muted = 0, e_back = 0;
    for (int f = 0; f < 600; ++f) {
        if (f == 200 || f == 400) {
            for (int c = 0; c < CHANNELS; ++c) nm_mute_channel(m, c, f == 200);
        }
        int n = nm_tick(m, g_out);
        double e = 0;
        for (int i = 0; i < 2 * n; ++i) e += g_out[i] * g_out[i];
        if (f < 200) e_all += e;
        else if (f >= 210 && f < 400) e_muted += e;
        else if (f >= 400) e_back += e;
    }
    EXPECT(e_muted < e_all * 1e-6 && e_back > e_all * 0.2, "mute silences all 20, unmute restores (energy %.1f, %.2g, %.1f)", e_all,
           e_muted, e_back);
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    for (int c = CH_SSG1; c < CHANNELS; ++c) nm_mute_channel(m, c, 1);
    EXPECT(nm_epsm(m)->mute == 0x7FFF && nm_apu(m)->mute == 0, "channels 5-19 mute the EPSM unit's channels");
    EXPECT(energy(m, 200) > 0.1 && nm_epsm(m)->level[0] == 0.0f && nm_epsm(m)->level[1] == 0.0f, "2A03 still heard, EPSM silent in the mix");
    nm_mute_channel(m, CH_FM1, 0);
    EXPECT(nm_epsm(m)->mute == (0x7FFF & ~(1u << (CH_FM1 - 5))), "unmuting FM 1 clears its bit only");
    EXPECT(nm_mute_channel(m, CHANNELS, 1) == NM_ERR_IMAGE, "channel 20 does not exist");
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    EXPECT(nm_epsm(m)->mute == 0, "start clears mutes (power-on)");
}

/* ---- corrections and extensions: EPSM_ENGINE_PATCH and the machine's instrument tracking -------------------------- */

/* One EPSM register write: the bank ($401C/$401D: 0, $401E/$401F: 1), the register selected, the value. */
typedef struct RegWrite {
    uint8_t bank, reg, value;
} RegWrite;

/* The EPSM register writes in the machine's write log (drained), decoded with the select each bank holds. */
static int epsm_reg_writes(NesMachine* m, RegWrite* out, int cap) {
    static uint8_t log[3 * 4096];
    const int n = nm_write_log(m, log, 4096);
    uint8_t sel[2] = {0, 0};
    int k = 0;
    for (int i = 0; i < n; ++i) {
        const uint8_t* w = log + 3 * i;
        const uint16_t addr = (uint16_t)(w[0] | w[1] << 8);
        if (addr < 0x401C || addr > 0x401F) continue;
        const int port = addr - 0x401C, bank = port >> 1;
        if (!(port & 1)) {
            sel[bank] = w[2];
        } else if (k < cap) {
            out[k].bank = (uint8_t)bank;
            out[k].reg = sel[bank];
            out[k].value = w[2];
            ++k;
        }
    }
    return k;
}

/* The YM2608 / YMF288 has one LFO register, $22 on A1 = 0 (bit 3 enable, bits 0-2 frequency); $24-$26 are timer A/B
 * and $23 is unused. FamiStudio's own player writes an instrument's LFO byte (EpsmPatchRegs[30]) to $22 for every FM
 * channel (ChannelStateEPSMFm.LoadInstrument). FamiStudio 4.5.3's 6502 engine selected $22 + the channel's register
 * offset (0..2), so FM 2/5 wrote it to $23 and FM 3/6 to $24 (Timer A); the patched build writes $22. */
static void test_lfo(NesMachine* m) {
    printf("EPSM LFO: an instrument load on each FM channel writes the patch's LFO byte to $22 and nothing to $23-$26\n");
    static NesImage lfo;
    lfo = g_thor;
    uint8_t* region = lfo.bytes + NM_REGION_OFFSET;
    const uint8_t* entry =
        region + (lfo.info.exp_instruments - NES_EPSM_SONG_BASE) + NM_EXP_INSTRUMENT_SIZE * EPSM_TEST_INSTRUMENT_FDEFAULT;
    const uint32_t block = nm_rd16(entry + 14) - NES_EPSM_SONG_BASE;
    region[block + NM_EPSM_PATCH_SIZE - 1] = 0x0B; /* patch byte 30 = the $22 value: LFO on, frequency 3 */
    EXPECT(nm_load_image(&lfo, &nes_player_epsm) == 0, "FDefault with LFO $0B loads (%s)", lfo.info.problem ? lfo.info.problem : "-");
    static RegWrite w[4096];
    for (int k = 0; k < 6; ++k) {
        EXPECT(nm_audition(m, &lfo, CH_FM1 + k, 58, EPSM_TEST_INSTRUMENT_FDEFAULT, 0) == NM_OK, "audition FM %d", k + 1);
        nm_set_apu_logging(m, 1);
        nm_tick(m, g_out);
        nm_tick(m, g_out);
        const int n = epsm_reg_writes(m, w, 4096);
        int patch = 0, lfo22 = 0, other = 0;
        for (int i = 0; i < n; ++i) {
            patch |= w[i].bank == k / 3 && w[i].reg == 0xB0 + k % 3;
            lfo22 |= w[i].bank == 0 && w[i].reg == 0x22 && w[i].value == 0x0B;
            other += w[i].reg >= 0x23 && w[i].reg <= 0x26;
        }
        EXPECT(patch && lfo22 && other == 0 && nm_epsm(m)->reg[0][0x22] == 0x0B,
               "FM %d: patch loaded, LFO $0B written to $22 (now $%02X), %d writes to $23-$26 on either bank", k + 1,
               nm_epsm(m)->reg[0][0x22], other);
    }
    nm_set_apu_logging(m, 0);
}

/* A rhythm drum's instrument is the entry famistudio_set_epsm_instrument loads (famistudio_ptr1 + famistudio_r3 when it
 * stores the drum's level), in either fixture layout; an edit of that instrument's level (volume envelope's first value)
 * and pan (patch byte 1) reaches the engine at once. */
static int g_drum_held;
static int kick_holds_rkick(NesMachine* m) {
    return g_drum_held = nm_epsm_instrument(m, CH_RHYTHM1) == EPSM_TEST_INSTRUMENT_RKICK;
}

static void test_rhythm_instruments(NesMachine* m) {
    printf("EPSM rhythm instruments: identified from the loader's entry pointer, edits followed live\n");
    static const int drums[6] = {EPSM_TEST_INSTRUMENT_RKICK, EPSM_TEST_INSTRUMENT_RSNARE, EPSM_TEST_INSTRUMENT_RCYMBAL,
                                 EPSM_TEST_INSTRUMENT_RHAT, EPSM_TEST_INSTRUMENT_RTOM, EPSM_TEST_INSTRUMENT_RRIM};
    for (int layout = 0; layout < 2; ++layout) {
        const NesImage* im = layout ? &g_moved : &g_thor;
        for (int d = 0; d < 6; ++d) {
            for (int j = 0; j < 2; ++j) {
                const int ins = j ? EPSM_TEST_INSTRUMENT_RHATLEFT : drums[d];
                EXPECT(nm_audition(m, im, CH_RHYTHM1 + d, 37, ins, 0) == NM_OK, "audition drum %d", d);
                nm_tick(m, g_out);
                const uint8_t* region = nm_region(im);
                const uint8_t* e = region + (im->info.exp_instruments - NES_EPSM_SONG_BASE) + NM_EXP_INSTRUMENT_SIZE * ins;
                const int level = region[nm_rd16(e) - NES_EPSM_SONG_BASE + 1] & 0x0F;
                EXPECT(nm_epsm_instrument(m, CH_RHYTHM1 + d) == ins &&
                           nm_peek(m, (uint16_t)(NES_EPSM_FAMISTUDIO_CHN_EPSM_RHYTHM_VOLUME + d)) == level,
                       "%s layout, drum %d loads instrument %d (tracked %d), level %d", layout ? "moved" : "plain", d, ins,
                       nm_epsm_instrument(m, CH_RHYTHM1 + d), level);
            }
        }
    }
    /* Live: the bass drum holding RKick; its level and pan edited. */
    static NesImage edit;
    edit = g_thor;
    uint8_t* region = edit.bytes + NM_REGION_OFFSET;
    const uint8_t* e = region + (edit.info.exp_instruments - NES_EPSM_SONG_BASE) + NM_EXP_INSTRUMENT_SIZE * EPSM_TEST_INSTRUMENT_RKICK;
    uint8_t* level = region + (nm_rd16(e) - NES_EPSM_SONG_BASE + 1);
    uint8_t* pan = region + (nm_rd16(e + 14) - NES_EPSM_SONG_BASE + 1);
    const int new_level = (*level & 0x0F) == 5 ? 6 : 5, new_pan = (*pan & 0xC0) == 0x40 ? 0x80 : 0x40;
    *level = (uint8_t)(0xC0 | new_level);
    *pan = (uint8_t)((*pan & 0x3F) | new_pan);
    EXPECT(nm_load_image(&edit, &nes_player_epsm) == 0, "RKick's level and pan edited: loads (%s)", edit.info.problem ? edit.info.problem : "-");
    int mode;
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    for (int f = 0; f < 4000 && !kick_holds_rkick(m); ++f) nm_tick_silent(m);
    EXPECT(g_drum_held, "the bass drum loads RKick in the song");
    nm_apply_image(m, &edit);
    EXPECT(nm_peek(m, NES_EPSM_FAMISTUDIO_CHN_EPSM_RHYTHM_VOLUME) == new_level &&
               nm_peek(m, NES_EPSM_FAMISTUDIO_CHN_EPSM_RHYTHM_STEREO) == new_pan,
           "the bass drum takes the edited level %d and pan $%02X at once (%d, $%02X)", new_level, new_pan,
           nm_peek(m, NES_EPSM_FAMISTUDIO_CHN_EPSM_RHYTHM_VOLUME), nm_peek(m, NES_EPSM_FAMISTUDIO_CHN_EPSM_RHYTHM_STEREO));
}

/* Plays one row of `bytes` on channel `c` of an audition machine at the next tick (a stream in free RAM after the
 * audition streams, ending with a jump to their silent loop). */
#define ROW_RAM 0x07E0
static void play_row(NesMachine* m, int c, const uint8_t* bytes, int n) {
    for (int i = 0; i < n; ++i) nm_poke(m, (uint16_t)(ROW_RAM + i), bytes[i]);
    nm_poke(m, (uint16_t)(ROW_RAM + n), 0x42);
    nm_poke(m, (uint16_t)(ROW_RAM + n + 1), (uint8_t)NES_AUDITION_RAM);
    nm_poke(m, (uint16_t)(ROW_RAM + n + 2), (uint8_t)(NES_AUDITION_RAM >> 8));
    nm_poke(m, (uint16_t)(NES_EPSM_FAMISTUDIO_CHN_PTR_LO + c), (uint8_t)ROW_RAM);
    nm_poke(m, (uint16_t)(NES_EPSM_FAMISTUDIO_CHN_PTR_HI + c), (uint8_t)(ROW_RAM >> 8));
    nm_poke(m, (uint16_t)(NES_EPSM_FAMISTUDIO_CHN_REPEAT + c), 0);
    nm_poke(m, (uint16_t)(NES_EPSM_FAMISTUDIO_CHN_REF_LEN + c), 0);
    nm_poke(m, (uint16_t)(NES_EPSM_FAMISTUDIO_CHN_NOTE_DELAY + c), 0xFF);
    nm_poke(m, NES_EPSM_FAMISTUDIO_TEMPO_ACC_HI, nm_peek(m, NES_EPSM_FAMISTUDIO_SONG_SPEED)); /* the next frame reads a row */
}

/* The SSG attack path must not fall into the FM/rhythm trigger store with an SSG channel index: that writes into
 * the hi-hat/tom/rim-shot pan array and makes the next drum note silent on both sides. Exercise the real loader
 * and the next drum attack, not just the RAM layout. No rhythm ROM is needed to observe the chip's pan writes. */
static void test_ssg_attack_keeps_rhythm_pan(NesMachine* m) {
    printf("EPSM SSG attacks preserve the next hi-hat, tom and rim-shot pan\n");
    for (int s = 0; s < NES_EPSM_SSG; ++s) {
        const int drum = 3 + s;
        EXPECT(nm_audition(m, &g_thor, CH_RHYTHM1 + drum, 37, EPSM_TEST_INSTRUMENT_RHATLEFT, 0) == NM_OK,
               "audition left-panned drum %d", drum);
        nm_tick(m, g_out);
        uint8_t pans[NES_EPSM_RHYTHM];
        for (int d = 0; d < NES_EPSM_RHYTHM; ++d)
            pans[d] = nm_peek(m, (uint16_t)(NES_EPSM_FAMISTUDIO_CHN_EPSM_RHYTHM_STEREO + d));
        EXPECT(pans[drum] == 0x80, "drum %d starts left-panned ($%02X)", drum, pans[drum]);
        const uint8_t ssg_attack[] = {0x5F, EPSM_TEST_INSTRUMENT_SLEAD, 0x40, 58};
        play_row(m, CH_SSG1 + s, ssg_attack, sizeof ssg_attack);
        nm_tick(m, g_out);
        int unchanged = 1;
        for (int d = 0; d < NES_EPSM_RHYTHM; ++d)
            unchanged &= nm_peek(m, (uint16_t)(NES_EPSM_FAMISTUDIO_CHN_EPSM_RHYTHM_STEREO + d)) == pans[d];
        EXPECT(unchanged, "SSG %d attack preserves every drum's held pan", s);
        const uint8_t drum_attack[] = {0x40, 37};
        play_row(m, CH_RHYTHM1 + drum, drum_attack, sizeof drum_attack);
        nm_tick(m, g_out);
        EXPECT((nm_epsm(m)->reg[0][0x18 + drum] & 0xC0) == pans[drum],
               "the next drum %d attack still reaches the left output ($%02X)", drum, nm_epsm(m)->reg[0][0x18 + drum]);
    }
}

static uint16_t env_addr(NesMachine* m, int slot) {
    return (uint16_t)(nm_peek(m, (uint16_t)(NES_EPSM_FAMISTUDIO_ENV_ADDR_LO + slot)) |
                      nm_peek(m, (uint16_t)(NES_EPSM_FAMISTUDIO_ENV_ADDR_HI + slot)) << 8);
}

/* Thor Tracker's `$5F o` on an EPSM SSG (EPSM_ENGINE_PATCH). FamiStudio 4.5.3's no-attack EPSM load (`$43` then an
 * instrument change) skips the basic envelopes and stores the mixer and noise envelope pointers from X = the volume slot,
 * so they land in the SSG's volume and arpeggio slots; `$5F` loads the instrument's five envelopes into their own slots
 * (volume, arpeggio, mixer, noise; pitch in the pitch slot) while the row keeps its own attack flag, and bit 7 of o keeps
 * the shared manual period the engine holds. */
static void test_ssg_hold_load(NesMachine* m) {
    printf("EPSM $5F: an SSG instrument load into the right envelope slots, legato kept, bit 7 keeping the period\n");
    const int ssg = CH_SSG1;
    const int target = EPSM_TEST_INSTRUMENT_SENVMANUAL;
    const uint8_t* region = nm_region(&g_edited);
    const uint8_t* e = region + (g_edited.info.exp_instruments - NES_EPSM_SONG_BASE) + NM_EXP_INSTRUMENT_SIZE * target;
    EXPECT(e[11] == 0x80 && nm_rd16(e + 12) == EPSM_TEST_EDITED_PERIOD, "SEnvManual is a manual-period sound (%d)", nm_rd16(e + 12));
    for (int keep = 0; keep < 2; ++keep) {
        EXPECT(nm_audition(m, &g_edited, ssg, 58, EPSM_TEST_INSTRUMENT_SLEAD, 0) == NM_OK, "audition SSG 1 SLead");
        for (int f = 0; f < 6; ++f) nm_tick(m, g_out);
        nm_poke(m, NES_EPSM_FAMISTUDIO_EPSM_ENV_PERIOD_LO, 1234 & 0xFF);
        nm_poke(m, NES_EPSM_FAMISTUDIO_EPSM_ENV_PERIOD_HI, 1234 >> 8);
        const uint8_t row[] = {0x43, 0x5F, (uint8_t)(target | (keep ? 0x80 : 0)), 0x40, 58};
        play_row(m, ssg, row, sizeof row);
        nm_tick(m, g_out);
        const int slot = nm_peek(m, (uint16_t)(NES_EPSM_FAMISTUDIO_CHANNEL_ENV + ssg));
        const int pslot = nm_peek(m, (uint16_t)(NES_EPSM_FAMISTUDIO_CHANNEL_TO_PITCH_ENV + ssg));
        const uint16_t pitch = (uint16_t)(nm_peek(m, (uint16_t)(NES_EPSM_FAMISTUDIO_PITCH_ENV_ADDR_LO + pslot)) |
                                          nm_peek(m, (uint16_t)(NES_EPSM_FAMISTUDIO_PITCH_ENV_ADDR_HI + pslot)) << 8);
        EXPECT(env_addr(m, slot) == nm_rd16(e) && env_addr(m, slot + 1) == nm_rd16(e + 2) && pitch == nm_rd16(e + 4) &&
                   env_addr(m, slot + 2) == nm_rd16(e + 6) && env_addr(m, slot + 3) == nm_rd16(e + 8),
               "keep %d: volume $%04X, arpeggio $%04X, pitch $%04X, mixer $%04X, noise $%04X in their own slots (want $%04X $%04X "
               "$%04X $%04X $%04X)",
               keep, env_addr(m, slot), env_addr(m, slot + 1), pitch, env_addr(m, slot + 2), env_addr(m, slot + 3), nm_rd16(e),
               nm_rd16(e + 2), nm_rd16(e + 4), nm_rd16(e + 6), nm_rd16(e + 8));
        const int period = nm_peek(m, NES_EPSM_FAMISTUDIO_EPSM_ENV_PERIOD_LO) | nm_peek(m, NES_EPSM_FAMISTUDIO_EPSM_ENV_PERIOD_HI) << 8;
        EXPECT(period == (keep ? 1234 : EPSM_TEST_EDITED_PERIOD) && nm_epsm_instrument(m, ssg) == target &&
                   (nm_peek(m, NES_EPSM_FAMISTUDIO_EPSM_CHN_ENV_SHAPE) & 0x0F) == (e[10] & 0x0F),
               "keep %d: period %d (want %d), the SSG holds SEnvManual (%d) with its shape", keep, period,
               keep ? 1234 : EPSM_TEST_EDITED_PERIOD, nm_epsm_instrument(m, ssg));
    }
    /* Legato: reloading the held SLead through `$43 $5F` leaves its envelope positions as uninterrupted play has them; an
     * attack (`$5F` alone) restarts them. */
    int ptr[3];
    for (int run = 0; run < 3; ++run) {
        nm_audition(m, &g_edited, ssg, 58, EPSM_TEST_INSTRUMENT_SLEAD, 0);
        for (int f = 0; f < 6; ++f) nm_tick(m, g_out);
        const uint8_t legato[] = {0x43, 0x5F, EPSM_TEST_INSTRUMENT_SLEAD, 0x40, 58};
        const uint8_t attack[] = {0x5F, EPSM_TEST_INSTRUMENT_SLEAD, 0x40, 58};
        if (run == 1) play_row(m, ssg, legato, sizeof legato);
        if (run == 2) play_row(m, ssg, attack, sizeof attack);
        nm_tick(m, g_out);
        ptr[run] = nm_peek(m, (uint16_t)(NES_EPSM_FAMISTUDIO_ENV_PTR + nm_peek(m, (uint16_t)(NES_EPSM_FAMISTUDIO_CHANNEL_ENV + ssg))));
    }
    EXPECT(ptr[2] != ptr[0], "the fixture tells an attack from held play (volume envelope at %d vs %d)", ptr[2], ptr[0]);
    EXPECT(ptr[1] == ptr[0], "legato `$43 $5F`: the volume envelope position is uninterrupted play's (%d, %d)", ptr[1], ptr[0]);
}

/* The bounded seek fallback (nm_start with no budget for a replay from the song start): every EPSM channel whose order
 * starts a note on an instrument loaded in an earlier order gets that instrument from the engine's own loader, so its
 * sound after the first tick equals straight play's (SSG shape and octave, FM patch registers, drum level and pan). */
static void test_order_start_restore(NesMachine* straight, NesMachine* m) {
    printf("EPSM seek fallback: channels entering an order on a held instrument load it again\n");
    const NmView v = nm_view(&g_thor);
    int cases = 0, roles = 0;
    for (int o = 1; o < EPSM_TEST_ORDERS; ++o) {
        for (int c = CH_SSG1; c < CHANNELS; ++c) {
            const uint8_t* oe = v.region + (g_thor.info.streams[c] + 4u * (uint32_t)o - NES_EPSM_SONG_BASE);
            uint16_t a = nm_rd16(oe + 2);
            int held = 0;
            for (int k = 0; k < oe[1]; ++k) {
                NmEntry entry;
                if (nm_walk_entry(&v, a, c, &entry) || entry.instrument >= 0) break;
                if (entry.note) {
                    held = 1;
                    break;
                }
                a = entry.end;
            }
            if (!held) continue;
            int mode = -1;
            EXPECT(nm_start(straight, &g_thor, o, 0, 100000, &mode) == NM_OK && mode == NM_SEEK_FROM_START, "straight play reaches order %d", o);
            const int want = nm_epsm_instrument(straight, c);
            if (want < 0) continue;
            mode = -1;
            EXPECT(nm_start(m, &g_thor, o, 0, 0, &mode) == NM_OK && mode == NM_SEEK_FROM_ORDER, "order %d: no budget, the fallback", o);
            EXPECT(nm_epsm_instrument(m, c) == want, "order %d channel %d: holds instrument %d as straight play (%d)", o, c, want,
                   nm_epsm_instrument(m, c));
            nm_tick_silent(straight);
            nm_tick_silent(m);
            int same;
            if (c < CH_FM1) {
                const int k = c - CH_SSG1;
                same = (nm_peek(straight, (uint16_t)(NES_EPSM_FAMISTUDIO_EPSM_CHN_ENV_SHAPE + k)) & 0x0F) ==
                           (nm_peek(m, (uint16_t)(NES_EPSM_FAMISTUDIO_EPSM_CHN_ENV_SHAPE + k)) & 0x0F) &&
                       nm_peek(straight, (uint16_t)(NES_EPSM_FAMISTUDIO_EPSM_CHN_ENV_OCTAVE + k)) ==
                           nm_peek(m, (uint16_t)(NES_EPSM_FAMISTUDIO_EPSM_CHN_ENV_OCTAVE + k));
                roles |= 1;
            } else if (c < CH_RHYTHM1) {
                const int k = c - CH_FM1, bank = k / 3, sub = k % 3;
                same = nm_epsm(straight)->reg[bank][0xB0 + sub] == nm_epsm(m)->reg[bank][0xB0 + sub] &&
                       nm_epsm(straight)->reg[bank][0xB4 + sub] == nm_epsm(m)->reg[bank][0xB4 + sub];
                for (int op = 0; op < 4; ++op) {
                    same &= nm_epsm(straight)->reg[bank][0x30 + 4 * op + sub] == nm_epsm(m)->reg[bank][0x30 + 4 * op + sub];
                }
                roles |= 2;
            } else {
                const int k = c - CH_RHYTHM1;
                same = nm_peek(straight, (uint16_t)(NES_EPSM_FAMISTUDIO_CHN_EPSM_RHYTHM_VOLUME + k)) ==
                           nm_peek(m, (uint16_t)(NES_EPSM_FAMISTUDIO_CHN_EPSM_RHYTHM_VOLUME + k)) &&
                       nm_peek(straight, (uint16_t)(NES_EPSM_FAMISTUDIO_CHN_EPSM_RHYTHM_STEREO + k)) ==
                           nm_peek(m, (uint16_t)(NES_EPSM_FAMISTUDIO_CHN_EPSM_RHYTHM_STEREO + k));
                roles |= 4;
            }
            EXPECT(same, "order %d channel %d: after the first tick its sound is straight play's", o, c);
            ++cases;
        }
    }
    EXPECT(cases > 0, "the fixture enters %d order(s) on held EPSM instruments (roles SSG/FM/rhythm: %d)", cases, roles);
}

/* epsm-copies.nesimg (`tools/build.sh exportSamples`; core's golden epsm_copies, lowered by the Kotlin compiler): FM 1
 * plays slot 0 at order 0 row 0, its identical copy slot 1 from row 4 and holds that copy into order 1, whose first FM 1
 * entry starts a note without an instrument change (the lowering sends no FM instrument at an order boundary). The
 * bounded fallback into order 1 holds slot 1 (the actual slot, not the equal slot 0) with the patch straight play
 * holds: after the first tick FM 1's patch registers, $22 and the engine's algorithm, pan and operator levels are
 * straight play's, and no patch load is left queued. */
#define COPIES_FM_SLOT 1
#define COPIES_ORDER 1
static void test_copies_fallback(NesMachine* straight, NesMachine* m, const char* samples) {
    printf("EPSM seek fallback: FM 1 entering an order on a held identical copy (epsm-copies.nesimg)\n");
    static NesImage image;
    char path[1024];
    snprintf(path, sizeof path, "%s/epsm-copies.nesimg", samples);
    FILE* f = fopen(path, "rb");
    if (!f) {
        EXPECT(0, "missing %s; run `tools/build.sh exportSamples` first (or pass the samples directory)", path);
        return;
    }
    memset(&image, 0, sizeof image);
    const size_t size = fread(image.bytes, 1, nes_player_epsm.image_size, f);
    fclose(f);
    EXPECT(size == nes_player_epsm.image_size && nm_load_image(&image, &nes_player_epsm) == 0, "%s loads (%s)", path,
           image.info.problem ? image.info.problem : "-");
    if (image.info.loaded != NM_LOADED) return;

    /* The fixture exercises the case. */
    const NmView v = nm_view(&image);
    const uint8_t* e = v.region + (image.info.streams[CH_FM1] + 4u * COPIES_ORDER - NES_EPSM_SONG_BASE);
    NmEntry first;
    const char* why = nm_walk_entry(&v, nm_rd16(e + 2), CH_FM1, &first);
    EXPECT(!why && first.instrument < 0 && first.note,
           "order %d's first FM 1 entry starts a note without an instrument change (%s, instrument %d, note %d)", COPIES_ORDER,
           why ? why : "-", first.instrument, first.note);
    const uint8_t* table = v.region + (image.info.exp_instruments - NES_EPSM_SONG_BASE);
    const uint8_t* held = v.region + (nm_rd16(table + NM_EXP_INSTRUMENT_SIZE * COPIES_FM_SLOT + 14) - NES_EPSM_SONG_BASE);
    const uint8_t* original = v.region + (nm_rd16(table + 14) - NES_EPSM_SONG_BASE);
    EXPECT(!memcmp(held, original, NM_EPSM_PATCH_SIZE), "slots 0 and %d hold identical patches", COPIES_FM_SLOT);
    int mode = -1;
    EXPECT(nm_start(straight, &image, COPIES_ORDER, 0, 12000, &mode) == NM_OK && mode == NM_SEEK_FROM_START,
           "straight play reaches order %d", COPIES_ORDER);
    EXPECT(nm_epsm_instrument(straight, CH_FM1) == COPIES_FM_SLOT, "straight play enters order %d with FM 1 on slot %d (%d)",
           COPIES_ORDER, COPIES_FM_SLOT, nm_epsm_instrument(straight, CH_FM1));

    /* The fallback. */
    mode = -1;
    EXPECT(nm_start(m, &image, COPIES_ORDER, 0, 0, &mode) == NM_OK && mode == NM_SEEK_FROM_ORDER,
           "budget 0: the start at %d/0 falls back to the order start (mode %d)", COPIES_ORDER, mode);
    EXPECT(nm_epsm_instrument(m, CH_FM1) == COPIES_FM_SLOT, "fallback: FM 1 holds slot %d as in straight play (%d)", COPIES_FM_SLOT,
           nm_epsm_instrument(m, CH_FM1));
    nm_tick_silent(straight);
    nm_tick_silent(m);
    const NesEpsm* a = nm_epsm(straight);
    const NesEpsm* b = nm_epsm(m);
    int regs = a->reg[0][0xB0] == held[0] && b->reg[0][0xB0] == held[0] && a->reg[0][0xB4] == b->reg[0][0xB4] &&
               a->reg[0][0x22] == b->reg[0][0x22];
    for (int r = 0x30; r < 0xA0; r += 4) {
        if (r < 0x40 || r >= 0x50) regs &= a->reg[0][r] == b->reg[0][r]; /* DT/MUL, AR, DR, SR, SL/RR, SSG-EG; not TL */
    }
    EXPECT(regs, "fallback: after the first tick FM 1's patch registers and $22 are straight play's (B0 $%02X / $%02X)",
           a->reg[0][0xB0], b->reg[0][0xB0]);
    static const uint16_t ram[] = {NES_EPSM_FAMISTUDIO_CHN_EPSM_ALG,     NES_EPSM_FAMISTUDIO_CHN_EPSM_FM_STEREO,
                                   NES_EPSM_FAMISTUDIO_CHN_EPSM_VOL_OP1, NES_EPSM_FAMISTUDIO_CHN_EPSM_VOL_OP2,
                                   NES_EPSM_FAMISTUDIO_CHN_EPSM_VOL_OP3, NES_EPSM_FAMISTUDIO_CHN_EPSM_VOL_OP4};
    int engine = nm_peek(straight, NES_EPSM_FAMISTUDIO_CHN_EPSM_FM_INSTRUMENT) == 0xFF &&
                 nm_peek(m, NES_EPSM_FAMISTUDIO_CHN_EPSM_FM_INSTRUMENT) == 0xFF;
    for (size_t i = 0; i < sizeof ram / sizeof ram[0]; ++i) engine &= nm_peek(straight, ram[i]) == nm_peek(m, ram[i]);
    EXPECT(engine, "fallback: FM 1's algorithm, pan and operator levels are straight play's, no patch load queued");
}

static void test_chip_engine_ops(void) {
    printf("chip engine interface: \"nes-famistudio-epsm\"\n");
    const ChipEngineOps* ops = chip_engine_ops("nes-famistudio-epsm");
    const ChipEngineOps* plain = chip_engine_ops("nes-famistudio");
    EXPECT(ops != NULL && plain != NULL && ops != plain && ops != chip_engine_ops("nes-famistudio-vrc6"), "registered beside the 2A03 and VRC6 engines");
    if (!ops) return;
    EXPECT(ops->image_size == NES_EPSM_IMAGE_SIZE && ops->image_storage >= ops->image_size, "image size %zu", ops->image_size);
    ChipImage* img = (ChipImage*)malloc(ops->image_storage);
    static uint8_t bytes[NES_IMAGE_SIZE];
    memset(bytes, 0, sizeof bytes);
    memcpy(bytes, nes_epsm_thor, NES_EPSM_THOR_SIZE);
    EXPECT(ops->load_image(img, bytes, ops->image_size) == CHIP_OK, "EPSM image loads");
    EXPECT(ops->image_size == plain->image_size || ops->load_image(img, bytes, plain->image_size) == CHIP_ERR_IMAGE,
           "the 2A03 image size rejected");
    memset(bytes, 0, sizeof bytes);
    memcpy(bytes, nes_ft_thor, NES_FT_THOR_SIZE);
    EXPECT(ops->load_image(img, bytes, ops->image_size) == CHIP_ERR_IMAGE, "a 2A03 image cut to the EPSM size rejected");
    memset(bytes, 0, sizeof bytes);
    memcpy(bytes, nes_epsm_thor, NES_EPSM_THOR_SIZE);
    ops->load_image(img, bytes, ops->image_size);
    ChipMachine* cm = ops->create(RATE);
    ChipMachine* au = ops->create(RATE);
    int mode = -1, order, row, tick;
    EXPECT(cm && au && ops->start(cm, img, 1, 0, 12000, &mode) == CHIP_OK && mode == CHIP_SEEK_FROM_START, "start at order 1");
    const int ticks = row_frame(1, 8) - row_frame(1, 0);
    double e = 0;
    for (int i = 0; i < ticks; ++i) {
        int n = ops->tick(cm, g_out);
        for (int k = 0; k < 2 * (n > 0 ? n : 0); ++k) e += g_out[k] * g_out[k];
    }
    ops->position(cm, &order, &row, &tick);
    EXPECT(e > 1.0 && order == 1 && row == 8 && tick == 0, "8 rows through the ops (%d/%d/%d, energy %.1f)", order, row, tick, e);
    /* The hosts pass 1-based slots. */
    EXPECT(ops->audition(au, img, CH_FM1, 58, EPSM_TEST_INSTRUMENT_FDEFAULT + 1, 0) == CHIP_OK, "FM audition through the ops");
    EXPECT(ops->mute(cm, CH_RHYTHM1, 1) == CHIP_OK && ops->mute(cm, CHANNELS, 1) == CHIP_ERR_IMAGE, "mute the bass drum; channel 20 rejected");
    EXPECT(ops->tick(cm, g_out) > 0 && ops->tick(au, g_out) > 0, "both machines tick");
    EXPECT(ops->release(au) == 1, "FM release through the ops");
    ops->destroy(cm);
    ops->destroy(au);
    free(img);
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    const char* samples = argc > 2 ? argv[2] : "app/build/samples";
    test_unit();
    test_rhythm_without_rom();
    test_images();
    NesMachine* m = nm_create(RATE, &nes_player_epsm);
    if (!m) {
        printf("nm_create failed\n");
        return 2;
    }
    EXPECT(nm_player(m) == &nes_player_epsm && nes_player_epsm.channels == 20 && nes_player_epsm.envelope_channels == 14 &&
               nes_player_epsm.song_base == NES_EPSM_SONG_BASE && nes_player_epsm.effect_envelopes == 384 &&
               nes_player_epsm.expansion == NES_EXPANSION_EPSM,
           "machine for the EPSM build: 20 channels (14 with envelopes), song base $%04X, effect envelopes from 384",
           NES_EPSM_SONG_BASE);
    EXPECT(nes_player_2a03.envelope_channels == nes_player_2a03.channels && nes_player_vrc6.envelope_channels == nes_player_vrc6.channels &&
               nes_player_vrc7.envelope_channels == nes_player_vrc7.channels && nes_player_fds.envelope_channels == nes_player_fds.channels &&
               nes_player_s5b.envelope_channels == nes_player_s5b.channels &&
               nes_player_n163[NM_N163_BUILDS - 1].envelope_channels == nes_player_n163[NM_N163_BUILDS - 1].channels,
           "every other build has envelopes on all its channels");
    test_seek(m);
    test_live_apply(m);
    test_audition(m);
    test_mute(m);
    test_lfo(m);
    test_rhythm_instruments(m);
    test_ssg_hold_load(m);
    test_ssg_attack_keeps_rhythm_pan(m);
    NesMachine* straight = nm_create(RATE, &nes_player_epsm);
    EXPECT(straight != NULL, "a second EPSM machine");
    if (straight) {
        test_order_start_restore(straight, m);
        test_copies_fallback(straight, m, samples);
        nm_destroy(straight);
    }
    test_chip_engine_ops();
    test_rhythm_with_rom(); /* installs the ROM if given: everything after it hears the drums */
    if (g_rom) test_audition(m);
    test_playback(m, dir);
    nm_destroy(m);
    printf("nes_epsm_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
