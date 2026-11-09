package org.thortracker.system.pokey

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.pokey.PokeyRules.FX_ARPEGGIO
import org.thortracker.system.pokey.PokeyRules.FX_CLOCK
import org.thortracker.system.pokey.PokeyRules.FX_FILTER
import org.thortracker.system.pokey.PokeyRules.FX_POLY
import org.thortracker.system.pokey.PokeyRules.FX_RESTART
import org.thortracker.system.pokey.PokeyRules.FX_SLIDE_UP
import org.thortracker.system.pokey.PokeyRules.FX_TIMERS
import org.thortracker.system.pokey.PokeyRules.FX_TWO_TONE
import org.thortracker.system.pokey.PokeyRules.FX_VIBRATO

/**
 * The bundled POKEY demo: "Scanline Cruise", D minor, Dm-B♭-F-C, 6 ticks per row (150 BPM on NTSC), every kind of
 * POKEY sound. Orders: Intro, Drive, Glow, Saw, Static, Drive again, looping to Drive.
 *
 * - Intro: an 8-bit pure lead (POKEY 1), the distortion-C buzz bass (POKEY 2), arpeggio-envelope chords (POKEY 3) and
 *   noise snares over volume-only thumps (POKEY 4).
 * - Drive: the lead, digi drums from the song's samples (POKEY 2) and a 16-bit bass on POKEY 4 that takes POKEY 3;
 *   its first row restores the 64 kHz clock, 17-bit noise and two-tone off (K00 N00 W00) and restarts the timers (T00).
 * - Glow: a high-pass phasing lead on POKEY 1 clocked by POKEY 3, with `Hxx` offsets; square chords (`0xy`) and the
 *   buzz bass.
 * - Saw: the difference "POKEY saw" (POKEY 1 + 3 at 1.79 MHz), digi drums and the buzz bass.
 * - Static: the 15 kHz clock and 9-bit noise (K01 N01) with a generator restart (R00), and two-tone sync (W02): the
 *   silent "Sync clock" on POKEY 2 sets the pitch, the 1.79 MHz "Sync lead" on POKEY 1 sweeps the timbre.
 */
object PokeyDemoSong {
    private const val SPEED = 6

    private val PRESET_SLOTS = PokeyPresets.ALL.mapIndexed { i, p -> p.name to i + 1 }.toMap()
    private fun slot(name: String) = PRESET_SLOTS[name] ?: throw IllegalStateException("Demo needs preset $name")

    private val LEAD = slot("Pure lead")
    private val SQUARE = slot("Soft square")
    private val MAJOR = slot("Major arp")
    private val MINOR = slot("Minor arp")
    private val BUZZ = slot("Dist C bass")
    private val BASS16 = slot("16-bit bass")
    private val PHASING = slot("Phasing lead")
    private val SAW = slot("POKEY saw")
    private val SNARE = slot("Noise snare")
    private val HAT = slot("Noise hat")
    private val THUMP = slot("Thump")
    private val SYNC_LEAD = slot("Sync lead")
    private val SYNC_CLOCK = slot("Sync clock")

    /** The digi kit's sounds follow the presets. */
    private val KICK = PokeyPresets.ALL.size + 1 + PokeySamples.KICK
    private val DIGI_SNARE = PokeyPresets.ALL.size + 1 + PokeySamples.SNARE
    private val DIGI_HAT = PokeyPresets.ALL.size + 1 + PokeySamples.HAT
    private val TOM = PokeyPresets.ALL.size + 1 + PokeySamples.TOM

    private const val CH1 = 0
    private const val CH2 = 1
    private const val CH3 = 2
    private const val CH4 = 3

    private val ROOTS = listOf("D-2", "A#-1", "F-2", "C-2").map(::n)
    private val SAW_ROOTS = listOf("D-3", "A#-2", "F-3", "C-3").map(::n)

    /** Snare at C-5 (a clean pure first tick on both base clocks), hats at C-7, digi drums at their root C-4. */
    private val SNARE_NOTE = n("C-5")
    private val HAT_NOTE = n("C-7")
    private val DIGI = n("C-4")

    fun build(): Song {
        val blank = PokeySystem.blank()
        val kit = PokeyPresets.drumKit(0)
        var sound = blank.pokey.copy(samples = PokeySamples.starterKit(), machine = PokeyMachine.NTSC)
        kit.forEachIndexed { k, preset -> sound = sound.withInstrument(PokeyPresets.ALL.size + 1 + k, preset.instrument) }
        return blank.copy(
            title = "Scanline Cruise",
            artist = "Thor Tracker",
            comment = "POKEY demo: 8-bit, 16-bit, high-pass and difference sounds, digi drums, 15 kHz and 9-bit noise, " +
                "two-tone sync, volume only. Loops to order 1.",
            ticksPerRow = SPEED,
            loopOrder = 1,
            patterns = listOf(intro(), drive(), glow(), saw(), static()),
            orders = listOf(0, 1, 2, 3, 4, 1),
            sound = sound,
        )
    }

