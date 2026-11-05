package org.thortracker.model

import java.util.Collections

/**
 * Read-only harmony reference for a [Scale], used by the Sketch view: the scale's tones and the triads built
 * from them. It never touches a song. Intervals are semitones above the scale root, so a pitch class is
 * `scale.root + interval` (mod 12).
 *
 * - [tones]: every scale tone in ascending order from the root, labelled with its interval degree relative to
 *   the major scale ("1", "2", "b3", "4", "5", "b6", "b7", ...). A tritone is "#4" and a minor sixth "#5" when
 *   the scale has no perfect fourth / fifth (Lydian, whole tone); a minor third is "#2" in a scale with a major
 *   third and no major second.
 * - [chords]: for seven-tone scales, the diatonic stacked-third triads in degree order, omitting stacks that
 *   are not major, minor, diminished or augmented (e.g. double harmonic's V). For other scales, only the
 *   standard triads whose three tones all belong to the scale (no tone is ever added), ordered by root then
 *   quality. Chromatic has none: choose a scale first.
 *
 * Roman numerals follow the tone degrees: the numeral is the root's degree number, prefixed with the same
 * accidental as its tone label (so natural minor reads i ii° bIII iv v bVI bVII), uppercase for major,
 * lowercase for minor, "°" for diminished and "+" for augmented.
 */
class TonalGuide(val scale: Scale) {
    /** A scale tone: [interval] 0..11 above the root, its pitch [name] (e.g. "F#"), its [degree] (e.g. "b3"). */
    data class Tone(val interval: Int, val name: String, val degree: String)

    /**
     * A root-position triad. [intervals] are ascending semitone offsets above the SCALE root (not the chord
     * root); the first is the chord root (0..11), later tones may exceed 11 where the chord wraps past the
     * octave, e.g. B diminished in C major is [11, 14, 17]. [name] is e.g. "C", "Dm", "Bdim", "Caug";
     * [degree] its roman numeral, e.g. "IV", "vi", "vii°".
     */
    data class Chord(val name: String, val degree: String, val intervals: List<Int>)

    val tones: List<Tone>
    val chords: List<Chord>

    init {
        val root = Math.floorMod(scale.root, 12)
        val intervals = (0 until 12).filter { scale.kind.hasInterval(it) }
        tones = Collections.unmodifiableList(
            intervals.map { Tone(it, Scale.ROOT_NAMES[(root + it) % 12], degree(it)) },
        )
        chords = Collections.unmodifiableList(
            when {
                scale.isChromatic -> emptyList()
                intervals.size == 7 -> intervals.indices.mapNotNull { i ->
                    // Stack thirds: every other scale tone, lifted an octave once the index wraps.
                    val stack = listOf(i, i + 2, i + 4).map { j -> intervals[j % 7] + 12 * (j / 7) }
                    Quality.entries.firstOrNull { it.third == stack[1] - stack[0] && it.fifth == stack[2] - stack[0] }
                        ?.let { chord(root, stack[0], it, degree(stack[0])) }
                }
                else -> intervals.flatMap { r ->
                    Quality.entries
                        .filter { q -> scale.kind.hasInterval(r + q.third) && scale.kind.hasInterval(r + q.fifth) }
                        .map { q -> chord(root, r, q, degree(r)) }
                }
            },
        )
    }

    /** Interval label in this scale's spelling (see the class comment). */
    private fun degree(interval: Int): String {
        val has = scale.kind::hasInterval
        return when (interval) {
            3 -> if (has(4) && !has(2)) "#2" else "b3"
            6 -> if (!has(5)) "#4" else "b5"
            8 -> if (!has(7)) "#5" else "b6"
            else -> DEGREES[interval]
        }
    }

    private enum class Quality(val third: Int, val fifth: Int, val suffix: String) {
        MAJOR(4, 7, ""),
        MINOR(3, 7, "m"),
        DIMINISHED(3, 6, "dim"),
        AUGMENTED(4, 8, "aug"),
    }

    private companion object {
        /** Interval degree labels relative to the major scale, by semitone 0..11. */
        val DEGREES = listOf("1", "b2", "2", "b3", "3", "4", "b5", "5", "b6", "6", "b7", "7")
        val NUMERALS = listOf("I", "II", "III", "IV", "V", "VI", "VII")

        fun chord(root: Int, r: Int, q: Quality, label: String): Chord {
            val accidental = label.dropLast(1)
            val numeral = NUMERALS[label.last() - '1']
            val degree = accidental + when (q) {
                Quality.MAJOR -> numeral
                Quality.MINOR -> numeral.lowercase()
                Quality.DIMINISHED -> numeral.lowercase() + "°"
                Quality.AUGMENTED -> "$numeral+"
            }
            val name = Scale.ROOT_NAMES[(root + r) % 12] + q.suffix
            return Chord(name, degree, Collections.unmodifiableList(listOf(r, r + q.third, r + q.fifth)))
        }
    }
}
