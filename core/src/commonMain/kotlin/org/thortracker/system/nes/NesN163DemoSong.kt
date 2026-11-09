package org.thortracker.system.nes

import org.thortracker.model.Song
import org.thortracker.system.nes.NesDemoSong.PatternBuilder
import org.thortracker.system.nes.NesDemoSong.n

/**
 * The bundled NES + N163 demo: "Wavetable Garden", A minor, speed 6, for any channel count. Intro, theme and bridge,
 * arranged 0-1-2-1 and looping to order 1. The N163's channels take the parts in this order, so the song grows with the
 * count: a lead whose three PWM waves sweep and loop (OFF releases it onto a sine as it fades), arpeggio chords on a thin
 * square, a sine pad, a saw bell restarted by X00 (phase reset), a triangle-wave counter-melody, a harmony, an echo of
 * the lead and a sine sub bass. Every sound has 16-sample waves (in tune) at its own RAM position (0, 16, … 112: eight
 * sounds fill the 128 samples eight channels leave), so no two channels overwrite each other's waves. The 2A03 keeps the
 * triangle bass, noise drums and the DPCM starter kit, and its pulses take the chords and the counter-melody when the
 * song has too few N163 channels for them.
 */
internal object NesN163DemoSong {
    private const val BASS = 1
    private const val KICK = 2
    private const val SNARE = 3
    private const val HAT = 4
    private const val DRUM_KIT = 5
    private const val PULSE_CHORD = 6
    private const val PULSE_COUNTER = 7
    private const val LEAD = 8
    private const val CHORD = 9
    private const val PAD = 10
    private const val BELL = 11
    private const val COUNTER = 12
    private const val HARMONY = 13
    private const val ECHO = 14
    private const val SUB = 15

    private const val PULSE1 = 0
    private const val PULSE2 = 1
    private const val TRIANGLE = NesRules.TRIANGLE
    private const val NOISE = NesRules.NOISE
    private const val DPCM = NesRules.DPCM

    /** The N163 parts in the order channels take them. */
    private enum class Part { LEAD, CHORD, PAD, BELL, COUNTER, HARMONY, ECHO, SUB }

    /** Chords Am F C G: arpeggio root and 0xy, the bass root, a pad note, the counter-melody's two notes. */
    private val ROOTS = intArrayOf(n("A-3"), n("F-3"), n("C-4"), n("G-3"))
    private val ARPS = intArrayOf(0x37, 0x47, 0x47, 0x47)
    private val BASS_ROOTS = intArrayOf(n("A-2"), n("F-2"), n("C-3"), n("G-2"))
    private val PADS = intArrayOf(n("E-4"), n("C-4"), n("E-4"), n("D-4"))
    private val COUNTERS = listOf("C-5" to "B-4", "A-4" to "C-5", "G-4" to "E-4", "D-5" to "B-4")

    /** Bridge chords Dm Am F E. */
    private val BRIDGE_ROOTS = intArrayOf(n("D-4"), n("A-3"), n("F-3"), n("E-3"))
    private val BRIDGE_ARPS = intArrayOf(0x37, 0x37, 0x47, 0x47)
    private val BRIDGE_BASS = intArrayOf(n("D-2"), n("A-2"), n("F-2"), n("E-2"))

    private val THEME = listOf(
        0 to "E-5", 4 to "A-5", 6 to "G-5", 8 to "E-5", 12 to "C-5", 14 to "D-5",
        16 to "C-5", 20 to "A-4", 24 to "C-5", 28 to "F-5",
        32 to "E-5", 36 to "G-5", 40 to "E-5", 44 to "C-5",
        48 to "D-5", 52 to "B-4", 56 to "G-4",
    )

