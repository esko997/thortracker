package org.thortracker.ui

import org.thortracker.model.Pattern
import org.thortracker.model.Songs

/**
 * A pattern drawn as one thin band per channel, lit where notes start: the arrangement overview used
 * by the Song view's order list and the Pattern view's order column. Every row has the same width, so a
 * shorter pattern draws a proportionally shorter strip. Shared per pane.
 */
internal class DensityStrip(private val ink: Ink) {
    /** Patterns are immutable, so identity is a safe and cheap cache key. */
    private val cache = IdentityCache<Pattern, BooleanArray>(512)

    private fun density(p: Pattern): BooleanArray = cache.getOrPut(p) {
        val rows = p.length
        BooleanArray(p.tracks.size * rows) { i -> p.tracks[i / rows][i % rows].note >= 0 }
    }

    /** Draws [p] into the box; [playRow] >= 0 adds the amber playhead tick. */
    fun draw(c: Surface, left: Float, top: Float, right: Float, bottom: Float, p: Pattern, playRow: Int = -1) {
        val channels = p.tracks.size
        val rows = p.length
        if (channels == 0 || rows == 0 || right - left < 4f) return
        val dens = density(p)
        val bandH = (bottom - top) / channels
        val colW = (right - left) / Songs.ROWS
        val end = left + colW * rows
        for (ch in 0 until channels) {
            val by = top + ch * bandH
            ink.rect(c, left, by, end, by + bandH - 1, Palette.alpha(Palette.channel(ch), 26))
            for (r in 0 until rows) {
                if (dens[ch * rows + r]) {
                    val bx = left + r * colW
                    ink.rect(c, bx, by, bx + maxOf(colW - 1, 1f), by + bandH - 1, Palette.channel(ch))
                }
            }
        }
        if (playRow >= 0) {
            val hx = left + (playRow + 0.5f) * colW
            ink.line(c, hx, top - 1, hx, bottom + 1, Palette.PLAYHEAD, ink.dp(2f))
        }
    }
}
