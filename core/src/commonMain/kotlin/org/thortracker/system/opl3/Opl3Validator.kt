package org.thortracker.system.opl3

import org.thortracker.backend.Opl3PlayerImage
import org.thortracker.backend.Opl3PlayerImage.CHANNELS
import org.thortracker.backend.Opl3Lowering
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.model.Songs

/**
 * OPL3 song checks. [errors] mirror what `o3_load_image` (`opl3/opl3_image.c`) rejects, as user-facing messages;
 * [warnings] are the specification's "Should"s and chord-pool pressure (docs/AUDIO_ENGINE.md, "OPL3").
 */
object Opl3Validator {
    private const val MAX_REPORTED = 40

    fun structuralErrors(song: Song): List<String> {
        val out = Report()
        if (song.sound !is Opl3Sound) {
            out += "The song is for ${song.system.name}, not the OPL3"
            return out.list()
        }
        val channels = Opl3Rules.CHANNEL_NAMES.size
        if (song.ticksPerRow !in 1..255) out += "Speed ${song.ticksPerRow} is out of range; ticks per row must be 1-255"
        if (song.patterns.isEmpty()) out += "The song has no patterns; add one"
        song.patterns.forEachIndexed { p, pattern ->
            val label = Songs.patternLabel(song, p).cap()
            if (pattern.scale.root !in 0..11) out += "$label has scale root ${pattern.scale.root}; roots are 0-11 (C-B)"
            if (pattern.length !in 1..Songs.ROWS) out += "$label is ${pattern.length} rows long; lengths are 1-${Songs.ROWS}"
            if (pattern.tracks.size != channels) {
                out += "$label has ${pattern.tracks.size} channels; OPL3 patterns have $channels"
                return@forEachIndexed
            }
            pattern.tracks.forEachIndexed { ch, track ->
                if (track.size != Songs.ROWS) {
                    out += "$label, ${Opl3Rules.CHANNEL_NAMES[ch]} has ${track.size} rows; patterns have ${Songs.ROWS}"
                    return@forEachIndexed
                }
                track.forEachIndexed { row, cell ->
                    Opl3Rules.cellRangeProblem(cell)?.let { out += "$label, ${Opl3Rules.CHANNEL_NAMES[ch]} row $row: $it" }
                }
            }
        }
        if (song.orders.isEmpty()) out += "The order list is empty; add at least one order"
        song.orders.forEachIndexed { i, p -> if (p !in song.patterns.indices) out += "Order $i refers to missing pattern $p" }
        if (song.orders.isNotEmpty() && song.loopOrder !in song.orders.indices) {
            out += "Loop order ${song.loopOrder} is outside the order list (0-${song.orders.size - 1})"
        }
        val sound = song.opl3
        if (sound.instruments.size != Opl3System.SLOTS) {
            out += "The song has ${sound.instruments.size} instrument slots; OPL3 songs have exactly ${Opl3System.SLOTS}"
        }
        sound.instruments.forEachIndexed { i, ins -> ins?.problem()?.let { out += "Instrument ${hex2(i + 1)}: $it" } }
        if (sound.chords.size > Opl3System.MAX_CHORDS) out += "The song has ${sound.chords.size} chord shapes; at most ${Opl3System.MAX_CHORDS}"
        sound.chords.forEachIndexed { k, chord -> chord.problem()?.let { out += "Chord shape ${hex2(k + 1)}: $it" } }
        if (sound.multispeed !in 1..Opl3System.MAX_MULTISPEED) out += "Multispeed ${sound.multispeed} is outside 1-${Opl3System.MAX_MULTISPEED}"
        song.tuning?.problems(Opl3System)?.forEach { out += it }
        if (song.newPatternScale.root !in 0..11) out += "The scale for new patterns has root ${song.newPatternScale.root}; roots are 0-11 (C-B)"
        if (song.parkedOrders.any { it !in song.orders.indices } || song.parkedOrders.zipWithNext().any { (a, b) -> a >= b }) {
            out += "Parked orders ${song.parkedOrders} must be ascending, distinct orders 0-${song.orders.size - 1}"
        }
        return out.list()
    }

