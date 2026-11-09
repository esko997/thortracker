package org.thortracker.system.opm

import org.thortracker.backend.OpmLowering
import org.thortracker.backend.OpmPlayerImage
import org.thortracker.backend.OpmPlayerImage.CHANNELS
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.model.Songs

/**
 * OPM song checks. [errors] mirror what `om_load_image` (`opm/opm_image.c`, [OpmPlayerImage.LOADER_PROBLEMS]) rejects,
 * as user-facing messages: the header's ranges (multispeed, speed, the shared LFO/CSM state: [OpmSound.problem]), the
 * order list, instrument records ([OpmInstrument.problem]), cells (notes D#0..D-8, defined instruments, a noise
 * instrument only on FM 8, effect codes and parameters, `Nxx` only on FM 8) and the data region's capacity; the
 * rest of the loader's reasons (magic, layout, reserved bytes, pattern offsets and runs) are what the lowering always
 * writes correctly. [warnings] are the specification's "Should"s (docs/AUDIO_ENGINE.md, "OPM").
 */
object OpmValidator {
    private const val MAX_REPORTED = 40

    fun structuralErrors(song: Song): List<String> {
        val out = Report()
        if (song.sound !is OpmSound) {
            out += "The song is for ${song.system.name}, not the OPM"
            return out.list()
        }
        val channels = OpmRules.CHANNEL_NAMES.size
        if (song.ticksPerRow !in 1..255) out += "Speed ${song.ticksPerRow} is out of range; ticks per row must be 1-255"
        if (song.patterns.isEmpty()) out += "The song has no patterns; add one"
        song.patterns.forEachIndexed { p, pattern ->
            val label = Songs.patternLabel(song, p).cap()
            if (pattern.scale.root !in 0..11) out += "$label has scale root ${pattern.scale.root}; roots are 0-11 (C-B)"
            if (pattern.length !in 1..Songs.ROWS) out += "$label is ${pattern.length} rows long; lengths are 1-${Songs.ROWS}"
            if (pattern.tracks.size != channels) {
                out += "$label has ${pattern.tracks.size} channels; OPM patterns have $channels"
                return@forEachIndexed
            }
            pattern.tracks.forEachIndexed { ch, track ->
                if (track.size != Songs.ROWS) {
                    out += "$label, ${OpmRules.CHANNEL_NAMES[ch]} has ${track.size} rows; patterns have ${Songs.ROWS}"
                    return@forEachIndexed
                }
                track.forEachIndexed { row, cell ->
                    OpmRules.cellRangeProblem(cell)?.let { out += "$label, ${OpmRules.CHANNEL_NAMES[ch]} row $row: $it" }
                }
            }
        }
        if (song.orders.isEmpty()) out += "The order list is empty; add at least one order"
        song.orders.forEachIndexed { i, p -> if (p !in song.patterns.indices) out += "Order $i refers to missing pattern $p" }
        if (song.orders.isNotEmpty() && song.loopOrder !in song.orders.indices) {
            out += "Loop order ${song.loopOrder} is outside the order list (0-${song.orders.size - 1})"
        }
        val sound = song.opm
        sound.problem()?.let { out += "Song settings: $it" }
        sound.instruments.forEachIndexed { i, ins -> ins?.problem()?.let { out += "Instrument ${hex2(i + 1)}: $it" } }
        song.tuning?.problems(OpmSystem)?.forEach { out += it }
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
        val song = OpmSystem.arranged(document)
        val out = Report()
        if (song.orders.size > OpmSystem.maxOrders) out += "The song has ${song.orders.size} orders; the OPM player plays at most ${OpmSystem.maxOrders}"
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            pattern.tracks.forEachIndexed { ch, track ->
                for (row in 0 until pattern.length) {
                    cellProblem(song, ch, track[row])?.let { out += "$label, ${OpmRules.CHANNEL_NAMES[ch]} row $row: $it" }
                }
            }
        }
        if (out.isEmpty()) {
            val size = OpmLowering.dataSize(song)
            if (size > OpmPlayerImage.DATA_CAPACITY) {
                out += "The song's pattern data needs ${kb(size)}; the OPM image holds ${kb(OpmPlayerImage.DATA_CAPACITY)}. " +
                    "Reuse patterns or shorten the song"
            }
        }
        return out.list()
    }

    /**
     * Why [cell] on [channel] cannot load, given the song (its order count as the player sees it and instruments), or
     * null.
     */
    fun cellProblem(song: Song, channel: Int, cell: Cell): String? {
        OpmRules.cellRangeProblem(cell)?.let { return it }
        if (cell.instrument != 0) {
            val ins = song.opm.instrument(cell.instrument)
                ?: return "instrument ${hex2(cell.instrument)} is empty; choose a preset for it or another instrument"
            if (ins.noise && channel != OpmRules.NOISE_CHANNEL) {
                return "instrument ${hex2(cell.instrument)} \"${ins.name}\" plays the noise generator; the YM2151 wires noise " +
                    "to FM 8 only, so noise instruments belong on FM 8"
            }
        }
        val orders = song.orders.size
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code == 0 && param == 0) continue
            OpmRules.paramProblem(code, param, orders, channel)?.let { return it }
        }
        return null
    }

    /** Legal but probably unintended details. */
    fun warnings(document: Song): List<String> {
        if (structuralErrors(document).isNotEmpty()) return emptyList()
        val song = OpmSystem.arranged(document)
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
                    out += "${Songs.patternLabel(song, p).cap()}, ${OpmRules.CHANNEL_NAMES[ch]} row $first: ${OpmSystem.noteName(cell.note)} " +
                        "has no instrument; an order can be entered by the loop or a seek, so give its first note one"
                }
            }
        }
        return out
    }

    /**
     * Walks the arrangement once in order (jumps, delays and cuts ignored) and reports note locks on rows without a
     * note and note delays the speed never reaches; then, for the whole song, instruments (or `Hxy`) that take the
     * shared LFO while its depth stays 0 everywhere (they sound without it), and `Qxx` timer changes in a song where
     * CSM is never on (timer A only matters for CSM).
     */
    private fun straightPlay(song: Song, out: Report) {
        val sound = song.opm
        val instrument = IntArray(CHANNELS)
        var speed = song.ticksPerRow
        var amdUsed = sound.amd > 0
        var pmdUsed = sound.pmd > 0
        var csmUsed = sound.csm
        var amsWhere: String? = null
        var pmsWhere: String? = null
        var timerWhere: String? = null
        song.orders.forEach { p ->
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            for (row in 0 until pattern.length) {
                for (ch in 0 until CHANNELS) {
                    val c = pattern.tracks[ch][row]
                    for (col in 0..c.more.size) if (c.effectCode(col) == OpmRules.FX_SPEED && c.effectParam(col) > 0) speed = c.effectParam(col)
                }
                for (ch in 0 until CHANNELS) {
                    val c = pattern.tracks[ch][row]
                    val where = "$label, ${OpmRules.CHANNEL_NAMES[ch]} row $row"
                    if (c.instrument != 0) instrument[ch] = c.instrument
                    var noteFx: Char? = null
                    var sens: Int? = null
                    for (col in 0..c.more.size) {
                        val code = c.effectCode(col)
                        val param = c.effectParam(col)
                        if (code == 0 && param == 0) continue
                        if (code in OpmRules.NOTE_EFFECTS && noteFx == null) noteFx = OpmRules.effectText(code, param)[0]
                        when (code) {
                            OpmRules.FX_DELAY -> if (param >= speed) out += "$where: G${hex2(param)} waits $param ticks but rows are $speed ticks; the row never plays"
                            OpmRules.FX_SENSITIVITY -> sens = param
                            OpmRules.FX_AMD -> if (param > 0) amdUsed = true
                            OpmRules.FX_PMD -> if (param > 0) pmdUsed = true
                            OpmRules.FX_CSM -> if (param == 1) csmUsed = true
                            OpmRules.FX_TIMER_A -> if (timerWhere == null) timerWhere = where
                        }
                    }
                    if (c.note < 0 && noteFx != null) out += "$where: ${noteFx}xx applies when a note starts; this row has no note, so it is ignored"
                    sens?.let {
                        if (it and 3 != 0 && amsWhere == null) amsWhere = "$where (H${hex2(it)})"
                        if (it shr 4 != 0 && pmsWhere == null) pmsWhere = "$where (H${hex2(it)})"
                    }
                    if (c.note >= 0 && sens == null) {
                        val ins = sound.instrument(instrument[ch]) ?: continue
                        val name = "instrument ${hex2(instrument[ch])} \"${ins.name}\""
                        if (ins.ams != 0 && ins.operators.any { it.am } && amsWhere == null) amsWhere = "$where ($name)"
                        if (ins.pms != 0 && pmsWhere == null) pmsWhere = "$where ($name)"
                    }
                }
            }
        }
        if (!amdUsed) amsWhere?.let {
            out += "$it takes the LFO's amplitude (AMS) but the LFO amplitude depth (AMD) is 0 in the whole song, so it has " +
                "no tremolo; set AMD in the Song view or with Txx"
        }
        if (!pmdUsed) pmsWhere?.let {
            out += "$it takes the LFO's pitch (PMS) but the LFO pitch depth (PMD) is 0 in the whole song, so it has no " +
                "vibrato; set PMD in the Song view or with Vxx"
        }
        if (!csmUsed) timerWhere?.let {
            out += "$it: Qxx sets timer A, which only CSM uses, but CSM is never on in this song (turn it on in the Song view or with X01)"
        }
    }

    /** "Limits used" rows of the song check. */
    fun limits(song: Song): List<Pair<String, String>> = buildList {
        val used = HashSet<Int>()
        for (p in song.orders.toSet()) for (track in song.patterns[p].tracks) for (cell in track) if (cell.instrument > 0) used += cell.instrument
        val sound = song.opm
        val noise = sound.instruments.count { it?.noise == true }
        add("Instruments" to "${used.size} used, ${sound.instruments.count { it != null }} of ${OpmSystem.SLOTS} filled ($noise noise, FM 8 only)")
        add("Pattern data" to "${kb(OpmLowering.dataSize(song))} of ${kb(OpmPlayerImage.DATA_CAPACITY)}")
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
