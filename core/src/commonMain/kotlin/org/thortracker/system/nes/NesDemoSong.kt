package org.thortracker.system.nes

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs

/**
 * The bundled NES demo: "Thor Quest", E minor, Em-C-G-D, speed 6. Intro, theme A and theme B, arranged 0-1-2-1-2
 * and looping to order 1. Shows the envelope instruments (a lead with a release tail and a duty sweep, a PWM pluck,
 * arpeggio chords, a triangle bass, pitched noise drums) and the effects: 0xy chords, 4xy vibrato, Axy fade, Cxx
 * volume, Gxx delay, Qxx/Rxx glides, Sxx cuts, Vxx duty and OFF releases.
 */
internal object NesDemoSong {
    private const val LEAD = 1
    private const val CHORD = 2
    private const val PLUCK = 3
    private const val BASS = 4
    private const val KICK = 5
    private const val SNARE = 6
    private const val HAT = 7
    private const val OPEN_HAT = 8
    private const val COUNTER = 9

    /** Bar chords: Em C G D — pulse chord root, its arpeggio and the triangle bass root (sounds an octave lower). */
    private val CHORD_ROOTS = intArrayOf(n("E-4"), n("C-4"), n("G-4"), n("D-4"))
    private val CHORD_ARPS = intArrayOf(0x37, 0x47, 0x47, 0x47)
    private val BASS_ROOTS = intArrayOf(n("E-3"), n("C-3"), n("G-3"), n("D-3"))

    fun build(): Song {
        val base = NesSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[LEAD - 1] = NesInstrument(
            name = "Lead 50%",
            volume = NesEnvelope(listOf(14, 13, 12, 11, 10, 8, 6, 4, 3, 2, 1, 0), loop = 4, release = 5),
            duty = NesEnvelope(listOf(1, 1, 1, 2)),
        )
        bank[COUNTER - 1] = NesInstrument(
            name = "Counter (no duty env)",
            volume = NesEnvelope(listOf(12, 11, 10, 9, 8, 6, 4, 3, 2, 1, 0), loop = 4, release = 5),
        )
        bank[CHORD - 1] = NesInstrument(name = "Chord 12.5%", volume = NesEnvelope(listOf(9, 9, 8, 8, 7, 7, 6, 6, 5)), duty = NesEnvelope(listOf(0)))
        bank[PLUCK - 1] = NesInstrument(
            name = "PWM pluck",
            volume = NesEnvelope(listOf(13, 12, 10, 9, 8, 7, 6, 5, 4, 3, 3, 2, 2, 1, 1, 0)),
            duty = NesEnvelope(listOf(0, 0, 1, 1, 2, 2, 3, 3, 2, 2, 1, 1), loop = 0),
        )
        bank[BASS - 1] = NesInstrument(name = "Triangle bass")
        bank[KICK - 1] = NesInstrument(
            name = "Kick",
            volume = NesEnvelope(listOf(15, 13, 10, 7, 4, 2, 0)),
            arpeggio = NesEnvelope(listOf(0, -1, -2, -3, -4, -5, -6)),
        )
        bank[SNARE - 1] = NesInstrument(name = "Snare", volume = NesEnvelope(listOf(14, 11, 9, 7, 6, 5, 4, 3, 2, 1, 0)), arpeggio = NesEnvelope(listOf(2, 0)))
        bank[HAT - 1] = NesInstrument(name = "Closed hat", volume = NesEnvelope(listOf(8, 5, 3, 1, 0)))
        bank[OPEN_HAT - 1] = NesInstrument(name = "Open hat", volume = NesEnvelope(listOf(8, 8, 7, 6, 5, 4, 4, 3, 3, 2, 2, 1, 1, 0)))
        return base.copy(
            title = "Thor Quest",
            artist = "Thor Tracker",
            comment = "NES demo: envelope instruments, arpeggio chords, vibrato, glides, cuts, releases. Loops to order 1.",
            ticksPerRow = 6,
            loopOrder = 1,
            patterns = listOf(intro(), themeA(), themeB()),
            orders = listOf(0, 1, 2, 1, 2),
            sound = NesSound(bank),
        )
    }