    /** Problems that block playing or exporting [document]'s arrangement (its unparked orders). */
    fun errors(document: Song): List<String> {
        val structural = structuralErrors(document)
        if (structural.isNotEmpty()) return structural
        if (document.parkedOrders.size == document.orders.size) {
            return listOf("Every order is parked; unpark at least one to play the song or export it")
        }
        val song = Opl3System.arranged(document)
        val out = Report()
        if (song.orders.size > Opl3System.maxOrders) out += "The song has ${song.orders.size} orders; the OPL3 player plays at most ${Opl3System.maxOrders}"
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            pattern.tracks.forEachIndexed { ch, track ->
                for (row in 0 until pattern.length) {
                    cellProblem(song, ch, track[row])?.let { out += "$label, ${Opl3Rules.CHANNEL_NAMES[ch]} row $row: $it" }
                }
            }
        }
        if (out.isEmpty()) {
            val size = Opl3Lowering.dataSize(song)
            if (size > Opl3PlayerImage.DATA_CAPACITY) {
                out += "The song's pattern data needs ${kb(size)}; the OPL3 image holds ${kb(Opl3PlayerImage.DATA_CAPACITY)}. " +
                    "Reuse patterns or shorten the song"
            }
        }
        return out.list()
    }

    /**
     * Why [cell] on [channel] cannot load, given the song (its order count as the player sees it, chord shapes and
     * instruments), or null.
     */
    fun cellProblem(song: Song, channel: Int, cell: Cell): String? {
        Opl3Rules.cellRangeProblem(cell)?.let { return it }
        val sound = song.opl3
        if (cell.instrument != 0) {
            val ins = sound.instrument(cell.instrument)
                ?: return "instrument ${hex2(cell.instrument)} is empty; choose a preset for it or another instrument"
            if (ins.fourOp && channel >= Opl3Rules.FOUR_OP_CHANNELS) {
                return "instrument ${hex2(cell.instrument)} \"${ins.name}\" is 4-op; channels FM 7-9 play 2-op instruments only (4-op: FM 1-6)"
            }
        }
        val orders = song.orders.size
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code == 0 && param == 0) continue
            Opl3Rules.paramProblem(code, param, orders, sound.chords.size)?.let { return it }
        }
        return null
    }

    /** Legal but probably unintended details. */
    fun warnings(document: Song): List<String> {
        if (structuralErrors(document).isNotEmpty()) return emptyList()
        val song = Opl3System.arranged(document)
        val out = Report()
        firstNotesWithoutInstrument(song).forEach { out += it }
        straightPlay(song, out)
        return out.list()
    }

    /**
     * Each order is also entered by the loop, a seek or a live edit, so its first note on a channel should name the
     * instrument (otherwise it plays whatever that channel last had, possibly nothing on a fresh start).
     */
    private fun firstNotesWithoutInstrument(song: Song): List<String> {
        val out = ArrayList<String>()
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            for (ch in 0 until CHANNELS) {
                val track = pattern.tracks[ch]
                val first = (0 until pattern.length).firstOrNull { track[it].note >= 0 || track[it].instrument != 0 } ?: continue
                val cell = track[first]
                if (cell.note >= 0 && cell.instrument == 0) {
                    out += "${Songs.patternLabel(song, p).cap()}, ${Opl3Rules.CHANNEL_NAMES[ch]} row $first: ${Opl3System.noteName(cell.note)} " +
                        "has no instrument; an order can be entered by the loop or a seek, so give its first note one"
                }
            }
        }
        return out
    }

    /**
     * Walks the arrangement once in order (jumps, delays and cuts ignored) and reports note-only effects on rows
     * without a note, chords on 4-op instruments, note delays the speed never reaches and rows whose chords need more
     * spare voices than the pool has (the player then steals the oldest chord notes).
     */
    private fun straightPlay(song: Song, out: Report) {
        val sound = song.opl3
        val instrument = IntArray(CHANNELS)
        val keyed = BooleanArray(CHANNELS)
        val fourOp = BooleanArray(CHANNELS)
        val chordVoices = IntArray(CHANNELS)
        var speed = song.ticksPerRow
        var warnedPool = 0
        song.orders.forEachIndexed { o, p ->
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            for (row in 0 until pattern.length) {
                for (ch in 0 until CHANNELS) {
                    val c = pattern.tracks[ch][row]
                    for (col in 0..c.more.size) if (c.effectCode(col) == Opl3Rules.FX_SPEED && c.effectParam(col) > 0) speed = c.effectParam(col)
                }
                for (ch in 0 until CHANNELS) {
                    val c = pattern.tracks[ch][row]
                    val where = "$label, ${Opl3Rules.CHANNEL_NAMES[ch]} row $row"
                    if (c.instrument != 0) instrument[ch] = c.instrument
                    var chord = 0
                    var noteFx: Char? = null
                    for (col in 0..c.more.size) {
                        val code = c.effectCode(col)
                        val param = c.effectParam(col)
                        if (code == 0 && param == 0) continue
                        if (code in Opl3Rules.NOTE_EFFECTS && noteFx == null) noteFx = Opl3Rules.effectText(code, param)[0]
                        when (code) {
                            Opl3Rules.FX_CHORD -> chord = listOf(param shr 4, param and 15).count { it != 0 }
                            Opl3Rules.FX_SHAPE -> chord = sound.chords.getOrNull(param - 1)?.intervals?.size ?: 0
                            Opl3Rules.FX_DELAY -> if (param >= speed) out += "$where: G${hex2(param)} waits $param ticks but rows are $speed ticks; the row never plays"
                        }
                    }
                    val ins = sound.instrument(instrument[ch])
                    if (c.note >= 0) {
                        if (chord > 0 && ins != null && ins.fourOp) {
                            out += "$where: chords play only with 2-op instruments; \"${ins.name}\" is 4-op, so only the root sounds"
                            chord = 0
                        }
                        if (ins != null) {
                            keyed[ch] = true
                            if (ch < Opl3Rules.FOUR_OP_CHANNELS) fourOp[ch] = ins.fourOp
                            chordVoices[ch] = chord
                        }
                    } else {
                        if (noteFx != null) out += "$where: ${noteFx}xx applies when a note starts; this row has no note, so it is ignored"
                        if (c.note == Cell.CUT) { keyed[ch] = false; chordVoices[ch] = 0 }
                    }
                }
                val pool = Opl3PlayerImage.POOL - Opl3PlayerImage.FOUR_OP_CHANNELS + (0 until Opl3PlayerImage.FOUR_OP_CHANNELS).count { !fourOp[it] }
                val need = (0 until CHANNELS).sumOf { if (keyed[it]) chordVoices[it] else 0 }
                if (need > pool && warnedPool < 3) {
                    warnedPool++
                    out += "Order ${hex2(o)} row $row: chords need $need spare voices but ${pool} are free (4-op instruments use up to six); " +
                        "the oldest chord notes are cut. End chords with OFF or use fewer 4-op channels"
                }
            }
        }
    }

    /** "Limits used" rows of the song check. */
    fun limits(song: Song): List<Pair<String, String>> = buildList {
        val used = HashSet<Int>()
        for (p in song.orders.toSet()) for (track in song.patterns[p].tracks) for (cell in track) if (cell.instrument > 0) used += cell.instrument
        val sound = song.opl3
        add("Instruments" to "${used.size} used, ${sound.instruments.count { it != null }} of ${Opl3System.SLOTS} filled")
        add("Chord shapes" to "${sound.chords.size} of ${Opl3System.MAX_CHORDS}")
        add("Pattern data" to "${kb(Opl3Lowering.dataSize(song))} of ${kb(Opl3PlayerImage.DATA_CAPACITY)}")
    }

    private fun kb(bytes: Int) = NumberText.fixed(bytes / 1024.0, 1) + " KB"

    private fun hex2(v: Int) = NumberText.hex(v, 2)

    private fun String.cap() = replaceFirstChar { it.uppercase() }

    private class Report {
        private val items = ArrayList<String>()
        private var dropped = 0
        operator fun plusAssign(message: String) {
            if (items.size < MAX_REPORTED) items += message else dropped++
        }
        fun isEmpty() = items.isEmpty()
        fun list(): List<String> = if (dropped == 0) items else items + "...and $dropped more"
    }
}
