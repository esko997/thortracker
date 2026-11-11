package org.thortracker.input

import android.view.InputDevice
import org.thortracker.editor.Action
import org.thortracker.ui.HostInput

/** Merge hardware devices and the virtual dock before HostInput sees key edges. */
class TouchKeyOwners {
    enum class Edge { DOWN, REPEAT, UP, NONE }
    private val owners = HashMap<Int, MutableSet<Int>>()

    fun change(source: Int, code: Int, down: Boolean, repeat: Int = 0): Edge {
        val held = owners[code]
        if (down) {
            if (repeat > 0) return if (held?.contains(source) == true) Edge.REPEAT else Edge.NONE
            if (held != null) { held.add(source); return Edge.NONE }
            owners[code] = mutableSetOf(source)
            return Edge.DOWN
        }
        if (held == null || !held.remove(source)) return Edge.NONE
        if (held.isNotEmpty()) return Edge.NONE
        owners.remove(code)
        return Edge.UP
    }

    /** Cancellation deliberately emits no UP: an editor may interpret UP as a write. */
    fun cancel() = owners.clear()

    fun holds(source: Int) = owners.values.any { source in it }

    /**
     * [source] went away mid-gesture (the dock was hidden): forget only its presses, never as releases.
     * A key another source still holds keeps its owner and meaning, so its own release still acts. Each key
     * only [source] held is forgotten by [router] ([HostInput.forget]); [cancelHold] receives every editor
     * action no surviving key still drives, so other sources' holds and their audition keep going.
     */
    fun forget(source: Int, router: HostInput, cancelHold: (Action) -> Unit) {
        val orphaned = ArrayList<Int>()
        val entries = owners.entries.iterator()
        while (entries.hasNext()) {
            val (code, held) = entries.next()
            if (held.remove(source) && held.isEmpty()) { entries.remove(); orphaned += code }
        }
        for (code in orphaned) router.forget(code)?.let(cancelHold)
    }
}

/** The dock hides while any usable physical controller is attached, including a handheld's built-in pad. */
object TouchControllers {
    /**
     * A device that can drive the default controls: an enabled, non-virtual gamepad with A and B, and
     * directions from a D-pad or a stick/hat (Android turns a hat into D-pad keys). A keyboard, D-pad remote or
     * headset button is not one, even when its keys reach [HostInput.isController]. [hasKeys] is
     * [InputDevice.hasKeys]; [enabled] is true below API 27, which cannot disable input devices.
     */
    fun usable(virtual: Boolean, enabled: Boolean, sources: Int, hasKeys: (IntArray) -> BooleanArray): Boolean {
        if (virtual || !enabled) return false
        fun has(source: Int) = sources and source == source
        if (!has(InputDevice.SOURCE_GAMEPAD)) return false
        if (!has(InputDevice.SOURCE_DPAD) && !has(InputDevice.SOURCE_JOYSTICK)) return false
        return hasKeys(intArrayOf(Keys.BUTTON_A, Keys.BUTTON_B)).all { it }
    }
}

/** Pointer IDs, not event indices, own buttons until lifted. Latches are explicit, persistent layers. */
class TouchPointers(
    private val edge: (code: Int, down: Boolean) -> Unit,
) {
    private val pointers = HashMap<Int, Int>()
    private val latches = LinkedHashSet<Int>()
    private val held = HashMap<Int, Int>()
    fun latched(code: Int) = code in latches
    fun pressed(code: Int) = code in held || code in latches

    fun down(pointer: Int, code: Int, latch: Boolean) {
        if (pointer in pointers) return
        pointers[pointer] = code
        if (latch) {
            if (latches.remove(code)) edge(code, false)
            else { latches.add(code); edge(code, true) }
        } else {
            val count = held[code] ?: 0
            held[code] = count + 1
            if (count == 0) edge(code, true)
        }
    }

    fun up(pointer: Int, isLatch: (Int) -> Boolean) {
        val code = pointers.remove(pointer) ?: return
        if (isLatch(code)) return
        val count = held[code] ?: return
        if (count > 1) held[code] = count - 1
        else { held.remove(code); edge(code, false) }
    }

    /** Host also cancels HostInput and editor holds. No synthetic release/tap on cancellation. */
    fun cancel() { pointers.clear(); latches.clear(); held.clear() }
}
