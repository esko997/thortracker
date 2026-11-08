package org.thortracker.system.nes

import org.thortracker.model.Song
import org.thortracker.system.nes.NesDemoSong.PatternBuilder
import org.thortracker.system.nes.NesDemoSong.n

/**
 * The bundled NES + Sunsoft 5B demo: "Copper Kite", E minor, speed 6. Intro, theme and bridge, arranged 0-1-2-1 and
 * looping to order 1, showing what the 5B adds to the 2A03:
 * - intro: 5B Square 1 holds chords with a slow manual-period triangle envelope (a hardware tremolo), sped up by `J80`,
 *   slowed by `I02` and set to `$012C` by `I01` on Square 1 with `J2C` on Square 2 in one row (on the note rows, so the
 *   notes keep the effect's period); 5B noise hats on Square 3;
 * - theme: a 5B tone lead (written an octave up: the 5B sounds an octave below a pulse on the same note) echoed by
 *   2A03 Pulse 1, a buzz bass on Square 2 (a saw envelope whose period follows the note, octave 4: in unison with the
 *   tone), 5B noise snare and hats on Square 3 with the 2A03 kick and the DPCM kit;
 * - bridge: arpeggio chords on the lead, the buzz bass turned triangle (octave 5), a falling tone+noise tom fill.
 * Envelope sounds never overlap on two squares and the noise always comes from Square 3, the highest square, so the
 * shared generators play what each sound asks for.
 */
internal object NesS5bDemoSong {
    private const val ECHO = 1
    private const val BASS = 2
    private const val KICK = 3
    private const val HAT = 4
    private const val DRUM_KIT = 5
    private const val LEAD = 6
    private const val BUZZ = 7
    private const val BUZZ_TRI = 8
    private const val PAD = 9
    private const val SNARE_5B = 10
    private const val HAT_5B = 11
    private const val TOM_5B = 12

    private const val PULSE1 = 0
    private const val TRIANGLE = NesRules.TRIANGLE
    private const val NOISE = NesRules.NOISE
    private const val DPCM = NesRules.DPCM
    private const val SQ1 = NesRules.S5B_SQUARE1
    private const val SQ2 = NesRules.S5B_SQUARE1 + 1
    private const val SQ3 = NesRules.S5B_SQUARE1 + 2
    private const val CHANNELS = 8

    /** Em C D Bm: pad chord tones (sounding an octave lower), the buzz bass roots, the triangle's roots. */
    private val PAD_NOTES = intArrayOf(n("E-5"), n("C-5"), n("D-5"), n("B-4"))
    private val BUZZ_ROOTS = intArrayOf(n("E-3"), n("C-3"), n("D-3"), n("B-2"))
    private val TRI_ROOTS = intArrayOf(n("E-3"), n("C-3"), n("D-3"), n("B-2"))
    private val BRIDGE_ROOTS = intArrayOf(n("A-2"), n("B-2"), n("C-3"), n("D-3"))

