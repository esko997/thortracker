package org.thortracker.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import org.thortracker.editor.Action
import org.thortracker.editor.ViewKind

/**
 * A physical binding: [button] alone, or a chord: [button] pressed while every one of [modifiers] is held. The
 * modifiers are a set, so the order they went down in never matters (L2 then R2, or R2 then L2, is the one L2+R2
 * layer). Android key codes.
 */
data class Binding(val button: Int, val modifiers: Set<Int> = emptySet())

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
    /** For view controls: the view this control shows on the controller-focused display (or the other one, [toOther]). */
    val shows: ViewKind? = null,
    /** A view control that shows [shows] on the other display, leaving controller focus here; with one display, on it. */
    val toOther: Boolean = false,
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
    TOGGLE_CHANNEL_COLLAPSE(ControlGroup.PATTERN, "Collapse / expand focused channel", "Collapse / expand", Action.TOGGLE_CHANNEL_COLLAPSE, views = PATTERN_ONLY, since = 7),
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
    SHOW_HELP(ControlGroup.VIEWS, "Show Help here (again: previous view)", "Help", null, since = 3, shows = ViewKind.HELP),
    OTHER_PATTERN(ControlGroup.VIEWS, "Show Pattern on the other display (again: previous view)", "Pattern on other", null, since = 6, shows = ViewKind.PATTERN, toOther = true),
    OTHER_SONG(ControlGroup.VIEWS, "Show Song on the other display (again: previous view)", "Song on other", null, since = 6, shows = ViewKind.SONG, toOther = true),
    OTHER_INSTRUMENT(ControlGroup.VIEWS, "Show Instrument on the other display (again: previous view)", "Instrument on other", null, since = 6, shows = ViewKind.INSTRUMENT, toOther = true),
    OTHER_SKETCH(ControlGroup.VIEWS, "Show Sketch on the other display (again: previous view)", "Sketch on other", null, since = 6, shows = ViewKind.SKETCH, toOther = true),
    OTHER_KEYBOARD(ControlGroup.VIEWS, "Show Keyboard on the other display (again: previous view)", "Keyboard on other", null, since = 6, shows = ViewKind.KEYBOARD, toOther = true),
    OTHER_WAVE(ControlGroup.VIEWS, "Show Wave on the other display (again: previous view)", "Wave on other", null, since = 6, shows = ViewKind.WAVE, toOther = true),
    OTHER_HELP(ControlGroup.VIEWS, "Show Help on the other display (again: previous view)", "Help on other", null, since = 6, shows = ViewKind.HELP, toOther = true);

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

    /** Bound chords of exactly the [modifiers] layer that act in [view], in control order. */
    fun chords(modifiers: Set<Int>, view: ViewKind): List<Pair<Control, Binding>> =
        Control.entries.mapNotNull { c -> table[c]?.takeIf { it.modifiers == modifiers && c.appliesIn(view) }?.let { c to it } }

    /**
     * A control acting in [view] that [button] triggers with only some of the [held] modifiers (L2+A while L2 and
     * R2 are both held), or null. While a layer of several modifiers is held, such a chord belongs to a layer
     * below it, so the press must not run it.
     */
    fun partialChord(button: Int, held: Set<Int>, view: ViewKind): Control? = byBinding.entries.firstNotNullOfOrNull { (b, controls) ->
        if (b.button != button || b.modifiers.isEmpty() || b.modifiers == held || !held.containsAll(b.modifiers)) null
        else controls.firstOrNull { it.appliesIn(view) }
    }

    fun with(control: Control, binding: Binding?): Keymap = Keymap(table + (control to binding))

    val isDefault: Boolean get() = table == DEFAULT.table

    /** Display text: "A", "L2+A", "—" when unbound. */
    fun label(control: Control): String = table[control]?.let(::label) ?: UNBOUND

    fun label(action: Action, view: ViewKind? = null): String = control(action, view)?.let(::label) ?: UNBOUND

    /** Two related actions in one hint: "←→", "L1 R1", "Sel+X/A" when they share their modifiers. */
    fun pair(a: Action, b: Action, view: ViewKind? = null): String = pair(control(a, view), control(b, view))

    private fun pair(a: Control?, b: Control?): String {
        val ba = a?.let { table[it] }
        val bb = b?.let { table[it] }
        if (ba == null || bb == null) return listOf(a?.let(::label) ?: UNBOUND, b?.let(::label) ?: UNBOUND).joinToString(" ")
        if (ba.modifiers != bb.modifiers) return "${label(ba)} ${label(bb)}"
        val chord = ba.modifiers.isNotEmpty()
        val prefix = if (chord) modifiersLabel(ba.modifiers) + "+" else ""
        val na = buttonName(ba.button)
        val nb = buttonName(bb.button)
        return prefix + if (na in ARROWS && nb in ARROWS) na + nb else if (chord) "$na/$nb" else "$na $nb"
    }

    /** All four directions: "✛" on the D-pad, otherwise their names. */
    fun dpad(): String =
        if (DIRECTIONS.all { table[it] == DEFAULT.table[it] }) "✛" else pair(Action.UP, Action.DOWN) + pair(Action.LEFT, Action.RIGHT)

    /** A held control combined with something else: "A+←→". */
    fun held(action: Action, then: String): String = "${label(action)}+$then"

    /** The canonical popup/toolbar key a bare press of [code] stands for, so menus follow rebinding. */
    fun canonical(code: Int): Int {
        val c = byBinding[Binding(code)]?.firstOrNull() ?: return code
        return CANONICAL[c] ?: Keys.UNKNOWN
    }

    /** Why [binding] cannot be used for [control], or null if it can (conflicts are checked separately). */
    fun problem(control: Control, binding: Binding): String? = when {
        binding.button in RESERVED -> "Menu and Back are reserved so the menu can always be reached"
        binding.button in MODIFIERS -> "${buttonName(binding.button)} only starts chords: hold it and press another button"
        binding.modifiers.any { it !in MODIFIERS } -> "Chords start with Select, L2, R2 or L3"
        binding.modifiers.size > MAX_CHORD_MODIFIERS -> "A chord holds at most two of Select, L2, R2 and L3"
        control.hold && binding.modifiers.isNotEmpty() -> "${control.short} is held with the D-pad, so it needs a single button"
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
        const val VERSION = 7
        const val UNBOUND = "—"

        /** Buttons that do nothing alone and start chords, in the order chord labels name them. */
        val MODIFIERS = setOf(
            Keys.BUTTON_SELECT, Keys.BUTTON_L2, Keys.BUTTON_R2, Keys.BUTTON_THUMBL,
        )

        /** A chord holds one modifier, or two at once (L2+R2+A); never three. */
        const val MAX_CHORD_MODIFIERS = 2

        /** Fixed system keys: they always open/leave the menu and are never bound. */
        val RESERVED = setOf(Keys.BACK, Keys.ESCAPE, Keys.MENU, Keys.BUTTON_MODE)

        private val DIRECTIONS = listOf(Control.UP, Control.DOWN, Control.LEFT, Control.RIGHT)
        private val ARROWS = setOf("↑", "↓", "←", "→")

        private val CANONICAL = mapOf(
            Control.UP to Keys.DPAD_UP, Control.DOWN to Keys.DPAD_DOWN,
            Control.LEFT to Keys.DPAD_LEFT, Control.RIGHT to Keys.DPAD_RIGHT,
            Control.EDIT to Keys.BUTTON_A, Control.CLEAR to Keys.BUTTON_B,
            Control.PREV_CHANNEL to Keys.BUTTON_L1, Control.NEXT_CHANNEL to Keys.BUTTON_R1,
        )

        private val SEL = setOf(Keys.BUTTON_SELECT)
        private val L2 = setOf(Keys.BUTTON_L2)
        private val R2 = setOf(Keys.BUTTON_R2)
        /** The third layer: both triggers held. */
        private val L2R2 = setOf(Keys.BUTTON_L2, Keys.BUTTON_R2)

        val DEFAULT = Keymap(mapOf(
            Control.UP to Binding(Keys.DPAD_UP),
            Control.DOWN to Binding(Keys.DPAD_DOWN),
            Control.LEFT to Binding(Keys.DPAD_LEFT),
            Control.RIGHT to Binding(Keys.DPAD_RIGHT),
            Control.EDIT to Binding(Keys.BUTTON_A),
            Control.CLEAR to Binding(Keys.BUTTON_B),
            Control.AUDITION to Binding(Keys.BUTTON_X),
            Control.MARK to Binding(Keys.BUTTON_Y),
            Control.PREV_CHANNEL to Binding(Keys.BUTTON_L1),
            Control.NEXT_CHANNEL to Binding(Keys.BUTTON_R1),
            Control.OCTAVE_DOWN to Binding(Keys.BUTTON_L1, SEL),
            Control.OCTAVE_UP to Binding(Keys.BUTTON_R1, SEL),
            Control.LISTEN_OCTAVE_DOWN to Binding(Keys.DPAD_LEFT, L2),
            Control.LISTEN_OCTAVE_UP to Binding(Keys.DPAD_RIGHT, L2),
            Control.COPY to Binding(Keys.BUTTON_X, SEL),
            Control.PASTE to Binding(Keys.BUTTON_A, SEL),
            Control.CUT to Binding(Keys.BUTTON_B, SEL),
            Control.DUPLICATE to Binding(Keys.BUTTON_Y, SEL),
            Control.INSERT_ROW to Binding(Keys.DPAD_UP, SEL),
            Control.DELETE_ROW to Binding(Keys.DPAD_DOWN, SEL),
            Control.PATTERN_PANEL to Binding(Keys.DPAD_RIGHT, L2),
            Control.TOGGLE_CHANNEL_COLLAPSE to Binding(Keys.DPAD_LEFT, L2),
            Control.UNDO to Binding(Keys.BUTTON_A, L2),
            Control.REDO to Binding(Keys.BUTTON_B, L2),
            Control.PLAY_PATTERN to Binding(Keys.BUTTON_START),
            Control.PLAY_CURSOR to Binding(Keys.BUTTON_START, L2),
            Control.LOOP_PATTERN to Binding(Keys.BUTTON_Y, L2),
            Control.SWAP to Binding(Keys.BUTTON_X, L2),
            Control.FOCUS to Binding(Keys.BUTTON_THUMBR),
            Control.TOOLBAR to Binding(Keys.BUTTON_START, SEL),
            Control.SHOW_SONG to Binding(Keys.BUTTON_A, R2),
            Control.SHOW_INSTRUMENT to Binding(Keys.BUTTON_B, R2),
            Control.SHOW_SKETCH to Binding(Keys.BUTTON_X, R2),
            Control.SHOW_KEYBOARD to Binding(Keys.BUTTON_Y, R2),
            Control.SHOW_PATTERN to Binding(Keys.DPAD_UP, R2),
            Control.SHOW_WAVE to Binding(Keys.DPAD_RIGHT, R2),
            Control.SHOW_HELP to Binding(Keys.DPAD_DOWN, R2),
            // Both triggers: the same buttons as the R2 view chords, for the other display.
            Control.OTHER_SONG to Binding(Keys.BUTTON_A, L2R2),
            Control.OTHER_INSTRUMENT to Binding(Keys.BUTTON_B, L2R2),
            Control.OTHER_SKETCH to Binding(Keys.BUTTON_X, L2R2),
            Control.OTHER_KEYBOARD to Binding(Keys.BUTTON_Y, L2R2),
            Control.OTHER_PATTERN to Binding(Keys.DPAD_UP, L2R2),
            Control.OTHER_WAVE to Binding(Keys.DPAD_RIGHT, L2R2),
            Control.OTHER_HELP to Binding(Keys.DPAD_DOWN, L2R2),
        ))

        private val NAMES = linkedMapOf(
            Keys.DPAD_UP to ("UP" to "↑"), Keys.DPAD_DOWN to ("DOWN" to "↓"),
            Keys.DPAD_LEFT to ("LEFT" to "←"), Keys.DPAD_RIGHT to ("RIGHT" to "→"),
            Keys.BUTTON_A to ("A" to "A"), Keys.BUTTON_B to ("B" to "B"),
            Keys.BUTTON_C to ("C" to "C"), Keys.BUTTON_X to ("X" to "X"),
            Keys.BUTTON_Y to ("Y" to "Y"), Keys.BUTTON_Z to ("Z" to "Z"),
            Keys.BUTTON_L1 to ("L1" to "L1"), Keys.BUTTON_R1 to ("R1" to "R1"),
            Keys.BUTTON_L2 to ("L2" to "L2"), Keys.BUTTON_R2 to ("R2" to "R2"),
            Keys.BUTTON_THUMBL to ("L3" to "L3"), Keys.BUTTON_THUMBR to ("R3" to "R3"),
            Keys.BUTTON_SELECT to ("SELECT" to "Sel"), Keys.BUTTON_START to ("START" to "Start"),
            Keys.SPACE to ("SPACE" to "Space"), Keys.ENTER to ("ENTER" to "Enter"),
            Keys.DPAD_CENTER to ("CENTER" to "Center"),
        )
        private val BY_TOKEN = NAMES.entries.associate { (code, names) -> names.first to code }

        /** Short on-screen name of a key. */
        fun buttonName(code: Int): String = NAMES[code]?.second ?: when (code) {
            in Keys.A..Keys.Z -> "Key " + ('A' + (code - Keys.A))
            in Keys.BUTTON_1..Keys.BUTTON_16 -> "Btn ${code - Keys.BUTTON_1 + 1}"
            else -> "Key $code"
        }

        /** Held [modifiers] in a fixed order (Select, L2, R2, L3), whatever order they went down in: "L2+R2". */
        fun modifiersLabel(modifiers: Set<Int>): String = ordered(modifiers).joinToString("+") { buttonName(it) }

        /** "A", "L2+A", "L2+R2+A". */
        fun label(binding: Binding): String = (ordered(binding.modifiers) + binding.button).joinToString("+") { buttonName(it) }

        private fun ordered(modifiers: Set<Int>): List<Int> =
            MODIFIERS.filter { it in modifiers } + modifiers.filter { it !in MODIFIERS }.sorted()

        private fun tokenOf(code: Int) = NAMES[code]?.first ?: "KEY_$code"

        private fun codeOf(token: String): Int? = BY_TOKEN[token] ?: token.removePrefix("KEY_").takeIf { token.startsWith("KEY_") }?.toIntOrNull()

        private fun token(b: Binding) = (ordered(b.modifiers) + b.button).joinToString("+") { tokenOf(it) }

        /**
         * "A", "L2+A" (every file version) or "L2+R2+A" (version 6 on): the last key is the button, the ones
         * before it the modifiers, in any order. A key named twice is not a chord.
         */
        private fun parse(text: String): Binding? {
            val codes = text.split('+').map { codeOf(it) ?: return null }
            val modifiers = codes.dropLast(1).toSet()
            if (modifiers.size != codes.size - 1 || codes.last() in modifiers) return null
            return Binding(codes.last(), modifiers)
        }

        private val JSON = Json { prettyPrint = true }

        /**
         * Reads a keymap file. Controls it does not mention keep their defaults (files from older versions)
         * unless the file already uses that default for something in the same view, in which case they start
         * unbound; unknown controls are ignored (files from newer versions). Invalid or duplicate bindings
         * among the file's own entries are rejected. A file that never changed a binding from the defaults of
         * its own version reads as today's defaults, so moved defaults (Redo to L2+B in version 3) reach users
         * who never rebound anything. Version 6 added two-modifier chords (the L2+R2 layer); older files read
         * unchanged, since a one-modifier token means the same, and they gain the other-display view chords,
         * which no older file could have used.
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
            // Version 5 gave the Pattern, Wave and Help view controls chords. An earlier file's null for them is the
            // old default, not a choice: take the new chord unless the file gave that chord to something else.
            if (version < 5) for (c in LATE_VIEW_CHORDS) {
                val value = entries[c.name]
                if (value != null && value !is JsonNull) continue
                val b = DEFAULT.binding(c) ?: continue
                map = map.with(c, null)
                if (map.conflicts(c, b).isEmpty()) map = map.with(c, b)
            }
            map.validationError()?.let { throw IllegalArgumentException(it) }
            return map
        }

        /** View controls that were unbound before keymap version 5. */
        private val LATE_VIEW_CHORDS = listOf(Control.SHOW_PATTERN, Control.SHOW_WAVE, Control.SHOW_HELP)

        /** A control's default binding in keymap file [version]: Redo was R2+A before version 3; [LATE_VIEW_CHORDS] were unbound before 5. */
        private fun defaultIn(control: Control, version: Int): Binding? = when {
            control == Control.REDO && version < 3 -> Binding(Keys.BUTTON_A, R2)
            control in LATE_VIEW_CHORDS && version < 5 -> null
            else -> DEFAULT.binding(control)
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
                val modifiers = d.modifiers.map { m -> physical(m)?.takeIf { it in MODIFIERS } ?: m }.toSet()
                val moved = button?.let { Binding(it, modifiers) }
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
