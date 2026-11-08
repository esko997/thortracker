package org.thortracker.system.nes

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.nes.NesDemoSong.PatternBuilder
import org.thortracker.system.nes.NesDemoSong.n
import org.thortracker.system.ym2612.YmOperator

/**
 * The bundled NES + EPSM demo: "Expansion Port", A minor, speed 6. Intro, theme and bridge, arranged 0-1-2-1 and
 * looping to order 1, showing what the EPSM's YMF288 adds to the 2A03 with four FM algorithms and the SSG:
 * - intro (Am F C G): an FM bell (algorithm 4) breaks the chords over additive organ chords (algorithm 7) swelling in
 *   on FM 3-5, the triangle bass enters, and the FM lead's pickup glides into the theme;
 * - theme: the FM lead (algorithm 4, vibrato from the song's one LFO) echoed by 2A03 Pulse 1, an FM slap bass
 *   (algorithm 0), a slow chorused pad (algorithm 5: one modulator, three carriers) on FM 3-5, an SSG arpeggio shimmer
 *   on SSG 2, SSG 3's tone+noise snare and noise ghost hats with the 2A03 hats and the DPCM kick;
 * - bridge (F G E Am): SSG 1 turns bass on the hardware envelope (a sawtooth whose period follows the note, tone and
 *   noise off), FM brass stabs (algorithm 4) on FM 6, a higher lead gliding home, 2A03 Pulse 2 arpeggio plucks.
 * The six rhythm channels stay empty: they play the YM2608 rhythm ROM, which is never bundled, so the demo plays
 * without it. Only SSG 1 sounds the hardware envelope and only SSG 3 the noise, so the SSG's shared generators play
 * what each sound asks for. Every EPSM note is within C-1..B-7.
 */
object NesEpsmDemoSong {
    private const val ECHO = 1
    private const val BASS = 2
    private const val PLUCK = 3
    private const val HAT = 4
    private const val DRUM_KIT = 5
    private const val FM_BASS = 6
    private const val FM_LEAD = 7
    private const val FM_PAD = 8
    private const val FM_ORGAN = 9
    private const val FM_BELL = 10
    private const val FM_BRASS = 11
    private const val SSG_SHIMMER = 12
    private const val SSG_BUZZ = 13
    private const val SSG_SNARE = 14
    private const val SSG_HAT = 15

    private const val PULSE1 = 0
    private const val PULSE2 = 1
    private const val TRIANGLE = NesRules.TRIANGLE
    private const val NOISE = NesRules.NOISE
    private const val DPCM = NesRules.DPCM
    private const val SSG1 = NesRules.EPSM_SSG1
    private const val SSG2 = NesRules.EPSM_SSG1 + 1
    private const val SSG3 = NesRules.EPSM_SSG1 + 2
    private const val FM1 = NesRules.EPSM_FM1
    private const val FM2 = NesRules.EPSM_FM1 + 1
    private const val FM3 = NesRules.EPSM_FM1 + 2
    private const val FM6 = NesRules.EPSM_FM1 + 5

    /** All 20 tracks: the 2A03's five, SSG 1-3, FM 1-6 and the six rhythm channels (left empty). */
    private const val CHANNELS = NesRules.EPSM_RHYTHM1 + 6

    /** The chip's one LFO (`$22`): on, rate 2 (6.02 Hz); the lead and pad take it as vibrato (PMS). */
    private const val LFO = NesSound.EPSM_LFO_ON or 2

    /** Theme and intro chords Am F C G: bell roots, FM bass roots, triangle roots (it sounds an octave lower). */
    private val THEME_BELL = intArrayOf(n("A-4"), n("F-4"), n("C-5"), n("G-4"))
    private val THEME_MINOR = booleanArrayOf(true, false, false, false)
    private val THEME_BASS = intArrayOf(n("A-1"), n("F-1"), n("C-2"), n("G-1"))
    private val THEME_TRIANGLE = intArrayOf(n("A-2"), n("F-2"), n("C-3"), n("G-2"))

    /** The theme's three-voice chords on FM 3-5, one a bar. */
    private val THEME_CHORDS = listOf(
        listOf("A-3", "C-4", "E-4"), listOf("A-3", "C-4", "F-4"), listOf("G-3", "C-4", "E-4"), listOf("G-3", "B-3", "D-4"),
    )

