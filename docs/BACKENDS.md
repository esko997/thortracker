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
  drives the VRC6, VRC7 (2-operator FM), N163, S5B, FDS, MMC5 and EPSM (a YMF288, OPN3-L) expansion chips, which can
  become later systems on the same driver.
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

## NES expansion chips

FamiStudio's engine also drives the Famicom cartridge sound chips; roadmap step 3 adds them one at a time,
VRC6 first. Decisions (made on the user's behalf; see Open questions):

- **One system per expansion, one family.** FamiStudio assembles its engine for one expansion (its project picks
  one in practice for NSF/ROM export), and a Thor Tracker system has fixed channels, so each expansion is its own
  `SoundSystem` variant beside the plain NES: `NesSystem` (`"nes-famistudio"`, 5 channels), `NesVrc6System`
  (`"nes-famistudio-vrc6"`, 8 channels) and `NesEpsmSystem` (`"nes-famistudio-epsm"`, 20 channels). All are
  `NesFamiStudioSystem`s sharing every rule, view, lowering and export; they differ only in `NesExpansion` (`NONE`,
  `VRC6`, `EPSM`). `SoundSystem.family` (`"NES"`) groups them: **New blank song** and **Load demo** list "NES…" once,
  which opens the variants ("NES (FamiStudio)", "NES + VRC6 (FamiStudio)", "NES + EPSM (FamiStudio)"). The plain NES
  is unchanged: same id, channels, files, lowering bytes, image and `.nsf`.
- **Model.** `NesSound.expansion` (project files: `"expansion":"vrc6"`, left out when none) names the chip; the
  song's system follows from it (`NesSound.system`), and a project whose backend and expansion disagree is rejected.
  Channels keep FamiStudio's engine order: the 2A03's five, then the expansion's (`NesRules.CHANNEL_NAMES` lists them
  all; a song has the first `expansion.channels`). The one bank of 64 sounds stays shared; each sound has a `chip`
  (`NesChip.APU` = 2A03, `VRC6`; FamiStudio's instrument expansion; omitted in files for 2A03), because the same
  envelope means different things on each chip (VRC6 duties 0-7). A channel plays only its chip's sounds
  (validation, audition); only 2A03 sounds map DPCM samples. NES data version stays 2: VRC6 songs are new files,
  2A03 files are byte-identical.
- **VRC6** (two pulses with eight duties and 12-bit periods, a sawtooth): channels VRC6 Pulse 1 (5), VRC6 Pulse 2 (6),
  VRC6 Saw (7, `ChannelKind.SAW`). VRC6 sounds have duty 0-7 (1/16 to 8/16) and the saw's master volume
  (`Vrc6SawVolume` Full / Half / Quarter = volume × 4 / × 2 / × 1 into the 6-bit accumulator rate; Full distorts
  above volume 10, as on the chip). `Vxx` takes 00-07 on the VRC6 pulses and does not exist on the saw; every other
  effect works as on the 2A03 pulses. The VRC6 pulses and saw reach every note C-1..B-8. The lowering sends the
  saw's master volume (`$55`) before an instrument of another volume, and with each order's first instrument so an
  order stays self-contained for seek and live edits; slides on the saw use FamiStudio's saw note table. Editor:
  on songs with an expansion the Instrument view shows a **Chip** row (2A03 / VRC6; switching clamps duties, Listen
  follows to a channel of that chip) and VRC6 sounds a **Saw volume** row; VRC6 sounds have no DPCM page; duty
  steps go to 7 and read 1/16-8/16. Demo: "Saw Sunrise" (`NesVrc6DemoSong`): saw lead (Full) and saw bass (Half),
  a VRC6 duty sweep and 1/16 chords, the 2A03 echo, bass, noise drums and the DPCM starter kit. `exportSamples`
  writes `saw-sunrise.{thor,nsf,txt,nesimg}`.
- **Engine.** A second player build of the same pinned engine (`FAMISTUDIO_EXP_VRC6 = 1`, `NesVrc6PlayerImage`,
  `nes_vrc6_player_image.h`) with its own song base (`$8E00`, the page after the larger player: 29178 bytes shared
  with the samples) and image size; the VRC6 audio unit `nes/nes_vrc6.c`; `NesPlayer` (Kotlin) / `NesPlayer`
  (`nes_player.h`, C) describe each build so the layout builder, validation, machine, `.nsf` export and tracer take
  the build as a parameter. Contract: `docs/AUDIO_ENGINE.md`, "VRC6".
- **Exports.** `.nsf` with the VRC6 player and header byte `$7B` = 1 (NSF players emulate the chip; hardware needs a
  cartridge with a VRC6). FamiStudio text with `Expansions="VRC6"`, `Expansion="VRC6"` and `Vrc6SawMasterVolume` on
  VRC6 instruments, and the `VRC6Square1/VRC6Square2/VRC6Saw` channels. Golden test songs `vrc6_demo` and
  `vrc6_effects` (FamiStudio's export of our text vs our lowering, VRC6 writes compared every frame).
- **EPSM** (`NesEpsmSystem`, `"nes-famistudio-epsm"`, "NES + EPSM (FamiStudio)"): Perkka's Expansion Port Sound Module, a
  Yamaha **YMF288 (OPN3-L)** on the Famicom expansion port at 8 MHz — the YM2608's FM, SSG and rhythm units without its
  ADPCM-B — which FamiStudio's driver writes through `$401C/$401D` (address/data, A1 = 0) and `$401E/$401F` (A1 = 1).
  Channels in FamiStudio's engine order (`NesExpansion.EPSM`, 20 in all): **SSG 1-3** (5-7, `ChannelKind.PULSE`), **FM
  1-6** (8-13, `ChannelKind.VOICE`) and the rhythm unit's **Kick, Snare, Cymbal, Hi-hat, Tom, Rim** (14-19,
  `ChannelKind.SAMPLE`: the YM2608's BD, SD, TOP, HH, TOM, RIM); `NesRules.EPSM_SSG1` / `EPSM_FM1` / `EPSM_RHYTHM1`.
  - *Pitch.* True pitch on every pitched channel (A-4 = 57 = 440 Hz). FamiStudio's EPSM note tables (pinned
    `NoteTables/famistudio_epsm_note_table_*` for FM, `famistudio_epsm_s_note_table_*` for the SSG) play note byte n
    at absolute n − 1, an octave below the name FamiStudio shows: SSG tone period TP = table[n], f = 8 MHz / 64 / TP
    (n = 58 → 440.1 Hz); FM: the driver normalises the table value to an F-number and block, f = F · (8 MHz / 144) /
    2^(21 − B) (n = 58 → 439.1 Hz). So `NesRules.engineNoteShift` is 12 on SSG and FM (`engineNote = note −
    NOTE_OFFSET + shift`; 0 on the 2A03 and the rhythm), FamiStudio's text shows EPSM notes an octave above Thor
    Tracker's (sounding the same), and SSG and FM reach C-1..B-7 (absolute 12..95). The rhythm drums have **fixed
    pitch**: any note 12..107 triggers the channel's drum; the Pattern view names the drum, never a pitch.
  - *Instruments.* `NesChip.EPSM` (no duty envelope): one EPSM sound plays on all 15 EPSM channels, as FamiStudio's one
    EPSM instrument type does. FM channels play its `EpsmPatch` (algorithm, feedback, left/right, AMS, PMS and four
    `YmOperator`s in algorithm-diagram order, the Mega Drive's operator model; `registers(lfo)` gives FamiStudio's 31
    `EpsmPatchRegs`) shaped by the volume, arpeggio and pitch envelopes (the driver turns volume into the carriers'
    total-level attenuation); SSG channels play the volume, arpeggio and pitch envelopes plus a **mixer** envelope (0
    tone + noise, 1 noise, 2 tone, 3 neither; empty = tone), a **noise** envelope (period 0-31) and the hardware
    envelope `epsmEnvelope` (`S5bEnvelope`: shape, auto period with octave −8..8 or a manual period; FamiStudio's EPSM
    defaults off / auto / octave 3 / period 4000); the rhythm channels use only the volume envelope's **first** value
    (0-15, empty = 15: the driver writes level = value × 2 | pan into `$18-$1D`, scaled by `Cxx`) and the patch's
    left/right bits. 2A03 channels take 2A03 sounds only.
  - *Shared hardware, shared in the model.* The chip has **one LFO** (`$22`): `NesSound.epsmLfo` is a song setting
    (0 = off, else bit 3 + rate 0-7), because FamiStudio's driver writes `$22` with every FM sound it loads — a
    per-sound value would be a false promise; exports write it into every EPSM instrument's register 30. The SSG has
    one noise and one envelope generator, shared as on the 5B: the highest SSG square sounding a noise / envelope sound
    sets the noise period / envelope shape and period each frame, any SSG attack with a shape restarts the envelope,
    and `Ixx`/`Jyy` (envelope period high/low, FamiStudio `EnvelopePeriod`) are lowered on SSG 1 as `$5D lo hi`
    (FamiStudio's exporter moves them there). The model does not pretend there are three of either.
  - *Effects and OFF.* FM: `0 4 A C F G P Q R S` (no `Vxx`); OFF on a sounding FM note is always a key-off into the
    patch's release (`$5C`; FamiStudio's `CanRelease` for EPSM sounds on FM), a second OFF stops it. SSG: the same
    effects plus `Ixx`/`Jyy`; OFF releases only a sound whose volume envelope has a loop and release, else stops.
    Rhythm: `A C F G S` only (FamiStudio's `Channel.SupportsEffect`: no pitch effects on a fixed-pitch drum); OFF is
    always a stop (the engine keeps no envelope for drums).
  - *Editor.* EPSM sounds' Instrument pages: Volume, Arpeggio, Pitch, Mixer, Noise, **SSG env** (shape, auto/manual
    period, octave, manual period), **FM** (the shared curated FM editor with the Mega Drive presets), **FM Expert**
    (every register field) and **Rhythm** (level = the first volume step, pan = the patch's left/right, shared with FM;
    the rhythm ROM's status and import). On EPSM songs the Chip row offers 2A03 and EPSM; the song's LFO is a Song-view
    setting. **YM2608 rhythm ROM…** is in the main menu while an EPSM (or YM2608) song is open and on the Rhythm page.
  - *Rhythm ROM (never bundled).* The rhythm unit plays Yamaha's 8 KB ADPCM-A drum ROM (MAME's `ym2608_adpcm_rom.bin`).
    It is not in the repository, APK, AppImage, demos or any export: the user imports their own copy once (**YM2608
    rhythm ROM…**), which `Ym2608RhythmRom.problem()` (`system/fm`, shared with the YM2608 system) checks (exactly 8192
    bytes, SHA-256 `53afd0fa9c62eda3e2be939e23f3adf48a2af8ad37bb1640261726c5d5adeba8`) before the host stores it as
    device firmware — Android `filesDir/firmware/ym2608_adpcm_rom.bin`, Deck
    `~/.local/share/thortracker/firmware/ym2608_adpcm_rom.bin` — and installs it into the native engine
    (`ym2608_rhythm_rom_install`, the one copy every rhythm-playing chip reads) at every start. FM/SSG-only songs play
    without it. A song whose played orders contain a rhythm note, or a rhythm audition, fails with an actionable
    message while it is missing; nothing substitutes another sound. `.nsf` and text exports never need or contain it
    (NSF players bring their own copy).
  - *Engine.* A player build of the same pinned engine with `FAMISTUDIO_EXP_EPSM = 1` (`NesEpsmPlayerImage`,
    `nes_epsm_player_image.h`, `NES_EXPANSION_EPSM` = 7 as FamiStudio's `ExpansionType`) and the YMF288 emulated by
    the vendored ymfm's `ymf288` (BSD-3, the Mega Drive's ymfm pin), as Mesen2 does. The machine outputs true stereo
    (FM and rhythm pans; the 2A03 centred); seeks clock the chip. Contract: `docs/AUDIO_ENGINE.md`, "EPSM".
  - *Exports.* `.nsf` with header byte `$7B` bit 7 (EPSM); FamiStudio text with `Expansions="EPSM"`, EPSM instruments
    as `EpsmPatch="0"` (custom) with `EpsmReg0`-`EpsmReg30`, `EPSMSquareEnvelopeShape` / `AutoPitch` /
    `AutoPitchOctave` and `MixerSettings` / `NoiseFreq` envelopes, channels `EPSMSquare1-3`, `EPSMFM1-6`,
    `EPSMRythm1-6`, effect `EnvelopePeriod`. FamiStudio 4.5.3's text format has no attribute for the SSG's manual
    envelope period (it always reads back 4000), so the text export refuses, with the sound and a fix, an EPSM sound
    with a manual period other than 4000; `.nsf`, WAV and playback are unaffected. WAV through the generic path.
  - *Demo.* "Expansion Port" (`NesEpsmDemoSong`), A minor: an FM lead (algorithm 4, the song LFO as vibrato), slap
    bass (algorithm 0), chorused pad (algorithm 5), organ (algorithm 7), bell and brass on FM; an SSG arpeggio shimmer,
    a hardware-envelope buzz bass (auto period, tone and noise off) and tone+noise drums on the SSG; the 2A03 echo,
    plucks, triangle bass, noise hats and the DPCM kick. It has **no rhythm notes**, so it plays without the ROM.

**Adding the next expansion** (VRC7, N163, S5B, FDS, MMC5, EPSM) follows the same path:
1. `prepare_nes_player.py`: assemble `nes_player.s` with `-D FAMISTUDIO_EXP_<CHIP>=1` (and its note tables), song
   base = the page after it; generate `nes_<chip>_player_image.h`, `Nes<Chip>PlayerImage.kt` and fixtures from a
   FamiStudio-exported test text; check the existing builds' outputs stay byte-identical.
2. Native: the chip's audio unit (`nes/nes_<chip>.c`, from NESdev), its registers in `bus_write`, a
   `NesPlayer` entry in `nes_players.c` (`NES_EXPANSION_<CHIP>`, channels, duty range, symbols), its opcode and
   duty rules in `nm_walk_entry`, an ops table in `nes_chip.c` registered in `chip_engine.c`, a host test.
   `NM_MAX_*` grow to the largest build.
3. Kotlin: a `NesExpansion` entry (chip, extra channels), a `NesChip` (label, duty range, its instrument fields), a
   `NesPlayer` entry, channel names/kinds appended to `NesRules`, its effects/opcodes in `FamiStudioLowering`, its
   text attributes in `FamiStudioText`, a `Nes<Chip>System` object (id, name, demo) in `SoundSystems.ALL` (the
   "NES" family shows it under New/Load demo), Instrument view rows for chip-specific fields, help, golden songs.
4. Docs: a section here and in `docs/AUDIO_ENGINE.md`.
Chips with instruments unlike envelopes (VRC7 patches, N163 waves, FDS waves and modulation) need their own
instrument fields and editor pages; songs combining several expansions would need FamiStudio's multi-expansion
engine build and a system per combination, so they are left out unless asked.

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
   Python reference player in every tick and VGM bytes identical to its export; the VGM plays in libvgm.
   **OPL3 backend**: model, curated instrument controls, presets, lowering (byte-identical to the reference builder),
   validation, project files, VGM export through the native player and the demo — see "OPL3 model, lowering and
   exports"; editor (instruments, effect columns), New → system and Help in the editor step.
   **YM2612 spike** — done (branch `ym2612-spike`). `chip_engine_ops("ym2612-fm")`: our own player (C) streamed as XGC
   frames through SGDK's XGM Z80 driver (MIT, the driver games ship) on `z80.h` with ymfm and our SN76489; image,
   player, effects, XGM/XGC encoding, seek, live edits, audition, mute and VGM/XGM/XGC export in
   `docs/AUDIO_ENGINE.md` ("YM2612"); native host test 460 checks: register writes identical to an independent
   Python reference in every tick, the driver's chip writes equal to the stream, the stream and the XGM/XGC exports
   byte-identical to SGDK xgmtool's conversion of the VGM; the VGM plays in libvgm (Nuked-OPN2 and Genesis Plus GX).
   **YM2612 backend** (next): model, FM editor reuse from the OPL3 (OPN2 operator set), PSG instruments, lowering,
   validation (heavy rows), project files, exports and the demo.

## Effect columns

A system declares how many effect columns each channel may have; the Game Boy keeps one (hUGEDriver stores one
effect per row) and its songs look exactly as today. Richer systems allow several (FamiTracker allows 4, Furnace 8),
with the number shown adjustable per channel to save width on handheld screens. Effect names are one character from
0-9 then A-Z, declared per system, so chips with more than 16 effects fit without changing Game Boy songs. Each effect
declares whether it applies when a note starts (like a parameter lock: no mid-note register clicks) or at once.

Model (done with the OPL3): `SoundSystem.effectColumns(channel)` (default 1; the OPL3 4). `Cell` keeps
`effect`/`param` as column 0 and adds `more: List<Fx>` for columns 1-3, always canonical (no trailing blank column),
so a cell that only uses column 0 is equal to, and saves exactly as, a cell from before (`[note, instrument, effect,
param]`; further columns append `effect, param` pairs). `withEffect(column, code, param)`, `effectCode/effectParam`,
`lastEffectColumn` and `hasAnyEffect` are the accessors; `hasEffect` still means column 0. The Game Boy, NES and SID
reject extra columns (`cellRangeProblem`), and `OneColumnProjectBytesTest` pins their demo, blank and example
project files to the SHA-256 the encoder wrote before effect columns existed. How many columns a pane shows is
editor state (per pane, automatic from the highest used column), not song data.

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
  on the dev PC, below the SID 8580's 593 µs; measured per tick on devices (engine, 2026-11-08): Nova 0.32 ms, DMG
  0.62 ms, Konkr 1.29 ms mean (8 % of a core). ymfm stays the option for weaker devices and is a candidate core for
  the YM2612.
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
- **VGM loop**: the loop point carries a dump of the register state so every pass starts from the same registers.
- **Loop speed is the player's**: every arrival at the loop order's row 0 (the wrap, a `Bxx` back, the first pass)
  starts it at the speed sequential straight play reaches it with (`O3Info.start_speed`), so later passes keep the
  first pass's tempo without an `Fxx` there and the lowering emits none (no warning either). A `Bxx`/`Dxx` that
  skipped an `Fxx` before the loop order's first arrival therefore sets that derived speed already on the first pass.
- **Seek** (play from a row): the player is exact (it runs alone from the song start); the chip is clocked through
  the last second before the target (`O3M_PREROLL_TICKS` 60 × multispeed), so held notes and release tails arrive
  under way — sound identical to straight play when the target is within that second of the start, else the 20 ms
  loudness envelope stays within ~3 % (host test). Cost ~31 ms on the dev PC (~75 ms estimated on the Konkr), on the
  control thread's spare machine, as SID seeks (~88 ms).
- **VGM export on the hosts**: the native player renders it (`o3_export_vgm`, `opl3/opl3_export.h`: Android JNI
  `ChipAudio.nativeOpl3Vgm`, Deck cinterop `DeckOpl3Vgm`, both registered as `Opl3Vgm.renderer`), one player
  implementation proven against the Python reference; the GD3 1.00 tag (track = song title, author, system
  "OPL3 (Thor Tracker)", date = today unless given, converter "Thor Tracker") is written natively too.

### OPL3 model, lowering and exports

- **System** (`system/opl3`): `Opl3System`, id `"opl3-fm"`, nine channels `FM 1`-`FM 9` (`ChannelKind.VOICE`,
  tonal), four effect columns each, notes C-0..B-7 (absolute 0..95 = the player's note; FamiTracker-style names,
  A-4 = 57 = 440 Hz), one bank of 64 slots, 60 ticks per second × multispeed, at most 256 orders. OFF is key off
  (the release plays, chord notes too). Song settings (Song view): multispeed 1-8, tremolo depth 1/4.8 dB, vibrato
  depth 7/14 cents (register 0xBD). Constants come from `backend/Opl3PlayerImage` (generated from the C headers).
- **Chip data** (`Opl3Sound`): 64 instrument slots, each `null` (empty: not in the image, a cell naming it is an
  error "instrument 0D is empty") or an `Opl3Instrument`: `fourOp`, `algorithm` (2-op 0 FM/1 additive; 4-op 0-3),
  operator 1 `feedback`, always four `Opl3Operator`s holding exactly the chip's register fields (`mult tl ar dr sl rr
  wave ksl am vib sustain ksr`; a 2-op instrument keeps operators 3-4 for a later switch to 4-op, lowered as zeros),
  and the drum `Opl3Sweep` (start/end −96..96 semitones from the note, speed in 1/16 semitone per tick, 0 = none);
  the chord-shape table (`Opl3Chord`: name, 1-4 non-zero intervals −48..48; new songs get 16: maj, min, 7, maj7, m7,
  sus2, sus4, dim, aug, add9, m9, 5+8 and four inversions); multispeed and the two LFO depths. Empty slots (rather
  than 64 always-defined instruments) keep the image's instrument count meaningful and let the spike's image be
  reproduced byte for byte.
- **Curated controls** (`Opl3Curated`, one place for every list and macro): algorithm (6: 2-op FM, 2-op additive and
  the four 4-op routings), ratio sets (22 two-operator `mod:car` sets such as 1:1, 2:1, 7:1, ½:1, 3:2; 22 four-operator
  sets including the E-piano tine 14:1:1:1 and bell 3:7:1:2), waveform combinations (20 per operator count; sine
  carriers under each modulator shape, then shaped carriers, leaving out combinations that sound alike or cancel),
  brightness 0-63 (= 63 − the lowest modulator TL; moving it shifts every modulator's TL together, keeping their
  differences), level 0-63 (the same for carriers), attack (all operators' AR), decay (carriers' DR), tone decay
  (modulators' DR: how fast brightness fades), sustain 0-15 (0 = percussive: EGT off; else EGT on and SL = 15 −
  value, all operators), release (all RR), feedback, key scaling Off/Soft/Strong (modulator KSL 1.5 or 3 dB/oct,
  carriers 1.5 dB/oct and KSR for Strong; additive instruments scale their carriers) and LFO
  None/Vibrato/Tremolo/Both (VIB on all operators, AM on carriers). **Rule**: the operator registers (the Expert
  grid) are the instrument; each control is read from the fields it owns — a list control shows the entry the
  registers match, or the nearest entry marked approximate (≈) after hand edits; a macro reads the first carrier (or
  modulator) and is approximate when the operators it writes disagree — and stepping a control writes only its own
  fields. Changing the algorithm moves operators between roles and then shifts carriers and modulators so level and
  brightness read as before.
- **Presets** (`Opl3Presets`, 17): E-piano (4-op two pairs), Brass, Bell (4-op 1+(2→3)+4), Organ (4-op 1+chain),
  FM bass, Pad, Lead, Pluck, Marimba, Clav, Flute, Organ 2-op (additive), and pitched drums Kick (sweep +24 → 0),
  Snare (+7 → −5), Tom (+7 → −2), Hi-hat and Open hat, each with the note it was voiced at. New songs have them in
  slots 01-11, so every channel is musical at once; the rest are empty.
- **Lowering** (`backend/Opl3Lowering`): the arrangement (`Opl3System.arranged`: parked orders removed; the loop
  order and every `Bxx` in any column renumbered; `Bxx` = order xx) into the image layout of `docs/AUDIO_ENGINE.md`:
  each order's rows = its pattern's length; each (order, channel) track encoded as the player reads it (empty-row
  runs, mask, note/`0xFE`, slot, the non-blank effect columns in column order — a blank column before a used one is
  left out), byte-identical tracks stored once in first-use order; every filled slot is written (instrument count =
  the highest), so any instrument can be auditioned. Pattern loop playback aims every `Bxx` at the looped order.
  Explicit `000` cannot be stored (it is the empty effect, as on the Game Boy), so `0xy` on a row without a note
  cannot switch an arpeggio off mid-note; a note without `0xy` plays plain.
- **Tunings** (`FinePitch` `Pxx`): a tuned note gets `P80+n` (n sixty-fourths of a semitone, 0-63) in the first free
  effect column (or the column already holding `P`): every degree within 0.8 cents, plus the chip's F-number
  rounding (≤ 2.6 cents from 12-TET).
- **Validation** (`Opl3Validator`): structural (shapes, ranges, 64 slots, ≤ 64 chord shapes of 1-4 intervals,
  multispeed 1-8, at most 4 effect columns, each instrument's fields) and what `o3_load_image` rejects, as messages
  naming pattern, channel and row: an empty slot, a 4-op instrument on FM 7-9 ("channels FM 7-9 play 2-op
  instruments only"), `Bxx` past the last order, `Cxx`/`Ixx` above 3F, `Exx` above 7, `F00`, `Kxx` beyond the table,
  `Mxy` operator not 1-4, `Wxy` out of range, unknown effects, pattern data beyond the image's 56.9 KB. Warnings
  (one straight pass in order): a first note of an order without instrument, locks and chords on rows without a note
  (ignored by the player), chords on 4-op instruments (only the root sounds), `Gxx` at or beyond the speed (never
  plays), and rows whose sounding chords need more spare voices than the pool has at that point (3 + one per FM 1-6
  channel playing 2-op), since the player then steals the oldest chord notes. No loop-speed warning: the player owns
  the loop's speed.
- **Project files:** backend `"opl3-fm"`, OPL3 data version 1 (README, "Project format").
- **Export:** `.vgm` (`Opl3Vgm.export`): the image is rendered by the native player through `Opl3Vgm.renderer`, which
  each host registers (Android JNI, Deck cinterop); JVM unit tests have no renderer and check the seam with a stand-in
  (the image and tags handed over), while the native host test proves the renderer's bytes against the Python
  reference. A ten-minute tick cap ends songs that never return to their loop point.
- **Golden tests** (`Opl3GoldenTest`, `tools/audio/opl3/opl3_golden.py`): the spike's test song and a features song
  (notes C-0 and B-7, slot 64 behind empty slots, negative and extreme sweeps, chord digits with a zero and every shape
  length incl. ±48 and inversions, four effect columns and blank columns before used ones, an instrument without a
  note, orders of 64, 20 and 1 rows, multispeed 2, both LFO depths, `Bxx`/`Dxx` across a parked order) rebuilt from
  Thor Tracker's model lower to exactly the bytes the reference builder writes (the spike's song without its one
  explicit `000`). `Opl3ModelTest`: cells and columns, project round trips, curated controls read back what they
  write, approximate readings, tunings, validation and warning messages, arrangement, the VGM seam, the demo.
- **Demo:** "Glass Harbour" (`Opl3DemoSong`, C major/A minor, Fmaj7-G-Em7-Am, speed 7): a 2-op lead with vibrato,
  brightness and waveform locks in up to four columns, a portamento glide and a rising slide with a volume fade; FM
  bass; a 4-op E-piano arpeggio and a 4-op bell counter-melody panned left; a pad playing chord shapes (`K04` maj7,
  `K0A` add9, `K05` m7, `K0B` m9) on spare voices, panned left; pluck stabs with `Hxy` chords panned right; swept FM
  kick, snare, hats and a tom fill moving across the stereo field. Intro, theme A, theme B, theme A, fill; loops to
  theme A. No errors or warnings; `exportSamples` writes `glass-harbour.{thor,o3img}` and
  `tools/audio/render_opl3_image.sh` renders the image (and its VGM).

### OPL3 editor

- **Effect columns in the Pattern view** (shared code; active only where `effectColumns` > 1, so the Game Boy, NES and
  SID views and their controls are unchanged): a channel's cursor fields are note, sound, then effect type and value
  per shown column (`ViewController.fieldCount`, `columnOf`, `isValueField`); ←→ walk them and then the next channel.
  Each column is picked, valued, re-entered (tap Edit repeats that column's last effect) and cleared on its own;
  copy, cut, paste, duplicate, insert/delete row and selections move whole cells, all columns included
  (`normaliseCell` keeps the columns the channel has). OFF on a row with effects keeps them (one-column systems
  still replace the row). The shown count is per pane (`ViewState.effectColumns`, saved in the Android workspace):
  automatic = the highest column the song uses on that channel (at least one), or set on the Pattern panel's
  Columns row (←→ count, L1/R1 channel, Clear automatic). Effects in hidden columns still play: the row shows a
  dot after its last column and the lane header "+n fx".
- **Naming effects**: on several-column systems the status line names every effect as it is picked or changed, with
  the system's longer reading (`SoundSystem.effectDetail`: the OPL3's `Hxy`/`Kxx` chord notes from the row's note,
  why a chord stays silent — no note, a 4-op sound — and `0xy` arpeggio notes); the hint names the column's effect;
  the inspector lists every column of the cell (hidden ones marked) with the cursor's in full.
- **9 channels on handheld screens** (decision): the text keeps its 12 dp size; lanes are `note sound` plus 4
  characters per shown column, and when they do not all fit they scroll sideways by whole lanes, following the
  cursor's channel (FamiTracker/Furnace style), with "◀n" / "n▶" counts (tappable) beside the headers. Shrinking
  text to fit 9 × 2-4 columns would be illegible on 6" and 3.9" displays; scrolling keeps every edit in view and
  narrowing busy channels' columns shows more lanes. Measured: one column per channel shows all 9 lanes on the
  Deck (1280×800) and the Thor's top display (1920×1080 at 369 dpi); with two columns everywhere the top display and
  the Deck show 7, the bottom one (1240×1080) 4. The inspector stays beside scrolled lanes only while 6 lanes still show,
  otherwise the lanes take its room (the status line names effects while editing). Deck test hook:
  `size=WxH[@density]` renders at the Thor's sizes under the offscreen driver.
- **Instrument view** (`Opl3InstrumentView`, `Opl3InstrumentPainter`), controller-only, four pages. *Sound*: the
  curated controls of `Opl3Curated`, each one Edit+←→ value (preset, algorithm incl. 2-op/4-op, ratio set,
  waveforms, brightness, level, feedback, attack, decay, sustain, release, tone decay, key scaling, vibrato/tremolo;
  "≈" marks a reading of hand-edited registers) beside the drawn algorithm (operator boxes with ratio and waveform,
  routing arrows, carriers into the output, the feedback loop) and envelope (carriers in dB with times; the
  modulators' tone decay dimmer). *Expert*: mode, connection and feedback as stored, then every register field of
  each operator as a grid (←→ or L1/R1 move between operators; AM/VIB/EGT/KSR switch on tap). *Drum*: drum presets
  and the sweep's start, end and speed with the pitch curve over half a second. *Chords*: the song's `Kxx` table
  with each shape's notes from the listening C; Edit adds shapes and intervals, Edit+←→ changes an interval (±12 on
  ↑↓, never 0), Clear deletes one (a shape's last deletes the shape); shapes take their default-table name when
  their intervals match one. Holding Edit on a sound row (or Listen) auditions on the Listen channel; 4-op sounds
  listen on FM 1 when Listen is FM 7-9. Empty slots (null) get the Lead preset on their first edit; Clear on Slot
  empties one. Every edit is one undo step (`setOpl3Instrument`, `editOpl3Chords`).
- **Shared views**: New and Load demo list the OPL3 through `SoundSystems`; Song lists its settings, Keyboard and
  Sketch play any of the 9 channels; channel 9 has its own lane colour. Help: effects page ("Up to 4 effects per
  channel per row"), the Pattern rows for columns, hidden effects and lane scrolling, and the instrument pages.
- Tests: `Opl3EditorTest` (columns, cursor walk, per-column edits, copy/paste, OFF, chord text, every instrument
  page, empty slots, 4-op listening; one-column systems keep four fields).

### YM2612 (Mega Drive): driver investigation and decisions (2026-11-08, branch `ym2612-spike`)

Decision rule (the integrator's, on the user's behalf): if SGDK's XGM driver can run in the loop, build that; else
fall back to the OPL3 approach (own player, chips driven directly, VGM export). **Result: XGM in the loop is
feasible and is what `"ym2612-fm"` does.** The full contract is `docs/AUDIO_ENGINE.md`, "YM2612".

Evidence (SGDK `github.com/Stephane-D/SGDK` commit `9a78a441`, 2026-10-20; spike tools in `/tmp/ym_bench/`):

- **Licence**: the whole SGDK repository is MIT (`license.txt`: "MIT License - Copyright (c) 2025 Stephane
  Dallongeville"); neither the driver source `src/snd/drv_xgm.s80` (and its includes `inc/snd/z80_def.i80`,
  `z80_mac.i80`, `z80_fct.i80`) nor `tools/xgmtool` carry other terms. Games link the assembled driver from
  `lib/libmd.a`. SGDK's assembler `tools/sjasm` (SjASM 0.39j, Konamiman 2021 after Sjoerd Mastijn) is zlib.
- **Provenance**: `drv_xgm.s80` assembled with SGDK's own sjasm (built from `tools/sjasm/src` on Linux) is
  byte-identical to the `drv_xgm` symbol in SGDK's prebuilt `lib/libmd.a` (5888 bytes, sha256 `557fe542…cf31e`).
- **What the driver needs from the 68000**: nothing at play time but a few writes into Z80 RAM, all in
  `src/snd/xgm.c`: load the driver, the sample id table (`$1C00`), the music address (`PARAMS+$00`), the play
  command (`COMMAND` bit 6), and per vertical interrupt `PENDING_FRM` += 1 (`PARAMS+$0F`, after `MODIFYING_F` at
  `+$0E` reads 0: `setNextXFrame`). The Z80 does everything else (prefetches a frame of music data through the
  bank window `$8000`, executes it, mixes PCM in cycle-counted 254-cycle slots). So the engine emulates the Z80
  (floooh `z80.h`, cycle-stepped, the `m6502.h` pin), the YM2612, the PSG and the 68000's pokes; no 68000 emulation.
- **Format**: XGM is a register stream, not a tracker format. `xgmtool` (1.76) turns a VGM into XGM (per frame:
  YM registers diffed against a state and re-emitted in a canonical order, A4/A0 pairs together, key writes kept
  between the register writes before and after them, PSG diffed; `VGM_cleanCommands`, `XGM_extractMusic`) and XGM
  into the compiled **XGC** the driver reads (frames prefixed by their size, PSG split into envelope/tone
  commands, at most 6 key writes per command, D1L/RR "state" commands for pause, frames above 250 bytes split with
  a frame-skip command, 3 empty frames in front for PCM alignment, `$2B` writes removed). SGDK's `rescomp`
  accepts a `.vgm` or `.xgm` resource and runs `xgmtool` to get the XGC. So Thor Tracker still needs **its own
  player** (rows and effects to register writes, as for the OPL3); its per-tick writes are the VGM, and the XGM/XGC
  come from them by xgmtool's algorithm (ported to C, `ym/ym_xgm.c`; the host test proves it byte-equal to xgmtool).
- **Spike** (`/tmp/ym_bench/bench/xgm_feas.cpp`): the driver on `z80.h` with SGDK's 68000 pokes boots in 20415
  Z80 cycles and plays XGCs that xgmtool made from SGDK's sample VGMs (`sonic1`, `midnight`, `toy_story`): about
  15000 YM writes per second (mostly the DAC byte it rewrites every PCM slot). Z80 + driver alone cost 317 µs per
  60 Hz frame on the dev PC (59659 Z80 cycles).
- **Driver behaviour that matters for a tracker**: (1) it prefetches frame *n*+1 right after executing frame *n*, so
  the engine produces frames one tick ahead (a live edit is heard one tick later, ≤ 20 ms); (2) it waits for the
  YM2612's busy flag between writes (32 internal cycles ≈ 90 Z80 cycles, 1.3 YM samples), so a key-off and key-on
  of one channel in one frame do retrigger; (3) its `initDAC` enables the DAC, but its PCM mixer rewrites `$2B`
  from a saved value (0, as xgmtool strips `$2B`) whenever no PCM has played for 4 frames, so **FM 6 is a normal FM
  channel** in songs without PCM; (4) XGM v1 advances one frame per vertical interrupt (60 Hz NTSC, 50 Hz PAL):
  no multispeed.
- **Cores** (`/tmp/ym_bench/bench/core_bench.cpp`: the driver's writes for 20 s of `midnight`/`toy_story`
  captured with Z80-cycle timing and replayed into each core, dev PC): **Nuked-OPN2 1.0.9** (`335747d7`,
  LGPL-2.1-or-later, die-accurate, clocked per internal cycle) 886-902 µs per 60 Hz tick; **ymfm** (`81aec25c`,
  BSD-3-Clause, per sample) 165-204 µs. Against Nuked: sample correlation 0.66 / 0.91, 20 ms loudness-envelope
  correlation 0.994 / 1.000 (output scale ×7.0); both model the YM2612's 9-bit DAC discontinuity. Interleaved
  with the cycle-stepped Z80 Nuked cost 1.64 ms per tick. With the OPL3's measured device ratios (Konkr ≈ 2.6× the
  dev PC for Nuked, 3.3× for ymfm) the Nuked machine would need ~3.5-4 ms per 16.7 ms tick on the Konkr, twice the
  SID 8580 (the heaviest system so far, 1.8 ms); ymfm ~1.6 ms. **Chosen: ymfm** (not affordable on the weakest
  device with the Z80 in the loop). The busy flag the driver polls is modelled through ymfm's interface (32 × 6
  YM clocks after each data write, ymfm's own figure) on the Z80's clock.
- **PSG**: our own SN76489 (`ym/ym_psg.c`, the Sega VDP variant: 16-bit LFSR, white-noise taps 0x0009, 2 dB
  steps), written from the SMS Power documentation; no third-party core.
- **Not chosen**: XGM2 (SGDK 2.x, also MIT, Z80) — XGM v1 is what `rescomp`'s `XGM` resource and xgmtool ship
  and its format is a plain register stream; XGM2 is a later option. Echo (zlib) and MDSDRV (zlib) sequence on the
  68000 (would need a 68000 emulator in the engine). GEMS is proprietary.

Decisions:

- **Engine** (`"ym2612-fm"`): our player (`ym/ym_player.c`, specified in `docs/AUDIO_ENGINE.md`, mirrored by an
  independent Python reference) → per-tick writes → XGC frames (`ym/ym_xgm.c`, xgmtool's algorithm) streamed into
  the emulated cartridge → SGDK's XGM Z80 driver on `z80.h` → ymfm YM2612 + our SN76489, resampled with blip_buf.
  What the engine plays is what `XGM_startPlay` plays in a game.
- **Exports**: VGM 1.71 (the player's writes, YM2612 + SN76489) and XGM 1.01 (`.xgm`, the `rescomp` resource;
  `XGM song "song.xgm"` in a game's `.res`), plus the compiled XGC (`.bin`, what `XGM_startPlay` takes directly).
- **Channels**: FM 1-6, PSG 1-3, Noise (10 tracker channels). Channel 3 special mode and the DAC/PCM (which the
  driver supports on FM 6, up to 4 mixed channels) are left out of this release. No chord effect (6 voices, none
  spare): chords are notes across channels.
- **Region**: NTSC (60 Hz, YM2612 7670453 Hz, PSG 3579545 Hz) by default, PAL (50 Hz, 7600489 / 3546895 Hz) per
  song; pitch tables per region so A-4 = 440 Hz on both. A PAL song in a game needs `XGM_setMusicTempo(50)` on a
  PAL console (SGDK's tempo is NTSC-based by default).
- **Instruments**: 64 slots (shown as two banks, FM voices and PSG sounds, over the one numbering), each empty, an FM instrument (the chip's operator registers: DT/MUL, TL,
  RS/AR, AM/D1R, D2R, D1L/RR, SSG-EG per operator; algorithm, feedback, AMS/FMS; drum sweep) or a PSG instrument
  (software envelopes as on the NES: volume, arpeggio and pitch, one value per tick; noise mode; drum sweep — the
  spike's attack/decay/sustain/release record was replaced, see below). FM slots play on FM channels, PSG slots on PSG
  and Noise. Drums are pitched instruments with the sweep, as on the OPL3.
- **Effects** (FamiTracker/OPL3 letters where meanings match, up to 4 columns): `0xy` `1xx` `2xx` `3xx` `4xy` `8xy`
  (FM pan) `Axy` `Bxx` `Cxx` (FM 00-7F, PSG 00-0F) `Dxx` `Exx` (feedback lock) `Fxx` `Gxx` `Ixx` (brightness lock,
  00-7F) `Lxx` (LFO: 00 off, 08-0F on at rate 0-7) `Mxy` (ratio lock) `Nxy` (noise mode lock) `Pxx` `Sxx`.
- **Noise**: fixed rates (clock/512, /1024, /2048) or PSG 3's period ("pitched": the note sets it, periodic noise
  sounds at the note); while pitched noise sounds it owns PSG 3, which is silent.
- **Cost: exact pass replay** (2026-11-08, after the Konkr measured 1.9 ms per warm tick, 1.3 ms of it the Z80 and
  driver): without PCM the driver spends ~92 % of its cycles in fixed code that reads nothing the music changes
  (its PCM mixer and its frame-wait loop). The machine records such a pass once and replays it whenever it starts
  with the same inputs (chips run alone and get its writes at the same cycles; the Z80 resumes in the recorded
  state), so the driver and its timing stay exactly the spike's: the host test runs replayed and fully stepped
  machines side by side and compares samples, chip writes, positions, Z80 state and RAM. Dev PC 695 → 352 µs per
  tick, ymfm now two thirds of it. Rejected: running the Z80 only until the frame is done (or skipping the mixer)
  — cheaper to write but it changes when the driver executes frames and how far it lags behind heavy ones, which
  a game with the same XGC would not do.
- **PSG instruments as envelopes** (the user's NES-like editor): the spike's ADSR record became references to
  FamiStudio-style volume/arpeggio/pitch sequences in the image's data region (`docs/AUDIO_ENGINE.md`, "Image
  layout"); OFF plays the volume envelope's release tail, or silences a note without one.

### YM2612 model, lowering and exports

- **System** (`system/ym2612`): `YmSystem`, id `"ym2612-fm"`, "Mega Drive (YM2612 + PSG)", ten channels `FM 1`-`FM 6`
  (`ChannelKind.VOICE`), `PSG 1`-`PSG 3` (`PULSE`) and `Noise` (`NOISE`, not tonal: its notes only matter in pitched
  mode), four effect columns each, notes C-0..B-7 (absolute 0..95 = the player's note; FamiTracker-style names, A-4 = 57
  = 440 Hz), 64 instrument slots shown as two banks over the one numbering — `FM voices` (FM 1-6) and `PSG sounds` (PSG
  1-3, Noise), so the editor keeps a current sound per kind (`SoundSystem.defaultInstrument`: the first slot of the
  bank's kind) — at most 256 orders. Ticks per second follow the region: 59.92 (NTSC, MCLK / (3420 ×
  262)) or 49.70 (PAL). OFF is key off (FM release; a PSG sound jumps to its volume envelope's release point, or is
  silent at once without one). Song settings (Song view): Region (NTSC/PAL) and LFO (off or rate 0-7, register $22 at
  the song start). Constants come from `backend/YmPlayerImage` (generated from the C headers).
- **Chip data** (`YmSound`): 64 slots, each `null` (empty: an error when a cell names it) or a `YmInstrument`, stored
  with `"type"`: `YmFmInstrument` (`"fm"`: `algorithm` 0-7, operator 1 `feedback`, the LFO depths `ams` 0-3 and `fms`
  0-7, four `YmOperator`s holding exactly the chip's register fields `dt mul tl ks ar am d1r d2r sl rr ssgEg`, and
  the drum `YmSweep`) or `YmPsgInstrument` (`"psg"`: FamiStudio-style `volume` 0..15, `arpeggio` −64..63 semitones and
  `pitch` −64..63 sixteenths of a semitone envelopes, one value per tick, as `NesEnvelope` (loop, and a release point
  on the volume envelope only), `pitchRelative`, the Noise channel's `noise` mode — white/periodic and clock/512,
  /1024, /2048 or pitched by PSG 3 — and the sweep); `pal` and `lfo`. FM slots play on FM 1-6, PSG slots on PSG 1-3
  and Noise; a slot on the wrong kind of channel is an error. **PSG envelopes instead of the spike's ADSR** (decided
  on the user's behalf): the NES editor's envelopes are what chiptune users know, arpeggio envelopes give PSG chords
  without spare voices, and the editor reuses the NES envelope editor.
- **Curated controls** (`YmCurated`, the OPL3's rule: the operator registers are the instrument; each control reads the
  fields it owns, shows the nearest entry "≈" after hand edits and writes only its own fields): the 8 algorithms
  (name, one-line diagram and routing edges/carriers for drawing: Chain, Two into chain, 1 and chain into 4, Pair and
  3 into 4, Two pairs, One into three, Pair and two sines, Additive), 26 ratio sets of four multipliers with detune
  patterns ("chorus": operators 1-2 up, 3-4 down; "shimmer") including the E-piano tine 14:1:1:1 and bells, 11 SSG-EG
  presets (off; saw/bounce/rise shapes on the modulators — the tone cycles — or the carriers — the volume cycles or
  holds), brightness and level 0-127 (127 − the lowest modulator/carrier TL, shifting the group together), attack
  (every AR), decay (carriers' D1R), tone decay (modulators' D1R), sustain 0-15 (every SL), sustain rate (every D2R),
  release (every RR), feedback, key scaling Off/Soft/Medium/Strong (KS 0-3 on every operator), vibrato depth (FMS) and
  tremolo depth (AMS, with AM on the carriers). Changing the algorithm moves operators between roles and keeps level
  and brightness as before.
- **Presets** (`YmPresets`, 24, categories FM/DRUM/PSG/NOISE): Slap bass, Finger bass, Brass, E-piano, Bell, Strings,
  Lead, Organ (additive), Pluck, Pad; FM drums Kick (sweep +30 → 0), Snare (feedback noise over a +12 → 0 body), Tom
  (+12 → −2), Hi-hat and Crash; PSG lead (volume loop with a release tail and a delayed pitch vibrato), PSG pluck, PSG
  arp major/minor (arpeggio envelopes); Noise snare, hat, open hat, a pitched white-noise tom with a sweep and the
  pitched periodic Buzz bass. New songs have them in slots 01-24.
- **Lowering** (`backend/YmLowering`): the arrangement (`YmSystem.arranged`: parked orders removed; the loop order and
  every `Bxx` in any column renumbered) into the layout of `docs/AUDIO_ENGINE.md`: each order's rows = its pattern's
  length; each (order, channel) track encoded as the player reads it, byte-identical tracks stored once in first-use
  order; then the PSG envelopes (slots ascending; volume, arpeggio, pitch; byte-identical envelopes stored once; an empty
  envelope is `$FFFF`); every filled slot is written (instrument count = the highest).
- **Tunings** (`FinePitch` `Pxx`, as the OPL3): `P80+n` in the first free effect column; every degree within 0.8 cents.
- **Validation** (`YmValidator`): structural (shapes, ranges, 64 slots, the LFO byte, every operator and envelope
  field) and what `ym_load_image` rejects, as messages naming pattern, channel and row: an empty slot, an FM/PSG
  instrument on the other kind of channel, effects a channel does not play (`8`, `E`, `I`, `M` FM only; `N` Noise
  only), `Bxx` past the last order, `Cxx` above 7F (FM) or 0F (PSG), `Exx` above 7, `F00`, `Ixx` above 7F, `Lxx` not
  00/08-0F, `Mxy` operator not 1-4, `Nxy` out of range, unknown effects, data beyond the image's 56.2 KB. Warnings (one
  straight pass): a first note of an order without instrument, locks on rows without a note, `Gxx` at or beyond the
  speed, PSG notes below A-2 (the 10-bit period's floor), PSG 3 notes while pitched noise owns PSG 3, and **heavy rows**:
  the YM writes a row's key-ons need, estimated from the shadowed registers (a different instrument ≈ 30, changed locks
  8, a retrigger 4), above the XGM driver's ~90 per frame (the song's first row, which the reset makes heavy anyway, is
  not reported).
- **Exports** (`YmExport`): `.vgm` (VGM 1.71 + GD3), `.xgm` (SGDK's `rescomp` `XGM` resource, with the GD3) and `.xgc`
  (the compiled stream `XGM_startPlay` takes; the audio document calls it `.bin`, xgmtool's default; `.xgc` keeps it
  apart from other binaries) through `YmExport.renderer`, which each host registers (Android JNI
  `ChipAudio.nativeYmExport`, Deck cinterop over `ym/ym_export.h`). Ten minutes of ticks at the region's rate end
  songs that never return to their loop point.
- **Project files**: backend `"ym2612-fm"`, Mega Drive data version 1 (README, "Project format").
- **Golden tests** (`YmGoldenTest`, `tools/audio/ym/ym_golden.py`): the native host test's song (`ym_song.test_song`)
  and a features song (PAL, LFO rate 7, notes C-0 and B-7, FM slot 64 and PSG slot 63 behind empty slots, every
  operator field at its extremes and every SSG-EG shape, AMS 3/FMS 7, algorithm 7, extreme sweeps, a volume loop with a
  release point, negative arpeggio values, a relative pitch envelope, a 256-value envelope, an instrument without
  envelopes and one sharing another's (stored once), noise periodic and white at every rate incl. pitched, `Nxy`
  locks, four effect columns and blank columns before used ones, an instrument without a note, orders of 64, 20 and 1
  rows, `Bxx`/`Dxx` across a parked order) rebuilt from Thor Tracker's model lower to exactly the bytes the reference
  builder writes. `YmModelTest`: channels and effects per channel kind, project round trips, presets, curated controls
  read back what they write and approximate readings, tunings, validation and warning messages, song settings,
  arrangement, audition checks, the export seam, the demo.
- **Demo**: "Neon Causeway" (`YmDemoSong`, A minor with a major dominant, Am-F-G-E and a Dm-G-C-E bridge, speed 6 =
  150 BPM): a square FM lead with vibrato, two portamento glides and a falling slide with a fade; a slap bass with
  brightness-locked octave pops; brass stabs on two channels panned left and right (strings in the bridge); swept FM
  kick, FM snare, toms and a crash; PSG chord arpeggios from arpeggio envelopes (doubled an octave up in the bridge), a
  PSG echo of the lead three rows late, noise hats and pitched-noise toms in the fill. Intro, theme A, bridge, theme
  A, fill; loops to theme A; LFO rate 2. No errors or warnings; `exportSamples` writes `neon-causeway.{thor,ymimg}` and
  `tools/audio/render_ym_image.sh` renders the image and its exports.

### YM2612 editor

- **Shared FM editor** (`system/fm`): the OPL3 instrument painter's pieces moved into `FmInstrumentPainter` — the bank
  column, page tabs, setting rows with the name cursor, the Expert register grid (one column per operator, carriers
  marked ♪), the algorithm diagram (a routing of operator boxes on a grid: `FmRouting.chains` for the OPL3's chains,
  explicit positions with elbow arrows between rows for the OPN2's eight algorithms), the envelope sketch in dB
  (`FmEnvelopeSketch`: attack, decay, held level with an optional fall — the YM2612's sustain rate — release, the
  modulators' tone decay, and SSG-EG shapes drawn from the attack's end to OFF) and the drum sweep's pitch curve — and
  `FmControls` (most-used channel, the sweep's default speed, preset stepping). The OPL3 screens are drawn by the same
  code as before: all 17 Deck renders of its Sound/Expert/Drum/Chords pages, Thor sizes, Pattern and Help were
  pixel-identical before and after. The NES envelope step plot moved into `EnvelopePlot` (the NES and the Mega Drive's
  PSG sounds draw and touch-draw with it; NES renders pixel-identical too).
- **Instrument view** (`YmInstrumentView`, `YmInstrumentPainter`): head rows Slot, Name, Listen on, **Type** (FM voice /
  PSG sound: Edit switches, giving the slot the other kind's starting sound with its name kept, one undo step), Page.
  *FM voices*: *Sound* — the curated controls of `YmCurated` (preset, algorithm, ratios with detune, brightness,
  level, feedback, attack, decay, sustain, sustain rate, release, tone decay, key scaling, vibrato/FMS,
  tremolo/AMS, SSG-EG; "≈" for hand-edited readings; vibrato and tremolo dimmed while the song's LFO is off) beside the
  drawn algorithm and the first carrier's envelope; *Expert* — algorithm, feedback, AMS, FMS as stored, then DT, MUL,
  TL, KS, AR, AM, D1R, D2R, SL, RR and SSG-EG per operator (←→ or L1/R1 between operators; AM switches on tap; SSG-EG
  steps off, 8-15); *Drum* — drum presets and the sweep. *PSG sounds*: *Volume*, *Arpeggio*, *Pitch* — the NES envelope
  editor (length, loop, release on volume, relative/absolute pitch, steps drawn with Edit+↑↓/←→ or by touch, the
  same `NesEnvelopes` edits) — and *Noise·Drum* (white/periodic, rate /512, /1024, /2048 or pitched, dimmed unless Listen
  is Noise; the sweep). Presets list the slot's kind (FM voices and drums, or PSG and noise sounds); a noise preset
  moves Listen to Noise. An empty slot shows the pages of the Listen channel's kind and Edit on Slot (or any setting)
  makes that kind's starting sound (Lead or PSG lead). Listen plays FM voices on FM 1 when Listen is a PSG channel, PSG
  sounds on PSG 1 when it is an FM channel. Every edit is one undo step (`YmEdits`: `setYmInstrument`, `editYmFm`,
  `editYmPsg`, `editYmEnvelope`; copy/paste a sound, or one PSG envelope into another envelope page).
- **Entry sounds per kind**: the editor's entry sound is per bank, so the system declares two banks over the one slot
  numbering — FM voices (FM 1-6) and PSG sounds (PSG 1-3, Noise) — and `SoundSystem.defaultInstrument` gives each
  bank's first entry sound (the first FM slot, the first PSG slot): notes entered on PSG lanes of a new song get a PSG
  sound without choosing one. Choosing a slot in the instrument view selects it in its kind's bank.
- **Shared views**: New and Load demo list the Mega Drive through `SoundSystems`; the Pattern view shows ten lanes
  (FM 1-6, PSG 1-3, Noise, which has its own lane colour) with up to four effect columns each and sideways lane
  scrolling (the OPL3's mechanism, unchanged); Song lists Region and LFO; Keyboard and Sketch play every channel,
  Noise as a non-tonal channel (no scale or chord reference). Help: the effects page and the three instrument pages.
- Tests: `YmEditorTest` (ten channels and columns, notes on FM and PSG lanes getting their kind's sound with two effect
  columns, every FM page, PSG envelopes and noise, empty slots and the type switch, noise presets and copy/paste, Help
  and song settings).

## OPN and OPNA (PC-88/98): YM2203 and YM2608

**Status: source slice on branch `opna-systems`, not built, tested or listened to** (`docs/VERIFICATION.md`).

- *Systems.* Two standalone systems in the family "PC-88/98" (New and Load demo list them together):
  `OpnSystem` = **PC-88/98 OPN (YM2203)** (`"ym2203-opn"`: FM 1-3, SSG 1-3) and `OpnaSystem` = **PC-88/98 OPNA
  (YM2608)** (`"ym2608-opna"`: FM 1-6, SSG 1-3, Kick, Snare, Cymbal, Hi-hat, Tom, Rim, ADPCM), one
  `OpnFamilySystem(chip)` in `system/opn/`. Music-only, like the OPL3: Thor Tracker's own C player (no console driver
  to run) on ymfm's `ym2203` / `ym2608` at the PC-88/98 clocks (3.9936 MHz / 7.9872 MHz; sources in
  `docs/AUDIO_ENGINE.md`, "OPN and OPNA"), exported as VGM; WAV through the generic path.
- *Notes and timing.* Absolute notes C-0..B-7, FamiTracker names (A-4 = 440 Hz); the SSG's lowest is B-0 (its 12-bit
  period). One tick per timer B period, a song setting (TB 183-250, 47.5-578 ticks per second, default 198 = 59.77
  Hz), as PC-98 drivers time their tempo; `ticksPerSecond(song)` follows it. Up to four effect columns per channel.
- *Model* (`OpnSound`, project `sound` with `"chip":"ym2203"|"ym2608"`, backend version 1 for both): 64 sound slots
  of any part — `"type":"fm"` (the Mega Drive's OPN voice fields: algorithm, feedback, `ams`, `fms`, four `operators`
  with `dt mul tl ks ar am d1r d2r sl rr ssgEg`, plus `ch3`: four operator pitch offsets in 1/64 semitones for FM 3's
  special/CSM modes, and the drum `sweep`), `"ssg"` (volume / arpeggio / pitch / mixer / noise envelopes, pitch mode,
  the hardware `envelope` as the EPSM's: shape, autoPitch, octave, period, and `sweep`), `"rhythm"` (level 0-31,
  left/right) and `"adpcm"` (sample index, root note, level 0-255, left/right, loop, `sweep`); the YM2608's `samples`
  (`name`, recorded `rate`, ADPCM-B `data` as one Base64 string); song settings `timerB`, `fm3Mode`
  (`normal`/`special`/`csm`), `timerA` (CSM rate), and on the YM2608 `lfo` (`$22`) and `rhythmLevel` (`$11`). Banks
  are views of the one slot list by part (FM voices, SSG sounds, Rhythm sounds, ADPCM sounds), as the Mega Drive's FM
  and PSG banks; a cell's sound must match its channel's part. A YM2203 song may not use OPNA-only features (LFO and its
  depths, pans, rhythm, ADPCM, samples): the validator names each.
- *Shared hardware, shared in the model.* The SSG's noise generator and envelope generator are one per chip: `Nxx`
  and noise envelopes drive the noise period, the highest-numbered SSG channel sounding a hardware-envelope sound sets
  its period, and each such note restarts it (`$0D`). FM 3's mode and timer A are song settings (`Txx` changes the
  CSM rate while playing); the YM2608's LFO (`Lxx`) and rhythm total level (`Rxx`) likewise.
- *ADPCM-B samples are song data* (onboard hardware, so fully supported): imported from `.wav` (any rate up to 55 466
  Hz, resampled on request), or generated from Thor Tracker's own procedural starter kit (`OpnSampleKit`: Kick,
  Snare, Hat, Bass, Bell, Voice — original synthesis, no recorded audio), then edited on the device (trim, gain,
  normalise, reverse, fades, resample, rename, replace, delete); `OpnAdpcm` encodes with an exact model of ymfm's
  ADPCM-B decoder (edits other than trimming the end decode and re-encode, which adds a little noise each time). The
  lowering places every sample, 32-byte aligned, in the chip's 256 KiB; the VGM carries them as a data block. Notes
  transpose from the sound's root note through Δ-N.
- *Rhythm* plays the user-imported YM2608 rhythm ROM (shared with the EPSM; see
  `docs/AUDIO_ENGINE.md`, "The YM2608 rhythm ROM"): needed only to play a song whose played orders hold a rhythm note
  or to audition a drum; VGM export never needs or contains it.
- *Editor* (`OpnInstrumentView`): a Type row switches a slot between the chip's parts. FM: **Sound** (the Mega
  Drive's curated controls and presets via `asYm()`/`withYm`), **Expert** (every register; AM, AMS, FMS only on the
  YM2608), **Ch3** (FM 3's mode and the four operator pitch offsets in semitones and 1/64 fine; Listen plays FM 3),
  **Drum** (sweep). SSG: **Volume**, **Arpeggio**, **Pitch**, **Mixer**, **Noise**, **Hw env** (shape, auto/manual
  period, octave, period), **Drum**. Rhythm: level, pan and the rhythm ROM row. ADPCM: **Sample** (sample, root,
  level, pan, loop, name, trim, Tools…, Add sample… with memory used/free) and **Drum**. Song view: tick rate (timer
  B), FM 3 mode, CSM rate (timer A); on the YM2608 also LFO and rhythm level.
- *Demos.* "Night Express" (YM2203, D minor: FM 3 special-mode chords, SSG arpeggios, hardware-envelope buzz bass and
  swell, tone/noise drums) and "Paper Lanterns" (YM2608, A major at TB 192: six panned FM voices with the LFO,
  ADPCM-B kick, snare and a looping voice from the starter kit, SSG; no rhythm notes, so it plays without the ROM).
  `OpnDemoSong.shortest(chip)` is the one-row song of the WAV export test.

## Open questions

- Should the system be switchable inside a song later (conversion), or only chosen with New? Proposed: New only for
  now.
- NES instruments: one shared bank for all four channels (FamiStudio's way) rather than per-channel banks. Proposed:
  shared, with the editor greying out what a channel ignores.
- OPL3 GD3 notes field: empty for now; the song's comment could go there if songs get one.
- OPL3 rhythm mode as an option for drums later? Proposed: not unless asked (its pairs share pitches).
- OPL3 empty instrument slots (decided on the user's behalf): a slot is empty until a preset or edit fills it, rather
  than 64 always-defined instruments as on the NES/SID; new songs fill 01-11 with the presets. Review.
- OPL3 `000` cannot be stored, so an arpeggio cannot be switched off on a row without a note (the next note without
  `0xy` plays plain; the player and reference treat an explicit `000` as "arpeggio off"). Proposed: accept, as on the
  Game Boy; a dedicated "arpeggio off" letter would be the alternative.
- YM2612 channel 3 special mode and the DAC/PCM (SGDK's XGM driver mixes up to 4 PCM channels on FM 6): left out
  of this release. PCM drums would need sample banks in the image and the XGM's PCM table. Proposed: a later step.
- YM2612 driver: XGM v1 (`drv_xgm`) chosen; XGM2 (SGDK 2.00's newer driver and format) is not ported. Proposed:
  revisit if games standardise on XGM2.
- YM2612 heavy frames: XGM executes ~70-100 YM writes per frame; heavier frames (song start, the loop frame's full
  dump, many simultaneous instrument changes) lag up to 3 ticks, faithfully. A lighter loop frame (only the registers
  that differ) would shorten the loop lag but breaks byte equality with xgmtool. Proposed: keep xgmtool's frame and
  warn about heavy rows in the validator.
- YM2612 core: ymfm (BSD-3, ~2.4× cheaper per tick); Nuked-OPN2 (LGPL-2.1+, die-accurate) remains an option if
  listening finds ymfm lacking. The Z80's bus wait states while accessing the 68000's ROM window are not modelled
  (the driver reads the stream slightly faster than on hardware).
- OPL3 curated lists (algorithms, 22 ratio sets, 20 waveform combinations per operator count, key scaling, LFO) and
  the 17 presets are a first proposal; the user's own FM simplification ideas may replace them (they only read and
  write operator registers, so songs are unaffected).
- YM2612 curated lists (8 algorithms, 26 ratio sets with detune, 11 SSG-EG presets, key scaling, LFO depths) and the
  24 presets are a first proposal, like the OPL3's; they only read and write operator registers and envelopes.
- YM2612 heavy-row warning: the write estimate (≈30 per FM instrument change, 8 for changed locks, 4 per retrigger)
  against a budget of 90 per frame is a heuristic from the spike's measurement (70-100 writes per frame), not a
  simulation of the driver. Proposed: keep until device listening shows audible lag elsewhere.
- YM2612 XGC export extension: `.xgc` (the audio document and xgmtool use `.bin`). Proposed: `.xgc`, which SGDK's
  `rescomp` accepts as a `BIN` resource under any name.
- YM2612 editor (decided on the user's behalf): one Instrument view for both kinds, with a Type row that swaps a slot
  between FM voice and PSG sound (the old sound is lost except through undo); an empty slot takes the Listen channel's
  kind. Alternative: separate FM and PSG slot ranges. Review.
- YM2612 envelope sketch times are approximations from the OPN2's rate tables at the NTSC clock (attack ≈ 7 % of the
  same rate's full decay, key scaling ignored), like the OPL3's; listening may suggest adjustments.
- NES expansions (decided on the user's behalf): one system variant per expansion chip ("NES + VRC6") grouped under
  "NES…" in New and Load demo, rather than one NES system whose song settings switch the chip (that would change a
  song's channel count in place). Converting a plain NES song to NES + VRC6 is not offered yet (it would add three
  empty tracks; the conversion step of the roadmap could do it). Review.
- VRC6 sounds share the one 64-slot bank with a Chip row per sound, rather than a separate VRC6 bank; a VRC6 sound on
  a 2A03 channel (or the reverse) is an error, as FamiStudio does not allow it. The saw's master volume defaults to
  Half (FamiStudio's default). Review.
- VRC6 mixing level: one DAC step = the 2A03's full pulse ÷ 15 (NESdev's "roughly equal" full pulses); no
  hardware or reference-emulator comparison yet. Listening may ask for a different balance.
- EPSM FamiStudio text and the SSG's manual envelope period (decided on the user's behalf): FamiStudio 4.5.3's text
  export and import have no `EPSMSquareEnvelopePitch` attribute, so a manual period always reads back as 4000. The
  text export refuses an EPSM sound with a manual period other than 4000 (naming the sound) rather than writing a file
  that plays differently in FamiStudio; `.nsf`, WAV and playback keep the period. Alternatives: export anyway with a
  warning, or write the period as `EnvelopePeriod` effects on the notes. Review.
- EPSM LFO as one song setting (decided on the user's behalf): the YMF288 has one LFO register (`$22`) and FamiStudio's
  driver writes each FM instrument's register 30 there at every instrument load, so per-sound LFO values would fight
  (the last sound loaded wins for all six FM channels). Thor Tracker keeps it as a song setting and writes it into
  every exported EPSM instrument; FamiStudio projects whose instruments disagree cannot be represented exactly. Review.
- EPSM SSG noise and hardware envelope are shared by SSG 1-3, as on the 5B: the highest square sounding such a sound
  drives them. Unlike the 5B branch, this slice shows no Pattern lane tags or inspector lines for who drives them.
  Proposed: add them when the 5B and EPSM branches merge (one shared implementation).
- EPSM rhythm drums have fixed pitch (the ROM's samples at the chip's rate): a note only triggers the channel's drum,
  so transposing a rhythm lane changes nothing audible. Proposed: keep FamiStudio's model (any note triggers); a
  dedicated "hit" note is the alternative.
- EPSM rhythm ROM: never bundled (Yamaha's data); users must obtain and import their own copy, checked by SHA-256.
  FamiStudio's own desktop emulator embeds it; Thor Tracker deliberately does not. Songs with rhythm notes do not play
  until it is imported, with a message saying how. Review the message and the import's placement after device testing.
- EPSM mixing level and stereo: the machine outputs true stereo (FM and rhythm pans, 2A03 centred). The YMF288's level
  against the 2A03 follows the evidence in `docs/AUDIO_ENGINE.md`, "EPSM"; no hardware comparison yet. Listening may
  ask for a different balance.
- OPN/OPNA mixing (decided on the user's behalf): the chips' own DAC range with MAME's `pc8801.cpp` weights (YM2203 FM
  and SSG channels equal; YM2608 SSG at half the FM route's weight), a full FM carrier at 0.125 as on the OPL3. Board
  balances differ (PC-88 models, PC-9801-26K, -86); listening may ask for another.
- OPN/OPNA tempo as timer B (decided on the user's behalf): ticks are timer B periods, as PC-98 drivers do, rather
  than a fixed 60 Hz with multispeed; 60 Hz is not exactly reachable (TB 198 = 59.77 Hz). Review.
- OPN/OPNA FM 3 CSM: an FM 3 note sets `$27 = $81` and timer A keys the operators until the note's OFF (no regular
  key-on). Whether every VGM player emulates CSM is not checked. Review after listening.
- OPN/OPNA ADPCM-B memory: 256 KiB (the PC-9801-86 / Sound Board II DRAM as commonly documented; not measured). Samples
  are stored ADPCM-only in projects, so editing re-encodes them (generation loss); storing the source PCM too would
  double the project size. Review.
- OPN/OPNA seeks replay every tick through the whole chip (exact, as OPM's); the cost on the Konkr for a long song is
  not measured.
