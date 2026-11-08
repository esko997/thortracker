package org.thortracker.system.sid

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.sid.SidTables.GATE
import org.thortracker.system.sid.SidTables.NOISE
import org.thortracker.system.sid.SidTables.PULSE_WAVE
import org.thortracker.system.sid.SidTables.SAW

/**
 * The bundled C64 demo: "Raster Run", A minor, Am-F-C-G, 6 ticks per row (125 BPM), MOS 8580. Intro, theme A, theme B
 * and an arpeggio section, looping to theme A. Voice 1 is a sawtooth bass under a resonant lowpass whose cutoff sweeps
 * up and down (its first note's filter program; the others leave the filter running), voice 2 a pulse lead with pulse
 * width modulation and delayed vibrato, then pulse arpeggios from wave programs; voice 3 the drums, all wave programs:
 * a noise-click pulse kick falling in pitch, GoatTracker's classic noise/pulse snare and noise hats.
 */
internal object SidDemoSong {
    private const val BASS_SWEEP = 1
    private const val BASS = 2
    private const val LEAD = 3
    private const val KICK = 4
    private const val SNARE = 5
    private const val HAT = 6
    private const val OPEN_HAT = 7
    private const val ARP_MINOR = 8
    private const val ARP_MAJOR = 9

    private val BASS_ROOTS = intArrayOf(n("A-2"), n("F-2"), n("C-3"), n("G-2"))
    private val ARP_ROOTS = intArrayOf(n("A-3"), n("F-3"), n("C-4"), n("G-3"))
    private val ARPS = intArrayOf(ARP_MINOR, ARP_MAJOR, ARP_MAJOR, ARP_MAJOR)
    private val DRUM = n("C-4")

    fun build(): Song {
        val blank = SidSystem.blank()
        val bank = blank.sid.instruments.toMutableList()
        val saw = SidProgram(listOf(SidRow.wave(SAW or GATE)))
        bank[BASS_SWEEP - 1] = SidInstrument(
            name = "Bass sweep", attack = 0, decay = 9, sustain = 8, release = 6, wave = saw,
            filter = SidProgram(listOf(
                SidRow.filter(SidTables.LOWPASS, 0xC, 0x1), // voice 1 through a resonant lowpass
                SidRow.cutoff(0x03),
                SidRow.modulate(0x40, 1), SidRow.modulate(0x40, -1), // 64 ticks up, 64 down: a cycle every 21⅓ rows
                SidRow.jump(3),
            )),
        )
        bank[BASS - 1] = SidInstrument(name = "Bass", attack = 0, decay = 9, sustain = 8, release = 6, wave = saw)
        bank[LEAD - 1] = SidInstrument(
            name = "Lead PWM", attack = 1, decay = 8, sustain = 10, release = 8,
            vibrato = SidVibrato(delay = 18, speed = 3, depth = 3, noteIndependent = true),
            wave = SidProgram(listOf(SidRow.wave(PULSE_WAVE or GATE))),
            pulse = SidProgram(listOf(SidRow.pulseWidth(0x200), SidRow.modulate(0x30, 0x18), SidRow.modulate(0x30, -0x18), SidRow.jump(2))),
        )
        val middle = SidProgram(listOf(SidRow.pulseWidth(0x800)))
        bank[KICK - 1] = SidInstrument(
            name = "Kick", attack = 0, decay = 8, sustain = 0, release = 0, pulse = middle,
            wave = SidProgram(listOf(
                SidRow.waveAbsolute(NOISE or GATE, n("C-5")),
                SidRow.waveAbsolute(PULSE_WAVE or GATE, n("C-3")), SidRow.waveAbsolute(PULSE_WAVE or GATE, n("G-2")),
                SidRow.waveAbsolute(PULSE_WAVE or GATE, n("D#2")), SidRow.waveAbsolute(PULSE_WAVE or GATE, n("C-2")),
                SidRow.waveAbsolute(PULSE_WAVE or GATE, n("A-1")), SidRow.waveAbsolute(PULSE_WAVE or GATE, n("G-1")),
            )),
        )
        bank[SNARE - 1] = SidInstrument(
            name = "Snare", attack = 0, decay = 0, sustain = 13, release = 9, pulse = middle,
            wave = SidProgram(listOf( // GoatTracker's example snare: absolute notes, gate off after three ticks
                SidRow.waveAbsolute(NOISE or GATE, 0x50), SidRow.waveAbsolute(PULSE_WAVE or GATE, 0x2A),
                SidRow.waveAbsolute(PULSE_WAVE or GATE, 0x24), SidRow.waveAbsolute(NOISE, 0x54), SidRow.waveAbsolute(NOISE, 0x51),
            )),
        )
        val hiss = SidProgram(listOf(SidRow.waveAbsolute(NOISE or GATE, n("B-7"))))
        bank[HAT - 1] = SidInstrument(name = "Hat", attack = 0, decay = 3, sustain = 0, release = 0, wave = hiss)
        bank[OPEN_HAT - 1] = SidInstrument(name = "Open hat", attack = 0, decay = 7, sustain = 0, release = 0, wave = hiss)
        for ((slot, third) in listOf(ARP_MINOR to 3, ARP_MAJOR to 4)) {
            bank[slot - 1] = SidInstrument(
                name = if (third == 3) "Arp minor" else "Arp major", attack = 0, decay = 6, sustain = 9, release = 6,
                wave = SidProgram(listOf(
                    SidRow.wave(PULSE_WAVE or GATE), SidRow(0x00, SidTables.relativeNote(third)),
                    SidRow(0x00, SidTables.relativeNote(7)), SidRow(0x00, SidTables.relativeNote(0)), SidRow.jump(2),
                )),
                pulse = SidProgram(listOf(SidRow.pulseWidth(0x400), SidRow.modulate(0x40, 0x08), SidRow.modulate(0x40, -0x08), SidRow.jump(2))),
            )
        }
        return blank.copy(
            title = "Raster Run",
            artist = "Thor Tracker",
            comment = "C64 demo: filter-swept bass, PWM lead with vibrato, wave-program drums and arpeggios. Loops to order 1.",
            ticksPerRow = 6,
            loopOrder = 1,
            patterns = listOf(intro(), themeA(), themeB(), arps()),
            orders = listOf(0, 1, 2, 3),
            sound = SidTables.place(null, blank.sid.copy(instruments = bank)),
        )
    }

