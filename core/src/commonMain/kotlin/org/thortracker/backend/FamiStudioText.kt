package org.thortracker.backend

import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.nes.DpcmSample
import org.thortracker.system.nes.NesChip
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesExpansion
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesSound

/**
 * FamiStudio 4.5 text export (`FamistudioTextFile`): a FamiTracker-tempo project FamiStudio 4.5.3 opens (File →
 * Import, or `FamiStudio song.txt <command>` on its command line), from which it exports ca65/asm6/NESASM data for
 * games. It describes the same [FsSong] the preview lowers: DPCM samples (one per distinct data, the ones the song
 * plays first and in [NesDpcm]'s order, so FamiStudio packs them at the same addresses), instruments (named "slot
 * name", with their DPCM mappings), one arpeggio per `0xy` used, and per channel one FamiStudio pattern per distinct
 * order content (FamiStudio keeps note durations and releases inside patterns, so an order whose notes end or release
 * differently gets its own copy). A song with an expansion chip names it (`Expansions="VRC6"`), its instruments of
 * that chip carry `Expansion="VRC6"` and the saw master volume (`Expansion="EPSM"`: the FM patch as Custom patch 0
 * with its 31 registers, register 30 the song's LFO, the SSG envelope shape, auto-pitch and octave, and the
 * `MixerSettings` and `NoiseFreq` envelopes), its channels follow the 2A03's, and an SSG envelope period is the
 * `EnvelopePeriod` of SSG 1's note.
 *
 * FamiStudio 4.5.3's text format has no attribute for the EPSM SSG's manual envelope period (its import keeps the
 * default 4000), so an EPSM sound with another manual period cannot be exported as text ([manualPeriodProblem]);
 * `.nsf`, WAV and playback carry it.
 */
object FamiStudioText {
    private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** FamiStudio's channel type names (`ChannelType.InternalNames`), in engine order: the 2A03's, then the expansion's. */
    private val APU_TYPES = listOf("Square1", "Square2", "Triangle", "Noise", "DPCM")

    private fun channelType(expansion: NesExpansion, c: Int): String = when {
        c < APU_TYPES.size -> APU_TYPES[c]
        expansion == NesExpansion.VRC6 -> listOf("VRC6Square1", "VRC6Square2", "VRC6Saw")[c - APU_TYPES.size]
        NesRules.isEpsmSsg(expansion, c) -> "EPSMSquare${c - NesRules.EPSM_SSG1 + 1}"
        NesRules.isEpsmFm(expansion, c) -> "EPSMFM${c - NesRules.EPSM_FM1 + 1}"
        else -> "EPSMRythm${c - NesRules.EPSM_RHYTHM1 + 1}"
    }

    /** FamiStudio's expansion names (`ExpansionType.InternalNames`). */
    private fun expansionName(chip: NesChip): String = when (chip) {
        NesChip.APU -> ""
        NesChip.VRC6 -> "VRC6"
        NesChip.EPSM -> "EPSM"
    }

    /** FamiStudio's EPSM manual envelope period default, the only one its text import yields. */
    const val EPSM_TEXT_PERIOD = 4000

    fun export(song: Song): ByteArray = text(song).encodeToByteArray()

    /** @throws IllegalArgumentException for a song with errors. */
    fun text(song: Song): String {
        val errors = org.thortracker.system.nes.NesSystem.errors(song)
        require(errors.isEmpty()) { errors.first() }
        return text(FsSong.of(song))
    }

