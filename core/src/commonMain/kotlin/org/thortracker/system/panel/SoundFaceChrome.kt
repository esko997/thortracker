package org.thortracker.system.panel

import org.thortracker.editor.Action
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.ViewController
import org.thortracker.ui.Align
import org.thortracker.ui.Painter
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** Interaction adapter only: P remains the backend's real immutable patch, never a universal sound model. */
internal abstract class SoundFaceBinding<P : Any>(val family: String, val nameLimit: Int) {
    fun panel(v: ViewController) = v.state.soundPanel(family)
    abstract fun current(v: ViewController): P?
    abstract fun empty(v: ViewController): P
    abstract fun name(patch: P): String
    abstract fun named(patch: P, name: String): P
    abstract fun store(v: ViewController, patch: P, label: String): Boolean
    abstract fun move(v: ViewController, delta: Int)
    abstract fun publish(v: ViewController)
    abstract fun audition(v: ViewController)
    abstract fun count(patch: P): Int
    /** Families can report an empty slot's control count without constructing a patch every normalization. */
    open fun controlCount(v: ViewController): Int = count(current(v) ?: empty(v))
    abstract fun label(patch: P, control: Int): String
    abstract fun maximum(patch: P, control: Int): Int
    abstract fun value(patch: P, control: Int): Int?
    abstract fun text(patch: P, control: Int): String
    abstract fun hint(patch: P, control: Int): String
    open fun enabled(patch: P, control: Int): Boolean = true
    abstract fun set(patch: P, control: Int, value: Int): P
    open fun coarse(patch: P, control: Int): Int = if (maximum(patch, control) > 31) 8 else 1

    fun normalise(v: ViewController, advancedLast: Int) {
        val st = v.state
        val p = panel(v)
        st.instField = st.instField.coerceIn(-1, if (p.advanced) advancedLast else controlCount(v) + 1)
        if (!p.advanced && st.instField == 0) st.instField = 2
        st.textPos = st.textPos.coerceIn(0, nameLimit - 1)
    }
    fun selectMode(v: ViewController, advanced: Boolean, keepFocus: Boolean = false) {
        MusicalPanelMode.select(v, panel(v), advanced, keepFocus)
        if (!advanced && v.state.instField == 0) v.state.instField = 2
    }
    fun moveSlot(v: ViewController, delta: Int) {
        val p = panel(v)
        p.invalidate()
        if (v.editHeld) { v.useEdit(); p.editBlocked = true }
        val field = v.state.instField
        move(v, delta)
        v.state.instField = field
        normalise(v, Int.MAX_VALUE)
    }
    fun direction(v: ViewController, dx: Int, dy: Int): Boolean {
        val p = panel(v)
        val st = v.state
        if (p.editBlocked && v.editHeld) { v.useEdit(); return true }
        if (p.advanced) {
            if (st.instField == 0 && dy > 0 && !v.editHeld) { st.instField = -1; return true }
            if (st.instField != -1) return false
            if (v.editHeld) v.useEdit()
            if (dx != 0) selectMode(v, dx > 0, true) else if (dy < 0) st.instField = 0
            return true
        }
        if (!v.editHeld) {
            if (dy != 0) moveSlot(v, -dy)
            else MusicalPanelMode.cycleSoundField(v, p, controlCount(v), dx)
            return true
        }
        v.useEdit()
        when (st.instField) {
            -1 -> if (dx != 0) selectMode(v, dx > 0, true)
            1 -> if (dx != 0) {
                val patch = current(v) ?: empty(v)
                store(v, named(patch, NameEdit.edit(name(patch), st.textPos, dx, nameLimit)), "Rename sound")
            } else st.textPos = (st.textPos - dy).coerceIn(0, nameLimit - 1)
            else -> {
                val patch = current(v) ?: empty(v)
                val i = st.instField - 2
                if (i in 0 until count(patch) && enabled(patch, i)) {
                    val delta = dx + dy * coarse(patch, i)
                    if (delta != 0) {
                        val n = ((value(patch, i) ?: if (delta > 0) -1 else maximum(patch, i) + 1) + delta).coerceIn(0, maximum(patch, i))
                        if (store(v, set(patch, i, n), "Set ${label(patch, i)}")) heard(v)
                    }
                }
            }
        }
        return true
    }
    fun editPress(v: ViewController): Boolean {
        panel(v).editBlocked = false
        if (v.state.instField < 0) return true
        if (panel(v).advanced) return false
        if (v.state.instField >= 2 && current(v) != null) audition(v)
        return true
    }
    fun editRelease(v: ViewController, tap: Boolean): Boolean {
        val p = panel(v)
        if (p.editBlocked) { p.editBlocked = false; return true }
        if (v.state.instField < 0) {
            if (tap) selectMode(v, !p.advanced, true)
            return true
        }
        if (p.advanced) return false
        if (!v.auditionHeld) v.session.releaseAudition()
        return true
    }
    fun clear(v: ViewController): Boolean {
        if (panel(v).editBlocked && v.editHeld) { v.useEdit(); v.useClear(); return true }
        if (v.state.instField < 0) return true
        if (panel(v).advanced) return false
        if (v.state.instField == 1) current(v)?.let {
            store(v, named(it, ViewController.deleteChar(name(it), v.state.textPos)), "Rename sound")
        }
        return true
    }
    /** Shoulders page Sound controls; destructive commands must end a pinned gesture before changing its target. */
    fun command(v: ViewController, action: Action): Boolean {
        val p = panel(v)
        if (action == Action.PASTE || action == Action.CUT || action == Action.DUPLICATE) {
            p.invalidate()
            if (v.editHeld) { v.useEdit(); p.editBlocked = true }
        }
        if (p.advanced || (action != Action.PREV_CHANNEL && action != Action.NEXT_CHANNEL)) return false
        p.invalidate()
        if (v.editHeld) { v.useEdit(); p.editBlocked = true }
        MusicalPanelMode.cycleSoundField(v, p, controlCount(v), if (action == Action.NEXT_CHANNEL) 1 else -1)
        return true
    }
    fun heard(v: ViewController) {
        if (v.auditionHeld || (v.editHeld && !panel(v).editBlocked && v.state.instField >= 2)) audition(v)
    }
    fun hints(v: ViewController, hints: MutableList<Hint>): Boolean {
        if (panel(v).advanced && v.state.instField >= 0) return false
        hints += Hint(v.lr(), "Parameter")
        hints += Hint(v.ud(), "Sound slot")
        hints += Hint(v.editLr(), if (v.state.instField < 0) "Sound / Advanced" else if (v.state.instField == 1) "Change letter" else "Shape sound")
        if (v.state.instField == 1) hints += Hint(v.editUd(), "Previous / next letter")
        hints += Hint(v.k(Action.AUDITION), "Listen")
        hints += Hint(v.octaves(), "Listen pitch")
        hints += Hint(v.copyPaste(), "Copy/paste sound")
        return true
    }
}

