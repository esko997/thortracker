# Thor Tracker — MVP plan

Status: MVP implemented and exercised on the Thor, an Android emulator and the isolated target-game toolchain. APK and two project/UGE examples are delivered. See docs/VERIFICATION.md for evidence and limits: human listening, ergonomics and physical button-to-speaker latency remain unverified; the current Thor mixer path measures approximately 100 ms output latency.

## Goal

A useful, controller-first Android GB/GBC music tracker for the AYN Thor. Compose complete songs, audition and replay edits quickly, use both displays freely, save projects safely, and export `.uge` songs for hUGEDriver-based games, including gbc-puzzler.

The first backend is explicitly GB/hUGE. Backend extensibility means modular software, not a universal instrument model or lossless song conversion. A project belongs to one backend. GBA and Mega Drive are examples of possible future backends, not MVP targets.

## Roadmap (2026-11-08, after 0.13.0)

Sound families: Game Boy, NES + DPCM and expansions, C64 SID, OPL3, Mega Drive, Sega 8-bit, AY/YM computers and PC Engine. VRC6, VRC7, N163, Sunsoft 5B, FDS, MMC5 and EPSM extend the NES family.
Order below is the current intent; design notes for the chip work live in `docs/BACKENDS.md`.

**Current priority (0.41.0/code 55 candidate, 2026-11-10):** finish soft-launch workflow
hardening and review the isolated phone touch prototype, not another backend. TIA NTSC/PAL
four-column support and Android interruption fixes have candidate evidence in
`docs/VERIFICATION.md`; official publication and new Deck/Brick verification are still pending.
The phone calibration target is the **Galaxy Fold8**. DMG is a touch-functional testbed, not
evidence that the phone layout or thumb reach is calibrated.

SAA1099 is released as **0.34.0/code 47**, retaining VIC/TED and all earlier systems. Native/reference,
JVM, reserved-device frontend and published AppImage checks pass; the DMG picker retry and exact
artifact evidence are recorded in `docs/VERIFICATION.md`.

VSU is published as **0.35.0/code 48** from main `aa3149e`. Its 956 JVM tests, 594 native checks,
seven-rate seek/headroom checks and all three reserved-device candidate scenarios pass. All four
publication jobs, the actual official AppImage and official APK acceptance on DMG pass.

**0.36.0/code 49** is published from main `506c424`, retaining both WonderSwan variants, VSU,
both SAA variants and all earlier engines. Its 995 JVM tests, 693 WonderSwan native checks,
2,796 WAV checks, independent references and all three reserved-device candidate scenarios pass.
All four publication jobs, both official AppImage demos and official APK scenarios on DMG/Konkr pass.
All local Kotlin/Native linking, including debug/test links, remains prohibited: use CI and exercise
its downloaded AppImage.