    fun build(): Song {
        val base = NesS5bSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[ECHO - 1] = NesInstrument(name = "Echo 25%", volume = NesEnvelope(listOf(9, 8, 6, 5, 4, 3, 2, 1, 0)), duty = NesEnvelope(listOf(1)))
        bank[BASS - 1] = NesInstrument(name = "Triangle bass")
        bank[KICK - 1] = NesInstrument(
            name = "Kick",
            volume = NesEnvelope(listOf(15, 13, 10, 7, 4, 2, 0)),
            arpeggio = NesEnvelope(listOf(0, -1, -2, -3, -4, -5, -6)),
        )
        bank[HAT - 1] = NesInstrument(name = "Closed hat", volume = NesEnvelope(listOf(8, 5, 3, 1, 0)))
        bank[DRUM_KIT - 1] = DpcmStarterKit.instrument(0)
        bank[LEAD - 1] = NesInstrument(
            name = "5B lead",
            volume = NesEnvelope(listOf(13, 13, 12, 12, 11, 11, 10, 8, 6, 4, 2, 0), loop = 5, release = 6),
            chip = NesChip.S5B,
        )
        bank[BUZZ - 1] = NesInstrument(
            name = "Buzz bass (saw)",
            chip = NesChip.S5B,
            s5b = S5bEnvelope(shape = S5bShape.SAW_DOWN, autoPitch = true, octave = 4),
        )
        bank[BUZZ_TRI - 1] = NesInstrument(
            name = "Buzz bass (tri)",
            chip = NesChip.S5B,
            s5b = S5bEnvelope(shape = S5bShape.TRIANGLE, autoPitch = true, octave = 5),
        )
        bank[PAD - 1] = NesInstrument(
            name = "Tremolo pad",
            chip = NesChip.S5B,
            s5b = S5bEnvelope(shape = S5bShape.TRIANGLE, autoPitch = false, period = 600),
        )
        bank[SNARE_5B - 1] = NesInstrument(
            name = "5B snare",
            volume = NesEnvelope(listOf(14, 11, 9, 7, 6, 5, 4, 3, 2, 1, 0)),
            chip = NesChip.S5B,
            mixer = NesEnvelope(listOf(1, 1, 0, 0, 2)),
            noise = NesEnvelope(listOf(6, 9, 12, 15, 18)),
        )
        bank[HAT_5B - 1] = NesInstrument(
            name = "5B hat",
            volume = NesEnvelope(listOf(10, 6, 3, 1, 0)),
            chip = NesChip.S5B,
            mixer = NesEnvelope(listOf(1)),
            noise = NesEnvelope(listOf(1)),
        )
        bank[TOM_5B - 1] = NesInstrument(
            name = "5B tom (tone+noise)",
            volume = NesEnvelope(listOf(13, 12, 10, 8, 6, 4, 3, 2, 1, 0)),
            arpeggio = NesEnvelope(listOf(0, -1, -2, -3, -4, -5)),
            chip = NesChip.S5B,
            mixer = NesEnvelope(listOf(0)),
            noise = NesEnvelope(listOf(4)),
        )
        return base.copy(
            title = "Copper Kite",
            artist = "Thor Tracker",
            comment = "NES + Sunsoft 5B demo: hardware-envelope tremolo pad (Ixx/Jyy), tone lead, buzz basses, 5B noise drums, " +
                "2A03 echo, bass and drums. Loops to order 1.",
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
            // 5B Square 1: one pad chord tone a bar, its tremolo the hardware envelope.
            note(SQ1, r, PAD_NOTES[bar], PAD)
            if (bar >= 2) triangleBass(bar, TRI_ROOTS)
            for (row in listOf(2, 6, 10, 14)) note(SQ3, r + row, "A-5", HAT_5B)
        }
        // The envelope period on the pad's note rows (so the new notes keep it): fast, slow, then $012C from two squares.
        fx(SQ1, 16, NesRules.FX_ENV_PERIOD_LO, 0x80)
        fx(SQ1, 32, NesRules.FX_ENV_PERIOD_HI, 0x02)
        fx(SQ1, 48, NesRules.FX_ENV_PERIOD_HI, 0x01)
        fx(SQ2, 48, NesRules.FX_ENV_PERIOD_LO, 0x2C)
        cut(SQ1, 63)
        note(NOISE, 60, KICK_NOTE, KICK)
        note(DPCM, 60, TOM_HIGH, DRUM_KIT)
        note(DPCM, 62, TOM_LOW, DRUM_KIT)
    }.build()

    private fun theme() = PatternBuilder("Theme", CHANNELS).apply {
        // Written an octave up: the 5B square sounds it at the 2A03 pulses' pitch of the note an octave lower.
        val melody = listOf(
            0 to "B-5", 4 to "E-6", 6 to "F#6", 8 to "G-6", 12 to "F#6", 14 to "E-6",
            16 to "E-6", 20 to "G-6", 24 to "C-6", 28 to "E-6",
            32 to "F#6", 36 to "A-6", 40 to "F#6", 44 to "D-6",
            48 to "D#6", 52 to "F#6", 56 to "B-5",
        )
        for ((row, name) in melody) note(SQ1, row, name, LEAD)
        fx(SQ1, 8, NesRules.FX_VIBRATO, 0x63)
        fx(SQ1, 12, NesRules.FX_VIBRATO, 0x00)
        fx(SQ1, 24, NesRules.FX_SLIDE_UP, 0x04) // C-6 rising to E-6
        fx(SQ1, 56, NesRules.FX_VIBRATO, 0x74)
        cut(SQ1, 62) // releases the lead into its tail
        fx(SQ1, 63, NesRules.FX_VIBRATO, 0x00)
        // 2A03 Pulse 1: the lead an octave down in the writing (the same sounding pitch), quiet and three rows late.
        for ((row, name) in melody) if (row + 3 < 64) {
            note(PULSE1, row + 3, n(name) - 12, ECHO)
            fx(PULSE1, row + 3, NesRules.FX_VOLUME, 0x08)
        }
        for (bar in 0 until 4) {
            buzzBass(bar, BUZZ_ROOTS, BUZZ)
            drums(bar)
        }
    }.build()

