package org.thortracker.system.ws

/** Musical choices act only on their named native fields; no hidden macro state. */
internal object WsSoundMacros {
    const val COUNT = 5
    private val shapes = arrayOf("Held", "Pluck", "Soft pluck", "Swell")
    private val voices = arrayOf("Wave", "Noise", "CPU sample", "Color DMA")
    private val bends = intArrayOf(0, -1, -4, 1, 4)
    private val bendNames = arrayOf("Still", "Fall", "Dive", "Rise", "Lift")
    private val rates = arrayOf("4 kB/s", "6 kB/s", "12 kB/s", "24 kB/s")
    private val playback = arrayOf("Once", "Loop", "Reverse", "Reverse loop")

    private val noiseTaps = intArrayOf(14, 10, 13, 4, 8, 6, 9, 11)

    /** Reset-state preview, one real CH4 LFSR clock; not a claim about a free-running note's phase. */
    fun noiseStep(register: Int, tap: Int): Int =
        ((register shl 1) or (1 xor (register shr 7) xor (register shr noiseTaps[tap]) and 1)) and 0x7fff
    fun title(i: Int, ins: WsInstrument): String = when (i) {
        0 -> "Voice"
        1 -> when (ins.kind) { WsKind.TONE -> "Wave"; WsKind.NOISE -> "Grain"; WsKind.SAMPLE -> "Recording" }
        2 -> "Contour"
        3 -> when (ins.kind) { WsKind.TONE -> "Bend"; WsKind.NOISE -> "Attack"; WsKind.SAMPLE -> if (ins.isDma) "Speed" else "Root note" }
        else -> if (ins.kind == WsKind.SAMPLE) "Playback" else "Listen lane"
    }

    fun maximum(i: Int, ins: WsInstrument, sound: WsSound): Int = when (i) {
        0 -> if (sound.color) 3 else 2
        1 -> when (ins.kind) { WsKind.TONE -> maxOf(0, sound.waves.size - 1); WsKind.NOISE -> 7; WsKind.SAMPLE -> maxOf(0, sound.samples.size - 1) }
        2 -> 3
        3 -> when (ins.kind) { WsKind.TONE -> 4; WsKind.NOISE -> 1; WsKind.SAMPLE -> if (ins.isDma) 3 else WsRules.NOTES.last - WsRules.NOTES.first }
        else -> if (ins.kind == WsKind.SAMPLE) (if (ins.isDma) 3 else 1) else if (ins.kind == WsKind.NOISE) 0 else 3
    }

    fun reason(i: Int, ins: WsInstrument, sound: WsSound, lane: Int): String? = when {
        i == 1 && ins.kind == WsKind.TONE && !ins.waves.isEmpty -> "Wave sequence owns the tone: Advanced"
        i == 1 && ins.kind == WsKind.NOISE && !ins.taps.isEmpty -> "Tap sequence owns the grain: Advanced"
        i == 1 && ins.kind == WsKind.SAMPLE && sound.samples.isEmpty() -> "Add a recording in Advanced / Sample"
        i == 1 && ins.kind == WsKind.TONE && sound.waves.isEmpty() -> "Add a wave in the Wave editor"
        i == 3 && ins.kind == WsKind.TONE && lane != WsRules.SWEEP_LANE -> "Bend sounds on CH3: choose Listen lane"
        i == 3 && ins.kind == WsKind.TONE && !ins.pitch.isEmpty -> "Pitch sequence owns motion: Advanced"
        else -> null
    }

    private fun contour(shape: Int, peak: Int): WsEnvelope = when (shape) {
        0 -> WsEnvelope(listOf(peak))
        1 -> WsEnvelope(List(8) { peak * (7 - it) / 7 })
        2 -> WsEnvelope(List(32) { peak * (31 - it) / 31 })
        else -> WsEnvelope(List(32) { peak * it / 31 })
    }

    private fun shape(ins: WsInstrument): Int? {
        val e = ins.volume
        if (e.isEmpty) return if (e.loop == -1 && e.release == -1) 0 else null
        if (e.loop != -1 || e.release != -1) return null
        if (e.values.size == 1) return 0
        val peak = e.values.maxOrNull() ?: return null
        val last = e.values.lastIndex
        if (last != 7 && last != 31) return null
        var falling = true
        var rising = last == 31
        for (i in e.values.indices) {
            if (e.values[i] != peak * (last - i) / last) falling = false
            if (e.values[i] != peak * i / last) rising = false
        }
        return if (falling) (if (last == 7) 1 else 2) else if (rising) 3 else null
    }

