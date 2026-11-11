package org.thortracker.system.nes

import org.thortracker.system.ChannelKind
import org.thortracker.system.ym2612.YmSoundControl
import org.thortracker.system.ym2612.YmSoundMacros

/** A projection of the native channel, not an instrument type persisted in the project. */
internal enum class NesVoice(val title: String) {
    PULSE("Pulse circuit"), TRIANGLE("Triangle gate"), NOISE("Noise generator"), DPCM("Delta sample deck"),
    VRC6_PULSE("Eight-width pulse"), SAW("Saw accumulator"), VRC7("Two-operator FM"),
    N163("Wave memory"), FDS("Disk wave + motion"), SSG("Tone + noise mixer"), PCM("PCM sample deck"),
    EPSM_FM("Four-operator FM"), RHYTHM("ROM drum deck");

    companion object {
        fun of(expansion: NesExpansion, channel: Int, chip: NesChip): NesVoice = when {
            channel == NesRules.DPCM -> DPCM
            NesRules.isMmc5Pcm(expansion, channel) -> PCM
            NesRules.isEpsmRhythm(expansion, channel) -> RHYTHM
            chip == NesChip.VRC7 -> VRC7
            chip == NesChip.N163 -> N163
            chip == NesChip.FDS -> FDS
            chip == NesChip.S5B -> SSG
            chip == NesChip.EPSM -> if (expansion.system.channels[channel].kind == ChannelKind.VOICE) EPSM_FM else SSG
            chip == NesChip.VRC6 -> if (expansion.system.channels[channel].kind == ChannelKind.SAW) SAW else VRC6_PULSE
            channel == 2 -> TRIANGLE
            channel == 3 -> NOISE
            else -> PULSE
        }
    }
}

internal enum class NesSoundControl(val label: String, val maximum: Int, val hint: String) {
    VOICE("Voice", 0, "Choose a channel; crossing chips converts this instrument's native type"),
    CHARACTER("Width", 3, "Static pulse width; choosing replaces a Custom duty sequence"),
    VRC6_WIDTH("Width", 7, "Eight pulse widths, from thin to square"),
    GRAIN("Grain", 1, "Air uses long noise; Metal repeats the short sequence"),
    CONTOUR("Contour", 3, "Choose Held, Pluck, Swell or Short; replaces volume sequence"),
    GATE("Gate", 3, "Triangle has no volume fade: only held or timed on/off gates"),
    TRANSPOSE("Register", 48, "Static pitch offset; choosing replaces a Custom arpeggio"),
    DRIVE("Drive", 2, "Full can overflow the saw accumulator above volume 10"),
    WAVE("Wave", 3, "Reshape the first wave only; sequence, size and RAM stay intact"),
    MOTION("Motion", 3, "Choose a modulation table; depth and speed stay intact"),
    DEPTH("Depth", 63, "Modulation amount; needs a moving table and nonzero speed"),
    SPEED("Motion speed", 4095, "Manual modulation speed; auto pitch remains in Advanced"),
    MIX("Blend", 3, "Tone, noise, both or constant level; replaces the mixer sequence"),
    NOISE_COLOR("Noise color", 31, "Shared noise clock: higher values make a coarser texture"),
    ENVELOPE("Envelope voice", 8, "Shared hardware envelope; Off returns to the volume contour"),
    PRESET("FM voice", 15, "ROM voices or Custom; all six FM channels share one custom patch"),
    BRIGHTNESS("Brightness", 63, "Modulator strength only; editing a ROM voice makes it Custom"),
    HARMONICS("Harmonics", 4, "Modulator ratio only; carrier pitch stays unchanged"),
    EDGE("Edge", 7, "Real modulator feedback; editing a ROM voice makes it Custom"),
    FM_BRIGHTNESS("Brightness", 127, "Modulator strength, preserving relative levels"),
    FM_STRUCTURE("Structure", 7, "Explicit routing and role-aware level balancing"),
    FM_HARMONICS("Harmonics", 4, "Modulator ratios only; carrier pitch stays unchanged"),
    FM_EDGE("Edge", 7, "Operator one's feedback only"),
    FM_CONTOUR("Contour", 3, "Carrier envelopes only; unavailable with active SSG envelopes"),
    HIT("Hit level", 15, "Only the first volume step sets the fixed ROM drum level"),
    PAN("Output", 3, "Centre, left, right or muted; no sample data changes"),
    KEY("Key", 95, "Choose the note whose sample mapping you are editing"),
    SAMPLE("Sample", 0, "Map an existing sample; import and starter kits stay in Advanced"),
    RATE("Speed", 15, "DPCM hardware playback rate, not a chromatic pitch shift"),
    TUNE("Tune", 72, "PCM semitone offset from -48 to +24"),
    LOOP("Repeat", 1, "Repeat this key's sample; PCM loop start stays in Advanced"),
    START("Start level", 64, "Inherit or set this key's initial delta-decoder level"),
}

