# USB MIDI (Android Beta)

Under Files, choose **New USB MIDI song (Beta)**. Select a track in Pattern, then open **Files → MIDI tracks and USB outputs** to bind its destination, set channel, gate and velocity, add tracks, or enable transport clock. Use the regular pattern editor, undo, project files and order list. Removing a track affects every pattern.

For output to a computer, plug in a data-capable USB cable and select **MIDI** in Android’s USB preferences. Select the handheld’s MIDI input on the computer. Some devices omit MIDI peripheral mode. For USB host output, connect a class-compliant MIDI interface or synth to the handheld. Select the receiver’s input port in the track settings. If an attachment cannot be identified uniquely, rebind after unplugging it or restarting the app.

C0 sends MIDI note 12. G9 sends 127. Channels display as 1–16 and programs as 1–128. Set the receiver’s bend range to match the track. Tracks sharing a port and channel share program, bend and controllers. Simultaneous owners of one pitch share a sounding note until the last release. Reserve the selected channels on the receiver. Panic silences all performances on those channels. A disconnected receiver may need its own panic operation.

Each row spans four effect columns, processed left to right. Later commands in the same family win. Effects use hexadecimal parameters in the grid.

| Code | Action |
|---|---|
| 0xy | Cycle the root and two upward semitone offsets within the row |
| 1xx / 2xx | Bend up / down from center by xx cents over the row |
| 3xx / 4xx | Raise expression from zero / lower it from full over the row |
| 5xx | Divide the row into 1–16 strikes |
| 6xx | Delay the first strike by xx/256 of the row |
| 7xx | Add 1–8 increasingly quiet echoes within the row |
| 8xx | Override attack velocity (1–127) |
| 9xx / Axx | Send a 0–127 value to either named CC assignment |

Gate is a percentage of each strike. Android runs 48 tracker ticks per second and four rows per quarter. At speed 6 the tempo is 120 BPM. Clock sends six pulses per row to each bound port. Play from cursor starts a new external transport run at that point. Pattern and song loops keep the clock running. Position-pointer and Continue messages are unsupported. MIDI output has no offline WAV render.
