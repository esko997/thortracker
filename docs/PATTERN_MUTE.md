# Pattern-local channel mute: deferred feasibility record

**User decision, 2026-11-10: defer pattern mute.** Do not implement or advertise the feature. Documents, UI, playback and exports remain unchanged. The user chose deferral after reviewing the native-export incompatibilities below; no reduced preview/WAV-only or constrained-driver feature was approved.

The intended unit is **one channel/column within one pattern**. All order entries reusing that
pattern share its mute state. This is neither global channel mute nor visual column compression;
the clarification does not reopen implementation or settle tail/shared-resource semantics.

Source inspection used the 0.38.0 candidate (`73b2597`). No builds, tests, renderers, reference tools or devices were run for this investigation. This document records the requested first-checkpoint findings and possible future contracts, not an implementation commitment.

## Reviewed choices and selected deferral

A saved pattern mute must not silently disappear in export. The existing audition-borrowing mute API does **not** establish a uniform document-mute contract, and a native register log is not an export of an emulator's output mask.

The user was offered precise preview/WAV mute with explicit native-export limits, or deferral, and
**selected deferral**. The list below also retains a constrained driver-command alternative considered
during engineering research; it was not presented as an approved implementation choice.

1. **Precise preview/WAV mute, explicit native-export refusals.** Preserve the running player/chip state and gate only the selected lane's audible contribution. Support a native format only after its implementation proves the same semantics; otherwise refuse that export with the pattern, lane, format and reason. This changes the original all-native-export acceptance requirement and needs approval. It is not permission to claim every VGM is compatible.
2. **Constrained driver-command mute.** Define an entry cut and suppression/routing policy representable by the destination driver. Apply that same policy to preview, WAV and native export. Explicitly reject songs or lanes for which effects, shared resources, capacity or tail behavior make it unrepresentable. This is different from an advancing hidden voice; its exact tail and shared-modulation semantics must be approved. Do not silently substitute it for option 1.
3. **Defer pattern mute — selected.** Leave documents, playback and exports unchanged. Reopening implementation requires a new explicit decision on the sound and native-export contract.

Extending a playback driver can help an executable format, but cannot make stock hUGETracker, FamiStudio text or GoatTracker understand new document semantics. A custom driver/file variant would be a separate compatibility decision, not a transparent fix.

## Possible future document and editing contract

These proposals are recorded for a future review only. They are not approved changes; any eventual project version must be selected against the format current at that time:

- Add `Pattern.mutedChannels`, an ascending, unique list of zero-based channel indices; empty means unmuted. Validate every entry against that song's channel count, including unused/parked patterns. Reject duplicates, descending order, negative/out-of-range indices and wrong JSON types rather than normalizing malformed files.
- At the inspected baseline, the required next project format would be 11 (current: 10); old documents would default to no mute. Reject the new field under an older declared version, matching existing version-gated fields. This is a project-format change, not an app release-version change. Current boundaries: `core/src/commonMain/kotlin/org/thortracker/model/Song.kt:95-112` and `ProjectCodec.kt:20-66,134-155`.
- Preserve the original notes, instruments, every effect column, hidden rows and pattern length. A toggle is one ordinary document edit, participates in Undo/Redo and survives save/reopen. It is not a workspace flag, host-monitor setting, arrangement-occurrence flag or mixer ceiling.
- Pattern clones preserve the property; newly blank patterns start unmuted. Reusing a pattern reuses its mute. Parking an order does not clear it, and playing that parked pattern alone still applies it.
- Conversion maps mute indices through the same lane mapping as the cells, including unused patterns. Report loss of a muted source lane when the existing conversion drops that lane; never retain stale source indices. Current conversion explicitly preserves unused patterns/hidden rows but rebuilds the channel dimension (`conversion/Conversion.kt:28-50,95-100`, under the same Kotlin package root).
- Pattern channel headers visibly distinguish muted lanes. Provide a menu item and rebindable action for the selected pattern/channel; do not hide the data or require a new touch-only workflow.
- Explicit audition stays available without editing the document property. Borrowing and document mute are separate reasons: releasing audition must not accidentally unmute the song lane.

## Entry, tail and transport proposal for option 1

This is a proposed contract, not current behavior:

