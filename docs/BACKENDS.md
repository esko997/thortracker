# Sound backends — design

Status: proposal (2026-11-07). Order of work: Game Boy refactor → NES 2A03 → C64 SID → FM.

## Goal

A song targets one sound chip ("system"). Everything a musician does that is not about the chip (orders, patterns,
rows, grooves, parked orders, Sketch, tunings, the controller workflow, both frontends) is shared. Everything that is
about the chip (channels, instruments, effects, limits, playback, export) belongs to a backend. Adding a chip must not
change a single byte of what the Game Boy backend plays or exports.

## Where the code is today

The shared parts are already neutral: `Song.patterns`, `orders`, `ticksPerRow`, `loopOrder`, `parkedOrders`,
`Pattern.tracks/length/scale`, `Transport`, `Workspace`, `HostInput`, `Popups`, `AppMenus`, the painters' layout code.

The Game Boy leaks in five places (reference counts from a read-only audit):

| Where | What is Game Boy-specific |
| --- | --- |
| `model/Song.kt` | Three typed 15-slot banks (`pulseInstruments`, `waveInstruments`, `noiseInstruments`), 16 wave tables, `Instrument` = NRx register fields, `Cell.note` 0..71 = C-3..B-8, `Cell.instrument == 0` = hUGE legato |
| `model/SongValidator.kt` | 35 `HugeRules`, 12 `InstrumentKind` references: 4 channels, bank sizes, NR10 on channel 1 only, ROM bank and 272-track preview limits |
| `editor/ViewController.kt`, `EditorSession.kt`, `SoundParams.kt` | 54 `InstrumentKind` references: 3 banks, 3 pens, noise excluded from scales/tuning/Sketch, octave clamp 3..8, register-timed parameters |
| `ui/` painters | `SoundPainter` (3 bank tabs, GB envelope curves), `WavePainter`, `PatternPainter` (`40y` detune labels), `HelpPainter` (GB effects, NR50/NR51), "Export .uge" |
| `model/Tuning.kt` | Realisation computes cents from GB periods (`131072 / (2048 - period)`) and writes the bend as `40y` |
| `ProjectCodec` | Accepts only `backend: "gb-huge"`, `backendVersion: 1` (the envelope already has the field) |

Playback on both frontends: Kotlin lowers the song (`HugeLowering` → `HugeImageLayout`, a 64 KiB Game Boy memory
image), and C runs the real hUGEDriver on binjgb (`huge_machine.c`) inside the AAudio (Android) or SDL3 (Deck)
callback. `TrackerAudio` is already a clean Kotlin seam; the native seam (`huge_machine.h`) is Game Boy-shaped.

Pinned behaviour that must stay green throughout: `CrossPlatformGoldenTest` (factory `.uge` size and hash, tuning
notes and bends), `Uge2sourceFixtureTest` (lowering byte-identical to real `uge2source`), `HugeLoweringTest`,
`HugeImageLayoutTest`, `UgeExporterTest`, `ProjectCodecTest`, the native host test (126 checks), the 18 emulator
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
    fun lower(song: Song): EngineImage       // what the native engine plays
    fun instrumentEditor(): InstrumentEditor // params, painter, hints
}
```

Game Boy's implementation wraps today's `HugeRules`, `HugeLimits`, `HugeLowering`, `HugeImageLayout`, `SongValidator`.

## Native engine (C, both frontends)

`huge_machine.h` becomes one implementation of a small engine interface the AAudio and SDL3 hosts call:

```c
typedef struct ChipEngineOps {
    int  (*set_image)(void *e, const uint8_t *image, size_t size);   // validated, applied at a tick boundary
    void (*start)(void *e, int order, int row, int loop_order);
    int  (*render)(void *e, float *stereo, int frames);
    void (*position)(void *e, int *order, int *row, int *tick);
    void (*audition)(void *e, int channel, int note, int instrument, int fine);
    void (*release)(void *e);
    void (*mute)(void *e, int channel, int muted);
} ChipEngineOps;
```

The latency-compensated position reporting, live song updates and audition mixing stay in the hosts and work for
every chip.

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

1. **Declare the Game Boy.** `SoundSystem` + `GbSystem`; validator, `ViewController`, `EditorSession`, painters, Help
   and menus read channels, effects, limits and views from it. No behaviour change; screenshots identical.
2. **Model.** `system` + `GbSound`, absolute notes, project version 8 with migration, backend registry in the codec,
   tuning realisation through the system.
3. **Native engine interface.** Game Boy behind `ChipEngineOps` on Android and Deck; host test unchanged.
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
