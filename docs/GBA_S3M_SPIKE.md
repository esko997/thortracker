# GBA combined S3M export: measured feasibility

2026-11-12. **Research only. No PSG backend or production exporter implemented.**

## Decision

**Conditional GO for a deliberately restricted GBT + Maxmod export profile; NO-GO as a lossless extension/replacement of the current XM backend.** Review the restrictions before implementation. The existing eight-channel XM path remains independently useful and unchanged.

There is an existing community workflow, not a format we would have to invent: compose one S3M, split it with GBT Player's `s3msplit`, convert the PSG portion with `s3m2gbt`, and convert the PCM portion with `mmutil`. A normal GBA application links GBT Player and Maxmod and advances them together. The spike built and ran **four real PSG channels plus eight PCM voices using unmodified player libraries**. No dependency on the user's game or Butano was required.

This is a documented upstream convention, **not a universal GBA music standard**. Existing tools solve the file conversion and playback building blocks; they do not provide a complete, robust combined transport with verified audible synchronization.

The user's interoperability requirement supersedes the custom companion/bundle direction in the [earlier broad spike](GBA_PSG_SPIKE.md): no Thor-specific music format or runtime fork, and the user's game is a possible consumer, not the API specification.

## What was actually exercised

Pinned components:

| Component | Revision / version |
| --- | --- |
| GBT Player, splitter, converter and combined example | `130e48662da2a91140a10d5af3934df90792de21` |
| devkitPro Maxmod | 1.0.15, `ce8adb2a6bf1f29ec203b6c3410c1ddf8e270811` |
| mmutil | 1.10.1, `8b0792e45852b58fda4def4d263e246d3a73662d` |
| SDK container | `devkitpro/devkitarm@sha256:116afba8df8453961de2936ffab20dd441edf4d682856c1ec8b0e53d7ed0bbf5` |
| mGBA libretro | `0.11-212-7a12d6d`, core SHA-256 `c840f305825d07c9bec7a6ec18841073c4b539fd234f73623474f97c507f1cdc` |

First, the unchanged upstream combined example was converted, linked and run for 600 emulator frames. Its displayed PCM/PSG positions agreed. Then targeted S3Ms were passed through the same unmodified converters and libraries, with a standalone observation harness recording transport state, active hardware/mixer channels, audio-call CPU cycles and stereo PCM.

Seventeen source fixtures were converted: twelve accepted, five rejected as expected. One accepted fixture was additionally run with experimental application-only latency compensation. No game source, tracker production source or player library was changed. This was headless emulator execution and signal analysis, **not physical-GBA listening or game-load verification**.

### Runtime results with the upstream synchronization algorithm

The algorithm runs `mmVBlank()` and `mmFrame()`, then advances GBT one to five times until order/row/tick match. Measurements below compare positions after that catch-up, while both players are active.

| Probe | Frames | Result |
| --- | ---: | --- |
| Original template, instrumented | 3,600 | Zero position mismatches |
| Four PSG + eight PCM voices | 3,600 | Zero mismatches; all twelve paths active simultaneously in 2,676 frames |
| Paired onsets, finite | 1,800 | Zero mismatches while both active; GBT remains playing after Maxmod stops |
| Paired onsets, looping | 36,000, approximately 603 seconds | Zero position mismatches; 378 measured onset pairs |
| Mirrored speed changes | 1,800 | Zero mismatches |
| Mirrored order jump B | 1,800 | Zero mismatches |
| Mirrored row break C | 1,800 | Zero mismatches while both active |
| B01 + C16 from order 0 | 1,800 | Zero mismatches, but this coincides with the next order and is not a general B+C proof |
| B00 + C16 from order 0 | 1,800 | **1,443 mismatched frames**; catch-up reaches its five-update bound |
| Natural restart after speed changes | 1,800 | Zero final-position mismatches, but up to five catch-up updates rather than the usual two |
| Finite song ending on a held PSG note | 1,800 | **PSG remains audible after PCM transport ends**; final PSG status bit 1, stereo RMS approximately 0.0348 in the last second |
| Wrong-class instrument on pulse lane | 1,800 | Accepted and played; converter does not reject the incompatible instrument assignment |

