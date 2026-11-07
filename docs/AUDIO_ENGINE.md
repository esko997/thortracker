# Audio engine: hUGEDriver preview (and the NES spike)

Preview playback runs the **actual pinned hUGEDriver 6.1.3 binary** on an embedded Game Boy
Color emulator (binjgb) inside `libthortracker_audio.so`, output through AAudio (Android) or SDL3
(Steam Deck). There is no second song sequencer: notes, instruments, effects, timing and channel
behaviour are whatever the driver code does on the emulated CPU and APU.

The NES system (`"nes-famistudio"`, design step 4) follows the same principle: the FamiStudio
sound engine 4.5.3 runs on an emulated 6502 + 2A03. It is compiled into both hosts and proven in
the host test, but nothing in Kotlin uses it yet; see [NES](#nes-famistudio-sound-engine-on-a-6502--2a03-nes-famistudio).

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
| FamiStudio sound engine 4.5.3 | `third_party/famistudio-4.5.3/` (`famistudio_ca65.s`, `NoteTables/famistudio_note_table_{lsb,msb}.bin`, `readme.txt`, `LICENSE`, and the test-only `DemoSource/song_shatterhand_ca65.s`); assembled into `app/src/main/cpp/generated/nes_player_image.h` | github.com/BleuBleu/FamiStudio tag `4.5.3`, commit `c09dca64788606c09fcb5e70db28b9dd1f34be78` (the engine file is unchanged since tag 4.5.0 and still says 4.5.0 in its banner); `famistudio_ca65.s` sha256 `e126a8f3…71fe`, note tables `b917b0eb…ee41` / `b52e4924…69fb`, demo song `59c73131…8c60` | engine: all-permissive ("copyright notice and this notice are preserved"); note tables and demo data: FamiStudio's MIT licence. Both notices are in `NOTICE.txt` |
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
binjgb's `emulator.c`, plus `common.c`; and the NES files `nes/nes_chip.c`, `nes/nes_machine.c`,
which `#include`s the header-only `m6502.h`, `nes/nes_apu.c` and `blip_buf.c`; always with
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

Design step 4 (spike): a native machine proven in the host test, registered in `chip_engine_ops`
and compiled into the Android library and the Deck binary. No Kotlin lowering, UI or host
integration yet (step 5).

### What runs

- **Player image** (`generated/nes_player_image.h`, 2791 bytes at `$8000`): the unmodified
  FamiStudio 4.5.3 engine plus a fixed NSF-style entry table (`tools/audio/nes/nes_player.s`):
  `$8000 nes_player_init` (A = song index → `famistudio_init(NTSC, NES_SONG_BASE)` +
  `famistudio_music_play`), `$8003 nes_player_play` (`famistudio_update`), `$8006
  nes_player_idle` (return address of host calls), `$8009 nes_player_trap` (interrupt/BRK vector
  target = fault). Configuration: NTSC, smooth vibrato, DPCM support compiled in at
  `FAMISTUDIO_DPCM_OFF = $C000`, every 2A03 feature FamiStudio's own NSF kernel enables (volume
  track and slides, pitch track, slide notes incl. noise, vibrato, arpeggio, duty-cycle effect, DMC
  delta counter, release notes, phase reset, extended instrument range) except the DPCM
  bank-switching/extended-range encodings, **FamiStudio tempo mode**, no SFX. Extra features only
  add opcodes, so any 2A03-only FamiStudio-tempo export without DPCM bank switching plays.
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
  high-pass and 14 kHz low-pass (Famicom-style output stage), × 2.0, L = R. The demo song peaks at
  0.56 (rms 0.13).

### CPU memory map

| Range | Use |
|---|---|
| `$0000-$0007` | engine zero page |
| `$0100-$01FF` | stack (host calls start at S = `$FB` with the idle return address pushed) |
| `$0200-$02A2` | engine RAM; `$02A3-$07FF` free (candidate audition scratch, below) |
| `$4000-$4017` | APU, writes timestamped to their CPU cycle; `$4015` reads status |
| `$8000-$8AE6` | player image |
| `$A000-$FFF9` | song region (`NES_SONG_BASE`..`NES_SONG_END`, 24570 bytes): the image's FamiStudio data |
| `$FFFA-$FFFF` | vectors → `nes_player_trap`, written by the machine |

Everything else reads 0 and ignores writes.

### Image layout (what step 5's lowering produces)

`image_size` = 32-byte header + 24570-byte song region, little-endian:

