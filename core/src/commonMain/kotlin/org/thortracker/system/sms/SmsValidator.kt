package org.thortracker.system.sms

import org.thortracker.backend.SmsImageLayout
import org.thortracker.backend.SmsLowering
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.model.Songs

/**
 * Master System song checks: [errors] are what the lowering or the engine's loader (`sms_image.c`) cannot take, or
 * what would desynchronise the Banjo driver (a note delay or cut as long as its row); [warnings] are the driver's
 * shared-state surprises (one custom FM voice, Square 3 and pitched noise) and limits a game cares about (one 16 KB bank).
 */
object SmsValidator {
    private const val MAX_REPORTED = 40

    fun structuralErrors(system: SmsSystem, song: Song): List<String> {
        val out = Report()
        val sound = song.sound as? SmsSound
        if (sound == null || sound.layout != system.layout) {
            out += "The song is for ${song.system.name}, not the ${system.name}"
            return out.list()
        }
        val layout = system.layout
        val channels = layout.channelCount
        if (song.ticksPerRow !in 1..255) out += "Speed ${song.ticksPerRow} is out of range; ticks per row must be 1-255"
        if (song.patterns.isEmpty()) out += "The song has no patterns; add one"
        song.patterns.forEachIndexed { p, pattern ->
            val label = Songs.patternLabel(song, p).cap()
            if (pattern.scale.root !in 0..11) out += "$label has scale root ${pattern.scale.root}; roots are 0-11 (C-B)"
            if (pattern.length !in 1..Songs.ROWS) out += "$label is ${pattern.length} rows long; lengths are 1-${Songs.ROWS}"
            if (pattern.tracks.size != channels) {
                out += "$label has ${pattern.tracks.size} channels; ${system.name} patterns have $channels"
                return@forEachIndexed
            }
            pattern.tracks.forEachIndexed { ch, track ->
                if (track.size != Songs.ROWS) {
                    out += "$label, ${layout.channelNames[ch]} has ${track.size} rows; patterns have ${Songs.ROWS}"
                    return@forEachIndexed
                }
                track.forEachIndexed { row, cell ->
                    SmsRules.cellRangeProblem(layout, cell)?.let { out += "$label, ${layout.channelNames[ch]} row $row: $it" }
                }
            }
        }
        if (song.orders.isEmpty()) out += "The order list is empty; add at least one order"
        song.orders.forEachIndexed { i, p -> if (p !in song.patterns.indices) out += "Order $i refers to missing pattern $p" }
        if (song.orders.isNotEmpty() && song.loopOrder !in song.orders.indices) {
            out += "Loop order ${song.loopOrder} is outside the order list (0-${song.orders.size - 1})"
        }
        if (sound.instruments.size != SmsSystem.SLOTS) {
            out += "The song has ${sound.instruments.size} instrument slots; Master System songs have exactly ${SmsSystem.SLOTS}"
        }
        sound.instruments.forEachIndexed { i, ins -> ins?.problem()?.let { out += "Instrument ${hex2(i + 1)}: $it" } }
        song.tuning?.problems(system)?.forEach { out += it }
        if (song.newPatternScale.root !in 0..11) out += "The scale for new patterns has root ${song.newPatternScale.root}; roots are 0-11 (C-B)"
        if (song.parkedOrders.any { it !in song.orders.indices } || song.parkedOrders.zipWithNext().any { (a, b) -> a >= b }) {
            out += "Parked orders ${song.parkedOrders} must be ascending, distinct orders 0-${song.orders.size - 1}"
        }
        return out.list()
    }

