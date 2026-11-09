package org.thortracker.system.scc

import org.thortracker.system.nes.NesEnvelope

/** A named starting point for an SCC instrument slot. */
data class SccPreset(val name: String, val instrument: SccInstrument)

/**
 * The SCC's starter waves and instrument presets, written for Thor Tracker (no game's data). A new song gets [WAVES]
 * as waves 1..[WAVES].size and the presets in its first slots ([SccSound.blank]); the presets' wave numbers refer to
 * [WAVES]. Drums are instruments too: a sine with a relative pitch sweep for the kick, the noise-like wave for the
 * snare and hat (the SCC has no noise generator).
 */
object SccPresets {
    // Wave numbers of [WAVES] (1-based, as cells and envelopes name them).
    const val SINE = 1
    const val TRIANGLE = 2
    const val SAW = 3
    const val SQUARE = 4
    const val PULSE_25 = 5
    const val PULSE_12 = 6
    const val ORGAN = 7
    const val BELL = 8
    const val BASS = 9
    const val NOISE = 10

    /** The starter waves, in wave-number order. */
    val WAVES: List<SccWave> = listOf(
        SccWave("Sine", SccWaveShapes.sine()),
        SccWave("Triangle", SccWaveShapes.triangle()),
        SccWave("Saw", SccWaveShapes.saw()),
        SccWave("Square", SccWaveShapes.square()),
        SccWave("Pulse 25%", SccWaveShapes.pulse(8)),
        SccWave("Pulse 12.5%", SccWaveShapes.pulse(4)),
        // Drawbar-like octaves: 8', 4', 2', 1'.
        SccWave("Organ", SccWaveShapes.additive(listOf(1 to 1.0, 2 to 0.6, 4 to 0.4, 8 to 0.25))),
        // Odd partials with a strong 7th: a hollow, clangy tone that suits short decays.
        SccWave("Bell", SccWaveShapes.additive(listOf(1 to 1.0, 3 to 0.45, 5 to 0.3, 7 to 0.55))),
        // A soft saw: fundamental-heavy, so low notes stay round.
        SccWave("Bass", SccWaveShapes.additive(listOf(1 to 1.0, 2 to 0.55, 3 to 0.25))),
        SccWave("Noise", SccWaveShapes.noise(0x5CC)),
    )

    private fun env(vararg values: Int, loop: Int = -1, release: Int = -1) = NesEnvelope(values.toList(), loop, release)

    private fun wave(vararg waves: Int, loop: Int = -1) = NesEnvelope(waves.toList(), loop)

    val ALL: List<SccPreset> = listOf(
        // Sustains at 12 while held; the release fades it out.
        SccPreset("Lead", SccInstrument("Lead",
            volume = env(15, 14, 13, 12, 9, 6, 4, 2, 0, release = 4),
            wave = wave(PULSE_25))),
        // Restarted waveform for a consistent attack, settling to 11.
        SccPreset("Bass", SccInstrument("Bass",
            volume = env(15, 14, 13, 12, 11),
            wave = wave(BASS), restart = true)),
        // A struck decay down to 3, held while keyed; the release takes it to silence.
        SccPreset("Bell", SccInstrument("Bell",
            volume = env(15, 13, 12, 11, 10, 9, 9, 8, 8, 7, 7, 6, 6, 5, 5, 5, 4, 4, 4, 3, 2, 1, 1, 0, release = 20),
            wave = wave(BELL), restart = true)),
        // Slow swell, gentle absolute pitch wobble (±1/8 semitone), long release.
        SccPreset("Pad", SccInstrument("Pad",
            volume = env(2, 4, 6, 8, 9, 10, 11, 11, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0, release = 8),
            pitch = env(0, 1, 2, 2, 1, 0, -1, -2, -2, -1, loop = 0),
            wave = wave(TRIANGLE))),
        // Fast decay to silence: plays staccato by itself.
        SccPreset("Pluck", SccInstrument("Pluck",
            volume = env(15, 12, 10, 8, 6, 5, 4, 3, 2, 1, 0),
            wave = wave(SAW), restart = true)),
        // Organs stop at once: no release point, so OFF silences it.
        SccPreset("Organ", SccInstrument("Organ",
            volume = env(15, 14),
            wave = wave(ORGAN))),
        // A sine swept down by a relative pitch envelope (in 1/16 semitones per tick, accumulating).
        SccPreset("Kick", SccInstrument("Kick",
            volume = env(15, 15, 14, 13, 11, 9, 7, 5, 3, 2, 1, 0),
            pitch = env(-40, -32, -24, -16, -12, -8),
            pitchRelative = true,
            wave = wave(SINE), restart = true)),
        // One tick of sine for the body, then the noise wave.
        SccPreset("Snare", SccInstrument("Snare",
            volume = env(15, 14, 12, 10, 8, 6, 5, 4, 3, 2, 1, 0),
            pitch = env(-6, -4, -2, 0),
            pitchRelative = true,
            wave = wave(SINE, NOISE), restart = true)),
        SccPreset("Hat", SccInstrument("Hat",
            volume = env(11, 7, 4, 2, 1, 0),
            wave = wave(NOISE), restart = true)),
        // Octave flutter every two ticks: the chip-lead staple; add 0xy for chords.
        SccPreset("Arp lead", SccInstrument("Arp lead",
            volume = env(14, 13, 12, 12, 11, 8, 5, 2, 0, release = 5),
            arpeggio = env(0, 0, 12, 12, loop = 0),
            wave = wave(PULSE_12))),
        // Decaying repeats written into the volume envelope: an echo without a second channel.
        SccPreset("Echo", SccInstrument("Echo",
            volume = env(15, 13, 11, 9, 0, 0, 0, 9, 8, 6, 0, 0, 0, 6, 5, 4, 0, 0, 0, 4, 3, 2, 0, 0, 0, 2, 1, 1, 0),
            wave = wave(SQUARE))),
        // A wave envelope sweeping sine → triangle → saw → square and back, looping: the timbre moves while held.
        SccPreset("Morph", SccInstrument("Morph",
            volume = env(13, 12, 12, 9, 6, 3, 0, release = 3),
            wave = wave(SINE, SINE, SINE, TRIANGLE, TRIANGLE, TRIANGLE, SAW, SAW, SAW, SQUARE, SQUARE, SQUARE,
                SAW, SAW, SAW, TRIANGLE, TRIANGLE, TRIANGLE, loop = 0))),
    )

    private val BY_NAME = ALL.associateBy { it.name }

    fun named(name: String): SccPreset? = BY_NAME[name]
}
