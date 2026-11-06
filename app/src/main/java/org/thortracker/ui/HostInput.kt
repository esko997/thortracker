package org.thortracker.ui

import android.view.KeyEvent
import org.thortracker.editor.Action
import org.thortracker.editor.ViewKind
import org.thortracker.input.Binding
import org.thortracker.input.Control
import org.thortracker.input.Keymap

/**
 * Host-level controller routing over physical key codes and the user's [keymap]. Decides, per press, which
 * layer owns it: an open in-app popup, the revealed shell toolbar, a host control (history, transport,
 * displays), or the focused editor. A press keeps the owner and meaning of its first DOWN for its repeats and
 * its UP, so a key whose DOWN was consumed (chord, popup choice, toolbar press) never leaks a repeat or
 * release into the editor, and an editor key held while a modal opens cannot keep editing underneath it.
 *
 * Modifiers ([Keymap.MODIFIERS]: Select, L2, R2, L3) do nothing alone. With one held, a press uses its chord
 * if one is bound, otherwise the key's own binding. With several held, a key bound in any of their chords is
 * ambiguous and does nothing. Menu and Back are fixed: they open the menu (Back leaves a focused toolbar or
 * popup first). Popups and the toolbar receive the canonical key a press stands for ([Keymap.canonical]), so
 * their navigation follows rebinding; [Control.overPopups] chords still run over popups. Bindings resolve in
 * the focused [ViewKind], so a chord can act differently per view ([Control.views]).
 */
class HostInput(var keymap: Keymap = Keymap.DEFAULT) {
    enum class Target { NONE, POPUP, TOOLBAR }

    sealed interface Route {
        /** Consumed; [code] is the canonical key for a POPUP/TOOLBAR target. */
        data class Deliver(val target: Target, val code: Int = KeyEvent.KEYCODE_UNKNOWN) : Route
        /** A host control's press. */
        data class Run(val control: Control) : Route
        /** Fixed menu key. */
        data object Menu : Route
        /** An editor action press, repeat or release for the focused view. */
        data class Editor(val action: Action) : Route
        /** Not a controller key: let the system handle it (volume and similar). */
        data object Pass : Route
    }

    private sealed interface Owner {
        data object Global : Owner
        data class Popup(val code: Int) : Owner
        data class Toolbar(val code: Int) : Owner
        data class Editor(val action: Action) : Owner
    }

    private val owners = HashMap<Int, Owner>()
    private val held = LinkedHashSet<Int>()

    /** Forgets every held key, e.g. on pause, when matching key-ups may never arrive. */
    fun reset() {
        owners.clear()
        held.clear()
    }

    /** Modifiers currently held, in press order; the focused view shows their chords. */
    val heldModifiers: Set<Int> get() = held

    fun route(code: Int, down: Boolean, repeat: Int, popupOpen: Boolean, toolbarFocused: Boolean, view: ViewKind): Route {
        if (code in Keymap.MODIFIERS) {
            if (down) held += code else held -= code
            return Route.Deliver(Target.NONE)
        }
        if (!down || repeat > 0) return continued(code, down, popupOpen, toolbarFocused, view)
        when (code) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_MODE ->
                return if (popupOpen) deliver(code, Owner.Popup(code)) else { owners[code] = Owner.Global; Route.Menu }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> return when {
                popupOpen -> deliver(code, Owner.Popup(code))
                toolbarFocused -> deliver(code, Owner.Toolbar(code))
                else -> { owners[code] = Owner.Global; Route.Menu }
            }
        }
        val chords = held.mapNotNull { keymap.control(Binding(code, it), view) }
        if (chords.isNotEmpty() && held.size > 1) return deliver(code, Owner.Global)
        val control = chords.firstOrNull() ?: keymap.control(Binding(code), view)
            ?: if (code == KeyEvent.KEYCODE_SPACE) Control.PLAY_PATTERN else null
        if (popupOpen) {
            if (control != null && control.overPopups && chords.isNotEmpty()) return run(code, control)
            // Any other chord (e.g. R2+A for a view) must not press the popup's highlighted item.
            if (chords.isNotEmpty()) return deliver(code, Owner.Global)
            return deliver(code, Owner.Popup(keymap.canonical(code)))
        }
        if (control == null) return if (isController(code)) deliver(code, Owner.Global) else Route.Pass
        if (control.host) return run(code, control)
        if (toolbarFocused) return deliver(code, Owner.Toolbar(if (chords.isEmpty()) keymap.canonical(code) else KeyEvent.KEYCODE_UNKNOWN))
        val action = control.action ?: return deliver(code, Owner.Global)
        owners[code] = Owner.Editor(action)
        return Route.Editor(action)
    }

    /** Repeats and releases follow the owner of the press. */
    private fun continued(code: Int, down: Boolean, popupOpen: Boolean, toolbarFocused: Boolean, view: ViewKind): Route {
        val owner = if (down) owners[code] else owners.remove(code)
        return when (owner) {
            Owner.Global -> Route.Deliver(Target.NONE)
            is Owner.Popup -> if (popupOpen) Route.Deliver(Target.POPUP, owner.code) else Route.Deliver(Target.NONE)
            is Owner.Toolbar -> if (toolbarFocused && !popupOpen) Route.Deliver(Target.TOOLBAR, owner.code) else Route.Deliver(Target.NONE)
            // A release always reaches the editor so it can drop its hold; repeats stop under a modal.
            is Owner.Editor -> if (!down || (!popupOpen && !toolbarFocused)) Route.Editor(owner.action) else Route.Deliver(Target.NONE)
            null -> when {
                down -> Route.Deliver(Target.NONE)
                popupOpen -> Route.Deliver(Target.POPUP, keymap.canonical(code))
                else -> keymap.control(Binding(code), view)?.action?.let { Route.Editor(it) }
                    ?: if (isController(code)) Route.Deliver(Target.NONE) else Route.Pass
            }
        }
    }

    private fun run(code: Int, control: Control): Route {
        owners[code] = Owner.Global
        return Route.Run(control)
    }

    private fun deliver(code: Int, owner: Owner): Route {
        owners[code] = owner
        return when (owner) {
            Owner.Global, is Owner.Editor -> Route.Deliver(Target.NONE)
            is Owner.Popup -> Route.Deliver(Target.POPUP, owner.code)
            is Owner.Toolbar -> Route.Deliver(Target.TOOLBAR, owner.code)
        }
    }

    companion object {
        /** Gamepad buttons and the D-pad: consumed even when unbound, so they never trigger system fallbacks. */
        fun isController(code: Int): Boolean =
            code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER ||
                code in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE ||
                code in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16
    }
}
