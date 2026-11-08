package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.sid.SidChip
import org.thortracker.system.sid.SidInstrument
import org.thortracker.system.sid.SidProgram
import org.thortracker.system.sid.SidRestart
import org.thortracker.system.sid.SidRow
import org.thortracker.system.sid.SidRules
import org.thortracker.system.sid.SidSound
import org.thortracker.system.sid.SidSystem
import org.thortracker.system.sid.SidTables
import org.thortracker.system.sid.SidTables.GATE
import org.thortracker.system.sid.SidTables.NOISE
import org.thortracker.system.sid.SidTables.PULSE_WAVE
import org.thortracker.system.sid.SidTables.SAW
import org.thortracker.system.sid.SidTables.TRIANGLE
import org.thortracker.system.sid.SidVibrato
import org.thortracker.system.sid.sid

/**
 * Songs for [SidGoldenTest], built with Thor Tracker's model: the demo; an effects song (every command 1-F with its
 * parameter forms, key off/on, instrument-only rows, legato and gate-off instruments with first waves 00/FE/FF,
 * wave programs with delays, silent waveforms, keep-frequency, relative and absolute notes and every command a wave
 * program may run, pulse and filter programs with loops, instrument vibrato of both kinds, a start tempo other than
 * GoatTracker's default, funktempo); and a multispeed (×2) song with its own hard-restart envelope.
 */
object SidGoldenSongs {
    val ALL: Map<String, () -> Song> = linkedMapOf(
        "demo" to { SidSystem.demo() },
        "effects" to ::effects,
        "multispeed" to ::multispeed,
    )

    /** gt2reloc options each song needs beyond `-I0 -J1` (multispeed, hard-restart envelope), as the fixture script reads them. */
    fun packerOptions(song: Song): String =
        "-S${song.sid.multispeed} -A${"%02X%02X".format(song.sid.hardRestartAttackDecay, song.sid.hardRestartSustainRelease)}"

