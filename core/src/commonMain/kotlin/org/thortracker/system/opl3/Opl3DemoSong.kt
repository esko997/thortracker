package org.thortracker.system.opl3

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.opl3.Opl3Rules.FX_BRIGHTNESS
import org.thortracker.system.opl3.Opl3Rules.FX_CHORD
import org.thortracker.system.opl3.Opl3Rules.FX_PAN
import org.thortracker.system.opl3.Opl3Rules.FX_PORTAMENTO
import org.thortracker.system.opl3.Opl3Rules.FX_SHAPE
import org.thortracker.system.opl3.Opl3Rules.FX_SLIDE_UP
import org.thortracker.system.opl3.Opl3Rules.FX_VIBRATO
import org.thortracker.system.opl3.Opl3Rules.FX_VOLUME
import org.thortracker.system.opl3.Opl3Rules.FX_WAVE

/**
 * The bundled OPL3 demo: "Glass Harbour", C major / A minor, Fmaj7-G-Em7-Am, 7 ticks per row (≈129 BPM). Intro, theme
 * A, theme B, theme A again and a tom fill, looping to theme A. FM 1 is a 2-op lead with vibrato and per-note
 * brightness and waveform locks, FM 2 an FM bass, FM 3 a 4-op E-piano arpeggio, FM 4 a 4-op bell counter-melody
 * (theme B), FM 5 a pad playing chord shapes (`Kxx`: maj7, add9, m7, m9) on the chip's spare voices, panned left, FM 6
 * pluck stabs with `Hxy` chords panned right, FM 7-9 pitched FM drums (a sweeping kick, snare, hats, toms in the fill).
 */
internal object Opl3DemoSong {
    private const val SPEED = 7

    private val PRESET_SLOTS = Opl3Presets.ALL.mapIndexed { i, p -> p.name to i + 1 }.toMap()
    private fun slot(name: String) = PRESET_SLOTS[name] ?: throw IllegalStateException("Demo needs preset $name")

    private val LEAD = slot("Lead")
    private val BASS = slot("FM bass")
    private val EPIANO = slot("E-piano")
    private val BELL = slot("Bell")
    private val PAD = slot("Pad")
    private val PLUCK = slot("Pluck")
    private val KICK = slot("Kick")
    private val SNARE = slot("Snare")
    private val TOM = slot("Tom")
    private val HAT = slot("Hi-hat")
    private val OPEN_HAT = slot("Open hat")

    private const val LEAD_CH = 0
    private const val BASS_CH = 1
    private const val EPIANO_CH = 2
    private const val BELL_CH = 3
    private const val PAD_CH = 4
    private const val PLUCK_CH = 5
    private const val KICK_CH = 6
    private const val SNARE_CH = 7
    private const val HAT_CH = 8

    private val BASS_ROOTS = listOf(n("F-2"), n("G-2"), n("E-2"), n("A-2"))
    private val PAD_ROOTS = listOf(n("F-3"), n("G-3"), n("E-3"), n("A-3"))

    /** Default chord table: maj7 = K04, add9 = K0A, m7 = K05, m9 = K0B. */
    private val PAD_SHAPES = listOf(4, 10, 5, 11)

    /** Pluck stab root and `Hxy` per bar: Am over F, G (B+D+G), Em (G+B+D), Am. */
    private val STABS = listOf(n("A-4") to 0x37, n("B-4") to 0x38, n("G-4") to 0x47, n("A-4") to 0x37)
    private val ARPEGGIO = listOf(
        listOf("A-4", "C-5", "E-5", "C-5"), listOf("B-4", "D-5", "G-5", "D-5"),
        listOf("G-4", "B-4", "D-5", "B-4"), listOf("A-4", "C-5", "E-5", "C-5"),
    )

    fun build(): Song {
        val blank = Opl3System.blank()
        return blank.copy(
            title = "Glass Harbour",
            artist = "Thor Tracker",
            comment = "OPL3 demo: 4-op E-piano and bell, chord shapes on spare voices, pitched FM drums, stereo. Loops to order 1.",
            ticksPerRow = SPEED,
            loopOrder = 1,
            patterns = listOf(intro(), themeA(), themeB(), fill()),
            orders = listOf(0, 1, 2, 1, 3),
        )
    }

