/* Regression test for the NES seek fallback (nm_start, NM_SEEK_FROM_ORDER) on the 5B build: a square that enters an
 * order holding a manual-period sound must keep sounding it. The lowering does not re-send such a sound at the order
 * start (its load would rewrite the shared envelope period), so a fallback that only resets the engine and places the
 * streams leaves the square on the engine's silent dummy envelopes until its next instrument change.
 *
 * Fixture: s5b-manual-copies.nesimg, lowered by the Kotlin compiler (`tools/build.sh exportSamples`; the golden song
 * s5b_manual_copies). Slots 0 and 1 are identical manual-period sounds; Square 1 holds slot 1 from order 0 row 8 and
 * enters order 1 with a legato note and no instrument change of its own.
 *
 * Checks: the fixture really exercises the case (straight play enters order 1 with Square 1 on slot 1, a manual-period
 * entry, and order 1's first Square 1 entry starts a note without an instrument change); then a budget-0 start at
 * order 1 row 0 falls back to the order start, Square 1 holds slot 1 as in straight play, and its volume register is
 * non-zero during row 0 as in straight play. Without the fallback's instrument restore the slot is -1 and the volume
 * register 0.
 *
 * Build and run: tools/audio/run_native_host_test.sh [evidence-dir] (after exportSamples), or alone:
 *   nes_s5b_fallback_test [samples-dir]   (default app/build/samples, or THOR_SAMPLES in the script) */
#include <stdio.h>
#include <string.h>

#include "nes_machine.h"
#include "nes_s5b.h"

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
#define CH_SQ1 5         /* 5B Square 1 */
#define HELD_SLOT 1      /* the identical copy Square 1 holds from order 0 row 8 */
#define ORDER 1          /* entered with a legato note on that slot */
#define TICKS_PER_ROW 3

static float g_out[NM_MAX_TICK_FRAMES * 2];
static NesImage g_image;

/* Square 1's volume register ($08) over the first `ticks` ticks after a start, any non-zero. */
static int square1_sounds(NesMachine* m, int ticks) {
    int sounds = 0;
    for (int i = 0; i < ticks; ++i) {
        nm_tick(m, g_out);
        sounds |= nm_s5b(m)->reg[8] != 0;
    }
    return sounds;
}

int main(int argc, char** argv) {
    const char* samples = argc > 1 ? argv[1] : "app/build/samples";
    printf("5B seek fallback: a square entering the order with a held manual-period sound keeps it\n");
    char path[1024];
    snprintf(path, sizeof path, "%s/s5b-manual-copies.nesimg", samples);
    FILE* f = fopen(path, "rb");
    if (!f) {
        printf("  FAIL: missing %s; run `tools/build.sh exportSamples` first (or pass the samples directory)\n", path);
        return 1;
    }
    const size_t size = fread(g_image.bytes, 1, nes_player_s5b.image_size, f);
    fclose(f);
    EXPECT(size == nes_player_s5b.image_size && nm_load_image(&g_image, &nes_player_s5b) == 0, "%s loads (%s)", path,
           g_image.info.problem ? g_image.info.problem : "-");
    if (g_image.info.loaded != NM_LOADED) {
        printf("nes_s5b_fallback_test: %d checks, %d failures\n", checks, failures);
        return 1;
    }
    NesMachine* straight = nm_create(RATE, &nes_player_s5b);
    NesMachine* m = nm_create(RATE, &nes_player_s5b);
    if (!straight || !m) {
        printf("nm_create failed\n");
        return 2;
    }

    /* The fixture exercises the case. */
    const NmView v = nm_view(&g_image);
    const uint32_t base = nes_player_s5b.song_base;
    const uint8_t* e = v.region + (g_image.info.streams[CH_SQ1] + 4u * ORDER - base);
    NmEntry first;
    const char* why = nm_walk_entry(&v, nm_rd16(e + 2), CH_SQ1, &first);
    EXPECT(!why && first.instrument < 0 && first.note,
           "order %d's first Square 1 entry starts a note without an instrument change (%s, instrument %d, note %d)", ORDER,
           why ? why : "-", first.instrument, first.note);
    const uint8_t* entry = v.region + (g_image.info.exp_instruments - base) + NM_EXP_INSTRUMENT_SIZE * HELD_SLOT;
    EXPECT(entry[11] == 0x80, "slot %d is a manual-period sound (octave byte $%02X)", HELD_SLOT, entry[11]);
    int mode = -1;
    EXPECT(nm_start(straight, &g_image, ORDER, 0, 12000, &mode) == NM_OK && mode == NM_SEEK_FROM_START,
           "straight play reaches order %d", ORDER);
    EXPECT(nm_s5b_instrument(straight, CH_SQ1) == HELD_SLOT, "straight play enters order %d with Square 1 on slot %d (%d)", ORDER,
           HELD_SLOT, nm_s5b_instrument(straight, CH_SQ1));
    EXPECT(square1_sounds(straight, TICKS_PER_ROW), "straight play: Square 1 sounds during order %d row 0", ORDER);

    /* The fallback. */
    mode = -1;
    EXPECT(nm_start(m, &g_image, ORDER, 0, 0, &mode) == NM_OK && mode == NM_SEEK_FROM_ORDER,
           "budget 0: the start at %d/0 falls back to the order start (mode %d)", ORDER, mode);
    EXPECT(nm_s5b_instrument(m, CH_SQ1) == HELD_SLOT, "fallback: Square 1 holds slot %d as in straight play (%d)", HELD_SLOT,
           nm_s5b_instrument(m, CH_SQ1));
    EXPECT((nm_peek(m, (uint16_t)NES_S5B_FAMISTUDIO_S5B_CHN_ENV_SHAPE) & 0x0F) == (entry[10] & 0x0F),
           "fallback: Square 1's hardware envelope shape is the held sound's ($%02X)", entry[10]);
    EXPECT(square1_sounds(m, TICKS_PER_ROW), "fallback: Square 1 sounds during order %d row 0 (volume register non-zero)", ORDER);

    nm_destroy(straight);
    nm_destroy(m);
    printf("nes_s5b_fallback_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