/** All recipes own only the fields named here. Reading never rewrites an Advanced program. */
internal object NesSoundMacros {
    private val pulse = listOf(NesSoundControl.VOICE, NesSoundControl.CHARACTER, NesSoundControl.CONTOUR, NesSoundControl.TRANSPOSE)
    private val triangle = listOf(NesSoundControl.VOICE, NesSoundControl.GATE, NesSoundControl.TRANSPOSE)
    private val noise = listOf(NesSoundControl.VOICE, NesSoundControl.GRAIN, NesSoundControl.CONTOUR, NesSoundControl.TRANSPOSE)
    private val vrc6 = listOf(NesSoundControl.VOICE, NesSoundControl.VRC6_WIDTH, NesSoundControl.CONTOUR, NesSoundControl.TRANSPOSE)
    private val saw = listOf(NesSoundControl.VOICE, NesSoundControl.DRIVE, NesSoundControl.CONTOUR, NesSoundControl.TRANSPOSE)
    private val wave = listOf(NesSoundControl.VOICE, NesSoundControl.WAVE, NesSoundControl.CONTOUR, NesSoundControl.TRANSPOSE)
    private val fds = listOf(NesSoundControl.VOICE, NesSoundControl.WAVE, NesSoundControl.MOTION, NesSoundControl.DEPTH, NesSoundControl.SPEED, NesSoundControl.CONTOUR)
    private val ssg = listOf(NesSoundControl.VOICE, NesSoundControl.MIX, NesSoundControl.NOISE_COLOR, NesSoundControl.ENVELOPE, NesSoundControl.CONTOUR, NesSoundControl.TRANSPOSE)
    private val vrc7 = listOf(NesSoundControl.VOICE, NesSoundControl.PRESET, NesSoundControl.BRIGHTNESS, NesSoundControl.HARMONICS, NesSoundControl.EDGE, NesSoundControl.CONTOUR)
    private val fm = listOf(NesSoundControl.VOICE, NesSoundControl.FM_BRIGHTNESS, NesSoundControl.FM_STRUCTURE, NesSoundControl.FM_HARMONICS, NesSoundControl.FM_EDGE, NesSoundControl.FM_CONTOUR)
    private val rhythm = listOf(NesSoundControl.VOICE, NesSoundControl.HIT, NesSoundControl.PAN)
    private val dpcm = listOf(NesSoundControl.VOICE, NesSoundControl.KEY, NesSoundControl.SAMPLE, NesSoundControl.RATE, NesSoundControl.LOOP, NesSoundControl.START)
    private val pcm = listOf(NesSoundControl.VOICE, NesSoundControl.KEY, NesSoundControl.SAMPLE, NesSoundControl.TUNE, NesSoundControl.LOOP)
    private val contours = arrayOf(NesEnvelope(listOf(15)), NesEnvelope(List(32) { (15 - it / 2).coerceAtLeast(0) }),
        NesEnvelope(List(32) { (it / 2).coerceAtMost(15) }), NesEnvelope(List(9) { (15 - it * 2).coerceAtLeast(0) }))
    private val gates = arrayOf(NesEnvelope(listOf(15)), NesEnvelope(List(9) { if (it < 8) 15 else 0 }),
        NesEnvelope(List(17) { if (it < 16) 15 else 0 }), NesEnvelope(List(33) { if (it < 32) 15 else 0 }))
    private val waveChoices = arrayOf(WavePreset.SINE, WavePreset.TRIANGLE, WavePreset.SAWTOOTH, WavePreset.SQUARE50)
    private val ratios = intArrayOf(1, 2, 3, 5, 7)
    private val contourNames = arrayOf("Held", "Pluck", "Swell", "Short")
    private val gateNames = arrayOf("Held", "8 frames", "16 frames", "32 frames")
    private val harmonicNames = arrayOf("Round", "Hollow", "Reedy", "Metal", "Glass")
    private val mixNames = arrayOf("Tone", "Noise", "Tone + noise", "Constant level")
    private val panNames = arrayOf("Centre", "Left", "Right", "Muted")
    private val mixValues = intArrayOf(2, 1, 0, 3)

