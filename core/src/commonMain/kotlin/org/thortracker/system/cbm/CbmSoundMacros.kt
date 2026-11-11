package org.thortracker.system.cbm

import org.thortracker.backend.CbmImage

internal enum class CbmSoundControl(val label: String) {
    VOICE("Voice"), GATE("Gate"), MASTER("All voices"), LANE("Listen lane"),
    SAMPLE("Recording"), ROOT("Original pitch"), LOOP("Repeat"), LOOP_START("Repeat from")
}

/** Recipes are explicit replacements of just one native program, never whole-instrument presets. */
internal object CbmSoundMacros {
    private val vic = listOf(CbmSoundControl.GATE, CbmSoundControl.MASTER, CbmSoundControl.LANE)
    private val ted = listOf(CbmSoundControl.VOICE, CbmSoundControl.GATE, CbmSoundControl.MASTER, CbmSoundControl.LANE)
    private val sample = listOf(CbmSoundControl.VOICE, CbmSoundControl.SAMPLE, CbmSoundControl.ROOT,
        CbmSoundControl.LOOP, CbmSoundControl.LOOP_START)
    private val gateNames = arrayOf("Held", "Tap", "Short", "Pulse")
    private val masterNames = arrayOf("Leave", "Pluck", "Fade", "Swell")
    private val voiceNames = arrayOf("Square", "Noise", "Sample")
    private val gates = listOf(CbmEnvelope(), CbmEnvelope(listOf(1, 1, 0)),
        CbmEnvelope(List(8) { 1 } + 0), CbmEnvelope(listOf(1, 1, 0, 0), loop = 0))
    private val noiseGates = gates.map { e -> e.copy(values = e.values.map { if (it == 1) 2 else it }) }
    private fun gates(ins: CbmInstrument) = if (ins.wave == 2) noiseGates else gates
    private fun contours(max: Int) = arrayOf(CbmEnvelope(),
        CbmEnvelope(listOf(max, max * 3 / 4, max / 2, max / 4, 0)),
        CbmEnvelope(List(16) { max * (15 - it) / 15 }),
        CbmEnvelope(List(16) { max * it / 15 }))
    private val vicContours = contours(CbmChip.VIC.maxVolume)
    private val tedContours = contours(CbmChip.TED.maxVolume)
    private fun contours(chip: CbmChip) = if (chip == CbmChip.VIC) vicContours else tedContours

