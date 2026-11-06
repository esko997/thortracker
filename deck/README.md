# Thor Tracker on Steam Deck — spike

![The Deck build's Pattern view on a Steam Deck: the demo song's Intro pattern, the cursor on an edited C#4 in Pulse 1, the playhead on row 0C, and the shared controller's hints at the bottom](spike-deck-screenshot.png)

A Kotlin/Native + SDL3 frontend for Steam Deck / desktop Linux. It shares the Android app's core and audio engine:

- `:core`: Kotlin Multiplatform module (`jvm` for Android, `linuxX64` here). It holds the song model, validation, `.thor`/`.uge` export, `EditorSession`, `ViewController` and the keymap. Every editing rule is the same code on both platforms.
- The C hUGEDriver engine (`app/src/main/cpp/huge_machine.c` + binjgb), unchanged. `native/deck_audio.c` feeds it through an SDL3 audio stream (PipeWire on SteamOS), with live song updates and note auditions.
- The shared `Workspace` (panes, focus, view chords, swap) and `Transport` (play/stop, pattern loop, parked orders) drive both platforms. `DeckAudio.kt` implements the shared `TrackerAudio` over the SDL engine glue, so Start, L2+Start and L2+Y behave as on the Thor. The Deck uses a single pane.
- `src/linuxX64Main/.../Main.kt` is the frontend. SDL gamepad buttons map to the Android key codes the shared keymap uses, so chords and bindings resolve exactly as on the Thor. It draws the Pattern view with SDL's 8x8 debug font.

## What the spike showed (SteamOS 3.8.28, Desktop Mode)

- One native 12 MB binary with no Java runtime. It uses the Deck's system SDL3 3.2.18 (Wayland, PipeWire). The engine is compiled with Kotlin/Native's own clang and glibc 2.19 sysroot, so the build needs no system compiler.
- The Steam Deck Controller is detected. Scripted input through the same routing as the gamepad moved the cursor, edited a note (A+Right), undid it (L2+A) and started playback (Start). Playback ran at the right tempo while edits reached the engine live, at a steady 89-90 fps (90 Hz panel, vsync) with no slow frames.
- `.thor` files load through the shared `ProjectCodec` on Linux.
- `:core:linuxX64Test` runs `CrossPlatformGoldenTest` natively, with values produced on the JVM. Number text matches `String.format` exactly over 216 cases. The example project's and demo's `.uge` exports are byte-identical (length + 64-bit hash). Every degree of the four built-in tunings lands on the same note and `40y` bend. Cents computed from ratios differ in the last bits of `log2` (JVM vs native libm, up to ~1e-13 cents on just-5-limit). That is far below the 5/10/20 ¢ limits, so cents are compared within 1e-9.

Not yet checked: physical button presses (no one pressed them), Gaming Mode as a non-Steam game, suspend/resume, back paddles and trackpads under Steam Input.

## Build and run

```sh
deck/tools/fetch-deps.sh            # SDL3 headers + the Deck's libSDL3.so to link against (deck/deps, gitignored)
tools/build.sh :deck:linkDebugExecutableLinuxX64
# deck/build/bin/linuxX64/debugExecutable/thortracker.kexe [song.thor] [seconds=N] [script=96+22,20,108]
```

On the Deck, `~/thortracker-deck/spike/run.sh` starts it with the demo song. Add it in Desktop Mode via Steam → Add a Non-Steam Game to launch it from Gaming Mode. Gamepad: D-pad moves, hold A + D-pad edits, B clears, L2+A / L2+B undo/redo, Start plays/stops, Esc (keyboard) quits.

## Open questions for the real port

- Text: replace the debug font with a real one (stb_truetype or SDL_ttf) for the full UI.
- Views: share the painters through a small drawing interface in `:core`, with one view at a time and quick switching on the Deck. Move `HostInput` (button routing) into `:core` so the Deck frontend uses it too.
- Files: Open/Save/Export through SDL3 file dialogs (desktop portal) or a built-in browser in Gaming Mode.
- Packaging: link against the Steam Linux Runtime (sniper) SDK or bundle SDL3. Build in CI. Ship a Flatpak/AppImage or a Steam shortcut.
