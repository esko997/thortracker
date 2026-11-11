package org.thortracker.system.opl3

import org.thortracker.system.panel.SoundFaceChrome
import org.thortracker.ui.Palette
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** Organ stop rail above a keyboard registration diagram, never a register grid. */
internal class Opl3SoundPainter private constructor(v: PaneView, private val advanced: Opl3InstrumentPainter) :
    SoundFaceChrome<Opl3Instrument>(v, Opl3SoundFace, advanced) {
    constructor(v: PaneView) : this(v, Opl3InstrumentPainter(v))
    private val box = Rect()
    override fun sidebar(c: Surface, area: Rect) = advanced.drawSidebar(c, area, false)
    override fun slotAt(x: Float, y: Float) = advanced.slotAt(x, y)
    override fun accent() = when (ink.palette.id) {
        Palette.CONTRAST.id -> ink.palette.FOCUS
        Palette.PAPER.id -> 0xFF775300.toInt()
        else -> 0xFFB99453.toInt()
    }

    override fun drawInstrument(c: Surface, area: Rect, patch: Opl3Instrument, compact: Boolean) {
        val p = ink.palette
        c.fillRect(area.left, area.top, area.right, area.bottom, Palette.mix(p.BG, accent(), 0.08f))
        if (compact) {
            val i = (st.instField - 2).coerceIn(0, 5)
            rail(c, patch, i, area)
            registration(c, area.right - ink.dp(38f), area.top + ink.dp(4f), ink.dp(32f), ink.dp(18f), patch)
            return
        }
        val gap = ink.dp(8f)
        val displayHeight = ink.dp(48f)
        val keyWidth = area.width() * 0.5f / 14
        val keyTop = area.top + ink.dp(8f)
        for (key in 0 until 14) {
            val x = area.left + gap + key * keyWidth
            c.fillRect(x, keyTop, x + keyWidth - ink.dp(1f), area.top + displayHeight - gap, p.KEY_WHITE)
            if (key % 7 != 2 && key % 7 != 6) c.fillRect(x + keyWidth * 0.68f, keyTop,
                x + keyWidth * 1.25f, keyTop + ink.dp(18f), p.KEY_BLACK)
        }
        registration(c, area.centerX() + gap * 2, keyTop, area.width() * 0.5f - gap * 3, displayHeight - gap * 2, patch)
        val width = area.width() / 3
        val height = (area.height() - displayHeight) / 2
        for (i in 0..5) {
            val x = area.left + i % 3 * width
            val y = area.top + displayHeight + i / 3 * height
            box.set(x + gap, y, x + width - gap, y + height)
            rail(c, patch, i, box, if (i == 5) p.WRITE else accent())
            c.fillRect(x, y + ink.dp(5f), x + ink.dp(2f), y + height - ink.dp(5f), accent())
        }
    }

    /** Actual operator levels as organ drawbars: output stops are ivory, modulation stops brass. */
    private fun registration(c: Surface, left: Float, top: Float, width: Float, height: Float, patch: Opl3Instrument) {
        val spacing = width / patch.operatorCount
        for (i in 0 until patch.operatorCount) {
            val x = left + (i + 0.5f) * spacing
            val y = top + height * (63 - patch.operators[i].tl) / 63f
            val col = if (patch.isCarrier(i)) ink.palette.TEXT else accent()
            c.line(x, top, x, top + height, ink.palette.RULE, ink.dp(2f))
            c.fillRect(x - spacing * 0.25f, y - ink.dp(2f), x + spacing * 0.25f, y + ink.dp(2f), col)
        }
    }
}
