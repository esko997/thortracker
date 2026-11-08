package org.thortracker.system.nes

import org.thortracker.model.Song
import org.thortracker.system.nes.NesDemoSong.PatternBuilder
import org.thortracker.system.nes.NesDemoSong.n

/**
 * The bundled NES + VRC6 demo: "Saw Sunrise", D minor, speed 6. Intro, theme and bridge, arranged 0-1-2-1 and looping
 * to order 1, in the style of the VRC6's best-known soundtracks: the saw sings the theme (Full saw volume, volume up
 * to 10 so the accumulator stays clean) and plays the bridge's bass (Half), a VRC6 pulse sweeps its duty through all
 * eight steps under it while the other plays thin 1/16 arpeggio chords (Vxx widening them), and the 2A03 keeps the
 * echo, triangle bass, noise drums and the DPCM starter kit.
 */
internal object NesVrc6DemoSong {
    private const val ECHO = 1
    private const val BASS = 2
    private const val KICK = 3
    private const val SNARE = 4
    private const val HAT = 5
    private const val DRUM_KIT = 6
    private const val SAW_LEAD = 7
    private const val SAW_BASS = 8
    private const val PWM = 9
    private const val THIN = 10

    private const val PULSE1 = 0
    private const val TRIANGLE = NesRules.TRIANGLE
    private const val NOISE = NesRules.NOISE
    private const val DPCM = NesRules.DPCM
    private const val V_PULSE1 = NesRules.VRC6_PULSE1
    private const val V_PULSE2 = NesRules.VRC6_PULSE2
    private const val SAW = NesRules.VRC6_SAW
    private const val CHANNELS = 8

    /** Theme chords Dm Bb C A: arpeggio root and 0xy, the triangle's root (sounds an octave lower), a long pad note. */
    private val THEME_ROOTS = intArrayOf(n("D-4"), n("A#3"), n("C-4"), n("A-3"))
    private val THEME_ARPS = intArrayOf(0x37, 0x47, 0x47, 0x47)
    private val THEME_BASS = intArrayOf(n("D-3"), n("A#2"), n("C-3"), n("A-2"))
    private val THEME_PAD = intArrayOf(n("F-4"), n("D-4"), n("E-4"), n("E-4"))

    /** Bridge chords Bb C Dm A: the saw's bass roots. */
    private val BRIDGE_BASS = intArrayOf(n("A#2"), n("C-3"), n("D-3"), n("A-2"))

    fun build(): Song {
        val base = NesVrc6System.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[ECHO - 1] = NesInstrument(name = "Echo 25%", volume = NesEnvelope(listOf(9, 8, 6, 5, 4, 3, 2, 1, 0)), duty = NesEnvelope(listOf(1)))
        bank[BASS - 1] = NesInstrument(name = "Triangle bass")
        bank[KICK - 1] = NesInstrument(
            name = "Kick",
            volume = NesEnvelope(listOf(15, 13, 10, 7, 4, 2, 0)),
            arpeggio = NesEnvelope(listOf(0, -1, -2, -3, -4, -5, -6)),
        )
        bank[SNARE - 1] = NesInstrument(name = "Snare", volume = NesEnvelope(listOf(14, 11, 9, 7, 6, 5, 4, 3, 2, 1, 0)), arpeggio = NesEnvelope(listOf(2, 0)))
        bank[HAT - 1] = NesInstrument(name = "Closed hat", volume = NesEnvelope(listOf(8, 5, 3, 1, 0)))
        bank[DRUM_KIT - 1] = DpcmStarterKit.instrument(0)
        bank[SAW_LEAD - 1] = NesInstrument(
            name = "Saw lead (Full)",
            volume = NesEnvelope(listOf(10, 10, 9, 9, 8, 8, 6, 5, 4, 3, 2, 1, 0), loop = 4, release = 6),
            chip = NesChip.VRC6,
            sawVolume = Vrc6SawVolume.FULL,
        )
        bank[SAW_BASS - 1] = NesInstrument(
            name = "Saw bass (Half)",
            volume = NesEnvelope(listOf(15, 14, 13, 12, 11, 10)),
            chip = NesChip.VRC6,
        )
        bank[PWM - 1] = NesInstrument(
            name = "VRC6 PWM sweep",
            volume = NesEnvelope(listOf(11, 10, 9, 9, 8, 8, 7, 7, 6, 6, 5, 5, 4), loop = 12),
            duty = NesEnvelope(listOf(0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 6, 6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1), loop = 0),
            chip = NesChip.VRC6,
        )
        bank[THIN - 1] = NesInstrument(
            name = "VRC6 chord 1/16",
            volume = NesEnvelope(listOf(9, 9, 8, 8, 7, 7, 6, 6, 5, 5, 4)),
            duty = NesEnvelope(listOf(0)),
            chip = NesChip.VRC6,
        )
        return base.copy(
            title = "Saw Sunrise",
            artist = "Thor Tracker",
            comment = "NES + VRC6 demo: saw lead and saw bass, VRC6 duty sweep and 1/16 chords, 2A03 echo, bass and drums. Loops to order 1.",
            ticksPerRow = 6,
            loopOrder = 1,
            patterns = listOf(intro(), theme(), bridge()),
            orders = listOf(0, 1, 2, 1),
            sound = base.nes.copy(instruments = bank, samples = DpcmStarterKit.samples()),
        )
    }

