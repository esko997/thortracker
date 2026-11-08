# Thor Tracker verification — 2026-11-08

## 0.14.0 WAV export and opt-in diagnostics

- **Export .wav** renders Game Boy, NES, C64 SID, OPL3 and Mega Drive songs through the same native engines used for playback, as 48 kHz, 16-bit stereo PCM. Choose extra loop passes and either no fade or a five-second fade. Rendering stops at ten minutes if the requested loop point has not been reached and reports that cap.
- Android renders to a private temporary file before opening the document picker; cancelling the picker keeps the completed export available to save. Cancel, hide/reopen progress and discard are supported. Desktop exports into the chosen folder without loading the whole WAV into memory. A failed save leaves no partial destination.
- **Help → About** shows version/build and chip/driver versions. **Report a bug** opens GitHub; **Copy diagnostics** puts an opt-in report on Android's clipboard, or saves `~/ThorTracker/diagnostics.txt` on desktop. No automatic upload or telemetry.
- Fixed the long view-shortcut list overlapping its explanation in Help → Controls.
- Workstation builds now serialize Gradle, default to two workers, run Kotlin in a single-use process, and cap Android native compilation to two compiler jobs per ABI.

Verified: native checks passed (Game Boy 145, NES 336, SID 151, OPL3 282, Mega Drive 500, WAV 208); all five demos rendered and the shortest valid one-row loops completed with zero and two extra passes. Android's 18 existing instrumentation tests passed. The Game Boy factory `.uge` is byte-identical; 26 non-Help renders at two screen sizes are pixel-identical to 0.13.0; the six Help renders differ for the new About tab and corrected shortcut layout. On Nova, a SID WAV saved through Android's picker is valid 48 kHz stereo PCM (1,710,896 frames); picker cancellation, export cancellation/restart, hide/reopen, discard, clipboard diagnostics and the GitHub link were exercised. Desktop SID export produced the same frame count; a deliberately unwritable destination reported failure and left no temporary output. The cancel/restart spool-ownership race was reproduced with the native renderer and fixed by giving each Android export its own file.

Release candidate checks: 392 JVM core tests, 13 Linux core tests and six Android-host unit tests passed. The production-signed APK also exported Mega Drive on DMG (one extra loop plus fade, 3,008,364 frames) and OPL3 on Konkr (no fade, 1,792,000 frames); both saved files were verified as non-silent 48 kHz stereo PCM and removed after the smoke. About shows 0.14.0/build 26 on Nova, DMG and Konkr. The packaged AppImage exported the SID demo with the same frame count as Android; its compressor used two workers.

## 0.13.0 Sega Mega Drive songs

A fifth sound chip pair: the Mega Drive's **YM2612** FM chip and **SN76489** PSG, played through SGDK's real **XGM** sound driver on an emulated Z80, so exports drop straight into Mega Drive homebrew made with SGDK.