    /** SSG 2's shimmer: a high chord root a half note, its 0xy arpeggio. */
    private val SHIMMER_ROOTS = intArrayOf(n("A-5"), n("F-5"), n("C-6"), n("G-5"))
    private val SHIMMER_ARPS = intArrayOf(0x37, 0x47, 0x47, 0x47)

    /** Bridge chords F G E Am: the buzz bass roots, pad chords, brass stab notes, Pulse 2 pluck roots and arpeggios. */
    private val BRIDGE_BUZZ = intArrayOf(n("F-2"), n("G-2"), n("E-2"), n("A-2"))
    private val BRIDGE_CHORDS = listOf(
        listOf("A-3", "C-4", "F-4"), listOf("B-3", "D-4", "G-4"), listOf("G#3", "B-3", "E-4"), listOf("A-3", "C-4", "E-4"),
    )
    private val BRIDGE_BRASS = intArrayOf(n("C-5"), n("D-5"), n("B-4"), n("C-5"))
    private val BRIDGE_PLUCKS = intArrayOf(n("F-4"), n("G-4"), n("E-4"), n("A-4"))
    private val BRIDGE_ARPS = intArrayOf(0x47, 0x47, 0x47, 0x37)

    fun build(): Song {
        val base = NesEpsmSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[ECHO - 1] = NesInstrument(name = "Echo 25%", volume = NesEnvelope(listOf(9, 8, 6, 5, 4, 3, 2, 1, 0)), duty = NesEnvelope(listOf(1)))
        bank[BASS - 1] = NesInstrument(name = "Triangle bass")
        bank[PLUCK - 1] = NesInstrument(name = "Pluck 12.5%", volume = NesEnvelope(listOf(10, 8, 6, 5, 4, 3, 2, 1, 0)), duty = NesEnvelope(listOf(0)))
        bank[HAT - 1] = NesInstrument(name = "Closed hat", volume = NesEnvelope(listOf(8, 5, 3, 1, 0)))
        bank[DRUM_KIT - 1] = DpcmStarterKit.instrument(0)
        // Chain 1→2→3→4: operator 2's fast decay clicks over a round body.
        bank[FM_BASS - 1] = fm("FM slap bass", 0, 5,
            op(1, 34, d1r = 12, d2r = 5, sl = 4, rr = 9),
            op(3, 44, d1r = 19, d2r = 8, sl = 9, rr = 9, dt = 3),
            op(1, 24, d1r = 10, d2r = 4, sl = 3, rr = 9, dt = 7),
            op(1, 2, d1r = 8, d2r = 3, sl = 2, rr = 10))
        // Two detuned pairs, modulators at twice the carriers' pitch: a hollow, singing square.
        bank[FM_LEAD - 1] = fm("FM lead", 4, 2,
            op(2, 30, d1r = 3, d2r = 1, sl = 2, rr = 7, dt = 3),
            op(1, 4, d1r = 2, sl = 1, rr = 7, dt = 3),
            op(2, 32, d1r = 3, d2r = 1, sl = 2, rr = 7, dt = 7),
            op(1, 6, d1r = 2, sl = 1, rr = 7, dt = 7), pms = 2)
        // One slow modulator under three detuned carriers (the third an octave up): a chorused string pad.
        bank[FM_PAD - 1] = fm("FM pad", 5, 3,
            op(1, 30, ar = 12, d1r = 2, sl = 2, rr = 5),
            op(1, 14, ar = 11, d1r = 1, sl = 1, rr = 5, dt = 3),
            op(1, 16, ar = 11, d1r = 1, sl = 1, rr = 5, dt = 7),
            op(2, 26, ar = 10, d1r = 2, sl = 2, rr = 5), pms = 3)
        bank[FM_ORGAN - 1] = organ()
        // Inharmonic pairs (7:2 and 3:1) with a long percussive decay.
        bank[FM_BELL - 1] = fm("FM bell", 4, 2,
            op(7, 32, d1r = 7, d2r = 3, sl = 5, rr = 5),
            op(2, 8, d1r = 5, d2r = 3, sl = 6, rr = 5),
            op(3, 36, d1r = 6, d2r = 3, sl = 5, rr = 5, dt = 2),
            op(1, 10, d1r = 4, d2r = 2, sl = 5, rr = 5))
        // Detuned pairs whose modulators swell in slower than the carriers: the rasp opens up.
        bank[FM_BRASS - 1] = fm("FM brass", 4, 5,
            op(1, 26, ar = 21, d1r = 4, d2r = 1, sl = 2, rr = 7, dt = 3),
            op(1, 4, ar = 27, d1r = 3, d2r = 1, sl = 1, rr = 7, dt = 3),
            op(1, 28, ar = 20, d1r = 4, d2r = 1, sl = 2, rr = 7, dt = 7),
            op(1, 6, ar = 27, d1r = 3, d2r = 1, sl = 1, rr = 7, dt = 7))
        bank[SSG_SHIMMER - 1] = NesInstrument(
            name = "SSG shimmer",
            volume = NesEnvelope(listOf(9, 8, 7, 6, 6, 5, 5, 4)),
            chip = NesChip.EPSM,
        )
        // Tone and noise off: the channel's level is the hardware envelope itself, a sawtooth at the note's pitch
        // (auto period, octave 4: the tone period ÷ 16, 32 steps per ramp = the tone's own frequency).
        bank[SSG_BUZZ - 1] = NesInstrument(
            name = "SSG buzz bass (env)",
            chip = NesChip.EPSM,
            mixer = NesEnvelope(listOf(3)),
            epsmEnvelope = S5bEnvelope.EPSM_DEFAULT.copy(shape = S5bShape.SAW_DOWN, autoPitch = true, octave = 4),
        )
        // Two frames of tone+noise for the body, then noise alone, its period darkening.
        bank[SSG_SNARE - 1] = NesInstrument(
            name = "SSG snare",
            volume = NesEnvelope(listOf(15, 13, 11, 9, 7, 6, 5, 4, 3, 2, 1, 0)),
            arpeggio = NesEnvelope(listOf(0, -4)),
            chip = NesChip.EPSM,
            mixer = NesEnvelope(listOf(0, 0, 1)),
            noise = NesEnvelope(listOf(4, 6, 8, 10, 12)),
        )
        bank[SSG_HAT - 1] = NesInstrument(
            name = "SSG hat",
            volume = NesEnvelope(listOf(9, 6, 3, 1, 0)),
            chip = NesChip.EPSM,
            mixer = NesEnvelope(listOf(1)),
            noise = NesEnvelope(listOf(1)),
        )
        return base.copy(
            title = "Expansion Port",
            artist = "Thor Tracker",
            comment = "NES + EPSM demo: FM lead, slap bass, pad, organ, bell and brass (algorithms 0, 4, 5, 7), SSG shimmer, " +
                "hardware-envelope buzz bass and noise drums, 2A03 echo, plucks, bass and DPCM kick. No rhythm-ROM " +
                "channels. Loops to order 1.",
            ticksPerRow = 6,
            loopOrder = 1,
            patterns = listOf(intro(), theme(), bridge()),
            orders = listOf(0, 1, 2, 1),
            sound = base.nes.copy(instruments = bank, samples = DpcmStarterKit.samples(), epsmLfo = LFO),
        )
    }

