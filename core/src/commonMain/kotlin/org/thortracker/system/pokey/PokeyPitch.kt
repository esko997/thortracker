package org.thortracker.system.pokey

import org.thortracker.backend.PokeyPlayerImage
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * The player's pitch-to-counter search in Kotlin (docs/AUDIO_ENGINE.md, "POKEY: Player", "Pitch to counter"): the same
 * double operations in the same order as `pokey_player.c` and `tools/audio/pokey/pokey_ref.py`, so the counters it
 * picks are the ones the chip plays. The song check (warnings), instrument summaries and the editor's readouts use it.
 *
 * Pitch is in 1/64 semitone, 0 = C-0, A-4 = 3648 = 440 Hz. Rounding is half away from zero (C `llround`).
 */
object PokeyPitch {
    /** How a voice's counter counts: 8 or 16 bits, on the shared base clock or the 1.79 MHz machine clock. */
    enum class Counter(val bits16: Boolean, val fast: Boolean) {
        BASE_8(false, false), FAST_8(false, true), BASE_16(true, false), FAST_16(true, true);

        /** Largest counter value N. */
        val max: Int get() = if (bits16) 0xFFFF else 0xFF

        companion object {
            fun of(bits16: Boolean, fast: Boolean): Counter = when {
                bits16 && fast -> FAST_16
                bits16 -> BASE_16
                fast -> FAST_8
                else -> BASE_8
            }
        }
    }

    /** Frequency of [pitch64]: 440 · 2^((p − 3648) / 768) Hz. */
    fun target(pitch64: Int): Double = 440.0 * 2.0.pow((pitch64 - PokeyPlayerImage.PITCH_A4).toDouble() / 768.0)

    /** Fires per waveform period of [distortion] (`D`). */
    fun divisor(distortion: Int): Int = when (distortion) {
        0 -> 2
        1, 3 -> 62
        2 -> 465
        6 -> 15
        else -> 2
    }

    /** The pattern length a counter period must be coprime to (`M`) for [distortion] to play its full pattern. */
    fun modulus(distortion: Int): Int = when (distortion) {
        0, 1, 3 -> 31
        2 -> 465
        6 -> 15
        else -> 1
    }

    /** Base-clock multiplier: 114 on the 15 kHz clock, else 28. */
    private fun base(base15: Boolean): Int = if (base15) PokeyPlayerImage.BASE_15 else PokeyPlayerImage.BASE_64

    /** The counter's period in machine cycles for value [n]. */
    fun period(counter: Counter, base15: Boolean, n: Int): Int = when (counter) {
        Counter.FAST_8 -> n + PokeyPlayerImage.FAST_8
        Counter.FAST_16 -> n + PokeyPlayerImage.FAST_16
        else -> base(base15) * (n + 1)
    }

    /** The counter value whose period is nearest [cycles] (C `llround`), clamped to 0..[max]. */
    private fun nearest(counter: Counter, base15: Boolean, cycles: Double, max: Int): Int {
        val n = when (counter) {
            Counter.FAST_8 -> llround(cycles) - PokeyPlayerImage.FAST_8
            Counter.FAST_16 -> llround(cycles) - PokeyPlayerImage.FAST_16
            else -> llround(cycles / base(base15)) - 1
        }
        return n.coerceIn(0L, max.toLong()).toInt()
    }

    /** C's `llround` for the finite values the search sees: half away from zero. */
    private fun llround(x: Double): Long = if (x >= 0) (x + 0.5).let { kotlin.math.floor(it).toLong() } else -llround(-x)

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /**
     * The counter a tone of [pitch64] with [distortion] plays on: Pt = C / (f · D); every 8-bit N, or the 49 values
     * around the nearest 16-bit N (window clipped to the range); N minimises |P(N) − Pt| among periods coprime to M
     * (all if none is), ties the smaller N. [minPeriod]: candidates with shorter periods are left out (samples).
     */
    fun counter(machine: PokeyMachine, base15: Boolean, counter: Counter, pitch64: Int, distortion: Int): Int =
        search(machine.clockHz / (target(pitch64) * divisor(distortion)), counter, base15, modulus(distortion), 0)

