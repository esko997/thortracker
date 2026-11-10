package org.thortracker.system.vsu

import org.thortracker.backend.VsuPlayerImage
import org.thortracker.backend.VsuPlayerImage.NOISE_LANE
import org.thortracker.backend.VsuPlayerImage.PITCH_MAX
import org.thortracker.backend.VsuPlayerImage.WAVE_PITCH_MIN
import org.thortracker.model.NumberText
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round

/**
 * What a pitch really sounds on the VSU, as the player computes it (docs/AUDIO_ENGINE.md, "Virtual Boy VSU: Pitch"):
 * pitch in 1/64 semitone (0 = C-0, note n = 64 n), clamped per lane, turned into the 11-bit frequency register F through
 * the period table `VSU_PERIOD_Q`. Wave channels then play 156250 / (2048 − F) Hz; the noise channel clocks its shift
 * register 500000 / (2048 − F) times per second. Used for honest readouts (actual Hz, cents from 12-TET) and the
 * Wave 5 sweep-overflow checks; nothing here is lowered.
 */
object VsuPitch {
    /**
     * `VSU_PERIOD_Q[f]` = round(65536 · 156250 / 440 · 2^((3648 − f) / 768)), computed in doubles. Every value's
     * fraction lies at least 9.5 × 10⁻⁴ from .5 (checked against the 60-digit table the native player embeds), so double
     * rounding reproduces the native literals exactly: Q[0] = 626238494, Q[767] = 313401976.
     */
    private val Q = IntArray(768) { f -> round(65536.0 * 156250.0 / 440.0 * 2.0.pow((3648 - f) / 768.0)).toInt() }

    /** `VSU_PERIOD_Q[f]` (0..767). */
    fun q(f: Int): Int = Q[f]

    /** [pitch] clamped to what [lane] plays: wave lanes 1728..6143 (D#2 up), Noise 0..6143. */
    fun clamp(lane: Int, pitch: Int): Int = if (lane == NOISE_LANE) pitch.coerceIn(0, PITCH_MAX) else pitch.coerceIn(WAVE_PITCH_MIN, PITCH_MAX)

    /** Wave period W = (Q[f] + 2^(15+o)) >> (16+o) for pitch 768 o + f (clamped to 0..6143). */
    fun wavePeriod(pitch: Int): Int {
        val p = pitch.coerceIn(0, PITCH_MAX)
        val o = p / 768
        return ((Q[p % 768].toLong() + (1L shl (15 + o))) shr (16 + o)).toInt()
    }

    /** Noise period N = (Q[f] + 5 · 2^(16+o)) / (10 · 2^(16+o)). */
    fun noisePeriod(pitch: Int): Int {
        val p = pitch.coerceIn(0, PITCH_MAX)
        val o = p / 768
        return ((Q[p % 768].toLong() + 5L * (1L shl (16 + o))) / (10L shl (16 + o))).toInt()
    }

    /** The frequency register F the player writes on [lane] for [pitch] (clamped to the lane). */
    fun register(lane: Int, pitch: Int): Int {
        val p = clamp(lane, pitch)
        return 2048 - if (lane == NOISE_LANE) noisePeriod(p) else wavePeriod(p)
    }

    /** Wave lanes: the tone's frequency; Noise: how often the shift register steps, per second. */
    fun hz(lane: Int, pitch: Int): Double {
        val f = register(lane, pitch)
        return (if (lane == NOISE_LANE) VsuPlayerImage.NOISE_BASE_HZ else VsuPlayerImage.WAVE_BASE_HZ) / (2048 - f)
    }

    /** 12-TET frequency of [pitch] (1/64 semitones, A-4 = 3648 = 440 Hz). */
    fun twelveTet(pitch: Int): Double = 440.0 * 2.0.pow((pitch - VsuPlayerImage.PITCH_A4) / 768.0)

    /**
     * How far a wave lane's sound of [pitch] is from 12-TET, in cents (the period resolution: up to about −20 cents at
     * B-7). On Noise: how far the shift register's rate / 32 is from it (rates quantise strongly above A-5).
     */
    fun cents(lane: Int, pitch: Int): Double {
        val p = clamp(lane, pitch)
        val heard = if (lane == NOISE_LANE) hz(lane, p) / 32.0 else hz(lane, p)
        return 1200.0 * log2(heard / twelveTet(p))
    }

    /**
     * "A-4 F 1693: 440.1 Hz (+0.6¢)" on a wave lane; "C-5 F 1840: shift register at 2.4 kHz" on Noise (the rate it
     * steps at), for [note] raised by [fine] sixty-fourths.
     */
    fun readout(lane: Int, note: Int, fine: Int = 0): String {
        val pitch = 64 * note + fine
        val name = VsuSystem.noteName(note)
        if (lane != NOISE_LANE && pitch < WAVE_PITCH_MIN) return "$name is below D#2: wave lanes cannot play it"
        val f = register(lane, pitch)
        return if (lane == NOISE_LANE) "$name F $f: shift register at ${hzText(hz(lane, pitch))}"
            else "$name F $f: ${hzText(hz(lane, pitch))} (${centsText(cents(lane, pitch))})"
    }

    fun hzText(hz: Double): String = when {
        hz >= 1000.0 -> NumberText.fixed(hz / 1000.0, 2) + " kHz"
        hz >= 100.0 -> NumberText.fixed(hz, 1) + " Hz"
        else -> NumberText.fixed(hz, 2) + " Hz"
    }

    fun centsText(cents: Double): String = NumberText.fixed(cents, 1, plus = true) + "¢"

    /**
     * The hardware sweep overflow (Sacred Tech Scroll, "Sweep"): with the sweep function selected, every effects tick
     * computes F ± (F >> shift); a value above 2047 stops channel 5 at once, even when the sweep is switched off or its
     * interval is 0. Only an upward sweep can exceed it. True when key-on value [f] stops at once with [shift].
     */
    fun sweepStops(f: Int, shift: Int): Boolean = f + (f shr shift) > VsuPlayerImage.FREQ_MAX

    /** The lowest wave note whose key-on F [sweepStops] with [shift], or null when none of D#2..B-7 does. */
    fun lowestStoppingNote(shift: Int): Int? =
        (VsuPlayerImage.WAVE_NOTE_LOW until VsuPlayerImage.NOTES).firstOrNull { sweepStops(register(VsuPlayerImage.SWEEP_LANE, 64 * it), shift) }

    /** The shift register's sequence length for noise tap [tap] (0..7). */
    fun tapLength(tap: Int): Int = VsuPlayerImage.TAP_LENGTHS.getOrElse(tap) { 0 }
}
