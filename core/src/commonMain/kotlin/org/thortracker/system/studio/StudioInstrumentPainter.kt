package org.thortracker.system.studio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.system.fm.FmInstrumentPainter
import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.ui.*

/** The active touch stays with the face that accepted it, even if an edit changes the patch type. */
internal class StudioInstrumentPainter(v: PaneView) : Painter(v) {
    private val mono by lazy { StudioSoundPainter(v) }
    private val module by lazy { StudioModulePainter(v) }
    private val modular by lazy { ModularPainter(v) }
    private var drawn: Painter? = null; private var active: Painter? = null
    override fun draw(c: Surface, area: Rect) {
        if (st.studioPaneWidth != area.width() || st.studioPaneHeight != area.height()) {
            if (st.studioPaneWidth >= 0f) StudioSoundView.panel(st).invalidate()
            st.studioPaneWidth = area.width(); st.studioPaneHeight = area.height()
        }
        val face = when (StudioSoundView.patch(song, st)) {
            is MonoPatch -> mono
            is ModularPatch -> modular
            else -> module
        }
        face.draw(c, area); drawn = face
    }
    override fun down(x: Float, y: Float): Boolean {
        val face = drawn ?: return false
        if (face.down(x, y)) { active = face; return true }; return false
    }
    override fun move(x: Float, y: Float) { active?.move(x, y) }
    override fun up(x: Float, y: Float) { val a = active; active = null; a?.up(x, y) }
    override fun cancel() { val a = active; active = null; a?.cancel() }
}

internal class StudioModulePainter(v: PaneView) : FmInstrumentPainter(v) {
    private val plateRect = Rect()
    private val mode = Array(2) { Rect() }
    private val pages = Array(3) { Rect() }
    private val name = Rect(); private val presetsBtn = Rect(); private val listen = Rect()
    private val scope = Rect(); private val prev = Rect(); private val next = Rect(); private val field = Rect(); private val guide = Rect()
    private val previousSlot = Rect(); private val nextSlot = Rect()
    private val bankTiles = Array(10) { Rect() }
    private val bankKeys = IntArray(10)
    private var bankCount = 0
    private val choiceCells = Array(4) { Rect() }
    private var choiceKey = -1; private var choiceCount = 0
    private val badge = Rect()
    private val envFillX = FloatArray(5); private val envFillY = FloatArray(5)
    private val drag = PanelDrag<Pointer>()
    private val cancelPointer: () -> Unit = { cancel() }
    private var heldPad = false
    private val waveCanvas = StudioWaveCanvas()

    private class Pointer(
        val st: ViewState, val revision: Int, val slot: Int, val lane: Int, val page: Int, val key: Int,
        val x: Float, val y: Float, val initial: Int, val unitsPerPixel: Float, val dial: Boolean,
        val preview: Boolean, val tapEntry: Boolean, var moved: Boolean = false, var version: Long,
        val source: Int, val point: Int,
    )

    private var version = -1L; private var state: ViewState? = null; private var revision = 0
    private var slot = 0; private var lane = 0; private var selected = 0; private var page = 0; private var slice = 0
    private var drawnSource = 0; private var drawnPoint = 0; private var multiBankDrawn = false
    private fun facePage(p: StudioPatch) = if (p is WavetablePatch) st.wavetablePage else st.samplerPage
    private var windowStart = 0; private var windowEnd = 1
    private var waveAsset: StudioPcm? = null; private var waveFirst = -1; private var waveLast = -1; private var waveCount = 0
    private val lows = FloatArray(256); private val highs = FloatArray(256)

    private fun button(c: Surface, r: Rect, label: String, accent: Int, focus: Boolean = false, on: Boolean = false) {
        PanelWidgets.segment(ink, c, r, on, accent)
        val size = minOf(ink.unit, r.height() * .55f)
        ink.label(c, ink.fit(label, r.width() - ink.dp(4f), size, on || focus), r.centerX(), r.top, r.height(),
            if (on || focus) ink.palette.TEXT else ink.palette.DIM, on || focus, size, Align.CENTER)
        if (focus) ink.outline(c, r, cursorColor(), ink.dp(1.65f), ink.dp(2f))
    }

    private fun drawLetterBadge(c: Surface, right: Float, top: Float, h: Float, letters: String) {
        val sz = minOf(ink.unit * .74f, h * .66f)
        val pad = sz * .36f
        val w = ink.monoWidth(letters, sz, true) + pad * 2f
        badge.set(right - w, top + (h - sz * 1.32f) * .5f, right, top + (h + sz * 1.32f) * .5f)
        ink.round(c, badge, ink.dp(2.5f), Palette.alpha(ink.palette.PLAYHEAD, 48))
        ink.mono(c, letters, badge.centerX(), top, h, ink.palette.PLAYHEAD, true, sz, Align.CENTER)
    }

    /** Multi-dial hardware bank applies on standard/large Sound faces that aren't full-height wave/slice editors. */
    private fun supportsMultiBank(p: StudioPatch): Boolean = when (p) {
        is FmPatch, is PercussionPatch, is PluckPatch -> true
        is WavetablePatch -> st.wavetablePage == 0
        is SamplerPatch -> st.samplerPage == 2 && StudioModuleControls.pcm(song.studio, p) != null
        else -> false
    }

