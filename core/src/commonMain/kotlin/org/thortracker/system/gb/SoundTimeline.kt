package org.thortracker.system.gb

import kotlin.math.log2
import org.thortracker.model.NumberText

/**
 * What the Game Boy APU does over time with a [GbInstrument]'s register settings, for the Sound panel's drawings and
 * captions (playback never uses it). It follows binjgb, the emulator the Game Boy engine runs, and the hardware it
 * models: the volume envelope (one step every pace/64 s until it would leave 0..15), the length timer ((64 - length)/256
 * s, the wave channel (256 - length)/256 s), the DAC switched off by an NRx2 with volume 0 and Fade, the Pulse 1
 * frequency sweep with both of its overflow checks, the wave channel's output shift and the noise LFSR. Times count from
 * the note's trigger at the nominal clock rates; the frame sequencer's phase can bring each first clock slightly earlier.
 */
internal object SoundTimeline {
    private const val ENVELOPE_HZ = 64.0
    private const val LENGTH_HZ = 256.0
    private const val SWEEP_HZ = 128.0
    private const val CPU_HZ = 4194304.0

    /** The highest 11-bit period; a sweep calculation above it switches Pulse 1 off. */
    const val MAX_PERIOD = 2047

    /** The longest span [windowSeconds] chooses. */
    const val MAX_WINDOW = 2.0

    private val WINDOWS = doubleArrayOf(0.25, 0.5, 1.0, MAX_WINDOW)
    private val NOISE_DIVISORS = intArrayOf(8, 16, 32, 48, 64, 80, 96, 112)

    // ---- length, DAC and envelope ---------------------------------------------------------------------------

    /** Seconds until the length timer silences the note, or null when Length cut is off. */
    fun cutSeconds(ins: GbInstrument): Double? = when {
        !ins.lengthEnabled -> null
        ins.kind == GbBank.WAVE -> (256 - (ins.length and 0xFF)) / LENGTH_HZ
        else -> (64 - (ins.length and 0x3F)) / LENGTH_HZ
    }

    /** Pulse and noise: NRx2's top five bits are zero (volume 0 with Fade), which switches the channel's DAC off. */
    fun dacOff(ins: GbInstrument): Boolean = ins.kind != GbBank.WAVE && ins.volume == 0 && ins.envelopeDirection == 0

    /** Seconds between envelope steps, or null when the volume holds (pace 0) or on wave sounds (no envelope). */
    fun stepSeconds(ins: GbInstrument): Double? =
        if (ins.kind == GbBank.WAVE || ins.envelopePace == 0) null else ins.envelopePace / ENVELOPE_HZ

    /** Envelope steps before it stops: at 0 fading, at 15 swelling. */
    fun envelopeSteps(ins: GbInstrument): Int = when {
        stepSeconds(ins) == null -> 0
        ins.envelopeDirection == 0 -> ins.volume
        else -> 15 - ins.volume
    }

    /** Seconds until the envelope's last step, or null when it never moves. */
    fun envelopeEndSeconds(ins: GbInstrument): Double? {
        val step = stepSeconds(ins) ?: return null
        val steps = envelopeSteps(ins)
        return if (steps == 0) null else steps * step
    }

    /** Right-shift NR32 applies to the wave samples: mute 4, 100% 0, 50% 1, 25% 2. */
    fun outputShift(ins: GbInstrument): Int = when (ins.waveVolume) {
        1 -> 0
        2 -> 1
        3 -> 2
        else -> 4
    }

    /**
     * The level 0..15 the channel outputs [seconds] after the trigger, before the length cut and the sweep: the
     * envelope on pulse and noise, the loudest possible sample after the output shift on wave sounds.
     */
    fun level(ins: GbInstrument, seconds: Double): Int {
        if (ins.kind == GbBank.WAVE) return 15 shr outputShift(ins)
        if (dacOff(ins)) return 0
        if (ins.envelopePace == 0) return ins.volume
        // Whole steps taken: seconds * 64 / pace, in integers of 1/64 s so step boundaries are exact.
        val taken = minOf(envelopeSteps(ins), (seconds * ENVELOPE_HZ / ins.envelopePace + 1e-9).toInt())
        return if (ins.envelopeDirection == 0) ins.volume - taken else ins.volume + taken
    }

    // ---- Pulse 1 frequency sweep ----------------------------------------------------------------------------

