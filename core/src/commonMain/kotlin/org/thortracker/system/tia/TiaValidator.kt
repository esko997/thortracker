package org.thortracker.system.tia

import org.thortracker.backend.TiaLowering
import org.thortracker.backend.TiaPlayerImage
import org.thortracker.backend.TiaPlayerImage.CHANNELS
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.model.Songs

/**
 * Atari 2600 song checks. [errors] mirror what `ti_load_image` (`tia/tia_image.c`, [TiaPlayerImage.LOADER_PROBLEMS])
 * rejects, as user-facing messages: the speed (1-255), the order list (1-256 orders, the loop inside it, 1-64 rows per
 * order), sound records ([TiaInstrument.problem]: the waveform 0-F, envelope shapes and values), cells (notes C-0..B-9
 * or OFF, defined sounds, effect codes and parameters: `Bxx` inside the order list, `Cxx` ≤ 0F, no `F00`, `Wxx` ≤ 0F)
 * and the data region's capacity; the region byte is the song's own (its system), and the rest of the loader's reasons
 * (magic, layout, reserved bytes, offsets, runs and masks) are what the lowering always writes correctly.
 * [warnings]: an order's first note without a sound, delays the speed never reaches, tonal notes outside their
 * waveform's span (they play its nearest end) and `Wxx` on a sound with a waveform envelope (ignored).
 */
object TiaValidator {
    private const val MAX_REPORTED = 40