    override fun draw(c: Surface, area: Rect) {
        state = null
        bankCount = 0; choiceCount = 0; choiceKey = -1; multiBankDrawn = false
        for (r in bankTiles) r.set(0f, 0f, 0f, 0f)
        for (r in choiceCells) r.set(0f, 0f, 0f, 0f)
        prev.set(0f, 0f, 0f, 0f); next.set(0f, 0f, 0f, 0f); field.set(0f, 0f, 0f, 0f)

        val patch = StudioSoundView.patch(song, st); if (patch is MonoPatch) return
        val s = song.studio; val panel = StudioSoundView.panel(st); val advanced = panel.advanced; val p = ink.palette
        val kind = patch.kind
        val accent = StudioGraphics.color(if (patch is WavetablePatch) StudioKind.SAMPLER else kind, p)
        val u = ink.unit; val outerPad = u * .44f
        val top = header(c, area, "Sound", kind.faceName)
        val bank = drawBank(c, area, top, StudioSound.SLOTS, p.channel(st.listenChannel), false) { id -> s.patch(id)?.name?.ifEmpty { "(unnamed)" } }

        plateRect.set(bank.right + outerPad, top + outerPad * .5f, area.right - outerPad, area.bottom - outerPad)
        if (plateRect.width() < u * 4f || plateRect.height() < u * 4f) return
        ink.round(c, plateRect, ink.dp(4f), Palette.mix(p.LANE, accent, .055f))
        ink.outline(c, plateRect, Palette.mix(p.RULE, accent, .24f), ink.dp(1f), ink.dp(4f))

        val pad = u * .42f
        val left = plateRect.left + pad; val right = plateRect.right - pad
        val width = right - left; val bottom = plateRect.bottom - pad
        var y = plateRect.top + pad
        val h = bottom - y
        if (width < 1f || h < 1f) return

        val bar = minOf(u * 2.55f, h * .13f)
        val split = left + width * .50f
        mode[0].set(left, y, split - pad * .25f, y + bar); mode[1].set(split + pad * .25f, y, right, y + bar)
        for (i in 0..1) button(c, mode[i], if (i == 0) "Sound" else "Advanced", accent, st.instField == StudioSoundView.MODE && advanced == (i == 1), advanced == (i == 1))
        y += bar + pad * .42f

        val activeKey = StudioModuleControls.field(patch, st)
        val showPresetsInBar = width >= u * 20f
        val listenW = minOf(width * (if (showPresetsInBar) .20f else .30f), maxOf(ink.dp(46f), u * 5.0f))
        val presetsW = if (showPresetsInBar) minOf(width * .21f, u * 5.4f) else 0f
        val arrowW = minOf(bar, width * (if (showPresetsInBar) .095f else .11f))
        listen.set(right - listenW, y, right, y + bar)
        if (showPresetsInBar) {
            presetsBtn.set(listen.left - pad * .35f - presetsW, y, listen.left - pad * .35f, y + bar)
            nextSlot.set(presetsBtn.left - pad * .35f - arrowW, y, presetsBtn.left - pad * .35f, y + bar)
        } else {
            presetsBtn.set(0f, 0f, 0f, 0f)
            nextSlot.set(listen.left - pad * .35f - arrowW, y, listen.left - pad * .35f, y + bar)
        }
        previousSlot.set(nextSlot.left - arrowW, y, nextSlot.left, y + bar)
        name.set(left, y, previousSlot.left - pad * .35f, y + bar)
        val shownName = if (st.instField == StudioSoundView.NAME) patch.name.padEnd(st.textPos + 1).let {
            it.substring(0, st.textPos) + "[" + it[st.textPos] + "]" + it.substring(st.textPos + 1)
        } else patch.name.ifEmpty { "(unnamed)" }
        button(c, name, shownName, accent, st.instField == StudioSoundView.NAME, on = st.instField == StudioSoundView.NAME)
        button(c, previousSlot, "‹", accent); button(c, nextSlot, "›", accent)
        if (showPresetsInBar) button(c, presetsBtn, "Presets", accent, focus = activeKey == StudioModuleControls.LIBRARY)
        button(c, listen, "Listen", accent, on = heldPad || ctl.auditionHeld)
        y += bar + pad * .45f

        for (r in pages) r.set(0f, 0f, 0f, 0f)
        val tabs = when (patch) {
            is WavetablePatch -> StudioModuleControls.wavePages
            is SamplerPatch -> StudioModuleControls.pageNames
            else -> null
        }
        if (tabs != null && !advanced) {
            val w = width / tabs.size
            val current = facePage(patch)
            for (i in tabs.indices) {
                pages[i].set(left + i * w, y, left + (i + 1) * w - pad * .26f, y + bar)
                button(c, pages[i], tabs[i], accent, activeKey == StudioModuleControls.PAGE && current == i, current == i)
            }
            y += bar + pad * .48f
        }

        val guideH = minOf(u * 1.65f, h * .078f)
        guide.set(left, bottom - guideH, right, bottom)
        val bodyAvailH = guide.top - pad * .4f - y
        val useMultiBank = !advanced && h >= u * 29f && bodyAvailH >= u * 15.5f && width >= u * 16f && supportsMultiBank(patch)

        if (useMultiBank) {
            multiBankDrawn = true
            val scopeFraction = when (patch) {
                is WavetablePatch -> .45f
                is SamplerPatch -> .36f
                else -> .40f
            }
            val scopeH = (bodyAvailH * scopeFraction).coerceIn(u * 5.2f, bodyAvailH - u * 9.5f)
            scope.set(left, y, right, y + scopeH)
            drawScopeContent(c, patch, accent, advanced = false)
            val bankTop = scope.bottom + pad * .55f
            val bankBottom = guide.top - pad * .35f
            drawMultiControlBank(c, patch, left, bankTop, right, bankBottom, accent, activeKey)
        } else {
            val paramH = minOf(u * 5.4f, h * .23f)
            val fy = guide.top - pad * .45f - paramH
            scope.set(left, y, right, fy - pad * .55f)
            if (scope.height() > 0f) drawScopeContent(c, patch, accent, advanced)
            drawSingleFieldCarousel(c, patch, s, left, fy, right, paramH, pad, accent, advanced, activeKey)
        }

        val help = when {
            st.instField == StudioSoundView.MODE -> "Two views of the same patch."
            st.instField == StudioSoundView.NAME -> "Name this sound."
            activeKey == null -> "Choose a sound slot with Up / Down."
            else -> StudioModuleControls.help(patch, activeKey)
        }
        val gs = minOf(u * .86f, guideH * .78f)
        ink.label(c, ink.fit(help, width, gs), left, guide.top, guideH, p.DIM, size = gs)

        version = session.documentVersion; state = st; revision = panel.revision; slot = st.instrument
        lane = st.listenChannel; page = facePage(patch); slice = st.samplerSlice; selected = st.instField
        drawnSource = st.waveSource; drawnPoint = st.wavePoint
    }

