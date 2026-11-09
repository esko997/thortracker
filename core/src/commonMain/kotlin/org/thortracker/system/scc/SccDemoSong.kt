package org.thortracker.system.scc

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.scc.SccRules.FX_ARPEGGIO
import org.thortracker.system.scc.SccRules.FX_CUT
import org.thortracker.system.scc.SccRules.FX_DELAY
import org.thortracker.system.scc.SccRules.FX_PORTAMENTO
import org.thortracker.system.scc.SccRules.FX_SLIDE_UP
import org.thortracker.system.scc.SccRules.FX_SPEED
import org.thortracker.system.scc.SccRules.FX_TEST
import org.thortracker.system.scc.SccRules.FX_VIBRATO
import org.thortracker.system.scc.SccRules.FX_VOLUME
import org.thortracker.system.scc.SccRules.FX_VOLUME_SLIDE
import org.thortracker.system.scc.SccRules.FX_WAVE

/**
 * The bundled SCC and SCC+ demos, written for Thor Tracker (no game's music), and the shortest valid song the native
 * WAV export test renders.
 *
 * - [build], "Cartridge Dusk" (SCC, K051649): D minor, Dm-B♭-C-A, 6 ticks per row (150 BPM). Intro, theme A, theme B,
 *   theme A again and a turnaround, looping to theme A. SCC 1 a pulse lead with vibrato and per-note and mid-note wave
 *   changes (`W`), SCC 2 saw plucks with `0xy` chord arpeggios (the morphing wave-envelope instrument in the
 *   turnaround), SCC 3 bass interleaved with a swept sine kick and a noise-wave snare. SCC 4 and SCC 5 share one wave
 *   RAM on this chip, so the song uses them as the chip allows: a two-voice organ pad on the same instrument (one wave
 *   for both), and in theme B a call and response that alternates a saw pluck on SCC 4 with a square echo on SCC 5,
 *   each phrase ended by OFF before the other enters.
 * - [buildPlus], "Prism Lane" (SCC+, K052539): A major, A-F♯m-D-E, 5 ticks per row (180 BPM), with two waves of its own
 *   (Glass, Glass bright). Five channels with five different waves at once, which only the SCC+ can do: a glass lead
 *   (switching waves with `W`), a bell counter-melody, bass and kick, a shimmering triangle pad (the morph in theme B)
 *   and noise-wave drums. A bridge turns on the test register's waveform restart (`T20`: every period write restarts
 *   the wave, so vibrato buzzes like hard sync) and speeds up, then clears it.
 */
object SccDemoSong {
    private val PRESET_SLOTS = SccPresets.ALL.mapIndexed { i, p -> p.name to i + 1 }.toMap()
    private fun slot(name: String) = PRESET_SLOTS[name] ?: throw IllegalStateException("Demo needs preset $name")

    private val LEAD = slot("Lead")
    private val BASS = slot("Bass")
    private val BELL = slot("Bell")
    private val PAD = slot("Pad")
    private val PLUCK = slot("Pluck")
    private val ORGAN = slot("Organ")
    private val KICK = slot("Kick")
    private val SNARE = slot("Snare")
    private val HAT = slot("Hat")
    private val ARP_LEAD = slot("Arp lead")
    private val ECHO = slot("Echo")
    private val MORPH = slot("Morph")

    private const val ROWS = 32
    private const val BLOCK = 8

    // ---- "Cartridge Dusk" (SCC) ------------------------------------------------------------------------------

    private const val LEAD_CH = 0
    private const val CHORD_CH = 1
    private const val BASS_CH = 2
    private const val PAIR_4 = 3
    private const val PAIR_5 = 4

    /** Per bar of two beats: bass root, pluck root and its `0xy`, the organ pair's two notes. */
    private val DUSK_BASS = listOf("D-2", "A#1", "C-2", "A-1")
    private val DUSK_PLUCK = listOf("D-4" to 0x37, "A#3" to 0x47, "C-4" to 0x47, "A-3" to 0x47)
    private val DUSK_PAIR = listOf("F-4" to "A-4", "D-4" to "F-4", "E-4" to "G-4", "C#4" to "E-4")

