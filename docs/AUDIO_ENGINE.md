# Audio engine: hUGEDriver preview and the NES engine

Preview playback runs the **actual pinned hUGEDriver 6.1.3 binary** on an embedded Game Boy
Color emulator (binjgb) inside `libthortracker_audio.so`, output through AAudio (Android) or SDL3
(Steam Deck). There is no second song sequencer: notes, instruments, effects, timing and channel
behaviour are whatever the driver code does on the emulated CPU and APU.

The NES system (`"nes-famistudio"`) follows the same principle: the FamiStudio sound engine 4.5.3
(FamiTracker tempo) runs on an emulated 6502 + 2A03, compiled into both hosts; its image layout,
seek, live-edit and audition contract are in [NES](#nes-famistudio-sound-engine-on-a-6502--2a03-nes-famistudio).

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

The host's held-audition cap on the Deck is about a minute of the system's `tickHz` ticks.

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
| m6502.h (floooh/chips) | `app/src/main/cpp/third_party/chips/` (`m6502.h`, `LICENSE`) | github.com/floooh/chips commit `9e88298ce56319953ac7a43213a1120359f7a3a6`, `m6502.h` sha256 `b3fc32a0…868d46e` | zlib — keep the notice; it is in `NOTICE.txt` |
| blip_buf 1.1.0 | `app/src/main/cpp/third_party/blip_buf/` (`blip_buf.c/.h`, `license.txt`, `readme.txt`, `changes.txt`) | Shay Green's release archive `blip_buf-1.1.0.zip` (code.google.com archive of blip-buf, sha256 `37a9b4f0…2b398e4c`); `blip_buf.c` sha256 `66c4a3f3…0c7573`, unmodified | LGPL-2.1-or-later (GPL-3.0-compatible); source ships in the repo, notice in `NOTICE.txt` |
| cc65 `ca65`/`ld65` | offline only (`tools/audio/prepare_nes_player.py`) | Ubuntu 24.04 package `cc65 2.19-1` ("ca65 V2.18 - Ubuntu 2.19-1"), checked by the script | tools are not shipped |

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
library `chip_engines` (`chip_engine.c`, `huge_chip.c`, `huge_machine.c`, which `#include`s
binjgb's `emulator.c`, plus `common.c`; and the NES files `nes/nes_chip.c`, `nes/nes_machine.c`
(which `#include`s the header-only `m6502.h`), `nes/nes_image.c`, `nes/nes_apu.c` and `blip_buf.c`; always with
`NDEBUG`) and the shared `thortracker_audio` (`audio_engine.cpp`, `audio_jni.cpp`) linked with NDK
`aaudio`, `log`, `m`, `dl`. No third-party Java/Gradle dependency. Everything is C, so the Deck
compiles the same files with Kotlin/Native's clang (`deck/build.gradle.kts`, `buildEngine`).

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
  target = fault). Configuration: NTSC, smooth vibrato, DPCM support compiled in at
  `FAMISTUDIO_DPCM_OFF = $C000`, **FamiTracker tempo** with delayed notes and cuts
  (`FAMISTUDIO_USE_FAMITRACKER_TEMPO`, `..._DELAYED_NOTES_OR_CUTS`), and every 2A03 feature
  FamiStudio's own FamiTracker-tempo NSF kernel enables (volume track and slides, pitch track, slide
  notes incl. noise, vibrato, arpeggio, duty-cycle effect, DMC delta counter, release notes, phase
  reset, extended instrument range) except the DPCM bank-switching/extended-range encodings; no SFX.
  So any 2A03-only FamiTracker-tempo FamiStudio export without DPCM bank switching plays. The
  same bytes are in Kotlin as `core/.../backend/NesPlayerImage.kt` (generated) for the `.nsf` export.
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
  3-4 cycle delay, immediate 5-step clock, IRQ flag), the DMC output level (`$4011`), and the
  nonlinear mixer (exact NESdev formulas, so the idle triangle's held step and the DMC level
  change the other channels' loudness as on hardware). Not emulated: DPCM sample playback and DMC
  IRQ (samples are silent), IRQs to the CPU, same-cycle length/halt quirks, the APU-cycle phase
  of pulse/noise timers (counted in CPU cycles), triangle periods below 2 (frozen, avoiding
  ultrasonic pops). Silent channels' timers are not stepped (their phase is reset by the write that
  makes them audible); timers run event to event, not cycle by cycle.
