package org.thortracker.model

import org.thortracker.backend.HugeLimits

/**
 * GB/hUGE checks, in two layers:
 * - [structuralErrors]: shape and value ranges every consumer may rely on. Projects violating these are not
 *   saved or loaded ([ProjectCodec]).
 * - [errors]: structural errors plus everything that blocks preview/export with hUGEDriver 6.1.3 and
 *   hUGETracker 1.0.11 / uge2source. A work-in-progress song may have these and still be saved.
 * [warnings] are legal but probably unintended. Messages name the location and the fix.
 */
object SongValidator {
    /** Lists are capped so a pathological song cannot flood the UI; the last entry states the remainder. */
    const val MAX_REPORTED = 100

    fun structuralErrors(song: Song): List<String> {
        val out = Report()
        if (song.backend != Songs.BACKEND) {
            out += "Backend \"${song.backend}\" is not supported; this version only edits \"${Songs.BACKEND}\" songs"
            return out.list()
        }
        val channels = HugeRules.CHANNEL_KINDS.size
        if (song.ticksPerRow !in 1..255) out += "Speed ${song.ticksPerRow} is out of range; ticks per row must be 1-255"
        if (song.patterns.isEmpty()) out += "The song has no patterns; add one"
        song.patterns.forEachIndexed { p, pattern ->
            val label = Songs.patternLabel(song, p)
            if (pattern.scale.root !in 0..11) out += "${label.cap()} has scale root ${pattern.scale.root}; roots are 0-11 (C-B)"
            if (pattern.tracks.size != channels) {
                out += "${label.cap()} has ${pattern.tracks.size} channels; GB/hUGE patterns have $channels"
                return@forEachIndexed
            }
            pattern.tracks.forEachIndexed { ch, track ->
                if (track.size != Songs.ROWS) {
                    out += "${label.cap()}, ${HugeRules.CHANNEL_NAMES[ch]} has ${track.size} rows; patterns have ${Songs.ROWS}"
                    return@forEachIndexed
                }
                track.forEachIndexed { row, cell ->
                    cellRangeProblem(cell)?.let { out += "${label.cap()}, ${HugeRules.CHANNEL_NAMES[ch]} row $row: $it" }
                }
            }
        }
        if (song.orders.isEmpty()) out += "The order list is empty; add at least one order"
        song.orders.forEachIndexed { i, p ->
            if (p !in song.patterns.indices) out += "Order $i refers to missing pattern $p"
        }
        if (song.orders.isNotEmpty() && song.loopOrder !in song.orders.indices) {
            out += "Loop order ${song.loopOrder} is outside the order list (0-${song.orders.size - 1})"
        }
        checkBank(out, "Pulse", song.pulseInstruments, InstrumentKind.PULSE)
        checkBank(out, "Wave", song.waveInstruments, InstrumentKind.WAVE)
        checkBank(out, "Noise", song.noiseInstruments, InstrumentKind.NOISE)
        if (song.waves.size != Songs.WAVE_COUNT) out += "The song has ${song.waves.size} waves; GB/hUGE songs have exactly ${Songs.WAVE_COUNT}"
        song.waves.forEachIndexed { w, samples ->
            if (samples.size != Songs.WAVE_SAMPLES) out += "Wave ${HugeRules.hex2(w)} has ${samples.size} samples; waves have ${Songs.WAVE_SAMPLES}"
            else if (samples.any { it !in 0..Songs.WAVE_MAX }) out += "Wave ${HugeRules.hex2(w)} has samples outside 0-15"
        }
        return out.list()
    }

    fun errors(song: Song): List<String> {
        val structural = structuralErrors(song)
        if (structural.isNotEmpty()) return structural
        val out = Report()
        if (song.orders.size > HugeRules.MAX_ORDERS) {
            out += "The song has ${song.orders.size} orders; hUGEDriver supports at most ${HugeRules.MAX_ORDERS}"
        }
        text(out, "Title", song.title)
        text(out, "Artist", song.artist)
        text(out, "Comment", song.comment)
        for (kind in InstrumentKind.entries) {
            HugeRules.bank(song, kind).forEachIndexed { i, instrument ->
                text(out, "${kind.label()} instrument ${HugeRules.hex2(i + 1)} name", instrument.name)
            }
        }
        song.patterns.forEachIndexed { p, pattern ->
            pattern.tracks.forEachIndexed { ch, track ->
                track.forEachIndexed { row, cell ->
                    HugeRules.cellProblem(song, ch, cell)?.let {
                        out += "${Songs.patternLabel(song, p).cap()}, ${HugeRules.CHANNEL_NAMES[ch]} row $row: $it"
                    }
                }
            }
        }
        val plan = HugeRules.loopPlan(song)
        plan.errors.forEach { out += it }
        val tracks = HugeRules.distinctReferencedTracks(song, plan)
        if (tracks > HugeLimits.PREVIEW_TRACK_SLOTS) {
            out += "Song uses $tracks distinct channel patterns; the GB preview/driver data limit is " +
                "${HugeLimits.PREVIEW_TRACK_SLOTS} — reuse or clear patterns"
        }
        return out.list()
    }