- At each actual pattern entry, apply that pattern's mask **before its first audible tick**. Entering a muted lane suppresses an existing tail immediately. Its rows and every local/global effect still execute, and clocks, FM feedback, envelopes, shared noise/modulation and DMA/sample progression continue.
- On leaving the muted pattern, reveal the state the lane would have reached while hidden, including a still-active tail. Do not retrigger, reset an envelope or wait for the next note. This tail-resumption choice must be explicitly accepted; it differs from an entry cut.
- A seek, mid-pattern start, song loop, pattern loop, order jump, repeated pattern and live edit use the same mask and tick ordering. An edit takes effect at the next safe tick boundary. A repeated numeric position is not sufficient to detect entry; preserve the `row_serial`/driver timing contract where provided.
- Carry the property through each validated playback image and machine/export path, not a UI-only side table. WAV must consume the same semantics without relying on a foreground controller. Audition must bypass the document mask on its own audition machine.
- Mute is applied at the backend's defined lane-contribution boundary, not by multiplying the final stereo PCM. A shared/nonlinear mixer may retain a transient or shared filter tail after a lane stops contributing; do not promise instant all-zero output from a previously energized shared filter. Define and test that boundary per backend.
- Native feasibility requires equivalence of audible behavior and relevant continuing state, not merely the ability to write a zero volume. Changing an FM carrier's total level can also change a feedback path; stopping a channel can change shared clock/modulation behavior.

`app/src/main/cpp/chip_engine.h:145-147` currently describes audition borrowing with next-note resumption, not this contract. Its position and entry rules are at `:77-81,155-160`.

## Format-by-format source evidence

Paths below are relative to the repository. Kotlin backend paths abbreviate `core/src/commonMain/kotlin/org/thortracker/backend/` as **B**, and C paths abbreviate `app/src/main/cpp/` as **C**. Categories describe the current route, not an untested claim that a hardware transformation is impossible forever.

### Tracker data and executable formats

| Format / coverage | Current route and evidence | Consequence for exact output-mask semantics |
| --- | --- | --- |
| Game Boy `.uge` v6 | **B** `UgeExporter.kt:23-57` serializes `HugeLowering` tracks, unused volume column and empty routines. **B** `HugeLowering.kt:87-96` has one effect per row; `Cell.CUT` replaces that effect with E00. `system/gb/HugeRules.kt:27,72-94` defines 15 instrument slots per bank and global master-volume, panning, jump, break and speed commands. | **Not represented by the current route.** An added entry cut cannot share a cell with Fxx/Bxx/Dxx; relocating a command needs available capacity and must preserve evaluation order. NR51 routing is global, must preserve other lanes and later panning writes, and also needs an effect slot when none is already present. Silent aliases consume finite banks and do not generally silence a preceding tail. Do not promise universal stock `.uge` support or remove effects to make space. |
| NES `.nsf`, every expansion/layout | **B** `NsfExporter.kt:5-16,23-35` embeds FamiStudio player bytes, data and samples. **C** `nes/nes_machine.c:1435-1471` instead applies host core masks separately for APU, FDS, N163, MMC5, VRC6, S5B, EPSM and VRC7. | **Not represented by the current route.** The core masks are not NSF player data. Per-expansion hardware/driver transformations need individual proofs; a blanket volume-zero substitution is not an audit of triangle, DPCM, envelopes or shared expansion resources. Preserve the existing MMC5 PCM export refusal rather than pretending mute adds that missing player. |
| NES FamiStudio `.txt` | **B** `FamiStudioText.kt:20-45,91-106,142-163` describes instruments, notes and per-order patterns, not Thor output masks. Existing unsupported EPSM manual-period and MMC5 PCM cases are already refused. | **Not represented by the current route.** Patching the embedded NSF player alone cannot change stock FamiStudio text/import/playback semantics. Any supported lowering must be common in meaning to text, NSF and preview, with honest capacity checks. |
| C64 `.sid`, `.prg` | **B** `SidExport.kt:6-18,21-33,36-59,102-112` exports the GoatTracker playroutine and lowered data. **C** `sid/sid_machine.c:632-635` uses reSID's `set_voice_mask`, a separate emulator operation. | **Not represented by the current route.** The emulator mask is not a SID register or embedded player command. Key-off is a release, not immediate output gating; changing waveform/envelope or using global volume is not automatically equivalent for sync, ring modulation and shared filtering. A custom playroutine would need a separate proved contract. |
| GoatTracker `.sng` | **B** `SidExport.kt:115` exports `GtSong.sng()`. **B** `GtSong.kt:145-151,206-229` constructs voice patterns with notes/instruments/commands and enforces finite tables/patterns. | **Not represented by the current route.** A custom PSID player does not make the stock `.sng` route equivalent. Duplicating silent instrument/table programs is capacity-sensitive and changes continuing-state semantics unless proved otherwise. |
| Master System Banjo `.asm`, `.s` | `system/sms/SmsExport.kt:20-24,49-63` emits the same Banjo data as playback for WLA-DX or SDAS. **C** `sms/sms_machine.h:82-83` documents a separate chip-output mask with driver/chips still running. | **Not represented by the current route.** A mask in the host or VGM renderer is absent from the exported Banjo source. SN, FM, rhythm and shared custom-patch cases all require coverage; do not advertise only the easy PSG layout as complete. |
| Mega Drive `.xgm`, compiled `.xgc` | `system/ym2612/YmSystem.kt:75-78` registers these alongside VGM. **C** `ym/ym_machine.c:691-705` gates FM/PSG core outputs, but stops a muted PCM lane through the driver. | **No uniform existing mute representation.** FM/PSG core masks and PCM stop semantics differ. A hardware routing/register transformation must also reach XGM2 commands and preserve FM3, feedback, DAC ownership and PCM progression under the chosen contract. |