    fun controls(voice: NesVoice): List<NesSoundControl> = when (voice) {
        NesVoice.PULSE -> pulse; NesVoice.TRIANGLE -> triangle; NesVoice.NOISE -> noise
        NesVoice.VRC6_PULSE -> vrc6; NesVoice.SAW -> saw; NesVoice.VRC7 -> vrc7
        NesVoice.N163 -> wave; NesVoice.FDS -> fds; NesVoice.SSG -> ssg
        NesVoice.EPSM_FM -> fm; NesVoice.RHYTHM -> rhythm; NesVoice.DPCM -> dpcm; NesVoice.PCM -> pcm
    }

    private fun fm(c: NesSoundControl): YmSoundControl? = when (c) {
        NesSoundControl.FM_BRIGHTNESS -> YmSoundControl.BRIGHTNESS
        NesSoundControl.FM_STRUCTURE -> YmSoundControl.STRUCTURE
        NesSoundControl.FM_HARMONICS -> YmSoundControl.HARMONICS
        NesSoundControl.FM_EDGE -> YmSoundControl.EDGE
        NesSoundControl.FM_CONTOUR -> YmSoundControl.CONTOUR
        else -> null
    }

    fun enabled(c: NesSoundControl, ins: NesInstrument, voice: NesVoice, key: Int): Boolean = when (c) {
        NesSoundControl.CONTOUR -> voice != NesVoice.SSG || ins.s5b.shape == S5bShape.OFF
        NesSoundControl.SPEED -> !ins.fds.autoMod
        NesSoundControl.RATE, NesSoundControl.START -> key in ins.dpcm
        NesSoundControl.TUNE -> key in ins.pcm
        NesSoundControl.LOOP -> if (voice == NesVoice.PCM) key in ins.pcm else key in ins.dpcm
        else -> fm(c)?.let { YmSoundMacros.enabled(it, ins.epsm.asYm()) } ?: true
    }

    private fun constant(e: NesEnvelope, default: Int): Int? = when {
        e.values.isEmpty() -> default
        e.values.all { it == e.values[0] } -> e.values[0]
        else -> null
    }

