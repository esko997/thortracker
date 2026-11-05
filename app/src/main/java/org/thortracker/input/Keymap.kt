package org.thortracker.input

import android.view.KeyEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import org.thortracker.editor.Action

/** A physical binding: [button] alone, or [button] pressed while [modifier] is held. Android key codes. */
data class Binding(val button: Int, val modifier: Int? = null)

/** Areas of the Controls screen. */
enum class ControlGroup(val label: String) {
    MOVE("Move and edit"),
    CHANNEL("Channels and octave"),
    CLIPBOARD("Clipboard and rows"),
    PATTERN("Pattern"),
    HISTORY("History"),
    TRANSPORT("Playback"),
    VIEWS("Views and displays"),
}

/**
 * Every rebindable control. [action] is the editor action it sends; host controls ([host]) are run by the
 * activity instead. [hold] controls are held together with the D-pad, so they bind to single buttons only.
 * [overPopups] controls also work while an in-app popup is open. [essential] controls may never be left
 * without a binding (they navigate and confirm). Menu and Back are fixed system keys and not listed.
 */
enum class Control(
    val group: ControlGroup,
    val label: String,
    val short: String,
    val action: Action?,
    val hold: Boolean = false,
    val overPopups: Boolean = false,
    val essential: Boolean = false,
) {
    UP(ControlGroup.MOVE, "Up", "Up", Action.UP, hold = true, essential = true),
    DOWN(ControlGroup.MOVE, "Down", "Down", Action.DOWN, hold = true, essential = true),
    LEFT(ControlGroup.MOVE, "Left", "Left", Action.LEFT, hold = true, essential = true),
    RIGHT(ControlGroup.MOVE, "Right", "Right", Action.RIGHT, hold = true, essential = true),
    EDIT(ControlGroup.MOVE, "Edit / choose (hold + D-pad to change)", "Edit", Action.EDIT, hold = true, essential = true),
    CLEAR(ControlGroup.MOVE, "Clear / back (hold + D-pad to jump)", "Clear", Action.CLEAR, hold = true, essential = true),
    AUDITION(ControlGroup.MOVE, "Listen (hold)", "Listen", Action.AUDITION, hold = true),
    MARK(ControlGroup.MOVE, "Select block (hold + D-pad)", "Select", Action.MARK, hold = true),
    PREV_CHANNEL(ControlGroup.CHANNEL, "Previous channel / bank / page", "Previous", Action.PREV_CHANNEL),
    NEXT_CHANNEL(ControlGroup.CHANNEL, "Next channel / bank / page", "Next", Action.NEXT_CHANNEL),
    OCTAVE_DOWN(ControlGroup.CHANNEL, "Entry octave down", "Octave down", Action.OCTAVE_DOWN),
    OCTAVE_UP(ControlGroup.CHANNEL, "Entry octave up", "Octave up", Action.OCTAVE_UP),
    COPY(ControlGroup.CLIPBOARD, "Copy", "Copy", Action.COPY),
    PASTE(ControlGroup.CLIPBOARD, "Paste", "Paste", Action.PASTE),
    CUT(ControlGroup.CLIPBOARD, "Cut", "Cut", Action.CUT),
    DUPLICATE(ControlGroup.CLIPBOARD, "Duplicate", "Duplicate", Action.DUPLICATE),
    INSERT_ROW(ControlGroup.CLIPBOARD, "Insert row", "Insert row", Action.INSERT_ROW),
    DELETE_ROW(ControlGroup.CLIPBOARD, "Delete row", "Delete row", Action.DELETE_ROW),
    PATTERN_PANEL(ControlGroup.PATTERN, "Pattern panel: root, scale, length", "Pattern panel", Action.SCALE),
    UNDO(ControlGroup.HISTORY, "Undo", "Undo", Action.UNDO, overPopups = true),
    REDO(ControlGroup.HISTORY, "Redo", "Redo", Action.REDO, overPopups = true),
    PLAY_PATTERN(ControlGroup.TRANSPORT, "Play pattern from row 00 / stop", "Play", null),
    PLAY_CURSOR(ControlGroup.TRANSPORT, "Play from cursor / stop", "Play from cursor", null),
    SWAP(ControlGroup.VIEWS, "Swap the two displays' views", "Swap views", null, overPopups = true),
    FOCUS(ControlGroup.VIEWS, "Control the other display", "Other display", null),
    TOOLBAR(ControlGroup.VIEWS, "Show / leave the toolbar", "Toolbar", null);

    /** Run by the activity (history, transport, displays) rather than delivered to the focused view. */
    val host: Boolean get() = action == null || this == UNDO || this == REDO
}

