/* Renders a lowered YM2203 / YM2608 image (the 65536 / 327680 bytes OpnSystem / OpnaSystem.engine.lower writes)
 * through the native OPN machine (opn_machine.h: the player on ymfm, what the "ym2203-opn" / "ym2608-opna" engines
 * run): opn_load_image (the full validation; its problem and byte are printed when it fails) for the chip the image
 * header names, start at order 0 row 0, one tick at a time. Writes a 48 kHz stereo 16-bit WAV and prints peak/rms.
 * `mute=1,7,16` mutes tracker channels (1-based) to hear others alone; `--vgm out.vgm` also writes the VGM export
 * through the hosts' entry point (opn_export_vgm, GD3 tag from `title=`/`author=`/`date=`).
 *
 * The YM2608 rhythm ROM is the user's own, never in the repository: give its path as the 4th argument (after the
 * seconds) or `rom=path`, or set THOR_RHYTHM_ROM; it is installed (ym2608_rhythm_rom_install) after its size and
 * SHA-256 are checked. Without it the rhythm drums stay silent, and the tool says so when the song keyed one.
 * Built and run by tools/audio/render_opn_image.sh. */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "opn_export.h"
#include "opn_image.h"
#include "opn_machine.h"
#include "opn_ymfm.h"
#include "ym2608_rhythm_rom.h"

#define RATE 48000

/* ---- SHA-256 (FIPS 180-4), for the rhythm ROM's identity --------------------------------------------------------- */

static const uint32_t K256[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98,
    0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
    0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8,
    0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819,
    0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
    0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
    0xc67178f2,
};

static uint32_t ror(uint32_t x, int n) { return x >> n | x << (32 - n); }

