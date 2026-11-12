# Unified GBA PCM + PSG: feasibility spike

2026-11-12. **Archived broad research, not implemented capability.**

**Current decision:** see the [measured community S3M / GBT + Maxmod spike](GBA_S3M_SPIKE.md).
The user subsequently required an existing community export path without a
Thor-specific format/runtime fork or coupling to their game. The custom companion,
bundle and game-first recommendations below are therefore **superseded, not approved
implementation direction**. The hardware/runtime source findings remain useful context.

## Earlier recommendation (superseded)

Keep **one GBA project, one pattern/order timeline and one transport**, with the existing eight sample lanes plus four typed hardware lanes: Pulse 1, Pulse 2, Wave and Noise. Keep Maxmod for PCM; add a synchronized PSG companion and export the complete composition as one game-integration bundle. One project/export action does not require one standard music file internally.

This is feasible in principle: GBT Player already demonstrates unified composition split into Maxmod PCM and real GBA PSG playback. Butano already supports both hardware paths simultaneously. The unresolved engineering is the shared transport, audible synchronization, exact consumer-runtime compatibility and preview fidelity—not whether the hardware can combine them.

**First implementation milestone should be a small combined-audio ROM using the actual Butano runtime, before adding the editor or promising a shipping date.** Do not replace Maxmod, switch all music to MIDI/S3M, or implement a second PCM mixer for this feature.

## Evidence and an important runtime correction

Examined tracker source: `ca619072425f85eb83738a5a1232939c916edf64` (0.45.0).
Examined game's Butano submodule: `7443b6c389c086f6cf3222674366917e8e8fc457`.
The game checkout is actively being developed and was inspected read-only.

- `gba-survivors/Makefile:123-145` selects `AUDIOBACKEND=maxmod`, with `DMGAUDIOBACKEND=null`. PSG music is currently disabled, not unavailable in Butano.
- Butano's `tools/sources_setup.mak:40-47` compiles its bundled Maxmod C/assembly sources. Its optional default DMG backend independently compiles GBT/advgm.
- `butano_dka.mak:12-16` enables `MM_MMUTIL_DEVKITPRO_COMPAT` and uses installed `mmutil`. **Converter compatibility is not runtime-version identity.**
- The game's bundled Maxmod has `MMCB_SONGTICK`; devkitPro Maxmod 1.0.15 does not. Its `src/core/mas.c:647-652` also resets modulation phase on applicable note triggers. Thor Tracker's compatibility adaptation deliberately retains legacy vibrato phase.
- The released 0.45.0 converter/bank checks and 8,139 channel-state observations establish parity with **mmutil 1.10.1 / devkitPro Maxmod 1.0.15**, not with every newer Maxmod runtime or this game's bundled implementation. The checks remain valid for that named target; they must not be generalized to the current game.

Before shipping a combined backend, choose and pin a supported consumer runtime. Prefer a versioned integration based on the game's C runtime, with explicitly tested compatibility adaptations if retaining existing Thor Tracker PCM semantics. Preview and game must execute matching semantics. Do not silently change old songs' sound or silently describe arbitrary system Maxmod installations as supported.

## What the hardware provides

| Lanes | Hardware path | Relevant restrictions |
| --- | --- | --- |
| Sample 1–8 | Maxmod software voices mixed into Direct Sound A/B | CPU/RAM cost, mixer rate and SFX compete with gameplay |
| Pulse 1 | GBA sound channel 1 | Four duty settings, hardware envelope/length and pitch sweep |
| Pulse 2 | GBA sound channel 2 | Pulse duties/envelope/length; no channel-1 sweep unit |
| Wave | GBA sound channel 3 | Four-bit wave RAM; GBA-specific 32/64-sample banking and discrete volume settings |
| Noise | GBA sound channel 4 | LFSR/divider-based noise, not arbitrary chromatic sample playback |

