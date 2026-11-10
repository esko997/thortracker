package org.thortracker.ui

import org.thortracker.editor.Action

/**
 * A [PopupView] drawn with the shared [Surface], for frontends without native widgets: a scrim and a centred panel
 * laid out like the Android popup (title, message, optional text field, rows with value chips, key hints). The text
 * field takes typed text from the frontend ([type], [erase]) while it has focus.
 */
class DrawnPopup(private val ink: Ink) : PopupView {
    var palette: Palette
        get() = ink.palette
        set(value) { ink.palette = value }

    private var spec: PopupSpec? = null
    private var owner: Popups? = null
    private var hasParent = false
    private var scroll = 0f
    private var cursor = 0
    private var editing = false
    private var revealCursor = false
    private var messageText: String? = null
    private var messageWidth = 0f
    private var messageSize = 0f
    private var messageLines: List<String> = emptyList()
    private val rowRects = ArrayList<Rect>()
    private val panel = Rect()
    private val scratch = Rect()

    val visible: Boolean get() = spec != null

    override val fieldFocused: Boolean get() = editing && spec?.text != null

    override fun show(spec: PopupSpec, hasParent: Boolean, popups: Popups) {
        if (spec !== this.spec) {
            scroll = 0f
            editing = false
            // Long source/attribution messages open at their beginning, not at the action below them.
            revealCursor = spec.message.isNullOrEmpty()
        }
        this.spec = spec
        this.hasParent = hasParent
        owner = popups
        cursor = spec.selected
    }

    override fun hide() {
        spec = null; owner = null; editing = false
        messageText = null; messageLines = emptyList()
    }

    override fun select(entry: Int) {
        cursor = entry; editing = editing && entry == 0; revealCursor = true
    }

    override fun page(direction: Int) {
        scroll += direction * ink.dp(200f)
        revealCursor = false
    }

    override fun focusField() { editing = spec?.text != null }

    /** Typed text for the focused field (keyboard text input). */
    fun type(text: String) {
        val field = spec?.text ?: return
        if (fieldFocused) field.value = (field.value + text).take(field.maxLength)
    }

    /** Backspace in the focused field. */
    fun erase() {
        val field = spec?.text ?: return
        if (fieldFocused) field.value = field.value.dropLast(1)
    }

    /** Leaves the field (Enter/Back while typing) without closing the popup. */
    fun stopTyping() { editing = false }