/** Shared mode/name/paging and gesture plumbing. Each backend owns its complete control composition and artwork. */
internal abstract class SoundFaceChrome<P : Any>(v: PaneView, protected val face: SoundFaceBinding<P>,
    private val precise: Painter, capacity: Int = 8) : Painter(v) {
    protected val controls = Array(capacity) { Rect() }
    protected val rails = Array(capacity) { Rect() }
    protected val values = Array(capacity) { "" }
    protected val indices = IntArray(capacity) { -1 }
    protected val available = BooleanArray(capacity)
    protected val modes = Array(2) { Rect() }
    private val railPatches = arrayOfNulls<Any>(capacity)
    private val railWidths = FloatArray(capacity) { -1f }
    private val railUnits = FloatArray(capacity) { -1f }
    private val railLabels = Array(capacity) { "" }
    private val railValues = Array(capacity) { "" }
    private val body = Rect()
    private val content = Rect()
    private val nameRect = Rect()
    private val previousSlot = Rect()
    private val nextSlot = Rect()
    private val previousControl = Rect()
    private val nextControl = Rect()
    private var advancedPointer = false
    private var cached: P? = null
    private var emptySlot = -1
    private var emptyRevision = -1
    private var emptyPatch: P? = null
    private var title = ""
    private var titleWidth = -1f
    private var titleUnit = -1f
    internal var compact = false
        private set
    private var hintPatch: P? = null
    private var hintControl = -1
    private var hintWidth = -1f
    private var hintUnit = -1f
    private var selectedHint = ""
    private class Target<P>(val slot: Int, val control: Int, val revision: Int, val left: Float, val right: Float,
        val maximum: Int, val original: P, var expected: P?)
    private val drag = PanelDrag<Target<P>>()
    private val cancelTouch: () -> Unit = { cancel() }
    protected abstract fun sidebar(c: Surface, area: Rect): Rect
    protected abstract fun slotAt(x: Float, y: Float): Int
    protected abstract fun drawInstrument(c: Surface, area: Rect, patch: P, compact: Boolean)
    protected open fun accent(): Int = ink.palette.FOCUS

    final override fun draw(c: Surface, area: Rect) {
        val panel = face.panel(ctl)
        val touch = ink.dp(48f)
        val p = ink.palette
        for (r in controls) r.set(0f, 0f, 0f, 0f)
        previousControl.set(0f, 0f, 0f, 0f); nextControl.set(0f, 0f, 0f, 0f)
        c.fillRect(area.left, area.top, area.right, area.bottom, p.BG)
        val modeWidth = minOf(area.width() / 2, maxOf(touch * 2, ink.unit * 7))
        for (i in 0..1) {
            modes[i].set(area.left + i * modeWidth, area.top, area.left + (i + 1) * modeWidth, area.top + touch)
            PanelWidgets.segment(ink, c, modes[i], panel.advanced == (i == 1), accent())
            ink.label(c, if (i == 0) "Sound" else "Advanced", modes[i].centerX(), area.top, touch, p.TEXT, true, align = Align.CENTER)
        }
        if (st.instField == -1) ink.outline(c, modes[if (panel.advanced) 1 else 0], p.FOCUS, ink.dp(2f))
        body.set(area.left, area.top + touch, area.right, area.bottom)
        if (panel.advanced) { precise.draw(c, body); return }
        val bank = sidebar(c, body)
        val actual = face.current(ctl)
        if (actual == null && (emptyPatch == null || emptySlot != st.instrument || emptyRevision != panel.revision)) {
            emptyPatch = face.empty(ctl); emptySlot = st.instrument; emptyRevision = panel.revision
        }
        val patch = actual ?: emptyPatch!!
        val pad = ink.dp(6f)
        val left = bank.right + pad
        val right = area.right - pad
        nextSlot.set(right - touch, bank.top, right, bank.top + touch)
        previousSlot.set(nextSlot.left - touch, bank.top, nextSlot.left, nextSlot.bottom)
        nameRect.set(left, bank.top, previousSlot.left, nextSlot.bottom)
        if (cached !== patch || titleWidth != nameRect.width() || titleUnit != ink.unit) {
            for (i in 0 until face.count(patch)) {
                available[i] = face.enabled(patch, i)
                indices[i] = face.value(patch, i) ?: -1
                values[i] = face.text(patch, i)
            }
            title = ink.fit(if (actual == null) "Empty: edit to create" else face.name(patch).ifEmpty { "Unnamed" },
                nameRect.width(), ink.unit, true)
            cached = patch; titleWidth = nameRect.width(); titleUnit = ink.unit
        }
        ink.label(c, title, left, bank.top, touch, p.TEXT, true)
        ink.label(c, "-", previousSlot.centerX(), bank.top, touch, p.TEXT, true, align = Align.CENTER)
        ink.label(c, "+", nextSlot.centerX(), bank.top, touch, p.TEXT, true, align = Align.CENTER)
        if (st.instField == 1) ink.outline(c, nameRect, p.FOCUS, ink.dp(2f))
        val bottom = body.bottom - ink.unit * 1.5f
        compact = right - left < ink.dp(360f) || bottom - nameRect.bottom < touch * 3
        content.set(left, nameRect.bottom, right, bottom)
        if (compact) {
            previousControl.set(left, content.top, left + touch, bottom)
            nextControl.set(right - touch, content.top, right, bottom)
            content.set(previousControl.right, content.top, nextControl.left, bottom)
            ink.label(c, "<", previousControl.centerX(), content.top, content.height(), p.TEXT, true, align = Align.CENTER)
            ink.label(c, ">", nextControl.centerX(), content.top, content.height(), p.TEXT, true, align = Align.CENTER)
        }
        drawInstrument(c, content, patch, compact)
        val selected = (st.instField - 2).coerceIn(0, face.count(patch) - 1)
        if (hintPatch !== patch || hintControl != selected || hintWidth != right - left || hintUnit != ink.unit) {
            selectedHint = ink.fit(face.hint(patch, selected), right - left, ink.unit * 0.75f)
            hintPatch = patch; hintControl = selected; hintWidth = right - left; hintUnit = ink.unit
        }
        ink.label(c, selectedHint, left, bottom, ink.unit * 1.5f, p.DIM, size = ink.unit * 0.75f)
    }

    /** Registers a horizontal musical rail. Artwork/layout remains the caller's, including custom vertical drawbars. */
    protected fun rail(c: Surface, patch: P, control: Int, area: Rect, color: Int = accent()) {
        controls[control].set(area.left, area.top, area.right, area.bottom)
        val r = rails[control]
        r.set(area.left + ink.dp(10f), area.bottom - ink.dp(14f), area.right - ink.dp(10f), area.bottom - ink.dp(14f))
        val h = minOf(ink.dp(24f), area.height() * 0.3f)
        if (railPatches[control] !== patch || railWidths[control] != area.width() || railUnits[control] != ink.unit) {
            railLabels[control] = ink.fit(face.label(patch, control), area.width(), ink.unit * 0.9f, true)
            railValues[control] = ink.fit(values[control], area.width(), ink.unit * 0.85f)
            railPatches[control] = patch; railWidths[control] = area.width(); railUnits[control] = ink.unit
        }
        ink.label(c, railLabels[control], area.left, area.top, h, ink.palette.TEXT, true, ink.unit * 0.9f)
        ink.label(c, railValues[control], area.left, area.top + h, h, if (available[control]) color else ink.palette.DIM,
            size = ink.unit * 0.85f)
        if (available[control] && indices[control] >= 0) PanelWidgets.rail(ink, c, r.left, r.right, r.top,
            indices[control].toFloat() / face.maximum(patch, control).coerceAtLeast(1), face.maximum(patch, control), color,
            st.instField == control + 2, ctl.editHeld || drag.target != null)
        else c.line(r.left, r.top, r.right, r.top, ink.palette.FAINT, ink.dp(2f))
        if (st.instField == control + 2) c.line(area.left, area.bottom, area.right, area.bottom, ink.palette.FOCUS, ink.dp(2f))
    }
    internal fun modeRect(advanced: Boolean): Rect = modes[if (advanced) 1 else 0]
    internal fun controlRect(index: Int): Rect = controls[index]
    internal fun navigationRect(forward: Boolean): Rect = if (forward) nextControl else previousControl
    internal fun slotNavigationRect(forward: Boolean): Rect = if (forward) nextSlot else previousSlot
    internal fun nameTarget(): Rect = nameRect
    internal fun valueX(index: Int, fraction: Float): Float = rails[index].let { it.left + it.width() * fraction }

    final override fun down(x: Float, y: Float): Boolean {
        cancel()
        val p = face.panel(ctl)
        for (i in 0..1) if (modes[i].contains(x, y)) { face.selectMode(ctl, i == 1); return true }
        if (p.advanced) {
            advancedPointer = precise.down(x, y)
            if (advancedPointer) {
                p.cancelTouch = cancelTouch
                if (ctl.editHeld) { ctl.useEdit(); p.editBlocked = true }
            }
            return advancedPointer
        }
        if (ctl.editHeld) { ctl.useEdit(); p.editBlocked = true }
        val slot = slotAt(x, y)
        if (slot > 0) { if (slot == st.instrument) face.publish(ctl) else face.moveSlot(ctl, slot - st.instrument); return true }
        if (previousSlot.contains(x, y) || nextSlot.contains(x, y)) {
            face.moveSlot(ctl, if (nextSlot.contains(x, y)) 1 else -1); return true
        }
        if (nameRect.contains(x, y)) { st.instField = 1; return true }
        val actual = face.current(ctl)
        val patch = actual ?: face.empty(ctl)
        if (previousControl.contains(x, y) || nextControl.contains(x, y)) {
            st.instField = 2 + ((st.instField - 2).coerceIn(0, face.count(patch) - 1) +
                if (nextControl.contains(x, y)) 1 else face.count(patch) - 1).mod(face.count(patch))
            return true
        }
        for (i in 0 until face.count(patch)) if (controls[i].contains(x, y)) {
            st.instField = i + 2
            if (!face.enabled(patch, i)) return true
            drag.begin(session, "Set ${face.label(patch, i)}", Target(st.instrument, i, p.revision,
                rails[i].left, rails[i].right, face.maximum(patch, i), patch, actual))
            p.cancelTouch = cancelTouch
            setFromX(x)
            return true
        }
        return false
    }
    private fun setFromX(x: Float) {
        val t = drag.live(session) ?: return
        val p = face.panel(ctl)
        if (p.advanced || st.instrument != t.slot || p.revision != t.revision || face.current(ctl) !== t.expected) { cancel(); return }
        val n = PanelWidgets.railValue(x, t.left, t.right, 0, t.maximum)
        val next = face.set(t.original, t.control, n)
        if (next == t.expected || (t.expected == null && next === t.original)) return
        if (face.store(ctl, next, "Set ${face.label(t.original, t.control)}")) face.heard(ctl)
        t.expected = face.current(ctl)
    }
    final override fun move(x: Float, y: Float) { if (advancedPointer) precise.move(x, y) else setFromX(x) }
    final override fun up(x: Float, y: Float) { if (advancedPointer) precise.up(x, y); cancel() }
    final override fun cancel() {
        if (advancedPointer) precise.cancel()
        advancedPointer = false
        drag.finish(session)
        val p = face.panel(ctl)
        if (p.cancelTouch === cancelTouch) p.cancelTouch = null
    }
}