- **Output**: the mixed level as band-limited steps into blip_buf (blargg's band-limited synthesis,
  the technique of game-music-emu and FamiStudio's own NesSndEmu), then a first-order 37 Hz
  high-pass and 14 kHz low-pass (Famicom-style output stage), × 2.0, L = R. The test song peaks at
  0.67 (rms 0.15).

### CPU memory map

| Range | Use |
|---|---|
| `$0000-$0007` | engine zero page |
| `$0100-$01FF` | stack (host calls start at S = `$FB` with the idle return address pushed) |
| `$0200-$02AB` | engine RAM (`NES_RAM_BASE`/`NES_RAM_SIZE`); the prepare script fails if it reaches `$07C0` |
| `$07C0-$07FF` | audition streams, written by the machine (`NES_AUDITION_RAM`) |
| `$4000-$4017` | APU, writes timestamped to their CPU cycle; `$4015` reads status |
| `$8000-$8B11` | player image |
| `$A000-$FFF9` | song region (`NES_SONG_BASE`..`NES_SONG_END`, 24570 bytes): the image's FamiStudio data |
| `$FFFA-$FFFF` | vectors → `nes_player_trap`, written by the machine |

Everything else reads 0 and ignores writes.

### Image layout (what the Kotlin lowering produces)

Numbers are generated into both `nes_player_image.h` (`NES_IMAGE_*`) and `NesPlayerImage.kt`; the
native loader is `nes/nes_image.c` (`nm_load_image`), which rejects anything off-contract with a
reason (`NesImageInfo.problem`; the host test prints them) — mirror its checks in Kotlin validation
for user-facing messages. `image_size` = 26650 bytes, little-endian:

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | `"TTNS"` |
| 4 | 4 | player id = first 4 bytes of the player image sha256 (`NesPlayerImage.PLAYER_ID`, currently `0x47B5B60E`): an image only plays on the player build it was lowered for |
| 8 | 2 | song base `$A000` |
| 10 | 2 | data size: bytes of song data used; the rest of the region must be zero |
| 12 | 1 | song index: 0 |
| 13 | 1 | layout: 1 = Thor Tracker (below). 0 = raw FamiStudio export (tests and tools only: bytes 18/19/20 = rows per order, order count, loop order or `$FF`; no directory; plays, seeks from the start, live apply only copies bytes) |
| 14 | 1 | instrument count, 1..64 |
| 15 | 1 | 0 |
| 16 | 2 | envelope count = highest used directory index + 1 (≤ 512) |
| 18 | 14 | 0 |
| 32 | 2048 | envelope directory: 512 entries of {u16 address, u8 length (2..255), u8 kind (0 volume, 1 arpeggio, 2 duty, 3 pitch)}; address 0 = unused (all 4 bytes 0); entries ≥ count all zero |
| 2080 | 24570 | song region, placed at `$A000` |

**Song region** (all pointers absolute, so data is lowered for `$A000`):

- FamiStudio song list with exactly one song: `u8 1`, `word instruments`, `word samples−4`
  (unused: no DPCM), five channel stream words (pulse 1, pulse 2, triangle, noise, DPCM), `word
  tempoPal = 256*T/125`, `word tempoNtsc = 256*T/150` (T = FamiTracker tempo; 150 → 256).
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
  | `$01-$3F` | note, engine note = byte + 12 (13..75) |
  | `$40 n` | note, engine note n (1..96) |
  | `$00` | stop |
  | `$50 step from to` | slide note (ends on `to`); FamiStudio's step (see below) |
  | `$45` | release (volume envelope jumps to its release index; nothing if it has none) |
  | `$81\|(k<<1)` | empty run: this row + k more (k 0..63) |

  | Prefix | Meaning | Channels |
  |---|---|---|
  | `$46 s` | speed (Fxx), 1..127; put it before `$47` so it applies at the row start | 0-3 |
  | `$47 d` | note delay: the rest of the row plays at frame d+1 of the row (FamiStudio NoteDelay N = `$47 N−1`); at most one per row, 0..127 | 0-3 |
  | `$48 c` | cut the note at frame c of the row (FamiStudio CutDelay N = `$48 N`), 0..127 | 0-3 |
  | `$43` | next note without attack | 0-3 |
  | `$70\|v` | volume track 0..15 (multiplies the envelope) | 0-3 |
  | `$51 step target` | volume slide | 0-3 |
  | `$4E f` | fine pitch: signed period offset (+ lowers the pitch) | 0-2 |
  | `$49 lo hi` / `$4A` | vibrato envelope override (directory entry, kind pitch) / clear | 0-2 |
  | `$4B lo hi` / `$4C` / `$4D` | arpeggio envelope override (directory entry, kind arpeggio) / clear / reset | 0-3 |
  | `$4F d` | duty 0..3 | 0, 1, 3 |
  | `$53` | phase reset | 0, 1 |
  | `$80\|(i<<1)`, `$54 i` | instrument (< count) | 0-3 |

  The DPCM channel's rows are empty runs only. Anything else (`$52`, `$55`+, opcodes on a channel
  whose engine table has no entry, which would write out of bounds) is rejected.
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
then channels 0..4), the NTSC tempo step and a sorted index of the directory.

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
  row 15, 416 frames: 4.8 ms); suggested `seekTicks` 12000 (~3.3 min, ~140 ms).

