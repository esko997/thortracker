package org.thortracker.system.sid

/** Musical edits are deliberately smaller than a preset. Unrecognised programs are never approximated. */
internal enum class SidSoundControl(val label: String) {
    VOICE("Voice"), PULSE("Pulse"), CONTOUR("Contour"), TAIL("Tail"), COLOR("Color"),
}

internal object SidSoundMacros {
    private val voices = arrayOf(
        listOf(SidRow.wave(SidTables.TRIANGLE or SidTables.GATE)),
        listOf(SidRow.wave(SidTables.SAW or SidTables.GATE)),
        listOf(SidRow.wave(SidTables.PULSE_WAVE or SidTables.GATE)),
        listOf(SidRow.wave(SidTables.NOISE or SidTables.GATE)),
    )
    private val pulses = arrayOf(
        emptyList(),
        listOf(SidRow.pulseWidth(0x800)),
        listOf(SidRow.pulseWidth(0x200)),
        listOf(SidRow.pulseWidth(0x400), SidRow.modulate(32, 16), SidRow.modulate(32, -16), SidRow.jump(2)),
        listOf(SidRow.pulseWidth(0x400), SidRow.modulate(8, 64), SidRow.modulate(8, -64), SidRow.jump(2)),
    )
    // All three voices: the SID has one filter, not three instrument-local filters.
    private val colors = arrayOf(
        emptyList(),
        listOf(SidRow.filter(SidTables.LOWPASS, 4, 7), SidRow.cutoff(48)),
        listOf(SidRow.filter(SidTables.LOWPASS, 8, 7), SidRow.cutoff(96)),
        listOf(SidRow.filter(SidTables.BANDPASS, 10, 7), SidRow.cutoff(96)),
        listOf(SidRow.filter(SidTables.HIGHPASS, 4, 7), SidRow.cutoff(80)),
    )
    private val contours = arrayOf(intArrayOf(0, 7, 0), intArrayOf(0, 9, 10), intArrayOf(9, 8, 12), intArrayOf(0, 0, 15))
    private val labels = arrayOf(
        arrayOf("Triangle", "Saw", "Pulse", "Noise"),
        arrayOf("Inherit", "Square", "Narrow", "Drift", "Flutter"),
        arrayOf("Pluck", "Held", "Swell", "Organ"),
        arrayOf("6 ms", "24 ms", "48 ms", "72 ms", "114 ms", "168 ms", "204 ms", "240 ms", "300 ms", "750 ms", "1.5 s", "2.4 s", "3 s", "9 s", "15 s", "24 s"),
        arrayOf("Leave", "Warm", "Round", "Nasal", "Air"),
    )
    fun steps(control: SidSoundControl): Int = labels[control.ordinal].size
    fun label(control: SidSoundControl, index: Int): String = labels[control.ordinal][index]

    /** Commands in a custom wave program can supersede another control's owned fields at runtime. */
    fun overridden(control: SidSoundControl, ins: SidInstrument): Boolean {
        for (row in ins.wave.rows) {
            val command = row.left - SidTables.WAVE_COMMAND
            if (when (control) {
                SidSoundControl.VOICE -> false
                SidSoundControl.PULSE -> command == SidRules.FX_PULSE_PROGRAM
                SidSoundControl.CONTOUR -> command == SidRules.FX_ATTACK_DECAY || command == SidRules.FX_SUSTAIN_RELEASE
                SidSoundControl.TAIL -> command == SidRules.FX_SUSTAIN_RELEASE
                SidSoundControl.COLOR -> command in SidRules.FX_FILTER_PROGRAM..SidRules.FX_CUTOFF
            }) return true
        }
        return false
    }

    /** Explicit stop and lowering's implicit stop are equivalent; a match still keeps the original bytes. */
    private fun matches(rows: List<SidRow>, choice: List<SidRow>): Boolean {
        if (rows == choice) return true
        if (choice.isEmpty() || choice.last().isJump) return false
        if (rows.size != choice.size + 1 || rows.last() != SidRow.jump(0)) return false
        for (i in choice.indices) if (rows[i] != choice[i]) return false
        return true
    }

    fun value(control: SidSoundControl, ins: SidInstrument): Int? {
        if (overridden(control, ins)) return null
        val index = when (control) {
            SidSoundControl.VOICE -> voices.indexOfFirst { matches(ins.wave.rows, it) }
            SidSoundControl.PULSE -> pulses.indexOfFirst { matches(ins.pulse.rows, it) }
            SidSoundControl.CONTOUR -> contours.indexOfFirst { ins.attack == it[0] && ins.decay == it[1] && ins.sustain == it[2] }
            SidSoundControl.TAIL -> ins.release
            SidSoundControl.COLOR -> colors.indexOfFirst { matches(ins.filter.rows, it) }
        }
        return index.takeIf { it >= 0 }
    }

    /** No name/restart/vibrato changes or canonicalising a matching choice. Pulse bootstraps only an empty width. */
    fun apply(control: SidSoundControl, ins: SidInstrument, index: Int): SidInstrument {
        require(index in 0 until steps(control))
        if (overridden(control, ins)) return ins
        if (value(control, ins) == index) return ins
        return when (control) {
            // A newly selected Pulse must sound from a blank slot. Existing programs are never replaced here.
            SidSoundControl.VOICE -> ins.copy(
                wave = ins.wave.copy(rows = voices[index]),
                pulse = if (index == 2 && ins.pulse.rows.isEmpty()) ins.pulse.copy(rows = pulses[1]) else ins.pulse,
            )
            SidSoundControl.PULSE -> ins.copy(pulse = ins.pulse.copy(rows = pulses[index]))
            SidSoundControl.CONTOUR -> contours[index].let { ins.copy(attack = it[0], decay = it[1], sustain = it[2]) }
            SidSoundControl.TAIL -> ins.copy(release = index)
            SidSoundControl.COLOR -> ins.copy(filter = ins.filter.copy(rows = colors[index]))
        }
    }
}