    private fun drawScopeContent(c: Surface, patch: StudioPatch, accent: Int, advanced: Boolean) {
        when {
            advanced -> drawExact(c, patch, accent)
            patch is SamplerPatch -> sampleScope(c, patch, accent)
            patch is FmPatch -> fmScope(c, patch, accent)
            patch is PercussionPatch -> drumScope(c, patch, accent)
            patch is WavetablePatch -> waveCanvas.draw(ink, c, scope, patch, st.waveSource, st.wavePoint, st.wavetablePage == 1, accent)
            patch is PluckPatch -> pluckScope(c, patch, accent)
        }
    }

    private fun drawSingleFieldCarousel(
        c: Surface, patch: StudioPatch, s: StudioSound, left: Float, fy: Float, right: Float,
        paramH: Float, pad: Float, accent: Int, advanced: Boolean, key: Int?,
    ) {
        val p = ink.palette; val u = ink.unit; val width = right - left
        val arrow = minOf(paramH, width * .14f)
        prev.set(left, fy, left + arrow, fy + paramH); next.set(right - arrow, fy, right, fy + paramH)
        field.set(prev.right + pad * .5f, fy, next.left - pad * .5f, fy + paramH)
        button(c, prev, "‹", accent); button(c, next, "›", accent)
        val title = if (key == null) (if (st.instField == StudioSoundView.NAME) "Name" else "Face") else StudioModuleControls.title(patch, key, advanced)
        val readout = if (key != null) StudioModuleControls.display(patch, st, s, key) else if (st.instField == StudioSoundView.NAME) patch.name else if (advanced) "Advanced" else "Sound"
        ink.round(c, field, ink.dp(3f), p.BAR)
        ink.outline(c, field, if (key != null) cursorColor() else p.RULE, ink.dp(1.5f), ink.dp(3f))
        val textH = field.height() * .42f; val size = minOf(u, textH * .8f)
        val letters = if (key != null && key < 100) StudioAssignments.boundLetters(song, st.listenChannel, 0, patch.kind, key) else null
        ink.label(c, ink.fit(title, field.width() - pad * (if (letters != null) 4.5f else 2f), size, true), field.centerX(), fy, textH, p.TEXT, true, size, Align.CENTER)
        if (letters != null) drawLetterBadge(c, field.right - ink.dp(6f), fy, textH, letters)
        val spec = key?.takeIf { it < 100 }?.let { k -> patch.kind.parameters.first { it.id == k } }
        val detail = if (spec != null && advanced) "$readout  /  ${fxHex(spec.nearestByte(patch.value(spec.id)))}" else readout
        ink.mono(c, ink.fit(detail, field.width() - pad * 2, size, true), field.centerX(), fy + textH, textH, accent, true, size, Align.CENTER)
        if (key != null) {
            val r = StudioModuleControls.range(patch, st, s, key); val value = StudioModuleControls.value(patch, st, s, key)
            if (r != null && value != null && r.last > r.first) {
                val l = field.left + pad; val w = field.width() - 2 * pad; val cy = field.bottom - pad * .58f
                c.line(l, cy, l + w, cy, p.RULE, ink.dp(2f))
                val pos = l + w * (value - r.first) / (r.last - r.first)
                c.line(l, cy, pos, cy, accent, ink.dp(2.5f))
                c.fillCircle(pos, cy, ink.dp(3f), accent)
            }
        }
    }

    private fun drawMultiControlBank(c: Surface, patch: StudioPatch, left: Float, top: Float, right: Float, bottom: Float, accent: Int, activeKey: Int?) {
        val u = ink.unit; val gap = u * .36f; var y = top
        val s = song.studio; val p = ink.palette
        val allFields = StudioModuleControls.fields(patch, st).filter { it != StudioModuleControls.PAGE && it != StudioModuleControls.LIBRARY }

        // Discrete top segmented switch for Drums Model (Kick / Snare / Hat) or Sampler Loop (Off / Forward loop).
        val discreteKey = when (patch) {
            is PercussionPatch -> 0
            is SamplerPatch -> 3
            else -> -1
        }
        val dialKeys = if (discreteKey >= 0) {
            val spec = patch.kind.parameters.first { it.id == discreteKey }
            val switchH = minOf(u * 2.35f, (bottom - y) * .22f)
            val rowRect = Rect(left, y, right, y + switchH)
            ink.round(c, rowRect, ink.dp(3f), Palette.mix(p.BG, p.LANE, .5f))
            choiceKey = discreteKey
            choiceCount = spec.labels.size
            PanelWidgets.cells(rowRect, choiceCount, ink.dp(2f), choiceCells)
            val cur = patch.value(discreteKey)
            val focusedSwitch = activeKey == discreteKey
            for (i in 0 until choiceCount) {
                val cell = choiceCells[i]
                val on = i == cur
                PanelWidgets.segment(ink, c, cell, on, accent)
                val sz = minOf(u * .94f, cell.height() * .54f)
                ink.label(c, ink.fit(spec.labels[i], cell.width() - ink.dp(6f), sz, on), cell.centerX(), cell.top, cell.height(),
                    if (on) p.TEXT else p.DIM, on, sz, Align.CENTER)
                if (on && focusedSwitch) ink.outline(c, cell, cursorColor(), ink.dp(1.6f), ink.dp(3f))
            }
            y += switchH + gap
            allFields.filter { it != discreteKey }
        } else allFields

        val count = minOf(dialKeys.size, bankTiles.size)
        if (count <= 0 || bottom <= y) return
        val width = right - left
        val cols = when {
            width >= u * 34f && count >= 7 -> 4
            width >= u * 28f && count == 5 -> 5
            count <= 4 && width >= u * 24f -> count
            else -> 3
        }
        val rows = (count + cols - 1) / cols
        val cellW = (width - gap * (cols - 1)) / cols
        val cellH = ((bottom - y) - gap * (rows - 1)) / rows
        bankCount = count
        for (i in 0 until count) {
            val k = dialKeys[i]
            bankKeys[i] = k
            val col = i % cols; val row = i / cols
            val x0 = left + col * (cellW + gap); val y0 = y + row * (cellH + gap)
            val r = bankTiles[i]
            r.set(x0, y0, x0 + cellW, y0 + cellH)
            drawBankControl(c, r, patch, s, k, activeKey == k, accent)
        }
    }

