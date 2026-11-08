/* Host regression test for the NES + Namco 163 builds ("nes-famistudio-n163-1".."-8"): the N163 audio unit
 * (nes_n163.c) against the NESdev "Namco 163 audio" description for every channel count 1..8, and the FamiStudio
 * sound engine 4.5.3 assembled with FAMISTUDIO_EXP_N163 = 1, FAMISTUDIO_EXP_N163_CHN_CNT = 8 playing FamiStudio's
 * own export of tools/audio/nes/n163_test.txt and the same rows in the Thor Tracker image layout (nes_n163_song.h):
 * identical register writes (APU and N163 ports), row timing, wave uploads in N163 RAM, the wave-index sequence with
 * its repeats, loop and release, phase resets, seek, live relocation and edits, audition, mute, the image rules of
 * the N163 builds, the chip engine interface of all eight builds and the WAV export (chip_wav.c) of the test song.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir]
 * Writes <dir>/nes_n163.wav (48 kHz stereo, 30 s), <dir>/nes_n163_trace.txt (per-frame APU + N163 writes) and
 * <dir>/nes_n163.nsf (the 8-channel N163 player + FamiStudio's export, NSF expansion byte $7B = $10). */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "blip_buf.h"
#include "chip_engine.h"
#include "chip_wav.h"
#include "nes_apu.h"
#include "nes_ft_song.h"
#include "nes_machine.h"
#include "nes_n163.h"
#include "nes_n163_song.h"

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
#define FRAME 29780u

/* The build of the test song: 8 N163 channels, engine channels 5..12 (N163 channel k = engine channel 5 + k). */
#define P (&nes_player_n163[N163_TEST_CHANNELS - 1])
#define CH_N163(k) (NM_FIRST_EXP + (k))
#define ALL_CHANNELS (NM_FIRST_EXP + N163_TEST_CHANNELS)

#define FIRST_PASS_ROWS (N163_TEST_ORDERS * N163_TEST_ROWS)
#define LOOP_ROWS ((N163_TEST_ORDERS - N163_TEST_LOOP) * N163_TEST_ROWS)

_Static_assert(N163_TEST_CHANNELS == 8, "the fixture is for the 8-channel build");

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

/* ---- N163 unit checks (NESdev "Namco 163 audio") ------------------------------------------- */

/* One sound RAM byte through the ports: address (no auto-increment), then data. */
static void poke(NesN163* n, uint32_t t, int addr, int value) {
    nes_n163_write(n, t, 0xF800, (uint8_t)addr);
    nes_n163_write(n, t, 0x4800, (uint8_t)value);
}

/* Channel index k (hardware channel 8 - k, registers $78 - 8k): 18-bit frequency, wave length (multiple of 4,
 * 4..256), wave address (samples), volume; channel index 0's volume byte also holds the enabled count. */
static void set_channel(NesN163* n, uint32_t t, int k, uint32_t freq, int length, int addr, int volume, int count) {
    const int b = 0x78 - 8 * k;
    poke(n, t, b + 0, (int)(freq & 0xFF));
    poke(n, t, b + 2, (int)((freq >> 8) & 0xFF));
    poke(n, t, b + 4, ((256 - length) & 0xFC) | (int)((freq >> 16) & 3));
    poke(n, t, b + 6, addr);
    poke(n, t, b + 7, volume | (k == 0 ? (count - 1) << 4 : 0));
}

static void fresh(NesN163* n) {
    memset(n, 0, sizeof *n);
    nes_n163_reset(n);
}

/* One update every 15 CPU cycles, from channel 8 ($78) down through the enabled count, the DAC holding (sample - 8) x
 * volume of the update until the next: each channel k plays its own constant sample k at volume 15. */
static void test_unit_slots(int c) {
    NesN163 n;
    fresh(&n);
    EXPECT(nes_n163_channel_count(&n) == 1 && nes_n163_dac(&n) == 0, "%d ch: power-on: 1 channel enabled, DAC 0", c);
    for (int k = 0; k < 8; ++k) poke(&n, 0, k, k); /* sample 2k = k */
    for (int k = 0; k < c; ++k) set_channel(&n, 0, k, 0, 4, 2 * k, 15, c);
    EXPECT(nes_n163_channel_count(&n) == c, "%d ch: $7F bits 4-6 enable %d channels", c, nes_n163_channel_count(&n));
    int timing = 1, order = 1;
    for (int j = 1; j <= 3 * c + 2; ++j) {
        nes_n163_run(&n, 15u * (uint32_t)j - 1u);
        if (j == 1 ? nes_n163_dac(&n) != 0 : nes_n163_dac_channel(&n) != (j - 2) % c) timing = 0;
        nes_n163_run(&n, 15u * (uint32_t)j);
        const int k = (j - 1) % c;
        if (nes_n163_dac_channel(&n) != k || nes_n163_dac(&n) != (k - 8) * 15) order = 0;
    }
    EXPECT(timing, "%d ch: an update every 15 CPU cycles, the DAC held in between", c);
    EXPECT(order, "%d ch: updates cycle channel 8 ($78), 7 ($70) .. %d, each outputting (sample - 8) x volume", c, 9 - c);
}

/* f = CPU x freq / (15 x 65536 x L x c): channel 8's phase after U of its updates is U x freq mod (L << 16), across
 * frame boundaries; the wraps over the time measure the frequency. */
static void test_unit_pitch(int c) {
    NesN163 n;
    fresh(&n);
    const uint32_t freq = 0x1234u + 0x800u * (uint32_t)c, length = 16, updates = 200000;
    set_channel(&n, 0, 0, freq, (int)length, 0, 15, c);
    for (int k = 1; k < c; ++k) set_channel(&n, 0, k, 0x3000u * (uint32_t)k, 32, 0, 0, c);
    const uint64_t t = 15ull * (1ull + (uint64_t)c * (updates - 1)); /* channel 8's update number `updates` */
    for (uint64_t f = 0; f < t / FRAME; ++f) nes_n163_end_frame(&n, FRAME);
    nes_n163_run(&n, (uint32_t)(t % FRAME));
    const uint64_t want = (uint64_t)updates * freq % (length << 16);
    EXPECT(nes_n163_phase(&n, 0) == want, "%d ch: phase after %u updates = U x freq mod (L << 16) = %llu (%u)", c, updates,
           (unsigned long long)want, nes_n163_phase(&n, 0));
    const double wraps = floor((double)updates * freq / (double)(length << 16));
    const double hz = wraps / ((double)t / CPU_HZ), formula = CPU_HZ * freq / (15.0 * 65536.0 * length * c);
    EXPECT(fabs(hz / formula - 1.0) < 0.002, "%d ch: freq $%05X, 16 samples: %.3f Hz measured, %.3f Hz by NESdev's formula", c,
           freq, hz, formula);
}

/* Mutes force a channel's slots to 0 and change nothing else; a silent unit (no output, as when seeking) computes
 * the same slots as an audible one. */
static void test_unit_mute_seek(int c) {
    static NesN163 a, b, s;
    fresh(&a);
    for (int i = 0; i < 64; ++i) poke(&a, 0, i, (i * 37 + 11) & 0xFF);
    for (int k = 0; k < c; ++k) set_channel(&a, 0, k, 0x1000u + 0x2345u * (uint32_t)k, 16 + 4 * k, 8 * k, 15 - k, c);
    b = a;
    s = a;
    nes_n163_set_mute(&b, 1);
    blip_t* out = blip_new(RATE / 10);
    blip_set_rates(out, CPU_HZ, RATE);
    nes_n163_set_output(&a, out);
    static short pcm[RATE / 10];
    int same = 1, muted = 1, heard = 0;
    for (int f = 0; f < 6; ++f) {
        for (uint32_t t = 97; t < FRAME; t += 1013) {
            nes_n163_run(&a, t);
            nes_n163_run(&b, t);
            nes_n163_run(&s, t);
            same &= !memcmp(a.ram, b.ram, sizeof a.ram) && a.pos == b.pos && a.last == b.last;
            if (b.last == 0) muted &= nes_n163_dac(&b) == 0;
            else muted &= nes_n163_dac(&b) == nes_n163_dac(&a);
            heard |= nes_n163_dac(&a) != 0;
            if (t == 97 + 1013 * 7) { /* a register write mid-frame on every unit */
                poke(&a, t, 0x7A, f * 13);
                poke(&b, t, 0x7A, f * 13);
                poke(&s, t, 0x7A, f * 13);
            }
        }
        nes_n163_end_frame(&a, FRAME);
        nes_n163_end_frame(&b, FRAME);
        nes_n163_end_frame(&s, FRAME);
        blip_end_frame(out, FRAME);
        while (blip_samples_avail(out) > 0) blip_read_samples(out, pcm, RATE / 10, 0);
    }
    EXPECT(same, "%d ch: muting channel 8 changes neither RAM, phases nor the round robin", c);
    EXPECT(muted && heard, "%d ch: the muted channel's slots output 0, the others as unmuted", c);
    EXPECT(!memcmp(a.ram, s.ram, sizeof a.ram) && a.pos == s.pos && a.last == s.last && a.level == s.level && a.timer == s.timer,
           "%d ch: without output (seek) RAM, phases, round-robin position and DAC equal audible play", c);
    nes_n163_set_output(&a, NULL);
    blip_delete(out);
}