    private fun effects(): Song {
        val bank = SidSound.blank().instruments.toMutableList()
        bank[0] = SidInstrument(
            name = "Lead", attack = 0, decay = 8, sustain = 10, release = 9,
            vibrato = SidVibrato(delay = 4, speed = 2, depth = 0x30),
            wave = SidProgram(listOf(SidRow.wave(PULSE_WAVE or GATE))),
            pulse = SidProgram(listOf(SidRow.pulseWidth(0x180), SidRow.modulate(0x20, 0x20), SidRow.modulate(0x20, -0x20), SidRow.jump(2))),
        )
        bank[1] = SidInstrument(
            name = "Bass", attack = 0, decay = 6, sustain = 12, release = 4,
            wave = SidProgram(listOf(SidRow.waveAbsolute(NOISE or GATE, 0x4F), SidRow.wave(SAW or GATE))),
            filter = SidProgram(listOf(SidRow.filter(SidTables.BANDPASS, 8, 2), SidRow.cutoff(0x40), SidRow.modulate(0x10, 2),
                SidRow.modulate(0x10, -2), SidRow.jump(3))),
        )
        bank[2] = SidInstrument(
            name = "Legato pad", attack = 5, decay = 10, sustain = 9, release = 10, restart = SidRestart.LEGATO, firstWave = 0x00,
            vibrato = SidVibrato(delay = 6, speed = 3, depth = 3, noteIndependent = true),
            wave = SidProgram(listOf(SidRow.wave(TRIANGLE or GATE), SidRow.delay(3), SidRow.wave(TRIANGLE or GATE, 12),
                SidRow(0xE5, SidTables.KEEP_FREQUENCY), SidRow(PULSE_WAVE or GATE, SidTables.KEEP_FREQUENCY), SidRow.jump(3))),
            pulse = SidProgram(listOf(SidRow.pulseWidth(0x600))),
        )
        bank[3] = SidInstrument(
            name = "Pluck no HR", attack = 0, decay = 9, sustain = 0, release = 0, restart = SidRestart.GATE_OFF, firstWave = 0xFF,
            wave = SidProgram(listOf(SidRow.wave(SAW or GATE, 12), SidRow.wave(SAW or GATE, -12), SidRow.wave(SAW or GATE))),
        )
        bank[4] = SidInstrument(
            name = "Commands", attack = 0, decay = 9, sustain = 9, release = 8, firstWave = 0xFE,
            wave = SidProgram(listOf(
                SidRow.wave(SAW or GATE), SidRow.command(SidRules.FX_PORTA_UP, 0x10), SidRow.delay(2),
                SidRow.command(SidRules.FX_PORTA_DOWN, 0x08), SidRow.command(SidRules.FX_TONE_PORTA, 0x20),
                SidRow.command(SidRules.FX_VIBRATO, 0x23), SidRow.command(SidRules.FX_ATTACK_DECAY, 0x08),
                SidRow.command(SidRules.FX_SUSTAIN_RELEASE, 0xA8), SidRow.command(SidRules.FX_WAVEFORM, 0x41),
                SidRow.command(SidRules.FX_PULSE_PROGRAM, 6), SidRow.command(SidRules.FX_FILTER_PROGRAM, 2),
                SidRow.command(SidRules.FX_FILTER_CONTROL, 0xF4), SidRow.command(SidRules.FX_CUTOFF, 0x30),
                SidRow.command(SidRules.FX_VOLUME, 0x0E), SidRow.delay(4), SidRow.jump(2),
            )),
        )
        bank[5] = SidInstrument(
            name = "PWM", attack = 1, decay = 9, sustain = 8, release = 8,
            wave = SidProgram(listOf(SidRow.wave(PULSE_WAVE or GATE))),
            pulse = SidProgram(listOf(SidRow.pulseWidth(0x400), SidRow.modulate(0x10, 0x40), SidRow.jump(0))),
        )
        bank[6] = SidInstrument(
            name = "Snare", attack = 0, decay = 0, sustain = 13, release = 9,
            wave = SidProgram(listOf(SidRow.waveAbsolute(NOISE or GATE, 0x50), SidRow.waveAbsolute(PULSE_WAVE or GATE, 0x2A),
                SidRow.waveAbsolute(NOISE, 0x54))),
            pulse = SidProgram(listOf(SidRow.pulseWidth(0x800))),
        )
        bank[7] = SidInstrument(
            name = "Arp", attack = 0, decay = 7, sustain = 8, release = 6,
            wave = SidProgram(listOf(SidRow.wave(PULSE_WAVE or GATE), SidRow(0, SidTables.relativeNote(4)), SidRow(0, SidTables.relativeNote(7)),
                SidRow(0, SidTables.relativeNote(0)), SidRow.jump(2))),
            pulse = SidProgram(listOf(SidRow.pulseWidth(0x800))),
        )
        val a = Builder("Effects A", 32).apply {
            note(0, 0, n("C-4"), 1); fx(0, 0, SidRules.FX_VOLUME, 0x0F)
            note(0, 4, n("E-4"), 0); fx(0, 4, SidRules.FX_PORTA_UP, 0x08)
            note(0, 8, n("G-4"), 0); fx(0, 8, SidRules.FX_PORTA_DOWN, 0x04)
            fx(0, 10, SidRules.FX_PORTA_UP, 0x00)
            note(0, 12, n("C-5"), 0); fx(0, 12, SidRules.FX_TONE_PORTA, 0x10)
            note(0, 16, n("G-4"), 0); fx(0, 16, SidRules.FX_TONE_PORTA, 0x00)
            note(0, 18, n("A-4"), 0); fx(0, 18, SidRules.FX_VIBRATO, 0x35)
            fx(0, 22, SidRules.FX_VIBRATO, 0x00)
            off(0, 24)
            on(0, 26)
            note(0, 28, n("D-5"), 0); fx(0, 28, SidRules.FX_ATTACK_DECAY, 0x2A)
            fx(0, 30, SidRules.FX_SUSTAIN_RELEASE, 0x86)

            note(1, 0, n("C-2"), 2)
            note(1, 4, n("C-2"), 0); fx(1, 4, SidRules.FX_FILTER_CONTROL, 0xC2)
            fx(1, 6, SidRules.FX_CUTOFF, 0x60)
            note(1, 8, n("G-1"), 0); fx(1, 8, SidRules.FX_FILTER_PROGRAM, 2)
            note(1, 12, n("A#1"), 4)
            note(1, 16, n("F-2"), 2); fx(1, 16, SidRules.FX_FILTER_PROGRAM, 0x00)
            fx(1, 20, SidRules.FX_FILTER_CONTROL, 0x00)
            note(1, 24, n("C-2"), 0); fx(1, 24, SidRules.FX_WAVEFORM, 0x21)
            instrument(1, 28, 3)
            note(1, 30, n("C-3"), 0)

            note(2, 0, n("C-4"), 7)
            note(2, 4, n("C-4"), 8); fx(2, 4, SidRules.FX_FUNKTEMPO, 0x53)
            note(2, 8, n("C-4"), 7)
            note(2, 12, n("E-3"), 6); fx(2, 12, SidRules.FX_PULSE_PROGRAM, 1)
            fx(2, 14, SidRules.FX_PULSE_PROGRAM, 0)
            note(2, 16, n("C-4"), 7); fx(2, 16, SidRules.FX_TEMPO, 0x04)
            note(2, 20, n("C-3"), 8); fx(2, 20, SidRules.FX_WAVE_PROGRAM, 4)
            fx(2, 22, SidRules.FX_WAVE_PROGRAM, 0)
            note(2, 24, n("C-4"), 7); fx(2, 24, SidRules.FX_TEMPO, 0x00)
            note(2, 28, n("C-4"), 7); fx(2, 28, SidRules.FX_TEMPO, 0x05)
        }.build()
        val b = Builder("Effects B", 16).apply {
            note(0, 0, n("E-4"), 5)
            note(0, 8, n("A-4"), 0)
            off(0, 14)
            note(1, 0, n("A-2"), 3)
            note(1, 4, n("B-2"), 0)
            note(1, 8, n("C-3"), 0)
            off(1, 12)
            on(1, 14)
            note(2, 0, n("C-4"), 7); fx(2, 0, SidRules.FX_VOLUME, 0x0C)
            note(2, 8, n("D-3"), 6)
            note(2, 12, n("C-4"), 7); fx(2, 12, SidRules.FX_VOLUME, 0x0F)
        }.build()
        val base = SidSystem.blank()
        return base.copy(
            title = "SID effects", artist = "Thor Tracker", comment = "golden: every command",
            ticksPerRow = 5, loopOrder = 1, patterns = listOf(a, b), orders = listOf(0, 1, 0),
            sound = SidTables.place(null, base.sid.copy(instruments = bank, chip = SidChip.MOS6581)),
        )
    }

