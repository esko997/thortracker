package org.thortracker.system.vsu

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.vsu.VsuRules.FX_ARPEGGIO
import org.thortracker.system.vsu.VsuRules.FX_DELAY
import org.thortracker.system.vsu.VsuRules.FX_LENGTH
import org.thortracker.system.vsu.VsuRules.FX_LEVEL
import org.thortracker.system.vsu.VsuRules.FX_NOISE_TAP
import org.thortracker.system.vsu.VsuRules.FX_PORTAMENTO
import org.thortracker.system.vsu.VsuRules.FX_STEREO
import org.thortracker.system.vsu.VsuRules.FX_SWEEP_MOD
import org.thortracker.system.vsu.VsuRules.FX_VIBRATO
import org.thortracker.system.vsu.VsuRules.FX_WAVE

/**
 * The bundled Virtual Boy demo: "Crimson Stereoscope" by Thor Tracker, D minor, Dm-B♭-F-C, 6 ticks per row (125 BPM at
 * 50 ticks per second). Intro, theme A, theme B, theme A again and a fill, looping to theme A. Wave 1 is the lead (a
 * square lead with vibrato and level locks; in theme B a saw lead gliding with portamento), Wave 2 the triangle bass,
 * Wave 3 pulse plucks arpeggiating the chords on the right (wave locks recolour notes in theme B), Wave 4 a swelling pad
 * with a growing envelope and chord arpeggios on the left (toms panning across the field in the fill), Wave 5 the
 * hardware's tricks (lasers sweeping down, a sweep kick, a modulation-vibrato counter melody, risers sweeping up until
 * the overflow stops them, one with an `Mxx` lock), Noise the drums (kick, snare, hats and metal hats on four taps,
 * `Lxx` lengths and `Nxx` taps).
 */
object VsuDemoSong {
    private const val SPEED = 6

    private val SLOTS = VsuPresets.ALL.mapIndexed { i, p -> p.name to i + 1 }.toMap()
    private fun slot(name: String) = SLOTS[name] ?: throw IllegalStateException("Demo needs preset $name")

    private val SQUARE_LEAD = slot("Square lead")
    private val SAW_LEAD = slot("Saw lead")
    private val BASS = slot("Bass")
    private val PLUCK = slot("Pluck")
    private val PAD = slot("Swell pad")
    private val VIBRATO = slot("Vibrato lead")
    private val LASER = slot("Laser")
    private val SWEEP_KICK = slot("Sweep kick")
    private val RISER = slot("Riser")
    private val TOM = slot("Tom")
    private val KICK = slot("Noise kick")
    private val SNARE = slot("Noise snare")
    private val HAT = slot("Noise hat")
    private val METAL = slot("Metal hat")

    private const val LEAD = 0
    private const val BASS_LANE = 1
    private const val ARP = 2
    private const val PAD_LANE = 3
    private const val W5 = 4
    private const val NOISE = 5

    /** Per bar: the bass root, the pad note and its arpeggio (minor or major triad), the arpeggio's chord tones. */
    private val BASS_ROOTS = listOf(n("D-3"), n("A#2"), n("F-2"), n("C-3"))
    private val PAD_NOTES = listOf(n("D-4"), n("A#3"), n("F-4"), n("C-4"))
    private val PAD_ARPEGGIOS = listOf(0x37, 0x47, 0x47, 0x47)
    private val CHORDS = listOf(
        listOf("D-5", "F-5", "A-5", "D-6"), listOf("A#4", "D-5", "F-5", "A#5"),
        listOf("F-5", "A-5", "C-6", "F-6"), listOf("C-5", "E-5", "G-5", "C-6"),
    )

    fun build(): Song {
        val blank = VsuSystem.blank()
        return blank.copy(
            title = "Crimson Stereoscope",
            artist = "Thor Tracker",
            comment = "Virtual Boy demo: five shared waves, Wave 5 sweeps and modulation, noise taps, stereo levels. Loops to order 1.",
            ticksPerRow = SPEED,
            loopOrder = 1,
            patterns = listOf(intro(), themeA(), themeB(), fill()),
            orders = listOf(0, 1, 2, 1, 3),
        )
    }

    /**
     * The shortest valid song: one order of one row, A-4 on Wave 1 with instrument 01. The native WAV test renders its
     * image (`vsu-shortest.vbimg`) as the smallest case the loader accepts.
     */
    fun shortest(): Song {
        val blank = VsuSystem.blank()
        val tracks = List(VsuRules.CHANNEL_NAMES.size) { lane ->
            List(Songs.ROWS) { r -> if (lane == LEAD && r == 0) Cell(note = n("A-4"), instrument = 1) else Cell.BLANK }
        }
        return blank.copy(
            title = "Shortest",
            artist = "Thor Tracker",
            patterns = listOf(Pattern("Shortest", tracks, length = 1)),
            orders = listOf(0),
            loopOrder = 0,
        )
    }

