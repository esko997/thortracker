package org.thortracker.system.cbm

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.cbm.CbmRules.FX_ARPEGGIO
import org.thortracker.system.cbm.CbmRules.FX_CUT
import org.thortracker.system.cbm.CbmRules.FX_PORTAMENTO
import org.thortracker.system.cbm.CbmRules.FX_TIMER
import org.thortracker.system.cbm.CbmRules.FX_VIBRATO
import org.thortracker.system.cbm.CbmRules.FX_VOLUME
import org.thortracker.system.cbm.CbmRules.FX_VOLUME_SLIDE
import org.thortracker.system.cbm.CbmRules.FX_WAVE

/**
 * The bundled VIC-20 and C16/Plus/4 demos (original songs) and the shortest valid song of each chip (the WAV export
 * test's `vic-shortest.cbimg` / `ted-shortest.cbimg`).
 *
 * **"Kernal Lights"** (VIC-20, PAL, A minor, Am-F-C-G, 6 ticks per row): Intro, Theme A, Theme B, Breakdown, looping
 * to Theme A. Every lane plays:
 *
 * - Bass: gated eighths with octave jumps.
 * - Alto: in the intro a slow swell whose volume envelope **owns the master volume**, so each bar the whole mix swells
 *   in with it; then plucked chord arpeggios (`0xy`, gated by a wave envelope: the VIC has no per-voice volume).
 * - Soprano: the lead with a delayed vibrato, a portamento run (`3xx`) and vibrato (`4xy`) in Theme B; in the
 *   breakdown a note whose released volume envelope fades the master volume (and every voice) to silence.
 * - Noise: hats, snares and kicks; the note picks the noise rate, arpeggio envelopes sweep it.
 *
 * The breakdown drops the master volume (`V06`) and slides it back up (`A20`).
 *
 * **"Plus Four Parade"** (C16/Plus/4, PAL, D minor, Dm-B♭-F-C, 6 ticks per row): Intro, Theme twice, Drum Break,
 * looping to the Theme. Voice 1 plays a swelling chord arpeggio (its volume envelope owns the master volume) and then
 * the lead; Voice 2 the bass, its noise hats (`W02` on a square sound, cut by `S02`) and a noise snare whose wave
 * envelope switches from a square click to noise. In the drum break both voices stop and the DAC lane plays the starter
 * kit through the TED's D/A mode, `Txx` speeding the timer up (the kit pitched up) and slowing it down for a tom fill.
 */
object CbmDemoSong {
    private const val SPEED = 6

    // ---- VIC-20 ---------------------------------------------------------------------------------------------------

    private const val BASS_CH = 0
    private const val ALTO_CH = 1
    private const val SOPRANO_CH = 2
    private const val NOISE_CH = 3

    private const val V_LEAD = 1
    private const val V_PLUCK = 2
    private const val V_BASS = 3
    private const val V_SWELL = 4
    private const val V_FADE = 5
    private const val V_HAT = 6
    private const val V_SNARE = 7
    private const val V_KICK = 8
    private const val V_CRASH = 9

    /** Noise lane notes: rate indices (higher = brighter). */
    private const val HAT_RATE = 118
    private const val SNARE_RATE = 100
    private const val KICK_RATE = 70
    private const val CRASH_RATE = 110

    private val VIC_BASS = listOf(n("A-1"), n("F-1"), n("C-2"), n("G-1"))
    private val VIC_CHORDS = listOf(n("A-3") to 0x37, n("F-3") to 0x47, n("C-4") to 0x47, n("G-3") to 0x47)

