package org.thortracker.system.nes

import org.thortracker.backend.FamiStudioEnvelopes
import org.thortracker.backend.FamiStudioEnvelopes.Kind
import org.thortracker.backend.FamiStudioLowering
import org.thortracker.backend.FsSong
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.model.TuningMap
import org.thortracker.system.ChannelKind

/**
 * NES/FamiStudio checks, layered like the Game Boy's:
 * - [structuralErrors]: shape and value ranges every consumer relies on; such projects are neither saved nor loaded.
 * - [errors]: plus everything the FamiStudio sound engine 4.5.3 (and FamiStudio's text import) cannot play as
 *   written: effect parameters, envelope loop/release rules, encoded sizes and the song region.
 * [warnings] are legal but probably unintended. Messages name the location and the fix.
 */
object NesValidator {
    const val MAX_REPORTED = 100

    /** NSF title/artist fields: 31 ASCII characters. */
    const val NSF_TEXT = 31

    fun structuralErrors(song: Song): List<String> {
        val out = Report()
        if (song.sound !is NesSound) {
            out += "The song is for ${song.system.name}, not the NES"
            return out.list()
        }
        val channels = NesRules.CHANNEL_NAMES.size
        if (song.ticksPerRow !in 1..255) out += "Speed ${song.ticksPerRow} is out of range; ticks per row must be 1-255"
        if (song.patterns.isEmpty()) out += "The song has no patterns; add one"
        song.patterns.forEachIndexed { p, pattern ->
            val label = Songs.patternLabel(song, p).cap()
            if (pattern.scale.root !in 0..11) out += "$label has scale root ${pattern.scale.root}; roots are 0-11 (C-B)"
            if (pattern.length !in 1..Songs.ROWS) out += "$label is ${pattern.length} rows long; lengths are 1-${Songs.ROWS}"
            if (pattern.tracks.size != channels) {
                out += "$label has ${pattern.tracks.size} channels; NES patterns have $channels"
                return@forEachIndexed
            }
            pattern.tracks.forEachIndexed { ch, track ->
                if (track.size != Songs.ROWS) {
                    out += "$label, ${NesRules.CHANNEL_NAMES[ch]} has ${track.size} rows; patterns have ${Songs.ROWS}"
                    return@forEachIndexed
                }
                track.forEachIndexed { row, cell ->
                    NesRules.cellRangeProblem(cell)?.let { out += "$label, ${NesRules.CHANNEL_NAMES[ch]} row $row: $it" }
                }
            }
        }
        if (song.orders.isEmpty()) out += "The order list is empty; add at least one order"
        song.orders.forEachIndexed { i, p -> if (p !in song.patterns.indices) out += "Order $i refers to missing pattern $p" }
        if (song.orders.isNotEmpty() && song.loopOrder !in song.orders.indices) {
            out += "Loop order ${song.loopOrder} is outside the order list (0-${song.orders.size - 1})"
        }
        val bank = song.nes.instruments
        if (bank.size != NesSystem.SLOTS) out += "The song has ${bank.size} instruments; NES songs have exactly ${NesSystem.SLOTS}"
        bank.forEachIndexed { i, ins ->
            val where = "Instrument ${NesRules.hex2(i + 1)}"
            envelopeShape(out, where, "volume", ins.volume, NesEnvelope.VOLUME)
            envelopeShape(out, where, "arpeggio", ins.arpeggio, NesEnvelope.ARPEGGIO)
            envelopeShape(out, where, "pitch", ins.pitch, NesEnvelope.PITCH)
            envelopeShape(out, where, "duty", ins.duty, NesEnvelope.DUTY)
        }
        song.tuning?.problems(NesSystem.noteRange)?.forEach { out += it }
        if (song.newPatternScale.root !in 0..11) out += "The scale for new patterns has root ${song.newPatternScale.root}; roots are 0-11 (C-B)"
        if (song.parkedOrders.any { it !in song.orders.indices } || song.parkedOrders.zipWithNext().any { (a, b) -> a >= b }) {
            out += "Parked orders ${song.parkedOrders} must be ascending, distinct orders 0-${song.orders.size - 1}"
        }
        return out.list()
    }