    private fun drawBankControl(c: Surface, r: Rect, patch: StudioPatch, s: StudioSound, key: Int, selected: Boolean, accent: Int) {
        val p = ink.palette; val u = ink.unit
        val fill = if (selected) Palette.mix(p.BAR, accent, .18f) else Palette.mix(p.BG, p.LANE, .72f)
        ink.round(c, r, ink.dp(3f), fill)
        if (selected) ink.outline(c, r, cursorColor(), ink.dp(1.65f), ink.dp(3f))
        else ink.outline(c, r, Palette.alpha(p.RULE, 150), ink.dp(1f), ink.dp(3f))

        val title = StudioModuleControls.title(patch, key, false)
        val shown = StudioModuleControls.display(patch, st, s, key)
        val range = StudioModuleControls.range(patch, st, s, key)
        val value = StudioModuleControls.value(patch, st, s, key)
        val spec = if (key < 100) patch.kind.parameters.firstOrNull { it.id == key } else null
        val fraction = when {
            spec != null -> spec.nearestByte(patch.value(spec.id)) / 255f
            range != null && value != null && range.last > range.first -> ((value - range.first).toFloat() / (range.last - range.first)).coerceIn(0f, 1f)
            else -> 0f
        }
        val letters = if (key < 100) StudioAssignments.boundLetters(song, st.listenChannel, 0, patch.kind, key) else null

        // Compact ribbon tile when vertical room is tight or for frame-offset markers; rotary dial when room permits.
        if (r.height() < u * 3.35f || key >= 100) {
            val pad = minOf(u * .45f, r.width() * .07f)
            val topH = r.height() * .56f
            val sz = minOf(u * .88f, topH * .72f)
            ink.label(c, ink.fit(title, r.width() - 2 * pad - (if (letters != null) ink.dp(20f) else 0f), sz, selected),
                r.left + pad, r.top, topH, p.TEXT, selected, sz)
            if (letters != null) drawLetterBadge(c, r.right - pad, r.top, topH, letters)
            val valSz = minOf(u * .84f, (r.height() - topH) * .78f)
            ink.mono(c, ink.fit(shown, r.width() - 2 * pad, valSz, true), r.left + pad, r.top + topH * .82f, r.height() - topH * .82f, accent, true, valSz)
            val rx0 = r.left + pad; val rx1 = r.right - pad; val ry = r.bottom - ink.dp(3.2f)
            c.line(rx0, ry, rx1, ry, p.RULE, ink.dp(2f))
            c.line(rx0, ry, rx0 + (rx1 - rx0) * fraction, ry, accent, ink.dp(2.2f))
            return
        }

        val size = minOf(u * .92f, r.height() * .21f)
        val labelH = size * 1.28f
        val cx = r.centerX(); val cy = r.centerY() + ink.dp(1f)
        val tickR = minOf(r.width() * .30f, (r.height() - 2f * labelH) * .46f, u * 2.2f)
        val maxLabelW = r.width() - ink.dp(if (letters != null) 24f else 6f)
        ink.label(c, ink.fit(title, maxLabelW, size, selected), cx, r.top + ink.dp(1f), labelH, p.TEXT, selected, size, Align.CENTER)
        if (letters != null) drawLetterBadge(c, r.right - ink.dp(3f), r.top + ink.dp(1f), labelH, letters)

        for (i in 0..10) {
            val idx = i * 10
            val dx = dialX[idx]; val dy = dialY[idx]
            val inner = tickR * if (i % 5 == 0) .82f else .88f
            c.line(cx + dx * inner, cy + dy * inner, cx + dx * tickR, cy + dy * tickR,
                if (i * .1f <= fraction + .001f) accent else p.RULE, ink.dp(1.35f))
        }
        val radius = tickR * .72f
        c.fillCircle(cx, cy + ink.dp(1.4f), radius + ink.dp(1f), p.BG)
        c.fillCircle(cx, cy, radius, Palette.mix(p.BAR, accent, .14f))
        c.strokeCircle(cx, cy, radius, Palette.mix(p.RULE, accent, .22f), ink.dp(1.3f))
        val dialIdx = (fraction * 100f).roundToInt().coerceIn(0, 100)
        val dx = dialX[dialIdx]; val dy = dialY[dialIdx]
        c.line(cx + dx * radius * .32f, cy + dy * radius * .32f, cx + dx * radius * .88f, cy + dy * radius * .88f,
            if (selected) cursorColor() else p.TEXT, ink.dp(2.1f))
        ink.mono(c, ink.fit(shown, r.width() - ink.dp(6f), size, true), cx, r.bottom - labelH - ink.dp(1f), labelH, accent, selected, size, Align.CENTER)
    }

    private var exactFirst = 0; private var exactCount = 0; private var exactRowH = 1f
    private fun drawExact(c: Surface, patch: StudioPatch, accent: Int) {
        val keys = StudioModuleControls.fields(patch, st); val p = ink.palette
        val rows = minOf(keys.size, maxOf(1, (scope.height() / maxOf(ink.dp(34f), ink.unit * 2.4f)).toInt()))
        exactFirst = ((st.instField - 2).coerceAtLeast(0) / rows) * rows; exactCount = minOf(rows, keys.size - exactFirst)
        val rh = scope.height() / rows
        for (i in 0 until exactCount) {
            val index = exactFirst + i; val k = keys[index]
            val title = StudioModuleControls.title(patch, k, true); val value = StudioModuleControls.display(patch, st, song.studio, k)
            val y = scope.top + i * rh; val sel = st.instField == index + 2; val size = minOf(ink.unit, rh * .65f); val pad = ink.unit * .48f
            c.fillRect(scope.left, y, scope.right, y + rh - ink.dp(1f), if (sel) p.CURSOR_FILL else p.LANE)
            ink.label(c, ink.fit(title, scope.width() * .51f - pad, size), scope.left + pad, y, rh, p.TEXT, sel, size)
            ink.mono(c, ink.fit(value, scope.width() * .46f - pad, size), scope.right - pad, y, rh, accent, sel, size, Align.RIGHT)
        }
        exactRowH = rh
    }