static void test_unit(void) {
    printf("N163 unit: slots, pitch, mute and silent runs for 1..8 channels\n");
    for (int c = 1; c <= 8; ++c) {
        test_unit_slots(c);
        test_unit_pitch(c);
        test_unit_mute_seek(c);
    }

    printf("N163 unit: waves, ports, sound disable, levels\n");
    NesN163 n;
    /* Wave address 6 (byte 3, low nibble), length 8, one sample per update (frequency bits 16-17 = 1). */
    fresh(&n);
    poke(&n, 0, 3, 0x21);
    poke(&n, 0, 4, 0x43);
    poke(&n, 0, 5, 0x65);
    poke(&n, 0, 6, 0x87);
    set_channel(&n, 0, 0, 0x10000, 8, 6, 15, 1);
    int wave_ok = 1;
    for (int j = 1; j <= 24; ++j) {
        nes_n163_run(&n, 15u * (uint32_t)j);
        const int value = 1 + j % 8; /* samples 6..13 = 1..8: the even sample in the low nibble */
        wave_ok &= nes_n163_dac(&n) == (value - 8) * 15 && nes_n163_peek(&n, 0x7D) == j % 8;
    }
    EXPECT(wave_ok, "wave at sample 6, 8 samples long: 2 3 4 5 6 7 8 1 repeating, phase bits 16-23 stored in $7D");

    /* Address port: auto-increment stops at $7F; reads return RAM and advance too. */
    fresh(&n);
    nes_n163_write(&n, 0, 0xF800, 0x80 | 0x7D);
    for (int v = 1; v <= 4; ++v) nes_n163_write(&n, 0, 0x4800, (uint8_t)(0x11 * v));
    EXPECT(nes_n163_peek(&n, 0x7D) == 0x11 && nes_n163_peek(&n, 0x7E) == 0x22 && nes_n163_peek(&n, 0x7F) == 0x44 &&
               n.addr == 0xFF && nes_n163_peek(&n, 0x00) == 0,
           "auto-increment from $7D stops at $7F (no wrap to $00)");
    nes_n163_write(&n, 0, 0xF800, 0x10);
    nes_n163_write(&n, 0, 0x4FFF, 0xAA); /* data port mirror */
    nes_n163_write(&n, 0, 0x4800, 0xBB);
    EXPECT(nes_n163_peek(&n, 0x10) == 0xBB && nes_n163_peek(&n, 0x11) == 0 && n.addr == 0x10,
           "without the I bit every access hits the same byte");
    for (int i = 0; i < 4; ++i) poke(&n, 0, 0x20 + i, 0xC0 + i);
    nes_n163_write(&n, 0, 0xFFFF, 0x80 | 0x20); /* address port mirror */
    const int r0 = nes_n163_read(&n, 0), r1 = nes_n163_read(&n, 0), r2 = nes_n163_read(&n, 0);
    EXPECT(r0 == 0xC0 && r1 == 0xC1 && r2 == 0xC2 && n.addr == (0x80 | 0x23), "data port reads return RAM and auto-increment");
    nes_n163_write(&n, 0, 0xF800, 0x21);
    EXPECT(nes_n163_read(&n, 0) == 0xC1 && nes_n163_read(&n, 0) == 0xC1, "reads without the I bit stay put");
    EXPECT(nes_n163_register(0x4800) && nes_n163_register(0x4FFF) && nes_n163_register(0xE000) && nes_n163_register(0xE7FF) &&
               nes_n163_register(0xF800) && nes_n163_register(0xFFFF) && !nes_n163_register(0x47FF) &&
               !nes_n163_register(0x5000) && !nes_n163_register(0xE800) && !nes_n163_register(0xDFFF) &&
               !nes_n163_register(0xF7FF),
           "N163 ports: $4800-$4FFF, $E000-$E7FF, $F800-$FFFF");

    /* $E000 bit 6 disables sound: DAC 0, oscillators running. */
    static NesN163 on;
    fresh(&n);
    for (int i = 0; i < 8; ++i) poke(&n, 0, i, 0xFF); /* the whole 16-sample wave at 15 */
    set_channel(&n, 0, 0, 0x4321, 16, 0, 15, 1);
    on = n;
    nes_n163_run(&n, 15);
    EXPECT(nes_n163_dac(&n) == 105, "sample 15 at volume 15: DAC 105");
    nes_n163_write(&n, 20, 0xE000, 0x40);
    EXPECT(nes_n163_dac(&n) == 0 && nes_n163_mix_level(&n) == 0.0f, "$E000 bit 6 set: DAC 0 at once");
    nes_n163_run(&n, 3000);
    nes_n163_run(&on, 3000);
    EXPECT(nes_n163_phase(&n, 0) == nes_n163_phase(&on, 0) && nes_n163_phase(&n, 0) != 0 && nes_n163_dac(&n) == 0,
           "while disabled the phase keeps advancing");
    nes_n163_write(&n, 3000, 0xE7FF, 0x3F);
    EXPECT(nes_n163_dac(&n) == 105, "bit 6 clear (mirror $E7FF, page bits ignored): sound again");

    /* Silence: volume 0, or samples of 8 (the DAC's centre). */
    fresh(&n);
    for (int i = 0; i < 8; ++i) poke(&n, 0, i, 0x5A);
    for (int i = 8; i < 16; ++i) poke(&n, 0, i, 0x88);
    set_channel(&n, 0, 0, 0x8000, 16, 0, 0, 2);
    set_channel(&n, 0, 1, 0x9000, 16, 16, 15, 2);
    int silent = 1;
    for (uint32_t t = 15; t < 20000; t += 15) {
        nes_n163_run(&n, t);
        silent &= nes_n163_dac(&n) == 0;
    }
    EXPECT(silent, "volume 0 and a wave of 8s are silent in every slot");
    const double pulse15 = 95.88 / (8128.0 / 15.0 + 100.0);
    EXPECT(fabs(225.0 * NES_N163_STEP - pulse15 * pow(10.0, 12.0 / 20.0)) < 1e-9,
           "full-volume N163 square (225 DAC units) = 2A03 pulse at 15 + 12 dB");
}

/* ---- the 8-channel N163 build playing the test song ------------------------------------------ */

static int load_fixture(NesImage* image, const uint8_t* bytes, size_t size, const char* name) {
    memset(image, 0, sizeof *image);
    memcpy(image->bytes, bytes, size);
    int r = nm_load_image(image, P);
    EXPECT(r == 0, "%s loads (%s at %u)", name, image->info.problem ? image->info.problem : "-", image->info.problem_at);
    return r;
}

static void song_row(int k, int* order, int* row, int* frames) {
    if (k >= FIRST_PASS_ROWS) k = N163_TEST_LOOP * N163_TEST_ROWS + (k - FIRST_PASS_ROWS) % LOOP_ROWS;
    *order = k / N163_TEST_ROWS;
    *row = k % N163_TEST_ROWS;
    *frames = nes_n163_speed[*order][*row];
}

static int song_frames(int rows) {
    int total = 0, o, r, f;
    for (int k = 0; k < rows; ++k) {
        song_row(k, &o, &r, &f);
        total += f;
    }
    return total;
}

static int row_frame(int order, int row) { return song_frames(order * N163_TEST_ROWS + row); }

static uint16_t region_offset(uint16_t addr) { return (uint16_t)(addr - P->song_base); }