    fun vic(): Song {
        val own = listOf(
            CbmInstrument("Raster lead", pitch = env(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 6, 12, 6, 0, -6, -12, -6, 0, loop = 10)),
            CbmInstrument("Glass pluck", waves = gate(8)),
            CbmInstrument("Gated bass", waves = gate(5)),
            // 3 → 15 in steps of two, four ticks each: about five rows of swell for every voice.
            CbmInstrument("Dawn swell", volume = CbmEnvelope(listOf(3, 5, 7, 9, 11, 13, 15).flatMap { v -> List(4) { v } })),
            CbmInstrument("Lights out", volume = env(15, 13, 11, 9, 7, 5, 3, 1, 0, release = 1)),
            CbmInstrument("Noise hat", waves = gate(2)),
            CbmInstrument("Noise snare", arpeggio = env(0, -6, -10, -14, -18), waves = gate(6)),
            CbmInstrument("Noise kick", arpeggio = env(0, -24, -40, -56), waves = gate(4)),
            CbmInstrument("Noise crash", arpeggio = env(0, -2, -4, -6, -8, -10, -12, -14), waves = gate(24)),
        )
        val presets = CbmPresets.VIC.map { it.instrument }
        return VicSystem.blank().copy(
            title = "Kernal Lights",
            artist = "Thor Tracker",
            comment = "VIC-20 demo: three gated square voices and the noise voice, a swell that owns the chip's one " +
                "master volume, V/A master volume moves and a released fade-out. Loops to order 1.",
            ticksPerRow = SPEED,
            loopOrder = 1,
            patterns = listOf(vicIntro(), vicThemeA(), vicThemeB(), vicBreakdown()),
            orders = listOf(0, 1, 2, 3),
            sound = CbmSound(CbmChip.VIC, slots(own + presets)),
        )
    }

    private fun vicIntro() = PatternBuilder("Intro", 4).apply {
        for (bar in 0 until 4) {
            vicBass(bar)
            // Each bar's chord swells in, and with it the whole mix (the swell owns the master volume).
            val (root, arp) = VIC_CHORDS[bar]
            note(ALTO_CH, bar * 16, root, V_SWELL)
            fx(ALTO_CH, bar * 16, 0, FX_ARPEGGIO, arp)
            if (bar >= 2) vicHats(bar)
        }
        for (row in listOf(56, 58, 60, 62)) note(NOISE_CH, row, SNARE_RATE, V_SNARE)
        cut(ALTO_CH, 60)
    }.build()

    private fun vicThemeA() = PatternBuilder("Theme A", 4).apply {
        for (bar in 0 until 4) { vicBass(bar); vicArps(bar); vicDrums(bar) }
        note(NOISE_CH, 0, CRASH_RATE, V_CRASH)
        lead(SOPRANO_CH, V_LEAD, listOf(0 to "E-5", 4 to "A-5", 6 to "G-5", 8 to "E-5", 12 to "C-5", 14 to "D-5",
            16 to "C-5", 20 to "A-4", 24 to "F-4", 28 to "A-4",
            32 to "G-4", 36 to "C-5", 38 to "D-5", 40 to "E-5", 44 to "G-5",
            48 to "D-5", 52 to "B-4", 56 to "G-4"))
        cut(SOPRANO_CH, 60)
    }.build()

    private fun vicThemeB() = PatternBuilder("Theme B", 4).apply {
        for (bar in 0 until 4) { vicBass(bar); vicArps(bar); vicDrums(bar) }
        note(NOISE_CH, 0, CRASH_RATE, V_CRASH)
        lead(SOPRANO_CH, V_LEAD, listOf(0 to "A-5", 8 to "G-5", 10 to "E-5", 12 to "C-5",
            16 to "F-5", 20 to "E-5", 24 to "C-5", 28 to "A-4",
            32 to "C-5", 36 to "E-5", 40 to "G-5", 44 to "E-5"))
        fx(SOPRANO_CH, 0, 0, FX_VIBRATO, 0x23)
        // A portamento run up to B-5 without restarting the note.
        note(SOPRANO_CH, 48, n("D-5"), V_LEAD)
        note(SOPRANO_CH, 52, n("G-5"), 0)
        fx(SOPRANO_CH, 52, 0, FX_PORTAMENTO, 0x20)
        note(SOPRANO_CH, 56, n("B-5"), 0)
        cut(SOPRANO_CH, 62)
        fx(SOPRANO_CH, 62, 0, FX_PORTAMENTO, 0x00)
        fx(SOPRANO_CH, 62, 1, FX_VIBRATO, 0x00)
    }.build()

