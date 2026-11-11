# Musical instrument faces

**0.44.0/code 62:** Sound covers every supported chip family, including wavetable, noise,
sample and percussion channels. Advanced remains the exact native editor. The Game Boy, SID
and YM2612 pilots shipped in 0.43.0; the wider rollout has passed the device scenarios below.

The APK remains **Thor Tracker**, package `org.thortracker`, with the production signing key.
It updates the normal app rather than installing another similarly named app. The older
separate Touch Preview installation and its data are not removed or imported automatically.


## Handheld gallery

Actual native editor captures, not mockups or recolored composites. The original three pilots
below are Pocket DMG captures from 0.43.0 revision 3. The wider-family gallery uses the final
0.44.0 candidate on Nova, DMG and Konkr; it omits Android system bars.

### Game Boy

![Game Boy pixel LCD instrument on DMG](media/instrument-pilots/gb-pulse-sound.png)

[Wave](media/instrument-pilots/gb-wave-sound.png) ·
[Noise](media/instrument-pilots/gb-noise-sound.png) ·
[Advanced](media/instrument-pilots/gb-pulse-advanced.png)

### C64 SID

![SID waveform keys and faders on DMG](media/instrument-pilots/sid-mos8580-sound.png)

[Advanced](media/instrument-pilots/sid-mos8580-advanced.png)

### Mega Drive YM2612

![YM2612 brightness dial and four supporting controls on DMG](media/instrument-pilots/ym2612-sound.png)

[Advanced](media/instrument-pilots/ym2612-advanced.png)

Compact **360×270 dp native editor renders**, not physical phone captures:
[GB](media/instrument-pilots/gb-pulse-360x270dp.png) ·
[SID](media/instrument-pilots/sid-mos8580-360x270dp.png) ·
[YM2612](media/instrument-pilots/ym2612-360x270dp.png).

### More sound machines

![NES console cabinet on Nova](media/instrument-faces/nes.png)

![POKEY keyboard-style controls on DMG](media/instrument-faces/pokey.png)

![TIA paddle controls on DMG](media/instrument-faces/tia.png)

![WonderSwan wave console on Nova](media/instrument-faces/wonderswan.png)

Open the remaining full-size captures:

| Family | Device |
| --- | --- |
| [AY/YM2149](media/instrument-faces/ay.png) | Nova |
| [Sega PSG](media/instrument-faces/sega.png) | Nova |
| [VIC](media/instrument-faces/vic.png) | DMG |
| [SAA1099](media/instrument-faces/saa.png) | DMG |
| [PC Engine](media/instrument-faces/pce.png) | Konkr |
| [SCC](media/instrument-faces/scc.png) | Nova |
| [Virtual Boy](media/instrument-faces/vsu.png) | Konkr |
| [OPL3](media/instrument-faces/opl3.png) | Nova |
| [OPM](media/instrument-faces/opm.png) | Nova |
| [OPNA ADPCM](media/instrument-faces/opna-adpcm.png) | Nova |

## One instrument, two faces

In **Instrument**, select a slot on the left. **Sound** offers a few musical controls on the
right; **Advanced** retains the exact chip editor. Switching faces never converts or normalizes
an instrument. A patch outside a musical control's recognized choices stays **Custom**, with
no invented slider position. Deliberate edits own only the fields listed below.

- Touch: tap Sound/Advanced; move a control horizontally. Hold X or A to hear accepted edits.
- Sound controller: **Up/Down selects the instrument slot; Left/Right cycles controls;
  hold A + Left/Right to adjust.** New Sound views focus the first musical control. X listens.
  The horizontal cycle also includes Name and Sound/Advanced: A switches faces on the mode
  selector; on Name, A+Left/Right edits a letter and A+Up/Down moves the letter cursor.
  Advanced retains its existing precise-editor navigation.
- A continuous edit is one Undo. Mode/slot changes and Undo end stale pointer edits. A held
  across a mode change must be released before it can change a different control.
- The instrument list uses the same typography, geometry and hit targets in both faces;
  machine-specific artwork stays in the sound-editing area. Small Sound panes page controls
  at physical touch size rather than shrinking them. The list retains Advanced's existing
  responsive layout. The bank retains workspace colors; the editing area can use a
  machine-specific palette with contrasting control labels and focus indicators.
- Mode/cursor state belongs to the pane for this session, not the musical project. There is
  no new project format, hidden macro bank, extra synthesis layer or export-only approximation.