    /**
     * The shortest valid NES + EPSM song (the WAV export test's `epsm-shortest.nesimg`): one order of a one-row pattern
     * at speed 1, looping to order 0, holding A-4 on FM 1 with sound 01, the sustaining organ, so every pass sounds. No
     * rhythm notes: it plays without the rhythm ROM.
     */
    fun shortest(): Song {
        val base = NesEpsmSystem.blank()
        val held = Cell(note = n("A-4"), instrument = 1)
        val tracks = List(CHANNELS) { ch -> List(Songs.ROWS) { row -> if (ch == FM1 && row == 0) held else Cell.BLANK } }
        return base.copy(
            title = "EPSM shortest",
            ticksPerRow = 1,
            loopOrder = 0,
            patterns = listOf(Pattern("Pattern 00", tracks, length = 1)),
            orders = listOf(0),
            sound = base.nes.copy(instruments = base.nes.instruments.toMutableList().also { it[0] = organ() }),
        )
    }

    /** Additive drawbars 1, 2, 4 and 8 (algorithm 7: every operator is heard), held at full level while keyed. */
    private fun organ() = fm("FM organ", 7, 0, op(1, 14, rr = 9), op(2, 20, rr = 9), op(4, 28, rr = 9), op(8, 36, rr = 9))

    private fun intro() = PatternBuilder("Intro", CHANNELS).apply {
        for (bar in 0 until 4) {
            val r = bar * 16
            // FM 6: the bell breaks the chord in eighths, up through the tenth and back.
            val third = if (THEME_MINOR[bar]) 15 else 16
            for ((step, offset) in listOf(0, 7, 12, third, 19, third, 12, 7).withIndex()) {
                note(FM6, r + step * 2, THEME_BELL[bar] + offset, FM_BELL)
            }
            chord(r, THEME_CHORDS[bar], FM_ORGAN)
            if (bar >= 2) bass(bar, THEME_TRIANGLE, TRIANGLE, BASS)
            for (row in listOf(2, 6, 10, 14)) note(NOISE, r + row, HAT_NOTE, HAT)
        }
        fx(FM6, 0, NesRules.FX_VOLUME, 0x0B)
        // The organ swells in over the first bar, then holds.
        for (voice in 0 until 3) {
            fx(FM3 + voice, 0, NesRules.FX_VOLUME_SLIDE, 0x2A)
            fx(FM3 + voice, 16, NesRules.FX_VOLUME, 0x0A)
        }
        note(SSG3, 56, SNARE_NOTE, SSG_SNARE)
        note(SSG3, 60, SNARE_NOTE, SSG_SNARE)
        note(DPCM, 60, TOM_HIGH, DRUM_KIT)
        note(DPCM, 62, TOM_LOW, DRUM_KIT)
        // The lead's pickup: E-4 gliding up a fourth into the theme.
        note(FM2, 56, "E-4", FM_LEAD); fx(FM2, 56, NesRules.FX_SLIDE_UP, 0x05)
        cut(FM2, 63)
    }.build()

