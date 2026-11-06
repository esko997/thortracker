package org.thortracker.editor

/**
 * The views a frontend shows and which one the controller drives. Each pane is a [ViewState] (a view kind with its
 * own cursor and scroll). Where panes appear is the frontend's layout: on the Thor one pane per display, on a single
 * screen one pane at a time. A layout may later show two panes side by side on a large screen; nothing here assumes
 * a pane owns a display. Persistence and drawing stay with the frontend.
 */
class Workspace(panes: List<ViewState>, focus: Int = 0) {
    init { require(panes.isNotEmpty()) { "A workspace needs at least one pane" } }

    /** Pane order is layout order: pane 0 is the main pane (the Thor's top display). */
    val panes: MutableList<ViewState> = panes.toMutableList()

    /** The pane the controller drives. */
    var focus: Int = focus.coerceIn(0, panes.size - 1)
        set(value) { field = value.coerceIn(0, panes.size - 1) }

    val focused: ViewState get() = panes[focus]

    /** What each pane showed before a view chord replaced it (view and whether it was pinned), for the second press. */
    private val beforeChord = HashMap<Int, Pair<ViewKind, Boolean>>()

    /**
     * A view chord: shows [kind] in the focused pane and pins it there, like choosing it from a view menu. Pressed
     * again while that view is showing, it returns to the view it replaced, pinned or following the selection as it
     * was. Returns false when nothing changed (the view is showing and no earlier view is remembered).
     */
    fun showView(kind: ViewKind): Boolean {
        val state = focused
        if (state.kind == kind) {
            val (previous, pinned) = beforeChord.remove(focus) ?: return false
            state.switchKind(previous)
            state.pinned = pinned
        } else {
            beforeChord[focus] = state.kind to state.pinned
            state.switchKind(kind)
            state.pinned = true
        }
        return true
    }

    /** Exchanges two panes' complete views (cursors, scroll and view-chord memory travel with them). */
    fun swap(a: Int = 0, b: Int = 1) {
        if (a !in panes.indices || b !in panes.indices || a == b) return
        val first = panes[a]
        panes[a] = panes[b]
        panes[b] = first
        val beforeA = beforeChord.remove(a)
        beforeChord.remove(b)?.let { beforeChord[a] = it }
        beforeA?.let { beforeChord[b] = it }
    }

    /**
     * Where transport and order-scoped commands act: the focused pane's own place if it is a Pattern view (two
     * pattern panes may sit at different places), otherwise the shared selection.
     */
    fun activePlace(selection: Selection): Pair<Int, Int> {
        val state = focused
        return if (state.kind == ViewKind.PATTERN) state.order to state.row else selection.order to selection.row
    }
}