    private fun intro() = PatternBuilder("Intro").apply {
        for (bar in 0 until 4) {
            pad(bar)
            arpeggio(bar, colour = false)
            if (bar >= 2) bass(bar)
            val r = bar * 16
            if (bar >= 1) for (row in listOf(2, 6, 10, 14)) note(NOISE, r + row, n("C-7"), HAT)
            if (bar >= 1) fx(NOISE, r + 2, 0, FX_LENGTH, 0x03)
            if (bar == 3) { note(NOISE, r, n("C-2"), KICK); note(NOISE, r + 8, n("C-2"), KICK) }
        }
        // Lasers diving across the bars, then a riser into theme A.
        for (row in listOf(12, 28, 44)) note(W5, row, n("C-7"), LASER)
        fx(W5, 12, 0, FX_STEREO, 0xAF)
        note(W5, 52, n("C-3"), RISER)
    }.build()

    private fun themeA() = PatternBuilder("Theme A").apply {
        for (bar in 0 until 4) { bass(bar); arpeggio(bar, colour = false); pad(bar); drums(bar, metal = false) }
        lead(SQUARE_LEAD, listOf(0 to "A-4", 4 to "D-5", 6 to "E-5", 8 to "F-5", 12 to "E-5", 14 to "D-5",
            16 to "F-5", 20 to "D-5", 22 to "C-5", 24 to "A#4", 28 to "C-5",
            32 to "A-4", 36 to "C-5", 38 to "D-5", 40 to "F-5", 44 to "A-5", 46 to "G-5",
            48 to "E-5", 52 to "G-5", 56 to "E-5", 58 to "D-5", 60 to "C-5"))
        // Slightly left, vibrato from the first note on; passing notes softer (level locks).
        fx(LEAD, 0, 0, FX_STEREO, 0xFC)
        fx(LEAD, 0, 1, FX_VIBRATO, 0x23)
        for (row in listOf(6, 22, 38, 58)) fx(LEAD, row, 0, FX_LEVEL, 0x0B)
        cut(LEAD, 62)
        // Wave 5: a sweep kick on the downbeats, panned centre.
        for (bar in 0 until 4) note(W5, bar * 16, n("C-4"), SWEEP_KICK)
        fx(W5, 0, 0, FX_STEREO, 0xFF)
    }.build()

    private fun themeB() = PatternBuilder("Theme B").apply {
        for (bar in 0 until 4) { bass(bar); arpeggio(bar, colour = true); pad(bar); drums(bar, metal = true) }
        // Saw lead: long notes, then a glide (3xx: no new attack) up to C-6 and an OFF that ends portamento.
        lead(SAW_LEAD, listOf(0 to "D-5", 8 to "A-5", 12 to "G-5", 16 to "F-5", 24 to "D-5", 28 to "F-5",
            32 to "C-6", 40 to "A-5", 44 to "F-5", 48 to "G-5"))
        fx(LEAD, 0, 0, FX_VIBRATO, 0x34)
        note(LEAD, 52, n("A-5"), 0)
        fx(LEAD, 52, 0, FX_PORTAMENTO, 0x20)
        note(LEAD, 56, n("C-6"), 0)
        cut(LEAD, 62)
        fx(LEAD, 62, 0, FX_PORTAMENTO, 0x00)
        // Wave 5: the modulation-vibrato counter melody, a little right.
        for ((row, name) in listOf(4 to "F-4", 10 to "A-4", 20 to "F-4", 26 to "D-4", 36 to "A-4", 42 to "C-5", 52 to "E-4", 58 to "G-4")) {
            note(W5, row, n(name), VIBRATO)
        }
        fx(W5, 4, 0, FX_STEREO, 0x9F)
        cut(W5, 63)
    }.build()

    private fun fill() = PatternBuilder("Fill").apply {
        for (bar in 0 until 2) { bass(bar); arpeggio(bar, colour = false); pad(bar); drums(bar, metal = true) }
        for (bar in 2 until 4) bass(bar)
        // Staccato lead (length locks), then silence for the toms.
        lead(SQUARE_LEAD, listOf(0 to "D-5", 4 to "F-5", 8 to "A-5", 12 to "D-6", 16 to "C-6", 20 to "A-5", 24 to "F-5", 28 to "E-5"))
        for (row in listOf(0, 4, 8, 12)) fx(LEAD, row, 0, FX_LENGTH, 0x14)
        cut(LEAD, 31)
        // Toms on Wave 4 panning across the stereo field.
        val toms = listOf(32 to "D-4", 36 to "C-4", 40 to "A#3", 44 to "A-3", 48 to "F-3", 52 to "E-3", 56 to "D-3", 58 to "D-3")
        for ((i, pair) in toms.withIndex()) {
            note(PAD_LANE, pair.first, n(pair.second), TOM)
            fx(PAD_LANE, pair.first, 0, FX_STEREO, listOf(0xF3, 0xD6, 0xA9, 0x6C, 0x3F, 0x6C, 0xA9, 0xFF)[i])
        }
        cut(PAD_LANE, 62)
        // Wave 5 risers: the preset's slow sweep up, then a faster one from an Mxx lock (slow clock, interval 4, up, shift 4).
        note(W5, 32, n("C-3"), RISER)
        note(W5, 48, n("C-3"), RISER)
        fx(W5, 48, 0, FX_SWEEP_MOD, 0xCC)
        cut(W5, 63)
        // Noise: kicks under the toms, metal hats on another tap, then a delayed snare flam into the loop.
        for (row in listOf(32, 40, 48, 56)) note(NOISE, row, n("C-2"), KICK)
        for (row in listOf(36, 44, 52)) {
            note(NOISE, row, n("G-6"), METAL)
            fx(NOISE, row, 0, FX_NOISE_TAP, 0x05)
        }
        note(NOISE, 60, n("C-6"), SNARE)
        note(NOISE, 62, n("C-6"), SNARE)
        fx(NOISE, 62, 0, FX_DELAY, 0x03)
        fx(NOISE, 62, 1, FX_NOISE_TAP, 0x02)
    }.build()

