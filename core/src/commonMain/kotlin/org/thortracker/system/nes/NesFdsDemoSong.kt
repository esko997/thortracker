package org.thortracker.system.nes

import org.thortracker.model.Song
import org.thortracker.system.nes.NesDemoSong.PatternBuilder
import org.thortracker.system.nes.NesDemoSong.n
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The bundled NES + FDS demo: "Side B", D minor, speed 6, four 64-row patterns played 0-1-2-3 and looping to order 1
 * (once through 256 rows = 1536 frames, about 25.6 s; the loop 192 rows, about 19.2 s). The FDS channel takes a different
 * sound in each part, all written at sounding pitch (one cycle per wave sounds as named):
 * - Intro: an FM bass (auto-mod 1/1: the mod table runs at the note's own frequency) whose mod depth opens up note by
 *   note (Hxx on every note), over the drums and the DPCM starter kit, the 2A03's chords joining halfway;
 * - Theme: a lead on a drawn two-harmonic wave with a slow sine vibrato from the modulation unit that starts 18 frames
 *   into each note (mod delay), Jxx speeding the vibrato up on a held note and I01 turning it into a growl (the next
 *   note with an attack returns to the sound's own speed), OFF releasing into the lead's tail;
 * - Bridge: a pad whose four PWM waves cycle while the note holds and whose release (OFF) moves to a sine as it fades,
 *   with the 2A03's pulse lead on top;
 * - Bells: an FM bell (auto-mod 7/2, a clangorous ratio) struck on the beat, its depth set lower on each strike (Hxx),
 *   some strikes restarting the wave (X00, the phase reset).
 * The 2A03 keeps arpeggio chords (Pulse 1), a counter-melody (Pulse 2), the triangle bass, noise drums and the DPCM kit.
 */
internal object NesFdsDemoSong {
    private const val BASS = 1
    private const val KICK = 2
    private const val SNARE = 3
    private const val HAT = 4
    private const val DRUM_KIT = 5
    private const val CHORD = 6
    private const val COUNTER = 7
    private const val PULSE_LEAD = 8
    private const val FM_BASS = 9
    private const val VIBRATO_LEAD = 10
    private const val MORPH = 11
    private const val BELL = 12

    private const val PULSE1 = 0
    private const val PULSE2 = 1
    private const val TRIANGLE = NesRules.TRIANGLE
    private const val NOISE = NesRules.NOISE
    private const val DPCM = NesRules.DPCM
    private const val FDS = NesRules.FDS

    /** Chords Dm Bb C A: arpeggio root and 0xy, the bass root, the counter-melody's two notes. */
    private val ROOTS = intArrayOf(n("D-4"), n("A#3"), n("C-4"), n("A-3"))
    private val ARPS = intArrayOf(0x37, 0x47, 0x47, 0x47)
    private val BASS_ROOTS = intArrayOf(n("D-2"), n("A#1"), n("C-2"), n("A-1"))
    private val COUNTERS = listOf("F-4" to "A-4", "F-4" to "D-4", "E-4" to "G-4", "E-4" to "C#4")

    /** The FM bass riff of one bar: row and semitones above the bar's root (an octave above the triangle's). */
    private val RIFF = listOf(0 to 12, 3 to 12, 6 to 24, 8 to 12, 10 to 19, 12 to 22, 14 to 24)

    /** The theme on the vibrato lead: row and note (rows 24 and 56 are held and swept by Jxx and Ixx). */
    private val THEME = listOf(
        0 to "A-4", 4 to "D-5", 8 to "F-5", 12 to "E-5", 16 to "D-5", 20 to "C-5", 24 to "A#4",
        32 to "G-4", 36 to "A#4", 40 to "C-5", 44 to "E-5", 48 to "G-5", 52 to "F-5", 56 to "E-5",
    )

    /** The bridge's pulse lead over the morphing pad. */
    private val BRIDGE_LINE = listOf(
        0 to "D-5", 6 to "C-5", 8 to "A#4", 12 to "A-4", 16 to "F-5", 22 to "E-5", 24 to "D-5", 28 to "A#4",
        32 to "G-5", 38 to "F-5", 40 to "E-5", 44 to "C-5", 48 to "E-5", 52 to "C#5", 56 to "A-4",
    )

    /** The bell figure of one bar: semitones above the bar's chord root. */
    private val BELLS = intArrayOf(12, 19, 24, 15)

    fun build(): Song {
        val base = NesFdsSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[BASS - 1] = NesInstrument(name = "Triangle bass")
        bank[KICK - 1] = NesInstrument(
            name = "Kick",
            volume = NesEnvelope(listOf(15, 13, 10, 7, 4, 2, 0)),
            arpeggio = NesEnvelope(listOf(0, -1, -2, -3, -4, -5, -6)),
        )
        bank[SNARE - 1] = NesInstrument(name = "Snare", volume = NesEnvelope(listOf(14, 11, 9, 7, 6, 5, 4, 3, 2, 1, 0)), arpeggio = NesEnvelope(listOf(2, 0)))
        bank[HAT - 1] = NesInstrument(name = "Closed hat", volume = NesEnvelope(listOf(8, 5, 3, 1, 0)))
        bank[DRUM_KIT - 1] = DpcmStarterKit.instrument(0)
        bank[CHORD - 1] = NesInstrument(name = "Pulse chord", volume = NesEnvelope(listOf(7, 6, 6, 5, 4, 3)), duty = NesEnvelope(listOf(1)))
        bank[COUNTER - 1] = NesInstrument(name = "Pulse counter", volume = NesEnvelope(listOf(8, 7, 7, 6, 6, 5), loop = 5), duty = NesEnvelope(listOf(2)))
        bank[PULSE_LEAD - 1] = NesInstrument(
            name = "Pulse lead",
            volume = NesEnvelope(listOf(11, 10, 9, 9, 8), loop = 4),
            duty = NesEnvelope(listOf(2)),
        )
        bank[FM_BASS - 1] = fds(
            "FM bass",
            FdsSound(modulation = FdsPresets.modulation(WavePreset.SINE), modDepth = 0x10, autoMod = true),
            volume = NesEnvelope(listOf(15, 13, 12, 11, 10, 9, 8)),
        )
        bank[VIBRATO_LEAD - 1] = fds(
            "Vibrato lead",
            // About 6 Hz: the table runs CPU × speed / 2^22 times a second (NESdev "FDS audio"), so 14 is 5.97 Hz.
            FdsSound(waves = listOf(TWO_HARMONICS), modulation = FdsPresets.modulation(WavePreset.SINE), modSpeed = 14, modDepth = 4,
                modDelay = 18, masterVolume = FdsMasterVolume.TWO_THIRDS),
            volume = NesEnvelope(listOf(14, 13, 12, 12, 11, 11, 9, 6, 3, 0), loop = 4, release = 6),
        )
        bank[MORPH - 1] = fds(
            "PWM morph",
            // Four PWM widths cycle while the note holds; OFF moves to a sine that holds while the volume fades.
            FdsSound(waves = FdsPresets.waves(WavePreset.PWM, 4) + listOf(FdsPresets.SINE), repeat = listOf(3, 3, 3, 3, 1),
                loop = 0, release = 4, masterVolume = FdsMasterVolume.HALF),
            volume = NesEnvelope(listOf(6, 8, 10, 11, 11, 11, 10, 8, 6, 4, 2, 0), loop = 5, release = 7),
        )
        bank[BELL - 1] = fds(
            "FM bell",
            FdsSound(modulation = FdsPresets.modulation(WavePreset.SINE), modDepth = 0x30, autoMod = true, autoModNumer = 7,
                autoModDenom = 2, masterVolume = FdsMasterVolume.TWO_THIRDS),
            volume = NesEnvelope(listOf(15, 12, 10, 8, 7, 6, 5, 4, 3, 3, 2, 2, 1, 1, 0)),
        )
        return base.copy(
            title = "Side B",
            artist = "Thor Tracker",
            comment = "NES + FDS demo: FM bass (auto-mod, Hxx depth sweep), vibrato lead (mod delay, Jxx/Ixx speed), PWM morph " +
                "pad with a release, FM bell (auto-mod 7/2, X00); 2A03 chords, counter-melody, bass, drums and DPCM kit. Loops to order 1.",
            ticksPerRow = 6,
            loopOrder = 1,
            patterns = listOf(intro(), theme(), bridge(), bells()),
            orders = listOf(0, 1, 2, 3),
            sound = base.nes.copy(instruments = bank, samples = DpcmStarterKit.samples()),
        )
    }

    private fun fds(name: String, sound: FdsSound, volume: NesEnvelope) =
        NesInstrument(name = name, volume = volume, chip = NesChip.FDS, fds = sound)

    /** One cycle of a fundamental plus a softer second harmonic, 0..63 (a drawn wave: FamiStudio's "Custom"). */
    private val TWO_HARMONICS: List<Int> = List(FdsSound.WAVE_SIZE) { i ->
        val t = 2 * PI * i / FdsSound.WAVE_SIZE
        (31.5 + 20 * sin(t) + 10 * sin(2 * t)).roundToInt().coerceIn(FdsSound.SAMPLE)
    }

    private fun intro() = PatternBuilder("Intro", NesExpansion.FDS.channels).apply {
        var step = 0
        for (bar in 0 until 4) {
            val r = bar * 16
            for ((row, offset) in RIFF) {
                note(FDS, r + row, BASS_ROOTS[bar] + offset, FM_BASS)
                // The FM opens up: each note's depth a little deeper (every note sets one, so none returns to the sound's).
                fx(FDS, r + row, NesRules.FX_FDS_MOD_DEPTH, 0x04 + step++)
            }
            if (bar >= 2) chords(bar, ROOTS, ARPS)
            drums(bar, full = bar >= 1)
        }
        note(DPCM, 60, TOM_HIGH, DRUM_KIT)
        note(DPCM, 62, TOM_LOW, DRUM_KIT)
    }.build()

    private fun theme() = PatternBuilder("Theme", NesExpansion.FDS.channels).apply {
        for ((row, name) in THEME) note(FDS, row, name, VIBRATO_LEAD)
        // The held A#4: the vibrato speeds up (Jxx keeps the speed's high digit, 0), then the next note returns to 14.
        fx(FDS, 26, NesRules.FX_FDS_MOD_SPEED_LO, 0x0A)
        fx(FDS, 28, NesRules.FX_FDS_MOD_SPEED_LO, 0x12)
        fx(FDS, 30, NesRules.FX_FDS_MOD_SPEED_LO, 0x1C)
        // The held E-5: I01 makes the speed $10E, a growl, before OFF releases the lead.
        fx(FDS, 59, NesRules.FX_FDS_MOD_SPEED_HI, 0x01)
        cut(FDS, 62)
        for (bar in 0 until 4) {
            chords(bar, ROOTS, ARPS)
            counter(bar)
            bass(bar, BASS_ROOTS)
            drums(bar, full = true)
        }
    }.build()

    private fun bridge() = PatternBuilder("Bridge", NesExpansion.FDS.channels).apply {
        for ((row, name) in BRIDGE_LINE) note(PULSE1, row, name, PULSE_LEAD)
        fx(PULSE1, 0, NesRules.FX_VIBRATO, 0x52)
        fx(PULSE1, 60, NesRules.FX_VIBRATO, 0x00)
        for (bar in 0 until 4) {
            val r = bar * 16
            // The pad holds the chord's fifth for 12 rows, then OFF releases it onto the sine.
            note(FDS, r, ROOTS[bar] + 7, MORPH)
            cut(FDS, r + 12)
            counter(bar)
            bass(bar, BASS_ROOTS)
            drums(bar, full = bar != 3)
        }
        note(NOISE, 60, SNARE_NOTE, SNARE)
        note(NOISE, 62, SNARE_NOTE, SNARE)
    }.build()

    private fun bells() = PatternBuilder("Bells", NesExpansion.FDS.channels).apply {
        var strike = 0
        for (bar in 0 until 4) {
            val r = bar * 16
            for ((i, offset) in BELLS.withIndex()) {
                val row = r + i * 4
                note(FDS, row, ROOTS[bar] + offset, BELL)
                // Every other strike restarts the wave (X00, at the sound's own depth); the others set a smaller depth.
                if (i % 2 == 0) fx(FDS, row, NesRules.FX_PHASE_RESET, 0x00)
                else fx(FDS, row, NesRules.FX_FDS_MOD_DEPTH, 0x2C - 4 * strike++)
            }
            chords(bar, ROOTS, ARPS)
            counter(bar)
            bass(bar, BASS_ROOTS)
            drums(bar, full = true)
        }
    }.build()

    /** Arpeggio chords on the beats on Pulse 1. */
    private fun PatternBuilder.chords(bar: Int, roots: IntArray, arps: IntArray) {
        for (beat in listOf(0, 4, 8, 12)) {
            note(PULSE1, bar * 16 + beat, roots[bar], CHORD)
            fx(PULSE1, bar * 16 + beat, NesRules.FX_ARPEGGIO, arps[bar])
        }
    }

    /** Two counter-melody notes a bar on Pulse 2. */
    private fun PatternBuilder.counter(bar: Int) {
        val (first, second) = COUNTERS[bar]
        note(PULSE2, bar * 16 + 2, first, COUNTER)
        note(PULSE2, bar * 16 + 10, second, COUNTER)
    }

    /** Root, root, octave, root, fifth, octave on the triangle; the short notes are cut a few frames in. */
    private fun PatternBuilder.bass(bar: Int, roots: IntArray) {
        val r = bar * 16
        for ((row, offset) in listOf(0 to 0, 3 to 0, 6 to 12, 8 to 0, 11 to 7, 14 to 12)) {
            note(TRIANGLE, r + row, roots[bar] + offset, BASS)
            if (row == 3 || row == 6 || row == 11) fx(TRIANGLE, r + row, NesRules.FX_NOTE_CUT, 0x04)
        }
    }

    /** Noise and DPCM drums: hats only, or the whole kit when [full]. */
    private fun PatternBuilder.drums(bar: Int, full: Boolean) {
        val r = bar * 16
        for (row in listOf(2, 6, 10, 14)) note(NOISE, r + row, HAT_NOTE, HAT)
        if (!full) return
        for ((row, instrument) in listOf(0 to KICK, 4 to SNARE, 8 to KICK, 12 to SNARE)) {
            note(NOISE, r + row, if (instrument == KICK) KICK_NOTE else SNARE_NOTE, instrument)
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