    private fun envelopeShape(out: Report, where: String, name: String, e: NesEnvelope, valid: IntRange) {
        if (e.values.size > NesEnvelope.MAX_LENGTH) out += "$where: the $name envelope has ${e.values.size} steps; at most ${NesEnvelope.MAX_LENGTH}"
        e.values.firstOrNull { it !in valid }?.let { out += "$where: $name value $it is outside ${valid.first}..${valid.last}" }
        if (e.loop < -1 || e.loop >= maxOf(e.values.size, 1) && e.loop != -1) out += "$where: the $name loop point ${e.loop} is outside the envelope"
        if (e.release < -1 || e.release >= maxOf(e.values.size, 1) && e.release != -1) out += "$where: the $name release point ${e.release} is outside the envelope"
    }

    /** Problems that block playing or exporting [document]'s arrangement (its unparked orders). */
    fun errors(document: Song): List<String> {
        val structural = structuralErrors(document)
        if (structural.isNotEmpty()) return structural
        if (document.parkedOrders.size == document.orders.size) {
            return listOf("Every order is parked; unpark at least one to play the song or export it")
        }
        val song = NesSystem.arranged(document)
        val out = Report()
        if (song.orders.size > NesRules.MAX_ORDERS) out += "The song has ${song.orders.size} orders; the NES player supports at most ${NesRules.MAX_ORDERS}"
        if (song.ticksPerRow !in NesRules.SPEEDS) {
            out += "Song speed ${song.ticksPerRow} is too slow: FamiStudio's FamiTracker-tempo speeds are 1-${NesRules.SPEEDS.last} ticks per row"
        }
        song.nes.instruments.forEachIndexed { i, ins ->
            val where = "Instrument ${NesRules.hex2(i + 1)} \"${ins.name}\""
            envelopeRules(out, where, "volume", ins.volume, releases = true)
            envelopeRules(out, where, "arpeggio", ins.arpeggio, releases = false)
            envelopeRules(out, where, "pitch", ins.pitch, releases = false)
            envelopeRules(out, where, "duty", ins.duty, releases = false)
            for ((name, size) in listOf(
                "volume" to FamiStudioEnvelopes.encode(ins.volume, Kind.VOLUME).size,
                "arpeggio" to FamiStudioEnvelopes.encode(ins.arpeggio, Kind.ARPEGGIO).size,
                "pitch" to FamiStudioEnvelopes.encode(ins.pitch, Kind.PITCH, ins.pitchRelative).size,
                "duty" to FamiStudioEnvelopes.encode(ins.duty, Kind.DUTY).size,
            )) if (size > 255) out += "$where: the $name envelope takes $size bytes once encoded; FamiStudio's limit is 255. Shorten it or repeat fewer changes"
        }
        val played = song.orders.toSet()
        document.patterns.forEachIndexed { p, pattern ->
            if (p !in played) return@forEachIndexed
            val label = Songs.patternLabel(song, p).cap()
            pattern.tracks.forEachIndexed { ch, track ->
                for (row in 0 until pattern.length) {
                    NesRules.cellProblem(song, ch, track[row])?.let { out += "$label, ${NesRules.CHANNEL_NAMES[ch]} row $row: $it" }
                }
            }
        }
        if (out.isEmpty()) {
            try {
                FamiStudioLowering.lower(FsSong.of(song))
            } catch (e: IllegalArgumentException) {
                out += e.message ?: "The song cannot be lowered for the NES player"
            }
        }
        return out.list()
    }

    /** FamiStudio's loop/release rules (its text import silently drops what breaks them). */
    private fun envelopeRules(out: Report, where: String, name: String, e: NesEnvelope, releases: Boolean) {
        if (e.release >= 0 && !releases) out += "$where: only volume envelopes have a release point; remove the $name release"
        if (releases && e.release >= 0 && e.loop < 0) {
            out += "$where: the volume release point needs a loop before it (FamiStudio sustains a note by looping until it is released); set a loop"
        }
        if (releases && e.release >= 0 && e.loop >= e.release) {
            out += "$where: the volume loop (${e.loop}) must come before the release point (${e.release})"
        }
    }

