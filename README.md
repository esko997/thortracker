# Thor Tracker

A controller-first Android tracker for Game Boy / Game Boy Color music, designed around the AYN Thor's two displays. Projects use a versioned `.thor` document; export produces hUGETracker `.uge` v6 files.

Playback runs the actual pinned hUGEDriver 6.1.3 inside binjgb, through Android AAudio. There is no runtime compiler or separate approximate sequencer. The editor keeps the song in memory; new snapshots are prepared away from the audio callback and applied between driver ticks without restarting the song.

## Build and install

Requires JDK 17, Android SDK platform 35, build tools, NDK `27.0.12077973`, and CMake `3.22.1`. The Gradle wrapper uses Gradle 8.9.

```sh
./tools/build.sh
# APK: app/build/outputs/apk/debug/app-debug.apk
adb -s YOUR_DEVICE install -r app/build/outputs/apk/debug/app-debug.apk
```

`tools/build.sh` uses the host JDK when available, otherwise the workstation's `ubuntu` Distrobox. It serializes Gradle with the shared workstation build lock. A conventional environment can instead run `./gradlew testDebugUnitTest assembleDebug` with `ANDROID_HOME` set. Both arm64-v8a and x86_64 are included; Android 8 or later is required.

Generate a sample project and export:

```sh
./tools/build.sh exportSamples
# app/build/samples/thor-groove.thor
# app/build/samples/thor-groove.uge
```

The installed app opens **Thor Groove** on a first launch. Subsequent launches recover the working document. **File → New blank song** starts a new composition.

The verified delivery is `dist/thor-tracker-debug.apk`. `examples/` contains **Thor Groove** and **Controller Study**, each as `.thor` and `.uge`; the latter was entered through the running Thor UI and controller event path. See [verification results and remaining physical checks](docs/VERIFICATION.md) before treating measured engine latency as acoustic latency.

## Views and displays

Each display has one full-size view: **Pattern**, **Song**, **Instrument**, **Wave**, **Keyboard**, **Sketch**, or **Help**. There is no designated “top-screen view” or “bottom-screen view.” Both displays can show the same kind of view and edit the same document. On a single-screen device, switch between views with the view button.

- **View button:** choose a view on that display. Choosing one pins it.
- **Pinned / Context button:** open behavior and layout options.
- **Pin:** prevent automatic changes of view kind.
- **Follow selected item:** follow the other view's published selection.
- **Lock to this item:** retain the current channel, instrument, or wave rather than following selection. Changing the kind of editor releases its old subject lock; swapping views preserves it.
- **Swap:** exchange complete view states, including cursor and scrolling.
- **Focus / R3:** choose which display receives controller input. Touch does not silently move controller focus. The focused view has an amber border and title marker.
- **Follow playhead:** optional in the pattern view; editing can stay at its cursor while playback continues elsewhere.

Preset layouts are Pattern + Keyboard and Pattern + Instrument. View layout, behavior and controller bindings persist between launches. Undo/redo belongs to the document, not the display.

The toolbar is hidden over the full-height editor rather than reserving a row. **Select+Start** reveals it and gives it controller focus: Left/Right choose, A activates, and B/Back returns to the editor. It stays visible while controller-focused. Tap or pull the small top-centre handle for touch use; that reveal hides after six idle seconds. Neither transition moves the grid. On the narrower display, **Swap**, **Undo/redo** and **File** remain available through **Menu**.

App menus, confirmations, rename fields and errors are in-app overlays on their originating display. Up/Down choose, A activates, B/Back returns one level, and L1/R1 page long menus. Android's document picker and keyboard remain system-owned.

Short cell and key afterglows, channel activity lamps, and focus/view transitions provide feedback without moving the grid or obscuring text. Animations settle completely when idle and honor Android's animator-duration setting, including disabled animations.

## Controller reference

Every control is rebindable in **Menu → Controls**: one row per control, grouped by area, with its current binding on the right. Choose a row, then press the new button, or hold **Select, L2, R2 or L3** and press a button for a chord. Held controls (D-pad, Edit, Clear, Listen, Select block) take a single button. Assigning a binding another control uses asks first and leaves that control unbound; the D-pad, Edit and Clear can never be left without a button. Menu and Back are fixed, so the screen is always reachable. The hint bar, held-modifier chord hints, popups and Help show your bindings.

