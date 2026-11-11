package org.thortracker.system.gb

/** Musical choices, in the order presented by the pulse Sound view. No macro state is stored in the song. */
enum class GbPulseControl { CHARACTER, SHAPE, LENGTH, MOTION }

/**
 * A deliberately small vocabulary over hUGE's real pulse registers, not another instrument format.
 *
 * Character owns duty only. Motion owns the three sweep fields, on Pulse 1 only. Shape and Length cooperate:
 * they own volume, envelope direction/pace and the length gate. Shape retains a recognized Length rank (otherwise
 * Medium); Length retains a recognized Shape (otherwise explicitly establishes Pluck). Changing Length on a known
 * shape only changes its pace or gate. Disabled raw length and inactive envelope/sweep direction are preserved.
 *
 * Reads never normalize. A matching selection returns the original object, including equivalent 25/75% timbres and
 * inactive register bits. Unrepresented settings read Custom; in particular a sweep which can cut an otherwise
 * stationary note is not called Still. All times are nominal: hardware frame-sequencer phase shifts the first clock.
 */
internal object GbPulseMacros {
    private val CHARACTERS = arrayOf("Thin", "Reedy", "Square")
    private val SHAPES = arrayOf("Pluck", "Held", "Swell")
    private val LENGTHS = arrayOf("Short", "Medium", "Long", "Longest")
    private val MOTIONS = arrayOf("Still", "Falling", "Rising")
    private val PACES = intArrayOf(1, 2, 4, 7)
    private val GATES = intArrayOf(48, 32, 0)
    private val CHARACTER_TEXT = arrayOf(
        "Thin pulse: 12.5% duty",
        "Reedy pulse: 25% or 75%, the same harmonic balance",
        "Classic square: 50% duty",
    )
    private val PLUCK_TEXT = arrayOf(
        "Fades to silence in about 188 ms",
        "Fades to silence in about 375 ms",
        "Fades to silence in about 750 ms",
        "Fades to silence in about 1.31 s",
    )
    private val HELD_TEXT = arrayOf(
        "Steady tone, cut after about 63 ms",
        "Steady tone, cut after about 125 ms",
        "Steady tone, cut after about 250 ms",
        "Steady tone until the next note or OFF",
    )
    private val SWELL_TEXT = arrayOf(
        "Rises for about 188 ms, then holds until note/OFF",
        "Rises for about 375 ms, then holds until note/OFF",
        "Rises for about 750 ms, then holds until note/OFF",
        "Rises for about 1.31 s, then holds until note/OFF",
    )
    private val MOTION_TEXT = arrayOf(
        "No pitch sweep; Motion is Pulse 1 only",
        "Falling pitch on Pulse 1 only; note-dependent steps",
        "Rising pitch on Pulse 1 only; high notes can cut off",
    )

    fun steps(control: GbPulseControl): Int = labels(control).size

    fun valueLabel(control: GbPulseControl, index: Int): String = labels(control)[index]

    private fun labels(control: GbPulseControl): Array<String> = when (control) {
        GbPulseControl.CHARACTER -> CHARACTERS
        GbPulseControl.SHAPE -> SHAPES
        GbPulseControl.LENGTH -> LENGTHS
        GbPulseControl.MOTION -> MOTIONS
    }

    fun value(control: GbPulseControl, instrument: GbInstrument): Int? {
        if (instrument.kind != GbBank.PULSE) return null
        return when (control) {
            GbPulseControl.CHARACTER -> when (instrument.duty) {
                0 -> 0
                1, 3 -> 1
                2 -> 2
                else -> null
            }
            GbPulseControl.SHAPE -> shape(instrument)
            GbPulseControl.LENGTH -> length(instrument)
            GbPulseControl.MOTION -> motion(instrument)
        }
    }

    private fun shape(i: GbInstrument): Int? {
        if (i.length !in 0..63 || i.envelopeDirection !in 0..1) return null
        return when {
            i.volume == 12 && i.envelopePace == 0 -> 1
            i.lengthEnabled || i.envelopePace !in 1..7 -> null
            i.volume == 12 && i.envelopeDirection == 0 -> 0
            i.volume == 3 && i.envelopeDirection == 1 -> 2
            else -> null
        }
    }

    private fun length(i: GbInstrument): Int? = when (shape(i)) {
        0, 2 -> PACES.indexOf(i.envelopePace).takeIf { it >= 0 }
        1 -> if (!i.lengthEnabled) 3 else GATES.indexOf(i.length).takeIf { it >= 0 }
        else -> null
    }

