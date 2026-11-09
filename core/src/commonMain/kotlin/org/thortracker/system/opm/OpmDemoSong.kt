package org.thortracker.system.opm

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.opm.OpmRules.FX_AMD
import org.thortracker.system.opm.OpmRules.FX_BRIGHTNESS
import org.thortracker.system.opm.OpmRules.FX_CSM
import org.thortracker.system.opm.OpmRules.FX_FEEDBACK
import org.thortracker.system.opm.OpmRules.FX_LFO_RATE
import org.thortracker.system.opm.OpmRules.FX_LFO_WAVE
import org.thortracker.system.opm.OpmRules.FX_NOISE
import org.thortracker.system.opm.OpmRules.FX_PAN
import org.thortracker.system.opm.OpmRules.FX_PMD
import org.thortracker.system.opm.OpmRules.FX_PORTAMENTO
import org.thortracker.system.opm.OpmRules.FX_SENSITIVITY
import org.thortracker.system.opm.OpmRules.FX_TIMER_A
import org.thortracker.system.opm.OpmRules.FX_VIBRATO
import org.thortracker.system.opm.OpmRules.FX_VOLUME

/**
 * The bundled OPM demo: "Sharp Corners", an X68000-style arcade stage theme in E minor, Em-C-D-B, 6 ticks per row
 * (150 BPM). Intro, theme A, theme B, theme A again and the "Overdrive" break, looping to theme A. Every channel plays:
 * FM 1 a detuned lead (per-note brightness and feedback locks, `Hxy` taking more of the LFO on a held note, a
 * portamento run), FM 2 an octave-jumping FM bass, FM 3 an inharmonic bell built on coarse detune (DT2) panned left in
 * theme B, FM 4 and FM 5 a two-voice pad panned apart with LFO tremolo, FM 6 a swept kick, FM 7 an FM snare and FM 8
 * noise hi-hats and a noise snare (`Nxx` locks for accents and a rising roll). The header starts the shared LFO
 * (triangle, 5.72 Hz, AMD 24, PMD 20); theme B switches it to a faster saw with deeper depths and theme A restores it
 * (`Lxx`, `Wxx`, `Txx`, `Vxx`). The break turns CSM on (`X01`): released pad notes are re-keyed at timer A's rate,
 * a buzz that rises as `Qxx` raises the rate from 122 Hz to 977 Hz, until `X00` brings the drums back.
 *
 * The demo's own voices are explicit instruments in slots 01-09; the presets ([OpmPresets.ALL]) follow them.
 */
object OpmDemoSong {
    private const val SPEED = 6

    private const val LEAD_CH = 0
    private const val BASS_CH = 1
    private const val BELL_CH = 2
    private const val PAD_LO = 3
    private const val PAD_HI = 4
    private const val KICK_CH = 5
    private const val SNARE_CH = 6
    private const val NOISE_CH = OpmRules.NOISE_CHANNEL

    private const val LEAD = 1
    private const val BASS = 2
    private const val BELL = 3
    private const val PAD = 4
    private const val VOX = 5
    private const val KICK = 6
    private const val SNARE = 7
    private const val HAT = 8
    private const val NOISE_SNARE = 9

    /** Header LFO: triangle at LFRQ C8 (5.72 Hz), AMD 24, PMD 20; theme A writes the same values back. */
    private const val LFO_RATE = 0xC8
    private const val LFO_WAVE = 2
    private const val LFO_AMD = 0x18
    private const val LFO_PMD = 0x14

    private val BASS_ROOTS = listOf(n("E-2"), n("C-2"), n("D-2"), n("B-1"))

    /** The pad's two voices per bar: Em (G, B), C (G, C), D (A, D), B (F#, B). */
    private val PADS = listOf(n("G-4") to n("B-4"), n("G-4") to n("C-5"), n("A-4") to n("D-5"), n("F#4") to n("B-4"))

    private val KICK_NOTE = n("A-1")
    private val SNARE_NOTE = n("D-4")
    private val HAT_NOTE = n("C-5")
    private val NOISE_SNARE_NOTE = n("C-4")