    fun controls(chip: CbmChip, ins: CbmInstrument) = when {
        chip == CbmChip.VIC -> vic
        ins.kind == CbmKind.SAMPLE -> sample
        else -> ted
    }
    fun maximum(c: CbmSoundControl, sound: CbmSound, ins: CbmInstrument): Int = when (c) {
        CbmSoundControl.VOICE -> 2
        CbmSoundControl.GATE, CbmSoundControl.MASTER -> 3
        CbmSoundControl.LANE -> if (sound.chip == CbmChip.VIC) 3 else 1
        CbmSoundControl.SAMPLE -> maxOf(0, sound.samples.size - 1)
        CbmSoundControl.ROOT -> CbmImage.ROOT_MAX - CbmImage.ROOT_MIN
        CbmSoundControl.LOOP -> 1
        CbmSoundControl.LOOP_START -> maxOf(0, (sound.samples.getOrNull(ins.sample)?.data?.size ?: 1) - 1)
    }
    fun enabled(c: CbmSoundControl, sound: CbmSound, ins: CbmInstrument): Boolean = when (c) {
        CbmSoundControl.LANE -> sound.chip == CbmChip.VIC || !ins.playsNoise
        CbmSoundControl.GATE -> ins.waves.values.all { it == 0 || it == ins.wave }
        CbmSoundControl.SAMPLE -> sound.samples.isNotEmpty()
        CbmSoundControl.LOOP_START -> ins.loop && ins.sample in sound.samples.indices
        else -> true
    }
    fun value(c: CbmSoundControl, sound: CbmSound, ins: CbmInstrument, lane: Int): Int? = when (c) {
        CbmSoundControl.VOICE -> if (ins.kind == CbmKind.SAMPLE) 2 else if (ins.waves.values.any { it != 0 && it != ins.wave }) null else ins.wave - 1
        CbmSoundControl.GATE -> gates(ins).indexOf(ins.waves).takeIf { it >= 0 }
        CbmSoundControl.MASTER -> contours(sound.chip).indexOf(ins.volume).takeIf { it >= 0 }
        CbmSoundControl.LANE -> lane.coerceIn(0, maximum(c, sound, ins))
        CbmSoundControl.SAMPLE -> ins.sample
        CbmSoundControl.ROOT -> ins.root - CbmImage.ROOT_MIN
        CbmSoundControl.LOOP -> if (ins.loop) 1 else 0
        CbmSoundControl.LOOP_START -> ins.loopStart
    }
    fun label(c: CbmSoundControl, value: Int, sound: CbmSound): String = when (c) {
        CbmSoundControl.VOICE -> voiceNames[value]
        CbmSoundControl.GATE -> gateNames[value]
        CbmSoundControl.MASTER -> masterNames[value]
        CbmSoundControl.LANE -> sound.system.channels[value].name
        CbmSoundControl.SAMPLE -> sound.samples.getOrNull(value)?.name ?: "Add in Advanced"
        CbmSoundControl.ROOT -> sound.system.noteName(value + CbmImage.ROOT_MIN)
        CbmSoundControl.LOOP -> if (value == 0) "Once" else "Loop"
        CbmSoundControl.LOOP_START -> "Frame $value"
    }
    fun hint(c: CbmSoundControl, chip: CbmChip): String = when (c) {
        CbmSoundControl.VOICE -> "Noise: voice 2. Sample: both; add in Advanced."
        CbmSoundControl.GATE -> if (chip == CbmChip.VIC) "This lane only; note sets noise rate." else "This voice only. Mixed programs: Advanced."
        CbmSoundControl.MASTER -> "Shared master: shapes EVERY voice."
        CbmSoundControl.LANE -> "Audition lane only; no patch change."
        CbmSoundControl.SAMPLE -> "Existing recording; add audio in Advanced."
        CbmSoundControl.ROOT -> "Note that plays at the recording's own rate."
        CbmSoundControl.LOOP -> "Repeat recording until OFF."
        CbmSoundControl.LOOP_START -> "First frame of the repeated section."
    }
    fun set(c: CbmSoundControl, sound: CbmSound, ins: CbmInstrument, requested: Int): CbmInstrument {
        if (!enabled(c, sound, ins) || c == CbmSoundControl.LANE) return ins
        val n = requested.coerceIn(0, maximum(c, sound, ins))
        if (value(c, sound, ins, 0) == n) return ins
        return when (c) {
            CbmSoundControl.VOICE -> when {
                n == 2 -> ins.copy(kind = CbmKind.SAMPLE)
                // Returning from a recording restores the exact inactive tone program.
                ins.kind == CbmKind.SAMPLE && ins.wave == n + 1 -> ins.copy(kind = CbmKind.TONE)
                else -> ins.copy(kind = CbmKind.TONE, wave = n + 1,
                    waves = ins.waves.copy(values = ins.waves.values.map { if (it == 0) 0 else n + 1 }))
            }
            CbmSoundControl.GATE -> ins.copy(waves = gates(ins)[n])
            CbmSoundControl.MASTER -> ins.copy(volume = contours(sound.chip)[n])
            CbmSoundControl.SAMPLE -> ins.copy(sample = n, loopStart = ins.loopStart.coerceAtMost(sound.samples[n].data.lastIndex))
            CbmSoundControl.ROOT -> ins.copy(root = n + CbmImage.ROOT_MIN)
            CbmSoundControl.LOOP -> ins.copy(loop = n == 1)
            CbmSoundControl.LOOP_START -> ins.copy(loopStart = n)
            CbmSoundControl.LANE -> ins
        }
    }
}