The Game Boy borrowing implementation is particularly important: `third_party/hUGEDriver-6.1.3/rgbds/hUGEDriver.asm:257-280` explicitly cuts the note and leaves muted channels alone for other uses. It is not an advancing hidden-voice output gate.

### VGM register streams

All existing VGM routes below emit register writes and timing, not host mute metadata. **None is automatically approved for option 1 by this inspection.** A route that can use a hardware output gate may become supported after equivalence tests; if not, the export must refuse affected songs under option 1. Do not replace output masks with total-level changes without checking feedback, shared state and tails.

| Backend / variants | Current VGM writer evidence (**C**) | Existing mute boundary / proof obligation |
| --- | --- | --- |
| AY: ZX128, MSX, Atari ST, CPC | `ay/ay_vgm.c:30-31,75-76` writes player registers. | `ay/ay_machine.h:51-52`: leave channel out of mix, player/chip run. Any amplitude/routing rewrite must retain shared noise/envelope progression and restore the correct envelope/fixed-volume mode. |
| OPL3 | `opl3/opl3_vgm.c:24-27,94-105` emits player writes, including separate key-off phase. | `opl3/opl3_machine.h:48-49`: voice output enables, player untouched. Hardware output-enable gating is a candidate, **not established here**; paired voices and rhythm ownership must be included. |
| OPM / YM2151 | `opm/opm_vgm.c:31-32,93-106` emits player writes. | `opm/opm_machine.h:50-51`: channel excluded from sum without changing registers. Output-routing gating is a candidate requiring proof; preserve all FM computation, feedback and shared modulation. |
| OPN / YM2203, OPNA / YM2608 | `opn/opn_vgm.c:41-44,136-147` emits player writes. | `opn/opn_ymfm.h:4-6` requires muted parts' state, including FM feedback, to advance exactly. Audit FM, SSG, rhythm and ADPCM-B separately; the two chips do not have identical output controls. |
| Mega Drive / YM2612 + PSG + PCM | `ym/ym_vgm.c:145-165,195-198` starts a machine and records chip writes. | `ym/ym_machine.c:691-705`: FM/PSG masks do not become writes; PCM mute stops playback. Must agree with both XGM2 formats, not just WAV. |
| Master System / Game Gear, all Banjo layouts | `sms/sms_vgm.c:110-122,144-151` records actual driver writes. | `sms/sms_machine.h:82-83`: driver/chips continue behind output mask. Must agree with the exported `.asm`/`.s`, including FM/rhythm/shared patch behavior. |
| PC Engine | `pce/pce_vgm.c:35-36,125-128` emits player/timer writes. | `pce/pce_machine.h:39-40,62-65`: current machine mute masks R4 volume, while traces are explicitly **before** masking and do not trace toggles. Player-log export therefore needs deliberate integration, including DDA/timer and modulation behavior; it does not inherit machine mute. |
| POKEY | `pokey/pokey_vgm.c:116-119` advances player/chip and emits batches. | `pokey/pokey_machine.h:46-47`: a tracker lane can hold several physical channels. Prove joined/high-pass/clock-source behavior and ownership, not one bit per assumed physical channel. |
| SAA1099: SAM and CMS | `saa/saa_vgm.c:28-31,89-95` emits player writes. | `saa/saa_machine.h:54-55`: channel excluded from output while cores run. Preserve shared envelope/noise clock sources and dual-chip CMS mapping. |
| SCC and SCC+ | `scc/scc_vgm.c:33-34,91-96` emits player writes. | `scc/scc_machine.h:50-51`: output exclusion only. Prove volume/enable rewrites without freezing oscillator state or changing shared wave RAM behavior. |
| Virtual Boy VSU | `vsu/vsu_vgm.c:68-70` advances player/chip and emits batches. | `vsu/vsu_machine.h:55-56`: contribution removed from mixer sum while chip runs. Do not substitute channel disable without checking modulation, envelopes and continuing phase. |
| WonderSwan and WonderSwan Color | `ws/ws_vgm.c:114-120,225-226` advances player and timed events. | `ws/ws_machine.h:63-65`: output computed then discarded, no other changes. Cover wave, voice, DMA and Hyper Voice ownership/progression where available. |