The shared parts are mode/cursor safety, drag ownership, hit mapping, input routing, audition
and document history. Musical mappings and face composition stay chip-specific. The pixel
lettering is an original small bitmap alphabet, not a copied console font or logo.

## Game Boy — pixel LCD

Four olive/near-black shades, integer-pixel lettering, stepped selectors and real chip diagrams.
Pulse, Wave and Noise all start in Sound. L1/R1 switches banks.

| Bank | Controls | Exact ownership and limits |
| --- | --- | --- |
| Pulse | Character, Shape, Length, Motion | Duty; envelope/length gate; hardware sweep. Motion is audible on Pulse 1 only; rising sweep can reach the hardware cutoff. |
| Wave | Wave, Level, Length | Select an existing shared 32-sample wave; hardware mute/25%/50%/100% shift; 125/250/500 ms gate or Held. No shared samples are rewritten. |
| Noise | Grain, Shape, Length | Long 15-bit Air or short 7-bit Metal sequence; actual envelope and length gate. Played notes still choose the noise clock. |

Pulse/Noise Shape offers Pluck, Held and Swell. Length retains a recognized Shape; choosing
Length on a Custom Shape deliberately adopts Pluck, with a selected-control warning. Pluck
fade / Swell rise choices are approximately 188/375/750/1313 ms; Held uses approximately
63/125/250 ms or no length gate. These are different hardware mechanisms, not an emulated ADSR.

Selecting an already-current choice preserves the exact patch, including equivalent 25%/75%
pulse duty and inactive raw fields. Wave Level names describe the chip's sample shifts: actual
AC amplitude can differ from an ideal percentage because samples are only four bits. The
review square wave measures a 5:1 full/quarter RMS ratio, not a falsely promised 4:1.

## C64 SID — programmable synth deck

A blue/periwinkle signal display, beige waveform keys and amber faders on one continuous face.
The waveform, pulse movement and envelope drawing follow the stored patch. L1/R1 chooses the
voice used for audition; it does not create independent per-voice filters.

| Control | Choices | Owned fields |
| --- | --- | --- |
| Voice | Triangle, Saw, Pulse, Noise | Wave program. Selecting Pulse also seeds an empty pulse program to half width; existing pulse programs survive. |
| Pulse | Inherit, Square, Narrow, Drift, Flutter | Pulse program only; enabled for a recognized Pulse voice. |
| Contour | Pluck, Held, Swell, Organ | Attack, decay and sustain only. |
| Tail | Nominal 6 ms–24 s chip release settings | Release nibble only; actual tail depends on envelope state. |
| Color | Leave, Warm, Round, Nasal, Air | Filter program only; generated colors route all three voices. |

**There is one shared filter. The last voice to start a filter program wins.** Leave makes no
new filter claim; it does not bypass another voice's filter. Both 6581 and 8580 retain their
actual filter behavior, so the same Color is not promised to sound identical on both chips.
Complex programs read Custom. Wave-table commands that override envelope, pulse or filter
settings make the affected musical control unavailable rather than pretending it can win.
Advanced retains all tables, placement, restart, vibrato and precise programming controls.

## Mega Drive YM2612 — FM tone console

Graphite and silver with an electric-blue Brightness dial, red Edge accent and supporting
controls. Wider panes show the large tonal dial beside four smaller controls; narrow panes
page them. L1/R1 steps through Sound controls. Existing Curated/Expert, CH3 and Drum pages
remain under Advanced. PSG and PCM now have separate Sound controls rather than an FM face:
PSG contour/time, pitch, grain, clock and audition lane; PCM key, recording, tuning, level and lane.
Recording choices reference existing samples and mappings; opening a face does not rewrite them.

| Control | Musical role | Owned fields |
| --- | --- | --- |
| Brightness | Amount of modulation; percentage is control travel, not a measured spectrum | Modulator TL only, preserving relative levels until register limits. Carrier TL stays unchanged; perceived loudness can still vary with timbre. |
| Structure | Eight routing families | Algorithm plus the existing role-aware voice-level balancing. This is an explicit structural change, not a preset replacement during other edits. |
| Harmonics | Round, Hollow, Reedy, Metal, Glass | Modulator multipliers 1/2/3/5/7 only. Carrier multiplier and detune survive. |
| Edge | Feedback bite | Feedback 0–7 only. |
| Contour | Pluck, Held, Bloom, Short | Carrier attack/decay/sustain/release fields only. |

