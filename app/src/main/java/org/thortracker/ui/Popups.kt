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

/**
 * Full-size overlay inside one host root: a scrim and a centred panel drawn in the tracker's tokens.
 * Rows are ordinary non-focusable Buttons (accessible by text) whose selection is the popup's logical
 * cursor; Android view focus stays on the tracker except while the text field is being typed into.
 */
@SuppressLint("ViewConstructor")
class PopupLayer(context: Context) : FrameLayout(context), PopupView {
    var palette: Palette = Palette.SLATE
        set(value) {
            if (field === value) return
            field = value
            title.setTextColor(value.TEXT)
            (title.background as GradientDrawable).setColor(value.LANE)
            (panel.background as GradientDrawable).apply { setColor(value.BG); setStroke(dp(1), value.RULE) }
            rule.setBackgroundColor(value.RULE)
            setBackgroundColor(Palette.alpha(value.BG, 0xB8))
            hints.palette = value
            recolourBody(body)
            shown?.let { paintSelection(it.selected, scrollToEntry = false) }
            invalidate()
        }

    /** Recolour in place: an open dialog keeps its text, keyboard focus, selection and scroll position. */
    private fun recolourBody(view: View) {
        if (view is TextView) view.setTextColor(palette.TEXT)
        if (view is EditText) styleField(view)
        (view.background as? GradientDrawable)?.apply { setColor(palette.LANE); setStroke(dp(1), palette.RULE) }
        view.background?.invalidateSelf()
        if (view is ViewGroup) for (i in 0 until view.childCount) recolourBody(view.getChildAt(i))
    }

