/* Chip-neutral engine interface: what the audio hosts (AAudio: audio_engine.cpp; SDL3:
 * deck/native/deck_audio.c) call to play a song image and audition notes. One implementation
 * per sound system, looked up by the system id the song was lowered for:
 *
 *   "gb-huge"         hUGEDriver 6.1.3 on binjgb (huge_chip.c over huge_machine.c)
 *   "nes-famistudio"  FamiStudio sound engine 4.5.3 (FamiTracker tempo) on a 6502 + 2A03
 *                     (nes/nes_chip.c over nes/nes_machine.c and nes/nes_image.c)
 *   "nes-famistudio-vrc6"  the same engine built with Konami VRC6 support on a 6502 + 2A03 + VRC6
 *                     (nes/nes_chip.c, nes/nes_vrc6.c; nes/nes_player.h describes every build)
 *   "nes-famistudio-vrc7"  the same engine built with Konami VRC7 support on a 6502 + 2A03 + VRC7
 *                     (nes/nes_chip.c, nes/nes_vrc7.c over emu2413 1.6.4)
 *   "nes-famistudio-n163-1".."nes-famistudio-n163-8"  the same engine built with Namco 163 support for 1..8
 *                     N163 channels on a 6502 + 2A03 + N163 (nes/nes_chip.c, nes/nes_n163.c)
 *   "nes-famistudio-fds"  the same engine built with Famicom Disk System support (and FDS auto-mod) on a 6502 +
 *                     2A03 + FDS (nes/nes_chip.c, nes/nes_fds.c)
 *   "nes-famistudio-s5b"  the same engine built with Sunsoft 5B support on a 6502 + 2A03 + 5B (YM2149F)
 *                     (nes/nes_chip.c, nes/nes_s5b.c)
 *   "nes-famistudio-mmc5"  the same engine built with Nintendo MMC5 support on a 6502 + 2A03 + MMC5 audio, plus
 *                     Thor Tracker's software PCM lane on the MMC5 DAC (channel 7; its image is the FamiStudio
 *                     image followed by the lane's extension) (nes/nes_chip.c, nes/nes_mmc5.c, nes/nes_mmc5_pcm.c)
 *   "nes-famistudio-epsm"  the same engine built with EPSM support on a 6502 + 2A03 + YMF288 (ymfm), true stereo;
 *                     its rhythm drums play the user-installed rhythm ROM (nes/nes_chip.c, nes/nes_epsm_unit.c,
 *                     nes/nes_epsm_ymfm.cpp; ym2608_rhythm_rom.h: ym2608_rhythm_rom_install)
 *   "c64-sid"         GoatTracker 2.77 playroutine on a 6510 + reSID 1.0 (PAL C64)
 *                     (sid/sid_chip.c over sid/sid_machine.c, sid/sid_image.c, sid/sid_resid.cpp)
 *   "opl3-fm"         Thor Tracker's own OPL3 player on a YMF262 (Nuked-OPL3 1.8)
 *                     (opl3/opl3_chip.c over opl3/opl3_machine.c, opl3_player.c, opl3_image.c)
 *   "ym2612-fm"       Thor Tracker's own YM2612 player streamed through SGDK's XGM2 driver on a Z80 + YM2612
 *                     (ymfm) + SN76489 (ym/ym_chip.c over ym/ym_machine.c, ym_player.c, ym_xgm.c, ym_image.c)
 *   "ws-wonderswan"   Thor Tracker's own WonderSwan player on the sound unit (a port of ares's APU), voice samples
 *                     from the HBlank timer (ws/ws_chip.c over ws/ws_machine.c, ws_player.c, ws_image.c, ws_apu.c)
 *   "ws-wonderswan-color"  the same on the WonderSwan Color, plus Sound DMA and Hyper Voice (ws/ws_chip.c)
 *   "vic20-vic"       Thor Tracker's own VIC-20 player on the VIC's sound part (VICE's model)
 *   "c16-ted"         the same player on the C16/Plus/4 TED's sound part (plus4emu's model), D/A samples from the timer
 *                     (cbm/cbm_chip.c over cbm/cbm_machine.c, cbm_player.c, cbm_image.c, cbm_vic.c, cbm_ted.c)
 *   "atari2600-tia-ntsc", "atari2600-tia-pal"  Thor Tracker's own Atari 2600 player on the TIA's two audio channels
 *                     (Stella 7.0's AudioChannel), NTSC or PAL timing (tia/tia_chip.c over tia/tia_machine.c,
 *                     tia_player.c, tia_image.c, tia_stella.cpp)
 *   "atari-pokey"     Thor Tracker's own player on the Atari 8-bit POKEY, an own cycle-exact model
 *                     (pokey/pokey_engine.c over pokey/pokey_machine.c, pokey_player.c, pokey_chip.c, pokey_image.c)
 *   "ym2203-opn"      Thor Tracker's own OPN player on a YM2203 (ymfm): FM 1-3, SSG 1-3
 *   "ym2608-opna"     the same player on a YM2608 (ymfm): FM 1-6, SSG 1-3, the six rhythm drums (the user-installed
 *                     rhythm ROM, ym2608_rhythm_rom.h) and ADPCM-B from the image's sample memory
 *                     (both: opn/opn_chip.c over opn/opn_machine.c, opn_player.c, opn_image.c, opn_ymfm.cpp)
 *   "sms-banjo"       the Banjo sound driver (joffb/banjo) on a Z80 + SN76489 (+ Game Gear stereo) + YM2413
 *                     (emu2413), every Master System layout (sms/sms_chip.c over sms/sms_machine.c, sms_image.c)
 *   "ay-zx128", "ay-msx", "ay-atari-st", "ay-cpc"  Thor Tracker's own AY player on its own AY-3-8910 / YM2149 core,
 *                     one engine per stock computer: ZX Spectrum 128 (AY-3-8912), MSX (AY-3-8910), Atari ST (YM2149F),
 *                     Amstrad CPC (AY-3-8912, stereo socket) (ay/ay_chip.c over ay/ay_machine.c, ay_player.c,
 *                     ay_psg.c, ay_image.c)
 *   "pce-huc6280"     Thor Tracker's own PC Engine player on the HuC6280's PSG (Mednafen 1.32.1), DDA samples from
 *                     the CPU timer (pce/pce_chip.c over pce/pce_machine.c, pce_player.c, pce_image.c, pce_psg.cpp)
 *   "msx-scc"         Thor Tracker's own SCC player on a Konami K051649 (our port of MAME's k051649) at the
 *                     MSX's 3.58 MHz (scc/scc_chip.c over scc/scc_machine.c, scc_player.c, scc_image.c,
 *                     scc_k051649.c)
 *   "msx-scc-plus"    the same player and core as a K052539 in SCC+ mode (five wave RAMs; same files)
 *   "ym2151-opm"      Thor Tracker's own OPM player on a YM2151 (ymfm) at the X68000's 4 MHz
 *                     (opm/opm_chip.c over opm/opm_machine.c, opm_player.c, opm_image.c, opm_ymfm.cpp)
 *
 * A machine runs the system's real playback driver; the host owns threading, image hand-over,
 * mixing, channel borrowing and output latency. Contract for every implementation:
 *
 *  - Images are produced by the frontend's lowering (`image_size` bytes) and loaded into
 *    host-allocated storage of `image_storage` bytes with load_image (copy + validate). Loaded
 *    images are plain data: hosts move them with memcpy and machines never keep a pointer to one.
 *  - Machines advance in driver ticks: tick() runs one tick and writes at most
 *    CHIP_MAX_TICK_FRAMES interleaved float stereo frames (DC-free, roughly [-1, 1]).
 *  - create() is the only call that may allocate. Every other call is realtime-safe except
 *    start() and audition(), which hosts run off the audio thread on a spare machine.
 *  - A machine is used by one thread at a time (hosts hand machines over between threads).
 *  - Positions are (order, row, tick) of the row the next tick processes; order is the
 *    played-order position the frontend gave start(). tick 0 means the next tick starts that
 *    row: a row is entered exactly when the position before a tick has tick 0 (a one-row loop
 *    at speed 1 reports the same (order, row, 0) before every tick, and enters it every time),
 *    unless the engine provides row_serial() (below).
 */
