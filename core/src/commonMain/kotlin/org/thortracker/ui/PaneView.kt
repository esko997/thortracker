package org.thortracker.ui

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
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem

/**
 * One pane: renders the view kind in [state] from the shared [session] into whatever rectangle the frontend gives
 * it, and turns controller actions and touch into session commands. Touch never takes controller focus: the host
 * owns [controllerFocused] and only it routes controller events here. The frontend supplies the drawing [surface],
 * the display [density], a clock and [invalidate] (draw again); everything else is shared.
 */
class PaneView(
    val session: EditorSession,
    state: ViewState,
    density: Float,
    val surface: Surface,
    clock: () -> Long,
    private val invalidate: () -> Unit,
) {
    val controller = ViewController(session, state)

    var state: ViewState = state
        set(value) {
            if (value === field) return
            cancelInteraction()
            field = value
            controller.state = value
            seenSelection = session.selectionVersion
            seenKind = value.kind
            touchScrolled = false
            geometryReady = false
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

    /** Chord modifiers held on the controller; set by the host only for the controller-focused pane. */
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

    /** Battery level 0..100 and whether it is charging; null hides the readout (no battery reported). */
    var battery: Battery? = null
        set(value) {
            if (field == value) return
            field = value
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

    val ink = Ink(density, surface)
    var palette: Palette
        get() = ink.palette
        set(value) {
            if (ink.palette === value) return
            ink.palette = value
            invalidate()
        }
    internal val strip = DensityStrip(ink)
    val motion = Motion(clock, invalidate)
    private var viewChangeStart = Motion.OFF
    private var focusStart = Motion.OFF
    private var seenMessage: String? = null
    private var messageStart = Motion.OFF
    private val scratch = Rect()
    private val painters = HashMap<ViewKind, Painter>()
    private var activePainter: Painter? = null
    private var handling = 0
    private var seenSelection = session.selectionVersion
    private var seenKind = state.kind
    private var seenZoom = state.zoom
    private var geometryReady = false

    /** Geometry changes end the old gesture before any coordinates can be interpreted at the new size. */
    private fun syncZoom() {
        if (seenZoom == state.zoom) return
        seenZoom = state.zoom
        cancelInteraction()
        touchScrolled = false
        geometryReady = false
    }

    /** Set when the user scrolled by touch; cleared on the next controller move so the cursor comes back into view. */
    internal var touchScrolled = false

    private var checkedSong: Song? = null
    private var checkedProblems: List<String> = emptyList()
    private var warnedSong: Song? = null
    private var checkedWarnings: List<String> = emptyList()

    /** Export problems of the current song, recomputed only when the document changes. */
    internal fun problems(): List<String> {
        val s = session.song
        if (s !== checkedSong) {
            checkedProblems = s.system.errors(s)
            checkedSong = s
        }
        return checkedProblems
    }

    /** Legal but probably unintended details of the current song. */
    internal fun warnings(): List<String> {
        val s = session.song
        if (s !== warnedSong) {
            checkedWarnings = s.system.warnings(s)
            warnedSong = s
        }
        return checkedWarnings
    }

    init { controller.normalise() }

    /** Current controller hints (key label, meaning); while chord modifiers are held, the chords of exactly that layer. */
    val hints: List<Pair<String, String>> get() = currentHints().map { it.keys to it.text }

    fun currentHints(): List<Hint> {
        val mods = heldModifiers
        if (mods.isEmpty()) return controller.hints()
        val chords = keymap.chords(mods, state.kind)
            .filter { (c, _) -> c.shows == null || c.shows in session.song.system.views }
            .sortedBy { (c, _) -> if (c.host) 0 else 1 }
            .map { (c, b) -> Hint(Keymap.label(b), c.short) }
        if (chords.isEmpty() && mods.size > 1) return listOf(Hint(Keymap.modifiersLabel(mods), "No chord: release one"))
        return chords
    }

    /** Re-reads the session: applies selection following and repaints. Call from the session callbacks. */
    fun refresh() {
        syncZoom()
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
            Follow.applyLock(state, session.song)
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

    /** True while the controller drives this pane's Pattern grid (no pattern panel open): where stick jumps apply. */
    val patternFocused: Boolean get() = controllerFocused && state.kind == ViewKind.PATTERN && !state.scalePanel

    /** The right-stick double flick: the Pattern cursor to its pattern's first or last row ([ViewController.jumpPatternEdge]). */
    fun jumpPatternEdge(bottom: Boolean): Boolean = input {
        touchScrolled = false
        controller.jumpPatternEdge(bottom)
    }

    /** Horizontal double flick: first/last channel's note, keeping the row. */
    fun jumpPatternChannelEdge(last: Boolean): Boolean = input {
        touchScrolled = false
        controller.jumpPatternChannelEdge(last)
    }

    /** Runs view-originated input so the selection it publishes is not followed back onto this pane. */
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
     * True while this pane is publishing the current shared selection, when it published the latest
     * one, or while a touch drag or held A/B/Y is in progress on it. Hosts should not apply contextual
     * view replacement to such a pane; [onTouchEnded] says when a drag is over.
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

    /** The system whose own views' painters [painters] holds; another song system starts them afresh. */
    private var paintersSystem: SoundSystem? = null

    private fun painter(): Painter {
        val system = session.song.system
        if (system !== paintersSystem) { painters.clear(); paintersSystem = system }
        return painters.getOrPut(state.kind) {
            system.view(state.kind)?.painter(this) ?: when (state.kind) {
                ViewKind.PATTERN -> PatternPainter(this)
                ViewKind.SONG -> SongPainter(this)
                ViewKind.KEYBOARD -> KeyboardPainter(this)
                ViewKind.SKETCH -> SketchPainter(this)
                ViewKind.HELP -> HelpPainter(this)
                // normalise() never leaves a pane on a view its song's system lacks.
                else -> PatternPainter(this)
            }
        }
    }

    /** Draws the whole pane (view, hint bar, focus border) into [surface] at [width] x [height] pixels. */
    fun draw(width: Int, height: Int) {
        val c = surface
        motion.beginFrame()
        syncZoom()
        ink.layout(width, height, state.zoom)
        c.fillRect(0f, 0f, width.toFloat(), height.toFloat(), ink.palette.BG)
        val hintH = ink.unit * 1.9f
        val area = Rect(0f, 0f, width.toFloat(), height - hintH)
        controller.normalise()
        painter().draw(c, area)
        geometryReady = true
        drawViewChange(c, area)
        drawHintBar(c, Rect(0f, height - hintH, width.toFloat(), height.toFloat()))
        if (controllerFocused) {
            val w = ink.dp(3f)
            scratch.set(w / 2, w / 2, width - w / 2, height - w / 2)
            ink.outline(c, scratch, ink.palette.FOCUS_ACCENT, w)
            // Taking control: a soft inner rim that settles into the steady border.
            val f = motion.fade(focusStart, 320)
            if (f > 0f) {
                val hw = ink.dp(7f) * f
                scratch.set(w + hw / 2, w + hw / 2, width - w - hw / 2, height - w - hw / 2)
                ink.outline(c, scratch, Palette.alpha(ink.palette.FOCUS_ACCENT, (f * 110).toInt()), hw)
            }
        }
    }

    /** A new view (swap, contextual change): a focus-accent rule draws once along the header, then fades. */
    private fun drawViewChange(c: Surface, area: Rect) {
        val t = motion.progress(viewChangeStart, 360)
        if (t >= 1f) return
        val reach = minOf(1f, t / 0.55f)
        val grow = 1f - (1f - reach) * (1f - reach) * (1f - reach)
        val alpha = if (t < 0.55f) 255 else ((1f - (t - 0.55f) / 0.45f) * 255).toInt()
        val y = area.top + ink.unit * 2f
        ink.rect(c, area.left, y - ink.dp(2f), area.left + area.width() * grow, y, Palette.alpha(ink.palette.FOCUS_ACCENT, alpha))
    }

    private fun drawHintBar(c: Surface, r: Rect) {
        ink.rect(c, r, ink.palette.LANE)
        ink.line(c, r.left, r.top, r.right, r.top, ink.palette.RULE, ink.dp(1f))
        val size = ink.unit * 0.82f
        var x = r.left + ink.unit * 0.6f
        val msg = session.message
        val right = drawBattery(c, r, r.right - ink.unit * 0.6f, size) - ink.unit * 0.6f
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
                    ink.round(c, scratch, size * 0.3f, Palette.alpha(ink.palette.PLAYHEAD, (f * 64).toInt()))
                }
                ink.label(c, text, right, r.top, r.height(), ink.palette.PLAYHEAD, true, size, Align.RIGHT)
            }
        }
    }

    /**
     * A small battery at the bar's right end: outline, fill to the level, and the percentage. Cyan while
     * charging, red at 15% or below otherwise. Returns where the rest of the bar must end.
     */
    private fun drawBattery(c: Surface, r: Rect, right: Float, size: Float): Float {
        val b = battery ?: return right
        val color = when {
            b.charging -> ink.palette.LISTEN
            b.level <= 15 -> ink.palette.ERROR
            else -> ink.palette.DIM
        }
        val text = "${b.level}%"
        val textW = ink.labelWidth(text, size, false)
        ink.label(c, text, right, r.top, r.height(), color, false, size, Align.RIGHT)
        val h = r.height() * 0.36f
        val w = h * 1.9f
        val nub = ink.dp(2f)
        val bodyRight = right - textW - ink.unit * 0.3f - nub
        val top = r.centerY() - h / 2f
        scratch.set(bodyRight - w, top, bodyRight, top + h)
        ink.outline(c, scratch, color, ink.dp(1.2f), ink.dp(2f))
        ink.rect(c, bodyRight, top + h * 0.3f, bodyRight + nub, top + h * 0.7f, color)
        val inset = ink.dp(2.2f)
        val fill = (w - inset * 2) * b.level.coerceIn(0, 100) / 100f
        if (fill > 0f) ink.rect(c, bodyRight - w + inset, top + inset, bodyRight - w + inset + fill, top + h - inset, color)
        return bodyRight - w
    }

    data class Battery(val level: Int, val charging: Boolean)

    /** Touch start at pane coordinates; true when the pane takes the gesture. */
    fun touchDown(x: Float, y: Float): Boolean {
        syncZoom()
        if (!geometryReady) return false
        val p = painter()
        val handled = input { p.down(x, y) }
        activePainter = if (handled) p else null
        return handled
    }

    fun touchMove(x: Float, y: Float) { activePainter?.let { p -> input { p.move(x, y) } } }

    fun touchUp(x: Float, y: Float) = endTouch(notify = true) { it.up(x, y) }

    fun touchCancel() = endTouch(notify = true) { it.cancel() }

    /** Whether a touch gesture is in progress (the frontend keeps delivering it). */
    val touchActive: Boolean get() = activePainter != null
}

