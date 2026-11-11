package org.thortracker.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import org.thortracker.input.Keys
import org.thortracker.input.PreviewPointers

/** Three rows of 48dp targets, with independent pointer ownership and bounded thumb travel. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class PreviewDock(
    context: Context,
    private val send: (code: Int, down: Boolean, repeat: Int) -> Unit,
    private val cancelInput: () -> Unit,
) : LinearLayout(context) {
    companion object { const val HEIGHT_DP = 144 }

    private val buttons = LinkedHashMap<Int, Button>()
    private val names = HashMap<Int, String>()
    private val painted = HashMap<Int, Boolean>()
    private val latchKeys = setOf(Keys.BUTTON_SELECT, Keys.BUTTON_L2, Keys.BUTTON_R2, Keys.BUTTON_THUMBL)
    private val arrows = intArrayOf(Keys.DPAD_UP, Keys.DPAD_DOWN, Keys.DPAD_LEFT, Keys.DPAD_RIGHT)
    private val bounds = Rect()
    private val pointers = PreviewPointers { code, down -> send(code, down, 0) }
    private val leftControls = LinearLayout(context).apply { orientation = VERTICAL }
    private val rightControls = LinearLayout(context).apply { orientation = VERTICAL }
    private var repeating = false
    private var repeatCount = 0
    var palette: Palette = Palette.SLATE
        set(value) { if (field !== value) { field = value; painted.clear(); repaint() } }
    private val repeat = object : Runnable {
        override fun run() {
            if (!repeating) return
            repeatCount++
            for (code in arrows) if (pointers.pressed(code)) send(code, true, repeatCount)
            if (repeating) postDelayed(this, 85)
        }
    }

    init {
        orientation = HORIZONTAL
        setPadding(dp(8), 0, dp(8), 0)
        isMotionEventSplittingEnabled = false
        addView(leftControls, LayoutParams(0, -1))
        addView(View(context), LayoutParams(0, -1, 1f))
        addView(rightControls, LayoutParams(0, -1))
        row(leftControls).also { key(it, "L2", Keys.BUTTON_L2); key(it, "↑", Keys.DPAD_UP); key(it, "L1", Keys.BUTTON_L1) }
        row(leftControls).also { key(it, "←", Keys.DPAD_LEFT); key(it, "Start", Keys.BUTTON_START); key(it, "→", Keys.DPAD_RIGHT) }
        row(leftControls).also { key(it, "Select", Keys.BUTTON_SELECT); key(it, "↓", Keys.DPAD_DOWN); space(it) }
        row(rightControls).also { key(it, "R1", Keys.BUTTON_R1); key(it, "Y", Keys.BUTTON_Y); key(it, "R2", Keys.BUTTON_R2) }
        row(rightControls).also { key(it, "X", Keys.BUTTON_X); key(it, "R3", Keys.BUTTON_THUMBR); key(it, "A", Keys.BUTTON_A) }
        row(rightControls).also { space(it); key(it, "B", Keys.BUTTON_B); key(it, "L3", Keys.BUTTON_THUMBL) }
        repaint()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // A wider unfolded window adds a central gap, not extra thumb travel.
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val groupWidth = minOf(available / 2, dp(216))
        leftControls.layoutParams.width = groupWidth
        rightControls.layoutParams.width = groupWidth
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun row(parent: LinearLayout) = LinearLayout(context).also {
        it.orientation = HORIZONTAL
        parent.addView(it, LayoutParams(-1, dp(48)))
    }
    private fun space(row: LinearLayout) = row.addView(View(context), LayoutParams(0, -1, 1f))
    private fun key(row: LinearLayout, name: String, code: Int) {
        val button = Button(context).apply {
            text = name; typeface = PopupLayer.REGULAR; textSize = 12f
            isAllCaps = false; minWidth = dp(48); minimumWidth = dp(48)
            minHeight = dp(48); minimumHeight = dp(48); setPadding(0, 0, 0, 0)
            tag = "preview-key-$code"
            setOnClickListener {
                // Accessibility activation is a full tap; real multi-touch goes through dispatchTouchEvent.
                pointers.down(Int.MIN_VALUE, code, code in latchKeys)
                pointers.up(Int.MIN_VALUE) { it in latchKeys }
                repaint()
            }
        }
        names[code] = name
        buttons[code] = button
        row.addView(button, LayoutParams(0, -1, 1f))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // A hidden dock can still be a gesture's touch target: its stale buttons must not take another finger.
        if (!isEnabled || visibility != VISIBLE) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val index = event.actionIndex
                val x = event.getX(index).toInt(); val y = event.getY(index).toInt()
                for ((code, button) in buttons) {
                    button.getDrawingRect(bounds)
                    offsetDescendantRectToMyCoords(button, bounds)
                    if (bounds.contains(x, y)) {
                        pointers.down(event.getPointerId(index), code, code in latchKeys)
                        // A callback may synchronously open a modal and cancel the entire gesture.
                        if (!repeating && arrows.any { pointers.pressed(it) }) {
                            repeating = true; repeatCount = 0; postDelayed(repeat, 350)
                        }
                        break
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                pointers.up(event.getPointerId(event.actionIndex)) { it in latchKeys }
                if (arrows.none { pointers.pressed(it) }) stopRepeat()
            }
            MotionEvent.ACTION_CANCEL -> cancelInput()
        }
        repaint()
        return true
    }

    private fun stopRepeat() { repeating = false; removeCallbacks(repeat) }
    fun cancel() { stopRepeat(); pointers.cancel(); repaint() }
    override fun onDetachedFromWindow() { cancelInput(); super.onDetachedFromWindow() }

    private fun repaint() {
        setBackgroundColor(palette.BAR)
        for ((code, button) in buttons) {
            val on = pointers.pressed(code)
            if (painted[code] == on) continue
            painted[code] = on
            button.text = names[code] + if (pointers.latched(code)) " ON" else ""
            button.isSelected = on
            button.contentDescription = "${names[code]}${if (code in latchKeys) if (on) " layer on; tap to release" else " layer off; tap to latch" else " controller button; hold with arrows"}"
            button.setTextColor(if (on) palette.BG else palette.TEXT)
            button.background = GradientDrawable().apply {
                setColor(if (on) palette.FOCUS_ACCENT else palette.BAR)
                setStroke(dp(1), palette.RULE)
                cornerRadius = dp(4).toFloat()
            }
        }
    }
}