    fun build(): Song {
        val blank = OpmSystem.blank()
        val own = listOf(lead(), bass(), bell(), pad(), vox(), kick(), snare(), hat(), noiseSnare())
        val presets = OpmPresets.ALL.map { it.instrument }.take(OpmSystem.SLOTS - own.size)
        val instruments = (own + presets).let { it + List(OpmSystem.SLOTS - it.size) { null } }
        return blank.copy(
            title = "Sharp Corners",
            artist = "Thor Tracker",
            comment = "OPM demo: X68000-style arcade theme. Shared LFO changes, a DT2 bell, noise drums on FM 8 and a CSM break. Loops to order 1.",
            ticksPerRow = SPEED,
            loopOrder = 1,
            patterns = listOf(intro(), themeA(), themeB(), overdrive()),
            orders = listOf(0, 1, 2, 1, 3),
            sound = OpmSound(
                instruments = instruments,
                lfoRate = LFO_RATE,
                lfoWave = LFO_WAVE,
                amd = LFO_AMD,
                pmd = LFO_PMD,
            ),
        )
    }

    /**
     * The shortest valid OPM song (the WAV export test's `opm-shortest.omimg`): one order of a one-row pattern at speed
     * 1, looping to order 0, holding A-4 on FM 1 with slot 01, a sustaining sine, so every pass sounds.
     */
    fun shortest(): Song {
        val base = OpmSystem.blank()
        val held = Cell(note = n("A-4"), instrument = 1)
        val tracks = List(OpmRules.CHANNEL_NAMES.size) { ch -> List(Songs.ROWS) { row -> if (ch == LEAD_CH && row == 0) held else Cell.BLANK } }
        return base.copy(
            title = "OPM shortest",
            ticksPerRow = 1,
            loopOrder = 0,
            patterns = listOf(Pattern("Pattern 00", tracks, length = 1)),
            orders = listOf(0),
            sound = OpmSound(List(OpmSystem.SLOTS) { if (it == 0) sine() else null }),
        )
    }

    // ---- instruments ------------------------------------------------------------------------------------------

    private fun op(
        mul: Int, tl: Int, ar: Int = 31, d1r: Int = 0, d1l: Int = 0, d2r: Int = 0, rr: Int = 8,
        dt1: Int = 0, dt2: Int = 0, ks: Int = 0, am: Boolean = false,
    ) = OpmOperator(dt1 = dt1, mul = mul, tl = tl, ks = ks, ar = ar, am = am, d1r = d1r, dt2 = dt2, d2r = d2r, d1l = d1l, rr = rr)

    /** An operator kept silent (level 127). */
    private fun silent() = op(1, 127, rr = 15)

    /** Algorithm 7 with only operator 4 heard, held at full level while keyed. */
    private fun sine() = OpmInstrument("Sine", algorithm = 7, operators = listOf(silent(), silent(), silent(), op(1, 0, rr = 6)))

    /** Two detuned 2-op stacks (algorithm 4) with feedback: a bright, chorused saw-like lead; a little LFO vibrato (PMS 2). */
    private fun lead() = OpmInstrument("Sharp lead", algorithm = 4, feedback = 6, pms = 2, operators = listOf(
        op(1, 28, d1r = 8, d1l = 3, d2r = 2, rr = 8, dt1 = 3),
        op(1, 6, d1r = 4, d1l = 2, d2r = 1, rr = 7),
        op(2, 36, ar = 28, d1r = 10, d1l = 4, d2r = 2, rr = 8, dt1 = 7),
        op(1, 10, d1r = 4, d1l = 2, d2r = 1, rr = 7, dt1 = 3),
    ))

    /** A four-operator chain (algorithm 0) starting an octave down: the plucked arcade bass. */
    private fun bass() = OpmInstrument("Chain bass", algorithm = 0, feedback = 5, operators = listOf(
        op(0, 30, d1r = 12, d1l = 6, rr = 10),
        op(1, 32, d1r = 14, d1l = 8, rr = 10),
        op(1, 24, d1r = 10, d1l = 5, rr = 10),
        op(1, 2, d1r = 6, d1l = 4, d2r = 3, rr = 10, ks = 1),
    ))

    /** Modulators at ×3 with DT2 2 (×1.57) and ×5 with DT2 3 (×1.73): inharmonic partials, a struck metal bar. */
    private fun bell() = OpmInstrument("DT2 bell", algorithm = 4, operators = listOf(
        op(3, 34, d1r = 9, d1l = 8, d2r = 5, rr = 5, dt2 = 2),
        op(1, 4, d1r = 5, d1l = 6, d2r = 3, rr = 4),
        op(5, 42, d1r = 11, d1l = 9, d2r = 6, rr = 5, dt1 = 2, dt2 = 3),
        op(2, 14, d1r = 6, d1l = 7, d2r = 4, rr = 4, ks = 1),
    ))