    private fun caption(c: Surface, text: String, accent: Int): Float {
        val h = minOf(ink.unit * 1.55f, scope.height() * .28f); val pad = ink.unit * .5f
        val size = minOf(ink.unit * .95f, h * .82f)
        ink.label(c, ink.fit(text, scope.width() - 2 * pad, size), scope.left + pad, scope.top, h, accent, true, size)
        return scope.top + h
    }

    private fun sampleScope(c: Surface, p: SamplerPatch, accent: Int) {
        val a = StudioModuleControls.pcm(song.studio, p); val col = ink.palette
        ink.round(c, scope, ink.dp(3f), Palette.mix(col.BG, accent, .075f))
        val selected = StudioModuleControls.selected(p, st)
        val shape = st.samplerPage == 2
        if (a == null) {
            val size = minOf(ink.unit, scope.height() * .6f)
            ink.label(c, ink.fit("Choose recording", scope.width(), size), scope.centerX(), scope.top, scope.height(), col.TEXT, true, size, Align.CENTER)
            return
        }
        val h = caption(c, when (st.samplerPage) {
            1 -> "${p.slices.size} regions  /  ${st.samplerZoom}×"
            2 -> "Amplitude & filter contour"
            else -> "${a.sampleRate} Hz · ${if (a.channels == 2) "Stereo" else "Mono"} · ${org.thortracker.model.NumberText.fixed(a.frames.toDouble() / maxOf(1, a.sampleRate), 2)} s"
        }, accent)
        val pad = ink.unit * .43f; val l = scope.left + pad; val w = scope.width() - 2 * pad; val b = scope.bottom - pad; val height = b - h
        if (w <= 0 || height <= 0) return
        if (shape) {
            val attack = w * .38f * p.value(4) / 2000; val release = w * .42f * p.value(5) / 4000
            val peak = b - height * (.2f + .76f * p.value(7) / 100f)
            c.line(l, b, l + w, b, col.RULE, ink.dp(1f))
            envFillX[0] = l; envFillY[0] = b
            envFillX[1] = l + attack; envFillY[1] = peak
            envFillX[2] = l + w - release; envFillY[2] = peak
            envFillX[3] = l + w; envFillY[3] = b
            c.polyline(envFillX, envFillY, 4, accent, ink.dp(2.3f))
            return
        }
        val zoom = if (st.samplerPage == 1) st.samplerZoom else 1
        val span = (a.frames / zoom).coerceAtLeast(1)
        val focus = if (st.samplerPage == 1 && selected != null) when (StudioModuleControls.field(p, st)) {
            StudioModuleControls.MARK_OUT -> selected.end; StudioModuleControls.MARK_IN -> selected.start; else -> (selected.start + selected.end) / 2
        } else p.start
        windowStart = (focus - span / 2).coerceIn(0, a.frames - span); windowEnd = windowStart + span
        fun x(frame: Int) = l + w * (frame - windowStart).toFloat() / span
        val start = if (st.samplerPage == 1 && selected != null) selected.start else p.start
        val end = if (st.samplerPage == 1 && selected != null) selected.end else StudioModuleControls.end(p, a.frames)
        val l0 = x(start).coerceIn(l, l + w); val r0 = x(end).coerceIn(l, l + w)
        c.fillRect(l0, h, r0, b, Palette.mix(col.BG, accent, .19f)); val mid = (h + b) * .5f
        c.line(l, mid, l + w, mid, col.RULE, ink.dp(1f))
        val count = minOf(256, span, maxOf(1, (w / ink.dp(2f)).toInt()))
        if (a !== waveAsset || windowStart != waveFirst || windowEnd != waveLast || waveCount != count) {
            for (i in 0 until count) {
                val from = windowStart + (span.toLong() * i / count).toInt()
                val to = windowStart + (span.toLong() * (i + 1) / count).toInt()
                lows[i] = a.waveformMin(from, to); highs[i] = a.waveformMax(from, to)
            }
            waveAsset = a; waveFirst = windowStart; waveLast = windowEnd; waveCount = count
        }
        val amp = height * .44f
        for (i in 0 until count) {
            val px = l + w * (i + .5f) / count
            c.line(px, mid - highs[i] * amp, px, mid - lows[i] * amp, if (px >= l0 && px <= r0) accent else col.DIM, maxOf(ink.dp(1f), w / count))
        }
        if (st.samplerPage == 1) {
            val size = minOf(ink.unit * .84f, height * .28f); val sorted = p.slices
            for (s in sorted) {
                val px = x(s.start); if (px < l || px >= l + w) continue
                c.line(px, h, px, b, if (s.id == st.samplerSlice) col.FOCUS else col.DIM, ink.dp(if (s.id == st.samplerSlice) 2f else 1f))
                if (x(s.end) - px >= size * 1.65f) ink.mono(c, fxHex(s.id), px + 1, h, minOf(size * 1.4f, height), col.TEXT, true, size)
            }
            if (selected != null && x(selected.end) in l..l + w) c.line(x(selected.end), h, x(selected.end), b, col.FOCUS, ink.dp(2f))
        } else {
            c.line(l0, h, l0, b, col.FOCUS, ink.dp(1.4f)); c.line(r0, h, r0, b, col.FOCUS, ink.dp(1.4f))
        }
    }

