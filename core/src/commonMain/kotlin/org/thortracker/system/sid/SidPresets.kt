package org.thortracker.system.sid

import org.thortracker.system.sid.SidTables.GATE
import org.thortracker.system.sid.SidTables.NOISE
import org.thortracker.system.sid.SidTables.PULSE_WAVE
import org.thortracker.system.sid.SidTables.SAW
import org.thortracker.system.sid.SidTables.TRIANGLE

/**
 * Instruments to start from, so a blank slot is musical at once: the classic C64 sounds as GoatTracker musicians
 * build them (envelope, wave program arpeggios and drums with absolute notes, pulse-width sweeps, a filter sweep).
 * A preset with a filter program routes the voice given to [build], since GoatTracker's routing names voices.
 */
object SidPresets {
    class Preset(val name: String, val description: String, val build: (voice: Int) -> SidInstrument)

    private fun prog(vararg rows: SidRow) = SidProgram(rows.toList())

    val ALL: List<Preset> = listOf(
        Preset("Sawtooth lead", "Bright saw with a short decay and delayed vibrato") {
            SidInstrument(name = "Saw lead", attack = 0, decay = 9, sustain = 0xA, release = 9,
                vibrato = SidVibrato(delay = 14, speed = 3, depth = 3, noteIndependent = true),
                wave = prog(SidRow.wave(SAW or GATE)))
        },
        Preset("PWM pad", "Slow pulse whose width sweeps up and down") {
            SidInstrument(name = "PWM pad", attack = 6, decay = 8, sustain = 0xC, release = 0xA,
                wave = prog(SidRow.wave(PULSE_WAVE or GATE)),
                pulse = prog(SidRow.pulseWidth(0x400), SidRow.modulate(0x30, 0x18), SidRow.modulate(0x30, -0x18), SidRow.jump(2)))
        },
        Preset("Filter bass", "Saw bass through a resonant lowpass that closes on each note") { voice ->
            SidInstrument(name = "Filter bass", attack = 0, decay = 9, sustain = 8, release = 3,
                wave = prog(SidRow.wave(SAW or GATE)),
                filter = prog(SidRow.filter(SidTables.LOWPASS, 0xC, 1 shl voice), SidRow.cutoff(0x90), SidRow.modulate(0x20, -3)))
        },
        Preset("Noise snare", "Pulse body and noise burst from absolute notes, any key sounds the same") {
            SidInstrument(name = "Noise snare", attack = 0, decay = 9, sustain = 0, release = 9,
                wave = prog(
                    SidRow.waveAbsolute(NOISE or GATE, 84),
                    SidRow.waveAbsolute(PULSE_WAVE or GATE, 40),
                    SidRow.waveAbsolute(PULSE_WAVE or GATE, 36),
                    SidRow.waveAbsolute(NOISE or GATE, 79),
                    SidRow.waveAbsolute(NOISE, 76),
                ),
                pulse = prog(SidRow.pulseWidth(0x800)))
        },
        Preset("Kick (wave arp)", "Click, then a pulse falling through absolute notes: the C64 drum trick") {
            SidInstrument(name = "Kick", attack = 0, decay = 8, sustain = 0, release = 8,
                wave = prog(
                    SidRow.waveAbsolute(NOISE or GATE, 72),
                    SidRow.waveAbsolute(PULSE_WAVE or GATE, 50),
                    SidRow.waveAbsolute(PULSE_WAVE or GATE, 44),
                    SidRow.waveAbsolute(PULSE_WAVE or GATE, 38),
                    SidRow.waveAbsolute(PULSE_WAVE or GATE, 32),
                    SidRow.waveAbsolute(TRIANGLE or GATE, 26),
                    SidRow.waveAbsolute(TRIANGLE, 22),
                ),
                pulse = prog(SidRow.pulseWidth(0x400)))
        },
        Preset("Hi-hat", "Short high noise") {
            SidInstrument(name = "Hi-hat", attack = 0, decay = 4, sustain = 0, release = 4,
                wave = prog(SidRow.waveAbsolute(NOISE or GATE, 92), SidRow.waveAbsolute(NOISE, 92)))
        },
        Preset("Triangle bass", "Soft, round triangle") {
            SidInstrument(name = "Tri bass", attack = 0, decay = 8, sustain = 0xC, release = 4,
                wave = prog(SidRow.wave(TRIANGLE or GATE)))
        },
        Preset("Arpeggio chord", "Major chord cycled every tick by the wave program") {
            SidInstrument(name = "Arp major", attack = 0, decay = 9, sustain = 0xA, release = 8,
                wave = prog(SidRow.wave(PULSE_WAVE or GATE, 0), SidRow.wave(PULSE_WAVE or GATE, 4), SidRow.wave(PULSE_WAVE or GATE, 7), SidRow.jump(1)),
                pulse = prog(SidRow.pulseWidth(0x600)))
        },
    )

    /** The preset whose sound [ins] is (ignoring name and placement), or -1. */
    fun indexOf(ins: SidInstrument, voice: Int): Int = ALL.indexOfFirst { same(it.build(voice), ins) }

    private fun same(a: SidInstrument, b: SidInstrument): Boolean =
        a.copy(name = b.name, wave = a.wave.copy(at = b.wave.at), pulse = a.pulse.copy(at = b.pulse.at), filter = a.filter.copy(at = b.filter.at)) == b
}
