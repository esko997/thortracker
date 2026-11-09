package org.thortracker.input

/**
 * Reads one binding from real presses for Menu → Controls: the first non-modifier button that goes down, with the
 * modifiers ([Keymap.MODIFIERS]) held at that moment as its chord. Modifiers may go down in any order and be let go
 * again before the button; only those still held count. Whether the result is allowed is [Keymap.problem]'s call.
 */
class BindingCapture {
    private val held = LinkedHashSet<Int>()

    /** One key press or release (no repeats). Returns the captured binding when a button goes down, else null. */
    fun key(code: Int, down: Boolean): Binding? {
        if (code in Keymap.MODIFIERS) {
            if (down) held += code else held -= code
            return null
        }
        return if (down) Binding(code, held.toSet()) else null
    }
}