    private fun intro() = PatternBuilder("Intro", CHANNELS).apply {
        for (bar in 0 until 4) {
            val r = bar * 16
            // VRC6 Pulse 1: the chord broken in eighths, its duty sweeping through all eight steps.
            for (step in 0 until 8) {
                val offset = intArrayOf(0, 7, 12, 7, 3, 7, 12, 15)[step].let { if (THEME_ARPS[bar] == 0x47 && it % 12 == 3) it + 1 else it }
                note(V_PULSE1, r + step * 2, THEME_ROOTS[bar] + offset, PWM)
            }
            if (bar >= 2) bass(bar, THEME_BASS)
            for (row in listOf(2, 6, 10, 14)) note(NOISE, r + row, HAT_NOTE, HAT)
        }
        fx(V_PULSE1, 0, NesRules.FX_VOLUME_SLIDE, 0x3F) // the sweep swells in
        fx(V_PULSE1, 16, NesRules.FX_VOLUME, 0x0F)
        note(NOISE, 56, SNARE_NOTE, SNARE)
        note(NOISE, 60, SNARE_NOTE, SNARE)
        note(DPCM, 60, TOM_HIGH, DRUM_KIT)
        note(DPCM, 62, TOM_LOW, DRUM_KIT)
        // The saw's pickup: E-4 gliding up a fourth into the theme.
        note(SAW, 56, "E-4", SAW_LEAD); fx(SAW, 56, NesRules.FX_SLIDE_UP, 0x05)
        cut(SAW, 63)
    }.build()

    private fun theme() = PatternBuilder("Theme", CHANNELS).apply {
        val melody = listOf(
            0 to "A-4", 4 to "D-5", 6 to "E-5", 8 to "F-5", 12 to "E-5", 14 to "D-5",
            16 to "D-5", 20 to "F-5", 24 to "A#4", 28 to "D-5",
            32 to "E-5", 36 to "G-5", 40 to "E-5", 44 to "C-5",
            48 to "C#5", 52 to "E-5", 56 to "A-4",
        )
        for ((row, name) in melody) note(SAW, row, name, SAW_LEAD)
        fx(SAW, 8, NesRules.FX_VIBRATO, 0x63)
        fx(SAW, 12, NesRules.FX_VIBRATO, 0x00)
        fx(SAW, 24, NesRules.FX_SLIDE_UP, 0x04) // A#4 rising to D-5
        fx(SAW, 56, NesRules.FX_VIBRATO, 0x74)
        cut(SAW, 62) // releases the lead into its tail
        fx(SAW, 63, NesRules.FX_VIBRATO, 0x00)
        // 2A03 Pulse 1: a quiet echo of the saw, three rows late.
        for ((row, name) in melody) if (row + 3 < 64) { note(PULSE1, row + 3, name, ECHO); fx(PULSE1, row + 3, NesRules.FX_VOLUME, 0x08) }
        for (bar in 0 until 4) {
            val r = bar * 16
            // VRC6 Pulse 2: thin arpeggio chords on the beats, widened by Vxx as the phrase opens up.
            for (beat in listOf(0, 4, 8, 12)) {
                note(V_PULSE2, r + beat, THEME_ROOTS[bar], THIN)
                fx(V_PULSE2, r + beat, NesRules.FX_ARPEGGIO, THEME_ARPS[bar])
            }
            // VRC6 Pulse 1: one long pad note a bar, its duty sweeping.
            note(V_PULSE1, r, THEME_PAD[bar], PWM)
            fx(V_PULSE1, r, NesRules.FX_VOLUME, 0x09)
            bass(bar, THEME_BASS)
            drums(bar)
        }
        fx(V_PULSE2, 33, NesRules.FX_DUTY, 0x03) // 4/16 from bar 3
        fx(V_PULSE2, 49, NesRules.FX_DUTY, 0x07) // 8/16 (square) for the last bar
        // A late ghost snare, delayed into the middle of its row.
        note(NOISE, 47, SNARE_NOTE, SNARE); fx(NOISE, 47, NesRules.FX_NOTE_DELAY, 0x03)
    }.build()