These are **12 composition lanes, not 12 hardware oscillators**. PSG stereo routing is left/right/both/off, not PCM's arbitrary pan law. PSG master level, routing, Direct Sound controls and final output headroom share hardware resources. Hardware-generated tones avoid per-sample software synthesis, but sequencing/register updates are not free. Adding four PSG lanes does not make an eight-voice PCM mixer cheaper; moving suitable musical parts from PCM to PSG can reduce active PCM work.

The current Game Boy editor and musical controls are useful UI precedents, but its GB CPU driver is not a GBA-native driver. GBA wave banking, mixing and timing also mean that a plain Game Boy APU preview is not automatically faithful.

## Proposed composition and export contract

All items in this section are proposed, not existing APIs.

### One editable document

- Extend the GBA backend rather than asking the composer to maintain linked sample and PSG songs.
- Keep the current eight PCM lane indices and sample/instrument identities. A backend-version migration appends four empty PSG lanes to old projects without rewriting their musical content.
- Add typed PSG instruments/banks; preserve the PCM sample library, keymaps and envelopes. Lane selection determines the valid instruments and controls.
- Pattern/order editing, tempo, transport, loop selection, save/load, Undo, audition, mute and WAV recording operate on the complete composition.
- Show hardware-appropriate Sound/Advanced controls: duty and envelope for pulses, sweep only where available, waveform editing for Wave, and meaningful noise divider/width controls. Reject unsupported combinations rather than substituting PCM approximations.
- Keep one effect column initially. Extra lanes must not become an excuse to broaden effect scope at the same time.

### One complete export action

A proposed **Export GBA bundle** produces a versioned package containing:

- The existing eight-channel PCM `.xm`, still converted by the supported `mmutil` path.
- PSG instrument/pattern data indexed against the same canonical arrangement.
- A manifest identifying format revision, exact runtime/converter compatibility, mixer profile, resource requirements and checksums tying both parts together.
- Generated identifiers and integration instructions for a separately versioned runtime/Butano adapter; a minimal example belongs with that adapter.

The game developer installs the supported adapter once, imports the bundle, and uses **one combined play/stop/pause operation**. They do not manually start two unrelated songs or keep two exports in sync. Names and packaging remain design choices; there is no existing `.gba-song` format or `play_gba_song()` API being claimed here.

Plain `.xm` export remains lossless for sample-only projects. A mixed song must not silently export just its samples: either refuse that export or explicitly label and confirm a samples-only export. The complete bundle is the default hardware-target path for mixed songs. WAV remains a recording, not a substitute for independently playable hardware channels.

### Control-flow ownership must be explicit

Maxmod should own speed/BPM, order jumps, row breaks, pattern loops and delays. PSG must follow the actual processed ticks, not independently interpret a second copy of global flow commands.

There is a concrete export constraint: an eight-channel XM may already use all eight effect cells on a row. A global command entered in a ninth/PSG lane cannot always be moved into it without losing another effect. **Conservative initial rule: global flow/tempo commands remain on PCM lanes, while PSG lanes expose local sound effects.** Even a PSG-only composition can use an otherwise silent PCM lane for global commands. Reject unsupported placement visibly; do not invent hidden lanes or silently drop effects. A dedicated global-command lane would be a separate design change.

A companion interpreter must distinguish a newly decoded row from repeated tick-zero callbacks during pattern delay. Order/row/tick coordinates alone can repeat legitimately during loops or delay. Include a tick serial, row-start/decode information and song generation in the integration hook, as well as the processed position. Arrangement pruning and parked orders must map identically in both outputs.

## Synchronization: the main technical risk

### What does not work reliably

1. Starting ordinary XM and GBT/VGM together and hoping their clocks match.
2. Updating PSG once per `bn::core::update()` independently of Maxmod.
3. Reanchoring only at row boundaries: this misses intra-row effects, note delays and cuts.
4. Treating a render-time tick callback as the time audio leaves the DAC.

GBT Player's combined example explicitly warns about drift. It runs `mmFrame()` and then advances GBT up to five times until order/row/tick agree. This is a useful existing **logical synchronization** baseline, not proof of sample-aligned output. It demonstrates four PCM voices, not the requested eight plus game sound effects.

