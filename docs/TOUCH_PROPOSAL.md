# Touch interface proposals

Status: **isolated Android controller-dock preview; not merged into production**.
The 0.42.0-touch-preview.2 candidate integrates the current editor, audio engines and file paths under a distinct Android
identity. **Galaxy Fold8 is the design target. Pocket DMG is only a functional testbed**, not
a substitute for the phone's screen geometry or a human two-thumb ergonomics trial.
The landscape study and browser evidence below remain historical proposals, not a native
implementation contract.

## Native controller-dock preview for Galaxy Fold8

### Geometry and interaction

The primary display has a 48 dp Menu / Touch preview / Play bar, the real `TrackerView` taking
the remaining height, and a **144 dp dock: three rows, all sixteen controller buttons retained**.
Each thumb group is capped at 216 dp and anchored 8 dp from its side. Wider windows add a
central gap rather than stretching controls toward the crease. L2 and R2 sit at the outer
top corners; the normal D-pad and A/B/X/Y arrangement remain recognizable:

```text
L2      ↑      L1              R1      Y      R2
←     Start    →               X      R3      A
Select  ↓                             B      L3
```

Samsung's [Fold8 specification sheet](https://stg-images.samsung.com/is/content/samsung/assets/us/f2607/business/smartphones/spec-sheets/galaxy_z_fold8_one_pager_0713.pdf)
lists a 972×1248 cover and 1848×2448 main display. The native ratio study uses representative
360×462, 412×529, 720×544, 840×634 and 960×724 dp **app windows**; these are not measurements of
the user's Android density, display zoom or usable insets. All targets pass the 48 dp check
in those profiles. The 360×462 study shows fifteen Pattern rows; the 720×544 study shows
nineteen, plus the existing inspector and arrangement overview.

There is no forced orientation: the cover supports the portrait flow, while the opened
inner display can use its natural wide layout. System bars stay outside the content and
Android resizes for the IME. Configuration changes cancel held input. Actual fold/unfold,
phone keyboard/inset behavior, large font/display zoom and physical reach still need phone
review. Slate/Paper/Contrast and the existing native typeface are reused; this does not
introduce a second note/value editor.

- Each finger owns its initial button until it lifts, even after moving outside it. Sliding
  between buttons does not retarget; lift and press the next direction. A second finger can
  press/repeat D-pad directions while A or X remains held. Arrows repeat after 350 ms, then
  every 85 ms; other buttons do not synthesize repeats.
- A, B, X and Y retain the real shared semantics: A writes/adjusts, B clears the current field,
  X listens and Y marks. In Pattern, X+arrows keeps the original note sounding while navigating;
  it does not invent a new continuous pitch-audition rule. Select+B cuts, not ordinary B.
- Select/L2/R2/L3 are **tap-to-latch**, explicitly labeled `ON`, selected and announced to
  accessibility. Tap again to release. L2 and R2 may both stay on for the existing third-layer
  view commands without needing a third finger. Start remains available for L2+Start and
  Select+Start. L1/R1 and R3 are momentary. Dock labels are physical button names and use the
  same user keymap as the physical controller; Controls can restore/rebind that map.
- Menu opens an explicit full-width, scrollable portrait navigation drawer. It lists every
  available system view, Files, Pattern scale/length, Tuning/scale library, view behavior,
  zoom, Undo/Redo and the real Settings/Controls/Help menu. Play/Stop is always at the top.
  Both target the primary editor. Existing hardware Menu and Select+Start still use the
  shared menu/toolbar paths.
- Virtual keys and physical-device key owners are merged before `HostInput`: one source's
  UP cannot release another source's held A/X/modifier. Pointer IDs, not their array positions,
  own buttons. Opening a drawer/modal/toolbar, ACTION_CANCEL, lost window focus, pause,
  rotation, or controller removal/reconfiguration cancels holds and audition without
  interpreting cancellation as a tap. Modal/IME entry clears all visible latches; late UPs
  and hardware repeats cannot resurrect a canceled press. Focus loss alone does not stop
  background song transport; the existing onPause transport policy is unchanged.
- The dock hides automatically while a usable physical controller is attached and returns when
  the last one goes, including at startup, resume and device enable/disable changes. Usable means
  an enabled, non-virtual gamepad with A, B and D-pad or stick/hat directions; a handheld's
  built-in pad counts, while keyboards, D-pad remotes and Android's virtual keyboard do not. The
  editor takes the reclaimed height; the Menu/Play header and drawer remain. Hiding forgets only
  the dock's presses, latches, arrow repeat and their audition without treating them as releases;
  keys a physical controller holds keep their owner and layer. A forgotten dock Edit/Clear/Mark/Listen
  hold ends only that hold, so a physical Edit or Listen and its audition continue. There is no manual
  show override.

