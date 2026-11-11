# Thor Tracker verification — 2026-11-11

## 0.41.0 — TIA four columns and workflow hardening

**Version 0.41.0/code 55 is published.** TIA NTSC and PAL now
accept four ordered effect columns per channel. The registry's 43 systems comprise **2
one-column, 3 two-column and 38 four-column variants**. Project/backend/image schemas are
unchanged; old two-column TIA players reject third/fourth-column content rather than silently
dropping it. This adds no voices or 6507 driver, and WAV export is not cartridge CPU-budget proof.

The TIA carried-state/inspector and fine-pitch adapters follow the real player's ordering:
last G00 cancels an earlier delay, a later slide clears portamento, the last P controls detune,
and returning an upward tuning to a coarse note retains P80 where removal would uncover an
earlier P. Help clarifies Columns, hidden effects and active shortcuts. **B remains Clear;
Select+B remains Cut cell/block.** Focus loss or controller removal/change cancels held edit,
Listen and modifier state without synthesizing a tap; focus-only cancellation does not stop
transport, whereas Activity pause does. Interrupted shortcut capture is also canceled, including
configuration changes: an invisible held modifier cannot leak into the next binding attempt.
Menus no longer start a zero-distance scroll when already at the top: that animation could
swallow the next fast tap. The unchanged real Save As touch scenario fails before this fix
and passes afterward, without adding a compensating delay to the test.

### Automated candidate evidence

- **1,112 core JVM and 8 Android host tests pass.**
- **All 29 native suites pass**, including **3,786 TIA checks and 2,796 WAV checks**.
  Optional EPSM/OPNA rhythm-ROM checks are explicitly **skipped because no ROM is installed**;
  they are not passing firmware-backed evidence.
- Independent TIA verification covers **1,500 ticks**, NTSC/PAL pitch, and **four new
  192-tick static/live-write and cadence comparisons**. Both region-specific four-second CLI
  WAV exports complete.
- An additional native NES continuity probe compares FDS/S5B no-op live image application:
  **408,926 interleaved stereo float samples are bit-identical**. Edited-future register-write
  positions agree within **two CPU clocks**, but their PCM is **not bit-identical**.
  Do not extend the no-op PCM result to edited/relocated songs.
- **172/172 existing sample exports are byte-identical to 0.40.0**, with no added or removed
  exports. Evidence: `workflow-touch-041/sample-compatibility.json` under the recovery root.

### Actual Android workflows

- **Nova, full-song:** real controller input composes a three-order arrangement using Repeat,
  Clone and loop selection, copy/cut/paste and Undo. Actual project save/reopen, Activity
  recreation, advancing playback and UGE export pass. WAV follows **Files → Export .wav →
  length → WAV ready → Save .wav… → DocumentsUI**; saved RIFF/PCM passes format/data-length,
  AC-energy and first-200ms checks and its bytes match an independent native render.
- **Konkr, TIA NTSC/PAL:** actual fourth-slot editing changes **F06 → F07 during playback**;
  playback keeps advancing without errors. Both projects pass real SAF save/reopen and
  Activity recreation with all four columns preserved. Pattern and Controls/Effects Help
  captures are retained. The actual PAL SAF WAV has the same PCM as the independent render.
- **Konkr permanent Android contracts:** **28 tests, zero failures**. The new capture regression
  fails on the preserved unfixed candidate and passes after the fix, covering focus loss,
  controller removal/change and configuration changes, followed by actual unmodified R3 audition.
- **Nova interruption regressions:** the same three focused probes fail on exact **0.40.0**
  baseline and pass on **0.41.0**: held edit across focus loss, held Listen across focus loss,
  and a held modifier across the disconnect callback. The harness first removed an invalid
  assumption that a canceled key-up must be consumed. These are real Activity callback/input
  probes, **not claims of physical controller disconnection testing**.

Every run cold-restores the **eight original private entries**, removes its temporary test
package and restores device settings. Document cleanup is restricted to the logged UUID-owned
filenames: three for full-song (`.thor`, `.uge`, `.wav`), three for TIA (two `.thor`, one `.wav`).
Activity recreation in the workflow is distinct from the cold-process restoration proof.
Saved WAV energy is not an AAudio-output or speaker-listening measurement.

Evidence is retained under
`~/.local/state/thortracker/recovery-20261109/workflow-touch-041/`:
`candidate-device-v2/` contains workflow/callback transcripts, captures, owned exports and
restoration receipts; `native/` contains the native run artifacts, alongside the independent
TIA/NES probe evidence and NTSC/PAL CLI WAVs in the recovery root. External harnesses/probes
are not production source. Builds and devices remain serialized and memory-capped.

### Separate portrait preview and remaining gates

The portrait preview remains a **separate package and branch**, not production touch support.
**19 host tests and 13 real Android scenarios pass on DMG**, which is a functional testbed only.
The design target is **Galaxy Fold8**, with a 144 dp three-row dock and bounded thumb groups.
Five native cover/inner ratio renders pass geometry checks, alongside real multitouch,
binding capture, interruption, provider and Save As picker workflows. These are not phone
captures or physical ergonomics verification. Next is joint prototype review and soft-launch
hardening, not new backends. Swing, mixer ceilings, pattern-local mute and shaders remain deferred.