    fun warnings(song: Song): List<String> {
        if (structuralErrors(song).isNotEmpty()) return emptyList()
        val out = Report()
        val speed = song.ticksPerRow
        song.patterns.forEachIndexed { p, pattern ->
            pattern.tracks.forEachIndexed { ch, track ->
                track.forEachIndexed { row, cell ->
                    val where = "${Songs.patternLabel(song, p).cap()}, ${HugeRules.CHANNEL_NAMES[ch]} row $row"
                    val fx = HugeRules.effectText(cell.effect, cell.param)
                    if (cell.effect == HugeRules.FX_NOTE_DELAY && cell.param >= speed) {
                        out += "$where: $fx delays by ${cell.param} ticks, but rows last $speed ticks at the song speed, so the note is skipped"
                    }
                    if (cell.effect == HugeRules.FX_NOTE_CUT && cell.param >= speed) {
                        out += "$where: $fx cuts after ${cell.param} ticks, but rows last $speed ticks at the song speed, so nothing is cut"
                    }
                    if (cell.note >= 0 && HugeRules.channelKind(ch) == InstrumentKind.NOISE) {
                        HugeRules.noiseNoteProblem(cell.note)?.let { out += "$where: $it" }
                    }
                }
            }
        }
        silentLegatoNotes(song).forEach { out += it }
        if (song.orders.size <= HugeRules.MAX_ORDERS) {
            val tracks = HugeRules.distinctReferencedTracks(song, HugeRules.loopPlan(song))
            val bytes = estimatedDriverBytes(song, tracks)
            if (bytes > HugeLimits.ROM_BANK_BYTES) {
                out += "Estimated song data is $bytes bytes, more than one ${HugeLimits.ROM_BANK_BYTES}-byte ROM bank; " +
                    "a banked GBDK build (uge2source -b) needs the song in one bank. Reuse patterns or shorten the song"
            }
        }
        return out.list()
    }

    /** Range problem of a single cell independent of context, or null. */
    fun cellRangeProblem(cell: Cell): String? = when {
        cell.note != Cell.EMPTY && cell.note != Cell.CUT && cell.note !in 0..Songs.NOTE_MAX ->
            "note ${cell.note} is outside C-3..B-8 (0-${Songs.NOTE_MAX})"
        cell.instrument !in 0..Songs.INSTRUMENT_SLOTS -> "instrument ${cell.instrument} is outside 0-${Songs.INSTRUMENT_SLOTS}"
        cell.effect !in 0..15 -> "effect ${cell.effect} is outside 0-F"
        cell.param !in 0..255 -> "effect parameter ${cell.param} is outside 00-FF"
        else -> null
    }

    /** uge2source's GBDK output size (it de-duplicates identical tracks): descriptor, order_cnt, tracks, orders, instruments, waves. */
    private fun estimatedDriverBytes(song: Song, distinctTracks: Int): Int {
        val channels = HugeRules.CHANNEL_KINDS.size
        val descriptor = 1 + 2 * 10 // hUGESong_t: tempo byte + 10 pointers
        val orderCount = 1
        return descriptor + orderCount + distinctTracks * Songs.ROWS * 3 + channels * song.orders.size * 2 +
            3 * Songs.INSTRUMENT_SLOTS * 6 + Songs.WAVE_COUNT * 16
    }

    /**
     * Notes without an instrument are legato in hUGEDriver 6.1.3: `res 7` clears the trigger bit and the
     * instrument's NRx2/NR32 is not reloaded. After an OFF (note_cut zeroes NRx2/NR32) or before any instrument
     * has played, such a note is silent. Follows the order list in sequence (jumps are not simulated); patterns
     * outside the order list start from an unknown state. One message per cell.
     */
    private fun silentLegatoNotes(song: Song): Collection<String> {
        val channels = HugeRules.CHANNEL_KINDS.size
        val found = LinkedHashMap<String, String>()
        val start = "the start of the song (no instrument has played on this channel yet)"
        val states = Array(channels) { LegatoState(start) }
        for (p in song.orders) for (ch in 0 until channels) scanLegato(song, p, ch, states[ch], found)
        val referenced = song.orders.toSet()
        for (p in song.patterns.indices) {
            if (p !in referenced) for (ch in 0 until channels) scanLegato(song, p, ch, LegatoState(null, lastNoteLegato = false), found)
        }
        return found.values
    }