/* Expansion instrument j of a loaded image, its wave count and wave w's packed bytes. */
static const uint8_t* exp_entry(const NesImage* im, int j) {
    return nm_region(im) + region_offset(im->info.exp_instruments) + NM_EXP_INSTRUMENT_SIZE * j;
}

static int exp_waves(const NesImage* im, int j) {
    const NmView v = nm_view(im);
    return nm_n163_waves(&v, (uint16_t)(im->info.exp_instruments + NM_EXP_INSTRUMENT_SIZE * j));
}

static const uint8_t* wave_bytes(const NesImage* im, int j, int w) {
    const uint8_t* table = nm_region(im) + region_offset(nm_rd16(exp_entry(im, j) + 10));
    return nm_region(im) + region_offset(nm_rd16(table + 2 * w));
}

/* Envelope bytes and length of directory entry `idx`. */
static const uint8_t* dir_envelope(const NesImage* im, int idx, int* len) {
    const uint8_t* d = im->bytes + NES_IMAGE_HEADER_SIZE + 4 * idx;
    *len = d[2];
    return nm_region(im) + region_offset(nm_rd16(d));
}

static int dir_index(const NesImage* im, uint16_t addr) { return nm_find_envelope(&im->info, im->bytes + NES_IMAGE_HEADER_SIZE, addr); }

static uint8_t peek_n163(NesMachine* m, uint16_t base, int k) { return nm_peek(m, (uint16_t)(base + k)); }

/* After a frame: every sounding N163 channel holds its current wave (wave index of its instrument) in N163 RAM at the
 * instrument's position, its wave address register = that position and its length bits = 256 - size. Channels whose
 * waves overlap another sounding channel's with different bytes are skipped (only one can be in RAM). */
static int check_waves(NesMachine* m, const NesImage* im, int* checked) {
    const NesN163* n = nm_n163(m);
    int bad = 0, active[8], pos[8], size[8];
    const uint8_t* bytes[8];
    for (int k = 0; k < N163_TEST_CHANNELS; ++k) {
        const int j = peek_n163(m, P->n163.chn_n163_instrument, k), w = peek_n163(m, P->n163.chn_n163_wave_index, k);
        active[k] = nm_peek(m, (uint16_t)(P->sym.chn_note + CH_N163(k))) && w != 0xFF && j < im->info.instruments;
        if (!active[k]) continue;
        if (w >= exp_waves(im, j)) {
            ++bad;
            active[k] = 0;
            continue;
        }
        pos[k] = exp_entry(im, j)[8];
        size[k] = exp_entry(im, j)[9];
        bytes[k] = wave_bytes(im, j, w);
    }
    for (int k = 0; k < N163_TEST_CHANNELS; ++k) {
        if (!active[k]) continue;
        int conflict = 0;
        for (int q = 0; q < N163_TEST_CHANNELS && !conflict; ++q) {
            if (q == k || !active[q]) continue;
            for (int b = pos[k] / 2; b < (pos[k] + size[k]) / 2; ++b) {
                if (b >= pos[q] / 2 && b < (pos[q] + size[q]) / 2 && bytes[k][b - pos[k] / 2] != bytes[q][b - pos[q] / 2]) conflict = 1;
            }
        }
        if (conflict) continue;
        ++*checked;
        const int regs = 0x78 - 8 * k;
        if (memcmp(n->ram + pos[k] / 2, bytes[k], (size_t)size[k] / 2) || n->ram[regs + 6] != pos[k] ||
            (n->ram[regs + 4] & 0xFC) != ((256 - size[k]) & 0xFC)) {
            ++bad;
        }
    }
    return bad;
}

/* Per-frame sound register writes (APU and N163 ports) as (address low, address high, value). */
typedef struct {
    int frames;
    uint8_t (*w)[3 * 1024];
    int* n;
} Trace;

static void trace_alloc(Trace* t, int frames) {
    t->frames = frames;
    t->w = calloc((size_t)frames, 3 * 1024);
    t->n = (int*)calloc((size_t)frames, sizeof(int));
}

static void trace_free(Trace* t) {
    free(t->w);
    free(t->n);
}

static void record(NesMachine* m, Trace* t, int f) { t->n[f] = nm_write_log(m, t->w[f], 1024); }

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

/* Operand bytes of a row-entry prefix opcode (as nm_walk_entry reads the stream). */
static int prefix_operands(uint8_t b) {
    switch (b) {
    case 0x46: case 0x47: case 0x48: case 0x4E: case 0x4F: case 0x52: case 0x54: case 0x55: return 1;
    case 0x49: case 0x4B: case 0x51: return 2;
    default: return 0;
    }
}

typedef struct {
    int order, row;
} RowRef;

/* The first row of channel `c` whose entry holds `op` as a prefix (prefix = 1) or as its terminator (prefix = 0). */
static int find_row(const NesImage* im, int c, uint8_t op, int prefix, RowRef* ref) {
    const NmView v = nm_view(im);
    const uint32_t base = P->song_base;
    const uint8_t* s = v.region;
    for (int o = 0; o < im->info.orders; ++o) {
        const uint8_t* e = s + (im->info.streams[c] + 4u * (uint32_t)o - base);
        uint16_t a = nm_rd16(e + 2);
        int row = 0;
        for (int k = 0; k < e[1]; ++k) {
            NmEntry entry;
            if (nm_walk_entry(&v, a, c, &entry)) return 0;
            for (uint32_t p = a;;) {
                const uint8_t b = s[p - base];
                const int terminator = b < 0x40 || b == 0x40 || b == 0x45 || b == 0x50 || b == 0x5A || (b >= 0x80 && (b & 1));
                if (terminator || b == op) {
                    if (b == op && prefix != terminator) {
                        ref->order = o;
                        ref->row = row;
                        return 1;
                    }
                    if (terminator) break;
                }
                p += 1u + (b >= 0x40 && b < 0x70 ? (uint32_t)prefix_operands(b) : 0u);
            }
            row += entry.rows;
            a = entry.end;
        }
    }
    return 0;
}

/* Region offset of the first row entry of channel `c` in order 0. */
static uint32_t first_entry(const NesImage* im, int c) {
    const uint8_t* e = nm_region(im) + region_offset(im->info.streams[c]);
    return region_offset(nm_rd16(e + 2));
}

static void expect_problem(NesImage* bad, const NesPlayer* p, const char* want, const char* what) {
    const int r = nm_load_image(bad, p);
    EXPECT(r != 0 && bad->info.problem && !strcmp(bad->info.problem, want), "%s: rejected with \"%s\" (%s)", what, want,
           r ? bad->info.problem : "loaded");
}