    fun value(i: Int, ins: WsInstrument, lane: Int): Int? = when (i) {
        0 -> when (ins.kind) { WsKind.TONE -> 0; WsKind.NOISE -> 1; WsKind.SAMPLE -> if (ins.isDma) 3 else 2 }
        1 -> when (ins.kind) { WsKind.TONE -> if (ins.waves.isEmpty) ins.wave else null; WsKind.NOISE -> if (ins.taps.isEmpty) ins.tap else null; WsKind.SAMPLE -> ins.sample }
        2 -> shape(ins)
        3 -> when (ins.kind) {
            WsKind.TONE -> if (ins.sweep == 0) 0 else if (ins.sweepTime == 7) bends.indexOf(ins.sweep).takeIf { it >= 0 } else null
            WsKind.NOISE -> if (ins.lfsrReset) 1 else 0
            WsKind.SAMPLE -> if (ins.isDma) ins.rate else ins.root - WsRules.NOTES.first
        }
        else -> if (ins.kind == WsKind.SAMPLE) (if (ins.loop) 1 else 0) + (if (ins.isDma && ins.backwards) 2 else 0)
            else if (ins.kind == WsKind.NOISE) 0 else lane.coerceIn(0, 3)
    }

    fun label(i: Int, ins: WsInstrument, sound: WsSound, lane: Int): String {
        val v = value(i, ins, lane) ?: return "Custom"
        return when (i) {
            0 -> voices[v]
            1 -> when (ins.kind) { WsKind.TONE -> "Wave ${v + 1}"; WsKind.NOISE -> "Texture ${v + 1}"; WsKind.SAMPLE -> sound.samples.getOrNull(v)?.name ?: "No recording" }
            2 -> shapes[v]
            3 -> when (ins.kind) { WsKind.TONE -> bendNames[v]; WsKind.NOISE -> if (v == 0) "Free-running" else "Repeatable"; WsKind.SAMPLE -> if (ins.isDma) rates[v] else WsSystem.noteName(ins.root) }
            else -> if (ins.kind == WsKind.SAMPLE) playback[v] else if (ins.kind == WsKind.NOISE) "CH4 only" else "CH${v + 1}"
        }
    }

    fun hint(i: Int, ins: WsInstrument, sound: WsSound, lane: Int): String = reason(i, ins, sound, lane) ?: when (i) {
        0 -> if (sound.samples.isEmpty()) "Sample voices need a recording: Advanced / Sample" else "Other voice settings stay intact"
        1 -> if (ins.kind == WsKind.SAMPLE) "Select existing audio; bytes are never rewritten" else "Shared waves and sequences stay intact"
        2 -> if (shape(ins) == null) "Choosing a contour replaces only the volume program" else "Tick-shaped volume; note OFF stops the sound"
        3 -> when (ins.kind) { WsKind.TONE -> "CH3 hardware bend; rising tones can overflow"; WsKind.NOISE -> "Restart the noise sequence on each note"; WsKind.SAMPLE -> if (ins.isDma) "Bytes per second; stereo frames run at half this rate" else "The note that plays at the shared voice-timer rate" }
        else -> if (ins.kind == WsKind.SAMPLE) "CPU loop keeps its start; DMA loops the whole recording" else "Audition routing only; the patch does not change"
    }

    fun apply(i: Int, ins: WsInstrument, requested: Int, sound: WsSound, lane: Int): WsInstrument {
        if (reason(i, ins, sound, lane) != null) return ins
        val v = requested.coerceIn(0, maximum(i, ins, sound))
        if (value(i, ins, lane) == v) return ins
        return when (i) {
            0 -> when (v) {
                0 -> ins.copy(kind = WsKind.TONE)
                1 -> ins.copy(kind = WsKind.NOISE)
                else -> if (sound.samples.isEmpty()) ins else ins.copy(kind = WsKind.SAMPLE, feed = if (v == 3) WsFeed.DMA else WsFeed.CPU)
            }
            1 -> when (ins.kind) {
                WsKind.TONE -> ins.copy(wave = v)
                WsKind.NOISE -> ins.copy(tap = v)
                WsKind.SAMPLE -> ins.copy(sample = v, loopStart = ins.loopStart.coerceAtMost(sound.samples[v].frames - 1))
            }
            2 -> ins.copy(volume = contour(v, ins.volume.values.maxOrNull() ?: 15))
            3 -> when (ins.kind) {
                WsKind.TONE -> if (v == 0) ins.copy(sweep = 0) else ins.copy(sweep = bends[v], sweepTime = 7)
                WsKind.NOISE -> ins.copy(lfsrReset = v != 0)
                WsKind.SAMPLE -> if (ins.isDma) ins.copy(rate = v) else ins.copy(root = v + WsRules.NOTES.first)
            }
            else -> if (ins.kind != WsKind.SAMPLE) ins else if (ins.isDma) ins.copy(loop = v and 1 != 0, backwards = v and 2 != 0) else ins.copy(loop = v != 0)
        }
    }
}
