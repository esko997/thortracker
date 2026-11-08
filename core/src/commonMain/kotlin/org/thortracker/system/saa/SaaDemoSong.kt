package org.thortracker.system.saa

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.saa.SaaRules.FX_ARPEGGIO
import org.thortracker.system.saa.SaaRules.FX_CUT
import org.thortracker.system.saa.SaaRules.FX_DELAY
import org.thortracker.system.saa.SaaRules.FX_PAN
import org.thortracker.system.saa.SaaRules.FX_PORTAMENTO
import org.thortracker.system.saa.SaaRules.FX_SLIDE_UP
import org.thortracker.system.saa.SaaRules.FX_SPEED
import org.thortracker.system.saa.SaaRules.FX_SYNC
import org.thortracker.system.saa.SaaRules.FX_VIBRATO
import org.thortracker.system.saa.SaaRules.FX_VOLUME
import org.thortracker.system.saa.SaaRules.FX_VOLUME_SLIDE

/**
 * The bundled SAM Coupé and CMS demos, written for Thor Tracker (no game's music), and the shortest valid songs the
 * native WAV export test renders.
 *
 * - [coupeLights], "Coupé Lights" (SAM Coupé): E minor, Em-C-D-Bm, 6 ticks per row at 50 Hz (125 BPM). SAA 2 plays a
 *   high plucked line (panned right) whose tone generator clocks the buzz bass on SAA 3: a repeating hardware envelope
 *   with the mixer off, so the envelope itself is the waveform, three octaves below SAA 2's notes; the bass line is
 *   SAA 2's line. SAA 4-6 are kick, snare and hats, all on noise clock 0, so they share lanes 4-6's noise generator
 *   without clashing. SAA 1 carries the stereo leads: a lead panned left with vibrato, an echo lead whose repeats are
 *   swept across the stereo field with `8xy`, and a stereo pad with `0xy` chords in the outro. Intro, theme A, theme
 *   B, theme A, outro; loops to theme A.
 * - [blasterSkyline], "Blaster Skyline" (Creative Music System): D major, D-A-Bm-G, a 7/6-tick groove at 60 Hz
 *   (13 ticks per two rows: 138.5 BPM, the closest a 60 Hz tick gets to 140 without running the instruments faster),
 *   twelve lanes. Chip A: plucked chords with `0xy`, the clock line and the buzz bass (chip A's envelope 0), kick,
 *   snare and hats. Chip B: a lead doubled on B1 and B2 (panned apart) whose phrases start with `Y01`, restarting chip
 *   B's generators together so the unison is phase-aligned; the mirror (chip B's envelope 0: the right output inverted)
 *   on B3, a noise sweep riser on B4 (noise clock 3, following its own pitch sweep), a stereo pad on B5 and the slow
 *   swell (chip B's envelope 1, external clock) on B6. Intro, theme A, theme B, theme A, bridge; loops to theme A.
 */
object SaaDemoSong {
    private val PRESET_SLOTS = SaaPresets.ALL.mapIndexed { i, p -> p.name to i + 1 }.toMap()
    private fun slot(name: String) = PRESET_SLOTS[name] ?: throw IllegalStateException("Demo needs preset $name")

    private val LEAD = slot("Lead")
    private val PLUCK = slot("Pluck")
    private val STEREO_PAD = slot("Stereo pad")
    private val ECHO_LEAD = slot("Echo lead")
    private val KICK = slot("Kick")
    private val SNARE = slot("Snare")
    private val HAT = slot("Hat")
    private val NOISE_SWEEP = slot("Noise sweep")
    private val BUZZ_BASS = slot("Buzz bass")
    private val SLOW_SWELL = slot("Slow swell")
    private val MIRROR = slot("Mirror")

    /** The demos' own instruments, after the presets: panned copies of presets. */
    private val LEFT = SaaPresets.ALL.size + 1
    private val RIGHT = SaaPresets.ALL.size + 2

    private const val ROWS = 32
    private const val BLOCK = 8

    // ---- "Coupé Lights" (SAM Coupé) -----------------------------------------------------------------------------

    private const val SAM_LEAD = 0
    private const val SAM_LINE = 1
    private const val SAM_BUZZ = 2
    private const val SAM_KICK = 3
    private const val SAM_SNARE = 4
    private const val SAM_HAT = 5