**0.38.0/code 52** publishes the Brick Hammer Pak and colour themes. **0.39.0/code 53** publishes
persistent zoom and the [documentation website](https://esko997.github.io/thortracker/).
Official Android acceptance on Nova/DMG/Konkr and actual AppImage checks pass; Thor/Deck receive
artifacts without interruption and the user's Brick session is left alone. Mixer ceilings and
shared swing remain design proposals; pattern-local mute remains explicitly deferred.

1. **Hardening pass** — fixes from the user's Thor testing of 0.11-0.13; tune the FM curated lists, presets and
   demo mixes by ear; review the open questions in `docs/BACKENDS.md`.
2. **WAV export** — implemented for all five systems in 0.14.0, with loop/fade options and disk-backed rendering.
   Help → About adds opt-in diagnostics and a bug-report link; no automatic upload.
3. **NES expansion chips** through the FamiStudio engine already in the loop: VRC6 (2 pulses + sawtooth, 0.15.0),
   VRC7 (six two-operator FM voices, 0.16.0), N163 (1-8 wavetable voices, 0.17.0), Sunsoft 5B
   (three squares with shared noise/envelope, 0.18.0) and FDS (one 64-sample wavetable voice with modulation,
   0.19.0) are implemented, with driver/native, desktop and reserved-handheld checks recorded in
   `docs/VERIFICATION.md`. N163's eight channel-count variants are grouped under NES → NES + N163….
   MMC5's two pulse voices and software-driven PCM lane shipped in 0.20.0.
   EPSM (YMF288/OPN3-L: three SSG voices, six FM voices and six rhythm drums) shipped
   in 0.21.0. Core/native checks, 19 permanent Android tests, 32 unchanged Game Boy
   views, Android/desktop export parity and Nova/DMG/Konkr production playback/audition
   checks passed. Its user-supplied rhythm ROM is never distributed.
   Next: Sega Master System / Game Gear and the SMS FM Sound Unit.
   Source-only worktrees do not count as verified releases.
4. **Sega Master System / Game Gear** — integrated for 0.22.0: the real Banjo driver on a Z80, SN76489
   with Game Gear stereo, and the Japanese SMS FM Sound Unit (YM2413), with separate melodic/rhythm layouts.
   Native/reference, Android/desktop export parity, reserved-device playback and unchanged Game Boy
   checks passed; see `docs/VERIFICATION.md`. All four variants are grouped under **Sega 8-bit…**.
5. **Mega Drive extensions** — released in 0.23.0: three PCM lanes and sample kits, channel 3's independent
   operator pitches, and the real XGM2 driver with unpacked/compiled exports. Native/reference, exported-file
   driver playback, reserved-device edits/audition, WAV import and Game Boy parity passed; see `docs/VERIFICATION.md`
   for measured seek latency, adaptive-buffer underruns and the upstream converter limitation.
6. **L2+R2 third chord layer** — integrated and device-verified for 0.24.0 (keymap version 6).
   A binding is a button plus a modifier *set* (at most two of Select/L2/R2/L3), so L2+R2+A
   is one layer whichever trigger went down first. Routing: a press runs the chord of exactly the held set; failing
   that, if any subset of the held set binds the button (L2+A, R2+A while both are held) the press does nothing;
   otherwise the bare key acts. Presses keep the meaning of their DOWN through rolling releases; Android key-ups
   flagged canceled do not trigger taps or cursor advance; edits already made on press remain. Captures track
   held triggers correctly, including analog-trigger release on the Deck frontend. Older keymap files decode
   unchanged and gain the new defaults. See `docs/VERIFICATION.md` for device and two-pane evidence.
   - **Default choice for review:** L2+R2 + A/B/X/Y/↑/→/↓ show Song/Instrument/Sketch/Keyboard/Pattern/Wave/Help
     on the *other* display, mirroring the R2 view chords, without moving controller focus (one R3 → R2+x → R3
     sequence becomes one chord; e.g. Keyboard on the other screen beside the pattern being edited). With one
     display (Deck, single-screen Android) they show on it. L2+R2+← stays free. No existing chord moved.
7. **More chips** (unordered):
   - **AY-3-8910 / YM2149 (0.25.0)** — ZX Spectrum 128, MSX, Atari ST and Amstrad CPC:
     own player/core, shared noise and hardware-envelope ownership, buzzer/sync sounds, software envelopes,
     VGM/WAV and Copper Lane demos. Verified on Nova, DMG, Konkr and the SDL frontend; CPC WAV retains stereo,
     while standard AY VGM is mono.
   - **PC Engine HuC6280 (0.26.0)** — six 32-step wavetable channels, shared LFO, noise, stereo balance and timer-driven
     DDA samples; own player on Mednafen's PSG, controller wave/sample editors, VGM/WAV and Turbo Lanterns.
   - **Konami SCC / SCC+ (0.27.0)** — MSX wavetable cartridges, five channels:
     SCC (K051649, channels 4/5 share a wave) and SCC+ (K052539, five waves), own player on a port of MAME's k051649,
     VGM/WAV, wave/envelope editors and shared-wave inspector. Verified on Nova, DMG, Konkr and the SDL frontend.
   - YM2413 / OPLL — MSX-Music and the SMS FM unit: nine 2-op voices, 15 fixed presets + one custom.
   - YM2151 / OPM — arcade boards and the Sharp X68000: eight 4-op voices, one shared LFO, noise on channel 8, CSM.
     Integrated for **0.29.0** ("OPM FM (YM2151)", `"ym2151-opm"`, demo "Sharp Corners"):
     Thor Tracker's own player on ymfm at the X68000's 4 MHz, VGM/WAV export, native and independent-core
     comparison, Nova/DMG/Konkr and SDL verification complete. No human listening or original-hardware
     measurement claimed; `docs/VERIFICATION.md` records the evidence.
   - YM2203 / YM2608 (OPN / OPNA) — PC-88 and PC-98: FM + an AY-style SSG; the YM2608 adds stereo, the LFO, six
     rhythm drums and ADPCM-B samples.
     Released as **0.30.0/code 43** ("PC-88/98 OPN" and "PC-88/98 OPNA", demos "Night Express" and
     "Paper Lanterns"; Thor Tracker's own player on ymfm, VGM/WAV export, shared EPSM/OPNA rhythm firmware).
     Native/headroom/reference checks, integration suites, 32 unchanged Game Boy views, desktop playback,
     Nova/DMG/Konkr activity smokes, actual Android import/export pickers and legacy firmware reuse pass.
     Independent VGM playback also verifies the exported FM/SSG mix; evidence and limits are in `docs/VERIFICATION.md`.
   - POKEY — Atari 8-bit computers: 4 channels, distortion polynomials, high-pass, 16-bit pairing.
     Released as **0.31.0/code 44**, demo **Scanline Cruise**, with native volume-DAC samples,
     NTSC/PAL timing, four effect columns, VGM/WAV export and channel-ownership inspection.
     Native/reference comparisons, 831 JVM tests, 32 unchanged Game Boy views, all three reserved
     Android handhelds and SDL playback/audition pass, including the official APK/AppImage artifacts.
   - TIA — Atari 2600: two voices with odd, tuning-limited pitches.
     Released **0.32.0/code 45**: NTSC/PAL, Stella 7.0's TIA audio channel, honest AUDF quantization,
     WAV-only, Cartridge Dawn demos. Native/reference, Android controller/edit/export and SDL
     playback/audition checks pass, including the official APK/AppImage; Thor/Deck artifacts delivered.
   - VIC-20 VIC and Commodore 16/Plus4 TED — released **0.33.0/code 46**: "VIC-20 (VIC)" and
     "C16 / Plus/4 (TED)", our own player on VICE's VIC and plus4emu's TED sound, PAL/NTSC per song,
     shared master volume, TED D/A samples; project + WAV only. The audition/song-ordering race found
     during reserved-device testing is corrected and regression-tested in 0.34.0.
   - SAA1099 — released **0.34.0/code 47**: SAM Coupé (one chip, six voices) and Creative Music System
     (two chips, twelve voices), shared noise/envelope controls, VGM and WAV. Native/reference, JVM,
     Android device scenarios and the published AppImage pass; see the recorded DMG picker retry.
   - Virtual Boy VSU — released **0.35.0/code 48** (`vb-vsu`: own player on a Mednafen-derived core
     with the Sacred Tech Scroll's amendments, VGM + WAV export).
     956 JVM tests, 594 native checks, 2,622 WAV checks, independent references and all three reserved
     Android frontends pass, including official AppImage/DMG APK checks (`docs/VERIFICATION.md`).
   - WonderSwan and WonderSwan Color — released **0.36.0/code 49**: four wavetable channels
     (voice samples, sweep, noise), plus Color's Sound DMA and Hyper Voice. Own player on a C port
     of ares's APU, VGM/WAV export; Hyper Voice VGM is explicitly refused.
     995 JVM tests, 693 native checks, 2,796 WAV checks, independent references and all three reserved
     Android frontends pass, including official AppImage/DMG/Konkr APK checks (`docs/VERIFICATION.md`).
   - Not planned: sample-based chips (Amiga Paula, SNES SPC700, GBA DMA).
8. **Conversion between systems (released 0.37.0/code 50)** — Files menu preview, complete paginated/exportable
   loss report, explicit Apply with previous-project protection, stale-preview rejection and all registered
   destinations. Retains compatible promotions and editable instrument identity; cross-family sounds use
   named audible presets, with located pitch/effect/channel losses and capacity blockers. All 43 destinations,
   the export matrix and native renders pass. Final CI/publication, the official AppImage's eight conversion
   scenarios and the official APK on Nova/DMG/Konkr pass; exact evidence is in `docs/VERIFICATION.md`.
9. **Tuning library (0.28.0)** — 500 bundled choices: four stock, 424 DaMuSc measured tunings regenerated from
   [licensed upstream data](https://github.com/jomimc/DaMuSc/blob/9a4589b99d229691fbefd99b4174e39814789a16/LICENSE.md),
   and independently generated 1–72 EDO (496 additions). Non-octave periods and attribution are preserved.
   Controller paging/category/search, selected-tuning provenance and optional local `.thorscales` packs work on
   Android and desktop; verified on Nova, DMG and Konkr, including a private 4,550-entry import.
   No uncleared scale files in standard releases, no automatic downloads and no upstream licensing contact.
   Project format 10 reads versions 1–9; older apps cannot read newly saved projects.
10. **Minimal Linux handhelds — Brick Hammer / NextUI first** (added 2026-11-09).
   - Shared native frontend and ARM64/vendor-SDL2 adapter are released in **0.38.0/code 52**,
     with a NextUI Pak build. [NextUI confirms Hammer support](https://nextui.loveretro.games/getting-started/).
     Hardware display/audio/lifecycle checks pass; see bounded measurements in `docs/VERIFICATION.md`.
   - Device is now available: read-only ADB inspection on 2026-11-09 confirms NextUI `20261013-0`
     (`39a06de`), AArch64 ELF userspace, glibc 2.33, 998,332 KiB RAM/no swap, 1024×768 at 60 Hz,
     vendor SDL2 2.30.8 under `/usr/trimui/lib`, and the ALSA `audiocodec` device.
     No application was launched, installed or changed during this inspection.
   - Follow-up USB shell verification on 2026-11-10 succeeds as root on the existing AArch64 firmware.
     The existing Remote Terminal HTTP page responds at `http://192.168.1.248:8080/`; no terminal input
     or launcher changes were sent. Available RAM was 736,328 KiB, with no swap. Neither a compiler nor
     Python was found on the device's PATH, so executable probes need a cross-built binary.
   - An isolated cross-built ARM64/glibc probe now runs on the device and loads the actual vendor SDL:
     SDL 2.30.8 exposes **only `mali` video**, plus `alsa`, `dsp`, `disk` and `dummy` audio drivers.
     It opened no display/audio/input subsystem (`SDL_WasInit(0) == 0`) and its temporary executable
     was removed. This proves loader/library compatibility, not rendering or callback performance;
     a stock KMS/DRM or SDL3 assumption would miss the installed vendor video integration.
   - Packaging: `Tools/tg5040/ThorTracker.pak/launch.sh`, with projects in `/mnt/SDCARD/ThorTunez`
     and private workspace/recovery data in its `.thortracker` subdirectory, outside the replaceable Pak.
     The launcher follows [NextUI's Pak contract](https://nextui.loveretro.games/paks/making-paks/),
     uses its diagnostics locations and preserves HOME/ALSA routing. No vendor SDL/Mali library ships.
     Real-device input confirms the trigger axes, printed face-button mapping, independent modifiers and
     cumulative L2+R2 routing. Normal Pak launch, actual Mali rendering, save/reopen, Paper persistence,
     note edit/Undo and explicit Quit back to NextUI pass.
   - Six 25-second real-ALSA runs cover GB, NES, SID, OPL3, Mega Drive and EPSM with advancing playback,
     nonzero PCM and zero driver faults. One GB case has a recovered underrun; the other five have none.
     SID, OPL3, Mega Drive and EPSM FM additionally pass playback-plus-audition and audition-only checks.
     Actual physical X audition also passes in Instrument and Keyboard; temporary test files are removed.
   - Remaining hardware hardening: long projects/Undo history, exports, physical
     suspend/resume and acoustic listening. Keep the bounded results distinct from all-workload guarantees;
     do not silently reduce chip fidelity or remove systems to pass the port.
   - Miyoo Mini Plus remains a secondary, higher-risk experiment: 128 MB RAM and a software-oriented display
     stack. The installed Kotlin 2.1.0 compiler lists ARM32 hard-float, but marks it deprecated; the existing
     16 MiB glyph atlas and full-chip CPU budget need measurement. The Miyoo port remains unimplemented.
11. **Distribution** (after much more work): a free soft launch (e.g. r/AynThor) with GitHub releases, then a paid
   Play Store listing. Before the store: settle the name ("Thor" is AYN's product name), Play Console with the
   existing release key, and Android developer verification (2027).
12. **GitHub Pages website and documentation** (requested 2026-11-10):
   - Static site implemented under `site/`, drawing its manual sections directly from canonical
     repository documentation. Installation, controls, systems/limits, files/exports, troubleshooting,
     source and notices are included; no runtime JavaScript, analytics or remote fonts.
   - Dedicated Pages workflow and local-link validation are implemented. Integrated publication and
     browser acceptance are tracked in `docs/VERIFICATION.md`; maintainer steps are in `site/README.md`.

### Testing feedback and QoL proposals (2026-11-08)

- **Zoom in/out — implemented and verified for 0.39.0:** four workspace-only content sizes
  (75/100/125/150%), independent per Android view and persistent on Linux handhelds.
  Menu → Focused view zoom provides in/out/reset; rebindable actions are deliberately unbound.
  Complete-view swaps retain zoom; shell and modal touch targets stay physical-sized. No song,
  playback, export or Undo changes. Nova, DMG, Konkr and the actual CI AppImage pass compact-screen,
  persistence and frontend acceptance; zoom does not depend on future touchscreen support.
- **Multiple effects per row — NES in 0.40.0, TIA in the 0.41.0 candidate:** the
  [manual matrix](README.md#multiple-effects-per-row) covers all 43 variants: **2 with one column,
  3 with two and 38 with four**. All 15 FamiStudio NES variants and both TIA regions accept four
  ordered columns. TIA's image/project schema is unchanged; old players reject added-column
  content. More columns do not add voices, independent pitch targets or shared hardware controls.
  The [compatibility review](docs/BACKENDS.md#current-capacity-and-compatibility-review-0410-candidate)
  records precedence, timing restrictions and the driver/export gate. Four is the normal ceiling
  where straightforward, not a mandate to retrofit fixed-format drivers or elaborate export packing.
  VIC/TED and VSU still need their packed-count contract changed. Game Boy/SID remain one-column
  until a native-format solution justifies its complexity; none is next in the hardening queue.
- **Swing rhythms — implementation paused:** investigate viable drivers and the additional code,
  format, export and verification cost of the simplest useful implementation before choosing timing
  semantics. The earlier song-wide plus pattern Inherit / Straight / Override design is only a
  research candidate, not an approved scope. SID funktempo and Sega odd-row speed already exist.
  [Feasibility and open decisions](docs/BACKENDS.md#swing-and-groove-research-proposal) cover
  quantization, odd-length phase, tempo-command precedence, seek/loops and driver-specific limits.
  No playback timing changes are authorized by this research.
- **Touch-only phone use — isolated portrait preview, production unchanged:** the separate
  package/branch explores the controller dock and drawer alongside the earlier landscape study.
  Six real multi-touch tests pass on DMG as a functional testbed; **Galaxy Fold8** is the user's
  calibration target, with calibration still in progress. Do not describe phone ergonomics as verified.
  Next is joint prototype review and soft-launch hardening: simultaneous holds, modifier layers,
  thumb reach, obscured rows, edge-back gestures, file picking, keyboard/insets and cancellation.
  Reuse shared controller actions, document, recovery, audio and export paths; preserve physical
  controllers and dual displays. [The proposal](docs/TOUCH_PROPOSAL.md) remains a review boundary,
  not permission to merge the preview into production.
- **Hardening and ergonomics priority:** use bounded live editing/playback, navigation, selection,
  clipboard, Undo and save/reopen scenarios on Nova, DMG and Konkr. Investigate concrete failures
  before changing behavior; preserve private state and `/ThorTunez`, take exclusive device leases,
  and avoid emulators or simultaneous heavy builds. Assess shortcut discoverability and small
  workflow improvements without reducing utility. The 0.41.0 candidate cancels held edit/listen
  input and modifiers after window-focus loss or controller removal/change; focus-only cancellation
  preserves transport, while Activity pause stops it. Nova's full-song and Konkr's TIA four-column
  scenarios cover actual UI editing, playback, save/reopen and export; exact bounds and restoration
  evidence are in `docs/VERIFICATION.md`. Existing automatic-column and high-channel fixes remain.
  **Keep B = Clear and Select+B = Cut cell/block.** Clear has field-specific note/effect semantics;
  silently turning it into Cut would replace the shared clipboard, which document Undo does not
  restore. Improve discoverability first. A separate last-cleared recovery slot would be new scope,
  not an implicit change to the existing clipboard.
  Recurring friction to revisit is deliberately short: discovering **Columns** and hidden effects,
  distinguishing **Clear from Cut**, and avoiding stale held input after an interruption.
  Help now explains Columns/hidden-FX markers and current shortcuts. Keep swing, mixer ceilings,
  pattern-local mute and shader pipelines deferred rather than expanding this hardening pass.
- **Korg DS-10 / DS-10+ and original synthesis** (updated 2026-11-10): longer-term direction is an original
  built-in synthesis backend with its own instruments and more playful panels, alongside the hardware
  backends. Separately explore bespoke friendly synth panels for existing chips: musical controls map to
  that chip's actual instrument parameters, with an advanced page retaining precise hardware access.
  Do not force unlike chips into a universal instrument model or hide quantization/shared-resource effects.
  Both are design directions, not implemented engines or selected panel designs.
- **BRIXEQ interface research** (2026-11-09): reviewed the [manual](https://github.com/boorch/brixeq-pak/blob/main/MANUAL.md)
  and read-only copies of the installed v1.9.0 Pak UI/launcher. Useful discussion candidates:
  - **Colour themes, released in 0.38.0/code 52:** original Slate, Paper and Contrast palettes,
    selected under Menu → Colour theme and persisted as workspace state. They do not alter song data,
    exports or Undo history. Nova, DMG and Konkr pass selection/restart/playback checks, and all 32 Slate
    baseline captures are pixel-identical. The actual CI AppImage passes palette persistence in fresh
    processes, unchanged project bytes, playback, audition and compact surfaces. Brick Paper selection,
    full Quit/relaunch persistence and exact project save/reopen also pass. No BRIXEQ palette was copied.
  - **CRT/scanlines and shader pipelines — deferred:** BRIXEQ offers OFF/MILD/FULL post-processing,
    horizontal RGB separation and alternating pairs of dim output rows; its shader omits warp,
    bloom and animated grain. Investigate reusable
    [`.slangp` support](https://docs.libretro.com/development/shader/slang-shaders/) as an alternative
    to a bespoke option, not an approved renderer rewrite. A preset needs a compatible filter-chain
    runtime, not just a file picker. [librashader](https://github.com/SnowflakePowered/librashader)
    is a reuse candidate, not a verified fit for the current Android Canvas/SDL surfaces or Brick
    graphics capabilities. Check runtime and individual shader licences, small-text readability,
    frame time, memory and power; retain an unfiltered default and a safe disable path.
  - **Workflow:** persistent focused-parameter editing across steps; explicit step audition; bounded
    selection randomization with one-step Undo; queued pattern/scene changes; trigless parameter locks.
    These are research ideas, not approved implementations. Keep hardware-specific effect legality,
    deterministic/native-export semantics and editable instrument-slot identity.
  - BRIXEQ's complete per-step instrument snapshots are an interesting contrast to tracker instruments,
    not a reason to replace Thor Tracker's model. Its repository has no declared project license;
    availability of Lua in the release is not reuse permission. Discuss ideas and implement independently,
    rather than copying its code, palettes, artwork or shaders into the GPL project.
- **Pattern channel compression — implemented in 0.37.1:** the bindable **L2+Left** toggle collapses
  the focused channel to a numbered note-only strip. Hidden sounds/effects still play and export.
  Navigation/touch skip hidden fields; expansion restores effect-column choices. Per-pane workspace
  persistence and display swaps retain the layout; full-cell clipboard operations retain all hidden data.
  Existing custom keymap conflicts are preserved instead of overwritten.
- **Pattern-local channel mute — explicitly deferred by the user (2026-11-10):** current native formats
  cannot uniformly preserve an output-mask mute. The user declined a preview/WAV-first implementation
  with native-export refusals. [Feasibility record](docs/PATTERN_MUTE.md) retains the constraints and
  unapproved future alternatives. The requested unit is **one channel/column within one pattern**,
  not a global channel mute, visual collapse or a particular order occurrence. It must survive
  save/reopen and affect playback and native/WAV export; every reuse of that pattern uses its mute.
  Define entry/tail behaviour and retain timing/control-flow effects on muted lanes;
  shared oscillators, envelopes, filters and modulation must not be accidentally disabled. Audition policy
  and the per-backend lowering contract need design before implementation.
- **Mixer volume ceiling — explicitly deferred:** prioritize effect slots and native volume automation.
  The recorded fader concept is a ceiling,
  `effective = min(programmed level, ceiling)`, not proportional gain. Lower automation stays unchanged;
  automation above the ceiling flattens at it. Apply after the relevant musical volume/envelope sources,
  not merely at the first note. A 70% ceiling therefore differs from multiplying every value by 0.7.
  Register-volume chips, discrete wave gain, autonomous envelopes, FM carrier attenuation and SID's shared
  master volume have different capabilities. Define exact/quantized/unsupported native-export behaviour
  per backend; never silently promise an independent hardware fader or export a preview-only mix.
  The [complete capability/design pass](docs/MIXER_CEILINGS.md) covers all 43 registered systems and
  every native export route. Reopening implementation requires review of the level/percentage domain,
  autonomous-envelope and FM restrictions, shared/stereo controls, zero/live behavior and export
  refusals. No mixer feature is implemented or currently scheduled.
- **Right-stick pattern jump** — implemented in 0.14.1, verified in the Deck frontend and Android input path: two
  quick same-direction right-stick flicks, neutral between them, move the focused Pattern cursor to the pattern's
  first/last row. Shared detector `input/StickFlicks` (engage 0.6, neutral 0.3, flick ≤ 300 ms, gap ≤ 300 ms)
  behind `HostInput.rightStick`; Android reads Z/RZ or RX/RY per device, the Deck SDL's right X/Y. Fixed gesture, not
  in the keymap; the L2+R2 layer is unaffected. Thresholds may need tuning on the Thor's sticks.
  Horizontal extension implemented in 0.37.1: double-left/right jumps to the first/last channel's note
  cell, retaining the current row and including collapsed channels. Pattern-only; it does not change
  the current pattern/order. Konkr's actual uinput route verifies both axes and shortened-pattern bounds.
- **Crash-recovery working policy:** serialize heavyweight builds across worktrees; cap build workers and
  compiler parallelism, avoid accumulating Gradle/Kotlin daemons, and never overlap an emulator with compilation.
  Local Linux Gradle runs now require 8 GiB available memory and use an enforced 4 GiB/no-swap scope
  (3 GiB reclaim threshold, two-CPU quota), created inside Distrobox around the real build.
  Put compiler scratch and audio evidence on SSD, never RAM-backed `/tmp`; retain evidence across reboots.
  Check available memory, swap activity and pressure before each heavy run; prefer reserved Android hardware.
  The reset alone does not establish the cause of the latest lockup.

## Agreed MVP scope

### Song editing

- Song/order view, pattern view, instruments page, waveform editor, and note-entry/audition view.
- Four monophonic channels for this backend: pulse 1, pulse 2, wave, noise. Channel count is not an application-wide constant.
- Pattern cells contain a note, instrument reference, and one effect. This is one effect per channel per row, not one effect shared across all four channels.
- Explicit, reusable instruments. No automatically generated instrument system or hidden per-pattern sound bundles.
- Pulse, wave and noise instrument controls appropriate to the selected backend and instrument type.
- 32-sample waveforms with 4-bit sample values for GB/hUGE.
- Order arrangement, tempo, loops, pattern creation/duplication, selection, copy/paste and undo/redo sufficient to compose complete songs.
- Step entry and auditioning, not real-time recording. Support note-entry advance settings.
- Distinguish blank cells, new notes and explicit cuts. Do not assume a generic synth-style release exists for every backend.
- Show hexadecimal effect data in the pattern and named/contextual controls for editing it. Expose only behavior implemented by preview and export; unsupported operations must not be silently ignored.
- Make backend limits visible before export: instrument counts, waveform counts, note ranges, pattern constraints and effect-slot restrictions.

### Displays, views and focus

- One full-size view per available display. No docking, split-pane system or window manager.
- Any available view can be opened on either display. No top/bottom role restrictions.
- Both displays may show the same view, with independent view navigation where appropriate.
- Provide useful default layouts without making them mandatory.
- Allow contextual view switching, explicit view selection and pinning a view.
- Keep view pinning separate from subject following: an instrument view can stay open while following the selected instrument, or lock to a specific instrument.
- Swap displays by exchanging complete view states, including the selected subject and navigation position.
- One shared song document and undo history. Never maintain per-display copies of song data.
- Explicit, visible controller focus and a dedicated action to switch focused display.
- Touch acts on the touched view without unexpectedly moving controller focus. Contextual updates do not steal focus.
- Transport commands are global. Other controller actions target the focused view.
- Lower-screen keyboard is a default arrangement, not a required placement. Noise auditioning and backend-specific editors may replace it by choice.
- Persist workspace/layout preferences separately from the musical project.
- Support a usable single-display fallback when a second display is absent or disconnected; preserve the document and recover the second view when possible.

### Controls

- Controller-first: ordinary composition operations must not require a touchscreen keyboard. Touch is an additional useful input path.
- Separate semantic actions from physical button bindings so controls can be remapped without rewriting views.
- 0.5.0: one keymap (control → button or Select/L2/R2/L3 chord) drives routing, hints and Help; Menu → Controls rebinds with real presses, asks before takeovers, never strands navigation/confirm, and exports/imports versioned JSON. Menu and Back are fixed.
- 0.6.0: 19 scale guides (modes, minors, blues, whole tone, exotic and Japanese scales), project format 4.
- 0.7.0 microtuning: one Scala tuning per song, realised with stock hUGEDriver as nearest note + `40y` (+0..15 period units from tick 1, held across effect-less rows — verified natively). Entry restricted to degrees within a per-song accuracy limit (5¢ default, measured on the driver's 12-note grid); explicit Fit command; project format 5. Exact per-song tunings would need a retuned driver note table shipped with the game (not done).
  - Decision (user, 2026-11-05): the `40y` bends stay written in the pattern cells, so the pattern shows exactly what will be rendered. Applying the tuning only at playback/export was considered and rejected; retuning a finished song is not a use case.
- 0.8.1: Song view **New patterns** scale (editor-only guide that New starts patterns in; Clone/Repeat keep their source scale), project format 6.
- 0.8.1: L2+Y pattern loop (keymap version 4): the engine plays that order alone, with its stored Bxx jumps aimed back at it; editor playback only.
- 0.8.2: parked orders (Song view Park): kept in place, skipped by playback and export via one arrangement step (`HugeRules.arranged`) that renumbers loop/`Bxx`; playing a parked order loops it alone; project format 7.
- Initial mapping proposal: D-pad navigates; hold A plus D-pad adjusts the current field; horizontal adjustments are fine and vertical adjustments coarse where appropriate (semitone/octave for notes); tapping A can insert the previous value; L/R changes channel; Start toggles playback.
- The mapping is provisional and must be exercised on hardware. Choose nonconflicting bindings for focus, selection, copy/paste, clear, undo and redo.
- Show contextual control hints on both displays.
- A held adjustment or waveform drag is one logical undo operation.
- Audition versus note insertion must be visibly distinguished so exploring sounds does not overwrite a phrase accidentally.

### Persistence and export

- Own versioned project format, identifying backend and backend-data version. Do not force editor-specific data into `.uge`.
- Export `.uge` only in the MVP. Preview-player ROMs/data are internal implementation details, not additional user-facing export formats.
- Atomic replacement where the storage mechanism supports it; recovery protection where provider semantics do not guarantee atomic rename/replacement.
- Crash-recovery autosave and recovery of unsaved edits after Android lifecycle interruptions/process death.
- Document-wide undo/redo across both displays.
- Prefer Android's document picker and user-selected storage locations. Broad filesystem permissions are not the default. If provider behavior creates material friction, document the observed problem before choosing an alternative.
- Distinguish app-private working/recovery data from explicit user saves and exports. A revoked URI grant or missing provider must not destroy the working document.
- Exercise save, reopen, export and transfer off the device, not just internal serialization.

### Backend boundaries

- Shared application infrastructure owns input actions, display hosts, workspace layout, selection, edit commands, undo, file orchestration and transport UI.
- Backend owns musical document semantics, instruments, effects, constraints, compilation, export and playback implementation.
- Backend-specific views are allowed. No universal FM/wavetable/sampler editor is required.
- Views and editor commands must not manipulate emulator memory or hUGEDriver internals directly.
- Cross-backend conversion is an explicit, lossy document operation with a report; it does not replace backend semantics. Do not implement a speculative backend plugin system, universal effect language or unused future drivers.

## Responsive playback is a requirement

The user's composition loop is repeated note entry followed by Play to hear a run. Fidelity cannot make this loop unpleasant.

### Hard requirements

- Playback and editing must feel seamless, like a conventional DAW/tracker. One second is not a target or permission to stall the composition loop.
- Keep the working song and prepared playback data in RAM where useful. Measure input-to-audio latency, but judge the complete repeated edit/audition/play workflow.
- Edits during playback must reach upcoming musical events without stopping or restarting the song. Apply structural changes at defined safe boundaries and retain transport continuity.
- Provide playback from the current pattern/selected position and from the song start; editing a later phrase must not require listening from the beginning.
- Auditioning notes/instruments must feel immediate. Measure physical latency and tune buffering rather than relying only on emulator execution benchmarks.
- Playback position must be exposed to the UI accurately enough to follow the pattern.
- Do not run an external build toolchain for every Play operation or allocate/compile on the realtime audio callback.

### Live editing policy to establish in the spike

Seamless live editing is now an explicit user requirement. Use in-memory encoding and safe update boundaries for actual-driver playback, or the authorized simulation path if necessary. Do not require a disruptive rebuild/restart after each edit. Cycle accuracy is subordinate to a responsive, continuous composition workflow.

Starting at a selected row must have defined instrument/effect state. Evaluate bounded fast-forward/state reconstruction or suitable checkpoints; do not begin in arbitrary stale register state. Seeking behavior and its latency are part of the playback experiment.

### Preferred implementation

Use the actual pinned hUGEDriver in an embedded emulated environment with CPU execution, memory/timing support and an APU. Reuse an existing core if this is simpler than extracting/building a minimal one. No visible Game Boy display is needed.

Separate application compilation/preparation from runtime song-data encoding. Prefer a prebuilt small player plus in-memory data generation when supported. Actual data layout and memory/bank handling must be verified against the selected driver, not assumed.

Both preview song data and `.uge` export originate from the same validated backend document. Match the chosen driver/version's semantics rather than assuming current upstream documentation matches gbc-puzzler's pinned driver.

An APU alone is not an implementation of hUGEDriver sequencing: instrument retrigger, row/tick effects, cuts and timing would still need to be reproduced by application code.

### Authorized alternative if necessary

The user explicitly accepts a working synthesizer/simulation if actual-driver preparation cannot satisfy the interactive requirement. If measurements justify it:

- Use a bounded GB-aware preview implementation or suitable embedded synthesizer for working sounds, retaining correct `.uge` export.
- Prefer meaningful GB constraints over generic sounds: discrete duty, waveform/volume limits, envelopes and characteristic noise.
- Make any approximate-preview status and unsupported sonic details explicit. Do not imply that an approximate preview proves in-game fidelity.
- Avoid implementing two complete playback engines merely for architectural neatness. Choose the smallest working path based on evidence.
- Any exported feature still needs semantic verification against hUGEDriver. Do not silently drop effects or substitute unrelated sounds.

Do not abandon the responsive composition loop to preserve emulator purity. Conversely, do not introduce a simulator merely on the assumption that in-memory driver preparation will be slow.

## Playtest workflow pass — 0.2.0

- Auto-hidden, overlay toolbar; Select+Start reveals it with explicit controller focus. Touch reveal times out; focused controller use does not.
- L2/R2 are chord-only: L2+A undo, L2+B redo (R2+A until 0.8), L2+X complete-view swap. No bare-trigger action or constituent-button edit/audition leak.
- 0.8: R2 chords show views on the focused display (R2+A Song, R2+B Instrument, R2+X Sketch, R2+Y Keyboard; Pattern, Wave and Help bindable); a second press returns to the previous view.
- 0.8.6: R2 + ↑/→/↓ show Pattern/Wave/Help (keymap version 5). Steam Deck AppImage from the shared core.
- 0.9.0: NES (FamiStudio) sound system, true pitch (project format 9), GPL-3.0-or-later.
- 0.9.1: Android error bubbles clear when the song is fixed; unplayable auditions are passing messages.
- 0.10.0: NES DPCM channel (samples, starter kit, .wav/.dmc import), shared song+sample memory.
- 0.11.0: C64 SID sound system (GoatTracker 2.77 playroutine + reSID), .sid/.prg/.sng export.
- 0.12.0: OPL3 FM sound system (own player on Nuked-OPL3), up to 4 effect columns on OPL3, chord effect, curated FM editor, VGM export.
- 0.13.0: Mega Drive sound system (YM2612 + PSG through SGDK's XGM Z80 driver), .vgm/.xgm/.xgc export.
- L2+R2 third chord layer: other-display view chords by default (keymap version 6), see Roadmap.
- Start plays from the current pattern's first row; L3+Start plays from its cursor. Song-start remains an explicit command.
- App-owned menus, rename, confirmation and errors live inside the app's display roots; Android SAF and the IME remain system-owned.
- Visible arpeggio-0 selection without inventing a parameter; OFF label retains immediate-cut semantics.
- Discoverable Song details/tempo shortcut.
- Non-destructive per-pattern root/scale guides for D-pad tonal note entry, persisted in project format 2. Existing format 1 songs remain readable.
- Supported stutter construction is repeated note/instrument rows, not an invented unsupported hUGE effect.

## Tonal playground — 0.3.0

- Sketch is a full-size, freely placeable view with independent per-display exploration state, pinning and complete-state swapping.
- Starts from the pattern's root/scale; local root/scale experiments never change the song. An explicit Pattern key reset resumes following.
- Scale notes with interval degrees, diatonic triads with roman numerals, and highlighted chord-tone roles.
- Hold-to-audition notes or repeating arpeggios through the existing actual-driver engine. Tonal channels only; no note insertion, history entry or export change.
- Controller and touch navigation; preview release/cancellation on menus, focus changes, swaps and lifecycle interruption.
- **L2+Start replaces the 0.2 L3+Start cursor-play binding.** Bare Start retains pattern-row-00 playback. Bare L2/R2/L3 remain inactive.
- Phrase generation, variations, insertion and the circle-of-fifths reference are not part of this initial playground.

## Pattern length — 0.4.0

- One played length per pattern (1–64, all channels), set from the pattern panel; undoable, saved in project format 3.
- Hidden rows are preserved; insert/delete row shift only played rows; whole-pattern copy/paste and clone carry the length.
- Preview and `.uge` share one lowering: hidden rows blank, `D01` on the last played row (or the loop `B` on a short final order).
- Per-channel lengths are intentionally out of scope (no hUGE per-channel position; too complex for this interface).


## Proposed MVP exclusions

These are implementation scope defaults, not claims that the features are impossible:

- Additional sample-based platforms (GBA DMA, SNES SPC700 and Amiga Paula).
- Real-time note recording, MIDI integration, cloud sync, collaborative editing, plugin hosting and sample recording.
- Import of arbitrary existing `.uge`/Furnace projects. Export compatibility does not establish import compatibility.
- C/assembly/standalone-ROM exports.
- Window management, split panes and arbitrary docking.
- Full Furnace feature parity, unrestricted automation lanes, custom executable driver routines and advanced macro/subpattern authoring.
- Analog/revision-perfect emulation and hardware quirks not exercised by the supported feature set.
- Scale-root-relative interval labels in place of Pattern note names remain deferred; Sketch displays degrees alongside pitches.

Standard instrument settings, row effects and full-song composition are not optional scaffolding. A runnable empty UI or audio demo is not the MVP.

## Implementation sequence for the autonomous run

### 1. Inspect environment and choose the narrow implementation path

- Inspect installed Android SDK/NDK/JDK/build tooling, available device connections and repository conventions before installing or changing global tooling.
- Verify Thor accessibility through existing adb connections; do not assume it is connected. Do not change device settings or disrupt unrelated applications unnecessarily.
- Inspect candidate emulator/core licenses, Android build integration and driver data interfaces. Verify hUGEDriver and `.uge` versions from current source.
- Select the application UI/native-audio stack based on a working dual-display/audio experiment, not a preference presented as established evidence.
- Record selected dependencies, license obligations and backend boundaries in the repository.

### 2. Prove the high-risk vertical slice

- Render independent interchangeable views on both displays; controller focus and touch routing must be observable.
- Play a short four-channel song through actual hUGEDriver, expose its order/row, and measure latency.
- Change a note and an instrument, replay promptly, audition, and start from a later phrase.
- Export the same document to `.uge`; load/convert it using the real toolchain and play it with the pinned driver.
- Decide whether the preferred playback path meets the responsiveness requirement. Use the authorized alternative only when evidence calls for it.

### 3. Implement the complete editing loop

- Establish the versioned GB/hUGE document, validated edit commands and shared undo/redo.
- Complete pattern, order/song, instrument, waveform and keyboard/noise-audition views.
- Wire selection, phrase copy/paste, duplication, clear, tempo/loop controls and context-aware effects.
- Provide configurable input bindings, visible focus, contextual hints and intentional audition/entry behavior.
- No mocks/no-op controls presented as working features.

### 4. Complete workspace and file workflows

- View assignment to either display, pin/follow/lock behavior, duplicate views, swap and saved layout preferences.
- Single-display/disconnect handling and Android lifecycle recovery.
- Project save/open, autosave recovery and user-selected `.uge` export locations.
- Preserve recoverable data through failed saves and revoked/missing storage access.

### 5. Exercise the actual composition workflow and deliver

- Use the actual application to enter a melody, create bass/percussion, modify an instrument, draw/select a waveform, arrange repeated patterns, add an effect, copy a phrase and undo edits.
- Repeatedly edit and replay from the current phrase and song start; measure latency after small and larger supported songs.
- Swap and pin views, change controller focus, use touch without stealing focus, and edit the same document from both displays.
- Save, close/reopen, recover interrupted work and export the project. Compare musical events and audible behavior with the pinned-driver export.
- Exercise the gbc-puzzler integration in an isolated location without replacing the user's music, changing its tracked files, or disturbing concurrent work.
- Produce an installable debug APK, a real sample project and an exported `.uge` demonstrating supported features. Document how to build/install/use the app and the controls.
- Update implementation notes with exercised evidence, measured latency, known limitations and any real blockers. No unverified completion claims.

## Acceptance checklist

- [x] A complete song can be composed with controller controls; touch offers note entry and appropriate editing controls. Verified through the Thor controller event node, not human-held button testing.
- [x] Explicit instruments, wave editing, order arrangement, tempo/loops and one effect per channel-row work end to end.
- [x] Either display can host any available view; pin/follow/subject lock and complete-state swap behave consistently.
- [x] Controller focus is visible, switchable and unaffected by incidental touch/context changes.
- [x] Both displays mutate one document and one coherent undo/redo history.
- [ ] Human assessment that repeated editing, auditioning and playback feel seamless. Automated live-update, continuity and digital-output checks passed; device output timestamp latency is measured, but acoustic latency and subjective playability are not.
- [x] Playback from a selected phrase has defined state and does not require manually replaying the entire song.
- [x] Save/open, autosave recovery, interrupted lifecycle and failed-storage handling preserve user work.
- [x] Exported `.uge` is accepted by the actual target tools and plays the intended composition with the pinned driver.
- [x] Application-level code does not hard-code four channels or manipulate emulated driver state from views.
- [x] A debug APK, sample projects, `.uge` exports, build instructions and truthful verification results are delivered.

Additional requested polish is implemented: brief entry/key afterglows, channel activity lamps, focus/view feedback and a custom adaptive launcher mark. The grid stays stable, animations honor the system duration setting, and inactive feedback does not schedule its own redraws. Hardware bug hunting also corrected cross-display dialogs, subject locks, native input focus and three gesture/following defects.

## Autonomous execution rules

The user intends a sustained implementation session while away. This is an execution plan, not a guarantee that the complete MVP fits a particular elapsed-time window.

- Once implementation is authorized, proceed through actionable work without requesting routine confirmations. Choose conservative defaults and record them.
- Work toward the complete agreed MVP, not a redefined demo. Report actual missing prerequisites rather than inventing a completion claim.
- Use parallel implementation only after shared contracts and independent work slices are clear. Keep one integration owner; do not run competing shared builds or device installs.
- Do not modify gbc-puzzler, replace its music, or use its canonical working tree as an integration scratch area.
- Do not purchase services, publish releases, push repositories, factory-reset devices or make destructive/unrelated changes without authorization.
- Verify behavior through the running application and audio/export paths, not only unit tests or compilation.
- If Thor hardware is unavailable, finish reachable build/export/emulator work and distinguish it explicitly from unperformed Thor dual-screen, controller and physical audio-latency checks.
- Record decisions and progress in repository files so work can resume from actual state rather than conversation assumptions.
- Work runs within the active tool session; there is no implied unattended scheduler that continues after the assistant has ended its run.
- Implementation authorized on 2026-11-04. Work until the complete goal is reached or 2026-11-05 08:45 America/New_York; if complete early, spend remaining useful effort on refinement and bug hunting.
- Do not access or disturb the Thor before 2026-11-05 00:00 America/New_York. After that it is authorized for testing at wireless-debug address `100.71.60.0:46869`. Inspect shared device-access coordination before claiming it.
