# Audio engines

Preview playback runs the **actual pinned hUGEDriver 6.1.3 binary** on an embedded Game Boy
Color emulator (binjgb) inside `libthortracker_audio.so`, output through AAudio (Android) or SDL3
(Steam Deck). There is no second song sequencer: notes, instruments, effects, timing and channel
behaviour are whatever the driver code does on the emulated CPU and APU.

The NES system (`"nes-famistudio"`) follows the same principle: the FamiStudio sound engine 4.5.3
(FamiTracker tempo) runs on an emulated 6502 + 2A03 with all five channels, DPCM samples included;
its image layout, seek, live-edit and audition contract are in [NES](#nes-famistudio-sound-engine-on-a-6502--2a03-nes-famistudio).
The expansion variants run the same engine assembled with the selected chip:
[VRC6](#vrc6-the-same-engine-with-konamis-vrc6-nes-famistudio-vrc6),
[VRC7](#vrc7-the-same-engine-with-konamis-vrc7-nes-famistudio-vrc7), Namco
[N163](#n163-the-same-engine-with-namcos-163-nes-famistudio-n163-1--8) with 1..8 wavetable channels
(`"nes-famistudio-n163-1"`..`"-8"`),
[Sunsoft 5B](#sunsoft-5b-the-same-engine-with-sunsofts-5b-nes-famistudio-s5b),
[FDS](#fds-the-same-engine-with-the-famicom-disk-systems-sound-nes-famistudio-fds), and
[EPSM](#epsm-the-same-engine-with-a-ymf288-nes-famistudio-epsm).

The C64 SID system (`"c64-sid"`, design step 6, native spike) runs the GoatTracker 2.77 playroutine
on an emulated 6510 + reSID 1.0 (MOS 6581 or 8580, PAL); its image layout, timing, seek, audition and
what the Kotlin lowering must produce are in [SID](#c64-sid-goattracker-2-playroutine-on-a-6510--resid-c64-sid).

The OPL3 system (`"opl3-fm"`, design step 8, native spike) has no console driver to run: it plays songs with
Thor Tracker's **own player** (C, specified in this document and mirrored by an independent Python reference)
on a YMF262 emulated by Nuked-OPL3 1.8, and exports VGM. Core choice, image layout, player specification,
effects, seek, live edits, audition and VGM export are in [OPL3](#opl3-thor-trackers-own-player-on-a-ymf262-opl3-fm).

The Mega Drive system (`"ym2612-fm"`) streams Thor Tracker's player through
**SGDK's XGM2 Z80 driver**, with a YM2612 (ymfm), SN76489 and three driver-mixed PCM lanes.
It exports VGM, WAV and unpacked/packed XGM2. See [YM2612](#ym2612-sgdks-xgm2-driver-on-a-z80--ym2612--psg-ym2612-fm).

The AY-3-8910 / YM2149 systems (`"ay-zx128"`, `"ay-msx"`, `"ay-atari-st"`, `"ay-cpc"`, released in 0.25.0)
play Thor Tracker's own player on its own AY/YM core, one variant per stock computer (clock, chip type, frame rate,
stock wiring), and export VGM. See [AY-3-8910 / YM2149](#ay-3-8910--ym2149-thor-trackers-own-player-for-the-zx-spectrum-128-msx-atari-st-and-amstrad-cpc).

The PC Engine system (`"pce-huc6280"`, 0.26.0) plays the HuC6280's six-channel wavetable PSG with
Thor Tracker's own player (specified here, mirrored by an independent Python reference) on Mednafen's cycle-timed PSG,
with noise on channels 5-6, the channel 2 → channel 1 LFO, stereo balances and DDA samples streamed from the CPU
timer, and exports VGM. See [PC Engine](#pc-engine-thor-trackers-own-player-on-a-huc6280-psg-pce-huc6280).

The Konami SCC systems (`"msx-scc"`: the K051649 of Konami's MSX megaROM cartridges, `"msx-scc-plus"`: the K052539 of
the Sound Cartridge in SCC+ mode; unverified source slice) play songs with Thor Tracker's **own player** (C, specified
here, mirrored by an independent Python reference) on a K051649/K052539 sound core ported from MAME, at the MSX's
3579545 Hz, and export VGM. See [SCC](#scc-and-scc-thor-trackers-own-player-on-a-konami-k051649--k052539-msx-scc-msx-scc-plus).

The OPM system (`"ym2151-opm"`, 0.29.0) plays a YM2151 at the X68000's 4 MHz with Thor Tracker's
own player (specified here, mirrored by an independent Python reference) on ymfm's YM2151, with the chip's LFO,
noise and CSM as shared song state, and exports VGM. See [OPM](#opm-thor-trackers-own-player-on-a-ym2151-ym2151-opm).

The PC-88/98 systems (`"ym2203-opn"` and `"ym2608-opna"`, 0.30.0) play songs with Thor
Tracker's own OPN player (C) on ymfm's YM2203 (3 FM + SSG) and YM2608 (6 stereo FM + SSG + six rhythm drums from the
user's rhythm ROM + ADPCM-B with the song's own samples) at the PC-88/98 clocks, and export VGM. See
[OPN and OPNA](#opn-and-opna-thor-trackers-own-player-on-ymfms-ym2203--ym2608-ym2203-opn-ym2608-opna) and
[the YM2608 rhythm ROM](#the-ym2608-rhythm-rom-shared-firmware).

The Atari 8-bit system (`"atari-pokey"`) has no console driver to run either: Thor Tracker's own player (C, mirrored
by an independent Python reference) drives an own cycle-exact, event-driven POKEY model written from the Altirra
Hardware Reference Manual (NTSC or PAL clock, every distortion, 16-bit linking, 1.79 MHz clocks, high-pass, STIMER,
two-tone, volume-only samples through timer interrupts, Altirra's nonlinear mix and output coupling); it exports VGM.
See [POKEY](#pokey-thor-trackers-own-player-on-the-atari-8-bit-pokey-atari-pokey).

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
| FDS DAC levels | 64 values in `app/src/main/cpp/nes/nes_fds.c` (the 2C33's 6-bit wave DAC) | FamiStudio commit `c09dca64…be78`, `ThirdParty/NesSndEmu/nes_apu/Nes_Fds.cpp` `dac_lookup` (plgDavid's measurements, cleaned up by Persune, NESdev Discord) | Nes_Snd_Emu: LGPL-2.1-or-later (GPL-3.0-compatible); credited in `NOTICE.txt` |
| FamiStudio FDS note tables | `third_party/famistudio-4.5.3/NoteTables/famistudio_fds_note_table_{lsb,msb}.bin`, assembled into `generated/nes_fds_player_image.h` | the FamiStudio pin; sha256 `db5d9908…c6f8` / `84a7b0d6…cbf` | FamiStudio's MIT licence (in `NOTICE.txt`) |
| cc65 `ca65`/`ld65` | offline only (`tools/audio/prepare_nes_player.py`) | Ubuntu 24.04 package `cc65 2.19-1` ("ca65 V2.18 - Ubuntu 2.19-1"), checked by the script | tools are not shipped |
| GoatTracker 2.77 playroutine | `third_party/goattracker-2.77/player.s` (unmodified `src/player.s`, CRLF kept by `.gitattributes`); assembled into `app/src/main/cpp/generated/sid_player_image.h`; the test song `tools/audio/sid/gt_test_song.py` → `gt_test.sng` (ours) and GoatTracker's own packing `gt_test.sid` | SourceForge `GoatTracker 2/2.77/GoatTracker_2.77.zip` (Lasse Öörni, 2025-01-20), sha256 `96c2bd6a…4561ef` (the digest SourceForge lists); `player.s` sha256 `ee9ddbe9…5fad609a`, identical to the copy embedded in the release's packer (`goatdata.c`) | its own notice: "This playroutine source code does not fall under the GPL license! Use it, or song binaries created from it freely for any purpose, commercial or noncommercial." In `NOTICE.txt` |
| reSID 1.0 | `app/src/main/cpp/third_party/resid-1.0/` (`sid`, `voice`, `wave`, `envelope`, `filter`, `dac`, `extfilt`, `pot` `.cc/.h`, `spline.h`, the eight generated `wave*.h` tables, `siddefs.h.in`, `COPYING`, `AUTHORS`, `README`, `NEWS`), unmodified; plus `siddefs.h` (ours: `siddefs.h.in` with configure's defaults) | github.com/daglem/reSID commit `02c456abfe23cc4f06d94424e40beab8c90fa31f` (2021-12-10: the reSID 1.0 lineage as maintained in VICE, with Leandro Nini's fixes; the last commit before the 2022 rework, whose compile-time `exp()` tables only build with g++) | GPL-2.0-or-later (GPL-3.0-compatible); source ships in the repo, notice in `NOTICE.txt` |
| GoatTracker 2.77 `gt2reloc` + `gtasm` | offline only (`tools/audio/fetch_goattracker.sh`, `prepare_sid_player.py`): the release's command-line packer, and `tools/audio/sid/gtasm.c` over its embedded assembler (Magnus Lind's, from Exomizer) | built from the pinned release | GPL-2.0-or-later (packer) / Exomizer's licence (assembler); tools are not shipped, their output is the free playroutine |
| SGDK XGM2 driver | `third_party/sgdk-xgm2/`, unmodified driver and includes; assembled into `app/src/main/cpp/generated/xgm2_driver_image.h` by `tools/audio/prepare_ym.py` | github.com/Stephane-D/SGDK commit `9a78a44143a867cd8c5053b95cd061a21f0b73c2`; image 8192 bytes, SHA-256 `c7c9d630faf2397580d407878008c8cb7c836ad3d6c0c0ab26d6db9b59583358`, equal to `drv_xgm2` in SGDK's `lib/libmd.a` | MIT — notice in `NOTICE.txt` |
| SGDK sjasm 0.39j and xgm2tool | offline assembly and format-interoperability tools from the pinned SGDK checkout | the SGDK pin | sjasm zlib, xgm2tool MIT; not shipped |
| z80.h (floooh/chips) | `app/src/main/cpp/third_party/chips/z80.h`; implemented once in `cpuz80.c` | the `m6502.h` commit `9e88298c`; `z80.h` sha256 `6ca70ffd…de734f3` | zlib (the chips notice) |
| ymfm | `app/src/main/cpp/third_party/ymfm/` (`ymfm.h`, `ymfm_opn.h/.cpp`, `ymfm_opm.h/.cpp`, `ymfm_fm.h/.ipp`, `ymfm_adpcm.h/.cpp`, `ymfm_ssg.h/.cpp`, `LICENSE`), unmodified | github.com/aaronsgiles/ymfm commit `81aec25ccbb98f4873a255f7551ac4dadac59b4a` (2026-07-27); `ymfm_opn.cpp` sha256 `8734c6d5…913b489`, `ymfm_fm.ipp` `4fb7fe59…f6ab4b9`; `ymfm_opm.cpp` `2235d571…98b22dc` and `ymfm_opm.h` `cab75025…a9aee7b` fetched from `raw.githubusercontent.com/aaronsgiles/ymfm/81aec25c…/src/` for the OPM (the vendored `ymfm.h`, `ymfm_fm.ipp` and `ymfm_opn.cpp` match that commit's byte for byte) | BSD-3-Clause — notice in `NOTICE.txt` |
| Nuked-OPL3 1.8 | `app/src/main/cpp/third_party/nuked-opl3/` (`opl3.c`, `opl3.h`, `LICENSE`), unmodified | github.com/nukeykt/Nuked-OPL3 commit `765ec962e473aeb767e4cba74ffdc8f588ffbfe8` (2026-08-24, "remove NEW bit checks for 4-op channels", after "fix 4-op channel frequency latching"; the header still says version 1.8); `opl3.c` sha256 `59eb873f…1bfe03`, `opl3.h` `a84266b8…763679`, `LICENSE` `20c17d8b…aec96e331` | LGPL-2.1-or-later (GPL-3.0-compatible); source ships in the repo, notice in `NOTICE.txt` |
| emu2413 1.6.4 | `app/src/main/cpp/third_party/emu2413/` (`emu2413.c`, `emu2413.h`, `LICENSE`), unmodified; the VRC7's FM synthesis (VRC7 mode, its `OPLL_VRC7_TONE` patch ROM) | github.com/digital-sound-antiques/emu2413 commit `a1d5120903b75f93d3eb7690e34a29c0b4add903` (develop; the file banner says v1.6.4); `emu2413.c` sha256 `35d492c1…40abe5`, `emu2413.h` `24d132a3…4a637e3`, `LICENSE` `8fb093f2…2355f` (identical to upstream's raw files at that commit) | MIT — notice in `NOTICE.txt` |
| FamiStudio VRC7 note tables | `third_party/famistudio-4.5.3/NoteTables/famistudio_vrc7_note_table_{lsb,msb}.bin`, assembled into the VRC7 build | the FamiStudio pin (`SoundEngine/NoteTables/` at `c09dca64`); sha256 `933d38bf…55eb3d` / `1f70b8b9…e87210` | FamiStudio's MIT licence (its notice in `NOTICE.txt`) |
| Altirra 4.40 (POKEY generator formulas, mix table, output stage) | ported, not vendored: `pokey/pokey_chip.c` (`pk_init`'s generator steps from `pokeytables.cpp`, `pk_mix_init` from `pokey.cpp` UpdateMixTable) and `pokey/pokey_machine.c` (the AC coupling and clamp of `pokeyrenderer.cpp` PostFilter, constants in `pokey_chip.h`) | virtualdub.org/downloads/Altirra-4.40-src.7z (read 2026-11-08); behaviour from the Altirra Hardware Reference Manual 2026-01-02; MAME `pokey.cpp` consulted only, no code | GPL-2.0-or-later (GPL-3.0-compatible) — notice in `NOTICE.txt` |
| ayumi | `third_party/ayumi/` (`ayumi.c`, `ayumi.h`, `LICENSE`, our `README.md`), unmodified; its measured `AY_dac_table`/`YM_dac_table` copied as data into `ay/ay_psg.c`; the file itself linked only by the native host test (independent comparison) | github.com/true-grue/ayumi commit `07c08b4874c359169e4a028edf73f046d8b763e2` (2021-11-24); `ayumi.c` sha256 `8824bc34…f19401d`, `ayumi.h` `506e3b75…f17099`, `LICENSE` `20bb6439…a66da` | MIT — notice in `NOTICE.txt` |
| Mednafen 1.32.1 PSG | `app/src/main/cpp/third_party/mednafen-pce-psg/` (`pce_psg.cpp`, `pce_psg.h`, `COPYING`), unmodified, compiled through `pce/pce_psg.cpp` against our shim (`shim/mednafen/mednafen.h`, `shim/trio/trio.h`) | `mednafen-1.32.1.tar.xz` (mednafen.github.io/releases/files/) sha256 `de7eb94ab66212ae7758376524368a8ab208234b33796625ca630547dbc83832`; `src/hw_sound/pce_psg/pce_psg.cpp` sha256 `e6a029e33e0c1fe9330b358b0610c146eec481b5a2e7b804324b10f87d55f8c5`, `pce_psg.h` `5307959029251c8f85e43f1231215f0add2e59d12ce15ec486053f1fe31f796c`, `COPYING` `8177f97513213526df2cf6184d8ff986c675afb514d4e68a404010521b880643` | GPL-2.0-or-later (GPL-3.0-compatible); source ships in the repo, notice in `NOTICE.txt` |
| MAME k051649 (port) | `app/src/main/cpp/scc/scc_k051649.c/.h`: our C port of the Konami 051649/052539 sound logic (counter, latch, halting, period-write restart, test bits 5-7, `>> 4` output), restructured as an analytic advance and an event renderer; test bits 0-1 added from openMSX's documented model (no openMSX code) | github.com/mamedev/mame `src/devices/sound/k051649.cpp` at commit `20d4667cdf485066a113f1ef24bae0887cd7134c` (2025-07-17, its last change), sha256 `003db1a4…07122`; `k051649.h` `3d3031cc…f87ef87` | BSD-3-Clause (copyright-holders Bryan McPhail) — the port keeps that licence and notice in its header; notice in `NOTICE.txt` |

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
`nes/nes_machine.c`, `nes/nes_image.c`, `nes/nes_apu.c`, `nes/nes_vrc6.c`, `nes/nes_mmc5.c`, `nes/nes_players.c` and `blip_buf.c`; the SID files `sid/sid_chip.c`,
`sid/sid_machine.c`, `sid/sid_image.c`; the OPL3 files `opl3/opl3_chip.c`, `opl3/opl3_machine.c`,
`opl3/opl3_player.c`, `opl3/opl3_image.c`, `opl3/opl3_vgm.c` and Nuked-OPL3's `opl3.c`; `vgm_gd3.c` (the VGM exports'
GD3 tags); the YM2612 files `cpuz80.c`, `ym/ym_chip.c`, `ym/ym_machine.c`, `ym/ym_player.c`, `ym/ym_image.c`,
`ym/ym_xgm.c`, `ym/ym_vgm.c`, `ym/ym_psg.c`; the OPN files `opn/opn_chip.c`, `opn/opn_machine.c`, `opn/opn_player.c`,
`opn/opn_image.c`, `opn/opn_vgm.c`; the AY files `ay/ay_chip.c`, `ay/ay_machine.c`, `ay/ay_player.c`,
`ay/ay_psg.c`, `ay/ay_image.c`, `ay/ay_vgm.c`; the POKEY files `pokey/pokey_engine.c`, `pokey/pokey_machine.c`,
`pokey/pokey_player.c`, `pokey/pokey_chip.c`, `pokey/pokey_image.c`, `pokey/pokey_vgm.c`; always with `NDEBUG`), static C++17 libraries `resid` (reSID 1.0 and
`sid/sid_resid.cpp`, its C interface; `-O2 -w`) and `ymfm` (`ym/ym_ymfm.cpp`, which compiles ymfm's `ymfm_opn.cpp`
itself and instantiates the OPN and OPNA engine templates, `nes/nes_epsm_ymfm.cpp`, `opn/opn_ymfm.cpp`, `opm/opm_ymfm.cpp`,
`ym2608_rhythm_rom.c`, plus `ymfm_adpcm.cpp`, `ymfm_ssg.cpp`) that `chip_engines` links, and the shared `thortracker_audio` (`audio_engine.cpp`, `audio_jni.cpp`) linked with
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

Android transport intent has no seek timeout. `PlaybackPosition.pending` covers queued
Play/Stop work and start preparation, then the native published-start sequence remains pending
until the callback acknowledges that exact sequence **after** publishing its playing/ended
state. Returning from `nativePlay` is not acknowledgement. Idle stream suspension also respects
this handoff. A newer start cannot be acknowledged by an older callback, and a start that ends
on its first callback settles without requiring the UI to have observed `playing=true`.
`Transport.position()` clears requested intent only for a stopped, non-pending snapshot; it
never restores intent from an old playing snapshot after Stop/focus loss. Failed commands
finish their queued phase, closed audio reports stopped, and Stop/device loss cancels the native
pending state. Desktop remains synchronous (`pending=false`); Game Boy driver playback,
seek reconstruction, and synthesis are unchanged.

Verified with five deterministic transport-transition regressions in both JVM and Linux suites,
the actual Konkr final-row seek/Stop regression, and a 14.5-second order-local seek whose UI
round trips stayed at or below 20 ms. Nova/DMG playback, audition, editing, recovery and Stop
also passed, as did the clean 21-test Android suite and all 32 unchanged Game Boy renders.

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
  float stereo, buffer initially 2 bursts), stops after 10 s idle, and is reopened after a device
  error (transport is stopped, not silently resumed). `close()` destroys everything on the
  control thread and waits (≤ 2 s).
- If AAudio's underrun count increases, the data callback grows the used buffer by one
  hardware burst, never beyond its existing capacity. It does not shrink during that stream's
  lifetime; a newly opened stream starts at two bursts again. This follows
  [AAudio's documented tuning policy](https://developer.android.com/ndk/guides/audio/aaudio/aaudio#tuning-buffers-to-minimize-latency)
  and [Oboe's callback-safe tuning contract](https://github.com/google/oboe/blob/main/include/oboe/LatencyTuner.h):
  no new allocation, stream lock or control-thread wait in the callback. Diagnostics retain
  the real underrun count and actual buffer size. This matters for clocked FM engines whose
  complete driver tick can exceed a FAST track's initial two-burst window.
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

## VRC7: the same engine with Konami's VRC7 (`"nes-famistudio-vrc7"`)

The second NES expansion chip (design in `docs/BACKENDS.md`, "NES expansion chips", VRC7). Everything in
the NES section holds; this section lists what differs. The 2A03 and VRC6 builds' player bytes, image
sizes, song bases and fixtures are unchanged (`prepare_nes_player.py` checks its outputs for them stay
byte-identical).

### What runs

- **Player** (`generated/nes_vrc7_player_image.h`, `NesVrc7PlayerImage.kt`): `tools/audio/nes/nes_player.s`
  assembled with `-D FAMISTUDIO_EXP_VRC7=1`, nothing else changed: the same entry table and configuration plus
  FamiStudio's VRC7 code and its note tables (`NoteTables/famistudio_vrc7_note_table_lsb/msb.bin`, pinned). 11
  channels, 23 envelope slots, 9 pitch envelope slots. Song base: the first page after the player (measured by the
  script, `NES_VRC7_SONG_BASE` / `NesVrc7PlayerImage.SONG_BASE`); the region up to `$FFF9` is shared with the DPCM
  samples as on the 2A03 build; image = 32-byte header + directory + region (`NES_VRC7_IMAGE_SIZE`).
- **Channels**: engine channels 0-4 as the 2A03 build, then **5-10 VRC7 FM 1-6**.
- **Song header** (FamiStudio's for expansions with their own instruments): count, instruments, **instruments_exp**,
  samples − 4, eleven channel streams, PAL tempo, NTSC tempo (`famistudio_init` reads the stream words from offset
  7). The Thor layout keeps one order stream per channel (`$41 n ptr` per order, `$42`).
- **Instruments**: the 2A03 table (64 slots × 8 bytes, as before) and right after it the expansion table, **64 slots
  × 16 bytes** (`FAMISTUDIO_USE_INSTRUMENT_EXTENDED_RANGE`: an FM channel's instrument byte `$80 | (slot − 1) << 1`
  indexes it × 16): volume, arpeggio and pitch envelope words (directory entries `slot × 4 + 0/1/3`), the sustain
  override byte (`$20` release, `$80` stop; the song's `Vrc7Sustain`, the same in every entry), `patch << 4`
  (0 = custom), and the 8 patch registers (a built-in patch's ROM values; loaded only for patch 0). Loading an FM
  instrument (`famistudio_set_vrc7_instrument`) sets the envelopes (not on a no-attack note), the global
  `famistudio_chn_vrc7_sustain`, the channel's patch byte, and for patch 0 writes `$00-$07` at once.
- **Opcodes**: `$56` (FM channels only) releases: the channel keys off at its next update and its volume envelope
  jumps to its release point (as `$45`, which FM channels never get). `$4F` (duty) and `$55` are invalid on FM.
  Stop (note 0) and cut key off with the sustain bit cleared, or set with the stop override.
- **Per frame and FM channel** (`famistudio_update_vrc7_channel_sound`): a new attack on a still keyed channel first
  writes `$20+c` = 0 (so the chip retriggers); then `$30+c` = `patch << 4 | (15 − volume)` (volume = envelope ×
  volume track, so 0 is the chip's quietest, −45 dB, not silence), `$10+c` = F-number low, and `$20+c` = sustain
  byte | key | block << 1 | F-number bit 8. The frequency is the VRC7 note table (F-number already shifted by the
  block) + (pitch envelope + fine pitch) × 8 + the slide (pitch shift 3: slides carry no fraction bit here), halved
  until it is below `$200` to find the block. Writes go through `$9010` (select) and `$9030` (data); init writes
  `$E000` = 0 (audio on). One engine quirk is kept as it plays: a stop on a note whose `$20+c` value has no block or
  F-number bit 8 left after masking takes the stop-override path (sustain bit set).
- **Sounding pitch**: VRC7 engine note 46 is approximately 220 Hz, while the same 2A03 note is approximately 440 Hz.
  `NesRules.engineNote` therefore adds 12 on the FM channels: absolute 57 becomes engine note 58. This applies to
  audition, lowered songs and FamiStudio text. The driver table limits FM to C-1..B-7; validation also rejects
  slides above B-7. Pxx uses the pulse period at the unshifted sounding note and the FM frequency at the shifted
  engine note, retaining one detune meaning across both chips. The released base NES mapping is unchanged.
- **Lowering** (Kotlin, what differs from the 2A03's): OFF on a sounding FM note is `$56` (FamiStudio's `CanRelease`
  is always true there), else a stop. A custom-patch sound is never re-sent per order (the per-order instrument
  re-send of the other channels would rewrite `$00-$07` under another FM channel's custom sound); FamiStudio's
  `OtherVrc7ChannelUsesCustomPatch` makes a channel send its custom sound again after another FM channel had a note
  of a different custom sound. There are **two intentional corrections** to FamiStudio 4.5.3's exporter:
  1. At the loop, a channel reloads its first custom patch if another custom patch can appear in the loop. This is
     conservative because Gxx can change the final writer. Upstream compares only each channel's own first/last
     instrument and can leave the wrong shared patch installed on later passes.
  2. Mid-song, restoring a custom patch keeps the channel's instrument identity. A no-attack note therefore retains
     its held envelope and does not key off/key on. Upstream invalidates the instrument after another custom patch
     and forces a fresh attack, even though the note explicitly disabled attack. Loop restoration preserves legato too.
  These follow FamiStudio's editor semantics, not its buggy export in those cases. See
  [`FamitoneMusicFile.cs`](https://github.com/BleuBleu/FamiStudio/blob/4.5.3/FamiStudio/Source/IO/FamitoneMusicFile.cs),
  [`ChannelStateVrc7.cs`](https://github.com/BleuBleu/FamiStudio/blob/4.5.3/FamiStudio/Source/ChannelStates/ChannelStateVrc7.cs)
  and [`ChannelState.cs`](https://github.com/BleuBleu/FamiStudio/blob/4.5.3/FamiStudio/Source/ChannelStates/ChannelState.cs).
  The independent `vrc7_patch_restore` golden explicitly demonstrates both export differences through the real driver.
  The ordinary parity goldens omit only idempotent writes of unchanged custom-patch bytes, retaining every key/envelope
  write. Text imports retain the musical intent, but re-exporting them through FamiStudio 4.5.3 can reintroduce these
  bugs; Thor Tracker's own NSF, WAV and playback use the corrections.
  Slides use the VRC7 note table and FamiStudio's VRC7 slide shift (delta >> 3). A cell's Pxx is converted to the FM step nearest
  the pulse's detune (`NesRules.vrc7FinePitch`); audition fine pitch on FM uses the same conversion. Sounds that differ
  only in their names compare equal for legato and custom-patch restoration, as FamiStudio's export merges identical
  instruments (`Project.MergeIdenticalInstruments`). Streams nevertheless retain each note's own bank slot so held
  notes follow edits to that slot, not its first equal copy. Switching copies can rewrite unchanged custom bytes.
- **VRC7 audio** (native): emu2413 1.6.4 in VRC7 mode (`OPLL_setChipType` 1, its `OPLL_VRC7_TONE` ROM patches,
  which equal FamiStudio's `Vrc7InstrumentPatch` table), one OPLL sample per 36 CPU cycles (49715.9 Hz). Registers:
  `$9010` select, `$9030` data, `$E000` bit 6 the reset latch (set: chip registers and the tremolo LFO cleared, the
  vibrato phase kept, writes ignored, silent); only the VRC7's registers `$00-$07`, `$0F`, `$10-$15`, `$20-$25`,
  `$30-$35` reach the chip. Mix: one emu2413 output unit = (2A03 full pulse level / 2) · 10^(11.09/20) / 1863.1 of the
  2A03 mixer scale, from rainwarrior's VRC7 level survey (+11.3 dB, +10.88 dB on Lagrange Point) and that test
  patch's RMS on emu2413; the VRC7 build's output gain is 2.0 / (1 + six FM channels' full swing) (the 2A03 and VRC6
  builds keep 2.0).
  Instance creation goes through `chip_opll_new`: a shared mutex protects emu2413's lazy global table initialization
  when playback and background WAV rendering create chips concurrently. Chip stepping is per-instance and never
  takes that mutex; the vendored emulator is unchanged.
- **Machine**: seek runs the chip without output, so FM envelopes and phases advance as in straight play; the order
  fallback leaves an FM channel whose order starts with a custom sound without an instrument until its next
  instrument byte (custom sounds are not re-sent per order). Mute leaves a channel out of the mix (the chip keeps running). Live edits: the machine tracks which
  expansion entry each FM channel loaded and which entry the custom registers came from; `apply_image` updates the
  channels' patch nibble and the sustain byte, and rewrites `$00-$07` when that entry's registers changed, so a
  custom-patch edit is heard at once. Audition release is `$56`; the audition ends when the volume envelope's tail
  has finished and the carrier is silent (or frozen at release rate 0).
- **Exports**: `.nsf` with the VRC7 player, header byte `$7B` = `$02` (NSF players emulate the chip); `.wav` through
  `chip_wav_open("nes-famistudio-vrc7", …)`; FamiStudio text (`Expansions="VRC7"`, `Vrc7Patch`, `Vrc7Reg0-7` for
  custom patches, `Vrc7OverrideRelease`/`Vrc7OverrideStop` from the song setting, channels `VRC7FM1-6`).

### Preparing the player

`python3 tools/audio/prepare_nes_player.py` builds the VRC7 player with the others: assembled once to measure its
size, then again at its song base (the code does not depend on it). It writes `nes_vrc7_player_image.h`,
`NesVrc7PlayerImage.kt` and the fixtures `app/src/test/cpp/nes_vrc7_song.h` from `tools/audio/nes/vrc7_test.txt` as
exported by FamiStudio 4.5.3 (`vrc7_test_ca65.s`; `--famistudio DIR` writes it when missing, else checks it is
unchanged). FamiStudio's ca65 export ends each VRC7 instrument's first `.byte` row with a comma (`$00, $10, `); the
link and the parser drop it. In the Thor layout both instrument tables have the same count.

### Verification

- Kotlin: `NesGoldenTest` runs the golden songs `vrc7_demo` and `vrc7_effects` (FamiStudio's export of our text vs
  our lowering through the VRC7 player; `$9010`/`$9030`/`$E000` writes compared every frame through two passes and
  the loop; `vrc7_effects` has two custom sounds and an identical twin taking the custom patch in turn, also on one
  row, the song's release and stop overrides, `$56` releases and stops, cuts, delays, slides, vibrato, Pxx, volume
  slides, arpeggios, pitch envelopes, C-1 and B-7, and speed changes on an FM channel). `NesLoweringTest` checks the
  VRC7 image contract (header, both instrument tables, `$56` only on FM, no duty or saw opcodes there), the engine
  numbers and Pxx conversion, and the custom-patch rules (no per-order re-send, the loop reload). `NesExportTest`,
  `NesProjectCodecTest` and `NesInstrumentViewTest` cover the `.nsf`/text exports, validation, files and the FM page.
  The separate `vrc7_patch_restore` upstream-export fixture is deliberately not an equality fixture: after another
  channel changes the custom patch, it compares upstream's attack/envelope restart with our held legato envelope,
  and upstream's wrong patch on repeated loops with our restored patch. Delayed final writers remain covered by
  `NesLoweringTest`.
- Native: `nes_vrc7_test` also consumes `vrc7-held-copies.nesimg`, generated by Kotlin's `exportSamples`. Editing an
  unused identical custom original leaves the held copy unchanged; editing a held preset copy changes only its own
  voice, and a held custom copy follows its own patch edit immediately. All retain their settled volume envelopes.
- Not done: listening on a VRC7 cartridge, comparison with a reference VRC7 emulator's audio (NSFPlay), Thor
  measurements.

## N163: the same engine with Namco's 163 (`"nes-famistudio-n163-1"`..`"-8"`)

The Namco 163 expansion (design in `docs/BACKENDS.md`, "NES expansion chips"): up to eight wavetable channels
sharing 128 bytes of sound RAM. FamiStudio builds its engine for a fixed channel count, so there are **eight
builds and eight engines**, `"nes-famistudio-n163-<n>"` for n = 1..8 N163 channels. Everything in the NES
section above holds; this section lists only what differs. The 2A03 and VRC6 engines, their player bytes,
image sizes and song bases are unchanged.

### What runs

- **Players** (`generated/nes_n163_<n>_player_image.h`, `NesN163PlayerImages.kt` objects `NesN163PlayerImage<n>`):
  `tools/audio/nes/nes_player.s` assembled with `-D FAMISTUDIO_EXP_N163=1 -D FAMISTUDIO_EXP_N163_CHN_CNT=<n>` and the
  pinned N163 note tables (`NoteTables/famistudio_n163_note_table_<n>ch_lsb/msb.bin`), nothing else changed: the same
  entry table (`$8000` init, `$8003` play, `$8006` idle, `$8009` trap). Engine channels 5 + n, envelope slots
  11 + 3n (N163 channel k: volume, arpeggio and **wave index** from slot 11 + 3k), pitch envelope slots 3 + n.
- **Song base**: the first page after each player (measured by the prepare script), region up to `$FFF9` as
  before; image = 32-byte header + directory + region (`NES_N163_<n>_IMAGE_SIZE`, below the 2A03's).
- **Channels**: engine channels 0-4 as the 2A03 build, then **N163 1..n** (engine channels 5..4+n; N163 channel k
  = engine channel 5 + k = hardware channel 8 − k, registers `$78 − 8k`).
- **N163 audio** (`nes/nes_n163.c`, written here from the NESdev wiki "Namco 163 audio"): 128 bytes of sound RAM,
  the address port `$F800-$FFFF` (bit 7 auto-increment; the address stops at `$7F`, no wrap), the data port
  `$4800-$4FFF` (writes store, reads return RAM and auto-increment), `$E000-$E7FF` bit 6 disables sound (the DAC
  outputs 0, the oscillators keep running; power-on enabled; the engine never writes it). One channel is updated
  every **15 CPU cycles**, cycling from channel 8 (`$78`) downwards through the enabled count (`$7F` bits 4-6, + 1,
  read at every update: a change applies from the next slot, the round restarting at channel 8 once the position
  reaches the count): phase += frequency mod (length << 16), stored back in RAM (length = 256 − (`$7C` & `$FC`)),
  sample = the nibble at ((phase >> 16) + address) & `$FF` (the even sample in the low nibble), DAC = (sample − 8) ×
  volume, **held until the next update**: the chip does not mix, its output is time-multiplexed. So a channel plays
  f = CPU × freq / (15 × 65536 × L × c). Every slot is computed exactly with output attached or not, so a silent
  seek leaves RAM, phases and the round-robin position as audible play does; with output the DAC level goes into
  the 2A03's band-limited buffer whenever it changes.
- **Mixing level**: NESdev "Mixing" measures the loudest N163 square in 1-channel mode **11 to 19.5 dB** louder
  than the loudest 2A03 square, by board (submapper 3: 11.0-13.0 dB, 4: 16.0-17.0, 5: 18.0-19.5). FamiStudio songs
  are balanced for the quieter class, so we take **12 dB**: a full DAC swing (225 units: sample 0 to 15 at volume
  15) = the 2A03 mixer's pulse at volume 15 × 10^(12/20) (`NES_N163_STEP` per unit), positive polarity, the 2A03
  build's output gain unchanged. Peak headroom: one N163 slot reaches ±0.32 of the 2A03 mixer's full scale.
- **Multiplex whine**: the output switches channel every 15 cycles, so the per-channel update rate is
  119.3 kHz / c: at 7-8 channels (17.0 / 14.9 kHz) the switching pattern is audible as a high whine, as on the
  hardware (NESdev: "very loud and apparent" through A/V output). We keep it; the output stage's 14 kHz low-pass
  softens it at 8 channels.
- **Machine** (`nes_machine.c`, `nm_create(rate, &nes_player_n163[n − 1])`): on N163 builds the bus routes `$4800-$4FFF`
  (writes and reads), `$E000-$E7FF` and `$F800-$FFFF` (writes) to the unit, timestamped like the APU; reads at
  `$8000+` still return the ROM. Only the build's own expansion unit is reset, clocked and mixed. `nm_write_log`
  includes the N163 port writes; `nm_apu_log` still gives only the APU's. Mute 5..4+n silences N163 channel k's
  slots (bit k of the unit's mask): RAM, phases and the round robin never change on mute.

### CPU memory map additions

| Range | Use |
|---|---|
| `$4800-$4FFF` | N163 data port (write: store at the address, read: the RAM byte; both auto-increment with bit 7 of the address) |
| `$E000-$E7FF` | N163 sound disable (bit 6); writes only, reads return the ROM (song data) |
| `$F800-$FFFF` | N163 address port; writes only, reads return the ROM (song data; the vectors) |

### Image layout additions (Thor layout, N163 builds)

- **Song header**: `u8 1`, `word instruments`, `word instruments_exp` (the expansion instrument table), `word
  samples−4`, 5 + n channel stream words, PAL tempo, NTSC tempo (FamiStudio's `exp` header: 7 bytes before the stream
  words, `nes_player_head`). The raw layout accepts the same header (song stride 2 × (5 + n) + 4).
- **Expansion instrument table**: one 16-byte entry per instrument (the same count as the 2A03 table; instrument i
  on an N163 channel loads entry i — `$54 i` / `$80|i<<1` as on the other channels): words volume (kind 0),
  arpeggio (kind 1), pitch (kind 3) — the same directory entries as the 2A03 table — and the **wave-index envelope**
  (kind 4); byte 8 wave position (samples, even), byte 9 wave size (samples, multiple of 4, ≥ 4), position + size
  ≤ 256 − 16n (the wave RAM below the n channels' registers); word 10 the wave table; bytes 12-15 zero. Every slot
  is well formed (non-N163 slots carry default N163 data).
- **Wave table**: one pointer per distinct wave (FamiStudio's dedup order), each to size / 2 packed bytes (the even
  sample in the low nibble) inside the song data; tables and waves may be shared. `nm_load_image` requires a table
  entry for every index the wave-index envelope produces (largest value + 1 entries) and every wave inside the data.
- **Directory kind 4 = WAVE** (`NM_KIND_WAVE`, N163 images only; rejected by other builds): encoded
  like a volume envelope (byte 0 = release index or 0, values `$C0` + wave index 0..63, holds, loops; ends in a
  loop); `nm_envelope_start(WAVE)` = 1. Slot s's wave-index envelope sits at directory **256 + (s − 1)** (256..319);
  effect envelopes start at **320** in N163 and FDS images (384 in 5B and EPSM images, 256 in the other builds).
- **Opcodes on N163 channels**: `$5A` release (0 operands, ends the row: the volume **and** the wave-index envelopes
  jump to their release points), `$5B` phase reset (0 operands, prefix: after the channel updates the engine
  writes phase 0), fine pitch `$4E f` (frequency offset, + raises; the Kotlin side converts), vibrato and arpeggio as
  on the other pitched channels. `$5A`/`$5B` are rejected off the N163 channels (and on other builds: "opcode not
  allowed in a row"); `$4F` (duty) and `$53` (2A03 phase reset) are rejected on N163 channels.
- **Notes**: the build's N163 note table, tuned for 16-sample waves (a wave of L samples sounds 12·log2(16 / L)
  semitones from the note); the tables saturate at `$FFFF` from note 95 (4 ch), 92 (5), 88 (6), 86 (7), 83 (8):
  Kotlin validation rejects those notes.

### Live edits, audition, ops

- **Live edit** (`nm_apply_image`, Thor to Thor): the wave-index envelope of each N163 channel (slot kind WAVE) is
  remapped like every envelope pointer, and `famistudio_exp_instrument_lo/hi` follow the new table. The engine
  uploads a wave only when its wave index changes, so for every N163 channel whose current instrument
  (`famistudio_chn_n163_instrument`) has another wave in the new image — position, size, wave count or any wave's
  bytes, compared by content (a moved but identical wave is unchanged) — the machine sets
  `famistudio_chn_n163_wave_index[k]` = `$FF`: the next engine frame re-uploads it. `apply_image` itself leaves chip RAM
  and phases untouched, and never restarts transport. The extra upload costs CPU cycles, so later timed writes can
  shift and a pitch-changing channel need not end that frame at the phase of unedited playback. Relocated data can
  likewise change indexed-read page crossings. Timed-write replay checks the exact resulting chip state rather
  than incorrectly requiring identical phases under different write timings.
- **Audition** on N163 channels: `$54 i`, `$4E f`, `$40 n` as on the other pitched channels; the release stream is
  `$5A`. `release()` returns 1 if the instrument's volume envelope has a release point (the tail ends at its final
  loop) or else its wave-index envelope has one (the tail ends at that envelope's final loop); otherwise 0 (cut).
- **Ops** (`nes_chip.c`): `nes_n163_<n>_chip_ops`, ids `"nes-famistudio-n163-1"`..`"-8"`, `image_size` =
  `NES_N163_<n>_IMAGE_SIZE`, the same functions as `"nes-famistudio"` over a machine of that build (nothing allocated
  after `create`). `.nsf` export: the build's player at `$8000`, the data at its song base, header byte `$7B` = `$10`
  (Namco 163).

### Preparing the players

`python3 tools/audio/prepare_nes_player.py` builds all players in one run: the eight N163 builds, their headers and
`NesN163PlayerImages.kt`, and from `tools/audio/nes/n163_test.txt` (an 8-channel FamiStudio text exported by FamiStudio
4.5.3 as `n163_test_ca65.s`) the fixtures `app/src/test/cpp/nes_n163_song.h`: raw at the 8-channel song base, the Thor
layout, every block (wave tables and waves included) moved, and an edited copy (one note, one volume envelope byte,
one wave byte; `N163_TEST_EDIT_*`).

### Verification

- `tools/audio/run_native_host_test.sh [dir]` builds and runs `app/src/test/cpp/nes_n163_test.c`: N163 unit checks
  from NESdev **for every channel count 1..8** (slot timing every 15 cycles and the order from channel 8 down, the
  phase after U updates = U × freq mod (L << 16) across frame boundaries and the frequency against NESdev's
  formula, mute changing neither RAM nor phases, a silent run equal to an audible one), then wave address, length
  and nibble order, auto-increment stopping at `$7F`, data port reads, `$E000` disable, silence at volume 0 and
  with a wave of 8s, the 12 dB level; image rules (raw and Thor loads, 13 streams, wave-index envelopes at
  256 + j, refusals of the other builds' images, kind 4 only on N163 builds, odd wave position, sizes 6 and 0, a
  wave reaching the registers, nonzero bytes 12-15, wave table and wave outside the data, a wrong-kind wave-index
  word, `$53`/`$4F` on N163 channels and `$5A`/`$5B` off them); playback of the 8-channel test song (**the Thor layout
  writes the same APU and N163 registers as FamiStudio's own export through the first pass and two loops**, row
  starts, every sounding channel's current wave in RAM at its instrument's position with matching wave address and
  length registers, no driver faults; the measured worst call is 15,827 CPU cycles); the wave index following
  FamiStudio's envelope walk (repeats, loop, release); a `$5B` row writing phase 0; seek (registers for 200 frames,
  and N163 RAM, phases and round robin equal to audible straight play); live relocation before every frame with
  identical register sequences and each chip state matching a separate replay of its own timed writes; the edited
  wave re-uploaded in the next frame without restart or phase-reset writes, and the edited note; audition (frequency registers, fine
  pitch `+10` = `10 << 5 << 2`, `$5A` reaching the wave-index envelope's release); mute of 5-12 in the mix with chip
  state unchanged; all eight ops tables. Evidence: `nes_n163.wav`, `nes_n163_trace.txt` (per-frame APU and N163
  port writes), `nes_n163.nsf` (8-channel player + FamiStudio's export, `$7B` = `$10`).
- Not done: listening on hardware or an N163 cartridge, comparison with a reference N163 emulator's audio
  (NSFPlay, Mesen), Thor measurements.

## FDS: the same engine with the Famicom Disk System's sound (`"nes-famistudio-fds"`)

The Famicom Disk System's 2C33 sound unit (design in `docs/BACKENDS.md`, "NES expansion chips"): one wavetable channel
(64 samples of 6 bits) with frequency modulation from a 32-entry table. One build, one engine. Everything in the NES
section above holds; this section lists only what differs. The 2A03, VRC6, VRC7, N163 and Sunsoft 5B engines, their player
bytes, image sizes and song bases are unchanged.

### What runs

- **Player** (`generated/nes_fds_player_image.h`, `NesFdsPlayerImage.kt`): `tools/audio/nes/nes_player.s` assembled with
  `-D FAMISTUDIO_EXP_FDS=1 -D FAMISTUDIO_USE_FDS_AUTOMOD=1` (auto-mod as FamiStudio's own NSF kernel `nsf.s` enables it)
  and the pinned FDS note tables (`NoteTables/famistudio_fds_note_table_lsb/msb.bin`). Two scoped patches support the full
  manual-speed range and preserve runtime modulation during legato instrument re-sends; the vendored source stays
  unchanged. The player is 3868 bytes at `$8000`, with the same entry table and song data at `$9000`.
  Engine channels 6 (FDS = 5), envelope slots 14 (`3+3+2+3+3`: FDS volume, arpeggio and **wave index** from slot 11),
  pitch envelope slots 4 (FDS: 3). Image = 32-byte header + directory + region (`NES_FDS_IMAGE_SIZE`).
- **FDS audio** (`nes/nes_fds.c`, written here from the NESdev wiki "FDS audio", raw wikitext, with Disch's
  `FDS.txt` "Unit Activity" where the wiki is silent):
  - registers `$4040-$407F` wave RAM (writes only while `$4089` bit 7 is set; reads `$40 |` the addressed sample while
    writable, else the sample at the current position), `$4080` volume envelope / gain (bit 7: gain = bits 0-5,
    envelope off; resets the envelope timer), `$4082/$4083` 12-bit frequency (`$4083` bit 7 halts the wave unit and
    resets its accumulator, so the position is 0; bit 6 disables both envelopes and resets their timers), `$4084` mod
    envelope / gain, `$4085` mod counter (7-bit signed; it does **not** reset the table position, NESdev), `$4086/$4087`
    12-bit mod frequency (`$4087` bit 7 halts the mod unit, forces accumulator bits 0-12 to 0 keeping the table address
    in bits 13-17, and allows `$4088`; bit 6 forces a step every tick), `$4088` mod table write at the address then
    advance (32 writes end where they began), `$4089` write enable + master volume, `$408A` envelope speed (power-on
    `$E8`, as the BIOS leaves it, with `$4080` = `$80`; `$4023` = `$83`, the sound registers enabled, is not modelled
    further), reads `$4090-$4097` (gains, accumulators, `$4094` = bits 4-11 of the completed counter × gain, `$4095`
    the next increment `0,1,2,4,C,C,E,F`, `$4096` the DAC sample, `$4097` the counter);
  - a **unit tick every 16 CPU cycles**: the mod unit (accumulator bits 0-11 += mod frequency; on a carry out of bit 11
    the counter takes the entry at bits 13-17 — 0, +1, +2, +4, reset, −4, −2, −1, wrapping in 7 bits — and the 6-bit
    position advances, bit 12 being the "ghost" bit that applies each entry twice), then the wave unit (24-bit
    accumulator += NESdev's modulated pitch `temp = counter × gain; if ((temp & $0F) && !(temp & $800)) temp += $20;
    temp = ((temp + $400) >> 4) & $FF; pitch = (frequency × temp) & $FFFFF`, applied whether the mod unit runs or
    not; position = bits 18-23). A 64-sample wave therefore sounds **CPU × frequency / 2^22** Hz unmodulated;
  - envelopes (enabled by bit 7 = 0, `$408A` ≠ 0, `$4083` bits 6-7 clear): one step every 8 × (speed + 1) ×
    (`$408A` + 1) cycles, up to 32 or down to 0 (FamiStudio never enables them; modelled anyway);
  - output: the gain the DAC uses is latched from the volume gain only while the wave position is 0 (or when the
    accumulator wraps past it, as FamiStudio's `Nes_Fds.cpp`), writing gain 0 mutes at once; `$4089` bit 7 holds the
    DAC sample while the wave unit runs on; level = DAC(sample) × min(gain, 32) / 32 × 2 / (2 + master), DAC =
    **FamiStudio's measured curve** (`Nes_Fds.cpp` `dac_lookup`, plgDavid's measurements cleaned up by Persune; Nes_Snd_Emu,
    LGPL-2.1-or-later: the 64-entry table only) normalised to 1 at 63; volume applied linearly (the wiki: the 32-cycle
    PWM "may be emulated simply as a linear volume control");
  - not modelled (the wiki marks them unverified, or no song depends on them): the `$4023` audio reset, the "envelopes
    4× faster" and "halts the mod table accumulator" remarks on `$4083` bit 7 (Disch and FamiStudio keep the mod unit
    running), the delayed first mod step after clearing `$4087` bit 7, the multiplier's intermediate `$4094` values.
  Event-driven like the other units: with output attached or not every tick and envelope step is computed exactly,
  so a silent seek leaves every register, RAM, accumulator, timer and the latch as audible play.
- **Mixing**: NESdev "Mixing": the FDS maximum is "roughly 2.4x the maximum volume of the APU square, and the
  polarity is the same", through a filter "approximated as a 1-pole lowpass with a cutoff of ~2000Hz". Full output
  (sample 63, gain ≥ 32, master 0) = 2.4 × the 2A03 mixer's pulse at volume 15 (`NES_FDS_FULL_LEVEL`), positive
  polarity, into the 2A03's band-limited buffer through a 1-pole 2000 Hz low-pass stepped once per unit tick (sent
  with `blip_add_delta_fast`: the filtered signal has no edges). The filter is output state: attaching the output
  starts it at the current level.
- **Machine** (`nm_create(rate, &nes_player_fds)`): on the FDS build the bus routes writes `$4040-$408A` and reads
  `$4040-$407F`, `$4090-$4097` to the unit, timestamped like the APU. Only the FDS unit is reset, clocked and mixed.
  `nm_write_log` includes the FDS writes; `nm_apu_log` gives only `$4000-$4017`. Mute 5 forces the FDS output to 0;
  nothing else changes.
- **Pitch**: FamiStudio's FDS table is frequency = f × 2^20 / CPU (f = FamiStudio's pitch of the note) while the chip
  plays CPU × frequency / 2^22 per wave, so **engine note n sounds two octaves below the 2A03's n with a one-cycle wave**
  (engine note 70: frequency 1031 = 439.9 Hz; engine note 46: 258 = 110 Hz; NESdev, Disch, FamiStudio's own Nes_Fds and its
  register viewer `RegisterInterpreter.cs` agree). Thor Tracker keeps the table (FamiStudio parity, `.nsf`), so its
  FDS channel plays **absolute note a as engine note a + 13** (absolute 57 = A-4 = 440 Hz; range C-1..G#6 = engine
  25..93; above engine note 93 the 12-bit frequency overflows).

### CPU memory map additions

| Range | Use |
|---|---|
| `$4040-$407F` | FDS wave RAM (writes while `$4089` bit 7 is set; reads `$40` + sample) |
| `$4080-$408A` | FDS sound registers (writes) |
| `$4090-$4097` | FDS sound state (reads) |

### Image layout additions (Thor layout, FDS build)

- **Song header**: `u8 1`, `word instruments`, `word instruments_exp`, `word samples−4`, 6 channel stream words, PAL
  tempo, NTSC tempo (7 bytes before the stream words, `nes_player_head`). The raw layout accepts the same header.
- **Expansion instrument table**: one 16-byte entry per instrument (the same count as the 2A03 table; non-FDS slots
  carry well-formed default FDS data), as FamiStudio's `FamitoneMusicFile.cs` writes it and
  `famistudio_set_fds_instrument` / `famistudio_update_fds_wave` read it (`famistudio_ca65.s` 5235-5435): words volume
  (kind 0), arpeggio (kind 1), pitch (kind 3), the **wave-index envelope** (kind 4, directory **256 + slot − 1**);
  byte 8 = mod depth << 2 | master volume (any value); word 9 the wave table; word 11 the **mod table** (32 entries
  0..7 inside the song data; may be shared); bytes 13-14 the manual mod speed word (0..4095), with `hi | $80` when the
  low byte is `$80-$FF`. That high-byte tag distinguishes the manual word from auto-mod `$80 | numerator, denominator`
  (1..32 each). Other manual words retain FamiStudio's bytes. Byte 15 is the mod delay (0..255).
- **Wave table**: one pointer per distinct wave (FamiStudio's dedup order), each to 64 samples 0..63 inside the song
  data; a table entry for every index the wave-index envelope produces (largest value + 1).
- **Directory kind 4 = WAVE** is valid on N163 and FDS images ("envelope kind outside 0..4"); effect envelopes start at
  **320** in FDS images.
- **Opcodes on the FDS channel (5)**: `$57 lo hi` mod speed (≤ `$0FFF`), `$58 d` mod depth (≤ 63) — both set the
  engine's value and its override flag, so an instrument loaded later in the same row keeps them; the values persist
  until the next instrument load without override —, `$59` release (0 operands, ends the row: the wave-index and the
  volume envelopes jump to their release points), `$53` phase reset (`famistudio_process_phase_resets` writes
  `$4083` = prev_hi | `$80`, then prev_hi), `$4E f` fine pitch (frequency offset, + raises; pitch shift 0), vibrato,
  arpeggio, volume, slides, instruments, delays, cut. Thor Tracker's `$5F i` loads the actual zero-based instrument slot
  and envelope pointers while retaining runtime modulation (table, speed, depth, auto ratio and delay). It temporarily
  sets unused bit 5 of `famistudio_fds_override_flags` around that load and restores the row's original flags afterward.
  This preserves both loop-held effects and the correct target for live instrument edits.
  Rejected: `$4F` and `$52` on FDS, `$57`-`$59` off FDS, `$55`/`$56`/`$5A`/`$5B` on the FDS build.
  `$5F` is valid only on FDS in this build (its separate Sunsoft 5B meaning is unchanged).
- **Engine behaviours kept**: auto-mod multiplies the 16-bit frequency by the numerator in 16 bits (`famistudio_mul`
  wraps), then divides and clamps the result to `$FFF`; mod delay d keeps modulation reset for d − 1 frames and
  enables it in frame d (`dec; bne`). The tagged manual-speed correction avoids upstream's ambiguous low-byte flag;
  the app and NSF/WAV exports support the full range. FamiStudio text preserves exact speeds, but upstream 4.5.3
  assembly/NSF export can reintroduce that ambiguity.

### Live edits, audition, ops

- **Live edit** (`nm_apply_image`, Thor to Thor): the FDS channel's wave-index envelope (slot kind WAVE) is remapped
  like every envelope pointer and `famistudio_exp_instrument_lo/hi` follow the new table. For the channel's current
  sound (`famistudio_chn_fds_instrument`), decided while the old bytes are in place:
  - master volume, wave count or any wave's 64 samples changed (by content): `famistudio_fds_wave_index` = `$FF`, the
    next engine frame uploads the wave (the engine uploads only on a wave index change);
  - its mod table changed by content: the machine writes the new table to the chip at once as the engine does on
    loading a sound (`$4087` = `$80`, `$4084` = `$80`, `$4085` = 0, 32 × `$4088`; the engine's next update re-enables
    the unit) and `famistudio_fds_mod_envelope` takes the new pointer; moved but identical: only the pointer moves
    (the engine compares pointers, so a later load sharing the table writes nothing, as in straight play);
  - mod speed or auto-mod fraction, depth and delay: where the engine's value equals the old sound's own (no `$57` /
    `$58` override in effect), the new sound's is taken, as a reload would.
  Chip state is otherwise never touched.
- **Audition** on the FDS channel: `$54 i`, `$4E f`, `$40 n` as on the other pitched channels (engine notes); the
  release stream is `$59`. `release()` returns 1 if the volume envelope has a release point (the tail ends at its final
  loop) or else the wave-index envelope has one (the tail ends at that envelope's final loop); otherwise 0 (cut).
- **Ops** (`nes_chip.c`): `nes_fds_chip_ops`, id `"nes-famistudio-fds"`, `image_size` = `NES_FDS_IMAGE_SIZE`, the same
  functions as `"nes-famistudio"` over a machine of the FDS build. `.nsf` export: the FDS player at `$8000`, the data at
  its song base, header byte `$7B` = `$04` (FDS, `NsfFile.cs`).

### Preparing the player

`python3 tools/audio/prepare_nes_player.py` builds the FDS player with the others, its header and `NesFdsPlayerImage.kt`,
and from `tools/audio/nes/fds_test.txt` (exported by FamiStudio 4.5.3) the fixtures `app/src/test/cpp/nes_fds_song.h`:
raw at the FDS song base, the Thor layout, every block (wave tables, waves and mod tables included) moved, and an edited
copy (one note, one volume envelope byte, one wave sample, one mod table entry; `FDS_TEST_EDIT_*`), with the order
lengths (`nes_fds_order_rows`) and each FDS instrument's waves, mod speed, depth, delay and auto-mod fraction.

### Verification

- `tools/audio/run_native_host_test.sh [dir]` builds and runs `app/src/test/cpp/nes_fds_test.c`: FDS unit checks from
  NESdev (wave frequency CPU × freq / 2^22 by zero crossings and the 24-bit accumulator; true pitch: engine note 70 =
  440 Hz and 46 = 110 Hz with a one-cycle wave, the player's note table = FamiStudio's; mod table ring writes, `$4088`
  ignored while running, the ghost bit, every step value, 7-bit wrap both ways, mod frequency carries, `$4087` bit 7
  keeping the address; the modulation formula's examples and every counter × gain against the wiki's code; halt and
  reset; write-enable hold; the gain latch at position 0 and the immediate mute; master volume ratios; the 2.4 × pulse
  level, gain clamp and DAC curve; envelope timing, clamps, `$408A`, `$4083` bit 6; every read; mute and a silent run
  leaving the unit state identical); image rules (raw and Thor loads, refusals of the other builds' images and of
  `$57`-`$59` there, kind 5, wave table / wave / mod table outside the data, a sample of 64, an entry of 8, auto-mod
  fractions 0 and 33, manual speed `$1000`, a wrong-kind wave-index word, `$57`/`$58` ranges, `$57`/`$58`/`$59` off the
  FDS channel, `$4F`/`$52`/`$55`/`$56`/`$5A`/`$5B` on it, `$53` on the triangle); playback of the test song (**the Thor
  layout writes the same APU and FDS registers as FamiStudio's own export through the first pass and two loops**, row
  starts, the instrument's current wave in the chip's wave RAM, its mod table in the chip's ring with
  `famistudio_fds_mod_envelope` pointing at it, the mod unit every frame — halted on cut and delay, depth and manual or
  auto-mod speed `min($FFF, freq × n / d)` — `$57`/`$58` rows and re-binds without them); the wave index following
  FamiStudio's envelope walk and `$59`; delay d enabling in frame d; a `$53` row; seek (registers for 200 frames and
  the full unit state equal to audible straight play); live relocation before every frame with identical writes and
  unit state; the edited wave uploaded with the next frame; the edited mod table written as the engine loads one;
  the edited note; audition (the note table frequency, fine `+10`, `$59` reaching the wave release, cut); mute; the ops
  table. Evidence: `nes_fds.wav`, `nes_fds_trace.txt` (per-frame APU and FDS writes), `nes_fds.nsf` (`$7B` = `$04`).
- Full 12-bit manual modulation speeds include the ambiguous low-byte range `$80`–`$FF`, checked against independent
  `$57` speed-command witnesses. Manual and automatic instrument-copy fixtures exercise the second loop and live
  master-volume edits; `$5F` keeps runtime modulation while retaining the actual bank slot. Their register traces
  also match independent FamiStudio exports. The native FDS suite currently has 227 checks.
- Production-signed Android and desktop smoke evidence, including same-device Game Boy pixel parity and the
  Android/desktop FDS export comparison, is recorded in `docs/VERIFICATION.md`.
- Not done: listening on a Famicom with a RAM adapter, comparison with a reference FDS emulator's audio (NSFPlay,
  Mesen), Thor measurements.

## Sunsoft 5B: the same engine with Sunsoft's 5B (`"nes-famistudio-s5b"`)

A further NES expansion chip (design in `docs/BACKENDS.md`, "NES expansion chips"). Everything in the NES
section holds; this section lists what differs. The 2A03 and VRC6 builds' player bytes, image sizes, song
bases and fixtures are unchanged (`prepare_nes_player.py` checks its outputs for them stay byte-identical).

### What runs

- **Player** (`generated/nes_s5b_player_image.h`, `NesS5bPlayerImage.kt`): `tools/audio/nes/nes_player.s`
  assembled with `-D FAMISTUDIO_EXP_S5B=1` and a scoped `$5F` extension described below. The generator patches
  a temporary copy of the pinned FamiStudio source; the vendored source stays unchanged. The extension adds
  30 bytes (3552 total), no RAM, and leaves the song base at `$8E00`. The same entry table and configuration
  use FamiStudio's 5B code (its squares use the 2A03 note table). 8 channels, **23 envelope slots** (a 5B square has
  four: volume, arpeggio, **mixer** at offset 2, **noise** at offset 3; `famistudio_channel_env` = 0, 3, 6, 8, —,
  11, 15, 19), 6 pitch envelope slots (the squares' are 3-5). Song base: the first page after the player
  (measured by the script, `NES_S5B_SONG_BASE` / `NesS5bPlayerImage.SONG_BASE`); the region up to `$FFF9` is
  shared with the DPCM samples as on the 2A03 build; image = 32-byte header + directory + region
  (`NES_S5B_IMAGE_SIZE`).
- **Channels**: engine channels 0-4 as the 2A03 build, then **5 5B Square 1, 6 5B Square 2, 7 5B Square 3**.
- **Song header** (FamiStudio's for expansions with their own instruments): count, instruments,
  **instruments_exp**, samples − 4, eight channel streams, PAL tempo, NTSC tempo (`nes_player_head` = 7 bytes
  before the stream words). The Thor layout keeps one order stream per channel (`$41 n ptr` per order, `$42`).
- **Instruments**: the 2A03 table (count × 8 bytes, as before; every slot has one) and the expansion table at
  header word 3, one **16-byte entry per slot** (`FAMISTUDIO_USE_INSTRUMENT_EXTENDED_RANGE`: a 5B square's
  instrument byte `$80 | i << 1` / `$54 i` indexes entry i × 16; FamitoneMusicFile.cs `instruments_exp`,
  `famistudio_set_s5b_instrument`):

  | Offset | Size | Field |
  |---|---|---|
  | 0 | 2 | volume envelope (directory entry of kind 0) |
  | 2 | 2 | arpeggio envelope (kind 1) |
  | 4 | 2 | pitch envelope (kind 3) |
  | 6 | 2 | **mixer** envelope (kind 5) |
  | 8 | 2 | **noise** envelope (kind 6) |
  | 10 | 1 | hardware envelope shape: 0 = none, else `$08-$0F` (FamiStudio's S5BEnvelopeShape 1..8 + 7) |
  | 11 | 1 | auto-period octave, signed −8..8, or `$80` = manual period |
  | 12 | 2 | manual envelope period (u16, any value; used with `$80`) |
  | 14 | 2 | 0 |

  `nm_load_image` checks the five words are directory entries of those kinds, the shape, the octave and the
  zero bytes, and that the table lies inside the song data (raw layout: that the pointer does).
- **Directory kinds 5 (mixer) and 6 (noise)**, legal in 5B and EPSM images and rejected by other builds.
  Neither has a header byte (instructions from index 0, like arpeggio and duty). Mixer values are
  FamiStudio's processed bits, tone off in bit 0 and noise off in bit 3: `$C0` (tone + noise), `$C1` (noise),
  `$C8` (tone), `$C9` (neither: a constant level at the volume); noise values 0..31 (`$C0-$DF`). Hold counts and
  loops as every envelope. Thor Tracker's directory indices: slot × 4 + kind for volume, arpeggio, duty and
  pitch (unchanged), mixer 256 + 2 × slot, noise 256 + 2 × slot + 1, effect envelopes (`0xy` arpeggios,
  vibratos) from **384** (`NesPlayer.effect_envelopes`; EPSM also uses 384, N163/FDS use 320, the remaining builds 256).
- **Opcodes**: `$5E lo hi` (5B squares only; not a terminator) sets the shared manual envelope period
  (`famistudio_s5b_env_period`) and `famistudio_s5b_env_override` for the frame, so an instrument loaded later
  in that frame keeps the effect's period. FamiStudio's export moves every envelope period effect to 5B Square
  1 (Thor Tracker's lowering likewise); the walker accepts it on any square and rejects it elsewhere and in
  other builds. `$4F` (duty) and `$53` (phase reset) are invalid on the squares; `$55` stays VRC6-only. An order
  does not re-send a manual-period sound its square holds (the load would set the shared period).
  `$5F i` is Thor Tracker's extension for this 5B build: load the actual zero-based instrument slot while keeping the
  shared manual period. The handler saves `famistudio_s5b_env_override`, sets it for this load, then restores
  it and continues the stream. This is used for renamed, otherwise identical manual-period instruments:
  FamiStudio merges them and emits no load, but Thor Tracker must retain distinct slot identity for live edits.
  Ordinary changes and forced reloads still use the original load instruction. The walker validates the square,
  build and slot; independent FamiStudio exports do not use this opcode. The manual-copy reference song
  previously diverged at frame 24 (900 instead of 2000); the extension restores trace equality.
- **Per frame** (`famistudio_update_s5b_channel_sound`, squares in order 0..2): tone period = note table + pitch
  envelope, fine pitch and slide **+ 1** (`$00-$05`; the 5B divides by 32 × period, so a note sounds an octave
  below a pulse's: Kotlin sends the 5B squares FamiStudio's note + 12, true pitch); volume = envelope × volume track, with bit 4 (E) when the
  instrument has a shape (`$08-$0A`). The last square with a shape and a note sets the hardware envelope: its
  octave `$80` takes the manual period, else its tone period shifted by the octave (left for negative octaves,
  right for positive ones with the last shift rounding an odd value up); `$0B/$0C` are written then, and `$0D` =
  shape after any square's note attack (bit 7 of `famistudio_s5b_chn_env_shape`). `$07` = the three mixer
  envelope values shifted by square (bits 0-2 tone off, 3-5 noise off), `$06` = a noise envelope value (the
  engine's noise mask only tests Square 1's mixer correctly: Squares 2 and 3 always store theirs; kept as it
  plays). Writes go through `$C000` (select) and `$E000` (data); init writes `$07` = `$38`.
- **5B audio** (`nes/nes_s5b.c`, written here from the NESdev wiki "Sunsoft 5B audio"; emu2149, FamiStudio's 5B
  core, MIT, read as a reference for the noise and envelope details, no code taken): `$C000-$DFFF` select (bits
  4-7 set: data writes ignored until a clean select), `$E000-$FFFF` data; reads there return the ROM. One /16
  prescaler clocks every generator: tone up-counters (period 0 = 1; a reach flips the square and resets; a
  shortened period flips at the next clock), f = CPU / (32 P); noise: the same counter on 5 bits toggling a /2
  prescaler whose rising edge steps a 17-bit LFSR (bit 0 ^ bit 3 into bit 16, seed 1, output = !bit 0); envelope:
  16-bit period (0 = 1), 32 levels per ramp, `$0D` restarts it at once, NESdev's shape table (`$00-$07` as `$09`
  / `$0F`; at a ramp's end: no continue → 0 held; hold → attack ^ alternate ? 31 : 0 held; alternate → turn
  around, the edge level repeated; else the ramp restarts). Mixer: on = (tone off | square) & (noise off |
  noise); level = the envelope's (E) or 2 V + 1; DAC logarithmic 1.5 dB per step, 31 full, levels 0 and 1 silent;
  the three channels mixed linearly. **Mix level**: a channel at full = the 2A03 pulse at 15 (95.88 /
  (8128 / 15 + 100)) × 1.56563, FamiStudio 4.5.3's balance (Nes_Sunsoft's 0.793774 × 4080 / 12240 over
  Nes_Vrc6's square 0.169; NESdev: a full VRC6 pulse ≈ a full 2A03 pulse); the 5B build's output gain is 2.0 /
  (1 + 3 × that level) (the 2A03 and VRC6 builds keep 2.0). The 5B's amplifier nonlinearity and filtering are
  not modelled. With output the unit runs clock by clock (deltas at their CPU cycle); without (seek) every
  counter, the LFSR and the envelope advance arithmetically to the same state.
- **Machine**: only the build's expansion unit is reset, clocked and mixed. Seek runs the 5B silently, so tone
  phases, the LFSR and the envelope are those of straight play. Mute 5-7 leaves a square out of the mix (it keeps
  running). `nm_write_log` gives the `$C000`/`$E000` writes with the APU's. Live edits: the machine sees on the
  bus which expansion entry each square loaded last (the engine's store to `famistudio_s5b_chn_env_shape` right
  after its read of entry + 10) and which entry the manual period came from (the store to
  `famistudio_s5b_env_period` after reading entry + 12; a `$5E` makes it none); `apply_image` rewrites each
  square's shape nibble (keeping bit 7, a pending reset: the new shape is written to `$0D` at the next attack)
  and octave from its entry, the manual period from its entry, and a square whose entry turned manual after the
  period's last store loads its period as the engine's own load would; the expansion table pointer moves with the
  table. An identical image changes nothing (the host test applies a moved image before every frame). Audition
  takes the squares (fine pitch on them), release is the generic `$45` (a volume release point).
- **Ops** (`nes_chip.c`): `nes_s5b_chip_ops`, id `"nes-famistudio-s5b"`, `image_size` `NES_S5B_IMAGE_SIZE`, the
  same functions as `"nes-famistudio"` over a machine of the 5B build. `.nsf` export: the 5B player, header byte
  `$7B` = `$20` (Sunsoft 5B).

### Preparing the player

`python3 tools/audio/prepare_nes_player.py` builds the 5B player with the others and writes
`nes_s5b_player_image.h`, `NesS5bPlayerImage.kt` and the fixtures `app/src/test/cpp/nes_s5b_song.h` from
`tools/audio/nes/s5b_test.txt` as exported by FamiStudio 4.5.3 (`s5b_test_ca65.s`; `--famistudio DIR` writes it
when missing): raw at the 5B song base, the Thor layout (both instrument tables padded to one count), every block
moved, and an edited copy (one note, the SLead volume sustain, SEnvManual's manual period, SEnvAuto's shape).

### Verification

- `tools/audio/run_native_host_test.sh [dir]` builds and runs `app/src/test/cpp/nes_s5b_test.c`: 5B unit checks
  (the select latch and register widths; tone flips per period incl. 0, 4095 and the immediate flip on a
  shortened period; the LFSR's first steps, sequence and maximal 131071 period through the /2 prescaler; all 16
  shapes' 200-step level sequences and holds at two periods, period 0, a 16-bit period, the `$0D` restart; E bit
  and 2 V + 1 volumes; the mixer's AND and DC; the DAC's steps; the mix level; mute; random register writes giving
  identical state silent and audible; a synthesized 220 Hz tone); image rules (raw and Thor loads, the expansion
  entries, the builds refusing each other's images, mixer/noise kinds and values, shape, octave, zero bytes,
  table pointers, `$5E` only on the squares, no duty or phase reset there); playback (**the Thor layout writes
  the same APU and 5B registers as FamiStudio's own export through the first pass and two loops**, row starts,
  `$07` = the mixer envelopes every frame, `$0B/$0C` = the auto or manual period in every frame with an envelope,
  the note-table period + 1 and its pitch, the `$5E` keeping its period against a same-frame instrument load);
  seek to six targets (the unit's state equal to straight audible play). A separate manual-copy fixture proves
  the order fallback restores held instruments: without restoration, the slot is −1, the shape is cleared
  and Square 1 is silent; with restoration all ten checks pass. Fallback still does not reconstruct prior
  shared-period effects or generator phases. Live relocation
  before every frame with identical writes, the SLead sustain and note edits, SEnvManual's period and SEnvAuto's
  shape followed at once, and edits (also an octave change and an entry turned manual) giving the envelope state
  of a fresh start on the edited data at seven rows; audition (period + 1, fine pitch, release tail, the hardware
  envelope running); mute; the ops table. Evidence: `nes_s5b.wav`, `nes_s5b_trace.txt`, `nes_s5b.nsf`.
  `wav_render_test.c` renders the demo `copper-kite.nesimg`.
- Not done: listening on a 5B cartridge, the 5B amplifier's nonlinearity, comparison with a reference emulator's
  audio (NSFPlay, FamiStudio), Thor measurements.
## MMC5: the same engine with Nintendo's MMC5 (`"nes-famistudio-mmc5"`)

The second NES expansion (design in `docs/BACKENDS.md`, "NES expansion chips", MMC5). Everything in the NES
section holds; this section lists what differs. Source-only so far: the numbers below that depend on the assembled
player (size, id, RAM, song base, image size) are written by `prepare_nes_player.py` into the generated files and
are not repeated here until the parent generates and verifies them.

- **Player** (`generated/nes_mmc5_player_image.h`, `NesMmc5PlayerImage.kt`): `tools/audio/nes/nes_player.s`
  assembled with `-D FAMISTUDIO_EXP_MMC5=1`, nothing else changed (same entry table and configuration, the 2A03
  note tables; no extra tables). 17 envelope slots (`3+3+2+3+3+3`) and 5 pitch envelope slots; the MMC5 pulses'
  envelopes start at slots 11 and 14, their pitch envelopes are 3 and 4; RAM adds `famistudio_mmc5_pulse1_prev` /
  `_pulse2_prev` (the period-high caches, `$80` at init).
- **Song base** `NES_MMC5_SONG_BASE`: the first page after the player, measured by `prepare_nes_player.py` (it
  assembles once at a provisional base, then at the page after the measured end; the base only changes the operands
  of the init call). Region = song base..`$FFF9`, samples as before; image = 32-byte header + directory + region
  (`NES_MMC5_IMAGE_SIZE`, at most the 2A03 build's).
- **Channels**: 0-4 as the 2A03 build, then **5 MMC5 Pulse 1, 6 MMC5 Pulse 2**; the FamiStudio song header has
  **seven** stream words, the Thor layout seven order streams.
- **Opcodes**: the plain 2A03 set; the engine has no MMC5 opcode (`$55`, VRC6 saw volume, is refused). `$4F d` takes
  0-3 on the MMC5 pulses (`famistudio_duty_lookup`, as the 2A03 pulses); `$53` (phase reset) is valid on the 2A03 and
  MMC5 pulses (`famistudio_channel_to_phase_reset_mask` `$20`, `$40`; it rewrites `$5003`/`$5007` with the cached
  period high). Duty envelopes 0-3. Notes use the 2A03 pulse table: the same period on MMC5 Pulse 1 as on Pulse 1 for
  every note (absolute 57 = engine note 46 = A4 ≈ 440 Hz); A-1 is the lowest note (11-bit periods).
- **Register writes** (`famistudio_ca65.s`): init `$5010 = $00`, `$5015 = $03` (lines 1390-1399); every frame
  `famistudio_update_channel_sound` per MMC5 pulse (lines 4292-4294): `$5002`/`$5006` (period low), `$5003`/`$5007`
  only when the period high changes (no `FAMISTUDIO_CFG_SMOOTH_VIBRATO` sweep trick, which needs a sweep unit: a
  crossing restarts the duty sequence, audible as a click), `$5000`/`$5004` = duty | `$30` | volume. Never written:
  `$5001`/`$5005` (sweep), `$5011` (PCM) and `$5104` (ExRAM mode).
- **PCM: not driven by FamiStudio's engine; played by Thor Tracker's software PCM lane.** Header line 107: "Nintendo
  MMC5 (2 extra squares, extra DPCM not supported)"; the MMC5 build has no PCM channel, opcode or `$5011` write, and
  FamiStudio 4.5.3 never activates its `Mmc5Dpcm` channel type. The engine keeps its **seven** channels (generated
  player unchanged, `CHANNELS = 7`); MMC5 songs have an **eighth lane, MMC5 PCM** (`NesRules.MMC5_PCM` = 7,
  `NesExpansion.engineChannels` = 7), played by `nes/nes_mmc5_pcm.c`: a host-side sample player that writes the real
  DAC (`$5011`, write mode) on the machine's CPU-cycle timebase. It is not emulated MMC5 hardware (the DAC has no
  sample clock or DMA) and the emulated 6502 does not pay the streaming cost a real game would. Details below,
  "MMC5 PCM lane".
- **MMC5 audio** (`nes/nes_mmc5.c`, written here from the NESdev wiki "MMC5 audio"; see its header): pulses as the
  2A03's (shared `nes_duty_table` and `nes_length_table` from `nes_apu.c`) without a sweep unit and without
  silencing periods below 8; envelopes and length counters clocked together every 7424 CPU cycles (241.08 Hz;
  NESdev's die-image figure, not bench-confirmed); `$5015` enables (reads give the length status); `$5010` mode and
  IRQ enable, `$5011` write-mode 8-bit DAC (`$00` keeps the DAC and trips the IRQ flag; ignored in read mode).
  **Read-mode PCM** feeds the DAC on every CPU read of `$8000-$BFFF`, including opcode fetches and dummy reads;
  direct `$5011` writes are ignored in that mode. The unit resets its DAC to 0; the software PCM lane centres it
  at 128 while output is detached. Mix: the pulses through the 2A03 pulse formula, the DAC as a DMC level of the same value (up to twice
  the DMC's 127) through the tnd formula, the sum **negated** (NESdev: every MMC5 channel has the opposite polarity)
  into the 2A03's band-limited buffer. Event-driven with output; arithmetic without, so seeks clock every timer,
  sequencer, envelope and length counter.

#### MMC5 PCM lane

- **Image**: `"nes-famistudio-mmc5"`'s `image_size` is `NES_MMC5_IMAGE_SIZE` (the seven-channel FamiStudio image,
  byte-for-byte as before) + `NES_PCM_EXT_SIZE` (411020); `image_storage` = `sizeof(NesMmc5Image)` (other engines keep
  `sizeof(NesImage)`). Extension (little-endian; `nes_mmc5_pcm.h`, `NesPcmLowering`): header 0 `"TTPC"`, 4 version 1,
  5 samples (≤ 64), 6 u16 mappings (≤ 6144 = 64 slots × 96 keys), 8 u32 data bytes (≤ 262144), 12 u16 events
  (≤ 16320 = 255 orders × 64 rows), 14 orders (= the FamiStudio image's), 15 zero; at 16: 255 × {u16 first event,
  u16 count} (identical orders share a range); at 1036: 64 × {u32 offset, u32 length, u32 loop start}; at 1804: 6144 ×
  {u8 sample, u8 flags (bit 0 loop), u16 binding, u32 period}; binding = `(slot−1)×96 + key−12`, strictly ascending.
  At 50956: 16320 × {u8 row, u8 flags (1 note, 2 OFF, 4 volume, 8 delay, 16 cut), u16 mapping,
  u16 volume bits 0-3 / delay 4-8 / cut 9-13}; at 148876: sample bytes, each 1..255.
  `nes_pcm_load` checks reserved bytes, counts, ranges, rows, periods, nonzero data and unique ascending bindings
  0..6143. A row cannot combine delay and cut, matching the authored lane's single effect column.
- **Periods** are 16.16 CPU cycles per sample step: `round(1789773 × 65536 / (rate × 2^(pitch/12)))`, computed by the
  lowering (`Pcm.period`) and checked against 8..32768 cycles; rates 1000..48000 Hz and pitches −48..+24 semitones give
  9.3..28636 cycles, so no u32 overflow.
- **Timing**: at cycle 0 before the play call, a new row forces any pending delayed note/OFF and applies its own
  event; other frames advance the delay countdown. Cuts are counted after that and survive row and order boundaries.
  Any immediate or fired delayed note/OFF cancels an earlier cut; the new row's own `Sxx` is installed after its note,
  so `S00` cuts immediately. Steps land on the cycle their 16.16 time rounds up to; the lane runs to the current cycle
  before every MMC5 register access and at frame end, so writes reach the unit in time order.
- **DAC rules**: value = 128 + (sample − 128) × volume / 15 (1..255; never `$00`, which would hold the DAC and trip
  the IRQ); silence is 128. Reset always centres it, including songs with no mappings. Muting machine channel 7
  mixes the centre value instead of zero while position and phase continue; idle mute/unmute introduces no step.
- **Seek**: the silent fast-forward counts steps arithmetically (equal to stepped play). The order fallback walks the
  earlier orders' events once (rows last the speed in effect after them: `nm_row_speeds`, tempo 150) and reconstructs
  voice, position, phase, volume and pending delay/cut at the order start, including cuts crossing rows and orders,
  using straight play's 29780/29781-cycle frame alternation; later frames of the fallback may alternate differently
  (at most one cycle per two frames).
- **Live edits** (`nm_apply_image_pcm`): held and pending notes follow the authored slot/key binding, found by binary
  search, not their compact table indices. Inserting an earlier key cannot retarget a note. An unchanged sample,
  even moved, retains position and phase and adopts its binding's new pitch/loop settings; a removed binding or
  changed sample stops the held note at 128 or cancels the pending one. Machines copy the extension; no image pointer is retained.
  The lane's state is allocated only in `nm_create` (MMC5 build); start, tick, apply and seek allocate nothing.
- **Audition**: `nm_audition_pcm` on channel 7 with note = mapping index + 1; held (a loop sustains), release cuts.
- **Mix budget** (also the N163 builds): total span W = 1.0 (2A03) + `NES_MMC5_SPAN` (≈ 1.4115), or +
  `NES_N163_SPAN` (≈ 0.5947). The bound includes blip_buf's interpolated kernel and high-pass plus the machine's
  37 Hz high-pass and 14 kHz low-pass: the step-response total variation gives peak/W ≤ 1.1188 before the int16
  stage and ≤ 1.1184 after the filters over the supported 8–96 kHz range. Use 1.12 with margin:
  every unit scales by `32000 / (1.12 × W)`, and post-filter gain is `0.98 × 32768 / 32000`.
  The derived peaks are below 31966 internally and 0.979 at output, without changing relative chip levels.
  MMC5 is about 14.1 dB and N163 10.5 dB quieter than the plain 2A03 build at equal 2A03 content.
  `nes_mix_budget_test.c` exercises simultaneous register swings, relative levels and idle mute through `nm_tick`;
  rendered verification is recorded separately. Every other build retains its scale and gain.
- **Exports**: `.nsf` and FamiStudio text refuse a song whose PCM lane plays a note (`NesPcmLowering.exportProblem`);
  with a silent lane they export the seven channels unchanged (unplayed PCM samples and mappings stay in the project);
  a PCM-lane `Fxx` becomes the song speed on Pulse 1 where no engine lane sets one that row (`FsSong.of`). WAV export
  runs the engine image, lane included.
- **Machine** (`nes_machine.c`): `nm_create(rate, &nes_player_mmc5)`; writes to `$5000-$5FFF` go to `nm_write_log`
  (so a stray ExRAM write would show) and the audio registers to the unit; `$5010`/`$5015` reads come from the unit.
  Seek, live relocation, audition (channels 0-3 and 5-6, fine pitch on 0-2 and 5-6) and mute (5-6) as on the other
  builds.
- **Validation** (`nes_image.c`): seven streams against this build's song base; duty 0-3 everywhere ("MMC5 duty
  outside 0..3" on the MMC5 pulses), `$53` on channels 0, 1, 5, 6, `$55` refused.
- **Ops** (`nes_chip.c`): `nes_mmc5_chip_ops`, id `"nes-famistudio-mmc5"`, `image_size` `NES_MMC5_IMAGE_SIZE`.
  `.nsf` export: the MMC5 player at `$8000`, the data at its song base, header byte `$7B` = `$08` (MMC5).

### Preparing the player

`python3 tools/audio/prepare_nes_player.py --famistudio "$(tools/audio/fetch_famistudio.sh)"` once (it writes
FamiStudio 4.5.3's export of `tools/audio/nes/mmc5_test.txt` to `mmc5_test_ca65.s` when missing), then plain runs.
It writes `nes_mmc5_player_image.h`, `NesMmc5PlayerImage.kt` and the fixtures `app/src/test/cpp/nes_mmc5_song.h`
(raw at the song base, the Thor layout, every block moved, and order 2 MMC5 Pulse 1 row 8 E4 → G4 plus the
MPulse sustain edited), and checks the seven existing outputs are unchanged. `mmc5_test.txt` (`Expansions="MMC5"`,
2A03 instruments only) covers duty envelopes 0-3 and `Vxx`, vibrato, slides (one crossing a period-high
boundary), arpeggio, fine pitch, volume and volume slide, delay, cut, release, no-attack, phase reset, the A-1 and
B-8 edges and instruments shared between 2A03 and MMC5 pulses. FamiStudio's text names notes an octave below
scientific pitch (its "A3" is absolute 57, 440 Hz).

### Verification (to be run by the parent; nothing here has been built or run yet)

- `tools/audio/run_native_host_test.sh [dir]` builds and runs `app/src/test/cpp/nes_mmc5_test.c`: unit checks
  against NESdev (duty steps, no sweep, periods below 8 audible, the 7424-cycle clock, `$5015`, length halt, `$5011`
  write and read modes, inverted polarity, mute); pitch (the MMC5 and 2A03 pulse periods equal for engine note 46,
  ≈ 440 Hz); FamiStudio's export vs the Thor layout (identical APU + MMC5 writes and row timing through a loop);
  seek with clocked unit state; live relocation and edits; audition and mute on 5-6; image rules; the ops; the
  driver's init writes and the registers it never writes. Evidence: `nes_mmc5.wav`, `nes_mmc5_trace.txt`,
  `nes_mmc5.nsf` (`$7B` = `$08`). `wav_render_test.c` renders `tandem-skies.nesimg` and the shortest valid MMC5
  song `mmc5-shortest.nesimg` (one row, speed 1).
- Kotlin: `NesGoldenTest` golden songs `mmc5_demo` and `mmc5_effects` (fixtures generated with
  `THOR_UPDATE_NES_GOLDEN=1` and `tools/audio/nes_golden.py`), `NesLoweringTest`, `NesExportTest`,
  `NesProjectCodecTest`, `NesInstrumentViewTest`.
- Not done: any build or run, listening, an MMC5 cartridge, a reference-emulator comparison.

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

## YM2612: SGDK's XGM2 driver on a Z80 + YM2612 + PSG (`"ym2612-fm"`)

Thor Tracker's integer player (`ym/ym_player.c`) turns rows and effects into register
writes and PCM commands. `ym/ym_xgm.c` encodes them into separate FM and PSG XGM2
streams, consumed by the unmodified SGDK driver on an emulated Z80. The driver,
not a parallel host mixer, mixes the three PCM lanes into the YM2612 DAC.
The independent Python player is `tools/audio/ym/ym_ref.py`.
Kotlin's `system/ym2612`, `backend/YmLowering` and `YmPcmLowering` produce the
layout below; shared constants are generated into `backend/YmPlayerImage.kt`.

### What runs

- **Driver:** SGDK commit `9a78a441`, MIT, `third_party/sgdk-xgm2/`.
  `generated/xgm2_driver_image.h` is 8192 bytes, assembled with SGDK's sjasm
  and compared against `drv_xgm2` in its `lib/libmd.a`.
  SHA-256: `c7c9d630faf2397580d407878008c8cb7c836ad3d6c0c0ab26d6db9b59583358`.
- **Z80:** floooh's `z80.h`, at MCLK/15. RAM `$0000-$1FFF` mirrors to `$3FFF`;
  YM2612 ports are at `$4000-$4003`, mirrored through `$5FFF`; nine serial writes
  to `$6000` select the 68000 bank at `$8000-$FFFF`; PSG writes use `$7F11`.
  The emulated cartridge maps the FM ring at `$020000`, PSG ring at `$030000`,
  and PCM bank at `$100000`. Each stream ring is 64 KiB; the bank is at most 1 MiB.
  Unmapped ROM reads return `$FF`. Accesses to the 68000 bus (`$7F00-$FFFF`) add
  three Z80 wait cycles; chip clocks and the interrupt line continue during the wait.
  The 68000 side implements SGDK's XGM2 RAM handshake; it does not emulate a 68000 CPU.
- **YM2612:** ymfm `81aec25c` (BSD-3-Clause), through `ym/ym_ymfm.cpp`.
  Clock MCLK/7, one generated sample per 1008 master clocks. Busy timing and
  timer A are visible to the Z80. Timer A belongs to the driver's PCM mixer;
  FM3 special mode uses bit 6 of `$27`, without overwriting the timer-control bits.
- **PSG:** `ym/ym_psg.c`, Sega's SN76489: three 10-bit tones, 16-bit noise
  LFSR with white-noise taps `$0009`, periodic noise, and four 2 dB attenuation
  controls. Noise source 3 shares tone 3's period.
- **Timing:** one tick is a vertical interrupt, held for one line (228 Z80
  cycles). NTSC uses MCLK 53693175 Hz and 262 × 3420 master clocks per tick
  (about 59.92 Hz); PAL uses 53203424 Hz and 313 lines (about 49.70 Hz).
  Chips keep running through driver work and seek. The old XGM1 pass-replay
  optimization is not used.
- **Output:** YM2612 stereo plus centred PSG, resampled with blip_buf at the
  Z80 clock rate. Float output is the mixed sample divided by 32768.
  Gain constants are `YM_FM_GAIN` and `YM_PSG_GAIN`; PCM is already in the
  YM2612 output, not mixed a second time.

### Streaming XGM2

The driver prefetches music. The machine keeps an encoded frame ahead of execution
and tracks the driver's consumed frames separately from the player's position.
`position()` and `row_serial()` must follow execution, including repeated
one-row loops; enqueueing another frame is not proof that it was heard.
When a frame is still pending, the producer stops advancing rather than overwriting
its position history. Full register frames occur at stream start and the first loop
arrival, not on every subsequent loop.

FM and PSG have independent packed streams. Each unpacked frame, including its
end command, must fit in 255 bytes: a full 256-byte circular buffer would appear
empty to the driver. The encoder reserves four bytes for a wait or loop command.
It never splits an oversized music frame with an XGM1-style frame-skip opcode.
Packed literals respect 256-byte pages; ring wrap uses each stream's own loop
command. Instrument loads and the `$9x` TL command preserve the driver's cached
TL and sustain/release state, including after an algorithm change.

PCM commands identify a compiled variant (1-123) and one of three driver channels.
Variant zero stops the channel. The sample table has 124 entries, including the
end page. Samples are signed 8-bit, padded with signed zero to 256-byte boundaries.
The driver's timer gives a nominal full rate of MCLK/4032 (about 13.3 kHz NTSC);
half-rate variants use MCLK/8064.


### Machine reset writes (SGDK's `YM2612_reset()` + `PSG_reset()`)

In this order, to the chips at machine reset and as the first commands of the VGM: port 0 `$22`=0, `$27`=0,
`$2B`=0; for port 0 and 1, channel 0..2, slot 0..3, register `$30`=0, `$40`=`$7F`, `$50`=0, `$60`=0, `$70`=0,
`$80`=`$FF`, `$90`=0 at register + 4·slot + channel; for port 0 and 1, channel 0..2: `$A0`=0, `$A4`=0, `$A8`=0,
`$AC`=0, `$B0`=0, `$B4`=`$C0` (+ channel); port 0 `$28` = 0, 4, 1, 5, 2, 6 (channel 0..2: `0|ch`, `4|ch`); PSG
for i = 0..3: `$80 | i<<5`, `$00`, `$90 | i<<5 | $0F`.

### Channels

| Channel | 0-5 | 6, 7, 8 | 9 | 10-12 |
|---|---|---|---|---|
| Name | FM 1-6 | PSG 1-3 | Noise | PCM 1-3 |
| Chip | YM2612 FM | SN76489 tone | SN76489 noise | XGM2 mixer → YM2612 DAC |
| Instruments | FM slots | PSG slots | PSG slots | PCM kits |

FM 6 is an ordinary FM voice when the DAC is off. While PCM plays, the DAC replaces
its output and uses FM 6's stereo routing. FM3 special mode gives its four operators
independent pitches; it does not add four independently sequenced tracker lanes.

### Image layout (what the Kotlin lowering produces)

`image_size` = 1188864 bytes, layout 2, little-endian. The original 65536-byte
tonal prefix and its 57536-byte pattern/envelope capacity remain intact.
`ym/ym_image.h` is authoritative; `tools/audio/ym/ym_song.py` is the reference
builder. `ym_load_image` reports a reason and offending offset on rejection.

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | `"TTYM"` |
| 4 | 1 | layout 2 |
| 5 | 1 | region: 0 NTSC, 1 PAL |
| 6 | 1 | initial speed, 1..255 ticks per row |
| 7 | 1 | initial LFO register `$22`: 0 or `$08-$0F` |
| 8 | 2 | order count, 1..256 |
| 10 | 2 | loop order, below order count |
| 12 | 1 | highest instrument slot present, 0..64 |
| 13 | 1 | zero |
| 14 | 2 | used tonal pattern/envelope bytes, at most 57536 |
| 16 | 1 | FM3 mode: 0 normal, 1 special |
| 17 | 1 | compiled PCM variants, 0..123 |
| 18 | 2 | used PCM bank pages, 0..4096 |
| 20 | 4 | used PCM pattern bytes, at most 65536 |
| 24 | 40 | zero |
| 64 | 256 | rows per order: 1..255, 0 means 256 |
| 320 | 5120 | tonal pattern offsets: 256 × 10 × u16 |
| 5440 | 2560 | instruments: 64 × 40 bytes |
| 8000 | 57536 | tonal patterns and PSG envelopes |
| 65536 | 512 | FM3 offsets: 64 × 4 × s16, diagram operator order |
| 66048 | 1536 | PCM pattern offsets: 256 × 3 × u16 |
| 67584 | 6144 | kit/key variant ids: 64 × 96 bytes; 0 unmapped |
| 73728 | 984 | 123 variant records, eight bytes each |
| 74752 | 65536 | PCM pattern data |
| 140288 | 1048576 | compiled signed 8-bit PCM bank |

Pattern offsets are relative to their own tonal or PCM data region. Unused order
entries and reserved bytes are zero. Each variant record is a u16 start page,
u16 nonzero length in pages, flags (bit 0 half rate), and three zero bytes.
FM3 offsets are −3072..3072 in 1/64 semitones and zero on non-FM slots.


**Instrument** (40 bytes; an all-zero record = no instrument; records above I must be zero). Byte 0, flags: bit 7
= defined (1), bit 0 = PSG, bit 1 = PCM; FM has neither kind bit. Other bits are zero.

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

PCM instrument (flags `$82`): the remaining 39 bytes are zero. Its 96-key table
selects compiled variants; unmapped keys do not play. The project holds at most
64 source samples sharing 256 KiB, sample rates 1..48000 Hz, kit level 0..15,
and key pitch offsets −48..24 semitones. Lowering resamples and bakes the kit level
into each mapped sound, deduplicates identical padded waveforms, and enforces
123 variants / 1 MiB. Levels are 2 dB steps below 15; zero is silent.
There are no sample loops or runtime PCM pitch/volume commands.

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
| instrument | slot 1..64, defined; FM on 0-5, PSG on 6-9, PCM on 10-12 |
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
2. Tick 0 of a row: read every channel's row at (order, row). Then, over channels 0..12 and their effect columns in
   order: `Fxx` sets the speed (the last one wins and applies to this row); `Bxx`/`Dxx` record the next position:
   `B` only → (xx, 0); `D` only → (next order, xx), the next order being order + 1 or, past the last one, the loop
   order; both → (B's order, D's row). Then for each channel 0..12: its cut is cleared; if the row has `Gxx` with xx >
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

**FM3 special mode:** apply the instrument's four signed offsets to the current
FM3 pitch after note effects. Diagram operators 1, 2, 3 and 4 use `$AD/$A9`,
`$AE/$AA`, `$AC/$A8` and `$A6/$A2` respectively. High register precedes low.
Normal mode and other FM lanes ignore the offsets. The encoder changes mode
with the driver's dedicated command rather than replacing timer register `$27`.

**PCM row:** a note selects the current kit's key mapping and triggers that variant
on the lane's driver channel. OFF and `Sxx` stop it. `Gxx` delays the trigger;
`B`, `D`, `F` and `L` retain their global meanings. There is no tonal update
for PCM: the driver advances samples and mixes them between musical ticks.

### Effects (what Kotlin's effect specs declare for `"ym2612-fm"`)

Names are one character (`docs/BACKENDS.md`, "Effect columns"); up to 4 columns per channel. "Note" = applies to
the note keyed on this row only (parameter lock); "now" = from this row on, persisting.
In this table "all" means the ten tonal channels; PCM additionally accepts only
`B`, `D`, `F`, `G`, `L` and `S`. FM = 0-5, PSG = 6-8, N = 9.
An effect outside its channel's supported set is rejected by the loader.

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

### XGM2 files (`ym/ym_xgm.c`, `ym/ym_vgm.c`)

Both file forms contain the compiled sample bank and separate FM/PSG streams,
with page-aligned lengths and a 124-entry sample-address table:

- **`.xgm`**: `"XGM2"` magic, format version `$10`, 260-byte header, unpacked
  streams, GD3 metadata. The PAL bit selects the region.
- **`.xgc`**: the same header without the four-byte magic (256 bytes), packed
  streams and the packed flag set; no metadata.

These are XGM2 formats, not XGM1 or xgmtool 1.76's XGC. Consumers must use the
XGM2 driver/toolchain. The exporter retains key ordering, operator-frequency
pairs, cached instrument state and PCM ids. Loop offsets address each stream
independently. Frame overflow is an explicit error, never a silently split frame.

For SGDK, import the **already compiled `.xgc`** without further conversion or
resource compression: `BIN music "song.xgc" 256` in the `.res` file, then
`XGM2_play(music)`. The 256-byte address alignment is required by the driver's
sample and stream address tables.

**Pinned converter limitation:** xgm2tool 1.08 at `9a78a441` does not correctly
re-import XGM2 files. Its XGM→XGC path leaves unpacked loop offsets in the packed
streams and adds `$80` to already signed PCM bytes; its XGM→VGM path throws a
`LoopCommand` cast exception even on a file generated by the same tool.
Do not run these exports through that converter or its `XGM2` resource processor.
The unpacked `.xgm` remains available for format-aware tooling; the `.xgc`
bypasses those converter defects.

### VGM export

`ym_render(..., YM_FORMAT_VGM, ...)` runs the actual machine and records its
YM2612 and PSG writes with Z80-cycle timestamps. VGM 1.71 uses the region's
YM2612/SN76489 clocks and converts elapsed master clocks to 44.1 kHz waits;
it does not replace console timing with exactly 60/50 Hz.

The output starts with the chip reset registers. Commands `$52`/`$53` carry
YM writes and `$50` PSG writes. DAC bytes go into a PCM data block and use
`$8n` output/wait commands; `$E0` restores the PCM cursor at the loop point.
Thus a looping VGM reuses the appropriate DAC bytes rather than running off
the sample block. The host entry point appends GD3 and updates EOF/tag offsets.
The exporter observes a bounded number of ticks when the song never returns
to its declared loop.

### Position, seek and live edits

- `position()`: the next frame the driver executes, not the latest encoded frame. Normally the player is one tick
  ahead. If the driver skips an interrupt to refill PCM, the queued frame remains pending and the player waits;
  musical time stretches as it does when the real driver plays an exported stream.
- `row_serial()`: the row instance stored beside each queued position. A stalled `(order, row, 0)` is not another
  row; a speed-1 row jumping to itself is.
- `start(order, row, max_ticks)`: the player first finds the target, bounded by `max_ticks`. If unreachable within
  that budget, it starts at the target order's row 0 and searches within that order (`CHIP_SEEK_FROM_ORDER`, bounded
  by 65536 ticks). An unreachable row is rejected. The machine then resets and **replays the whole driver and chips**
  until the target frame is next to execute. This preserves the Z80, PCM cursors, chip phases and register-write
  cycles; the old 60-tick register-restore shortcut cannot reconstruct XGM2's PCM buffers.
  Silent replay advances the resampler's fractional time but discards its earlier filter history: the tests compare
  Z80 registers/RAM and subsequent cycle-stamped writes exactly, and audio 1–2 seconds after the seek within
  `1e-4` RMS. Cost is linear in the replay distance, not the old fixed pre-roll cost. Hosts prepare a spare machine
  off the audio callback (Android's control thread, the Deck's main thread).
- `apply_image`: copies used image regions and relocates the player, including remembered PCM kit/sample bindings
  and the header LFO setting. A changed PCM bank waits until old frames have executed and the driver fetches its
  `main_loop` PCM-fill boundary; there are no forced mid-fill or interrupt-handler swaps. Unchanged compiled
  waveforms continue at their rebased address; changed waveforms stop. A third preallocated image retains the latest
  edit while a bank swap is pending. Queued old frames finish first, so a busy PCM refill can delay the edit. No
  allocation occurs on this path. A region change applies at the next start.

### Audition

`audition(channel 0..12, note 0..95, slot, fine −128..127)`: FM slots on channels 0–5, PSG slots on 6–9, PCM kits
on 10–12; wrong kinds and unmapped PCM keys return `CHIP_ERR_IMAGE`. PCM uses the key's compiled pitch and level;
fine pitch applies to tonal voices. The spare machine resets and boots the driver; its first full frame contains the
audition, followed by sweep/envelope updates.

`release()` returns 0 before any tick or when already silent. Otherwise FM/PSG send a key-off through the next
stream frame; PCM is a one-shot and plays out without a stop command. Ticking ends after the driver's release and
silence, or the 10-second release limit. FM silence means every carrier envelope is at `0x3FF`; PSG means envelope
off and attenuation 15; PCM means the driver has cleared its channel's STATUS bit.
Measured tonal auditions retain A-4 at 440.126 Hz (FM sine, F-number 1083) and 440.397 Hz (PSG period 254).

### Mute, mix and ops behaviour

`mute(channel 0..9)` removes only the tonal channel's output. Muted FM operators still run their feedback and
envelopes; PSG counters continue, so unmuting resumes mid-note. FM 6 mute **does not mute the DAC**.
`mute(channel 10..12)` instead queues the real driver's PCM stop and suppresses subsequent triggers on that lane;
unmuting permits future notes, not the stopped one-shot. The stop takes effect when its frame executes, including
any driver refill delay. `start` and `audition` clear mutes.

**Mix**: ymfm's YM2612 sum × 192/256 (one full-level FM channel peaks at 0.125 of full scale, six at 0.75) and the PSG
level table (2 dB steps, 4096 at attenuation 0) × 65/256, both through blip_buf, float = sample / 32768. The FM:PSG
balance is libvgm's default (VGMPlay volumes YM2612 0x200 : SN76489 0x100): a full-level PSG square has 0.35 times
the rms of a full-level FM sine.

| Op | Behaviour |
|---|---|
| `load_image` | copy, then `ym_load_image`; `image_storage` = `sizeof(YmImage)` |
| `start(order, row)` | above; `seek_mode` from start or order |
| `apply_image` | above; ignored while auditioning |
| `audition`, `release` | above |
| `mute(channel 0..12)` | tonal output suppression or a PCM stop, above |
| `position` | the next tick the driver executes |
| `tick` | one vertical interrupt: 801–802 frames at 48 kHz NTSC (965–966 PAL), ≤ 1933 at 96 kHz PAL; 0 after a released audition ended; `CHIP_ERR_FAULT` if the driver stops or makes no progress for 128 ticks |

**Kotlin** (`PlaybackEngine` for `"ym2612-fm"`): `imageSize` 1188864, `seekTicks` 12000, `note(n)` = n (absolute
0..95), `fine` in 1/64 semitones for tonal voices, `ticksPerSecond` = 59.92 (NTSC) or 49.70 (PAL). Validation mirrors
`YmPlayerImage.LOADER_PROBLEMS`, including PCM bank limits, and warns about PSG 3 under pitched noise, note locks
without notes, PSG notes below A-2, ignored FM 3 offsets in normal mode, and FM 6/DAC sharing. The XGM1 90-write
heuristic is removed: XGM2's encoded-frame bound is a different constraint, and real refill stalls are handled by
the machine rather than predicted from a register-write count.

### Exports on the hosts

`ym_export(image, YM_IMAGE_SIZE, format, max_ticks, title, author, date,
&size, &problem)` is declared in `ym/ym_export.h`; release its result with
`ym_export_free`. Invalid size, layout, format, frame overflow and allocation
failures are reported, not replaced with an empty export.

Formats are `YM_EXPORT_VGM`, `YM_EXPORT_XGM` (unpacked XGM2 with GD3) and
`YM_EXPORT_XGC` (packed XGM2 without metadata). Android registers JNI
`ChipAudio.nativeYmExport` as `YmExport.renderer`; the Deck uses cinterop
over the same entry point. The shared menu and desktop picker show each
format's `ExportFormat.label`, distinguishing XGM2 from the old driver.
WAV uses the common native playback renderer. Export ticks are capped at
ten minutes in the selected region.

### Preparing the driver, tables and fixtures

`python3 tools/audio/prepare_ym.py [--sgdk DIR]` verifies
`third_party/sgdk-xgm2/` against the pinned SGDK checkout, assembles the driver
with sjasm and compares it to SGDK's `drv_xgm2`. It writes:

- `generated/xgm2_driver_image.h` and `generated/ym_tables.h`;
- Kotlin `backend/YmPlayerImage.kt`, from the native headers;
- `app/src/test/cpp/ym_test_song.h`, including tonal, PCM, FM3, relocated,
  edited and PAL fixtures plus the independent player's expected writes.

`python3 tools/audio/ym/ym_golden.py` generates the Kotlin lowering fixtures.
`exportSamples` supplies Neon Causeway, Night Ferry (PCM + FM3), and the
shortest tonal/PCM/FM3 songs used by the WAV renderer checks.
Regenerate after changing layout, driver pin, reference player or fixtures.

### Verification

Run `tools/audio/run_native_host_test.sh DIR` for native driver, PCM, FM3,
seek, live-edit, audition, mute, stream-wrap and common WAV checks.
`tools/audio/ym_reference_check.py DIR` independently parses both file forms and
checks them against the reference player, distinguishing musical frames from
physical PCM refill delays. Optional `--xgm2tool PATH` is a converter compatibility
diagnostic: it currently fails on the pinned upstream converter's loop and sample
re-import defects above, rather than accepting its corrupted output.
Optional `--vgm2wav PATH` adds an independent VGM player.
`tools/audio/render_ym_image.sh` exercises images and native exports.

`tools/audio/build_ym_bench.sh DIR` builds host and arm64 benchmarks.
Measure paced playback as well as warm ticks, seeks, held audition and live
sample-bank edits on the reserved handhelds. Current release evidence belongs
in `docs/VERIFICATION.md`; the old XGM1 pass-replay timings do not describe XGM2.
Host/reference agreement is not a physical Mega Drive or human listening test.

### Hosts: threads and costs

Machine creation, seek and audition preparation run on control/spare machines,
not the audio callback. Playback and image application must remain allocation-free.
`ym_copy_image` copies the used pattern region and used PCM pages rather than
the full reserved bank. Performance must be measured with real PCM activity:
the old driver's silent-mixer shortcut cannot establish XGM2's cost.

## Master System: the Banjo driver on a Z80 + SN76489 + YM2413 (`"sms-banjo"`)

One engine for the four Master System systems (`sms-psg`, `gg-psg`, `sms-fm`, `sms-fm-drums`); the image's layout byte
selects the chips and channels. Sources: `sms/sms_image.{h,c}`, `sms/sms_machine.{h,c}`, `sms/sms_chip.c`,
`sms/sms_vgm.c` + `sms/sms_export.h`; Kotlin `backend/SmsImageLayout.kt`, `BanjoSong.kt`, `SmsLowering.kt`.

### Pins and preparation

- Banjo `7aaf837ebbe67e567e019b5819b5467bf62253ad` (MIT), `third_party/banjo` (driver directories banjo, sn, opll,
  opll_drums, defines, json2sms.py, README, format_spec, LICENSE), unmodified.
- WLA-DX v10.7 (`91c52b1f4ef3cc8ba3c0638f7536539579af6a9f`, GPL-2.0-or-later, build tool only) via
  `tools/audio/fetch_wladx.sh`.
- `tools/audio/prepare_sms.py` checks the vendored files against upstream, assembles
  `tools/audio/sms/thor_banjo_host.asm` (the driver with BANJO_SYS=1, a 15-channel array, first-row initialization
  and two-chip update/mute routines) and writes `app/src/main/cpp/generated/banjo_driver_image.h`
  (the 16 KiB bank, its sha256, the label addresses; struct sizes 32/34/12 checked). **The native build needs this
  generated header**; rerun after changing the pin or the host file.
- emu2413 1.6.4 (`a1d5120903b75f93d3eb7690e34a29c0b4add903`, MIT), `third_party/emu2413`, unmodified, run at
  clock/72 (49716 Hz, no rate converter).

### Machine

- Z80 map: $0000-$3FFF driver bank (an opcode fetch elsewhere, other than the return trap $FFF0, is a fault);
  $4000-$BFFF the image region; $C000-$DFFF RAM (mirrored), audition song at $D000, stack below $DFF0.
  I/O: $40-$7F SN76489, $F0/$F1 YM2413, $06 Game Gear stereo (Game Gear layout only); reads $FF.
- Start: `banjo_init(15, SN | OPLL?)`, `banjo_system_flags` = GG on the Game Gear layout (what
  `banjo_check_hardware` reports there), the region's call fields filled for the layout, `banjo_play_song($4000)`.
  The channel-init callback increments `tic` after `music_play` resets it, so row zero lasts a full row even at
  speed 1 or 255. Exported song sources include the same callback; the vendored driver remains unmodified.
- Tick = NTSC frame, 59736 Z80 cycles: `banjo_update_song` at cycle 0, the chips clocked through the frame with each
  write at its cycle (PSG every 16 cycles, YM2413 every 72), blip_buf to the host rate, 20 Hz DC high-pass, gain 2.5.
  Mix: PSG level × 3/8 + YM2413 × 3/4 (a full square and a full FM channel peak alike); listening may adjust.
- Positions from driver state: a row starts when `process_new_line` is set or `tic` is 1; row = pattern_length − line;
  an order jump or the order end predicts the next order.
- Seek: silent ticks with the driver and chips clocked throughout, preserving held voices' oscillator, envelope and
  LFO history. Past the tick budget, a linear command scan restores carried instruments and the last shared FM
  custom voice, then starts cold at the order boundary with its straight-play speeds and replays to the target.
  This fallback does not reconstruct previously held notes or their envelope/phase history. A target requiring
  more than the replay budget even from its order start is refused.
- Live edit: per channel, the pointer and line wait after the current row in the new track (a pending note delay
  continues after the same delay of the new row when it has one); instrument macro pointers re-read, positions past a
  shortened macro stop it; the FM preset nibble refreshed; the custom voice the chip holds is rewritten when edited;
  speeds follow when the straight-play speed at the playhead changed.
- Audition: a one-order song in RAM (speed 255, 64 rows, no loop) loads the instrument (FM_DRUM on rhythm channels) and
  plays the driver note. Release: NOTE_RELEASE when the volume macro has a release part, else NOTE_OFF, read on the next
  tick through the TIC_WAIT path; returns 0 for SN sounds without a release; ends after 3 silent ticks or 10 s.
- Mute: SN channel bits, YM2413 channel and rhythm masks (bass drum, snare, tom, cymbal, hi-hat).

### Image layout

Meta (0x400 bytes): "TTSB", version 1, layout (0 PSG, 1 GG, 2 FM, 3 FM rhythm), channels (4/4/13/15), orders (1-255),
loop order, pattern length (1-64, even from the lowering), data size (u16), instrument count (u16), rows per order at
0x20, speed 1 / speed 2 at each order's start (straight play) at 0x120 / 0x220. Region (0x8000 bytes) = Z80
$4000-$BFFF: Banjo's 34-byte header (call fields 0), order pointer table, order rows, 12-byte instrument records,
macros, FM patches (8 registers) and kit pitches (6 bytes), tracks. The loader validates every pointer, macro header
(volume: sustain length/loop, release length/loop; others length/loop), command size (`$00` and `$1F` refused, note
delay and speeds ≥ 1), row count per track, instrument and order references and the patch of every custom voice or
fixed kit a track loads.

### Song data (`BanjoSong`) and json2sms 7aaf837

Same structure and commands as json2sms; deliberate differences, each because the driver reads it so: arpeggio and
extra macro loop = value index + 2 (`music_update_arp_macro`/`ex_macro` read the loop position's own byte; json2sms's
+ 1 replays the previous step, or the loop byte itself for loop 0); volume macros as json2sms (+ 4). The initial
`tic` is speed + 1, established by the song's channel-init callback because `music_play` overwrites the header's
counter. Every order shorter than the pattern length and
the last order (when looping to a later order) end with an explicit ORDER_JUMP; D00 is an ORDER_JUMP to the next
order (ORDER_NEXT would wrap to order 0, not the loop order); OFF is NOTE_RELEASE for instruments with a volume release
part. Driver limits respected by the validator: one pitch effect per channel, note delay/cut within the row, one
custom FM voice, rhythm drums without pitch effects or macros, FM voices without the extra macro (inverted type test in
`opll/volume_change.inc`).

### VGM export

VGM 1.51 with GD3: the driver's own writes from a silent run (SN76489 0x50, YM2413 0x51, Game Gear 0x4F), 44.1 kHz
waits from Z80 cycles, the driver's initialisation at time 0, the loop at the first arrival at (loop order, row 0) and
the end at the second (or 10 minutes, no loop). SN feedback 0x0009, width 16, flags 0.

### Verification

`tools/audio/run_native_host_test.sh` runs `app/src/test/cpp/sms_machine_test.c` (images built by hand in C from this
contract); `tools/audio/render_sms_image.sh` renders an image (SampleExportTool writes the demos'). JVM:
`SmsLoweringTest`, `SmsModelTest`, `SmsEditorTest`.

## EPSM: the same engine with a YMF288 (`"nes-famistudio-epsm"`)

**Status: verified for 0.21.0.** `prepare_nes_player.py` generated the player: 5048 bytes at
`$8000`, song base `$9400`, SHA-256 `46215fde7f7ed01b36167e955ec1436fc48a9e8193bea28e6656f8501dbec758`.
All 32 previous NES generated outputs stayed byte-identical. Runtime verification is recorded separately in
`docs/VERIFICATION.md`. Everything in the NES section holds; this section lists what differs.

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
  region (`NES_EPSM_IMAGE_SIZE`). `NES_EXPANSION_EPSM` = `$80`, matching the NSF expansion flag.
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
  | 6 | 2 | **mixer** envelope (kind 5) |
  | 8 | 2 | **noise** envelope (kind 6) |
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
- **Directory kinds 5 (mixer) and 6 (noise)** are shared by the 5B and EPSM builds. Neither has a header byte
  (instructions from index 0, like arpeggio and duty). Mixer values are FamiStudio's processed bits `$C0` (tone +
  noise), `$C1` (noise), `$C8` (tone), `$C9` (neither); noise values 0..31 encode as `$C0-$DF`.
  Thor Tracker's directory indices: slot × 4 + kind for volume, arpeggio, duty and pitch (unchanged), mixer
  256 + 2 × slot, noise 256 + 2 × slot + 1, effect envelopes (`0xy`, vibratos) from **384** (`effect_envelopes`).
- **Opcodes**: `$5C` (FM 1-6 only; ends the row) = FM release: key off, so the note fades at the patch's release
  rates (an OFF on a sounding FM note; a second OFF is the usual stop). `$5D lo hi` (SSG 1-3 only) = the shared manual
  envelope period for the frame (`Ixx`/`Jyy`; FamiStudio's exporter moves them to SSG 1, as Thor Tracker's lowering
  does). `$4F` (duty) is invalid on every EPSM channel; the rhythm channels also reject release, slides, pitch and
  vibrato commands. The scoped `$5F o` SSG load selects slot `o & $3F`; bit 7 preserves the shared manual period,
  and bit 6 must be zero. It uses attack-style envelope addressing while retaining the row's no-attack flag and
  envelope positions, correcting upstream's legato mixer/noise pointer writes into volume/arpeggio slots.
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
  A scoped loader correction writes the FM patch's LFO byte to global `$22`, never `$22` plus the channel offset.
  Another correction exits the SSG attack path after setting its envelope-trigger bit: upstream's inverted branch
  fell through into the FM trigger array and overwrote the hi-hat, tom or rim-shot pan. The vendored source stays
  unchanged; the temporary patched source is pinned to SHA-256
  `42739f0b0ada0f363d28ae88c9f47e2e27ac7deb49f1838c3f412527a77d1c66`.
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
  Band-limited output buffers budget the full raw FM/rhythm/SSG range, including filter overshoot, before their
  int16 stage. The machine restores the relative scale when combining the EPSM and 2A03.
- **Stereo and mix level**: each native sample's left and right levels go as band-limited steps into two extra
  blip_buf buffers at the sample's CPU cycle; the machine adds its centred 2A03 to both and runs one output stage per
  side. **This build's output is true stereo** (FM `$B4` and rhythm pans); the other NES builds stay mono. The levels
  are **FamiStudio 4.5.3's own EPSM-to-2A03 balance** (`Nes_EPSM.cpp`, `ym3438.cpp`, `Simple_Apu.cpp`) in the 2A03
  mixer's scale: one FamiStudio unit = 10.59857 / 163430 (its EPSM Blip_Synth volume over its range); FM = 18 / 16
  units per ymfm OPNA unit (FamiStudio sums 6 × the YM3438-mode 9-bit output on 3 of 4 cycles; ymfm's sum is that
  >> 1); rhythm = 1.1 units per ymfm ADPCM-A unit (4 of 24 cycles × value >> 1 × 11/20); SSG = (4080 × 10 / 18) /
  (16382 × 2 / 3) units per ymfm mix unit (emu2149's YM2149 table top over ymfm's, FamiStudio's × 10 / 18).
  The raw side spans about 27.89 full 2A03 mixer units. Both buffer and final-output budgets include a 1.12
  filter-overshoot allowance; the calculated final bound is below 0.979. This deliberately preserves ymfm's
  unclipped linear sums and the relative balance, at the cost of about **35.6 dB less master gain than plain NES**.
  The demo is consequently quiet (native test RMS 0.0064, peak 0.0400); this is not automatic loudness normalization.
  **This is not measured hardware loudness or verified DAC-overflow behavior.** Yamaha's
  [YMF288 application manual](https://www.quarter-dev.info/archives/yamaha/YMF288.pdf) specifies a 16-bit serial
  output and an SSG-to-FM ratio, but does not specify the sum's overflow rule. We do not invent clipping or wrapping.
- **Seek**: the YMF288 is clocked sample by sample through the silent frames (output detached), so FM envelopes,
  phases, feedback and the LFO, the SSG's tone/noise/envelope counters and the rhythm drums' ADPCM positions are those
  of straight play — no register-snapshot restore. A machine reset restores a snapshot of a freshly constructed chip
  (ymfm's save/restore state, taken once at creation; no allocation).
- **Rhythm ROM** (`ym2608_rhythm_rom.h`): the drums play the YM2608 rhythm ROM (8192 bytes: bass drum, snare, top cymbal,
  hi-hat, tom, rim shot at ymfm's fixed addresses), which Thor Tracker does not distribute. `int
  ym2608_rhythm_rom_install(const uint8_t* rom, size_t size)` copies it once into static storage and publishes it
  with an atomic release store for EPSM and OPNA: 0 ok (the identical bytes again are a no-op); −1 for NULL, a
  size other than 8192, or other bytes than an installed ROM (installed storage is never rewritten while audio
  threads may read it). `ym2608_rhythm_rom_installed()` is 0/1. The caller checks the ROM's identity first
  (`Ym2608RhythmRom.problem()`: size, then SHA-256 `53afd0fa…eba8`). Hosts: Android
  `ChipAudio.nativeInstallRhythmRom(rom: ByteArray): Int` (JNI, `audio_jni.cpp`), called by `MainActivity` at start
  for a stored `filesDir/firmware/ym2608_adpcm_rom.bin` that still verifies and after an import; the Deck calls the
  same C function through `decknative.def` for `~/.local/share/thortracker/firmware/ym2608_adpcm_rom.bin`. Without
  a ROM a rhythm key-on makes no sound (that drum stays out of the mix until a key-on with the ROM) and sets
  `nes_epsm_chip_rhythm_missing` (`nm_epsm_rhythm_missing`): a diagnostic only — Kotlin's `lower()` refuses a song
  whose played orders contain a rhythm note, and audition refuses a drum, with `Ym2608RhythmRom.MISSING` ("… import
  your copy once with "YM2608 rhythm ROM…" …"), so nothing ever substitutes another sound.
- **Live edits**: SSG: the machine sees on the bus which expansion entry each SSG loaded last (the engine's store of
  the shape byte right after reading entry + 10) and which entry the manual period came from (the store after reading
  entry + 12; a `$5D` makes it none);
  `apply_image` rewrites each SSG's shape nibble (keeping bit 7, a pending envelope restart) and octave from its
  entry, and the shared manual period from the entry it came from; an SSG whose entry turned manual after the
  period's last store sets it, as the engine's own load would. FM: a channel whose loaded instrument's 31-byte patch
  differs in the new image gets that instrument stored into `famistudio_chn_epsm_fm_instrument` again, so the
  engine's next update reloads the patch, algorithm, pan and operator levels exactly as its own load does. Rhythm:
  each drum's instrument is identified from the loader's actual `ptr1 + r3` entry, not an arbitrary program-memory
  read window; its level and pan are re-read from that slot. An identical image changes nothing.
  An unchanged FM sound is not reloaded solely at an order boundary: that would briefly restore its raw carrier
  levels before the row's volume is applied. The `epsm_effects` reference trace catches this at frame 504.
- **Audition**: on every EPSM channel. A held note `$54 i [$4E f] $40 n` (fine pitch on SSG and FM); release is the
  generic `$45` on SSG (only with a volume release point, else a cut), **`$5C` on FM** (always: the patch's release,
  ending when the volume tail is done and the carriers are quiet); a drum is a one-shot of the ROM and ends at its
  end address.
- **Mute** 5-19 leaves a channel out of the chip's sums (it keeps running; unmuting resumes mid-note).
  `nm_write_log` gives the `$401C-$401F` writes with the APU's.
- **Validation** (`nes_image.c`, EPSM build): twenty stream words, the 7-byte head with the `instruments_exp`
  pointer, the expansion entries and patch blocks above, mixer values `$C0/$C1/$C8/$C9`, noise 0..31, directory kinds
  0..3 and 5/6, effect envelopes from 384, `$5C` only on FM 1-6, `$5D lo hi` only on SSG 1-3, `$5F o` only on
  SSG with a valid slot/flag operand, and the rhythm-channel restrictions above. Other builds refuse EPSM images
  and the reverse.
- **Ops** (`nes_chip.c`): `nes_epsm_chip_ops`, id `"nes-famistudio-epsm"`, `image_size` `NES_EPSM_IMAGE_SIZE`, the
  same functions as `"nes-famistudio"` over a machine of the EPSM build. `.nsf` export: the EPSM player, header byte
  `$7B` = `$80` (FamiStudio `NsfFile.cs`: EPSM), so NSF players emulate the chip (and use their own rhythm ROM copy).
  FamiStudio text: `Expansions="EPSM"`, see `docs/BACKENDS.md` (a manual SSG envelope period other than 4000 cannot be
  written there; the text export refuses it).

### Preparing the player

`python3 tools/audio/prepare_nes_player.py` builds the EPSM player with the others and writes
`nes_epsm_player_image.h` (`NES_EPSM_*` and `NES_EPSM_FAMISTUDIO_*` macros), `NesEpsmPlayerImage.kt` (`CHANNELS` 20,
`EXP_INSTRUMENT_SIZE` 16, `PATCH_SIZE` 31, `KIND_MIXER` 5, `KIND_NOISE` 6, `EFFECT_ENVELOPES` 384) and
`app/src/test/cpp/nes_epsm_song.h` from `tools/audio/nes/epsm_test.txt` as exported by FamiStudio 4.5.3
(`epsm_test_ca65.s`; `--famistudio "$(tools/audio/fetch_famistudio.sh)"` writes it when missing). The test text
covers the 2A03 beside SSG (mixer tone/noise/both/none and noise envelopes, hardware-envelope shapes with auto
periods at octaves 3/1/−1/−3 and a manual period, `EnvelopePeriod`, slides, vibrato, arpeggio, volume slide, delay,
cut, release), FM (the Default preset and custom algorithms 0/2/4/5/7, feedback, left/right/both pans, AMS/PMS with
the LFO, SSG-EG, releases, slides, vibrato, fine pitch, stop, the range ends C-1/B-7) and all six rhythm drums
(left/right/both, `Cxx`, delay, cut).

### Verification

- `tools/audio/run_native_host_test.sh [dir]` builds and runs `app/src/test/cpp/nes_epsm_test.c`. Evidence:
  `nes_epsm.wav` (30 s, all channels; the drums silent without the ROM), `nes_epsm_tonal.wav` (drums muted),
  `nes_epsm_trace.txt` (per frame `$4000-$4017` and `$401C-$401F` writes), `nes_epsm.nsf` (`$7B` = `$80`), and, only
  with a private ROM (below), `nes_epsm_rhythm.wav` (only the drums).
- **Private rhythm checks**: `THOR_RHYTHM_ROM=/path/to/ym2608_adpcm_rom.bin` (the user's own copy; size and
  SHA-256 checked, anything else refused) enables the rhythm checks of `nes_epsm_test` (which then also writes
  `nes_epsm_rhythm.wav`) and the drums of `tools/audio/nes_render.c` (also its optional fourth argument:
  `tools/audio/render_nes_image.sh image.nesimg out.wav [seconds] [rhythm-rom]`). `tools/audio/nes_reference_check.py`
  never reads the ROM: it compares `nes_epsm_rhythm.wav`, when present, with FamiStudio's wav-export (FamiStudio's
  emulator has its own built-in copy). Unset, the test prints "rhythm ROM checks skipped (THOR_RHYTHM_ROM
  unset)". Nothing copies, stores or commits the ROM; keep it and the rhythm evidence outside the repository.
- `tools/audio/nes_reference_check.py <evidence-dir>`: py65 runs our `nes_epsm.nsf` and FamiStudio's own NSF export of
  `epsm_test.txt`; both must write our trace's registers (values differing only in pitch registers on slide frames,
  where FamiStudio's asm and NSF exports compute different slide steps); our SSG + FM + 2A03 audio is compared with
  FamiStudio's wav-export of channels 0-13 (libgme has no EPSM).
- `wav_render_test.c` renders `expansion-port.nesimg` (the demo, no rhythm) and `epsm-shortest.nesimg` (one row at
  speed 1, A-4 held on FM 1), checking exact loop boundaries, PCM equality against straight engine ticking and
  chunk-size independence. Demo master volume is not a format invariant.
- Kotlin: `NesGoldenTest` runs the golden songs `epsm_demo`, `epsm_effects` and `epsm_rhythm` (FamiStudio's export of
  our text vs our lowering, register traces with the EPSM writes compared every frame; generate them as in
  `docs/VERIFICATION.md`); `NesLoweringTest`, `TruePitchTest` and `NesInstrumentViewTest` cover the image contract,
  pitch and editor.
- Completed: 515 JVM core, 13 Linux core and six Android-host unit tests; 332 native EPSM checks with a private
  rhythm ROM; 999 WAV checks after removing the incidental per-demo loudness threshold. Other engine checks passed.
  The production APK passed 19 permanent Android tests and 32 unchanged Game Boy renders; actual Android/desktop
  exports match and Nova/DMG/Konkr playback/audition checks passed. See `docs/VERIFICATION.md` for evidence and limits.
- Not done: human listening; a hardware EPSM; a comparison with Mesen2's or FamiStudio's audio; Thor measurements.

## AY-3-8910 / YM2149: Thor Tracker's own player for the ZX Spectrum 128, MSX, Atari ST and Amstrad CPC

Roadmap step 7 ("More chips"), source only (branch `ay-systems`, unbuilt: the integrator runs every generation step,
build and test listed under "Preparing" and "Verification"). Four system variants, one per stock computer, play
songs lowered into the image layout below through **our own player** (`ay/ay_player.c`, pure integer C without a
chip, mirrored by the independent Python reference `tools/audio/ay/ay_ref.py`) on **our own AY-3-8910 / YM2149 core**
(`ay/ay_psg.c`), and export VGM. No computer is emulated (no Z80 or 68000): none of these machines has one standard
music driver, and songs leave as VGM register logs, never as fake native executables. This section is the contract
of the native engine, the Python reference and the Kotlin system (`core` `system/ay`, `backend/AyLowering.kt`); the
numbers they share are generated into `backend/AyPlayerImage.kt` ("Preparing").

### Variants: chips, clocks, ticks and wiring (primary sources)

Each variant is the stock computer: its chip, its clock, its frame interrupt as the player's tick, and its stock
output. Times are kept as exact integer ratios of the machine's master clock *M*: the chip clock is *M* / `ay_div`,
one tick is `frame_div` master clocks, one chip **step** (the generators' clock, chip clock / 8) is 8 · `ay_div`
master clocks.

| Image byte 5 | System id | Name | Chip | *M* (Hz) | `ay_div` | Chip clock | `frame_div` | Tick | R7 bits 7-6 | VGM `0x78` type / `0x74` clock |
|---|---|---|---|---|---|---|---|---|---|---|
| 0 | `ay-zx128` | ZX Spectrum 128 (AY-3-8912) | GI AY-3-8912 (the 28-pin AY-3-8910: same sound, one I/O port) | 3546900 (Z80) | 2 | 1773450 Hz | 70908 (T-states per frame) | 50.0212 Hz | `11` | `0x01` / 1773450 |
| 1 | `ay-msx` | MSX (AY-3-8910) | GI AY-3-8910 | 10738635 (VDP crystal; Z80 = *M*/3) | 6 | 1789772.5 Hz | 179208 (2 · 342 · 262: one NTSC TMS9918A frame) | 59.9227 Hz | `10` | `0x00` / 1789772 |
| 2 | `ay-atari-st` | Atari ST (YM2149F) | Yamaha YM2149F | 32084988 (PAL ST master) | 16 | 2005311.75 Hz | 641024 (4 · 160256: 512 cycles · 313 lines of the 8 MHz CPU) | 50.0527 Hz | `11` | `0x10` / 2005312 |
| 3 | `ay-cpc` | Amstrad CPC (AY-3-8912) | GI AY-3-8912 | 16000000 (Gate Array crystal; Z80 = *M*/4) | 16 | 1000000 Hz | 319488 (16 · 19968 µs: 312 lines of 64 µs, the CRTC's default frame) | 50.0801 Hz | `00` | `0x01` / 1000000 |

Sources: World of Spectrum's 128K reference ("The main processor runs at 3.54690 MHz", "70908 T states per frame",
the AY clocked at half the CPU clock) and the 128 ROM 0 disassembly (Matthew Wilson: the ROM writes register 7 as
`$FF`/`$F8`, i.e. both I/O direction bits set); the MSX2 Technical Handbook chapter 5a ("in MSX, it is defined that
fc = 1.7897725 MHz"; "it should always be set so that bit 6 = 0 and bit 7 = 1"); Hatari `src/clocks_timings.c`
(`ATARI_STF_PAL_MCLK 32084988`, CPU = MCLK/4, YM = CPU/4 "2 MHz", `ATARI_STF_CYCLES_PER_VBL_PAL 160256`). The ST's
I/O bits (both ports outputs: port A drives drive select, RS-232 handshake and the printer strobe, port B the printer
data) follow the ST's use of the ports [INFERENCE: not quoted from a primary source]; they never affect sound. The
VGM clock field is an integer: the MSX's 1789772.5 Hz is written as 1789772 (the value MSX VGM rips use), the ST's
as 2005312. MSX machines with the later MSX-ENGINE (a YM2149 inside) are not a separate variant: the MSX standard
names the AY-3-8910. The MSX tick is the NTSC (Japanese) 60 Hz frame; PAL MSX machines are not modelled. Amstrad CPC:
MAME's `amstrad.cpp` (`AY8912(config, m_ay, 16_MHz_XTAL / 16)`), the CPC's Gate Array dividing its 16 MHz clock to
4 MHz for the Z80 and 1 MHz for the CRTC and PSG, the CRTC's default 64 µs lines × 312 lines = 19968 µs frame (50.08
Hz, the frame-flyback rate CPC music players are synchronised to); register 7 bit 6 = 0 (port A reads the keyboard
through the PPI; bit 7 has no port on the 8912) as CPCWiki's PSG page states.

**Wiring**: the ZX Spectrum 128, the MSX and the Atari ST mix the three channels into **one mono output** (the 128's
TV/sound socket, the MSX's sound output, the ST's monitor/TV audio): their engines output the mono mix on both host
channels, with no per-channel panning. The **Amstrad CPC** has a stock **3.5 mm stereo socket** ("3.5mm socket for
stereo sound output", CPC464 service manual, 1985; the socket's pinout: channel A left, B centre, C right): its
engine outputs that socket, **left = A + B, right = B + C** (B through equal resistors to both sides [INFERENCE: the
equal weight is not quoted from the schematic]); its internal speaker (mono) is not modelled. The VGM format has no
field for a machine's channel wiring, so every variant's VGM declares the chip's default single mixed output (players
choose their own panning). The channels are summed linearly; the real machines tie the outputs together through
resistors, which compresses loud chords slightly (MAME models it as a resistor network) — not modelled (see
`docs/BACKENDS.md`, "Open questions").

### Chip core (`ay/ay_psg.c`)

Written for Thor Tracker from the GI AY-3-8910/8912 data manual, the Yamaha YM2149 manual and the MSX2 Technical
Handbook; MAME's `ay8910.cpp` (BSD-3-Clause, Couriersud) and ayumi (MIT, Peter Sovietov) were read for behaviour the
manuals leave implicit; the per-level DAC curves are **ayumi's measured tables** (`AY_dac_table`: 16 levels of the
AY-3-8910, `YM_dac_table`: 32 levels of the YM2149; ayumi commit `07c08b4874c359169e4a028edf73f046d8b763e2`,
`ayumi.c` sha256 `8824bc34…f19401d`), copied as data with ayumi's MIT notice. ayumi itself is vendored unmodified in
`third_party/ayumi/` (`ayumi.c`, `ayumi.h`, `LICENSE`) for the host test's independent comparison only; it is not
linked into the app.

Registers R0-R13 are the sound registers (R14/R15 are I/O ports: not modelled, writes ignored):
R0/R1, R2/R3, R4/R5 tone periods A-C (12 bits, fine + coarse; unused bits ignored), R6 noise period (5 bits), R7
mixer (bits 0-2 tone **disable** A-C, bits 3-5 noise disable A-C, bits 6-7 I/O direction), R8-R10 amplitude A-C
(bit 4 M: the envelope's level, else the 4-bit level), R11/R12 envelope period (16 bits), R13 envelope shape
(bits 0-3: hold, alternate, attack, continue).

Per step (chip clock / 8; MAME's physical 16-level AY / 32-level YM envelope model):

- **Tone** (per channel): counter + 1; when counter ≥ period (0 acts as 1) the output flips and the counter becomes
  0. So f = clock / (16 · TP), and a period shortened below the counter flips on the next step.
- **Noise**: counter + 1; when counter ≥ 2 · period (0 acts as 1) the counter becomes 0 and the 17-bit LFSR steps:
  new bit 16 = bit 0 ⊕ bit 3, shift right (seed 1 at reset). Noise output = bit 0. f(noise clock) = clock / (16 · NP).
- **Envelope**: when not holding, counter + 1; when counter ≥ max(1, period · `env_div`) the counter
  becomes 0 and the envelope takes one step. **AY-3-8910/8912**: 16 levels, `env_div` 2; **YM2149**: 32 levels,
  `env_div` 1 — for nonzero periods, the same cycle (256 · EP clocks per ramp), twice the resolution on the YM.
  **EP = 0** advances one level per clock/8: a ramp takes 128 chip clocks on AY (twice EP=1's rate), 256 on YM
  (the same rate as EP=1), following [MAME's envelope counter](https://github.com/mamedev/mame/blob/master/src/devices/sound/ay8910.cpp).
  The instrument, effect and shared-envelope readings use this rate too. Shapes (as MAME's
  `set_shape` and the manuals' table): attack = bit 2; continue = 0 behaves as hold = 1 with alternate = attack (so
  0-3 = 9, 4-7 = 15); a ramp runs from the top (level max, falling) or bottom (0, rising) according to attack; at a
  ramp's end: hold → (alternate flips the direction once) and hold the last level of that direction; otherwise
  alternate flips the direction and continues, else the ramp restarts. **A write to R13 (any value, also an
  unchanged one) restarts the envelope**: counter 0, level at the ramp's start, holding cleared.
- **Channel output**: (tone output | tone disable) & (noise output | noise disable) — a channel with both disabled
  outputs a constant level. Level: M set → the envelope level; else the fixed volume V: AY level V (16 steps), YM
  level 2V + 1 (V = 0 → 0). DAC: AY `AY_DAC[level]` (0..15), YM `YM_DAC[level]` (0..31), both 0..1 (level max = 1.0).
- **Mix**: the sum of the channels' DAC values × `AY_AMP` (10922 per channel at 1.0: three full channels reach
  32766) — mono (all three) on the ZX, MSX and ST; on the CPC two sums, left (A, B) and right (B, C) — whose changes are
  sent to blip_buf 1.1.0 buffers (one mono, or left and right on the CPC) as band-limited deltas at their step (clock
  rate = the step rate *M* / (8 · `ay_div`)), with blip_buf's high-pass (bass shift 9, ~15 Hz) removing the DC the
  unipolar outputs carry; float = sample / 32768 (mono: written to both host channels).
- **Mute** (bit per channel): a muted channel is left out of the sum; every generator keeps running.
- **Silent advance** (seeking, `out` = NULL): every counter, flip, the LFSR, the envelope step and hold state advance
  **arithmetically to exactly the state stepping would reach** (tone: the first flip after max(1, TP − counter)
  steps, then every TP; noise: the same on 2 · NP, the LFSR stepped once per event; envelope: steps counted the same
  way, periodic shapes reduced modulo their cycle, held shapes saturated). The host test compares the two paths'
  states after every tick of a song.

Register writes take effect at the step boundary they are applied at (the player's writes at a tick's start, in
order).

### Machine (`ay/ay_machine.c`)

`AyMachine`: the image copy, the player, the chip, one blip buffer (mono; two on the CPC), the tick remainder. A tick:
the player runs once; its writes (in order) go to the chip at the tick's first step; the chip runs `steps` steps,
`steps` from exact accumulation (`acc += frame_div; steps = acc / (8 · ay_div); acc %= 8 · ay_div`: ZX 4431/4432,
MSX 3733/3734, ST 5008, CPC 2496); blip_end_frame(steps); all available samples are read (at most 2048 frames at 96
kHz) and written as stereo frames (L = R except on the CPC). No allocation outside create; no key-off gap (the AY
has no key bits).

### Channels

| Channel | 0 | 1 | 2 |
|---|---|---|---|
| Name | Channel A | Channel B | Channel C |
| Chip | tone A, R0/R1, R8, R7 bits 0/3 | tone B, R2/R3, R9, bits 1/4 | tone C, R4/R5, R10, bits 2/5 |

Every channel plays every instrument; the noise generator (R6) and the envelope generator (R11-R13) are **shared**
(below, "Shared noise and envelope").

### Image layout (what the Kotlin lowering produces)

`image_size` = 65536 bytes, little-endian, every pattern and envelope reference an offset into the data region (any
layout that passes `ay_load_image` relocates). Constants: `ay/ay_image.h` (`AY_*`); the reference builder is
`tools/audio/ay/ay_song.py` (`build()`). The loader rejects anything off-contract with a reason and the byte
(`AyInfo.problem`, `problem_offset`): the Kotlin validator mirrors every reason with user-facing messages.

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | `"TTAY"` |
| 4 | 1 | layout: 1 |
| 5 | 1 | variant: 0 ZX Spectrum 128, 1 MSX, 2 Atari ST, 3 Amstrad CPC (each engine accepts only its own) |
| 6 | 1 | speed (ticks per row) at the song start, 1..255 |
| 7 | 1 | 0 |
| 8 | 2 | order count, 1..256 |
| 10 | 2 | loop order (< count) |
| 12 | 1 | instrument count I = highest slot present, 0..64 |
| 13 | 1 | 0 |
| 14 | 2 | data size D: bytes of pattern and envelope data used, ≤ 61440 |
| 16 | 48 | 0 |
| 64 | 256 | rows of order *o* at 64 + *o*: 1..255, 0 = 256 (Thor Tracker: ≤ 64); entries ≥ count 0 |
| 320 | 1536 | pattern of (order *o*, channel *c*) = u16 offset into the data region at 320 + 2 (3 *o* + *c*); entries ≥ count 0 |
| 1856 | 192 | 0 |
| 2048 | 2048 | instrument slot *n* (1..64) at 2048 + 32 (*n* − 1) |
| 4096 | 61440 | data region: patterns and envelopes, `[0, D)` |

**Instrument** (32 bytes; an all-zero record = no instrument; records above I must be zero):

| Byte | Field |
|---|---|
| 0 | flags: bit 7 = defined (1); bit 0 = the pitch envelope is relative (accumulates every tick); bit 1 = **auto envelope period** (follows the note) else manual; bit 2 = **tone synced to the envelope** (auto only); bits 3-6 zero |
| 1 | hardware envelope shape: 0 = off (fixed volume), 8..15 = the shape written to R13 |
| 2 | auto octave offset, s8 −8..7 (auto only; else 0) |
| 3 | 0 |
| 4 | u16 manual envelope period 0..65535 (manual only; else 0) |
| 6 | u16 data offset of the volume envelope (0..15), `$FFFF` = none (15) |
| 8 | u16 arpeggio envelope (−64..63 semitones), `$FFFF` = none (0) |
| 10 | u16 pitch envelope (−64..63 × 4 pitch units = 1/16 semitone), `$FFFF` = none (0) |
| 12 | u16 mixer envelope (0..3: bit 0 tone, bit 1 noise), `$FFFF` = none (1: tone only) |
| 14 | u16 noise envelope (noise period 0..31), `$FFFF` = none (the note does not set R6) |
| 16 | drum sweep start, s8 semitones relative to the note, −96..96 |
| 17 | drum sweep end, s8 semitones, −96..96 |
| 18 | drum sweep speed, 1/16 semitone per tick toward the end; 0 = no sweep (start/end ignored) |
| 19 | 13 bytes 0 |

**Envelope** (at its offset, wholly inside `[0, D)`), as the Mega Drive's PSG envelopes: u16 length L 1..256, u16
loop index (< L, `$FFFF` = none), u16 release index (`$FFFF` = none; volume only, and only with a loop: loop <
release < L), then L signed bytes. The reference builder places envelopes after the patterns, slots 1..64 ascending
(volume, arpeggio, pitch, mixer, noise), each deduplicated by its bytes (header included) against the envelopes
before it; any placement that passes the loader plays.

**Patterns**: exactly the OPL3's and Mega Drive's encoding (`0x80 | k` = k + 1 empty rows; mask `m` 0x01..0x13: bit
0 note, bit 1 instrument, bits 2-4 effect count 0..4, bits 5-7 zero, m ≠ 0; note 0..95 or `0xFE` OFF; instrument 1..64
defined; effects (code 0..35, parameter) per column in order), each (order, channel) decoded for exactly rows(*o*)
rows, every byte read inside `[0, D)`.

**What the lowering writes** from a `Cell` with up to 4 effect columns: as the Mega Drive (note `EMPTY` → no byte,
`CUT` → `0xFE`; instrument 0 → no byte; blank columns omitted; empty rows packed into runs; byte-identical patterns
may be shared). Load-time effect checks: the effect exists (table below), `Bxx` < order count, `Cxx` ≤ 0F, `Exx` = 00
or 08..0F, `F00` rejected, `Jxx` ∈ 00..07 ∪ F8..FF, `Vxx` ≤ 03, `Wxx` ≤ 1F. Envelope checks: as above, values in
their kind's range, release only on volume.

**Should** (not checked; validator warnings): an instrument on the first note of every order; shared noise and
envelope conflicts ("Shared noise and envelope"); notes below the variant's lowest reachable note (the period
clamps at 4095).

`ay_load_image` derives (`AyInfo`) variant, order count, loop, speed, rows per order and the speed each order starts
with in sequential straight play (header speed, then the last `Fxx` of each order: rows, then channels, then
columns; jumps ignored).

### Pitch

Units: **pitch** in 1/64 semitone, 0 = C-0, at most 6143 (just below C-8); note *n* = pitch 64 *n*. With pitch =
768 · *o* + *f* (*o* = octave 0..7, *f* 0..767) and the variant's table `AY_DIV[v][f]` = floor(256 · clock / (16 · C0
· 2^(f/768)) + 0.5), C0 = 440 · 2^(−57/12) Hz, clock = *M* / `ay_div` exactly:

- **Tone period** TP(pitch) = clamp((`AY_DIV[f]` + (128 << *o*)) >> (8 + *o*), 1, 4095).
- **Auto envelope period** EP(pitch, J, shape) = clamp((`AY_DIV[f]` + (1 << (*s* − 1))) >> *s*, 1, 65535), *s* = 8 +
  *o* + 4 + *t* + J, *t* = 1 for the triangle shapes 10 and 14 (two ramps per cycle) else 0, J = the octave offset
  −8..7: the envelope's audible cycle sounds the note J octaves up (saw shapes 8 and 12: 256 · EP clocks per cycle;
  triangles 512 · EP), i.e. EP ≈ TP / 2^(4 + *t* + J).
- **Synced tone** (instrument flag bit 2, auto mode, while the note uses the hardware envelope): TP = EP · 2^*k*,
  *k* = 4 + *t* + J (*k* < 0, i.e. J ≤ −5 − *t*: (EP + (1 << (−*k* − 1))) >> −*k*), clamped 1..4095: tone and
  envelope share one exact period ratio (no beating; the tone stays at the note, its pitch now the envelope period's,
  coarser up high).

A-4 = 57: ZX 1773450 / (16 · 252) = 439.84 Hz, MSX 1789772.5 / (16 · 254) = 440.40 Hz, ST 2005311.75 / (16 · 285) =
439.76 Hz, CPC 1000000 / (16 · 142) = 440.14 Hz. Lowest notes with TP ≤ 4095 (the lowest reachable, the system's note
range from it to B-7): ZX and MSX A-0, ST B-0, CPC C-0 (its 1 MHz clock reaches lower; its top octave is coarser:
B-7 is TP 16). `generated/ay_tables.h` holds the four tables and the vibrato sine `AY_SINE[i] = floor(127
sin(2πi/64) + 0.5)` (the OPL3's).

### Player

**Writes** are `(register, value)` pairs for R0-R13. Every write except R13 goes through a shadow of R0-R12 (reset
state: all 0): a write equal to the shadow is not emitted. R13 is written only when the player restarts the envelope
(below) and is always emitted then (the chip restarts on any R13 write).

**Reset** (start, audition): all player state cleared; channels: instrument 0, volume 15 (stored × 8 = 120), no
effects, phase off, never keyed; stamp counter 0; envelope owner none; speed = header speed; position (0, 0, tick
0). Writes: R7 = `io` | `0x3F` (`io` = the variant's bits 7-6: ZX/ST `0xC0`, MSX `0x80`, CPC `0x00`), so every tone
and noise is disabled before the first note.

**Tick** (positions are (order, row, tick-in-row); the host's position is the tick about to run):

1. Tick 0 of a row: read every channel's row at (order, row). Then, over channels 0..2 and their effect columns in
   order: `Fxx` sets the speed (the last one wins and applies to this row); `Bxx`/`Dxx` record the next position as
   the OPL3 (`B` only → (xx, 0); `D` only → (next order, xx); both → (B's order, D's row)). Then for each channel
   0..2: its cut is cleared; if the row has `Gxx` with xx > 0 (the last `G` of the row) its row is delayed to tick xx;
   otherwise the row runs now. Other ticks: each channel whose delayed row is due (tick = xx) runs it, channels in
   order.
2. Each channel whose cut tick (`Sxx`) equals this tick is keyed off, channels in order.
3. Each channel 0..2 that has keyed any note since reset is **evaluated** (below), channels in order; then the
   shared resources are resolved and the tick's writes emitted in this order: per channel 0..2 R(2c) then R(2c + 1)
   (tone period low, high), R6, R7, R8, R9, R10, R11, R12, R13 (if restarted).
4. Each evaluated channel's effects and envelopes advance (below).
5. tick + 1; when it reaches the speed: tick 0 and the next row (the OPL3's rule: the recorded jump with the row
   clamped to the target order's last row, else row + 1, after an order's last row the next order, after the last
   order the loop order), with the **loop speed** rule (row 0 of the loop order starts at `AyInfo.start_speed` of that
   order; an `Fxx` on that row still applies).

**Running a row** of a channel: if the row has a note 0..95, the arpeggio and fine pitch are cleared; an instrument
byte sets the channel's instrument; the effect columns run in order (table below; the **note settings** `E`, `H`,
`I`, `J`, `V`, `W` are collected in column order into the row's settings); then the note: OFF keys off; a note while
portamento is active (`3xx`, xx > 0) and the channel's phase is on only sets the glide target; any other note keys
on. After that, if the row did not key on, the row's collected settings are applied to the channel's **current**
note settings at once (on a row with a key-on they were applied by the key-on).

**Key-on** (note *n*): if the channel's instrument is not a defined slot nothing else happens. Else: the five
envelopes are **copied** from the image (so an instrument edit applies from the next note) with every position 0,
the pitch accumulator 0, the relative flag; the note settings are loaded from the instrument (shape, auto/manual,
octave J, manual period, sync; mixer override none, noise override none), then the row's collected settings applied
in column order; phase on; pitch state base = target = 64 *n*, slide offset 0, arpeggio step 0, vibrato phase 0, sweep
offset 64 · start (when the sweep speed ≠ 0, else 0), sweep end 64 · end, sweep step 4 · speed; **stamp** = ++stamp
counter; restart pending (if the note uses the hardware envelope).

**Note settings** (current per channel; `E`, `H`, `I`, `J`, `V`, `W` change them): `Exx` shape (00 = off, else
08..0F; a change of shape on the sounding note sets restart pending), `Hxx` manual period high byte / `Ixx` low byte
(the other byte kept; both switch the channel to manual), `Jxx` auto with octave offset J = xx as s8 (−8..7), `Vxx`
mixer override 0..3, `Wxx` noise override 0..31.

**Key-off** (OFF, `Sxx`): if the phase is on: a volume envelope with a release point continues there (phase
released); anything else turns the phase off at once.

**Evaluating a channel** (per tick): envelope values at the current positions — level = 0 if the phase is off, else
the volume envelope's value (15 without one); arpeggio value (0 without); bend = 4 · the pitch envelope's value (0
without), with the relative flag the accumulator += bend (clamped ±6144) and the offset is the accumulator, else the
bend; mixer *m* = the override if set, else the mixer envelope's value (1 without one); noise *nz* = the override if
set, else the noise envelope's value, else none. Pitch = clamp(base + 64 · (arpeggio effect + arpeggio value) + fine
+ slide + vibrato + sweep + offset, 0, 6143), vibrato = floor(`AY_SINE`[phase] · depth / 16), arpeggio effect = 0,
x, y for step 0, 1, 2 of `0xy`. *v* = (level · (volume8 >> 3) + 14) / 15 (integer division: non-zero whenever both
are). The channel **sounds** when the phase is not off and *v* > 0. It **uses the envelope** when it sounds and its
shape ≠ 0; its EP is the auto period EP(pitch, J, shape) or the manual period. TP = the synced tone period when it
uses the envelope in auto mode with sync, else TP(pitch). Register values: tone period TP (written as R(2c) = TP & 255,
R(2c + 1) = TP >> 8); amplitude = 0 when it does not sound, `0x10` when it uses the envelope, else *v*; mixer bits:
tone enabled iff it sounds and *m* bit 0, noise enabled iff it sounds and *m* bit 1 (a disabled source sets its
R7 bit). R7 = `io` | the three channels' disable bits (a never-keyed channel: both disabled).

### Shared noise and envelope

The AY has one noise generator and one envelope generator for three channels; the player arbitrates them by the
**most recent key-on**, the rule the editor shows and the validator explains:

- **Noise**: the channels that sound with noise enabled and an *nz* compete; the one with the highest stamp owns R6,
  which is written with its *nz* (shadowed). A channel with noise enabled but no *nz* plays whatever R6 holds. No
  candidate: R6 keeps its value.
- **Envelope**: the channels that use the envelope compete; the one with the highest stamp is the **envelope owner**:
  R11/R12 = its EP (shadowed, low byte first), and R13 = its shape when restart is pending for it (its key-on, or an
  `Exx` changing its shape) or when ownership moved to it from another channel (or from none) and its shape differs
  from the last R13 written (an unchanged shape keeps running: a held buzzer bass is not retriggered when a short note
  that borrowed the envelope ends). Every channel that uses the envelope sets M, so it plays the owner's envelope:
  exactly what the hardware does. **Restart pending** stays set while a note is silent, then clears on its first tick
  using the shared envelope (owner or not), or when its phase is off. A note that starts silent (a volume envelope
  beginning at 0, `C00`) and first becomes audible as owner restarts the envelope then, even if the shape equals the
  last R13 written. An audible non-owner has already heard the shared envelope; taking ownership later does not
  replay its old key-on. This keeps a finished one-shot silent and a continuous envelope running through a handoff.
- The stamp is the key-on counter, so a later note always wins; channels keyed on the same tick are ordered by
  channel (C wins over A).

**Advancing** a channel (step 4): arpeggio step (mod 3) if set; slide offset ± speed (clamped ±6144); base toward
target by the portamento speed; vibrato phase + speed (mod 64) when speed and depth are set; sweep offset toward its
end by its step; volume8 + x − y clamped 0..120 for `Axy`; then each envelope steps (FamiStudio's rules, as the Mega
Drive's PSG envelopes: next = position + 1; not released and next = the release point → the loop index; else next =
L → the loop index if the envelope has a loop and no release point, else L − 1). A released note whose volume position
is now L − 1 with value 0 turns its phase off.

### Effects (what Kotlin's effect specs declare)

Up to 4 effect columns per channel; every effect plays on every channel. "Note" = set for the note keyed on this row;
on a row without a key-on it changes the sounding note at once.

| Effect | Parameter | Meaning |
|---|---|---|
| `0xy` arpeggio | x, y semitones | as the Mega Drive |
| `1xx` / `2xx` slide | 1/64 semitone per tick | as the Mega Drive |
| `3xx` portamento | 1/64 semitone per tick | as the Mega Drive |
| `4xy` vibrato | speed, depth | as the Mega Drive |
| `Axy` volume slide | x up, y down, 1/8 step per tick | now: volume 0..15 |
| `Bxx` jump, `Dxx` break, `Fxx` speed, `Gxx` delay | | as the Mega Drive |
| `Cxx` volume | 00-0F | now: channel volume (start 0F), scales the level; 00 silences (also a hardware-envelope note) |
| `Exx` envelope shape | 00 off, 08-0F | note: the hardware envelope shape (8 `\\\\`, 9 `\___`, A `\/\/`, B `\‾‾`, C `////`, D `/‾‾`, E `/\/\`, F `/___`); a change restarts the envelope |
| `Hxx` envelope period high | 00-FF | note: manual envelope period, high byte (low kept) |
| `Ixx` envelope period low | 00-FF | note: manual envelope period, low byte (high kept) |
| `Jxx` auto envelope | 00-07 up, F8-FF down (s8) | note: the envelope period follows the note, xx octaves up (J00 = the note's own pitch) |
| `Pxx` fine pitch | 80 = none | as the Mega Drive (1/64 semitone) |
| `Sxx` note off | tick | as the Mega Drive |
| `Vxx` mixer | 00-03 | note: tone (bit 0) and noise (bit 1) gates; 00 = neither (the constant level, or the bare hardware envelope: the classic buzzer) |
| `Wxx` noise period | 00-1F | note: the noise period this note asks for (0 acts as 1 on the chip) |

### Position, seek and live edits

- `position()`: the player's (order, row, tick), exact through `Fxx`, `Gxx` and jumps.
- `start(order, row, max_ticks)`: machine reset (chip power-on: registers 0, LFSR 1, envelope holding at 0), then
  the player runs from the song start until the next tick starts (order, row) (`CHIP_SEEK_FROM_START`) with **every
  tick's writes applied to the chip and the chip silently advanced through every step** (no pre-roll, no register
  restore: the chip state after a seek is bit-identical to straight play's, which the host test proves by comparing
  states and the following output). Beyond `max_ticks`: the OPL3's order fallback (`CHIP_SEEK_FROM_ORDER`: reset, the
  player placed at the order's row 0 with its start speed, earlier rows of the order replayed with the chip silently
  clocked). Cost estimate ~0.5 µs per tick (player + arithmetic chip) [INFERENCE: not measured]; `seekTicks` 12000.
- `apply_image` (realtime-safe): the OPL3's relocation rules (`ay_player_relocate`): the player keeps no pointers into
  an image (rows read by (order, row) at row start; envelopes copied at key-on), positions clamped, speed rules,
  started rows re-read their jumps and delays. A changed variant byte is ignored (the engine is the variant's).

### Audition

`audition(channel 0..2, note 0..95, slot 1..64, fine −128..127)`: the (spare) machine is reset and the player keys on
the note on that channel with the slot's instrument and its own settings (no row settings), `fine` added like `Pxx`;
ticks then only evaluate and advance (envelopes and sweeps play; the shared resources are the auditioned channel's).
An undefined slot or arguments outside these ranges: `CHIP_ERR_IMAGE`. `release()`: 0 before any tick or when the
phase is already off; else the key-off applies from the next tick, returning 1, and `tick` returns 0 once the phase
is off or after 10 s (a looping volume envelope without a release point is silenced at key-off).

### Mute, mix and ops behaviour

`mute(channel 0..2)` leaves the channel out of the chip's mix only (the player and the chip run on; the VGM is
unchanged; unmuting resumes mid-note; a muted envelope owner still drives the envelope the others hear).
`start`/`audition` clear mutes.

| Op | Behaviour |
|---|---|
| `load_image` | copy, then `ay_load_image` for the engine's variant; `image_storage` = `sizeof(AyImage)` |
| `start(order, row)` | above |
| `apply_image` | above; ignored while auditioning |
| `audition`, `release` | above |
| `mute(channel 0-2)` | above |
| `position` | the player's |
| `tick` | one frame interrupt of whole chip steps: 959-960 frames at 48 kHz (ZX), 800-802 (MSX), 958-959 (ST), 958-959 (CPC); 96 kHz 1918-1920 / 1601-1603 / 1917-1918 / 1916-1917; 0 after a released audition ended |

**Kotlin** (`PlaybackEngine` of each variant): `id` the variant's, `imageSize` 65536, `seekTicks` 12000, `note(n)` =
n, `fine` = the system's fine pitch in 1/64 semitone, `ticksPerSecond` = *M* / `frame_div`.

### VGM export

`ay_vgm_render` (`ay/ay_vgm.c`) runs the player headless and logs its writes as a VGM 1.71 for one AY8910-family
chip; `tools/audio/ay/ay_ref.py IMAGE --vgm OUT` writes the same bytes from the Python reference.

- Header (256 bytes, others 0): `"Vgm "`, 0x04 = file size − 4, 0x08 = 0x171, 0x18 = total samples (44.1 kHz), 0x1C
  = loop offset − 0x1C and 0x20 = loop samples (both 0 without a loop), 0x24 = 50 (ZX, ST, CPC) or 60 (MSX), 0x34 = 0xCC
  (data at 0x100), 0x74 = the chip clock and 0x78 = the chip type from the variants table, 0x79 = 0x01 (flags:
  legacy single mixed output), 0x7A-0x7B = 0.
- Data: the player's reset batch; then per tick: at the first arrival at (loop order, row 0, tick 0) the loop point
  and a dump of R0-R12 (all values, zeros included; **not R13**, whose write would restart the envelope); the tick's
  writes `0xA0 reg val`; the tick's wait in 44.1 kHz samples by exact accumulation (`acc += 44100 · frame_div; wait =
  acc / M; acc %= M` in 64 bits: ZX 881/882, MSX 735/736, ST 881/882, CPC 880/881), as `0x62` (735), `0x63` (882), `0x70`-`0x7F` (1-16)
  or `0x61 nn nn`. The song ends at the second arrival at the loop point (or at the tick limit, then without a loop),
  then `0x66`. A loop that starts while a hardware-envelope note is held continues the envelope as the previous pass
  left it.
- **Hosts' entry point** (`ay/ay_export.h`, self-contained for cinterop): `ay_export_vgm(variant, image, 65536,
  max_ticks, title, author, date, &size, &problem)` validates the image for that variant, renders the file above and
  appends a GD3 1.00 tag (`vgm_gd3.c`; system "ZX Spectrum 128 (Thor Tracker)", "MSX (Thor Tracker)", "Atari ST
  (Thor Tracker)" or "Amstrad CPC (Thor Tracker)", converter "Thor Tracker", date today unless given); free with `ay_export_free`. Android: JNI
  `ChipAudio.nativeAyVgm(variant, image, maxTicks, title, author, date)` (strings as UTF-8 byte arrays;
  `IllegalArgumentException(problem)`), registered as `AyVgm.renderer`; Deck: `ay_export.h` in `decknative.def`,
  `DeckAyVgm` registered by `main`. Ten minutes of ticks at the variant's rate end songs that never loop back.

### Shared names (`ay/ay_image.h`, `ay_player.h`, `ay_machine.h` → `backend/AyPlayerImage.kt`)

`prepare_ay.py` parses the C headers and writes `object AyPlayerImage` with: `ENGINE_IDS` (list by variant),
`IMAGE_SIZE`, `MAGIC`, `LAYOUT`, header offsets `H_MAGIC H_LAYOUT H_VARIANT H_SPEED H_ORDERS H_LOOP H_INSTRUMENTS
H_DATA_SIZE HEADER_SIZE`, variants `VARIANT_ZX128 VARIANT_MSX VARIANT_ATARI_ST VARIANT_CPC`, `OFF_ROWS OFF_ORDERS OFF_INSTRUMENTS
INSTRUMENT_SIZE OFF_DATA DATA_CAPACITY`, instrument fields `I_FLAGS FLAG_DEFINED FLAG_PITCH_RELATIVE FLAG_AUTO_PERIOD
FLAG_SYNC I_SHAPE I_OCTAVE I_PERIOD I_ENV_VOLUME I_ENV_ARPEGGIO I_ENV_PITCH I_ENV_MIXER I_ENV_NOISE I_SWEEP_START
I_SWEEP_END I_SWEEP_SPEED SWEEP_LIMIT OCTAVE_MIN OCTAVE_MAX`, envelopes `ENV_HEADER ENV_MAX_LENGTH NO_ENVELOPE
PITCH_UNIT`, patterns `RUN MAX_RUN MASK_NOTE MASK_INSTRUMENT MASK_EFFECT_SHIFT NOTE_OFF NOTES`, limits `CHANNELS
MAX_ORDERS MAX_INSTRUMENTS MAX_EFFECTS TONE_PERIOD_MAX NOISE_PERIOD_MAX ENV_PERIOD_MAX`, pitch
`PITCH_UNITS_PER_SEMITONE PITCH_MAX`, effect codes `EFFECT_LETTERS FX_ARPEGGIO FX_SLIDE_UP FX_SLIDE_DOWN
FX_PORTAMENTO FX_VIBRATO FX_VOLUME_SLIDE FX_JUMP FX_VOLUME FX_BREAK FX_ENV_SHAPE FX_SPEED FX_DELAY FX_ENV_HIGH
FX_ENV_LOW FX_ENV_AUTO FX_FINE FX_CUT FX_MIXER FX_NOISE`, timing `MASTER_HZ AY_DIV FRAME_DIV` (IntArrays by variant),
`IO_BITS` (by variant), `STEREO` (by variant: 1 = the CPC's A+B / B+C socket), `SEEK_TICKS`, `GD3_SYSTEMS` (by
variant), `GD3_CONVERTER`, the tables `DIV_ZX128 DIV_MSX DIV_ATARI_ST DIV_CPC` (768 entries each) and `LOADER_PROBLEMS`
(every reason `ay_load_image` reports, in `ay_image.c` order).

### Preparing the tables and fixtures

`python3 tools/audio/prepare_ay.py` (pure Python 3) writes `app/src/main/cpp/generated/ay_tables.h` (from
`ay_ref.py`'s table functions), `core/.../backend/AyPlayerImage.kt` and `app/src/test/cpp/ay_test_song.h` (the test
images per variant: the song, the same song laid out anew, a note edit, a tempo edit and the song cut to two orders;
the reference player's per-tick writes and positions for 1500 ticks; the reference VGM's size, FNV-1a hash and loop
per variant; byte offsets for the corruption checks). `python3 tools/audio/ay/ay_golden.py` writes the Kotlin golden
images (`core/src/jvmTest/resources/ay-golden/`). Rerun both after changing the player specification, the engine
headers, the reference or the test songs.

### Verification

- `tools/audio/run_native_host_test.sh [dir]` adds `app/src/test/cpp/ay_machine_test.c`: the tables (A-4 per
  variant, every note in range within the period quantisation), corruption checks, the player's writes and positions
  equal to the reference in every tick for every variant, the delayed envelope restart (a hardware-envelope note
  starting silent through a volume envelope from 0 or `C00` writes R13 once on its first audible tick although the
  previous note left the same one-shot shape, the chip then at the ramp's start; a note keyed off before it sounds
  drops its pending restart), chip unit checks (tone frequency by zero crossings, noise
  LFSR sequence, all 16 shapes on both chip types against the manuals' tables, period-0 behaviour, R13 restart),
  silent advance = stepping (states after every tick of the test song), seek bit-identical to straight play, loop
  passes, live edits, audition (A-4 measured per variant, envelope buzzer pitch, release), mute, VGM bytes = the
  reference's, the export entry point and the ops tables; **independent comparison**: 9.4 million chip steps
  against vendored ayumi check exact tone counters/edges, noise counter/LFSR, envelope steps and gated DAC levels.
  AY envelope phase is compared from nonzero-period R13 restarts until period changes (ayumi's duplicated
  32-step AY model differs at those boundaries; period zero has its own MAME-derived regression). The song's
  writes are replayed at exact chip-step timestamps through ayumi's resampler, not rounded to output samples:
  that rounding measurably changes synced-buzzer timbre. Solo/mixed 20 ms RMS-envelope correlation must reach
  0.95 and overall RMS must stay within 0.8–1.25 of the reference; no zero-crossing pitch estimate is made from
  polyphony or noise. CPC sides are checked separately: left A + B, right B + C.
  `wav_render_test.c` renders the four demos and the shortest valid song (one
  order of one row, speed 1) per variant.
- `tools/audio/ay_reference_check.py <dir> [--vgm2wav PATH]`: the reference reproduces the trace and the VGM; the VGM
  parsed from the format alone; optionally played by libvgm.
- `tools/audio/render_ay_image.sh image out.wav [seconds] [mute=mask] [--vgm out.vgm]` renders an image through the
  ops table (the variant from the image) and its VGM through `ay_export_vgm`.
- Executed results: `docs/VERIFICATION.md`, 0.25.0 — native/reference comparisons, Android device timings,
  SDL playback, export parity and Game Boy regression proof. No human listening or vintage-hardware measurement
  is claimed.

## PC Engine: Thor Tracker's own player on a HuC6280 PSG (`"pce-huc6280"`)

**Status: integrated for 0.26.0**, with native/reference, Android and SDL verification recorded in `VERIFICATION.md`.
The NEC PC Engine / TurboGrafx-16 sound generator is the PSG inside Hudson's HuC6280 CPU: six channels, each
a 32-step 5-bit wavetable or a direct D/A (DDA) output, noise on the last two, one shared LFO (channel 2's waveform
modulating channel 1's frequency) and a two-stage stereo volume (per channel and master). There is no standard sound
driver to run, so it follows the OPL3 and OPM: **our own player** (`pce/pce_player.c`, pure integer C without a chip)
whose behaviour is the specification in this section; `tools/audio/pce/pce_ref.py` implements the same specification
separately in Python and the host test requires identical register writes (tick batches and timer-interrupt batches).
Songs export as VGM (the player's register log) and WAV. Not a console executable: no HuCard/HES export (that would
need a real 6280 driver). This section is the contract of the Kotlin system and lowering (`core` `system/pce`,
`backend/PceLowering.kt`); the numbers they share with the engine are generated into `backend/PcePlayerImage.kt`
(below, "Preparing").

### Chip, clocks and core

- **Core**: Mednafen 1.32.1's PSG (`src/hw_sound/pce_psg/pce_psg.cpp`, `pce_psg.h`; GPL-2.0-or-later), vendored
  **unmodified** in `app/src/main/cpp/third_party/mednafen-pce-psg/` with Mednafen's `COPYING`, compiled once in
  `pce/pce_psg.cpp` (`#include "pce_psg.cpp"`) against a minimal shim (`third_party/mednafen-pce-psg/shim/`:
  `mednafen/mednafen.h` with the integer types, `INLINE`, `MDFN_COLD` and no-op save-state macros; an empty
  `trio/trio.h`). Pin: `mednafen-1.32.1.tar.xz` from `mednafen.github.io/releases/files/`, sha256
  `de7eb94ab66212ae7758376524368a8ab208234b33796625ca630547dbc83832`; `pce_psg.cpp` sha256
  `e6a029e33e0c1fe9330b358b0610c146eec481b5a2e7b804324b10f87d55f8c5`, `pce_psg.h`
  `5307959029251c8f85e43f1231215f0add2e59d12ce15ec486053f1fe31f796c`, `COPYING`
  `8177f97513213526df2cf6184d8ff986c675afb514d4e68a404010521b880643`. Mednafen's PSG was chosen because it is timed
  per CPU cycle (waveform counters, the noise LFSR, the LFO, the DDA latch, the hardware's sequential volume latching)
  and its behaviour was checked by its author on hardware (comments in the source); MAME's `c6280.cpp` (BSD-3-Clause,
  per output sample) is the cross-check for register semantics: both agree on the LFO (channel 2 modulates channel 1
  with shift 0, 2 or 4; channel 2 is **not** muted while it is the modulator), on the DDA index reset and on the noise
  LFSR taps.
- **Revision**: the original HuC6280 (Mednafen `REVISION_HUC6280`, its default for the PC Engine; the HuC6280A of the
  SuperGrafx and CoreGrafx I centres the channel amplitude, not modelled). Channels output only with control bit 7 set;
  samples are unipolar (0..62 × volume).
- **Clocks** (NTSC): master 21477272.73 Hz (= 236250000 / 11); the CPU, its timer and Mednafen's PSG timestamps run at
  master / 3 = **7159090.91 Hz** (78750000 / 11, "cycles" below); the waveform counters step at master / 6 =
  3579545.45 Hz. A channel with 12-bit period *N* (register value 0 = 4096) sounds **3579545.45 / (32 · N) Hz**; a
  noise channel's LFSR is clocked every (31 − NF) · 128 cycles (NF 31: 64). VGM's HuC6280 clock is 3579545.
- **DDA timer**: the HuC6280's own timer (Mednafen `src/pce/huc6280.cpp` `TimerSync`: a 7-bit reload value *R*,
  decremented every 1024 cycles, an interrupt and reload when it passes below 0) interrupts every **1024 · (R + 1)
  cycles = 6991.3 / (R + 1) Hz**. A new reload value applies from the next reload (after the next interrupt): the first
  interrupt comes 1024 · (R + 1) cycles after the start/audition reset (R = the header's), each next one 1024 · (R + 1)
  cycles later with R as the player holds it at that interrupt. An interrupt on a tick's first cycle runs after that
  tick's batch. The player streams DDA samples from this interrupt (`pcp_timer`), as PC Engine sound drivers do.
- **Output**: Mednafen writes band-limited deltas (its 7-tap phase filter, gain 2048) into two 32-bit buffers at master
  / 12 = 1789772.73 Hz (index = cycle >> 2). The wrapper integrates them per index (level += delta; value = level >> 12,
  i.e. Mednafen's scale ÷ 2048 ÷ 2: one channel at full volume swings 0..5290, leaving headroom for six unipolar
  channels) and feeds the value changes to blip_buf 1.1.0 at 1789772.73 Hz → the host rate (its ~15 Hz high-pass removes
  the HuC6280's DC), float = sample / 32768. `SetVolume(1.0)` (Mednafen's PC Engine HuCard level). The last 8 indices of
  a tick (the filter's tail past the tick end) carry into the next tick; timestamps restart each tick at the
  leftover `(start + L) & 3`.
- **Tick** = 1/60 s / multispeed (header byte 5, N = 1..8): tick *k* (from the song start or audition) spans cycles
  ⌊k · 1312500 / (11 N)⌋ .. ⌊(k + 1) · 1312500 / (11 N)⌋ (119318.18 / N cycles, exact). The player's batch is written at
  the tick's first cycle (all at one timestamp, in order); the DDA timer interrupts falling inside the tick are written
  at their cycles. As the OPL3 and OPM, the tempo is the host timer, not a console's vblank.
- **Mute** (`pcm_mute`): the machine forces bits 0-4 of every register 4 value it sends to a muted channel to 0
  (volume 0 = attenuation ≥ 31 steps = silence in Mednafen's volume table), keeping bits 6-7 (so waveform-index side
  effects are those of the unmuted song); toggling writes the channel's register 4 (from the player's shadow, masked
  or not) and restores the select register. The player, its writes and the VGM are unchanged; waveform, noise and LFO
  phases keep running (a muted channel 2 still modulates channel 1).
- **Chip reset**: `start` and `audition` rebuild Mednafen's `PCE_PSG` object in place (its `Power()` keeps per-channel
  filter history and timestamps, which would offset the cleared integrator), then `SetVolume(1.0)`, `Power(0)`; the
  delta buffers and blip are cleared and mutes cleared; no allocation outside `pcm_create`.

### Channels and shared resources

6 tracker channels = the PSG's channels 0-5, `PSG 1`-`PSG 6`. **No chord effect, no voice allocation.** A channel plays
one of three instrument kinds: **tone** (its 32-step wave, loaded into the channel's waveform RAM), **noise** (the
LFSR; channels 5 and 6 only — the hardware has noise only on channels 4 and 5 counting from 0) and **sample** (DDA:
the channel's D/A latch written from the timer interrupt; any channel). Shared chip resources are song state (header)
changed by global effects, never per-instrument data:

| Shared resource | Register | Song start (header) | Effect |
|---|---|---|---|
| Master balance | R1 = left << 4 \| right (0..15 each; 15 = full, 3 dB per step) | byte 14 | `Xxy` |
| LFO frequency | R8 0..255 (0 = 256): channel 2's step period × this | byte 15 | `Lxx` |
| LFO control | R9: bits 0-1 mode (0 off; 1, 2, 3 = channel 2's sample − 16 shifted left 0, 2, 4 and added to channel 1's period), bit 7 = halt and reset channel 2's waveform index | byte 16 | `Mxx` |
| DDA timer | the CPU timer's reload value *R* 0..127 (not a PSG register: no write) | byte 17 | `Txx` |

**Volume** (both cores, "verified from both patent and manual" in MAME): attenuation in 1.5 dB steps = (31 −
volume) + (31 − scale(channel balance side)) + (31 − scale(master side)), at most 31 (= silence), with scale =
`0, 3, 5, 7, 9, 11, 13, 15, 16, 19, 21, 23, 25, 27, 29, 31` for 0..15 (so a balance step is 3 dB). The player's 5-bit
channel volume is R4 bits 0-4.

**LFO**: when R9's mode is not 0, channel 2's waveform (stepping at its own period × the LFO frequency, 0 = 256) offsets
channel 1's period by (sample − 16) << (0, 2, 4)[mode − 1]; it acts only while channel 2 is enabled (R4 bit 7) and R9
bit 7 is clear. Channel 2 keeps sounding at its volume (both cores): a song that wants only the vibrato sets channel 2's
volume to 0 (`C00`) while keeping a note on it. In the tracker: channel 2's note and wave are the LFO's rate and shape.

### Image layout (what the Kotlin lowering produces)

`image_size` = **131072** bytes, little-endian, every pattern and envelope reference a u16 offset and every sample a
u32 offset into the data region, so any layout that passes `pc_load_image` (`pce/pce_image.c`) relocates. Constants:
`pce/pce_image.h` (`PC_*`); the reference builder is `tools/audio/pce/pce_song.py` (`build()`). The loader rejects
anything off-contract with a reason and the byte (`PcInfo.problem`, `problem_offset`); the Kotlin validator mirrors its
checks with user-facing messages.

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | `"TTPC"` |
| 4 | 1 | layout: 1 |
| 5 | 1 | multispeed N, 1..8 (player ticks per 60 Hz frame) |
| 6 | 1 | speed (ticks per row) at the song start, 1..255 |
| 7 | 1 | instrument count I = highest slot present, 0..64 |
| 8 | 2 | order count, 1..256 |
| 10 | 2 | loop order (< count) |
| 12 | 1 | wave count W, 0..64 |
| 13 | 1 | sample count S, 0..64 |
| 14 | 1 | master balance R1 at the start (left << 4 \| right) |
| 15 | 1 | LFO frequency R8 at the start, 0..255 |
| 16 | 1 | LFO control R9 at the start: 0..3 or `$80`..`$83` |
| 17 | 1 | DDA timer reload *R* at the start, 0..127 |
| 18 | 2 | 0 |
| 20 | 4 | data size D: bytes of the data region used, ≤ 123072 |
| 24 | 40 | 0 |
| 64 | 256 | rows of order *o* at 64 + *o*: 1..255, 0 = 256 (Thor Tracker: ≤ 64); entries ≥ count 0 |
| 320 | 3072 | pattern of (order *o*, channel *c*) = u16 offset into the data region at 320 + 2 (6 *o* + *c*); entries ≥ count 0 |
| 3392 | 2048 | instrument slot *n* (1..64) at 3392 + 32 (*n* − 1) |
| 5440 | 2048 | wave *w* (0..63) at 5440 + 32 *w*: 32 samples 0..31 in play order; waves ≥ W all zero |
| 7488 | 512 | sample *s* (0..63) at 7488 + 8 *s*: u32 offset into the data region, u32 length (frames, ≥ 1); entries ≥ S zero |
| 8000 | 123072 | data region `[0, D)`: patterns, envelopes, sample data (5-bit values 0..31, one per byte) |

Pattern and envelope offsets are u16, so the lowering places patterns, then envelopes, then sample data (the samples
may lie beyond 65535). The lowering refuses a song whose patterns or envelopes would start beyond data offset 65535
(the validator: "patterns and envelopes pass the 64 KB their offsets reach"); samples may lie anywhere below D, each
1..65535 frames (`PC_MAX_SAMPLE_FRAMES`: the u16 loop start).

**Instrument** (32 bytes; an all-zero record = no instrument; records above I must be zero):

| Byte | Field |
|---|---|
| 0 | flags: bit 7 = defined (1); bits 0-1 kind: 0 tone, 1 noise, 2 sample (3 invalid); bit 2 = pitch envelope is relative; others 0 |
| 1 | tone: wave 0..W − 1; other kinds 0 |
| 2 | sample: sample index 0..S − 1; other kinds 0 |
| 3 | sample: root note 9..95 (the note that plays one sample frame per timer interrupt); other kinds 0 |
| 4 | sample: 1 = loop, 0 = one-shot; other kinds 0 |
| 5 | 0 |
| 6 | u16 sample loop start frame (< the sample's length; 0 without loop or for other kinds) |
| 8 + 6 *k* | envelope *k* (0 volume, 1 arpeggio, 2 pitch, 3 wave): u16 offset into the data region, u8 length L (0 = none: offset 0, loop and release `$FF`), u8 loop (`$FF` none, else < L), u8 release (`$FF` none, else 1..L − 1, and > loop when both), u8 0 |

Envelope values (L bytes at the offset): volume 0..31; arpeggio s8 −64..63 semitones; pitch s8 −128..127 (1/64
semitone, absolute or relative per the flag); wave 0..W − 1, only on tone instruments (other kinds: length 0).

**Patterns**: decoded from the offset for exactly rows(*o*) rows (a run longer than the order's remaining rows is cut
there); every row read must lie inside `[0, D)`. Row encoding, as the OPL3's and OPM's:

| Bytes | Meaning |
|---|---|
| `0x80 \| k` | k + 1 empty rows (k 0..127) |
| `m` (0x01..0x13), then data | bit 0: a note byte follows; bit 1: an instrument byte follows; bits 2-4: effect count 0..4; bits 5-6 zero, m ≠ 0 |
| note | 9..95 = absolute note A-0..B-7 (0 = C-0, 57 = A-4), `0xFE` = OFF |
| instrument | slot 1..64, defined; a noise instrument only on channels 4 and 5 (`PSG 5`, `PSG 6`) |
| effects | per effect column in order: code (0..35 = `0`-`9`, `A`-`Z`), parameter |

Load-time parameter checks: `Bxx` < order count, `Cxx` ≤ 1F, `F00` rejected, `Mxx` ∈ 00..03 or 80..83, `Txx` ≤ 7F,
`Wxx` < W; header: magic, layout, multispeed 1..8, speed ≥ 1, I/W/S ≤ 64, R9 value as `Mxx`, timer ≤ 127, D ≤ capacity,
reserved bytes 0; instruments: flags, kind fields and envelopes as above, every envelope and sample range inside
`[0, D)` (sample lengths 1..65535 frames, `PC_MAX_SAMPLE_FRAMES`), values in range; waves ≤ 31 and zero above W; sample entries zero above S. Codes without
an effect (`5`, `6`, `7`, `9`, `E`, `H`, `I`, `J`, `K`, `N`, `O`, `Q`, `R`, `U`, `V`, `Y`, `Z`) are rejected ("unknown
effect code"); the other effects take any parameter. `pc_load_image` derives (`PcInfo`) the order count, loop,
multispeed, speed, rows per order and the speed each order starts with in sequential straight play (header speed, then
the last `Fxx` of each order: rows, then channels, then columns; jumps ignored).

**What the lowering writes** from a `Cell` with up to 4 effect columns: note `EMPTY` → no note byte, `CUT` → `0xFE`,
an absolute note → itself; instrument 0 → no byte; effect columns in column order, blank columns (code 0, parameter 0)
omitted; empty rows in runs; byte-identical (order, channel) encodings stored once in first-use order (orders, then
channels), then each instrument's envelopes in slot order (volume, arpeggio, pitch, wave; byte-identical envelopes
stored once, first use), then the samples in sample order (identical data stored once); every filled instrument slot
written (the instrument count is the highest), every wave of the song (W = its count), every sample of the song (S).

### Player

Units: **pitch** in 1/64 semitone, 0 = C-0; notes are pitch = note · 64. Clamp range `PCP_PITCH_MIN` = 569 ..
`PCP_PITCH_MAX` = 6143 (569 is the lowest pitch whose period fits 12 bits, 4093; 6143 = B-7 + 63/64).

**Period** (`pcp_period`): `PCP_PERIOD_Q16[i]` (*i* = 0..767) = round(39375000 / 11 / (32 · 440 · 2^((i / 64 − 57) /
12)) · 65536) in IEEE double precision (39375000 / 11 = 3579545.4545… Hz exactly; a rounded decimal clock changes 4
entries) — the C-0 octave in 16.16 fixed point, written out in `pce_player.c` (`pce_ref.py` computes it with that
formula and `prepare_pce.py` fails on any difference); for pitch *p* (clamped), *o* = p / 768, *i* = p mod 768: N =
(T[i] + (1 << (15 + o))) >> (16 + o). A-4 = N 254 = **440.40 Hz (+1.56 cents)**; A-0 4068; B-7 28 (+19.2 cents). Within
A-0..B-7 the worst error is 19.2 cents (the 12-bit period's resolution at the top; 4.2 cents up to C-6, 14.5 at C-7).

**Sample step** (`pcp_sample_step(pitch, root)`): frames per timer interrupt in 16.16 = Q(root · 64) / Q(pitch) ·
65536 with Q(p) = T[p mod 768] / 2^(p / 768), in integers: rp = root · 64, d = p / 768 − rp / 768; d ≥ 0: (T[rp mod
768] << (16 + d)) / T[p mod 768]; d < 0: ((T[rp mod 768] << 16) / T[p mod 768]) >> −d (unsigned 64-bit, truncating).
The root note plays 65536 (one frame per interrupt).

**Writes** are (register 0..9, value) pairs. Registers 2-7 act on the selected channel: before a write to channel *c*'s
register the player emits R0 = *c* if its select (`select`, −1 after reset until R0 is written) differs. Shadows (the
value last written): global R1, R8, R9; per channel R2, R3, R4, R5, R7 (channels 4, 5). A write equal to its shadow is
not emitted (and emits no R0). R6 (wave/DDA data) is never shadowed. Wave loads write R4 twice explicitly (below).

**Reset** (start, audition): all state cleared; channels: instrument 0, volume 31 (vol8 248), pan `$FF`, cut −1, not
sounding, loaded wave −1; shared state from the header; position (0, 0, tick 0) with the header speed. The reset batch
(explicit, every write emitted, setting the shadows): R1 = master, R8 = LFO frequency, R9 = LFO control; then for *c*
= 0..5: R0 = *c*, R2 = 0, R3 = 0, R4 = 0, R5 = `$FF`, and for *c* ≥ 4 R7 = 0. Select is 5 afterwards.

**Tick** (positions are (order, row, tick-in-row); the host's position is the tick about to run):

1. The globals through their shadows: R1 = master, R8 = LFO frequency, R9 = LFO control (emitted only when changed: an
   effect of the previous tick is written at once, so this matters only for live edits).
2. Tick 0 of a row: read every channel's row at (order, row). Then, over channels 0..5 and their effect columns in
   order: `Fxx` sets the speed (the last one wins and applies to this row); `Bxx`/`Dxx` record the next position: `B`
   only → (xx, 0); `D` only → (next order, xx), the next order being order + 1 or, past the last one, the loop order;
   both → (B's order, D's row). Then for each channel 0..5: its cut is cleared; if the row has `Gxx` with xx > 0 (the
   last `G` of the row) its row is delayed to tick xx; otherwise the row runs now. Other ticks: each channel whose
   delayed row is due (tick = xx) runs it, channels in order.
3. Each channel whose cut tick (`Sxx`) equals this tick gets OFF, channels in order.
4. Each sounding channel 0..5 is updated (below), channels in order.
5. tick + 1; when it reaches the speed: tick 0 and the next row — the recorded jump (row clamped to the target order's
   last row), else row + 1, after the last row of an order the next order, after the last order the loop order.
   **Loop speed**: whenever the next row is row 0 of the loop order the speed becomes `PcInfo.start_speed[loop]`.

**Running a row** of a channel: if the row has a note (9..95), the arpeggio, fine pitch and wave override are cleared;
an instrument byte sets the channel's instrument; the effect columns run in order (table below; `B`, `D`, `F`, `G`
already acted); then the note: OFF → **OFF** (below); a note with portamento active (`3xx`, xx > 0) while the channel is
holding an unreleased tone or sample only sets the glide target (no key-on); any other note keys on, including during a release tail.

**Key-on** (note *n*, channel *c*): if the channel's instrument is not a defined slot, nothing happens. Else: kind and
instrument latched; envelope indices 0, released = 0, relative pitch accumulator 0; base = target = 64 *n*, slide offset
0, arpeggio step 0, vibrato phase 0; sounding = 1, fresh = 1; DDA active = (kind is sample), and for a sample position =
`$10000` (frame 1 next). Nothing is written now: the update writes (the first update of a sample writes frame 0).

**OFF**: if the channel is not sounding nothing happens. If the instrument's volume envelope has a release point: released
= 1 and every envelope of the instrument with a release point jumps to it (the value used by the next update). Else
**stop**: sounding = 0, DDA inactive, R4 = `$00` (tone, noise) or `$40` (sample) through the shadow.
A released channel stops when its volume envelope reaches its final zero value; intermediate zeroes and nonzero
final values do not stop it. Stopping also ends DDA timer writes. The penultimate release value gets its full tick.

**Envelopes** (value at index *idx* read in the update, then advanced): held part [0, H) with H = release if set, else
L. Not released, or released without a release point of its own: next = idx + 1; if next ≥ H: next = loop if set, else
H − 1. Released with a release point: next = idx + 1, at most L − 1. Without an envelope: volume 31, arpeggio 0, pitch
0, wave = the override (`Wxx`) if set, else the instrument's wave. A relative pitch envelope adds its value to the
accumulator (clamped ±6144) before use; an absolute one is used as is. A tone instrument with a wave envelope ignores
`Wxx`.

**Update** of a sounding channel *c* (per tick, after the rows): vol = max(0, volume value + (vol8 >> 3) − 31);
semitones = arpeggio effect (0, x, y for step 0, 1, 2 of `0xy`) + arpeggio value; pitch = clamp(base + 64 · semitones +
fine + slide + vibrato + pitch value, 569, 6143), vibrato = floor(SINE[phase] · depth / 16) (arithmetic right shift
by 4) with `SINE[i]` = floor(127 sin(2πi/64) + 0.5) (the OPL3's table, written out in both implementations). Writes:

- **tone**: wave *w* (envelope, override or instrument); if the channel's loaded wave ≠ *w*: **wave load** = R4 =
  `$40`, R4 = `$00` (both emitted unconditionally: bit 6 falling resets the write index), 32 × R6 = the wave's samples,
  loaded = *w*, shadow R4 = `$00`. Then R2 = N & `$FF`, R3 = N >> 8 (N = period(pitch)), on channels 4-5 R7 = `$00`, R5
  = pan, R4 = `$80` \| vol.
- **noise** (channels 4-5): NF = ((base >> 6) + semitones) & 31 (the note picks the noise rate: note mod 32, so C-0..G-2
  = 0..31, repeating every 32 semitones; NF 31 is the fastest); R7 = `$80` \| NF, R5 = pan, R4 = `$80` \| vol. Pitch
  effects other than arpeggios do not apply.
- **sample**: step = sample step(pitch, root); on channels 4-5 R7 = `$00`; R5 = pan; R4 = `$C0` \| vol; if fresh: R6 =
  the sample's frame 0 (after R4: DDA mode set, so the write goes to the latch, never to the wave RAM).

Then fresh = 0 and the envelopes and effects advance: envelope indices; arpeggio step (mod 3) if an arpeggio is set;
slide offset ± speed (clamped ±6144) for `1xx`/`2xx`; base toward target by the speed (no overshoot) for `3xx`; vibrato
phase + speed (mod 64) when speed and depth are set; vol8 + x − y (clamped 0..248) for `Axy`.

**Timer interrupt** (`pcp_timer`, a batch of its own): for each channel 0..5 with DDA active: f = position >> 16; if f
≥ length: with loop, position = (loop start << 16) + (position − (length << 16)) mod ((length − loop start) << 16) and f
= position >> 16; without loop: stop (DDA inactive, sounding = 0, R4 = `$40`) and next channel. Else R6 = frame *f*
(R0 first when needed), position += step (the step of the last update).

### Effects (what Kotlin's effect specs declare for `"pce-huc6280"`)

Up to 4 effect columns per channel. "Note" = for the note keyed on this row only; "now" = from this row on; "shared" =
the chip's one resource, for every channel, written at once (the globals) and kept until changed.

| Effect | Parameter | Meaning |
|---|---|---|
| `0xy` arpeggio | x, y semitones | note: steps note, +x, +y every tick (also noise rates); also sets the step to 0 (on a row without a note it changes the sounding note's arpeggio); the next note without `0xy` plays plain |
| `1xx` slide up | 1/64 semitones per tick | now: until `100`/`200`/`300`; a new note restarts the offset at 0 but keeps sliding |
| `2xx` slide down | 1/64 semitones per tick | now: as `1xx`, down |
| `3xx` portamento | 1/64 semitones per tick | now: while active a note on a held, unreleased tone/sample channel glides there without key-on; a note after OFF starts a fresh attack |
| `4xy` vibrato | x speed, y depth | now: the player's vibrato (depth F ±1.86 semitones), independent of the chip's LFO; `4x0` off |
| `8xy` pan | x left, y right, 0-F | now: the channel's balance R5 (`$FF` at reset; `800` silences) |
| `Axy` volume slide | x up, y down, 1/8 step per tick | now: until `A00` |
| `Bxx` jump | order | after this row play order xx row 0 (with `Dxx`: its row) |
| `Cxx` volume | 00-1F | now: channel volume (start 1F), 1.5 dB per step below 1F, added to the envelope's attenuation |
| `Dxx` break | row | after this row play row xx (clamped) of the next order |
| `Fxx` speed | 01-FF | now, global, this row included; the last of the row wins |
| `Gxx` note delay | ticks | the channel's row runs at tick xx; never if xx ≥ the speed |
| `Lxx` LFO frequency | 00-FF (00 = 256) | shared: R8 |
| `Mxx` LFO control | 00 off, 01-03 depth (shift 0, 2, 4), +80 halt/reset | shared: R9 |
| `Pxx` fine pitch | 80 = none | xx − 80 in 1/64 semitones until the next note |
| `Sxx` note off | tick | OFF at tick xx of this row |
| `Txx` DDA rate | timer reload 00-7F | shared: 6991.3 / (xx + 1) Hz from the next timer reload |
| `Wxx` wave | 00..W − 1 | now: the tone wave of the sounding note (loaded at the next update) and of later notes until the next note row without `W`; ignored by instruments with a wave envelope |
| `Xxy` master balance | x left, y right, 0-F | shared: R1 |

Shared effects run in the row order of the channels (channels 0..5, columns in order), so the last one in a row wins;
each sets the player's shared value and writes R1/R8/R9 at once (in this tick's batch, through the shadows; `Txx`
writes nothing). Volume, pan and wave change sounding notes at the next update (this tick).

### Position, seek and live edits

- `position()` is the player's (order, row, tick).
- `start(order, row, max_ticks)`: the player alone finds the number of ticks straight play needs to reach (order, row,
  0); if it is ≤ `max_ticks` the machine resets and plays that many ticks with the chip, the DDA timer and blip_buf
  clocked exactly as audible playback and the output discarded (`CHIP_SEEK_FROM_START`: every phase, LFSR, latch and
  filter state equals straight play's). Otherwise (`CHIP_SEEK_FROM_ORDER`): reset, `pcp_place` at the order start with
  the speed straight play starts it with (shared state from the header: `X`/`L`/`M`/`T` effects of earlier orders are
  not replayed), and the order's earlier rows replayed the same clocked way; a row behind a
  `Dxx` of the same order that is unreachable = `CHIP_ERR_IMAGE`. `PCM_SEEK_TICKS` = 3600 is the suggested budget.
- `apply_image` (live edit at a tick boundary, realtime-safe): (1) **position**: an order past the new count becomes
  the last order's last row, a row past its order's rows that order's last row; a clamped position drops the recorded
  jump and delayed rows, takes the speed straight play has at that row in the new song (its own `Fxx` included) and, if
  its row had not started (tick 0), counts as played: the next tick starts the row after it. (2) **speed**: otherwise,
  when the speed straight play has at (order, row) — the order's start speed and the `Fxx` of earlier rows, plus the
  row's own once it has started — differs between the old and the new image, the new one is taken (an edit of an
  earlier `Fxx` is heard at once; a speed set by a jump-reached path is kept when nothing relevant changed). (3) a
  started row's jump and still-delayed rows are re-read from the new data (a delayed row stays pending when its new
  `Gxx` ≥ the tick about to run). (4) Sounding notes keep their state and read their instrument's
  envelopes, wave and sample from the new image each tick (indices clamped to the new lengths; a sample position past
  the new length follows the end rule); a channel whose loaded wave's 32 bytes changed reloads it at its next update
  (loaded = −1); multispeed follows the header from the next tick; each shared header field (master, LFO frequency, LFO
  control, timer reload) whose value differs between the old and the new image sets the player's shared value to the
  new one (written by the next tick's globals). Robustness (live edits only): a sounding note reads its latched slot's
  record as it now is with its kind kept latched, envelope indices clamped to L − 1; a timer interrupt for a DDA
  channel whose slot is no longer a defined sample stops it like a one-shot end; a key-on of a noise sound on channels
  0-3 does nothing. A multispeed change rebases the tick timeline at the next tick (tick *k* from the edit's tick
  *k₀*: base + ⌊(k − k₀) · 1312500 / (11 N)⌋).

### Audition

`audition(channel 0..5, note 9..95, slot 1..64, fine −128..127)`: the machine is reset (header shared state) and the
player keys on the note with the slot's instrument, no locks, `fine` added like `Pxx`; ticks only update (no rows). A
noise instrument off channels 4-5, an undefined slot or arguments outside these ranges are `CHIP_ERR_IMAGE`.
`release()`: 0 before any tick (the audition is then over: `tick` returns 0); else OFF is applied and its batch written to the chip at once; returns 0 when that
stopped the channel (no release point), else 1. `tick` returns 0 once the channel stops (including a one-shot sample's
end or the final zero of a released volume envelope), or 10 s after release for a tail that never reaches zero.

### Mute and ops behaviour

| Op | Behaviour |
|---|---|
| `load_image` | copy, then `pc_load_image`; `image_storage` = `sizeof(PceImage)` |
| `start`, `apply_image`, `audition`, `release`, `mute`, `position` | above |
| `tick` | one tick: 800 frames at 48 kHz (multispeed N: 800 / N), ≤ 1601 at 96 kHz; 0 after a released audition ended |

**Kotlin** (`PlaybackEngine` for `"pce-huc6280"`): `imageSize` 131072, `seekTicks` 3600, `note(n)` = n, `fine` = the
system's fine pitch in 1/64 semitones, `ticksPerSecond` = 60 × multispeed.

### VGM export

`pc_vgm_render` (`pce/pce_vgm.c`) runs the player and the timer headless and logs their writes as a VGM 1.71 for one
HuC6280 (`tools/audio/pce/pce_ref.py IMAGE --vgm OUT` writes the same bytes from the Python reference).

- Header (256 bytes, others 0): `"Vgm "`, 0x04 = file size − 4, 0x08 = 0x171, 0x18 = total samples (44.1 kHz), 0x1C =
  loop offset − 0x1C and 0x20 = loop samples (both 0 without a loop), 0x24 = 60, 0x34 = 0xCC (data at 0x100), **0xA4 =
  3579545** (HuC6280 clock; VGM 1.61+). Writes are `0xB9 reg val`.
- Time: an event at absolute cycle *t* (ticks: ⌊k · 1312500 / (11 N)⌋; interrupts: their cycles) is at 44.1 kHz sample
  ⌊t · 4851 / 787500⌋ (= t · 44100 / 7159090.91). Before each event's writes, a wait of (its sample − the current
  sample): `0x62` for 735, `0x63` for 882, `0x70`-`0x7F` for 1-16, else `0x61 nn nn` (split at 65535). An event without
  writes emits nothing (no wait). The trace hook reports the reset batch as kind 0 at cycle 0.
- Data: the reset batch; then per tick: at the first arrival at (loop order, row 0, tick 0) the wait to that tick's
  start, then the loop point and a dump
  of the chip state as the player's shadows hold it: R1, R8, R9; for *c* = 0..5: R0 = *c*, a wave load of the loaded
  wave (if any; R4 `$40`, R4 `$00`, 32 × R6), R2, R3, R5, R7 (*c* ≥ 4), R4 = its shadow; then R0 = the player's select
  (if not −1); then the tick's writes, then each timer interrupt of the tick at its time. The song ends at the second
  arrival at the loop point (or the tick limit, then without a loop) with the wait to that tick's start, then `0x66`.
  The dump re-establishes waves at the loop point, so a VGM player's first pass restarts the loaded waves' phases there
  (the engine does not). This can alter loop-boundary phase; WAV preserves the app's continuous render.
- Hosts' entry point (`pce/pce_export.h`): `pc_export_vgm(image, 131072, max_ticks, title, author, date, &size,
  &problem)`, as `o3_export_vgm` with GD3 system "PC Engine (Thor Tracker)", converter "Thor Tracker" (`vgm_gd3.c`).
- Kotlin (`system/pce/PceVgm.kt`): `PceVgm.export(song)` = lower + `PceVgm.renderer.render(image, maxTicks,
  PceVgmTags(title, author))`; Android registers JNI `ChipAudio.nativePceVgm`, the Deck `DeckPceVgm` (cinterop).
  `maxTicks` = 60 × multispeed × 600 (ten minutes). DDA streams are written one R6 per interrupt, not as VGM
  DAC-stream commands (those cannot express the per-note step). With one sample channel an interrupt costs its wait
  (one byte, `0x70`-`0x7F`, while interrupts are at most 16 samples apart: reload 0 or 1) and `0xB9 06 vv`: 4 bytes,
  about 28 KB/s at 6991 Hz (27964 bytes/s), plus a 3-byte R0 when the tick's writes moved the select. With *k* ≥ 2
  sample channels every R6 also needs its `0xB9 00 c` channel select: 1 + 6*k* bytes per interrupt, about 91 KB/s
  for two channels at 6991 Hz.
- WAV: the generic `chip_wav.c` over the `"pce-huc6280"` ops (no system code).

### Preparing the fixtures and constants

`python3 tools/audio/prepare_pce.py` (pure Python 3) writes `core/src/commonMain/kotlin/org/thortracker/backend/PcePlayerImage.kt`
(every `PC_*` constant of `pce_image.h` without its prefix, `PCP_PITCH_MIN`/`MAX`, `PCM_SEEK_TICKS`, the effect codes,
`LOADER_PROBLEMS` from `pce_image.c`, the GD3 strings) and `app/src/test/cpp/pce_test_song.h` (test images and the
reference player's per-tick and per-interrupt writes, positions, the reference VGM's size, hash and loop), and fails
when `PCP_PERIOD_Q16` or any shared number disagrees with the Python reference. `python3 tools/audio/pce/pce_golden.py`
writes `core/src/jvmTest/resources/pce-golden/*.bin.gz` for `PceGoldenTest`.

### Verification

The native suite, independent traces/VGM, JVM/Linux tests, Android editor/playback/export checks and Game Boy
pixel/UGE parity passed. See [0.26.0 verification](VERIFICATION.md#0260-pc-engine-huc6280) for evidence and limits.
Commands for reproducing the checks:

- `tools/audio/run_native_host_test.sh [dir]` builds and runs `app/src/test/cpp/pce_machine_test.c` (see that file
  and `docs/VERIFICATION.md`). `wav_render_test.c` renders `pce-demo.pcimg` (the demo) and `pce-shortest.pcimg` (one
  row, A-4 held on PSG 1) from `exportSamples`.
- `tools/audio/pce/pce_reference_check.py <dir> [--pitch] [--vgm2wav PATH]`: the reference player against the trace
  and the VGM, the VGM parsed from the format alone, the period table and A-4's pitch computed independently from the
  clock, optionally libvgm's vgm2wav (its HuC6280 core) against the engine's WAV.
- `tools/audio/render_pce_image.sh image out.wav [seconds] [mute=...] [--vgm out.vgm]`.
- Kotlin: `PceGoldenTest` (the lowering against the reference builder), `PceModelTest`, `PceEditorTest`.

## SCC and SCC+: Thor Tracker's own player on a Konami K051649 / K052539 (`"msx-scc"`, `"msx-scc-plus"`)

Roadmap step 7 (wavetable chips). No MSX sound driver is in the loop: Konami's drivers are not free, and the systems are
the sound chip alone, not an MSX (no PSG, no AY+SCC combination). Like the OPL3, songs play through **our own player**
(`scc/scc_player.c`, pure integer C without a chip) whose behaviour is this section; `tools/audio/scc/scc_ref.py`
implements the same specification separately in Python and the host test requires identical register writes in every
tick. Songs export as VGM (the player's register log). This section is the contract of the Kotlin systems and lowering
(`core` `system/scc`, `backend/SccLowering.kt`); the numbers they share with the engine are generated into
`backend/SccPlayerImage.kt` ("Preparing" below). One image layout, player and machine serve both variants; the image
says which chip it is for and each engine id loads only its own.

### The chips (primary documentation and audited emulators)

Sources: Sean Young (BiFi), "Konami SCC Sound Chip" and "Konami Sound Cartridge - SCC+" (msxnet technical pages,
archived at web.archive.org/web/2020/http://bifi.msxnet.org/msxnet/tech/scc.html and `.../soundcartridge`); MAME
`src/devices/sound/k051649.cpp` (BSD-3-Clause, Bryan McPhail; last change commit
`20d4667cdf485066a113f1ef24bae0887cd7134c`, 2025-07-17; file sha256 `003db1a4…7122`); openMSX `src/sound/SCC.cc`
(commit `aecaaf6c89620e0f601bfe7830d23c24ad5eecbc`, sha256 `348b61fe…2d19`), whose comments record the hardware
measurements of Jon De Schrijder (output), Manuel Pazos (test register) and NYYRIKKI (frequency writes); libvgm
`emu/cores/k051649.c` (`c5c03a2c`) and vgmplay-legacy for the VGM side. openMSX is read as documentation of measured
behaviour only; no openMSX code is used.

- **Five channels**, each: 32 signed 8-bit samples (wave RAM, played 0 → 31 and again), a 12-bit period P, a 4-bit
  volume, a key-enable bit. Frequency = 3579545 / (32 · (P + 1)) Hz (BiFi: "fclock is the clock frequency of the
  computer, 3,579,545 Hz", the PSG's formula), so A-4 needs P = 253 (440.40 Hz).
- **K051649 (SCC)**: four wave RAMs; **channels 4 and 5 share the fourth** (BiFi; MAME writes `0x60-0x7F` to both).
  Whatever is written there, both channels play. **K052539 (SCC+, "SCC-I")** in SCC+ mode: five wave RAMs, one per
  channel; otherwise identical (BiFi: "the sound chip itself is identical"; MAME's 052539 waveform handlers).
- **Clock**: the MSX bus clock, 3579545 Hz (3.579545 MHz NTSC colour subcarrier; the same on 50 Hz MSX machines).
- **Counter** (MAME): every clock, a channel whose effective period E > 8 counts `clk + 1`; when it passes E the
  waveform position advances (mod 32) and `clk` = 0; whenever `clk` is 0 the channel latches its output sample
  = (key ? wave[position] : 0) · volume. So volume, key and wave RAM changes are heard at the next step, not at once
  (NYYRIKKI: "change of volume is not implemented immediately"). **E ≤ 8 halts the channel**: position and latch
  freeze (MAME "channel is halted for freq < 9"; openMSX `incr = 0`; NYYRIKKI's sample players stop the counter this
  way).
- **Frequency writes restart the step** (openMSX `count = 0`, MAME `clock = -1` "sample reload pending", libvgm
  "behaviour according to openMSX"; NYYRIKKI: "writing value to frequency causes current byte to be started again"):
  `clk` = −1, so one clock later the latch reloads at the current position with the current volume/key/wave and the
  next step follows E + 1 clocks after that. A pitch rewritten every tick (slides, vibrato, pitch envelopes) therefore
  loses the partial step at each tick boundary: up to one step per tick, which flattens very low notes slightly
  (P near 4095: up to ~7 %). This is the chip, not the player.
- **Output** (Jon De Schrijder's scope measurements, MAME's `>> 4`): per channel (sample · volume) with the low 4 bits
  dropped before the sum (arithmetic shift, so −1 · 1 → −1): −120..119 per channel, five channels −600..595 (plus a
  DC offset the analog stage removes).
- **Test ("deformation") register** (SCC: `98E0h-98FFh`, SCC+: `B8C0h-B8DFh`; "not used by Konami"): bit 5 = a
  frequency write also resets the waveform position to 0 (MAME and openMSX); bit 6 = every wave RAM is read-only
  (writes ignored; reads show the rotating counter); bit 7 = on the SCC, the shared fourth RAM is read-only (ignored by
  the SCC+); bit 0 = "4-bit frequency": the counter uses P >> 8; bit 1 = "8-bit frequency": P & 0xFF (bit 1 wins when
  both are set) — openMSX's model of Manuel Pazos's measurements, applied when a frequency register is written (the
  only emulator implementing bits 0-1; MAME and libvgm ignore them; BiFi's early tests reported different multipliers,
  so treat them as **unverified**); bits 2-4: no known function. Reading the register writes 0xFF to it (the player
  never reads).
- **Power-on** (MAME `device_reset`): periods 0, volumes 15, keys off, test 0; wave RAM content unknown (openMSX: 0xFF
  with random bits). The player's reset batch writes every register, so nothing depends on it.

### Core: our port of MAME's k051649 (`scc/scc_k051649.c`)

`scc_k051649.c/.h` is a C port of MAME's sound logic above (BSD-3-Clause, its notice kept in the file and in
`NOTICE.txt`), extended with the test-register bits 0, 1 and 7-on-SCC+-ignored as documented, an exact **analytic
advance** (no output) and an **event renderer**: a channel's output changes only when its latch reloads, so rendering
walks reload events instead of 3.58 million clocks per second. Both leave bit-identical state, so a silent seek clocks
the chip exactly as audible playback does. API (`scc_k051649.h`):

- `sck_reset(core, plus)`; `sck_write(core, port, reg, val)` with the VGM `0xD2` ports: 0 = wave RAM (SCC: reg
  `0x00-0x7F`, `0x60-0x7F` the shared RAM; ignored on the SCC+), 4 = wave RAM (SCC+: reg `0x00-0x9F`; ignored on the
  SCC), 1 = period (reg 2c = bits 0-7 of channel c, 2c + 1 = bits 8-11; `0x0A`-`0xFF` ignored), 2 = volume (reg c, low
  4 bits), 3 = key enables (bits 0-4), 5 = test register.
- `sck_advance(core, clocks)`: the state after `clocks` clocks, O(1) per channel.
- `sck_render(core, clocks, emit, ctx)`: the same advance, calling `emit(ctx, clock, channel, level)` (clock offset
  0..clocks − 1, in increasing order per channel) whenever a channel's level = latch >> 4 changes.
- State (`SccCore`, plain data, compared with `memcmp` by the tests): test, key, `ram[5][32]` (the SCC uses RAMs 0-3),
  per channel period, effective period, `clk` (−1 = reload pending), position, volume, latch.

Cost: one event per step of a sounding channel (A-4: 14 kHz, 236 per tick), O(1) per halted or keyed-off channel.

### What runs

- **Tick** = 1/60 s / multispeed (header, N = 1..8): the player runs once, its writes reach the core at the tick's first
  clock in the order made, then the core renders 3579545 / (60 · N) clocks (exact integer accumulation: 59659 or 59660
  at N = 1).
- **Resampling**: blip_buf 1.1.0 at clock rate 3579545 Hz to the host rate (its ~15 Hz high-pass removes the SCC's DC).
  Input = the sum of the channels' levels · 48 (±28800 at most); output float = sample / 32768; mono, written to both
  stereo channels.
- **Size**: `SccImage` = 65536 bytes + `ScInfo`; a machine holds an image copy, the core (~300 bytes), the player and
  one blip buffer. Creation allocates once; no tables are built.

### Channels and wave RAMs

Tracker channels `SCC 1`-`SCC 5` = chip channels 0-4, one bank of instruments for all. Wave RAM *k* on the SCC+ belongs
to channel *k*. On the SCC, RAMs 0-2 belong to channels 1-3 and **RAM 3 to whichever of channels 4 and 5 claimed it
last** (the player's rule below): both channels always play its content. The Kotlin side never pretends otherwise: the
song check warns where SCC 4 and SCC 5 overlap with different waves and the Pattern lane headers and inspector show
the row model's shared-wave estimate (`SoundSystem.sharedState`). The check includes overlaps that end inside a row:
it observes keyed state after rows, scheduled cuts and release ends at their event ticks, skipping unchanged ticks.
A delayed note entering at or after the tail's final-0 tick does not overlap it; neither does a note entering when
tick-0 OFF or a terminal 0 has already ended the other voice before the update. The model does not simulate wave
envelope steps: a `+` lane tag and an inspector qualification mark possible changes of wave **or owner** by either
keyed shared voice. That uncertainty survives the stepping voice's key-off (even within the same row) until a later
definite write; a static-wave note entering during its tail is not presented as a definite RAM owner afterwards.

### Image layout (what the Kotlin lowering produces)

`image_size` = 65536 bytes, little-endian, position-independent (every pattern and envelope reference is an offset into
the data region, so any layout that passes `sc_load_image` relocates). Constants: `scc/scc_image.h` (`SC_*`); the
reference builder is `tools/audio/scc/scc_song.py` (`build()`). The loader rejects anything off-contract with a reason
and the byte (`ScInfo.problem`, `problem_offset`); the Kotlin validator mirrors its checks with user-facing messages.

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | `"TTSC"` |
| 4 | 1 | layout: 1 |
| 5 | 1 | variant: 0 = SCC (K051649), 1 = SCC+ (K052539) |
| 6 | 1 | multispeed N, 1..8 (player ticks per 60 Hz frame) |
| 7 | 1 | speed (ticks per row) at the song start, 1..255 |
| 8 | 2 | order count, 1..256 |
| 10 | 2 | loop order (< count) |
| 12 | 1 | instrument count I = highest slot present, 0..64 |
| 13 | 1 | wave count W, 1..64 (waves 1..W) |
| 14 | 2 | data size D: bytes of the data region used, ≤ 59584 |
| 16 | 48 | 0 |
| 64 | 256 | rows of order *o* at 64 + *o*: 1..255, 0 = 256 (Thor Tracker: ≤ 64); entries ≥ count 0 |
| 320 | 2560 | pattern of (order *o*, channel *c*) = u16 offset into the data region at 320 + 2 (5 *o* + *c*); entries ≥ count 0 |
| 2880 | 1024 | instrument slot *n* (1..64) at 2880 + 16 (*n* − 1) |
| 3904 | 2048 | wave *k* (1..64) at 3904 + 32 (*k* − 1): 32 signed bytes; waves above W are 0 |
| 5952 | 59584 | data region: patterns and envelopes, `[0, D)` |

**Instrument** (16 bytes; all zero = no instrument; records above I must be zero):

| Byte | Field |
|---|---|
| 0 | flags: bit 7 defined (1); bit 0 `RESTART` = the waveform restarts at each note (below); bit 1 `PITCH_RELATIVE` = the pitch envelope accumulates; other bits 0 |
| 1 | 0 |
| 2, 4, 6, 8 | u16 offsets into the data region of the volume, arpeggio, pitch and wave envelopes; `0xFFFF` = none (the wave envelope is required) |
| 10 | 6 bytes 0 |

**Envelope** (at its offset, wholly inside `[0, D)`): u16 length L (1..256), u16 loop (0..L − 1, `0xFFFF` none), u16
release (1..L − 1, `0xFFFF` none; only volume envelopes may have one; with both, loop < release), then L value bytes:
volume 0..15; arpeggio s8 −64..63 semitones; pitch s8 −64..63 sixteenths of a semitone; wave 1..W. Instruments may
share an envelope's bytes.

**Patterns**: decoded from their offset for exactly rows(*o*) rows (a run longer than the order's remaining rows is cut
there, so one pattern can serve orders of different lengths); every row read must lie inside `[0, D)`. Row encoding (the
OPL3's):

| Bytes | Meaning |
|---|---|
| `0x80 \| k` | k + 1 empty rows (k 0..127) |
| `m` (0x01..0x13), then data | bit 0: a note byte follows; bit 1: an instrument byte follows; bits 2-4: effect count 0..4; bits 5-6 zero, m ≠ 0 |
| note | 0..95 = absolute note C-0..B-7 (0 = C-0, 57 = A-4 = 440 Hz), `0xFE` = OFF |
| instrument | slot 1..64, defined |
| effects | per effect column in order: code (0..35 = `0`-`9`, `A`-`Z`), parameter |

**What the lowering writes** (the arranged song: parked orders removed) from a `Cell` with up to 4 effect columns: note
`EMPTY` → no note byte, `CUT` → `0xFE`, an absolute note → itself; instrument 0 → no byte, else the slot; effect columns
in column order, blank columns (code 0, parameter 0) omitted; a cell with none of these is an empty row. Load-time
parameter checks: `Bxx` < order count, `Cxx` ≤ 0F, `F00` rejected, `Wxx` 1..W; other effects (`0 1 2 3 4 A D G P S T`)
take any parameter; any other code is rejected.

**Canonical layout** (the Kotlin lowering and the reference builder produce the same bytes; the golden test compares
them): header speed = the song's ticks per row, multispeed, variant, I = the highest defined slot (0 if none), W = 64
(every wave of the song, as two's-complement bytes), rows of order *o* = its pattern's length. The data region is
filled in this order, each new byte string appended at the end and **an identical byte string already placed reused**
(its first offset): (1) the tracks, order 0..count − 1, channel 0..4 within each order: the pattern's rows 0..length − 1
of that channel, empty rows as maximal runs of at most 128 (so a track decodes exactly its rows); (2) the envelopes,
slot 1..64 (defined only), volume, arpeggio, pitch, wave within each: an envelope without values is `0xFFFF` (the wave
envelope always has values), else its 6-byte header (L, loop or `0xFFFF`, release or `0xFFFF`) and values (s8 for
arpeggio and pitch). D = the bytes used. Instrument flags = defined | `RESTART` if `restart` | `PITCH_RELATIVE` if
`pitchRelative`; everything else in the image is 0.

**Should** (not checked): an instrument on the first note of every order (an order is also entered by the loop, the seek
fallback and a live edit that clamps).

`sc_load_image` derives (`ScInfo`) variant, order count, loop, multispeed, speed, rows per order and the speed each
order starts with in sequential straight play (header speed, then the last `Fxx` of each order: rows, then channels,
then columns; jumps ignored).

### Player

Units: **pitch** in 1/64 semitone, 0 = C-0, at most 6143 (just below C-8); a note is pitch 64 · note.
**Period**: o = pitch / 768, f = pitch mod 768, P = min(4095, ((`SC_DIV[f]` + 2^(7+o)) >> (8 + o)) − 1), where
`SC_DIV[f]` = floor(D · 2^(−f/768) · 256 + 0.5) and D = 3579545 / (32 · 440 · 2^(−57/12)) = 6840.9695 (P + 1 of C-0
in Q8). A-4 = P 253 (440.40 Hz, +1.6 cents); every note A-0..G#5 is within 4.2 cents of 12-TET A440, ≤ G#6 within 12.0,
B-7 is +19.2 cents (P 27): the 12-bit divider's resolution, not the table's. Notes below A-0 (P would exceed 4095)
clamp to P 4095 (27.3 Hz); the highest pitch gives P 26, so the player never halts a channel by pitch.
`generated/scc_tables.h` holds `SC_DIV` and the vibrato sine `SC_SINE[i]` = floor(127 sin(2πi/64) + 0.5).

**Writes** are `{port, reg, val}` in the VGM `0xD2` port numbering of the core API (wave port 0 on the SCC, 4 on the
SCC+). Every write goes through a **shadow** of what the chip holds (test, key, ten period bytes, five volumes, five
wave RAMs): a write equal to the shadow is not emitted, except where marked *forced*. A wave write to a RAM the test
register protects (bit 6; on the SCC bit 7 for RAM 3) is never made, so the shadow stays what the chip holds. A tick's
writes form one batch, applied at one instant in the order made (no key-off phase: the SCC samples nothing per write).

**Reset** (start, audition): all state cleared; channels: instrument 0, channel volume 15, no effects, no wave lock;
SCC RAM 3 has no owner; speed = header speed; position (0, 0, tick 0); test value 0. Writes (all *forced*, the shadow
then equals them): test = 0; key = 0; per channel 0..4 period bytes 0 and 1 = 0; volumes 0..4 = 0; every wave RAM
byte = 0 (SCC: port 0 regs `0x00-0x7F`; SCC+: port 4 regs `0x00-0x9F`), in register order. So any VGM player starts
from the same registers whatever its own reset does.

**Tick** (positions are (order, row, tick-in-row); the host's position is the tick about to run): as the OPL3's steps
2 to 5, with five channels:

1. Tick 0 of a row: read every channel's row at (order, row). Then, over channels 0..4 and their effect columns in
   order: `Fxx` sets the speed (the last one wins and applies to this row); `Bxx`/`Dxx` record the next position: `B`
   only → (xx, 0); `D` only → (next order, xx), the next order being order + 1 or, past the last one, the loop order;
   both → (B's order, D's row). Then for each channel 0..4: its cut is cleared; if the row has `Gxx` with xx > 0 (the
   last `G` of the row) its row is delayed to tick xx; otherwise the row runs now. Other ticks: each channel whose
   delayed row is due (tick = xx) runs it, channels in order.
2. Each channel whose cut tick (`Sxx`) equals this tick is keyed off, channels in order.
3. Each channel 0..4 that is active (has keyed a note since reset and its slot is still defined) is updated (below).
4. The wave pass (below), then the key register: bit *c* = channel *c* keyed (shadowed write, port 3).
5. tick + 1; when it reaches the speed: tick 0 and the next row — the recorded jump (row clamped to the target order's
   last row), else row + 1, after the last row of an order the next order, after the last order the loop order.
   **Loop speed**: whenever the next row is row 0 of the loop order the speed becomes `ScInfo.start_speed[loop]`
   (an `Fxx` on that row still applies at its row start), exactly as the OPL3's.

**Running a row** of a channel: if the row has a note 0..95, the arpeggio effect, fine pitch and wave lock are cleared;
an instrument byte sets the channel's instrument; the effect columns run in order (table below); then the note: OFF
keys the channel off; a note with portamento active (`3xx`, xx > 0) while the channel holds a note (keyed and not
released) only sets the glide target (no key-on); any other note keys on — including one during an audible release or
after a finished one.

**Key-on** (note *n*): if the channel's instrument is not a defined slot, nothing happens. Else the channel's note slot
= that instrument; every envelope index = 0, not released, the relative pitch accumulator = 0; base = target = 64 *n*,
slide offset 0 (the slide mode persists), arpeggio step 0, vibrato phase 0; keyed; active; this tick's update writes the
period **forced** (both bytes, so the step restarts at the note), and if the instrument has `RESTART` that write is
wrapped in test = value | 0x20 … test = value (shadowed), so the waveform restarts at position 0 (the chip's bit 5).
The held wave (below) = the note's first wave (its wave lock, else the wave envelope's first value), so a key-off in the
same tick (`S00`) leaves that wave. On the SCC a key-on on channel 4 or 5 is a **wave claim** (below).

**Key-off** (OFF, `Sxx`): if keyed: when the note's volume envelope has a release point and the note is not released,
it is released (volume envelope index = the release point); otherwise the channel is no longer keyed (its key bit
clears: silence at its next step). OFF on a released note therefore cuts it. A release also ends by itself: see
**Release end** under the update.

**Update** (per tick, active channels 0..4 in order): the note slot's record and envelopes are read from the
**current** image (live edits reach sounding notes; an index past an edited envelope's length is first set to its last
value). Values at the current indexes: volume e (15 without an envelope), arpeggio a (0), pitch v (0), wave w; the
wave lock, if set, replaces w. Pitch offset q = 4 v, or with `PITCH_RELATIVE` accumulator = clamp(accumulator + 4 v,
−6144, 6144) and q = accumulator. Pitch = clamp(base + 64 (a + arp) + fine + slide + vibrato + q, 0, 6143), arp = 0,
x, y for step 0, 1, 2 of `0xy`, vibrato = floor(`SC_SINE`[phase] · depth / 16) (arithmetic shift). Writes: period
low byte then high byte of P(pitch) (port 1, regs 2c, 2c + 1; forced at key-on, wrapped as above); volume (port 2, reg
c) = e · cv / 15 (integer division), at least 1 when e > 0 and cv > 0, cv = channel volume / 16 (0..15).
**Release end**: a released note whose volume index is the envelope's last (L − 1) and whose value there (e, before the
channel volume) is 0 is no longer keyed — in this update, before its held wave (so its key bit clears in this tick's
batch together with the volume 0, and this tick's wave step does not claim). Only that final 0, at the index being
played: the index is tested before it advances, so the value before it (the penultimate) still sounds keyed for its
tick; a 0 inside the release, a release ending above 0 and a held note at volume 0 stay keyed. Then the
channel's **held wave**: while the channel is keyed (a released note included) or has a wave lock, it becomes w, and on
the SCC a channel 4 or 5 whose held wave changes makes a **wave claim**; a keyed-off channel without a lock keeps the
wave it held (its wave envelope still advances, but its steps no longer reach a wave RAM), so after its key-off (OFF,
a second OFF, or its release end) only its next key-on or an explicit `Wxx` claims RAM 3 again. Then everything
advances: each envelope index (below); arpeggio step (mod 3) while `0xy` ≠ 0; slide offset ± speed (clamped to
±6144) for `1xx`/`2xx`; base toward target by the speed (no overshoot) for `3xx`; vibrato phase + speed (mod 64) while
speed and depth are set; channel volume (in 1/16 steps, 0..240) + x − y for `Axy`. A released or keyed-off note keeps
updating (its release follows slides and vibrato).

**Envelope index advance**: end = the release point while the note is not released and the envelope has one, else L.
If index + 1 < end: index + 1; else if the envelope loops and the note is not released: the loop point; else it holds
(index = end − 1). So a released note plays from the release point to the end and holds the last value, and a note
held before a release point without a loop sustains the value before it. The volume envelope follows FamiStudio here;
the others do not: FamiStudio's release (`famistudio_ca65.s`, `@jump_to_release_envelope`) moves only an envelope with
a release point and keeps looping the rest, while this player stops every loop (arpeggio, pitch, wave) at the release:
each runs on to its end and holds its last value (the Mega Drive's PSG envelopes follow FamiStudio; `docs/BACKENDS.md`,
open questions).

**Wave pass**: for each wave RAM with an active owner — the SCC+'s RAM *k* and the SCC's RAMs 0-2: channel *k*; the
SCC's RAM 3: the channel among 4 and 5 that made the latest wave claim (in a tick, channel 5's claim comes last, so it
wins a tie; before any claim RAM 3 has no owner) — the 32 bytes of the owner's held wave are read from the current
image and every byte that differs from the shadow is written (index order), unless the test register protects that
RAM. A wave edited live is therefore heard at the next tick; an owner that is no longer active writes nothing. A
keyed-off owner keeps RAM 3 (its held wave, fixed) until the other channel claims it.

**Test register** (`Txx`): the value is written at once (shadowed, port 5) when the row runs. If bits 0-1 changed,
every active channel's period is written forced in this tick's update, so the new frequency mode applies at once.

### Effects (what Kotlin's effect specs declare for `"msx-scc"` and `"msx-scc-plus"`)

One character each (`docs/BACKENDS.md`, "Effect columns"); up to 4 columns per channel. "Note" = applies to the note
keyed on this row only; "now" = from this row on, persisting.

| Effect | Parameter | Meaning |
|---|---|---|
| `0xy` arpeggio | x, y semitones | note: steps note, +x, +y every tick (on a row without a note it changes the sounding note's) |
| `1xx` slide up | 1/64 semitone per tick | now: until `100`/`200`/`300`; a new note restarts the offset at 0 but keeps sliding |
| `2xx` slide down | 1/64 semitone per tick | now: as `1xx`, down |
| `3xx` portamento | 1/64 semitone per tick | now: while active a note on a held (keyed, not released) note glides there without key-on; during or after a release it keys on; `300` ends it |
| `4xy` vibrato | x speed 0-F (64-step cycle), y depth 0-F | now: offset floor(SINE · y / 16) (depth F: ±1.86 semitones); `4x0` off |
| `Axy` volume slide | x up, y down, 1/16 step per tick | now: the channel volume, until `A00` (`A01`: 15 steps in 4 s) |
| `Bxx` jump | order | after this row play order xx row 0 (with `Dxx`: its row) |
| `Cxx` volume | 00-0F | now: channel volume (start 0F), scales the volume envelope |
| `Dxx` break | row | after this row play row xx (clamped) of the next order (`Bxx`'s with both) |
| `Fxx` speed | 01-FF ticks per row | now, global, this row included; the last of the row wins |
| `Gxx` note delay | ticks | the channel's row (all but `B`/`D`/`F`) runs at tick xx; never if xx ≥ the speed |
| `Pxx` fine pitch | 80 = none | xx − 80 in 1/64 semitones (±2 semitones) until the next note |
| `Sxx` note off | tick | key off at tick xx of this row, like an OFF cell at that tick |
| `Txx` test register | bits | now, global: the chip's test register = xx (bit 5 waves restart at period writes, bit 6 all waves frozen, bit 7 SCC: the shared wave frozen; bits 0/1 unverified frequency modes; 2-4 nothing) |
| `Wxx` wave | 01-W | note: the note keyed on this row plays wave xx instead of its instrument's wave envelope; on a row without a note the sounding note switches to xx until its next note. On a keyed-off channel (silent; the song check warns) it still becomes the held wave: the channel's RAM is rewritten, and on the SCC, when xx differs from the wave SCC 4 / SCC 5 held, that channel claims RAM 3 with it (the only claim a keyed-off channel makes) |

No chord effect: the SCC has five fixed voices (`docs/BACKENDS.md`); chords are written across channels. Drums are
instruments with volume, pitch (relative: sweeps) and wave envelopes.

### Position, seek and live edits

- `position()` is the player's (order, row, tick).
- `start(order, row, max_ticks)`: the core and player are reset and the player runs alone from the song start until
  the next tick starts (order, row) (`CHIP_SEEK_FROM_START`), counting *n* ticks; beyond `max_ticks`, from the order's
  row 0 with its start speed (`CHIP_SEEK_FROM_ORDER`, the OPL3's fallback and bounds; a row behind a `Dxx` of the same
  order is `CHIP_ERR_IMAGE`). Then from the reset again: the first *n* − P ticks run the player and apply its writes to
  the core with **`sck_advance`** (no output), **P = `SCM_PREROLL_TICKS` 60 × multispeed**. During those silent
  ticks, `blip_skip_silence` also advances the cleared resampler's fractional sample phase in constant time,
  using the same fixed-point factor and modulo as `blip_end_frame`; no samples or signal history are generated.
  The last P ticks run as in play (`sck_render` into the resampler, output discarded), on **the same sample grid
  as straight play**, not a grid restarted at the pre-roll boundary. A phase error persists through arbitrarily
  long pre-roll and was the cause of the beyond-pre-roll seek mismatch despite matching core state.
  The core's state at the target is **bit-identical to straight play** (phases, latches, wave RAMs); the only
  difference is the resampler's signal history. The unchanged 1 s pre-roll rebuilds the finite impulse tail and
  lets blip_buf's integer high-pass converge to a sub-LSB remainder. The regression requires every output sample
  within 1 LSB (1/32768) of straight play's; with *n* ≤ P the whole way is rendered and must be bit-identical.
  Silent ticks retain arithmetic chip advancement plus constant-time resampler phase advancement; rendered work
  remains bounded by P ticks.
- `apply_image` (live edit at a tick boundary, realtime-safe: a 64 KB copy plus a few row decodes): the player keeps no
  pointer into an image. The OPL3's rules (1)-(3) and (5) hold as written there (clamped position; speed; re-read jump
  and delays; multispeed from the next tick). Rule (4) differs: **sounding notes keep their slot** and read its edited
  envelopes and waves from the next tick (an index past a shortened envelope moves to its last value; a slot that is no
  longer defined cuts the note: key bit cleared, channel inactive); the wave RAM follows its owner's edited wave at the
  next tick. Edited cells are heard from the next row processed.

### Audition

`audition(channel 0..4, note 0..95, slot 1..64, fine −128..127)`: the (spare) machine is reset and the player keys on
the note on that channel with the slot's instrument, no wave lock, `fine` added like `Pxx`; ticks then only update the
channel (envelopes, wave pass, key register), no rows. An undefined slot or arguments outside these ranges are
`CHIP_ERR_IMAGE`. `release()`: 0 before any tick (cut), when the channel is no longer keyed, or when the note's
volume envelope has no release point (the key-off clears the key bit: silent now); else the player's key-off from the
next tick, returning 1, and `tick` returns 0 once the channel is no longer keyed (a release ending at volume 0 keys it
off in the tick of that 0) or the released volume envelope holds its last value and that value (after the channel
volume) is 0, or after 10 s (`SCM_RELEASE_SECONDS`). While the song plays, the host mutes the channel
with `mute`.

### Mute and ops behaviour

`mute(channel 0-4)` leaves the channel out of the resampler input only (its level steps to 0 at the next tick's first
clock; unmuting steps it back to the channel's current level): the player, the core and the VGM are untouched, so
unmuting resumes mid-note. On the SCC a muted channel 4 still plays the shared RAM's wave into nothing; channel 5 is
unaffected. `start`/`audition` clear mutes.

| Op | Behaviour |
|---|---|
| `load_image` | copy, then `sc_load_image`, then the variant must be the ops' (`"msx-scc"` 0, `"msx-scc-plus"` 1); `image_storage` = `sizeof(SccImage)` |
| `start(order, row)` | above; `seek_mode` from start or order |
| `apply_image` | above; ignored while auditioning or for an image of the other variant |
| `audition`, `release` | above |
| `mute(channel 0-4)` | above |
| `position` | the player's |
| `tick` | one tick: 800 frames at 48 kHz (multispeed N: 800 / N), ≤ 1601 at 96 kHz; 0 after a released audition ended |

**Kotlin** (`PlaybackEngine` for both ids): `imageSize` 65536, `seekTicks` 12000, `note(n)` = n (absolute 0..95),
`fine` = the system's fine pitch in 1/64 semitones (0 by default), `ticksPerSecond` = 60 × multispeed.

### VGM export

`sc_vgm_render` (`scc/scc_vgm.c`) runs the player headless and logs its writes as a VGM 1.71 for one K051649 (SCC) or
K052539 (SCC+); `tools/audio/scc/scc_ref.py IMAGE --vgm OUT` writes the same bytes from the Python reference. File:

- Header (256 bytes, others 0): `"Vgm "`, 0x04 = file size − 4, 0x08 = 0x171, 0x18 = total samples (44.1 kHz),
  0x1C = loop offset − 0x1C and 0x20 = loop samples (both 0 without a loop), 0x24 = 60, 0x34 = 0xCC (data at 0x100),
  **0x9C = 1789773** (SCC) or **0x8000_0000 | 1789773** (SCC+: bit 31 = K052539). The VGM convention halves the MSX
  clock: the VGM 1.71 specification gives 1789773 as the typical K051649 clock, and its players (libvgm, VGMPlay, from
  older MAME) play f = clock / (16 (P + 1)) = 3579546 / (32 (P + 1)), the chip's pitch. `sc_vgm_render` writes no GD3
  tag; the hosts' entry point appends one.
- Data: the reset batch; then per tick: at the first arrival at (loop order, row 0, tick 0) the loop point and a dump
  of the whole register state from the shadow: test = shadow & 0x03 (frequency mode kept, protection and restart off),
  every wave RAM byte, then periods (low, high per channel), volumes, key, test = shadow — so each pass starts from the
  same registers although the writes are diffed; then the tick's writes as `0xD2 pp aa dd`; then the tick's wait
  (44100 / (60 · N) samples by exact accumulation: 735 = `0x62` at N = 1; `0x70`-`0x7F` for 1-16, `0x61 nn nn`
  otherwise). The song ends at the second arrival at the loop point (or at the tick limit, then without a loop), then
  `0x66`. The dump's period writes restart steps at the loop point, so a VGM player's phase there may differ from the
  engine's by under one step (inaudible).
- VGM players do not model the test register's bits 0-1 (libvgm, VGMPlay); songs using them play differently there.
- **Hosts' entry point** (`scc/scc_export.h`, self-contained for cinterop): `sc_export_vgm(image, 65536, max_ticks,
  title, author, date, &size, &problem)` validates the image, renders the file above (measured, then rendered into one
  allocation) and appends a **GD3 1.00** tag (`vgm_gd3.h`): track = title, system "MSX SCC (Thor Tracker)" or "MSX
  SCC+ (Thor Tracker)", author, date (`"yyyy/mm/dd"`, empty = today), converter "Thor Tracker"; free with
  `sc_export_free`. Control thread only.
- **Kotlin** (`core` `system/scc/SccVgm.kt`): `SccVgm.export(song)` lowers the song and calls
  `SccVgm.renderer.render(image, maxTicks, SccVgmTags(title, author, date))`, registered at startup by Android's
  `ChipAudio` (JNI `ChipAudio.nativeSccVgm(image, maxTicks, title, author, date)`, UTF-8 byte arrays) and the Deck's
  `main` (`DeckSccVgm` over cinterop). WAV export needs nothing system-specific (`chip_wav.c` through the ops).

### Preparing the tables and fixtures

`python3 tools/audio/prepare_scc.py` (pure Python 3) writes `app/src/main/cpp/generated/scc_tables.h` (`SC_DIV`,
`SC_SINE` from `scc_ref.py`'s table functions), `core/src/commonMain/kotlin/org/thortracker/backend/SccPlayerImage.kt`
(the layout, limits, effect codes, period table, clock, seek and GD3 constants parsed from `scc_image.h`,
`scc_player.h`, `scc_machine.h` and `scc_vgm.h`, a `period(pitch)` function and `LOADER_PROBLEMS`: every reason
`sc_load_image` reports) and `app/src/test/cpp/scc_test_song.h` (the host test's fixtures, below).
`python3 tools/audio/scc/scc_golden.py` writes the Kotlin golden images `core/src/jvmTest/resources/scc-golden/*.bin.gz`.
Rerun both after changing this specification, the engine headers, the reference player or the test song.

`SccPlayerImage` (Kotlin, generated; the names the Kotlin code uses): `ENGINE_ID_SCC` = `"msx-scc"`,
`ENGINE_ID_PLUS` = `"msx-scc-plus"`, `IMAGE_SIZE`, `MAGIC`, `LAYOUT`, `H_MAGIC`, `H_LAYOUT`, `H_VARIANT`,
`H_MULTISPEED`, `H_SPEED`, `H_ORDERS`, `H_LOOP`, `H_INSTRUMENTS`, `H_WAVES`, `H_DATA_SIZE`, `HEADER_SIZE`,
`VARIANT_SCC`, `VARIANT_PLUS`, `OFF_ROWS`, `OFF_ORDERS`, `OFF_INSTRUMENTS`, `INSTRUMENT_SIZE`, `OFF_WAVES`,
`WAVE_BYTES`, `OFF_DATA`, `DATA_CAPACITY`, `I_FLAGS`, `I_VOLUME`, `I_ARPEGGIO`, `I_PITCH`, `I_WAVE`, `FLAG_DEFINED`,
`FLAG_RESTART`, `FLAG_PITCH_RELATIVE`, `ENV_NONE`, `ENV_HEADER`, `MAX_ENVELOPE`, `RUN`, `MAX_RUN`, `MASK_NOTE`,
`MASK_INSTRUMENT`, `MASK_EFFECT_SHIFT`, `NOTE_OFF`, `NOTES`, `CHANNELS`, `MAX_ORDERS`, `MAX_INSTRUMENTS`, `MAX_WAVES`,
`MAX_EFFECTS`, `MAX_MULTISPEED`, `PITCH_UNITS_PER_SEMITONE`, `PITCH_MAX`, `PERIOD_MAX`, `PERIOD_HALT`, `EFFECT_LETTERS`,
`FX_ARPEGGIO`, `FX_SLIDE_UP`, `FX_SLIDE_DOWN`, `FX_PORTAMENTO`, `FX_VIBRATO`, `FX_VOLUME_SLIDE`, `FX_JUMP`,
`FX_VOLUME`, `FX_BREAK`, `FX_SPEED`, `FX_DELAY`, `FX_FINE`, `FX_CUT`, `FX_TEST`, `FX_WAVE`, `CLOCK`, `VGM_CLOCK`,
`SEEK_TICKS`, `PREROLL_TICKS`, `GD3_SYSTEM_SCC`, `GD3_SYSTEM_PLUS`, `GD3_CONVERTER`, `DIV` (768 entries), `DIV_SHIFT`,
`fun period(pitch: Int): Int` (the formula above), `fun hz(period: Int): Double` (3579545 / (32 (P + 1))) and
`LOADER_PROBLEMS`.

`scc_test_song.h` (C, generated; images are stored up to their last non-zero byte, the test zero-pads them to 65536):
`SCC_REF_TICKS` 1500; images `scc_song_image` (the test song on the SCC), `scc_plus_image` (the same song as SCC+),
`scc_moved_image` (the song laid out anew: patterns and envelopes reversed behind a 37-byte gap), `scc_edited_image` (a
note edit), `scc_tempo_image` (an `Fxx` edit), `scc_short_image` (cut to two orders), `scc_x2_image` (multispeed 2),
`scc_wave_edit_image` (one wave's bytes changed); for `song`, `plus`, `edited`, `tempo`: the reference player's batches
(the reset batch, then one per tick) as `scc_ref_NAME_offsets[SCC_REF_TICKS + 2]` (start of batch *i* in the flat
arrays; last = total), `scc_ref_NAME_ports[]`, `scc_ref_NAME_regs[]`, `scc_ref_NAME_vals[]` (unsigned char) and
`scc_ref_NAME_pos[3 · (SCC_REF_TICKS + 1)]` (order, row, tick before each batch; −1 for the reset batch); VGM checks
`SCC_REF_VGM_SIZE`, `SCC_REF_VGM_FNV` (FNV-1a 64 of the file), `SCC_REF_VGM_TICKS`, `SCC_REF_VGM_LOOP_OFFSET`,
`SCC_REF_VGM_LOOP_SAMPLES`, `SCC_REF_VGM_SAMPLES` and the same six `SCC_PLUS_REF_VGM_*` for the SCC+ image; corruption
offsets `SCC_OFFSET_MASK`, `SCC_OFFSET_NOTE`, `SCC_OFFSET_INST`, `SCC_OFFSET_EFFECT` (a row's mask, note, instrument
and first effect code bytes, absolute image offsets), `SCC_OFFSET_PARAM_<X>` for the parameter byte of effect X in
`B C F W`, `SCC_OFFSET_ENV_VOLUME` (a volume envelope with a release point: its header), `SCC_OFFSET_ENV_WAVE` (the
first value of a wave envelope) and `SCC_OFFSET_INSTRUMENT` (slot 1's record).

The test song (`tools/audio/scc/scc_song.py`, 4 orders of 16/32/16/24 rows, `B01` back to the loop order 1): every
effect in several columns, OFF cells, delays and cuts, empty runs, patterns used twice; instruments with looping and
releasing volume envelopes, absolute and relative pitch envelopes, arpeggio envelopes, multi-wave envelopes and
`RESTART`; SCC 4 and SCC 5 claiming the shared RAM alternately with different waves; `T20` (restart at period writes),
`T40` (all waves frozen across a wave change, then `T00`), `T01`/`T02` (frequency modes, briefly); speed changes, a break
to order 3 row 2, order 3 ending at speed 5 and jumping back to the loop order, which has no `Fxx`.

### Verification

- `tools/audio/run_native_host_test.sh [dir]` builds and runs `app/src/test/cpp/scc_machine_test.c`: core unit checks
  (the output formula, halting at E ≤ 8, the step restart one clock after a period write, volume/key/wave changes heard
  at the next step, test bits 5/6/7 on both chips and 0/1, shared RAM 3 on the SCC and five RAMs on the SCC+, writes to
  the wrong wave port ignored, `sck_advance` ≡ `sck_render` state after random write streams); every single-byte
  corruption rejected with its reason; **the machine's writes equal the Python reference player's in all 1500 ticks**
  for `song`, `plus`, `edited`, `tempo`, with identical positions; the reset batch; 30 s non-silent in every second, no
  clipping, bit-identical on a second start; loop passes with identical positions; seeks (several
  targets beyond and within the pre-roll): position, writes equal to straight play for 200 ticks, **core state
  `memcmp`-equal to straight play's at the target**, output within 1/32768 (bit-identical within the pre-roll); the
  order fallback; live edits: the same image and `moved` alternating before every tick write the reference, a note edit
  applied before its row equals straight play of the edited song, a tempo edit, the cut song clamping, x2 halving the
  tick, `wave_edit` applied while that wave sounds rewrites the RAM at the next tick, an instrument deleted under a
  sounding note cuts it; audition: **A-4 measured 440.40 Hz** (sine wave, P 253), `fine` +64 = A#4 (P 239, 466.09 Hz),
  release with and without a release point, held notes, argument limits; mute silences and leaves the writes equal to
  the reference; the SCC's shared wave: channel 5's claim changes what channel 4 plays (core RAM 3 = channel 5's wave,
  channel 4's output follows it) while the SCC+ keeps both; **keyed-off notes** (constructed images, both chips): SCC 4
  with a looping two-wave envelope cut by OFF, and a release-tail instrument keyed off by a second OFF while its tail
  still sounds, keep their last wave in RAM 3 while their envelopes advance, so SCC 5's organ entering afterwards keeps
  RAM 3 (SCC) or RAM 4 (SCC+) and is heard; the sounding released note still claims; a `W04` on the keyed-off SCC 4
  still claims RAM 3 on the SCC and leaves the SCC+'s RAM 4 alone; **held versus released notes**
  (`test_held_release`, constructed images, both chips): a release's final 0 clears the key bit in its own tick with
  volume 0 while the penultimate tick still sounds keyed, a 0 inside a release, a release ending above 0 and held
  silence stay keyed, `3xx` glides into a held silent note but a note during an audible release or after a finished
  one keys on (envelope restarted, no glide), and a finished release's wave steps never take RAM 3 back from SCC 5
  (not even in the final tick) while a later `W04` still claims; **VGM bytes equal the reference player's** for both
  variants (header clock and bit 31); `sc_export_vgm` with GD3 decoded back; the ops tables as the hosts drive them,
  including the variant check. Evidence in the directory: `scc_song.wav`, `scc_plus_song.wav` (30 s), `scc_tone.wav`
  (A-4 + release), `scc_trace.txt` (per tick: position, then the writes `port:reg=val`), `scc_chip_trace.txt` (a
  scripted register sequence: `W clock port reg val` lines and the core's `E clock channel level` events),
  `scc_song.vgm`, `scc_plus_song.vgm`, `scc_song.bin`, `scc_plus_song.bin`.
- `app/src/test/cpp/wav_render_test.c`: the demo images of both variants and the shortest valid songs
  (`scc-shortest.sccimg`, `scc-plus-shortest.sccimg`: one order of one row at speed 1, A-4 held on SCC 1) through
  `chip_wav.c`.
- `tools/audio/scc_reference_check.py <dir> [--vgm2wav PATH]` (developer evidence; the audio part needs numpy): the
  reference player reproduces `scc_trace.txt`; an independent per-clock model of the chip (MAME's loop, one clock at a
  time) reproduces every event of `scc_chip_trace.txt`; the VGMs parse from the format alone (header, clock and bit 31,
  waits summing to the header's samples, the loop point at the register dump, the `0xD2` stream equal to the trace);
  **pitch**: `scc_tone.wav` measured by FFT against 3579545 / (32 · 254) = 440.397 Hz, and with `--vgm2wav` (libvgm,
  an independent player) the exported VGM rendered and measured the same, which also proves the header clock
  convention; envelope/spectrum correlation of the libvgm render against `scc_song.wav`.
- `tools/audio/render_scc_image.sh image out.wav [seconds] [mute=45] [--vgm out.vgm] [title=..] [author=..]
  [date=..]` renders a lowered image through the ops table as the hosts do, and its VGM through `sc_export_vgm`.
- Not done: listening on hardware, a comparison with a real SCC or SCC+ cartridge.

Verified on 2026-11-09: generated tables/goldens and sample exports, 690 JVM / 14 Linux / six Android-host tests,
606 SCC functional checks and 1,841 WAV checks, independent player/chip/VGM checks, signed Android and desktop builds,
both systems exercised on Nova/DMG/Konkr and the actual SDL frontend. Silent seek now retains blip's fractional
sample phase; the previously failing targets are sample-identical without lengthening preroll or relaxing tolerance.
Thread-CPU timing remains a reported benchmark, not a worst/mean correctness gate: even hosted CI's 52.3 µs worst
tick exceeded three times its 12.7 µs mean despite remaining far inside the audio budget.
See `docs/VERIFICATION.md` for timings, exports and limits. The optional libvgm comparison was not run.

## OPM: Thor Tracker's own player on a YM2151 (`"ym2151-opm"`)

**Status: integrated for 0.29.0**, with native/reference, Android and SDL evidence recorded
in `docs/VERIFICATION.md`.
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
  pair is scaled by **7/16 before** blip_buf 1.1.0 resamples 62500 Hz to the host rate (its ~15 Hz high-pass).
  This preserves the DAC's own saturation while leaving headroom for resampling and DC removal before blip's
  int16 output stage; float = sample / 32768. The unscaled test song clipped at 44.1, 48 and 96 kHz; the corrected
  native regression passes at 8, 44.1, 48 and 96 kHz (48 kHz: peak 0.4452, RMS 0.0830).
  Muted channels are left out of the sum before the DAC round trip, but every channel is still computed
  (one `fm_engine::output` call per channel: ymfm advances a channel's feedback history only while computing its
  output); registers and the player are untouched.
- **Chip reset**: `start` and `audition` rebuild the chip in place (`omc_reset`: ymfm's `reset()` leaves the envelope,
  LFO and noise counters running, and a reused machine must sound like a fresh one), as the YM2612's machine does;
  ymfm allocates its channel and operator objects then. Both run off the audio thread on machines the callback is not
  using: Android `Engine::play`/`Engine::audition` (`audio_engine.cpp`) on the back machine slots, called through JNI
  from `ChipAudio`'s single "thor-audio-control" executor thread; the Deck's `da_play`/`da_audition`
  (`deck/native/deck_audio.c`, main thread) on spare machines outside the stream lock, swapped in afterwards; the WAV
  export's own machine (`chip_wav_open`). The audio callbacks (`Engine::render`, `feed`) call only `tick`,
  `apply_image`, `mute`, `position` and (Android) `release`, none of which allocates.
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
- `start(order, row, max_ticks)`: **fully clocked**. The player alone first counts the ticks straight play needs from
  the song start until the next tick starts (order, row) (≤ `max_ticks`; no chip). Then the chip is
  rebuilt, the player reset, the reset batch written and every one of those ticks runs exactly as in play — the
  player's writes with the key-off phase, every native chip sample, the resampler's frames read and discarded — so
  registers, envelopes, operator phases and feedback, the LFO, the noise generator, the timers (CSM) and blip_buf's
  history at the target are straight play's: the sound from the target is **bit-identical** to straight play's from
  that tick (`CHIP_SEEK_FROM_START`). There is no register restore and no pre-roll window. Beyond `max_ticks` (Bxx
  loops, rows behind a jump, far targets): the player is placed at the order's row 0 with the speed straight play
  starts that order with and the order's earlier rows replay with the chip clocked the same way (`CHIP_SEEK_FROM_ORDER`,
  its own bound 256 × 256 ticks; the state of play started at that order: instruments, volumes and shared values set
  in earlier orders are not there, as on every system); a row behind a `Dxx` of the same order is unreachable
  (`CHIP_ERR_IMAGE`). Cost: one straight-play tick of chip time per tick sought (not yet measured; ymfm's YM2612 costs
  235 µs per 60 Hz tick on the dev PC), so `OMM_SEEK_TICKS` = Kotlin's `seekTicks` = **3600** (one minute at
  multispeed 1) rather than the OPL3's 12000; tune it from the bench.
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

`mute(channel 0-7)` leaves the channel out of the output sum while ymfm still computes it (envelopes, phases and
feedback run as unmuted, so unmuting is indistinguishable from never muting); the player's writes and the VGM are
unchanged. `start`/`audition` clear mutes.

| Op | Behaviour |
|---|---|
| `load_image` | copy, then `om_load_image`; `image_storage` = `sizeof(OpmImage)` |
| `start`, `apply_image`, `audition`, `release`, `mute`, `position` | above |
| `tick` | one tick: 800 frames at 48 kHz (multispeed N: 800 / N), ≤ 1601 at 96 kHz; 0 after a released audition ended |

**Kotlin** (`PlaybackEngine` for `"ym2151-opm"`): `imageSize` 65536, `seekTicks` 3600 (`OMM_SEEK_TICKS`), `note(n)` = n, `fine` = the
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
  channels and *c* for the others (*c* = 0..7); the key-off phase writes; if there were any, or the dump keyed a
  channel off, a one-sample wait (`0x70`); the other writes; the rest of the tick's wait (44100 / (60 · N) by exact
  accumulation, less the one-sample wait; `0x62` for 735, `0x70`-`0x7F` for 1-16, `0x61 nn nn` otherwise). The dump's
  key-offs belong to the loop tick's key-off phase: on later passes a channel the dump keys off may still sound from
  the song's end, and the chip samples the key state once per sample, so without the gap a key-on on the loop row
  would not retrigger it. Writes are `0x54 reg val`. The song ends at the second arrival at the loop point (or the
  tick limit, then without a loop), then `0x66`.
- Hosts' entry point (`opm/opm_export.h`): `om_export_vgm(image, 65536, max_ticks, title, author, date, &size,
  &problem)`, as `o3_export_vgm` with GD3 system "OPM (Thor Tracker)", converter "Thor Tracker" (`vgm_gd3.c`).
- Kotlin (`system/opm/OpmVgm.kt`): `OpmVgm.export(song)` = lower + `OpmVgm.renderer.render(image, maxTicks,
  OpmVgmTags(title, author))`; Android registers JNI `ChipAudio.nativeOpmVgm`, the Deck `DeckOpmVgm` (cinterop).
  `maxTicks` = 60 × multispeed × 600 (ten minutes).
- WAV: the generic `chip_wav.c` over the `"ym2151-opm"` ops (no system code).
- VGM players' CSM: the VGM holds the `$10`/`$11`/`$14` writes only; whether and how long a player keys on at timer A
  overflows depends on its YM2151 core (stock ymfm, also inside MAME, keeps the CSM key-on until its next register
  write re-prepares the operators, so it may sound different from the engine). The independent MAME/Nuked
  looped-song comparisons are recorded in `docs/VERIFICATION.md`; they do not establish CSM equivalence.

### Preparing the fixtures and constants

`python3 tools/audio/prepare_opm.py` (pure Python 3) writes `core/src/commonMain/kotlin/org/thortracker/backend/OpmPlayerImage.kt`
(layout, limits, effect codes, carriers, pitch constants, seek and GD3 constants parsed from `opm_image.h`,
`opm_player.h`, `opm_machine.h` and `opm_vgm.c`, and `LOADER_PROBLEMS` from `opm_image.c`) and
`app/src/test/cpp/opm_test_song.h` (test images: the song, relaid, a note edit, a tempo edit, a cut song, x2
multispeed, a header-LFO edit; the reference player's per-tick writes, key-off phase lengths and positions for 1500
ticks; the reference VGM's size, FNV-1a hash and loop; byte offsets for corruption checks). `python3
tools/audio/opm/opm_golden.py` writes `core/src/jvmTest/resources/opm-golden/*.bin.gz` for `OpmGoldenTest`. No
generated table header: the vibrato sine is written out in both implementations.

### Verification

- `tools/audio/run_native_host_test.sh [dir]` builds and runs `app/src/test/cpp/opm_machine_test.c`: key code mapping
  for every note, A-4 measured on a sine instrument at 439.88 Hz (± 0.5 Hz), every loader reason by single-byte
  corruption, the machine's writes against the Python reference for 1500 ticks with positions, loop passes against the
  VGM loop, seeks landing with straight play's writes and **bit-identical sound** (every target, compared over 1 s; the
  order fallback deterministic), mute then unmute equal to never muting, live edits (re-laid image, note and tempo edits, cut song, x2,
  header LFO edit), audition (pitch, `fine`, release, noise only on FM 8, CSM off), mute (silence, writes unchanged),
  LFO pitch modulation measured (PMD/PMS vibrato depth), noise on FM 8 (spectral flatness), CSM (retrigger rate =
  62500 / (1024 − NA) Hz measured from envelope restarts), the VGM bytes against the reference, the exported VGM's
  loop played twice on ymfm (a held, decayed note on the loop row attacks again on the second pass; the waits keep
  735 samples per tick) and the export entry
  point's GD3 tag. Evidence: `opm_song.wav`, `opm_tone.wav`, `opm_trace.txt`, `opm_song.vgm`, `opm_song.bin`.
- `wav_render_test.c` renders `opm-demo.omimg` (the demo) and `opm-shortest.omimg` (one row, A-4 held on FM 1) from
  `exportSamples`.
- `tools/audio/opm/opm_reference_check.py <dir> [--vgm2wav PATH] [--pitch]`: the reference player against the trace
  and the VGM, the VGM parsed from the format alone (header, waits sum, loop, command stream), the 4 MHz pitch table
  derived from ymfm's phase-step table; optionally libvgm's vgm2wav against the engine's WAV.
- `tools/audio/render_opm_image.sh image out.wav [seconds] [mute=...] [--vgm out.vgm]`.
- Kotlin: `OpmGoldenTest` (the lowering against the reference builder), `OpmModelTest`, `OpmEditorTest`.
- Executed: 261 focused native checks, 1,896 WAV-export checks, 739 JVM / 19 Linux / six Android-host tests,
  the clean 21-test Android suite, Nova/DMG/Konkr activity exercises, SDL playback and all 32 unchanged Game Boy
  renders. Device timings, export evidence and comparison limits belong in `docs/VERIFICATION.md`.
- Not done: human listening or a real YM2151 / X68000 measurement.

## OPN and OPNA: Thor Tracker's own player on ymfm's YM2203 / YM2608 (`"ym2203-opn"`, `"ym2608-opna"`)

**Integration verified:** 1,806 native checks, 420 independent Python checks, 2,006 WAV checks,
783 JVM / 19 Linux / six Android-host tests, 21 permanent Android tests and 32 unchanged Game Boy
renders. Nova/DMG/Konkr activity smokes, real document pickers, preserved EPSM firmware, desktop
playback and artifact privacy scans pass. Human listening and physical-board comparison remain
unperformed; `docs/VERIFICATION.md` records the measured costs, isolated Nova underruns and limits.

### Hardware, clocks and sources

- **Chips**: Yamaha YM2203 (OPN: three 4-operator FM channels, mono; the SSG: three square channels sharing one noise
  generator and one envelope generator) and YM2608 (OPNA: six stereo FM channels with the LFO; the same SSG; the
  rhythm unit's six ADPCM-A drums from an on-chip ROM; one ADPCM-B channel reading external memory). Emulated by ymfm
  (`ym2203`, `ym2608`; the pin of the table above, unmodified).
- **Clocks** (the PC-88 / PC-98 sound clocks): YM2203 **3 993 600 Hz**, YM2608 **7 987 200 Hz**. Evidence: MAME's
  `src/mame/nec/pc8801.cpp` (`PC8801FH_OSC3` = 31.9488 MHz, `MASTER_CLOCK` = OSC3 / 8 for the YM2203, × 2 for the
  YM2608), `src/devices/bus/pc98_cbus/pc9801_26.cpp` (YM2203 at 15.9744 MHz / 4, "divider not verified") and
  `pc9801_86.cpp` (YM2608 at 7.987 MHz). These are an emulator's board descriptions, not datasheets or a measured
  board; no hardware measurement backs them here.
- **Rates**: at the power-on prescaler (FM ÷6, SSG ÷4) one FM sample takes 72 master clocks on the YM2203 and 144 on
  the YM2608: **55 466.67 Hz on both** ("native samples"). The effective AY-compatible SSG clock is **1 996 800 Hz**
  on both, distinct from the **249 600 Hz counter-update rate** (YM2203 fM/16, YM2608 fM/32). Each tone period
  toggles the output; two toggles complete a cycle, giving **124 800 / TP Hz** (YM2203 fM/(32 TP), YM2608 fM/(64 TP)).
  The wrapper clocks nine SSG updates per two native samples. This agrees with
  [upstream ymfm](https://github.com/aaronsgiles/ymfm/blob/main/src/ymfm_opn.h) and the independent
  [MAME-derived OPN prescaler](https://github.com/ValleyBell/libvgm/blob/master/emu/cores/fmopn.c)
  (`SSG clock = master × 2 / (4 × pre_divider)`, with pre_divider 1 for YM2203 and 2 for YM2608).
  The [YM2608 application manual §3-1](https://manualmachine.com/yamaha/ym2608/5410883-user-manual/#35) gives the
  latter tone formula explicitly; no conflicting YM2203 manual passage or original-hardware measurement is established here.
- **ADPCM-B memory**: 256 KiB, the size of the PC-9801-86's and Sound Board II's DRAM as commonly documented (MAME
  maps 2 MiB and marks the size unconfirmed); the chip runs in 8-bit DRAM mode (`$101` bit 1), so start/end registers
  count 32 bytes.

### What runs

- **Player** (`opn/opn_player.c`, `opn_player.h`): Thor Tracker's own, a port of the YM2612 player to the OPN family.
  It sequences an image and produces register writes per tick; the machine sends them to ymfm, the VGM export logs
  them, and seeking runs the player alone to count ticks. No allocation; the only floating point is a 768-entry
  2^(i/768) table built once (`opn_tables_init`, `pthread_once`), everything else is 64-bit integer.
- **Chip** (`opn/opn_ymfm.cpp`, `opn_ymfm.h`): ymfm's `ym2203` / `ym2608` subclassed so one native sample is one FM
  clock with every part's output kept apart (for mutes): the FM computed channel by channel (every channel the chip
  computes, so a muted channel's feedback history advances exactly as unmuted; only the sum leaves it out), the SSG
  resampled 9:2 per channel exactly as ymfm's `resample_2_9` (4.5 SSG samples per FM sample at prescaler 6), the
  YM2608's ADPCM-A clocked on the envelope cycle and ADPCM-B every sample as `ym2608::clock_fm_and_adpcm`. Timers:
  ymfm schedules timer A/B through its interface; the wrapper counts master clocks (72 / 144 per sample) and delivers
  expiries, a reload counting from the expired deadline, so CSM keys FM 3 at exactly timer A's rate. Reset restores a
  power-on snapshot (ymfm save/restore, no allocation). External memory: ADPCM-A reads the installed rhythm ROM
  (latched at a rhythm key-on), ADPCM-B the machine's copy of the image's sample memory.
- **Machine** (`opn/opn_machine.c`): player + chip + blip_buf (native rate → host rate, stereo). One tick = 16 ×
  (256 − TB) native samples; the key-off phase reaches the chip one native sample before the rest of the tick.
- **Ops** (`opn/opn_chip.c`): `opn_chip_ops` (`"ym2203-opn"`, images of 65 536 bytes) and `opna_chip_ops`
  (`"ym2608-opna"`, 327 680 bytes); `image_storage` is `sizeof(OpnImage)` for both. Images of the other chip are
  refused.

### Channels and parts

| Chip | Channels |
|---|---|
| YM2203 | 0-2 FM 1-3, 3-5 SSG 1-3 |
| YM2608 | 0-5 FM 1-6 (4-6 on port 1), 6-8 SSG 1-3, 9-14 Kick, Snare, Cymbal, Hi-hat, Tom, Rim (register `$10` bits 0-5), 15 ADPCM |

A channel plays sounds of its own part only (FM voice, SSG sound, rhythm sound, ADPCM sound); the loader refuses
other cells. FM 3 is the channel with the special and CSM modes.

### Image layout (what the Kotlin lowering produces)

`opn/opn_image.h` is the contract (Kotlin mirrors it by hand in `OpnPlayerImage.kt`; there is no generator). One
fixed-size image: 64-byte header (`"TTOP"`, layout 1, chip, speed, `$22`, orders, loop, instrument count, FM 3 mode,
data size, timer A, timer B, rhythm level, sample count, ADPCM-B bytes used), rows per order (256 × u8), the order
table (256 orders × 16 channels × u16 data offsets), 64 instrument records of 48 bytes (flags = defined | kind; FM:
algorithm, feedback, `$B4` AMS/FMS, four × seven operator registers, four s16 FM 3 operator pitch offsets; SSG: pitch
mode, hardware envelope shape / auto / octave / manual period, five envelope offsets; rhythm: level, pan; ADPCM:
pan, level, sample, root note, loop; FM, SSG and ADPCM: the drum sweep), 64 ADPCM-B sample entries (start, end,
rate), the data region (patterns and SSG envelopes, 52 928 bytes) and, on the YM2608, the 256 KiB ADPCM-B memory.
Patterns use the Mega Drive's row encoding (runs, mask byte, absolute notes 0..95, `$FE` off, up to four effect
columns). `opn_load_image` checks every byte the player can reach: header ranges (TB 183..250, NA ≤ 1023, `$11` ≤ 63,
YM2203: LFO, rhythm level and samples 0), records per kind, envelopes, samples (32-byte aligned, ascending, inside the
used memory, rate 1000..55 466), unused bytes 0 (including the ADPCM-B tail), every pattern decoding to exactly its
rows, notes (SSG ≥ B-0), kinds per channel, and each effect against its channel and chip.

### Player

- **Pitch** in 1/64 semitones (`u`, 0 = C-0; f(u) = 13.75 Hz × 2^((u + 192) / 768), so A-4 = 3648 = 440 Hz): note ×
  64 + arpeggio × 64 + `Pxx` fine + slide + vibrato (`(sine × depth) >> 4`, the Mega Drive's 64-step table) + sweep,
  plus the SSG's arpeggio (× 64) and pitch (× 4) envelopes; clamped 0..6143.
  - FM: block = u / 768, F-number = round(f × 2^21 / (55 466.67 × 2^block)) (618..1235: the standard octave
    split, the same on both chips because their FM rates are equal).
  - SSG: TP = round(124 800 / f), 1..4095 (the lowest playable note is B-0).
  - ADPCM-B: Δ-N = round(rate × 65 536 / 55 466.67 × 2^((u − root × 64) / 768)), 1..65 535.
- **Writes** are diffed against a shadow of both ports, except `$28` key, `$0D` (a write restarts the SSG envelope),
  `$10` rhythm key and `$100` ADPCM-B control, and frequency pairs, written high byte first, both whenever either
  changes. Each batch starts with its key-off phase (`$28` off, rhythm dump `$10 = $80 | bit`, ADPCM-B stop
  `$100 = $01`).
- **Per tick**: `$22` and `$11` (YM2608) and timer A (`$24 = NA >> 2`, `$25 = NA & 3`) diffed; the row (rows, `Gxx`
  delays, `Sxx` cuts, `Bxx`/`Dxx` jumps, `Fxx`, the loop order's start speed: the Mega Drive player's sequencing);
  updates FM, then SSG, then rhythm, then ADPCM-B; `$27` last (normal `$00`, special `$40`, CSM `$81` while FM 3 holds
  a note, else `$40`).
- **FM**: a key-on writes the operators (carrier TL + 127 − volume), `$B0` and on the YM2608 `$B4` (pan | AMS/FMS);
  `$28 = $F0 | channel` follows at the next update. Locks `Exx`/`Ixx`/`Mxy` change only that note's copy. FM 3 in the
  special or CSM mode plays operator k at u + its sound's offset k: `$AD/$A9` operator 1, `$AE/$AA` 2, `$AC/$A8` 3,
  `$A6/$A2` 4 (the chip's mapping; ymfm `opn_registers::cache_operator_data`). In CSM mode an FM 3 note writes no `$28`:
  `$27 = $81` (CSM, timer A loaded) makes timer A key all four operators on every (1024 − NA) native samples
  (18.03 µs × (1024 − NA)); its OFF or `Sxx` sets `$27 = $40`.
- **SSG**: the five envelopes are copied at key-on (later instrument edits apply to the next note). Per channel and
  tick: level = (volume envelope × channel volume + 7) / 15 into `$08+i`, or with a hardware envelope `$10` while that
  level is above 0 (the volume envelope then gates the generator); mixer from the mixer envelope (empty = tone).
  Shared: `$07` from the three channels' mixers (an inactive channel disables both); `$06` noise period = `Nxx`'s,
  overridden each tick by the highest-numbered sounding channel with a noise envelope; `$0B/$0C` envelope period from
  the highest-numbered sounding channel with a hardware envelope (auto: TP >> octave, negative octaves shift left,
  1..65 535; manual: the sound's period or `Jxx` × 256); `$0D` at the key-on of such a note (`Hxx` overrides the
  shape). Release: the volume envelope's release part, as the Mega Drive's PSG.
- **Rhythm** (YM2608): `$18+d = pan | (level × channel volume + 15) / 31` every tick (diffed; `Cxx`/`Axy` act on a
  sounding drum), and at a note `$10 = 1 << d` (re-trigger without a dump); OFF/`Sxx` dump in the key-off phase.
  `Rxx` sets the total level `$11`.
- **ADPCM-B** (YM2608): a note stops the previous sample in the key-off phase (`$100 = $01`), then writes `$101 = pan
  | $02` (8-bit DRAM), start / 32 and end / 32 − 1 to `$102-$105`, Δ-N to `$109/$10A` and `$10B = level × channel
  volume / 255` (both every tick), then `$100 = $A0 | (loop ? $10 : 0)`. OFF/`Sxx` stop it.
- **Reset** (`opnp_machine_reset`, then `opnp_reset`): all operators silent (TL `$7F`, SL/RR `$FF`), frequencies 0,
  `$B4 = $C0` and `$29 = $80` (six FM channels) on the YM2608, keys off, SSG off (`$07 = $3F`), rhythm dumped with
  levels 0, ADPCM-B reset with `$101 = $02` and limit `$FFFF`; then the header's `$22`, `$11`, timer A and `$27`.

### Effects (what Kotlin's effect specs declare)

As the Mega Drive's where the letters match (`0` arpeggio, `1`/`2` slides, `3` portamento, `4` vibrato, `8` pan, `A`
volume slide, `B` jump, `C` volume, `D` break, `E` feedback lock, `F` speed, `G` delay, `I` brightness lock, `L` LFO,
`M` ratio lock, `P` fine pitch, `S` note off), plus `H` SSG hardware-envelope shape lock (00 none, 08-0F), `J` SSG
manual envelope period lock (xx × 256), `N` SSG noise period (00-1F), `R` rhythm total level (00-3F, YM2608) and `T`
timer A = xx × 4 (the CSM rate). Volumes: FM `C00-C7F`, SSG `C00-C0F`, rhythm `C00-C1F`, ADPCM `C00-CFF`. Pan,
`L` and `R` exist on the YM2608 only. `B`, `D`, `F`, `L`, `R`, `T` load on every channel; pitch effects not on
rhythm channels; FM locks on FM only; `H`, `J`, `N` on SSG only.

### Position, seek and live edits

- **Position** is the player's (order, row, tick); `tick 0` marks row starts (no `row_serial`).
- **Seek** (`opnm_start`): the player runs alone first to count the ticks to the target, within `max_ticks`
  (`SEEK_TICKS` = 12 000) from the song start, else from the target order's start with the speed straight play starts
  it with (`CHIP_SEEK_FROM_ORDER`). Then the machine is reset to exactly a fresh machine (chip power-on snapshot and
  reset writes, empty resamplers) and every tick up to the target runs as straight play runs it — all writes, the
  whole chip clocked, the resampler fed and read — with the output discarded. From the song start the sound after a
  seek is therefore sample-identical to straight play reaching that row (FM envelopes and release tails, SSG
  counters, rhythm and ADPCM-B positions, a running CSM timer all included). The cost is rendering the skipped stretch
  (not measured; WAV-export speed), bounded by `max_ticks`.
- **Live edits** (`opnm_apply_image`, at a tick boundary, realtime-safe): the machine copies only what it reads
  (`opn_copy_image`): the info, header and tables and the used data region (at most 64 KiB), and the used ADPCM-B
  memory only when its bytes differ from the machine's copy (compared exactly with `memcmp`, then copied): an edit
  that keeps the samples reads them once and writes none. The host's own hand-over of loaded images is unchanged
  (Android copies `image_storage` on its control thread); the player keeps (order,
  row, tick), clamps them to the new song and re-reads the current rows (the Mega Drive's relocation), and takes the
  header's LFO, timer A and rhythm level when they changed. A sample under way continues from its address in the new
  bytes; new start/end addresses apply at the next note. FM 3's mode is not taken at the edit: the player reads it
  from the image only at FM 3's key-ons (and at reset). An FM 3 note and its release tail keep the mode they started
  with: their frequency registers and `$27`; normal/special key off with `$28`, and CSM uses `$27 = $40` at OFF or
  `Sxx`. At a new FM 3 note, `$27` changes directly to that note's current image mode; CSM has no `$28` key-off phase.
  With FM 3 idle, an edited mode likewise takes effect only at FM 3's next note.

### Audition, mute, mix

- **Audition** on any channel with a sound of its part (`opnp_audition`); fine pitch on FM, SSG, ADPCM. Release keys
  off and ticks until the part is silent (FM carriers at ymfm's quiet attenuation, SSG level 0, the drum or sample at
  its end) or 10 s. A drum audition without the rhythm ROM is refused in Kotlin (`Ym2608RhythmRom.MISSING`).
- **Mute** leaves a tracker channel's part out of the sums only (`OPN_MUTE_*`); the chip keeps computing it, so
  unmuting continues exactly as if never muted.
- **Mix** (MAME's relative routing in `pc8801.cpp`): YM2203 (FM + SSG A + B + C) / 2, mono;
  YM2608 clamp16(FM + rhythm + ADPCM-B, ymfm's own sum: FM >> 1, ADPCM-B >> 1) +
  (SSG A + B + C) / 3 on both sides (MAME: SSG 0.25 against FM 0.5). Apply a fixed **3/16 output
  gain before blip_buf**, without clipping the combined route. Preserve the YM2203 floating-point DAC
  and YM2608's inner DAC saturation before adding SSG. The unscaled legal route ranges are
  −16384..40956 and −32768..49149 respectively; additive FM algorithms can reach the DAC limits.
  Budget the full DC-to-opposite-rail span, not just the positive peak: the larger span is below
  2.5 host units. The integrated `bl_step` table's total variation is below two, including linear
  phase interpolation; use two as a conservative reconstruction span bound for arbitrary held
  levels. The high-pass subtracts a running DC estimate, so budget that entire reconstructed span:
  2.5 × 2 × 3/16 = 0.9375, with more than 2048 PCM units left for integer rounding.
  This gain does not change relative part levels or register writes; it is not a limiter or automatic gain.
  The native headroom regression covers both chips at 8, 44.1, 48 and 96 kHz with full additive FM,
  full-volume SSG, held DC, DC-to-square and simultaneous cuts, comparing against an independently
  routed chip plus blip path (including DAC saturation). The final native run passes 1,806 checks; the old
  production mixer fails 58 against that regression. External observation of 16 loud/demo/rate cases
  (32 sides) finds no outer-clamp loss, resampler clipping, observer mismatch or integrator overflow;
  the maximum preclip peak is 0.317718506.
  The balance between FM and SSG on real boards differs by model and is not measured here.

### VGM export (`opn/opn_vgm.c`, `opn_export.h`)

VGM 1.71, command data at `$114` (relative data offset `$34` = `$E0`). YM2203: clock at `$44` = 3 993 600, writes as command `$55`. YM2608: clock at
`$48` = 7 987 200, port 0 / 1 writes `$56` / `$57`, and first a data block `$67 $66 $81` ("YM2608 DELTA-T ROM data":
u32 memory size 262 144, u32 start 0, the used ADPCM-B bytes) when the song has samples. The SSG flags `$7A` / `$7B`
are `$01`: the VGM 1.71 specification (vgmrips `vgmspec171.txt`) gives `$79` "AY8910 Flags … Default is 0x01" (bit 0
Legacy Output) and `$7A` / `$7B` as the same flags for the AY8910 "connected with/part of" the YM2203 / YM2608; bit 4
(YM2149 pin 26's extra /2) stays clear because the SSG divider is the chip's prescaler. Then the reset writes and each
tick's writes with the key-off phase one 44.1 kHz sample earlier, waits from the native sample count (exact: 44 100 ×
72 per native sample over 3 993 600), `$66`, then the GD3 tag (system "PC-88/98 OPN (Thor Tracker)" / "PC-88/98 OPNA
(Thor Tracker)").

**Board mix metadata.** The [VGM 1.71 extended header](https://github.com/vgmrips/vgmplay-legacy/blob/master/VGMPlay/vgmspec171.txt)
at `$100` has size 12 and one volume entry: first-chip paired SSG `$86` / `$87`,
relative volume `$8600` / `$8200` (YM2203 ×6 / YM2608 ×2). VGMPlay's default normalized
SSG/FM routing is not the preview's board mix. Independent MAME renders of isolated
FM and SSG tones agree in their reference-to-engine gains within 0.83% after this
metadata; without it the SSG is too quiet. Overall output gain remains the VGM
player's choice. No register writes, waits, sample data or native PCM change.

**Loop without injected writes.** The file contains only writes the player makes in native playback, never a register
dump. The renderer runs the player to its arrivals at (loop order, row 0, tick 0), A1, A2, …, and takes the first pair
Ab < Aa whose complete player states are identical (`opnp_same_state`: position, speed, song-wide settings, every
channel's note, effect and envelope state, rows, cursors, SSG envelope copies and the register shadow, field by
field; a 64-bit digest finds candidates and a second player replayed to Ab confirms them exactly). From Ab on the
player's writes repeat with period [Ab, Aa) forever, so the file holds the ticks before Aa with the loop offset at Ab:
the first pass and every repeat send exactly native playback's writes — a held SSG hardware-envelope note, a held CSM
note, a looping ADPCM-B sample or a slide across the wrap included. Usually Ab = A2 and Aa = A3, so the file holds the
intro, one pass and the looping pass. Only the waits of a repeat reuse pass b's 44.1 kHz rounding (each write within
one VGM sample of native timing). A song whose state never repeats within `max_ticks` (10 minutes in Kotlin's
`OpnVgm.maxTicks`) and 64 arrivals ends there without a loop. Kotlin: `OpnVgm` with the host renderer (Android
`ChipAudio.nativeOpnVgm`, Deck `DeckOpnVgm` over `opn_export_vgm`). The rhythm ROM is never in a file: VGM players
supply their own copy. Whether a given VGM player emulates CSM (timer A) is that player's matter; not checked.

### Tools and verification

- `tools/audio/run_native_host_test.sh [dir]` builds and runs `app/src/test/cpp/opn_machine_test.c` (both chips,
  images built in C, no fixtures, no ROM): loader rejections; pitch measured from rendered audio (FM, SSG, ADPCM-B);
  FM 3 special-mode writes and pitch; CSM timer A expiry rate and stop; FM 3's mode switched live under a held note
  (every ordered pair of modes, released by OFF or by a retriggering note: writes and samples = the unedited song's
  until the next note, the release in the old mode, then the new mode as a seek to that note sets it); SSG mixer /
  noise / hardware-envelope ownership; rhythm writes and silence plus the missing flag without the ROM; ADPCM-B
  sequence, end, loop, pan and level; positions; seeks sample-identical to straight play (an ADPCM-B sample keyed
  long before the target, FM release tails, the SSG envelope, CSM) and the order fallback; live edits before every
  tick; mute continuity at the
  chip level (exact) and machine level; VGM structure and loops (each file expanded to three passes must equal a
  fresh player's write trace exactly — songs with an SSG hardware-envelope note, a CSM note and a looping ADPCM-B
  sample held across the wrap, a volume slide that settles only after some passes, and a song that never repeats and
  so has no loop); the ops tables; per-tick cost. Evidence WAVs, VGMs, write traces and images go to the directory.
- `python3 tools/audio/opn_reference_check.py [dir]` (standard library only, independent of the C code): parses the
  VGMs, recomputes frequencies from the logged registers with the datasheet formulas and the clocks above, decodes
  ADPCM-B itself, measures the evidence WAVs' pitch against them, expands each loop VGM itself to compare it with
  the player's straight write trace, and validates the relative extended-header offsets and paired-SSG mix.
- `wav_render_test.c` renders the demos `night-express.opnimg` and `paper-lanterns.opnaimg` and the shortest songs
  `opn-shortest.opnimg` and `opna-shortest.opnaimg` (from `exportSamples`).
- `tools/audio/render_opn_image.sh image out.wav [seconds] [rom.bin | rom=path] [mute=1,7,16] [--vgm out.vgm]
  [title=… author=… date=…]` renders an image through the machine (rhythm with a private ROM path or
  `THOR_RHYTHM_ROM`; it says so when a song keyed drums without one) and optionally its VGM.
- **Private rhythm checks**: `THOR_RHYTHM_ROM=/path/to/ym2608_adpcm_rom.bin` (the user's own copy, size and SHA-256
  checked) also runs the audible-rhythm checks; without it they are reported as skipped. Keep the ROM and its
  evidence outside the repository.
- The focused held/idle FM 3 mode check passed 264/264 on 2026-11-09; the preserved pre-fix player failed 72 checks.
  The final focused native suite passes 1,806 checks, independent Python reference 420 checks and WAV export
  2,006 checks. All 14 independent MAME tone checks pass unchanged 0.2% FM/SSG and 0.5% ADPCM tolerances;
  maximum error is 0.1621% for the ADPCM octave, with FM 3 at 880.208 versus 880.198 Hz.
  Full demo exports also pass independent envelope/spectrum/pitch-class comparison after the board-mix
  metadata fix. Reserved-device UI, actual WAV import and VGM/WAV save pickers, firmware reuse and artifact
  privacy were exercised; see `docs/VERIFICATION.md`. No human listening or real PC-88/98 measurement is claimed.

## The YM2608 rhythm ROM (shared firmware)

One module serves every chip with rhythm drums — the NES EPSM's YMF288 (`"nes-famistudio-epsm"`) and the YM2608
(`"ym2608-opna"`): `ym2608_rhythm_rom.h` / `.c`. The ROM (8192 bytes: bass drum, snare, top cymbal, hi-hat, tom, rim
shot at ymfm's fixed addresses; SHA-256 `53afd0fa9c62eda3e2be939e23f3adf48a2af8ad37bb1640261726c5d5adeba8`) is
Yamaha's and is not distributed with Thor Tracker in any form.

- `int ym2608_rhythm_rom_install(const uint8_t* rom, size_t size)` copies it once into static storage and publishes it
  with an atomic release store: 0 ok (the identical bytes again are a no-op); −1 for NULL, a size other than 8192, or
  other bytes than an installed ROM (installed storage is never rewritten while audio threads may read it).
  `ym2608_rhythm_rom_installed()` is 0/1; `ym2608_rhythm_rom()` returns the bytes or NULL (acquire). The caller checks
  the ROM's identity first (`Ym2608RhythmRom.problem()` in `system/fm`: size, then SHA-256).
- Hosts: Android `ChipAudio.nativeInstallRhythmRom(rom: ByteArray): Int` (JNI, `audio_jni.cpp`), called by
  `MainActivity` at start for a stored `filesDir/firmware/ym2608_adpcm_rom.bin` that still verifies and after an
  import; the Deck calls the same C function through `decknative.def` for
  `~/.local/share/thortracker/firmware/ym2608_adpcm_rom.bin`. One import (main menu "YM2608 rhythm ROM…", shown for
  songs whose system plays it, `SoundSystem.rhythmRom`, or the EPSM / OPNA Rhythm pages) serves both systems; the file
  name and both host storage directories are unchanged from EPSM-only versions. Startup validates and loads that
  existing file in place; there is no copy, rename, alternate path, or deprecated API alias.
- Needed only when used: Kotlin's `lower()` refuses a song whose played orders hold a rhythm note, and audition
  refuses a drum, with `Ym2608RhythmRom.MISSING`; FM, SSG and ADPCM-B play without it, and NSF, FamiStudio text and
  VGM exports never contain or need it (a WAV export plays the song, so a rhythm song needs it there too).

## POKEY: Thor Tracker's own player on the Atari 8-bit POKEY (`"atari-pokey"`)

**Integration status:** 0.31.0/code 44 merges the exact OPN/OPNA parent `d417843`; source only, **UNVERIFIED**.
The ended-PCM portamento preflight fix and its native/Kotlin/reference regressions are preserved, not yet run.
`VERIFICATION.md` lists the pending full-chain checks and the Kotlin sample-lifetime approximation risks.

The Atari 400/800/XL/XE's POKEY (C012294) has no console driver to run: songs play through Thor Tracker's **own
player** (`pokey/pokey_player.c`, pure integer C without a chip, apart from the pitch search's doubles below), whose
behaviour is the specification in this section, on an **own cycle-exact POKEY model** (`pokey/pokey_chip.c`). The
player is what an Atari music driver is: a routine the video frame interrupt calls (the tick) and a timer interrupt
routine (sample playback). `tools/audio/pokey/pokey_ref.py` implements the player separately in Python and
`tools/audio/pokey_reference_check.py` requires identical register writes. Songs export as VGM (POKEY, VGM 1.71). No Atari executable (XEX/SAP) is
exported: that would need a 6502 driver this system does not have. One POKEY only: stereo (dual-POKEY) boards are an
add-on, not part of the machine. Kotlin side: `core` `system/pokey`, `backend/PokeyLowering.kt`, the hand-kept mirror
of the engine numbers `backend/PokeyPlayerImage.kt`.

### Sources

- **Altirra Hardware Reference Manual**, Avery Lee, 2026-01-02 edition (virtualdub.org), chapter 5 "POKEY": timer
  periods (N + 4 at 1.79 MHz, N + 7 linked, (N + 1) × 28 / × 114), linked-timer fire timing, the distortion table,
  noise sampling, the generators (polynomials, patterns, reset state, one-cycle delay per channel), high-pass
  flip-flops and the inversion of channels 1/2, volume only, STIMER, initialization mode and the base clocks' phase,
  two-tone resync (2-cycle delay, force break), the nonlinear output. Measured on hardware.
- **Atari 400/800 Hardware Manual** (Atari, 1982), POKEY register descriptions; the machine clocks (NTSC
  3.579545 MHz / 2, PAL 17.734475 MHz / 10) and frame lengths (114 cycles per line, 262 / 312 lines).
- **MAME `src/devices/sound/pokey.cpp` 4.9** (BSD-3-Clause, Brad Oliver, Eric Smith, Juergen Buchmueller; read at
  github.com/mamedev/mame master, 2026-11-08): the per-cycle structure (counting, then the fires of channels 3, 4, 1,
  2; counters keep counting through the fire delay; linked low counters wrap). Where MAME and Altirra differ, Altirra
  (hardware measurements) wins: STIMER sets the output flip-flops to 1, fast counters keep counting in initialization
  mode, the two-tone resync is 2 cycles after the triggering fire.
- **Altirra 4.40 source** (`src/ATAudio/source/pokeytables.cpp`, `pokey.cpp` UpdateMixTable, `pokeyrenderer.cpp`
  PostFilter; GPL-2.0-or-later, Avery Lee; virtualdub.org/downloads/Altirra-4.40-src.7z, 2026-11-08): the generator
  formulas, the per-bit DAC levels and saturation curve, the output's AC coupling (τ = 2.597 ms) and amplifier clamp.
  Ported to C in `pokey_chip.c` and `pokey_machine.c` (GPL-3.0-or-later is compatible; notice in `NOTICE.txt`).

### The chip (`pokey/pokey_chip.c`)

- **Clocks**: NTSC 315 MHz / 176 = 1789772.73 Hz, PAL 1773447.5 Hz; the 64 kHz and 15 kHz base clocks are the machine
  clock / 28 and / 114, pulsing first 19 and 78 cycles after initialization mode ends (both run; AUDCTL bit 0 picks
  one for every base-clocked counter).
- **Counters**: a counter counts its clock (channel 1 / 3: the machine clock when AUDCTL bit 6 / 5; channel 2 / 4:
  channel 1's / 3's fires when linked by AUDCTL bit 4 / 3; else the base clock). Reloaded with AUDF + 1 counts left;
  the count that reaches 0 is the underflow; the counter **fires `PK_FIRE_DELAY` = 3 cycles later** and reloads then
  (a fire already pending ignores the underflow). Periods: AUDF + 4 cycles at 1.79 MHz, (AUDF + 1) × 28 / × 114 on a
  base clock. Linked: the low counter is not reloaded by its own fires (it wraps and keeps counting), the high one
  counts them, and the high one's fire reloads both: N + 7 cycles at 1.79 MHz (N = AUDF low + 256 × AUDF high), (N + 1)
  × 28 / × 114 on a base clock; the low channel still fires on every wrap (audio and IRQ). An AUDF write takes effect
  at the next reload. A reload leaves a pending fire pending; only STIMER and two-tone resyncs drop pending fires.
- **Per cycle** (`step`): counting; then the fires of channel 3 (linked: count channel 4, else reload; audio; the
  channel 1 high-pass flip-flop samples channel 1's output if AUDCTL bit 2, else is 1), channel 4 (linked: reload 3;
  reload 4; audio; flip-flop of channel 2 by bit 1; IRQ 4), the two-tone resyncs due, channel 1 (linked: count 2,
  else reload and, in two-tone mode without force break, a resync 2 cycles later; audio; IRQ 1), channel 2 (linked:
  reload 1; reload 2; audio; two-tone resync; IRQ 2). The model runs `step` only in cycles where something happens
  and skips the others arithmetically; `pk_run_reference` steps every cycle and the host test requires identical
  output.
- **Audio circuit** at a fire: unless AUDC bit 7 is clear and the 5-bit generator's bit is 0, a pure tone (bit 5)
  toggles the output flip-flop, else it takes the 4-bit generator's bit (bit 6) or the 9/17-bit one (AUDCTL bit 7
  selects 9). **Generators**: XNOR shift registers from the all-zero state at the end of initialization mode, one shift
  per cycle (4-bit 1+x³+x⁴, 5-bit 1+x³+x⁵, 9-bit 1+x⁴+x⁹, 17-bit 1+x¹²+x¹⁷ tapped after bit 8; Altirra's formulas);
  POKEY channel *c* (1-4) sees the bit *c* cycles late (index t − end of initialization mode − *c*); in initialization
  mode (and before a channel's first delayed bit) the 5-bit reads 1, the others 0.
- **Output**: channel *c* is on when AUDC bit 4 (volume only, after the high-pass XOR) or its flip-flop (channels 1
  and 2: XOR their high-pass flip-flop, forced to 1 when off: inverted against 3 and 4) is 1; its level is then AUDC
  bits 0-3. **STIMER** reloads every counter (pending fires dropped, no pulses), sets every output flip-flop and both
  high-pass flip-flops to 1 and drops pending resyncs. **SKCTL** bits 0-1 = 0: initialization mode (generators and
  base clocks reset and held; 1.79 MHz counters keep counting); leaving it starts them. Bit 3 two-tone: a resync
  reloads counters 1 and 2 (pending fires dropped) 2 cycles after a fire of timer 2, or of timer 1 — only while 1+2
  are not linked, as in MAME (a linked low timer's fires are not known to resync; unverified on hardware) — while the
  serial output bit is 1 — the idle line (no serial transfers here), 0 with force break (bit 7). **IRQEN** bits 0, 1, 2 =
  timers 1, 2, 4: `pk_run` returns after a cycle in which an enabled timer fired.
- **Mix and output** (`pokey_machine.c`): the four levels index Altirra's nonlinear table (`pk_mix_init`: per-bit DAC
  drops 0.12/0.26/0.56/1.12 of 8.24, linear to 0.14 then 1 − e^(−2.85 x) saturation, × −2.1); each change is a step
  into blip_buf 1.1.0 at its cycle (machine clock → host rate, ×16384), then per host sample the AC coupling
  y = c·y + (x − x′), c = e^(−1/(rate·τ)), τ = 2.5968511 ms, clamped to [−0.65, 0.5] (Altirra's amplifier), output
  mono → both host channels. blip_buf's own ~15 Hz leak is in series.
- **Not modelled**: the serial port (shift registers; SKCTL bits 4-6 clock modes, whose asynchronous receive mode
  holds timers 3/4), keyboard and paddle scans (no sound), Altirra's 1.5-cycle high-pass delay and first-period offsets
  right after STIMER (sub-period phase), the 9/17-bit register's partial reset when initialization mode lasts under
  18 cycles (it resets at once here, as in MAME), a second POKEY, the XL/XE console speaker (GTIA).

### Channels, sounds and the channels they take

Four tracker channels, POKEY channels 1-4. A **sound** (instrument) is one of four kinds; a note takes every POKEY
channel its kind needs, **cutting whatever sound held them** (the most recent note wins; the Kotlin song check warns
where a note cuts a sound that has not been released, and the Pattern lanes show which channel holds which):

| Kind (flags) | Channels it plays on | POKEY channels it takes | AUDCTL bits it sets |
|---|---|---|---|
| 8-bit tone | any | its own | fast: 1.79 MHz on channel 1 (bit 6) or 3 (bit 5) only |
| 16-bit (`JOINED`) | 2, 4 | 2 + 1 or 4 + 3 (1 / 3 = low byte) | link 1+2 (4) / 3+4 (3); fast: the low counter at 1.79 MHz (6 / 5) |
| High-pass (`FILTERED`) | 1, 2 | 1 + 3 or 2 + 4 (3 / 4 = the high-pass clock) | high-pass 1 by 3 (2) / 2 by 4 (1); fast (channel 1 only): 1 and 3 at 1.79 MHz (6, 5) |
| 16-bit high-pass (`JOINED + FILTERED`) | 2 | all four: 1+2 the sound, 3+4 its clock | 4, 3, 1; fast: 6 and 5 |
| Samples (`SAMPLE`) | 1, 2, 4 (timer 3 has no interrupt) | its own | fast (channel 1 only): 6; its timer's IRQEN bit |

A sound on a channel its kind cannot use (a 16-bit sound on channel 1, fast on channel 2 or 4, samples on channel
3, ...) does not key on (the lane's old voice keeps sounding; the Kotlin song check reports it as an error). Channels a sound takes besides its
own get AUDC = the sound's partner byte (0 = silent; anything else makes them audible with their counters' own
waveforms). A channel whose sound was cut, or never played, has AUDC 0 until a note takes it again. The shared AUDCTL
bits 7 (9-bit noise) and 0 (15 kHz base clock) and SKCTL's two-tone mode are global effects (`N`, `K`, `W`), STIMER
(`T`) and an initialization-mode pulse (`R`) act on the whole chip.

### Image layout (what the Kotlin lowering produces)

`image_size` = 65536 bytes, little-endian; constants in `pokey/pokey_image.h` (`PK_*`), mirrored in
`backend/PokeyPlayerImage.kt`. The loader (`pk_load_image`, `pokey/pokey_image.c`) rejects anything off-contract with
a reason and the byte (`PkInfo.problem`, `problem_offset`); the Kotlin validator reports the same conditions in words.

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | `"TTPK"` |
| 4 | 1 | layout: 1 |
| 5 | 1 | machine: 0 NTSC, 1 PAL |
| 6 | 1 | multispeed N, 1..4 (player ticks per video frame) || 7 | 1 | speed (ticks per row) at the song start, 1..255 |
| 8 | 2 | order count, 1..256 |
| 10 | 2 | loop order (< count) |
| 12 | 1 | instrument count I = highest slot present, 0..64 |
| 13 | 1 | shared AUDCTL bits at the start: 0x80 (9-bit noise) and 0x01 (15 kHz) only |
| 14 | 2 | data size D, ≤ 44608 |
| 16 | 2 | sample bytes S, ≤ 16384 |
| 18 | 1 | sample count, 0..32 |
| 19 | 45 | 0 |
| 64 | 256 | rows of order *o*: 1..255, 0 = 256 (Thor Tracker: ≤ 64); entries ≥ count 0 |
| 320 | 2048 | pattern of (order *o*, channel *c*) = u16 data offset at 320 + 2 (4 *o* + *c*); entries ≥ count 0 |
| 2368 | 2048 | instrument slot *n* (1..64) at 2368 + 32 (*n* − 1) |
| 4416 | 128 | sample *k* (0..31) at 4416 + 4 *k*: u16 byte offset into the sample region, u16 length in levels 1..32768; entries ≥ count 0 |
| 4544 | 44608 | data region: patterns and envelopes, `[0, D)` |
| 49152 | 16384 | sample region: 4-bit levels, two per byte (earlier level in the low nibble), `[0, S)`; a sample lies inside it |

**Instrument** (32 bytes; all-zero = no instrument; records above I zero): byte 0 flags — 0x80 defined (required),
0x01 joined, 0x02 filtered, 0x04 sample (not with joined/filtered), 0x08 fast, 0x10 pitch envelope relative, 0x20
difference (filtered only); byte 1 filter offset s8 (filtered; difference: 1..127; else 0); byte 2 partner AUDC
(joined/filtered; else 0); byte 3 sample index (< count; sample sounds, else 0); byte 4 root note 0..107 (samples, else
0); byte 5 0; bytes 6-7 rate 1000..32000 levels per second at the root (samples, else 0); bytes 8-9 loop start < the
sample's length or 0xFFFF (samples; else 0xFFFF); bytes 10-17 data offsets of the volume (0..15), arpeggio (s8
−64..63 semitones), pitch (s8 −64..63 sixteenths of a semitone) and distortion (0..8) envelopes, 0xFFFF = none; bytes
18-31 0. Sample sounds use the arpeggio and pitch envelopes only (the others must be 0xFFFF).

**Envelope** (in the data region, may be shared): u16 length 1..256, u16 loop (< length, or 0xFFFF), u16 release
(1..length − 1, greater than the loop, or 0xFFFF), then `length` bytes inside `[0, D)`, each in its envelope's range.

**Distortion values** (envelopes, `Vxx`): 0..7 = AUDC bits 7-5 (`0` poly5+poly17/9, `2` poly5-gated tone, `4`
poly5+poly4, `6` = `2`, `8` poly17/9, `A` pure tone, `C` poly4, `E` = `A`), 8 = volume only (the level is a DC
step: the DAC). No distortion envelope = `A`.

**Patterns**: as the OPL3's (`0x80 | k` = k + 1 empty rows; mask `0x01..0x13`: bit 0 note, bit 1 instrument, bits 2-4
effect count 0..4; note 0..107 or `0xFE` OFF; instrument 1..64, defined; effect code, parameter per column), decoded
for exactly rows(*o*) rows inside `[0, D)`. Load-time parameter checks: `Bxx` < order count, `Cxx` ≤ 0F, `F00`
rejected, `Hxx` any (a difference sound clamps to 1..127), `Kxx` ≤ 1, `Nxx` ≤ 1, `R00`, `T00` only, `Vxx` ≤ 8, `Wxx`
≤ 2. `pk_load_image` derives `PkInfo`: order count, loop, machine, multispeed, speed, rows per order, and per order the
speed, shared AUDCTL bits and two-tone state sequential straight play starts it with (header values, then each order's
last `F`, `K`/`N`, `W` in rows, channels, columns order; jumps ignored).

### Player

Units: **pitch** in 1/64 semitone, 0 = C-0, at most 108 · 64 − 1; note *n* = 64 *n*; frequency f(p) =
440 · 2^((p − 3648) / 768) Hz (A-4 = 57 · 64 = 3648 = 440 Hz).

**Pitch to counter** (per tick for every sounding voice; doubles, the Python reference does the same operations):
the distortion gives a divisor *D* (fires per waveform period) and a modulus *M*: pure tone `A`/`E`, noise `8` and
volume only: D 2, M 1; `0`: D 2, M 31; `C` (the 15-step poly4 pattern): D 15, M 15; `2`/`6` (the 31-step poly5 gate
with 15 toggles): D 62, M 31; `4` (poly5 and poly4, 465 steps): D 465, M 465. Noise distortions `0`/`8` are not
periodic tones: their note picks the rate a square wave of that pitch would fire at. Target period Pt = C / (f · D)
cycles (C the machine clock). The voice's counter has periods P(N): 28 (N + 1) or 114 (N + 1) (base clock, the
current shared choice), N + 4 (fast 8-bit), N + 7 (fast 16-bit); N 0..255 (8-bit) or 0..65535 (16-bit). Candidates:
every 8-bit N; for 16-bit, N0 = round(Pt) − 7 (fast) or round(Pt / 28 or 114) − 1 (round = half away from zero, C's
`llround`), clamped to 0..65535, and the 49 values N0 − 24 .. N0 + 24 inside 0..65535 (clipped at the ends, not
shifted). The chosen N minimises |P(N) − Pt| (doubles) among candidates whose period is coprime to M (so the sampled
pattern has its full length and the note its pitch), or among all if none is; ties take the smaller N. (On the 15 kHz
clock every period is a multiple of 3, so `C` and `4` cannot play their full patterns there; the song check warns.)
The search depends only on (pitch, distortion, base clock, machine, filter offset) and the voice's kind: the player
keeps the last result per voice and reuses it while those are unchanged (a cache, not a rule).

**High-pass sounds**: the clock channel's counter N′ = N + filter offset (clamped to the counter's range): the
offset sweeps the high-pass sampling against the tone (a phasing pulse wave at |offset| small). **Difference** sounds
(the "POKEY saw"): the note is the difference of the two counters' fire rates, C/P(N) − C/P(N + d), d = offset 1..127:
N minimises |C/P(N) − C/P(N + d) − f| (8-bit: every N with N + d ≤ 255; 16-bit: N0 from x = (√(Δ² + 4 C Δ / f) − Δ) /
2 cycles, Δ = d (fast) or 28 d / 114 d cycles, as above, and the 49 around it clipped to 0..65535 − d; ties the
smaller N); the clock channel's N′ = N + d; the distortion's D is not used. `Hxx` overrides d (clamped to 1..127).

**Samples**: rate r = rate · 2^((p − 64 root) / 768); the counter is chosen as above for Pt = C / r, D 1, M 1, among
periods ≥ `PK_PCM_MIN_PERIOD` = 114 cycles (one level per scan line at most: what a 6502 interrupt routine sustains
beside the display's DMA).

**Envelopes** (FamiStudio's semantics): at the key-on every envelope of the sound starts at step 0; each tick reads
the step, then advances: the step after the last loops to `loop` (when there is a loop and no release) or holds the
last; reaching `release` before the note is released loops to `loop` (or holds `release − 1` without a loop). OFF moves
every envelope with a release to its release step (and on from there to the end, which holds); a second OFF does
nothing. Values: volume 0..15 (none: 15), arpeggio semitones (none: 0), pitch sixteenths: absolute offset, or added to
an accumulator each tick and the sum used in the same tick (relative; the accumulator is clamped to ±1728, the pitch
range), distortion 0..8 (none: 5). Edits apply live: envelopes are read from the current image every tick by (slot,
envelope, step); a step beyond a shortened envelope is clamped to its last; a slot no longer defined reads as no
envelopes.

**Writes**: through a shadow of the 16 registers — a write equal to the shadow is not emitted, except the reset batch,
STIMER and the `R` pair. A tick's batch, in order: SKCTL (`R`: SKCTL with bits 0-1 cleared, then SKCTL), AUDCTL, AUDF1,
AUDC1, AUDF2, AUDC2, AUDF3, AUDC3, AUDF4, AUDC4, IRQEN, STIMER (`T`, value 0). SKCTL = 3 | 0x08 (two-tone on) | 0x80
(`W02`). The machine writes a batch at the tick's first cycle. **Reset** (start, audition): all state cleared; lanes:
instrument 0, volume 15, no effects, no voice; speed = header speed; shared AUDCTL bits = header byte 13; two-tone
off; the reset batch, **emitted in full whatever the shadow holds** (a defined start for the chip and for every VGM
player), then held by the shadow: SKCTL 0, AUDCTL = header byte 13, AUDF1, AUDC1 .. AUDF4, AUDC4 = 0, IRQEN 0, SKCTL 3
(initialization mode ends: the generators and base clocks start at the first cycle). It and the first tick's batch
are both written at cycle 0.

**Tick** (positions (order, row, tick-in-row); the host's position is the tick about to run): sequencing exactly as the
OPL3 player (row reads at tick 0, `F` last wins, `B`/`D` jumps, `G` delays, `S` cuts at their tick), plus: the global
effects `K`, `N`, `W`, `R`, `T` act at the row's tick 0 like `F` (channel then column order, the last wins; not delayed
by `G`); **every arrival at row 0 of the loop order** (the wrap, a jump, the first pass) sets the speed, the shared
AUDCTL bits and the two-tone state to what sequential straight play starts that order with (`PkInfo.start_speed`,
`start_audctl`, `start_two_tone`), so every pass of the loop plays alike without the lowering emitting anything. Then
each voice is updated (below), the shared AUDCTL, SKCTL and IRQEN values are assembled and the batch written.

**Running a row** of a lane: a note 0..107 clears the lane's arpeggio effect, fine pitch and `V`/`H` overrides; an
instrument byte sets the lane's instrument; effects run in column order (table); then the note: OFF releases the voice
(envelopes with a release point jump to it; a voice whose volume envelope has no release point, or that has no volume
envelope, is **silent at once**: level 0, its channels kept until a note takes them, its envelopes stop stepping, pitch
still updates; samples stop: AUDC 0, interrupt off); a note with portamento active (`3xx` > 0) on a lane whose voice
is **held** only sets the glide target; any other note keys on. Held = keyed on and neither released nor silenced by
OFF/`Sxx`, and for a sample voice still playing: a one-shot sample whose timer interrupt has run past its last level
(or that stopped because a live edit shrank it) is no longer held, so the next note keys on and restarts it even
under a sticky `3xx`. A looping sample stays held until OFF or `Sxx`; a synthesized voice stays held after its
envelopes end (even at level 0) until OFF or `Sxx`. A voice cut by another lane's key-on has ended and is not held.

**Key-on** (note *n*, lane *l*): if the lane's instrument is not a defined slot or its kind cannot play on this lane,
nothing happens (the lane's old voice keeps sounding). Else the old voice of this lane ends; every POKEY channel the
new voice takes is taken from its holder (that voice is **cut**: it ends and releases all its channels); channels the
old voice held that the new one does not get AUDC 0. The voice copies its instrument's flags, filter offset, partner
AUDC and, for samples, the sample index, rate, root and loop; base = target = 64 *n*; slide 0, arpeggio step 0,
vibrato phase 0, relative pitch accumulator 0, envelopes at step 0; samples start at level 0 (AUDC = 0x10 | level).

**Update** (per tick, after the rows; voices in lane order): pitch = clamp(base + 64 · (arpeggio envelope + `0xy`
step) + fine + slide + vibrato + pitch envelope · 4, 0, 6911), vibrato = floor(SINE[phase] · depth / 16) with the
OPL3's 64-entry sine; level = ⌈env · vol / 15⌉ (env the volume envelope, vol the lane volume 0..15); distortion =
`Vxx` if set, else the envelope's. Registers of the voice's channels per kind (AUDC of its own channel = distortion
<< 5 | level, or 0x10 | level for volume only; 16-bit: low AUDF = N & 0xFF, high AUDF = N >> 8; the partner channels
AUDC = partner byte). AUDCTL = shared bits | the bits of every voice (table above). IRQEN = the timer bits of sample
voices still playing. Then the effects advance as the OPL3's (arpeggio step mod 3, slides ±speed clamped to ±6912,
portamento, vibrato phase + speed mod 64, volume · 8 + x − y clamped 0..120 for `Axy`) and the envelopes step (not for
a silenced voice). Lanes without a voice (never keyed, ended or cut) neither update nor advance their effects. A sample
voice whose index lies beyond its sample in the current image stops (live edits).

**Timer interrupt** (`pkp_irq`, the machine calls it with the timers that fired): for each fired timer 1, 2, 4 in that
order whose channel holds a playing sample voice: the next level (index + 1; past the end: the loop start, or the voice
stops: AUDC 0, then IRQEN with its bit cleared); else AUDC = 0x10 | ⌈level · vol / 15⌉. The writes go out through the
same shadow at the interrupt's cycle (an interrupt in the tick's last cycle comes before the next tick's batch). A
level's length depends on the counter's phase at the key-on (the first level lasts up to one period; STIMER `T00` on
the note's row aligns it, but resets every channel).

### Effects

Names are one character; up to 4 columns per channel. "Note" = shapes the note keyed on this row (on a row without
a note: the sounding voice, until the next note); "now" = from this row on; "global" = the whole chip, at tick 0.

| Effect | Parameter | Meaning |
|---|---|---|
| `0xy` arpeggio | x, y semitones | note, plus x, plus y each tick |
| `1xx` / `2xx` slide | 1/64 semitones per tick | now: up / down until `100`/`200`/`300`; a note restarts the offset |
| `3xx` portamento | 1/64 semitones per tick | now: a note on a held voice (see Running a row) glides there |
| `4xy` vibrato | speed x, depth y | now: ±floor(SINE · y / 16) sixty-fourths (y F: ±1.86 semitones) |
| `Axy` volume slide | x up, y down, 1/8 step per tick | now |
| `Bxx` jump | order | global: after this row play order xx |
| `Cxx` volume | 00-0F | now: the lane volume (start 0F); scales envelopes and sample levels |
| `Dxx` break | row | global: after this row play row xx of the next order |
| `Fxx` speed | 01-FF | global, this row included |
| `Gxx` delay | ticks | the lane's row runs at tick xx (not its global effects) |
| `Hxx` high-pass offset | s8 (difference: 01-7F) | note: the clock channel's counter offset of high-pass sounds |
| `Kxx` base clock | 00 64 kHz, 01 15 kHz | global: AUDCTL bit 0 (every base-clocked voice is recomputed: same pitches, coarser) |
| `Nxx` noise generator | 00 17-bit, 01 9-bit | global: AUDCTL bit 7 |
| `Pxx` fine pitch | 80 = none | xx − 80 sixty-fourths (±2 semitones) until the next note |
| `R00` generator restart | — | global: SKCTL initialization-mode pulse: generators and base clocks restart (noise and base-clock phase repeat) |
| `Sxx` note off | tick | release at tick xx of this row |
| `T00` timer restart | — | global: STIMER: every counter reloads, outputs to 1 (aligns high-pass and sample phases) |
| `Vxx` distortion | 00-08 | note: overrides the distortion envelope (08 = volume only) |
| `Wxx` two-tone | 00 off, 01 on, 02 on + force break | global: SKCTL bits 3, 7: timers 1 and 2 resync each other (02: timer 2 only) |

### Position, seek, live edits, audition, mute

- `position()` is the player's (order, row, tick).
- `start(order, row, max_ticks)`: the player runs alone from the song start to find the tick count *n* (bounded by
  `max_ticks`; else the order fallback: the player placed at the order's row 0 with that order's start speed, shared
  AUDCTL bits and two-tone state, `CHIP_SEEK_FROM_ORDER`). Then the machine is reset exactly as a fresh one and **every
  one of the *n* ticks runs as `tick` runs it** — player, interrupts, the whole chip clocked, mixer, blip_buf and the
  coupling filter — with the frames discarded. After a seek from the start the output is sample-identical to straight
  play reaching that row. Cost: the event-driven chip's cost for the skipped stretch (suggested `seekTicks` 12000).
- `apply_image`: the OPL3's rules (1)-(3) and (5) (clamping, speed, re-read jumps and delays, multispeed and machine
  from the next tick); (4) becomes: voices keep their kind, channels and sample choice from their key-on and read
  envelopes and sample bounds from the new image by slot and index (a sample index or level beyond the new data stops
  the sample). A machine change (NTSC ↔ PAL) applies at the next tick (clock and frame length).
- `audition(channel 0..3, note 0..107, slot 1..64, fine −128..127)`: reset; the player keys the note on that lane
  (taking its channels; `fine` added like `Pxx`); ticks update the voice (envelopes, samples) without rows. A sound
  that cannot play on that lane, an undefined slot or arguments out of range: `CHIP_ERR_IMAGE`. `release()`: 0 before
  any tick or when the voice is silent; else OFF from the next tick (state only: the next tick's batch carries it) and
  `tick` returns 0 once the voice is silent for good — a silenced voice (no volume release point: at once), level 0
  with every envelope held at its end, or a sample stopped — or after 10 s (600 / 500 ticks × N).
- `mute(lane)`: the mix leaves out the POKEY channels the lane's voice holds (its own and the taken ones) while it
  holds them; the chip, the player and the VGM are unchanged; unmuting resumes mid-note. `start`/`audition` clear mutes.
- `tick`: one player tick and its interrupts: frame cycles / N (NTSC 29868, PAL 35568 at N = 1; exact accumulation),
  ≤ 1928 frames at 96 kHz.

### VGM export

`pk_vgm_render` (`pokey/pokey_vgm.c`) runs the player and the chip's timers (the interrupts' times) headless and logs
the writes as VGM 1.71 for one POKEY: header 0x08 = 0x171, 0x24 = 60 (NTSC) / 50 (PAL), 0x34 = 0xCC, **0xB0 = POKEY
clock** (1789773 NTSC, 1773448 PAL: the format's integer Hz), 0x18 total samples, 0x1C loop offset − 0x1C and 0x20 loop
samples (both 0 without a loop), others 0 (data at 0x100; `pk_vgm_render` writes no GD3, 0x14 = 0). Writes `0xBB reg
val` (reg 0x00-0x0F; IRQEN is not logged: it only routes interrupts to the player; the chip of the render receives it).
Waits from cycle times: samples(c) = floor(c · 44100 / C), exact integers (NTSC c · 77 / 3125, PAL c · 88200 /
3546895), emitted before a write as samples(its cycle) minus the samples waited so far (a write lands at most one
44.1 kHz sample, 23 µs, before its cycle), encoded `0x62` (735), `0x63` (882), `0x70`-`0x7F` (1-16), else `0x61 nn nn`
chunks of at most 65535. The data starts with the reset batch at cycle 0. At the first arrival at (loop order, row 0,
tick 0): the wait up to that tick's cycle, the loop point, then a dump of SKCTL, AUDCTL, AUDF1, AUDC1 .. AUDF4, AUDC4
(the shadow's values, all written) before that tick's batch. The song ends at the second arrival (or the tick limit,
then without a loop): the wait up to that tick's cycle, then `0x66`. Hosts' entry point `pokey/pokey_export.h`
(self-contained for cinterop): `pk_export_vgm(image, 65536, max_ticks, title, author, date, &size, &problem)`
validates the image (`problem` = the loader's reason, or "image size", "out of memory"), renders the file (measured
first, then rendered into one allocation) and appends a GD3 1.00 tag as the OPL3's (system "Atari 8-bit POKEY (Thor
Tracker)"), freed with `pk_export_free`; control thread only. Kotlin `PokeyVgm.renderer` is registered by `ChipAudio`
(JNI `nativePokeyVgm(image, maxTicks, title, author, date)`, UTF-8 byte arrays, `IllegalArgumentException(problem)` on
rejection) and the Deck (`DeckPokeyVgm` over cinterop `decknative`).

### Ops behaviour

| Op | Behaviour |
|---|---|
| `load_image` | copy, then `pk_load_image`; `image_storage` = `sizeof(PokeyImage)` |
| `start(order, row)` | above; `seek_mode` from start or order |
| `apply_image` | above; ignored while auditioning |
| `audition`, `release` | above (`channel` = the lane, POKEY channel 1-4 = 0-3) |
| `mute(channel 0-3)` | above |
| `position` | the player's |
| `tick` | one tick: NTSC 801-802 frames at 48 kHz (PAL 962-963; multispeed N: / N), ≤ 1928 at 96 kHz; 0 after a released audition ended; never `CHIP_ERR_FAULT` |

The machine (`pokey/pokey_machine.c`) owns one `PokeyChip` (whose generator tables `pk_init` builds once at creation:
131071 steps), the player, a copy of the image, Altirra's mix table scaled to integers (× 16384, `PKM_MIX_SCALE`), one
blip_buf (machine clock → host rate, its rates reset when the image's machine changes) and the coupling filter's state
(doubles). The chip's output callback adds a step to blip_buf at the cycle of every change of the audible levels;
mutes mask the levels of the channels a muted lane's voice holds (re-mixed after every tick's batch, since voices take
channels there, and on every `mute`). Nothing in `tick`, `apply_image`, `mute` or `release` allocates or locks.

### Native build and sources

`pokey/pokey_engine.c` (the ops table `pokey_chip_ops`, registered in `chip_engine.c`), `pokey_machine.c`,
`pokey_player.c`, `pokey_chip.c`, `pokey_image.c` and `pokey_vgm.c` are C11 in the `chip_engines` library
(`CMakeLists.txt`, `POKEY_DIR`), the Deck's native build (`deck/build.gradle.kts`; `pokey_export.h` in the cinterop
`decknative.def`), the host test script and every render script that links `chip_engine.c`
(`tools/audio/render_{nes,sid,opl3,ym,pokey}_image.sh`). They use blip_buf and `vgm_gd3.c`, nothing else; no
generated files.

### Preparing

Nothing to prepare: the POKEY system has no driver binary, no generated tables (the vibrato sine is copied into
`pokey_player.c` from `generated/opl3_tables.h`'s `O3_SINE`) and no fixtures. The host test builds its test song in C
(`app/src/test/cpp/pokey_test_song.h`) and writes the images and traces the Python reference reads.
`core/.../backend/PokeyPlayerImage.kt` mirrors `pokey_image.h`, `pokey_player.h` (`PKP_*`, `PK_PCM_MIN_PERIOD`),
`pokey_machine.h` and `pokey_vgm.h` by hand: change them together.

### Verification

**Not run yet** (written source-only; the integrator runs it). `tools/audio/run_native_host_test.sh [dir]` builds and
runs `app/src/test/cpp/pokey_machine_test.c` on the test song (`pokey_test_song.h`: 4 orders of 16/32/16/24 rows,
speed 6, loop order 1, 13 instruments covering every kind, 2 samples; NTSC, and a PAL copy). It checks:

- **The chip** against the Altirra manual's hardware numbers: the 4- and 5-bit generators' patterns
  `000011101100101` / `0000011100100010101111011010011` from the reset state, channel *c* *c* cycles late; maximal
  sequences (2^(n−1) − 1 ones per period; the 9-bit one not periodic in 7 or 73); initialization mode reading 1 / 0;
  periods N + 4, N + 7 (linked), (N + 1) × 28 and × 114 from the output edges; 64 kHz AUDF 72 = 437.8 Hz (NTSC);
  equal tones on channels 1 and 3 cancelling (constant mix) while 1 and 2 add; STIMER leaving channels 3 and 4 on and
  1 and 2 off (also with the high-pass flip-flops on); two-tone with force break: timer 2 at 84 cycles lets timer 1
  set to 85 cycles fire once after STIMER and pre-empts it at 86. 13 chip cases (fast, 64 kHz, 15 kHz, linked,
  high-pass, STIMER, initialization mode, two-tone with and without break, volume only, timer interrupts with IRQEN
  changes, 9- vs 17-bit noise, PAL distortions): **`pk_run` = `pk_run_reference`** (identical edges and interrupts),
  written to `pokey_chip_cases.txt`.
- **Images**: the derived `PkInfo` (rows, start speed / AUDCTL bits / two-tone per order); 57 single-byte corruptions
  each rejected with its reason; loading is a pure function.
- **The song**: 1500 ticks NTSC and PAL into `pokey_trace.txt` / `pokey_trace_pal.txt` (`T tick order row tickInRow
  cycle:` and `I cycle timers:` lines with ` R=VV` writes, the format the reference reads); every kind sounding,
  samples on timers 1, 2 and 4, every distortion and volume only written, every AUDCTL bit used, two-tone, force break,
  the `R` pair and STIMER written; a second chip fed the same writes with `pk_run` and with `pk_run_reference` produces
  identical edges and interrupts over all 1500 ticks, and its interrupts are exactly the ones the player received;
  every tick exactly one frame of cycles; 30 s (`pokey_song.wav`) non-silent in every second, within the amplifier
  clamp, mono on both channels, bit-identical on a second start; loop passes stepping through identical positions,
  each arrival with the first pass's speed, AUDCTL bits and two-tone state, equal to the VGM's loop.
- **Seek**: eight targets landing on their row with **60 ticks of output sample-identical to straight play**; the order
  fallback for rows only reached through `D02` and for a small budget, then the order's start speed and its `F05`;
  targets outside the song rejected.
- **Live edits** (per-tick hashes of every batch and its cycle): the same image and a re-laid-out one before every
  tick = straight play; a note edit applied at five ticks up to its row = the edited song's straight play; a tempo edit
  ahead of the playhead = the tempo song, behind it rows of 9 then (undone) 6 ticks; the song cut under order 3 clamps
  and continues in the loop order; ×2 multispeed and then PAL applied live change the next tick's cycles.
- **Audition**, pitch measured from the chip's output edges: A-4 on 8-bit 64 kHz AUDF 72 = 437.81 Hz; `fine` +64 =
  AUDF 68; A-4 on the fast 16-bit counter within its resolution (N 2027, ±0.22 Hz); A-7 on fast 8-bit; distortion C
  on C-4 periodic in exactly 15 fires; the difference saw on C-3 beating at C/P(N) − C/P(N + 3) (pulse widths wrap
  once per beat); a once sample giving exactly 600 timer-4 interrupts for its 600 levels then stopping (AUDC4 0, IRQEN
  cleared), each level one period; a looping sample at the 114-cycle minimum period still playing; release ending with
  the volume envelope's tail (4-10 ticks), a voice without a release point silent at the next tick, a cut before any
  tick; argument and lane limits (`pokey_tone.wav`).
- **Mute**: writes unchanged; lanes holding no channel leave the output bit-identical, the lane holding them silences
  it; all lanes silent after 100 ms, unmuting resumes, a mute before the first tick holds.
- **Sticky portamento over samples** (`pokey_porta.bin`, 24 rows at speed 6, every lane C-4 `310` then E-4 on row 8
  and G-4 on row 16, into `pokey_trace_porta.txt`): a 200-level one-shot at 7990 Hz, over long before row 8, keys on
  afresh on each note — the row's tick-0 batch writes AUDC1 at its first level and IRQEN with timer 1, then 199 timer 1
  levels and the stop; nothing between notes; base = target (no glide); audible on rows 0, 8 and 16 alone and silent
  on rows 7 and 15. An 8000-level one-shot still playing at row 8 glides on (index kept) and, over by row 16,
  restarts; a looping sample and a tone whose one-shot volume envelope ended at 0 stay held and glide.
- **Multispeed** ×2: 120 ticks = 60 frames of cycles and host frames, 20 rows.
- **VGM**: header fields, the file parsed from the format alone (waits sum to the header's total = samples of the
  end cycle) and its 0xBB writes equal, sample for sample, to the machine's writes (IRQEN left out) with the reset
  batch first and the loop dump at the loop offset; PAL header and length; `pokey_song.vgm`, `pokey_song.bin`,
  `pokey_song_pal.bin` written. The entry point: GD3 fields decoded back, rejected images, tick limit without loop,
  today's date by default. The ops table as the hosts drive it; tick cost without a slow path (POKEY ticks are not
  flat: the chip's work follows the counters' fire rates and sample interrupts; after warm-up no tick above 3× the
  99th percentile of thread CPU time, none above a quarter of the tick's 16.7 ms).

`tools/audio/run_native_host_test.sh` also runs `wav_render_test.c` on the demo `scanline-cruise.pkimg` and the
shortest song `pokey-shortest.pkimg` (written by `tools/build.sh exportSamples`). `tools/audio/render_pokey_image.sh
image.pkimg out.wav [seconds] [mute=14] [--vgm out.vgm] [title=..] [author=..] [date=..]` renders an image through the
ops table as the hosts do (`tools/audio/pokey_render.c`), and its VGM through `pk_export_vgm`. **Bench**:
`tools/audio/build_pokey_bench.sh [dir]` builds `tools/audio/pokey_bench.c` for the host and with the NDK (static
arm64, `-O2` and `-O3`): cold ticks, warm ticks against the chip alone fed the same writes, paced ticks, the player
alone, `apply_image`, audition setup, seeks and 12000 clocked ticks.

- `tools/audio/pokey_reference_check.py <dir> [--vgm2wav PATH]` (developer evidence; the audio part needs numpy) — NOT
  RUN yet. `tools/audio/pokey/pokey_ref.py` is a second implementation of the player and machine, written from this
  section without the C sources, on `tools/audio/pokey/pokey_chip_ref.py`, a POKEY model that steps every cycle (from
  Altirra's manual and generator formulas and MAME's cycle order). The script requires: the host test's
  `pokey_trace.txt` (NTSC), `pokey_trace_pal.txt` (PAL) and `pokey_trace_porta.txt` (sticky portamento over samples)
  reproduced line for line (every tick's batch at its cycle, every timer interrupt with its writes); `pokey_song.vgm`
  reproduced byte for byte (36000-tick limit) and parsed from the format alone (1.71 header, POKEY clock at 0xB0,
  frame rate, data at 0x100, no GD3, waits summing to the header's total, the loop point on a command boundary
  followed by the SKCTL/AUDCTL/AUDF/AUDC dump equal to the registers the
  trace reaches before the loop order's first tick, writes and their 44.1 kHz times = the reset batch and trace writes
  without IRQEN); every case of `pokey_chip_cases.txt` replayed through the reference chip (stepping every cycle, and
  event-driven: both agree) giving exactly the engine's output changes; the reference generators' 4- and 5-bit
  patterns equal to the manual's. With `--vgm2wav`, libvgm's player and its POKEY core (MAME's, a separate emulation)
  render the VGM, compared with the engine's 30 s `pokey_song.wav`, DC removed (MAME's core is not AC-coupled):
  20 ms loudness-envelope correlation > 0.8, 60 Hz-4 kHz log-spectrum correlation > 0.75, dominant pitch class equal
  in > 60 % of 100 ms windows, level ratio within 0.25-4 (thresholds chosen before any render).

### Hosts: threads and costs

Not measured yet (run the bench). By construction: the callback runs `tick` (the player's tick, its pitch searches
only when a voice's inputs change, the event-driven chip — work proportional to counter underflows and fires, i.e. to
the pitches and sample rates playing, up to ~262 timer interrupts per tick for a sample at the 114-cycle minimum — the
mix, blip_buf and one filter step per host sample), `apply_image` (a 64 KB copy plus a few row decodes) and `mute`;
the control thread runs `start` (machine reset and every tick up to the target fully clocked: the cost of playing
that stretch, without the audio output; `seekTicks` 12000 ≈ 200 s of song) and `audition` (reset only) on spare
machines, and creation (allocation and the generator tables). Konkr-class devices: to be measured with
`pokey_bench-arm64`.
