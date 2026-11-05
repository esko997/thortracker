package org.thortracker.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import org.thortracker.audio.PlaybackPosition
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.Follow
import org.thortracker.editor.Hint
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.editor.channelCount
import org.thortracker.input.Keymap

/**
 * One full-size tracker view. Renders the view kind in [state] from the shared [session] and turns
 * controller actions and touch into session commands. Touch never takes controller focus: the host
 * owns [controllerFocused] and only it routes controller events here.
 */
@SuppressLint("ViewConstructor")
class TrackerView(context: Context, val session: EditorSession, state: ViewState) : View(context) {
    val controller = ViewController(session, state)

    var state: ViewState = state
        set(value) {
            if (value === field) return
            controller.cancelHolds()
            field = value
            controller.state = value
            seenSelection = session.selectionVersion
            seenKind = value.kind
            touchScrolled = false
            controller.normalise()
            viewChangeStart = motion.start()
            invalidate()
        }

    var controllerFocused: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) { controller.cancelHolds(); heldModifiers = emptySet() }
            focusStart = if (value) motion.start() else Motion.OFF
            invalidate()
        }

    /** Chord modifiers held on the controller; set by the host only for the controller-focused view. */
    var heldModifiers: Set<Int> = emptySet()
        set(value) {
            if (field == value) return
            field = value.toSet()
            invalidate()
        }

    /** The user's controller bindings: hints, help and chord hints name these buttons. */
    var keymap: Keymap = Keymap.DEFAULT
        set(value) {
            if (field == value) return
            field = value
            controller.keys = value
            invalidate()
        }

    var playback: PlaybackPosition = PlaybackPosition()
        set(value) {
            if (field == value) return
            field = value
            if (!controller.holding && activePainter == null) {
                controller.followPlayback(value.order, value.playing)
                // Following resumes after the gesture; never move its target underneath it.
                if (value.playing && state.kind == ViewKind.PATTERN && state.followPlayback) touchScrolled = false
            }
            invalidate()
        }

    internal val ink = Ink(resources.displayMetrics.density)
    internal val strip = DensityStrip(ink)
    internal val motion = Motion(this)
    private var viewChangeStart = Motion.OFF
    private var focusStart = Motion.OFF
    private var seenMessage: String? = null
    private var messageStart = Motion.OFF
    private val scratch = RectF()
    private val painters = HashMap<ViewKind, Painter>()
    private var activePainter: Painter? = null
    private var handling = 0
    private var seenSelection = session.selectionVersion
    private var seenKind = state.kind

    /** Set when the user scrolled by touch; cleared on the next controller move so the cursor comes back into view. */
    internal var touchScrolled = false

    private var checkedSong: org.thortracker.model.Song? = null
    private var checkedProblems: List<String> = emptyList()
    private var warnedSong: org.thortracker.model.Song? = null
    private var checkedWarnings: List<String> = emptyList()

    /** Export problems of the current song, recomputed only when the document changes. */
    internal fun problems(): List<String> {
        val s = session.song
        if (s !== checkedSong) {
            checkedProblems = org.thortracker.model.SongValidator.errors(s)
            checkedSong = s
        }
        return checkedProblems
    }

    /** Legal but probably unintended details of the current song. */
    internal fun warnings(): List<String> {
        val s = session.song
        if (s !== warnedSong) {
            checkedWarnings = org.thortracker.model.SongValidator.warnings(s)
            warnedSong = s
        }
        return checkedWarnings
    }

    init {
        // Android view focus is unrelated to controller focus (owned by the host), but this view must
        // hold it. While a window is in touch mode, ViewRootImpl's EarlyPostImeInputStage consumes the
        // first D-pad key ("leaving touch mode") whenever that moves focus to some view, before the
        // Activity sees it. A focused non-ViewGroup that is focusable in touch mode keeps focus across
        // touch/non-touch transitions, so no D-pad press is ever swallowed. Touch does not request focus
        // here (onTouchEvent never calls super), and focus highlight is off.
        isFocusable = true
        isFocusableInTouchMode = true
        isFocusedByDefault = true
        defaultFocusHighlightEnabled = false
        controller.normalise()
    }

    /** Current controller hints (key label, meaning); while one chord modifier is held, its chords. */
    val hints: List<Pair<String, String>> get() = currentHints().map { it.keys to it.text }

    internal fun currentHints(): List<Hint> {
        val mods = heldModifiers
        if (mods.isEmpty()) return controller.hints()
        if (mods.size > 1) return listOf(Hint(mods.joinToString("+") { Keymap.buttonName(it) }, "No chord: release one"))
        return keymap.chords(mods.first())
            .sortedBy { (c, _) -> if (c.host) 0 else 1 }
            .map { (c, b) -> Hint(Keymap.label(b), c.short) }
    }

    /** Re-reads the session: applies selection following and repaints. Call from the session callbacks. */
    fun refresh() {
        val kindChanged = state.kind != seenKind
        seenKind = state.kind
        if (kindChanged) viewChangeStart = motion.start()
        if (handling == 0) {
            if (kindChanged || session.selectionVersion != seenSelection) {
                if (controller.holding || activePainter != null) {
                    deferredFollow = true // don't move the subject under a held A/B/Y or a touch drag
                } else {
                    seenSelection = session.selectionVersion
                    applyFollow()
                }
            }
            // The host may have changed the lock; hold the view on it.
            Follow.applyLock(state, session.song.channelCount())
        } else {
            controller.syncLock()
        }
        controller.normalise()
        invalidate()
    }

    /**
     * Releases everything held: modifier, pressed actions, audition, touch drags and any open undo
     * gesture. Call before dialogs, on pause, display disconnect, swap and focus changes, because the
     * matching key-up may never arrive.
     */
    fun cancelInteraction() {
        endTouch(notify = false) { it.cancel() }
        controller.cancelHolds()
        invalidate()
    }

    /** A controller [action] press ([down], [repeat] > 0 for key repeats) or release, routed by the host. */
    fun controllerAction(action: Action, down: Boolean, repeat: Int): Boolean = when {
        !down -> release(action)
        repeat == 0 -> press(action)
        action.isDirection -> press(action)
        else -> true
    }

    fun press(action: Action): Boolean = input {
        if (action.isDirection || action == Action.EDIT) touchScrolled = false
        controller.press(action)
    }

    fun release(action: Action): Boolean = input { controller.release(action) }

    /** Runs view-originated input so the selection it publishes is not followed back onto this view. */
    internal fun <T> input(block: () -> T): T {
        val before = session.selectionVersion
        val songBefore = session.song
        val selBefore = session.selection
        handling++
        try {
            return block()
        } finally {
            handling--
            if (session.selectionVersion != before) ownVersion = session.selectionVersion
            if (handling == 0) {
                if (deferredFollow && !controller.holding && activePainter == null) { deferredFollow = false; applyFollow() }
                seenSelection = session.selectionVersion
                controller.syncLock()
                if (session.song !== songBefore) painter().edited(songBefore, selBefore)
            }
            invalidate()
        }
    }

    private var deferredFollow = false

    private fun applyFollow() {
        controller.follow()
        if (state.kind == ViewKind.HELP && !state.pinned && session.selection.context == ViewKind.HELP) state.helpPage = 1
    }

    private var ownVersion = -1L

    /**
     * True while this view is publishing the current shared selection, when it published the latest
     * one, or while a touch drag or held A/B/Y is in progress on it. Hosts should not apply contextual
     * view replacement to such a view; [onTouchEnded] says when a drag is over.
     */
    val ownsSelection: Boolean
        get() = handling > 0 || activePainter != null || controller.holding || ownVersion == session.selectionVersion

    /** Called after a touch drag ends on its own (not via [cancelInteraction]), so the host can apply deferred context. */
    var onTouchEnded: (() -> Unit)? = null

    internal val touching: Boolean get() = activePainter != null

    /** Ends the active touch. The painter is released first so a follow deferred during the drag applies now. */
    private inline fun endTouch(notify: Boolean, crossinline end: (Painter) -> Unit) {
        val p = activePainter ?: return
        activePainter = null
        input { end(p) }
        if (notify) onTouchEnded?.invoke()
    }

    private fun painter(): Painter = painters.getOrPut(state.kind) {
        when (state.kind) {
            ViewKind.PATTERN -> PatternPainter(this)
            ViewKind.SONG -> SongPainter(this)
            ViewKind.INSTRUMENT -> SoundPainter(this)
            ViewKind.WAVE -> WavePainter(this)
            ViewKind.KEYBOARD -> KeyboardPainter(this)
            ViewKind.SKETCH -> SketchPainter(this)
            ViewKind.HELP -> HelpPainter(this)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        ink.layout(w, h)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        motion.refreshScale()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // The animator scale may have been changed in system settings while the app was away.
        if (hasWindowFocus) motion.refreshScale()
    }

    override fun onDraw(canvas: Canvas) {
        motion.beginFrame()
        ink.layout(width, height)
        canvas.drawColor(Palette.BG)
        val hintH = ink.unit * 1.9f
        val area = RectF(0f, 0f, width.toFloat(), height - hintH)
        controller.normalise()
        painter().draw(canvas, area)
        drawViewChange(canvas, area)
        drawHintBar(canvas, RectF(0f, height - hintH, width.toFloat(), height.toFloat()))
        if (controllerFocused) {
            val w = ink.dp(3f)
            scratch.set(w / 2, w / 2, width - w / 2, height - w / 2)
            ink.outline(canvas, scratch, Palette.PLAYHEAD, w)
            // Taking control: a soft inner rim that settles into the steady border.
            val f = motion.fade(focusStart, 320)
            if (f > 0f) {
                val hw = ink.dp(7f) * f
                scratch.set(w + hw / 2, w + hw / 2, width - w - hw / 2, height - w - hw / 2)
                ink.outline(canvas, scratch, Palette.alpha(Palette.PLAYHEAD, (f * 110).toInt()), hw)
            }
        }
    }

    /** A new view (swap, contextual change): an amber rule draws once along the header, then fades. */
    private fun drawViewChange(c: Canvas, area: RectF) {
        val t = motion.progress(viewChangeStart, 360)
        if (t >= 1f) return
        val reach = minOf(1f, t / 0.55f)
        val grow = 1f - (1f - reach) * (1f - reach) * (1f - reach)
        val alpha = if (t < 0.55f) 255 else ((1f - (t - 0.55f) / 0.45f) * 255).toInt()
        val y = area.top + ink.unit * 2f
        ink.rect(c, area.left, y - ink.dp(2f), area.left + area.width() * grow, y, Palette.alpha(Palette.PLAYHEAD, alpha))
    }

    private fun drawHintBar(c: Canvas, r: RectF) {
        ink.rect(c, r, Palette.LANE)
        ink.line(c, r.left, r.top, r.right, r.top, Palette.RULE, ink.dp(1f))
        val size = ink.unit * 0.82f
        var x = r.left + ink.unit * 0.6f
        val msg = session.message
        val right = r.right - ink.unit * 0.6f
        if (!controllerFocused) x = ink.hint(c, "R3", "Control this display", x, r.top, r.height(), size)
        for (h in currentHints()) {
            if (x + ink.hintWidth(h.keys, h.text, size) > right - (if (msg != null) ink.unit * 10 else 0f)) break
            x = ink.hint(c, h.keys, h.text, x, r.top, r.height(), size)
        }
        if (msg !== seenMessage) {
            seenMessage = msg
            messageStart = if (msg != null) motion.start() else Motion.OFF
        }
        if (msg != null) {
            val avail = right - x
            if (avail > ink.unit * 4) {
                val text = ink.fit(msg, avail, size, true)
                // A new status message arrives on a brief amber chip that dissolves into the bar.
                val f = motion.fade(messageStart, 700)
                if (f > 0f) {
                    val pad = ink.unit * 0.4f
                    scratch.set(right - ink.labelWidth(text, size, true) - pad, r.top + r.height() * 0.14f, right + pad, r.bottom - r.height() * 0.14f)
                    ink.round(c, scratch, size * 0.3f, Palette.alpha(Palette.PLAYHEAD, (f * 64).toInt()))
                }
                ink.label(c, text, right, r.top, r.height(), Palette.PLAYHEAD, true, size, android.graphics.Paint.Align.RIGHT)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val p = painter()
                val handled = input { p.down(x, y) }
                activePainter = if (handled) p else null
                if (handled) parent?.requestDisallowInterceptTouchEvent(true)
                return handled
            }
            MotionEvent.ACTION_MOVE -> activePainter?.let { p -> input { p.move(x, y) } }
            MotionEvent.ACTION_UP -> endTouch(notify = true) { it.up(x, y) }
            MotionEvent.ACTION_CANCEL -> endTouch(notify = true) { it.cancel() }
        }
        return activePainter != null || event.actionMasked == MotionEvent.ACTION_UP
    }
}