    private fun search(pt: Double, counter: Counter, base15: Boolean, m: Int, minPeriod: Int): Int {
        val lo: Int
        val hi: Int
        if (counter.bits16) {
            val n0 = nearest(counter, base15, pt, counter.max)
            lo = maxOf(0, n0 - 24)
            hi = minOf(counter.max, n0 + 24)
        } else {
            lo = 0
            hi = counter.max
        }
        for (pass in 0..1) {
            var best = -1
            var bestError = 0.0
            for (n in lo..hi) {
                val p = period(counter, base15, n)
                if (p < minPeriod) continue
                if (pass == 0 && gcd(p, m) != 1) continue
                val error = abs(p.toDouble() - pt)
                if (best < 0 || error < bestError) {
                    best = n
                    bestError = error
                }
            }
            if (best >= 0) return best
        }
        return hi
    }

    /**
     * A difference sound's counter (the "POKEY saw"): the note is C/P(N) − C/P(N + d), d = [offset] 1..127; N
     * minimises |C/P(N) − C/P(N + d) − f| (8-bit: every N with N + d in range; 16-bit: the 49 around the estimate N ≈
     * (√(Δ² + 4 C Δ / f) − Δ) / 2 cycles, Δ = P(N + d) − P(N)), ties the smaller N.
     */
    fun differenceCounter(machine: PokeyMachine, base15: Boolean, counter: Counter, pitch64: Int, offset: Int): Int {
        val c = machine.clockHz
        val f = target(pitch64)
        val d = offset.coerceIn(PokeyInstrument.DIFFERENCE_OFFSET)
        val max = counter.max - d
        val lo: Int
        val hi: Int
        if (counter.bits16) {
            val delta = (period(counter, base15, d) - period(counter, base15, 0)).toDouble()
            val x = (sqrt(delta * delta + 4.0 * c * delta / f) - delta) / 2.0
            val n0 = nearest(counter, base15, x, max)
            lo = maxOf(0, n0 - 24)
            hi = minOf(max, n0 + 24)
        } else {
            lo = 0
            hi = max
        }
        var best = lo
        var bestError = Double.MAX_VALUE
        for (n in lo..hi) {
            val rate = c / period(counter, base15, n) - c / period(counter, base15, n + d)
            val error = abs(rate - f)
            if (error < bestError) {
                best = n
                bestError = error
            }
        }
        return best
    }

    /** A sample voice's counter: Pt = C / r, r = rate · 2^((p − 64 root) / 768), among periods ≥ 114 cycles. */
    fun sampleCounter(machine: PokeyMachine, base15: Boolean, fast: Boolean, rate: Int, root: Int, pitch64: Int): Int {
        val r = rate * 2.0.pow((pitch64 - 64 * root).toDouble() / 768.0)
        return search(machine.clockHz / r, Counter.of(false, fast), base15, 1, PokeyPlayerImage.PCM_MIN_PERIOD)
    }

    /** The counter [ins] uses on [lane] (fast only where the lane allows it), or null when the kind cannot play there. */
    fun counterOf(ins: PokeyInstrument, lane: Int): Counter? =
        if (lane !in ins.kind.lanes) null else Counter.of(ins.kind.joined, ins.fast && lane in ins.kind.fastLanes)

    /**
     * What [ins] on [lane] actually sounds for [pitch64] with [distortion] ([PokeyDistortion] value): the frequency of
     * the counter(s) the player picks (noise distortions `0`/`8` and volume only: the square-wave rate of that counter;
     * difference sounds: the difference rate; samples: levels per second). NaN when the kind cannot play on [lane].
     * [base15]: the shared 15 kHz base clock.
     */
    fun hz(machine: PokeyMachine, base15: Boolean, ins: PokeyInstrument, lane: Int, pitch64: Int, distortion: Int): Double {
        val clocking = counterOf(ins, lane) ?: return Double.NaN
        val n = chosen(machine, base15, ins, lane, pitch64, distortion)
        val c = machine.clockHz
        return when {
            ins.kind == PokeyKind.SAMPLE -> c / period(clocking, base15, n)
            ins.kind.filtered && ins.difference -> {
                val d = ins.filterOffset.coerceIn(PokeyInstrument.DIFFERENCE_OFFSET)
                c / period(clocking, base15, n) - c / period(clocking, base15, n + d)
            }
            else -> c / (period(clocking, base15, n).toDouble() * divisor(distortion))
        }
    }

