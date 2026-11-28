package org.thortracker.system.studio

import kotlin.math.abs
import org.thortracker.editor.*
import org.thortracker.model.Song

internal val StudioKind.faceName: String get() = when (this) {
    StudioKind.MONO -> "Mono"
    StudioKind.SAMPLER -> "Sampler"
    StudioKind.FM -> "FM"
    StudioKind.PERCUSSION -> "Drums"
    StudioKind.WAVETABLE -> "Wavetable"
    StudioKind.PLUCK -> "Pluck"
    StudioKind.MODULAR -> "Modular"
    StudioKind.DELAY -> "Delay"
    StudioKind.DRIVE -> "Drive"
    StudioKind.CRUSHER -> "Bitcrusher"
    StudioKind.REVERB -> "Reverb"
    StudioKind.CHORUS -> "Chorus"
}

internal fun fxHex(value: Int) = value.toString(16).uppercase().padStart(2, '0')
internal fun fxLetter(code: Int) = code.toString(36).uppercase()

/** Nearest representable automation byte. Switching faces never quantizes the stored patch. */
internal fun StudioParameter.nearestByte(value: Int): Int {
    var lo = 0
    var hi = 255
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (fromHex(mid) < value) lo = mid + 1 else hi = mid
    }
    return if (lo > 0 && abs(fromHex(lo - 1) - value) <= abs(fromHex(lo) - value)) lo - 1 else lo
}

/** Capture the exact cursor before opening a popup. Never silently use another empty column. */
internal object StudioAssignments {
    private class Destination(val cursor: ViewState, val order: Int, val row: Int, val channel: Int,
        val field: Int, val docVersion: Long) {
        val column = ViewController.columnOf(field)
        fun valid(session: EditorSession, menu: MenuContext, origin: ViewState): Boolean =
            session.documentVersion == docVersion && menu.patternCursor(origin) === cursor && cursor.order == order &&
                cursor.row == row && cursor.channel == channel && cursor.field == field
    }

    private fun destination(session: EditorSession, menu: MenuContext, origin: ViewState): Destination? {
        val cursor = menu.patternCursor(origin)
        val song = session.song
        val ch = cursor.channel
        val lane = (song.sound as? StudioSound)?.tracks?.getOrNull(ch) ?: return null
        val column = ViewController.columnOf(cursor.field)
        val reason = when {
            lane.target == StudioTarget.USB_MIDI -> "Select an internal track in Pattern; USB has no Studio modules."
            cursor.field < 2 || column !in 0 until song.system.effectColumns(ch) -> "Focus an effect cell in Pattern, then choose Write."
            cursor.order !in song.orders.indices || cursor.row !in 0 until song.rowCount(song.patternAt(cursor.order)) -> "Select a visible pattern row first."
            else -> song.cellAt(song.patternAt(cursor.order), ch, cursor.row).let { cell ->
                if (cell.effectCode(column) != 0 || cell.effectParam(column) != 0) "FX ${column + 1} already contains a command. Choose a blank cell or clear it first." else null
            }
        }
        if (reason != null) { menu.error(reason); return null }
        return Destination(cursor, cursor.order, cursor.row, ch, cursor.field, session.documentVersion)
    }

    fun choose(session: EditorSession, menu: MenuContext, origin: ViewState) {
        val dest = destination(session, menu, origin) ?: return
        targets(session.song.studio, dest.channel, origin.instrument, menu, "Write to row ${fxHex(dest.row)}, FX ${dest.column + 1}") { binding, p, value ->
            assign(session, menu, origin, dest, binding, p, value)
        }
    }