### Isolation and files

Every Android variant has application ID **`org.thortracker.touchpreview`**, label
**Thor Tracker Touch Preview**, and test application ID **`org.thortracker.touchpreview.test`**.
The namespace/JNI classes remain `org.thortracker`; do not rename native symbols.
Private recovery, settings, imported scales and firmware therefore live under the preview
package, not production. The SAF provider authority is **`org.thortracker.touchpreview.documents`**.
Its only root is the preview package's external-files directory:

`Android/data/org.thortracker.touchpreview/files/Documents/ThorTrackerTouchPreview/`

All file pickers initially point to that provider. It supports real create/read/write/delete
for projects, exports and imported files, rejects traversal, and does not copy or discover
production songs. Android's chooser can still explicitly browse another provider: never
choose a production destination or `/ThorTunez` during preview tests. Files in this preview
root are app-scoped and **removed when the preview is uninstalled**; export anything worth
keeping to a deliberately chosen separate backup before cleanup. No storage permission,
production URI grant, production FileProvider authority or production backup is reused.

### Game Boy pulse Sound playground

The earlier accurate instrument panel remains **Advanced**. This pilot adds a different,
approachable **Sound** face inside Instrument view—not another document or a patch conversion.
Select an instrument on the left; explore four musical controls on the right. Pulse starts
in Sound; Wave and Noise retain Advanced. Mode choice and remembered cursors are pane-local
for this session, not part of the song or saved workspace.

| Control | Musical choices | What changes |
| --- | --- | --- |
| Character | Thin, Reedy, Square | Pulse duty only. The equivalent 25% and 75% timbres both read Reedy; selecting the current choice preserves the exact original. |
| Shape | Pluck, Held, Swell | Loudness envelope and length gate. Pluck fades; Held stays steady; Swell grows, then holds. |
| Length | Short, Medium, Long, Longest | Pluck fade / Swell rise: about 188 / 375 / 750 / 1313 ms. Held: about 63 / 125 / 250 ms or until the next note/OFF. |
| Motion | Still, Falling, Rising | Hardware pitch sweep on **Pulse 1 only**. Pulse 2 ignores it; rising notes can reach the hardware cutoff. |

These are discrete real-chip settings, not filters, ADSR emulation or additional synthesis.
Shape keeps a recognized Length choice; otherwise it starts at Medium. Length keeps a
recognized Shape. **Changing Length on a Custom Shape deliberately makes a Pluck**; the
panel warns about this before editing. Neither changes Character, Motion, name or other-bank
fields. Selecting a control's already-current choice preserves the entire patch exactly.

Opening Sound or Advanced never rewrites an instrument. Unrepresented advanced settings
show **Custom**, with no invented slider position. Existing factory sounds can legitimately
show Custom because their exact level differs from these musical presets. Find a sound here,
then switch to Advanced to refine it; both faces edit the same stored instrument and Undo history.

**Touch:** tap Sound/Advanced; slide a control horizontally. Hold X on the dock to hear changes.
**Controller:** Up/Down selects controls, held A + direction adjusts and auditions, X listens.
Move above the slot row to the editor tabs, then Left/Right (or A) switches modes. L1/R1 still
switch banks. One continuous edit is one Undo; Undo, slot navigation and mode changes invalidate
an old pointer drag. A held across a mode switch must be released before it can edit again.

The console-inspired face uses a neutral shell, olive readout and burgundy controls; Contrast
uses the workspace's high-contrast roles. Short panes show one control with 48dp Previous/Next
targets; instrument slots remain selectable on the left, with paging rather than tiny rows.

![Game Boy pulse Sound face on Pocket DMG](media/gb-pulse-sound-dmg.png)

Actual Pocket DMG capture, with a transient recovery-status message still visible.
[Custom advanced patch, untouched in Sound](media/gb-pulse-custom-dmg.png).
These are not Galaxy Fold8 captures or evidence of physical two-thumb comfort.

**Verified for preview.2:** 1,154 shared-core tests and 24 Android host tests; signed APK
installed and exercised on reserved Pocket DMG. Actual Activity key/touch dispatch covers
musical edits, X audition, mode switching, grouped Undo, pointer invalidation and exact Custom
patch preservation. All three themes were captured. Six native WAV renders confirm fading,
steady and swelling loudness, steady/falling/rising pitch, and the rising sweep's eventual cutoff.
Live diagnostics: zero xruns and driver faults in this short scenario; max callback 0.923 ms.
This is not a long-session or physical speaker-listening claim.

