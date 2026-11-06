package org.thortracker.input

import android.view.KeyEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import org.thortracker.editor.Action
import org.thortracker.editor.ViewKind

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
 * without a binding (they navigate and confirm). [views] limits a control to those views (null = every view):
 * two controls may share a binding when their views do not overlap, so one chord can mean different things
 * in different views. Menu and Back are fixed system keys and not listed.
 */
enum class Control(
    val group: ControlGroup,
    private val job: String,
    val short: String,
    val action: Action?,
    val hold: Boolean = false,
    val overPopups: Boolean = false,
    val essential: Boolean = false,
    val views: Set<ViewKind>? = null,
    /** Keymap file version that introduced the control; older files may already use its default. */
    val since: Int = 1,
    /** For view controls: the view this control shows on the controller-focused display. */
    val shows: ViewKind? = null,
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
    OCTAVE_DOWN(ControlGroup.CHANNEL, "Octave down", "Octave down", Action.OCTAVE_DOWN, views = OCTAVE_VIEWS),
    OCTAVE_UP(ControlGroup.CHANNEL, "Octave up", "Octave up", Action.OCTAVE_UP, views = OCTAVE_VIEWS),
    LISTEN_OCTAVE_DOWN(ControlGroup.CHANNEL, "Listening octave down", "Octave down", Action.OCTAVE_DOWN, views = SOUND_VIEWS, since = 2),
    LISTEN_OCTAVE_UP(ControlGroup.CHANNEL, "Listening octave up", "Octave up", Action.OCTAVE_UP, views = SOUND_VIEWS, since = 2),
    COPY(ControlGroup.CLIPBOARD, "Copy", "Copy", Action.COPY, views = CLIP_VIEWS),
    PASTE(ControlGroup.CLIPBOARD, "Paste", "Paste", Action.PASTE, views = CLIP_VIEWS),
    CUT(ControlGroup.CLIPBOARD, "Cut", "Cut", Action.CUT, views = CUT_VIEWS),
    DUPLICATE(ControlGroup.CLIPBOARD, "Duplicate", "Duplicate", Action.DUPLICATE, views = CLIP_VIEWS),
    INSERT_ROW(ControlGroup.CLIPBOARD, "Insert row / order", "Insert", Action.INSERT_ROW, views = ROW_VIEWS),
    DELETE_ROW(ControlGroup.CLIPBOARD, "Delete row / order", "Delete", Action.DELETE_ROW, views = ROW_VIEWS),
    PATTERN_PANEL(ControlGroup.PATTERN, "Pattern panel: root, scale, length", "Pattern panel", Action.SCALE, views = PATTERN_ONLY),
    UNDO(ControlGroup.HISTORY, "Undo", "Undo", Action.UNDO, overPopups = true),
    REDO(ControlGroup.HISTORY, "Redo", "Redo", Action.REDO, overPopups = true),
    PLAY_PATTERN(ControlGroup.TRANSPORT, "Play pattern from row 00 / stop", "Play", null),
    PLAY_CURSOR(ControlGroup.TRANSPORT, "Play from cursor / stop", "Play from cursor", null),
    LOOP_PATTERN(ControlGroup.TRANSPORT, "Loop the current pattern on / off", "Loop pattern", null, since = 4),
    SWAP(ControlGroup.VIEWS, "Swap the two displays' views", "Swap views", null, overPopups = true),
    FOCUS(ControlGroup.VIEWS, "Control the other display", "Other display", null),
    TOOLBAR(ControlGroup.VIEWS, "Show / leave the toolbar", "Toolbar", null),
    SHOW_PATTERN(ControlGroup.VIEWS, "Show Pattern here (again: previous view)", "Pattern", null, since = 3, shows = ViewKind.PATTERN),
    SHOW_SONG(ControlGroup.VIEWS, "Show Song here (again: previous view)", "Song", null, since = 3, shows = ViewKind.SONG),
    SHOW_INSTRUMENT(ControlGroup.VIEWS, "Show Instrument here (again: previous view)", "Instrument", null, since = 3, shows = ViewKind.INSTRUMENT),
    SHOW_SKETCH(ControlGroup.VIEWS, "Show Sketch here (again: previous view)", "Sketch", null, since = 3, shows = ViewKind.SKETCH),
    SHOW_KEYBOARD(ControlGroup.VIEWS, "Show Keyboard here (again: previous view)", "Keyboard", null, since = 3, shows = ViewKind.KEYBOARD),
    SHOW_WAVE(ControlGroup.VIEWS, "Show Wave here (again: previous view)", "Wave", null, since = 3, shows = ViewKind.WAVE),
    SHOW_HELP(ControlGroup.VIEWS, "Show Help here (again: previous view)", "Help", null, since = 3, shows = ViewKind.HELP);

    /** Run by the activity (history, transport, displays) rather than delivered to the focused view. */
    val host: Boolean get() = action == null || this == UNDO || this == REDO

    /** What the control does, with the views it is limited to: "Paste (Pattern, Song, Instrument, Wave)". */
    val label: String get() = if (views == null) job else "$job (${ViewKind.entries.filter { it in views }.joinToString { it.title }})"

    fun appliesIn(view: ViewKind): Boolean = views == null || view in views

    /** Two controls can both be active in some view. */
    fun overlaps(other: Control): Boolean =
        views == null || other.views == null || views.any { it in other.views }
}

