/* Host regression test for the NES + FDS build ("nes-famistudio-fds"): the FDS audio unit (nes_fds.c) against the
 * NESdev "FDS audio" description (registers, unit ticks, the modulation formula, envelopes, the gain latch, write
 * enable, mixing level), and the FamiStudio sound engine 4.5.3 assembled with FAMISTUDIO_EXP_FDS = 1 and
 * FAMISTUDIO_USE_FDS_AUTOMOD = 1 playing FamiStudio's own export of tools/audio/nes/fds_test.txt and the same rows in
 * the Thor Tracker image layout (nes_fds_song.h): identical register writes (APU and FDS), row timing, wave uploads,
 * mod table loads, mod speed / depth / delay / auto-mod, `$57` / `$58` overrides and instrument re-binds, the wave-index
 * sequence with its release, phase reset, true pitch, seek, live relocation and edits, audition, mute, the image rules
 * of the FDS build and the chip engine interface.
 *
 * Build and run (no Android needed): tools/audio/run_native_host_test.sh [evidence-dir]
 * Writes <dir>/nes_fds.wav (48 kHz stereo, 30 s), <dir>/nes_fds_trace.txt (per-frame APU + FDS writes) and
 * <dir>/nes_fds.nsf (the FDS player + FamiStudio's export, NSF expansion byte $7B = $04). */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "blip_buf.h"
#include "chip_engine.h"
#include "nes_apu.h"
#include "nes_fds.h"
#include "nes_fds_song.h"
#include "nes_ft_song.h"
#include "nes_machine.h"

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
#define TICK NES_FDS_UNIT_CYCLES

#define P (&nes_player_fds)
#define CH FDS_TEST_CHANNEL
#define ALL_CHANNELS NES_FDS_CHANNELS

_Static_assert(FDS_TEST_CHANNEL == NM_FDS, "the FDS channel is engine channel 5");

static float g_out[NM_MAX_TICK_FRAMES * 2];
static NesImage g_raw, g_thor, g_moved, g_edited;

/* FamiStudio's FDS note table (contract: NoteTables/famistudio_fds_note_table_{lsb,msb}.bin, FamiStudio notes 0..96). */
static const uint16_t kFdsNotes[97] = {
    0,    19,   20,   22,   23,   24,   26,   27,   29,   30,   32,   34,   36,   38,   41,   43,   46,   48,   51,   54,
    57,   61,   64,   68,   72,   77,   81,   86,   91,   97,   102,  108,  115,  122,  129,  137,  145,  153,  162,  172,
    182,  193,  205,  217,  230,  243,  258,  273,  289,  307,  325,  344,  365,  386,  409,  434,  459,  487,  516,  546,
    579,  613,  650,  688,  729,  772,  818,  867,  919,  973,  1031, 1092, 1157, 1226, 1299, 1376, 1458, 1545, 1637, 1734,
    1837, 1947, 2062, 2185, 2315, 2452, 2598, 2753, 2916, 3090, 3274, 3468, 3675, 3893, 4125, 4370, 4630,
};

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

/* ---- FDS unit checks (NESdev "FDS audio") ---------------------------------------------------- */

static void fresh(NesFds* f) {
    memset(f, 0, sizeof *f);
    nes_fds_reset(f);
}

static void put(NesFds* f, uint16_t addr, int value) { nes_fds_write(f, f->time, addr, (uint8_t)value); }

/* Advances `cycles` CPU cycles across output frames of FRAME cycles. */
static void run_cycles(NesFds* f, uint64_t cycles) {
    while (cycles) {
        const uint32_t room = FRAME - f->time;
        const uint32_t step = cycles < room ? (uint32_t)cycles : room;
        nes_fds_run(f, f->time + step);
        cycles -= step;
        if (f->time == FRAME) nes_fds_end_frame(f, FRAME);
    }
}

static void load_wave(NesFds* f, const uint8_t* w) {
    put(f, 0x4089, 0x80);
    for (int k = 0; k < 64; ++k) put(f, (uint16_t)(0x4040 + k), w[k]);
    put(f, 0x4089, 0x00);
}

static void set_freq(NesFds* f, uint32_t freq) {
    put(f, 0x4082, (int)(freq & 0xFF));
    put(f, 0x4083, (int)((freq >> 8) & 0x0F));
}

static int mod_address(const NesFds* f) { return (int)(f->mod_acc >> 13) & 31; }

/* One-cycle square (63 for positions 0-31, 0 after) at `freq`: rising edges (the wave restarting) over `ticks` unit
 * ticks; the accumulator holds ticks x freq x 64 mod 2^24 (counter 0: pitch = freq x 64). */
static int wave_edges(uint32_t freq, uint32_t ticks, int* acc_ok) {
    static NesFds f;
    fresh(&f);
    uint8_t w[64];
    for (int k = 0; k < 64; ++k) w[k] = k < 32 ? 63 : 0;
    load_wave(&f, w);
    put(&f, 0x4080, 0x80 | 32);
    put(&f, 0x4084, 0x80);
    put(&f, 0x4087, 0x80);
    set_freq(&f, freq);
    run_cycles(&f, TICK - f.time); /* align to the unit ticks */
    int edges = 0, high = 1;
    for (uint32_t i = 1; i < ticks; ++i) {
        run_cycles(&f, TICK);
        const int h = f.sample == 63;
        if (h && !high) ++edges;
        high = h;
    }
    *acc_ok = f.wave_acc == (uint32_t)((uint64_t)ticks * freq * 64u & 0xFFFFFFu);
    return edges;
}

static void test_unit_pitch(void) {
    printf("FDS unit: wave frequency CPU x freq / 2^22 by zero crossings\n");
    const uint32_t ticks = 400000;
    const double seconds = ticks * (double)TICK / CPU_HZ;
    const uint32_t freqs[] = {0x200, 0x555, 0xFFF};
    for (size_t i = 0; i < sizeof freqs / sizeof freqs[0]; ++i) {
        int acc_ok = 0;
        const int edges = wave_edges(freqs[i], ticks, &acc_ok);
        const double hz = edges / seconds, formula = CPU_HZ * freqs[i] / 4194304.0;
        EXPECT(acc_ok, "freq $%03X: accumulator = ticks x freq x 64 mod 2^24 (24 bits)", freqs[i]);
        EXPECT(fabs(hz / formula - 1.0) < 0.003, "freq $%03X: %.2f Hz measured, %.2f Hz = CPU x freq / 2^22", freqs[i], hz, formula);
    }
    /* True pitch: FamiStudio's FDS table plays engine note n two octaves below the 2A03's n with a one-cycle wave;
     * Thor Tracker plays absolute note a as engine note a + 13 (absolute 57 = A-4 = engine 70). */
    const uint8_t* lsb = P->code + (NES_FDS_FAMISTUDIO_FDS_NOTE_TABLE_LSB - P->base);
    const uint8_t* msb = P->code + (NES_FDS_FAMISTUDIO_FDS_NOTE_TABLE_MSB - P->base);
    int table_ok = 1;
    for (int n = 0; n <= 96; ++n) table_ok &= (lsb[n] | msb[n] << 8) == kFdsNotes[n];
    EXPECT(table_ok, "the player's FDS note table = FamiStudio's (NoteTables/famistudio_fds_note_table_*.bin)");
    const int notes[2] = {70, 46};
    const double want[2] = {440.0, 110.0};
    for (int i = 0; i < 2; ++i) {
        const uint32_t freq = (uint32_t)(lsb[notes[i]] | msb[notes[i]] << 8);
        int acc_ok = 0;
        const int edges = wave_edges(freq, 2000000, &acc_ok);
        const double hz = edges / (2000000.0 * TICK / CPU_HZ);
        EXPECT(freq == (i ? 258u : 1031u) && fabs(hz / want[i] - 1.0) < 0.003,
               "engine note %d: freq %u, one-cycle wave %.2f Hz by zero crossings (~%.0f Hz)", notes[i], freq, hz, want[i]);
    }
}

/* NESdev's modulation formula, written as on the wiki with signed ints. */
static uint32_t ref_pitch(int pitch, int counter, int gain) {
    int temp = counter * gain;
    if ((temp & 0x0f) && !(temp & 0x800)) temp += 0x20;
    temp += 0x400;
    temp = (temp >> 4) & 0xff;
    return (uint32_t)(pitch * temp) & 0xFFFFF;
}

static void set_mod(NesFds* f, int counter, int gain) {
    put(f, 0x4085, counter & 0x7F);
    put(f, 0x4084, 0x80 | gain);
}

static int wrap7(int v) { return ((v & 0x7F) ^ 0x40) - 0x40; }