    /**
     * Bass and kicks only: the master volume drops to 6, slides back up from row 32 (`A20`: two eighths of a level per
     * tick), and at row 48 the soprano's "Lights out" takes the master volume; its OFF at row 56 releases the envelope,
     * fading every voice to silence before the loop.
     */
    private fun vicBreakdown() = PatternBuilder("Breakdown", 4).apply {
        fx(NOISE_CH, 0, 1, FX_VOLUME, 0x06)
        fx(NOISE_CH, 32, 1, FX_VOLUME_SLIDE, 0x20)
        fx(NOISE_CH, 47, 1, FX_VOLUME_SLIDE, 0x00)
        for (bar in 0 until 4) {
            note(BASS_CH, bar * 16, VIC_BASS[bar], V_BASS)
            note(BASS_CH, bar * 16 + 8, VIC_BASS[bar] + 12, V_BASS)
            note(NOISE_CH, bar * 16, KICK_RATE, V_KICK)
            if (bar >= 2) note(NOISE_CH, bar * 16 + 8, KICK_RATE, V_KICK)
        }
        note(SOPRANO_CH, 48, n("A-5"), V_FADE)
        cut(SOPRANO_CH, 56)
    }.build()

    private fun PatternBuilder.vicBass(bar: Int) {
        val root = VIC_BASS[bar]
        for ((row, offset) in listOf(0 to 0, 4 to 12, 6 to 0, 8 to 0, 12 to 12, 14 to 7)) note(BASS_CH, bar * 16 + row, root + offset, V_BASS)
    }

    private fun PatternBuilder.vicArps(bar: Int) {
        val (root, arp) = VIC_CHORDS[bar]
        for (row in listOf(0, 8)) {
            note(ALTO_CH, bar * 16 + row, root, V_PLUCK)
            fx(ALTO_CH, bar * 16 + row, 0, FX_ARPEGGIO, arp)
        }
    }

    private fun PatternBuilder.vicHats(bar: Int) {
        for (row in listOf(2, 6, 10, 14)) note(NOISE_CH, bar * 16 + row, HAT_RATE, V_HAT)
    }

    private fun PatternBuilder.vicDrums(bar: Int) {
        val r = bar * 16
        if (bar > 0) note(NOISE_CH, r, KICK_RATE, V_KICK)
        note(NOISE_CH, r + 4, SNARE_RATE, V_SNARE)
        note(NOISE_CH, r + 8, KICK_RATE, V_KICK)
        note(NOISE_CH, r + 12, SNARE_RATE, V_SNARE)
        vicHats(bar)
    }

    // ---- C16 / Plus/4 ---------------------------------------------------------------------------------------------

    private const val VOICE1_CH = 0
    private const val VOICE2_CH = 1
    private const val DAC_CH = 2

    private const val T_LEAD = 1
    private const val T_PLUCK = 2
    private const val T_BASS = 3
    private const val T_TICK = 4
    private const val T_SNARE = 5
    private const val T_SWELL = 6
    private const val T_KICK = 7
    private const val T_DSNARE = 8
    private const val T_HAT = 9
    private const val T_TOM = 10

    private val TED_BASS = listOf(n("D-3"), n("A#2"), n("F-3"), n("C-3"))
    private val TED_CHORDS = listOf(n("D-4") to 0x37, n("A#3") to 0x47, n("F-4") to 0x47, n("C-4") to 0x47)

    /** Voice 2's noise notes: the noise steps with voice 2's period, so the note sets its colour. */
    private val TICK_NOTE = n("C-8")
    private val SNARE_NOTE = n("A-6")