### WAV and systems without native music export

WAV uses the `ChipEngineOps` machine, so a document mute must reach the machine when rendering offline; a host-monitor toggle is insufficient. Inspect and update the generic `app/src/main/cpp/chip_wav.c` path alongside each machine rather than assuming UI playback tests prove export behavior.

TIA NTSC/PAL and VIC-20/C16 currently expose project/WAV only: `system/tia/TiaSystem.kt:62-64` and `system/cbm/CbmSystem.kt:72-73`. They still need full pattern-mute behavior, image validation and shared-state tests, but this feature must not invent a native export promise for them.

## Acceptance work after a policy is approved

This is the verification plan for a future implementation, **not tests already written or run**:

1. Model/codec/editor: malformed masks, old-format defaults/new-field rejection, save/reopen, Undo/Redo, cloning, conversion lane remapping/loss reporting, hidden rows and parked/unused patterns. Assert original cells remain byte-for-byte/structurally unchanged.
2. Transport: A (audible held note) → B (same lane muted, with timing/control effects) → C (unmuted empty row) → B reused; seek directly into B and C, loop B alone, jump into a nonzero row, and live-toggle at a tick boundary. C distinguishes resumed hidden tails from irreversible cuts. Include one-row speed-1 loops and the YM driver's delayed position reporting.
3. Native/WAV: for each supported format render the **actual exported file** through its real/reference consumer and compare against live/offline behavior. Inspect tick/control trajectories as well as sound. For a refused export assert a useful pattern/lane/format error and no seemingly successful output file. Include full instrument/table banks and entry rows with occupied effects; no silent aliases beyond capacity.
4. Chip state: compare muted versus unmuted register/player progression where the chosen semantics require equality; explicitly test FM feedback, SID sync/ring/filter, AY/SAA shared resources, SCC wave sharing, POKEY joined/clock lanes, OPL3 pairs/rhythm and every PCM/DMA owner. Nonzero background PCM is not proof that the selected audition was accepted.
5. Frontend: mute a header's lane; see data retained and header state change; save/reopen; Undo/Redo; reuse/park/clone pattern; invoke menu and rebound key/gamepad action. Audition the muted lane with transport both stopped and playing, verify accepted audition, release it and verify the document mute is still active.
6. Defaults: existing unmuted golden outputs and musical rendering remain unchanged. The runtime path must not allocate or copy per tick merely to apply a mask.

Parent-owned future verification entrypoints include `tools/build.sh :core:jvmTest testDebugUnitTest`, `tools/audio/run_native_host_test.sh` and the existing per-backend renderer/reference-check scripts under `tools/audio/`. Select cases for the approved implementation; serialize jobs and keep Kotlin/Native linking in CI. This document-only change needs no audio/build verification.