static void test_unit_mod(void) {
    printf("FDS unit: mod table ring, ghost bit, steps, wrap, mod frequency, the modulation formula\n");
    static NesFds f;
    fresh(&f);
    uint8_t tab[32];
    for (int k = 0; k < 32; ++k) tab[k] = (uint8_t)((k * 3 + 1) & 7);
    put(&f, 0x4088, 5);
    EXPECT(f.mod[0] == 0 && mod_address(&f) == 0, "$4088 ignored while the mod unit runs ($4087 bit 7 clear)");
    put(&f, 0x4087, 0x80);
    for (int k = 0; k < 5; ++k) put(&f, 0x4088, 0x18 | tab[k]);
    EXPECT(mod_address(&f) == 5 && f.mod[4] == tab[4], "each $4088 write stores value & 7 at the address and advances it");
    for (int k = 5; k < 32; ++k) put(&f, 0x4088, tab[k]);
    EXPECT(!memcmp(f.mod, tab, 32) && mod_address(&f) == 0, "32 writes fill the ring and end where they began");

    /* Forced carry ($4087 bit 6): a step every tick; each entry acts twice (the ghost bit). */
    const uint8_t seq[32] = {1, 2, 3, 4, 5, 6, 7, 0, 1, 1, 1, 1, 7, 7, 7, 7, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3};
    static const int kStep[8] = {0, 1, 2, 4, 99, -4, -2, -1};
    put(&f, 0x4087, 0x80);
    for (int k = 0; k < 32; ++k) put(&f, 0x4088, seq[k]);
    put(&f, 0x4085, 0);
    put(&f, 0x4087, 0x40);
    run_cycles(&f, TICK - f.time);
    int counter = 0, steps_ok = 1, ghost_ok = 1;
    for (int t = 0; t < 128; ++t) {
        const int e = seq[(t / 2) & 31];
        counter = e == 4 ? 0 : wrap7(counter + kStep[e]);
        if (t) run_cycles(&f, TICK);
        else nes_fds_run(&f, f.time); /* the first tick happened at the alignment */
        steps_ok &= f.counter == counter;
        ghost_ok &= (int)(f.mod_acc >> 12 & 63) == ((t + 1) & 63);
    }
    EXPECT(steps_ok, "steps 0,+1,+2,+4,reset,-4,-2,-1 applied from the table entry at the address, wrapping in 7 bits");
    EXPECT(ghost_ok, "the 6-bit position (bits 12-17) advances every forced tick: every entry acts twice");
    put(&f, 0x4087, 0x80);
    for (int k = 0; k < 32; ++k) put(&f, 0x4088, 1);
    put(&f, 0x4085, 0x3F);
    put(&f, 0x4087, 0x40);
    run_cycles(&f, TICK);
    EXPECT(f.counter == -64, "63 + 1 wraps to -64 (%d)", f.counter);
    put(&f, 0x4087, 0x80);
    for (int k = 0; k < 32; ++k) put(&f, 0x4088, 7);
    put(&f, 0x4085, 0x40);
    put(&f, 0x4087, 0x40);
    run_cycles(&f, TICK);
    EXPECT(f.counter == 63, "-64 - 1 wraps to 63 (%d)", f.counter);

    /* Mod frequency: a carry out of bit 11 every 4096 / freq ticks. */
    fresh(&f);
    put(&f, 0x4087, 0x80);
    for (int k = 0; k < 32; ++k) put(&f, 0x4088, 1);
    put(&f, 0x4086, 0x23);
    put(&f, 0x4087, 0x01); /* $123 */
    run_cycles(&f, TICK - f.time);
    const uint32_t ticks = 1000;
    run_cycles(&f, TICK * (ticks - 1));
    const uint32_t steps = ticks * 0x123u / 4096u;
    EXPECT(f.counter == wrap7((int)steps) && (f.mod_acc >> 12) == (steps & 63) && (f.mod_acc & 0xFFF) == ticks * 0x123u % 4096u,
           "mod freq $123: %u ticks = %u steps (counter %d, position %u)", ticks, steps, f.counter, f.mod_acc >> 12);
    const uint32_t address = f.mod_acc >> 13;
    put(&f, 0x4087, 0x81);
    EXPECT((f.mod_acc & 0x1FFF) == 0 && (f.mod_acc >> 13) == address, "$4087 bit 7 forces bits 0-12 to 0, keeps the address");
    const int8_t held = f.counter;
    run_cycles(&f, TICK * 100);
    EXPECT(f.counter == held && f.mod_acc >> 13 == address, "a halted mod unit does not step");

    /* The formula: examples, then every counter and gain against the wiki's code. */
    fresh(&f);
    set_freq(&f, 0x100);
    set_mod(&f, 0, 0);
    EXPECT(nes_fds_pitch(&f) == 0x100 * 64, "counter 0: pitch x 64");
    set_mod(&f, 1, 1);
    EXPECT(nes_fds_pitch(&f) == 0x100 * 66, "counter 1, gain 1: 1 -> +$20 rounding -> $421 >> 4 = 66");
    set_mod(&f, -1, 1);
    EXPECT(nes_fds_pitch(&f) == 0x100 * 63, "counter -1, gain 1: no rounding (negative) -> 63");
    set_mod(&f, 2, 8);
    EXPECT(nes_fds_pitch(&f) == 0x100 * 65, "counter 2, gain 8: 16, no remainder -> 65");
    set_mod(&f, 63, 63);
    EXPECT(nes_fds_pitch(&f) == 0x100 * 56, "counter 63, gain 63: 3969 has bit 11 set -> no rounding, ($1381 >> 4) & $FF = 56");
    const uint32_t freqs[] = {0x001, 0x123, 0x800, 0xFFF};
    int formula_ok = 1;
    for (size_t i = 0; i < 4; ++i) {
        set_freq(&f, freqs[i]);
        for (int c = -64; c < 64; ++c) {
            for (int g = 0; g < 64; ++g) {
                set_mod(&f, c, g);
                formula_ok &= nes_fds_pitch(&f) == ref_pitch((int)freqs[i], c, g);
            }
        }
    }
    EXPECT(formula_ok, "every counter -64..63 x gain 0..63 x 4 frequencies = NESdev's formula (20 bits)");
    fresh(&f);
    set_freq(&f, 0x100);
    set_mod(&f, 1, 1);
    put(&f, 0x4087, 0x80);
    run_cycles(&f, TICK * 10 - f.time);
    EXPECT(f.wave_acc == 10u * 0x100u * 66u, "the formula applies with the mod unit halted (10 ticks of pitch $100 x 66)");
}

static void test_unit_wave(void) {
    printf("FDS unit: halt/reset, write enable hold, gain latch, master volume, level\n");
    static NesFds f;
    uint8_t ramp[64];
    for (int k = 0; k < 64; ++k) ramp[k] = (uint8_t)k;
    fresh(&f);
    load_wave(&f, ramp);
    put(&f, 0x4080, 0x80 | 32);
    set_freq(&f, 0x400); /* pitch $10000: one position every 4 ticks */
    run_cycles(&f, TICK * 37 - f.time);
    EXPECT(nes_fds_position(&f) == 9 && f.sample == 9, "position 9 after 37 ticks, sample 9 (%d)", nes_fds_position(&f));
    put(&f, 0x4083, 0x84);
    EXPECT(f.wave_acc == 0 && f.sample == 0, "$4083 bit 7: accumulator and position reset, the DAC holds the sample at $4040");
    run_cycles(&f, TICK * 50);
    EXPECT(f.wave_acc == 0, "halted: no advance");
    put(&f, 0x4083, 0x04);
    run_cycles(&f, TICK);
    EXPECT(f.wave_acc == 0x10000, "cleared: the next tick advances by the pitch");

    /* Write enable: the output holds; the wave unit runs; wave RAM writes only while enabled. */
    run_cycles(&f, TICK * 20);
    const uint8_t sample = f.sample;
    const uint32_t acc = f.wave_acc;
    put(&f, 0x4089, 0x80);
    put(&f, 0x4050, 0x2A);
    run_cycles(&f, TICK * 20);
    EXPECT(f.sample == sample && f.wave_acc == acc + 20u * 0x10000u && f.wave[0x10] == 0x2A,
           "$4089 bit 7: sample %d held while the accumulator runs; wave RAM written", sample);
    EXPECT(nes_fds_read(&f, f.time, 0x4050) == (0x40 | 0x2A), "write-enabled reads return the addressed sample | $40");
    put(&f, 0x4089, 0x00);
    EXPECT(f.sample == f.wave[nes_fds_position(&f)], "cleared: the DAC follows the position again");
    put(&f, 0x4051, 0x01);
    EXPECT(f.wave[0x11] == 0x11, "wave RAM write-protected while $4089 bit 7 is clear");
    EXPECT(nes_fds_read(&f, f.time, 0x4077) == (0x40 | f.wave[nes_fds_position(&f)]), "protected reads return the current sample");

    /* Gain latch at position 0; writing gain 0 mutes at once. */
    fresh(&f);
    load_wave(&f, ramp);
    put(&f, 0x4080, 0x80 | 20);
    EXPECT(f.gain == 20, "at position 0 the gain follows a $4080 write at once");
    set_freq(&f, 0x400);
    run_cycles(&f, TICK * 5 - f.time);
    put(&f, 0x4080, 0x80 | 30);
    EXPECT(f.vol_gain == 30 && f.gain == 20, "away from position 0 the output keeps the latched gain");
    int ticks = 0;
    while (nes_fds_position(&f) != 0 && ticks < 1000) {
        run_cycles(&f, TICK);
        ++ticks;
        if (nes_fds_position(&f) != 0 && f.gain != 20) break;
    }
    EXPECT(nes_fds_position(&f) == 0 && f.gain == 30, "the new gain is latched when the position reaches 0 (%d ticks)", ticks);
    run_cycles(&f, TICK * 9);
    put(&f, 0x4080, 0x80);
    EXPECT(f.gain == 0 && nes_fds_mix_level(&f) == 0.0f, "writing gain 0 mutes at once");
    put(&f, 0x4080, 0x80 | 25);
    EXPECT(f.gain == 0, "and a new gain waits for position 0 again");

    /* Level: DAC(sample) x min(gain, 32) / 32 x 2 / (2 + master); full = 2.4 x a 2A03 pulse at 15. */
    uint8_t top[64];
    memset(top, 63, sizeof top);
    fresh(&f);
    load_wave(&f, top);
    put(&f, 0x4080, 0x80 | 32);
    const double pulse15 = 95.88 / (8128.0 / 15.0 + 100.0);
    EXPECT(fabs(NES_FDS_FULL_LEVEL - 2.4 * pulse15) < 1e-12 && fabs(nes_fds_mix_level(&f) - NES_FDS_FULL_LEVEL) < 1e-6,
           "full output (sample 63, gain 32, master 0) = 2.4 x the 2A03 mixer's pulse at volume 15 (%.4f)", nes_fds_mix_level(&f));
    put(&f, 0x4080, 0x80 | 63);
    EXPECT(fabs(nes_fds_mix_level(&f) - NES_FDS_FULL_LEVEL) < 1e-6, "gain 63 is clamped to 32 at the output");
    put(&f, 0x4080, 0x80 | 16);
    EXPECT(fabs(nes_fds_mix_level(&f) - NES_FDS_FULL_LEVEL / 2) < 1e-6, "gain 16: half");
    put(&f, 0x4080, 0x80 | 32);
    static const double kMaster[4] = {1.0, 2.0 / 3.0, 0.5, 0.4};
    int master_ok = 1;
    for (int v = 0; v < 4; ++v) {
        put(&f, 0x4089, v);
        master_ok &= fabs(nes_fds_mix_level(&f) / NES_FDS_FULL_LEVEL - kMaster[v]) < 1e-6;
    }
    EXPECT(master_ok, "master volume 0..3: 2/2, 2/3, 2/4, 2/5");
    EXPECT(nes_fds_dac(0) == 0.0 && nes_fds_dac(63) == 1.0 && fabs(nes_fds_dac(31) - 914.0 / 2016.0) < 1e-12 &&
               nes_fds_dac(16) < nes_fds_dac(15),
           "DAC: FamiStudio's measured curve (Nes_Fds.cpp dac_lookup), normalised, with its jag at 16");
    EXPECT(nes_fds_register(0x4040) && nes_fds_register(0x408A) && !nes_fds_register(0x403F) && !nes_fds_register(0x408B) &&
               nes_fds_readable(0x407F) && nes_fds_readable(0x4090) && nes_fds_readable(0x4097) && !nes_fds_readable(0x4080) &&
               !nes_fds_readable(0x4098),
           "FDS registers: writes $4040-$408A, reads $4040-$407F and $4090-$4097");
}