The [isolated preview APK, source and review notes](https://github.com/esko997/thortracker/releases/tag/touch-preview-0.41.0-1)
are published under immutable tag `touch-preview-0.41.0-1` (`1a5e049`). The downloaded APK
matches SHA-256 `ba4c43de14325642a26404217caba70acbf93aab2a599f43c2100f671fe13e99`,
uses the production signing certificate, and installs as `org.thortracker.touchpreview`,
version `0.41.0-touch-preview.1`/code 55, arm64 only. DMG has a clean review installation.
The phone-only `ThorTracker-Preview` Syncthing outbox contains the same APK and checksum;
phone receipt/installation is not claimed. Its app-scoped document root is deleted on uninstall.
Production version checks exclude this separate-package prerelease.

### Official publication and delivery

PR #55 merged as main `089ac739266c60c33b4d3f4fcac554c10cc69c35`.
All five jobs in [publication run 42926200711](https://github.com/esko997/thortracker/actions/runs/42926200711)
pass: build, Deck, Brick, signed release and native attachment. The
[official 0.41.0 release](https://github.com/esko997/thortracker/releases/tag/v0.41.0)
contains verified checksum sidecars:

- APK: `c2bbcf07bb78237e7b4c4af601da31d6ef68b9507c034fd3877ac14569834aa2`
- AppImage: `a0f41cbe2d9f4bd4991722e2e908055a541406b1a7bff0e4a360e8fa1285a161`
- Brick Pak: `5386021e67ee007d069ced0608aab691c8af95317cdd9d614737dd0cd2c821bf`

The APK is `org.thortracker`, version 0.41.0/code 55, non-debuggable, with arm64-v8a/x86_64
libraries and production certificate
`d12064692cbaa5c2feecf3cf5a2d3719a088e7bafe1ffa55e1f00aeecafed469`.
The 24-entry Pak passes CRC checks and retains executable launcher/AArch64 payload permissions.

The **published APK** repeats full-song composition/save/reopen/UGE/WAV on Nova and both TIA
regions' four-column/live-edit/save/reopen/PAL WAV workflows on Konkr and DMG. Their exported
WAVs are byte-identical to the earlier independently rendered fixtures. Original eight-entry
private state is cold-restored, UUID-owned documents and test packages removed, and settings
restored. Final read-only checks show 0.41.0/code 55 on all three, 60,000 ms timeouts, Nova/Konkr
Asleep and DMG Dozing. The separate DMG preview remains installed. Nova's first reservation
deferred to a brief PS2-card sync lease; no app test ran until that owner released it.

The **published AppImage** runs both actual Android-saved four-column projects in isolated
1024×768 offscreen/dummy-audio sessions: Play → Instrument → held X → Pattern. NTSC/PAL
playback reaches rows 43/35, each accepts one audition, with audition peaks 0.1652832/0.16616821
and playback peaks 0.14242554/0.14297485. Screenshots show all four effects in the inspector.
This is actual-binary PCM/UI evidence, not physical speaker listening.

The Deck received the checksum-verified AppImage without launch or Gaming Mode input.
`~/thortracker-deck/thor-tracker` now points to `thor-tracker-0.41.0-x86_64.AppImage`; the old
0.40.0 file remains. **Thor and Brick were not accessed.** No new physical Deck/Brick runtime
proof is claimed. GitHub Pages deployment `42926200785` passes; the deployed Systems page's
four-column matrix/TIA text was inspected in Chromium.

Published evidence is under `workflow-touch-041/official-release/`, `official-device/`,
`official-desktop/`, `official-device-final-state.json` and `deck-delivery.json` beneath the
recovery root above. Disposable verification sources are removed; logs, APKs and captures remain.


## 0.40.0 — NES multi-effect support and editing hardening

Version **0.40.0/code 54** gives all **15 NES/FamiStudio variants four effect columns**, including
EPSM and every N163 layout. Independent attributes compose left to right; duplicates, shared
volume/slide targets and global speed have explicit precedence. Preview, NSF and FamiStudio text
use the same lowering. Existing export exclusions remain; MMC5 PCM cannot combine delay and cut,
and engine-lane cuts cannot precede delayed notes. The project/cell schema is unchanged.

Official [0.40.0](https://github.com/esko997/thortracker/releases/tag/v0.40.0) is published from
main `80929d833bdcc6ccd60780c55b7ec13eac4e5e58`; all five jobs in
[run 42885282575](https://github.com/esko997/thortracker/actions/runs/42885282575) pass. Downloaded
APK/AppImage/Pak match their checksum sidecars. The APK is non-debuggable, code 54/version 0.40.0,
signed by the production certificate, with arm64-v8a and x86_64 libraries. The 24-entry Pak passes
CRC validation and retains its executable AArch64 payload and launcher.

The **published APK** passes the reversible NES four-column editing/playback/export/save/reopen
scenario on Nova, DMG and Konkr; captures are inspected and original private bytes/settings are
cold-restored. Final read-only checks confirm 0.40.0/code 54, no test package and the original
60,000 ms timeout on all three. Nova/Konkr report Asleep; DMG reports Dozing after Home/sleep.

The **published AppImage** passes an isolated 1280×800 offscreen/dummy-audio run with live
F08→F09 editing, Undo, held Instrument audition and return to Pattern. Playback advances to
order 0/row 11, with one audition, audition peak 0.245156 and playback peak 0.151235. Its saved
working document exactly matches the starting project after Undo, and its surface is inspected.
The candidate additionally passed the compact 768×576 held-empty-FX4/Undo scenario. These are
actual native-binary checks, not physical Deck listening or new Brick hardware verification.

Thor receives only the verified APK at `Download/Thor Tracker/thor-tracker-0.40.0.apk`; no wake,
install or launch. The reachable Deck receives the AppImage and an atomic update of the existing
`~/thortracker-deck/thor-tracker` symlink from 0.39.0 to 0.40.0; both remote hashes match.
Old AppImages are retained. The user's Brick session remains untouched.

| Official asset | SHA-256 |
| --- | --- |
| APK | `a64034f7e21a3d65b4b8bafebdfb8741b12b9239a809a72e002d8f40e4d0a3ee` |
| x86_64 AppImage | `7e9d6980d913f7771d9cc2d75345e991a0c29222dff052d7177f22f228e6ac87` |
| NextUI Pak | `498c67b6eeba72ca276b0f400e988ad6790505ab7178185b08b95346884ed93c` |

Pages run `42885282733` builds/deploys successfully. The published
[effect-column matrix](https://esko997.github.io/thortracker/systems/#multiple-effects-per-row)
shows NES in the four-column row, with the deep link working and no page-wide overflow at 390 px.
Official artifacts, signature/metadata checks, delivery and restoration receipts are retained under
`recovery-20261109/official-0400/`; native frontend evidence is under `nes-effects-hardening/desktop/`.

The compiled registry reports **2 one-column, 5 two-column and 36 four-column systems**. All
**172 existing sample exports are byte-identical** to 0.39.0, with identical file sets; the optional
EPSM rhythm-ROM fixture is excluded from both runs. A real 0.39.0 reader explicitly refuses a
new multi-column NES project rather than losing its later effects.

The capped local build passes **1,108 core JVM tests and 8 Android host tests** and produces the
production-signed release APK/instrumentation. Nova passes all **24 permanent Android tests**,
zero ignored/failing cases, inside the separate private-backup/cold-restore wrapper.

The actual native NES machine passes **18 × 256 frames** with exact register-command and
order/row/tick agreement between a future-edited reference and a live image replacement, plus
**90 direct-seek comparisons**. Cases cover all 15 variants, G02/S05, G02/S02 and four-column
DPCM. Independent py65 execution matches **nine 256-frame comparisons**: five of Thor's NSF
exports and four independently imported/exported by unmodified FamiStudio 4.5.3. DPCM sample
addresses relocate, so their actual 1,041-byte payloads are verified before comparing commands.
G02/S05 starts at tick 2 and cuts at tick 5; G02/S02 remains silent in native and reference runs.
Four-column MMC5 PCM renders through the production engine and equals the effective three-command
image byte for byte. Invalid cut-before-delay and PCM delay+cut fixtures fail with row/channel context.

Separately laid-out edited images are **not claimed PCM-bit-identical**: some first differences
occur before live replacement, alongside one/two-cycle driver-call differences. The traces compare
register order/values and logical timing. Measured peak driver calls range up to 13,918 cycles in
these fixtures; that is evidence for these songs, not a worst-case console CPU-budget guarantee.

Actual Activity/controller scenarios pass on DMG and Konkr: four-column editing during playback,
Undo, WAV/NSF/text exports, exact save/reopen/restart, and EPSM's last channel at 75/150% zoom.
Surface captures are inspected. A baseline compiled-core reproduction shows held automatic-column
shrink retargeting an edit and channel-16 transient cell-key collisions; the corrected build retains
the held field, restores the exact song with Undo, and keeps high-channel keys distinct.
The real Konkr scenario verifies held-column behavior on both OPL3 and NES and clipboard-neutral
Clear. Before/after Android captures show a rebound Focus hint changing from literal R3 to Sel+R3.

Renewed baseline POKEY first-note/live-edit, PCE noise/DDA and navigation/save/reopen scenarios also
pass on Nova, DMG and Konkr. Each device run uses its exclusive ledger lease, restores original
private bytes and settings in a cold process, removes its temporary test package, returns Home
and requests sleep. `/ThorTunez`, Thor and the user's active Brick session are untouched by these tests.

Swing remains **research only**. A native Banjo header-8/4 spike verifies first-row initialization
and repeated 1/2/3-row boundaries; odd tails still change total pattern duration. The cost review
distinguishes one owned scheduler, NES lowering, restricted Banjo, SID and Game Boy integration.
Mixer ceilings, pattern-local mute, touch implementation and shader pipelines remain deferred.
The touch proposal records portrait controller-dock/drawer tradeoffs; B remains Clear, with existing
Select+B Cut cell/block rather than silently replacing the shared clipboard.

Evidence: `recovery-20261109/nes-effects-hardening/` contains before/after editor output, native
position/register traces, independent-reference results, sample fingerprints, device captures,
exact restoration receipts and the timing spike. No temporary harness/probe is committed.

## 0.39.0 — persistent view zoom and documentation

Version **0.39.0/code 53** adds per-view content zoom at 75/100/125/150%, a focused-view menu
with reset, and deliberately unbound rebindable controls. Android saves each view's preference;
Linux handhelds save their single-view preference. Menus and shell touch targets keep their
physical size. Song data, playback semantics and exports are unchanged.

This release also includes the canonical static documentation site and design-only mixer
capability record. Pattern-local mute remains explicitly deferred by the user; no mute or mixer
feature is advertised as implemented.

Official **0.39.0** is published from main `ac5c92c`; all five jobs in run `42855741540` pass.
Downloaded APK, AppImage and NextUI Pak match their checksum sidecars. The APK has the production
certificate, code 53/version 0.39.0, both supported ABIs and a non-debuggable manifest. The 24-entry
Pak passes CRC validation and contains an executable AArch64 binary and launcher.

The actual official AppImage passes 150% zoom, advancing playback, held Instrument audition and
return to Pattern: one audition, PCM peak **0.404798**, order 0/row 50. Its surface is inspected;
this is offscreen/dummy-audio evidence, not physical Deck listening. The actual official APK passes
the reversible zoom/controller/persistence scenario on **Nova, DMG and Konkr**, with captures
inspected and every original private entry cold-restored. Temporary test packages are removed;
settings are restored and the three devices returned Home/asleep. `/ThorTunez` is untouched.

Thor receives only the verified APK in `Download/Thor Tracker`; it is not woken, installed or launched.
The reachable Deck receives the verified AppImage and its existing `~/thortracker-deck/thor-tracker`
symlink now targets 0.39.0, without launching it. The user's Brick session is not accessed.
Published artifacts and delivery receipts are under `recovery-20261109/official-0390/`; runtime
captures and restoration evidence are under `recovery-20261109/workspace-qol/`.

The capped local build passes **1,090 core JVM tests and 8 Android host tests**, and builds the
production-signed release APK plus external instrumentation. Nova also passes all **24 permanent
Android tests**, with zero ignored/failing cases, inside the external backup/cold-restore wrapper.
The first core run exposed an obsolete all-actions-must-be-bound assumption and a compact fixture
accidentally testing a no-op zoom while manually suppressing scrolling. The collision invariant
remains; each compact fixture now starts at a different zoom. Production behavior was not changed.

Nova, DMG and Konkr pass actual Activity/controller zoom menus, 75/150% extrema, the expanded
last effect field, custom bindings and limits/reset, zoom during advancing playback, exact
project/history invariance and Activity restart with 125% retained. Captures are inspected;
the Nova comparison is included in the manual. These are single-display hardware checks;
complete-view swaps and independent pane persistence additionally have shared/JVM coverage.
Thor and the user's active Brick session are not interrupted.

The actual CI AppImage from `c3f5a54` passes 75/150% compact rendering, a fresh-process
150% reopen, reset to 100% and malformed-preference recovery. Menus stay at their normal physical
size. The working project remains byte-identical to the official 0.38.0 fixture across all
operations. Downloaded AppImage and ARM64 Pak checksums match their sidecars; the Pak passes CRC
validation. Candidate build run `42852014698` passes all three platform jobs. This is local
offscreen/dummy-audio native verification, not a new physical Brick or Deck session.

Bounded hardening repeats eight POKEY first-note/live-edit/audition/restart cycles on Konkr and
eight cycles each for PCE noise and DDA snares on DMG. Illegal PSG1 noise assignment leaves the
document unchanged; legal PSG5 assignment plays. Offline first-200ms AC energy is positive before
and after, with identical measured peaks/RMS for the unchanged fixtures. No driver faults occur.

Nova's real SID scenario runs eight seconds of playback, eight with held audition, paired live
edits, and four seconds of stopped audition. The adaptive buffer grows to **960 frames / 20 ms**,
with **three startup/transition xruns** and zero driver faults. Accepted-run worst callback is
**16.109 ms**; an earlier run measured **18.120 ms**. This is limited headroom, not a glitch-free
or acoustic-listening claim. A separate bounded 16-row SID WAV has peak 7423 and AC RMS 1613.68.
No fidelity reduction or audio-engine change is made.

The external harness initially required a non-null accessibility root despite actual Activity
window focus, rejected the new recovery case names, and used a four-second short-fixture assertion
on the full SID demo. Those harness constraints were corrected; every affected run's original
eight private entries were subsequently cold-restored and verified. Settings are restored and
temporary test packages removed; `/ThorTunez` is untouched.

Documentation builds 15 pages with **13 transformation checks** passing. Two unchanged-input
builds produce identical bytes for all 42 output files. Actual browser checks cover light/dark
themes, reduced-motion preference, keyboard skip/disclosure/scroll controls, JavaScript-disabled
navigation, loaded local fonts/images and 200% CSS zoom. Eight guides plus the manual have no
page-wide horizontal overflow at 320/844/1440 CSS pixels. The corrected Pages workflow passes
candidate dispatch `42853011146`. Main Pages run `42855741732` builds and deploys successfully.
The public site at `https://esko997.github.io/thortracker/` passes mobile navigation, download links,
the zoom deep link, loaded images/fonts, no page-wide overflow and the styled HTTP 404 page.

## 0.38.0 — colour themes and Brick Hammer / NextUI

Version **0.38.0/code 52** adds original Slate, Paper and Contrast workspace palettes and an
ARM64 NextUI Pak. Slate preserves the original appearance. Theme selection is outside the
musical document and does not add an Undo entry. Android candidate source is `1943ce6`;
the native implementation exercised on hardware below is `046d897`. The final Help copy also describes
the diagnostics file in the projects folder on Linux handhelds, rather than naming only the Deck path.

Official **0.38.0/code 52** is published from main `660e090`; all five jobs in run `42850250052`
pass, including signed APK and combined native-asset publication. Downloaded APK/AppImage/Pak
checksums match their sidecars; APK certificate, non-debuggable manifest and both Android ABIs
are verified. The Pak's 24 entries pass CRC validation and retain executable launcher/binary modes.
The actual official AppImage advances to order 0/row 58 through held Instrument audition and a
return to Pattern, with PCM peak 0.413676; the resulting surface is inspected. This remains
offscreen/dummy-audio evidence, not physical Deck listening. The final candidate Help/About
diagnostics wording was also exercised and inspected before merge. Published artifacts and
manifest evidence are in `recovery-20261109/official-0380/`.

- The bounded local build passes **1,084 core JVM tests and 7 Android host tests**. Nova passes
  **24 permanent Android instrumentation tests** against the production-signed release APK.
  The mixed-audio case reports one initial xrun, a 576-frame adaptive buffer and zero driver faults.
- Nova, DMG and Konkr each pass real Activity/controller selection of all three palettes,
  Pattern/Instrument/Keyboard/Help/menu capture, unchanged project bytes/document version/history,
  Activity recreation with Contrast retained, and advancing playback afterward. This is Activity
  restart evidence, not a claim of a separate process-death persistence scenario or human listening.
- Paper and Contrast keyboard focus now have contrasting key-specific rims on both white and black
  keys. Effect-help cached rows are rebuilt when their palette changes, avoiding stale explicit colours.
  Captured keyboard surfaces are inspected; the Help cache regression passes in the core suite.
- Before that final Help-copy clarification, **32/32 complete Slate RGBA captures are identical**
  to the official 0.37.1 same-device baseline, covering 16 Game Boy views at 1920×1080 and 1240×1080.
  The About diagnostics wording is an intentional subsequent text change, not a palette change.
- The actual CI AppImage from `44abe41` selects all three themes through the shared controller menu,
  plays the factory song with PCM peak 0.509230, and restores Paper/Contrast in separate fresh processes.
  The working project remains byte-identical. Compact 1024×768 keyboard runs produce held-audition PCM
  peaks 0.119800/0.119781 and show readable black/white key focus rims. A nine-lane OPL3 fixture retains
  its exact project bytes through collapse, horizontal edge jumps to channels 8/0, a vertical jump to
  its shortened pattern's row 15, and advancing playback with peak 0.116943.
- Native surfaces are inspected at 1280×800 and 1024×768. These runs use offscreen video and dummy audio:
  no physical Deck performance, acoustic output or Brick rendering claim is implied.
  AppImage SHA-256: `b801e1431698f6f24a17539224faf0adc02989ee58723cde26986eca108bc9f4`.
- Source `046d897` passes all three jobs in [CI run 42820497888](https://github.com/esko997/thortracker/actions/runs/42820497888):
  Android/core/native tests, desktop AppImage build/boot, and ARM64 Pak build/ABI packaging.
  Its final AppImage repeats controller theme selection, fresh-process Paper restoration, exact
  project preservation, playback and compact Instrument held audition. SHA-256:
  `843d03d383c43ba94329a9e38d092d50ea62ca67ec0fc6b12a6d12e7182c49c9`.
- Each Android live theme scenario uses an owned fixture and a separate cold restoration test. Eight original
  private-file entries are restored byte-for-byte; `/ThorTunez` is untouched. Test packages are removed,
  original screen timeouts restored, devices returned Home/asleep, and leases released.
- APK SHA-256: `bf287a90b5a3e24bbc46c15d202f6bce1701f2d4e62566ebd4b992ff87046f12`.
  Production certificate SHA-256:
  `d12064692cbaa5c2feecf3cf5a2d3719a088e7bafe1ffa55e1f00aeecafed469`.
  This candidate is on Nova, DMG and Konkr; Thor and Deck have not been changed.
- The Brick Pak is built: 7,874,145 bytes, 24 ZIP entries, correct executable modes and passing CRC.
  It shares the complete native frontend/engines and uses vendor SDL2 with external `/mnt/SDCARD/ThorTunez`
  data. Fonts include their OFL licence; the Pak includes GPL/engine notices and no vendor shared libraries.
  The actual ARM64 ELF requires at most GLIBC 2.17, GLIBCXX 3.4.22 and CXXABI 1.3.3, below the captured
  firmware limits. `--as-needed` removes unused toolchain crypt/util/resolver dependencies rather than
  weakening the ABI allowlist. Pak SHA-256:
  `09a90fc92af295a7c00010f9092f34917fa317e24db3e4edd853b2c251c6d86e`.
- The unchanged CI Pak now runs through the Brick's normal **Tools → ThorTracker** launcher:
  vendor SDL **2.30.8**, real **Mali/ALSA**, **1024×768**, X360 controller mapping and a 1,024-frame
  48 kHz stereo device buffer. Its installed executable SHA-256 matches the CI ELF:
  `688d747f0c1652ffe5f1cec9172f322ced9fad8e3dfe91be115bfe2bf16c4158`.
- Actual evdev input passes R2 view switching, cumulative L2+R2 → Pattern, L2+Left collapse,
  held-A note editing and L2+A Undo. The real file browser saves and reopens the factory project
  byte-identically. Paper survives Quit and a fresh Pak launch; NextUI's Tools screen returns normally.
  Inspected surfaces include Pattern, Keyboard, collapsed lanes, menus and Help/About.
- Six separate **25-second real-hardware playback runs** produce nonzero PCM and advancing orders,
  with zero driver faults: Game Boy, NES, SID, OPL3, Mega Drive and NES+EPSM.
  Recorded worst callbacks are respectively **0.817, 4.344, 12.204, 8.299, 11.135 and 13.819 ms**.
  NES/SID/OPL3/Mega Drive/EPSM report zero late callbacks and no ALSA underruns. The GB timed case
  records **one late callback and one recovered ALSA underrun**; a separate preceding GB session
  records **12,019 callbacks, zero late callbacks and zero faults**. This is bounded measurement,
  not a claim that all workloads are glitch-free.
- These are actual generated PCM/callback and inspected framebuffer results, not acoustic/DAC
  measurements. Physical suspend/resume remains untested. No Miyoo Mini runtime is claimed.
  USB access was lost at idle NextUI both before the initial Tracker launch and after later Quit.
  The user subsequently disabled sleep while USB-connected; the remaining checks and cleanup completed.
  This establishes the observed sequence, not a diagnosed cause of the earlier disconnects.
- SID, OPL3 and Mega Drive also pass 8-second held audition during playback, followed by 3-second
  audition-only runs: two accepted auditions each, nonzero PCM, zero late callbacks, no ALSA underruns
  and zero faults. Worst callbacks are **19.638, 10.531 and 18.266 ms** respectively.
  The device period is **21.333 ms**: SID's measured maximum consumes about **92%** of one period,
  leaving only **1.695 ms**, not a comfortable worst-case margin. The `late callbacks` diagnostic
  counts sounding callback gaps above **two periods (42.667 ms)**; zero is not proof that every
  callback met a one-period deadline. ALSA underrun log counts are reported separately.
- EPSM FM subsequently passes the same mixed/solo audition scenario with a valid FM sound selected:
  two auditions, PCM peaks **0.024737/0.019331**, **741 callbacks**, **zero late callbacks, faults or
  ALSA underruns**, and worst callback **12.053 ms**. Physical X presses in Instrument and Keyboard
  independently produce two accepted auditions and PCM peak **0.119781**, without ALSA underruns.
- The identical EPSM fixture/control sequence on the production x86_64 AppImage yields peaks
  **0.025635/0.019727**, versus Brick **0.024737/0.019331** (differences **3.63%/2.05%**).
  The lower EPSM levels are therefore also present on desktop, rather than evidence of a large
  Brick-only attenuation. These are wall-clock-controlled peak windows on the same engine, not
  sample-exact or independent-reference validation of absolute loudness.
- After reconnection, the user's newly composed SCC working song, previous-project recovery file and
  Contrast preference remain byte-identical through the isolated checks. Only the owned smoke Pak,
  test workspace and unchanged test diagnostics report are removed. The production Pak is reopened
  in Song view; the user's USB sleep setting is untouched and the canonical device lease is released.
- The six timed playback runs average **56 frames/s** including cold UI work. The separate GB session
  uses **51,272 KiB RSS**, with a **58,716 KiB high-water mark**, five threads and no swap.
  These are specific observed workloads, not peak-memory bounds for arbitrary songs.
- All Kotlin/Native linking remains on CI. Local Android/JVM work used one capped build at a time;
  no emulator or local native link was launched.

Evidence: `recovery-20261109/brick-themes/`, especially `nova-permanent-tests.txt`,
`theme-smoke-fixed/{nova,dmg,konkr}/`, `slate-parity/results.json`, `desktop-themes/results.json`,
`ci-046d897/{pak-verification,desktop-runtime}.json`, `device-contracts/`, and
`hardware-a8218cc3f32d48d5bb7165660357d5eb/{playback-results,dual-results}.json`.
The latter directory also retains final physical-input/EPSM logs and the user-state preservation record.

## 0.37.1 Pattern workspace and first-note hardening — released and verified

Version **0.37.1/code 51** adds visual Pattern channel collapse (**L2+Left**) and right-stick
double-left/right jumps to the first/last channel's note cell, without changing row or order.
Collapsed lanes retain their sounds/effects, full-cell clipboard data and exports; layout is a
per-pane workspace preference. Existing custom keymap conflicts are preserved.

- **1,080 core JVM tests and 7 Android host tests pass.** Collapse checks cover document/history
  identity, project/GB/OPL3 export bytes, hidden-field navigation, clipboard data, lane geometry,
  touch selection, workspace persistence and keymap migration. Entry tests cover document replacement,
  ordinary edits/history, invalid-channel rejection, repair/OFF and unfinished effect editing.
- PCE noise snare failure: normal note/sound entry could commit noise onto PSG 1–4 before checking
  audition legality; converting a used tone slot to noise could also invalidate stored patterns.
  Four controller regressions fail before the fix and pass afterward. Assignment preflight now
  refuses the edit without moving the cursor or mutating the song. New noise replacement checks all
  stored patterns, including parked patterns and bindings used by inherited notes. Existing invalid
  imports remain repairable; no notes move silently and native validation is unchanged.
- **91 native PCE fixture checks pass**, including legal noise/DDA snares, presets/envelope bounds,
  real audition/playback PCM and VGM export, plus intentional invalid-image rejection.
- Nova's real Activity rejects a snare on PSG 1 without changing the song, then accepts and plays
  the same snare on PSG 5. Eight noise-snare and eight DDA-snare edit/play/audition cycles pass;
  pre/post projects and offline WAVs are byte-identical. All 102 stream reports have zero driver
  faults and xruns; maximum observed callback is 1.710 ms. Separate temporary live callback counters
  also record nonzero PCM. The actionable channel guidance is placed first so narrow footers show it.
- POKEY's silence was reproduced on DMG: entry memory retained another document's instrument slot.
  Old slot 06 became joined bass: first-lane callback RMS **0**, then **0.183711** on the second lane.
  Old slot 0F became a deliberately silent sync clock; old custom slot 10 became empty in a new song.
  A replacement-only generation now resets document-local entry memory, not normal edits or Undo/Redo.
  The fixed replacement test uses slots **[1,1,1]**, with first-callback RMS **0.202863** in every case.
- A second DMG callback test passes eight first-note cases across all four lanes with queued and
  preprocessed blank images, 24 rapid restarts, held audition/live edit/release, and natural
  10.5-second idle suspension/resume. All 15 stopped windows contain zero nonzero samples;
  driver faults and xruns stay zero. These are actual post-clamp AAudio callback measurements,
  not human listening or DAC measurements. The temporary counters are removed from release source.
- Konkr passes actual Activity view switching, nine-lane OPL3 collapse, real uinput
  EventHub/InputReader horizontal and vertical gestures, exact provider save/reopen, and Activity
  restart with collapsed layout retained. Endpoints are channel index 8 and shortened row 15.
  Full-size expanded/collapsed surfaces were inspected; project bytes do not change on collapse.
- Each Activity stress case uses a UUID-owned document and verifies eight original private-file
  entries through a separate cold restoration test. Only owned Download documents/test packages
  are removed; `/ThorTunez` is untouched. Device leases and original settings are restored.
  Evidence: `recovery-20261109/feedback-phone-qol/`.
- Brick exploration confirms USB root shell and the existing remote terminal. A bounded ARM64/glibc
  probe loads vendor SDL 2.30.8: only `mali` video, plus ALSA/DSP/disk/dummy audio drivers. It never
  initializes an SDL subsystem and is removed afterward. This is ABI/capability evidence, not a
  running Tracker port. Landscape touch-only use, themes, pattern mute, mixer ceilings, synth panels
  and a GitHub Pages documentation site remain explicitly scoped roadmap work.
- Probe-free signed candidate **0.37.1/code 51** passes Nova's **22 permanent Android tests** and
  an isolated **32-view Game Boy render capture**. Full-RGBA comparison against the same-device 0.37.0
  baseline is identical for 30 images; only Help → Controls scrollbar extents change (32/34 pixels)
  because the help list is longer. The permanent mixed-audio test observes one xrun and adaptive
  buffer growth to 576 frames, with zero driver faults; targeted PCE/POKEY stress reports zero xruns.
- The clean candidate repeats Nova's actual PCE refusal/legal-lane playback and 16 snare stress
  cycles, Konkr's nine-lane gesture/save/restart scenario, and DMG's eight POKEY edit/play/audition
  cycles, with original private state restored. No temporary callback fields appear in diagnostics.
  The first DMG visual capture was obscured by Sortie's focused switcher; after dismissing only that
  known overlay and asserting real Tracker window focus, the repeated bounded scenario and unobstructed
  surface pass. No production workaround or unconditional extra controller input was added.
- Candidate commit `fa086a2` passes both build/deck jobs in push CI `42758659874` and PR CI
  `42758757755`. The actual CI AppImage exercises collapse and both stick axes at 1280×800 and
  1240×1080: channel 8/row 3, then channel 0/row 63, always on the note field. At 1920×1080,
  playback continues through collapse, Instrument view and held audition, reaching row 28 with
  PCM peak 0.421082 (held-audition peak 0.446411). Screens were inspected. Offscreen/dummy-audio
  execution is not physical Deck or listening evidence. All Kotlin/Native linking stayed in CI.
- Published from merge `fa75310` after all four jobs in main CI **42760524523** passed.
  Both published checksum sidecars and the production APK certificate were verified. APK SHA-256:
  `efa73fd156648d165cdd4061863f8d680a4e4d922cedaeafc2c80e8ebc54efec`;
  AppImage SHA-256: `14ad2ac6bb916e7bbd3328897f609250b989f38f2618bdd8c65147062ae68fcd`.
  The published AppImage itself repeats the gesture and playback/audition scenarios successfully:
  playback reaches row 28, PCM peak 0.421082, held-audition peak 0.489075.
- Official APK **0.37.1/code 51** is installed and its installed hash verified on Nova, DMG and
  Konkr. Each passes one bounded Activity scenario plus one separate cold eight-path restoration
  test. Nova runs eight noise and eight DDA snare cycles; DMG runs eight POKEY cycles with exact
  pre/post-stress project and WAV bytes; Konkr covers six system/view passes, real uinput gestures,
  provider save/open and actual Activity recreation. The POKEY-only scenario does not recreate the
  Activity. These official targeted scenarios report zero xruns/driver faults; stream energy is
  not measured by the clean APK. Captured surfaces are unobstructed and inspected.
- Nova's first official attempt stopped at a temporary accessibility-root availability assertion,
  before PCE actions; its cold restoration still passed. The Activity and audio had initialized
  without an audio error. Correcting the external harness to use `Activity.hasWindowFocus()` rather
  than require an accessibility root passes the unchanged PCE scenario. Known Sortie dismissal is
  still conditional on its identified foreground window. No production code changed for this.
- All temporary test packages are removed, original settings/private data restored, and leases
  released. Nova/Konkr finish Asleep; DMG finishes Dozing with the transition complete. `/ThorTunez`
  is untouched. Thor receives only the hash-verified APK at
  `/sdcard/Download/Thor Tracker/thor-tracker-0.37.1.apk`, without wake/install/launch.
  Deck receives the hash-verified AppImage and an atomic update of the existing stable
  `~/thortracker-deck/thor-tracker` symlink; previous versions remain, with no launch.
  Final evidence: `feedback-phone-qol/official-*` and `artifact-only-delivery.json`.

## 0.37.0 Cross-system conversion — released and verified

Version **0.37.0/code 50** adds Files → Convert to system with a non-mutating preview, complete
paginated/exportable loss report, explicit previous-project confirmation and stale-preview protection.
All **43 registered destinations** are available. Capacity overflow blocks rather than truncates;
cross-family presets, dropped lanes/resources and pitch/effect approximations are reported explicitly.
Android exports capture the requested song; a recreated activity with a lost exporter refuses to write
an unrelated default format.

- **1,062 JVM tests and 6 Android host tests pass**, with signed release and sample exports built under
  the resource cap. The conversion-specific suite has **67 tests**.
- Fail-before/pass-after regressions cover inherited notes inventing an unused default instrument,
  inherited noise selecting the wrong hardware lane, GB's later B00 overriding an earlier jump, and
  SMS's last delay overriding an earlier delay before zero-elision or destination column budgeting.
  Compatible promotions also retain the original effect-column layout.
- A throwaway CLI exercises **78 real conversion/export cases**: 77 converted documents and one
  same-system no-op, no blockers or failures. It covers every destination, actual demos and compatible
  promotions, source immutability, hidden/unused pattern data, distinct instrument identities,
  canonical project round trips and identical lowered images before/after decoding.
- **52 actual native-engine renders**, 12 seconds each, have nonzero PCM, advancing rows and no
  driver faults. This is playability evidence, not cross-chip waveform, mix-headroom or tuning equality.
  Native-only VGM/XGM/XGC exporters are not replaced by JVM mocks.
- A converted PCE demo exposed real SCC resampler clipping. Five synchronized full-volume voices,
  including −128/+127 polarity changes, reproduced **28 failures** across SCC/SCC+ at seven rates
  from 8000 to 96000 Hz. Reducing the gain **before** blip_buf from 48 to 24 passes **689 SCC checks**:
  the full mix equals five solo contributions within six PCM LSBs and never reaches a clipping rail.
  The formerly clipped 12-second conversion now peaks at **0.545685**, with 119 row changes.
  Raw player/chip traces, both images and both VGM exports are byte-identical before/after.
  The independent reference also matches all 1,501 player batches, 2,545 chip events and exact VGM
  bytes. A-4 remains 440.397 Hz natively and 440.420 Hz through pinned libvgm; existing waveform
  comparison tolerances pass unchanged for both variants.
- The integrated native run passes every engine and **2,796 WAV-export checks**. Optional OPNA/EPSM
  rhythm-ROM checks remain skipped because that private ROM is unavailable; no ROM is distributed.
- Candidate CI `42734222075` (`076b879`) passes both builds. Its actual AppImage completes eight
  isolated conversion scenarios: preview, cancel, confirmation cancel, report first/next/back,
  full TXT report save and apply/play. The JVM checker verifies the resulting source document,
  converted document, previous-project backup and complete report against the expected values.
  Playback reaches order 3/row 11; preview, report pages and converted Pattern surfaces were inspected.
  This candidate predates the SCC gain fix; it is UI evidence, not proof of the corrected SCC audio.
- Post-fix CI `42737641539` (`4e2e0ec`) passes both builds. Its actual AppImage plays the formerly
  clipped PCE→SCC conversion for 13 seconds, reaches order 1/row 55 and peaks at **0.5456848**,
  matching the corrected native renderer. The five-channel Pattern surface was inspected.
  Evidence: `recovery-20261109/conversion-fixed-desktop/`.
- Nova passes the **22 permanent Android tests** and **32 full-RGBA Game Boy baseline comparisons**.
  Nova, DMG and Konkr each pass both actual conversion/picker scenarios against the corrected signed
  release. These exercise GB→NES and AY→GB, cancellation, all report pages, frozen report/backend
  export snapshots, backup confirmation, save/reopen, playback, held audition and native WAV output.
  Actual activity recreation behind DocumentsUI refuses the lost exporter and writes no default
  backend payload. Initial Nova screenshots caught preceding composed frames; compositor-settled
  captures visibly confirm report pagination, backup confirmation and the recreation refusal.
  Dedicated playback/audition has zero xruns/faults on all three devices; maximum callbacks are
  **3.834 / 13.298 / 7.540 ms**, respectively. Working/recovery/previous files and AtomicFile companions
  are restored byte-exact; workspace/keymap values are restored and `/ThorTunez` is never accessed.
  Evidence: `recovery-20261109/conversion-frontend/{clean-parity,visual-smoke}/`.

Evidence: `~/.local/state/thortracker/recovery-20261109/conversion-export-evidence/`.
Final integrated CI **`42744768001`** passes. PR #48 merged as main **`ed40708`**; all four jobs in
official publication run **`42746509594`** pass. Downloaded **0.37.0/code 50** artifacts:

- APK SHA-256 `eef9a6f8c5459292eed93048151c526284e790370db12ba5ca08c6a31e76867e`
- AppImage SHA-256 `64b60ba9926a20123533442791f1f3cf16dbc77028ed63f581eb63ac9c222cf0`

The APK's production certificate, non-debuggable flag, version and both Android ABIs were verified.
The official APK passes both conversion/picker scenarios on **Nova, DMG and Konkr**, with zero
dedicated xruns/faults and maximum callbacks **3.542 / 14.792 / 4.118 ms**, respectively.
Nova also passes all **22 permanent Android tests** and **32 full-RGBA Game Boy baseline comparisons**.
Its lifecycle test records one settling xrun and a 576-frame buffer, not a dedicated-scenario underrun.
Actual report/refusal surfaces were inspected. Owned test documents/captures and the test package
were removed; private document state, workspace/keymap, screen timeout, launcher and sleep were restored.
In the recreated-activity refusal case, DocumentsUI had already created a **zero-byte destination**.
That empty file is expected: the app refuses to write a payload, but does not delete the returned URI.
The smoke wrapper removes its own test destination afterward; this is not a successful report export.

The actual official AppImage passes all **eight isolated conversion scenarios**. The JVM checker confirms
saved source/converted documents, the previous-project backup and the complete TXT report exactly.
Apply/play reaches order 3/row 11 with peak **0.26314014**. A separate 13-second PCE→SCC run reaches
order 1/row 54 and peaks at **0.5456848**, matching the corrected native headroom result.
Preview, report, converted Pattern and SCC surfaces were inspected. These are dummy-output desktop
PCM and Android state/timing/offline-WAV checks, not microphone recordings or human listening.
Evidence under `recovery-20261109/`: `official-0370/`, `conversion-official-smoke/`,
`conversion-official-export-evidence/` and `conversion-official-scc-desktop/`.

Official 0.37 is installed on the three reserved Android devices. Hash-verified artifacts were copied
to Thor's `/sdcard/Download/Thor Tracker/` and Deck's `~/thortracker-deck/`, whose stable `thor-tracker`
symlink now selects 0.37. Thor and Deck were not woken, installed or launched; `/ThorTunez` was untouched.
The root `main` worktree was fast-forwarded with the preexisting DS-10 discussion note preserved verbatim.
All temporary probe source remains outside the committed application/test tree.

## 0.36.0 WonderSwan — integration verification

Both WonderSwan models integrate VSU, SAA1099 and the preceding systems, targeting
**0.36.0 / versionCode 49**, project format 10. **995 JVM tests and 6 Android host tests**
pass; signed Android release, clean instrumentation and all sample exports build inside
the enforced resource scope. The factory Game Boy `.uge` remains byte-identical.
The required generated driver constants, reference fixture and two golden files are tracked
as clean-checkout inputs, not treated as build caches.

- The integrated native run passes every earlier engine and **2,796 WAV checks**. Its three
  WonderSwan failures were test defects: comparing different host-owned ROM addresses as chip
  state, and calling a 1,000-byte buffer too small for a 414-byte VGM. The corrected checks compare
  all hardware state after verifying the mapped bytes and normalizing only the host address;
  VGM boundaries now test exactly the required size and one byte short.
- **693 WonderSwan native checks pass**, including CPU-fed samples, all four DMA rates,
  mono/opposed stereo, hardware sweep/noise, live edits, seeks and mute continuity.
- Headroom verification now includes the full signed-byte range **−128 / +127** and uses
  Hyper Voice's hardware shift as an exact half-level control. Removed an incidental peak-window
  assertion. Restoring the old half-gain mixer produces **12 actual host-clipping failures**
  at 11025/44100 Hz; the production quarter-gain mixer passes without changing raw chip wrap,
  player/timer/DMA traces or any reference VGM bytes. This is a same-resampler linearity control,
  not an independent hardware oracle.
- Independent Python comparison matches both **1,500-tick** traces, exact VGM bytes, decoded
  register/RAM timelines and three repeated loop passes. A-4 is **440.362 Hz** in the native WAV
  and **440.353 Hz** through pinned libvgm `c5c03a2c`, versus **440.367 Hz** from the clock/divider.
  The reference reader now uses the standard WAV module for libvgm's extensible PCM header;
  the original 0.3 Hz tolerance is unchanged.
- The actual renderer CLI plays both exported demos for five seconds: mono **peak 0.151,
  RMS 0.0191**, Color **peak 0.321, RMS 0.0391**, both reaching order 1/row 22.
  Mono VGM is **338,335 bytes**, 20.78 seconds with an 8.90-second loop.

Evidence: `~/.local/state/thortracker/recovery-20261109/{ws-native,ws-corrected,ws-before}`.
The native/Kotlin registries and all shared-registry renderer source lists retain every engine;
the SMS renderer remains standalone. Hyper Voice VGM export is explicitly refused; WAV supports it.
Both jobs in candidate CI run `42730175159` (`cc19ab6`) pass. Its actual AppImage played and
held-auditioned both demos, then returned to Pattern: mono reached order 1/row 22 with hold/after
PCM peaks 0.08578491/0.15112305; Color reached order 1/row 20 with 0.28866577/0.32122803.
Both rendered surfaces were inspected; evidence is `recovery-20261109/ws-desktop/`.
Nova, DMG and Konkr each pass both actual-device scenarios: wave/noise/sweep edits, CPU-fed and DMA
samples, Hyper Voice, held audition, playback/seek, project round trip, WAV export and explicit
Hyper Voice VGM refusal. All three have zero driver faults and zero xruns in the dedicated scenarios;
maximum callbacks are **1.186 / 5.030 / 2.104 ms** respectively. Their edited WAV pairs are byte-identical,
48 kHz stereo with 569,856 frames and no clipping rails. Nova also passes all **22 permanent Android
tests** and **32 full-RGBA Game Boy baseline comparisons**. Actual wave, DMA and refusal surfaces
were inspected. Evidence: `recovery-20261109/ws-frontend/device-smoke/`.
The collection wrapper initially attempted to pull the intentionally refused VGM after both tests
had passed; successful exports were retained and checked. It now recognizes that explicit negative
case without relaxing positive-export checks. The corrected wrapper completed on DMG and verified
that the refused VGM was absent.
No physical WonderSwan listening or hardware equivalence is claimed. Local Kotlin/Native linking
remains disabled.

Final integrated CI `42739508947` passes. PR #47 merged as main **`506c424`**; all four publication
jobs in run **`42741502289`** pass. Official **0.36.0/code 49** artifacts:

- APK SHA-256 `8aff14c58b5a955575723b2e1154c9dd508efb803af518d8588a0486f7a40325`
- AppImage SHA-256 `84fd07ee4468166b69c0bae69529e9e7ab92abaa05b12089aaf555c30b52eb5e`

The downloaded APK has the production certificate, is non-debuggable and contains both Android ABIs.
Both official scenarios pass on DMG and Konkr: zero xruns/faults, maximum callbacks **4.653 / 3.998 ms**,
both WAVs byte-identical to all three accepted candidates, and the refused Hyper Voice VGM absent.
Actual DMA/refusal surfaces were inspected. Nova was not downgraded from the accepted 0.37 candidate.
The official AppImage plays and held-auditions both demos, then returns to Pattern; playback advances
to order 1/rows 20 and 22, with nonzero hold PCM and peaks **0.15112305 / 0.32122803** afterward.
Both desktop surfaces were inspected. Evidence: `recovery-20261109/{official-0360,ws-official-smoke,ws-official-desktop}/`.
Hash-verified copies were delivered to Thor's Download folder and Deck's `~/thortracker-deck/`;
Deck's stable `thor-tracker` symlink was updated. Neither device was woken, installed or launched.

## Workstation recovery and enforced build limits

The second reported lockup interrupted VIC/TED verification. Recovered on-disk JVM reports contain
**883 tests, zero failures/errors** (2026-11-09 21:42:57–21:43:11 UTC); no completed APK survived that
invocation. These reports are not proof of the later shared-state changes.

`/tmp` is a **14 GiB tmpfs** on this workstation. Prior temporary audio/build evidence was RAM-backed
and was lost on reboot; historical results below remain records of observed runs, but their `/tmp`
artifacts are no longer available. Harness source was recovered from persistent transcripts, not
revalidated by that recovery. New evidence lives under `~/.local/state/thortracker/` on the SSD.
The previous boot records an OOM kill of Chrome at 13:15 local time; it does not establish the exact
cause of the later reported lockup.

`tools/build.sh` now enforces local Linux limits inside the actual host/Distrobox build process:
8 GiB available-memory preflight, 3 GiB memory-high threshold, 4 GiB hard maximum, zero swap,
two-CPU quota, low CPU/I/O priority, and disk-backed compiler scratch including Java's temporary
directory. The shared build lock and single-use/in-process compiler policy remain.

Smoke evidence: the real Gradle 8.9 `help` invocation succeeds; a temporary init probe in its JVM
reports `MemoryHigh=3221225472`, `MemoryMax=4294967296`, `MemorySwapMax=0`,
`CPUQuotaPerSecUSec=2s` and the SSD Java temporary directory. `TMPDIR=/tmp` is rejected before Gradle.
A separate intentional 128 MiB allocation in a **64 MiB** scope is killed locally with
`Result=oom-kill`, `MemoryPeak=67108864`, `MemorySwapPeak=0`; the workstation remains responsive.
This demonstrates containment, not that unrelated desktop workloads cannot exhaust the host.

The subsequent bounded **debug Linux test link** still caused file-cache churn: the owned scope recorded
**511,098,163,200 bytes read**, over one million memory-high events, about **2 GiB/s** current reads,
and zero scope swap. It was stopped at `:core:linkDebugTestLinuxX64`; no Linux test/desktop-build pass
is claimed from that invocation. Current disk reads and swap traffic returned to zero afterward.
All Kotlin/Native linking on this workstation now goes to CI; the 4 GiB limit was not raised.

## 0.35.0 Virtual Boy VSU — integration verification

The `vsu-system` branch integrates the published SAA 0.34 main revision `5830e7c`, including the
audition/song-ordering fix, and preserves the prepared headroom changes in `3ee5b32`. Version is
0.35.0/code 48. **956 JVM tests**, the Android host suite, signed release and clean instrumentation builds pass within the enforced resource scope.

A seven-rate seek probe found one real timing defect: at 11025 Hz, an odd silent prefix reset the
half-sample frame phase (**30/30 frame-count mismatches**, worst PCM difference **0.017578125**).
Advancing both cleared resamplers with `blip_skip_silence` fixes it: the same target now has exact frame
counts and **bit-identical PCM at all seven rates, 8–96 kHz**, with identical raw chip state.
The permanent regression keeps the existing 2e-3 bound. Evidence: `recovery-20261109/vsu-before/`
under `~/.local/state/thortracker`. Final integrated CI and official publication passed; see below.

**594 VSU native checks pass**, including seek, mute, live edits and seven-rate headroom. The first integrated
run passed all other engines and **2,622 WAV checks**, but exposed one stale VSU sweep assertion: S5INT
retains the swept frequency, so 1500 immediately stops on the next-value overflow (2250), as specified by
the pinned Tech Scroll. The corrected case also checks FQL does not restart a stopped channel and a later
explicit restart stops at the first overflowing frame. No production sweep behavior changed.

An isolated original-scale-64 run fails **29 checks** (hidden clipping error up to 0.505249; peak 1.333333);
scale 8 passes the unchanged oracle bounds. Register, raw output, chip-case and VGM files are byte-identical
across the gain comparison. The independent Python reference agrees on **1,500 player ticks**, **132,385
raw output changes**, **30 chip cases** and the complete **6,661-byte VGM**. Pinned libvgm gives envelope
correlation **0.980**, log-spectrum **0.897**, pitch-class agreement **95%** and gain-normalized level ratio
**2.96** with the documented core differences; this is not hardware waveform equivalence.

The initial candidate's actual CI-built AppImage (`949b94e`, run `42724617812`) passed two local
offscreen/dummy-audio scenarios: demo playback plus held Instrument audition and return to Pattern
advanced to order 0/row 41, with PCM peaks 0.08251954 during the hold and 0.07674154 afterward.
A noise-only held audition produced peak 0.036783855 and returned to exact silence on release.
Both rendered surfaces were inspected. Evidence: `recovery-20261109/vsu-desktop/`.
These are frontend/PCM checks, not physical-device listening or proof of the later integrated binary.

The production-signed candidate passes **22 permanent Android tests** on Nova and **32 full-RGBA
Game Boy parity captures**. The lifecycle suite incurred one settling xrun and grew Nova's buffer to
576 frames; its driver-fault count stayed zero. Actual VSU menu creation, playback/held audition,
live edits, all wave/modulation pages, undo/redo, restart, cursor seek and document exports passed
on Nova, DMG and Konkr. Dedicated VSU scenarios had **zero xruns and driver faults**; maximum
callback times were **3.388438 / 6.807240 / 4.538769 ms** respectively.
The first DMG/Konkr probes exposed a harness selection race: setting the view's modulation page
without the canonical `Selection.wave` allowed the normal follow callback to select wave 0.
Correcting both fixture states passed against the unchanged APK; no production behavior was altered.
Konkr's maximum seek used order-local fallback: **378 ms wall time / 23.959077 ms native preparation**.
Each device exported a non-silent 48 kHz stereo WAV (1,843,200 frames, peaks 3581/3868, no rail samples)
and a **47,225-byte VGM**. The independent left/right document-silencing fixture rendered 153,600
frames with peak 1220 per side; its opposing-silence assertions passed. Device wave/modulation
surfaces were inspected. Evidence: `recovery-20261109/vsu-frontend/{ordered-clean,ordered-smoke,selection-smoke,accepted-metrics.json}`.

Official **0.35.0/code 48** is published from main `aa3149e`; all four jobs in run `42735902241`
pass. Downloaded APK/AppImage checksums, production certificate, non-debuggable manifest and
arm64/x86_64 APK libraries are verified. APK SHA-256:
`0ecde00d61cf6a19dcb411ae6b74b062911734ec5b7049abfd0abfbf5b3829e6`;
AppImage SHA-256: `e61d4d09497dab4bfdb829c0cb8e59d802df92eb383e9fb0fdaa71edc203b54f`.
The actual official AppImage repeats playback/held audition/Pattern return at order 0/row 41 and
the noise-only audition, with hold peaks **0.08251954 / 0.036783855**. Both surfaces were inspected.
Artifact-only copies reached Thor Downloads and Deck's `~/thortracker-deck/`; the Deck's stable
`thor-tracker` symlink was updated atomically. No installation, launch or wake command was issued.
The actual official APK passes the full VSU scenario on DMG with zero xruns/faults and a maximum
callback of **6.944 ms**. Edited WAV, stereo-silencing WAV and VGM bytes match the accepted candidate.
The official modulation surface was inspected. Nova/Konkr were not downgraded from newer accepted
candidates merely to repeat this release gate.
Evidence: `recovery-20261109/{official-0350,vsu-official-desktop,vsu-official-smoke}/`.

- Native/Kotlin registries, Android JNI, Deck VGM bindings, model codec and sample export retain VSU
  and both SAA variants alongside every candidate system. The registry-driven New/demo menus need no special branch.
- All 14 shared-registry renderer scripts include VSU, SAA, CBM, TIA, POKEY and the earlier engines,
  including the Stella/PCE/ymfm wrappers and include paths. The SMS-only renderer remains intentionally standalone.
- The prepared scale 8 precedes blip_buf; float divisor 24576 and raw physical diagnostics are unchanged.
  The seven-rate oracle bounds and independent-reference thresholds are retained. No 5–10 ms startup
  silence is invented; the hardware observation remains unresolved.
- The recovered `~/.local/state/thortracker/recovery-20261109/harnesses/VsuDeviceHarness.kt` uses the
  current frontend, VSU editor, WAV and diagnostics APIs; no repository instrumentation scaffold is added.

Verification commands, serialized with SSD-backed evidence and scratch:

```sh
nice -n 10 tools/build.sh --max-workers=1 :core:jvmTest :app:testDebugUnitTest :app:exportSamples
tools/audio/run_native_host_test.sh "$HOME/.local/state/thortracker/vsu-035/native"
python3 tools/audio/vsu_reference_check.py "$HOME/.local/state/thortracker/vsu-035/native"
python3 tools/audio/saa_reference_check.py "$HOME/.local/state/thortracker/vsu-035/native"
tools/audio/render_vsu_image.sh app/build/samples/crimson-stereoscope.vbimg "$HOME/.local/state/thortracker/vsu-035/demo.wav" 30 --vgm "$HOME/.local/state/thortracker/vsu-035/demo.vgm"
tools/audio/render_vsu_image.sh app/build/samples/vsu-shortest.vbimg "$HOME/.local/state/thortracker/vsu-035/shortest.wav" 2
nice -n 10 tools/build.sh --max-workers=1 -PthortrackerSigningDir="$SIGNING_DIR" :app:assembleRelease
nice -n 10 tools/build.sh --max-workers=1 -I "$RELEASE_TEST_INIT" -PthortrackerSigningDir="$SIGNING_DIR" :app:assembleReleaseAndroidTest
```

Prerequisites: prepared pinned native drivers/tables, Android SDK/NDK/JDK (or the configured Ubuntu
Distrobox), host C/C++ compilers and Python 3. The Gradle wrapper enforces the memory preflight,
same shared build lock and bounded scope; serialize the native commands under the parent's resource
policy as well. Re-exercise every shared-registry renderer, the full all-engine WAV suite and independent
references on this combined source merge. Compare original scale-64 versus scale-8 headroom runs in isolated
evidence worktrees: the original must fail and the prepared gain must pass, while raw register/sample,
chip-case and VGM traces remain byte-identical. Do not weaken bounds to obtain that result.
The optional `vsu_reference_check.py EVIDENCE_DIR --vgm2wav /path/to/vgm2wav` requires libvgm and numpy;
its host-gain normalization is diagnostic only, not physical hardware proof.
`SIGNING_DIR` must name the parent's existing release-key directory; do not copy secrets into the repo.
`RELEASE_TEST_INIT` must be the parent's external Gradle init script selecting `android.testBuildType = "release"`
and adding only the recovered `VsuDeviceHarness.kt` through an isolated external androidTest source directory. The default project
uses debug instrumentation; there is no release-test task without that override. Sign the resulting test APK
with the same production certificate, run only the VSU class, then build/run the permanent suite without
the temporary harness source directory.

Run Linux tests and optimized AppImage linking in CI only, then exercise that downloaded AppImage.
Production-signed Android harness/permanent tests need the canonical Nova/DMG/Konkr leases and exact
settings/project restoration; invoke only `org.thortracker.VsuDeviceHarness` in the temporary harness APK
(`-e deepSeek true -e deviceRole konkr` only on Konkr). Preserve Game Boy UGE/full-RGBA parity gates.

## 0.34.0 Philips SAA1099 — integration verification

SAA candidate `c27a60b` integrates SAM Coupé, Creative Music System and all preceding systems.
Six generated fixtures repeat byte-identically; **925 JVM and 6 Android host tests** pass.
The corrected production-signed Android release and clean/temporary frontend instrumentation
compile within the enforced build scope. This is candidate evidence, not a pass for 0.36.0.

- All earlier native engines and **2,567 WAV checks** pass. The first SAA run found a fractional
  resampler-time loss during silent seek and a test assuming the new tone period had already latched.
- Reusing `blip_skip_silence` for both stereo sides fixes CMS seek at order 3/row 21 from **9 LSB**
  difference to **bit-identical**, retaining the original 1/32768 limit. The envelope test now covers
  exactly eight actual edges, including the initial pending reset half-period. The corrected SAA run
  passes **618 checks**. Both uninterrupted song WAVs, raw player/chip traces and VGM files remain
  byte-identical to the pre-fix run.
- Pre-clamp stress/public-machine probes cover **510,719,992 channel samples**, with extrema
  **−28,944 / +30,071**, zero clipping; existing SAM ×10/CMS ×5 gain is retained. See the source
  audit for the finite coverage and analytical bound, not an exhaustive-history claim.
- Independent reference: **1,501 player batches**, **2,000,461 chip clocks / 5,684 output events**,
  byte-exact VGM streams and repeated loops pass. A-4 measures **440.141 Hz SAM / 440.397 Hz CMS**;
  pinned libvgm `c5c03a2c` independently renders **440.141 / 440.394 Hz** and recognizes both CMS chips.
  Its differing noise/envelope model is documented: song envelope/spectrum correlations
  **0.972/0.777 SAM, 0.801/0.730 CMS** are diagnostics, not an equivalence or listening claim.

Evidence: `~/.local/state/thortracker/recovery-20261109/{saa-native,saa-corrected,saa-preclamp,saa-frontend}`.
CI **42720599432** passes the integrated build/native suite and optimized AppImage. The actual downloaded
AppImage plays both demos, auditions a held instrument and returns to Pattern: SAM reaches order 1/row 9,
CMS order 1/row 12, one audition each, with final peaks **0.443787 / 0.287689**. Both surfaces were inspected.
The corrected production APK passes **22 clean Android tests** and all **32 full-RGBA Game Boy
surface comparisons** on Nova. The lifecycle suite observed one settling xrun and grew its buffer
to 576 frames; no driver faults. SAM and CMS controller, hardware-envelope/shared-noise, live-edit,
held-audition, stop/restart, project-picker and export scenarios each pass on Nova, DMG and Konkr.
These six backend scenarios report zero xruns/faults; largest callbacks are **2.360 / 11.099 / 5.567 ms**.
Konkr's deep seek takes **454 ms wall / 113.977 ms native**, using the documented order-local fallback.
All six picker-created WAVs are non-silent 48 kHz stereo with distinct sides and no full-scale samples;
SAM has **921,600 frames**, CMS **832,000**. VGM 1.71 lengths, loop samples and 8 MHz / dual-7.15909 MHz
clock headers are checked. Final queue-order APK SHA-256:
`f37ba345cdb4fff37ac2529f2e06114b32fbd1a75c9a19325a3edd4eeda6b055`.
Published as **v0.34.0** from main `5830e7c`; all four jobs in run `42729677335` passed.
Official APK SHA-256: `3791d1fc8c64824696819898208a8656da2d445f489fe1cfb578d3c9e2c061a3`.
Official AppImage: `3da7aeb2fefcb6738b534c7d812cc605d545f7c038add353e4d8286d5c3b34fa`.
The APK is production-signed, non-debuggable, code 47, with arm64-v8a/x86_64 libraries.
Published-binary SAM/CMS scenarios passed on Nova and Konkr. On DMG, CMS passed; SAM's
first attempt timed out locating Downloads in DocumentsUI, and the unchanged focused SAM
scenario then passed. This retry does not establish a fix for the transient picker-navigation failure.
All six exported WAV/VGM pairs are byte-identical to the already-verified candidate outputs.
The actual official AppImage played/auditioned both demos and returned to Pattern with advancing
positions and nonzero PCM; both screenshots were inspected. Thor received only the APK in
`Download/Thor Tracker/`; Deck received only the AppImage and stable `thor-tracker` symlink update.
Remote hashes matched; neither daily-use device was awakened, installed or launched.
Evidence: `recovery-20261109/{official-0340,saa-official-smoke,saa-official-sam-retry,saa-official-desktop}`.
Physical SAM Coupé/Game Blaster listening has not been performed.

## 0.33.0 VIC-20 VIC and C16/Plus4 TED

Integrates published TIA and all earlier systems. The reference fixture is deterministic across two runs.
Focused headroom verification passes **6,704 checks**: the saved original VIC gain produces 14 failures,
including both 96 kHz adversaries clipping at 32767. The 11/16 pre-resampler gain preserves raw chip levels
and passes the quiet oracle. Fixed the oracle's unequal starting phase and explicitly exercised TED noise
with distinct tone reloads; no audio threshold was relaxed.

- **888 JVM and 6 Android host tests** pass; the production-signed release APK builds within the new
  4 GiB/no-swap scope. Five shared-state regressions fail against the preserved old implementation and
  pass corrected: selected versus playing instrument identity, DAC portamento, per-tick pitch/envelopes,
  timer changes, and interrupt phase/stop timing. The inspector now follows the actual native scheduler.
- Native integration passes every other engine and **2,393 WAV checks**. Its two CBM failures were
  test defects: reading the end-of-tick register to assert the initial DAC frame, and an arbitrary
  host worst/mean timing ratio. The first now checks the key-on trace before interrupts; host CPU
  timings remain diagnostics, not machine-dependent assertions. The corrected CBM run passes
  **7,131 checks**. No engine behavior was changed to satisfy either assertion.
- All **eight** fixture/export player traces match the separate Python reference. Measured shortest-song
  fundamentals are **444.07 Hz VIC / 439.84 Hz TED**, including both actual renderer CLI outputs.
  The documented renderer builds and runs both systems.
- Independent pinned-upstream comparison passes **82,388,935 field comparisons**, **3,814,784 single
  clocks**, and **67,621 native-level transitions**; **88 scheduler assertions** cover 18 scenarios.
  Six local reference files match the pinned upstream bytes. VIC cold noise reset differs between
  the two models; strict comparison starts after natural convergence over 16,384 disabled clocks.
  This is raw chip/scheduler evidence, not cold-start equivalence, resampled-PCM equality or analog
  hardware validation.
- The clean production-targeted Android suite passes **21 tests in 48.078 s**, zero observed xruns
  and faults. Both real controller scenarios pass on **Nova, DMG and Konkr**, covering New/demo,
  tone/noise/master-volume instruments, envelopes, TED DAC/root/loop/rate and actual WAV import,
  playback plus audition, live edits, undo/redo, exact restart, seek, settings and project/WAV pickers.
  All measured scenarios have **zero xruns and driver faults**. Worst callbacks for VIC/TED:
  Nova **1.229/3.216 ms**, DMG **7.004/5.127 ms**, Konkr **1.097/2.472 ms**; all below their actual
  4 ms (Nova) or 20 ms (DMG/Konkr) burst period. These are observations, not future-load guarantees.
- All **32 Game Boy views** match the recovered verified baseline in full RGBA, and factory `.uge`
  bytes remain identical. Actual Android instrument/shared-control surfaces were visually inspected.

Evidence: `~/.local/state/thortracker/recovery-20261109/{cbm-native,cbm-native-oracle,cbm-frontend}`.
Optional private YM2608 ROM checks were not run after reboot: that private `/tmp` input was lost.

CI run **42715356447**, candidate `4b1c95e4a5826f2e4ecbc6024dd829225e341dc3`, passes Linux
core tests, Android builds, byte-identical factory UGE and the full integrated native/WAV suite.
Its optimized AppImage also passes five actual local SDL scenarios: VIC solo/playback-plus-audition,
TED square solo/playback-plus-audition, and TED DAC solo. Playback reaches **order 1, row 35 VIC /
row 36 TED**; solo peaks are **0.037128 VIC, 0.303803 TED square, 0.453918 TED DAC**.
The imported Android-edited TED lead has a wave envelope `[0]` and correctly produces silence;
explicitly selecting its sounding square/DAC instruments verifies audition without changing the engine.
Instrument, DAC and Pattern surfaces were visually inspected. Offscreen/dummy output verifies PCM/UI,
not physical speakers or human listening. Evidence: `cbm-ci-appimage/` and `cbm-desktop/` under the
same SSD evidence root. Temporary Android harness sources have been removed.

### Official 0.33.0 delivery

Published from `eef2607354d05cd971eb552de9b7816a43f5f0cc`; workflow **42719997421** passes build,
Deck, signed release and AppImage attachment. APK code 46 is non-debuggable, includes arm64/x86_64 and
verifies against the production certificate. Official VIC/TED controller, playback, live-edit, restart,
sample/document and export scenarios were exercised on Nova, DMG and Konkr with **zero xruns and driver faults**;
largest observed callbacks are **2.019 / 6.423 / 4.604 ms** respectively.

**Audition-ordering defect, corrected for 0.34.0:** Konkr's original TED kind-switch scenario returned
a DAC placement error after the instrument had become a tone. Nova and DMG passed that same harness.
The C-4 rerun issued another valid audition and could overwrite the earlier error; it did **not**
disprove the race. A deterministic real-executor regression subsequently failed on the old production
APK and passed unchanged against the corrected APK, covering both already-applied and still-queued
song updates. Command boundaries now seal edit batches instead of consuming a future song from a
shared mailbox. Current invalid requests still fail normally. The original, unmodified C-2 TED
scenario also passes on Konkr and DMG after the correction. The C-4 runner's separate plural-only
result-parser defect was fixed without treating its successful `OK (1 test)` as an app failure.

The official AppImage also passes actual VIC/TED playback plus held audition, with advancing transport and
nonzero PCM; final Pattern surfaces were inspected. Thor receives only the SHA-verified APK in Download;
Deck receives only the SHA-verified AppImage and its stable `thor-tracker` symlink. Neither was woken,
installed to or launched; `/ThorTunez` remains untouched.

APK SHA-256: `d119c0bb4c84c19c767871f6f82a1dd4a569ab94113c708fa4a998df51ada384`.
AppImage SHA-256: `fe27df74e6f834334b589096743d1dfde88504797b69a1e03857d5922b16a5ab`.
Evidence: `recovery-20261109/{official-0330,cbm-official}` under `~/.local/state/thortracker`.

## 0.32.0 Atari 2600 TIA

Integrates verified POKEY `c8a7d46` and published OPN/OPNA. **Cartridge Dawn** has NTSC and PAL variants,
both TIA voices, all 16 AUDC modes, tonal/raw instruments, honest divider quantization and WAV export.
VGM has no TIA command; no substitute register format or cartridge export is advertised.

### Engine, reference and build evidence

- Generated Kotlin constants, native fixtures and compressed goldens are byte-identical across two runs.
  Vendored Stella `AudioChannel.cxx/.hxx` match upstream commit `d55b1aec0d067a4c901a6dcdf81cb8f579685659`.
- Corrected pre-resampler headroom: the preserved unattenuated path fails **81 checks**. Quarter-gain
  absolute levels before differencing pass **40 NTSC/PAL × rate × AUDC cases** and a separate quiet
  resampler oracle, without changing raw nonlinear chip levels or register traces.
- The integrated native suite passes, including **1,812 TIA**, **356 POKEY**, **1,806 OPN/OPNA** and
  **2,220 WAV** checks. Independent Python period/shape comparisons cover every AUDC at four dividers;
  brute-force nearest-divider edges and the **1,500-tick** player trace pass unchanged tolerances.
  Measured AUDC C/AUDF 11: **436.105 Hz NTSC** and **432.126 Hz PAL**, matching the hardware's deliberately
  imperfect A-4 (**−15.4 / −31.3 cents**), not an artificially retuned 440 Hz.
- **851 JVM, 19 Linux and 6 Android host tests** pass. The signed release APK and debug SDL executable
  build with one worker. Both TIA renderer CLIs and the integrated POKEY renderer run successfully;
  all shared-registry renderer source lists include every engine (SMS remains intentionally standalone).
- Factory `.uge` is unchanged: **77,994 bytes**, SHA-256
  `c6d91f1c02c34425d49ce19279f090fa309a500568462de6efc5c5a5f792bccb`.
  All **32 Game Boy views** are pixel-identical to the verified POKEY baseline.

### Actual frontend evidence

- The clean permanent Android suite passes **21 tests in 48.299 s**, zero observed xruns or driver faults.
  Rebuilt after temporary-harness removal, the final clean suite passes **21 in 47.993 s**; its general
  audio regression reports **one xrun, zero driver faults**. The separate TIA scenarios below report zero.
- Both regional scenarios pass on **Nova (123.302 s), DMG (127.878 s), Konkr (134.953 s)**: New/Load demo,
  all five instrument pages, all AUDC choices, tonal/raw audition, presets, envelope/loop/release editing,
  undo/redo, tuning-guide divider audition, playback with both audition channels, live edits, Stop,
  exact project restart, seek, and actual project Save/Open and WAV document pickers.
- All six project exports and six WAV exports match their retained source bytes and are identical across
  devices within each region. NTSC WAV is **8,612,732 bytes**, PAL **8,872,112 bytes**.
- Across those scenarios: **zero observed xruns and driver faults**. Maximum observed callbacks:
  **2.323 / 5.809 / 1.766 ms** on Nova/DMG/Konkr. Konkr's **16,065-tick** order-fallback seeks complete
  in **330.582 ms NTSC / 413.341 ms PAL**, with playback resumed and no pending seek.
- Four actual SDL scenes exercise each region with playback alone and playback plus held audition.
  Final rows are **26 NTSC / 25 PAL**, audition count is one in the latter scenes, and peaks are
  **0.23056 / 0.23038**. Android compact/landscape instrument and tuning-guide surfaces and the SDL
  instrument surface were visually inspected. This is automated PCM/UI evidence, not human listening.
- Temporary instrumentation sources are removed and retained only with local evidence. Private-ROM
  and distinctive-chunk exclusion passes for the candidate APK; no private audio is published.

### Official release

Published from `93510f7be39974f5e8e2f04c5bd431130abc15a6` after all four jobs in workflow
`42708368638` passed. The signed APK is **0.32.0/code 45**, non-debuggable, arm64/x86_64,
with the established production certificate.

- The **official APK** passes both regional scenarios on Nova, DMG and Konkr: **two tests each**,
  zero xruns and driver faults. Worst callbacks are **2.927 / 5.019 / 1.877 ms**, respectively;
  all below the devices' burst periods.
- The **official optimized AppImage** runs locally with each region's exported project, playback,
  held instrument audition and return to Pattern: final rows **43 NTSC / 41 PAL**, one audition each,
  released-audition peaks **0.2305603 / 0.2303772**. Both final surfaces were visually inspected.
  Offscreen/dummy output verifies PCM generation and UI, not physical speaker output.
- Thor receives the byte-verified APK in `Download`; Deck receives the byte-verified AppImage and
  its existing `~/thortracker-deck/thor-tracker` symlink points to 0.32.0. Neither device was woken,
  installed to or launched; no `/ThorTunez` data was touched.

APK SHA-256: `2455a9803b8d8c2322404f27992a5aaced93a2d968662845433770f0be1b0c61`.
AppImage SHA-256: `07a438e0387308078a207b463a6c39e700b475964db8725bc2c827fdd0cc6107`.
Retained official artifacts and evidence: `~/.local/state/thortracker/recovery-20261109/`.

## 0.31.0 Atari 8-bit POKEY

**Atari 8-bit (POKEY)** (`atari-pokey`) adds four channels, NTSC/PAL timing, distortion polynomials,
16-bit pairing, high-pass and shared-resource ownership, and platform-native volume-DAC samples.
Thor Tracker's player and cycle model follow Altirra/MAME; the demo is **Scanline Cruise**, with VGM/WAV
exports. Version **0.31.0/code 44** integrates verified OPN/OPNA `c3c054f`, retaining all earlier systems,
project format 10, tuning provenance, 500 bundled scales and optional local packs.

Published [v0.31.0](https://github.com/esko997/thortracker/releases/tag/v0.31.0) after PR #42 merged as
`409a5bfb665ab086cc8741a03f904b23d324c013`. Workflow `42696269600` passed build, Deck, signed release
and AppImage attachment. Official artifacts passed checksum, production-certificate, version and
non-debuggable checks:

- APK SHA-256: `3a4b18afe60511c4811df8aa228a802910f7debd5734b24d03694261ad5f84dd`.
- AppImage SHA-256: `7f86086af72e7831d44e3bcfeb874b22c47631cccb4288ce52de14205c21aa9f`.
- The official APK passed both full controller/import/restart/export scenarios on Nova (**80.264 s**),
  DMG (**83.987 s**) and Konkr (**91.543 s**), with zero observed xruns or driver faults. Maximum observed
  callbacks were **3.109 / 10.563 / 3.746 ms** respectively; Konkr's deep seek took **263.405 ms**.
  All demo/edited VGM/WAV bytes equal the verified candidate, and all six actual document-picker exports match.
- The actual optimized AppImage ran locally with playback plus held audition: row 30 after four seconds,
  one active audition, PCM peak **0.65**, and a successfully drawn instrument surface.
- The official APK passed private-ROM/chunk exclusion. Artifact-only delivery placed it in Thor Downloads
  and the AppImage under `~/thortracker-deck/`, updating the existing `thor-tracker` symlink. Both remote
  hashes match; neither daily device was woken, installed to or launched. Owned test packages/captures
  were removed from the three reserved devices, and all leases were released.

### Sample ownership and native verification

- `0c94315` distinguishes ended PCM from synthesized voices: portamento holds an unreleased sample only
  while it is playing; a synthesized voice stays held after its envelopes end. `76112bf` replaces the
  inspector's ideal-rate estimate with rounded counters, inherited phase, pending fires, clock changes,
  STIMER, two-tone synchronization and per-tick pitch effects/envelopes. The preserved old model selects
  slot 61 while slot 60 is still playing. Verification also caught absent `000` cells incorrectly clearing
  continuing arpeggio; the corrected inspector skips them as lowering does.
- All **48 targeted model/lowering/editor/timer tests** pass, including an independent cycle-stepped
  timer oracle. A 16,711,680-tick steady-row ownership query took **0.103 s** on this host. Sample-free
  songs retain the cheap row-only walk; the inspector remains a straight-play projection, not a runtime
  simulation of jumps or subsequent loop passes.
- **356 POKEY native checks** pass, including 18 timer-boundary ownership cases. The integrated all-engine
  native run passed the other engines and **2,093 shared WAV checks**. Two test-oracle defects were corrected:
  VGM sizing does not promise loop metadata until actual rendering, and coincident edges invalidate a
  maximum-width threshold for counting saw phase wraps. No production audio changed; all ten POKEY evidence
  files remained byte-identical. An independent PCM spectrum put the saw beat at **129.525 Hz** versus
  **129.568 Hz** analytically.
- Independent Python reproduction matches **1,500 NTSC ticks / 38,964 interrupts / 19,641 writes**,
  **1,500 PAL ticks / 42,396 interrupts / 22,902 writes**, and **144 sticky-portamento ticks /
  32,137 interrupts / 41,000 writes** exactly. Its VGM matches all **27,437 bytes**, and the separate
  parser checks 6,544 writes and the 292,908-sample loop. All **13 independent chip cases** match.
- Independent libvgm/MAME rendering passes the unchanged gates: envelope correlation **0.834**,
  log-spectrum correlation **0.869**, pitch-class agreement **76%** of 300 windows, level ratio **0.45**
  with the reference renderer at 25% master volume. The renderer was rebuilt with its POKEY core enabled;
  an initial reference-player crash came from that core being disabled.
- Native host timing: mean **22.9 µs**, p99 **41.7 µs**, maximum **74.4 µs** per measured tick.
  The chip's physical amplifier clamp and AC-coupling model are retained; no host headroom defect was demonstrated.

### Frontends, exports and regression checks

- **831 JVM, 19 Linux and six Android-host tests** pass, with no failures or skips.
  After removing both temporary harness sources and rebuilding, the clean permanent Android suite passes
  **21 tests** on Nova in **48.126 s**. Its general audio diagnostics observed one xrun and zero driver faults;
  the separate POKEY scenarios below observed no xruns.
- **32 Game Boy view renders are pixel-identical** to the verified OPNA baseline, and factory `.uge`
  output is byte-identical. Temporary controller and screen-capture sources/APKs are retained outside the repository.
- Two actual MainActivity/controller scenarios pass on **Nova (80.609 s), DMG (84.225 s) and Konkr
  (91.240 s)**: all five sound kinds, four envelopes, relative pitch, clocks, high-pass/difference/partner
  controls, sample root/rate/loop, Android WAV import (7,990 Hz / 2,045 levels), starter kit, NTSC→PAL,
  multispeed 2, noise/base-clock settings, Undo/Redo, activity restart with exact state restoration,
  playback plus tone/sample audition, seek/Stop, and actual New/demo and VGM/WAV document-picker flows.
- Demo and edited VGM/WAV outputs are byte-identical across all three devices. All six document-picker
  exports equal their corresponding rendered demo files. The actual Android demo VGM, excluding only GD3
  metadata, equals the independent Python player's **172,896 bytes / 2,304 ticks / 1,695,623 samples**
  exactly, including its loop.
- Maximum observed POKEY callbacks: **Nova 2.760 ms, DMG 10.003 ms, Konkr 3.745 ms**; zero observed xruns
  or driver faults in these scenarios. Konkr's 16,065-tick order-local seek took **266.539 ms** natively
  (603 ms including capture/Stop), with 12 UI round trips and a maximum of **32 ms**.
- Four actual SDL debug-executable scenes pass: NTSC and PAL, each with playback alone and playback plus
  instrument audition. At four seconds the NTSC case reached row 30 and PAL/multispeed-2 reached row 50;
  PCM was nonzero and held audition was active. Android imported-sample and desktop instrument surfaces were inspected.
- The initial DMG picker attempt was obstructed by Sortie's Running apps overlay, confirmed by screenshot
  and accessibility root. Dismissing it with the actual B gamepad event allowed both scenarios to pass;
  no app workaround or timeout weakening was added.

Production signing, version and non-debuggable status were verified for the candidate APK. Private rhythm
firmware and aligned distinctive ROM chunks are absent from it; private OPNA-derived audio remains local.
Thor/Deck are artifact-only targets; no wake/install/launch was performed on either during these checks.
Local builds were serialized at nice 10 with a 1 GiB single-worker JVM, in-process Kotlin and no daemon;
only the desktop debug executable was linked locally. Human listening, original-hardware fidelity,
physical-button latency and the documented POKEY model omissions remain unverified.

## 0.30.0 PC-88/98 OPN and OPNA

Adds **PC-88/98 OPN (YM2203)** (`ym2203-opn`, **Night Express**) and **PC-88/98 OPNA
(YM2608)** (`ym2608-opna`, **Paper Lanterns**), using Thor Tracker's player on ymfm.
Both have FM and SSG, FM 3 special/CSM modes and VGM/WAV export. OPNA adds stereo,
LFO, six rhythm drums and ADPCM-B sample import, generation and editing. The rhythm
drums require the user's private YM2608 ROM; both factory demos work without it.
EPSM and OPNA now share the same validated firmware loader and existing storage path.
Version **0.30.0/code 43**; project format 10 and all 500 bundled scales are retained.

Published as [v0.30.0](https://github.com/esko997/thortracker/releases/tag/v0.30.0)
from main `238151e`; all four jobs of workflow `42684841340` passed. The downloaded,
production-signed APK passed all three temporary frontend scenarios on Nova
(147.242 s), DMG (122.671 s) and Konkr (157.097 s). Konkr's 16,065-tick order-local
seeks took 15.746 s for OPNA and 7.471 s for OPN while the longest observed UI
round trips were 21/15 ms; Stop remained responsive. Twelve actual Android exports
passed VGM structure/mixer checks and private-firmware exclusion.
The published optimized AppImage ran both demos locally for four seconds, advancing
to rows 29/23 with nonzero PCM. APK/AppImage checksums were verified on Thor/Deck
after artifact-only transfer; the Deck's stable symlink now targets 0.30.0.
No Thor/Deck wake, install or launch occurred. Owned Android test packages and
capture directories were removed; `/ThorTunez` was untouched.

Observed integration evidence:

- **783 JVM, 19 Linux and six Android-host tests**, zero failures/errors/skips;
  **21 permanent Android tests** passed after removing both temporary harness sources.
  All **32 Game Boy view captures** and the factory `.uge` remain byte/pixel-identical.
- **1,806 native checks**, zero failures, including FM/SSG/ADPCM pitch, FM 3/CSM,
  mute continuity, live edits, fully clocked seeks and VGM loop traces. The old
  mixer fails 58 of these checks; fixed **3/16 gain before blip_buf** preserves the
  chip's inner DAC saturation without clipping the combined route.
- **420 independent Python checks**, covering 14 tones, eight loop traces, 22 VGM
  files and the extended volume headers; **2,006 WAV-export checks** passed.
  External observation of 16 loud/demo/rate cases, 32 sides, found no outer-clamp
  loss, resampler clips, observer mismatches or integrator overflows; maximum
  preclip peak **0.317718506**.
- All 14 independent MAME tone comparisons pass unchanged 0.2% FM/SSG and 0.5%
  ADPCM tolerances. Maximum error is 0.1621% for the ADPCM octave; FM 3 measures
  880.208 versus 880.198 Hz. The focused FM 3 live-mode regression separately
  passes 264 checks; the old player fails 72.
- The final independent mixed-song check caught VGMPlay's different default
  FM/SSG balance. VGM 1.71 paired-SSG volume metadata now preserves the preview's
  nominal balance: isolated FM/SSG reference gains differ by **0.80% OPN / 0.82%
  OPNA**, rather than factors of about six/two. The new checker fails on all 24
  pre-fix structure checks. After the export-only fix, all 19 native WAVs and
  both Android demo WAVs are byte-identical.
- Both final Android document-picker VGMs were rendered by independent MAME:
  envelope correlations **0.815 / 0.983**, averaged log-spectrum correlations
  **0.848 / 0.921**, dominant pitch-class agreement **81% / 94%** (OPN / OPNA).
  These pass the existing 0.8/0.8/70% comparison gates; they are not bit-exact
  emulator equivalence, original-hardware measurements or a human listening review.
- **Nova, DMG and Konkr** passed actual activity/controller checks for playback,
  held audition, FM Sound/Expert/Ch3/Drum, SSG envelopes/noise/hardware envelopes,
  ADPCM generation/loop/trim/reverse, a real Android WAV import picker, exact
  undo/redo, restart, Stop and both exports. New blank/demo family menus and
  VGM/WAV save pickers were exercised on Nova, including after the metadata fix.
- Konkr replayed **16,065 ticks** in **15.60 s OPNA / 7.55 s OPN** without freezing
  the UI: 700/339 interaction rounds, maximum 14/22 ms, and Stop remained usable.
  Konkr and DMG reported no underruns or driver faults in these checks. Nova had
  isolated single settling underruns, including the legacy EPSM check; its buffer
  expanded 384→576 frames and subsequent stable windows had no further growth.
  No driver faults were observed.
- Nova's **pre-existing EPSM firmware** loaded without re-importing, played all six
  OPNA drums and all six EPSM drums, and appeared as installed in the real menu.
  Konkr/DMG correctly refused all six rhythm auditions without firmware.
  Private EPSM PCM was checked only in device cache and deleted there; neither
  that recording nor the ROM was copied into repository or release artifacts.
- Local desktop debug builds passed eight playback/audition/FM-3/Song/Stop scenes;
  both demos were played and visually inspected again after the export fix.
  The corrected signed APK's 28 entries, desktop executable and two picker VGMs
  contain neither the whole private ROM nor any of 256 distinct aligned 32-byte
  chunks. All 143 generated sample-export files passed the earlier same scan.

Local evidence is retained under `/tmp/thor-opna-final-frontend-e8ujjrin`,
`/tmp/thor-opna-final-vgm-60soo3d8` and `/tmp/thor-opna-vgm-mix-sz0dupl5`.
Native/private evidence also includes `/tmp/thor-opna-headroom-verified-trtorenl`
and `/tmp/thor-opna-final-renders-4cybt44o`; do not publish these directories.

Builds were serialized, one worker, in-process Kotlin. A combined optimized
desktop link was cancelled when it caused heavy swapping; the successful local
builds used a **1 GiB JVM, Serial GC, two active processors and debug desktop
linking**. No unrelated process was killed. The optimized AppImage build and
published-artifact smoke are publication gates. Thor/Deck receive artifacts only;
`/ThorTunez` remains untouched. Human listening and physical PC-88/98 comparison
remain unperformed.

## 0.29.0 OPM FM and reliable asynchronous transport

**OPM FM (YM2151)** (`ym2151-opm`) adds eight four-operator FM channels at
the X68000's 4 MHz clock, shared LFO, FM 8 noise, CSM, VGM/WAV export and
the **Sharp Corners** demo. Version 0.29.0/code 42.

Observed integration evidence:

- Generated constants, native fixtures and both compressed golden images
  reproduced byte-for-byte on a second generation.
- Final production-signed Android and native desktop builds passed, together with
  **739 JVM, 19 Linux and six Android-host tests**. Builds were serialized with one
  Gradle worker and in-process Kotlin. During the build the host remained 88–90%
  idle with no swap-in/out; occupied swap alone was not treated as memory pressure.
- The full native run passed the prior engines and **1,896 WAV-export checks**.
  The final focused OPM run passed **261 checks**, including current-row F09 tempo
  edits and undo, audition, LFO, noise, CSM, mute, loops and export.
- Corrected OPM gain **before** blip's int16 stage, preserving the YM3012's own
  DAC saturation. The focused clipping regression went from **3 failures out of
  258 checks** to **zero** at 44.1, 48 and 96 kHz; the 8 kHz boundary also passes.
  At 48 kHz the test song peaks at **0.4452**, RMS **0.0830**, with seek output
  bit-identical to straight playback. Tick thread-CPU cost in the final run was
  **311.6 µs mean / 677.8 µs worst**: benchmark evidence, not a timing pass/fail gate.
- Fixed VGM loop retriggering: the loop-state key-off now receives a chip sample
  before the next key-on. Actual repeated playback on ymfm reproduced the stale
  envelope before the fix and restores the first-pass attack afterwards.
- Independent Python reference: **1,500 ticks**, byte-identical **7,168-byte
  VGM**, 455 ticks / 334,425 samples, loop 263,865 samples. Header, command
  boundaries, register state dump and key-off waits pass separate parsing.
  A-4 measures **439.882 Hz**; all playable notes are within **4.4 cents** of
  ideal equal temperament using the chip's measured phase-step table.
- Independent libvgm **MAME and Nuked OPM** cores rendered the complete looped
  VGM against the 30-second engine WAV. Envelope correlations were **0.874 / 0.850**,
  averaged log-spectrum correlations **0.900 / 0.902**, and dominant pitch-class
  agreement **90% / 79%**. The reference renderer used 25% host gain to avoid its
  own PCM clipping. No production gain or comparison threshold was changed for this.
  The original single-periodogram comparison measured **0.790** for both cores:
  it compared phase-dependent noise peaks. The checker now averages five 100 ms
  Hann periodograms, retaining every frequency bin and the final shorter group;
  it still reports the original diagnostic and retains the 0.8 spectral threshold.
  A frequency-inverted negative control preserved the envelope but failed the
  spectral check (**−0.666**) and pitch-class check (**2%**).
- Actual activity exercises passed on **Nova, DMG and Konkr**: controller-driven
  playback, FM/noise editing and audition, undo/redo, LFO/CSM settings, restart,
  VGM/WAV export and seek/Stop. Every sampled xrun counter was zero.
  Normal final-row native seeks measured **494 / 1,018 / 1,733 ms**, respectively.
- Reproduced Konkr's slow-seek transport bug: the former 1,500 ms UI timeout
  expired before preparation, so Start did not stop subsequent playback.
  Explicit queued/native-published pending state replaces that timeout.
  The fixed **14.5-second, 16,065-tick** deep seek completed and stopped correctly;
  **648 UI round trips**, maximum **20 ms**, stayed responsive during preparation.
  Five permanent transition tests cover queued handoff, stop/restart ordering,
  failure, immediate/natural end and synchronous playback.
- All **32 Game Boy screenshots remain pixel-identical**. After removing the
  temporary device/screenshot harnesses and rebuilding the test APK, the clean
  **21-test Android suite passed** on Nova (48.138 s).
- The actual final SDL executable passed isolated playback, held audition and Stop
  scenarios with inspected surfaces. The standalone eight-second render produced
  peak **0.445**, RMS **0.0868**, and a **7,316-byte VGM** with a 5.98-second loop.

Published as **v0.29.0**, PR #40 / main `8f7c0ad`. CI's build, Deck and signed-APK jobs
passed; GitHub then marked the workflow failed without creating the AppImage attachment
job, produced no failed-job logs, and refused a retry. The successful Deck job's original
artifact was checksum-verified, exercised as an AppImage, and attached to the existing release.
The official APK passed activity smoke on Nova, DMG and Konkr. Nova recorded **one underrun**
before the first settled playback-plus-audition sample, with no subsequent growth; DMG/Konkr
recorded zero and all three recorded zero driver faults. The official Konkr deep seek took
**14.58 seconds native**, remained responsive (657 UI rounds, maximum 13 ms) and stopped correctly.
Thor received the APK in Downloads without installation or wake; the Deck received the
AppImage and updated stable `~/thortracker-deck/thor-tracker` symlink without launch or wake.
No human listening or original-hardware measurement is claimed.

## 0.28.0 Licensed tuning library and optional local packs

The library contains **500 tunings**: four existing stock choices, **424 DaMuSc
measured tunings** regenerated from pinned, explicitly licensed upstream data,
and independently generated **1–72 EDO**. Non-octave periods, original numeric
intervals and source/licence attribution are retained. Naren's files and
annotations are not redistributed; other collections are local user imports,
not bundled content or automatic downloads.

Verification:

- **706 JVM core tests, 14 Linux core tests and six Android-host tests passed**.
  Production-signed Android, instrumentation and native desktop builds passed,
  serialized with one Gradle worker and in-process Kotlin.
- After removing both temporary instrumentation harnesses, the freshly built
  clean test APK passed **all 21 permanent Android tests** on Nova (47.949 s).
- All three generated packs reproduced byte-for-byte. The APK contains exactly
  those three catalogue packs, totalling 500 entries, with packaged notices.
  The 41 explicitly flagged octave completions, 334 non-octave periods and
  measured scales spanning 2–25 degrees are preserved rather than normalised.
- Real Nova, DMG and Konkr activities exercised collection/category paging,
  search, numeric details and source scrolling, Apply, root/tolerance retention,
  Undo, project roundtrip and activity recovery.
- Actual Android document pickers imported a private **4,550-entry** local pack;
  every entry parsed with the production Scala parser. Import plus validation
  after file selection took **0.571 / 1.007 / 3.277 seconds** on Nova/DMG/Konkr.
  Worst measured UI round trips during import were **8 / 16 / 41 ms**.
  This private pack and its source files are not release assets.
- All three devices passed same-ID replacement cancellation/confirmation,
  invalid-scale visibility without Apply, removal cancellation/confirmation,
  selected-tuning retention after removal, bundled-ID protection and invalid
  UTF-8 rejection. DMG initially exposed a Sortie overlay obstructing the picker,
  then a smoke-harness race that waited for deletion rather than the completed
  library refresh; dismissing the overlay and awaiting the actual UI completion
  resolved those harness failures without changing app behavior.
- The actual SDL frontend exercised the same import/replace/remove lifecycle.
  Confirming replacement and Quit in one input step left the replacement fully
  persisted on exit. Oversized input and a corrupt installed pack produced
  visible errors; invalid UTF-8 now identifies the encoding problem instead of
  a generic file-operation error.
- The production parser also accepted all **4,581 source files** in the private
  audit corpus, including duplicate paths. Eight files initially failed because
  an attached Scala `!` annotation was treated as part of the interval; the
  corrected parser preserves and accepts those sources.
- **All 32 Game Boy renders remain pixel-identical** to verified 0.27.0,
  comparing complete decoded RGBA buffers. Factory Game Boy `.uge` bytes remain
  unchanged. Device and SDL tuning-library surfaces were inspected.

Project format is now **10**, storing only the selected tuning and its provenance,
not the catalogue. Versions 1–9 remain readable; older apps cannot read newly
saved format-10 projects, even when no tuning is selected. No new chip engine,
original-hardware comparison or human listening result is claimed here.

## 0.27.0 Konami SCC and SCC+

Two five-voice MSX wavetable systems: SCC shares waveform RAM between channels
4 and 5; SCC+ has five independent waves. Both provide signed 32-sample wave
editing, software envelopes, restart/test-register controls and VGM/WAV export.
The demos are **Cartridge Dusk** and **Prism Lane**.

Verification:

- **690 JVM core tests, 14 Linux core tests and six Android-host tests passed**;
  production-signed Android and native desktop release builds succeeded.
  Builds remained serialized with one Gradle worker and in-process Kotlin.
  The growing desktop compiler script now uses Bash stdin rather than a single
  command-line argument, avoiding Linux's per-argument size limit.
- The native suite passed; SCC has **606 functional checks** and WAV export
  **1,841 checks**. PC Engine retains 379 functional checks. Both chips report
  thread-CPU timing separately from correctness: hosted SCC CI failed a
  worst/mean ratio gate at **52.3 / 12.7 µs**, not an audio deadline or
  functional assertion. After removing that nondeterministic gate, all 606
  SCC checks pass; the local benchmark reports **32.2 / 10.9 µs** worst/mean.
- The independent player matches **1,500 ticks plus reset**. A per-clock chip
  model matches **2,545 events over 285,160 clocks**. SCC/SCC+ reference VGMs
  match byte-for-byte (23,356 / 24,204 bytes); clocks, writes, waits and loop
  boundaries pass independent parsing. A-4 measures **440.397 Hz**.
- Fixed keyed-off waveform ownership and released-note portamento. Focused
  native regressions went from **8/28** and **20/62 failures** respectively to
  zero, preserving held-silent legato, audible release tails and final-zero timing.
- Fixed shared-wave warnings that missed an overlap ending inside a row.
  The consumer-API regression failed on the isolated previous source and
  passes afterward, including delayed nonoverlap and persistent ownership
  uncertainty after a stepping tail ends. The inspector remains conservative,
  not a claim of tick-exact envelope simulation.
- Fixed seek's fractional resampler phase: silent clock advancement had left
  blip on the reset sample grid. Two seek targets differed by **66 and 30
  16-bit LSBs** before; they are now sample-identical. The existing 60-tick
  preroll and one-LSB tolerance were not relaxed.
- Real Nova, DMG and Konkr activities exercised both systems: playback,
  simultaneous audition, envelope/restart/wave edits, wave and sound clipboard
  with Undo, project roundtrip and seek. All reported **zero xruns and driver
  faults**. Maximum callbacks were **1.21 / 3.72 / 1.63 ms**, within the
  respective 4 / 20 / 20 ms bursts.
  After removing the temporary harnesses, the freshly built clean test APK
  passed **all 21 permanent Android tests** on Nova. Its separate existing
  Game Boy audio test recorded one startup xrun, zero driver faults and a
  0.869 ms maximum callback; the SCC scenarios above remained xrun-free.
- Demo seeks to the last order/row 24 took SCC/SCC+ **1.21/1.71 ms** on Nova,
  **2.83/4.24 ms** on DMG and **8.70/17.83 ms** on Konkr. Konkr's 16,065-tick
  worst-speed seek used the documented order-start fallback: **59.69/62.95 ms**
  native, **89/81 ms** wall time, maximum UI round trips **8/2 ms**.
- Nova's exported VGMs exactly match the independent factory-image exporter
  after removing metadata (36,685 / 42,773 bytes). WAVs are 48 kHz stereo,
  approximately **15.73 / 13.07 seconds**, with peaks **0.8225 / 0.7537**,
  RMS **0.1942 / 0.1425** and no clipped samples.
- Both Android-saved projects ran in the actual SDL frontend for six seconds,
  reaching order 1 rows 18 / 28, peaks **0.7680 / 0.6338**, and the Instrument
  view. Device Pattern/Wave and desktop Instrument surfaces were inspected.
  **All 32 Game Boy renders remain pixel-identical** to verified 0.26.0.
- The standalone SCC renderer built and ran: three-second WAV, peak **0.713**,
  RMS **0.1837**, order 1/row 14; VGM **7.58 seconds**, loop **5.98 seconds**.

No original-hardware capture, human listening session or libvgm audio
comparison is claimed. VGM loop-state dumps can reset waveform phase.

## 0.26.0 PC Engine HuC6280

The PC Engine adds six 32-step, 5-bit wavetable channels, noise on PSG 5–6,
the shared PSG 2 → PSG 1 LFO, stereo balance and timer-driven D/A samples.
The controller editors cover waves, software envelopes, sample import,
pitched/looped samples and a synthesized starter kit. **Turbo Lanterns**
demonstrates the system. Exports are VGM and WAV, not HuCard/HES executables.

Verification:

- **657 JVM core tests, 14 Linux core tests and six Android-host tests passed**.
  Android release, Android instrumentation and desktop release builds succeeded;
  the candidate APK was signed with the production key. Heavy jobs ran serially,
  with one Gradle worker and in-process Kotlin; no emulator.
- The full native suite passed: **380 PC Engine checks and 1,667 WAV checks**,
  alongside every existing engine. The independent Python player matches all
  **1,500 ticks**, including timer interrupts and CPU-cycle timestamps. Its VGM
  is byte-identical; independent parsing checks clocks, writes, waits and loops.
- Fixed released-note portamento: notes after OFF now start fresh attacks,
  including during a release tail. A final zero stops the voice and its DDA
  writes, without truncating the preceding audible tick or ending an
  intermediate zero. Held-but-silent notes remain legato. The focused native
  regression failed **20 of 52 checks before** and passes all 52 afterward.
- Fixed sample clipboard lifetime across documents and deleted source samples,
  with atomic Undo and full-bank refusal/reuse. An isolated original-source
  regression pasted another document's `[4,3,2]` frames instead of the copied
  `[31,23,19,11,5,0]`; the identical regression passes on the integrated source.
  The starter kit is generated at the song's DDA rate, preserving its pitch and duration.
- Nova passed **22 Android tests** (21 permanent plus the temporary PC Engine
  harness). Real-activity smokes on **Nova, DMG and Konkr** exercised playback,
  simultaneous audition, envelope/wave edits, sample root/loop edits,
  cross-document sample paste and Undo, project roundtrip and deep seek.
  PC Engine scenarios reported **zero xruns and zero driver faults**. The
  separate existing GB audio test recorded one startup xrun and passed.
  After removing the temporary harnesses, a freshly built test APK passed
  **all 21 permanent Android tests** again on Nova (zero xruns or driver faults).
- Demo seek to order 4/row 48 took **134 / 283 / 428 ms** natively on
  Nova/DMG/Konkr. Maximum callback times were **2.71 / 11.47 / 4.89 ms**
  against 4 / 20 / 20 ms bursts. A worst-speed order seek on Konkr (16,065
  ticks, five high tones plus looped DDA) took **14.87 s**, with a maximum
  UI round trip of **17 ms** and no audio faults. Exact-phase long seeks remain
  a documented performance tradeoff, not a claim of instant access.
- Nova's 32-second WAV is 48 kHz stereo, peak **0.6112**, RMS **0.0431**,
  without clipping. Its VGM command stream and normalized header exactly match
  the independent factory-image export (**482,027 bytes** before GD3); metadata
  decodes correctly.
- The actual SDL frontend opened the Android-saved project, played for six
  seconds and switched to the sound editor: transport reached row 50, audio
  peak **0.3383**. Desktop and real-device wave/sample surfaces were inspected.
- Factory Game Boy `.uge` bytes and **all 32 Game Boy view renders** are
  identical to verified 0.25.0 (RGBA comparison).
- The documented standalone renderer also built and ran: three seconds of
  WAV reached order 0/row 30; its complete-song VGM is 32.00 seconds with
  a 25.60-second loop.
- A separate real-activity Nova smoke opened the Android Downloads picker and
  imported a 44.1 kHz WAV into **1,747 five-bit frames at 6,991 Hz**, auditioned
  it and undid the import. Starter-kit insertion, audition and Undo also passed;
  the picker and sample-editor screenshots were inspected.
- Published **0.26.0** assets were checked against release SHA-256 digests and
  the production signing certificate. The official APK passed the PC Engine
  controller/audio scenario on Nova, DMG and Konkr, with zero xruns or driver
  faults. Konkr's first USB instrumentation connection was interrupted; its
  original timeout was restored before a successful rerun.
  The official AppImage also played the Android project through row 50.
  The APK was copied to Thor Downloads and the Deck's stable `thor-tracker`
  symlink updated, with destination hashes verified; neither device was
  woken or launched, and Thor's installed app was not changed.

No human listening test, original-hardware capture or libvgm audio comparison
is claimed. The VGM loop-state dump can reset waveform phase; WAV retains the
app's continuous render. `/ThorTunez` was untouched.


## 0.25.0 AY / YM2149 computers

Four computer variants join the sound-system chooser: **ZX Spectrum 128, MSX,
Atari ST and Amstrad CPC**. Each uses its own clock, frame rate and stock output
wiring. Three tone channels share one noise generator and one hardware
envelope; the Pattern inspector shows who owns those shared resources.

The sound editor combines software envelopes with the chip's hardware shapes,
pitched buzzer/sync sounds and drum sweeps. Its expert page exposes the raw
envelope period, with divider/frequency readings and 16-level AY or 32-level
YM shape diagrams. Each variant includes Copper Lane and exports VGM/WAV.
The CPC's WAV/preview is stereo (A left, B both, C right); standard AY VGM is
mono and cannot describe that socket wiring.

Verification:

- **612 JVM core tests, 14 Linux core tests and six Android-host tests passed**;
  signed Android and desktop release builds succeeded. Builds used one Gradle
  worker, in-process Kotlin compilation and serialized heavy jobs; no emulator.
- The full native suite passed, including **1,010 AY checks and 1,580 WAV
  export checks**. Independent Python player traces match all 1,500 ticks per
  variant. VGM bytes, headers, fractional frame waits and loop boundaries
  match the independent exporter.
- An independent ayumi comparison checks **9,401,550 chip steps**: exact tone
  counters/edges, noise LFSR, comparable envelope phases and gated DAC levels.
  Solo/mixed audio RMS-envelope correlations are **0.9907–0.9994**. Reference
  writes are scheduled at their exact chip clocks: rounding them to 48 kHz
  sample boundaries changed synced-buzzer timbre and was an invalid comparison.
  The original 0.95 correlation and 0.8–1.25 RMS bounds remain.
- Fixed three reproduced envelope edges: AY period zero advances twice as fast
  as period one (YM's rates are equal); a silent note retains its restart until
  its first audible tick; a previously audible non-owner cannot cause a stale
  restart when the shared envelope changes owner. The focused period check
  failed 30 checks before the fix and passes all 172 afterward. Each old
  restart implementation failed 16 checks; the corrected one passes all 40.
- Nova passed the **21 permanent Android tests** after both temporary harnesses
  were removed. Separate real-activity smokes on **Nova, DMG and Konkr**
  exercised all four computers: playback, held-X audition during playback,
  hardware shape/period and software-envelope edits, Undo and seek to order
  3/row 48. All reported zero xruns and zero driver faults. Maximum native seek:
  **0.56 / 1.18 / 2.73 ms** respectively; maximum audio callback:
  **3.74 / 5.32 / 2.19 ms**. Their bursts were 4 / 20 / 20 ms.
- Nova exported all four projects to WAV and VGM. WAVs are complete 48 kHz
  stereo renders with nonzero signal and no clipping. Every VGM command stream
  and normalized header byte-matches the independent Python export of the
  factory image; GD3 metadata decodes correctly.
- The actual SDL frontend opened each Android-saved project, played for six
  seconds and switched to its sound editor. All four had advancing transport
  and nonzero audio; desktop and real-device surfaces were inspected.
- The Game Boy factory `.uge` is byte-identical to 0.24.0. **All 32 Game Boy
  views are RGBA-pixel-identical** to that verified build.

Temporary test packages and owned device captures were removed; device timeouts
were restored and the reserved devices returned Home/asleep. `/ThorTunez` was
untouched. No human listening test, vintage-hardware comparison or physical
Deck controller test is claimed. VGM's sample-granularity timing and external
players' resamplers can alter the phase-sensitive buzzer timbre; WAV preserves
the app's render.
## 0.24.0 Cumulative controller layers

**L2 + R2 is a third, independent chord layer**, whichever trigger is pressed
first. Its defaults mirror the R2 view shortcuts: A/B/X/Y show
Song/Instrument/Sketch/Keyboard, and Up/Right/Down show Pattern/Wave/Help.
With two displays they change the other pane without moving controller focus;
with one display they act on that display. L2+R2+Left remains unassigned.
No existing shortcut moved. A missing third-layer chord cannot accidentally
run a matching L2-only or R2-only command.

Controls can capture two modifiers and export them in keymap format 6.
Older keymaps retain their custom bindings and gain unclaimed new defaults.
Rolling releases preserve a press's original meaning. Releasing an analog
trigger during capture now removes it from the captured chord.

Canceled controller input no longer turns an A release into a tap or cursor
advance. Edits already made on press or during a held adjustment remain;
cancellation is not Undo. The cursor-advance defect was reproduced on Nova
before the fix and the same regression passed afterward.

Verification:

- 575 JVM core tests, 14 Linux core tests and six Android-host unit tests
  passed. Signed Android and desktop release builds succeeded with serialized,
  single-worker builds; no emulator was used.
- Nova passed all **21 permanent Android tests** with the temporary render
  harness removed. The real Controls UI captured both triggers, excluded a
  trigger released before the button, and preserved both bindings through
  export, reset and import. DMG and Konkr each passed the three targeted
  layer/cancellation/capture regressions.
- Nova also passed the other-pane test with a **1240×1080 simulated secondary
  display**. The test required two actually attached app hosts; it verified
  unchanged controller focus and unchanged document data. A live screenshot
  showed Song on the secondary presentation beside the primary Pattern view.
  The overlay setting and device timeouts were restored.
- The actual desktop frontend exercised all seven third-layer view shortcuts,
  both trigger orders, existing Undo/Redo and R2 shortcuts, and both right-stick
  double-flick jumps. A separate SDL event-injection smoke opened the real
  binding capture, held L2/R2, released L2, then pressed Left: the app persisted
  **R2+Left**, not L2+R2+Left.
- The factory Game Boy `.uge` remained byte-identical. Full-RGBA comparisons
  found **30 of 32 Game Boy renders identical** to 0.22.0; only the two
  Help→Controls renders changed for the new layer's instructions and shortcuts.
  Those changed surfaces were inspected.

Thor and physical Deck controller ergonomics were not tested in this round.
Reserved-device test packages and owned render files were removed; external
projects and `/ThorTunez` were untouched.
## 0.23.0 Mega Drive PCM kits, FM channel 3 and XGM2

Mega Drive songs now have three PCM lanes alongside the six FM and four PSG
lanes. PCM kit instruments map notes to imported WAV samples, with per-key
pitch, a chromatic spread action and a static kit level. FM 6 is inaudible while
the DAC plays PCM. The FM instrument editor's **Ch3** page enables independent
operator pitches on FM 3, including semitone and 1/64-semitone offsets.
Existing songs migrate without changing their tonal data; Neon Causeway remains
the normal demo, with Night Ferry as a separate PCM/FM3 verification fixture.

The real SGDK XGM2 Z80 driver replaces XGM1. VGM exports record its actual
timestamped writes and mixed DAC output. `.xgm` is unpacked XGM2; `.xgc` is the
already compiled form for `XGM2_play`. Import `.xgc` into SGDK with
`BIN music "song.xgc" 256`, without further resource compression.

**Upstream tool caveat:** pinned xgm2tool 1.08 corrupts its XGM2 re-import:
it retains unpacked loop offsets in packed output and flips signed PCM bytes
by `$80`. Its XGM→VGM path also throws a cast exception on its own output.
Use the compiled `.xgc` directly, not its `XGM2` resource conversion.
Unmodified SGDK ResComp 4.02 successfully imported both compiled fixtures as
256-byte-aligned BIN resources, preserving their 32 KiB of combined data.

Verification:

- 559 JVM core tests, 13 Linux core tests, six Android-host unit tests and the
  19-test permanent Android suite passed. The signed APK and desktop executable
  built serially with one Gradle worker and in-process Kotlin compilation.
- 807 Mega Drive native checks and 1,304 common WAV checks passed. Coverage
  includes PCM pitch and bounds, FM3 register ordering, mute/DAC ownership,
  held-note edits and bank replacement, exact machine-state seeking, stream
  ring wraps, allocation-free tick/edit paths and shortest looping songs.
  The PCM tone measured 424.62 Hz against 424.03 Hz expected (2.4 cents).
- The independent Python player matched both 1,500-tick native traces.
  Independent file parsing verified sample IDs/data, commands, loop targets,
  packed blocks and musical frame counts. The PCM fixture has 360 musical
  frames over 364 physical ticks: the real driver's refill delays are retained.
- A separate direct-file consumer loaded exported sample banks, tables and
  packed streams into the actual Z80 driver, without the live stream producer.
  All 68 checks passed through at least two loop jumps. Tonal playback matched
  the reference for 1,222 frames; PCM playback for 665 frames, with 79 triggers,
  17 stops and 149,837 DAC writes. Exported waits can alter refill timing;
  agreement is checked at executed musical frames, not assumed vint indices.
- Desktop playback and held PCM audition produced nonzero audio. The actual
  file picker imported a 96 kHz stereo WAV as 4,800 signed samples at 48 kHz,
  reported resampling/downmixing, mapped it to C#3 and auditioned it. PCM kit
  and FM3 editor surfaces were inspected on desktop and reserved handhelds.
- Nova, Konkr and DMG passed real-activity playback, paired held audition,
  audition alone, controller edits of PCM level and FM3 fine pitch, exact undo,
  save/decode and a seek to order 3, row 48. All reported zero driver faults.
  Seek preparation took 0.813 s / 2.976 s / 1.793 s respectively: replay is
  cycle-exact and linear in the destination, not an instantaneous register dump.
- Konkr and foreground DMG reported zero underruns, with 960-frame bursts and
  1,920-frame buffers; maximum callbacks were 15.825 ms and 27.614 ms.
  Nova's 192-frame low-latency stream needed adaptive buffering: observed runs
  reported two to four underruns while growing from 384 to 768–1,152 frames.
  In the extended run, the count stayed at two throughout 28 seconds of paired
  playback/audition, at 768 frames and a maximum callback of 11.982 ms.
  These are observed device costs, not a claim of glitch-free startup.
- Android JNI VGM/XGM/XGC exports of Night Ferry were byte-identical to the
  native host exports. Every sample in its 1,187,929-frame, 48 kHz stereo WAV
  matched the host render before the latter's fade.
- All 32 Game Boy view captures were RGBA-identical to the preceding release,
  and the factory `.uge` was unchanged. Temporary harness source, device test
  packages and owned captures were removed; timeouts were restored and the
  reserved devices returned Home/asleep. `/ThorTunez` was untouched.

No physical Mega Drive cartridge or human listening comparison was performed.
The host still has substantial occupied swap, so verification remains serialized;
the post-build sample showed no swap-out activity rather than memory saturation.

## 0.22.0 Sega Master System, Game Gear and FM Sound Unit

**New blank song / Load demo → Sega 8-bit…** offers four layouts: SMS PSG,
Game Gear stereo PSG, SMS FM (four PSG plus nine FM channels), and SMS FM rhythm
(four PSG, six FM and five hardware drums). The real unmodified Banjo driver runs
on an emulated Z80, with SN76489 and emu2413 synthesis. Each layout has its own
Harbour Lights demo, instrument editor and WLA-DX/SDAS/VGM/WAV exports.

The integrated build passed 538 JVM core tests, 13 Linux core tests and six
Android-host unit tests. The full native run passed, including 120 Sega checks
and 1,219 WAV-export checks.
Each layout's demo and shortest-song fixture rendered through the real Banjo driver.

Independent assembly verification compiled every exported song with both WLA-DX
and SDAS/GBDK, using the pinned upstream Banjo driver. For each layout, the exported
ROMs and the application's `SmsMachine` executed 3,457 ticks, 577 row starts and two
loops with identical transport and driver state. All chip writes matched in order
and CPU cycle: SMS PSG 26,130; Game Gear 26,149 (including 19 stereo writes);
SMS FM 20,516; SMS FM rhythm 19,114. Relocated song data also matched byte-for-byte.
This exercises the exported initialization stub, including the first-row tick,
rather than asserting assembly-source spelling.

Actual desktop playback advanced for all four layouts with nonzero PCM. The
grouped system picker and creation of a new rhythm-mode song were inspected,
along with PSG envelopes, custom two-operator FM voices and fixed-pitch drum kits.
Held Listen produced audio for all three instrument kinds.

On Nova, all four layouts passed real-activity song playback, playback plus held
audition, and audition-only observation (ten seconds per phase). All reported zero
underruns and driver faults at 48 kHz, 192-frame bursts and a 384-frame buffer.
Maximum observed callbacks: PSG 3.316 ms, Game Gear 1.797 ms, FM 6.665 ms,
FM rhythm 7.491 ms. DMG and Konkr passed the same three phases for the rhythm
layout with zero underruns/faults, 960-frame bursts and a 1,920-frame buffer;
maximum callbacks were 25.239 ms and 8.594 ms respectively. DMG's first probe
could not acquire focus because Sortie's switcher overlay was open; dismissing
that overlay and confirming Tracker focus resolved the prerequisite. No launcher
configuration changed.

Through the actual Android and desktop file menus, all sixteen exports (four
layouts × WLA-DX ASM, SDAS, VGM and WAV) were byte-identical across platforms.
Every WAV contains 1,537,980 frames of 48 kHz, 16-bit stereo PCM (32.04125 seconds).
Peaks are 16,112 / 15,796 / 15,234 / 17,282 respectively, without rail samples.
Game Gear has 1,537,833 frames with different left/right samples; the other
layouts are mono. VGM headers, clocks, loop offsets and parsed stereo/FM writes
were checked. Android custom FM feedback editing (5→6), undo (→5), Save As and
cold recovery passed; the saved project was byte-identical to the original.

The clean permanent Android suite passed all 19 tests on the production-signed
candidate. All 32 Game Boy views were RGBA-identical to the 0.21/0.20 baseline,
and the factory `.uge` was unchanged. Temporary probe source/test packages,
21 owned Android fixture/export files and 32 captures were removed. Device
timeouts were restored, reserved handhelds returned Home/asleep and all leases
released. `/ThorTunez` was untouched.

The clean signing job twice hit Gradle's default ten-second distribution-download
timeout before compilation. The wrapper now allows sixty seconds per network
operation; signing still neither restores nor saves a Gradle cache. An isolated,
empty `GRADLE_USER_HOME` downloaded Gradle 8.9 and completed `--version` in 13.83 s.

A subsequent CI run passed the audio/reference checks but failed an OPL3 timing
ratio: 1.419 ms worst versus 0.373 ms mean. Thread CPU time still varies with
frequency and cache contention, so the benchmark now reports its measurements
without an arbitrary 3× pass/fail threshold. The unchanged OPL3 engine passed
281 functional checks; its rendered WAV, VGM and register trace remained
byte-identical. Paced target-device playback remains the realtime check.

No physical Sega cartridge comparison or human listening review is claimed.
Hardware limits remain visible: one shared custom OPLL patch, shared rhythm
pitches and pitched-noise/Square 3 coupling. NTSC timing only; Banjo cannot
represent fine-pitch tunings or independent FM pitch envelopes at this pin.

## 0.21.0 NES + EPSM

**New blank song / Load demo → NES… → NES + EPSM** adds the YMF288 expansion module:
three SSG voices, six four-operator FM voices and six rhythm drums after the NES's
five channels. **Expansion Port** demonstrates the tonal voices. Instruments expose
the SSG's shared noise/envelope, curated FM controls, the full FM patch and rhythm
level/panning. Playback, audition, NSF and FamiStudio text retain the real
FamiStudio 4.5.3 driver; WAV renders the same emulated output.

The rhythm drums require a user-supplied, hash-verified 8 KB YM2608 rhythm ROM.
Missing or invalid firmware refuses rhythm playback explicitly. Importing a valid
ROM recovers playback without restarting, and the private import survives a cold
restart. No rhythm ROM or derived drum audio is included in source or releases.

The combined MMC5/EPSM integration passed 515 JVM core tests, 13 Linux core tests and
six Android-host unit tests. Regeneration preserved all 32 generated NES outputs
byte-for-byte; the EPSM player is 5,048 bytes and the factory Game Boy UGE is unchanged.
Native EPSM coverage passed 332 checks with a privately supplied rhythm ROM,
including SSG attack preservation of drum pan, live patch edits and unreachable-row
seek fallback with identical instrument copies. Other native engine checks passed:
Game Boy 145, NES 336, VRC6 105, VRC7 115, N163 225, FDS 227, Sunsoft 5B 204 plus
10 seek-fallback checks, MMC5 130, PCM 95, mixer budget 13, SID 151, OPL3 282 and
Mega Drive 501.

WAV coverage passed 999 checks: samples equal straight engine ticking, loop boundaries
and chunk-size independence remain checked. The old generic `peak > 1000` assertion
was removed, not repinned: master volume is not a WAV-format contract. EPSM's raw-range
headroom deliberately makes its demo quiet (test-song RMS 0.0064, peak 0.0400;
exported demo peak 923). It preserves relative balance without claiming unverified
hardware DAC clipping; the gain policy is documented in `docs/AUDIO_ENGINE.md`.

The permanent Android suite passed all 19 tests against the production-signed,
non-debuggable candidate on Nova. Thirty-two Game Boy views are pixel-identical
to 0.20.0's same-device baseline in full RGBA bytes. Twenty-channel Pattern rendering,
editing and undo now work; the last Rim lane survives a cold process restart.
Restoration waits for the actual song instead of clamping its channel against the
temporary Game Boy demo. Following playback also clamps a stale channel when
switching back to a smaller system.

Actual Android and desktop export dialogs produced byte-identical tonal NSF
(32,832 bytes) and FamiStudio text (49,003 bytes). Their private rhythm WAV exports
contain identical PCM: 38,336 frames of 48 kHz, 16-bit stereo audio, peak 475.
An SSG manual period unsupported by FamiStudio text is refused with an actionable
message on both platforms; neither leaves an empty export. Desktop now catches
that error rather than aborting. Desktop also recovers from a rejected Play request
after importing the ROM with one subsequent Play action.

Nova exercised the curated and Expert FM pages, a brightness edit and undo,
missing/invalid/valid firmware, invalid replacement and cold-restart rhythm playback.
A 14.87-second Android output capture contains nonzero tonal PCM; it is an output
capture, not microphone recording or human listening. Desktop held FM audition
also produced nonzero output. Physical-cartridge comparison and human listening
remain unverified.

Production MainActivity timing exposed Nova underruns with EPSM at its original
384-frame / 8 ms buffer. AAudio now adds one burst after a measured xrun, capped by
the device's capacity; a newly opened stream still starts at two bursts. In the
bounded playback-plus-audition check Nova settled at 768 frames / 16 ms after two
xruns, with no further increase through held audition and audition-only playback.
Its Game Boy check retained 384 frames with zero xruns. Konkr kept its existing
1,920-frame buffer with zero xruns throughout Game Boy, EPSM, simultaneous playback
and FM audition, and audition-only checks (10 seconds each); its maximum callback
was 12.76 ms. All observed driver-fault counts were zero. This is measured buffer
adaptation, not a claim of universally underrun-free playback.

DMG passed the same four 10-second scenarios at its existing 1,920-frame buffer,
with zero xruns and driver faults; maximum callback time was 24.43 ms. Its initial
controller-driven probe was blocked by a focused Sortie switcher overlay. After
dismissing that overlay and confirming Tracker's window focus, the probe used the
actual toolbar Play action and passed. No launcher settings were changed.

Temporary timing/capture sources and test packages were removed, along with the
32 owned device captures and eight owned Downloads fixtures. Reserved-device display
timeouts were restored and the devices returned Home/asleep; `/ThorTunez` was untouched.

The published 0.21.0 APK and AppImage passed their checksum manifests; the APK has
the production certificate and version code 34. The published standalone AppImage
played Expansion Port for five seconds, reaching order 0/row 39 with nonzero PCM
(peak 0.011538708), and its actual Pattern surface was inspected.
The official APK was installed over the existing app and cold-launch checked on
Nova, DMG and Konkr. Thor received only the APK in `Download/Thor Tracker`; the Deck
received the executable AppImage and an updated stable `thor-tracker` symlink.
Remote digests matched. Neither daily device was woken or launched; `/ThorTunez`
was untouched and all device leases were released.

## 0.20.0 NES + MMC5

**New blank song / Load demo → NES… → NES + MMC5** adds two pulse voices and an
eight-bit PCM lane to the NES's five channels. **Tandem Skies** demonstrates the
pulse voices; **Sample Courier** exercises pitched PCM mappings, loops and drums.
The PCM editor imports WAV files, maps samples to notes and edits pitch and looping.
Its clocked software player drives the MMC5 DAC; it does not model the 6502 cost
of a game's sample-feeding routine. FamiStudio 4.5.3 still plays the seven other lanes.

WAV exports include PCM. NSF and FamiStudio text exports retain the real seven-stream
driver and reject songs that play the PCM lane rather than silently dropping it.
Android now removes the newly created export document if generation or writing fails;
the working project is not that document and is never deleted by this cleanup.

Verification so far: 493 JVM core tests, 13 Linux core tests, six Android-host unit
tests; native coverage includes 130 MMC5, 95 PCM, 13 mixer-budget and 962 WAV checks.
The cleaned permanent Android suite passed all 18 tests on Nova. A temporary capture
produced 32 Game Boy views, all identical to the same-device baseline in full RGBA bytes.
The capture source, device capture files and temporary test package were removed.

Nova exercised PCM audition, a pitch edit, project save/reload and WAV import.
The saved pitch edit changed only the selected mapping. An imported 16 kHz fixture
preserved every byte except its 256 zero values, which became 1 as reported by the UI
because the MMC5 DAC ignores zero writes; the existing sample pool stayed unchanged.
The imported project also played through the actual desktop frontend.

Android's no-fade PCM WAV contains 1,175,663 frames of 48 kHz, 16-bit stereo audio,
peak 8,494 and no rail samples. A separate 19.84-second REMOTE_SUBMIX capture confirms
live Android output (peak 1,378, RMS 0.007669 at the device's existing volume), not a
microphone recording or human listening. Valid tonal-demo NSF and text exports are
byte-identical to the generated reference exports. Both rejected PCM formats display
the actionable error and leave no empty document. Owned Download smoke files were
removed; `/ThorTunez` was untouched.

The final production-signed, non-debuggable 0.20.0/code 33 candidate passed actual
tonal and PCM playback, mapped audition and editor inspection on DMG and Konkr.
Both active app AudioFlinger tracks reported zero underruns. Konkr used 32.44 CPU
seconds over 65.57 wall seconds: 49.5% of one core for the whole app, including UI,
not an isolated callback measurement. Original display timeouts were restored and
both devices returned Home/asleep.

The versioned desktop frontend exported the same edited PCM project through its
actual WAV dialog. Both platform exports contain the same 1,175,663 stereo frames;
only six of 2,351,326 samples differ, each by one least-significant bit.

The published 0.20.0 APK and AppImage passed their checksum manifests; the APK is
production-signed, version code 33. The published AppImage played the imported PCM
project for five seconds (order 0/row 40, peak 0.25267678), with the actual Pattern
surface inspected. The official APK was installed over the existing app and
cold-launch checked on Nova, DMG and Konkr. The Thor received only the APK in
`Download/Thor Tracker`; the Deck received the executable AppImage and an updated
stable `thor-tracker` symlink. No Thor/Deck wake, installation or launch occurred;
`/ThorTunez` was untouched and all device leases were released.

### N163 output clipping

The N163 mixer now budgets the combined 2A03/N163 range before the int16 blip stage and the final output stage,
including filter overshoot. This preserves the chip-to-APU balance, reduces master level by about 10.5 dB,
and leaves the previous driver images and song exports unchanged.

All 13 combined mixer-budget checks passed. The rendered N163 extreme peaked at 0.7680; the calculated bound
over the supported sample rates is below 0.979. The eight demo WAV peaks were 12680, 10687, 9862, 8745, 8466,
7825, 7964 and 7932, rather than the first three hitting the 32767 rail.

The actual desktop frontend played the one-voice demo through its APU-only introduction into the N163 lead:
11 seconds, order 1/row 36, peak 0.3869816, with the active N163 pattern visible. This was offscreen/dummy-audio
execution with rendered output measurements, not human listening or a physical-cartridge comparison.

## 0.19.0 NES + FDS

- **New blank song / Load demo → NES… → NES + FDS** adds the Famicom Disk System's wavetable voice after the NES's five channels. The demo is **Side B**.
- Instruments have 64-sample, 6-bit wave sequences with repeats, loop and release; four master-volume levels; a 32-entry modulation table; depth, delay, full 12-bit manual speed and automatic pitch ratios. The editor distinguishes the table's targets from the steps the chip actually plays.
- Playback, audition, live edits, NSF, FamiStudio text and WAV use the pinned FamiStudio 4.5.3 driver. A scoped loader correction disambiguates manual speed low bytes `$80`–`$FF` from automatic modulation. A scoped `$5F` instrument-load extension preserves runtime modulation without aliasing identical instruments' live-edit identities.

Verified: 473 JVM core tests, 13 Linux core tests and six Android-host unit tests; the integrated native command passed
(Game Boy 145, NES 336, VRC6 105, VRC7 115, N163 225, FDS 227, Sunsoft 5B 204 plus 10 seek-fallback checks,
SID 151, OPL3 282, Mega Drive 501, WAV 850). Independent FamiStudio traces cover the demo, effects, manual and
automatic instrument copies, and manual-speed command witnesses. The second-loop copy regression reproduced
four native failures before correction and passes afterward. All 29 previous player/reference outputs, 64 previous
golden files and 31 previous sample exports, including `thor-groove.uge`, remain byte-identical.

The production-signed 0.19.0/code 32 candidate passed all 18 Android instrumentation tests on Nova. A separate
temporary capture test produced 32 Game Boy views, all RGBA-identical to the same-device 0.14.1 baseline.
The capture harness, test package and device capture files were removed.

Actual Side B playback and its six-lane Pattern surface were inspected on Nova, DMG and Konkr. Each active audio
track reported zero AudioFlinger underruns during its check. On Konkr, the whole app used 11.26 CPU seconds over
24.78 wall seconds of playback: 45.4% of one core, including UI work, not an isolated audio-callback measurement.
DMG exercised the wave and modulation pages, controller audition and a manual-speed edit across 127→128.
Its saved and reloaded project retains speed 128, depth 4 and delay 18.

NSF and FamiStudio text exported through Android's document picker are byte-identical to exports of that saved
project through the actual desktop frontend. Fresh no-fade WAV exports both contain 1,226,779 frames of 48 kHz,
16-bit stereo PCM; 140 of 2,453,558 samples differ, by at most two least-significant bits. The Android peak is
25,797. The comparison uses fresh renders: a hidden Android WAV-ready popup deliberately retains its original
snapshot, even when the working project changes. Owned Download smoke files were removed; `/ThorTunez` was untouched.

The actual desktop frontend also exercised held-note audition (peak 0.45099) and playback (peak 0.63122).
Native WAV checks include the shortest valid one-row, one-tick FDS song. These checks are not human listening,
physical Famicom/RAM-adapter testing, or comparison with a reference FDS emulator's audio. Thor was not used.

Published as [v0.19.0](https://github.com/esko997/thortracker/releases/tag/v0.19.0) from main
`9f8b9fa4741ae7bb0367bf49ecb96768c94bee25`; PR run 42540956821 and publication run 42541693480 passed.
The downloaded release APK is version code 32, signed by the production certificate, and its SHA-256 is
`84a30e55630bc24e35e106d956e3dc4f1417ed6881e7f7963d94749f004f2f23`.
It was installed over the existing app on Nova, DMG and Konkr; each reported 0.19.0 and cold-launched to an
observed app surface. Device timeouts were restored and the devices returned Home/asleep.
Thor received that same verified APK in `Download/Thor Tracker/` only: no install, wake or launch.
The published AppImage SHA-256 is `c07464ccb33f7cc4ec20ad83f8314f8e92d45e2c908304851478d9d58981fe02`;
the Deck copy was verified and its stable `~/thortracker-deck/thor-tracker` symlink updated without launching it.
Locally, the published AppImage opened *Side B* in an isolated home and advanced playback to row 30 with
peak 0.63122; its actual Pattern surface was captured and inspected. Both artifacts matched their published
checksum files. `/ThorTunez` was untouched.

## 0.18.0 NES + Sunsoft 5B

- **New blank song / Load demo → NES… → NES + Sunsoft 5B** adds three square channels after the NES's five channels. The demo is **Copper Kite**.
- Instruments have volume, arpeggio, pitch, mixer and noise envelopes, plus a hardware-envelope page with shape, automatic pitch or a fixed manual period. The three squares share one noise generator and one hardware envelope; Pattern badges show their owners.
- Playback, audition, live edits, NSF, FamiStudio text and WAV use the pinned FamiStudio 4.5.3 driver. A scoped instrument-load extension preserves the shared manual period when switching between identical instrument copies without losing their independent live-edit identities.
- Fixed order-start seek fallback dropping carried instruments. It now restores them through the real driver's instrument loader; the fallback remains approximate for earlier shared-period effects and generator phases.
- The shared-state inspector follows actual instrument-reload suppression and pending period overrides. Lane headers reserve space for badges and horizontal-scroll indicators, and omit instrument labels when even an ellipsis cannot fit.

Verified: 444 JVM core tests, 13 Linux core tests and six Android-host unit tests; the integrated native command passed
(Game Boy 145, NES 336, VRC6 105, VRC7 115, N163 225, Sunsoft 5B 204 plus 10 seek-fallback checks,
SID 151, OPL3 282, Mega Drive 501, WAV 794). Independent FamiStudio traces cover the demo, effect/loop behavior and
identical manual-period instrument copies. The copy regression first differed at frame 24: period 900 instead of
2000; it now matches. Removing fallback restoration reproduces three failures; all ten checks pass with it.
The inspector's carried-period smoke failed before its correction and now passes all eight checked positions.
Previous player/reference outputs, 31 prior sample exports and the factory `.uge` remain byte-identical.

The final production-signed candidate passed all 18 Android instrumentation tests on Nova. A separate temporary
capture test produced 32 Game Boy views, all RGBA-identical to the same-device 0.14.1 baseline. The capture harness,
test package and device capture files were removed.

Actual Copper Kite playback and lane headers were inspected on Nova, DMG and Konkr; each active audio track reported
zero AudioFlinger underruns during its check. DMG exercised all eight lanes and a controller audition while changing
the manual envelope period from 600 to 601. Its saved project retains 601. NSF and FamiStudio text exported through
the Android document picker are byte-identical to exports made through the actual desktop frontend.
The edited no-fade WAV has 1,226,779 frames of non-silent 48 kHz, 16-bit stereo PCM, peak 17,064.
Android and desktop WAVs have the same length; 20 of their 2,453,558 PCM samples differ by one least-significant bit.
The four owned Download smoke files were removed, and `/ThorTunez` was untouched.

The actual desktop frontend also produced a nonzero audition peak (0.24216) and exported the shortest valid
one-row, one-tick 5B song without a fade: 798 non-silent frames. These checks are not human listening or a comparison
with a physical Sunsoft 5B cartridge or a reference emulator's audio.

Published artifacts: main workflow `42535995173` passed build, Deck, APK publication and AppImage publication.
The APK's production certificate and version 0.18.0/code 31 were verified; it was installed over the existing app
and cold-launched on Nova, DMG and Konkr. Thor received the APK in Downloads without an install or wake.
The Deck received the AppImage and an updated stable `thor-tracker` symlink without launching it.
Both remote artifact digests matched the published checksums. The published AppImage itself played Copper Kite
locally to row 29 in four seconds with peak 0.24205; its actual Pattern surface was inspected.
APK SHA-256: `731bca584bb3d5b5515ebd0cc0e166a688bd04b805d8a92a0a92fa8e28d2b26d`.
AppImage SHA-256: `3c4364f03eb31b48437b715839c36f36413b98e9a0e33f16818cea5c59591c9f`.

## 0.17.0 NES + Namco 163

- **New blank song / Load demo → NES… → NES + N163…** offers all eight fixed channel counts. **Wavetable Garden** grows from six total lanes to thirteen; channel count changes the chip's update rate and the RAM available for waves.
- N163 instruments have volume/arpeggio/pitch envelopes and sequences of 4-bit waves, with size, RAM position, repeats, loop and release. The editor shows the shared RAM map and warns about overlapping sounds. Wave sizes other than the driver's 16-sample tuning are explained rather than silently retuned.
- Playback, audition, live edits, NSF, FamiStudio text and WAV use the corresponding pinned FamiStudio 4.5.3 driver. The native chip preserves time-multiplexed DAC behavior, including the high-frequency whine at large channel counts.
- Fixed VRC7 live edits of identical instrument copies: musical equivalence no longer aliases their runtime bank identities. Editing an unused original does not alter its held copy; held copies follow their own patch edits without restarting volume envelopes.
- Fixed Mega Drive mute/unmute continuity: YM2612 feedback continues to evolve while muted instead of freezing inside the output calculation.
- Fixed selected NES instrument labels being fitted with the regular font and then overflowing when drawn bold. Fitting now uses the actual face, including wave settings, tabs and sample details.

Verified: 438 JVM core tests, 13 Linux core tests and six Android-host unit tests; native checks passed
(Game Boy 145, NES 336, VRC6 105, VRC7 115, N163 225, SID 151, OPL3 282, Mega Drive 501, WAV 738).
Independent FamiStudio exports cover N163 counts 1, 2, 4 and 8, comparing every frame's APU and N163 port writes.
All eight counts have demo and shortest-song WAV checks. The previous player outputs and 31 existing golden files
remain unchanged. The VRC7 held-copy regression failed on three wrong-patch assertions before the fix; the
Mega Drive feedback regression differed on 1,531 post-unmute samples before its fix and now matches exactly.
Unmuted NTSC/PAL Mega Drive reference WAVs remain byte-identical.

The final production-signed candidate passed all 18 existing Android instrumentation tests on Nova. A separate
temporary capture test produced 32 Game Boy views, all pixel-identical to the same-device 0.14.1 baseline;
the harness source and test package were removed. The factory `.uge` is byte-identical. Reserved-handheld
smoke tracks reported zero AudioFlinger underruns on Nova, DMG and Konkr.
Nova exercised thirteen-channel scrolling, wave audition and live sample editing; its edited NSF and FamiStudio
text exports through the document picker are byte-identical to desktop exports. Its edited no-fade WAV is
non-silent 48 kHz, 16-bit stereo PCM, 1,226,779 frames. DMG exercised the one-channel variant and wave presets.
Konkr exercised the eight-channel variant, shared-RAM overlap warnings and their clearing, and the corrected
selected-label bounds at 960×720. External `/ThorTunez` songs were untouched.

The actual desktop frontend played the eight-channel demo and exported the shortest valid one-row, one-tick
N163 song without a fade: 798 non-silent frames. One- and eight-channel NSFs also produced PCM in independent
libgme; this checks format/playback, not mix-level accuracy. These checks are not human listening or a comparison
with a physical Namco 163 cartridge.

Official 0.17.0 was published from `8ce6157` after the build, Deck, signing and AppImage attachment jobs passed.
Both published checksums and the production signing certificate were verified. Nova, DMG and Konkr received
versionCode 30 and cold-launched successfully; their actual app surfaces were inspected. The Thor received only
the verified APK in `Downloads/Thor Tracker`, without waking, installing or launching. The Deck's verified
AppImage replaced the target of the stable `~/thortracker-deck/thor-tracker` symlink, without launching there.
The published AppImage also played the eight-channel demo on this PC: row 29 after four seconds, peak 0.20598,
with a rendered Pattern view. APK SHA-256: `f8cf8b6ffa945774b6efd44f650a0c9e4ff67afbf7b6d410617b7f2fabe71910`;
AppImage: `7b6b771dee79aa7a0031ef857d03da53036151cfcdd6a94cb3722a10e92fcca0`.

## Unreleased: WonderSwan and WonderSwan Color — source slice, NOT verified

**Source integration update:** WonderSwan/SAA head `38026f1` and VSU/SAA candidate `9464a80`
are combined for **0.36.0/code 49**, project format 10. The current merge status and candidate
attribution are recorded at the top of this document; this section retains the original WonderSwan
contract and pending procedures. The parent owns verification. All Kotlin/Native linking
(including debug tests/executables) must run in CI, not locally; use disk-backed evidence under
`~/.local/state/thortracker`, not `/tmp`.

Branch `wonderswan-system` adds the **WonderSwan** (`"ws-wonderswan"`: CH1-CH4, CPU-fed voice samples on CH2, CH3
sweep, CH4 noise) and **WonderSwan Color** (`"ws-wonderswan-color"`: the same plus Sound DMA on CH2 and Hyper Voice)
systems — Thor Tracker's own player on a C port of ares's WonderSwan APU, VGM and WAV export — with the demos **Paper
Crane Run** (WonderSwan) and **Lantern Tide** (WonderSwan Color). It was written source-only: **nothing has been built,
compiled, generated, tested, run on a device or listened to** for WonderSwan. The original slice did
not bump the version; the combined integration now targets 0.36.0/code 49. The parent must run the
pending checks before calling this backend verified and record the observed results separately.

1. Generate the Kotlin constants, the native fixture and the golden images (pure Python 3), check a second run is
   byte-identical, then commit `core/src/commonMain/kotlin/org/thortracker/backend/WsPlayerImage.kt`,
   `app/src/test/cpp/ws_test_song.h` (per-tick and per-interrupt reference writes for 1500 ticks of four songs, the
   reference VGMs' hashes and loader corruptions) and `core/src/jvmTest/resources/ws-golden/{mono,color}.bin.gz`:

   ```sh
   python3 tools/audio/prepare_ws.py
   python3 tools/audio/ws/ws_golden.py
   python3 tools/audio/prepare_ws.py && python3 tools/audio/ws/ws_golden.py && git status --short   # no new changes
   ```

   `prepare_ws.py` fails before writing if the C sources and the Python reference disagree on any shared number,
   `WSP_PERIOD_Q16` or `WSP_SINE`; it also asserts that the reference VGMs of `ws_song`, `wsc_vgm` and `ws_tone` close
   their loops within 45283 ticks.
2. The usual full run (Kotlin: `WsGoldenTest`, `WsModelTest`, `WsEditorTest` and every existing test), the app and
   Deck builds (CMake through Gradle; cinterop `decknative` with `ws_export.h`), the sample exports
   (`paper-crane-run.thor`, `lantern-tide.thor`, `ws-demo.wsimg`, `wsc-demo.wsimg`, `ws-shortest.wsimg`,
   `wsc-shortest.wsimg`) and the native host test (`ws_machine_test.c` with the Kotlin images, and `wav_render_test.c`
   with the two demos and the two shortest songs):

   ```sh
   nice -n 10 tools/build.sh --max-workers=1 :core:jvmTest :app:testDebugUnitTest :app:exportSamples
   nice -n 10 tools/build.sh --max-workers=1 :app:assembleRelease :app:assembleAndroidTest
   # EVIDENCE is an existing SSD-backed directory under ~/.local/state/thortracker.
   # Run native commands in the parent's bounded, serialized host-C scope.
   tools/audio/run_native_host_test.sh "$EVIDENCE"
   python3 tools/audio/ws/ws_reference_check.py "$EVIDENCE"
   python3 tools/audio/ws/ws_reference_check.py "$EVIDENCE" --vgm2wav /path/to/libvgm/bin/vgm2wav   # optional
   tools/audio/render_ws_image.sh app/build/samples/ws-demo.wsimg "$EVIDENCE/paper-crane-run.wav" 90 --vgm "$EVIDENCE/paper-crane-run.vgm"
   tools/audio/render_ws_image.sh app/build/samples/wsc-demo.wsimg "$EVIDENCE/lantern-tide.wav" 90
   ```

   Run `:core:linuxX64Test` and `:deck:linkReleaseExecutableLinuxX64` in CI only, then exercise
   the downloaded optimized AppImage; no local debug/test Kotlin/Native link is permitted.

   Expected from the specification (not yet observed): A-4 = N 218 = 440.37 Hz (+1.44 cents), every note G-1..C-6
   within 6.4 cents and the worst 23.3 cents (F#-7); the eight LFSR sequence lengths 32767, 1953, 254, 217, 73, 63, 42,
   28 (already reproduced by arithmetic from the formula); the APU's closed-form blocks equal to its per-cycle loop; the
   engine's tick and timer-interrupt writes (with cycles) and DMA transfers equal `ws_ref.py`'s for 1500 ticks of each
   reference song; the VGMs byte-equal to the reference's and their loops repeating exactly over three passes; seeks
   bit-identical to straight play. The native test's thresholds (A-4 within 0.25 Hz, `ws_reference_check.py` 0.3 Hz,
   silence rms < 0.0005, non-silence rms > 0.005 / 0.002, release ends in 1-10 ticks, one-shots in 2, the 755-tick
   release cap, and the `WsLane` size `_Static_assert` written for arm64/x86_64 layouts) may need tuning once measured.
   Also the release-standard checks: Game Boy views pixel-identical to the baseline, every existing golden and sample
   export unchanged, and every shared-registry `render_*_image.sh` still links all engines (they compile
   the WonderSwan and VSU sources because `chip_engine.c` lists their ops; standalone SMS is the exception).
3. Listen: both demos and the reference songs on headphones and (song setting) speaker output; the CH3 sweep, the noise
   taps, the CPU-fed voice drums at 12000 / R Hz, the Color's DMA loops, hold, backwards and stereo Hyper Voice; the
   proposed voice / Hyper Voice level mapping (docs/BACKENDS.md, open questions). The Paper Crane Run VGM in an
   independent player (libvgm: its WonderSwan core differs in noise and voice details; pitch and timing should agree).
4. Reserved handhelds only (Nova, DMG, Konkr; the Thor gets artifacts only): New blank song / Load demo → WonderSwan →
   WonderSwan / WonderSwan Color; demo playback (no underruns; the tick cost on the Konkr incl. a 4528-tick seek), the
   Instrument pages per kind and feed, the Wave view, the starter kit and a `.wav` import (stereo kept on the Color
   only), audition on every lane (lane-fit refusals), the Song view's Output / speaker shift / voice timer rows and
   the Pattern inspector's shared-state lines (timer, shift, DMA owner), VGM (refused with Hyper Voice notes) and WAV
   exports through the document picker.

Preserved untracked reference material: `.ws-reference/` (fetched hardware sources: ares, Mesen 2,
Mednafen 1.32.1 `wswan`, libvgm, the VGM 1.71 text), `.ws-kotlin-ref/` and
`.ws-native-scratch/` (the slices' read-only copies and scratch fragments).

## 0.16.0 NES + Konami VRC7

- **New blank song / Load demo → NES… → NES + VRC7** adds six two-operator FM channels to the NES's five channels. The demo is **Lantern Point**.
- The shared instrument bank offers the VRC7's 15 built-in patches and editable custom patches. The editor exposes both operators, feedback, modulation level, envelopes, key scaling, tremolo and vibrato. All six FM channels share one physical custom patch; the song check warns about conflicting held custom sounds.
- Playback, audition, live edits, NSF, FamiStudio text and WAV export use the pinned FamiStudio 4.5.3 VRC7 driver and emu2413 in VRC7 mode.
- FM notes use true pitch: A-4 is approximately 440 Hz, with a C-1–B-7 range. The FamiStudio FM note index is one octave higher than its 2A03 index for the same pitch; text export and audition use that conversion too.
- Corrected two FamiStudio 4.5.3 export behaviors: restoring the shared custom patch at loop boundaries (including delayed final writers), and restoring it mid-song without retriggering an explicitly no-attack note's held envelope. These follow the editor's musical behavior. An independently exported adversarial golden demonstrates both differences; FamiStudio 4.5.3 can reintroduce them when re-exporting our text. Thor Tracker's own NSF, WAV and playback use the corrections.
- Shared emu2413 table initialization is serialized when playback and background WAV rendering create instances concurrently; ticking/rendering never takes that lock.

Verified: 417 JVM core tests, 13 Linux core tests and six Android-host unit tests; native checks passed (Game Boy 145, NES 336, VRC6 105, VRC7 111, SID 151, OPL3 282, Mega Drive 500, WAV 290). The ordinary VRC7 parity goldens compare real 6502 register traces against independent FamiStudio exports, ignoring only writes of unchanged custom-patch bytes. The separate `vrc7_patch_restore` fixture explicitly checks the two intentional export divergences rather than claiming trace equality: upstream retriggers the mid-song note and restarts its volume at 15 while ours holds 6; upstream keeps the other patch on repeated loops while ours restores the lead. All 28 pre-existing golden files remain byte-identical. Additional real-driver regressions cover true pitch and delayed final writers. Existing Game Boy factory `.uge` bytes are unchanged.

The production-signed candidate passed the 18 existing Android instrumentation tests plus a temporary render capture on Nova. All 32 Game Boy views are pixel-identical to the same-device 0.14.1 baseline. The automated audio suite recorded one xrun, no driver faults and a 0.508 ms maximum callback; sustained VRC7 playback smoke tracks reported zero underruns on Nova, DMG and Konkr.

Reserved-handheld checks exercised eleven-channel scrolling, custom/preset FM editors and audition controls. Nova's NSF and FamiStudio-text exports through Android's document picker are byte-identical to desktop exports; its no-fade WAV is non-silent 48 kHz, 16-bit stereo PCM, 1,226,779 frames. Temporary test APKs, captures and exports were removed; `/ThorTunez` was untouched.

The actual desktop frontend played Lantern Point and exported the shortest valid one-row, one-tick FM song without a fade: 798 non-silent frames. The exported NSF also opened as eleven voices and produced PCM in independent libgme; this is a format/playback check, not mix-level validation. These checks are not human listening or comparison with a physical VRC7 cartridge.

The published 0.16.0 APK and AppImage passed their checksum manifests; the APK has production certificate
`d12064692cbaa5c2feecf3cf5a2d3719a088e7bafe1ffa55e1f00aeecafed469`, version code 29. The official AppImage played
Lantern Point (peak 0.19839096). The official APK was installed and cold-launch checked on Nova, DMG and Konkr;
the Thor received only the APK in Downloads, and the Deck's stable `thor-tracker` symlink was updated without
launching or changing Steam settings.

## 0.15.0 NES + Konami VRC6

- **New blank song / Load demo → NES… → NES + VRC6** adds two eight-duty pulse channels and a sawtooth to the NES's five channels. The demo is **Saw Sunrise**.
- The shared 64-slot bank distinguishes 2A03 and VRC6 sounds. VRC6 instruments expose their eight pulse duties and Full/Half/Quarter saw gain, with the chip's accumulator distortion at high Full-gain levels.
- Playback, audition, live edits, NSF, FamiStudio text and WAV export use the VRC6 build of the pinned FamiStudio 4.5.3 driver.
- Fixed live envelope edits selecting an identical envelope from a different instrument instead of the edited slot.
- Fixed single-effect Pattern lanes overflowing into their neighbours on narrow screens: readable minimum widths use the existing horizontal scrolling and touch hit testing. Four-channel Game Boy layouts remain unchanged.

Verified: 410 JVM core tests, 13 Linux core tests and six Android-host unit tests; native checks passed (Game Boy 145, NES 336, VRC6 105, SID 151, OPL3 282, Mega Drive 500, WAV 249). Two VRC6 golden songs compare every frame's register writes against FamiStudio's independent export, including repeat passes; the 14 pre-existing NES golden files remain byte-identical. A generated VRC6 NSF also played through libgme as eight voices with non-silent PCM.

The 18 existing Android instrumentation tests passed against the production-signed candidate on Nova. A temporary capture harness rendered 32 Game Boy views: all are pixel-identical to a 0.14.1 baseline captured on the same Nova, including after the narrow-lane fix. The Game Boy factory `.uge` remains byte-identical. One xrun, no driver faults and a 0.638 ms maximum callback were recorded during the final automated audio suite; sustained VRC6 playback smoke tracks reported zero underruns on Nova, DMG and Konkr.

Reserved-handheld checks exercised family selection, eight-channel playback, saw/pulse editors and X audition. Nova's NSF and FamiStudio-text exports through Android's document picker are byte-identical to desktop sample exports; its WAV is non-silent 48 kHz, 16-bit stereo PCM (1,466,779 frames). On Konkr's 960×720 display, the reproduced lane overlap is gone, L1/R1 reaches the final saw channel by scrolling, and touching a scrolled effect parameter selects that field. Temporary test APKs/captures/exports were removed; `/ThorTunez` was untouched.

The actual desktop frontend played the demo and exported the shortest valid one-row, one-tick VRC6 song without a fade: 798 non-silent frames, rather than running to the safety cap. These are automated signal and UI checks, not human listening or a test on a physical VRC6 cartridge.
## 0.14.1 Right-stick Pattern shortcuts

- With the Pattern grid focused, flick the **right stick Up twice** to reach the first row, or **Down twice** to reach the last row. Return to neutral between flicks; each flick and the gap can be up to 300 ms.
- Single flicks and a held stick do not jump. Menus, the toolbar, the Pattern panel and other views block the gesture. Changing focus or controllers cancels a pending flick. Left-stick and D-pad navigation are unchanged.
- Available on Android and Steam Deck; the L2/R2 bindings are unchanged.

Verified: 404 JVM core tests, 13 Linux core tests and six Android-host unit tests passed; Android and Deck builds succeeded. Six actual Deck frontend scenarios covered both jumps, single/held input, modal exclusion and view-change cancellation. The packaged release AppImage also passed both jumps and modal exclusion.

On Nova, a temporary Android `uinput` gamepad exercised EventHub/InputReader through the activity: Down jumped to row `3F`, Up to `00`; single/held input, menus, Song, toolbar and Pattern panel did not jump; left-stick and D-pad single-row movement remained intact. The same signed 0.14.1 candidate passed both jumps on DMG (Mega Drive) and Konkr (OPL3). Temporary virtual devices/helpers were removed and handhelds returned to Home/sleep; external songs were untouched. Physical-stick ergonomics and threshold tuning still need human playtesting.

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
