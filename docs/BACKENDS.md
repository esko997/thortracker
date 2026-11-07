# Sound backends — design

Status: proposal (2026-11-07). Order of work: Game Boy refactor → NES 2A03 → C64 SID → FM.

## Goal

A song targets one sound chip ("system"). Everything a musician does that is not about the chip (orders, patterns,
rows, grooves, parked orders, Sketch, tunings, the controller workflow, both frontends) is shared. Everything that is
about the chip (channels, instruments, effects, limits, playback, export) belongs to a backend. Adding a chip must not
change a single byte of what the Game Boy backend plays or exports.

## Where the code is today

Steps 1 and 2 are done for the Game Boy (branch `systems`).

- `system/SoundSystem.kt`: `SoundSystem` (channels as `ChannelSpec` with a chip-neutral `ChannelKind`, bank index and
  `tonal`; `banks`; absolute `noteRange`; `effects`/`effectsFor`; `views`; `exports`; `fine` (tuning realisation);
  validation; `arranged`/`patternLoop`; order-jump rewriting; `engine`; instrument names/summaries; Help prose),
  `SystemSound`, and the `SoundSystems` registry (unknown id: a clear error). `SystemView` is a system's own view:
  controller logic plus painter, which `ViewController` and `PaneView` delegate to.
- `system/gb/`: `GbSystem` wraps `HugeRules`, `GbValidator` (was `SongValidator`), `HugeLimits`, `HugeLowering` and
  `UgeExporter` unchanged; `GbSound` holds `GbInstrument` banks (`GbBank` = pulse/wave/noise) and the 16 waves;
  `GbSoundView`/`GbWaveView` with `SoundPainter`, `WavePainter`, `SoundParams` and the bank/wave edits (`GbEdits`).
  Tuned notes are realised as `40y` (`GbSystem.fine`).
- `Song` holds the shared fields plus `sound: SystemSound`; `Song.system` is the sound's system. `Cell.note` counts
  semitones from C-0 (C-3 = 36); lowering subtracts 36, so `.uge` bytes are unchanged. Scales, Sketch, phrases, the
  keyboard and tunings work in that absolute pitch; octave limits come from the note range.
- `ProjectCodec` writes version 8 (`song.backend` + `song.sound`) and upgrades versions 1-7 through
  `SoundSystem.upgradeProject`. `TrackerAudio` takes absolute notes; the song's system converts them.
- Shared code and both audio hosts no longer name the Game Boy. `SoundSystem.engine` (`PlaybackEngine`) is what a
  host needs to play a system: the native engine id, the image size and `lower(song, out)`, the seek tick budget,
  and audition mapping (`auditionProblem`, `note`, `fine`). `GbSystem.engine` wraps `HugeLowering` and
  `HugeImageLayout` (now in `backend/`).

Playback on both frontends: the song's system lowers it (Game Boy: `HugeLowering` → `HugeImageLayout`, a 64 KiB Game
Boy memory image), and C runs the real driver (Game Boy: hUGEDriver on binjgb, `huge_machine.c`) behind
`ChipEngineOps` inside the AAudio (Android, `ChipAudio`) or SDL3 (Deck, `DeckAudio`) callback. Each host runs the
engine of the song it is given and replaces it when a song of another system arrives.

Pinned behaviour that must stay green throughout: `CrossPlatformGoldenTest` (factory `.uge` size and hash, tuning
notes and bends), `Uge2sourceFixtureTest` (lowering byte-identical to real `uge2source`), `HugeLoweringTest`,
`HugeImageLayoutTest`, `UgeExporterTest`, `ProjectCodecTest`, the native host test (145 checks), the 18 emulator
tests, and screenshot parity of every view.

## Principles

1. **The real driver is in the loop.** A backend plays songs by running the same playback code a game would ship,
   on an emulation of the hardware. What you hear is what the export plays. (Game Boy: hUGEDriver on binjgb. NES:
   the FamiStudio sound engine on a 6502 + 2A03.) A backend that only fed chip registers from a Kotlin sequencer
   would be quicker to write, but its songs could not go into a game.
2. **Backends declare; the editor asks.** Channel names and kinds, note range, effect list, instrument schema,
   limits, available views, help text and export formats come from the backend. No editor code names a chip.
3. **Each backend keeps its own instrument model.** No universal instrument. Game Boy instruments stay register
   settings. NES instruments are envelopes (volume, arpeggio, pitch, duty sequences with loop and release points),
   because that is what NES drivers play. Envelopes ("macros") are therefore an NES feature first; a later chip may
   reuse that editor, but the Game Boy never has to.
4. **One system per song.** The system is chosen with New. Converting a song to another system comes later, as a
   lossy step with a report like export validation.
5. **Every step ships.** Each step below is a release with the Game Boy byte-identical.

## Model

```kotlin
data class Song(
    // shared: title, artist, comment, ticksPerRow, loopOrder, patterns, orders, parkedOrders, tuning, newPatternScale
    val system: SystemId,          // "gb-huge", "nes-famistudio", …
    val sound: SystemSound,        // sealed: everything chip-specific
)
sealed interface SystemSound
data class GbSound(val pulse: List<GbInstrument>, val wave: List<GbInstrument>, val noise: List<GbInstrument>,
                   val waves: List<List<Int>>) : SystemSound      // today's fields, moved, unchanged
data class NesSound(val instruments: List<NesInstrument>) : SystemSound
```