    /** The 11-bit period hUGEDriver writes for absolute [note] (no detune). */
    fun notePeriod(note: Int): Int =
        HugeRules.NOTE_PERIODS[(note - HugeRules.NOTE_OFFSET).coerceIn(0, HugeRules.NOTE_PERIODS.size - 1)]

    /** Seconds between sweep clocks, or null when the sweep never clocks (time 0, or not a pulse sound). */
    fun sweepClockSeconds(ins: GbInstrument): Double? =
        if (ins.kind != GbBank.PULSE || ins.sweepTime == 0) null else ins.sweepTime / SWEEP_HZ

    private fun calculate(ins: GbInstrument, period: Int): Int {
        val delta = period shr ins.sweepShift
        return if (ins.sweepDirection == 1) period + delta else period - delta
    }

    /** True when the trigger's own overflow check (made whenever shift is not 0) silences Pulse 1 at once. */
    fun sweepSilencesAtTrigger(ins: GbInstrument, period: Int): Boolean =
        ins.kind == GbBank.PULSE && ins.sweepShift != 0 && calculate(ins, period) > MAX_PERIOD

    /**
     * Pulse 1's period after one sweep clock from [period], or -1 when the clock switches the channel off: its
     * calculation overflows, or (after writing the new period when shift is not 0) the immediate second check does.
     * With shift 0 the period never changes, but an upward calculation (twice the period) is still checked.
     */
    fun sweepClock(ins: GbInstrument, period: Int): Int {
        val next = calculate(ins, period)
        if (next > MAX_PERIOD) return -1
        val written = if (ins.sweepShift != 0) next else period
        return if (calculate(ins, written) > MAX_PERIOD) -1 else written
    }

    /** Semitones from the note's [start] period to [period] (pulse frequency 131072 / (2048 - period)). */
    fun semitones(start: Int, period: Int): Double = 12.0 * log2((2048.0 - start) / (2048.0 - period))

    /** Seconds after the trigger at which the sweep switches Pulse 1 off for [note], or null when it never does in [limit]. */
    fun sweepOffSeconds(ins: GbInstrument, note: Int, limit: Double = MAX_WINDOW): Double? {
        var period = notePeriod(note)
        if (sweepSilencesAtTrigger(ins, period)) return 0.0
        val clock = sweepClockSeconds(ins) ?: return null
        var t = clock
        while (t <= limit + 1e-9) {
            period = sweepClock(ins, period)
            if (period < 0) return t
            t += clock
        }
        return null
    }

    // ---- span and captions ----------------------------------------------------------------------------------

    /** A span showing the sound's shape at [note]: the envelope, length cut and sweep, rounded up to 0.25, 0.5, 1 or 2 s. */
    fun windowSeconds(ins: GbInstrument, note: Int): Double {
        var end = 0.0
        envelopeEndSeconds(ins)?.let { end = maxOf(end, it) }
        cutSeconds(ins)?.let { end = maxOf(end, it) }
        sweepClockSeconds(ins)?.let { clock ->
            end = maxOf(end, sweepOffSeconds(ins, note) ?: (clock * 8))
        }
        if (end <= 0.0) return 1.0
        return WINDOWS.firstOrNull { it >= end - 1e-9 } ?: MAX_WINDOW
    }

    fun ms(seconds: Double): String = "${NumberText.fixed(seconds * 1000.0, 1)} ms"

    /** One line saying what the volume does: hold, fade or swell with exact timing, the length cut, or why it is silent. */
    fun describe(ins: GbInstrument): String {
        val cut = cutSeconds(ins)
        val cutText = cut?.let { " · length cut at ${ms(it)}" }
        if (ins.kind == GbBank.WAVE) {
            val top = 15 shr outputShift(ins)
            val head = if (top == 0) "Silent: output Mute" else "Output ${SoundParams.waveVolume.format(ins, SoundParams.waveVolume.get(ins))}: samples shifted right ${outputShift(ins)}, loudest level $top of 15"
            return head + (cutText ?: if (top == 0) "" else " · sounds until the next note or OFF")
        }
        if (dacOff(ins)) return "Silent: volume 0 with Fade switches the channel's DAC off"
        val step = stepSeconds(ins)
        val steps = envelopeSteps(ins)
        val end = if (ins.envelopeDirection == 0) ins.volume - steps else ins.volume + steps
        val head = when {
            step == null && ins.volume == 0 -> "Silent: volume 0 holds (pace 0)"
            step == null -> "Holds at volume ${ins.volume}"
            steps == 0 -> "Stays at volume 15: Swell starts at the top"
            ins.envelopeDirection == 0 -> "Fades ${ins.volume} → 0 in ${ms(steps * step)}, a step every ${ms(step)}"
            else -> "Swells ${ins.volume} → 15 in ${ms(steps * step)}, a step every ${ms(step)}"
        }
        val tail = cutText ?: if (end > 0) " · sounds until the next note or OFF" else ""
        return head + tail
    }