/** Draws one view kind into its area and interprets touches on it. */
internal abstract class Painter(protected val v: PaneView) {
    protected val ink: Ink get() = v.ink
    protected val ctl: ViewController get() = v.controller
    protected val st: ViewState get() = v.state
    protected val session: EditorSession get() = v.session
    protected val song get() = v.session.song
    protected val motion: Motion get() = v.motion

    abstract fun draw(c: Surface, area: Rect)
    open fun down(x: Float, y: Float): Boolean = false
    open fun move(x: Float, y: Float) {}
    open fun up(x: Float, y: Float) {}
    open fun cancel() {}

    /** This view's own input changed the document from [before], with [selection] as it stood beforehand. */
    open fun edited(before: Song, selection: org.thortracker.editor.Selection) {}

    /** A labelled header strip; returns its bottom. [glow] (a colour with alpha, 0 = none) tints it under the text. */
    protected fun header(c: Surface, area: Rect, title: String, detail: String, reserveRight: Float = 0f, glow: Int = 0): Float {
        val h = ink.unit * 2f
        ink.rect(c, area.left, area.top, area.right, area.top + h, ink.palette.LANE)
        if (glow != 0) ink.rect(c, area.left, area.top, area.right, area.top + h, glow)
        val x = area.left + ink.unit * 0.7f
        ink.label(c, title, x, area.top, h, ink.palette.TEXT, true, ink.unit * 1.05f)
        val tw = ink.labelWidth(title, ink.unit * 1.05f, true)
        ink.label(c, ink.fit(detail, area.width() - tw - reserveRight - ink.unit * 2.5f), x + tw + ink.unit * 0.9f, area.top, h, ink.palette.DIM)
        return area.top + h
    }
}