    private fun styleField(view: EditText) {
        view.setHintTextColor(palette.DIM)
        view.highlightColor = if (palette === Palette.SLATE) nativeFieldHighlight else Palette.alpha(palette.FOCUS_ACCENT, 80)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            view.textCursorDrawable?.mutate()?.setTint(palette.FOCUS_ACCENT)
            view.textSelectHandle?.mutate()?.setTint(palette.FOCUS_ACCENT)
            view.textSelectHandleLeft?.mutate()?.setTint(palette.FOCUS_ACCENT)
            view.textSelectHandleRight?.mutate()?.setTint(palette.FOCUS_ACCENT)
        }
    }

    private val density = resources.displayMetrics.density
    private var shown: PopupSpec? = null
    private var owner: Popups? = null
    private val rows = ArrayList<View>()
    private var field: EditText? = null
    private var nativeFieldHighlight = 0
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
        typeface = BOLD; setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); setTextColor(palette.TEXT)
        setPadding(dp(18), dp(12), dp(18), dp(11)); maxLines = 2
        background = GradientDrawable().apply {
            setColor(palette.LANE)
            cornerRadii = floatArrayOf(dp(6).toFloat(), dp(6).toFloat(), dp(6).toFloat(), dp(6).toFloat(), 0f, 0f, 0f, 0f)
        }
    }
    private val hints = KeyHints(context)
    private val rule = View(context).apply { setBackgroundColor(palette.RULE) }
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
            setColor(palette.BG); cornerRadius = dp(6).toFloat(); setStroke(dp(1), palette.RULE)
        }
        setPadding(dp(1), dp(1), dp(1), dp(1))
        elevation = dp(12).toFloat()
        addView(title, LinearLayout.LayoutParams(-1, -2))
        addView(rule, LinearLayout.LayoutParams(-1, dp(1)))
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
        setBackgroundColor(Palette.alpha(palette.BG, 0xB8))
        isClickable = true
        isFocusable = false
        setOnClickListener { owner?.back() } // tapping the scrim leaves the top popup
        addView(panel, LayoutParams(-2, -2, Gravity.CENTER))
    }

    override val fieldFocused: Boolean get() = this.field?.isFocused == true

    override fun show(spec: PopupSpec, hasParent: Boolean, popups: Popups) {
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

    override fun hide() {
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
                setTextColor(palette.TEXT); setLineSpacing(0f, 1.25f)
                setPadding(dp(18), dp(14), dp(18), dp(10))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        spec.text?.let { value ->
            val edit = EditText(context).apply {
                setText(value.value); setSingleLine(); setSelectAllOnFocus(true)
                filters = arrayOf<InputFilter>(InputFilter.LengthFilter(value.maxLength))
                imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                typeface = REGULAR; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(palette.TEXT); setHintTextColor(palette.DIM)
                nativeFieldHighlight = highlightColor
                if (palette !== Palette.SLATE) styleField(this)
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
            val row: View = when {
                item.heading -> TextView(context).apply {
                    text = item.label; typeface = BOLD; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(palette.FOCUS_ACCENT); setPadding(dp(20), dp(12), dp(18), dp(4))
                }
                item.value != null -> LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(46)
                    setPadding(dp(20), 0, dp(18), 0)
                    background = RowBackground(density)
                    isClickable = true
                    contentDescription = "${item.label}, ${item.value}"
                    setOnClickListener { owner?.choose(entry) }
                    addView(TextView(context).apply {
                        text = item.label; typeface = REGULAR; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); maxLines = 2
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(TextView(context).apply {
                        text = item.value; typeface = BOLD; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        gravity = Gravity.CENTER; minWidth = dp(92)
                        setPadding(dp(10), dp(4), dp(10), dp(4))
                        background = GradientDrawable().apply { setColor(palette.LANE); cornerRadius = dp(4).toFloat(); setStroke(dp(1), palette.RULE) }
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
                }
                else -> Button(context).apply {
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
            }
            rows += row
            body.addView(row, LinearLayout.LayoutParams(-1, if (row is Button) dp(46) else -2))
        }
        body.addView(View(context), LinearLayout.LayoutParams(-1, dp(6)))
        scroll.scrollTo(0, 0)
    }

    /** Moves the visible cursor to [entry] and scrolls it into view. */
    override fun select(entry: Int) = paintSelection(entry, scrollToEntry = true)

    private fun paintSelection(entry: Int, scrollToEntry: Boolean) {
        val spec = shown ?: return
        rows.forEachIndexed { index, view ->
            val on = index == entry
            if (view is EditText) {
                view.background = GradientDrawable().apply {
                    setColor(palette.LANE); cornerRadius = dp(4).toFloat()
                    setStroke(dp(if (on || view.isFocused) 2 else 1), if (on) palette.FOCUS_ACCENT else palette.RULE)
                }
            } else {
                val item = spec.items[spec.itemIndex(index)]
                if (item.heading) {
                    (view as TextView).setTextColor(palette.FOCUS_ACCENT)
                    return@forEachIndexed
                }
                (view.background as? RowBackground)?.let { it.selected = on; it.invalidateSelf() }
                val color = when {
                    item.tone == PopupItem.Tone.DANGER -> palette.WRITE
                    on -> palette.TEXT
                    else -> Palette.mix(palette.TEXT, palette.DIM, 0.35f)
                }
                if (view is LinearLayout) {
                    (view.getChildAt(0) as TextView).setTextColor(color)
                    (view.getChildAt(1) as TextView).setTextColor(if (on) palette.FOCUS_ACCENT else palette.TEXT)
                } else (view as TextView).setTextColor(color)
            }
        }
        val keys = owner?.keys ?: org.thortracker.input.Keymap.DEFAULT
        val onField = spec.text != null && entry == 0
        val leave = if (spec.items.any { it.action == null }) "Back" else "Close"
        hints.set(if (spec.capture != null) listOf(
            "Button" to "Bind it",
            "Sel L2 R2 L3 +" to "Chord",
            "L2+R2 +" to "Both",
            "Back" to "Cancel",
        ) else buildList {
            add(keys.pair(org.thortracker.editor.Action.UP, org.thortracker.editor.Action.DOWN) to "Move")
            add(keys.label(org.thortracker.editor.Action.EDIT) to if (onField) (if (fieldFocused) "Keyboard" else "Type") else "Choose")
            if (onField && fieldFocused) add(keys.pair(org.thortracker.editor.Action.LEFT, org.thortracker.editor.Action.RIGHT) to "Cursor")
            add(keys.label(org.thortracker.editor.Action.CLEAR) to leave)
        })
        if (scrollToEntry) reveal(entry)
    }

    private fun reveal(entry: Int) {
        val row = rows.getOrNull(entry) ?: return
        scroll.post {
            if (entry == 0) {
                // Even a zero-distance scroll intercepts the next tap while its animator runs.
                if (scroll.scrollY != 0) scroll.smoothScrollTo(0, 0)
                return@post
            }
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

    override fun page(direction: Int) {
        scroll.smoothScrollBy(0, direction * maxOf(dp(48), scroll.height * 2 / 3))
    }

    /** Gives the text field Android focus and asks the system keyboard to appear. */
    override fun focusField() {
        val f = field ?: return
        f.requestFocus()
        f.post { context.getSystemService(InputMethodManager::class.java)?.showSoftInput(f, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun dp(v: Int) = (v * density).toInt()

    private inner class RowBackground(private val density: Float) : Drawable() {
        var selected = false
        private val paint = android.graphics.Paint()
        private var pressed = false

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (selected || pressed) {
                paint.color = if (selected) palette.CURSOR_FILL else palette.BAR
                canvas.drawRect(b, paint)
            }
            if (selected) {
                paint.color = palette.FOCUS_ACCENT
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
    private val surface = CanvasSurface()
    private val ink = Ink(resources.displayMetrics.density, surface)
    var palette: Palette
        get() = ink.palette
        set(value) {
            if (ink.palette === value) return
            ink.palette = value
            invalidate()
        }
    private var hints: List<Pair<String, String>> = emptyList()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun set(next: List<Pair<String, String>>) {
        if (next == hints) return
        hints = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.LANE)
        surface.canvas = canvas
        val size = ink.dp(12f) * 0.82f
        var x = ink.dp(14f)
        for ((keys, meaning) in hints) {
            if (x + ink.hintWidth(keys, meaning, size) > width) break
            x = ink.hint(surface, keys, meaning, x, 0f, height.toFloat(), size)
        }
        surface.canvas = null
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