    fun value(c: NesSoundControl, ins: NesInstrument, voice: NesVoice, key: Int = 48): Int? = when (c) {
        NesSoundControl.CHARACTER, NesSoundControl.VRC6_WIDTH -> constant(ins.duty, 0)
        NesSoundControl.GRAIN -> constant(ins.duty, 0)?.let { it and 1 }
        NesSoundControl.CONTOUR -> contours.indexOf(ins.volume).takeIf { it >= 0 }
        NesSoundControl.GATE -> gates.indexOf(ins.volume).takeIf { it >= 0 }
        NesSoundControl.TRANSPOSE -> constant(ins.arpeggio, 0)?.let { (it + 24).takeIf { n -> n in 0..48 } }
        NesSoundControl.DRIVE -> 2 - ins.sawVolume.ordinal
        NesSoundControl.WAVE -> waveChoices.indices.firstOrNull { i ->
            if (voice == NesVoice.FDS) ins.fds.waves.firstOrNull() == FdsPresets.wave(waveChoices[i], 0, 1)
            else ins.wave.waves.firstOrNull() == N163Presets.wave(waveChoices[i], ins.wave.size, 0, 1)
        }
        NesSoundControl.MOTION -> waveChoices.indices.firstOrNull { ins.fds.modulation == FdsPresets.modulation(waveChoices[it]) }
        NesSoundControl.DEPTH -> ins.fds.modDepth
        NesSoundControl.SPEED -> ins.fds.modSpeed
        NesSoundControl.MIX -> constant(ins.mixer, 2)?.let { mixValues.indexOf(it).takeIf { i -> i >= 0 } }
        NesSoundControl.NOISE_COLOR -> constant(ins.noise, 0)
        NesSoundControl.ENVELOPE -> ins.s5b.shape.ordinal
        NesSoundControl.PRESET -> ins.fm.preset
        NesSoundControl.BRIGHTNESS -> 63 - Vrc7Param.LEVEL.get(ins.fm.sounding, 0)
        NesSoundControl.HARMONICS -> ratios.indexOf(Vrc7Param.MULT.get(ins.fm.sounding, 0)).takeIf { it >= 0 }
        NesSoundControl.EDGE -> Vrc7Param.FEEDBACK.get(ins.fm.sounding, 0)
        NesSoundControl.HIT -> ins.volume.values.firstOrNull() ?: 15
        NesSoundControl.PAN -> when { ins.epsm.left && ins.epsm.right -> 0; ins.epsm.left -> 1; ins.epsm.right -> 2; else -> 3 }
        NesSoundControl.SAMPLE -> if (voice == NesVoice.PCM) ins.pcm[key]?.sample?.plus(1) ?: 0 else ins.dpcm[key]?.sample?.plus(1) ?: 0
        NesSoundControl.RATE -> ins.dpcm[key]?.pitch
        NesSoundControl.TUNE -> ins.pcm[key]?.pitch?.plus(48)
        NesSoundControl.LOOP -> (if (voice == NesVoice.PCM) ins.pcm[key]?.loop else ins.dpcm[key]?.loop)?.let { if (it) 1 else 0 }
        NesSoundControl.START -> ins.dpcm[key]?.dmcInitial?.plus(1)
        NesSoundControl.VOICE, NesSoundControl.KEY -> null
        else -> fm(c)?.let { YmSoundMacros.value(it, ins.epsm.asYm()) }
    }

    fun label(c: NesSoundControl, value: Int): String = when (c) {
        NesSoundControl.CHARACTER -> arrayOf("Thin 12.5%", "Hollow 25%", "Square 50%", "Hollow 75%")[value]
        NesSoundControl.VRC6_WIDTH -> "${(value + 1) * 100 / 16}%"
        NesSoundControl.GRAIN -> if (value == 0) "Air" else "Metal"
        NesSoundControl.CONTOUR -> contourNames[value]
        NesSoundControl.GATE -> gateNames[value]
        NesSoundControl.TRANSPOSE -> "${value - 24} semitones"
        NesSoundControl.DRIVE -> Vrc6SawVolume.entries[2 - value].label
        NesSoundControl.WAVE, NesSoundControl.MOTION -> waveChoices[value].label
        NesSoundControl.MIX -> mixNames[value]
        NesSoundControl.ENVELOPE -> S5bShape.entries[value].label
        NesSoundControl.PRESET -> if (value == 0) "Custom / shared" else Vrc7Presets.NAMES[value]
        NesSoundControl.HARMONICS -> harmonicNames[value]
        NesSoundControl.PAN -> panNames[value]
        NesSoundControl.LOOP -> if (value == 0) "One shot" else "Repeat"
        NesSoundControl.START -> if (value == 0) "Inherit sample" else "${(value - 1) * 2}"
        NesSoundControl.TUNE -> "${value - 48} semitones"
        else -> fm(c)?.let { YmSoundMacros.label(it, value) } ?: value.toString()
    }