    private fun bridge() = PatternBuilder("Bridge", CHANNELS).apply {
        val top = listOf("D-5", "F-5", "D-5", "A#4", "E-5", "G-5", "E-5", "C-5", "F-5", "A-5", "F-5", "D-5", "E-5", "C#5", "E-5", "A-5")
        val below = listOf("A#4", "D-5", "A#4", "F-4", "C-5", "E-5", "C-5", "G-4", "D-5", "F-5", "D-5", "A-4", "C#5", "A-4", "C#5", "E-5")
        for (i in top.indices) {
            note(V_PULSE1, i * 4, top[i], PWM)
            note(V_PULSE2, i * 4, below[i], THIN)
        }
        fx(V_PULSE2, 0, NesRules.FX_DUTY, 0x02) // 3/16 under the sweep
        fx(V_PULSE1, 60, NesRules.FX_SLIDE_DOWN, 0x05) // A-5 sinking to E-5
        cut(V_PULSE1, 63)
        cut(V_PULSE2, 63)
        // The saw turns bass (Half saw volume: the instrument change sends the master volume).
        for (bar in 0 until 4) {
            bass(bar, BRIDGE_BASS, SAW, SAW_BASS)
            drums(bar)
        }
        fx(SAW, 0, NesRules.FX_VOLUME_SLIDE, 0x8F) // the saw bass swells in
        fx(SAW, 16, NesRules.FX_VOLUME, 0x0F)
    }.build()

    /** Root, root, octave, root, fifth, octave; the short notes are cut a few frames in. */
    private fun PatternBuilder.bass(bar: Int, roots: IntArray, channel: Int = TRIANGLE, instrument: Int = BASS) {
        val r = bar * 16
        for ((row, offset) in listOf(0 to 0, 3 to 0, 6 to 12, 8 to 0, 11 to 7, 14 to 12)) {
            note(channel, r + row, roots[bar] + offset, instrument)
            if (row == 3 || row == 6 || row == 11) fx(channel, r + row, NesRules.FX_NOTE_CUT, 0x04)
        }
    }

    private fun PatternBuilder.drums(bar: Int) {
        val r = bar * 16
        val hits = listOf(0 to KICK, 2 to HAT, 4 to SNARE, 6 to HAT, 8 to KICK, 10 to HAT, 12 to SNARE, 14 to HAT)
        for ((row, instrument) in hits) {
            note(NOISE, r + row, when (instrument) { KICK -> KICK_NOTE; SNARE -> SNARE_NOTE; else -> HAT_NOTE }, instrument)
        }
        for ((row, note) in listOf(0 to KIT_KICK, 4 to KIT_SNARE, 8 to KIT_KICK, 12 to KIT_SNARE)) note(DPCM, r + row, note, DRUM_KIT)
    }

    /** Noise rates (note mod 16) and starter kit keys, as in the 2A03 demo. */
    private val KICK_NOTE = n("A-3")
    private val SNARE_NOTE = n("F-4")
    private val HAT_NOTE = n("A#4")
    private val KIT_KICK = n("C-3")
    private val KIT_SNARE = n("D-3")
    private val TOM_LOW = n("G-3")
    private val TOM_HIGH = n("A-3")
}
