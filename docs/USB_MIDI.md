# USB MIDI (Android Beta)

Under Files, choose **New USB MIDI song (Beta)**. Select a track in Pattern, then open **Files → MIDI tracks and USB outputs** to bind its destination, set channel, gate and velocity, add tracks, or enable transport clock. Use the regular pattern editor, undo, project files and order list. Removing a track affects every pattern.

For output to a computer, plug in a data-capable USB cable and select **MIDI** in Android’s USB preferences. Select the handheld’s MIDI input on the computer. Some devices omit MIDI peripheral mode. For USB host output, connect a class-compliant MIDI interface or synth to the handheld. Select the receiver’s input port in the track settings. If an attachment cannot be identified uniquely, rebind after unplugging it or restarting the app.

C0 sends MIDI note 12. G9 sends 127. Channels display as 1–16 and programs as 1–128. Set the receiver’s bend range to match the track. Tracks sharing a port and channel share program, bend and controllers. Simultaneous owners of one pitch share a sounding note until the last release. Reserve the selected channels on the receiver. Panic silences all performances on those channels. A disconnected receiver may need its own panic operation.

Each row spans four effect columns, processed left to right. Later commands in the same family win. Effects use hexadecimal parameters in the grid.

| Code | Action |
|---|---|
| 0xy | Cycle the root and two upward semitone offsets within the row |
| 1xx / 2xx | Add / subtract xx cents from the current bend over the row |
| 3xx / 4xx | Raise / lower current expression by xx MIDI steps over the row |
| 5xx | Divide the row into 1–16 strikes |
| 6xx | Delay the first strike by xx/256 of the row |
| 7xx | Add 1–8 increasingly quiet echoes within the row |
| 8xx | Override attack velocity (1–127) |
| 9xx / Axx | Send a 0–127 value to either named CC assignment |

Slides add five equal increments across the row and hold their final value through blank rows and automatic loops. Tracks on the same port and channel share the wheel and expression. A named CC11 write immediately sets the starting expression for subsequent increments. Expression clamps at 0–127 and bend clamps at the limits of the wheel. Set tracks sharing a channel to the receiver’s bend range.

A fresh pattern note centers the wheel at its first strike, including a delayed strike. Retriggers, echoes and audition during playback retain the current bend. Expression carries across notes. A new Play starts from centered bend and full expression, sends configured bank/program selections, and reconstructs controller changes preceding the cursor. Program selection adds no extra slide reset. Stop cancels remaining increments and restores any bend or expression changed by the tracker to center or full.

Gate is a percentage of each strike. Android runs 48 tracker ticks per second and four rows per quarter. At speed 6 the tempo is 120 BPM. Clock sends six pulses per row to each bound port. Play from cursor starts a new external transport run at that point. Pattern and song loops keep the clock running. Position-pointer and Continue messages are unsupported. MIDI output has no offline WAV render.

## Internal instruments alongside USB MIDI

Choose **Files → New Studio song** for internal synthesis with optional USB tracks.
**Studio tracks and destinations…** assigns each of its one to eight tracks to Mono synth
or USB MIDI. Mono patches are edited in Instrument; USB bank, program and CC settings stay
with the track. See [Studio instruments](MANUAL.md#studio-internal-instruments).

In a mixed Studio project, the internal audio engine supplies the row clock and image version
used by USB dispatch. USB is paced against the native output-presentation estimate, not a
second free-running song transport. This does not guarantee sample-accurate alignment at an
external receiver. An unavailable route prevents playback; a failed route or audio clock
stops both destinations. USB MIDI remains Android-only.

## Project and playback model

A MIDI project uses the same patterns, cells, orders, project envelope and undo history as chip projects. Its sound data holds the track list and logical port preferences. Each track has a stable ID independent of its lane index, destination and channel. Open the project in the Android app to use its USB backend.

The row sequencer emits timed note, pitch, expression and named-parameter events in musical units. The Android playback worker maps them to MIDI messages, resolves port preferences and tracks note ownership. One monotonic timeline drives notes and transport clock. The worker submits simultaneous messages for the same port together, with clock first at a shared deadline. Android scheduling and the USB receiver affect arrival timing. Stop cancels the next scheduled callback and releases owned notes, sustain and bend on the available outputs. Leaving the Activity pauses the tracker and runs transport cleanup. Returning to the app requires a new Play. Android cannot acknowledge that the receiver processed a message. If a cable is removed while a note is sounding, reconnect and explicitly use Panic or silence the receiver.