### What the inspected runtime actually does

Butano's bundled Maxmod `src/gba/main_gba.c:148-207` divides a rendered frame into sample spans, processing a tracker tick whenever the next boundary is reached. Several ticks can be processed in one CPU call. `src/core/mas.c:1882-1885` emits `MMCB_SONGTICK` after transport advancement, using a payload captured for the processed tick. Position getters at that point can describe the next position.

Those calls prepare buffered PCM. PSG register writes, by contrast, affect hardware immediately. Therefore direct register writes from that callback can lead the corresponding PCM and bunch distinct musical events together. **The exact FIFO/buffer latency has not been measured in this spike.**

Butano also owns the single Maxmod event handler (`hw/src/bn_hw_audio_maxmod.cpp.h:203-218,284-311`), installing/removing it for song messages. A companion cannot safely take it over behind Butano's back. The adapter must multiplex this deliberately. Callback execution follows the selected audio commit mode: ordinary core update or VBlank IRQ; do not allocate, block or reenter transport from it.

### Recommended timing experiment

Add a narrowly scoped render-timeline hook to the pinned runtime, reporting processed transport state plus output-buffer generation and sample offset. In the examined C runtime, `mm_mixlen - remaining_samples` around `mppProcessTickMain()` in `main_gba.c:163-172` identifies the render-buffer offset. It does **not** by itself identify the audible deadline; map that offset through the actual DMA/FIFO playback timeline.

Generate bounded PSG commands into a fixed-capacity queue during rendering. Consume commands against the corresponding playback timeline, invalidating old generations on stop/seek/restart. Share the PSG sequencing logic between tracker preview and the GBA runtime; only the register-write target differs.

Two precision levels should be compared in the small ROM:

- **Frame-quantized, buffer-aligned delivery:** simpler, no additional timer requirement. Measure onset skew and explicitly accept its granularity before using it as the production contract. This may be a sensible practical middle ground; it is not sample-exact synchronization.
- **Sample-clock deadlines:** a Timer 1 cascade from Maxmod's Timer 0 is a candidate, not a demonstrated solution. It needs FIFO phase calibration, IRQ-latency measurements and resource reservation. Long non-nestable VBlank work can delay delivery even with correctly calculated timestamps.

Do not commit to the more complex scheduler before measuring whether the simpler contract meets musical expectations. Do not call either solution drift-free or hardware-faithful before measuring long playback, tempo changes and control flow.

## Hardware ownership and integration

| Resource | Existing owner / proposed rule |
| --- | --- |
| Timer 0, DMA 1/2, FIFO A/B | Maxmod; retain ownership |
| VBlank | Butano core and Maxmod segment servicing; integrate, do not replace handlers |
| Timers 2/3 | Butano timing/CPU accounting; not spare |
| Timer 1 | Conflicts with Butano link support; reserve explicitly if using a precise scheduler |
| PSG channel registers and wave RAM | One companion player; do not also advance ordinary GBT/advgm music |
| `SOUNDCNT_L` | PSG routing/master settings under coordinated ownership |
| `SOUNDCNT_H` | Shared PCM routing and PSG ratio: masked writes only |
| `SOUNDCNT_X` | Shared master power: never disable it merely to mute PSG |

Maxmod initialization writes `SOUNDCNT_H=0x9A0C`; initialize the combined mix policy in a defined order. Preserve PCM FIFO/timer configuration when changing PSG gain. Reserve headroom in preview and export; summing two independently normalized outputs is not faithful hardware mixing.

For a custom companion, keep the game's ordinary DMG backend disabled and expose integration through the adapter. If reusing GBT, prevent Butano's normal per-frame updater from double-stepping it. Existing sample SFX can remain in Maxmod, but music/SFX voice allocation must be explicit. Initial PSG channel ownership should be exclusive to music; PSG SFX stealing/restoration is separate scope.