    fun build(): Song {
        val blank = SccSystem.blank()
        return blank.copy(
            title = "Cartridge Dusk",
            artist = "Thor Tracker",
            comment = "SCC demo: pulse lead with wave changes, chord plucks, bass with kick and snare; SCC 4 and SCC 5 share " +
                "one wave RAM, so they play one organ wave together or alternate phrases. Loops to order 1.",
            ticksPerRow = 6,
            loopOrder = 1,
            patterns = listOf(duskIntro(), duskThemeA(), duskThemeB(), duskTurnaround()),
            orders = listOf(0, 1, 2, 1, 3),
        )
    }

    private fun duskIntro() = PatternBuilder("Intro").apply {
        for (bar in 0 until 4) {
            duskBass(bar, snare = false)
            duskPlucks(bar)
            duskOrgan(bar)
        }
        // A two-note pickup into theme A.
        note(LEAD_CH, 28, n("D-5"), LEAD)
        note(LEAD_CH, 30, n("E-5"), LEAD)
    }.build()

    private fun duskThemeA() = PatternBuilder("Theme A").apply {
        for (bar in 0 until 4) {
            duskBass(bar, snare = true)
            duskPlucks(bar)
            duskOrgan(bar)
        }
        lead(LEAD_CH, LEAD, listOf(0 to "A-4", 3 to "D-5", 6 to "F-5", 8 to "G-5", 11 to "F-5", 14 to "D-5", 16 to "E-5",
            18 to "G-5", 20 to "C-6", 22 to "G-5", 24 to "E-5", 28 to "C#5", 30 to "A-4"))
        // Vibrato and full channel volume from the first note (two columns).
        fx(LEAD_CH, 0, 0, FX_VIBRATO, 0x24)
        fx(LEAD_CH, 0, 1, FX_VOLUME, 0x0F)
        // The peak note plays the 12.5% pulse (a lock for that note), and the held E-5 turns square mid-note.
        fx(LEAD_CH, 20, 0, FX_WAVE, SccPresets.PULSE_12)
        fx(LEAD_CH, 26, 0, FX_WAVE, SccPresets.SQUARE)
        // Free the shared wave RAM for theme B's call and response: the organ has no release, so OFF silences it.
        cut(PAIR_4, 31)
        cut(PAIR_5, 31)
    }.build()

    private fun duskThemeB() = PatternBuilder("Theme B").apply {
        for (bar in 0 until 4) {
            duskBass(bar, snare = true)
            duskPlucks(bar)
        }
        lead(LEAD_CH, LEAD, listOf(0 to "D-5", 2 to "E-5", 4 to "F-5", 6 to "A-5", 8 to "A#5", 14 to "A-5", 16 to "G-5",
            18 to "E-5", 20 to "C-5", 22 to "E-5", 24 to "E-5"))
        // The held B♭ brightens to the 12.5% pulse halfway through.
        fx(LEAD_CH, 12, 0, FX_WAVE, SccPresets.PULSE_12)
        // A glide down to C#5 without a new attack, then OFF with portamento off.
        note(LEAD_CH, 28, n("C#5"), 0)
        fx(LEAD_CH, 28, 0, FX_PORTAMENTO, 0x20)
        cut(LEAD_CH, 30)
        fx(LEAD_CH, 30, 0, FX_PORTAMENTO, 0x00)
        // Call (SCC 4, saw pluck) and response (SCC 5, square echo): one channel at a time, so the shared RAM always
        // holds the wave of the channel that sounds. Each phrase ends with OFF before the other enters.
        lead(PAIR_4, PLUCK, listOf(0 to "A-3", 2 to "D-4", 4 to "F-4", 6 to "A-4", 8 to "A#3", 10 to "D-4", 12 to "F-4"))
        cut(PAIR_4, 15)
        lead(PAIR_5, ECHO, listOf(16 to "C-5", 20 to "G-4", 24 to "C#5", 28 to "E-5"))
        cut(PAIR_5, 31)
    }.build()