/** The complete control → binding table. Immutable; [with] returns a changed copy. */
class Keymap private constructor(private val table: Map<Control, Binding?>) {
    private val byBinding: Map<Binding, Control> = table.entries.mapNotNull { (c, b) -> b?.let { it to c } }.toMap()

    fun binding(control: Control): Binding? = table[control]

    fun control(binding: Binding): Control? = byBinding[binding]

    fun control(action: Action): Control? = Control.entries.firstOrNull { it.action == action }

    /** Bound chords using [modifier], in control order. */
    fun chords(modifier: Int): List<Pair<Control, Binding>> =
        Control.entries.mapNotNull { c -> table[c]?.takeIf { it.modifier == modifier }?.let { c to it } }

    fun with(control: Control, binding: Binding?): Keymap = Keymap(table + (control to binding))

    val isDefault: Boolean get() = table == DEFAULT.table

    /** Display text: "A", "L2+A", "—" when unbound. */
    fun label(control: Control): String = table[control]?.let(::label) ?: UNBOUND

    fun label(action: Action): String = control(action)?.let(::label) ?: UNBOUND

    /** Two related actions in one hint: "←→", "L1 R1", "Sel+X/A" when they share a modifier. */
    fun pair(a: Action, b: Action): String {
        val ba = control(a)?.let { table[it] }
        val bb = control(b)?.let { table[it] }
        if (ba == null || bb == null) return listOf(label(a), label(b)).joinToString(" ")
        if (ba.modifier != bb.modifier) return "${label(ba)} ${label(bb)}"
        val prefix = ba.modifier?.let { buttonName(it) + "+" } ?: ""
        val na = buttonName(ba.button)
        val nb = buttonName(bb.button)
        return prefix + if (na in ARROWS && nb in ARROWS) na + nb else if (ba.modifier != null) "$na/$nb" else "$na $nb"
    }

    /** All four directions: "✛" on the D-pad, otherwise their names. */
    fun dpad(): String =
        if (DIRECTIONS.all { table[it] == DEFAULT.table[it] }) "✛" else pair(Action.UP, Action.DOWN) + pair(Action.LEFT, Action.RIGHT)

    /** A held control combined with something else: "A+←→". */
    fun held(action: Action, then: String): String = "${label(action)}+$then"

    /** The canonical popup/toolbar key a bare press of [code] stands for, so menus follow rebinding. */
    fun canonical(code: Int): Int {
        val c = control(Binding(code)) ?: return code
        return CANONICAL[c] ?: KeyEvent.KEYCODE_UNKNOWN
    }

    /** Why [binding] cannot be used for [control], or null if it can (conflicts are checked separately). */
    fun problem(control: Control, binding: Binding): String? = when {
        binding.button in RESERVED -> "Menu and Back are reserved so the menu can always be reached"
        binding.button in MODIFIERS -> "${buttonName(binding.button)} only starts chords: hold it and press another button"
        binding.modifier != null && binding.modifier !in MODIFIERS -> "Chords start with Select, L2, R2 or L3"
        control.hold && binding.modifier != null -> "${control.short} is held with the D-pad, so it needs a single button"
        else -> null
    }

    /** Essential controls the takeover of [binding] by [control] would leave with no binding. */
    fun stranded(control: Control, binding: Binding): Control? =
        control(binding)?.takeIf { it != control && it.essential }