    /** The DAC kit's root: the samples play at the DAC rate there. */
    private val DAC_NOTE = n("C-4")

    fun ted(): Song {
        val own = listOf(
            CbmInstrument("Parade lead", pitch = env(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 6, 12, 6, 0, -6, -12, -6, 0, loop = 10)),
            CbmInstrument("Pluck", waves = gate(8)),
            CbmInstrument("Gated bass", waves = gate(5)),
            // A plain square: W02 turns it into voice 2's noise, S02 cuts it short.
            CbmInstrument("Tick"),
            CbmInstrument("Noise snare", wave = 2, arpeggio = env(0, -5, -9, -12, -14, -16), waves = env(1, 2, 2, 2, 2, 2, 0)),
            CbmInstrument("Parade swell", volume = CbmEnvelope(listOf(2, 3, 4, 5, 6, 7, 8).flatMap { v -> List(4) { v } })),
            CbmStarterKit.instrument(CbmStarterKit.KICK, "DAC kick"),
            CbmStarterKit.instrument(CbmStarterKit.SNARE, "DAC snare"),
            CbmStarterKit.instrument(CbmStarterKit.HAT, "DAC hat"),
            CbmStarterKit.instrument(CbmStarterKit.TOM, "DAC tom"),
        )
        val presets = CbmPresets.TED.map { it.instrument }
        return TedSystem.blank().copy(
            title = "Plus Four Parade",
            artist = "Thor Tracker",
            comment = "C16/Plus/4 demo: two square voices, voice 2's noise by W02 and by a wave envelope, a swell that " +
                "owns the TED's one volume, and a drum break on the DAC (D/A mode) with Txx rate changes. Loops to order 1.",
            ticksPerRow = SPEED,
            loopOrder = 1,
            patterns = listOf(tedIntro(), tedTheme(), tedBreak()),
            orders = listOf(0, 1, 1, 2),
            sound = CbmSound(CbmChip.TED, slots(own + presets), samples = CbmStarterKit.samples()),
        )
    }

    private fun tedIntro() = PatternBuilder("Intro", 3).apply {
        for (bar in 0 until 4) {
            val (root, arp) = TED_CHORDS[bar]
            note(VOICE1_CH, bar * 16, root, T_SWELL)
            fx(VOICE1_CH, bar * 16, 0, FX_ARPEGGIO, arp)
            note(VOICE2_CH, bar * 16, TED_BASS[bar], T_BASS)
            note(VOICE2_CH, bar * 16 + 8, TED_BASS[bar], T_BASS)
            if (bar >= 2) for (row in listOf(4, 12)) tick(bar * 16 + row)
        }
        cut(VOICE1_CH, 60)
    }.build()

    private fun tedTheme() = PatternBuilder("Theme", 3).apply {
        for (bar in 0 until 4) {
            val r = bar * 16
            note(VOICE2_CH, r, TED_BASS[bar], T_BASS)
            note(VOICE2_CH, r + 8, TED_BASS[bar] + 12, T_BASS)
            for (row in listOf(2, 6, 10, 14)) tick(r + row)
            for (row in listOf(4, 12)) note(VOICE2_CH, r + row, SNARE_NOTE, T_SNARE)
        }
        lead(VOICE1_CH, T_LEAD, listOf(0 to "A-4", 4 to "D-5", 6 to "C-5", 8 to "A-4", 12 to "F-4", 14 to "G-4",
            16 to "F-4", 20 to "D-4", 24 to "A#3", 28 to "D-4",
            32 to "C-4", 36 to "F-4", 38 to "G-4", 40 to "A-4", 44 to "C-5",
            48 to "G-4", 52 to "E-4", 56 to "C-4"))
        cut(VOICE1_CH, 60)
    }.build()

