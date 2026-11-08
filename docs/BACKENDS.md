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
  semitones in scientific pitch (0 = C0, A4 = 57 = 440 Hz), the same pitch on every system; Game Boy lowering subtracts
  24 (hUGE note 0 = C2), so `.uge` bytes are unchanged. Note names are the system's (`SoundSystem.noteName`,
  `nameOctaveOffset`): hUGETracker's (an octave above scientific: its C-3 is C2) and FamiTracker's (A-4 = 440 Hz); the
  editor's octave fields count in those names. Scales, Sketch, phrases, the
  keyboard and tunings work in that absolute pitch; octave limits come from the note range.
- `ProjectCodec` writes version 9 (`song.backend` + `song.sound`, scientific pitch) and upgrades versions 1-8 through
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

- **Notes become absolute.** `Cell.note` counts semitones in scientific pitch (0 = C0, A4 = 57 = 440 Hz; the Game Boy's C-3, which sounds C2, = 24). Each backend declares its
  range (Game Boy C-3..B-8; NES: FamiStudio note byte `n` = absolute `n + 11`, table C-1..B-8 with A-4 = 440 Hz on the
  pulses, pulses reach down to A-1 and the triangle sounds an octave below — confirmed in step 4) and lowering subtracts its
  own offset, so `.uge` bytes do not change. Sketch, the Keyboard view, scales and tunings then work in one pitch
  space for every chip.
- **Cells keep note / instrument / effect / param.** The effect numbers are the backend's. Each backend maps a
  shared core of meanings (arpeggio, slides, portamento, vibrato, volume, note delay, note cut, speed, position jump,
  pattern break, fine pitch) to its own numbers, which Sketch, Help, conversion and the effect picker use. Game Boy
  keeps the hUGE numbers exactly.
- **Tuning realisation moves to the backend:** `realise(cents) -> (note, fine)`. Game Boy keeps `40y`, NES uses the
  engine's fine-pitch track. Each chip reports its own "N of M degrees reachable within 5¢".
- **Project files version 8** add `system` and nest the chip data; version 1-7 files open as Game Boy songs. Version 9
  makes notes scientific pitch on every system (8 counted in each system's naming; Game Boy 8 files move down 12).

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

- **Driver:** FamiStudio sound engine 4.5.3 (all-permissive licence, notice kept). Used in NES homebrew; supports
  volume and fine-pitch tracks, slides, vibrato, arpeggios, release notes, duty changes and note delays. It also
  drives the VRC6, VRC7 (2-operator FM), N163, S5B, FDS, MMC5 and EPSM (YM2608) expansion chips, which can become
  later systems on the same driver.
- **Engine:** `m6502.h` (floooh/chips, zlib) and a 2A03 APU written here (`nes/nes_apu.c`: pulse ×2, triangle,
  noise and the DMC with sample playback), band-limited through blip_buf (LGPL-2.1+). The player image is assembled
  once (`tools/audio/prepare_nes_player.py`, committed like hUGEDriver's, with DPCM and the delta counter enabled);
  song data and DPCM samples share `$8C00-$FFF9` (29690 bytes, no bank switching): the song grows up from `$8C00`, the
  samples sit at the top (64-aligned, never below `$C000`, where the DMC starts reading), and the song must end below
  them. A song without samples keeps all 29690 bytes (0.9.0 allowed 24570). Details in `docs/AUDIO_ENGINE.md` (NES
  section).
- **Model** (`system/nes`): `NesSound` = one bank of 64 `NesInstrument`s (slot n = index n − 1), each four
  `NesEnvelope`s (values, loop, release; FamiStudio ranges: volume 0-15, arpeggio and pitch −64..63, duty 0-3, at most
  256 steps; a release only on volume and only after a loop, as FamiStudio's import enforces) plus the pitch
  envelope's relative flag, and the song's DPCM samples. Notes C-1..B-8 (12-107); FamiStudio note n = absolute − 11
  (A-4 = 440 Hz on the pulses). The pulses and the triangle cannot go below A-1; Noise notes pick one of 16 rates
  (note mod 16). Project files store NES songs as backend `"nes-famistudio"`, NES data version 2 (`backendVersion`;
  version 1 songs gain an empty DPCM track); versions before 8 are Game Boy songs only.
- **DPCM** (channel 5, `ChannelKind.SAMPLE`, not tonal): as FamiStudio 4.5.3 does it. A `DpcmSample` is the DMC
  bytes the hardware plays (16n + 1, at most 4081) and FamiStudio's initial value ÷ 2 (default 32: `$4011` = 64). An
  instrument maps notes to samples (`NesInstrument.dpcm`: `DpcmMapping` sample, rate 0-15, loop, optional initial
  value override), so one instrument is a drum kit; on DPCM only the mappings matter. A DPCM note starts its mapping's
  sample (setting the output level first); a note without instrument plays the current instrument's mapping without
  attack (the level is kept); OFF stops the sample. Sources (`Dpcm`): `.dmc` files, `.wav` files converted with
  FamiStudio's own import and encoder arithmetic (byte-identical, proven by `DpcmTest` against FamiStudio itself via
  `tools/audio/dpcm_reference.py`; padded to 16n + 1 rather than FamiStudio's default 16n), and `DpcmStarterKit`
  (kick, snare, hat, toms, bass; synthesized in integer arithmetic, so identical on every platform and free of
  third-party audio). Budgets: the samples a song plays fit `$C000-$FFF9` in 64-byte slots (at most 16320 bytes), song
  data plus samples fit the shared 29690 bytes ("Song data 20.1 KB + DPCM samples 9.0 KB exceed the NES's 28.9 KB"),
  and at most 63 distinct mappings (FamiStudio's limit without its extended range); validation names the samples and
  notes.
- **Effects** (one column; codes are the base-36 value of their character): `0xy` arpeggio (a FamiStudio arpeggio
  0, x, y on that note), `4xy` vibrato (speed 1-C, depth 1-F; persists across notes until `400`), `Axy` volume x
  sliding to y by the next volume effect, `Cxx` volume, `Fxx` speed 1-1F, `Gxx` note delay, `Pxx` fine pitch (80 =
  none; for the note it is on, so tunings use it), `Qxx`/`Rxx` glide up/down xx semitones arriving at the next note,
  `Sxx` cut, `Vxx` duty (Noise: mode), `Zxx` DMC level 00-7F (DPCM only; FamiStudio's delta counter: with a note it
  replaces that sample's initial value, without one it applies at once). DPCM takes only `F`, `G`, `S` and `Z`
  (FamiStudio's `Channel.SupportsEffect`). No jumps: FamiStudio's engine has none, so the loop order and pattern
  lengths arrange the song. OFF releases a note whose instrument has a volume release point, otherwise (or when
  already released, and always on DPCM) it stops. A note without instrument is legato (FamiStudio "no attack") on the
  current instrument.
- **Lowering** (`backend/FsSong`, `FamiStudioLowering`, `FamiStudioEnvelopes`, `NesDpcm`, `NesImageLayout`): the song
  becomes FamiStudio's simple-note model (`FsSong`), then engine data in the image layout of `docs/AUDIO_ENGINE.md`.
  Each order's pattern stream is what FamiStudio's own exporter (`FamitoneMusicFile.GetSongData`) emits for those
  rows — opcode order, tracked state, slide and volume-slide step arithmetic (slides take FamiStudio's larger
  NTSC/PAL step, as its music export always targets both) — and envelopes are its `ProcessEnvelope` bytes. Its layout
  is ours: instruments and their envelopes in stable slots, `$41` per order, identical order streams stored once;
  each order re-sends its first note's instrument and a held volume so it can be entered by seek, loop or live edit.
  DPCM (`NesDpcm`) follows FamiStudio's packing: the samples the song plays in sample order, each padded with `$55` to
  64 bytes (identical data stored once), as one block placed at the top of `$C000-$FFF9`, and a 4-byte sample list entry
  per distinct mapping; a DPCM note is its entry number. The preview image also holds the mappings the song does not
  play while the list, the sample memory and the shared budget have room (they never take room the song needs), so the
  editor can audition them (`PlaybackEngine.note(song, channel, note, instrument)` gives the entry).
- **Golden test** (`NesGoldenTest`): FamiStudio 4.5.3 imports our text export of four songs (the demo, an effects
  song, an envelope song, a DPCM song) and exports its own data and sample bank (`tools/audio/nes_golden.py`). Our
  envelope bytes must appear in its export, our sample memory must equal its `.dmc` byte for byte, and both datas,
  run by the embedded player on a 6502 (`NesTracer`), must write identical APU registers (DMC ones included) every
  frame through two passes and the loop. Whole-file bytes are not compared: FamiStudio sorts and merges instruments,
  orders envelopes by CRC and compresses streams by a size search, none of which affects what plays.
- **Export:** `.nsf` (`NsfExporter`: NSF 1, NTSC, no bank switching: the player at `$8000`, the lowered data at
  `$8C00` and the sample block where the image puts it; plays in NSF players and on hardware via flash carts) and
  FamiStudio's text format
  (`FamiStudioText`; FamiStudio 4.5.3 opens it, the golden script proves it, and exports it to ca65/asm6/NESASM for
  games; instruments are named "slot name" and carry their DPCM mappings, one `DPCMSample` per distinct sample data,
  one pattern per distinct order content so note durations and releases stay exact).
- **Demo:** "Thor Quest" (`NesDemoSong`), loaded by Load demo on NES songs; the starter kit plays kick and snare on
  DPCM under the noise drums, with tom fills. `exportSamples` writes it as `thor-quest.{thor,nsf,txt,nesimg}`;
  `tools/audio/render_nes_image.sh` renders an image through the native engine.

## SID backend (C64)

- **Driver:** the GoatTracker 2.77 playroutine (Lasse Öörni; "does not fall under the GPL … use it, or song
  binaries created from it freely for any purpose"), GoatTracker's packer configuration without
  optimizations so one player build plays every song. GoatTracker is the de-facto C64 tracker; songs and
  `.sid`/`.prg` exports from it play in SID players, on hardware and in games.
- **Engine:** the 6510 is the shared `m6502.h`; the chip is reSID 1.0 (Dag Lem, GPL-2.0-or-later, C++ behind a
  C interface), cycle-exact writes, band-limited resampling, MOS 6581 or 8580 per song (8580 recommended for
  new songs). PAL, 50 Hz ticks, multispeed 2-16× as GoatTracker's CIA timing. Details, costs and the image
  layout: `docs/AUDIO_ENGINE.md` → SID.
- **Spike result** (step 6, branch `sid-spike`): a song written in GoatTracker's format, packed by GoatTracker's
  own packer, and our lowering of it into the fixed-address image layout are byte-identical data; on the
  machine the image writes **the same SID registers as py65 running GoatTracker's packed `.sid`** in every
  frame, with row starts on the reference player's frames; 440.07 Hz for A-4; deterministic; seek, mute,
  audition and release through the player; audio matches sidplayfp's reSID by loudness envelope (0.985 on the
  8580). The Deck links reSID through Kotlin/Native's clang++ and libstdc++.

### Model, lowering and exports (step 2)

- **System** (`system/sid`): `SidSystem`, id `"c64-sid"`, three voices (`ChannelKind.VOICE`, all tonal), one bank of
  63 instruments shared by all voices (slot n = GoatTracker instrument n). Notes C-0..G#7 (absolute 0..92 =
  GoatTracker's note; A-4 = 57 = 440.09 Hz; FamiTracker-style names); OFF is key off, note −3
  (`SidRules.KEY_ON`, "ON") is key on. PAL only: 50.12 ticks per second times the song's multispeed.
- **Chip data** (`SidSound`): the 63 `SidInstrument`s, the chip model (`SidChip`, 8580 for new songs), multispeed
  1-16 (tempos and gate timers count the faster ticks, as in GoatTracker) and the hard-restart envelope (default
  `$0F00`). An instrument is GoatTracker's: ADSR nibbles, `SidRestart` (hard restart / gate off only `$80` /
  legato `$40`), gate timer 1-63, first-frame wave, `SidVibrato` (delay 0 = off, speed, depth, note-independent)
  and three **programs** (`SidProgram`: wave, pulse and filter) of `SidRow`s. Rows are GoatTracker's table bytes as
  its editor shows them, except that a jump's target is a row of the same program (1-based, 0 = stop) and a wave
  program command's parameter is the pattern effect's (below). A program whose last row is not a jump stops there
  (the lowering adds `$FF 00`). GoatTracker's four song-wide tables are not stored; they are built from the
  programs and effects.
- **Program placement** (`SidTables.placement`/`place`): identical programs of a table share rows; each program
  remembers the row it was placed at (`SidProgram.at`, saved). After every instrument edit the editor calls
  `SidTables.place(before, after)`: programs that did not change keep their rows, the edited one stays where it is
  if it still fits and otherwise takes the first gap that holds it; only when gaps are too fragmented is
  everything packed again from row 1 in slot order. The lowering places the stored rows the same way, so a song
  lowers identically every time and the native engine can follow a playing program to its new rows by slot
  (`docs/AUDIO_ENGINE.md`, SID "Live edits"). The **speedtable** (`SidSpeeds`) holds each distinct value once, in a
  fixed walk: instrument vibratos by slot, then wave program commands, then every pattern (played or not) by
  voice and row; the player re-reads a voice's speed row at each row start.
- **Effects** (`SidRules`, one column, GoatTracker's numbers 1-F; parameters are values, not table rows):
  `1xx`/`2xx` portamento up/down and `3xx` toneportamento at speed 4·xx (`300` = tie), `4xy` vibrato speed x,
  depth y·`$10` (`400` off) — GoatTracker 1's parameters, which GoatTracker 2 documents as these speedtable
  values — `5xx`/`6xx` AD/SR, `7xx` waveform, `8xx`/`9xx`/`Axx` start instrument xx's wave/pulse/filter program
  (`x00` stops it), `Bxy` filter resonance x and voices y (`B00` off), `Cxx` cutoff, `D0x` master volume, `Exy`
  funktempo x/y ticks (2-F each), `Fxx` tempo 03-7F (`F00`/`F01` resume the funktempo). Wave programs may run
  commands 1-7 and 9-D (GoatTracker forbids 0, 8 and E there). Channel tempos (`F83-FFF`) are not offered.
- **Tunings** (`SidTuning`): the player has no fine pitch (`FinePitch.max` 0, `retunesTable`), so a song's tuning
  retunes its 96-entry frequency table: note n plays the tuning degree nearest to it on the 12-note grid, exactly
  (GoatTracker's register value for n scaled by the degree's deviation); `TuningMap` then reports every such
  degree as reachable with no error. Without a tuning (and with 12-equal) the table is GoatTracker's own.
- **Lowering** (`backend/GtSong`, `backend/SidLowering`): `GtSong.of(song)` is the song in GoatTracker's own terms
  (programs at their placed rows with absolute jumps, program commands as start rows, speed effects as speed
  rows, one GoatTracker pattern per distinct voice pattern of each order, at most 208); an order's voice that starts
  with a note but no instrument gets the instrument straight play reaches it with, so the loop, a seek or a pattern
  loop (`SidSystem.patternLoop`) play it the same. `SidLowering` is a port of `gt_song.lower`: GoatTracker 2.77's
  packer without optimizations (instrument order hard-restart / gate-off / legato, table conversions, `packpattern`)
  at the fixed addresses of the image layout. One deviation from gt2reloc: in multispeed, gt2reloc counts one extra
  gate-off instrument, so its first legato instrument plays without legato; the engine's operands are ours, so
  legato stays exact.
- **Validation** (`SidValidator`, mirrors `sm_load_image`): instrument and program ranges, program rows (jump targets,
  wave commands GoatTracker allows, first wave row a waveform), table rows (each table's programs within 255 rows,
  at most 255 speeds), effect parameters, program commands naming instruments without that program, tempo 3-127,
  gate timers below the shortest row (start tempo, `Fxx`, `Exy`), 208 voice patterns and the song data region
  (`$2500-$CFFF`, 43776 bytes). A 64-row pattern packs into at most 256 bytes (instrument, command, parameter and
  note on every row is exactly GoatTracker's limit), so Thor patterns always fit. Warnings: a voice's first note
  without instrument (the player's instrument 1 sounds), PSID text fields, silent instruments.
- **Project files:** backend `"c64-sid"`, SID data version 1 (README, "Project format").
- **Exports** (`backend/SidExport`): `.sid` (PSID v2 as gt2reloc writes it: player at `$1000` with the song's five
  parameters, the image region up to its data end, PAL, the chip model; multispeed songs load and init at `$0FF6`
  behind gt2reloc's CIA speed code); `.prg` (`10 SYS2061`, start code that inits the player and hooks it into the
  KERNAL's CIA 1 timer interrupt at `$4CC7` / multispeed cycles, then returns to BASIC; then the same block); and
  GoatTracker's `.sng` (`GtSong.sng`; GoatTracker reads a start tempo other than its default from instrument 63 as
  a tempo holder, so such songs must leave slot 63 unplayed; chip model, multispeed, hard-restart envelope and a
  tuning's frequency table are packer options there, not part of the file).
- **Golden tests** (`SidGoldenTest`, `tools/audio/sid_golden.py`): the spike's `gt_test.sng` read by `GtSong.read` and
  lowered is byte-identical to the image proven to be GoatTracker's packing, and our `.sid` of it writes the SID
  registers py65 recorded from gt2reloc's `.sid` in all 1500 frames; three Thor songs (the demo, an effects song with
  every command and program feature, a ×2 multispeed song with its own hard-restart envelope) are exported as
  `.sng`, packed by GoatTracker's gt2reloc (which loads them), and our `.sid` and gt2reloc's write identical SID
  registers in all of 3000 frames (through each song's loop); every `.prg` run from `SYS2061` on a 6502
  (`SidTracer`) programs the CIA and writes what the `.sid` writes. `SidModelTest` covers placement, speeds, tunings
  and validation messages; `SidProjectCodecTest` round trips.
- **Demo:** "Raster Run" (`SidDemoSong`, A minor, 8580): a sawtooth bass under a resonant lowpass sweep, a PWM lead
  with delayed note-independent vibrato and a toneportamento, pulse arpeggios from wave programs, and wave-program
  drums (noise-click pulse kick, GoatTracker's snare, noise hats). `exportSamples` writes it as
  `raster-run.{thor,sid,prg,sng,sidimg}`; `tools/audio/render_sid_image.sh` renders an image through the native
  engine.

### Editor (step 2)

- **Instrument view** (`SidInstrumentView`, `SidInstrumentPainter`): controller-only like the NES envelope editor, six
  pages: *Envelope* (ADSR with the drawn curve and times, and *Preset*: `SidPresets`, eight starting sounds — saw lead,
  PWM pad, filter bass, noise snare, wave-arpeggio kick, hi-hat, triangle bass, arpeggio chord; a filter preset routes
  the Listen voice), *Wave*, *Pulse* and *Filter* programs, *Vibrato·HR* (restart mode, gate timer, first-frame
  waveform, vibrato drawn) and *Tables*. A program is edited as rows of columns (`SidPrograms`): the first column is
  the step's kind (wave: waveform, silent, wait, hold, command, jump; pulse: width, sweep, jump; filter: filter,
  cutoff, sweep, jump), the others its values (shape, gate/sync/ring/test, relative/absolute/kept pitch; passband,
  resonance, voices…); rows added, duplicated or deleted keep jump targets. Pulse and filter pages draw the width or
  cutoff the program plays over 100 ticks. Every edit goes through `setSidInstrument`, which re-places the tables
  (`SidTables.place(before, after)`), so unchanged programs keep their rows. Holding Edit on a sound setting
  auditions the sound and each change re-auditions it from the new image.
- **Raw tables**: the Tables page lists the 255 rows of each table as the player reads them (jumps as table rows; the
  stop rows the lowering adds), each with the owning slot and program row; read-only, Edit opens the owning program.
- **Shared state** (`SoundSystem.sharedState`, `SidFilterOwner`): the voice that last started a filter program (an
  instrument with one on a note, or `Axx`; `B00` turns the filter off), in straight play up to the playhead or the
  cursor; the Pattern view tags its lane header *FILTER* and the inspector names it.
- **Song settings** (`SoundSystem.songSettings`, `SidSongSettings`): the Song view lists chip model, multispeed and
  the hard-restart attack/decay and sustain/release after its shared rows (`ViewState.songHeaderItems`); other
  systems have none, so their Song view is unchanged. `SoundSystem.ticksPerSecond(song)` (the SID: × multispeed) feeds
  the Song view's BPM, Sketch's step and the Deck's audition cap.
- **Key on** (`SoundSystem.keyOn`): Clear on an OFF enters GoatTracker's key on, shown as `ON`.

## Steps

1. **Declare the Game Boy** — done. `SoundSystem` + `GbSystem` (`system/gb`); the editor, painters, Help and menus
   read channels, banks, effects, limits and views from the song's system. Screenshots identical.
2. **Model** — done. `GbSound`, absolute notes, project version 8 with migration from 1-7 (since version 9: scientific pitch, Game Boy C-3 = 24), Sketch
   phrases in saved workspaces migrated, tuning realisation through the system.
3. **Native engine interface** — done. Game Boy behind `ChipEngineOps` on Android and Deck. `TrackerAudio` takes
   absolute notes; the song's system lowers, names its engine and converts audition notes (`SoundSystem.engine`), and
   `ChipAudio` (Android, was `HugeAudio`) and `DeckAudio` switch engines with the song's system.
4. **NES spike** — done. `chip_engine_ops("nes-famistudio")` plays FamiStudio's own export of a
   demo song in the native host test (183 checks): exact pitches, deterministic render, no CPU faults, seek, mute,
   loop period; register writes identical to py65 running the same NSF; audio within the spread of libgme and
   FamiStudio's own WAV/NSF exports. No audition yet; position/seek by frame count. Decisions for step 5 (tempo mode:
   FamiTracker tempo recommended so the engine owns rows and `Fxx`; stable data slots for live edits; audition song;
   note mapping `n + 11`) are listed in `docs/AUDIO_ENGINE.md` → "What step 5 needs".
5. **NES backend.** Lowering, instruments and their editor, New → system, Help, validation, `.nsf` and FamiStudio
   text export. Release.
6. **SID spike** — done (branch `sid-spike`). `chip_engine_ops("c64-sid")`: GoatTracker 2.77 playroutine on
   `m6502.h` + reSID 1.0 (see "SID backend"); native host test 130 checks; register writes identical to py65
   running GoatTracker's own packing in every frame; image layout and lowering contract in
   `docs/AUDIO_ENGINE.md`.
7. **SID backend.** Model, lowering (byte-identical to GoatTracker's packer; golden traces), validation, project
   files, `.sid`/`.prg`/`.sng` export and the demo (see "Model, lowering and exports"); editor (instruments,
   programs, tables), New → system, Help.
8. **FM**: OPL3 first, YM2612 second (see below).
   **OPL3 spike** — done (branch `opl3-spike`). `chip_engine_ops("opl3-fm")`: our own player (C) on Nuked-OPL3 1.8,
   image layout, player and effect specification, seek, live edits, audition, mute and VGM export in
   `docs/AUDIO_ENGINE.md` ("OPL3"); native host test 257 checks with register writes identical to an independent
   Python reference player in every tick and VGM bytes identical to its export; the VGM plays in libvgm. Next: the
   OPL3 backend (model, lowering, instrument editor, effect columns in the editor, VGM export, New → system, Help).

## Effect columns

A system declares how many effect columns each channel may have; the Game Boy keeps one (hUGEDriver stores one
effect per row) and its songs look exactly as today. Richer systems allow several (FamiTracker allows 4, Furnace 8),
with the number shown adjustable per channel to save width on handheld screens. Effect names are one character from
0-9 then A-Z, declared per system, so chips with more than 16 effects fit without changing Game Boy songs. Each effect
declares whether it applies when a note starts (like a parameter lock: no mid-note register clicks) or at once.

## FM

Both OPL3 (YMF262) and YM2612 (OPN2), OPL3 first. Ideas from ALM Busy Circuits' Akemie's Castle / Akemie's Taiko and
Twisted Electrons' BlastBeats, which all present OPL3 with few controls:

- **Instrument editor of curated choices**: an algorithm from a short curated list, a frequency-ratio set stepped
  through whole-number pairs, a waveform combination, brightness (modulator level) and attack/decay, each one ←/→
  value; the full per-operator grid stays available behind a toggle.
- **Parameter locks as effects**: brightness, ratio, waveform and similar effects on a row, applied at note start.
- **Chord effect** written like the arpeggio (`xy` = +x and +y semitones) but sounding together on spare chip voices
  (OPL3 has 18 two-operator voices); larger chords through a second column or a numbered chord-shape table with
  inversions. Only systems with voices to spare declare it; on the others (Game Boy, NES, YM2612) chords stay notes
  spread across channels.
- **Drums are pitched instruments**: the note is the pitch, the instrument the drum sound, built from ordinary FM
  voices with a pitch sweep (start pitch, end pitch, speed) so every drum is independent. OPL3's rhythm mode may come
  later as an option; its snare/hi-hat and tom/cymbal pairs share a pitch.
- **OPL3 is music-only**: no console plays it, so the system uses our own player and exports VGM. YM2612 then reuses
  the FM editor and player, and adds export for a Mega Drive sound driver so songs can go into games.
- Cores: ymfm (BSD-3-Clause) covers both chips; the Nuked cores (LGPL-2.1) are also GPL-compatible.

### OPL3 decisions (made for the spike on the user's behalf; the full contract is `docs/AUDIO_ENGINE.md`, "OPL3")

- **Core: Nuked-OPL3 1.8** (die-accurate, plain C), not ymfm (3× cheaper, not cycle-exact): 460 µs per 60 Hz tick
  on the dev PC, below the SID 8580's 593 µs (~1.6-2.1 ms estimated on a Konkr-class device). ymfm stays the option
  for weaker devices and is a candidate core for the YM2612.
- **Music-only, our own player**: pure integer C (`opl3/opl3_player.c`), specified in the audio document and mirrored
  by an independent Python reference (`tools/audio/opl3/opl3_ref.py`); export is VGM 1.71 (the player's register log).
- **9 tracker channels**: the OPL2/AdLib count and screen-friendly; channels 1-6 own the six 4-op pairs (a 4-op
  instrument switches the pair at key-on), channels 7-9 are 2-op only. The remaining voices are the **chord pool**:
  voices 15-17 always, plus the six pair secondaries while their channel plays 2-op (9 spare voices in a 2-op song).
- **Tick = 1/60 s** with integer multispeed 1..8 (ticks per row count fast ticks), speed per song plus `Fxx`.
- **Parameter locks at key-on**: `Exx` feedback, `Ixx` brightness (modulator TL), `Mxy` ratio (operator MULT),
  `Wxy` waveform apply to the note keyed on their row only (Elektron-style locks); volume and pan change at once.
- **Chord**: `Hxy` (+x, +y) and `Kxx` (a numbered shape of up to 4 signed intervals, so inversions), per note like
  the NES arpeggio, 2-op instruments only; pool voices are allocated deterministically (free voice released
  longest ago, else steal the oldest allocated) and released with the root.
- **Drums**: instruments carry a pitch sweep (start and end in semitones relative to the note, speed in 1/16
  semitones per tick); no rhythm mode.
- **Effects** (FamiTracker letters where meanings match): `0xy` arpeggio, `1xx`/`2xx` slides, `3xx` portamento,
  `4xy` vibrato, `8xy` pan (OPL3 left/right), `Axy` volume slide, `Bxx` jump, `Cxx` volume, `Dxx` break, `Exx`
  feedback, `Fxx` speed, `Gxx` note delay, `Hxy` chord, `Ixx` brightness, `Kxx` chord shape, `Mxy` ratio, `Pxx` fine
  pitch, `Sxx` note off, `Wxy` waveform. **Up to 4 effect columns** per channel in the image and the player now;
  the editor generalisation (see "Effect columns") is part of the OPL3 backend step.
- **Retrigger**: a tick's key-offs reach the chip one sample before its other writes (the chip samples key-on once
  per sample); in the VGM a one-sample wait separates them.
- **VGM loop**: the loop point carries a dump of the register state so every pass starts from the same registers;
  songs that change speed should set `Fxx` on the loop order's first row (lowering warning).

What the Kotlin side needs: `OplSystem` (id `"opl3-fm"`, 9 channels, 4 effect columns, effect specs from the table
in the audio document, one bank of 64 instruments with 2-op/4-op operator data and the drum sweep, up to 64 chord
shapes), a lowering to the image layout (byte-for-byte what `tools/audio/opl3/opl3_song.py`'s `build()` writes for
the same song is a good golden test), validation mirroring `o3_load_image` plus the "Should" warnings, and the VGM
export.

## Open questions

- Should the system be switchable inside a song later (conversion), or only chosen with New? Proposed: New only for
  now.
- NES instruments: one shared bank for all four channels (FamiStudio's way) rather than per-channel banks. Proposed:
  shared, with the editor greying out what a channel ignores.
- OPL3 VGM export from Kotlin: call the engine's `o3_vgm_render` through JNI/cinterop (one player implementation,
  proven against the Python reference) or port the player to Kotlin (`core`, testable in jvmTest, a third
  implementation to keep in step). Proposed: native.
- OPL3 GD3 tags (title, author, "Thor Tracker") in the VGM: proposed yes, from the song's name and comment.
- OPL3 rhythm mode as an option for drums later? Proposed: not unless asked (its pairs share pitches).
