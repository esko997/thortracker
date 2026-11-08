/* Host regression test for the NES + Nintendo MMC5 build ("nes-famistudio-mmc5"): the MMC5 audio unit (nes_mmc5.c)
 * against the NESdev "MMC5 audio" description, and the FamiStudio sound engine 4.5.3 assembled with
 * FAMISTUDIO_EXP_MMC5 = 1 playing FamiStudio's own export of tools/audio/nes/mmc5_test.txt and the same rows in the
 * Thor Tracker image layout (nes_mmc5_song.h): identical register writes (APU and MMC5), row timing, the MMC5 pulses'
 * pitch (the 2A03 note table: engine note 46 = A440), duty, the registers the driver does and does not write, seek
 * (including the unit's clocked state), live relocation and edits, audition, mute, the image rules of the MMC5 build
 * and the chip engine interface. The 2A03 and VRC6 builds keep their own tests; here they only prove the builds
 * refuse each other's images.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir]
 * Writes <dir>/nes_mmc5.wav (48 kHz stereo, 30 s), <dir>/nes_mmc5_trace.txt (per-frame APU + MMC5 writes) and
 * <dir>/nes_mmc5.nsf (the MMC5 player + FamiStudio's export, NSF expansion byte $7B = $08). */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "blip_buf.h"
#include "chip_engine.h"
#include "nes_apu.h"
#include "nes_ft_song.h"
#include "nes_machine.h"
#include "nes_mmc5.h"
#include "nes_mmc5_song.h"
#include "nes_vrc6_song.h"

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

/* Engine channels of the MMC5 build. */
#define CH_MPULSE1 5
#define CH_MPULSE2 6

#define FIRST_PASS_ROWS (MMC5_TEST_ORDERS * MMC5_TEST_ROWS)
#define LOOP_ROWS ((MMC5_TEST_ORDERS - MMC5_TEST_LOOP) * MMC5_TEST_ROWS)

static float g_out[NM_MAX_TICK_FRAMES * 2];
static NesImage g_raw, g_thor, g_moved, g_edited;

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