    /** Per two-beat block: the bass root (the buzz bass sounds it three octaves below SAA 2's line). */
    private val COUPE_ROOTS = listOf("E-2", "C-2", "D-2", "B-1")

    fun coupeLights(): Song {
        val blank = SamCoupeSystem.blank()
        val lead = SaaPresets.ALL[LEAD - 1].instrument
        val pluck = SaaPresets.ALL[PLUCK - 1].instrument
        val sound = blank.saa
            .withInstrument(LEFT, lead.copy(name = "Lead left", panLeft = 15, panRight = 7))
            .withInstrument(RIGHT, pluck.copy(name = "Line right", panLeft = 7, panRight = 15))
        return blank.copy(
            title = "Coupé Lights",
            artist = "Thor Tracker",
            comment = "SAM Coupé demo: SAA 2's plucked line clocks the buzz bass on SAA 3 (a hardware envelope three octaves " +
                "below it), drums on SAA 4-6 share one noise generator, stereo leads on SAA 1. Loops to order 1.",
            ticksPerRow = 6,
            loopOrder = 1,
            patterns = listOf(coupeIntro(), coupeThemeA(), coupeThemeB(), coupeOutro()),
            orders = listOf(0, 1, 2, 1, 3),
            sound = sound,
        )
    }

    private fun coupeIntro() = PatternBuilder("Intro", SaaVariant.SAM).apply {
        for (block in 0 until 4) {
            coupeBass(block)
            coupeDrums(block, snare = block >= 2, hats = block >= 1)
        }
        // A two-note pickup into theme A.
        note(SAM_LEAD, 28, n("F#4"), LEFT)
        note(SAM_LEAD, 30, n("A-4"), LEFT)
    }.build()

    private fun coupeThemeA() = PatternBuilder("Theme A", SaaVariant.SAM).apply {
        for (block in 0 until 4) {
            coupeBass(block)
            coupeDrums(block, snare = true, hats = true)
        }
        lead(SAM_LEAD, LEFT, listOf(0 to "B-4", 2 to "E-5", 4 to "G-5", 6 to "B-5", 8 to "C-6", 11 to "B-5", 12 to "G-5",
            14 to "E-5", 16 to "F#5", 18 to "A-5", 20 to "D-6", 22 to "A-5", 24 to "B-5", 27 to "A-5", 28 to "F#5", 30 to "D-5"))
        // Vibrato and full channel volume from the first note (two columns).
        fx(SAM_LEAD, 0, 0, FX_VIBRATO, 0x34)
        fx(SAM_LEAD, 0, 1, FX_VOLUME, 0x0F)
        // A flam: the last snare's row runs two ticks late.
        fx(SAM_SNARE, 28, 0, FX_DELAY, 2)
    }.build()

    private fun coupeThemeB() = PatternBuilder("Theme B", SaaVariant.SAM).apply {
        for (block in 0 until 4) {
            coupeBass(block)
            coupeDrums(block, snare = true, hats = true)
        }
        // The echo lead plays plain (theme A's vibrato stops): each note's repeats (ticks 7, 13, 19 and 25) swept
        // from left to right with 8xy.
        fx(SAM_LEAD, 0, 1, FX_VIBRATO, 0x00)
        for ((block, name) in listOf("E-5", "G-5", "A-5", "F#5").withIndex()) {
            val r = block * BLOCK
            note(SAM_LEAD, r, n(name), ECHO_LEAD)
            fx(SAM_LEAD, r, 0, FX_PAN, 0xFF)
            fx(SAM_LEAD, r + 1, 0, FX_PAN, 0xF4)
            fx(SAM_LEAD, r + 2, 0, FX_PAN, 0xC8)
            fx(SAM_LEAD, r + 3, 0, FX_PAN, 0x8C)
            fx(SAM_LEAD, r + 4, 0, FX_PAN, 0x4F)
        }
        // Choked hats: Sxx keys the last hat of each block off two ticks into its row.
        for (block in 0 until 4) fx(SAM_HAT, block * BLOCK + 6, 0, FX_CUT, 2)
    }.build()