static void test_unit_envelopes(void) {
    printf("FDS unit: envelopes, $408A, $4083 bit 6, reads\n");
    static NesFds f;
    fresh(&f);
    EXPECT(f.env_speed == 0xE8 && f.vol_ctrl == 0x80 && f.gain == 0, "power-on as the BIOS leaves it: $4080 = $80, $408A = $E8");
    put(&f, 0x4080, 0x40 | 5); /* envelope on, up, speed 5 */
    const uint32_t period = 8u * 6u * 233u;
    run_cycles(&f, period - 1);
    EXPECT(f.vol_gain == 0, "no step before 8 x (e + 1) x ($408A + 1) = %u cycles", period);
    run_cycles(&f, 1);
    EXPECT(f.vol_gain == 1 && f.gain == 1, "one step up at %u cycles (position 0: the output follows)", period);
    run_cycles(&f, period);
    EXPECT(f.vol_gain == 2, "and every period after");
    put(&f, 0x4080, 0x80 | 31);
    put(&f, 0x4080, 0x40);
    run_cycles(&f, 3u * 8u * 233u);
    EXPECT(f.vol_gain == 32, "increase stops at 32 (%d)", f.vol_gain);
    put(&f, 0x4080, 0x80 | 40);
    put(&f, 0x4080, 0x00);
    run_cycles(&f, 8u * 233u);
    EXPECT(f.vol_gain == 39, "decrease works from a written gain above 32 (%d)", f.vol_gain);
    put(&f, 0x4083, 0x40);
    run_cycles(&f, 10u * 8u * 233u);
    EXPECT(f.vol_gain == 39, "$4083 bit 6 stops the envelopes");
    put(&f, 0x4083, 0x00);
    put(&f, 0x408A, 0x00);
    run_cycles(&f, 10u * 8u * 233u);
    EXPECT(f.vol_gain == 39, "$408A = 0 stops the envelopes");
    put(&f, 0x408A, 0x01);
    put(&f, 0x4080, 0x00 | 2);
    run_cycles(&f, 8u * 3u * 2u);
    EXPECT(f.vol_gain == 38, "$408A = 1, speed 2: a step every 8 x 3 x 2 = 48 cycles");
    put(&f, 0x408A, 0xE8);
    put(&f, 0x4084, 0x80 | 10);
    put(&f, 0x4084, 0x40 | 3);
    run_cycles(&f, 8u * 4u * 233u);
    EXPECT(f.mod_gain == 11, "the mod envelope steps the same way (%d)", f.mod_gain);

    /* Reads. */
    fresh(&f);
    put(&f, 0x4080, 0x80 | 21);
    put(&f, 0x4084, 0x80 | 5);
    put(&f, 0x4085, 0x7D); /* -3 */
    put(&f, 0x4087, 0x80);
    for (int k = 0; k < 32; ++k) put(&f, 0x4088, k & 7);
    EXPECT(nes_fds_read(&f, f.time, 0x4090) == (0x40 | 21) && nes_fds_read(&f, f.time, 0x4092) == (0x40 | 5) &&
               nes_fds_read(&f, f.time, 0x4097) == 0x7D && nes_fds_read(&f, f.time, 0x4095) == 0x0 &&
               nes_fds_read(&f, f.time, 0x4094) == (uint8_t)((-15) >> 4),
           "$4090 gain, $4092 mod gain, $4097 counter, $4095 increment, $4094 counter x gain bits 4-11");
    for (int k = 0; k < 3; ++k) put(&f, 0x4088, k); /* the same entries again: address 3 */
    EXPECT(nes_fds_read(&f, f.time, 0x4095) == 0x4 && nes_fds_read(&f, f.time, 0x4093) == 0,
           "$4095 at entry 3 reads 4; $4093 mod accumulator bits 5-11");
    set_freq(&f, 0x800);
    run_cycles(&f, TICK * 3);
    EXPECT(nes_fds_read(&f, f.time, 0x4091) == (uint8_t)(f.wave_acc >> 12) && nes_fds_read(&f, f.time, 0x4096) == (0x40 | f.sample),
           "$4091 accumulator bits 12-19, $4096 the DAC sample");
}

/* Mute forces 0 and changes nothing else; a silent unit (no output, as when seeking) computes the same state as an
 * audible one, through mod steps, envelopes, register writes and frames. */
static void test_unit_mute_seek(void) {
    printf("FDS unit: mute and silent runs\n");
    static NesFds a, b, s;
    fresh(&a);
    uint8_t w[64];
    for (int k = 0; k < 64; ++k) w[k] = (uint8_t)((k * 37 + 11) & 63);
    load_wave(&a, w);
    put(&a, 0x4087, 0x80);
    for (int k = 0; k < 32; ++k) put(&a, 0x4088, (k * 5) & 7);
    put(&a, 0x4080, 0x40 | 2);
    put(&a, 0x4084, 0x80 | 9);
    put(&a, 0x4086, 0x40);
    put(&a, 0x4087, 0x02);
    set_freq(&a, 0x2B3);
    b = a;
    s = a;
    nes_fds_set_mute(&b, 1);
    blip_t* out = blip_new(RATE / 10);
    blip_set_rates(out, CPU_HZ, RATE);
    nes_fds_set_output(&a, out);
    nes_fds_set_output(&b, out);
    static short pcm[RATE / 10];
    int same = 1, muted = 1, heard = 0;
    for (int fr = 0; fr < 6; ++fr) {
        for (uint32_t t = 97; t < FRAME; t += 1013) {
            nes_fds_run(&a, t);
            nes_fds_run(&b, t);
            nes_fds_run(&s, t);
            same &= nes_fds_same_state(&a, &b);
            muted &= nes_fds_mix_level(&b) == 0.0f;
            heard |= nes_fds_mix_level(&a) != 0.0f;
            if (t == 97 + 1013 * 7) {
                nes_fds_write(&a, t, 0x4085, (uint8_t)(fr * 13));
                nes_fds_write(&b, t, 0x4085, (uint8_t)(fr * 13));
                nes_fds_write(&s, t, 0x4085, (uint8_t)(fr * 13));
            }
        }
        nes_fds_end_frame(&a, FRAME);
        nes_fds_end_frame(&b, FRAME);
        nes_fds_end_frame(&s, FRAME);
        blip_end_frame(out, FRAME);
        while (blip_samples_avail(out) > 0) blip_read_samples(out, pcm, RATE / 10, 0);
    }
    EXPECT(same && muted && heard, "muted: output 0, registers, RAMs, accumulators, timers and latch as unmuted");
    EXPECT(nes_fds_same_state(&a, &s), "without output (seek) every unit state equals audible play");
    EXPECT(fabsf(a.lp_k - (float)(1.0 - exp(-2.0 * M_PI * 2000.0 * 16.0 / CPU_HZ))) < 1e-6,
           "output low-pass: 1 pole at 2000 Hz stepped per 16-cycle unit tick");
    nes_fds_set_output(&a, NULL);
    nes_fds_set_output(&b, NULL);
    blip_delete(out);
}

/* ---- the FDS build playing the test song ------------------------------------------------------ */

static int rows_before(int order) {
    int n = 0;
    for (int o = 0; o < order; ++o) n += nes_fds_order_rows[o];
    return n;
}

#define FIRST_PASS_ROWS rows_before(FDS_TEST_ORDERS)
#define LOOP_ROWS (FIRST_PASS_ROWS - rows_before(FDS_TEST_LOOP))

static void song_row(int k, int* order, int* row, int* frames) {
    if (k >= FIRST_PASS_ROWS) k = rows_before(FDS_TEST_LOOP) + (k - FIRST_PASS_ROWS) % LOOP_ROWS;
    int o = 0;
    while (k >= nes_fds_order_rows[o]) k -= nes_fds_order_rows[o++];
    *order = o;
    *row = k;
    *frames = nes_fds_speed[o][k];
}

static int song_frames(int rows) {
    int total = 0, o, r, f;
    for (int k = 0; k < rows; ++k) {
        song_row(k, &o, &r, &f);
        total += f;
    }
    return total;
}

static int row_frame(int order, int row) { return song_frames(rows_before(order) + row); }

static int load_fixture(NesImage* image, const uint8_t* bytes, size_t size, const char* name) {
    memset(image, 0, sizeof *image);
    memcpy(image->bytes, bytes, size);
    int r = nm_load_image(image, P);
    EXPECT(r == 0, "%s loads (%s at %u)", name, image->info.problem ? image->info.problem : "-", image->info.problem_at);
    return r;
}

static uint16_t region_offset(uint16_t addr) { return (uint16_t)(addr - P->song_base); }

/* Expansion instrument j of a loaded image, its wave count, wave w's 64 samples and its mod table. */
static const uint8_t* exp_entry(const NesImage* im, int j) {
    return nm_region(im) + region_offset(im->info.exp_instruments) + NM_EXP_INSTRUMENT_SIZE * j;
}

static int exp_waves(const NesImage* im, int j) {
    const NmView v = nm_view(im);
    return nm_n163_waves(&v, (uint16_t)(im->info.exp_instruments + NM_EXP_INSTRUMENT_SIZE * j));
}

static const uint8_t* wave_bytes(const NesImage* im, int j, int w) {
    const uint8_t* table = nm_region(im) + region_offset(nm_rd16(exp_entry(im, j) + 9));
    return nm_region(im) + region_offset(nm_rd16(table + 2 * w));
}

static uint16_t mod_table_addr(const NesImage* im, int j) { return nm_rd16(exp_entry(im, j) + 11); }

static const uint8_t* dir_envelope(const NesImage* im, int idx, int* len) {
    const uint8_t* d = im->bytes + NES_IMAGE_HEADER_SIZE + 4 * idx;
    *len = d[2];
    return nm_region(im) + region_offset(nm_rd16(d));
}