    /** [channels] N163 channels (1..8). */
    fun build(channels: Int): Song {
        val system = NesN163System.of(channels)
        val base = system.blank()
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
        bank[PULSE_CHORD - 1] = NesInstrument(name = "Pulse chord", volume = NesEnvelope(listOf(8, 7, 6, 5, 4, 3)), duty = NesEnvelope(listOf(0)))
        bank[PULSE_COUNTER - 1] = NesInstrument(name = "Pulse counter", volume = NesEnvelope(listOf(9, 8, 8, 7, 7, 6), loop = 5), duty = NesEnvelope(listOf(2)))
        bank[LEAD - 1] = n163(
            "PWM lead", LEAD,
            // Three PWM widths looping while the note holds; OFF releases into a sine that holds as the volume fades.
            N163Wave(waves = N163Presets.waves(WavePreset.PWM, 16, 3) + listOf(N163Presets.sine(16)), repeat = listOf(4, 4, 4, 1),
                loop = 0, release = 3),
            volume = NesEnvelope(listOf(13, 12, 11, 11, 10, 10, 8, 6, 4, 2, 0), loop = 3, release = 6),
        )
        bank[CHORD - 1] = n163("Thin chord", CHORD, wave(WavePreset.SQUARE25), volume = NesEnvelope(listOf(10, 9, 8, 7, 6, 5, 5, 4)))
        bank[PAD - 1] = n163("Sine pad", PAD, wave(WavePreset.SINE), volume = NesEnvelope(listOf(4, 6, 8, 9, 10, 10, 9), loop = 6))
        bank[BELL - 1] = n163("Saw bell", BELL, wave(WavePreset.SAWTOOTH), volume = NesEnvelope(listOf(12, 10, 8, 7, 6, 5, 4, 3, 2, 1, 0)))
        bank[COUNTER - 1] = n163("Triangle counter", COUNTER, wave(WavePreset.TRIANGLE), volume = NesEnvelope(listOf(9, 9, 8, 8, 7), loop = 4))
        bank[HARMONY - 1] = n163("Square harmony", HARMONY, wave(WavePreset.SQUARE50), volume = NesEnvelope(listOf(7, 7, 6, 6, 5), loop = 4))
        bank[ECHO - 1] = n163("Sine echo", ECHO, wave(WavePreset.SINE), volume = NesEnvelope(listOf(6, 5, 4, 3, 2, 1, 0)))
        bank[SUB - 1] = n163("Sine sub", SUB, wave(WavePreset.SINE), volume = NesEnvelope(listOf(12, 12, 11, 10), loop = 3))
        val parts = Part.entries.take(channels)
        return base.copy(
            title = "Wavetable Garden",
            artist = "Thor Tracker",
            comment = "NES + N163 demo ($channels channel${if (channels == 1) "" else "s"}): PWM wave lead, ${parts.drop(1).joinToString { it.name.lowercase() }}" +
                (if (channels > 1) " on N163 channels; " else " on its N163 channel; ") +
                "2A03 bass, drums and DPCM kit. Loops to order 1.",
            ticksPerRow = 6,
            loopOrder = 1,
            patterns = listOf(intro(parts), theme(parts), bridge(parts)),
            orders = listOf(0, 1, 2, 1),
            sound = base.nes.copy(instruments = bank, samples = DpcmStarterKit.samples()),
        )
    }

    /** An N163 sound at RAM position 16 × (its rank among the N163 sounds), so all eight fit 8 channels' 128 samples. */
    private fun n163(name: String, slot: Int, wave: N163Wave, volume: NesEnvelope) =
        NesInstrument(name = name, volume = volume, chip = NesChip.N163, wave = wave.copy(position = 16 * (slot - LEAD)))

    private fun wave(preset: WavePreset) = N163Wave(waves = listOf(N163Presets.wave(preset, 16, 0, 1)))

    /** The N163 channel of [part], or -1 when the song has too few. */
    private fun List<Part>.channel(part: Part): Int = indexOf(part).let { if (it < 0) -1 else NesRules.N163_FIRST + it }

    private fun intro(parts: List<Part>) = PatternBuilder("Intro", NesRules.APU_CHANNELS + parts.size).apply {
        val pad = parts.channel(Part.PAD)
        val bell = parts.channel(Part.BELL)
        val sub = parts.channel(Part.SUB)
        for (bar in 0 until 4) {
            val r = bar * 16
            chords(parts, bar, ROOTS, ARPS)
            if (pad >= 0) note(pad, r, PADS[bar], PAD)
            if (bell >= 0) for (step in 0 until 4) {
                note(bell, r + step * 4, ROOTS[bar] + intArrayOf(12, 19, 24, 19)[step], BELL)
                fx(bell, r + step * 4, NesRules.FX_PHASE_RESET, 0) // each strike restarts the saw from its first sample
            }
            if (bar >= 2) {
                bass(bar, BASS_ROOTS)
                if (sub >= 0) note(sub, r, BASS_ROOTS[bar] + 12, SUB)
            }
            for (row in listOf(2, 6, 10, 14)) note(NOISE, r + row, HAT_NOTE, HAT)
        }
        if (pad >= 0) fx(pad, 0, NesRules.FX_VOLUME_SLIDE, 0x2C) // the pad swells in
        note(NOISE, 56, SNARE_NOTE, SNARE)
        note(NOISE, 60, SNARE_NOTE, SNARE)
        note(DPCM, 60, TOM_HIGH, DRUM_KIT)
        note(DPCM, 62, TOM_LOW, DRUM_KIT)
        // The lead's pickup: B-4 gliding up a fourth into the theme, then released (its waves and volume fade out).
        val lead = parts.channel(Part.LEAD)
        note(lead, 56, "B-4", LEAD); fx(lead, 56, NesRules.FX_SLIDE_UP, 0x05)
        cut(lead, 63)
    }.build()