- **Notes become absolute.** `Cell.note` counts semitones from C-0 (so today's C-3 = 36). Each backend declares its
  range (Game Boy C-3..B-8; NES the FamiStudio engine's note table, confirmed in step 4) and lowering subtracts its
  own offset, so `.uge` bytes do not change. Sketch, the Keyboard view, scales and tunings then work in one pitch
  space for every chip.
- **Cells keep note / instrument / effect / param.** The effect numbers are the backend's. Each backend maps a
  shared core of meanings (arpeggio, slides, portamento, vibrato, volume, note delay, note cut, speed, position jump,
  pattern break, fine pitch) to its own numbers, which Sketch, Help, conversion and the effect picker use. Game Boy
  keeps the hUGE numbers exactly.
- **Tuning realisation moves to the backend:** `realise(cents) -> (note, fine)`. Game Boy keeps `40y`, NES uses the
  engine's fine-pitch track. Each chip reports its own "N of M degrees reachable within 5¢".
- **Project files version 8** add `system` and nest the chip data; version 1-7 files open as Game Boy songs.

## Backend interface (core)

```kotlin
interface SoundSystem {
    val id: SystemId
    val name: String                         // "Game Boy (hUGEDriver)"
    val channels: List<ChannelSpec>          // name, kind (pulse/wave/triangle/noise/…), tunable, colour
    val noteRange: IntRange
    val effects: List<EffectSpec>            // number, meaning, parameter text, channels it applies to
    val views: Set<ViewKind>                 // NES has no Wave view
    val exports: List<ExportFormat>          // .uge; .nsf, FamiStudio text
    fun validate(song: Song): List<Problem>
    fun realise(note: Int, cents: Double): Pitch
    val engine: PlaybackEngine               // native engine id, lower(song) → image, seek budget, audition mapping
    fun instrumentEditor(): InstrumentEditor // params, painter, hints
}
```

Game Boy's implementation wraps today's `HugeRules`, `HugeLimits`, `HugeLowering`, `HugeImageLayout`, `SongValidator`.

## Native engine (C, both frontends)

Both hosts (AAudio on Android, SDL3 on the Deck) drive chips only through `ChipEngineOps` in
`app/src/main/cpp/chip_engine.h`; `chip_engine_ops("gb-huge")` is hUGEDriver on binjgb (`huge_chip.c` over
`huge_machine.c`). The ops are tick-based (`tick` renders one driver tick), images are copied into host-owned slots
and applied at tick boundaries, and `release` reports whether the chip has a release phase the host must keep
ticking. `docs/AUDIO_ENGINE.md` lists what a new chip implements.

The latency-compensated position reporting, live song updates and audition mixing (with the auditioned channel
muted in the song) stay in the hosts and work for every chip.

## NES backend

- **Driver:** FamiStudio sound engine 4.5 (all-permissive licence, notice kept). Used in NES homebrew; supports
  volume and fine-pitch tracks, slides, vibrato, arpeggios, release notes, duty changes and note delays. It also
  drives the VRC6, VRC7 (2-operator FM), N163, S5B, FDS, MMC5 and EPSM (YM2608) expansion chips, which can become
  later systems on the same driver.
- **Engine:** a 6502 core and a 2A03 APU (pulse ×2, triangle, noise; no DPCM samples), both under permissive licences
  or written here. The engine binary is assembled once at build time (like hUGEDriver) and the song data is placed
  at a fixed address, as `HugeImageLayout` does today.
- **Lowering:** Kotlin encodes songs into the FamiStudio engine's data format. A golden test compares it with
  FamiStudio's own export of the same song.
- **Instruments:** duty, volume, arpeggio and pitch envelopes, each with loop and release points. A new instrument
  editor; Wave view hidden; triangle has no volume (the editor says so instead of offering it).
- **Export:** `.nsf` (plays in any NSF player and on hardware via flash carts) and FamiStudio's text format (opens in
  FamiStudio, from which it exports to ca65/asm6/NESASM projects).

## Steps

1. **Declare the Game Boy** — done. `SoundSystem` + `GbSystem` (`system/gb`); the editor, painters, Help and menus
   read channels, banks, effects, limits and views from the song's system. Screenshots identical.
2. **Model** — done. `GbSound`, absolute notes (C-3 = 36), project version 8 with migration from 1-7, Sketch
   phrases in saved workspaces migrated, tuning realisation through the system.
3. **Native engine interface** — done. Game Boy behind `ChipEngineOps` on Android and Deck. `TrackerAudio` takes
   absolute notes; the song's system lowers, names its engine and converts audition notes (`SoundSystem.engine`), and
   `ChipAudio` (Android, was `HugeAudio`) and `DeckAudio` switch engines with the song's system.
4. **NES spike.** 6502 + 2A03 playing the FamiStudio engine with a song exported from FamiStudio, in the native host
   test; compare audio with an NSF render.
5. **NES backend.** Lowering, instruments and their editor, New → system, Help, validation, `.nsf` and FamiStudio
   text export. Release.
6. **SID**, then **FM** (design to follow; FM has its own ideas pending).

## Open questions

- Should the system be switchable inside a song later (conversion), or only chosen with New? Proposed: New only for
  now.
- NES instruments: one shared bank for all four channels (FamiStudio's way) rather than per-channel banks. Proposed:
  shared, with the editor greying out what a channel ignores.