| Offset | Field |
|---|---|
| 0 | `"TTNS"` |
| 4 | player id = first 4 bytes of the player image sha256 (`NES_PLAYER_ID`): an image only plays on the engine build it was lowered for |
| 8 | song base (`$A000`) |
| 10 | data size (bytes of FamiStudio data; the rest of the region must be zero) |
| 12 | song index |
| 13 / 14 / 16 / 17 | timing: frames per row (u8), rows per order (u16), order count (u8), loop order (u8); all zero = unknown |
| 18-31 | zero |
| 32 | song region, placed at `$A000` |

`load_image` validates the header, the FamiStudio song list (count 1-17, song index, instrument
pointer, five channel stream pointers and the tempo-envelope pointer of every song inside the data)
and the zero padding. The streams themselves are not parsed; bad stream data ends in a fault.

**FamiStudio data format** (authority: the engine source and FamiStudio's exporter,
`FamiStudio/Source/IO/FamitoneMusicFile.cs`; FamiStudio's own `famistudio-asm-export` is the
golden reference, and the 4.5.3 Linux release runs headless with `dotnet FamiStudio.dll <song>
famistudio-asm-export out.s -famistudio-asm-format:ca65`, checked here): byte song count; word
instrument list; word DPCM list − 4; per song 5 stream pointers (pulse 1, pulse 2, triangle, noise,
DPCM) + 4 tempo bytes (FamiStudio tempo: tempo-envelope pointer, frame-lookup index, 0). An
instrument is 4 envelope pointers: volume (byte 0 = release index), arpeggio, duty, pitch (byte
0 = relative/absolute flag). Envelope bytes ≥ `$80` are values (byte − 192), `$00` loops to the
index in the next byte, `$01-$7F` hold the value that many frames. Channel streams are the
engine's opcode language (notes, empty-row runs, `$41` reference = call a block, `$42` loop,
`$44` end, `$45` release, effect opcodes `$4x/$5x`; see `famistudio_advance_channel`). All
pointers are absolute, so data is lowered for `$A000`.

**Notes**: stream note `n` (1-96) indexes the NTSC note table; `n = 46` is period `$0FD`
(440.4 Hz on a pulse), so Thor Tracker's absolute note (C-0 = 0) is `n + 11`. Pulses can play
`n ≥ 10` (A-1, the longest 11-bit period); the triangle uses the same table and sounds an octave
lower (FamiTracker convention); 0 stops the note. The host test checks the whole table against
12-TET A440 (within one period unit).

### Ops behaviour (spike)

| Op | Behaviour |
|---|---|
| `start(order, row)` | power-on reset, init + play, then a silent fast-forward of the real engine for `(order × rows per order + row) × frames per row` frames (always `CHIP_SEEK_FROM_START`). Fails with `CHIP_ERR_IMAGE` past the song, past `max_ticks`, or for (order, row) ≠ (0, 0) without timing. Cost ≈ 11.4 µs per frame on the dev PC (3591 frames: 41 ms) |
| `apply_image` | copies the song region and timing of a valid image at the tick boundary; the engine keeps its stream and envelope pointers, so this is only correct for edits that keep every data offset |
| `audition` | `CHIP_ERR_IMAGE` (not supported yet) |
| `release` | 0 |
| `mute(channel 0-4)` | forces the channel's level to 0 in the APU mixer while the engine keeps running, so unmuting resumes mid-note (hUGE resumes at the next note) |
| `position` | from the frame counter and the image timing, wrapped at the loop order; without timing `(0, 0, frames)` |
| `tick` | one frame: play routine + APU, 798-800 frames at 48 kHz |

### What step 5 needs

