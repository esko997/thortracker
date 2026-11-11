package org.thortracker.system.opm

import org.thortracker.system.panel.SoundFaceChrome
import org.thortracker.ui.Palette
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** Workstation: operator output meters in a violet display, texture faders below, articulation to the right. */
internal class OpmSoundPainter private constructor(v: PaneView, private val advanced: OpmInstrumentPainter) :
    SoundFaceChrome<OpmInstrument>(v, OpmSoundFace, advanced) {
    constructor(v: PaneView) : this(v, OpmInstrumentPainter(v))
    private val box = Rect()
    override fun sidebar(c: Surface, area: Rect) = advanced.drawSidebar(c, area, false)
    override fun slotAt(x: Float, y: Float) = advanced.slotAt(x, y)
    override fun accent() = when (ink.palette.id) {
        Palette.CONTRAST.id -> ink.palette.FOCUS
        Palette.PAPER.id -> 0xFF714498.toInt()
        else -> 0xFFB49BD8.toInt()
    }

    override fun drawInstrument(c: Surface, area: Rect, patch: OpmInstrument, compact: Boolean) {
        if (compact) {
            rail(c, patch, (st.instField - 2).coerceIn(0, 5), area)
            for (i in 0..3) {
                val x = area.right - ink.dp(36f) + ink.dp(8f) * i
                c.fillRect(x, area.top + ink.dp(22f) - ink.dp(18f) * (127 - patch.operators[i].tl) / 127f,
                    x + ink.dp(4f), area.top + ink.dp(22f), if (patch.isCarrier(i)) ink.palette.TEXT else accent())
            }
            return
        }
        val p = ink.palette
        val gap = ink.dp(12f)
        val split = area.left + area.width() * 0.46f
        val screenBottom = area.top + ink.dp(48f)
        c.fillRect(area.left, area.top, split - gap, screenBottom, Palette.mix(p.BG, accent(), 0.16f))
        ink.label(c, if (patch.noise) "FM 8 / noise" else "Workstation", area.left + gap,
            area.top, ink.dp(20f), p.TEXT, true)
        val meterBottom = screenBottom - ink.dp(4f)
        val meterTop = area.top + ink.dp(24f)
        val meterWidth = (split - area.left - gap * 3) / 4
        for (i in 0..3) {
            val x = area.left + gap + i * meterWidth
            val col = if (patch.isCarrier(i)) p.TEXT else accent()
            c.fillRect(x, meterTop, x + meterWidth * 0.6f, meterBottom, p.RULE)
            c.fillRect(x, meterBottom - (meterBottom - meterTop) * (127 - patch.operators[i].tl) / 127f,
                x + meterWidth * 0.6f, meterBottom, col)
        }
        val h = (area.bottom - screenBottom) / 2
        for (i in 0..1) {
            box.set(area.left + gap, screenBottom + i * h, split - gap, screenBottom + (i + 1) * h)
            rail(c, patch, i, box)
        }
        val h2 = area.height() / 2
        val w2 = (area.right - split) / 2
        for (i in 2..5) {
            val x = split + (i - 2) % 2 * w2
            val y = area.top + (i - 2) / 2 * h2
            box.set(x + gap, y, x + w2 - gap, y + h2)
            rail(c, patch, i, box, if (i == 4) p.WRITE else accent())
        }
    }
}