static void sha256_block(uint32_t h[8], const uint8_t* b) {
    uint32_t w[64];
    for (int i = 0; i < 16; ++i)
        w[i] = (uint32_t)b[4 * i] << 24 | (uint32_t)b[4 * i + 1] << 16 | (uint32_t)b[4 * i + 2] << 8 | b[4 * i + 3];
    for (int i = 16; i < 64; ++i) {
        const uint32_t s0 = ror(w[i - 15], 7) ^ ror(w[i - 15], 18) ^ (w[i - 15] >> 3);
        const uint32_t s1 = ror(w[i - 2], 17) ^ ror(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    uint32_t a = h[0], bb = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
    for (int i = 0; i < 64; ++i) {
        const uint32_t t1 = hh + (ror(e, 6) ^ ror(e, 11) ^ ror(e, 25)) + ((e & f) ^ (~e & g)) + K256[i] + w[i];
        const uint32_t t2 = (ror(a, 2) ^ ror(a, 13) ^ ror(a, 22)) + ((a & bb) ^ (a & c) ^ (bb & c));
        hh = g;
        g = f;
        f = e;
        e = d + t1;
        d = c;
        c = bb;
        bb = a;
        a = t1 + t2;
    }
    h[0] += a, h[1] += bb, h[2] += c, h[3] += d, h[4] += e, h[5] += f, h[6] += g, h[7] += hh;
}

/* Lower-case hex digest of data[0 .. n - 1] into hex (65 bytes). */
static void sha256_hex(const uint8_t* data, size_t n, char* hex) {
    uint32_t h[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    size_t i = 0;
    for (; i + 64 <= n; i += 64) sha256_block(h, data + i);
    uint8_t tail[128] = {0};
    const size_t rest = n - i;
    memcpy(tail, data + i, rest);
    tail[rest] = 0x80;
    const size_t blocks = rest + 9 <= 64 ? 1 : 2;
    const uint64_t bits = (uint64_t)n * 8;
    for (int k = 0; k < 8; ++k) tail[64 * blocks - 1 - k] = (uint8_t)(bits >> (8 * k));
    for (size_t k = 0; k < blocks; ++k) sha256_block(h, tail + 64 * k);
    for (int k = 0; k < 8; ++k) sprintf(hex + 8 * k, "%08x", h[k]);
}

#define RHYTHM_ROM_SHA256 "53afd0fa9c62eda3e2be939e23f3adf48a2af8ad37bb1640261726c5d5adeba8"

static int install_rom(const char* path) {
    FILE* f = fopen(path, "rb");
    if (!f) {
        perror(path);
        return 1;
    }
    static uint8_t rom[YM2608_RHYTHM_ROM_SIZE + 1];
    const size_t n = fread(rom, 1, sizeof rom, f);
    fclose(f);
    if (n != YM2608_RHYTHM_ROM_SIZE) {
        fprintf(stderr, "%s: %zu bytes; the YM2608 rhythm ROM is %u\n", path, n, YM2608_RHYTHM_ROM_SIZE);
        return 1;
    }
    char hex[65];
    sha256_hex(rom, n, hex);
    if (strcmp(hex, RHYTHM_ROM_SHA256)) {
        fprintf(stderr, "%s: SHA-256 %s is not the YM2608 rhythm ROM's\n", path, hex);
        return 1;
    }
    if (ym2608_rhythm_rom_install(rom, n)) {
        fprintf(stderr, "%s: install failed\n", path);
        return 1;
    }
    return 0;
}

/* ---- VGM ---------------------------------------------------------------------------------------------------------- */

static int write_vgm(const uint8_t* bytes, size_t size, const char* path, const char* title, const char* author,
                     const char* date) {
    size_t n = 0;
    const char* problem = NULL;
    uint8_t* vgm = opn_export_vgm(bytes, size, 60u * 8u * 600u, title, author, date, &n, &problem);
    if (!vgm) {
        fprintf(stderr, "VGM export failed: %s\n", problem);
        return 1;
    }
    FILE* f = fopen(path, "wb");
    if (!f) {
        perror(path);
        opn_export_free(vgm);
        return 1;
    }
    fwrite(vgm, 1, n, f);
    fclose(f);
    const uint32_t samples = vgm[0x18] | vgm[0x19] << 8 | vgm[0x1A] << 16 | (uint32_t)vgm[0x1B] << 24;
    const uint32_t loop = vgm[0x20] | vgm[0x21] << 8 | vgm[0x22] << 16 | (uint32_t)vgm[0x23] << 24;
    printf("%s: %zu bytes, %.2f s, loop %.2f s\n", path, n, samples / 44100.0, loop / 44100.0);
    opn_export_free(vgm);
    return 0;
}

static OpnImage image;
static uint8_t file_bytes[OPNA_IMAGE_SIZE + 1];

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr,
                "usage: %s image.opnimg|image.opnaimg out.wav [seconds] [rhythm-rom.bin] [rom=path] [mute=1,7,16]"
                " [--vgm out.vgm] [title=..] [author=..] [date=..]\n(THOR_RHYTHM_ROM=path also names the rhythm ROM)\n",
                argv[0]);
        return 2;
    }
    double seconds = 30.0;
    const char *mutes = "", *vgm = NULL, *title = "", *author = "", *date = "", *rom = getenv("THOR_RHYTHM_ROM");
    int positional = 0;
    for (int a = 3; a < argc; ++a) {
        if (!strncmp(argv[a], "mute=", 5)) mutes = argv[a] + 5;
        else if (!strcmp(argv[a], "--vgm") && a + 1 < argc) vgm = argv[++a];
        else if (!strncmp(argv[a], "title=", 6)) title = argv[a] + 6;
        else if (!strncmp(argv[a], "author=", 7)) author = argv[a] + 7;
        else if (!strncmp(argv[a], "date=", 5)) date = argv[a] + 5;
        else if (!strncmp(argv[a], "rom=", 4)) rom = argv[a] + 4;
        else if (positional++ == 0) seconds = atof(argv[a]);
        else rom = argv[a];
    }
    if (rom && *rom && install_rom(rom)) return 1;

    FILE* f = fopen(argv[1], "rb");
    if (!f) {
        perror(argv[1]);
        return 1;
    }
    const size_t size = fread(file_bytes, 1, sizeof file_bytes, f);
    fclose(f);
    const int chip = size > OPN_H_CHIP ? file_bytes[OPN_H_CHIP] : -1;
    if ((chip != OPN_CHIP_YM2203 && chip != OPN_CHIP_YM2608) || size != opn_image_size(chip)) {
        fprintf(stderr, "%s is %zu bytes (chip byte %d); YM2203 images are %u bytes, YM2608 images %u\n", argv[1], size,
                chip, OPN_IMAGE_SIZE, OPNA_IMAGE_SIZE);
        return 1;
    }
    memcpy(image.bytes, file_bytes, size);
    if (opn_load_image(&image, chip)) {
        fprintf(stderr, "image rejected: %s (at byte %d)\n", image.info.problem ? image.info.problem : "?",
                image.info.problem_offset);
        return 1;
    }
    printf("%s: %s, %d orders, timer B %d (%.2f ticks/s)%s\n", argv[1], chip ? "YM2608" : "YM2203", image.info.orders,
           image.info.timer_b, 3993600.0 / 72.0 / image.info.tick_samples,
           image.info.uses_rhythm ? ", rhythm notes" : "");
    if (image.info.uses_rhythm && !ym2608_rhythm_rom_installed())
        fprintf(stderr, "warning: the song plays rhythm drums and no rhythm ROM was given: they stay silent\n");
    if (vgm && write_vgm(file_bytes, size, vgm, title, author, date)) return 1;

    OpnMachine* m = opnm_create(chip, RATE);
    if (!m) {
        fprintf(stderr, "opnm_create failed\n");
        return 1;
    }
    int mode = 0;
    if (opnm_start(m, &image, 0, 0, 12000, &mode) != OPNM_OK) {
        fprintf(stderr, "start failed\n");
        return 1;
    }
    for (const char* c = mutes; *c;) {
        char* end;
        const long ch = strtol(c, &end, 10);
        if (end == c) {
            ++c;
            continue;
        }
        if (ch >= 1 && ch <= image.info.channels) opnm_mute(m, (int)ch - 1, 1);
        c = end;
    }
    const long want = (long)(seconds * RATE);
    float* pcm = malloc(sizeof(float) * 2 * (size_t)(want + OPNM_MAX_TICK_FRAMES));
    if (!pcm) {
        fprintf(stderr, "out of memory\n");
        return 1;
    }
    long frames = 0;
    static float tick[OPNM_MAX_TICK_FRAMES * 2];
    while (frames < want) {
        const int n = opnm_tick(m, tick);
        if (n <= 0) break;
        memcpy(pcm + frames * 2, tick, sizeof(float) * 2 * (size_t)n);
        frames += n;
    }
    double peak = 0, sum = 0;
    for (long i = 0; i < frames * 2; ++i) {
        const double v = fabs(pcm[i]);
        if (v > peak) peak = v;
        sum += pcm[i] * (double)pcm[i];
    }
    FILE* w = fopen(argv[2], "wb");
    if (!w) {
        perror(argv[2]);
        return 1;
    }
    const uint32_t data = (uint32_t)(frames * 4);
    const uint32_t hdr[] = {0x46464952u, 36 + data, 0x45564157u, 0x20746d66u, 16, 0x00020001u, RATE, RATE * 4,
                            0x00100004u, 0x61746164u, data};
    fwrite(hdr, sizeof hdr, 1, w);
    for (long i = 0; i < frames * 2; ++i) {
        const float v = pcm[i] < -1 ? -1 : pcm[i] > 1 ? 1 : pcm[i];
        const int16_t s = (int16_t)lrintf(v * 32767);
        fwrite(&s, 2, 1, w);
    }
    fclose(w);
    int order, row, t;
    opnm_position(m, &order, &row, &t);
    printf("%s: %.1f s, peak %.3f, rms %.4f, ended at order %d row %d\n", argv[2], frames / (double)RATE, peak,
           frames ? sqrt(sum / (frames * 2.0)) : 0.0, order, row);
    if (chip == OPN_CHIP_YM2608 && opn_chip_rhythm_missing(opnm_chip(m)))
        printf("rhythm drums were keyed without a rhythm ROM: they were silent\n");
    opnm_destroy(m);
    free(pcm);
    return 0;
}