    private fun fmScope(c: Surface, p: FmPatch, accent: Int) {
        val col = ink.palette
        ink.round(c, scope, ink.dp(4f), Palette.mix(col.BG, accent, .085f))
        val ratio = (100 * p.value(1) + p.value(0) / 2) / p.value(0)
        val ratioText = "${ratio / 100}.${(ratio % 100).toString().padStart(2, '0')} : 1"
        val fb = p.value(3)
        val y = caption(c, "Modulator ($ratioText)  →  Carrier" + (if (fb > 0) "   ·   FB $fb%" else ""), accent)
        val rad = minOf(scope.width() * .15f, (scope.bottom - y) * .40f); if (rad < 1f) return
        val cy = (scope.bottom + y) / 2; val x1 = scope.left + scope.width() * .26f; val x2 = scope.left + scope.width() * .74f
        c.line(x1 + rad, cy, x2 - rad, cy, accent, ink.dp(1.5f + p.value(2) * 3f / 1200))
        // Feedback loop over modulator when active
        if (fb > 0 && cy - rad - ink.dp(6f) > y) {
            val ly = cy - rad - ink.dp(5f)
            c.line(x1 - rad * .45f, cy - rad * .86f, x1 - rad * .45f, ly, col.PLAYHEAD, ink.dp(1.4f))
            c.line(x1 - rad * .45f, ly, x1 + rad * .45f, ly, col.PLAYHEAD, ink.dp(1.4f))
            c.line(x1 + rad * .45f, ly, x1 + rad * .45f, cy - rad * .86f, col.PLAYHEAD, ink.dp(1.4f))
        }
        for (i in 0..1) {
            val cx = if (i == 0) x1 else x2
            c.fillCircle(cx, cy, rad, col.BG)
            c.strokeCircle(cx, cy, rad, if (i == 0) accent else col.TEXT, ink.dp(2f))
            var last = cy
            for (j in 0..48) {
                val t = j / 48f
                val phase = if (i == 0) t * p.value(1) / 100f else t * p.value(0) / 100f + StudioGraphics.sinCycle(t * p.value(1) / 100f) * p.value(2) / 628f
                val at = cy - rad * .44f * StudioGraphics.sinCycle(phase)
                if (j != 0) c.line(cx + rad * ((j - 1) / 48f * 1.65f - .825f), last, cx + rad * (t * 1.65f - .825f), at, accent, ink.dp(1.5f))
                last = at
            }
        }
    }

    private fun drumScope(c: Surface, p: PercussionPatch, accent: Int) {
        val col = ink.palette
        ink.round(c, scope, ink.dp(4f), Palette.mix(col.BAR, accent, .09f))
        val model = p.value(0)
        val y = caption(c, "${p.kind.parameters[0].display(model)}  ·  strike pad", accent); val pad = ink.unit * .6f
        val w = scope.width() - 2 * pad; val h = scope.bottom - y - pad; if (w < 1 || h < 1) return
        val rad = minOf(w * .18f, h * .44f); val cx = scope.left + rad + pad; val cy = y + h * .5f
        c.fillCircle(cx, cy, rad, col.BG)
        c.strokeCircle(cx, cy, rad, accent, ink.dp(2f))
        c.strokeCircle(cx, cy, rad * (.24f + .54f * p.value(1) / 100f), if (heldPad || ctl.auditionHeld) col.FOCUS else accent, ink.dp(2f))
        val lx = cx + rad + pad; val len = scope.right - pad - lx; val tail = .10f + .90f * p.value(4) / 4000f
        var py = cy
        for (i in 1..80) {
            val t = i / 80f; val env = (1 - t / tail).coerceAtLeast(0f)
            val body = StudioGraphics.sinCycle(t * (4f + model * 10f + p.value(3) * .05f))
            val snap = (((i * 73 + i * i * 13) % 53) / 26f - 1f) * (1f - t).let { it * it * it } * p.value(2) / 100f
            val ny = cy - rad * .85f * env * (body * p.value(1) / 100f + snap).coerceIn(-1f, 1f)
            c.line(lx + len * (i - 1) / 80f, py, lx + len * t, ny, accent, ink.dp(1.7f)); py = ny
        }
    }

    /** Three tines describe modal tuning and initial strike weights. The chart is attenuation in dB, not live audio. */
    private fun pluckScope(c: Surface, p: PluckPatch, accent: Int) {
        val palette = ink.palette
        ink.round(c, scope, ink.dp(4f), Palette.mix(palette.BG, accent, .07f))
        val top = caption(c, "Tap to strike", accent)
        val pad = ink.unit * .6f
        val left = scope.left + pad; val right = scope.right - pad
        val bottom = scope.bottom - pad
        val w = right - left; val h = bottom - top
        if (w < 4 || h < 4) return
        val textH = minOf(ink.unit * 1.45f, h * .25f)
        val base = bottom - textH
        val tineColor = if (heldPad || ctl.auditionHeld) palette.FOCUS else accent
        val material = p.value(0) / 100f; val strike = p.value(1) / 100f; val damping = p.value(2) / 100f
        val ring = p.value(3)
        val horizon = when { ring <= 250 -> 250; ring <= 1000 -> 1000; ring <= 4000 -> 4000; else -> 8000 }
        val gx = left + w * .46f
        val chartWidth = right - gx
        c.line(left, base, left + w * .34f, base, tineColor, ink.dp(3f))
        c.line(gx, base, right, base, palette.RULE, ink.dp(1f))
        for (i in 2 downTo 0) {
            val ratio = when (i) { 0 -> 1f; 1 -> 2f + .76f * material; else -> 3f + 2.37f * material }
            val excitation = when (i) { 0 -> 1f; 1 -> .12f + .66f * strike; else -> .06f + .68f * strike }
            val x = left + w * (.05f + i * .12f)
            val tip = base - (base - top) * .88f / ratio
            val thickness = ink.dp(2f + 4f * excitation)
            c.line(x, base, x, tip, tineColor, thickness)
            c.fillCircle(x, tip, thickness * .5f, tineColor)
            val duration = ring * when (i) { 0 -> 1f; 1 -> .90f - .65f * damping; else -> .75f - .63f * damping }
            val end = gx + chartWidth * duration / horizon
            c.line(gx, top + (base - top) * .10f, end, base, if (i == 0) tineColor else palette.DIM, ink.dp(if (i == 0) 2f else 1f))
        }
        val size = minOf(ink.unit * .82f, textH * .75f)
        val span = if (horizon == 250) "250 ms" else "${horizon / 1000} s"
        ink.mono(c, "−60 dB", gx, base, textH, palette.DIM, size = size)
        ink.mono(c, span, right, base, textH, palette.DIM, size = size, align = Align.RIGHT)
    }