static int dir_index(const NesImage* im, uint16_t addr) { return nm_find_envelope(&im->info, im->bytes + NES_IMAGE_HEADER_SIZE, addr); }

static uint8_t fds_ram(NesMachine* m, uint16_t addr) { return nm_peek(m, addr); }
static uint16_t fds_ram16(NesMachine* m, uint16_t addr) { return (uint16_t)(nm_peek(m, addr) | nm_peek(m, (uint16_t)(addr + 1)) << 8); }

/* Whether the chip's 32-entry ring holds `table` from some address (the engine writes it from wherever the address is). */
static int is_rotation(const uint8_t* ring, const uint8_t* table) {
    for (int r = 0; r < 32; ++r) {
        int same = 1;
        for (int k = 0; k < 32 && same; ++k) same = ring[(r + k) & 31] == table[k];
        if (same) return 1;
    }
    return 0;
}

/* After a frame: the FDS channel's current wave (wave index of its instrument) in chip wave RAM, and the mod table the
 * engine loaded (famistudio_fds_mod_envelope = the instrument's table) in the chip's ring. */
static void check_chip_tables(NesMachine* m, const NesImage* im, int* wave_checked, int* wave_bad, int* mod_checked, int* mod_bad) {
    const NesFds* f = nm_fds(m);
    const int j = fds_ram(m, P->fds.chn_fds_instrument), w = fds_ram(m, P->fds.fds_wave_index);
    const uint16_t loaded = fds_ram16(m, P->fds.fds_mod_envelope);
    if (j >= im->info.instruments) return;
    if (nm_peek(m, (uint16_t)(P->sym.chn_note + CH)) && w != 0xFF) {
        ++*wave_checked;
        if (w >= exp_waves(im, j) || memcmp(f->wave, wave_bytes(im, j, w), 64)) ++*wave_bad;
    }
    if (loaded) {
        ++*mod_checked;
        if (loaded != mod_table_addr(im, j) || !is_rotation(f->mod, nm_region(im) + region_offset(loaded))) ++*mod_bad;
    }
}

/* After a frame: the mod unit as famistudio_update_fds_channel_sound leaves it. Note cut or delay counting: halted
 * ($4087 bit 7), gain 0. Depth 0: gain 0. Else gain = depth, running at the manual speed or, with auto-mod,
 * min($FFF, (frequency x numerator mod 2^16) / denominator). */
static int check_mod_unit(NesMachine* m, int* enabled) {
    const NesFds* f = nm_fds(m);
    const int note = nm_peek(m, (uint16_t)(P->sym.chn_note + CH));
    const int delay = fds_ram(m, P->fds.fds_mod_delay_counter), depth = fds_ram(m, P->fds.fds_mod_depth);
    const int numer = fds_ram(m, P->fds.fds_automod_numer), denom = fds_ram(m, P->fds.fds_automod_denom);
    if (!note || delay) return (f->mod_hi & 0x80) && f->mod_gain == 0;
    if (!depth) return f->mod_gain == 0;
    ++*enabled;
    uint32_t want = fds_ram16(m, P->fds.fds_mod_speed) & 0xFFF;
    if (numer) {
        want = ((nes_fds_frequency(f) * (uint32_t)numer) & 0xFFFF) / (uint32_t)(denom ? denom : 1);
        if (want > 0xFFF) want = 0xFFF;
    }
    return f->mod_gain == depth && !(f->mod_hi & 0x80) && nes_fds_mod_frequency(f) == want;
}

/* Per-frame sound register writes (APU and FDS) as (address low, address high, value). */
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
    case 0x46: case 0x47: case 0x48: case 0x4E: case 0x4F: case 0x52: case 0x54: case 0x55: case 0x58: return 1;
    case 0x49: case 0x4B: case 0x51: case 0x57: return 2;
    default: return 0;
    }
}

static int terminator(uint8_t b) { return b < 0x40 || b == 0x40 || b == 0x45 || b == 0x50 || b == 0x59 || (b >= 0x80 && (b & 1)); }

/* What the FDS channel's row holds (first row of its entry only). */
typedef struct {
    int valid, delay, inst, has57, has58, has53;
    int instrument, speed, depth;
} RowInfo;

static RowInfo g_rows[256][256];

static void scan_rows(const NesImage* im) {
    memset(g_rows, 0, sizeof g_rows);
    const NmView v = nm_view(im);
    const uint32_t base = P->song_base;
    const uint8_t* s = v.region;
    for (int o = 0; o < im->info.orders; ++o) {
        const uint8_t* e = s + (im->info.streams[CH] + 4u * (uint32_t)o - base);
        uint16_t a = nm_rd16(e + 2);
        int row = 0;
        for (int k = 0; k < e[1]; ++k) {
            NmEntry entry;
            if (nm_walk_entry(&v, a, CH, &entry)) return;
            RowInfo* ri = &g_rows[o][row];
            ri->valid = 1;
            for (uint32_t p = a;;) {
                const uint8_t b = s[p - base];
                if (terminator(b)) break;
                if (b >= 0x80) {
                    ri->inst = 1;
                    ri->instrument = (b & 0x7F) >> 1;
                } else if (b == 0x54) {
                    ri->inst = 1;
                    ri->instrument = s[p + 1 - base];
                } else if (b == 0x57) {
                    ri->has57 = 1;
                    ri->speed = s[p + 1 - base] | s[p + 2 - base] << 8;
                } else if (b == 0x58) {
                    ri->has58 = 1;
                    ri->depth = s[p + 1 - base];
                } else if (b == 0x47) {
                    ri->delay = 1;
                } else if (b == 0x53) {
                    ri->has53 = 1;
                }
                p += 1u + (b >= 0x40 && b < 0x70 ? (uint32_t)prefix_operands(b) : 0u);
            }
            row += entry.rows;
            a = entry.end;
        }
    }
}

static int find_row(int (*want)(const RowInfo*), int* order, int* row) {
    for (int o = 0; o < g_thor.info.orders; ++o) {
        for (int r = 0; r < g_thor.info.rows[o]; ++r) {
            if (g_rows[o][r].valid && want(&g_rows[o][r])) {
                *order = o;
                *row = r;
                return 1;
            }
        }
    }
    return 0;
}

static int row_has53(const RowInfo* ri) { return ri->has53 && !ri->delay; }

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

/* Writes `bytes` at the first row entry of channel `c` of a copy of the Thor image. */
static void patch_entry(NesImage* bad, int c, const uint8_t* bytes, int n) {
    *bad = g_thor;
    memcpy(bad->bytes + NM_REGION_OFFSET + first_entry(&g_thor, c), bytes, (size_t)n);
}

static void put16(uint8_t* p, uint32_t v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
}