    private fun intro() = PatternBuilder("Intro").apply {
        for (bar in 0 until 4) {
            val r = bar * 16
            chords(bar)
            if (bar == 0) {
                fx(1, r, NesRules.FX_VOLUME_SLIDE, 0x2F) // the first chord root swells in, the arpeggio follows
                fx(1, r + 6, NesRules.FX_VOLUME, 0x0F)
            }
            if (bar >= 2) bass(bar)
            for (row in listOf(2, 6, 10, 14)) note(3, r + row, HAT_NOTE, HAT)
            if (bar == 3) {
                note(3, r + 8, SNARE_NOTE, SNARE)
                note(3, r + 12, SNARE_NOTE, SNARE); fx(3, r + 12, NesRules.FX_VOLUME, 0x0A)
                note(3, r + 13, SNARE_NOTE, SNARE); fx(3, r + 13, NesRules.FX_VOLUME, 0x0C)
                note(3, r + 14, SNARE_NOTE, SNARE); fx(3, r + 14, NesRules.FX_VOLUME, 0x0F)
                note(3, r + 15, SNARE_NOTE, SNARE)
            }
        }
        // A pickup into theme A: the lead glides up a fourth into its first note.
        note(0, 56, "F#4", LEAD); fx(0, 56, NesRules.FX_SLIDE_UP, 0x05)
        cut(0, 63)
    }.build()

    private fun themeA() = PatternBuilder("Theme A").apply {
        for (bar in 0 until 4) {
            chords(bar)
            bass(bar)
            drums(bar)
        }
        melody(
            listOf(0 to "B-4", 6 to "G-4", 8 to "E-5", 16 to "E-5", 20 to "D-5", 24 to "C-5", 28 to "B-4",
                32 to "D-5", 38 to "B-4", 40 to "G-4", 44 to "A-4", 48 to "A-4", 56 to "D-5"),
        )
        fx(0, 10, NesRules.FX_VIBRATO, 0x63)
        fx(0, 16, NesRules.FX_VIBRATO, 0x00)
        fx(0, 48, NesRules.FX_SLIDE_UP, 0x02) // A-4 rising to B-4 by the D-5
        fx(0, 58, NesRules.FX_VIBRATO, 0x64)
        cut(0, 62) // releases the lead into its tail
        fx(0, 63, NesRules.FX_VIBRATO, 0x00)
    }.build()

    private fun themeB() = PatternBuilder("Theme B").apply {
        for (bar in 0 until 4) {
            val r = bar * 16
            bass(bar)
            drums(bar)
            // Pulse 1: a plucked broken chord every 8th, its duty sweeping (PWM).
            for (step in 0 until 8) {
                val row = r + step * 2
                val offset = intArrayOf(0, 7, 12, 7, 3, 7, 12, 15)[step].let { if (CHORD_ARPS[bar] == 0x47 && it % 12 == 3) it + 1 else it }
                note(0, row, CHORD_ROOTS[bar] + 12 + offset, PLUCK)
            }
        }
        // Pulse 2: a counter-melody; its instrument has no duty envelope, so Vxx sets the duty (25%, then 50%).
        val counter = listOf(0 to "G-5", 8 to "F#5", 12 to "E-5", 16 to "E-5", 24 to "G-5", 32 to "B-5", 40 to "A-5", 44 to "G-5",
            48 to "F#5", 56 to "A-5")
        for ((row, name) in counter) note(1, row, name, COUNTER)
        fx(1, 0, NesRules.FX_DUTY, 0x01)
        fx(1, 16, NesRules.FX_VOLUME, 0x0B)
        fx(1, 24, NesRules.FX_VIBRATO, 0x52)
        fx(1, 32, NesRules.FX_DUTY, 0x02)
        fx(1, 40, NesRules.FX_VOLUME, 0x0F)
        fx(1, 56, NesRules.FX_SLIDE_DOWN, 0x02) // A-5 sinking to G-5
        cut(1, 62)
        fx(1, 63, NesRules.FX_VIBRATO, 0x00)
        // A late ghost snare, delayed into the middle of its row.
        note(3, 47, SNARE_NOTE, SNARE); fx(3, 47, NesRules.FX_NOTE_DELAY, 0x03)
        // The last bass note falls an octave.
        note(2, 60, BASS_ROOTS[3] + 12, BASS); fx(2, 60, NesRules.FX_SLIDE_DOWN, 0x0C)
    }.build()