static void test_images(void) {
    printf("N163 images: build identity, 13 streams, the expansion instrument table, N163 opcode rules\n");
    nm_build_raw_image(&g_raw, P, nes_n163_raw, NES_N163_RAW_SIZE, NULL, 0, 0, N163_TEST_ROWS, N163_TEST_ORDERS, N163_TEST_LOOP);
    EXPECT(nm_load_image(&g_raw, P) == 0, "FamiStudio's raw N163 export loads (%s)", g_raw.info.problem ? g_raw.info.problem : "-");
    EXPECT(g_raw.info.tempo_ntsc == N163_TEST_TEMPO_NTSC, "raw: NTSC tempo word after the 7-byte header and 13 channel pointers (%u)",
           g_raw.info.tempo_ntsc);
    load_fixture(&g_thor, nes_n163_thor, NES_N163_THOR_SIZE, "Thor layout");
    load_fixture(&g_moved, nes_n163_moved, NES_N163_MOVED_SIZE, "moved");
    load_fixture(&g_edited, nes_n163_edited, NES_N163_EDITED_SIZE, "edited");
    const NesImageInfo* i = &g_thor.info;
    EXPECT(i->orders == N163_TEST_ORDERS && i->loop == N163_TEST_LOOP && i->instruments == N163_TEST_INSTRUMENTS &&
               i->tempo_ntsc == N163_TEST_TEMPO_NTSC && i->exp_instruments >= P->song_base,
           "Thor layout: %u orders, loop %u, %u instruments, tempo step %u, expansion table $%04X", i->orders, i->loop,
           i->instruments, i->tempo_ntsc, i->exp_instruments);
    int streams_ok = 1;
    for (int c = 0; c < ALL_CHANNELS; ++c) streams_ok &= i->streams[c] >= P->song_base;
    EXPECT(streams_ok && P->channels == ALL_CHANNELS && P->n163_channels == 8, "an order stream for each of the 13 channels");
    for (int o = 0; o < N163_TEST_ORDERS; ++o) {
        EXPECT(i->speed[o] == nes_n163_speed[o][0], "order %d starts at speed %d (%d)", o, nes_n163_speed[o][0], i->speed[o]);
    }
    int waves_ok = 1, wave_dir = 1;
    for (int j = 0; j < i->instruments; ++j) {
        const int idx = dir_index(&g_thor, nm_rd16(exp_entry(&g_thor, j) + 6));
        waves_ok &= exp_waves(&g_thor, j) >= 1;
        wave_dir &= idx == 256 + j && g_thor.bytes[NES_IMAGE_HEADER_SIZE + 4 * idx + 3] == NM_KIND_WAVE;
    }
    EXPECT(waves_ok && wave_dir, "every expansion instrument j: wave-index envelope at directory 256 + j (kind 4), >= 1 wave");

    /* The builds refuse each other's images. */
    static NesImage other, bad;
    memset(&other, 0, sizeof other);
    memcpy(other.bytes, nes_ft_thor, NES_FT_THOR_SIZE);
    EXPECT(nm_load_image(&other, P) != 0, "a 2A03 image on the N163 build: rejected (%s)", other.info.problem);
    other = g_thor;
    EXPECT(nm_load_image(&other, &nes_player_2a03) != 0, "an N163 image on the 2A03 build: rejected (%s)", other.info.problem);
    other = g_thor;
    EXPECT(nm_load_image(&other, &nes_player_n163[6]) != 0 && nm_load_image(&other, &nes_player_vrc6) != 0,
           "an 8-channel N163 image on the 7-channel N163 and the VRC6 builds: rejected");

    /* Kind 4 only on N163 builds (the other builds' message unchanged). */
    memset(&other, 0, sizeof other);
    memcpy(other.bytes, nes_ft_thor, NES_FT_THOR_SIZE);
    EXPECT(nm_load_image(&other, &nes_player_2a03) == 0 && other.info.used_count > 0, "the 2A03 test image loads on its build");
    other.bytes[NES_IMAGE_HEADER_SIZE + 4 * other.info.used[0] + 3] = NM_KIND_WAVE;
    expect_problem(&other, &nes_player_2a03, "envelope kind outside 0..3", "a WAVE directory entry in a 2A03 image");
    bad = g_thor;
    bad.bytes[NES_IMAGE_HEADER_SIZE + 4 * 256 + 3] = 5;
    expect_problem(&bad, P, "envelope kind outside 0..4", "directory kind 5 in an N163 image");

    /* Expansion instrument 0's wave. */
    const uint32_t e0 = NM_REGION_OFFSET + region_offset(i->exp_instruments);
    const int size0 = g_thor.bytes[e0 + 9];
    bad = g_thor;
    bad.bytes[e0 + 8] |= 1;
    expect_problem(&bad, P, "N163 wave position is odd", "odd wave position");
    bad = g_thor;
    bad.bytes[e0 + 9] = 6;
    expect_problem(&bad, P, "N163 wave size is not a multiple of 4 from 4", "wave size 6");
    bad = g_thor;
    bad.bytes[e0 + 9] = 0;
    expect_problem(&bad, P, "N163 wave size is not a multiple of 4 from 4", "wave size 0");
    bad = g_thor;
    bad.bytes[e0 + 8] = (uint8_t)(256 - 16 * 8 - size0 + 2);
    expect_problem(&bad, P, "N163 wave overlaps the channel registers", "a wave reaching $40 (8 channels' registers)");
    bad = g_thor;
    bad.bytes[e0 + 8] = (uint8_t)(256 - 16 * 8 - size0);
    EXPECT(nm_load_image(&bad, P) == 0, "a wave ending exactly below the registers loads (%s)", bad.info.problem ? bad.info.problem : "-");
    bad = g_thor;
    bad.bytes[e0 + 13] = 1;
    expect_problem(&bad, P, "N163 instrument bytes 12-15 not zero", "a nonzero byte 13");
    bad = g_thor;
    bad.bytes[e0 + 10] = (uint8_t)(P->song_base + i->size - 1);
    bad.bytes[e0 + 11] = (uint8_t)((P->song_base + i->size - 1) >> 8);
    expect_problem(&bad, P, "N163 wave table outside the data", "wave table at the end of the data");
    bad = g_thor;
    {
        const uint32_t table = NM_REGION_OFFSET + region_offset(nm_rd16(g_thor.bytes + e0 + 10));
        bad.bytes[table] = (uint8_t)(P->song_base + i->size - 1);
        bad.bytes[table + 1] = (uint8_t)((P->song_base + i->size - 1) >> 8);
    }
    expect_problem(&bad, P, "N163 wave outside the data", "a wave running past the data");
    bad = g_thor;
    bad.bytes[e0 + 6] = bad.bytes[e0 + 0];
    bad.bytes[e0 + 7] = bad.bytes[e0 + 1];
    expect_problem(&bad, P, "expansion instrument envelope of the wrong kind", "a volume envelope as wave-index envelope");

    /* N163 opcodes: $5A / $5B only on the N163 channels; no $4F or $53 there. */
    bad = g_thor;
    bad.bytes[NM_REGION_OFFSET + first_entry(&g_thor, CH_N163(0))] = 0x53;
    expect_problem(&bad, P, "phase reset on an N163 channel is $5B", "$53 on N163 1");
    bad = g_thor;
    bad.bytes[NM_REGION_OFFSET + first_entry(&g_thor, CH_N163(7))] = 0x4F;
    expect_problem(&bad, P, "duty effect on an N163 channel", "$4F on N163 8");
    bad = g_thor;
    bad.bytes[NM_REGION_OFFSET + first_entry(&g_thor, 0)] = 0x5A;
    expect_problem(&bad, P, "N163 release off the N163 channels", "$5A on pulse 1");
    bad = g_thor;
    bad.bytes[NM_REGION_OFFSET + first_entry(&g_thor, 2)] = 0x5B;
    expect_problem(&bad, P, "N163 phase reset off the N163 channels", "$5B on the triangle");
    RowRef ref;
    EXPECT(find_row(&g_thor, CH_N163(0), 0x5A, 0, &ref) || find_row(&g_thor, CH_N163(1), 0x5A, 0, &ref) ||
               find_row(&g_thor, CH_N163(2), 0x5A, 0, &ref) || find_row(&g_thor, CH_N163(3), 0x5A, 0, &ref),
           "the song holds an N163 release ($5A) row");

    /* Header problems. */
    bad = g_thor;
    bad.bytes[8] = (uint8_t)NES_SONG_BASE, bad.bytes[9] = (uint8_t)(NES_SONG_BASE >> 8);
    EXPECT(nm_load_image(&bad, P) != 0, "the 2A03 song base on the N163 build: rejected (%s)", bad.info.problem);
    bad = g_thor;
    bad.bytes[10] = (uint8_t)(P->region_size + 1), bad.bytes[11] = (uint8_t)((P->region_size + 1) >> 8);
    EXPECT(nm_load_image(&bad, P) != 0, "data larger than the N163 region: rejected (%s)", bad.info.problem);
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
    snprintf(path, sizeof path, "%s/nes_n163.wav", dir);
    write_wav(path, wav, frames);
    printf("  wrote %s (%.1f s)\n", path, frames / (double)RATE);
    snprintf(path, sizeof path, "%s/nes_n163_trace.txt", dir);
    FILE* ft = fopen(path, "w");
    if (ft) {
        fprintf(ft, "# frame: APU and N163 port writes as address=value (hex), FamiStudio 4.5.3 N163 player (8 channels, "
                    "FamiTracker tempo), n163_test\n");
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
    snprintf(path, sizeof path, "%s/nes_n163.nsf", dir);
    FILE* fn = fopen(path, "wb");
    if (fn) {
        uint8_t h[128];
        memset(h, 0, sizeof h);
        memcpy(h, "NESM\x1A", 5);
        h[5] = 1;
        h[6] = 1;
        h[7] = 1;
        h[8] = (uint8_t)P->base, h[9] = (uint8_t)(P->base >> 8);
        h[10] = (uint8_t)P->init, h[11] = (uint8_t)(P->init >> 8);
        h[12] = (uint8_t)P->play, h[13] = (uint8_t)(P->play >> 8);
        strcpy((char*)h + 0x0E, "Thor Tracker N163 test");
        strcpy((char*)h + 0x2E, "Thor Tracker");
        strcpy((char*)h + 0x4E, "FamiStudio 4.5.3 engine");
        h[0x6E] = 0xFF, h[0x6F] = 0x40;
        h[0x78] = 0x20, h[0x79] = 0x4E;
        h[0x7B] = 0x10; /* expansion: Namco 163 */
        fwrite(h, 1, sizeof h, fn);
        static uint8_t body[0x8000];
        const size_t size = (size_t)(P->song_base - 0x8000) + NES_N163_RAW_SIZE;
        memset(body, 0, sizeof body);
        memcpy(body, P->code, P->size);
        memcpy(body + (P->song_base - 0x8000), nes_n163_raw, NES_N163_RAW_SIZE);
        fwrite(body, 1, size, fn);
        fclose(fn);
    }
}

static void test_playback(NesMachine* m, const char* dir) {
    const int pass_rows = FIRST_PASS_ROWS + 2 * LOOP_ROWS;
    const int frames = song_frames(pass_rows);
    printf("FamiStudio's N163 export of n163_test.txt: %d rows (first pass + 2 loops), %d frames\n", pass_rows, frames);
    Trace raw, thor;
    trace_alloc(&raw, frames);
    trace_alloc(&thor, frames);
    play_trace(m, &g_raw, &raw);
    int mode, faults = 0, nans = 0, n163_writes = 0, apu_writes = 0, bad_rows = 0, k = 0, row_start = 0, wave_bad = 0, wave_checked = 0;
    uint32_t worst_call = 0;
    double sum2 = 0, peak = 0, t0 = now_us();
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_set_apu_logging(m, 1);
    const uint64_t cycles0 = nm_driver_cycles(m);
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
            if (thor.w[f][3 * i + 1] == 0x40) ++apu_writes;
            else ++n163_writes;
        }
        wave_bad += check_waves(m, &g_thor, &wave_checked);
        if (nm_last_call_cycles(m) > worst_call) worst_call = nm_last_call_cycles(m);
        for (int i = 0; i < 2 * n; ++i) {
            if (g_out[i] != g_out[i]) ++nans;
            double v = fabs(g_out[i]);
            if (v > peak) peak = v;
            sum2 += g_out[i] * g_out[i];
        }
    }
    const double per_tick = (now_us() - t0) / frames, rms = sqrt(sum2 / (2.0 * frames * 798.7));
    const double mean_call = (double)(nm_driver_cycles(m) - cycles0) / frames;
    EXPECT(faults == 0 && nans == 0, "no driver faults or NaNs over %d frames", frames);
    EXPECT(rms > 0.03 && peak < 1.0, "non-silent, unclipped: rms %.4f peak %.4f", rms, peak);
    EXPECT(worst_call < FRAME / 2, "engine frame within half a frame (worst %u cycles, mean %.0f)", worst_call, mean_call);
    EXPECT(n163_writes > frames * 8 && apu_writes > frames * 3, "the engine writes the N163 ports (%d) and the APU (%d) every frame",
           n163_writes, apu_writes);
    EXPECT(wave_checked > frames / 2 && wave_bad == 0,
           "each sounding N163 channel's current wave is in N163 RAM at its instrument's position (%d checks, %d wrong)",
           wave_checked, wave_bad);
    printf("  rms %.4f, peak %.4f, engine frame worst %u / mean %.0f CPU cycles, %.1f us per tick (%.0fx realtime)\n", rms, peak,
           worst_call, mean_call, per_tick, 16639.0 / per_tick);
    int d = first_difference(&raw, &thor, 0, frames);
    EXPECT(d < 0, "Thor layout writes the same APU and N163 registers as FamiStudio's export, all %d frames (first difference %d)",
           frames, d);
    EXPECT(bad_rows == 0 && k == pass_rows, "%d row starts at the frames the speeds give, loop to order %d (%d wrong)", k,
           N163_TEST_LOOP, bad_rows);
    EXPECT((nm_n163(m)->ram[0x7F] >> 4) == 7, "the engine enables 8 N163 channels ($7F bits 4-6 = 7)");

    /* nm_apu_log keeps reporting only the APU. */
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_set_apu_logging(m, 1);
    nm_tick_silent(m);
    uint8_t pairs[1024];
    const int n_apu = nm_apu_log(m, pairs, 512);
    nm_tick_silent(m);
    static uint8_t triples[3 * 1024];
    const int n_all = nm_write_log(m, triples, 1024);
    int n163_in_all = 0;
    for (int i = 0; i < n_all; ++i) n163_in_all += triples[3 * i + 1] == 0x48 || triples[3 * i + 1] == 0xF8;
    EXPECT(n_apu > 0 && n163_in_all > 0, "apu_log: %d APU pairs; write_log: %d writes, %d of them N163 ports", n_apu, n_all, n163_in_all);

    write_evidence(m, dir, &raw);
    trace_free(&raw);
    trace_free(&thor);
}