    /** One modulator into three detuned carriers (algorithm 5), slow attack, LFO tremolo on every carrier (AMS 1). */
    private fun pad() = OpmInstrument("Wide pad", algorithm = 5, feedback = 3, pms = 1, ams = 1, operators = listOf(
        op(1, 38, ar = 18, rr = 6),
        op(1, 18, ar = 16, d1r = 2, d1l = 2, rr = 6, dt1 = 3, am = true),
        op(2, 24, ar = 16, d1r = 2, d1l = 2, rr = 6, dt1 = 7, am = true),
        op(1, 20, ar = 17, d1r = 2, d1l = 2, rr = 6, am = true),
    ))

    /** Instant attack, fast decay to silence: each CSM re-key is a short formant pulse, so the timer rate becomes a pitch. */
    private fun vox() = OpmInstrument("CSM vox", algorithm = 4, feedback = 4, operators = listOf(
        op(1, 22, d1r = 18, d1l = 15, rr = 15),
        op(1, 8, d1r = 20, d1l = 15, rr = 15),
        op(3, 30, d1r = 18, d1l = 15, rr = 15),
        op(2, 16, d1r = 22, d1l = 15, rr = 15),
    ))

    /** One 2-op stack sweeping down two octaves onto the note. */
    private fun kick() = OpmInstrument("Sweep kick", algorithm = 4, operators = listOf(
        op(1, 30, d1r = 20, d1l = 15, rr = 15),
        op(1, 0, d1r = 12, d1l = 15, rr = 12),
        silent(),
        silent(),
    ), sweep = OpmSweep(start = 24, end = 0, speed = 48))

    /** Full feedback on a ×3 modulator for grit, with a short downward pitch drop. */
    private fun snare() = OpmInstrument("FM snare", algorithm = 4, feedback = 7, operators = listOf(
        op(3, 18, d1r = 16, d1l = 15, rr = 14),
        op(1, 4, d1r = 13, d1l = 15, rr = 13),
        silent(),
        silent(),
    ), sweep = OpmSweep(start = 7, end = 0, speed = 32))

    /** Operator 4 as the noise generator at NFRQ 28 (31.3 kHz), a short tick. FM 8 only. */
    private fun hat() = OpmInstrument("Noise hat", algorithm = 7, operators = listOf(
        silent(),
        silent(),
        silent(),
        op(1, 6, d1r = 18, d1l = 15, rr = 15),
    ), noise = true, noiseFrequency = 28)

    /** Noise at NFRQ 20 (10.4 kHz) over a swept sine body on operator 1. FM 8 only. */
    private fun noiseSnare() = OpmInstrument("Noise snare", algorithm = 7, operators = listOf(
        op(1, 20, d1r = 14, d1l = 15, rr = 13),
        silent(),
        silent(),
        op(1, 2, d1r = 12, d1l = 15, rr = 12),
    ), noise = true, noiseFrequency = 20, sweep = OpmSweep(start = 5, end = 0, speed = 24))

    // ---- patterns ---------------------------------------------------------------------------------------------

    private fun intro() = PatternBuilder("Intro").apply {
        // Pads apart: FM 4 left, FM 5 right (pan stays until changed).
        fx(PAD_LO, 0, 1, FX_PAN, 0x10)
        fx(PAD_HI, 0, 1, FX_PAN, 0x01)
        for (bar in 0 until 4) {
            val r = bar * 16
            bass(bar)
            pads(bar, PAD)
            note(KICK_CH, r, KICK_NOTE, KICK)
            if (bar >= 2) note(KICK_CH, r + 8, KICK_NOTE, KICK)
            if (bar >= 1) for (row in listOf(2, 6, 10, 14)) note(NOISE_CH, r + row, HAT_NOTE, HAT)
        }
        // A rising FM snare run and the lead's pickup into theme A.
        for ((row, name) in listOf(56 to "D-4", 58 to "E-4", 60 to "F#4", 62 to "A-4")) note(SNARE_CH, row, n(name), SNARE)
        note(LEAD_CH, 60, n("B-4"), LEAD)
    }.build()

