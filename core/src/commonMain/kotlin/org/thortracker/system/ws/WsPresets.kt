package org.thortracker.system.ws

/**
 * Starting points for WonderSwan songs: the built-in [WAVES] a new song has (original 32-step, 4-bit tables: plain
 * shapes and additive mixes computed in integers, each spanning 0..15) and the sounds the instrument editor's Preset
 * control loads ([presets]). [Category.TONE] presets carry the wave they were voiced with ([Preset.wave]: the editor
 * reuses an identical song wave or adds it); the sweep presets bend in hardware on CH3 only. [Category.NOISE] presets
 * play channel 4's LFSR with one of its tap modes. [Category.SAMPLE] presets carry a starter-kit sample
 * ([Preset.sample], added to the song when it has none with the same bytes): CPU-fed on both models, Sound DMA ones on
 * the WonderSwan Color only. A new song's slots 01.. hold the tone and noise presets.
 */
object WsPresets {
    enum class Category { TONE, NOISE, SAMPLE }

    /**
     * A starting sound: [name] is its [instrument]'s name; a tone's [wave] is its 32 values ([WsInstrument.wave] = 0); a
     * sample preset's [sample] is the sample it plays ([WsInstrument.sample] = 0).
     */
    data class Preset(
        val name: String,
        val category: Category,
        val instrument: WsInstrument,
        val wave: List<Int>? = null,
        val sample: WsSample? = null,
    )

    /** floor(127 sin(2π i / 64) + 0.5), i = 0..63: the player's vibrato table, here the additive waves' source. */
    private val SIN64 = intArrayOf(
        0, 12, 25, 37, 49, 60, 71, 81, 90, 98, 106, 112, 117, 122, 125, 126,
        127, 126, 125, 122, 117, 112, 106, 98, 90, 81, 71, 60, 49, 37, 25, 12,
        0, -12, -25, -37, -49, -60, -71, -81, -90, -98, -106, -112, -117, -122, -125, -126,
        -127, -126, -125, -122, -117, -112, -106, -98, -90, -81, -71, -60, -49, -37, -25, -12,
    )

    /**
     * The sum of harmonics (number, amplitude) over 32 steps, scaled to the full 0..15 range in integers:
     * (v − min) · 15 + (max − min) / 2, divided by max − min.
     */
    private fun additive(vararg harmonics: Pair<Int, Int>): List<Int> {
        val v = IntArray(32) { i -> harmonics.sumOf { (k, a) -> a * SIN64[(2 * k * i) % 64] } }
        val lo = v.min()
        val span = maxOf(1, v.max() - lo)
        return v.map { ((it - lo) * 15 + span / 2) / span }
    }

    private val SINE = listOf(8, 9, 10, 12, 13, 14, 14, 15, 15, 15, 14, 14, 13, 12, 10, 9, 8, 6, 5, 3, 2, 1, 1, 0, 0, 0, 1, 1, 2, 3, 5, 6)
    private val TRIANGLE = List(32) { if (it < 16) it else 31 - it }
    private val SAW = List(32) { it / 2 }
    private val SQUARE = List(32) { if (it < 16) 15 else 0 }
    private val PULSE_25 = List(32) { if (it < 8) 15 else 0 }
    private val PULSE_12 = List(32) { if (it < 4) 15 else 0 }

    /** Harmonics 1, 2, 4, 8 at 8, 4, 2, 1: drawbar organ. */
    private val ORGAN = additive(1 to 8, 2 to 4, 4 to 2, 8 to 1)

    /** Odd harmonics 1, 3, 5 at 10, 6, 3: woody, clarinet-like. */
    private val HOLLOW = additive(1 to 10, 3 to 6, 5 to 3)

    /** The first four harmonics of a saw: rounder than the full saw. */
    private val SOFT_SAW = additive(1 to 12, 2 to 6, 3 to 4, 4 to 3)

    /** Harmonics 1, 2, 3 at 10, 7, 2: a full bass. */
    private val BASS = additive(1 to 10, 2 to 7, 3 to 2)

