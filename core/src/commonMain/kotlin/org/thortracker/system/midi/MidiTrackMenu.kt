package org.thortracker.system.midi

import org.thortracker.editor.EditorSession
import org.thortracker.editor.MenuContext
import org.thortracker.system.studio.*

fun midiTrackMenu(session: EditorSession, menu: MenuContext, ports: List<Pair<String, String>>, panic: () -> Unit, usbAvailable: Boolean = true) {
    val song = session.song
    val legacy = song.sound as? MidiSound
    val studio = song.sound as? StudioSound
    val index = session.selection.channel
    val lane = studio?.tracks?.get(index)
    val track = lane?.midi ?: requireNotNull(legacy).tracks[index]
    val external = lane == null || lane.target == StudioTarget.USB_MIDI
    fun editLane(label: String, change: (StudioTrack) -> StudioTrack) {
        session.apply(label) { doc -> doc.copy(sound = doc.studio.copy(tracks = doc.studio.tracks.map {
            if (it.midi.id == track.id) change(it) else it
        })) }
    }
    fun edit(label: String, change: (MidiTrack) -> MidiTrack) {
        if (studio != null) return editLane(label) { it.copy(midi = change(it.midi)) }
        session.apply(label) { doc ->
            val state = doc.sound as MidiSound
            doc.copy(sound = state.copy(tracks = state.tracks.map { if (it.id == track.id) change(it) else it }))
        }
    }
    fun number(label: String, value: Int, range: IntRange, change: (MidiTrack, Int) -> MidiTrack): Pair<String, () -> Unit> =
        "$label · $value" to {
            menu.text("$label (${range.first}–${range.last})", value.toString(), 5) { entered ->
                val n = entered.toIntOrNull()
                if (n == null || n !in range) menu.error("Enter a number from ${range.first} to ${range.last}")
                else edit(label) { change(it, n) }
            }
        }
    val output = ports.firstOrNull { it.first == track.port }?.second ?: "Bind USB output"
    val clock = studio?.clock ?: requireNotNull(legacy).clock
    val count = song.system.channels.size
    menu.menu("${if (studio == null) "MIDI" else "Studio"} track ${track.id}", buildList {
        if (lane != null) add("Destination · ${if (external) "USB MIDI" else "Mono"}" to {
            menu.menu("Track destination", listOf(
                "Mono synth" to { editLane("Assign Mono") { it.copy(target = StudioTarget.MONO) } },
                "USB MIDI" to {
                    if (usbAvailable) editLane("Assign USB MIDI") { it.copy(target = StudioTarget.USB_MIDI) }
                    else menu.error("USB MIDI output is available on Android only")
                }))
        })
        if (external) add((if (usbAvailable) output else "USB MIDI · Android only") to {
            if (!usbAvailable) menu.error("This USB route is preserved. Play it on Android or choose Mono explicitly.")
            else if (ports.isEmpty()) menu.error("Connect a data cable and select MIDI under Android USB preferences, or attach a class-compliant USB instrument. Reopen tracks to refresh.")
            else menu.menu("Send to USB port", ports.map { (key, label) -> label to { edit("Bind MIDI output") { it.copy(port = key) } } })
        })
        if (external) add(number("Channel", track.channel + 1, 1..16) { t, v -> t.copy(channel = v - 1) })
        add(number("Velocity", track.velocity, 1..127) { t, v -> t.copy(velocity = v) })
        add(number("Gate % of step", track.gate, 1..100) { t, v -> t.copy(gate = v) })
        if (external) add(number("Receiver bend range", track.bendRange, 1..24) { t, v -> t.copy(bendRange = v) })
        if (external) add(number("Bank (-1 off)", track.bank ?: -1, -1..16383) { t, v -> t.copy(bank = v.takeIf { it >= 0 }) })
        if (external) add(number("Program (0 off)", track.program?.plus(1) ?: 0, 0..128) { t, v -> t.copy(program = (v - 1).takeIf { it >= 0 }) })
        if (external) track.bindings.forEachIndexed { i, binding ->
            add(number("${binding.name} CC", binding.controller, 0..119) { t, v ->
                t.copy(bindings = t.bindings.mapIndexed { j, b -> if (i == j) b.copy(controller = v) else b })
            })
            add("Rename ${binding.name}" to {
                menu.text("Control name", binding.name, 24) { name ->
                    if (name.isNotBlank()) edit("Rename MIDI control") { t ->
                        t.copy(bindings = t.bindings.mapIndexed { j, b -> if (i == j) b.copy(name = name) else b })
                    }
                }
            })
        }
        if (external && usbAvailable) add("Clock / Start / Stop · ${if (clock) "on" else "off"}" to {
            session.apply("Toggle MIDI clock") { it.copy(sound = studio?.copy(clock = !clock) ?: requireNotNull(legacy).copy(clock = !clock)) }
        })
        if (count < (if (studio == null) 64 else StudioSound.MAX_TRACKS)) add("Add track" to {
            session.apply("Add track") { if (studio != null) it.addStudioTrack() else it.addMidiTrack() }; Unit
        })
        if (count > 1) add("Remove track ${track.id}" to {
            menu.menu("Remove track from every pattern?", listOf("Remove track ${track.id}" to {
                session.apply("Remove track") { if (studio != null) it.removeStudioTrack(index) else it.removeMidiTrack(index) }; Unit
            }))
        })
        if (external && usbAvailable) add("Panic…" to { menu.menu("Silence bound channels, including other performers?", listOf("Send panic" to panic)) })
        song.system.warnings(song).forEach { add(it to { menu.status(it) }) }
    })
}
