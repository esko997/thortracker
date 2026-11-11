package org.thortracker.system.panel

import org.thortracker.editor.ViewController

/** Presentation only: a musical face and its precise editor share one instrument and one document history. */
data class MusicalPanelState(
    var advanced: Boolean = false,
    var soundField: Int = 2,
    var advancedField: Int = 2,
    var revision: Int = 0,
    var editBlocked: Boolean = false,
) {
    /** Pointer ownership belongs to a live painter, never a copied workspace. */
    internal var cancelTouch: (() -> Unit)? = null

    fun invalidate() {
        cancelTouch?.invoke()
        revision++
    }
}

internal object MusicalPanelMode {
    /** Horizontal Sound navigation: mode, name, then musical controls; slot selection is vertical. */
    fun cycleSoundField(v: ViewController, panel: MusicalPanelState, controlCount: Int, delta: Int) {
        val st = v.state
        val last = controlCount + 1
        val current = if (st.instField < 0) 0 else st.instField.coerceIn(1, last)
        val next = (current + delta).mod(last + 1)
        if (st.instField >= 1) panel.soundField = st.instField
        st.instField = if (next == 0) -1 else next
        st.textPos = 0
    }

    /** Field -1 is the mode selector. Switching must not turn a still-held Edit into an edit in the other face. */
    fun select(v: ViewController, panel: MusicalPanelState, advanced: Boolean, keepModeFocus: Boolean = false) {
        if (panel.advanced == advanced) return
        panel.invalidate()
        if (v.state.instField >= 0) {
            if (panel.advanced) panel.advancedField = v.state.instField else panel.soundField = v.state.instField
        }
        panel.advanced = advanced
        v.state.instField = if (keepModeFocus) -1 else if (advanced) panel.advancedField else panel.soundField
        if (v.editHeld) { v.useEdit(); panel.editBlocked = true }
        if (v.clearHeld) v.useClear()
    }
}