    private fun themeA() = PatternBuilder("Theme A").apply {
        for (bar in 0 until 4) { bass(bar); pads(bar, PAD); drums(bar) }
        // The header's LFO again (theme B changed it): triangle 5.72 Hz, AMD 24, PMD 20.
        fx(PAD_LO, 0, 0, FX_LFO_RATE, LFO_RATE)
        fx(PAD_LO, 0, 2, FX_LFO_WAVE, LFO_WAVE)
        fx(PAD_HI, 0, 0, FX_AMD, LFO_AMD)
        fx(PAD_HI, 0, 2, FX_PMD, LFO_PMD)
        lead(listOf(0 to "E-5", 4 to "G-5", 6 to "A-5", 8 to "B-5", 12 to "A-5", 14 to "G-5",
            16 to "E-5", 20 to "G-5", 24 to "C-6", 28 to "B-5",
            32 to "A-5", 36 to "F#5", 38 to "G-5", 40 to "A-5", 44 to "D-6",
            48 to "D#6", 52 to "B-5", 56 to "F#5"))
        fx(LEAD_CH, 0, 0, FX_VIBRATO, 0x32)
        // Locks for one note each: a brighter top note, full feedback on the climb.
        fx(LEAD_CH, 24, 0, FX_BRIGHTNESS, 0x6A)
        fx(LEAD_CH, 44, 0, FX_FEEDBACK, 7)
        cut(LEAD_CH, 60)
    }.build()

    private fun themeB() = PatternBuilder("Theme B").apply {
        for (bar in 0 until 4) { bass(bar); pads(bar, PAD); drums(bar) }
        // A faster saw LFO with deeper tremolo and vibrato for the whole chip.
        fx(PAD_LO, 0, 0, FX_LFO_WAVE, 0)
        fx(PAD_LO, 0, 2, FX_LFO_RATE, 0xD4)
        fx(PAD_HI, 0, 0, FX_AMD, 0x30)
        fx(PAD_HI, 0, 2, FX_PMD, 0x28)
        lead(listOf(0 to "B-5", 8 to "A-5", 10 to "G-5", 12 to "E-5",
            16 to "G-5", 20 to "C-6", 24 to "E-6", 28 to "D-6",
            32 to "D-6", 36 to "C-6", 40 to "B-5", 44 to "A-5", 48 to "B-5"))
        // The held first note takes much more of the LFO's pitch (PMS 5); the next note goes back to the lead's PMS 2.
        fx(LEAD_CH, 0, 0, FX_SENSITIVITY, 0x50)
        fx(LEAD_CH, 24, 0, FX_BRIGHTNESS, 0x70)
        // A portamento run up to F#6 without new attacks, ended with the OFF.
        note(LEAD_CH, 52, n("D#6"), 0)
        fx(LEAD_CH, 52, 0, FX_PORTAMENTO, 0x20)
        note(LEAD_CH, 56, n("F#6"), 0)
        cut(LEAD_CH, 60)
        fx(LEAD_CH, 60, 0, FX_PORTAMENTO, 0x00)
        // The DT2 bell answers on the off-beats, on the left (full volume again after the break's C00).
        for ((row, name) in listOf(2 to "B-5", 10 to "G-5", 18 to "E-6", 26 to "C-6", 34 to "F#6", 42 to "D-6", 50 to "D#6", 58 to "B-5")) {
            note(BELL_CH, row, n(name), BELL)
        }
        fx(BELL_CH, 2, 0, FX_VOLUME, 0x7F)
        fx(BELL_CH, 2, 1, FX_PAN, 0x10)
    }.build()

