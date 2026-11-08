# Audio engine: hUGEDriver preview, the NES engine, the SID engine and the OPL3 engine

Preview playback runs the **actual pinned hUGEDriver 6.1.3 binary** on an embedded Game Boy
Color emulator (binjgb) inside `libthortracker_audio.so`, output through AAudio (Android) or SDL3
(Steam Deck). There is no second song sequencer: notes, instruments, effects, timing and channel
behaviour are whatever the driver code does on the emulated CPU and APU.

The NES system (`"nes-famistudio"`) follows the same principle: the FamiStudio sound engine 4.5.3
(FamiTracker tempo) runs on an emulated 6502 + 2A03 with all five channels, DPCM samples included;
its image layout, seek, live-edit and audition contract are in [NES](#nes-famistudio-sound-engine-on-a-6502--2a03-nes-famistudio).

The C64 SID system (`"c64-sid"`, design step 6, native spike) runs the GoatTracker 2.77 playroutine
on an emulated 6510 + reSID 1.0 (MOS 6581 or 8580, PAL); its image layout, timing, seek, audition and
what the Kotlin lowering must produce are in [SID](#c64-sid-goattracker-2-playroutine-on-a-6510--resid-c64-sid).

The OPL3 system (`"opl3-fm"`, design step 8, native spike) has no console driver to run: it plays songs with
Thor Tracker's **own player** (C, specified in this document and mirrored by an independent Python reference)
on a YMF262 emulated by Nuked-OPL3 1.8, and exports VGM. Core choice, image layout, player specification,
effects, seek, live edits, audition and VGM export are in [OPL3](#opl3-thor-trackers-own-player-on-a-ymf262-opl3-fm).

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
| `position(&order, &row, &tick)` | row the next tick processes |
| `tick(out)` | one driver tick: ≤ `CHIP_MAX_TICK_FRAMES` float stereo frames, 0 = released note ended, `CHIP_ERR_FAULT` |

The hosts keep everything that is not about the chip: triple-buffered image and machine hand-over
at tick boundaries (Android), image versions, latency-compensated position, audition mixing and
channel borrowing, the Deck's held-note cap and `da_peak` diagnostics. The host test drives the
Game Boy through the ops table as well as through `hm_*` directly.

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
`nes/nes_machine.c`, `nes/nes_image.c`, `nes/nes_apu.c` and `blip_buf.c`; the SID files `sid/sid_chip.c`,
`sid/sid_machine.c`, `sid/sid_image.c`; the OPL3 files `opl3/opl3_chip.c`, `opl3/opl3_machine.c`,
`opl3/opl3_player.c`, `opl3/opl3_image.c`, `opl3/opl3_vgm.c` and Nuked-OPL3's `opl3.c`; always with `NDEBUG`),
a static C++17 library `resid` (reSID 1.0 and `sid/sid_resid.cpp`, its C interface; `-O2 -w`) that
`chip_engines` links, and the shared `thortracker_audio` (`audio_engine.cpp`, `audio_jni.cpp`) linked with
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
3. **Envelopes**: every engine envelope pointer (11 envelope slots, 3 pitch slots) is found in the
   old directory and moved to: the same index if its bytes are unchanged; else an entry of the same
   kind with identical bytes (effect envelopes renumbered by the lowering); else the same index
   edited — keeping the read position if it is still an instruction start, else restarting that
   envelope; else the engine's silent dummy envelope. So a sustained note follows an edit of its
   own instrument's envelope (GB: the next note does) unless an identical copy of the old envelope
   remains elsewhere.
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
move freely between images. Keep directory indices stable for instrument envelopes (e.g. slot·4 +
kind) so sustained notes follow envelope edits; effect envelopes and DPCM mappings may be renumbered
(the streams carry the new numbers). Raw layouts: bytes are copied (correct only if nothing moved).
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
requires identical register writes in every tick. Songs export as VGM (the player's register log). The Kotlin
system, lowering and UI are not written yet; this section is their contract.

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
the Konkr): scaled by that ratio an OPL3 tick costs ~1.6 ms on a Konkr-class device (the ×4 dev-PC rule of
thumb gives ~2.1 ms), about 10 % of a 16.7 ms tick. ymfm would cost a third of that if a device ever needs it;
it is also the candidate for the YM2612 later (its OPN2 shares the framework; Nuked-OPN2 is the accurate
alternative there). Device numbers: push `/tmp/opl3_bench/opl3_bench-arm64` (the engine) and
`/tmp/opl3_bench/opl3_core_bench-arm64` (the two cores), static executables, and run them with `30` /
`10` (seconds) as arguments; neither has been run on a device yet.

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

**Should** (not checked): `Fxx` on the loop order's first row when the speed changes anywhere (otherwise the
second pass plays at the speed the song ended with: different from the first pass and from the VGM's loop);
an instrument on the first note of every order (an order is also entered by the loop, the seek fallback and a
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
stamps 0; speed = header speed; position (0, 0, tick 0); writes `0x105 = 1` (OPL3 mode), then 0xBD = header
byte 7 (if not 0).

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
   the loop order.

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
- `start(order, row, max_ticks)`: the chip is reset and the **player runs alone** from the song start (no chip
  clocking: 0.19 µs per tick on the dev PC) until the next tick starts (order, row) (`CHIP_SEEK_FROM_START`),
  then the chip receives the player's register state: 0x105, 0x104, 0x08, 0xBD, every operator register
  (0x20-0x35, 0x40-0x55, 0x60-0x75, 0x80-0x95, 0xE0-0xF5 of both banks), 0xC0-0xC8 (through the mute routing),
  0xA0-0xA8, then 0xB0-0xB8 (key-ons last), non-zero values only. The player state is exact — its writes after a
  seek equal straight play's — while the chip's own state is not reconstructed: notes held at the target restart
  their envelopes from the attack, and release tails of notes keyed off before it are silent. Measured loudness
  0.5-1.5 s after a seek is within 0.6-1.6× of straight play (checked). Beyond `max_ticks` (Bxx loops, unreachable
  rows), the player is placed at the order's row 0 with the speed straight play starts that order with and replays
  its earlier rows (`CHIP_SEEK_FROM_ORDER`; this replay has its own bound, < 256 rows × 255 ticks); a row behind a
  `Dxx` of the same order cannot be reached (`CHIP_ERR_IMAGE`). Instruments, volumes and slides from earlier
  orders are lost there, as on the other systems. Suggested `seekTicks`: 12000 (2.3 ms on the dev PC); much
  larger budgets are affordable.
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
  0x34 = 0xCC (data at 0x100), 0x5C = 14318180 (YMF262 clock). No GD3 tag (the Kotlin export may append one and
  set 0x14).
- Data: the reset batch; then per tick: at the first arrival at (loop order, row 0, tick 0) the loop point and a
  dump of the whole register state (0x105, 0x104, 0x08, 0xBD, every operator register of both banks, then
  0xC0-0xC8, 0xA0-0xA8, 0xB0-0xB8 for voices 0..17, all values including zeros), so each pass of the loop starts
  from the same registers although the player's writes are diffed; the key-off phase writes; if there were any,
  a one-sample wait (`0x70`); the other writes; the rest of the tick's wait (44100 / (60 · N) samples by exact
  accumulation: 735 = `0x62` at N = 1; `0x70`-`0x7F` for 1-16, `0x61 nn nn` otherwise). Writes are `0x5E reg val`
  (bank 0) and `0x5F reg val` (bank 1). The song ends at the second arrival at the loop point (or at the tick
  limit, then without a loop), then `0x66`.
- For the Kotlin export: the hosts already link this code; exposing `o3_vgm_render` through JNI (Android) and
  cinterop (Deck) keeps one player implementation, checked against the Python reference by the host test. A
  pure Kotlin port would be a third implementation and would need the same golden trace (open question in
  `docs/BACKENDS.md`).

### Preparing the tables and fixtures

`python3 tools/audio/prepare_opl3.py` (pure Python 3) writes `app/src/main/cpp/generated/opl3_tables.h` (from
`opl3_ref.py`'s table functions) and `app/src/test/cpp/opl3_test_song.h` (the test images: the song, the same
song laid out anew (`relayout=True`: patterns reversed behind a 37-byte gap), a note edit, a tempo edit, the song
cut to two orders and an x2 multispeed variant; the reference player's per-tick writes, key-off phase lengths and
positions for 1500 ticks of three of them; the reference VGM's size, FNV-1a hash and loop; byte offsets for the
corruption checks). Rerun it after changing the player specification, the reference player or the test song.

### Verification

- The test song (`tools/audio/opl3/opl3_song.py`, 4 orders of 16/32/16/24 rows, `B01` back to the loop order 1):
  12 instruments (2-op FM and additive, all four 4-op algorithms, a sine, a kick sweeping C-5 → C-3 at 2.5
  semitones per tick, a snare sweeping +7 → −5, a hi-hat), four chord shapes (one with an inversion), every effect,
  multiple effect columns, OFF cells, delays and cuts, empty runs, patterns used twice; order 1 drains the chord
  pool and steals, channels switch between 4-op and 2-op instruments, order 2 changes speed and breaks to order 3
  row 2.
- `tools/audio/run_native_host_test.sh [dir]` runs `app/src/test/cpp/opl3_machine_test.c` — 2026-11-08:
  **257 checks, 0 failures** (Game Boy 145, NES 336, SID 151 unchanged): the pitch table (A-4 = 580/block 4 =
  439.991 Hz; all notes ≤ 2.59 cents); 42 single-byte corruptions each rejected with its reason; **the machine's
  writes equal the Python reference player's in all 1500 ticks** (10026 writes, up to 178 in a tick; all 9 pool
  voices keyed at once; 4-op pairs switching on and off; the kick sweeping) with identical positions; 30 s
  non-silent in every second, no clipping, bit-identical on a second start; eight seeks (up to 435 ticks, ≤ 130
  µs) landing on their row with 200 ticks of writes equal to straight play, loudness after a seek, the order
  fallback for rows only reached through `D02` and for a small budget, targets outside the song rejected; live
  edits: the same image and the re-laid-out image alternating before every one of 1500 ticks write the reference,
  a note edit applied at five ticks from the song start to the tick before its row equals straight play of the edited song, a tempo edit ahead
  of the playhead equals the edited song and behind it gives rows of 9 then (undone) 6 ticks, the song cut under
  order 3 clamps and continues in the loop order, x2 applied live halves the tick; audition: **A-4 measured
  439.991 Hz** on the sine instrument, held, one voice keyed, release ends after 52 ticks, `fine` +64 = A#4
  (466.16 Hz), a 4-op instrument sets its pair, the kick sweeps 523.25 → 130.81 Hz, a cut before any tick,
  argument limits; mute leaves the writes equal to the reference and silences; multispeed x2 = 120 ticks per second;
  **VGM bytes equal the reference player's**; the ops table as the hosts drive it. Evidence in the directory:
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
  dump), plus a test-song speed change across the loop (now `F06` on the loop order's first row, and a "Should").
- **Bench**: `tools/audio/build_opl3_bench.sh [dir]` builds `tools/audio/opl3_bench.c` for the host and with the
  NDK as static arm64 executables (`-O2` as the app, and `-O3`); `adb push` and run `opl3_bench-arm64 [seconds]
  [rate]`. Built 2026-11-08 into `/tmp/opl3_bench/` (with the core comparison bench); not run on a device.
- Not done: listening on hardware, device measurements, a comparison with a real YMF262.

### Hosts: threads and costs

Dev PC (`opl3_bench-host`, 48 kHz, 30 s of the test song, 2026-11-08) and a Konkr-class estimate (×4):

| Work | Thread | Dev PC | ≈ Konkr |
|---|---|---|---|
| `tick` (player + chip + resample) | audio callback | 515 µs mean, p99 686 µs, p99.9 771 µs (chip 496 µs, player 0.3 µs) | ~2.1 ms mean (~1.6 ms scaled from the SID's measured 1.8 ms) |
| `apply_image` (relocating) | audio callback, tick boundary | 2.8 µs mean, worst 7-15 µs | ≤ 0.06 ms |
| machine creation | control | 0.03 ms | ~0.1 ms |
| `audition` setup | control, spare machine | 1.7 µs | ~0.01 ms |
| `start` (seek; 12000 ticks fast-forward) | control, spare machine | 0.13 ms to order 3 row 20; 2.3 ms for 12000 ticks | ~10 ms |

The hosts treat the machine like the SID's (`Engine::create` on the control thread, seeks and auditions on spare
machines, the callback only ticks, mutes and applies images); nothing in `audio_engine.cpp` or `deck_audio.c` is
system-specific.