    /** One order of one row at speed 1: a held A-4 on POKEY 1 with sound 01, looping to itself. */
    fun shortest(): Song {
        val blank = PokeySystem.blank()
        val tracks = List(PokeyRules.CHANNEL_NAMES.size) { ch -> List(Songs.ROWS) { r -> if (ch == CH1 && r == 0) Cell(n("A-4"), 1) else Cell.BLANK } }
        return blank.copy(
            title = "POKEY shortest",
            ticksPerRow = 1,
            loopOrder = 0,
            patterns = listOf(Pattern("Pattern 00", tracks, length = 1)),
            orders = listOf(0),
        )
    }

    private fun intro() = PatternBuilder("Intro").apply {
        lead(listOf(0 to "A-4", 4 to "D-5", 8 to "F-5", 12 to "E-5", 16 to "D-5", 20 to "F-5", 24 to "A#-4", 28 to "D-5",
            32 to "C-5", 36 to "A-4", 40 to "F-4", 44 to "A-4", 48 to "G-4", 52 to "C-5", 56 to "E-5", 60 to "G-5"))
        fx(CH1, 0, 0, FX_VIBRATO, 0x42)
        for (bar in 0 until 4) {
            buzz(bar, CH2, listOf(0, 4, 6, 8, 12, 14))
            note(CH3, bar * 16, listOf(n("D-4"), n("A#-3"), n("F-3"), n("C-4"))[bar], if (bar == 0) MINOR else MAJOR)
            for (row in listOf(0, 8)) note(CH4, bar * 16 + row, DIGI, THUMP)
            for (row in listOf(4, 12)) note(CH4, bar * 16 + row, SNARE_NOTE, SNARE)
        }
        off(CH3, 60)
    }.build()

    private fun drive() = PatternBuilder("Drive").apply {
        // Restore the chip-wide state the Static section changes; the loop arrives here too.
        fx(CH3, 0, 0, FX_CLOCK, 0x00)
        fx(CH3, 0, 1, FX_POLY, 0x00)
        fx(CH3, 0, 2, FX_TWO_TONE, 0x00)
        fx(CH3, 0, 3, FX_TIMERS, 0x00)
        lead(listOf(0 to "D-5", 2 to "E-5", 4 to "F-5", 8 to "A-5", 12 to "G-5", 14 to "F-5", 16 to "F-5", 20 to "D-5", 24 to "A#-4",
            28 to "C-5", 32 to "C-5", 34 to "D-5", 36 to "C-5", 40 to "A-4", 44 to "F-4", 48 to "E-4", 52 to "G-4", 56 to "C-5", 60 to "E-5"))
        for (bar in 0 until 4) {
            digiDrums(bar)
            val r = bar * 16
            for ((row, offset) in listOf(0 to 0, 6 to 0, 8 to 12, 12 to 0, 14 to 7)) note(CH4, r + row, ROOTS[bar] + offset, BASS16)
        }
        off(CH4, 62)
    }.build()

    private fun glow() = PatternBuilder("Glow").apply {
        for ((row, name) in listOf(0 to "D-5", 12 to "C-5", 16 to "A#-4", 28 to "A-4", 32 to "C-5", 40 to "F-5", 48 to "E-5", 56 to "C-5")) {
            note(CH1, row, n(name), PHASING)
        }
        // The high-pass clock's offset: 1 phases slowly, 3 faster, -2 the other way.
        fx(CH1, 0, 0, FX_FILTER, 0x01)
        fx(CH1, 32, 0, FX_FILTER, 0x03)
        fx(CH1, 48, 0, FX_FILTER, 0xFE)
        off(CH1, 62)
        val chords = listOf(n("D-4") to 0x37, n("A#-3") to 0x47, n("F-3") to 0x47, n("C-4") to 0x47)
        for (bar in 0 until 4) {
            val (root, arp) = chords[bar]
            for (row in listOf(0, 8)) {
                note(CH2, bar * 16 + row, root, SQUARE)
                fx(CH2, bar * 16 + row, 0, FX_ARPEGGIO, arp)
            }
            buzz(bar, CH4, listOf(0, 6, 8, 14))
        }
        off(CH2, 62)
    }.build()