    fun draw(c: Surface, width: Float, height: Float) {
        val s = spec ?: return
        val u = ink.dp(1f)
        c.fillRect(0f, 0f, width, height, Palette.alpha(ink.palette.BG, 0xB8))
        val w = minOf(520f * u, width - 32f * u).coerceAtLeast(200f * u)
        val titleSize = 17f * u
        val rowH = 46f * u
        val pad = 18f * u
        val titleH = titleSize * 2.2f
        val hintH = 30f * u
        val bodySize = 15f * u
        val itemSize = 16f * u

        // Reuse wrapping across frames; a selected Scala file can contain tens of thousands of characters.
        val wrapWidth = w - pad * 2
        if (s.message !== messageText || wrapWidth != messageWidth || bodySize != messageSize) {
            messageText = s.message
            messageWidth = wrapWidth
            messageSize = bodySize
            messageLines = s.message?.let { wrap(it, wrapWidth, bodySize) }.orEmpty()
        }
        val message = messageLines
        val lineH = bodySize * 1.25f * 1.15f
        val messageH = if (message.isEmpty()) 0f else message.size * lineH + 24f * u
        val fieldH = if (s.text != null) 64f * u else 0f
        val rowsH = s.items.sumOf { (if (it.heading) 34f * u else rowH).toDouble() }.toFloat() + 6f * u
        val bodyH = messageH + fieldH + rowsH
        val maxBody = height * 0.86f - titleH - hintH
        val shownBody = minOf(bodyH, maxBody)
        val h = titleH + shownBody + hintH + 2f * u
        panel.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        ink.round(c, panel, 6f * u, ink.palette.BG)
        ink.outline(c, panel, ink.palette.RULE, u, 6f * u)
        c.fillRound(panel.left, panel.top, panel.right, panel.top + titleH, 6f * u, ink.palette.LANE)
        c.fillRect(panel.left, panel.top + titleH - 6f * u, panel.right, panel.top + titleH, ink.palette.LANE)
        c.text(s.title, panel.left + pad, panel.top, titleH, ink.palette.TEXT, Face.SANS, true, titleSize, Align.LEFT)
        c.fillRect(panel.left, panel.top + titleH, panel.right, panel.top + titleH + u, ink.palette.RULE)

        // Explicit row navigation reveals its cursor; manual paging remains free to read the message.
        val bodyTop = panel.top + titleH + u
        val entryTops = ArrayList<Pair<Float, Float>>()
        var y = messageH
        if (s.text != null) { entryTops += y to y + fieldH; y += fieldH }
        for (item in s.items) { val rh = if (item.heading) 34f * u else rowH; entryTops += y to y + rh; y += rh }
        if (revealCursor) {
            entryTops.getOrNull(cursor)?.let { (top, bottom) ->
                if (top - scroll < 0) scroll = top - 8f * u
                if (bottom - scroll > shownBody) scroll = bottom - shownBody + 8f * u
            }
            revealCursor = false
        }
        scroll = scroll.coerceIn(0f, maxOf(0f, bodyH - shownBody))
        val clipTop = bodyTop
        val clipBottom = bodyTop + shownBody
        fun visible(top: Float, bottom: Float) = bottom > clipTop && top < clipBottom

        val messageTop = bodyTop + 14f * u - scroll
        val firstLine = ((clipTop - messageTop) / lineH).toInt().coerceIn(0, message.size)
        val lastLine = (((clipBottom - messageTop) / lineH).toInt() + 1).coerceIn(firstLine, message.size)
        for (i in firstLine until lastLine) {
            c.text(message[i], panel.left + pad, messageTop + i * lineH, lineH, ink.palette.TEXT, Face.SANS, false, bodySize, Align.LEFT)
        }
        rowRects.clear()
        var entry = 0
        s.text?.let { field ->
            val (top, bottom) = entryTops[0]
            val fy = bodyTop + top - scroll
            scratch.set(panel.left + 16f * u, fy + 12f * u, panel.right - 16f * u, fy + fieldH - 8f * u)
            if (visible(scratch.top, scratch.bottom)) {
                ink.round(c, scratch, 4f * u, ink.palette.LANE)
                ink.outline(c, scratch, if (cursor == 0) ink.palette.FOCUS_ACCENT else ink.palette.RULE, if (cursor == 0 || editing) 2f * u else u, 4f * u)
                val shown = field.value + if (editing) "▏" else ""
                c.text(shown, scratch.left + 12f * u, scratch.top, scratch.height(), ink.palette.TEXT, Face.SANS, false, itemSize, Align.LEFT)
            }
            rowRects += Rect(scratch.left, scratch.top, scratch.right, scratch.bottom)
            entry = 1
        }
        s.items.forEachIndexed { index, item ->
            val (top, bottom) = entryTops[entry + index]
            val ry = bodyTop + top - scroll
            val rb = bodyTop + bottom - scroll
            rowRects += Rect(panel.left, ry, panel.right, rb)
            if (!visible(ry, rb)) return@forEachIndexed
            val on = entry + index == cursor
            if (item.heading) {
                c.text(item.label ?: "", panel.left + 20f * u, ry + 6f * u, rb - ry - 6f * u, ink.palette.FOCUS_ACCENT, Face.SANS, true, 13f * u, Align.LEFT)
                return@forEachIndexed
            }
            if (on) {
                c.fillRect(panel.left + u, maxOf(ry, clipTop), panel.right - u, minOf(rb, clipBottom), ink.palette.CURSOR_FILL)
                c.fillRect(panel.left + u, maxOf(ry, clipTop), panel.left + u + 3f * u, minOf(rb, clipBottom), ink.palette.FOCUS_ACCENT)
            }
            val color = when {
                item.tone == PopupItem.Tone.DANGER -> ink.palette.WRITE
                on -> ink.palette.TEXT
                else -> Palette.mix(ink.palette.TEXT, ink.palette.DIM, 0.35f)
            }
            val label = item.label ?: if (hasParent) "Back" else "Cancel"
            val bold = item.tone != PopupItem.Tone.PLAIN
            item.value?.let { value ->
                val vw = maxOf(92f * u, ink.labelWidth(value, 15f * u, true) + 20f * u)
                scratch.set(panel.right - 18f * u - vw, ry + 8f * u, panel.right - 18f * u, rb - 8f * u)
                ink.round(c, scratch, 4f * u, ink.palette.LANE)
                ink.outline(c, scratch, ink.palette.RULE, u, 4f * u)
                c.text(value, scratch.centerX(), scratch.top, scratch.height(), if (on) ink.palette.FOCUS_ACCENT else ink.palette.TEXT, Face.SANS, true, 15f * u, Align.CENTER)
                c.text(ink.fit(label, scratch.left - panel.left - 32f * u, itemSize), panel.left + 20f * u, ry, rb - ry, color, Face.SANS, bold, itemSize, Align.LEFT)
            } ?: c.text(ink.fit(label, w - 38f * u, itemSize, bold), panel.left + 20f * u, ry, rb - ry, color, Face.SANS, bold, itemSize, Align.LEFT)
        }
        // Cover anything scrolled above or below the body, then the hint strip.
        c.fillRect(panel.left + u, clipBottom, panel.right - u, panel.bottom - u, ink.palette.LANE)
        drawHints(c, s, panel.left + u, clipBottom, panel.right - u, panel.bottom - u)
    }

