package org.thortracker

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.storage.WorkspaceCodec
import org.thortracker.storage.WorkspaceStore
import org.thortracker.ui.Palette
import org.thortracker.ui.PopupItem
import org.thortracker.ui.PopupLayer
import org.thortracker.ui.PopupSpec
import org.thortracker.ui.PopupText
import org.thortracker.ui.Popups
import org.thortracker.ui.ShellToolbar

@RunWith(AndroidJUnit4::class)
class ColourThemeTest {
    @Test fun palettePersistsIndependentlyOfPaneLayoutAndUnknownIdsFallBack() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "theme-probe-${System.nanoTime()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$prefix-$name", mode)
        }
        try {
            val store = WorkspaceStore(context)
            assertSame(Palette.SLATE, store.palette())
            val states = listOf(ViewState(kind = ViewKind.PATTERN, row = 17), ViewState(kind = ViewKind.HELP))
            store.save(states, 1)
            for (palette in Palette.all) {
                store.savePalette(palette)
                val reopened = WorkspaceStore(context)
                assertSame(palette, reopened.palette())
                assertEquals(WorkspaceCodec.encode(states), WorkspaceCodec.encode(reopened.load()))
                assertEquals(1, reopened.focused())
            }
            context.getSharedPreferences("workspace", Context.MODE_PRIVATE).edit().putString("palette", "future-palette").commit()
            assertSame(Palette.SLATE, WorkspaceStore(context).palette())
        } finally {
            base.deleteSharedPreferences("$prefix-workspace")
        }
    }

    @Test fun nativePopupAndToolbarRecolourWithoutReplacingAnOpenTextFieldOrLeakingScopes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.TrackerTheme)
            val popup = PopupLayer(context)
            val other = PopupLayer(context)
            val toolbar = ShellToolbar(context) { false }
            val button = toolbar.add("Menu") {}
            val popups = Popups({ if (it == 0) popup else other }, { listOf(0, 1) })
            val value = PopupText("Keep this text", maxLength = 80)
            val spec = PopupSpec("Open dialog", text = value, items = listOf(PopupItem.dismiss()))
            popups.show(spec, 0)
            val edit = requireNotNull(findEdit(popup))
            edit.setSelection(2, 6)
            val selected = spec.selected
            for (palette in listOf(Palette.PAPER, Palette.CONTRAST, Palette.SLATE)) {
                popup.palette = palette
                toolbar.palette = palette
                assertSame(edit, findEdit(popup))
                assertEquals("Keep this text", edit.text.toString())
                assertEquals("Keep this text", value.value)
                assertEquals(2, edit.selectionStart)
                assertEquals(6, edit.selectionEnd)
                assertEquals(selected, spec.selected)
                assertSame(spec, popups.top)
                assertEquals(palette.TEXT, edit.currentTextColor)
                assertEquals(palette.TEXT, button.currentTextColor)
                assertEquals(palette.DIM, toolbar.status.currentTextColor)
                assertSame(Palette.SLATE, other.palette)
            }
            popups.closeAll()
        }
    }

    private fun findEdit(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findEdit(view.getChildAt(i))?.let { return it }
        return null
    }
}