    override fun down(x: Float, y: Float): Boolean {
        val panel = StudioSoundView.panel(st)
        if (st !== state || st.kind != ViewKind.INSTRUMENT || version != session.documentVersion || slot != st.instrument ||
            lane != st.listenChannel || slice != st.samplerSlice || revision != panel.revision || selected != st.instField) return false
        val p = StudioSoundView.patch(song, st)
        if (p is MonoPatch || page != facePage(p) ||
            (p is WavetablePatch && (drawnPoint != st.wavePoint || drawnSource != st.waveSource))) return false
        val preview = ctl.editHeld && !panel.editBlocked; StudioSoundView.blockHeldEdit(ctl)
        for (i in 0..1) if (mode[i].contains(x, y)) { StudioSoundView.selectMode(ctl, i == 1); return true }
        if (slotRect.contains(x, y)) { StudioSoundView.selectSlot(ctl, slotScroll + ((y - slotTop) / slotH).toInt() + 1); return true }
        if (previousSlot.contains(x, y) || nextSlot.contains(x, y)) {
            StudioSoundView.selectSlot(ctl, st.instrument + if (nextSlot.contains(x, y)) 1 else -1)
            return true
        }
        if (presetsBtn.width() > 0f && presetsBtn.contains(x, y)) {
            val libIdx = StudioModuleControls.fields(p, st).indexOf(StudioModuleControls.LIBRARY)
            if (libIdx >= 0) StudioSoundView.selectField(ctl, libIdx + 2)
            StudioModuleControls.activate(ctl, StudioModuleControls.LIBRARY)
            return true
        }
        if (listen.contains(x, y)) { listenDown(); return true }
        if (name.contains(x, y)) { StudioSoundView.selectField(ctl, StudioSoundView.NAME); StudioSoundView.requestEntry(ctl); return true }
        for (i in pages.indices) if (pages[i].width() > 0 && pages[i].contains(x, y)) { StudioModuleControls.selectPage(ctl, i); return true }

        // Multi-dial & segmented-choice hardware bank hit-testing
        if (multiBankDrawn && !panel.advanced) {
            if (choiceCount > 0 && choiceKey >= 0) {
                val ci = PanelWidgets.cellAt(choiceCells, choiceCount, x, y)
                if (ci >= 0) {
                    val fieldIdx = StudioModuleControls.fields(p, st).indexOf(choiceKey)
                    if (fieldIdx >= 0) StudioSoundView.selectField(ctl, fieldIdx + 2)
                    StudioModuleControls.set(ctl, choiceKey, ci, preview)
                    return true
                }
            }
            for (i in 0 until bankCount) if (bankTiles[i].contains(x, y)) {
                val key = bankKeys[i]
                val fieldIdx = StudioModuleControls.fields(p, st).indexOf(key)
                val wasFocused = fieldIdx >= 0 && st.instField == fieldIdx + 2
                if (fieldIdx >= 0) StudioSoundView.selectField(ctl, fieldIdx + 2)
                if (!begin(p, key, x, y, ink.dp(116f), preview, tapEntry = wasFocused, dial = true)) {
                    StudioModuleControls.activate(ctl, key)
                }
                return true
            }
        }

        if (prev.width() > 0f && (prev.contains(x, y) || next.contains(x, y))) {
            val n = StudioModuleControls.fields(p, st).size + 2
            val f = (st.instField.coerceAtLeast(0) + if (next.contains(x, y)) 1 else -1).mod(n)
            StudioSoundView.selectField(ctl, if (f == 0) StudioSoundView.MODE else f)
            return true
        }
        if (panel.advanced && scope.contains(x, y)) {
            val i = ((y - scope.top) / exactRowH).toInt(); if (i !in 0 until exactCount) return false
            StudioSoundView.selectField(ctl, exactFirst + i + 2); StudioSoundView.requestEntry(ctl); return true
        }
        if (!panel.advanced && scope.contains(x, y)) {
            if (p is SamplerPatch && StudioModuleControls.pcm(song.studio, p) == null) {
                StudioModuleControls.activate(ctl, StudioModuleControls.SAMPLE)
                return true
            }
            fun dragArtwork(key: Int, units: Float? = null): Boolean {
                val index = StudioModuleControls.fields(p, st).indexOf(key)
                if (index < 0) return false
                StudioSoundView.selectField(ctl, index + 2)
                return begin(p, key, x, y, scope.width() * .5f, preview, tapEntry = false, dial = false, units = units)
            }
            when (p) {
                is WavetablePatch -> {
                    if (st.wavetablePage == 1) {
                        if (!waveCanvas.begin(ctl, p, x, y)) return false
                        panel.cancelTouch = cancelPointer
                    } else {
                        val n = waveCanvas.morphAt(x, y)
                        if (n == null) listenDown() else {
                            StudioSoundView.selectField(ctl, StudioModuleControls.fields(p, st).indexOf(0) + 2)
                            begin(p, 0, x, y, scope.width(), false, tapEntry = false, dial = false, units = waveCanvas.morphUnitsPerPixel, initial = n)
                            StudioModuleControls.set(ctl, 0, n, false)
                            drag.target?.version = session.documentVersion
                        }
                    }
                    return true
                }
                is PluckPatch -> { listenDown(); return true }
                is FmPatch -> return dragArtwork(if (x < scope.centerX()) 1 else 2)
                is PercussionPatch -> {
                    if (x < scope.left + scope.width() * .42f) { listenDown(); return true }
                    return dragArtwork(4)
                }
                is SamplerPatch -> if (st.samplerPage == 2) return dragArtwork(if (x < scope.centerX()) 4 else 5)
                else -> Unit
            }
            if (p is SamplerPatch && p.assetId != null && st.samplerPage <= 1) {
                val region = if (st.samplerPage == 1) StudioModuleControls.selected(p, st) else null
                val a = StudioModuleControls.pcm(song.studio, p)
                if (a != null && (st.samplerPage == 0 || region != null)) {
                    val left = scope.left + ink.unit * .43f; val width = scope.width() - ink.unit * .86f
                    val span = (windowEnd - windowStart).coerceAtLeast(1)
                    val x0 = left + width * ((region?.start ?: p.start) - windowStart).toFloat() / span
                    val x1 = left + width * ((region?.end ?: StudioModuleControls.end(p, a.frames)) - windowStart).toFloat() / span
                    val nearStart = abs(x - x0) <= abs(x - x1)
                    if (minOf(abs(x - x0), abs(x - x1)) <= ink.dp(12f) && width > 0) {
                        val key = if (region != null) { if (nearStart) StudioModuleControls.MARK_IN else StudioModuleControls.MARK_OUT }
                            else if (nearStart) StudioModuleControls.TRIM_IN else StudioModuleControls.TRIM_OUT
                        return dragArtwork(key, span / width)
                    }
                    if (st.samplerPage == 0) { listenDown(); return true }
                }
            }
        }
        if (!panel.advanced && p is SamplerPatch && st.samplerPage == 1 && scope.contains(x, y)) {
            val frame = windowStart + ((x - scope.left - ink.unit * .43f) / (scope.width() - ink.unit * .86f)).coerceIn(0f, 1f) * (windowEnd - windowStart)
            val s = p.slices.firstOrNull { frame >= it.start && frame < it.end }
            if (s != null) { panel.invalidate(); st.samplerSlice = s.id; listenDown() } else if (p.slices.isEmpty()) StudioModuleControls.activate(ctl, StudioModuleControls.CHOP)
            return true
        }
        if (field.width() <= 0f || !field.contains(x, y)) return false
        if (st.instField == StudioSoundView.MODE) { StudioSoundView.selectMode(ctl, !panel.advanced, true); return true }
        if (st.instField == StudioSoundView.NAME) { StudioSoundView.requestEntry(ctl); return true }
        val key = StudioModuleControls.field(p, st) ?: return false
        if (panel.advanced || !begin(p, key, x, y, field.width() * .72f, preview, tapEntry = true, dial = false)) StudioModuleControls.activate(ctl, key)
        return true
    }