    private fun duskTurnaround() = PatternBuilder("Turnaround").apply {
        for (bar in 0 until 4) {
            duskBass(bar, snare = true)
            duskOrgan(bar)
        }
        // The arp lead's octave flutter, a speed-up into the last bar, a rising slide that fades, then everything reset.
        lead(LEAD_CH, ARP_LEAD, listOf(0 to "D-5", 8 to "A#4", 16 to "C-5", 24 to "A-4"))
        fx(LEAD_CH, 16, 0, FX_SPEED, 5)
        fx(LEAD_CH, 24, 0, FX_VOLUME_SLIDE, 0x02)
        fx(LEAD_CH, 24, 1, FX_SLIDE_UP, 0x03)
        cut(LEAD_CH, 31)
        fx(LEAD_CH, 31, 0, FX_SLIDE_UP, 0x00)
        fx(LEAD_CH, 31, 1, FX_VOLUME_SLIDE, 0x00)
        fx(LEAD_CH, 31, 2, FX_VOLUME, 0x0F)
        // The morph's wave envelope sweeps sine → square and back under held roots.
        note(CHORD_CH, 0, n("D-4"), MORPH)
        note(CHORD_CH, 16, n("C-4"), MORPH)
        // A flam on the last snare: its row runs 3 ticks late.
        fx(BASS_CH, 28, 0, FX_DELAY, 3)
    }.build()

    /** Kick, root, snare (or the fifth in the intro), octave: the bass channel doubles as the kick drum. */
    private fun PatternBuilder.duskBass(bar: Int, snare: Boolean) {
        val r = bar * BLOCK
        val root = n(DUSK_BASS[bar])
        note(BASS_CH, r, n("A-2"), KICK)
        note(BASS_CH, r + 2, root, BASS)
        if (snare) note(BASS_CH, r + 4, n("C-5"), SNARE) else note(BASS_CH, r + 4, root + 7, BASS)
        note(BASS_CH, r + 6, root + 12, BASS)
    }

    /** Three saw plucks per bar, each with the bar's chord as an arpeggio. */
    private fun PatternBuilder.duskPlucks(bar: Int) {
        val r = bar * BLOCK
        val (root, arp) = DUSK_PLUCK[bar]
        for (row in listOf(0, 3, 6)) {
            note(CHORD_CH, r + row, n(root), PLUCK)
            fx(CHORD_CH, r + row, 0, FX_ARPEGGIO, arp)
        }
    }

    /** The organ pair: the same instrument (one wave) on SCC 4 and SCC 5, so the shared RAM suits both. */
    private fun PatternBuilder.duskOrgan(bar: Int) {
        val r = bar * BLOCK
        val (a, b) = DUSK_PAIR[bar]
        note(PAIR_4, r, n(a), ORGAN)
        note(PAIR_5, r, n(b), ORGAN)
    }

    // ---- "Prism Lane" (SCC+) ---------------------------------------------------------------------------------

    private const val GLASS_CH = 0
    private const val BELL_CH = 1
    private const val LOW_CH = 2
    private const val PAD_CH = 3
    private const val DRUM_CH = 4

    /** The two waves the SCC+ demo adds after the starter waves. */
    private val GLASS = SccPresets.WAVES.size + 1
    private val GLASS_BRIGHT = SccPresets.WAVES.size + 2

    /** The demo's own instrument, after the presets. */
    private val GLASS_LEAD = SccPresets.ALL.size + 1

    private val PRISM_BASS = listOf("A-1", "F#1", "D-2", "E-2")
    private val PRISM_PAD = listOf("A-3" to 0x47, "F#3" to 0x37, "D-4" to 0x47, "E-3" to 0x47)