    private fun coupeOutro() = PatternBuilder("Outro", SaaVariant.SAM).apply {
        for (block in 0 until 4) {
            coupeBass(block)
            coupeDrums(block, snare = true, hats = block < 3)
        }
        // The stereo pad's chords as arpeggios, then a rising slide that fades; everything reset for the loop.
        for ((block, chord) in listOf("E-4" to 0x37, "C-4" to 0x47, "D-4" to 0x47, "B-3" to 0x37).withIndex()) {
            note(SAM_LEAD, block * BLOCK, n(chord.first), STEREO_PAD)
            fx(SAM_LEAD, block * BLOCK, 0, FX_ARPEGGIO, chord.second)
        }
        fx(SAM_LEAD, 24, 1, FX_VOLUME_SLIDE, 0x01)
        fx(SAM_LEAD, 28, 0, FX_SLIDE_UP, 0x04)
        cut(SAM_LEAD, 31)
        fx(SAM_LEAD, 31, 0, FX_SLIDE_UP, 0x00)
        fx(SAM_LEAD, 31, 1, FX_VOLUME_SLIDE, 0x00)
        fx(SAM_LEAD, 31, 2, FX_VOLUME, 0x0F)
    }.build()

    /**
     * SAA 2's line (the root three octaves up twice, then the fifth above it: the bass line the buzz bass follows) and
     * the buzz bass keyed at the block's start. SAA 2 comes first in the row, so its pitch is set before the envelope starts.
     */
    private fun PatternBuilder.coupeBass(block: Int) {
        val r = block * BLOCK
        val root = n(COUPE_ROOTS[block])
        note(SAM_LINE, r, root + 36, RIGHT)
        note(SAM_LINE, r + 3, root + 36, RIGHT)
        note(SAM_LINE, r + 6, root + 43, RIGHT)
        note(SAM_BUZZ, r, root, BUZZ_BASS)
    }

    /** Kick on the block's first beat, snare on its second, hats on the eighths: all on noise clock 0. */
    private fun PatternBuilder.coupeDrums(block: Int, snare: Boolean, hats: Boolean) {
        val r = block * BLOCK
        note(SAM_KICK, r, n("A-2"), KICK)
        if (snare) note(SAM_SNARE, r + 4, n("C-4"), SNARE)
        if (hats) for (k in 0 until 4) note(SAM_HAT, r + 2 * k, n("C-6"), HAT)
        else note(SAM_HAT, r + 4, n("C-6"), HAT)
    }

    // ---- "Blaster Skyline" (Creative Music System) --------------------------------------------------------------

    private const val A_CHORDS = 0
    private const val A_LINE = 1
    private const val A_BUZZ = 2
    private const val A_KICK = 3
    private const val A_SNARE = 4
    private const val A_HAT = 5
    private const val B_LEAD = 6
    private const val B_TWIN = 7
    private const val B_MIRROR = 8
    private const val B_SWEEP = 9
    private const val B_PAD = 10
    private const val B_SWELL = 11

    /** Per two-beat block: bass root, the pluck's chord root and `0xy`, the mirror's third, the pad's fifth, the swell's root. */
    private val SKYLINE_ROOTS = listOf("D-2", "A-1", "B-1", "G-1")
    private val SKYLINE_CHORDS = listOf("D-4" to 0x47, "A-3" to 0x47, "B-3" to 0x37, "G-3" to 0x47)
    private val SKYLINE_THIRDS = listOf("F#4", "C#4", "D-4", "B-3")
    private val SKYLINE_FIFTHS = listOf("A-4", "E-4", "F#4", "D-4")
    private val SKYLINE_SWELL = listOf("D-3", "A-2", "B-2", "G-2")

    fun blasterSkyline(): Song {
        val blank = CmsSystem.blank()
        val lead = SaaPresets.ALL[LEAD - 1].instrument
        val sound = blank.saa
            .withInstrument(LEFT, lead.copy(name = "Unison left", panLeft = 15, panRight = 5))
            .withInstrument(RIGHT, lead.copy(name = "Unison right", panLeft = 5, panRight = 15))
        return blank.copy(
            title = "Blaster Skyline",
            artist = "Thor Tracker",
            comment = "Creative Music System demo: twelve lanes on two SAA1099s, both chips' hardware envelopes (buzz bass on " +
                "A3, mirror and slow swell on B3 and B6), a lead doubled on B1 and B2 and phase-aligned by Y01. Loops to order 1.",
            ticksPerRow = 7,
            loopOrder = 1,
            patterns = listOf(skylineIntro(), skylineThemeA(), skylineThemeB(), skylineBridge()),
            orders = listOf(0, 1, 2, 1, 3),
            sound = sound,
        )
    }

