# Thor Tracker — MVP plan

Status: MVP implemented and exercised on the Thor, an Android emulator and the isolated target-game toolchain. APK and two project/UGE examples are delivered. See docs/VERIFICATION.md for evidence and limits: human listening, ergonomics and physical button-to-speaker latency remain unverified; the current Thor mixer path measures approximately 100 ms output latency.

## Goal

A useful, controller-first Android GB/GBC music tracker for the AYN Thor. Compose complete songs, audition and replay edits quickly, use both displays freely, save projects safely, and export `.uge` songs for hUGEDriver-based games, including gbc-puzzler.

The first backend is explicitly GB/hUGE. Backend extensibility means modular software, not a universal instrument model or lossless song conversion. A project belongs to one backend. GBA and Mega Drive are examples of possible future backends, not MVP targets.

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
- Microtuning (not implemented): stock hUGEDriver can only detune with `40y` (+0..15 period units upward, from tick 1 of each note, using the row's effect slot). That reaches quarter tones within ~10 cents from octave 5 up but only ~22-44 cents of bend in octaves 3-4. Exact per-song tunings would need a retuned driver note table shipped with the game.
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
- No cross-backend song conversion. Do not implement a speculative backend plugin system, universal effect language or unused future drivers.

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
- L2/R2 are chord-only: L2+A undo, R2+A redo, L2+X complete-view swap. No bare-trigger action or constituent-button edit/audition leak.
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

- GBA/Mega Drive or other additional backends and conversion between backends.
- Real-time note recording, MIDI integration, cloud sync, collaborative editing, plugin hosting and sample recording.
- Import of arbitrary existing `.uge`/Furnace projects. Export compatibility does not establish import compatibility.
- C/assembly/standalone-ROM exports.
- Window management, split panes and arbitrary docking.
- Full Furnace feature parity, unrestricted automation lanes, custom executable driver routines and advanced macro/subpattern authoring.
- Analog/revision-perfect emulation and hardware quirks not exercised by the supported feature set.
- Configurable colour themes (requested after playtesting; deferred).
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
