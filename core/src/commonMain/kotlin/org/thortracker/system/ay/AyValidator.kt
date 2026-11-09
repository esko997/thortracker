package org.thortracker.system.ay

import org.thortracker.backend.AyLowering
import org.thortracker.backend.AyPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.EffectSpec

/**
 * AY-3-8910 / YM2149 song checks. [errors] mirror what `ay_load_image` (`ay/ay_image.c`, [AyPlayerImage.LOADER_PROBLEMS])
 * rejects, as user-facing messages: the header (speed, orders, loop, instrument count, data size), every instrument
 * field and envelope ([AyInstrument.problem]), and every row (note, defined instrument, effect count, each effect's
 * load-time check, [AyRules.paramProblem]). [warnings] are the specification's "Should"s (docs/AUDIO_ENGINE.md,
 * "AY-3-8910 / YM2149") and the shared noise/envelope handovers straight play finds ([AyStraightPlay]).
 */
object AyValidator {
    private const val MAX_REPORTED = 40

    /** Each kind of row-level warning is reported this often at most (the first rows), so others stay visible. */
    private const val PER_KIND = 3

    fun structuralErrors(song: Song): List<String> {
        val out = Report()
        val sound = song.sound as? AySound
        if (sound == null) {
            out += "The song is for ${song.system.name}, not an AY-3-8910 / YM2149 computer"
            return out.list()
        }
        val channels = AyRules.CHANNELS
        if (song.ticksPerRow !in 1..255) out += "Speed ${song.ticksPerRow} is out of range; ticks per row must be 1-255"
        if (song.patterns.isEmpty()) out += "The song has no patterns; add one"
        song.patterns.forEachIndexed { p, pattern ->
            val label = Songs.patternLabel(song, p).cap()
            if (pattern.scale.root !in 0..11) out += "$label has scale root ${pattern.scale.root}; roots are 0-11 (C-B)"
            if (pattern.length !in 1..Songs.ROWS) out += "$label is ${pattern.length} rows long; lengths are 1-${Songs.ROWS}"
            if (pattern.tracks.size != channels) {
                out += "$label has ${pattern.tracks.size} channels; AY patterns have $channels"
                return@forEachIndexed
            }
            pattern.tracks.forEachIndexed { ch, track ->
                if (track.size != Songs.ROWS) {
                    out += "$label, ${AyRules.CHANNEL_NAMES[ch]} has ${track.size} rows; patterns have ${Songs.ROWS}"
                    return@forEachIndexed
                }
                track.forEachIndexed { row, cell ->
                    AyRules.cellRangeProblem(cell)?.let { out += "$label, ${AyRules.CHANNEL_NAMES[ch]} row $row: $it" }
                }
            }
        }
        if (song.orders.isEmpty()) out += "The order list is empty; add at least one order"
        song.orders.forEachIndexed { i, p -> if (p !in song.patterns.indices) out += "Order $i refers to missing pattern $p" }
        if (song.orders.isNotEmpty() && song.loopOrder !in song.orders.indices) {
            out += "Loop order ${song.loopOrder} is outside the order list (0-${song.orders.size - 1})"
        }
        if (sound.instruments.size != AySystem.SLOTS) {
            out += "The song has ${sound.instruments.size} sound slots; AY songs have exactly ${AySystem.SLOTS}"
        }
        sound.instruments.forEachIndexed { i, ins -> ins?.problem()?.let { out += "Sound ${hex2(i + 1)}: $it" } }
        song.tuning?.problems(sound.variant.system)?.forEach { out += it }
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
        val system = document.ay.variant.system
        val song = system.arranged(document)
        val out = Report()
        if (song.orders.size > system.maxOrders) out += "The song has ${song.orders.size} orders; the AY player plays at most ${system.maxOrders}"
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            pattern.tracks.forEachIndexed { ch, track ->
                for (row in 0 until pattern.length) {
                    cellProblem(song, ch, track[row])?.let { out += "$label, ${AyRules.CHANNEL_NAMES[ch]} row $row: $it" }
                }
            }
        }
        if (out.isEmpty()) {
            val size = AyLowering.dataSize(song)
            if (size > AyPlayerImage.DATA_CAPACITY) {
                out += "The song's pattern and envelope data need ${kb(size)}; the AY image holds ${kb(AyPlayerImage.DATA_CAPACITY)}. " +
                    "Reuse patterns or shorten the song"
            }
        }
        return out.list()
    }

    /** Why [cell] on [channel] cannot load, given the song (its order count as the player sees it and its sounds), or null. */
    fun cellProblem(song: Song, channel: Int, cell: Cell): String? {
        AyRules.cellRangeProblem(cell)?.let { return it }
        if (cell.instrument != 0 && song.ay.instrument(cell.instrument) == null) {
            return "sound ${hex2(cell.instrument)} is empty; choose a preset for it or another sound"
        }
        val orders = song.orders.size
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code == 0 && param == 0) continue
            AyRules.paramProblem(channel, code, param, orders)?.let { return it }
        }
        return null
    }

    /** Legal but probably unintended details. */
    fun warnings(document: Song): List<String> {
        if (structuralErrors(document).isNotEmpty()) return emptyList()
        if (document.parkedOrders.size == document.orders.size) return emptyList()
        val system = document.ay.variant.system
        val song = system.arranged(document)
        val out = Report()
        firstNotesWithoutInstrument(song).forEach { out += it }
        lowNotes(song, system).forEach { out += it }
        straightPlay(song, system, out)
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
            for (ch in 0 until AyRules.CHANNELS) {
                val track = pattern.tracks[ch]
                val first = (0 until pattern.length).firstOrNull { track[it].note >= 0 || track[it].instrument != 0 } ?: continue
                val cell = track[first]
                if (cell.note >= 0 && cell.instrument == 0) {
                    out += "${Songs.patternLabel(song, p).cap()}, ${AyRules.CHANNEL_NAMES[ch]} row $first: ${song.system.noteName(cell.note)} " +
                        "has no sound; an order can be entered by the loop or a seek, so give its first note one"
                }
            }
        }
        return out
    }

    /** Notes below the variant's lowest reachable note: the 12-bit tone period clamps at 4095. */
    private fun lowNotes(song: Song, system: AySystem): List<String> {
        val out = ArrayList<String>()
        val lowest = system.noteRange.first
        val hz = NumberText.fixed(AyRules.toneHz(system.variant, AyPlayerImage.TONE_PERIOD_MAX), 1)
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            for (ch in 0 until AyRules.CHANNELS) for (row in 0 until pattern.length) {
                val note = pattern.tracks[ch][row].note
                if (note in 0 until lowest && out.size < PER_KIND) {
                    out += "${Songs.patternLabel(song, p).cap()}, ${AyRules.CHANNEL_NAMES[ch]} row $row: ${system.noteName(note)} is below the " +
                        "${system.variant.label}'s lowest note (${system.noteName(lowest)}); it sounds at the longest tone period, $hz Hz"
                }
            }
        }
        return out
    }

    /**
     * Straight play of the arrangement ([AyStraightPlay]): note delays the speed never reaches, note settings on rows
     * where the channel plays nothing, envelope periods (`Hxx`, `Ixx`, `Jxx`) for notes without an envelope shape, and
     * key-ons that take the shared envelope or noise period from a channel still using it differently.
     */
    private fun straightPlay(song: Song, system: AySystem, out: Report) {
        val play = AyStraightPlay(song)
        play.run(song.orders.indices.toList())
        val counts = IntArray(5)
        for (e in play.events) {
            val kind = when (e) {
                is AyStraightPlay.DelayNeverPlays -> 0
                is AyStraightPlay.IdleSettings -> 1
                is AyStraightPlay.PeriodWithoutShape -> 2
                is AyStraightPlay.EnvelopeTaken -> 3
                is AyStraightPlay.NoiseTaken -> 4
            }
            if (counts[kind] >= PER_KIND) continue
            counts[kind]++
            val pattern = song.orders[e.order]
            val where = "${Songs.patternLabel(song, pattern).cap()}, ${AyRules.CHANNEL_NAMES[e.channel]} row ${e.row}"
            val at = "${hex2(e.order)}:${hex2(e.row)}"
            out += when (e) {
                is AyStraightPlay.DelayNeverPlays -> "$where: G${hex2(e.delay)} waits ${e.delay} ticks but rows are ${e.speed} ticks; the row never plays"
                is AyStraightPlay.IdleSettings -> "$where: ${EffectSpec.charOf(e.code)}xx is a note setting, but nothing plays on this channel " +
                    "here, so it is ignored; put it on the row of the note it is for"
                is AyStraightPlay.PeriodWithoutShape -> "$where: ${EffectSpec.charOf(e.code)}xx sets the envelope period, but the note has no " +
                    "envelope shape (its sound has none and no Exx chose one), so it does nothing; add E08-E0F"
                is AyStraightPlay.EnvelopeTaken -> {
                    val taker = AyRules.CHANNEL_NAMES[e.channel]
                    val from = AyRules.CHANNEL_NAMES[e.from]
                    "$taker's note at $at takes the envelope (${e.taker.text(system)}) from $from's held note (${e.held.text(system)}); " +
                        "${AyRules.CHANNEL_LETTERS[e.from]} plays ${AyRules.CHANNEL_LETTERS[e.channel]}'s envelope until " +
                        "${AyRules.CHANNEL_LETTERS[e.channel]}'s note ends"
                }
                is AyStraightPlay.NoiseTaken -> {
                    val taker = AyRules.CHANNEL_NAMES[e.channel]
                    val from = AyRules.CHANNEL_NAMES[e.from]
                    "$taker's note at $at takes the noise generator (period ${e.period}) from $from's held note (period ${e.held}); " +
                        "${AyRules.CHANNEL_LETTERS[e.from]} plays noise period ${e.period} until ${AyRules.CHANNEL_LETTERS[e.channel]}'s note ends"
                }
            }
        }
    }

    /** "Limits used" rows of the song check. */
    fun limits(song: Song): List<Pair<String, String>> = buildList {
        val used = HashSet<Int>()
        for (p in song.orders.toSet()) for (track in song.patterns[p].tracks) for (cell in track) if (cell.instrument > 0) used += cell.instrument
        val sound = song.ay
        add("Sounds" to "${used.size} used, ${sound.instruments.count { it != null }} of ${AySystem.SLOTS} filled")
        add("Pattern data" to "${kb(AyLowering.dataSize(song))} of ${kb(AyPlayerImage.DATA_CAPACITY)}")
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