1. **Tempo mode.** In FamiStudio tempo the streams advance every frame (row lengths are baked
   into the data as frame counts; the tempo envelope only adds/skips frames for NTSC/PAL), so the
   engine has no rows: position and seek need the lowering's frame timing. The header's single
   frames-per-row value covers constant grooves only; grooves or per-pattern lengths need a
   per-order start-frame table. In **FamiTracker tempo** (`FAMISTUDIO_USE_FAMITRACKER_TEMPO` +
   `..._DELAYED_NOTES_OR_CUTS`) the engine advances rows itself with speed (= ticks per row, `Fxx`)
   and tempo, supports note delay/cut, and exposes the row in RAM (`famistudio_tempo_advance_row`
   after an update; before it, a row starts iff `famistudio_tempo_acc_hi >= famistudio_song_speed`).
   That matches the tracker model (and the Game Boy's ticks per row) and makes row-exact seek and
   position possible from engine state; recommended. Switching means: set the flags in
   `nes_player.s`, re-run the prepare script (new player id), make `nm_validate` treat the 4
   tempo bytes as NTSC/PAL step words instead of an envelope pointer, and count rows instead of
   frames. The test song then has to come from a FamiTracker-tempo export (FamiStudio's CLI can
   export one from a FamiStudio text file).
2. **Live edits.** Stream pointers are cached by the engine, so a relayout under a playing song
   is unsafe. Either lay data out with stable slots (as `HugeImageLayout` does: each
   pattern/channel stream in a fixed slot, order lists as streams of `$41` references), or re-seek a
   spare machine to the current position off the audio thread and swap it in.
3. **Audition.** Needs a one-note song using the image's instrument list: e.g. a second song in
   the data (song index 1) whose stream for the channel plays note `n` with instrument `i` and
   holds, or a stream the C side writes into free RAM (`$02A3-$07FF`) pointing at the image's
   instruments. Release = the engine's release opcode (`$45`), so `release()` should return 1 and
   tick until the volume envelope reaches 0. Channel borrowing can use `mute` (mixer level) or
   the engine's channel state.
4. **Export.** The host test writes `nes_demo.nsf`: a 128-byte NSF header (load `$8000`, init
   `$8000`, play `$8003`, NTSC speed 16639 µs) + the player + the song region. The same bytes are
   the `.nsf` export.
5. **Measure on the Thor**: realtime cost per tick and seek cost per frame (dev PC: 104 µs per
   tick, 159× realtime; the engine's worst frame is 4267 CPU cycles), and output level against
   the Game Boy.

### Preparing the player (once, when the pin or configuration changes)

```
python3 tools/audio/prepare_nes_player.py   # ca65/ld65 on PATH or in the `ubuntu` distrobox
```

It checks the vendored sources' sha256 and the assembler version, assembles
`tools/audio/nes/nes_player.s` with `ca65 -g` and links it alone (`nes_player.cfg`: code at
`$8000`, zero page at `$00`, RAM at `$0200`), checks the entry table, that the player ends below
`NES_SONG_BASE` and the RAM layout, links the demo song export at `$A000` (`nes_song.cfg`), checks
its song list, and writes `app/src/main/cpp/generated/nes_player_image.h` (bytes, addresses,
player id) and the test fixture `app/src/test/cpp/nes_demo_song.h`. Two runs produce identical
files.

### Verification

- `tools/audio/run_native_host_test.sh [dir]` also builds and runs `app/src/test/cpp/nes_machine_test.c`
  (2026-11-07: **183 checks, 0 failures**; Game Boy unchanged at 145). APU: pulse/triangle pitch
  from timer periods by zero crossings (440.40 / 220.20 Hz exact), length counter clocked at cycle
  149149 for index 0, `$4015` status and enable, frame IRQ flag, envelope, linear counter, sweep
  in both negate modes and target muting, 5-step immediate clock after the `$4017` delay, LFSR
  periods 32767 / 93, mixer formulas. Player: entry table, note table vs 12-TET, FamiStudio's
  own export of Shatterhand "Final Area" played for 4200 frames (one pass + loop + 10 s) with no
  fault, non-silent and unclipped, register writes repeating with the 3600-frame song period (the
  timing metadata matches the engine), pulse periods on the note table, the longest held pulse 1
  and triangle notes rendered alone measuring 220.30 Hz (timer 220.20) and 130.68 Hz (timer
  130.68), identical render hashes twice, seek to order 3 row 5 writing the same registers as
  straight play, seek limits, apply, mute, invalid opcode/BRK/runaway faults, an unknown stream
  opcode reaching the engine's BRK as `CHIP_ERR_FAULT`, and the ops table (registry, image checks,
  start/position/tick/apply/mute/audition/release).
- Evidence written to the directory: `nes_demo.wav` (30 s, 48 kHz stereo), `nes_demo_tonal.wav`
  (pulses + triangle only), `nes_trace.txt` (per-frame APU writes), `nes_demo.nsf`.
- `tools/audio/nes_reference_check.py <dir> [--famistudio <FamiStudio 4.5.3 Linux release>]`
  (developer evidence: numpy, py65, libgme; FamiStudio needs .NET 8) — 2026-11-07, all PASS:
  - py65 (an independent 6502) running the NSF: all 4200 frames, 37075 APU writes identical in
    order and value.
  - Audio vs libgme rendering the same NSF, FamiStudio's own `wav-export` and libgme rendering
    FamiStudio's own `nsf-export` of `Shatterhand.fms`: loudness-envelope correlation 0.90 / 0.93 /
    0.89 (full mix) and 0.94 / 0.96 (pulses + triangle), log-spectrum correlation 0.91 / 0.93
    (pulses + triangle). The two references agree with each other less (0.875 full mix; 0.906 and
    0.911 pulses + triangle): the differences are within the spread between players (output
    filters, random noise, mixer approximations), not an outlier.
- Not done: listening on hardware, blargg's APU test ROMs (they need a PPU and IRQs), a comparison
  with a real NES capture.
