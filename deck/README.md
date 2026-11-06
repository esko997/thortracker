# Thor Tracker on Steam Deck — spike

![The Deck build's Pattern view on a Steam Deck, drawn by the same shared code as the Thor app: the demo song's Intro pattern with an edited C#4 under the cursor, the inspector and order list on the right, and the controller hints at the bottom](spike-deck-screenshot.png)

A Kotlin/Native + SDL3 frontend for Steam Deck / desktop Linux. It shares the Android app's core and audio engine:

- `:core`: Kotlin Multiplatform module (`jvm` for Android, `linuxX64` here). It holds the song model, validation, `.thor`/`.uge` export, `EditorSession`, `ViewController` and the keymap. Every editing rule is the same code on both platforms.
- The C hUGEDriver engine (`app/src/main/cpp/huge_machine.c` + binjgb), unchanged. `native/deck_audio.c` feeds it through an SDL3 audio stream (PipeWire on SteamOS), with live song updates and note auditions.
- The shared `Workspace` (panes, focus, view chords, swap) and `Transport` (play/stop, pattern loop, parked orders) drive both platforms. `DeckAudio.kt` implements the shared `TrackerAudio` over the SDL engine glue, so Start, L2+Start and L2+Y behave as on the Thor. The Deck uses a single pane.
- Every view (Pattern, Song, Instrument, Wave, Keyboard, Sketch, Help) is the shared `PaneView` from `:core`, the same code the Thor app draws with. `SdlSurface.kt` implements the shared `Surface` over `native/deck_draw.c`: shapes as SDL triangle geometry, text from bundled Noto fonts (`fonts/`, OFL) rasterised by `stb_truetype` (`native/third_party`, public domain) into a glyph atlas. `Main.kt` is the gamepad routing and the frame loop; one pane fills the screen and the R2 chords switch its view.

## Menus and files

The Deck uses the shared button routing (`HostInput`), popups and menus (`AppMenus`): the same main menu, Files, Views, Tuning, Controls (rebinding, keymap export/import) and Help as the Thor, plus **Quit**. Popups are drawn with the shared `Surface` (`DrawnPopup`); the platform side is `DeckShell.kt`:

- **Open the menu** with a back grip (L4/R4/L5/R5, if Steam Input passes them through) or **Select+Start** (the Thor's toolbar chord; the Deck has no toolbar). B goes back. While rebinding a button, Menu cancels.
- **Files** live in `~/ThorTracker`. Open, Save as, Export .uge and keymap files use an in-app folder browser that works with the D-pad, so no system dialog is needed in Gaming Mode. Saving names the file after the song title and asks before replacing a file.
- **Recovery:** the working song is written to `~/.local/share/thortracker/working.thor` a second after each change and on Quit, and reopens on the next start. "Load demo", "New blank song" and Open keep the replaced song as `previous.thor` for "Recover previous project". The keymap is saved beside it.
- Built-in tunings ship in `tunings/` beside the executable.

## What the spike showed (SteamOS 3.8.28, Desktop Mode)

- One native 12 MB binary with no Java runtime. It uses the Deck's system SDL3 3.2.18 (Wayland, PipeWire). The engine is compiled with Kotlin/Native's own clang and glibc 2.19 sysroot, so the build needs no system compiler.
- The Steam Deck Controller is detected. Scripted input through the same routing as the gamepad moved the cursor, edited a note (A+Right), undid it (L2+A) and started playback (Start). Playback ran at the right tempo while edits reached the engine live, at a steady 89-90 fps (90 Hz panel, vsync) with no slow frames.
- `.thor` files load through the shared `ProjectCodec` on Linux.
- All seven shared views render on the Deck at 89-90 fps with the bundled fonts, reached by the R2 view chords; the scale panel, playhead and hint bar match the Thor layout.
- `:core:linuxX64Test` runs `CrossPlatformGoldenTest` natively, with values produced on the JVM. Number text matches `String.format` exactly over 216 cases. The example project's and demo's `.uge` exports are byte-identical (length + 64-bit hash). Every degree of the four built-in tunings lands on the same note and `40y` bend. Cents computed from ratios differ in the last bits of `log2` (JVM vs native libm, up to ~1e-13 cents on just-5-limit). That is far below the 5/10/20 ¢ limits, so cents are compared within 1e-9.

Not yet checked: physical button presses (no one pressed them), Gaming Mode as a non-Steam game, suspend/resume, back paddles and trackpads under Steam Input.

## Packaging

`tools/build-sdl3.sh` builds SDL3 3.2.18 from its release tarball into `deps/` (CI does this); `tools/package-appimage.sh <version>` puts the release executable, `fonts/`, `tunings/` and that `libSDL3.so.0` (in `lib/`, found through the executable's `$ORIGIN/lib` rpath) into `dist/thor-tracker-<version>-x86_64.AppImage`. CI's `deck` job builds it on every push, starts the AppImage offscreen and opens the menu, and uploads it; releases attach it. The executable needs only glibc 2.19 or later; the bundled SDL3 is built on Ubuntu 24.04 (glibc 2.39; SteamOS has 2.41).

## Build and run

```sh
deck/tools/fetch-deps.sh            # SDL3 headers + the Deck's libSDL3.so to link against (deck/deps, gitignored)
tools/build.sh :deck:linkDebugExecutableLinuxX64
# deck/build/bin/linuxX64/debugExecutable/thortracker.kexe [song.thor] [seconds=N] [script=96+22,20,108]
# fonts/ and tunings/ are copied beside the executable; deploy them together
```

On the Deck, `~/thortracker-deck/spike/run.sh` starts it with the demo song. Add it in Desktop Mode via Steam → Add a Non-Steam Game to launch it from Gaming Mode. Gamepad: as on the Thor (D-pad moves, hold A + D-pad edits, B clears, L2+A / L2+B undo/redo, Start plays/stops, L2+Y loops, R2+A/B/X/Y and R2+↑/→/↓ switch views); Select+Start or a back grip opens the menu, which has Quit. Keyboard: arrows, Z/X/A/S = A/B/X/Y, Space = Start, Tab = Select, F1 = menu, Esc = back.

## Open questions for the real port

- On-screen keyboard for the song title field in Gaming Mode (a physical keyboard works; the title can also be edited letter by letter in the Song view).
- Touchscreen input.
- Shapes are not anti-aliased on the Deck yet (SDL geometry); circles and rounded corners look slightly stepped.
- Files: Open/Save/Export through SDL3 file dialogs (desktop portal) or a built-in browser in Gaming Mode.