    private fun theme() = PatternBuilder("Theme", CHANNELS).apply {
        val melody = listOf(
            0 to "A-4", 2 to "C-5", 4 to "E-5", 10 to "D-5", 12 to "C-5", 14 to "B-4",
            16 to "C-5", 22 to "A-4", 24 to "F-5", 30 to "E-5",
            32 to "G-5", 34 to "E-5", 36 to "C-5", 38 to "G-4", 44 to "A-4", 46 to "C-5",
            48 to "D-5", 52 to "B-4", 54 to "G-4", 56 to "D-5",
        )
        lead(melody)
        fx(FM2, 56, NesRules.FX_SLIDE_UP, 0x02) // D-5 rising to E-5
        cut(FM2, 62) // keys off into the patch's release
        for (bar in 0 until 4) {
            val r = bar * 16
            bass(bar, THEME_BASS, FM1, FM_BASS)
            chord(r, THEME_CHORDS[bar], FM_PAD)
            // SSG 2: a quiet high arpeggio, a half note per chord.
            for (half in listOf(0, 8)) {
                note(SSG2, r + half, SHIMMER_ROOTS[bar], SSG_SHIMMER)
                fx(SSG2, r + half, NesRules.FX_ARPEGGIO, SHIMMER_ARPS[bar])
            }
            drums(bar)
        }
        // The pad's level on its note rows, so every order sets its own (seek, loop).
        for (voice in 0 until 3) fx(FM3 + voice, 0, NesRules.FX_VOLUME, 0x0B)
        // The intro's triangle bass and the bridge's buzz bass hold their last notes: stop them.
        cut(TRIANGLE, 0)
        cut(SSG1, 0)
        // A late ghost snare, delayed into the middle of its row.
        note(SSG3, 47, SNARE_NOTE, SSG_SNARE); fx(SSG3, 47, NesRules.FX_NOTE_DELAY, 0x03)
    }.build()

