package org.thortracker.system.nes

import org.thortracker.backend.FamiStudioEnvelopes
import org.thortracker.backend.FamiStudioEnvelopes.Kind
import org.thortracker.backend.FamiStudioLowering
import org.thortracker.backend.FamiStudioText
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
            if (ins.chip != NesChip.APU && ins.dpcm.isNotEmpty()) out += "$where: only 2A03 sounds map DPCM samples"
            if (ins.chip == NesChip.EPSM) {
                if (ins.duty.values.isNotEmpty()) out += "$where: EPSM sounds have no duty envelope; clear it"
                if (ins.epsmEnvelope.octave !in S5bEnvelope.OCTAVES) out += "$where: SSG envelope octave ${ins.epsmEnvelope.octave} is outside -8..8"
                if (ins.epsmEnvelope.period !in S5bEnvelope.PERIODS) out += "$where: SSG envelope period ${ins.epsmEnvelope.period} is outside 0-65535"
                ins.epsm.problem()?.let { out += "$where: the FM patch $it" }
            } else {
                if (ins.mixer.values.isNotEmpty() || ins.noise.values.isNotEmpty()) out += "$where: only EPSM sounds have mixer and noise envelopes"
                if (ins.epsmEnvelope != S5bEnvelope.EPSM_DEFAULT) out += "$where: only EPSM sounds have an SSG hardware envelope"
                if (ins.epsm != EpsmPatch.DEFAULT) out += "$where: only EPSM sounds have an FM patch"
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
        val lfo = song.nes.epsmLfo
        when {
            expansion != NesExpansion.EPSM && lfo != 0 -> out += "Only EPSM songs have an LFO setting; this song's is $lfo"
            lfo != 0 && lfo !in NesSound.EPSM_LFO_ON..NesSound.EPSM_LFO_ON + 7 ->
                out += "The EPSM LFO register value $lfo is not off (0) or on with a rate (8-15)"
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
        val squares = if (song.nes.expansion == NesExpansion.EPSM) NesRules.EPSM_SSG1 until NesRules.EPSM_FM1 else IntRange.EMPTY
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
        if (song.nes.expansion == NesExpansion.EPSM) epsmWarnings(song).forEach { out += it }
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

    /** Whether [ins]'s SSG mixer lets noise through on some frame (an empty mixer is tone only). */
    private fun usesNoise(ins: NesInstrument): Boolean = FamiStudioEnvelopes.effective(ins.mixer, Kind.MIXER).values.any { it == 0 || it == 1 }

    /**
     * The EPSM's shared hardware as FamiStudio's sound engine 4.5.3 drives it, where one sound plays another's settings:
     * - SSG envelope generator: every frame the highest SSG square sounding a sound with a hardware envelope sets the
     *   generator's shape and period for all (`famistudio_update_epsm_square_channel_sound`); while it plays, the volume
     *   envelope, Cxx and Axy do not change that square's level.
     * - SSG noise generator: the highest sounding square sets the noise period; SSG 1 only when its mixer lets noise
     *   through, SSG 2 and 3 always (the engine tests their unshifted mixer value against `famistudio_epsm_square_noise_mask`
     *   bits meant for shifted values, so the test never skips them).
     * - FM LFO: one for the chip, the song's; FM sounds with AMS/PMS depths while it is off do not wobble.
     * - Drums: only the first volume value of a sound counts (the engine reads it once at the note).
     * [song] is the arrangement of a structurally valid EPSM song.
     */
    private fun epsmWarnings(song: Song): List<String> {
        val out = ArrayList<String>()
        val fs = FsSong.of(song)
        val expansion = song.nes.expansion
        val squares = NesRules.EPSM_SSG1 until NesRules.EPSM_FM1
        fun ins(slot: Int) = fs.instruments[slot - 1]
        fun envOn(slot: Int) = slot != 0 && ins(slot).let { it.chip == NesChip.EPSM && it.epsmEnvelope.shape != S5bShape.OFF }
        fun where(ch: Int, row: Int): String {
            val o = (0 until fs.orders).last { fs.orderStart[it] <= row }
            return "${Songs.patternLabel(song, fs.patternOfOrder[o]).cap()}, ${NesRules.channelName(expansion, ch)} row ${row - fs.orderStart[o]}"
        }
        val sounding = IntArray(fs.channels)
        val note = IntArray(fs.channels)
        val reported = HashSet<String>()
        for (row in 0 until fs.totalRows) {
            for (ch in squares) {
                val n = fs.notes[ch][row] ?: continue
                if (n.isMusical) {
                    if (n.instrument != 0) sounding[ch] = n.instrument
                    note[ch] = n.value
                } else if (n.value == FsNote.STOP) sounding[ch] = 0
                if (n.hasVolume && envOn(sounding[ch]) && reported.add("volume $ch ${sounding[ch]}")) {
                    out += "${where(ch, row)}: volume effects do not change sound ${NesRules.hex2(sounding[ch])}: its SSG hardware " +
                        "envelope sets the level (the volume envelope, Cxx and Axy are ignored while it plays)"
                }
            }
            val env = squares.filter { envOn(sounding[it]) }
            if (env.size > 1) {
                val owner = env.last()
                val own = ins(sounding[owner]).epsmEnvelope
                for (other in env.dropLast(1)) {
                    val e = ins(sounding[other]).epsmEnvelope
                    val differs = e.shape != own.shape || e.autoPitch != own.autoPitch ||
                        (if (own.autoPitch) e.octave != own.octave || note[other] != note[owner] else e.period != own.period)
                    if (differs && reported.add("env ${sounding[other]} ${sounding[owner]}")) {
                        out += "${where(other, row)}: sound ${NesRules.hex2(sounding[other])} and ${NesRules.channelName(expansion, owner)}'s " +
                            "sound ${NesRules.hex2(sounding[owner])} share the SSG's one envelope generator: while both sound, " +
                            "${NesRules.channelName(expansion, owner)}'s shape and period play on both (the highest square sets " +
                            "them); give them the same envelope and note, or keep them apart"
                    }
                }
            }
            val source = squares.lastOrNull { sounding[it] != 0 && (it != NesRules.EPSM_SSG1 || usesNoise(ins(sounding[it]))) }
            if (source != null) {
                val rate = FamiStudioEnvelopes.effective(ins(sounding[source]).noise, Kind.NOISE)
                for (ch in squares) {
                    if (ch == source || sounding[ch] == 0 || !usesNoise(ins(sounding[ch]))) continue
                    if (FamiStudioEnvelopes.effective(ins(sounding[ch]).noise, Kind.NOISE) == rate) continue
                    if (!reported.add("noise ${sounding[ch]} ${sounding[source]}")) continue
                    val quirk = if (!usesNoise(ins(sounding[source]))) ", even without noise in its mixer (FamiStudio's engine counts SSG 2 and 3 always)" else ""
                    out += "${where(ch, row)}: sound ${NesRules.hex2(sounding[ch])} plays noise, but ${NesRules.channelName(expansion, source)}'s " +
                        "sound ${NesRules.hex2(sounding[source])} sets the SSG's one noise rate$quirk; give both the same noise envelope or " +
                        "play the noise on the highest square"
                }
            }
            for (ch in squares) if ((fs.notes[ch][row]?.cutDelay ?: -1) >= 0) sounding[ch] = 0
        }
        val fmSlots = HashSet<Int>()
        val drumSlots = HashSet<Int>()
        for (ch in NesRules.EPSM_FM1 until fs.channels) for (n in fs.notes[ch]) {
            if (n == null || !n.isMusical || n.instrument == 0) continue
            if (NesRules.isEpsmRhythm(expansion, ch)) drumSlots += n.instrument else fmSlots += n.instrument
        }
        if (fs.epsmLfo == 0) {
            val wobbling = fmSlots.sorted().filter { ins(it).epsm.let { p -> p.ams > 0 || p.pms > 0 } }
            if (wobbling.isNotEmpty()) {
                out += "FM sound${if (wobbling.size == 1) "" else "s"} ${wobbling.joinToString(", ") { NesRules.hex2(it) }} set LFO depths " +
                    "(AMS/PMS), but the song's LFO is off; turn it on in the Song view (LFO), which all FM channels share"
            }
        }
        val stepped = drumSlots.sorted().filter { ins(it).volume.values.distinct().size > 1 }
        if (stepped.isNotEmpty()) {
            out += "Drum sound${if (stepped.size == 1) "" else "s"} ${stepped.joinToString(", ") { NesRules.hex2(it) }} have a changing " +
                "volume envelope, but the EPSM drums play only its first value (times Cxx); use Cxx or Axy for accents"
        }
        // The FamiStudio text export refuses such a sound (FamiStudioText.manualPeriodProblem); tell before the export does.
        FamiStudioText.manualPeriodProblem(fs)?.let { out += it }
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
