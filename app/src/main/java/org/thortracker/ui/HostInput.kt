package org.thortracker.ui

import android.view.KeyEvent

/**
 * Host-level controller routing over logical (already remapped) key codes. Decides, per press, which
 * layer owns it: an open in-app popup, the revealed shell toolbar, a host chord/global command, or the
 * focused editor. A press keeps the owner of its first DOWN for its repeats and its UP, so a key whose
 * DOWN was consumed (chord constituent, popup choice, toolbar press) never leaks a repeat or release
 * into the editor, and an editor key held while a modal opens cannot keep editing underneath it.
 *
 * Chords: L2, R2 and L3 are pure triggers and do nothing alone. L2+A undo, R2+A redo (both triggers
 * held: nothing), L2+X swap complete views, R2+X nothing. Start toggles playback from the current
 * order's first row, L3+Start from the cursor, Select+Start reveals/focuses or returns from the
 * toolbar. Select still reaches the editor, where it is the copy/paste modifier.
 */
class HostInput {
    enum class Command { UNDO, REDO, SWAP, TOOLBAR, PLAY_PATTERN, PLAY_CURSOR, FOCUS, MENU }

    enum class Target { NONE, POPUP, TOOLBAR, EDITOR }

    sealed interface Route {
        data class Deliver(val target: Target) : Route
        data class Run(val command: Command) : Route
    }

    private enum class Owner { GLOBAL, POPUP, TOOLBAR, EDITOR }

    private val owners = HashMap<Int, Owner>()
    private var l2 = false
    private var r2 = false
    private var l3 = false
    private var select = false

    /** Forgets every held key, e.g. on pause, when matching key-ups may never arrive. */
    fun reset() {
        owners.clear()
        l2 = false; r2 = false; l3 = false; select = false
    }

    fun route(code: Int, down: Boolean, repeat: Int, popupOpen: Boolean, toolbarFocused: Boolean): Route {
        if (!down) when (code) {
            KeyEvent.KEYCODE_BUTTON_L2 -> l2 = false
            KeyEvent.KEYCODE_BUTTON_R2 -> r2 = false
            KeyEvent.KEYCODE_BUTTON_THUMBL -> l3 = false
            KeyEvent.KEYCODE_BUTTON_SELECT -> select = false
        }
        if (!down || repeat > 0) {
            val owner = if (down) owners[code] else owners.remove(code)
            return Route.Deliver(when (owner) {
                Owner.GLOBAL -> Target.NONE
                Owner.POPUP -> if (popupOpen) Target.POPUP else Target.NONE
                Owner.TOOLBAR -> if (toolbarFocused && !popupOpen) Target.TOOLBAR else Target.NONE
                // A release always reaches the editor so it can drop its hold; repeats stop under a modal.
                Owner.EDITOR -> if (!down || (!popupOpen && !toolbarFocused)) Target.EDITOR else Target.NONE
                null -> when {
                    down || isTrigger(code) -> Target.NONE
                    popupOpen -> Target.POPUP
                    else -> Target.EDITOR
                }
            })
        }
        if (isTrigger(code)) {
            when (code) {
                KeyEvent.KEYCODE_BUTTON_L2 -> l2 = true
                KeyEvent.KEYCODE_BUTTON_R2 -> r2 = true
                else -> l3 = true
            }
            owners[code] = Owner.GLOBAL
            return Route.Deliver(Target.NONE)
        }
        if (code == KeyEvent.KEYCODE_BUTTON_SELECT) select = true
        val triggerChord = (l2 || r2) &&
            (code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_BUTTON_X)
        if (popupOpen && !triggerChord) return deliver(code, Owner.POPUP, Target.POPUP)
        var suppressed = false
        val command = when (code) {
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_SPACE -> when {
                select -> Command.TOOLBAR
                l3 -> Command.PLAY_CURSOR
                else -> Command.PLAY_PATTERN
            }
            KeyEvent.KEYCODE_BUTTON_A -> when {
                l2 && r2 -> { suppressed = true; null }
                l2 -> Command.UNDO
                r2 -> Command.REDO
                else -> null
            }
            KeyEvent.KEYCODE_BUTTON_X -> when {
                l2 && !r2 -> Command.SWAP
                r2 -> { suppressed = true; null }
                else -> null
            }
            KeyEvent.KEYCODE_BUTTON_THUMBR -> Command.FOCUS
            KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_MENU -> Command.MENU
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (toolbarFocused) null else Command.MENU
            else -> null
        }
        if (command != null) {
            owners[code] = Owner.GLOBAL
            return Route.Run(command)
        }
        if (suppressed) return deliver(code, Owner.GLOBAL, Target.NONE)
        if (toolbarFocused) return deliver(code, Owner.TOOLBAR, Target.TOOLBAR)
        return deliver(code, Owner.EDITOR, Target.EDITOR)
    }

    private fun deliver(code: Int, owner: Owner, target: Target): Route {
        owners[code] = owner
        return Route.Deliver(target)
    }

    private fun isTrigger(code: Int) = code == KeyEvent.KEYCODE_BUTTON_L2 ||
        code == KeyEvent.KEYCODE_BUTTON_R2 || code == KeyEvent.KEYCODE_BUTTON_THUMBL
}
