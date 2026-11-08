package org.thortracker.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.ViewKind

class KeymapTest {
    private val sel = setOf(Keys.BUTTON_SELECT)
    private val l2 = setOf(Keys.BUTTON_L2)
    private val r2 = setOf(Keys.BUTTON_R2)
    private val l3 = setOf(Keys.BUTTON_THUMBL)
    private val both = setOf(Keys.BUTTON_L2, Keys.BUTTON_R2)

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
        assertEquals(listOf("R2+A", "R2+B", "R2+X", "R2+Y", "R2+↑", "R2+→", "R2+↓"),
            listOf(Control.SHOW_SONG, Control.SHOW_INSTRUMENT, Control.SHOW_SKETCH, Control.SHOW_KEYBOARD,
                Control.SHOW_PATTERN, Control.SHOW_WAVE, Control.SHOW_HELP).map(k::label))
    }

    @Test fun everyControlHasAValidDefaultThatNoOtherControlInTheSameViewShares() {
        val k = Keymap.DEFAULT
        for (c in Control.entries) {
            val b = k.binding(c)
            assertNotNull(c.name, b)
            assertNull(c.name, k.problem(c, b!!))
            assertEquals(c.name, emptyList<Control>(), k.conflicts(c, b))
        }
    }

    @Test fun oneChordMeansDifferentThingsInDifferentViews() {
        val k = Keymap.DEFAULT
        val l2Right = Binding(Keys.DPAD_RIGHT, l2)
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
        assertEquals(Binding(Keys.DPAD_LEFT, l2), read.binding(Control.SWAP))
        assertNull(read.binding(Control.LISTEN_OCTAVE_DOWN))
        assertEquals(Keymap.DEFAULT.binding(Control.LISTEN_OCTAVE_UP), read.binding(Control.LISTEN_OCTAVE_UP))
    }

    @Test fun keymapFileRoundTripsAndToleratesOtherVersionsControls() {
        val changed = Keymap.DEFAULT.with(Control.UNDO, Binding(Keys.BUTTON_B, sel)).with(Control.CUT, null)
            .with(Control.FOCUS, Binding(Keys.BUTTON_C))
        assertEquals(changed, Keymap.fromJson(changed.toJson()))
        assertTrue(changed.toJson().contains("\"UNDO\": \"SELECT+B\""))
        // Unknown controls (newer file) are ignored; missing ones (older file) keep their defaults.
        val partial = """{"format":"thor-tracker-keymap","version":1,"bindings":{"REDO":"L3+A","FUTURE_THING":"X"}}"""
        val read = Keymap.fromJson(partial)
        assertEquals(Binding(Keys.BUTTON_A, l3), read.binding(Control.REDO))
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
        assertEquals(Binding(Keys.BUTTON_A, r2), read.binding(Control.REDO))
        assertNull(read.binding(Control.SHOW_SONG))
        assertEquals(Keymap.DEFAULT.binding(Control.SHOW_INSTRUMENT), read.binding(Control.SHOW_INSTRUMENT))
        assertEquals(Binding(Keys.BUTTON_X, l3), read.binding(Control.SWAP))
    }

    @Test fun loopPatternArrivesOnL2YUnlessAnOlderKeymapAlreadyUsesIt() {
        assertEquals(Binding(Keys.BUTTON_Y, l2), Keymap.DEFAULT.binding(Control.LOOP_PATTERN))
        fun versionThree(bindings: Map<String, String>): String {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(Keymap.DEFAULT.toJson()) as kotlinx.serialization.json.JsonObject
            val all = (root["bindings"] as kotlinx.serialization.json.JsonObject).toMutableMap()
            Control.entries.filter { it.since > 3 }.forEach { all.remove(it.name) }
            bindings.forEach { (k, v) -> all[k] = kotlinx.serialization.json.JsonPrimitive(v) }
            return kotlinx.serialization.json.JsonObject(root.toMutableMap().also {
                it["version"] = kotlinx.serialization.json.JsonPrimitive(3)
                it["bindings"] = kotlinx.serialization.json.JsonObject(all)
            }).toString()
        }
        val customised = Keymap.fromJson(versionThree(mapOf("SWAP" to "L3+X")))
        assertEquals(Keymap.DEFAULT.binding(Control.LOOP_PATTERN), customised.binding(Control.LOOP_PATTERN))
        val takesL2Y = Keymap.fromJson(versionThree(mapOf("SWAP" to "L2+Y")))
        assertEquals(Binding(Keys.BUTTON_Y, l2), takesL2Y.binding(Control.SWAP))
        assertNull(takesL2Y.binding(Control.LOOP_PATTERN))
    }

    @Test fun anOlderKeymapGainsThePatternWaveAndHelpChordsUnlessItGaveThemAway() {
        /** A version 4 file: today's defaults with the three view controls unbound, as 0.8.1-0.8.6 wrote them. */
        fun versionFour(bindings: Map<String, String>): String {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(Keymap.DEFAULT.toJson()) as kotlinx.serialization.json.JsonObject
            val all = (root["bindings"] as kotlinx.serialization.json.JsonObject).toMutableMap()
            Control.entries.filter { it.since > 4 }.forEach { all.remove(it.name) }
            for (c in listOf(Control.SHOW_PATTERN, Control.SHOW_WAVE, Control.SHOW_HELP)) all[c.name] = kotlinx.serialization.json.JsonNull
            bindings.forEach { (k, v) -> all[k] = kotlinx.serialization.json.JsonPrimitive(v) }
            return kotlinx.serialization.json.JsonObject(root.toMutableMap().also {
                it["version"] = kotlinx.serialization.json.JsonPrimitive(4)
                it["bindings"] = kotlinx.serialization.json.JsonObject(all)
            }).toString()
        }
        assertEquals(Keymap.DEFAULT, Keymap.fromJson(versionFour(emptyMap())))
        val customised = Keymap.fromJson(versionFour(mapOf("SWAP" to "L3+X")))
        assertEquals(Binding(Keys.DPAD_UP, r2), customised.binding(Control.SHOW_PATTERN))
        assertEquals(Binding(Keys.BUTTON_X, l3), customised.binding(Control.SWAP))
        // A file that put Swap on R2+↑ keeps it there; Pattern then has no chord.
        val takesR2Up = Keymap.fromJson(versionFour(mapOf("SWAP" to "R2+UP")))
        assertEquals(Binding(Keys.DPAD_UP, r2), takesR2Up.binding(Control.SWAP))
        assertNull(takesR2Up.binding(Control.SHOW_PATTERN))
        assertEquals(Binding(Keys.DPAD_DOWN, r2), takesR2Up.binding(Control.SHOW_HELP))
    }

    @Test fun twoModifierChordsAreOneLayerWhicheverOrderTheyAreWritten() {
        val k = Keymap.DEFAULT.with(Control.SWAP, Binding(Keys.DPAD_LEFT, setOf(Keys.BUTTON_R2, Keys.BUTTON_L2)))
        assertEquals("L2+R2+←", k.label(Control.SWAP))
        assertTrue(k.toJson().contains("\"SWAP\": \"L2+R2+LEFT\""))
        assertEquals(k, Keymap.fromJson(k.toJson()))
        assertEquals(k, Keymap.fromJson(k.toJson().replace("\"L2+R2+LEFT\"", "\"R2+L2+LEFT\"")))
        assertEquals(Control.SWAP, k.control(Binding(Keys.DPAD_LEFT, both), ViewKind.PATTERN))
        // The one-trigger layers stay separate from it.
        assertNull(k.control(Binding(Keys.DPAD_LEFT, r2), ViewKind.PATTERN))
        assertTrue(k.chords(both, ViewKind.PATTERN).any { it.first == Control.SWAP })
        assertTrue(k.chords(r2, ViewKind.PATTERN).none { it.first == Control.SWAP })
        assertTrue(k.chords(l2, ViewKind.PATTERN).none { it.first == Control.SWAP })
    }

    @Test fun aChordHoldsAtMostTwoModifiersAndNeverAHeldControl() {
        val k = Keymap.DEFAULT
        assertNull(k.problem(Control.SWAP, Binding(Keys.BUTTON_X, setOf(Keys.BUTTON_SELECT, Keys.BUTTON_THUMBL))))
        assertTrue(k.problem(Control.SWAP, Binding(Keys.BUTTON_X, both + Keys.BUTTON_SELECT))!!.contains("at most two"))
        assertTrue(k.problem(Control.EDIT, Binding(Keys.BUTTON_X, both))!!.contains("single button"))
    }

    @Test fun whileTwoModifiersAreHeldTheirSingleModifierChordsArePartial() {
        val k = Keymap.DEFAULT
        assertNotNull(k.partialChord(Keys.BUTTON_A, both, ViewKind.PATTERN))    // L2+A, R2+A
        assertNull(k.partialChord(Keys.BUTTON_A, l2, ViewKind.PATTERN))         // that is L2+A's own layer
        assertNull(k.partialChord(Keys.BUTTON_A, emptySet(), ViewKind.PATTERN))
        // L2+← acts only in sound views, and R2+← nowhere.
        assertNull(k.partialChord(Keys.DPAD_LEFT, both, ViewKind.PATTERN))
        assertEquals(Control.LISTEN_OCTAVE_DOWN, k.partialChord(Keys.DPAD_LEFT, both, ViewKind.INSTRUMENT))
    }

    @Test fun anOlderKeymapReadsUnchangedAndGainsTheOtherDisplayChords() {
        /** A version 5 file (0.8.6-0.14.1), as those versions wrote it, with [change] applied. */
        fun versionFive(change: Map<String, String>): String {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(Keymap.DEFAULT.toJson()) as kotlinx.serialization.json.JsonObject
            val all = (root["bindings"] as kotlinx.serialization.json.JsonObject).toMutableMap()
            Control.entries.filter { it.since > 5 }.forEach { all.remove(it.name) }
            change.forEach { (k, v) -> all[k] = kotlinx.serialization.json.JsonPrimitive(v) }
            return kotlinx.serialization.json.JsonObject(root.toMutableMap().also {
                it["version"] = kotlinx.serialization.json.JsonPrimitive(5)
                it["bindings"] = kotlinx.serialization.json.JsonObject(all)
            }).toString()
        }
        assertEquals(Keymap.DEFAULT, Keymap.fromJson(versionFive(emptyMap())))
        val customised = Keymap.fromJson(versionFive(mapOf("UNDO" to "L3+B", "SWAP" to "R2+LEFT")))
        assertEquals(Binding(Keys.BUTTON_B, l3), customised.binding(Control.UNDO))
        assertEquals(Binding(Keys.DPAD_LEFT, r2), customised.binding(Control.SWAP))
        for (c in Control.entries.filter { it.since == 6 }) assertEquals(c.name, Keymap.DEFAULT.binding(c), customised.binding(c))
        // Written back, it is a current file that reads the same.
        assertEquals(customised, Keymap.fromJson(customised.toJson()))
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
            file("\"UNDO\":\"L2+L2+A\"") to "not a key",           // a modifier named twice
            file("\"UNDO\":\"SELECT+L2+R2+A\"") to "at most two",  // three modifiers
            file("\"AUDITION\":\"L2+R2+X\"") to "single button",   // held control in the third layer
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
        assertEquals(Control.EDIT, k.stranded(Control.AUDITION, Binding(Keys.BUTTON_A)))
        assertNull(k.stranded(Control.REDO, Binding(Keys.BUTTON_X, l2))) // Swap is not essential
    }

    @Test fun legacyRemapsMoveEveryBindingThatUsedTheRemappedButtons() {
        // 0.4 remap: physical B acts as A and physical A acts as B.
        val k = Keymap.fromLegacyRemap(mapOf(Keys.BUTTON_B to Keys.BUTTON_A, Keys.BUTTON_A to Keys.BUTTON_B))
        assertEquals(Binding(Keys.BUTTON_B), k.binding(Control.EDIT))
        assertEquals(Binding(Keys.BUTTON_A), k.binding(Control.CLEAR))
        assertEquals(Binding(Keys.BUTTON_B, l2), k.binding(Control.UNDO))
        assertEquals(Binding(Keys.BUTTON_B, sel), k.binding(Control.PASTE))
        assertEquals(Keymap.DEFAULT.binding(Control.COPY), k.binding(Control.COPY))
        assertEquals(Keymap.DEFAULT, Keymap.fromLegacyRemap(emptyMap()))
    }

    @Test fun legacyRemapsMoveBothModifiersOfAChord() {
        // A 0.4 remap that swapped the triggers: each one-trigger chord moves, the two-trigger layer stays itself.
        val k = Keymap.fromLegacyRemap(mapOf(Keys.BUTTON_L2 to Keys.BUTTON_R2, Keys.BUTTON_R2 to Keys.BUTTON_L2))
        assertEquals(Binding(Keys.BUTTON_A, r2), k.binding(Control.UNDO))
        assertEquals(Binding(Keys.BUTTON_A, l2), k.binding(Control.SHOW_SONG))
        assertEquals(Binding(Keys.BUTTON_A, both), k.binding(Control.OTHER_SONG))
    }

    @Test fun menusFollowReboundConfirmAndBack() {
        val k = Keymap.DEFAULT.with(Control.EDIT, Binding(Keys.BUTTON_Y)).with(Control.MARK, Binding(Keys.BUTTON_A))
        assertEquals(Keys.BUTTON_A, k.canonical(Keys.BUTTON_Y))
        assertEquals(Keys.UNKNOWN, k.canonical(Keys.BUTTON_A))
        assertEquals(Keys.ENTER, k.canonical(Keys.ENTER))
        assertEquals("Y+←→", k.held(Action.EDIT, k.pair(Action.LEFT, Action.RIGHT)))
    }
}
