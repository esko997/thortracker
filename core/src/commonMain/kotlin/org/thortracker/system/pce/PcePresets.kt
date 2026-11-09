package org.thortracker.system.pce

/**
 * Starting points for PC Engine songs: the built-in [WAVES] a new song has (original tables: plain shapes and a few
 * additive mixes, each normalised to the full 0..31 range) and the sounds a new song has in slots 01.. ([ALL]; the
 * instrument editor's Preset control loads any of them into a slot). [Category.TONE] presets play on every channel and
 * carry the wave they were voiced with ([Preset.wave]: the editor reuses an identical song wave or adds it, then points
 * [PceInstrument.wave] at it); [Category.NOISE] presets play the noise generator on PSG 5 and PSG 6 only, the note
 * picking the noise rate (D#5 plays the fastest, rate 31). Sample sounds come from [PceStarterKit] and `.wav` imports.
 */
object PcePresets {
    enum class Category { TONE, NOISE }

    /** A starting sound: [name] is its [instrument]'s name; a tone's [wave] is its 32 values ([PceInstrument.wave] = 0). */
    data class Preset(val name: String, val category: Category, val instrument: PceInstrument, val wave: List<Int>? = null)

    private val SINE = listOf(16, 19, 21, 24, 26, 28, 30, 31, 31, 31, 30, 28, 26, 24, 21, 19, 16, 12, 10, 7, 5, 3, 1, 0, 0, 0, 1, 3, 5, 7, 10, 12)
    private val TRIANGLE = listOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 17, 19, 21, 23, 25, 27, 29, 31, 29, 27, 25, 23, 21, 19, 17, 16, 14, 12, 10, 8, 6, 4, 2)
    private val SAW = List(32) { it }
    private val SQUARE = List(32) { if (it < 16) 31 else 0 }
    private val PULSE_25 = List(32) { if (it < 8) 31 else 0 }
    private val PULSE_12 = List(32) { if (it < 4) 31 else 0 }

    /** Harmonics 1, 2, 4, 8 at 1, ½, ¼, ⅛: drawbar organ. */
    private val ORGAN = listOf(15, 24, 28, 29, 31, 31, 28, 26, 28, 29, 26, 21, 18, 16, 13, 12, 15, 19, 18, 15, 13, 10, 5, 2, 3, 5, 3, 0, 0, 2, 3, 7)

    /** Odd harmonics 1, 3, 5 at 1, 0.6, 0.25: woody, clarinet-like. */
    private val HOLLOW = listOf(15, 25, 31, 31, 28, 25, 23, 24, 24, 24, 23, 25, 28, 31, 31, 25, 16, 6, 0, 0, 3, 6, 8, 7, 7, 7, 8, 6, 3, 0, 0, 6)

    /** The first four harmonics of a saw: rounder than the full saw. */
    private val SOFT_SAW = listOf(16, 23, 29, 31, 30, 28, 25, 23, 22, 23, 23, 22, 20, 18, 16, 16, 16, 15, 15, 13, 11, 9, 8, 8, 9, 8, 6, 3, 1, 0, 2, 8)

    /** Harmonics 1, 2, 3 at 1, 0.7, 0.2: a full bass. */
    private val BASS = listOf(16, 21, 26, 29, 31, 31, 29, 26, 24, 21, 19, 18, 17, 17, 16, 16, 16, 15, 15, 14, 14, 13, 12, 10, 7, 5, 2, 0, 0, 2, 5, 10)

    /** Harmonics 1, 2, 3, 5 with a strong second: nasal reed. */
    private val REED = listOf(15, 25, 31, 31, 28, 24, 21, 19, 16, 13, 10, 10, 13, 17, 20, 19, 16, 12, 11, 14, 18, 21, 21, 18, 15, 12, 10, 7, 3, 0, 0, 6)

    /** Harmonics 1, 3, 7, 11: a glassy, bell-like edge. */
    private val BELL = listOf(16, 29, 26, 26, 29, 28, 31, 24, 15, 24, 31, 28, 29, 26, 26, 29, 16, 2, 5, 5, 2, 3, 0, 7, 16, 7, 0, 3, 2, 5, 5, 2)

    /** A new song's waves, in order (wave 00 = Sine). */
    val WAVES: List<Pair<String, List<Int>>> = listOf(
        "Sine" to SINE,
        "Triangle" to TRIANGLE,
        "Saw" to SAW,
        "Square" to SQUARE,
        "Pulse 25%" to PULSE_25,
        "Pulse 12.5%" to PULSE_12,
        "Organ" to ORGAN,
        "Hollow" to HOLLOW,
        "Soft saw" to SOFT_SAW,
        "Bass" to BASS,
        "Reed" to REED,
        "Bell" to BELL,
    )

    private fun env(vararg values: Int, loop: Int = -1, release: Int = -1) = PceEnvelope(values.toList(), loop, release)

    private fun tone(name: String, wave: List<Int>, volume: PceEnvelope, arpeggio: PceEnvelope = PceEnvelope.NONE,
                     pitch: PceEnvelope = PceEnvelope.NONE, relative: Boolean = false) =
        Preset(name, Category.TONE, PceInstrument(name, PceKind.TONE, 0, volume, arpeggio, pitch, relative), wave)

    private fun noise(name: String, volume: PceEnvelope, arpeggio: PceEnvelope = PceEnvelope.NONE) =
        Preset(name, Category.NOISE, PceInstrument(name, PceKind.NOISE, volume = volume, arpeggio = arpeggio))

    val ALL: List<Preset> = listOf(
        // A pulse lead that settles a little, fading out after OFF.
        tone("Lead", PULSE_25, env(31, 29, 27, 26, 25, 24, 20, 16, 12, 8, 4, 0, release = 6)),
        tone("Square", SQUARE, env(31, 28, 26, 25, 18, 10, 4, 0, release = 4)),
        // Slow swell, held, slow fade after OFF.
        tone("Sine pad", SINE, env(6, 10, 14, 18, 22, 26, 29, 31, 28, 24, 20, 16, 12, 8, 4, 0, release = 8)),
        tone("Organ", ORGAN, env(31, 20, 10, 0, release = 1)),
        tone("Bass", BASS, env(31, 30, 28, 26, 24, 22, 21, 20, 12, 4, 0, release = 8)),
        // A plucked string: decays to silence by itself.
        tone("Pluck", SOFT_SAW, env(31, 28, 25, 22, 20, 18, 16, 14, 12, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)),
        // Swells in while the pitch scoops up from a quarter tone below.
        tone("Brass", SAW, env(18, 24, 29, 31, 30, 29, 28, 20, 12, 4, 0, release = 7), pitch = env(-32, -24, -16, -8, 0)),
        tone("Bell", BELL, env(31, 27, 24, 22, 20, 18, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)),
        tone("Reed", REED, env(28, 31, 30, 29, 18, 8, 0, release = 4)),
        tone("Flute", TRIANGLE, env(14, 22, 28, 31, 30, 29, 16, 6, 0, release = 6)),
        tone("Thin pulse", PULSE_12, env(31, 26, 23, 21, 20, 10, 0, release = 5)),
        tone("Clarinet", HOLLOW, env(24, 30, 31, 30, 14, 4, 0, release = 4)),
        // An octave arpeggio chip lead.
        tone("Octave chip", SQUARE, env(31, 27, 25, 24, 12, 0, release = 4), arpeggio = env(0, 0, 12, 12, loop = 0)),
        // Drums from tones: a sine whose pitch falls a semitone (kick) or a third of one (tom) per tick.
        tone("Kick", SINE, env(31, 30, 28, 25, 21, 16, 11, 6, 2, 0), pitch = env(0, -64, loop = 1), relative = true),
        tone("Tom", SINE, env(31, 28, 25, 22, 19, 16, 13, 10, 8, 6, 4, 2, 1, 0), pitch = env(0, -20, loop = 1), relative = true),
        // Noise (PSG 5-6): the note picks the rate; D#5 (and every 32 semitones from G-2) is the fastest.
        noise("Hi-hat", env(24, 16, 10, 6, 3, 1, 0)),
        noise("Open hat", env(26, 24, 22, 20, 18, 16, 14, 12, 10, 8, 6, 5, 4, 3, 2, 1, 0)),
        // The rate steps down by an arpeggio after the attack.
        noise("Snare", env(31, 26, 22, 18, 14, 11, 8, 6, 4, 2, 1, 0), arpeggio = env(0, -3, -5, -6, -7)),
        noise("Noise kick", env(31, 24, 16, 8, 0), arpeggio = env(0, -6, -12, -18, -24)),
        noise("Crash", env(31, 29, 27, 25, 23, 21, 19, 17, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)),
    )

    /** The preset named [name], or null. */
    fun named(name: String): Preset? = ALL.firstOrNull { it.name == name }
}
