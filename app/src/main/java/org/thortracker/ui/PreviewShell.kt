package org.thortracker.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Single-screen preview chrome. The actual editor receives the remaining height, including after resize. */
class PreviewShell(
    context: Context,
    private val tracker: TrackerView,
    val dock: PreviewDock,
    private val cancelInput: () -> Unit,
    private val entries: () -> List<Pair<String, () -> Unit>>,
    play: () -> Unit,
) : FrameLayout(context) {
    private val title = TextView(context).apply {
        typeface = PopupLayer.REGULAR; textSize = 12f; gravity = Gravity.CENTER_VERTICAL
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(8), 0, dp(8), 0)
    }
    private val menu = button("Menu") { openDrawer() }
    private val transport = button("Play", play)
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val drawer = FrameLayout(context).apply { visibility = GONE; isClickable = true }
    private val drawerRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val drawerScroll = ScrollView(context).apply { isFillViewport = true; addView(drawerRows) }
    private var palette = Palette.SLATE
    val drawerOpen: Boolean get() = drawer.visibility == VISIBLE

    init {
        tag = "preview-shell"
        fitsSystemWindows = true
        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(menu, LinearLayout.LayoutParams(dp(64), dp(48)))
        bar.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        bar.addView(transport, LinearLayout.LayoutParams(dp(64), dp(48)))
        content.addView(bar, LinearLayout.LayoutParams(-1, dp(48)))
        content.addView(tracker, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(dock, LinearLayout.LayoutParams(-1, dp(PreviewDock.HEIGHT_DP)))
        addView(content, LayoutParams(-1, -1))
        drawer.setOnClickListener { closeDrawer() }
        drawer.addView(drawerScroll, LayoutParams(-1, -1, Gravity.START))
        addView(drawer, LayoutParams(-1, -1))
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun button(label: String, action: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; typeface = PopupLayer.REGULAR; textSize = 14f
        minHeight = dp(48); minimumHeight = dp(48); minWidth = dp(48)
        setPadding(dp(8), dp(4), dp(8), dp(4)); setOnClickListener { action() }
    }

    fun openDrawer() {
        cancelInput()
        drawerRows.removeAllViews()
        addDrawerButton("Close navigation") { closeDrawer() }
        for ((label, action) in entries()) addDrawerButton(label) { closeDrawer(); action() }
        drawer.visibility = VISIBLE
        content.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        drawerRows.getChildAt(0)?.requestFocus()
        drawerRows.announceForAccessibility("Touch preview navigation")
    }

    private fun addDrawerButton(label: String, action: () -> Unit) {
        val item = button(label, action).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        style(item)
        drawerRows.addView(item, LinearLayout.LayoutParams(-1, -2))
    }

    fun closeDrawer() {
        drawer.visibility = GONE
        content.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
        tracker.requestFocus()
    }

    fun refresh(value: Palette, view: String, playing: Boolean, ready: Boolean) {
        title.text = "Touch preview\n$view"
        transport.text = if (playing) "Stop" else "Play"
        transport.isEnabled = ready
        if (palette !== value || menu.background !is GradientDrawable) {
            palette = value
            setBackgroundColor(value.BG); content.setBackgroundColor(value.BG)
            title.setTextColor(value.TEXT); drawer.setBackgroundColor(value.BG)
            style(menu); style(transport)
            for (i in 0 until drawerRows.childCount) style(drawerRows.getChildAt(i) as Button)
            dock.palette = value
        }
    }

    private fun style(button: Button) {
        button.setTextColor(palette.TEXT)
        button.background = GradientDrawable().apply { setColor(palette.BAR); setStroke(dp(1), palette.RULE) }
    }
}