    /** Problems that block playing or exporting [document]'s arrangement. */
    fun errors(system: SmsSystem, document: Song): List<String> {
        val structural = structuralErrors(system, document)
        if (structural.isNotEmpty()) return structural
        if (document.parkedOrders.size == document.orders.size) {
            return listOf("Every order is parked; unpark at least one to play the song or export it")
        }
        val song = system.arranged(document)
        val layout = system.layout
        val out = Report()
        if (song.orders.size > SmsImageLayout.MAX_ORDERS) out += "The song has ${song.orders.size} orders; Banjo plays at most ${SmsImageLayout.MAX_ORDERS}"
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            pattern.tracks.forEachIndexed { ch, track ->
                for (row in 0 until pattern.length) {
                    cellProblem(song, ch, track[row])?.let { out += "$label, ${layout.channelNames[ch]} row $row: $it" }
                }
            }
        }
        if (!out.isEmpty()) return out.list()
        // Note delays and cuts within their row's ticks (the speed straight play has there): a longer one makes Banjo's
        // channel skip the next row and run behind the others.
        val straight = SmsLowering.straight(song)
        song.orders.forEachIndexed { o, p ->
            val pattern = song.patterns[p]
            for (row in 0 until pattern.length) for (ch in 0 until layout.channelCount) {
                val cell = pattern.tracks[ch][row]
                var delay = 0
                var cut = 0
                for (col in 0..cell.more.size) {
                    when (cell.effectCode(col)) {
                        SmsRules.FX_DELAY -> delay = cell.effectParam(col)
                        SmsRules.FX_CUT -> cut = cell.effectParam(col)
                    }
                }
                val ticks = straight.rowTicks[o][row]
                if (delay + cut >= ticks && delay + cut > 0) {
                    out += "Order ${hex2(o)}, ${layout.channelNames[ch]} row $row: " +
                        (if (cut > 0) "G${hex2(delay)} + S${hex2(cut)} wait ${delay + cut}" else "G${hex2(delay)} waits $delay") +
                        " ticks but the row has $ticks; Banjo would fall a row behind. Shorten it or slow the row"
                }
            }
        }
        if (out.isEmpty()) {
            val size = SmsLowering.dataSize(song)
            if (size > SmsImageLayout.REGION_SIZE) {
                out += "The song data needs ${kb(size)}; the Master System image holds ${kb(SmsImageLayout.REGION_SIZE)}. Reuse patterns or shorten the song"
            }
        }
        return out.list()
    }

    /** Why [cell] on [channel] cannot be lowered, given the song, or null. */
    fun cellProblem(song: Song, channel: Int, cell: Cell): String? {
        val sound = song.sms
        val layout = sound.layout
        SmsRules.cellRangeProblem(layout, cell)?.let { return it }
        val kind = layout.kind(channel)
        if (cell.note >= 0 && (kind == SmsChannelKind.FM || kind == SmsChannelKind.DRUM) && cell.note > SmsInstruments.FM_TOP) {
            return "${song.system.noteName(cell.note)} is above the YM2413's B-7"
        }
        if (cell.instrument != 0) {
            val ins = sound.instrument(cell.instrument) ?: return "instrument ${hex2(cell.instrument)} is empty; choose a preset for it or another instrument"
            SmsSystem.of(layout).kindProblem(channel, cell.instrument, ins)?.let { return it }
        }
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code == 0 && param == 0) continue
            SmsRules.paramProblem(layout, channel, code, param, song.orders.size)?.let { return it }
        }
        return null
    }

    /** Legal but probably unintended details. */
    fun warnings(system: SmsSystem, document: Song): List<String> {
        if (structuralErrors(system, document).isNotEmpty()) return emptyList()
        val song = system.arranged(document)
        val layout = system.layout
        val out = Report()
        firstNotesWithoutInstrument(song, layout).forEach { out += it }
        straightPlay(song, layout, out)
        val size = runCatching { SmsLowering.build(song, arranged = true).dataSize }.getOrNull()
        if (size != null && size + exportOverhead(layout) > SmsImageLayout.BANK_SIZE) {
            out += "The song data is ${kb(size + exportOverhead(layout))}: more than one 16 KB ROM bank, so a game cannot page it into one slot; " +
                "reuse patterns or split the song"
        }
        return out.list()
    }

    /** Bytes an exported song adds to the image's data: the call routines (13 bytes) and the channel mute table. */
    private fun exportOverhead(layout: SmsLayout): Int = (if (layout.fm) 3 * 7 else 0) + 2 * layout.channelCount

    private fun firstNotesWithoutInstrument(song: Song, layout: SmsLayout): List<String> {
        val out = ArrayList<String>()
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            for (ch in 0 until layout.channelCount) {
                val track = pattern.tracks[ch]
                val first = (0 until pattern.length).firstOrNull { track[it].note >= 0 || track[it].instrument != 0 } ?: continue
                val cell = track[first]
                if (cell.note >= 0 && cell.instrument == 0 && layout.kind(ch) != SmsChannelKind.DRUM) {
                    out += "${Songs.patternLabel(song, p).cap()}, ${layout.channelNames[ch]} row $first: ${song.system.noteName(cell.note)} " +
                        "has no instrument; an order can be entered by the loop or a seek, so give its first note one"
                }
            }
        }
        return out
    }

    /**
     * Walks the arrangement once in order and reports: PSG notes below A-2 (they play A-2) and Noise notes below C-3
     * (no fixed rate in Banjo's table), two pitch effects on one row (the last wins), a custom FM voice loaded while
     * another channel still plays a different custom voice (the YM2413 holds one), Square 3 notes while the noise is
     * pitched by Square 3, and instruments named without a note at the end of a track (they never load).
     */
    private fun straightPlay(song: Song, layout: SmsLayout, out: Report) {
        val sound = song.sms
        val channels = layout.channelCount
        val current = IntArray(channels)
        var noiseMode = 3 // Banjo's start: white, pitched by Square 3
        var noiseSounding = false
        var warnedLow = 0
        var warnedPitch = 0
        var warnedCustom = 0
        var warnedSquare3 = 0
        var warnedPending = 0
        song.orders.forEachIndexed { o, p ->
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            val pending = IntArray(channels)
            for (row in 0 until pattern.length) {
                for (ch in 0 until channels) {
                    val c = pattern.tracks[ch][row]
                    val where = "$label, ${layout.channelNames[ch]} row $row"
                    val kind = layout.kind(ch)
                    val pitchFx = (0..c.more.size).count { c.effectCode(it) in SmsRules.PITCH_EFFECTS && (c.effectCode(it) != 0 || c.effectParam(it) != 0) }
                    if (pitchFx > 1 && warnedPitch < 3) {
                        warnedPitch++
                        out += "$where: $pitchFx pitch effects (0-4) on one row; Banjo keeps one per channel, the last column wins"
                    }
                    if (kind == SmsChannelKind.NOISE) {
                        for (col in 0..c.more.size) if (c.effectCode(col) == SmsRules.FX_NOISE) {
                            noiseMode = ((c.effectParam(col) shr 4) and 1) or ((c.effectParam(col) and 1) shl 1)
                        }
                    }
                    if (c.instrument != 0) pending[ch] = c.instrument
                    if (c.note == Cell.CUT && kind == SmsChannelKind.NOISE) noiseSounding = false
                    if (c.note < 0) continue
                    if (pending[ch] != 0) {
                        val next = pending[ch]
                        pending[ch] = 0
                        val ins = sound.instrument(next)
                        if (kind == SmsChannelKind.FM && ins is SmsFmInstrument && ins.isCustom && next != current[ch] && warnedCustom < 3) {
                            val other = (0 until channels).firstOrNull { other ->
                                other != ch && layout.kind(other) == SmsChannelKind.FM &&
                                    (sound.instrument(current[other]) as? SmsFmInstrument)?.isCustom == true && current[other] != next
                            }
                            if (other != null) {
                                warnedCustom++
                                out += "$where: custom voice ${hex2(next)} replaces the YM2413's one custom voice while ${layout.channelNames[other]} " +
                                    "plays custom voice ${hex2(current[other])}; that channel now sounds as ${hex2(next)}"
                            }
                        }
                        current[ch] = next
                    }
                    when (kind) {
                        SmsChannelKind.SQUARE -> {
                            if (c.note < SmsRules.PSG_LOWEST && warnedLow < 3) {
                                warnedLow++
                                out += "$where: ${song.system.noteName(c.note)} is below Banjo's PSG range (A-2 and up); it plays A-2"
                            }
                            if (ch == SmsLayout.SQUARE_3 && noiseSounding && noiseMode and 2 != 0 && warnedSquare3 < 3) {
                                warnedSquare3++
                                out += "$where: the Noise channel is pitched by Square 3 here, so its notes and this one share Square 3's period; " +
                                    "end the noise with OFF or switch it to a fixed rate (N10, N00)"
                            }
                        }
                        SmsChannelKind.NOISE -> {
                            val ins = sound.instrument(current[ch]) as? SmsPsgInstrument
                            ins?.noise?.values?.firstOrNull()?.let { noiseMode = it }
                            noiseSounding = true
                            if (noiseMode and 2 == 0 && c.note < SmsRules.NOISE_LOWEST && warnedLow < 3) {
                                warnedLow++
                                out += "$where: ${song.system.noteName(c.note)} is below C-3; Banjo's fixed noise rate for it is arbitrary"
                            }
                        }
                        else -> Unit
                    }
                }
            }
            for (ch in 0 until channels) {
                if (pending[ch] != 0 && layout.kind(ch) != SmsChannelKind.DRUM && warnedPending < 3) {
                    warnedPending++
                    out += "Order ${hex2(o)}, ${layout.channelNames[ch]}: instrument ${hex2(pending[ch])} is named without a note after it; " +
                        "Banjo loads instruments with notes, so it is not used"
                }
            }
        }
    }

    /** "Limits used" rows of the song check. */
    fun limits(song: Song): List<Pair<String, String>> = buildList {
        val used = HashSet<Int>()
        for (p in song.orders.toSet()) for (track in song.patterns[p].tracks) for (cell in track) if (cell.instrument > 0) used += cell.instrument
        val sound = song.sms
        val counts = SmsInstrumentKind.entries.associateWith { k -> sound.instruments.count { it?.kind == k } }
        val filled = counts.values.sum()
        val parts = sound.layout.kinds.joinToString(", ") { "${counts[it]} ${it.bankName.substringBefore(' ').lowercase()}" }
        add("Instruments" to "${used.size} used, $filled of ${SmsSystem.SLOTS} filled ($parts)")
        val size = runCatching { SmsLowering.dataSize(song) }.getOrNull()
        if (size != null) add("Song data" to "${kb(size)} (one ROM bank holds ${kb(SmsImageLayout.BANK_SIZE)})")
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
