package org.thortracker.backend

import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.nes.DpcmMapping
import org.thortracker.system.nes.DpcmSample
import org.thortracker.system.nes.FdsPresets
import org.thortracker.system.nes.FdsSound
import org.thortracker.system.nes.N163Wave
import org.thortracker.system.nes.NesChip
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesExpansion
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesSound
import org.thortracker.system.nes.WavePreset
import org.thortracker.system.nes.Vrc7Presets

/**
 * FamiStudio 4.5 text export (`FamistudioTextFile`): a FamiTracker-tempo project FamiStudio 4.5.3 opens (File →
 * Import, or `FamiStudio song.txt <command>` on its command line), from which it exports ca65/asm6/NESASM data for
 * games. It describes the same [FsSong] the preview lowers: DPCM samples (one per distinct data, the ones the song
 * plays first and in [NesDpcm]'s order, so FamiStudio packs them at the same addresses), instruments (named "slot
 * name", with their DPCM mappings), one arpeggio per `0xy` used, and per channel one FamiStudio pattern per distinct
 * order content (FamiStudio keeps note durations and releases inside patterns, so an order whose notes end or release
 * differently gets its own copy). A song with an expansion chip names it (`Expansions="VRC6"`; the N163 also
 * `NumN163Channels`; the MMC5's pulses play 2A03 instruments, which carry no expansion), its instruments of that chip
 * carry `Expansion="VRC6"` and the saw master volume
 * (`Expansion="VRC7"`: the patch, the custom patch's registers and the release/stop overrides; `Expansion="N163"`: the
 * wave size, position and count with the `N163Wave` and `Repeat` envelopes; `Expansion="FDS"`: the wave count, the wave
 * and modulation presets, master volume and modulation settings with the `FDSWave`, `FDSMod` and `Repeat` envelopes;
 * `Expansion="S5B"`: the envelope shape, auto-pitch, octave and period, and the `MixerSettings` and `NoiseFreq`
 * envelopes), its channels follow the 2A03's, and a 5B envelope period is the `EnvelopePeriod` of 5B Square 1's note.
 * Notes are FamiStudio's engine notes ([NesRules.engineNote]: true pitch on every channel, so on the VRC7 and the 5B an
 * octave and on the FDS two octaves above Thor Tracker's names), so FamiStudio plays the same bytes. An FDS sound's
 * `FdsModSpeed` is its exact speed: a manual speed with a low byte of $80-$FF plays as itself in FamiStudio's editor,
 * while its 4.5.3 assembly/NSF export misencodes it as auto-mod (Thor Tracker's player tags it, see [FamiStudioLowering]).
 */
object FamiStudioText {
    private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** FamiStudio's channel type names (`ChannelType.InternalNames`), in engine order: the 2A03's, then the expansion's. */
    private val APU_TYPES = listOf("Square1", "Square2", "Triangle", "Noise", "DPCM")

    private fun channelType(expansion: NesExpansion, c: Int): String = when {
        c < APU_TYPES.size -> APU_TYPES[c]
        expansion == NesExpansion.VRC6 -> listOf("VRC6Square1", "VRC6Square2", "VRC6Saw")[c - APU_TYPES.size]
        expansion.chip == NesChip.N163 -> "N163Wave${c - APU_TYPES.size + 1}"
        expansion == NesExpansion.FDS -> "FDS"
        expansion == NesExpansion.MMC5 -> "MMC5Square${c - APU_TYPES.size + 1}"
        expansion == NesExpansion.S5B -> "S5BSquare${c - APU_TYPES.size + 1}"
        else -> "VRC7FM${c - APU_TYPES.size + 1}"
    }

    /** FamiStudio's expansion names (`ExpansionType.InternalNames`). */
    private fun expansionName(expansion: NesExpansion): String = when {
        expansion == NesExpansion.MMC5 -> "MMC5"
        else -> when (expansion.chip) {
            NesChip.APU -> ""
            NesChip.VRC6 -> "VRC6"
            NesChip.VRC7 -> "VRC7"
            NesChip.N163 -> "N163"
            NesChip.FDS -> "FDS"
            NesChip.S5B -> "S5B"
        }
    }

    fun export(song: Song): ByteArray = text(song).encodeToByteArray()