- **New blank song** and **Load demo** offer **Mega Drive (YM2612 + PSG)**. The demo is **Neon Causeway**.
- Ten channels: FM 1-6, PSG 1-3 and Noise, with up to four effect columns each (as on OPL3). NTSC by default; PAL and the chip LFO are song settings.
- **FM voices** use the same editor as OPL3: a **Sound** page of curated choices (the YM2612's 8 algorithms drawn as diagrams, ratios with detune, brightness, level, envelope with sustain rate, key scaling, vibrato/tremolo, SSG-EG), an **Expert** page with every operator register, and a **Drum** page for pitched FM drums with a sweep.
- **PSG sounds** are volume, arpeggio and pitch envelopes (like NES instruments), plus noise mode and rate. A slot's **Type** row switches it between FM voice and PSG sound; each lane starts on a sound of the right kind.
- 24 presets: slap bass, brass, e-piano, bell, strings, lead, FM drums, PSG leads and arpeggios, noise drums.
- **Export .vgm**, **.xgm** (for SGDK projects) and **.xgc**.
- Not in this release: channel 3's special mode, PCM samples (DAC) and XGM2.

Verified: 408 unit tests; native engine checks: Game Boy 145, NES 336, SID 151, OPL3 282, Mega Drive 500 (the driver's chip writes equal the streamed frames; our XGM and XGC exports are byte-identical to SGDK's own xgmtool converting our VGM, NTSC and PAL; the player's writes equal an independent Python implementation every tick; a fast-path that replays the driver's idle Z80 passes is proven sample- and register-identical to stepping every cycle); Mega Drive project lowering byte-identical to the reference builder for two golden songs; other systems' project files encode byte-identically; Game Boy factory `.uge` byte-identical and all 32 Game Boy view renders pixel-identical; 18 Android tests on the emulator. On an AYANEO Pocket DMG the Mega Drive demo plays with 0 audio underruns and the FM/PSG instrument pages work. Cost per tick on the Konkr: 0.9 ms warm (4.9 ms when the CPU clocks down between ticks).

## 0.12.0 OPL3 FM songs

A fourth sound chip: the Yamaha **OPL3** (YMF262), the Sound Blaster's FM chip, emulated by Nuked-OPL3 and played by Thor Tracker's own player (no console shipped an OPL3 sound driver, so this system is music-only and exports VGM).

- **New blank song** and **Load demo** offer **OPL3 FM (YMF262)**. The OPL3 demo is **Glass Harbour**.
- Nine channels, FM 1-9. FM 1-6 can play 4-operator sounds; the chip's other voices form a pool for chords.
- **Up to four effect columns per channel** on OPL3 songs (the other chips keep one). The Pattern panel's new Columns row sets how many show per channel; effects in hidden columns still play and are marked. Lanes scroll sideways to keep the cursor's channel in view on narrow screens.
- **Chords:** `Hxy` sounds the note plus +x and +y semitones together on spare voices; `Kxx` plays a shape from the song's chord table (Chords page). The status line shows the chord's notes while you edit.
- **FM sounds built from curated choices:** the **Sound** page sets algorithm (2-op FM/additive, the four 4-op routings), ratios, waveforms, brightness, level, feedback, envelope, tone decay, key scaling and vibrato/tremolo, one ←/→ value each, with a drawn algorithm and envelope. **Expert** shows every operator register. **Drum** adds a pitch sweep (start, end, speed) for pitched FM drums. 17 presets (e-piano, brass, bell, organ, bass, pad, kick, snare, tom, hats…).
- Parameter-lock effects (brightness, feedback, ratio, waveform) apply when the note starts.
- **Export .vgm** (VGM 1.71 with title/author tags), playable in VGM players.

Verified: 379 unit tests; native engine checks: Game Boy 145, NES 336, SID 151, OPL3 282 (our player's register writes equal an independent Python implementation of the same spec in every tick, VGM byte-identical; A-4 at 439.99 Hz; exact-row seeks; live re-layout before every tick changes nothing); OPL3 project lowering byte-identical to the reference builder for two golden songs; existing Game Boy, NES and SID project files encode byte-identically after the effect-column model change; Game Boy factory `.uge` byte-identical and all 32 Game Boy view renders pixel-identical; 18 Android tests on the emulator. On a Konkr Pocket Block (the slowest test device): the OPL3 demo plays with 0 audio underruns over 22 s, and the instrument pages work. Cost per 60 Hz tick: Konkr 1.3 ms warm (6 ms when the CPU clocks down between ticks, still within the 16.7 ms budget).

## 0.11.0 Commodore 64 SID songs

Thor Tracker now writes music for a third chip: the C64's SID, played by GoatTracker 2.77's own playroutine on an emulated 6510 with reSID 1.0, on the Thor and the Steam Deck.

- **New blank song** and **Load demo** offer **C64 SID (GoatTracker)**. The SID demo is **Raster Run**.
- Three voices and one filter shared by the chip. The Pattern header tags the voice whose sound currently owns the filter; the inspector names it.
- 63 sounds shared by all voices. The instrument view has six pages (L1/R1): **Envelope** (attack/decay/sustain/release with a drawn curve, plus presets: saw lead, PWM pad, filter bass, noise snare, wave-arpeggio kick, hi-hat, triangle bass, arpeggio chord), **Wave**, **Pulse** and **Filter** programs edited row by row, **Vibrato·HR** (hard restart, gate timer, first-frame waveform, vibrato) and **Tables** (the song-wide tables as the player reads them).
- Song settings: chip model (MOS 8580 for new songs, or 6581), multispeed 1-16, the hard-restart envelope.
- Effects use GoatTracker's numbers: `1xx`/`2xx` portamento, `3xx` tone portamento, `4xy` vibrato, `5xx`/`6xx` envelope, `7xx` waveform, `8xx`/`9xx`/`Axx` wave/pulse/filter program, `Bxx` filter control, `Cxx` cutoff, `Dxx` volume, `Exy` funktempo, `Fxx` tempo. Rows can hold key off or key on.
- Microtonal tunings retune the song's frequency table, so every tuned degree is exact.
- Export `.sid` (PSID, plays in SID players), `.prg` (runs on a C64) and GoatTracker `.sng` (opens in GoatTracker 2.77).
- Live edits follow the playhead even when data moves; seeking and the first chip setup happen off the audio thread.

Verified: 351 unit tests; native engine checks: Game Boy 145, NES 336, SID 151; our SID lowering is byte-identical to GoatTracker's own packer on its test song, and on three golden songs our `.sid` writes the same SID registers every frame as GoatTracker's packed `.sid` (run in an independent 6502 emulator); `.prg` writes the same as `.sid`; re-laying out a song before every tick changes no register write. Game Boy factory `.uge` byte-identical, all 32 Game Boy view renders pixel-identical, 18 Android tests on the emulator. On a Retroid Pocket Nova the SID demo plays with its filter tag and the instrument pages work. Audio-thread cost per 50 Hz tick (8580): Nova 0.46 ms, DMG 0.93 ms, Konkr 1.78 ms (worst stall incl. a live edit 2.5 ms).

## 0.10.0 NES DPCM samples

NES songs gain the 2A03's fifth channel, **DPCM**: delta-modulation samples, played by the FamiStudio engine and emulated in the APU.

- The Pattern view shows five NES channels. A DPCM note plays the sample its instrument maps to that key; the inspector and status line name the sample, and a non-looping sample's rail ends where the sample stops.
- The NES instrument view has a **DPCM** page (L1/R1): each key maps to a sample with a pitch (rate 0-F), loop and start level. Add samples from the built-in **starter kit** (kick, snare, hat, tom, bass; synthesized by Thor Tracker) or **import a .wav or .dmc file**: a .wav is converted on the device at the rate you choose, with its size shown against the free sample memory. X listens to the shown key.
- `Zxx` sets the DMC output level. The DPCM channel also takes `F`, `G` and `S`.
- Memory: song data and samples share $8C00-$FFF9 (29,690 bytes); samples sit at the top, 64-byte aligned, never below $C000. Songs without samples have more room than in 0.9.x.
- The NES demo, Thor Quest, now plays kit drums on the DPCM channel.
- `.nsf` and FamiStudio text exports include the samples and mappings. NES songs from 0.9.x open with an empty DPCM channel; earlier releases cannot open NES songs saved by 0.10.0.

Verified: 317 unit tests (JVM, Linux, Android unit); native engine checks: Game Boy 145, NES 336 (DMC rates and bit-level output, loops, seeking into sample-heavy patterns, live edits that move samples, the shared memory budget at its boundary); FamiStudio 4.5.3 writes the same APU registers every frame as our lowering for four golden songs, DMC included, and its sample bytes match ours; WAV conversion byte-identical to FamiStudio's own on five reference files; Game Boy factory `.uge` byte-identical and all 32 Game Boy view renders pixel-identical; 18 Android tests on the emulator; on a Retroid Pocket Nova: the NES demo plays with DPCM, an older NES song opens with an empty DPCM channel, and a .wav imported through the Android file picker converts, maps and plays.

## 0.9.1 Error bubbles clear when the problem does

- On Android, the error bubble that says the preview is playing the last playable version (for example "Pulse 1 cannot play below A-1") now goes away as soon as the song is fixed, instead of staying on screen.
- Auditioning a note the channel cannot play shows a short status message that fades, instead of an error that stayed until the next playable note.

Verified: 298 unit tests; reproduced the stuck bubble on a Retroid Pocket Nova and confirmed it clears about 1.5 s after the offending note is removed. NES engine speed measured on the test handhelds with the same C sources the app uses: Retroid Pocket Nova (QCS8550) 214× realtime, AYANEO Pocket DMG 98×, Konkr Pocket Block (MT8786) 46×; NES and Game Boy loudness already match within 1.5 dB.

## 0.9.0 NES songs, true pitch, GPL

Thor Tracker now writes music for a second sound chip: the NES (2A03), played by the FamiStudio sound engine 4.5.3 on an emulated 6502, on the Thor and the Steam Deck.

- **New blank song** and **Load demo** ask for the chip: Game Boy (hUGEDriver) or NES (FamiStudio). The NES demo is **Thor Quest**.
- NES songs have Pulse 1, Pulse 2, Triangle and Noise (no DPCM samples yet) and one bank of 64 instruments shared by all four channels. Each instrument has volume, arpeggio, pitch and duty envelopes with loop and release points, edited in the new controller-only instrument view; envelopes a channel ignores are greyed out.
- NES effects: `0xy` arpeggio, `4xy` vibrato, `Axy` volume slide, `Cxx` volume, `Fxx` speed, `Gxx` note delay, `Pxx` fine pitch (tunings use it), `Qxx`/`Rxx` glide up/down, `Sxx` note cut, `Vxx` duty (noise mode on Noise). There are no jumps: the loop point and pattern lengths shape the song. OFF plays the instrument's release when its volume envelope has one.
- Export NES songs as `.nsf` (any NSF player, or hardware via a flash cart) or FamiStudio text (opens in FamiStudio 4.5.3, which exports to NES game projects).
- **True pitch:** a note number is the same pitch on every chip (A4 = 440 Hz). Each chip keeps its own note names, so Game Boy songs look exactly as before. Projects save as format 9; older projects and saved Sketch phrases upgrade when opened. Earlier releases cannot open format 9 files.
- Thor Tracker is now free software under the GNU GPL, version 3 or later (Help → Controls → About).
- On the Deck, auditioning a note during playback now mutes that channel in the song, as on the Thor.

Verified: 297 unit tests (JVM and Linux); native engine checks: Game Boy 145, NES 240 (exact rows, seek, auditions with release, live edits); for three test songs our NES data writes the same APU registers every frame as FamiStudio 4.5.3's own export; the Game Boy factory `.uge` is byte-identical and all 32 Game Boy view renders are pixel-identical to 0.8.6; 18 Android tests on the emulator; the NES demo loads and plays on the Android emulator and the Deck build.

## 0.8.6 Steam Deck build, more view chords

Thor Tracker now also runs on the Steam Deck (and other x86_64 Linux) as `thor-tracker-0.8.6-x86_64.AppImage`, built from the same shared core: every view, the menus, files, the controls and the hUGEDriver sound engine. See the README's "Steam Deck" section to install it as a non-Steam game.

For the Thor:
- **R2 + ↑ / → / ↓** show Pattern, Wave and Help (R2 + A / B / X / Y still show Song, Instrument, Sketch and Keyboard). A saved button mapping gains these chords unless it already uses them (keymap file version 5).
- While waiting for a new button in Menu → Controls, **Menu** now cancels, like Back.
- Under the hood: button routing, popups, menus, panes, playback control and every view's drawing are shared code (`core/`), so the Thor and the Deck behave the same. Every view renders pixel-identically to 0.8.5 at both Thor screen sizes.

Steam Deck build:
- One view at a time on the 1280×800 screen; Select+Start (or a back grip) opens the menu, which has Quit; Steam+X brings up the keyboard for text fields.
- Projects and exports are kept in `~/ThorTracker`, chosen with an in-app folder browser that works in Gaming Mode. The working song saves itself and reopens on the next start.
- Not yet: touchscreen input, and trackpad shortcuts (possible later through a Steam Input layout).

Verified: 253 unit tests (JVM and Linux), 18 Android tests on the emulator, factory `.uge` byte-identical. The AppImage was run on a Steam Deck (SteamOS 3.8.28): bundled SDL3 on Wayland/PipeWire, Steam Deck Controller, menus, save, tuning, quit and recovery, playback at 90 fps; held Sketch phrases and the R2 + ↑ return to Pattern were checked with the same build on Linux.

## 0.8.5 shared core

The app's platform-independent code is now the Kotlin Multiplatform module `core/`: song model, hUGE rules, lowering and `.uge` export, editor, undo and keymap. It is built for the JVM (used by the Android app) and for Kotlin/Native `linuxX64` (groundwork for other devices). App behaviour is unchanged, with one exception: decimal numbers in the editor (cents, percentages, milliseconds) always use a dot. Before, they followed the device language, e.g. "7,8" in German.

- JVM-only calls were replaced with multiplatform equivalents. `NumberText` reproduces `java.util.Formatter`'s `%.Nf`/`%X` output and is checked against `String.format` on 60,000 random values. `Keys` holds the Android key-code numbers, so keymap files are unchanged.
- `CrossPlatformGoldenTest` runs on both targets against JVM-produced values: number text (216 cases), the example and demo `.uge` exports, and the note and `40y` bend each degree of the built-in tunings lands on. Cents from ratios differ in the last bits of `log2` between the JVM and native libm (about 1e-13 ¢), far below the 5/10/20 ¢ fit limits.
- CI runs `:core:jvmTest` and `:core:linuxX64Test`, and builds every branch push.

## 0.8.4 signed releases, CI and a README tour

Thor Tracker now ships as signed GitHub releases. **Installing this release over an earlier Thor Tracker requires uninstalling it once first**: earlier APKs were signed with a development key. Uninstalling removes the app's private data (recovery copy, button mapping, screen layout); songs saved or exported through the file picker, and exported keymaps, are kept. Later releases install over this one.

- GitHub Actions `build` workflow on every push and pull request: unit tests, debug and release builds, the factory `.uge` byte-for-byte check and the native hUGEDriver host test. A version bump on `main` signs and publishes the release.
- `tools/release.sh` builds and verifies the signed APK locally (pinned release certificate, package, version, not debuggable).
- The release build's lint gate no longer stops on the Google Play target-SDK rule (`ExpiredTargetSdkVersion`); the app is sideloaded, and its runtime behaviour (targetSdk 32) is unchanged.
- README: hero clip, install instructions and a visual tour (Pattern + Keyboard on both screens, Song view with a parked order, Instrument/Wave, Sketch, R2 view chords, pattern loop). All media are real captures from the app in the emulator at the Thor's resolutions.

App behaviour is unchanged from 0.8.3.

## 0.8.3 Song check order numbers with parked orders

0.8.2 validated the arrangement (parked orders removed), so messages naming an order used played-order positions: with order 01 parked, a problem at order 04 was reported at order 03. Song check now names orders as the user numbers them. Cell checks read the document's own cells, so a jump is quoted as written and counted against all orders. The loop-slot and pattern-break messages map played positions back through `HugeRules.playedOrders`, and the suggested `Bxx` is the one to type. Regression test `songCheckNamesOrdersAsTheUserNumbersThemWhenAnEarlierOneIsParked` parks order 01 of 5 and checks the D-break (order 0 → order 2), stale jump ("the song has 5 orders") and loop-slot (order 2, last order 4, B03) messages. It fails on the 0.8.2 validator and passes now.

Kotlin suite: 225 passed. Factory `.uge` byte-identical. Validator messages only; no playback or device-path change, so the 0.8.2 Android run (18 passed) stands.

Delivery: **0.8.3**, version code **16**, APK **2,586,437 bytes**, SHA-256 `3b7c12a3ec713fc53917383c08afe21cd05a70cd6d90b891523ecbaa791f6ad5`. Checksum-matching copy at **Download/Thor Tracker/thor-tracker-0.8.3-debug.apk** on the Thor (artifact-only lease; not installed there).

## 0.8.2 parked orders

The Song view's **Park** order command (Unpark when parked) keeps an order in place, with its pattern, but song playback and `.uge` export skip it. One arrangement step, `HugeRules.arranged`, removes parked orders and renumbers everything that names an order: the loop target and every stored `Bxx`. A target that is parked lands on the next played order after it, or on the loop target when none follows. `SongValidator.errors`/`warnings` (now `GbValidator`) and `HugeLowering.lower` work on that arrangement, so only played orders can block export. The activity maps the engine's arranged positions back to document orders (`HugeRules.playedOrders`, now `Songs.playedOrders`). Starting playback on a parked order loops it on its own until playback stops or moves to a played order. Insert/delete/move/undo carry parking with the order entry. Repeat/New/Clone next to a parked order are parked too. The last playing order can be neither parked nor deleted. The Song rows dim and say "Parked"; the Pattern order list marks `‖` and its header "Parked"; "Loop to" names the order the song actually loops to when its target is parked. Saved as `parkedOrders` in project format 7; older files park nothing, and a format 6 file claiming parked orders is rejected.

Kotlin suite: 224 passed (arrangement renumbering incl. parked loop targets and jumps past the last played order, export/lowering byte-identical to the unparked arrangement, parked-only problems demoted to warnings, all-parked rejected, structural/format checks, session insert/delete/move/undo/clone/last-order rules). Factory `.uge` byte-identical. Android suite on the owned emulator: 18 passed. Run alone: the real driver with order 1 of 3 parked hears only two orders, the second reaching row 6 of the 7-row pattern C (1.8 s); in the app at 1 tick per row, playback from order 00 reached order 02 and never order 01 (parked), then Start on order 01 looped it alone with wraps and stopping ended that loop (9.7 s). Screens: `evidence/octave-0.7/43-song-parked-lower.png` (parked row and Park chip fit the 1240-px display), `44-loop-parked.png` (loop target note; parking survived an app restart via recovery).

Delivery: **0.8.2**, version code **15**, APK **2,570,049 bytes**, SHA-256 `f4bed341355250adb6593fb5304f9046d4e5f7d9897e796c81c06ec7ab6d066e`. Checksum-matching copy at **Download/Thor Tracker/thor-tracker-0.8.2-debug.apk** on the Thor (artifact-only lease; not installed there).

## 0.8.1 new-pattern scale and pattern loop

**New patterns scale.** The Song view has a fifth header row, **New patterns**: the editor-only scale guide every pattern made with New starts in (A+Left/Right scale, A+Up/Down root, B Chromatic, undoable). Clone and Repeat keep the source pattern's scale; existing patterns are unchanged. It is saved as the song's `newPatternScale` in project format 6; format 1-5 files read as Chromatic, and a format 5 file claiming a non-chromatic one is rejected. 0.8.0 and earlier cannot open format 6.

**Pattern loop (L2+Y).** Toggles a loop on the focused pattern view's order. The engine then plays that order alone (`HugeRules.patternLoop`): stored `Bxx` jumps in it are aimed back at it, `B00`/`Dxx` already land on the loop, and the document is untouched. Turning it on while that order plays keeps the row; while another plays, it starts at row 00; turning it off plays on through the song from the same row; stopped, it arms the next Play. While on, Start / L2+Start loop whichever order they start. The Pattern header shows "· Looping". The loop remembers its pattern: inserting, deleting or moving orders (or undoing that) keeps it on the nearest order that still plays that pattern, falling back to the same position only when no order plays it. Keymap version 4: an older keymap gains L2+Y unless it already uses L2+Y, in which case Loop pattern starts unbound.

Kotlin suite: 213 passed (nearest order still playing the looped pattern, new-pattern scale editing/undo, New vs Clone, format 6 round trip and gate, loop playback song with jumps rewritten and lowerable, keymap v3 → v4 migration with and without an L2+Y conflict). Factory `.uge` byte-identical. Android suite on the owned emulator: 16 passed. Run alone, the loop tests take their full observation windows and pass their wrap assertions: the real driver replays a looped 6-row pattern whose B03 would have left it (1.6 s; only order 0 rows 0-3 heard, at least two wraps, no driver faults); the app's L2+Y + Start stays on order 01 past the pattern's end and releases on L2+Y again (9.5 s); and while order 02 loops, inserting an order at 00 moves the loop to order 03 (same pattern) and undo returns it to 02 (2.6 s). Screens: `evidence/octave-0.7/40-song-new-pattern-scale.png`, `41-pattern-looping.png`. One emulator run was cut short by an emulator segfault (exit 139, host-side); the rerun passed.

Delivery: **0.8.1**, version code **14**, APK **2,706,121 bytes**, SHA-256 `6cd0a8f003fd31e374059775b64738c75fcdc8be2c98233bbad36f734ed86f54`. Checksum-matching copy at **Download/Thor Tracker/thor-tracker-0.8.1-debug.apk** on the Thor (artifact-only lease; not installed there).

## 0.8.0 view chords

R2+A/B/X/Y show Song/Instrument/Sketch/Keyboard on the controller-focused display (pinned, like the View menu); the same chord again returns to the view it replaced, restoring whether it was pinned or following the selection, and Swap carries that memory with the views. Pattern, Wave and Help have unbound view controls in Menu → Controls. Redo moved from R2+A to L2+B. Chords over an open popup no longer press its highlighted item. Keymap files are version 3: an older keymap that never changed a default reads as today's defaults (Redo on L2+B, view chords bound); a customised one keeps Redo on R2+A and leaves Show Song unbound.

Kotlin suite: 208 passed (view-chord routing per view and over popups, L2+B redo, untouched and customised version-2 keymaps, unbound-by-default view controls). Factory `.uge` byte-identical. Android suite on the owned emulator: 13 passed, including real R2+B / R2+A key events switching Pattern → Instrument → Pattern (unpinned state restored) and Instrument → Song → back to Instrument without editing. Held-R2 hints read "R2+A Song, R2+B Instrument, R2+X Sketch, R2+Y Keyboard"; held L2 lists Undo, Redo (L2+B), Play from cursor, Swap views, Pattern panel.

Delivery: **0.8.0**, version code **13**, APK **2,661,661 bytes**, SHA-256 `81762f7c80afddda969eff88cddc10e1d50c24a22b25f8e2694a97eafd2581c1`. Checksum-matching copy at **Download/Thor Tracker/thor-tracker-0.8.0-debug.apk** on the Thor (artifact-only lease; not installed there).

## 0.7.3 stale jumps after deleting orders; battery readout

User report: after deleting orders, playback played patterns not in the arranger. Reproduced from the user's backup (read-only pull of `/sdcard/ThorTunez/dungeon2bug`): an unplayed pattern (0B) kept `B0B` aimed at an order that no longer existed, the whole song failed validation, and the preview silently kept the last playable (pre-delete) version. Fixes: order insert/delete/move/clone renumber stored `Bxx` with their orders (as the loop point already was); a stale jump in a pattern no order plays is a warning, not an error; the "playing the last playable version" message now stays on screen until fixed. With the fix the backup validates with only that warning and lowers to orders 00 01 06 04 06 07 08 09 0A; on the owned emulator, Play stepped the Song view's playhead through all nine orders and looped.

Battery: each hint bar ends with a battery and percentage (grey; cyan plugged in or charging; red at ≤15% on battery; battery broadcasts update only the readout, never the views), checked on the emulator at 100/64/12% charging and discharging, on both the 1920×1080 and 1240×1080 displays.

Kotlin suite: 205 passed (adds jump remapping through insert/delete/move/clone, deleting five orders before an `B0B`, and unplayed-pattern jump warnings). Factory `.uge` byte-identical. Android suite: 12 passed.

Delivery: **0.7.3**, version code **12**, APK **2,624,628 bytes**, SHA-256 `821cd52a5b505fe763fd8f00fba4e7f190a90e9bee0df42a1bd29a2a82ad14fd`. Checksum-matching copy at **Download/Thor Tracker/thor-tracker-0.7.3-debug.apk** on the Thor (artifact-only lease; not installed there).

## 0.7.2 fit-mark corrections

Fit marks now record whole cells: a tuning Fit that only changes a note's `40y` bend is marked, the ▴/▾ direction counts the bend, and the tuned-note inspector shows "fitted from" (e.g. `E-5+7`). Editing a marked cell's sound or effect unmarks it; inserting or deleting rows clears the pattern's marks instead of leaving them on shifted rows. Kotlin suite: 202 passed, adding a real chained Fit (D → Eb → E remembers D), a bend-only tuning mark, and row insertion clearing marks. Factory `.uge` byte-identical. The Android suite and screenshots were not rerun after this change (last run on 0.7.1: 12 passed).

Delivery: **0.7.2**, version code **11**, APK **2,553,669 bytes**, SHA-256 `bc0b4a621a7564bb3bf601c8796010f5629692bb7b29fc6aa4dff473e11d4702`. Checksum-matching copy at **Download/Thor Tracker/thor-tracker-0.7.2-debug.apk** on the Thor (artifact-only lease; not installed there).

## 0.7.1 visible fit marks

Fit (scale or tuning) now marks each moved note in the grid (amber field, ▴/▾ direction) and the inspector names the original ("Note G#5 · fitted from A-5"). Chained Fits keep each note's original pitch; a mark lasts while its cell holds the fitted note. Kotlin suite: 199 passed, including chained-Fit drift, per-note unmarking on edit, undo restoring/clearing marks, and a note fitted back to its original losing its mark. Factory `.uge` byte-identical. Owned emulator: 12 Android tests passed; on the 1240×1080 display a fresh demo fitted to C natural minor showed 12 marked notes, and editing one of them removed only that mark. Not installed on the Thor.

Delivery: **0.7.1**, version code **10**, APK **2,655,314 bytes**, SHA-256 `80c61162b339be72a7cb937c903b8709976a8cef38795ac3349cbc7e7c0b01cb`. Checksum-matching copy at **Download/Thor Tracker/thor-tracker-0.7.1-debug.apk** on the Thor (artifact-only lease; not installed there).

## 0.7.0 view-scoped chords, listening octave, fit notes to scale (also tuning and Sketch phrases)

Tested on the owned Android 13 emulator at 1920×1080 plus a 1240×1080 secondary display, both 369 dpi; **not installed or exercised on the Thor**.

| Check | Observed result |
| --- | --- |
| Kotlin suite | 196 tests passed: view-scoped routing (L2+→ is the pattern panel in Pattern and listening octave up in Instrument/Wave/Keyboard/Sketch; L2+→ in Song falls back to bare →), per-view chord lists, view-aware conflicts and takeovers, version-1 keymaps not gaining a new default they already use, fit-to-scale (nearest note, ties down, effects kept, Noise/hidden rows untouched, one undo, no-op for Chromatic and tuned songs), listening-octave re-sounding a held audition, plus the tuning and Sketch phrase suites |
| Native actual-driver host test | 126 checks, 0 failures |
| Factory `.uge` | Byte-identical |
| Android suite | 12 tests passed, including after a session that left controller focus on the lower display (setup now resets focus; previously 6 tests failed from that leftover state) |
| Held triggers | Holding L2 in Pattern listed Undo, Play from cursor, Swap views, Pattern panel; in Instrument it listed Undo, Play from cursor, Swap views, Octave down, Octave up; R2 listed Redo |
| Listening octave | L2+→ in Instrument changed the hint and status from "Listen at C-4" to "Listening at C-5" |
| Fit notes | Demo pattern 00 in C natural minor: Fit moved 12 Pulse/Wave notes (e.g. A-5→G#5, E→D#, ties down); Noise unchanged; L2+A restored the exact notes. The four-row panel fits the 1240×1080 display; footer reads "A fit L2+→ close" on the Notes row |

Not verified: listening on hardware, and the tuning/Sketch-phrase features beyond their unit and emulator suites.

Follow-up in the same version: a held Listen in Keyboard now also re-sounds at the new octave (regression test added), and the tuned-note inspector no longer reports a note whose row holds another effect as untuned when its plain pitch is a tuning degree. Kotlin suite: 197 passed; factory `.uge` byte-identical. The Android suite was not rerun after this follow-up.

Delivery: **0.7.0**, version code **9**, APK **2,686,563 bytes**, SHA-256 `1d4db541633d47e32a7c31e64409cee7a778cb27410a0cc5bc25b7e0d8f97088`. Checksum-matching copy at **Download/Thor Tracker/thor-tracker-0.7.0-debug.apk** on the Thor (artifact-only lease; replaced the earlier 0.7.0 copy; not installed there). Projects saved by 0.7.0 use format 5; keymap files use version 2.

## 0.6.0 more scales

Fourteen scale kinds added (harmonic/melodic minor, the five other modes, blues, whole tone, Phrygian dominant, double harmonic, Hungarian minor, Hirajoshi, Miyako-bushi); new scale names require project format 4. Kotlin suite passed, including exact chords/numerals for Lydian, harmonic and melodic minor, Phrygian dominant, double harmonic and Hungarian minor (irregular stacks omitted), contained-triad scales, `#4`/`#5`/`#2` spelling, well-formed triads for every root of every scale, and format-4 gating. Factory `.uge` byte-identical. 0.6.0 installed on the Retroid Pocket Nova and AYANEO Pocket DMG: 12 Android tests passed on each. On the Nova, the pattern panel and header chip showed "C Phrygian dominant" without clipping; the pattern scale was restored afterwards.

Delivery: **0.6.0**, version code **8**, APK **2,486,847 bytes**, SHA-256 `c74c28630e451ed022a8e9c3f303885eb698e03fa815093400f9dc3989c26a57`. Checksum-matching copy at **Download/Thor Tracker/thor-tracker-0.6.0-debug.apk** on the Thor (artifact-only lease; not installed there). Projects saved by 0.6.0 use format 4.

## 0.5.0 Controls screen and single-screen handhelds

| Check | Observed result |
| --- | --- |
| Kotlin suite | 145 tests passed, including keymap routing (chords over popups, ambiguity, repeat/release ownership, rebound menus, Menu/Back fixed, system keys passed through), keymap file round trip/rejections, and 0.4 remap migration |
| Owned emulator (single display this boot) | 12 Android tests passed, including real key events through Menu → Controls: Undo rebound to L3+B and used; takeover prompt left Undo unbound; Edit could not be stranded; export, reset and import through the document-picker path |
| AYANEO Pocket DMG (landscape 1240×1080, 360 dpi, Android 13) | 12 Android tests passed. Pattern, Keyboard (L2+X second saved view), Controls, pattern panel, Sketch and Help inspected without clipping |
| Retroid Pocket Nova (landscape 1280×960, 320 dpi, Android 13) | 12 Android tests passed. Same views inspected with key-only driving |
| Factory `.uge` | Byte-identical |

Handheld runs used each device's exclusive access lease. Thor Tracker 0.5.0 remains installed on the DMG and Nova; the test package was removed, Sortie returned to the front, and each screen returned to the sleep state it was found in. Earlier failed attempts were environmental and are not app defects: a sleeping screen or the Nova's lock screen kept the activity paused (the app ignores keys while paused), and the Nova's vendor accessibility layer returns a null UI tree, so test helpers now click popup rows through the view hierarchy and reach Files through the Menu (the toolbar File button is hidden on narrow displays by design).

Delivery: **0.5.0**, version code **7**, APK **2,486,847 bytes**, SHA-256 `f9768d0950669de228f186e267f9f22eff7e02fb3f8df0103f22894d73b29392`. Checksum-matching copy at **Download/Thor Tracker/thor-tracker-0.5.0-debug.apk** on the Thor (artifact-only lease; not installed there).

## 0.4.1 L2+Right pattern panel

L2+Right is now a host chord that toggles the pattern root/scale/length panel; Select+Right reverts to an ordinary cursor move. Unit suite passed, including routing (chord repeats/release never reach the editor; toolbar focus keeps Right). Complete Android suite on the owned emulator: 11 tests passed, including real key events toggling the panel without moving the cursor and Select+Right moving it.

Delivery: **0.4.1**, version code **6**, APK **2,454,079 bytes**, SHA-256 `c56d1c7dfc6596879cf4c589ce8898d76d2730a8e7789500b5958cf3ad59c036`. Checksum-matching copy at **Download/Thor Tracker/thor-tracker-0.4.1-debug.apk** (artifact-only lease); not installed.

## 0.4.0 pattern length

Owned Android 13 emulator only; not installed on the Thor.

| Check | Observed result |
| --- | --- |
| Kotlin suite | 136 tests passed: lowering (D01 placement, existing-break reuse, hidden rows blank, short final order looping with one `B`), validation (ending-slot conflict, breaks into hidden rows, hidden-row problems ignored), editing (hidden rows survive shorten/insert/delete/lengthen; whole-pattern paste carries length in one undo step), format 3 round trip and version gating, controller panel |
| Complete Android suite | 10 tests passed, including the real driver playing 5- and 3-row patterns: every observed position stayed inside each length, both last rows played, zero driver faults |
| Factory `.uge` | Regenerated file byte-identical to the checked-in example (full-length patterns lower unchanged) |
| Converter | Demo with 24- and 7-row patterns and a final-order loop: gbc-puzzler's pinned `uge2source` exited 0; output had one `D01` per shortened pattern (one sharing a row with a note) and one `B05` loop jump |
| Controller UI | Select+Right, Down, Down, L1×5 set 24 rows; grid ended at row 17, header chip and status reported hidden rows; recovery saved format 3 with `length: 24`. Song view showed `Intro · 40` with a proportionally shorter strip and "Ready to export" |

Delivery: **0.4.0**, version code **5**, APK **2,454,075 bytes**, SHA-256 `0e95debed3ae774187666a3f6c975a013e3304480451a061021e6240af3a432f`. Checksum-matching copy at **Download/Thor Tracker/thor-tracker-0.4.0-debug.apk** (artifact-only lease); not installed. Projects saved by 0.4.0 use format 3 and do not open in 0.3.x.

## 0.3.1 held-trigger hints

Holding L2, R2 or both on the owned emulator (keys held with `input keycombination -t`, paired with unbound L3) replaced the focused display's hint bar with `L2+A Undo · L2+X Swap views · L2+Start Play from cursor`, `R2+A Redo`, and `L2+R2 No chord: release one trigger` respectively; the contextual hints returned on release. Unit suite passed, including a regression that a trigger released under a popup or cleared by pause cannot leave stale hints. Complete Android suite: 9 tests passed.

Delivery: **0.3.1**, version code **4**, APK **2,454,079 bytes**, SHA-256 `5405eb7519e51a6b1318f64d717bad22ea73b3a04f13bdb1c8f19cb479a35378`. Checksum-matching copy transferred under an exclusive artifact-only lease to **Download/Thor Tracker/thor-tracker-0.3.1-debug.apk**; not installed.

## 0.3.0 Sketch and transport binding

Tested on the owned Android 13 emulator at 1920×1080 and a native 1240×1080 secondary presentation, both 369 dpi. It was **not installed or exercised on the user's active Thor session**.

| Check | Observed result |
| --- | --- |
| Kotlin model/editor/export/input suite | 127 tests passed |
| Native actual-driver host smoke | 107 checks, 0 failures |
| Complete Android emulator suite | 9 tests passed |
| Transport bindings | Start began focused order 1 at row 0; L2+Start began at cursor row 40. Select+Start retains toolbar precedence; bare L3+Start now behaves as Start |
| Held Sketch chord | Controller-selected C Major I repeated C-4/E-4/G-4 through the real activity; release and opening a menu cancelled further retriggers |
| Preview engine | The real HugeAudio audition path prepared each preview; song playback stayed in order and advanced without restarting; zero driver faults |
| Document safety | Sketch exploration and auditioning left the song equal and undo unavailable. A separate emulator session kept the recovery project byte-identical |
| Export invariant | Regenerated factory `.uge` byte-identical to the checked-in example |

Runtime visual checks inspected chromatic, C major, A natural minor and A minor pentatonic layouts on the native lower display, plus the full-height top display after a complete-view swap. Labels, seven chord pads, chord-tone markers and the pentatonic explanation fit without clipping. Force-stop/relaunch restored the local C Major Sketch key and note cursor.

No acoustic output was verified. The emulator ran with `-no-audio`, so its two output captures were necessarily silent and carry no evidence about Sketch. Preview evidence is the actual-driver host checks plus the device suite's audition diagnostics (`lastAuditionPrepNs` > 0, zero driver faults) and the observed C-4/E-4/G-4 audition sequence.

Delivery: **0.3.0**, version code **3**, APK **2,569,258 bytes**, SHA-256 `7dfa6f5f056d30793bd9cfa704243ebab9923eebef06337dfac9132b39263c81`. Local artifact: `dist/thor-tracker-debug.apk`. A checksum-matching copy was transferred under an exclusive artifact-only Thor lease to **Download/Thor Tracker/thor-tracker-0.3.0-debug.apk**. It was **not installed**: no Thor input, app launch, settings change or working-document access occurred.

## 0.2.0 playtest-feedback release

This pass was tested on the owned Android 13 emulator, **not installed or exercised on the user's active Thor session**. Visual checks used 1920×1080 and a 1240×1080 secondary presentation, both at the Thor's previously recorded density of 369 dpi. Secondary-display captures were inspected at native resolution. This is layout/input proof, not a new physical-controller, acoustic-latency or ergonomic measurement.

| Check | Observed result |
| --- | --- |
| Kotlin model/editor/export/input suite | 113 tests passed |
| Native actual-driver host smoke | 107 checks, 0 failures |
| Complete Android emulator suite | 8 tests passed |
| Pattern/cursor transport | Focused pattern order 1 started at row 0 with Start and row 40 with L3+Start, despite a different shared selection |
| Trigger ownership | Bare L2/R2 did not consume history; L2+A/R2+A undid/redid; releasing the trigger before A did not insert a note |
| Popup chord precedence | Three failing-before/passing-after regressions prevented popup activation by undo/redo or ambiguous trigger chords and retained global screen swap |
| Actual UI-exported UGE | 68,102-byte file accepted by gbc-puzzler's pinned `uge2source` |
| Factory UGE stability | Regenerated file byte-identical to the original example |

Runtime interaction checks:

- Touch-revealed toolbar hid after its six-second idle timeout. Select+Start highlighted the toolbar, which stayed visible past that timeout; B returned to the editor. Revealing it did not move the grid.
- Chose effect 0 as the first type after no effect: `000` remained silent and visibly selected. Entering the parameter produced `001` arpeggio. An OFF row displayed immediate-silence semantics; L2+A/R2+A removed/restored it without a stray note.
- Set D Major through Select+Right, entered D-4 then E-4 with controller actions, and inspected the scale panel on both display sizes. The recovery document retained root 2 / major and the exact notes, instrument numbers, arpeggio and OFF.
- Opened Song details / tempo from the main menu, with ticks per row selected, and changed 6 to 5.
- L2+X exchanged Song and Wave views while preserving the Wave 3/sample-3 cursor and Song tempo cursor. Single-display swapping also recovered the other saved view.
- Navigated nested app-owned menus with the controller on both displays. Undo/redo over an open popup did not activate Files. Renamed from the secondary display using ordinary keyboard input, with no explicit input-display routing required.
- Disconnected the secondary display with a rename popup open. The popup moved to the primary display and its callback still renamed the document. Cancelled a remapping prompt and returned to normal editing.
- Saved the 14,171-byte format-2 project and exported `.uge` through the real Android document picker. The saved project matched the complete recovery document. After force-stop/relaunch, the UI restored the title, tempo, D Major guide, notes, arpeggio and OFF.

Evidence is under `evidence/feedback-2026-11-05/`. Both original format-1 examples remain unchanged and readable; new saves use format 2. Colour themes and interval labels remain deferred.

Delivery: **0.2.0**, version code **2**, APK **2,437,695 bytes**, SHA-256 `cf9e1d9460fb3edc5e65e927bd27f0058f0fe4d10ef3ab7a82d8786406778a81`. Local artifact: `dist/thor-tracker-debug.apk`. A checksum-matching copy was transferred under an exclusive artifact-only Thor lease to **Download/Thor Tracker/thor-tracker-0.2.0-debug.apk**. It was **not installed**: no Thor input, app launch, settings change or working-document access occurred.

**The remaining sections record the original 0.1.0 MVP delivery. Its native-dialog and bare-trigger controls are superseded by 0.2.0 above.**

## Scope and limits

The application, editor, storage and actual-driver playback are implemented. Verification used an Android emulator and an AYN Thor running Android 13, with logical displays 0 and 4. Thor access began after the authorized midnight boundary under an exclusive device lease.

Controller checks injected Linux key events through the Thor's actual `Odin Controller` event node and key layout. They exercised Android dispatch, modifiers, native menus and the application together. They were **not human-held physical-button or ergonomic tests**. Touch checks used Android input injection. Screenshots and recordings were inspected for layout and interaction feedback.

The remaining human check is important: listen and judge repeated edit/audition/play responsiveness. The current Thor audio configuration adds approximately 100 ms of timestamp-measured output latency. No microphone, acoustic loopback or physical button-to-speaker measurement was used. The implementation does not wait for a compiler or restart the song after ordinary live edits, but automated measurements do not establish that it feels like a desktop DAW.

## Automated results

| Check | Observed result |
| --- | --- |
| Kotlin model/editor/export/layout suite | 101 tests, no failures or errors |
| Native actual-driver host smoke | 107 checks, 0 failures; WAV and per-tick APU trace generated |
| Android controller/recovery tests on emulator | 4 tests passed |
| Complete Android suite on Thor | 6 tests passed |
| Thor audio lifecycle stress | 50 consecutive open/play/close cycles passed |
| Thor playback/live-edit/audition soak | 30 seconds, 0 xruns and 0 driver faults |
| Factory UGE in isolated gbc-puzzler | Pinned converter accepted it; debug ROM built; sound smoke reported 0 failures, 63 redraw checks and 59 flush checks |
| Hardware-composed Controller Study UGE | 73,594-byte export accepted by the game's pinned `uge2source` |

The original gbc-puzzler working tree and music were not used as an integration scratch directory. The game smoke ran in `.tools/game-smoke`, made from an archive of the game's HEAD.

## Actual composition workflow

`examples/controller-study.thor` was composed through the running application, then saved and exported with Android's document picker:

- Entered C4, D4, E4, G4 with A + D-pad; marked/copied/pasted the phrase with Y and Select combinations.
- Added pulse harmony, wave bass and noise percussion; edited pulse duty/volume and noise envelope/length settings.
- Drew all 32 waveform samples with a touch stroke. One undo/redo restored the entire stroke. Generated a sine in wave F and assigned it to wave instrument 01 through the Wave view.
- Cloned a pattern, repeated it in the arrangement, moved orders, retained the loop's target, and changed tempo from 6 to 8 ticks per row.
- Entered 037 arpeggio and D01 pattern break using the effect fields. The song has three orders and loops to order 1.
- Keyboard Listen mode left the document unchanged. Write mode inserted G4/instrument 1 at the shared cursor; a single undo restored the exact song.
- Saved `.thor`, exported `.uge`, transferred both off the device and reopened the saved project. The reopened song equaled the composed document exactly.

## Display and controller checks

- All six views were visually inspected at the Thor's actual display sizes. The compact single-row toolbar leaves 24 pattern rows visible while retaining readable text.
- Swapped Pattern and Help between displays; the complete view states moved with them. Pinned Help remained in place when the other display changed selection. Context mode switched between Keyboard, Instrument and Effects help.
- Locked an instrument while changing the other display's channel; changing editor kind released the old kind's subject lock.
- Opened Pattern on both displays with subject following disabled. The upper cursor stayed at row 00 while the lower cursor advanced to row 06. A note entered in lower row 05 appeared in both views; shared undo restored the exact document.
- R3 moved the amber controller-focus indicator. Touching the other display did not steal it. Lower-screen controller focus survived leaving and returning to the activity.
- Native menus and nested rename dialogs opened on their originating display and accepted D-pad/A input. Touching the other display dismissed the menu without changing logical editor focus.
- Remapped Undo to L3, edited and undid through that binding, repeated after process restart, then restored default bindings.
- On the owned emulator, disconnected a secondary display while its Wave view had focus. Input fell back to the primary display without changing the song. Reconnection restored Wave 3, sample cursor 3 and pin state.

## Bugs reproduced and fixed during the hardware pass

- Secondary-display dialogs previously appeared on the primary display and did not receive normal controller navigation. Dialogs now use the originating display's context/window type rather than manually forwarding keys across windows.
- A view-kind change could reinterpret an old instrument lock as a wave index. Locks now remain scoped to their editor kind.
- Native focus after presentation recreation could consume the first D-pad movement. The tracker now explicitly restores native view focus without changing logical controller ownership.
- Following another display during a slider drag changed a second instrument midway through the gesture. The fixed build retains the original bank/slot/parameter and defers following until release.
- Undo during a touch drag allowed later moves to overwrite the undo and create separate history entries. Invalidated gesture tokens now stop further edits until lift.
- Playhead following moved the grid under a held touch: a five-second press on row 00 selected row 31. The fixed build kept row 00 selected; transport continued while navigation was held stable.

Each of the last three failures was observed on the old APK and the same scenario passed on the corrected APK.

## Save safety and interruption checks

- A deliberately stalled document-provider save did not block recovery of newer edits.
- Completing an older save after a new document was opened did not attach the old destination to the new document.
- Process death while the document picker was open retained recoverable working edits.
- A second interrupted-save scenario deliberately changed recovery to unsupported project version 999. Returning the picker result did not write the fallback demo into the destination: the destination remained zero bytes, the invalid recovery was quarantined byte-for-byte, and one error dismissal returned to the editor without a stacked second dialog.
- Recovery and external saves remain separate; provider-controlled writes are not represented as universally atomic.

## Audio evidence

The last complete Thor regression run reported:

- 48 kHz stereo float output; 960-frame burst and 1,920-frame buffer.
- Worst callback 2.35 ms; seek preparation 0.14 ms; audition preparation 0.12 ms; live-image application 0.07 ms.
- 0 xruns, 0 driver faults; output timestamp latency 4,817 frames, approximately 100 ms.
- Observed engine-position start 61 ms, **not acoustic latency**.

A separate 30-second run reached a worst callback of 2.79 ms and output latency of 102–106 ms without underruns. The output-mix software equalizer registered by the Thor firmware prevents FAST mixing even while disabled; user audio settings were not changed to work around it. See [AUDIO_ENGINE.md](AUDIO_ENGINE.md) for the evidence and live-edit/seek semantics.

Actual Thor digital output was captured for 14.87 seconds: stereo 48 kHz, peak 0.05615, RMS 0.01601, no clipped samples and no exact internal silence longer than 0.94 ms. REMOTE_SUBMIX capture had to start before opening AAudio; recording an already-open stream returned silence on this firmware. The alternate playback-duplication capture API failed to register an audio policy. These capture limitations do not establish a failure of normal headphone playback.

## Interface feedback

Recorded and inspected short note-entry/undo glows, keyboard strike/release feedback, write-mode confirmation, channel activity lamps and focus changes. Layout coordinates remain stable. Keyboard hit rectangles are reused across animation frames. On the owned emulator, Android animator scale 0 produced the edited state without a running afterglow; the previous setting was restored. No Thor animation setting was changed.

Local screenshots, MP4s, audio captures and routing dumps are under the gitignored `evidence/` directory. The deliverable examples are under `examples/`, and the installable debug APK is under `dist/`.

## Delivery and device return

- APK: `dist/thor-tracker-debug.apk`, SHA-256 `a76524d2e7926506e44aca7d42300a888d1cca28d6d443298e093684c489a831`. The adaptive launcher mark was checked through Android's application-details surface after the final resource-only build.
- Both examples and the APK were copied to **Download/Thor Tracker** on the Thor; all five device hashes match the workstation artifacts. `dist/SHA256SUMS` verifies the local delivery.
- Left Thor Groove loaded at row 00, with Pattern + Keyboard, primary controller focus, default bindings and stopped transport. Both displays were verified OFF after putting the device to sleep.
- Removed the test-only instrumentation package and temporary UI dump. Stopped the owned emulator without stopping the shared adb server.
- Six Sortie configuration files remained byte-identical. Its playlog hash changed and the last record was a prior Glittersmash session ending at 00:02; that legitimate history was preserved rather than rolled back.
- System brightness and floating-icon values differed from the lease baseline and were restored to their original values. Secure/global settings were unchanged; the monotonically changing display-focus counter was retained.
- Released the exclusive Thor lease at **03:01 America/New_York**. No Thor ROMs or saves were touched; no unrelated package was removed and no microphone was recorded.