The dense fixture's largest observed audio-call cost was **52,673 / 280,896 cycles, or 18.75% of one GBA frame**, with a 16 kHz, eight-voice mixer. That is a measured standalone fixture, not a worst-case game CPU guarantee, not a sample/SFX budget, and not a measurement of every possible pattern/effect combination.

### Audible timing is a separate result

The paired-onset fixture pans PSG left and PCM right. Analysis uses 64-sample RMS windows at 65,536 Hz, approximately 0.977 ms per window, with a quiet interval required before another onset. Thresholds 0.01, 0.02 and 0.03 produced the same overall range and median in the long recording:

- PSG leads PCM by **31.25–49.80 ms**, median **41.02 ms**, over 378 onset pairs.
- At threshold 0.02, the first fifty pairs' median lead is 41.50 ms and the last fifty pairs' is 41.02 ms. This fixture shows a bounded offset, not accumulating drift.
- These are waveform/filter/threshold-dependent audio observations, not exact register-write timestamps. Row/tick agreement alone would have missed the issue.

A second, throwaway application-glue experiment retained a two-frame history of Maxmod positions and advanced GBT toward the older target. **Neither library nor the converted music changed.** Over thirty seconds / nineteen onset pairs, PSG led PCM by **0–15.63 ms**, median **6.84 ms** at threshold 0.02. The mismatch counter against the delayed target stayed zero; current PCM and PSG counters intentionally no longer match.

This establishes a plausible **no-runtime-fork, frame-quantized** timing option. It is not sample alignment, a fully validated transport, or a ten-minute validation of the compensation variant. It has not been checked under physical hardware/game load, pause/resume, seeking or missed updates. It must not be presented as production-ready integration code.

## Proposed conservative export contract

These are proposed restrictions, not new supported Thor Tracker features.

### One composition, existing files

- One project and shared pattern/order timeline, with four typed PSG lanes plus eight PCM lanes.
- One combined S3M as the interoperable composition artifact. Use the upstream split/conversion steps to produce the normal GBT C data and Maxmod soundbank for a game.
- No separate hand-maintained PSG song and no Thor-specific manifest/runtime needed for playback. Generated files still need the normal application initialization, frame servicing and coordinated transport glue.
- Preserve existing XM projects and their exporter. A mixed S3M profile must not silently flatten or discard unsupported existing instrument/effect semantics.
- In the exported S3M, channels 1–4 are PSG and channels 5–12 PCM. Samples 1–31 are reserved for PSG; PCM begins at 32. A 99-sample S3M leaves **68 PCM sample slots**, not the current model's full 128-sample bank.

### Timing and flow

- **Initial BPM exactly 150.** The PSG converter rejects other initial BPMs and T tempo effects. Speed A changes are possible; this is a software-profile restriction, not a GBA hardware limit.
- Start with conservative speed 1–31, one effect per lane/row and deterministic, mirrored global commands.
- The splitter **does not duplicate global commands**. The exporter must place A/B/C commands in both domains, or reject an effect-slot collision. Do not promise arbitrary dense effect rows can be converted without loss.
- Allow separately validated B jumps or C breaks; **reject combined B+C on one row**. Maxmod combines the target order and row; GBT's commands overwrite parts of that state in processing order.
- Reject PSG pattern-loop, note-delay and pattern-delay commands; do not allow a PCM-only global flow change to silently take the PSG arrangement elsewhere.
- Prefer explicit, mirrored loop jumps and explicit loop-entry speed/pan state. GBT's natural restart resets state differently from Maxmod. A passing post-frame position comparison does not prove identical intermediate event timing.
- The game-facing example needs coordinated stop/restart and end-of-song handling. The upstream catch-up loop alone can leave a held PSG note sounding indefinitely. Buffered PCM complicates where the final stop belongs.

### PSG instruments and effects

