package org.thortracker.system.pce

/** Musical recipes only own their named envelope; waveform/sample storage and song-wide LFO never change. */
internal object PceSoundMacros {
    val contours = arrayOf("Held", "Pluck", "Bell", "Bloom")
    private val volume = arrayOf(
        PceEnvelope(listOf(31)),
        PceEnvelope(listOf(31, 29, 26, 22, 17, 11, 5, 0)),
        PceEnvelope(List(32) { (31 - it).coerceAtLeast(0) }),
        PceEnvelope(listOf(0, 6, 12, 18, 24, 29, 31, 23, 15, 7, 0), release = 7),
    )
    val motions = arrayOf("Still", "Shimmer", "Rise", "Fall")
    private val pitch = arrayOf(PceEnvelope(), PceEnvelope(listOf(0, 8, 16, 8, 0, -8, -16, -8), loop = 0),
        PceEnvelope(listOf(4), loop = 0), PceEnvelope(listOf(-4), loop = 0))
    private val noiseMotions = arrayOf("Still", "Rattle", "Rise", "Fall")
    private val noisePitch = arrayOf(PceEnvelope(),
        PceEnvelope(listOf(0, 4, 0, 8), loop = 0),
        PceEnvelope(listOf(0, 2, 4, 6, 8)),
        PceEnvelope(listOf(8, 6, 4, 2, 0)))
    private val sources = arrayOf("Wavetable", "Noise", "DDA sample")
    fun listenValue(ins: PceInstrument, channel: Int): Int =
        if (ins.kind == PceKind.NOISE) (channel - 4).coerceIn(0, 1) else channel.coerceIn(0, 5)
    fun listenChannel(ins: PceInstrument, value: Int): Int =
        if (ins.kind == PceKind.NOISE) 4 + value.coerceIn(0, 1) else value.coerceIn(0, 5)
    val labels = arrayOf("Source", "Wave", "Contour", "Motion", "Repeat", "Listen")
    const val COUNT = 6
    fun maximum(i: Int, s: PceSound, ins: PceInstrument): Int = when (i) {
        0 -> 2
        1 -> when (ins.kind) { PceKind.TONE -> maxOf(0, s.waves.lastIndex); PceKind.NOISE -> 31; PceKind.SAMPLE -> maxOf(0, s.samples.lastIndex) }
        2 -> 3
        3 -> if (ins.kind == PceKind.SAMPLE) PceRules.NOTES.last - PceRules.NOTES.first else 3
        4 -> 1
        else -> if (ins.kind == PceKind.NOISE) 1 else 5
    }
    fun enabled(i: Int, s: PceSound, ins: PceInstrument): Boolean = when (i) {
        1 -> when (ins.kind) { PceKind.TONE -> s.waves.isNotEmpty() && ins.waves.values.isEmpty(); PceKind.NOISE -> ins.arpeggio.values.size <= 1 && ins.arpeggio.release < 0; PceKind.SAMPLE -> s.samples.isNotEmpty() }
        4 -> ins.kind == PceKind.SAMPLE && s.samples.getOrNull(ins.sample) != null
        else -> true
    }
    fun value(i: Int, ins: PceInstrument): Int = when (i) {
        0 -> ins.kind.ordinal
        1 -> when (ins.kind) { PceKind.TONE -> ins.wave; PceKind.SAMPLE -> ins.sample; PceKind.NOISE -> ins.arpeggio.values.firstOrNull()?.takeIf { it in 0..31 } ?: if (ins.arpeggio.values.isEmpty()) 0 else -1 }
        2 -> if (ins.volume.values.isEmpty()) 0 else volume.indexOf(ins.volume)
        3 -> when (ins.kind) {
            PceKind.SAMPLE -> ins.root - PceRules.NOTES.first
            PceKind.NOISE -> noisePitch.indexOf(ins.arpeggio)
            PceKind.TONE -> pitch.indices.firstOrNull { pitch[it] == ins.pitch && (ins.pitch.values.isEmpty() || ins.pitchRelative == (it >= 2)) } ?: -1
        }
        4 -> if (ins.loop) 1 else 0
        else -> 0
    }
    fun title(i: Int, ins: PceInstrument): String = when (i) {
        1 -> when (ins.kind) { PceKind.TONE -> "Wave"; PceKind.NOISE -> "Noise color"; PceKind.SAMPLE -> "Recording" }
        3 -> if (ins.kind == PceKind.SAMPLE) "Root pitch" else "Motion"
        else -> labels[i]
    }
    fun text(i: Int, value: Int, ins: PceInstrument): String {
        if (value < 0) return "Custom"
        return when (i) {
            0 -> sources[value]
            1 -> if (ins.kind == PceKind.NOISE) "+$value notes" else "${value + 1}"
            2 -> contours[value]
            3 -> if (ins.kind == PceKind.SAMPLE) "Note ${value + PceRules.NOTES.first}" else if (ins.kind == PceKind.NOISE) noiseMotions[value] else motions[value]
            4 -> if (value == 0) "One shot" else "From loop start"
            else -> "PSG ${listenChannel(ins, value) + 1}"
        }
    }
    fun hint(i: Int, s: PceSound, ins: PceInstrument): String = when (i) {
        0 -> "Changes source only; other settings stay."
        1 -> when (ins.kind) {
            PceKind.TONE -> if (ins.waves.values.isNotEmpty()) "Wave sequence active: edit in Advanced." else "Select stored wave; never redraws shared data."
            PceKind.NOISE -> if (enabled(1, s, ins)) "Note-relative color; rates wrap every 32 notes." else "Noise sequence active: choose Still motion or use Advanced."
            PceKind.SAMPLE -> if (s.samples.isEmpty()) "Import recordings in Advanced > Sample > Add." else "Select recording; loop start clamps to its length."
        }
        2 -> "Replaces volume contour only; timing follows song ticks."
        3 -> if (ins.kind == PceKind.SAMPLE) "This note plays at the song's DDA sample rate." else if (ins.kind == PceKind.NOISE) "Replaces noise color / arpeggio only; clock wraps at 32." else "Replaces pitch motion only; not the shared LFO."
        4 -> if (ins.kind == PceKind.SAMPLE) "Repeats from stored loop start; Advanced sets the point." else "Repeat belongs to DDA samples."
        else -> "Noise uses PSG 5/6. LFO: PSG 2 modulates PSG 1."
    }
    fun apply(i: Int, s: PceSound, ins: PceInstrument, requested: Int): PceInstrument {
        if (!enabled(i, s, ins) || i == 5) return ins
        val n = requested.coerceIn(0, maximum(i, s, ins))
        if (value(i, ins) == n) return ins
        return when (i) {
            0 -> ins.copy(kind = PceKind.entries[n])
            1 -> when (ins.kind) {
                PceKind.TONE -> ins.copy(wave = n)
                PceKind.NOISE -> ins.copy(arpeggio = PceEnvelope(listOf(n)))
                PceKind.SAMPLE -> ins.copy(sample = n, loopStart = ins.loopStart.coerceAtMost(s.samples[n].data.lastIndex))
            }
            2 -> ins.copy(volume = volume[n])
            3 -> when (ins.kind) {
                PceKind.SAMPLE -> ins.copy(root = n + PceRules.NOTES.first)
                PceKind.NOISE -> ins.copy(arpeggio = noisePitch[n])
                PceKind.TONE -> ins.copy(pitch = pitch[n], pitchRelative = n >= 2)
            }
            4 -> ins.copy(loop = n != 0)
            else -> ins
        }
    }
}