    /** Harmonics 1, 2, 3, 5 with a strong second: nasal reed. */
    private val REED = additive(1 to 10, 2 to 8, 3 to 5, 5 to 3)

    /** Harmonics 1, 3, 7, 11: a glassy, bell-like edge. */
    private val BELL = additive(1 to 8, 3 to 6, 7 to 4, 11 to 3)

    /** A strong second harmonic over the fundamental: a vowel-like "ah". */
    private val VOWEL = additive(1 to 6, 2 to 10, 3 to 4)

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
        "Vowel" to VOWEL,
    )

    private fun env(vararg values: Int, loop: Int = -1, release: Int = -1) = WsEnvelope(values.toList(), loop, release)

    private fun tone(name: String, wave: List<Int>, volume: WsEnvelope, arpeggio: WsEnvelope = WsEnvelope.NONE,
                     pitch: WsEnvelope = WsEnvelope.NONE, relative: Boolean = false, sweep: Int = 0, sweepTime: Int = 0) =
        Preset(name, Category.TONE, WsInstrument(name, WsKind.TONE, wave = 0, volume = volume, arpeggio = arpeggio, pitch = pitch,
            pitchRelative = relative, sweep = sweep, sweepTime = sweepTime), wave)

    private fun noise(name: String, tap: Int, volume: WsEnvelope, arpeggio: WsEnvelope = WsEnvelope.NONE,
                      taps: WsEnvelope = WsEnvelope.NONE, reset: Boolean = false) =
        Preset(name, Category.NOISE, WsInstrument(name, WsKind.NOISE, tap = tap, volume = volume, arpeggio = arpeggio, taps = taps, lfsrReset = reset))

    private fun sample(name: String, sample: WsSample, feed: WsFeed, loop: Boolean = false) =
        Preset(name, Category.SAMPLE, WsInstrument(name, WsKind.SAMPLE, sample = 0, feed = feed, root = WsInstrument.ROOT,
            rate = WsStarterKit.DMA_RATE, loop = loop), sample = sample)

    private val TONES: List<Preset> = listOf(
        // A pulse lead that settles a little, fading out after OFF.
        tone("Lead", PULSE_25, env(15, 14, 13, 13, 12, 12, 10, 8, 6, 4, 2, 0, release = 6)),
        tone("Square", SQUARE, env(15, 14, 13, 12, 9, 5, 2, 0, release = 4)),
        // Slow swell, held, slow fade after OFF.
        tone("Sine pad", SINE, env(3, 5, 7, 9, 11, 13, 14, 15, 14, 12, 10, 8, 6, 4, 2, 0, release = 8)),
        tone("Organ", ORGAN, env(15, 10, 5, 0, release = 1)),
        tone("Bass", BASS, env(15, 15, 14, 13, 12, 11, 10, 10, 6, 2, 0, release = 8)),
        // A plucked string: decays to silence by itself.
        tone("Pluck", SOFT_SAW, env(15, 14, 12, 11, 10, 9, 8, 7, 6, 5, 4, 4, 3, 3, 2, 2, 1, 1, 0)),
        // Swells in while the pitch scoops up from a quarter tone below.
        tone("Brass", SAW, env(9, 12, 14, 15, 15, 14, 14, 10, 6, 2, 0, release = 7), pitch = env(-32, -24, -16, -8, 0)),
        tone("Bell", BELL, env(15, 13, 12, 11, 10, 9, 8, 7, 7, 6, 6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1, 0)),
        tone("Reed", REED, env(14, 15, 15, 14, 9, 4, 0, release = 4)),
        tone("Flute", TRIANGLE, env(7, 11, 14, 15, 15, 14, 8, 3, 0, release = 6)),
        tone("Thin pulse", PULSE_12, env(15, 13, 11, 10, 10, 5, 0, release = 5)),
        tone("Clarinet", HOLLOW, env(12, 15, 15, 15, 7, 2, 0, release = 4)),
        tone("Vowel", VOWEL, env(10, 13, 15, 15, 14, 7, 0, release = 5)),
        // An octave arpeggio chip lead.
        tone("Octave chip", SQUARE, env(15, 13, 12, 12, 6, 0, release = 4), arpeggio = env(0, 0, 12, 12, loop = 0)),
        // Drums from tones: a sine whose pitch falls a semitone (kick) or a third of one (tom) per tick.
        tone("Kick", SINE, env(15, 15, 14, 12, 10, 8, 5, 3, 1, 0), pitch = env(0, -64, loop = 1), relative = true),
        tone("Tom", SINE, env(15, 14, 12, 11, 9, 8, 6, 5, 4, 3, 2, 1, 0), pitch = env(0, -20, loop = 1), relative = true),
        // CH3's hardware sweep: the frequency register moves by the sweep value every (time + 1) / 375 s.
        tone("Laser (CH3)", SQUARE, env(15, 14, 13, 12, 11, 10, 8, 6, 4, 2, 0), sweep = -24, sweepTime = 0),
        tone("Rise (CH3)", PULSE_25, env(13, 14, 15, 15, 14, 12, 9, 6, 3, 0, release = 4), sweep = 4, sweepTime = 2),
        tone("Sweep kick (CH3)", SINE, env(15, 15, 13, 11, 8, 5, 2, 0), sweep = -48, sweepTime = 0),
        tone("Slow rise (CH3)", TRIANGLE, env(12, 13, 14, 15, 14, 10, 6, 2, 0, release = 4), sweep = 1, sweepTime = 4),
    )

    private val NOISES: List<Preset> = listOf(
        // Tap mode 0: the 32767-step sequence, white noise.
        noise("Hi-hat", 0, env(12, 8, 5, 3, 1, 0)),
        noise("Open hat", 0, env(13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)),
        // The pitch steps down by an arpeggio after the attack.
        noise("Snare", 0, env(15, 13, 11, 9, 7, 5, 4, 3, 2, 1, 0), arpeggio = env(0, -3, -5, -6, -7)),
        noise("Crash", 0, env(15, 14, 13, 12, 11, 10, 9, 9, 8, 7, 7, 6, 5, 5, 4, 3, 3, 2, 1, 1, 0)),
        // Short sequences buzz at a pitch: tap mode 4 (73 steps) restarted each note, mode 7 (28 steps).
        noise("Metal", 4, env(15, 12, 9, 7, 5, 3, 1, 0), reset = true),
        noise("Buzz", 7, env(14, 14, 13, 12, 10, 8, 5, 2, 0, release = 4)),
        // White noise falling into a short buzz: a tap sequence.
        noise("Noise kick", 0, env(15, 12, 8, 4, 0), arpeggio = env(0, -6, -12, -18, -24), taps = env(0, 0, 2, 5, 7, loop = 4)),
    )

    private val SAMPLES_CPU: List<Preset> by lazy {
        val kit = WsStarterKit.samples()
        listOf(
            sample("Kick (voice)", kit[WsStarterKit.KICK], WsFeed.CPU),
            sample("Snare (voice)", kit[WsStarterKit.SNARE], WsFeed.CPU),
            sample("Hat (voice)", kit[WsStarterKit.HAT], WsFeed.CPU),
            sample("Tom (voice)", kit[WsStarterKit.TOM], WsFeed.CPU),
        )
    }

    private val SAMPLES_DMA: List<Preset> by lazy {
        val kit = WsStarterKit.samples()
        listOf(
            sample("Kick (DMA)", kit[WsStarterKit.KICK], WsFeed.DMA),
            sample("Snare (DMA)", kit[WsStarterKit.SNARE], WsFeed.DMA),
            sample("Hat (DMA)", kit[WsStarterKit.HAT], WsFeed.DMA),
            sample("Tom (DMA)", kit[WsStarterKit.TOM], WsFeed.DMA),
        )
    }

    /** The presets of the [color] model: tones, noise, CPU-fed samples, and on the Color the Sound DMA samples. */
    fun presets(color: Boolean): List<Preset> = TONES + NOISES + SAMPLES_CPU + (if (color) SAMPLES_DMA else emptyList())

    /** The preset named [name] on the Color (every preset), or null. */
    fun named(name: String): Preset? = presets(true).firstOrNull { it.name == name }
}