/** Draws one view kind into its area and interprets touches on it. */
internal abstract class Painter(protected val v: TrackerView) {
    protected val ink: Ink get() = v.ink
    protected val ctl: ViewController get() = v.controller
    protected val st: ViewState get() = v.state
    protected val session: EditorSession get() = v.session
    protected val song get() = v.session.song
    protected val motion: Motion get() = v.motion

    abstract fun draw(c: Canvas, area: RectF)
    open fun down(x: Float, y: Float): Boolean = false
    open fun move(x: Float, y: Float) {}
    open fun up(x: Float, y: Float) {}
    open fun cancel() {}

    /** This view's own input changed the document from [before], with [selection] as it stood beforehand. */
    open fun edited(before: org.thortracker.model.Song, selection: org.thortracker.editor.Selection) {}

    /** A labelled header strip; returns its bottom. [glow] (a colour with alpha, 0 = none) tints it under the text. */
    protected fun header(c: Canvas, area: RectF, title: String, detail: String, reserveRight: Float = 0f, glow: Int = 0): Float {
        val h = ink.unit * 2f
        ink.rect(c, area.left, area.top, area.right, area.top + h, Palette.LANE)
        if (glow != 0) ink.rect(c, area.left, area.top, area.right, area.top + h, glow)
        val x = area.left + ink.unit * 0.7f
        ink.label(c, title, x, area.top, h, Palette.TEXT, true, ink.unit * 1.05f)
        val tw = ink.labelWidth(title, ink.unit * 1.05f, true)
        ink.label(c, ink.fit(detail, area.width() - tw - reserveRight - ink.unit * 2.5f), x + tw + ink.unit * 0.9f, area.top, h, Palette.DIM)
        return area.top + h
    }
}
