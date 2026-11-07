package org.thortracker.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.sign

/**
 * Conventional scale shapes offered as a pattern's pitch guide, as semitone intervals above the root. Serial
 * names are stable; [V2] lists the kinds project format 2-3 can contain (the rest need format 4).
 */
@Serializable
enum class ScaleKind(val label: String, intervals: IntArray) {
    @SerialName("chromatic") CHROMATIC("Chromatic", IntArray(12) { it }),
    @SerialName("major") MAJOR("Major", intArrayOf(0, 2, 4, 5, 7, 9, 11)),
    @SerialName("natural-minor") NATURAL_MINOR("Natural minor", intArrayOf(0, 2, 3, 5, 7, 8, 10)),
    @SerialName("harmonic-minor") HARMONIC_MINOR("Harmonic minor", intArrayOf(0, 2, 3, 5, 7, 8, 11)),
    @SerialName("melodic-minor") MELODIC_MINOR("Melodic minor", intArrayOf(0, 2, 3, 5, 7, 9, 11)),
    @SerialName("dorian") DORIAN("Dorian", intArrayOf(0, 2, 3, 5, 7, 9, 10)),
    @SerialName("phrygian") PHRYGIAN("Phrygian", intArrayOf(0, 1, 3, 5, 7, 8, 10)),
    @SerialName("lydian") LYDIAN("Lydian", intArrayOf(0, 2, 4, 6, 7, 9, 11)),
    @SerialName("mixolydian") MIXOLYDIAN("Mixolydian", intArrayOf(0, 2, 4, 5, 7, 9, 10)),
    @SerialName("locrian") LOCRIAN("Locrian", intArrayOf(0, 1, 3, 5, 6, 8, 10)),
    @SerialName("major-pentatonic") MAJOR_PENTATONIC("Major pentatonic", intArrayOf(0, 2, 4, 7, 9)),
    @SerialName("minor-pentatonic") MINOR_PENTATONIC("Minor pentatonic", intArrayOf(0, 3, 5, 7, 10)),
    @SerialName("blues") BLUES("Blues", intArrayOf(0, 3, 5, 6, 7, 10)),
    @SerialName("whole-tone") WHOLE_TONE("Whole tone", intArrayOf(0, 2, 4, 6, 8, 10)),
    @SerialName("phrygian-dominant") PHRYGIAN_DOMINANT("Phrygian dominant", intArrayOf(0, 1, 4, 5, 7, 8, 10)),
    @SerialName("double-harmonic") DOUBLE_HARMONIC("Double harmonic", intArrayOf(0, 1, 4, 5, 7, 8, 11)),
    @SerialName("hungarian-minor") HUNGARIAN_MINOR("Hungarian minor", intArrayOf(0, 2, 3, 6, 7, 8, 11)),
    @SerialName("hirajoshi") HIRAJOSHI("Hirajoshi", intArrayOf(0, 2, 3, 7, 8)),
    @SerialName("miyako-bushi") MIYAKO_BUSHI("Miyako-bushi (In)", intArrayOf(0, 1, 5, 7, 8));

    private val mask: Int = intervals.fold(0) { m, i -> m or (1 shl i) }

    fun hasInterval(semitones: Int): Boolean = mask and (1 shl semitones.mod(12)) != 0

    companion object {
        /** Kinds that existed in project formats 2 and 3. */
        val V2 = setOf(CHROMATIC, MAJOR, NATURAL_MINOR, MAJOR_PENTATONIC, MINOR_PENTATONIC)
    }
}

/**
 * Editor-only pitch guide of a pattern: which notes the Pattern view's note stepping visits on tonal
 * channels. It never changes stored notes, and lowering, preview audio and `.uge` export ignore it.
 * [root] is a pitch class, 0 = C .. 11 = B (a note's pitch class is note % 12). Stepping stays within the song
 * system's note range ([range] parameters).
 */
@Serializable
data class Scale(val root: Int = 0, val kind: ScaleKind = ScaleKind.CHROMATIC) {
    val isChromatic: Boolean get() = kind == ScaleKind.CHROMATIC

    fun contains(note: Int): Boolean = kind.hasInterval(note - root)

    /** The nearest scale note strictly above ([direction] > 0) or below [note] within [range], or null at the edge. */
    fun step(note: Int, direction: Int, range: IntRange): Int? {
        val d = direction.sign
        if (d == 0) return note
        var n = note + d
        while (n in range) {
            if (contains(n)) return n
            n += d
        }
        return null
    }

    /**
     * [note] moved by whole [octaves]: exact for a scale note; for a note outside the scale, the first scale note
     * at or beyond that octave target in the same direction. Null when nothing fits within [range].
     */
    fun octave(note: Int, octaves: Int, range: IntRange): Int? {
        val d = octaves.sign
        var n = note + 12 * octaves
        if (d == 0 || contains(note)) return n.takeIf { it in range }
        while (n in range) {
            if (contains(n)) return n
            n += d
        }
        return null
    }

    /** [note] itself if it is in the scale, else the nearest scale note above it, else below it (null if none in range). */
    fun snap(note: Int, range: IntRange): Int? = if (contains(note)) note else step(note, 1, range) ?: step(note, -1, range)

    /**
     * [note] itself if it is in the scale, else the closer of the scale notes below and above it; an exact
     * tie goes down. Null if no scale note is in [range].
     */
    fun nearest(note: Int, range: IntRange): Int? {
        if (contains(note)) return note
        val down = step(note, -1, range)
        val up = step(note, 1, range)
        return when {
            down == null -> up
            up == null -> down
            up - note < note - down -> up
            else -> down
        }
    }

    /** "Chromatic", or root and scale, e.g. "D Natural minor". */
    val label: String get() = if (isChromatic) kind.label else "${ROOT_NAMES[root.mod(12)]} ${kind.label}"

    companion object {
        val CHROMATIC = Scale()
        val ROOT_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    }
}