    private fun begin(
        p: StudioPatch, key: Int, x: Float, y: Float, travelPx: Float, preview: Boolean,
        tapEntry: Boolean, dial: Boolean, units: Float? = null, initial: Int? = null,
    ): Boolean {
        val r = StudioModuleControls.range(p, st, song.studio, key) ?: return false
        val n = StudioModuleControls.value(p, st, song.studio, key) ?: return false
        val panel = StudioSoundView.panel(st)
        drag.begin(session, "Set ${StudioModuleControls.title(p, key, false)}", Pointer(
            st, panel.revision, st.instrument, st.listenChannel, facePage(p), key, x, y, initial ?: n,
            units ?: ((r.last - r.first) / maxOf(travelPx, 1f)), dial, preview, tapEntry, moved = false,
            version = session.documentVersion, source = st.waveSource, point = st.wavePoint,
        ))
        panel.cancelTouch = cancelPointer
        return true
    }

    private fun listenDown() {
        if (!ctl.auditionHeld) { StudioSoundView.audition(ctl); heldPad = true; StudioSoundView.panel(st).cancelTouch = cancelPointer }
    }

    override fun move(x: Float, y: Float) {
        if (waveCanvas.captured) { waveCanvas.move(ctl, x, y, ink.dp(4f)); return }
        val d = drag.live(session) ?: return
        val panel = StudioSoundView.panel(st)
        if (st !== d.st || st.kind != ViewKind.INSTRUMENT || st.instrument != d.slot || st.listenChannel != d.lane ||
            panel.revision != d.revision || d.version != session.documentVersion) { cancel(); return }
        val p = StudioSoundView.patch(song, st)
        if (p is MonoPatch || facePage(p) != d.page || StudioModuleControls.field(p, st) != d.key ||
            (d.key == StudioModuleControls.HEIGHT && (st.waveSource != d.source || st.wavePoint != d.point))) { cancel(); return }
        if (!d.moved) {
            val dist = if (d.dial) abs(x - d.x) + abs(y - d.y) else abs(x - d.x)
            if (dist < ink.dp(4f)) return
            d.moved = true
        }
        val deltaPx = if (d.dial) (x - d.x + d.y - y) else (x - d.x)
        val n = d.initial + (deltaPx * d.unitsPerPixel).roundToInt()
        StudioModuleControls.set(ctl, d.key, n, d.preview)
        d.version = session.documentVersion
    }

    override fun up(x: Float, y: Float) {
        if (waveCanvas.captured) { waveCanvas.move(ctl, x, y, ink.dp(4f)); cancel(); return }
        move(x, y)
        val d = drag.live(session)
        val tap = d != null && d.tapEntry && !d.moved && session.documentVersion == d.version &&
            d.st === st && d.slot == st.instrument && d.revision == StudioSoundView.panel(st).revision &&
            d.initial == StudioModuleControls.value(StudioSoundView.patch(song, st), st, song.studio, d.key)
        cancel()
        if (tap && d != null) StudioModuleControls.activate(ctl, d.key)
    }

    override fun cancel() {
        val panel = StudioSoundView.panel(drag.target?.st ?: st)
        if (panel.cancelTouch === cancelPointer) panel.cancelTouch = null
        waveCanvas.finish(ctl)
        drag.finish(session)
        if (heldPad) { heldPad = false; if (!ctl.auditionHeld) session.releaseAudition() }
    }

    companion object {
        private fun dialAngle(value: Int) = (135.0 + value * 2.7) * PI / 180.0
        private val dialX = FloatArray(101) { cos(dialAngle(it)).toFloat() }
        private val dialY = FloatArray(101) { sin(dialAngle(it)).toFloat() }
    }
}