    private fun intro() = PatternBuilder("Intro").apply {
        for (bar in 0 until 4) {
            bass(bar)
            arpeggio(bar)
            pad(bar)
            val r = bar * 16
            note(KICK_CH, r, n("C-3"), KICK)
            if (bar >= 2) note(KICK_CH, r + 8, n("C-3"), KICK)
            for (row in listOf(4, 8, 12)) note(HAT_CH, r + row, HAT_NOTE, HAT)
        }
        // A rising tom run into theme A.
        for ((row, name) in listOf(56 to "G-3", 58 to "A-3", 60 to "C-4", 62 to "D-4")) note(SNARE_CH, row, n(name), TOM)
    }.build()

    private fun themeA() = PatternBuilder("Theme A").apply {
        for (bar in 0 until 4) { bass(bar); arpeggio(bar); pad(bar); stabs(bar); drums(bar) }
        lead(listOf(0 to "E-5", 6 to "D-5", 8 to "C-5", 12 to "A-4", 16 to "B-4", 20 to "D-5", 24 to "G-5", 28 to "E-5",
            32 to "G-5", 36 to "E-5", 40 to "D-5", 44 to "B-4", 48 to "C-5", 52 to "A-4", 56 to "E-5"))
        // First note: vibrato, a brighter attack and full volume in three columns (all heard from this note).
        fx(LEAD_CH, 0, 1, FX_VIBRATO, 0x23)
        fx(LEAD_CH, 0, 2, FX_BRIGHTNESS, 0x2C)
        fx(LEAD_CH, 0, 3, FX_VOLUME, 0x3F)
        // Waveform locks on two notes: a half-sine modulator (W11) colours them for that note only.
        fx(LEAD_CH, 24, 1, FX_WAVE, 0x11)
        fx(LEAD_CH, 56, 1, FX_WAVE, 0x11)
        fx(LEAD_CH, 56, 2, FX_BRIGHTNESS, 0x30)
        cut(LEAD_CH, 62)
    }.build()

    private fun themeB() = PatternBuilder("Theme B").apply {
        for (bar in 0 until 4) { bass(bar); pad(bar); stabs(bar); drums(bar) }
        lead(listOf(0 to "A-5", 4 to "G-5", 6 to "F-5", 8 to "E-5", 12 to "C-5", 16 to "D-5", 22 to "B-4", 24 to "G-4",
            28 to "D-5", 32 to "E-5", 36 to "G-5", 40 to "B-5", 44 to "A-5", 48 to "A-4"))
        fx(LEAD_CH, 0, 1, FX_VIBRATO, 0x34)
        // A glide: C-5 and E-5 slide in from A-4 without new attacks (3xx), then portamento off with the OFF.
        note(LEAD_CH, 52, n("C-5"), 0)
        fx(LEAD_CH, 52, 0, FX_PORTAMENTO, 0x18)
        note(LEAD_CH, 56, n("E-5"), 0)
        cut(LEAD_CH, 62)
        fx(LEAD_CH, 62, 0, FX_PORTAMENTO, 0x00)
        // The 4-op bell answers on the left.
        for ((row, name) in listOf(2 to "C-6", 10 to "A-5", 18 to "B-5", 26 to "G-5", 34 to "B-5", 42 to "G-5", 50 to "C-6", 58 to "E-6")) {
            note(BELL_CH, row, n(name), BELL)
        }
        fx(BELL_CH, 2, 0, FX_PAN, 0x10)
        // E-piano: held chord tones on the bar instead of the arpeggio.
        for (bar in 0 until 4) note(EPIANO_CH, bar * 16, n(ARPEGGIO[bar][2]), EPIANO)
    }.build()

