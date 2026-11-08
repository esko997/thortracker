# Audio engine: hUGEDriver preview, the NES engine, the SID engine, the OPL3 engine, the YM2612 engine and the OPM engine

Preview playback runs the **actual pinned hUGEDriver 6.1.3 binary** on an embedded Game Boy
Color emulator (binjgb) inside `libthortracker_audio.so`, output through AAudio (Android) or SDL3
(Steam Deck). There is no second song sequencer: notes, instruments, effects, timing and channel
behaviour are whatever the driver code does on the emulated CPU and APU.

The NES system (`"nes-famistudio"`) follows the same principle: the FamiStudio sound engine 4.5.3
(FamiTracker tempo) runs on an emulated 6502 + 2A03 with all five channels, DPCM samples included;
its image layout, seek, live-edit and audition contract are in [NES](#nes-famistudio-sound-engine-on-a-6502--2a03-nes-famistudio).
The NES + VRC6 and NES + EPSM variants run the same engine assembled with that expansion
([VRC6](#vrc6-the-same-engine-with-konamis-vrc6-nes-famistudio-vrc6),
[EPSM](#epsm-the-same-engine-with-a-ymf288-nes-famistudio-epsm)).

The C64 SID system (`"c64-sid"`, design step 6, native spike) runs the GoatTracker 2.77 playroutine
on an emulated 6510 + reSID 1.0 (MOS 6581 or 8580, PAL); its image layout, timing, seek, audition and
what the Kotlin lowering must produce are in [SID](#c64-sid-goattracker-2-playroutine-on-a-6510--resid-c64-sid).

The OPL3 system (`"opl3-fm"`, design step 8, native spike) has no console driver to run: it plays songs with
Thor Tracker's **own player** (C, specified in this document and mirrored by an independent Python reference)
on a YMF262 emulated by Nuked-OPL3 1.8, and exports VGM. Core choice, image layout, player specification,
effects, seek, live edits, audition and VGM export are in [OPL3](#opl3-thor-trackers-own-player-on-a-ymf262-opl3-fm).

The Mega Drive system (`"ym2612-fm"`, design step 8, native spike) streams Thor Tracker's own player, as XGC frames,
through **SGDK's XGM Z80 driver** (the driver games ship) on an emulated Z80 with a YM2612 (ymfm) and the SN76489;
it exports VGM, XGM and XGC. See [YM2612](#ym2612-sgdks-xgm-driver-on-a-z80--ym2612--psg-ym2612-fm).

The OPM system (`"ym2151-opm"`, unverified source slice) plays a YM2151 at the X68000's 4 MHz with Thor Tracker's
own player (specified here, mirrored by an independent Python reference) on ymfm's YM2151, with the chip's LFO,
noise and CSM as shared song state, and exports VGM. See [OPM](#opm-thor-trackers-own-player-on-a-ym2151-ym2151-opm).

## Chip engine interface (what a new sound system implements)

Both audio hosts (`audio_engine.cpp` on AAudio, `deck/native/deck_audio.c` on SDL3) are
chip-neutral: they call one `ChipEngineOps` table (`app/src/main/cpp/chip_engine.h`), looked up
by system id with `chip_engine_ops(id)` (`chip_engine.c`). Their Kotlin sides, `ChipAudio`
(Android) and `DeckAudio`, are chip-neutral too: they ask the song's system for its
`PlaybackEngine` (`SoundSystem.engine`, core `system/SoundSystem.kt`) and create the native host
for its `id`. When a song of another system arrives they destroy the running engine (playback
ends) and create the new one. `"gb-huge"`'s ops (`huge_chip.c`) map one-to-one onto
`huge_machine.h`, so the Game Boy plays exactly as before.

A new chip implements both sides. In Kotlin, `PlaybackEngine`:

| Member | Contract |
|---|---|
| `id` | the native ops' `system_id` |
| `imageSize` | the native `image_size`; hosts allocate the image buffer with it |
| `seekTicks` | `start`'s `max_ticks`: silent fast-forward budget for play-from-position (Game Boy: 12000, ~3.3 min) |
| `lower(song, out)` | the image for the song as heard (the system's export lowering); `IllegalArgumentException` with an actionable message for an unplayable song (the host keeps the last image) |
| `auditionProblem(channel, note, instrument, fine)` | why an audition cannot sound (absolute note, system fine pitch), or null; the host reports it as its error |
| `note(note)`, `fine(note, fine)` | `audition`'s note and fine arguments for an absolute note and the system's fine value (Game Boy: note − 36, `40y` detune unchanged) |

The host's held-audition cap on the Deck is about a minute of the song's ticks (`SoundSystem.ticksPerSecond`, which
counts multispeed).

Natively, it adds one ops table to the registry in `chip_engine.c` (and its sources to `CMakeLists.txt`,
`deck/build.gradle.kts` and the host test script) and implements:

| Op / field | Contract |
|---|---|
| `system_id`, `image_size`, `image_storage` | id the Kotlin side passes; bytes of a lowered image; bytes of a loaded image (host-allocated, plain data moved with `memcpy`, never referenced by a machine after a call returns) |
| `create(rate)` / `destroy` | the only allocation; rates up to `CHIP_MAX_SAMPLE_RATE` |
| `load_image(storage, bytes, size)` | copy + validate (`CHIP_OK` / `CHIP_ERR_IMAGE`); hosts load off the audio thread |
| `start(image, order, row, max_ticks, &seek_mode)` | reset and position the real driver so the next tick plays (order, row); off the audio thread on a spare machine |
| `apply_image(image)` | live edit at a tick boundary, transport preserved; realtime-safe |
| `audition(image, channel, note, instrument, fine)` | reset and sound one held note through the driver; `fine` is the chip's fine pitch (Game Boy: `40y` detune) |
| `release()` | key-off: 0 = silent now (host drops the machine; Game Boy), 1 = release phase, host keeps ticking until `tick` returns 0 |
| `mute(channel, muted)` | the song machine's side of audition channel borrowing |
| `position(&order, &row, &tick)` | row the next tick processes; tick 0 = the next tick starts that row (a one-row loop at speed 1 reports the same `(order, row, 0)` before every tick and enters the row every time) |
| `tick(out)` | one driver tick: ≤ `CHIP_MAX_TICK_FRAMES` float stereo frames, 0 = released note ended, `CHIP_ERR_FAULT` |
| `row_serial()` (optional) | for engines whose position follows a lagging driver (the Mega Drive: behind a heavy frame tick 0 repeats without a new row, then several rows pass in one tick): the row-start count belonging to the position's row instance; changes exactly when the position moves into a newly started row. NULL = tick 0 marks row starts |

The hosts keep everything that is not about the chip: triple-buffered image and machine hand-over
at tick boundaries (Android), image versions, latency-compensated position, audition mixing and
channel borrowing, the Deck's held-note cap and `da_peak` diagnostics. The host test drives the
Game Boy through the ops table as well as through `hm_*` directly.

### WAV export (`chip_wav.c`)

Every system renders `.wav` files through the same ops: `chip_wav_open(system_id, image, loops, fade_ms,
max_seconds, spool_path)` creates a machine at 48 kHz, `start`s it at (0, 0) and spools 16-bit stereo PCM to the
host's private file tick by tick (memory stays at one tick, whatever the length); `chip_wav_run(job, ticks)` steps
it, `chip_wav_status` reports frames, the expected total, the order, loop known, size and `CHIP_WAV_CAPPED`;
`chip_wav_close` removes the spool unless the file completed (the host then moves or copies it and deletes it).

- **Row entries** follow the contract above: `row_serial` changing, or tick 0 before a tick. The loop point is the
  first row instance entered a second time (`(order, row)`, whatever order list or jump leads back, a row jumping to
  itself included). The song part ends at that second arrival, or `loops` arrivals later.
- **Fade** (`fade_ms` > 0): the song keeps playing past the end of the song part (it loops on; nothing is keyed off)
  while a linear gain falls to 0; trailing silence beyond one second after the last sample louder than 1 LSB is cut
  (`ftruncate`). This is not a release tail: no driver has a "stop with release" operation, so none is rendered.
  `fade_ms` = 0 ends exactly at the loop point, for files a player loops.
- **Limit**: a song part reaching `max_seconds` (the hosts: 600) stops at that tick, fades as above and sets
  `CHIP_WAV_CAPPED`; the hosts tell the user the file is not the whole song (or not all the passes). Files never
  exceed `CHIP_WAV_MAX_FRAMES` (size < 2³¹ bytes: RIFF's 32-bit lengths and a 32-bit `off_t`, about 3 h 6 min);
  `max_seconds` = 0 means that bound, and a fade that cannot fit is refused (`"arguments"`).
- **Failures**: a driver fault (`CHIP_WAV_ERR_FAULT`) or a failed spool write (`CHIP_WAV_ERR_WRITE`, e.g. disk full)
  end the job; closing it removes the spool.
- **Hosts**: Android (`ChipAudio.nativeWav*`, `MainActivity`) renders on a worker thread into
  `cacheDir/wav-export.wav.part`, then asks for the destination ("WAV ready": Save .wav… / Discard) and copies it
  there; no document exists until the file is complete, a failed copy deletes its document, and the activity going
  away frees the render. The Deck (`DeckWav`, `DeckShell.pumpExport`) asks first, renders in 8 ms slices of the frame
  loop into `~/.local/share/thortracker/wav-export.wav.part` and moves it into place (rename, or copy across file
  systems). Both remove a leftover spool at startup. `chip_render_wav` renders a whole file in one call (tests).
- **Host test** (`app/src/test/cpp/wav_render_test.c`, in `tools/audio/run_native_host_test.sh`): every demo image;
  header, samples identical to straight ticking up to the loop point, step-size independence, loop passes, fade
  length and end, the limit with `CHIP_WAV_CAPPED`, spool removal on early close.

## Sources, pins and licences

| Component | Where | Pin | Licence / obligation |
|---|---|---|---|
| hUGEDriver 6.1.3 | `third_party/hUGEDriver-6.1.3/` (README, `gbdk/hUGEDriver.lib`, `include/`, `rgbds/hUGEDriver.asm`) | byte-identical to gbc-puzzler's vendored copy of the [v6.1.3 release](https://github.com/SuperDisk/hUGEDriver/releases/tag/v6.1.3); `hUGEDriver.lib` sha256 `6c0a2664…5cac89`, `hUGEDriver.asm` sha256 `16870fd8…01d56` | public domain ("dedicated to the public domain" in its README); no obligation |
| binjgb | `app/src/main/cpp/third_party/binjgb/` (`src/emulator.c/.h`, `common.c/.h`, `memory.c/.h`, `builtin-palettes.def`, `LICENSE`) | github.com/binji/binjgb commit `16621111ed0ee73bcc45c912a823bcebedcffc0f` | MIT — **ship the copyright and permission notice** (`LICENSE`) with the app (credits/about screen or bundled notices) |
| GBDK-2020 4.5.0 `sdar`/`sdldgb` | offline only (`tools/audio/prepare_huge_player.py`) | gbdk-linux64.tar.gz sha256 `d7857a5f…605c` (gbc-puzzler `tools/fetch-gbdk`) | tools are not shipped; output is the PD driver |
| FamiStudio sound engine 4.5.3 | `third_party/famistudio-4.5.3/` (`famistudio_ca65.s`, `NoteTables/famistudio_note_table_{lsb,msb}.bin`, `readme.txt`, `LICENSE`); assembled into `app/src/main/cpp/generated/nes_player_image.h`; the test song `tools/audio/nes/ft_test.txt` (ours) and its FamiStudio 4.5.3 export `ft_test_ca65.s` | github.com/BleuBleu/FamiStudio tag `4.5.3`, commit `c09dca64788606c09fcb5e70db28b9dd1f34be78` (the engine file is unchanged since tag 4.5.0 and still says 4.5.0 in its banner); `famistudio_ca65.s` sha256 `e126a8f3…71fe`, note tables `b917b0eb…ee41` / `b52e4924…69fb` | engine: all-permissive ("copyright notice and this notice are preserved"); note tables: FamiStudio's MIT licence. Both notices are in `NOTICE.txt` |
| m6502.h (floooh/chips) | `app/src/main/cpp/third_party/chips/` (`m6502.h`, `LICENSE`); implemented once in `cpu6502.c` for the NES and SID machines | github.com/floooh/chips commit `9e88298ce56319953ac7a43213a1120359f7a3a6`, `m6502.h` sha256 `b3fc32a0…868d46e` | zlib — keep the notice; it is in `NOTICE.txt` |
| blip_buf 1.1.0 | `app/src/main/cpp/third_party/blip_buf/` (`blip_buf.c/.h`, `license.txt`, `readme.txt`, `changes.txt`) | Shay Green's release archive `blip_buf-1.1.0.zip` (code.google.com archive of blip-buf, sha256 `37a9b4f0…2b398e4c`); `blip_buf.c` sha256 `66c4a3f3…0c7573`, unmodified | LGPL-2.1-or-later (GPL-3.0-compatible); source ships in the repo, notice in `NOTICE.txt` |
| cc65 `ca65`/`ld65` | offline only (`tools/audio/prepare_nes_player.py`) | Ubuntu 24.04 package `cc65 2.19-1` ("ca65 V2.18 - Ubuntu 2.19-1"), checked by the script | tools are not shipped |
| GoatTracker 2.77 playroutine | `third_party/goattracker-2.77/player.s` (unmodified `src/player.s`, CRLF kept by `.gitattributes`); assembled into `app/src/main/cpp/generated/sid_player_image.h`; the test song `tools/audio/sid/gt_test_song.py` → `gt_test.sng` (ours) and GoatTracker's own packing `gt_test.sid` | SourceForge `GoatTracker 2/2.77/GoatTracker_2.77.zip` (Lasse Öörni, 2025-01-20), sha256 `96c2bd6a…4561ef` (the digest SourceForge lists); `player.s` sha256 `ee9ddbe9…5fad609a`, identical to the copy embedded in the release's packer (`goatdata.c`) | its own notice: "This playroutine source code does not fall under the GPL license! Use it, or song binaries created from it freely for any purpose, commercial or noncommercial." In `NOTICE.txt` |
| reSID 1.0 | `app/src/main/cpp/third_party/resid-1.0/` (`sid`, `voice`, `wave`, `envelope`, `filter`, `dac`, `extfilt`, `pot` `.cc/.h`, `spline.h`, the eight generated `wave*.h` tables, `siddefs.h.in`, `COPYING`, `AUTHORS`, `README`, `NEWS`), unmodified; plus `siddefs.h` (ours: `siddefs.h.in` with configure's defaults) | github.com/daglem/reSID commit `02c456abfe23cc4f06d94424e40beab8c90fa31f` (2021-12-10: the reSID 1.0 lineage as maintained in VICE, with Leandro Nini's fixes; the last commit before the 2022 rework, whose compile-time `exp()` tables only build with g++) | GPL-2.0-or-later (GPL-3.0-compatible); source ships in the repo, notice in `NOTICE.txt` |
| GoatTracker 2.77 `gt2reloc` + `gtasm` | offline only (`tools/audio/fetch_goattracker.sh`, `prepare_sid_player.py`): the release's command-line packer, and `tools/audio/sid/gtasm.c` over its embedded assembler (Magnus Lind's, from Exomizer) | built from the pinned release | GPL-2.0-or-later (packer) / Exomizer's licence (assembler); tools are not shipped, their output is the free playroutine |
| SGDK XGM driver | `third_party/sgdk-xgm/` (`drv_xgm.s80`, `z80_def.i80`, `z80_mac.i80`, `z80_fct.i80`, `xgm.txt`, `LICENSE.txt`), unmodified; assembled into `app/src/main/cpp/generated/xgm_driver_image.h` by `tools/audio/prepare_ym.py` | github.com/Stephane-D/SGDK commit `9a78a44143a867cd8c5053b95cd061a21f0b73c2` (2026-10-20); `drv_xgm.s80` sha256 `aae47f6f…6e81b63`; image (5888 bytes) sha256 `557fe542…cf31e` = `drv_xgm` in SGDK's `lib/libmd.a` | MIT — notice in `NOTICE.txt` |
| SGDK sjasm 0.39j, xgmtool 1.76 | offline only (`tools/audio/fetch_sgdk.sh` builds both from the pinned commit) | the SGDK pin | sjasm zlib, xgmtool MIT; not shipped (xgmtool's conversion is ported in `ym/ym_xgm.c`, MIT notice kept) |
| z80.h (floooh/chips) | `app/src/main/cpp/third_party/chips/z80.h`; implemented once in `cpuz80.c` | the `m6502.h` commit `9e88298c`; `z80.h` sha256 `6ca70ffd…de734f3` | zlib (the chips notice) |
| ymfm | `app/src/main/cpp/third_party/ymfm/` (`ymfm.h`, `ymfm_opn.h/.cpp`, `ymfm_opm.h/.cpp`, `ymfm_fm.h/.ipp`, `ymfm_adpcm.h/.cpp`, `ymfm_ssg.h/.cpp`, `LICENSE`), unmodified | github.com/aaronsgiles/ymfm commit `81aec25ccbb98f4873a255f7551ac4dadac59b4a` (2026-07-27); `ymfm_opn.cpp` sha256 `8734c6d5…913b489`, `ymfm_fm.ipp` `4fb7fe59…f6ab4b9`; `ymfm_opm.cpp` `2235d571…98b22dc` and `ymfm_opm.h` `cab75025…a9aee7b` fetched from `raw.githubusercontent.com/aaronsgiles/ymfm/81aec25c…/src/` for the OPM (the vendored `ymfm.h`, `ymfm_fm.ipp` and `ymfm_opn.cpp` match that commit's byte for byte) | BSD-3-Clause — notice in `NOTICE.txt` |
| Nuked-OPL3 1.8 | `app/src/main/cpp/third_party/nuked-opl3/` (`opl3.c`, `opl3.h`, `LICENSE`), unmodified | github.com/nukeykt/Nuked-OPL3 commit `765ec962e473aeb767e4cba74ffdc8f588ffbfe8` (2026-08-24, "remove NEW bit checks for 4-op channels", after "fix 4-op channel frequency latching"; the header still says version 1.8); `opl3.c` sha256 `59eb873f…1bfe03`, `opl3.h` `a84266b8…763679`, `LICENSE` `20c17d8b…aec96e331` | LGPL-2.1-or-later (GPL-3.0-compatible); source ships in the repo, notice in `NOTICE.txt` |

binjgb was chosen over a hand-written core because it passes blargg `cpu_instrs`,
`instr_timing`, `mem_timing`, `dmg_sound` and `cgb_sound` and most mooneye timing tests
(its `test_results.md`), is a single C file, reports invalid opcodes as an event instead of
aborting, and reads cartridge ROM from a host-owned buffer (live song edits are plain memory
writes). The preview is still an emulator: analog output, mixer filtering and revision quirks
are binjgb's, not proven equal to a specific GBC.

**Local binjgb change** (only one, marked `THOR-TRACKER LOCAL CHANGE`): `AudioBuffer` holds
float samples in [0, 1] instead of unsigned 8-bit (`emulator.h` struct, `write_audio_frame`,
`init_audio_buffer`), so the 4-bit channel sums keep full resolution. Mixing/APU logic is
unchanged.

## Preparing the player (once, when the driver pin changes)

```
python3 tools/audio/prepare_huge_player.py        # GBDK_HOME=/path/to/gbdk (default ../gbc-puzzler/.tools/gbdk)
```

It checks both driver hashes, extracts `hUGEDriver.o`, links it alone with `sdldgb`
(`_CODE=0x0150`, `_DATA=0xC000`), verifies the object's area sizes and exported symbol
offsets, proves the unexported WRAM variable offsets from instruction bytes in the linked image,
and writes `app/src/main/cpp/generated/huge_player_image.h` (committed). Nothing is compiled or
assembled at app runtime or per Play.

## Native build (Gradle, owned by the app module)

`ndkVersion = "27.0.12077973"`, CMake `3.22.1`, `externalNativeBuild.cmake.path =
src/main/cpp/CMakeLists.txt`, ABIs `arm64-v8a`, `x86_64`. The CMake project builds a static C11
library `chip_engines` (`chip_engine.c`, `cpu6502.c` (the one `m6502.h` implementation), `huge_chip.c`,
`huge_machine.c`, which `#include`s binjgb's `emulator.c`, plus `common.c`; the NES files `nes/nes_chip.c`,
`nes/nes_machine.c`, `nes/nes_image.c`, `nes/nes_apu.c`, `nes/nes_vrc6.c`, `nes/nes_players.c` and `blip_buf.c`; the SID files `sid/sid_chip.c`,
`sid/sid_machine.c`, `sid/sid_image.c`; the OPL3 files `opl3/opl3_chip.c`, `opl3/opl3_machine.c`,
`opl3/opl3_player.c`, `opl3/opl3_image.c`, `opl3/opl3_vgm.c` and Nuked-OPL3's `opl3.c`; `vgm_gd3.c` (the VGM exports'
GD3 tags); the YM2612 files `cpuz80.c`, `ym/ym_chip.c`, `ym/ym_machine.c`, `ym/ym_player.c`, `ym/ym_image.c`,
`ym/ym_xgm.c`, `ym/ym_vgm.c`, `ym/ym_psg.c`; always with `NDEBUG`), static C++17 libraries `resid` (reSID 1.0 and
`sid/sid_resid.cpp`, its C interface; `-O2 -w`) and `ymfm` (`ym/ym_ymfm.cpp`, which compiles ymfm's `ymfm_opn.cpp`
itself, plus `ymfm_adpcm.cpp`, `ymfm_ssg.cpp`) that `chip_engines` links, and the shared `thortracker_audio` (`audio_engine.cpp`, `audio_jni.cpp`) linked with
NDK `aaudio`, `log`, `m`, `dl` and the NDK's default static C++ runtime. No third-party Java/Gradle
dependency. The engines the hosts call
are C; the Deck compiles the same files with Kotlin/Native's clang and reSID with its clang++ against the
sysroot's GCC 8.3 libstdc++ (`--gcc-toolchain`), and links `-lstdc++` (`deck/build.gradle.kts`,
`buildEngine`).

## Memory map of the preview cartridge

32 KiB MBC5+RAM CGB ROM built in memory (`huge_machine.c: build_rom`), LCD off:

| Range | Use |
|---|---|
| `0x0090` | sentinel return address for host-called driver routines |
| `0x0150–0x08EA` | hUGEDriver code |
| `0x0900–0x0F3F` | fixed header: descriptor, order_cnt, duty/wave/noise tables, waves, four 127-entry order tables |
| `0x0F40–0xBFFF` | tracks (ROM, VRAM, cart RAM — contiguous for the CPU) |
| `0xC000–0xC063` | driver variables |
| `0xC100–0xC3FF` | audition scratch song |
| `0xC400–0xDFFF` | tracks |

Capacity: **272 distinct 192-byte channel tracks** (byte-identical tracks share a slot) and 127
orders. `HugeLimits.PREVIEW_TRACK_SLOTS` (backend) is the single owner of that number;
`SongValidator` rejects bigger songs, and `HugeImageLayout` checks the constant matches the map.
For comparison a GBDK game bank holds ~80 tracks (export warning, not a preview limit).

## Data path

`ChipAudio.update(song)` (UI thread, non-blocking) → control thread: the song system's
`PlaybackEngine.lower` — for the Game Boy `HugeLowering.lower` (the same lowering `.uge` export
uses) → `HugeImageLayout.build` (fixed header, deduplicated
tracks) → JNI loads the 64 KiB image (`load_image`) and copies it into a preallocated triple
buffer. The AAudio callback takes the newest image **at a driver-tick boundary** and
`apply_image` (`hm_apply_image`) copies changed bytes
into the running machine, then refreshes the driver's cached copies (descriptor pointers,
order_cnt, current pattern pointers, base tempo if edited, wave-reload flag). The callback never
allocates, locks, encodes or compiles; images are produced off the realtime thread.

## Live-edit semantics (transport never restarts)

- Pattern cells: the driver reads rows from memory every tick, so an edit is heard from the next
  row processed (effects of the current row from its next tick).
- Order list: the current order's pattern pointers are re-resolved immediately (switch at the
  next row); later orders as reached. If orders shrink below the playing order it clamps to the
  last order; a pending `Bxx` beyond the end goes to order 0.
- Instruments: loaded by the driver when a note with that instrument triggers. A sustained note
  keeps the registers it was triggered with (as on hardware); the next note uses the edit.
- Waves: the driver caches the loaded wave id; any wave-table change resets it to
  `hUGE_NO_WAVE`, so the next CH3 note with an instrument reloads wave RAM.
- Song tempo (ticks per row): applied immediately (overrides an earlier `Fxx`); a row already
  longer than the new speed ends on the next tick without replaying its notes (the driver's
  ticks-per-row is set to end that row, and the edited speed is installed when the next row
  starts, before that row's own `Fxx`).

## Play from a position

`play(order, row)` prepares a spare machine on the control thread: power-on reset, `hUGE_init`,
then **silent fast-forward of the real driver from order 0 row 0** (APU frame sequencer
advanced through idle time; no sample synthesis) until the driver is at (order, row, tick 0),
budget `PlaybackEngine.seekTicks` (Game Boy: 12000 ticks, ~3.3 min; the Deck uses the same budget). If not reached (budget or the row
is unreachable through jumps), it restarts from driver-init state, positions the driver at the
order start and silently replays that order's earlier rows. The ready machine is swapped in
by the callback; the result mode is reported in `diagnostics()["lastSeekMode"]` (0 = from song
start, 1 = from order start).

## Audition

A second machine plays a one-row song (`ticks per row = 256`, so the note sustains) using the
song's current instrument/wave tables, triggered through the driver like any row. While
transport runs, the song's same channel is muted with `hUGE_mute_channel` at the next tick (the
driver cuts it and leaves it alone) and unmuted on release (it resumes at that channel's next
note or effect). Release stops the audition machine immediately. Instrument 0 is rejected with
an error (hUGE has no "no instrument" sound).

Sketch's held previews use `EditorSession.auditionSequence(channel, steps, instrument, stepMillis)` and a
host-owned timer that sends each step through that same `TrackerAudio.audition` path: chord arpeggios
step every 180 ms; the phrase loop steps once per pattern row at the song tempo
(`ticksPerRow * 1000 / 59.73` ms). A null step starts nothing, so the previous note keeps sounding
(it is not retriggered). Steps are scheduled against the loop's start time rather than by chained
delays, so handler latency does not accumulate. These are audition-only references, not a song
sequencer or an exported effect. A new note/phrase cancels the old timer; release, menus, swaps, focus
loss and activity pause cancel the held preview. Only the phrase's explicit Insert command writes
notes (one undo step); neither these previews nor their scheduling touch project data.

## Position, errors, lifecycle

- `position()` is lock-free against the callback: each row start is recorded with its output
  frame; the reader picks the row presented now using `AAudioStream_getTimestamp`.
- `error()` returns the latest actionable problem (invalid song from validation, no
  instrument, AAudio failure, driver fault) or null; no method throws for these.
- The stream opens on first Play/audition (exclusive low-latency, falls back to shared; usage
  `AAUDIO_USAGE_GAME` on API 28+, resolved with `dlsym` because minSdk is 26; 48 kHz
  float stereo, buffer fixed at 2 bursts — not adapted on underrun; `diagnostics()` reports
  xruns for device evaluation), stops after 10 s idle, and is reopened after a device
  error (transport is stopped, not silently resumed). `close()` destroys everything on the
  control thread and waits (≤ 2 s).
- Thor (Android 13) output path, measured 2026-11-05 by the parent: the stream is a normal
  mixer track (960-frame burst, 1920-frame buffer, ~83–106 ms timestamp latency, 0 xruns), not
  FAST, although the primary output has a FastMixer (192 frames) with free slots. Cause, from
  the AudioFlinger dump and Android 13 source: the output-mix effect chain (session 0) holds an
  NXP software `Equalizer` (flags `0x00400048`: processes audio, not HW-accelerated), registered
  by `com.odin.settings` though disabled. `PlaybackThread::createTrack_l` asks every chain on
  sessions DEVICE, OUTPUT_STAGE, OUTPUT_MIX and the track's own session;
  `EffectChain::isFastCompatible()` rejects any process-implementing software effect whether
  enabled or not, so FAST is dropped for every app's track on that output. App-side requests
  (usage GAME, PCM_I16, a session id to drop RAW) cannot change this and were tried or ruled
  out; the I16 trial was reverted. This is a device-configuration limit, not an engine defect.
- `ChipAudio.diagnostics()` reports sample rate, burst, buffer size, xruns, worst callback time,
  last seek / audition preparation time, faults, seek mode, exclusive/low-latency flags and the
  last live-apply time inside the callback, plus the output latency used for the playhead
  (`outputLatencyFrames`, the timestamp measurement, or the buffer size when no timestamp is
  available or it is negative) and the last raw timestamp measurement
  (`rawTimestampLatencyFrames`). `position()` never waits for a stream open/close: if the
  control thread holds the stream it uses the last latency.

## Verification

- `tools/audio/run_native_host_test.sh [dir]` builds the machine with host gcc and checks, via
  binjgb's APU write log and driver WRAM: note trigger bytes from instrument tables; row/order
  timing and wrap; live cell/order/instrument/tempo/wave edits without transport movement;
  seek reconstruction (speed and NR50 from earlier orders) and the unreachable-row fallback;
  effects 0,1,2,3,4,5,7,8,9,A,B,C,D,E,F; wave/noise registers; audition; channel mute borrow;
  invalid image rejection; and the `"gb-huge"` ops table as the hosts use it (registry lookup,
  image size checks, start, non-silent render of the demo song, position advance, live apply,
  seek, mute, audition and release). It prints seek and per-tick costs and writes `host_demo.wav` and
  `host_trace.txt` (per-tick APU register writes) for comparison with a reference player
  (PyBoy/mGBA running the same driver).
- `core/src/jvmTest/kotlin/org/thortracker/backend/HugeImageLayoutTest.kt`: layout equals the lowered
  bytes, sharing, capacity, minimal byte diffs, and `GbSystem.engine` lowers exactly that image and
  converts audition notes (absolute − 36).
- Exercised (2026-11-05): host test **107 checks, 0 failures**, including lowering the
  tempo to one tick per row during an existing row without replaying that row. Host timings:
  seek to order 1 row 8 0.63 ms; order 15 row 60 (~6100 driver ticks) 16.81 ms;
  realtime render 32.9 µs per tick (≈510× realtime).
- 2026-11-07 (chip engine interface): host test **145 checks, 0 failures** (126 machine checks
  plus 19 through `ChipEngineOps`).
- On the physical Thor, all six Android instrumentation tests passed, including live editing,
  auditioning and 50 consecutive open/play/close cycles. A separate 30-second float-stream soak
  reported zero underruns and driver faults, worst callback 2.79 ms (20 ms burst), and output
  timestamp latency 102–106 ms on the device configuration described above.
- The final regression run reported worst callback 2.35 ms, seek preparation 0.14 ms, audition
  preparation 0.12 ms, live-image application 0.07 ms, and output timestamp latency 100 ms.
  The observed engine-position start was 61 ms. This is **not** physical button-to-speaker latency.
- Captured 14.87 seconds of actual Thor digital output for the controller-composed study:
  stereo 48 kHz, peak 0.05615, RMS 0.01601, no clipped samples, and no exact internal silence
  longer than 0.94 ms. Capture used scrcpy's REMOTE_SUBMIX **before opening the AAudio stream**;
  starting capture against an already-open stream returned silence on this firmware. Its alternate
  playback-duplication route failed to register an audio policy. No microphone was used.
- Acoustic listening, physical button-to-speaker latency, and an independent trace-by-trace
  reference-player comparison have not been performed. Digital capture is signal-path evidence,
  not a substitute for the user's listening and playability assessment.

## NES: FamiStudio sound engine on a 6502 + 2A03 (`"nes-famistudio"`)

Design step 5: the engine plays FamiTracker-tempo songs with exact rows, seeks, relocates a playing
song onto an edited image in realtime and auditions notes with a release phase. The Kotlin side
(`NesSystem.engine`, lowering, UI) is NesModel's/NesEditor's; this section is the contract.

### What runs

- **Player image** (`generated/nes_player_image.h`, 2834 bytes at `$8000`): the unmodified
  FamiStudio 4.5.3 engine plus a fixed NSF-style entry table (`tools/audio/nes/nes_player.s`):
  `$8000 nes_player_init` (A = song index → `famistudio_init(NTSC, NES_SONG_BASE)` +
  `famistudio_music_play`), `$8003 nes_player_play` (`famistudio_update`), `$8006
  nes_player_idle` (return address of host calls), `$8009 nes_player_trap` (interrupt/BRK vector
  target = fault). Configuration: NTSC, smooth vibrato, DPCM support with samples at
  `FAMISTUDIO_DPCM_OFF = $C000`, **FamiTracker tempo** with delayed notes and cuts
  (`FAMISTUDIO_USE_FAMITRACKER_TEMPO`, `..._DELAYED_NOTES_OR_CUTS`), and every 2A03 feature
  FamiStudio's own FamiTracker-tempo NSF kernel enables (volume track and slides, pitch track, slide
  notes incl. noise, vibrato, arpeggio, duty-cycle effect, DMC delta counter, release notes, phase
  reset, extended instrument range) except the DPCM bank-switching/extended-range encodings (so at
  most 63 DPCM mappings, and samples only at `$C000-$FFF9`); no SFX. So any 2A03-only
  FamiTracker-tempo FamiStudio export without DPCM bank switching or extended DPCM range plays (its
  `.dmc` where its `FAMISTUDIO_DPCM_PTR` says). The same bytes are in Kotlin as `core/.../backend/NesPlayerImage.kt` (generated)
  for the `.nsf` export.
- **Tempo**: one tick = one NTSC frame (60.0988 Hz). The engine starts a row on a frame when
  `famistudio_tempo_acc_hi >= famistudio_song_speed`, subtracts the speed, runs the row, and adds
  the song's tempo step to the accumulator every frame. Step (NTSC) = `256 * tempo / 150` (FamiStudio's
  integer formula), so at tempo 150 a row lasts exactly `speed` frames (= the Game Boy's ticks per
  row). `$46 s` (Fxx) sets the speed when its row runs; `famistudio_music_play` starts at speed 6.
- **CPU**: `m6502.h` (floooh/chips; cycle-stepped, tested in `floooh/chips-test` with nestest, the
  Wolfgang Lorenz suite and a cycle comparison against perfect6502; here additionally against py65,
  below), BCD disabled as on the 2A03. The machine calls the player exactly like an NSF
  player: init once, then play at the start of every frame, each call returning to
  `nes_player_idle` where the machine stops. Frames alternate 29780 and 29781 CPU cycles
  (29780.5 = 341 × 262 − 0.5 PPU dots / 3: 60.0988 Hz, the real NTSC rate rather than 60 Hz).
- **Faults** (`CHIP_ERR_FAULT`, then silent until the next start): fetching an undocumented, JAM
  or BRK opcode (FamiStudio executes `brk` on stream opcodes its build does not support), fetching
  the trap address, or a call longer than one frame (init: 200000 cycles).
- **APU**: `nes/nes_apu.c`, written here from the NESdev wiki, because no permissively licensed,
  accurate 2A03 APU in plain C was available (blargg's Nes_Snd_Emu/game-music-emu and MAME are
  C++; puNES and FCEUmm are woven into their emulators' globals) and the Deck builds C only.
  Emulated: pulse duty sequencers, envelopes, sweeps (pulse 1 ones' complement, target-period
  muting even with sweep off), length counters with halt and the `$4015` enables/status, the
  triangle linear counter, the noise LFSR in both modes, the frame counter (4/5-step, `$4017`
  3-4 cycle delay, immediate 5-step clock, IRQ flag), the **DMC** (NTSC rate table 428..54 CPU
  cycles per bit, `$4011` direct load, `$4012`/`$4013` sample address `$C000 + 64 a` and length
  `16 l + 1`, the memory reader fetching sample bytes from CPU memory by DMA with the `$FFFF → $8000`
  wrap, the one-byte sample buffer, the 8-bit output shift unit with its silence flag and level steps
  of ±2 clamped to 0..127, loop, `$4015` bit 4 start/stop/status, the DMC IRQ flag and its clearing
  by `$4010`/`$4015` writes), and the nonlinear mixer (exact NESdev formulas, so the idle
  triangle's held step and the DMC level change the other channels' loudness as on hardware). Not
  emulated: IRQs to the CPU (the frame and DMC IRQs are flags only; FamiStudio uses neither), DMA
  CPU stalls (a fetch steals 1-4 CPU cycles on hardware; here the player's later register writes in
  that frame land those few cycles early, which no row, write order or audible timing depends on),
  the 1-3 cycle delay of a fetch after the buffer empties (here the same cycle), same-cycle
  length/halt quirks, the APU-cycle phase of pulse/noise timers (counted in CPU cycles), triangle
  periods below 2 (frozen, avoiding ultrasonic pops). Silent pulse/triangle/noise timers are not
  stepped (their phase is reset by the write that makes them audible); the DMC timer always runs, as
  on hardware (also while seeking, so a sample carries over a seek; idle stretches advance in one
  step); timers run event to event, not cycle by cycle.
- **Output**: the mixed level as band-limited steps into blip_buf (blargg's band-limited synthesis,
  the technique of game-music-emu and FamiStudio's own NesSndEmu), then a first-order 37 Hz
  high-pass and 14 kHz low-pass (Famicom-style output stage), × 2.0, L = R. The test song peaks at
  0.67 (rms 0.15), the DPCM test song at 0.80 (rms 0.16).

### CPU memory map

| Range | Use |
|---|---|
| `$0000-$0007` | engine zero page |
| `$0100-$01FF` | stack (host calls start at S = `$FB` with the idle return address pushed) |
| `$0200-$02AB` | engine RAM (`NES_RAM_BASE`/`NES_RAM_SIZE`); the prepare script fails if it reaches `$07C0` |
| `$07C0-$07FF` | audition streams, written by the machine (`NES_AUDITION_RAM`) |
| `$4000-$4017` | APU, writes timestamped to their CPU cycle; `$4015` reads status |
| `$8000-$8B11` | player image |
| `$8C00-$FFF9` | the image's region (`NES_SONG_BASE`..`NES_REGION_END`, 29690 bytes), shared: FamiStudio data from `$8C00` upward, the DPCM sample block (64-aligned, at or above `$C000`, where `$4012` can reach) above it; the DMC reads samples by DMA |
| `$FFFA-$FFFF` | vectors → `nes_player_trap`, written by the machine |

Everything else reads 0 and ignores writes.

### Image layout (what the Kotlin lowering produces)

Numbers are generated into both `nes_player_image.h` (`NES_IMAGE_*`) and `NesPlayerImage.kt`; the
native loader is `nes/nes_image.c` (`nm_load_image`), which rejects anything off-contract with a
reason (`NesImageInfo.problem`; the host test prints them) — mirror its checks in Kotlin validation
for user-facing messages. `image_size` = 31770 bytes, little-endian:

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | `"TTNS"` |
| 4 | 4 | player id = first 4 bytes of the player image sha256 (`NesPlayerImage.PLAYER_ID`, currently `0xA9A250E3`): an image only plays on the player build it was lowered for |
| 8 | 2 | song base `$8C00` |
| 10 | 2 | data size S: bytes of song data from `$8C00` (≤ 29690; with samples ≤ sample start − `$8C00`) |
| 12 | 1 | song index: 0 |
| 13 | 1 | layout: 1 = Thor Tracker (below). 0 = raw FamiStudio export + its `.dmc` (tests and tools only: bytes 18/19/20 = rows per order, order count, loop order or `$FF`; no directory; plays, seeks from the start, live apply only copies bytes) |
| 14 | 1 | instrument count, 1..64 |
| 15 | 1 | DPCM list entries (mappings), 0..63 (`NES_IMAGE_HEADER_DPCM_COUNT`; raw: 0) |
| 16 | 2 | envelope count = highest used directory index + 1 (≤ 512) |
| 18 | 4 | 0 |
| 22 | 2 | sample block bytes B (`NES_IMAGE_HEADER_SAMPLE_SIZE`; both layouts); 0 = no samples |
| 24 | 2 | sample block start A (`NES_IMAGE_HEADER_SAMPLE_START`; both layouts); 0 = no samples, else a multiple of 64, ≥ `$C000`, A + B ≤ `$FFFA`, and `$8C00` + S ≤ A |
| 26 | 6 | 0 |
| 32 | 2048 | envelope directory: 512 entries of {u16 address, u8 length (2..255), u8 kind (0 volume, 1 arpeggio, 2 duty, 3 pitch)}; address 0 = unused (all 4 bytes 0); entries ≥ count all zero |
| 2080 | 29690 | region: CPU memory `$8C00-$FFF9` (address *a* at region offset *a* − `$8C00`): song data `[$8C00, $8C00+S)`, samples `[A, A+B)`, every other byte zero |

One budget: song data and samples share the 29690 bytes. A song without samples may fill all of them
(0.9.0 allowed 24570); with samples the song data must end at or below the sample block, which the
Kotlin lowering packs as high as it fits (A = the highest multiple of 64 with A + B ≤ `$FFFA`; it
must stay ≥ `$C000`, so samples total at most 16378 bytes). The raw layout's `.dmc` sits at `$C000`.

**Song data** (all pointers absolute, so data is lowered for `$8C00`):

- FamiStudio song list with exactly one song: `u8 1`, `word instruments`, `word samples−4` (the
  DPCM list; any value when byte 15 is 0), five channel stream words (pulse 1, pulse 2, triangle,
  noise, DPCM), `word tempoPal = 256*T/125`, `word tempoNtsc = 256*T/150` (T = FamiTracker tempo;
  150 → 256).
- **DPCM list** (FamiStudio's, without bank switching): byte 15 entries of 4 bytes at `samples`
  inside the song data, entry *k* (1-based) = mapping *k*: `$4012` = (sample address − `$C000`) / 64,
  `$4013` = (sample bytes − 1) / 16, `$4010` = rate 0..15 | `$40` loop (IRQ and bits 4-5 zero), initial
  `$4011` level 0..127 (FamiStudio: the sample's `DmcInitialValue` × 2, or the mapping's override).
  Every entry's 16 *l* + 1 bytes from `$C000` + 64 *a* must lie inside the sample block `[A, A+B)`.
- **Samples** (the sample block): DMC bytes as the hardware plays them (LSB first, 1 = +2), each from a
  64-byte boundary, 16 *n* + 1 bytes ≤ 4081; FamiStudio's `.dmc` packs them in project order and pads
  each to the next boundary with `$55`. Several mappings may share a sample (other rate, loop or level).
- Instrument table: count × 8 bytes, four envelope pointers each: volume, arpeggio, duty, pitch.
  Each must be a directory entry of that kind (the triangle ignores duty, but the pointer must
  still be valid). Instrument *i* in streams is `$80|(i<<1)` (i < 64) or `$54 i`.
- Envelopes (FamiStudio format): volume byte 0 = release index (0 = none) and pitch byte 0 =
  `$00` absolute / `$80` relative, then instructions: value `$80-$FF` (value + 192; volume 0..15,
  duty 0..3), hold count `$01-$7F` (repeat the value that many frames), loop `$00 index`. An
  envelope ends with its final loop; loop targets are a value or hold of the same envelope; a
  release index points at an instruction. Release envelopes are FamiStudio's: `[R] values… $00
  L(sustain) values… $00 last` (the release tail ends holding its last value). Directory entries
  are ascending by address and must not overlap (no shared bytes between entries).
- **Channel streams** (all five, including DPCM): exactly one `$41 n lo hi` per order, at `stream + 4·o`
  (`n` = entries in that order's pattern stream, 1..255), then `$42 lo hi` → `stream + 4·loop`.
  Nothing else. All channels must agree on the order count and loop.
- **Pattern streams** (one per order and channel; any number of `$41` may share one): flat (no
  `$41`/`$42`/`$44`), exactly `rows(o)` rows on every channel (1..255; Thor Tracker: ≤ 64). A row is
  optional prefix opcodes then **one terminator**; `n` counts terminators:

  | Terminator | Meaning |
  |---|---|
  | `$01-$3F` | note, engine note = byte + 12 (13..75); DPCM: mapping *k* = the byte (1..entries) |
  | `$40 n` | note, engine note n (1..96); DPCM: mapping n − 12 (n 13..75, ≤ entries + 12) |
  | `$00` | stop (DPCM: the sample stops, `$4015 = $0F`) |
  | `$50 step from to` | slide note (ends on `to`); FamiStudio's step (see below) |
  | `$45` | release (volume envelope jumps to its release index; nothing if it has none) |
  | `$81\|(k<<1)` | empty run: this row + k more (k 0..63) |

  | Prefix | Meaning | Channels |
  |---|---|---|
  | `$46 s` | speed (Fxx), 1..127; put it before `$47` so it applies at the row start | 0-4 |
  | `$47 d` | note delay: the rest of the row plays at frame d+1 of the row (FamiStudio NoteDelay N = `$47 N−1`); at most one per row, 0..127 | 0-4 |
  | `$48 c` | cut the note at frame c of the row (FamiStudio CutDelay N = `$48 N`), 0..127; DPCM: stops the sample | 0-4 |
  | `$43` | next note without attack (DPCM: the sample starts without its initial `$4011` level) | 0-4 |
  | `$52 v` | DMC delta counter (FamiStudio DeltaCounter): `$80\|v` writes `$4011 = v` now; `v` (0..127) replaces the initial level of the next sample started | 4 |
  | `$70\|v` | volume track 0..15 (multiplies the envelope) | 0-3 |
  | `$51 step target` | volume slide | 0-3 |
  | `$4E f` | fine pitch: signed period offset (+ lowers the pitch) | 0-2 |
  | `$49 lo hi` / `$4A` | vibrato envelope override (directory entry, kind pitch) / clear | 0-2 |
  | `$4B lo hi` / `$4C` / `$4D` | arpeggio envelope override (directory entry, kind arpeggio) / clear / reset | 0-3 |
  | `$4F d` | duty 0..3 | 0, 1, 3 |
  | `$53` | phase reset | 0, 1 |
  | `$80\|(i<<1)`, `$54 i` | instrument (< count) | 0-3 |

  Anything else (`$55`+, release/slides/instruments on the DPCM channel, opcodes on a channel whose
  engine table has no entry, which would write out of bounds, a DPCM note past the list, which would
  read past it) is rejected. A DPCM note starts its mapping through FamiStudio's
  `famistudio_music_sample_play`: `$4015 = $0F`, `$4012`, `$4013`, `$4010`, `$4011` (initial level,
  or a pending `$52` value; none after `$43`), `$4015 = $1F`. FamiStudio's export puts a DPCM-only
  instrument (one used on no other channel) in no instrument table: the mapping index says it all.
- **Notes**: engine note n (1-96) indexes the NTSC note table; n = 46 is period `$0FD` (440.4 Hz on a
  pulse; FamiStudio calls it A3), so Thor Tracker's absolute note (scientific pitch: 0 = C0, A4 = 57 = 440 Hz)
  is **n + 11** (verified against 12-TET A440 in the host test). Absolute notes are the same pitch on every
  system: the Game Boy's hUGE note 0 (named C-3 by hUGETracker) is C2 = 65.4 Hz = absolute 24.
  Pulses can play n ≥ 10 (A-1, the longest 11-bit period); the triangle uses the same table and
  sounds an octave lower (FamiTracker convention). Noise: the register's period index is
  `((n + arpeggio) & 15) ^ 15`, and duty bit 0 selects the short (93-step) mode.
- **Slide and volume-slide steps**: FamiStudio's asm export computes them for NTSC and PAL together
  (the larger magnitude, NTSC's sign; there is no NTSC-only asm option), its NSF export for NTSC only
  (on ft_test: `$FD`/`$FE`, `$F0`/`$F2`). Either plays; they differ by a frame or two in slide length.
- **Should** (not checked): each order re-establishes the channel state straight play has at its
  start (instrument before the first note, volume track if not 15, active overrides), because an
  order is also entered by the loop, by the seek fallback and by a live edit. Speed: put the song
  speed as `$46` on order 0 row 0 (a loop to order 0 then resets it).

What `nm_load_image` derives (`NesImageInfo`): order count, loop, rows per order, the speed each
order starts with in straight play (6, then the last `$46` of each order in engine order: rows,
then channels 0..4), the NTSC tempo step, a sorted index of the directory, the DPCM list address
and entry count and the used sample bytes.

### Position and seek (exact rows from engine state)

- Before every frame the machine evaluates the engine's row condition (speed not 0, not paused, `acc_hi
  >= speed`); `position()` returns the row the next tick processes: the next row with tick 0 if the
  next frame starts one, else the current row and the frames already played in it. Rows advance
  through `rows(o)` and wrap to the loop order; Fxx, any tempo and note delays are exact because
  the engine itself decides. Raw images use the header's constant timing (no timing: `(0, rows
  since start, tick)`).
- `start(order, row, max_ticks)`: power-on, init, then a silent fast-forward of the real engine from
  the song start until position is `(order, row, 0)` (`CHIP_SEEK_FROM_START`). If that needs more than
  `max_ticks` frames (Thor layout only) it restarts, places every channel at the order's `$41`
  entry, sets the speed that order starts with (accumulator full, so the next frame starts the
  row) and silently replays the order's earlier rows (`CHIP_SEEK_FROM_ORDER`; instruments still
  sounding from earlier orders are lost, as on the Game Boy). Fails if not reached within
  `max_ticks` again, or for a row outside the song. Cost on the dev PC: 11.6 µs per frame (order 3
  row 15, 416 frames: 4.8 ms); suggested `seekTicks` 12000 (~3.3 min, ~140 ms). The DMC runs through
  the fast-forward, so a sample started before the target is heard from where straight play has it
  (same level, address, bytes left and output-unit phase); the order fallback loses samples started in
  earlier orders, like instruments. DPCM test song: 10-14 µs per frame (order 3 row 4, 252 frames:
  2.7 ms).

### Live edits (`apply_image`, realtime-safe, ≤ 15 µs measured)

At a tick boundary, Thor layout to Thor layout:

1. **Position**: the current order/row is kept; if the order or row no longer exists it is clamped
   to the last row of the last (or the current) order, so the next row is the loop order / next
   order (pending note delays are then dropped).
2. **Streams**: each channel's engine stream state (pointer, reference return, entries left in the
   pattern, empty-run remainder) is recomputed in the new data for "rows 0..row of this order
   consumed" — or "0..row−1, positioned after the row's `$47 d`" when the channel has a delayed note
   pending. Edited cells are heard from the next row processed.
3. **Envelopes**: every engine envelope pointer (11 envelope slots, 3 pitch slots in the base NES build) is
   found in the old directory. Instrument envelopes retain their stable slot even if an identical copy of the
   old bytes remains elsewhere, so editing one instrument affects its sustained notes, not another instrument.
   Effect envelopes can be renumbered: keep an unchanged slot, else find identical bytes of the same kind,
   else follow the edited slot. An edited envelope keeps its read position if it is still an instruction
   start, otherwise restarts; a removed envelope falls back to the engine's silent dummy.
4. Instrument table pointer, DPCM list pointer and tempo step are re-read; the speed is set to
   straight play's speed at the playhead if the edit changed it (song speed, an earlier Fxx in this
   order) or the playhead was clamped; otherwise the engine keeps its speed (e.g. one inherited
   through the loop).
5. **Samples** (both layouts; decided before the new bytes replace the old): a sample the DMC is
   playing (bytes left, or looping) keeps playing if its bytes (the `$4012`/`$4013` range) are
   unchanged where they are; else, if an entry of the new DPCM list has a sample of the same length
   with identical bytes, it continues at the same position there (`$4012` and the read address move
   by the same distance); else it **stops**, as a `$4015` write with bit 4 clear does: no further
   fetches, the bits already fetched (≤ 2 bytes) play out and the level holds. So editing, replacing or
   deleting the playing sample cuts it; moving it (another sample grew or was removed, mappings
   reordered) or editing any other sample does not. The latched rate/loop (`$4010`) and level stay
   until the engine starts the next sample, which plays the new list entry and bytes.

**Contract for the builder**: any layout that passes `nm_load_image` relocates — data and samples may
move freely between images. Instrument envelope indices are `slot·4 + kind` (0..255); effect envelopes
start at 256 and may be renumbered, as may DPCM mappings (the streams carry the new numbers). Raw
layouts: bytes are copied (correct only if nothing moved).
The host test applies a fully re-laid-out image before every one of 1080 frames and gets register
writes identical to straight play; with the DPCM test song it moves every sample before every frame
(298 playing samples relocated in 576 frames) and the DMC output stays sample-identical.

### Audition

`audition(channel 0..3, note, slot, fine)` resets the (spare) machine with the image, starts
its song, then points all five channel streams into RAM at `$07C0`: silent channels play `$FF $42`
(64-row empty runs, looping); the auditioned channel plays `$54 instrument`, `$4E fine` (pulses and
triangle only), `$40 note`, then the silent loop — so the note sustains through the real engine
with the image's own instrument table and envelopes. Arguments: engine note 1..96 (absolute − 11),
instrument = the cell's 1-based slot as the hosts pass it (`nes_chip.c` plays table index slot − 1;
slot 0 is rejected; `nm_audition` itself takes the table index), fine = signed period offset −128..127 like `$4E` (ignored on
noise). Raw images and out-of-range arguments are `CHIP_ERR_IMAGE`.

`audition(4, k, slot, fine)` (DPCM): `k` is the 1-based entry of the image's DPCM list (the mapping of
the cell's instrument and note, as the lowering numbered it; the Kotlin engine looks it up), the stream
plays note byte `k`, so the engine starts that sample with its rate, loop and initial level exactly as a
song row does; slot must be ≥ 1 (otherwise ignored), fine is ignored. `k` outside 1..entries is
`CHIP_ERR_IMAGE`; to preview mappings the song does not use, the preview image's list must hold them.
A looped mapping sustains until released.

`release()`: on the DPCM channel always 0 (stop: the host drops the machine). Otherwise, if no tick
has run, or the instrument's volume envelope has no release index, returns 0
(the host drops the machine: a cut). Otherwise the channel's stream is pointed at `$45` + the
silent loop and the accumulator is filled so the next frame reads it: the engine jumps the volume
envelope to its release index; `release` returns 1 and `tick` returns 0 once the envelope has
read its last value (the final loop: the release tail is over). The host keeps ticking until then
(Lead in ft_test: 4 ticks). While transport runs, the hosts mute the song's same channel with
`mute` (mixer level; the song keeps running, unmuting resumes mid-note); `start`/`audition`
clear all mutes (power-on).

### Ops behaviour

| Op | Behaviour |
|---|---|
| `load_image` | copy, then `nm_load_image` (full validation, derived info); `image_storage` = `sizeof(NesImage)` (bytes + info) |
| `start(order, row)` | above; `seek_mode` from start or order |
| `apply_image` | above; ignored on a faulted or auditioning machine |
| `audition`, `release` | above |
| `mute(channel 0-4)` | forces the channel's level to 0 in the APU mixer (DPCM: the sample keeps running; unmuting resumes it at its current position) |
| `position` | above |
| `tick` | one frame: play routine + APU, 798-800 frames at 48 kHz; 0 after a released audition ended |

### Remaining for step 5 (not native)

`.nsf` export (`NsfExporter`, docs/BACKENDS.md: a 128-byte NSF header — load `$8000`, init
`$8000`, play `$8003`, NTSC speed 16639 µs, no bank switching — + `NesPlayerImage.BYTES` + zero fill
to `$8C00` + the region up to its last used byte: song data, zero fill, the sample block). Still to measure on the Thor:
realtime cost per tick and seek cost per frame (dev PC: 129 µs per tick, 129× realtime, on ft_test;
206 µs, 81×, on the sample-heavy DPCM test song; the engine's worst frame 4828 CPU cycles), and
output level against the Game Boy.

### FamiStudio 4.5.3 application (offline reference, not shipped)

The NES tools export with FamiStudio itself. `tools/audio/fetch_famistudio.sh` downloads the
official Linux release `FamiStudio453-LinuxAMD64.zip`
(`https://github.com/BleuBleu/FamiStudio/releases/download/4.5.3/FamiStudio453-LinuxAMD64.zip`,
sha256 `9133afed2fb384663b0735d8e950cdc2714849f4fd476b16de8d56e5e81da0ad`, the digest GitHub lists
for the asset; pinned in the script) into
`${THOR_TRACKER_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/thor-tracker}/famistudio-4.5.3/`, verifies it
(a mismatching file is deleted and the script fails), extracts it to `app/` there and prints that
directory (the one containing `FamiStudio.dll`) as its last stdout line; progress goes to stderr.
It is idempotent: a verified archive is never downloaded again and a complete extraction is reused.
`nes_golden.py` and `nes_reference_check.py` run it themselves; `--famistudio DIR` on either
overrides it with another copy of the release. Running FamiStudio needs .NET 8 (`dotnet` on PATH,
else the tools use the `ubuntu` distrobox, which shares `$HOME` and so the cache). Needs curl and unzip.

### Preparing the player (once, when the pin, configuration or layout changes)

```
python3 tools/audio/prepare_nes_player.py [--famistudio "$(tools/audio/fetch_famistudio.sh)"]
```

It checks the vendored sources' sha256 and the assembler version (ca65/ld65 on PATH or in the
`ubuntu` distrobox), assembles `tools/audio/nes/nes_player.s` with `ca65 -g` and links it alone
(`nes_player.cfg`: code at `$8000`, zero page at `$00`, RAM at `$0200`), checks the entry table,
that the player ends below `NES_SONG_BASE`, that engine RAM ends below the audition area and the
engine's channel/envelope tables, and writes `app/src/main/cpp/generated/nes_player_image.h`
(bytes, entry/engine addresses, layout numbers, player id), `core/.../backend/NesPlayerImage.kt`
and the test fixtures `app/src/test/cpp/nes_ft_song.h` and `nes_dpcm_song.h`. The first comes from
`tools/audio/nes/ft_test.txt` (FamiStudio text, FamiTracker tempo, written for these tests: Fxx
6/7/4/8, note delays and cuts, release notes, volume track and slide, fine pitch, vibrato,
arpeggio instrument and effect, slides on pulse and triangle, duty effect, phase reset, no-attack
notes, loop to order 1) as exported by FamiStudio 4.5.3 itself (`ft_test_ca65.s`, committed;
`--famistudio` re-exports and checks it is unchanged). The export is linked with ld65 at `$8C00`
(`nes_song.cfg`; raw fixture) and parsed by the script, which must give the same bytes, then rearranged into the
Thor layout without changing any row's bytes, plus variants (every block moved; one note and one
envelope edited; cut to two orders; the patterns moved to the top so the song data fills all 29690
bytes). The second does the same for `tools/audio/nes/dpcm_test.txt`
(five DMC samples synthesized for it, eight mappings over three rates, loops and per-sample /
per-mapping initial levels; a DPCM channel with stops, delta counter now and at the next sample, note
delay, cut, a no-attack note and a row of samples every row; Fxx 6/5/3/7; pulse and triangle on top)
and its export `dpcm_test_ca65.s` + `dpcm_test_ca65.dmc` (the samples; the list is assembled with
`FAMISTUDIO_DPCM_PTR = 0`, i.e. samples at `$C000`): raw fixture with the `.dmc`, the Thor layout with
the samples where the `.dmc` has them, every block moved and the samples packed at the top, one Kick
byte inverted, the samples at the top with the song data ending exactly at them; and the DMC writes the
text asks for, derived from the text alone. Two runs produce identical files.

### Verification

- `tools/audio/run_native_host_test.sh [dir]` also builds and runs `app/src/test/cpp/nes_machine_test.c`
  (2026-11-07: **336 checks, 0 failures**; Game Boy unchanged at 145). APU: as in step 4 (pitch by
  zero crossings, length/envelope/linear/sweep/frame counter, LFSR, mixer). Player: entry table,
  FamiTracker speed/step after init, note table vs 12-TET. Images: derived rows, loop and order-start
  speeds equal the text; 25 single-byte corruptions each rejected with its reason. Playback: the
  Thor layout writes **the same APU registers as FamiStudio's own export in all 1080 frames**
  (first pass + two loops); all 160 row starts on the frames the text's Fxx gives (6/7/4/8), with
  the loop; cut at frame 4, delays at frames 2 and 5, release tail 10-7-4-0 exactly when the text
  says; pitch of held pulse/triangle notes 841.06 / 48.17 Hz (= timer); deterministic render; tempo
  125 row starts equal an independent model of the accumulator (68 rows in 536 frames vs 452).
  Seek: six targets from the start, register-identical to straight play for 200 frames; the order
  fallback (budget 60) lands on 2/8 after 56 frames and stays in step; limits. Live apply: a
  re-laid-out image applied before every frame — identical writes for 1080 frames, pointers really
  moved; Lead's sustain edit heard on the sustaining note (12 → 8); the edited note at 2/8; speed edits
  (applied 4 → 5 at 2/10; kept 8 at 3/4, 7 at 2/6 and 1/3; the edited Fxx runs at its row); orders
  removed under the playhead; raw apply. Audition: A440 on the first tick, sustain 12 for 2 s, other
  channels silent, release tail 4 ticks then 0, fine +10/−5 on pulse/triangle, instrument arpeggio,
  noise period/mode, triangle without release = cut, argument limits. Mute, faults (JAM, BRK,
  runaway, an unknown opcode in a raw image reaching the engine's BRK), and the ops table as the hosts
  drive it (start, rows over Fxx, apply keeps position, audition with channel borrow, release).
- DPCM (same run). DMC unit checks against an independent NESdev table/model: the level steps once per
  rate period at all 16 rates; `$0F $AA $00` from 64 gives 66 68 70 72 70 68 66 64 | 62 64 … | 62 60 … 48;
  clamping at 126/127/1; `$4013 = 1` fetches 17 bytes `$C000-$C010` with `$4015` bit 4 until the last;
  `$FFC0` + 65 bytes wraps to `$8000`; loop (694 fetches, no IRQ); IRQ flag set/kept/cleared; `$4015`
  stop mid-sample (fetched bits play out, then the level holds); `$4011` during playback; identical DMC
  state with and without synthesis. FamiStudio's export of `dpcm_test.txt` + its `.dmc`: the Thor layout
  writes **the same APU registers in all 816 frames, 405 of them `$4010-$4013`/`$4015`**; 160 row starts
  on the text's frames; all 30 DMC events of the text (26 sample starts, stops, cut, delay, delta counter
  now and deferred, no attack) register for register at their frames and no other start; after the Kick
  (rate `$C`) and the Long sample (1025 bytes, rate `$F`, fetched in 14 frames) the level equals a
  bit-by-bit model of the bytes (28, 44); looped samples run to their Stop (884 / 2171 bytes, 27 / 66
  passes); the DMC alone is audible and deterministic. Seek into the sample-heavy order 2 (3 targets),
  mid-loop and mid-Long: the DMC state at landing equals straight play's, writes and DMC in step for
  200 frames, the DMC heard afterwards sample-identical; the order fallback's DMC writes in step.
  Live edits: every sample moved before every frame — same writes (`$4012` mapped), 298 relocations,
  DMC output sample-identical; an edited playing Kick stops, an unchanged playing Snare continues, the
  next Kick plays the edited bytes (model 14 vs 28); the same image mid-sample changes nothing. 21 DPCM
  and sample-block corruptions rejected with reasons (misaligned, below `$C000`, past the vectors, size
  without start, samples outside the block, bytes outside song and block). Audition of a mapping (first
  tick `$4012 $4013 $4010 $4011 $4015=$1F`, other channels silent, release = 0), a looped mapping for
  2 s, limits; mute 4 silences the DMC while it keeps fetching; the ops table with channel 4 borrowing.
- Shared region (same run): a sample-less ft_test image with **29690 bytes of song data** (patterns at
  `$FF1A`; 0.9.0's limit was 24570) plays register-identical to the compact layout for 1080 frames,
  relocates between the two before every frame with identical writes, and seeks to 2/8 in step for 200
  frames. dpcm_test with its samples at `$F880-$FFD0` and 27776 bytes of song data ending exactly at
  `$F880` plays register-identical to the `$C000` layout (`$4012` mapped) for 816 frames, sounds
  DMC-identical, and alternates with it before every frame (samples moving `$C000` ↔ `$F880`) with the
  DMC identical; one more byte of song data, or the block starting 64 bytes lower, is rejected ("song
  data overlaps the samples").
- Evidence written to the directory: `nes_ft.wav` / `nes_ft_tonal.wav` (30 s, 48 kHz), `nes_trace.txt`
  (per-frame APU writes), `nes_ft.nsf` (player + FamiStudio's export); `nes_dpcm.wav` /
  `nes_dpcm_dmc.wav` (the DMC alone), `nes_dpcm_trace.txt`, `nes_dpcm.nsf` (player, export, samples
  at `$C000`).
- `tools/audio/nes_reference_check.py <dir> [--famistudio <FamiStudio 4.5.3 Linux release>]`
  (developer evidence: numpy, py65, libgme; FamiStudio, fetched by `fetch_famistudio.sh` unless
  `--famistudio` is given, needs .NET 8) — 2026-11-07, PASS:
  - py65 running `nes_ft.nsf`: 1080 frames, 9309 APU writes identical in order and value.
  - **FamiStudio's own `nsf-export`** (its FamiTracker-tempo kernel and its NTSC data) in py65: identical
    APU writes in all 1080 frames except 95 frames of pulse/triangle period during the two slides
    whose step differs between FamiStudio's asm (Dual) and NSF (NTSC) exports (see above).
  - Audio vs FamiStudio's own `wav-export`: loudness-envelope correlation 0.92 (pulses + triangle
    0.94), log-spectrum 0.85 / 0.91, strongest partial in the same pitch class in 95% / 98% of
    windows. libgme renders this song differently from both FamiStudio and us (same pitch class in
    ~45% of windows against either), so it only gets loose bounds (0.83 / 0.84 for us; 0.79 / 0.82
    FamiStudio vs libgme).
  - DPCM song: py65 running `nes_dpcm.nsf`: 816 frames, 5878 APU writes (405 DMC) identical.
    FamiStudio's own `nsf-export` in py65 (its kernel places the samples at `$E000`): identical APU
    writes in all 816 frames with `$4012` relative to the sample base. Audio vs FamiStudio's
    `wav-export`: envelope 0.90, log-spectrum 0.93, same pitch class in 92% of windows; the DMC alone:
    envelope 0.92, log-spectrum 0.80 vs FamiStudio, and 0.98 / 0.92 / 88% vs libgme (blargg's DMC);
    the two references agree with each other less (0.89 / 0.79 / 36%).
- Not done: listening on hardware, blargg's APU test ROMs (they need a PPU and IRQs), a comparison
  with a real NES capture, Thor measurements.

## VRC6: the same engine with Konami's VRC6 (`"nes-famistudio-vrc6"`)

The first NES expansion chip (design in `docs/BACKENDS.md`, "NES expansion chips"). Everything in the
NES section above holds for this engine; this section lists only what differs. The 2A03 engine, its
player bytes, image size and song base are unchanged (`prepare_nes_player.py` checks its four outputs
stay byte-identical).

### What runs

- **Player** (`generated/nes_vrc6_player_image.h`, `NesVrc6PlayerImage.kt`; 3430 bytes at `$8000`, id
  `$6CCBFF6E`, image sha256 `6effcb6c…eab`): `tools/audio/nes/nes_player.s` assembled a second time with
  `-D FAMISTUDIO_EXP_VRC6=1`, nothing else changed: the same entry table (`$8000` init, `$8003` play,
  `$8006` idle, `$8009` trap), the same configuration plus FamiStudio's VRC6 code and its saw note
  tables (`NoteTables/famistudio_saw_note_table_lsb/msb.bin`, pinned). Engine RAM `$0200-$0326`, 20
  envelope slots and 6 pitch envelope slots (2A03: 11 and 3); the audition streams stay at `$07C0`.
- **Song base `$8E00`**: the first page after the larger player, so the region is `$8E00-$FFF9`,
  **29178 bytes** (the 2A03 build: 29690), shared with the DPCM samples exactly as there (samples at the
  top, 64-aligned, never below `$C000`). Image = 32-byte header + directory + region = **31258 bytes**
  (`NES_VRC6_IMAGE_SIZE`); the header layout is the same, with this player's id and song base.
- **Channels**: engine channels 0-4 as the 2A03 build, then **5 VRC6 Pulse 1, 6 VRC6 Pulse 2, 7 VRC6 Saw**.
  The FamiStudio song header has a stream word per channel: count, instrument table, samples − 4,
  **eight** channel streams, PAL tempo, NTSC tempo (offsets 21 and 23). Thor layout as before: one
  order stream (`$41 n ptr` per order, `$42`) per channel, eight of them.
- **Opcodes**: `$55 v` (VRC6 saw only) sets `famistudio_vrc6_saw_volume`: `$01` Full (volume × 4),
  `$00` Half (× 2, the power-on value), `$FF` Quarter (× 1) — FamiStudio writes `1 − Vrc6SawMasterVolume`,
  which its ca65 export prints as `$ffffffff` for Quarter (the binary export and our lowering write
  `$FF`). The saw's 6-bit accumulator rate is the volume (envelope × volume track, `famistudio_volume_table`)
  shifted by it; rates above 42 overflow and distort. `$4F d` (duty effect) takes 0-7 on the VRC6 pulses
  (`famistudio_vrc6_duty_lookup`: duty d = d + 1 of 16 steps) and is invalid on the saw; `$53` (phase
  reset) is valid on the 2A03 pulses and all three VRC6 channels. Duty envelope values are 0-7 on VRC6
  instruments. The VRC6 pulses use the 2A03 note table (their 12-bit periods reach every note down to C-1),
  the saw `famistudio_saw_note_table` (CPU / (14 f) − 1). Pitch shift 0: slides have the 2A03's one
  fraction bit.
- **VRC6 audio** (`nes/nes_vrc6.c`, written here from the NESdev wiki "VRC6 audio"): registers
  `$9000-$9003`, `$A000-$A002`, `$B000-$B002` (writes timestamped to the CPU cycle like the APU's; reads
  there return the ROM, as the registers are write-only). Pulses: 12-bit divider, a 16-step duty generator
  counting down, output volume V while step ≤ duty D (or always with the mode bit), clearing E silences and
  resets to step 15. Saw: the rate is added to an 8-bit accumulator on clocks 2, 4 … 12 and the 14th clock
  resets it; output = accumulator >> 3; clearing E forces it to 0. `$9003`: H halts every oscillator, B
  shifts the periods right by 4, A by 8 (overriding B). Mix: a linear DAC of pulse + pulse + saw (0-61), one
  step = the 2A03 mixer's full pulse ÷ 15 (NESdev: a full VRC6 pulse ≈ a full 2A03 pulse), added to the
  2A03's band-limited buffer with positive polarity. Event-driven with output, arithmetic without (seek).
- **Machine** (`nes_machine.c`): one machine is created for one build (`nm_create(rate, &nes_player_vrc6)`;
  `nes/nes_player.h` describes both builds from their generated headers: bytes, entries, song base, region
  and image size, channel count, envelope slots, duty range, engine RAM symbols). Images are loaded for a
  build (`nm_load_image(image, player)`); a machine refuses images loaded for the other one. Seek, live
  relocation, audition and mute work on all eight channels exactly as on five. Audition takes channels
  0-3 and 5-7 (fine pitch on 0-2 and 5-7). Mute 5-7 silences the VRC6 channels in the mix.
  `nm_write_log` gives every sound write (APU and VRC6) as (address low, high, value); `nm_apu_log` still
  gives only the APU's.
- **Validation** (`nes_image.c`, per build): stream and song pointers against this build's song base and
  region, eight streams, the duty range 0-7 in envelopes and `$4F` on the VRC6 pulses, `$55` only on the
  saw with `$00`/`$01`/`$FF` (on the 2A03 build `$55` is "not allowed in a row"). Raw FamiStudio exports
  with eight channels load too (song stride 2 × 8 + 4).
- **Ops** (`nes_chip.c`): `nes_vrc6_chip_ops`, id `"nes-famistudio-vrc6"`, `image_size` 31258, the same
  functions as `"nes-famistudio"` over a machine of the VRC6 build. `.nsf` export: the VRC6 player at
  `$8000`, the data at `$8E00`, header byte `$7B` = `$01` (VRC6), so NSF players emulate the chip.

### Preparing the player

`python3 tools/audio/prepare_nes_player.py` builds both players in one run. For the VRC6 build it also
writes the fixtures `app/src/test/cpp/nes_vrc6_song.h` from `tools/audio/nes/vrc6_test.txt` (FamiStudio
text with `Expansions="VRC6"`: 2A03 lead, bass and hat beside VPulse/VSweep/VThin on the VRC6 pulses with
all eight duties, duty envelopes and effects, the three saw master volumes switching between instruments,
vibrato, slides on a VRC6 pulse and the saw, fine pitch, volume track and slide, note delay, cut, phase
reset, release notes, no-attack, Fxx 6/7/4/5, loop to order 1) as exported by FamiStudio 4.5.3
(`vrc6_test_ca65.s`, committed): raw at `$8E00`, the Thor layout, every block moved, and one note plus the
VPulse sustain edited.

### Verification

- `tools/audio/run_native_host_test.sh [dir]` builds and runs `app/src/test/cpp/nes_vrc6_test.c`: VRC6 unit
  checks (each duty's high steps, mode bit, 12-bit period timing, disable/reset, the saw's accumulator
  sequence at rate 42 and the wrap above 42, `$9003` H/B/A, mixer level vs the 2A03 pulse, mute); image
  rules (raw and Thor loads, eight streams, order-start speeds, the builds refusing each other's images,
  duty 8 and saw volume `$02` rejected, song base and region size); playback (**the Thor layout writes the
  same APU and VRC6 registers as FamiStudio's own export through the first pass and two loops**, row starts
  on the text's frames, duty 7 and the note-table period on VRC6 Pulse 1, saw rates 2×/4×/1× for
  Half/Full/Quarter, VThin's duty envelope 1-3-5, the saw period from the saw table, the saw table vs
  12-TET, pitch of a held VRC6 pulse and saw note by zero crossings); seek to six targets in step with
  straight play for 200 frames and the order fallback holding the order's saw volume; live relocation
  before every frame with identical writes, the VPulse sustain edit heard, the edited note at 2/8;
  audition on VRC6 Pulse 1/2 and the saw (periods, fine pitch, release tail, cut, limits); mute of 5-7 in
  the mix; the ops table. Evidence: `nes_vrc6.wav`, `nes_vrc6_trace.txt` (per-frame APU and VRC6 writes),
  `nes_vrc6.nsf` (player + FamiStudio's export, `$7B` = `$01`).
- Kotlin: `NesGoldenTest` runs the golden songs `vrc6_demo` and `vrc6_effects` (FamiStudio's export of our
  text vs our lowering, register traces with the VRC6 writes compared every frame); `NesLoweringTest`
  checks the VRC6 image contract; `NesExportTest`, `NesProjectCodecTest` and `NesInstrumentViewTest` cover
  the `.nsf`/text exports, files and the editor.
- Not done: listening on hardware or a VRC6 cartridge, comparison with a reference VRC6 emulator's audio
  (libgme/NSFPlay), Thor measurements.

## EPSM: the same engine with a YMF288 (`"nes-famistudio-epsm"`)

**Status: unverified source slice** (branch `nes-epsm`): written without building, generating or running anything;
the numbers below that depend on generation (player size, song base) are measured by `prepare_nes_player.py` when the
parent runs it. Design in `docs/BACKENDS.md`, "NES expansion chips". Everything in the NES section holds; this section
lists what differs. The 2A03 and VRC6 builds' player bytes, image sizes, song bases and fixtures are unchanged
(`prepare_nes_player.py` checks its outputs for them stay byte-identical).

The EPSM (NESdev "Expansion Port Sound Module") is a Yamaha **YMF288 (OPN3-L)** — the YM2608's six 4-operator FM
channels, three SSG channels and six ADPCM-A rhythm drums from an on-chip ROM; **no ADPCM-B and no clock prescaler** —
on an 8 MHz oscillator, written through `$401C` (address, A1 = 0), `$401D` (data, A1 = 0), `$401E` (address, A1 = 1)
and `$401F` (data, A1 = 1). It is not a full YM2608.

### What runs

- **Player** (`generated/nes_epsm_player_image.h`, `NesEpsmPlayerImage.kt`): `tools/audio/nes/nes_player.s`
  assembled with `-D FAMISTUDIO_EXP_EPSM=1` (3 SSG, 6 FM, 6 rhythm channels), plus FamiStudio's EPSM note tables
  (`NoteTables/famistudio_epsm_note_table_{lsb,msb}.bin` for FM, `famistudio_epsm_s_note_table_{lsb,msb}.bin` for
  the SSG, byte-identical to tag 4.5.3 and SHA-256-pinned in the script). **20 channels**, **35 envelope slots** and
  **12 pitch envelope slots**; `famistudio_channel_env` covers channels 0-13 only (the rhythm drums have no envelope
  slots): an SSG has four (volume, arpeggio, **mixer** at offset 2, **noise** at offset 3), an FM channel two (volume,
  arpeggio). Song base: the first page after the player (`NES_EPSM_SONG_BASE` / `NesEpsmPlayerImage.SONG_BASE`); the
  region up to the vectors is shared with the DPCM samples as on the 2A03 build; image = 32-byte header + directory +
  region (`NES_EPSM_IMAGE_SIZE`). `NES_EXPANSION_EPSM` = 7 (FamiStudio's `ExpansionType.EPSM`).
- **Channels**: engine channels 0-4 as the 2A03 build, then **5-7 SSG 1-3**, **8-13 FM 1-6** and **14-19 the rhythm
  drums** bass drum, snare, top cymbal, hi-hat, tom and rim shot (Thor Tracker: Kick, Snare, Cymbal, Hi-hat, Tom, Rim).
- **Song header** (FamiStudio's for expansions with their own instruments): count, instruments, **instruments_exp**,
  samples − 4, twenty channel stream words, PAL tempo, NTSC tempo (`nes_player_head` = 7 bytes before the stream
  words). The Thor layout keeps one order stream per channel (`$41 n ptr` per order, `$42`).
- **Instruments**: the 2A03 table (64 × 8 bytes, as before) and the expansion table at header word 3, one **16-byte
  entry per slot** (an EPSM channel's instrument byte indexes entry i × 16, the same index as the 2A03 table;
  FamitoneMusicFile.cs `instruments_exp`, `famistudio_set_epsm_instrument`):

  | Offset | Size | Field |
  |---|---|---|
  | 0 | 2 | volume envelope (directory entry of kind 0) |
  | 2 | 2 | arpeggio envelope (kind 1) |
  | 4 | 2 | pitch envelope (kind 3) |
  | 6 | 2 | **mixer** envelope (kind 4) |
  | 8 | 2 | **noise** envelope (kind 5) |
  | 10 | 1 | SSG hardware envelope shape: 0 = none, else `$08-$0F` (`EPSMSquareEnvelopeShape` 1..8 + 7) |
  | 11 | 1 | auto-period octave, signed −8..8, or `$80` = manual period |
  | 12 | 2 | manual envelope period (u16) |
  | 14 | 2 | pointer to the sound's **31-byte FM patch** |

  Patch blocks follow the expansion table (identical blocks shared), in FamiStudio's export order (`epsmRegOrder`,
  the engine's `famistudio_epsm_register_order`): `$B0` (feedback, algorithm), `$B4` (L, R, AMS, PMS), then for
  operators 1-4 (algorithm-diagram numbering, register offsets +0, +8, +4, +C) `$30 $50 $60 $70 $80 $90`, then the
  four total levels (`$40 $48 $44 $4C`, written with the volume rather than at the load), and byte 30 = `$22`, the
  song's LFO (`NesSound.epsmLfo`, the same in every block). The rhythm drums read only the volume envelope's first
  value and byte 1's pan bits (`& $C0`). `nm_load_image` checks the five envelope words are directory entries of those
  kinds, the shape, the octave, and that each patch pointer's 31 bytes and the table lie inside the song data.
- **Directory kinds 4 (mixer) and 5 (noise)**, legal only in the EPSM build (other builds: "envelope kind outside
  0..3"); neither has a header byte (instructions from index 0, like arpeggio and duty). Mixer values are
  FamiStudio's processed bits `$C0` (tone + noise), `$C1` (noise), `$C8` (tone), `$C9` (neither); noise values 0..31.
  Thor Tracker's directory indices: slot × 4 + kind for volume, arpeggio, duty and pitch (unchanged), mixer
  256 + 2 × slot, noise 256 + 2 × slot + 1, effect envelopes (`0xy`, vibratos) from **384** (`effect_envelopes`).
- **Opcodes**: `$5C` (FM 1-6 only; ends the row) = FM release: key off, so the note fades at the patch's release
  rates (an OFF on a sounding FM note; a second OFF is the usual stop). `$5D lo hi` (SSG 1-3 only) = the shared manual
  envelope period for the frame (`Ixx`/`Jyy`; FamiStudio's exporter moves them to SSG 1, as Thor Tracker's lowering
  does). `$4F` (duty) is invalid on every EPSM channel; the rhythm channels also reject `$45`, `$49-$4B`, `$4D`,
  `$4E`, `$50`, `$5C` and `$5D` (no release, slides, pitch or vibrato on a fixed-pitch drum).
- **What the driver writes** (FamiStudio 4.5.3, `famistudio_ca65.s`): init `$07` = `$38` (three tones, no noise),
  `$29` = `$80` (all six FM channels), `$27` = `$00`, rhythm total level `$11` = `$37`. **FM**: an instrument load
  writes the patch registers and `$22`; every frame the carriers' total levels = the patch's TL + the attenuation
  `famistudio_epsm_fm_vol_table[volume]` (`$7E` at volume 0 … `$00` at 15; carriers by algorithm), the frequency
  `$A4`/`$A0` (block and F-number from the FM note table + pitch) and `$B4` (pan, AMS, PMS); an attack keys the
  channel off and on again (`$28` = `$F0` | channel, `$F4-$F6` for FM 4-6); a stop keys it off and writes `$B4` = 0
  (both outputs off). **SSG**: the tone period from the SSG note table, `$08-$0A` = volume | `$10` when the sound has
  a shape; the last SSG writes the shared `$0B/$0C` (the envelope period of the highest SSG sounding a sound with a
  shape: its tone period shifted right by a positive octave, the last shift rounding up, or left by a negative one;
  or the manual period), `$06` (the noise period of the highest SSG with noise in its mixer), `$07` (the three mixer
  values) and, after any SSG attack with a shape, `$0D`. **Rhythm**: on an attack `$18 + i` = (volume × 2) | pan,
  then `$10` = 1 << i (key on).
- **Pitch** (from FamiStudio's tables and the 8 MHz hardware): note byte n sounds at absolute n − 1, an octave below
  the name FamiStudio shows. SSG: TP = table[n], f = 8 MHz / 64 / TP (n = 58 → 440.1 Hz). FM: the driver normalises
  the table value to an F-number and block, f = F · (8 MHz / 144) / 2^(21 − B) (n = 58 → 439.1 Hz). Kotlin sends EPSM
  SSG and FM notes as `NesRules.engineNote(expansion, channel, note)` with shift 12, so absolute 57 = A-4 = 440 Hz
  on every pitched channel; the text export writes FamiStudio's n (shown an octave higher there).
- **YMF288** (`nes/nes_epsm_ymfm.cpp`, `nes/nes_epsm.h`): ymfm's `ymf288` (Aaron Giles, BSD-3-Clause, the
  Mega Drive's pin, unmodified; `ym/ym_ymfm.cpp` compiles `ymfm_opn.cpp` once for both) at `OPN_FIDELITY_MED`: one
  native sample per 144 chip clocks = 55555.6 Hz, the chip's own FM rate; the SSG runs at its effective 2 MHz and is
  resampled 9:2 as ymfm's `ymf288::generate` does. Mesen2 emulates the EPSM the same way.
- **EPSM unit** (`nes/nes_epsm_unit.c`): bus `$401C-$401F` (writes timestamped to the CPU cycle: the chip runs up to
  it first; reads see the ROM, the registers are write-only). Clock: one native sample per 144 × 1789773 / 8000000 =
  **32.216 NTSC CPU cycles**, counted exactly in fixed point (8000000 units per CPU cycle, 144 × 1789773 per sample).
  The chip runs whether or not output is attached.
- **Stereo and mix level**: each native sample's left and right levels go as band-limited steps into two extra
  blip_buf buffers at the sample's CPU cycle; the machine adds its centred 2A03 to both and runs one output stage per
  side. **This build's output is true stereo** (FM `$B4` and rhythm pans); the other NES builds stay mono. The levels
  are **FamiStudio 4.5.3's own EPSM-to-2A03 balance** (`Nes_EPSM.cpp`, `ym3438.cpp`, `Simple_Apu.cpp`) in the 2A03
  mixer's scale: one FamiStudio unit = 10.59857 / 163430 (its EPSM Blip_Synth volume over its range); FM = 18 / 16
  units per ymfm OPNA unit (FamiStudio sums 6 × the YM3438-mode 9-bit output on 3 of 4 cycles; ymfm's sum is that
  >> 1); rhythm = 1.1 units per ymfm ADPCM-A unit (4 of 24 cycles × value >> 1 × 11/20); SSG = (4080 × 10 / 18) /
  (16382 × 2 / 3) units per ymfm mix unit (emu2149's YM2149 table top over ymfm's, FamiStudio's × 10 / 18). Output
  gain 2.0, as the 2A03 build: FamiStudio's 16-bit clipping point lands on the hosts' ±1. **No hardware measurement
  of the EPSM's level against a Famicom's 2A03 is published**; this is the composing tool's balance, not a survey.
- **Seek**: the YMF288 is clocked sample by sample through the silent frames (output detached), so FM envelopes,
  phases, feedback and the LFO, the SSG's tone/noise/envelope counters and the rhythm drums' ADPCM positions are those
  of straight play — no register-snapshot restore. A machine reset restores a snapshot of a freshly constructed chip
  (ymfm's save/restore state, taken once at creation; no allocation).
- **Rhythm ROM** (`nes/nes_epsm.h`): the drums play the YM2608 rhythm ROM (8192 bytes: bass drum, snare, top cymbal,
  hi-hat, tom, rim shot at ymfm's fixed addresses), which Thor Tracker does not distribute. `int
  nes_epsm_install_rhythm_rom(const uint8_t* rom, size_t size)` copies it once into static storage and publishes it
  with an atomic release store for every EPSM chip: 0 ok (the identical bytes again are a no-op); −1 for NULL, a
  size other than 8192, or other bytes than an installed ROM (installed storage is never rewritten while audio
  threads may read it). `nes_epsm_rhythm_rom_installed()` is 0/1. The caller checks the ROM's identity first
  (`EpsmRhythmRom.problem()`: size, then SHA-256 `53afd0fa…eba8`). Hosts: Android
  `ChipAudio.nativeInstallRhythmRom(rom: ByteArray): Int` (JNI, `audio_jni.cpp`), called by `MainActivity` at start
  for a stored `filesDir/firmware/ym2608_adpcm_rom.bin` that still verifies and after an import; the Deck calls the
  same C function through `decknative.def` for `~/.local/share/thortracker/firmware/ym2608_adpcm_rom.bin`. Without
  a ROM a rhythm key-on makes no sound (that drum stays out of the mix until a key-on with the ROM) and sets
  `nes_epsm_chip_rhythm_missing` (`nm_epsm_rhythm_missing`): a diagnostic only — Kotlin's `lower()` refuses a song
  whose played orders contain a rhythm note, and audition refuses a drum, with `EpsmRhythmRom.MISSING` ("… import
  your copy once with "EPSM rhythm ROM…" …"), so nothing ever substitutes another sound.
- **Live edits**: SSG: the machine sees on the bus which expansion entry each SSG loaded last (the engine's store of
  the shape byte right after reading entry + 10) and which entry the manual period came from (the store after reading
  entry + 12; a `$5D` makes it none);
  `apply_image` rewrites each SSG's shape nibble (keeping bit 7, a pending envelope restart) and octave from its
  entry, and the shared manual period from the entry it came from; an SSG whose entry turned manual after the
  period's last store sets it, as the engine's own load would. FM: a channel whose loaded instrument's 31-byte patch
  differs in the new image gets that instrument stored into `famistudio_chn_epsm_fm_instrument` again, so the
  engine's next update reloads the patch, algorithm, pan and operator levels exactly as its own load does. Rhythm:
  each drum's level and pan are re-read from the instrument it last took them from. An identical image changes
  nothing.
- **Audition**: on every EPSM channel. A held note `$54 i [$4E f] $40 n` (fine pitch on SSG and FM); release is the
  generic `$45` on SSG (only with a volume release point, else a cut), **`$5C` on FM** (always: the patch's release,
  ending when the volume tail is done and the carriers are quiet); a drum is a one-shot of the ROM and ends at its
  end address.
- **Mute** 5-19 leaves a channel out of the chip's sums (it keeps running; unmuting resumes mid-note).
  `nm_write_log` gives the `$401C-$401F` writes with the APU's.
- **Validation** (`nes_image.c`, EPSM build): twenty stream words, the 7-byte head with the `instruments_exp`
  pointer, the expansion entries and patch blocks above, mixer values `$C0/$C1/$C8/$C9`, noise 0..31, directory kinds
  0..5, effect envelopes from 384, `$5C` only on FM 1-6, `$5D lo hi` only on SSG 1-3, the rhythm channels' rejected
  opcodes and `$4F` on every EPSM channel. Other builds refuse EPSM images and the reverse.
- **Ops** (`nes_chip.c`): `nes_epsm_chip_ops`, id `"nes-famistudio-epsm"`, `image_size` `NES_EPSM_IMAGE_SIZE`, the
  same functions as `"nes-famistudio"` over a machine of the EPSM build. `.nsf` export: the EPSM player, header byte
  `$7B` = `$80` (FamiStudio `NsfFile.cs`: EPSM), so NSF players emulate the chip (and use their own rhythm ROM copy).
  FamiStudio text: `Expansions="EPSM"`, see `docs/BACKENDS.md` (a manual SSG envelope period other than 4000 cannot be
  written there; the text export refuses it).

### Preparing the player

`python3 tools/audio/prepare_nes_player.py` builds the EPSM player with the others and writes
`nes_epsm_player_image.h` (`NES_EPSM_*` and `NES_EPSM_FAMISTUDIO_*` macros), `NesEpsmPlayerImage.kt` (`CHANNELS` 20,
`EXP_INSTRUMENT_SIZE` 16, `PATCH_SIZE` 31, `KIND_MIXER` 4, `KIND_NOISE` 5, `EFFECT_ENVELOPES` 384) and
`app/src/test/cpp/nes_epsm_song.h` from `tools/audio/nes/epsm_test.txt` as exported by FamiStudio 4.5.3
(`epsm_test_ca65.s`; `--famistudio "$(tools/audio/fetch_famistudio.sh)"` writes it when missing). The test text
covers the 2A03 beside SSG (mixer tone/noise/both/none and noise envelopes, hardware-envelope shapes with auto
periods at octaves 3/1/−1/−3 and a manual period, `EnvelopePeriod`, slides, vibrato, arpeggio, volume slide, delay,
cut, release), FM (the Default preset and custom algorithms 0/2/4/5/7, feedback, left/right/both pans, AMS/PMS with
the LFO, SSG-EG, releases, slides, vibrato, fine pitch, stop, the range ends C-1/B-7) and all six rhythm drums
(left/right/both, `Cxx`, delay, cut).

### Verification (to be run by the parent; nothing here has been run)

- `tools/audio/run_native_host_test.sh [dir]` builds and runs `app/src/test/cpp/nes_epsm_test.c`. Evidence:
  `nes_epsm.wav` (30 s, all channels; the drums silent without the ROM), `nes_epsm_tonal.wav` (drums muted),
  `nes_epsm_trace.txt` (per frame `$4000-$4017` and `$401C-$401F` writes), `nes_epsm.nsf` (`$7B` = `$80`), and, only
  with a private ROM (below), `nes_epsm_rhythm.wav` (only the drums).
- **Private rhythm checks**: `THOR_EPSM_RHYTHM_ROM=/path/to/ym2608_adpcm_rom.bin` (the user's own copy; size and
  SHA-256 checked, anything else refused) enables the rhythm checks of `nes_epsm_test` (which then also writes
  `nes_epsm_rhythm.wav`) and the drums of `tools/audio/nes_render.c` (also its optional fourth argument:
  `tools/audio/render_nes_image.sh image.nesimg out.wav [seconds] [rhythm-rom]`). `tools/audio/nes_reference_check.py`
  never reads the ROM: it compares `nes_epsm_rhythm.wav`, when present, with FamiStudio's wav-export (FamiStudio's
  emulator has its own built-in copy). Unset, the test prints "rhythm ROM checks skipped (THOR_EPSM_RHYTHM_ROM
  unset)". Nothing copies, stores or commits the ROM; keep it and the rhythm evidence outside the repository.
- `tools/audio/nes_reference_check.py <evidence-dir>`: py65 runs our `nes_epsm.nsf` and FamiStudio's own NSF export of
  `epsm_test.txt`; both must write our trace's registers (values differing only in pitch registers on slide frames,
  where FamiStudio's asm and NSF exports compute different slide steps); our SSG + FM + 2A03 audio is compared with
  FamiStudio's wav-export of channels 0-13 (libgme has no EPSM).
- `wav_render_test.c` renders `expansion-port.nesimg` (the demo, no rhythm) and `epsm-shortest.nesimg` (one row at
  speed 1, A-4 held on FM 1; once-through under 1 s, peak above 500) from `exportSamples`.
- Kotlin: `NesGoldenTest` runs the golden songs `epsm_demo`, `epsm_effects` and `epsm_rhythm` (FamiStudio's export of
  our text vs our lowering, register traces with the EPSM writes compared every frame; generate them as in
  `docs/VERIFICATION.md`); `NesLoweringTest`, `TruePitchTest` and `NesInstrumentViewTest` cover the image contract,
  pitch and editor.
- Not done: any build or run; listening; a hardware EPSM; a comparison with Mesen2's or FamiStudio's audio; Thor
  measurements.

## C64 SID: GoatTracker 2 playroutine on a 6510 + reSID (`"c64-sid"`)

Design step 6: `chip_engine_ops("c64-sid")` plays GoatTracker songs lowered into the image layout below,
proven in the host test. The native side of SID step 2 is done (relocating live edits, off-audio-thread
costs, audition, export numbers for Kotlin in `SidPlayerImage.kt`); the Kotlin system, lowering and UI
are described in `docs/BACKENDS.md`.

### What runs

- **Player image** (`generated/sid_player_image.h`, 1249 bytes at `$1000`): GoatTracker 2.77's
  `player.s`, unmodified, assembled by the assembler GoatTracker itself embeds (Magnus Lind's, from
  Exomizer; `tools/audio/sid/gtasm.c` is a command-line front end for it) with the defines GoatTracker's
  packer (`src/greloc.c`) writes for **"Disable optimization"**: every feature of the playroutine is
  present, so any song plays without re-assembling the player. Fixed choices: unbuffered SID writes, no
  sound-effect or volume entry points, no ghost registers, no author info, one subtune, three channels,
  the full 96-note frequency table (C-0..B-7), zero page `$FC/$FD`, and GoatTracker's defaults for pulse
  skipping on sequencer ticks and realtime-command skipping on tick 0 (the editor's `PO`/`RO` options,
  on by default). The song data labels (`mt_insad`, `mt_wavetbl`, `mt_patttbllo`, …) are equates at the
  fixed addresses of the layout instead of data appended after the code.
- **Parameters**: five song-dependent defines are immediate operands. The prepare script assembles the
  player with two value sets and requires exactly five differing bytes, one per define; the machine writes
  each image's values there (`SID_PATCH_*`): `FIRSTNOHRINSTR`, `FIRSTLEGATOINSTR` (the instrument order,
  below), `ADPARAM`/`SRPARAM` (the hard-restart attack/decay and sustain/release, GoatTracker's `-A`,
  default `$0F00`; values from `$F000`, which select GoatTracker's alternative player, are not supported)
  and `DEFAULTTEMPO`.
- **CPU**: `m6502.h` with decimal mode (a 6510; the player does not use it), 64 KiB RAM, the SID at
  `$D400-$D7FF` (registers mirrored every 32 bytes). The machine calls the player like a PSID player:
  `init` (`$1000`, A = 0) once, then `play` (`$1003`) once per tick, each call returning to
  `SID_PLAYER_IDLE` (`$0FF0`). Faults (`CHIP_ERR_FAULT`, then silent until the next start): an undocumented,
  JAM or BRK opcode fetch, a fetch of `SID_PLAYER_TRAP` (the vectors), or a call longer than a tick. The
  player's worst call in the test song is 1046 cycles (5% of a PAL frame).
- **Tick** = one `play` call = one GoatTracker tick: 19656 cycles (63 × 312, PAL, 50.1245 Hz at 985248 Hz)
  at speed 1. **Multispeed** (header byte 14 = N, 2..16): `$4CC7 / N + 1` cycles, the CIA timer period
  gt2reloc's multispeed PSIDs program (x2: 9828 cycles, ~479 frames at 48 kHz); tempos and gate timers
  are then in N-times-faster ticks, as in GoatTracker. Its 25 Hz mode (`-S0`) is rejected (a tick of 39311
  cycles would exceed `CHIP_MAX_TICK_FRAMES` at 96 kHz); NTSC is not offered (GoatTracker's default is PAL).
- **SID**: reSID 1.0 through `sid/sid_resid.cpp` (C interface; the hosts and the machine stay C). Each
  play call's writes are timestamped to their CPU cycle; the SID is clocked to each write, written, then
  clocked to the end of the tick, cycle by cycle, and resampled with reSID's `SAMPLE_RESAMPLE`
  (Kaiser-windowed sinc FIR, passband 20 kHz at 44.1/48 kHz, 16-bit stop band). Cost on the dev PC
  (48 kHz): 593 µs per tick for the 8580 and 735 µs for the 6581 (34× / 27× realtime, 3.0 / 3.7 % of a
  core); `SAMPLE_INTERPOLATE` saves only 18 % (483 / 614 µs) because reSID's per-cycle chip emulation, not
  the FIR, dominates, so the band-limited method is kept. Device numbers come from the bench (below).
  Creating the first SID machine in a process builds reSID's filter tables (72 ms on the dev PC; 120-200 ms
  measured on the Konkr); later machines take 0.6 ms (their FIR table). Hosts create machines only when an
  engine opens, on the control thread, never in the audio callback (below, "Hosts"). A machine is 252 KB
  plus reSID's (~90 KB: FIR table, ring).
- **Power-on**: reSID's `reset()` is the chip's RES pin (oscillator phases kept); `sid_resid_reset` also
  restores the power-up oscillator value `$555555`, the 8580 write pipeline and the resampler, so every
  start renders bit-identical samples (checked). Not changed in reSID's sources.
- **Chip model** per song (header byte 13). Recommendation for new Thor Tracker songs: **8580**. Its
  filter is close to linear and consistent between chips, so a cutoff value sounds the same on any 8580
  and in reSID, whereas 6581 filters vary by up to an octave between chips and reSID models one; it has
  no volume-write click and cleaner combined waveforms; current replacement chips and FPGA cores default
  to it. The 6581 (GoatTracker's own default and most classic music) stays selectable per song.
- **Output**: reSID's 16-bit output (after its model of the C64 output stage: ~16 Hz high-pass, 16 kHz
  low-pass, so DC-free) ÷ 32768, L = R, with reSID's default voice mask (no external input). The test
  song peaks at 0.39 (8580) / 0.46 (6581), RMS 0.094 / 0.100 (the NES test song: 0.67 / 0.15).

### C64 memory map

| Range | Use |
|---|---|
| `$00FC-$00FD` | player zero page (`mt_temp1/2`) |
| `$0100-$01FF` | stack (host calls start at S = `$FB` with the idle return address pushed) |
| `$0E00-$0E1F` | audition orderlists and patterns (spare machines only) |
| `$0FF0` / `$0FF3` | `SID_PLAYER_IDLE` (host-call return) / `SID_PLAYER_TRAP` (vector target) |
| `$1000-$14E0` | player code and its variables (self-modifying, as on a C64) |
| `$1800-$CFFF` | the image's region |
| `$D400-$D7FF` | SID, writes timestamped to their cycle; other `$Dxxx` reads 0 |
| `$FFFA-$FFFF` | vectors → `SID_PLAYER_TRAP` |

### Image layout (what the Kotlin lowering produces)

`image_size` = 47200 bytes: a 96-byte header and the region, C64 RAM `$1800-$CFFF` (address *a* at image
offset 96 + *a* − `$1800`). Numbers are generated into `sid_player_image.h` (`SID_*`). The native loader
is `sid/sid_image.c` (`sm_load_image`), which rejects anything off-contract with a reason
(`SidImageInfo.problem`; the host test prints them): mirror its checks in Kotlin validation. The
reference lowering is `tools/audio/sid/gt_song.py` (`lower()`), proven to be GoatTracker's own packing (see
Verification). Header, little-endian:

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | `"TTSD"` |
| 4 | 4 | player id = first 4 bytes of the player image sha256 (`SID_PLAYER_ID`, currently `0x4CAFC832`) |
| 8 | 2 | region base `$1800` |
| 10 | 2 | data end: address after the last song byte (`$2500` < end ≤ `$D000`; patterns and orderlists must lie below it; export stops there) |
| 12 | 1 | layout: 1 = Thor Tracker (below) |
| 13 | 1 | chip model: 0 = MOS 6581, 1 = MOS 8580 |
| 14 | 1 | speed multiplier 1..16 (player calls per frame) |
| 15 | 1 | `DEFAULTTEMPO`: ticks per row − 1 at song start, 2..127 (gt2reloc: 6 × multiplier − 1, or instrument 63's attack/decay − 1 when that instrument has A/D ≥ 2 and no wavetable) |
| 16 | 1 | `FIRSTNOHRINSTR` |
| 17 | 1 | `FIRSTLEGATOINSTR` |
| 18 | 1 | hard restart attack/decay (`ADPARAM`, GoatTracker default `$0F`) |
| 19 | 1 | hard restart sustain/release (`SRPARAM`, default `$00`) |
| 20 | 1 | instrument count *I*, 1..63 |
| 21 | 11 | 0 |
| 32 | 64 | slot map: byte *s* = player instrument number of the editor's instrument slot *s* (1..63), 0 = no instrument; byte 32 (slot 0) is 0; numbers 1..*I*, each at most once |
| 96 | 47104 | region |

Region, fixed tables (the player's labels; *n* = 1-based index, `label + n − 1`):

| Address | Label | Content |
|---|---|---|
| `$1800` / `$1860` | `mt_freqtbllo` / `hi` | 96 frequency-register values, note 0 (C-0) .. 95 (B-7). GoatTracker's default table (`gt_song.FREQ_LO/HI`): A-4 = note 57 = `$1D46` = 440.09 Hz, within 1.5 cents of 12-TET from C-1 up. A song's tuning is realised here (GoatTracker's `-G` base pitch and Scala tunings compute this table); the SID has no per-note fine pitch |
| `$18C0` / `$18C3` | `mt_songtbllo` / `hi` | the three orderlist addresses |
| `$1900` / `$19D0` | `mt_patttbllo` / `hi` | pattern addresses, patterns 0..207 |
| `$1AC0` + 64 *k* | `mt_insad`, `mt_inssr`, `mt_inswaveptr`, `mt_inspulseptr`, `mt_insfiltptr`, `mt_insvibparam`, `mt_insvibdelay`, `mt_insgatetimer`, `mt_insfirstwave` (*k* = 0..8) | instrument columns, instrument *n* at offset *n* − 1 |
| `$1D00` / `$1E00` | `mt_wavetbl` / `mt_notetbl` | wavetable left / right, rows 1..255 |
| `$1F00` / `$2000` | `mt_pulsetimetbl` / `mt_pulsespdtbl` | pulsetable |
| `$2100` / `$2200` | `mt_filttimetbl` / `mt_filtspdtbl` | filtertable |
| `$2301` / `$2401` | `mt_speedlefttbl` / `mt_speedrighttbl` | speedtable; `$2300` and `$2400` stay 0 (index 0 = no speed) |
| `$2500`.. | | orderlists, then patterns, up to the data end |

**What the lowering writes** (GoatTracker's packed format as `greloc.c` writes it without
optimizations; all table rows and all instruments are kept, so row and instrument numbers need no
remapping beyond the instrument order):

- **Instrument order**: player numbers put hard-restart instruments first, then no-hard-restart ones
  (gate timer bit 7), then legato ones (bit 6), each group by slot. `FIRSTNOHRINSTR` = normal + 1,
  `FIRSTLEGATOINSTR` = normal + no-HR + 1 (gt2reloc adds one more in multispeed, where it counts an extra
  no-HR instrument: the first legato instrument then plays without legato; mirror it for exports that must
  equal GoatTracker's, or not, since the operand is ours to set). Patterns use player numbers; the slot map
  records the order. Columns: A/D, S/R, wave/pulse/filter pointers, vibrato speedtable index and
  vibrato delay − 1 (both 0 when the delay is 0), gate timer & `$3F`, first-frame wave.
- **Wavetable** left: waveforms `$10-$DF` + `$10`; inaudible waveforms `$E0-$EF` → `$10-$1F`; delays
  `$01-$0F`, commands `$F0-$FE`, jump `$FF` and `$00` unchanged. Right: notes XOR `$80` (relative
  `$00-$7F` → `$80-$FF`, keep frequency `$80` → `$00`, absolute `$81-$DF` → `$01-$5F`); command
  parameters and jump targets unchanged (they are row numbers).
- **Pulsetable** unchanged. **Filtertable** left: set-filter steps `$81-$FE` → `((x & $70) >> 1) | $80`;
  everything else unchanged. **Speedtable** unchanged.
- **Orderlists** (Thor layout): per channel *n* pattern numbers (0..207), `$FF`, the restart order; *n*
  (1..254) and the restart are the same on all three channels. No repeat (`$D0-$DF`) or transpose
  (`$E0-$FE`) commands.
- **Patterns** (GoatTracker's packed rows, `packpattern`): per row an optional instrument byte `$01-$3F`
  (only when it differs from the previous row's instrument in the pattern; always on row 0), then, when
  the command or its parameter differs from the previous row's (always on row 0), `$40|c` (a note
  follows) or `$50|c` (the row is a rest) with a parameter byte unless *c* = 0, then the note byte:
  `$60-$BC` notes C-0..G#7 (absolute note *n* = byte − `$60`), `$BD` rest, `$BE` key off, `$BF` key on;
  runs of 2..64 bare rests after row 0 pack into one byte `$100 − count` (`$C0-$FE`). A pattern ends with
  `$00` after its last row (or at exactly 256 bytes); at most 256 bytes, 1..128 rows, equal rows on the
  three channels of an order. Command parameters are GoatTracker's with two packer conversions: `Fxx` with
  `xx & $7F` ≥ 3 is stored minus one, and `Dxx` with `xx` ≥ `$10` (timing marks) becomes command 0.
- **Should** (not checked): each order's patterns re-send the instrument on row 0 (an order is also
  entered by the loop and by the seek fallback); no channel tempo (`F8x`-`FFx`), so all channels keep
  one row clock; gate timers below the tempo (GoatTracker's rule; otherwise rows are not fetched).

`sm_load_image` derives (`SidImageInfo`): order count and restart, rows per order, the pattern of each
channel and order, and the global tempo (and funktempo speedtable entry) each order starts with in
straight play.

### Position, rows and seek

- GoatTracker reads a row's pattern data `gate timer` ticks before the row starts (hard restart and gate
  off happen then) and starts it when the channel's counter counts from 1 to 0 (tick 0). The machine reads
  channel 1's counter (`mt_chncounter`) before each tick: `position()` is the row the next tick
  processes, from player state, exact through `Fxx`, funktempo and multispeed; orders and rows per order
  come from the image (rows wrap to the restart order).
- Start-up: the first play call after init resets the player, the next tick 0 only loads the first
  patterns, and row 0 starts `tempo + 1` ticks later (7 ticks at the default tempo, with row 0 fetched and
  hard-restarted on the way). `start(0, 0)` runs these silently, so the next tick is row 0's first.
- `start(order, row, max_ticks)`: power-on, init, then the real player runs from the song start until the
  next tick starts (order, row) (`CHIP_SEEK_FROM_START`). The SID is **deferred** while seeking: the
  writes of the last 150 ticks (× the multiplier; ~3 s) wait in a ring and are played clocked when the
  target is reached, the last of them with samples so the resampler's history is straight play's; older
  ticks reach the SID as bare writes one cycle apart. The player state is exact (register writes after a
  seek equal straight play's); the SID's state from before that window (oscillator phases, envelopes of
  notes older than ~3 s) is approximate: 2 s after a seek the loudness envelope is within 0.7 % (target
  inside the window) and 0.3 % (beyond it) of straight play. Clocked-silent ticks cost 39 µs on the dev PC
  and bare ticks 6.7 µs, so a seek is ~6 ms plus 6.7 µs per tick (12000 ticks, ~4 min: ~86 ms). Suggested
  `seekTicks` 12000, as for the Game Boy and NES; check the bench on the Thor.
- Fallback (`CHIP_SEEK_FROM_ORDER`, beyond `max_ticks`): power-on, the reset call, then every orderlist
  is pointed at the order and every channel tempo set to the order's start tempo (and the funktempo pair
  from the speedtable), so the next tick 0 loads that order's patterns; its earlier rows replay silently.
  Lost, as on the Game Boy and NES: instruments, table programs, filter and volume from earlier orders,
  and the funktempo phase.
- `bulk` clocking (`SID::clock(delta)`) is not sample-identical to per-cycle clocking (reSID integrates its
  filters in multi-cycle steps and the noise generator does not repeat the per-cycle sequence), so seeking
  is compared by loudness, not samples; `sid_resid_clock` keeps the resampler's phase on straight play's
  sample grid.

### Live edits (`apply_image`, relocating, realtime-safe, 4 µs mean measured)

At a tick boundary (`sm_apply_image`; no allocation), any valid image may replace the playing one — data
may move freely, patterns and instruments may be renumbered:

1. **Position**: the current order/row is kept; if it no longer exists it is clamped to the last row of
   the last (or the current) order, so the next row is the restart order / the next order.
2. **Sequencer**, per channel, re-derived from the new data at the playhead (not from the old pointers):
   whether the channel has already fetched the next row (its counter has passed its gate timer:
   `mt_chncounter` ≠ 0 and ≤ `mt_chngatetimer`) decides the last row it has fetched; from that row
   (`sm_fetch_state`) come `mt_chnpattnum`, `mt_chnsongptr` (the order's entry + 1; the sequencer advances
   at tick 0 of a pattern's last row, so after that row has started the next order's pattern is loaded,
   fresh), `mt_chnpattptr` and `mt_chnpackedrest` (inside a packed rest: its byte + rows counted), the
   persisting instrument and command of the pattern so far (`mt_chninstr` — otherwise the playing
   instrument's new number by slot —, `mt_chnnewfx`/`mt_chnnewparam`) and, for a fetched row not yet
   started, its note (`mt_chnnewnote`) and key off/on (`mt_chngate`). Edited cells are heard from the next
   row the channel fetches, or from the next row if the edit hit a row fetched but not yet started (its
   hard restart has happened; the new note replaces the old one).
3. **Table programs**: a wave/pulse/filter pointer that lies in an instrument's program in the old image
   (its start row up to the program's `$FF` jump row; the channel's own instrument first, else the
   innermost) moves with that instrument slot's program in the new image, keeping its offset (clamped
   to the program's last row before its jump; 0 if the instrument has no program any more). Pointers
   into rows only commands reach (`8xx`/`9xx`/`Axx`, wavetable commands `F8`-`FA`) are kept: the lowering
   keeps program rows stable (it moves a program only when it no longer fits), so this is the identity
   in the common case and skipped when the instrument columns, slot map and table left columns are
   unchanged. Speedtable rows need no mapping (`mt_chnparam` reloads at every row start).
4. **Tempo**: straight play's global tempo / funktempo pair at the playhead (`sm_tempo_at`: the order's
   start tempo, then the `F`/`E` commands of the rows started; tick-0 commands persist and re-run every
   row) is set if the edit changed it (an earlier `Fxx`/`Exx`, the default tempo, the funktempo
   speedtable row) or the playhead was clamped; otherwise the player keeps its own (e.g. one inherited
   through the loop). Funktempo keeps its phase.
5. **Header**: the five parameters are rewritten; chip model (`set_chip_model`, voices keep running; the
   filter integrators restart) and speed multiplier (tick length) follow the new header.

Before the player has loaded its first patterns (only inside `start`), the data is copied and the
orderlist positions clamped. The host test applies a fully re-laid-out image (`sid_gt_moved`) before
every one of 1500 ticks and gets py65's reference writes (dev PC: 4 µs mean per apply; worst 9-20 µs in
bench runs, one 59 µs outlier in a host-test run on a loaded desktop).

### Audition

`audition(channel 0..2, note, slot, fine)`: the hosts pass the cell's slot (1..63); the image's slot map
gives the player number (slot without instrument: `CHIP_ERR_IMAGE`). The spare machine is reset with the
image, its song table and pattern table entries 0/1 are pointed at scratch data in RAM (`$0E00`): the
auditioned channel plays a 64-row pattern `instrument, $40, note, $C1` (one note, 63 packed rests) and
then loops a 64-row rest pattern; the other channels loop the rest pattern. The start-up ticks (reset,
pattern load, the note's fetch and hard restart) run silently, so the first tick is the note's first, as
when a song row plays it, through the image's own instruments and tables; the note is held (gate on)
until released. `note` is the absolute note = GoatTracker note 0..92 (C-0..G#7, the pattern range); `fine`
must be 0 (tunings live in the song's frequency table). `release()`: 0 before any tick (cut); 0 as well
when the voice is already silent (envelope counter 0, e.g. a sustain-0 note that has
decayed: the machine ends); otherwise the player's key-off state (`mt_chngate = $FE`, exactly what a key-off
row sets) from the next tick, returning 1; `tick` returns 0 once the voice's envelope counter is 0 (Tone,
release 0: 1 tick; Lead, release 9 from sustain A: 35 ticks).

### Ops behaviour

| Op | Behaviour |
|---|---|
| `load_image` | copy, then `sm_load_image`; `image_storage` = `sizeof(SidImage)` (50288: bytes + info) |
| `start(order, row)` | above; `seek_mode` from start or order |
| `apply_image` | above (relocating); ignored on a faulted or auditioning machine |
| `audition`, `release` | above |
| `mute(channel 0-2)` | reSID's voice mask: the voice leaves the filter and the mix, keeps running (its sync and ring modulation still act; unmuting resumes mid-note); `start`/`audition` clear mutes |
| `position` | above |
| `tick` | one player call: 957-958 frames at 48 kHz (speed 1); 0 after a released audition ended |

### Preparing the player (once, when the pin, configuration or layout changes)

```
/tmp/sidref/bin/python tools/audio/prepare_sid_player.py [--goattracker DIR]
```

`tools/audio/fetch_goattracker.sh` downloads the pinned GoatTracker 2.77 release into
`${THOR_TRACKER_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/thor-tracker}/goattracker-2.77/` (sha256 checked,
as `fetch_famistudio.sh` does), builds its packer `gt2reloc` (it links GoatTracker's SDL 1.2 front-end
library, so it builds and runs in the `ubuntu` distrobox when the host has no `sdl-config`: install
`build-essential libsdl1.2-compat-dev` there) and `gtasm`, and prints their directory. The prepare script
checks the vendored `player.s`, assembles the player and finds the parameter operands and player symbols
(words appended after the code), checks the low-byte jump tables stay in their pages and the player ends
below `$1800`, writes `tools/audio/sid/gt_test.sng` from `gt_test_song.py`, packs it with
`gt2reloc gt_test.sng gt_test.sid -I0 -J1` (gt2reloc's `-I` case falls through into `-J`, so `-I0` alone
would also switch full buffering on; it keeps file names in 60-byte buffers, so it runs from the song's
directory), lowers the song, proves the lowering is the packer's data, records py65's reference
(below), builds the live-edit fixtures (`sid_gt_moved`: the same song laid out anew by `gt_song.lower(...,
relayout=True)` with every instrument program moved 100 rows by `move_programs` — py65 must record the
reference writes for it too —; `sid_gt_edited`, `sid_gt_tempo`, `sid_gt_short`) and writes
`generated/sid_player_image.h`, `core/.../backend/SidPlayerImage.kt` and `app/src/test/cpp/sid_gt_song.h`.
Committed `gt_test.sng`/`gt_test.sid` must be reproduced byte for byte. Needs py65 (`python3 -m venv
/tmp/sidref && /tmp/sidref/bin/pip install numpy py65`).

### Verification

- The test song (`tools/audio/sid/gt_test_song.py`, written for these tests as a GoatTracker song in the
  Thor shape: 4 orders of 16/32/16/24 rows looping to order 1): hard-restart, no-HR and legato
  instruments (first wave `$00`/`$FF`), instrument vibrato with normal and note-independent speeds,
  commands 1-F, key off/on, wavetable delays, keep-frequency, relative/absolute notes, inaudible
  waveforms and wavetable commands, pulse and filter programs with loops, packed rests, a pattern used
  twice, and a held A-4 triangle. Every table row is reached and no table part repeats, so GoatTracker's
  packer keeps them all.
- **Lowering = GoatTracker's packer**: the player source plus `gt_song.lower`'s data written with
  greloc's labels and order, assembled by `gtasm`, is **byte-identical to gt2reloc's PSID payload**
  (1839 bytes: 1249 of player, 590 of song data); the Thor image holds the same bytes at the layout's addresses.
- **Independent CPU reference**: py65 runs gt2reloc's PSID (init, then one play call per frame) for 1500
  frames (30 s) and records 20390 SID writes and the frames where its channel-1 counter starts rows; it
  also runs the Thor image on the player image (parameters patched) and gets the same writes. Both are in
  the fixture.
- `tools/audio/run_native_host_test.sh [dir]` builds the engines (C with `$CC`, reSID with `$CXX`, linked by
  `$CXX`) and runs `app/src/test/cpp/sid_machine_test.c` — 2026-11-08: **151 checks, 0 failures** (Game Boy
  145, NES 336 unchanged): player image and frequency table (A-4 440.09 Hz, ≤ 1.51 cents from 12-TET);
  derived orders/rows/start tempos; 22 single-byte corruptions each rejected with its reason; the Thor
  image on this machine **writes the same SID registers as py65's reference in all 1500 frames** and its
  287 row starts fall on the reference player's frames with the right (order, row); gt2reloc's own PSID
  on this machine also equals py65 in all frames; six seek targets land exactly (ticks = the reference row
  start) with writes in step for 200 ticks; the order fallback lands and stays in row step; loudness after
  seeks (above); audition of A-4 on the Tone instrument **440.07 Hz measured** (register 440.09 Hz),
  sustained 2 s, other voices silent, release tails, a decayed (sustain 0) note's release cuts, argument
  limits; the held A-4 in the song with voices 2-3 muted 440.00 Hz; all voices muted < 0.002 after 100 ms;
  muting leaves writes unchanged; BRK, JAM and runaway faults; x2 multispeed ticks of 9828 cycles;
  same-image live apply in step; **live edits**: the re-laid-out image and the original applied
  alternately before every one of 1500 ticks from power-on **write py65's reference** with its 287 row
  starts (576 channel-ticks inside moved wave programs), and stay in step with straight play for 600
  ticks after seeks 0/5, 1/17, 2/3 and the order fallback 3/6; the note edit applied at each of the 13
  ticks before its row starts (also between its fetch and start) and during order 1 equals straight play
  of the edited song in all 1500 ticks; a tempo edit behind the playhead gives rows of 9 ticks and its
  undo 6; a funktempo speedtable edit is taken (6/4 → 5/3) in funktempo; the song cut to two orders under
  order 3 clamps and then plays order 1 in order; 6581 applied live writes the same registers, x2 applied
  live halves the tick; the ops table as the hosts drive it; 30 s on each model non-silent, no samples at
  full scale, bit-identical on a second start. Evidence: `sid_gt_8580.wav`, `sid_gt_6581.wav` (30 s),
  `sid_tone_8580.wav`, `sid_trace.txt` (the machine's writes per tick), `sid_gt.sid`.
- `tools/audio/sid_reference_check.py <dir>` (developer evidence: numpy, py65; sidplayfp 2.6, e.g. in the
  `ubuntu` distrobox) — 2026-11-07, PASS: py65 running `sid_gt.sid` equals `sid_trace.txt` in 1500/1500
  frames; against **sidplayfp's reSID** (its own C64 environment, aligned by envelope): 8580 envelope
  correlation 0.985, log-spectrum 0.863, same pitch class in 95 % of windows; 6581 0.884 / 0.860 / 96 %
  (libsidplayfp configures the 6581 filter differently); against reSIDfp (another filter model, reported
  only) 0.842 / 0.936 / 96 % and 0.968 / 0.850 / 95 %.
- **Bench**: `tools/audio/build_sid_bench.sh [dir]` builds `tools/audio/sid_bench.c` for the host and with
  the NDK as static-libc++ arm64 executables (`-O2` as the app, and `-O3`): `adb push` and run
  `sid_bench-arm64 [seconds] [rate]`; it prints machine creation (first / later), tick cost (mean, p99,
  p99.9, worst) for both models and sampling methods, the relocating `apply_image`, audition setup and seek
  costs. Built 2026-11-08 into `/tmp/sid_bench2/` (`sid_bench-host`, `sid_bench-arm64`, `sid_bench-arm64-O3`).
- Not done: listening on hardware, device measurements of step 2 (the arm64 bench is built, not run),
  a comparison with a real C64 capture.

### Hosts: threads and costs

What runs where (both hosts drive the machine through `chip_engine.h`), dev PC numbers (`sid_bench-host`,
48 kHz, 2026-11-08) and the Konkr-class estimate (× 4; measured there before: seek × 3.3, creation 120-200
ms):

| Work | Thread | Dev PC | ≈ Konkr |
|---|---|---|---|
| `tick` (8580 / 6581, resample) | audio callback | 590-610 / 730-755 µs mean, p99.9 0.7-1.1 ms | 2.4 / 3.0 ms mean, ~4.5 ms p99.9 |
| `apply_image` (relocating) | audio callback, tick boundary | 4 µs mean, worst 9-20 µs (one 59 µs outlier) | ≤ 0.25 ms |
| machine creation | control (engine open) | 72 ms first (reSID tables), 0.6 ms later | 120-200 ms first |
| `audition` setup | control, spare machine | 0.35 ms | ~1.4 ms |
| `start` (seek, 12000 ticks ≈ 4 min) | control, spare machine | ~6 ms + 6.9 µs per tick ≈ 88 ms | ~280 ms |

- **Android** (`audio_engine.cpp`): `Engine::create` makes all six machines (three song, three audition
  slots) on ChipAudio's control thread when a song of the system first needs the engine; `play` and
  `audition` run `start`/`audition` on the back machine of a triple buffer and publish it; the callback
  swaps at its next start and keeps playing the previous machine meanwhile, so a seek delays the new
  position by its cost but never stalls audio. The callback only ticks, mutes and applies images.
- **Deck** (`deck_audio.c`): `da_open` creates four machines (song and audition, each with a spare) on
  the main thread; `da_play`/`da_audition` run `start`/`audition` on the spare outside the SDL stream lock
  and swap pointers under it (a tick boundary: the callback runs whole ticks under the lock); live edits
  apply under the lock. Seeks no longer hold the lock.
- The audio-thread stall per tick (worst tick + worst apply) is ~1.0-1.4 ms on the dev PC (≈ 4-5.6 ms
  Konkr-class at the single worst tick of 30 s on a loaded desktop; the p99.9 estimate is ~4.5 ms; one
  20 ms tick is rendered per ~5 callbacks).
- **Kotlin**: `SidSystem.engine` (id `"c64-sid"`, `imageSize` 47200, `seekTicks` 12000, `note(n)` = n,
  `fine` → 0). Exports use the generated `SidPlayerImage.kt` (player bytes, the `PATCH_` operand
  addresses, layout labels, header offsets, GoatTracker's frequency table, PSID constants): `.sid` = a
  PSID v2 as gt2reloc writes it (`PSID_HEADER_SIZE` header: load 0, init `$1000`, play `$1003`, 1 song,
  start 1, speed word 0; flags PAL + model; for multispeed the 10-byte CIA speed code with `$4CC7` / N at
  `$0FF6`, load/init there and speed word all ones), then the load address and data; `.prg` = `$1000`
  little-endian + data; data = player with the five header parameters written, zeros to `$1800`, the
  region up to the header's data end.

## OPL3: Thor Tracker's own player on a YMF262 (`"opl3-fm"`)

Design step 8, native spike (branch `opl3-spike`): `chip_engine_ops("opl3-fm")` plays songs lowered into the
image layout below, proven in the host test against an independent reference implementation. The OPL3 is
music-only: no console ships it with a sound driver, so the system plays through **our own player**
(`opl3/opl3_player.c`, pure integer C without a chip), whose behaviour is the specification in this section;
`tools/audio/opl3/opl3_ref.py` implements the same specification separately in Python and the host test
requires identical register writes in every tick. Songs export as VGM (the player's register log). This section
is the contract of the Kotlin system and lowering (`core` `system/opl3`, `backend/Opl3Lowering.kt`); the numbers
they share with the engine are generated into `backend/Opl3PlayerImage.kt` (below, "Preparing").

### Core: Nuked-OPL3 1.8 (chosen) and ymfm

Both cores were built from upstream and fed the same register stream (18 voices busy: three 4-op pairs and
twelve 2-op voices, notes re-keyed every 15 ticks; `/tmp/opl3_bench/opl3_core_bench.cpp`, built by
`/tmp/opl3_bench/build_core_bench.sh` for the host and the NDK):

| | Nuked-OPL3 1.8 (`765ec962`) | ymfm (`81aec25c`, 2026-07-27) |
|---|---|---|
| Licence, language | LGPL-2.1-or-later, C99, one file pair | BSD-3-Clause, C++14 templates (`ymfm_opl.cpp` also pulls `ymfm_adpcm.cpp`/`ymfm_pcm.cpp`) |
| Accuracy | cycle-accurate model from the YMF262 die (decapsulated OPL ROMs, envelope and phase generator timing, 4-op routing); the reference used by Furnace and DOSBox-Staging | "audibly indistinguishable" by design, not cycle-exact; against Nuked on the same stream: sample correlation 0.85, loudness-envelope correlation 0.987, per-41 ms log-spectrum correlation 0.79 (min 0.67), output scale ×2.0 |
| Dev PC, generate one 60 Hz tick (828.6 samples) | **457-466 µs** mean (p99.9 540-740 µs), 36× realtime | 157-160 µs mean (p99.9 262-280 µs), 104× realtime |
| + blip_buf resample to 48 kHz | 9.4 µs | — |

**Nuked-OPL3 is the core**: it is the more accurate one, it is plain C (the engine needs no C++ runtime, unlike
reSID), and its cost is below the SID 8580's on the same machine (593 µs dev PC; ~1.8 ms per tick measured on
the Konkr). Measured on the devices (2026-11-08, Nuked alone per 60 Hz tick): Nova 308 µs, DMG 717 µs, Konkr
1216 µs mean (7 % of a 16.7 ms tick); ymfm 132 / 314 / 516 µs. ymfm would cost less than half if a device ever
needs it; it is also the candidate for the YM2612 later (its OPN2 shares the framework; Nuked-OPN2 is the accurate
alternative there). Engine costs on the devices: "Hosts: threads and costs".

### What runs

- **Chip**: Nuked-OPL3 at its native rate, 14318180 / 288 = 49715.9 Hz, `OPL3_Generate` (stereo: outputs A/B;
  C/D are unused), every register write applied directly (`OPL3_WriteReg`, unbuffered) at the tick boundary.
- **Tick** = 1/60 s / multispeed (header byte 5, N = 1..8): the player runs once, then the chip renders
  14318180 / (288 · 60 · N) samples (exact integer accumulation: 828/829 at N = 1). Ticks per row (speed) count
  these ticks, so multispeed makes rows shorter unless the speed grows with it, as in GoatTracker.
- **Key-off phase**: a batch of writes has two parts (see the player). The machine writes the key-offs, renders
  one native sample, then writes the rest and renders the remainder of the tick. The YMF262's envelope generator
  samples the key-on bit once per sample, so a key-off immediately followed by a key-on in the same instant would
  not retrigger the note (verified: without the gap, consecutive notes of a decaying drum never re-attacked).
- **Resampling**: band-limited steps (blip_buf 1.1.0, as the NES) from 49715.9 Hz to the host rate, with its
  ~15 Hz high-pass (bass shift 9), so the output is DC-free although half-wave OPL waveforms have DC. Output
  float = sample / 32768. The test song: rms 0.100, peak 0.71 (NES test song 0.15 / 0.67, SID 0.094 / 0.39).
- **Size**: `Opl3Image` 66352 bytes (image + `O3Info`), a machine ~100 KB (image copy, chip 21 KB, player 6.6 KB,
  two blip buffers). Creation 0.03 ms; no tables are built.

### Voices, tracker channels and the chord pool

The YMF262 has 18 two-operator voices (bank 0: 0-8, bank 1: 9-17); six pairs (0+3, 1+4, 2+5, 9+12, 10+13,
11+14) can each become one 4-op voice (register 0x104 bits 0-5). The system has **9 tracker channels**:

| Channel | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|---|---|---|---|---|---|---|---|---|---|
| Primary voice | 0 | 1 | 2 | 9 | 10 | 11 | 6 | 7 | 8 |
| 4-op secondary | 3 | 4 | 5 | 12 | 13 | 14 | — | — | — |

Why 9: it is the OPL2/AdLib channel count most FM music is written for and fits a handheld screen; channels 1-6
can play 4-op instruments (all six pairs are available), channels 7-9 are 2-op only (the lowering rejects a 4-op
instrument there; voices 6-8 are also the rhythm-mode voices, which this system does not use). The other voices
form the **chord pool**, in priority order: 15, 16, 17 (always), then 3, 4, 5, 12, 13, 14 — each only while its
pair's channel plays 2-op instruments. A 2-op song therefore has 9 spare voices (e.g. three 4-note chords or
four 3-note chords at once); a song using all six 4-op pairs keeps 3.

A channel's pair mode is the mode of the instrument of the last note it keyed: a key-on with a 4-op instrument
sets the pair's 0x104 bit (taking the secondary back from the pool: a chord voice on it is keyed off and
removed from its owner's chord, then it is stamped as just released), a key-on with a 2-op instrument clears it
(the secondary rejoins the pool, stamped now).

**Allocation** (deterministic): a chord voice is the pool voice with no owner and the oldest stamp (released
longest ago; ties by pool order); if every usable pool voice is owned, the owned one with the oldest stamp is
stolen (key-off written, removed from its owner's chord). The allocated voice gets the owner and a new stamp.
Stamps come from one counter incremented by every allocation, release and pair switch. **Release with the
root**: a key-off of the root (OFF, `Sxx`, the next key-on of that channel) keys off its chord voices in
allocation order, frees them and stamps each.

### Image layout (what the Kotlin lowering produces)

`image_size` = 65536 bytes, little-endian, every pattern reference an offset into the data region, so any
layout that passes `o3_load_image` (`opl3/opl3_image.c`) relocates. Constants: `opl3/opl3_image.h`
(`O3_*`); the reference builder is `tools/audio/opl3/opl3_song.py` (`build()`). The loader rejects anything
off-contract with a reason and the byte (`O3Info.problem`, `problem_offset`): mirror its checks in Kotlin
validation for user-facing messages.

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | `"TTO3"` |
| 4 | 1 | layout: 1 |
| 5 | 1 | multispeed N, 1..8 (player ticks per 60 Hz frame) |
| 6 | 1 | speed (ticks per row) at the song start, 1..255 |
| 7 | 1 | register 0xBD: bit 7 deep tremolo (4.8 dB instead of 1 dB), bit 6 deep vibrato (14 instead of 7 cents); other bits 0 (no rhythm mode) |
| 8 | 2 | order count, 1..256 |
| 10 | 2 | loop order (< count): where play continues after the last order |
| 12 | 1 | instrument count I = highest slot present, 0..64 |
| 13 | 1 | chord shape count S, 0..64 |
| 14 | 2 | data size D: bytes of pattern data used, ≤ 58304 |
| 16 | 48 | 0 |
| 64 | 256 | rows of order *o* at 64 + *o*: 1..255, 0 = 256 (Thor Tracker: ≤ 64); entries ≥ count 0 |
| 320 | 4608 | pattern of (order *o*, channel *c*) = u16 offset into the data region at 320 + 2 (9 *o* + *c*); entries ≥ count 0 |
| 4928 | 2048 | instrument slot *n* (1..64) at 4928 + 32 (*n* − 1) |
| 6976 | 256 | chord shape *k* (1..64) at 6976 + 4 (*k* − 1) |
| 7232 | 58304 | data region: patterns, `[0, D)` |

**Instrument** (32 bytes; an all-zero record = no instrument; records above I must be zero):

| Byte | Field |
|---|---|
| 0 | flags: bit 7 = defined (1), bit 0 = 4-op; other bits 0 |
| 1 | algorithm: 2-op 0 = FM (op 1 → op 2), 1 = additive (op 1 + op 2); 4-op 0..3 = CNT_A · 2 + CNT_B: 0 = 1→2→3→4, 1 = (1→2) + (3→4), 2 = 1 + (2→3→4), 3 = 1 + (2→3) + 4 |
| 2 | feedback of operator 1, 0..7 |
| 3 | 0 |
| 4 + 5 *k* | operator *k* + 1 (*k* = 0..3; 2-op: operators 3, 4 all zero): register 0x20 (AM, VIB, EGT = sustain, KSR, MULT), 0x40 (KSL, TL), 0x60 (AR, DR), 0x80 (SL, RR), 0xE0 (waveform 0..7) |
| 24 | drum sweep start, s8 semitones relative to the note, −96..96 |
| 25 | drum sweep end, s8 semitones, −96..96 |
| 26 | drum sweep speed, 1/16 semitone per tick toward the end; 0 = no sweep (start/end ignored) |
| 27 | 5 bytes 0 |

Carriers (the operators volume scales and brightness leaves alone): 2-op FM {2}, additive {1, 2}; 4-op algorithm
0 {4}, 1 {2, 4}, 2 {1, 4}, 3 {1, 3, 4} (from Nuked's routing; the FM groundwork notes had algorithms 1 and 2
swapped). Operators 1-2 are the primary voice's modulator/carrier slots, 3-4 the secondary's. Feedback acts on
operator 1 only (the chip ignores the secondary's feedback in 4-op mode, so it is written 0).

**Chord shape** (4 bytes): up to four s8 intervals in semitones, −48..48, non-zero ones first, then zeros;
shape *k* ≤ S has at least one. Negative intervals give inversions (e.g. −5, 4, 7).

**Patterns**: a pattern is decoded from its offset for exactly rows(*o*) rows (a run longer than the order's
remaining rows is cut there, so one pattern can serve orders of different lengths); every row read must lie
inside `[0, D)`. Row encoding:

| Bytes | Meaning |
|---|---|
| `0x80 \| k` | k + 1 empty rows (k 0..127) |
| `m` (0x01..0x13), then data | bit 0: a note byte follows; bit 1: an instrument byte follows; bits 2-4: effect count 0..4; bits 5-6 zero, m ≠ 0 |
| note | 0..95 = absolute note C-0..B-7 (Thor Tracker's absolute note: 0 = C-0, 57 = A-4 = 440 Hz), `0xFE` = OFF (key off) |
| instrument | slot 1..64, defined; on channels 7-9 2-op only |
| effects | per effect column in order: code (0..35 = `0`-`9`, `A`-`Z`), parameter |

**What the lowering writes** from a `Cell(note, instrument, effect, param)` with up to 4 effect columns: note
`EMPTY` → no note byte, `CUT` → `0xFE`, an absolute note 0..95 → itself; instrument 0 → no byte, else the slot;
effect columns in column order, blank columns (code 0, parameter 0) omitted. Empty rows pack into runs. The
lowering may share byte-identical patterns between orders and channels (the reference builder deduplicates).
Load-time parameter checks: `Bxx` < order count, `Cxx` ≤ 3F, `Exx` ≤ 7, `F00` rejected, `Ixx` ≤ 3F, `Kxx` ≤ S,
`Mxy` x 1..4, `Wxy` x 0..4 and y ≤ 7; other effects take any parameter.

**Should** (not checked): an instrument on the first note of every order (an order is also entered by the loop, the seek fallback and a
live edit that clamps); FM locks and chords only on rows with a note (otherwise ignored: warn).

`o3_load_image` derives (`O3Info`) order count, loop, multispeed, speed, rows per order and the speed each
order starts with in sequential straight play (header speed, then the last `Fxx` of each order: rows, then
channels, then columns; jumps ignored).

### Player

Units: **pitch** in 1/64 semitone, 0 = C-0, at most 8 · 768 − 1 (just below C-8); notes are pitch = note · 64.
Pitch to registers: octave = pitch / 768, f = pitch mod 768, F-number = `O3_FNUM[f]`, block = octave − 1 if
f < `O3_FNUM_SPLIT` (438) else octave; block −1 becomes block 0 with the F-number halved. The table is
`floor(x + 0.5)` of x = 2 · F_C · 2^(f/768), halved (and rounded) from the split on, where F_C = 440 · 2^(−57/12)
· 2^20 / (14318180 / 288) ≈ 344.88 (C-n's F-number at block n): A-4 = F-number 580 at block 4 = 439.99 Hz; every
note C-0..B-7 is within 2.6 cents of 12-TET A440 (the YMF262's 10-bit F-number resolution).
`generated/opl3_tables.h` holds the table and the vibrato sine `SINE[i] = floor(127 sin(2πi/64) + 0.5)`.

**Writes**: every write goes through a shadow of all 512 registers (reset state 0): a write equal to the shadow is
not emitted. A batch is the key-off phase — every write that turns a key-on bit (0xB0-0xB8 bit 5) from 1 to 0,
in the order made — followed by every other write in the order made (`O3Player.offs` = length of the first part).

**Reset** (start, audition): all state cleared; channels: instrument 0, volume 3F, pan both, no effects; pool
stamps 0; speed = header speed; position (0, 0, tick 0); writes `0x105 = 1` (OPL3 mode), 0xC0-0xC8 of both banks
= 0x30 (both outputs, feedback 0, FM), then 0xBD = header byte 7 (if not 0). Why the C0 writes: the YMF262 latches
a voice's output enables only when its C0 is written, and they come out of reset **on** (Nuked models this), so a
register value the player never wrote is not what the chip outputs. Without them a C0 that should be 0 (`800` pan
with feedback 0 and FM, or the machine's mute of such a voice) would equal the reset shadow, never be written, and
the voice would keep sounding — in the engine and in every VGM player.

**Tick** (positions are (order, row, tick-in-row); the host's position is the tick about to run):

1. Write 0xBD = header byte 7 (only emitted when it changed: a live edit).
2. Tick 0 of a row: read every channel's row at (order, row). Then, over channels 0..8 and their effect columns
   in order: `Fxx` sets the speed (so the last one wins and applies to this row); `Bxx`/`Dxx` record the next
   position: `B` only → (xx, 0); `D` only → (next order, xx), the next order being order + 1 or, past the last
   one, the loop order; both → (B's order, D's row). Then for each channel 0..8: its cut is cleared; if the row
   has `Gxx` with xx > 0 (the last `G` of the row) its row is delayed to tick xx; otherwise the row runs now.
   Other ticks: each channel whose delayed row is due (tick = xx) runs it, channels in order.
3. Each channel whose cut tick (`Sxx`) equals this tick is keyed off, channels in order.
4. Each channel 0..8 that has keyed any note since reset is updated (below).
5. tick + 1; when it reaches the speed: tick 0 and the next row — the recorded jump (row clamped to the
   target order's last row), else row + 1, after the last row of an order the next order, after the last order
   the loop order. **Loop speed**: whenever the next row is row 0 of the loop order (the wrap, a jump, the first
   pass) the speed becomes the speed sequential straight play starts that order with (`O3Info.start_speed`; an
   `Fxx` on that row still applies at its row start), so every pass of the loop runs at the first pass's tempo
   without an `Fxx` there — the player owns this, the lowering emits nothing for it. (A `Bxx`/`Dxx` that skipped
   an `Fxx` before the loop order's first arrival makes that derived speed apply on the first pass too.)

**Running a row** of a channel: if the row has a note 0..95, the arpeggio and fine pitch are cleared and the
row's chord is empty; an instrument byte sets the channel's instrument; the effect columns run in order
(table below); then the note: OFF keys the channel off; a note with portamento active (`3xx`, xx > 0) while
the channel is keyed only sets the glide target (no key-on: the row's locks and chord are not used); any other
note keys on.

**Key-on** (note *n*, channel *c*): if the channel's instrument is not a defined slot, nothing happens. Else
the channel is keyed off (below), the pair mode is set (channels 1-6; 0x104 written with all six bits), the
instrument's operators are copied and the row's locks applied in column order (`E`, `I`, `M`, `W`), then
written to the primary voice operator by operator (1..4): 0x20, 0x40 (carriers: TL + 63 − volume, at most 63),
0x60, 0x80, 0xE0; then 0xC0 = pan | feedback << 1 | CNT (2-op: algorithm; 4-op primary: algorithm >> 1,
secondary 0xC0 + 3 = pan | (algorithm & 1)). Pitch state: base = target = 64 *n*, slide offset 0, arpeggio
step 0, vibrato phase 0, sweep offset = 64 · start (when the sweep speed is not 0, else 0), sweep end 64 · end,
sweep step 4 · speed. Keyed. Then, for a 2-op instrument, one pool voice per chord interval (row's `H`/`K`)
is allocated and loaded with the same operators and connection (0x20..0xE0 per operator, 0xC0).

**Key-off**: if keyed, the primary's 0xB0 with bit 5 cleared; then its chord voices are released.

**Update** (per tick, after the row): pitch = clamp(base + 64 · arpeggio + fine + slide + vibrato + sweep,
0, 6143), with arpeggio = 0, x, y for step 0, 1, 2 of `0xy` and vibrato = floor(SINE[phase] · depth / 16)
(arithmetic right shift). Writes: the primary's carriers' 0x40 (TL + 63 − volume, ≤ 63), 0xA0 (F-number low)
and 0xB0 (key-on << 5 | block << 2 | F-number high); then per chord voice in allocation order its carriers'
0x40, 0xA0 and 0xB0 (always keyed) for pitch + 64 · interval (clamped). Then the effects advance: arpeggio step
(mod 3) if an arpeggio is set; slide offset ± speed (clamped to ±6144) for `1xx`/`2xx`; base toward target by the
speed (no overshoot) for `3xx`; vibrato phase + speed (mod 64) when speed and depth are set; sweep offset toward
the end by its step (no overshoot); volume · 8 + x − y (clamped 0..504) for `Axy`. A released note keeps
updating (its release tail follows slides and vibrato).

### Effects (what Kotlin's effect specs declare for `"opl3-fm"`)

Names are one character, as `docs/BACKENDS.md` "Effect columns" requires; up to 4 columns per channel. "Note"
= applies to the note keyed on this row only (parameter lock); "now" = from this row on, persisting.

| Effect | Parameter | Meaning |
|---|---|---|
| `0xy` arpeggio | x, y semitones | note: steps note, +x, +y every tick; the next note without `0xy` plays plain (also sets the step to 0; on a row without a note it changes the sounding note's arpeggio) |
| `1xx` slide up | 1/64 semitones per tick | now: the pitch rises until `100`/`200`/`300`; a new note restarts the offset at 0 but keeps sliding |
| `2xx` slide down | 1/64 semitones per tick | now: as `1xx`, down |
| `3xx` portamento | 1/64 semitones per tick | now: while active a note on a keyed channel glides there without key-on; `300` (or `1xx`/`2xx`) ends it |
| `4xy` vibrato | x speed 0-F (64-step cycle), y depth 0-F | now: offset floor(SINE · y / 16) (depth F: ±1.86 semitones); `4x0` off; the phase restarts with each note |
| `8xy` pan | x ≠ 0 left, y ≠ 0 right | now: OPL3 output A (left) / B (right); `800` silences; written at once to the note and its chord voices |
| `Axy` volume slide | x up, y down, 1/8 step per tick | now: until `A00` (`A01`: 63 steps in 8.4 s; `A0F` in 0.56 s) |
| `Bxx` jump | order | after this row play order xx row 0 (with `Dxx`: its row) |
| `Cxx` volume | 00-3F | now: channel volume (start 3F), 0.75 dB per step on the carriers |
| `Dxx` break | row | after this row play row xx (clamped) of the next order (`Bxx`'s with both) |
| `Exx` feedback | 0-7 | note: operator 1 feedback |
| `Fxx` speed | 01-FF ticks per row | now, global, this row included; the last of the row wins |
| `Gxx` note delay | ticks | the channel's row (note, instrument, all its effects except `B`/`D`/`F`) runs at tick xx; never if xx ≥ the speed |
| `Hxy` chord | x, y semitones | note: also sounds +x and +y on pool voices (zeros skipped); 2-op instruments only; released with the root |
| `Ixx` brightness | 00-3F | note: TL of every modulator = 3F − xx (KSL kept): 3F = brightest; no modulators (additive) = no effect |
| `Kxx` chord shape | shape 01..S | note: as `Hxy` with the image's shape (up to 4 intervals, inversions) |
| `Mxy` ratio | operator x 1-4, MULT y 0-F | note: frequency multiplier (0 = ×½); an operator the instrument lacks is ignored |
| `Pxx` fine pitch | 80 = none | xx − 80 in 1/64 semitones (±2 semitones) until the next note |
| `Sxx` note off | tick | key off at tick xx of this row (release phase), like an OFF cell at that tick |
| `Wxy` waveform | operator x 1-4 (0 = all), wave y 0-7 | note: operator waveform |

FM locks (`E`, `I`, `M`, `W`) apply at key-on only: changing a TL, multiplier or waveform of a sounding
note produces register-step clicks on the chip. Volume (`C`, `A`) and pan change sounding notes, as on any
tracker. The chord effect is declared only by systems with voices to spare (here); drums are ordinary
instruments with the sweep, the note giving the pitch (no rhythm mode).

### Position, seek and live edits

- `position()` is the player's (order, row, tick) — exact through `Fxx`, `Gxx`, jumps and multispeed.
- `start(order, row, max_ticks)`: the chip is reset and the **player runs** from the song start until the next
  tick starts (order, row) (`CHIP_SEEK_FROM_START`), first alone (no chip clocking: 0.2 µs per tick on the dev
  PC) to find the tick count *n*, then again: alone until *n* − P ticks, where **P = `O3M_PREROLL_TICKS` 60 ×
  multispeed (1 s)**, the chip then receives the player's register state (0x105, 0x104, 0x08, 0xBD, every operator
  register 0x20-0x35, 0x40-0x55, 0x60-0x75, 0x80-0x95, 0xE0-0xF5 of both banks, 0xC0-0xC8 through the mute routing,
  0xA0-0xA8, then 0xB0-0xB8: key-ons last; non-zero values only), and the last P ticks run as in play with the chip
  clocked and the output discarded (the resampler's history included). The player state is exact — its writes
  after a seek equal straight play's. The chip's: **identical to straight play when *n* ≤ P** (the whole way is
  clocked from the song start; the host test compares the sound bit for bit); beyond, notes keyed more than 1 s
  before the target restart their envelope 1 s before it (attack and the first second of decay are done when it
  plays; a note still decaying after that is a little louder than in straight play), release tails of notes keyed
  off within that second sound, older ones were silent anyway or are lost, and the LFO and operator phases differ.
  Measured on the test song: the 20 ms loudness envelope over the first 0.5 s within 2.6 % (rms deviation relative
  to the level; 24.5 % before the pre-roll) and loudness 0.5-1 s after within 0.8-1.25× (checked). Beyond `max_ticks`
  (Bxx loops, unreachable rows), the player is placed at the order's row 0 with the speed straight play starts that
  order with and replays its earlier rows (`CHIP_SEEK_FROM_ORDER`, the same pre-roll; this replay has its own bound,
  < 256 rows × 255 ticks); a row behind a `Dxx` of the same order cannot be reached (`CHIP_ERR_IMAGE`).
  Instruments, volumes and slides from earlier orders are lost there, as on the other systems. Cost: ~31 ms on the
  dev PC (60 clocked ticks) plus 0.2 µs per player tick; suggested `seekTicks`: 12000 (2.4 ms more).
- `apply_image` (live edit at a tick boundary, realtime-safe, 2.7 µs mean / 15 µs worst measured: a 64 KB copy
  plus a few row decodes): the player keeps no pointers into an image — rows are read by (order, row) at each
  row start, instruments and chord shapes are copied at key-on — so data may move freely. Rules: (1) a position
  past the new song is clamped to the last row of the last (or the current) order; if that row had not started,
  the next row follows it (the next order, or the loop); pending delays and jumps are dropped; (2) the speed is set
  to straight play's at the playhead (the order's start speed and the `Fxx` of the rows before it, and of the row
  itself once started) if the edit changed it or the position was clamped, else the player keeps its own (e.g.
  one inherited through the loop); (3) a row that has started re-reads its jump (`B`/`D`) and its still-delayed
  channels from the new data (a delay now in the past is dropped); (4) sounding notes keep the registers they
  were keyed with, the next note uses the edited instrument; (5) depth (0xBD) and multispeed follow the header
  from the next tick. Edited cells are heard from the next row processed.

### Audition

`audition(channel 0..8, note 0..95, slot 1..64, fine −128..127)`: the (spare) machine is reset and the player
keys on the note on that channel with the slot's instrument, no locks and no chord, `fine` added like `Pxx`
(1/64 semitones; Kotlin's `fine` is the system's fine pitch, 0 for plain notes); ticks then only update pitch and
volume (no rows), so sweeps and the instrument envelope play as in a song. A 4-op instrument on channels 7-9, an
undefined slot or arguments outside these ranges are `CHIP_ERR_IMAGE`. `release()`: 0 before any tick (cut) or
when every carrier operator is already silent (envelope at 0x1FF); else the player's key-off from the next tick,
returning 1, and `tick` returns 0 once every carrier operator's envelope is at 0x1FF (the sine instrument with
release rate 6: 52 ticks) or after 10 s (a release rate of 0 never ends). While the song plays, the host mutes the
channel with `mute`.

### Mute and ops behaviour

`mute(channel 0-8)` clears the output enables (0xC0 bits 4-5) on the chip of the channel's voices — its primary,
its 4-op secondary, and the pool voices it owns or last owned (release tails) — and never touches the player, so
its writes and the VGM are unchanged; silence is immediate (rms < 0.0005 100 ms after muting everything) and
unmuting resumes mid-note. `start`/`audition` clear mutes.

| Op | Behaviour |
|---|---|
| `load_image` | copy, then `o3_load_image`; `image_storage` = `sizeof(Opl3Image)` = 66352 |
| `start(order, row)` | above; `seek_mode` from start or order |
| `apply_image` | above; ignored while auditioning |
| `audition`, `release` | above |
| `mute(channel 0-8)` | above |
| `position` | the player's |
| `tick` | one tick: 800 frames at 48 kHz (multispeed N: 800 / N), ≤ 1601 at 96 kHz; 0 after a released audition ended |

**Kotlin** (`PlaybackEngine` for `"opl3-fm"`): `imageSize` 65536, `seekTicks` 12000, `note(n)` = n (absolute
0..95), `fine` = the system's fine pitch in 1/64 semitones (0 by default), `ticksPerSecond` = 60 × multispeed.

### VGM export

`o3_vgm_render` (`opl3/opl3_vgm.c`) runs the player headless and logs its writes as a VGM 1.71 for one YMF262;
`tools/audio/opl3/opl3_ref.py IMAGE --vgm OUT` writes the same bytes from the Python reference. File:

- Header (256 bytes, others 0): `"Vgm "`, 0x04 = file size − 4, 0x08 = 0x171, 0x18 = total samples (44.1 kHz),
  0x1C = loop offset − 0x1C and 0x20 = loop samples (both 0 without a loop), 0x24 = 60 (recording rate),
  0x34 = 0xCC (data at 0x100), 0x5C = 14318180 (YMF262 clock). `o3_vgm_render` writes no GD3 tag; the hosts'
  entry point below appends one and sets 0x14.
- Data: the reset batch; then per tick: at the first arrival at (loop order, row 0, tick 0) the loop point and a
  dump of the whole register state (0x105, 0x104, 0x08, 0xBD, every operator register of both banks, then
  0xC0-0xC8, 0xA0-0xA8, 0xB0-0xB8 for voices 0..17, all values including zeros), so each pass of the loop starts
  from the same registers although the player's writes are diffed; the key-off phase writes; if there were any,
  a one-sample wait (`0x70`); the other writes; the rest of the tick's wait (44100 / (60 · N) samples by exact
  accumulation: 735 = `0x62` at N = 1; `0x70`-`0x7F` for 1-16, `0x61 nn nn` otherwise). Writes are `0x5E reg val`
  (bank 0) and `0x5F reg val` (bank 1). The song ends at the second arrival at the loop point (or at the tick
  limit, then without a loop), then `0x66`.
- **Hosts' entry point** (`opl3/opl3_export.h`, self-contained for cinterop): `o3_export_vgm(image, 65536,
  max_ticks, title, author, date, &size, &problem)` validates the image (`problem` = the loader's reason, or
  "image size", "out of memory"), renders the file above (measured first, then rendered into one allocation) and
  appends a **GD3 1.00** tag: track = title, track (JP), game, game (JP) empty, system "OPL3 (Thor Tracker)",
  system (JP) empty, author, author (JP) empty, date (`"yyyy/mm/dd"`; empty = today, local time), converter
  "Thor Tracker", notes empty — UTF-8 in, UTF-16LE in the file (malformed UTF-8 becomes U+FFFD); 0x14 = tag
  offset − 0x14 and 0x04 cover it. Free the file with `o3_export_free`. Control thread only (it allocates and
  renders the whole song: 150 µs for the test song on the dev PC).
- **Kotlin** (`core` `system/opl3/Opl3Vgm.kt`): `Opl3Vgm.export(song)` lowers the song and calls
  `Opl3Vgm.renderer.render(image, maxTicks, Opl3VgmTags(title, author, date))`, which each host registers at
  startup: Android `ChipAudio`'s companion (JNI `ChipAudio.nativeOpl3Vgm(image, maxTicks, title, author, date)`,
  strings as UTF-8 byte arrays because JNI's own UTF strings are modified UTF-8; a rejected image throws
  `IllegalArgumentException(problem)`), the Deck's `main` (`DeckOpl3Vgm` over cinterop `decknative`). One player
  implementation for playback and export, checked against the Python reference.

### Preparing the tables and fixtures

`python3 tools/audio/prepare_opl3.py` (pure Python 3) writes `app/src/main/cpp/generated/opl3_tables.h` (from
`opl3_ref.py`'s table functions), `core/src/commonMain/kotlin/org/thortracker/backend/Opl3PlayerImage.kt` (the
image layout, limits, effect codes, pitch table, seek and GD3 constants parsed from `opl3_image.h`,
`opl3_player.h`, `opl3_machine.h` and `opl3_vgm.c`, and `LOADER_PROBLEMS`: every reason `o3_load_image` reports,
which the Kotlin validator mirrors with user-facing messages) and `app/src/test/cpp/opl3_test_song.h` (the test images: the song, the same
song laid out anew (`relayout=True`: patterns reversed behind a 37-byte gap), a note edit, a tempo edit, the song
cut to two orders and an x2 multispeed variant; the reference player's per-tick writes, key-off phase lengths and
positions for 1500 ticks of three of them; the reference VGM's size, FNV-1a hash and loop; byte offsets for the
corruption checks). Rerun it after changing the player specification, the engine headers, the reference player or
the test song.

### Verification

- The test song (`tools/audio/opl3/opl3_song.py`, 4 orders of 16/32/16/24 rows, `B01` back to the loop order 1):
  12 instruments (2-op FM and additive, all four 4-op algorithms, a sine, a kick sweeping C-5 → C-3 at 2.5
  semitones per tick, a snare sweeping +7 → −5, a hi-hat), four chord shapes (one with an inversion), every effect,
  multiple effect columns, OFF cells, delays and cuts, empty runs, patterns used twice; order 1 drains the chord
  pool and steals, channels switch between 4-op and 2-op instruments, order 2 changes speed and breaks to order 3
  row 2; order 3 ends at speed 5 and jumps back to the loop order, which has no `Fxx` (the player's loop speed).
- `tools/audio/run_native_host_test.sh [dir]` runs `app/src/test/cpp/opl3_machine_test.c` — 2026-11-08:
  **282 checks, 0 failures** (Game Boy 145, NES 336, SID 151 unchanged): the pitch table (A-4 = 580/block 4 =
  439.991 Hz; all notes ≤ 2.59 cents); 42 single-byte corruptions each rejected with its reason; **the machine's
  writes equal the Python reference player's in all 1500 ticks** (10024 writes, up to 176 in a tick; all 9 pool
  voices keyed at once; 4-op pairs switching on and off; the kick sweeping) with identical positions; 30 s
  non-silent in every second, no clipping, bit-identical on a second start; **tick cost flat**: after 1 s of
  warm-up, no tick of 30 s above 3× the mean (thread CPU time, so other processes' preemption does not count;
  1.4× measured); **loop passes**: three passes of the loop (entered from order 0 at speed 6 and by `B01` from
  order 3 at speed 5) step through identical (order, row, tick) positions, 359 ticks = the VGM's loop (the
  registers are not pass-identical: which pool voice takes a chord note follows the allocation history); eight
  seeks (up to 435 ticks, ~31 ms) landing on their row with 200 ticks of writes equal to straight play; sound after
  a seek vs straight play: 0/5 (30 ticks) bit-identical, 1/0, 1/17, 2/12, 3/20 envelope within 0.026 (limit 0.08)
  and loudness within 0.8-1.25×; the order fallback for rows only reached through `D02` and for a small budget,
  targets outside the song rejected; live
  edits: the same image and the re-laid-out image alternating before every one of 1500 ticks write the reference,
  a note edit applied at five ticks from the song start to the tick before its row equals straight play of the edited song, a tempo edit ahead
  of the playhead equals the edited song and behind it gives rows of 9 then (undone) 6 ticks, the song cut under
  order 3 clamps and continues in the loop order, x2 applied live halves the tick; audition: **A-4 measured
  439.991 Hz** on the sine instrument, held, one voice keyed, release ends after 52 ticks, `fine` +64 = A#4
  (466.16 Hz), a 4-op instrument sets its pair, the kick sweeps 523.25 → 130.81 Hz, a cut before any tick,
  argument limits; mute leaves the writes equal to the reference and silences, also when applied between `start`
  and the first tick and right after a seek's register restore; multispeed x2 = 120 ticks per second;
  **VGM bytes equal the reference player's**; the hosts' entry point `o3_export_vgm`: the reference bytes plus a
  GD3 tag (header 0x04/0x14 aside), every field decoded back (2- and 4-byte UTF-8 title, surrogate pair), a
  default date of today, rejected images with the loader's reason, a tick limit without loop; the ops table as the
  hosts drive it. Evidence in the directory:
  `opl3_song.wav` (30 s), `opl3_tone.wav` (A-4 + release), `opl3_trace.txt` (per tick: position, key-off phase,
  `|`, the rest), `opl3_song.vgm`, `opl3_song.bin` (the image).
- `tools/audio/opl3_reference_check.py <dir> [--vgm2wav PATH]` (developer evidence; the audio part needs numpy)
  — 2026-11-08, PASS: the reference player reproduces the engine's trace file in 1500/1500 ticks and the VGM byte
  for byte; the VGM parsed from the format alone has a valid header, its waits sum to the header's 334425 samples,
  the loop point is the register dump before the loop order, and the stream equals the engine's trace (key-off
  phase, one-sample gap, writes, waits) for all 455 ticks. Played by **libvgm's vgm2wav** (github.com/ValleyBell/libvgm
  `c5c03a2c`, an independent VGM player, built with its CMake; its YMF262 core chosen with a local
  `VGM2WAV_OPL3_CORE` patch to vgm2wav) against the engine's 30 s render: AdLibEmu (DOSBox's OPL3, libvgm's default)
  envelope correlation 0.891, log-spectrum correlation 0.854, same dominant pitch class in 86 % of 100 ms windows,
  level ×1.16; MAME's YMF262 0.892 / 0.857 / 84 % / ×1.08; libvgm's own Nuked-OPL3 copy (with Nuked's write
  buffer: 2 samples per write) 0.868 / 0.865 / 76 % / ×1.13. A per-channel check (each channel soloed, the VGM
  through libvgm's Nuked vs through the vendored Nuked without write buffering) gives envelope correlations
  0.96-0.998. These comparisons found two real defects that are now fixed and specified: the missing key-off gap
  (retriggered notes did not re-attack) and the loop body assuming the first pass's register state (now the
  dump), plus a test-song speed change across the loop (first `F06` on the loop order's first row, now the
  player's loop speed).
- `tools/audio/render_opl3_image.sh image out.wav [seconds] [mute=19] [--vgm out.vgm] [title=..] [author=..]
  [date=..]` renders a lowered image (e.g. the host test's `opl3_song.bin`) through the ops table as the hosts do,
  and its VGM through `o3_export_vgm`.
- **Bench**: `tools/audio/build_opl3_bench.sh [dir]` builds `tools/audio/opl3_bench.c` for the host and with the
  NDK as static arm64 executables (`-O2` as the app, and `-O3`); `adb push` and run `opl3_bench-arm64 [seconds]
  [rate] [paced_seconds]`. Sections: the first 60 ticks of the process (cold), then after 2 s of warm-up the tick
  back to back with the chip alone interleaved on the same writes (wall and thread CPU time, the overhead tick −
  chip, the slowest ticks by index, the count above 3× the mean), paced one tick per 1/60 s like an audio callback,
  the player alone, `apply_image`, and the control-thread costs. Built 2026-11-08 into `/tmp/opl3_bench2/`.
- Not done: listening on hardware, a comparison with a real YMF262.

### Hosts: threads and costs

Dev PC (`opl3_bench-host`, 48 kHz, 30 s of the test song, 2026-11-08) and the devices (the first bench, built
from the spike, 2026-11-08):

| Work | Thread | Dev PC | Nova / DMG / Konkr |
|---|---|---|---|
| `tick` (player + chip + resample) | audio callback | 528 µs mean (thread CPU worst 1.4× the mean after warm-up); chip alone 504 µs, player 0.3 µs; paced at 60 Hz 824 µs mean (the CPU clocks down between ticks) | 316 / 620 / 1293 µs mean, p99 511 / 734 / 1370 µs; chip alone 274 / 562 / 1224 µs |
| `apply_image` (relocating) | audio callback, tick boundary | 2.8 µs mean, worst 7-15 µs | 1.7 / 3.5 / 8.4 µs mean, worst 8 / 66 / 32 µs |
| machine creation | control | 0.03 ms | 0.16 / 0.09 / 0.10 ms |
| `audition` setup | control, spare machine | 1.6 µs | 1.6 / 3.1 / 7.5 µs |
| `start` (seek) | control, spare machine | ~31 ms (1 s pre-roll; 14 ms within the first 0.5 s), + 2.4 ms for 12000 player ticks | ~17 / 37 / 75 ms pre-roll (60 × the chip) + 1.9 / 3.7 / 9.0 ms for 12000 ticks |

**Tick outliers** (2026-11-08): the first device bench showed rare slow ticks, Nova p99.9 3.5 ms / worst 3.9 ms
(12× the mean), DMG 2.6 ms, Konkr 3.6 ms, while the chip alone stayed flat (Nova p99.9 0.34 ms). They were not the
engine: they appear only in the section a process measures first (the tick was first in the engine bench; in the
separate core bench Nuked, measured first, showed the same on the Nova — worst 3× its mean — and ymfm and blip_buf
measured after it did not), only 2-3 of 1800 ticks (p99.9 of 1800 ticks is the second-slowest), and p99 (18th
slowest) is 1.06-1.6× the mean: the start of a fresh process on a cold CPU (frequency ramp and migration from a
little core), measured as engine time because the chip-alone section ran after 30 s of warm-up. The engine's tick
has no data-dependent slow path: no allocation, fixed blip_buf frame sizes (end_frame of the tick's 828/829 native
samples, read of all available frames, ≤ 1601), the key-off split costs one extra native sample, integer chip and
resampler (no denormals); on the dev PC its thread CPU time is ≤ 1.43× the mean in every tick after warm-up (host
test: ≤ 3×). The bench now measures the cold start separately, warms up, interleaves the chip alone per tick so both
see the same CPU state, reports thread CPU time and the slowest ticks by index, and adds a paced (callback-like) run.
In the app the first callbacks after a play start may still meet a cold CPU; that is the host's (AAudio performance
mode) concern and the same for every system.

The hosts treat the machine like the SID's (`Engine::create` on the control thread, seeks and auditions on spare
machines, the callback only ticks, mutes and applies images); nothing in `audio_engine.cpp` or `deck_audio.c` is
system-specific.

## YM2612: SGDK's XGM driver on a Z80 + YM2612 + PSG (`"ym2612-fm"`)

Design step 8 (FM), second chip, native spike (branch `ym2612-spike`): `chip_engine_ops("ym2612-fm")` plays songs
lowered into the image layout below **through SGDK's XGM Z80 driver**, the driver Mega Drive homebrew ships. The
driver investigation and the reasons for each choice are in `docs/BACKENDS.md`, "YM2612 (Mega Drive)". XGM is a
register stream, so the musical part is Thor Tracker's **own player** (`ym/ym_player.c`, pure integer C, specified
here and mirrored by the independent Python reference `tools/audio/ym/ym_ref.py`; the host test requires identical
writes in every tick). Its per-tick writes are the VGM export; xgmtool's VGM → XGM → XGC conversion (ported to C,
`ym/ym_xgm.c`, proven byte-equal to xgmtool 1.76) turns them into the XGC frames the driver reads from the emulated
cartridge, one frame per vertical interrupt, exactly as `XGM_startPlay` plays a song in a game. This section is the
contract of the Kotlin system and lowering (`core` `system/ym2612`, `backend/YmLowering`); the numbers they share
with the engine are generated into `core/.../backend/YmPlayerImage.kt` (below, "Preparing").

### What runs

- **Z80** (floooh `z80.h`, the pinned `m6502.h` commit, cycle-stepped, `cpuz80.c` its one implementation) at
  MCLK / 15 with the Mega Drive's Z80 map: 8 KiB RAM at `$0000` (mirrored to `$3FFF`), YM2612 ports at
  `$4000-$4003` (mirrored to `$5FFF`; reads return the status byte with the busy flag), the bank register at
  `$6000` (9 serial bit-0 writes select A15-A23 of the 68000 address the window shows), the PSG at `$7F11`
  (`$7F11/13/15/17`), the 32 KiB bank window at `$8000`. The emulated 68000 address space holds only what the
  driver reads: a 256-byte null sample (`YM_NULL_SAMPLE` = `$010000`, zeros) and the music ring (`YM_RING` =
  `$020000`, 64 KiB); everything else reads `$FF`. Z80 accesses to the 68000 bus take no extra wait states (real
  hardware adds about 3 cycles each; it slows the driver's PCM mixing, not its frame processing). No interrupts:
  the driver runs with interrupts disabled. Most of its cycles are not stepped but replayed (below, "Pass replay").
- **Driver**: `drv_xgm.s80` (SGDK `9a78a441`, MIT), unmodified, assembled with SGDK's sjasm into
  `generated/xgm_driver_image.h` (5888 bytes; byte-identical to `drv_xgm` in SGDK's `lib/libmd.a`).
- **YM2612**: ymfm (`81aec25c`, BSD-3-Clause) `ymfm::ym2612` behind a C interface (`ym/ym_ymfm.cpp`, built like
  reSID: C++17, `-O2 -w`), clocked at MCLK / 7 (one sample per 1008 MCLK = 53267.0 Hz NTSC, 52781.2 Hz PAL); a
  data write sets the busy flag for 32 × 6 chip clocks (ymfm's figure; 89.6 Z80 cycles), which the driver polls.
  Writes take effect at the next chip sample (ymfm is sample-based); the driver's busy waits keep a key-off and a
  key-on of one channel at least one sample apart.
- **PSG**: `ym/ym_psg.c`, an SN76489 as integrated in the Mega Drive VDP (written for Thor Tracker from the SMS Power
  documentation): three 10-bit tone counters and a noise counter decremented at MCLK / 240 (PSG clock / 16), a tone
  output flips when its counter reloads (period 0 counts as 1), 16-bit noise LFSR (white noise: feedback from bits
  0 and 3, i.e. taps 0x0009; periodic: bit 0 recirculated), reset to `$8000` by every noise-register write, shifted
  each time the noise counter's flip-flop rises; noise rates clock/512, /1024, /2048 (counter 16, 32, 64) or tone 3's
  period; 4-bit attenuation in 2 dB steps (15 = off). Bipolar output (±level).
- **Timing**: one engine tick = one vertical interrupt: 3420 MCLK per line × 262 lines (NTSC, MCLK 53693175 Hz,
  59.92 Hz) or 313 lines (PAL, MCLK 53203424 Hz, 49.70 Hz): 59736 (71364) Z80 cycles, 888.9 (1061.96) YM samples,
  3733.5 (4460.25) PSG counter steps per tick, all accumulated exactly in master clocks. (The VGM export uses
  VGM's 1/60 s and 1/50 s, as xgmtool does.)
- **Mix and resampling**: YM2612 left/right and the PSG (centred) go through band-limited steps (blip_buf 1.1.0, as
  the NES/OPL3) clocked at the Z80 rate (one step per YM sample, one per PSG change) to the host rate, with
  blip_buf's ~15 Hz high-pass. Levels: ymfm's 14-bit sum × `YM_FM_GAIN`, the PSG's channel level table × 
  `YM_PSG_GAIN` (see "Mix"), float = sample / 32768.
- **68000 side** (`ym/ym_machine.c`, what SGDK's `xgm.c` does): at reset `YM2612_reset()` and `PSG_reset()`'s writes
  go to the chips (below, "Machine reset writes"), the driver is copied to Z80 RAM and the Z80 reset; it runs until
  it reports ready (`STATUS` bit 7, 20.4 k cycles); then the null sample entry at `$1C00` and the 63 sample ids at
  `$1C04` (all the null sample: no PCM), the music address `PARAMS+$00` = `YM_RING`, `COMMAND` |= `$40` (play).
  Per tick: if `MODIFYING_F` (`PARAMS+$0E`) is set the Z80 runs until it clears (inside the tick's cycles), then
  `PENDING_FRM` (`PARAMS+$0F`) += 1, then the tick's Z80 cycles run with the chips.

### Streaming XGC frames (driver in the loop)

The driver executes a frame when `PENDING_FRM` > 0 and, right after, **prefetches the next frame** into its buffer.
So the engine keeps the player one tick ahead: at engine tick *k* it runs player tick *k* + 1, encodes its writes
into XGC frames (`ym/ym_xgm.c`, xgmtool's algorithm, below) and appends them to the ring; then it releases tick *k*,
in which the driver executes the frames of player tick *k* (prefetched during tick *k* − 1) and prefetches those of
*k* + 1. Starting play encodes player tick 0, sets the play command and runs one tick in which the driver picks the
command up (its `external_com` runs after a processed frame) and prefetches; that tick's output is discarded.
`position()` reports the position of the player tick whose frames the next engine tick executes.

- A player tick may become several XGC frames (a frame holds ≤ 250 bytes; xgmtool ends a full frame with the
  frame-skip command, so the driver executes the parts back to back in the same interrupt).
- **Ring wrap**: when a tick's frames would not fit before the ring's end, the engine first writes a frame holding
  only frame-skip + loop to offset 0 (`$FA $FC 00 00 00`, a 6-byte frame), which the driver executes within the same
  interrupt as the next frame (the frame-skip adds the interrupt the loop's frame end consumes), then writes the
  tick's frames from the ring start. The driver counts loops down in `REM_LOOP` (`PARAMS+$38`) and stops at 0; the
  engine sets it to 0 (= 256 more loops) at every wrap.
- The engine never writes the XGC file's 3 leading empty frames (they only align PCM, which this system has none of)
  nor its loop command (the player follows the song's loop itself). Every arrival at (loop order, row 0, tick 0)
  encodes the **loop frame** as the export has it: the player's register dump and xgmtool's loop start (the frame
  carries every register). From the song start the stream is therefore **byte-identical to xgmtool's XGC** of the
  exported VGM for the whole first pass, loop frame included (host test); later passes dump the player's state then
  (the game re-executes the first arrival's dump; they differ only in registers the song leaves changed at its end).
- **Driver lag (heavy frames)**: the XGM v1 driver spends most of each frame mixing PCM (silent here) and executes
  about 70-100 YM writes per vertical interrupt; a heavier frame spills into the next interrupts and the frames
  queued behind it then run back to back until it has caught up (`PENDING_FRM` counts the interrupts it owes). Measured
  on the test song: the song start (the machine reset writes, ~280 writes) and each loop frame (the full dump) lag up
  to 3 ticks, 31 of 1500 ticks lag at all. A game does exactly the same with this XGC; the Kotlin validator should warn
  about rows whose writes exceed the budget. `position()` follows the driver: it is the position of the first tick the
  driver has not finished (`encoded − 1 − PENDING_FRM`), so behind a heavy frame it waits and then catches up.

### Pass replay (the driver's idle work, `ym/ym_machine.c`)

Without PCM the driver spends ~92 % of its Z80 cycles in two pieces of fixed code: its PCM mixer (`pcm_mix`, 43917
cycles per 256 silent samples, about one pass per tick) and its frame-wait loop (`sync_frame_loop`, 254 cycles per
iteration). Neither reads the music. The machine records one execution of such a **pass** — from the opcode fetch at
one of the two labels (addresses from the sjasm listing, `XGM_DRIVER_PCM_MIX`, `XGM_DRIVER_SYNC_FRAME_LOOP` in
`generated/xgm_driver_image.h`) to the next fetch at either — and replays it whenever a pass starts with the same
inputs: the chips run alone for its cycles and receive its chip and bank writes at the same cycles, its memory
writes land at its end, and the Z80 continues in the recorded exit state. Driver, timing and output are exactly the
fully stepped machine's.

- **Inputs compared**: the first read of every byte (code included, ROM reads by address), the YM address latch,
  the bank register if read before the pass sets it, the registers a pass reads (not those it loads before use:
  the mixer's AF BC DE HL SP, the frame wait's AF HL).
- **Re-based, not compared**: the PCM read pointer BC' (each `LD A,(BC)` under EXX is a relative read: its value is
  compared at the re-based address, which must not be one the pass writes; wherever the pass reads B' its page is
  compared; BC' ends advanced by the pass's reads), R (advanced, or exact after an `LD R,A`), the frame-wait
  counter (`IDLE_LOOP` + 1, and HL with it), and registers neither pass touches (IY, AF'; the frame wait's BC, SP:
  carried through, checked unchanged when recorded).
- **Ticks**: a pass may run on across the tick boundary (the vertical interrupt, the ring append) unless it read
  memory the 68000 side changes there (`PENDING_FRM`, `REM_LOOP`, the ring): such a pass (the frame wait) only
  replays if it ends within the tick, else it is stepped.
- **Recording** aborts on anything else (a YM status read, an I/O access, a write to the 68000's variables, `LD A,R`,
  `LD A,C` on the pointer); those passes are simply stepped. At most 64 passes and 640 KiB per machine (allocated
  at creation); the test song keeps 18 (190 KiB), 40 with PAL and auditions. The memo survives `start` and
  `audition` (same driver); the first ticks of a new machine record (a cold tick costs what a stepped one does).
- **Proof**: the host test runs a replayed and a fully stepped machine side by side (the song across a ring wrap,
  PAL, a seek with mutes, FM/PSG/Noise auditions with release; 4817 ticks) and requires identical samples, chip
  writes with their cycles, lag and positions every tick, and identical Z80 state and RAM whenever the replayed one
  is between passes; 95 % of the Z80's cycles were replayed. `ymm_set_replay(m, 0)` steps every cycle (test hook).

### Machine reset writes (SGDK's `YM2612_reset()` + `PSG_reset()`)

In this order, to the chips at machine reset and as the first commands of the VGM: port 0 `$22`=0, `$27`=0,
`$2B`=0; for port 0 and 1, channel 0..2, slot 0..3, register `$30`=0, `$40`=`$7F`, `$50`=0, `$60`=0, `$70`=0,
`$80`=`$FF`, `$90`=0 at register + 4·slot + channel; for port 0 and 1, channel 0..2: `$A0`=0, `$A4`=0, `$A8`=0,
`$AC`=0, `$B0`=0, `$B4`=`$C0` (+ channel); port 0 `$28` = 0, 4, 1, 5, 2, 6 (channel 0..2: `0|ch`, `4|ch`); PSG
for i = 0..3: `$80 | i<<5`, `$00`, `$90 | i<<5 | $0F`.

### Channels

| Channel | 0-5 | 6, 7, 8 | 9 |
|---|---|---|---|
| Name | FM 1-6 | PSG 1-3 | Noise |
| Chip | YM2612 channel *c*: port *c* / 3, register offset *c* mod 3, key select (`$28`) 4·(*c* / 3) + *c* mod 3 | SN76489 tone *c* − 6 | SN76489 noise (index 3) |
| Instruments | FM slots | PSG slots | PSG slots |

FM 6 is an ordinary FM channel (the driver's DAC is off without PCM). No channel 3 special mode, no DAC.

### Image layout (what the Kotlin lowering produces)

`image_size` = 65536 bytes, little-endian, every pattern reference an offset into the data region (so any layout
that passes `ym_load_image` relocates). Constants: `ym/ym_image.h` (`YM_*`); the reference builder is
`tools/audio/ym/ym_song.py` (`build()`). The loader rejects anything off-contract with a reason and the byte
(`YmInfo.problem`, `problem_offset`): mirror its checks in Kotlin validation for user-facing messages.

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | `"TTYM"` |
| 4 | 1 | layout: 1 |
| 5 | 1 | region: 0 = NTSC (60 Hz), 1 = PAL (50 Hz) |
| 6 | 1 | speed (ticks per row) at the song start, 1..255 |
| 7 | 1 | LFO (register `$22`) at the start: 0 = off, `$08`-`$0F` = on, rate 0..7 (3.98 … 72.2 Hz) |
| 8 | 2 | order count, 1..256 |
| 10 | 2 | loop order (< count) |
| 12 | 1 | instrument count I = highest slot present, 0..64 |
| 13 | 1 | 0 |
| 14 | 2 | data size D: bytes of pattern and envelope data used, ≤ 57536 |
| 16 | 48 | 0 |
| 64 | 256 | rows of order *o* at 64 + *o*: 1..255, 0 = 256 (Thor Tracker: ≤ 64); entries ≥ count 0 |
| 320 | 5120 | pattern of (order *o*, channel *c*) = u16 offset into the data region at 320 + 2 (10 *o* + *c*); entries ≥ count 0 |
| 5440 | 2560 | instrument slot *n* (1..64) at 5440 + 40 (*n* − 1) |
| 8000 | 57536 | data region: patterns and PSG envelopes, `[0, D)` |


**Instrument** (40 bytes; an all-zero record = no instrument; records above I must be zero). Byte 0, flags: bit 7
= defined (1), bit 0 = PSG instrument (1) or FM (0); other bits 0.

FM instrument (flags `$80`):

| Byte | Field |
|---|---|
| 1 | algorithm 0..7 (register `$B0` bits 0-2) |
| 2 | feedback of operator 1, 0..7 (`$B0` bits 3-5) |
| 3 | AMS (bits 4-5) and FMS (bits 0-2), register `$B4` without the pan bits; bits 3, 6, 7 zero |
| 4 + 7 *k* | operator *k* + 1 (*k* = 0..3): registers `$30` (DT bits 4-6, MUL 0-3; bit 7 0), `$40` (TL 0..127; bit 7 0), `$50` (RS bits 6-7, AR 0-4; bit 5 0), `$60` (AM bit 7, D1R 0-4; bits 5-6 0), `$70` (D2R 0-4; bits 5-7 0), `$80` (D1L 4-7, RR 0-3), `$90` (SSG-EG 0-3; bits 4-7 0) |
| 32 | drum sweep start, s8 semitones relative to the note, −96..96 |
| 33 | drum sweep end, s8 semitones, −96..96 |
| 34 | drum sweep speed, 1/16 semitone per tick toward the end; 0 = no sweep (start/end ignored) |
| 35 | 5 bytes 0 |

Operators are numbered as in the algorithm diagrams (operator 1 has the feedback); their registers sit at offsets
+0, +8, +4, +12 (operator 1, 2, 3, 4) from the channel's. **Carriers** (what volume scales and brightness leaves
alone), bit *k* = operator *k* + 1: algorithms 0-3 {4}, 4 {2, 4}, 5 and 6 {2, 3, 4}, 7 {1, 2, 3, 4}.

PSG instrument (flags `$81`): references up to three FamiStudio-style envelopes (one value per tick) in the data
region, so instruments can share them.

| Byte | Field |
|---|---|
| 1 | noise mode (Noise channel only, ignored elsewhere): bit 2 white (1) / periodic (0), bits 0-1 source: 0..2 = clock/512, /1024, /2048, 3 = PSG 3's period ("pitched"); bits 3-7 zero |
| 2 | flags: bit 0 = the pitch envelope is relative (accumulates every tick); bits 1-7 zero |
| 3 | 0 |
| 4 | u16: data offset of the volume envelope (values 0..15), `$FFFF` = none (level 15) |
| 6 | u16: data offset of the arpeggio envelope (−64..63 semitones), `$FFFF` = none |
| 8 | u16: data offset of the pitch envelope (−64..63 × 4 pitch units = 1/16 semitone), `$FFFF` = none |
| 10 | 22 bytes 0 |
| 32 | drum sweep start, end, speed (as FM) |
| 35 | 5 bytes 0 |

**Envelope** (at its offset, wholly inside `[0, D)`): u16 length L 1..256, u16 loop index (< L, `$FFFF` = none),
u16 release index (`$FFFF` = none; volume only, and only with a loop: loop < release < L), then L signed bytes.
The reference builder places envelopes after the patterns, slots 1..64 ascending (volume, arpeggio, pitch), each
deduplicated by its bytes against the envelopes before it; any placement that passes the loader plays.

**Patterns** (as the OPL3's): a pattern is decoded from its offset for exactly rows(*o*) rows (a run longer than the
order's remaining rows is cut there, so one pattern can serve orders of different lengths); every row read must lie
inside `[0, D)`. Row encoding:

| Bytes | Meaning |
|---|---|
| `0x80 \| k` | k + 1 empty rows (k 0..127) |
| `m` (0x01..0x13), then data | bit 0: a note byte follows; bit 1: an instrument byte follows; bits 2-4: effect count 0..4; bits 5-6 zero, m ≠ 0 |
| note | 0..95 = absolute note C-0..B-7 (0 = C-0, 57 = A-4 = 440 Hz), `0xFE` = OFF (key off) |
| instrument | slot 1..64, defined, FM on channels 0-5, PSG on 6-9 |
| effects | per effect column in order: code (0..35 = `0`-`9`, `A`-`Z`), parameter |

**What the lowering writes** from a `Cell(note, instrument, effect, param)` with up to 4 effect columns: note
`EMPTY` → no note byte, `CUT` → `0xFE`, an absolute note 0..95 → itself; instrument 0 → no byte, else the slot;
effect columns in column order, blank columns (code 0, parameter 0) omitted. Empty rows pack into runs. The
lowering may share byte-identical patterns between orders and channels (the reference builder deduplicates).
Load-time effect checks: the effect must exist on the channel (table below), `Bxx` < order count, `Cxx` ≤ 7F (FM) or
≤ 0F (PSG, Noise), `Exx` ≤ 7, `F00` rejected, `Ixx` ≤ 7F, `Lxx` = 00 or 08..0F, `Mxy` x 1..4, `Nxy` x ≤ 1 and y ≤ 3.
Envelope checks: as above, values in range (volume 0..15, others −64..63), release only on volume.

**Should** (not checked): an instrument on the first note of every order; locks only on rows with a note
(otherwise ignored: warn); PSG 3 silent while pitched noise sounds (it is muted then: warn).

`ym_load_image` derives (`YmInfo`) order count, loop, region, speed, rows per order and the speed each order starts
with in sequential straight play (header speed, then the last `Fxx` of each order: rows, then channels, then
columns; jumps ignored).

### Player

Units: **pitch** in 1/64 semitone, 0 = C-0, at most 8 · 768 − 1 = 6143 (just below C-8); notes are pitch = note · 64.

**FM pitch → registers**: octave = pitch / 768, f = pitch mod 768, F-number = `YM_FNUM[region][f]`, block =
octave − 1 if f < `YM_FNUM_SPLIT[region]` else octave; block −1 becomes block 0 with the F-number halved
(`>> 1`). With F_C = 440 · 2^(−57/12) · 2^21 / fs (C-n's F-number at block n), fs = MCLK / 1008 (NTSC 53267.04 Hz: F_C = 643.76; PAL
52781.17 Hz: 649.69) and x = 2 · F_C · 2^(f/768): `YM_FNUM[f]` = floor(x + 0.5) for f < SPLIT, floor(x / 2 + 0.5)
from SPLIT on, SPLIT = the first f with floor(x + 0.5) > 2047 (NTSC 514, PAL 504). Registers: `$A4` = block << 3
| F-number >> 8, `$A0` = F-number & 255, **always as a pair, `$A4` first** (the chip latches `$A4` until `$A0`): if
either differs from the shadow both are written. A-4 = F-number 1083 at block 4 = 440.13 Hz (NTSC; PAL 1093,
440.14 Hz); every note from C-1 is within 0.66 cents of 12-TET A440, octave 0 (halved F-numbers) within 1.9.

**PSG pitch → period**: N = clamp((`YM_PSG_DIV[region][f]` + (128 << octave)) >> (8 + octave), 1, 1023) with
`YM_PSG_DIV[f]` = floor(256 · PSG_clock / (32 · C0 · 2^(f/768)) + 0.5), C0 = 440 · 2^(−57/12) Hz, PSG_clock =
MCLK / 15 (3579545 Hz NTSC, 3546894.9 Hz PAL). A-4 = 254 (440.40 Hz; PAL 252, 439.84 Hz); notes below A-2 (N > 1023) sound at the
lowest period (109.3 Hz); high notes are coarse (10-bit periods). **Pitched noise** (source 3) writes PSG 3's
period N(pitch + 48 · 64): periodic noise then sounds at the note (the LFSR's period of 16 shifts at tone 3's
rate is 4 octaves below tone 3).

`generated/ym_tables.h` holds both tables per region and the vibrato sine `YM_SINE[i] = floor(127 sin(2πi/64) +
0.5)` (the OPL3's).

**Writes** are records: `YM0 reg val` (port 0), `YM1 reg val` (port 1), `PSG byte`. Every YM register write goes
through a shadow of both ports' 256 registers, every PSG write through a shadow of the three tone periods, four
attenuations and the noise control: a write equal to the shadow is not emitted (the frequency pair: above).
Key-on/off (`YM0 $28`) is not shadowed: it is written when a channel's key state changes (a retrigger writes off
then on). PSG encodings: tone period of tone *i*: if only the low 4 bits differ from the shadow one byte `$80 | i<<5
| N & 15`, else two: that and `N >> 4`; attenuation `$90 | i<<5 | a`; noise control `$E0 | mode`. A batch is the
key-off phase — every `$28` key-off write, in the order made — followed by every other write in the order made
(`YmPlayer.offs` = length of the first part).

**Shadow at reset** = the chips after the machine reset writes: per port and channel the operator registers `$30` 0,
`$40` `$7F`, `$50`-`$70` 0, `$80` `$FF`, `$90` 0, `$A0`-`$AC` 0, `$B0` 0, `$B4` `$C0`; `$22`, `$27`, `$2B` 0; all
other registers 0; tone periods 0, attenuations 15, noise control 0; every FM channel keyed off.

**Reset** (start, audition): all state cleared; FM channels: instrument 0, volume `$7F`, pan both (`$C0`); PSG and
Noise: instrument 0, volume `$0F`; LFO = header byte 7; speed = header speed; position (0, 0, tick 0). Writes:
`YM0 $22` = LFO (emitted only if not 0).

**Tick** (positions are (order, row, tick-in-row); the host's position is the tick about to run):

1. `YM0 $22` = the LFO value.
2. Tick 0 of a row: read every channel's row at (order, row). Then, over channels 0..9 and their effect columns in
   order: `Fxx` sets the speed (the last one wins and applies to this row); `Bxx`/`Dxx` record the next position:
   `B` only → (xx, 0); `D` only → (next order, xx), the next order being order + 1 or, past the last one, the loop
   order; both → (B's order, D's row). Then for each channel 0..9: its cut is cleared; if the row has `Gxx` with xx >
   0 (the last `G` of the row) its row is delayed to tick xx; otherwise the row runs now. Other ticks: each channel
   whose delayed row is due (tick = xx) runs it, channels in order.
3. Each channel whose cut tick (`Sxx`) equals this tick is keyed off, channels in order.
4. Each channel 0..9 that has keyed any note since reset is updated (below), channels in order.
5. tick + 1; when it reaches the speed: tick 0 and the next row — the recorded jump (row clamped to the target
   order's last row), else row + 1, after the last row of an order the next order, after the last order the loop
   order. **Loop speed**: whenever the next row is row 0 of the loop order the speed becomes the speed sequential
   straight play starts that order with (`YmInfo.start_speed`; an `Fxx` on that row still applies at its row start).

**Running a row** of a channel: if the row has a note 0..95, the arpeggio and fine pitch are cleared; an instrument
byte sets the channel's instrument; the effect columns run in order (table below); then the note: OFF keys the
channel off; a note with portamento active (`3xx`, xx > 0) while the channel is keyed only sets the glide target
(no key-on: the row's locks are not used); any other note keys on.

**FM key-on** (note *n*, channel *c*): if the channel's instrument is not a defined FM slot, nothing happens. Else
the channel is keyed off (below), the instrument's operators, algorithm, feedback and AMS/FMS are copied and the
row's locks applied in column order (`E` feedback, `I` brightness, `M` ratio), then written operator by operator
(1..4): `$30`, `$40` (carriers: TL + 127 − volume, at most 127), `$50`, `$60`, `$70`, `$80`, `$90`; then `$B0` =
feedback << 3 | algorithm, `$B4` = pan | AMS/FMS. Pitch state: base = target = 64 *n*, slide offset 0, arpeggio
step 0, vibrato phase 0, sweep offset = 64 · start (when the sweep speed is not 0, else 0), sweep end 64 · end,
sweep step 4 · speed. Keyed, and the key-on is pending (written by the update).

**FM key-off**: if keyed, `$28` = key select (operators off), keyed = 0, and a pending key-on is dropped (a
key-on and a cut in the same tick, e.g. `S00`, leave the channel off).

**PSG key-on** (channels 6-9): if the instrument is not a defined PSG slot, nothing happens. Else the channel is
keyed off (below), the instrument's three envelopes are copied (an instrument edit applies from the next note, as
FM's) with every position 0, the pitch accumulator 0 and the relative flag; phase on. Noise channel: the note's
noise mode = the instrument's byte 1, or (x << 2) | y of the row's `Nxy` (lock). Pitch state as FM. Keyed.

**PSG key-off**: if keyed: keyed = 0; a volume envelope with a release point continues there (phase released),
anything else is silent at once (phase off).

**FM update** (per tick, after the row): pitch = clamp(base + 64 · arpeggio + fine + slide + vibrato + sweep, 0,
6143), with arpeggio = 0, x, y for step 0, 1, 2 of `0xy` and vibrato = floor(SINE[phase] · depth / 16)
(arithmetic right shift). Writes: the carriers' `$40` (operators 1..4, TL + 127 − volume, ≤ 127), the frequency
pair, then a pending key-on: `$28` = `$F0` | key select. Then the effects advance: arpeggio step (mod 3) if an
arpeggio is set; slide offset ± speed (clamped to ±6144) for `1xx`/`2xx`; base toward target by the speed (no
overshoot) for `3xx`; vibrato phase + speed (mod 64) when speed and depth are set; sweep offset toward the end by
its step (no overshoot); volume · 8 + x − y (clamped 0..1016) for `Axy`. A released note keeps updating.

**PSG update** (channels 6-9). Envelope values at the current positions: level = 0 if the phase is off, else the
volume envelope's value (15 without one); arpeggio = its value (0 without); bend = 4 · the pitch envelope's value
(0 without); with the relative flag the accumulator += bend (clamped ±6144) and the offset is the accumulator,
else the offset is the bend. Pitch as FM with 64 · arpeggio + offset added inside the clamp. Tone channels 6-8
(tone *i* = *c* − 6): if *c* = 8 and pitched noise owns PSG 3 (the Noise channel has keyed a note since reset, its
note's mode has source 3 and its phase is not off), only attenuation 15 is written; else the period N(pitch) and
attenuation 15 − (level · volume + 7) / 15 (volume = the channel volume 0..15, i.e. volume · 8 >> 3; integer
division). **Noise** (channel 9): noise control = the note's mode; if its source is 3, PSG 3's period N(pitch +
3072); attenuation of index 3 as above. Then the effects advance as FM (volume clamped 0..120), then each envelope
steps (FamiStudio's rules): next = position + 1; not released and next = the release point → the loop index;
else next = L → the loop index if the envelope has a loop and no release point, else L − 1 (hold the last value).
A released note whose volume position is now L − 1 with value 0 turns off (pitched noise frees PSG 3).

### Effects (what Kotlin's effect specs declare for `"ym2612-fm"`)

Names are one character (`docs/BACKENDS.md`, "Effect columns"); up to 4 columns per channel. "Note" = applies to
the note keyed on this row only (parameter lock); "now" = from this row on, persisting. Channels: FM = 0-5, PSG =
6-8, N = 9; an effect on a channel it does not list is rejected by the loader.

| Effect | Channels | Parameter | Meaning |
|---|---|---|---|
| `0xy` arpeggio | all | x, y semitones | note: steps note, +x, +y every tick; the next note without `0xy` plays plain (also sets the step to 0; on a row without a note it changes the sounding note's arpeggio) |
| `1xx` slide up | all | 1/64 semitones per tick | now: the pitch rises until `100`/`200`/`300`; a new note restarts the offset at 0 but keeps sliding |
| `2xx` slide down | all | 1/64 semitones per tick | now: as `1xx`, down |
| `3xx` portamento | all | 1/64 semitones per tick | now: while active a note on a keyed channel glides there without key-on; `300` (or `1xx`/`2xx`) ends it |
| `4xy` vibrato | all | x speed 0-F (64-step cycle), y depth 0-F | now: offset floor(SINE · y / 16) (depth F: ±1.86 semitones); `4x0` off; the phase restarts with each note |
| `8xy` pan | FM | x ≠ 0 left, y ≠ 0 right | now: `$B4` bits 7/6, written at once to the sounding note; `800` silences |
| `Axy` volume slide | all | x up, y down, 1/8 step per tick | now: until `A00` |
| `Bxx` jump | all | order | after this row play order xx row 0 (with `Dxx`: its row) |
| `Cxx` volume | all | FM 00-7F, PSG 00-0F | now: channel volume (start 7F / 0F); FM 0.75 dB per step on the carriers' TL, PSG scales the envelope level |
| `Dxx` break | all | row | after this row play row xx (clamped) of the next order (`Bxx`'s with both) |
| `Exx` feedback | FM | 0-7 | note: operator 1 feedback |
| `Fxx` speed | all | 01-FF ticks per row | now, global, this row included; the last of the row wins |
| `Gxx` note delay | all | ticks | the channel's row (note, instrument, all its effects except `B`/`D`/`F`) runs at tick xx; never if xx ≥ the speed |
| `Ixx` brightness | FM | 00-7F | note: TL of every modulator (non-carrier) = 7F − xx: 7F = brightest; algorithm 7 (no modulators) = no effect |
| `Lxx` LFO | all | 00, 08-0F | now, global: register `$22` (00 off, 08-0F on at rate 0-7: 3.98, 5.56, 6.02, 6.37, 6.88, 9.63, 48.1, 72.2 Hz); depth per instrument (AMS/FMS, operator AM) |
| `Mxy` ratio | FM | operator x 1-4, MUL y 0-F | note: frequency multiplier (0 = ×½), DT kept |
| `Nxy` noise mode | N | x 0 periodic / 1 white, y source 0-3 | note: overrides the instrument's mode (y 3 = pitched by the note, owning PSG 3) |
| `Pxx` fine pitch | all | 80 = none | xx − 80 in 1/64 semitones (±2 semitones) until the next note |
| `Sxx` note off | all | tick | key off at tick xx of this row (release phase), like an OFF cell at that tick |

FM locks (`E`, `I`, `M`) apply at key-on only (changing a TL or multiplier of a sounding note steps audibly).
Volume, pan and LFO change at once. No chord effect (no spare voices); drums are ordinary instruments with the
sweep, the note giving the pitch.

**`Lxx` and the header LFO**: `Lxx` writes `$22` at once and sets the player's LFO value, which step 1 rewrites
every tick (emitted only on change).

### XGM / XGC encoding (`ym/ym_xgm.c`: xgmtool 1.76's algorithm)

The player's writes, frame by frame (one frame per tick; the VGM's frame), become XGM and XGC as xgmtool converts
the exported VGM (`xgmtool song.vgm song.xgm` / `song.bin`); the host test checks byte equality with xgmtool built
from the pinned SGDK. Per frame (with a loop-start marker on the first arrival at the loop point):

1. **Clean** (`VGM_cleanCommands`): YM writes update a register state (`YM2612_set`: `$28` keeps the channel's
   key nibble, `$27` keeps bits 6-7; registers xgmtool ignores — port 1 `$22`-`$2B`, `$30`-`$B7` with offset 3,
   everything below `$22` and from `$B8` — are dropped). A key write (`$28`) is kept as is; a non-key write after
   key writes first flushes: the state's delta since the last flush, then those key writes. At the frame end: the
   remaining delta, the remaining key writes, then the PSG delta (`PSG_getDelta`: per index 0..3, tone then
   attenuation; a tone whose high bits are unchanged is one byte). The YM delta lists the register pairs
   (`$24`/`$25`, `$A4`/`$A0`, `$A5`/`$A1`, `$A6`/`$A2`, `$AC`/`$A8`, `$AD`/`$A9`, `$AE`/`$AA`; port 1 too except
   `$24`) first, both when either differs, then port 0 and port 1 registers in ascending order. A loop-start marker
   resets the old state (the loop frame carries every register written so far).
2. **XGM** (`XGM_extractMusic`): PSG writes, key writes and port 0/1 writes are grouped; a port write after key
   writes flushes port 0, port 1, then keys; at the end port 0 (≤ 16 writes per `$2X`), port 1 (`$3X`), keys (≤ 16 per
   `$4X`), PSG (≤ 16 per `$1X`); then `$00` (frame), or at the very end the loop command `$7E` (offset of the loop
   frame) and `$7F`.
3. **XGC** (`XGC_extractMusic`): per XGM frame a size byte, then the PSG writes (split per XGM PSG command into
   envelope writes, ≤ 4 per command, then tone writes, ≤ 8), the YM commands in order with key commands split at 6
   writes and `$2B` writes removed, then **state** commands (`$60 | n−1`, pairs (index, value): D1L/RR registers
   `$80`-`$8E` that changed, index `$44` + slot, port 0 then 1), a frame skip + loop at the loop's end; command
   bytes are the XGM codes << 1 (frame skip `$7D` → `$FA`, loop `$FC`); a frame reaching 250 bytes is closed with a
   frame skip and continued in a new frame. The file has 3 empty frames first, the sample table (all "silent":
   `$FFFF`, `$0001`), `$FE` = 0, `$FF` = PAL flag.

`.xgm` files carry a GD3 tag (flag bit 1) when the export has one; xgmtool appends " - Optimized with XGMTool" to the
converter field, Thor Tracker writes its own GD3 (as the VGM's).

### VGM export

`ym_vgm_render` (`ym/ym_vgm.c`) runs the player headless and logs its writes as a VGM 1.71 for one YM2612 and one
SN76489; `tools/audio/ym/ym_ref.py IMAGE --vgm OUT` writes the same bytes from the Python reference. File:

- Header (256 bytes, others 0): `"Vgm "`, 0x04 = file size − 4, 0x08 = 0x171, 0x0C = SN76489 clock (3579545 NTSC,
  3546895 PAL), 0x18 = total samples (44.1 kHz), 0x1C = loop offset − 0x1C and 0x20 = loop samples (both 0
  without a loop), 0x24 = 60 / 50 (rate), 0x28 = 0x0009 (SN76489 feedback), 0x2A = 16 (shift register width), 0x2B =
  0 (flags), 0x2C = YM2612 clock (7670453 / 7600489), 0x34 = 0xCC (data at 0x100). `ym_vgm_render` writes no GD3 tag;
  the hosts' entry point appends one and sets 0x14.
- Data: the machine reset writes, then the player's reset batch; then per tick: at the first arrival at (loop order,
  row 0, tick 0) the loop point and a dump of the player's register state (`YM0 $22`; per FM channel 0..5: operators
  1..4 `$30`-`$90`, `$B0`, `$B4`, `$A4`, `$A0`; per tone 0..2 its period as two bytes and its attenuation; the noise
  control and attenuation 3 — all values, including zeros), so each pass of the loop starts from the same registers;
  the key-off phase writes; if there were any, a one-sample wait (`0x70`, so the key-off reaches VGM players' chips
  one sample before a key-on: a retrigger); the other writes; the rest of the tick's wait: 735 (`0x62`, NTSC) or 882
  (`0x63`, PAL) samples, after a one-sample gap 734 / 881 (`0x61 nn nn`). Writes are `0x52 reg val` (port 0), `0x53
  reg val` (port 1) and `0x50 byte` (PSG). The song ends at the second arrival at the loop point (or at the tick limit,
  then without a loop), then `0x66`.

### Position, seek and live edits

- `position()`: above (the player runs one tick ahead; the heard position lags behind heavy frames).
- `row_serial()`: the player ticks encoded at tick 0 up to the heard position's tick (`ymm_row_serial`, stored beside
  each FIFO position), so a lag that repeats `(order, row, 0)` is not a new row and a speed-1 row jumping to itself is.
- `start(order, row, max_ticks)`: machine reset (chips reset, SGDK's reset writes, driver boot), then the **player and
  the encoder's state** run alone from the song start until the next tick starts (order, row) (`CHIP_SEEK_FROM_START`;
  0.18 µs per tick: `ymx_absorb` keeps the encoder state without encoding, exact because the clean and XGC states are
  both every register written so far); then, from `YMM_PREROLL_TICKS` (60) ticks before the target, the chips receive
  the player's register dump (and a key-on for its keyed FM channels) directly (the 68000's part; the YM address latch
  is left on `$2A` as the driver expects) and the last 60 ticks stream through the driver with the output discarded.
  The player and the stream after a seek equal straight play's; the sound is **identical to straight play when the
  target is within 60 ticks of the start** (the whole way streams from the song start, host test: bit-identical); beyond,
  notes keyed earlier restart 1 s before the target. Measured on the test song: each channel's 20 ms loudness
  envelope over 0.5 s stays 0.012-0.028 from straight play (channels with at least 2 % of the energy; limit 0.1 —
  FM release tails and SSG-EG phases of notes keyed before the pre-roll), the mix 0.005-0.103 (limit 0.15: it also
  depends on the channels' relative phases, as PSG counters and FM phases restart at the restore; the OPL3's mix
  measures 0.026), loudness 0.5-1 s after within 0.8-1.25×. Beyond `max_ticks` the player is placed at the order
  start (`CHIP_SEEK_FROM_ORDER`, as the OPL3). Cost: ~20 ms on the dev PC (60 ticks of driver and chips, the
  driver's idle work replayed: 36-44 ms stepped); suggested `seekTicks` 12000 (2.5 ms more). The hosts seek on a
  spare machine off the audio thread (Android's control thread, the Deck's main thread), as for every system.
- `apply_image` (realtime-safe, 2.7 µs mean / 13 µs worst): `ymp_relocate`, the OPL3's rules, plus: a changed header
  LFO byte replaces the player's LFO value. The player is one tick ahead, so an edit is heard from the tick after next
  (≤ 2 ticks, 33 ms). A changed region applies at the next start.

### Audition

`audition(channel 0..9, note 0..95, slot, fine −128..127)`: FM slots on channels 0-5, PSG slots on 6-9 (else
`CHIP_ERR_IMAGE`). The (spare) machine is reset and the driver booted (0.35 ms); the stream starts with the audition
batch alone (the encoder knows the reset registers, so the frame is light and the driver executes it at once); ticks
then only update (sweeps and envelopes play). `release()`: 0 before any tick or when the note is already silent (FM:
every carrier's envelope at `0x3FF` in ymfm; PSG: phase off and attenuation 15 on the chip); else the key-off goes
into the next frame and `tick` returns 0 once the driver has executed it and the note is silent, or after 10 s.
Measured: A-4 440.126 Hz (FM sine, F-number 1083) and 440.397 Hz (PSG period 254); release 112 ticks (sine, RR 6),
30 ticks (PSG square: the volume envelope's 30-value tail), 1-3 ticks for a PSG or Noise instrument without a release
point (silent at key-off, once the driver has executed it); the Noise channel's pitched noise follows the note and
the relative pitch envelope (PSG 3's period).

### Mute, mix and ops behaviour

`mute(channel 0-9)` leaves the channel out of the chips' output only: an FM channel contributes what the chip outputs
for a channel with its `$B4` output bits clear (the DAC offset; `ym/ym_ymfm.cpp` sums ymfm's channels itself), a PSG
channel contributes nothing; the player, the driver and the chips run on, so the stream and the VGM are unchanged and
unmuting resumes mid-note. `start`/`audition` clear mutes.

**Mix**: ymfm's YM2612 sum × 192/256 (one full-level FM channel peaks at 0.125 of full scale, six at 0.75) and the PSG
level table (2 dB steps, 4096 at attenuation 0) × 65/256, both through blip_buf, float = sample / 32768. The FM:PSG
balance is libvgm's default (VGMPlay volumes YM2612 0x200 : SN76489 0x100): a full-level PSG square has 0.35 times
the rms of a full-level FM sine. Test song: rms 0.07, peak 0.39.

| Op | Behaviour |
|---|---|
| `load_image` | copy, then `ym_load_image`; `image_storage` = `sizeof(YmImage)` |
| `start(order, row)` | above; `seek_mode` from start or order |
| `apply_image` | above; ignored while auditioning |
| `audition`, `release` | above |
| `mute(channel 0-9)` | above |
| `position` | the next tick the driver executes |
| `tick` | one vertical interrupt: 801-802 frames at 48 kHz NTSC (965-966 PAL), ≤ 1933 at 96 kHz PAL; 0 after a released audition ended; `CHIP_ERR_FAULT` if the driver stopped playing |

**Kotlin** (`PlaybackEngine` for `"ym2612-fm"`): `imageSize` 65536, `seekTicks` 12000, `note(n)` = n (absolute
0..95), `fine` = the system's fine pitch in 1/64 semitones (0 by default), `ticksPerSecond` = 59.92 (NTSC) or 49.70
(PAL) — the console's vertical interrupt; the VGM's 60/50 Hz is its own convention. Validation should mirror
`YmPlayerImage.LOADER_PROBLEMS` and warn about heavy rows (driver lag), PSG 3 notes under pitched noise, locks without a
note and PSG notes below A-2.

### Exports on the hosts

`ym_export(image, 65536, format, max_ticks, title, author, date, &size, &problem)` (`ym/ym_export.h`, self-contained
for cinterop; `ym_export_free`): format `YM_EXPORT_VGM` (the VGM above plus a GD3 1.00 tag: system "Mega Drive (Thor
Tracker)", converter "Thor Tracker", date today unless given), `YM_EXPORT_XGM` (`.xgm`: xgmtool's XGM of that VGM, the
same GD3 tag appended and flag bit 1 set — the input of SGDK's `rescomp` `XGM` resource) or `YM_EXPORT_XGC` (`.bin`:
xgmtool's compiled XGC, what `XGM_startPlay` plays; no tag). Rejected images report the loader's reason ("image size",
"format", "out of memory", "frame too large" otherwise). Android: JNI
`Java_org_thortracker_audio_ChipAudio_nativeYmExport(image, format, maxTicks, title, author, date)` (strings as UTF-8
byte arrays; `IllegalArgumentException(problem)` on rejection) in `audio_jni.cpp`, declared in `ChipAudio`'s companion,
which registers it as `YmExport.renderer`; Deck: `ym_export.h` is in `decknative.def`, `DeckYmExport` (`DeckVgm.kt`)
is registered by `main`. The 30 s test song renders its XGM in 1.2 ms on the dev PC.

### Preparing the driver, tables and fixtures

`python3 tools/audio/prepare_ym.py [--sgdk DIR]` (needs `tools/audio/fetch_sgdk.sh`: clones the pinned SGDK into
`~/.cache/thor-tracker/sgdk-9a78a441` and builds sjasm and xgmtool) checks the vendored `third_party/sgdk-xgm` files
against the checkout, assembles the driver with sjasm and compares it with `drv_xgm` in SGDK's `lib/libmd.a`, then
writes `generated/xgm_driver_image.h`, `generated/ym_tables.h` (from `ym_ref.py`'s table functions),
`core/.../backend/YmPlayerImage.kt` (layout, limits, effect codes, timing, export formats, F-number tables and
`LOADER_PROBLEMS` parsed from the C headers) and `app/src/test/cpp/ym_test_song.h` (the test images: the song, the
same song laid out anew, a note edit, a tempo edit, the song cut to two orders and a PAL version; the reference
player's per-tick writes, key-off phase lengths and positions for 1500 ticks of four of them; the reference VGM's
size, FNV-1a hash and loop for NTSC and PAL; xgmtool's XGM and XGC of those VGMs; byte offsets for the corruption
checks). Rerun after changing the driver pin, the player specification, the reference player or the test song.

### Verification

- The test song (`tools/audio/ym/ym_song.py`, 4 orders of 16/32/16/24 rows, `B01` back to the loop order 1): all 8
  algorithms with feedback (FM 6 included), LFO (header, `Lxx`, AMS/FMS, operator AM), SSG-EG, DT/MUL variety, every
  effect where allowed, 4 columns, OFF cells, retriggers, an FM kick sweep, PSG envelopes (a volume loop before its
  release point, release tails, ends held at 0 and above 0, no envelope at all, an arpeggio envelope, absolute and
  relative pitch envelopes, one envelope shared by two instruments), white/periodic noise at fixed rates and pitched
  (muting PSG 3), runs, shared patterns.
- `tools/audio/run_native_host_test.sh [dir]` runs `app/src/test/cpp/ym_machine_test.c` — 2026-11-08: **500 checks, 0
  failures** (Game Boy 145, NES 336, SID 151, OPL3 282 unchanged): pitch tables (A-4 = 440.13 Hz NTSC / 440.14 PAL, FM
  notes from C-1 within 0.66 cents, PSG A-2..A-6 within 12 cents); 67 single-byte corruptions rejected with their
  reason (envelopes included); **the player's writes and positions equal the independent Python reference's in all
  1500 ticks, NTSC and PAL**; the machine's player (one tick ahead), its heard positions (lagging behind heavy frames,
  max 3 ticks) and **the driver's chip writes equal to the streamed XGC frames, in order** (18501 writes, the DAC kept
  off); **the engine's XGC stream equals xgmtool's XGC of the reference VGM for the whole first pass** (9933 bytes); a
  3126-tick run across a ring wrap; **pass replay = every cycle stepped** (4817 ticks: samples, chip writes,
  positions; Z80 state and RAM at 4537 tick ends; 95 % of the Z80's cycles replayed; 40 passes, 444 KiB); 30 s NTSC
  and PAL non-silent in every second, no clipping, bit-identical on a second start; tick cost flat (≤ 3× the mean);
  loop passes step through identical positions = the VGM loop; eight seeks with exact positions and writes, sound
  inside the pre-roll bit-identical, beyond within the per-channel and mix envelope limits; the order fallback; live
  edits (same and re-laid-out image before every tick, a note edit, tempo edits ahead of and behind the playhead, the
  song cut under the playhead); audition (A-4 on FM and PSG measured, NTSC and PAL, fine +64, every algorithm, the
  kick sweep, release incl. the PSG release tail and instruments without a release point, pitched noise on the Noise
  channel, argument limits); mute (writes unchanged, silence, resume, FM 6/Noise/PSG alone, before the first tick,
  after a seek); PAL timing (71364 Z80 cycles per tick); **VGM bytes = the reference's (NTSC, PAL), XGM and XGC bytes
  = xgmtool's (NTSC, PAL)**; the export entry point (GD3 fields incl. 4-byte UTF-8, default date, rejections); the
  ops table. Evidence: `ym_song.wav`, `ym_song_pal.wav`, `ym_tone_fm.wav`, `ym_tone_psg.wav`, `ym_trace.txt`,
  `ym_song.vgm`, `ym_song.xgm`, `ym_song.bin` (XGC), `ym_image.bin`.
- `tools/audio/ym_reference_check.py <dir> [--xgmtool PATH] [--vgm2wav PATH]` (developer evidence) — 2026-11-08: the
  reference reproduces the engine's trace in 1500/1500 ticks and the VGM byte for byte; the VGM parsed from the format
  alone (header, waits = 346185 samples, loop point on the dump, stream = trace); xgmtool's XGM/XGC of the VGM = the
  engine's exports; played by libvgm's vgm2wav (local patch: env `VGM2WAV_YM2612_CORE`) over the first pass against the
  engine's render: Nuked-OPN2 envelope correlation 0.947, log-spectrum 0.788, same pitch class 91 %, level ×4.1;
  Genesis Plus GX 0.935 / 0.765 / 88 %. Per channel soloed (Nuked): envelope 0.98-1.00 except FM 2 (the bell and
  E-piano, where GPGX and Nuked themselves differ by 2-3×) and PSG 2 after the first pass (its volume slide runs across
  the loop: the VGM replays pass 1, the player plays on).
- `tools/audio/render_ym_image.sh image out.wav [seconds] [mute=190] [--vgm|--xgm|--xgc out]` renders an image through
  the ops table and the exports through `ym_export`.
- **Bench**: `tools/audio/build_ym_bench.sh [dir]` builds `tools/audio/ym_bench.c` for the host and as static arm64
  executables (`-O2`, `-O3`); `adb push` and run `ym_bench-arm64 [seconds] [rate] [paced_seconds]`: cold, warm and
  paced ticks with pass replay, the same machine with replay off (the spike's cost, for before/after on one device),
  live edits, auditions and seeks. Built 2026-11-08 into `/tmp/ym_bench2/` (the spike's in `/tmp/ym_bench/`).
- Not done: listening on hardware, a comparison with a real YM2612 or an emulator running a whole game.

### Hosts: threads and costs

Dev PC (`ym_bench-host`, 48 kHz, 30 s of the test song, 2026-11-08). Konkr, spike (before pass replay, measured by
the integrator): 1.9 ms per warm tick (Z80 and driver 1.3 ms of it), paced 6.9 ms mean / 10.5 ms worst, seek 3/20
112 ms. The devices have not run `/tmp/ym_bench2/` yet.

| Work | Thread | Dev PC |
|---|---|---|
| `tick` (player + encoder + driver + ymfm + PSG + resample) | audio callback | 353 µs mean (p99 532, worst 744); ymfm alone 235 µs, the rest (the driver's frame work stepped, its idle work replayed, PSG, player, XGC encoder, blip) 118 µs; paced at 59.92 Hz 487 µs mean (p99 885; the CPU clocks down between ticks); cold first 60 ticks 402 µs. Replay off (the spike): 695 µs warm |
| `apply_image` (relocating) | audio callback, tick boundary | 2.4 µs mean, worst 19 µs |
| machine creation | control | 0.4 ms (640 KiB replay arena reserved, touched as passes are kept) |
| `audition` setup (driver boot) | control, spare machine | 0.35 ms |
| `start` (seek) | control, spare machine | 10.7 ms within the first second (all through the driver), 20 ms for 3/20 (60 ticks through the driver; 36 ms stepped) + 2.9 ms for 12000 player ticks |

Expected on the Konkr from the spike's split: the Z80's 1.3 ms shrinks to its frame work (~8 % of its cycles) plus
replay bookkeeping, about 0.8 ms per warm tick (~5 % of a core) and a seek of ~45 ms; to be confirmed with the bench.

## OPM: Thor Tracker's own player on a YM2151 (`"ym2151-opm"`)

**Status: unverified source slice** (branch `opm-systems`): written without building, generating or running anything.
The YM2151 (OPM) is the Sharp X68000's sound chip (also many arcade boards); like the OPL3 it plays through **our own
player** (`opm/opm_player.c`, pure integer C without a chip) whose behaviour is the specification in this section;
`tools/audio/opm/opm_ref.py` implements the same specification separately in Python and the host test requires
identical register writes in every tick. Songs export as VGM (the player's register log) and WAV. This section is the
contract of the Kotlin system and lowering (`core` `system/opm`, `backend/OpmLowering.kt`); the numbers they share with
the engine are generated into `backend/OpmPlayerImage.kt` (below, "Preparing").

### Chip and clock

- **Core**: ymfm's `ym2151` (`third_party/ymfm/ymfm_opm.h/.cpp`, the files fetched at the vendored pin `81aec25c`,
  unmodified; `ymfm_opm.cpp` sha256 `2235d571…98b22dc`, `ymfm_opm.h` `cab75025…a9aee7b`), compiled once in
  `opm/opm_ymfm.cpp` (`#include "ymfm_opm.cpp"`; `ymfm_fm.ipp`, which `ym/ym_ymfm.cpp` also includes through
  `ymfm_opn.cpp`, holds only templates and inline functions).
- **Clock: 4 MHz**, the X68000's (its 16 MHz crystal / 4; MAME `src/mame/sharp/x68k.cpp`:
  `YM2151(config, m_ym2151, 16_MHz_XTAL / 4)`), not the 3.579545 MHz the YM2151 application manual's tables assume.
  Native rate = 4000000 / 64 = **62500 Hz** (32 operator slots × prescale 2), envelope generator every third sample.
- **Pitch at 4 MHz**: a key code sounds 1200 · log2(4000000 / 3579545) = **192.27 cents** above the manual's note
  (ymfm's phase-step table is David Viens' chip-verified table for the 3.579545 MHz nominal clock: key code 4/A =
  439.94 Hz there). The player subtracts `OMP_PITCH_OFFSET` = 123 sixty-fourths of a semitone (123.05 exact), so
  absolute notes keep Thor Tracker's true pitch (0 = C-0, 57 = A-4 = 440 Hz): A-4 = key code `$48` (4/G) + key
  fraction 5 = **439.88 Hz (−0.46 cents)**; every playable note within 4.4 cents of 12-TET A440 (the table's octave
  shift truncates in octave 0; computed from ymfm's table: `tools/audio/opm/opm_reference_check.py --pitch`).
- **Playable notes** at 4 MHz: key codes C#0 (fraction 0) … C-8 (fraction 63) = pitch units 187..6330, so notes
  **D#0 (3) .. D-8 (98)** (`OM_NOTE_MIN`, `OM_NOTE_MAX`); pitch effects clamp there.
- **Tick** = 1/60 s / multispeed (header byte 5, N = 1..8): the player runs once, then the chip renders 4000000 / (64 ·
  60 · N) samples (exact integer accumulation: 1041/1042 at N = 1). As the OPL3, the player's tempo is its own
  host timer, not the chip's timer B (timer B only raises status flags; nothing sounds from it, so it is not used).
- **Key-off phase**: as the OPL3's, the machine writes the batch's key-offs, renders one native sample, then the rest
  (ymfm samples the key state once per sample in `fm_operator::prepare`, so a key-off followed by a key-on in the same
  instant would not retrigger).
- **Timers and CSM**: ymfm leaves timers to its host; `opm/opm_ymfm.cpp` runs both on the sample clock (a timer of
  *p* · 64 input clocks — ymfm's `period · OPERATORS · prescale` — expires after *p* samples, checked before each
  sample is generated) and reports expiry to ymfm (`engine_timer_expired`), which in **CSM mode** (`$14` bit 7) keys
  on all four operators of **all eight channels** for one sample at every timer A overflow (`CSM_TRIGGER_MASK` =
  all channels on the OPM; the key returns to each channel's own state at the next sample). Unmodified ymfm only
  re-evaluates key states when it re-prepares its operators (after a register write, or every 4096 samples), so after
  a sample in which timer A expired the wrapper calls `ym2151::invalidate_caches()`: the next sample re-prepares and
  the CSM key-on lasts one sample, as on the chip (a momentary pulse, as Nuked-OPM models it). A channel keyed by the
  player is unaffected (already on); a released channel restarts its attack. Timer A period = (1024 − NA) samples:
  **62500 / (1024 − NA) Hz**, 61.0 Hz (NA 0) to 62.5 kHz (NA 1023).
- **Output**: ymfm's 16-bit sum (14-bit channels, then the YM3012 DAC's 10.3 floating-point round trip) per channel
  pair, resampled band-limited with blip_buf 1.1.0 from 62500 Hz to the host rate (its ~15 Hz high-pass), float =
  sample / 32768. Muted channels are left out of ymfm's output sum (`fm_engine::output` channel mask) before the DAC
  round trip; registers and the player are untouched.
- **Chip reset**: `start` and `audition` rebuild the chip in place (`omc_reset`: ymfm's `reset()` leaves the envelope,
  LFO and noise counters running, and a reused machine must sound like a fresh one), as the YM2612's machine does;
  ymfm allocates its channel and operator objects then. Both run off the audio thread (`chip_engine.h`); `tick`,
  `apply_image`, `mute` and `release` never allocate.
- **Not used**: the CT1/CT2 output pins (`$1B` bits 6-7; on the X68000 they select the ADPCM clock and the floppy
  drive's ready signal — not sound), timer B, IRQs, the test register `$01` (its undocumented bit 1 holds the LFO in
  reset; not exposed), the OKI MSM6258 ADPCM of the X68000 (a separate chip, not the OPM).

### Channels and shared resources

8 tracker channels = the chip's 8 channels, `FM 1`-`FM 8`, each a 4-operator voice. **No chord effect, no voice
allocation**: every channel is one hardware channel. The YM2151 has state shared by every channel, which is song
data (header) and changed by global effects, never per-instrument data pretending to be per voice:

| Shared resource | Registers | Song start (header) | Effect | Per channel |
|---|---|---|---|---|
| LFO frequency | `$18` LFRQ 0..255: 62500 · ((16 + LFRQ mod 16) << (LFRQ / 16)) / 2^30 Hz (0.0009 Hz .. 59.1 Hz at 4 MHz) | byte 16 | `Lxx` | — |
| LFO waveform | `$1B` bits 0-1: 0 saw, 1 square, 2 triangle, 3 noise | byte 7 | `Wxx` | — |
| LFO amplitude depth AMD | `$19` (bit 7 clear) 0..127 | byte 17 | `Txx` | AMS 0..3 (`$38` bits 0-1) and each operator's AMS-EN (`$A0` bit 7) |
| LFO pitch depth PMD | `$19` (bit 7 set) 0..127 | byte 18 | `Vxx` | PMS 0..7 (`$38` bits 4-6) |
| Noise generator | `$0F` bit 7 enable, bits 0-4 NFRQ: 4000000 / (32 · (32 − NFRQ)) Hz | off | `Nxx` (FM 8 only) | **only FM 8's operator 4 (C2)** plays it |
| Timer A / CSM | `$10` NA high 8 bits, `$11` low 2 bits; `$14` bit 7 CSM, bit 0 load timer A | bytes 20-21, 13 | `Qxx` (high byte), `Xxx` (mode) | all channels |

Noise is modelled as what it is: the instrument flag "operator 4 is noise" with its frequency, legal on FM 8 only (the
loader rejects a noise instrument named on another channel, and `Nxx` off FM 8); a key-on on FM 8 sets `$0F` from its
instrument (noise on with its NFRQ, or noise off keeping the frequency).

### Image layout (what the Kotlin lowering produces)

`image_size` = 65536 bytes, little-endian, every pattern reference an offset into the data region, so any layout that
passes `om_load_image` (`opm/opm_image.c`) relocates. Constants: `opm/opm_image.h` (`OM_*`); the reference builder is
`tools/audio/opm/opm_song.py` (`build()`). The loader rejects anything off-contract with a reason and the byte
(`OmInfo.problem`, `problem_offset`): the Kotlin validator mirrors its checks with user-facing messages.

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | `"TTOM"` |
| 4 | 1 | layout: 1 |
| 5 | 1 | multispeed N, 1..8 (player ticks per 60 Hz frame) |
| 6 | 1 | speed (ticks per row) at the song start, 1..255 |
| 7 | 1 | LFO waveform 0..3 |
| 8 | 2 | order count, 1..256 |
| 10 | 2 | loop order (< count) |
| 12 | 1 | instrument count I = highest slot present, 0..64 |
| 13 | 1 | CSM at the start: 0 off, 1 on |
| 14 | 2 | data size D: bytes of pattern data used, ≤ 59072 |
| 16 | 1 | LFO frequency LFRQ 0..255 |
| 17 | 1 | AMD 0..127 |
| 18 | 1 | PMD 0..127 |
| 19 | 1 | 0 |
| 20 | 2 | timer A NA 0..1023 |
| 22 | 42 | 0 |
| 64 | 256 | rows of order *o* at 64 + *o*: 1..255, 0 = 256 (Thor Tracker: ≤ 64); entries ≥ count 0 |
| 320 | 4096 | pattern of (order *o*, channel *c*) = u16 offset into the data region at 320 + 2 (8 *o* + *c*); entries ≥ count 0 |
| 4416 | 2048 | instrument slot *n* (1..64) at 4416 + 32 (*n* − 1) |
| 6464 | 59072 | data region: patterns, `[0, D)` |

**Instrument** (32 bytes; an all-zero record = no instrument; records above I must be zero). Operators are numbered
as the YM2151's algorithm diagrams: **operator 1 = M1, 2 = C1, 3 = M2, 4 = C2**.

| Byte | Field |
|---|---|
| 0 | flags: bit 7 = defined (1), bit 0 = noise (operator 4 plays the noise generator; FM 8 only); other bits 0 |
| 1 | `$20` bits 0-5: feedback (of operator 1) << 3 \| algorithm 0..7; bits 6-7 0 |
| 2 | `$38`: PMS 0..7 << 4 \| AMS 0..3; bits 7, 3, 2 zero |
| 3 | noise frequency NFRQ 0..31; 0 without the noise flag |
| 4 + 6 *k* | operator *k* + 1 (*k* = 0..3): `$40` (DT1 << 4 \| MUL; bit 7 0), `$60` (TL 0..127; bit 7 0), `$80` (KS << 6 \| AR; bit 5 0), `$A0` (AMS-EN << 7 \| D1R; bits 5-6 0), `$C0` (DT2 << 6 \| D2R; bit 5 0), `$E0` (D1L << 4 \| RR) |
| 28 | drum sweep start, s8 semitones relative to the note, −96..96 |
| 29 | drum sweep end, s8 semitones, −96..96 |
| 30 | drum sweep speed, 1/16 semitone per tick toward the end; 0 = no sweep (start/end ignored) |
| 31 | 0 |

**Register addresses**: channel *c* (0..7): `$20`/`$28`/`$30`/`$38` + *c*; operator *k* (0..3) of channel *c*: bank +
8 · slot + *c* with slot = (0, 2, 1, 3)[*k*] (M1, C1, M2, C2 occupy slots 0, 2, 1, 3: ymfm's `operator_map` lists a
channel's operators as opnum *c*, 16 + *c*, 8 + *c*, 24 + *c*). Key register `$08` = operator mask << 3 \| channel,
mask bit *k* = operator *k* + 1 (`$78` = all four). **Carriers** (bit *k* = operator *k* + 1, the OPN family's
routing): algorithms 0-3 `$8`, 4 `$A`, 5 `$E`, 6 `$E`, 7 `$F`.

**Patterns**: decoded from the offset for exactly rows(*o*) rows (a run longer than the order's remaining rows is cut
there); every row read must lie inside `[0, D)`. Row encoding, as the OPL3's:

| Bytes | Meaning |
|---|---|
| `0x80 \| k` | k + 1 empty rows (k 0..127) |
| `m` (0x01..0x13), then data | bit 0: a note byte follows; bit 1: an instrument byte follows; bits 2-4: effect count 0..4; bits 5-6 zero, m ≠ 0 |
| note | 3..98 = absolute note D#0..D-8 (0 = C-0, 57 = A-4), `0xFE` = OFF (key off) |
| instrument | slot 1..64, defined; a noise instrument only on channel 7 (FM 8) |
| effects | per effect column in order: code (0..35 = `0`-`9`, `A`-`Z`), parameter |

Load-time parameter checks: `Bxx` < order count, `Cxx` ≤ 7F, `Exx` ≤ 7, `F00` rejected, `Hxy` x ≤ 7 and y ≤ 3,
`Ixx` ≤ 7F, `Mxy` x 1..4, `Nxx` ≤ 1F and only on channel 7, `Txx` ≤ 7F, `Vxx` ≤ 7F, `Wxx` ≤ 3, `Xxx` ≤ 1; header:
magic, layout, multispeed 1..8, speed ≥ 1, wave ≤ 3, CSM ≤ 1, AMD/PMD ≤ 127, timer A ≤ 1023, reserved bytes 0. Codes
without an effect (`5`, `6`, `7`, `9`, `J`, `K`, `O`, `R`, `U`, `Y`, `Z`) are rejected ("unknown effect code"); the
other effects take any parameter. `om_load_image` derives (`OmInfo`) the order count, loop, multispeed, speed, rows per order
and the speed each order starts with in sequential straight play (header speed, then the last `Fxx` of each order:
rows, then channels, then columns; jumps ignored).

**What the lowering writes** from a `Cell` with up to 4 effect columns: note `EMPTY` → no note byte, `CUT` → `0xFE`,
an absolute note → itself; instrument 0 → no byte; effect columns in column order, blank columns (code 0, parameter 0)
omitted; empty rows in runs; byte-identical (order, channel) encodings stored once in first-use order (orders, then
channels); every filled instrument slot written (the instrument count is the highest).

### Player

Units: **pitch** in 1/64 semitone, 0 = C-0; notes are pitch = note · 64.

**Pitch → registers** (`omp_key_code`): p = clamp(pitch, 187, 6330); c = p − 123; n = c >> 6 (1..96: semitones above
C-0 in the 3.579545 MHz table), kf = c & 63; i = n − 1; octave = i / 12, s = i mod 12; note code = s + s / 3 (the
gappy 0, 1, 2, 4, 5, 6, 8, 9, 10, 12, 13, 14 = C#, D, …, B, C of the next octave); `$28 + c` = octave << 4 \| note
code, `$30 + c` = kf << 2.

**Writes**: every write goes through a shadow of the 256 registers (reset state 0); a write equal to its shadow is not
emitted. `$19` is two registers: AMD is shadowed at `$19` (written `$19` = AMD), PMD at the unused `$1A` (written
`$19` = `$80` \| PMD). `$08` is not shadowed: the player writes it when a channel's key state changes. A batch is the
key-off phase — every `$08` write with operator mask 0, in the order made — followed by every other write in the order
made (`OmPlayer.offs` = length of the first part).

**Shared writes** (the "globals", in this order, each through the shadow): `$18` = LFO rate, `$1B` = LFO waveform,
`$19` = AMD, `$19` = `$80` \| PMD, `$0F` = noise, `$10` = timer A >> 2, `$11` = timer A & 3, `$14` = `$81` if CSM else
`$00` (CSM mode with timer A loaded; timer A's flag and IRQ stay off).

**Reset** (start, audition): all state cleared; channels: instrument 0, volume 7F (vol8 1016), pan `$C0` (both),
cut −1, not keyed; shared state from the header (LFO rate, waveform, AMD, PMD, CSM, timer A; audition: CSM off), noise
`$00`; speed = header speed; position (0, 0, tick 0). Writes: `$20 + c` = `$C0` for c = 0..7 (the chip resets these to
0 = both outputs off; ymfm to `$C0`, so the explicit writes make every chip and VGM player agree), then the globals.

**Tick** (positions are (order, row, tick-in-row); the host's position is the tick about to run):

1. The globals (emitted only where they changed: a live edit).
2. Tick 0 of a row: read every channel's row at (order, row). Then, over channels 0..7 and their effect columns in
   order: `Fxx` sets the speed (the last one wins and applies to this row); `Bxx`/`Dxx` record the next position: `B`
   only → (xx, 0); `D` only → (next order, xx), the next order being order + 1 or, past the last one, the loop order;
   both → (B's order, D's row). Then for each channel 0..7: its cut is cleared; if the row has `Gxx` with xx > 0 (the
   last `G` of the row) its row is delayed to tick xx; otherwise the row runs now. Other ticks: each channel whose
   delayed row is due (tick = xx) runs it, channels in order.
3. Each channel whose cut tick (`Sxx`) equals this tick is keyed off, channels in order.
4. Each channel 0..7 that has keyed any note since reset is updated (below), channels in order.
5. tick + 1; when it reaches the speed: tick 0 and the next row — the recorded jump (row clamped to the target order's
   last row), else row + 1, after the last row of an order the next order, after the last order the loop order.
   **Loop speed**: whenever the next row is row 0 of the loop order the speed becomes `OmInfo.start_speed[loop]` (an
   `Fxx` on that row still applies at its row start).

**Running a row** of a channel: if the row has a note (3..98), the arpeggio and fine pitch are cleared; an instrument
byte sets the channel's instrument; the effect columns run in order (table below; `B`, `D`, `F`, `G` already acted);
then the note: OFF keys the channel off; a note with portamento active (`3xx`, xx > 0) while the channel is keyed only
sets the glide target (no key-on: the row's locks are not used); any other note keys on.

**Key-on** (note *n*, channel *c*): if the channel's instrument is not a defined slot, nothing happens. Else the
channel is keyed off (below); the instrument's operator registers, connection (byte 1), sensitivity (byte 2) and the
algorithm's carriers are copied; on FM 8 the noise becomes `$80` \| NFRQ for a noise instrument, else noise & `$1F`
(off, frequency kept); then the row's locks in column order: `Exx` connection = (connection & 7) \| xx << 3; `Ixx` every
modulator's TL = 7F − xx; `Mxy` operator x's MUL = y (DT1 kept); `Hxy` sensitivity = x << 4 \| y; `Nxx` (FM 8) noise =
(noise & `$80`) \| xx. Writes, operator by operator (1..4): `$40`, `$60` (carriers: TL + 127 − volume, at most 127),
`$80`, `$A0`, `$C0`, `$E0`; then `$20 + c` = pan \| connection, `$38 + c` = sensitivity; on FM 8 the globals. Pitch
state: base = target = 64 *n*, slide offset 0, arpeggio step 0, vibrato phase 0, sweep offset = 64 · start (when the
sweep speed is not 0, else 0), sweep end 64 · end, sweep step 4 · speed. The key-on is pending (written by the update).

**Key-off**: a pending key-on is dropped; if keyed (on the chip), `$08` = *c* (operator mask 0; key-off phase), not
keyed.

**Update** (per tick, after the rows): pitch = clamp(base + 64 · arpeggio + fine + slide + vibrato + sweep, 187, 6330),
arpeggio = 0, x, y for step 0, 1, 2 of `0xy`, vibrato = floor(SINE[phase] · depth / 16) (arithmetic right shift) with
`SINE[i]` = floor(127 sin(2πi/64) + 0.5) (the OPL3's table, written out in `opm_player.c` and `opm_ref.py`). Writes:
the carriers' `$60` (operators 1..4, TL + 127 − volume, ≤ 127), `$28 + c`, `$30 + c`, then a pending key-on: `$08` =
`$78` \| *c*, keyed. Then the effects advance: arpeggio step (mod 3) if an arpeggio is set; slide offset ± speed
(clamped ±6144) for `1xx`/`2xx`; base toward target by the speed (no overshoot) for `3xx`; vibrato phase + speed (mod
64) when speed and depth are set; sweep offset toward the end by its step (no overshoot); vol8 + x − y (clamped
0..1016) for `Axy`, volume = vol8 >> 3. A released note keeps updating (its release tail follows slides and vibrato).

### Effects (what Kotlin's effect specs declare for `"ym2151-opm"`)

Names are one character (`docs/BACKENDS.md`, "Effect columns"); up to 4 columns per channel. "Note" = applies to the
note keyed on this row only (parameter lock); "now" = from this row on; "shared" = the chip's one resource, for every
channel, written at once (the globals) and kept until changed.

| Effect | Parameter | Meaning |
|---|---|---|
| `0xy` arpeggio | x, y semitones | note: steps note, +x, +y every tick; the next note without `0xy` plays plain (also sets the step to 0; on a row without a note it changes the sounding note's arpeggio) |
| `1xx` slide up | 1/64 semitones per tick | now: until `100`/`200`/`300`; a new note restarts the offset at 0 but keeps sliding |
| `2xx` slide down | 1/64 semitones per tick | now: as `1xx`, down |
| `3xx` portamento | 1/64 semitones per tick | now: while active a note on a keyed channel glides there without key-on; `300` (or `1xx`/`2xx`) ends it |
| `4xy` vibrato | x speed 0-F, y depth 0-F | now: the player's own vibrato (depth F ±1.86 semitones), independent of the chip's LFO; `4x0` off; restarts with each note |
| `8xy` pan | x ≠ 0 left, y ≠ 0 right | now: `$20` bits 6 (left) / 7 (right), written at once to the sounding note; `800` silences |
| `Axy` volume slide | x up, y down, 1/8 step per tick | now: until `A00` (`A01`: 127 steps in 16.9 s) |
| `Bxx` jump | order | after this row play order xx row 0 (with `Dxx`: its row) |
| `Cxx` volume | 00-7F | now: channel volume (start 7F), 0.75 dB per step on the carriers' TL |
| `Dxx` break | row | after this row play row xx (clamped) of the next order (`Bxx`'s with both) |
| `Exx` feedback | 0-7 | note: operator 1 feedback |
| `Fxx` speed | 01-FF | now, global, this row included; the last of the row wins |
| `Gxx` note delay | ticks | the channel's row (note, instrument, its effects except `B`/`D`/`F`) runs at tick xx; never if xx ≥ the speed |
| `Hxy` LFO sensitivity | x PMS 0-7, y AMS 0-3 | now: the channel's `$38` at once (the sounding note) and the lock of a note on this row; the next note without `H` takes its instrument's |
| `Ixx` brightness | 00-7F | note: TL of every modulator = 7F − xx (7F brightest); algorithm 7 (no modulators) = no effect |
| `Lxx` LFO frequency | LFRQ 00-FF | shared: `$18` |
| `Mxy` ratio | operator x 1-4, MUL y 0-F | note: frequency multiplier (0 = ×½), DT1/DT2 kept |
| `Nxx` noise frequency | NFRQ 00-1F | FM 8 only: shared `$0F` bits 0-4 at once (noise on/off stays as FM 8's note set it) and the lock of a note on this row |
| `Pxx` fine pitch | 80 = none | xx − 80 in 1/64 semitones (±2 semitones) until the next note |
| `Qxx` timer A | NA high byte | shared: `$10` (CSM rate 62500 / (1024 − NA) Hz, NA = xx · 4 + the low 2 bits, from the next overflow) |
| `Sxx` note off | tick | key off at tick xx of this row (release phase) |
| `Txx` LFO amplitude depth | AMD 00-7F | shared: `$19` |
| `Vxx` LFO pitch depth | PMD 00-7F | shared: `$19` \| `$80` |
| `Wxx` LFO waveform | 00 saw, 01 square, 02 triangle, 03 noise | shared: `$1B` |
| `Xxx` CSM | 00 off, 01 on | shared: `$14` (`$81` on, `$00` off): at each timer A overflow every channel's four operators are keyed on for one sample |

Shared effects run in the row order of the channels (channels 0..7, columns in order), so the last one in a row wins;
each sets the player's shared value and writes the globals at once. FM locks (`E`, `I`, `M`) apply at key-on only.
Volume, pan, `H` and the shared registers change sounding notes at once. Drums are ordinary instruments with the sweep
(pitch) and, on FM 8, noise.

### Position, seek and live edits

- `position()` is the player's (order, row, tick).
- `start(order, row, max_ticks)`: as the OPL3's (`OMM_PREROLL_TICKS` = 60 × multispeed, `CHIP_SEEK_FROM_START`
  within `max_ticks`, else the order fallback `CHIP_SEEK_FROM_ORDER`, its own bound 256 × 256 ticks, a row behind a
  `Dxx` of the same order unreachable = `CHIP_ERR_IMAGE`). The restore onto the reset chip after the player-only part
  writes: `$0F`, `$10`, `$11`, `$18`, `$19` (AMD), `$19` (`$80` \| PMD), `$1B`, `$14`, then `$20`-`$27` (always),
  `$28`-`$3F`, every operator register `$40`-`$FF` (non-zero values, as everything except `$20`-`$27`), then `$08` =
  `$78` \| *c* for every keyed channel. Within the pre-roll the chip state equals straight play's when the target is
  ≤ 1 s from the start; beyond, envelopes, the LFO phase, the noise generator and the CSM timer's phase restart 1 s
  before the target (the same approximation as the OPL3's).
- `apply_image` (live edit at a tick boundary, realtime-safe): the OPL3's rules 1-4 (clamping, speed, a started row's
  jump and delays re-read, sounding notes keep their registers), and (5) multispeed follows the header from the next
  tick; (6) each shared header field (LFO rate, waveform, AMD, PMD, CSM, timer A) whose value differs between the old
  and the new image sets the player's shared value to the new one (written by the next tick's globals) — an edit of the
  song's start values is heard at once, while values set by effects persist when the start values are not edited.

### Audition

`audition(channel 0..7, note 3..98, slot 1..64, fine −128..127)`: the machine is reset (header LFO, **CSM off**: CSM
would retrigger the released note forever) and the player keys on the note with the slot's instrument, no locks,
`fine` added like `Pxx`; ticks only update pitch and volume (no rows). A noise instrument off channel 7, an undefined
slot or arguments outside these ranges are `CHIP_ERR_IMAGE`. `release()`: 0 before any tick or when every carrier's
envelope attenuation is ≥ `0x380` (ymfm's `EG_QUIET`, where it stops computing the operator); else the player's key-off
batch is written to the chip at once (as the OPL3's), returning 1, and `tick` returns 0 once every carrier is quiet or
after 10 s.

### Mute and ops behaviour

`mute(channel 0-7)` leaves the channel out of the output sum (ymfm keeps computing it, so feedback and envelopes run as
unmuted); the player's writes and the VGM are unchanged. `start`/`audition` clear mutes.

| Op | Behaviour |
|---|---|
| `load_image` | copy, then `om_load_image`; `image_storage` = `sizeof(OpmImage)` |
| `start`, `apply_image`, `audition`, `release`, `mute`, `position` | above |
| `tick` | one tick: 800 frames at 48 kHz (multispeed N: 800 / N), ≤ 1601 at 96 kHz; 0 after a released audition ended |

**Kotlin** (`PlaybackEngine` for `"ym2151-opm"`): `imageSize` 65536, `seekTicks` 12000, `note(n)` = n, `fine` = the
system's fine pitch in 1/64 semitones, `ticksPerSecond` = 60 × multispeed.

### VGM export

`om_vgm_render` (`opm/opm_vgm.c`) runs the player headless and logs its writes as a VGM 1.71 for one YM2151;
`tools/audio/opm/opm_ref.py IMAGE --vgm OUT` writes the same bytes from the Python reference.

- Header (256 bytes, others 0): `"Vgm "`, 0x04 = file size − 4, 0x08 = 0x171, 0x18 = total samples (44.1 kHz), 0x1C =
  loop offset − 0x1C and 0x20 = loop samples (both 0 without a loop), 0x24 = 60, **0x30 = 4000000** (YM2151 clock),
  0x34 = 0xCC (data at 0x100).
- Data: the reset batch; then per tick: at the first arrival at (loop order, row 0, tick 0) the loop point and a dump
  of the whole register state as the player's shadow holds it: `$0F`, `$10`, `$11`, `$18`, `$19` (AMD), `$19` (`$80`
  \| PMD), `$1B`, `$14`, `$20`-`$3F`, `$40`-`$FF` (all values including zeros), then `$08` = `$78` \| *c* for keyed
  channels and *c* for the others (*c* = 0..7); the key-off phase writes; if there were any, a one-sample wait (`0x70`);
  the other writes; the rest of the tick's wait (44100 / (60 · N) by exact accumulation; `0x62` for 735, `0x70`-`0x7F`
  for 1-16, `0x61 nn nn` otherwise). Writes are `0x54 reg val`. The song ends at the second arrival at the loop point
  (or the tick limit, then without a loop), then `0x66`.
- Hosts' entry point (`opm/opm_export.h`): `om_export_vgm(image, 65536, max_ticks, title, author, date, &size,
  &problem)`, as `o3_export_vgm` with GD3 system "OPM (Thor Tracker)", converter "Thor Tracker" (`vgm_gd3.c`).
- Kotlin (`system/opm/OpmVgm.kt`): `OpmVgm.export(song)` = lower + `OpmVgm.renderer.render(image, maxTicks,
  OpmVgmTags(title, author))`; Android registers JNI `ChipAudio.nativeOpmVgm`, the Deck `DeckOpmVgm` (cinterop).
  `maxTicks` = 60 × multispeed × 600 (ten minutes).
- WAV: the generic `chip_wav.c` over the `"ym2151-opm"` ops (no system code).
- VGM players' CSM: the VGM holds the `$10`/`$11`/`$14` writes only; whether and how long a player keys on at timer A
  overflows depends on its YM2151 core (stock ymfm, also inside MAME, keeps the CSM key-on until its next register
  write re-prepares the operators, so it may sound different from the engine). Not checked against any player.

### Preparing the fixtures and constants

`python3 tools/audio/prepare_opm.py` (pure Python 3) writes `core/src/commonMain/kotlin/org/thortracker/backend/OpmPlayerImage.kt`
(layout, limits, effect codes, carriers, pitch constants, seek and GD3 constants parsed from `opm_image.h`,
`opm_player.h`, `opm_machine.h` and `opm_vgm.c`, and `LOADER_PROBLEMS` from `opm_image.c`) and
`app/src/test/cpp/opm_test_song.h` (test images: the song, relaid, a note edit, a tempo edit, a cut song, x2
multispeed, a header-LFO edit; the reference player's per-tick writes, key-off phase lengths and positions for 1500
ticks; the reference VGM's size, FNV-1a hash and loop; byte offsets for corruption checks). `python3
tools/audio/opm/opm_golden.py` writes `core/src/jvmTest/resources/opm-golden/*.bin.gz` for `OpmGoldenTest`. No
generated table header: the vibrato sine is written out in both implementations.

### Verification (to be run by the parent; nothing here has been run)

- `tools/audio/run_native_host_test.sh [dir]` builds and runs `app/src/test/cpp/opm_machine_test.c`: key code mapping
  for every note, A-4 measured on a sine instrument at 439.88 Hz (± 0.5 Hz), every loader reason by single-byte
  corruption, the machine's writes against the Python reference for 1500 ticks with positions, loop passes against the
  VGM loop, seeks landing with straight play's writes, live edits (re-laid image, note and tempo edits, cut song, x2,
  header LFO edit), audition (pitch, `fine`, release, noise only on FM 8, CSM off), mute (silence, writes unchanged),
  LFO pitch modulation measured (PMD/PMS vibrato depth), noise on FM 8 (spectral flatness), CSM (retrigger rate =
  62500 / (1024 − NA) Hz measured from envelope restarts), the VGM bytes against the reference and the export entry
  point's GD3 tag. Evidence: `opm_song.wav`, `opm_tone.wav`, `opm_trace.txt`, `opm_song.vgm`, `opm_song.bin`.
- `wav_render_test.c` renders `opm-demo.omimg` (the demo) and `opm-shortest.omimg` (one row, A-4 held on FM 1) from
  `exportSamples`.
- `tools/audio/opm/opm_reference_check.py <dir> [--vgm2wav PATH] [--pitch]`: the reference player against the trace
  and the VGM, the VGM parsed from the format alone (header, waits sum, loop, command stream), the 4 MHz pitch table
  derived from ymfm's phase-step table; optionally libvgm's vgm2wav against the engine's WAV.
- `tools/audio/render_opm_image.sh image out.wav [seconds] [mute=...] [--vgm out.vgm]`.
- Kotlin: `OpmGoldenTest` (the lowering against the reference builder), `OpmModelTest`, `OpmEditorTest`.
- Not done: any build or run; listening; a real YM2151 or X68000; the device cost (ymfm's OPM is expected near its
  OPN2 cost, 235 µs per tick on the dev PC for the YM2612).
