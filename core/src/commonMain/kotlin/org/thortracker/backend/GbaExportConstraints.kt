package org.thortracker.backend

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import org.thortracker.model.Cell
import org.thortracker.system.gba.GbaEnvelope
import org.thortracker.system.gba.GbaSample
import org.thortracker.system.gba.GbaSound

/** XM pitch encoding and the integer middle-C frequency used by mmutil. */
data class GbaSamplePitch(val relativeNote: Int, val fineTune: Int, val frequency: Int, val centsError: Double)

/** Limits of the pinned GBA mmutil/Maxmod v0x18 path, not theoretical GBA hardware limits. */
object GbaExportConstraints {
    /**
     * Source frames/second apply at rootNote, not necessarily C4. XM's 1/128-semitone grid is rounded
     * once here for both exports. centsError includes Maxmod's integer frequency/4 quantization.
     * No PCM resampling or root-note reinterpretation occurs.
     */
    fun pitch(sample: GbaSample): GbaSamplePitch {
        require(sample.sampleRate in 1..48000) { "Sample rate must be 1..48000 Hz" }
        require(sample.rootNote in 0..95 && sample.fineTune in -128..127) { "Invalid sample root or finetune" }
        val semitones = 12.0 * ln(sample.sampleRate / 8363.0) / ln(2.0) + 48 - sample.rootNote + sample.fineTune / 128.0
        val units = (semitones * 128).roundToInt()
        val relative = ((units + 64) / 128.0).let { kotlin.math.floor(it).toInt() }
        val fine = units - relative * 128
        require(relative in -128..127 && fine in -128..127) { "Sample pitch is outside XM relative-note range" }
        val frequency = (8363.0 * 2.0.pow(relative / 12.0 + fine / 1536.0)).toInt()
        require(frequency in 4..262143) { "Sample pitch is outside Maxmod's 16-bit frequency/4 range; resample or change root" }
        val desired = 8363.0 * 2.0.pow(semitones / 12.0)
        return GbaSamplePitch(relative, fine, frequency, 1200.0 * ln((frequency / 4 * 4) / desired) / ln(2.0))
    }

    fun effectError(effect: Int, param: Int): String? = when {
        effect !in 0..15 -> "Only XM effects 0..F are supported"
        param !in 0..255 -> "Effect parameter must be a byte"
        effect in 1..2 && param >= 0xE0 -> "Maxmod cannot preserve slides 1E0..1FF or 2E0..2FF"
        effect in listOf(5, 6, 10) && param and 15 != 0 && param and 240 != 0 -> "Volume slide must use one direction only"
        effect == 12 && param > 64 -> "XM Cxx volume must be 00..40"
        effect == 13 && param and 15 > 9 -> "XM Dxx row is binary-coded decimal"
        effect == 14 && param shr 4 !in listOf(1, 2, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14) -> "This XM E subcommand is unsupported by Maxmod"
        effect == 14 && param shr 4 in listOf(4, 7) && param and 15 > 7 -> "XM waveform must be 0..7"
        effect == 15 && param == 0 -> "F00 is not a valid Maxmod speed"
        else -> null
    }

    /**
     * mmutil's XM offset sanitizer indexes its flattened samples by instrument-1, not keymap.
     * Accept only cells where that check has the same endpoint as the actual selected sample.
     * Must be called after structural sound validation; returns null for effects other than 9.
     */
    fun sampleOffsetError(sound: GbaSound, cell: Cell): String? {
        if (cell.effect != 9) return null
        if (cell.instrument !in 1..sound.instruments.size || cell.note !in 0..95)
            return "Sample offset needs an explicit note and instrument"
        val inst = sound.instruments[cell.instrument - 1]
        val selected = inst.keymap.getOrNull(cell.note) ?: return "Invalid sample-offset keymap"
        fun end(index: Int): Int? = if (index == -1) 4 else sound.samples.getOrNull(index)?.let {
            if (it.loopEnd > it.loopStart) it.loopEnd else it.pcm.size
        }
        var remaining = cell.instrument - 1
        var reference: Int? = null
        for (instrument in sound.instruments) {
            val local = instrument.keymap.filter { it >= 0 }.distinct().toMutableList()
            if (local.isNotEmpty() && -1 in instrument.keymap) local += -1
            if (remaining < local.size) { reference = end(local[remaining]); break }
            remaining -= local.size
        }
        val endpoint = end(selected)
        if (reference == null || endpoint == null || reference != endpoint)
            return "Pinned mmutil sample-offset check cannot preserve this multisample mapping"
        if (cell.param * 256 >= endpoint) return "Sample offset is beyond the sample or loop end"
        return null
    }

