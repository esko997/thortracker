package org.thortracker.ui

/** A mutable rectangle in pixels (left/top inclusive, right/bottom exclusive), shaped like Android's RectF. */
class Rect(var left: Float = 0f, var top: Float = 0f, var right: Float = 0f, var bottom: Float = 0f) {
    fun width(): Float = right - left
    fun height(): Float = bottom - top
    fun centerX(): Float = (left + right) / 2f
    fun centerY(): Float = (top + bottom) / 2f
    fun contains(x: Float, y: Float): Boolean = left < right && top < bottom && x >= left && x < right && y >= top && y < bottom
    fun set(l: Float, t: Float, r: Float, b: Float) { left = l; top = t; right = r; bottom = b }
    override fun toString() = "Rect($left, $top, $right, $bottom)"
}

enum class Align { LEFT, CENTER, RIGHT }

/** The two text faces the UI uses: monospaced for grid values, condensed sans for labels. */
enum class Face { MONO, SANS }

/**
 * What a platform must draw for the shared views. Colours are ARGB ints ([Palette]). Every primitive is
 * anti-aliased. Text is placed by its row: [text] centres the face's ascent/descent box vertically in [top, top+h]
 * and aligns horizontally at [x].
 */
interface Surface {
    fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int)
    fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int)
    /** An outline centred on the rectangle's edges; [radius] > 0 rounds its corners. */
    fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float)
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float)
    fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int)
    fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float)
    /** A connected line through the first [count] points. */
    fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float)
    fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align)
    fun textWidth(s: String, face: Face, bold: Boolean, size: Float): Float
}

/**
 * A small cache keyed by object identity, for immutable values whose equality is expensive (patterns, instruments).
 * Linear lookup; cleared when full. Platform-independent replacement for java.util.IdentityHashMap in drawing code.
 */
class IdentityCache<K : Any, V>(private val capacity: Int) {
    private val keys = ArrayList<K>()
    private val values = ArrayList<V>()

    fun getOrPut(key: K, make: () -> V): V {
        for (i in keys.indices) if (keys[i] === key) return values[i]
        if (keys.size >= capacity) clear()
        val v = make()
        keys += key
        values += v
        return v
    }

    fun clear() { keys.clear(); values.clear() }
}