### Live edits (`apply_image`, realtime-safe, ~5 µs)

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

**Contract for the builder**: any layout that passes `nm_load_image` relocates — data may move
freely between images. Keep directory indices stable for instrument envelopes (e.g. slot·4 + kind)
so sustained notes follow envelope edits; effect envelopes may be renumbered. Raw layouts: bytes
are copied (correct only if nothing moved). The host test applies a fully re-laid-out image before
every one of 1080 frames and gets register writes identical to straight play.

### Audition

`audition(channel 0..3, note, slot, fine)` resets the (spare) machine with the image, starts
its song, then points all five channel streams into RAM at `$07C0`: silent channels play `$FF $42`
(64-row empty runs, looping); the auditioned channel plays `$54 instrument`, `$4E fine` (pulses and
triangle only), `$40 note`, then the silent loop — so the note sustains through the real engine
with the image's own instrument table and envelopes. Arguments: engine note 1..96 (absolute − 11),
instrument = the cell's 1-based slot as the hosts pass it (`nes_chip.c` plays table index slot − 1;
slot 0 is rejected; `nm_audition` itself takes the table index), fine = signed period offset −128..127 like `$4E` (ignored on
noise). DPCM (4), raw images and out-of-range arguments are `CHIP_ERR_IMAGE`.

`release()`: if no tick has run, or the instrument's volume envelope has no release index, returns 0
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
| `mute(channel 0-4)` | forces the channel's level to 0 in the APU mixer |
| `position` | above |
| `tick` | one frame: play routine + APU, 798-800 frames at 48 kHz; 0 after a released audition ended |

### Remaining for step 5 (not native)

`.nsf` export is done (`NsfExporter`, docs/BACKENDS.md: a 128-byte NSF header — load `$8000`, init
`$8000`, play `$8003`, NTSC speed 16639 µs, no bank switching — + `NesPlayerImage.BYTES` + zero fill
to `$A000` + the lowered data). Still to measure on the Thor: realtime cost per tick and seek cost
per frame (dev PC: 114 µs per tick, 147× realtime; the engine's worst frame 4828 CPU cycles), and
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
and the test fixture `app/src/test/cpp/nes_ft_song.h`. The fixture comes from
`tools/audio/nes/ft_test.txt` (FamiStudio text, FamiTracker tempo, written for these tests: Fxx
6/7/4/8, note delays and cuts, release notes, volume track and slide, fine pitch, vibrato,
arpeggio instrument and effect, slides on pulse and triangle, duty effect, phase reset, no-attack
notes, loop to order 1) as exported by FamiStudio 4.5.3 itself (`ft_test_ca65.s`, committed;
`--famistudio` re-exports and checks it is unchanged). The export is linked with ld65 at `$A000`
(raw fixture) and parsed by the script, which must give the same bytes, then rearranged into the
Thor layout without changing any row's bytes, plus variants (every block moved; one note and one
envelope edited; cut to two orders). Two runs produce identical files.

### Verification

- `tools/audio/run_native_host_test.sh [dir]` also builds and runs `app/src/test/cpp/nes_machine_test.c`
  (2026-11-07: **237 checks, 0 failures**; Game Boy unchanged at 145). APU: as in step 4 (pitch by
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
- Evidence written to the directory: `nes_ft.wav` / `nes_ft_tonal.wav` (30 s, 48 kHz), `nes_trace.txt`
  (per-frame APU writes), `nes_ft.nsf` (player + FamiStudio's export).
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
- Not done: listening on hardware, blargg's APU test ROMs (they need a PPU and IRQs), a comparison
  with a real NES capture, Thor measurements.