Combined pause/resume must account for already-buffered PCM and autonomous hardware envelopes. Seeking must reconstruct relevant instrument/effect state or declare restart-only semantics; changing two byte pointers is not enough. Editor seek/audition can require more reconstruction than the first game's API, but cannot be falsely presented as equivalent.

## Reuse options assessed

| Option | Finding | Decision |
| --- | --- | --- |
| Maxmod + GBT | MIT PSG player and existing combined-composition example; limited PSG effects and synchronization assumptions | Best precedent and first proof baseline; reuse only if its feature semantics fit |
| Maxmod + small PSG companion | Preserves existing PCM; requires a bounded interpreter, timing integration and hardware-aware preview | Recommended architectural direction; use existing permissive code where appropriate rather than a new general-purpose music engine |
| Stock Butano advgm | Real PSG register playback, 0BSD; its converter maps each supported wait to one VBlank, including arbitrary-duration waits | Not a drop-in arbitrary-tempo companion; useful register/wave-handling reference |
| Furnace GBA/MinMod | Authoring exists, but the inspected exporter registry has no GBA ROM exporter; FCS is not a supplied game runtime | Reference, not an established complete export path |
| MP2K/m4a | Real combined PCM/PSG, but different sequence/instrument/effect semantics; decompiled commercial runtime has no demonstrated permissive grant | Do not migrate or copy it for this feature |
| Kitty Advance | MIT MIDI-based combined runtime, explicitly WIP, with incomplete wave handling | Monitor, not the initial production dependency |
| Rendering PSG to samples | Can sound similar and use existing XM | Does not use the actual PSG hardware or provide four additional hardware voices |

The permissively licensed `mid2agb` converter does not license Nintendo's entire m4a runtime. Similarly, a desktop MP2K player or a PCM mixer replacement is not a complete GBA game driver.

## Licensing decision before implementation

Thor Tracker stays **GPL-3.0-or-later**, as requested. Musical output is not automatically GPL simply because a GPL tool generated it; embedded third-party samples and copied runtime code retain their own obligations.

GBT is MIT, advgm is 0BSD and Maxmod has a permissive notice-based license. New game-linked glue needs an explicit policy: GPL-only glue can impose GPL obligations on games linking it. **Recommend a separately licensed permissive runtime/adapter if arbitrary homebrew games are intended, while keeping the tracker GPL-3.0-or-later. This is a recommendation requiring approval, not a license change made by this spike.** Retain upstream notices and do not copy GPL tracker implementation into that runtime without compatible rights.

## Implementation gates and relative complexity

1. **Pin and compare the actual consumer runtime.** Run the existing PCM fixtures against Butano's bundled runtime, including modulation, loops, tempo and envelopes. Resolve differences explicitly before adding PSG.
2. **Small standalone Butano ROM.** Exercise aligned PCM/PSG note starts, four distinct PSG voices, eight PCM voices, pattern delays/loops/jumps, tempo extremes, pause/restart and simulated missed commits. Compare the logical-lockstep baseline with buffer-aware scheduling. No edits to the active game checkout are needed.
3. **Choose the timing/feature contract from measurements.** Record maximum and distribution of onset skew, long-run drift, missed deadlines, queue bounds and actual GBA CPU/RAM costs with SFX. An emulator is useful evidence, not physical-GBA listening proof.
4. **Implement the shared data model and complete export bundle.** Preserve sample-only projects, enforce lane/global-effect rules, pin runtime compatibility and prevent mismatched sidecars.
5. **Implement combined preview and instrument UI.** Reuse navigation, Undo and common controls; use a GBA-aware PSG implementation, including wave-bank behavior and output-level relationships. Expand native channel/mute/image bookkeeping beyond the current eight-bit masks.
6. **Integrate into a temporary copy/minimal reproduction of the game**, then its active branch only with coordinated ownership. Verify that the actual exported package, not hand-authored equivalent data, produces the measured result.