    fun buildPlus(): Song {
        val blank = SccPlusSystem.blank()
        val sound = blank.scc
            .withWave(GLASS, SccWave("Glass", SccWaveShapes.additive(listOf(1 to 1.0, 2 to 0.5, 3 to 0.35, 6 to 0.3))))
            .withWave(GLASS_BRIGHT, SccWave("Glass bright", SccWaveShapes.additive(listOf(1 to 1.0, 4 to 0.6, 6 to 0.45, 9 to 0.35))))
            .withInstrument(GLASS_LEAD, SccInstrument("Glass lead",
                volume = NesEnvelope(listOf(15, 14, 13, 12, 11, 11, 10, 8, 6, 4, 2, 0), release = 7),
                wave = NesEnvelope(listOf(GLASS))))
        return blank.copy(
            title = "Prism Lane",
            artist = "Thor Tracker",
            comment = "SCC+ demo: five channels, five different waves at once (one wave RAM each on the K052539), a glass " +
                "lead switching waves, bell, bass and kick, a morphing pad and noise-wave drums; the bridge uses T20. Loops to order 1.",
            ticksPerRow = 5,
            loopOrder = 1,
            patterns = listOf(prismIntro(), prismThemeA(), prismThemeB(), prismBridge()),
            orders = listOf(0, 1, 2, 1, 3),
            sound = sound,
        )
    }

    private fun prismIntro() = PatternBuilder("Intro").apply {
        for (bar in 0 until 4) {
            prismBass(bar)
            prismPad(bar, PAD)
            prismDrums(bar, snare = bar >= 2)
        }
        note(BELL_CH, 24, n("E-5"), BELL)
        note(BELL_CH, 28, n("G#5"), BELL)
    }.build()

    private fun prismThemeA() = PatternBuilder("Theme A").apply {
        for (bar in 0 until 4) {
            prismBass(bar)
            prismPad(bar, PAD)
            prismDrums(bar, snare = true)
        }
        lead(GLASS_CH, GLASS_LEAD, listOf(0 to "E-5", 4 to "C#5", 6 to "E-5", 8 to "F#5", 12 to "E-5", 14 to "C#5", 16 to "D-5",
            18 to "E-5", 20 to "F#5", 22 to "A-5", 24 to "G#5", 28 to "B-5"))
        fx(GLASS_CH, 0, 0, FX_VIBRATO, 0x23)
        // The top note plays the bright glass, then the held note returns to the plain glass two rows later.
        fx(GLASS_CH, 28, 0, FX_WAVE, GLASS_BRIGHT)
        fx(GLASS_CH, 30, 0, FX_WAVE, GLASS)
        lead(BELL_CH, BELL, listOf(2 to "C#6", 10 to "A-5", 18 to "F#5", 26 to "B-5"))
    }.build()

    private fun prismThemeB() = PatternBuilder("Theme B").apply {
        for (bar in 0 until 4) {
            prismBass(bar)
            prismPad(bar, MORPH)
            prismDrums(bar, snare = true)
        }
        lead(GLASS_CH, GLASS_LEAD, listOf(0 to "A-5", 2 to "G#5", 4 to "E-5", 8 to "F#5", 10 to "E-5", 12 to "C#5", 16 to "D-5",
            20 to "F#5", 24 to "E-5", 28 to "G#4"))
        fx(GLASS_CH, 20, 0, FX_WAVE, GLASS_BRIGHT)
        fx(GLASS_CH, 24, 0, FX_VIBRATO, 0x46)
        cut(GLASS_CH, 30)
        lead(BELL_CH, BELL, listOf(4 to "E-6", 12 to "C#6", 20 to "A-5", 28 to "B-5"))
        // Choked hats: Sxx keys the hat off two ticks into its row.
        for (bar in 0 until 4) fx(DRUM_CH, bar * BLOCK + 6, 0, FX_CUT, 2)
    }.build()