    internal fun text(fs: FsSong): String {
        manualPeriodProblem(fs)?.let { throw IllegalArgumentException(it) }
        val out = StringBuilder()
        fun line(depth: Int, s: String) {
            repeat(depth) { out.append('\t') }
            out.append(s).append('\n')
        }
        line(0, "Project" + attr("Version", "4.5.3") + attr("TempoMode", "FamiTracker") +
            attr("Name", fs.title.ifBlank { "Untitled" }) + (if (fs.artist.isNotBlank()) attr("Author", fs.artist) else "") +
            (if (fs.expansion != NesExpansion.NONE) attr("Expansions", expansionName(fs.expansion.chip)) else ""))
        val names = instrumentNames(fs)
        val samples = sampleNames(fs, names.keys)
        for ((data, name) in samples) {
            val first = fs.samples.first { it.data == data }
            line(1, "DPCMSample" + attr("Name", name) +
                (if (first.dmcInitial != DpcmSample.DEFAULT_DMC_INITIAL) attr("DmcInitialValue", first.dmcInitial) else "") +
                attr("Data", data.joinToString("") { NumberText.hex(it, 2).lowercase() }))
        }
        for ((slot, name) in names) {
            val ins = fs.instruments[slot - 1]
            line(1, "Instrument" + attr("Name", name) + when (ins.chip) {
                NesChip.APU -> ""
                NesChip.VRC6 -> attr("Expansion", "VRC6") + attr("Vrc6SawMasterVolume", ins.sawVolume.label)
                // `FamistudioTextFile`: EpsmPatch (always Custom, 0) and its 31 registers, then the SSG envelope settings.
                NesChip.EPSM -> attr("Expansion", "EPSM") + attr("EpsmPatch", 0) +
                    ins.epsm.registers(fs.epsmLfo).withIndex().joinToString("") { (i, r) -> attr("EpsmReg$i", r) } +
                    attr("EPSMSquareEnvelopeShape", ins.epsmEnvelope.shape.famiStudio) +
                    attr("EPSMSquareEnvelopeAutoPitch", if (ins.epsmEnvelope.autoPitch) "True" else "False") +
                    attr("EPSMSquareEnvelopeAutoPitchOctave", ins.epsmEnvelope.octave)
            })
            for ((note, m) in ins.dpcm.entries.sortedBy { it.key }.map { it.key to it.value }) {
                val sample = fs.samples.getOrNull(m.sample) ?: continue
                val shared = samples.getValue(sample.data)
                val sampleDefault = fs.samples.first { it.data == sample.data }.dmcInitial
                val initial = if (m.dmcInitial >= 0) m.dmcInitial else sample.dmcInitial
                line(2, "DPCMMapping" + attr("Note", noteName(note - NesRules.NOTE_OFFSET)) + attr("Sample", shared) +
                    attr("Pitch", m.pitch) + attr("Loop", if (m.loop) "True" else "False") + attr("Bank", 0) +
                    (if (initial != sampleDefault || m.dmcInitial >= 0) attr("DmcInitialValue", initial) else ""))
            }
            envelope(::line, "Volume", ins.volume, 15, false)
            envelope(::line, "Arpeggio", ins.arpeggio, 0, false)
            envelope(::line, "Pitch", ins.pitch, 0, ins.pitchRelative)
            envelope(::line, "DutyCycle", ins.duty, 0, false)
            if (ins.chip == NesChip.EPSM) {
                envelope(::line, "MixerSettings", ins.mixer, FamiStudioEnvelopes.MIXER_TONE, false)
                envelope(::line, "NoiseFreq", ins.noise, 0, false)
            }
        }
        val arpeggios = HashSet<Int>()
        for (ch in fs.notes) for (n in ch) if (n != null && n.isMusical && n.arpeggio >= 0) arpeggios += n.arpeggio
        for (a in arpeggios.sorted()) {
            line(1, "Arpeggio" + attr("Name", arpName(a)) + attr("Length", 3) + attr("Loop", 0) +
                attr("Values", "0,${a shr 4},${a and 0xF}"))
        }
        val common = fs.lengths.groupBy { it }.maxByOrNull { (len, list) -> list.size * 1000 + len }!!.key
        line(1, "Song" + attr("Name", fs.title.ifBlank { "Untitled" }) + attr("Length", fs.orders) + attr("LoopPoint", fs.loopOrder) +
            attr("PatternLength", common) + attr("BeatLength", minOf(4, common)) + attr("FamiTrackerTempo", FamiStudioLowering.TEMPO) +
            attr("FamiTrackerSpeed", fs.speed))
        for (o in 0 until fs.orders) if (fs.lengths[o] != common) {
            line(2, "PatternCustomSettings" + attr("Time", o) + attr("Length", fs.lengths[o]))
        }
        for (c in 0 until fs.channels) {
            line(2, "Channel" + attr("Type", channelType(fs.expansion, c)))
            val patterns = LinkedHashMap<List<String>, String>()
            val instances = ArrayList<Pair<Int, String>>()
            val variants = HashMap<Int, Int>()
            for (o in 0 until fs.orders) {
                val notes = orderNotes(fs, c, o, names)
                if (notes.isEmpty()) continue
                val name = patterns.getOrPut(notes) {
                    val p = fs.patternOfOrder[o]
                    val n = (variants[p] ?: 0) + 1
                    variants[p] = n
                    val base = "${NumberText.hex(p, 2)} ${fs.patternNames[p]}".trimEnd()
                    if (n == 1) base else "$base ($n)"
                }
                instances += o to name
            }
            for ((notes, name) in patterns) {
                line(3, "Pattern" + attr("Name", name))
                for (n in notes) line(4, n)
            }
            for ((o, name) in instances) line(3, "PatternInstance" + attr("Time", o) + attr("Pattern", name))
        }
        return out.toString()
    }