The eight original private recovery/workspace entries were cold-restored and independently
byte-verified; test APK and UUID-owned external artifacts were removed, timeout restored to
60000 ms, and DMG returned Home/asleep. Production app data and `/ThorTunez` were untouched.
Builds were serialized and bounded; no emulator or local Kotlin/Native linking was used.
No new Deck/Brick runtime proof is claimed for this Android preview.

APK: `thor-tracker-0.42.0-touch-preview.2.apk`, code 57, arm64-v8a and x86_64.
SHA-256: `5fbbf32f6185ebdb8e058f3124e5dd77e7301f800d79d5454504a5988300bdc8`.
Install over the existing preview, without uninstalling. Review the musical controls and
Sound → Advanced workflow before expanding this approach to Wave, Noise or other chips.

### Previous 0.42.0-touch-preview.1 verification

The production-signed preview passes **1,130 shared-core and 24 Android host tests**. The final
segmented-cell boundary correction also passes the targeted Game Boy panel suite and signed build.
The complete pulse/wave/noise [Game Boy panel](https://github.com/esko997/thortracker/blob/gameboy-panel-review-042/docs/INSTRUMENT_PANELS.md) is present; other instrument
panels remain unchanged. OFF insertion advances by the configured entry step, Open asks before
replacing the working song, and the editor canvas keeps its first touch pointer through reordered
IDs and either lift order.

On reserved Pocket DMG:
- A **160-round** actual multitouch run covers held editing, multi-owner directions,
  selection/copy/paste, cancellation and reversed lifts, with 640 audition calls and 27
  first-pointer probes. Zero xruns/driver faults; max callback 6.588 ms.
- The final Game Boy panel passes 19 parameter paths, 41 history cases, held-Edit Clear,
  two pointer cases, 72 theme/zoom/size configurations and eight compact touch-navigation cases.
  These include synthetic 360×270 cover and 720×352 inner editor viewports, not phone captures.
- The permanent first-pointer regression passes against the final APK.
- Actual built-in-controller sleep/wake and four second-gamepad add/remove cycles verify automatic
  dock hiding/showing and reclaimed editor height on the final APK.

The DMG's built-in pad normally hides the dock. The long touch scenario explicitly forces it visible
as a **test fixture only**; production has no show override. Shell input-disable permission is denied
and was not bypassed. Every completed run independently cold-verifies eight restored private
recovery/workspace entries and removes its owned test package. Detailed harness corrections and
stable/native evidence are in [Verification](https://github.com/esko997/thortracker/blob/gameboy-panel-review-042/docs/VERIFICATION.md).

APK: `thor-tracker-0.42.0-touch-preview.1.apk`, code 56, arm64-v8a and x86_64.
SHA-256: `1f8881cc2e0b32442242a19541907fd141487f07df97c40c46a670b93c791775`.
Update the existing preview in place; do not uninstall it and lose its private/app-scoped files.

### Previous 0.41.0 verification

The production-signed, arm64 preview passes **19 host tests** and **13 real Android scenarios
on reserved Pocket DMG**. The latter inject multi-pointer MotionEvents through Android's input
dispatcher, using sparse physical-range IDs 0/2/4, plus hardware KeyEvents. They cover:

- Held two-finger note adjustments and one grouped Undo, direction repeat, rolling releases,
  mixed physical/virtual owners, cancellation and Home/background/foreground.
- Held audition forwarded to native audio, native preparation and advancing playback. These
  are not physical speaker-listening or phone latency measurements.
- Both trigger layers, real touch-driven binding/conflict replacement and cancellation.
- An actual notification-panel interruption: capture is canceled and the next unmodified R3
  binding does not inherit an invisible L2. OEM window animations are disabled for this probe
  and all three original animation settings are restored exactly afterward.
- Native View measurement/rendering at all five cover/inner ratio profiles above, every dock
  target's bounds, and held-input cancellation through the real resize callback.
- Real provider save/open, JSON MIME and truncating overwrite. The actual Save As picker opens
  directly on a uniquely marked preview root without choosing it from the sidebar.

The same fast menu-tap scenario failed before the shared popup fix and passes afterward:
starting a zero-distance scroll at the top of a menu intercepted the following tap. The fix
avoids that animation; the test was not slowed down to hide the failure.

Evidence is retained under
`~/.local/state/thortracker/recovery-20261109/workflow-touch-041/preview-fold8-verified/`.
The capture regression's before/after evidence and the 28-test production Android suite are
recorded separately in `docs/VERIFICATION.md` on the stable integration branch. Disposable
instrumentation is not shipped in the application. Production app data and `/ThorTunez` were
not touched; the preview test package was removed and DMG settings restored.

Build only this branch with the existing signed-release procedure, one bounded build at a
time through `tools/build.sh --max-workers=1`. No emulator or local Kotlin/Native linking is
needed. Do not merge the preview application identity or touch shell into production before
ergonomics review.

### Phone delivery and review

The review APK uses a separate GitHub prerelease and immutable source tag, not the production
latest release. A send-only Syncthing folder, `ThorTracker-Preview` (`thortracker-preview`),
is offered only to the already paired phone. Accept that folder on the phone to receive APKs;
this is APK delivery, **not song synchronization**. Installation remains a manual Android step.

Review both folded and unfolded:

1. A + directions for editing, X + directions for listening, Y selection, Select+B cut,
   Undo/Redo, and L2/R2 separately and together. Check thumb reach and accidental presses.
2. Drawer view changes, scale selection, dense effect/instrument editing, zoom and themes.
3. Save/Open and WAV export using disposable songs and explicitly chosen backup destinations.
4. Fold/unfold, rotate, open the phone keyboard, background/return and attach/detach a real
   controller. No invisible hold, repeated edit or audition should survive an interruption.

### Explicit preview limitations

This is a real editing/audio/file preview, not the silent HTML study. It intentionally retains
shared controller down-to-write and field-specific Clear semantics; it does not implement
the landscape proposal's release-to-write pad, OFF confirmation or whole-cell editor.
The canvas still lacks virtual per-cell TalkBack nodes and small direct canvas targets are
unchanged. Dock/drawer controls have semantic labels and 48 dp targets; existing native menu
rows remain 46 dp. This is not a complete accessible tracker redesign.

Actual Fold8 density/zoom/insets, physical two-thumb comfort, fold transitions, IME fit,
TalkBack, audible latency and full controller/dual-display parity still need review. Ratio
renders on DMG are not phone captures. The provider is a flat preview root without folder
creation/rename, and uninstalling the preview deletes its app-scoped files. No production
data migration or production touch rollout is part of this branch.


## Initial landscape study

The original study recommended one tracker and one document model with an explicitly selected
**Touch layout** workspace presentation. It explored a side editor rather than an onscreen gamepad,
separating **selecting a whole pattern cell** from **editing its fields**:

- Pattern on the left; a contextual Note / Sound / FX editor on the right.
- Persistent transport, view selection, Undo/Redo and Actions. Do not hide essential controls after an inactivity timeout.
- Touch-sized rows and controls; horizontal lane scrolling and visual collapse handle channel count instead of squeezing every lane onto the screen.
- Write and Listen are explicit note-pad modes. No modifier chords and no essential command hidden behind a long press.
- Preserve the current dense controller layout. Touching a handheld must not unexpectedly switch its layout or controller-focused display.

The tradeoff is deliberate: fewer visible rows/channels in exchange for reliable direct input. Review that tradeoff before implementing it.

## Reopened candidate: portrait controller overlay and drawer

The user's current proposal is to retain the familiar D-pad/A/B/X/Y workflow on a phone rather than
design another editor. Portrait could place a dedicated control area below the tracker, avoiding a
literal overlay on the selected row. Compare that geometry with landscape before choosing either.
This is a discussion candidate, not a change to the silent browser study below.

- Reuse shared logical input/actions and their existing legality, history and audition rules.
  Test multi-touch holds explicitly: held A/X plus D-pad and trigger chords need separate pointer
  ownership. Two thumbs cannot comfortably hold three independent targets, so evaluate visibly
  latched modifiers rather than assuming L2+R2 chords translate unchanged.
- Keep a visible drawer button for views, scales, project actions and settings. A swipe can supplement
  it, but Android's left-edge Back gesture makes a swipe-only route unsafe. Opening the drawer must
  release pending held controls and retain the editor's location.
- Let users explicitly choose/show the controls. Controller connection can offer a suggestion, not
  strand someone with no visible controls when a paired controller is asleep or out of reach.
- Preserve held-audition release on pointer cancellation, dialogs, keyboard appearance, focus loss,
  backgrounding and controller changes. Never leave a virtual trigger or note latched invisibly.
- Compare thumb reach, minimum touch targets, four-effect lanes, selection/clipboard/Undo, instrument
  editing and native file picking on a real phone. Retaining controller semantics is less new editor
  logic, but is not proof of comfortable touch input or accessibility.

The original side-editor study remains a reference for context preservation and explicit actions;
its single-contact note pad is not evidence that a multi-touch controller overlay works.

## Review artifacts

- [Interactive, self-contained browser study](prototypes/touch-landscape.html). Open the HTML file in a browser; it also works without a server or network connection.
- [844 × 390 landscape capture](prototypes/touch-landscape-844.png).
- [640 × 360 compact capture](prototypes/touch-landscape-640.png).
- [Effect editing capture](prototypes/touch-landscape-fx.png).

The browser study is **silent and isolated**: Play moves a visual cursor; held notes show gesture feedback but produce no sound. It neither loads nor saves `.thor` projects. It is not an engine, Android implementation or chip emulator.

Implemented in the study: whole-cell selection and scrolling; note/OFF/clear entry; Write/Listen; step and octave; representative sound choices; one GB effect slot; Undo/Redo with selection; single-cell copy/paste; visual collapse; independent playhead/selection; release/cancel and single-contact ownership.

Not simulated: arrangement, range-selection handles, full instrument synthesis controls, multi-effect backends, tuning/scale adaptation, cross-order advance, project/file IO, native accessibility, real audio or Android lifecycle. Those are specified below rather than represented by nonworking buttons. The browser's portrait reflow is only a viewing convenience, not a proposed portrait product mode.

### Five-minute walkthrough

1. Tap an empty row in Pulse 1. Tap D, E, G. Notes advance by Step; set Step 0 to overwrite in place.
2. Undo: the edit and selection return together. Redo restores them.
3. Choose Listen only; hold a pad, then release. No note is written. In Write, touch a pad and slide outside it before releasing: no write.
4. Select a populated cell. Sound changes its instrument, not its note. FX can enter `037` or the level-only `C0F` example. The prototype only exposes a small valid subset, not the complete GB effect catalog.
5. OFF uses GB's single effect slot (`E00`). The study **proposes confirmation before replacing an existing effect**; current Pattern entry instead refuses and requires clearing the effect first. FX explains the occupied slot; clearing OFF releases it. Arpeggio is unavailable on Noise, including a cross-lane paste.
6. Actions → Collapse lane, then expand it. Copy/paste includes the sound/effect even when they are hidden. Play is silent; selecting another row does not move the playing cursor.

## Layout choices

### A. Side editor — recommended starting point

```text
┌ Pattern / views ─ project / files ─ Undo ─ Redo ─ Play ─ Actions ┐
│ row  Pulse 1  Pulse 2  Wave …        │ Note │ Sound │ FX         │
│ 00   C-4 01 …                      │ Pulse 1 · row 00 · C-4    │
│ 01   …        scrollable pattern    │ C  C# D  D# E  F          │
│ 02   …        and lanes            │ F# G  G# A  A# B          │
│ 03   …                             │ Oct− 4 Oct+  OFF  Clear   │
│ 04   …                             │ Write / Listen   Step 1   │
└ contextual status; errors remain associated with the edited cell ┘
```

Study geometry: 320 CSS-pixel editor; 48-pixel interactive targets; 56-pixel transport. At 844 × 390, the four representative GB lanes fit beside the editor. At 640 × 360, fewer lanes fit and the grid scrolls horizontally; the note controls remain reachable. Native design should use **48 dp targets**, not literal physical pixels. CSS geometry is not proof of physical-phone ergonomics.

The two-row chromatic pad is a space/target-size experiment, not a commitment to replace the existing piano keyboard. A conventional piano gives familiar pitch geometry but needs either more width or fewer simultaneously visible keys. The pad also must not imply every backend/tuning is twelve-tone: production note choices must come from the selected scale/tuning and channel capability.

Full multi-effect lanes are intentionally not crammed into the pattern. A whole-cell tap chooses location; FX exposes ordered slot selectors only up to that backend's capacity. A collapsed lane can still be edited through the dock without expanding it.

### Alternatives considered

| Layout | Benefit | Cost / decision |
| --- | --- | --- |
| Bottom keyboard/editor | Familiar piano layout; maximum channel width | Consumes scarce landscape height. Keep as a candidate for a performance-focused Keyboard view, not the default Pattern editor. |
| Side editor | Stable edit location; pattern retains height; large controls | Reduces visible channels. Recommended first; needs easy horizontal navigation and left-handed mirroring. |
| Full-screen value sheet | Room for dense FM/operator or exact hex controls | Hides context. Use for complex editors/IME pressure, with a pinned channel/row summary and explicit return, not for every note. |
| Virtual gamepad/control area | Reuses familiar controller actions and chords | Reopened by the user as a portrait/landscape candidate. Requires multi-touch, modifier ergonomics, visibility and cancellation studies; not implemented. |

Before native layout is fixed, also measure 18-voice/large-channel arrangements, multi-FX lanes, cutout/gesture-navigation insets and enlarged text. The GB study does not prove those fit. At short usable heights (including the IME), switch deliberately to a contextual sheet; do not shrink controls below the target.

## Interaction contract to review

### Selection is not mutation

A normal grid tap selects a **whole cell** and shows its note/sound/FX in the dock. A drag scrolls rather than writing or beginning a block selection. Both axes need explicit scrolling support; the production pattern currently only has touch vertical scrolling and small lane arrows.

Selected edit position and playing position remain visually distinct. Touch scrolling must not immediately snap back to playback. Follow is an explicit control, and playback must not steal an active field editor.

### Write, Listen, OFF and Clear

**Proposed for phone step entry:** down begins audition/feedback; valid release writes exactly one note and advances once. Dragging away or cancellation writes nothing. Listen never writes or advances. Initial implementation need not support polyphonic multi-touch; ignore extra contacts while retaining the owning pointer ID.

**This differs from current Keyboard Write:** it writes and advances on down; glide only changes audition; cancel ends the gesture but retains the note already written. The spike deliberately explores the safer release-to-write alternative. We should decide whether it is worth changing phone entry before any shared Keyboard behavior changes. Audition onset can remain on down; it does not have to wait for the write.

- OFF and Clear must be separate, labeled commands, not the controller's context-sensitive B cycle.
- OFF must ultimately use the actual backend representation. Production stores `Cell.CUT`; GB lowers that to `E00`, while OPL3 releases rather than immediately silencing. Current one-column Pattern OFF replaces the whole cell with `Cell(note = CUT)`, dropping its instrument, and refuses if an effect is already present. The study's literal OFF-plus-`E00` fields are only a display stand-in, not the project representation; it retains the sound and offers replacement confirmation. Those differences are proposals, not existing behavior.
- For review, the study's Clear removes the note only and preserves sound/FX (except removing OFF also removes its `E00`). Current controller clear also clears the note's instrument. Final field-specific Clear semantics need agreement; never silently change the controller path.
- Sound 00/legato needs an explicit production choice distinct from erasing the note. The study's first four sounds are a subset of each new-song bank; sound-bank editing is a separate operation from assigning a sound to a row.
- One successful entry is one shared undo transaction. Failure does not advance or add history. Scale/step advance must retain current cross-order/played-length semantics; the study stops at its single pattern's end.

The JavaScript cell/history/legality examples exist only to make the interaction study inspectable. They are not reusable editor code or parity tests. Native work must delegate to the existing shared entry/validation/history rules; do not port these stand-ins into another implementation.

### Effects remain tracker effects

Present a musical name **and the actual hex command**, not a second automation format. The effect chooser is filtered by system and channel. Parameter editors can be numeric, nibbles, enums or useful musical controls, but lower to the existing row command.

GB has one slot; OPL3 has up to four, evaluated in column order. Show ordered FX slot selectors, not arbitrary extra slots on every chip. GB OFF conflicts with its slot; globally acting effects must say they are global. Show quantization where applicable: for example GB Wave volume groups map to 25/50/100%, not sixteen independent amplitudes.

Do not bypass the new PCE assignment preflight or reject repair edits merely because another field contains an unfinished effect. Imported invalid data needs an editable, located error, not silent relocation/clamping or an uneditable modal trap.

### Range editing

Add an explicit **Select range** action. Tap the first cell, then the last; show the marked rectangle and endpoint controls. Drag handles are optional accelerators, not the only way to resize. Keep scroll and selection modes visibly distinct. Copy/Cut/Paste/Duplicate apply existing whole-cell/block semantics, including hidden effects in collapsed lanes. Offer Insert/Delete row separately.

This is a proposal, not implemented in the browser study. Auto-scroll at edges and cut/paste bounds require native verification.

### Continuous parameters and held sound

A slider gesture owns one existing history token, not one undo item per move. Cancellation releases held sound and closes the transaction; it is not automatically an Undo of already-applied instrument changes. A modal, display detach, pause, input-owner change or lost pointer must release audition exactly once. A second touch must never replace the first pointer's coordinates or leave a note held.

No phone-specific instrument model: existing chip parameters, ranges, curated values, restrictions and advanced pages remain authoritative. Bespoke synth panels can be designed later on top of this working touch interaction layer.

## Complete phone workflow coverage

| Task | Current source behavior | Proposed phone interaction |
| --- | --- | --- |
| Switch views / transport | Native toolbar/menu exists; toolbar hides after six seconds; some controls disappear below 720 dp | Persistent compact transport and visible view picker; Actions for secondary operations, not for every note |
| Enter notes | Pattern selects; Keyboard Write enters notes | Pattern dock with explicit Write/Listen, step/octave and error feedback |
| Sound assignment | Touch can select bank entries/entry sound, but existing-cell reassignment needs controller editing | Sound tab explicitly assigns to the selected row; separate action to open instrument design |
| FX / OFF / clear | No direct complete Pattern touch editor | Field-specific controls and backend-aware FX slots; exact hex retained |
| Range / clipboard | Copy/cut/paste menus exist; no touch start of arbitrary MARK range | Explicit selection mode plus start/end cells; existing clipboard commands |
| Arrange song | Song has Repeat/New/Clone/Delete/Move/Loop/Park chips | Keep these, add a touch picker to assign an arbitrary existing pattern to an order |
| Tempo / metadata / chip settings | Touch often only selects settings; controller adjusts values | Tap opens a native/shared value editor using the same setting rules; show ticks/row or actual backend unit, not invented universal BPM |
| Instrument design | GB sliders work; representative FM touch mostly selects a row/operator | Direct parameter/value controls using each system view's semantics; all required parameters reachable without a modifier |
| Sketch / Keyboard | Existing held audition and insertion paths | Preserve these workflows, expose every adjustment as an explicit control; not replaced by the small Pattern pad |
| Save/Open/Save as | Native Android document-provider workflow already exists | Reuse it, including asynchronous snapshot/generation protection and error handling |
| Native/WAV export | Existing options/progress/Save/Discard flows | Reuse; touch must reach every choice, validation error and cancellation |
| Repair / validation | Check is scrollable text; current-cell diagnostics exist | Editing controls must repair every reported field; a navigable error list is desirable, not a prerequisite to understanding an error |
| Undo/Redo / recovery | Shared session history and persistence | Same document history, selection restore and recovery, no parallel touch-only store |

No full phone implementation should be called complete based only on the Pattern pad. The acceptance walkthrough must create, arrange, sound-design, save, reopen and export a song without a controller, on GB **and** a complex FM backend.

## Source findings and implementation boundaries

These are source-inspection findings, not claims of fresh Android runtime testing in this spike. Paths below are relative to the repository.

- `core/src/commonMain/kotlin/org/thortracker/ui/Theme.kt:56–68` and `PatternPainter.kt:76–104`: current row height is about **10.8–14.4 dp**, far below the proposed direct-touch targets.
- `ui/PatternPainter.kt:923–1030`: tap selection and vertical scrolling, but no direct Pattern value editor or touch MARK initiation.
- `ui/KeyboardPainter.kt:167–230`: current down-to-write, held audition/glide and cancellation semantics.
- `ui/PaneView.kt:184–262,383–414`: retained painter/gesture ownership, selection following and semantic input boundary. Preserve this rather than writing to the document from Android widgets.
- `editor/ViewController.kt:744–748,811–870,1234–1323`: entry legality, effects, clear and keyboard operations. `EditorSession.setCell()` alone does not supply every interactive legality check.
- `editor/EditorSession.kt:128–209,410–550,564–655`: shared history/selection, clipboard, row and arrangement operations. Arrangement commands also repair loop/parking/jump references; do not splice order arrays independently.
- `system/SoundSystem.kt:338–345`, `system/gb/GbSystem.kt:64–65`, `system/gb/HugeRules.kt:61–114,300–354`, `system/opl3/Opl3System.kt:229–238`: effect capacity, OFF representation and system/channel-specific legality.
- `system/gb/SoundParams.kt:6–79`, `system/fm/FmInstrumentPainter.kt:522–537`, `system/opl3/Opl3InstrumentView.kt:179–229`: parameter metadata and the difference between touch selection and actual FM parameter editing.
- `app/src/main/java/org/thortracker/ui/TrackerView.kt:34–95`: focusable-in-touch-mode behavior and single-contact bridge. No active pointer-ID tracking or independent secondary-contact handling currently exists.
- `app/src/main/java/org/thortracker/MainActivity.kt:285–293,350–410,655–675,1029–1064,1113–1169`: modal/lifecycle cleanup, immersive layout, distinct touched-display/controller-focus rules and native IO. Touch commands must target their originating pane rather than accidentally editing the controller-focused other display.
- `app/src/main/java/org/thortracker/ui/ShellToolbar.kt:64–110,218–250`: 48 dp bar, 40 dp buttons, six-second reveal timeout and narrow-layout hiding.

For abbreviated `ui/`, `editor/` and `system/` entries above, the prefix is `core/src/commonMain/kotlin/org/thortracker/`.

### What can be reused; what cannot simply be wired up

Reuse semantic `Action` commands for history/clipboard and the existing popup/file contracts. Reuse the immutable song, entry memory, system validators and backend lowering. Do **not** generate Android keycodes or emulate L2/R2 chords for touch buttons.

Some value-entry behavior lives in private controller methods and needs careful extraction into shared semantic operations before direct touch widgets can use it. This is not permission to add a second set of note-entry rules. Keep undo grouping, assignment refusal, document-bound entry memory and selection restoration identical across input paths.

Native work also needs explicit safe-inset/IME behavior and accessibility. The current canvas does not expose individual cells/keys as virtual accessibility nodes; native menus alone do not make the editor accessible. Target large text, semantic selected-cell actions and reachable focus order. The HTML's native button semantics do not prove Android TalkBack support.

## Decisions for our review

1. **Side editor vs bottom piano:** is the reduced row/channel overview worth reliable 48 dp targets? Recommended side editor first, with optional mirroring; compare on a real phone before freezing geometry.
2. **Release-to-write vs existing down-to-write:** recommended release for deliberate phone step entry, audition on down. No existing behavior changes until agreed.
3. **Whole-cell selection vs tiny subfields:** recommended whole-cell tap and explicit dock tabs. Dense direct-field taps may remain an optional expert/controller-layout behavior.
4. **Clear semantics:** recommended explicit field-specific actions, not the overloaded controller cycle. Decide whether clearing a note should retain its sound assignment before implementation.
5. **OFF conflict and sound handling:** retain the existing refusal/clear-first behavior, or approve a confirmation that replaces the occupied effect? Decide separately whether OFF should retain the sound assignment. Until agreed, production behavior stays unchanged.

No decision is required to use or inspect the study. These are the points to resolve together before approving native work.

## Implementation sequence only after review

1. Establish shared semantic entry/value commands and gesture ownership without changing controller behavior.
2. Implement the selected Pattern touch presentation and lifecycle/accessibility handling; verify note/sound/FX, selection, undo and legality.
3. Finish Song settings/arrangement, FM parameter editing and all modal/file/export paths.
4. Verify complete controller-free composition on a phone; run handheld/dual-screen regression scenarios and backend/export parity.

No version bump, app build, device installation or release belongs to this proposal.

## Spike verification

Executed the actual HTML in Chromium, including mobile/touch emulation. This is browser-model evidence, not native-phone or human ergonomics evidence.

- Inspected the captured Note and FX surfaces. Checked **844 × 390, 740 × 360, 640 × 360 and 960 × 540** viewports: no page overflow, no internal Note-editor scrolling, and all measured transport/tab/pad/note-tool/mode/step targets at least **48 × 48 CSS pixels**. Fixed the compact Undo/Redo width after the first measurement found it below target.
- Exercised note write/step, Undo with selection restoration, Redo, Listen without writing, down-preview/release-write, drag-off cancellation, hex edits, explicit OFF/effect replacement confirmation, OFF slot occupancy, Noise arpeggio rejection, whole-cell copy/paste, collapse/expand preservation, and selecting while the silent playhead runs.
- Sent browser touch events, including `touchCancel` and overlapping contacts: cancellation left the grid unchanged; the first contact retained ownership and only its note was written.
- At 640 × 360, actual touch swipes scrolled the grid horizontally to 184 pixels and vertically to 166 pixels without changing selection.
- Opened the self-contained `file://` HTML and entered a note successfully. No web dependencies or server are required. No browser JavaScript errors were recorded.
- No production files, Android APKs, native binaries, devices or user music files were changed. The roadmap link, proposal, isolated HTML and three review images are the only intended repository changes.

Still unverified: physical finger comfort/reach, handedness, TalkBack/font scaling, Android IME/insets, real audition latency, dense FM layouts, background/native lifecycle, and controller/dual-display parity after an eventual implementation.