    /**
     * The counter value N the player gives [ins]'s own channel on [lane] for [pitch64] (high-pass sounds: the sound's
     * channel; its clock channel runs N + offset), or -1 when the kind cannot play there.
     */
    fun chosen(machine: PokeyMachine, base15: Boolean, ins: PokeyInstrument, lane: Int, pitch64: Int, distortion: Int): Int {
        val clocking = counterOf(ins, lane) ?: return -1
        val p = pitch64.coerceIn(0, PokeyPlayerImage.PITCH_MAX)
        return when {
            ins.kind == PokeyKind.SAMPLE -> sampleCounter(machine, base15, clocking.fast, ins.rate, ins.root, p)
            ins.kind.filtered && ins.difference ->
                differenceCounter(machine, base15, clocking, p, ins.filterOffset.coerceIn(PokeyInstrument.DIFFERENCE_OFFSET))
            else -> counter(machine, base15, clocking, p, distortion)
        }
    }

    /** True when [n] is at either end of what [ins]'s counter on [lane] can hold (the note was clamped there). */
    fun atLimit(ins: PokeyInstrument, lane: Int, n: Int): Boolean {
        val clocking = counterOf(ins, lane) ?: return false
        val max = if (ins.kind.filtered && ins.difference) clocking.max - ins.filterOffset.coerceIn(PokeyInstrument.DIFFERENCE_OFFSET) else clocking.max
        return n <= 0 || n >= max || (ins.kind == PokeyKind.SAMPLE && n == firstSampleN(clocking))
    }

    /** The smallest N whose period is at least [PokeyPlayerImage.PCM_MIN_PERIOD] on the 64 kHz clock or at 1.79 MHz. */
    private fun firstSampleN(clocking: Counter): Int =
        if (clocking.fast) PokeyPlayerImage.PCM_MIN_PERIOD - PokeyPlayerImage.FAST_8 else (PokeyPlayerImage.PCM_MIN_PERIOD + PokeyPlayerImage.BASE_64 - 1) / PokeyPlayerImage.BASE_64 - 1

    /** How far [hz] is from [pitch64]'s frequency ([target]) in cents (samples: from the rate the pitch asks for). */
    fun cents(machine: PokeyMachine, base15: Boolean, ins: PokeyInstrument, lane: Int, pitch64: Int, distortion: Int): Double {
        val actual = hz(machine, base15, ins, lane, pitch64, distortion)
        if (actual.isNaN()) return Double.NaN
        val p = pitch64.coerceIn(0, PokeyPlayerImage.PITCH_MAX)
        val wanted = if (ins.kind == PokeyKind.SAMPLE) ins.rate * 2.0.pow((p - 64 * ins.root).toDouble() / 768.0) else target(p)
        return 1200.0 * log2(actual / wanted)
    }

    /** "437.8 Hz" with one decimal (two below 100 Hz). */
    fun hzText(hz: Double): String {
        if (hz.isNaN()) return "—"
        val digits = if (hz < 100) 2 else 1
        val scale = if (digits == 2) 100.0 else 10.0
        val r = (hz * scale).roundToLong()
        val whole = r / scale.toLong()
        val frac = (r % scale.toLong()).toString().padStart(digits, '0')
        return "$whole.$frac Hz"
    }

    /** "+3¢", "−12¢", "±0¢". */
    fun centsText(cents: Double): String {
        if (cents.isNaN()) return "—"
        val r = kotlin.math.round(cents).toLong()
        return when {
            r > 0 -> "+$r¢"
            r < 0 -> "−${-r}¢"
            else -> "±0¢"
        }
    }
}
