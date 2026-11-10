package org.thortracker.system.vsu

import org.thortracker.backend.VsuLowering
import org.thortracker.backend.VsuPlayerImage
import org.thortracker.backend.VsuPlayerImage.CHANNELS
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.model.Songs

/**
 * Virtual Boy song checks. [errors] mirror what `vb_load_image` (`vsu/vsu_image.c`, [VsuPlayerImage.LOADER_CHECKS])
 * rejects, as user-facing messages; [warnings] are the honest notes about VSU behaviour the player and hardware cause
 * (docs/AUDIO_ENGINE.md, "Virtual Boy VSU"): rows delayed past the speed, locks that do nothing, Wave 5 notes the
 * sweep-overflow rule stops at once, sweep/modulation on lanes without it, silent levels.
 */
object VsuValidator {
    private const val MAX_REPORTED = 40

    fun structuralErrors(song: Song): List<String> {
        val out = Report()
        if (song.sound !is VsuSound) {
            out += "The song is for ${song.system.name}, not the Virtual Boy"
            return out.list()
        }
        if (song.ticksPerRow !in 1..255) out += "Speed ${song.ticksPerRow} is out of range; ticks per row must be 1-255"
        if (song.patterns.isEmpty()) out += "The song has no patterns; add one"
        song.patterns.forEachIndexed { p, pattern ->
            val label = Songs.patternLabel(song, p).cap()
            if (pattern.scale.root !in 0..11) out += "$label has scale root ${pattern.scale.root}; roots are 0-11 (C-B)"
            if (pattern.length !in 1..Songs.ROWS) out += "$label is ${pattern.length} rows long; lengths are 1-${Songs.ROWS}"
            if (pattern.tracks.size != CHANNELS) {
                out += "$label has ${pattern.tracks.size} lanes; Virtual Boy patterns have $CHANNELS"
                return@forEachIndexed
            }
            pattern.tracks.forEachIndexed { ch, track ->
                if (track.size != Songs.ROWS) {
                    out += "$label, ${VsuRules.CHANNEL_NAMES[ch]} has ${track.size} rows; patterns have ${Songs.ROWS}"
                    return@forEachIndexed
                }
                track.forEachIndexed { row, cell ->
                    VsuRules.cellRangeProblem(cell)?.let { out += "$label, ${VsuRules.CHANNEL_NAMES[ch]} row $row: $it" }
                }
            }
        }
        if (song.orders.isEmpty()) out += "The order list is empty; add at least one order"
        song.orders.forEachIndexed { i, p -> if (p !in song.patterns.indices) out += "Order $i refers to missing pattern $p" }
        if (song.orders.isNotEmpty() && song.loopOrder !in song.orders.indices) {
            out += "Loop order ${song.loopOrder} is outside the order list (0-${song.orders.size - 1})"
        }
        val sound = song.vsu
        if (sound.instruments.size != VsuSystem.SLOTS) {
            out += "The song has ${sound.instruments.size} instrument slots; Virtual Boy songs have exactly ${VsuSystem.SLOTS}"
        }
        sound.instruments.forEachIndexed { i, ins -> ins?.problem()?.let { out += "Instrument ${hex2(i + 1)}: $it" } }
        if (sound.waves.size != VsuPlayerImage.WAVES) out += "The song has ${sound.waves.size} waves; the VSU has exactly ${VsuPlayerImage.WAVES}"
        sound.waves.forEachIndexed { w, samples ->
            if (samples.size != VsuPlayerImage.WAVE_SAMPLES) out += "Wave ${w + 1} has ${samples.size} samples; waves have ${VsuPlayerImage.WAVE_SAMPLES}"
            samples.firstOrNull { it !in 0..VsuPlayerImage.SAMPLE_MAX }?.let { out += "Wave ${w + 1} has sample $it; samples are 0-63" }
        }
        if (sound.modulation.size != VsuPlayerImage.MOD_VALUES) {
            out += "The modulation table has ${sound.modulation.size} values; it has exactly ${VsuPlayerImage.MOD_VALUES}"
        }
        sound.modulation.firstOrNull { it !in -128..127 }?.let { out += "The modulation table has value $it; values are -128..127" }
        song.tuning?.problems(VsuSystem)?.forEach { out += it }
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
        val song = VsuSystem.arranged(document)
        val out = Report()
        if (song.orders.size > VsuSystem.maxOrders) out += "The song has ${song.orders.size} orders; the VSU player plays at most ${VsuSystem.maxOrders}"
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            pattern.tracks.forEachIndexed { ch, track ->
                for (row in 0 until pattern.length) {
                    cellProblem(song, ch, track[row])?.let { out += "$label, ${VsuRules.CHANNEL_NAMES[ch]} row $row: $it" }
                }
            }
        }
        if (out.isEmpty()) {
            val size = VsuLowering.dataSize(song)
            if (size > VsuPlayerImage.DATA_CAPACITY) {
                out += "The song's pattern data needs ${kb(size)}; the VSU image holds ${kb(VsuPlayerImage.DATA_CAPACITY)}. " +
                    "Reuse patterns or shorten the song"
            }
        }
        return out.list()
    }

    /**
     * Why [cell] on lane [channel] cannot load, given the song (its order count as the player sees it and its
     * instruments), or null: the lane's note range and effects, a defined instrument, the effect parameters.
     */
    fun cellProblem(song: Song, channel: Int, cell: Cell): String? {
        VsuRules.cellRangeProblem(cell)?.let { return it }
        VsuRules.laneProblem(channel, cell)?.let { return it }
        if (cell.instrument != 0 && song.vsu.instrument(cell.instrument) == null) {
            return "instrument ${hex2(cell.instrument)} is empty; choose a preset for it or another instrument"
        }
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code == 0 && param == 0) continue
            VsuRules.paramProblem(code, param, song.orders.size)?.let { return it }
        }
        return null
    }

    /** Legal but probably unintended details. */
    fun warnings(document: Song): List<String> {
        if (structuralErrors(document).isNotEmpty()) return emptyList()
        val song = VsuSystem.arranged(document)
        val out = Report()
        firstNotesWithoutInstrument(song).forEach { out += it }
        val walk = VsuWalk(song) { out += it }
        song.orders.forEachIndexed { o, p ->
            val pattern = song.patterns[p]
            for (row in 0 until pattern.length) walk.row(o, p, pattern, row)
        }
        return out.list()
    }

    /**
     * Each order is also entered by the loop, a seek or a live edit, so its first note on a lane should name the
     * instrument (otherwise it plays whatever that lane last had, possibly nothing on a fresh start).
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
                    out += "${Songs.patternLabel(song, p).cap()}, ${VsuRules.CHANNEL_NAMES[ch]} row $first: ${VsuSystem.noteName(cell.note)} " +
                        "has no instrument; an order can be entered by the loop or a seek, so give its first note one"
                }
            }
        }
        return out
    }

    /** "Limits used" rows of the song check. */
    fun limits(song: Song): List<Pair<String, String>> = buildList {
        val used = HashSet<Int>()
        for (p in song.orders.toSet()) for (track in song.patterns[p].tracks) for (cell in track) if (cell.instrument > 0) used += cell.instrument
        val sound = song.vsu
        add("Instruments" to "${used.size} used, ${sound.instruments.count { it != null }} of ${VsuSystem.SLOTS} filled")
        val waves = (0 until VsuPlayerImage.WAVES).count { VsuWaves.lanesPlaying(song, it).isNotEmpty() }
        add("Waves" to "$waves of ${VsuPlayerImage.WAVES} played (shared by every lane)")
        add("Pattern data" to "${kb(VsuLowering.dataSize(song))} of ${kb(VsuPlayerImage.DATA_CAPACITY)}")
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