static void test_images(void) {
    printf("FDS images: build identity, 6 streams, the expansion instrument table, FDS opcode rules\n");
    nm_build_raw_image(&g_raw, P, nes_fds_raw, NES_FDS_RAW_SIZE, NULL, 0, 0, 0, 0, 0);
    EXPECT(nm_load_image(&g_raw, P) == 0, "FamiStudio's raw FDS export loads (%s)", g_raw.info.problem ? g_raw.info.problem : "-");
    EXPECT(g_raw.info.tempo_ntsc == FDS_TEST_TEMPO_NTSC, "raw: NTSC tempo word after the 7-byte header and 6 channel pointers (%u)",
           g_raw.info.tempo_ntsc);
    load_fixture(&g_thor, nes_fds_thor, NES_FDS_THOR_SIZE, "Thor layout");
    load_fixture(&g_moved, nes_fds_moved, NES_FDS_MOVED_SIZE, "moved");
    load_fixture(&g_edited, nes_fds_edited, NES_FDS_EDITED_SIZE, "edited");
    const NesImageInfo* i = &g_thor.info;
    EXPECT(i->orders == FDS_TEST_ORDERS && i->loop == FDS_TEST_LOOP && i->instruments == FDS_TEST_INSTRUMENTS &&
               i->tempo_ntsc == FDS_TEST_TEMPO_NTSC && i->exp_instruments >= P->song_base,
           "Thor layout: %u orders, loop %u, %u instruments, tempo step %u, expansion table $%04X", i->orders, i->loop,
           i->instruments, i->tempo_ntsc, i->exp_instruments);
    int rows_ok = 1;
    for (int o = 0; o < FDS_TEST_ORDERS; ++o) {
        rows_ok &= i->rows[o] == nes_fds_order_rows[o] && i->speed[o] == nes_fds_speed[o][0];
    }
    EXPECT(rows_ok, "each order's rows and starting speed as fds_test.txt");
    int streams_ok = 1;
    for (int c = 0; c < ALL_CHANNELS; ++c) streams_ok &= i->streams[c] >= P->song_base;
    EXPECT(streams_ok && P->channels == 6 && P->expansion == NES_EXPANSION_FDS, "an order stream for each of the 6 channels");
    int waves_ok = 1, wave_dir = 1;
    for (int j = 0; j < i->instruments; ++j) {
        const int idx = dir_index(&g_thor, nm_rd16(exp_entry(&g_thor, j) + 6));
        waves_ok &= exp_waves(&g_thor, j) >= 1;
        wave_dir &= idx == 256 + j && g_thor.bytes[NES_IMAGE_HEADER_SIZE + 4 * idx + 3] == NM_KIND_WAVE;
    }
    EXPECT(waves_ok && wave_dir, "every expansion instrument j: wave-index envelope at directory 256 + j (kind 4), >= 1 wave");
    EXPECT(exp_waves(&g_thor, FDS_TEST_INSTRUMENT_FWAVES) == FDS_TEST_WAVE_COUNT_FWAVES,
           "FWAVES: %d waves in its table", FDS_TEST_WAVE_COUNT_FWAVES);

    /* The builds refuse each other's images. */
    static NesImage other, bad;
    memset(&other, 0, sizeof other);
    memcpy(other.bytes, nes_ft_thor, NES_FT_THOR_SIZE);
    EXPECT(nm_load_image(&other, P) != 0, "a 2A03 image on the FDS build: rejected (%s)", other.info.problem);
    other = g_thor;
    EXPECT(nm_load_image(&other, &nes_player_2a03) != 0 && nm_load_image(&other, &nes_player_vrc6) != 0 &&
               nm_load_image(&other, &nes_player_n163[0]) != 0,
           "an FDS image on the 2A03, VRC6 and N163 builds: rejected");

    /* `$57`-`$59` are FDS opcodes only (the 2A03 build's message unchanged). */
    memset(&other, 0, sizeof other);
    memcpy(other.bytes, nes_ft_thor, NES_FT_THOR_SIZE);
    EXPECT(nm_load_image(&other, &nes_player_2a03) == 0, "the 2A03 test image loads on its build");
    {
        const uint32_t at = NM_REGION_OFFSET + (nm_rd16(nm_region(&other) + (other.info.streams[0] - NES_SONG_BASE) + 2) - NES_SONG_BASE);
        static NesImage op;
        for (uint8_t b = 0x57; b <= 0x59; ++b) {
            op = other;
            op.bytes[at] = b;
            expect_problem(&op, &nes_player_2a03, "opcode not allowed in a row", "an FDS opcode on the 2A03 build");
        }
    }
    bad = g_thor;
    bad.bytes[NES_IMAGE_HEADER_SIZE + 4 * 256 + 3] = 5;
    expect_problem(&bad, P, "envelope kind outside 0..4", "directory kind 5 in an FDS image");

    /* Expansion instrument 0. */
    const uint32_t e0 = NM_REGION_OFFSET + region_offset(i->exp_instruments);
    const uint32_t end = P->song_base + i->size;
    bad = g_thor;
    put16(bad.bytes + e0 + 9, end - 1);
    expect_problem(&bad, P, "FDS wave table outside the data", "wave table at the end of the data");
    bad = g_thor;
    put16(bad.bytes + NM_REGION_OFFSET + region_offset(nm_rd16(g_thor.bytes + e0 + 9)), end - 63);
    expect_problem(&bad, P, "FDS wave outside the data", "a wave running past the data");
    bad = g_thor;
    bad.bytes[NM_REGION_OFFSET + region_offset(nm_rd16(nm_region(&g_thor) + region_offset(nm_rd16(g_thor.bytes + e0 + 9))))] = 64;
    expect_problem(&bad, P, "FDS wave sample above 63", "a wave sample of 64");
    bad = g_thor;
    put16(bad.bytes + e0 + 11, end - 31);
    expect_problem(&bad, P, "FDS mod table outside the data", "a mod table running past the data");
    bad = g_thor;
    bad.bytes[NM_REGION_OFFSET + region_offset(nm_rd16(g_thor.bytes + e0 + 11)) + 5] = 8;
    expect_problem(&bad, P, "FDS mod table entry above 7", "a mod table entry of 8");
    bad = g_thor;
    bad.bytes[e0 + 13] = 0x80, bad.bytes[e0 + 14] = 1;
    expect_problem(&bad, P, "FDS auto-mod numerator outside 1..32", "auto-mod 0/1");
    bad.bytes[e0 + 13] = 0x80 | 33;
    expect_problem(&bad, P, "FDS auto-mod numerator outside 1..32", "auto-mod 33/1");
    bad.bytes[e0 + 13] = 0x80 | 1, bad.bytes[e0 + 14] = 0;
    expect_problem(&bad, P, "FDS auto-mod denominator outside 1..32", "auto-mod 1/0");
    bad.bytes[e0 + 14] = 33;
    expect_problem(&bad, P, "FDS auto-mod denominator outside 1..32", "auto-mod 1/33");
    bad.bytes[e0 + 13] = 0x80 | 32, bad.bytes[e0 + 14] = 32;
    EXPECT(nm_load_image(&bad, P) == 0, "auto-mod 32/32 loads (%s)", bad.info.problem ? bad.info.problem : "-");
    bad.bytes[e0 + 13] = 0x00, bad.bytes[e0 + 14] = 0x10;
    expect_problem(&bad, P, "FDS mod speed above $0FFF", "manual speed $1000");
    bad.bytes[e0 + 13] = 0x7F, bad.bytes[e0 + 14] = 0x0F;
    EXPECT(nm_load_image(&bad, P) == 0, "manual speed $0F7F loads (%s)", bad.info.problem ? bad.info.problem : "-");
    bad = g_thor;
    bad.bytes[e0 + 6] = bad.bytes[e0 + 0];
    bad.bytes[e0 + 7] = bad.bytes[e0 + 1];
    expect_problem(&bad, P, "expansion instrument envelope of the wrong kind", "a volume envelope as wave-index envelope");

    /* FDS opcodes: `$57` / `$58` / `$59` only on the FDS channel, with their ranges; no `$4F`, `$52`, N163 or VRC opcodes. */
    const uint8_t speed_hi[] = {0x57, 0x00, 0x10}, depth_hi[] = {0x58, 0x40};
    patch_entry(&bad, CH, speed_hi, 3);
    expect_problem(&bad, P, "FDS mod speed above $0FFF", "`$57` $1000");
    patch_entry(&bad, CH, depth_hi, 2);
    expect_problem(&bad, P, "FDS mod depth above 63", "`$58` 64");
    const uint8_t op57[] = {0x57}, op58[] = {0x58}, op59[] = {0x59}, op4f[] = {0x4F}, op52[] = {0x52}, op53[] = {0x53};
    patch_entry(&bad, 0, op57, 1);
    expect_problem(&bad, P, "FDS mod speed off the FDS channel", "`$57` on pulse 1");
    patch_entry(&bad, 1, op58, 1);
    expect_problem(&bad, P, "FDS mod depth off the FDS channel", "`$58` on pulse 2");
    patch_entry(&bad, 2, op59, 1);
    expect_problem(&bad, P, "FDS release off the FDS channel", "`$59` on the triangle");
    patch_entry(&bad, CH, op4f, 1);
    expect_problem(&bad, P, "duty effect on the FDS channel", "`$4F` on the FDS channel");
    patch_entry(&bad, CH, op52, 1);
    expect_problem(&bad, P, "opcode not allowed in a row", "`$52` on the FDS channel");
    patch_entry(&bad, 2, op53, 1);
    expect_problem(&bad, P, "phase reset only on the pulses and the FDS channel", "`$53` on the triangle");
    static const uint8_t kForeign[] = {0x55, 0x56, 0x5A, 0x5B};
    for (size_t k = 0; k < sizeof kForeign; ++k) {
        patch_entry(&bad, CH, &kForeign[k], 1);
        expect_problem(&bad, P, "opcode not allowed in a row", "a VRC6/VRC7/N163 opcode on the FDS build");
    }
    scan_rows(&g_thor);
    int o, r;
    EXPECT(find_row(row_has53, &o, &r), "the song holds an FDS phase reset (`$53`) row");

    /* Header problems. */
    bad = g_thor;
    bad.bytes[8] = (uint8_t)NES_SONG_BASE, bad.bytes[9] = (uint8_t)(NES_SONG_BASE >> 8);
    EXPECT(nm_load_image(&bad, P) != 0, "the 2A03 song base on the FDS build: rejected (%s)", bad.info.problem);
    bad = g_thor;
    bad.bytes[10] = (uint8_t)(P->region_size + 1), bad.bytes[11] = (uint8_t)((P->region_size + 1) >> 8);
    EXPECT(nm_load_image(&bad, P) != 0, "data larger than the FDS region: rejected (%s)", bad.info.problem);
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
    snprintf(path, sizeof path, "%s/nes_fds.wav", dir);
    write_wav(path, wav, frames);
    printf("  wrote %s (%.1f s)\n", path, frames / (double)RATE);
    snprintf(path, sizeof path, "%s/nes_fds_trace.txt", dir);
    FILE* ft = fopen(path, "w");
    if (ft) {
        fprintf(ft, "# frame: APU and FDS register writes as address=value (hex), FamiStudio 4.5.3 FDS player (auto-mod, "
                    "FamiTracker tempo), fds_test\n");
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
    snprintf(path, sizeof path, "%s/nes_fds.nsf", dir);
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
        strcpy((char*)h + 0x0E, "Thor Tracker FDS test");
        strcpy((char*)h + 0x2E, "Thor Tracker");
        strcpy((char*)h + 0x4E, "FamiStudio 4.5.3 engine");
        h[0x6E] = 0xFF, h[0x6F] = 0x40;
        h[0x78] = 0x20, h[0x79] = 0x4E;
        h[0x7B] = 0x04; /* expansion: Famicom Disk System (NsfFile.cs) */
        fwrite(h, 1, sizeof h, fn);
        static uint8_t body[0x8000];
        const size_t size = (size_t)(P->song_base - 0x8000) + NES_FDS_RAW_SIZE;
        memset(body, 0, sizeof body);
        memcpy(body, P->code, P->size);
        memcpy(body + (P->song_base - 0x8000), nes_fds_raw, NES_FDS_RAW_SIZE);
        fwrite(body, 1, size, fn);
        fclose(fn);
    }
}