    private fun saw() = PatternBuilder("Saw").apply {
        for (bar in 0 until 4) {
            val r = bar * 16
            for ((row, offset) in listOf(0 to 0, 3 to 0, 6 to 12, 8 to 0, 11 to 7, 14 to 10)) note(CH1, r + row, SAW_ROOTS[bar] + offset, SAW)
            digiDrums(bar)
            buzz(bar, CH4, listOf(0, 8))
        }
        off(CH1, 62)
    }.build()

    private fun static() = PatternBuilder("Static").apply {
        // The 15 kHz clock, 9-bit noise from a restarted generator, and two-tone sync: POKEY 2 restarts POKEY 1.
        note(CH3, 0, HAT_NOTE, HAT)
        fx(CH3, 0, 0, FX_CLOCK, 0x01)
        fx(CH3, 0, 1, FX_POLY, 0x01)
        fx(CH3, 0, 2, FX_TWO_TONE, 0x02)
        fx(CH3, 0, 3, FX_RESTART, 0x00)
        for (bar in 0 until 4) {
            val r = bar * 16
            note(CH1, r, n("A#-7"), SYNC_LEAD)
            note(CH1, r + 8, n("D-8"), SYNC_LEAD)
            for (row in listOf(0, 8)) note(CH4, r + row, DIGI, THUMP)
            for (row in listOf(2, 6, 10, 14)) note(CH3, r + row, HAT_NOTE, HAT)
            if (bar > 0) note(CH3, r, HAT_NOTE, HAT)
            for (row in listOf(4, 12)) note(CH3, r + row, SNARE_NOTE, SNARE)
        }
        fx(CH1, 0, 0, FX_SLIDE_UP, 0x06)
        val melody = listOf(0 to "D-3", 4 to "F-3", 8 to "A-3", 12 to "F-3", 16 to "A#-3", 20 to "A-3", 24 to "F-3", 28 to "D-3",
            32 to "C-4", 36 to "A-3", 40 to "F-3", 44 to "A-3", 48 to "G-3", 52 to "E-3", 56 to "C-4", 60 to "G-3")
        for ((row, name) in melody) note(CH2, row, n(name), SYNC_CLOCK)
        off(CH1, 62)
        fx(CH1, 62, 0, FX_SLIDE_UP, 0x00)
    }.build()

    /** The buzz bass on [channel]: the bar's root on [rows]. */
    private fun PatternBuilder.buzz(bar: Int, channel: Int, rows: List<Int>) {
        for (row in rows) note(channel, bar * 16 + row, ROOTS[bar], BUZZ)
    }

    /** Digi kick, snare and hats on POKEY 2, a tom fill closing the last bar. */
    private fun PatternBuilder.digiDrums(bar: Int) {
        val r = bar * 16
        note(CH2, r, DIGI, KICK)
        note(CH2, r + 8, DIGI, KICK)
        if (bar % 2 == 1) note(CH2, r + 10, DIGI, KICK) else note(CH2, r + 10, DIGI, DIGI_HAT)
        note(CH2, r + 4, DIGI, DIGI_SNARE)
        note(CH2, r + 2, DIGI, DIGI_HAT)
        note(CH2, r + 6, DIGI, DIGI_HAT)
        if (bar == 3) {
            note(CH2, r + 12, DIGI, TOM)
            note(CH2, r + 14, DIGI, TOM)
        } else {
            note(CH2, r + 12, DIGI, DIGI_SNARE)
            note(CH2, r + 14, DIGI, DIGI_HAT)
        }
    }

    private fun PatternBuilder.lead(notes: List<Pair<Int, String>>) {
        for ((row, name) in notes) note(CH1, row, n(name), LEAD)
    }

    /** "A-4", "A#-4" (sharps keep the dash for readability here) → absolute note. */
    private fun n(name: String): Int {
        val octave = name.last().digitToInt()
        val index = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B").indexOf(name.dropLast(1).removeSuffix("-"))
        require(index >= 0) { "bad note $name" }
        return octave * 12 + index
    }

    private class PatternBuilder(val title: String) {
        private val tracks = List(PokeyRules.CHANNEL_NAMES.size) { Array(Songs.ROWS) { Cell.BLANK } }

        fun note(channel: Int, row: Int, note: Int, instrument: Int) {
            tracks[channel][row] = tracks[channel][row].copy(note = note, instrument = instrument)
        }

        fun off(channel: Int, row: Int) {
            tracks[channel][row] = tracks[channel][row].copy(note = Cell.CUT, instrument = 0)
        }

        fun fx(channel: Int, row: Int, column: Int, effect: Int, param: Int) {
            tracks[channel][row] = tracks[channel][row].withEffect(column, effect, param)
        }

        fun build() = Pattern(title, tracks.map { it.toList() })
    }
}
