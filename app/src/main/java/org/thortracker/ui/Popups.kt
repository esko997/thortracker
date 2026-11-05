package org.thortracker.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One choice in an in-app popup. A null [action] dismisses the popup (back one level). */
class PopupItem(val label: String?, val tone: Tone = Tone.PLAIN, val action: (() -> Unit)? = null) {
    enum class Tone { PLAIN, PRIMARY, DANGER }

    companion object {
        /** The leaving row: "Back" when a parent popup is underneath, otherwise "Cancel". */
        fun dismiss() = PopupItem(null)
    }
}

/** An editable single-line value shown above the items; [value] tracks the field. */
class PopupText(initial: String, val maxLength: Int) {
    var value: String = initial
}

/**
 * Content of one modal popup. [capture], when set, receives the next physical key press instead of
 * popup navigation (controller remapping). [selected] counts the text field (when present) as entry 0.
 */
class PopupSpec(
    val title: String,
    val message: String? = null,
    val items: List<PopupItem>,
    val text: PopupText? = null,
    val capture: ((Int) -> Unit)? = null,
    selected: Int = 0,
) {
    internal var selected = selected
    internal val entries: Int get() = items.size + if (text != null) 1 else 0
    internal fun itemIndex(entry: Int) = entry - if (text != null) 1 else 0
}

/**
 * The app's modal popups: one stack shown inside one host root (never a Dialog, PopupWindow or other
 * system window). Choosing an item that opens another popup keeps the chooser underneath as the
 * "Back" target; choosing a final item closes the whole stack. Popups shown while others are open
 * (asynchronous errors) stack on top and reveal what they covered when dismissed.
 */
class Popups(private val layers: (Int) -> PopupLayer?, private val displays: () -> Collection<Int>) {
    private val stack = ArrayList<PopupSpec>()
    private var wasOpen = false

    /** Host index currently showing the popups. */
    var display = 0
        private set

    /** Called when the stack opens or closes completely. */
    var onOpenChanged: ((Boolean) -> Unit)? = null

    val isOpen: Boolean get() = stack.isNotEmpty()
    val top: PopupSpec? get() = stack.lastOrNull()

    /** True while the top popup's text field holds Android focus, so typing keys must reach it. */
    val typing: Boolean get() = isOpen && layers(display)?.fieldFocused == true

    fun show(spec: PopupSpec, display: Int) {
        if (stack.isEmpty()) this.display = display
        stack += spec
        render()
    }

    /** Leaves the top popup, revealing the one underneath (or closing). */
    fun back() {
        if (stack.isEmpty()) return
        stack.removeAt(stack.lastIndex)
        render()
    }

    fun closeAll() {
        if (stack.isEmpty()) return
        stack.clear()
        render()
    }

    /** Cancels any pending button capture (pause, display loss); returns true if one was waiting. */
    fun dropCaptures(): Boolean {
        if (!stack.removeAll { it.capture != null }) return false
        render()
        return true
    }

    /** The host showing the popups went away: continue on the primary display. */
    fun rehome() {
        if (display != 0 && display !in displays()) { display = 0; render() }
    }

    /** Runs entry [entry] of the top popup, as tapped or pressed with A. */
    fun choose(entry: Int) {
        val spec = top ?: return
        spec.selected = entry.coerceIn(0, spec.entries - 1)
        val index = spec.itemIndex(spec.selected)
        if (index < 0) { layers(display)?.focusField(); render(); return }
        val action = spec.items[index].action ?: return back()
        val depth = stack.size
        action()
        // A final choice closes everything; one that opened a popup keeps its menu as the Back target.
        if (stack.size <= depth && stack.lastOrNull() === spec) stack.clear()
        render()
    }

    /** Controller and keyboard input for the top popup (logical, remapped [code]). */
    fun handleKey(code: Int, down: Boolean, repeat: Int): Boolean {
        val spec = top ?: return false
        val layer = layers(display)
        val typing = layer?.fieldFocused == true
        val fresh = down && repeat == 0
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) move(spec, if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (typing) return false
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP -> if (down) layer?.page(-1)
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN -> if (down) layer?.page(1)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> if (fresh) choose(spec.selected)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (fresh) {
                // Enter in the text field submits with the first (primary) item.
                if (spec.text != null && spec.selected == 0) choose(1) else choose(spec.selected)
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (fresh) back()
            KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_MENU -> if (fresh) closeAll()
            else -> if (typing || !(KeyEvent.isGamepadButton(code) || code == KeyEvent.KEYCODE_SPACE)) return false
        }
        return true
    }

    private fun move(spec: PopupSpec, delta: Int) {
        val next = (spec.selected + delta).coerceIn(0, spec.entries - 1)
        val layer = layers(display)
        if (next == spec.selected) { layer?.page(delta); return }
        spec.selected = next
        layer?.select(next)
    }