    /** Root, root, octave, root, fifth (the fifth of F-2 stays in range: C-3). */
    private fun PatternBuilder.bass(bar: Int) {
        val r = bar * 16
        for ((row, offset) in listOf(0 to 0, 6 to 0, 8 to 12, 11 to 0, 14 to 7)) note(BASS_LANE, r + row, BASS_ROOTS[bar] + offset, BASS)
    }

    /** Plucks on the chord tones (right); [colour]: wave locks give the first and fifth notes the triangle and the saw. */
    private fun PatternBuilder.arpeggio(bar: Int, colour: Boolean) {
        val r = bar * 16
        val tones = CHORDS[bar]
        listOf(0, 1, 2, 3, 2, 1, 0, 1).forEachIndexed { i, t -> note(ARP, r + 2 * i, n(tones[t]), PLUCK) }
        if (bar == 0) fx(ARP, r, 0, FX_STEREO, 0x6F)
        if (colour) {
            fx(ARP, r, 1, FX_WAVE, 0x02)
            fx(ARP, r + 8, 0, FX_WAVE, 0x03)
        }
    }

    /** One swelling note per bar arpeggiating its triad (left), stopped before the next bar's. */
    private fun PatternBuilder.pad(bar: Int) {
        val r = bar * 16
        note(PAD_LANE, r, PAD_NOTES[bar], PAD)
        fx(PAD_LANE, r, 0, FX_ARPEGGIO, PAD_ARPEGGIOS[bar])
        if (bar == 0) fx(PAD_LANE, r, 1, FX_STEREO, 0xF6)
        cut(PAD_LANE, r + 15)
    }

    /** Kick, snare and hats; [metal]: the last hat of each bar is the metal hat with a tap lock. */
    private fun PatternBuilder.drums(bar: Int, metal: Boolean) {
        val r = bar * 16
        note(NOISE, r, n("C-2"), KICK)
        note(NOISE, r + 8, n("C-2"), KICK)
        if (bar % 2 == 1) note(NOISE, r + 10, n("C-2"), KICK) else note(NOISE, r + 10, n("C-7"), HAT)
        note(NOISE, r + 4, n("C-6"), SNARE)
        note(NOISE, r + 12, n("C-6"), SNARE)
        note(NOISE, r + 2, n("C-7"), HAT)
        note(NOISE, r + 6, n("C-7"), HAT)
        fx(NOISE, r + 6, 0, FX_LENGTH, 0x02)
        if (metal) {
            note(NOISE, r + 14, n("G-6"), METAL)
            fx(NOISE, r + 14, 0, FX_NOISE_TAP, 0x03)
        } else {
            note(NOISE, r + 14, n("C-7"), HAT)
            fx(NOISE, r + 14, 0, FX_LENGTH, if (bar == 3) 0x0C else 0x04)
        }
    }

    private fun PatternBuilder.lead(instrument: Int, notes: List<Pair<Int, String>>) {
        for ((row, name) in notes) note(LEAD, row, n(name), instrument)
    }

    private fun n(name: String): Int {
        val index = listOf("C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-").indexOf(name.substring(0, 2))
        require(index >= 0) { "bad note $name" }
        return name.substring(2).toInt() * 12 + index
    }

    private class PatternBuilder(val title: String) {
        private val tracks = List(VsuRules.CHANNEL_NAMES.size) { Array(Songs.ROWS) { Cell.BLANK } }

        fun note(channel: Int, row: Int, note: Int, instrument: Int) {
            tracks[channel][row] = tracks[channel][row].copy(note = note, instrument = instrument)
        }

        fun cut(channel: Int, row: Int) {
            tracks[channel][row] = tracks[channel][row].copy(note = Cell.CUT, instrument = 0)
        }

        fun fx(channel: Int, row: Int, column: Int, effect: Int, param: Int) {
            tracks[channel][row] = tracks[channel][row].withEffect(column, effect, param)
        }

        fun build() = Pattern(title, tracks.map { it.toList() })
    }
}
