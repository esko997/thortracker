package org.thortracker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.input.Keys

class DrawnPopupTest {
    private class RecordingSurface : Surface {
        val texts = mutableListOf<String>()
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) { texts += s }
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float) = s.length * size / 2
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) {}
        override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {}
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) {}
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) {}
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) {}
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) {}
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) {}
    }

    @Test fun longSourcesOpenAtBeginningAndControllerPagesWithoutSnappingBackToActions() {
        val surface = RecordingSurface()
        val view = DrawnPopup(Ink(1f, surface))
        val popups = Popups({ view }, { listOf(0) })
        popups.show(PopupSpec("Source", message = (0 until 100).joinToString("\n") { "Line $it" },
            items = listOf(PopupItem("First action") {}, PopupItem("End action") {})), 0)
        fun lines(): List<String> {
            surface.texts.clear()
            view.draw(surface, 640f, 480f)
            return surface.texts.filter { it.startsWith("Line ") }
        }
        assertTrue(lines().contains("Line 0"))
        popups.handleKey(Keys.BUTTON_R1, true, 0)
        val paged = lines()
        assertTrue(paged.isNotEmpty())
        assertFalse(paged.contains("Line 0"))
        assertEquals(paged, lines())
        popups.handleKey(Keys.BUTTON_L1, true, 0)
        assertTrue(lines().contains("Line 0"))
        popups.handleKey(Keys.DPAD_DOWN, true, 0)
        lines()
        assertTrue(surface.texts.contains("End action"))
        popups.handleKey(Keys.BUTTON_L1, true, 0)
        val beforeActions = lines()
        assertTrue(beforeActions.isNotEmpty())
        assertEquals(beforeActions, lines())
    }

    @Test fun unbrokenSourceTokensWrapWithoutLosingCharacters() {
        val surface = RecordingSurface()
        val view = DrawnPopup(Ink(1f, surface))
        val popups = Popups({ view }, { listOf(0) })
        val token = "Q".repeat(180)
        popups.show(PopupSpec("Source", message = token, items = listOf(PopupItem.dismiss())), 0)
        view.draw(surface, 250f, 1000f)
        val fragments = surface.texts.filter { it.isNotEmpty() && it.all { c -> c == 'Q' } }
        assertTrue(fragments.size > 1)
        assertEquals(token, fragments.joinToString(""))
    }
}