    private fun bridge() = PatternBuilder("Bridge", CHANNELS).apply {
        // The lead plays arpeggio chords on the beats: A minor, B minor, C major, D major.
        val chords = listOf("A-5" to 0x37, "B-5" to 0x37, "C-6" to 0x47, "D-6" to 0x47)
        for (bar in 0 until 4) {
            val r = bar * 16
            for (beat in listOf(0, 4, 8, 12)) {
                note(SQ1, r + beat, chords[bar].first, LEAD)
                fx(SQ1, r + beat, NesRules.FX_ARPEGGIO, chords[bar].second)
            }
            buzzBass(bar, BRIDGE_ROOTS, BUZZ_TRI)
            triangleBass(bar, BRIDGE_ROOTS)
            if (bar < 3) drums(bar)
        }
        fx(SQ1, 60, NesRules.FX_SLIDE_DOWN, 0x05)
        // Bar 4: a falling tone+noise tom fill on Square 3 (the note pitches the square that gates the noise).
        for ((row, name) in listOf(48 to "E-5", 51 to "C-5", 54 to "A-4", 57 to "F-4", 60 to "D-4")) note(SQ3, row, name, TOM_5B)
        note(NOISE, 48, KICK_NOTE, KICK)
        note(DPCM, 48, KIT_KICK, DRUM_KIT)
    }.build()

    /** The buzz bass: root on the beat, octave on the offbeat, the short notes cut a few frames in. */
    private fun PatternBuilder.buzzBass(bar: Int, roots: IntArray, instrument: Int) {
        val r = bar * 16
        for ((row, offset) in listOf(0 to 0, 6 to 12, 8 to 0, 14 to 12)) {
            note(SQ2, r + row, roots[bar] + offset, instrument)
            if (offset == 12) fx(SQ2, r + row, NesRules.FX_NOTE_CUT, 0x04)
        }
    }

    /** Root, root, octave, root, fifth, octave on the triangle (it sounds an octave lower). */
    private fun PatternBuilder.triangleBass(bar: Int, roots: IntArray) {
        val r = bar * 16
        for ((row, offset) in listOf(0 to 0, 3 to 0, 6 to 12, 8 to 0, 11 to 7, 14 to 12)) {
            note(TRIANGLE, r + row, roots[bar] + offset, BASS)
            if (row == 3 || row == 6 || row == 11) fx(TRIANGLE, r + row, NesRules.FX_NOTE_CUT, 0x04)
        }
    }

    /** 2A03 kick and DPCM kit on the beats, the 5B snare on 2 and 4, 5B hats between. */
    private fun PatternBuilder.drums(bar: Int) {
        val r = bar * 16
        for (row in listOf(0, 8)) note(NOISE, r + row, KICK_NOTE, KICK)
        for ((row, note) in listOf(0 to KIT_KICK, 4 to KIT_SNARE, 8 to KIT_KICK, 12 to KIT_SNARE)) note(DPCM, r + row, note, DRUM_KIT)
        for (row in listOf(4, 12)) note(SQ3, r + row, "D-5", SNARE_5B)
        for (row in listOf(2, 6, 10, 14)) note(SQ3, r + row, "A-5", HAT_5B)
    }

    /** Noise rates (note mod 16) and starter kit keys, as in the 2A03 demo. */
    private val KICK_NOTE = n("A-3")
    private val KIT_KICK = n("C-3")
    private val KIT_SNARE = n("D-3")
    private val TOM_LOW = n("G-3")
    private val TOM_HIGH = n("A-3")
}