    private fun drawHints(c: Surface, s: PopupSpec, l: Float, t: Float, r: Float, b: Float) {
        val keys = owner?.keys ?: org.thortracker.input.Keymap.DEFAULT
        val onField = s.text != null && cursor == 0
        val leave = if (s.items.any { it.action == null }) "Back" else "Close"
        val hints = if (s.capture != null) listOf("Button" to "Bind it", "Sel L2 R2 L3 +" to "Chord", "L2+R2 +" to "Both", "Back" to "Cancel") else buildList {
            add(keys.pair(Action.UP, Action.DOWN) to "Move")
            add(keys.label(Action.EDIT) to if (onField) (if (fieldFocused) "Done" else "Type") else "Choose")
            if (!s.message.isNullOrEmpty()) add("L1 R1" to "Page")
            add(keys.label(Action.CLEAR) to leave)
        }
        val size = ink.dp(12f) * 0.82f
        var x = l + ink.dp(14f)
        for ((k, meaning) in hints) {
            if (x + ink.hintWidth(k, meaning, size) > r) break
            x = ink.hint(c, k, meaning, x, t, b - t, size)
        }
    }

    /** The entry under a tap at ([x], [y]), -1 for none (inside the panel), or -2 outside it (the scrim). */
    fun hit(x: Float, y: Float): Int {
        if (!panel.contains(x, y)) return -2
        return rowRects.indexOfFirst { it.contains(x, y) }
    }

    private fun wrap(text: String, width: Float, size: Float): List<String> {
        val out = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) { out += ""; continue }
            var start = 0
            while (start < paragraph.length) {
                var end = start
                var space = -1
                while (end < paragraph.length) {
                    val next = end + if (paragraph[end].isHighSurrogate() && end + 1 < paragraph.length &&
                        paragraph[end + 1].isLowSurrogate()) 2 else 1
                    if (end > start && ink.labelWidth(paragraph.substring(start, next), size) > width) break
                    if (paragraph[end] == ' ') space = next
                    end = next
                }
                // Break long ids/URLs as well as prose. Keep every character, including source whitespace.
                if (end < paragraph.length && space > start) end = space
                out += paragraph.substring(start, end)
                start = end
            }
        }
        return out
    }
}