    private fun skylineIntro() = PatternBuilder("Intro", SaaVariant.CMS).apply {
        groove()
        for (block in 0 until 4) {
            skylineBass(block)
            skylineDrums(block, snare = block >= 2)
            skylinePads(block)
        }
        // A riser into theme A: the noise sweep on B4 follows its own rising pitch (noise clock 3).
        note(B_SWEEP, 24, n("C-4"), NOISE_SWEEP)
    }.build()

    private fun skylineThemeA() = PatternBuilder("Theme A", SaaVariant.CMS).apply {
        groove()
        for (block in 0 until 4) {
            skylineBass(block)
            skylineDrums(block, snare = true)
            skylineChords(block)
            skylinePads(block)
        }
        unison(listOf(0 to "F#5", 2 to "A-5", 4 to "D-6", 6 to "C#6", 8 to "E-5", 11 to "A-5", 14 to "C#6", 16 to "D-6",
            18 to "F#6", 20 to "D-6", 22 to "B-5", 24 to "D-6", 27 to "B-5", 28 to "G-5", 30 to "A-5"))
        fx(B_LEAD, 0, 1, FX_VIBRATO, 0x23)
        fx(B_TWIN, 0, 1, FX_VIBRATO, 0x23)
    }.build()

    private fun skylineThemeB() = PatternBuilder("Theme B", SaaVariant.CMS).apply {
        groove()
        for (block in 0 until 4) {
            skylineBass(block)
            skylineDrums(block, snare = true)
            skylineChords(block)
            skylinePads(block)
        }
        unison(listOf(0 to "A-5", 4 to "F#5", 6 to "E-5", 8 to "C#5", 12 to "E-5", 16 to "F#5", 20 to "B-5", 24 to "A-5"))
        // The held last note glides up to D-6 without a new attack, then stops.
        for (ch in listOf(B_LEAD, B_TWIN)) {
            note(ch, 28, n("D-6"), 0)
            fx(ch, 28, 1, FX_PORTAMENTO, 0x18)
            cut(ch, 31)
            fx(ch, 31, 1, FX_PORTAMENTO, 0x00)
        }
        note(B_SWEEP, 24, n("C-4"), NOISE_SWEEP)
        // A flam on the last snare.
        fx(A_SNARE, 28, 0, FX_DELAY, 2)
    }.build()

    private fun skylineBridge() = PatternBuilder("Bridge", SaaVariant.CMS).apply {
        groove()
        for (block in 0 until 4) {
            skylineBass(block)
            skylineDrums(block, snare = true)
            skylinePads(block)
        }
        // The plucks rest; the unison plays long notes, quieter, every phrase phase-aligned again.
        unison(listOf(0 to "D-5", 8 to "C#5", 16 to "B-4", 24 to "D-5"))
        for (ch in listOf(B_LEAD, B_TWIN)) fx(ch, 0, 1, FX_VOLUME, 0x0B)
        for (ch in listOf(B_LEAD, B_TWIN)) fx(ch, 31, 1, FX_VOLUME, 0x0F)
        for (block in 1 until 4) fx(B_LEAD, block * BLOCK, 2, FX_SYNC, SaaRules.SYNC_RESTART)
    }.build()

    /** The 7/6 groove: F07 on even rows, F06 on odd rows, in A6's first effect column (its hats use none). */
    private fun PatternBuilder.groove() {
        for (row in 0 until ROWS) fx(A_HAT, row, 0, FX_SPEED, if (row % 2 == 0) 7 else 6)
    }

    /** A2's line (root three octaves up, twice, then the fifth) and the buzz bass on A3 keyed with it. */
    private fun PatternBuilder.skylineBass(block: Int) {
        val r = block * BLOCK
        val root = n(SKYLINE_ROOTS[block])
        note(A_LINE, r, root + 36, PLUCK)
        note(A_LINE, r + 3, root + 36, PLUCK)
        note(A_LINE, r + 6, root + 43, PLUCK)
        note(A_BUZZ, r, root, BUZZ_BASS)
    }