| Lane | Existing GBT representation / restriction |
| --- | --- |
| Pulse 1 and 2 | Instrument slots 1–4 select duty: 25%, 50%, 75%, 12.5%; 16 volume levels; hardware envelope effects. No exposed channel-1 pitch-sweep interface |
| Wave | Slots 8–15, eight custom waves; 32 or 64 samples reduced to four-bit values; discrete hardware output levels |
| Noise | Slots 16–31 select fixed noise types; the note pitch is ignored, so this is not arbitrary chromatic noise |

Pitched notes must lie in the converter's raw S3M range `0x20..0x7B` with valid semitone nibbles: 72 pitches. Tracker octave labels vary; validate encoded pitches, not display names alone. Arpeggio offsets must also remain inside the frequency table.

The useful local subset includes tonal arpeggio/vibrato, discrete left/both/right routing, volume, pulse/noise hardware envelope and supported delayed cuts. Noise ignores tonal arpeggio/vibrato; cuts require `1 <= cut_tick < speed`. There is no blanket parity with the existing Game Boy editor or all hardware capabilities.

Exporter validation is essential: the converter can accept wrong-class instruments and mask them into another duty/wave selection, rather than fail. Instrument-only pulse/wave events also do not provide general XM-style instrument switching. All eight custom wave definitions need valid data when using `--instruments`.

### PCM instruments: the largest compatibility cost

The PCM portion is **S3M played by Maxmod**, not the current XM song with four extra lanes attached.

| Current capability | S3M profile consequence |
| --- | --- |
| Mono eight-bit PCM and forward loops | Representable |
| Root pitch / fine tuning | Map to C2SPD, subject to converter/runtime frequency quantization |
| Instrument multisample keymaps | Not native; require validated per-note sample resolution or restriction to one sample per instrument |
| Volume/pan envelopes, sustain and fadeout | Not native; reject rather than silently remove them |
| Sample-default pan | Requires explicit event lowering; may compete with the row's effect slot |
| Note release | S3M's cut is not XM envelope release |
| XM linear-period effects / effect memory | Cannot assume S3M Amiga-period commands have identical pitch-slide/portamento behavior |
| Short patterns / selected loop order | Need explicit validated break/jump lowering into S3M's 64-row patterns |

Per-row volume automation remains possible. Baking envelopes/keymaps into extra events might preserve particular songs, but a general lossless lowering pass is substantial work and is **not** the recommended first implementation. In particular, the existing PCM instrument UI must not offer controls that the chosen export profile silently ignores.

## Converter hazards the writer must avoid

These are source-inspection findings unless explicitly covered by the fixtures above:

- Use an even order count, contiguous valid orders and only terminal 255 padding. GBT and mmutil disagree about some order alignment/skip/end cases; do not emit 254 skips or invalid entries that require remapping B targets.
- Give every declared instrument, including unused reserved slots, a valid nonzero header pointer. The splitter writes to instrument headers; a zero pointer can make it modify the module header.
- Give empty patterns real pattern data with 64 row terminators; do not rely on a zero pattern pointer being treated identically by both converters.
- The splitter moves packed PCM event channels but does **not** shift channel-setting/default-pan tables. Use an intentional neutral header and explicit pan events.
- Do not rely on channel-disabled flags to remove events. Keep required PSG headers enabled and omit unwanted packed events.
- Feed only the split DMA S3M to mmutil. Converting the unsplit module can play the template's sample approximations of PSG instead of reserving those parts for hardware.

Negative fixtures confirmed rejection of BPM 125, T7D, SB0, SD3 and an out-of-range low PSG note. They are targeted checks, not exhaustive certification of the exporter contract.

## Complexity and the implementation gate

| Work | Assessment |
| --- | --- |
| Constrained S3M writer and upstream conversion pipeline | Moderate; conventional binary writer, but the two converters' edge cases require explicit validation |
| Twelve-lane unified editor and typed instrument selection | Moderate; reuse editor/Undo/transport machinery, extend existing channel limits |
| Preserve arbitrary existing XM instruments/effects | High and unnecessary for a restricted profile; not a promised migration |
| Coordinated playback and faithful preview | Highest remaining risk: frame-quantized onset alignment, finite end/loops/transport, GBA wave/noise behavior and shared output mixing |
| Integrate a documented example in a generic homebrew game | Ordinary application glue with pinned libraries; not tied to the user's game, but not zero integration work |

