package org.thortracker.system.pokey

import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.panel.EnvelopeContour

/** Musical choices own only the fields named here; advanced programs are never approximated. */
internal object PokeySoundMacros {
    val toneLabels = arrayOf("Voice", "Grain", "Clock", "Contour", "Motion", "Partner")
    val sampleLabels = arrayOf("Voice", "Recording", "Playback", "Root", "Repeat", "Import")
    private val grains = intArrayOf(5, 6, 1, 2, 4, 0, 8)
    private val grainLabels = arrayOf("Clear", "Buzz", "Gated", "Rattle", "Air", "Storm", "DC level")
    private val motions = intArrayOf(1, 2, 8, 32)
    private val motionLabels = arrayOf("Slow phase", "Phase", "Hollow", "Thin", "Difference saw")
    private val contours = arrayOf("Held", "Pluck", "Swell")
    fun sample(i: PokeyInstrument) = i.kind == PokeyKind.SAMPLE
    fun label(i: PokeyInstrument, n: Int) = (if (sample(i)) sampleLabels else toneLabels)[n]
    fun maximum(s: Song, i: PokeyInstrument, n: Int): Int = when (n) {
        0 -> 4
        1 -> if (sample(i)) (s.pokey.samples.size - 1).coerceAtLeast(0) else grains.lastIndex
        2 -> if (sample(i)) PokeyInstrument.RATES.last - PokeyInstrument.RATES.first else 1
        3 -> if (sample(i)) PokeyRules.NOTES.last else 2
        4 -> if (sample(i)) (s.pokey.samples.getOrNull(i.sample)?.levels?.size ?: 0) else 4
        else -> if (sample(i)) 0 else 15
    }
    fun enabled(s: Song, i: PokeyInstrument, n: Int, lane: Int): Boolean = when {
        n == 0 -> true
        sample(i) -> when (n) { 1 -> s.pokey.samples.isNotEmpty(); 4 -> s.pokey.samples.getOrNull(i.sample)?.levels?.isNotEmpty() == true; else -> true }
        n == 1 -> i.distortion.values.size <= 1 && i.distortion.loop == -1 && i.distortion.release == -1
        n == 2 -> lane in i.kind.fastLanes
        n == 4 -> i.kind.filtered
        n == 5 -> i.kind.filtered || i.kind.joined
        else -> true
    }
    fun value(s: Song, i: PokeyInstrument, n: Int): Int? = when {
        n == 0 -> i.kind.ordinal
        sample(i) -> when (n) { 1 -> i.sample.takeIf { it in s.pokey.samples.indices }; 2 -> i.rate - PokeyInstrument.RATES.first; 3 -> i.root; 4 -> i.loop + 1; else -> 0 }
        else -> when (n) {
            1 -> if (i.distortion.values.size <= 1 && i.distortion.loop == -1 && i.distortion.release == -1) {
                val d = i.distortion.values.firstOrNull() ?: PokeyDistortion.PURE
                grains.indexOf(if (d == 7) 5 else if (d == 3) 1 else d).takeIf { it >= 0 }
            } else null
            2 -> if (i.fast) 1 else 0
            3 -> if (i.volume.values.size == 1 && i.volume.loop == -1 && i.volume.release == -1) 0 else EnvelopeContour.read(i.volume)?.shape?.ordinal
            4 -> if (i.difference) (if (i.filterOffset == 1) 4 else null) else motions.indexOf(i.filterOffset).takeIf { it >= 0 }
            else -> i.partnerVolume
        }
    }
    fun text(s: Song, i: PokeyInstrument, n: Int): String {
        val v = value(s, i, n) ?: return "Custom"
        return when {
            n == 0 -> i.kind.label
            sample(i) -> when (n) { 1 -> s.pokey.samples.getOrNull(v)?.name ?: "No samples"; 2 -> "${i.rate} levels/s"; 3 -> PokeySystem.noteName(i.root); 4 -> if (i.loop < 0) "Once" else "From level ${i.loop}"; else -> "Kit / WAV…" }
            else -> when (n) { 1 -> grainLabels[v]; 2 -> if (i.fast) "Fast clock" else "Song clock"; 3 -> contours[v]; 4 -> motionLabels[v]; else -> if (v == 0) "Silent" else "$v / 15" }
        }
    }
    fun hint(s: Song, i: PokeyInstrument, n: Int, lane: Int): String = when {
        n == 0 -> "Pairing takes channels; Samples uses the volume DAC."
        sample(i) -> when (n) { 1 -> "Uses a song recording; no sample data is rewritten."; 2 -> "Levels per second at Root; notes change playback rate."; 3 -> "The note that plays at the recording rate."; 4 -> "Once or exact loop-start level; WAV data stays intact."; else -> "Open the native sample importer and starter kit." }
        n == 1 -> if (!enabled(s, i, n, lane)) "Custom distortion program: edit it in Advanced." else "Noise notes choose a rate; DC has no pitch. Song owns noise length."
        n == 2 -> if (!enabled(s, i, n, lane)) "Fast clock is unavailable on this listening channel." else "Fast clock changes pitch range, not the song's base clock."
        n == 3 -> "Choosing a contour replaces only the volume sequence."
        n == 4 -> if (!i.kind.filtered) "Choose a high-pass Voice to shape its paired clock." else "Changes paired counter offset; saw uses the difference frequency."
        else -> if (!i.kind.joined && !i.kind.filtered) "A paired Voice can mix in its taken channels." else "Level of taken channels; their distortion remains unchanged."
    }
    fun apply(s: Song, i: PokeyInstrument, n: Int, requested: Int, lane: Int): PokeyInstrument {
        if (!enabled(s, i, n, lane)) return i
        val v = requested.coerceIn(0, maximum(s, i, n))
        if (value(s, i, n) == v) return i
        if (n == 0) {
            val kind = PokeyKind.entries[v]
            if (kind == PokeyKind.SAMPLE && s.pokey.samples.isEmpty()) return i
            val sampleIndex = if (kind == PokeyKind.SAMPLE && i.sample !in s.pokey.samples.indices) 0 else i.sample
            val loop = if (kind == PokeyKind.SAMPLE && i.loop >= s.pokey.samples[sampleIndex].levels.size) -1 else i.loop
            return i.copy(kind = kind, sample = sampleIndex, loop = loop,
                filterOffset = if (kind.filtered && i.difference) i.filterOffset.coerceIn(1, 127) else i.filterOffset)
        }
        if (sample(i)) return when (n) {
            1 -> i.copy(sample = v, loop = if (i.loop >= s.pokey.samples[v].levels.size) -1 else i.loop)
            2 -> i.copy(rate = v + PokeyInstrument.RATES.first)
            3 -> i.copy(root = v)
            4 -> i.copy(loop = v - 1)
            else -> i
        }
        return when (n) {
            1 -> i.copy(distortion = NesEnvelope(listOf(grains[v])))
            2 -> i.copy(fast = v == 1)
            3 -> i.copy(volume = EnvelopeContour.choose(i.volume, EnvelopeContour.Shape.entries[v]))
            4 -> i.copy(difference = v == 4, filterOffset = if (v == 4) 1 else motions[v])
            else -> i.copy(partnerVolume = v)
        }
    }
}