    /** Kick, snare and hats on chip A's lanes 4-6, all on noise clock 0. */
    private fun PatternBuilder.skylineDrums(block: Int, snare: Boolean) {
        val r = block * BLOCK
        note(A_KICK, r, n("A-2"), KICK)
        note(A_KICK, r + 5, n("A-2"), KICK)
        if (snare) note(A_SNARE, r + 4, n("C-4"), SNARE)
        for (k in 0 until 4) note(A_HAT, r + 2 * k, n("C-6"), HAT)
    }

    /** Three plucks per block on A1, each with the block's chord as an arpeggio. */
    private fun PatternBuilder.skylineChords(block: Int) {
        val r = block * BLOCK
        val (root, arp) = SKYLINE_CHORDS[block]
        for (row in listOf(0, 3, 6)) {
            note(A_CHORDS, r + row, n(root), PLUCK)
            fx(A_CHORDS, r + row, 0, FX_ARPEGGIO, arp)
        }
    }

    /** Chip B's held chord: the mirror's third on B3, the stereo pad's fifth on B5, the slow swell's root on B6. */
    private fun PatternBuilder.skylinePads(block: Int) {
        val r = block * BLOCK
        note(B_MIRROR, r, n(SKYLINE_THIRDS[block]), MIRROR)
        note(B_PAD, r, n(SKYLINE_FIFTHS[block]), STEREO_PAD)
        note(B_SWELL, r, n(SKYLINE_SWELL[block]), SLOW_SWELL)
    }

    /** The doubled lead on B1 and B2, panned apart; `Y01` on the first note restarts chip B's generators in phase. */
    private fun PatternBuilder.unison(notes: List<Pair<Int, String>>) {
        for ((row, name) in notes) {
            note(B_LEAD, row, n(name), LEFT)
            note(B_TWIN, row, n(name), RIGHT)
        }
        fx(B_LEAD, notes.first().first, 0, FX_SYNC, SaaRules.SYNC_RESTART)
    }

    // ---- shortest -----------------------------------------------------------------------------------------------

    /**
     * The shortest valid song for [variant] (the WAV export test's `saa-sam-shortest.saaimg` / `saa-cms-shortest.saaimg`):
     * one order of a one-row pattern at speed 1, looping to order 0, holding A-4 on lane 1 with slot 01, a constant
     * full-volume tone panned centre, so every pass sounds.
     */
    fun shortest(variant: SaaVariant): Song {
        val base = variant.system.blank()
        val held = Cell(note = n("A-4"), instrument = 1)
        val tracks = List(variant.channels) { ch -> List(Songs.ROWS) { row -> if (ch == 0 && row == 0) held else Cell.BLANK } }
        return base.copy(
            title = "${variant.label} shortest",
            ticksPerRow = 1,
            loopOrder = 0,
            patterns = listOf(Pattern("Pattern 00", tracks, length = 1)),
            orders = listOf(0),
            sound = SaaSound(
                variant = variant,
                instruments = List(SaaSound.SLOTS) { if (it == 0) SaaInstrument("Tone", volume = NesEnvelope(listOf(15))) else null },
            ),
        )
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private fun PatternBuilder.lead(channel: Int, instrument: Int, notes: List<Pair<Int, String>>) {
        for ((row, name) in notes) note(channel, row, n(name), instrument)
    }

    private fun n(name: String): Int {
        val index = listOf("C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-").indexOf(name.substring(0, 2))
        require(index >= 0) { "bad note $name" }
        return name.substring(2).toInt() * 12 + index
    }

    private class PatternBuilder(val title: String, variant: SaaVariant) {
        private val tracks = List(variant.channels) { Array(Songs.ROWS) { Cell.BLANK } }

        fun note(channel: Int, row: Int, note: Int, instrument: Int) {
            tracks[channel][row] = tracks[channel][row].copy(note = note, instrument = instrument)
        }

        fun cut(channel: Int, row: Int) {
            tracks[channel][row] = tracks[channel][row].copy(note = Cell.CUT, instrument = 0)
        }

        fun fx(channel: Int, row: Int, column: Int, effect: Int, param: Int) {
            tracks[channel][row] = tracks[channel][row].withEffect(column, effect, param)
        }

        fun build() = Pattern(title, tracks.map { it.toList() }, length = ROWS)
    }
}
