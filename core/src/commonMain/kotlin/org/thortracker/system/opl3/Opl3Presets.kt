package org.thortracker.system.opl3

/**
 * Starting sounds for OPL3 instruments: a new song has them in slots 01-10 and the instrument editor's Preset control
 * loads any of them into a slot. Drums are ordinary FM voices with the pitch sweep; [Opl3Preset.note] is the note
 * they were voiced at (the note picks the drum's pitch).
 */
object Opl3Presets {
    enum class Category { MELODIC, DRUM }

    data class Preset(val instrument: Opl3Instrument, val category: Category, val note: Int? = null) {
        val name: String get() = instrument.name
    }

    private fun op(
        mult: Int, tl: Int, ar: Int = 15, dr: Int = 0, sl: Int = 0, rr: Int = 6, wave: Int = 0, ksl: Int = 0,
        sustain: Boolean = true, vib: Boolean = false, am: Boolean = false, ksr: Boolean = false,
    ) = Opl3Operator(mult, tl, ar, dr, sl, rr, wave, ksl, am, vib, sustain, ksr)

    private fun melodic(ins: Opl3Instrument) = Preset(ins, Category.MELODIC)
    private fun drum(ins: Opl3Instrument, note: Int) = Preset(ins, Category.DRUM, note)

    val ALL: List<Preset> = listOf(
        // 4-op (1→2)+(3→4): a bright, fast-fading tine pair over a mellow body pair.
        melodic(Opl3Instrument.fourOp("E-piano", 1, 3, listOf(
            op(14, 38, dr = 7, sl = 15, rr = 6, ksl = 2, sustain = false), op(1, 4, dr = 3, sl = 6, rr = 6, sustain = false),
            op(1, 26, dr = 4, sl = 6, rr = 6, ksl = 2, sustain = false), op(1, 6, dr = 2, sl = 5, rr = 6, sustain = false)))),
        // 2-op FM: modulator swells in with the attack, feedback for the rasp.
        melodic(Opl3Instrument.twoOp("Brass", 0, 6,
            op(1, 22, ar = 7, dr = 2, sl = 2, rr = 6, ksl = 2), op(1, 2, ar = 8, dr = 1, sl = 1, rr = 6))),
        // 4-op 1+(2→3)+4: inharmonic ratios, long percussive decay.
        melodic(Opl3Instrument.fourOp("Bell", 3, 0, listOf(
            op(3, 20, dr = 5, sl = 9, rr = 5, sustain = false), op(7, 28, dr = 4, sl = 8, rr = 5, sustain = false),
            op(1, 8, dr = 3, sl = 10, rr = 5, sustain = false), op(2, 16, dr = 5, sl = 12, rr = 5, wave = 5, sustain = false)))),
        // 4-op 1+(2→3→4): a drawbar-ish sine plus a reedy chain, held.
        melodic(Opl3Instrument.fourOp("Organ", 2, 1, listOf(
            op(4, 16, ar = 14, rr = 8), op(3, 30, ar = 14, rr = 8), op(1, 28, ar = 14, rr = 8), op(1, 6, ar = 14, rr = 8)))),
        melodic(Opl3Instrument.twoOp("FM bass", 0, 5,
            op(1, 22, dr = 5, sl = 7, rr = 8, wave = 1, sustain = false), op(1, 0, dr = 3, sl = 3, rr = 8))),
        melodic(Opl3Instrument.twoOp("Pad", 0, 3,
            op(2, 32, ar = 5, dr = 1, sl = 2, rr = 4, vib = true, ksl = 2), op(1, 4, ar = 5, dr = 1, sl = 1, rr = 4, vib = true))),
        melodic(Opl3Instrument.twoOp("Lead", 0, 5,
            op(2, 28, dr = 2, sl = 3, rr = 6, vib = true, ksl = 2), op(1, 2, dr = 1, sl = 2, rr = 6, vib = true))),
        melodic(Opl3Instrument.twoOp("Pluck", 0, 4,
            op(3, 26, dr = 6, sl = 15, rr = 7, sustain = false), op(1, 0, dr = 5, sl = 15, rr = 7, sustain = false))),
        melodic(Opl3Instrument.twoOp("Marimba", 0, 0,
            op(4, 30, dr = 8, sl = 15, rr = 8, sustain = false), op(1, 0, dr = 5, sl = 15, rr = 8, sustain = false))),
        melodic(Opl3Instrument.twoOp("Clav", 0, 6,
            op(3, 18, dr = 6, sl = 8, rr = 9, wave = 1, sustain = false), op(1, 2, dr = 4, sl = 6, rr = 9, sustain = false))),
        melodic(Opl3Instrument.twoOp("Flute", 0, 7,
            op(1, 42, ar = 7, dr = 1, sl = 2, rr = 6), op(1, 2, ar = 7, sl = 1, rr = 6, vib = true))),
        melodic(Opl3Instrument.twoOp("Organ 2-op", 1, 3,
            op(2, 10, rr = 8), op(1, 4, rr = 8))),
        // Drums: the note sets the pitch, the sweep the drop.
        drum(Opl3Instrument.twoOp("Kick", 0, 0,
            op(1, 40, dr = 8, sl = 15, rr = 8), op(1, 0, dr = 4, sl = 6, rr = 7, sustain = false), Opl3Sweep(24, 0, 40)), 36),
        drum(Opl3Instrument.twoOp("Snare", 0, 7,
            op(15, 8, dr = 7, sl = 7, rr = 8, wave = 6), op(1, 4, dr = 6, sl = 7, rr = 8, sustain = false), Opl3Sweep(7, -5, 12)), 50),
        drum(Opl3Instrument.twoOp("Tom", 0, 2,
            op(1, 30, dr = 6, sl = 15, rr = 7), op(1, 0, dr = 4, sl = 10, rr = 6, sustain = false), Opl3Sweep(7, -2, 10)), 43),
        drum(Opl3Instrument.twoOp("Hi-hat", 0, 6,
            op(12, 10, dr = 10, sl = 12, rr = 12, wave = 7), op(9, 6, dr = 10, sl = 12, rr = 12, sustain = false)), 84),
        drum(Opl3Instrument.twoOp("Open hat", 0, 6,
            op(12, 10, dr = 6, sl = 12, rr = 6, wave = 7), op(9, 6, dr = 6, sl = 12, rr = 6, sustain = false)), 84),
    )

    /** The preset named [name], or null. */
    fun named(name: String): Preset? = ALL.firstOrNull { it.name == name }

    /** [name]'s instrument; fails loudly for an unknown name (demo and test songs use it). */
    fun instrument(name: String): Opl3Instrument = named(name)?.instrument ?: throw IllegalArgumentException("No OPL3 preset \"$name\"")
}
