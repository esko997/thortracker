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

### What SID step 2 needs (data model and lowering)

A `SidSystem` (`system/sid`, id `"c64-sid"`) whose `SidSound` is GoatTracker's instrument model, lowered by a
Kotlin port of `tools/audio/sid/gt_song.py` (`lower()`; byte-identical to GoatTracker's packer for the test
song):

- **Channels:** three voices, all tonal, one shared filter. Notes C-0..G#7 (absolute 0..92: GoatTracker's
  note = Thor Tracker's absolute note; its pattern range ends at G#7, the wavetable reaches B-7). Tunings go
  into the song's 96-entry frequency table (GoatTracker's base-pitch and Scala tunings work that way); there is
  no per-note fine pitch, so `fine` realises nothing and a tuning's "N of M degrees within 5¢" is exact.
- **Instruments** (63 slots, shared by all voices, like the NES): attack/decay, sustain/release, wavetable,
  pulsetable and filtertable start rows, vibrato (speedtable row + delay), hard restart / gate timer (ticks
  before the note: fetch, gate off and hard restart; flags: no hard restart, legato = no gate off) and the
  first-frame waveform (usually `$09` test + gate; `$00` keep, `$FE`/`$FF` gate off/on). The lowering orders
  them hard-restart / no-HR / legato and writes the slot map.
- **Tables** (song-wide, 255 rows each; GoatTracker's editor shows them as a table view): wavetable (waveform
  incl. combined waves, sync/ring/test/gate bits, delays, relative/absolute notes for arpeggios and drums,
  embedded commands, jumps), pulsetable (set width, modulate for n ticks at a signed speed, jumps),
  filtertable (passband + resonance + voice routing, cutoff, modulation, jumps), speedtable (vibrato
  speed/depth, portamento speed, funktempo pairs; `$80` = note-independent). Proposed editor: per-instrument
  "programs" that the lowering allocates rows for, keeping each instrument's rows stable across edits (live
  edits then follow the playing program; see AUDIO_ENGINE, SID "Live edits"), with the raw
  table view available.
- **The global filter:** one cutoff, resonance, routing and passband for the chip. Instruments take it over
  through their filtertable row (GoatTracker's model: the last instrument started with a filter program owns
  it); pattern commands `A` (filter program), `B` (resonance/routing; `B00` off), `C` (cutoff). The editor
  should show which channel last took the filter.
- **Hard restart:** per instrument (gate timer, flags) plus the song's hard-restart ADSR (default `$0F00`;
  GoatTracker's alternative player for `$Fxxx` values is not in the engine).
- **Tempo:** ticks per row 2..128 as `DEFAULTTEMPO` = ticks − 1 and `F03-F7F` (all channels); funktempo
  (`Exx` and `F00/F01`) alternates two tempos; channel tempos (`F83-FFF`) break the shared row clock and
  should not be offered. Multispeed 1-16 per song (ticks per frame); GoatTracker's advice applies:
  multiply tempos and gate timers.
- **Effects** (one column, GoatTracker numbers 1-F): `1xx`/`2xx` portamento up/down and `3xx` toneportamento
  (speedtable rows; `300` = tie note), `4xx` vibrato, `5xx`/`6xx` attack-decay / sustain-release, `7xx`
  waveform, `8xx`/`9xx`/`Axx` wave/pulse/filter program, `Bxx` filter control, `Cxx` cutoff, `Dxx` master
  volume (0-F), `Exx` funktempo, `Fxx` tempo; rows have key off / key on. One-shot commands 5-F let a running
  1-4 effect continue; command 0 returns to instrument vibrato. No jumps or pattern breaks: orders arrange
  the song (like the NES). Effects with speedtable parameters need the lowering to allocate speedtable rows
  (Thor Tracker's effect parameter → a row holding it).
- **Orders:** one pattern per channel per order (the Thor shape), ≤ 254 orders, ≤ 208 distinct
  channel-patterns, ≤ 128 rows; packed pattern ≤ 256 bytes (64 rows with instrument, command and note on every
  row would not fit: validation must say so); song data ≤ 43776 bytes after the fixed tables.
- **Export:** `.sid` (PSID v2, as gt2reloc writes it) and `.prg`; GoatTracker `.sng` export/import is a
  natural addition (`tools/audio/sid/gt_song.py` reads and writes it).
- **Validation** (mirrors `sm_load_image`), Help prose, New → C64 SID, a demo song.

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
7. **SID backend.** Model, lowering, editor (instruments, tables), New → system, Help, validation, export.
8. **FM**: OPL3 first, YM2612 second (see below).

## Effect columns

A system declares how many effect columns each channel may have; the Game Boy keeps one (hUGEDriver stores one
effect per row) and its songs look exactly as today. Richer systems allow several (FamiTracker allows 4, Furnace 8),
with the number shown adjustable per channel to save width on handheld screens. Effect names are one character from
0-9 then A-Z, declared per system, so chips with more than 16 effects fit without changing Game Boy songs. Each effect
declares whether it applies when a note starts (like a parameter lock: no mid-note register clicks) or at once.

## FM (planned)

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

## Open questions

- Should the system be switchable inside a song later (conversion), or only chosen with New? Proposed: New only for
  now.
- NES instruments: one shared bank for all four channels (FamiStudio's way) rather than per-channel banks. Proposed:
  shared, with the editor greying out what a channel ignores.