static void test_playback(NesMachine* m, const char* dir) {
    const int pass_rows = FIRST_PASS_ROWS + 2 * LOOP_ROWS;
    const int frames = song_frames(pass_rows);
    printf("FamiStudio's FDS export of fds_test.txt: %d rows (first pass + 2 loops), %d frames\n", pass_rows, frames);
    Trace raw, thor;
    trace_alloc(&raw, frames);
    trace_alloc(&thor, frames);
    play_trace(m, &g_raw, &raw);
    int mode, faults = 0, nans = 0, fds_writes = 0, apu_writes = 0, bad_rows = 0, k = 0, row_start = 0;
    int wave_checked = 0, wave_bad = 0, mod_checked = 0, mod_bad = 0, unit_bad = 0, unit_enabled = 0;
    int n57 = 0, bad57 = 0, n58 = 0, bad58 = 0, rebinds = 0, bad_rebinds = 0, after57 = 0;
    uint32_t worst_call = 0;
    double sum2 = 0, peak = 0, t0 = now_us();
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_set_apu_logging(m, 1);
    const uint64_t cycles0 = nm_driver_cycles(m);
    for (int f = 0; f < frames; ++f) {
        int po, pr, pt, o = -1, r = -1;
        nm_position(m, &po, &pr, &pt);
        if (pt == 0) {
            int len;
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
            const uint16_t a = (uint16_t)(thor.w[f][3 * i] | thor.w[f][3 * i + 1] << 8);
            if (a <= 0x4017) ++apu_writes;
            else ++fds_writes;
        }
        check_chip_tables(m, &g_thor, &wave_checked, &wave_bad, &mod_checked, &mod_bad);
        unit_bad += !check_mod_unit(m, &unit_enabled);
        /* `$57` / `$58` and re-binds, after the frame that read the row. */
        if (o >= 0 && g_rows[o][r].valid && !g_rows[o][r].delay) {
            const RowInfo* ri = &g_rows[o][r];
            const uint8_t* e = exp_entry(&g_thor, ri->inst ? ri->instrument : 0);
            if (ri->has57) {
                ++n57;
                bad57 += fds_ram16(m, P->fds.fds_mod_speed) != ri->speed;
                after57 = 1;
            }
            if (ri->has58) {
                ++n58;
                bad58 += fds_ram(m, P->fds.fds_mod_depth) != ri->depth;
            }
            if (ri->inst && !ri->has57) {
                const int own = e[13] & 0x80 ? fds_ram(m, P->fds.fds_automod_numer) == (e[13] & 0x7F) &&
                                                   fds_ram(m, P->fds.fds_automod_denom) == e[14]
                                             : fds_ram(m, P->fds.fds_automod_numer) == 0 &&
                                                   fds_ram16(m, P->fds.fds_mod_speed) == (e[13] | e[14] << 8);
                if (after57) ++rebinds;
                after57 = 0;
                bad_rebinds += !own;
            }
            if (ri->inst && !ri->has58) bad_rebinds += fds_ram(m, P->fds.fds_mod_depth) != e[8] >> 2;
        }
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
    EXPECT(fds_writes > frames * 4 && apu_writes > frames * 3, "the engine writes the FDS (%d) and the APU (%d) every frame",
           fds_writes, apu_writes);
    EXPECT(wave_checked > frames / 2 && wave_bad == 0, "the chip's wave RAM holds the FDS instrument's current wave (%d checks, %d wrong)",
           wave_checked, wave_bad);
    EXPECT(mod_checked > frames / 2 && mod_bad == 0,
           "the chip's mod ring holds the current instrument's mod table, famistudio_fds_mod_envelope its pointer (%d checks, %d wrong)",
           mod_checked, mod_bad);
    EXPECT(unit_bad == 0 && unit_enabled > 0,
           "every frame the mod unit is halted with gain 0 (cut, delay), off (depth 0) or at depth and speed / auto-mod speed "
           "min($FFF, freq x n / d) (%d wrong, %d frames modulating)", unit_bad, unit_enabled);
    EXPECT(n57 > 0 && bad57 == 0, "`$57` rows set famistudio_fds_mod_speed to their word (%d rows, %d wrong)", n57, bad57);
    EXPECT(n58 > 0 && bad58 == 0, "`$58` rows set famistudio_fds_mod_depth (%d rows, %d wrong)", n58, bad58);
    EXPECT(rebinds > 0 && bad_rebinds == 0,
           "attacked notes with an instrument and no `$57`/`$58` take the sound's own speed/auto-mod and depth (%d re-binds after "
           "`$57`, %d wrong)", rebinds, bad_rebinds);
    printf("  rms %.4f, peak %.4f, engine frame worst %u / mean %.0f CPU cycles, %.1f us per tick (%.0fx realtime)\n", rms, peak,
           worst_call, mean_call, per_tick, 16639.0 / per_tick);
    int d = first_difference(&raw, &thor, 0, frames);
    EXPECT(d < 0, "Thor layout writes the same APU and FDS registers as FamiStudio's export, all %d frames (first difference %d)",
           frames, d);
    EXPECT(bad_rows == 0 && k == pass_rows, "%d row starts at the frames the speeds give, loop to order %d (%d wrong)", k,
           FDS_TEST_LOOP, bad_rows);

    /* nm_apu_log keeps reporting only the APU. */
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_set_apu_logging(m, 1);
    nm_tick_silent(m);
    uint8_t pairs[1024];
    const int n_apu = nm_apu_log(m, pairs, 512);
    int apu_only = 1;
    for (int i = 0; i < n_apu; ++i) apu_only &= pairs[2 * i] <= 0x17;
    nm_tick_silent(m);
    static uint8_t triples[3 * 1024];
    const int n_all = nm_write_log(m, triples, 1024);
    int fds_in_all = 0;
    for (int i = 0; i < n_all; ++i) fds_in_all += triples[3 * i + 1] == 0x40 && triples[3 * i] >= 0x40;
    EXPECT(n_apu > 0 && apu_only && fds_in_all > 0, "apu_log: %d APU pairs, none FDS; write_log: %d writes, %d of them FDS", n_apu,
           n_all, fds_in_all);

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

#define WAVE_SLOT (P->code[P->sym.channel_env - P->base + CH] + 2) /* FAMISTUDIO_ENV_FDS_WAVE_IDX_OFF */

static void test_wave_sequence(NesMachine* m) {
    printf("FDS wave index: repeats, loop and release (`$59`) through the engine\n");
    const int j = FDS_TEST_INSTRUMENT_FWAVES;
    int wlen, vlen;
    const uint8_t* wave = dir_envelope(&g_thor, dir_index(&g_thor, nm_rd16(exp_entry(&g_thor, j) + 6)), &wlen);
    const uint8_t* vol = dir_envelope(&g_thor, dir_index(&g_thor, nm_rd16(exp_entry(&g_thor, j))), &vlen);
    EXPECT(wave[0] && exp_waves(&g_thor, j) >= 2, "FWAVES: a wave sequence of %d waves with a release point", exp_waves(&g_thor, j));
    if (!wave[0]) return;
    EXPECT(nm_audition(m, &g_thor, CH, 58, j, 0) == NM_OK, "audition FWAVES on the FDS channel");
    EnvSim sim = {1, 0, 0};
    int follows = 0, ticks = 0, in_ram = 1, distinct = 0, seen = 0;
    for (; ticks < 240; ++ticks) {
        nm_tick(m, g_out);
        env_step(wave, &sim);
        const int w = fds_ram(m, P->fds.fds_wave_index);
        follows += w == sim.value && nm_peek(m, (uint16_t)(P->sym.env_value + WAVE_SLOT)) == sim.value;
        if (!(seen >> (w & 31) & 1)) ++distinct;
        seen |= 1 << (w & 31);
        in_ram &= w < exp_waves(&g_thor, j) && !memcmp(nm_fds(m)->wave, wave_bytes(&g_thor, j, w), 64);
    }
    EXPECT(follows == ticks, "the wave index follows the FamiStudio envelope walk of its repeats and loop every tick (%d of %d)",
           follows, ticks);
    EXPECT(in_ram && distinct >= 2, "each tick the current wave (%d distinct) is in the chip's wave RAM", distinct);
    EXPECT(nm_release(m) == 1, "a release point: release phase (volume release %s)", vol[0] ? "too" : "none");
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
        released &= fds_ram(m, P->fds.fds_wave_index) == sim.value;
    }
    EXPECT(released, "after `$59` the wave index continues at the release point");
    EXPECT(ended, "the release tail ends (nm_tick returns 0)");
}

static void test_mod_delay_automod(NesMachine* m) {
    printf("FDS modulation: delay, manual speed, auto-mod\n");
    const int d = FDS_TEST_MOD_DELAY_FSAW, depth = FDS_TEST_MOD_DEPTH_FSAW;
    EXPECT(d >= 2 && depth > 0, "fixture: FSAW delay %d >= 2, depth %d", d, depth);
    EXPECT(nm_audition(m, &g_thor, CH, 50, FDS_TEST_INSTRUMENT_FSAW, 0) == NM_OK, "audition FSAW");
    int halted_ok = 1;
    for (int f = 1; f < d; ++f) {
        nm_tick(m, g_out);
        halted_ok &= (nm_fds(m)->mod_hi & 0x80) && nm_fds(m)->mod_gain == 0;
    }
    nm_tick(m, g_out);
    EXPECT(halted_ok && !(nm_fds(m)->mod_hi & 0x80) && nm_fds(m)->mod_gain == depth,
           "delay %d: modulation reset for frames 1..%d, enabled in frame %d (`dec; bne`)", d, d - 1, d);

    EXPECT(nm_audition(m, &g_thor, CH, 50, FDS_TEST_INSTRUMENT_FSINE, 0) == NM_OK, "audition FSINE");
    nm_tick(m, g_out);
    EXPECT(nes_fds_mod_frequency(nm_fds(m)) == FDS_TEST_MOD_SPEED_FSINE && nm_fds(m)->mod_gain == FDS_TEST_MOD_DEPTH_FSINE &&
               !(nm_fds(m)->mod_hi & 0x80),
           "FSINE: mod speed %d, depth %d from the first frame (delay 0)", FDS_TEST_MOD_SPEED_FSINE, FDS_TEST_MOD_DEPTH_FSINE);

    const int n = FDS_TEST_AUTOMOD_NUMER_FAUTO12, q = FDS_TEST_AUTOMOD_DENOM_FAUTO12;
    const int notes[] = {30, 50, 70};
    for (size_t i = 0; i < 3; ++i) {
        nm_audition(m, &g_thor, CH, notes[i], FDS_TEST_INSTRUMENT_FAUTO12, 0);
        for (int t = 0; t < 1 + FDS_TEST_MOD_DELAY_FAUTO12; ++t) nm_tick(m, g_out);
        const uint32_t freq = nes_fds_frequency(nm_fds(m));
        uint32_t want = (freq * (uint32_t)n & 0xFFFF) / (uint32_t)q;
        if (want > 0xFFF) want = 0xFFF;
        EXPECT(nes_fds_mod_frequency(nm_fds(m)) == want, "FAUTO12 (%d/%d), note %d: mod speed = freq %u x %d / %d = %u (%u)", n, q,
               notes[i], freq, n, q, want, nes_fds_mod_frequency(nm_fds(m)));
    }
}

static void test_phase_reset(NesMachine* m) {
    printf("FDS phase reset (`$53`)\n");
    int o = 0, r = 0;
    if (!find_row(row_has53, &o, &r)) return;
    int mode;
    EXPECT(nm_start(m, &g_thor, o, r, 12000, &mode) == NM_OK, "seek to the phase reset row %d/%d", o, r);
    nm_set_apu_logging(m, 1);
    nm_tick_silent(m);
    static uint8_t w[3 * 1024];
    const int n = nm_write_log(m, w, 1024);
    const int prev = fds_ram(m, P->fds.fds_prev_hi);
    int reset = 0;
    for (int i = 0; i + 1 < n; ++i) {
        const uint8_t* a = w + 3 * i;
        const uint8_t* b = a + 3;
        if (a[0] == 0x83 && a[1] == 0x40 && a[2] == (prev | 0x80) && b[0] == 0x83 && b[1] == 0x40 && b[2] == prev) reset = 1;
    }
    EXPECT(reset, "$4083 = prev_hi | $80 then prev_hi ($%02X) in that frame (famistudio_process_phase_resets)", prev);
}

static void test_seek(NesMachine* m) {
    printf("FDS seek: exact rows from engine state, FDS unit state as in straight play\n");
    const int frames = song_frames(FIRST_PASS_ROWS + LOOP_ROWS);
    Trace ref, t;
    trace_alloc(&ref, frames);
    trace_alloc(&t, frames);
    play_trace(m, &g_thor, &ref);
    NesMachine* straight = nm_create(RATE, P);
    const int last = FDS_TEST_ORDERS - 1;
    const int targets[][2] = {{0, nes_fds_order_rows[0] / 2}, {1 % FDS_TEST_ORDERS, 0}, {last, nes_fds_order_rows[last] - 1}};
    for (size_t i = 0; i < sizeof targets / sizeof targets[0]; ++i) {
        const int o = targets[i][0], r = targets[i][1], f0 = row_frame(o, r);
        int mode = -1, po, pr, pt;
        int res = nm_start(m, &g_thor, o, r, 12000, &mode);
        nm_position(m, &po, &pr, &pt);
        EXPECT(res == NM_OK && mode == NM_SEEK_FROM_START && po == o && pr == r && pt == 0 && nm_frames(m) == (uint32_t)f0,
               "seek %d/%d: from start, %u frames (row starts at %d)", o, r, nm_frames(m), f0);
        nm_start(straight, &g_thor, 0, 0, 0, &mode);
        for (int f = 0; f < f0; ++f) nm_tick(straight, g_out);
        EXPECT(nes_fds_same_state(nm_fds(m), nm_fds(straight)), "seek %d/%d: every FDS unit state = audible straight play", o, r);
        nm_set_apu_logging(m, 1);
        int same = 1;
        for (int f = f0; f < f0 + 200 && f < frames; ++f) {
            nm_tick_silent(m);
            record(m, &t, f);
            same &= same_frame(&ref, &t, f);
        }
        EXPECT(same, "seek %d/%d writes the same APU and FDS registers as straight play for 200 frames", o, r);
    }
    nm_destroy(straight);
    int mode;
    EXPECT(nm_start(m, &g_thor, FDS_TEST_ORDERS, 0, 12000, &mode) == NM_ERR_IMAGE, "order past the song rejected");
    static NesImage other;
    memset(&other, 0, sizeof other);
    memcpy(other.bytes, nes_ft_thor, NES_FT_THOR_SIZE);
    nm_load_image(&other, &nes_player_2a03);
    EXPECT(nm_start(m, &other, 0, 0, 0, &mode) == NM_ERR_IMAGE, "an FDS machine refuses an image loaded for the 2A03 build");
    trace_free(&ref);
    trace_free(&t);
}

/* The first frame f (1-based, straight play) such that after frames f - 1 and f `cond` holds; 0 if none. */
static int find_frames(NesMachine* straight, int frames, int (*cond)(NesMachine*)) {
    int mode, prev = 0;
    nm_start(straight, &g_thor, 0, 0, 0, &mode);
    for (int f = 1; f <= frames; ++f) {
        nm_tick(straight, g_out);
        const int now = cond(straight);
        if (now && prev) return f;
        prev = now;
    }
    return 0;
}

static int playing_edited_wave(NesMachine* m) {
    const int at = FDS_TEST_EDIT_WAVE_OFFSET;
    return nm_peek(m, (uint16_t)(P->sym.chn_note + CH)) && fds_ram(m, P->fds.chn_fds_instrument) == FDS_TEST_EDIT_WAVE_INSTRUMENT &&
           fds_ram(m, P->fds.fds_wave_index) == FDS_TEST_EDIT_WAVE_INDEX && nm_fds(m)->wave[at] == FDS_TEST_EDIT_WAVE_OLD;
}

static int playing_edited_mod(NesMachine* m) {
    return nm_peek(m, (uint16_t)(P->sym.chn_note + CH)) && fds_ram(m, P->fds.chn_fds_instrument) == FDS_TEST_EDIT_MOD_INSTRUMENT &&
           fds_ram16(m, P->fds.fds_mod_envelope) == mod_table_addr(&g_thor, FDS_TEST_EDIT_MOD_INSTRUMENT);
}

static void test_live_apply(NesMachine* m) {
    printf("FDS live apply: relocation and edits at tick boundaries\n");
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
        same_chip &= nes_fds_same_state(nm_fds(m), nm_fds(straight));
    }
    int d = first_difference(&ref, &t, 0, frames);
    EXPECT(d < 0, "moved data (waves, wave tables and mod tables too) applied before each of %d frames: same writes as straight "
                  "play (first difference %d)", frames, d);
    EXPECT(same_chip, "... and the same FDS unit state every frame (no wave upload or mod table write)");
    printf("  worst apply_image: %.1f us\n", worst_us);

    /* The edited wave sample: re-uploaded with the next engine frame, transport untouched. */
    const int j = FDS_TEST_EDIT_WAVE_INSTRUMENT, w = FDS_TEST_EDIT_WAVE_INDEX, at = FDS_TEST_EDIT_WAVE_OFFSET;
    EXPECT(wave_bytes(&g_thor, j, w)[at] == FDS_TEST_EDIT_WAVE_OLD && wave_bytes(&g_edited, j, w)[at] == FDS_TEST_EDIT_WAVE_NEW,
           "fixture: wave %d of expansion instrument %d sample %d %d -> %d", w, j, at, FDS_TEST_EDIT_WAVE_OLD, FDS_TEST_EDIT_WAVE_NEW);
    int target = find_frames(straight, frames, playing_edited_wave);
    EXPECT(target > 0, "straight play holds the edited wave for two frames");
    if (target > 0) {
        nm_start(m, &g_thor, 0, 0, 0, &mode);
        nm_start(straight, &g_thor, 0, 0, 0, &mode);
        for (int f = 1; f < target; ++f) {
            nm_tick(m, g_out);
            nm_tick(straight, g_out);
        }
        nm_apply_image(m, &g_edited);
        EXPECT(fds_ram(m, P->fds.fds_wave_index) == 0xFF, "the changed wave sets famistudio_fds_wave_index = $FF");
        nm_tick(m, g_out);
        nm_tick(straight, g_out);
        int o0, r0, t0, o1, r1, t1;
        nm_position(m, &o0, &r0, &t0);
        nm_position(straight, &o1, &r1, &t1);
        EXPECT(fds_ram(m, P->fds.fds_wave_index) == w && nm_fds(m)->wave[at] == FDS_TEST_EDIT_WAVE_NEW &&
                   nm_fds(straight)->wave[at] == FDS_TEST_EDIT_WAVE_OLD,
               "the edited wave is uploaded with the next engine frame (sample %d = %d)", at, nm_fds(m)->wave[at]);
        EXPECT(o0 == o1 && r0 == r1 && t0 == t1, "no restart: position %d/%d/%d as without the edit", o0, r0, t0);
    }

    /* The edited mod table entry: written to the chip at once as the engine loads a table; the pointer follows. */
    const int jm = FDS_TEST_EDIT_MOD_INSTRUMENT;
    const uint16_t new_table = mod_table_addr(&g_edited, jm);
    EXPECT(nm_region(&g_thor)[region_offset(mod_table_addr(&g_thor, jm)) + FDS_TEST_EDIT_MOD_OFFSET] == FDS_TEST_EDIT_MOD_OLD &&
               nm_region(&g_edited)[region_offset(new_table) + FDS_TEST_EDIT_MOD_OFFSET] == FDS_TEST_EDIT_MOD_NEW,
           "fixture: mod table of expansion instrument %d entry %d %d -> %d", jm, FDS_TEST_EDIT_MOD_OFFSET, FDS_TEST_EDIT_MOD_OLD,
           FDS_TEST_EDIT_MOD_NEW);
    target = find_frames(straight, frames, playing_edited_mod);
    EXPECT(target > 0, "straight play holds the edited mod table's instrument for two frames");
    if (target > 0) {
        nm_start(m, &g_thor, 0, 0, 0, &mode);
        for (int f = 1; f < target; ++f) nm_tick(m, g_out);
        nm_set_apu_logging(m, 1);
        nm_apply_image(m, &g_edited);
        static uint8_t wlog[3 * 1024];
        const int n = nm_write_log(m, wlog, 1024);
        const uint8_t* table = nm_region(&g_edited) + region_offset(new_table);
        int sequence = n == 35 && wlog[0] == 0x87 && wlog[2] == 0x80 && wlog[3] == 0x84 && wlog[5] == 0x80 && wlog[6] == 0x85 &&
                       wlog[8] == 0;
        for (int k = 0; k < 32 && sequence; ++k) sequence = wlog[9 + 3 * k] == 0x88 && wlog[11 + 3 * k] == table[k];
        EXPECT(sequence, "apply writes $4087 = $80, $4084 = $80, $4085 = 0 and the 32 new entries to $4088 (%d writes)", n);
        EXPECT(is_rotation(nm_fds(m)->mod, table) && fds_ram16(m, P->fds.fds_mod_envelope) == new_table,
               "the chip's ring holds the edited table; famistudio_fds_mod_envelope = $%04X", new_table);
        nm_tick(m, g_out);
        const int m2 = nm_write_log(m, wlog, 1024);
        int rewrites = 0;
        for (int k = 0; k < m2; ++k) rewrites += wlog[3 * k] == 0x88 && wlog[3 * k + 1] == 0x40;
        EXPECT(rewrites == 0 || fds_ram(m, P->fds.chn_fds_instrument) != jm, "the engine does not write the table again (%d)", rewrites);
    }

    /* The edited volume envelope byte and the edited note. */
    const uint8_t* dir = g_edited.bytes + NES_IMAGE_HEADER_SIZE + 4 * FDS_TEST_EDIT_VOLUME_ENVELOPE;
    EXPECT(nm_region(&g_edited)[region_offset(nm_rd16(dir)) + FDS_TEST_EDIT_VOLUME_OFFSET] == FDS_TEST_EDIT_VOLUME_NEW &&
               dir[3] == NM_KIND_VOLUME,
           "fixture: volume envelope %d byte %d = $%02X", FDS_TEST_EDIT_VOLUME_ENVELOPE, FDS_TEST_EDIT_VOLUME_OFFSET,
           FDS_TEST_EDIT_VOLUME_NEW);
    nm_start(m, &g_edited, FDS_TEST_EDIT_ORDER, FDS_TEST_EDIT_ROW, 12000, &mode);
    nm_tick_silent(m);
    const int edited_note = nm_peek(m, (uint16_t)(P->sym.chn_note + FDS_TEST_EDIT_CHANNEL));
    nm_start(m, &g_thor, FDS_TEST_EDIT_ORDER, FDS_TEST_EDIT_ROW, 12000, &mode);
    nm_tick_silent(m);
    const int original_note = nm_peek(m, (uint16_t)(P->sym.chn_note + FDS_TEST_EDIT_CHANNEL));
    nm_start(m, &g_thor, 0, 1, 12000, &mode);
    nm_tick_silent(m);
    nm_apply_image(m, &g_edited);
    int o, r, tk, heard = -1;
    for (int i = 0; i < 4000 && heard < 0; ++i) {
        nm_position(m, &o, &r, &tk);
        nm_tick_silent(m);
        if (o == FDS_TEST_EDIT_ORDER && r == FDS_TEST_EDIT_ROW && tk == 0) {
            heard = nm_peek(m, (uint16_t)(P->sym.chn_note + FDS_TEST_EDIT_CHANNEL));
        }
    }
    EXPECT(edited_note != original_note && heard == edited_note, "edited note at order %d row %d: %d (was %d, heard %d)",
           FDS_TEST_EDIT_ORDER, FDS_TEST_EDIT_ROW, edited_note, original_note, heard);
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

/* An expansion instrument without a volume or wave-index release point, or -1. */
static int no_release_instrument(void) {
    for (int j = 0; j < g_thor.info.instruments; ++j) {
        int wlen, vlen;
        const uint8_t* wave = dir_envelope(&g_thor, dir_index(&g_thor, nm_rd16(exp_entry(&g_thor, j) + 6)), &wlen);
        const uint8_t* vol = dir_envelope(&g_thor, dir_index(&g_thor, nm_rd16(exp_entry(&g_thor, j))), &vlen);
        if (!wave[0] && !vol[0]) return j;
    }
    return -1;
}

static void test_audition(NesMachine* m) {
    printf("FDS audition: held notes on the FDS channel, fine pitch, release, cut\n");
    const int note = 70, inst = FDS_TEST_INSTRUMENT_FSINE;
    EXPECT(nm_audition(m, &g_thor, CH, note, inst, 0) == NM_OK, "audition the FDS channel, expansion instrument FSINE");
    nm_tick(m, g_out);
    const uint32_t f0 = nes_fds_frequency(nm_fds(m));
    EXPECT(f0 == kFdsNotes[note] && !(nm_fds(m)->freq_hi & 0x80), "first tick: $4082/$4083 = the FDS note table at engine note %d "
           "(%u; FSINE has no pitch or arpeggio envelope)", note, f0);
    const double en = energy(m, 120);
    EXPECT(en > 0.01 && (nm_apu(m)->pulse[0].ctrl & 15) == 0, "held 2 s, the other channels silent (energy %.2f)", en);
    EXPECT(nm_audition(m, &g_thor, CH, note, inst, 10) == NM_OK, "audition again with fine +10");
    nm_tick(m, g_out);
    EXPECT(nes_fds_frequency(nm_fds(m)) == f0 + 10u, "fine +10 raises the frequency by 10 (pitch shift 0) ($%03X -> $%03X)", f0,
           nes_fds_frequency(nm_fds(m)));

    const int j = FDS_TEST_INSTRUMENT_FWAVES;
    EXPECT(nm_audition(m, &g_thor, CH, note, j, 0) == NM_OK, "audition the releasing instrument FWAVES");
    for (int t = 0; t < 30; ++t) nm_tick(m, g_out);
    int wlen;
    const uint8_t* wave = dir_envelope(&g_thor, dir_index(&g_thor, nm_rd16(exp_entry(&g_thor, j) + 6)), &wlen);
    EXPECT(nm_release(m) == 1, "release: `$59`");
    nm_tick(m, g_out);
    const int ptr = nm_peek(m, (uint16_t)(P->sym.env_ptr + WAVE_SLOT));
    EXPECT(ptr > wave[0] && ptr <= wlen, "the wave-index envelope jumped to its release point %d (pointer %d): `$59`, not `$45`",
           wave[0], ptr);
    const int cut = no_release_instrument();
    if (cut >= 0) {
        EXPECT(nm_audition(m, &g_thor, CH, note, cut, 0) == NM_OK, "audition expansion instrument %d (no release points)", cut);
        nm_tick(m, g_out);
        EXPECT(nm_release(m) == 0, "no release point: cut (0)");
    }
    EXPECT(nm_audition(m, &g_thor, ALL_CHANNELS, note, 0, 0) == NM_ERR_IMAGE, "channel 6 does not exist");
    EXPECT(nm_audition(m, &g_thor, CH, note, FDS_TEST_INSTRUMENTS, 0) == NM_ERR_IMAGE, "instrument past the table rejected");
    EXPECT(nm_audition(m, &g_raw, CH, note, 0, 0) == NM_ERR_IMAGE, "raw layout cannot audition");
}

static void test_mute(NesMachine* m) {
    printf("FDS mute: the FDS channel in the mix\n");
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
    EXPECT(e_muted < e_all * 1e-6 && e_back > e_all * 0.2, "mute silences all 6, unmute restores (energy %.1f, %.2g, %.1f)", e_all,
           e_muted, e_back);
    NesMachine* twin = nm_create(RATE, P);
    nm_start(twin, &g_thor, 0, 0, 0, &mode);
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    nm_mute_channel(m, CH, 1);
    EXPECT(nm_fds(m)->mute == 1 && nm_apu(m)->mute == 0, "channel 5 mutes the FDS unit");
    int same = 1, silent = 1;
    double e2a03 = 0;
    for (int f = 0; f < 300; ++f) {
        const int n = nm_tick(m, g_out);
        for (int i = 0; i < 2 * n; ++i) e2a03 += g_out[i] * g_out[i];
        nm_tick(twin, g_out);
        same &= nes_fds_same_state(nm_fds(m), nm_fds(twin));
        silent &= nes_fds_mix_level(nm_fds(m)) == 0.0f;
    }
    EXPECT(e2a03 > 0.1 && silent, "2A03 still heard, FDS silent in the mix");
    EXPECT(same, "a muted FDS channel keeps every unit state exactly as unmuted play");
    nm_destroy(twin);
    EXPECT(nm_mute_channel(m, ALL_CHANNELS, 1) == NM_ERR_IMAGE, "channel 6 does not exist");
    nm_start(m, &g_thor, 0, 0, 0, &mode);
    EXPECT(nm_fds(m)->mute == 0, "start clears mutes (power-on)");
}

static void test_chip_engine_ops(void) {
    printf("chip engine interface: \"nes-famistudio-fds\"\n");
    static uint8_t bytes[NM_IMAGE_MAX_SIZE];
    const ChipEngineOps* ops = chip_engine_ops("nes-famistudio-fds");
    EXPECT(ops != NULL && ops != chip_engine_ops("nes-famistudio") && ops != chip_engine_ops("nes-famistudio-n163-1"),
           "nes-famistudio-fds registered");
    if (!ops) return;
    EXPECT(ops->image_size == P->image_size && ops->image_storage >= ops->image_size, "image size %zu", ops->image_size);
    ChipImage* img = (ChipImage*)malloc(ops->image_storage);
    memset(bytes, 0, sizeof bytes);
    memcpy(bytes, nes_fds_thor, NES_FDS_THOR_SIZE);
    EXPECT(ops->load_image(img, bytes, ops->image_size + 1) == CHIP_ERR_IMAGE, "another image size rejected");
    EXPECT(ops->load_image(img, bytes, ops->image_size) == CHIP_OK, "the test image loads");
    ChipMachine* cm = ops->create(RATE);
    ChipMachine* au = ops->create(RATE);
    EXPECT(cm != NULL && au != NULL, "create");
    if (cm && au) {
        int mode = -1, order, row, tick;
        const int o1 = 1 % FDS_TEST_ORDERS;
        EXPECT(ops->start(cm, img, o1, 0, 12000, &mode) == CHIP_OK && mode == CHIP_SEEK_FROM_START, "start at order %d", o1);
        const int ticks = row_frame(o1, 4) - row_frame(o1, 0);
        double e = 0;
        for (int i = 0; i < ticks; ++i) {
            int k = ops->tick(cm, g_out);
            for (int q = 0; q < 2 * (k > 0 ? k : 0); ++q) e += g_out[q] * g_out[q];
        }
        ops->position(cm, &order, &row, &tick);
        EXPECT(e > 0.01 && order == o1 && row == 4 && tick == 0, "4 rows through the ops (%d/%d/%d, energy %.2f)", order, row, tick, e);
        /* The hosts pass 1-based slots. */
        EXPECT(ops->audition(au, img, CH, 70, FDS_TEST_INSTRUMENT_FSINE + 1, 0) == CHIP_OK, "FDS audition through the ops");
        EXPECT(ops->mute(cm, CH, 1) == CHIP_OK && ops->mute(cm, ALL_CHANNELS, 1) == CHIP_ERR_IMAGE,
               "mute the song's FDS channel; channel 6 rejected");
        EXPECT(ops->tick(cm, g_out) > 0 && ops->tick(au, g_out) > 0, "both machines tick");
    }
    if (au) ops->destroy(au);
    if (cm) ops->destroy(cm);
    free(img);
}

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : ".";
    test_unit_pitch();
    test_unit_mod();
    test_unit_wave();
    test_unit_envelopes();
    test_unit_mute_seek();
    test_images();
    NesMachine* m = nm_create(RATE, P);
    if (!m) {
        printf("nm_create failed\n");
        return 2;
    }
    EXPECT(nm_player(m) == P && P->channels == 6 && P->song_base == NES_FDS_SONG_BASE && P->expansion == NES_EXPANSION_FDS,
           "machine for the FDS build: 6 channels, song base $%04X", NES_FDS_SONG_BASE);
    test_playback(m, dir);
    test_wave_sequence(m);
    test_mod_delay_automod(m);
    test_phase_reset(m);
    test_seek(m);
    test_live_apply(m);
    test_audition(m);
    test_mute(m);
    test_chip_engine_ops();
    nm_destroy(m);
    printf("nes_fds_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