/* The FamiStudio envelope step (famistudio_update's envelope loop): a hold count delays, $00 n loops, a value $C0 + v
 * is the output. */
typedef struct {
    int ptr, repeat, value;
} EnvSim;

static void env_step(const uint8_t* env, EnvSim* s) {
    if (s->repeat && --s->repeat) return;
    int p = s->ptr;
    for (;;) {
        const uint8_t b = env[p];
        if (b >= 0x80) {
            s->value = (uint8_t)(b + 0x40);
            s->ptr = p + 1;
            return;
        }
        if (b) {
            s->repeat = b;
            s->ptr = p + 1;
            return;
        }
        p = env[p + 1];
    }
}

/* The expansion instrument with a wave-index release point (loop and release), preferring one whose volume envelope
 * has none (its release tail is the wave sequence's); -1 if none. */
static int release_instrument(const NesImage* im, int* volume_release) {
    int found = -1;
    for (int j = 0; j < im->info.instruments; ++j) {
        int wlen, vlen;
        const uint8_t* wave = dir_envelope(im, dir_index(im, nm_rd16(exp_entry(im, j) + 6)), &wlen);
        const uint8_t* vol = dir_envelope(im, dir_index(im, nm_rd16(exp_entry(im, j))), &vlen);
        if (!wave[0] || exp_waves(im, j) < 2) continue;
        if (found < 0 || !vol[0]) {
            found = j;
            *volume_release = vol[0] != 0;
        }
        if (!vol[0]) break;
    }
    return found;
}

static void test_wave_sequence(NesMachine* m) {
    printf("N163 wave index: repeats, loop and release through the engine\n");
    int volume_release = 0;
    const int j = release_instrument(&g_thor, &volume_release);
    EXPECT(j >= 0, "the song has an N163 instrument whose wave sequence loops and releases");
    if (j < 0) return;
    int wlen;
    const uint8_t* wave = dir_envelope(&g_thor, dir_index(&g_thor, nm_rd16(exp_entry(&g_thor, j) + 6)), &wlen);
    const int slot = P->code[P->sym.channel_env - P->base + CH_N163(0)] + 2;
    EXPECT(nm_audition(m, &g_thor, CH_N163(0), 34, j, 0) == NM_OK, "audition expansion instrument %d on N163 1", j);
    EnvSim sim = {1, 0, 0};
    int follows = 0, ticks = 0, in_ram = 1, distinct = 0, seen = 0;
    for (; ticks < 240; ++ticks) {
        nm_tick(m, g_out);
        env_step(wave, &sim);
        const int w = peek_n163(m, P->n163.chn_n163_wave_index, 0);
        follows += w == sim.value && nm_peek(m, (uint16_t)(P->sym.env_value + slot)) == sim.value;
        if (!(seen >> (w & 31) & 1)) ++distinct;
        seen |= 1 << (w & 31);
        const uint8_t* e = exp_entry(&g_thor, j);
        in_ram &= w < exp_waves(&g_thor, j) && !memcmp(nm_n163(m)->ram + e[8] / 2, wave_bytes(&g_thor, j, w), e[9] / 2u);
    }
    EXPECT(follows == ticks, "the wave index follows the FamiStudio envelope walk of its repeats and loop every tick (%d of %d)",
           follows, ticks);
    EXPECT(in_ram && distinct >= 2, "each tick the current wave (%d distinct) is uploaded at the instrument's position", distinct);
    EXPECT(nm_release(m) == 1, "a wave-index release point: release phase (volume release %s)", volume_release ? "too" : "none");
    sim.ptr = wave[0];
    sim.repeat = 0;
    int released = 1, ended = 0;
    for (int t = 0; t < 2000; ++t) {
        const int r = nm_tick(m, g_out);
        if (r == 0) {
            ended = 1;
            break;
        }
        env_step(wave, &sim);
        released &= peek_n163(m, P->n163.chn_n163_wave_index, 0) == sim.value;
    }
    EXPECT(released, "after `$5A` the wave index continues at the release point");
    EXPECT(ended, "the release tail ends (nm_tick returns 0)");
}

