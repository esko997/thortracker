package org.thortracker.system.ym2612

import org.thortracker.system.nes.NesEnvelope

/**
 * Starting sounds for Mega Drive instruments: a new song has them in slots 01.. (FM voices first, then FM drums, PSG
 * tones and Noise-channel sounds) and the instrument editor's Preset control loads any of them into a slot. Drums are
 * ordinary voices with the pitch sweep; [Preset.note] is the note they were voiced at (the note picks the drum's
 * pitch). [Category.FM] and [Category.DRUM] play on FM 1-6, [Category.PSG] on PSG 1-3 (and Noise), [Category.NOISE]
 * on the Noise channel.
 */
object YmPresets {
    enum class Category { FM, DRUM, PSG, NOISE }

    data class Preset(val instrument: YmInstrument, val category: Category, val note: Int? = null) {
        val name: String get() = instrument.name
    }

    private fun op(
        mul: Int, tl: Int, ar: Int = 31, d1r: Int = 0, d2r: Int = 0, sl: Int = 0, rr: Int = 8, dt: Int = 0, ks: Int = 0,
        am: Boolean = false, ssg: Int = 0,
    ) = YmOperator(dt, mul, tl, ks, ar, am, d1r, d2r, sl, rr, ssg)

    private fun fm(name: String, alg: Int, fb: Int, vararg ops: YmOperator, ams: Int = 0, fms: Int = 0, sweep: YmSweep = YmSweep.NONE) =
        YmFmInstrument(name, alg, fb, ams, fms, ops.toList(), sweep)

    private fun env(vararg values: Int, loop: Int = -1, release: Int = -1) = NesEnvelope(values.toList(), loop, release)

    private fun psg(name: String, volume: NesEnvelope, arpeggio: NesEnvelope = NesEnvelope(), pitch: NesEnvelope = NesEnvelope(),
                    noise: Int = YmPsgInstrument.WHITE or 1, sweep: YmSweep = YmSweep.NONE) =
        YmPsgInstrument(name, volume, arpeggio, pitch, false, noise, sweep)

    /** A muted operator (algorithm slots a drum does not use). */
    private val MUTE = op(1, 127, rr = 15)