    private fun intro() = PatternBuilder("Intro").apply {
        for (bar in 0 until 4) {
            bass(bar)
            val r = bar * 16
            if (bar < 2) {
                note(2, r, DRUM, KICK)
                for (row in listOf(4, 8, 12)) note(2, r + row, DRUM, HAT)
            } else drums(bar)
        }
        // Snare roll into theme A.
        for (row in listOf(58, 60, 61, 62, 63)) note(2, row, DRUM, SNARE)
    }.build()

    private fun themeA() = PatternBuilder("Theme A").apply {
        for (bar in 0 until 4) { bass(bar); drums(bar) }
        lead(listOf(0 to "A-4", 6 to "C-5", 8 to "E-5", 12 to "D-5", 14 to "C-5",
            16 to "C-5", 20 to "A-4", 24 to "F-4", 28 to "A-4",
            32 to "G-4", 36 to "C-5", 40 to "E-5", 44 to "G-5",
            48 to "D-5", 54 to "B-4", 56 to "G-4"))
        cut(1, 62)
    }.build()

    private fun themeB() = PatternBuilder("Theme B").apply {
        for (bar in 0 until 4) { bass(bar); if (bar < 3) drums(bar) }
        val r = 48
        note(2, r, DRUM, KICK); note(2, r + 4, DRUM, SNARE); note(2, r + 8, DRUM, KICK)
        for (row in listOf(10, 12, 13, 14, 15)) note(2, r + row, DRUM, SNARE)
        lead(listOf(0 to "E-5", 4 to "D-5", 6 to "C-5", 8 to "B-4", 10 to "A-4",
            16 to "F-5", 20 to "E-5", 24 to "C-5", 28 to "A-4",
            32 to "G-5", 38 to "E-5", 40 to "C-5", 44 to "E-5",
            48 to "D-5", 56 to "G-4"))
        cut(1, 14)
        fx(1, 56, SidRules.FX_TONE_PORTA, 0x28) // D-5 glides down to G-4 without a new attack
        fx(1, 60, SidRules.FX_VIBRATO, 0x43) // a wider vibrato on the held note
        cut(1, 63)
    }.build()

    private fun arps() = PatternBuilder("Arps").apply {
        for (bar in 0 until 4) {
            bass(bar)
            drums(bar)
            val r = bar * 16
            note(1, r, ARP_ROOTS[bar], ARPS[bar])
            note(1, r + 8, ARP_ROOTS[bar] + 12, ARPS[bar])
        }
        cut(1, 62)
    }.build()

    /** Root, root, octave, root, root, fifth, octave; the pattern's first note starts the filter sweep. */
    private fun PatternBuilder.bass(bar: Int) {
        val r = bar * 16
        for ((row, offset) in listOf(0 to 0, 3 to 0, 6 to 12, 8 to 0, 10 to 0, 12 to 7, 14 to 12)) {
            note(0, r + row, BASS_ROOTS[bar] + offset, if (r + row == 0) BASS_SWEEP else BASS)
        }
    }

    private fun PatternBuilder.drums(bar: Int) {
        val r = bar * 16
        for ((row, instrument) in listOf(0 to KICK, 2 to HAT, 4 to SNARE, 6 to HAT, 8 to KICK, 10 to KICK, 12 to SNARE, 14 to OPEN_HAT)) {
            note(2, r + row, DRUM, instrument)
        }
    }

    private fun PatternBuilder.lead(notes: List<Pair<Int, String>>) {
        for ((row, name) in notes) note(1, row, n(name), LEAD)
    }

    private fun n(name: String): Int {
        val index = listOf("C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-").indexOf(name.substring(0, 2))
        require(index >= 0) { "bad note $name" }
        return name.substring(2).toInt() * 12 + index
    }

    private class PatternBuilder(val title: String) {
        private val tracks = List(SidRules.CHANNEL_NAMES.size) { Array(Songs.ROWS) { Cell.BLANK } }

        fun note(channel: Int, row: Int, note: Int, instrument: Int) {
            tracks[channel][row] = tracks[channel][row].copy(note = note, instrument = instrument)
        }

        fun cut(channel: Int, row: Int) {
            tracks[channel][row] = tracks[channel][row].copy(note = Cell.CUT, instrument = 0)
        }

        fun fx(channel: Int, row: Int, effect: Int, param: Int) {
            tracks[channel][row] = tracks[channel][row].copy(effect = effect, param = param)
        }

        fun build() = Pattern(title, tracks.map { it.toList() })
    }
}
