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
 * Modifiers ([Keymap.MODIFIERS]: Select, L2, R2, L3) do nothing alone. The modifiers held at a press form its
 * layer, whatever order they went down in: a press runs the chord bound to exactly that layer (L2+R2+A while both
 * triggers are held). Failing that, a chord of only some of them (L2+A or R2+A while both are held) belongs to a
 * layer below and does nothing, so a third-layer press never slips into a single-trigger action; a key no held
 * layer binds keeps its own binding. Menu and Back are fixed: they open the menu (Back leaves a focused toolbar or
 * popup first). Popups and the toolbar receive the canonical key a press stands for ([Keymap.canonical]), so
 * their navigation follows rebinding; [Control.overPopups] chords still run over popups. Bindings resolve in
 * the focused [ViewKind], so a chord can act differently per view ([Control.views]).
 *
 * Releases roll: a chord keeps the meaning of its DOWN however its modifiers and button are let go, and a key held
 * since before a modifier went down keeps its own meaning until released. A press the system cancels ([cancel])
 * is forgotten without acting.
 *
 * The right stick ([rightStick]) is not a key: double up/down jumps to the first/last Pattern row;
 * double left/right jumps to the first/last channel's note. Each axis detects flicks independently.
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
    /** Independent axes per controller: a diagonal can complete both jumps, never half of one on each axis. */
    private class StickAxes {
        val horizontal = StickFlicks()
        val vertical = StickFlicks()
        fun reset() { horizontal.reset(); vertical.reset() }
    }
    private val sticks = HashMap<Int, StickAxes>()
    private var stickPane = -1
    private var stickView: ViewKind? = null

    /** Forgets every held key and pending stick flick, e.g. on pause, when matching key-ups may never arrive. */
    fun reset() {
        owners.clear()
        held.clear()
        resetStick()
    }

    /**
     * Keeps [heldModifiers] true to the hardware for a key that does not go through [route], because a binding
     * capture takes every key. Returns whether [code] is a modifier.
     */
    fun trackModifier(code: Int, down: Boolean): Boolean {
        if (code !in Keymap.MODIFIERS) return false
        if (down) held += code else held -= code
        return true
    }

    /**
     * The system cancelled [code]'s press (Android flags the key-up canceled when another window takes focus or
     * the controller goes away). Forgets it without acting: a modifier stops being held and a press loses its
     * owner. Returns true when the press was driving the editor: the host must then cancel the editor's holds
     * rather than release them, since a release could count as a tap and write.
     */
    fun cancel(code: Int): Boolean {
        if (trackModifier(code, false)) return false
        return owners.remove(code) is Owner.Editor
    }

    /**
     * Like [cancel], for one of several input sources going away while others stay held: forgets [code]'s
     * press without acting. Returns the editor action whose hold the host must cancel alone
     * ([org.thortracker.editor.ViewController.cancelHold]), or null when the press drove no editor action or
     * another held key (or chord) still drives the same action.
     */
    fun forget(code: Int): Action? {
        if (trackModifier(code, false)) return null
        val action = (owners.remove(code) as? Owner.Editor)?.action ?: return null
        return action.takeIf { owners.values.none { it is Owner.Editor && it.action == action } }
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
     * One sample of [device]'s right-stick axis ([value] -1 left/up .. 1 right/down) at monotonic [millis].
     * Returns -1 for the first channel/row, 1 for the last, 0 otherwise. [horizontal] selects an independent
     * detector, so diagonal double flicks may complete both axes. Only a quick double flick the same way
     * with neutral between ([StickFlicks]) acts, while [pane] shows a [patternFocused] Pattern view
     * (controller focus, no panel) and neither a popup nor the toolbar has the controller. Anything else, a
     * change of focused pane or view, or a button press ([route]) in between forgets the first flick.
     */
    fun rightStick(device: Int, horizontal: Boolean, value: Float, millis: Long, pane: Int, view: ViewKind, patternFocused: Boolean,
                   popupOpen: Boolean, toolbarFocused: Boolean): Int {
        if (pane != stickPane || view != stickView) { stickPane = pane; stickView = view; resetStick() }
        val axes = sticks.getOrPut(device) { StickAxes() }
        val jump = (if (horizontal) axes.horizontal else axes.vertical).sample(value, millis)
        if (!patternFocused || view != ViewKind.PATTERN || popupOpen || toolbarFocused) { resetStick(); return 0 }
        return jump
    }

    /** Modifiers currently held, in press order; the focused view shows the chords of exactly this layer. */
    val heldModifiers: Set<Int> get() = held

    fun route(code: Int, down: Boolean, repeat: Int, popupOpen: Boolean, toolbarFocused: Boolean, view: ViewKind): Route {
        // A button may open a menu, the toolbar or another view, so it ends any stick double flick; D-pad moves don't.
        if (down && repeat == 0 && code !in DIRECTIONS) resetStick()
        if (trackModifier(code, down)) return Route.Deliver(Target.NONE)
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
        val chord = if (held.isEmpty()) null else keymap.control(Binding(code, held), view)
        // A chord of a layer below the held one (L2+A while L2+R2 are held) must not run, nor its bare key act.
        if (chord == null && held.size > 1 && keymap.partialChord(code, held, view) != null) return deliver(code, Owner.Global)
        val control = chord ?: keymap.control(Binding(code), view)
            ?: if (code == Keys.SPACE) Control.PLAY_PATTERN else null
        if (popupOpen) {
            if (chord != null && chord.overPopups) return run(code, chord)
            // Any other chord (e.g. R2+A for a view) must not press the popup's highlighted item.
            if (chord != null) return deliver(code, Owner.Global)
            return deliver(code, Owner.Popup(keymap.canonical(code)))
        }
        if (control == null) return if (isController(code)) deliver(code, Owner.Global) else Route.Pass
        if (control.host) return run(code, control)
        if (toolbarFocused) return deliver(code, Owner.Toolbar(if (chord == null) keymap.canonical(code) else Keys.UNKNOWN))
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