    private fun render() {
        val open = stack.isNotEmpty()
        var target = layers(display)
        if (open && target == null) { display = 0; target = layers(0) }
        for (index in displays()) layers(index)?.takeIf { it !== target || !open }?.hide()
        if (open) target?.show(stack.last(), stack.size > 1, this)
        if (open != wasOpen) { wasOpen = open; onOpenChanged?.invoke(open) }
    }
}

/**
 * Full-size overlay inside one host root: a scrim and a centred panel drawn in the tracker's tokens.
 * Rows are ordinary non-focusable Buttons (accessible by text) whose selection is the popup's logical
 * cursor; Android view focus stays on the tracker except while the text field is being typed into.
 */
@SuppressLint("ViewConstructor")
class PopupLayer(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var shown: PopupSpec? = null
    private var owner: Popups? = null
    private val rows = ArrayList<View>()
    private var field: EditText? = null
    private var available = 0
    private val scroll = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cap = (available * 0.86f - dp(96)).toInt().coerceAtLeast(dp(96))
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                if (mode == MeasureSpec.UNSPECIFIED) cap else minOf(cap, size), MeasureSpec.AT_MOST))
        }
    }.apply { isVerticalScrollBarEnabled = true; isFocusable = false; overScrollMode = OVER_SCROLL_NEVER }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val title = TextView(context).apply {
        typeface = BOLD; setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); setTextColor(Palette.TEXT)
        setPadding(dp(18), dp(12), dp(18), dp(11)); maxLines = 2
        background = GradientDrawable().apply {
            setColor(Palette.LANE)
            cornerRadii = floatArrayOf(dp(6).toFloat(), dp(6).toFloat(), dp(6).toFloat(), dp(6).toFloat(), 0f, 0f, 0f, 0f)
        }
    }
    private val hints = KeyHints(context)
    private val panel = object : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = MeasureSpec.getSize(widthMeasureSpec)
            val width = minOf(dp(520), available - dp(32)).coerceAtLeast(dp(200))
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true // taps on the panel's own padding never reach the scrim
        background = GradientDrawable().apply {
            setColor(Palette.BG); cornerRadius = dp(6).toFloat(); setStroke(dp(1), Palette.RULE)
        }
        setPadding(dp(1), dp(1), dp(1), dp(1))
        elevation = dp(12).toFloat()
        addView(title, LinearLayout.LayoutParams(-1, -2))
        addView(View(context).apply { setBackgroundColor(Palette.RULE) }, LinearLayout.LayoutParams(-1, dp(1)))
        scroll.addView(body, LayoutParams(-1, -2))
        addView(scroll, LinearLayout.LayoutParams(-1, -2))
        addView(hints, LinearLayout.LayoutParams(-1, dp(30)))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        available = MeasureSpec.getSize(heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    init {
        visibility = GONE
        setBackgroundColor(Palette.alpha(Palette.BG, 0xB8))
        isClickable = true
        isFocusable = false
        setOnClickListener { owner?.back() } // tapping the scrim leaves the top popup
        addView(panel, LayoutParams(-2, -2, Gravity.CENTER))
    }

    val fieldFocused: Boolean get() = this.field?.isFocused == true

    internal fun show(spec: PopupSpec, hasParent: Boolean, popups: Popups) {
        owner = popups
        val appearing = visibility != VISIBLE
        if (spec !== shown) build(spec, hasParent)
        visibility = VISIBLE
        select(spec.selected)
        if (appearing) {
            panel.alpha = 0f; panel.translationY = -dp(10).toFloat()
            panel.animate().alpha(1f).translationY(0f).setDuration(140).start()
        }
    }

    internal fun hide() {
        if (visibility == GONE && shown == null) return
        field?.let { f ->
            context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(f.windowToken, 0)
            f.clearFocus()
        }
        panel.animate().cancel()
        visibility = GONE
        shown = null
        owner = null
        field = null
        rows.clear()
        body.removeAllViews()
    }

    private fun build(spec: PopupSpec, hasParent: Boolean) {
        field?.let { context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(it.windowToken, 0) }
        shown = spec
        rows.clear()
        field = null
        body.removeAllViews()
        title.text = spec.title
        spec.message?.let { text ->
            body.addView(TextView(context).apply {
                this.text = text; typeface = REGULAR; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Palette.TEXT); setLineSpacing(0f, 1.25f)
                setPadding(dp(18), dp(14), dp(18), dp(10))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        spec.text?.let { value ->
            val edit = EditText(context).apply {
                setText(value.value); setSingleLine(); setSelectAllOnFocus(true)
                filters = arrayOf<InputFilter>(InputFilter.LengthFilter(value.maxLength))
                imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                typeface = REGULAR; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(Palette.TEXT); setHintTextColor(Palette.DIM)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) { value.value = s?.toString().orEmpty() }
                })
                setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_DONE) { owner?.choose(1); true } else false
                }
                setOnFocusChangeListener { _, focused ->
                    shown?.let { s -> if (focused) s.selected = 0; select(s.selected) }
                }
            }
            field = edit
            val wrap = FrameLayout(context).apply { setPadding(dp(16), dp(12), dp(16), dp(8)) }
            wrap.addView(edit, LayoutParams(-1, -2))
            rows += edit
            body.addView(wrap, LinearLayout.LayoutParams(-1, -2))
        }
        spec.items.forEachIndexed { index, item ->
            val entry = index + if (spec.text != null) 1 else 0
            val row = Button(context).apply {
                text = item.label ?: if (hasParent) "Back" else "Cancel"
                isAllCaps = false; isFocusable = false; stateListAnimator = null
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                typeface = if (item.tone == PopupItem.Tone.PLAIN) REGULAR else BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                minHeight = dp(46); minimumHeight = dp(46)
                setPadding(dp(20), 0, dp(18), 0)
                background = RowBackground(density)
                setOnClickListener { owner?.choose(entry) }
            }
            rows += row
            body.addView(row, LinearLayout.LayoutParams(-1, dp(46)))
        }
        body.addView(View(context), LinearLayout.LayoutParams(-1, dp(6)))
        scroll.scrollTo(0, 0)
    }

    /** Moves the visible cursor to [entry] and scrolls it into view. */
    internal fun select(entry: Int) {
        val spec = shown ?: return
        rows.forEachIndexed { index, view ->
            val on = index == entry
            if (view is EditText) {
                view.background = GradientDrawable().apply {
                    setColor(Palette.LANE); cornerRadius = dp(4).toFloat()
                    setStroke(dp(if (on || view.isFocused) 2 else 1), if (on) Palette.PLAYHEAD else Palette.RULE)
                }
            } else {
                val tone = spec.items[spec.itemIndex(index)].tone
                (view.background as? RowBackground)?.let { it.selected = on; it.invalidateSelf() }
                (view as TextView).setTextColor(when {
                    tone == PopupItem.Tone.DANGER -> Palette.WRITE
                    on -> Palette.TEXT
                    else -> Palette.mix(Palette.TEXT, Palette.DIM, 0.35f)
                })
            }
        }
        val onField = spec.text != null && entry == 0
        val leave = if (spec.items.any { it.action == null }) "Back" else "Close"
        hints.set(buildList {
            add("↑↓" to "Move")
            add("A" to if (onField) (if (fieldFocused) "Keyboard" else "Type") else "Choose")
            if (onField && fieldFocused) add("←→" to "Cursor")
            add("B" to leave)
        })
        reveal(entry)
    }

    private fun reveal(entry: Int) {
        val row = rows.getOrNull(entry) ?: return
        scroll.post {
            if (entry == 0) { scroll.smoothScrollTo(0, 0); return@post }
            var top = row.top
            var parent = row.parent as? View
            while (parent != null && parent !== body) { top += parent.top; parent = parent.parent as? View }
            val bottom = top + row.height
            val pad = dp(8)
            when {
                top < scroll.scrollY -> scroll.smoothScrollTo(0, top - pad)
                bottom > scroll.scrollY + scroll.height -> scroll.smoothScrollTo(0, bottom - scroll.height + pad)
            }
        }
    }

    internal fun page(direction: Int) {
        scroll.smoothScrollBy(0, direction * maxOf(dp(48), scroll.height * 2 / 3))
    }

    /** Gives the text field Android focus and asks the system keyboard to appear. */
    internal fun focusField() {
        val f = field ?: return
        f.requestFocus()
        f.post { context.getSystemService(InputMethodManager::class.java)?.showSoftInput(f, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun dp(v: Int) = (v * density).toInt()

    private class RowBackground(private val density: Float) : Drawable() {
        var selected = false
        private val paint = android.graphics.Paint()
        private var pressed = false

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (selected || pressed) {
                paint.color = if (selected) Palette.CURSOR_FILL else Palette.BAR
                canvas.drawRect(b, paint)
            }
            if (selected) {
                paint.color = Palette.PLAYHEAD
                canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.left + 3 * density, b.bottom.toFloat(), paint)
            }
        }

        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean {
            val now = state.contains(android.R.attr.state_pressed)
            if (now == pressed) return false
            pressed = now
            return true
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        internal val REGULAR: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        internal val BOLD: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
}

/** A strip of controller key badges, drawn like the tracker's own hint bar. */
class KeyHints(context: Context) : View(context) {
    private val ink = Ink(resources.displayMetrics.density)
    private var hints: List<Pair<String, String>> = emptyList()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun set(next: List<Pair<String, String>>) {
        if (next == hints) return
        hints = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Palette.LANE)
        val size = ink.dp(12f) * 0.82f
        var x = ink.dp(14f)
        for ((keys, meaning) in hints) {
            if (x + ink.hintWidth(keys, meaning, size) > width) break
            x = ink.hint(canvas, keys, meaning, x, 0f, height.toFloat(), size)
        }
    }
}

/** Marks [views] as hidden from accessibility while a modal covers them (restored when [hidden] is false). */
internal fun ViewGroup.hideFromAccessibility(hidden: Boolean, vararg except: View) {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (child in except) continue
        child.importantForAccessibility = if (hidden) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }
}
