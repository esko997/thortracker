package org.thortracker.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.ViewKind

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
        assertEquals("L2+A/B", k.pair(Action.UNDO, Action.REDO))
        assertEquals(listOf("R2+A", "R2+B", "R2+X", "R2+Y"),
            listOf(Control.SHOW_SONG, Control.SHOW_INSTRUMENT, Control.SHOW_SKETCH, Control.SHOW_KEYBOARD).map(k::label))
        assertEquals(null, k.binding(Control.SHOW_PATTERN))
    }

    @Test fun everyControlHasAValidDefaultThatNoOtherControlInTheSameViewShares() {
        val k = Keymap.DEFAULT
        val unboundByDefault = setOf(Control.SHOW_PATTERN, Control.SHOW_WAVE, Control.SHOW_HELP)
        for (c in Control.entries) {
            val b = k.binding(c)
            if (c in unboundByDefault) { assertNull(c.name, b); continue }
            assertNotNull(c.name, b)
            assertNull(c.name, k.problem(c, b!!))
            assertEquals(c.name, emptyList<Control>(), k.conflicts(c, b))
        }
    }

    @Test fun oneChordMeansDifferentThingsInDifferentViews() {
        val k = Keymap.DEFAULT
        val l2Right = Binding(KeyEvent.KEYCODE_DPAD_RIGHT, l2)
        assertEquals(Control.PATTERN_PANEL, k.control(l2Right, ViewKind.PATTERN))
        assertEquals(Control.LISTEN_OCTAVE_UP, k.control(l2Right, ViewKind.INSTRUMENT))
        assertNull(k.control(l2Right, ViewKind.SONG))
        // Hints name the binding that acts in the view.
        assertEquals("L2+←→", k.pair(Action.OCTAVE_DOWN, Action.OCTAVE_UP, ViewKind.WAVE))
        assertEquals("Sel+L1/R1", k.pair(Action.OCTAVE_DOWN, Action.OCTAVE_UP, ViewKind.PATTERN))
        // Chord hints list only what the chord does in the view.
        val patternChords = k.chords(l2, ViewKind.PATTERN).map { it.first }
        val soundChords = k.chords(l2, ViewKind.INSTRUMENT).map { it.first }
        assertTrue(Control.PATTERN_PANEL in patternChords && Control.LISTEN_OCTAVE_UP !in patternChords)
        assertTrue(Control.LISTEN_OCTAVE_UP in soundChords && Control.PATTERN_PANEL !in soundChords)
        assertTrue(Control.UNDO in patternChords && Control.UNDO in soundChords)
        // A global control taking a scoped binding displaces every control it would collide with.
        assertEquals(setOf(Control.PATTERN_PANEL, Control.LISTEN_OCTAVE_UP), k.conflicts(Control.SWAP, l2Right).toSet())
        assertEquals(listOf(Control.PATTERN_PANEL), k.conflicts(Control.INSERT_ROW, l2Right))
    }

    @Test fun olderFilesDoNotGainADefaultTheyAlreadyUseElsewhere() {
        // A 0.5 file that put Swap on L2+←: the new listening-octave default must not collide with it.
        val old = """{"format":"thor-tracker-keymap","version":1,"bindings":{"SWAP":"L2+LEFT","UNDO":"L2+A"}}"""
        val read = Keymap.fromJson(old)
        assertEquals(Binding(KeyEvent.KEYCODE_DPAD_LEFT, l2), read.binding(Control.SWAP))
        assertNull(read.binding(Control.LISTEN_OCTAVE_DOWN))
        assertEquals(Keymap.DEFAULT.binding(Control.LISTEN_OCTAVE_UP), read.binding(Control.LISTEN_OCTAVE_UP))
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

    /** A 0.7.x (version 2) keymap: today's defaults with Redo on R2+A and no view controls. */
    private fun versionTwo(change: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit = {}): String {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(Keymap.DEFAULT.toJson()) as kotlinx.serialization.json.JsonObject
        val bindings = (root["bindings"] as kotlinx.serialization.json.JsonObject).toMutableMap()
        Control.entries.filter { it.since > 2 }.forEach { bindings.remove(it.name) }
        bindings["REDO"] = kotlinx.serialization.json.JsonPrimitive("R2+A")
        change(bindings)
        return kotlinx.serialization.json.JsonObject(root.toMutableMap().also {
            it["version"] = kotlinx.serialization.json.JsonPrimitive(2)
            it["bindings"] = kotlinx.serialization.json.JsonObject(bindings)
        }).toString()
    }

    @Test fun anUntouchedOlderKeymapTakesTodaysDefaultsIncludingMovedRedo() {
        assertEquals(Keymap.DEFAULT, Keymap.fromJson(versionTwo()))
    }

    @Test fun aCustomisedOlderKeymapKeepsRedoOnR2AndLeavesThatViewChordUnbound() {
        val read = Keymap.fromJson(versionTwo { it["SWAP"] = kotlinx.serialization.json.JsonPrimitive("L3+X") })
        assertEquals(Binding(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_R2), read.binding(Control.REDO))
        assertNull(read.binding(Control.SHOW_SONG))
        assertEquals(Keymap.DEFAULT.binding(Control.SHOW_INSTRUMENT), read.binding(Control.SHOW_INSTRUMENT))
        assertEquals(Binding(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_THUMBL), read.binding(Control.SWAP))
    }

    @Test fun loopPatternArrivesOnL2YUnlessAnOlderKeymapAlreadyUsesIt() {
        assertEquals(Binding(KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L2), Keymap.DEFAULT.binding(Control.LOOP_PATTERN))
        fun versionThree(bindings: Map<String, String>): String {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(Keymap.DEFAULT.toJson()) as kotlinx.serialization.json.JsonObject
            val all = (root["bindings"] as kotlinx.serialization.json.JsonObject).toMutableMap()
            all.remove(Control.LOOP_PATTERN.name)
            bindings.forEach { (k, v) -> all[k] = kotlinx.serialization.json.JsonPrimitive(v) }
            return kotlinx.serialization.json.JsonObject(root.toMutableMap().also {
                it["version"] = kotlinx.serialization.json.JsonPrimitive(3)
                it["bindings"] = kotlinx.serialization.json.JsonObject(all)
            }).toString()
        }
        val customised = Keymap.fromJson(versionThree(mapOf("SWAP" to "L3+X")))
        assertEquals(Keymap.DEFAULT.binding(Control.LOOP_PATTERN), customised.binding(Control.LOOP_PATTERN))
        val takesL2Y = Keymap.fromJson(versionThree(mapOf("SWAP" to "L2+Y")))
        assertEquals(Binding(KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L2), takesL2Y.binding(Control.SWAP))
        assertNull(takesL2Y.binding(Control.LOOP_PATTERN))
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
