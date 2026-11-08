package org.thortracker.system.gb

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs

/**
 * The bundled demo: "Thor Groove", A minor, Am-F-C-G, 6 ticks per row. Intro, theme A and theme B,
 * arranged 0-1-2-1-2 and looping back to order 1. Uses arpeggio chords, a sweep zap (Pulse 1 only),
 * vibrato, tone portamento, explicit cuts, Exx cuts, Cxy volume, a 9xx wave switch and two wave timbres.
 */
internal object DemoSong {
    private const val CHORD = 1
    private const val LEAD = 2
    private const val PLUCK = 3
    private const val ZAP = 4
    private const val TRI_BASS = 1
    private const val SAW_BASS = 2
    private const val KICK = 1
    private const val SNARE = 2
    private const val HAT = 3
    private const val OPEN_HAT = 4

    private const val ARP = 0x0
    private const val VIBRATO = 0x4
    private const val TONE_PORTA = 0x3
    private const val TIMBRE = 0x9
    private const val VOLUME = 0xC
    private const val CUT_FX = 0xE

    /** Bar roots, Am F C G: pulse chord root, its arpeggio, wave bass root (wave sounds an octave lower). */
    private val CHORD_ROOTS = intArrayOf(n("A-5"), n("F-5"), n("C-5"), n("G-5"))
    private val CHORD_ARPS = intArrayOf(0x37, 0x47, 0x47, 0x47)
    private val BASS_ROOTS = intArrayOf(n("A-4"), n("F-4"), n("C-5"), n("G-4"))

    fun build(): Song {
        val base = HugeRules.blankSong()
        return base.copy(
            title = "Thor Groove",
            artist = "Thor Tracker",
            comment = "Demo song: arpeggios, sweep, vibrato, portamento, cuts and volume effects. Loops to order 1.",
            ticksPerRow = 6,
            loopOrder = 1,
            patterns = listOf(intro(), themeA(), themeB()),
            orders = listOf(0, 1, 2, 1, 2),
            sound = base.gb.copy(
            pulse = base.gb.pulse.toMutableList().apply {
                this[CHORD - 1] = GbInstrument(name = "Chord 25%", duty = 1, volume = 8)
                this[LEAD - 1] = GbInstrument(name = "Lead 50%", duty = 2, volume = 13, envelopePace = 7)
                this[PLUCK - 1] = GbInstrument(name = "Pluck 12.5%", duty = 0, volume = 14, envelopePace = 1)
                this[ZAP - 1] = GbInstrument(name = "Sweep zap", duty = 2, volume = 15, envelopePace = 2,
                    sweepTime = 2, sweepDirection = 0, sweepShift = 2)
            },
            wave = base.gb.wave.toMutableList().apply {
                this[TRI_BASS - 1] = GbInstrument(name = "Triangle bass", kind = GbBank.WAVE, wave = 5, waveVolume = 1)
                this[SAW_BASS - 1] = GbInstrument(name = "Saw bass", kind = GbBank.WAVE, wave = 4, waveVolume = 2)
            },
            noise = base.gb.noise.toMutableList().apply {
                this[KICK - 1] = GbInstrument(name = "Kick", kind = GbBank.NOISE, volume = 15, envelopePace = 1)
                this[SNARE - 1] = GbInstrument(name = "Snare", kind = GbBank.NOISE, volume = 13, envelopePace = 2)
                this[HAT - 1] = GbInstrument(name = "Closed hat", kind = GbBank.NOISE, volume = 7, envelopePace = 1)
                this[OPEN_HAT - 1] = GbInstrument(name = "Open hat", kind = GbBank.NOISE, volume = 7, envelopePace = 3)
            },
            ),
        )
    }

    private fun intro() = PatternBuilder("Intro").apply {
        for (bar in 0 until 4) {
            chords(bar)
            bass(bar, TRI_BASS)
            val r = bar * 16
            if (bar < 2) {
                for (row in listOf(2, 6, 10, 14)) note(3, r + row, "A#7", HAT)
                note(3, r, "C-5", KICK)
            } else if (bar == 2) {
                drums(bar)
            } else {
                for (row in 0 until 8 step 2) note(3, r + row, if (row % 4 == 0) "C-5" else "A#7", if (row % 4 == 0) KICK else HAT)
                for ((i, row) in (8 until 16).withIndex()) {
                    note(3, r + row, "A-6", SNARE)
                    if (i < 6) fx(3, r + row, VOLUME, 0x06 + i)
                }
            }
        }
        // Pickup into theme A on Pulse 2.
        note(1, 56, "E-6", PLUCK); note(1, 58, "G-6", PLUCK); note(1, 60, "A-6", PLUCK); note(1, 62, "C-7", PLUCK)
        cut(1, 63)
    }.build()

