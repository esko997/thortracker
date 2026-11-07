# Audio engine: hUGEDriver preview

Preview playback runs the **actual pinned hUGEDriver 6.1.3 binary** on an embedded Game Boy
Color emulator (binjgb) inside `libthortracker_audio.so`, output through AAudio (Android) or SDL3
(Steam Deck). There is no second song sequencer: notes, instruments, effects, timing and channel
behaviour are whatever the driver code does on the emulated CPU and APU.

## Chip engine interface (what a new sound system implements)

Both audio hosts (`audio_engine.cpp` on AAudio, `deck/native/deck_audio.c` on SDL3) are
chip-neutral: they call one `ChipEngineOps` table (`app/src/main/cpp/chip_engine.h`), looked up
by system id with `chip_engine_ops(id)` (`chip_engine.c`). `HugeAudio` and `DeckAudio` create
their host for `"gb-huge"`, whose ops (`huge_chip.c`) map one-to-one onto `huge_machine.h`, so the
Game Boy plays exactly as before.

A new chip adds one ops table to the registry in `chip_engine.c` (and its sources to `CMakeLists.txt`,
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
binjgb's `emulator.c`, plus `common.c`, always with `NDEBUG`) and the shared `thortracker_audio`
(`audio_engine.cpp`, `audio_jni.cpp`) linked with NDK `aaudio`, `log`, `m`, `dl`. No third-party
Java/Gradle dependency. The Deck compiles the same C files with Kotlin/Native's clang
(`deck/build.gradle.kts`, `buildEngine`).

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

`HugeAudio.update(song)` (UI thread, non-blocking) → control thread: `HugeLowering.lower`
(the same lowering `.uge` export uses) → `HugeImageLayout.build` (fixed header, deduplicated
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
budget `HugeAudio.SEEK_TICK_BUDGET` = 12000 ticks (~3.3 min). If not reached (budget or the row
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
- `HugeAudio.diagnostics()` reports sample rate, burst, buffer size, xruns, worst callback time,
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
- `core/src/jvmTest/kotlin/org/thortracker/audio/HugeImageLayoutTest.kt`: layout equals the lowered
  bytes, sharing, capacity, minimal byte diffs.
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