    private class LegatoState(var silencedBy: String?, var lastNoteLegato: Boolean = true)

    private fun scanLegato(song: Song, p: Int, ch: Int, state: LegatoState, found: MutableMap<String, String>) {
        val kind = HugeRules.channelKind(ch)
        song.patterns[p].tracks[ch].forEachIndexed { row, cell ->
            val where = "${Songs.patternLabel(song, p).cap()}, ${HugeRules.CHANNEL_NAMES[ch]} row $row"
            if (cell.note >= 0) {
                if (cell.instrument != 0) {
                    state.silencedBy = null
                    state.lastNoteLegato = false
                } else {
                    state.silencedBy?.let { cause ->
                        found.getOrPut(where) {
                            "$where: ${Songs.noteName(cell.note)} has no instrument, so hUGEDriver changes the pitch without " +
                                "restarting (legato) and it stays silent after $cause; enter the instrument number"
                        }
                    }
                    state.lastNoteLegato = true
                }
            }
            val y = cell.param and 0xF
            when {
                cell.note == Cell.CUT -> state.silencedBy = "the OFF on $where"
                cell.effect == HugeRules.FX_NOTE_CUT && cell.param < song.ticksPerRow ->
                    state.silencedBy = "the ${HugeRules.effectText(cell.effect, cell.param)} cut on $where"
                // Wave Cxy rewrites NR32; Pulse/Noise Cxy restarts only if the last note carried the trigger bit.
                cell.effect == HugeRules.FX_SET_VOLUME && y != 0 && (kind == InstrumentKind.WAVE || !state.lastNoteLegato) ->
                    state.silencedBy = null
                // Axy always retriggers, from the zeroed volume plus x.
                cell.effect == HugeRules.FX_VOLUME_SLIDE && cell.param shr 4 != 0 && kind != InstrumentKind.WAVE ->
                    state.silencedBy = null
            }
        }
    }

    private fun checkBank(out: Report, label: String, bank: List<Instrument>, kind: InstrumentKind) {
        if (bank.size != Songs.INSTRUMENT_SLOTS) {
            out += "The $label bank has ${bank.size} instruments; GB/hUGE banks have exactly ${Songs.INSTRUMENT_SLOTS}"
        }
        bank.forEachIndexed { i, it ->
            val where = "$label instrument ${HugeRules.hex2(i + 1)}"
            if (it.kind != kind) out += "$where is a ${it.kind.label()} instrument in the $label bank"
            val maxLength = if (kind == InstrumentKind.WAVE) 255 else 63
            range(out, where, "duty", it.duty, 0..3)
            range(out, where, "volume", it.volume, 0..15)
            range(out, where, "envelope direction", it.envelopeDirection, 0..1)
            range(out, where, "envelope pace", it.envelopePace, 0..7)
            range(out, where, "length", it.length, 0..maxLength)
            range(out, where, "sweep time", it.sweepTime, 0..7)
            range(out, where, "sweep direction", it.sweepDirection, 0..1)
            range(out, where, "sweep shift", it.sweepShift, 0..7)
            range(out, where, "wave", it.wave, 0 until Songs.WAVE_COUNT)
            range(out, where, "wave volume", it.waveVolume, 0..3)
            range(out, where, "noise width", it.noiseWidth, 0..1)
        }
    }

    private fun range(out: Report, where: String, field: String, value: Int, valid: IntRange) {
        if (value !in valid) out += "$where: $field $value is outside ${valid.first}-${valid.last}"
    }

    private fun text(out: Report, field: String, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8).size
        if (bytes > HugeRules.MAX_TEXT_BYTES) out += "$field is $bytes bytes long; hUGETracker stores at most ${HugeRules.MAX_TEXT_BYTES} (UTF-8)"
    }

    private fun InstrumentKind.label() = name.lowercase().replaceFirstChar { it.uppercase() }

    private fun String.cap() = replaceFirstChar { it.uppercase() }

    private class Report {
        private val items = ArrayList<String>()
        private var dropped = 0
        operator fun plusAssign(message: String) {
            if (items.size < MAX_REPORTED) items += message else dropped++
        }
        fun list(): List<String> = if (dropped == 0) items else items + "...and $dropped more"
    }
}