    private fun themeA() = PatternBuilder("Theme A").apply {
        for (bar in 0 until 4) { chords(bar); bass(bar, TRI_BASS); drums(bar) }
        melody(listOf(
            0 to "E-6", 4 to "D-6", 6 to "C-6", 8 to "A-5", 14 to "C-6",
            16 to "A-5", 20 to "C-6", 22 to "F-6", 28 to "E-6", 30 to "C-6",
            32 to "G-6", 36 to "E-6", 38 to "D-6", 40 to "C-6", 46 to "D-6",
            48 to "B-5", 52 to "D-6", 56 to "D-6",
        ))
        vibrato(10..13); vibrato(24..27); vibrato(42..45)
        // Bar 4: glide from D-6 up to E-6 with tone portamento, then an explicit cut.
        note(1, 58, "E-6", 0); for (row in 58..61) fx(1, row, TONE_PORTA, 0x20)
        cut(1, 62)
    }.build()

    private fun themeB() = PatternBuilder("Theme B").apply {
        for (bar in 0 until 4) {
            chords(bar, zapLastBar = bar == 3)
            bass(bar, if (bar < 2) TRI_BASS else SAW_BASS)
            drums(bar)
        }
        // Switch the saw bass to the organ wave for one note, retriggering it (9xx on the wave channel).
        fx(2, 44, TIMBRE, 0x0E)
        melody(listOf(
            0 to "A-6", 2 to "G-6", 4 to "E-6", 8 to "D-6", 10 to "E-6",
            16 to "F-6", 20 to "E-6", 22 to "C-6", 24 to "A-5",
            32 to "G-6", 34 to "E-6", 36 to "G-6", 38 to "C-7", 44 to "B-6",
            48 to "D-7", 54 to "B-6", 56 to "G-6",
        ))
        vibrato(12..15); vibrato(40..43)
        fx(1, 28, VOLUME, 0x08); fx(1, 30, VOLUME, 0x04)
        cut(1, 60)
        // Drum fill over the last bar.
        for (row in 56 until 64) { note(3, row, if (row % 2 == 0) "A-6" else "D-7", SNARE); clearFx(3, row) }
        fx(3, 62, VOLUME, 0x0A); fx(3, 63, VOLUME, 0x0F)
    }.build()

    private fun PatternBuilder.chords(bar: Int, zapLastBar: Boolean = false) {
        val r = bar * 16
        val root = CHORD_ROOTS[bar]
        val arp = CHORD_ARPS[bar]
        for (start in listOf(0, 6)) {
            note(0, r + start, root, CHORD)
            for (row in start until start + 4) fx(0, r + row, ARP, arp)
            cut(0, r + start + 4)
        }
        if (zapLastBar) {
            note(0, r + 12, n("A-6"), ZAP)
            fx(0, r + 15, CUT_FX, 0x02)
        } else {
            note(0, r + 12, root, CHORD)
            for (row in 12 until 16) fx(0, r + row, ARP, arp)
            fx(0, r + 15, CUT_FX, 0x03)
        }
    }

    private fun PatternBuilder.bass(bar: Int, instrument: Int) {
        val r = bar * 16
        val root = BASS_ROOTS[bar]
        val steps = listOf(0 to 0, 3 to 0, 6 to 12, 8 to 0, 11 to 0, 12 to 7, 14 to 12)
        for ((row, offset) in steps) note(2, r + row, root + offset, instrument)
        cut(2, r + 2); cut(2, r + 5); cut(2, r + 10)
    }

    private fun PatternBuilder.drums(bar: Int) {
        val r = bar * 16
        val hits = listOf(0 to KICK, 2 to HAT, 4 to SNARE, 6 to HAT, 8 to KICK, 10 to KICK, 12 to SNARE, 14 to OPEN_HAT)
        for ((row, instrument) in hits) {
            val pitch = when (instrument) { KICK -> "C-5"; SNARE -> "A-6"; else -> "A#7" }
            note(3, r + row, pitch, instrument)
        }
    }

    private fun PatternBuilder.melody(notes: List<Pair<Int, String>>) {
        for ((row, name) in notes) note(1, row, name, LEAD)
    }

    private fun PatternBuilder.vibrato(rows: IntRange) {
        for (row in rows) fx(1, row, VIBRATO, 0x33)
    }

    private fun n(name: String): Int {
        val index = listOf("C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-").indexOf(name.substring(0, 2))
        require(index >= 0) { "bad note $name" }
        // hUGETracker names: octave numbers one above scientific pitch.
        return (name.substring(2).toInt() - GbSystem.nameOctaveOffset) * 12 + index
    }

    private class PatternBuilder(private val title: String) {
        private val tracks = List(4) { Array(Songs.ROWS) { Cell.BLANK } }

        fun note(channel: Int, row: Int, name: String, instrument: Int) {
            note(channel, row, DemoSong.n(name), instrument)
        }

        fun note(channel: Int, row: Int, note: Int, instrument: Int) {
            tracks[channel][row] = tracks[channel][row].copy(note = note, instrument = instrument)
        }

        fun cut(channel: Int, row: Int) {
            tracks[channel][row] = Cell(note = Cell.CUT)
        }

        fun fx(channel: Int, row: Int, effect: Int, param: Int) {
            tracks[channel][row] = tracks[channel][row].copy(effect = effect, param = param)
        }

        fun clearFx(channel: Int, row: Int) {
            fx(channel, row, 0, 0)
        }

        fun build() = Pattern(title, tracks.map { it.toList() })
    }
}