    /**
     * Both voices stop; the DAC plays the kit: at the default rate (T0C), pitched up by a faster timer (T08) in the
     * third bar, a tom fill on a slower one (T10), the default restored at the end and the DAC stopped before the loop.
     */
    private fun tedBreak() = PatternBuilder("Drum Break", 3).apply {
        cut(VOICE1_CH, 0)
        cut(VOICE2_CH, 0)
        fx(DAC_CH, 0, 0, FX_TIMER, 0x0C)
        for (bar in 0 until 3) {
            val r = bar * 16
            for ((row, slot) in listOf(0 to T_KICK, 4 to T_DSNARE, 6 to T_HAT, 8 to T_KICK, 10 to T_KICK, 12 to T_DSNARE, 14 to T_HAT)) {
                note(DAC_CH, r + row, DAC_NOTE, slot)
            }
        }
        fx(DAC_CH, 32, 1, FX_TIMER, 0x08)
        fx(DAC_CH, 48, 1, FX_TIMER, 0x10)
        for ((row, name) in listOf(48 to "C-4", 52 to "A-3", 56 to "F-3")) note(DAC_CH, row, n(name), T_TOM)
        fx(DAC_CH, 60, 0, FX_TIMER, 0x0C)
        cut(DAC_CH, 62)
    }.build()

    /** Voice 2's noise tick: a plain square sound switched to noise (`W02`) and cut after two ticks (`S02`). */
    private fun PatternBuilder.tick(row: Int) {
        note(VOICE2_CH, row, TICK_NOTE, T_TICK)
        fx(VOICE2_CH, row, 0, FX_WAVE, 0x02)
        fx(VOICE2_CH, row, 1, FX_CUT, 0x02)
    }

    // ---- shortest songs -----------------------------------------------------------------------------------------

    /**
     * The shortest valid song of [chip] (the WAV export test's `*-shortest.cbimg`): one order of a one-row pattern at
     * speed 6, PAL, looping to order 0, holding A-4 with slot 01 (a plain square) on the VIC's Soprano or the TED's
     * Voice 1, so every pass sounds.
     */
    fun shortest(chip: CbmChip): Song {
        val system = chip.system
        val lane = if (chip == CbmChip.VIC) SOPRANO_CH else VOICE1_CH
        val held = Cell(note = n("A-4"), instrument = 1)
        val tracks = List(chip.channels) { ch -> List(Songs.ROWS) { row -> if (ch == lane && row == 0) held else Cell.BLANK } }
        return system.blank().copy(
            title = "${if (chip == CbmChip.VIC) "VIC-20" else "C16"} shortest",
            ticksPerRow = SPEED,
            loopOrder = 0,
            patterns = listOf(Pattern("Pattern 00", tracks, length = 1)),
            orders = listOf(0),
            sound = CbmSound(chip, List(CbmSystem.SLOTS) { if (it == 0) CbmInstrument("Square") else null }),
        )
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private fun slots(list: List<CbmInstrument>): List<CbmInstrument?> =
        list.take(CbmSystem.SLOTS).let { it + List(CbmSystem.SLOTS - it.size) { null } }

    private fun env(vararg values: Int, loop: Int = -1, release: Int = -1) = CbmEnvelope(values.toList(), loop, release)

    /** [n] ticks of square, then off: a wave envelope that gates a voice. */
    private fun gate(n: Int) = CbmEnvelope(List(n) { 1 } + 0)

    private fun PatternBuilder.lead(channel: Int, slot: Int, notes: List<Pair<Int, String>>) {
        for ((row, name) in notes) note(channel, row, n(name), slot)
    }

    private fun n(name: String): Int {
        val index = listOf("C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-").indexOf(name.substring(0, 2))
        require(index >= 0) { "bad note $name" }
        return name.substring(2).toInt() * 12 + index
    }

    private class PatternBuilder(val title: String, lanes: Int) {
        private val tracks = List(lanes) { Array(Songs.ROWS) { Cell.BLANK } }

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
