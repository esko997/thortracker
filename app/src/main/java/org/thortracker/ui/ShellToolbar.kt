package org.thortracker.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.thortracker.editor.Action
import org.thortracker.input.Control
import org.thortracker.input.Keymap

/**
 * The host's auto-hiding shell toolbar, laid over the top of the tracker so revealing it never moves
 * the grid. Hidden by default. A touch reveal (edge handle) hides itself after [TOUCH_TIMEOUT_MS] of
 * no touch on it; a controller reveal ([focusByController]) takes the logical controller cursor and
 * never times out, leaving only on Clear/Back, the toolbar control or [hide]. Buttons stay ordinary,
 * non-focusable [Button]s so Android focus remains on the tracker and they remain accessible by text.
 */
@SuppressLint("ViewConstructor")
class ShellToolbar(context: Context, private val holdOpen: () -> Boolean) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val bar = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(Palette.LANE) }
    }
    private val hints = KeyHints(context).apply { visibility = GONE }
    private val buttons = ArrayList<Button>()
    private val wide = HashSet<Button>()
    private val looks = HashMap<Button, Int>()
    private var cursor = 0
    private var hiding = false
    private val timeout = Runnable { if (holdOpen() || controllerFocused) touched() else hide() }

    val heading = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(Palette.PLAYHEAD); maxLines = 1
        typeface = PopupLayer.BOLD; ellipsize = TextUtils.TruncateAt.END
    }
    val status = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextColor(Palette.DIM); maxLines = 1
        typeface = PopupLayer.REGULAR; ellipsize = TextUtils.TruncateAt.END
    }

    /** True while the toolbar is on screen (including its hide animation). */
    val revealed: Boolean get() = visibility == VISIBLE && !hiding

    /** True while the toolbar holds the logical controller cursor. */
    var controllerFocused = false
        private set

    /** Called after the toolbar hides or gives the controller back to the editor. */
    var onLeave: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        visibility = GONE
        isClickable = true // touches on the bar's gaps never fall through to the grid
        val title = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
            addView(heading, LayoutParams(-1, -2))
            addView(status, LayoutParams(-1, -2))
        }
        bar.addView(title, LayoutParams(0, -1, 1f))
        addView(bar, LayoutParams(-1, dp(48)))
        addView(View(context).apply { setBackgroundColor(Palette.RULE) }, LayoutParams(-1, dp(1)))
        addView(hints, LayoutParams(-1, dp(26)))
        setKeys(Keymap.DEFAULT)
        addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val visibility = if (right - left >= dp(720)) VISIBLE else GONE
            wide.forEach { if (it.visibility != visibility) it.visibility = visibility }
            if (controllerFocused) post { moveCursor(0) }
        }
    }

    /** Names the current [keymap]'s buttons in the controller hint strip. */
    fun setKeys(keymap: Keymap) {
        hints.set(listOf(
            keymap.pair(Action.LEFT, Action.RIGHT) to "Choose",
            keymap.label(Action.EDIT) to "Press",
            keymap.label(Action.CLEAR) to "Back to editor",
            keymap.label(Control.TOOLBAR) to "Hide",
        ))
    }

    /** Adds a button; [wideOnly] buttons hide on narrow displays (their actions remain in Menu). */
    fun add(label: String, wideOnly: Boolean = false, action: () -> Unit): Button {
        val button = Button(context).apply {
            text = label; isAllCaps = false; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = PopupLayer.REGULAR
            isFocusable = false; stateListAnimator = null
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LayoutParams(-2, dp(40)).apply { setMargins(dp(2), 0, dp(2), 0) }
            setOnClickListener { touched(); action() }
        }
        buttons += button
        if (wideOnly) wide += button
        bar.addView(button)
        paint(button, false)
        return button
    }

    /** Reveals for touch use; hides again after a quiet period. */
    fun revealByTouch() {
        show()
        touched()
    }

    /** Reveals and takes the logical controller cursor; never times out. */
    fun focusByController() {
        removeCallbacks(timeout)
        controllerFocused = true
        hints.visibility = VISIBLE
        show()
        moveCursor(0)
    }

    fun hide() {
        removeCallbacks(timeout)
        val wasFocused = controllerFocused
        controllerFocused = false
        hints.visibility = GONE
        repaint()
        if (visibility != VISIBLE || hiding) { if (wasFocused) onLeave?.invoke(); return }
        hiding = true
        animate().cancel()
        animate().translationY(-height.toFloat()).alpha(0f).setDuration(140).withEndAction {
            hiding = false
            visibility = GONE
        }.start()
        onLeave?.invoke()
    }

    /** Controller input while [controllerFocused]. Returns false for keys the system should handle. */
    fun handleKey(code: Int, down: Boolean, repeat: Int): Boolean {
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (down) moveCursor(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                if (down && repeat == 0) visibleButtons().getOrNull(cursor)?.takeIf { it.isEnabled }?.performClick()
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE ->
                if (down && repeat == 0) hide()
            else -> return KeyEvent.isGamepadButton(code) ||
                code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
        }
        return true
    }

    /** Any touch on the toolbar restarts the touch-reveal timeout. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) touched()
        return super.dispatchTouchEvent(event)
    }

    /** Re-applies button styling after labels or enablement change. */
    fun repaint() {
        var shown = 0
        for (button in buttons) {
            val visible = button.visibility == VISIBLE
            paint(button, controllerFocused && visible && shown == cursor)
            if (visible) shown++
        }
    }

    private fun show() {
        animate().cancel()
        hiding = false
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            alpha = 0f
            translationY = -dp(49).toFloat()
        }
        animate().translationY(0f).alpha(1f).setDuration(160).start()
        repaint()
    }

    private fun touched() {
        removeCallbacks(timeout)
        if (!controllerFocused && visibility == VISIBLE) postDelayed(timeout, TOUCH_TIMEOUT_MS)
    }

    private fun moveCursor(delta: Int) {
        val visible = visibleButtons()
        if (visible.isEmpty()) return
        cursor = (cursor + delta).coerceIn(0, visible.size - 1)
        repaint()
    }

    private fun visibleButtons(): List<Button> = buttons.filter { it.visibility == VISIBLE }

    private fun paint(button: Button, focused: Boolean) {
        val look = (if (focused) 1 else 0) or (if (button.isEnabled) 2 else 0)
        if (looks[button] == look) return
        looks[button] = look
        button.background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(if (focused) Palette.CURSOR_FILL else Palette.BAR)
            if (focused) setStroke(dp(2), Palette.PLAYHEAD)
        }
        button.setTextColor(when {
            !button.isEnabled -> Palette.FAINT
            focused -> Palette.PLAYHEAD
            else -> Palette.TEXT
        })
    }

    private fun dp(v: Int) = (v * density).toInt()

    companion object {
        const val TOUCH_TIMEOUT_MS = 6000L
    }
}

/**
 * A small persistent pull tab at the top centre of a host. Tapping it (or pulling down on it) reveals
 * the toolbar. Its touch target stays inside the editor's header strip, never over grid cells.
 */
class ToolbarHandle(context: Context, private val reveal: () -> Unit) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pill = RectF()
    private var downY = 0f
    private var pulled = false

    init {
        contentDescription = "Show toolbar"
        isFocusable = false
        isClickable = true
        setOnClickListener { if (!pulled) reveal() }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = event.y; pulled = false }
            MotionEvent.ACTION_MOVE -> if (!pulled && event.y - downY > 6 * density) {
                pulled = true
                reveal()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val w = 30 * density
        val h = 4 * density
        val top = 3 * density
        pill.set((width - w) / 2, top, (width + w) / 2, top + h)
        paint.color = Palette.alpha(Palette.DIM, if (isPressed) 0xFF else 0x99)
        canvas.drawRoundRect(pill, h / 2, h / 2, paint)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}
