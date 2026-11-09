package org.thortracker.system.opm

/**
 * Starting sounds for OPM instruments: a new song has them in slots 01.. (FM voices first, then FM drums, then the
 * noise sounds) and the instrument editor's Preset controls load any of them into a slot. Drums are ordinary voices
 * with the pitch sweep; [Preset.note] is the note a preset was voiced at (the note picks a drum's pitch).
 * [Category.FM] and [Category.DRUM] play on every channel; [Category.NOISE] sounds have [OpmInstrument.noise] (operator
 * 4 plays the chip's one noise generator) and play on FM 8 only.
 */
object OpmPresets {
    enum class Category { FM, DRUM, NOISE }

    /** A starting sound: [name] is its [instrument]'s name. */
    data class Preset(val name: String, val category: Category, val instrument: OpmInstrument, val note: Int? = null)

    private fun op(
        mul: Int, tl: Int, ar: Int = 31, d1r: Int = 0, d2r: Int = 0, d1l: Int = 0, rr: Int = 8, dt1: Int = 0, dt2: Int = 0,
        ks: Int = 0, am: Boolean = false,
    ) = OpmOperator(dt1 = dt1, mul = mul, tl = tl, ks = ks, ar = ar, am = am, d1r = d1r, dt2 = dt2, d2r = d2r, d1l = d1l, rr = rr)

    private fun preset(
        name: String, category: Category, alg: Int, fb: Int, vararg ops: OpmOperator, pms: Int = 0, ams: Int = 0,
        nfrq: Int = -1, sweep: OpmSweep = OpmSweep.NONE, note: Int? = null,
    ) = Preset(name, category, OpmInstrument(name, alg, fb, pms, ams, ops.toList(), nfrq >= 0, maxOf(nfrq, 0), sweep), note)

    /** A muted operator (algorithm slots a sound does not use). */
    private val MUTE = op(1, 127, rr = 15)