Four-sine/additive Structure has no modulators, so Brightness and Harmonics are unavailable.
Active carrier SSG envelopes make Contour unavailable; their shape is not silently discarded.
Names, sweep, CH3 offsets, AMS/FMS, song LFO and unrelated operator settings remain intact.
The labels are musical entry points, not promises of identical perceived timbre for arbitrary
carrier ratios, detuning, tuning or modulation programs.

## Wider family coverage

The controls below edit the existing native instruments. Shared noise clocks, envelopes,
filters, channels and sample memories retain the backend's existing ownership rules; a Sound
face does not turn them into independent synthesizers.

| Family | Sound approach | Important boundary |
| --- | --- | --- |
| NES and expansions | Console-grey cabinet, red faders, native signal and envelope displays; voice-specific width, contour, register, wave, modulation or sample controls | Voice explicitly selects a channel; crossing chips converts the instrument's native type. DPCM/PCM mappings follow the selected key. EPSM rhythm still needs the user's rhythm ROM. |
| AY/YM2149 computers | Tone/noise source, contour, noise clock and hardware-envelope shaping | Noise and the hardware envelope remain shared resources, not per-instrument copies. |
| Sega PSG/Game Gear and OPLL | PSG contour, chord and noise shaping; FM voice and tone controls | Noise can depend on the tone channel's clock. OPLL ROM voices and editable custom patches remain distinct. |
| VIC/TED | Native source, gate, contour, level and audition controls | Chip-wide volume and software sample playback keep their existing backend limits. |
| SAA1099/SAM Coupé/Game Blaster | Source, contour, stereo position, noise and envelope motion | Six- and twelve-channel variants retain their own lane groups and shared envelope/noise ownership. |
| Atari POKEY/TIA | POKEY keyboard-style keycaps and TIA paddle controls; chip-specific tone/noise choices, contours and movement | Joined voices and channel restrictions are respected; unsupported controls are unavailable rather than approximate. |
| PC Engine | Wave/noise/sample source, existing waveform or recording, contour and movement | Shared waveform/sample data are not rewritten. Noise and DDA retain their channel and playback restrictions. |
| SCC/SCC+ | Existing wave, contour, waveform gesture, attack and audition lane | Native shared-wave constraints remain; opening the face preserves the complete patch. |
| WonderSwan/Color | Voice, wave/noise/recording, contour, tuning and playback | CPU sample, HyperVoice and DMA modes keep their distinct capabilities and compatible audition lanes. |
| Virtual Boy VSU | Audition lane, timbre or grain, contour, gate, motion and level | Wave-5 modulation and noise remain restricted to the appropriate native voices. |
| OPL3 | Voicing, drawbar, reed, contour, release and drop | Two- and four-operator patches use their real routing; this does not add a new hardware-drum instrument type. |
| OPM/YM2151 | Colour, alloy, contour, bite, noise and fall | Hardware noise belongs to FM8; arbitrary patches and disabled controls retain their exact native settings. |
| OPN family | Separate FM, SSG, rhythm and ADPCM faces | Rhythm requires its ROM; ADPCM uses real imported recordings. The FM controls do not overwrite these other instrument types. |

Handheld layouts page controls when necessary instead of reducing touch targets below usable
size. The NES cabinet uses a denser two-column control layout on Pocket DMG. POKEY shows all
six controls and TIA all four paddles there; SCC, WonderSwan, Sega FM and SAA use the available
landscape width instead of unnecessarily paging a single control.

The first real NES pass found a mixed-input bug: a touch edit while A was held changed the
patch but did not restart audition. Accepted touch edits now update audition while the
separate held-direction guard still prevents accidental edits after a target change.

## Verification and limits

The final 0.44.0 candidate passes **1,402 core JVM and 22 Android host tests**. Real Nova
Activity scenarios cover **41 system configurations and 281 channel/slot cases**: 1,309
controller control probes, 281 held-A touch edits, 281 pixel-identical Sound/Advanced sidebar
comparisons, whole-project Undo and preservation checks, 41 native name Apply/Cancel/Undo
scenarios, native audition and advancing transport. These include the new YM2612 PSG/PCM
faces; GB/SID retain their previously verified pilots.