    fun structuralErrors(song: Song): List<String> {
        val out = Report()
        val sound = song.sound as? TiaSound
        if (sound == null) {
            out += "The song is for ${song.system.name}, not the Atari 2600"
            return out.list()
        }
        if (song.ticksPerRow !in 1..255) out += "Speed ${song.ticksPerRow} is out of range; ticks per row must be 1-255"
        if (song.patterns.isEmpty()) out += "The song has no patterns; add one"
        song.patterns.forEachIndexed { p, pattern ->
            val label = Songs.patternLabel(song, p).cap()
            if (pattern.scale.root !in 0..11) out += "$label has scale root ${pattern.scale.root}; roots are 0-11 (C-B)"
            if (pattern.length !in 1..Songs.ROWS) out += "$label is ${pattern.length} rows long; lengths are 1-${Songs.ROWS}"
            if (pattern.tracks.size != CHANNELS) {
                out += "$label has ${pattern.tracks.size} channels; Atari 2600 patterns have $CHANNELS"
                return@forEachIndexed
            }
            pattern.tracks.forEachIndexed { ch, track ->
                if (track.size != Songs.ROWS) {
                    out += "$label, ${TiaRules.CHANNEL_NAMES[ch]} has ${track.size} rows; patterns have ${Songs.ROWS}"
                    return@forEachIndexed
                }
                track.forEachIndexed { row, cell ->
                    TiaRules.cellRangeProblem(cell)?.let { out += "$label, ${TiaRules.CHANNEL_NAMES[ch]} row $row: $it" }
                }
            }
        }
        if (song.orders.isEmpty()) out += "The order list is empty; add at least one order"
        song.orders.forEachIndexed { i, p -> if (p !in song.patterns.indices) out += "Order $i refers to missing pattern $p" }
        if (song.orders.isNotEmpty() && song.loopOrder !in song.orders.indices) {
            out += "Loop order ${song.loopOrder} is outside the order list (0-${song.orders.size - 1})"
        }
        sound.problem()?.let { out += "Song settings: $it" }
        sound.instruments.forEachIndexed { i, ins -> ins?.problem()?.let { out += "Sound ${hex2(i + 1)} \"${ins.name}\": $it" } }
        song.tuning?.problems(sound.region.system)?.forEach { out += it }
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
        val system = document.tiaSystem
        val song = system.arranged(document)
        val out = Report()
        if (song.orders.size > system.maxOrders) out += "The song has ${song.orders.size} orders; the Atari 2600 player plays at most ${system.maxOrders}"
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            pattern.tracks.forEachIndexed { ch, track ->
                for (row in 0 until pattern.length) {
                    cellProblem(song, ch, track[row])?.let { out += "$label, ${TiaRules.CHANNEL_NAMES[ch]} row $row: $it" }
                }
            }
        }
        if (out.isEmpty()) TiaLowering.sizeProblem(song)?.let { out += it }
        return out.list()
    }

    /** Why [cell] on [channel] cannot load, given the song (its order count as the player sees it and its sounds), or null. */
    fun cellProblem(song: Song, channel: Int, cell: Cell): String? {
        TiaRules.cellRangeProblem(cell)?.let { return it }
        if (cell.instrument != 0 && song.tia.instrument(cell.instrument) == null) {
            return "sound ${hex2(cell.instrument)} is empty; choose a preset for it or another sound"
        }
        val orders = song.orders.size
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code == 0 && param == 0) continue
            TiaRules.paramProblem(code, param, orders)?.let { return it }
        }
        return null
    }

    /** Legal but probably unintended details. */
    fun warnings(document: Song): List<String> {
        if (structuralErrors(document).isNotEmpty()) return emptyList()
        val song = document.tiaSystem.arranged(document)
        val out = Report()
        firstNotesWithoutInstrument(song).forEach { out += it }
        straightPlay(song, out)
        return out.list()
    }

    /**
     * Each order is also entered by the loop, a seek or a live edit, so its first note on a channel should name the
     * sound (otherwise it plays whatever that channel last had, possibly nothing on a fresh start).
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
                    out += "${Songs.patternLabel(song, p).cap()}, ${TiaRules.CHANNEL_NAMES[ch]} row $first: ${TiaSystem.noteText(cell.note)} " +
                        "has no sound; an order can be entered by the loop or a seek, so give its first note one"
                }
            }
        }
        return out
    }

    /**
     * Walks the arrangement once in order ([TiaWalk]: jumps ignored, sounds carried per channel) and reports note delays
     * the speed never reaches, tonal notes outside the span of the waveform they key on with (they play its nearest
     * end, AUDF 31 or 0) and `Wxx` on rows whose sound has a waveform envelope (the envelope wins).
     */
    private fun straightPlay(song: Song, out: Report) {
        val walk = TiaWalk(song)
        val region = song.tia.region
        song.orders.forEach { p ->
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            for (row in 0 until pattern.length) {
                for (ch in 0 until CHANNELS) {
                    val c = pattern.tracks[ch][row]
                    for (col in 0..c.more.size) {
                        if (c.effectCode(col) != TiaRules.FX_DELAY) continue
                        val d = c.effectParam(col)
                        // The speed this row plays at: earlier rows' and its own Fxx.
                        val speed = rowSpeed(walk.speed, pattern.tracks, row)
                        if (d >= speed) out += "$label, ${TiaRules.CHANNEL_NAMES[ch]} row $row: G${hex2(d)} waits $d ticks but rows are $speed ticks; the row never plays"
                    }
                }
                walk.row(p, row,
                    onKeyOn = { ch, k ->
                        if (k.raw || k.glide) return@row
                        val reach = TiaRules.reach(region, k.waveform) ?: return@row
                        if (k.note !in reach) {
                            val end = if (k.note < reach.first) "lowest, AUDF 31" else "highest, AUDF 0"
                            out += "$label, ${TiaRules.CHANNEL_NAMES[ch]} row $row: ${TiaSystem.noteText(k.note)} is outside waveform " +
                                "${TiaRules.audcText(k.waveform)}'s span ${TiaSystem.noteText(reach.first)}..${TiaSystem.noteText(reach.last)} " +
                                "on ${region.label}; it plays the $end (${k.hz?.let { TiaRules.hzText(it) } ?: ""})"
                        }
                    },
                    onRow = { ch, c ->
                        val ins = song.tia.instrument(walk.carried(ch)) ?: return@row
                        if (ins.waveforms.isEmpty) return@row
                        for (col in 0..c.more.size) if (c.effectCode(col) == TiaRules.FX_WAVEFORM) {
                            out += "$label, ${TiaRules.CHANNEL_NAMES[ch]} row $row: ${TiaRules.effectText(TiaRules.FX_WAVEFORM, c.effectParam(col))} " +
                                "is ignored: sound ${hex2(walk.carried(ch))} \"${ins.name}\" has a waveform envelope"
                            break
                        }
                    })
            }
        }
    }

    private fun rowSpeed(speed: Int, tracks: List<List<Cell>>, row: Int): Int {
        var s = speed
        for (t in tracks) {
            val c = t[row]
            for (col in 0..c.more.size) if (c.effectCode(col) == TiaRules.FX_SPEED && c.effectParam(col) > 0) s = c.effectParam(col)
        }
        return s
    }

    /** "Limits used" rows of the song check. */
    fun limits(song: Song): List<Pair<String, String>> = buildList {
        val used = HashSet<Int>()
        for (p in song.orders.toSet()) for (track in song.patterns[p].tracks) for (cell in track) if (cell.instrument > 0) used += cell.instrument
        val sound = song.tia
        val filled = sound.instruments.filterNotNull()
        add("Sounds" to "${used.size} used, ${filled.size} of ${TiaSystem.SLOTS} filled (${filled.count { it.raw }} raw)")
        add("Song data" to "${kb(TiaLowering.dataSize(song))} of ${kb(TiaPlayerImage.DATA_CAPACITY)} (patterns and envelopes)")
        add("Region" to "${sound.region.label}: ${NumberText.fixed(TiaRules.tickHz(sound.region), 2)} ticks per second")
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