/* Frequency from rising zero crossings of a mono signal (mean removed). */
static double zero_cross_hz(const float* x, int n, int rate) {
    double mean = 0;
    for (int i = 0; i < n; ++i) mean += x[i];
    mean /= n;
    int first = -1, count = 0;
    double first_t = 0, last_t = 0;
    for (int i = 1; i < n; ++i) {
        double a = x[i - 1] - mean, b = x[i] - mean;
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

/* ---- MMC5 unit checks (NESdev "MMC5 audio") ----------------------------------------------- */

static NesMmc5 fresh_unit(void) {
    NesMmc5 v;
    memset(&v, 0, sizeof v);
    nes_mmc5_reset(&v);
    return v;
}

/* Pulse 1 at constant volume `volume`, duty `duty`, the length counter halted (bit 5), period `period`, enabled and
 * started at cycle `t` (the $5003 write resets the sequencer to step 0). */
static void start_pulse(NesMmc5* v, uint32_t t, int duty, int volume, int period) {
    nes_mmc5_write(v, t, 0x5015, 0x01);
    nes_mmc5_write(v, t, 0x5000, (uint8_t)(duty << 6 | 0x30 | volume));
    nes_mmc5_write(v, t, 0x5002, (uint8_t)period);
    nes_mmc5_write(v, t, 0x5003, (uint8_t)(0x08 | (period >> 8)));
}

/* One full sequencer cycle sampled once per step: duty 0..3 high on 1, 2, 4 and 6 of 8 steps (the 2A03's table);
 * the timer clocks the sequencer every 2 (t + 1) CPU cycles. */
static void test_mmc5_pulse(void) {
    printf("MMC5 unit: pulses\n");
    NesMmc5 v = fresh_unit();
    EXPECT(nes_mmc5_channel_out(&v, NES_MMC5_PULSE1) == 0 && nes_mmc5_channel_out(&v, NES_MMC5_PULSE2) == 0 &&
               nes_mmc5_channel_out(&v, NES_MMC5_PCM) == 0 && nes_mmc5_mix_level(&v) == 0.0f,
           "power-on: pulses disabled, DAC 0, silent");
    static const int want_high[4] = {1, 2, 4, 6};
    const int period = 99; /* 200 CPU cycles per sequencer step */
    for (int duty = 0; duty < 4; ++duty) {
        v = fresh_unit();
        start_pulse(&v, 0, duty, 10, period);
        int high = 0, wrong_level = 0;
        for (int step = 1; step <= 8; ++step) {
            nes_mmc5_run(&v, (uint32_t)(200 * step));
            const int out = nes_mmc5_channel_out(&v, NES_MMC5_PULSE1);
            if (out) ++high;
            if (out && out != 10) ++wrong_level;
        }
        EXPECT(high == want_high[duty] && !wrong_level, "duty %d: %d of 8 steps at volume 10 (want %d)", duty, high,
               want_high[duty]);
    }
    /* Period $7FF: 4096 cycles per step; duty 0 is high only at step 1, reached 7 steps after step 0. */
    v = fresh_unit();
    start_pulse(&v, 0, 0, 15, 0x7FF);
    const uint32_t step1 = 2 + 4096u * 6; /* reset left the timer at 2: steps at 2, 4098, ... (seq 7, 6, ..., 1) */
    nes_mmc5_run(&v, step1 - 1);
    const int before = nes_mmc5_channel_out(&v, NES_MMC5_PULSE1);
    nes_mmc5_run(&v, step1);
    EXPECT(before == 0 && nes_mmc5_channel_out(&v, NES_MMC5_PULSE1) == 15 && v.pulse[0].seq == 1,
           "period $7FF: 4096 cycles per step, step 1 (duty 0's only high step) at cycle %u, not before", step1);

    /* No sweep unit: $5001 is ignored, the period never changes and a would-be overflow does not mute. */
    v = fresh_unit();
    start_pulse(&v, 0, 2, 15, 0x700);
    nes_mmc5_write(&v, 0, 0x5001, 0x81); /* APU: enabled, shift 1 -> target $A80 > $7FF mutes and never sweeps */
    int heard = 0;
    for (uint32_t t = 1000; t <= 8 * NES_MMC5_CLOCK_PERIOD; t += 1000) {
        nes_mmc5_run(&v, t);
        heard |= nes_mmc5_channel_out(&v, NES_MMC5_PULSE1) != 0;
    }
    EXPECT(v.pulse[0].period == 0x700 && heard, "$5001 ignored: period stays $700 over 8 clocks and the pulse sounds");

    /* Periods below 8 are not silenced: period 3 steps every 8 cycles, a full cycle in 64. */
    v = fresh_unit();
    start_pulse(&v, 0, 2, 15, 3);
    int hi = 0, lo = 0;
    for (uint32_t t = 8; t <= 64; t += 8) {
        nes_mmc5_run(&v, t);
        if (nes_mmc5_channel_out(&v, NES_MMC5_PULSE1)) ++hi;
        else ++lo;
    }
    EXPECT(hi == 4 && lo == 4, "period 3 (ultrasonic) still sounds: %d high, %d low steps of 8", hi, lo);
}

/* Envelope and length counter: clocked together every 7424 CPU cycles (241.08 Hz), from power-on. */
static void test_mmc5_clock(void) {
    printf("MMC5 unit: envelope and length counter clock (CPU / %u = %.2f Hz)\n", NES_MMC5_CLOCK_PERIOD,
           CPU_HZ / NES_MMC5_CLOCK_PERIOD);
    EXPECT(NES_MMC5_CLOCK_PERIOD == 7424 && fabs(CPU_HZ / NES_MMC5_CLOCK_PERIOD - 241.08) < 0.01,
           "the clock divider: 7424 CPU cycles (241.08 Hz)");
    NesMmc5 v = fresh_unit();
    nes_mmc5_write(&v, 0, 0x5015, 0x01);
    nes_mmc5_write(&v, 0, 0x5000, 0xC0); /* duty 3, decaying envelope at divider 0, length counting */
    nes_mmc5_write(&v, 0, 0x5002, 0x40);
    nes_mmc5_write(&v, 0, 0x5003, 0x08); /* length index 1: 254 */
    const NesMmc5Pulse* p = &v.pulse[0];
    nes_mmc5_run(&v, NES_MMC5_CLOCK_PERIOD - 1);
    const int e0 = p->env.decay, l0 = p->length;
    nes_mmc5_run(&v, NES_MMC5_CLOCK_PERIOD);
    const int e1 = p->env.decay, l1 = p->length;
    nes_mmc5_run(&v, 2 * NES_MMC5_CLOCK_PERIOD - 1);
    const int e1b = p->env.decay;
    nes_mmc5_run(&v, 2 * NES_MMC5_CLOCK_PERIOD);
    EXPECT(e0 == 0 && l0 == 254 && e1 == 15 && l1 == 253 && e1b == 15 && p->env.decay == 14 && p->length == 252,
           "first clock at cycle 7424 (envelope start 15, length 254 -> 253), the next at 14848 (14, 252): %d %d %d %d %d %d",
           e0, l0, e1, l1, p->env.decay, p->length);
    EXPECT(nes_mmc5_channel_out(&v, NES_MMC5_PULSE1) == 14 || nes_mmc5_channel_out(&v, NES_MMC5_PULSE1) == 0,
           "decaying volume drives the output");
    /* Length table and halt: index 3 loads 2; halted (bit 5) it holds, released it reaches 0 in two clocks. */
    v = fresh_unit();
    nes_mmc5_write(&v, 0, 0x5015, 0x03);
    nes_mmc5_write(&v, 0, 0x5004, 0xBF); /* duty 2, halt, constant 15 */
    nes_mmc5_write(&v, 0, 0x5006, 0x40);
    nes_mmc5_write(&v, 0, 0x5007, 0x18);
    nes_mmc5_run(&v, 10 * NES_MMC5_CLOCK_PERIOD);
    EXPECT(v.pulse[1].length == 2 && nes_mmc5_read(&v, 10 * NES_MMC5_CLOCK_PERIOD, 0x5015) == 0x02,
           "halted length counter holds 2 over 10 clocks; $5015 reads bit 1");
    nes_mmc5_write(&v, 10 * NES_MMC5_CLOCK_PERIOD, 0x5004, 0x9F); /* halt released */
    nes_mmc5_run(&v, 12 * NES_MMC5_CLOCK_PERIOD);
    EXPECT(v.pulse[1].length == 0 && nes_mmc5_channel_out(&v, NES_MMC5_PULSE2) == 0 &&
               nes_mmc5_read(&v, 12 * NES_MMC5_CLOCK_PERIOD, 0x5015) == 0x00,
           "released, it reaches 0 after two clocks: silent, $5015 bit 1 clear");
    int table_ok = 1;
    for (int i = 0; i < 32; ++i) {
        v = fresh_unit();
        nes_mmc5_write(&v, 0, 0x5015, 0x01);
        nes_mmc5_write(&v, 0, 0x5003, (uint8_t)(i << 3));
        table_ok &= v.pulse[0].length == nes_length_table[i];
    }
    EXPECT(table_ok && nes_length_table[0] == 10 && nes_length_table[1] == 254 && nes_length_table[31] == 30,
           "length loads follow the APU table");
}

static void test_mmc5_status_pcm(void) {
    printf("MMC5 unit: $5015, $5010/$5011 PCM, polarity, mute, registers\n");
    /* $5015: a disabled pulse loads no length; clearing a bit zeroes the counter. */
    NesMmc5 v = fresh_unit();
    nes_mmc5_write(&v, 0, 0x5000, 0xBF);
    nes_mmc5_write(&v, 0, 0x5003, 0x08);
    EXPECT(v.pulse[0].length == 0 && nes_mmc5_channel_out(&v, NES_MMC5_PULSE1) == 0, "disabled: $5003 loads no length");
    nes_mmc5_write(&v, 0, 0x5015, 0x01);
    nes_mmc5_write(&v, 0, 0x5003, 0x08);
    EXPECT(v.pulse[0].length == 254 && nes_mmc5_read(&v, 0, 0x5015) == 0x01, "enabled: length 254, $5015 reads 1");
    nes_mmc5_write(&v, 10, 0x5015, 0x00);
    EXPECT(v.pulse[0].length == 0 && nes_mmc5_read(&v, 10, 0x5015) == 0x00, "clearing bit 0 zeroes the length counter");

    /* $5011 in write mode sets the 8-bit DAC; $00 keeps it and trips the IRQ flag. */
    v = fresh_unit();
    nes_mmc5_write(&v, 0, 0x5010, 0x00);
    nes_mmc5_write(&v, 0, 0x5011, 0x80);
    EXPECT(nes_mmc5_channel_out(&v, NES_MMC5_PCM) == 0x80 && !v.irq_trip, "write mode: $5011 = $80 sets the DAC");
    nes_mmc5_write(&v, 0, 0x5011, 0x00);
    EXPECT(nes_mmc5_channel_out(&v, NES_MMC5_PCM) == 0x80 && v.irq_trip, "$00 leaves the DAC and sets the IRQ trip flag");
    EXPECT(nes_mmc5_read(&v, 0, 0x5010) == 0x01 && !v.irq_trip, "IRQ disabled: $5010 reads $01 (MMC5A) and clears the trip");
    nes_mmc5_write(&v, 0, 0x5010, 0x80);
    nes_mmc5_write(&v, 0, 0x5011, 0x00);
    const uint8_t r1 = nes_mmc5_read(&v, 0, 0x5010), r2 = nes_mmc5_read(&v, 0, 0x5010);
    EXPECT(r1 == 0x81 && r2 == 0x01, "IRQ enabled: $5010 reads $81 once, then $01 ($%02X $%02X)", r1, r2);
    nes_mmc5_write(&v, 0, 0x5011, 0x00);
    nes_mmc5_write(&v, 0, 0x5011, 0x22);
    EXPECT(!v.irq_trip && v.dac == 0x22, "a non-zero write clears the trip flag");
    nes_mmc5_write(&v, 0, 0x5010, 0x01); /* read mode */
    nes_mmc5_write(&v, 0, 0x5011, 0x44);
    nes_mmc5_write(&v, 0, 0x5011, 0x00);
    EXPECT(v.dac == 0x22 && !v.irq_trip && v.pcm_mode == 1, "read mode: $5011 writes are ignored");

    /* Levels: the DAC is a DMC level of the same value through the tnd formula (255: twice the DMC's 127), the pulses
     * the 2A03 pulse formula; everything inverted. */
    v = fresh_unit();
    nes_mmc5_write(&v, 0, 0x5011, 127);
    const double dmc127 = 159.79 / (22638.0 / 127.0 + 100.0);
    EXPECT(fabs(nes_mmc5_mix_level(&v) + dmc127) < 1e-4, "DAC 127 = minus the APU's DMC at 127 (%.4f vs %.4f)",
           nes_mmc5_mix_level(&v), -dmc127);
    const float l127 = nes_mmc5_mix_level(&v);
    nes_mmc5_write(&v, 0, 0x5011, 255);
    const double dmc255 = 159.79 / (22638.0 / 255.0 + 100.0);
    EXPECT(fabs(nes_mmc5_mix_level(&v) + dmc255) < 1e-4 && nes_mmc5_mix_level(&v) < l127,
           "DAC 255: the high bit goes past the DMC's range (%.4f)", nes_mmc5_mix_level(&v));
    v = fresh_unit();
    start_pulse(&v, 0, 3, 15, 0x7FF); /* duty 3 is high at step 0 */
    const double full_2a03 = 95.88 / (8128.0 / 15.0 + 100.0);
    EXPECT(fabs(nes_mmc5_mix_level(&v) + full_2a03) < 1e-4, "MMC5 pulse at 15 = minus the 2A03 pulse at 15 (%.4f vs %.4f)",
           nes_mmc5_mix_level(&v), -full_2a03);
    nes_mmc5_set_mute(&v, 1 << NES_MMC5_PULSE1);
    EXPECT(nes_mmc5_mix_level(&v) == 0.0f && nes_mmc5_channel_out(&v, NES_MMC5_PULSE1) == 15, "muted in the mix, still running");
    nes_mmc5_set_mute(&v, 0);

    /* Inverted polarity into the band-limited buffer: the MMC5 pulse going high steps the output down, the APU's
     * pulse going high steps it up. */
    blip_t* b = blip_new(RATE / 10);
    blip_set_rates(b, CPU_HZ, RATE);
    short pcm[256];
    v = fresh_unit();
    nes_mmc5_set_output(&v, b);
    start_pulse(&v, 100, 3, 15, 0x7FF);
    nes_mmc5_end_frame(&v, 2000);
    blip_end_frame(b, 2000);
    int n = blip_read_samples(b, pcm, 256, 0), lo_mmc5 = 0, hi_mmc5 = 0;
    for (int i = 0; i < n; ++i) {
        if (pcm[i] < lo_mmc5) lo_mmc5 = pcm[i];
        if (pcm[i] > hi_mmc5) hi_mmc5 = pcm[i];
    }
    blip_clear(b);
    NesApu a;
    memset(&a, 0, sizeof a);
    nes_apu_reset(&a);
    nes_apu_set_output(&a, b);
    nes_apu_write(&a, 100, 0x4015, 0x01);
    nes_apu_write(&a, 100, 0x4000, 0xFF);
    nes_apu_write(&a, 100, 0x4002, 0xFF);
    nes_apu_write(&a, 100, 0x4003, 0x0B); /* period $3FF: the sweep unit's target $7FE does not mute */
    nes_apu_end_frame(&a, 2000);
    blip_end_frame(b, 2000);
    n = blip_read_samples(b, pcm, 256, 0);
    int lo_apu = 0, hi_apu = 0;
    for (int i = 0; i < n; ++i) {
        if (pcm[i] < lo_apu) lo_apu = pcm[i];
        if (pcm[i] > hi_apu) hi_apu = pcm[i];
    }
    blip_delete(b);
    EXPECT(lo_mmc5 < -1000 && hi_mmc5 < -lo_mmc5 / 4 && hi_apu > 1000 && lo_apu > -hi_apu / 4,
           "inverted polarity: MMC5 pulse high -> %d..%d, APU pulse high -> %d..%d", lo_mmc5, hi_mmc5, lo_apu, hi_apu);

    EXPECT(nes_mmc5_register(0x5000) && nes_mmc5_register(0x5001) && nes_mmc5_register(0x5007) && nes_mmc5_register(0x5010) &&
               nes_mmc5_register(0x5011) && nes_mmc5_register(0x5015) && !nes_mmc5_register(0x5008) &&
               !nes_mmc5_register(0x500F) && !nes_mmc5_register(0x5012) && !nes_mmc5_register(0x5014) &&
               !nes_mmc5_register(0x5016) && !nes_mmc5_register(0x5104) && !nes_mmc5_register(0x4015),
           "MMC5 audio registers: $5000-$5007, $5010, $5011, $5015");
    /* Silent (seeking) and event-driven runs reach the same state. */
    NesMmc5 s = fresh_unit(), e = fresh_unit();
    blip_t* b2 = blip_new(RATE / 10);
    blip_set_rates(b2, CPU_HZ, RATE);
    nes_mmc5_set_output(&e, b2);
    for (int k = 0; k < 2; ++k) {
        NesMmc5* u = k ? &e : &s;
        nes_mmc5_write(u, 0, 0x5015, 0x03);
        nes_mmc5_write(u, 0, 0x5000, 0x8F);
        nes_mmc5_write(u, 0, 0x5002, 0x37);
        nes_mmc5_write(u, 0, 0x5003, 0x09);
        nes_mmc5_write(u, 5000, 0x5004, 0x45);
        nes_mmc5_write(u, 5000, 0x5006, 0x05);
        nes_mmc5_write(u, 5000, 0x5007, 0x10);
        nes_mmc5_write(u, 21000, 0x5002, 0xF1);
        nes_mmc5_end_frame(u, 29780);
    }
    blip_end_frame(b2, 29780);
    blip_delete(b2);
    int same = s.clock_timer == e.clock_timer && s.time == e.time;
    for (int i = 0; i < 2; ++i) {
        same &= s.pulse[i].seq == e.pulse[i].seq && s.pulse[i].timer == e.pulse[i].timer && s.pulse[i].length == e.pulse[i].length &&
                s.pulse[i].env.decay == e.pulse[i].env.decay && s.pulse[i].env.divider == e.pulse[i].env.divider;
    }
    EXPECT(same, "a frame run silently ends in the state the event-driven run reaches");
}

/* ---- the MMC5 build playing the test song -------------------------------------------------- */

static int load_fixture(NesImage* image, const uint8_t* bytes, size_t size, const char* name) {
    memset(image, 0, sizeof *image);
    memcpy(image->bytes, bytes, size);
    int r = nm_load_image(image, &nes_player_mmc5);
    EXPECT(r == 0, "%s loads (%s at %u)", name, image->info.problem ? image->info.problem : "-", image->info.problem_at);
    return r;
}

static void song_row(int k, int* order, int* row, int* frames) {
    if (k >= FIRST_PASS_ROWS) k = MMC5_TEST_LOOP * MMC5_TEST_ROWS + (k - FIRST_PASS_ROWS) % LOOP_ROWS;
    *order = k / MMC5_TEST_ROWS;
    *row = k % MMC5_TEST_ROWS;
    *frames = nes_mmc5_speed[*order][*row];
}

static int song_frames(int rows) {
    int total = 0, o, r, f;
    for (int k = 0; k < rows; ++k) {
        song_row(k, &o, &r, &f);
        total += f;
    }
    return total;
}

static int row_frame(int order, int row) { return song_frames(order * MMC5_TEST_ROWS + row); }

static int pulse_table(NesMachine* m, int note) {
    return nm_peek(m, (uint16_t)(NES_MMC5_FAMISTUDIO_NOTE_TABLE_LSB + note)) |
           nm_peek(m, (uint16_t)(NES_MMC5_FAMISTUDIO_NOTE_TABLE_MSB + note)) << 8;
}

/* Address (song base offset) of opcode `op` among the row prefixes of channel `c`'s streams, or -1. */
static int find_opcode(const NesImage* image, int c, uint8_t op) {
    const NesImageInfo* info = &image->info;
    const uint32_t base = NES_MMC5_SONG_BASE;
    const uint8_t* s = nm_region(image);
    const NmView view = nm_view(image);
    for (int o = 0; o < info->orders; ++o) {
        const uint8_t* e = s + (info->streams[c] + 4u * (uint32_t)o - base);
        uint16_t a = nm_rd16(e + 2);
        for (int k = 0; k < e[1]; ++k) {
            NmEntry entry;
            if (nm_walk_entry(&view, a, c, &entry)) return -1;
            for (uint32_t p = a; p < entry.end;) {
                const uint8_t b = s[p - base];
                if (b >= 0x80 && (b & 1)) break;               /* empty run */
                if (b >= 0x70) { p += 1; continue; }          /* instrument change, volume track */
                if (b < 0x40 || b == 0x45 || b == 0x40 || b == 0x50) break; /* the row's note */
                if (b == op) return (int)(p - base);
                p += 1 + (b == 0x49 || b == 0x4B || b == 0x51 ? 2 : b == 0x43 || b == 0x4A || b == 0x4C || b == 0x4D || b == 0x53 ? 0 : 1);
            }
            a = entry.end;
        }
    }
    return -1;
}

/* Per-frame sound register writes (APU and MMC5) as (address low, address high, value). */
typedef struct {
    int frames;
    uint8_t (*w)[768];
    int* n;
} Trace;

static void trace_alloc(Trace* t, int frames) {
    t->frames = frames;
    t->w = calloc((size_t)frames, 768);
    t->n = (int*)calloc((size_t)frames, sizeof(int));
}

static void trace_free(Trace* t) {
    free(t->w);
    free(t->n);
}

static void record(NesMachine* m, Trace* t, int f) { t->n[f] = nm_write_log(m, t->w[f], 256); }

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

/* The unit's whole clocked state (not its output attachment). */
static int same_unit(const NesMmc5* a, const NesMmc5* b) {
    int same = a->enabled == b->enabled && a->pcm_mode == b->pcm_mode && a->irq_enable == b->irq_enable &&
               a->irq_trip == b->irq_trip && a->dac == b->dac && a->clock_timer == b->clock_timer && a->time == b->time;
    for (int i = 0; i < 2; ++i) {
        const NesMmc5Pulse *p = &a->pulse[i], *q = &b->pulse[i];
        same &= p->ctrl == q->ctrl && p->period == q->period && p->length == q->length && p->seq == q->seq &&
                p->timer == q->timer && p->env.start == q->env.start && p->env.divider == q->env.divider &&
                p->env.decay == q->env.decay;
    }
    return same;
}

static void test_images(void) {
    printf("MMC5 images: build identity, 7 streams, MMC5 operand rules\n");
    nm_build_raw_image(&g_raw, &nes_player_mmc5, nes_mmc5_raw, NES_MMC5_RAW_SIZE, NULL, 0, 0, MMC5_TEST_ROWS, MMC5_TEST_ORDERS,
                       MMC5_TEST_LOOP);
    EXPECT(nm_load_image(&g_raw, &nes_player_mmc5) == 0, "FamiStudio's raw MMC5 export loads (%s)",
           g_raw.info.problem ? g_raw.info.problem : "-");
    EXPECT(g_raw.info.tempo_ntsc == MMC5_TEST_TEMPO_NTSC, "raw: NTSC tempo word after 7 channel pointers (%u)", g_raw.info.tempo_ntsc);
    load_fixture(&g_thor, nes_mmc5_thor, NES_MMC5_THOR_SIZE, "Thor layout");
    load_fixture(&g_moved, nes_mmc5_moved, NES_MMC5_MOVED_SIZE, "moved");
    load_fixture(&g_edited, nes_mmc5_edited, NES_MMC5_EDITED_SIZE, "edited");
    const NesImageInfo* i = &g_thor.info;
    EXPECT(i->orders == MMC5_TEST_ORDERS && i->loop == MMC5_TEST_LOOP && i->instruments == MMC5_TEST_INSTRUMENTS &&
               i->tempo_ntsc == MMC5_TEST_TEMPO_NTSC,
           "Thor layout: %u orders, loop %u, %u instruments, tempo step %u", i->orders, i->loop, i->instruments, i->tempo_ntsc);
    int streams_ok = 1;
    for (int c = 0; c < NES_MMC5_CHANNELS; ++c) streams_ok &= i->streams[c] >= NES_MMC5_SONG_BASE;
    EXPECT(streams_ok && nes_player_mmc5.channels == 7, "an order stream for each of the 7 channels");
    EXPECT(NES_MMC5_SONG_BASE >= NES_MMC5_PLAYER_BASE + NES_MMC5_PLAYER_SIZE && NES_MMC5_SONG_BASE % 256 == 0 &&
               NES_MMC5_IMAGE_SIZE <= NM_IMAGE_MAX_SIZE,
           "song base $%04X: the page after the %u-byte player; image %u bytes", NES_MMC5_SONG_BASE, NES_MMC5_PLAYER_SIZE,
           NES_MMC5_IMAGE_SIZE);
    EXPECT(nes_player_pitched(&nes_player_mmc5, CH_MPULSE1) && nes_player_pitched(&nes_player_mmc5, CH_MPULSE2) &&
               nes_player_mmc5_channel(&nes_player_mmc5, CH_MPULSE2) && !nes_player_mmc5_channel(&nes_player_mmc5, 7) &&
               !nes_player_mmc5_channel(&nes_player_vrc6, CH_MPULSE1) && !nes_player_vrc6_channel(&nes_player_mmc5, CH_MPULSE1),
           "channels 5 and 6: pitched MMC5 pulses");

    /* The builds refuse each other's images. */
    static NesImage other;
    memset(&other, 0, sizeof other);
    memcpy(other.bytes, nes_ft_thor, NES_FT_THOR_SIZE);
    EXPECT(nm_load_image(&other, &nes_player_mmc5) != 0, "a 2A03 image on the MMC5 build: rejected (%s)", other.info.problem);
    memset(&other, 0, sizeof other);
    memcpy(other.bytes, nes_vrc6_thor, NES_VRC6_THOR_SIZE);
    EXPECT(nm_load_image(&other, &nes_player_mmc5) != 0, "a VRC6 image on the MMC5 build: rejected (%s)", other.info.problem);
    EXPECT(nm_load_image(&other, &nes_player_vrc6) == 0, "... and loads on its own build");
    other = g_thor;
    EXPECT(nm_load_image(&other, &nes_player_2a03) != 0, "an MMC5 image on the 2A03 build: rejected (%s)", other.info.problem);
    other = g_thor;
    EXPECT(nm_load_image(&other, &nes_player_vrc6) != 0, "an MMC5 image on the VRC6 build: rejected (%s)", other.info.problem);

    /* MMC5 operands: duty 0..3 on the MMC5 pulses, $53 phase reset allowed there, no $55. */
    const int duty_at = find_opcode(&g_thor, CH_MPULSE1, 0x4F), reset_at = find_opcode(&g_thor, CH_MPULSE2, 0x53);
    EXPECT(duty_at >= 0 && reset_at >= 0, "the Thor layout holds a duty effect ($4F) on MMC5 pulse 1 and a phase reset ($53) on "
                                          "MMC5 pulse 2, and loads");
    static NesImage bad;
    if (duty_at >= 0) {
        bad = g_thor;
        bad.bytes[NM_REGION_OFFSET + duty_at + 1] = 0x04;
        EXPECT(nm_load_image(&bad, &nes_player_mmc5) != 0 && bad.info.problem && !strcmp(bad.info.problem, "MMC5 duty outside 0..3"),
               "MMC5 duty 4: rejected (%s)", bad.info.problem);
        bad = g_thor;
        bad.bytes[NM_REGION_OFFSET + duty_at] = 0x55;
        bad.bytes[NM_REGION_OFFSET + duty_at + 1] = 0x01;
        EXPECT(nm_load_image(&bad, &nes_player_mmc5) != 0 && bad.info.problem &&
                   !strcmp(bad.info.problem, "VRC6 saw volume opcode in an MMC5 song"),
               "$55 on an MMC5 pulse: rejected (%s)", bad.info.problem);
    }
    /* Header problems. */
    bad = g_thor;
    bad.bytes[8] = (uint8_t)NES_SONG_BASE, bad.bytes[9] = (uint8_t)(NES_SONG_BASE >> 8);
    EXPECT(nm_load_image(&bad, &nes_player_mmc5) != 0, "the 2A03 song base on the MMC5 build: rejected (%s)", bad.info.problem);
    bad = g_thor;
    bad.bytes[10] = (uint8_t)(NES_MMC5_IMAGE_REGION_SIZE + 1), bad.bytes[11] = (uint8_t)((NES_MMC5_IMAGE_REGION_SIZE + 1) >> 8);
    EXPECT(nm_load_image(&bad, &nes_player_mmc5) != 0, "data larger than the MMC5 region: rejected (%s)", bad.info.problem);
}

static void write_evidence(NesMachine* m, const char* dir, const Trace* raw) {
    char path[512];
    static float wav[NM_MAX_TICK_FRAMES * 2 * 60 * 30];
    int mode, frames = 0;
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    for (int f = 0; f < 60 * 30; ++f) {
        int n = nm_tick(m, wav + 2 * frames);
        frames += n > 0 ? n : 0;
    }
    snprintf(path, sizeof path, "%s/nes_mmc5.wav", dir);
    write_wav(path, wav, frames);
    printf("  wrote %s (%.1f s)\n", path, frames / (double)RATE);
    snprintf(path, sizeof path, "%s/nes_mmc5_trace.txt", dir);
    FILE* ft = fopen(path, "w");
    if (ft) {
        fprintf(ft, "# frame: APU and MMC5 writes as address=value (hex), FamiStudio 4.5.3 MMC5 player (FamiTracker tempo), mmc5_test\n");
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
    snprintf(path, sizeof path, "%s/nes_mmc5.nsf", dir);
    FILE* fn = fopen(path, "wb");
    if (fn) {
        uint8_t h[128];
        memset(h, 0, sizeof h);
        memcpy(h, "NESM\x1A", 5);
        h[5] = 1;
        h[6] = 1;
        h[7] = 1;
        h[8] = (uint8_t)NES_MMC5_PLAYER_BASE, h[9] = (uint8_t)(NES_MMC5_PLAYER_BASE >> 8);
        h[10] = (uint8_t)NES_MMC5_PLAYER_INIT, h[11] = (uint8_t)(NES_MMC5_PLAYER_INIT >> 8);
        h[12] = (uint8_t)NES_MMC5_PLAYER_PLAY, h[13] = (uint8_t)(NES_MMC5_PLAYER_PLAY >> 8);
        strcpy((char*)h + 0x0E, "Thor Tracker MMC5 test");
        strcpy((char*)h + 0x2E, "Thor Tracker");
        strcpy((char*)h + 0x4E, "FamiStudio 4.5.3 engine");
        h[0x6E] = 0xFF, h[0x6F] = 0x40;
        h[0x78] = 0x20, h[0x79] = 0x4E;
        h[0x7B] = 0x08; /* expansion: MMC5 */
        fwrite(h, 1, sizeof h, fn);
        static uint8_t body[NES_MMC5_SONG_BASE - 0x8000 + NES_MMC5_RAW_SIZE];
        memset(body, 0, sizeof body);
        memcpy(body, nes_mmc5_player_code, NES_MMC5_PLAYER_SIZE);
        memcpy(body + (NES_MMC5_SONG_BASE - 0x8000), nes_mmc5_raw, NES_MMC5_RAW_SIZE);
        fwrite(body, 1, sizeof body, fn);
        fclose(fn);
    }
}

static void test_playback(NesMachine* m, const char* dir) {
    const int pass_rows = FIRST_PASS_ROWS + 2 * LOOP_ROWS;
    const int frames = song_frames(pass_rows);
    printf("FamiStudio's MMC5 export of mmc5_test.txt: %d rows (first pass + 2 loops), %d frames\n", pass_rows, frames);
    Trace raw, thor;
    trace_alloc(&raw, frames);
    trace_alloc(&thor, frames);
    play_trace(m, &g_raw, &raw);
    int mode, faults = 0, nans = 0, mmc5_writes = 0, apu_writes = 0, bad_rows = 0, k = 0, row_start = 0, foreign = 0, negative = 0,
        positive = 0;
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
        for (int i = 0; i < thor.n[f]; ++i) {
            const uint8_t lo = thor.w[f][3 * i], hi = thor.w[f][3 * i + 1];
            if (hi == 0x40) {
                ++apu_writes;
            } else {
                ++mmc5_writes;
                /* During the song only the pulse volume/period registers: no sweep, PCM, status or ExRAM writes. */
                if (hi != 0x50 || (lo != 0x00 && lo != 0x02 && lo != 0x03 && lo != 0x04 && lo != 0x06 && lo != 0x07)) ++foreign;
            }
        }
        if (nes_mmc5_mix_level(nm_mmc5(m)) < 0.0f) ++negative;
        if (nes_mmc5_mix_level(nm_mmc5(m)) > 0.0f) ++positive;
        if (nm_last_call_cycles(m) > worst_call) worst_call = nm_last_call_cycles(m);
        for (int i = 0; i < 2 * n; ++i) {
            if (g_out[i] != g_out[i]) ++nans;
            double v = fabs(g_out[i]);
            if (v > peak) peak = v;
            sum2 += g_out[i] * g_out[i];
        }
    }
    const double per_tick = (now_us() - t0) / frames, rms = sqrt(sum2 / (2.0 * frames * 798.7));
    EXPECT(faults == 0 && nans == 0, "no driver faults or NaNs over %d frames", frames);
    EXPECT(rms > 0.03 && peak < 1.0, "non-silent, unclipped: rms %.4f peak %.4f", rms, peak);
    EXPECT(worst_call < 29780 / 4, "engine frame within a quarter frame (worst %u cycles)", worst_call);
    EXPECT(mmc5_writes > frames * 2 && apu_writes > frames * 3, "the engine writes the MMC5 (%d) and the APU (%d) every frame",
           mmc5_writes, apu_writes);
    EXPECT(foreign == 0, "during the song only $5000/$5002/$5003/$5004/$5006/$5007: never $5001/$5005 (sweep), $5011 (PCM), "
                         "$5010/$5015 or $5104 (%d others)", foreign);
    EXPECT(positive == 0 && negative > frames / 10, "the MMC5 output is negative (inverted) whenever it sounds: %d of %d frame ends "
                                                    "negative, %d positive", negative, frames, positive);
    printf("  rms %.4f, peak %.4f, worst engine frame %u CPU cycles, %.1f us per tick (%.0fx realtime)\n", rms, peak, worst_call,
           per_tick, 16639.0 / per_tick);
    int d = first_difference(&raw, &thor, 0, frames);
    EXPECT(d < 0, "Thor layout writes the same APU and MMC5 registers as FamiStudio's export, all %d frames (first difference %d)",
           frames, d);
    EXPECT(bad_rows == 0 && k == pass_rows, "%d row starts at the frames the speeds give, loop to order %d (%d wrong)", k,
           MMC5_TEST_LOOP, bad_rows);

    /* Init: $5010 = $00 (PCM write mode, IRQ off) and $5015 = $03 (both pulses on). */
    nm_set_apu_logging(m, 1);
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    uint8_t triples[768];
    const int n_init = nm_write_log(m, triples, 256);
    int w5010 = -1, w5015 = -1, init_foreign = 0;
    for (int i = 0; i < n_init; ++i) {
        const uint8_t* e = triples + 3 * i;
        if (e[1] != 0x50) continue;
        if (e[0] == 0x10) w5010 = e[2];
        else if (e[0] == 0x15) w5015 = e[2];
        else if (e[0] == 0x01 || e[0] == 0x05 || e[0] == 0x11) ++init_foreign;
    }
    EXPECT(w5010 == 0x00 && w5015 == 0x03 && !init_foreign, "init writes $5010 = $00 and $5015 = $03 (got %d, %d)", w5010, w5015);
    const NesMmc5* u = nm_mmc5(m);
    EXPECT(u->enabled == 3 && u->pcm_mode == 0 && !u->irq_enable && u->dac == 0, "after init: pulses enabled, write mode, IRQ off");

    /* nm_apu_log keeps reporting only the APU. */
    nm_set_apu_logging(m, 1);
    nm_tick_silent(m);
    uint8_t pairs[512];
    const int n_apu = nm_apu_log(m, pairs, 256);
    nm_tick_silent(m);
    const int n_all = nm_write_log(m, triples, 256);
    int mmc5_in_all = 0;
    for (int i = 0; i < n_all; ++i) mmc5_in_all += triples[3 * i + 1] == 0x50;
    EXPECT(n_apu > 0 && mmc5_in_all > 0, "apu_log: %d APU pairs; write_log: %d writes, %d of them MMC5", n_apu, n_all, mmc5_in_all);

    /* Registers at known rows: duty and period from the 2A03 note table. */
    nm_start(m, &g_thor, 0, 0, 12000, &mode);
    nm_tick_silent(m);
    nm_tick_silent(m);
    const int note5 = nm_peek(m, NES_MMC5_FAMISTUDIO_CHN_NOTE + CH_MPULSE1);
    EXPECT(u->pulse[0].length && (u->pulse[0].ctrl >> 6) == 2 && u->pulse[0].period == pulse_table(m, note5),
           "order 0 row 0, MMC5 pulse 1 MPulse: sounding, duty 2, period %d = note table[%d] %d", u->pulse[0].period, note5,
           pulse_table(m, note5));
    nm_start(m, &g_thor, 2, 0, 12000, &mode);
    int duties[3];
    for (int f = 0; f < 3; ++f) {
        nm_tick_silent(m);
        duties[f] = u->pulse[0].ctrl >> 6;
    }
    EXPECT(duties[0] == 1 && duties[1] == 3 && duties[2] == 0, "order 2 row 0, MThin duty envelope 1 3 0 (%d %d %d)", duties[0],
           duties[1], duties[2]);

    write_evidence(m, dir, &raw);
    trace_free(&raw);
    trace_free(&thor);
}

/* Pitch: the MMC5 pulses use the 2A03 note table and the 2A03 pulse's divider, so engine note 46 (A4) writes the 2A03
 * Pulse 1's period and sounds at CPU / (16 (t + 1)) ~ 440 Hz. */
static void test_pitch(NesMachine* m) {
    printf("MMC5 pitch: engine note 46 = A440 on the 2A03 note table\n");
    const int note = 46;
    uint8_t triples[768];
    int mmc5_lo = -1, mmc5_hi = -1, apu_lo = -1, apu_hi = -1;
    EXPECT(nm_audition(m, &g_thor, CH_MPULSE1, note, MMC5_TEST_INSTRUMENT_MPULSE, 0) == NM_OK, "audition MMC5 pulse 1 MPulse A4");
    nm_set_apu_logging(m, 1);
    nm_tick(m, g_out);
    int n = nm_write_log(m, triples, 256);
    for (int i = 0; i < n; ++i) {
        const uint8_t* e = triples + 3 * i;
        if (e[1] == 0x50 && e[0] == 0x02) mmc5_lo = e[2];
        if (e[1] == 0x50 && e[0] == 0x03) mmc5_hi = e[2] & 7;
    }
    const int period = nm_mmc5(m)->pulse[0].period;
    static float mono[NM_MAX_TICK_FRAMES * 100];
    int got = 0;
    for (int f = 0; f < 90; ++f) {
        const int k = nm_tick(m, g_out);
        for (int i = 0; i < k && got < (int)(sizeof mono / sizeof mono[0]); ++i) mono[got++] = g_out[2 * i];
    }
    const double hz = zero_cross_hz(mono, got, RATE), divider_hz = CPU_HZ / (16.0 * (period + 1));
    EXPECT(nm_audition(m, &g_thor, 0, note, MMC5_TEST_INSTRUMENT_MPULSE, 0) == NM_OK, "audition 2A03 pulse 1 MPulse A4");
    nm_set_apu_logging(m, 1);
    nm_tick(m, g_out);
    n = nm_write_log(m, triples, 256);
    for (int i = 0; i < n; ++i) {
        const uint8_t* e = triples + 3 * i;
        if (e[1] == 0x40 && e[0] == 0x02) apu_lo = e[2];
        if (e[1] == 0x40 && e[0] == 0x03) apu_hi = e[2] & 7;
    }
    nm_set_apu_logging(m, 0);
    EXPECT(mmc5_lo >= 0 && mmc5_hi >= 0 && mmc5_lo == apu_lo && mmc5_hi == apu_hi && period == (mmc5_lo | mmc5_hi << 8) &&
               period == pulse_table(m, note),
           "$5002/$5003 = $4002/$4003 for note 46: period $%03X (2A03 $%03X), note table %d", mmc5_lo | mmc5_hi << 8,
           apu_lo | apu_hi << 8, pulse_table(m, note));
    EXPECT(fabs(hz / 440.0 - 1) < 0.005 && fabs(hz / divider_hz - 1) < 0.005,
           "MMC5 pulse 1 at period %d sounds %.2f Hz (440 within 0.5%%; divider %.2f Hz)", period, hz, divider_hz);
}

static void test_seek(NesMachine* m) {
    printf("MMC5 seek: exact rows from engine state, the unit clocked as in straight play\n");
    const int frames = song_frames(FIRST_PASS_ROWS + LOOP_ROWS);
    Trace ref, t;
    trace_alloc(&ref, frames);
    trace_alloc(&t, frames);
    play_trace(m, &g_thor, &ref);
    const int targets[][2] = {{0, 4}, {1, 0}, {1, 8}, {2, 8}, {2, 12}, {3, 15}};
    for (size_t i = 0; i < sizeof targets / sizeof targets[0]; ++i) {
        const int o = targets[i][0], r = targets[i][1], f0 = row_frame(o, r);
        int mode = -1, po, pr, pt;
        /* Straight play with output up to the row, then the silent seek to it: the same unit state. */
        nm_start(m, &g_thor, 0, 0, 0, &mode);
        for (int f = 0; f < f0; ++f) nm_tick(m, g_out);
        const NesMmc5 straight = *nm_mmc5(m);
        int res = nm_start(m, &g_thor, o, r, 12000, &mode);
        nm_position(m, &po, &pr, &pt);
        EXPECT(res == NM_OK && mode == NM_SEEK_FROM_START && po == o && pr == r && pt == 0 && nm_frames(m) == (uint32_t)f0,
               "seek %d/%d: from start, %u frames (row starts at %d)", o, r, nm_frames(m), f0);
        EXPECT(same_unit(&straight, nm_mmc5(m)), "seek %d/%d: the MMC5 unit's timers, sequencers, envelopes, lengths and clock "
                                                 "divider equal straight play's", o, r);
        nm_set_apu_logging(m, 1);
        int same = 1;
        for (int f = f0; f < f0 + 200 && f < frames; ++f) {
            nm_tick_silent(m);
            record(m, &t, f);
            same &= same_frame(&ref, &t, f);
        }
        EXPECT(same, "seek %d/%d writes the same APU and MMC5 registers as straight play for 200 frames", o, r);
    }
    /* Order fallback: the streams are placed at order 2's start and its rows replayed; the engine's MMC5 period-high
     * caches (which decide whether $5003/$5007 is rewritten, restarting the sequencer) match straight play's. */
    int mode = -1, o, r, tk;
    nm_start(m, &g_thor, 2, 8, 12000, &mode);
    const int prev1 = nm_peek(m, NES_MMC5_FAMISTUDIO_MMC5_PULSE1_PREV), prev2 = nm_peek(m, NES_MMC5_FAMISTUDIO_MMC5_PULSE2_PREV);
    EXPECT(nm_start(m, &g_thor, 2, 8, 70, &mode) == NM_OK && mode == NM_SEEK_FROM_ORDER, "small budget: seek from order 2's start");
    nm_position(m, &o, &r, &tk);
    EXPECT(o == 2 && r == 8 && tk == 0, "fallback lands on 2/8 (%d/%d/%d)", o, r, tk);
    EXPECT(nm_peek(m, NES_MMC5_FAMISTUDIO_MMC5_PULSE1_PREV) == prev1 && nm_peek(m, NES_MMC5_FAMISTUDIO_MMC5_PULSE2_PREV) == prev2,
           "after the order fallback the period-high caches are straight play's ($%02X $%02X)", prev1, prev2);
    EXPECT(nm_start(m, &g_thor, MMC5_TEST_ORDERS, 0, 12000, &mode) == NM_ERR_IMAGE, "order past the song rejected");
    static NesImage other;
    memset(&other, 0, sizeof other);
    memcpy(other.bytes, nes_ft_thor, NES_FT_THOR_SIZE);
    nm_load_image(&other, &nes_player_2a03);
    EXPECT(nm_start(m, &other, 0, 0, 0, &mode) == NM_ERR_IMAGE, "an MMC5 machine refuses an image loaded for the 2A03 build");
    trace_free(&ref);
    trace_free(&t);
}

static void test_live_apply(NesMachine* m) {
    printf("MMC5 live apply: relocation and edits at tick boundaries\n");
    const int frames = song_frames(FIRST_PASS_ROWS + 2 * LOOP_ROWS);
    Trace ref, t;
    trace_alloc(&ref, frames);
    trace_alloc(&t, frames);
    play_trace(m, &g_thor, &ref);
    int mode;
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_set_apu_logging(m, 1);
    double worst_us = 0;
    for (int f = 0; f < frames; ++f) {
        double a0 = now_us();
        nm_apply_image(m, (f & 1) ? &g_moved : &g_thor);
        double us = now_us() - a0;
        if (us > worst_us) worst_us = us;
        nm_tick_silent(m);
        record(m, &t, f);
    }
    int d = first_difference(&ref, &t, 0, frames);
    EXPECT(d < 0, "moved data applied before each of %d frames: same writes as straight play (first difference %d)", frames, d);
    printf("  worst apply_image: %.1f us\n", worst_us);

    /* MPulse's sustain is edited: the MMC5 pulse and the 2A03 pulse 2 sharing the instrument follow; Lead stays. */
    const NesMmc5* v = nm_mmc5(m);
    nm_start(m, &g_thor, 0, 0, 12000, &mode);
    for (int i = 0; i < 4; ++i) nm_tick_silent(m);
    const int before = v->pulse[0].ctrl & 15;
    nm_apply_image(m, &g_edited);
    nm_tick_silent(m);
    const int after = v->pulse[0].ctrl & 15;
    EXPECT(before == 12 && after == 8 && (nm_apu(m)->pulse[1].ctrl & 15) == 8 && (nm_apu(m)->pulse[0].ctrl & 15) == 11,
           "MPulse sustain follows its edit on MMC5 pulse 1 (%d -> %d) and 2A03 pulse 2 (%d); unedited Lead stays at 11 (%d)",
           before, after, nm_apu(m)->pulse[1].ctrl & 15, nm_apu(m)->pulse[0].ctrl & 15);
    nm_start(m, &g_edited, 2, 8, 12000, &mode);
    nm_tick_silent(m);
    const int edited_note = nm_peek(m, NES_MMC5_FAMISTUDIO_CHN_NOTE + CH_MPULSE1);
    nm_start(m, &g_thor, 2, 8, 12000, &mode);
    nm_tick_silent(m);
    const int original_note = nm_peek(m, NES_MMC5_FAMISTUDIO_CHN_NOTE + CH_MPULSE1);
    nm_start(m, &g_thor, 1, 4, 12000, &mode);
    nm_tick_silent(m);
    nm_apply_image(m, &g_edited);
    int o, r, tk, heard = -1;
    for (int i = 0; i < 400 && heard < 0; ++i) {
        nm_position(m, &o, &r, &tk);
        nm_tick_silent(m);
        if (o == 2 && r == 8 && tk == 0) heard = nm_peek(m, NES_MMC5_FAMISTUDIO_CHN_NOTE + CH_MPULSE1);
    }
    EXPECT(edited_note == original_note + 3 && heard == edited_note, "edited MMC5 note at order 2 row 8: %d (was %d, heard %d)",
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
    printf("MMC5 audition: held notes on the MMC5 pulses, release\n");
    const NesMmc5* v = nm_mmc5(m);
    const int note = 46; /* A440 */
    EXPECT(nm_audition(m, &g_thor, CH_MPULSE1, note, MMC5_TEST_INSTRUMENT_MPULSE, 0) == NM_OK, "audition MMC5 pulse 1 MPulse");
    nm_tick(m, g_out);
    EXPECT(v->pulse[0].period == pulse_table(m, note) && v->pulse[0].length, "first tick sounds A440 (period %d)", v->pulse[0].period);
    double e = energy(m, 120);
    EXPECT(e > 1.0 && (v->pulse[0].ctrl & 15) == 12 && !(v->pulse[1].ctrl & 15) && !(nm_apu(m)->pulse[0].ctrl & 15),
           "held 2 s at the sustain level 12, other channels silent (energy %.1f)", e);
    EXPECT(nm_release(m) == 1, "MPulse has a release point: release phase");
    int ticks = 0, res;
    while ((res = nm_tick(m, g_out)) > 0 && ticks < 100) ++ticks;
    EXPECT(res == 0 && ticks == 4, "release tail 10 7 4 0 plays 4 ticks (%d)", ticks);
    EXPECT(nm_audition(m, &g_thor, CH_MPULSE2, note, MMC5_TEST_INSTRUMENT_MPULSE, 10) == NM_OK, "audition MMC5 pulse 2, fine +10");
    nm_tick(m, g_out);
    EXPECT(v->pulse[1].period == pulse_table(m, note) + 10 && energy(m, 30) > 1.0, "fine +10 adds 10 to the period (%d)",
           v->pulse[1].period);
    EXPECT(nm_audition(m, &g_thor, CH_MPULSE2, note, MMC5_TEST_INSTRUMENT_BASS, -3) == NM_OK, "audition MMC5 pulse 2 Bass, fine -3");
    nm_tick(m, g_out);
    EXPECT(v->pulse[1].period == pulse_table(m, note) - 3, "fine -3 (period %d)", v->pulse[1].period);
    EXPECT(nm_release(m) == 0, "Bass has no release point: cut");
    EXPECT(nm_audition(m, &g_thor, NES_MMC5_CHANNELS, note, 0, 0) == NM_ERR_IMAGE, "channel 7 does not exist");
    EXPECT(nm_audition(m, &g_thor, CH_MPULSE1, note, MMC5_TEST_INSTRUMENTS, 0) == NM_ERR_IMAGE, "instrument past the table rejected");
    EXPECT(nm_audition(m, &g_raw, CH_MPULSE1, note, 0, 0) == NM_ERR_IMAGE, "raw layout cannot audition");
}

static void test_mute(NesMachine* m) {
    printf("MMC5 mute: the two MMC5 pulses in the mix\n");
    int mode;
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    double e_all = 0, e_muted = 0, e_back = 0;
    for (int f = 0; f < 600; ++f) {
        if (f == 200 || f == 400) {
            for (int c = 0; c < NES_MMC5_CHANNELS; ++c) nm_mute_channel(m, c, f == 200);
        }
        int n = nm_tick(m, g_out);
        double e = 0;
        for (int i = 0; i < 2 * n; ++i) e += g_out[i] * g_out[i];
        if (f < 200) e_all += e;
        else if (f >= 210 && f < 400) e_muted += e;
        else if (f >= 400) e_back += e;
    }
    EXPECT(e_muted < e_all * 1e-6 && e_back > e_all * 0.2, "mute silences all 7, unmute restores (energy %.1f, %.2g, %.1f)", e_all,
           e_muted, e_back);
    /* Only the MMC5: the 2A03 part remains. */
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    for (int c = CH_MPULSE1; c <= CH_MPULSE2; ++c) nm_mute_channel(m, c, 1);
    EXPECT(nm_mmc5(m)->mute == 3 && nm_apu(m)->mute == 0, "channels 5-6 mute the MMC5 unit's pulses");
    EXPECT(energy(m, 200) > 0.1 && nes_mmc5_mix_level(nm_mmc5(m)) == 0.0f, "2A03 still heard, MMC5 silent in the mix");
    EXPECT(nm_mute_channel(m, NES_MMC5_CHANNELS, 1) == NM_ERR_IMAGE, "channel 7 does not exist");
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    EXPECT(nm_mmc5(m)->mute == 0, "start clears mutes (power-on)");
}

static void test_chip_engine_ops(void) {
    printf("chip engine interface: \"nes-famistudio-mmc5\"\n");
    const ChipEngineOps* ops = chip_engine_ops("nes-famistudio-mmc5");
    const ChipEngineOps* plain = chip_engine_ops("nes-famistudio");
    const ChipEngineOps* vrc6 = chip_engine_ops("nes-famistudio-vrc6");
    EXPECT(ops != NULL && plain != NULL && vrc6 != NULL && ops != plain && ops != vrc6, "registered beside the 2A03 and VRC6 engines");
    if (!ops) return;
    EXPECT(ops->image_size == NES_MMC5_IMAGE_SIZE && plain->image_size == NES_IMAGE_SIZE && ops->image_storage >= ops->image_size,
           "image sizes %zu (2A03: %zu)", ops->image_size, plain->image_size);
    ChipImage* img = (ChipImage*)malloc(ops->image_storage);
    static uint8_t bytes[NES_IMAGE_SIZE];
    memset(bytes, 0, sizeof bytes);
    memcpy(bytes, nes_mmc5_thor, NES_MMC5_THOR_SIZE);
    EXPECT(ops->load_image(img, bytes, ops->image_size) == CHIP_OK, "MMC5 image loads");
    if (plain->image_size != ops->image_size) {
        EXPECT(ops->load_image(img, bytes, plain->image_size) == CHIP_ERR_IMAGE, "the 2A03 image size rejected");
    }
    memset(bytes, 0, sizeof bytes);
    memcpy(bytes, nes_ft_thor, NES_FT_THOR_SIZE);
    EXPECT(ops->load_image(img, bytes, ops->image_size) == CHIP_ERR_IMAGE, "a 2A03 image cut to the MMC5 size rejected");
    memset(bytes, 0, sizeof bytes);
    memcpy(bytes, nes_mmc5_thor, NES_MMC5_THOR_SIZE);
    ops->load_image(img, bytes, ops->image_size);
    ChipMachine* cm = ops->create(RATE);
    ChipMachine* au = ops->create(RATE);
    int mode = -1, order, row, tick;
    EXPECT(ops->start(cm, img, 1, 0, 12000, &mode) == CHIP_OK && mode == CHIP_SEEK_FROM_START, "start at order 1");
    const int ticks = row_frame(1, 8) - row_frame(1, 0);
    double e = 0;
    for (int i = 0; i < ticks; ++i) {
        int n = ops->tick(cm, g_out);
        for (int k = 0; k < 2 * (n > 0 ? n : 0); ++k) e += g_out[k] * g_out[k];
    }
    ops->position(cm, &order, &row, &tick);
    EXPECT(e > 1.0 && order == 1 && row == 8 && tick == 0, "8 rows through the ops (%d/%d/%d, energy %.1f)", order, row, tick, e);
    /* The hosts pass 1-based slots. */
    EXPECT(ops->audition(au, img, CH_MPULSE2, 46, MMC5_TEST_INSTRUMENT_MPULSE + 1, 0) == CHIP_OK, "MMC5 pulse 2 audition through the ops");
    EXPECT(ops->mute(cm, CH_MPULSE2, 1) == CHIP_OK && ops->mute(cm, NES_MMC5_CHANNELS, 1) == CHIP_ERR_IMAGE,
           "mute the song's MMC5 pulse 2; channel 7 rejected");
    EXPECT(ops->tick(cm, g_out) > 0 && ops->tick(au, g_out) > 0, "both machines tick");
    EXPECT(ops->release(au) == 1, "MPulse has a release point");
    ops->destroy(cm);
    ops->destroy(au);
    free(img);
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    test_mmc5_pulse();
    test_mmc5_clock();
    test_mmc5_status_pcm();
    test_images();
    NesMachine* m = nm_create(RATE, &nes_player_mmc5);
    if (!m) {
        printf("nm_create failed\n");
        return 2;
    }
    EXPECT(nm_player(m) == &nes_player_mmc5 && nes_player_mmc5.channels == 7 && nes_player_mmc5.song_base == NES_MMC5_SONG_BASE &&
               nes_player_mmc5.expansion == NES_EXPANSION_MMC5 && nes_player_mmc5.duty_max == 3,
           "machine for the MMC5 build: 7 channels, song base $%04X", NES_MMC5_SONG_BASE);
    test_playback(m, dir);
    test_pitch(m);
    test_seek(m);
    test_live_apply(m);
    test_audition(m);
    test_mute(m);
    test_chip_engine_ops();
    nm_destroy(m);
    printf("nes_mmc5_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
