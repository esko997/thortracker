/* One emulated Game Boy Color (binjgb) running the pinned hUGEDriver 6.1.3 player.
 *
 * The cartridge is a 32 KB MBC5+RAM image synthesized in memory: driver code at
 * HUGE_CODE_BASE, driver variables at HUGE_DATA_BASE, song data written by the host into
 * the address space the driver reads through 16-bit pointers:
 *
 *   0x0090        sentinel return address (halt loop); never executed by the engine
 *   0x0150-0x08EA hUGEDriver code (generated/huge_player_image.h)
 *   0x0900-0x7FFF ROM   \
 *   0x8000-0x9FFF VRAM   > song region A (contiguous for the CPU; LCD is off)
 *   0xA000-0xBFFF cart RAM /
 *   0xC000-0xC063 driver WRAM variables
 *   0xC100-0xC3FF audition scratch (descriptor, orders, one-note track)
 *   0xC400-0xDFFF song region B
 *
 * The song descriptor is always at HM_DESCRIPTOR. Images are produced by the Kotlin
 * HugeImage layout (org.thortracker.audio) from the backend's HugeData lowering; this
 * file only validates pointers and copies bytes. No allocation after hm_create.
 *
 * Threading: a machine is used by one thread at a time (handed over by the engine).
 */
#ifndef THORTRACKER_HUGE_MACHINE_H_
#define THORTRACKER_HUGE_MACHINE_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define HM_SENTINEL 0x0090
#define HM_DESCRIPTOR 0x0900
#define HM_REGION_A_BEGIN 0x0900
#define HM_REGION_A_END 0xC000
#define HM_REGION_B_BEGIN 0xC400
#define HM_REGION_B_END 0xE000
#define HM_SCRATCH_BEGIN 0xC100
#define HM_TICK_CYCLES 70224u /* one VBlank period: 4194304 / 70224 = 59.73 Hz */
#define HM_MAX_SAMPLE_RATE 96000u
#define HM_MAX_TICK_FRAMES 1700u /* >= 70224 * 96000 / 4194304 + 1 */
#define HM_NO_WAVE 100           /* hUGE_NO_WAVE */

/* A complete song memory image. Only bytes inside regions A and B are used; everything
 * else must be zero. */
typedef struct HugeImage {
    uint8_t mem[0x10000];
    uint32_t version;
} HugeImage;

typedef struct HugeMachine HugeMachine;

enum {
    HM_OK = 0,
    HM_ERR_IMAGE = -1,   /* descriptor/pointers outside the song regions */
    HM_ERR_FAULT = -2,   /* invalid opcode or a driver call exceeded its cycle budget */
};

/* Start-position result of hm_start_song. */
enum {
    HM_SEEK_FROM_START = 0, /* state reconstructed by silently playing from order 0 row 0 */
    HM_SEEK_FROM_ORDER = 1, /* target not reached within the budget: driver-init state at
                               the order start, then rows replayed silently up to the row */
};

HugeMachine* hm_create(uint32_t sample_rate);
void hm_destroy(HugeMachine* m);

/* Validates an image (descriptor pointers, order count, tempo). Returns HM_OK or
 * HM_ERR_IMAGE. Pure function of the image. */
int hm_validate(const HugeImage* image);

/* Power-on reset, load the image, hUGE_init, then position the driver so the next tick
 * plays (order, row). *seek_mode receives HM_SEEK_*. Returns HM_OK or an error. */
int hm_start_song(HugeMachine* m, const HugeImage* image, int order, int row, int max_ticks,
                  int* seek_mode);

/* Power-on reset, load the image's instruments/waves, and start a one-row audition song
 * playing `note` (0..71) with `instrument` (1..15) on `channel` (0..3). */
int hm_start_audition(HugeMachine* m, const HugeImage* image, int channel, int note,
                      int instrument);

/* Live edit at a tick boundary: copy changed song bytes into the running machine and
 * re-resolve the driver's cached pointers (order table, current patterns, order count,
 * base tempo if it changed, wave reload if waves changed). Transport is preserved. */
void hm_apply_image(HugeMachine* m, const HugeImage* image);
uint32_t hm_image_version(const HugeMachine* m);

/* hUGE_mute_channel(channel, mute): the driver cuts the channel and leaves it alone
 * while muted; unmuting resumes at the channel's next note/effect. */
int hm_mute_channel(HugeMachine* m, int channel, int mute);

/* Position of the row the next tick processes (driver's current_order/2, row, tick). */
void hm_position(const HugeMachine* m, int* order, int* row, int* tick);

/* Runs one driver tick (hUGE_dosound + the rest of the VBlank period) and writes the
 * produced stereo frames (interleaved float, DC-filtered) to out. Returns the number of
 * frames (<= HM_MAX_TICK_FRAMES) or HM_ERR_FAULT. */
int hm_tick(HugeMachine* m, float* out);

/* Same as hm_tick without audio rendering (frame sequencer only); used for seeking. */
int hm_tick_silent(HugeMachine* m);

/* Total cycles spent inside driver calls since the last reset (diagnostics). */
uint64_t hm_driver_cycles(const HugeMachine* m);

/* Test hook: enables binjgb's APU write log; hm_apu_log copies (addr,value) pairs of the
 * writes made since the last call (max `capacity`). */
void hm_set_apu_logging(HugeMachine* m, int enabled);
int hm_apu_log(HugeMachine* m, uint8_t* pairs, int capacity);
/* Test hook: raw read of the emulated address space. */
uint8_t hm_peek(HugeMachine* m, uint16_t addr);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_HUGE_MACHINE_H_ */