    fun toJson(): String = JSON.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("format", JsonPrimitive(FORMAT))
        put("version", JsonPrimitive(VERSION))
        put("bindings", buildJsonObject {
            for (c in Control.entries) put(c.name, table[c]?.let { JsonPrimitive(token(it)) } ?: JsonNull)
        })
    })

    override fun equals(other: Any?): Boolean = other is Keymap && other.table == table
    override fun hashCode(): Int = table.hashCode()

    companion object {
        const val FORMAT = "thor-tracker-keymap"
        const val VERSION = 1
        const val UNBOUND = "—"

        /** Buttons that do nothing alone and start chords. */
        val MODIFIERS = setOf(
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_THUMBL,
        )

        /** Fixed system keys: they always open/leave the menu and are never bound. */
        val RESERVED = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_MODE)

        private val DIRECTIONS = listOf(Control.UP, Control.DOWN, Control.LEFT, Control.RIGHT)
        private val ARROWS = setOf("↑", "↓", "←", "→")

        private val CANONICAL = mapOf(
            Control.UP to KeyEvent.KEYCODE_DPAD_UP, Control.DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
            Control.LEFT to KeyEvent.KEYCODE_DPAD_LEFT, Control.RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
            Control.EDIT to KeyEvent.KEYCODE_BUTTON_A, Control.CLEAR to KeyEvent.KEYCODE_BUTTON_B,
            Control.PREV_CHANNEL to KeyEvent.KEYCODE_BUTTON_L1, Control.NEXT_CHANNEL to KeyEvent.KEYCODE_BUTTON_R1,
        )

        private const val SEL = KeyEvent.KEYCODE_BUTTON_SELECT
        private const val L2 = KeyEvent.KEYCODE_BUTTON_L2
        private const val R2 = KeyEvent.KEYCODE_BUTTON_R2

        val DEFAULT = Keymap(mapOf(
            Control.UP to Binding(KeyEvent.KEYCODE_DPAD_UP),
            Control.DOWN to Binding(KeyEvent.KEYCODE_DPAD_DOWN),
            Control.LEFT to Binding(KeyEvent.KEYCODE_DPAD_LEFT),
            Control.RIGHT to Binding(KeyEvent.KEYCODE_DPAD_RIGHT),
            Control.EDIT to Binding(KeyEvent.KEYCODE_BUTTON_A),
            Control.CLEAR to Binding(KeyEvent.KEYCODE_BUTTON_B),
            Control.AUDITION to Binding(KeyEvent.KEYCODE_BUTTON_X),
            Control.MARK to Binding(KeyEvent.KEYCODE_BUTTON_Y),
            Control.PREV_CHANNEL to Binding(KeyEvent.KEYCODE_BUTTON_L1),
            Control.NEXT_CHANNEL to Binding(KeyEvent.KEYCODE_BUTTON_R1),
            Control.OCTAVE_DOWN to Binding(KeyEvent.KEYCODE_BUTTON_L1, SEL),
            Control.OCTAVE_UP to Binding(KeyEvent.KEYCODE_BUTTON_R1, SEL),
            Control.COPY to Binding(KeyEvent.KEYCODE_BUTTON_X, SEL),
            Control.PASTE to Binding(KeyEvent.KEYCODE_BUTTON_A, SEL),
            Control.CUT to Binding(KeyEvent.KEYCODE_BUTTON_B, SEL),
            Control.DUPLICATE to Binding(KeyEvent.KEYCODE_BUTTON_Y, SEL),
            Control.INSERT_ROW to Binding(KeyEvent.KEYCODE_DPAD_UP, SEL),
            Control.DELETE_ROW to Binding(KeyEvent.KEYCODE_DPAD_DOWN, SEL),
            Control.PATTERN_PANEL to Binding(KeyEvent.KEYCODE_DPAD_RIGHT, L2),
            Control.UNDO to Binding(KeyEvent.KEYCODE_BUTTON_A, L2),
            Control.REDO to Binding(KeyEvent.KEYCODE_BUTTON_A, R2),
            Control.PLAY_PATTERN to Binding(KeyEvent.KEYCODE_BUTTON_START),
            Control.PLAY_CURSOR to Binding(KeyEvent.KEYCODE_BUTTON_START, L2),
            Control.SWAP to Binding(KeyEvent.KEYCODE_BUTTON_X, L2),
            Control.FOCUS to Binding(KeyEvent.KEYCODE_BUTTON_THUMBR),
            Control.TOOLBAR to Binding(KeyEvent.KEYCODE_BUTTON_START, SEL),
        ))

        private val NAMES = linkedMapOf(
            KeyEvent.KEYCODE_DPAD_UP to ("UP" to "↑"), KeyEvent.KEYCODE_DPAD_DOWN to ("DOWN" to "↓"),
            KeyEvent.KEYCODE_DPAD_LEFT to ("LEFT" to "←"), KeyEvent.KEYCODE_DPAD_RIGHT to ("RIGHT" to "→"),
            KeyEvent.KEYCODE_BUTTON_A to ("A" to "A"), KeyEvent.KEYCODE_BUTTON_B to ("B" to "B"),
            KeyEvent.KEYCODE_BUTTON_C to ("C" to "C"), KeyEvent.KEYCODE_BUTTON_X to ("X" to "X"),
            KeyEvent.KEYCODE_BUTTON_Y to ("Y" to "Y"), KeyEvent.KEYCODE_BUTTON_Z to ("Z" to "Z"),
            KeyEvent.KEYCODE_BUTTON_L1 to ("L1" to "L1"), KeyEvent.KEYCODE_BUTTON_R1 to ("R1" to "R1"),
            KeyEvent.KEYCODE_BUTTON_L2 to ("L2" to "L2"), KeyEvent.KEYCODE_BUTTON_R2 to ("R2" to "R2"),
            KeyEvent.KEYCODE_BUTTON_THUMBL to ("L3" to "L3"), KeyEvent.KEYCODE_BUTTON_THUMBR to ("R3" to "R3"),
            KeyEvent.KEYCODE_BUTTON_SELECT to ("SELECT" to "Sel"), KeyEvent.KEYCODE_BUTTON_START to ("START" to "Start"),
            KeyEvent.KEYCODE_SPACE to ("SPACE" to "Space"), KeyEvent.KEYCODE_ENTER to ("ENTER" to "Enter"),
            KeyEvent.KEYCODE_DPAD_CENTER to ("CENTER" to "Center"),
        )
        private val BY_TOKEN = NAMES.entries.associate { (code, names) -> names.first to code }

        /** Short on-screen name of a key. */
        fun buttonName(code: Int): String = NAMES[code]?.second ?: when (code) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> "Key " + ('A' + (code - KeyEvent.KEYCODE_A))
            in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16 -> "Btn ${code - KeyEvent.KEYCODE_BUTTON_1 + 1}"
            else -> "Key $code"
        }

        fun label(binding: Binding): String =
            (binding.modifier?.let { buttonName(it) + "+" } ?: "") + buttonName(binding.button)

        private fun tokenOf(code: Int) = NAMES[code]?.first ?: "KEY_$code"

        private fun codeOf(token: String): Int? = BY_TOKEN[token] ?: token.removePrefix("KEY_").takeIf { token.startsWith("KEY_") }?.toIntOrNull()

        private fun token(b: Binding) = (b.modifier?.let { tokenOf(it) + "+" } ?: "") + tokenOf(b.button)

        private fun parse(text: String): Binding? {
            val parts = text.split('+')
            return when (parts.size) {
                1 -> codeOf(parts[0])?.let { Binding(it) }
                2 -> { val m = codeOf(parts[0]); val b = codeOf(parts[1]); if (m != null && b != null) Binding(b, m) else null }
                else -> null
            }
        }

        private val JSON = Json { prettyPrint = true }

        /**
         * Reads a keymap file. Controls it does not mention keep their defaults (files from older versions);
         * unknown controls are ignored (files from newer versions). Invalid or duplicate bindings are rejected.
         */
        fun fromJson(text: String): Keymap {
            val root = try { JSON.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }
                ?: throw IllegalArgumentException("Not a keymap: the file is not a JSON object")
            if ((root["format"] as? JsonPrimitive)?.content != FORMAT) throw IllegalArgumentException("Not a Thor Tracker keymap file")
            val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("Keymap version is missing")
            if (version !in 1..VERSION) throw IllegalArgumentException("Keymap version $version is newer than this app reads ($VERSION)")
            val entries = root["bindings"] as? JsonObject ?: throw IllegalArgumentException("Keymap has no bindings")
            var map = DEFAULT
            for ((name, value) in entries) {
                val control = Control.entries.firstOrNull { it.name == name } ?: continue
                val binding = if (value is JsonNull) null else {
                    val text2 = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw IllegalArgumentException("$name: binding must be text like \"L2+A\" or null")
                    parse(text2) ?: throw IllegalArgumentException("$name: \"$text2\" is not a key or chord")
                }
                if (binding != null) map.problem(control, binding)?.let { throw IllegalArgumentException("$name: $it") }
                map = map.with(control, binding)
            }
            map.validationError()?.let { throw IllegalArgumentException(it) }
            return map
        }

        /**
         * Converts 0.1-0.4 physical → logical key remaps (each logical key is the default button it acts as)
         * into a keymap: each default binding's buttons move to the physical key that now produces them.
         * Returns the default keymap if the result would be invalid.
         */
        fun fromLegacyRemap(remap: Map<Int, Int>): Keymap {
            if (remap.isEmpty()) return DEFAULT
            fun physical(logical: Int): Int? = remap.entries.firstOrNull { it.value == logical }?.key
                ?: logical.takeIf { it !in remap.keys }
            var map = DEFAULT
            for (c in Control.entries) {
                val d = DEFAULT.binding(c) ?: continue
                val button = physical(d.button)
                val modifier = d.modifier?.let { physical(it)?.takeIf { p -> p in MODIFIERS } ?: it }
                val moved = button?.let { Binding(it, modifier) }
                map = map.with(c, moved?.takeIf { map.problem(c, it) == null })
            }
            return if (map.validationError() == null) map else DEFAULT
        }

        private fun Keymap.validationError(): String? {
            val seen = HashMap<Binding, Control>()
            for (c in Control.entries) {
                val b = binding(c)
                if (b == null) { if (c.essential) return "${c.label} needs a binding"; continue }
                seen.put(b, c)?.let { return "${label(b)} is bound to both ${it.label} and ${c.label}" }
            }
            return null
        }
    }
}
