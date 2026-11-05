package org.thortracker.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.editor.Action

class KeymapTest {
    private val sel = KeyEvent.KEYCODE_BUTTON_SELECT
    private val l2 = KeyEvent.KEYCODE_BUTTON_L2

    @Test fun defaultLabelsMatchTheDocumentedControls() {
        val k = Keymap.DEFAULT
        assertEquals("L2+A", k.label(Action.UNDO))
        assertEquals("L2+→", k.label(Action.SCALE))
        assertEquals("Sel+X/A", k.pair(Action.COPY, Action.PASTE))
        assertEquals("L1 R1", k.pair(Action.PREV_CHANNEL, Action.NEXT_CHANNEL))
        assertEquals("A+←→", k.held(Action.EDIT, k.pair(Action.LEFT, Action.RIGHT)))
        assertEquals("✛", k.dpad())
        assertEquals("Sel+Start", k.label(Control.TOOLBAR))
    }

    @Test fun everyControlHasADistinctValidDefault() {
        val k = Keymap.DEFAULT
        val bindings = Control.entries.map { c -> assertNotNull(c.name, k.binding(c)); k.binding(c)!! }
        assertEquals(bindings.size, bindings.toSet().size)
        for (c in Control.entries) assertNull(c.name, k.problem(c, k.binding(c)!!))
    }

    @Test fun keymapFileRoundTripsAndToleratesOtherVersionsControls() {
        val changed = Keymap.DEFAULT.with(Control.UNDO, Binding(KeyEvent.KEYCODE_BUTTON_B, sel)).with(Control.CUT, null)
            .with(Control.FOCUS, Binding(KeyEvent.KEYCODE_BUTTON_C))
        assertEquals(changed, Keymap.fromJson(changed.toJson()))
        assertTrue(changed.toJson().contains("\"UNDO\": \"SELECT+B\""))
        // Unknown controls (newer file) are ignored; missing ones (older file) keep their defaults.
        val partial = """{"format":"thor-tracker-keymap","version":1,"bindings":{"REDO":"L3+A","FUTURE_THING":"X"}}"""
        val read = Keymap.fromJson(partial)
        assertEquals(Binding(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_THUMBL), read.binding(Control.REDO))
        assertEquals(Keymap.DEFAULT.binding(Control.UNDO), read.binding(Control.UNDO))
    }

    @Test fun invalidKeymapFilesAreRejectedWithTheReason() {
        fun file(bindings: String) = """{"format":"thor-tracker-keymap","version":1,"bindings":{$bindings}}"""
        val cases = mapOf(
            file("\"COPY\":\"START\"") to "both",                // duplicate of Play
            file("\"EDIT\":\"L2+A\"") to "single button",         // held control as a chord
            file("\"UNDO\":\"L2\"") to "only starts chords",      // modifier alone
            file("\"UNDO\":\"A+B\"") to "Chords start with",      // non-modifier chord
            file("\"UP\":null") to "needs a binding",             // essential unbound
            file("\"UNDO\":\"BOGUS\"") to "not a key",
            """{"format":"thor-tracker-keymap","version":9,"bindings":{}}""" to "newer",
            """{"format":"other"}""" to "Not a Thor Tracker keymap",
        )
        for ((text, reason) in cases) {
            val e = assertThrows(IllegalArgumentException::class.java) { Keymap.fromJson(text) }
            assertTrue("$text: ${e.message}", e.message!!.contains(reason))
        }
    }

    @Test fun takingABindingFromAnEssentialControlIsReported() {
        val k = Keymap.DEFAULT
        assertEquals(Control.EDIT, k.stranded(Control.AUDITION, Binding(KeyEvent.KEYCODE_BUTTON_A)))
        assertNull(k.stranded(Control.REDO, Binding(KeyEvent.KEYCODE_BUTTON_X, l2))) // Swap is not essential
    }

    @Test fun legacyRemapsMoveEveryBindingThatUsedTheRemappedButtons() {
        // 0.4 remap: physical B acts as A and physical A acts as B.
        val k = Keymap.fromLegacyRemap(mapOf(KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(Binding(KeyEvent.KEYCODE_BUTTON_B), k.binding(Control.EDIT))
        assertEquals(Binding(KeyEvent.KEYCODE_BUTTON_A), k.binding(Control.CLEAR))
        assertEquals(Binding(KeyEvent.KEYCODE_BUTTON_B, l2), k.binding(Control.UNDO))
        assertEquals(Binding(KeyEvent.KEYCODE_BUTTON_B, sel), k.binding(Control.PASTE))
        assertEquals(Keymap.DEFAULT.binding(Control.COPY), k.binding(Control.COPY))
        assertEquals(Keymap.DEFAULT, Keymap.fromLegacyRemap(emptyMap()))
    }

    @Test fun menusFollowReboundConfirmAndBack() {
        val k = Keymap.DEFAULT.with(Control.EDIT, Binding(KeyEvent.KEYCODE_BUTTON_Y)).with(Control.MARK, Binding(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, k.canonical(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, k.canonical(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(KeyEvent.KEYCODE_ENTER, k.canonical(KeyEvent.KEYCODE_ENTER))
        assertEquals("Y+←→", k.held(Action.EDIT, k.pair(Action.LEFT, Action.RIGHT)))
    }
}