private val ViewKind.title: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

// Top-level (not the enum's companion) so they exist when the entries are constructed.
private val PATTERN_ONLY = setOf(ViewKind.PATTERN)
private val ROW_VIEWS = setOf(ViewKind.PATTERN, ViewKind.SONG)
private val CLIP_VIEWS = setOf(ViewKind.PATTERN, ViewKind.SONG, ViewKind.INSTRUMENT, ViewKind.WAVE)
private val CUT_VIEWS = setOf(ViewKind.PATTERN, ViewKind.INSTRUMENT, ViewKind.WAVE)
private val SOUND_VIEWS = setOf(ViewKind.INSTRUMENT, ViewKind.WAVE, ViewKind.KEYBOARD, ViewKind.SKETCH)
private val OCTAVE_VIEWS = SOUND_VIEWS + ViewKind.PATTERN

/** The complete control → binding table (every control present, null = unbound). Immutable; [with] returns a changed copy. */
class Keymap private constructor(bindings: Map<Control, Binding?>) {
    private val table: Map<Control, Binding?> = Control.entries.associateWith { bindings[it] }
    private val byBinding: Map<Binding, List<Control>> =
        table.entries.mapNotNull { (c, b) -> b?.let { it to c } }.groupBy({ it.first }, { it.second })

    fun binding(control: Control): Binding? = table[control]

    /** The control [binding] triggers in [view], or null. */
    fun control(binding: Binding, view: ViewKind): Control? = byBinding[binding]?.firstOrNull { it.appliesIn(view) }

    /** Controls that would conflict with [control] using [binding] (they share a view with it). */
    fun conflicts(control: Control, binding: Binding): List<Control> =
        byBinding[binding].orEmpty().filter { it != control && it.overlaps(control) }

    /**
     * The control sending [action] in [view]: a bound control limited to that view first, then a bound
     * control for every view. Without a view (or when none is bound) the first control with the action.
     */
    fun control(action: Action, view: ViewKind? = null): Control? {
        if (view != null) {
            val active = Control.entries.filter { it.action == action && it.appliesIn(view) && table[it] != null }
            // The most view-specific binding is the one the view advertises.
            active.minByOrNull { it.views?.size ?: Int.MAX_VALUE }?.let { return it }
        }
        return Control.entries.firstOrNull { it.action == action }
    }

    /** Bound chords using [modifier] that act in [view], in control order. */
    fun chords(modifier: Int, view: ViewKind): List<Pair<Control, Binding>> =
        Control.entries.mapNotNull { c -> table[c]?.takeIf { it.modifier == modifier && c.appliesIn(view) }?.let { c to it } }

    fun with(control: Control, binding: Binding?): Keymap = Keymap(table + (control to binding))

    val isDefault: Boolean get() = table == DEFAULT.table

    /** Display text: "A", "L2+A", "—" when unbound. */
    fun label(control: Control): String = table[control]?.let(::label) ?: UNBOUND

    fun label(action: Action, view: ViewKind? = null): String = control(action, view)?.let(::label) ?: UNBOUND

    /** Two related actions in one hint: "←→", "L1 R1", "Sel+X/A" when they share a modifier. */
    fun pair(a: Action, b: Action, view: ViewKind? = null): String = pair(control(a, view), control(b, view))

