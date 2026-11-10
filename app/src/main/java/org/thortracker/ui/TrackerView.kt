package org.thortracker.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewState

/**
 * An Android display's pane: a [View] that hosts the shared [PaneView] (rendering, controller and touch logic),
 * drawing it through a [CanvasSurface]. The host owns controller focus and routes controller events here.
 */
@SuppressLint("ViewConstructor")
class TrackerView(context: Context, session: EditorSession, state: ViewState) : View(context) {
    private val surface = CanvasSurface()
    val pane = PaneView(session, state, resources.displayMetrics.density, surface, AnimationUtils::currentAnimationTimeMillis, ::postInvalidateOnAnimation)

    val session: EditorSession get() = pane.session
    val controller get() = pane.controller
    var state: ViewState by pane::state
    var controllerFocused: Boolean by pane::controllerFocused
    var heldModifiers: Set<Int> by pane::heldModifiers
    var keymap by pane::keymap
    var battery: PaneView.Battery? by pane::battery
    var playback by pane::playback
    var onTouchEnded: (() -> Unit)? by pane::onTouchEnded
    val ownsSelection: Boolean get() = pane.ownsSelection
    val hints: List<Pair<String, String>> get() = pane.hints

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
    }

    fun refresh() = pane.refresh()
    fun cancelInteraction() = pane.cancelInteraction()
    fun controllerAction(action: org.thortracker.editor.Action, down: Boolean, repeat: Int) = pane.controllerAction(action, down, repeat)
    fun press(action: org.thortracker.editor.Action) = pane.press(action)
    fun release(action: org.thortracker.editor.Action) = pane.release(action)
    val patternFocused: Boolean get() = pane.patternFocused
    fun jumpPatternEdge(bottom: Boolean) = pane.jumpPatternEdge(bottom)
    fun jumpPatternChannelEdge(last: Boolean) = pane.jumpPatternChannelEdge(last)

    /** Re-reads the system animator duration scale (0 = Remove animations shows end states at once). */
    private fun refreshMotionScale() {
        pane.motion.scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        pane.ink.layout(w, h)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshMotionScale()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // The animator scale may have been changed in system settings while the app was away.
        if (hasWindowFocus) refreshMotionScale()
    }

    override fun onDraw(canvas: Canvas) {
        surface.canvas = canvas
        try { pane.draw(width, height) } finally { surface.canvas = null }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val handled = pane.touchDown(x, y)
                if (handled) parent?.requestDisallowInterceptTouchEvent(true)
                return handled
            }
            MotionEvent.ACTION_MOVE -> pane.touchMove(x, y)
            MotionEvent.ACTION_UP -> pane.touchUp(x, y)
            MotionEvent.ACTION_CANCEL -> pane.touchCancel()
        }
        return pane.touchActive || event.actionMasked == MotionEvent.ACTION_UP
    }
}