    private fun fill() = PatternBuilder("Fill").apply {
        for (bar in 0 until 2) { bass(bar); arpeggio(bar); pad(bar); stabs(bar); drums(bar) }
        for (bar in 2 until 4) { bass(bar); pad(bar) }
        // Toms falling across the stereo field, then the kick and an open hat into the loop.
        val toms = listOf(32 to "D-4", 34 to "C-4", 36 to "A-3", 38 to "G-3", 40 to "D-4", 42 to "C-4", 44 to "A-3", 46 to "G-3",
            48 to "E-3", 50 to "E-3", 52 to "D-3", 54 to "D-3", 56 to "C-3", 58 to "C-3", 60 to "A-2", 62 to "A-2")
        for ((row, name) in toms) note(SNARE_CH, row, n(name), TOM)
        for (row in listOf(32, 40, 48, 56)) note(KICK_CH, row, n("C-3"), KICK)
        fx(SNARE_CH, 32, 0, FX_PAN, 0x10)
        fx(SNARE_CH, 40, 0, FX_PAN, 0x01)
        fx(SNARE_CH, 48, 0, FX_PAN, 0x11)
        note(HAT_CH, 60, HAT_NOTE, OPEN_HAT)
        // The lead rises a sixth on a slide and fades.
        note(LEAD_CH, 32, n("E-5"), LEAD)
        fx(LEAD_CH, 32, 0, FX_VIBRATO, 0x45)
        fx(LEAD_CH, 48, 0, FX_SLIDE_UP, 0x02)
        fx(LEAD_CH, 48, 1, Opl3Rules.FX_VOLUME_SLIDE, 0x02)
        cut(LEAD_CH, 63)
        fx(LEAD_CH, 63, 0, FX_SLIDE_UP, 0x00)
        fx(LEAD_CH, 63, 1, FX_VOLUME, 0x3F)
    }.build()

    /** Root, root, octave, root, fifth. */
    private fun PatternBuilder.bass(bar: Int) {
        val r = bar * 16
        for ((row, offset) in listOf(0 to 0, 6 to 0, 8 to 12, 11 to 0, 14 to 7)) note(BASS_CH, r + row, BASS_ROOTS[bar] + offset, BASS)
    }

    private fun PatternBuilder.arpeggio(bar: Int) {
        val r = bar * 16
        ARPEGGIO[bar].forEachIndexed { i, name -> note(EPIANO_CH, r + listOf(0, 3, 6, 10)[i], n(name), EPIANO) }
    }

    /** Hats sit at C-5: ×9 and ×12 operators put their metallic noise around 5-6 kHz. */
    private val HAT_NOTE = n("C-5")

    /** One chord shape per bar on the pad (left), released before the next. */
    private fun PatternBuilder.pad(bar: Int) {
        val r = bar * 16
        note(PAD_CH, r, PAD_ROOTS[bar], PAD)
        fx(PAD_CH, r, 0, FX_SHAPE, PAD_SHAPES[bar])
        if (bar == 0) {
            fx(PAD_CH, r, 1, FX_PAN, 0x10)
            fx(PAD_CH, r, 2, FX_VOLUME, 0x36) // four voices a chord: sits under the lead
        }
        cut(PAD_CH, r + 15)
    }

    /** Off-beat pluck stabs with `Hxy` chords (right). */
    private fun PatternBuilder.stabs(bar: Int) {
        val r = bar * 16
        val (root, chord) = STABS[bar]
        for (row in listOf(4, 12)) {
            note(PLUCK_CH, r + row, root, PLUCK)
            fx(PLUCK_CH, r + row, 0, FX_CHORD, chord)
        }
        if (bar == 0) fx(PLUCK_CH, r + 4, 1, FX_PAN, 0x01)
    }

    private fun PatternBuilder.drums(bar: Int) {
        val r = bar * 16
        note(KICK_CH, r, n("C-3"), KICK)
        note(KICK_CH, r + 8, n("C-3"), KICK)
        if (bar % 2 == 1) note(KICK_CH, r + 10, n("C-3"), KICK)
        note(SNARE_CH, r + 4, n("D-4"), SNARE)
        note(SNARE_CH, r + 12, n("D-4"), SNARE)
        for (row in 0 until 16 step 2) {
            if (row == 14) note(HAT_CH, r + row, HAT_NOTE, OPEN_HAT)
            else {
                note(HAT_CH, r + row, HAT_NOTE, HAT)
                fx(HAT_CH, r + row, 0, FX_VOLUME, if (row % 4 == 0) 0x3F else 0x36)
            }
        }
    }

    private fun PatternBuilder.lead(notes: List<Pair<Int, String>>) {
        for ((row, name) in notes) note(LEAD_CH, row, n(name), LEAD)
    }

    private fun n(name: String): Int {
        val index = listOf("C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-").indexOf(name.substring(0, 2))
        require(index >= 0) { "bad note $name" }
        return name.substring(2).toInt() * 12 + index
    }

    private class PatternBuilder(val title: String) {
        private val tracks = List(Opl3Rules.CHANNEL_NAMES.size) { Array(Songs.ROWS) { Cell.BLANK } }

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