    /**
     * @throws IllegalArgumentException for a song with errors, or an MMC5 song whose PCM lane plays (FamiStudio has no
     * MMC5 PCM channel to hold it; [NesPcmLowering.exportProblem]). A silent PCM lane's samples and mappings stay in the
     * project, and its `Fxx` is in the song's speeds ([FsSong]).
     */
    fun text(song: Song): String {
        val errors = org.thortracker.system.nes.NesSystem.errors(song)
        require(errors.isEmpty()) { errors.first() }
        NesPcmLowering.exportProblem(song, "FamiStudio text")?.let { throw IllegalArgumentException(it) }
        return text(FsSong.of(song))
    }

    internal fun text(fs: FsSong): String {
        val out = StringBuilder()
        fun line(depth: Int, s: String) {
            repeat(depth) { out.append('\t') }
            out.append(s).append('\n')
        }
        line(0, "Project" + attr("Version", "4.5.3") + attr("TempoMode", "FamiTracker") +
            attr("Name", fs.title.ifBlank { "Untitled" }) + (if (fs.artist.isNotBlank()) attr("Author", fs.artist) else "") +
            (if (fs.expansion != NesExpansion.NONE) attr("Expansions", expansionName(fs.expansion)) else "") +
            (if (fs.expansion.chip == NesChip.N163) attr("NumN163Channels", fs.expansion.n163Channels) else ""))
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
            line(1, "Instrument" + attr("Name", name) + chipAttributes(fs, ins))
            for ((note, m) in ins.dpcm.entries.sortedBy { it.key }.map { it.key to it.value }) {
                val sample = fs.samples.getOrNull(m.sample) ?: continue
                val shared = samples.getValue(sample.data)
                line(2, "DPCMMapping" + attr("Note", noteName(note - NesRules.NOTE_OFFSET)) + attr("Sample", shared) +
                    attr("Pitch", m.pitch) + attr("Loop", if (m.loop) "True" else "False") + attr("Bank", 0) +
                    (dmcInitialOverride(fs, sample, m)?.let { attr("DmcInitialValue", it) } ?: ""))
            }
            for (e in envelopeLines(ins)) line(2, e)
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

    /** FamiStudio instrument names by slot ("slot name"), for every slot the song uses or that was edited. */
    private fun instrumentNames(fs: FsSong): Map<Int, String> {
        val used = HashSet<Int>()
        for (ch in fs.notes) for (n in ch) if (n != null && n.isMusical && n.instrument != 0) used += n.instrument
        val untouched = NesSound.blank().instruments
        val names = LinkedHashMap<Int, String>()
        for ((i, ins) in fs.instruments.withIndex()) {
            // Untouched slots (NesSound.blank's) stay out of the FamiStudio project; MMC5 PCM mappings, which FamiStudio
            // cannot hold (an unplayed PCM lane's data stays in the Thor Tracker project), do not count as an edit.
            if (i + 1 !in used && ins.copy(pcm = emptyMap()) == untouched[i]) continue
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
                if (n.fdsModSpeed >= 0) s.append(attr("FdsModSpeed", n.fdsModSpeed))
                if (n.fdsModDepth >= 0) s.append(attr("FdsModDepth", n.fdsModDepth))
                if (n.duty >= 0) s.append(attr("DutyCycle", n.duty))
                if (n.noteDelay >= 0) s.append(attr("NoteDelay", n.noteDelay))
                if (n.cutDelay >= 0) s.append(attr("CutDelay", n.cutDelay))
                if (n.deltaCounter >= 0) s.append(attr("DeltaCounter", n.deltaCounter))
                if (n.phaseReset) s.append(attr("PhaseReset", 1))
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

    /**
     * An instrument's attributes after its name: its expansion's (`FamistudioTextFile`). VRC7: `Vrc7Patch`, the
     * registers only for Custom (0), then both overrides (the song's setting, the same on every VRC7 instrument:
     * FamiStudio's engine keeps only the last loaded one's). N163: preset, size, position, count (the waves themselves
     * in the N163Wave envelope). FDS: count, presets, then the settings that are not FamiStudio's defaults (the mod speed
     * exactly as set). 5B: `S5BEnvelopeShape` 0 (off) or 1-8 = `$08-$0F`, then auto-pitch, its octave, the period.
     */
    private fun chipAttributes(fs: FsSong, ins: NesInstrument): String = when (ins.chip) {
        NesChip.APU -> ""
        NesChip.VRC6 -> attr("Expansion", "VRC6") + attr("Vrc6SawMasterVolume", ins.sawVolume.label)
        NesChip.VRC7 -> attr("Expansion", "VRC7") + attr("Vrc7Patch", ins.fm.preset) +
            (if (ins.fm.preset == Vrc7Presets.CUSTOM) ins.fm.regs.withIndex().joinToString("") { (i, r) -> attr("Vrc7Reg$i", r) } else "") +
            attr("Vrc7OverrideRelease", if (fs.vrc7Sustain.release) "True" else "False") +
            attr("Vrc7OverrideStop", if (fs.vrc7Sustain.stop) "True" else "False")
        NesChip.N163 -> attr("Expansion", "N163") + attr("N163WavePreset", "Custom") + attr("N163WaveSize", ins.wave.size) +
            attr("N163WavePos", ins.wave.position) + attr("N163WaveCount", ins.wave.waves.size)
        NesChip.FDS -> ins.fds.let { f ->
            attr("Expansion", "FDS") + attr("FdsWaveCount", f.waves.size) +
                attr("FdsWavePreset", presetName(FdsPresets.presetOf(f.waves))) +
                attr("FdsModPreset", presetName(FdsPresets.modPresetOf(f.modulation))) +
                (if (f.masterVolume.register != 0) attr("FdsMasterVolume", f.masterVolume.register) else "") +
                (if (f.modSpeed != 0) attr("FdsModSpeed", f.modSpeed) else "") +
                (if (f.modDepth != 0) attr("FdsModDepth", f.modDepth) else "") +
                (if (f.modDelay != 0) attr("FdsModDelay", f.modDelay) else "") +
                (if (f.autoMod) attr("FdsAutoMod", "True") + attr("FdsAutoModDenom", f.autoModDenom) +
                    attr("FdsAutoModNumer", f.autoModNumer) else "")
        }
        NesChip.S5B -> attr("Expansion", "S5B") + attr("S5BEnvelopeShape", ins.s5b.shape.famiStudio) +
            attr("S5BEnvelopeAutoPitch", if (ins.s5b.autoPitch) "True" else "False") +
            attr("S5BEnvelopeAutoPitchOctave", ins.s5b.octave) + attr("S5BEnvelopePitch", ins.s5b.period)
    }

    /**
     * An instrument's envelope lines; empty and all-neutral envelopes are left out (FamiStudio's defaults: a 5B mixer
     * is neutral at tone only).
     */
    private fun envelopeLines(ins: NesInstrument): List<String> = listOfNotNull(
        envelope("Volume", ins.volume, 15, false),
        envelope("Arpeggio", ins.arpeggio, 0, false),
        envelope("Pitch", ins.pitch, 0, ins.pitchRelative),
        envelope("DutyCycle", ins.duty, 0, false),
    ) + when (ins.chip) {
        NesChip.N163 -> n163Envelopes(ins.wave)
        NesChip.FDS -> fdsEnvelopes(ins.fds)
        NesChip.S5B -> listOfNotNull(
            envelope("MixerSettings", ins.mixer, FamiStudioEnvelopes.MIXER_TONE, false),
            envelope("NoiseFreq", ins.noise, 0, false),
        )
        else -> emptyList()
    }

    /**
     * What FamiStudio's export compares when it merges identical instruments (`Project.MergeIdenticalInstruments`: a
     * CRC of the imported instrument without its name and colour; DPCM mappings count with their note, pitch, loop and
     * initial value override but not their sample, whose ID the CRC skips): sounds of equal keys are one instrument in
     * FamiStudio's export.
     */
    internal fun mergeKey(fs: FsSong, slot: Int): List<String> = fs.instruments[slot - 1].let { ins ->
        val mappings = ins.dpcm.entries.sortedBy { it.key }.mapNotNull { (note, m) ->
            val sample = fs.samples.getOrNull(m.sample) ?: return@mapNotNull null
            "DPCMMapping" + attr("Note", note) + attr("Pitch", m.pitch) + attr("Loop", m.loop) +
                (dmcInitialOverride(fs, sample, m)?.let { attr("DmcInitialValue", it) } ?: "")
        }
        listOf(chipAttributes(fs, ins)) + mappings + envelopeLines(ins)
    }

    /** A mapping's DMC initial value when it overrides its sample's (FamiStudio's `DmcInitialValue`), else null. */
    private fun dmcInitialOverride(fs: FsSong, sample: DpcmSample, m: DpcmMapping): Int? {
        val sampleDefault = fs.samples.first { it.data == sample.data }.dmcInitial
        val initial = if (m.dmcInitial >= 0) m.dmcInitial else sample.dmcInitial
        return initial.takeIf { it != sampleDefault || m.dmcInitial >= 0 }
    }

    private fun envelope(type: String, e: NesEnvelope, neutral: Int, relative: Boolean): String? {
        if (e.values.isEmpty() || e.values.all { it == neutral }) return null
        return "Envelope" + attr("Type", type) + attr("Length", e.values.size) +
            (if (e.loop >= 0) attr("Loop", e.loop) else "") + (if (e.release >= 0) attr("Release", e.release) else "") +
            (if (relative) attr("Relative", "True") else "") + attr("Values", e.values.joinToString(","))
    }

    /**
     * An N163 sound's waves as FamiStudio stores them: the `N163Wave` envelope holds every wave's samples one after the
     * other (its loop and release in samples, at wave boundaries) and `Repeat` the frames each wave plays (loop and
     * release in waves; FamiStudio derives them from the wave envelope's). Both are written even when trivial so the
     * import never falls back to its defaults (an all-0 wave is real data here).
     */
    private fun n163Envelopes(w: N163Wave): List<String> {
        val loop = if (w.loop >= 0) attr("Loop", w.loop * w.size) else ""
        val release = if (w.release >= 0) attr("Release", w.release * w.size) else ""
        return listOf(
            "Envelope" + attr("Type", "N163Wave") + attr("Length", w.size * w.waves.size) + loop + release +
                attr("Values", w.waves.flatten().joinToString(",")),
            "Envelope" + attr("Type", "Repeat") + attr("Length", w.waves.size) +
                (if (w.loop >= 0) attr("Loop", w.loop) else "") + (if (w.release >= 0) attr("Release", w.release) else "") +
                attr("Values", w.repeat.joinToString(",")),
        )
    }

    /**
     * An FDS sound's waves and modulation as FamiStudio stores them: `FDSWave` holds every wave's 64 samples one after
     * the other (loop and release in samples, at wave boundaries), `FDSMod` the 32 modulation targets, `Repeat` the frames
     * each wave plays (loop and release in waves). All are written even when trivial, as for the N163.
     */
    private fun fdsEnvelopes(f: FdsSound): List<String> {
        val size = FdsSound.WAVE_SIZE
        return listOf(
            "Envelope" + attr("Type", "FDSWave") + attr("Length", size * f.waves.size) +
                (if (f.loop >= 0) attr("Loop", f.loop * size) else "") + (if (f.release >= 0) attr("Release", f.release * size) else "") +
                attr("Values", f.waves.flatten().joinToString(",")),
            "Envelope" + attr("Type", "FDSMod") + attr("Length", f.modulation.size) + attr("Values", f.modulation.joinToString(",")),
            "Envelope" + attr("Type", "Repeat") + attr("Length", f.waves.size) +
                (if (f.loop >= 0) attr("Loop", f.loop) else "") + (if (f.release >= 0) attr("Release", f.release) else "") +
                attr("Values", f.repeat.joinToString(",")),
        )
    }

    /** FamiStudio's `WavePresetType.InternalNames` for [preset]; "Custom" for drawn waves. */
    private fun presetName(preset: WavePreset?): String = when (preset) {
        null -> "Custom"
        WavePreset.SINE -> "Sine"
        WavePreset.TRIANGLE -> "Triangle"
        WavePreset.SAWTOOTH -> "Sawtooth"
        WavePreset.SQUARE50 -> "Square50%"
        WavePreset.SQUARE25 -> "Square25%"
        WavePreset.FLAT -> "Flat"
        WavePreset.PWM -> "PWM"
        WavePreset.PWM2 -> "PWM2"
        WavePreset.PWM3 -> "PWM3"
        WavePreset.PWM4 -> "PWM4"
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
