package org.thortracker.system.midi

import org.thortracker.editor.EditorSession
import org.thortracker.editor.MenuContext

fun midiTrackMenu(session: EditorSession, menu: MenuContext, ports: List<Pair<String, String>>, panic: () -> Unit) {
    val song = session.song
    val sound = song.sound as MidiSound
    val index = session.selection.channel
    val track = sound.tracks[index]
    fun edit(label: String, change: (MidiTrack) -> MidiTrack) {
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
    menu.menu("MIDI track ${track.id}", buildList {
        add(output to {
            if (ports.isEmpty()) menu.error("Connect a data cable and select MIDI under Android USB preferences, or attach a class-compliant USB instrument. Reopen MIDI tracks to refresh.")
            else menu.menu("Send to USB port", ports.map { (key, label) -> label to { edit("Bind MIDI output") { it.copy(port = key) } } })
        })
        add(number("Channel", track.channel + 1, 1..16) { t, v -> t.copy(channel = v - 1) })
        add(number("Velocity", track.velocity, 1..127) { t, v -> t.copy(velocity = v) })
        add(number("Gate % of step", track.gate, 1..100) { t, v -> t.copy(gate = v) })
        add(number("Receiver bend range", track.bendRange, 1..24) { t, v -> t.copy(bendRange = v) })
        add(number("Bank (-1 off)", track.bank ?: -1, -1..16383) { t, v -> t.copy(bank = v.takeIf { it >= 0 }) })
        add(number("Program (0 off)", track.program?.plus(1) ?: 0, 0..128) { t, v -> t.copy(program = (v - 1).takeIf { it >= 0 }) })
        track.bindings.forEachIndexed { i, binding ->
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
        add("Clock / Start / Stop · ${if (sound.clock) "on" else "off"}" to {
            session.apply("Toggle MIDI clock") { it.copy(sound = sound.copy(clock = !sound.clock)) }
        })
        if (sound.tracks.size < 64) add("Add track" to { session.apply("Add MIDI track") { it.addMidiTrack() }; Unit })
        if (sound.tracks.size > 1) add("Remove track ${track.id}" to {
            menu.menu("Remove track from every pattern?", listOf("Remove track ${track.id}" to {
                session.apply("Remove MIDI track") { it.removeMidiTrack(index) }; Unit
            }))
        })
        add("Panic…" to { menu.menu("Silence bound channels, including other performers?", listOf("Send panic" to panic)) })
        song.system.warnings(song).forEach { add(it to { menu.status(it) }) }
    })
}