    /**
     * Why the song's FamiStudio text would not play as the song does, or null: an EPSM sound the text holds whose SSG
     * hardware envelope takes a manual period other than [EPSM_TEXT_PERIOD] (FamiStudio 4.5.3 writes no
     * `EPSMSquareEnvelopePitch`, so its import reads 4000).
     */
    internal fun manualPeriodProblem(fs: FsSong): String? {
        for (slot in instrumentNames(fs).keys) {
            val ins = fs.instruments[slot - 1]
            if (ins.chip != NesChip.EPSM || ins.epsmEnvelope.autoPitch || ins.epsmEnvelope.period == EPSM_TEXT_PERIOD) continue
            return "Sound ${NesRules.hex2(slot)} ${ins.name} uses a manual SSG envelope period of ${ins.epsmEnvelope.period}, " +
                "which FamiStudio 4.5.3's text format cannot store (its import reads $EPSM_TEXT_PERIOD). Set the period to " +
                "$EPSM_TEXT_PERIOD or use auto period for the text export, or export .nsf or WAV, which keep it"
        }
        return null
    }

    /** FamiStudio instrument names by slot ("slot name"), for every slot the song uses or that was edited. */
    private fun instrumentNames(fs: FsSong): Map<Int, String> {
        val used = HashSet<Int>()
        for (ch in fs.notes) for (n in ch) if (n != null && n.isMusical && n.instrument != 0) used += n.instrument
        val untouched = NesSound.blank().instruments
        val names = LinkedHashMap<Int, String>()
        for ((i, ins) in fs.instruments.withIndex()) {
            // Untouched slots (NesSound.blank's) stay out of the FamiStudio project.
            if (i + 1 !in used && ins == untouched[i]) continue
            names[i + 1] = "${NumberText.hex(i + 1, 2)} ${ins.name}".trimEnd()
        }
        return names
    }

    /**
     * FamiStudio sample names (unique, as its import looks samples up by name) by sample data: one sample per distinct
     * data that [slots]' mappings reference, those the song plays first in [NesDpcm]'s placement order.
     */
    private fun sampleNames(fs: FsSong, slots: Set<Int>): Map<List<Int>, String> {
        val layout = NesDpcm.layout(fs)
        val referenced = slots.flatMap { fs.instruments[it - 1].dpcm.values.map { m -> m.sample } }.filter { it in fs.samples.indices }.sorted()
        val names = LinkedHashMap<List<Int>, String>()
        val taken = HashSet<String>()
        for (s in layout.placed + referenced) {
            val data = fs.samples[s].data
            if (data in names) continue
            val base = fs.samples[s].name.ifBlank { "Sample" }
            var name = base
            var n = 2
            while (!taken.add(name)) name = "$base ${n++}"
            names[data] = name
        }
        return names
    }

    /**
     * The first order of each distinct FamiStudio pattern the export gives channel [c] (FamiStudio's exporter counts
     * effects per pattern, not per order, e.g. to pick its speed channel).
     */
    internal fun distinctPatternOrders(fs: FsSong, c: Int): List<Int> {
        val names = instrumentNames(fs)
        val seen = HashSet<List<String>>()
        return (0 until fs.orders).filter { o -> orderNotes(fs, c, o, names).let { it.isNotEmpty() && seen.add(it) } }
    }