    fun warnings(document: Song): List<String> {
        if (structuralErrors(document).isNotEmpty()) return emptyList()
        val song = NesSystem.arranged(document)
        val out = Report()
        val speed = song.ticksPerRow
        val played = song.orders.toSet()
        document.patterns.forEachIndexed { p, pattern ->
            if (p !in played) return@forEachIndexed
            pattern.tracks.forEachIndexed { ch, track ->
                for (row in 0 until pattern.length) {
                    val cell = track[row]
                    val where = "${Songs.patternLabel(song, p).cap()}, ${NesRules.CHANNEL_NAMES[ch]} row $row"
                    val fx = NesRules.effectText(cell.effect, cell.param)
                    if (cell.effect == NesRules.FX_NOTE_DELAY && cell.param in 1..NesRules.DELAY_MAX && cell.param >= speed) {
                        out += "$where: $fx delays by ${cell.param} frames, but rows last $speed frames at the song speed, so it lands in a later row"
                    }
                    if (cell.effect == NesRules.FX_NOTE_CUT && cell.hasEffect && cell.param >= speed) {
                        out += "$where: $fx cuts after ${cell.param} frames, but rows last $speed frames at the song speed"
                    }
                    if (cell.effect == NesRules.FX_VIBRATO && (cell.param and 0xF) > 13) {
                        out += "$where: FamiStudio warns that vibrato depths above D do not sound right in its sound engine"
                    }
                }
            }
        }
        firstNotesWithoutInstrument(song).forEach { out += it }
        if (song.title.length > NSF_TEXT || song.title.any { it.code !in 0x20..0x7e }) {
            out += "The .nsf title field holds $NSF_TEXT plain ASCII characters; the title will be shortened or changed there"
        }
        if (song.artist.length > NSF_TEXT || song.artist.any { it.code !in 0x20..0x7e }) {
            out += "The .nsf artist field holds $NSF_TEXT plain ASCII characters; the artist will be shortened or changed there"
        }
        song.tuning?.let { tunedNoteWarnings(song, TuningMap(it, NesSystem)).forEach { m -> out += m } }
        return out.list()
    }

    /** A note without instrument before any instrument has played on its channel: the engine has no sound loaded. */
    private fun firstNotesWithoutInstrument(song: Song): List<String> {
        val out = ArrayList<String>()
        for (ch in NesRules.CHANNEL_NAMES.indices) {
            loop@ for (p in song.orders) {
                val pattern = song.patterns[p]
                for (row in 0 until pattern.length) {
                    val cell = pattern.tracks[ch][row]
                    if (cell.note < 0) continue
                    if (cell.instrument == 0) {
                        out += "${Songs.patternLabel(song, p).cap()}, ${NesRules.CHANNEL_NAMES[ch]} row $row: ${Songs.noteName(cell.note)} has no " +
                            "instrument and none has played on this channel yet; enter the instrument number"
                    }
                    break@loop
                }
            }
        }
        return out
    }

    /** Tonal notes of a tuned song that do not sound a tuned pitch (their Pxx missing, wrong or the slot taken). */
    private fun tunedNoteWarnings(song: Song, map: TuningMap): List<String> {
        val out = ArrayList<String>()
        song.patterns.forEachIndexed { p, pattern ->
            pattern.tracks.forEachIndexed { ch, track ->
                if (NesRules.kind(ch) == ChannelKind.NOISE) return@forEachIndexed
                for (row in 0 until pattern.length) {
                    val cell = track[row]
                    if (cell.note < 0) continue
                    val where = "${Songs.patternLabel(song, p).cap()}, ${NesRules.CHANNEL_NAMES[ch]} row $row"
                    val fine = NesSystem.fine.of(cell)
                    when {
                        fine == null -> if (map.index(cell.note, 0) < 0) {
                            out += "$where: tuned note needs this row's effect slot for Pxx but it holds " +
                                "${NesRules.effectText(cell.effect, cell.param)}; it plays at the plain 12-note pitch"
                        }
                        map.index(cell.note, fine) < 0 -> {
                            val pitch = Songs.noteName(cell.note) + if (fine > 0) " +$fine" else ""
                            out += "$where: $pitch is outside the song tuning \"${map.tuning.name}\"; use Fit notes to tuning or re-enter it"
                        }
                    }
                }
            }
        }
        return out
    }

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
