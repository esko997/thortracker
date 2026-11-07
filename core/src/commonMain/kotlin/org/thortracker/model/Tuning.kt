package org.thortracker.model

import kotlinx.serialization.Serializable
import org.thortracker.system.SoundSystem
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log2

/**
 * A song's microtuning, from a Scala `.scl` file. [cents] are scale degrees 1..n above 1/1, strictly ascending; the
 * last is the period (usually 1200.0, an octave) after which the scale repeats. [root] is the note that sounds 1/1.
 * [tolerance] (one of [TOLERANCES], cents) is how far a played pitch may miss its degree for that degree to be offered
 * for note entry.
 *
 * Cents are measured against the sound system's own 12-note grid: every plain table note is taken as exactly 100
 * cents from its neighbours, so a 12-equal scale maps to plain notes. A chip's note table may deviate from ideal 12-TET
 * in absolute Hz; tunings inherit that. Tuned pitches play as a note plus the system's fine pitch
 * ([SoundSystem.fine]); noise channels are never tuned. See [TuningMap].
 */
@Serializable
data class Tuning(
    val name: String,
    val cents: List<Double>,
    val root: Int = 24,
    val tolerance: Int = 5,
) {
    /** Number of degrees per period. */
    val steps: Int get() = cents.size

    /** The interval after which the scale repeats, in cents. */
    val period: Double get() = cents.last()

    /** Structural problems, one message each, with 1/1 on a note of [range]; empty when the tuning is usable. */
    fun problems(range: IntRange): List<String> {
        val out = ArrayList<String>()
        if (name.length > MAX_NAME) out += "Tuning name is ${name.length} characters long; at most $MAX_NAME are allowed"
        if (root !in range) {
            out += "Tuning root $root is outside ${Songs.noteName(range.first)}..${Songs.noteName(range.last)} (${range.first}-${range.last})"
        }
        if (tolerance !in TOLERANCES) out += "Tuning tolerance $tolerance is not one of ${TOLERANCES.joinToString("/")} cents"
        degreeProblem(cents)?.let { out += "Tuning \"$name\": $it" }
        return out
    }

    companion object {
        /** Allowed note-entry accuracy limits, in cents. */
        val TOLERANCES = listOf(5, 10, 20)
        const val MAX_DEGREES = 256
        const val MAX_NAME = 255

        /**
         * Steps finer than this on average are far below what a note table plus fine pitch resolves (Game Boy: one
         * period unit is 1.5-50 cents) and would make the tuned pitch list enormous.
         */
        const val MIN_AVERAGE_STEP = 1.0

        /** Why [cents] cannot be a tuning's degree list, or null. */
        internal fun degreeProblem(cents: List<Double>): String? {
            if (cents.isEmpty()) return "it has no degrees"
            if (cents.size > MAX_DEGREES) return "it has ${cents.size} degrees; at most $MAX_DEGREES are supported"
            cents.forEachIndexed { i, c ->
                if (!c.isFinite()) return "degree ${i + 1} is not a number"
                if (c <= 0.0) return "degree ${i + 1} (${fmt(c)} cents) is not above 1/1"
                if (i > 0 && c <= cents[i - 1]) {
                    return "degree ${i + 1} (${fmt(c)} cents) is not above degree $i (${fmt(cents[i - 1])} cents); degrees must ascend"
                }
            }
            if (cents.last() / cents.size < MIN_AVERAGE_STEP) {
                return "its ${cents.size} degrees span only ${fmt(cents.last())} cents; steps must average at least " +
                    "${fmt(MIN_AVERAGE_STEP)} cent"
            }
            return null
        }

        internal fun fmt(c: Double): String = NumberText.fixed(c, 3).trimEnd('0').trimEnd('.')
    }
}

/**
 * Scala scale file (`.scl`) reader, per https://www.huygens-fokker.org/scala/scl_format.html: lines starting with
 * `!` are comments; the first other line is the description, the next the number of degrees, then one pitch per line.
 * A pitch containing `.` is in cents, otherwise it is a ratio `a/b` or an integer `a` (= a/1). Text after the value
 * (separated by whitespace) is ignored. 1/1 is implicit; the last pitch is the period.
 */
object Scl {
    private val CENTS = Regex("""[+-]?(\d+\.\d*|\.\d+)""")
    private val RATIO = Regex("""([+-]?\d+)(?:/([+-]?\d+))?""")
    private val COUNT = Regex("""[+-]?\d+""")

