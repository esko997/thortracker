package org.thortracker.system.opn

import org.thortracker.system.panel.SoundFaceChrome
import org.thortracker.ui.Align
import org.thortracker.ui.Palette
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** PC tone console: FM routing display, SSG step scope, rhythm pads and a native-sample waveform bay. */
internal class OpnSoundPainter private constructor(v: PaneView, private val advanced: OpnInstrumentPainter) :
    SoundFaceChrome<OpnInstrument>(v, OpnSoundFace, advanced) {
    constructor(v: PaneView) : this(v, OpnInstrumentPainter(v))
    private val box = Rect()
    private val diagram = Rect()
    private val operatorNames = arrayOf("1", "2", "3", "4")
    private var sampleData: AdpcmBytes? = null
    private val samplePeaks = FloatArray(64)
    private var sampleTitle = ""
    private var sampleTitleSource: OpnaSample? = null
    private var sampleTitleWidth = -1f
    private var sampleTitleUnit = -1f
    override fun sidebar(c: Surface, area: Rect) = advanced.drawSidebar(c, area, false)
    override fun slotAt(x: Float, y: Float) = advanced.slotAt(x, y)
    override fun accent() = when (ink.palette.id) {
        Palette.CONTRAST.id -> ink.palette.FOCUS
        Palette.PAPER.id -> 0xFF006279.toInt()
        else -> 0xFF70B4C0.toInt()
    }

    override fun drawInstrument(c: Surface, area: Rect, patch: OpnInstrument, compact: Boolean) {
        val p = ink.palette
        val gap = ink.dp(10f)
        c.fillRect(area.left, area.top, area.right, area.bottom, Palette.mix(p.BG, accent(), 0.07f))
        if (compact) {
            rail(c, patch, (st.instField - 2).coerceIn(0, OpnSoundMacros.labels(patch).size - 1), area)
            miniDisplay(c, area, patch)
            return
        }
        val split = area.left + area.width() * 0.55f
        val displayBottom = area.top + (area.height() - ink.dp(96f)).coerceIn(ink.dp(48f), ink.dp(120f))
        diagram.set(area.left + gap, area.top + ink.dp(4f), split - gap, displayBottom - ink.dp(4f))
        c.fillRect(diagram.left, diagram.top, diagram.right, diagram.bottom, p.BG)
        when (patch) {
            is OpnFmSound -> fmDisplay(c, patch)
            is OpnSsgSound -> ssgDisplay(c, patch)
            is OpnRhythmSound -> rhythmDisplay(c)
            is OpnAdpcmSound -> sampleDisplay(c, patch)
        }
        if (patch is OpnRhythmSound) {
            box.set(area.left + gap, displayBottom, split - gap, area.bottom)
            rail(c, patch, 0, box)
            box.set(split + gap, area.top + gap, area.right - gap, area.bottom)
            rail(c, patch, 1, box, p.WRITE)
            return
        }
        val h = (area.bottom - displayBottom) / 2
        for (i in 0..1) {
            box.set(area.left + gap, displayBottom + i * h, split - gap, displayBottom + (i + 1) * h)
            rail(c, patch, i, box)
        }
        val h2 = area.height() / 3
        for (i in 2..4) {
            box.set(split + gap, area.top + (i - 2) * h2, area.right - gap, area.top + (i - 1) * h2)
            rail(c, patch, i, box, if (i == 4) p.WRITE else accent())
        }
        c.line(split, area.top, split, area.bottom, p.RULE, ink.dp(2f))
    }

    private fun miniDisplay(c: Surface, area: Rect, patch: OpnInstrument) {
        val left = area.right - ink.dp(36f)
        val top = area.top + ink.dp(4f)
        val bottom = top + ink.dp(18f)
        when (patch) {
            is OpnFmSound -> for (i in 0..3) {
                val x = left + ink.dp(8f) * i
                c.fillRect(x, bottom - ink.dp(18f) * (127 - patch.operators[i].tl) / 127f,
                    x + ink.dp(4f), bottom, if (patch.isCarrier(i)) ink.palette.TEXT else accent())
            }
            is OpnSsgSound -> {
                val values = patch.volume.values
                val count = values.size.coerceAtLeast(1)
                for (i in 0 until count) {
                    val x = left + ink.dp(32f) * i / count
                    val y = bottom - ink.dp(18f) * (values.getOrNull(i) ?: 15) / 15f
                    c.line(x, y, left + ink.dp(32f) * (i + 1) / count, y, accent(), ink.dp(2f))
                }
            }
            is OpnRhythmSound -> {
                val selected = OpnInstrumentView.listenChannel(song, st) - song.opn.chip.rhythmFirst
                for (i in 0..5) c.fillCircle(left + ink.dp(5f + i % 3 * 11f), top + ink.dp(4f + i / 3 * 10f),
                    ink.dp(3f), if (i == selected) accent() else ink.palette.RULE)
            }
            is OpnAdpcmSound -> {
                c.strokeCircle(left + ink.dp(9f), top + ink.dp(9f), ink.dp(7f), accent(), ink.dp(2f))
                if (patch.loop) c.fillCircle(left + ink.dp(9f), top + ink.dp(9f), ink.dp(3f), accent())
                c.fillRect(left + ink.dp(24f), bottom - ink.dp(18f) * patch.level / 255f,
                    left + ink.dp(30f), bottom, accent())
            }
        }
    }

    private fun fmDisplay(c: Surface, patch: OpnFmSound) {
        val p = ink.palette
        ink.label(c, "FM operator levels", diagram.left, diagram.top, ink.dp(18f), accent(), true, size = ink.unit * 0.8f)
        val width = diagram.width() / 4
        val top = diagram.top + ink.dp(20f)
        val bottom = diagram.bottom - ink.dp(4f)
        for (i in 0..3) {
            val x = diagram.left + width * i
            val level = (127 - patch.operators[i].tl) / 127f
            val color = if (patch.isCarrier(i)) p.TEXT else accent()
            c.fillRect(x + ink.dp(4f), bottom - (bottom - top) * level, x + width - ink.dp(4f), bottom, Palette.mix(p.BG, color, 0.35f))
            ink.label(c, operatorNames[i], x + width / 2, top, bottom - top, color, true, align = Align.CENTER)
            if (patch.isCarrier(i)) c.line(x + width / 2, bottom, x + width / 2, diagram.bottom, p.TEXT, ink.dp(2f))
        }
    }

    private fun ssgDisplay(c: Surface, patch: OpnSsgSound) {
        ink.label(c, "SSG volume program", diagram.left, diagram.top, ink.dp(18f), accent(), true, size = ink.unit * 0.8f)
        val sequence = patch.volume.values
        val count = sequence.size.coerceAtLeast(1)
        val top = diagram.top + ink.dp(20f)
        val bottom = diagram.bottom - ink.dp(6f)
        var previousY = bottom
        for (i in 0 until count) {
            val x = diagram.left + diagram.width() * i / count
            val end = diagram.left + diagram.width() * (i + 1) / count
            val y = bottom - (bottom - top) * (sequence.getOrNull(i) ?: 15) / 15f
            if (i > 0) c.line(x, previousY, x, y, accent(), ink.dp(2f))
            c.line(x, y, end, y, accent(), ink.dp(2f))
            previousY = y
        }
    }

    private fun rhythmDisplay(c: Surface) {
        val selected = OpnInstrumentView.listenChannel(song, st) - song.opn.chip.rhythmFirst
        val width = diagram.width() / 3
        val height = diagram.height() / 2
        for (i in 0..5) {
            val x = diagram.left + i % 3 * width
            val y = diagram.top + i / 3 * height
            c.fillRect(x + ink.dp(2f), y + ink.dp(2f), x + width - ink.dp(2f), y + height - ink.dp(2f),
                if (i == selected) Palette.mix(ink.palette.BG, accent(), 0.4f) else ink.palette.RULE)
            ink.label(c, OpnChip.RHYTHM_NAMES[i], x + width / 2, y, height, ink.palette.TEXT, align = Align.CENTER, size = ink.unit * 0.75f)
        }
    }

    private fun sampleDisplay(c: Surface, patch: OpnAdpcmSound) {
        val sample = song.opn.samples.getOrNull(patch.sample)
        if (sampleData !== sample?.data) {
            sampleData = sample?.data
            samplePeaks.fill(0f)
            if (sample != null) {
                val pcm = OpnAdpcm.decode(sample.data)
                for (i in pcm.indices) {
                    val bucket = (i.toLong() * samplePeaks.size / pcm.size).toInt()
                    samplePeaks[bucket] = maxOf(samplePeaks[bucket], kotlin.math.abs(pcm[i].toInt()) / 32768f)
                }
            }
        }
        if (sampleTitleSource !== sample || sampleTitleWidth != diagram.width() || sampleTitleUnit != ink.unit) {
            sampleTitle = ink.fit(sample?.name ?: "Import sample in Advanced", diagram.width(), ink.unit * 0.8f)
            sampleTitleSource = sample; sampleTitleWidth = diagram.width(); sampleTitleUnit = ink.unit
        }
        ink.label(c, sampleTitle, diagram.left, diagram.top, ink.dp(18f), accent(), size = ink.unit * 0.8f)
        val center = (diagram.top + ink.dp(20f) + diagram.bottom) / 2
        val amplitude = (diagram.bottom - diagram.top - ink.dp(24f)) / 2
        c.line(diagram.left, center, diagram.right, center, ink.palette.RULE, ink.dp(1f))
        for (i in samplePeaks.indices) {
            val x = diagram.left + diagram.width() * i / samplePeaks.size
            c.line(x, center - samplePeaks[i] * amplitude, x, center + samplePeaks[i] * amplitude, accent(), ink.dp(2f))
        }
    }
}