Bindings survive app updates. Uninstalling or clearing app data removes them, so use **Export keymap…** to save a `thor-tracker-keymap.json` anywhere (e.g. Download) and **Import keymap…** to restore it, or to copy it to another handheld. The file is readable JSON (`"UNDO": "L2+A"`). Remaps from earlier versions are converted automatically. The table below lists the defaults.

| Input | Action |
| --- | --- |
| D-pad | Move through fields, rows or tools |
| Hold A + D-pad | Change the selected value; horizontal fine, vertical coarse |
| Tap A | Enter the last note/value, or activate a command |
| Tap B | Clear the selected field; on an empty pattern note, place OFF |
| Hold B + D-pad | Move or jump without clearing on release |
| Hold X | Audition without writing |
| Hold Y + D-pad | Mark a pattern block |
| L1 / R1 | Change channel, instrument bank, wave or help page |
| L2 / R2 alone | No action; chord modifiers only |
| L2 + A / R2 + A | Undo / redo |
| L2 + X | Swap complete views, including their cursors and scrolling |
| Select + X / A / B | Copy / paste / cut |
| Select + Y | Duplicate |
| Select + L1 / R1 | Lower / raise entry octave |
| Select + Up / Down | Insert / delete a row in the channel or selection |
| L2 + Right | Open/close the current pattern's root/scale/length panel |
| Start | Play from row 00 of the current pattern's order, or stop |
| L2 + Start | Play from the current cursor, or stop |
| Select + Start | Reveal/focus the toolbar, or return to the editor |
| R3 | Switch controller focus between displays |
| Menu / Back | Application menu; Back closes a popup or focused toolbar first |

A held adjustment or wave-drawing gesture is one undo step. Overlapping gestures on the two displays share a step that commits after both end. Undo during a held controller adjustment ends that step; further changes start a new step. Undo during a touch drag ends its edits until the finger lifts. Touch drags keep their original instrument or wave, and selection/playhead following waits until the gesture ends. Opening a menu or leaving the app ends active gestures and releases audition notes. **Menu → Play from song start** explicitly starts at order 0, row 0; it is not a toggle.

Hold the modifier **before** pressing its chord button. While Select, L2, R2 or L3 is held, the focused display's hint bar shows only that modifier's chords; holding two shows that no chord applies. With a modifier held, a button without a chord does its ordinary job. An A press that precedes L2/R2 remains an ordinary edit; note entry is not delayed waiting for a possible chord. Consumed chords suppress repeats and releases, even if the trigger is released first. Undo/redo and screen swap also work over app popups without choosing their highlighted item. Bare L3 is also unbound.

### Enter a phrase

1. Choose Pattern. Navigate to a note field, hold A and use the D-pad to select a pitch. The adjacent sound field selects an instrument.
2. Tap A on subsequent empty rows to repeat the last note. **Menu → Entry step** sets the row advance.
3. Alternatively, put Keyboard on the other display, select **Write** with Y, and enter notes there. **Listen** mode auditions without changing the pattern.
4. Hold Y and move to mark a phrase; Select+X copies it, Select+A pastes it. L2+A undoes a mistake.
5. Press Start to hear the phrase, edit while it plays, and stop/start or seek without saving or exporting.

**Menu → Song details / tempo** opens the Song view with **Ticks per row** selected. Hold A+Left/Right to change it; a lower value is faster. The view also edits title, artist, arrangement and loop point, with repeat/new/clone/delete/move commands. Instrument edits affect later note triggers. The Wave view edits the 32 four-bit samples directly; held drawing and touch drags are grouped for undo.

The keyboard's base octave is limited to 7. Keys above B-8 are dark and unavailable rather than silently clamped to another pitch.

### Pattern scales

Tap **Scale** in the Pattern header or press **L2+Right**. Up/Down chooses Root, Scale or Length; Left/Right changes it; B resets that field and A/Done (or L2+Right again) closes. The root strip and arrows are also touchable.