DMG repeats **73 cases across 15 PSG/Atari configurations**; Konkr repeats **30 cases across
six wavetable configurations**. The three runs produce **768 native WAVs** across 384 cases.
These compare real saved baseline/shaped patches and validate native output, not acoustic
listening or original-hardware CPU cost. No driver faults were recorded. Nova accumulated
two audio underruns during the dense stress run; DMG/Konkr recorded none, so this is not a
claim of universally glitch-free audio. A separate clean APK passes all **30 permanent
Android instrumentation tests** on DMG, including its native audio contract.

Compact 360×270 dp renders exercise paging; actual handheld captures above expose the
landscape layouts. These do not establish Galaxy Fold8 physical thumb reach. The CI AppImage
also passes isolated offscreen instrument/audition/playback scenarios; physical Deck and
Brick operation are not claimed by that check. See [verification details](VERIFICATION.md).

### Earlier pilot evidence

The original code-59 candidate passes **1,187 shared-core and 22 Android host tests**, plus a
real Pocket DMG Activity scenario for all three GB banks, both SID models and YM2612. It covers
touch/controller edits, grouped Undo, exact native-image preservation across face switches,
audition and advancing playback. Native Android editor renders at **360×270 dp and 720×352 dp**
exercise all controls at physical touch size. Those are synthetic editor viewports, not Galaxy
Fold8 captures or physical two-thumb ergonomics evidence.

Revision 2 fixes controller name navigation beyond SID's 16-character limit. Its regression
fails before the fix and passes afterward; **1,188 core and 22 Android host tests pass**.
Compact GB/FM coverage now requires touch navigation whenever controls are hidden.
Revision 3 passes **1,206 core and 22 Android host tests**, plus a live Pocket DMG Activity
scenario covering the requested axes, held edits, grouped Undo, touch mode switching and
pixel-identical instrument lists in all six bank/model cases. All **30 permanent Android
contracts** pass in a separate clean test APK. The gallery and six 360×270 dp native renders
are refreshed; musical mappings and the earlier native-audio measurements are unchanged.

Pattern channel headers now show the most frequently assigned instrument in that channel's
active pattern rows, not the shared entry instrument. Empty assignments and hidden rows do
not count; ties prefer the lowest slot. The live Activity check verifies distinct Pulse 1/2
labels remain stable while choosing a different entry sound. This does not alter playback.

**34 native WAVs** exercise envelopes, wave shifts/gates, noise width, SID PWM/shared filter and
FM modulation/harmonics/feedback. The low GB noise fixture clocks at 146.286 Hz: its seven-bit
sequence repeats after 0.868164 s (correlation 0.99993), while the long sequence does not
(0.15643 at that lag). A short-window spectral-flatness assumption was rejected in favor of
that exact chip-period comparison. SID Warm → Air → Warm changes started by different voices
match the corresponding static-filter RMS within 1.5% on 6581 and 0.001% on 8580. FM Brightness
raises this fixture's spectral centroid from 262 to 538 Hz while leaving both carrier TLs at 16.
These are digital native-render checks, not acoustic listening or original-console CPU-budget proof.

A counterfactual check compares each SID window against both static colors: the correct color's
PCM RMS error is below one tenth of the wrong color's error in all six windows (both chip models).
The YM Edge input projects differ **only** in feedback 0 → 7; 99.905% of sampled PCM frames differ.
Their equality to the adjacent Glass/Held fixtures is expected endpoint reuse, not a harmonics
change. Feedback is not monotonically brighter for arbitrary patches: this MUL-7 fixture's
centroid decreases from 2778 to 2224 Hz. The test proves an isolated feedback change, not brightness.

Every device run cold-backs up and independently verifies restoration of the eight original
private recovery/workspace entries; its test APK and UUID-owned files are removed, and original
device settings restored. `/ThorTunez` is not touched. Builds are serialized and memory-capped;
local Kotlin/Native linking and emulators are avoided.

## Wider rollout

The authorized family rollout is implemented. Future refinements must keep the stable
instrument list, controller axes, mode safety, touch ownership and Undo behavior. Do not
force every chip into the same musical controls or recolor one universal form.

Related voices share widgets and layout machinery, but each mapping owns explicit native
fields and exposes shared-resource limits. Opening a face preserves arbitrary patches;
Advanced remains available. The original synth, swing, mixer ceilings and shader pipeline
remain separate, deferred work.
