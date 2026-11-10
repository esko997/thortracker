package org.thortracker.input

/** Merge hardware devices and the virtual dock before HostInput sees key edges. */
class PreviewKeyOwners {
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
}

/** Pointer IDs, not event indices, own buttons until lifted. Latches are explicit, persistent layers. */
class PreviewPointers(
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
