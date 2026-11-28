package org.thortracker.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Primary-screen touch chrome. All touch chrome (both the top bar and the virtual controller dock) hides
 * completely whenever a usable physical controller is attached ([dockShown] == false), leaving 100% of the
 * display to [tracker].
 */
class TouchShell(
    context: Context,
    private val tracker: TrackerView,
    val dock: ControllerDock,
    private val cancelInput: () -> Unit,
    private val chooseView: () -> Unit,
    private val entries: () -> List<Pair<String, () -> Unit>>,
    play: () -> Unit,
) : FrameLayout(context) {
    private val songTitle = TextView(context).apply {
        typeface = PopupLayer.BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val menu = pillButton("Menu") { openDrawer() }
    private val viewPill = pillButton("Pattern ▾", chooseView)
    private val transport = pillButton("Play", play)
    private val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), 0, dp(6), 0)
    }
    private val barRule = View(context)
    private val header = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val drawer = FrameLayout(context).apply { visibility = GONE; isClickable = true }
    private val drawerCard = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; isClickable = true }
    private val drawerTitle = TextView(context).apply {
        text = "Navigation"
        typeface = PopupLayer.BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(16), dp(12), dp(16), dp(10))
    }
    private val drawerRule = View(context)
    private val drawerRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val drawerScroll = ScrollView(context).apply { isFillViewport = false; addView(drawerRows) }
    private var palette = Palette.SLATE
    private var lastPlaying = false
    val drawerOpen: Boolean get() = drawer.visibility == VISIBLE

    /** The host cancels the dock's presses ([ControllerDock.cancel]) before hiding touch chrome. */
    var dockShown: Boolean
        get() = dock.visibility == VISIBLE
        set(value) {
            val vis = if (value) VISIBLE else GONE
            header.visibility = vis
            dock.visibility = vis
            if (!value && drawerOpen) closeDrawer()
        }

    init {
        tag = "touch-shell"
        fitsSystemWindows = true
        bar.addView(menu, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) })
        bar.addView(viewPill, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(8) })
        bar.addView(songTitle, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        bar.addView(transport, LinearLayout.LayoutParams(-2, dp(34)))
        header.addView(bar, LinearLayout.LayoutParams(-1, dp(42)))
        header.addView(barRule, LinearLayout.LayoutParams(-1, dp(1)))
        content.addView(header, LinearLayout.LayoutParams(-1, -2))
        content.addView(tracker, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(dock, LinearLayout.LayoutParams(-1, dp(ControllerDock.HEIGHT_DP)))
        addView(content, LayoutParams(-1, -1))

        drawerCard.addView(drawerTitle, LinearLayout.LayoutParams(-1, -2))
        drawerCard.addView(drawerRule, LinearLayout.LayoutParams(-1, dp(1)))
        drawerCard.addView(drawerScroll, LinearLayout.LayoutParams(-1, -2))
        drawer.setOnClickListener { closeDrawer() }
        drawer.addView(drawerCard, LayoutParams(dp(300), -1, Gravity.START))
        addView(drawer, LayoutParams(-1, -1))
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()

    private fun pillButton(label: String, action: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; typeface = PopupLayer.REGULAR
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        minHeight = 0; minimumHeight = 0; minWidth = dp(52); minimumWidth = dp(52)
        stateListAnimator = null
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { action() }
    }

    private fun drawerRowButton(label: String, action: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; typeface = PopupLayer.REGULAR
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        minHeight = dp(46); minimumHeight = dp(46)
        stateListAnimator = null
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(4), dp(16), dp(4))
        setOnClickListener { action() }
    }

    fun openDrawer() {
        cancelInput()
        drawerRows.removeAllViews()
        addDrawerButton("Close navigation", accent = true) { closeDrawer() }
        for ((label, action) in entries()) addDrawerButton(label) { closeDrawer(); action() }
        drawer.visibility = VISIBLE
        content.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        drawerRows.getChildAt(0)?.requestFocus()
        drawerRows.announceForAccessibility("Navigation")
    }

    private fun addDrawerButton(label: String, accent: Boolean = false, action: () -> Unit) {
        val item = drawerRowButton(label, action).apply {
            typeface = if (accent) PopupLayer.BOLD else PopupLayer.REGULAR
        }
        styleDrawerRow(item, accent)
        drawerRows.addView(item, LinearLayout.LayoutParams(-1, -2))
    }

    fun closeDrawer() {
        drawer.visibility = GONE
        content.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
        tracker.requestFocus()
    }

    fun refresh(value: Palette, song: String, view: String, playing: Boolean, ready: Boolean) {
        songTitle.text = song.ifBlank { "Thor Tracker" }
        viewPill.text = "$view ▾"
        transport.text = if (playing) "Stop" else "Play"
        transport.isEnabled = ready
        val paletteChanged = palette !== value || menu.background !is GradientDrawable
        if (paletteChanged || lastPlaying != playing) {
            palette = value
            lastPlaying = playing
            setBackgroundColor(value.BG); content.setBackgroundColor(value.BG)
            bar.setBackgroundColor(value.LANE)
            barRule.setBackgroundColor(value.RULE)
            songTitle.setTextColor(value.DIM)
            drawer.setBackgroundColor(Palette.alpha(value.BG, 0xB8))
            drawerCard.background = GradientDrawable().apply {
                setColor(value.BG)
                setStroke(dp(1), value.RULE)
            }
            drawerTitle.setBackgroundColor(value.LANE)
            drawerTitle.setTextColor(value.FOCUS_ACCENT)
            drawerRule.setBackgroundColor(value.RULE)
            stylePill(menu, active = false)
            stylePill(viewPill, active = true)
            styleTransport(playing, ready)
            for (i in 0 until drawerRows.childCount) {
                styleDrawerRow(drawerRows.getChildAt(i) as Button, i == 0)
            }
            dock.palette = value
        } else {
            styleTransport(playing, ready)
        }
    }

    private fun stylePill(button: Button, active: Boolean) {
        button.setTextColor(if (active) palette.FOCUS_ACCENT else palette.TEXT)
        button.typeface = if (active) PopupLayer.BOLD else PopupLayer.REGULAR
        button.background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(if (active) Palette.mix(palette.BAR, palette.FOCUS_ACCENT, 0.14f) else palette.BAR)
            setStroke(dp(1), if (active) Palette.alpha(palette.FOCUS_ACCENT, 140) else palette.RULE)
        }
    }

    private fun styleTransport(playing: Boolean, ready: Boolean) {
        transport.setTextColor(when {
            !ready -> palette.FAINT
            playing -> palette.PLAYHEAD
            else -> palette.TEXT
        })
        transport.typeface = if (playing) PopupLayer.BOLD else PopupLayer.REGULAR
        transport.background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(if (playing) Palette.alpha(palette.PLAYHEAD, 44) else palette.BAR)
            setStroke(dp(1), if (playing) palette.PLAYHEAD else palette.RULE)
        }
    }

    private fun styleDrawerRow(button: Button, accent: Boolean) {
        button.setTextColor(if (accent) palette.FOCUS_ACCENT else palette.TEXT)
        button.background = GradientDrawable().apply {
            setColor(if (accent) palette.LANE else palette.BG)
            setStroke(dp(1), palette.RULE)
        }
    }
}