#ifndef THORTRACKER_CHIP_ENGINE_H_
#define THORTRACKER_CHIP_ENGINE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CHIP_MAX_SAMPLE_RATE 96000u
#define CHIP_MAX_TICK_FRAMES 2048u /* host buffer size per tick; >= any system's tick at 96 kHz */

enum {
    CHIP_OK = 0,
    CHIP_ERR_IMAGE = -1, /* invalid image or arguments; nothing was executed */
    CHIP_ERR_FAULT = -2, /* the driver faulted; the machine stays silent until restarted */
};

/* Start-position result of start(). */
enum {
    CHIP_SEEK_FROM_START = 0, /* state reconstructed by silently playing from the song start */
    CHIP_SEEK_FROM_ORDER = 1, /* fallback: driver state at the order start, earlier rows replayed */
};

typedef struct ChipMachine ChipMachine; /* opaque, one per implementation */
typedef struct ChipImage ChipImage;     /* opaque loaded image in host-allocated storage */

typedef struct ChipEngineOps {
    const char* system_id;
    size_t image_size;    /* bytes of a lowered image as the frontend produces it */
    size_t image_storage; /* bytes of a loaded image (host allocates, max-aligned) */

    ChipMachine* (*create)(uint32_t sample_rate); /* NULL on failure or unsupported rate */
    void (*destroy)(ChipMachine* m);

    /* Copies `size` lowered bytes into `storage` and validates them. Pure function of the
     * bytes. Returns CHIP_OK or CHIP_ERR_IMAGE (storage content is then unusable). */
    int (*load_image)(ChipImage* storage, const uint8_t* bytes, size_t size);

    /* Reset, load the image, and position the driver so the next tick plays (order, row);
     * at most `max_ticks` driver ticks of silent fast-forward. *seek_mode receives CHIP_SEEK_*.
     * Returns CHIP_OK or an error. */
    int (*start)(ChipMachine* m, const ChipImage* image, int order, int row, int max_ticks,
                 int* seek_mode);

    /* Live edit at a tick boundary: the running song continues with the new image's data
     * (transport preserved). Invalid images are ignored. */
    void (*apply_image)(ChipMachine* m, const ChipImage* image);

    /* Reset and start sounding one held note through the driver with the image's
     * instruments: driver note number, instrument slot, `fine` = the system's fine-pitch
     * amount (Game Boy: 40y detune 0..15). Returns CHIP_OK or CHIP_ERR_IMAGE for arguments
     * the system cannot play. */
    int (*audition)(ChipMachine* m, const ChipImage* image, int channel, int note, int instrument,
                    int fine);

    /* Key-off of the audition note. Returns 0 if the machine is silent now (the host stops
     * ticking it) or 1 if it has a release phase to play: the host keeps ticking until tick()
     * returns 0. */
    int (*release)(ChipMachine* m);

    /* Silences `channel` of a playing song and leaves it to the host (audition borrowing);
     * unmuting resumes at the channel's next note. Returns CHIP_OK or an error. */
    int (*mute)(ChipMachine* m, int channel, int muted);

    void (*position)(const ChipMachine* m, int* order, int* row, int* tick);

    /* Runs one driver tick and writes its frames to `out`. Returns the frame count (> 0),
     * 0 when a released audition has finished, or CHIP_ERR_FAULT. */
    int (*tick)(ChipMachine* m, float* out);

    /* Optional (NULL: tick 0 marks row starts, above). For engines whose position follows a
     * lagging driver (the Mega Drive's XGM2 waits while refilling PCM, so tick 0 may
     * repeat without a new row): a counter of row
     * starts, the value belonging to the row instance position() reports. It changes exactly
     * when position() moves into a newly started row instance (also a row re-entered by a jump
     * to itself). start() resets it. Used by the WAV export's loop detection (chip_wav.c). */
    uint32_t (*row_serial)(const ChipMachine* m);
} ChipEngineOps;

/* The implementation for a system id, or NULL if this build has none. */
const ChipEngineOps* chip_engine_ops(const char* system_id);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_CHIP_ENGINE_H_ */
