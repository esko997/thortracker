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
VRC6 first, then VRC7. Decisions (made on the user's behalf; see Open questions):

- **One system per expansion, one family.** FamiStudio assembles its engine for one expansion (its project picks
  one in practice for NSF/ROM export), and a Thor Tracker system has fixed channels, so each expansion is its own
  `SoundSystem` variant beside the plain NES: `NesSystem` (`"nes-famistudio"`, 5 channels), `NesVrc6System`
  (`"nes-famistudio-vrc6"`, 8 channels) and `NesVrc7System` (`"nes-famistudio-vrc7"`, 11 channels). All are
  `NesFamiStudioSystem`s sharing every rule, view, lowering and export; they differ only in `NesExpansion` (`NONE`,
  `VRC6`, `VRC7`). `SoundSystem.family` (`"NES"`) groups them: **New blank song** and **Load demo** list "NES…" once,
  which opens the variants ("NES (FamiStudio)", "NES + VRC6 (FamiStudio)", "NES + VRC7 (FamiStudio)"). The plain NES
  is unchanged: same id, channels, files, lowering bytes, image and `.nsf`. Each variant's engine refuses another
  variant's song (its image would be laid out for another player build).
- **Model.** `NesSound.expansion` (project files: `"expansion":"vrc6"` / `"vrc7"`, left out when none) names the chip;
  the song's system follows from it (`NesSound.system`), and a project whose backend and expansion disagree is
  rejected. Channels keep FamiStudio's engine order: the 2A03's five (`NesRules.APU_NAMES`), then the expansion's
  (`NesExpansion.channelNames` / `channelKinds`; `NesRules.channelName` and `NesRules.kind` take the expansion). The
  one bank of 64 sounds stays shared; each sound has a `chip` (`NesChip.APU` = 2A03, `VRC6`, `VRC7`; FamiStudio's
  instrument expansion; omitted in files for 2A03), because the same envelope means different things on each chip
  (VRC6 duties 0-7, no duty on the VRC7). A channel plays only its chip's sounds (validation, audition); only 2A03
  sounds map DPCM samples. A sound pasted into a song without its chip becomes a 2A03 sound (`forChip`: duty clamped,
  saw volume and FM patch reset). NES data version stays 2: expansion songs are new files, 2A03 files are
  byte-identical.
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
- **VRC7** (a YM2413 derivative: six 2-operator FM channels, 15 patches in ROM and one programmable patch): channels
  VRC7 FM 1-6 (5-10, `ChannelKind.VOICE`), reaching every note C-1..B-7. A VRC7 sound (`NesChip.VRC7`) has volume,
  arpeggio and pitch envelopes (no duty) and its patch (`Vrc7Patch`: FamiStudio's `Vrc7Patch` 0-15, 0 = custom, and
  `Vrc7PatchRegs`, the 8 registers `$00-$07`; a built-in patch holds its ROM values, as FamiStudio's setter copies
  them, so switching to Custom starts from that sound; `Vrc7Presets` = FamiStudio's table = emu2413's VRC7 ROM set).
  Sounding pitch is channel-aware: `NesRules.engineNote` adds 12 on VRC7, so absolute 57 (A-4) is engine note 58
  (about 440 Hz), not the 2A03's engine note 46 (which would sound 220 Hz on VRC7). Pattern lowering, text export,
  audition and Pxx use this same mapping; notes and slide targets above B-7 are rejected on FM channels.
  Volume is the chip's 4-bit level (the engine writes `patch << 4 | 15 − volume` to `$30+`, so 0 is −45 dB, not
  silent); pitch envelopes, Pxx and slides work on the FM frequency number (FamiStudio's pitch shift 3: × 8,
  + raises). Effects: everything but `Vxx` (no duty) and `Zxx`. `Pxx` keeps one meaning on every channel: the cell's
  detune is the pitch the same Pxx gives a 2A03 pulse on that note, converted to the nearest FM step
  (`NesRules.vrc7FinePitch`; FamiStudio's own fine pitch is raw FM steps), so tunings work on FM within the chip's
  step size. OFF on a sounding FM note is FamiStudio's release (`CanRelease` is always true on VRC7 channels): opcode
  `$56` keys off (the patch's release, or the sustain rate 5) and the volume envelope continues from its release
  point; OFF on a released note, or `Sxx`, is a stop (key off without the release override). Two shared settings
  are the chip's, not a sound's:
  - **One custom patch.** The chip has one set of custom registers for all six channels; loading a custom sound writes
    them. The lowering follows FamiStudio's exporter (`OtherVrc7ChannelUsesCustomPatch`: a channel whose custom sound
    another FM channel's different custom sound may have replaced sends its instrument again with its next note),
    never re-sends a custom sound per order (Thor Tracker's per-order re-send is skipped for it: it would rewrite the
    registers under another channel), with **two intentional export corrections**: loop entries reload a custom patch
    another channel may have replaced (including delayed final writers), and mid-song restoration keeps a no-attack
    note's held envelope instead of forcing a new attack. FamiStudio's editor preserves both behaviors; its 4.5.3
    exporter misses loop restoration and retriggers that mid-song legato case. The independent `vrc7_patch_restore`
    golden demonstrates both differences (`AUDIO_ENGINE.md`). A custom sound starting while another FM channel holds a note of
    a different custom sound (different registers) changes that note's timbre on the chip itself: the song check warns
    where. VRC7 sounds identical but for their names are musically equivalent, so a copied custom sound shares the
    patch without reloading it. Runtime bank identities remain distinct: a held note follows edits to its actual
    slot, not an identical original or copy.
  - **Sustain overrides** (`NesSound.vrc7Sustain`: `release`, `stop`; FamiStudio's `Vrc7OverrideRelease` /
    `Vrc7OverrideStop`). FamiStudio stores them per instrument, but its engine keeps one copy
    (`famistudio_chn_vrc7_sustain`, the last VRC7 instrument loaded on any channel), so per-sound values would apply
    to the wrong notes. Thor Tracker makes them one song setting, written into every VRC7 instrument of the exports, so
    FamiStudio's player, its exporter and our engine agree.
  Editor: the Chip row offers 2A03 / VRC7; a VRC7 sound's fourth page (Duty's place) is **FM patch**: Patch (Custom or
  the 15 built-ins), Release and Stop sustain (the song's setting, shown on every VRC7 sound), then each register
  field per operator (`Vrc7Param`: multiplier, half-sine, modulation level, feedback, AR, DR, SL, RR, sustained, KSL,
  KSR, tremolo, vibrato; ←/→ picks modulator or carrier). Editing a built-in patch's field makes it Custom from that
  patch's registers. Demo: "Lantern Point" (`NesVrc7DemoSong`): a custom-patch lead with vibrato and key-off releases,
  built-in Bass guitar, Brass pads, Piano arpeggio chords and Bell sparkles, a 2A03 echo, triangle, noise drums and
  the DPCM starter kit. `exportSamples` writes `lantern-point.{thor,nsf,txt,nesimg}`.
  Engine: the `FAMISTUDIO_EXP_VRC7 = 1` player build (`NesVrc7PlayerImage`, `nes_vrc7_player_image.h`, song base the
  first page after it) with FamiStudio's VRC7 note tables, its header gaining the expansion instrument pointer (16-byte
  instruments after the 2A03 table, one per slot), on emu2413 1.6.4 in VRC7 mode. Contract: `docs/AUDIO_ENGINE.md`,
  "VRC7". Exports: `.nsf` with header byte `$7B` = 2; FamiStudio text with `Expansions="VRC7"`, `Expansion="VRC7"`,
  `Vrc7Patch`, `Vrc7Reg0-7` (custom only) and both overrides, and the `VRC7FM1-6` channels; `.wav`. Golden songs
  `vrc7_demo` and `vrc7_effects` (VRC7 register writes, `$9010`/`$9030`, compared every frame).
- **Sunsoft 5B** (`NesS5bSystem`, `"nes-famistudio-s5b"`, "NES + Sunsoft 5B (FamiStudio)"; a YM2149F): channels 5B Square
  1-3 (5-7, `ChannelKind.PULSE`, no duty). Notes are true pitch, as everywhere (A-4 = 57 = 440 Hz): FamiStudio's engine
  plays the 5B from the pulses' note table + 1, where the 5B (CPU / 32 period; NESdev, FamiStudio's emu2149 with its
  clock divider) sounds an octave below a pulse, so a 5B square plays FamiStudio's note + 12 (`NesRules.S5B_OCTAVE`;
  FamiStudio's text shows those notes an octave above Thor Tracker's, sounding the same) and reaches C-1..B-7; `Pxx`
  converts to the 5B's periods for the same pitch (`NesRules.s5bFinePitch`). 5B sounds (`NesChip.S5B`) have volume, arpeggio and pitch envelopes, a
  **mixer** envelope (`NesInstrument.mixer`: 0 tone+noise, 1 noise, 2 tone, 3 off; empty = tone, FamiStudio's
  default), a **noise** envelope (0-31) and a hardware envelope (`S5bEnvelope`: shape off or `$08-$0F`, auto period
  with octave -8..8 or a manual period 0-65535). Shared hardware, as FamiStudio's engine 4.5.3 drives it: the highest
  square sounding an envelope sound sets the envelope's shape and period every frame, any attack restarts it, its
  level replaces the volume envelope and `Cxx`; the highest sounding square sets the noise period (Square 1 only with
  noise in its mixer, Squares 2-3 always: the engine's mask table is meant for shifted mixer values; FamiStudio's
  editor preview differs, the exported engine and this app play this). `NesS5b` warns where squares fight over them and
  feeds the Pattern view's shared state (lane tags ENV / NOISE, inspector lines). Effects: `Ixx` / `Jyy` = the
  envelope period's high / low byte (FamiStudio `EnvelopePeriod`), combined when both are on one row (on any squares;
  alone the other byte is 0; two different values of one byte in a row are an error), lowered as `$5E lo hi` on Square
  1 as FamiStudio's exporter moves them; the next note with attack on a square that played since reloads its sound
  (FamiStudio's override rule). Each order's first note re-sends its sound (seek, loop, live edits), except a manual-
  period sound the square already holds: its load would set the shared period, which FamiStudio's export leaves at
  another square's or an `Ixx`/`Jyy`'s value there, so those orders are FamiStudio's stream for any delay, legato or
  loop (`s5b_effects` orders C-E). The seek fallback restores the held instruments through the real driver's
  instrument loader, so these squares no longer start silent. It remains an approximate seek: channel-order
  loads need not recover an earlier shared-period effect or the original generator phases.
  Identical, renamed manual-period instruments use a 5B-only `$5F` load extension: keep the global period, as
  FamiStudio's merged export does, but retain the actual slot for live edits. The Pattern inspector follows
  the same equivalence and override rules rather than assuming every instrument number rewrites the period.
  Lowering: the 16-byte
  `instruments_exp` entries (vol, arp, pitch, mixer, noise words, shape, octave or `$80`, period, 0, 0) for every slot;
  mixer/noise envelopes at directory `256 + 2 slot (+1)`, kinds 5/6, mixer values as FamiStudio's processed bits
  (`ProcessMixerEnvelope`); effect envelopes from 384 (`NesPlayer.effectEnvelopes`). Editor: pages volume, arpeggio,
  pitch, mixer, noise and **5B envelope** (shape drawing, auto/manual, octave in musical terms, period as rate/ramp time).
  Exports: `.nsf` with `$7B` bit 5; FamiStudio text `Expansions="S5B"`, `S5BEnvelopeShape/AutoPitch/AutoPitchOctave/
  Pitch`, `MixerSettings`/`NoiseFreq` envelopes, `S5BSquare1-3`, `EnvelopePeriod`. Demo "Copper Kite"
  (`NesS5bDemoSong`); golden songs `s5b_demo`, `s5b_effects`. Native: `nes/nes_s5b.c` (NESdev + emu2149 behaviour;
  mix level per FamiStudio's 5B/VRC6 ratio; the 5B amplifier's nonlinearity is not modelled), see AUDIO_ENGINE.md.

**N163** (Namco 163: up to eight 4-bit wavetable channels; sources: FamiStudio 4.5.3 `famistudio_ca65.s`,
`Instrument.cs`, `Envelope.cs`, `FamitoneMusicFile.cs`, `FamistudioTextFile.cs`, `ChannelStateN163.cs`, `NesApu.cs`
at tag 4.5.3 / commit c09dca6, and the NESdev wiki "Namco 163 audio"). Decisions:

- **One variant per channel count.** The chip's channel count is a register, but it sets each channel's update rate
  (one channel every 15 CPU cycles in turn, so f = CPU × freq / (15 × 65536 × L × n)), FamiStudio's note table and pitch
  shift (`FAMISTUDIO_N163_PITCH_SHIFT`: 2, 3, 4, 4, 5, 5, 5, 5) and the RAM left for waves (128 bytes minus 8 per
  channel), and FamiStudio assembles its engine for one count (`FAMISTUDIO_EXP_N163_CHN_CNT`). So every legal count 1-8
  is its own system, `NesN163System.of(n)`: id and engine `"nes-famistudio-n163-<n>"`, "NES + N163, n channels
  (FamiStudio)", `NesExpansion.N163_<n>` (files: `"expansion":"n163-<n>"`), channels N163 1..n (`ChannelKind.WAVE`) after
  DPCM. `SoundSystem.familyGroup` ("NES + N163") nests the eight under the NES family in New / Load demo.
- **Sounds** (`NesChip.N163`): volume, arpeggio and pitch envelopes (no duty) plus `N163Wave` — FamiStudio's model:
  `size` (multiple of 4, ≥ 4), `position` (even 4-bit sample address; FamiStudio uploads whole bytes), 1..min(64,
  1024 / size) `waves` of `size` samples 0-15, `repeat` frames per wave 1-15 (FamiStudio's `WaveformRepeat`), a `loop`
  and a `release` wave (release needs a loop before it). FamiStudio's presets are ported with its float arithmetic
  (`N163Presets`). The validator requires position + size ≤ 256 − 16 n (waves stay below the channel registers, as
  FamiStudio's `N163MaxWaveSize` does) and refuses notes whose note-table entry saturates at $FFFF (4 channels from
  A#8, 8 channels from A#7). It warns where two channels sound at once with overlapping RAM ranges and different
  waves (each upload overwrites the other: FamiStudio's documented "user's responsibility"; identical single waves at
  one place share safely), about wave sizes that are not powers of two (FamiStudio tunes its tables for 16 samples:
  12 log2(16 / size) semitones away, off-key) and about all-zero waves. Explicit positions only: FamiStudio's
  auto-position is an export-time heuristic its text import does not even read; the Wave page shows every sound's range.
- **Effects**: all of FamiStudio's for N163 channels — arpeggio, vibrato, volume and slides, speed, delay, cut, fine
  pitch (`Pxx` converted to the nearest N163 frequency step for the same pitch, `NesRules.n163FinePitch`, as on the
  VRC7), glides (N163 note table, delta >> pitch shift), and `X00`, the phase reset (opcode `$5B`, N163 only; the
  plain NES's effect list is unchanged). No duty. OFF releases (opcode `$5A`: the volume and the wave-index envelopes
  jump to their releases) when the sound's volume or wave sequence has a release point (FamiStudio's `CanRelease`), else
  stops. A note without instrument after another sound attacks anyway (`CanDisableAttack` is false on N163 channels).
- **Lowering**: the expansion instrument table (16 bytes per slot, every slot): volume, arpeggio, pitch envelopes, the
  wave-index envelope (`CreateWaveIndexEnvelope` + `ProcessEnvelope`, directory 256 + slot − 1, kind 4), position, size,
  a wave table of pointers to the slot's distinct waves (deduplicated as FamiStudio does; identical waves and tables
  stored once), 4 zero bytes; effect envelopes from 320. Contract: `docs/AUDIO_ENGINE.md`, "N163".
- **Engine**: eight builds (`NesN163PlayerImage1..8`, `nes_n163_<n>_player_image.h`), `nes/nes_n163.c` from NESdev:
  128-byte RAM with the auto-incrementing address port, channels updated in turn every 15 cycles, the DAC output
  time-multiplexed as on the chip (so 7-8 channels whine at 17 / 15 kHz, as on hardware), 12 dB above an APU pulse
  (NESdev's mixing table: 11-19.5 dB depending on the board). Mute silences a channel's slots without touching the chip;
  a silent seek advances phases exactly; a live edit re-uploads a playing sound's wave only when its waves changed.
- **Exports**: `.nsf` with header `$7B` bit 4 ($10); FamiStudio text with `Expansions="N163" NumN163Channels`,
  `Expansion="N163" N163WavePreset="Custom" N163WaveSize N163WavePos N163WaveCount`, `N163Wave` and `Repeat` envelopes,
  `PhaseReset="1"` and channels `N163Wave1..n`. Golden songs `n163_demo` (8 channels), `n163_demo_1` and `n163_effects`
  (4 channels); FamiStudio's export of our text and our lowering must write the same APU and `$4800`/`$F800` sequence.
- **Editor and demo**: the Instrument view's Wave page (draw samples, size, position, waves, repeats, loop and release,
  presets, the song's RAM map); demo "Wavetable Garden" (`NesN163DemoSong.build(n)`), whose parts grow with the count.

**FDS** (the Famicom Disk System's 2C33: one wavetable channel with a modulation unit; sources: FamiStudio 4.5.3
`famistudio_ca65.s` (FDS update 2276-2402, attack 4595-4626, instrument load and wave upload 5235-5435, opcodes
`$57`/`$58`/`$59`), `FamitoneMusicFile.cs` (`OutputInstruments`, `GetSongData`'s FDS override flags), `Instrument.cs`,
`Envelope.cs` (`BuildFdsModulationTable`), `Channel.cs`, `FamistudioTextFile.cs`, `NesApu.cs`, `NsfFile.cs`,
`RegisterInterpreter.cs` at tag 4.5.3 / commit c09dca6, the NESdev wiki "FDS audio" and Disch's FDS.txt). Decisions:

- **One variant**, `NesFdsSystem`: id and engine `"nes-famistudio-fds"`, "NES + FDS (FamiStudio)", `NesExpansion.FDS`
  (files: `"expansion":"fds"`), channel `FDS` (`ChannelKind.WAVE`, channel 5) after DPCM. The engine is `nes_player.s`
  with `FAMISTUDIO_EXP_FDS` and `FAMISTUDIO_USE_FDS_AUTOMOD` (as FamiStudio's own NSF kernel).
- **True pitch**: FamiStudio's FDS note table is f × 2^20 / CPU, while the chip plays its 64-step wave CPU × frequency /
  2^22 times a second (NESdev; FamiStudio's register viewer agrees), so FamiStudio's note n sounds two octaves below the
  2A03's note n with a one-cycle wave. Thor Tracker's notes are true pitch: the FDS channel plays FamiStudio's engine note
  24 higher (`NesRules.FDS_TRANSPOSE`, `engineNoteShift`, `engineNote`; A-4 = engine note 70, frequency 1031, 439.9 Hz).
  One cycle per wave sounds as named, 2 cycles an octave up, 4 two octaves up. The range is C-1..G#6: from engine note
  94 (4125) on, FamiStudio's frequency passes the chip's 12 bits (`$4083` keeps bits 8-11), so higher notes, slides
  and `0xy` targets are refused. The `.txt` export writes FamiStudio's own (engine) note numbers, so FamiStudio plays
  the same bytes and names them two octaves higher.
- **Sounds** (`NesChip.FDS`, `FdsSound`): volume, arpeggio and pitch envelopes (no duty), 1-16 waves of 64 samples
  0-63 with a repeat of 1-15 frames each, a loop and a release wave (as the N163's), master volume (100/66/50/40%), 32
  modulation targets −64..63 encoded with FamiStudio's greedy `BuildFdsModulationTable` (entry 0 resets; `FdsSound.modTable`),
  mod speed 0-4095, depth 0-63, delay 0-255 frames, and auto-mod (the engine sets the mod speed every frame to the
  frequency × numerator / denominator, 1-32 each, written reduced). Presets are FamiStudio's (`FdsPresets`).
- **Driver behavior**: a scoped patch disambiguates manual speeds whose low byte is `$80-$FF` from auto modulation,
  preserving the complete manual range 0-4095. Such manual words use a high-byte tag (`hi | $80`); other manual
  words and automatic fractions keep their original bytes. FamiStudio text retains the exact speed; its editor
  preview is correct, but its own 4.5.3 assembly/NSF exporter has the ambiguity. Auto-mod multiplies the frequency
  by the numerator in 16 bits (`famistudio_mul`), so the validator warns when the product reaches 65536. A mod
  delay d keeps modulation reset d − 1 frames. Warnings also cover constant waves, zero depth, a flat mod table
  or zero speed, and I/J while an auto-mod sound plays.
- **Effects**: all of FamiStudio's for the FDS channel — arpeggio, vibrato, volume and slides, speed, delay, cut, fine
  pitch (`Pxx` converted to the nearest FDS frequency step at the engine note for the pitch the same Pxx gives a pulse,
  `NesRules.fdsFinePitch`), glides (FDS table, one fraction bit), `X00` (FamiStudio's generic phase reset `$53`) and
  FamiTracker's FDS letters: `Hxx` mod depth 00-3F (`FdsModDepth`, `$58`), `Ixx` the mod speed's high digit 00-0F and
  `Jxx` its low byte (`FdsModSpeed`, `$57` with the whole 12-bit word: the other half is the speed the channel has at that
  row along the played order — the sound's own after an attack that loads it, else the last I/J). These effect codes
  are looked up per expansion (`NesRules.effect(expansion, code)`); S5B has its own I/J meanings. FamiStudio's
  re-bind rule is kept: an attacked note without H (or I/J) after one with it sends its instrument again, restoring the
  sound's depth and speed. OFF releases (`$59`: volume and wave-index envelopes) when the volume envelope or the wave
  sequence has a release point, else stops; legato across sounds needs identical envelopes, waves and mod table included
  (`CanDisableAttack`).
- **Lowering**: the expansion table (16 bytes per slot, every slot): volume, arpeggio, pitch envelopes, the wave-index
  envelope (directory 256 + slot − 1, kind 4, the N163 code path), `(depth << 2) | master volume`, a wave table of
  pointers to the slot's distinct 64-byte waves, the 32-byte mod table, the tagged manual speed or auto-mod fraction,
  the delay; waves, tables and mod tables stored once; effect envelopes from 320. A legato instrument re-send or switch
  between identical copies uses the scoped `$5F i` load: select the actual bank slot and envelope pointers, retaining
  runtime modulation. It works whether the loop arrives with the intro's overrides or different end-of-song values,
  without losing the live-edit target. Contract: `docs/AUDIO_ENGINE.md`, "FDS".
- **Exports**: `.nsf` with header `$7B` bit 2 ($04); FamiStudio text with `Expansions="FDS"`, `Expansion="FDS"
  FdsWaveCount FdsWavePreset FdsModPreset [FdsMasterVolume FdsModSpeed FdsModDepth FdsModDelay FdsAutoMod
  FdsAutoModDenom FdsAutoModNumer]`, `FDSWave`, `FDSMod` and `Repeat` envelopes, channel `FDS` and note attributes
  `FdsModSpeed`, `FdsModDepth`, `PhaseReset`. Golden songs cover the demo, effects and manual/auto-mod copy loops;
  FamiStudio's export of our text and our lowering write the same `$4040-$408A` sequences. A separate upstream-safe
  `$57` witness checks manual speeds whose low bytes are `$80-$FF`.
- **Editor and demo**: on FDS songs the Instrument view's Chip row offers 2A03 / FDS; FDS sounds have a **Waves** page
  (draw 64 samples 0-63, 1-16 waves with duplicate/delete, repeat, loop, release, FamiStudio's presets incl. the PWM
  sweeps, master volume) and a **Modulation** page (the 32 targets drawn as bars with the counter the chip reaches drawn
  over them, mod presets, speed stepping ±1/±16 through 0-4095 with its rate in Hz, depth,
  delay, auto-mod on/off with numerator/denominator and the first note whose 16-bit product overflows); edits through
  `FdsEdits`, waves copy/paste between N163 and FDS sounds rescaled. Demo "Side B" (`NesFdsDemoSong`): an FM bass
  (auto-mod, an H sweep), a vibrato lead (mod delay, J and I speed sweeps, OFF), a PWM morph pad with a release, an FM
  bell (auto-mod 7/2, X00), the 2A03 and the DPCM kit alongside; once through 25.6 s, loop 19.2 s.

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
    envelope `s5b` (the shared `S5bEnvelope`: shape, auto period with octave −8..8 or a manual period; switching a
    sound to EPSM selects period 4000); the rhythm channels use only the volume envelope's **first** value
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
    `nes_epsm_player_image.h`, native NSF flag `NES_EXPANSION_EPSM` = `$80`) and the YMF288 emulated by
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

- **MMC5** (Nintendo's MMC5 mapper: two more pulses and an 8-bit PCM DAC): channels MMC5 Pulse 1 (5) and MMC5
  Pulse 2 (6), `ChannelKind.PULSE`. FamiStudio 4.5.3 has no MMC5 instrument type: MMC5 squares play 2A03 instruments
  (its `Channel.SupportsInstrument`), so `NesExpansion.MMC5.chip` is `NesChip.APU`, the song holds only 2A03 sounds
  and the Instrument view has no Chip row. Duties 0-3 (`Vxx` 00-03), every 2A03-pulse effect, notes A-1..B-8 (the
  same 11-bit periods and note table as the 2A03 pulses: absolute 57 is engine note 46 and 440 Hz on every pulse).
  Differences the musician hears, both from the hardware and the pinned driver: no sweep unit; the driver writes the
  period-high register only when it changes, without the 2A03 pulses' smooth-vibrato trick, so a pitch crossing a
  period-high boundary restarts the waveform (FamiStudio's documentation: "the reset bug cannot be worked
  around"); periods below 8 are not silenced. **PCM is Thor Tracker's software lane**: the pinned engine's header says
  "Nintendo MMC5 (2 extra squares, extra DPCM not supported)" (`famistudio_ca65.s` line 107), its MMC5 init writes
  `$5010 = $00` and `$5015 = $03` only and nothing writes `$5011`; FamiStudio 4.5.3 never activates `Mmc5Dpcm`. So the
  engine stays seven channels and MMC5 songs get an eighth lane, **MMC5 PCM**, played by a software player on the real
  DAC (AUDIO_ENGINE.md, "MMC5 PCM lane"): 2A03 sounds map keys to PCM samples on their PCM page (sample, pitch
  −48..+24 semitones, loop, chromatic spread; beside, not instead of, their DPCM mappings); `.wav` import to unsigned
  8-bit mono (Dpcm's reader; rates to 48 kHz kept, higher converted and disclosed; minima raised to 1 for the DAC's
  `$00` rule; nothing cut: a file that does not fit 256 KiB / 64 samples is refused); `Cxx`, `Gxx`, `Sxx`, OFF and
  `Fxx` on the lane, other effects refused; playback, audition, live edits, seeks and WAV export. `.nsf` and FamiStudio
  text refuse songs whose PCM lane plays. Demo: "Tandem Skies"
  (`NesMmc5DemoSong`): the MMC5 pulses as a lead/harmony pair over the 2A03's echo, triangle bass, noise drums and
  the DPCM starter kit (its PCM lane is silent, so its `.nsf`/text stay complete); `exportSamples` writes
  `tandem-skies.{thor,nsf,txt,nesimg}`, the shortest valid MMC5 song `mmc5-shortest.nesimg`, and the PCM fixtures
  `sample-courier.{thor,nesimg}` (`NesMmc5DemoSong.pcmDemo`: synthesized kit `NesPcmKit`) and `mmc5-pcm-shortest.nesimg`
  for the WAV test. Engine: a third player build (`FAMISTUDIO_EXP_MMC5 = 1`,
  `NesMmc5PlayerImage`, `nes_mmc5_player_image.h`) whose song base is measured by `prepare_nes_player.py` (the first
  page after the player), the MMC5 audio unit `nes/nes_mmc5.c`; exports `.nsf` with `$7B` = `$08` and FamiStudio text
  with `Expansions="MMC5"` and the `MMC5Square1/MMC5Square2` channels (instruments without an `Expansion`). Golden
  test songs `mmc5_demo` and `mmc5_effects`. Contract: `docs/AUDIO_ENGINE.md`, "MMC5".

**Adding another expansion** follows the same path:
1. `prepare_nes_player.py`: assemble `nes_player.s` with `-D FAMISTUDIO_EXP_<CHIP>=1` (and its note tables), song
   base = the page after it; generate `nes_<chip>_player_image.h`, `Nes<Chip>PlayerImage.kt` and fixtures from a
   FamiStudio-exported test text; check the existing builds' outputs stay byte-identical.
2. Native: the chip's audio unit (`nes/nes_<chip>.c`, from NESdev), its registers in `bus_write`, a
   `NesPlayer` entry in `nes_players.c` (`NES_EXPANSION_<CHIP>`, channels, duty range, symbols), its opcode and
   duty rules in `nm_walk_entry`, an ops table in `nes_chip.c` registered in `chip_engine.c`, a host test.
   `NM_MAX_*` grow to the largest build.
3. Kotlin: a `NesExpansion` entry (chip, channel names and kinds), a `NesChip` (label, duty range, its instrument
   fields), a `NesPlayer` entry, its effects/opcodes in `NesRules` and `FamiStudioLowering`, its text attributes in
   `FamiStudioText`, a `Nes<Chip>System` object (id, name, demo) in `SoundSystems.ALL` (the "NES" family shows it
   under New/Load demo), Instrument view rows or pages for chip-specific fields, help, golden songs.
4. Docs: a section here and in `docs/AUDIO_ENGINE.md`.
Chips with instruments unlike envelopes need their own instrument fields and editor pages (the VRC7's patch page; N163
waves, FDS waves and modulation next); chip state the engine keeps once (the VRC7's sustain overrides) is a song
setting. Songs combining several expansions would need FamiStudio's multi-expansion engine build and a system per
combination, so they are left out unless asked.

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

### YM2612 (Mega Drive): XGM2, PCM and FM3

The engine keeps a real Mega Drive driver in the loop: Thor Tracker's row player
→ separate packed FM/PSG streams → SGDK's unmodified XGM2 driver on `z80.h`
→ ymfm YM2612 and the SN76489. SGDK commit `9a78a441` is MIT; its assembler
is an offline tool. The assembled 8192-byte driver is checked against SGDK's
`drv_xgm2`, not replaced by a host-side approximation.

XGM2 replaces XGM1 completely. Its three PCM channels mix signed 8-bit samples
into the DAC that replaces FM 6's output; there is no extra software mixer
outside the driver. The SN76489 remains the Sega VDP variant with white-noise
taps `$0009`. ymfm (`81aec25c`, BSD-3-Clause) supplies FM synthesis, timer
and busy behavior. Runtime/layout details are in `AUDIO_ENGINE.md`, "YM2612".

Decisions:

- Preserve the original ten FM/PSG lanes and their 57536-byte data capacity.
  Append PCM 1-3 rather than repurposing existing lanes.
- Keep one shared bank of 64 typed instruments: FM voice, PSG sound or PCM kit.
  Notes select a kit key; the instrument column selects the kit. This permits
  pitched drums and chromatic sampled sounds without encoding drum type as pitch.
- PCM sources: at most 64, sharing 256 KiB; signed 8-bit at 1..48000 Hz.
  WAV import reports stereo downmix and conversion above 48 kHz. Per-key pitch
  is −48..24 semitones, kit level 0..15. Lowering compiles every mapped key,
  including keys used only for audition, into at most 123 distinct variants and
  a 1 MiB bank. Identical waveforms share storage. No truncation or silent fallback.
- PCM plays one-shots. No runtime PCM pitch/volume effects or sample loops;
  level and pitch are compiled. OFF/`Sxx` stop a sample, `Gxx` delays it, and
  global `B`/`D`/`F`/`L` remain available.
- FM3 special mode uses four instrument pitch offsets, ±4 octaves in
  1/64-semitone units, relative to the channel's note/effects. Other FM channels
  ignore them. This is one four-operator voice, not four extra tracker lanes.
  No Timer-A CSM: XGM2 needs that timer for PCM.
- FM 6 and PCM share the DAC/output path and stereo routing. PCM mute stops
  that driver's voice; unmute admits future notes rather than restarting an
  old sample. Muting FM 6 does not mute the shared DAC.
- No chord effect: FM voices are still scarce, so chords use separate lanes.
  PSG envelopes and its noise/tone-3 coupling are unchanged.
- NTSC/PAL select real console clocks and frame timing. Notes are absolute
  C-0..B-7, A-4 = 57; tonal tuning is lowered to `Pxx`, never onto PCM lanes.
- Export VGM from actual driver writes (DAC included), WAV from playback,
  and unpacked/packed XGM2. `.xgm`/`.xgc` now require XGM2-capable consumers;
  old XGM1 files/drivers are not a compatibility target.

### YM2612 model, lowering and exports

`YmSystem` retains id `"ym2612-fm"` with thirteen channels, four effect columns
and at most 256 orders. The banks are views over the same slot numbering:
FM voices on 0-5, PSG sounds on 6-9, PCM kits on 10-12.
Song settings are Region, LFO and FM 3 mode.

`YmSound` contains the slots, `pal`, `lfo`, `fm3Mode` and `pcmSamples`:

- `YmFmInstrument`: algorithm, feedback, AMS/FMS, four operator register sets
  (`dt mul tl ks ar am d1r d2r sl rr ssgEg`), drum sweep, and four `ch3` offsets.
- `YmPsgInstrument`: volume/arpeggio/pitch envelopes, relative-pitch flag,
  white/periodic noise source and drum sweep.
- `YmPcmInstrument`: name, fixed level and note-to-sample/pitch mappings.

Backend data version 2 migrates version 1 by adding three empty tracks to every
pattern, including parked/unplayed patterns. Orders, tuning, notes and effects
are retained. Other backends' formats are unchanged.

`YmLowering` first compiles the PCM bank, refusing overflow before changing the
output image. Tonal tracks/envelopes keep their original prefix and capacity.
The appended area holds FM3 offsets, PCM order/key/variant tables, PCM patterns
and bank. Pattern/envelope and waveform deduplication are deterministic.
Arrangement still removes parked orders and renumbers every `Bxx` column.
`YmValidator` and the native loader enforce channel kinds, effect support,
sample references and bounds; an unmapped PCM key is reported.

The curated FM controls still operate on the chip registers rather than a
second patch representation. Algorithms, ratio/detune groups, brightness,
carrier level, envelopes, SSG-EG, key scaling and LFO depths retain the OPL3
editor's "≈" convention for hand-edited values. Presets and the musical content
of **Neon Causeway** are unchanged. **Night Ferry** is a separate PCM/FM3
verification project exported by `exportSamples`.

`YmExport.renderer` is registered by Android JNI and Deck cinterop over the
same `ym_export` entry point. Menus use explicit **XGM2 (.xgm)** and
**packed XGM2 (.xgc)** labels; VGM and unpacked XGM2 carry GD3, packed XGM2 does
not. The bounded export duration is ten minutes when no declared loop is reached.
The shared WAV renderer runs the same chip engine.

Behavior tests cover version migration (including parked patterns), tonal
capacity, PCM compilation limits and deduplication, mapping, import conversion,
FM3 lowering, editor interactions and clipboard identity. Golden fixtures
compare Kotlin lowering to the independent Python builder.

### YM2612 editor

The shared FM painter supplies routing diagrams, envelopes, operator grids and
pitch sweeps; PSG continues to reuse the NES envelope editor.

- **Type** selects FM voice, PSG sound or PCM kit in the same slot, retaining
  the name and making one undoable edit. Entry instruments are remembered per
  bank, so a PSG or PCM lane does not inherit an incompatible FM slot.
- FM pages: **Sound**, **Expert**, **Drum**, **Ch3**. The Ch3 page exposes the
  song-wide mode and each operator's coarse/fine offset, marks offsets inactive
  in normal mode, and auditions on FM 3.
- PSG pages: **Volume**, **Arpeggio**, **Pitch**, **Noise·Drum**; noise controls
  are meaningful on the Noise lane and pitched noise owns PSG 3's period.
- PCM controls: kit **Level**, **Key**, **Sample**, **Pitch**, **Spread** and
  the song's sample list with **Name** and **Add**. Add imports a WAV and maps
  it at the selected or next free key; deleting a sample removes its bindings
  in every kit and is undoable. Spread fills free nearby keys chromatically.
- Pattern entry starts on a mapped PCM key; held horizontal editing moves
  between mapped keys. Keyboard and inspector show the mapped sample name and
  rate/pitch detail. One-shot rails use compiled duration and local row speeds;
  as on NES, they are a local pattern estimate, not arrangement simulation.
- Copying a PCM kit snapshots its referenced immutable samples, not just their
  indices. Paste reuses matching destination samples by bytes/rate, appends
  missing ones within the shared limits, and remaps keys atomically. Refusal
  leaves the document unchanged; one undo reverses the whole paste.
- Pattern shows all thirteen lanes with sideways scrolling. Song exposes the
  three settings above. PCM and Noise are not scale/chord-reference lanes.
  Help derives the channel-specific effects and mapped-sample controls.

## AY-3-8910 / YM2149 (ZX Spectrum 128, MSX, Atari ST, Amstrad CPC)

Roadmap step 7, released in 0.25.0 with native/reference, Android and SDL verification recorded in `VERIFICATION.md`. The
engine contract (clocks and their sources, chip core, image, player, shared noise/envelope arbitration, effects, seek,
VGM) is `docs/AUDIO_ENGINE.md`, "AY-3-8910 / YM2149".

Decisions (made on the user's behalf; see "Open questions"):

- **Four variants, one per stock computer**, grouped as the family "AY-3-8910 / YM2149" in New and Load demo, as the
  NES expansions are: `AyZx128System` (`"ay-zx128"`, "ZX Spectrum 128 (AY-3-8912)", 1773450 Hz, 50.02 Hz frames),
  `AyMsxSystem` (`"ay-msx"`, "MSX (AY-3-8910)", 1789772.5 Hz, 59.92 Hz), `AyAtariStSystem` (`"ay-atari-st"`,
  "Atari ST (YM2149F)", 2005311.75 Hz, 50.05 Hz) and `AyCpcSystem` (`"ay-cpc"`, "Amstrad CPC (AY-3-8912)", 1000000 Hz,
  50.08 Hz). The chip type decides the amplitude behaviour (AY: 16 levels and a 16-step envelope; YM: 32-level DAC and
  a 32-step envelope, fixed volumes at the odd levels); the clock decides the period tables, so every note is true
  pitch on every machine. Each machine's stock wiring: the ZX 128, MSX and ST mix to mono (no panning); the CPC's
  stock stereo socket carries A left, C right and B on both sides, which its engine outputs (the internal speaker's
  mono is not modelled). No stereo modifications (ABC/ACB boards) on the mono machines.
- **Own player and own chip core**, as the OPL3: none of these machines has one standard music driver to put in the
  loop, and none of them is emulated (no Z80/68000). Our core (`ay/ay_psg.c`) is written from the GI and Yamaha
  manuals with MAME's `ay8910.cpp` and ayumi read for details, ayumi's measured DAC tables (MIT) and blip_buf output;
  it can advance its exact state without synthesis, so play-from-position clocks the chip through the whole way from
  the song start (no pre-roll approximation). ayumi itself is vendored for the host test's independent comparison.
- **Exports**: VGM 1.71 (AY8910 family, with the variant's chip type and clock) and WAV. No `.ay`/`.sndh`/`.psg` or
  executable formats (they would need a machine-specific player we do not run).
- **Channels** `Channel A`-`Channel C` (`ChannelKind.PULSE`, tonal, bank 0), four effect columns each, one bank of 64
  sounds ("Sounds"), notes from the variant's lowest reachable note (ZX/MSX A-0, ST B-0: tone periods stop at 4095; CPC C-0)
  to B-7, FamiTracker-style names (A-4 = 57 = 440 Hz), at most 256 orders, OFF = key off (the volume envelope's release
  or silence).

### AY model, lowering and exports (`core`)

- **Model** (`system/ay`): `AyVariant` (enum, serialized `"zx128"`, `"msx"`, `"atari-st"`, `"cpc"`; image byte, chip name,
  YM flag, its system, engine id, clock and tick rate from `AyPlayerImage`). `AySound(variant, instruments: 64 ×
  AyInstrument?)` (null = empty slot; a cell naming one is an error), `Song.ay`; a project's sound variant must match
  its backend (structural error otherwise, as the NES expansions). `AyInstrument(name, volume, arpeggio, pitch,
  pitchRelative, mixer, noise, envelope, sweep)`: five FamiStudio-style `NesEnvelope`s (volume 0..15 with loop and
  release, arpeggio −64..63 semitones, pitch −64..63 sixteenths, mixer 0..3 = tone bit 0 / noise bit 1, noise period
  0..31 — empty: 15 / 0 / 0 / tone only / no noise claim); `AyEnvelopeSettings(shape 0|8..15, auto, octave −8..7,
  period 0..65535, sync)` — both the auto octave and the manual period are kept while the other mode is chosen
  (lowering writes only the active one); `AySweep(start, end, speed)` as the Mega Drive's. Project files: backend =
  the variant's id, AY data version 1.
- **Shared resources, honestly**: the noise period and the hardware envelope are one per chip. Cells and instruments
  only *ask* for them; the player gives each to the most recent key-on among the channels using it (AUDIO_ENGINE,
  "Shared noise and envelope"). `AySystem.sharedState` simulates straight play row by row with the same rule and
  tags the Pattern lanes (`ENV` owner, `env` playing another channel's envelope, `NZ` owner, `nz` using another's
  noise period) and lists the owners with their shape/period in the inspector. Warnings name rows where a key-on
  takes the envelope or noise from a channel still using it with different settings ("Channel B's note at 02:14 takes
  the envelope (shape C, auto J00) from Channel A's held note (shape A, manual 0400); A plays B's envelope until B's
  note ends").
- **Lowering** (`backend/AyLowering.kt`): the arrangement (parked orders removed; loop order and every `Bxx`
  renumbered) into the image, track encoding, sharing and envelope placement exactly as the Mega Drive's
  (`YmLowering`, five envelopes per sound in slot order: volume, arpeggio, pitch, mixer, noise); byte-identical to the
  reference builder `tools/audio/ay/ay_song.py` (golden test). Tunings: `P80+n` as the Mega Drive.
- **Validation** (`AyValidator`): structural (shapes, ranges, 64 slots, variant, every instrument field and envelope)
  and every `ay_load_image` reason (`AyPlayerImage.LOADER_PROBLEMS`) as messages naming pattern, channel and row;
  warnings: a first note of an order without instrument, notes below the variant's lowest note, `Gxx` at or beyond the
  speed, note settings on rows where nothing sounds, envelope/noise conflicts (above), `Jxx`/`Hxx`/`Ixx` on a note
  whose sound has no hardware envelope shape and no `Exx`.
- **Export** (`AyVgm`): `.vgm` through `AyVgm.renderer` (Android JNI `ChipAudio.nativeAyVgm`, Deck `DeckAyVgm`),
  ten minutes of ticks at the variant's rate at most.
- **Presets** (`AyPresets`, new songs fill slots 01..): Square lead (volume decay to a held level, delayed vibrato in
  the pitch envelope), Pluck, Soft pad (slow volume swell), Arp major / Arp minor (arpeggio envelopes, the classic
  one-channel chords), Buzzer bass (shape 8 saw, auto J00, tone off), Sync buzzer (shape 10 triangle with tone, sync),
  Bell (shape 9 decay at a manual period, tone on), Envelope tremolo (shape 14, manual slow period, tone on), Kick
  (tone + noise for two ticks, pitch sweep down), Snare (noise period envelope with a tone body), Closed hat / Open
  hat (noise 1-2, short/long volume), Tom (sweep), Noise sweep (riser: noise period envelope 31 → 1).
- **Demo** (`AyDemoSong.build(variant)`): one original composition for all four variants ("Copper Lane"), tempo
  set per variant's tick rate so it has the same BPM: buzzer bass that hands the envelope to a synced lead in the
  bridge (the shared envelope audible on purpose), arpeggio-envelope chords, noise drums sharing the noise generator
  with a riser, vibrato and portamento, volume slides; no errors or warnings except the intentional, documented
  shared-resource handovers (none on its default rows).

### AY editor

- **Instrument view** (`AyInstrumentView`, `AyInstrumentPainter`, `AyEdits`), controller-first, head rows Slot, Name,
  Listen on, Page. *Sound* (curated): preset, gates (Tone / Noise / Tone + noise / Neither: a one-value mixer
  envelope; "≈" when the mixer envelope varies), noise period (none = shared / 0-31: a one-value noise envelope;
  "≈" when it varies), hardware envelope (Off or one of the eight shapes, drawn), period (Auto with octave / Manual
  with value), sync tone, the drum sweep, with a reading of what the Listen note writes (TP, EP, the envelope's
  frequency) and a line saying the noise and envelope are shared. *Volume*, *Arpeggio*, *Pitch*, *Mixer*, *Noise*:
  the NES envelope editor (`EnvelopePlot`, `NesEnvelopes` edits) with each kind's range. *Envelope* (expert): the
  raw R13 shape (all 16 values, the 8 aliases marked), auto/manual, octave, the 16-bit period (fine ←→, coarse
  ↑↓), sync, and the chip's ramp drawn at the variant's resolution (16 or 32 steps). Every edit is one undo step;
  empty slots take the Square lead on their first edit; Clear on Slot empties one; copy/paste a sound or an
  envelope.
- **Shared views**: Song, Keyboard, Sketch and Pattern as for the Mega Drive's PSG channels (three lanes, up to four
  effect columns); Help: effects, the shared resources and the instrument pages; lane tags from `sharedState`.

## PC Engine (HuC6280 PSG): decisions (0.26.0)

Full contract: `docs/AUDIO_ENGINE.md`, "PC Engine". Decided on the user's behalf (the user approved working through the
chip roadmap autonomously and asked for every onboard feature, including the direct-DAC sample mode):

- **Standalone system** `"pce-huc6280"`, "PC Engine (HuC6280)", no family. PC Engine games ship their own sound
  drivers (no standard one with a GPL-compatible licence to run), so it follows the OPL3 and OPM: **our own player**
  (C, mirrored by an independent Python reference) on an emulated PSG, exporting VGM and WAV; no HuCard/HES export.
- **Core**: Mednafen 1.32.1's PSG (GPL-2.0-or-later), vendored unmodified behind a small shim: cycle-timed, the
  hardware's sequential volume latching, the LFO and DDA as the author measured them on hardware. MAME's `c6280`
  (BSD-3) was used only to cross-check register semantics. The **original HuC6280** revision (PC Engine; uncentred
  amplitudes), not the HuC6280A.
- **Clock**: NTSC, the CPU's 7.16 MHz (master 21.48 MHz / 3); periods are 12-bit at 3.58 MHz, so notes are A-0..B-7
  (A-4 = period 254 = 440.40 Hz; the 12-bit period limits accuracy at the top, 19 cents at B-7). Tick: 60 Hz ×
  multispeed from the host timer, like the OPL3/OPM.
- **Six channels, no chords**, three sound kinds in one 64-slot bank: **tone** (a song wave: 32 steps × 5 bits, up to
  64 waves per song, plus an optional per-tick wave sequence), **noise** (channels 5-6 only, as on the chip; the note
  picks one of the 32 noise rates, note mod 32) and **sample** (the DDA: 5-bit frames streamed by the player from the
  HuC6280's timer interrupt, as PC Engine drivers do; any channel; pitched by stepping through the frames relative to a
  root note; loop or one-shot). Every kind has volume, arpeggio and pitch envelopes (loop and release points,
  FamiStudio-style); there is no hardware envelope.
- **Shared resources are song state**, changed by global effects: master balance (`X`), the LFO frequency and control
  (`L`, `M`; channel 2's waveform modulates channel 1's period, channel 2 keeps sounding as on the chip) and the DDA
  timer rate (`T`, 6991 Hz / (R + 1)). Per channel: volume (`C`, `A`), balance (`8xy`), wave (`W`).
- **Samples** are user data: imported `.wav` files (resampled to the song's DDA rate and quantised to 5 bits on the
  device) or the built-in starter kit synthesised in integer arithmetic at the song's DDA rate when it is added (no
  third-party audio; samples already in a song keep their frames). Samples, patterns and envelopes share the image's
  120 KB data region.
- **Model** (`system/pce/PceSound.kt`): `PceSound` = 64 slots of `PceInstrument?` (null = empty), up to 64 waves (32
  values 0..31 each), up to 64 `PceSample`s (5-bit frames, 1..65535), multispeed and the shared state at the song start
  (master, LFO frequency and control, timer reload). `PceInstrument`: name, `PceKind` (TONE, NOISE, SAMPLE), wave,
  four `PceEnvelope`s (volume 0..31, arpeggio −64..63, pitch −128..127 in 1/64 semitone, absolute or relative, and the
  tone-only wave sequence; up to 255 steps, loop and release points), and the sample fields (sample, root note, loop,
  loop start). Switching a sound's kind keeps the other kinds' fields. New songs get 12 built-in waves and 20 presets
  (15 tone, 5 noise) in slots 01-20; samples come from the Sample page. Project files store `"pce-huc6280"`, backend
  version 1.
- **Lowering** (`backend/PceLowering.kt`): the layout of `docs/AUDIO_ENGINE.md` — patterns, then envelopes (identical
  bytes stored once), then samples (identical data stored once); `PceGoldenTest` compares it with the independent
  Python builder (`tools/audio/pce/pce_song.py`). `PceValidator` mirrors every loader check with a user-facing message
  (noise sounds named on PSG 1-4, wave and sample indices, envelope shapes, the 64 KB reach of pattern and envelope
  offsets, the shared data budget with sample bytes).
- **Editor** (`PceInstrumentView`/`Painter`, reusing the shared FM painter base and the NES envelope plot;
  `PceWaveView`/`Painter`; `PceSampleMenu`; `PceEdits`): rows Slot, Name, Listen on, Kind, Preset, Wave; pages per kind
  (tone: Volume, Arpeggio, Pitch, Wave seq; noise: Volume, Arpeggio; sample: Sample, Volume, Arpeggio, Pitch). A noise
  sound listens on PSG 5. The Wave view draws and reshapes waves and renumbers sounds, wave sequences and `Wxx` when
  waves are inserted or deleted; deleting a sample renumbers the sounds that play later samples. Copying a sample
  sound carries its sample's frames: pasting it (in any song, even after the sample was deleted) plays the song's
  sample with the same frames or adds them as a new sample, as one undo step; with no room for a 65th sample the
  paste is refused and the song is unchanged. The Song view rows are Multispeed, Master left/right, LFO frequency,
  LFO control and DDA rate; the Pattern inspector and lane tags show the shared state at the cursor
  (`PceSharedState`).
- Demo: "Turbo Lanterns" (original, D minor): a pulse-width-cycling lead (wave sequence), `Wxx` glass arpeggios with
  pans, bass, starter-kit D/A drums with a `T01` half-rate fill, noise hats and snares on PSG 5-6, an LFO passage
  (PSG 2 silent with `C00`, `L`/`M` bending PSG 1) and a master fade-in (`X88`..`XFF`).

## Konami SCC and SCC+ (MSX)

Roadmap step 7 (more chips): the Konami SCC wavetable chip of MSX megaROM cartridges and its SCC+ variant, as two
systems of one family ("MSX SCC…" in New and Load demo). The contract is `docs/AUDIO_ENGINE.md`, "SCC and SCC+".
Decisions (made on the user's behalf; see Open questions):

- **The chip alone, two variants.** `"msx-scc"` = K051649 (megaROM SCC: channels 4 and 5 share one wave RAM),
  `"msx-scc-plus"` = K052539 in SCC+ mode (Sound Cartridge: five wave RAMs). Not an MSX: no PSG, no AY + SCC
  combination, no machine emulation. Everything else is identical, so one image layout, player and machine serve both;
  the image's variant byte picks the chip and each engine id loads only its own.
- **Own player + chip core + VGM**, the OPL3 precedent: Konami's drivers are not free and no free MSX SCC driver is a
  de-facto standard to run in the loop. Songs export as VGM (K051649 / K052539, which VGM 1.70 added) and WAV; no MSX
  executable is claimed.
- **Core: our C port of MAME's k051649** (BSD-3-Clause, notice kept), the emulator whose counter/latch model agrees
  with openMSX's hardware notes and with libvgm (the VGM players' core). Rejected: emu2212 (MIT, plain C) — its key
  enables change only at waveform wrap, its test-register rotation moves playback and it point-samples without band
  limiting, all contrary to the measured hardware notes. The port renders level-change events into blip_buf (no
  3.58 MHz loop) and advances silently in O(1), with identical state, so seeking is chip-exact.
- **Clock**: 3579545 Hz (the MSX bus clock: BiFi's "fclock = 3,579,545 Hz"); f = 3579545 / (32 (P + 1)). The VGM header
  carries 1789773 (the format's halved convention its players use; checked against libvgm's formula).
- **Shared wave, honestly**: on the SCC the player gives RAM 3 to whichever of SCC 4 / SCC 5 made the latest wave claim
  (a key-on, or a change of the wave it holds: `Wxx`, or a wave envelope step while its note is keyed, release tail
  included); both channels always play it. Once a note is keyed off — OFF without a release point, a second OFF, or
  its release reaching a final volume 0, which keys it off in that tick — its envelope no longer reaches the RAM (it
  keeps the last wave there until the other channel claims it); only an explicit `Wxx` on that keyed-off channel
  still claims.
  The song check warns where the two overlap with different waves, the Pattern lane headers and inspector show what
  RAM 3 holds (`sharedState`), and Help and the editors say so. SCC+ songs have no such rule.
- **Instruments** are FamiStudio-style envelopes run by the player (the chip has none): volume (with loop and release),
  arpeggio, pitch (absolute or relative) and a **wave envelope** (wave numbers per tick: wave sequences/morphs), plus
  a `restart` flag (the waveform restarts at each note through the test register's bit 5). Envelopes reuse
  `NesEnvelope` (same values, loop and release fields and ranges); the release rule differs from FamiStudio's for
  arpeggio, pitch and wave (open questions). 64 slots, `null` = empty, like the OPL3.
- **Waves**: 64 per song, 32 signed 8-bit samples each, always all present (an unused wave is silent), drawn in the
  Wave view. Live edits of a sounding wave or instrument are heard from the next tick (the player reads the image every
  tick), which suits wavetable editing; this differs from the OPL3 (sounding notes keep their registers).
- **Effects**: the OPL3's movement/sequencing set (`0 1 2 3 4 A B C D F G P S`) with `Cxx` 00-0F, plus `Wxx` (wave lock)
  and `Txx` (the chip's test register, exposed as hardware: restart, freeze, the unverified frequency modes). No chord
  effect: five fixed voices.
- **Pitch**: true pitch through a 768-step-per-octave period table (A-4 = P 253 = 440.40 Hz); the 12-bit divider limits
  accuracy at the top (≤ 4.2 cents to G#5, ≤ 12 cents to G#6, +19 cents at B-7). Notes A-0..B-7.

### SCC model, lowering and exports

- **Systems** (`system/scc`): `KonamiSccSystem(variant)` with `SccSystem` (`"msx-scc"`, "Konami SCC (K051649)") and
  `SccPlusSystem` (`"msx-scc-plus"`, "Konami SCC+ (K052539)"), family "MSX SCC"; five channels `SCC 1`-`SCC 5`
  (`ChannelKind.WAVE`, tonal), four effect columns, notes A-0..B-7 (9..95; FamiTracker names), one bank of 64 slots,
  60 ticks per second × multispeed (Song view: Multispeed 1-8), at most 256 orders, OFF = release (or cut without a
  release point; a release ending at volume 0 keys the note off itself), `3xx` glides only into a held (unreleased)
  note, fine pitch `Pxx` like the OPL3's. Constants come from `backend/SccPlayerImage` (generated).
- **Chip data** (`SccSound`): `variant`, 64 nullable `SccInstrument`s (`volume`, `arpeggio`, `pitch`, `wave`
  `NesEnvelope`s, `pitchRelative`, `restart`), 64 `SccWave`s, `multispeed`. A project whose backend and `variant`
  disagree is a structural error. New songs get ten starter waves (Sine, Triangle, Saw, Square, Pulse 25 %, Pulse
  12.5 %, Organ, Bell, Bass, Noise; the rest silent) and twelve presets in slots 01-12 (Lead, Bass, Bell, Pad, Pluck,
  Organ, Kick, Snare, Hat, Arp lead, Echo, Morph: a wave-envelope sequence); `SccWaveShapes` makes the shapes.
- **Lowering** (`backend/SccLowering`): the arranged song in the canonical layout (`docs/AUDIO_ENGINE.md`): tracks then
  envelopes, one dedup map, W = 64, I = the highest filled slot; `dataSize` for the validator.
- **Validation** (`SccValidator`): every `sc_load_image` check as a message naming pattern, channel and row (empty
  slots, envelope lengths/loop/release/values, wave numbers, `Bxx`, `Cxx` above 0F, `F00`, `W00`/`Wxx` beyond 64,
  unknown effects, data capacity), plus an empty envelope that still carries a loop or release point. Warnings (a
  row-level walk of straight play, `SccWalk`, shared with the shared-state display; it counts ticks so a release ending
  at volume 0 keys its note off at the player's tick): on the SCC, SCC 4 and SCC 5
  sounding together with waves whose samples differ (once per stretch: order, row, both waves and who claimed last);
  `Txx` bits 0-1 (unverified, VGM players ignore them), 2-4 (no effect), 7 on the SCC+ (ignored) and 6-7 (later wave
  changes are lost until cleared); `Wxx` where nothing sounds; `Gxx` beyond the speed; an order's first note without
  an instrument; notes above G#6 with their actual cents and period.
- **Shared state** (`sharedState`): on the SCC the lane tags of SCC 4 and SCC 5 name the wave RAM 3 holds (`W05`, `W05+`
  when the claiming instrument's wave envelope has several waves) and the inspector says who claimed it and where, and
  whether the claiming note still steps (keyed) or left the wave reached at its key-off (keyed off); a non-zero test
  register is listed on both variants.
- **Exports**: `.vgm` (`SccVgm`, the native renderer registered by each host, like the OPL3's) and `.wav` (the generic
  path). No MSX executable export: no MSX driver is in the loop.
- **Demos**: "Cartridge Dusk" (SCC; D minor, 150 BPM): SCC 4 and SCC 5 play one organ instrument together or alternate
  phrases ended by OFF, as the shared RAM allows; "Prism Lane" (SCC+; A major, 180 BPM): five waves at once, two waves
  of its own and a `T20` bridge (waveform restart at every period write: vibrato buzzes like hard sync). Both without
  errors or warnings; `SccDemoSong.shortest(variant)` is the one-row song the WAV test renders. `exportSamples` writes
  `cartridge-dusk.thor`, `prism-lane.thor`, `scc-demo.sccimg`, `scc-plus-demo.sccimg`, `scc-shortest.sccimg` and
  `scc-plus-shortest.sccimg`.
- **Tests**: `SccModelTest` (round trips of both variants, validation and warnings, the shared-wave warning on the SCC
  only, lowering bytes and dedup, arrangement, fine pitch, audition problems, shared state, demos), `SccGoldenTest`
  (the reference builder's test, features and SCC+ songs rebuilt from the model lower to the same 65536 bytes) and
  `SccEditorTest`.

### SCC editor

- **Instrument view** (`SccInstrumentView`, `SccInstrumentPainter`): the 64-slot bank (empty slots say how to start),
  then rows Slot, Name, Preset, Listen on, Restart, Page, Length, Loop, Release (volume), Mode (pitch), Steps; L1/R1
  switch the Volume, Arpeggio, Pitch and Wave pages, edited step by step like the NES envelope editor (`EnvelopePlot`,
  given a value-range overload that leaves NES and Mega Drive drawing unchanged); the Wave page shows the waves it
  steps through and points a following Wave view at the one under the cursor. Listening on SCC 4 or 5 of an SCC song
  notes the shared RAM. Unlike the NES editor, a release point without a loop is allowed (the player defines it).
- **Wave view** (`SccWaveView`, `SccWavePainter`): 64 waves of 32 signed samples; a name row, a thumbnail strip, tools
  (Sine, Triangle, Saw, Square, Pulse with Edit+←→ width, Noise with Edit+←→ reseed, Invert, Shift left/right, Smooth,
  Normalise, Use in sound) and the drawing grid (↑↓ pen by 8, Clear held + ↑↓ by 1, Edit held draws). Listen plays the
  wave through a sound that uses it. On the SCC a wave SCC 4 or 5 plays carries the shared-RAM note.
- **Help**: the effects page from `SccRules`, the Instrument, Envelopes and Waves sections and an "SCC and SCC+ wave
  RAM" section.

### YM2151 (OPM): decisions, model, lowering and editor (branch `opm-systems`, unverified source slice)

Full contract: `docs/AUDIO_ENGINE.md`, "OPM". Decided on the user's behalf (the user approved working through the chip
roadmap autonomously):

- **Standalone system** `"ym2151-opm"`, "OPM FM (YM2151)", no family: the X68000's and arcade boards' chip has no
  standard sound driver to run, so it follows the OPL3: **our own player** (C, mirrored by an independent Python
  reference) on **ymfm's `ym2151`** (the vendored pin; `ymfm_opm.h/.cpp` fetched at that commit), exporting VGM and WAV.
  ymfm rather than a die-accurate core: none with a GPL-compatible licence is vendored, ymfm's OPM uses David Viens'
  chip-verified pitch table, and it shares the YM2612's code path already in the app.
- **Clock: 4 MHz**, the X68000's (16 MHz / 4; MAME's `x68k.cpp`). Key codes are defined for 3.579545 MHz, so the
  player subtracts 123/64 semitone (192.27 cents) to keep Thor Tracker's absolute pitch; the playable range is then
  D#0..D-8. Arcade boards at 3.58 MHz are not a separate variant (a song plays at true pitch either way; only the
  range ends and envelope/LFO speeds would differ by 11.7 %).
- **Tick**: 60 Hz × multispeed from the host (as the OPL3), not the chip's timer B (on the X68000 drivers such as
  MXDRV take their tempo from timer B; it makes no sound, so Thor Tracker's own tick keeps tempo semantics identical
  across systems).
- **Eight channels, no chords** (each channel is one hardware channel; no voice allocation).
- **Shared resources are song state**, not per-voice settings: the LFO (rate, waveform, AMD, PMD), the noise
  generator (exists only on FM 8's operator 4) and CSM/timer A are header fields (Song view rows) and global effects
  (`L`, `W`, `T`, `V`, `N`, `Q`, `X`); instruments hold only what is per channel (PMS, AMS, AM enables) and the noise
  flag/frequency of their FM 8 note. A noise sound named on FM 1-7 is an error, not silently ignored. The Pattern
  inspector and lane tags show the shared state at the cursor (`SoundSystem.sharedState`).
- **CSM** is the YM2151's real feature (timer A keys on all eight channels' operators at each overflow), modelled in the
  ymfm wrapper by running ymfm's timers on the sample clock; auditions run without it (it would retrigger a released
  note forever).
- **Not exposed**: CT1/CT2 (X68000 ADPCM clock and floppy pins, not sound), timer B (no sound), the test register's
  undocumented LFO reset, the X68000's separate MSM6258 ADPCM chip.
- **Model** (`system/opm/OpmSound.kt`): `OpmInstrument` (algorithm, feedback, PMS, AMS, four `OpmOperator`s with the
  register fields DT1, MUL, TL, KS, AR, AM, D1R, DT2, D2R, D1L, RR, `noise` + `noiseFrequency`, the drum sweep),
  `OpmSound` (64 slots, multispeed and the shared state at the song start). Operators are numbered as in the
  algorithm diagrams (1 = M1, 2 = C1, 3 = M2, 4 = C2); the lowering writes each to its register slot.
- **Lowering** (`backend/OpmLowering.kt`) mirrors the OPL3's (`OpmGoldenTest` against `tools/audio/opm/opm_song.py`).
- **Editor** (`OpmInstrumentView`, `OpmInstrumentPainter`, reusing the shared FM pieces unchanged): head rows Slot,
  Name, Listen on, Page; *Sound* — the curated controls of `OpmCurated` (preset, algorithm, ratios with DT1 and DT2,
  brightness, level, feedback, attack, decay, sustain, sustain rate, release, tone decay, key scaling, vibrato = PMS,
  tremolo = AMS); *Expert* — algorithm, feedback, PMS, AMS, then DT1, MUL, TL, KS, AR, AM, D1R, DT2, D2R, D1L, RR per
  operator; *Drum* — drum and noise presets, noise on/off and frequency, the sweep. A noise sound listens on FM 8.
- Demo: "Sharp Corners" (original), all eight channels, the LFO, DT2 bells, noise drums on FM 8 and a CSM passage.

## OPN and OPNA (PC-88/98): YM2203 and YM2608

**Status: released as 0.30.0/code 43.** Native/headroom/reference, Kotlin, Android and SDL checks pass;
official signed artifacts were exercised on all three reserved handhelds and locally (`docs/VERIFICATION.md`).

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

## POKEY backend (Atari 8-bit)

Integrated for **0.31.0/code 44** with verified OPN/OPNA `c3c054f`. Native/reference, Kotlin, Android
and SDL verification passes; see `VERIFICATION.md`. Human listening and original-hardware fidelity remain unverified.

The Atari 400/800/XL/XE's POKEY has no console sound driver to run, so it follows the OPL3 precedent: Thor Tracker's
own player (`pokey/pokey_player.c`) on its own cycle-exact POKEY model (`pokey/pokey_chip.c`, after the Altirra Hardware
Reference Manual, Altirra 4.40 and MAME), exported as VGM. The contract (chip, image layout, player, effects, seek,
VGM) is `docs/AUDIO_ENGINE.md`, "POKEY"; this section is the Kotlin side.

### POKEY model, lowering and exports

- **System** (`system/pokey`): `PokeySystem`, id `"atari-pokey"`, name "Atari 8-bit (POKEY)", four channels `POKEY 1`-
  `POKEY 4` (`ChannelKind.VOICE`, tonal: noise distortions pick a rate from the note), four effect columns each, notes
  C-0..B-8 (absolute 0..107 = the player's note; FamiTracker-style names, A-4 = 57 = 440 Hz), one bank of 64 slots,
  one tick per video frame × multispeed (`ticksPerSecond` = the song machine's frame rate × multispeed: NTSC
  59.92, PAL 49.86), at most 256 orders. OFF is a release: envelopes with a release step jump to it; a sound whose
  volume envelope has no release step goes silent at once; a sample stops. Song settings (Song view): Machine
  (NTSC/PAL), Multispeed 1-4, Noise (17- or 9-bit generator at the start) and Base clock (64 or 15 kHz at the start).
  Fine pitch is `Pxx` (xx − 80 sixty-fourths of a semitone) in any free column, as on the OPL3. Constants come from
  `backend/PokeyPlayerImage`, a hand-kept mirror of `pokey_image.h` and the machine constants (change both together).
- **Chip data** (`PokeySound`): 64 slots (`null` = empty), up to 32 `PokeySample`s (4-bit levels 0..15, at most 32768
  each, all of them sharing 32768 levels of memory), the machine, multispeed and the AUDCTL bits the song starts with
  (`poly9`, `base15`). A `PokeyInstrument` has a **kind** (`PokeyKind`: 8-bit, 16-bit, high-pass, 16-bit high-pass,
  samples), `fast` (the 1.79 MHz clock where the kind allows it), FamiStudio-style `NesEnvelope`s for volume (0..15),
  arpeggio (semitones), pitch (sixteenths of a semitone, absolute or `pitchRelative`) and distortion
  (`PokeyDistortion` 0..8: AUDC 0 2 4 6 8 A C E and volume only), the high-pass `filterOffset` and `difference` mode,
  the partner channels' distortion and volume, and for samples the sample index, root note, rate (levels per second
  at the root) and loop. Switching a kind in the editor keeps every field (a lossless switch back); only the fields the
  kind uses are validated and lowered.
- **Kinds and channel taking**: a note takes every POKEY channel its kind needs (8-bit: its own; 16-bit on 2/4: also
  1/3, the low byte; high-pass on 1/2: also 3/4, the clock; 16-bit high-pass on 2: all four; samples on 1, 2 or 4),
  cutting whatever voice held them. Envelope release steps may sit on any of the four envelopes, with or without a
  loop (the image's rule: 1..length − 1, after the loop if there is one).
- **Straight-play walk** (`PokeyStraightPlay`, `PokeySongState.kt`): runs rows in order (jumps and delays ignored) as
  the player decides channel ownership — instruments, notes, OFF and `Sxx`, portamento (a `3xx` note only retargets a
  held voice: unreleased, and for a sample still playing according to its actual counters; a
  synthesized voice whose envelopes ended stays held), and global `F K N W R T` at tick 0. `PokeyChannelHolders` turns
  it into the Pattern lane tags ("16-bit 1+2", "held by 2", "HP 1 by 3", "HP clock
  of 1", "saw 1+3", "samples", "1.79 MHz") and inspector lines (the AUDCTL state; each lane's voice, what it takes,
  sounding/released/silent and since when). The player restores the base clock, noise generator and two-tone mode
  on loop entry; the inspector is a straight-play projection, not a simulation of jumps or subsequent loop passes.
  `PokeyOwnershipPlayback` and `PokeySampleClock` advance rounded counters with inherited phase, prescalers,
  pending fires, STIMER, generator restarts and two-tone synchronization. Pitch slides/glides, arpeggios,
  vibrato, fine pitch and envelopes affect sample lifetime per tick; absent `000` cells leave continuing effects
  intact. Timer advancement is arithmetic or memoized, not per sample level; stable rows advance in bulk.
  Sample-free songs retain the cheap row-only walk. Native boundary cases and an independent cycle-stepped
  Kotlin timer oracle pass; the former ideal-rate estimate fails the preserved high-note ownership regression.
- **Pitch** (`PokeyPitch`): the player's pitch-to-counter search in Kotlin, the same double operations (target period
  C / (f · D), coprimality with the distortion's pattern length, the 49-value 16-bit window clipped to the range, the
  difference sound's search, samples' 114-cycle minimum period, ties to the smaller N, rounding half away from zero).
  It gives the readouts ("A-4 on POKEY 1: 437.8 Hz (−9¢)"), the summaries and the song check's pitch warnings. The JVM's
  `pow` may differ from the C library's in the last bit, so in a rare exact tie a readout or warning could name the
  neighbouring counter from the one the C player picks; playback and export never use the Kotlin search.
- **Pattern loop** (`PokeySystem.patternLoop`): the looped order starts with the state sequential straight play starts
  it with (`startState`, the loader's `start_speed`/`start_audctl`/`start_two_tone` derivation: earlier played orders'
  last `Fxx`, `Kxx`/`Nxx` and `Wxx`, jumps ignored): that speed as the song speed, those bits as the start bits, and a
  non-zero two-tone mode as a `Wxx` in the first free effect column of row 0 (lanes, then columns). When row 0 sets
  two-tone itself, its own `Wxx` stands; when row 0 has no free effect column at all, the loop plays with two-tone off.
- **Lowering** (`backend/PokeyLowering`): the arrangement (parked orders out, `Bxx` and the loop renumbered), rows per
  order = the pattern's length; the data region holds every (order, channel) track encoded as the OPL3's (runs, mask,
  note/OFF, slot, non-blank columns), byte-identical tracks once in first-use order, then the envelopes of every filled
  slot (slot order; volume, arpeggio, pitch, distortion), byte-identical envelopes once; an empty envelope is "none".
  Records write only the kind's fields (filter offset for high-pass, partner AUDC for 16-bit and high-pass, sample,
  root, rate and loop for samples, whose volume and distortion envelopes are "none"), the rest 0 / 0xFFFF. Samples are
  packed in index order from byte 0, two levels per byte, low nibble first. Over-full data or sample memory throws an
  `IllegalArgumentException` with what to do.
- **Song check** (`PokeyValidator`): structural errors (shapes and ranges, the kind's fields only); errors that block
  playback or the loader (empty slots named in cells, a sound on a channel its kind cannot use or fast where the
  channel has no 1.79 MHz clock — also for notes inheriting the lane's sound —, samples missing or looping past their
  end, sample memory, effect parameters as `pk_load_image` checks them: `Bxx` < orders, `Cxx` ≤ 0F, `F00`, `Kxx`/`Nxx`
  ≤ 1, `R00`/`T00` only, `Vxx` ≤ 8, `Wxx` ≤ 2, data size); warnings (a note cutting another lane's unreleased voice,
  distortions 4 and C on a 15 kHz base-clocked counter, notes more than 50 cents off because the counter is out of
  range or coarse there, `Hxx` on sounds without high-pass or clamped on difference sounds, `Vxx` on samples, `Gxx`
  the speed never reaches, an order's first note without a sound). Limits: instruments, samples and sample memory,
  pattern and envelope data.
- **Samples** (`PokeySamples`): `.wav` import through the NES importer's parser (`Dpcm.Wav`: PCM 8-32-bit or float,
  stereo mixed), linear resampling to the chosen rate (offered: 3995, 5327, 6392, 7990, 10653, 12784 Hz — the NTSC
  64 kHz clock's exact rates; 12784 Hz is the fastest a 64 kHz sample channel plays within the 114-cycle minimum, and
  faster rates, exact only on POKEY 1 at 1.79 MHz or on the 15 kHz clock, are set by hand), then a **linear, envelope-biased** mapping to
  0..15: each value plus a peak-following envelope (instant attack, ~5 ms half-life), scaled so the loudest peak
  reaches 15; silence maps to 0, the level a channel rests at, so samples start and end without a DC click. The
  DAC's nonlinearity is not pre-compensated. The starter kit (kick, snare, hat, tom at 7990 Hz) is synthesised with
  integer arithmetic (deterministic on every platform), one-sided and faded to 0. Memory counts levels in whole bytes
  (an odd-length sample uses one level more).
- **Presets** (`PokeyPresets`): pure lead, soft square, major/minor arpeggio chords, distortion C bass, 16-bit bass,
  phasing lead (high-pass), POKEY saw and 16-bit saw (difference), noise snare and hat, tone kick, volume-only thump,
  sync lead and the silent sync clock for two-tone `W02`. Blank songs have no samples, so sample sounds are not
  presets: `drumKit(firstSample)` makes the four digi drum sounds once the editor's kit import has added the samples.
- **Exports**: VGM 1.71 through the native player (`PokeyVgm.renderer`, registered by `ChipAudio.nativePokeyVgm` and
  the Deck's `DeckPokeyVgm`; ten minutes at most when a song never returns to its loop). WAV through the shared path.
  No Atari executable (XEX/SAP): that would need a 6502 driver.
- **Project files**: backend `atari-pokey`, POKEY data version 1, project format 10; `sound` holds the 64 slots
  (`null` or `name`, `kind` `tone`/`joined`/`filtered`/`joined-filtered`/`sample`, `fast`, the four envelopes as NES
  envelopes, `pitchRelative`, and when not at their defaults `filterOffset`, `difference`, `partnerDistortion`,
  `partnerVolume`, `sample`, `root`, `rate`, `loop`), `samples` (`name`, `levels`), `machine` `ntsc`/`pal`,
  `multispeed`, `poly9`, `base15`.
- **Demo** "Scanline Cruise" (NTSC, D minor, 6 ticks per row): Intro (pure lead, distortion C bass, arpeggio-envelope
  chords, noise snares over volume-only thumps), Drive (digi drums from the starter kit, a 16-bit bass taking POKEY 3,
  `K00 N00 W00 T00`), Glow (a high-pass phasing lead with `Hxx`, `0xy` square chords), Saw (the difference saw at
  1.79 MHz), Static (`K01 N01 R00` and two-tone sync `W02`: the silent sync clock sets the pitch on POKEY 2, the
  1.79 MHz sync lead sweeps the timbre on POKEY 1), Drive again, looping to Drive. `PokeyDemoSong.shortest()` is one
  order of one row at speed 1 with a held A-4 on POKEY 1. Both have no errors or warnings.
- Tests: `PokeyModelTest` (registry, project round trips, presets, demo and shortest song checks, the pitch search's
  known values and true pitch across kinds, kind/lane and effect-parameter errors, unused fields, warnings, audition,
  lane ownership, sample lifetime under pitch effects, samples and WAV import, arrangement), `PokeyLoweringTest` (exact bytes of the shortest song,
  kind-specific records, sample packing, shared envelopes, and every `pk_load_image` condition re-checked on the demo,
  the shortest song and a song with every kind).
  `PokeySampleClockTest` compares the accelerated counters with an independent cycle-stepped oracle.

### POKEY editor

- **Instrument view** (`PokeyInstrumentView`, UI slice): head rows slot, name, Listen channel, kind, page. Pages:
  *Sound* (preset, clock, high-pass offset and difference mode, partner channels, pitch mode, with which channels a
  note takes and how in tune it plays from `PokeyPitch`), the envelopes *Volume*, *Arpeggio*, *Pitch*, *Distortion*,
  and for sample sounds *Sample* (sample, root, rate, loop, the song's samples, `.wav` import and the starter kit).
- **Shared views**: New and Load demo list the POKEY through `SoundSystems`; Pattern shows four lanes with four effect
  columns and the channel-holder tags; Song lists Machine, Multispeed, Noise and Base clock; Help's effects page uses
  `PokeyRules` and the system help.

## Atari 2600 (TIA): decisions (0.32.0/code 45 locally verified; publication pending)

Full contract: `docs/AUDIO_ENGINE.md`, "Atari 2600". Decided on the user's behalf (roadmap item 7, "TIA"; every
onboard sound feature, nothing invented):

- **Two systems, one family**: `"atari2600-tia-ntsc"` "Atari 2600 NTSC (TIA)" and `"atari2600-tia-pal"` "Atari 2600 PAL
  (TIA)", family "Atari 2600" (New and Load demo list it with both variants, as the NES). The TV standard is not a
  song setting because it is the chip's clock: NTSC 3579545.45 Hz, PAL 3546894 Hz, so every waveform's 32 pitches and
  the frame rate (the tick: 262 or 312 lines of 228 colour clocks, 59.92 / 49.86 Hz) differ. A song stores its region
  (`TiaSound.region`); a project's backend must match it. SECAM consoles (PAL timing, different video; a reported AUDC
  0/B difference no source pins down) are not modelled.
- **Own player**, as the OPL3 and PC Engine: 2600 games ship their own 6507 drivers (TIATracker's is console assembly),
  so `tia/tia_player.c` implements the specification and the independent Python reference (`tools/audio/tia/`) checks
  it write for write. **Core**: Stella 7.0's `AudioChannel` (Christian Speckner's gate-level model of the counters,
  GPL-2.0-or-later), vendored unmodified; Stella's event positions (line clocks 9/81 and 37/149) and its mono mixing
  formula (two channels at full volume = 1.5 × one, the console's shared output node) reimplemented and credited.
  Power-on counter state 0 (Stella's; real consoles start arbitrary). Nothing restarts a waveform: new notes continue the
  running counters, as on hardware.
- **Honest pitch**: notes are true pitch (0 = C-0, A-4 = 440 Hz) and a **tonal** sound plays the AUDF whose frequency
  is nearest in log frequency for the waveform playing that frame (`TIP_AUDF_EDGE`, generated into
  `TiaPlayerImage.AUDF_EDGE_NTSC/PAL`; `TiaRules.audf` mirrors the native lookup). The note sounds at the divider's
  real frequency (A-4 on div 6: AUDF 11, 436.10 Hz NTSC / 432.13 Hz PAL); out-of-span notes play the nearest end
  (AUDF 0 or 31) and are a validator **warning**, not an error. Slides, vibrato, portamento, `Pxx` and pitch envelopes
  work in 1/64 semitones and are heard where they cross a divider edge: they step, as on the chip. A **raw** sound (for
  noise and drums, where the waveform's fundamental is a repetition rate) takes AUDF = 31 − ((note + arpeggio) mod 32)
  and ignores fine pitch, slides, portamento, vibrato and the pitch envelope. Constant waveforms (AUDC 0, B) write no
  AUDF in tonal mode.
- **Two channels, no chords, no shared controls**: `TIA 1`, `TIA 2` (`ChannelKind.VOICE`, tonal), each owning its AUDC,
  AUDF and AUDV; two effect columns. What the channels share (the audio clock, the output node) is documented, not a
  control.
- **No samples**: the TIA has no dedicated sample-playback hardware; writing AUDV on a constant waveform (AUDC 0) many times a frame
  is a CPU technique that would need a cycle-timed 6507 driver. It is offered only at the frame rate, through volume
  envelopes on AUDC 0/B (the "DC click" preset).
- **Exports**: WAV only (the generic `chip_wav.c` over the variant's ops). VGM has no TIA (its specification lists no
  such chip, command or clock field), so nothing is invented; no register dump, no cartridge (that needs a 6507 driver
  and a display kernel).
- **Seek**: fully clocked replay up to `TIM_SEEK_TICKS` 7200 frames (2 min NTSC, 2 min 24 s PAL), then the order
  fallback, as the other systems.
- **Model** (`system/tia/TiaSound.kt`, backend version 1): `TiaSound(region: TiaRegion ("ntsc"/"pal"), instruments: 64
  × TiaInstrument?)`; `TiaInstrument(name, waveform 0..15 (AUDC), raw, relativePitch, volume 0..15, arpeggio −64..63,
  pitch −128..127, waveforms 0..15)` — four `TiaEnvelope(values, loop, release)` of up to 255 steps. New songs get 16
  presets in slots 01-16 (`TiaPresets`: squares at div 6 and div 2, a div 6 pluck, div 31 bass, div 93 low, 4-bit
  buzz, poly leads 7 and F, raw rumble 3 and engine 2, a noise-then-div-93 kick (waveform envelope), snare and hats on
  raw AUDC 8, an AUDC 0 click, a raw div 2 zap), each named for its waveform.
- **Lowering** (`backend/TiaLowering.kt`): the layout of `docs/AUDIO_ENGINE.md` — header with the region byte,
  patterns (byte-identical encodings once, first use), then envelopes in slot order (byte-identical value lists once),
  every filled slot its own record (never aliased, so a live edit of one of two identical sounds changes only it);
  `TiaGoldenTest` compares it with the Python builder. `TiaValidator` mirrors every loader check with a user-facing
  message (speed, orders and rows, notes C-0..B-9 or OFF, defined slots, effect codes, `Bxx`/`Cxx`/`F00`/`Wxx`, sound
  records and envelope shapes, the 62144-byte data region) and warns about tonal notes outside the span of the waveform
  they key on with (the sound carried per channel along straight play, `Wxx` and the waveform envelope's first step
  included), `Wxx` on a sound with a waveform envelope (ignored), unreachable delays and first notes without a sound.
- **Editor** (`TiaInstrumentView`/`Painter` on the shared FM painter base and the NES envelope plot; `TiaEdits`): rows
  Slot, Name, Listen on, Waveform (with its span on the song's standard), Pitch mode (tonal/raw), Preset; pages Volume,
  Arpeggio, Pitch (with relative/absolute), Waveform seq and **Pitch guide** (the waveform's 32 dividers: AUDF → Hz →
  nearest note ± cents; Edit plays the divider under the cursor). No Wave view and no song settings. The Pattern
  inspector and lane tags (`TiaSharedState`) show the **key-on registers** straight play reaches per channel at the
  cursor (note, AUDC incl. `Wxx` and the waveform envelope's first step, AUDF, Hz, cents), computed from row data and
  the carried sound only (envelopes, slides and volume are not run).
- Demo: "Cartridge Dawn" (original, D Dorian, Dm-C-G-Dm with an F-C bridge): a div 6 lead on the notes div 6 plays in
  tune, a div 2 sparkle arpeggio, div 31 bass sharing TIA 2 with raw noise drums and a waveform-envelope kick, a slide
  and a portamento that step through the dividers, and `W01` turning held notes into the 4-bit buzz at the same pitch.
  NTSC at 7 frames per row, PAL the same composition at 6.

## VIC-20 (VIC) and C16 / Plus/4 (TED): decisions (0.33.0/code 46 integration; full verification pending)

Full contract: `docs/AUDIO_ENGINE.md`, "VIC-20 and C16/Plus/4". Decided on the user's behalf (approved autonomous
work through the chip roadmap; every onboard sound feature, stock NTSC/PAL variants, no invented per-voice volume):

- **Two systems, one engine design**: `"vic20-vic"` "VIC-20 (VIC)" and `"c16-ted"` "C16 / Plus/4 (TED)", no family
  (different machines). Neither has a standard driver with a GPL-compatible licence, so both follow the OPL3: **our
  own player** (`cbm/cbm_player.c`, one image layout with a chip byte, mirrored by `tools/audio/cbm/cbm_ref.py`) on an
  emulated chip. No VGM (the format defines neither chip) and no `.prg` (a console executable); project + WAV only.
- **Cores**: VICE's `vic20sound.c` (viznut's reverse-engineered 6561 model: Johnson-counter squares, the noise LFSR
  clocking the fourth shift register, the measured nonlinear `voltagefunction`, the board's 1.6 kHz low-pass and
  159 Hz high-pass) and plus4emu's TED sound (István Varga: the 221 kHz sound clock, the `$3FE` no-toggle case and its
  decay, D/A mode, the noise LFSR, the volume curve and output nonlinearity) — both GPL-2.0-or-later, adapted in
  `cbm/cbm_vic.c` and `cbm/cbm_ted.c` with their headers, pinned by commit and hash. VICE's own TED sound was not used:
  it steps per output sample.
- **Variants**: PAL and NTSC per song (the Song view's **Machine** row), not separate systems: the chip and its
  features are identical, only the clock and frame change. Ticks are the machine's real frame (PAL VIC 50.04 Hz, NTSC
  60.28; TED 49.86 / 59.92) × multispeed 1-4 — a raster-interrupt driver's clock, not the host's 60 Hz.
- **Lanes = hardware voices, no chords**: VIC Bass, Alto, Soprano, Noise; TED Voice 1, Voice 2 (square or noise, the
  chip's own switch), DAC. Note ranges per lane and machine are the notes the chip sounds within ±50 cents (VIC
  lanes ~3⅓ octaves each, worst 46.5 cents PAL; TED A-2..C-8). The VIC Noise lane's notes are the 128 noise rates
  (higher = brighter); TED Voice 2 reaches note 127 for bright noise (a square up there is out of tune: warning).
- **Shared volume, honestly**: neither chip has a per-voice volume, so sounds have no per-voice volume. The one
  master volume is song state (`Vxx`, `Axy`) and a sound's **Master vol** envelope, which while its note sounds takes
  the master volume for every voice (the "owner"; the last key-on with such an envelope wins, shown as the `VOL` lane
  tag). Per-voice shaping is the **Wave** envelope (per tick off/square/noise: gating and square↔noise drums),
  arpeggio and pitch envelopes. A volume or wave envelope with a release point makes OFF a release.
- **TED D/A**: samples (9 levels, 0..8) on the DAC lane, one frame per TED timer 1 interrupt at a shared rate
  (`Txx`: reload 64 + 4·T single clocks, 13.9 kHz..818 Hz PAL), pitched against a root note. D/A mode holds both voice
  oscillators and outputs the volume register as the level, so a sounding DAC note silences both voices (lane tag
  `D/A`); the voices resume when it ends. The DAC ignores the master volume. The VIC has no D/A: VIC-20 "digis" that
  write the volume register exploit the chip's DC offset (which the core reproduces: a volume change steps the level)
  but are not an onboard sample facility, so they are not offered.
- **Not offered** (documented, not hidden): the VIC's cycle-timed shift-register waveform tricks (loading patterns by
  toggling bit 7 at chosen cycles) need a cycle-exact CPU routine, not a frame-tick player; the TED's `$3FE` value
  (no tone, decays high) is a register the player never produces (periods are 2..1024).
- **Model** (`system/cbm/CbmSound.kt`): `CbmSound` = chip, 64 slots of `CbmInstrument?`, TED `samples` (≤ 64, 1..65535
  frames 0..8), `video`, `multispeed`, start `volume`, TED `dacTimer`. `CbmInstrument`: name, kind (tone/sample), wave
  (1 square, 2 noise on the TED), envelopes volume (master) / arpeggio / pitch (abs/rel) / wave with loop and release,
  sample fields. Backend version 1.
- **Lowering/validation** (`backend/CbmLowering.kt`, `CbmValidator.kt`): the layout of `docs/AUDIO_ENGINE.md`;
  patterns, then envelopes, then samples, each deduplicated among its own kind; the validator mirrors the loader
  (lane ranges by machine, placement: sample sounds on the DAC lane only, noise-capable sounds on TED Voice 2 only;
  effects per lane; 64 KB offset reach; capacity) and warns about square notes above C-8 on Voice 2, overlapping master
  envelopes and DAC notes silencing the voices.
- **Editor**: Instrument view (rows Slot, Name, Listen on, Kind and Wave on the TED, Preset; pages Master vol,
  Arpeggio, Pitch, Wave, and Sample on the TED) on the NES envelope plot, a TED sample menu (`.wav` import resampled to
  the song's DAC rate and quantised to 0..8, an integer-synthesised starter kit), presets per chip, Song view rows
  Machine, Multispeed, Master volume and DAC rate, lane tags and inspector lines from `CbmSharedState`.
- Demos (original): **Kernal Lights** (VIC-20, A minor: master-volume swells owned by the alto, gated arpeggios, noise
  drums picking rates) and **Plus Four Parade** (C16 / Plus/4, D minor: both voices, Voice 2 square↔noise drums, D/A
  drums with DAC-rate changes).

## Open questions

- OPM clock (decided on the user's behalf, per the brief): 4 MHz (X68000) only; a 3.579545 MHz (arcade) variant would
  be a second system id with its own pitch offset (0) and range. Review if arcade-accurate envelope/LFO speeds matter.
- OPM tempo source: the player's own 60 Hz × multispeed tick rather than the chip's timer B (which X68000 drivers use).
  Timer B makes no sound, so nothing audible is lost; tempos are not expressed in timer B units. Review.
- OPM noise: modelled as an FM 8-only instrument flag plus `Nxx` (the hardware has one noise generator on FM 8's
  operator 4). Alternative: a song-level noise setting with FM 8 instruments ignoring it. Review.
- OPM CSM in VGM players: the VGM carries the `$10`/`$11`/`$14` writes; whether a player emulates CSM key-ons
  depends on its YM2151 core (ymfm and MAME do). Not checked against other players.
- OPM curated lists and presets are a first proposal, like the OPL3's and YM2612's.

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
- YM2612 performance: XGM2 must be measured with active PCM and paced hardware
  playback. The old XGM1 silent-mixer pass-replay timings no longer apply.
- YM2612 core: ymfm (BSD-3); Nuked-OPN2 (LGPL-2.1+, die-accurate) remains an option if
  listening finds ymfm lacking. XGM2 now models three Z80 wait cycles for accesses
  to the 68000 bus, including the ROM window and PSG port; chip clocks keep running.
- OPL3 curated lists (algorithms, 22 ratio sets, 20 waveform combinations per operator count, key scaling, LFO) and
  the 17 presets are a first proposal; the user's own FM simplification ideas may replace them (they only read and
  write operator registers, so songs are unaffected).
- YM2612 curated lists (8 algorithms, 26 ratio sets with detune, 11 SSG-EG presets, key scaling, LFO depths) and the
  24 presets are a first proposal, like the OPL3's; they only read and write operator registers and envelopes.
- YM2612 compiled export uses `.xgc`: import it into SGDK as a 256-byte-aligned, uncompressed `BIN` resource and call
  `XGM2_play`. Do not recompile it with the pinned xgm2tool 1.08, whose XGM2 re-import corrupts loop offsets and PCM
  signedness (see `AUDIO_ENGINE.md`). The unpacked `.xgm` is retained for format-aware tooling.
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
- FDS (decided on the user's behalf): true pitch with FamiStudio's note table shifted 24 notes on the FDS channel (a
  one-cycle wave sounds as named: A-4 = 440 Hz on every chip); the FamiStudio text keeps FamiStudio's note numbers, which
  FamiStudio names two octaves higher. Review.
- FDS engine build: auto-mod remains enabled. A tagged manual-speed encoding and scoped loader correction support
  all 0-4095 values instead of dropping the ambiguous low-byte range. FamiStudio text preserves the exact value;
  its editor preview works, but upstream 4.5.3 assembly/NSF exports can reintroduce the bug. Review interoperability.
- FDS `Ixx`/`Jxx` in one effect column: each sets half of the 12-bit speed, the other half taken from the channel's
  speed at that row along the played order (FamiTracker's letters; FamiStudio's own FamiTracker import zeroes the other
  half instead). Review with use.
- FDS output: FamiStudio's measured DAC curve, a 1-pole 2 kHz low-pass and 2.4 × a full 2A03 pulse (NESdev); no hardware or
  reference-emulator comparison yet. Listening may ask for a different balance or filter.
- VRC7 sustain overrides (decided on the user's behalf): one song setting (`NesSound.vrc7Sustain`) instead of
  FamiStudio's per-instrument checkboxes, because FamiStudio's engine applies the last loaded VRC7 instrument's to all
  six channels; the exports write it into every VRC7 instrument. Review.
- VRC7 custom patches stay per sound (FamiStudio's model, several custom sounds may take turns) rather than one song
  patch; holding notes of two different custom sounds at once is a warning, not an error, since the chip itself plays
  it (both sound like the one loaded last). Two deliberate corrections to FamiStudio's exporter preserve the editor's
  musical behavior: restoring the shared patch at loop entries, and restoring it mid-song without retriggering an
  explicitly no-attack note. The adversarial upstream-export golden covers both; FamiStudio 4.5.3 re-exports of our
  text can reintroduce them. Review.
- VRC7 `Pxx` means the pulse's detune converted to FM steps (one meaning on every channel, tunings work) rather than
  FamiStudio's raw FM steps; the FamiStudio text carries the converted steps. Pitch envelopes stay raw (FamiStudio's
  meaning: frequency steps × 8, + raises). Review.
- VRC7 mixing level: one emu2413 output unit against the 2A03 pulse from rainwarrior's VRC7 level survey (FM about
  +11 dB over a full 2A03 square), the VRC7 build's output gain lowered so six full FM channels do not clip; no
  listening comparison with a VRC7 cartridge or NSFPlay yet. Listening may ask for a different balance.
- VIC/TED master-volume owner (decided on the user's behalf): the last key-on of a sound with a Master vol envelope
  drives the chip's one volume for every voice; `Vxx` sets the level the envelope is offset by. Alternatives: only
  effects (no envelope), or a fixed "volume lane". Review once listened to.
- VIC/TED machine as a song setting (PAL/NTSC) rather than four systems: notes keep their pitch; a song whose notes
  leave the new machine's lane ranges shows errors after the switch. Review.
- TED D/A rate `Txx` = timer reload 64 + 4·T single clocks (a driver choice: the hardware timer is 16-bit); DAC writes
  always enable both voice outputs (the louder of the TED's two D/A curves). Review.
- VIC output level: VICE's per-sample average of the voice count is applied per cycle before the nonlinear table
  (what the DAC does); no comparison with a real VIC-20 recording. TED: no board filter modelled. Listening may ask
  for changes.
- POKEY channel taking (decided on the user's behalf): a note takes every channel its kind needs and the most recent
  note wins, with a song-check warning where an unreleased sound is cut and lane tags showing the holders, rather
  than reserving channels per pattern or forbidding notes on taken lanes. Review.
- POKEY sample import (decided on the user's behalf): linear amplitude mapping with an envelope bias (silence = level
  0, no click at the start and end) rather than mid-level-centred levels or pre-compensating the DAC's nonlinearity.
  Listening may prefer centred levels for sustained (looping) samples.
- POKEY pitch warnings flag notes more than 50 cents off at their key-on (the note, `Pxx` and the starting
  distortion); arpeggios, slides and envelopes moving into coarse ranges are not checked. Proposed: keep.
- POKEY presets are a first proposal (15 sounds plus the 4-sound digi kit); the 16-bit high-pass kind's preset uses
  difference offset 4 at 1.79 MHz.
- Atari 2600 regions as two systems (decided on the user's behalf): NTSC and PAL are variants of one family because
  the clock sets every pitch and the tick; converting a song between them would keep its notes and frames per row
  (re-quantizing every divider). SECAM is not a variant. Review.
- Atari 2600 tonal quantization picks the nearest divider in log frequency for the waveform playing that frame, and
  out-of-span notes play the nearest end with a warning. Alternatives (refusing out-of-span notes, or a per-sound
  "tuned" note table) were not taken. Review once listened to.
- Atari 2600 raw pitch mode maps C-0 to AUDF 31 and each semitone to one divider step, repeating every 32 semitones
  (the PC Engine noise convention); an alternative is a raw sound whose note column names the AUDF directly
  (00-1F). Review.
- Atari 2600 power-on state: all counters 0 after start and audition (Stella's); real consoles start arbitrary, so the
  first notes of a song may differ slightly on hardware.
- Atari 2600 output level: Stella's mixing formula (two full channels = 1.5 × one) with blip_buf's high-pass; no
  hardware comparison yet.
- Atari 2600 sample playback: not offered; fast AUDV writes from the CPU would need a cycle-timed driver.
  A per-frame volume envelope on AUDC 0 is the only "DC" sound. Review if the user wants a software-sample mode.
- SCC shared wave (decided on the user's behalf): on the K051649 the latest claim by SCC 4 or SCC 5 wins RAM 3 and the
  song check warns about overlaps, rather than forbidding different waves on the two channels or silently giving SCC 5
  priority. Review.
- SCC envelope loops at a release (decided in the source spec, unreviewed): a released note stops every loop — volume,
  arpeggio, pitch and wave — and runs each envelope to its end. FamiStudio (and the Mega Drive's PSG envelopes) move
  only an envelope with a release point and keep looping the rest, so here Arp lead's looping arpeggio settles on its
  last step and Morph's wave cycle stops during their release tails. A released note stays keyed (and its wave steps
  keep claiming RAM 3) only while its release sounds: the tick that plays a final volume 0 keys it off. Review.
- SCC test register bits 0-1 ("4-bit"/"8-bit frequency"): modelled as openMSX documents Manuel Pazos's measurements
  (applied at period writes); BiFi's early tests reported other multipliers, MAME and libvgm ignore the bits. Exposed
  through `Txx` with a warning; unverified on hardware.
- SCC test register bit 7 on the SCC (freezes the shared RAM) and bit 6 (freezes all): the read-side rotation they also
  cause is not modelled (the player never reads), and the bus-conflict spikes MAME lists for simultaneous CPU and chip
  wave RAM access are not emulated.
- SCC mixing: one channel at full volume and full-scale wave = 120 output units of the chip's 11-bit sum, × 48 into the
  resampler (five full channels reach 0.88). No comparison with a recorded cartridge yet; listening may ask for
  another level or the cartridge's analog filtering.
- SCC 50 Hz (European MSX) timing is not offered: ticks are 60 Hz × multispeed, as Konami's Japanese releases ran.
- PC Engine revision (decided on the user's behalf): the original HuC6280 (Mednafen's PC Engine default). The HuC6280A
  (SuperGrafx, CoreGrafx I) centres channel amplitudes and clicks less; it would be a song setting. Review.
- PC Engine noise: the note picks the rate (note mod 32, as Deflemask/Furnace map it) on a noise sound, rather than a
  noise-rate envelope; arpeggios step through rates. Review.
- PC Engine DDA: samples play from the CPU timer at one shared rate with a per-note step (nearest frame), as drivers
  do on hardware; the VGM writes registers per interrupt (about 28 KB/s for one sample channel at 6991 Hz: the R6
  write plus a wait; with more channels every R6 also needs a channel select, about 91 KB/s for two) instead of VGM
  DAC-stream commands. CD-ROM² ADPCM (a separate chip on the CD unit) is not part of the HuC6280 and is not modelled.
- PC Engine LFO: exposed raw (channel 2's note, wave and volume are the modulator's rate, shape and audibility) rather
  than hidden behind a vibrato abstraction, because that is what the chip does. Review once listened to.
- PC Engine output gain: Mednafen's level ÷ 2 so six full unipolar channels do not clip after the high-pass; no
  hardware comparison yet.
- PC Engine phase-exact seeking: the demo's order 4/row 48 took 134/283/428 ms on Nova/DMG/Konkr.
  A deliberately extreme valid order (speed 255, five B-7 tones and one looped DDA sample, seek to row 63:
  16,065 replayed ticks) took 14.87 s on Konkr. The UI remained responsive (maximum measured UI round trip 17 ms)
  and audio reported no xruns or driver faults. Keep exact chip/filter state rather than silently changing the
  sound; this long-seek case remains a performance tradeoff to revisit.
- AY variants (decided on the user's behalf): four stock machines — ZX Spectrum 128 (AY-3-8912), MSX (AY-3-8910,
  NTSC 60 Hz frames), Atari ST (YM2149F, PAL 50 Hz VBL) and Amstrad CPC (AY-3-8912 at 1 MHz, 50.08 Hz frames, the
  stereo socket) — each with its own clock, chip type and wiring. Not modelled: PAL MSX (50 Hz), MSX-ENGINE machines
  whose YM2149 has the YM's 32-step envelope, the NTSC ST, the CPC's 300 Hz interrupt rate as a tick, the CPC's mono
  internal speaker, ABC/ACB stereo modifications of the mono machines. The CPC socket's B weight (equal on both
  sides) is an inference from its resistive mixing, not a measured value. Review.
- AY output summing: the three channels add linearly; the real machines tie the outputs through resistors (MAME's
  resistor model; Hatari uses a measured 3-D table for the ST), which compresses loud chords a little. Proposed: keep
  linear until a listening comparison asks otherwise.
- AY envelope period 0 follows MAME's counter model: twice period 1's ramp rate on AY, the same rate as period 1
  on YM2149. Native boundary tests and editor frequency readings agree; no physical-chip capture is claimed.
- AY shared noise/envelope arbitration (decided on the user's behalf): the most recent key-on among the channels
  using a resource owns it; an owner change re-writes R13 only when the shape differs, so a held buzzer bass is not
  retriggered when a short note that borrowed the envelope ends (its envelope phase continues). Alternatives: a fixed
  channel priority (FamiStudio's 5B: the highest channel) or per-song ownership. Review.
- AY VGM loops do not dump R13 (a write restarts the envelope): a loop entered with a held hardware-envelope note
  continues the envelope as the previous pass left it.
- AY hardware envelope with volume: the chip cannot scale an envelope's level, so on such notes the volume envelope
  and `Cxx` only gate it (any level above 0 plays the full envelope; 0 silences). Review.
- Master System (decided on the user's behalf): four systems over one Banjo engine — `sms-psg`,
  `gg-psg`, `sms-fm`, `sms-fm-drums` — rather than one system with a layout setting (channel layouts are fixed per
  system). Review.
- Banjo plays 12-TET only (no fine-pitch command): tunings sound at their nearest notes. Absolute (fixed-note)
  arpeggio steps, Banjo's master volume/fades and its sound-effect queue are not exposed. NTSC only: Banjo's tables are
  for 3.58 MHz and a PAL game lowers speeds by one itself.
- Banjo 7aaf837's OPLL volume write tests the extra macro's type inverted (`volume_change.inc`): an FM instrument
  with a pitch or patch macro would corrupt its voice register, so FM voices offer volume and arpeggio envelopes only.
  Proposed: report upstream; enable FM pitch envelopes when fixed.

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
  drives them. The shared `NesS5b` inspector reports ENV/NOISE ownership in Pattern lane tags and inspector lines
  for both systems, and validation warns when sounds compete for the shared generators. Review their clarity.
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
- MMC5 PCM is played by Thor Tracker's software lane, not by the pinned engine and not by an invented hardware timer:
  open questions are the 256 KiB / 64-sample budget, the −48..+24 pitch range, a delay longer than its row being forced
  at the next row, and the lower MMC5/N163 output level from the full-span, two-filter budget (about 14.1/10.5 dB
  below plain NES at equal 2A03 content). Cuts continue across rows unless a new note/OFF cancels them.
  Native/JVM verification and human listening are tracked separately; no listening pass is claimed.
- MMC5 mixing: the two pulses at the 2A03 pulses' level (NESdev: "equivalent in volume") with inverted polarity, the
  240 Hz envelope/length clock at CPU ÷ 7424 (NESdev: from die images, not bench-confirmed). No hardware or
  reference-emulator comparison yet; listening may ask for a different balance.

## Master System / Game Gear (Banjo)

Driver: the Banjo sound driver (joffb/banjo `7aaf837`, MIT, `third_party/banjo` unmodified; checked against upstream
by `tools/audio/prepare_sms.py`), a real tracker-format SMS driver with SN76489, Game Gear stereo and YM2413
(including rhythm mode) support, used by homebrew through WLA-DX and devkitSMS/GBDK. Upstream's README and
`format_spec.md` disagree with the assembly; the assembly (`banjo_defines_wladx.inc`, `process_new_line.inc`, the
macro routines) is the authority and json2sms.py the independent export reference.

- **Systems** (`system/sms`): `SmsSystem` per `SmsLayout` — PSG (Square 1-3, Noise), Game Gear (same + `8xy` pan),
  FM (+ FM 1-9), FM rhythm (+ FM 1-6, Bass drum, Snare, Tom, Cymbal, Hi-hat), channels in Banjo's order. One engine
  id `sms-banjo`; the image says the layout. 64 slots over one numbering, one bank per kind (PSG sounds, FM voices,
  drum kits). Ticks: 59.92 Hz. Notes: PSG layouts A-2..B-8, FM layouts C-0..B-8 (FM and drums up to B-7).
- **Instruments** (`SmsSound`): PSG sounds = Banjo macros (volume with loop/release, arpeggio, and one extra macro:
  pitch in 1/128 semitone or the noise mode per tick); FM voices = built-in instrument 1-15 or the one custom voice
  (OPLL registers as fields, ROM voices as emu2413 measured them for starting points) + volume/arpeggio; drum kits =
  pitched by notes or three fixed pitches. Presets per layout fill new songs.
- **Effects** (up to 4 columns): `0xy 1xx 2xx 3xx 4xy 8xy(GG) Bxx Cxx D00 Fxx Gxx Lxx Nxy Oxx Sxx`, mapped to Banjo
  commands; arpeggio/slides/vibrato share Banjo's one slot; speeds apply from the next row; `Oxx` sets odd rows.
- **Lowering** (`backend/SmsLowering`, `BanjoSong`): Banjo song data; pattern length = longest order rounded to even;
  explicit order jumps for short orders and the loop; per (order, channel) tracks deduplicated; OFF = macro release or
  note off by the instrument in effect; `0xy` per note (auto `ARPEGGIO_OFF`); instrument rows without a note load with
  the next note. Differences from json2sms 7aaf837 (documented in AUDIO_ENGINE.md): arpeggio/extra macro loop index + 2,
  first row's tic = speed + 1 via a channel-init callback (the driver overwrites the header counter).
- **Exports**: WLA-DX `.asm` and SDAS `.s` Banjo song sources (genuine driver data, first-row initialization and two-chip call stubs), VGM 1.51
  through the native machine (`SmsExport.renderer`, JNI `ChipAudio.nativeSmsVgm`, Deck `DeckSmsVgm`).
- **Engine**: `sms/sms_machine.c` — Banjo on z80.h, our SN76489 (`ym/ym_psg.c`, plus Game Gear stereo), emu2413
  1.6.4 (MIT, chosen over ymfm's OPLL: plain C like the machine, per-rhythm-drum masks, proven OPLL accuracy).
- **Validation**: Banjo's limits as errors (note delay/cut within the row, D00 only, kinds per channel, FM ≤ B-7, data
  ≤ 32 KB, ≤ 255 orders); warnings for two custom FM voices at once, Square 3 under pitched noise, low PSG/noise notes,
  two pitch effects on a row, songs over one 16 KB bank.
- **Editor**: `SmsInstrumentView`/`SmsInstrumentPainter` on the shared FM painter and NES envelope plot: PSG pages
  Volume/Arpeggio/Pitch/Noise; FM pages Voice (instrument, modulator/carrier grid, routing and envelope sketch),
  Volume, Arpeggio; Kit page. Demo "Harbour Lights" arranged per layout.
- **Tests**: `SmsLoweringTest`, `SmsModelTest`, `SmsEditorTest`, native `sms_machine_test.c` (hand-built images).

## Philips SAA1099 (SAM Coupé, Creative Music System)

Roadmap step 7 (more chips): the Philips SAA1099 as the stock SAM Coupé's one chip and the two chips of Creative's
Music System / Game Blaster, two systems of one family ("Philips SAA1099…" in New and Load demo). The contract is
`docs/AUDIO_ENGINE.md`, "SAA1099". Decisions (made on the user's behalf; see Open questions):

- **The chips alone, two stock machines.** `"sam-saa1099"` = SAM Coupé (one SAA1099 at 8 MHz, six lanes, 50 Hz
  ticks), `"cms-saa1099"` = Creative Music System / Game Blaster (two SAA1099s at 7159090 Hz, twelve lanes, summed into
  one stereo output, 60 Hz ticks). Not the machines: no Z80/x86 emulation, no SAM DAC or Sound Blaster DSP (the Sound
  Blaster 1.0's optional C/MS chips are the same CMS pair; no other boards or stereo mods).
- **Own player + chip core + VGM**, the OPL3/SCC precedent: E-Tracker and the CMS drivers are not free and no driver
  is a standard. Songs export as VGM (SAA1099 at header 0xC8, dual-chip bit 30 for the CMS, `0xBD` with bit 7 for chip
  B) and WAV; no SAM or DOS executable is claimed.
- **Core: a clock-exact C port of SAASound's model** (BSD-3-Clause, notice kept): SAASound has been revised against
  recordings of real chips (envelope buffering, PDM levels, the LFSR) and follows the data sheet's routing (envelope 0
  on channel 2 only, envelope 1 on channel 5 only), which MAME does not (it applies each envelope to three channels and
  resets envelopes on every write); libvgm's Valley Bell core is close but its licence is unstated in the file and it
  disagrees with the data sheet on the preset noise clocks. The port renders output-change events into blip_buf and
  advances silently with identical state, so seeking is chip-exact.
- **Shared resources, honestly.** Each chip's two noise generators serve lanes 1-3 and 4-6: an instrument's noise clock
  claims its triple's generator (latest claim wins); the song check warns where lanes of a triple sound noise with
  different clocks at once; lane headers and the inspector show each generator's clock and owner. The two hardware
  envelopes exist only on lanes 3 and 6; their internal clock is lane 2's / 5's tone generator, shown on the lane
  headers and in the instrument view, never replaced by a hidden voice. `Yxx` (sync/reset, sound off) is per chip.
- **Instruments** are FamiStudio-style envelopes run by the player (volume with loop and release, arpeggio, pitch
  absolute or relative, and a **mixer envelope**: off/tone/noise/tone + noise per tick), plus the instrument's noise
  clock, stereo pan (left and right levels 0-15: the chip's twelve amplitude controllers) and an optional **hardware
  envelope** (shape, 4- or 3-bit resolution, inverted right, internal or external clock with a rate in ticks, retrigger
  per note). Envelopes reuse `NesEnvelope`. 64 slots, `null` = empty.
- **Effects**: the SCC's movement/sequencing set (`0 1 2 3 4 A B C D F G P S`) plus `8xy` pan, `Exx` hardware envelope
  control (lanes 3 and 6), `Mxx` mixer, `Nxx` noise clock and `Yxx` sync/sound. No chord effect.
- **Pitch**: true pitch through two 768-step-per-octave divider tables (one per clock); A-4 = 440.14 Hz (SAM), 440.40
  Hz (CMS); every note within 3.4 cents. Notes B-0..A#8 (SAM) and A-0..G#8 (CMS), the chips' real ranges.

### SAA1099 model, lowering and exports

- **Systems** (`system/saa`): `Saa1099System(variant)` with `SamCoupeSystem` (`"sam-saa1099"`, "SAM Coupé (SAA1099)")
  and `CmsSystem` (`"cms-saa1099"`, "Creative Music System (2× SAA1099)"), family "Philips SAA1099"; channels `SAA 1`-
  `SAA 6` or `A1`-`A6`, `B1`-`B6` (`ChannelKind.PULSE`, tonal; four effect columns each), notes 11..106 / 9..104 with
  FamiTracker names, one
  bank of 64 slots, 50 or 60 ticks per second × multispeed (Song view: Multispeed 1-8), at most 256 orders, OFF =
  release (or cut without a release point), fine pitch `Pxx`. Constants come from `backend/SaaPlayerImage` (generated).
  `backendVersion` 1.
- **Chip data** (`SaaSound`): `variant` (`"sam"` / `"cms"`, matching the backend), 64 nullable `SaaInstrument`s
  (`name`, `volume`, `arpeggio`, `pitch`, `mixer` `NesEnvelope`s, `pitchRelative`, `noise` 0..3, `panLeft`,
  `panRight` 0..15, `envelope`: null or `SaaHardwareEnvelope(shape 0..7, threeBit, invertRight, external, rate 1..255,
  retrigger)`), `multispeed` 1..8. A project whose backend and `variant` disagree is a structural error. A new
  instrument has volume `[15]`, no other envelopes (tone), noise 0, pan 15/15 and no hardware envelope (when one is
  switched on: shape 3, 4-bit, not inverted, internal, rate 1, retrigger; `rate` stays 1..255 and is stored as 0 for
  an internal clock). New songs get presets in slots 01-12 (Lead, Square bass, Pluck, Stereo pad, Echo lead (its
  volume envelope carries the repeats), Kick, Snare, Hat, Noise sweep, Buzz bass (lane 3: repetitive decay clocked by
  lane 2), Slow swell (external triangle), Mirror (inverted right)).
- **Lowering** (`backend/SaaLowering`): the canonical layout; `dataSize` for the validator.
- **Validation** (`SaaValidator`): every `sa_load_image` check as a message naming pattern, channel and row, plus an
  empty envelope that still carries a loop or release point. Warnings (a row-level walk of straight play, `SaaWalk`,
  shared with the shared-state display): two lanes of one triple sounding noise with different clocks at once (once
  per stretch: order, row, lanes, clocks and the claimant); a hardware envelope (instrument or `Exx`) on a lane without
  one; an internal-clock envelope while its clock lane has never set a pitch; `Y02`/`Y03` still in force at the loop
  point; `Nxx`/`Mxx`/`8xy`/`Exx` where nothing sounds; `Gxx` beyond the speed; an order's first note without an
  instrument.
- **Shared state** (`sharedState`, `SaaSharedState`): on the lane that claimed a noise generator last, `N0:31k` /
  `N1:T4` (generator, clock: 31k, 16k, 7.8k on the SAM, 28k, 14k, 7.0k on the CMS, or `T1`/`T4` = lane 1's or 4's
  tone); on lanes 3 and 6 `E←2` / `E←5` (envelope clocked by that lane) or `E ext`; `SYNC` / `OFF` replace a chip's
  tags while `Y02` / `Y03` holds it. The inspector explains each. Walk details: `E00` on a lane without an envelope is
  no warning; `Y02`/`Y03` in force at the end is excused when the loop order's row 0 restores that chip; volume slides
  do not count when deciding whether a lane sounds noise.
- **Exports**: `.vgm` (`SaaVgm`, the native renderer registered by each host) and `.wav` (the generic path).
- **Demos**: "Coupé Lights" (SAM; E minor, 125 BPM): SAA 2's plucked line clocks the buzz bass on SAA 3 (a repetitive
  hardware envelope with the mixer off, so the envelope is the waveform, three octaves below SAA 2), kick, snare and
  hats on SAA 4-6 sharing one noise clock, stereo leads on SAA 1 (an echo lead swept with `8xy`); "Blaster Skyline"
  (CMS; D major, a 7/6-tick groove at 60 Hz = 138.5 BPM, the nearest to 140 without faster instrument envelopes):
  twelve lanes, both chips' envelopes (buzz bass, mirror, external slow swell), a lead doubled on B1/B2 restarted in
  phase by `Y01`, a clock-3 noise riser. Both without errors or warnings; `SaaDemoSong.shortest(variant)` is the
  one-row song the WAV test renders. `exportSamples` writes `coupe-lights.thor`, `blaster-skyline.thor`,
  `saa-sam-demo.saaimg`, `saa-cms-demo.saaimg`, `saa-sam-shortest.saaimg` and `saa-cms-shortest.saaimg`.
- **Tests**: `SaaModelTest` (round trips of both variants, validation and warnings, shared noise and envelope rules,
  lowering bytes and dedup, arrangement, fine pitch, audition problems, shared state, demos), `SaaGoldenTest` (the
  reference builder's songs rebuilt from the model lower to the same 65536 bytes) and `SaaEditorTest`.

### SAA1099 editor

- **Instrument view** (`SaaInstrumentView`, `SaaInstrumentPainter`): the 64-slot bank, then rows Slot, Name, Preset,
  Listen on, Pan L | Pan R, Noise clock, Hardware envelope (Off/On), Shape (drawn), Resolution | Inverted right, Clock
  (Internal: lane 2/5 / External) | Rate, Retrigger (the envelope rows only while the sound has one), Page, Length,
  Loop, Release (volume), Mode (pitch), Steps; L1/R1 switch the Volume, Arpeggio, Pitch and Mixer pages
  (`EnvelopePlot`, given the SCC slice's value-range overload, byte-identical to branch `scc-systems`). Rows that a
  lane cannot use say so (the hardware envelope on lanes other than 3 and 6, the noise clock without noise in the
  mixer). No Wave view.
- **Help**: the effects page from `SaaRules`, the Instrument and Envelopes sections and a "SAA1099 shared noise and
  envelopes" section.

- SAA1099 noise from a tone generator: the data sheet says the controlling generator produces no tone; SAASound, MAME
  and libvgm keep it audible and so do we. A recording would settle it.
- SAA1099 preset noise clocks: the data sheet's 31.25/15.6/7.8 kHz (SAASound, MAME) rather than libvgm's twice those;
  frequency writes latched at the half-cycle end (SAASound, MAME) rather than at once (libvgm). Exported VGMs played by
  libvgm differ there.
- SAA1099 tick rates: 50 Hz for the SAM Coupé (its frame interrupt), 60 Hz for the CMS (a PC timer is free; our
  choice). Review.
- SAA1099 one-shot external envelopes hold their last step (single attack holds full level) because the final clock
  would restart them under VGM's address-plus-data writes; review if a "true end" is wanted.
- SAA1099 mixing: SAM ×10, CMS ×5 into the resampler from SAASound's 0..480 output levels; no comparison with a
  recorded SAM Coupé or Game Blaster yet.
