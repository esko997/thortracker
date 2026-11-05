package org.thortracker.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Conventional scale shapes offered as a pattern's pitch guide, as semitone intervals above the root. */
@Serializable
enum class ScaleKind(val label: String, intervals: IntArray) {
    @SerialName("chromatic") CHROMATIC("Chromatic", IntArray(12) { it }),
    @SerialName("major") MAJOR("Major", intArrayOf(0, 2, 4, 5, 7, 9, 11)),
    @SerialName("natural-minor") NATURAL_MINOR("Natural minor", intArrayOf(0, 2, 3, 5, 7, 8, 10)),
    @SerialName("major-pentatonic") MAJOR_PENTATONIC("Major pentatonic", intArrayOf(0, 2, 4, 7, 9)),
    @SerialName("minor-pentatonic") MINOR_PENTATONIC("Minor pentatonic", intArrayOf(0, 3, 5, 7, 10));

    private val mask: Int = intervals.fold(0) { m, i -> m or (1 shl i) }

    fun hasInterval(semitones: Int): Boolean = mask and (1 shl Math.floorMod(semitones, 12)) != 0
}

/**
 * Editor-only pitch guide of a pattern: which notes the Pattern view's note stepping visits on tonal
 * channels. It never changes stored notes, and lowering, preview audio and `.uge` export ignore it.
 * [root] is a pitch class, 0 = C .. 11 = B (hUGE note 0 is C-3, so a note's pitch class is note % 12).
 */
@Serializable
data class Scale(val root: Int = 0, val kind: ScaleKind = ScaleKind.CHROMATIC) {
    val isChromatic: Boolean get() = kind == ScaleKind.CHROMATIC

    fun contains(note: Int): Boolean = kind.hasInterval(note - root)

    /** The nearest scale note strictly above ([direction] > 0) or below [note] within C-3..B-8, or null at the edge. */
    fun step(note: Int, direction: Int): Int? {
        val d = Integer.signum(direction)
        if (d == 0) return note
        var n = note + d
        while (n in 0..Songs.NOTE_MAX) {
            if (contains(n)) return n
            n += d
        }
        return null
    }

    /**
     * [note] moved by whole [octaves]: exact for a scale note; for a note outside the scale, the first scale note
     * at or beyond that octave target in the same direction. Null when nothing fits within C-3..B-8.
     */
    fun octave(note: Int, octaves: Int): Int? {
        val d = Integer.signum(octaves)
        var n = note + 12 * octaves
        if (d == 0 || contains(note)) return n.takeIf { it in 0..Songs.NOTE_MAX }
        while (n in 0..Songs.NOTE_MAX) {
            if (contains(n)) return n
            n += d
        }
        return null
    }

    /** [note] itself if it is in the scale, else the nearest scale note above it, else below it (null if none in range). */
    fun snap(note: Int): Int? = if (contains(note)) note else step(note, 1) ?: step(note, -1)

    /** "Chromatic", or root and scale, e.g. "D Natural minor". */
    val label: String get() = if (isChromatic) kind.label else "${ROOT_NAMES[Math.floorMod(root, 12)]} ${kind.label}"

    companion object {
        val CHROMATIC = Scale()
        val ROOT_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    }
}