    private fun targets(s: StudioSound, channel: Int, slot: Int, menu: MenuContext, title: String,
        picked: (StudioBinding, StudioParameter, Int) -> Unit) {
        val lane = s.tracks[channel]
        fun params(kind: StudioKind, id: Int, value: (Int) -> Int, patch: StudioPatch? = null) {
            menu.menu("${kind.faceName} parameter", kind.parameters.map { spec ->
                val p = if (patch is ModularPatch) spec.copy(name=patch.graph.macros[spec.id].name) else spec
                "${p.name}  ${p.display(value(p.id))}" to {
                    picked(StudioBinding(id, kind, p.id), p, value(p.id))
                }
            })
        }
        menu.menu(title, buildList {
            // The family is explicit: never infer the meaning of a parameter ID across different instruments.
            val kinds = s.patches.distinctBy { it.kind }
            for (patch in kinds) {
                val example = s.patch(slot)?.takeIf { it.kind == patch.kind } ?: patch
                add("Instrument: ${patch.kind.faceName}" to { params(patch.kind, 0, example::value, example) })
            }
            for ((index, pedal) in lane.inserts.withIndex()) {
                add("Insert ${index + 1}: ${pedal.kind.faceName}" to { params(pedal.kind, pedal.id, pedal::value) })
            }
        })
    }

    /** Returns the bound effect letter(s) on [channel] for [instance]/[kind]/[parameter], or null when unbound. */
    fun boundLetters(song: Song, channel: Int, instance: Int, kind: StudioKind, parameter: Int): String? {
        val studio = song.sound as? StudioSound ?: return null
        val lane = studio.tracks.getOrNull(channel) ?: return null
        if (lane.bindings.isEmpty()) return null
        var first = -1
        var more: StringBuilder? = null
        for (code in 14..35) {
            val b = lane.bindings[code] ?: continue
            if (b.instance == instance && b.kind == kind && b.parameter == parameter) {
                if (first < 0) first = code
                else {
                    if (more == null) more = StringBuilder(fxLetter(first))
                    more.append(',').append(fxLetter(code))
                }
            }
        }
        return more?.toString() ?: if (first >= 0) fxLetter(first) else null
    }

    private fun bindingLabel(s: StudioSound, slot: Int, b: StudioBinding): String {
        val base = b.kind.parameters.first { p -> p.id == b.parameter }.name
        if (b.kind != StudioKind.MODULAR) return "${b.kind.faceName} $base"
        val active = (s.patch(slot) as? ModularPatch) ?: s.patches.firstOrNull { it is ModularPatch } as? ModularPatch
        val custom = active?.graph?.macros?.getOrNull(b.parameter)?.name
        return if (custom != null && custom != base) "${b.kind.faceName} $custom ($base)" else "${b.kind.faceName} $base"
    }

    /** Explicitly bind a named letter, including repair of an already-used unbound command. */
    fun bindings(v: ViewController, menu: MenuContext) {
        val state = v.state
        val channel = state.channel
        val doc = v.song
        val version = v.session.documentVersion
        val lane = doc.studio.tracks[channel]
        val counts = IntArray(36)
        for (pattern in doc.patterns) for (cell in pattern.tracks[channel])
            for (col in 0..cell.more.size) {
                val code = cell.effectCode(col)
                if (code in 14..35) counts[code]++
            }
        fun valid() = v.state === state && state.kind == ViewKind.STUDIO_STRIP && state.stripBus < 0 &&
            state.channel == channel && v.session.documentVersion == version
        fun bind(code: Int, next: StudioBinding?) {
            if (!valid()) { menu.error("The track changed. Open bindings again."); return }
            val map = if (next == null) lane.bindings - code else lane.bindings + (code to next)
            v.session.apply("Bind ${fxLetter(code)}") { it.copy(sound = doc.studio.copy(tracks =
                doc.studio.tracks.replaced(channel, lane.copy(bindings = map)))) }
        }
        menu.menu("Track ${lane.midi.id} · parameter letters", (14..35).map { code ->
            val b = lane.bindings[code]
            val count = counts[code]
            val letter = fxLetter(code)
            val label = b?.let { bindingLabel(doc.studio, state.instrument, it) } ?: "Unbound"
            "$letter  $label${if (count == 0) "" else "  ($count)"}" to {
                fun pick() = targets(doc.studio, channel, state.instrument, menu, "Bind $letter to…") { next, p, _ ->
                    if (count == 0 || next == b) bind(code, next)
                    else menu.menu("Change all $count $letter commands on this track?", listOf(
                        "Use ${next.kind.faceName} ${p.name}" to { bind(code, next) }))
                }
                if (b == null) pick() else menu.menu("$letter · $label", buildList {
                    add("Choose target…" to ::pick)
                    add("Unbind letter" to {
                        if (count == 0) bind(code, null)
                        else menu.error("Clear the $count $letter commands on this track first, or choose a replacement target.")
                    })
                })
            }
        })
    }

