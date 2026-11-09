package org.thortracker.system.tia

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.tia.TiaRules.FX_ARPEGGIO
import org.thortracker.system.tia.TiaRules.FX_PORTAMENTO
import org.thortracker.system.tia.TiaRules.FX_SLIDE_DOWN
import org.thortracker.system.tia.TiaRules.FX_SLIDE_UP
import org.thortracker.system.tia.TiaRules.FX_VIBRATO
import org.thortracker.system.tia.TiaRules.FX_WAVEFORM

/**
 * The bundled Atari 2600 demo: "Cartridge Dawn", an original title-screen tune in D Dorian, i-VII-IV-i (Dm-C-G-Dm)
 * with an F-C bridge, written for what the TIA plays in tune. NTSC at 7 frames per row (about 128 BPM); the PAL demo is
 * the same composition at 6 frames per row (about 125 BPM, every pitch about 16 cents lower).
 *
 * - TIA 1: the lead on the div 6 square (AUDC C), using only the notes whose divider lies within 20 cents of them on
 *   NTSC (A-3, C-4, D-4, E-4, F-4, A-4, C-5, D-5, E-5: D Dorian without its G and B, which fall between dividers);
 *   in the intro a div 2 sparkle arpeggio (D-5, G-5, A-5: all within 17 cents on div 2). Theme A' ends a phrase with a
 *   slide up (`104`) that jumps from E-5's divider to the next one, and a portamento (`310`) that walks down D-5's,
 *   C-5's and B-4's dividers to A-4: the steps are the chip's. The bridge's timbre switch: `W01` turns the held F-4, C-4
 *   and C-5 into the 4-bit poly buzz at exactly the same pitch (6 × (AUDF + 1) on div 6 = 15 × (AUDF + 1) on the poly),
 *   then F-5, which only the buzz has in tune.
 * - TIA 2: the bass on the div 31 tone (AUDC 6: C-1, D-1, F-1, G-1, D-2, all within 30 cents) sharing the channel with
 *   the drums, as 2600 music does: raw white-noise snares and hats (AUDC 8) and a kick that starts as noise and falls
 *   on div 93 (a waveform envelope, raw dividers stepped by its arpeggio envelope).
 *
 * Orders: intro, theme A, theme A', bridge, theme A, theme A', looping to theme A. The demo's own sounds are slots
 * 01-07; the presets ([TiaPresets.ALL]) follow them.
 */
object TiaDemoSong {
    const val TITLE = "Cartridge Dawn"

    /** Frames per row: NTSC 7 (59.92 Hz: 128 BPM at 4 rows per beat), PAL 6 (49.86 Hz: 125 BPM). */
    fun speed(region: TiaRegion): Int = if (region == TiaRegion.NTSC) 7 else 6

    private const val LEAD_CH = 0
    private const val LOW_CH = 1

    private const val LEAD = 1
    private const val SPARKLE = 2
    private const val BASS = 3
    private const val KICK = 4
    private const val SNARE = 5
    private const val HAT = 6
    private const val FILL = 7

    /** AUDC 1, the 4-bit poly buzz. */
    private const val W_BUZZ = 0x1

    /** Raw notes pick the divider: AUDF = 31 − (note mod 32). */
    private val SNARE_NOTE = n("A-4") // 57 mod 32 = 25: AUDF 6
    private val HAT_NOTE = n("D#5") // 63 mod 32 = 31: AUDF 0, the brightest noise
    private val KICK_NOTE = n("G-4") // 55 mod 32 = 23: AUDF 8, then the arpeggio envelope lowers it
    private val FILL_NOTE = n("C-5") // 60 mod 32 = 28: AUDF 3

    /** The bass roots of theme A (Dm, C, G, Dm) and the bridge (F, C, F, G). */
    private val ROOTS_A = listOf(n("D-1"), n("C-1"), n("G-1"), n("D-1"))
    private val ROOTS_B = listOf(n("F-1"), n("C-1"), n("F-1"), n("G-1"))

    fun song(region: TiaRegion): Song {
        val own = listOf(lead(), sparkle(), bass(), kick(), snare(), hat(), fill())
        val presets = TiaPresets.ALL.map { it.instrument }
        val instruments = (own + presets).take(TiaSystem.SLOTS).let { it + List(TiaSystem.SLOTS - it.size) { null } }
        return region.system.blank().copy(
            title = TITLE,
            artist = "Thor Tracker",
            comment = "Atari 2600 ${region.label} demo: a div 6 lead on in-tune notes, a div 2 sparkle, div 31 bass sharing " +
                "TIA 2 with raw noise drums, stepping slides and portamento, and a W01 timbre switch. Loops to order 1.",
            ticksPerRow = speed(region),
            loopOrder = 1,
            patterns = listOf(intro(), themeA(), themeA2(), bridge()),
            orders = listOf(0, 1, 2, 3, 1, 2),
            sound = TiaSound(region, instruments),
        )
    }