    val ALL: List<Preset> = listOf(
        // Chain 1→2→3→4: a bright click from operator 2's fast decay over a round body; the Mega Drive's signature bass.
        Preset(fm("Slap bass", 0, 5,
            op(1, 34, d1r = 12, d2r = 5, sl = 4, rr = 9),
            op(3, 44, d1r = 19, d2r = 8, sl = 9, rr = 9, dt = 3),
            op(1, 24, d1r = 10, d2r = 4, sl = 3, rr = 9, dt = 7),
            op(1, 2, d1r = 8, d2r = 3, sl = 2, rr = 10)), Category.FM),
        Preset(fm("Finger bass", 0, 3,
            op(0, 40, d1r = 8, d2r = 3, sl = 3, rr = 8),
            op(1, 48, d1r = 10, d2r = 4, sl = 5, rr = 8),
            op(1, 30, d1r = 7, d2r = 3, sl = 4, rr = 8),
            op(1, 4, d1r = 5, d2r = 2, sl = 2, rr = 9)), Category.FM),
        // Two detuned pairs whose modulators swell in slower than the carriers: the brass rasp opens up.
        Preset(fm("Brass", 4, 5,
            op(1, 26, ar = 21, d1r = 4, d2r = 1, sl = 2, rr = 7, dt = 3),
            op(1, 4, ar = 27, d1r = 3, d2r = 1, sl = 1, rr = 7, dt = 3),
            op(1, 28, ar = 20, d1r = 4, d2r = 1, sl = 2, rr = 7, dt = 7),
            op(1, 6, ar = 27, d1r = 3, d2r = 1, sl = 1, rr = 7, dt = 7)), Category.FM),
        // Two pairs: a 14× tine that fades fast over a mellow body.
        Preset(fm("E-piano", 4, 3,
            op(14, 46, d1r = 14, d2r = 6, sl = 6, rr = 7, dt = 3),
            op(1, 6, d1r = 6, d2r = 3, sl = 4, rr = 7),
            op(1, 30, d1r = 8, d2r = 3, sl = 5, rr = 7, dt = 7),
            op(1, 8, d1r = 5, d2r = 2, sl = 3, rr = 7, dt = 3)), Category.FM),
        // Inharmonic pairs, long percussive decay.
        Preset(fm("Bell", 4, 2,
            op(7, 32, d1r = 7, d2r = 3, sl = 5, rr = 5),
            op(2, 8, d1r = 5, d2r = 3, sl = 6, rr = 5),
            op(3, 36, d1r = 6, d2r = 3, sl = 5, rr = 5, dt = 2),
            op(1, 10, d1r = 4, d2r = 2, sl = 5, rr = 5)), Category.FM),
        // Slow attack, chorused pairs, held; vibrato with the song's LFO on.
        Preset(fm("Strings", 4, 4,
            op(1, 38, ar = 13, d1r = 2, sl = 1, rr = 5, dt = 3),
            op(1, 6, ar = 14, d1r = 1, sl = 1, rr = 5, dt = 3),
            op(1, 40, ar = 13, d1r = 2, sl = 1, rr = 5, dt = 7),
            op(1, 8, ar = 14, d1r = 1, sl = 1, rr = 5, dt = 7), fms = 3), Category.FM),
        // Modulators at twice the carriers' pitch: odd harmonics, a hollow square lead.
        Preset(fm("Lead", 4, 2,
            op(2, 30, d1r = 3, d2r = 1, sl = 2, rr = 7, dt = 3),
            op(1, 4, d1r = 2, sl = 1, rr = 7, dt = 3),
            op(2, 32, d1r = 3, d2r = 1, sl = 2, rr = 7, dt = 7),
            op(1, 6, d1r = 2, sl = 1, rr = 7, dt = 7), fms = 2), Category.FM),
        // Additive drawbars 1, 2, 4 and 8.
        Preset(fm("Organ", 7, 0,
            op(1, 14, rr = 9), op(2, 20, rr = 9), op(4, 28, rr = 9), op(8, 36, rr = 9), ams = 1), Category.FM),
        Preset(fm("Pluck", 4, 4,
            op(3, 30, d1r = 14, d2r = 8, sl = 8, rr = 8),
            op(1, 4, d1r = 10, d2r = 6, sl = 6, rr = 8),
            op(1, 34, d1r = 12, d2r = 7, sl = 7, rr = 8, dt = 2),
            op(1, 8, d1r = 9, d2r = 6, sl = 6, rr = 8, dt = 6)), Category.FM),
        Preset(fm("Pad", 4, 3,
            op(0, 40, ar = 9, d1r = 2, sl = 2, rr = 4, dt = 3),
            op(1, 8, ar = 10, d1r = 1, sl = 1, rr = 4, dt = 3),
            op(0, 42, ar = 9, d1r = 2, sl = 2, rr = 4, dt = 7),
            op(1, 10, ar = 10, d1r = 1, sl = 1, rr = 4, dt = 7), fms = 2), Category.FM),
        // Drums: the note sets the pitch, the sweep the drop.
        Preset(fm("Kick", 0, 0,
            op(1, 48, d1r = 20, d2r = 10, sl = 15, rr = 15), MUTE, MUTE,
            op(1, 0, d1r = 12, d2r = 8, sl = 6, rr = 12), sweep = YmSweep(30, 0, 56)), Category.DRUM, 24),
        // Feedback noise from operator 1 over a swept body on operator 4.
        Preset(fm("Snare", 4, 7,
            op(15, 8, d1r = 16, d2r = 10, sl = 15, rr = 12),
            op(1, 6, d1r = 13, d2r = 10, sl = 8, rr = 12),
            MUTE,
            op(1, 8, d1r = 16, d2r = 12, sl = 15, rr = 12), sweep = YmSweep(12, 0, 48)), Category.DRUM, 50),
        Preset(fm("Tom", 0, 2,
            op(1, 44, d1r = 16, d2r = 8, sl = 15, rr = 12), MUTE, MUTE,
            op(1, 2, d1r = 9, d2r = 6, sl = 7, rr = 11), sweep = YmSweep(12, -2, 20)), Category.DRUM, 43),
        Preset(fm("Hi-hat", 4, 7,
            op(15, 0, d1r = 24, d2r = 16, sl = 15, rr = 15),
            op(11, 10, d1r = 24, d2r = 18, sl = 15, rr = 15),
            op(13, 20, d1r = 22, d2r = 16, sl = 15, rr = 15),
            op(9, 14, d1r = 24, d2r = 18, sl = 15, rr = 15)), Category.DRUM, 72),
        Preset(fm("Crash", 4, 7,
            op(15, 0, d1r = 12, d2r = 6, sl = 15, rr = 8),
            op(11, 12, d1r = 10, d2r = 6, sl = 10, rr = 7),
            op(13, 20, d1r = 12, d2r = 6, sl = 15, rr = 8),
            op(9, 16, d1r = 10, d2r = 6, sl = 10, rr = 7)), Category.DRUM, 72),
        // PSG tones: volume envelopes with a release tail after OFF.
        Preset(psg("PSG lead", env(15, 14, 13, 12, 12, 9, 6, 3, 0, loop = 4, release = 5),
            pitch = env(0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 2, 1, 0, -1, -2, -2, -1, loop = 8)), Category.PSG),
        Preset(psg("PSG pluck", env(15, 13, 11, 9, 8, 7, 6, 5, 4, 3, 2, 2, 1, 1, 0)), Category.PSG),
        Preset(psg("PSG arp major", env(13, 13, 12, 12, 11, 11, 10, 7, 4, 0, loop = 6, release = 7),
            arpeggio = env(0, 0, 4, 4, 7, 7, loop = 0)), Category.PSG),
        Preset(psg("PSG arp minor", env(13, 13, 12, 12, 11, 11, 10, 7, 4, 0, loop = 6, release = 7),
            arpeggio = env(0, 0, 3, 3, 7, 7, loop = 0)), Category.PSG),
        // Noise: fixed-rate white noise drums and pitched periodic noise (PSG 3 goes silent while it sounds).
        Preset(psg("Noise snare", env(15, 13, 11, 9, 7, 6, 5, 4, 3, 2, 1, 0), noise = YmPsgInstrument.WHITE or 1), Category.NOISE, 48),
        Preset(psg("Noise hat", env(14, 10, 6, 3, 1, 0), noise = YmPsgInstrument.WHITE or 0), Category.NOISE, 48),
        Preset(psg("Noise open hat", env(14, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0), noise = YmPsgInstrument.WHITE or 0), Category.NOISE, 48),
        Preset(psg("Noise tom", env(15, 14, 12, 10, 8, 6, 4, 2, 1, 0), noise = YmPsgInstrument.WHITE or YmPsgInstrument.PITCHED,
            sweep = YmSweep(12, -12, 24)), Category.NOISE, 36),
        Preset(psg("Buzz bass", env(15, 15, 14, 14, 13, 13, 9, 5, 0, loop = 5, release = 6), noise = YmPsgInstrument.PITCHED), Category.NOISE, 36),
    )

    /** The preset named [name], or null. */
    fun named(name: String): Preset? = ALL.firstOrNull { it.name == name }

    /** [name]'s instrument; fails loudly for an unknown name (demo and test songs use it). */
    fun instrument(name: String): YmInstrument = named(name)?.instrument ?: throw IllegalArgumentException("No Mega Drive preset \"$name\"")

    /** Presets for [channel]: FM voices and drums on FM 1-6, PSG tones on PSG 1-3, every PSG sound on Noise; none on PCM 1-3 (kits start empty). */
    fun forChannel(channel: Int): List<Preset> = ALL.filter {
        when {
            YmRules.isFm(channel) -> it.category == Category.FM || it.category == Category.DRUM
            channel == YmRules.NOISE -> it.category == Category.NOISE || it.category == Category.PSG
            YmRules.isPcm(channel) -> false
            else -> it.category == Category.PSG
        }
    }
}