    val ALL: List<Preset> = listOf(
        // Modulators at twice the carriers' pitch: odd harmonics, a hollow square lead; chorused pairs.
        preset("Lead", Category.FM, 4, 2,
            op(2, 30, d1r = 3, d2r = 1, d1l = 2, rr = 7, dt1 = 3),
            op(1, 4, d1r = 2, d1l = 1, rr = 7, dt1 = 3),
            op(2, 32, d1r = 3, d2r = 1, d1l = 2, rr = 7, dt1 = 7),
            op(1, 6, d1r = 2, d1l = 1, rr = 7, dt1 = 7), pms = 2),
        // Two detuned pairs whose modulators swell in slower than the carriers: the brass rasp opens up.
        preset("Brass", Category.FM, 4, 5,
            op(1, 26, ar = 20, d1r = 4, d2r = 1, d1l = 2, rr = 7, dt1 = 3),
            op(1, 4, ar = 26, d1r = 3, d2r = 1, d1l = 1, rr = 7, dt1 = 3),
            op(1, 28, ar = 19, d1r = 4, d2r = 1, d1l = 2, rr = 7, dt1 = 7),
            op(1, 6, ar = 26, d1r = 3, d2r = 1, d1l = 1, rr = 7, dt1 = 7), pms = 1),
        // Two pairs: a 14× tine that fades fast over a mellow body; key scaling shortens the high notes.
        preset("E-piano", Category.FM, 4, 3,
            op(14, 46, d1r = 15, d2r = 6, d1l = 6, rr = 7, dt1 = 3, ks = 1),
            op(1, 6, d1r = 6, d2r = 3, d1l = 4, rr = 7, ks = 1),
            op(1, 30, d1r = 8, d2r = 3, d1l = 5, rr = 7, dt1 = 7, ks = 1),
            op(1, 8, d1r = 5, d2r = 2, d1l = 3, rr = 7, dt1 = 3, ks = 1)),
        // Chain 1→2→3→4: a bright click from operator 2's fast decay over a round body.
        preset("Slap bass", Category.FM, 0, 5,
            op(1, 34, d1r = 12, d2r = 5, d1l = 4, rr = 9),
            op(3, 44, d1r = 19, d2r = 8, d1l = 9, rr = 9, dt1 = 3),
            op(1, 24, d1r = 10, d2r = 4, d1l = 3, rr = 9, dt1 = 7),
            op(1, 2, d1r = 8, d2r = 3, d1l = 2, rr = 10)),
        preset("Finger bass", Category.FM, 0, 3,
            op(0, 40, d1r = 8, d2r = 3, d1l = 3, rr = 8),
            op(1, 48, d1r = 10, d2r = 4, d1l = 5, rr = 8),
            op(1, 30, d1r = 7, d2r = 3, d1l = 4, rr = 8),
            op(1, 4, d1r = 5, d2r = 2, d1l = 2, rr = 9)),
        // Slow attack, chorused pairs, held; vibrato once the song's LFO pitch depth is up.
        preset("Strings", Category.FM, 4, 4,
            op(1, 38, ar = 13, d1r = 2, d1l = 1, rr = 5, dt1 = 3),
            op(1, 6, ar = 14, d1r = 1, d1l = 1, rr = 5, dt1 = 3),
            op(1, 40, ar = 13, d1r = 2, d1l = 1, rr = 5, dt1 = 7),
            op(1, 8, ar = 14, d1r = 1, d1l = 1, rr = 5, dt1 = 7), pms = 3),
        // The OPM's own: DT2 puts both modulators at ×1.57, an inharmonic partial no multiplier reaches; long ring.
        preset("Bell", Category.FM, 4, 1,
            op(1, 30, d1r = 7, d2r = 3, d1l = 5, rr = 5, dt2 = 2),
            op(2, 6, d1r = 5, d2r = 3, d1l = 6, rr = 5),
            op(3, 36, d1r = 6, d2r = 3, d1l = 5, rr = 5, dt1 = 2, dt2 = 2),
            op(1, 10, d1r = 4, d2r = 2, d1l = 5, rr = 5)),
        // Additive drawbars 1, 2, 4 and 8; a touch of tremolo once the song's LFO amplitude depth is up.
        preset("Organ", Category.FM, 7, 0,
            op(1, 14, rr = 9, am = true), op(2, 20, rr = 9, am = true), op(4, 28, rr = 9, am = true), op(8, 36, rr = 9, am = true),
            ams = 1),
        preset("Pluck", Category.FM, 4, 4,
            op(3, 30, d1r = 14, d2r = 8, d1l = 8, rr = 8),
            op(1, 4, d1r = 10, d2r = 6, d1l = 6, rr = 8),
            op(1, 34, d1r = 12, d2r = 7, d1l = 7, rr = 8, dt1 = 2),
            op(1, 8, d1r = 9, d2r = 6, d1l = 6, rr = 8, dt1 = 6)),
        // Slow, held, chorused; both LFO sensitivities so the song's LFO (PMD and AMD) makes it move.
        preset("Pad", Category.FM, 4, 3,
            op(0, 40, ar = 9, d1r = 2, d1l = 2, rr = 4, dt1 = 3),
            op(1, 8, ar = 10, d1r = 1, d1l = 1, rr = 4, dt1 = 3, am = true),
            op(0, 42, ar = 9, d1r = 2, d1l = 2, rr = 4, dt1 = 7),
            op(1, 10, ar = 10, d1r = 1, d1l = 1, rr = 4, dt1 = 7, am = true), pms = 2, ams = 1),
        // Modulators attack slowly and fade: the tone opens, peaks and closes on a held note (a filter-like sweep).
        preset("Synth sweep", Category.FM, 4, 6,
            op(1, 22, ar = 11, d1r = 4, d2r = 2, d1l = 9, rr = 6, dt1 = 3),
            op(1, 4, d1r = 1, d1l = 1, rr = 6, dt1 = 3),
            op(2, 26, ar = 10, d1r = 4, d2r = 2, d1l = 9, rr = 6, dt1 = 7),
            op(1, 6, d1r = 1, d1l = 1, rr = 6, dt1 = 7)),
        // Drums: the note sets the pitch, the sweep the drop.
        preset("Kick", Category.DRUM, 0, 0,
            op(1, 48, d1r = 20, d2r = 10, d1l = 15, rr = 15), MUTE, MUTE,
            op(1, 0, d1r = 12, d2r = 8, d1l = 6, rr = 12), sweep = OpmSweep(30, 0, 56), note = 24),
        preset("Tom", Category.DRUM, 0, 2,
            op(1, 44, d1r = 16, d2r = 8, d1l = 15, rr = 12), MUTE, MUTE,
            op(1, 2, d1r = 9, d2r = 6, d1l = 7, rr = 11), sweep = OpmSweep(12, -2, 20), note = 43),
        // Feedback grit from operator 1 over a swept body on operator 4: the snare's tone (FM 1-8; add Snare noise on FM 8).
        preset("Snare body", Category.DRUM, 4, 7,
            op(15, 8, d1r = 16, d2r = 10, d1l = 15, rr = 12),
            op(1, 6, d1r = 13, d2r = 10, d1l = 8, rr = 12),
            MUTE,
            op(1, 8, d1r = 16, d2r = 12, d1l = 15, rr = 12), sweep = OpmSweep(12, 0, 48), note = 50),
        // Noise (FM 8 only): operators 1-3 muted, operator 4 is the noise generator shaped by its envelope.
        preset("Hi-hat", Category.NOISE, 7, 0,
            MUTE, MUTE, MUTE, op(1, 4, d1r = 18, d1l = 15, rr = 15), nfrq = 28),
        preset("Open hat", Category.NOISE, 7, 0,
            MUTE, MUTE, MUTE, op(1, 6, d1r = 11, d1l = 15, rr = 9), nfrq = 28),
        // A swept sine body on operator 2 under the noise: a whole snare on FM 8.
        preset("Snare noise", Category.NOISE, 7, 0,
            MUTE,
            op(1, 10, d1r = 16, d2r = 8, d1l = 15, rr = 12),
            MUTE,
            op(1, 6, d1r = 13, d2r = 6, d1l = 15, rr = 11), nfrq = 22, sweep = OpmSweep(12, 0, 48), note = 50),
        preset("Crash", Category.NOISE, 7, 0,
            MUTE, MUTE, MUTE, op(1, 2, d1r = 7, d2r = 4, d1l = 15, rr = 6), nfrq = 26),
    )

    /** The preset named [name], or null. */
    fun named(name: String): Preset? = ALL.firstOrNull { it.name == name }

    /** [name]'s instrument; fails loudly for an unknown name. */
    fun instrument(name: String): OpmInstrument = named(name)?.instrument ?: throw IllegalArgumentException("No OPM preset \"$name\"")
}