Relative complexity: model/UI reuse is moderate; packaging/Butano integration is moderate; synchronization, consumer-runtime parity and hardware-aware preview are the highest-risk work. The tiny PSG write loop is not the expensive part of the project. No defensible calendar estimate or game CPU guarantee follows from this source inspection.

## Sources

Primary sources, pinned where inspected:

- [GBT combined-Maxmod workflow](https://github.com/AntonioND/gbt-player/blob/130e48662da2a91140a10d5af3934df90792de21/gba/examples/combined_maxmod/readme.rst) and [synchronization example](https://github.com/AntonioND/gbt-player/blob/130e48662da2a91140a10d5af3934df90792de21/gba/examples/combined_maxmod/source/main.c).
- [GBT PSG instrument/effect contract](https://github.com/AntonioND/gbt-player/blob/130e48662da2a91140a10d5af3934df90792de21/gba/s3m2gbt/readme.rst).
- [Butano source selection](https://github.com/GValiente/butano/blob/7443b6c389c086f6cf3222674366917e8e8fc457/butano/tools/sources_setup.mak), [converter compatibility flag](https://github.com/GValiente/butano/blob/7443b6c389c086f6cf3222674366917e8e8fc457/butano/butano_dka.mak), [Maxmod frame rendering](https://github.com/GValiente/butano/blob/7443b6c389c086f6cf3222674366917e8e8fc457/butano/hw/3rd_party/maxmod/src/gba/main_gba.c), [sequencer](https://github.com/GValiente/butano/blob/7443b6c389c086f6cf3222674366917e8e8fc457/butano/hw/3rd_party/maxmod/src/core/mas.c), [mixer](https://github.com/GValiente/butano/blob/7443b6c389c086f6cf3222674366917e8e8fc457/butano/hw/3rd_party/maxmod/src/gba/mixer.c), and [event-handler ownership](https://github.com/GValiente/butano/blob/7443b6c389c086f6cf3222674366917e8e8fc457/butano/hw/src/bn_hw_audio_maxmod.cpp.h).
- [Butano advgm converter](https://github.com/GValiente/butano/blob/7443b6c389c086f6cf3222674366917e8e8fc457/butano/tools/advgm/advgm_converter.py) and [runtime](https://github.com/GValiente/butano/blob/7443b6c389c086f6cf3222674366917e8e8fc457/butano/hw/3rd_party/advgm/src/advgm.c).
- [Furnace GBA documentation](https://github.com/tildearrow/furnace/blob/11e8805723c5793a8ab95554fd502060ee65ada5/doc/7-systems/gba.md), [export registry](https://github.com/tildearrow/furnace/blob/11e8805723c5793a8ab95554fd502060ee65ada5/src/engine/exportDef.cpp), and [export-format documentation](https://github.com/tildearrow/furnace/blob/11e8805723c5793a8ab95554fd502060ee65ada5/doc/2-interface/export.md).
- [m4a implementation inspected for capabilities, not licensed for reuse here](https://github.com/pret/pokeemerald/blob/731ad5bfd6e6f265508d0efcca0ba42f9dcf5881/src/m4a.c) and [separate converter license](https://github.com/pret/pokeemerald/blob/731ad5bfd6e6f265508d0efcca0ba42f9dcf5881/tools/mid2agb/LICENSE).
- [Kitty Advance status](https://codeberg.org/roses/kitty/src/commit/af846f730cf60fa0b5eec5fef729dd8147bac4fe/README.md).
- Hardware/emulator engineering reference: [Game Boy Advance Audio](https://jsgroth.dev/blog/posts/gba-audio/), including GBA-specific wave RAM, routing and mixing behavior. The author's detailed mixing model carries its own accuracy caveats; it is not a replacement for measured parity.

**Verification performed for this spike:** source inspection, revision-specific primary documentation and comparison of the actual build-selected runtime against the released target. No new driver, ROM, audio timing measurement, game modification or GBA hardware test was performed. The sound-pack transfer requested alongside this research is separate from these findings.
