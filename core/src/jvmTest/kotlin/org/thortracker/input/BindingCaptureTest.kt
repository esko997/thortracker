package org.thortracker.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BindingCaptureTest {
    private val both = setOf(Keys.BUTTON_L2, Keys.BUTTON_R2)

    @Test fun bothTriggersCaptureOneChordWhicheverWentDownFirst() {
        for (order in listOf(listOf(Keys.BUTTON_L2, Keys.BUTTON_R2), listOf(Keys.BUTTON_R2, Keys.BUTTON_L2))) {
            val capture = BindingCapture()
            order.forEach { assertNull(capture.key(it, true)) }
            assertEquals(order.toString(), Binding(Keys.BUTTON_A, both), capture.key(Keys.BUTTON_A, true))
        }
    }

    @Test fun aModifierLetGoBeforeTheButtonIsNotPartOfTheChord() {
        val capture = BindingCapture()
        capture.key(Keys.BUTTON_L2, true)
        capture.key(Keys.BUTTON_R2, true)
        capture.key(Keys.BUTTON_L2, false)
        assertEquals(Binding(Keys.BUTTON_X, setOf(Keys.BUTTON_R2)), capture.key(Keys.BUTTON_X, true))
    }

    @Test fun modifierPressesAndReleasesAloneCaptureNothing() {
        val capture = BindingCapture()
        for (code in listOf(Keys.BUTTON_L2, Keys.BUTTON_R2)) assertNull(capture.key(code, true))
        for (code in listOf(Keys.BUTTON_R2, Keys.BUTTON_L2)) assertNull(capture.key(code, false))
        assertNull(capture.key(Keys.BUTTON_A, false))   // a release left over from choosing the row
        assertEquals(Binding(Keys.BUTTON_A), capture.key(Keys.BUTTON_A, true))
    }

    @Test fun threeHeldModifiersAreCapturedForTheKeymapToRefuse() {
        val capture = BindingCapture()
        for (code in listOf(Keys.BUTTON_SELECT, Keys.BUTTON_L2, Keys.BUTTON_R2)) capture.key(code, true)
        val binding = capture.key(Keys.BUTTON_A, true)!!
        assertEquals(both + Keys.BUTTON_SELECT, binding.modifiers)
        assertTrue(Keymap.DEFAULT.problem(Control.SWAP, binding)!!.contains("at most two"))
    }
}