    fun apply(c: NesSoundControl, ins: NesInstrument, voice: NesVoice, value: Int, key: Int = 48, sampleCount: Int = 0): NesInstrument {
        val maximum = if (c == NesSoundControl.SAMPLE) sampleCount else c.maximum
        if (value !in 0..maximum || !enabled(c, ins, voice, key) || NesSoundMacros.value(c, ins, voice, key) == value) return ins
        fun custom(param: Vrc7Param, n: Int) = ins.copy(fm = Vrc7Patch(0, param.set(ins.fm.sounding, 0, n)))
        return when (c) {
            NesSoundControl.CHARACTER, NesSoundControl.VRC6_WIDTH, NesSoundControl.GRAIN -> ins.copy(duty = NesEnvelope(listOf(value)))
            NesSoundControl.CONTOUR -> ins.copy(volume = contours[value])
            NesSoundControl.GATE -> ins.copy(volume = gates[value])
            NesSoundControl.TRANSPOSE -> ins.copy(arpeggio = NesEnvelope(listOf(value - 24)))
            NesSoundControl.DRIVE -> ins.copy(sawVolume = Vrc6SawVolume.entries[2 - value])
            NesSoundControl.WAVE -> if (voice == NesVoice.FDS) ins.copy(fds = ins.fds.copy(waves = ins.fds.waves.mapIndexed { i, w -> if (i == 0) FdsPresets.wave(waveChoices[value], 0, 1) else w }))
                else ins.copy(wave = ins.wave.copy(waves = ins.wave.waves.mapIndexed { i, w -> if (i == 0) N163Presets.wave(waveChoices[value], ins.wave.size, 0, 1) else w }))
            NesSoundControl.MOTION -> ins.copy(fds = ins.fds.copy(modulation = FdsPresets.modulation(waveChoices[value])))
            NesSoundControl.DEPTH -> ins.copy(fds = ins.fds.copy(modDepth = value))
            NesSoundControl.SPEED -> ins.copy(fds = ins.fds.copy(modSpeed = value))
            NesSoundControl.MIX -> ins.copy(mixer = NesEnvelope(listOf(mixValues[value])))
            NesSoundControl.NOISE_COLOR -> ins.copy(noise = NesEnvelope(listOf(value)))
            NesSoundControl.ENVELOPE -> ins.copy(s5b = ins.s5b.copy(shape = S5bShape.entries[value]))
            NesSoundControl.PRESET -> ins.copy(fm = if (value == 0) Vrc7Patch(0, ins.fm.sounding) else ins.fm.withPreset(value))
            NesSoundControl.BRIGHTNESS -> custom(Vrc7Param.LEVEL, 63 - value)
            NesSoundControl.HARMONICS -> custom(Vrc7Param.MULT, ratios[value])
            NesSoundControl.EDGE -> custom(Vrc7Param.FEEDBACK, value)
            NesSoundControl.HIT -> ins.copy(volume = ins.volume.copy(values = if (ins.volume.values.isEmpty()) listOf(value) else ins.volume.values.mapIndexed { i, n -> if (i == 0) value else n }))
            NesSoundControl.PAN -> ins.copy(epsm = ins.epsm.copy(left = value == 0 || value == 1, right = value == 0 || value == 2))
            NesSoundControl.SAMPLE -> if (voice == NesVoice.PCM) ins.copy(pcm = if (value == 0) ins.pcm - key else ins.pcm + (key to (ins.pcm[key] ?: PcmMapping(value - 1)).copy(sample = value - 1)))
                else ins.copy(dpcm = if (value == 0) ins.dpcm - key else ins.dpcm + (key to (ins.dpcm[key] ?: DpcmMapping(value - 1)).copy(sample = value - 1)))
            NesSoundControl.RATE -> ins.copy(dpcm = ins.dpcm + (key to ins.dpcm.getValue(key).copy(pitch = value)))
            NesSoundControl.START -> ins.copy(dpcm = ins.dpcm + (key to ins.dpcm.getValue(key).copy(dmcInitial = value - 1)))
            NesSoundControl.TUNE -> ins.copy(pcm = ins.pcm + (key to ins.pcm.getValue(key).copy(pitch = value - 48)))
            NesSoundControl.LOOP -> if (voice == NesVoice.PCM) ins.copy(pcm = ins.pcm + (key to ins.pcm.getValue(key).copy(loop = value == 1)))
                else ins.copy(dpcm = ins.dpcm + (key to ins.dpcm.getValue(key).copy(loop = value == 1)))
            else -> fm(c)?.let { ins.copy(epsm = ins.epsm.withYm(YmSoundMacros.apply(it, ins.epsm.asYm(), value))) } ?: ins
        }
    }
}