    private fun motion(i: GbInstrument): Int? {
        if (i.sweepTime !in 0..7 || i.sweepShift !in 0..7 || i.sweepDirection !in 0..1) return null
        // Time 0 with upward shift can overflow on trigger. Upward shift 0 with time can overflow when clocked.
        if ((i.sweepTime == 0 && (i.sweepDirection == 0 || i.sweepShift == 0)) ||
            (i.sweepShift == 0 && i.sweepDirection == 0)) return 0
        if (i.sweepTime == 4 && i.sweepShift == 7) return if (i.sweepDirection == 0) 1 else 2
        return null
    }

    fun describe(control: GbPulseControl, instrument: GbInstrument): String {
        if (instrument.kind != GbBank.PULSE) return "Sound controls are for pulse instruments only"
        return when (control) {
            GbPulseControl.CHARACTER -> value(control, instrument)?.let { CHARACTER_TEXT[it] }
                ?: "Custom duty; use Advanced for exact settings"
            GbPulseControl.SHAPE -> when (shape(instrument)) {
                0 -> "Pluck: starts at 12, fades to silence"
                1 -> "Held: steady level 12; Length sets the gate"
                2 -> "Swell: rises from 3 to 15, then holds"
                else -> "Custom envelope/gate; choosing Shape replaces these only"
            }
            GbPulseControl.LENGTH -> {
                val rank = length(instrument)
                if (rank == null) {
                    if (shape(instrument) == null) "Custom envelope/gate; choosing Length makes a Pluck"
                    else "Custom timing; choosing Length keeps this Shape"
                } else when (shape(instrument)) {
                    0 -> PLUCK_TEXT[rank]
                    1 -> HELD_TEXT[rank]
                    else -> SWELL_TEXT[rank]
                }
            }
            GbPulseControl.MOTION -> motion(instrument)?.let { MOTION_TEXT[it] }
                ?: "Custom sweep; Pulse 1 only, may cut off high notes"
        }
    }

    fun apply(control: GbPulseControl, instrument: GbInstrument, index: Int): GbInstrument {
        require(index in 0 until steps(control)) { "Invalid pulse control choice" }
        if (instrument.kind != GbBank.PULSE || value(control, instrument) == index) return instrument
        return when (control) {
            GbPulseControl.CHARACTER -> instrument.copy(duty = index)
            GbPulseControl.SHAPE -> envelope(instrument, index, length(instrument) ?: 1)
            GbPulseControl.LENGTH -> envelope(instrument, shape(instrument) ?: 0, index)
            GbPulseControl.MOTION -> when (index) {
                0 -> instrument.copy(sweepTime = 0, sweepShift = 0)
                else -> instrument.copy(sweepTime = 4, sweepDirection = index - 1, sweepShift = 7)
            }
        }
    }

    private fun envelope(i: GbInstrument, shape: Int, length: Int): GbInstrument = when (shape) {
        0 -> i.copy(volume = 12, envelopeDirection = 0, envelopePace = PACES[length], lengthEnabled = false)
        1 -> i.copy(volume = 12, envelopePace = 0, lengthEnabled = length < 3,
            length = if (length < 3) GATES[length] else i.length)
        else -> i.copy(volume = 3, envelopeDirection = 1, envelopePace = PACES[length], lengthEnabled = false)
    }

    /** Reset only this control: Square, Pluck, Medium or Still. Shape/Length keep their cooperative semantics. */
    fun reset(control: GbPulseControl, instrument: GbInstrument): GbInstrument =
        apply(control, instrument, when (control) {
            GbPulseControl.CHARACTER -> 2
            GbPulseControl.SHAPE -> 0
            GbPulseControl.LENGTH -> 1
            GbPulseControl.MOTION -> 0
        })

    fun help(control: GbPulseControl): String = when (control) {
        GbPulseControl.CHARACTER -> "Pulse timbre, not a filter. Reedy groups 25% and 75% duty: same harmonic balance, different phase. Changes duty only."
        GbPulseControl.SHAPE -> "Pluck fades; Held stays level; Swell rises then holds, not a full ADSR. Replaces envelope/gate only. Keeps Length rank, or Medium for Custom timing."
        GbPulseControl.LENGTH -> "Pluck fade / Swell rise: about 188, 375, 750, 1313 ms, in 12 quantized volume steps. Swell then holds. Held gate: 63, 125, 250 ms or until note/OFF. Custom Shape becomes Pluck; only envelope/gate changes. Motion or note/OFF can end a sound sooner."
        GbPulseControl.MOTION -> "Pulse 1 only; Pulse 2 ignores sweep. Falling/Rising step every 31.25 ms. Size depends on note, not fixed semitones; rising can overflow and silence high notes. Changes sweep only."
    }
}