    /** @throws IllegalArgumentException with a user-readable reason. */
    fun parse(text: String, fallbackName: String): Tuning {
        val lines = text.removePrefix("\uFEFF").split('\n').map { it.removeSuffix("\r") }
        // (1-based line number, content) of every non-comment line.
        val content = lines.withIndex().filter { !it.value.trimStart().startsWith('!') }.map { it.index + 1 to it.value }
        if (content.isEmpty()) fail("the file has no description line; it is not a Scala .scl file")
        val description = content[0].second.trim()
        val rest = content.drop(1).filter { it.second.isNotBlank() }
        val (countLine, countText) = rest.firstOrNull() ?: fail("the file has no degree count after the description")
        val countToken = token(countText)
        if (!COUNT.matches(countToken)) fail("line $countLine: \"$countToken\" is not a number of degrees")
        val count = WholeNumber(countToken)
        if (count.signum <= 0) fail("line $countLine: the scale has $count degrees; it needs at least 1")
        if (count.digits.length > 3 || count.digits.toInt() > Tuning.MAX_DEGREES) {
            fail("line $countLine: the scale has $count degrees; at most ${Tuning.MAX_DEGREES} are supported")
        }
        val n = count.digits.toInt()
        val pitchLines = rest.drop(1)
        if (pitchLines.size < n) fail("the file declares $n degrees but lists ${pitchLines.size}")
        if (pitchLines.size > n) {
            fail("line ${pitchLines[n].first}: the file declares $n degrees but lists more (\"${token(pitchLines[n].second)}\")")
        }
        val cents = pitchLines.mapIndexed { i, (line, value) ->
            val c = pitchCents(line, token(value))
            if (c <= 0.0) fail("line $line: degree ${i + 1} (${Tuning.fmt(c)} cents) is not above 1/1")
            c
        }
        for (i in 1 until n) {
            if (cents[i] <= cents[i - 1]) {
                fail("line ${pitchLines[i].first}: degree ${i + 1} (${Tuning.fmt(cents[i])} cents) is not above degree $i " +
                    "(${Tuning.fmt(cents[i - 1])} cents); degrees must ascend")
            }
        }
        Tuning.degreeProblem(cents)?.let { fail(it) }
        val name = description.ifEmpty { fallbackName.trim() }.ifEmpty { "Tuning" }.take(Tuning.MAX_NAME)
        return Tuning(name, cents)
    }

    private fun token(line: String): String = line.trim().split(Regex("\\s+"), limit = 2)[0]

    private fun pitchCents(line: Int, value: String): Double {
        if (value.contains('.')) {
            if (!CENTS.matches(value)) fail("line $line: \"$value\" is not a cents value")
            return value.toDouble()
        }
        val m = RATIO.matchEntire(value)
            ?: fail("line $line: \"$value\" is not a pitch; use cents (with a '.') or a ratio such as 3/2")
        val a = WholeNumber(m.groupValues[1])
        val b = WholeNumber(m.groupValues[2].ifEmpty { "1" })
        if (a.signum == 0 || b.signum == 0) fail("line $line: ratio $value has a zero; ratios must be positive")
        if (a.signum < 0 || b.signum < 0) fail("line $line: ratio $value is negative; ratios must be positive")
        val cents = 1200.0 * (log2(a.toDouble()) - log2(b.toDouble()))
        if (!cents.isFinite()) fail("line $line: ratio $value is too large")
        return cents
    }

    /** An integer token (`[+-]?\d+`) of any length, read as `java.math.BigInteger` would: "+007" is 7, "-0" is 0. */
    private class WholeNumber(text: String) {
        private val negative = text.startsWith('-')

        /** Magnitude without leading zeros; empty for zero. */
        val digits = text.removePrefix("+").removePrefix("-").trimStart('0')
        val signum: Int = if (digits.isEmpty()) 0 else if (negative) -1 else 1

        /** Nearest double (infinite beyond the double range), like `BigInteger.toDouble()`. */
        fun toDouble(): Double = toString().toDouble()

        override fun toString(): String = if (signum < 0) "-$digits" else digits.ifEmpty { "0" }
    }

    private fun fail(reason: String): Nothing = throw IllegalArgumentException("Not a usable Scala scale: $reason")
}

/**
 * One scale degree realised on a sound system: [note] plus [detune] (its fine pitch, [SoundSystem.fine]).
 * [degree] counts steps from 1/1 (negative below), [cents] is its exact target above 1/1 and [error] is
 * achieved minus target, in cents.
 */
data class TunedPitch(val degree: Int, val note: Int, val detune: Int, val cents: Double, val error: Double)