    private fun pair(a: Control?, b: Control?): String {
        val ba = a?.let { table[it] }
        val bb = b?.let { table[it] }
        if (ba == null || bb == null) return listOf(a?.let(::label) ?: UNBOUND, b?.let(::label) ?: UNBOUND).joinToString(" ")
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
        val c = byBinding[Binding(code)]?.firstOrNull() ?: return code
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
        conflicts(control, binding).firstOrNull { it.essential }

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
        const val VERSION = 4
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
            Control.LISTEN_OCTAVE_DOWN to Binding(KeyEvent.KEYCODE_DPAD_LEFT, L2),
            Control.LISTEN_OCTAVE_UP to Binding(KeyEvent.KEYCODE_DPAD_RIGHT, L2),
            Control.COPY to Binding(KeyEvent.KEYCODE_BUTTON_X, SEL),
            Control.PASTE to Binding(KeyEvent.KEYCODE_BUTTON_A, SEL),
            Control.CUT to Binding(KeyEvent.KEYCODE_BUTTON_B, SEL),
            Control.DUPLICATE to Binding(KeyEvent.KEYCODE_BUTTON_Y, SEL),
            Control.INSERT_ROW to Binding(KeyEvent.KEYCODE_DPAD_UP, SEL),
            Control.DELETE_ROW to Binding(KeyEvent.KEYCODE_DPAD_DOWN, SEL),
            Control.PATTERN_PANEL to Binding(KeyEvent.KEYCODE_DPAD_RIGHT, L2),
            Control.UNDO to Binding(KeyEvent.KEYCODE_BUTTON_A, L2),
            Control.REDO to Binding(KeyEvent.KEYCODE_BUTTON_B, L2),
            Control.PLAY_PATTERN to Binding(KeyEvent.KEYCODE_BUTTON_START),
            Control.PLAY_CURSOR to Binding(KeyEvent.KEYCODE_BUTTON_START, L2),
            Control.LOOP_PATTERN to Binding(KeyEvent.KEYCODE_BUTTON_Y, L2),
            Control.SWAP to Binding(KeyEvent.KEYCODE_BUTTON_X, L2),
            Control.FOCUS to Binding(KeyEvent.KEYCODE_BUTTON_THUMBR),
            Control.TOOLBAR to Binding(KeyEvent.KEYCODE_BUTTON_START, SEL),
            Control.SHOW_SONG to Binding(KeyEvent.KEYCODE_BUTTON_A, R2),
            Control.SHOW_INSTRUMENT to Binding(KeyEvent.KEYCODE_BUTTON_B, R2),
            Control.SHOW_SKETCH to Binding(KeyEvent.KEYCODE_BUTTON_X, R2),
            Control.SHOW_KEYBOARD to Binding(KeyEvent.KEYCODE_BUTTON_Y, R2),
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
         * Reads a keymap file. Controls it does not mention keep their defaults (files from older versions)
         * unless the file already uses that default for something in the same view, in which case they start
         * unbound; unknown controls are ignored (files from newer versions). Invalid or duplicate bindings
         * among the file's own entries are rejected. A file that never changed a binding from the defaults of
         * its own version reads as today's defaults, so moved defaults (Redo to L2+B in version 3) reach users
         * who never rebound anything.
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
            if (entries.all { (name, value) ->
                    val c = Control.entries.firstOrNull { it.name == name } ?: return@all true
                    val b = if (value is JsonNull) null else (value as? JsonPrimitive)?.content?.let(::parse)
                    b == defaultIn(c, version)
                }) return DEFAULT
            val named = entries.keys
            for (c in Control.entries) {
                if (c.since <= version || c.name in named) continue
                val b = map.binding(c) ?: continue
                if (map.conflicts(c, b).any { it.name in named }) map = map.with(c, null)
            }
            map.validationError()?.let { throw IllegalArgumentException(it) }
            return map
        }

        /** A control's default binding in keymap file [version]. Only Redo has moved (R2+A before version 3). */
        private fun defaultIn(control: Control, version: Int): Binding? =
            if (control == Control.REDO && version < 3) Binding(KeyEvent.KEYCODE_BUTTON_A, R2) else DEFAULT.binding(control)

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

        /** Essential controls need a binding; two controls may share one only if no view has both. */
        private fun Keymap.validationError(): String? {
            val seen = HashMap<Binding, MutableList<Control>>()
            for (c in Control.entries) {
                val b = binding(c)
                if (b == null) { if (c.essential) return "${c.label} needs a binding"; continue }
                val others = seen.getOrPut(b) { ArrayList() }
                others.firstOrNull { it.overlaps(c) }?.let { return "${label(b)} is bound to both ${it.label} and ${c.label}" }
                others += c
            }
            return null
        }
    }
}