    /**
     * The CSM break: drums and bell silenced (`C00`, so CSM's re-keys of their released notes stay quiet), short pad
     * notes released two rows after they start and re-keyed by CSM at timer A's rate, which `Qxx` raises; `X00` at
     * row 48 ends it and the drums come back with a noise-snare roll.
     */
    private fun overdrive() = PatternBuilder("Overdrive").apply {
        for (ch in listOf(BELL_CH, KICK_CH, SNARE_CH, NOISE_CH)) fx(ch, 0, 0, FX_VOLUME, 0x00)
        fx(PAD_LO, 0, 0, FX_CSM, 1)
        for ((row, q) in listOf(0 to 0x80, 8 to 0xA0, 16 to 0xC0, 24 to 0xD0, 32 to 0xE0, 40 to 0xE8, 44 to 0xF0)) fx(PAD_LO, row, 1, FX_TIMER_A, q)
        for ((row, chord) in listOf(0 to (n("E-4") to n("B-4")), 16 to (n("C-4") to n("G-4")), 32 to (n("D-4") to n("A-4")))) {
            note(PAD_LO, row, chord.first, VOX)
            note(PAD_HI, row, chord.second, VOX)
            cut(PAD_LO, row + 2)
            cut(PAD_HI, row + 2)
        }
        fx(PAD_LO, 48, 0, FX_CSM, 0)
        note(PAD_LO, 48, PADS[3].first, PAD)
        note(PAD_HI, 48, PADS[3].second, PAD)
        cut(PAD_LO, 62)
        cut(PAD_HI, 62)
        for (bar in 0 until 4) {
            val root = BASS_ROOTS[bar]
            for ((row, offset) in listOf(0 to 0, 4 to 12, 8 to 0, 12 to 12)) note(BASS_CH, bar * 16 + row, root + offset, BASS)
        }
        lead(listOf(0 to "B-5", 16 to "C-6", 32 to "D-6", 48 to "D#6", 56 to "F#6"))
        fx(LEAD_CH, 0, 0, FX_VIBRATO, 0x46)
        cut(LEAD_CH, 60)
        // The drums return: kick, a snare fill and a noise-snare roll whose noise rises (Nxx locks).
        note(KICK_CH, 48, KICK_NOTE, KICK)
        fx(KICK_CH, 48, 0, FX_VOLUME, 0x7F)
        note(KICK_CH, 56, KICK_NOTE, KICK)
        for ((row, name) in listOf(52 to "D-4", 56 to "F#4", 60 to "A-4")) note(SNARE_CH, row, n(name), SNARE)
        fx(SNARE_CH, 52, 0, FX_VOLUME, 0x7F)
        for (i in 0 until 8) {
            val row = 48 + 2 * i
            note(NOISE_CH, row, NOISE_SNARE_NOTE, NOISE_SNARE)
            fx(NOISE_CH, row, 1, FX_NOISE, 0x10 + 2 * i)
        }
        fx(NOISE_CH, 48, 0, FX_VOLUME, 0x7F)
    }.build()

    /** Root, octave, root, octave … with the fifth before the bar line: sixteenth-note arcade bass. */
    private fun PatternBuilder.bass(bar: Int) {
        val r = bar * 16
        val root = BASS_ROOTS[bar]
        for ((row, offset) in listOf(0 to 0, 2 to 12, 4 to 0, 6 to 12, 8 to 0, 10 to 12, 12 to 7, 14 to 12)) note(BASS_CH, r + row, root + offset, BASS)
    }

    /** The bar's two pad voices, released before the next bar. */
    private fun PatternBuilder.pads(bar: Int, instrument: Int) {
        val r = bar * 16
        note(PAD_LO, r, PADS[bar].first, instrument)
        note(PAD_HI, r, PADS[bar].second, instrument)
        cut(PAD_LO, r + 14)
        cut(PAD_HI, r + 14)
    }

    /** Kick, FM snare layered with the noise snare on 2 and 4, noise hats in eighths (accent and open-ish via `Nxx`). */
    private fun PatternBuilder.drums(bar: Int) {
        val r = bar * 16
        note(KICK_CH, r, KICK_NOTE, KICK)
        note(KICK_CH, r + 8, KICK_NOTE, KICK)
        if (bar % 2 == 1) note(KICK_CH, r + 10, KICK_NOTE, KICK)
        for (row in listOf(4, 12)) {
            note(SNARE_CH, r + row, SNARE_NOTE, SNARE)
            note(NOISE_CH, r + row, NOISE_SNARE_NOTE, NOISE_SNARE)
        }
        for (row in listOf(0, 2, 6, 8, 10, 14)) note(NOISE_CH, r + row, HAT_NOTE, HAT)
        fx(NOISE_CH, r, 0, FX_NOISE, 0x1F)
        fx(NOISE_CH, r + 14, 0, FX_NOISE, 0x18)
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
        private val tracks = List(OpmRules.CHANNEL_NAMES.size) { Array(Songs.ROWS) { Cell.BLANK } }

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