    /**
     * The shortest valid Atari 2600 song (the WAV export test's `tia-shortest.tiaimg`): one order of a one-row pattern
     * at speed 6 on NTSC, looping to order 0, holding A-4 on TIA 1 with slot 01, a div 6 square (AUDC C) without
     * envelopes: AUDF 11, 436.10 Hz, at full volume on every pass (as `tia_song.short_song()`).
     */
    fun shortest(): Song {
        val base = TiaNtscSystem.blank()
        val held = Cell(note = n("A-4"), instrument = 1)
        val tracks = List(TiaRules.CHANNEL_NAMES.size) { ch -> List(Songs.ROWS) { row -> if (ch == LEAD_CH && row == 0) held else Cell.BLANK } }
        return base.copy(
            title = "Atari 2600 shortest",
            ticksPerRow = 6,
            loopOrder = 0,
            patterns = listOf(Pattern("Pattern 00", tracks, length = 1)),
            orders = listOf(0),
            sound = TiaSound(TiaRegion.NTSC, List(TiaSystem.SLOTS) { if (it == 0) TiaInstrument("Square", waveform = 0xC) else null }),
        )
    }

    // ---- sounds -----------------------------------------------------------------------------------------------------

    private fun env(vararg values: Int, loop: Int = -1, release: Int = -1) = TiaEnvelope(values.toList(), loop, release)

    /** The div 6 square lead: a short attack settling to 10, fading after OFF. */
    private fun lead() = TiaInstrument("Dawn lead", 0xC, volume = env(14, 13, 12, 11, 10, 10, 7, 4, 2, 0, release = 6))

    /** A bright div 2 square that dies away, for arpeggios. */
    private fun sparkle() = TiaInstrument("Sparkle", 0x4, volume = env(11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 2, 1, 1, 0))

    /** The 18/13 div 31 tone, plucked. */
    private fun bass() = TiaInstrument("Low bass", 0x6, volume = env(15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4))

    /** One frame of white noise, then div 93 falling: the raw divider rises with the arpeggio envelope. */
    private fun kick() = TiaInstrument("Kick", 0xE, raw = true, volume = env(15, 15, 13, 11, 8, 5, 2, 0),
        arpeggio = env(0, 0, -3, -6, -9, -12, -15), waveforms = env(0x8, 0xE))

    /** White noise (AUDC 8), a bright attack darkening over three frames. */
    private fun snare() = TiaInstrument("Snare", 0x8, raw = true, volume = env(14, 12, 10, 8, 6, 5, 4, 3, 2, 1, 0),
        arpeggio = env(0, -2, -4, -5))

    /** The brightest white noise, very short. */
    private fun hat() = TiaInstrument("Hat", 0x8, raw = true, volume = env(7, 4, 2, 0))

    /** A rough 5-bit → 4-bit poly hit for fills (AUDC 3). */
    private fun fill() = TiaInstrument("Fill", 0x3, raw = true, volume = env(12, 10, 8, 6, 4, 2, 0))

    // ---- patterns ---------------------------------------------------------------------------------------------------

    private fun intro() = PatternBuilder("Intro").apply {
        // TIA 1: a div 2 sparkle (D-5, G-5, A-5) twice a bar, then the lead's pickup.
        for (bar in 0 until 3) for (row in listOf(0, 8)) {
            note(LEAD_CH, bar * 16 + row, n("D-5"), SPARKLE)
            fx(LEAD_CH, bar * 16 + row, 0, FX_ARPEGGIO, 0x57)
        }
        lead(listOf(48 to "A-3", 52 to "C-4", 56 to "D-4", 60 to "E-4"))
        // TIA 2: long bass notes, the drums joining in bar 3.
        note(LOW_CH, 0, n("D-1"), BASS)
        note(LOW_CH, 16, n("D-2"), BASS)
        for (bar in 2 until 4) bassBar(bar, ROOTS_A[if (bar == 2) 0 else 3])
        for (row in listOf(56, 58, 60, 62)) note(LOW_CH, row, SNARE_NOTE, SNARE)
    }.build()

    private fun themeA() = PatternBuilder("Theme A").apply {
        lead(listOf(
            0 to "D-4", 4 to "F-4", 6 to "A-4", 8 to "C-5", 12 to "A-4", 14 to "F-4",
            16 to "E-4", 20 to "C-4", 24 to "E-4", 26 to "F-4", 28 to "E-4",
            32 to "D-4", 36 to "E-4", 38 to "D-4", 40 to "C-4", 44 to "A-3", 46 to "C-4",
            48 to "D-4"))
        // Depth 4 (±31/64 semitone): D-4's divider and, at the peaks, D#4's.
        fx(LEAD_CH, 52, 0, FX_VIBRATO, 0x44)
        cut(LEAD_CH, 62)
        fx(LEAD_CH, 62, 0, FX_VIBRATO, 0x40)
        for (bar in 0 until 4) bassBar(bar, ROOTS_A[bar])
    }.build()