    fun writeParameter(session: EditorSession, menu: MenuContext, origin: ViewState, binding: StudioBinding,
        parameter: StudioParameter, value: Int, originValid: () -> Boolean = { true }) {
        val dest = destination(session, menu, origin) ?: return
        if (binding.instance != 0 && session.song.studio.tracks[dest.channel].inserts.none { it.id == binding.instance }) {
            menu.error("Focus a Pattern effect cell on the track that owns this pedal."); return
        }
        assign(session, menu, origin, dest, binding, parameter, value, originValid)
    }

    private fun assign(session: EditorSession, menu: MenuContext, origin: ViewState, dest: Destination,
        binding: StudioBinding, p: StudioParameter, value: Int, originValid: () -> Boolean = { true }) {
        if (!dest.valid(session, menu, origin)) { menu.error("Pattern target changed. Choose the cell again."); return }
        val song = session.song
        val lane = song.studio.tracks[dest.channel]
        val code = lane.bindings.entries.firstOrNull { it.value == binding }?.key ?: run {
            // A loaded project can contain unbound commands. Allocating their letter would change old rows.
            val used = BooleanArray(36)
            for (pattern in song.patterns) for (cell in pattern.tracks[dest.channel])
                for (col in 0..cell.more.size) used[cell.effectCode(col).coerceIn(0,35)] = true
            (14..35).firstOrNull { it !in lane.bindings && !used[it] }
        }
        if (code == null) { menu.error("All E–Z bindings are in use on this track."); return }
        val hex = p.nearestByte(value)
        fun write(byte: Int) {
            if (!originValid()) { menu.error("The instrument changed. Choose Write again."); return }
            if (!dest.valid(session, menu, origin)) { menu.error("Pattern target changed. Choose the cell again."); return }
            session.apply("Write ${binding.kind.faceName} ${p.name}") { doc ->
                val sound = doc.studio
                val track = sound.tracks[dest.channel]
                val bound = if (track.bindings[code] == binding) doc else doc.copy(sound = sound.copy(tracks =
                    sound.tracks.replaced(dest.channel, track.copy(bindings = track.bindings + (code to binding)))))
                withCommand(bound, dest, code, byte)
            }
            menu.status("${fxLetter(code)}${fxHex(byte)}  ${p.name}: ${p.display(p.fromHex(byte))}")
        }
        menu.menu("${fxLetter(code)} · ${binding.kind.faceName} ${p.name}", listOf(
            "Write ${fxLetter(code)}${fxHex(hex)} · ${p.display(p.fromHex(hex))}" to { write(hex) },
            "Enter another hex value…" to {
                menu.text("${p.name} · 00–FF", fxHex(hex), 2) { text ->
                    val byte = text.trim().toIntOrNull(16)
                    if (byte == null || byte !in 0..255) menu.error("Enter a hexadecimal byte, 00–FF.") else write(byte)
                }
            }))
    }

    fun writeSlice(session: EditorSession, menu: MenuContext, origin: ViewState, id: Int) {
        val dest = destination(session, menu, origin) ?: return
        menu.menu("Slice ${fxHex(id)} · row ${fxHex(dest.row)}, FX ${dest.column + 1}", listOf(
            "Write D${fxHex(id)}" to {
                if (dest.valid(session, menu, origin)) session.apply("Write slice ${fxHex(id)}") { withCommand(it, dest, 13, id) }
                else menu.error("Pattern target changed. Choose the cell again.")
                Unit
            }))
    }

    private fun withCommand(doc: Song, d: Destination, code: Int, byte: Int): Song {
        val pattern = doc.patternAt(d.order)
        val p = doc.patterns[pattern]
        val cell = p.tracks[d.channel][d.row]
        return doc.withPattern(pattern, p.withCell(d.channel, d.row, cell.withEffect(d.column, code, byte)))
    }
}