    private fun multispeed(): Song {
        val bank = SidSound.blank().instruments.toMutableList()
        bank[0] = SidInstrument(
            name = "Lead x2", attack = 0, decay = 9, sustain = 10, release = 9, gateTimer = 4,
            vibrato = SidVibrato(delay = 10, speed = 4, depth = 0x20),
            wave = SidProgram(listOf(SidRow.wave(PULSE_WAVE or GATE))),
            pulse = SidProgram(listOf(SidRow.pulseWidth(0x300), SidRow.modulate(0x40, 0x0C), SidRow.modulate(0x40, -0x0C), SidRow.jump(2))),
        )
        bank[1] = SidInstrument(
            name = "Bass x2", attack = 0, decay = 9, sustain = 6, release = 5, gateTimer = 4, restart = SidRestart.GATE_OFF,
            wave = SidProgram(listOf(SidRow.wave(SAW or GATE))),
            filter = SidProgram(listOf(SidRow.filter(SidTables.LOWPASS, 0xA, 2), SidRow.cutoff(0x20), SidRow.modulate(0x40, 1), SidRow.jump(0))),
        )
        bank[2] = SidInstrument(
            name = "Hat x2", attack = 0, decay = 4, sustain = 0, release = 0, gateTimer = 4,
            wave = SidProgram(listOf(SidRow.waveAbsolute(NOISE or GATE, 0x5E), SidRow.delay(4), SidRow.waveAbsolute(NOISE, 0x5A))),
        )
        val p = Builder("Multispeed", 32).apply {
            for ((row, name) in listOf(0 to "E-5", 6 to "D-5", 8 to "B-4", 12 to "G-4", 16 to "A-4", 24 to "C-5")) note(0, row, n(name), 1)
            fx(0, 16, SidRules.FX_VIBRATO, 0x46)
            off(0, 30)
            for (row in 0 until 32 step 4) note(1, row, n(if (row < 16) "E-2" else "A-1"), 2)
            fx(1, 8, SidRules.FX_PORTA_DOWN, 0x02)
            for (row in 0 until 32 step 2) note(2, row, n("C-4"), 3)
            fx(2, 24, SidRules.FX_TEMPO, 0x10)
        }.build()
        val base = SidSystem.blank()
        return base.copy(
            title = "SID multispeed", artist = "Thor Tracker", ticksPerRow = 12, loopOrder = 0,
            patterns = listOf(p), orders = listOf(0),
            sound = SidTables.place(null, base.sid.copy(instruments = bank, multispeed = 2, hardRestartAttackDecay = 0x0F, hardRestartSustainRelease = 0x01)),
        )
    }

    private fun n(name: String): Int {
        val index = listOf("C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-").indexOf(name.substring(0, 2))
        return name.substring(2).toInt() * 12 + index
    }

    private class Builder(val title: String, val length: Int) {
        private val tracks = List(3) { Array(Songs.ROWS) { Cell.BLANK } }
        fun note(c: Int, r: Int, note: Int, instrument: Int) { tracks[c][r] = tracks[c][r].copy(note = note, instrument = instrument) }
        fun instrument(c: Int, r: Int, instrument: Int) { tracks[c][r] = tracks[c][r].copy(instrument = instrument) }
        fun off(c: Int, r: Int) { tracks[c][r] = tracks[c][r].copy(note = Cell.CUT) }
        fun on(c: Int, r: Int) { tracks[c][r] = tracks[c][r].copy(note = SidRules.KEY_ON) }
        fun fx(c: Int, r: Int, effect: Int, param: Int) { tracks[c][r] = tracks[c][r].copy(effect = effect, param = param) }
        fun build() = Pattern(title, tracks.map { it.toList() }, length = length)
    }
}