    /** What the Pulse 1 sweep does to [note] (named [noteName]), or null when it is off. */
    fun describeSweep(ins: GbInstrument, note: Int, noteName: String): String? {
        if (ins.kind != GbBank.PULSE || (ins.sweepTime == 0 && ins.sweepShift == 0)) return null
        val start = notePeriod(note)
        if (sweepSilencesAtTrigger(ins, start)) return "Pulse 1 at $noteName: silent, the trigger's sweep check overflows period 2047"
        val clock = sweepClockSeconds(ins) ?: return "Sweep time 0: no sweep (shift alone only checks overflow at the trigger)"
        val off = sweepOffSeconds(ins, note)
        if (ins.sweepShift == 0) {
            return if (off != null) "Pulse 1 at $noteName: shift 0 keeps the pitch, but the upward check switches it off at ${ms(off)}"
            else "Shift 0: the sweep clocks every ${ms(clock)} without changing the pitch"
        }
        if (off != null) {
            var period = start
            var t = clock
            var last = start
            while (t < off - 1e-9) { period = sweepClock(ins, period); last = period; t += clock }
            return "Pulse 1 at $noteName: up ${NumberText.fixed(semitones(start, last), 1)} semitones, silent at ${ms(off)} (period overflow)"
        }
        val window = windowSeconds(ins, note)
        var period = start
        var t = clock
        while (t <= window + 1e-9) { period = sweepClock(ins, period); t += clock }
        val dir = if (ins.sweepDirection == 0) "down" else "up"
        return "Pulse 1 at $noteName: $dir ${NumberText.fixed(kotlin.math.abs(semitones(start, period)), 1)} semitones by ${ms(window)}, a step every ${ms(clock)}"
    }

    // ---- noise -------------------------------------------------------------------------------------------------

    /** NR43 hUGEDriver writes for a noise [note]: its poly for the note with the 7-bit width in bit 3. */
    fun noiseNr43(ins: GbInstrument, note: Int): Int =
        HugeRules.noisePoly((note - HugeRules.NOTE_OFFSET).coerceIn(0, HugeRules.NOTE_COUNT - 1)) or ((ins.noiseWidth and 1) shl 3)

    /** How often NR43 [nr43] clocks the LFSR, in Hz, or null when its clock shift (14, 15) never clocks it. */
    fun noiseClockHz(nr43: Int): Double? {
        val shift = nr43 shr 4
        if (shift > 13) return null
        return CPU_HZ / (NOISE_DIVISORS[nr43 and 7] shl shift)
    }

    /**
     * Fills [out] with the noise channel's first [out].size output bits after a trigger (1 = high), as binjgb clocks
     * its LFSR: reset to 0x7FFF, feedback bit 0 XOR bit 1 into bit 14 (15-bit) or bit 6 (7-bit), output bit 0 inverted.
     */
    fun lfsrBits(width: Int, out: BooleanArray) {
        var lfsr = 0x7FFF
        for (i in out.indices) {
            val bit = (lfsr xor (lfsr shr 1)) and 1
            lfsr = if (width == 1) ((lfsr shr 1) and 0x40.inv()) or (bit shl 6) else ((lfsr shr 1) and 0x4000.inv()) or (bit shl 14)
            out[i] = (lfsr and 1) == 0
        }
    }

    /** The 8 steps of each NR11 duty as binjgb plays them (1 = high): 12.5%, 25%, 50%, 75%. */
    val DUTY_STEPS: Array<BooleanArray> = arrayOf(
        booleanArrayOf(false, false, false, false, false, false, false, true),
        booleanArrayOf(true, false, false, false, false, false, false, true),
        booleanArrayOf(true, false, false, false, false, true, true, true),
        booleanArrayOf(false, true, true, true, true, true, true, false),
    )
}