/**
 * Where each degree of [tuning] lands on [system], measured against the system's own 12-note grid: note m plus
 * fine y sounds 100 * (m - root) + [SoundSystem.fine] cents(m, y) above 1/1. Plain table notes are therefore exact
 * 100-cent steps, so a 12-equal tuning maps every degree to its plain note. Each degree uses note
 * m = root + floor(target / 100) or the note above (clamped to the system's range), with the fine value closest to
 * the target; ties prefer the smaller fine value, then the lower note.
 */
class TuningMap(val tuning: Tuning, val system: SoundSystem) {
    private val range = system.noteRange
    private val fine = system.fine

    init {
        require(tuning.problems(range).isEmpty()) { tuning.problems(range).joinToString("; ") }
    }

    val steps: Int = tuning.steps

    /** Every degree whose target lies from the lowest note (less the tolerance) up to below one step past the highest, ascending; reachable or not. */
    val all: List<TunedPitch>

    /** Degrees played within [Tuning.tolerance], ascending by pitch (then degree); each (note, detune) appears once. */
    val pitches: List<TunedPitch>

    private val byKey: Map<Int, Int>
    private val pitchCents: DoubleArray

    init {
        val tol = tuning.tolerance.toDouble()
        val low = 100.0 * (range.first - tuning.root) - tol
        val high = 100.0 * (range.last - tuning.root) + 100.0
        var d = (floor(low / tuning.period).toInt() - 1) * steps
        while (target(d) < low) d++
        val found = ArrayList<TunedPitch>()
        while (target(d) < high) {
            found += realise(d)
            d++
        }
        all = found
        val best = HashMap<Int, TunedPitch>()
        for (p in found) {
            if (abs(p.error) > tol + EPSILON) continue
            val key = key(p.note, p.detune)
            val old = best[key]
            if (old == null || abs(p.error) < abs(old.error)) best[key] = p
        }
        pitches = best.values.sortedWith(compareBy({ cents(it.note, it.detune) }, { it.degree }))
        byKey = pitches.withIndex().associate { key(it.value.note, it.value.detune) to it.index }
        pitchCents = DoubleArray(pitches.size) { cents(pitches[it].note, pitches[it].detune) }
    }

    /** Index into [pitches] of exactly this note + detune, or -1. */
    fun index(note: Int, detune: Int): Int {
        if (note !in range || detune !in 0..fine.max) return -1
        return byKey[key(note, detune)] ?: -1
    }

    /** Index into [pitches] of the tuned pitch closest in cents to [note] + [detune] (lower on ties), or -1 if none. */
    fun nearest(note: Int, detune: Int): Int {
        require(note in range && detune in 0..fine.max) { "note $note / detune $detune out of range" }
        val c = cents(note, detune)
        var best = -1
        var bestDistance = Double.POSITIVE_INFINITY
        for (i in pitchCents.indices) {
            val distance = abs(pitchCents[i] - c)
            if (distance < bestDistance) {
                best = i
                bestDistance = distance
            }
        }
        return best
    }

    /** "r/n" (degree within the period / degrees per period), followed by " ·q" for periods q other than the root's. */
    fun label(p: TunedPitch): String {
        val q = p.degree.floorDiv(steps)
        val r = p.degree.mod(steps)
        return if (q == 0) "$r/$steps" else "$r/$steps ·$q"
    }

    /** Nominal pitch of [note] + [detune] in cents above 1/1 on the note-table grid. */
    fun cents(note: Int, detune: Int): Double = 100.0 * (note - tuning.root) + fine.cents(note, detune)

    /** Target of [degree] in cents above 1/1. */
    private fun target(degree: Int): Double {
        val q = degree.floorDiv(steps)
        val r = degree.mod(steps)
        return q * tuning.period + if (r == 0) 0.0 else tuning.cents[r - 1]
    }

    private fun realise(degree: Int): TunedPitch {
        val target = target(degree)
        val below = tuning.root + floor(target / 100.0).toInt()
        var bestNote = -1
        var bestDetune = 0
        var bestError = Double.POSITIVE_INFINITY
        for (note in intArrayOf(below, below + 1).map { it.coerceIn(range) }.distinct()) {
            for (detune in fine.range(note)) {
                val error = cents(note, detune) - target
                if (abs(error) < abs(bestError) || (abs(error) == abs(bestError) && detune < bestDetune)) {
                    bestNote = note
                    bestDetune = detune
                    bestError = error
                }
            }
        }
        return TunedPitch(degree, bestNote, bestDetune, target, bestError)
    }

    private fun key(note: Int, detune: Int) = note * (fine.max + 1) + detune

    private companion object {
        const val EPSILON = 1e-9
    }
}
