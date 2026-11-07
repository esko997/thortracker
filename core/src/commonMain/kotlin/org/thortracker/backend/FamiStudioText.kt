package org.thortracker.backend

import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.nes.NesEnvelope

/**
 * FamiStudio 4.5 text export (`FamistudioTextFile`): a FamiTracker-tempo project FamiStudio 4.5.3 opens (File →
 * Import, or `FamiStudio song.txt <command>` on its command line), from which it exports ca65/asm6/NESASM data for
 * games. It describes the same [FsSong] the preview lowers: instruments (named "slot name"), one arpeggio per `0xy`
 * used, and per channel one FamiStudio pattern per distinct order content (FamiStudio keeps note durations and
 * releases inside patterns, so an order whose notes end or release differently gets its own copy).
 */
object FamiStudioText {
    private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val CHANNEL_TYPES = listOf("Square1", "Square2", "Triangle", "Noise")

    fun export(song: Song): ByteArray = text(song).encodeToByteArray()

    /** @throws IllegalArgumentException for a song with errors. */
    fun text(song: Song): String {
        val errors = org.thortracker.system.nes.NesSystem.errors(song)
        require(errors.isEmpty()) { errors.first() }
        return text(FsSong.of(song))
    }

    internal fun text(fs: FsSong): String {
        val out = StringBuilder()
        fun line(depth: Int, s: String) {
            repeat(depth) { out.append('\t') }
            out.append(s).append('\n')
        }
        line(0, "Project" + attr("Version", "4.5.3") + attr("TempoMode", "FamiTracker") +
            attr("Name", fs.title.ifBlank { "Untitled" }) + (if (fs.artist.isNotBlank()) attr("Author", fs.artist) else ""))
        val used = HashSet<Int>()
        for (ch in fs.notes) for (n in ch) if (n != null && n.isMusical && n.instrument != 0) used += n.instrument
        val names = HashMap<Int, String>()
        for ((i, ins) in fs.instruments.withIndex()) {
            val slot = i + 1
            if (slot !in used && ins.copy(name = NesInstrument.DEFAULT.name) == NesInstrument.DEFAULT) continue
            val name = "${NumberText.hex(slot, 2)} ${ins.name}".trimEnd()
            names[slot] = name
            line(1, "Instrument" + attr("Name", name))
            envelope(::line, "Volume", ins.volume, 15, false)
            envelope(::line, "Arpeggio", ins.arpeggio, 0, false)
            envelope(::line, "Pitch", ins.pitch, 0, ins.pitchRelative)
            envelope(::line, "DutyCycle", ins.duty, 0, false)
        }
        val arpeggios = sortedSetOf<Int>()
        for (ch in fs.notes) for (n in ch) if (n != null && n.isMusical && n.arpeggio >= 0) arpeggios += n.arpeggio
        for (a in arpeggios) {
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
        for (c in CHANNEL_TYPES.indices) {
            line(2, "Channel" + attr("Type", CHANNEL_TYPES[c]))
            val patterns = LinkedHashMap<List<String>, String>()
            val instances = ArrayList<Pair<Int, String>>()
            val variants = HashMap<Int, Int>()
            for (o in 0 until fs.orders) {
                val notes = orderNotes(fs, c, o, names)
                if (notes.isEmpty()) continue
                val name = patterns.getOrPut(notes) {
                    val p = fs.patternOfOrder[o]
                    val n = variants.merge(p, 1, Int::plus)!!
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