    private fun prismBridge() = PatternBuilder("Bridge").apply {
        for (bar in 0 until 4) {
            prismBass(bar)
            prismPad(bar, PAD)
            prismDrums(bar, snare = true)
        }
        // T20: every period write restarts the waveform, so the vibrato's per-tick writes buzz like hard sync.
        lead(GLASS_CH, GLASS_LEAD, listOf(0 to "A-4", 8 to "F#4", 16 to "D-5", 24 to "E-5"))
        fx(GLASS_CH, 0, 0, FX_VIBRATO, 0x38)
        fx(GLASS_CH, 0, 1, FX_TEST, SccRules.TEST_RESTART)
        // Faster for the second half; the loop returns to theme A's speed.
        fx(GLASS_CH, 16, 0, FX_SPEED, 4)
        // Off, vibrato off and the test register back to normal, in three columns.
        cut(GLASS_CH, 31)
        fx(GLASS_CH, 31, 0, FX_VIBRATO, 0x00)
        fx(GLASS_CH, 31, 1, FX_TEST, 0x00)
        fx(GLASS_CH, 31, 2, FX_VOLUME, 0x0F)
        // A flam: the last snare runs two ticks late.
        fx(DRUM_CH, 28, 0, FX_DELAY, 2)
    }.build()

    /** Kick, root twice, kick, octave. */
    private fun PatternBuilder.prismBass(bar: Int) {
        val r = bar * BLOCK
        val root = n(PRISM_BASS[bar])
        note(LOW_CH, r, n("A-2"), KICK)
        note(LOW_CH, r + 2, root, BASS)
        note(LOW_CH, r + 3, root, BASS)
        note(LOW_CH, r + 4, n("A-2"), KICK)
        note(LOW_CH, r + 6, root + 12, BASS)
    }

    /** One pad note per bar shimmering through its chord with `0xy`. */
    private fun PatternBuilder.prismPad(bar: Int, instrument: Int) {
        val r = bar * BLOCK
        val (root, arp) = PRISM_PAD[bar]
        note(PAD_CH, r, n(root), instrument)
        fx(PAD_CH, r, 0, FX_ARPEGGIO, arp)
    }

    /** Hats on the eighths, a snare on the backbeat. */
    private fun PatternBuilder.prismDrums(bar: Int, snare: Boolean) {
        val r = bar * BLOCK
        note(DRUM_CH, r, n("F#6"), HAT)
        note(DRUM_CH, r + 2, n("F#6"), HAT)
        if (snare) note(DRUM_CH, r + 4, n("C-5"), SNARE) else note(DRUM_CH, r + 4, n("F#6"), HAT)
        note(DRUM_CH, r + 6, n("F#6"), HAT)
    }

    // ---- shortest -----------------------------------------------------------------------------------------------

    /**
     * The shortest valid song for [variant] (the WAV export test's `scc-shortest.sccimg` / `scc-plus-shortest.sccimg`):
     * one order of a one-row pattern at speed 1, looping to order 0, holding A-4 on SCC 1 with slot 01, a constant
     * full-volume sine (wave 1), so every pass sounds.
     */
    fun shortest(variant: SccVariant): Song {
        val base = variant.system.blank()
        val held = Cell(note = n("A-4"), instrument = 1)
        val tracks = List(SccRules.CHANNEL_NAMES.size) { ch -> List(Songs.ROWS) { row -> if (ch == 0 && row == 0) held else Cell.BLANK } }
        return base.copy(
            title = "${variant.label} shortest",
            ticksPerRow = 1,
            loopOrder = 0,
            patterns = listOf(Pattern("Pattern 00", tracks, length = 1)),
            orders = listOf(0),
            sound = SccSound(
                variant = variant,
                instruments = List(SccSound.SLOTS) { if (it == 0) SccInstrument("Sine", volume = NesEnvelope(listOf(15)), wave = NesEnvelope(listOf(1))) else null },
                waves = List(SccSound.WAVES) { if (it == 0) SccWave("Sine", SccWaveShapes.sine()) else SccWave() },
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

    private class PatternBuilder(val title: String) {
        private val tracks = List(SccRules.CHANNEL_NAMES.size) { Array(Songs.ROWS) { Cell.BLANK } }

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