    private fun themeA2() = PatternBuilder("Theme A'").apply {
        lead(listOf(
            0 to "A-4", 4 to "C-5", 6 to "D-5", 8 to "E-5", 12 to "D-5", 14 to "C-5",
            16 to "E-5",
            24 to "C-5", 28 to "A-4",
            32 to "D-5", 36 to "C-5", 40 to "A-4", 44 to "C-5", 46 to "D-5"))
        // A slide up from E-5: one jump, to the next divider (F#5's), heard after about a semitone of slide.
        fx(LEAD_CH, 18, 0, FX_SLIDE_UP, 0x04)
        fx(LEAD_CH, 23, 0, FX_SLIDE_UP, 0x00)
        // A portamento from D-5 down to A-4 without key-on: it walks the dividers between them.
        note(LEAD_CH, 52, n("A-4"), 0)
        fx(LEAD_CH, 52, 0, FX_PORTAMENTO, 0x10)
        cut(LEAD_CH, 60)
        fx(LEAD_CH, 60, 0, FX_PORTAMENTO, 0x00)
        for (bar in 0 until 4) bassBar(bar, ROOTS_A[bar], fill = bar == 3)
    }.build()

    /** F-C-F-G: the lead's held notes turn into the 4-bit buzz at the same pitch (W01), then back to the square. */
    private fun bridge() = PatternBuilder("Bridge").apply {
        note(LEAD_CH, 0, n("F-4"), LEAD)
        fx(LEAD_CH, 4, 0, FX_WAVEFORM, W_BUZZ)
        buzz(8, "C-5"); buzz(12, "F-4")
        note(LEAD_CH, 16, n("C-4"), LEAD)
        fx(LEAD_CH, 20, 0, FX_WAVEFORM, W_BUZZ)
        buzz(24, "C-5"); buzz(28, "F-4")
        buzz(32, "F-4"); fx(LEAD_CH, 32, 1, FX_VIBRATO, 0x38)
        buzz(40, "C-5"); buzz(44, "F-5"); buzz(46, "C-5")
        fx(LEAD_CH, 46, 1, FX_VIBRATO, 0x30)
        // Back on the square: D-4, E-4, then A-4 sliding down (stepping to the dividers below it).
        lead(listOf(48 to "D-4", 52 to "E-4", 56 to "A-4"))
        fx(LEAD_CH, 58, 0, FX_SLIDE_DOWN, 0x06)
        cut(LEAD_CH, 62)
        fx(LEAD_CH, 62, 0, FX_SLIDE_DOWN, 0x00)
        for (bar in 0 until 4) bassBar(bar, ROOTS_B[bar], kicks = bar == 2)
    }.build()

    /** A lead note on the 4-bit poly buzz: every note row needs its own `W01` (a note row without W restores the square). */
    private fun PatternBuilder.buzz(row: Int, name: String) {
        note(LEAD_CH, row, n(name), LEAD)
        fx(LEAD_CH, row, 0, FX_WAVEFORM, W_BUZZ)
    }

    /**
     * TIA 2 for one bar: the bass root on rows 0, 3, 6, 8, 11 and 14 (the octave up on row 8 when the root is D-1),
     * snares on 4 and 12 and a hat on 10; [kicks]: kicks on 0 and 8 instead of the bass; [fill]: a snare and fill roll
     * over the last beat.
     */
    private fun PatternBuilder.bassBar(bar: Int, root: Int, kicks: Boolean = false, fill: Boolean = false) {
        val r = bar * 16
        for (row in listOf(0, 3, 6, 8, 11, 14)) {
            if (fill && row >= 12) continue
            val octave = row == 8 && root == n("D-1")
            if (kicks && (row == 0 || row == 8)) note(LOW_CH, r + row, KICK_NOTE, KICK)
            else note(LOW_CH, r + row, if (octave) root + 12 else root, BASS)
        }
        note(LOW_CH, r + 4, SNARE_NOTE, SNARE)
        note(LOW_CH, r + 10, HAT_NOTE, HAT)
        if (fill) {
            note(LOW_CH, r + 12, SNARE_NOTE, SNARE)
            note(LOW_CH, r + 13, FILL_NOTE, FILL)
            note(LOW_CH, r + 14, SNARE_NOTE, SNARE)
            note(LOW_CH, r + 15, FILL_NOTE + 2, FILL)
        } else {
            note(LOW_CH, r + 12, SNARE_NOTE, SNARE)
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
        private val tracks = List(TiaRules.CHANNEL_NAMES.size) { Array(Songs.ROWS) { Cell.BLANK } }

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