    /** Set [checkPcm] false only for metadata edits whose unchanged PCM was already validated. */
    fun errors(sound: GbaSound, checkPcm: Boolean = true): List<String> = buildList {
        if (sound.instruments.size != 64) add("GBA needs exactly 64 instrument slots")
        if (sound.samples.size > 128) add("GBA supports at most 128 global samples")
        if (sound.bpm !in 32..255) add("GBA BPM must be 32..255")
        if (sound.samples.sumOf { it.pcm.size.toLong() } > 512 * 1024) add("Embedded PCM exceeds the 512 KiB backend limit")
        sound.samples.forEachIndexed { index, s ->
            val prefix = "Sample ${index + 1}: "
            if (checkPcm && s.pcm.any { it !in -128..127 }) add(prefix + "PCM must be signed 8-bit")
            if (s.volume !in 0..64 || s.pan !in 0..255) add(prefix + "invalid volume or pan")
            if (!(s.loopStart == 0 && s.loopEnd == 0) && !(s.loopStart >= 0 && s.loopEnd > s.loopStart && s.loopEnd <= s.pcm.size)) add(prefix + "invalid forward-loop bounds")
            try { pitch(s) } catch (e: IllegalArgumentException) { add(prefix + e.message) }
        }
        var localSamples = 0
        sound.instruments.forEachIndexed { index, inst ->
            val prefix = "Instrument ${index + 1}: "
            if (inst.keymap.size != 96 || inst.keymap.any { it < -1 || it >= sound.samples.size }) add(prefix + "keymap must have 96 valid sample indices or -1")
            val mapped = inst.keymap.filter { it >= 0 }.distinct()
            if (mapped.isNotEmpty()) {
                val count = mapped.size + if (-1 in inst.keymap) 1 else 0
                localSamples += count
                if (count > 16) add(prefix + "XM allows 16 local samples including unmapped silence")
                if (mapped.any { sound.samples.getOrNull(it)?.pcm?.isEmpty() == true }) add(prefix + "mapped sample is empty")
                if (inst.keymap.size == 96 && (84..95).any { inst.keymap[it] != inst.keymap[0] }) add(prefix + "pinned mmutil requires C7..B7 to use the C0 sample; change the high-octave keymap")
            }
            if (inst.fadeout !in 0..8191) add(prefix + "fadeout must be 0..8191 (Maxmod's 8-bit fadeout/32)")
            envelopeError(inst.volumeEnvelope)?.let { add(prefix + "volume envelope: " + it) }
            envelopeError(inst.panEnvelope)?.let { add(prefix + "pan envelope: " + it) }
        }
        if (localSamples > 255) add("XM instrument-local samples total $localSamples; pinned mmutil allows at most 255 (shared samples count once per instrument)")
    }

    private fun envelopeError(e: GbaEnvelope): String? = when {
        e.points.size > 12 -> "at most 12 points"
        e.enabled && (e.points.size < 2 || e.points.first().tick != 0) -> "enabled envelopes need at least two points, starting at tick 0"
        e.points.any { it.tick !in 0..255 || it.value !in 0..64 } -> "ticks must be 0..255 and values 0..64"
        e.points.zipWithNext().any { (a, b) -> a.tick >= b.tick } -> "ticks must strictly increase"
        e.sustain != -1 && e.sustain !in e.points.indices -> "invalid sustain point"
        !(e.loopStart == -1 && e.loopEnd == -1) && !(e.loopStart in e.points.indices && e.loopEnd in e.points.indices && e.loopStart < e.loopEnd) -> "invalid loop points"
        else -> null
    }

    /** Quantization is reported, never hidden by rewriting the project. */
    fun warnings(sound: GbaSound): List<String> = buildList {
        sound.samples.forEachIndexed { i, sample ->
            val pitch = try { pitch(sample) } catch (_: IllegalArgumentException) { null }
            if (pitch != null && abs(pitch.centsError) > 0.00001) add("Sample ${i + 1}: XM/Maxmod pitch quantization ${pitch.centsError} cents")
            if (sample.pan and 1 != 0) add("Sample ${i + 1}: Maxmod rounds panning down to an even value")
        }
        sound.instruments.forEachIndexed { i, inst ->
            if (inst.fadeout % 32 != 0) add("Instrument ${i + 1}: Maxmod rounds fadeout down to a multiple of 32")
        }
    }
}