Chromatic is the default. Every other scale constrains Pulse/Wave D-pad note entry to the chosen root's scale: Major, Natural/Harmonic/Melodic minor, Dorian, Phrygian, Lydian, Mixolydian, Locrian, Major/Minor pentatonic, Blues, Whole tone, Phrygian dominant, Double harmonic, Hungarian minor, Hirajoshi and Miyako-bushi (In). Horizontal edits visit successive scale notes; vertical edits move an octave, snapping an existing out-of-scale pitch in the edit direction. Changing the guide never transposes written notes. Noise and the piano keyboard remain chromatic; effect parameters remain raw hUGE values.

The guide belongs to the pattern, so repeated orders share it. It is undoable, saved in the project and preserved by pattern cloning/whole-pattern paste. Block paste still copies cells only. Scale metadata itself changes neither preview bytes nor `.uge` output.

### Pattern length and polyrhythms

The same panel's **Length** row sets how many rows the pattern plays, 1–64. Press Down to reach it; Left/Right changes it by 1, L1/R1 by 8, B returns to 64, and the ◀ ▶ arrows are touchable. Every order that uses the pattern plays that many rows and then moves to the next order. Rows past the length are hidden, not deleted: lengthening the pattern brings them back, and insert/delete row only shifts the played rows.

Lengths apply to all four channels together; the GB driver has no per-channel position. For polyrhythms, combine pattern lengths in the arrangement (for example 48 then 20), or write different phrase lengths in one pattern whose length is their common multiple. The Song view's order list labels short patterns with their length and draws their density strips proportionally shorter.

For preview and export, hidden rows are blank and the last played row receives `D01` in the first free effect slot (unless it already has `Dxx` or `Bxx`). A song check error asks you to free one effect slot if that row uses all four. Whole-pattern copy/paste and pattern cloning keep the length. `Dxx` breaks that would land on a hidden row of the next pattern are reported.

### Sketch: explore a key without writing

Reveal the toolbar, choose the current **View** button, then **Sketch**. If you opened the toolbar with Select+Start, press B/Back after choosing the view to return controller focus to the editor. Sketch can be pinned on either display, swapped with L2+X, or opened independently on both.

- **Up/Down** chooses settings, chords or notes; **Left/Right** chooses within that row.
- On settings, **hold A + D-pad** to change root, scale, tonal channel, sound or octave. Touch the left/right half of a setting to decrease/increase it.
- **Hold A or X** on a chord to hear a repeating root-position arpeggio; on a note to sustain it. Release to stop. Touch and hold a pad, or slide between pads, for the same preview.
- **L1/R1** chooses Pulse 1, Pulse 2 or Wave. Noise is intentionally excluded from this tonal reference.
- **Y** or **Pattern key** returns to the pattern's root/scale. Until you change it, Sketch follows that guide; after a local change, the pattern's guide and all written notes remain untouched.

