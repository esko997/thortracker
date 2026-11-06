package org.thortracker.ui

import org.thortracker.input.Keys

/**
 * One row of an in-app popup. A null [action] dismisses the popup (back one level). [value] is shown as a
 * right-hand column (e.g. a key binding). A [heading] row only labels the rows below it and is never selected.
 */
class PopupItem(
    val label: String?,
    val tone: Tone = Tone.PLAIN,
    val value: String? = null,
    val heading: Boolean = false,
    val action: (() -> Unit)? = null,
) {
    enum class Tone { PLAIN, PRIMARY, DANGER }

    companion object {
        /** The leaving row: "Back" when a parent popup is underneath, otherwise "Cancel". */
        fun dismiss() = PopupItem(null)

        fun heading(text: String) = PopupItem(text, heading = true)
    }
}

/** An editable single-line value shown above the items; [value] tracks the field. */
class PopupText(initial: String, val maxLength: Int) {
    var value: String = initial
}

/**
 * Content of one modal popup. [capture], when set, receives every physical key press and release instead of
 * popup navigation (controller rebinding). [selected] counts the text field (when present) as entry 0.
 */
class PopupSpec(
    val title: String,
    val message: String? = null,
    val items: List<PopupItem>,
    val text: PopupText? = null,
    val capture: ((code: Int, down: Boolean) -> Unit)? = null,
    selected: Int = 0,
) {
    var selected = selected
    val entries: Int get() = items.size + if (text != null) 1 else 0
    fun itemIndex(entry: Int) = entry - if (text != null) 1 else 0
    fun selectable(entry: Int) = items.getOrNull(itemIndex(entry))?.heading != true
}

/**
 * The app's modal popups: one stack shown by one display's [PopupView] (on Android, inside one host root, never a
 * Dialog, PopupWindow or other system window). Choosing an item that opens another popup keeps the chooser underneath as the
 * "Back" target; choosing a final item closes the whole stack. Popups shown while others are open
 * (asynchronous errors) stack on top and reveal what they covered when dismissed.
 */
class Popups(private val layers: (Int) -> PopupView?, private val displays: () -> Collection<Int>) {
    private val stack = ArrayList<PopupSpec>()
    private var wasOpen = false

    /** Host index currently showing the popups. */
    var display = 0
        private set

    /** Called when the stack opens or closes completely. */
    var onOpenChanged: ((Boolean) -> Unit)? = null

    val isOpen: Boolean get() = stack.isNotEmpty()
    val top: PopupSpec? get() = stack.lastOrNull()

    /** Controller bindings named in the popup's key hints. */
    var keys: org.thortracker.input.Keymap = org.thortracker.input.Keymap.DEFAULT

    /** True while the top popup's text field has keyboard focus, so typing keys must reach it. */
    val typing: Boolean get() = isOpen && layers(display)?.fieldFocused == true

    fun show(spec: PopupSpec, display: Int) {
        if (stack.isEmpty()) this.display = display
        stack += spec
        render()
    }

    /** Replaces the top popup in place (refreshed content), keeping its cursor where it was. */
    fun replaceTop(spec: PopupSpec) {
        val old = stack.removeLastOrNull() ?: return show(spec, display)
        spec.selected = old.selected.coerceIn(0, spec.entries - 1)
        stack += spec
        render()
    }

    /** Leaves the top popup, revealing the one underneath (or closing). */
    fun back() {
        if (stack.isEmpty()) return
        stack.removeAt(stack.lastIndex)
        render()
    }

    fun closeAll() {
        if (stack.isEmpty()) return
        stack.clear()
        render()
    }

    /** Cancels any pending button capture (pause, display loss); returns true if one was waiting. */
    fun dropCaptures(): Boolean {
        if (!stack.removeAll { it.capture != null }) return false
        render()
        return true
    }

    /** The host showing the popups went away: continue on the primary display. */
    fun rehome() {
        if (display != 0 && display !in displays()) { display = 0; render() }
    }

    /** Runs entry [entry] of the top popup, as tapped or pressed with A. */
    fun choose(entry: Int) {
        val spec = top ?: return
        val target = entry.coerceIn(0, spec.entries - 1)
        if (!spec.selectable(target)) return
        spec.selected = target
        val index = spec.itemIndex(spec.selected)
        if (index < 0) { layers(display)?.focusField(); render(); return }
        val action = spec.items[index].action ?: return back()
        val depth = stack.size
        action()
        // A final choice closes everything; one that opened a popup keeps its menu as the Back target.
        if (stack.size <= depth && stack.lastOrNull() === spec) stack.clear()
        render()
    }

    /** Controller and keyboard input for the top popup (logical, remapped [code]). */
    fun handleKey(code: Int, down: Boolean, repeat: Int): Boolean {
        val spec = top ?: return false
        val layer = layers(display)
        val typing = layer?.fieldFocused == true
        val fresh = down && repeat == 0
        when (code) {
            Keys.DPAD_UP, Keys.DPAD_DOWN -> {
                if (down) move(spec, if (code == Keys.DPAD_UP) -1 else 1)
            }
            Keys.DPAD_LEFT, Keys.DPAD_RIGHT -> if (typing) return false
            Keys.BUTTON_L1, Keys.PAGE_UP -> if (down) layer?.page(-1)
            Keys.BUTTON_R1, Keys.PAGE_DOWN -> if (down) layer?.page(1)
            Keys.BUTTON_A, Keys.DPAD_CENTER -> if (fresh) choose(spec.selected)
            Keys.ENTER, Keys.NUMPAD_ENTER -> if (fresh) {
                // Enter in the text field submits with the first (primary) item.
                if (spec.text != null && spec.selected == 0) choose(1) else choose(spec.selected)
            }
            Keys.BUTTON_B, Keys.BACK, Keys.ESCAPE -> if (fresh) back()
            Keys.BUTTON_MODE, Keys.MENU -> if (fresh) closeAll()
            else -> if (typing || !(Keys.isGamepadButton(code) || code == Keys.SPACE)) return false
        }
        return true
    }

    private fun move(spec: PopupSpec, delta: Int) {
        var next = (spec.selected + delta).coerceIn(0, spec.entries - 1)
        while (!spec.selectable(next) && next + delta in 0 until spec.entries) next += delta
        val layer = layers(display)
        if (next == spec.selected || !spec.selectable(next)) { layer?.page(delta); return }
        spec.selected = next
        layer?.select(next)
    }

    private fun render() {
        val open = stack.isNotEmpty()
        var target = layers(display)
        if (open && target == null) { display = 0; target = layers(0) }
        for (index in displays()) layers(index)?.takeIf { it !== target || !open }?.hide()
        if (open) target?.show(stack.last(), stack.size > 1, this)
        if (open != wasOpen) { wasOpen = open; onOpenChanged?.invoke(open) }
    }
}

/** Shows the popup stack on one display. Android uses real Views (accessible, with a system text field). */
interface PopupView {
    /** True while the text field has keyboard focus. */
    val fieldFocused: Boolean
    fun show(spec: PopupSpec, hasParent: Boolean, popups: Popups)
    fun hide()
    /** Moves the visible cursor to [entry] and scrolls it into view. */
    fun select(entry: Int)
    fun page(direction: Int)
    /** Gives the text field keyboard focus (and a soft keyboard where there is one). */
    fun focusField()
}