    private fun PatternBuilder.chords(bar: Int) {
        val r = bar * 16
        note(1, r, CHORD_ROOTS[bar], CHORD)
        fx(1, r, NesRules.FX_ARPEGGIO, CHORD_ARPS[bar])
        note(1, r + 8, CHORD_ROOTS[bar], CHORD)
        fx(1, r + 8, NesRules.FX_ARPEGGIO, CHORD_ARPS[bar])
    }

    /** Root, root, octave, root-fifth pattern; the short notes are cut a few frames in. */
    private fun PatternBuilder.bass(bar: Int) {
        val r = bar * 16
        val root = BASS_ROOTS[bar]
        for ((row, offset) in listOf(0 to 0, 3 to 0, 6 to 12, 8 to 0, 11 to 7, 14 to 12)) {
            note(2, r + row, root + offset, BASS)
            if (row == 3 || row == 6 || row == 11) fx(2, r + row, NesRules.FX_NOTE_CUT, 0x04)
        }
    }

    private fun PatternBuilder.drums(bar: Int) {
        val r = bar * 16
        val hits = listOf(0 to KICK, 2 to HAT, 4 to SNARE, 6 to HAT, 8 to KICK, 10 to KICK, 12 to SNARE, 14 to OPEN_HAT)
        for ((row, instrument) in hits) {
            note(3, r + row, when (instrument) { KICK -> KICK_NOTE; SNARE -> SNARE_NOTE; else -> HAT_NOTE }, instrument)
        }
        fx(3, r + 10, NesRules.FX_VOLUME, 0x09)
        fx(3, r + 12, NesRules.FX_VOLUME, 0x0F)
    }

    private fun PatternBuilder.melody(notes: List<Pair<Int, String>>) {
        for ((row, name) in notes) note(0, row, name, LEAD)
    }

    /** Noise notes pick one of 16 rates (note mod 16, higher = brighter): a low kick, mid snare, top hat. */
    private val KICK_NOTE = n("A-3")
    private val SNARE_NOTE = n("F-4")
    private val HAT_NOTE = n("A#4")

    private fun n(name: String): Int {
        val index = listOf("C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-").indexOf(name.substring(0, 2))
        require(index >= 0) { "bad note $name" }
        return name.substring(2).toInt() * 12 + index
    }

    private class PatternBuilder(private val title: String) {
        private val tracks = List(NesRules.CHANNEL_NAMES.size) { Array(Songs.ROWS) { Cell.BLANK } }

        fun note(channel: Int, row: Int, name: String, instrument: Int) = note(channel, row, n(name), instrument)

        fun note(channel: Int, row: Int, note: Int, instrument: Int) {
            tracks[channel][row] = tracks[channel][row].copy(note = note, instrument = instrument)
        }

        fun cut(channel: Int, row: Int) {
            tracks[channel][row] = Cell(note = Cell.CUT)
        }

        fun fx(channel: Int, row: Int, effect: Int, param: Int) {
            tracks[channel][row] = tracks[channel][row].copy(effect = effect, param = param)
        }

        fun build() = Pattern(title, tracks.map { it.toList() })
    }
}