Seven-note scales show their stacked-third triads; stacks that are not major, minor, diminished or augmented (e.g. double harmonic's V) are left out. Other scales (pentatonics, blues, whole tone, Japanese scales) show only standard triads made entirely of their notes. Chromatic offers twelve note pads and asks you to choose a scale for a chord shortlist. Notes show pitch and interval degree; the selected chord marks its root, third and fifth. Degrees and roman numerals use major-scale-relative intervals (`b3`, `#4`, `bIII`, etc.); pitch names use the tracker's sharp spelling.

Sketch is **listen-only**: it never inserts notes, consumes undo history or changes project/export bytes. Local key/cursor settings belong to the device workspace and survive reopening. The preview uses the selected song instrument through hUGEDriver and temporarily borrows that channel while the other channels continue. Chord notes repeat every 180 ms as a listening reference, not a tempo-synchronised authored sequence; voicings near the top of the GB range shift down together rather than clipping notes. Menus, view swaps, focus/lifecycle changes and release cancel held previews.

Phrase generation/variations, insertion and a circle-of-fifths reference remain future work.

### Arpeggio and OFF

The effect-type picker distinguishes **no effect** from selecting **0 (arpeggio)**. Choosing 0 displays `000` and its explanation without changing the sound. Move to the value field and set `xy` to produce `0xy`; for example `047` alternates the note, +4 and +7 semitones. `000` itself remains no effect.

**OFF** immediately silences the channel, with no release tail; it uses that row's effect slot as `E00`. For stutter/retrigger, repeat a note with a nonzero instrument on successive rows, adjusting ticks per row if finer spacing is needed. The supported hUGE effect set has no dedicated retrigger command; that is a driver/editor limitation, not a prohibition in the GBC sound hardware.

## Sound and export rules

The first backend is explicitly **GB/hUGE**: two pulse channels, one wave channel and one noise channel; up to 64 rows per pattern; one effect per channel per row. There are 15 instrument slots in each pulse/wave/noise bank and 16 wave tables. Instrument 0 means **legato**: it does not retrigger or reload an instrument. A legato note after a cut can therefore remain silent.

- Pattern edits are heard from the next row; updates do not restart transport.
- Instrument changes apply on the next note that loads that instrument. A sustained note keeps its existing envelope/settings.
- Wave edits reload on the next wave-channel note with an instrument.
- The base tempo can change while playing.
- Audition temporarily borrows the selected channel; the other song channels continue. The borrowed song channel resumes at its next note after audition release.
- Seeking reconstructs driver state by silently running earlier rows. Extremely long or unreachable positions use a fresh state at the requested order's beginning; see the audio document for the exact bound.

**Help → Check** lists export errors and warnings. The app will not silently export unsupported driver routines or broken noise pitch effects. An unfinished, export-invalid song can still be saved as a project. If a live edit cannot be lowered, playback retains the last valid snapshot and reports the problem.

Export is `.uge` only. GB-specific editing semantics live in the backend; future backends can define different channels, instruments and editors without pretending that GB songs convert losslessly to other chips.

- [UGE export format, effects and limits](docs/UGE_EXPORT.md)
- [Actual-driver playback, update semantics and native verification](docs/AUDIO_ENGINE.md)
- [Agreed scope and implementation plan](PLAN.md)

## Saving and recovery

**File → Save / Save as / Open / Export .uge** uses Android's document picker and user-selected locations; no broad storage permission is requested.

- Working changes are automatically written to an app-private, atomically replaced recovery file after a short debounce and when the activity pauses.
- Replacing a document retains one previous-project recovery backup. A later replacement overwrites that single slot: use named project files for permanent versions.
- Unreadable recovery files are quarantined intact rather than replaced in place. The app reports the failure and retained location.
- Opening a project validates its version and structure before replacing the working document.
- 0.6 saves project format 4 (per-pattern scale and length, extended scale list), which 0.5 and earlier cannot open. Format 1-3 projects still open, as Chromatic and 64-row patterns where those fields are absent.
- Recovery writes use a separate queue from document-provider I/O, so a stalled external save cannot block recovery of newer edits. Both run off the UI thread.
- Saving captures the version selected for that operation. If editing continues, the app reports that newer edits remain unsaved. Completing an older save cannot attach its destination to a different document.
- Android document providers control the durability and atomicity of writes to their own destinations; the app-private recovery copy remains separate.

Undo/redo is session history, not part of the serialized project format. App-private recovery is not a substitute for external backups; uninstalling or clearing app storage removes it.

## Verification commands

```sh
./tools/build.sh testDebugUnitTest assembleDebug assembleDebugAndroidTest exportSamples
./tools/audio/run_native_host_test.sh
adb -s YOUR_TEST_DEVICE install -r app/build/outputs/apk/debug/app-debug.apk
adb -s YOUR_TEST_DEVICE install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s YOUR_TEST_DEVICE shell am instrument -w org.thortracker.test/androidx.test.runner.AndroidJUnitRunner
```

Only run device checks on a device reserved for the test; the controller workflow test edits the app's working document. Host playback checks produce a WAV and driver trace under `evidence/audio/`. The sample `.uge` has also been converted with gbc-puzzler's pinned `uge2source`, built into an isolated copy of the game and exercised by its actual-ROM sound smoke; the original game worktree is not modified.

## Dependencies

binjgb is MIT licensed; its full notice is in `app/src/main/cpp/third_party/binjgb/LICENSE` and bundled in the APK's `assets/NOTICE.txt`. hUGEDriver 6.1.3 is public domain and pinned under `third_party/`. See the audio document for exact source revisions and the one-time player-image regeneration procedure.
