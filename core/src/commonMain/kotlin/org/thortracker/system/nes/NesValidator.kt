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
                    NesRules.cellRangeProblem(expansion, cell)?.let { out += "$label, ${NesRules.channelName(expansion, ch)} row $row: $it" }
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
        if (expansion != NesExpansion.VRC7 && song.nes.vrc7Sustain != Vrc7Sustain()) out += "Only VRC7 songs have the FM sustain settings"
        val lfo = song.nes.epsmLfo
        when {
            expansion != NesExpansion.EPSM && lfo != 0 -> out += "Only EPSM songs have an LFO setting; this song's is $lfo"
            lfo != 0 && lfo !in NesSound.EPSM_LFO_ON..NesSound.EPSM_LFO_ON + 7 ->
                out += "The EPSM LFO register value $lfo is not off (0) or on with a rate (8-15)"
        }
        bank.forEachIndexed { i, ins ->
            val where = "Instrument ${NesRules.hex2(i + 1)}"
            if (ins.chip !in expansion.chips) out += "$where is a ${ins.chip.label} sound; this song has no ${ins.chip.label} chip"
            if (ins.chip != NesChip.VRC6 && ins.sawVolume != Vrc6SawVolume.HALF) out += "$where: only VRC6 sounds have a saw volume"
            if (ins.chip != NesChip.VRC7 && ins.fm != Vrc7Patch.DEFAULT) out += "$where: only VRC7 sounds have an FM patch"
            if (ins.chip != NesChip.N163 && ins.wave != N163Wave.DEFAULT) out += "$where: only N163 sounds have waves"
            if (ins.chip != NesChip.FDS && ins.fds != FdsSound.DEFAULT) out += "$where: only FDS sounds have FDS waves and modulation"
            if (ins.chip != NesChip.APU && ins.dpcm.isNotEmpty()) out += "$where: only 2A03 sounds map DPCM samples"
            if (ins.chip == NesChip.VRC7) {
                if (ins.duty.values.isNotEmpty()) out += "$where: VRC7 sounds have no duty envelope; clear it"
                if (ins.fm.preset !in 0..Vrc7Presets.PRESETS.last) out += "$where: VRC7 patch ${ins.fm.preset} is outside 0-15 (0 = custom)"
                if (ins.fm.regs.size != Vrc7Patch.REGS) out += "$where: the VRC7 custom patch has ${ins.fm.regs.size} registers; it has ${Vrc7Patch.REGS}"
                ins.fm.regs.firstOrNull { it !in 0..255 }?.let { out += "$where: VRC7 patch register value $it is outside 0-255" }
                if (ins.fm.preset in Vrc7Presets.PRESETS && ins.fm.regs != Vrc7Presets.data(ins.fm.preset)) {
                    out += "$where: built-in VRC7 patch ${ins.fm.preset} keeps its ROM registers (as FamiStudio does); only Custom (0) has its own"
                }
            }
            if (ins.chip == NesChip.N163) {
                if (ins.duty.values.isNotEmpty()) out += "$where: N163 sounds have no duty envelope; clear it"
                n163Shape(out, where, ins.wave)
            }
            if (ins.chip == NesChip.FDS) {
                if (ins.duty.values.isNotEmpty()) out += "$where: FDS sounds have no duty envelope; clear it"
                fdsShape(out, where, ins.fds)
            }
            if (ins.chip.hasSsg) {
                val chip = if (ins.chip == NesChip.EPSM) "EPSM" else "5B"
                if (ins.duty.values.isNotEmpty()) out += "$where: $chip sounds have no duty envelope; clear it"
                val env = if (ins.chip == NesChip.EPSM) "SSG" else "5B"
                if (ins.s5b.octave !in S5bEnvelope.OCTAVES) out += "$where: $env envelope octave ${ins.s5b.octave} is outside -8..8"
                if (ins.s5b.period !in S5bEnvelope.PERIODS) out += "$where: $env envelope period ${ins.s5b.period} is outside 0-65535"
            } else {
                if (ins.mixer.values.isNotEmpty() || ins.noise.values.isNotEmpty()) out += "$where: only 5B and EPSM sounds have mixer and noise envelopes"
                if (ins.s5b != S5bEnvelope.DEFAULT) out += "$where: only 5B and EPSM sounds have a hardware envelope"
            }
            if (ins.chip == NesChip.EPSM) ins.epsm.problem()?.let { out += "$where: the FM patch $it" }
            else if (ins.epsm != EpsmPatch.DEFAULT) out += "$where: only EPSM sounds have an EPSM FM patch"
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

    /** The shape of an N163 sound's waves: what every consumer relies on (sizes, ranges, one repeat per wave). */
    private fun n163Shape(out: Report, where: String, w: N163Wave) {
        if (w.size < N163Wave.MIN_SIZE || w.size % 4 != 0 || w.size > NesRules.N163_RAM_SAMPLES) {
            out += "$where: the N163 wave size ${w.size} is not a multiple of 4 from ${N163Wave.MIN_SIZE} to ${NesRules.N163_RAM_SAMPLES}"
            return
        }
        if (w.position < 0 || w.position % 2 != 0 || w.position >= NesRules.N163_RAM_SAMPLES) {
            out += "$where: the N163 wave position ${w.position} is not an even sample 0-${NesRules.N163_RAM_SAMPLES - 2} (waves start on a byte)"
        }
        if (w.waves.size !in 1..N163Wave.maxWaves(w.size)) {
            out += "$where: ${w.waves.size} N163 waves; a sound holds 1-${N163Wave.maxWaves(w.size)} of ${w.size} samples " +
                "(FamiStudio keeps at most ${N163Wave.MAX_SAMPLES} samples and ${N163Wave.MAX_WAVES} waves)"
        }
        w.waves.forEachIndexed { i, wave ->
            if (wave.size != w.size) out += "$where: N163 wave ${i + 1} has ${wave.size} samples; the sound's waves have ${w.size}"
            wave.firstOrNull { it !in N163Wave.SAMPLE }?.let { out += "$where: N163 wave ${i + 1} sample $it is outside 0-15" }
        }
        if (w.repeat.size != w.waves.size) out += "$where: ${w.repeat.size} N163 wave repeats for ${w.waves.size} waves; each wave has one"
        w.repeat.firstOrNull { it !in N163Wave.REPEAT }?.let { out += "$where: an N163 wave plays for $it frames; 1-15 frames per wave" }
        if (w.loop !in -1 until w.waves.size) out += "$where: the N163 wave loop ${w.loop} is outside the sequence"
        if (w.release !in -1 until w.waves.size) out += "$where: the N163 wave release ${w.release} is outside the sequence"
    }

    /**
     * The shape of an FDS sound: what every consumer relies on (FamiStudio 4.5.3's limits, `Envelope.cs` and
     * `ParamProviders.cs`: 1-16 waves of 64 samples 0..63, a repeat of 1-15 frames per wave, 32 modulation targets
     * −64..63, mod speed 0-4095, depth 0-63, delay 0-255, auto-mod numerator and denominator 1-32).
     */
    private fun fdsShape(out: Report, where: String, f: FdsSound) {
        if (f.waves.size !in 1..FdsSound.MAX_WAVES) out += "$where: ${f.waves.size} FDS waves; a sound holds 1-${FdsSound.MAX_WAVES} (FamiStudio's limit)"
        f.waves.forEachIndexed { i, wave ->
            if (wave.size != FdsSound.WAVE_SIZE) out += "$where: FDS wave ${i + 1} has ${wave.size} samples; FDS waves have ${FdsSound.WAVE_SIZE}"
            wave.firstOrNull { it !in FdsSound.SAMPLE }?.let { out += "$where: FDS wave ${i + 1} sample $it is outside 0-63" }
        }
        if (f.repeat.size != f.waves.size) out += "$where: ${f.repeat.size} FDS wave repeats for ${f.waves.size} waves; each wave has one"
        f.repeat.firstOrNull { it !in FdsSound.REPEAT }?.let { out += "$where: an FDS wave plays for $it frames; 1-15 frames per wave" }
        if (f.loop !in -1 until f.waves.size) out += "$where: the FDS wave loop ${f.loop} is outside the sequence"
        if (f.release !in -1 until f.waves.size) out += "$where: the FDS wave release ${f.release} is outside the sequence"
        if (f.modulation.size != FdsSound.MOD_SIZE) out += "$where: the FDS modulation table has ${f.modulation.size} entries; it has ${FdsSound.MOD_SIZE}"
        f.modulation.firstOrNull { it !in FdsSound.MOD_TARGET }?.let { out += "$where: FDS modulation value $it is outside -64..63" }
        if (f.modSpeed !in FdsSound.MOD_SPEED) out += "$where: FDS mod speed ${f.modSpeed} is outside 0-4095"
        if (f.modDepth !in FdsSound.MOD_DEPTH) out += "$where: FDS mod depth ${f.modDepth} is outside 0-63"
        if (f.modDelay !in FdsSound.MOD_DELAY) out += "$where: FDS mod delay ${f.modDelay} is outside 0-255 frames"
        if (f.autoModNumer !in FdsSound.AUTO_MOD || f.autoModDenom !in FdsSound.AUTO_MOD) {
            out += "$where: the FDS auto-mod ratio ${f.autoModNumer}/${f.autoModDenom} is outside 1-32 / 1-32"
        }
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
            if (ins.chip == NesChip.N163) n163Rules(out, where, ins.wave, song.nes.expansion.n163Channels)
            if (ins.chip == NesChip.FDS) fdsRules(out, where, ins.fds)
        }
        val played = song.orders.toSet()
        val squares = NesRules.ssgChannels(song.nes.expansion)
        val ssg = NesRules.ssgLabel(song.nes.expansion)
        document.patterns.forEachIndexed { p, pattern ->
            if (p !in played) return@forEachIndexed
            val label = Songs.patternLabel(song, p).cap()
            pattern.tracks.forEachIndexed { ch, track ->
                for (row in 0 until pattern.length) {
                    NesRules.cellProblem(song, ch, track[row])?.let { out += "$label, ${NesRules.channelName(song.nes.expansion, ch)} row $row: $it" }
                }
            }
            if (!squares.isEmpty()) for (row in 0 until pattern.length) {
                NesRules.envPeriodConflict(squares.map { pattern.tracks[it][row] }, ssg)?.let { out += "$label, row $row: $it" }
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
        if (song.nes.expansion.chip == NesChip.N163) n163Warnings(song).forEach { out += it }
        if (song.nes.expansion == NesExpansion.FDS) fdsWarnings(song).forEach { out += it }
        if (!NesRules.ssgChannels(song.nes.expansion).isEmpty()) NesS5b.warnings(song).forEach { out += it }
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

    /**
     * The EPSM's shared FM LFO and its rhythm drums, as FamiStudio's sound engine 4.5.3 plays them (the SSG's shared
     * generators: [NesS5b.warnings]):
     * - FM LFO: one for the chip, the song's; FM sounds with AMS/PMS depths while it is off do not wobble.
     * - Drums: only the first volume value of a sound counts (the engine reads it once at the load), and a drum note
     *   without an instrument is legato like any other note: without an attack the engine does not key the drum again.
     * - FamiStudio's text format cannot carry a manual SSG envelope period other than 4000 ([FamiStudioText.manualPeriodProblem]).
     * [song] is the arrangement of a structurally valid EPSM song.
     */
    private fun epsmWarnings(song: Song): List<String> {
        val out = ArrayList<String>()
        val fs = FsSong.of(song)
        val expansion = song.nes.expansion
        fun ins(slot: Int) = fs.instruments[slot - 1]
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
        for (ch in NesRules.EPSM_RHYTHM1 until fs.channels) {
            val row = (0 until fs.totalRows).firstOrNull { fs.notes[ch][it]?.let { n -> n.isMusical && !n.attack } == true } ?: continue
            val o = (0 until fs.orders).last { fs.orderStart[it] <= row }
            out += "${Songs.patternLabel(song, fs.patternOfOrder[o]).cap()}, ${NesRules.channelName(expansion, ch)} row " +
                "${row - fs.orderStart[o]}: a drum note without an instrument is legato and does not hit the drum again; enter " +
                "the sound number on every hit"
        }
        // The FamiStudio text export refuses such a sound (FamiStudioText.manualPeriodProblem); tell before the export does.
        FamiStudioText.manualPeriodProblem(fs)?.let { out += it }
        return out
    }

    /**
     * The VRC7's one custom patch: a custom sound starting while another FM channel still holds a note of a different
     * custom sound changes that note's patch too (the chip has one set of custom registers; built-in patches have no
     * such limit). A released or stopped note no longer counts (its key-off tail may still change). [song] is the
     * arrangement of a structurally valid song.
     */
    private fun vrc7Warnings(song: Song): List<String> {
        val out = ArrayList<String>()
        val fs = FsSong.of(song)
        val fm = NesRules.VRC7_FM1 until fs.channels
        fun custom(slot: Int) = slot != 0 && fs.instruments[slot - 1].let { it.chip == NesChip.VRC7 && it.fm.preset == Vrc7Presets.CUSTOM }
        val holding = IntArray(fs.channels) // custom slot each FM channel holds a note of (0: none)
        val reported = HashSet<Pair<Int, Int>>()
        for (row in 0 until fs.totalRows) {
            for (ch in fm) {
                val n = fs.notes[ch][row] ?: continue
                when {
                    n.isMusical -> {
                        if (custom(n.instrument)) {
                            for (other in fm) {
                                val held = holding[other]
                                // Sounds with the same registers (a copied sound) load the same patch: no change heard.
                                if (other == ch || held == 0 || fs.instruments[held - 1].fm.regs == fs.instruments[n.instrument - 1].fm.regs ||
                                    !reported.add(held to n.instrument)) continue
                                val o = (0 until fs.orders).last { fs.orderStart[it] <= row }
                                out += "${Songs.patternLabel(song, fs.patternOfOrder[o]).cap()}, ${NesRules.channelName(song.nes.expansion, ch)} row " +
                                    "${row - fs.orderStart[o]}: custom sound ${NesRules.hex2(n.instrument)} loads the VRC7's one custom patch while " +
                                    "${NesRules.channelName(song.nes.expansion, other)} still holds custom sound ${NesRules.hex2(held)}, which then " +
                                    "plays the new patch; make one of them a built-in patch or end that note first"
                            }
                        }
                        holding[ch] = if (custom(n.instrument)) n.instrument else 0
                    }
                    n.value == FsNote.STOP || n.value == FsNote.RELEASE -> holding[ch] = 0
                }
                if (n.cutDelay >= 0) holding[ch] = 0
            }
        }
        return out
    }

    /** What FamiStudio's engine and text import require of an N163 sound's waves with [channels] N163 channels enabled. */
    private fun n163Rules(out: Report, where: String, w: N163Wave, channels: Int) {
        val ram = NesRules.n163WaveRam(channels)
        if (w.position + w.size > ram) {
            out += "$where: its N163 wave (RAM samples ${w.position}-${w.position + w.size - 1}) runs into the channel registers: with " +
                "$channels N163 channel${if (channels == 1) "" else "s"} waves fit samples 0-${ram - 1} (each channel's registers take 16 " +
                "samples at the top). Move it to ${maxOf(0, ram - w.size)} or below, or shrink it"
        }
        if (w.release >= 0 && w.loop < 0) {
            out += "$where: the N163 wave release needs a wave loop before it (FamiStudio sustains a note by looping until it is released); set a loop"
        }
        if (w.release >= 0 && w.loop >= 0 && w.loop >= w.release) {
            out += "$where: the N163 wave loop (wave ${w.loop + 1}) must come before the release (wave ${w.release + 1})"
        }
        val size = FamiStudioEnvelopes.waveIndex(w).size
        if (size > 255) out += "$where: the N163 wave sequence takes $size bytes once encoded; FamiStudio's limit is 255. Use fewer waves or repeats"
    }

    /**
     * What FamiStudio's engine and text import require of an FDS sound: the wave sequence's loop and release rules (as an
     * N163 sound's: `Envelope` loop/release setters) and its encoded size. Every manual mod speed 0..4095 plays as itself
     * (the lowering tags those whose low byte is $80-$FF for Thor Tracker's FDS player).
     */
    private fun fdsRules(out: Report, where: String, f: FdsSound) {
        if (f.release >= 0 && f.loop < 0) {
            out += "$where: the FDS wave release needs a wave loop before it (FamiStudio sustains a note by looping until it is released); set a loop"
        }
        if (f.release >= 0 && f.loop >= 0 && f.loop >= f.release) {
            out += "$where: the FDS wave loop (wave ${f.loop + 1}) must come before the release (wave ${f.release + 1})"
        }
        val size = FamiStudioEnvelopes.waveIndex(f).size
        if (size > 255) out += "$where: the FDS wave sequence takes $size bytes once encoded; FamiStudio's limit is 255. Use fewer waves or repeats"
    }

    /**
     * The N163's shared RAM as FamiStudio's engine uses it: each channel uploads its sound's current wave to that sound's
     * RAM position whenever the sequence moves to another wave (on every attack and every wave change), so two channels
     * sounding at once with overlapping RAM ranges overwrite each other's waves (identical single waves at the same place,
     * or the same single-wave sound, share safely). Also off-key wave sizes and silent all-zero waves of played sounds.
     * [song] is the arrangement of a structurally valid song.
     */
    private fun n163Warnings(song: Song): List<String> {
        val out = ArrayList<String>()
        val fs = FsSong.of(song)
        val expansion = song.nes.expansion
        val n163 = NesRules.N163_FIRST until fs.channels
        val playing = IntArray(fs.channels) // slot each N163 channel sounds (0: none)
        val attack = IntArray(fs.channels) // row of its last attack
        val reported = HashSet<Pair<Int, Int>>()
        val used = HashSet<Int>()
        // One pass, then the loop again: notes still sounding at the end overlap the loop's first rows.
        val rows = (0 until fs.totalRows) + (fs.orderStart[fs.loopOrder] until fs.totalRows)
        for (row in rows) {
            for (ch in n163) {
                val n = fs.notes[ch][row] ?: continue
                when {
                    n.isMusical && n.instrument != 0 -> {
                        if (n.attack || playing[ch] != n.instrument) attack[ch] = row
                        playing[ch] = n.instrument
                        used += n.instrument
                    }
                    n.value == FsNote.STOP -> playing[ch] = 0
                }
                if (n.cutDelay >= 0) playing[ch] = 0
            }
            for (a in n163) for (b in n163) {
                if (b <= a || playing[a] == 0 || playing[b] == 0) continue
                val sa = playing[a]
                val sb = playing[b]
                val wa = fs.instruments[sa - 1].wave
                val wb = fs.instruments[sb - 1].wave
                val shared = maxOf(wa.position, wb.position)..minOf(wa.position + wa.size, wb.position + wb.size) - 1
                if (shared.isEmpty() || sharesSafely(sa, wa, sb, wb, attack[a] == attack[b])) continue
                if (!reported.add(minOf(sa, sb) to maxOf(sa, sb))) continue
                val o = (0 until fs.orders).last { fs.orderStart[it] <= row }
                out += "${Songs.patternLabel(song, fs.patternOfOrder[o]).cap()} row ${row - fs.orderStart[o]}: " +
                    "${NesRules.channelName(expansion, a)} plays sound ${NesRules.hex2(sa)} (N163 RAM ${ramText(wa)}) while " +
                    "${NesRules.channelName(expansion, b)} plays sound ${NesRules.hex2(sb)} (RAM ${ramText(wb)}): they share samples " +
                    "${shared.first}-${shared.last}, so each overwrites the other's wave when it loads one. Give one of them another " +
                    "wave position (Instrument view, Wave page) or play them apart"
            }
        }
        for (slot in used.sorted()) {
            val ins = fs.instruments[slot - 1]
            val w = ins.wave
            if (!NesRules.n163InTune(w.size)) {
                val t = NesRules.n163Transpose(w.size)
                out += "Sound ${NesRules.hex2(slot)} \"${ins.name}\": its ${w.size}-sample N163 wave sounds " +
                    "${formatSemitones(kotlin.math.abs(t))} semitones ${if (t < 0) "below" else "above"} the notes (FamiStudio tunes the " +
                    "N163 for 16-sample waves; 4, 8, 32, 64 or 128 samples stay in tune, whole octaves away)"
            }
            if (w.waves.all { wave -> wave.all { it == 0 } }) {
                out += "Sound ${NesRules.hex2(slot)} \"${ins.name}\": all its N163 samples are 0, which is silent (a constant level " +
                    "only clicks as the volume changes); draw a wave or pick a preset on the Wave page"
            }
        }
        return out
    }

    /**
     * The FDS channel as FamiStudio's engine plays it ([song] is the arrangement of a structurally valid song): auto-mod
     * whose 16-bit multiply overflows on notes the song plays (`famistudio_mul`: frequency × numerator ≥ 65536 drops the
     * carry, so the mod speed comes out far too low), Ixx/Jxx while an auto-mod sound plays (the engine then computes the
     * speed from the pitch every frame and ignores them), silent waves, and modulation that cannot sound: a table, speed
     * or auto-mod at Mod depth 0, a depth with a flat table or with speed 0 (the table stands still), unless effects set
     * what is missing.
     */
    private fun fdsWarnings(song: Song): List<String> {
        val out = ArrayList<String>()
        val fs = FsSong.of(song)
        val ch = NesRules.FDS
        val top = HashMap<Int, Int>() // auto-mod slot → the highest engine note it plays
        val used = BooleanArray(fs.instruments.size + 1)
        val ignored = HashSet<Int>()
        var current = 0
        var depthEffects = false
        var speedEffects = false
        fun where(row: Int): String {
            val o = (0 until fs.orders).last { fs.orderStart[it] <= row }
            return "${Songs.patternLabel(song, fs.patternOfOrder[o]).cap()}, ${NesRules.channelName(song.nes.expansion, ch)} row ${row - fs.orderStart[o]}"
        }
        for (row in 0 until fs.totalRows) {
            val n = fs.notes[ch][row] ?: continue
            if (n.isMusical && n.instrument != 0) {
                current = n.instrument
                used[current] = true
                val ins = fs.instruments[current - 1]
                if (ins.fds.autoMod) {
                    val arp = maxOf(0, ins.arpeggio.values.maxOrNull() ?: 0, if (n.arpeggio >= 0) maxOf(n.arpeggio shr 4, n.arpeggio and 0xF) else 0)
                    top[current] = maxOf(top[current] ?: 0, maxOf(n.value, n.slideTarget) + arp)
                }
            }
            if (n.fdsModDepth > 0) depthEffects = true
            if (n.fdsModSpeed >= 0) {
                speedEffects = true
                // A row whose own sound is auto-mod is an error (NesRules.cellProblem); here the sound plays on.
                val own = n.isMusical && n.attack
                if (!own && current != 0 && fs.instruments[current - 1].fds.autoMod && ignored.add(current)) {
                    out += "${where(row)}: I/J have no effect while an auto-mod sound plays: sound ${NesRules.hex2(current)} computes " +
                        "its mod speed from the pitch every frame; remove the Ixx/Jxx or play a sound without Auto-mod"
                }
            }
        }
        for (slot in 1 until used.size) {
            if (!used[slot]) continue
            val ins = fs.instruments[slot - 1]
            val f = ins.fds
            val name = "Sound ${NesRules.hex2(slot)} \"${ins.name}\""
            top[slot]?.let { high ->
                val (numer, denom) = f.autoModFraction
                val from = NesRules.fdsLowestOverflowingNote(numer)
                if (from != null && NesRules.fdsAutoModOverflows(minOf(high, NesRules.FDS_HIGHEST_FS_NOTE), numer)) {
                    val plays = minOf(high, NesRules.FDS_HIGHEST_FS_NOTE) - NesRules.FDS_TRANSPOSE + NesRules.NOTE_OFFSET
                    out += "$name: auto-mod $numer/$denom overflows FamiStudio's 16-bit multiply (frequency × $numer ≥ 65536) from " +
                        "${NesSystem.noteName(from)} up, and the song plays it up to ${NesSystem.noteName(plays)}: the mod speed there comes " +
                        "out far too low. Use a smaller ratio (e.g. halve both numbers) or play it lower"
                }
            }
            val firstSample = f.waves[0][0]
            if (f.waves.all { wave -> wave.all { it == firstSample } }) {
                out += "$name: all its FDS samples are $firstSample, which is silent (a constant level only clicks as the volume " +
                    "changes); draw a wave or pick a preset"
            }
            val flat = f.modulation.all { it == 0 }
            when {
                f.modDepth == 0 && !depthEffects && (!flat || f.modSpeed > 0 || f.autoMod) ->
                    out += "$name: its modulation (table, speed or auto-mod) does nothing at Mod depth 0; set a depth (or use Hxx) " +
                        "or clear the modulation"
                f.modDepth > 0 && flat ->
                    out += "$name: Mod depth ${f.modDepth} with a flat modulation table bends nothing; draw the table or pick a preset"
                f.modDepth > 0 && !f.autoMod && f.modSpeed == 0 && !speedEffects ->
                    out += "$name: Mod depth ${f.modDepth} with mod speed 0: the modulation table stands still, so nothing is " +
                        "modulated; set a speed, turn Auto-mod on or use Ixx/Jxx"
            }
        }
        return out
    }

    /** Two sounds overlapping in N163 RAM that never disagree there: one single wave, the same place and the same samples. */
    private fun sharesSafely(sa: Int, wa: N163Wave, sb: Int, wb: N163Wave, sameAttack: Boolean): Boolean = when {
        sa == sb -> wa.waves.size == 1 || sameAttack
        else -> wa.waves.size == 1 && wb.waves.size == 1 && wa.position == wb.position && wa.size == wb.size && wa.waves == wb.waves
    }

    private fun ramText(w: N163Wave) = "${w.position}-${w.position + w.size - 1}"

    private fun formatSemitones(v: Double): String {
        val tenths = kotlin.math.round(v * 10).toInt()
        return "${tenths / 10}.${tenths % 10}"
    }

    /**
     * N163 RAM samples the song's played N163 sounds occupy (the union of their wave ranges), for Help's limits. 0 for
     * songs without the N163 or with errors in their structure.
     */
    fun n163RamUsed(document: Song): Int {
        if (document.nes.expansion.chip != NesChip.N163 || structuralErrors(document).isNotEmpty()) return 0
        val song = NesSystem.arranged(document)
        val samples = HashSet<Int>()
        for (p in song.orders.toSet()) for (ch in NesRules.N163_FIRST until song.nes.expansion.channels) {
            for (cell in song.patterns[p].tracks[ch]) {
                if (cell.note >= 0 && cell.instrument != 0) {
                    song.nes.instruments.getOrNull(cell.instrument - 1)?.takeIf { it.chip == NesChip.N163 }?.let { samples += it.wave.ram }
                }
            }
        }
        return samples.size
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
