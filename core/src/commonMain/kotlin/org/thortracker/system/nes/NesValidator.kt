package org.thortracker.system.nes

import org.thortracker.backend.FamiStudioEnvelopes
import org.thortracker.backend.FamiStudioEnvelopes.Kind
import org.thortracker.backend.FamiStudioLowering
import org.thortracker.backend.FsNote
import org.thortracker.backend.FsSong
import org.thortracker.backend.NesDpcm
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.model.TuningMap

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
        val expansion = song.nes.expansion
        val channels = expansion.channels
        if (song.ticksPerRow !in 1..255) out += "Speed ${song.ticksPerRow} is out of range; ticks per row must be 1-255"
        if (song.patterns.isEmpty()) out += "The song has no patterns; add one"
        song.patterns.forEachIndexed { p, pattern ->
            val label = Songs.patternLabel(song, p).cap()
            if (pattern.scale.root !in 0..11) out += "$label has scale root ${pattern.scale.root}; roots are 0-11 (C-B)"
            if (pattern.length !in 1..Songs.ROWS) out += "$label is ${pattern.length} rows long; lengths are 1-${Songs.ROWS}"
            if (pattern.tracks.size != channels) {
                out += "$label has ${pattern.tracks.size} channels; NES${if (expansion == NesExpansion.NONE) "" else " + ${expansion.chip.label}"} patterns have $channels"
                return@forEachIndexed
            }
            pattern.tracks.forEachIndexed { ch, track ->
                if (track.size != Songs.ROWS) {
                    out += "$label, ${NesRules.channelName(expansion, ch)} has ${track.size} rows; patterns have ${Songs.ROWS}"
                    return@forEachIndexed
                }
                track.forEachIndexed { row, cell ->
                    NesRules.cellRangeProblem(cell)?.let { out += "$label, ${NesRules.channelName(expansion, ch)} row $row: $it" }
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
            if (ins.chip !in expansion.chips) out += "$where is a ${ins.chip.label} sound; this song has no ${ins.chip.label} chip"
            if (ins.chip != NesChip.VRC6 && ins.sawVolume != Vrc6SawVolume.HALF) out += "$where: only VRC6 sounds have a saw volume"
            if (ins.chip != NesChip.VRC7 && ins.fm != Vrc7Patch.DEFAULT) out += "$where: only VRC7 sounds have an FM patch"
            if (ins.chip != NesChip.APU && ins.dpcm.isNotEmpty()) out += "$where: only 2A03 sounds map DPCM samples"
            if (ins.chip == NesChip.VRC7) {
                if (ins.duty.values.isNotEmpty()) out += "$where: VRC7 sounds have no duty envelope; clear it"
                if (ins.fm.preset !in 0..Vrc7Presets.PRESETS.last) out += "$where: VRC7 patch ${ins.fm.preset} is outside 0-15 (0 = custom)"
                if (ins.fm.regs.size != Vrc7Patch.REGS) out += "$where: the VRC7 custom patch has ${ins.fm.regs.size} registers; it has ${Vrc7Patch.REGS}"
                ins.fm.regs.firstOrNull { it !in 0..255 }?.let { out += "$where: VRC7 patch register value $it is outside 0-255" }
            }
            if (ins.chip == NesChip.S5B) {
                if (ins.duty.values.isNotEmpty()) out += "$where: 5B sounds have no duty envelope; clear it"
                if (ins.s5b.octave !in S5bEnvelope.OCTAVES) out += "$where: 5B envelope octave ${ins.s5b.octave} is outside -8..8"
                if (ins.s5b.period !in S5bEnvelope.PERIODS) out += "$where: 5B envelope period ${ins.s5b.period} is outside 0-65535"
            } else {
                if (ins.mixer.values.isNotEmpty() || ins.noise.values.isNotEmpty()) out += "$where: only 5B sounds have mixer and noise envelopes"
                if (ins.s5b != S5bEnvelope.DEFAULT) out += "$where: only 5B sounds have a hardware envelope"
            }
            envelopeShape(out, where, "volume", ins.volume, NesEnvelope.VOLUME)
            envelopeShape(out, where, "arpeggio", ins.arpeggio, NesEnvelope.ARPEGGIO)
            envelopeShape(out, where, "pitch", ins.pitch, NesEnvelope.PITCH)
            envelopeShape(out, where, "duty", ins.duty, ins.chip.duty)
            envelopeShape(out, where, "mixer", ins.mixer, NesEnvelope.MIXER)
            envelopeShape(out, where, "noise", ins.noise, NesEnvelope.NOISE)
            for ((note, m) in ins.dpcm) {
                val at = "$where, DPCM ${if (note in NesSystem.noteRange) NesSystem.noteName(note) else "note $note"}"
                when {
                    note !in NesSystem.noteRange -> out += "$at: notes are C-1..B-8 (${NesSystem.noteRange.first}-${NesSystem.noteRange.last})"
                    m.sample !in song.nes.samples.indices -> out += "$at: sample ${m.sample + 1} does not exist; the song has ${song.nes.samples.size}"
                    m.pitch !in DpcmMapping.PITCH -> out += "$at: rate ${m.pitch} is outside 0-15"
                    m.dmcInitial !in -1..DpcmSample.DMC_INITIAL.last -> out += "$at: initial value ${m.dmcInitial} is outside 0-63 (or -1 for the sample's)"
                }
            }
        }
        song.nes.samples.forEachIndexed { i, s ->
            val where = "DPCM sample ${i + 1} \"${s.name}\""
            if (s.data.size !in 1..DpcmSample.MAX_BYTES) out += "$where has ${s.data.size} bytes; samples are 1-${DpcmSample.MAX_BYTES} bytes"
            s.data.firstOrNull { it !in 0..255 }?.let { out += "$where: byte $it is outside 0-255" }
            if (s.dmcInitial !in DpcmSample.DMC_INITIAL) out += "$where: initial value ${s.dmcInitial} is outside 0-63"
        }
        song.tuning?.problems(NesSystem)?.forEach { out += it }
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
            envelopeRules(out, where, "mixer", ins.mixer, releases = false)
            envelopeRules(out, where, "noise", ins.noise, releases = false)
            for ((name, size) in listOf(
                "volume" to FamiStudioEnvelopes.encode(ins.volume, Kind.VOLUME).size,
                "arpeggio" to FamiStudioEnvelopes.encode(ins.arpeggio, Kind.ARPEGGIO).size,
                "pitch" to FamiStudioEnvelopes.encode(ins.pitch, Kind.PITCH, ins.pitchRelative).size,
                "duty" to FamiStudioEnvelopes.encode(ins.duty, Kind.DUTY).size,
                "mixer" to FamiStudioEnvelopes.encode(ins.mixer, Kind.MIXER).size,
                "noise" to FamiStudioEnvelopes.encode(ins.noise, Kind.NOISE).size,
            )) if (size > 255) out += "$where: the $name envelope takes $size bytes once encoded; FamiStudio's limit is 255. Shorten it or repeat fewer changes"
        }
        val played = song.orders.toSet()
        val squares = if (song.nes.expansion == NesExpansion.S5B) NesRules.S5B_SQUARE1 until song.nes.expansion.channels else IntRange.EMPTY
        document.patterns.forEachIndexed { p, pattern ->
            if (p !in played) return@forEachIndexed
            val label = Songs.patternLabel(song, p).cap()
            pattern.tracks.forEachIndexed { ch, track ->
                for (row in 0 until pattern.length) {
                    NesRules.cellProblem(song, ch, track[row])?.let { out += "$label, ${NesRules.channelName(song.nes.expansion, ch)} row $row: $it" }
                }
            }
            if (!squares.isEmpty()) for (row in 0 until pattern.length) {
                NesRules.envPeriodConflict(squares.map { pattern.tracks[it][row] })?.let { out += "$label, row $row: $it" }
            }
        }
        if (out.isEmpty()) dpcmErrors(song, out)
        if (out.isEmpty()) {
            try {
                FamiStudioLowering.lower(FsSong.of(song))
            } catch (e: IllegalArgumentException) {
                out += e.message ?: "The song cannot be lowered for the NES player"
            }
        }
        return out.list()
    }

    /**
     * DPCM problems of the arrangement [song]: sample lengths the hardware cannot play, notes without a mapping (also
     * legato ones, which play the channel's current instrument), and the sample memory and sample list budgets.
     */
    private fun dpcmErrors(song: Song, out: Report) {
        song.nes.samples.forEachIndexed { i, s ->
            if ((s.data.size - 1) % 16 != 0) {
                val shorter = (s.data.size - 1) / 16 * 16 + 1
                out += "DPCM sample ${i + 1} \"${s.name}\" is ${s.data.size} bytes; the DMC plays 16n + 1 bytes, e.g. " +
                    "$shorter or ${shorter + 16}. Re-import it or trim it"
            }
        }
        if (!out.isEmpty()) return
        val fs = FsSong.of(song)
        var row = 0
        for (o in song.orders.indices) {
            val p = song.orders[o]
            for (r in 0 until song.patterns[p].length) {
                val n = fs.notes[NesRules.DPCM][row++] ?: continue
                if (!n.isMusical) continue
                val where = "${Songs.patternLabel(song, p).cap()}, DPCM row $r"
                val note = n.value + NesRules.NOTE_OFFSET
                if (n.instrument == 0) {
                    out += "$where: ${NesSystem.noteName(note)} has no instrument and none has played on DPCM yet; enter the instrument whose DPCM samples it plays"
                } else {
                    NesRules.dpcmMappingProblem(song, n.instrument, note)?.let { out += "$where: $it" }
                }
            }
        }
        if (!out.isEmpty()) return
        val layout = NesDpcm.layout(fs)
        if (layout.playedBytes > Dpcm.MEMORY) {
            val used = fs.notes[NesRules.DPCM].mapNotNull { n ->
                if (n == null || !n.isMusical || n.instrument == 0) null
                else fs.instruments[n.instrument - 1].dpcm[n.value + NesRules.NOTE_OFFSET]?.sample
            }.toSet()
            val largest = used.sortedByDescending { fs.samples[it].data.size }.take(3)
                .joinToString { "\"${fs.samples[it].name}\" ${fs.samples[it].data.size}" }
            out += "The DPCM samples the song plays need ${layout.playedBytes} bytes in 64-byte slots; at most ${Dpcm.MEMORY} fit " +
                "(\$C000-\$FFF9, shared with the song data). Use fewer or shorter samples (largest: $largest bytes)"
        }
        if (layout.playedMappings > NesDpcm.MAX_ENTRIES) {
            out += "The song plays ${layout.playedMappings} different DPCM sample mappings (sample, rate, loop, initial value); " +
                "FamiStudio's player holds ${NesDpcm.MAX_ENTRIES}. Reuse samples at the same rate"
        }
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
                    val where = "${Songs.patternLabel(song, p).cap()}, ${NesRules.channelName(song.nes.expansion, ch)} row $row"
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
        if (song.nes.expansion == NesExpansion.VRC7) vrc7Warnings(song).forEach { out += it }
        if (song.nes.expansion == NesExpansion.S5B) NesS5b.warnings(song).forEach { out += it }
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
        for (ch in 0 until song.nes.expansion.channels) {
            if (ch == NesRules.DPCM) continue // an error there: a DPCM note needs an instrument's sample mapping
            loop@ for (p in song.orders) {
                val pattern = song.patterns[p]
                for (row in 0 until pattern.length) {
                    val cell = pattern.tracks[ch][row]
                    if (cell.note < 0) continue
                    if (cell.instrument == 0) {
                        out += "${Songs.patternLabel(song, p).cap()}, ${NesRules.channelName(song.nes.expansion, ch)} row $row: ${NesSystem.noteName(cell.note)} has no " +
                            "instrument and none has played on this channel yet; enter the instrument number"
                    }
                    break@loop
                }
            }
        }
        return out
    }

    /**
     * The VRC7's shared state, as FamiStudio's engine drives it: the chip has one custom patch, so a custom sound
     * starting while another FM channel still sounds a different custom sound changes that one's patch too; and the
     * engine keeps one release/stop sustain setting (`famistudio_chn_vrc7_sustain`), the last VRC7 sound loaded's, for
     * all six channels. [song] is the arrangement of a structurally valid song.
     */
    private fun vrc7Warnings(song: Song): List<String> {
        val out = ArrayList<String>()
        val fs = FsSong.of(song)
        val fm = NesRules.VRC7_FM1 until fs.channels
        fun custom(slot: Int) = slot != 0 && fs.instruments[slot - 1].let { it.chip == NesChip.VRC7 && it.fm.preset == Vrc7Presets.CUSTOM }
        val sounding = IntArray(fs.channels) // custom slot each FM channel is sounding (0: none)
        val reported = HashSet<Pair<Int, Int>>()
        for (row in 0 until fs.totalRows) {
            for (ch in fm) {
                val n = fs.notes[ch][row] ?: continue
                when {
                    n.isMusical -> {
                        if (custom(n.instrument)) {
                            for (other in fm) {
                                val playing = sounding[other]
                                if (other == ch || playing == 0 || playing == n.instrument || !reported.add(playing to n.instrument)) continue
                                val o = (0 until fs.orders).last { fs.orderStart[it] <= row }
                                out += "${Songs.patternLabel(song, fs.patternOfOrder[o]).cap()}, ${NesRules.channelName(song.nes.expansion, ch)} row " +
                                    "${row - fs.orderStart[o]}: custom sound ${NesRules.hex2(n.instrument)} loads the VRC7's one custom patch while " +
                                    "${NesRules.channelName(song.nes.expansion, other)} still sounds custom sound ${NesRules.hex2(playing)}, which then " +
                                    "plays the new patch; make one of them a built-in patch or keep them apart"
                            }
                        }
                        sounding[ch] = if (custom(n.instrument)) n.instrument else 0
                    }
                    n.value == FsNote.STOP -> sounding[ch] = 0
                }
                if (n.cutDelay >= 0) sounding[ch] = 0
            }
        }
        val used = fm.flatMap { ch -> fs.notes[ch].mapNotNull { n -> n?.takeIf { it.isMusical && it.instrument != 0 }?.instrument } }.toSortedSet()
        val settings = used.groupBy { fs.instruments[it - 1].fm.let { p -> p.sustainRelease to p.sustainStop } }
        if (settings.size > 1) {
            val names = settings.values.joinToString(" and ") { slots -> slots.joinToString(", ") { NesRules.hex2(it) } }
            out += "VRC7 sounds $names differ in Release/Stop sustain; FamiStudio's engine applies the setting of the last VRC7 " +
                "sound loaded to all six FM channels, so give them the same setting"
        }
        return out
    }

    /** Tonal notes of a tuned song that do not sound a tuned pitch (their Pxx missing, wrong or the slot taken). */
    private fun tunedNoteWarnings(song: Song, map: TuningMap): List<String> {
        val out = ArrayList<String>()
        song.patterns.forEachIndexed { p, pattern ->
            pattern.tracks.forEachIndexed { ch, track ->
                if (!NesRules.pitched(song.nes.expansion, ch)) return@forEachIndexed
                for (row in 0 until pattern.length) {
                    val cell = track[row]
                    if (cell.note < 0) continue
                    val where = "${Songs.patternLabel(song, p).cap()}, ${NesRules.channelName(song.nes.expansion, ch)} row $row"
                    val fine = NesSystem.fine.of(cell)
                    when {
                        fine == null -> if (map.index(cell.note, 0) < 0) {
                            out += "$where: tuned note needs this row's effect slot for Pxx but it holds " +
                                "${NesRules.effectText(cell.effect, cell.param)}; it plays at the plain 12-note pitch"
                        }
                        map.index(cell.note, fine) < 0 -> {
                            val pitch = NesSystem.noteName(cell.note) + if (fine > 0) " +$fine" else ""
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
