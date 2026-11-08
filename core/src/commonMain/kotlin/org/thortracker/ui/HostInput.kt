package org.thortracker.ui

import org.thortracker.editor.Action
import org.thortracker.editor.ViewKind
import org.thortracker.input.Binding
import org.thortracker.input.Control
import org.thortracker.input.Keymap
import org.thortracker.input.Keys
import org.thortracker.input.StickFlicks

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
 *
 * The right stick's vertical axis ([rightStick]) is not a key: a quick double flick up or down jumps the focused
 * Pattern view to the first or last row of its pattern.
 */
class HostInput(var keymap: Keymap = Keymap.DEFAULT) {
    enum class Target { NONE, POPUP, TOOLBAR }

    sealed interface Route {
        /** Consumed; [code] is the canonical key for a POPUP/TOOLBAR target. */
        data class Deliver(val target: Target, val code: Int = Keys.UNKNOWN) : Route
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
    /** One flick detector per controller, so two devices (or a phantom duplicate) never mix their samples. */
    private val sticks = HashMap<Int, StickFlicks>()
    private var stickPane = -1
    private var stickView: ViewKind? = null

    /** Forgets every held key and pending stick flick, e.g. on pause, when matching key-ups may never arrive. */
    fun reset() {
        owners.clear()
        held.clear()
        resetStick()
    }

    /**
     * Forgets pending right-stick flicks (a stick held now must return to neutral before it counts). Hosts call
     * this when a popup opens or closes, the window loses focus or a controller changes, so a flick from before
     * cannot complete a jump after.
     */
    fun resetStick() = sticks.values.forEach { it.reset() }

    /** A controller [device] went away or was reconfigured: drop its stick state. */
    fun forgetStick(device: Int) { sticks.remove(device) }

    /**
     * One sample of [device]'s right-stick vertical axis ([y] -1 up .. 1 down) at monotonic [millis]. Returns -1
     * to jump to the first row of the pattern, 1 to its last row, 0 otherwise: only a quick double flick the
     * same way with neutral between ([StickFlicks]), while [pane] shows a [patternFocused] Pattern view
     * (controller focus, no panel) and neither a popup nor the toolbar has the controller. Anything else, a
     * change of focused pane or view, or a button press ([route]) in between forgets the first flick.
     */
    fun rightStick(device: Int, y: Float, millis: Long, pane: Int, view: ViewKind, patternFocused: Boolean,
                   popupOpen: Boolean, toolbarFocused: Boolean): Int {
        if (pane != stickPane || view != stickView) { stickPane = pane; stickView = view; resetStick() }
        val flicks = sticks.getOrPut(device) { StickFlicks() }
        val jump = flicks.sample(y, millis)
        if (!patternFocused || view != ViewKind.PATTERN || popupOpen || toolbarFocused) { resetStick(); return 0 }
        return jump
    }

    /** Modifiers currently held, in press order; the focused view shows their chords. */
    val heldModifiers: Set<Int> get() = held

    fun route(code: Int, down: Boolean, repeat: Int, popupOpen: Boolean, toolbarFocused: Boolean, view: ViewKind): Route {
        // A button may open a menu, the toolbar or another view, so it ends any stick double flick; D-pad moves don't.
        if (down && repeat == 0 && code !in DIRECTIONS) resetStick()
        if (code in Keymap.MODIFIERS) {
            if (down) held += code else held -= code
            return Route.Deliver(Target.NONE)
        }
        if (!down || repeat > 0) return continued(code, down, popupOpen, toolbarFocused, view)
        when (code) {
            Keys.MENU, Keys.BUTTON_MODE ->
                return if (popupOpen) deliver(code, Owner.Popup(code)) else { owners[code] = Owner.Global; Route.Menu }
            Keys.BACK, Keys.ESCAPE -> return when {
                popupOpen -> deliver(code, Owner.Popup(code))
                toolbarFocused -> deliver(code, Owner.Toolbar(code))
                else -> { owners[code] = Owner.Global; Route.Menu }
            }
        }
        val chords = held.mapNotNull { keymap.control(Binding(code, it), view) }
        if (chords.isNotEmpty() && held.size > 1) return deliver(code, Owner.Global)
        val control = chords.firstOrNull() ?: keymap.control(Binding(code), view)
            ?: if (code == Keys.SPACE) Control.PLAY_PATTERN else null
        if (popupOpen) {
            if (control != null && control.overPopups && chords.isNotEmpty()) return run(code, control)
            // Any other chord (e.g. R2+A for a view) must not press the popup's highlighted item.
            if (chords.isNotEmpty()) return deliver(code, Owner.Global)
            return deliver(code, Owner.Popup(keymap.canonical(code)))
        }
        if (control == null) return if (isController(code)) deliver(code, Owner.Global) else Route.Pass
        if (control.host) return run(code, control)
        if (toolbarFocused) return deliver(code, Owner.Toolbar(if (chords.isEmpty()) keymap.canonical(code) else Keys.UNKNOWN))
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
        private val DIRECTIONS = setOf(Keys.DPAD_UP, Keys.DPAD_DOWN, Keys.DPAD_LEFT, Keys.DPAD_RIGHT)

        /** Gamepad buttons and the D-pad: consumed even when unbound, so they never trigger system fallbacks. */
        fun isController(code: Int): Boolean =
            code in Keys.DPAD_UP..Keys.DPAD_CENTER ||
                code in Keys.BUTTON_A..Keys.BUTTON_MODE ||
                code in Keys.BUTTON_1..Keys.BUTTON_16
    }
}