    private fun theme(parts: List<Part>) = PatternBuilder("Theme", NesRules.APU_CHANNELS + parts.size).apply {
        val lead = parts.channel(Part.LEAD)
        val harmony = parts.channel(Part.HARMONY)
        val echo = parts.channel(Part.ECHO)
        val pad = parts.channel(Part.PAD)
        val sub = parts.channel(Part.SUB)
        for ((row, name) in THEME) {
            note(lead, row, name, LEAD)
            if (harmony >= 0) note(harmony, row, n(name) - 4, HARMONY) // a major third below, mostly in key
            if (echo >= 0 && row + 3 < 64) note(echo, row + 3, name, ECHO)
        }
        fx(lead, 8, NesRules.FX_VIBRATO, 0x63)
        fx(lead, 12, NesRules.FX_VIBRATO, 0x00)
        fx(lead, 24, NesRules.FX_SLIDE_UP, 0x05) // C-5 rising to F-5
        fx(lead, 44, NesRules.FX_FINE_PITCH, 0x84) // a slightly sharp C-5
        fx(lead, 56, NesRules.FX_VIBRATO, 0x74)
        cut(lead, 62) // releases the lead into its tail
        fx(lead, 63, NesRules.FX_VIBRATO, 0x00)
        if (harmony >= 0) cut(harmony, 62)
        for (bar in 0 until 4) {
            val r = bar * 16
            chords(parts, bar, ROOTS, ARPS)
            counter(parts, bar)
            if (pad >= 0) { note(pad, r, PADS[bar], PAD); fx(pad, r, NesRules.FX_VOLUME, 0x09) }
            if (sub >= 0) note(sub, r, BASS_ROOTS[bar] + 12, SUB)
            bass(bar, BASS_ROOTS)
            drums(bar)
        }
        // A late ghost snare, delayed into the middle of its row.
        note(NOISE, 47, SNARE_NOTE, SNARE); fx(NOISE, 47, NesRules.FX_NOTE_DELAY, 0x03)
    }.build()

    private fun bridge(parts: List<Part>) = PatternBuilder("Bridge", NesRules.APU_CHANNELS + parts.size).apply {
        val lead = parts.channel(Part.LEAD)
        val bell = parts.channel(Part.BELL)
        val sub = parts.channel(Part.SUB)
        val line = listOf("F-5", "E-5", "D-5", "A-4", "E-5", "C-5", "A-4", "E-4", "F-4", "A-4", "C-5", "F-5", "E-5", "G#4", "B-4", "E-5")
        for ((i, name) in line.withIndex()) note(lead, i * 4, name, LEAD)
        fx(lead, 0, NesRules.FX_VOLUME_SLIDE, 0x6D) // the lead swells back in
        fx(lead, 60, NesRules.FX_SLIDE_DOWN, 0x05) // E-5 sinking to B-4
        cut(lead, 63)
        for (bar in 0 until 4) {
            val r = bar * 16
            chords(parts, bar, BRIDGE_ROOTS, BRIDGE_ARPS)
            if (bell >= 0) {
                note(bell, r + 8, BRIDGE_ROOTS[bar] + 24, BELL)
                fx(bell, r + 8, NesRules.FX_PHASE_RESET, 0)
            }
            if (sub >= 0) note(sub, r, BRIDGE_BASS[bar] + 12, SUB)
            bass(bar, BRIDGE_BASS)
            drums(bar)
        }
    }.build()

    /** Arpeggio chords on the beats: the N163's thin square, or the 2A03's Pulse 1 when the song has no chord channel. */
    private fun PatternBuilder.chords(parts: List<Part>, bar: Int, roots: IntArray, arps: IntArray) {
        val channel = parts.channel(Part.CHORD).let { if (it < 0) PULSE1 else it }
        val instrument = if (channel == PULSE1) PULSE_CHORD else CHORD
        for (beat in listOf(0, 4, 8, 12)) {
            note(channel, bar * 16 + beat, roots[bar], instrument)
            fx(channel, bar * 16 + beat, NesRules.FX_ARPEGGIO, arps[bar])
        }
    }

    /** Two counter-melody notes a bar: the N163's triangle wave, or the 2A03's Pulse 2 without a counter channel. */
    private fun PatternBuilder.counter(parts: List<Part>, bar: Int) {
        val channel = parts.channel(Part.COUNTER).let { if (it < 0) PULSE2 else it }
        val instrument = if (channel == PULSE2) PULSE_COUNTER else COUNTER
        val (first, second) = COUNTERS[bar]
        note(channel, bar * 16 + 2, first, instrument)
        note(channel, bar * 16 + 10, second, instrument)
    }

    /** Root, root, octave, root, fifth, octave on the triangle; the short notes are cut a few frames in. */
    private fun PatternBuilder.bass(bar: Int, roots: IntArray) {
        val r = bar * 16
        for ((row, offset) in listOf(0 to 0, 3 to 0, 6 to 12, 8 to 0, 11 to 7, 14 to 12)) {
            note(TRIANGLE, r + row, roots[bar] + offset, BASS)
            if (row == 3 || row == 6 || row == 11) fx(TRIANGLE, r + row, NesRules.FX_NOTE_CUT, 0x04)
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