static void test_phase_reset(NesMachine* m) {
    printf("N163 phase reset ($5B)\n");
    RowRef ref = {0, 0};
    int k = -1;
    for (int q = 0; q < N163_TEST_CHANNELS && k < 0; ++q) {
        if (find_row(&g_thor, CH_N163(q), 0x5B, 1, &ref)) k = q;
    }
    EXPECT(k >= 0, "the song holds an N163 phase reset row");
    if (k < 0) return;
    int mode;
    EXPECT(nm_start(m, &g_thor, ref.order, ref.row, 12000, &mode) == NM_OK, "seek to the phase reset row %d/%d", ref.order, ref.row);
    nm_set_apu_logging(m, 1);
    nm_tick_silent(m);
    static uint8_t w[3 * 1024];
    const int n = nm_write_log(m, w, 1024);
    const int regs = 0x78 - 8 * k;
    int zeroed = 0;
    for (int i = 0; i + 5 < n * 3; i += 3) {
        if (w[i + 1] == 0xF8 && w[i + 2] == regs + 1 && w[i + 4] == 0x48 && w[i + 5] == 0 && i + 17 < n * 3 && w[i + 7] == 0xF8 &&
            w[i + 8] == regs + 3 && w[i + 11] == 0 && w[i + 14] == regs + 5 && w[i + 17] == 0) {
            zeroed = 1;
        }
    }
    EXPECT(zeroed, "N163 %d: phase bytes $%02X, $%02X, $%02X written 0 in that frame", k + 1, regs + 1, regs + 3, regs + 5);
}

/* N163 unit state equality (RAM with every phase, round robin, DAC). */
static int same_n163(const NesN163* a, const NesN163* b) {
    return !memcmp(a->ram, b->ram, sizeof a->ram) && a->pos == b->pos && a->timer == b->timer && a->last == b->last &&
           a->level == b->level && a->addr == b->addr;
}

static void test_seek(NesMachine* m) {
    printf("N163 seek: exact rows from engine state, N163 RAM and phases as in straight play\n");
    const int frames = song_frames(FIRST_PASS_ROWS + LOOP_ROWS);
    Trace ref, t;
    trace_alloc(&ref, frames);
    trace_alloc(&t, frames);
    play_trace(m, &g_thor, &ref);
    NesMachine* straight = nm_create(RATE, P);
    const int targets[][2] = {{0, N163_TEST_ROWS / 2}, {1 % N163_TEST_ORDERS, 0}, {N163_TEST_ORDERS - 1, N163_TEST_ROWS - 1}};
    for (size_t i = 0; i < sizeof targets / sizeof targets[0]; ++i) {
        const int o = targets[i][0], r = targets[i][1], f0 = row_frame(o, r);
        int mode = -1, po, pr, pt;
        int res = nm_start(m, &g_thor, o, r, 12000, &mode);
        nm_position(m, &po, &pr, &pt);
        EXPECT(res == NM_OK && mode == NM_SEEK_FROM_START && po == o && pr == r && pt == 0 && nm_frames(m) == (uint32_t)f0,
               "seek %d/%d: from start, %u frames (row starts at %d)", o, r, nm_frames(m), f0);
        nm_start(straight, &g_thor, 0, 0, 0, &mode);
        for (int f = 0; f < f0; ++f) nm_tick(straight, g_out);
        EXPECT(same_n163(nm_n163(m), nm_n163(straight)), "seek %d/%d: N163 RAM, phases and round robin = audible straight play", o, r);
        nm_set_apu_logging(m, 1);
        int same = 1;
        for (int f = f0; f < f0 + 200 && f < frames; ++f) {
            nm_tick_silent(m);
            record(m, &t, f);
            same &= same_frame(&ref, &t, f);
        }
        EXPECT(same, "seek %d/%d writes the same APU and N163 registers as straight play for 200 frames", o, r);
    }
    nm_destroy(straight);
    int mode;
    EXPECT(nm_start(m, &g_thor, N163_TEST_ORDERS, 0, 12000, &mode) == NM_ERR_IMAGE, "order past the song rejected");
    static NesImage other;
    memset(&other, 0, sizeof other);
    memcpy(other.bytes, nes_ft_thor, NES_FT_THOR_SIZE);
    nm_load_image(&other, &nes_player_2a03);
    EXPECT(nm_start(m, &other, 0, 0, 0, &mode) == NM_ERR_IMAGE, "an N163 machine refuses an image loaded for the 2A03 build");
    trace_free(&ref);
    trace_free(&t);
}

