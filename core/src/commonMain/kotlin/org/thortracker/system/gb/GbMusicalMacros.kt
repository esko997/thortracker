package org.thortracker.system.gb

/** The three LCD faces edit the original typed patch, never a converted copy or a shared waveform. */
internal object GbMusicalMacros {
    private val PULSE = arrayOf("Character", "Shape", "Length", "Motion")
    private val WAVE = arrayOf("Wave", "Level", "Length")
    private val NOISE = arrayOf("Grain", "Shape", "Length")
    private val WAVES = Array(HugeRules.WAVE_COUNT) { org.thortracker.editor.EditorSession.hex2(it) }
    private val LEVELS = arrayOf("Mute", "25%", "50%", "100%")
    private val GRAIN = arrayOf("Air", "Metal")
    private val WAVE_LENGTHS = arrayOf("125 ms", "250 ms", "500 ms", "Held")
    private val WAVE_GATES = intArrayOf(224, 192, 128)
    private val OUTPUT = intArrayOf(0, 3, 2, 1)

    fun count(kind: GbBank): Int = if (kind == GbBank.PULSE) 4 else 3
    fun label(kind: GbBank, control: Int): String = when (kind) {
        GbBank.PULSE -> PULSE[control]
        GbBank.WAVE -> WAVE[control]
        GbBank.NOISE -> NOISE[control]
    }
    fun steps(kind: GbBank, control: Int): Int = when (kind) {
        GbBank.PULSE -> GbPulseMacros.steps(GbPulseControl.entries[control])
        GbBank.WAVE -> if (control == 0) HugeRules.WAVE_COUNT else 4
        GbBank.NOISE -> when (control) { 0 -> 2; 1 -> 3; else -> 4 }
    }
    fun valueLabel(kind: GbBank, control: Int, index: Int): String = when (kind) {
        GbBank.PULSE -> GbPulseMacros.valueLabel(GbPulseControl.entries[control], index)
        GbBank.WAVE -> when (control) { 0 -> WAVES[index]; 1 -> LEVELS[index]; else -> WAVE_LENGTHS[index] }
        GbBank.NOISE -> if (control == 0) GRAIN[index] else GbPulseMacros.valueLabel(GbPulseControl.entries[control], index)
    }
    fun value(control: Int, i: GbInstrument): Int? = when (i.kind) {
        GbBank.PULSE -> GbPulseMacros.value(GbPulseControl.entries[control], i)
        GbBank.WAVE -> when (control) {
            0 -> i.wave.takeIf { it in 0 until HugeRules.WAVE_COUNT }
            1 -> OUTPUT.indexOf(i.waveVolume).takeIf { it >= 0 }
            else -> if (!i.lengthEnabled) 3 else WAVE_GATES.indexOf(i.length).takeIf { it >= 0 }
        }
        GbBank.NOISE -> when (control) {
            0 -> i.noiseWidth.takeIf { it in 0..1 }
            1 -> GbPulseMacros.shape(i)
            else -> GbPulseMacros.length(i)
        }
    }
    fun apply(control: Int, i: GbInstrument, index: Int): GbInstrument {
        require(control in 0 until count(i.kind) && index in 0 until steps(i.kind, control))
        if (value(control, i) == index) return i
        return when (i.kind) {
            GbBank.PULSE -> GbPulseMacros.apply(GbPulseControl.entries[control], i, index)
            GbBank.WAVE -> when (control) {
                0 -> i.copy(wave = index)
                1 -> i.copy(waveVolume = OUTPUT[index])
                else -> i.copy(lengthEnabled = index < 3, length = if (index < 3) WAVE_GATES[index] else i.length)
            }
            GbBank.NOISE -> when (control) {
                0 -> i.copy(noiseWidth = index)
                1 -> GbPulseMacros.envelope(i, index, GbPulseMacros.length(i) ?: 1)
                else -> GbPulseMacros.envelope(i, GbPulseMacros.shape(i) ?: 0, index)
            }
        }
    }
    fun reset(control: Int, i: GbInstrument): GbInstrument = apply(control, i, when (i.kind) {
        GbBank.PULSE -> when (control) { 0 -> 2; 2 -> 1; else -> 0 }
        GbBank.WAVE -> if (control == 0) 0 else 3
        GbBank.NOISE -> if (control == 2) 1 else 0
    })
    fun help(kind: GbBank, control: Int): String = when (kind) {
        GbBank.PULSE -> GbPulseMacros.help(GbPulseControl.entries[control])
        GbBank.WAVE -> when (control) {
            0 -> "Choose a shared 32-sample wave. Edit its samples in Wave; every sound using it hears those edits."
            1 -> "Hardware output: mute or shift the samples to 25%, 50%, 100%. Changes output level only."
            else -> "Hardware length gate: 125, 250, 500 ms or until note/OFF. Changes the gate only."
        }
        GbBank.NOISE -> if (control == 0) "Air uses the long 15-bit noise sequence; Metal uses the short 7-bit sequence. Pitch still follows the played note."
            else GbPulseMacros.help(GbPulseControl.entries[control])
    }
    /** Only contextual safety belongs on the instrument itself; detailed explanations live in hints/Advanced. */
    fun note(i: GbInstrument, control: Int): String = when {
        i.kind == GbBank.WAVE && control == 0 -> "Shared wave / edit in Wave"
        i.kind == GbBank.WAVE && i.waveVolume == 0 -> "Muted / raise Level to hear"
        i.kind != GbBank.WAVE && control == 2 && GbPulseMacros.shape(i) == null -> "Length will choose Pluck"
        i.kind == GbBank.PULSE && control == 3 -> "Pulse 1 / rising can cut off"
        else -> ""
    }
}