    /** The FamiStudio `Note` lines of channel [c] in order [o]. */
    private fun orderNotes(fs: FsSong, c: Int, o: Int, names: Map<Int, String>): List<String> {
        val lines = ArrayList<String>()
        for (abs in fs.orderStart[o] until fs.orderStart[o + 1]) {
            val n = fs.notes[c][abs]
            val release = releaseAfter(fs, c, abs)
            if ((n == null || n.isEmpty) && release < 0) continue
            val s = StringBuilder("Note").append(attr("Time", abs - fs.orderStart[o]))
            if (n != null) {
                if (n.isMusical) {
                    s.append(attr("Value", noteName(n.value)))
                    s.append(attr("Duration", duration(fs, c, abs)))
                    if (release >= 0) s.append(attr("Release", release))
                    if (n.instrument != 0) s.append(attr("Instrument", names.getValue(n.instrument)))
                    if (n.arpeggio >= 0) s.append(attr("Arpeggio", arpName(n.arpeggio)))
                    if (n.slideTarget != 0) s.append(attr("SlideTarget", noteName(n.slideTarget)))
                    if (!n.attack) s.append(attr("Attack", "False"))
                } else if (n.value == FsNote.STOP) {
                    s.append(attr("Value", "Stop"))
                }
                if (n.hasVolume) s.append(attr("Volume", n.volume))
                if (n.volumeSlideTarget >= 0) s.append(attr("VolumeSlideTarget", n.volumeSlideTarget))
                if (n.vibrato >= 0) s.append(attr("VibratoSpeed", n.vibrato shr 4)).append(attr("VibratoDepth", n.vibrato and 0xF))
                if (n.speed >= 0) s.append(attr("Speed", n.speed))
                if (n.finePitch != FsNote.NO_FINE) s.append(attr("FinePitch", n.finePitch))
                if (n.duty >= 0) s.append(attr("DutyCycle", n.duty))
                if (n.noteDelay >= 0) s.append(attr("NoteDelay", n.noteDelay))
                if (n.cutDelay >= 0) s.append(attr("CutDelay", n.cutDelay))
                if (n.deltaCounter >= 0) s.append(attr("DeltaCounter", n.deltaCounter))
                if (n.envelopePeriod >= 0) s.append(attr("EnvelopePeriod", n.envelopePeriod))
            }
            lines += s.toString()
        }
        return lines
    }

    /**
     * The release offset (rows) of the musical note at [abs] when a later OFF on channel [c] releases it, else -1.
     * FamiStudio keeps a release as an attribute of the note it releases.
     */
    private fun releaseAfter(fs: FsSong, c: Int, abs: Int): Int {
        val n = fs.notes[c][abs] ?: return -1
        if (!n.isMusical) return -1
        for (r in abs + 1 until fs.totalRows) {
            val m = fs.notes[c][r] ?: continue
            if (m.value == FsNote.RELEASE) return r - abs
            if (m.isMusical || m.value == FsNote.STOP) return -1
        }
        return -1
    }

    /** Rows until the channel's next note or stop (FamiStudio would otherwise insert a stop where the note ends). */
    private fun duration(fs: FsSong, c: Int, abs: Int): Int {
        for (r in abs + 1 until fs.totalRows) {
            val m = fs.notes[c][r] ?: continue
            if (m.isMusical || m.value == FsNote.STOP) return r - abs
        }
        return fs.totalRows - abs
    }

    private fun envelope(line: (Int, String) -> Unit, type: String, e: NesEnvelope, neutral: Int, relative: Boolean) {
        if (e.values.isEmpty() || e.values.all { it == neutral }) return
        line(2, "Envelope" + attr("Type", type) + attr("Length", e.values.size) +
            (if (e.loop >= 0) attr("Loop", e.loop) else "") + (if (e.release >= 0) attr("Release", e.release) else "") +
            (if (relative) attr("Relative", "True") else "") + attr("Values", e.values.joinToString(",")))
    }

    private fun arpName(param: Int) = "Arp 0${NumberText.hex(param, 2)}"

    /** FamiStudio's note name: note 1 = "C0". */
    private fun noteName(n: Int) = NOTE_NAMES[(n - 1) % 12] + ((n - 1) / 12)

    /** ` Key="value"` with FamiStudio's quoting (doubled quotes; control characters become spaces). */
    private fun attr(key: String, value: Any): String {
        val v = value.toString().map { if (it < ' ') ' ' else it }.joinToString("").replace("\"", "\"\"")
        return " $key=\"$v\""
    }
}