    private fun bridge() = PatternBuilder("Bridge", CHANNELS).apply {
        val melody = listOf(
            0 to "A-5", 8 to "G-5", 12 to "F-5",
            16 to "G-5", 24 to "D-5", 28 to "G-5",
            32 to "G#5", 40 to "B-5", 44 to "G#5",
            48 to "A-5", 56 to "E-5",
        )
        lead(melody)
        fx(FM2, 56, NesRules.FX_SLIDE_DOWN, 0x07) // E-5 sinking to A-4, home for the theme
        cut(FM2, 62)
        cut(FM1, 0) // the slap bass rests; SSG 1's buzz takes over
        cut(SSG2, 0) // so does the shimmer
        for (bar in 0 until 4) {
            val r = bar * 16
            bass(bar, BRIDGE_BUZZ, SSG1, SSG_BUZZ)
            chord(r, BRIDGE_CHORDS[bar], FM_PAD)
            // FM 6: brass stabs, two short and one held.
            for ((on, off) in listOf(0 to 2, 6 to 8, 10 to 14)) {
                note(FM6, r + on, BRIDGE_BRASS[bar], FM_BRASS)
                cut(FM6, r + off)
            }
            // 2A03 Pulse 2: off-beat arpeggio plucks.
            for (row in listOf(2, 6, 10, 14)) {
                note(PULSE2, r + row, BRIDGE_PLUCKS[bar], PLUCK)
                fx(PULSE2, r + row, NesRules.FX_ARPEGGIO, BRIDGE_ARPS[bar])
            }
            drums(bar)
        }
        fx(FM6, 0, NesRules.FX_VOLUME, 0x0C)
        for (voice in 0 until 3) fx(FM3 + voice, 0, NesRules.FX_VOLUME, 0x0B)
        note(DPCM, 60, TOM_HIGH, DRUM_KIT)
        note(DPCM, 62, TOM_LOW, DRUM_KIT)
    }.build()

    /** FM 2 plays [notes]; 2A03 Pulse 1 echoes them quietly three rows late. */
    private fun PatternBuilder.lead(notes: List<Pair<Int, String>>) {
        for ((row, name) in notes) note(FM2, row, name, FM_LEAD)
        for ((row, name) in notes) if (row + 3 < 64) {
            note(PULSE1, row + 3, name, ECHO)
            fx(PULSE1, row + 3, NesRules.FX_VOLUME, 0x07)
        }
    }

    /** A three-voice chord on FM 3-5 from row [r]. */
    private fun PatternBuilder.chord(r: Int, notes: List<String>, instrument: Int) {
        for ((voice, name) in notes.withIndex()) note(FM3 + voice, r, name, instrument)
    }

    /** Root, root, octave, root, fifth, octave; the short notes are cut a few frames in. */
    private fun PatternBuilder.bass(bar: Int, roots: IntArray, channel: Int, instrument: Int) {
        val r = bar * 16
        for ((row, offset) in listOf(0 to 0, 3 to 0, 6 to 12, 8 to 0, 11 to 7, 14 to 12)) {
            note(channel, r + row, roots[bar] + offset, instrument)
            if (row == 3 || row == 6 || row == 11) fx(channel, r + row, NesRules.FX_NOTE_CUT, 0x04)
        }
    }

    /** DPCM kick, SSG 3 snare and ghost hats, 2A03 noise hats. */
    private fun PatternBuilder.drums(bar: Int) {
        val r = bar * 16
        for (row in listOf(0, 8)) note(DPCM, r + row, KIT_KICK, DRUM_KIT)
        if (bar == 3) note(DPCM, r + 10, KIT_KICK, DRUM_KIT)
        for (row in listOf(4, 12)) note(SSG3, r + row, SNARE_NOTE, SSG_SNARE)
        for (row in listOf(7, 15)) note(SSG3, r + row, SSG_HAT_NOTE, SSG_HAT)
        for (row in listOf(2, 6, 10, 14)) note(NOISE, r + row, HAT_NOTE, HAT)
    }

    /** An EPSM FM sound: the patch (operators numbered as in the algorithm diagrams) on the default envelopes. */
    private fun fm(name: String, algorithm: Int, feedback: Int, vararg ops: YmOperator, pms: Int = 0) = NesInstrument(
        name = name,
        chip = NesChip.EPSM,
        epsm = EpsmPatch(algorithm = algorithm, feedback = feedback, pms = pms, operators = ops.toList()),
    )

    private fun op(mul: Int, tl: Int, ar: Int = 31, d1r: Int = 0, d2r: Int = 0, sl: Int = 0, rr: Int = 8, dt: Int = 0) =
        YmOperator(dt = dt, mul = mul, tl = tl, ar = ar, d1r = d1r, d2r = d2r, sl = sl, rr = rr)

    /** 2A03 noise rate (note mod 16) for the hats; the SSG drums' pitches (the snare's tone body, the hat's unused). */
    private val HAT_NOTE = n("A#4")
    private val SNARE_NOTE = n("D-4")
    private val SSG_HAT_NOTE = n("C-6")

    /** The starter kit's notes ([DpcmStarterKit.NOTES]). */
    private val KIT_KICK = n("C-3")
    private val TOM_LOW = n("G-3")
    private val TOM_HIGH = n("A-3")
}