static void test_live_apply(NesMachine* m) {
    printf("N163 live apply: relocation and edits at tick boundaries\n");
    const int frames = song_frames(FIRST_PASS_ROWS + 2 * LOOP_ROWS);
    Trace ref, t;
    trace_alloc(&ref, frames);
    trace_alloc(&t, frames);
    play_trace(m, &g_thor, &ref);
    NesMachine* straight = nm_create(RATE, P);
    int mode;
    nm_start(straight, &g_thor, 0, 0, 0, &mode);
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_set_apu_logging(m, 1);
    double worst_us = 0;
    int same_chip = 1;
    for (int f = 0; f < frames; ++f) {
        double a0 = now_us();
        nm_apply_image(m, (f & 1) ? &g_moved : &g_thor);
        double us = now_us() - a0;
        if (us > worst_us) worst_us = us;
        nm_tick(m, g_out);
        nm_tick(straight, g_out);
        record(m, &t, f);
        same_chip &= same_n163(nm_n163(m), nm_n163(straight));
    }
    int d = first_difference(&ref, &t, 0, frames);
    EXPECT(d < 0, "moved data (wave tables and waves too) applied before each of %d frames: same writes as straight play "
                  "(first difference %d)", frames, d);
    EXPECT(same_chip, "... and the same N163 RAM, phases and output state every frame (no wave re-upload)");
    printf("  worst apply_image: %.1f us\n", worst_us);

    /* The edited wave byte: re-uploaded at once into a channel playing it, transport and phases untouched. Straight
     * play finds two consecutive frames with a channel playing that wave (old bytes in RAM); the edit is applied
     * after the first, so the engine frame after the edit still plays it. */
    const int j = N163_TEST_EDIT_WAVE_INSTRUMENT, w = N163_TEST_EDIT_WAVE_INDEX;
    const int at = exp_entry(&g_thor, j)[8] / 2 + N163_TEST_EDIT_WAVE_OFFSET;
    EXPECT(wave_bytes(&g_thor, j, w)[N163_TEST_EDIT_WAVE_OFFSET] == N163_TEST_EDIT_WAVE_OLD &&
               wave_bytes(&g_edited, j, w)[N163_TEST_EDIT_WAVE_OFFSET] == N163_TEST_EDIT_WAVE_NEW,
           "fixture: wave %d of expansion instrument %d byte %d $%02X -> $%02X", w, j, N163_TEST_EDIT_WAVE_OFFSET,
           N163_TEST_EDIT_WAVE_OLD, N163_TEST_EDIT_WAVE_NEW);
    nm_start(straight, &g_thor, 0, 0, 0, &mode);
    int target = -1, k = -1, prev = -1;
    for (int f = 1; f <= frames && target < 0; ++f) {
        nm_tick(straight, g_out);
        int now = -1;
        for (int q = 0; q < N163_TEST_CHANNELS; ++q) {
            if (nm_peek(straight, (uint16_t)(P->sym.chn_note + CH_N163(q))) &&
                peek_n163(straight, P->n163.chn_n163_instrument, q) == j && peek_n163(straight, P->n163.chn_n163_wave_index, q) == w &&
                nm_n163(straight)->ram[at] == N163_TEST_EDIT_WAVE_OLD && (now < 0 || q == prev)) {
                now = q;
            }
        }
        if (now >= 0 && now == prev) {
            target = f;
            k = now;
        }
        prev = now;
    }
    EXPECT(target > 0, "straight play holds the edited wave on a channel for two frames");
    if (target > 0) {
        nm_start(m, &g_thor, 0, 0, 0, &mode);
        nm_start(straight, &g_thor, 0, 0, 0, &mode);
        for (int f = 1; f < target; ++f) {
            nm_tick(m, g_out);
            nm_tick(straight, g_out);
        }
        nm_apply_image(m, &g_edited);
        nm_tick(m, g_out);
        nm_tick(straight, g_out);
        int o0, r0, t0, o1, r1, t1, phases = 1;
        nm_position(m, &o0, &r0, &t0);
        nm_position(straight, &o1, &r1, &t1);
        for (int q = 0; q < N163_TEST_CHANNELS; ++q) phases &= nes_n163_phase(nm_n163(m), q) == nes_n163_phase(nm_n163(straight), q);
        EXPECT(peek_n163(m, P->n163.chn_n163_wave_index, k) == w && nm_n163(m)->ram[at] == N163_TEST_EDIT_WAVE_NEW &&
                   nm_n163(straight)->ram[at] == N163_TEST_EDIT_WAVE_OLD,
               "N163 %d playing the edited wave: RAM byte $%02X re-uploaded with the next engine frame ($%02X)", k + 1, at,
               nm_n163(m)->ram[at]);
        EXPECT(o0 == o1 && r0 == r1 && t0 == t1 && phases, "no restart: position %d/%d/%d and every phase as without the edit", o0,
               r0, t0);
    }

    /* The edited volume envelope byte and the edited note. */
    const uint8_t* dir = g_edited.bytes + NES_IMAGE_HEADER_SIZE + 4 * N163_TEST_EDIT_VOLUME_ENVELOPE;
    EXPECT(nm_region(&g_edited)[region_offset(nm_rd16(dir)) + N163_TEST_EDIT_VOLUME_OFFSET] == N163_TEST_EDIT_VOLUME_NEW &&
               dir[3] == NM_KIND_VOLUME,
           "fixture: volume envelope %d byte %d = $%02X", N163_TEST_EDIT_VOLUME_ENVELOPE, N163_TEST_EDIT_VOLUME_OFFSET,
           N163_TEST_EDIT_VOLUME_NEW);
    nm_start(m, &g_edited, N163_TEST_EDIT_ORDER, N163_TEST_EDIT_ROW, 12000, &mode);
    nm_tick_silent(m);
    const int edited_note = nm_peek(m, (uint16_t)(P->sym.chn_note + N163_TEST_EDIT_CHANNEL));
    nm_start(m, &g_thor, N163_TEST_EDIT_ORDER, N163_TEST_EDIT_ROW, 12000, &mode);
    nm_tick_silent(m);
    const int original_note = nm_peek(m, (uint16_t)(P->sym.chn_note + N163_TEST_EDIT_CHANNEL));
    nm_start(m, &g_thor, 0, 1, 12000, &mode);
    nm_tick_silent(m);
    nm_apply_image(m, &g_edited);
    int o, r, tk, heard = -1;
    for (int i = 0; i < 4000 && heard < 0; ++i) {
        nm_position(m, &o, &r, &tk);
        nm_tick_silent(m);
        if (o == N163_TEST_EDIT_ORDER && r == N163_TEST_EDIT_ROW && tk == 0) {
            heard = nm_peek(m, (uint16_t)(P->sym.chn_note + N163_TEST_EDIT_CHANNEL));
        }
    }
    EXPECT(edited_note != original_note && heard == edited_note, "edited N163 note at order %d row %d: %d (was %d, heard %d)",
           N163_TEST_EDIT_ORDER, N163_TEST_EDIT_ROW, edited_note, original_note, heard);
    nm_destroy(straight);
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

static uint32_t n163_freq(const NesMachine* m, int k) {
    const uint8_t* r = nm_n163(m)->ram + 0x78 - 8 * k;
    return (uint32_t)r[0] | (uint32_t)r[2] << 8 | (uint32_t)(r[4] & 3) << 16;
}

static void test_audition(NesMachine* m) {
    printf("N163 audition: held notes on the N163 channels, fine pitch, release\n");
    const int note = 34;
    int volume_release = 0;
    const int j = release_instrument(&g_thor, &volume_release);
    const int inst = j >= 0 ? j : 0;
    EXPECT(nm_audition(m, &g_thor, CH_N163(3), note, inst, 0) == NM_OK, "audition N163 4, expansion instrument %d", inst);
    nm_tick(m, g_out);
    const uint32_t f0 = n163_freq(m, 3);
    const uint8_t* e = exp_entry(&g_thor, inst);
    EXPECT(f0 && !(f0 & 3) && nm_n163(m)->ram[0x78 - 8 * 3 + 6] == e[8], "first tick: frequency $%05X (18 bits from 16 << 2), wave "
           "address = the instrument's position %d", f0, e[8]);
    const double en = energy(m, 120);
    int others = 1;
    for (int k = 0; k < N163_TEST_CHANNELS; ++k) others &= k == 3 || (nm_n163(m)->ram[0x7F - 8 * k] & 15) == 0;
    EXPECT(en > 0.01 && others && (nm_apu(m)->pulse[0].ctrl & 15) == 0, "held 2 s, the other channels silent (energy %.2f)", en);
    EXPECT(nm_audition(m, &g_thor, CH_N163(3), note, inst, 10) == NM_OK, "audition again with fine +10");
    nm_tick(m, g_out);
    EXPECT(n163_freq(m, 3) == f0 + (10u << 5 << 2), "fine +10 raises the frequency by 10 << 5 (8 channels' pitch shift) << 2 "
           "($%05X -> $%05X)", f0, n163_freq(m, 3));
    if (j >= 0) {
        EXPECT(nm_audition(m, &g_thor, CH_N163(3), note, j, 0) == NM_OK, "audition the releasing instrument");
        for (int t = 0; t < 30; ++t) nm_tick(m, g_out);
        int wlen;
        const uint8_t* wave = dir_envelope(&g_thor, dir_index(&g_thor, nm_rd16(exp_entry(&g_thor, j) + 6)), &wlen);
        const int slot = P->code[P->sym.channel_env - P->base + CH_N163(3)] + 2;
        EXPECT(nm_release(m) == 1, "release: `$5A`");
        nm_tick(m, g_out);
        const int ptr = nm_peek(m, (uint16_t)(P->sym.env_ptr + slot));
        EXPECT(ptr > wave[0] && ptr <= wlen, "the wave-index envelope jumped to its release point %d (pointer %d): `$5A`, not `$45`",
               wave[0], ptr);
    }
    EXPECT(nm_audition(m, &g_thor, ALL_CHANNELS, note, 0, 0) == NM_ERR_IMAGE, "channel 13 does not exist");
    EXPECT(nm_audition(m, &g_thor, CH_N163(0), note, N163_TEST_INSTRUMENTS, 0) == NM_ERR_IMAGE, "instrument past the table rejected");
    EXPECT(nm_audition(m, &g_raw, CH_N163(0), note, 0, 0) == NM_ERR_IMAGE, "raw layout cannot audition");
}

static void test_mute(NesMachine* m) {
    printf("N163 mute: the eight N163 channels in the mix\n");
    int mode;
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    double e_all = 0, e_muted = 0, e_back = 0;
    for (int f = 0; f < 600; ++f) {
        if (f == 200 || f == 400) {
            for (int c = 0; c < ALL_CHANNELS; ++c) nm_mute_channel(m, c, f == 200);
        }
        int n = nm_tick(m, g_out);
        double e = 0;
        for (int i = 0; i < 2 * n; ++i) e += g_out[i] * g_out[i];
        if (f < 200) e_all += e;
        else if (f >= 210 && f < 400) e_muted += e;
        else if (f >= 400) e_back += e;
    }
    EXPECT(e_muted < e_all * 1e-6 && e_back > e_all * 0.2, "mute silences all 13, unmute restores (energy %.1f, %.2g, %.1f)", e_all,
           e_muted, e_back);
    NesMachine* twin = nm_create(RATE, P);
    nm_start(twin, &g_thor, 0, 0, 0, &mode);
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    for (int k = 0; k < N163_TEST_CHANNELS; ++k) nm_mute_channel(m, CH_N163(k), 1);
    EXPECT(nm_n163(m)->mute == 0xFF && nm_apu(m)->mute == 0, "channels 5-12 mute the N163 unit's channels 0-7");
    int same = 1, silent = 1;
    double e2a03 = 0;
    for (int f = 0; f < 300; ++f) {
        const int n = nm_tick(m, g_out);
        for (int i = 0; i < 2 * n; ++i) e2a03 += g_out[i] * g_out[i];
        nm_tick(twin, g_out);
        same &= same_n163(nm_n163(m), nm_n163(twin));
        silent &= nes_n163_mix_level(nm_n163(m)) == 0.0f;
    }
    EXPECT(e2a03 > 0.1 && silent, "2A03 still heard, N163 silent in the mix");
    EXPECT(same, "muted N163 channels keep RAM, phases and round robin exactly as unmuted play");
    nm_destroy(twin);
    EXPECT(nm_mute_channel(m, ALL_CHANNELS, 1) == NM_ERR_IMAGE, "channel 13 does not exist");
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    EXPECT(nm_n163(m)->mute == 0, "start clears mutes (power-on)");
}

static void test_chip_engine_ops(void) {
    printf("chip engine interface: \"nes-famistudio-n163-1\"..\"-8\"\n");
    static uint8_t bytes[NM_IMAGE_MAX_SIZE];
    for (int n = 1; n <= 8; ++n) {
        char id[32];
        snprintf(id, sizeof id, "nes-famistudio-n163-%d", n);
        const ChipEngineOps* ops = chip_engine_ops(id);
        EXPECT(ops != NULL && ops != chip_engine_ops("nes-famistudio") && ops != chip_engine_ops("nes-famistudio-vrc6"),
               "%s registered", id);
        if (!ops) continue;
        const NesPlayer* p = nes_player_n163_build(n);
        EXPECT(p == &nes_player_n163[n - 1] && p->n163_channels == n && p->channels == 5 + n &&
                   ops->image_size == p->image_size && ops->image_storage >= ops->image_size,
               "%s: %d N163 channels, image size %zu", id, n, ops->image_size);
        ChipImage* img = (ChipImage*)malloc(ops->image_storage);
        memset(bytes, 0, sizeof bytes);
        memcpy(bytes, nes_n163_thor, NES_N163_THOR_SIZE);
        EXPECT(ops->load_image(img, bytes, ops->image_size + 1) == CHIP_ERR_IMAGE, "%s: another image size rejected", id);
        EXPECT(ops->load_image(img, bytes, ops->image_size) == (n == 8 ? CHIP_OK : CHIP_ERR_IMAGE),
               "%s: the 8-channel test image %s", id, n == 8 ? "loads" : "rejected (another build)");
        ChipMachine* cm = ops->create(RATE);
        EXPECT(cm != NULL, "%s: create", id);
        if (n == 8 && cm) {
            ChipMachine* au = ops->create(RATE);
            int mode = -1, order, row, tick;
            const int o1 = 1 % N163_TEST_ORDERS;
            EXPECT(ops->start(cm, img, o1, 0, 12000, &mode) == CHIP_OK && mode == CHIP_SEEK_FROM_START, "start at order %d", o1);
            const int ticks = row_frame(o1, 4) - row_frame(o1, 0);
            double e = 0;
            for (int i = 0; i < ticks; ++i) {
                int k = ops->tick(cm, g_out);
                for (int q = 0; q < 2 * (k > 0 ? k : 0); ++q) e += g_out[q] * g_out[q];
            }
            ops->position(cm, &order, &row, &tick);
            EXPECT(e > 0.01 && order == o1 && row == 4 && tick == 0, "4 rows through the ops (%d/%d/%d, energy %.2f)", order, row,
                   tick, e);
            /* The hosts pass 1-based slots. */
            EXPECT(ops->audition(au, img, CH_N163(7), 34, 1, 0) == CHIP_OK, "N163 8 audition through the ops");
            EXPECT(ops->mute(cm, CH_N163(7), 1) == CHIP_OK && ops->mute(cm, ALL_CHANNELS, 1) == CHIP_ERR_IMAGE,
                   "mute the song's N163 8; channel 13 rejected");
            EXPECT(ops->tick(cm, g_out) > 0 && ops->tick(au, g_out) > 0, "both machines tick");
            ops->destroy(au);
        }
        if (cm) ops->destroy(cm);
        free(img);
    }
}

/* The WAV export of the 8-channel test song (as nes_vrc7_test.c checks the VRC6 and VRC7 test songs): once through
 * ends where straight ticking enters the loop row again, sample for sample; one more pass adds exactly the loop's
 * ticks. */
static void test_wav(const char* dir) {
    printf("WAV export of the N163 test song\n");
    const ChipEngineOps* ops = chip_engine_ops("nes-famistudio-n163-8");
    EXPECT(ops != NULL, "nes-famistudio-n163-8 registered");
    if (!ops) return;
    static uint8_t bytes[NM_IMAGE_MAX_SIZE];
    memset(bytes, 0, sizeof bytes);
    memcpy(bytes, nes_n163_thor, NES_N163_THOR_SIZE);
    char spool[512];
    snprintf(spool, sizeof spool, "%s/nes_n163_wav.part", dir);
    const int first_ticks = song_frames(FIRST_PASS_ROWS), loop_ticks = song_frames(FIRST_PASS_ROWS + LOOP_ROWS) - first_ticks;
    const size_t cap = (size_t)(first_ticks + loop_ticks) * NM_MAX_TICK_FRAMES;
    int16_t* ref = (int16_t*)malloc(cap * 2 * sizeof *ref);
    int16_t* file = (int16_t*)malloc((cap * 2 + 22) * sizeof *file);
    ChipImage* img = (ChipImage*)malloc(ops->image_storage);
    ChipMachine* cm = ops->create(RATE);
    int mode, n_ref = 0, n_first = 0;
    EXPECT(ops->load_image(img, bytes, ops->image_size) == CHIP_OK && ops->start(cm, img, 0, 0, 0, &mode) == CHIP_OK,
           "the Thor-layout test image starts");
    for (int t = 0; t < first_ticks + loop_ticks; ++t) {
        const int n = ops->tick(cm, g_out);
        for (int i = 0; i < 2 * n; ++i) ref[n_ref * 2 + i] = chip_wav_pcm16(g_out[i]);
        n_ref += n > 0 ? n : 0;
        if (t == first_ticks - 1) n_first = n_ref;
    }
    ops->destroy(cm);
    free(img);
    for (int loops = 0; loops < 2; ++loops) {
        const char* problem = NULL;
        int capped = -1;
        const int r = chip_render_wav("nes-famistudio-n163-8", bytes, ops->image_size, loops, 0, 600, spool, &capped, &problem);
        FILE* f = r == 0 ? fopen(spool, "rb") : NULL;
        const size_t got = f ? fread(file, 2, cap * 2 + 22, f) : 0;
        if (f) fclose(f);
        remove(spool);
        const int frames = got >= 22 ? (int)(got - 22) / 2 : -1, want = loops ? n_ref : n_first;
        EXPECT(r == 0 && capped == 0 && frames == want && !memcmp(file + 22, ref, (size_t)want * 4),
               "loops=%d: %d frames, identical to straight ticking (%d frames)", loops, frames, want);
    }
    free(file);
    free(ref);
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    test_unit();
    test_images();
    NesMachine* m = nm_create(RATE, P);
    if (!m) {
        printf("nm_create failed\n");
        return 2;
    }
    EXPECT(nm_player(m) == P && P->channels == 13 && P->song_base == NES_N163_8_SONG_BASE && P->expansion == NES_EXPANSION_N163,
           "machine for the 8-channel N163 build: 13 channels, song base $%04X", NES_N163_8_SONG_BASE);
    test_playback(m, dir);
    test_wave_sequence(m);
    test_phase_reset(m);
    test_seek(m);
    test_live_apply(m);
    test_audition(m);
    test_mute(m);
    test_chip_engine_ops();
    test_wav(dir);
    nm_destroy(m);
    printf("nes_n163_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