**Recommendation:** decide whether fixed BPM 150 plus speed changes, simpler PCM instruments, the limited PSG controls and a measured frame-quantized timing contract are attractive enough. If yes, implement an explicitly constrained combined profile using these existing formats and libraries. Before UI rollout, prove compensated long playback, end/loop/transport behavior and preview parity, then check a physical GBA-class target under representative load. Keep upstream runtime code unchanged and publish the integration example with compatible permissive licensing/notices.

If those musical restrictions are unacceptable, **defer combined PSG support rather than build a custom runtime to escape them**. Keep the current XM backend. No broad community lingua franca with the current XM feature set plus transparent hardware PSG was established by this investigation.

Thor Tracker remains GPL-3.0-or-later. GBT and the Maxmod tool/runtime components have permissive licenses; retain their notices. Generated musical data is not automatically GPL, but samples and copied code retain their respective obligations.

## Reproduction and evidence

Local retained evidence:

`~/.local/state/thortracker/gba-s3m-spike-b372170386a7/`

- `make_fixtures.py`, `fixtures/manifest.json`, `extra-cases.json`: source fixtures and focused adversarial cases.
- `conversion-results.json`: original fourteen converter results; `extra-cases.json` records the three additional accepted cases.
- `build.sh`, `probe.c`, `converted/*/probe.gba`: standalone builds; player sources are read-only upstream inputs. `DELAY_PSG` selects only the experimental application timing branch.
- `upstream/original.gba`: unchanged upstream application, SHA-256 `818e4e1d52d84da21dba0b68adc6cf4efce24d5ed295e17ffed9dc060c3d1646`.
- `runtime-results.json`, `extra-runtime-results.json`, `finite-tail-results.json`: transport, channel activity, CPU observations and held-note tail.
- `onset-results.json`, `delayed-onset-results.json`, `delayed-runtime-results.json`: long-run onset measurements and the separate short compensation probe. In the delayed report, raw current-position differences are intentional; the delayed-target mismatch counter is zero.
- `captures/*/{capture.wav,memory.bin}`: stereo recordings and 64-byte per-frame observations. The baseline memory layout records magic/frame, PCM order/row/tick, PSG order/row/tick, catch-up count, mismatch count, PSG status, active PCM voices, current/max audio cycles and both playing flags.

Builds were serialized in a network-disabled SDK container capped at 1 GiB / one CPU. Emulator captures ran headlessly with a 2 GiB process-group limit and one worker. No Android device, Steam Deck, active game checkout or shared Gradle build was used.

## Primary sources

- [GBT combined workflow and channel/sample convention](https://github.com/AntonioND/gbt-player/blob/130e48662da2a91140a10d5af3934df90792de21/gba/examples/combined_maxmod/readme.rst)
- [Upstream combined application and synchronization loop](https://github.com/AntonioND/gbt-player/blob/130e48662da2a91140a10d5af3934df90792de21/gba/examples/combined_maxmod/source/main.c)
- [S3M splitter](https://github.com/AntonioND/gbt-player/blob/130e48662da2a91140a10d5af3934df90792de21/gba/s3msplit/s3msplit.py)
- [PSG format/effect documentation](https://github.com/AntonioND/gbt-player/blob/130e48662da2a91140a10d5af3934df90792de21/gba/s3m2gbt/readme.rst) and [converter](https://github.com/AntonioND/gbt-player/blob/130e48662da2a91140a10d5af3934df90792de21/gba/s3m2gbt/s3m2gbt.py)
- [GBT GBA runtime](https://github.com/AntonioND/gbt-player/blob/130e48662da2a91140a10d5af3934df90792de21/gba/gbt_player/gbt_player.c)
- [Pinned mmutil source](https://github.com/devkitPro/mmutil/tree/8b0792e45852b58fda4def4d263e246d3a73662d) and [Maxmod source](https://github.com/devkitPro/maxmod/tree/ce8adb2a6bf1f29ec203b6c3410c1ddf8e270811)
